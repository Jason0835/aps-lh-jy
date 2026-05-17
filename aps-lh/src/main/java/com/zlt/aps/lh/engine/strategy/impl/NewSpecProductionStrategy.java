/**
 * Copyright (c) 2008, 智立通（厦门）科技有限公司 All rights reserved。
 */
package com.zlt.aps.lh.engine.strategy.impl;

import com.zlt.aps.lh.api.constant.LhScheduleConstant;
import com.zlt.aps.lh.api.constant.LhScheduleParamConstant;
import com.zlt.aps.lh.api.domain.dto.MachineCleaningWindowDTO;
import com.zlt.aps.lh.api.domain.dto.MachineMaintenanceWindowDTO;
import com.zlt.aps.lh.api.domain.dto.SkuDailyPlanQuotaDTO;
import com.zlt.aps.lh.component.TargetScheduleQtyResolver;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.context.LhScheduleConfig;
import com.zlt.aps.lh.api.domain.dto.MachineScheduleDTO;
import com.zlt.aps.lh.api.domain.dto.ShiftProductionControlDTO;
import com.zlt.aps.lh.api.domain.vo.LhShiftConfigVO;
import com.zlt.aps.lh.api.domain.dto.ShiftRuntimeState;
import com.zlt.aps.lh.api.domain.dto.SkuScheduleDTO;
import com.zlt.aps.lh.api.domain.entity.LhScheduleResult;
import com.zlt.aps.lh.api.domain.entity.LhUnscheduledResult;
import com.zlt.aps.lh.api.enums.ConstructionStageEnum;
import com.zlt.aps.lh.api.enums.NewSpecFailReasonEnum;
import com.zlt.aps.lh.api.enums.ScheduleTypeEnum;
import com.zlt.aps.lh.engine.strategy.ICapacityCalculateStrategy;
import com.zlt.aps.lh.engine.strategy.IEndingJudgmentStrategy;
import com.zlt.aps.lh.engine.strategy.IFirstInspectionBalanceStrategy;
import com.zlt.aps.lh.engine.strategy.IMachineMatchStrategy;
import com.zlt.aps.lh.engine.strategy.IMouldChangeBalanceStrategy;
import com.zlt.aps.lh.engine.strategy.IProductionStrategy;
import com.zlt.aps.lh.engine.strategy.ITrialProductionStrategy;
import com.zlt.aps.lh.engine.strategy.support.MachineProductionSegment;
import com.zlt.aps.lh.engine.strategy.support.MachineScheduleRole;
import com.zlt.aps.lh.engine.strategy.support.ProductionQuantityPolicy;
import com.zlt.aps.lh.service.impl.LhMaintenanceScheduleService;
import com.zlt.aps.lh.util.LeftRightMouldUtil;
import com.zlt.aps.lh.util.LhSingleControlMachineUtil;
import com.zlt.aps.lh.util.ShiftFieldUtil;
import com.zlt.aps.lh.util.LhMultiMachineDistributionUtil;
import com.zlt.aps.lh.util.LhScheduleTimeUtil;
import com.zlt.aps.lh.util.LhSpecialMaterialUtil;
import com.zlt.aps.lh.util.MachineCleaningOverlapUtil;
import com.zlt.aps.lh.util.PriorityTraceLogHelper;
import com.zlt.aps.lh.util.ShiftCapacityResolverUtil;
import com.zlt.aps.lh.util.ShiftProductionControlUtil;
import com.zlt.aps.lh.util.SingleMouldShiftQtyUtil;
import com.zlt.aps.lh.util.SkuDailyPlanQuotaUtil;
import com.zlt.aps.lh.component.OrderNoGenerator;
import com.zlt.aps.mdm.api.domain.entity.MdmSkuConstructionRef;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import javax.annotation.Resource;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 新增规格排产策略实现
 * <p>处理新增规格上机的排产逻辑, 包括机台匹配、换模均衡、首检分配、产能计算等</p>
 *
 * @author APS
 */
@Slf4j
@Component("newSpecProductionStrategy")
public class NewSpecProductionStrategy implements IProductionStrategy {

    private static final String NEW_SPEC_SCHEDULE_TYPE = "02";
    private static final String AUTO_DATA_SOURCE = "0";
    private static final String ZERO_PLAN_UNSCHEDULED_REASON = "新增结果裁剪为0";
    private static final String NEW_SPEC_CLEANING_ANALYSIS = "模具清洗+换模";
    @Resource
    private OrderNoGenerator orderNoGenerator;
    @Resource
    private IEndingJudgmentStrategy endingJudgmentStrategy;
    @Resource
    private LocalSearchMachineAllocatorStrategy localSearchMachineAllocator;
    @Resource
    private TargetScheduleQtyResolver targetScheduleQtyResolver;
    @Resource
    private LhMaintenanceScheduleService maintenanceScheduleService;
    @Resource
    private ITrialProductionStrategy trialProductionStrategy;

    @Override
    public String getStrategyType() {
        return ScheduleTypeEnum.NEW_SPEC.getCode();
    }

    @Override
    public String getStrategyName() {
        return "newSpecProductionStrategy";
    }

    @Override
    public void scheduleContinuousEnding(LhScheduleContext context) {
        // 新增策略不处理续作收尾，空实现
    }

    @Override
    public void allocateShiftPlanQty(LhScheduleContext context) {
        log.info("新增排产 - 班次计划量分配, 新增排程结果数: {}",
                context.getScheduleResultList().stream().filter(r -> NEW_SPEC_SCHEDULE_TYPE.equals(r.getScheduleType())).count());
        // 班次计划量已在scheduleNewSpecs中随生成结果时分配完毕，此处为空实现
    }

    @Override
    public void adjustEmbryoStock(LhScheduleContext context) {
        log.info("新增排产 - 胎胚库存调整");
        // 按物料编码汇总多机台排产量，再统一做库存裁剪（避免多机台场景下各机台独立比对导致总量超库存）
        Map<String, Integer> materialTotalPlanMap = new LinkedHashMap<>(16);
        Map<String, SkuScheduleDTO> materialSkuMap = new LinkedHashMap<>(16);
        Map<String, List<LhScheduleResult>> materialResultMap = new LinkedHashMap<>(16);
        for (LhScheduleResult result : context.getScheduleResultList()) {
            if (!NEW_SPEC_SCHEDULE_TYPE.equals(result.getScheduleType())
                    || "1".equals(result.getIsTypeBlock())) {
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
            materialResultMap.computeIfAbsent(result.getMaterialCode(), key -> new ArrayList<LhScheduleResult>())
                    .add(result);
        }
        // 按汇总计划量统一裁剪同物料的所有结果
        List<LhShiftConfigVO> shifts = LhScheduleTimeUtil.getScheduleShifts(context, context.getScheduleDate());
        for (Map.Entry<String, List<LhScheduleResult>> entry : materialResultMap.entrySet()) {
            String materialCode = entry.getKey();
            int totalPlan = materialTotalPlanMap.getOrDefault(materialCode, 0);
            SkuScheduleDTO sku = materialSkuMap.get(materialCode);
            if (sku == null || totalPlan <= 0 || totalPlan <= sku.getEmbryoStock()) {
                continue;
            }
            if (shouldKeepFormalNewSpecFullCapacity(sku, entry.getValue())) {
                log.info("正式新增跳过胎胚库存后置裁减, materialCode: {}, totalPlan: {}, embryoStock: {}",
                        materialCode, totalPlan, sku.getEmbryoStock());
                continue;
            }
            // 库存不足时按物料整体裁剪，避免逐条逐班取整导致总量丢失。
            ShiftFieldUtil.scaleGroupedShiftPlanQty(entry.getValue(), shifts, sku.getEmbryoStock());
            for (LhScheduleResult result : entry.getValue()) {
                refreshResultSummary(context, result);
            }
        }
        // 多机台余量和胎胚库存按机台数均分，最后一台补尾差
        distributeMultiMachineSurplusAndStock(context);
        finalizeZeroPlanNewSpecResults(context);
        // 新增结果在库存裁剪后需按最终计划量复核收尾语义，避免"未收完却标收尾"。
        refreshNewSpecEndingFlagByResult(context);
        syncMachineStateAfterNewAdjust(context);
        // S4.5 后置步骤均完成后，再按当前待排列表收口结构视图，避免影响本阶段元数据回查。
        context.rebuildStructureSkuMapFromPending(context.getNewSpecSkuList());
    }

    /**
     * 正式新增在非试制场景下保留满班补齐结果，不做胎胚库存后置裁减。
     *
     * @param sku SKU排程DTO
     * @param skuResults 该物料编码对应的新增结果
     * @return true-保留满班结果，不做库存裁减
     */
    private boolean shouldKeepFormalNewSpecFullCapacity(SkuScheduleDTO sku, List<LhScheduleResult> skuResults) {
        if (sku == null || CollectionUtils.isEmpty(skuResults)) {
            return false;
        }
        boolean endingResult = skuResults.stream().anyMatch(result -> result != null && "1".equals(result.getIsEnd()));
        ProductionQuantityPolicy policy = ProductionQuantityPolicy.from(sku, endingResult);
        if (policy.isStrictUpperLimit() && !policy.isEnding()) {
            return false;
        }
        return true;
    }

    @Override
    public void scheduleReduceMould(LhScheduleContext context) {
        // 新增策略不处理降模，空实现
    }

    @Override
    public void scheduleNewSpecs(LhScheduleContext context,
                                 IMachineMatchStrategy machineMatch,
                                 IMouldChangeBalanceStrategy mouldChangeBalance,
                                 IFirstInspectionBalanceStrategy inspectionBalance,
                                 ICapacityCalculateStrategy capacityCalculate) {
        log.info("新增排产 - 执行新增规格排产, 新增SKU数: {}", context.getNewSpecSkuList().size());

        List<LhShiftConfigVO> shifts = LhScheduleTimeUtil.getScheduleShifts(context, context.getScheduleDate());
        int scheduledCount = 0;
        Map<String, Integer> unscheduledReasonCountMap = new LinkedHashMap<>(8);

        Iterator<SkuScheduleDTO> iterator = context.getNewSpecSkuList().iterator();
        while (iterator.hasNext()) {
            SkuScheduleDTO sku = iterator.next();
            // 续作阶段未命中的SKU在此继续参与新增排产兜底，不做提前拦截。
            boolean isEnding = endingJudgmentStrategy.isEnding(context, sku);
            // 收尾SKU在排产前上调目标量（考虑胎胚库存），非收尾SKU保持按余量计算的目标量
            if (isEnding) {
                getTargetScheduleQtyResolver().upsizeEndingTargetQty(context, sku);
            }
            ProductionQuantityPolicy quantityPolicy = ProductionQuantityPolicy.from(sku, isEnding);
            sku.setStrictTargetQty(quantityPolicy.isStrictUpperLimit());
            log.info("新增SKU开始排产, materialCode: {}, 结构: {}, 规格: {}, 月计划量: {}, 目标量: {}, "
                            + "day1/day2/day3窗口量: {}, 余量: {}, 胎胚库存: {}, 是否收尾: {}, "
                            + "允许补满已开班次: {}, 严格禁止超排: {}, 非最后机台满排: {}",
                    sku.getMaterialCode(), sku.getStructureName(), sku.getSpecCode(),
                    sku.getMonthPlanQty(), sku.resolveTargetScheduleQty(), sku.getWindowPlanQty(),
                    sku.getSurplusQty(), sku.getEmbryoStock(), isEnding,
                    quantityPolicy.isAllowFillStartedShift(), quantityPolicy.isStrictUpperLimit(),
                    quantityPolicy.isFullRunForNonTailMachine());

            if (shouldSkipTrialSku(context, sku)) {
                addUnscheduledResult(context, sku, "试制量试当日不可排产", unscheduledReasonCountMap);
                iterator.remove();
                continue;
            }

            // 1. 匹配候选机台
            List<MachineScheduleDTO> candidates = machineMatch.matchMachines(context, sku);
            if (candidates.isEmpty()) {
                log.warn("新增SKU无候选机台, materialCode: {}, 结构: {}, 规格: {}, 寸口: {}, 目标量: {}",
                        sku.getMaterialCode(), sku.getStructureName(), sku.getSpecCode(),
                        sku.getProSize(), sku.resolveTargetScheduleQty());
                traceNewSpecMachineDecision(context, sku, candidates, null, null,
                        new HashSet<String>(0), NewSpecFailReasonEnum.MACHINE_SELECTION_FAILED,
                        false, "无可用硫化机台");
                addUnscheduledResult(context, sku, "无可用硫化机台", unscheduledReasonCountMap);
                iterator.remove();
                continue;
            }

            // 1.1 小规模候选机台场景下，局部搜索仅做评估，不再改写当前SKU基础首选机台
            MachineScheduleDTO localSearchSuggestedMachine = selectPreferredMachineByLocalSearch(
                    context, sku, candidates, shifts, machineMatch, mouldChangeBalance, inspectionBalance, capacityCalculate);
            MachineScheduleDTO preferredTrialMachine = resolvePreferredTrialMachine(context, sku, candidates);

            // 2. 基于策略选择最优机台，失败后排除并继续选择下一台。
            // 多机台拆量：当一台机台产能不足以排完目标量时，继续尝试下一台机台。
            boolean scheduled = false;
            NewSpecFailReasonEnum failReason = NewSpecFailReasonEnum.MACHINE_SELECTION_FAILED;
            Set<String> excludedMachineCodes = new HashSet<>(candidates.size());
            Integer originalTargetScheduleQty = sku.getTargetScheduleQty();
            // 初始化多机台拆量剩余量：需求目标保留月计划口径，实际拆机按日计划账本剩余额度收敛。
            int remainingQty = resolveSchedulableRemainingQty(sku);
            int dynamicTargetQty = remainingQty;
            sku.setRemainingScheduleQty(remainingQty);
            MachineScheduleDTO finalMachine = null;
            Date finalProductionStartTime = null;
            // 多机台累计调度结果，用于最终按总量确认排完与否
            int totalScheduledQty = 0;
            while (true) {
                MachineScheduleDTO candidateMachine = selectCandidateMachine(
                        context, sku, candidates, excludedMachineCodes, machineMatch, preferredTrialMachine,
                        quantityPolicy);
                if (candidateMachine == null) {
                    break;
                }
                String machineCode = candidateMachine.getMachineCode();
                if (StringUtils.isEmpty(machineCode)) {
                    log.warn("候选机台编码为空，跳过新增SKU排产, materialCode: {}, 目标量: {}",
                            sku.getMaterialCode(), sku.resolveTargetScheduleQty());
                    failReason = selectHigherPriorityFailReason(
                            failReason, NewSpecFailReasonEnum.MACHINE_SELECTION_FAILED);
                    break;
                }

                // 3. 计算机台可开工时间（考虑机台当前预计完工和能力策略约束）
                Date endingTime = candidateMachine.getEstimatedEndTime() != null
                        ? candidateMachine.getEstimatedEndTime() : resolveDefaultMachineEndTime(context, shifts);
                getMaintenanceScheduleService().tryAttachLongOnlineMaintenance(context, candidateMachine);
                if (isEnding) {
                    getMaintenanceScheduleService().tryAttachMaintenanceAfterFirstEnding(
                            context, candidateMachine, endingTime);
                }
                Date machineReadyTime = capacityCalculate.calculateStartTime(context,
                        machineCode, endingTime);
                boolean maintenanceOverlapSwitch = getMaintenanceScheduleService()
                        .shouldApplyMaintenanceOverlapSwitchRule(context, candidateMachine, endingTime);
                Date switchReadyTime = maintenanceOverlapSwitch
                        ? getMaintenanceScheduleService().resolveMaintenanceEndTime(context, candidateMachine)
                        : machineReadyTime;
                switchReadyTime = resolveSpecifyReservedReadyTime(context, sku, machineCode, switchReadyTime);
                switchReadyTime = ShiftProductionControlUtil.resolveEarliestSwitchStartTime(context, switchReadyTime);

                // 4. 分配换模窗口；模具清洗即便重叠，也不再顺延换模起点。
                Date mouldChangeStartTime = null;
                Date mouldChangeCompleteTime = null;
                Date inspectionTime = null;
                Date productionStartTime = null;
                NewSpecFailReasonEnum switchAllocateFailReason = null;
                int switchDurationHours = maintenanceOverlapSwitch
                        ? LhScheduleTimeUtil.getMaintenanceOverlapSwitchHours(context)
                        : LhScheduleTimeUtil.getMouldChangeTotalHours(context);
                mouldChangeStartTime = allocateGreenTireAwareMouldChange(
                        context, sku, machineCode, switchReadyTime, switchDurationHours, mouldChangeBalance);
                if (mouldChangeStartTime == null) {
                    log.debug("新增SKU换模窗口分配失败, materialCode: {}, 机台: {}, 机台就绪: {}, 目标量: {}",
                            sku.getMaterialCode(), machineCode,
                            LhScheduleTimeUtil.formatDateTime(switchReadyTime), sku.resolveTargetScheduleQty());
                    switchAllocateFailReason = NewSpecFailReasonEnum.MOULD_CHANGE_SHIFT_ALLOCATE_FAILED;
                }
                if (mouldChangeStartTime != null) {
                    mouldChangeCompleteTime = LhScheduleTimeUtil.addHours(mouldChangeStartTime, switchDurationHours);
                    inspectionTime = inspectionBalance.allocateInspection(context, machineCode, mouldChangeCompleteTime);
                    if (inspectionTime == null) {
                        log.debug("新增SKU首检分配失败, materialCode: {}, 机台: {}, 换模开始: {}, 换模完成: {}",
                                sku.getMaterialCode(), machineCode,
                                LhScheduleTimeUtil.formatDateTime(mouldChangeStartTime),
                                LhScheduleTimeUtil.formatDateTime(mouldChangeCompleteTime));
                        rollbackMouldChangeAllocation(context, sku, mouldChangeBalance, mouldChangeStartTime);
                        mouldChangeStartTime = null;
                        switchAllocateFailReason = NewSpecFailReasonEnum.FIRST_INSPECTION_SHIFT_ALLOCATE_FAILED;
                    } else {
                        productionStartTime = maintenanceOverlapSwitch
                                ? LhScheduleTimeUtil.addHours(
                                        inspectionTime, LhScheduleTimeUtil.getFirstInspectionHours(context))
                                : inspectionTime;
                    }
                }
                if (mouldChangeStartTime == null) {
                    excludedMachineCodes.add(machineCode);
                    failReason = selectHigherPriorityFailReason(
                            failReason, switchAllocateFailReason == null
                                    ? NewSpecFailReasonEnum.MOULD_CHANGE_SHIFT_ALLOCATE_FAILED
                                    : switchAllocateFailReason);
                    continue;
                }

                // 6. 基于首检分配时间生成新增规格排产结果，并校验当日是否有有效产能
                // 普通换模沿用"总时长已含首检"的旧口径；
                // 维保重叠时改为"4小时切换 + 1小时首检"的专用口径。
                int machineMouldQty = ShiftCapacityResolverUtil.resolveMachineMouldQty(candidateMachine);
                int runtimeShiftCapacity = ShiftCapacityResolverUtil.resolveRuntimeShiftCapacity(
                        context, candidateMachine, sku.getShiftCapacity());
                Date firstProductionStartTime = ShiftProductionControlUtil.resolveFirstSchedulableStartIgnoringCleaning(
                        context,
                        machineCode,
                        productionStartTime,
                        shifts,
                        runtimeShiftCapacity,
                        sku.getLhTimeSeconds(),
                        machineMouldQty);
                if (firstProductionStartTime == null) {
                    log.debug("新增SKU排程窗口内无可开产时间, materialCode: {}, 机台: {}, 首检时间: {}, 班产: {}, 硫化时间: {}, 模数: {}",
                            sku.getMaterialCode(), machineCode,
                            LhScheduleTimeUtil.formatDateTime(productionStartTime),
                            sku.getShiftCapacity(), sku.getLhTimeSeconds(), machineMouldQty);
                    inspectionBalance.rollbackInspection(context, inspectionTime);
                    rollbackMouldChangeAllocation(context, sku, mouldChangeBalance, mouldChangeStartTime);
                    excludedMachineCodes.add(machineCode);
                    failReason = selectHigherPriorityFailReason(
                            failReason, NewSpecFailReasonEnum.NO_CAPACITY_IN_SCHEDULE_WINDOW);
                    continue;
                }
                int maxQtyToWindowEnd = calculateMaxQtyToWindowEnd(
                        context, candidateMachine, sku, firstProductionStartTime, mouldChangeStartTime,
                        shifts, machineMouldQty, runtimeShiftCapacity, isEnding);
                MachineProductionSegment segment = buildMachineProductionSegment(
                        context, sku, machineCode, mouldChangeStartTime, firstProductionStartTime,
                        maxQtyToWindowEnd, runtimeShiftCapacity);
                MachineScheduleRole role = resolveMachineScheduleRole(quantityPolicy, totalScheduledQty,
                        maxQtyToWindowEnd, dynamicTargetQty);
                segment.setRole(role);
                int machinePlanQty = resolveMachinePlanQty(quantityPolicy, role, dynamicTargetQty,
                        totalScheduledQty, maxQtyToWindowEnd, runtimeShiftCapacity);
                log.info("新增SKU候选机台动态分配, materialCode: {}, 机台: {}, 角色: {}, 最大可排量: {}, "
                                + "累计已排: {}, 窗口目标量: {}, 本机台计划量: {}, 换模班次: {}, 开产班次: {}",
                        sku.getMaterialCode(), machineCode, role, maxQtyToWindowEnd, totalScheduledQty,
                        dynamicTargetQty, machinePlanQty, segment.getChangeoverShiftIndex(),
                        segment.getStartProductionShiftIndex());
                if (machinePlanQty <= 0) {
                    log.debug("新增SKU动态分配后本机台计划量为0, materialCode: {}, 机台: {}, 目标量: {}, 换模开始: {}, 开产时间: {}",
                            sku.getMaterialCode(), machineCode, dynamicTargetQty,
                            LhScheduleTimeUtil.formatDateTime(mouldChangeStartTime),
                            LhScheduleTimeUtil.formatDateTime(firstProductionStartTime));
                    inspectionBalance.rollbackInspection(context, inspectionTime);
                    rollbackMouldChangeAllocation(context, sku, mouldChangeBalance, mouldChangeStartTime);
                    excludedMachineCodes.add(machineCode);
                    failReason = selectHigherPriorityFailReason(
                            failReason, NewSpecFailReasonEnum.NO_CAPACITY_IN_SCHEDULE_WINDOW);
                    continue;
                }
                sku.setTargetScheduleQty(machinePlanQty);
                LhScheduleResult result = buildNewSpecScheduleResult(
                        context, candidateMachine, sku, firstProductionStartTime, mouldChangeStartTime,
                        mouldChangeCompleteTime, shifts, machineMouldQty, isEnding);
                if (result == null || result.getDailyPlanQty() == null || result.getDailyPlanQty() <= 0) {
                    log.debug("新增SKU结果无有效班次计划量, materialCode: {}, 机台: {}, 目标量: {}, 开产时间: {}",
                            sku.getMaterialCode(), machineCode, sku.resolveTargetScheduleQty(),
                            LhScheduleTimeUtil.formatDateTime(firstProductionStartTime));
                    // 无有效产能时回滚首检和换模占用，避免影响后续SKU排产
                    inspectionBalance.rollbackInspection(context, inspectionTime);
                    rollbackMouldChangeAllocation(context, sku, mouldChangeBalance, mouldChangeStartTime);
                    // 候选机台失败时恢复原目标量，避免把本次失败收敛值泄漏到后续候选机台。
                    sku.setTargetScheduleQty(originalTargetScheduleQty);
                    excludedMachineCodes.add(machineCode);
                    failReason = selectHigherPriorityFailReason(
                            failReason, NewSpecFailReasonEnum.NO_CAPACITY_IN_SCHEDULE_WINDOW);
                    continue;
                }

                sku.setMouldQty(machineMouldQty);
                // 7. 先按账本硬约束回裁结果，再落地结果与刷新机台状态，避免窗口总量被结果行放大。
                int machineScheduledQty = applyBlockToDailyQuota(context, sku, result, shifts);
                if (machineScheduledQty <= 0) {
                    inspectionBalance.rollbackInspection(context, inspectionTime);
                    rollbackMouldChangeAllocation(context, sku, mouldChangeBalance, mouldChangeStartTime);
                    sku.setTargetScheduleQty(originalTargetScheduleQty);
                    remainingQty = resolveSchedulableRemainingQty(sku);
                    sku.setRemainingScheduleQty(remainingQty);
                    if (!needMoreMachine(sku)) {
                        break;
                    }
                    excludedMachineCodes.add(machineCode);
                    failReason = selectHigherPriorityFailReason(
                            failReason, NewSpecFailReasonEnum.NO_CAPACITY_IN_SCHEDULE_WINDOW);
                    continue;
                }
                context.getScheduleResultList().add(result);
                context.getScheduleResultSourceSkuMap().put(result, sku);
                updateMachineState(context, candidateMachine, sku, result);
                registerMachineAssignment(context, machineCode, result);
                clearSpecifyReservation(context, machineCode, sku.getMaterialCode());
                scheduledCount++;
                scheduled = true;
                finalMachine = candidateMachine;
                finalProductionStartTime = firstProductionStartTime;
                // 累计本机台实际排产量，递减多机台剩余量
                totalScheduledQty += machineScheduledQty;
                remainingQty = Math.max(0, dynamicTargetQty - totalScheduledQty);
                sku.setRemainingScheduleQty(remainingQty);
                log.debug("新增排产本机台完成, SKU: {}, 机台: {}, 本机台排产量: {}, 累计已排: {}, 剩余: {}, 满班超排: {}, 机台就绪: {}, 换模开始: {}, 换模结束: {}, 首检开始: {}, 开产时间: {}",
                        sku.getMaterialCode(), machineCode, machineScheduledQty, totalScheduledQty, remainingQty,
                        sku.getShiftFillOverQty(),
                        LhScheduleTimeUtil.formatDateTime(switchReadyTime),
                        LhScheduleTimeUtil.formatDateTime(mouldChangeStartTime),
                        LhScheduleTimeUtil.formatDateTime(mouldChangeCompleteTime),
                        LhScheduleTimeUtil.formatDateTime(inspectionTime),
                        LhScheduleTimeUtil.formatDateTime(productionStartTime));
                if (remainingQty <= 0 || !needMoreMachine(sku)) {
                    // 全部排完（总量满足 且 每日额度满足），移出待排队列
                    iterator.remove();
                    if (remainingQty <= 0) {
                        log.info("新增SKU多机台排产全部完成, materialCode: {}, 使用机台数: {}, 总排产量: {}",
                                sku.getMaterialCode(), excludedMachineCodes.size() + 1, totalScheduledQty);
                    } else {
                        log.info("新增SKU日计划额度已满足, materialCode: {}, 使用机台数: {}, 总排产量: {}, "
                                        + "剩余总量: {}, 满班超排: {}",
                                sku.getMaterialCode(), excludedMachineCodes.size() + 1, totalScheduledQty,
                                remainingQty, sku.getShiftFillOverQty());
                    }
                    break;
                }
                // 一台排不完，保留原业务目标量，下一台机台按剩余缺口动态计算本机台计划量
                sku.setTargetScheduleQty(originalTargetScheduleQty);
                excludedMachineCodes.add(machineCode);
                log.info("新增SKU一台机台未排完，继续尝试下一台, materialCode: {}, 本机台: {}, 已排: {}, 剩余: {}",
                        sku.getMaterialCode(), machineCode, totalScheduledQty, remainingQty);
            }

            sku.setTargetScheduleQty(originalTargetScheduleQty);
            if (!scheduled) {
                // 所有候选机台都失败，记录未排产原因并移出待排队列
                log.warn("新增SKU排产失败, materialCode: {}, 结构: {}, 规格: {}, 目标量: {}, 候选机台数: {}, 排除机台: {}, 原因: {}",
                        sku.getMaterialCode(), sku.getStructureName(), sku.getSpecCode(),
                        sku.resolveTargetScheduleQty(), candidates.size(), excludedMachineCodes,
                        failReason.getDescription());
                traceNewSpecMachineDecision(context, sku, candidates, localSearchSuggestedMachine, null,
                        excludedMachineCodes, failReason, false, null);
                addUnscheduledResult(context, sku, failReason.getDescription(), unscheduledReasonCountMap);
                iterator.remove();
                // 多机台尝试但未排部分也记录未排
                if (totalScheduledQty > 0) {
                    log.warn("新增SKU部分成功部分失败, materialCode: {}, 已排: {}, 未排: {}",
                            sku.getMaterialCode(), totalScheduledQty, remainingQty);
                }
            } else {
                // 即使部分成功（remainingQty > 0 但无更多候选机台），也记录
                if (remainingQty > 0 && needMoreMachine(sku)) {
                    log.warn("新增SKU多机台排产未全部完成, materialCode: {}, 已排: {}, 剩余: {}, 满班超排: {}, 候选机台已耗尽",
                            sku.getMaterialCode(), totalScheduledQty, remainingQty, sku.getShiftFillOverQty());
                    // 剩余未排量计入未排结果
                    addUnscheduledResult(context, sku, remainingQty,
                            "多机台产能不足，剩余" + remainingQty + "未排", unscheduledReasonCountMap);
                    iterator.remove();
                } else if (remainingQty > 0) {
                    // 总量上仍有剩余（可能来自欠产传导），但日计划额度已满足，移出待排队列
                    log.info("新增SKU日计划额度已满足但总量仍有剩余, materialCode: {}, 已排: {}, 总量剩余: {}, 满班超排: {}",
                            sku.getMaterialCode(), totalScheduledQty, remainingQty, sku.getShiftFillOverQty());
                    iterator.remove();
                }
                traceNewSpecMachineDecision(context, sku, candidates, localSearchSuggestedMachine, finalMachine,
                        excludedMachineCodes, null, true,
                        PriorityTraceLogHelper.formatDateTime(finalProductionStartTime));
            }
        }
        log.info("新增排产完成, 成功: {}, 未排: {}, 原因分布: {}",
                scheduledCount,
                unscheduledReasonCountMap.values().stream().mapToInt(Integer::intValue).sum(),
                unscheduledReasonCountMap);
    }

    /**
     * 选择优先级更高的失败原因，便于保留最接近真实阻塞点的未排产原因。
     *
     * @param currentReason 当前失败原因
     * @param candidateReason 新候选失败原因
     * @return 优先级更高的失败原因
     */
    private NewSpecFailReasonEnum selectHigherPriorityFailReason(NewSpecFailReasonEnum currentReason,
                                                                 NewSpecFailReasonEnum candidateReason) {
        return candidateReason.getPriority() >= currentReason.getPriority()
                ? candidateReason : currentReason;
    }

    /**
     * 使用局部搜索选择当前SKU的首选机台。
     * <p>若配置关闭、阈值不命中或搜索失败，返回null并自动回退原贪心流程。</p>
     *
     * @param context 排程上下文
     * @param currentSku 当前SKU
     * @param candidates 候选机台
     * @param shifts 排程班次窗口
     * @param machineMatch 机台匹配策略
     * @param mouldChangeBalance 换模均衡策略
     * @param inspectionBalance 首检均衡策略
     * @param capacityCalculate 产能计算策略
     * @return 局部搜索首选机台；无法给出时返回null
     */
    private MachineScheduleDTO selectPreferredMachineByLocalSearch(LhScheduleContext context,
                                                                   SkuScheduleDTO currentSku,
                                                                   List<MachineScheduleDTO> candidates,
                                                                   List<LhShiftConfigVO> shifts,
                                                                   IMachineMatchStrategy machineMatch,
                                                                   IMouldChangeBalanceStrategy mouldChangeBalance,
                                                                   IFirstInspectionBalanceStrategy inspectionBalance,
                                                                   ICapacityCalculateStrategy capacityCalculate) {
        if (!shouldUseLocalSearch(context, candidates)) {
            return null;
        }
        List<SkuScheduleDTO> windowSkuList = buildLocalSearchWindow(context, currentSku);
        if (CollectionUtils.isEmpty(windowSkuList)) {
            return null;
        }
        return localSearchMachineAllocator.selectBestMachine(
                context, windowSkuList, candidates, shifts, machineMatch, mouldChangeBalance, inspectionBalance, capacityCalculate);
    }

    private MachineScheduleDTO selectCandidateMachine(LhScheduleContext context,
                                                      SkuScheduleDTO sku,
                                                      List<MachineScheduleDTO> candidates,
                                                      Set<String> excludedMachineCodes,
                                                      IMachineMatchStrategy machineMatch,
                                                      MachineScheduleDTO preferredTrialMachine,
                                                      ProductionQuantityPolicy quantityPolicy) {
        if (preferredTrialMachine != null
                && !excludedMachineCodes.contains(preferredTrialMachine.getMachineCode())) {
            log.info("新增排产优先尝试试制/小批量预选机台, materialCode: {}, machineCode: {}",
                    sku.getMaterialCode(), preferredTrialMachine.getMachineCode());
            return preferredTrialMachine;
        }
        if (isTrialConstructionStage(sku) && hasSingleControlCandidate(candidates)) {
            MachineScheduleDTO singleControlMachine = selectAvailableSingleControlMachine(candidates, excludedMachineCodes);
            if (singleControlMachine != null) {
                log.info("新增排产试制SKU仅尝试单控机台, materialCode: {}, machineCode: {}",
                        sku.getMaterialCode(), singleControlMachine.getMachineCode());
                return singleControlMachine;
            }
            log.info("新增排产试制SKU单控候选均已排除，不回落普通机台, materialCode: {}",
                    sku.getMaterialCode());
            return null;
        }
        if (quantityPolicy != null && quantityPolicy.isFullRunForNonTailMachine()) {
            return machineMatch.selectBestMachine(context, sku, candidates, excludedMachineCodes);
        }
        MachineScheduleDTO finishRemainingFirstMachine = resolveCanFinishRemainingQtyFirst(
                context, sku, candidates, excludedMachineCodes);
        if (finishRemainingFirstMachine != null) {
            log.info("新增排产优先选择可单机收完剩余量的机台, materialCode: {}, machineCode: {}, remainingQty: {}",
                    sku.getMaterialCode(), finishRemainingFirstMachine.getMachineCode(),
                    Math.max(0, sku.getRemainingScheduleQty()));
            return finishRemainingFirstMachine;
        }
        MachineScheduleDTO tailConcentratedMachine = resolveTailConcentratedSplitMachine(
                context, sku, candidates, excludedMachineCodes);
        if (tailConcentratedMachine != null) {
            log.info("新增排产优先选择可保留尾量集中能力的机台, materialCode: {}, machineCode: {}, remainingQty: {}",
                    sku.getMaterialCode(), tailConcentratedMachine.getMachineCode(),
                    Math.max(0, sku.getRemainingScheduleQty()));
            return tailConcentratedMachine;
        }
        return machineMatch.selectBestMachine(context, sku, candidates, excludedMachineCodes);
    }

    /**
     * 判断是否为试制施工阶段。
     *
     * @param sku 待排SKU
     * @return true-试制阶段
     */
    private boolean isTrialConstructionStage(SkuScheduleDTO sku) {
        return sku != null && StringUtils.equals(ConstructionStageEnum.TRIAL.getCode(), sku.getConstructionStage());
    }

    /**
     * 判断候选机台中是否存在单控机台。
     *
     * @param candidates 候选机台
     * @return true-存在单控机台
     */
    private boolean hasSingleControlCandidate(List<MachineScheduleDTO> candidates) {
        if (CollectionUtils.isEmpty(candidates)) {
            return false;
        }
        for (MachineScheduleDTO candidate : candidates) {
            if (candidate != null && LhSingleControlMachineUtil.isSingleMouldMachine(candidate.getMachineCode())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 选择尚未排除的单控机台。
     *
     * @param candidates 候选机台
     * @param excludedMachineCodes 已排除机台
     * @return 可尝试的单控机台
     */
    private MachineScheduleDTO selectAvailableSingleControlMachine(List<MachineScheduleDTO> candidates,
                                                                   Set<String> excludedMachineCodes) {
        if (CollectionUtils.isEmpty(candidates)) {
            return null;
        }
        for (MachineScheduleDTO candidate : candidates) {
            if (candidate == null || !LhSingleControlMachineUtil.isSingleMouldMachine(candidate.getMachineCode())) {
                continue;
            }
            if (!CollectionUtils.isEmpty(excludedMachineCodes)
                    && excludedMachineCodes.contains(candidate.getMachineCode())) {
                continue;
            }
            return candidate;
        }
        return null;
    }

    /**
     * 计算当前机台从开产班次到窗口结束的物理最大可排量。
     *
     * @param context 排程上下文
     * @param machine 机台
     * @param sku SKU
     * @param firstProductionStartTime 首个可生产时间
     * @param mouldChangeStartTime 换模开始时间
     * @param shifts 排程窗口班次
     * @param mouldQty 模台数
     * @param shiftCapacity 运行态班产
     * @param isEnding 是否收尾
     * @return 最大可排量
     */
    private int calculateMaxQtyToWindowEnd(LhScheduleContext context,
                                           MachineScheduleDTO machine,
                                           SkuScheduleDTO sku,
                                           Date firstProductionStartTime,
                                           Date mouldChangeStartTime,
                                           List<LhShiftConfigVO> shifts,
                                           int mouldQty,
                                           int shiftCapacity,
                                           boolean isEnding) {
        if (context == null || machine == null || sku == null || firstProductionStartTime == null
                || CollectionUtils.isEmpty(shifts)) {
            return 0;
        }
        List<MachineCleaningWindowDTO> cleaningWindowList = resolveEffectiveCleaningWindowList(
                context, machine.getMachineCode(), mouldChangeStartTime, firstProductionStartTime);
        List<MachineMaintenanceWindowDTO> maintenanceWindowList = resolveMachineMaintenanceWindowList(
                context, machine.getMachineCode());
        Date cursorStartTime = firstProductionStartTime;
        int dryIceLossQty = context.getParamIntValue(
                LhScheduleParamConstant.DRY_ICE_LOSS_QTY, LhScheduleConstant.DRY_ICE_LOSS_QTY);
        int dryIceDurationHours = context.getParamIntValue(
                LhScheduleParamConstant.DRY_ICE_DURATION_HOURS, LhScheduleConstant.DRY_ICE_DURATION_HOURS);
        int totalQty = 0;
        boolean started = false;
        for (LhShiftConfigVO shift : shifts) {
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
            int shiftMaxQty = ShiftCapacityResolverUtil.resolveShiftCapacityWithDowntime(
                    context.getDevicePlanShutList(),
                    cleaningWindowList,
                    maintenanceWindowList,
                    machine.getMachineCode(),
                    control.getEffectiveStartTime(),
                    control.getEffectiveEndTime(),
                    shiftCapacity,
                    sku.getLhTimeSeconds(),
                    mouldQty,
                    ShiftCapacityResolverUtil.resolveShiftDurationSeconds(shift),
                    dryIceLossQty,
                    dryIceDurationHours);
            shiftMaxQty = ShiftProductionControlUtil.deductCapacityByControl(control, shiftMaxQty, mouldQty);
            if (shiftMaxQty <= 0) {
                continue;
            }
            totalQty += shiftMaxQty;
            cursorStartTime = control.getEffectiveEndTime();
        }
        return Math.max(0, totalQty);
    }

    /**
     * 构建机台生产段，用于记录角色判断和关键日志。
     *
     * @param context 排程上下文
     * @param sku SKU
     * @param machineCode 机台编码
     * @param mouldChangeStartTime 换模开始时间
     * @param firstProductionStartTime 首个可生产时间
     * @param maxQtyToWindowEnd 最大可排量
     * @param shiftCapacity 运行态班产
     * @return 机台生产段
     */
    private MachineProductionSegment buildMachineProductionSegment(LhScheduleContext context,
                                                                   SkuScheduleDTO sku,
                                                                   String machineCode,
                                                                   Date mouldChangeStartTime,
                                                                   Date firstProductionStartTime,
                                                                   int maxQtyToWindowEnd,
                                                                   int shiftCapacity) {
        MachineProductionSegment segment = new MachineProductionSegment();
        segment.setMachineCode(machineCode);
        segment.setMaterialCode(sku.getMaterialCode());
        segment.setGreenTireGroupKey(sku.getEmbryoCode());
        segment.setNeedChangeover(true);
        segment.setMaxQtyToWindowEnd(maxQtyToWindowEnd);
        segment.setShiftCapacity(shiftCapacity);
        segment.setChangeoverShiftIndex(LhScheduleTimeUtil.getShiftIndex(
                context, context.getScheduleDate(), mouldChangeStartTime));
        segment.setStartProductionShiftIndex(LhScheduleTimeUtil.getShiftIndex(
                context, context.getScheduleDate(), firstProductionStartTime));
        return segment;
    }

    /**
     * 判断当前机台在多机台补量中的角色。
     *
     * @param policy 排产数量策略
     * @param scheduledQty 当前已排量
     * @param maxQtyToWindowEnd 当前机台最大可排量
     * @param targetQty 窗口目标量
     * @return 机台角色
     */
    private MachineScheduleRole resolveMachineScheduleRole(ProductionQuantityPolicy policy,
                                                           int scheduledQty,
                                                           int maxQtyToWindowEnd,
                                                           int targetQty) {
        if (policy != null && policy.isFullRunForNonTailMachine()
                && scheduledQty + maxQtyToWindowEnd < targetQty) {
            return MachineScheduleRole.FULL_RUN_MACHINE;
        }
        return MachineScheduleRole.TAIL_MACHINE;
    }

    /**
     * 根据机台角色计算当前机台计划量。
     *
     * @param policy 排产数量策略
     * @param role 机台角色
     * @param targetQty 窗口目标量
     * @param scheduledQty 当前已排量
     * @param maxQtyToWindowEnd 当前机台最大可排量
     * @param shiftCapacity 运行态班产
     * @return 当前机台计划量
     */
    private int resolveMachinePlanQty(ProductionQuantityPolicy policy,
                                      MachineScheduleRole role,
                                      int targetQty,
                                      int scheduledQty,
                                      int maxQtyToWindowEnd,
                                      int shiftCapacity) {
        if (maxQtyToWindowEnd <= 0) {
            return 0;
        }
        if (MachineScheduleRole.FULL_RUN_MACHINE == role) {
            return maxQtyToWindowEnd;
        }
        int remainingQty = Math.max(0, targetQty - scheduledQty);
        if (remainingQty <= 0) {
            return 0;
        }
        int planQty = policy != null && policy.isAllowFillStartedShift()
                ? roundUpToShiftCapacity(remainingQty, shiftCapacity) : remainingQty;
        return Math.min(planQty, maxQtyToWindowEnd);
    }

    /**
     * 将剩余量向上取整到单班产能，表示最后已开班班次补满。
     *
     * @param qty 剩余目标量
     * @param shiftCapacity 单班产能
     * @return 补满后的计划量
     */
    private int roundUpToShiftCapacity(int qty, int shiftCapacity) {
        if (qty <= 0 || shiftCapacity <= 0) {
            return Math.max(0, qty);
        }
        return ((qty + shiftCapacity - 1) / shiftCapacity) * shiftCapacity;
    }

    /**
     * 优先选择窗口内可单机收完剩余量的候选机台。
     * <p>试制/量试 SKU 存在可用单控机台时，仅考虑单控候选，避免普通机台抢占，
     * 迫使试制 SKU 等待单控机台收尾而非回落普通机台。</p>
     *
     * @param context 排程上下文
     * @param sku SKU
     * @param candidates 候选机台
     * @param excludedMachineCodes 已排除机台
     * @return 可单机收完剩余量的机台；不存在时返回 null
     */
    private MachineScheduleDTO resolveCanFinishRemainingQtyFirst(LhScheduleContext context,
                                                                 SkuScheduleDTO sku,
                                                                 List<MachineScheduleDTO> candidates,
                                                                 Set<String> excludedMachineCodes) {
        if (context == null || sku == null || CollectionUtils.isEmpty(candidates)) {
            return null;
        }
        int remainingQty = sku.getRemainingScheduleQty() > 0
                ? sku.getRemainingScheduleQty()
                : sku.resolveTargetScheduleQty();
        if (remainingQty <= 0) {
            return null;
        }
        // 试制/量试SKU有可用单控机台时，仅考虑单控候选，避免普通机台抢占
        boolean trialStickToSingleControl = false;
        if (shouldPreferTrialMachine(sku)) {
            for (MachineScheduleDTO candidate : candidates) {
                if (candidate == null || StringUtils.isEmpty(candidate.getMachineCode())) {
                    continue;
                }
                if (!CollectionUtils.isEmpty(excludedMachineCodes)
                        && excludedMachineCodes.contains(candidate.getMachineCode())) {
                    continue;
                }
                if (LhSingleControlMachineUtil.isSingleMouldMachine(candidate.getMachineCode())) {
                    trialStickToSingleControl = true;
                    break;
                }
            }
        }
        for (MachineScheduleDTO candidate : candidates) {
            if (candidate == null
                    || StringUtils.isEmpty(candidate.getMachineCode())
                    || (!CollectionUtils.isEmpty(excludedMachineCodes)
                    && excludedMachineCodes.contains(candidate.getMachineCode()))) {
                continue;
            }
            if (trialStickToSingleControl
                    && !LhSingleControlMachineUtil.isSingleMouldMachine(candidate.getMachineCode())) {
                continue;
            }
            int machineCapacity = getTargetScheduleQtyResolver()
                    .calcMachineAvailableCapacityInWindow(context, sku, candidate);
            if (machineCapacity >= remainingQty) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * 当所有候选机台都无法单机收完时，优先选择"先吃小块、把尾量集中留给另一台机台"的候选。
     * <p>仅在剩余尾量能够被其他候选机台单机承接时生效，避免把尾量拆得更碎。</p>
     *
     * @param context 排程上下文
     * @param sku SKU
     * @param candidates 候选机台
     * @param excludedMachineCodes 已排除机台
     * @return 优先机台；不存在时返回 null
     */
    private MachineScheduleDTO resolveTailConcentratedSplitMachine(LhScheduleContext context,
                                                                   SkuScheduleDTO sku,
                                                                   List<MachineScheduleDTO> candidates,
                                                                   Set<String> excludedMachineCodes) {
        if (context == null || sku == null || CollectionUtils.isEmpty(candidates)) {
            return null;
        }
        int remainingQty = sku.getRemainingScheduleQty() > 0
                ? sku.getRemainingScheduleQty()
                : sku.resolveTargetScheduleQty();
        if (remainingQty <= 0) {
            return null;
        }
        Map<MachineScheduleDTO, Integer> machineCapacityMap = new LinkedHashMap<>(candidates.size());
        for (MachineScheduleDTO candidate : candidates) {
            if (candidate == null
                    || StringUtils.isEmpty(candidate.getMachineCode())
                    || (!CollectionUtils.isEmpty(excludedMachineCodes)
                    && excludedMachineCodes.contains(candidate.getMachineCode()))) {
                continue;
            }
            int machineCapacity = getTargetScheduleQtyResolver()
                    .calcMachineAvailableCapacityInWindow(context, sku, candidate);
            if (machineCapacity > 0 && machineCapacity < remainingQty) {
                machineCapacityMap.put(candidate, machineCapacity);
            }
        }
        if (machineCapacityMap.size() < 2) {
            return null;
        }
        MachineScheduleDTO selectedMachine = null;
        int selectedCapacity = Integer.MAX_VALUE;
        for (Map.Entry<MachineScheduleDTO, Integer> entry : machineCapacityMap.entrySet()) {
            int tailQty = remainingQty - entry.getValue();
            int otherMaxCapacity = 0;
            for (Map.Entry<MachineScheduleDTO, Integer> otherEntry : machineCapacityMap.entrySet()) {
                if (otherEntry.getKey() == entry.getKey()) {
                    continue;
                }
                otherMaxCapacity = Math.max(otherMaxCapacity, otherEntry.getValue());
            }
            if (otherMaxCapacity < tailQty) {
                continue;
            }
            if (entry.getValue() < selectedCapacity) {
                selectedMachine = entry.getKey();
                selectedCapacity = entry.getValue();
            }
        }
        return selectedMachine;
    }

    private MachineScheduleDTO resolvePreferredTrialMachine(LhScheduleContext context,
                                                            SkuScheduleDTO sku,
                                                            List<MachineScheduleDTO> candidates) {
        if (sku == null || CollectionUtils.isEmpty(candidates)) {
            return null;
        }
        if (!shouldPreferTrialMachine(sku)) {
            return null;
        }
        String preferredMachineCode = getTrialProductionStrategy().matchTrialMachine(context, sku);
        if (StringUtils.isEmpty(preferredMachineCode)) {
            return null;
        }
        for (MachineScheduleDTO candidate : candidates) {
            if (candidate != null && StringUtils.equals(preferredMachineCode, candidate.getMachineCode())) {
                return candidate;
            }
        }
        return null;
    }

    private boolean shouldPreferTrialMachine(SkuScheduleDTO sku) {
        if (sku == null) {
            return false;
        }
        if (sku.isTrial() || sku.isSmallBatchValidation()) {
            return true;
        }
        return StringUtils.equals(ConstructionStageEnum.TRIAL.getCode(), sku.getConstructionStage())
                || StringUtils.equals(ConstructionStageEnum.MASS_TRIAL.getCode(), sku.getConstructionStage());
    }

    /**
     * 判断 SKU 是否属于试制/量试。
     *
     * @param sku SKU
     * @return true-试制或量试
     */
    private boolean isTrialOrMassTrialSku(SkuScheduleDTO sku) {
        if (sku == null) {
            return false;
        }
        return StringUtils.equals(ConstructionStageEnum.TRIAL.getCode(), sku.getConstructionStage())
                || StringUtils.equals(ConstructionStageEnum.MASS_TRIAL.getCode(), sku.getConstructionStage())
                || sku.isTrial();
    }

    /**
     * 判断是否启用局部搜索。
     *
     * @param context 排程上下文
     * @param candidates 候选机台列表
     * @return true-启用，false-不启用
     */
    private boolean shouldUseLocalSearch(LhScheduleContext context, List<MachineScheduleDTO> candidates) {
        if (CollectionUtils.isEmpty(candidates)) {
            return false;
        }
        LhScheduleConfig scheduleConfig = context.getScheduleConfig();
        if (scheduleConfig == null || !scheduleConfig.isLocalSearchEnabled()) {
            return false;
        }
        return candidates.size() < scheduleConfig.getLocalSearchMachineThreshold();
    }


    /**
     * 输出新增排产机台决策日志（含SKU基本信息和最终选中原因）。
     *
     * @param context 排程上下文
     * @param sku 待排SKU
     * @param candidates 候选机台列表
     * @param localSearchSuggestedMachine 局部搜索评估机台
     * @param finalMachine 最终选中机台
     * @param excludedMachineCodes 已排除机台编码
     * @param failReason 失败原因
     * @param success 是否成功
     * @param startTimeText 开产时间文本或附加说明
     */
    private void traceNewSpecMachineDecision(LhScheduleContext context, SkuScheduleDTO sku,
                                             List<MachineScheduleDTO> candidates,
                                             MachineScheduleDTO localSearchSuggestedMachine,
                                             MachineScheduleDTO finalMachine,
                                             Set<String> excludedMachineCodes,
                                             NewSpecFailReasonEnum failReason,
                                             boolean success,
                                             String startTimeText) {
        if (!PriorityTraceLogHelper.isEnabled(context)) {
            return;
        }
        String title = "SKU选机台TOP5候选列表";
        StringBuilder detailBuilder = new StringBuilder(1024);
        PriorityTraceLogHelper.appendTitleHeader(detailBuilder, title);

        // SKU基本信息
        String skuType = resolveNewSpecSkuType(sku);
        boolean isEnding = endingJudgmentStrategy.isEnding(context, sku);
        PriorityTraceLogHelper.appendLine(detailBuilder,
                PriorityTraceLogHelper.kv("排程日期", PriorityTraceLogHelper.formatDateTime(context.getScheduleDate()))
                        + ", " + PriorityTraceLogHelper.kv("SKU", sku.getMaterialCode())
                        + ", " + PriorityTraceLogHelper.kv("描述", sku.getMaterialDesc())
                        + ", " + PriorityTraceLogHelper.kv("待排产量", sku.resolveTargetScheduleQty())
                        + ", " + PriorityTraceLogHelper.kv("SKU类型", skuType)
                        + ", " + PriorityTraceLogHelper.kv("是否收尾", PriorityTraceLogHelper.yesNo(isEnding))
                        + ", " + PriorityTraceLogHelper.kv("规格", sku.getSpecCode())
                        + ", " + PriorityTraceLogHelper.kv("候选机台总数", PriorityTraceLogHelper.sizeOf(candidates))
                        + ", " + PriorityTraceLogHelper.kv("有效候选数", PriorityTraceLogHelper.sizeOf(candidates))
                        + ", " + PriorityTraceLogHelper.kv("已排除机台", CollectionUtils.isEmpty(excludedMachineCodes)
                        ? "-" : String.join(",", excludedMachineCodes)));

        // TOP5 候选机台
        int topN = LhScheduleConstant.SKU_MACHINE_CANDIDATE_TOP_N;
        int outputCount = Math.min(topN, PriorityTraceLogHelper.sizeOf(candidates));
        if (outputCount > 0) {
            PriorityTraceLogHelper.appendLine(detailBuilder, "TOP" + outputCount + "候选排序:");
            for (int i = 0; i < outputCount; i++) {
                MachineScheduleDTO machine = candidates.get(i);
                boolean isSingleCtrl = LhSingleControlMachineUtil.isSingleMouldMachine(machine.getMachineCode());
                String reasonSuffix = (i == 0 && success && finalMachine != null
                        && StringUtils.equals(machine.getMachineCode(), finalMachine.getMachineCode()))
                        ? "最优候选" : ("候选" + (i + 1));
                PriorityTraceLogHelper.appendLine(detailBuilder,
                        (i + 1)
                                + ". " + PriorityTraceLogHelper.kv("机台", machine.getMachineCode())
                                + ", " + PriorityTraceLogHelper.kv("名称", machine.getMachineName())
                                + ", " + PriorityTraceLogHelper.kv("单控", PriorityTraceLogHelper.yesNo(isSingleCtrl))
                                + ", " + PriorityTraceLogHelper.kv("收尾时间", PriorityTraceLogHelper.formatDateTime(machine.getEstimatedEndTime()))
                                + ", " + PriorityTraceLogHelper.kv("当前在机", machine.getPreviousMaterialCode())
                                + ", " + PriorityTraceLogHelper.kv("前规格", machine.getPreviousSpecCode())
                                + ", " + PriorityTraceLogHelper.kv("机台顺序", machine.getMachineOrder())
                                + ", " + PriorityTraceLogHelper.kv("原因", reasonSuffix));
            }
            if (PriorityTraceLogHelper.sizeOf(candidates) > topN) {
                PriorityTraceLogHelper.appendLine(detailBuilder,
                        "... 共" + PriorityTraceLogHelper.sizeOf(candidates) + "台，仅展示前" + topN + "台");
            }
        }

        // 局部搜索评估
        if (localSearchSuggestedMachine != null) {
            PriorityTraceLogHelper.appendLine(detailBuilder,
                    "局部搜索评估机台: " + localSearchSuggestedMachine.getMachineCode());
        }

        // 最终选中
        String selectReason = resolveNewSpecMachineSelectReason(context, sku, candidates, finalMachine,
                localSearchSuggestedMachine, excludedMachineCodes);
        PriorityTraceLogHelper.appendLine(detailBuilder,
                PriorityTraceLogHelper.kv("最终选中机台", finalMachine == null ? "-" : finalMachine.getMachineCode())
                        + ", " + PriorityTraceLogHelper.kv("选中原因", selectReason));
        if (success) {
            PriorityTraceLogHelper.appendLine(detailBuilder,
                    "决策结果: 成功, 开产时间=" + PriorityTraceLogHelper.safeText(startTimeText));
        } else {
            PriorityTraceLogHelper.appendLine(detailBuilder,
                    "决策结果: 失败, 原因=" + PriorityTraceLogHelper.safeText(
                            failReason == null ? null : failReason.getDescription())
                            + ", 备注=" + PriorityTraceLogHelper.safeText(startTimeText));
        }
        PriorityTraceLogHelper.appendTitleFooter(detailBuilder);
        String detail = detailBuilder.toString().trim();
        PriorityTraceLogHelper.logSortSummary(log, context, title, detail);
    }

    /**
     * 解析新增排产SKU类型描述。
     *
     * @param sku SKU
     * @return 类型描述
     */
    private static String resolveNewSpecSkuType(SkuScheduleDTO sku) {
        if (sku == null) {
            return "-";
        }
        if (ConstructionStageEnum.TRIAL.getCode().equals(sku.getConstructionStage())) {
            return "试制";
        }
        if (sku.isSmallBatchValidation()) {
            return "小批量";
        }
        if (ConstructionStageEnum.MASS_TRIAL.getCode().equals(sku.getConstructionStage())) {
            return "量试";
        }
        if (ConstructionStageEnum.FORMAL.getCode().equals(sku.getConstructionStage())) {
            return "正式";
        }
        return sku.getConstructionStage() != null ? sku.getConstructionStage() : "-";
    }

    /**
     * 解析新增排产选机台最终选中原因。
     *
     * @param context 排程上下文
     * @param sku SKU
     * @param candidates 候选机台列表
     * @param finalMachine 最终选中机台
     * @param localSearchSuggestedMachine 局部搜索评估机台
     * @param excludedMachineCodes 已排除机台编码
     * @return 选中原因
     */
    private static String resolveNewSpecMachineSelectReason(LhScheduleContext context, SkuScheduleDTO sku,
                                                             List<MachineScheduleDTO> candidates,
                                                             MachineScheduleDTO finalMachine,
                                                             MachineScheduleDTO localSearchSuggestedMachine,
                                                             Set<String> excludedMachineCodes) {
        if (finalMachine == null) {
            if (!CollectionUtils.isEmpty(candidates) && !CollectionUtils.isEmpty(excludedMachineCodes)) {
                return "候选机台全部被排除: " + String.join(",", excludedMachineCodes);
            }
            if (CollectionUtils.isEmpty(candidates)) {
                return "无可用候选机台";
            }
            return "机台选择失败";
        }
        List<String> reasons = new ArrayList<>(4);
        // 局部搜索评估命中
        if (localSearchSuggestedMachine != null
                && StringUtils.equals(finalMachine.getMachineCode(), localSearchSuggestedMachine.getMachineCode())) {
            reasons.add("局部搜索评估优");
        }
        // 候选排序首位
        if (!CollectionUtils.isEmpty(candidates)) {
            MachineScheduleDTO first = candidates.get(0);
            if (StringUtils.equals(finalMachine.getMachineCode(), first.getMachineCode())) {
                reasons.add("候选排序首位");
            }
        }
        // 收尾时间最接近
        if (finalMachine.getEstimatedEndTime() != null) {
            reasons.add("收尾时间最近");
        }
        // 排除后候选
        if (!CollectionUtils.isEmpty(excludedMachineCodes)) {
            reasons.add("排除" + excludedMachineCodes.size() + "台后选取");
        }
        if (reasons.isEmpty()) {
            reasons.add("排序兜底");
        }
        return String.join("，", reasons);
    }

    /**
     * 构建局部搜索窗口（当前SKU + 后续若干SKU）。
     *
     * @param context 排程上下文
     * @param currentSku 当前SKU
     * @return 局部搜索SKU窗口
     */
    private List<SkuScheduleDTO> buildLocalSearchWindow(LhScheduleContext context, SkuScheduleDTO currentSku) {
        List<SkuScheduleDTO> allNewSkuList = context.getNewSpecSkuList();
        int skuIndex = allNewSkuList.indexOf(currentSku);
        if (skuIndex < 0) {
            List<SkuScheduleDTO> fallbackList = new ArrayList<>(1);
            fallbackList.add(currentSku);
            return fallbackList;
        }
        int depth = context.getScheduleConfig() != null ? context.getScheduleConfig().getLocalSearchDepth() : 1;
        int endIndex = Math.min(allNewSkuList.size(), skuIndex + depth);
        return new ArrayList<>(allNewSkuList.subList(skuIndex, endIndex));
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 构建新增规格排程结果，并按班次分配计划量
     */
    private LhScheduleResult buildNewSpecScheduleResult(LhScheduleContext context,
                                                         MachineScheduleDTO machine,
                                                         SkuScheduleDTO sku,
                                                         Date startTime,
                                                         Date mouldChangeStartTime,
                                                         Date mouldChangeEndTime,
                                                         List<LhShiftConfigVO> shifts,
                                                         int mouldQty,
                                                         boolean isEnding) {
        LhScheduleResult result = new LhScheduleResult();
        result.setFactoryCode(context.getFactoryCode());
        result.setBatchNo(context.getBatchNo());
        result.setOrderNo(generateOrderNo(context));
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
        result.setScheduleType(NEW_SPEC_SCHEDULE_TYPE);
        result.setIsChangeMould("1");
        result.setIsTypeBlock("0");
        result.setConstructionStage(sku.getConstructionStage());
        // 设置产品状态（取自SKU与示方书关系的硫化示方书类型）
        MdmSkuConstructionRef constructionRef = context.getSkuConstructionRefMap().get(sku.getMaterialCode());
        result.setTrialStatus(constructionRef != null ? constructionRef.getLhType() : null);
        result.setEmbryoNo(sku.getEmbryoNo());
        result.setTextNo(sku.getTextNo());
        result.setLhNo(sku.getLhNo());
        result.setMonthPlanVersion(sku.getMonthPlanVersion());
        result.setProductionVersion(sku.getProductionVersion());
        result.setIsTrial(sku.isTrial() ? "1" : "0");
        result.setMachineOrder(machine.getMachineOrder());
        result.setRealScheduleDate(context.getScheduleDate());
        result.setProductionStatus("0");
        result.setMouldCode(resolveMouldCode(context, sku.getMaterialCode()));
        result.setHasSpecialMaterial(LhSpecialMaterialUtil.resolveHasSpecialMaterial(context, sku));
        // 保存真实换模开始时间，供下游换模计划表直接复用。
        result.setMouldChangeStartTime(mouldChangeStartTime);

        // 按班次分配计划量
        int pendingQty = sku.resolveTargetScheduleQty();
        List<MachineCleaningWindowDTO> cleaningWindowList = resolveEffectiveCleaningWindowList(
                context, result.getLhMachineCode(), mouldChangeStartTime, startTime);
        List<MachineMaintenanceWindowDTO> maintenanceWindowList = resolveMachineMaintenanceWindowList(
                context, result.getLhMachineCode());
        distributeToShifts(context, result, shifts, startTime,
                runtimeShiftCapacity, sku.getLhTimeSeconds(), mouldQty, pendingQty, cleaningWindowList,
                maintenanceWindowList, sku, isEnding);
        refreshResultSummary(context, result);
        applyCleaningMouldChangeAnalysis(context, result);
        return result;
    }

    /**
     * 命中"模具清洗+换模"组合场景时，写入首个排产班次原因分析。
     *
     * @param context 排程上下文
     * @param result 新增换模结果
     */
    private void applyCleaningMouldChangeAnalysis(LhScheduleContext context,
                                                  LhScheduleResult result) {
        Date firstPlannedShiftStartTime = resolveFirstPlannedShiftStartTime(result);
        if (context == null
                || result == null
                || result.getMouldChangeStartTime() == null
                || firstPlannedShiftStartTime == null) {
            return;
        }
        int firstPlannedShiftIndex = resolveFirstPlannedShiftIndex(result);
        if (firstPlannedShiftIndex <= 0) {
            return;
        }
        MachineScheduleDTO machine = context.getMachineScheduleMap().get(result.getLhMachineCode());
        if (machine == null
                || !MachineCleaningOverlapUtil.hasBlockingOverlap(
                machine.getCleaningWindowList(), result.getMouldChangeStartTime(), firstPlannedShiftStartTime)) {
            return;
        }
        ShiftFieldUtil.setShiftAnalysis(result, firstPlannedShiftIndex, NEW_SPEC_CLEANING_ANALYSIS);
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
     * @return 班次开始时间；未找到返回 null
     */
    private Date resolveFirstPlannedShiftStartTime(LhScheduleResult result) {
        int firstPlannedShiftIndex = resolveFirstPlannedShiftIndex(result);
        return firstPlannedShiftIndex > 0
                ? ShiftFieldUtil.getShiftStartTime(result, firstPlannedShiftIndex) : null;
    }

    /**
     * 基于最终计划量复核新增结果收尾标记。
     * <p>口径：仅新增结果生效；按物料编码汇总多机台排产量后，汇总计划量 >= max(硫化余量, 胎胚库存)时记为收尾。</p>
     * <p>多机台场景下，同一SKU在多台机台上的结果共享同一个收尾标记。</p>
     *
     * @param context 排程上下文
     */
    private void refreshNewSpecEndingFlagByResult(LhScheduleContext context) {
        if (context == null || CollectionUtils.isEmpty(context.getScheduleResultList())) {
            return;
        }
        // 按物料编码汇总新增结果的总计划量（支持多机台同SKU排产）
        Map<String, Integer> materialTotalPlanQtyMap = new LinkedHashMap<>(16);
        Map<String, Integer> materialEndingDemandQtyMap = new LinkedHashMap<>(16);
        for (LhScheduleResult result : context.getScheduleResultList()) {
            if (result == null || !NEW_SPEC_SCHEDULE_TYPE.equals(result.getScheduleType())) {
                continue;
            }
            String materialCode = result.getMaterialCode();
            if (StringUtils.isEmpty(materialCode)) {
                continue;
            }
            int planQty = ShiftFieldUtil.resolveScheduledQty(result);
            materialTotalPlanQtyMap.merge(materialCode, planQty, Integer::sum);
            if (!materialEndingDemandQtyMap.containsKey(materialCode)) {
                materialEndingDemandQtyMap.put(materialCode, resolveEndingDemandQty(context, result));
            }
        }
        // 基于汇总计划量统一设置同物料所有结果的收尾标记
        for (LhScheduleResult result : context.getScheduleResultList()) {
            if (result == null || !NEW_SPEC_SCHEDULE_TYPE.equals(result.getScheduleType())
                    || StringUtils.isEmpty(result.getMaterialCode())) {
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
     * 将计划量分配到各班次（从开产时间开始）
     * <p>试制非收尾SKU会根据日计划额度限制每个班次的排产量</p>
     *
     * @param sku    SKU排程DTO（用于获取日计划额度账本和目标量控制标记）
     * @param isEnding 是否收尾
     * @return 未排产的剩余量
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
                                   List<MachineMaintenanceWindowDTO> maintenanceWindowList,
                                   SkuScheduleDTO sku,
                                   boolean isEnding) {
        if (lhTimeSeconds <= 0 || mouldQty <= 0 || remaining <= 0 || startTime == null) {
            return remaining;
        }
        Map<Integer, ShiftRuntimeState> stateMap = context.getShiftRuntimeStateMap();
        int dryIceLossQty = context.getParamIntValue(
                LhScheduleParamConstant.DRY_ICE_LOSS_QTY, LhScheduleConstant.DRY_ICE_LOSS_QTY);
        int dryIceDurationHours = context.getParamIntValue(
                LhScheduleParamConstant.DRY_ICE_DURATION_HOURS, LhScheduleConstant.DRY_ICE_DURATION_HOURS);

        // 试制非收尾SKU在本轮分配内按日期追踪已消费日计划额度，防止同一天多个班次重复消费
        Map<LocalDate, Integer> trialDailyConsumedMap = null;
        if (sku != null && sku.isStrictTargetQty() && !isEnding) {
            Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap = sku.getDailyPlanQuotaMap();
            if (quotaMap != null && !quotaMap.isEmpty()) {
                trialDailyConsumedMap = new HashMap<>(4);
            }
        }

        boolean started = false;
        for (LhShiftConfigVO shift : shifts) {
            if (remaining <= 0) {
                break;
            }
            if (!started) {
                if (startTime.before(shift.getShiftEndDateTime())) {
                    started = true;
                } else {
                    continue;
                }
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

            // 试制非收尾SKU严格按照日计划额度限制班次可排量上限，不允许超出当日计划量补满班次
            if (trialDailyConsumedMap != null) {
                int dailyQuotaCap = resolveDailyQuotaCap(sku, shift.getWorkDate(), mouldQty, trialDailyConsumedMap);
                if (dailyQuotaCap >= 0) {
                    shiftMaxQty = Math.min(shiftMaxQty, dailyQuotaCap);
                }
                if (shiftMaxQty <= 0) {
                    continue;
                }
            }

            int shiftQty = ShiftCapacityResolverUtil.normalizeAllocatedShiftQty(
                    Math.min(remaining, shiftMaxQty), shiftMaxQty, mouldQty);
            if (shiftQty > 0) {
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

                // 更新本轮分配内该日已消费的日计划额度
                if (trialDailyConsumedMap != null && shift.getWorkDate() != null) {
                    LocalDate productionDate = shift.getWorkDate().toInstant()
                            .atZone(ZoneId.systemDefault()).toLocalDate();
                    trialDailyConsumedMap.merge(productionDate, shiftQty, Integer::sum);
                }

                startTime = effectiveEnd;

                if (!CollectionUtils.isEmpty(stateMap)) {
                    ShiftRuntimeState st = stateMap.get(shift.getShiftIndex());
                    if (st != null) {
                        st.setRemainingCapacity(Math.max(0, shiftMaxQty - shiftQty));
                    }
                }
            }
        }
        return remaining;
    }

    /**
     * 解析试制非收尾SKU在某工作日的日计划额度上限。
     * <p>从SKU的日计划额度账本中读取该日期的剩余额度，并扣除本轮已消费量，
     * 防止同一天多个班次重复消费。多模场景下按模台数对齐。</p>
     *
     * @param sku                  SKU排程DTO
     * @param workDate             班次归属工作日
     * @param mouldQty             模台数
     * @param trialDailyConsumedMap 本轮分配内按日期已消费量追踪
     * @return 日计划额度上限，-1表示无需限制
     */
    private int resolveDailyQuotaCap(SkuScheduleDTO sku, Date workDate, int mouldQty,
                                      Map<LocalDate, Integer> trialDailyConsumedMap) {
        if (workDate == null) {
            return -1;
        }
        Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap = sku.getDailyPlanQuotaMap();
        if (quotaMap == null || quotaMap.isEmpty()) {
            return -1;
        }
        LocalDate productionDate = workDate.toInstant()
                .atZone(ZoneId.systemDefault()).toLocalDate();
        SkuDailyPlanQuotaDTO quota = quotaMap.get(productionDate);
        if (quota == null) {
            // 该日期不在月计划范围内，不允许排产
            return 0;
        }
        int dailyRemaining = Math.max(0, quota.getRemainingQty());
        // 扣除本轮分配中该日期已消费的额度
        if (trialDailyConsumedMap != null) {
            Integer consumed = trialDailyConsumedMap.get(productionDate);
            if (consumed != null) {
                dailyRemaining = Math.max(0, dailyRemaining - consumed);
            }
        }
        if (dailyRemaining <= 0) {
            return 0;
        }
        // 多模场景下按模台数对齐，确保分配量可被机台实际生产
        int resolvedMouldQty = ShiftCapacityResolverUtil.resolveMachineMouldQty(mouldQty);
        if (resolvedMouldQty > 1) {
            dailyRemaining = (dailyRemaining / resolvedMouldQty) * resolvedMouldQty;
        }
        return Math.max(dailyRemaining, 0);
    }

    private void setShiftPlanQty(LhScheduleResult result, int shiftIndex, int qty, Date startTime, Date endTime) {
        ShiftFieldUtil.setShiftPlanQty(result, shiftIndex, qty, startTime, endTime);
    }

    private int calcTotalPlanQty(LhScheduleResult result, List<LhShiftConfigVO> shifts) {
        int total = 0;
        for (LhShiftConfigVO s : shifts) {
            Integer q = ShiftFieldUtil.getShiftPlanQty(result, s.getShiftIndex());
            total += (q != null ? q : 0);
        }
        return total;
    }

    /**
     * 刷新结果行的汇总计划量和规格结束时间。
     *
     * @param context 排程上下文
     * @param result 排程结果
     */
    private void refreshResultSummary(LhScheduleContext context, LhScheduleResult result) {
        if (result == null) {
            return;
        }
        ShiftFieldUtil.syncDailyPlanQty(result);
        List<LhShiftConfigVO> shifts = context.getScheduleWindowShifts();
        if (CollectionUtils.isEmpty(shifts)) {
            shifts = LhScheduleTimeUtil.getScheduleShifts(context, context.getScheduleDate());
        }
        int lhTimeSeconds = result.getLhTime() != null ? result.getLhTime() : 0;
        int mouldQty = ShiftCapacityResolverUtil.resolveMachineMouldQty(
                result.getMouldQty() != null ? result.getMouldQty() : 0);
        Date specEndTime = calcSpecEndTime(context, result, shifts, lhTimeSeconds, mouldQty);
        result.setSpecEndTime(specEndTime);
        result.setTdaySpecEndTime(specEndTime);
    }

    /**
     * 新增排产库存裁剪后，将零计划结果移出排程结果并转为未排。
     *
     * @param context 排程上下文
     */
    private void finalizeZeroPlanNewSpecResults(LhScheduleContext context) {
        if (context == null || CollectionUtils.isEmpty(context.getScheduleResultList())) {
            return;
        }
        Map<String, Integer> zeroPlanQtyMap = new LinkedHashMap<>(8);
        List<LhScheduleResult> zeroPlanResults = new ArrayList<>(8);
        for (LhScheduleResult result : context.getScheduleResultList()) {
            // 排除换活字块（换活字块不需要零计划量裁剪）
            if (!NEW_SPEC_SCHEDULE_TYPE.equals(result.getScheduleType())
                    || "1".equals(result.getIsTypeBlock())) {
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

    private Date calcSpecEndTime(LhScheduleContext context,
                                 LhScheduleResult result,
                                 List<LhShiftConfigVO> shifts,
                                 int lhTimeSeconds,
                                 int mouldQty) {
        if (lhTimeSeconds <= 0 || mouldQty <= 0) {
            return null;
        }
        for (int i = shifts.size() - 1; i >= 0; i--) {
            LhShiftConfigVO shift = shifts.get(i);
            Integer planQty = ShiftFieldUtil.getShiftPlanQty(result, shift.getShiftIndex());
            if (planQty == null || planQty <= 0) {
                continue;
            }
            Date shiftEnd = ShiftFieldUtil.getShiftEndTime(result, shift.getShiftIndex());
            if (shiftEnd != null) {
                return shiftEnd;
            }
            Date shiftStart = ShiftFieldUtil.getShiftStartTime(result, shift.getShiftIndex());
            if (shiftStart == null) {
                return shift.getShiftEndDateTime();
            }
            long secondsNeeded = (long) Math.ceil((double) planQty / mouldQty) * lhTimeSeconds;
            List<MachineCleaningWindowDTO> cleaningWindowList = resolveEffectiveCleaningWindowList(
                    context, result.getLhMachineCode(), result.getMouldChangeStartTime(), resolveFirstPlannedShiftStartTime(result));
            return ShiftCapacityResolverUtil.resolveCompletionTimeWithDowntimes(
                    context.getDevicePlanShutList(),
                    cleaningWindowList,
                    resolveMachineMaintenanceWindowList(context, result.getLhMachineCode()),
                    result.getLhMachineCode(),
                    shiftStart,
                    secondsNeeded);
        }
        return null;
    }

    /**
     * 机台缺失预计完工时刻时，回退到排程窗口基准时间，避免依赖系统当前时刻导致排程漂移。
     *
     * @param context 排程上下文
     * @param shifts 排程班次窗口
     * @return 默认机台结束时间
     */
    private Date resolveDefaultMachineEndTime(LhScheduleContext context, List<LhShiftConfigVO> shifts) {
        if (!CollectionUtils.isEmpty(shifts) && shifts.get(0).getShiftStartDateTime() != null) {
            return shifts.get(0).getShiftStartDateTime();
        }
        if (context != null && context.getScheduleDate() != null) {
            return context.getScheduleDate();
        }
        return new Date();
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
     * 解析新增换模结果在排产阶段需要生效的清洗窗口。
     *
     * @param context 排程上下文
     * @param machineCode 机台编号
     * @param switchStartTime 换模开始时间
     * @param firstProductionStartTime 首个可排产开始时间
     * @return 有效清洗窗口列表
     */
    private List<MachineCleaningWindowDTO> resolveEffectiveCleaningWindowList(LhScheduleContext context,
                                                                              String machineCode,
                                                                              Date switchStartTime,
                                                                              Date firstProductionStartTime) {
        List<MachineCleaningWindowDTO> cleaningWindowList = resolveMachineCleaningWindowList(context, machineCode);
        return new ArrayList<>(MachineCleaningOverlapUtil.excludeOverlapWindows(
                cleaningWindowList, switchStartTime, firstProductionStartTime));
    }

    private String resolveMouldCode(LhScheduleContext context, String materialCode) {
        if (!context.getSkuMouldRelMap().containsKey(materialCode)) {
            return null;
        }
        return context.getSkuMouldRelMap().get(materialCode).stream()
                .map(rel -> rel.getMouldCode())
                .filter(code -> code != null && !code.trim().isEmpty())
                .distinct()
                .collect(Collectors.joining(","));
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

    private LhMaintenanceScheduleService getMaintenanceScheduleService() {
        return maintenanceScheduleService != null
                ? maintenanceScheduleService
                : new LhMaintenanceScheduleService();
    }

    private ITrialProductionStrategy getTrialProductionStrategy() {
        return trialProductionStrategy != null
                ? trialProductionStrategy
                : new DefaultTrialProductionStrategy();
    }

    /**
     * 判断试制量试SKU当日是否跳过。
     *
     * @param context 排程上下文
     * @param sku 新增SKU
     * @return true-跳过排产
     */
    private boolean shouldSkipTrialSku(LhScheduleContext context, SkuScheduleDTO sku) {
        if (sku == null || !isTrialOrMassTrialSku(sku)) {
            return false;
        }
        ITrialProductionStrategy strategy = getTrialProductionStrategy();
        Date firstBlockedDate = null;
        boolean hasSchedulableBusinessDay = false;
        Set<String> checkedDateSet = new HashSet<>(8);
        for (LhShiftConfigVO shift : LhScheduleTimeUtil.getScheduleShifts(context, context.getScheduleDate())) {
            if (shift == null || shift.getWorkDate() == null) {
                continue;
            }
            String workDateKey = LhScheduleTimeUtil.formatDate(shift.getWorkDate());
            if (!checkedDateSet.add(workDateKey)) {
                continue;
            }
            Date workDate = shift.getWorkDate();
            if (!strategy.canScheduleTrialSkuOnDate(context, sku, workDate)) {
                if (firstBlockedDate == null) {
                    firstBlockedDate = workDate;
                }
                continue;
            }
            if (strategy.isDailyTrialLimitReached(context, workDate, sku.getMaterialCode())) {
                continue;
            }
            hasSchedulableBusinessDay = true;
            break;
        }
        if (!hasSchedulableBusinessDay) {
            Date logDate = firstBlockedDate != null ? firstBlockedDate
                    : (context.getScheduleDate() != null ? context.getScheduleDate() : context.getScheduleTargetDate());
            log.info("试制量试SKU排程窗口内无可排业务日, materialCode: {}, 日期: {}",
                    sku.getMaterialCode(), LhScheduleTimeUtil.formatDate(logDate));
            return true;
        }
        return false;
    }

    /**
     * 分配同胎胚错峰后的换模时间。
     *
     * @param context 排程上下文
     * @param sku SKU
     * @param machineCode 机台编码
     * @param switchReadyTime 机台可换模时间
     * @param switchDurationHours 换模时长
     * @param mouldChangeBalance 换模均衡策略
     * @return 换模开始时间；无法分配时返回 null
     */
    private Date allocateGreenTireAwareMouldChange(LhScheduleContext context,
                                                   SkuScheduleDTO sku,
                                                   String machineCode,
                                                   Date switchReadyTime,
                                                   int switchDurationHours,
                                                   IMouldChangeBalanceStrategy mouldChangeBalance) {
        if (sku == null || StringUtils.isEmpty(sku.getEmbryoCode())) {
            if (sku != null) {
                log.debug("SKU胎胚编码为空，跳过同胎胚换模错开判断, materialCode: {}, machineCode: {}",
                        sku.getMaterialCode(), machineCode);
            }
            return mouldChangeBalance.allocateMouldChange(context, machineCode, switchReadyTime, switchDurationHours);
        }
        // 先把已有结果和滚动继承结果里的同胎胚换模班次回填到占用表，避免新增规格只感知本轮登记的占用。
        preloadGreenTireChangeoverOccupancy(context);
        Date cursorTime = switchReadyTime;
        for (int attempt = 0; attempt < LhScheduleConstant.MAX_SHIFT_SLOT_COUNT * 2; attempt++) {
            Date allocatedTime = mouldChangeBalance.allocateMouldChange(
                    context, machineCode, cursorTime, switchDurationHours);
            if (allocatedTime == null) {
                return null;
            }
            int shiftIndex = LhScheduleTimeUtil.getShiftIndex(context, context.getScheduleDate(), allocatedTime);
            if (!hasGreenTireChangeoverConflict(context, sku.getEmbryoCode(), shiftIndex)) {
                registerGreenTireChangeoverShift(context, sku.getEmbryoCode(), shiftIndex);
                return allocatedTime;
            }
            mouldChangeBalance.rollbackMouldChange(context, allocatedTime);
            Date nextProbeTime = resolveNextChangeoverProbeTime(context, shiftIndex, allocatedTime);
            log.info("同胎胚换模班次冲突，顺延换模, materialCode: {}, embryoCode: {}, machineCode: {}, "
                            + "冲突班次: {}, 原换模时间: {}, 顺延探测时间: {}",
                    sku.getMaterialCode(), sku.getEmbryoCode(), machineCode, shiftIndex,
                    LhScheduleTimeUtil.formatDateTime(allocatedTime),
                    LhScheduleTimeUtil.formatDateTime(nextProbeTime));
            if (nextProbeTime == null) {
                return null;
            }
            cursorTime = nextProbeTime;
        }
        log.warn("同胎胚换模错开失败，超过窗口探测上限, materialCode: {}, embryoCode: {}, machineCode: {}",
                sku.getMaterialCode(), sku.getEmbryoCode(), machineCode);
        return null;
    }

    /**
     * 回填已有排程结果中的同胎胚换模班次占用。
     *
     * @param context 排程上下文
     */
    private void preloadGreenTireChangeoverOccupancy(LhScheduleContext context) {
        if (context == null || CollectionUtils.isEmpty(context.getScheduleResultList())) {
            return;
        }
        for (LhScheduleResult result : context.getScheduleResultList()) {
            if (!shouldTrackGreenTireChangeoverResult(result)) {
                continue;
            }
            Date changeoverStartTime = resolveExistingGreenTireChangeoverStartTime(result);
            if (changeoverStartTime == null) {
                continue;
            }
            int shiftIndex = LhScheduleTimeUtil.getShiftIndex(context, context.getScheduleDate(), changeoverStartTime);
            if (shiftIndex <= 0) {
                continue;
            }
            registerGreenTireChangeoverShift(context, result.getEmbryoCode(), shiftIndex);
        }
    }

    /**
     * 判断结果是否需要参与同胎胚换模占用回填。
     *
     * @param result 排程结果
     * @return true-需要参与；false-跳过
     */
    private boolean shouldTrackGreenTireChangeoverResult(LhScheduleResult result) {
        return result != null
                && "1".equals(result.getIsChangeMould())
                && StringUtils.isNotEmpty(result.getEmbryoCode())
                && resolveExistingGreenTireScheduledQty(result) > 0;
    }

    /**
     * 解析已有换模结果的计划量。
     *
     * @param result 排程结果
     * @return 计划量
     */
    private int resolveExistingGreenTireScheduledQty(LhScheduleResult result) {
        int scheduledQty = ShiftFieldUtil.resolveScheduledQty(result);
        if (scheduledQty > 0) {
            return scheduledQty;
        }
        return result.getDailyPlanQty() != null ? Math.max(0, result.getDailyPlanQty()) : 0;
    }

    /**
     * 解析已有换模结果应占用的换模开始时间。
     *
     * @param result 排程结果
     * @return 换模开始时间
     */
    private Date resolveExistingGreenTireChangeoverStartTime(LhScheduleResult result) {
        if (result == null) {
            return null;
        }
        if (result.getMouldChangeStartTime() != null) {
            return result.getMouldChangeStartTime();
        }
        if (result.isRollingInherited()) {
            return null;
        }
        Date productionStartTime = resolveExistingProductionStartTime(result);
        if (productionStartTime != null) {
            return productionStartTime;
        }
        return result.getSpecEndTime();
    }

    /**
     * 解析已有结果的首个开产时间，供缺少真实换模时间的继承结果复用。
     *
     * @param result 排程结果
     * @return 首个开产时间
     */
    private Date resolveExistingProductionStartTime(LhScheduleResult result) {
        List<Date> startTimes = new ArrayList<Date>(LhScheduleConstant.MAX_SHIFT_SLOT_COUNT);
        if (result.getClass1StartTime() != null) {
            startTimes.add(result.getClass1StartTime());
        }
        if (result.getClass2StartTime() != null) {
            startTimes.add(result.getClass2StartTime());
        }
        if (result.getClass3StartTime() != null) {
            startTimes.add(result.getClass3StartTime());
        }
        if (result.getClass4StartTime() != null) {
            startTimes.add(result.getClass4StartTime());
        }
        if (result.getClass5StartTime() != null) {
            startTimes.add(result.getClass5StartTime());
        }
        if (result.getClass6StartTime() != null) {
            startTimes.add(result.getClass6StartTime());
        }
        if (result.getClass7StartTime() != null) {
            startTimes.add(result.getClass7StartTime());
        }
        if (result.getClass8StartTime() != null) {
            startTimes.add(result.getClass8StartTime());
        }
        return startTimes.stream().min(Date::compareTo).orElse(null);
    }

    /**
     * 回滚换模均衡占用及同胎胚换模班次占用。
     *
     * @param context 排程上下文
     * @param sku SKU
     * @param mouldChangeBalance 换模均衡策略
     * @param mouldChangeStartTime 换模开始时间
     */
    private void rollbackMouldChangeAllocation(LhScheduleContext context,
                                               SkuScheduleDTO sku,
                                               IMouldChangeBalanceStrategy mouldChangeBalance,
                                               Date mouldChangeStartTime) {
        mouldChangeBalance.rollbackMouldChange(context, mouldChangeStartTime);
        rollbackGreenTireChangeoverShift(context, sku, mouldChangeStartTime);
    }

    /**
     * 判断同胎胚换模班次是否冲突。
     *
     * @param context 排程上下文
     * @param greenTireGroupKey 胎胚分组Key
     * @param shiftIndex 班次索引
     * @return true-冲突，false-不冲突
     */
    private boolean hasGreenTireChangeoverConflict(LhScheduleContext context,
                                                   String greenTireGroupKey,
                                                   int shiftIndex) {
        if (context == null || StringUtils.isEmpty(greenTireGroupKey) || shiftIndex <= 0) {
            return false;
        }
        Set<Integer> occupiedShiftSet = context.getGreenTireChangeoverShiftMap().get(greenTireGroupKey);
        return !CollectionUtils.isEmpty(occupiedShiftSet) && occupiedShiftSet.contains(shiftIndex);
    }

    /**
     * 登记同胎胚换模班次占用。
     *
     * @param context 排程上下文
     * @param greenTireGroupKey 胎胚分组Key
     * @param shiftIndex 班次索引
     */
    private void registerGreenTireChangeoverShift(LhScheduleContext context,
                                                  String greenTireGroupKey,
                                                  int shiftIndex) {
        if (context == null || StringUtils.isEmpty(greenTireGroupKey) || shiftIndex <= 0) {
            return;
        }
        context.getGreenTireChangeoverShiftMap()
                .computeIfAbsent(greenTireGroupKey, key -> new HashSet<Integer>(4))
                .add(shiftIndex);
    }

    /**
     * 回滚同胎胚换模班次占用。
     *
     * @param context 排程上下文
     * @param sku SKU
     * @param mouldChangeStartTime 换模开始时间
     */
    private void rollbackGreenTireChangeoverShift(LhScheduleContext context,
                                                  SkuScheduleDTO sku,
                                                  Date mouldChangeStartTime) {
        if (context == null || sku == null || StringUtils.isEmpty(sku.getEmbryoCode())
                || mouldChangeStartTime == null) {
            return;
        }
        int shiftIndex = LhScheduleTimeUtil.getShiftIndex(context, context.getScheduleDate(), mouldChangeStartTime);
        Set<Integer> occupiedShiftSet = context.getGreenTireChangeoverShiftMap().get(sku.getEmbryoCode());
        if (CollectionUtils.isEmpty(occupiedShiftSet)) {
            return;
        }
        occupiedShiftSet.remove(shiftIndex);
        if (occupiedShiftSet.isEmpty()) {
            context.getGreenTireChangeoverShiftMap().remove(sku.getEmbryoCode());
        }
    }

    /**
     * 获取下一次换模探测时间。
     *
     * @param context 排程上下文
     * @param shiftIndex 当前冲突班次索引
     * @param allocatedTime 当前换模时间
     * @return 下一探测时间
     */
    private Date resolveNextChangeoverProbeTime(LhScheduleContext context, int shiftIndex, Date allocatedTime) {
        if (context == null || shiftIndex <= 0) {
            return null;
        }
        LhShiftConfigVO shift = LhScheduleTimeUtil.getShiftByIndex(context, context.getScheduleDate(), shiftIndex);
        if (shift != null && shift.getShiftEndDateTime() != null) {
            return shift.getShiftEndDateTime();
        }
        return LhScheduleTimeUtil.addHours(allocatedTime, 1);
    }

    /**
     * 解析定点机台挤量后预留的机台就绪时间。
     *
     * @param context 排程上下文
     * @param sku 新增SKU
     * @param machineCode 机台编码
     * @param machineReadyTime 原机台就绪时间
     * @return 生效后的机台就绪时间
     */
    private Date resolveSpecifyReservedReadyTime(LhScheduleContext context,
                                                 SkuScheduleDTO sku,
                                                 String machineCode,
                                                 Date machineReadyTime) {
        if (context == null || sku == null || StringUtils.isEmpty(machineCode)) {
            return machineReadyTime;
        }
        String reservedMaterialCode = context.getSpecifyMachineReservedMaterialMap().get(machineCode);
        Date reservedSwitchStartTime = context.getSpecifyMachineReservedSwitchStartTimeMap().get(machineCode);
        if (!StringUtils.equals(reservedMaterialCode, sku.getMaterialCode()) || reservedSwitchStartTime == null) {
            return machineReadyTime;
        }
        if (machineReadyTime == null || reservedSwitchStartTime.after(machineReadyTime)) {
            log.info("新增排产使用定点机台挤量预留时间, machineCode: {}, materialCode: {}, readyTime: {}",
                    machineCode, sku.getMaterialCode(), LhScheduleTimeUtil.formatDateTime(reservedSwitchStartTime));
            return reservedSwitchStartTime;
        }
        return machineReadyTime;
    }

    /**
     * 清理定点机台挤量预留信息。
     *
     * @param context 排程上下文
     * @param machineCode 机台编码
     * @param materialCode 物料编码
     */
    private void clearSpecifyReservation(LhScheduleContext context, String machineCode, String materialCode) {
        if (context == null || StringUtils.isEmpty(machineCode)) {
            return;
        }
        String reservedMaterialCode = context.getSpecifyMachineReservedMaterialMap().get(machineCode);
        if (StringUtils.isEmpty(materialCode) || StringUtils.equals(materialCode, reservedMaterialCode)) {
            context.getSpecifyMachineReservedMaterialMap().remove(machineCode);
            context.getSpecifyMachineReservedSwitchStartTimeMap().remove(machineCode);
        }
    }

    private void updateMachineState(LhScheduleContext context, MachineScheduleDTO machine, SkuScheduleDTO sku, LhScheduleResult result) {
        cacheInitialMachineState(context, machine);
        machine.setPreviousMaterialCode(machine.getCurrentMaterialCode());
        machine.setPreviousMaterialDesc(machine.getCurrentMaterialDesc());
        machine.setCurrentMaterialCode(sku.getMaterialCode());
        machine.setCurrentMaterialDesc(sku.getMaterialDesc());
        machine.setPreviousSpecCode(sku.getSpecCode());
        machine.setPreviousProSize(sku.getProSize());
        machine.setEstimatedEndTime(result.getSpecEndTime());
    }

    /**
     * 在首次更新机台状态前缓存初始快照，便于零计划回滚。
     *
     * @param context 排程上下文
     * @param machine 机台
     */
    private void cacheInitialMachineState(LhScheduleContext context, MachineScheduleDTO machine) {
        if (context == null || machine == null || StringUtils.isEmpty(machine.getMachineCode())) {
            return;
        }
        if (context.getInitialMachineScheduleMap().containsKey(machine.getMachineCode())) {
            return;
        }
        MachineScheduleDTO snapshot = new MachineScheduleDTO();
        snapshot.setMachineCode(machine.getMachineCode());
        snapshot.setMachineName(machine.getMachineName());
        snapshot.setCurrentMaterialCode(machine.getCurrentMaterialCode());
        snapshot.setCurrentMaterialDesc(machine.getCurrentMaterialDesc());
        snapshot.setPreviousMaterialCode(machine.getPreviousMaterialCode());
        snapshot.setPreviousMaterialDesc(machine.getPreviousMaterialDesc());
        snapshot.setPreviousSpecCode(machine.getPreviousSpecCode());
        snapshot.setPreviousProSize(machine.getPreviousProSize());
        snapshot.setEstimatedEndTime(machine.getEstimatedEndTime());
        snapshot.setMachineOrder(machine.getMachineOrder());
        snapshot.setMaxMoldNum(machine.getMaxMoldNum());
        snapshot.setCapsuleUsageCount(machine.getCapsuleUsageCount());
        context.getInitialMachineScheduleMap().put(machine.getMachineCode(), snapshot);
    }

    /**
     * 生成工单号（使用线程安全的OrderNoGenerator）
     */
    private String generateOrderNo(LhScheduleContext context) {
        return orderNoGenerator.generateOrderNo(context.getScheduleTargetDate());
    }

    /**
     * 添加未排产记录
     */
    private void addUnscheduledResult(LhScheduleContext context, SkuScheduleDTO sku, String reason) {
        addUnscheduledResult(context, sku, sku.resolveTargetScheduleQty(), reason);
    }

    /**
     * 添加未排产记录
     *
     * @param context 排程上下文
     * @param sku SKU
     * @param unscheduledQty 未排数量
     * @param reason 未排原因
     */
    private void addUnscheduledResult(LhScheduleContext context, SkuScheduleDTO sku,
                                      int unscheduledQty, String reason) {
        LhUnscheduledResult unscheduled = new LhUnscheduledResult();
        unscheduled.setFactoryCode(context.getFactoryCode());
        unscheduled.setBatchNo(context.getBatchNo());
        unscheduled.setMaterialCode(sku.getMaterialCode());
        unscheduled.setMaterialDesc(sku.getMaterialDesc());
        unscheduled.setScheduleDate(context.getScheduleTargetDate());
        unscheduled.setUnscheduledReason(reason);
        unscheduled.setUnscheduledQty(Math.max(0, unscheduledQty));
        unscheduled.setStructureName(sku.getStructureName());
        unscheduled.setMainMaterialDesc(sku.getMainMaterialDesc());
        unscheduled.setSpecCode(sku.getSpecCode());
        unscheduled.setEmbryoCode(sku.getEmbryoCode());
        unscheduled.setMouldQty(sku.getMouldQty());
        unscheduled.setDataSource(AUTO_DATA_SOURCE);
        unscheduled.setIsDelete(0);
        context.getUnscheduledResultList().add(unscheduled);
        log.debug("新增SKU未排产, SKU: {}, 未排数量: {}, 原因: {}",
                sku.getMaterialCode(), Math.max(0, unscheduledQty), reason);
    }

    /**
     * 添加未排产记录并累计原因分布
     */
    private void addUnscheduledResult(LhScheduleContext context, SkuScheduleDTO sku, String reason,
                                      Map<String, Integer> reasonCountMap) {
        addUnscheduledResult(context, sku, reason);
        reasonCountMap.merge(reason, 1, Integer::sum);
    }

    /**
     * 添加指定数量的未排产记录并累计原因分布。
     *
     * @param context 排程上下文
     * @param sku SKU
     * @param unscheduledQty 未排数量
     * @param reason 未排原因
     * @param reasonCountMap 原因分布
     */
    private void addUnscheduledResult(LhScheduleContext context, SkuScheduleDTO sku,
                                      int unscheduledQty, String reason,
                                      Map<String, Integer> reasonCountMap) {
        addUnscheduledResult(context, sku, unscheduledQty, reason);
        reasonCountMap.merge(reason, 1, Integer::sum);
    }

    /**
     * 将排产块的班次数量按生产日期回写到SKU日计划额度账本。
     * <p>遍历排产结果中每个有排产量的班次，按班次归属日期扣减对应日期的剩余额度。
     * 如果班次产能大于当日剩余额度，排满班次并记录满班补齐超排量，
     * 超出部分优先冲抵窗口内后续日期的同SKU计划。</p>
     *
     * @param context 排程上下文
     * @param sku SKU排程DTO
     * @param result 排程结果
     * @param shifts 排程窗口班次列表
     */
    private int applyBlockToDailyQuota(LhScheduleContext context,
                                       SkuScheduleDTO sku,
                                       LhScheduleResult result,
                                       List<LhShiftConfigVO> shifts) {
        Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap = sku.getDailyPlanQuotaMap();
        if (quotaMap == null || quotaMap.isEmpty()) {
            return result.getDailyPlanQty() != null ? result.getDailyPlanQty() : 0;
        }
        int totalShiftFillOverQty = 0;
        for (LhShiftConfigVO shift : shifts) {
            Integer planQty = ShiftFieldUtil.getShiftPlanQty(result, shift.getShiftIndex());
            if (planQty == null || planQty <= 0) {
                continue;
            }
            Date workDate = shift.getWorkDate();
            if (workDate == null) {
                continue;
            }
            LocalDate productionDate = workDate.toInstant()
                    .atZone(ZoneId.systemDefault()).toLocalDate();
            SkuDailyPlanQuotaDTO quota = quotaMap.get(productionDate);
            if (quota == null) {
                continue;
            }
            // 按历史欠产、当日计划、未来预占顺序消费同一SKU的日计划账本
            int consumed = SkuDailyPlanQuotaUtil.consumeRollingQuota(quotaMap, productionDate, planQty);
            int overQty = planQty - consumed;
            if (overQty > 0) {
                boolean endingResult = "1".equals(result.getIsEnd());
                // 收尾结果必须严格截断，且不再记录满班补齐超排；
                // 试制等严格目标量场景仍需回裁，但保留超排账本用于追踪被截掉的补满量。
                if (endingResult || (sku != null && sku.isStrictTargetQty())) {
                    trimShiftPlanQty(result, shift.getShiftIndex(), consumed);
                    if (endingResult) {
                        continue;
                    }
                }
                // 无法冲抵的部分记录为满班补齐超排量
                quota.setShiftFillOverQty(quota.getShiftFillOverQty() + overQty);
                totalShiftFillOverQty += overQty;
                log.debug("班次满班补齐超排, materialCode: {}, 日期: {}, 班次: {}, 排产量: {}, 超排: {}",
                        sku.getMaterialCode(), productionDate, shift.getShiftIndex(), planQty, overQty);
            }
        }
        if (totalShiftFillOverQty > 0) {
            sku.setShiftFillOverQty(sku.getShiftFillOverQty() + totalShiftFillOverQty);
            // 同步写入上下文累加器，确保SKU从待排列表移除后汇总日志仍可读取
            context.getSkuShiftFillOverQtyMap().merge(sku.getMaterialCode(), totalShiftFillOverQty, Integer::sum);
        }
        refreshResultSummary(context, result);
        return result.getDailyPlanQty() != null ? result.getDailyPlanQty() : 0;
    }

    /**
     * 回裁单个班次计划量，并清空失效的结束时刻，交给结果汇总重新推导真实完工时刻。
     *
     * @param result 排程结果
     * @param shiftIndex 班次索引
     * @param trimmedQty 回裁后的计划量
     */
    private void trimShiftPlanQty(LhScheduleResult result, int shiftIndex, int trimmedQty) {
        Date shiftStartTime = ShiftFieldUtil.getShiftStartTime(result, shiftIndex);
        if (trimmedQty <= 0) {
            setShiftPlanQty(result, shiftIndex, 0, null, null);
            return;
        }
        setShiftPlanQty(result, shiftIndex, trimmedQty, shiftStartTime, null);
    }

    /**
     * 判断SKU是否需要继续尝试下一台机台排产。
     * <p>同时检查总量剩余和日计划额度剩余，两者都满足时才不需要继续。</p>
     *
     * @param sku SKU排程DTO
     * @return true-需要继续多机台排产，false-已满足
     */
    private boolean needMoreMachine(SkuScheduleDTO sku) {
        if (sku.getRemainingScheduleQty() > 0) {
            return true;
        }
        Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap = sku.getDailyPlanQuotaMap();
        if (quotaMap == null || quotaMap.isEmpty()) {
            return false;
        }
        return quotaMap.values().stream().anyMatch(day -> day.getRemainingQty() > 0);
    }

    /**
     * 解析新增规格本轮可继续落结果的剩余量。
     * <p>按需求排产时，目标量保留月计划需求口径；多机台拆量则按日计划账本剩余额度收敛，
     * 确保窗口总量封顶由账本统一控制。</p>
     *
     * @param sku SKU排程DTO
     * @return 本轮可继续排产量
     */
    private int resolveSchedulableRemainingQty(SkuScheduleDTO sku) {
        if (sku == null) {
            return 0;
        }
        int remainingQuotaQty = SkuDailyPlanQuotaUtil.sumRemainingQty(sku.getDailyPlanQuotaMap());
        if (remainingQuotaQty > 0) {
            int windowRemainingQty = resolveWindowRemainingQty(sku);
            return Math.min(sku.resolveTargetScheduleQty(), Math.min(remainingQuotaQty, windowRemainingQty));
        }
        return sku.resolveTargetScheduleQty();
    }

    /**
     * 解析窗口总量封顶后的剩余可排量。
     *
     * @param sku SKU排程DTO
     * @return 窗口剩余可排量
     */
    private int resolveWindowRemainingQty(SkuScheduleDTO sku) {
        if (sku.getWindowPlanQty() <= 0 || sku.getDailyPlanQuotaMap() == null
                || sku.getDailyPlanQuotaMap().isEmpty()) {
            return Integer.MAX_VALUE;
        }
        int scheduledQty = sku.getDailyPlanQuotaMap().values().stream()
                .filter(day -> day != null)
                .mapToInt(day -> Math.max(0, day.getScheduledQty()))
                .sum();
        return Math.max(0, sku.getWindowPlanQty() - scheduledQty);
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
     * 在所有SKU列表中查找指定materialCode的DTO
     */
    private SkuScheduleDTO findSkuDto(LhScheduleContext context, String materialCode) {
        if (context == null || StringUtils.isEmpty(materialCode)) {
            return null;
        }
        for (SkuScheduleDTO sku : context.getNewSpecSkuList()) {
            if (materialCode.equals(sku.getMaterialCode())) {
                return sku;
            }
        }
        for (SkuScheduleDTO sku : context.getContinuousSkuList()) {
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
     * 计算新增零计划结果转未排时的剩余待排数量。
     *
     * @param context 排程上下文
     * @param materialCode 物料编码
     * @return 未排数量
     */
    private int resolveRemainingUnscheduledQty(LhScheduleContext context, String materialCode) {
        SkuScheduleDTO sku = findSkuDto(context, materialCode);
        if (sku == null) {
            return 0;
        }
        int targetScheduleQty = sku.resolveTargetScheduleQty();
        int retainedQty = resolveEffectiveScheduledQty(context, materialCode);
        return Math.max(targetScheduleQty - retainedQty, 0);
    }

    /**
     * 统计同物料仍保留在新增结果列表中的有效计划量。
     *
     * @param context 排程上下文
     * @param materialCode 物料编码
     * @return 有效计划量
     */
    private int resolveEffectiveScheduledQty(LhScheduleContext context, String materialCode) {
        if (context == null || StringUtils.isEmpty(materialCode) || CollectionUtils.isEmpty(context.getScheduleResultList())) {
            return 0;
        }
        int totalQty = 0;
        for (LhScheduleResult result : context.getScheduleResultList()) {
            if (result == null
                    || !StringUtils.equals(materialCode, result.getMaterialCode())
                    || !NEW_SPEC_SCHEDULE_TYPE.equals(result.getScheduleType())
                    || "1".equals(result.getIsTypeBlock())  // 排除换活字块
                    || result.getDailyPlanQty() == null
                    || result.getDailyPlanQty() <= 0) {
                continue;
            }
            totalQty += result.getDailyPlanQty();
        }
        return totalQty;
    }

    /**
     * 按物料维度写入或合并未排结果。
     *
     * @param context 排程上下文
     * @param materialCode 物料编码
     * @param unscheduledQty 未排数量
     */
    private void mergeUnscheduledResultByMaterial(LhScheduleContext context, String materialCode, int unscheduledQty) {
        if (context == null || StringUtils.isEmpty(materialCode) || unscheduledQty <= 0) {
            return;
        }
        LhUnscheduledResult existing = findUnscheduledResultByMaterial(context, materialCode);
        if (existing != null) {
            int existingQty = existing.getUnscheduledQty() != null ? existing.getUnscheduledQty() : 0;
            existing.setUnscheduledQty(existingQty + unscheduledQty);
            if (StringUtils.isEmpty(existing.getUnscheduledReason())) {
                existing.setUnscheduledReason(ZERO_PLAN_UNSCHEDULED_REASON);
            }
            return;
        }
        SkuScheduleDTO sku = findSkuDto(context, materialCode);
        if (sku == null) {
            return;
        }
        LhUnscheduledResult unscheduled = new LhUnscheduledResult();
        unscheduled.setFactoryCode(context.getFactoryCode());
        unscheduled.setBatchNo(context.getBatchNo());
        unscheduled.setScheduleDate(context.getScheduleTargetDate());
        unscheduled.setMaterialCode(materialCode);
        unscheduled.setMaterialDesc(sku.getMaterialDesc());
        unscheduled.setStructureName(sku.getStructureName());
        unscheduled.setMainMaterialDesc(sku.getMainMaterialDesc());
        unscheduled.setSpecCode(sku.getSpecCode());
        unscheduled.setEmbryoCode(sku.getEmbryoCode());
        unscheduled.setMouldQty(sku.getMouldQty());
        unscheduled.setUnscheduledQty(unscheduledQty);
        unscheduled.setUnscheduledReason(ZERO_PLAN_UNSCHEDULED_REASON);
        unscheduled.setDataSource(AUTO_DATA_SOURCE);
        unscheduled.setIsDelete(0);
        context.getUnscheduledResultList().add(unscheduled);
    }

    /**
     * 查找已存在的未排结果。
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
     * 对未排结果按物料编码去重合并。
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
     * 将被移除的零计划结果同步从机台分配记录中清理掉。
     *
     * @param context 排程上下文
     * @param resultsToRemove 待移除结果
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

    /**
     * 多机台余量和胎胚库存按机台条数均分。
     * <p>对新增阶段结果按物料分组，委托 {@link LhMultiMachineDistributionUtil#distributeForSingleMaterial}
     * 按机台结果条数均分，最后一条补尾差。</p>
     *
     * @param context 排程上下文
     */
    private void distributeMultiMachineSurplusAndStock(LhScheduleContext context) {
        if (context == null || CollectionUtils.isEmpty(context.getScheduleResultList())) {
            return;
        }
        // 按物料编码汇总新增结果（排除换活字块）
        Map<String, List<LhScheduleResult>> materialResultsMap = new LinkedHashMap<>(16);
        for (LhScheduleResult result : context.getScheduleResultList()) {
            if (!NEW_SPEC_SCHEDULE_TYPE.equals(result.getScheduleType())
                    || "1".equals(result.getIsTypeBlock())) {
                continue;
            }
            if (StringUtils.isEmpty(result.getMaterialCode())) {
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
            // 仅分摊胎胚库存，余量不按机台均分（各机台结果保留原始全量值）
            LhMultiMachineDistributionUtil.distributeForSingleMaterial(
                    materialResults, totalSurplus, totalEmbryoStock);
            log.debug("多机台新增胎胚库存分摊完成, materialCode: {}, 机台数: {}, 总余量: {}, 总胎胚库存: {}",
                    materialCode, materialResults.size(), totalSurplus, totalEmbryoStock);
        }
    }

    /**
     * 新增零计划结果移除后，按最终保留结果重新同步机台状态。
     *
     * @param context 排程上下文
     */
    private void syncMachineStateAfterNewAdjust(LhScheduleContext context) {
        if (context == null || CollectionUtils.isEmpty(context.getMachineScheduleMap())) {
            return;
        }
        for (Map.Entry<String, MachineScheduleDTO> entry : context.getMachineScheduleMap().entrySet()) {
            String machineCode = entry.getKey();
            MachineScheduleDTO machine = entry.getValue();
            List<LhScheduleResult> assignedResults = context.getMachineAssignmentMap().get(machineCode);
            LhScheduleResult latestResult = resolveLatestAssignedResult(assignedResults);
            if (latestResult != null) {
                LhScheduleResult previousResult = resolvePreviousAssignedResult(assignedResults, latestResult);
                applyMachineStateFromResult(context, machine, latestResult, previousResult);
                continue;
            }
            restoreMachineStateFromInitial(context, machineCode, machine);
        }
    }

    /**
     * 查找机台当前保留的最新有效结果。
     *
     * @param context 排程上下文
     * @param machineCode 机台编码
     * @return 最新有效结果
     */
    private LhScheduleResult resolveLatestAssignedResult(List<LhScheduleResult> assignedResults) {
        if (CollectionUtils.isEmpty(assignedResults)) {
            return null;
        }
        return assignedResults.stream()
                .filter(result -> result != null
                        && result.getDailyPlanQty() != null
                        && result.getDailyPlanQty() > 0
                        && result.getSpecEndTime() != null)
                .max(Comparator.comparing(LhScheduleResult::getSpecEndTime))
                .orElse(null);
    }

    /**
     * 查找机台当前保留结果中的上一条有效结果。
     *
     * @param assignedResults 机台保留结果
     * @param latestResult 最新有效结果
     * @return 上一条有效结果
     */
    private LhScheduleResult resolvePreviousAssignedResult(List<LhScheduleResult> assignedResults,
                                                           LhScheduleResult latestResult) {
        if (CollectionUtils.isEmpty(assignedResults) || latestResult == null) {
            return null;
        }
        return assignedResults.stream()
                .filter(result -> result != null
                        && result != latestResult
                        && result.getDailyPlanQty() != null
                        && result.getDailyPlanQty() > 0
                        && result.getSpecEndTime() != null)
                .max(Comparator.comparing(LhScheduleResult::getSpecEndTime))
                .orElse(null);
    }

    /**
     * 使用最新有效结果回写机台状态。
     *
     * @param machine 机台
     * @param result 最新有效结果
     */
    private void applyMachineStateFromResult(LhScheduleContext context,
                                             MachineScheduleDTO machine,
                                             LhScheduleResult result,
                                             LhScheduleResult previousResult) {
        if (context == null || machine == null || result == null) {
            return;
        }
        String previousMaterialCode = null;
        String previousMaterialDesc = null;
        if (previousResult != null) {
            previousMaterialCode = previousResult.getMaterialCode();
            previousMaterialDesc = previousResult.getMaterialDesc();
        } else if (StringUtils.isNotEmpty(machine.getMachineCode())) {
            MachineScheduleDTO initialMachine = context.getInitialMachineScheduleMap().get(machine.getMachineCode());
            if (initialMachine != null) {
                previousMaterialCode = initialMachine.getCurrentMaterialCode();
                previousMaterialDesc = initialMachine.getCurrentMaterialDesc();
            }
        }
        SkuScheduleDTO sku = findSkuDto(context, result.getMaterialCode());
        machine.setCurrentMaterialCode(result.getMaterialCode());
        machine.setCurrentMaterialDesc(result.getMaterialDesc());
        machine.setPreviousMaterialCode(previousMaterialCode);
        machine.setPreviousMaterialDesc(previousMaterialDesc);
        machine.setPreviousSpecCode(result.getSpecCode());
        machine.setPreviousProSize(sku != null ? sku.getProSize() : null);
        machine.setEstimatedEndTime(result.getSpecEndTime());
    }

    /**
     * 当前机台无有效排程结果时，回退到初始化快照。
     *
     * @param context 排程上下文
     * @param machineCode 机台编码
     * @param machine 机台
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
    }
}
