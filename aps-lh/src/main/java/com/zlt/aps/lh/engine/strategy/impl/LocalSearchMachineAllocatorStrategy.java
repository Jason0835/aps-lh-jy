/**
 * Copyright (c) 2008, 智立通（厦门）科技有限公司 All rights reserved。
 */
package com.zlt.aps.lh.engine.strategy.impl;

import com.zlt.aps.lh.api.constant.LhScheduleConstant;
import com.zlt.aps.lh.api.constant.LhScheduleParamConstant;
import com.zlt.aps.lh.api.domain.dto.MachineCleaningWindowDTO;
import com.zlt.aps.lh.api.domain.dto.MachineScheduleDTO;
import com.zlt.aps.lh.api.domain.dto.ShiftProductionControlDTO;
import com.zlt.aps.lh.api.domain.dto.SkuScheduleDTO;
import com.zlt.aps.lh.api.domain.vo.LhShiftConfigVO;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.engine.strategy.ICapacityCalculateStrategy;
import com.zlt.aps.lh.engine.strategy.IFirstInspectionBalanceStrategy;
import com.zlt.aps.lh.engine.strategy.IMachineMatchStrategy;
import com.zlt.aps.lh.engine.strategy.IMouldChangeBalanceStrategy;
import com.zlt.aps.lh.util.FirstInspectionQtyUtil;
import com.zlt.aps.lh.util.LhScheduleTimeUtil;
import com.zlt.aps.lh.util.MachineCleaningOverlapUtil;
import com.zlt.aps.lh.util.ShiftCapacityResolverUtil;
import com.zlt.aps.lh.util.ShiftProductionControlUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 局部搜索机台分配器
 * <p>用于在小规模候选机台场景下，基于 DFS + 剪枝选择当前 SKU 的首选机台。</p>
 *
 * @author APS
 */
@Slf4j
@Component
public class LocalSearchMachineAllocatorStrategy {

    /** 每分钟毫秒数 */
    private static final long MILLIS_PER_MINUTE = 60_000L;
    /** 不可行分支的惩罚分 */
    private static final long INFEASIBLE_PENALTY = 1_000_000L;
    /** 喷砂顺延场景的最小重试次数 */
    private static final int MIN_SWITCH_DELAY_RETRY_COUNT = 2;

    /**
     * 选择当前 SKU 的首选机台。
     *
     * @param context 排程上下文
     * @param windowSkuList 局部搜索 SKU 窗口（第 0 个必须为当前 SKU）
     * @param currentCandidates 当前 SKU 的候选机台列表
     * @param shifts 排程班次窗口
     * @param machineMatch 机台匹配策略
     * @param mouldChangeBalance 换模均衡策略
     * @param inspectionBalance 首检均衡策略
     * @param capacityCalculate 产能计算策略
     * @return 选出的首选机台；若无法稳定给出则返回 null
     */
    public MachineScheduleDTO selectBestMachine(LhScheduleContext context,
                                                List<SkuScheduleDTO> windowSkuList,
                                                List<MachineScheduleDTO> currentCandidates,
                                                List<LhShiftConfigVO> shifts,
                                                IMachineMatchStrategy machineMatch,
                                                IMouldChangeBalanceStrategy mouldChangeBalance,
                                                IFirstInspectionBalanceStrategy inspectionBalance,
                                                ICapacityCalculateStrategy capacityCalculate) {
        // 基础输入不完整时直接放弃局部搜索，交给外层常规选机逻辑兜底
        if (CollectionUtils.isEmpty(windowSkuList) || CollectionUtils.isEmpty(currentCandidates) || CollectionUtils.isEmpty(shifts)) {
            log.debug("局部搜索输入不足，跳过评估, SKU窗口: {}, 候选机台: {}, 班次: {}",
                    CollectionUtils.isEmpty(windowSkuList) ? 0 : windowSkuList.size(),
                    CollectionUtils.isEmpty(currentCandidates) ? 0 : currentCandidates.size(),
                    CollectionUtils.isEmpty(shifts) ? 0 : shifts.size());
            return null;
        }
        SkuScheduleDTO currentSku = windowSkuList.get(0);
        log.info("局部搜索选机开始, 当前SKU: {}, 窗口SKU数: {}, 候选机台数: {}, 搜索深度: {}, 时间预算: {}ms",
                currentSku.getMaterialCode(), windowSkuList.size(), currentCandidates.size(),
                context.getScheduleConfig().getLocalSearchDepth(),
                context.getScheduleConfig().getLocalSearchTimeBudgetMs());
        // 设置搜索时间预算，避免局部搜索阻塞主排程流程
        long deadlineMs = System.currentTimeMillis() + context.getScheduleConfig().getLocalSearchTimeBudgetMs();
        int[] bestFeasibleCountHolder = new int[]{-1};
        long[] bestPenaltyHolder = new long[]{Long.MAX_VALUE};
        String[] bestFirstMachineCodeHolder = new String[]{null};

        Map<String, Date> virtualMachineEndTimeMap = new HashMap<>(16);
        List<LocalSearchReservationToken> reservationStack = new ArrayList<>(16);

        dfsSelect(context, windowSkuList, 0, currentCandidates, machineMatch, mouldChangeBalance, inspectionBalance,
                capacityCalculate, shifts, virtualMachineEndTimeMap, reservationStack, 0, 0L, null, deadlineMs,
                bestFeasibleCountHolder, bestPenaltyHolder, bestFirstMachineCodeHolder);

        // DFS 只记录首台机台编码，这里回填对应机台对象给上层使用
        if (StringUtils.isEmpty(bestFirstMachineCodeHolder[0])) {
            log.warn("局部搜索未找到可行首选机台, 当前SKU: {}, 窗口SKU数: {}, 候选机台数: {}, 最优可行数: {}, 最优评分: {}",
                    currentSku.getMaterialCode(), windowSkuList.size(), currentCandidates.size(),
                    bestFeasibleCountHolder[0], bestPenaltyHolder[0]);
            return null;
        }
        for (MachineScheduleDTO machine : currentCandidates) {
            if (machine != null && bestFirstMachineCodeHolder[0].equals(machine.getMachineCode())) {
                log.info("局部搜索选机完成, 当前SKU: {}, 首选机台: {}, 可行数: {}, 评分: {}",
                        currentSku.getMaterialCode(), bestFirstMachineCodeHolder[0],
                        bestFeasibleCountHolder[0], bestPenaltyHolder[0]);
                return machine;
            }
        }
        log.warn("局部搜索首选机台未回填到当前候选列表, 当前SKU: {}, 首选机台: {}, 候选机台数: {}",
                currentSku.getMaterialCode(), bestFirstMachineCodeHolder[0], currentCandidates.size());
        return null;
    }

    /**
     * 深度优先搜索选择首选机台。
     *
     * @param context 排程上下文
     * @param windowSkuList 局部搜索 SKU 窗口
     * @param level 当前层级
     * @param firstLevelCandidates 第 0 层候选机台
     * @param machineMatch 机台匹配策略
     * @param mouldChangeBalance 换模均衡策略
     * @param inspectionBalance 首检均衡策略
     * @param capacityCalculate 产能计算策略
     * @param shifts 排程班次窗口
     * @param virtualMachineEndTimeMap 虚拟机台结束时间
     * @param reservationStack 预占资源栈
     * @param currentFeasibleCount 当前已排可行数量
     * @param currentPenalty 当前累计惩罚分
     * @param firstMachineCode 分支首台机台编码
     * @param deadlineMs 搜索截止时间
     * @param bestFeasibleCountHolder 当前最优可行数量
     * @param bestPenaltyHolder 当前最优惩罚分
     * @param bestFirstMachineCodeHolder 当前最优首台机台编码
     * @return void
     */
    private void dfsSelect(LhScheduleContext context,
                           List<SkuScheduleDTO> windowSkuList,
                           int level,
                           List<MachineScheduleDTO> firstLevelCandidates,
                           IMachineMatchStrategy machineMatch,
                           IMouldChangeBalanceStrategy mouldChangeBalance,
                           IFirstInspectionBalanceStrategy inspectionBalance,
                           ICapacityCalculateStrategy capacityCalculate,
                           List<LhShiftConfigVO> shifts,
                           Map<String, Date> virtualMachineEndTimeMap,
                           List<LocalSearchReservationToken> reservationStack,
                           int currentFeasibleCount,
                           long currentPenalty,
                           String firstMachineCode,
                           long deadlineMs,
                           int[] bestFeasibleCountHolder,
                           long[] bestPenaltyHolder,
                           String[] bestFirstMachineCodeHolder) {
        // 超时立即停止，确保在时间预算内返回
        if (System.currentTimeMillis() > deadlineMs) {
            return;
        }
        int totalLevel = windowSkuList.size();
        // 到达叶子节点后尝试更新最优解
        if (level >= totalLevel) {
            updateBestResult(currentFeasibleCount, currentPenalty, firstMachineCode,
                    bestFeasibleCountHolder, bestPenaltyHolder, bestFirstMachineCodeHolder);
            return;
        }

        // 剪枝：理论可行上限已落后于当前最优时，直接跳过该分支
        int maxFeasibleUpperBound = currentFeasibleCount + (totalLevel - level);
        if (maxFeasibleUpperBound < bestFeasibleCountHolder[0]) {
            return;
        }
        if (maxFeasibleUpperBound == bestFeasibleCountHolder[0] && currentPenalty >= bestPenaltyHolder[0]) {
            return;
        }

        SkuScheduleDTO currentSku = windowSkuList.get(level);
        List<MachineScheduleDTO> candidates = level == 0
                ? firstLevelCandidates
                : matchMachinesForSimulation(context, machineMatch, currentSku);
        if (CollectionUtils.isEmpty(candidates)) {
            // 本层无可行机台，按不可行分支处理并继续探索后续层
            dfsSelect(context, windowSkuList, level + 1, firstLevelCandidates, machineMatch, mouldChangeBalance,
                    inspectionBalance, capacityCalculate, shifts, virtualMachineEndTimeMap, reservationStack,
                    currentFeasibleCount, currentPenalty + INFEASIBLE_PENALTY, firstMachineCode, deadlineMs,
                    bestFeasibleCountHolder, bestPenaltyHolder, bestFirstMachineCodeHolder);
            return;
        }

        boolean hasFeasibleBranch = false;
        for (int i = 0; i < candidates.size(); i++) {
            if (System.currentTimeMillis() > deadlineMs) {
                break;
            }
            MachineScheduleDTO candidateMachine = candidates.get(i);
            LocalSearchReservationToken token = reserveAssignment(context, currentSku, candidateMachine,
                    mouldChangeBalance, inspectionBalance, capacityCalculate, shifts, virtualMachineEndTimeMap, i);
            if (token == null) {
                continue;
            }
            hasFeasibleBranch = true;
            String machineCode = token.getMachineCode();
            Date previousEndTime = virtualMachineEndTimeMap.put(machineCode, token.getSpecEndTime());
            reservationStack.add(token);

            String nextFirstMachineCode = StringUtils.isEmpty(firstMachineCode) ? machineCode : firstMachineCode;
            dfsSelect(context, windowSkuList, level + 1, firstLevelCandidates, machineMatch, mouldChangeBalance,
                    inspectionBalance, capacityCalculate, shifts, virtualMachineEndTimeMap, reservationStack,
                    currentFeasibleCount + 1, currentPenalty + token.getPenaltyScore(), nextFirstMachineCode, deadlineMs,
                    bestFeasibleCountHolder, bestPenaltyHolder, bestFirstMachineCodeHolder);

            // 回溯：撤销本层预占，恢复现场后继续尝试下一个候选机台
            rollbackReservation(context, token, mouldChangeBalance, inspectionBalance);
            reservationStack.remove(reservationStack.size() - 1);
            if (previousEndTime == null) {
                virtualMachineEndTimeMap.remove(machineCode);
            } else {
                virtualMachineEndTimeMap.put(machineCode, previousEndTime);
            }
        }

        if (!hasFeasibleBranch) {
            dfsSelect(context, windowSkuList, level + 1, firstLevelCandidates, machineMatch, mouldChangeBalance,
                    inspectionBalance, capacityCalculate, shifts, virtualMachineEndTimeMap, reservationStack,
                    currentFeasibleCount, currentPenalty + INFEASIBLE_PENALTY, firstMachineCode, deadlineMs,
                    bestFeasibleCountHolder, bestPenaltyHolder, bestFirstMachineCodeHolder);
        }
    }

    /**
     * 在局部搜索模拟分支中匹配候选机台。
     * <p>模拟分支仅用于评估，不应输出最终决策日志口径以外的跟踪日志。</p>
     *
     * @param context 排程上下文
     * @param machineMatch 机台匹配策略
     * @param currentSku 当前SKU
     * @return 候选机台列表
     */
    private List<MachineScheduleDTO> matchMachinesForSimulation(LhScheduleContext context,
                                                                 IMachineMatchStrategy machineMatch,
                                                                 SkuScheduleDTO currentSku) {
        context.enterPriorityTraceMuteScope();
        try {
            return machineMatch.matchMachines(context, currentSku);
        } finally {
            context.exitPriorityTraceMuteScope();
        }
    }

    /**
     * 更新当前最优解。
     *
     * @param feasibleCount 可行数量
     * @param penaltyScore 惩罚分
     * @param firstMachineCode 首台机台编码
     * @param bestFeasibleCountHolder 最优可行数量
     * @param bestPenaltyHolder 最优惩罚分
     * @param bestFirstMachineCodeHolder 最优首台机台编码
     * @return void
     */
    private void updateBestResult(int feasibleCount,
                                  long penaltyScore,
                                  String firstMachineCode,
                                  int[] bestFeasibleCountHolder,
                                  long[] bestPenaltyHolder,
                                  String[] bestFirstMachineCodeHolder) {
        if (StringUtils.isEmpty(firstMachineCode)) {
            return;
        }
        if (feasibleCount > bestFeasibleCountHolder[0]
                || (feasibleCount == bestFeasibleCountHolder[0] && penaltyScore < bestPenaltyHolder[0])) {
            bestFeasibleCountHolder[0] = feasibleCount;
            bestPenaltyHolder[0] = penaltyScore;
            bestFirstMachineCodeHolder[0] = firstMachineCode;
        }
    }

    /**
     * 预占单个 SKU 在某机台上的关键资源。
     *
     * @param context 排程上下文
     * @param sku 待排 SKU
     * @param machine 候选机台
     * @param mouldChangeBalance 换模均衡策略
     * @param inspectionBalance 首检均衡策略
     * @param capacityCalculate 产能计算策略
     * @param shifts 排程班次窗口
     * @param virtualMachineEndTimeMap 虚拟机台结束时间
     * @param machineRank 候选排序位次（越小越优）
     * @return 预占令牌；不可行返回 null
     */
    private LocalSearchReservationToken reserveAssignment(LhScheduleContext context,
                                                          SkuScheduleDTO sku,
                                                          MachineScheduleDTO machine,
                                                          IMouldChangeBalanceStrategy mouldChangeBalance,
                                                          IFirstInspectionBalanceStrategy inspectionBalance,
                                                          ICapacityCalculateStrategy capacityCalculate,
                                                          List<LhShiftConfigVO> shifts,
                                                          Map<String, Date> virtualMachineEndTimeMap,
                                                          int machineRank) {
        if (machine == null || StringUtils.isEmpty(machine.getMachineCode())) {
            return null;
        }
        String machineCode = machine.getMachineCode();
        // 按“机台准备 -> 换模 -> 首检 -> 产能估算”的顺序串行预占资源
        Date machineEndTime = resolveMachineEndTime(context, machine, shifts, virtualMachineEndTimeMap);
        Date machineReadyTime = capacityCalculate.calculateStartTime(context, machineCode, machineEndTime);
        Date mouldChangeStartTime = null;
        Date mouldChangeCompleteTime = null;
        Date inspectionTime = null;
        Date candidateSwitchStartTime = ShiftProductionControlUtil.resolveEarliestSwitchStartTime(
                context, machineReadyTime);
        int maxDelayRetryCount = resolveMaxSwitchDelayRetryCount(machine);
        for (int retry = 0; retry < maxDelayRetryCount; retry++) {
            mouldChangeStartTime = mouldChangeBalance.allocateMouldChange(
                    context,
                    machineCode,
                    candidateSwitchStartTime,
                    LhScheduleTimeUtil.getMouldChangeTotalHours(context),
                    sku,
                    IMouldChangeBalanceStrategy.ACTION_NEW_SPEC_MOULD_CHANGE);
            if (mouldChangeStartTime == null) {
                return null;
            }
            mouldChangeCompleteTime = LhScheduleTimeUtil.addHours(
                    mouldChangeStartTime, LhScheduleTimeUtil.getMouldChangeTotalHours(context));
            inspectionTime = inspectionBalance.allocateInspection(context, machineCode, mouldChangeCompleteTime);
            if (inspectionTime == null) {
                // 首检失败需要回滚换模，避免污染全局资源状态
                mouldChangeBalance.rollbackMouldChange(context, mouldChangeStartTime);
                return null;
            }
            Date delayedSwitchStartTime = resolveDelayedSwitchStartTime(machine, mouldChangeStartTime, inspectionTime);
            if (!delayedSwitchStartTime.after(mouldChangeStartTime)) {
                break;
            }
            inspectionBalance.rollbackInspection(context, inspectionTime);
            mouldChangeBalance.rollbackMouldChange(context, mouldChangeStartTime);
            mouldChangeStartTime = null;
            mouldChangeCompleteTime = null;
            inspectionTime = null;
            candidateSwitchStartTime = delayedSwitchStartTime;
        }
        if (mouldChangeStartTime == null || inspectionTime == null) {
            return null;
        }
        // 业务口径：换模总时长已包含首检时长，局部搜索与主流程保持一致，不再额外 +FIRST_INSPECTION_HOURS
        Date productionStartTime = inspectionTime;
        int machineMouldQty = ShiftCapacityResolverUtil.resolveMachineMouldQty(machine);
        int runtimeShiftCapacity = ShiftCapacityResolverUtil.resolveRuntimeShiftCapacity(
                context, machine, sku.getShiftCapacity());
        Date firstProductionStartTime = ShiftProductionControlUtil.resolveFirstSchedulableStartIgnoringCleaning(
                context,
                machineCode,
                productionStartTime,
                shifts,
                runtimeShiftCapacity,
                sku.getLhTimeSeconds(),
                machineMouldQty);
        if (firstProductionStartTime == null) {
            inspectionBalance.rollbackInspection(context, inspectionTime);
            mouldChangeBalance.rollbackMouldChange(context, mouldChangeStartTime);
            return null;
        }
        LocalSearchCapacityEstimate capacityEstimate = estimateCapacity(
                context, sku, machine, mouldChangeStartTime, firstProductionStartTime, shifts);
        if (capacityEstimate.getTotalQty() <= 0 || capacityEstimate.getSpecEndTime() == null) {
            // 产能不可行时回滚已占用的首检/换模窗口
            inspectionBalance.rollbackInspection(context, inspectionTime);
            mouldChangeBalance.rollbackMouldChange(context, mouldChangeStartTime);
            return null;
        }
        long penaltyScore = capacityEstimate.getPenaltyScore() + machineRank;
        return new LocalSearchReservationToken(machineCode, mouldChangeStartTime, inspectionTime,
                capacityEstimate.getSpecEndTime(), penaltyScore);
    }

    /**
     * 回滚预占资源。
     *
     * @param context 排程上下文
     * @param token 预占令牌
     * @param mouldChangeBalance 换模均衡策略
     * @param inspectionBalance 首检均衡策略
     * @return void
     */
    private void rollbackReservation(LhScheduleContext context,
                                     LocalSearchReservationToken token,
                                     IMouldChangeBalanceStrategy mouldChangeBalance,
                                     IFirstInspectionBalanceStrategy inspectionBalance) {
        if (token == null) {
            return;
        }
        inspectionBalance.rollbackInspection(context, token.getInspectionTime());
        mouldChangeBalance.rollbackMouldChange(context, token.getMouldChangeStartTime());
    }

    /**
     * 估算从指定开产时间起在班次窗口内的可排产量与收尾时间。
     *
     * @param context 排程上下文
     * @param sku 待排 SKU
     * @param machine 候选机台
     * @param productionStartTime 开产时间
     * @param shifts 排程班次窗口
     * @return 产能估算结果
     */
    private LocalSearchCapacityEstimate estimateCapacity(LhScheduleContext context,
                                                         SkuScheduleDTO sku,
                                                         MachineScheduleDTO machine,
                                                         Date mouldChangeStartTime,
                                                         Date productionStartTime,
                                                         List<LhShiftConfigVO> shifts) {
        if (sku == null || machine == null || productionStartTime == null || CollectionUtils.isEmpty(shifts)) {
            return LocalSearchCapacityEstimate.empty();
        }
        int lhTimeSeconds = sku.getLhTimeSeconds();
        int mouldQty = ShiftCapacityResolverUtil.resolveMachineMouldQty(machine);
        int shiftCapacity = ShiftCapacityResolverUtil.resolveRuntimeShiftCapacity(
                context, machine, sku.getShiftCapacity());
        int remainingQty = sku.resolveTargetScheduleQty();
        if (lhTimeSeconds <= 0 || remainingQty <= 0) {
            return LocalSearchCapacityEstimate.empty();
        }
        List<MachineCleaningWindowDTO> cleaningWindowList = resolveEffectiveCleaningWindowList(
                machine, mouldChangeStartTime, productionStartTime);
        int dryIceLossQty = context.getParamIntValue(
                LhScheduleParamConstant.DRY_ICE_LOSS_QTY, LhScheduleConstant.DRY_ICE_LOSS_QTY);
        int dryIceDurationHours = context.getParamIntValue(
                LhScheduleParamConstant.DRY_ICE_DURATION_HOURS, LhScheduleConstant.DRY_ICE_DURATION_HOURS);

        Date cursorStartTime = productionStartTime;
        Date mouldChangeCompleteTime = mouldChangeStartTime == null ? null
                : LhScheduleTimeUtil.addHours(mouldChangeStartTime, LhScheduleTimeUtil.getMouldChangeTotalHours(context));
        LhShiftConfigVO firstInspectionShift = FirstInspectionQtyUtil.resolveAttributionShift(
                shifts, mouldChangeCompleteTime);
        int firstInspectionShiftIndex = Objects.isNull(firstInspectionShift)
                || Objects.isNull(firstInspectionShift.getShiftIndex()) ? -1 : firstInspectionShift.getShiftIndex();
        int firstInspectionQty = FirstInspectionQtyUtil.resolvePreviewFirstInspectionQty(
                context, firstInspectionShift, shiftCapacity, Math.max(remainingQty, shiftCapacity),
                null, machine.getMachineCode());
        Date specEndTime = null;
        int totalQty = 0;
        boolean started = false;

        for (LhShiftConfigVO shift : shifts) {
            if (remainingQty <= 0) {
                break;
            }
            if (!started) {
                if (cursorStartTime.before(shift.getShiftEndDateTime())) {
                    started = true;
                } else {
                    continue;
                }
            }
            ShiftProductionControlDTO control = ShiftProductionControlUtil.resolveEffectiveControl(context, shift, cursorStartTime);
            if (control == null || !control.isCanSchedule()) {
                continue;
            }
            Date effectiveStartTime = control.getEffectiveStartTime();
            Date effectiveEndTime = control.getEffectiveEndTime();

            // 统一按班产主口径或回退公式估算残班/整班计划量。
            int shiftMaxQty = ShiftCapacityResolverUtil.resolveShiftCapacityWithDowntime(
                    context.getDevicePlanShutList(),
                    cleaningWindowList,
                    machine.getMachineCode(),
                    effectiveStartTime,
                    effectiveEndTime,
                    shiftCapacity,
                    lhTimeSeconds,
                    mouldQty,
                    ShiftCapacityResolverUtil.resolveShiftDurationSeconds(shift),
                    dryIceLossQty,
                    dryIceDurationHours);
            shiftMaxQty = ShiftProductionControlUtil.deductCapacityByControl(control, shiftMaxQty, mouldQty);
            shiftMaxQty = FirstInspectionQtyUtil.resolveNormalCapacityAfterFirstInspection(
                    context, shift, shiftMaxQty, firstInspectionShiftIndex, firstInspectionQty,
                    shiftCapacity, null);
            if (shiftMaxQty <= 0) {
                continue;
            }

            int allocationQty = ShiftCapacityResolverUtil.normalizeAllocatedShiftQty(
                    Math.min(remainingQty, shiftMaxQty), shiftMaxQty, mouldQty);
            if (allocationQty <= 0) {
                continue;
            }
            totalQty += allocationQty;
            remainingQty -= allocationQty;
            specEndTime = ShiftCapacityResolverUtil.resolveShiftPlanEndTime(
                    context.getDevicePlanShutList(),
                    cleaningWindowList,
                    machine.getMachineCode(),
                    effectiveStartTime,
                    effectiveEndTime,
                    allocationQty,
                    shiftMaxQty);
            // 当前班次结束后再推进到下一班次，避免跨班次重叠计算
            cursorStartTime = effectiveEndTime;
        }
        totalQty += resolveFirstInspectionCapacityOutsideProductionWindow(
                context, shifts, mouldChangeCompleteTime, productionStartTime, shiftCapacity, totalQty,
                machine.getMachineCode());

        if (totalQty <= 0 || specEndTime == null) {
            return LocalSearchCapacityEstimate.empty();
        }
        long penaltyScore = productionStartTime.getTime() / MILLIS_PER_MINUTE + (long) remainingQty * INFEASIBLE_PENALTY;
        return new LocalSearchCapacityEstimate(totalQty, specEndTime, penaltyScore);
    }

    private int resolveFirstInspectionCapacityOutsideProductionWindow(LhScheduleContext context,
                                                                       List<LhShiftConfigVO> shifts,
                                                                       Date mouldChangeCompleteTime,
                                                                       Date productionStartTime,
                                                                       int shiftCapacity,
                                                                       int remainingQty,
                                                                       String machineCode) {
        LhShiftConfigVO attributionShift = FirstInspectionQtyUtil.resolveAttributionShift(shifts, mouldChangeCompleteTime);
        if (attributionShift == null || productionStartTime == null
                || productionStartTime.before(attributionShift.getShiftEndDateTime())) {
            return 0;
        }
        Map<Integer, Integer> firstInspectionCapacityMap = FirstInspectionQtyUtil.applyFirstInspectionQtyToCapacityMap(
                context, shifts, mouldChangeCompleteTime, new HashMap<Integer, Integer>(0),
                shiftCapacity, Math.max(remainingQty, shiftCapacity),
                null, machineCode);
        Integer firstInspectionQty = firstInspectionCapacityMap.get(attributionShift.getShiftIndex());
        return Math.max(0, Objects.isNull(firstInspectionQty) ? 0 : firstInspectionQty);
    }

    /**
     * 解析局部搜索估产时需要生效的清洗窗口。
     *
     * @param machine 候选机台
     * @param switchStartTime 换模开始时间
     * @param firstProductionStartTime 首个可排产开始时间
     * @return 有效清洗窗口列表
     */
    private List<MachineCleaningWindowDTO> resolveEffectiveCleaningWindowList(MachineScheduleDTO machine,
                                                                              Date switchStartTime,
                                                                              Date firstProductionStartTime) {
        if (machine == null || CollectionUtils.isEmpty(machine.getCleaningWindowList())) {
            return new ArrayList<>(0);
        }
        return new ArrayList<>(MachineCleaningOverlapUtil.excludeOverlapWindows(
                machine.getCleaningWindowList(), switchStartTime, firstProductionStartTime));
    }

    /**
     * 解析喷砂清洗导致的切换顺延起点。
     *
     * @param machine 候选机台
     * @param switchStartTime 候选切换开始时间
     * @param productionStartTime 候选开产时间
     * @return 顺延后的切换开始时间
     */
    private Date resolveDelayedSwitchStartTime(MachineScheduleDTO machine,
                                               Date switchStartTime,
                                               Date productionStartTime) {
        if (machine == null) {
            return switchStartTime;
        }
        return MachineCleaningOverlapUtil.resolveDelayedSwitchStartBySandBlast(
                machine.getCleaningWindowList(), switchStartTime, productionStartTime);
    }

    /**
     * 计算喷砂重叠场景下的最大顺延重试次数。
     *
     * @param machine 候选机台
     * @return 重试次数
     */
    private int resolveMaxSwitchDelayRetryCount(MachineScheduleDTO machine) {
        if (machine == null || CollectionUtils.isEmpty(machine.getCleaningWindowList())) {
            return MIN_SWITCH_DELAY_RETRY_COUNT;
        }
        return Math.max(machine.getCleaningWindowList().size() + 1, MIN_SWITCH_DELAY_RETRY_COUNT);
    }

    /**
     * 获取机台计算用结束时间（优先使用虚拟状态）。
     *
     * @param machine 候选机台
     * @param virtualMachineEndTimeMap 虚拟机台结束时间
     * @return 机台结束时间
     */
    private Date resolveMachineEndTime(LhScheduleContext context,
                                       MachineScheduleDTO machine,
                                       List<LhShiftConfigVO> shifts,
                                       Map<String, Date> virtualMachineEndTimeMap) {
        Date virtualEndTime = virtualMachineEndTimeMap.get(machine.getMachineCode());
        if (virtualEndTime != null) {
            return virtualEndTime;
        }
        if (machine.getEstimatedEndTime() != null) {
            return machine.getEstimatedEndTime();
        }
        if (!CollectionUtils.isEmpty(shifts) && shifts.get(0).getShiftStartDateTime() != null) {
            return shifts.get(0).getShiftStartDateTime();
        }
        if (context != null && context.getScheduleDate() != null) {
            return context.getScheduleDate();
        }
        return new Date();
    }

}

/**
 * 局部搜索预占令牌
 *
 * @author APS
 */
final class LocalSearchReservationToken {

    private final String machineCode;
    private final Date mouldChangeStartTime;
    private final Date inspectionTime;
    private final Date specEndTime;
    private final long penaltyScore;

    LocalSearchReservationToken(String machineCode, Date mouldChangeStartTime, Date inspectionTime,
                                Date specEndTime, long penaltyScore) {
        this.machineCode = machineCode;
        this.mouldChangeStartTime = mouldChangeStartTime;
        this.inspectionTime = inspectionTime;
        this.specEndTime = specEndTime;
        this.penaltyScore = penaltyScore;
    }

    String getMachineCode() {
        return machineCode;
    }

    Date getMouldChangeStartTime() {
        return mouldChangeStartTime;
    }

    Date getInspectionTime() {
        return inspectionTime;
    }

    Date getSpecEndTime() {
        return specEndTime;
    }

    long getPenaltyScore() {
        return penaltyScore;
    }
}

/**
 * 局部搜索产能估算结果
 *
 * @author APS
 */
final class LocalSearchCapacityEstimate {

    private final int totalQty;
    private final Date specEndTime;
    private final long penaltyScore;

    LocalSearchCapacityEstimate(int totalQty, Date specEndTime, long penaltyScore) {
        this.totalQty = totalQty;
        this.specEndTime = specEndTime;
        this.penaltyScore = penaltyScore;
    }

    static LocalSearchCapacityEstimate empty() {
        return new LocalSearchCapacityEstimate(0, null, Long.MAX_VALUE);
    }

    int getTotalQty() {
        return totalQty;
    }

    Date getSpecEndTime() {
        return specEndTime;
    }

    long getPenaltyScore() {
        return penaltyScore;
    }
}
