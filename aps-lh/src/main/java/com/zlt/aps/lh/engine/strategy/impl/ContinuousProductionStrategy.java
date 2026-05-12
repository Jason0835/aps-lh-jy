/**
 * Copyright (c) 2008, 智立通（厦门）科技有限公司 All rights reserved。
 */
package com.zlt.aps.lh.engine.strategy.impl;

import com.zlt.aps.lh.api.constant.LhScheduleConstant;
import com.zlt.aps.lh.api.constant.LhScheduleParamConstant;
import com.zlt.aps.lh.api.domain.dto.MachineCleaningWindowDTO;
import com.zlt.aps.lh.api.domain.dto.MachineMaintenanceWindowDTO;
import com.zlt.aps.lh.component.TargetScheduleQtyResolver;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.api.domain.dto.MachineScheduleDTO;
import com.zlt.aps.lh.api.domain.dto.ShiftProductionControlDTO;
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
import com.zlt.aps.lh.service.impl.LhMaintenanceScheduleService;
import com.zlt.aps.lh.util.LeftRightMouldUtil;
import com.zlt.aps.lh.util.ShiftFieldUtil;
import com.zlt.aps.lh.util.LhScheduleTimeUtil;
import com.zlt.aps.lh.util.LhSpecialMaterialUtil;
import com.zlt.aps.lh.util.LhMultiMachineDistributionUtil;
import com.zlt.aps.lh.util.LhSpecifyMachineUtil;
import com.zlt.aps.lh.util.MachineCleaningOverlapUtil;
import com.zlt.aps.lh.util.PriorityTraceLogHelper;
import com.zlt.aps.lh.util.ShiftCapacityResolverUtil;
import com.zlt.aps.lh.util.ShiftProductionControlUtil;
import com.zlt.aps.lh.util.SingleMouldShiftQtyUtil;
import com.zlt.aps.lh.component.OrderNoGenerator;
import com.zlt.aps.mdm.api.domain.entity.MdmMaterialInfo;
import com.zlt.aps.mdm.api.domain.entity.MdmDevicePlanShut;
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
 * <p>处理续作场景下的排产逻辑, 包括收尾判定、班次分配、库存调整、降模等</p>
 *
 * @author APS
 */
@Slf4j
@Component("continuousProductionStrategy")
public class ContinuousProductionStrategy implements IProductionStrategy {

    private static final String CONTINUOUS_SCHEDULE_TYPE = "01";
    private static final String AUTO_DATA_SOURCE = "0";
    private static final String ZERO_PLAN_UNSCHEDULED_REASON = "续作结果裁剪为0";
    private static final int TYPE_BLOCK_SWITCH_MAX_ATTEMPTS = 16;

    @Resource
    private OrderNoGenerator orderNoGenerator;

    @Resource
    private IEndingJudgmentStrategy endingJudgmentStrategy;
    @Resource
    private TargetScheduleQtyResolver targetScheduleQtyResolver;
    @Resource
    private LhMaintenanceScheduleService maintenanceScheduleService;

    /**
     * 定点物料新增换模预判沿用默认策略 Bean，保证与主流程口径一致。
     */
    @Resource
    private IMouldChangeBalanceStrategy mouldChangeBalanceStrategy;

    @Resource
    private IFirstInspectionBalanceStrategy firstInspectionBalanceStrategy;

    @Resource
    private ICapacityCalculateStrategy capacityCalculateStrategy;

    @Override
    public String getStrategyType() {
        return ScheduleTypeEnum.CONTINUOUS.getCode();
    }

    @Override
    public String getStrategyName() {
        return "continuousProductionStrategy";
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

            // 收尾SKU严格限制目标量，不允许为了填满班次而超排
            if (isEnding) {
                sku.setStrictTargetQty(true);
            }

            // 滚动衔接时沿用机台继承后的可用时间，避免从重叠窗口首班重复起排。
            Date startTime = resolveContinuousStartTime(context, machine, shifts);
            Date specifySwitchStartTime = !isEnding
                    ? tryReserveSpecifySqueezeSwitchStartTime(context, machine, sku, shifts) : null;
            List<LhShiftConfigVO> effectiveShifts = specifySwitchStartTime == null
                    ? shifts : filterShiftsBeforeSwitchStart(shifts, specifySwitchStartTime);
            int machineMouldQty = ShiftCapacityResolverUtil.resolveMachineMouldQty(machine);
            sku.setMouldQty(machineMouldQty);
            LhScheduleResult inheritedResult = findMergeableRollingInheritedResult(context, machineCode, sku.getMaterialCode());
            LhScheduleResult result = inheritedResult != null
                    ? appendScheduleToInheritedResult(context, inheritedResult, machine, sku,
                    startTime, effectiveShifts, machineMouldQty, isEnding)
                    : buildScheduleResult(context, machine, sku, startTime, null, effectiveShifts, machineMouldQty, isEnding);
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
                } else if (specifySwitchStartTime != null && result.getDailyPlanQty() != null
                        && result.getDailyPlanQty() > 0) {
                    machine.setEnding(true);
                    machine.setEstimatedEndTime(specifySwitchStartTime);
                    context.getSpecifyMachineReservedSwitchStartTimeMap().put(machineCode, specifySwitchStartTime);
                    log.info("触发定点机台挤量, machineCode: {}, currentMaterialCode: {}, reservedMaterialCode: {}, switchStartTime: {}",
                            machineCode, sku.getMaterialCode(),
                            context.getSpecifyMachineReservedMaterialMap().get(machineCode),
                            LhScheduleTimeUtil.formatDateTime(specifySwitchStartTime));
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

        // 按物料编码汇总多机台排产量，再统一做库存裁剪（避免多机台场景下各机台独立比对导致总量超库存）
        Map<String, Integer> materialTotalPlanMap = new LinkedHashMap<>(16);
        Map<String, SkuScheduleDTO> materialSkuMap = new LinkedHashMap<>(16);
        List<LhScheduleResult> continuousResults = new ArrayList<>();
        for (LhScheduleResult result : context.getScheduleResultList()) {
            if (!isContinuousPhaseResult(result)) {
                continue;
            }
            if (result.getEmbryoCode() == null) {
                continue;
            }
            SkuScheduleDTO sku = findSkuDto(context, result.getMaterialCode());
            if (sku == null || sku.getEmbryoStock() < 0) {
                continue;
            }
            int planQty = ShiftFieldUtil.resolveScheduledQty(result);
            materialTotalPlanMap.merge(result.getMaterialCode(), planQty, Integer::sum);
            materialSkuMap.putIfAbsent(result.getMaterialCode(), sku);
            continuousResults.add(result);
        }
        // 按汇总计划量统一裁剪同物料的所有结果
        for (LhScheduleResult result : continuousResults) {
            String materialCode = result.getMaterialCode();
            int totalPlan = materialTotalPlanMap.getOrDefault(materialCode, 0);
            SkuScheduleDTO sku = materialSkuMap.get(materialCode);
            if (sku == null || totalPlan <= 0 || totalPlan <= sku.getEmbryoStock()) {
                continue;
            }
            // 库存不足，按比例削减各班次计划量
            double ratio = (double) sku.getEmbryoStock() / totalPlan;
            scaleShiftPlanQty(context, result, ratio, shifts);
            refreshResultSummary(context, result, shifts);
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
        // 多机台余量和胎胚库存按实际排产量占比分摊，尾差由最大余数法补齐
        distributeMultiMachineSurplusAndStock(context);
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
     * 解析定点机台挤量的切换开始时间。
     *
     * @param context 排程上下文
     * @param machine 当前机台
     * @param currentSku 当前续作SKU
     * @param shifts 排程窗口班次
     * @return 切换开始时间，未触发挤量返回null
     */
    private Date tryReserveSpecifySqueezeSwitchStartTime(LhScheduleContext context,
                                                         MachineScheduleDTO machine,
                                                         SkuScheduleDTO currentSku,
                                                         List<LhShiftConfigVO> shifts) {
        if (context == null || machine == null || currentSku == null || CollectionUtils.isEmpty(shifts)
                || StringUtils.isEmpty(machine.getMachineCode())
                || StringUtils.isEmpty(currentSku.getMaterialCode())) {
            return null;
        }
        String machineCode = machine.getMachineCode();
        if (LhSpecifyMachineUtil.isLimitSpecifyMachine(context, machineCode, currentSku.getMaterialCode())) {
            return null;
        }
        SkuScheduleDTO specifySku = selectLimitSpecifySkuByMachine(context, machine);
        if (specifySku == null) {
            return null;
        }
        Date firstLastWorkDayShiftStartTime = resolveFirstLastWorkDayShiftStartTime(shifts);
        if (firstLastWorkDayShiftStartTime == null) {
            log.debug("定点机台挤量跳过, machineCode: {}, materialCode: {}, 原因: 最后业务日无可排班次",
                    machineCode, specifySku.getMaterialCode());
            return null;
        }
        int switchHours = isTypeBlockCandidate(context, machine, specifySku)
                ? LhScheduleTimeUtil.getTypeBlockChangeTotalHours(context)
                : LhScheduleTimeUtil.getMouldChangeTotalHours(context);
        Date switchStartTime = LhScheduleTimeUtil.addHours(firstLastWorkDayShiftStartTime, -switchHours);
        switchStartTime = resolveLatestAllowedSwitchStartTime(context, switchStartTime);
        List<LhShiftConfigVO> retainedShifts = filterShiftsBeforeSwitchStart(shifts, switchStartTime);
        if (CollectionUtils.isEmpty(retainedShifts)) {
            log.debug("定点机台挤量跳过, machineCode: {}, materialCode: {}, 原因: 当前SKU无可保留班次",
                    machineCode, specifySku.getMaterialCode());
            return null;
        }
        if (!canScheduleSpecifySkuOnMachine(context, machine, specifySku, shifts, switchStartTime)) {
            log.info("定点机台挤量跳过, machineCode: {}, materialCode: {}, 原因: 定点物料无法在预留机台正常排产",
                    machineCode, specifySku.getMaterialCode());
            return null;
        }
        reserveSpecifySqueeze(context, machineCode, specifySku.getMaterialCode(), switchStartTime);
        return switchStartTime;
    }

    /**
     * 回写定点机台挤量预留信息。
     *
     * @param context 排程上下文
     * @param machineCode 机台编码
     * @param materialCode 预留物料编码
     * @param switchStartTime 预留切换开始时间
     */
    private void reserveSpecifySqueeze(LhScheduleContext context,
                                       String machineCode,
                                       String materialCode,
                                       Date switchStartTime) {
        if (context == null || StringUtils.isEmpty(machineCode)
                || StringUtils.isEmpty(materialCode) || switchStartTime == null) {
            return;
        }
        context.getSpecifyMachineReservedMaterialMap().put(machineCode, materialCode);
        context.getSpecifyMachineReservedSwitchStartTimeMap().put(machineCode, switchStartTime);
    }

    /**
     * 过滤切换开始时间之前完整可用的班次。
     *
     * @param shifts 原排程窗口班次
     * @param switchStartTime 切换开始时间
     * @return 保留班次
     */
    private List<LhShiftConfigVO> filterShiftsBeforeSwitchStart(List<LhShiftConfigVO> shifts, Date switchStartTime) {
        if (CollectionUtils.isEmpty(shifts) || switchStartTime == null) {
            return new ArrayList<>(0);
        }
        List<LhShiftConfigVO> retainedShifts = new ArrayList<>(shifts.size());
        for (LhShiftConfigVO shift : shifts) {
            if (shift == null || shift.getShiftEndDateTime() == null) {
                continue;
            }
            if (!shift.getShiftEndDateTime().after(switchStartTime)) {
                retainedShifts.add(shift);
            }
        }
        return retainedShifts;
    }

    /**
     * 选择当前机台配置的限制作业定点SKU。
     *
     * @param context 排程上下文
     * @param machine 当前机台
     * @return 定点SKU，未命中返回null
     */
    private SkuScheduleDTO selectLimitSpecifySkuByMachine(LhScheduleContext context, MachineScheduleDTO machine) {
        if (context == null || machine == null || StringUtils.isEmpty(machine.getMachineCode())
                || CollectionUtils.isEmpty(context.getNewSpecSkuList())) {
            return null;
        }
        String machineCode = machine.getMachineCode();
        for (SkuScheduleDTO sku : context.getNewSpecSkuList()) {
            if (sku == null || StringUtils.isEmpty(sku.getMaterialCode()) || sku.resolveTargetScheduleQty() <= 0) {
                continue;
            }
            if (LhSpecifyMachineUtil.isLimitSpecifyMachine(context, machineCode, sku.getMaterialCode())) {
                return sku;
            }
        }
        return null;
    }

    /**
     * 解析排程窗口最后业务日的首个班次开始时间。
     *
     * @param shifts 排程窗口班次
     * @return 首个班次开始时间
     */
    private Date resolveFirstLastWorkDayShiftStartTime(List<LhShiftConfigVO> shifts) {
        Date lastWorkDate = null;
        for (LhShiftConfigVO shift : shifts) {
            if (shift == null || shift.getWorkDate() == null) {
                continue;
            }
            Date workDate = LhScheduleTimeUtil.clearTime(shift.getWorkDate());
            if (lastWorkDate == null || workDate.after(lastWorkDate)) {
                lastWorkDate = workDate;
            }
        }
        if (lastWorkDate == null) {
            return null;
        }
        Date firstShiftStartTime = null;
        for (LhShiftConfigVO shift : shifts) {
            if (shift == null || shift.getWorkDate() == null || shift.getShiftStartDateTime() == null) {
                continue;
            }
            Date workDate = LhScheduleTimeUtil.clearTime(shift.getWorkDate());
            if (!lastWorkDate.equals(workDate)) {
                continue;
            }
            Date shiftStartTime = shift.getShiftStartDateTime();
            if (firstShiftStartTime == null || shiftStartTime.before(firstShiftStartTime)) {
                firstShiftStartTime = shiftStartTime;
            }
        }
        return firstShiftStartTime;
    }

    /**
     * 反推不晚于候选时间的最晚合法切换开始时间。
     *
     * @param context 排程上下文
     * @param candidateStartTime 候选切换开始时间
     * @return 合法切换开始时间
     */
    private Date resolveLatestAllowedSwitchStartTime(LhScheduleContext context, Date candidateStartTime) {
        if (candidateStartTime == null || !LhScheduleTimeUtil.isNoMouldChangeTime(context, candidateStartTime)) {
            return candidateStartTime;
        }
        java.util.Calendar calendar = java.util.Calendar.getInstance();
        calendar.setTime(candidateStartTime);
        int hour = calendar.get(java.util.Calendar.HOUR_OF_DAY);
        Date baseDate = LhScheduleTimeUtil.clearTime(candidateStartTime);
        if (hour < LhScheduleTimeUtil.getMorningStartHour(context)) {
            baseDate = LhScheduleTimeUtil.addDays(baseDate, -1);
        }
        return LhScheduleTimeUtil.buildTime(baseDate, LhScheduleTimeUtil.getNoMouldChangeStartHour(context), 0, 0);
    }

    /**
     * 判断SKU是否满足换活字块条件：同胎胚、同规格、不同花纹。
     */
    private boolean isTypeBlockCandidate(LhScheduleContext context,
                                         MachineScheduleDTO machine,
                                         SkuScheduleDTO sku) {
        if (sku == null) {
            return false;
        }
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
     * 基于指定收尾时间计算换活字块开产时间。
     */
    private Date calcTypeBlockStartTime(LhScheduleContext context,
                                        MachineScheduleDTO machine,
                                        Date estimatedEndTime) {
        if (machine == null || estimatedEndTime == null) {
            return null;
        }
        Date switchStartTime = calcTypeBlockSwitchStartTime(context, machine, estimatedEndTime);
        return resolveTypeBlockProductionStartTime(context, machine, estimatedEndTime, switchStartTime);
    }

    /**
     * 基于指定收尾时间计算换活字块开始时间。
     */
    private Date calcTypeBlockSwitchStartTime(LhScheduleContext context,
                                              MachineScheduleDTO machine,
                                              Date estimatedEndTime) {
        if (machine == null || estimatedEndTime == null) {
            return null;
        }
        if (getMaintenanceScheduleService().shouldApplyMaintenanceOverlapSwitchRule(context, machine, estimatedEndTime)) {
            return getMaintenanceScheduleService().resolveMaintenanceEndTime(context, machine);
        }
        Date switchStartTime = resolveAllowedSwitchStartTime(
                context, machine.getMachineCode(), estimatedEndTime);
        switchStartTime = getMaintenanceScheduleService().delaySwitchStartByMaintenance(
                machine, switchStartTime, LhScheduleTimeUtil.getTypeBlockChangeTotalHours(context));
        return switchStartTime;
    }

    /**
     * 基于换活字块开始时间计算开产时间。
     */
    private Date resolveTypeBlockProductionStartTime(LhScheduleContext context,
                                                     MachineScheduleDTO machine,
                                                     Date estimatedEndTime,
                                                     Date switchStartTime) {
        if (switchStartTime == null) {
            return null;
        }
        if (getMaintenanceScheduleService().shouldApplyMaintenanceOverlapSwitchRule(context, machine, estimatedEndTime)) {
            Date inspectionStartTime = LhScheduleTimeUtil.addHours(
                    switchStartTime, LhScheduleTimeUtil.getMaintenanceOverlapSwitchHours(context));
            return LhScheduleTimeUtil.addHours(
                    inspectionStartTime, LhScheduleTimeUtil.getFirstInspectionHours(context));
        }
        return LhScheduleTimeUtil.addHours(
                switchStartTime, LhScheduleTimeUtil.getTypeBlockChangeTotalHours(context));
    }

    /**
     * 解析允许发起切换（换模/换活字块）的开始时间。
     * <p>20:00:00 允许发起切换，20:00:00 之后到次日早班前需顺延到下一个早班开始时间。</p>
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
     * 判断定点物料在当前机台和窗口内是否可排。
     * <p>这里仅做预判，不落正式结果，也不改变主流程状态。</p>
     *
     * @param context 排程上下文
     * @param machine 当前机台
     * @param specifySku 定点物料
     * @param shifts 排程窗口班次
     * @param endingTime 机台切换起点
     * @return true-可排，false-不可排
     */
    private boolean canScheduleSpecifySkuOnMachine(LhScheduleContext context,
                                                   MachineScheduleDTO machine,
                                                   SkuScheduleDTO specifySku,
                                                   List<LhShiftConfigVO> shifts,
                                                   Date endingTime) {
        if (context == null
                || machine == null
                || specifySku == null
                || endingTime == null
                || CollectionUtils.isEmpty(shifts)
                || StringUtils.isEmpty(machine.getMachineCode())) {
            return false;
        }
        if (isTypeBlockCandidate(context, machine, specifySku)) {
            Date typeBlockSwitchStartTime = calcTypeBlockSwitchStartTime(context, machine, endingTime);
            Date typeBlockStartTime = resolveTypeBlockProductionStartTime(
                    context, machine, endingTime, typeBlockSwitchStartTime);
            if (typeBlockStartTime == null || typeBlockSwitchStartTime == null) {
                return false;
            }
            int refinedTargetQty = getTargetScheduleQtyResolver().refineTargetQtyByMachineCapacity(
                    context,
                    specifySku,
                    machine,
                    typeBlockSwitchStartTime,
                    typeBlockStartTime,
                    shifts);
            if (refinedTargetQty <= 0) {
                log.debug("定点物料换活字块预判不可排, machineCode: {}, materialCode: {}, startTime: {}",
                        machine.getMachineCode(), specifySku.getMaterialCode(),
                        LhScheduleTimeUtil.formatDateTime(typeBlockStartTime));
                return false;
            }
            return true;
        }
        return canScheduleSpecifySkuByNewSpecPath(context, machine, specifySku, shifts, endingTime);
    }

    /**
     * 按新增换模链路预判定点物料是否可排。
     *
     * @param context 排程上下文
     * @param machine 当前机台
     * @param specifySku 定点物料
     * @param shifts 排程窗口班次
     * @param endingTime 机台切换起点
     * @return true-可排，false-不可排
     */
    private boolean canScheduleSpecifySkuByNewSpecPath(LhScheduleContext context,
                                                       MachineScheduleDTO machine,
                                                       SkuScheduleDTO specifySku,
                                                       List<LhShiftConfigVO> shifts,
                                                       Date endingTime) {
        Date machineReadyTime = getCapacityCalculateStrategy().calculateStartTime(
                context, machine.getMachineCode(), endingTime);
        boolean maintenanceOverlapSwitch = getMaintenanceScheduleService()
                .shouldApplyMaintenanceOverlapSwitchRule(context, machine, endingTime);
        Date switchReadyTime = maintenanceOverlapSwitch
                ? getMaintenanceScheduleService().resolveMaintenanceEndTime(context, machine)
                : machineReadyTime;
        switchReadyTime = ShiftProductionControlUtil.resolveEarliestSwitchStartTime(context, switchReadyTime);
        Date mouldChangeStartTime = getMouldChangeBalanceStrategy().allocateMouldChange(
                context, machine.getMachineCode(), switchReadyTime);
        if (mouldChangeStartTime == null) {
            log.debug("定点物料新增换模预判不可排, machineCode: {}, materialCode: {}, 原因: 无可用换模窗口",
                    machine.getMachineCode(), specifySku.getMaterialCode());
            return false;
        }
        Date inspectionTime = null;
        try {
            int switchDurationHours = maintenanceOverlapSwitch
                    ? LhScheduleTimeUtil.getMaintenanceOverlapSwitchHours(context)
                    : LhScheduleTimeUtil.getMouldChangeTotalHours(context);
            Date mouldChangeCompleteTime = LhScheduleTimeUtil.addHours(mouldChangeStartTime, switchDurationHours);
            inspectionTime = getFirstInspectionBalanceStrategy().allocateInspection(
                    context, machine.getMachineCode(), mouldChangeCompleteTime);
            if (inspectionTime == null) {
                log.debug("定点物料新增换模预判不可排, machineCode: {}, materialCode: {}, 原因: 首检窗口分配失败",
                        machine.getMachineCode(), specifySku.getMaterialCode());
                return false;
            }
            Date productionStartTime = maintenanceOverlapSwitch
                    ? LhScheduleTimeUtil.addHours(inspectionTime, LhScheduleTimeUtil.getFirstInspectionHours(context))
                    : inspectionTime;
            int machineMouldQty = ShiftCapacityResolverUtil.resolveMachineMouldQty(machine);
            int runtimeShiftCapacity = ShiftCapacityResolverUtil.resolveRuntimeShiftCapacity(
                    context, machine, specifySku.getShiftCapacity());
            Date firstProductionStartTime = ShiftProductionControlUtil.resolveFirstSchedulableStartIgnoringCleaning(
                    context,
                    machine.getMachineCode(),
                    productionStartTime,
                    shifts,
                    runtimeShiftCapacity,
                    specifySku.getLhTimeSeconds(),
                    machineMouldQty);
            if (firstProductionStartTime == null) {
                log.debug("定点物料新增换模预判不可排, machineCode: {}, materialCode: {}, 原因: 窗口内无可开产时间",
                        machine.getMachineCode(), specifySku.getMaterialCode());
                return false;
            }
            int refinedTargetQty = getTargetScheduleQtyResolver().refineTargetQtyByMachineCapacity(
                    context, specifySku, machine, mouldChangeStartTime, firstProductionStartTime, shifts);
            if (refinedTargetQty <= 0) {
                log.debug("定点物料新增换模预判不可排, machineCode: {}, materialCode: {}, 原因: 收敛后目标量为0",
                        machine.getMachineCode(), specifySku.getMaterialCode());
                return false;
            }
            return true;
        } finally {
            if (inspectionTime != null) {
                getFirstInspectionBalanceStrategy().rollbackInspection(context, inspectionTime);
            }
            getMouldChangeBalanceStrategy().rollbackMouldChange(context, mouldChangeStartTime);
        }
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
        int runtimeShiftCapacity = ShiftCapacityResolverUtil.resolveRuntimeShiftCapacity(
                context, machine, sku.getShiftCapacity());
        result.setSingleMouldShiftQty(SingleMouldShiftQtyUtil.resolveSingleMouldShiftQty(
                context, sku, machine, mouldQty));
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
        result.setHasSpecialMaterial(LhSpecialMaterialUtil.resolveHasSpecialMaterial(context, sku));

        // 生成工单号
        String orderNo = generateOrderNo(context);
        result.setOrderNo(orderNo);

        int refinedTargetQty = getTargetScheduleQtyResolver().refineTargetQtyByMachineCapacity(
                context, sku, machine, switchStartTime, startTime, shifts);
        List<MachineCleaningWindowDTO> cleaningWindowList = new ArrayList<>(MachineCleaningOverlapUtil.excludeOverlapWindows(
                machine.getCleaningWindowList(), switchStartTime, startTime));
        List<MachineMaintenanceWindowDTO> maintenanceWindowList = resolveMachineMaintenanceWindowList(
                context, machine.getMachineCode());

        // 按班次分配计划量
        int remaining = refinedTargetQty;
        distributeToShifts(context, result, shifts, startTime,
                runtimeShiftCapacity, sku.getLhTimeSeconds(), mouldQty, remaining, cleaningWindowList,
                maintenanceWindowList);

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
                                   List<MachineCleaningWindowDTO> cleaningWindowList,
                                   List<MachineMaintenanceWindowDTO> maintenanceWindowList) {
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

            ShiftProductionControlDTO control = ShiftProductionControlUtil.resolveEffectiveControl(context, shift, startTime);
            if (control == null || !control.isCanSchedule()) {
                continue;
            }
            Date effectiveStart = control.getEffectiveStartTime();
            Date effectiveEnd = control.getEffectiveEndTime();

            int shiftMaxQty = ShiftCapacityResolverUtil.resolveShiftCapacityWithDowntime(
                    context.getDevicePlanShutList(),
                    cleaningWindowList,
                    maintenanceWindowList,
                    result.getLhMachineCode(),
                    effectiveStart,
                    effectiveEnd,
                    shiftCapacity,
                    lhTimeSeconds,
                    mouldQty,
                    ShiftCapacityResolverUtil.resolveShiftDurationSeconds(shift),
                    dryIceLossQty,
                    dryIceDurationHours);
            shiftMaxQty = ShiftProductionControlUtil.deductCapacityByControl(control, shiftMaxQty, mouldQty);
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
                    maintenanceWindowList,
                    result.getLhMachineCode(),
                    effectiveStart,
                    effectiveEnd,
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
        List<MachineMaintenanceWindowDTO> maintenanceWindowList = resolveMachineMaintenanceWindowList(
                context, result.getLhMachineCode());
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
            ShiftProductionControlDTO control = ShiftProductionControlUtil.resolveEffectiveControl(context, shift, cursorStartTime);
            if (control == null || !control.isCanSchedule()) {
                setShiftPlanQty(result, shift.getShiftIndex(), 0, null, null);
                continue;
            }
            Date effectiveStartTime = control.getEffectiveStartTime();
            Date effectiveEndTime = control.getEffectiveEndTime();
            int shiftMaxQty = ShiftCapacityResolverUtil.resolveShiftCapacityWithDowntime(
                    context.getDevicePlanShutList(),
                    cleaningWindowList,
                    maintenanceWindowList,
                    result.getLhMachineCode(),
                    effectiveStartTime,
                    effectiveEndTime,
                    shiftCapacity,
                    result.getLhTime(),
                    mouldQty,
                    ShiftCapacityResolverUtil.resolveShiftDurationSeconds(shift),
                    dryIceLossQty,
                    dryIceDurationHours);
            shiftMaxQty = ShiftProductionControlUtil.deductCapacityByControl(control, shiftMaxQty, mouldQty);
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
                    maintenanceWindowList,
                    result.getLhMachineCode(),
                    effectiveStartTime,
                    effectiveEndTime,
                    shiftQty,
                    shiftMaxQty);
            setShiftPlanQty(result, shift.getShiftIndex(), shiftQty, effectiveStartTime, shiftPlanEndTime);
            remaining -= shiftQty;
            cursorStartTime = effectiveEndTime;
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
     * 基于最终计划量复核续作结果收尾标记。
     * <p>口径：当日计划量 >= max(硫化余量, 胎胚库存)时记为收尾，否则记为正常。</p>
     *
     * @param context 排程上下文
     */
    /**
     * 基于最终计划量复核续作结果收尾标记（按物料编码汇总多机台排产量后统一判断）。
     *
     * @param context 排程上下文
     */
    private void refreshContinuousEndingFlagByResult(LhScheduleContext context) {
        if (context == null || CollectionUtils.isEmpty(context.getScheduleResultList())) {
            return;
        }
        // 按物料编码汇总续作结果的总计划量
        Map<String, Integer> materialTotalPlanQtyMap = new LinkedHashMap<>(16);
        Map<String, Integer> materialEndingDemandQtyMap = new LinkedHashMap<>(16);
        for (LhScheduleResult result : context.getScheduleResultList()) {
            if (!isContinuousPhaseResult(result) || StringUtils.isEmpty(result.getMaterialCode())) {
                continue;
            }
            int planQty = ShiftFieldUtil.resolveScheduledQty(result);
            materialTotalPlanQtyMap.merge(result.getMaterialCode(), planQty, Integer::sum);
            if (!materialEndingDemandQtyMap.containsKey(result.getMaterialCode())) {
                materialEndingDemandQtyMap.put(result.getMaterialCode(), resolveEndingDemandQty(context, result));
            }
        }
        // 基于汇总计划量统一设置同物料所有结果的收尾标记
        for (LhScheduleResult result : context.getScheduleResultList()) {
            if (!isContinuousPhaseResult(result) || StringUtils.isEmpty(result.getMaterialCode())) {
                continue;
            }
            int totalPlanQty = materialTotalPlanQtyMap.getOrDefault(result.getMaterialCode(), 0);
            int endingDemandQty = materialEndingDemandQtyMap.getOrDefault(result.getMaterialCode(), 0);
            result.setIsEnd(totalPlanQty >= endingDemandQty && endingDemandQty > 0 ? "1" : "0");
        }
    }

    /**
     * 计算结果行收尾比较量（从SKU DTO取全量值，避免多机台分摊后偏小）。
     *
     * @param context 排程上下文
     * @param result 排程结果
     * @return max(硫化余量, 胎胚库存)
     */
    private int resolveEndingDemandQty(LhScheduleContext context, LhScheduleResult result) {
        SkuScheduleDTO sku = findSkuDto(context, result.getMaterialCode());
        int surplusQty = sku != null ? Math.max(0, sku.getSurplusQty()) : 0;
        int embryoStock = sku != null ? Math.max(0, sku.getEmbryoStock()) : 0;
        return Math.max(surplusQty, embryoStock);
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
     * 按比例削减各班次计划量（用于库存裁剪或降模）。
     *
     * @param result 排程结果
     * @param ratio  削减比例
     * @param shifts 班次列表
     */
    private void scaleShiftPlanQty(LhScheduleContext context, LhScheduleResult result, double ratio, List<LhShiftConfigVO> shifts) {
        for (LhShiftConfigVO shift : shifts) {
            int idx = shift.getShiftIndex();
            Integer qty = ShiftFieldUtil.getShiftPlanQty(result, idx);
            if (qty != null && qty > 0) {
                setShiftPlanQty(result, idx, (int) (qty * ratio),
                        ShiftFieldUtil.getShiftStartTime(result, idx),
                        ShiftFieldUtil.getShiftEndTime(result, idx));
            }
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
     * 多机台余量和胎胚库存按机台条数均分。
     * <p>对续作阶段结果按物料分组，委托 {@link LhMultiMachineDistributionUtil#distributeForSingleMaterial}
     * 按机台结果条数均分，最后一条补尾差。</p>
     *
     * @param context 排程上下文
     */
    private void distributeMultiMachineSurplusAndStock(LhScheduleContext context) {
        if (context == null || CollectionUtils.isEmpty(context.getScheduleResultList())) {
            return;
        }
        // 按物料编码汇总续作结果
        Map<String, List<LhScheduleResult>> materialResultsMap = new LinkedHashMap<>(16);
        for (LhScheduleResult result : context.getScheduleResultList()) {
            if (!isContinuousPhaseResult(result) || StringUtils.isEmpty(result.getMaterialCode())) {
                continue;
            }
            if (result.getDailyPlanQty() == null || result.getDailyPlanQty() <= 0) {
                continue;
            }
            materialResultsMap.computeIfAbsent(result.getMaterialCode(), k -> new ArrayList<>()).add(result);
        }
        // 委托工具类按机台条数均分
        for (Map.Entry<String, List<LhScheduleResult>> entry : materialResultsMap.entrySet()) {
            List<LhScheduleResult> materialResults = entry.getValue();
            if (materialResults.size() <= 1) {
                continue;
            }
            String materialCode = entry.getKey();
            SkuScheduleDTO sku = findSkuDto(context, materialCode);
            if (sku == null) {
                continue;
            }
            int totalSurplus = Math.max(0, sku.getSurplusQty());
            int totalEmbryoStock = Math.max(0, sku.getEmbryoStock());
            LhMultiMachineDistributionUtil.distributeForSingleMaterial(
                    materialResults, totalSurplus, totalEmbryoStock);
            log.debug("多机台续作余量/胎胚库存均分完成, materialCode: {}, 机台数: {}, 总余量: {}, 总胎胚库存: {}",
                    materialCode, materialResults.size(), totalSurplus, totalEmbryoStock);
        }
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
            List<MachineMaintenanceWindowDTO> maintenanceWindowList = resolveMachineMaintenanceWindowList(
                    context, result.getLhMachineCode());
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
                        maintenanceWindowList,
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

    private List<MachineMaintenanceWindowDTO> resolveMachineMaintenanceWindowList(LhScheduleContext context, String machineCode) {
        MachineScheduleDTO machine = context.getMachineScheduleMap().get(machineCode);
        if (machine == null || CollectionUtils.isEmpty(machine.getMaintenanceWindowList())) {
            return new ArrayList<>();
        }
        return machine.getMaintenanceWindowList();
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

    private String resolvePatternKey(String mainPattern, String pattern) {
        if (StringUtils.isNotEmpty(mainPattern)) {
            return mainPattern;
        }
        return StringUtils.isNotEmpty(pattern) ? pattern : null;
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

    private IMouldChangeBalanceStrategy getMouldChangeBalanceStrategy() {
        return mouldChangeBalanceStrategy;
    }

    private IFirstInspectionBalanceStrategy getFirstInspectionBalanceStrategy() {
        return firstInspectionBalanceStrategy;
    }

    private ICapacityCalculateStrategy getCapacityCalculateStrategy() {
        return capacityCalculateStrategy;
    }

    private LhMaintenanceScheduleService getMaintenanceScheduleService() {
        return maintenanceScheduleService != null
                ? maintenanceScheduleService
                : new LhMaintenanceScheduleService();
    }
}
