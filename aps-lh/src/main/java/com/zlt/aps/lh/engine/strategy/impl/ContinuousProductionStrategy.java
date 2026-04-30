/**
 * Copyright (c) 2008, 智立通（厦门）科技有限公司 All rights reserved。
 */
package com.zlt.aps.lh.engine.strategy.impl;

import com.zlt.aps.lh.api.constant.LhScheduleConstant;
import com.zlt.aps.lh.api.constant.LhScheduleParamConstant;
import com.zlt.aps.lh.api.domain.dto.MachineCleaningWindowDTO;
import com.zlt.aps.lh.component.TargetScheduleQtyResolver;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.api.domain.dto.MachineScheduleDTO;
import com.zlt.aps.lh.api.domain.vo.LhShiftConfigVO;
import com.zlt.aps.lh.api.domain.dto.ShiftRuntimeState;
import com.zlt.aps.lh.api.domain.dto.SkuScheduleDTO;
import com.zlt.aps.lh.api.domain.entity.LhScheduleResult;
import com.zlt.aps.lh.api.domain.entity.LhUnscheduledResult;
import com.zlt.aps.lh.api.enums.ScheduleTypeEnum;
import com.zlt.aps.lh.engine.strategy.ICapacityCalculateStrategy;
import com.zlt.aps.lh.engine.strategy.IEndingJudgmentStrategy;
import com.zlt.aps.lh.engine.strategy.IFirstInspectionBalanceStrategy;
import com.zlt.aps.lh.engine.strategy.IMachineMatchStrategy;
import com.zlt.aps.lh.engine.strategy.IMouldChangeBalanceStrategy;
import com.zlt.aps.lh.engine.strategy.IProductionStrategy;
import com.zlt.aps.lh.util.LeftRightMouldUtil;
import com.zlt.aps.lh.util.ShiftFieldUtil;
import com.zlt.aps.lh.util.LhScheduleTimeUtil;
import com.zlt.aps.lh.util.MachineCleaningOverlapUtil;
import com.zlt.aps.lh.util.PriorityTraceLogHelper;
import com.zlt.aps.lh.util.ShiftCapacityResolverUtil;
import com.zlt.aps.lh.util.SingleMouldShiftQtyUtil;
import com.zlt.aps.lh.component.OrderNoGenerator;
import com.zlt.aps.mdm.api.domain.entity.MdmMaterialInfo;
import com.zlt.aps.mdm.api.domain.entity.MdmDevicePlanShut;
import com.zlt.aps.mdm.api.domain.entity.MdmSkuMouldRel;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import javax.annotation.Resource;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 续作排产策略实现
 * <p>处理续作场景下的排产逻辑, 包括换活字块、收尾判定、班次分配、库存调整、降模等</p>
 *
 * @author APS
 */
@Slf4j
@Component("continuousProductionStrategy")
public class ContinuousProductionStrategy implements IProductionStrategy {

    private static final String CONTINUOUS_SCHEDULE_TYPE = "01";
    private static final String AUTO_DATA_SOURCE = "0";
    private static final String ZERO_PLAN_UNSCHEDULED_REASON = "续作结果裁剪为0";
    private static final String TYPE_BLOCK_CLEANING_ANALYSIS = "模具清洗+换活字块";
    private static final String TYPE_BLOCK_TRIGGER_ENDING = "收尾触发";
    private static final String TYPE_BLOCK_TRIGGER_FALLBACK = "在机前规格兜底触发";
    private static final String TYPE_BLOCK_SKIP_REASON_T1_NOT_END =
            "T-1 最新记录未收尾，跳过兜底反查";
    private static final int TYPE_BLOCK_SWITCH_MAX_ATTEMPTS = 16;

    @Resource
    private OrderNoGenerator orderNoGenerator;

    @Resource
    private IEndingJudgmentStrategy endingJudgmentStrategy;
    @Resource
    private TargetScheduleQtyResolver targetScheduleQtyResolver;

    @Override
    public String getStrategyType() {
        return ScheduleTypeEnum.CONTINUOUS.getCode();
    }

    @Override
    public String getStrategyName() {
        return "continuousProductionStrategy";
    }

    @Override
    public void scheduleTypeBlockChange(LhScheduleContext context) {
        log.info("续作排产 - 收尾后衔接排产, 机台数: {}", context.getMachineScheduleMap().size());

        List<LhShiftConfigVO> shifts = LhScheduleTimeUtil.getScheduleShifts(context, context.getScheduleDate());

        // 基于续作收尾阶段回写后的真实收尾时间，按机台收尾先后衔接同产品结构/换活字块
        List<MachineScheduleDTO> endingMachines = context.getMachineScheduleMap().values().stream()
                .filter(m -> m.isEnding() && m.getEstimatedEndTime() != null)
                .collect(Collectors.toList());
        endingMachines.sort(Comparator.comparing(MachineScheduleDTO::getEstimatedEndTime));
        Map<String, String> machineTriggerSourceMap = new HashMap<>(Math.max(16, endingMachines.size() * 2));
        List<MachineScheduleDTO> candidateMachines = new ArrayList<>(endingMachines);
        for (MachineScheduleDTO endingMachine : endingMachines) {
            machineTriggerSourceMap.put(endingMachine.getMachineCode(), TYPE_BLOCK_TRIGGER_ENDING);
        }
        List<MachineScheduleDTO> fallbackMachines = resolveTypeBlockFallbackMachines(context);
        fallbackMachines.sort(Comparator.comparing(MachineScheduleDTO::getEstimatedEndTime));
        for (MachineScheduleDTO fallbackMachine : fallbackMachines) {
            String machineCode = fallbackMachine.getMachineCode();
            if (StringUtils.isEmpty(machineCode) || machineTriggerSourceMap.containsKey(machineCode)) {
                continue;
            }
            candidateMachines.add(fallbackMachine);
            machineTriggerSourceMap.put(machineCode, TYPE_BLOCK_TRIGGER_FALLBACK);
        }
        traceEndingMachineOrder(context, candidateMachines);
        log.info("换活字块候选机台准备完成, 收尾机台: {}, 兜底机台: {}, 候选机台: {}, 待排新增SKU: {}",
                endingMachines.size(), fallbackMachines.size(), candidateMachines.size(),
                context.getNewSpecSkuList().size());

        Map<String, Boolean> strictPriorityOneMachineMap = new HashMap<>(Math.max(16, candidateMachines.size() * 2));
        Map<String, Boolean> completedMachineMap = new HashMap<>(Math.max(16, candidateMachines.size() * 2));
        int typeBlockScheduledCount = 0;
        while (!CollectionUtils.isEmpty(context.getNewSpecSkuList())) {
            List<MachineScheduleDTO> activeMachines = new ArrayList<>(candidateMachines.size());
            for (MachineScheduleDTO machine : candidateMachines) {
                if (machine == null || StringUtils.isEmpty(machine.getMachineCode())) {
                    continue;
                }
                String machineCode = machine.getMachineCode();
                if (Boolean.TRUE.equals(completedMachineMap.get(machineCode))) {
                    continue;
                }
                String triggerSource = machineTriggerSourceMap.get(machineCode);
                if (StringUtils.equals(TYPE_BLOCK_TRIGGER_ENDING, triggerSource) && !machine.isEnding()) {
                    completedMachineMap.put(machineCode, true);
                    continue;
                }
                activeMachines.add(machine);
            }
            if (CollectionUtils.isEmpty(activeMachines)) {
                log.warn("换活字块无可继续尝试机台, 待排新增SKU: {}, 已完成机台: {}",
                        context.getNewSpecSkuList().size(), completedMachineMap.size());
                break;
            }
            activeMachines.sort((leftMachine, rightMachine) -> {
                String leftTriggerSource = machineTriggerSourceMap.get(leftMachine.getMachineCode());
                String rightTriggerSource = machineTriggerSourceMap.get(rightMachine.getMachineCode());
                int triggerOrderCompare = Integer.compare(
                        resolveTypeBlockTriggerOrder(leftTriggerSource),
                        resolveTypeBlockTriggerOrder(rightTriggerSource));
                if (triggerOrderCompare != 0) {
                    return triggerOrderCompare;
                }
                Date leftEndTime = leftMachine.getEstimatedEndTime();
                Date rightEndTime = rightMachine.getEstimatedEndTime();
                if (leftEndTime == null && rightEndTime == null) {
                    return 0;
                }
                if (leftEndTime == null) {
                    return 1;
                }
                if (rightEndTime == null) {
                    return -1;
                }
                return leftEndTime.compareTo(rightEndTime);
            });

            boolean scheduledInCurrentRound = false;
            for (MachineScheduleDTO machine : activeMachines) {
                String machineCode = machine.getMachineCode();
                boolean strictPriorityOneAfterFirstHit = Boolean.TRUE.equals(strictPriorityOneMachineMap.get(machineCode));
                // 续作收尾机台候选SKU按“同胎胚描述+同主花纹 -> 同规格”分层筛选。
                List<SkuScheduleDTO> priorityOneCandidates =
                        filterSameEmbryoDescAndMainPatternCandidates(context, machine);
                List<SkuScheduleDTO> priorityTwoCandidates =
                        !strictPriorityOneAfterFirstHit && CollectionUtils.isEmpty(priorityOneCandidates)
                                ? filterSameSpecCandidates(context, machine) : new ArrayList<SkuScheduleDTO>(0);
                SkuScheduleDTO typeBlockSku = !CollectionUtils.isEmpty(priorityOneCandidates)
                        ? selectPreferredSkuFromCandidates(context, priorityOneCandidates)
                        : selectPreferredSkuFromCandidates(context, priorityTwoCandidates);
                String matchedLayer = !CollectionUtils.isEmpty(priorityOneCandidates) ? "第一层"
                        : (!CollectionUtils.isEmpty(priorityTwoCandidates) ? "第二层" : "未命中");
                if (typeBlockSku == null) {
                    log.debug("换活字块未匹配到SKU, 机台: {}, 触发来源: {}, 第一层候选: {}, 第二层候选: {}",
                            machineCode, machineTriggerSourceMap.get(machineCode),
                            priorityOneCandidates.size(), priorityTwoCandidates.size());
                    traceTypeBlockDecision(context, machine, priorityOneCandidates, priorityTwoCandidates,
                            null, matchedLayer, false, null, machineTriggerSourceMap.get(machineCode));
                    completedMachineMap.put(machineCode, true);
                    continue;
                }
                Date typeBlockStartTime = calcTypeBlockStartTime(context, machine);
                boolean success = appendFollowUpResult(
                        context, machine, typeBlockSku, typeBlockStartTime, shifts, true);
                traceTypeBlockDecision(context, machine, priorityOneCandidates, priorityTwoCandidates,
                        typeBlockSku, matchedLayer, success, typeBlockStartTime,
                        machineTriggerSourceMap.get(machineCode));
                if (!success) {
                    log.warn("换活字块排产失败, 机台: {}, materialCode: {}, 结构: {}, 开始时间: {}, 匹配层级: {}",
                            machineCode, typeBlockSku.getMaterialCode(), typeBlockSku.getStructureName(),
                            LhScheduleTimeUtil.formatDateTime(typeBlockStartTime), matchedLayer);
                    completedMachineMap.put(machineCode, true);
                    continue;
                }
                scheduledInCurrentRound = true;
                typeBlockScheduledCount++;
                if (CollectionUtils.isEmpty(priorityOneCandidates)) {
                    completedMachineMap.put(machineCode, true);
                } else {
                    strictPriorityOneMachineMap.put(machineCode, true);
                }
                if (!machine.isEnding()) {
                    completedMachineMap.put(machineCode, true);
                }
                // 每轮仅落一条结果，随后按更新后的机台收尾时间重新排序。
                break;
            }
            if (!scheduledInCurrentRound) {
                log.warn("本轮换活字块未产生排程结果, 候选机台: {}, 待排新增SKU: {}",
                        activeMachines.size(), context.getNewSpecSkuList().size());
                break;
            }
        }
        log.info("换活字块衔接排产结束, 新增结果数: {}, 剩余新增SKU: {}, 当前排程结果数: {}",
                typeBlockScheduledCount, context.getNewSpecSkuList().size(),
                context.getScheduleResultList().size());
    }

    private int resolveTypeBlockTriggerOrder(String triggerSource) {
        if (StringUtils.equals(TYPE_BLOCK_TRIGGER_ENDING, triggerSource)) {
            return 0;
        }
        if (StringUtils.equals(TYPE_BLOCK_TRIGGER_FALLBACK, triggerSource)) {
            return 1;
        }
        return 2;
    }

    @Override
    public void scheduleContinuousEnding(LhScheduleContext context) {
        log.info("续作排产 - 续作收尾判定, 续作SKU数: {}", context.getContinuousSkuList().size());

        List<LhShiftConfigVO> shifts = LhScheduleTimeUtil.getScheduleShifts(context, context.getScheduleDate());

        for (SkuScheduleDTO sku : context.getContinuousSkuList()) {
            String machineCode = sku.getContinuousMachineCode();
            MachineScheduleDTO machine = context.getMachineScheduleMap().get(machineCode);
            if (machine == null) {
                log.warn("续作SKU未匹配到机台状态，跳过续作排产, materialCode: {}, 续作机台: {}, 目标量: {}",
                        sku.getMaterialCode(), machineCode, sku.resolveTargetScheduleQty());
                continue;
            }

            boolean isEnding = endingJudgmentStrategy.isEnding(context, sku);

            // 滚动衔接时沿用机台继承后的可用时间，避免从重叠窗口首班重复起排。
            Date startTime = resolveContinuousStartTime(context, machine, shifts);
            int machineMouldQty = ShiftCapacityResolverUtil.resolveMachineMouldQty(machine);
            sku.setMouldQty(machineMouldQty);
            LhScheduleResult inheritedResult = findMergeableRollingInheritedResult(context, machineCode, sku.getMaterialCode());
            LhScheduleResult result = inheritedResult != null
                    ? appendScheduleToInheritedResult(context, inheritedResult, machine, sku,
                    startTime, shifts, machineMouldQty, isEnding)
                    : buildScheduleResult(context, machine, sku, startTime, null, shifts, machineMouldQty, isEnding);
            if (result != null) {
                result.setScheduleType("01");
                result.setIsChangeMould("0");
                result.setIsTypeBlock("0");
                result.setIsEnd(isEnding ? "1" : "0");
                if (inheritedResult == null) {
                    context.getScheduleResultList().add(result);
                    registerMachineAssignment(context, machineCode, result);
                }
                // 续作已完成当日排产，不应继续参与后续结构优先级判断。
                context.removePendingSkuFromStructureMap(sku);

                // 如果是收尾，更新机台收尾信息
                if (isEnding && result.getSpecEndTime() != null) {
                    Date actualCompletionTime = resolveActualCompletionTime(context, result);
                    machine.setEnding(true);
                    machine.setEstimatedEndTime(actualCompletionTime);
                    traceContinuousEndingUpdate(context, machine, sku, result, actualCompletionTime);
                }
                log.debug("续作SKU排产完成, materialCode: {}, 机台: {}, 开始时间: {}, 日计划量: {}, 是否收尾: {}",
                        sku.getMaterialCode(), machineCode,
                        LhScheduleTimeUtil.formatDateTime(startTime), result.getDailyPlanQty(), isEnding);
            } else {
                log.warn("续作SKU未生成有效排程结果, materialCode: {}, 机台: {}, 开始时间: {}, 目标量: {}",
                        sku.getMaterialCode(), machineCode,
                        LhScheduleTimeUtil.formatDateTime(startTime), sku.resolveTargetScheduleQty());
            }
        }
        log.info("续作收尾判定结束, 续作SKU: {}, 当前排程结果数: {}, 待新增SKU: {}",
                context.getContinuousSkuList().size(), context.getScheduleResultList().size(),
                context.getNewSpecSkuList().size());
    }

    /**
     * 解析续作起排时间。
     * <p>滚动衔接场景下从机台继承后的可用时间继续排；普通场景仍从窗口首班开始。</p>
     */
    private Date resolveContinuousStartTime(LhScheduleContext context,
                                            MachineScheduleDTO machine,
                                            List<LhShiftConfigVO> shifts) {
        Date defaultStartTime = CollectionUtils.isEmpty(shifts) ? new Date() : shifts.get(0).getShiftStartDateTime();
        if (context == null || !context.isRollingScheduleHandoff()) {
            return defaultStartTime;
        }
        Date appendStartTime = resolveRollingAppendStartTime(context, shifts);
        if (machine == null || machine.getEstimatedEndTime() == null) {
            return appendStartTime != null ? appendStartTime : defaultStartTime;
        }
        if (appendStartTime == null || machine.getEstimatedEndTime().after(appendStartTime)) {
            return machine.getEstimatedEndTime();
        }
        return appendStartTime;
    }

    /**
     * 解析滚动排程的追加起点。
     * <p>只允许续作从目标日第一班开始继续排，避免回写到重叠继承窗口。</p>
     */
    private Date resolveRollingAppendStartTime(LhScheduleContext context, List<LhShiftConfigVO> shifts) {
        if (context == null
                || context.getScheduleTargetDate() == null
                || CollectionUtils.isEmpty(shifts)) {
            return null;
        }
        Date targetDate = LhScheduleTimeUtil.clearTime(context.getScheduleTargetDate());
        Date appendStartTime = null;
        for (LhShiftConfigVO shift : shifts) {
            if (shift == null
                    || shift.getWorkDate() == null
                    || shift.getShiftStartDateTime() == null) {
                continue;
            }
            if (!targetDate.equals(LhScheduleTimeUtil.clearTime(shift.getWorkDate()))) {
                continue;
            }
            if (appendStartTime == null || shift.getShiftStartDateTime().before(appendStartTime)) {
                appendStartTime = shift.getShiftStartDateTime();
            }
        }
        return appendStartTime;
    }

    /**
     * 查找可并入的滚动继承续作结果。
     *
     * @param context 排程上下文
     * @param machineCode 机台编号
     * @param materialCode 物料编码
     * @return 可并入结果；未命中返回 null
     */
    private LhScheduleResult findMergeableRollingInheritedResult(LhScheduleContext context,
                                                                 String machineCode,
                                                                 String materialCode) {
        if (context == null
                || StringUtils.isEmpty(machineCode)
                || StringUtils.isEmpty(materialCode)
                || CollectionUtils.isEmpty(context.getMachineAssignmentMap())) {
            return null;
        }
        List<LhScheduleResult> assignedResults = context.getMachineAssignmentMap().get(machineCode);
        if (CollectionUtils.isEmpty(assignedResults)) {
            return null;
        }
        for (int i = assignedResults.size() - 1; i >= 0; i--) {
            LhScheduleResult assignedResult = assignedResults.get(i);
            if (assignedResult == null
                    || !assignedResult.isRollingInherited()
                    || !StringUtils.equals(materialCode, assignedResult.getMaterialCode())) {
                continue;
            }
            return assignedResult;
        }
        return null;
    }

    /**
     * 将滚动衔接后的续作剩余计划并入已继承结果。
     *
     * @param context 排程上下文
     * @param inheritedResult 已继承结果
     * @param machine 机台
     * @param sku SKU
     * @param startTime 起排时间
     * @param shifts 班次列表
     * @param machineMouldQty 机台模台数
     * @param isEnding 是否收尾
     * @return 合并后的继承结果
     */
    private LhScheduleResult appendScheduleToInheritedResult(LhScheduleContext context,
                                                             LhScheduleResult inheritedResult,
                                                             MachineScheduleDTO machine,
                                                             SkuScheduleDTO sku,
                                                             Date startTime,
                                                             List<LhShiftConfigVO> shifts,
                                                             int machineMouldQty,
                                                             boolean isEnding) {
        LhScheduleResult appendedResult = buildScheduleResult(
                context, machine, sku, startTime, null, shifts, machineMouldQty, isEnding);
        if (appendedResult == null
                || appendedResult.getDailyPlanQty() == null
                || appendedResult.getDailyPlanQty() <= 0) {
            return null;
        }
        for (int shiftIndex = 1; shiftIndex <= LhScheduleConstant.MAX_SHIFT_SLOT_COUNT; shiftIndex++) {
            Integer shiftPlanQty = ShiftFieldUtil.getShiftPlanQty(appendedResult, shiftIndex);
            if (shiftPlanQty == null || shiftPlanQty <= 0) {
                continue;
            }
            ShiftFieldUtil.copyShiftPlanFields(appendedResult, shiftIndex, inheritedResult, shiftIndex);
        }
        inheritedResult.setIsEnd(isEnding ? "1" : "0");
        refreshResultSummary(context, inheritedResult, shifts);
        return inheritedResult;
    }

    @Override
    public void allocateShiftPlanQty(LhScheduleContext context) {
        log.info("续作排产 - 班次计划量分配");

        List<LhShiftConfigVO> shifts = LhScheduleTimeUtil.getScheduleShifts(context, context.getScheduleDate());

        for (LhScheduleResult result : context.getScheduleResultList()) {
            if (!CONTINUOUS_SCHEDULE_TYPE.equals(result.getScheduleType())
                    && !"1".equals(result.getIsTypeBlock())) {
                continue;
            }
            // 重新按班次分配（夜->早->中顺序按可用量分配）
            redistributeShiftQty(context, result, shifts);
        }
    }

    @Override
    public void adjustEmbryoStock(LhScheduleContext context) {
        log.info("续作排产 - 胎胚库存调整");
        List<LhShiftConfigVO> shifts = LhScheduleTimeUtil.getScheduleShifts(context, context.getScheduleDate());

        // 收尾SKU优先占用胎胚库存，普通SKU再按顺序扣减
        Map<String, Integer> embryoStockMap = buildEmbryoStockMap(context);

        // 先处理收尾SKU
        for (LhScheduleResult result : context.getScheduleResultList()) {
            if ("1".equals(result.getIsEnd())) {
                adjustResultByEmbryoStock(context, result, embryoStockMap, shifts);
            }
        }
        // 再处理普通SKU
        for (LhScheduleResult result : context.getScheduleResultList()) {
            if (!"1".equals(result.getIsEnd())) {
                adjustResultByEmbryoStock(context, result, embryoStockMap, shifts);
            }
        }
        refreshContinuousEndingFlagByResult(context);
    }

    @Override
    public void scheduleReduceMould(LhScheduleContext context) {
        log.info("续作排产 - 降模排产");
        List<LhShiftConfigVO> shifts = LhScheduleTimeUtil.getScheduleShifts(context, context.getScheduleDate());

        // 按materialCode分组找出同SKU多机台情况（续作+换活字块统一收口）
        Map<String, List<LhScheduleResult>> skuResultMap = new HashMap<>();
        for (LhScheduleResult result : context.getScheduleResultList()) {
            if (isContinuousPhaseResult(result)) {
                skuResultMap.computeIfAbsent(result.getMaterialCode(), k -> new ArrayList<>()).add(result);
            }
        }

        for (Map.Entry<String, List<LhScheduleResult>> entry : skuResultMap.entrySet()) {
            List<LhScheduleResult> skuResults = entry.getValue();
            if (skuResults.size() <= 1) {
                continue;
            }

            int targetQty = 0;
            SkuScheduleDTO skuDto = findSkuDto(context, entry.getKey());
            if (skuDto != null) {
                targetQty = skuDto.resolveTargetScheduleQty();
            }

            // 总计划量超过当前排产目标量时才降模
            int totalPlanQty = skuResults.stream().mapToInt(ShiftFieldUtil::resolveScheduledQty).sum();
            if (targetQty <= 0 || totalPlanQty <= targetQty) {
                continue;
            }

            // 按胶囊使用次数升序，减少胶囊使用次数少的机台的计划（胶囊已使用次数少的优先下机）
            skuResults.sort(Comparator.comparingInt(r -> {
                MachineScheduleDTO m = context.getMachineScheduleMap().get(r.getLhMachineCode());
                return m != null ? m.getCapsuleUsageCount() : 0;
            }));

            int remaining = targetQty;
            for (LhScheduleResult result : skuResults) {
                int allocation = Math.min(remaining, ShiftFieldUtil.resolveScheduledQty(result));
                redistributeShiftQty(context, result, shifts, allocation);
                remaining -= allocation;
                if (allocation <= 0) {
                    // 当前结果已被完全降为0，保留收尾标记，避免后续继续参与续作产量判断。
                    result.setIsEnd("1");
                }
            }
        }
        // S4.4 收口：零计划续作结果语义统一，并按最终结果同步机台状态。
        finalizeZeroPlanContinuousResults(context);
        // 降模会再次改变最终计划量，收口后再统一复核一次收尾标记，确保落库口径一致。
        refreshContinuousEndingFlagByResult(context);
        syncMachineStateAfterContinuousAdjust(context);
        // 续作阶段全部处理完成后，再按剩余新增待排SKU统一收口结构视图，供S4.5排序使用。
        context.rebuildStructureSkuMapFromPending(context.getNewSpecSkuList());
    }

    @Override
    public void scheduleNewSpecs(LhScheduleContext context,
                                 IMachineMatchStrategy machineMatch,
                                 IMouldChangeBalanceStrategy mouldChangeBalance,
                                 IFirstInspectionBalanceStrategy inspectionBalance,
                                 ICapacityCalculateStrategy capacityCalculate) {
        // 续作策略不处理新增规格排产，空实现
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 在新增SKU列表中查找可直接续作的同产品结构SKU。
     */
    private SkuScheduleDTO findSameStructureContinuousSku(LhScheduleContext context, MachineScheduleDTO machine) {
        if (CollectionUtils.isEmpty(context.getNewSpecSkuList())) {
            return null;
        }
        for (SkuScheduleDTO sku : context.getNewSpecSkuList()) {
            if (isSameStructureContinuousCandidate(context, machine, sku)) {
                return sku;
            }
        }
        return null;
    }

    /**
     * 在新增SKU列表中查找可以换活字块的SKU。
     */
    private SkuScheduleDTO findTypeBlockChangeSku(LhScheduleContext context, MachineScheduleDTO machine) {
        if (CollectionUtils.isEmpty(context.getNewSpecSkuList())) {
            return null;
        }

        for (SkuScheduleDTO sku : context.getNewSpecSkuList()) {
            if (endingJudgmentStrategy.isEnding(context, sku)
                    && isTypeBlockCandidate(context, machine, sku)) {
                return sku;
            }
        }
        for (SkuScheduleDTO sku : context.getNewSpecSkuList()) {
            if (isTypeBlockCandidate(context, machine, sku)) {
                return sku;
            }
        }
        return null;
    }

    /**
     * 续作收尾机台候选SKU分层筛选：同胎胚描述+同主花纹，其次同规格。
     */
    private SkuScheduleDTO findPriorityTypeBlockSku(LhScheduleContext context, MachineScheduleDTO machine) {
        if (CollectionUtils.isEmpty(context.getNewSpecSkuList())) {
            return null;
        }

        // 命中更高优先级后直接返回，不再继续降级筛选。
        List<SkuScheduleDTO> priorityOneCandidates = filterSameEmbryoDescAndMainPatternCandidates(context, machine);
        if (!CollectionUtils.isEmpty(priorityOneCandidates)) {
            return selectPreferredSkuFromCandidates(context, priorityOneCandidates);
        }

        List<SkuScheduleDTO> priorityTwoCandidates = filterSameSpecCandidates(context, machine);
        if (!CollectionUtils.isEmpty(priorityTwoCandidates)) {
            return selectPreferredSkuFromCandidates(context, priorityTwoCandidates);
        }
        return null;
    }

    /**
     * 过滤同胎胚描述且同主花纹的候选SKU。
     */
    private List<SkuScheduleDTO> filterSameEmbryoDescAndMainPatternCandidates(LhScheduleContext context,
                                                                              MachineScheduleDTO machine) {
        List<SkuScheduleDTO> candidateList = new ArrayList<>(context.getNewSpecSkuList().size());
        for (SkuScheduleDTO sku : context.getNewSpecSkuList()) {
            // 优先级1：同时命中胎胚描述和主花纹，才进入本层候选集。
            if (isSameEmbryoDesc(context, machine, sku)
                    && isSameMainPatternStrict(context, machine, sku)) {
                candidateList.add(sku);
            }
        }
        return candidateList;
    }

    /**
     * 过滤同规格的候选SKU。
     */
    private List<SkuScheduleDTO> filterSameSpecCandidates(LhScheduleContext context, MachineScheduleDTO machine) {
        List<SkuScheduleDTO> candidateList = new ArrayList<>(context.getNewSpecSkuList().size());
        for (SkuScheduleDTO sku : context.getNewSpecSkuList()) {
            if (isSameSpec(context, machine, sku)) {
                candidateList.add(sku);
            }
        }
        return candidateList;
    }

    /**
     * 按月度计划SKU排序结果选择候选首位。
     */
    private SkuScheduleDTO selectPreferredSkuFromCandidates(LhScheduleContext context, List<SkuScheduleDTO> candidates) {
        if (CollectionUtils.isEmpty(candidates)) {
            return null;
        }
        return candidates.get(0);
    }

    /**
     * 判断SKU是否满足同产品结构直接续作。
     */
    private boolean isSameStructureContinuousCandidate(LhScheduleContext context,
                                                       MachineScheduleDTO machine,
                                                       SkuScheduleDTO sku) {
        return isSameEmbryo(context, machine, sku)
                && StringUtils.isNotEmpty(resolveMachineStructureKey(context, machine))
                && StringUtils.isNotEmpty(resolveStructureKey(sku))
                && StringUtils.equals(resolveMachineStructureKey(context, machine), resolveStructureKey(sku));
    }

    /**
     * 判断SKU是否满足换活字块条件：同胎胚、同规格、不同花纹。
     */
    private boolean isTypeBlockCandidate(LhScheduleContext context,
                                         MachineScheduleDTO machine,
                                         SkuScheduleDTO sku) {
        if (!isSameEmbryo(context, machine, sku)) {
            return false;
        }
        String machineSpecCode = resolveMachineSpecCode(context, machine);
        String machinePatternKey = resolveMachinePatternKey(context, machine);
        String skuPatternKey = resolvePatternKey(sku.getMainPattern(), sku.getPattern());
        if (StringUtils.isEmpty(machineSpecCode)
                || StringUtils.isEmpty(machinePatternKey)
                || StringUtils.isEmpty(sku.getSpecCode())
                || StringUtils.isEmpty(skuPatternKey)) {
            return false;
        }
        return StringUtils.equals(machineSpecCode, sku.getSpecCode())
                && !StringUtils.equals(machinePatternKey, skuPatternKey);
    }

    /**
     * 判断机台当前物料与候选SKU是否为相同胎胚。
     */
    private boolean isSameEmbryo(LhScheduleContext context, MachineScheduleDTO machine, SkuScheduleDTO sku) {
        String machineEmbryoCode = resolveMachineEmbryoCode(context, machine);
        return StringUtils.isNotEmpty(machineEmbryoCode)
                && StringUtils.isNotEmpty(sku.getEmbryoCode())
                && StringUtils.equals(machineEmbryoCode, sku.getEmbryoCode());
    }

    /**
     * 判断机台当前物料与候选SKU是否为相同胎胚描述。
     */
    private boolean isSameEmbryoDesc(LhScheduleContext context, MachineScheduleDTO machine, SkuScheduleDTO sku) {
        String machineEmbryoDesc = resolveMachineEmbryoDesc(context, machine);
        String skuEmbryoDesc = resolveSkuEmbryoDesc(context, sku);
        return StringUtils.isNotEmpty(machineEmbryoDesc)
                && StringUtils.equals(machineEmbryoDesc, skuEmbryoDesc);
    }

    /**
     * 判断机台当前物料与候选SKU是否为相同主花纹（严格只看mainPattern）。
     */
    private boolean isSameMainPatternStrict(LhScheduleContext context, MachineScheduleDTO machine, SkuScheduleDTO sku) {
        String machineMainPattern = resolveMachineMainPatternStrict(context, machine);
        String skuMainPattern = resolveSkuMainPatternStrict(context, sku);
        // 主花纹按严格口径比较，不回退到普通花纹字段。
        return StringUtils.isNotEmpty(machineMainPattern)
                && StringUtils.equals(machineMainPattern, skuMainPattern);
    }

    /**
     * 判断机台当前物料与候选SKU是否为相同规格。
     */
    private boolean isSameSpec(LhScheduleContext context, MachineScheduleDTO machine, SkuScheduleDTO sku) {
        String machineSpecCode = normalizeCompareToken(resolveMachineSpecCode(context, machine));
        String skuSpecCode = normalizeCompareToken(sku.getSpecCode());
        return StringUtils.isNotEmpty(machineSpecCode)
                && StringUtils.equals(machineSpecCode, skuSpecCode);
    }

    /**
     * 计算换活字块开产时间（无需换模、无需首检，仅消耗换活字块时间）
     */
    private Date calcTypeBlockStartTime(LhScheduleContext context,
                                        MachineScheduleDTO machine) {
        if (machine.getEstimatedEndTime() == null) {
            return null;
        }
        Date switchStartTime = resolveAllowedSwitchStartTime(
                context, machine.getMachineCode(), machine.getEstimatedEndTime());
        // 换活字块：允许切换时间 + 换活字块总耗时
        return LhScheduleTimeUtil.addHours(switchStartTime,
                LhScheduleTimeUtil.getTypeBlockChangeTotalHours(context));
    }

    /**
     * 根据换活字块后的开产时间反推真实换活字块开始时间。
     */
    private Date resolveTypeBlockChangeStartTime(LhScheduleContext context, Date productionStartTime) {
        if (productionStartTime == null) {
            return null;
        }
        // 换活字块结果里记录“真实切换开始时间”，方便换模计划表与结果时间口径一致。
        return LhScheduleTimeUtil.addHours(
                productionStartTime, -LhScheduleTimeUtil.getTypeBlockChangeTotalHours(context));
    }

    /**
     * 解析允许发起切换（换模/换活字块）的开始时间。
     * <p>20:00:00（含）到次日早班前禁止发起切换，需顺延到下一个早班开始时间。</p>
     */
    private Date resolveAllowedSwitchStartTime(LhScheduleContext context,
                                               String machineCode,
                                               Date endingTime) {
        if (endingTime == null) {
            return null;
        }
        Date adjustedTime = endingTime;
        for (int attempt = 0; attempt < TYPE_BLOCK_SWITCH_MAX_ATTEMPTS; attempt++) {
            Date downtimeAdjustedTime = resolveDowntimeAdjustedSwitchStartTime(
                    context, machineCode, adjustedTime);
            if (downtimeAdjustedTime.after(adjustedTime)) {
                adjustedTime = downtimeAdjustedTime;
                continue;
            }
            if (!LhScheduleTimeUtil.isNoMouldChangeTime(context, adjustedTime)) {
                return adjustedTime;
            }
            adjustedTime = LhScheduleTimeUtil.resolveNextMorningAfterNoMouldChangeWindow(context, adjustedTime);
        }
        log.warn("换活字块切换起点达到最大尝试次数, 机台: {}, 原始时间: {}",
                machineCode, LhScheduleTimeUtil.formatDateTime(endingTime));
        return adjustedTime;
    }

    /**
     * 根据停机窗口顺延换活字块切换起点。
     * <p>当候选切换窗口与机台停机窗口重叠时，顺延到重叠停机结束时刻。</p>
     */
    private Date resolveDowntimeAdjustedSwitchStartTime(LhScheduleContext context,
                                                        String machineCode,
                                                        Date candidateStartTime) {
        if (context == null
                || StringUtils.isEmpty(machineCode)
                || candidateStartTime == null) {
            return candidateStartTime;
        }
        Date candidateEndTime = LhScheduleTimeUtil.addHours(
                candidateStartTime, LhScheduleTimeUtil.getTypeBlockChangeTotalHours(context));
        Date latestOverlapEndTime = null;
        if (!CollectionUtils.isEmpty(context.getDevicePlanShutList())) {
            for (MdmDevicePlanShut planShut : context.getDevicePlanShutList()) {
                if (planShut == null
                        || !StringUtils.equals(machineCode, planShut.getMachineCode())
                        || planShut.getBeginDate() == null
                        || planShut.getEndDate() == null
                        || !planShut.getBeginDate().before(planShut.getEndDate())) {
                    continue;
                }
                if (!candidateStartTime.before(planShut.getEndDate())
                        || !planShut.getBeginDate().before(candidateEndTime)) {
                    continue;
                }
                if (latestOverlapEndTime == null || planShut.getEndDate().after(latestOverlapEndTime)) {
                    latestOverlapEndTime = planShut.getEndDate();
                }
            }
        }
        return latestOverlapEndTime != null ? latestOverlapEndTime : candidateStartTime;
    }

    /**
     * 追加续作衔接结果（同产品结构直续或换活字块）。
     */
    private boolean appendFollowUpResult(LhScheduleContext context,
                                         MachineScheduleDTO machine,
                                         SkuScheduleDTO sku,
                                         Date startTime,
                                         List<LhShiftConfigVO> shifts,
                                         boolean typeBlock) {
        if (startTime == null) {
            return false;
        }
        Integer originalTargetScheduleQty = sku.getTargetScheduleQty();
        boolean isEnding = endingJudgmentStrategy.isEnding(context, sku);
        int machineMouldQty = ShiftCapacityResolverUtil.resolveMachineMouldQty(machine);
        sku.setMouldQty(machineMouldQty);
        Date switchStartTime = typeBlock ? resolveTypeBlockChangeStartTime(context, startTime) : null;
        LhScheduleResult result = buildScheduleResult(
                context, machine, sku, startTime, switchStartTime, shifts, machineMouldQty, isEnding);
        if (result == null || result.getDailyPlanQty() == null || result.getDailyPlanQty() <= 0) {
            sku.setTargetScheduleQty(originalTargetScheduleQty);
            return false;
        }
        if (typeBlock) {
            result.setScheduleType("02");  // 换活字块显示为新增类型（用于前端展示）
            result.setIsChangeMould("1");
            result.setIsTypeBlock("1");
            result.setMouldCode(resolveMouldCode(context, sku.getMaterialCode(), machine.getCurrentMaterialCode()));
            // 换活字块虽然不是新增规格换模，但下游换模计划仍按真实切换开始时间生成。
            result.setMouldChangeStartTime(switchStartTime);
        } else {
            result.setScheduleType("01");  // 续作保持不变
            result.setIsChangeMould("0");
            result.setIsTypeBlock("0");
        }
        result.setIsEnd(isEnding ? "1" : "0");
        // 续作衔接结果即便非收尾，也必须补齐可计算完工时刻，避免结果校验失败。
        Date actualCompletionTime = resolveActualCompletionTime(context, result);
        if (actualCompletionTime == null) {
            return false;
        }
        result.setSpecEndTime(actualCompletionTime);
        result.setTdaySpecEndTime(actualCompletionTime);
        if (typeBlock) {
            applyTypeBlockCleaningAnalysis(context, result, shifts);
        }

        context.getScheduleResultList().add(result);
        registerMachineAssignment(context, machine.getMachineCode(), result);
        updateMachineState(context, machine, sku, result);
        context.getNewSpecSkuList().remove(sku);
        log.debug("{}排产完成, 机台: {}, SKU: {}",
                typeBlock ? "换活字块" : "同产品结构续作",
                machine.getMachineCode(), sku.getMaterialCode());
        return true;
    }

    /**
     * 输出续作收尾时间回写日志。
     *
     * @param context 排程上下文
     * @param machine 机台
     * @param sku 续作SKU
     * @param result 排产结果
     * @param actualCompletionTime 实际完工时间
     */
    private void traceContinuousEndingUpdate(LhScheduleContext context, MachineScheduleDTO machine,
                                             SkuScheduleDTO sku, LhScheduleResult result,
                                             Date actualCompletionTime) {
        if (!PriorityTraceLogHelper.isEnabled(context)) {
            return;
        }
        String title = "续作收尾真实时间回写";
        StringBuilder detailBuilder = new StringBuilder(256);
        PriorityTraceLogHelper.appendLine(detailBuilder,
                "机台=" + PriorityTraceLogHelper.safeText(machine.getMachineCode())
                        + ", SKU=" + PriorityTraceLogHelper.safeText(sku.getMaterialCode())
                        + ", 是否收尾=" + PriorityTraceLogHelper.yesNo(machine.isEnding()));
        PriorityTraceLogHelper.appendLine(detailBuilder,
                "结果specEndTime=" + PriorityTraceLogHelper.formatDateTime(result.getSpecEndTime())
                        + ", 回写estimatedEndTime=" + PriorityTraceLogHelper.formatDateTime(actualCompletionTime));
        String detail = detailBuilder.toString().trim();
        log.info("{}\n{}", title, detail);
        PriorityTraceLogHelper.appendProcessLog(context, title, detail);
    }

    /**
     * 输出衔接机台排序总览日志。
     *
     * @param context 排程上下文
     * @param endingMachines 衔接机台列表（包含收尾机台与兜底机台）
     */
    private void traceEndingMachineOrder(LhScheduleContext context, List<MachineScheduleDTO> endingMachines) {
        if (!PriorityTraceLogHelper.isEnabled(context)) {
            return;
        }
        String title = "衔接机台排序总览";
        StringBuilder detailBuilder = new StringBuilder(512);
        PriorityTraceLogHelper.appendLine(detailBuilder,
                "候选机台数=" + PriorityTraceLogHelper.sizeOf(endingMachines));
        int index = 1;
        for (MachineScheduleDTO machine : endingMachines) {
            Date estimatedEndTime = machine.getEstimatedEndTime();
            PriorityTraceLogHelper.appendLine(detailBuilder,
                    index++
                            + ". 机台=" + PriorityTraceLogHelper.safeText(machine.getMachineCode())
                            + ", 当前物料=" + PriorityTraceLogHelper.safeText(machine.getCurrentMaterialCode())
                            + ", 基准时间=" + PriorityTraceLogHelper.formatDateTime(estimatedEndTime)
                            + ", 实际切换起点=" + PriorityTraceLogHelper.formatDateTime(
                            resolveAllowedSwitchStartTime(context, machine.getMachineCode(), estimatedEndTime)));
        }
        String detail = detailBuilder.toString().trim();
        log.info("{}\n{}", title, detail);
        PriorityTraceLogHelper.appendProcessLog(context, title, detail);
    }

    /**
     * 输出单机台续作衔接决策日志。
     *
     * @param context 排程上下文
     * @param machine 收尾机台
     * @param priorityOneCandidates 第一层候选
     * @param priorityTwoCandidates 第二层候选
     * @param selectedSku 选中SKU
     * @param matchedLayer 命中层级
     * @param success 是否成功
     * @param startTime 开产时间
     */
    private void traceTypeBlockDecision(LhScheduleContext context, MachineScheduleDTO machine,
                                        List<SkuScheduleDTO> priorityOneCandidates,
                                        List<SkuScheduleDTO> priorityTwoCandidates,
                                        SkuScheduleDTO selectedSku,
                                        String matchedLayer,
                                        boolean success,
                                        Date startTime,
                                        String triggerSource) {
        if (!PriorityTraceLogHelper.isEnabled(context)) {
            return;
        }
        String title = "收尾机台衔接决策";
        StringBuilder detailBuilder = new StringBuilder(512);
        PriorityTraceLogHelper.appendLine(detailBuilder,
                "机台=" + PriorityTraceLogHelper.safeText(machine.getMachineCode())
                        + ", 当前物料=" + PriorityTraceLogHelper.safeText(machine.getCurrentMaterialCode())
                        + ", 真实收尾时间=" + PriorityTraceLogHelper.formatDateTime(machine.getEstimatedEndTime()));
        PriorityTraceLogHelper.appendLine(detailBuilder,
                "第一层候选(同胎胚描述+同主花纹)=" + buildSkuCodeSummary(priorityOneCandidates));
        PriorityTraceLogHelper.appendLine(detailBuilder,
                "第二层候选(同规格)=" + buildSkuCodeSummary(priorityTwoCandidates));
        PriorityTraceLogHelper.appendLine(detailBuilder,
                "命中层级=" + PriorityTraceLogHelper.safeText(matchedLayer)
                        + ", 选中SKU=" + PriorityTraceLogHelper.safeText(
                        selectedSku == null ? null : selectedSku.getMaterialCode())
                        + ", 是否换活字块=" + PriorityTraceLogHelper.yesNo(selectedSku != null)
                        + ", 触发来源=" + PriorityTraceLogHelper.safeText(triggerSource));
        PriorityTraceLogHelper.appendLine(detailBuilder,
                "衔接结果=" + (success ? "成功" : "未衔接")
                        + ", 换活字块开始时间=" + PriorityTraceLogHelper.formatDateTime(
                        startTime == null ? null : resolveTypeBlockChangeStartTime(context, startTime))
                        + ", 开产时间=" + PriorityTraceLogHelper.formatDateTime(startTime));
        String detail = detailBuilder.toString().trim();
        log.info("{}\n{}", title, detail);
        PriorityTraceLogHelper.appendProcessLog(context, title, detail);
    }

    /**
     * 组装候选SKU编码摘要。
     *
     * @param skuList SKU列表
     * @return 摘要文本
     */
    private String buildSkuCodeSummary(List<SkuScheduleDTO> skuList) {
        if (CollectionUtils.isEmpty(skuList)) {
            return "-";
        }
        List<String> materialCodes = new ArrayList<>(skuList.size());
        for (SkuScheduleDTO sku : skuList) {
            materialCodes.add(PriorityTraceLogHelper.safeText(sku.getMaterialCode()));
        }
        return String.join(",", materialCodes);
    }

    /**
     * 识别可参与换活字块兜底反查的机台。
     *
     * @param context 排程上下文
     * @return 兜底机台列表
     */
    private List<MachineScheduleDTO> resolveTypeBlockFallbackMachines(LhScheduleContext context) {
        List<MachineScheduleDTO> fallbackMachineList = new ArrayList<>();
        if (context == null
                || CollectionUtils.isEmpty(context.getMachineScheduleMap())
                || CollectionUtils.isEmpty(context.getMachineOnlineInfoMap())) {
            return fallbackMachineList;
        }
        for (MachineScheduleDTO machine : context.getMachineScheduleMap().values()) {
            if (machine == null || machine.isEnding() || machine.getEstimatedEndTime() == null) {
                continue;
            }
            String machineCode = machine.getMachineCode();
            if (StringUtils.isEmpty(machineCode)
                    || !context.getMachineOnlineInfoMap().containsKey(machineCode)
                    || StringUtils.isEmpty(machine.getCurrentMaterialCode())) {
                continue;
            }
            if (isMachineAssignedContinuousResult(context, machineCode)) {
                continue;
            }
            if (!isTypeBlockFallbackEligibleByPreviousDay(context, machine)) {
                continue;
            }
            fallbackMachineList.add(machine);
        }
        return fallbackMachineList;
    }

    /**
     * 判定机台是否已命中续作分配。
     *
     * @param context 排程上下文
     * @param machineCode 机台编码
     * @return true-已命中续作分配
     */
    private boolean isMachineAssignedContinuousResult(LhScheduleContext context, String machineCode) {
        if (context == null
                || StringUtils.isEmpty(machineCode)
                || CollectionUtils.isEmpty(context.getMachineAssignmentMap())) {
            return false;
        }
        List<LhScheduleResult> assignedResults = context.getMachineAssignmentMap().get(machineCode);
        if (CollectionUtils.isEmpty(assignedResults)) {
            return false;
        }
        for (LhScheduleResult assignedResult : assignedResults) {
            if (assignedResult != null
                    && StringUtils.equals(CONTINUOUS_SCHEDULE_TYPE, assignedResult.getScheduleType())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判定兜底机台是否通过 T-1 收尾校验。
     * <p>规则：T-1 无该机台该SKU记录，或最新一条记录 isEnd=1。</p>
     *
     * @param context 排程上下文
     * @param machine 机台
     * @return true-通过校验
     */
    private boolean isTypeBlockFallbackEligibleByPreviousDay(LhScheduleContext context, MachineScheduleDTO machine) {
        if (context == null || machine == null) {
            return false;
        }
        String machineCode = machine.getMachineCode();
        String currentMaterialCode = machine.getCurrentMaterialCode();
        if (StringUtils.isEmpty(machineCode) || StringUtils.isEmpty(currentMaterialCode)) {
            return false;
        }
        LhScheduleResult latestPreviousResult = resolveLatestPreviousResult(context, machineCode, currentMaterialCode);
        if (latestPreviousResult == null || StringUtils.equals("1", latestPreviousResult.getIsEnd())) {
            return true;
        }
        traceTypeBlockFallbackSkip(context, machine, latestPreviousResult, TYPE_BLOCK_SKIP_REASON_T1_NOT_END);
        return false;
    }

    /**
     * 解析 T-1 同机台同SKU的最新一条排程结果。
     * <p>排序字段优先 specEndTime，缺失时回退 createTime。</p>
     *
     * @param context 排程上下文
     * @param machineCode 机台编码
     * @param materialCode 物料编码
     * @return 最新结果，未命中返回 null
     */
    private LhScheduleResult resolveLatestPreviousResult(LhScheduleContext context, String machineCode, String materialCode) {
        if (context == null
                || StringUtils.isEmpty(machineCode)
                || StringUtils.isEmpty(materialCode)
                || CollectionUtils.isEmpty(context.getPreviousScheduleResultList())) {
            return null;
        }
        LhScheduleResult latestResult = null;
        Date latestTime = null;
        for (LhScheduleResult previousResult : context.getPreviousScheduleResultList()) {
            if (previousResult == null
                    || !StringUtils.equals(machineCode, previousResult.getLhMachineCode())
                    || !StringUtils.equals(materialCode, previousResult.getMaterialCode())) {
                continue;
            }
            Date currentTime = resolvePreviousResultOrderTime(previousResult);
            if (latestResult == null) {
                latestResult = previousResult;
                latestTime = currentTime;
                continue;
            }
            if (latestTime == null || (currentTime != null && currentTime.after(latestTime))) {
                latestResult = previousResult;
                latestTime = currentTime;
            }
        }
        return latestResult;
    }

    /**
     * 解析 T-1 记录排序时间。
     *
     * @param previousResult T-1排程结果
     * @return 排序时间
     */
    private Date resolvePreviousResultOrderTime(LhScheduleResult previousResult) {
        if (previousResult == null) {
            return null;
        }
        if (previousResult.getSpecEndTime() != null) {
            return previousResult.getSpecEndTime();
        }
        return previousResult.getCreateTime();
    }

    /**
     * 输出兜底机台被跳过的决策日志。
     *
     * @param context 排程上下文
     * @param machine 机台
     * @param latestPreviousResult T-1最新结果
     * @param reason 跳过原因
     */
    private void traceTypeBlockFallbackSkip(LhScheduleContext context,
                                            MachineScheduleDTO machine,
                                            LhScheduleResult latestPreviousResult,
                                            String reason) {
        if (machine == null) {
            return;
        }
        log.info("换活字块兜底机台跳过, 机台: {}, 当前物料: {}, 原因: {}",
                machine.getMachineCode(), machine.getCurrentMaterialCode(), reason);
        if (!PriorityTraceLogHelper.isEnabled(context)) {
            return;
        }
        String title = "收尾机台衔接决策";
        StringBuilder detailBuilder = new StringBuilder(384);
        PriorityTraceLogHelper.appendLine(detailBuilder,
                "机台=" + PriorityTraceLogHelper.safeText(machine.getMachineCode())
                        + ", 当前物料=" + PriorityTraceLogHelper.safeText(machine.getCurrentMaterialCode())
                        + ", 触发来源=" + TYPE_BLOCK_TRIGGER_FALLBACK);
        PriorityTraceLogHelper.appendLine(detailBuilder,
                "衔接结果=未衔接, 原因=" + PriorityTraceLogHelper.safeText(reason)
                        + ", T-1最新isEnd=" + PriorityTraceLogHelper.safeText(
                        latestPreviousResult == null ? null : latestPreviousResult.getIsEnd())
                        + ", T-1最新排序时间=" + PriorityTraceLogHelper.formatDateTime(
                        resolvePreviousResultOrderTime(latestPreviousResult)));
        String detail = detailBuilder.toString().trim();
        PriorityTraceLogHelper.appendProcessLog(context, title, detail);
    }

    /**
     * 构建排程结果，分配各班次计划量
     */
    private LhScheduleResult buildScheduleResult(LhScheduleContext context,
                                                  MachineScheduleDTO machine,
                                                  SkuScheduleDTO sku,
                                                  Date startTime,
                                                  Date switchStartTime,
                                                  List<LhShiftConfigVO> shifts,
                                                  int mouldQty,
                                                  boolean isEnding) {
        LhScheduleResult result = new LhScheduleResult();
        result.setFactoryCode(context.getFactoryCode());
        result.setBatchNo(context.getBatchNo());
        result.setLhMachineCode(machine.getMachineCode());
        result.setLhMachineName(machine.getMachineName());
        result.setLeftRightMould(LeftRightMouldUtil.resolveLeftRightMould(result.getLeftRightMould(), machine.getMachineCode()));
        result.setMaterialCode(sku.getMaterialCode());
        result.setMaterialDesc(sku.getMaterialDesc());
        result.setSpecCode(sku.getSpecCode());
        result.setSpecDesc(sku.getSpecDesc());
        result.setEmbryoCode(sku.getEmbryoCode());
        // 落库口径：库存未知(-1)按0落库，但排程过程仍保留-1语义用于跳过库存裁剪。
        result.setEmbryoStock(Math.max(sku.getEmbryoStock(), 0));
        result.setMainMaterialDesc(sku.getMainMaterialDesc());
        result.setStructureName(sku.getStructureName());
        result.setScheduleDate(context.getScheduleTargetDate());
        result.setLhTime(sku.getLhTimeSeconds());
        result.setMouldQty(mouldQty);
        result.setSingleMouldShiftQty(SingleMouldShiftQtyUtil.resolveSingleMouldShiftQty(context, sku, mouldQty));
        result.setDailyPlanQty(0);
        result.setTotalDailyPlanQty(sku.getMonthPlanQty());
        result.setMouldSurplusQty(sku.getSurplusQty());
        result.setIsEnd(isEnding ? "1" : "0");
        result.setIsDelivery(sku.isDeliveryLocked() ? "1" : "0");
        result.setIsRelease("0");
        result.setDataSource("0");
        result.setIsDelete(0);
        result.setScheduleType(sku.getScheduleType() != null ? sku.getScheduleType() : "01");
        result.setIsTypeBlock("0");
        result.setConstructionStage(sku.getConstructionStage());
        result.setEmbryoNo(sku.getEmbryoNo());
        result.setTextNo(sku.getTextNo());
        result.setLhNo(sku.getLhNo());
        result.setMonthPlanVersion(sku.getMonthPlanVersion());
        result.setProductionVersion(sku.getProductionVersion());
        result.setIsTrial(sku.isTrial() ? "1" : "0");
        result.setMachineOrder(machine.getMachineOrder());

        // 生成工单号
        String orderNo = generateOrderNo(context);
        result.setOrderNo(orderNo);

        int refinedTargetQty = getTargetScheduleQtyResolver().refineTargetQtyByMachineCapacity(
                context, sku, machine, switchStartTime, startTime, shifts);
        List<MachineCleaningWindowDTO> cleaningWindowList = new ArrayList<>(MachineCleaningOverlapUtil.excludeOverlapWindows(
                machine.getCleaningWindowList(), switchStartTime, startTime));

        // 按班次分配计划量
        int remaining = refinedTargetQty;
        distributeToShifts(context, result, shifts, startTime,
                sku.getShiftCapacity(), sku.getLhTimeSeconds(), mouldQty, remaining, cleaningWindowList);

        refreshResultSummary(context, result, shifts);
        result.setRealScheduleDate(context.getScheduleDate());
        result.setProductionStatus("0");

        return result;
    }

    /**
     * 向各班次分配计划量（从startTime所在班次开始，按夜->早->中次序填满）
     *
     * @return 未能排产的剩余量
     */
    private int distributeToShifts(LhScheduleContext context,
                                   LhScheduleResult result,
                                   List<LhShiftConfigVO> shifts,
                                   Date startTime,
                                   int shiftCapacity,
                                   int lhTimeSeconds,
                                   int mouldQty,
                                   int remaining,
                                   List<MachineCleaningWindowDTO> cleaningWindowList) {
        if (lhTimeSeconds <= 0 || mouldQty <= 0 || remaining <= 0) {
            return remaining;
        }
        Map<Integer, ShiftRuntimeState> stateMap = context.getShiftRuntimeStateMap();
        int dryIceLossQty = context.getParamIntValue(
                LhScheduleParamConstant.DRY_ICE_LOSS_QTY, LhScheduleConstant.DRY_ICE_LOSS_QTY);
        int dryIceDurationHours = context.getParamIntValue(
                LhScheduleParamConstant.DRY_ICE_DURATION_HOURS, LhScheduleConstant.DRY_ICE_DURATION_HOURS);

        boolean started = false;
        for (LhShiftConfigVO shift : shifts) {
            if (remaining <= 0) {
                break;
            }
            if (!started) {
                if (startTime != null && !startTime.before(shift.getShiftEndDateTime()) && shift != shifts.get(shifts.size() - 1)) {
                    continue;
                }
                started = true;
            }

            Date effectiveStart = (startTime != null && startTime.after(shift.getShiftStartDateTime()))
                    ? startTime : shift.getShiftStartDateTime();
            if (effectiveStart.after(shift.getShiftEndDateTime())) {
                continue;
            }

            int shiftMaxQty = ShiftCapacityResolverUtil.resolveShiftCapacityWithDowntime(
                    context.getDevicePlanShutList(),
                    cleaningWindowList,
                    result.getLhMachineCode(),
                    effectiveStart,
                    shift.getShiftEndDateTime(),
                    shiftCapacity,
                    lhTimeSeconds,
                    mouldQty,
                    ShiftCapacityResolverUtil.resolveShiftDurationSeconds(shift),
                    dryIceLossQty,
                    dryIceDurationHours);
            if (shiftMaxQty <= 0) {
                continue;
            }
            int shiftQty = ShiftCapacityResolverUtil.normalizeAllocatedShiftQty(
                    Math.min(remaining, shiftMaxQty), shiftMaxQty, mouldQty);
            if (shiftQty <= 0) {
                continue;
            }

            Date shiftPlanEndTime = ShiftCapacityResolverUtil.resolveShiftPlanEndTime(
                    context.getDevicePlanShutList(),
                    cleaningWindowList,
                    result.getLhMachineCode(),
                    effectiveStart,
                    shift.getShiftEndDateTime(),
                    shiftQty,
                    shiftMaxQty);
            setShiftPlanQty(result, shift.getShiftIndex(), shiftQty, effectiveStart, shiftPlanEndTime);
            remaining -= shiftQty;
            startTime = null;

            if (!CollectionUtils.isEmpty(stateMap)) {
                ShiftRuntimeState st = stateMap.get(shift.getShiftIndex());
                if (st != null) {
                    st.setRemainingCapacity(Math.max(0, shiftMaxQty - shiftQty));
                }
            }
        }
        return remaining;
    }

    /**
     * 按班次索引设置计划量和开始/结束时间（Hutool BeanUtil）
     */
    private void setShiftPlanQty(LhScheduleResult result, int shiftIndex, int qty, Date startTime, Date endTime) {
        ShiftFieldUtil.setShiftPlanQty(result, shiftIndex, qty, startTime, endTime);
    }

    /**
     * 计算规格收尾时间（最后一个有计划量班次中，完成剩余量所需的时间点）
     */
    private Date calcSpecEndTime(LhScheduleContext context,
                                 LhScheduleResult result,
                                 List<LhShiftConfigVO> shifts,
                                 int lhTimeSeconds,
                                 int mouldQty,
                                 boolean isEnding) {
        if (!isEnding) {
            return null;
        }
        // 找到最后一个有计划量的班次，按真实产量推导完工时刻，避免被班次结束时刻放大。
        for (int i = shifts.size() - 1; i >= 0; i--) {
            LhShiftConfigVO shift = shifts.get(i);
            Integer shiftPlanQty = ShiftFieldUtil.getShiftPlanQty(result, shift.getShiftIndex());
            Date shiftStartTime = ShiftFieldUtil.getShiftStartTime(result, shift.getShiftIndex());
            Date shiftEndTime = ShiftFieldUtil.getShiftEndTime(result, shift.getShiftIndex());
            if (shiftEndTime == null) {
                shiftEndTime = shift.getShiftEndDateTime();
            }
            if (shiftPlanQty == null || shiftPlanQty <= 0 || shiftStartTime == null) {
                continue;
            }
            if (lhTimeSeconds <= 0 || mouldQty <= 0) {
                return shiftEndTime;
            }
            long secondsNeeded = (long) Math.ceil((double) shiftPlanQty / mouldQty) * lhTimeSeconds;
            List<MachineCleaningWindowDTO> cleaningWindowList = resolveEffectiveCleaningWindowList(
                    context, result, resolveFirstPlannedShiftStartTime(result));
            Date shiftCompletionTime = ShiftCapacityResolverUtil.resolveCompletionTimeWithDowntimes(
                    context.getDevicePlanShutList(),
                    cleaningWindowList,
                    result.getLhMachineCode(),
                    shiftStartTime,
                    secondsNeeded);
            if (shiftCompletionTime != null) {
                return constrainCompletionWithinShift(shiftCompletionTime, shiftEndTime);
            }
            return shiftEndTime;
        }
        return null;
    }

    private int calcTotalPlanQty(LhScheduleResult result, List<LhShiftConfigVO> shifts) {
        int total = 0;
        for (LhShiftConfigVO s : shifts) {
            Integer qty = ShiftFieldUtil.getShiftPlanQty(result, s.getShiftIndex());
            total += (qty != null ? qty : 0);
        }
        return total;
    }

    /**
     * 重新在班次间均衡分配计划量（用于allocateShiftPlanQty后续调整）
     */
    private void redistributeShiftQty(LhScheduleContext context, LhScheduleResult result, List<LhShiftConfigVO> shifts) {
        redistributeShiftQty(context, result, shifts, ShiftFieldUtil.resolveScheduledQty(result));
    }

    /**
     * 按指定目标量重新在班次间均衡分配计划量。
     *
     * @param result 排程结果
     * @param shifts 班次列表
     * @param targetQty 目标计划量
     */
    private void redistributeShiftQty(LhScheduleContext context, LhScheduleResult result, List<LhShiftConfigVO> shifts, int targetQty) {
        if (CollectionUtils.isEmpty(shifts)
                || result.getLhTime() == null
                || result.getLhTime() <= 0) {
            return;
        }

        if (targetQty <= 0) {
            clearShiftPlanQty(result, shifts);
            refreshResultSummary(context, result, shifts);
            return;
        }

        int mouldQty = ShiftCapacityResolverUtil.resolveMachineMouldQty(
                result.getMouldQty() != null ? result.getMouldQty() : 0);
        int shiftCapacity = result.getSingleMouldShiftQty() != null ? result.getSingleMouldShiftQty() : 0;
        int remaining = targetQty;
        Date cursorStartTime = resolveRedistributeStartTime(result, shifts);
        List<MachineCleaningWindowDTO> cleaningWindowList = resolveEffectiveCleaningWindowList(
                context, result, resolveFirstPlannedShiftStartTime(result));
        int dryIceLossQty = context.getParamIntValue(
                LhScheduleParamConstant.DRY_ICE_LOSS_QTY, LhScheduleConstant.DRY_ICE_LOSS_QTY);
        int dryIceDurationHours = context.getParamIntValue(
                LhScheduleParamConstant.DRY_ICE_DURATION_HOURS, LhScheduleConstant.DRY_ICE_DURATION_HOURS);

        for (LhShiftConfigVO shift : shifts) {
            if (remaining <= 0) {
                setShiftPlanQty(result, shift.getShiftIndex(), 0, null, null);
                continue;
            }
            if (cursorStartTime != null
                    && !cursorStartTime.before(shift.getShiftEndDateTime())
                    && shift != shifts.get(shifts.size() - 1)) {
                setShiftPlanQty(result, shift.getShiftIndex(), 0, null, null);
                continue;
            }
            Date shiftStartTime = shift.getShiftStartDateTime();
            Date effectiveStartTime = cursorStartTime != null && cursorStartTime.after(shiftStartTime)
                    ? cursorStartTime : shiftStartTime;
            int shiftMaxQty = ShiftCapacityResolverUtil.resolveShiftCapacityWithDowntime(
                    context.getDevicePlanShutList(),
                    cleaningWindowList,
                    result.getLhMachineCode(),
                    effectiveStartTime,
                    shift.getShiftEndDateTime(),
                    shiftCapacity,
                    result.getLhTime(),
                    mouldQty,
                    ShiftCapacityResolverUtil.resolveShiftDurationSeconds(shift),
                    dryIceLossQty,
                    dryIceDurationHours);
            if (shiftMaxQty <= 0) {
                setShiftPlanQty(result, shift.getShiftIndex(), 0, null, null);
                continue;
            }
            int shiftQty = ShiftCapacityResolverUtil.normalizeAllocatedShiftQty(
                    Math.min(remaining, shiftMaxQty), shiftMaxQty, mouldQty);
            if (shiftQty <= 0) {
                setShiftPlanQty(result, shift.getShiftIndex(), 0, null, null);
                continue;
            }
            Date shiftPlanEndTime = ShiftCapacityResolverUtil.resolveShiftPlanEndTime(
                    context.getDevicePlanShutList(),
                    cleaningWindowList,
                    result.getLhMachineCode(),
                    effectiveStartTime,
                    shift.getShiftEndDateTime(),
                    shiftQty,
                    shiftMaxQty);
            setShiftPlanQty(result, shift.getShiftIndex(), shiftQty, effectiveStartTime, shiftPlanEndTime);
            remaining -= shiftQty;
            cursorStartTime = shift.getShiftEndDateTime();
        }
        refreshResultSummary(context, result, shifts);
    }

    /**
     * 获取结果当前的首个开产时间，供续作班次重分配时保留残班起点。
     *
     * @param result 排程结果
     * @param shifts 班次列表
     * @return 首个有效开产时间
     */
    private Date resolveRedistributeStartTime(LhScheduleResult result, List<LhShiftConfigVO> shifts) {
        for (LhShiftConfigVO shift : shifts) {
            Date shiftStartTime = ShiftFieldUtil.getShiftStartTime(result, shift.getShiftIndex());
            if (shiftStartTime != null) {
                return shiftStartTime;
            }
        }
        return shifts.get(0).getShiftStartDateTime();
    }

    /**
     * 构建胎胚库存Map（基于context中现有的skuLhCapacityMap，用materialCode的embryoCode分组统计）
     */
    private Map<String, Integer> buildEmbryoStockMap(LhScheduleContext context) {
        Map<String, Integer> stockMap = new HashMap<>();
        for (SkuScheduleDTO sku : context.getContinuousSkuList()) {
            if (sku.getEmbryoCode() != null && sku.getEmbryoStock() >= 0) {
                stockMap.put(sku.getEmbryoCode(), sku.getEmbryoStock());
            }
        }
        for (SkuScheduleDTO sku : context.getNewSpecSkuList()) {
            if (sku.getEmbryoCode() != null
                    && sku.getEmbryoStock() >= 0
                    && !stockMap.containsKey(sku.getEmbryoCode())) {
                stockMap.put(sku.getEmbryoCode(), sku.getEmbryoStock());
            }
        }
        return stockMap;
    }

    /**
     * 根据胎胚库存调整排程结果的计划量
     */
    private void adjustResultByEmbryoStock(LhScheduleContext context,
                                           LhScheduleResult result,
                                           Map<String, Integer> embryoStockMap,
                                           List<LhShiftConfigVO> shifts) {
        String embryoCode = result.getEmbryoCode();
        if (embryoCode == null) {
            return;
        }
        Integer stock = embryoStockMap.get(embryoCode);
        if (stock == null || stock < 0) {
            return;
        }
        int totalPlan = ShiftFieldUtil.resolveScheduledQty(result);
        if (totalPlan <= stock) {
            embryoStockMap.put(embryoCode, stock - totalPlan);
        } else {
            // 库存不足，削减计划量
            redistributeShiftQty(context, result, shifts, stock);
            embryoStockMap.put(embryoCode, 0);
        }
    }

    /**
     * 基于最终计划量复核续作结果收尾标记。
     * <p>口径：当日计划量 >= max(硫化余量, 胎胚库存)时记为收尾，否则记为正常。</p>
     *
     * @param context 排程上下文
     */
    private void refreshContinuousEndingFlagByResult(LhScheduleContext context) {
        if (context == null || CollectionUtils.isEmpty(context.getScheduleResultList())) {
            return;
        }
        for (LhScheduleResult result : context.getScheduleResultList()) {
            refreshContinuousEndingFlagByResult(result);
        }
    }

    /**
     * 基于结果行“最终计划量 vs max(硫化余量, 胎胚库存)”复核续作收尾标记。
     *
     * @param result 排程结果
     */
    private void refreshContinuousEndingFlagByResult(LhScheduleResult result) {
        if (!isContinuousPhaseResult(result)) {
            return;
        }
        int finalPlanQty = result.getDailyPlanQty() != null ? result.getDailyPlanQty() : 0;
        int endingDemandQty = resolveEndingDemandQty(result);
        result.setIsEnd(finalPlanQty >= endingDemandQty && endingDemandQty > 0 ? "1" : "0");
    }

    /**
     * 计算结果行收尾比较量。
     *
     * @param result 排程结果
     * @return max(硫化余量, 胎胚库存)
     */
    private int resolveEndingDemandQty(LhScheduleResult result) {
        int surplusQty = result.getMouldSurplusQty() != null ? result.getMouldSurplusQty() : 0;
        int embryoStock = result.getEmbryoStock() != null ? result.getEmbryoStock() : 0;
        return Math.max(Math.max(surplusQty, 0), Math.max(embryoStock, 0));
    }

    /**
     * 清空结果行的班次计划量。
     *
     * @param result 排程结果
     * @param shifts 班次列表
     */
    private void clearShiftPlanQty(LhScheduleResult result, List<LhShiftConfigVO> shifts) {
        for (LhShiftConfigVO shift : shifts) {
            setShiftPlanQty(result, shift.getShiftIndex(), 0, null, null);
        }
    }

    /**
     * 刷新结果行的汇总计划量和收尾时间。
     *
     * @param result 排程结果
     * @param shifts 班次列表
     */
    private void refreshResultSummary(LhScheduleContext context, LhScheduleResult result, List<LhShiftConfigVO> shifts) {
        if (result == null) {
            return;
        }
        ShiftFieldUtil.syncDailyPlanQty(result);
        if (result.getDailyPlanQty() == null || result.getDailyPlanQty() <= 0) {
            // 零计划结果不参与完工时刻语义。
            result.setSpecEndTime(null);
            result.setTdaySpecEndTime(null);
            return;
        }
        int lhTimeSeconds = result.getLhTime() != null ? result.getLhTime() : 0;
        int mouldQty = ShiftCapacityResolverUtil.resolveMachineMouldQty(
                result.getMouldQty() != null ? result.getMouldQty() : 0);
        Date specEndTime = calcSpecEndTime(context, result, shifts, lhTimeSeconds, mouldQty, "1".equals(result.getIsEnd()));
        if (specEndTime == null) {
            // 非收尾结果也要保留可推导完工时刻，避免后续校验出现 specEndTime 缺失。
            specEndTime = resolveActualCompletionTime(context, result);
        }
        result.setSpecEndTime(specEndTime);
        result.setTdaySpecEndTime(specEndTime);
    }

    /**
     * 统一处理续作阶段零计划结果：
     * 1) 清空完工时刻并从排程结果列表移除；
     * 2) 按物料去重写入/合并未排结果。
     *
     * @param context 排程上下文
     */
    private void finalizeZeroPlanContinuousResults(LhScheduleContext context) {
        if (context == null || CollectionUtils.isEmpty(context.getScheduleResultList())) {
            return;
        }
        Map<String, Integer> zeroPlanQtyMap = new LinkedHashMap<>(8);
        List<LhScheduleResult> zeroPlanResults = new ArrayList<>(8);
        for (LhScheduleResult result : context.getScheduleResultList()) {
            if (!isContinuousPhaseResult(result)) {
                continue;
            }
            if (result.getDailyPlanQty() != null && result.getDailyPlanQty() > 0) {
                continue;
            }
            result.setSpecEndTime(null);
            result.setTdaySpecEndTime(null);
            zeroPlanResults.add(result);
            if (StringUtils.isEmpty(result.getMaterialCode())) {
                continue;
            }
            int unscheduledQty = resolveRemainingUnscheduledQty(context, result.getMaterialCode());
            if (unscheduledQty > 0) {
                zeroPlanQtyMap.putIfAbsent(result.getMaterialCode(), unscheduledQty);
            }
        }
        for (Map.Entry<String, Integer> entry : zeroPlanQtyMap.entrySet()) {
            mergeUnscheduledResultByMaterial(context, entry.getKey(), entry.getValue());
        }
        if (!CollectionUtils.isEmpty(zeroPlanResults)) {
            context.getScheduleResultList().removeAll(zeroPlanResults);
            removeResultsFromMachineAssignments(context, zeroPlanResults);
        }
        normalizeUnscheduledResultsByMaterial(context);
    }

    /**
     * S4.4 结束后按最终有效续作结果二次回写机台状态。
     *
     * @param context 排程上下文
     */
    private void syncMachineStateAfterContinuousAdjust(LhScheduleContext context) {
        if (context == null || CollectionUtils.isEmpty(context.getMachineScheduleMap())) {
            return;
        }
        Map<String, List<LhScheduleResult>> machineResultMap = context.getScheduleResultList().stream()
                .filter(this::isEffectiveContinuousResult)
                .collect(Collectors.groupingBy(LhScheduleResult::getLhMachineCode));
        for (Map.Entry<String, MachineScheduleDTO> entry : context.getMachineScheduleMap().entrySet()) {
            String machineCode = entry.getKey();
            MachineScheduleDTO machine = entry.getValue();
            List<LhScheduleResult> machineResults = machineResultMap.get(machineCode);
            if (!CollectionUtils.isEmpty(machineResults)) {
                LhScheduleResult latestResult = machineResults.stream()
                        .max(Comparator.comparing(LhScheduleResult::getSpecEndTime))
                        .orElse(null);
                if (latestResult != null) {
                    LhScheduleResult previousResult = resolvePreviousMachineResult(machineResults, latestResult);
                    applyMachineStateFromResult(context, machine, latestResult, previousResult);
                    continue;
                }
            }
            restoreMachineStateFromInitial(context, machineCode, machine);
        }
    }

    /**
     * 在最终保留结果集中推导上一条有效结果。
     *
     * @param machineResults 机台有效结果列表
     * @param latestResult 最新结果
     * @return 上一条有效结果
     */
    private LhScheduleResult resolvePreviousMachineResult(List<LhScheduleResult> machineResults, LhScheduleResult latestResult) {
        if (CollectionUtils.isEmpty(machineResults) || latestResult == null) {
            return null;
        }
        return machineResults.stream()
                .filter(result -> result != null
                        && result != latestResult
                        && result.getSpecEndTime() != null)
                .max(Comparator.comparing(LhScheduleResult::getSpecEndTime))
                .orElse(null);
    }

    /**
     * 判断结果是否属于可驱动机台终态的有效结果。
     * <p>除续作结果外，S4.4 产生的换活字块结果也需要参与机台终态回写，
     * 否则会在 S4.5 选机时丢失真实收尾时间。</p>
     *
     * @param result 排程结果
     * @return true-有效结果；false-非有效结果
     */
    private boolean isEffectiveContinuousResult(LhScheduleResult result) {
        return isContinuousPhaseResult(result)
                && result.getDailyPlanQty() != null
                && result.getDailyPlanQty() > 0
                && result.getSpecEndTime() != null
                && StringUtils.isNotEmpty(result.getLhMachineCode());
    }

    /**
     * 判断结果是否属于续作阶段结果（含换活字块）。
     *
     * @param result 排程结果
     * @return true-续作阶段结果；false-非续作阶段结果
     */
    private boolean isContinuousPhaseResult(LhScheduleResult result) {
        if (result == null) {
            return false;
        }
        return CONTINUOUS_SCHEDULE_TYPE.equals(result.getScheduleType())
                || "1".equals(result.getIsTypeBlock());
    }

    /**
     * 根据最终有效结果回写机台状态。
     *
     * @param context 排程上下文
     * @param machine 机台
     * @param result 最终有效续作结果
     */
    private void applyMachineStateFromResult(LhScheduleContext context,
                                             MachineScheduleDTO machine,
                                             LhScheduleResult result,
                                             LhScheduleResult previousResult) {
        String previousMaterialCode = null;
        String previousMaterialDesc = null;
        if (previousResult != null) {
            previousMaterialCode = previousResult.getMaterialCode();
            previousMaterialDesc = previousResult.getMaterialDesc();
        } else if (machine != null && StringUtils.isNotEmpty(machine.getMachineCode())) {
            MachineScheduleDTO initialMachine = context.getInitialMachineScheduleMap().get(machine.getMachineCode());
            if (initialMachine != null) {
                previousMaterialCode = initialMachine.getCurrentMaterialCode();
                previousMaterialDesc = initialMachine.getCurrentMaterialDesc();
            }
        }
        machine.setCurrentMaterialCode(result.getMaterialCode());
        machine.setCurrentMaterialDesc(result.getMaterialDesc());
        machine.setPreviousMaterialCode(previousMaterialCode);
        machine.setPreviousMaterialDesc(previousMaterialDesc);
        machine.setPreviousSpecCode(result.getSpecCode());
        machine.setPreviousProSize(resolveMaterialProSize(context, result.getMaterialCode()));
        machine.setEstimatedEndTime(result.getSpecEndTime());
        machine.setEnding("1".equals(result.getIsEnd()) && result.getSpecEndTime() != null);
    }

    /**
     * 回退机台状态到初始化快照，避免沿用失效衔接状态。
     *
     * @param context 排程上下文
     * @param machineCode 机台编码
     * @param machine 当前机台对象
     */
    private void restoreMachineStateFromInitial(LhScheduleContext context, String machineCode, MachineScheduleDTO machine) {
        if (context == null || machine == null || StringUtils.isEmpty(machineCode)) {
            return;
        }
        MachineScheduleDTO initialMachine = context.getInitialMachineScheduleMap().get(machineCode);
        if (initialMachine == null) {
            return;
        }
        machine.setCurrentMaterialCode(initialMachine.getCurrentMaterialCode());
        machine.setCurrentMaterialDesc(initialMachine.getCurrentMaterialDesc());
        machine.setPreviousMaterialCode(initialMachine.getPreviousMaterialCode());
        machine.setPreviousMaterialDesc(initialMachine.getPreviousMaterialDesc());
        machine.setPreviousSpecCode(initialMachine.getPreviousSpecCode());
        machine.setPreviousProSize(initialMachine.getPreviousProSize());
        machine.setEstimatedEndTime(initialMachine.getEstimatedEndTime());
        machine.setEnding(initialMachine.isEnding());
    }

    /**
     * 解析物料规格英寸，用于机台前规格回写。
     *
     * @param context 排程上下文
     * @param materialCode 物料编码
     * @return 规格英寸
     */
    private String resolveMaterialProSize(LhScheduleContext context, String materialCode) {
        if (context == null || StringUtils.isEmpty(materialCode)) {
            return null;
        }
        MdmMaterialInfo materialInfo = context.getMaterialInfoMap().get(materialCode);
        if (materialInfo != null && StringUtils.isNotEmpty(materialInfo.getProSize())) {
            return materialInfo.getProSize();
        }
        SkuScheduleDTO sku = findSkuDto(context, materialCode);
        return sku != null ? sku.getProSize() : null;
    }

    /**
     * 计算物料剩余待排数量（续作零计划未排口径）。
     *
     * @param context 排程上下文
     * @param materialCode 物料编码
     * @return 剩余待排数量
     */
    private int resolveRemainingUnscheduledQty(LhScheduleContext context, String materialCode) {
        SkuScheduleDTO sku = findSkuDto(context, materialCode);
        if (sku == null) {
            return 0;
        }
        int targetScheduleQty = sku.resolveTargetScheduleQty();
        int retainedQty = resolveEffectiveContinuousPhaseScheduledQty(context, materialCode);
        return Math.max(targetScheduleQty - retainedQty, 0);
    }

    /**
     * 统计同物料在续作阶段最终保留的有效计划量（含换活字块）。
     *
     * @param context 排程上下文
     * @param materialCode 物料编码
     * @return 有效计划量
     */
    private int resolveEffectiveContinuousPhaseScheduledQty(LhScheduleContext context, String materialCode) {
        if (context == null || StringUtils.isEmpty(materialCode) || CollectionUtils.isEmpty(context.getScheduleResultList())) {
            return 0;
        }
        int totalQty = 0;
        for (LhScheduleResult result : context.getScheduleResultList()) {
            if (result == null
                    || !StringUtils.equals(materialCode, result.getMaterialCode())
                    || !isContinuousPhaseResult(result)
                    || result.getDailyPlanQty() == null
                    || result.getDailyPlanQty() <= 0) {
                continue;
            }
            totalQty += result.getDailyPlanQty();
        }
        return totalQty;
    }

    /**
     * 统计同物料最终仍保留在结果列表中的有效计划量。
     *
     * @param context 排程上下文
     * @param materialCode 物料编码
     * @param scheduleType 排产类型
     * @return 有效计划量
     */
    private int resolveEffectiveScheduledQty(LhScheduleContext context, String materialCode, String scheduleType) {
        if (context == null || StringUtils.isEmpty(materialCode) || CollectionUtils.isEmpty(context.getScheduleResultList())) {
            return 0;
        }
        int totalQty = 0;
        for (LhScheduleResult result : context.getScheduleResultList()) {
            if (result == null
                    || !StringUtils.equals(materialCode, result.getMaterialCode())
                    || !StringUtils.equals(scheduleType, result.getScheduleType())
                    || result.getDailyPlanQty() == null
                    || result.getDailyPlanQty() <= 0) {
                continue;
            }
            totalQty += result.getDailyPlanQty();
        }
        return totalQty;
    }

    /**
     * 按物料维度写入/合并未排结果，保证同物料仅一条记录。
     *
     * @param context 排程上下文
     * @param materialCode 物料编码
     * @param unscheduledQty 未排数量
     */
    private void mergeUnscheduledResultByMaterial(LhScheduleContext context, String materialCode, int unscheduledQty) {
        if (context == null || StringUtils.isEmpty(materialCode)) {
            return;
        }
        LhUnscheduledResult existing = findUnscheduledResultByMaterial(context, materialCode);
        if (existing != null) {
            int existingQty = existing.getUnscheduledQty() != null ? existing.getUnscheduledQty() : 0;
            existing.setUnscheduledQty(existingQty + Math.max(unscheduledQty, 0));
            if (StringUtils.isEmpty(existing.getUnscheduledReason())) {
                existing.setUnscheduledReason(ZERO_PLAN_UNSCHEDULED_REASON);
            }
            return;
        }
        SkuScheduleDTO sku = findSkuDto(context, materialCode);
        LhUnscheduledResult unscheduled = new LhUnscheduledResult();
        unscheduled.setFactoryCode(context.getFactoryCode());
        unscheduled.setBatchNo(context.getBatchNo());
        unscheduled.setScheduleDate(context.getScheduleTargetDate());
        unscheduled.setMaterialCode(materialCode);
        unscheduled.setUnscheduledQty(Math.max(unscheduledQty, 0));
        unscheduled.setUnscheduledReason(ZERO_PLAN_UNSCHEDULED_REASON);
        unscheduled.setDataSource(AUTO_DATA_SOURCE);
        unscheduled.setIsDelete(0);
        if (sku != null) {
            unscheduled.setMaterialDesc(sku.getMaterialDesc());
            unscheduled.setStructureName(sku.getStructureName());
            unscheduled.setMainMaterialDesc(sku.getMainMaterialDesc());
            unscheduled.setSpecCode(sku.getSpecCode());
            unscheduled.setEmbryoCode(sku.getEmbryoCode());
            unscheduled.setMouldQty(sku.getMouldQty());
        }
        context.getUnscheduledResultList().add(unscheduled);
    }

    /**
     * 根据物料编码查找已存在未排结果。
     *
     * @param context 排程上下文
     * @param materialCode 物料编码
     * @return 未排结果
     */
    private LhUnscheduledResult findUnscheduledResultByMaterial(LhScheduleContext context, String materialCode) {
        if (context == null || CollectionUtils.isEmpty(context.getUnscheduledResultList())) {
            return null;
        }
        for (LhUnscheduledResult unscheduledResult : context.getUnscheduledResultList()) {
            if (StringUtils.equals(materialCode, unscheduledResult.getMaterialCode())) {
                return unscheduledResult;
            }
        }
        return null;
    }

    /**
     * 按物料编码归并未排结果，确保同物料只保留一条记录。
     *
     * @param context 排程上下文
     */
    private void normalizeUnscheduledResultsByMaterial(LhScheduleContext context) {
        if (context == null || CollectionUtils.isEmpty(context.getUnscheduledResultList())) {
            return;
        }
        Map<String, LhUnscheduledResult> mergedMap = new LinkedHashMap<>(context.getUnscheduledResultList().size());
        for (LhUnscheduledResult unscheduledResult : context.getUnscheduledResultList()) {
            if (unscheduledResult == null || StringUtils.isEmpty(unscheduledResult.getMaterialCode())) {
                continue;
            }
            String materialCode = unscheduledResult.getMaterialCode();
            if (!mergedMap.containsKey(materialCode)) {
                mergedMap.put(materialCode, unscheduledResult);
                continue;
            }
            LhUnscheduledResult existing = mergedMap.get(materialCode);
            int existingQty = existing.getUnscheduledQty() != null ? existing.getUnscheduledQty() : 0;
            int currentQty = unscheduledResult.getUnscheduledQty() != null ? unscheduledResult.getUnscheduledQty() : 0;
            existing.setUnscheduledQty(existingQty + currentQty);
            if (StringUtils.isEmpty(existing.getUnscheduledReason())) {
                existing.setUnscheduledReason(unscheduledResult.getUnscheduledReason());
            }
        }
        context.getUnscheduledResultList().clear();
        context.getUnscheduledResultList().addAll(mergedMap.values());
    }

    /**
     * 从机台已分配结果中移除零计划续作结果，避免占用后续选机上下文。
     *
     * @param context 排程上下文
     * @param resultsToRemove 待移除结果列表
     */
    private void removeResultsFromMachineAssignments(LhScheduleContext context, List<LhScheduleResult> resultsToRemove) {
        if (context == null
                || CollectionUtils.isEmpty(resultsToRemove)
                || CollectionUtils.isEmpty(context.getMachineAssignmentMap())) {
            return;
        }
        Iterator<Map.Entry<String, List<LhScheduleResult>>> iterator =
                context.getMachineAssignmentMap().entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, List<LhScheduleResult>> entry = iterator.next();
            List<LhScheduleResult> assignedResults = entry.getValue();
            if (CollectionUtils.isEmpty(assignedResults)) {
                iterator.remove();
                continue;
            }
            assignedResults.removeAll(resultsToRemove);
            if (assignedResults.isEmpty()) {
                iterator.remove();
            }
        }
    }

    private void updateMachineState(LhScheduleContext context, MachineScheduleDTO machine, SkuScheduleDTO sku, LhScheduleResult result) {
        machine.setPreviousMaterialCode(machine.getCurrentMaterialCode());
        machine.setPreviousMaterialDesc(machine.getCurrentMaterialDesc());
        machine.setCurrentMaterialCode(sku.getMaterialCode());
        machine.setCurrentMaterialDesc(sku.getMaterialDesc());
        machine.setPreviousSpecCode(sku.getSpecCode());
        machine.setPreviousProSize(sku.getProSize());
        // 机台预计结束时间严格回写为实际完工时间，避免被整班结束时间放大。
        machine.setEstimatedEndTime(resolveActualCompletionTime(context, result));
        machine.setEnding("1".equals(result.getIsEnd()) && result.getSpecEndTime() != null);
    }

    private Date resolveActualCompletionTime(LhScheduleContext context, LhScheduleResult result) {
        if (result == null) {
            return null;
        }
        int lhTimeSeconds = result.getLhTime() != null ? result.getLhTime() : 0;
        int mouldQty = ShiftCapacityResolverUtil.resolveMachineMouldQty(
                result.getMouldQty() != null ? result.getMouldQty() : 0);
        if (lhTimeSeconds > 0 && mouldQty > 0) {
            Date actualCompletionTime = null;
            List<MachineCleaningWindowDTO> cleaningWindowList = resolveEffectiveCleaningWindowList(
                    context, result, resolveFirstPlannedShiftStartTime(result));
            for (int shiftIndex = 1; shiftIndex <= 8; shiftIndex++) {
                Integer shiftPlanQty = ShiftFieldUtil.getShiftPlanQty(result, shiftIndex);
                Date shiftStartTime = ShiftFieldUtil.getShiftStartTime(result, shiftIndex);
                if (shiftPlanQty == null || shiftPlanQty <= 0 || shiftStartTime == null) {
                    continue;
                }
                Date shiftEndTime = ShiftFieldUtil.getShiftEndTime(result, shiftIndex);
                long secondsNeeded = (long) Math.ceil((double) shiftPlanQty / mouldQty) * lhTimeSeconds;
                Date shiftCompletionTime = ShiftCapacityResolverUtil.resolveCompletionTimeWithDowntimes(
                        context.getDevicePlanShutList(),
                        cleaningWindowList,
                        result.getLhMachineCode(),
                        shiftStartTime,
                        secondsNeeded);
                if (shiftCompletionTime == null) {
                    shiftCompletionTime = shiftEndTime;
                } else {
                    shiftCompletionTime = constrainCompletionWithinShift(shiftCompletionTime, shiftEndTime);
                }
                if (actualCompletionTime == null || shiftCompletionTime.after(actualCompletionTime)) {
                    actualCompletionTime = shiftCompletionTime;
                }
            }
            if (actualCompletionTime != null) {
                return actualCompletionTime;
            }
        }
        return result.getSpecEndTime();
    }

    /**
     * 命中“模具清洗+换活字块”组合场景时，写入班次原因分析。
     *
     * @param context 排程上下文
     * @param result 排程结果
     * @param shifts 班次列表
     */
    private void applyTypeBlockCleaningAnalysis(LhScheduleContext context,
                                                LhScheduleResult result,
                                                List<LhShiftConfigVO> shifts) {
        if (context == null || result == null || CollectionUtils.isEmpty(shifts)) {
            return;
        }
        Date switchStartTime = result.getMouldChangeStartTime();
        Date productionStartTime = resolveFirstPlannedShiftStartTime(result);
        if (switchStartTime == null || productionStartTime == null) {
            return;
        }
        int firstPlannedShiftIndex = resolveFirstPlannedShiftIndex(result);
        if (firstPlannedShiftIndex <= 0) {
            return;
        }
        List<MachineCleaningWindowDTO> cleaningWindowList = resolveMachineCleaningWindowList(context, result.getLhMachineCode());
        if (!MachineCleaningOverlapUtil.hasBlockingOverlap(cleaningWindowList, switchStartTime, productionStartTime)) {
            return;
        }
        ShiftFieldUtil.setShiftAnalysis(result, firstPlannedShiftIndex, TYPE_BLOCK_CLEANING_ANALYSIS);
    }

    /**
     * 获取首个有排产量的班次索引。
     *
     * @param result 排程结果
     * @return 班次索引；未找到返回 -1
     */
    private int resolveFirstPlannedShiftIndex(LhScheduleResult result) {
        if (result == null) {
            return -1;
        }
        for (int shiftIndex = 1; shiftIndex <= LhScheduleConstant.MAX_SHIFT_SLOT_COUNT; shiftIndex++) {
            Integer shiftPlanQty = ShiftFieldUtil.getShiftPlanQty(result, shiftIndex);
            if (shiftPlanQty != null && shiftPlanQty > 0) {
                return shiftIndex;
            }
        }
        return -1;
    }

    /**
     * 获取首个有排产量班次的开始时间。
     *
     * @param result 排程结果
     * @return 班次开始时间
     */
    private Date resolveFirstPlannedShiftStartTime(LhScheduleResult result) {
        int firstPlannedShiftIndex = resolveFirstPlannedShiftIndex(result);
        return firstPlannedShiftIndex > 0
                ? ShiftFieldUtil.getShiftStartTime(result, firstPlannedShiftIndex) : null;
    }

    /**
     * 约束班次真实完工时刻不晚于该班次结束时刻，避免跨班时刻反向污染收尾判断。
     *
     * @param completionTime 计算出的完工时刻
     * @param shiftEndTime 班次结束时刻
     * @return 约束后的完工时刻
     */
    private Date constrainCompletionWithinShift(Date completionTime, Date shiftEndTime) {
        if (completionTime == null) {
            return shiftEndTime;
        }
        if (shiftEndTime == null) {
            return completionTime;
        }
        return completionTime.after(shiftEndTime) ? shiftEndTime : completionTime;
    }

    private List<MachineCleaningWindowDTO> resolveMachineCleaningWindowList(LhScheduleContext context, String machineCode) {
        MachineScheduleDTO machine = context.getMachineScheduleMap().get(machineCode);
        if (machine == null || CollectionUtils.isEmpty(machine.getCleaningWindowList())) {
            return new ArrayList<>();
        }
        return machine.getCleaningWindowList();
    }

    /**
     * 解析续作/换活字块结果在排产阶段需要生效的清洗窗口。
     *
     * @param context 排程上下文
     * @param result 排程结果
     * @param firstProductionStartTime 首个有排产量班次开始时间
     * @return 有效清洗窗口列表
     */
    private List<MachineCleaningWindowDTO> resolveEffectiveCleaningWindowList(LhScheduleContext context,
                                                                              LhScheduleResult result,
                                                                              Date firstProductionStartTime) {
        if (result == null) {
            return new ArrayList<>(0);
        }
        List<MachineCleaningWindowDTO> cleaningWindowList = resolveMachineCleaningWindowList(
                context, result.getLhMachineCode());
        return new ArrayList<>(MachineCleaningOverlapUtil.excludeOverlapWindows(
                cleaningWindowList, result.getMouldChangeStartTime(), firstProductionStartTime));
    }

    private String resolveMachineEmbryoCode(LhScheduleContext context, MachineScheduleDTO machine) {
        MdmMaterialInfo materialInfo = resolveMachineMaterialInfo(context, machine);
        if (materialInfo != null && StringUtils.isNotEmpty(materialInfo.getEmbryoCode())) {
            return materialInfo.getEmbryoCode();
        }
        SkuScheduleDTO currentSku = findSkuByMaterialCode(context.getContinuousSkuList(), machine.getCurrentMaterialCode());
        return currentSku != null ? currentSku.getEmbryoCode() : null;
    }

    private String resolveMachineEmbryoDesc(LhScheduleContext context, MachineScheduleDTO machine) {
        MdmMaterialInfo materialInfo = resolveMachineMaterialInfo(context, machine);
        if (materialInfo != null && StringUtils.isNotEmpty(materialInfo.getEmbryoDesc())) {
            return normalizeCompareToken(materialInfo.getEmbryoDesc());
        }
        SkuScheduleDTO currentSku = findSkuByMaterialCode(context.getContinuousSkuList(), machine.getCurrentMaterialCode());
        return currentSku != null ? normalizeCompareToken(currentSku.getMainMaterialDesc()) : null;
    }

    private String resolveSkuEmbryoDesc(LhScheduleContext context, SkuScheduleDTO sku) {
        if (context == null || sku == null) {
            return null;
        }
        MdmMaterialInfo materialInfo = context.getMaterialInfoMap().get(sku.getMaterialCode());
        if (materialInfo != null && StringUtils.isNotEmpty(materialInfo.getEmbryoDesc())) {
            return normalizeCompareToken(materialInfo.getEmbryoDesc());
        }
        return normalizeCompareToken(sku.getMainMaterialDesc());
    }

    private String resolveMachineSpecCode(LhScheduleContext context, MachineScheduleDTO machine) {
        if (StringUtils.isNotEmpty(machine.getPreviousSpecCode())) {
            return machine.getPreviousSpecCode();
        }
        MdmMaterialInfo materialInfo = resolveMachineMaterialInfo(context, machine);
        if (materialInfo != null && StringUtils.isNotEmpty(materialInfo.getSpecifications())) {
            return materialInfo.getSpecifications();
        }
        SkuScheduleDTO currentSku = findSkuByMaterialCode(context.getContinuousSkuList(), machine.getCurrentMaterialCode());
        return currentSku != null ? currentSku.getSpecCode() : null;
    }

    private String resolveMachinePatternKey(LhScheduleContext context, MachineScheduleDTO machine) {
        MdmMaterialInfo materialInfo = resolveMachineMaterialInfo(context, machine);
        if (materialInfo != null) {
            return resolvePatternKey(materialInfo.getMainPattern(), materialInfo.getPattern());
        }
        SkuScheduleDTO currentSku = findSkuByMaterialCode(context.getContinuousSkuList(), machine.getCurrentMaterialCode());
        if (currentSku == null) {
            return null;
        }
        return resolvePatternKey(currentSku.getMainPattern(), currentSku.getPattern());
    }

    private String resolveMachineMainPatternStrict(LhScheduleContext context, MachineScheduleDTO machine) {
        MdmMaterialInfo materialInfo = resolveMachineMaterialInfo(context, machine);
        if (materialInfo != null && StringUtils.isNotEmpty(materialInfo.getMainPattern())) {
            return normalizeCompareToken(materialInfo.getMainPattern());
        }
        SkuScheduleDTO currentSku = findSkuByMaterialCode(context.getContinuousSkuList(), machine.getCurrentMaterialCode());
        return currentSku != null ? normalizeCompareToken(currentSku.getMainPattern()) : null;
    }

    private String resolveSkuMainPatternStrict(LhScheduleContext context, SkuScheduleDTO sku) {
        if (context == null || sku == null) {
            return null;
        }
        MdmMaterialInfo materialInfo = context.getMaterialInfoMap().get(sku.getMaterialCode());
        if (materialInfo != null && StringUtils.isNotEmpty(materialInfo.getMainPattern())) {
            return normalizeCompareToken(materialInfo.getMainPattern());
        }
        return normalizeCompareToken(sku.getMainPattern());
    }

    private String resolveMachineStructureKey(LhScheduleContext context, MachineScheduleDTO machine) {
        MdmMaterialInfo materialInfo = resolveMachineMaterialInfo(context, machine);
        if (materialInfo != null) {
            String structureKey = resolveStructureKey(materialInfo.getStructureName(),
                    materialInfo.getSpecifications(),
                    materialInfo.getMainPattern(),
                    materialInfo.getPattern());
            if (StringUtils.isNotEmpty(structureKey)) {
                return structureKey;
            }
        }
        SkuScheduleDTO currentSku = findSkuByMaterialCode(context.getContinuousSkuList(), machine.getCurrentMaterialCode());
        return currentSku != null ? resolveStructureKey(currentSku) : null;
    }

    private MdmMaterialInfo resolveMachineMaterialInfo(LhScheduleContext context, MachineScheduleDTO machine) {
        if (context == null || machine == null || StringUtils.isEmpty(machine.getCurrentMaterialCode())) {
            return null;
        }
        return context.getMaterialInfoMap().get(machine.getCurrentMaterialCode());
    }

    private SkuScheduleDTO findSkuByMaterialCode(List<SkuScheduleDTO> skuList, String materialCode) {
        if (CollectionUtils.isEmpty(skuList) || StringUtils.isEmpty(materialCode)) {
            return null;
        }
        for (SkuScheduleDTO sku : skuList) {
            if (StringUtils.equals(materialCode, sku.getMaterialCode())) {
                return sku;
            }
        }
        return null;
    }

    private String resolveStructureKey(SkuScheduleDTO sku) {
        if (sku == null) {
            return null;
        }
        return resolveStructureKey(sku.getStructureName(), sku.getSpecCode(), sku.getMainPattern(), sku.getPattern());
    }

    private String resolveStructureKey(String structureName, String specCode, String mainPattern, String pattern) {
        if (StringUtils.isNotEmpty(structureName)) {
            return structureName;
        }
        String patternKey = resolvePatternKey(mainPattern, pattern);
        if (StringUtils.isEmpty(specCode) || StringUtils.isEmpty(patternKey)) {
            return null;
        }
        return specCode + "#" + patternKey;
    }

    private String resolvePatternKey(String mainPattern, String pattern) {
        if (StringUtils.isNotEmpty(mainPattern)) {
            return mainPattern;
        }
        return StringUtils.isNotEmpty(pattern) ? pattern : null;
    }

    private String normalizeCompareToken(String value) {
        if (StringUtils.isEmpty(value)) {
            return null;
        }
        String normalizedValue = value.trim();
        return StringUtils.isEmpty(normalizedValue) ? null : normalizedValue;
    }

    private String resolveMouldCode(LhScheduleContext context, String... materialCodes) {
        if (context == null || materialCodes == null) {
            return null;
        }
        for (String materialCode : materialCodes) {
            if (StringUtils.isEmpty(materialCode) || !context.getSkuMouldRelMap().containsKey(materialCode)) {
                continue;
            }
            String mouldCode = context.getSkuMouldRelMap().get(materialCode).stream()
                    .map(MdmSkuMouldRel::getMouldCode)
                    .filter(StringUtils::isNotEmpty)
                    .distinct()
                    .collect(Collectors.joining(","));
            if (StringUtils.isNotEmpty(mouldCode)) {
                return mouldCode;
            }
        }
        return null;
    }

    /**
     * 注册机台排程分配记录
     */
    private void registerMachineAssignment(LhScheduleContext context, String machineCode, LhScheduleResult result) {
        context.getMachineAssignmentMap()
                .computeIfAbsent(machineCode, k -> new ArrayList<>())
                .add(result);
    }

    /**
     * 在所有SKU列表中查找指定materialCode的SKU
     */
    private SkuScheduleDTO findSkuDto(LhScheduleContext context, String materialCode) {
        if (context == null || StringUtils.isEmpty(materialCode)) {
            return null;
        }
        for (SkuScheduleDTO sku : context.getContinuousSkuList()) {
            if (materialCode.equals(sku.getMaterialCode())) {
                return sku;
            }
        }
        for (SkuScheduleDTO sku : context.getNewSpecSkuList()) {
            if (materialCode.equals(sku.getMaterialCode())) {
                return sku;
            }
        }
        if (!CollectionUtils.isEmpty(context.getStructureSkuMap())) {
            for (List<SkuScheduleDTO> skuList : context.getStructureSkuMap().values()) {
                if (CollectionUtils.isEmpty(skuList)) {
                    continue;
                }
                for (SkuScheduleDTO sku : skuList) {
                    if (materialCode.equals(sku.getMaterialCode())) {
                        return sku;
                    }
                }
            }
        }
        return null;
    }

    /**
     * 生成工单号（使用线程安全的OrderNoGenerator）
     */
    private String generateOrderNo(LhScheduleContext context) {
        return orderNoGenerator.generateOrderNo(context.getScheduleTargetDate());
    }

    /**
     * 获取目标排产量解析器。
     *
     * @return 目标排产量解析器
     */
    private TargetScheduleQtyResolver getTargetScheduleQtyResolver() {
        return targetScheduleQtyResolver != null
                ? targetScheduleQtyResolver
                : new TargetScheduleQtyResolver();
    }
}
