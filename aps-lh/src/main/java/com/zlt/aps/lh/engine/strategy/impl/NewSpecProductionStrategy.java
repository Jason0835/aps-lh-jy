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
import com.zlt.aps.lh.api.enums.ShiftEnum;
import com.zlt.aps.lh.engine.strategy.ICapacityCalculateStrategy;
import com.zlt.aps.lh.engine.strategy.IEndingJudgmentStrategy;
import com.zlt.aps.lh.engine.strategy.IFirstInspectionBalanceStrategy;
import com.zlt.aps.lh.engine.strategy.IMachineMatchStrategy;
import com.zlt.aps.lh.engine.strategy.IMouldChangeBalanceStrategy;
import com.zlt.aps.lh.engine.strategy.IProductionStrategy;
import com.zlt.aps.lh.engine.strategy.ITrialProductionStrategy;
import com.zlt.aps.lh.engine.strategy.support.DailyMachineCapacityDayDecision;
import com.zlt.aps.lh.engine.strategy.support.DailyMachineCapacitySimulationRequest;
import com.zlt.aps.lh.engine.strategy.support.DailyMachineCapacitySimulationResult;
import com.zlt.aps.lh.engine.strategy.support.DailyMachineCapacitySimulationUtil;
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
import com.zlt.aps.lh.util.ResultDowntimeSummaryUtil;
import com.zlt.aps.lh.util.ShiftCapacityResolverUtil;
import com.zlt.aps.lh.util.ShiftProductionControlUtil;
import com.zlt.aps.lh.util.SingleMouldShiftQtyUtil;
import com.zlt.aps.lh.util.SkuDailyPlanQuotaUtil;
import com.zlt.aps.lh.component.OrderNoGenerator;
import com.zlt.aps.mdm.api.domain.entity.MdmDevicePlanShut;
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
        if (sku.getEmbryoStock() <= 0) {
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
        Map<String, Integer> unscheduledReasonCountMap = new LinkedHashMap<>(8);
        initializePendingNewSpecSkuTypeCounts(context);
        initializeSingleControlStructureEndingLayerSnapshot(context);
        int scheduledCount = schedulePendingNewSpecs(context, machineMatch, mouldChangeBalance,
                inspectionBalance, capacityCalculate, shifts, unscheduledReasonCountMap);
        log.info("新增排产完成, 成功: {}, 未排: {}, 原因分布: {}",
                scheduledCount,
                unscheduledReasonCountMap.values().stream().mapToInt(Integer::intValue).sum(),
                unscheduledReasonCountMap);
    }

    /**
     * 执行新增SKU排产。
     * <p>新增SKU必须按全局排序顺序逐个选机，不再按试制、量试、小批量做单控竞争重排。</p>
     *
     * @param context 排程上下文
     * @param machineMatch 机台匹配策略
     * @param mouldChangeBalance 换模均衡策略
     * @param inspectionBalance 首检均衡策略
     * @param capacityCalculate 产能策略
     * @param shifts 排程班次
     * @param unscheduledReasonCountMap 未排原因统计
     * @return 本轮新增的成功结果数
     */
    private int schedulePendingNewSpecs(LhScheduleContext context,
                                        IMachineMatchStrategy machineMatch,
                                        IMouldChangeBalanceStrategy mouldChangeBalance,
                                        IFirstInspectionBalanceStrategy inspectionBalance,
                                        ICapacityCalculateStrategy capacityCalculate,
                                        List<LhShiftConfigVO> shifts,
                                        Map<String, Integer> unscheduledReasonCountMap) {
        int totalScheduledCount = 0;
        int roundCount = 0;
        while (!CollectionUtils.isEmpty(context.getNewSpecSkuList())) {
            roundCount++;
            RoundScheduleSummary roundSummary = schedulePendingNewSpecsRound(context, machineMatch, mouldChangeBalance,
                    inspectionBalance, capacityCalculate, shifts, unscheduledReasonCountMap);
            totalScheduledCount += roundSummary.getScheduledCount();
            if (!roundSummary.isDeferred()) {
                break;
            }
            if (!roundSummary.isProgressed()) {
                log.warn("新增排产单控竞争延后后仍无进展，终止后续轮次避免死循环, remainingSkuCount: {}, round: {}",
                        context.getNewSpecSkuList().size(), roundCount);
                break;
            }
        }
        return totalScheduledCount;
    }

    private RoundScheduleSummary schedulePendingNewSpecsRound(LhScheduleContext context,
                                                              IMachineMatchStrategy machineMatch,
                                                              IMouldChangeBalanceStrategy mouldChangeBalance,
                                                              IFirstInspectionBalanceStrategy inspectionBalance,
                                                              ICapacityCalculateStrategy capacityCalculate,
                                                              List<LhShiftConfigVO> shifts,
                                                              Map<String, Integer> unscheduledReasonCountMap) {
        int scheduledCount = 0;
        boolean progressed = false;
        boolean deferred = false;
        boolean deferredResumePrioritized = false;
        List<SkuScheduleDTO> deferredSkuList = new ArrayList<SkuScheduleDTO>(4);
        Iterator<SkuScheduleDTO> iterator = context.getNewSpecSkuList().iterator();
        while (iterator.hasNext()) {
            SkuScheduleDTO sku = iterator.next();
            if (!CollectionUtils.isEmpty(deferredSkuList)
                    && shouldPrioritizeDeferredSingleControlSku(context, sku, deferredSkuList)) {
                deferredResumePrioritized = true;
                break;
            }
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
                removeCurrentNewSpecSku(context, iterator, sku);
                progressed = true;
                continue;
            }

            // 1. 匹配候选机台
            context.getNewSpecTypeRuleBlockedMap().remove(sku);
            List<MachineScheduleDTO> candidates = machineMatch.matchMachines(context, sku);
            if (candidates.isEmpty()) {
                String noCandidateReason = resolveNoCandidateMachineReason(context, sku);
                log.warn("新增SKU无候选机台, materialCode: {}, 结构: {}, 规格: {}, 寸口: {}, 目标量: {}, 原因: {}",
                        sku.getMaterialCode(), sku.getStructureName(), sku.getSpecCode(),
                        sku.getProSize(), sku.resolveTargetScheduleQty(), noCandidateReason);
                traceNewSpecMachineDecision(context, sku, candidates, null, null,
                        new HashSet<String>(0), new LinkedHashMap<String, String>(0),
                        NewSpecFailReasonEnum.MACHINE_SELECTION_FAILED,
                        false, noCandidateReason);
                addUnscheduledResult(context, sku, noCandidateReason, unscheduledReasonCountMap);
                removeCurrentNewSpecSku(context, iterator, sku);
                progressed = true;
                continue;
            }

            // 1.1 小规模候选机台场景下，局部搜索仅做评估，不再改写当前SKU基础首选机台
            MachineScheduleDTO localSearchSuggestedMachine = selectPreferredMachineByLocalSearch(
                    context, sku, candidates, shifts, machineMatch, mouldChangeBalance, inspectionBalance, capacityCalculate);
            MachineScheduleDTO preferredTrialMachine = resolvePreferredTrialMachine(context, sku, candidates);
            if (shouldDeferSingleControlCompetition(context, sku, candidates, machineMatch)) {
                log.info("新增SKU单控竞争延后, materialCode: {}, skuType: {}, structureName: {}, isStructureEndingPriority: {}, reason: {}",
                        sku.getMaterialCode(), resolveNewSpecSkuType(sku), sku.getStructureName(),
                        isStructureAllEndingPriority(context, sku),
                        "同层级存在更高优先级试制/量试/小批量SKU，当前SKU延后等待单控剩余产能");
                iterator.remove();
                deferredSkuList.add(sku);
                deferred = true;
                continue;
            }

            // 2. 基于策略选择最优机台，失败后排除并继续选择下一台。
            // 多机台拆量：当一台机台产能不足以排完目标量时，继续尝试下一台机台。
            boolean scheduled = false;
            NewSpecFailReasonEnum failReason = NewSpecFailReasonEnum.MACHINE_SELECTION_FAILED;
            Set<String> excludedMachineCodes = new HashSet<>(candidates.size());
            Map<String, String> excludedMachineReasonMap = new LinkedHashMap<>(candidates.size());
            Integer originalTargetScheduleQty = sku.getTargetScheduleQty();
            int minimumTargetScheduleQty = resolveFormalNonEndingMinimumTargetQty(context, sku, quantityPolicy);
            if (minimumTargetScheduleQty > 0) {
                sku.setTargetScheduleQty(minimumTargetScheduleQty);
            }
            Integer baseTargetScheduleQty = sku.getTargetScheduleQty();
            Integer finalTargetScheduleQty = baseTargetScheduleQty;
            // 初始化多机台拆量剩余量：需求目标保留月计划口径，实际拆机按日计划账本剩余额度收敛。
            int remainingQty = resolveSchedulableRemainingQty(sku);
            // 非收尾可溢出场景下，dynamicTargetQty 至少为一个满班产能，
            // 确保 shouldFillSingleMachineToWindowEnd 能按满班产能补足已开班次。
            if (quantityPolicy != null && quantityPolicy.isAllowFillStartedShift() && !quantityPolicy.isEnding()) {
                int shiftCapacity = sku.getShiftCapacity();
                if (shiftCapacity > 0) {
                    remainingQty = Math.max(remainingQty, shiftCapacity);
                }
            }
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
//                // 分配同胎胚错峰后的换模时间
//                mouldChangeStartTime = allocateGreenTireAwareMouldChange(
//                        context, sku, machineCode, switchReadyTime, switchDurationHours, mouldChangeBalance);
                mouldChangeStartTime = mouldChangeBalance.allocateMouldChange(
                        context, machineCode, switchReadyTime, switchDurationHours);
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
                    recordExcludedMachineReason(excludedMachineReasonMap, machineCode,
                            switchAllocateFailReason == NewSpecFailReasonEnum.FIRST_INSPECTION_SHIFT_ALLOCATE_FAILED
                                    ? "首检分配失败" : "换模窗口分配失败",
                            machineReadyTime, switchReadyTime, mouldChangeStartTime, mouldChangeCompleteTime,
                            inspectionTime, productionStartTime, null, null, null);
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
                    recordExcludedMachineReason(excludedMachineReasonMap, machineCode,
                            "排程窗口内无可开产时间",
                            machineReadyTime, switchReadyTime, mouldChangeStartTime, mouldChangeCompleteTime,
                            inspectionTime, productionStartTime, null, null, null);
                    failReason = selectHigherPriorityFailReason(
                            failReason, NewSpecFailReasonEnum.NO_CAPACITY_IN_SCHEDULE_WINDOW);
                    continue;
                }
                int candidateTargetQty = resolveCandidateTargetQty(
                        context, sku, candidateMachine, mouldChangeStartTime, firstProductionStartTime,
                        shifts, candidates, dynamicTargetQty, totalScheduledQty,
                        excludedMachineCodes, quantityPolicy);
                Map<Integer, Integer> shiftCapacityMap = calculateShiftCapacityMap(
                        context, candidateMachine, sku, firstProductionStartTime, mouldChangeStartTime,
                        shifts, machineMouldQty, runtimeShiftCapacity, isEnding);
                int maxQtyToWindowEnd = sumShiftCapacity(shiftCapacityMap);
                MachineProductionSegment segment = buildMachineProductionSegment(
                        context, sku, machineCode, mouldChangeStartTime, firstProductionStartTime,
                        maxQtyToWindowEnd, runtimeShiftCapacity, shiftCapacityMap);
                MachineScheduleRole role = resolveMachineScheduleRole(quantityPolicy, totalScheduledQty,
                        maxQtyToWindowEnd, candidateTargetQty);
                segment.setRole(role);
                boolean singleMachineWindowFill = shouldFillSingleMachineToWindowEnd(
                        context, sku, isEnding, totalScheduledQty, candidateTargetQty, maxQtyToWindowEnd);
                int machinePlanQty = singleMachineWindowFill
                        ? maxQtyToWindowEnd
                        : resolveMachinePlanQty(context, sku, quantityPolicy, role, segment,
                                candidateTargetQty, totalScheduledQty, maxQtyToWindowEnd, runtimeShiftCapacity);
                if (!singleMachineWindowFill) {
                    machinePlanQty = resolveDynamicMachinePlanQtyByDailyCapacity(
                            context, sku, candidates, excludedMachineCodes, quantityPolicy, segment,
                            candidateMachine, shifts, capacityCalculate, candidateTargetQty,
                            totalScheduledQty, machinePlanQty);
                }
                log.info("新增SKU候选机台动态分配, materialCode: {}, 机台: {}, 角色: {}, 最大可排量: {}, "
                                + "累计已排: {}, 窗口目标量: {}, 本机台计划量: {}, 换模班次: {}, 开产班次: {}",
                        sku.getMaterialCode(), machineCode, role, maxQtyToWindowEnd, totalScheduledQty,
                        candidateTargetQty, machinePlanQty, segment.getChangeoverShiftIndex(),
                        segment.getStartProductionShiftIndex());
                logNewSpecMachinePlanDecision(sku, quantityPolicy, isEnding, singleMachineWindowFill,
                        candidateTargetQty, maxQtyToWindowEnd, machinePlanQty, null);
                if (machinePlanQty <= 0) {
                    log.debug("新增SKU动态分配后本机台计划量为0, materialCode: {}, 机台: {}, 目标量: {}, 换模开始: {}, 开产时间: {}",
                            sku.getMaterialCode(), machineCode, candidateTargetQty,
                            LhScheduleTimeUtil.formatDateTime(mouldChangeStartTime),
                            LhScheduleTimeUtil.formatDateTime(firstProductionStartTime));
                    inspectionBalance.rollbackInspection(context, inspectionTime);
                    rollbackMouldChangeAllocation(context, sku, mouldChangeBalance, mouldChangeStartTime);
                    excludedMachineCodes.add(machineCode);
                    recordExcludedMachineReason(excludedMachineReasonMap, machineCode,
                            "动态分配后本机台计划量为0",
                            machineReadyTime, switchReadyTime, mouldChangeStartTime, mouldChangeCompleteTime,
                            inspectionTime, firstProductionStartTime, maxQtyToWindowEnd, machinePlanQty, null);
                    failReason = selectHigherPriorityFailReason(
                            failReason, NewSpecFailReasonEnum.NO_CAPACITY_IN_SCHEDULE_WINDOW);
                    continue;
                }
                dynamicTargetQty = candidateTargetQty;
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
                    sku.setTargetScheduleQty(baseTargetScheduleQty);
                    excludedMachineCodes.add(machineCode);
                    recordExcludedMachineReason(excludedMachineReasonMap, machineCode,
                            "结果无有效班次计划量",
                            machineReadyTime, switchReadyTime, mouldChangeStartTime, mouldChangeCompleteTime,
                            inspectionTime, firstProductionStartTime, maxQtyToWindowEnd, machinePlanQty, null);
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
                    sku.setTargetScheduleQty(baseTargetScheduleQty);
                    remainingQty = resolveSchedulableRemainingQty(sku);
                    sku.setRemainingScheduleQty(remainingQty);
                    if (!needMoreMachine(sku)) {
                        break;
                    }
                    excludedMachineCodes.add(machineCode);
                    recordExcludedMachineReason(excludedMachineReasonMap, machineCode,
                            "日计划额度回裁后为0",
                            machineReadyTime, switchReadyTime, mouldChangeStartTime, mouldChangeCompleteTime,
                            inspectionTime, firstProductionStartTime, maxQtyToWindowEnd, machinePlanQty,
                            machineScheduledQty);
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
                progressed = true;
                scheduled = true;
                finalMachine = candidateMachine;
                finalProductionStartTime = firstProductionStartTime;
                // 累计本机台实际排产量，递减多机台剩余量
                totalScheduledQty += machineScheduledQty;
                remainingQty = Math.max(0, dynamicTargetQty - totalScheduledQty);
                sku.setRemainingScheduleQty(remainingQty);
                finalTargetScheduleQty = dynamicTargetQty;
                log.debug("新增排产本机台完成, SKU: {}, 机台: {}, 本机台排产量: {}, 累计已排: {}, 剩余: {}, 满班超排: {}, 机台就绪: {}, 换模开始: {}, 换模结束: {}, 首检开始: {}, 开产时间: {}",
                        sku.getMaterialCode(), machineCode, machineScheduledQty, totalScheduledQty, remainingQty,
                        sku.getShiftFillOverQty(),
                        LhScheduleTimeUtil.formatDateTime(switchReadyTime),
                        LhScheduleTimeUtil.formatDateTime(mouldChangeStartTime),
                        LhScheduleTimeUtil.formatDateTime(mouldChangeCompleteTime),
                        LhScheduleTimeUtil.formatDateTime(inspectionTime),
                        LhScheduleTimeUtil.formatDateTime(productionStartTime));
                logNewSpecMachinePlanDecision(sku, quantityPolicy, isEnding, singleMachineWindowFill,
                        dynamicTargetQty, maxQtyToWindowEnd, machinePlanQty, machineScheduledQty);
                if (remainingQty <= 0 || !needMoreMachine(sku)) {
                    // 全部排完（总量满足 且 每日额度满足），移出待排队列
                    removeCurrentNewSpecSku(context, iterator, sku);
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
                sku.setTargetScheduleQty(baseTargetScheduleQty);
                excludedMachineCodes.add(machineCode);
                recordExcludedMachineReason(excludedMachineReasonMap, machineCode,
                        "本机台已排产但仍有剩余，继续尝试下一台",
                        machineReadyTime, switchReadyTime, mouldChangeStartTime, mouldChangeCompleteTime,
                        inspectionTime, firstProductionStartTime, maxQtyToWindowEnd, machinePlanQty,
                        machineScheduledQty);
                log.info("新增SKU一台机台未排完，继续尝试下一台, materialCode: {}, 本机台: {}, 已排: {}, 剩余: {}",
                        sku.getMaterialCode(), machineCode, totalScheduledQty, remainingQty);
            }

            sku.setTargetScheduleQty(scheduled ? finalTargetScheduleQty : originalTargetScheduleQty);
            if (scheduled && isEnding) {
                adjustSameSkuMultiMachineEndingStagger(context, sku, shifts);
            }
            if (!scheduled) {
                // 所有候选机台都失败，记录未排产原因并移出待排队列
                log.warn("新增SKU排产失败, materialCode: {}, 结构: {}, 规格: {}, 目标量: {}, 候选机台数: {}, 排除机台: {}, 原因: {}",
                        sku.getMaterialCode(), sku.getStructureName(), sku.getSpecCode(),
                        sku.resolveTargetScheduleQty(), candidates.size(), excludedMachineCodes,
                        failReason.getDescription());
                traceNewSpecMachineDecision(context, sku, candidates, localSearchSuggestedMachine, null,
                        excludedMachineCodes, excludedMachineReasonMap, failReason, false, null);
                addUnscheduledResult(context, sku, resolveScheduleFailureReason(sku, failReason),
                        unscheduledReasonCountMap);
                removeCurrentNewSpecSku(context, iterator, sku);
                progressed = true;
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
                    removeCurrentNewSpecSku(context, iterator, sku);
                } else if (remainingQty > 0) {
                    // 总量上仍有剩余（可能来自欠产传导），但日计划额度已满足，移出待排队列
                    log.info("新增SKU日计划额度已满足但总量仍有剩余, materialCode: {}, 已排: {}, 总量剩余: {}, 满班超排: {}",
                            sku.getMaterialCode(), totalScheduledQty, remainingQty, sku.getShiftFillOverQty());
                    removeCurrentNewSpecSku(context, iterator, sku);
                }
                traceNewSpecMachineDecision(context, sku, candidates, localSearchSuggestedMachine, finalMachine,
                        excludedMachineCodes, excludedMachineReasonMap, null, true,
                        PriorityTraceLogHelper.formatDateTime(finalProductionStartTime));
            }
        }
        if (!CollectionUtils.isEmpty(deferredSkuList)) {
            if (deferredResumePrioritized) {
                context.getNewSpecSkuList().addAll(0, deferredSkuList);
            } else {
                context.getNewSpecSkuList().addAll(deferredSkuList);
            }
        }
        return new RoundScheduleSummary(scheduledCount, progressed, deferred);
    }

    /**
     * 初始化新增待排SKU类型计数，供选机阶段日志与特殊机台保护规则复用。
     *
     * @param context 排程上下文
     */
    private void initializePendingNewSpecSkuTypeCounts(LhScheduleContext context) {
        int formalCount = 0;
        int trialCount = 0;
        int massTrialCount = 0;
        int smallBatchCount = 0;
        for (SkuScheduleDTO pendingSku : context.getNewSpecSkuList()) {
            if (isTrialConstructionStage(pendingSku)) {
                trialCount++;
                continue;
            }
            if (isMassTrialSku(pendingSku)) {
                massTrialCount++;
                continue;
            }
            if (isSmallBatchSku(pendingSku)) {
                smallBatchCount++;
                continue;
            }
            formalCount++;
        }
        context.setPendingFormalNewSpecSkuCount(formalCount);
        context.setPendingTrialNewSpecSkuCount(trialCount);
        context.setPendingMassTrialNewSpecSkuCount(massTrialCount);
        context.setPendingSmallBatchNewSpecSkuCount(smallBatchCount);
        log.info("新增待排SKU类型计数初始化, 试制SKU: {}, 量试SKU: {}, 小批量SKU: {}, 正规SKU: {}",
                trialCount, massTrialCount, smallBatchCount, formalCount);
    }

    /**
     * 判断最近一次选机是否被SKU类型机台约束清空候选。
     *
     * @param context 排程上下文
     * @param sku SKU
     * @return true-被类型规则清空
     */
    private boolean isTypeRuleBlocked(LhScheduleContext context, SkuScheduleDTO sku) {
        return context != null
                && sku != null
                && Boolean.TRUE.equals(context.getNewSpecTypeRuleBlockedMap().get(sku));
    }

    /**
     * 移除当前新增待排SKU，并同步更新类型计数。
     *
     * @param context 排程上下文
     * @param iterator 新增SKU迭代器
     * @param sku 当前SKU
     */
    private void removeCurrentNewSpecSku(LhScheduleContext context,
                                         Iterator<SkuScheduleDTO> iterator,
                                         SkuScheduleDTO sku) {
        iterator.remove();
        context.getNewSpecTypeRuleBlockedMap().remove(sku);
        if (isTrialConstructionStage(sku)) {
            context.setPendingTrialNewSpecSkuCount(Math.max(0, context.getPendingTrialNewSpecSkuCount() - 1));
            return;
        }
        if (isMassTrialSku(sku)) {
            context.setPendingMassTrialNewSpecSkuCount(
                    Math.max(0, context.getPendingMassTrialNewSpecSkuCount() - 1));
            return;
        }
        if (isSmallBatchSku(sku)) {
            context.setPendingSmallBatchNewSpecSkuCount(
                    Math.max(0, context.getPendingSmallBatchNewSpecSkuCount() - 1));
            return;
        }
        context.setPendingFormalNewSpecSkuCount(Math.max(0, context.getPendingFormalNewSpecSkuCount() - 1));
    }

    /**
     * 解析无候选机台时的业务原因。
     *
     * @param context 排程上下文
     * @param sku SKU
     * @return 未排原因
     */
    private String resolveNoCandidateMachineReason(LhScheduleContext context, SkuScheduleDTO sku) {
        if (isTypeRuleBlocked(context, sku) && isTrialConstructionStage(sku)) {
            return "试制SKU只能使用单控机台，但当前无可用单控机台或单控机台产能不足，无法排产";
        }
        return "无可用硫化机台";
    }

    /**
     * 解析候选机台尝试失败后的未排原因。
     *
     * @param sku SKU
     * @param failReason 失败原因
     * @return 未排原因
     */
    private String resolveScheduleFailureReason(SkuScheduleDTO sku, NewSpecFailReasonEnum failReason) {
        if (isTrialConstructionStage(sku)
                && NewSpecFailReasonEnum.NO_CAPACITY_IN_SCHEDULE_WINDOW == failReason) {
            return "试制SKU只能使用单控机台，但单控机台已被全局排序更靠前的SKU占用，或当前单控机台产能不足，无法排产";
        }
        return failReason.getDescription();
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
        List<MachineScheduleDTO> singleControlCandidates = filterAvailableCandidatesByMachineType(
                context, candidates, excludedMachineCodes, true);
        List<MachineScheduleDTO> normalCandidates = filterAvailableCandidatesByMachineType(
                context, candidates, excludedMachineCodes, false);
        if (shouldOnlyUseSingleControlCandidate(sku)) {
            MachineScheduleDTO singleControlMachine = selectCandidateMachineFromScopedList(
                    context, sku, singleControlCandidates, machineMatch, preferredTrialMachine, quantityPolicy);
            if (singleControlMachine != null) {
                log.info("新增排产{}SKU仅尝试单控机台, materialCode: {}, machineCode: {}",
                        resolveNewSpecSkuType(sku), sku.getMaterialCode(), singleControlMachine.getMachineCode());
                return singleControlMachine;
            }
            log.info("新增排产{}SKU单控候选均已排除，不回落普通机台, materialCode: {}",
                    resolveNewSpecSkuType(sku), sku.getMaterialCode());
            return null;
        }
        if (isMassTrialOrSmallBatchSku(sku) && !CollectionUtils.isEmpty(singleControlCandidates)) {
            MachineScheduleDTO reusedSingleControlMachine = resolvePreferredSingleControlReuseMachine(
                    context, sku, singleControlCandidates);
            if (reusedSingleControlMachine != null) {
                log.info("新增排产{}SKU优先复用高优先级SKU刚占用的单控机台, materialCode: {}, machineCode: {}",
                        resolveNewSpecSkuType(sku), sku.getMaterialCode(), reusedSingleControlMachine.getMachineCode());
                return reusedSingleControlMachine;
            }
            MachineScheduleDTO singleControlMachine = selectCandidateMachineFromScopedList(
                    context, sku, singleControlCandidates, machineMatch, preferredTrialMachine, quantityPolicy);
            if (singleControlMachine != null) {
                log.info("新增排产{}SKU优先消化单控机台, materialCode: {}, machineCode: {}, remainingSingleControlCount: {}, normalCandidateCount: {}",
                        resolveNewSpecSkuType(sku), sku.getMaterialCode(), singleControlMachine.getMachineCode(),
                        singleControlCandidates.size(), normalCandidates.size());
                return singleControlMachine;
            }
            log.info("新增排产{}SKU单控机台均无法承接，开始尝试普通机台, materialCode: {}, normalCandidateCount: {}",
                    resolveNewSpecSkuType(sku), sku.getMaterialCode(), normalCandidates.size());
            return selectCandidateMachineFromScopedList(
                    context, sku, normalCandidates, machineMatch, null, quantityPolicy);
        }
        MachineScheduleDTO normalMachine = selectCandidateMachineFromScopedList(
                context, sku, normalCandidates, machineMatch, null, quantityPolicy);
        if (normalMachine != null) {
            return normalMachine;
        }
        return selectCandidateMachineFromScopedList(
                context, sku, singleControlCandidates, machineMatch, null, quantityPolicy);
    }

    private MachineScheduleDTO selectCandidateMachineFromScopedList(LhScheduleContext context,
                                                                    SkuScheduleDTO sku,
                                                                    List<MachineScheduleDTO> scopedCandidates,
                                                                    IMachineMatchStrategy machineMatch,
                                                                    MachineScheduleDTO preferredTrialMachine,
                                                                    ProductionQuantityPolicy quantityPolicy) {
        if (CollectionUtils.isEmpty(scopedCandidates)) {
            return null;
        }
        if (preferredTrialMachine != null && containsMachine(scopedCandidates, preferredTrialMachine.getMachineCode())) {
            log.info("新增排产优先尝试试制/小批量预选机台, materialCode: {}, machineCode: {}",
                    sku.getMaterialCode(), preferredTrialMachine.getMachineCode());
            return preferredTrialMachine;
        }
        if (quantityPolicy != null && quantityPolicy.isFullRunForNonTailMachine()) {
            return machineMatch.selectBestMachine(context, sku, scopedCandidates, new HashSet<String>(0));
        }
        MachineScheduleDTO finishRemainingFirstMachine = resolveCanFinishRemainingQtyFirst(
                context, sku, scopedCandidates, new HashSet<String>(0));
        if (finishRemainingFirstMachine != null) {
            log.info("新增排产优先选择可单机收完剩余量的机台, materialCode: {}, machineCode: {}, remainingQty: {}",
                    sku.getMaterialCode(), finishRemainingFirstMachine.getMachineCode(),
                    Math.max(0, sku.getRemainingScheduleQty()));
            return finishRemainingFirstMachine;
        }
        MachineScheduleDTO tailConcentratedMachine = resolveTailConcentratedSplitMachine(
                context, sku, scopedCandidates, new HashSet<String>(0));
        if (tailConcentratedMachine != null) {
            log.info("新增排产优先选择可保留尾量集中能力的机台, materialCode: {}, machineCode: {}, remainingQty: {}",
                    sku.getMaterialCode(), tailConcentratedMachine.getMachineCode(),
                    Math.max(0, sku.getRemainingScheduleQty()));
            return tailConcentratedMachine;
        }
        return machineMatch.selectBestMachine(context, sku, scopedCandidates, new HashSet<String>(0));
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
     * 判断是否为当前配置生效的单控机台。
     *
     * @param context 排程上下文
     * @param machineCode 机台编码
     * @return true-单控机台
     */
    private boolean isSingleControlMachine(LhScheduleContext context, String machineCode) {
        return LhSingleControlMachineUtil.isConfiguredSingleControlMachine(context, machineCode);
    }

    /**
     * 判断当前SKU是否应仅尝试单控候选机台。
     *
     * @param sku SKU
     * @return true-仅尝试单控候选
     */
    private boolean shouldOnlyUseSingleControlCandidate(SkuScheduleDTO sku) {
        if (sku == null) {
            return false;
        }
        if (isTrialConstructionStage(sku)) {
            return true;
        }
        return false;
    }

    private List<MachineScheduleDTO> filterAvailableCandidatesByMachineType(LhScheduleContext context,
                                                                            List<MachineScheduleDTO> candidates,
                                                                            Set<String> excludedMachineCodes,
                                                                            boolean singleControl) {
        List<MachineScheduleDTO> filteredCandidates = new ArrayList<MachineScheduleDTO>(
                CollectionUtils.isEmpty(candidates) ? 0 : candidates.size());
        if (CollectionUtils.isEmpty(candidates)) {
            return filteredCandidates;
        }
        for (MachineScheduleDTO candidate : candidates) {
            if (candidate == null || StringUtils.isEmpty(candidate.getMachineCode())) {
                continue;
            }
            if (!CollectionUtils.isEmpty(excludedMachineCodes)
                    && excludedMachineCodes.contains(candidate.getMachineCode())) {
                continue;
            }
            boolean currentSingleControl = isSingleControlMachine(context, candidate.getMachineCode());
            if (singleControl == currentSingleControl) {
                filteredCandidates.add(candidate);
            }
        }
        return filteredCandidates;
    }

    private boolean containsMachine(List<MachineScheduleDTO> candidates, String machineCode) {
        if (CollectionUtils.isEmpty(candidates) || StringUtils.isEmpty(machineCode)) {
            return false;
        }
        for (MachineScheduleDTO candidate : candidates) {
            if (candidate != null && StringUtils.equals(machineCode, candidate.getMachineCode())) {
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
    private MachineScheduleDTO selectAvailableSingleControlMachine(LhScheduleContext context,
                                                                   List<MachineScheduleDTO> candidates,
                                                                   Set<String> excludedMachineCodes) {
        if (CollectionUtils.isEmpty(candidates)) {
            return null;
        }
        for (MachineScheduleDTO candidate : candidates) {
            if (candidate == null || !isSingleControlMachine(context, candidate.getMachineCode())) {
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
     * 计算当前机台各班次最大可排量。
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
     * @return 班次索引到最大可排量的映射
     */
    private Map<Integer, Integer> calculateShiftCapacityMap(LhScheduleContext context,
                                                            MachineScheduleDTO machine,
                                                            SkuScheduleDTO sku,
                                                            Date firstProductionStartTime,
                                                            Date mouldChangeStartTime,
                                                            List<LhShiftConfigVO> shifts,
                                                            int mouldQty,
                                                            int shiftCapacity,
                                                            boolean isEnding) {
        Map<Integer, Integer> shiftCapacityMap = new LinkedHashMap<Integer, Integer>(
                CollectionUtils.isEmpty(shifts) ? 0 : shifts.size());
        if (context == null || machine == null || sku == null || firstProductionStartTime == null
                || CollectionUtils.isEmpty(shifts)) {
            return shiftCapacityMap;
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
            shiftCapacityMap.put(shift.getShiftIndex(), shiftMaxQty);
            cursorStartTime = control.getEffectiveEndTime();
        }
        return shiftCapacityMap;
    }

    /**
     * 汇总班次可排产能。
     *
     * @param shiftCapacityMap 班次产能映射
     * @return 合计产能
     */
    private int sumShiftCapacity(Map<Integer, Integer> shiftCapacityMap) {
        if (CollectionUtils.isEmpty(shiftCapacityMap)) {
            return 0;
        }
        int totalQty = 0;
        for (Integer shiftCapacity : shiftCapacityMap.values()) {
            totalQty += shiftCapacity == null ? 0 : Math.max(0, shiftCapacity);
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
                                                                   int shiftCapacity,
                                                                   Map<Integer, Integer> shiftCapacityMap) {
        MachineProductionSegment segment = new MachineProductionSegment();
        segment.setMachineCode(machineCode);
        segment.setMaterialCode(sku.getMaterialCode());
        segment.setGreenTireGroupKey(sku.getEmbryoCode());
        segment.setNeedChangeover(true);
        segment.setMaxQtyToWindowEnd(maxQtyToWindowEnd);
        segment.setShiftCapacity(shiftCapacity);
        segment.setShiftCapacityMap(CollectionUtils.isEmpty(shiftCapacityMap)
                ? new LinkedHashMap<Integer, Integer>(0)
                : new LinkedHashMap<Integer, Integer>(shiftCapacityMap));
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
     * 解析新增排产正式/量试非收尾场景的最低目标量。
     * <p>文档口径要求非收尾目标量按窗口 dayN 累计值推进，不直接使用理论窗口满产产能。</p>
     *
     * @param context 排程上下文
     * @param sku SKU
     * @param policy 排产数量策略
     * @return 最低目标量
     */
    private int resolveFormalNonEndingMinimumTargetQty(LhScheduleContext context,
                                                       SkuScheduleDTO sku,
                                                       ProductionQuantityPolicy policy) {
        if (!shouldUseFormalNonEndingMinimumTarget(context, sku, policy)) {
            return sku == null ? 0 : Math.max(0, sku.resolveTargetScheduleQty());
        }
        int windowMinimumTargetQty = Math.max(0, sku.getWindowRemainingPlanQty());
        if (windowMinimumTargetQty <= 0) {
            windowMinimumTargetQty = Math.max(0, sku.getWindowPlanQty());
        }
        if (windowMinimumTargetQty <= 0) {
            windowMinimumTargetQty = SkuDailyPlanQuotaUtil.sumRemainingQty(sku.getDailyPlanQuotaMap());
        }
        if (windowMinimumTargetQty <= 0) {
            return Math.max(0, sku.resolveTargetScheduleQty());
        }
        return windowMinimumTargetQty;
    }

    /**
     * 判断当前是否使用新增排产正式/量试非收尾最低目标量口径。
     *
     * @param context 排程上下文
     * @param sku SKU
     * @param policy 排产数量策略
     * @return true-使用 dayN 累计最低目标量
     */
    private boolean shouldUseFormalNonEndingMinimumTarget(LhScheduleContext context,
                                                          SkuScheduleDTO sku,
                                                          ProductionQuantityPolicy policy) {
        if (context == null || sku == null || policy == null) {
            return false;
        }
        if (policy.isStrictUpperLimit() || !policy.isAllowFillStartedShift()) {
            return false;
        }
        return getTargetScheduleQtyResolver().isFullCapacityMode(context);
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
    private int resolveMachinePlanQty(LhScheduleContext context,
                                      SkuScheduleDTO sku,
                                      ProductionQuantityPolicy policy,
                                      MachineScheduleRole role,
                                      MachineProductionSegment segment,
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
        int tailFilledQty = resolveTailFillPlanQty(context, sku, policy, role, segment, remainingQty);
        if (tailFilledQty > 0) {
            return Math.min(tailFilledQty, maxQtyToWindowEnd);
        }
        int planQty = policy != null && policy.isAllowFillStartedShift()
                ? roundUpToShiftCapacity(remainingQty, shiftCapacity) : remainingQty;
        return Math.min(planQty, maxQtyToWindowEnd);
    }

    /**
     * 判断当前新增SKU是否允许按单机台补满到窗口结束。
     * <p>仅新增规格主链生效：非收尾、非试制，且当前首个成功机台已能独立覆盖窗口目标量时，直接补满到窗口结束。</p>
     *
     * @param sku SKU
     * @param isEnding 是否收尾
     * @param totalScheduledQty 当前SKU已累计排产量
     * @param candidateTargetQty 当前窗口目标量
     * @param maxQtyToWindowEnd 当前机台最大可排量
     * @return true-按单机台补满窗口处理
     */
    private boolean shouldFillSingleMachineToWindowEnd(LhScheduleContext context,
                                                       SkuScheduleDTO sku,
                                                       boolean isEnding,
                                                       int totalScheduledQty,
                                                       int candidateTargetQty,
                                                       int maxQtyToWindowEnd) {
        if (sku == null || isEnding || totalScheduledQty > 0) {
            return false;
        }
        if (StringUtils.equals(ConstructionStageEnum.TRIAL.getCode(), sku.getConstructionStage())) {
            return false;
        }
        if (candidateTargetQty <= 0 || maxQtyToWindowEnd < candidateTargetQty) {
            return false;
        }
        if (!CollectionUtils.isEmpty(sku.getDailyPlanQuotaMap())) {
            return hasMultiDayQuotaWindow(sku) && isOnlyPendingNewSpecSku(context);
        }
        if (!isOnlyPendingNewSpecSku(context)) {
            return false;
        }
        return candidateTargetQty > Math.max(0, sku.getPendingQty());
    }

    /**
     * 判断当前SKU是否带有多日窗口账本。
     *
     * @param sku SKU
     * @return true-多日窗口账本；false-仅单日或无账本
     */
    private boolean hasMultiDayQuotaWindow(SkuScheduleDTO sku) {
        return sku != null && !CollectionUtils.isEmpty(sku.getDailyPlanQuotaMap())
                && sku.getDailyPlanQuotaMap().size() > 1;
    }

    /**
     * 判断当前新增待排队列是否只剩当前SKU。
     *
     * @param context 排程上下文
     * @return true-只剩一个待排SKU；false-仍有后续SKU需要保留窗口产能
     */
    private boolean isOnlyPendingNewSpecSku(LhScheduleContext context) {
        return context != null && !CollectionUtils.isEmpty(context.getNewSpecSkuList())
                && context.getNewSpecSkuList().size() == 1;
    }

    /**
     * 正规非收尾多机台场景下，若后续 dayN 账本仍有可借额度，
     * 尾机台应补满当前可生产段，避免只排部分班次。
     *
     * @param context 排程上下文
     * @param sku SKU
     * @param policy 排产数量策略
     * @param role 机台角色
     * @param segment 当前机台生产段
     * @param remainingQty 本轮窗口剩余目标量
     * @return 尾机台补满量；0-沿用默认尾量逻辑
     */
    private int resolveTailFillPlanQty(LhScheduleContext context,
                                       SkuScheduleDTO sku,
                                       ProductionQuantityPolicy policy,
                                       MachineScheduleRole role,
                                       MachineProductionSegment segment,
                                       int remainingQty) {
        if (sku == null || policy == null || role != MachineScheduleRole.TAIL_MACHINE
                || segment == null || CollectionUtils.isEmpty(segment.getShiftCapacityMap())) {
            return 0;
        }
        if (!policy.isAllowFillStartedShift() || policy.isStrictUpperLimit()) {
            return 0;
        }
        if (!shouldUseFormalNonEndingMinimumTarget(context, sku, policy)) {
            return 0;
        }
        int remainingQuotaQty = SkuDailyPlanQuotaUtil.sumRemainingQty(sku.getDailyPlanQuotaMap());
        boolean multiDayQuota = hasMultiplePositiveQuotaDays(sku);
        if (!multiDayQuota && remainingQuotaQty <= remainingQty) {
            return 0;
        }
        int roundedRemainingQty = roundUpToShiftCapacity(remainingQty, segment.getShiftCapacity());
        int tailFilledQty = roundedRemainingQty + Math.max(0, segment.getShiftCapacity());
        if (tailFilledQty <= roundedRemainingQty) {
            return 0;
        }
        return Math.min(tailFilledQty, segment.getMaxQtyToWindowEnd());
    }

    /**
     * 新增SKU按dayN欠产节奏提前给后续机台留量。
     * <p>该方法只影响新增排产多候选机台场景；收尾场景参与动态拆量但仍严格截断，试制等严格目标场景沿用原有语义。</p>
     *
     * @param context 排程上下文
     * @param sku SKU
     * @param candidates 候选机台
     * @param excludedMachineCodes 已排除机台
     * @param policy 排产数量策略
     * @param segment 当前机台生产段
     * @param targetQty 窗口目标量
     * @param scheduledQty 当前已排量
     * @param defaultPlanQty 原计划量
     * @return 当前机台计划量
     */
    private int resolveDynamicMachinePlanQtyByDailyCapacity(LhScheduleContext context,
                                                            SkuScheduleDTO sku,
                                                            List<MachineScheduleDTO> candidates,
                                                            Set<String> excludedMachineCodes,
                                                            ProductionQuantityPolicy policy,
                                                            MachineProductionSegment segment,
                                                            MachineScheduleDTO candidateMachine,
                                                            List<LhShiftConfigVO> shifts,
                                                            ICapacityCalculateStrategy capacityCalculate,
                                                            int targetQty,
                                                            int scheduledQty,
                                                            int defaultPlanQty) {
        if (!shouldUseDailyDynamicMachineAllocation(sku, candidates, excludedMachineCodes, policy, segment)) {
            return defaultPlanQty;
        }
        if (MachineScheduleRole.FULL_RUN_MACHINE == segment.getRole()
                && shouldUseFormalNonEndingMinimumTarget(context, sku, policy)
                && hasMultiplePositiveQuotaDays(sku)) {
            return defaultPlanQty;
        }
        int remainingTargetQty = Math.max(0, targetQty - scheduledQty);
        if (remainingTargetQty <= 0 || defaultPlanQty <= 0) {
            return defaultPlanQty;
        }
        boolean needAddMachineByTotal = scheduledQty + segment.getMaxQtyToWindowEnd() < targetQty;
        int availableMachineCount = countAvailableCandidateMachines(candidates, excludedMachineCodes);
        int requiredMachineCountByDailyCapacity = resolveRequiredMachineCountByDailyCapacity(
                context, sku, candidates, excludedMachineCodes, policy, segment, candidateMachine,
                shifts, capacityCalculate, remainingTargetQty, availableMachineCount);
        boolean needAddMachineByDailyCapacity = requiredMachineCountByDailyCapacity > 1;
        if (!needAddMachineByTotal && !needAddMachineByDailyCapacity) {
            if (policy.isAllowFillStartedShift()) {
                return resolveSettledTailMachinePlanQty(segment, remainingTargetQty, defaultPlanQty);
            }
            return Math.min(remainingTargetQty, segment.getMaxQtyToWindowEnd());
        }
        if (availableMachineCount <= 1) {
            return defaultPlanQty;
        }
        int requiredMachineCount = resolveRequiredMachineCount(
                remainingTargetQty, segment.getMaxQtyToWindowEnd(), availableMachineCount,
                requiredMachineCountByDailyCapacity);
        int balancedPlanQty = roundUpToShiftCapacity(
                divideCeiling(remainingTargetQty, requiredMachineCount), segment.getShiftCapacity());
        balancedPlanQty = Math.min(balancedPlanQty, segment.getMaxQtyToWindowEnd());
        balancedPlanQty = Math.min(balancedPlanQty, remainingTargetQty);
        if (balancedPlanQty <= 0) {
            return defaultPlanQty;
        }
        log.info("新增SKU按dayN节奏动态扩机台, materialCode: {}, 当前机台: {}, 已排: {}, 目标: {}, "
                        + "默认计划量: {}, 动态计划量: {}, 可用候选数: {}, 预计机台数: {}, dayN推导机台数: {}",
                sku.getMaterialCode(), segment.getMachineCode(), scheduledQty, targetQty, defaultPlanQty,
                balancedPlanQty, availableMachineCount, requiredMachineCount, requiredMachineCountByDailyCapacity);
        return balancedPlanQty;
    }

    /**
     * 当前窗口 dayN 模拟已确认不需要继续扩机时，尾机台只保留满足剩余需求所需的满班量，
     * 不再额外多吃一整班，避免尾量跨到下一业务日。
     *
     * @param segment 当前机台生产段
     * @param remainingTargetQty 剩余目标量
     * @param defaultPlanQty 原计划量
     * @return 收敛后的计划量
     */
    private int resolveSettledTailMachinePlanQty(MachineProductionSegment segment,
                                                 int remainingTargetQty,
                                                 int defaultPlanQty) {
        if (segment == null || segment.getRole() != MachineScheduleRole.TAIL_MACHINE || defaultPlanQty <= 0) {
            return defaultPlanQty;
        }
        int roundedRemainingQty = roundUpToShiftCapacity(remainingTargetQty, segment.getShiftCapacity());
        if (roundedRemainingQty <= 0 || roundedRemainingQty >= defaultPlanQty) {
            return defaultPlanQty;
        }
        return Math.min(roundedRemainingQty, segment.getMaxQtyToWindowEnd());
    }

    /**
     * 记录新增SKU当前机台计划量的最终决策摘要，便于排查单机台补满窗口与严格目标量的差异。
     *
     * @param sku SKU
     * @param policy 排产数量策略
     * @param isEnding 是否收尾
     * @param isSingleMachine 是否命中单机台补满窗口
     * @param targetQty 当前窗口目标量
     * @param maxQtyToWindowEnd 当前机台最大可排量
     * @param finalPlanQty 当前机台最终计划量
     * @param actualScheduledQty 当前机台实际落地量
     */
    private void logNewSpecMachinePlanDecision(SkuScheduleDTO sku,
                                               ProductionQuantityPolicy policy,
                                               boolean isEnding,
                                               boolean isSingleMachine,
                                               int targetQty,
                                               int maxQtyToWindowEnd,
                                               int finalPlanQty,
                                               Integer actualScheduledQty) {
        if (sku == null || policy == null) {
            return;
        }
        log.info("新增SKU机台计划量决策, materialCode: {}, skuType: {}, isEnding: {}, isTrial: {}, "
                        + "isSmallBatch: {}, isSingleMachine: {}, targetQty: {}, maxQtyToWindowEnd: {}, "
                        + "finalPlanQty: {}, actualScheduledQty: {}, allowOverTarget: {}",
                sku.getMaterialCode(), resolveNewSpecSkuType(sku), isEnding,
                StringUtils.equals(ConstructionStageEnum.TRIAL.getCode(), sku.getConstructionStage()),
                sku.isSmallBatchValidation(), isSingleMachine, targetQty, maxQtyToWindowEnd,
                finalPlanQty, actualScheduledQty, policy.isAllowFillStartedShift());
    }

    /**
     * 判断当前SKU是否使用新增多机台动态拆量。
     *
     * @param sku SKU
     * @param candidates 候选机台
     * @param excludedMachineCodes 已排除机台
     * @param policy 排产数量策略
     * @param segment 当前机台生产段
     * @return true-使用动态拆量；false-沿用原逻辑
     */
    private boolean shouldUseDailyDynamicMachineAllocation(SkuScheduleDTO sku,
                                                           List<MachineScheduleDTO> candidates,
                                                           Set<String> excludedMachineCodes,
                                                           ProductionQuantityPolicy policy,
                                                           MachineProductionSegment segment) {
        if (sku == null || policy == null || segment == null) {
            return false;
        }
        if (policy.isStrictUpperLimit() && !policy.isEnding()) {
            return false;
        }
        if (CollectionUtils.isEmpty(sku.getDailyPlanQuotaMap()) || CollectionUtils.isEmpty(candidates)) {
            return false;
        }
        return candidates.size() > 1 && countAvailableCandidateMachines(candidates, excludedMachineCodes) > 0;
    }

    /**
     * 判断单台机台在当前追补窗口内是否无法消化dayN欠产。
     *
     * @param context 排程上下文
     * @param sku SKU
     * @param segment 当前机台生产段
     * @param remainingTargetQty 本轮窗口剩余目标量
     * @return true-需要提前增加机台；false-当前机台可覆盖追补窗口
     */
    private int resolveRequiredMachineCountByDailyCapacity(LhScheduleContext context,
                                                           SkuScheduleDTO sku,
                                                           List<MachineScheduleDTO> candidates,
                                                           Set<String> excludedMachineCodes,
                                                           ProductionQuantityPolicy policy,
                                                           MachineProductionSegment segment,
                                                           MachineScheduleDTO candidateMachine,
                                                           List<LhShiftConfigVO> shifts,
                                                           ICapacityCalculateStrategy capacityCalculate,
                                                           int remainingTargetQty,
                                                           int availableMachineCount) {
        if (sku == null || segment == null || CollectionUtils.isEmpty(sku.getDailyPlanQuotaMap())
                || CollectionUtils.isEmpty(candidates) || CollectionUtils.isEmpty(shifts)
                || candidateMachine == null || capacityCalculate == null) {
            return 0;
        }
        if (availableMachineCount <= 0) {
            return 0;
        }
        DailyMachineCapacitySimulationRequest request = new DailyMachineCapacitySimulationRequest();
        request.setMaterialCode(sku.getMaterialCode());
        request.setDailyPlanQuotaMap(buildSimulationQuotaMap(sku.getDailyPlanQuotaMap(), remainingTargetQty));
        List<Map<LocalDate, Integer>> existingMachineCapacityMaps = buildExistingSameMaterialCapacityMaps(
                context, sku, candidateMachine, shifts, request.getDailyPlanQuotaMap());
        request.setMachineDailyCapacityList(buildSimulationMachineCapacityList(
                context, sku, candidates, excludedMachineCodes, policy, segment, candidateMachine,
                shifts, capacityCalculate, request.getDailyPlanQuotaMap(), existingMachineCapacityMaps));
        request.setInitialActiveMachines(Math.max(1, existingMachineCapacityMaps.size() + 1));
        request.setShortageLookAheadDays(resolveNewSpecShortageLookAheadDays(context));
        request.setWindowEndDate(resolveScheduleTargetLocalDate(context));
        request.setSceneType("newSpec");
        DailyMachineCapacitySimulationResult simulationResult =
                DailyMachineCapacitySimulationUtil.simulateExpansion(request);
        logDailyMachineCapacitySimulation(sku, segment, simulationResult);
        return resolveRequiredNewSpecMachineCount(simulationResult.getFinalActiveMachines(),
                existingMachineCapacityMaps.size());
    }

    /**
     * 构建新增排产 dayN 模拟账本快照。
     *
     * @param quotaMap 原日计划账本
     * @param remainingTargetQty 本轮剩余目标量
     * @return 模拟账本
     */
    private Map<LocalDate, SkuDailyPlanQuotaDTO> buildSimulationQuotaMap(
            Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap,
            int remainingTargetQty) {
        Map<LocalDate, SkuDailyPlanQuotaDTO> simulationQuotaMap =
                new LinkedHashMap<LocalDate, SkuDailyPlanQuotaDTO>(Math.max(4, quotaMap.size() * 2));
        int remainingLimitQty = Math.max(0, remainingTargetQty);
        boolean hasTargetLimit = remainingTargetQty > 0;
        for (Map.Entry<LocalDate, SkuDailyPlanQuotaDTO> entry : quotaMap.entrySet()) {
            SkuDailyPlanQuotaDTO sourceQuota = entry.getValue();
            if (sourceQuota == null) {
                continue;
            }
            SkuDailyPlanQuotaDTO quota = new SkuDailyPlanQuotaDTO();
            quota.setMaterialCode(sourceQuota.getMaterialCode());
            quota.setProductionDate(sourceQuota.getProductionDate());
            int remainingQty = Math.max(0, sourceQuota.getRemainingQty());
            int dayPlanQty = Math.max(0, sourceQuota.getDayPlanQty());
            if (hasTargetLimit) {
                remainingQty = Math.min(remainingQty, remainingLimitQty);
                dayPlanQty = Math.min(dayPlanQty, remainingQty);
                remainingLimitQty -= remainingQty;
            }
            quota.setDayPlanQty(dayPlanQty);
            quota.setRemainingQty(remainingQty);
            simulationQuotaMap.put(entry.getKey(), quota);
        }
        return simulationQuotaMap;
    }

    /**
     * 构建候选机台日产能模拟列表。
     *
     * @param context 排程上下文
     * @param segment 当前生产段
     * @param quotaMap 模拟账本
     * @param availableMachineCount 可用候选机台数
     * @return 候选机台日产能列表
     */
    private List<Map<LocalDate, Integer>> buildSimulationMachineCapacityList(
            LhScheduleContext context,
            SkuScheduleDTO sku,
            List<MachineScheduleDTO> candidates,
            Set<String> excludedMachineCodes,
            ProductionQuantityPolicy policy,
            MachineProductionSegment currentSegment,
            MachineScheduleDTO currentMachine,
            List<LhShiftConfigVO> shifts,
            ICapacityCalculateStrategy capacityCalculate,
            Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap,
            List<Map<LocalDate, Integer>> existingMachineCapacityMaps) {
        List<Map<LocalDate, Integer>> machineCapacityList =
                new ArrayList<Map<LocalDate, Integer>>(Math.max(1, candidates.size())
                        + (CollectionUtils.isEmpty(existingMachineCapacityMaps) ? 0 : existingMachineCapacityMaps.size()));
        if (!CollectionUtils.isEmpty(existingMachineCapacityMaps)) {
            machineCapacityList.addAll(existingMachineCapacityMaps);
        }
        for (MachineScheduleDTO candidate : candidates) {
            if (candidate == null || StringUtils.isEmpty(candidate.getMachineCode())) {
                continue;
            }
            if (!CollectionUtils.isEmpty(excludedMachineCodes)
                    && excludedMachineCodes.contains(candidate.getMachineCode())) {
                continue;
            }
            if (StringUtils.equals(candidate.getMachineCode(), currentMachine.getMachineCode())) {
                machineCapacityList.add(buildSimulationCurrentMachineCapacityMap(context, currentSegment, quotaMap));
                continue;
            }
            machineCapacityList.add(buildSimulationCandidateCapacityMap(
                    context, sku, candidate, policy, shifts, capacityCalculate, quotaMap));
        }
        return machineCapacityList;
    }

    /**
     * 收集当前 SKU 在进入 S4.5 前已经落地的同 SKU 机台日产能图。
     * <p>换活字块与本轮前面已排出的新增结果都视为已启用机台，需要参与 dayN 扩机判断。</p>
     *
     * @param context 排程上下文
     * @param sku 当前 SKU
     * @param currentMachine 当前候选机台
     * @param shifts 排程窗口班次
     * @param quotaMap 模拟账本
     * @return 已启用机台日产能图列表
     */
    private List<Map<LocalDate, Integer>> buildExistingSameMaterialCapacityMaps(
            LhScheduleContext context,
            SkuScheduleDTO sku,
            MachineScheduleDTO currentMachine,
            List<LhShiftConfigVO> shifts,
            Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap) {
        List<Map<LocalDate, Integer>> existingMachineCapacityMaps = new ArrayList<Map<LocalDate, Integer>>(4);
        if (context == null || sku == null || CollectionUtils.isEmpty(context.getScheduleResultList())
                || CollectionUtils.isEmpty(shifts) || CollectionUtils.isEmpty(quotaMap)) {
            return existingMachineCapacityMaps;
        }
        Set<String> addedMachineCodes = new HashSet<String>(4);
        String currentMachineCode = currentMachine == null ? null : currentMachine.getMachineCode();
        for (LhScheduleResult result : context.getScheduleResultList()) {
            if (!isExistingSameMaterialActiveResult(result, sku.getMaterialCode(), currentMachineCode)) {
                continue;
            }
            if (!addedMachineCodes.add(result.getLhMachineCode())) {
                continue;
            }
            Map<LocalDate, Integer> capacityMap = buildExistingResultDailyCapacityMap(result, shifts, quotaMap);
            if (!hasPositiveDailyCapacity(capacityMap)) {
                continue;
            }
            existingMachineCapacityMaps.add(capacityMap);
        }
        return existingMachineCapacityMaps;
    }

    /**
     * 判断结果是否属于当前 SKU 已启用的同 SKU 机台。
     *
     * @param result 排程结果
     * @param materialCode 当前物料编码
     * @param currentMachineCode 当前候选机台编码
     * @return true-属于已启用机台
     */
    private boolean isExistingSameMaterialActiveResult(LhScheduleResult result,
                                                       String materialCode,
                                                       String currentMachineCode) {
        if (result == null
                || StringUtils.isEmpty(materialCode)
                || !StringUtils.equals(materialCode, result.getMaterialCode())
                || StringUtils.equals(currentMachineCode, result.getLhMachineCode())
                || StringUtils.isEmpty(result.getLhMachineCode())
                || resolveResultScheduledQty(result) <= 0) {
            return false;
        }
        return StringUtils.equals(NEW_SPEC_SCHEDULE_TYPE, result.getScheduleType())
                || StringUtils.equals(ScheduleTypeEnum.TYPE_BLOCK.getCode(), result.getScheduleType());
    }

    /**
     * 将既有结果按业务日折算为 dayN 模拟产能图。
     *
     * @param result 既有排程结果
     * @param shifts 排程窗口班次
     * @param quotaMap 模拟账本
     * @return 该结果的业务日产能图
     */
    private Map<LocalDate, Integer> buildExistingResultDailyCapacityMap(
            LhScheduleResult result,
            List<LhShiftConfigVO> shifts,
            Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap) {
        Map<LocalDate, Integer> capacityMap =
                new LinkedHashMap<LocalDate, Integer>(Math.max(4, quotaMap.size() * 2));
        for (LocalDate productionDate : quotaMap.keySet()) {
            capacityMap.put(productionDate, 0);
        }
        for (LhShiftConfigVO shift : shifts) {
            if (shift == null || shift.getWorkDate() == null || shift.getShiftIndex() == null) {
                continue;
            }
            LocalDate productionDate = shift.getWorkDate().toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
            if (!capacityMap.containsKey(productionDate)) {
                continue;
            }
            Integer shiftPlanQty = ShiftFieldUtil.getShiftPlanQty(result, shift.getShiftIndex());
            if (shiftPlanQty == null || shiftPlanQty <= 0) {
                continue;
            }
            capacityMap.merge(productionDate, shiftPlanQty, Integer::sum);
        }
        return capacityMap;
    }

    /**
     * 判断日产能图是否存在有效产能。
     *
     * @param capacityMap 日产能图
     * @return true-存在有效产能
     */
    private boolean hasPositiveDailyCapacity(Map<LocalDate, Integer> capacityMap) {
        if (CollectionUtils.isEmpty(capacityMap)) {
            return false;
        }
        for (Integer capacityQty : capacityMap.values()) {
            if (capacityQty != null && capacityQty > 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * 将 dayN 模拟返回的总启用机台数回算为当前新增阶段仍需启用的机台数。
     *
     * @param finalActiveMachines 模拟最终总启用机台数
     * @param existingMachineCount 已存在的同 SKU 机台数
     * @return 当前新增阶段需要启用的机台数（含当前机台）
     */
    private int resolveRequiredNewSpecMachineCount(int finalActiveMachines, int existingMachineCount) {
        return Math.max(1, Math.max(0, finalActiveMachines) - Math.max(0, existingMachineCount));
    }

    /**
     * 构建当前候选机台的 dayN 模拟产能图。
     *
     * @param context 排程上下文
     * @param currentSegment 当前机台生产段
     * @param quotaMap 模拟账本
     * @return 当前机台产能图
     */
    private Map<LocalDate, Integer> buildSimulationCurrentMachineCapacityMap(
            LhScheduleContext context,
            MachineProductionSegment currentSegment,
            Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap) {
        Map<LocalDate, Integer> currentMachineCapacityMap =
                new LinkedHashMap<LocalDate, Integer>(Math.max(4, quotaMap.size() * 2));
        for (LocalDate productionDate : quotaMap.keySet()) {
            currentMachineCapacityMap.put(productionDate,
                    sumSegmentCapacityByWorkDate(context, currentSegment, productionDate));
        }
        return currentMachineCapacityMap;
    }

    /**
     * 构建其他候选机台的 dayN 模拟产能图。
     *
     * @param context 排程上下文
     * @param sku SKU
     * @param candidate 候选机台
     * @param policy 排产数量策略
     * @param shifts 排程窗口班次
     * @param capacityCalculate 机台起排策略
     * @param quotaMap 模拟账本
     * @return 候选机台产能图
     */
    private Map<LocalDate, Integer> buildSimulationCandidateCapacityMap(
            LhScheduleContext context,
            SkuScheduleDTO sku,
            MachineScheduleDTO candidate,
            ProductionQuantityPolicy policy,
            List<LhShiftConfigVO> shifts,
            ICapacityCalculateStrategy capacityCalculate,
            Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap) {
        Map<LocalDate, Integer> capacityMap =
                new LinkedHashMap<LocalDate, Integer>(Math.max(4, quotaMap.size() * 2));
        if (context == null || sku == null || candidate == null || CollectionUtils.isEmpty(shifts)
                || capacityCalculate == null) {
            return capacityMap;
        }
        Date endingTime = candidate.getEstimatedEndTime() != null
                ? candidate.getEstimatedEndTime() : resolveDefaultMachineEndTime(context, shifts);
        Date machineReadyTime = capacityCalculate.calculateStartTime(context, candidate.getMachineCode(), endingTime);
        boolean maintenanceOverlapSwitch = getMaintenanceScheduleService()
                .shouldApplyMaintenanceOverlapSwitchRule(context, candidate, endingTime);
        Date switchReadyTime = maintenanceOverlapSwitch
                ? getMaintenanceScheduleService().resolveMaintenanceEndTime(context, candidate)
                : machineReadyTime;
        switchReadyTime = resolveSpecifyReservedReadyTime(context, sku, candidate.getMachineCode(), switchReadyTime);
        switchReadyTime = ShiftProductionControlUtil.resolveEarliestSwitchStartTime(context, switchReadyTime);
        int switchDurationHours = maintenanceOverlapSwitch
                ? LhScheduleTimeUtil.getMaintenanceOverlapSwitchHours(context)
                : LhScheduleTimeUtil.getMouldChangeTotalHours(context);
        Date mouldChangeStartTime = switchReadyTime;
        Date mouldChangeCompleteTime = LhScheduleTimeUtil.addHours(mouldChangeStartTime, switchDurationHours);
        Date productionStartTime = maintenanceOverlapSwitch
                ? LhScheduleTimeUtil.addHours(
                mouldChangeCompleteTime, LhScheduleTimeUtil.getFirstInspectionHours(context))
                : mouldChangeCompleteTime;
        int machineMouldQty = ShiftCapacityResolverUtil.resolveMachineMouldQty(candidate);
        int runtimeShiftCapacity = ShiftCapacityResolverUtil.resolveRuntimeShiftCapacity(
                context, candidate, sku.getShiftCapacity());
        Date firstProductionStartTime = ShiftProductionControlUtil.resolveFirstSchedulableStartIgnoringCleaning(
                context,
                candidate.getMachineCode(),
                productionStartTime,
                shifts,
                runtimeShiftCapacity,
                sku.getLhTimeSeconds(),
                machineMouldQty);
        if (firstProductionStartTime == null) {
            return capacityMap;
        }
        Map<Integer, Integer> shiftCapacityMap = calculateShiftCapacityMap(
                context, candidate, sku, firstProductionStartTime, mouldChangeStartTime,
                shifts, machineMouldQty, runtimeShiftCapacity, policy != null && policy.isEnding());
        MachineProductionSegment simulationSegment = buildMachineProductionSegment(
                context, sku, candidate.getMachineCode(), mouldChangeStartTime,
                firstProductionStartTime, sumShiftCapacity(shiftCapacityMap),
                runtimeShiftCapacity, shiftCapacityMap);
        for (LocalDate productionDate : quotaMap.keySet()) {
            capacityMap.put(productionDate, sumSegmentCapacityByWorkDate(
                    context, simulationSegment, productionDate));
        }
        return capacityMap;
    }

    /**
     * 输出 dayN 机台模拟过程日志。
     *
     * @param sku SKU
     * @param segment 当前生产段
     * @param simulationResult 模拟结果
     */
    private void logDailyMachineCapacitySimulation(SkuScheduleDTO sku,
                                                   MachineProductionSegment segment,
                                                   DailyMachineCapacitySimulationResult simulationResult) {
        if (sku == null || segment == null || simulationResult == null
                || CollectionUtils.isEmpty(simulationResult.getDayDecisionList())) {
            return;
        }
        for (DailyMachineCapacityDayDecision decision : simulationResult.getDayDecisionList()) {
            log.info("新增SKU dayN机台模拟, materialCode: {}, 当前机台: {}, 日期: {}, 追补截止: {}, "
                            + "dayN计划: {}, carryShortage: {}, 当日需求: {}, 当日产能: {}, "
                            + "当日欠产: {}, 累计需求: {}, 累计产能: {}, 启用机台: {}, 新增机台: {}, "
                            + "未满足: {}, 原因: {}",
                    sku.getMaterialCode(), segment.getMachineCode(), decision.getProductionDate(),
                    decision.getLookAheadEndDate(), decision.getTodayPlanQty(), decision.getCarryShortageQty(),
                    decision.getTodayRequiredQty(), decision.getTodayCapacityQty(), decision.getDayShortageQty(),
                    decision.getDemandQty(), decision.getCapacityQty(),
                    decision.getActiveMachineCount(), decision.getAddedMachineCount(),
                    decision.getUnmetQty(), decision.getReason());
        }
    }

    /**
     * 获取新增排产欠产追补判断天数。
     * <p>该值表示当前天发生欠产后，额外向后看几天，不包含当前天。</p>
     *
     * @param context 排程上下文
     * @return 向后观察天数（不含当天）
     */
    private int resolveNewSpecShortageLookAheadDays(LhScheduleContext context) {
        LhScheduleConfig scheduleConfig = context == null ? null : context.getScheduleConfig();
        if (scheduleConfig == null) {
            return LhScheduleConstant.NEW_SPEC_SHORTAGE_LOOK_AHEAD_DAYS;
        }
        return scheduleConfig.getNewSpecShortageLookAheadDays();
    }

    /**
     * 汇总生产段在指定业务日的可排产能。
     *
     * @param context 排程上下文
     * @param segment 当前机台生产段
     * @param productionDate 业务日
     * @return 该业务日产能
     */
    private int sumSegmentCapacityByWorkDate(LhScheduleContext context,
                                             MachineProductionSegment segment,
                                             LocalDate productionDate) {
        if (context == null || segment == null || productionDate == null
                || CollectionUtils.isEmpty(segment.getShiftCapacityMap())) {
            return 0;
        }
        int totalQty = 0;
        for (Map.Entry<Integer, Integer> entry : segment.getShiftCapacityMap().entrySet()) {
            LhShiftConfigVO shift = LhScheduleTimeUtil.getShiftByIndex(
                    context, context.getScheduleDate(), entry.getKey());
            if (shift == null || shift.getWorkDate() == null) {
                continue;
            }
            LocalDate shiftWorkDate = shift.getWorkDate().toInstant()
                    .atZone(ZoneId.systemDefault()).toLocalDate();
            if (productionDate.equals(shiftWorkDate)) {
                totalQty += entry.getValue() == null ? 0 : Math.max(0, entry.getValue());
            }
        }
        return Math.max(0, totalQty);
    }

    /**
     * 判断当前SKU的 dayN 账本是否跨多个业务日仍存在有效目标量。
     *
     * @param sku SKU
     * @return true-存在多个业务日计划量；false-仅单日目标
     */
    private boolean hasMultiplePositiveQuotaDays(SkuScheduleDTO sku) {
        if (sku == null || CollectionUtils.isEmpty(sku.getDailyPlanQuotaMap())) {
            return false;
        }
        int positiveDays = 0;
        for (SkuDailyPlanQuotaDTO quota : sku.getDailyPlanQuotaMap().values()) {
            if (quota == null) {
                continue;
            }
            int effectiveQty = Math.max(0, quota.getRemainingQty());
            if (effectiveQty <= 0) {
                effectiveQty = Math.max(0, quota.getDayPlanQty());
            }
            if (effectiveQty <= 0) {
                continue;
            }
            positiveDays++;
            if (positiveDays > 1) {
                return true;
            }
        }
        return false;
    }

    /**
     * 计算当前剩余目标量至少需要的机台数。
     *
     * @param remainingTargetQty 剩余目标量
     * @param currentMachineCapacity 当前机台窗口产能
     * @param availableMachineCount 可用候选机台数
     * @param needAddMachineByDailyCapacity 是否因dayN追补能力不足扩机台
     * @return 预计机台数
     */
    private int resolveRequiredMachineCount(int remainingTargetQty,
                                            int currentMachineCapacity,
                                            int availableMachineCount,
                                            int requiredMachineCountByDailyCapacity) {
        int capacityBasedCount = currentMachineCapacity <= 0
                ? availableMachineCount : divideCeiling(remainingTargetQty, currentMachineCapacity);
        int requiredMachineCount = Math.max(1, capacityBasedCount);
        if (requiredMachineCountByDailyCapacity > 0) {
            requiredMachineCount = Math.max(requiredMachineCount, requiredMachineCountByDailyCapacity);
        }
        return Math.max(1, Math.min(requiredMachineCount, availableMachineCount));
    }

    /**
     * 解析当前候选机台对应的窗口目标量。
     * <p>满排模式下，当只剩当前一台候选机台时，需要按该机台真实窗口产能收敛目标量，
     * 避免把理论窗口产能直接带入单机结果构造。</p>
     *
     * @param context 排程上下文
     * @param sku SKU
     * @param candidateMachine 当前候选机台
     * @param mouldChangeStartTime 换模开始时间
     * @param firstProductionStartTime 首次可开产时间
     * @param shifts 排程窗口班次
     * @param dynamicTargetQty 当前窗口目标量
     * @param totalScheduledQty 当前SKU已累计排产量
     * @param excludedMachineCodes 已排除机台
     * @param quantityPolicy 数量策略
     * @return 当前候选机台生效的窗口目标量
     */
    private int resolveCandidateTargetQty(LhScheduleContext context,
                                          SkuScheduleDTO sku,
                                          MachineScheduleDTO candidateMachine,
                                          Date mouldChangeStartTime,
                                          Date firstProductionStartTime,
                                          List<LhShiftConfigVO> shifts,
                                          List<MachineScheduleDTO> candidates,
                                          int dynamicTargetQty,
                                          int totalScheduledQty,
                                          Set<String> excludedMachineCodes,
                                          ProductionQuantityPolicy quantityPolicy) {
        if (context == null || sku == null || candidateMachine == null || quantityPolicy == null) {
            return Math.max(0, dynamicTargetQty);
        }
        if (quantityPolicy.isStrictUpperLimit()) {
            return Math.max(0, dynamicTargetQty);
        }
        int availableMachineCount = countAvailableCandidateMachines(candidates, excludedMachineCodes);
        if (totalScheduledQty > 0 || availableMachineCount > 1) {
            return Math.max(0, dynamicTargetQty);
        }
        Integer originalTargetScheduleQty = sku.getTargetScheduleQty();
        sku.setTargetScheduleQty(dynamicTargetQty);
        int refinedTargetQty = getTargetScheduleQtyResolver().refineTargetQtyByMachineCapacity(
                context, sku, candidateMachine, mouldChangeStartTime, firstProductionStartTime, shifts);
        sku.setTargetScheduleQty(originalTargetScheduleQty);
        return Math.max(0, refinedTargetQty);
    }

    /**
     * 统计尚可尝试的候选机台数量。
     *
     * @param candidates 候选机台
     * @param excludedMachineCodes 已排除机台编码
     * @return 可用候选数
     */
    private int countAvailableCandidateMachines(List<MachineScheduleDTO> candidates,
                                                Set<String> excludedMachineCodes) {
        if (CollectionUtils.isEmpty(candidates)) {
            return 0;
        }
        int count = 0;
        for (MachineScheduleDTO candidate : candidates) {
            if (candidate == null || StringUtils.isEmpty(candidate.getMachineCode())) {
                continue;
            }
            if (!CollectionUtils.isEmpty(excludedMachineCodes)
                    && excludedMachineCodes.contains(candidate.getMachineCode())) {
                continue;
            }
            count++;
        }
        return count;
    }

    /**
     * 向上整除。
     *
     * @param dividend 被除数
     * @param divisor 除数
     * @return 向上取整后的商
     */
    private int divideCeiling(int dividend, int divisor) {
        if (dividend <= 0) {
            return 0;
        }
        if (divisor <= 0) {
            return dividend;
        }
        return (dividend + divisor - 1) / divisor;
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
                if (isSingleControlMachine(context, candidate.getMachineCode())) {
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
                    && !isSingleControlMachine(context, candidate.getMachineCode())) {
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
     * 判断是否为量试或小批量SKU。
     * <p>isTrial 仅作为试制/量试总标识兼容，不按试制强约束处理。</p>
     *
     * @param sku SKU
     * @return true-量试或小批量
     */
    private boolean isMassTrialOrSmallBatchSku(SkuScheduleDTO sku) {
        return isMassTrialSku(sku) || isSmallBatchSku(sku);
    }

    private boolean shouldDeferSingleControlCompetition(LhScheduleContext context,
                                                        SkuScheduleDTO currentSku,
                                                        List<MachineScheduleDTO> candidates,
                                                        IMachineMatchStrategy machineMatch) {
        if (context == null || currentSku == null || CollectionUtils.isEmpty(candidates)
                || !isMassTrialOrSmallBatchSku(currentSku)
                || !hasAvailableSingleControlCandidate(context, candidates)) {
            return false;
        }
        if (!isStructureAllEndingPriority(context, currentSku)) {
            return false;
        }
        Set<String> currentSingleControlMachineCodes = collectSingleControlMachineCodes(context, candidates);
        if (CollectionUtils.isEmpty(currentSingleControlMachineCodes)) {
            return false;
        }
        for (SkuScheduleDTO pendingSku : context.getNewSpecSkuList()) {
            if (pendingSku == currentSku || !isHigherSingleControlPriority(pendingSku, currentSku)) {
                continue;
            }
            if (shouldSkipTrialSku(context, pendingSku)) {
                continue;
            }
            if (isSameStructureEndingLayer(context, currentSku, pendingSku)
                    && hasSharedSingleControlCandidates(context, pendingSku, currentSingleControlMachineCodes, machineMatch)) {
                return true;
            }
        }
        return false;
    }

    private MachineScheduleDTO resolvePreferredSingleControlReuseMachine(LhScheduleContext context,
                                                                         SkuScheduleDTO currentSku,
                                                                         List<MachineScheduleDTO> singleControlCandidates) {
        if (context == null || currentSku == null || CollectionUtils.isEmpty(singleControlCandidates)) {
            return null;
        }
        for (int index = context.getScheduleResultList().size() - 1; index >= 0; index--) {
            LhScheduleResult result = context.getScheduleResultList().get(index);
            if (result == null || !StringUtils.equals(NEW_SPEC_SCHEDULE_TYPE, result.getScheduleType())) {
                continue;
            }
            if (!isSingleControlMachine(context, result.getLhMachineCode())) {
                continue;
            }
            SkuScheduleDTO sourceSku = context.getScheduleResultSourceSkuMap().get(result);
            if (sourceSku == null || !isHigherSingleControlPriority(sourceSku, currentSku)
                    || !isSameStructureEndingLayer(context, currentSku, sourceSku)) {
                continue;
            }
            for (MachineScheduleDTO candidate : singleControlCandidates) {
                if (candidate != null && StringUtils.equals(result.getLhMachineCode(), candidate.getMachineCode())) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private boolean hasAvailableSingleControlCandidate(LhScheduleContext context, List<MachineScheduleDTO> candidates) {
        if (context == null || CollectionUtils.isEmpty(candidates)) {
            return false;
        }
        for (MachineScheduleDTO candidate : candidates) {
            if (candidate != null && isSingleControlMachine(context, candidate.getMachineCode())) {
                return true;
            }
        }
        return false;
    }

    private Set<String> collectSingleControlMachineCodes(LhScheduleContext context, List<MachineScheduleDTO> candidates) {
        Set<String> singleControlMachineCodes = new HashSet<String>(
                CollectionUtils.isEmpty(candidates) ? 0 : candidates.size());
        if (context == null || CollectionUtils.isEmpty(candidates)) {
            return singleControlMachineCodes;
        }
        for (MachineScheduleDTO candidate : candidates) {
            if (candidate == null || StringUtils.isEmpty(candidate.getMachineCode())) {
                continue;
            }
            if (isSingleControlMachine(context, candidate.getMachineCode())) {
                singleControlMachineCodes.add(candidate.getMachineCode());
            }
        }
        return singleControlMachineCodes;
    }

    private boolean hasSharedSingleControlCandidates(LhScheduleContext context,
                                                     SkuScheduleDTO sku,
                                                     Set<String> currentSingleControlMachineCodes,
                                                     IMachineMatchStrategy machineMatch) {
        if (context == null || sku == null || CollectionUtils.isEmpty(currentSingleControlMachineCodes)
                || machineMatch == null) {
            return false;
        }
        Boolean previousBlockedState = context.getNewSpecTypeRuleBlockedMap().get(sku);
        List<MachineScheduleDTO> higherPriorityCandidates = machineMatch.matchMachines(context, sku);
        if (previousBlockedState == null) {
            context.getNewSpecTypeRuleBlockedMap().remove(sku);
        } else {
            context.getNewSpecTypeRuleBlockedMap().put(sku, previousBlockedState);
        }
        if (CollectionUtils.isEmpty(higherPriorityCandidates)) {
            return false;
        }
        for (MachineScheduleDTO candidate : higherPriorityCandidates) {
            if (candidate == null || StringUtils.isEmpty(candidate.getMachineCode())) {
                continue;
            }
            if (currentSingleControlMachineCodes.contains(candidate.getMachineCode())
                    && isSingleControlMachine(context, candidate.getMachineCode())) {
                return true;
            }
        }
        return false;
    }

    private boolean isHigherSingleControlPriority(SkuScheduleDTO pendingSku, SkuScheduleDTO currentSku) {
        return resolveSingleControlCompetitionPriority(pendingSku)
                < resolveSingleControlCompetitionPriority(currentSku);
    }

    private int resolveSingleControlCompetitionPriority(SkuScheduleDTO sku) {
        if (isTrialConstructionStage(sku)) {
            return 0;
        }
        if (isMassTrialSku(sku)) {
            return 1;
        }
        if (isSmallBatchSku(sku)) {
            return 2;
        }
        return Integer.MAX_VALUE;
    }

    private boolean isSameStructureEndingLayer(LhScheduleContext context,
                                               SkuScheduleDTO currentSku,
                                               SkuScheduleDTO pendingSku) {
        if (context == null || currentSku == null || pendingSku == null) {
            return false;
        }
        return hitSingleControlStructureEndingLayer(context, currentSku)
                && hitSingleControlStructureEndingLayer(context, pendingSku);
    }

    /**
     * 判断SKU是否命中单控竞争使用的结构五天内收尾层级。
     * <p>对仍在待排列表中的SKU，沿用现有“同结构SKU全部收尾”的判定；</p>
     * <p>对已排出待排列表的高优先级SKU，退化为校验该SKU自身是否命中结构收尾窗口，保证量试可复用试制刚释放的单控产能。</p>
     *
     * @param context 排程上下文
     * @param targetSku 目标SKU
     * @return true-命中单控竞争结构收尾层级
     */
    private boolean hitSingleControlStructureEndingLayer(LhScheduleContext context, SkuScheduleDTO targetSku) {
        if (context == null || targetSku == null) {
            return false;
        }
        Boolean snapshotResult = context.getNewSpecSingleControlStructureEndingLayerMap().get(targetSku);
        if (snapshotResult != null) {
            return snapshotResult;
        }
        return isStructureAllEndingPriority(context, targetSku);
    }

    private void initializeSingleControlStructureEndingLayerSnapshot(LhScheduleContext context) {
        if (context == null) {
            return;
        }
        Map<SkuScheduleDTO, Boolean> snapshotMap = context.getNewSpecSingleControlStructureEndingLayerMap();
        snapshotMap.clear();
        if (CollectionUtils.isEmpty(context.getNewSpecSkuList())) {
            return;
        }
        for (SkuScheduleDTO sku : context.getNewSpecSkuList()) {
            if (sku == null) {
                continue;
            }
            snapshotMap.put(sku, isStructureAllEndingPriority(context, sku));
        }
    }

    private boolean shouldPrioritizeDeferredSingleControlSku(LhScheduleContext context,
                                                             SkuScheduleDTO currentSku,
                                                             List<SkuScheduleDTO> deferredSkuList) {
        if (context == null || currentSku == null || CollectionUtils.isEmpty(deferredSkuList)) {
            return false;
        }
        boolean hasLowerPriorityDeferredSku = false;
        for (SkuScheduleDTO deferredSku : deferredSkuList) {
            if (deferredSku == null || !isMassTrialOrSmallBatchSku(deferredSku)) {
                continue;
            }
            hasLowerPriorityDeferredSku = true;
            if (isSameStructureEndingLayer(context, deferredSku, currentSku)
                    && isHigherSingleControlPriority(currentSku, deferredSku)) {
                return false;
            }
        }
        if (!hasLowerPriorityDeferredSku) {
            return false;
        }
        for (SkuScheduleDTO deferredSku : deferredSkuList) {
            if (deferredSku != null
                    && isMassTrialOrSmallBatchSku(deferredSku)
                    && !isSameStructureEndingLayer(context, deferredSku, currentSku)) {
                return true;
            }
        }
        return false;
    }

    private boolean isStructureAllEndingPriority(LhScheduleContext context, SkuScheduleDTO targetSku) {
        if (context == null || targetSku == null || StringUtils.isEmpty(targetSku.getStructureName())) {
            return false;
        }
        int structureEndingDays = context.getScheduleConfig() != null
                ? context.getScheduleConfig().getStructureEndingDays()
                : LhScheduleConstant.DEFAULT_STRUCTURE_ENDING_DAYS;
        int totalSkuCount = 0;
        int endingSkuCount = 0;
        int latestEndingDays = -1;
        for (SkuScheduleDTO pendingSku : context.getNewSpecSkuList()) {
            if (pendingSku == null || !StringUtils.equals(targetSku.getStructureName(), pendingSku.getStructureName())) {
                continue;
            }
            totalSkuCount++;
            if (!endingJudgmentStrategy.isEnding(context, pendingSku)) {
                continue;
            }
            endingSkuCount++;
            int actualEndingDays = endingJudgmentStrategy.calculateEndingDaysForStructurePriority(context, pendingSku);
            if (actualEndingDays >= 0) {
                latestEndingDays = Math.max(latestEndingDays, actualEndingDays);
            }
        }
        return totalSkuCount > 0
                && endingSkuCount == totalSkuCount
                && latestEndingDays >= 0
                && latestEndingDays <= structureEndingDays;
    }

    /**
     * 判断是否为量试SKU。
     * <p>isTrial 仅作为试制/量试总标识兼容，不按试制强约束处理。</p>
     *
     * @param sku SKU
     * @return true-量试
     */
    private boolean isMassTrialSku(SkuScheduleDTO sku) {
        if (sku == null) {
            return false;
        }
        return StringUtils.equals(ConstructionStageEnum.MASS_TRIAL.getCode(), sku.getConstructionStage())
                || sku.isTrial();
    }

    /**
     * 判断是否为小批量SKU。
     *
     * @param sku SKU
     * @return true-小批量
     */
    private boolean isSmallBatchSku(SkuScheduleDTO sku) {
        return sku != null && sku.isSmallBatchValidation();
    }

    /**
     * 判断是否为试制、量试或小批量SKU。
     *
     * @param sku SKU
     * @return true-试制、量试或小批量
     */
    private boolean isTrialOrMassTrialOrSmallBatchSku(SkuScheduleDTO sku) {
        return isTrialConstructionStage(sku) || isMassTrialOrSmallBatchSku(sku);
    }

    /**
     * 判断是否为正规SKU。
     *
     * @param sku SKU
     * @return true-正规SKU
     */
    private boolean isFormalSku(SkuScheduleDTO sku) {
        return sku != null && !isTrialOrMassTrialOrSmallBatchSku(sku);
    }

    private static class RoundScheduleSummary {
        private final int scheduledCount;
        private final boolean progressed;
        private final boolean deferred;

        private RoundScheduleSummary(int scheduledCount, boolean progressed, boolean deferred) {
            this.scheduledCount = scheduledCount;
            this.progressed = progressed;
            this.deferred = deferred;
        }

        private int getScheduledCount() {
            return scheduledCount;
        }

        private boolean isProgressed() {
            return progressed;
        }

        private boolean isDeferred() {
            return deferred;
        }
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
     * @param excludedMachineReasonMap 已排除机台原因明细
     * @param failReason 失败原因
     * @param success 是否成功
     * @param startTimeText 开产时间文本或附加说明
     */
    private void traceNewSpecMachineDecision(LhScheduleContext context, SkuScheduleDTO sku,
                                             List<MachineScheduleDTO> candidates,
                                             MachineScheduleDTO localSearchSuggestedMachine,
                                             MachineScheduleDTO finalMachine,
                                             Set<String> excludedMachineCodes,
                                             Map<String, String> excludedMachineReasonMap,
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
                        + ", " + PriorityTraceLogHelper.kv("是否收尾", PriorityTraceLogHelper.oneZero(isEnding))
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
                boolean isSingleCtrl = isSingleControlMachine(context, machine.getMachineCode());
                String reasonSuffix = (i == 0 && success && finalMachine != null
                        && StringUtils.equals(machine.getMachineCode(), finalMachine.getMachineCode()))
                        ? "最优候选" : ("候选" + (i + 1));
                PriorityTraceLogHelper.appendLine(detailBuilder,
                        (i + 1)
                                + ". " + PriorityTraceLogHelper.kv("机台", machine.getMachineCode())
                                + ", " + PriorityTraceLogHelper.kv("名称", machine.getMachineName())
                                + ", " + PriorityTraceLogHelper.kv("单控", PriorityTraceLogHelper.oneZero(isSingleCtrl))
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

        appendExcludedMachineReasonTrace(detailBuilder, excludedMachineReasonMap);

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
     * 记录候选机台排除原因明细。
     *
     * @param excludedMachineReasonMap 排除原因明细
     * @param machineCode 机台编码
     * @param reason 排除原因
     * @param machineReadyTime 机台就绪时间
     * @param switchReadyTime 切换就绪时间
     * @param mouldChangeStartTime 换模开始时间
     * @param mouldChangeCompleteTime 换模完成时间
     * @param inspectionTime 首检时间
     * @param productionStartTime 开产时间
     * @param maxQtyToWindowEnd 窗口最大可排量
     * @param machinePlanQty 本机台计划量
     * @param machineScheduledQty 日计划回裁后排产量
     */
    private void recordExcludedMachineReason(Map<String, String> excludedMachineReasonMap,
                                             String machineCode,
                                             String reason,
                                             Date machineReadyTime,
                                             Date switchReadyTime,
                                             Date mouldChangeStartTime,
                                             Date mouldChangeCompleteTime,
                                             Date inspectionTime,
                                             Date productionStartTime,
                                             Integer maxQtyToWindowEnd,
                                             Integer machinePlanQty,
                                             Integer machineScheduledQty) {
        if (excludedMachineReasonMap == null || StringUtils.isEmpty(machineCode)) {
            return;
        }
        StringBuilder reasonBuilder = new StringBuilder(256);
        reasonBuilder.append(PriorityTraceLogHelper.kv("排除原因", reason));
        reasonBuilder.append(", ").append(PriorityTraceLogHelper.kv("机台就绪",
                LhScheduleTimeUtil.formatDateTime(machineReadyTime)));
        reasonBuilder.append(", ").append(PriorityTraceLogHelper.kv("切换就绪",
                LhScheduleTimeUtil.formatDateTime(switchReadyTime)));
        reasonBuilder.append(", ").append(PriorityTraceLogHelper.kv("换模开始",
                LhScheduleTimeUtil.formatDateTime(mouldChangeStartTime)));
        reasonBuilder.append(", ").append(PriorityTraceLogHelper.kv("换模完成",
                LhScheduleTimeUtil.formatDateTime(mouldChangeCompleteTime)));
        reasonBuilder.append(", ").append(PriorityTraceLogHelper.kv("首检",
                LhScheduleTimeUtil.formatDateTime(inspectionTime)));
        reasonBuilder.append(", ").append(PriorityTraceLogHelper.kv("开产",
                LhScheduleTimeUtil.formatDateTime(productionStartTime)));
        reasonBuilder.append(", ").append(PriorityTraceLogHelper.kv("最大可排量", maxQtyToWindowEnd));
        reasonBuilder.append(", ").append(PriorityTraceLogHelper.kv("本机台计划量", machinePlanQty));
        reasonBuilder.append(", ").append(PriorityTraceLogHelper.kv("日计划回裁量", machineScheduledQty));
        excludedMachineReasonMap.put(machineCode, reasonBuilder.toString());
    }

    /**
     * 输出候选机台排除原因明细。
     *
     * @param detailBuilder 日志明细
     * @param excludedMachineReasonMap 排除原因明细
     */
    private void appendExcludedMachineReasonTrace(StringBuilder detailBuilder,
                                                  Map<String, String> excludedMachineReasonMap) {
        if (detailBuilder == null || CollectionUtils.isEmpty(excludedMachineReasonMap)) {
            return;
        }
        PriorityTraceLogHelper.appendLine(detailBuilder, "排除明细:");
        for (Map.Entry<String, String> entry : excludedMachineReasonMap.entrySet()) {
            PriorityTraceLogHelper.appendLine(detailBuilder,
                    "- " + PriorityTraceLogHelper.kv("机台", entry.getKey())
                            + ", " + PriorityTraceLogHelper.safeText(entry.getValue()));
        }
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
        result.setChangedTrialStatus(constructionRef != null ? constructionRef.getLhType() : null);
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
     * 同SKU多机台收尾时，针对早班/中班同班次收尾做尾量错开。
     * <p>仅处理新增排产收尾结果；晚班不调整，避免破坏“晚班不能换模”下的有效产能。</p>
     *
     * @param context 排程上下文
     * @param sku 当前SKU
     * @param shifts 排程窗口班次
     */
    private void adjustSameSkuMultiMachineEndingStagger(LhScheduleContext context,
                                                        SkuScheduleDTO sku,
                                                        List<LhShiftConfigVO> shifts) {
        if (context == null || sku == null || CollectionUtils.isEmpty(shifts)
                || CollectionUtils.isEmpty(context.getScheduleResultList())) {
            return;
        }
        List<LhScheduleResult> sameSkuEndingResults = new ArrayList<>(4);
        for (LhScheduleResult result : context.getScheduleResultList()) {
            if (result == null
                    || !NEW_SPEC_SCHEDULE_TYPE.equals(result.getScheduleType())
                    || !"1".equals(result.getIsEnd())
                    || "1".equals(result.getIsTypeBlock())
                    || !StringUtils.equals(sku.getMaterialCode(), result.getMaterialCode())
                    || ShiftFieldUtil.resolveScheduledQty(result) <= 0) {
                continue;
            }
            sameSkuEndingResults.add(result);
        }
        if (sameSkuEndingResults.size() < 2) {
            return;
        }
        Map<Integer, List<LhScheduleResult>> endingShiftResultMap = new LinkedHashMap<>(4);
        for (LhScheduleResult result : sameSkuEndingResults) {
            int lastShiftIndex = resolveLastPlannedShiftIndex(result);
            if (lastShiftIndex <= 0) {
                continue;
            }
            endingShiftResultMap.computeIfAbsent(lastShiftIndex, key -> new ArrayList<LhScheduleResult>(2))
                    .add(result);
        }
        for (Map.Entry<Integer, List<LhScheduleResult>> entry : endingShiftResultMap.entrySet()) {
            if (entry.getValue().size() >= 2) {
                tryStaggerSameShiftEnding(context, sku, shifts, entry.getKey(), entry.getValue());
            }
        }
    }

    /**
     * 尝试将同班次收尾中的一台尾量转移到另一台后续班次，释放被转出台机台。
     *
     * @param context 排程上下文
     * @param sku 当前SKU
     * @param shifts 排程窗口班次
     * @param endingShiftIndex 同班次收尾索引
     * @param results 同班次收尾结果
     * @return true-已调整；false-未调整
     */
    private boolean tryStaggerSameShiftEnding(LhScheduleContext context,
                                              SkuScheduleDTO sku,
                                              List<LhShiftConfigVO> shifts,
                                              int endingShiftIndex,
                                              List<LhScheduleResult> results) {
        LhShiftConfigVO endingShift = findShiftByIndex(shifts, endingShiftIndex);
        LhShiftConfigVO nextShift = findShiftByIndex(shifts, endingShiftIndex + 1);
        if (endingShift == null || nextShift == null
                || StringUtils.equals(ShiftEnum.NIGHT_SHIFT.getCode(), endingShift.getShiftType())) {
            return false;
        }
        if (!isSameWorkDate(endingShift.getWorkDate(), nextShift.getWorkDate())) {
            return false;
        }
        LhScheduleResult donor = resolveTailDonorResult(results, endingShiftIndex);
        LhScheduleResult receiver = resolveTailReceiverResult(results, donor, endingShiftIndex + 1);
        if (donor == null || receiver == null) {
            return false;
        }
        Integer donorQty = ShiftFieldUtil.getShiftPlanQty(donor, endingShiftIndex);
        if (donorQty == null || donorQty <= 0) {
            return false;
        }
        int nextShiftCapacity = resolveAvailableShiftQtyForEndingStagger(context, receiver, nextShift);
        if (nextShiftCapacity <= 0 || donorQty > nextShiftCapacity) {
            return false;
        }
        setShiftPlanQty(donor, endingShiftIndex, 0, null, null);
        setShiftPlanQty(receiver, nextShift.getShiftIndex(), donorQty, nextShift.getShiftStartDateTime(), null);
        refreshResultSummary(context, donor);
        refreshResultSummary(context, receiver);
        refreshMachineStateAfterEndingStagger(context, donor);
        refreshMachineStateAfterEndingStagger(context, receiver);
        log.info("同SKU多机台收尾尾量错开, materialCode: {}, 释放机台: {}, 承接机台: {}, "
                        + "原收尾班次: {}, 承接班次: {}, 转移数量: {}",
                sku.getMaterialCode(), donor.getLhMachineCode(), receiver.getLhMachineCode(),
                endingShiftIndex, nextShift.getShiftIndex(), donorQty);
        return true;
    }

    private LhScheduleResult resolveTailDonorResult(List<LhScheduleResult> results, int endingShiftIndex) {
        LhScheduleResult donor = null;
        int minEndingQty = Integer.MAX_VALUE;
        for (LhScheduleResult result : results) {
            Integer qty = ShiftFieldUtil.getShiftPlanQty(result, endingShiftIndex);
            if (qty == null || qty <= 0) {
                continue;
            }
            if (qty < minEndingQty) {
                donor = result;
                minEndingQty = qty;
            }
        }
        return donor;
    }

    private LhScheduleResult resolveTailReceiverResult(List<LhScheduleResult> results,
                                                       LhScheduleResult donor,
                                                       int nextShiftIndex) {
        for (LhScheduleResult result : results) {
            if (result == null || result == donor) {
                continue;
            }
            Integer nextShiftQty = ShiftFieldUtil.getShiftPlanQty(result, nextShiftIndex);
            if (nextShiftQty == null || nextShiftQty <= 0) {
                return result;
            }
        }
        return null;
    }

    /**
     * 判断两个班次是否归属同一业务日。
     *
     * @param firstWorkDate 第一个班次业务日
     * @param secondWorkDate 第二个班次业务日
     * @return true-同一业务日；false-不同业务日
     */
    private boolean isSameWorkDate(Date firstWorkDate, Date secondWorkDate) {
        if (firstWorkDate == null || secondWorkDate == null) {
            return false;
        }
        LocalDate firstDate = firstWorkDate.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
        LocalDate secondDate = secondWorkDate.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
        return firstDate.equals(secondDate);
    }

    private int resolveAvailableShiftQtyForEndingStagger(LhScheduleContext context,
                                                         LhScheduleResult result,
                                                         LhShiftConfigVO targetShift) {
        if (context == null || result == null || targetShift == null
                || StringUtils.isEmpty(result.getLhMachineCode())
                || result.getLhTime() == null || result.getLhTime() <= 0
                || result.getMouldQty() == null || result.getMouldQty() <= 0) {
            return 0;
        }
        Date shiftStartTime = targetShift.getShiftStartDateTime();
        Date shiftEndTime = targetShift.getShiftEndDateTime();
        if (shiftStartTime == null || shiftEndTime == null || !shiftStartTime.before(shiftEndTime)) {
            return 0;
        }
        ShiftProductionControlDTO control = ShiftProductionControlUtil.resolveEffectiveControl(
                context, targetShift, shiftStartTime);
        if (control == null || !control.isCanSchedule()) {
            return 0;
        }
        MachineScheduleDTO machine = context.getMachineScheduleMap().get(result.getLhMachineCode());
        int baseShiftCapacity = resolveResultBaseShiftCapacity(result);
        int runtimeShiftCapacity = machine == null ? 0 : ShiftCapacityResolverUtil.resolveRuntimeShiftCapacity(
                context, machine, baseShiftCapacity);
        int shiftCapacity = runtimeShiftCapacity > 0 ? runtimeShiftCapacity : baseShiftCapacity;
        if (shiftCapacity <= 0) {
            return 0;
        }
        int dryIceLossQty = context.getParamIntValue(
                LhScheduleParamConstant.DRY_ICE_LOSS_QTY, LhScheduleConstant.DRY_ICE_LOSS_QTY);
        int dryIceDurationHours = context.getParamIntValue(
                LhScheduleParamConstant.DRY_ICE_DURATION_HOURS, LhScheduleConstant.DRY_ICE_DURATION_HOURS);
        int shiftMaxQty = ShiftCapacityResolverUtil.resolveShiftCapacityWithDowntime(
                context.getDevicePlanShutList(),
                resolveMachineCleaningWindowList(context, result.getLhMachineCode()),
                resolveMachineMaintenanceWindowList(context, result.getLhMachineCode()),
                result.getLhMachineCode(),
                control.getEffectiveStartTime(),
                control.getEffectiveEndTime(),
                shiftCapacity,
                result.getLhTime(),
                result.getMouldQty(),
                ShiftCapacityResolverUtil.resolveShiftDurationSeconds(targetShift),
                dryIceLossQty,
                dryIceDurationHours);
        return Math.max(0, ShiftProductionControlUtil.deductCapacityByControl(
                control, shiftMaxQty, result.getMouldQty()));
    }

    private int resolveLastPlannedShiftIndex(LhScheduleResult result) {
        for (int shiftIndex = LhScheduleConstant.MAX_SHIFT_SLOT_COUNT; shiftIndex >= 1; shiftIndex--) {
            Integer qty = ShiftFieldUtil.getShiftPlanQty(result, shiftIndex);
            if (qty != null && qty > 0) {
                return shiftIndex;
            }
        }
        return -1;
    }

    private int resolveResultBaseShiftCapacity(LhScheduleResult result) {
        if (result == null) {
            return 0;
        }
        if (result.getSingleMouldShiftQty() != null && result.getSingleMouldShiftQty() > 0) {
            return result.getSingleMouldShiftQty();
        }
        int maxShiftQty = 0;
        for (int shiftIndex = 1; shiftIndex <= LhScheduleConstant.MAX_SHIFT_SLOT_COUNT; shiftIndex++) {
            Integer shiftQty = ShiftFieldUtil.getShiftPlanQty(result, shiftIndex);
            if (shiftQty != null && shiftQty > maxShiftQty) {
                maxShiftQty = shiftQty;
            }
        }
        return maxShiftQty;
    }

    private LhShiftConfigVO findShiftByIndex(List<LhShiftConfigVO> shifts, int shiftIndex) {
        if (CollectionUtils.isEmpty(shifts)) {
            return null;
        }
        for (LhShiftConfigVO shift : shifts) {
            if (shift != null && shift.getShiftIndex() != null && shift.getShiftIndex() == shiftIndex) {
                return shift;
            }
        }
        return null;
    }

    private void refreshMachineStateAfterEndingStagger(LhScheduleContext context, LhScheduleResult result) {
        if (context == null || result == null || StringUtils.isEmpty(result.getLhMachineCode())) {
            return;
        }
        MachineScheduleDTO machine = context.getMachineScheduleMap().get(result.getLhMachineCode());
        if (machine == null) {
            return;
        }
        machine.setEstimatedEndTime(result.getSpecEndTime());
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
            int planQty = resolveResultScheduledQty(result);
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
        int surplusQty = sku != null ? Math.max(0, sku.getSurplusQty())
                : Math.max(0, result.getMouldSurplusQty() == null ? 0 : result.getMouldSurplusQty());
        int embryoStock = sku != null ? Math.max(0, sku.getEmbryoStock())
                : Math.max(0, result.getEmbryoStock() == null ? 0 : result.getEmbryoStock());
        return Math.max(surplusQty, embryoStock);
    }

    private int resolveResultScheduledQty(LhScheduleResult result) {
        int scheduledQty = ShiftFieldUtil.resolveScheduledQty(result);
        if (scheduledQty > 0) {
            return scheduledQty;
        }
        return result != null && result.getDailyPlanQty() != null ? Math.max(0, result.getDailyPlanQty()) : 0;
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
        syncResultDowntimeSummary(context, result);
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

    private List<MdmDevicePlanShut> resolveMachineShutdownWindowList(LhScheduleContext context, String machineCode) {
        if (context == null || CollectionUtils.isEmpty(context.getDevicePlanShutList())
                || StringUtils.isEmpty(machineCode)) {
            return new ArrayList<>();
        }
        List<MdmDevicePlanShut> shutdownWindowList = new ArrayList<>(4);
        for (MdmDevicePlanShut planShut : context.getDevicePlanShutList()) {
            if (planShut != null && StringUtils.equals(machineCode, planShut.getMachineCode())) {
                shutdownWindowList.add(planShut);
            }
        }
        return shutdownWindowList;
    }

    private void syncResultDowntimeSummary(LhScheduleContext context, LhScheduleResult result) {
        if (context == null || result == null) {
            return;
        }
        Date firstPlannedShiftStartTime = resolveFirstPlannedShiftStartTime(result);
        if (firstPlannedShiftStartTime == null || result.getSpecEndTime() == null) {
            ResultDowntimeSummaryUtil.clearDowntimeSummary(result);
            return;
        }
        ResultDowntimeSummaryUtil.fillDowntimeSummary(
                result,
                resolveMachineMaintenanceWindowList(context, result.getLhMachineCode()),
                resolveEffectiveCleaningWindowList(
                        context, result.getLhMachineCode(), result.getMouldChangeStartTime(), firstPlannedShiftStartTime),
                resolveMachineShutdownWindowList(context, result.getLhMachineCode()));
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
            if (!hasGreenTireChangeoverConflict(context, sku.getEmbryoCode(), shiftIndex, sku.getMaterialCode())) {
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
                                                   int shiftIndex,
                                                   String materialCode) {
        if (context == null || StringUtils.isEmpty(greenTireGroupKey) || shiftIndex <= 0) {
            return false;
        }
        Set<Integer> occupiedShiftSet = context.getGreenTireChangeoverShiftMap().get(greenTireGroupKey);
        if (CollectionUtils.isEmpty(occupiedShiftSet) || !occupiedShiftSet.contains(shiftIndex)) {
            return false;
        }
        return hasOtherSkuGreenTireChangeoverOccupancy(context, greenTireGroupKey, shiftIndex, materialCode);
    }

    /**
     * 判断指定班次上的同胎胚换模占用是否来自其他SKU。
     *
     * @param context 排程上下文
     * @param greenTireGroupKey 胎胚分组Key
     * @param shiftIndex 班次索引
     * @param materialCode 当前SKU物料编码
     * @return true-存在其他SKU占用；false-仅当前SKU占用
     */
    private boolean hasOtherSkuGreenTireChangeoverOccupancy(LhScheduleContext context,
                                                            String greenTireGroupKey,
                                                            int shiftIndex,
                                                            String materialCode) {
        if (context == null || CollectionUtils.isEmpty(context.getScheduleResultList())) {
            return true;
        }
        for (LhScheduleResult result : context.getScheduleResultList()) {
            if (!shouldTrackGreenTireChangeoverResult(result)
                    || !StringUtils.equals(greenTireGroupKey, result.getEmbryoCode())) {
                continue;
            }
            Date changeoverStartTime = resolveExistingGreenTireChangeoverStartTime(result);
            if (changeoverStartTime == null) {
                continue;
            }
            int occupiedShiftIndex = LhScheduleTimeUtil.getShiftIndex(
                    context, context.getScheduleDate(), changeoverStartTime);
            if (occupiedShiftIndex != shiftIndex) {
                continue;
            }
            if (!StringUtils.equals(materialCode, result.getMaterialCode())) {
                return true;
            }
        }
        return false;
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
            // 按历史欠产、当日计划、受限追补窗口消费同一SKU的日计划账本
            int consumed = SkuDailyPlanQuotaUtil.consumeRollingQuota(
                    quotaMap, productionDate, planQty, resolveLookAheadEndDate(context, quotaMap, productionDate));
            int overQty = planQty - consumed;
            if (overQty > 0) {
                boolean endingResult = "1".equals(result.getIsEnd());
                // 收尾结果必须严格截断，且不再记录满班补齐超排；
                // 试制等严格目标量场景仍需回裁，但保留超排账本用于追踪被截掉的补满量。
                if (endingResult || (sku != null && sku.isStrictTargetQty())
                        || shouldTrimUnavailableQuota(sku)) {
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
     * 判断日计划额度耗尽后是否需要回裁结果行。
     * <p>没有窗口目标量依据时，不允许把无法扣账的班次量继续留在结果行。</p>
     *
     * @param sku SKU排程DTO
     * @return true-需要回裁；false-允许保留满班补齐量
     */
    private boolean shouldTrimUnavailableQuota(SkuScheduleDTO sku) {
        if (sku == null) {
            return true;
        }
        return sku.getWindowPlanQty() <= 0 && sku.getWindowRemainingPlanQty() <= 0;
    }

    /**
     * 解析新增排产实际扣账允许追补的截止日期。
     *
     * @param context 排程上下文
     * @param quotaMap 日计划账本
     * @param productionDate 实际生产日期
     * @return 追补截止日期
     */
    private LocalDate resolveLookAheadEndDate(LhScheduleContext context,
                                              Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap,
                                              LocalDate productionDate) {
        return SkuDailyPlanQuotaUtil.resolveLookAheadEndDate(
                quotaMap, productionDate, resolveNewSpecShortageLookAheadDays(context),
                resolveScheduleTargetLocalDate(context));
    }

    /**
     * 解析排程目标业务日期。
     *
     * @param context 排程上下文
     * @return 排程目标业务日期
     */
    private LocalDate resolveScheduleTargetLocalDate(LhScheduleContext context) {
        if (context == null) {
            return null;
        }
        if (!CollectionUtils.isEmpty(context.getScheduleWindowShifts())) {
            for (int index = context.getScheduleWindowShifts().size() - 1; index >= 0; index--) {
                LhShiftConfigVO shift = context.getScheduleWindowShifts().get(index);
                if (shift != null && shift.getWorkDate() != null) {
                    return shift.getWorkDate().toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
                }
            }
        }
        if (context.getScheduleTargetDate() == null) {
            return null;
        }
        return context.getScheduleTargetDate().toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
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
