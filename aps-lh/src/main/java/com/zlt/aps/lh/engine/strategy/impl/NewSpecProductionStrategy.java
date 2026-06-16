/**
 * Copyright (c) 2008, 智立通（厦门）科技有限公司 All rights reserved。
 */
package com.zlt.aps.lh.engine.strategy.impl;

import cn.hutool.core.bean.BeanUtil;
import com.zlt.aps.lh.api.constant.LhScheduleConstant;
import com.zlt.aps.lh.api.constant.LhScheduleParamConstant;
import com.zlt.aps.lh.api.domain.dto.MachineCleaningWindowDTO;
import com.zlt.aps.lh.api.domain.dto.MachineMaintenanceWindowDTO;
import com.zlt.aps.lh.api.domain.dto.SkuDailyPlanQuotaDTO;
import com.zlt.aps.lh.api.domain.dto.SpecialMaterialMatchResult;
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
import com.zlt.aps.lh.api.enums.SkuTagEnum;
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
import com.zlt.aps.lh.engine.strategy.support.DailyMachineExpansionPlanner;
import com.zlt.aps.lh.engine.strategy.support.MachineProductionSegment;
import com.zlt.aps.lh.engine.strategy.support.MachineScheduleRole;
import com.zlt.aps.lh.engine.strategy.support.MouldResourceAllocationResult;
import com.zlt.aps.lh.engine.strategy.support.MouldResourceContext;
import com.zlt.aps.lh.engine.strategy.support.NewSpecCandidateCache;
import com.zlt.aps.lh.engine.strategy.support.ProductionQuantityPolicy;
import com.zlt.aps.lh.engine.strategy.support.SmallEndingSurplusSkipRule;
import com.zlt.aps.lh.service.impl.LhMaintenanceScheduleService;
import com.zlt.aps.lh.util.FirstInspectionQtyUtil;
import com.zlt.aps.lh.util.LeftRightMouldUtil;
import com.zlt.aps.lh.util.LhMachineHardMatchUtil;
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
import com.zlt.aps.lh.util.MachineStatusUtil;
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
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 新增规格排产策略实现。
 *
 * <p>业务定位：</p>
 * <ul>
 *   <li>处理 S4.5 新增 SKU 的选机、换模、首检、开产时间、班次分配和未排原因归集；</li>
 *   <li>支持同 SKU 单机台、多机台、尾量归集、非收尾补满、收尾严格控量和晚班不可换模衔接；</li>
 *   <li>消费 S4.3 初始化的日计划账本，并在胎胚库存裁剪后同步机台运行态；</li>
 *   <li>与机台匹配、换模均衡、首检均衡、目标量解析和局部搜索策略协作完成新增规格排产。</li>
 * </ul>
 *
 * <p>注意：本类方法较长且历史规则较多。维护时应优先局部补注释和小方法，不应改变排序、
 * 机台选择、日计划账本和收尾判断的既有语义。</p>
 *
 * @author APS
 */
@Slf4j
@Component("newSpecProductionStrategy")
public class NewSpecProductionStrategy implements IProductionStrategy {

    private static final String NEW_SPEC_SCHEDULE_TYPE = "02";
    private static final String AUTO_DATA_SOURCE = "0";
    private static final String ZERO_PLAN_UNSCHEDULED_REASON = "新增结果裁剪为0";
    private static final String HISTORY_SHORTAGE_NO_FUTURE_PREVIOUS_PRODUCED_UNSCHEDULED_REASON =
            "仅历史欠产、后续无月计划，且前日已有完成量，本次跳过补排";
    private static final String SHARED_EMBRYO_ZERO_SURPLUS_UNSCHEDULED_REASON =
            "共用胎胚且硫化余量为0";
    private static final String SMALL_ENDING_SURPLUS_UNSCHEDULED_REASON =
            SmallEndingSurplusSkipRule.UNSCHEDULED_REASON;
    private static final String NEW_SPEC_CLEANING_ANALYSIS = "模具清洗+换模";
    private static final int NEW_SPEC_CHANGEOVER_PROBE_LIMIT = 16;
    private static final int RELEASED_MACHINE_MORNING_TAIL_HOURS = 6;
    private static final Set<String> EMPTY_STRING_SET = Collections.emptySet();
    private static final Map<String, String> EMPTY_STRING_MAP = Collections.emptyMap();
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
        // 多机台余量和胎胚库存按机台数均分，最后一台补尾差，保证展示口径与总量一致。
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

        // shifts 是本次排程窗口 class1～class8 的实际业务班次，后续所有班次排量都按该列表落字段。
        List<LhShiftConfigVO> shifts = LhScheduleTimeUtil.getScheduleShifts(context, context.getScheduleDate());
        Map<String, Integer> unscheduledReasonCountMap = new LinkedHashMap<>(8);
        initializePendingNewSpecSkuTypeCounts(context);
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
        // TODO 后续建议把新增排产主循环拆为“候选生成、窗口分配、结果构建、账本消费”四个私有阶段，降低单方法维护成本。
        int scheduledCount = 0;
        int roundNo = 1;
        List<SkuScheduleDTO> deferredCompensationSkuList = new ArrayList<SkuScheduleDTO>(2);
        while (true) {
            traceActualPendingNewSpecQueue(context, roundNo);
            RoundScheduleSummary roundSummary = schedulePendingNewSpecsRound(
                    context, machineMatch, mouldChangeBalance, inspectionBalance, capacityCalculate,
                    shifts, unscheduledReasonCountMap, deferredCompensationSkuList);
            scheduledCount += roundSummary.getScheduledCount();
            if (CollectionUtils.isEmpty(deferredCompensationSkuList)) {
                return scheduledCount;
            }
            appendDeferredCompensationSkuList(context, deferredCompensationSkuList);
            deferredCompensationSkuList.clear();
            roundNo++;
        }
    }

    /**
     * 输出新增主循环真实待排队列。
     * <p>SKU 排序汇总只记录某一时点的排序快照；当续作补偿 SKU 在新增链路中被延后插入下一轮时，
     * 需要额外记录当前轮次真实待排顺序，避免过程日志与实际执行顺序不一致。</p>
     *
     * @param context 排程上下文
     * @param roundNo 新增主循环轮次
     */
    private void traceActualPendingNewSpecQueue(LhScheduleContext context, int roundNo) {
        if (!PriorityTraceLogHelper.isEnabled(context)) {
            return;
        }
        String title = "新增待排队列【实际执行】";
        List<SkuScheduleDTO> pendingSkuList = context.getNewSpecSkuList();
        int skuCount = PriorityTraceLogHelper.sizeOf(pendingSkuList);
        int outputCount = Math.min(LhScheduleConstant.SKU_SORT_TRACE_TOP_N, skuCount);

        StringBuilder detailBuilder = new StringBuilder(1024);
        PriorityTraceLogHelper.appendTitleHeader(detailBuilder, title);
        PriorityTraceLogHelper.appendLine(detailBuilder,
                PriorityTraceLogHelper.kv("排程日期", PriorityTraceLogHelper.formatDateTime(context.getScheduleDate()))
                        + ", " + PriorityTraceLogHelper.kv("步骤", context.getCurrentStep())
                        + ", " + PriorityTraceLogHelper.kv("轮次", roundNo)
                        + ", " + PriorityTraceLogHelper.kv("待排SKU数量", skuCount)
                        + ", " + PriorityTraceLogHelper.kv("输出范围", "TOP" + outputCount));
        if (CollectionUtils.isEmpty(pendingSkuList)) {
            PriorityTraceLogHelper.appendLine(detailBuilder, "无可输出的待排SKU");
        } else {
            for (int i = 0; i < outputCount; i++) {
                SkuScheduleDTO sku = pendingSkuList.get(i);
                PriorityTraceLogHelper.appendLine(detailBuilder,
                        "[新增待排队列] rank=" + (i + 1)
                                + ", sku=" + PriorityTraceLogHelper.safeText(sku.getMaterialCode())
                                + ", 补偿SKU=" + PriorityTraceLogHelper.oneZero(sku.isContinuousCompensationSku())
                                + ", 目标量=" + sku.resolveTargetScheduleQty()
                                + ", 窗口量=" + PriorityTraceLogHelper.safeText(sku.getWindowPlanQty())
                                + ", 班产=" + PriorityTraceLogHelper.safeText(sku.getShiftCapacity())
                                + ", 阶段=" + resolveConstructionStageDesc(sku)
                                + ", 施工组=" + resolveNewSpecDisplayType(sku)
                                + ", 收尾=" + PriorityTraceLogHelper.oneZero(endingJudgmentStrategy.isEnding(context, sku)));
            }
        }
        PriorityTraceLogHelper.appendTitleFooter(detailBuilder);
        PriorityTraceLogHelper.logSortSummary(log, context, title, detailBuilder.toString());
    }

    /**
     * 执行一轮新增 SKU 主排产循环。
     *
     * <p>业务步骤：</p>
     * <ul>
     *   <li>步骤1：按当前待排队列逐个 SKU 处理，先完成收尾、欠产账本和严格目标量判定；</li>
     *   <li>步骤2：调用机台匹配策略得到候选机台，候选只代表可承接，不代表最终一定排产；</li>
     *   <li>步骤3：逐台尝试换模、首检、开产时间和班次产能，失败机台加入排除集合后继续下一台；</li>
     *   <li>步骤4：按日计划账本回裁结果，当前机台不足时保留剩余量继续拆到下一台；</li>
     *   <li>步骤5：排产成功后更新结果、机台状态、机台占用和结构待排视图。</li>
     * </ul>
     *
     * <p>副作用：会修改 {@code context.newSpecSkuList}、{@code scheduleResultList}、
     * {@code scheduleResultSourceSkuMap}、机台运行态、日计划额度账本和机台占用关系。</p>
     *
     * @param context 排程上下文
     * @param machineMatch 机台匹配策略
     * @param mouldChangeBalance 换模均衡策略
     * @param inspectionBalance 首检均衡策略
     * @param capacityCalculate 产能计算策略
     * @param shifts 本次排程窗口班次，索引对应 class1～class8
     * @param unscheduledReasonCountMap 未排原因统计
     * @param deferredCompensationSkuList 被新增抢占的续作占位结果转出的补偿 SKU
     * @return 本轮排产摘要
     */
    private RoundScheduleSummary schedulePendingNewSpecsRound(LhScheduleContext context,
                                                              IMachineMatchStrategy machineMatch,
                                                              IMouldChangeBalanceStrategy mouldChangeBalance,
                                                              IFirstInspectionBalanceStrategy inspectionBalance,
                                                              ICapacityCalculateStrategy capacityCalculate,
                                                              List<LhShiftConfigVO> shifts,
                                                              Map<String, Integer> unscheduledReasonCountMap,
                                                              List<SkuScheduleDTO> deferredCompensationSkuList) {
        int scheduledCount = 0;
        boolean progressed = false;
        refreshPendingNewSpecSkuTypeCounts(context);
        Iterator<SkuScheduleDTO> iterator = context.getNewSpecSkuList().iterator();
        while (iterator.hasNext()) {
            SkuScheduleDTO sku = iterator.next();
            boolean currentSkuRemoved = false;
            // 续作、换活字块未消费完的 SKU 在此继续参与 S4.5，不因来源不同提前拦截。
            boolean isEnding = endingJudgmentStrategy.isEnding(context, sku);
            int previousDayFinishedQty = resolvePreviousDayFinishedQty(context, sku.getMaterialCode());
            if (shouldSkipHistoryShortageOnlyPreviousProducedSku(context, sku,
                    previousDayFinishedQty)) {
                int historyShortageQty = Math.max(0, sku.getMonthlyHistoryShortageQty());
                addUnscheduledResult(context, sku, historyShortageQty,
                        HISTORY_SHORTAGE_NO_FUTURE_PREVIOUS_PRODUCED_UNSCHEDULED_REASON,
                        unscheduledReasonCountMap);
                removeCurrentNewSpecSku(context, iterator, sku);
                progressed = true;
                log.info("新增SKU仅历史欠产且前日已有生产记录，本次跳过补排, materialCode: {}, "
                                + "historyShortageQty: {}, scheduleDate: {}, previousDayFinishedQty: {}, reason: {}",
                        sku.getMaterialCode(), historyShortageQty,
                        LhScheduleTimeUtil.formatDate(context.getScheduleDate()),
                        previousDayFinishedQty,
                        HISTORY_SHORTAGE_NO_FUTURE_PREVIOUS_PRODUCED_UNSCHEDULED_REASON);
                continue;
            }
            boolean forceEndingByNoFuturePlan = prepareNewSpecShortageQuota(context, sku);
            if (forceEndingByNoFuturePlan) {
                // 窗口和月底均无未来计划时，新增按收尾清量处理，目标量允许结合胎胚库存上调。
                isEnding = true;
            } else if (sku.isStrictNewSpecShortageOnly()) {
                // 窗口无计划但月底仍有计划时，仅补本月历史欠产，不按收尾满清，也不触发满班超排。
                isEnding = false;
            }
            if (handleSmallEndingSurplusSkipIfNecessary(context, iterator, sku, isEnding, unscheduledReasonCountMap)) {
                progressed = true;
                continue;
            }
            // 收尾SKU在排产前上调目标量（考虑胎胚库存），非收尾SKU保持按余量计算的目标量
            boolean sharedEmbryoZeroSurplusEnding = false;
            if (isEnding) {
                sharedEmbryoZeroSurplusEnding = getTargetScheduleQtyResolver()
                        .isSharedEmbryoZeroSurplusEnding(context, sku);
                getTargetScheduleQtyResolver().upsizeEndingTargetQty(context, sku);
                if (handleSharedEmbryoZeroSurplusEndingIfNecessary(
                        context, iterator, sku, sharedEmbryoZeroSurplusEnding, unscheduledReasonCountMap)) {
                    progressed = true;
                    continue;
                }
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

            // 1. 匹配候选机台：只做硬性准入和候选排序，换模/首检/产能在后续逐台试算。
            context.getNewSpecTypeRuleBlockedMap().remove(sku);
            List<MachineScheduleDTO> candidates = machineMatch.matchMachines(context, sku);
            logNewSpecMachineCandidateSnapshot(context, sku, candidates, EMPTY_STRING_SET, null);
            if (candidates.isEmpty()) {
                String noCandidateReason = resolveNoCandidateMachineReason(context, sku);
                log.warn("新增SKU无候选机台, materialCode: {}, 结构: {}, 规格: {}, 寸口: {}, 目标量: {}, 原因: {}",
                        sku.getMaterialCode(), sku.getStructureName(), sku.getSpecCode(),
                        sku.getProSize(), sku.resolveTargetScheduleQty(), noCandidateReason);
                traceNewSpecMachineDecision(context, sku, candidates, null, null,
                        EMPTY_STRING_SET, EMPTY_STRING_MAP,
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

            // 2. 基于策略选择最优机台，失败后排除并继续选择下一台。
            // 多机台拆量：当一台机台产能不足以排完目标量时，继续尝试下一台机台。
            boolean scheduled = false;
            NewSpecFailReasonEnum failReason = NewSpecFailReasonEnum.MACHINE_SELECTION_FAILED;
            Set<String> excludedMachineCodes = new HashSet<>(candidates.size());
            Map<String, String> excludedMachineReasonMap = new LinkedHashMap<>(candidates.size());
            // originalTargetScheduleQty 是进入本 SKU 前的业务目标量，用于所有候选失败后恢复原口径。
            Integer originalTargetScheduleQty = sku.getTargetScheduleQty();
            int minimumTargetScheduleQty = resolveFormalNonEndingMinimumTargetQty(context, sku, quantityPolicy);
            if (minimumTargetScheduleQty > 0) {
                // 正规/量试非收尾在满排口径下可临时抬高目标，避免单机台只按 dayN 小目标提前结束。
                sku.setTargetScheduleQty(minimumTargetScheduleQty);
            }
            // baseTargetScheduleQty 是本轮多机台拆量的业务基准，单台失败或继续下一台时按它恢复。
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
            // dynamicTargetQty 会随着 dayN 扩机台判断动态收敛，表示当前多机台组还需要消化的窗口目标。
            int dynamicTargetQty = remainingQty;
            sku.setRemainingScheduleQty(remainingQty);
            MachineScheduleDTO finalMachine = null;
            Date finalProductionStartTime = null;
            // 多机台累计调度结果，用于最终按总量、日计划账本和满班超排口径确认排完与否。
            int totalScheduledQty = 0;
            int originalAddMachineCount = countAvailableCandidateMachines(candidates, EMPTY_STRING_SET);
            int actualAllowedAddMachineCount = 0;
            NewSpecCandidateCache candidateCache = NewSpecCandidateCache.from(candidates,
                    machine -> isSingleControlMachine(context, machine.getMachineCode()));
            while (true) {
                logNewSpecMachineCandidateSnapshot(context, sku, candidates, excludedMachineCodes, excludedMachineReasonMap);
                MachineScheduleDTO candidateMachine = selectCandidateMachine(
                        context, sku, candidateCache, excludedMachineCodes, machineMatch,
                        preferredTrialMachine, quantityPolicy);
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
                // SKU新增机台必须先按候选机台模数预占可用模具；模具不足只跳过当前机台，不能中断排程主链。
                MouldResourceAllocationResult mouldResourceAllocationResult = tryAllocateMouldResourceForAddMachine(
                        context, sku, candidateMachine, originalAddMachineCount, actualAllowedAddMachineCount);
                if (!mouldResourceAllocationResult.isAllowed()) {
                    excludedMachineCodes.add(machineCode);
                    candidateCache.removeMachine(machineCode);
                    recordExcludedMachineReason(excludedMachineReasonMap, machineCode,
                            mouldResourceAllocationResult.getSkipReason().getDescription(),
                            null, null, null, null, null, null, null, null, null);
                    failReason = selectHigherPriorityFailReason(
                            failReason, NewSpecFailReasonEnum.MACHINE_SELECTION_FAILED);
                    continue;
                }

                // 3. 计算机台可开工时间（考虑机台当前预计完工和能力策略约束）
                Date endingTime = resolveMachineOccupationEndTime(context, candidateMachine, shifts);
                getMaintenanceScheduleService().tryAttachLongOnlineMaintenance(context, candidateMachine);
                if (isEnding) {
                    getMaintenanceScheduleService().tryAttachMaintenanceAfterFirstEnding(
                            context, candidateMachine, endingTime);
                }
                // 保养窗口挂载会改变候选机台运行态，提前清理窗口产能缓存，避免后续复用旧产能。
                candidateCache.clearCapacityCache();
                Date machineReadyTime = capacityCalculate.calculateStartTime(context,
                        machineCode, endingTime);
                boolean maintenanceOverlapSwitch = getMaintenanceScheduleService()
                        .shouldApplyMaintenanceOverlapSwitchRule(context, candidateMachine, endingTime);
                Date switchReadyTime = maintenanceOverlapSwitch
                        ? getMaintenanceScheduleService().resolveMaintenanceEndTime(context, candidateMachine)
                        : machineReadyTime;
                switchReadyTime = resolveSpecifyReservedReadyTime(context, sku, machineCode, switchReadyTime);
                switchReadyTime = ShiftProductionControlUtil.resolveEarliestSwitchStartTime(context, switchReadyTime);
                switchReadyTime = alignNewSpecSwitchReadyTimeToWindowStart(context, shifts, switchReadyTime);
                switchReadyTime = adjustReleasedMachineSwitchReadyTime(context, sku, candidateMachine, switchReadyTime);

                // 4. 分配换模窗口；晚班不可换模、换模上限和维保重叠都在分配器中统一收口。
                // 基础换模时间永远执行，换模均衡仅在开关开启时介入。
                Date mouldChangeStartTime = null;
                Date mouldChangeCompleteTime = null;
                Date inspectionTime = null;
                Date productionStartTime = null;
                NewSpecFailReasonEnum switchAllocateFailReason = null;
                int switchDurationHours = maintenanceOverlapSwitch
                        ? LhScheduleTimeUtil.getMaintenanceOverlapSwitchHours(context)
                        : LhScheduleTimeUtil.getMouldChangeTotalHours(context);
                mouldChangeStartTime = allocateNewSpecMouldChangeStartTime(
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
                    rollbackMouldResourceAllocation(context, sku, mouldResourceAllocationResult);
                    excludedMachineCodes.add(machineCode);
                    candidateCache.removeMachine(machineCode);
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
                firstProductionStartTime = alignFirstProductionStartTimeByDailyPlan(
                        context, sku, firstProductionStartTime, shifts, isEnding);
                if (firstProductionStartTime == null) {
                    log.debug("新增SKU排程窗口内无可开产时间, materialCode: {}, 机台: {}, 首检时间: {}, 班产: {}, 硫化时间: {}, 模数: {}",
                            sku.getMaterialCode(), machineCode,
                            LhScheduleTimeUtil.formatDateTime(productionStartTime),
                            sku.getShiftCapacity(), sku.getLhTimeSeconds(), machineMouldQty);
                    inspectionBalance.rollbackInspection(context, inspectionTime);
                    rollbackMouldChangeAllocation(context, sku, mouldChangeBalance, mouldChangeStartTime);
                    rollbackMouldResourceAllocation(context, sku, mouldResourceAllocationResult);
                    excludedMachineCodes.add(machineCode);
                    candidateCache.removeMachine(machineCode);
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
                // 普通换模8小时已包含首检：首检数量按换模完成落班计入产能，不额外推迟开产时间。
                shiftCapacityMap = FirstInspectionQtyUtil.applyFirstInspectionQtyToCapacityMap(
                        context, shifts, mouldChangeCompleteTime, shiftCapacityMap, runtimeShiftCapacity,
                        dynamicTargetQty, ScheduleTypeEnum.NEW_SPEC.getCode());
                int maxQtyToWindowEnd = sumShiftCapacity(shiftCapacityMap);
                MachineProductionSegment segment = buildMachineProductionSegment(
                        context, sku, machineCode, mouldChangeStartTime, firstProductionStartTime,
                        maxQtyToWindowEnd, runtimeShiftCapacity, shiftCapacityMap);
                MachineScheduleRole role = resolveMachineScheduleRole(quantityPolicy, totalScheduledQty,
                        maxQtyToWindowEnd, candidateTargetQty);
                segment.setRole(role);
                boolean singleMachineWindowFill = shouldFillSingleMachineToWindowEnd(
                        context, sku, candidateMachine, isEnding, totalScheduledQty,
                        candidateTargetQty, maxQtyToWindowEnd);
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
                if (segment.getFutureDayDemandMachineCount() > 1) {
                    /*
                     * T+2 后看 T+3 推导出多机台时，本轮目标量必须同步放大到这些机台的窗口有效产能，
                     * 否则第一台满班后 remainingQty 会归零，第二台无法进入现有候选机台主链。
                     */
                    candidateTargetQty = Math.max(candidateTargetQty,
                            segment.getMaxQtyToWindowEnd() * segment.getFutureDayDemandMachineCount());
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
                    rollbackMouldResourceAllocation(context, sku, mouldResourceAllocationResult);
                    excludedMachineCodes.add(machineCode);
                    candidateCache.removeMachine(machineCode);
                    recordExcludedMachineReason(excludedMachineReasonMap, machineCode,
                            "动态分配后本机台计划量为0",
                            machineReadyTime, switchReadyTime, mouldChangeStartTime, mouldChangeCompleteTime,
                            inspectionTime, firstProductionStartTime, maxQtyToWindowEnd, machinePlanQty, null);
                    failReason = selectHigherPriorityFailReason(
                            failReason, NewSpecFailReasonEnum.NO_CAPACITY_IN_SCHEDULE_WINDOW);
                    continue;
                }
                // 从这里开始 targetScheduleQty 临时改为“本机台计划量”，仅用于结果构建和班次分配。
                // 后续失败、继续下一台或本 SKU 结束时必须恢复到业务目标，避免污染后续候选机台。
                dynamicTargetQty = candidateTargetQty;
                sku.setTargetScheduleQty(machinePlanQty);
                takeoverReleasedContinuousPlaceholderIfNeeded(
                        context, machineCode, shifts, deferredCompensationSkuList);
                LhScheduleResult result = buildNewSpecScheduleResult(
                        context, candidateMachine, sku, firstProductionStartTime, mouldChangeStartTime,
                        mouldChangeCompleteTime, shifts, machineMouldQty, isEnding, mouldResourceAllocationResult);
                if (result == null || result.getDailyPlanQty() == null || result.getDailyPlanQty() <= 0) {
                    log.debug("新增SKU结果无有效班次计划量, materialCode: {}, 机台: {}, 目标量: {}, 开产时间: {}",
                            sku.getMaterialCode(), machineCode, sku.resolveTargetScheduleQty(),
                            LhScheduleTimeUtil.formatDateTime(firstProductionStartTime));
                    // 无有效产能时回滚首检和换模占用，避免影响后续SKU排产
                    inspectionBalance.rollbackInspection(context, inspectionTime);
                    rollbackMouldChangeAllocation(context, sku, mouldChangeBalance, mouldChangeStartTime);
                    rollbackMouldResourceAllocation(context, sku, mouldResourceAllocationResult);
                    // 候选机台失败时恢复原目标量，避免把本次失败收敛值泄漏到后续候选机台。
                    sku.setTargetScheduleQty(baseTargetScheduleQty);
                    excludedMachineCodes.add(machineCode);
                    candidateCache.removeMachine(machineCode);
                    recordExcludedMachineReason(excludedMachineReasonMap, machineCode,
                            "结果无有效班次计划量",
                            machineReadyTime, switchReadyTime, mouldChangeStartTime, mouldChangeCompleteTime,
                            inspectionTime, firstProductionStartTime, maxQtyToWindowEnd, machinePlanQty, null);
                    failReason = selectHigherPriorityFailReason(
                            failReason, NewSpecFailReasonEnum.NO_CAPACITY_IN_SCHEDULE_WINDOW);
                    continue;
                }

                sku.setMouldQty(machineMouldQty);
                applyNightNoMouldChangeContinuationFill(context, sku, result, shifts, quantityPolicy);
                // 7. 先按账本硬约束回裁结果，再落地结果与刷新机台状态，避免窗口总量被结果行放大。
                // 收尾/试制等严格目标量会被截断；正规/量试非收尾允许记录满班补齐超排。
                int machineScheduledQty = applyBlockToDailyQuota(context, sku, result, shifts);
                if (machineScheduledQty <= 0) {
                    inspectionBalance.rollbackInspection(context, inspectionTime);
                    rollbackMouldChangeAllocation(context, sku, mouldChangeBalance, mouldChangeStartTime);
                    rollbackMouldResourceAllocation(context, sku, mouldResourceAllocationResult);
                    sku.setTargetScheduleQty(baseTargetScheduleQty);
                    remainingQty = resolveSchedulableRemainingQty(sku);
                    sku.setRemainingScheduleQty(remainingQty);
                    if (!needMoreMachine(context, sku)) {
                        break;
                    }
                    excludedMachineCodes.add(machineCode);
                    candidateCache.removeMachine(machineCode);
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
                candidateCache.clearCapacityCache();
                scheduledCount++;
                actualAllowedAddMachineCount++;
                progressed = true;
                scheduled = true;
                finalMachine = candidateMachine;
                finalProductionStartTime = firstProductionStartTime;
                // 累计本机台实际排产量，递减多机台剩余量；剩余量仍需结合 dayN 账本判断是否继续加机台。
                totalScheduledQty += machineScheduledQty;
                if (segment.isStopAfterCurrentForSmallShortage()) {
                    // 小额欠产允许滚动时，当前机台已覆盖后续日计划，不再为了首日欠产余额继续扩机。
                    dynamicTargetQty = totalScheduledQty;
                }
                remainingQty = Math.max(0, dynamicTargetQty - totalScheduledQty);
                sku.setRemainingScheduleQty(remainingQty);
                finalTargetScheduleQty = dynamicTargetQty;
                /*
                 * 小批量待排计数依赖日计划账本剩余额度，当前机台扣账后需要刷新，
                 * 保证同一SKU继续拆机台时单控保护规则读取的是最新待排视图。
                 */
                refreshPendingNewSpecSkuTypeCounts(context);
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
                if (remainingQty <= 0 || !needMoreMachine(context, sku)) {
                    // 全部排完（总量满足 且 每日额度满足），移出待排队列
                    removeCurrentNewSpecSku(context, iterator, sku);
                    currentSkuRemoved = true;
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
                // 一台排不完，保留原业务目标量，下一台机台按剩余缺口动态计算本机台计划量。
                // 已成功排产的机台加入排除集合，表示本 SKU 后续拆量不再回头重复尝试同一机台。
                sku.setTargetScheduleQty(baseTargetScheduleQty);
                excludedMachineCodes.add(machineCode);
                candidateCache.removeMachine(machineCode);
                recordExcludedMachineReason(excludedMachineReasonMap, machineCode,
                        "本机台已排产但仍有剩余，继续尝试下一台",
                        machineReadyTime, switchReadyTime, mouldChangeStartTime, mouldChangeCompleteTime,
                        inspectionTime, firstProductionStartTime, maxQtyToWindowEnd, machinePlanQty,
                        machineScheduledQty);
                log.info("新增SKU一台机台未排完，继续尝试下一台, materialCode: {}, 本机台: {}, 已排: {}, 剩余: {}",
                        sku.getMaterialCode(), machineCode, totalScheduledQty, remainingQty);
            }

            sku.setTargetScheduleQty(scheduled ? finalTargetScheduleQty : originalTargetScheduleQty);
            if (scheduled) {
                adjustSameSkuMultiMachineAllocation(context, sku, shifts, quantityPolicy, isEnding);
            }
            if (!scheduled) {
                // 所有候选机台都失败，记录未排产原因并移出待排队列
                log.warn("新增SKU排产失败, materialCode: {}, 结构: {}, 规格: {}, 目标量: {}, 候选机台数: {}, 排除机台: {}, 原因: {}",
                        sku.getMaterialCode(), sku.getStructureName(), sku.getSpecCode(),
                        sku.resolveTargetScheduleQty(), candidates.size(), excludedMachineCodes,
                        failReason.getDescription());
                traceNewSpecMachineDecision(context, sku, candidates, localSearchSuggestedMachine, null,
                        excludedMachineCodes, excludedMachineReasonMap, failReason, false, null);
                addUnscheduledResult(context, sku, resolveScheduleFailureReason(context, sku, failReason),
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
                if (!currentSkuRemoved && remainingQty > 0 && needMoreMachine(context, sku)) {
                    log.warn("新增SKU多机台排产未全部完成, materialCode: {}, 已排: {}, 剩余: {}, 满班超排: {}, 候选机台已耗尽",
                            sku.getMaterialCode(), totalScheduledQty, remainingQty, sku.getShiftFillOverQty());
                    // 剩余未排量计入未排结果
                    addUnscheduledResult(context, sku, remainingQty,
                            "多机台产能不足，剩余" + remainingQty + "未排", unscheduledReasonCountMap);
                    removeCurrentNewSpecSku(context, iterator, sku);
                } else if (!currentSkuRemoved && remainingQty > 0) {
                    // 总量上仍有剩余（可能来自欠产传导），但日计划额度已满足，移出待排队列
                    log.info("新增SKU日计划额度已满足但总量仍有剩余, materialCode: {}, 已排: {}, 总量剩余: {}, 满班超排: {}",
                            sku.getMaterialCode(), totalScheduledQty, remainingQty, sku.getShiftFillOverQty());
                    removeCurrentNewSpecSku(context, iterator, sku);
                }
                traceNewSpecMachineDecision(context, sku, candidates, localSearchSuggestedMachine, finalMachine,
                        excludedMachineCodes, excludedMachineReasonMap, null, true,
                        PriorityTraceLogHelper.formatDateTime(finalProductionStartTime));
                if (!CollectionUtils.isEmpty(deferredCompensationSkuList)) {
                    return new RoundScheduleSummary(scheduledCount, true);
                }
            }
        }
        return new RoundScheduleSummary(scheduledCount, progressed);
    }

    /**
     * 准备新增排产欠产账本。
     * <p>委托公共增机台协作器处理本月欠产入账和窗口无计划判断，保证 S4.4 续作补偿与 S4.5 新增排产口径一致。</p>
     *
     * @param context 排程上下文
     * @param sku 新增排产SKU
     * @return true-当前窗口和月底均无计划，需要按收尾处理
     */
    private boolean prepareNewSpecShortageQuota(LhScheduleContext context, SkuScheduleDTO sku) {
        return DailyMachineExpansionPlanner.prepareShortageQuota(context, sku, "新增排产")
                .isForceEndingByNoFuturePlan();
    }

    /**
     * 判断仅历史欠产且后续无计划的SKU是否需要跳过本轮新增补排。
     * <p>该规则只限制非续作补偿的新增SKU；窗口 dayN 与月底后续计划均为0，
     * 且前日日完成量大于0时，避免本轮重复补排历史欠产。</p>
     *
     * @param context 排程上下文
     * @param sku 新增排产SKU
     * @param previousDayFinishedQty 前日日完成量
     * @return true-跳过本轮新增补排；false-继续原新增排产逻辑
     */
    private boolean shouldSkipHistoryShortageOnlyPreviousProducedSku(LhScheduleContext context,
                                                                     SkuScheduleDTO sku,
                                                                     int previousDayFinishedQty) {
        if (Objects.isNull(context) || Objects.isNull(sku)
                || sku.isContinuousCompensationSku()) {
            return false;
        }
        if (Math.max(0, sku.getMonthlyHistoryShortageQty()) <= 0) {
            return false;
        }
        if (!isWindowDayPlanEmpty(sku) || Math.max(0, sku.getFutureMonthPlanQtyAfterWindow()) > 0) {
            return false;
        }
        // 仅以T日前日完成量判断是否已生产过
        return previousDayFinishedQty > 0;
    }

    /**
     * 判断当前排程窗口 dayN 月计划量是否全为0。
     *
     * @param sku SKU
     * @return true-窗口内无 dayN 月计划量；false-仍有窗口计划或账本缺失
     */
    private boolean isWindowDayPlanEmpty(SkuScheduleDTO sku) {
        if (Objects.isNull(sku) || CollectionUtils.isEmpty(sku.getDailyPlanQuotaMap())) {
            return false;
        }
        for (SkuDailyPlanQuotaDTO quota : sku.getDailyPlanQuotaMap().values()) {
            if (Objects.nonNull(quota) && Math.max(0, quota.getDayPlanQty()) > 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * 解析当前排程日T前一日的日完成量。
     *
     * @param context 排程上下文
     * @param materialCode 物料编码
     * @return 前日日完成量，无记录时返回0
     */
    private int resolvePreviousDayFinishedQty(LhScheduleContext context, String materialCode) {
        if (Objects.isNull(context) || Objects.isNull(context.getScheduleDate())
                || StringUtils.isEmpty(materialCode)) {
            return 0;
        }
        Date previousDay = LhScheduleTimeUtil.clearTime(LhScheduleTimeUtil.addDays(context.getScheduleDate(), -1));
        Integer finishedQty = context.getMaterialDayFinishedQtyMap()
                .get(buildMaterialDayKey(materialCode, previousDay));
        return Objects.nonNull(finishedQty) ? Math.max(finishedQty, 0) : 0;
    }

    /**
     * 构建物料日完成量聚合Key。
     *
     * @param materialCode 物料编码
     * @param finishDate 完成日期
     * @return 聚合Key
     */
    private String buildMaterialDayKey(String materialCode, Date finishDate) {
        return materialCode + "_" + LhScheduleTimeUtil.formatDate(LhScheduleTimeUtil.clearTime(finishDate));
    }

    /**
     * 判断接口目标日前一日排程结果中该SKU是否已有有效排产量。
     *
     * @param context 排程上下文
     * @param materialCode 物料编码
     * @return true-接口目标日前一日排程结果已排过；false-未排过
     */
    private boolean hasPreviousScheduledResult(LhScheduleContext context, String materialCode) {
        if (Objects.isNull(context) || StringUtils.isEmpty(materialCode)
                || CollectionUtils.isEmpty(context.getTargetPreviousScheduleResultList())) {
            return false;
        }
        for (LhScheduleResult result : context.getTargetPreviousScheduleResultList()) {
            if (Objects.nonNull(result)
                    && StringUtils.equals(materialCode, result.getMaterialCode())
                    && resolveResultScheduledQty(result) > 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * 新增SKU抢占首日无计划释放机台时，撤销原续作占位结果并转补偿SKU到后续新增轮次。
     * <p>这类续作结果只是“若机台未被占用时的回退占位”，一旦本轮新增真正占机，就必须把占位结果撤销，
     * 否则最终结果会同时保留“原续作继续在原机台”与“新增SKU换模占用原机台”两条互斥结果。</p>
     *
     * @param context 排程上下文
     * @param machineCode 当前被新增SKU占用的机台编码
     * @param shifts 排程窗口班次
     * @param deferredCompensationSkuList 延后到下一轮补排的补偿SKU集合
     */
    private void takeoverReleasedContinuousPlaceholderIfNeeded(LhScheduleContext context,
                                                               String machineCode,
                                                               List<LhShiftConfigVO> shifts,
                                                               List<SkuScheduleDTO> deferredCompensationSkuList) {
        if (context == null || StringUtils.isEmpty(machineCode)
                || CollectionUtils.isEmpty(context.getFirstDayNoPlanReleasedContinuousMachineCodeSet())
                || !context.getFirstDayNoPlanReleasedContinuousMachineCodeSet().contains(machineCode)) {
            return;
        }
        List<LhScheduleResult> machineResults = resolveMachineResultsForPlaceholderTakeover(context, machineCode);
        if (CollectionUtils.isEmpty(machineResults)) {
            return;
        }
        List<LhScheduleResult> placeholderResults = new ArrayList<LhScheduleResult>(2);
        for (LhScheduleResult result : machineResults) {
            if (result == null || !StringUtils.equals(machineCode, result.getLhMachineCode())) {
                continue;
            }
            if (isReleasedFirstDayNoPlanPlaceholderResult(context, result)) {
                placeholderResults.add(result);
            }
        }
        if (CollectionUtils.isEmpty(placeholderResults)) {
            return;
        }
        for (LhScheduleResult placeholderResult : placeholderResults) {
            SkuScheduleDTO sourceSku = resolveResultSourceSku(context, placeholderResult);
            if (sourceSku == null) {
                continue;
            }
            restoreContinuousPlaceholderQuota(context, sourceSku);
            appendDeferredContinuousCompensationSku(
                    context, sourceSku, placeholderResult, deferredCompensationSkuList);
            context.getScheduleResultSourceSkuMap().remove(placeholderResult);
            context.getScheduleResultList().remove(placeholderResult);
        }
        removeResultsFromMachineAssignments(context, placeholderResults);
    }

    /**
     * 解析释放续作占位结果所在机台的结果集合。
     * <p>正常主链优先按机台占用索引查找；若索引中未命中占位结果，
     * 继续按结果列表补充扫描当前机台，保持与旧版全量扫描语义一致。</p>
     *
     * @param context 排程上下文
     * @param machineCode 机台编码
     * @return 当前机台结果集合
     */
    private List<LhScheduleResult> resolveMachineResultsForPlaceholderTakeover(LhScheduleContext context,
                                                                               String machineCode) {
        if (context == null || StringUtils.isEmpty(machineCode)) {
            return new ArrayList<LhScheduleResult>(0);
        }
        List<LhScheduleResult> machineResults = null;
        if (!CollectionUtils.isEmpty(context.getMachineAssignmentMap())) {
            machineResults = context.getMachineAssignmentMap().get(machineCode);
        }
        if (hasReleasedPlaceholderResult(context, machineResults, machineCode)) {
            return machineResults;
        }
        List<LhScheduleResult> scannedResults = new ArrayList<LhScheduleResult>(2);
        if (!CollectionUtils.isEmpty(machineResults)) {
            scannedResults.addAll(machineResults);
        }
        if (CollectionUtils.isEmpty(context.getScheduleResultList())) {
            return scannedResults;
        }
        for (LhScheduleResult result : context.getScheduleResultList()) {
            if (result != null && StringUtils.equals(machineCode, result.getLhMachineCode())) {
                addMachineResultIfAbsent(scannedResults, result);
            }
        }
        return scannedResults;
    }

    /**
     * 判断机台结果集合中是否包含释放续作占位结果。
     *
     * @param context 排程上下文
     * @param machineResults 机台结果集合
     * @param machineCode 机台编码
     * @return true-包含释放续作占位结果
     */
    private boolean hasReleasedPlaceholderResult(LhScheduleContext context,
                                                 List<LhScheduleResult> machineResults,
                                                 String machineCode) {
        if (CollectionUtils.isEmpty(machineResults)) {
            return false;
        }
        for (LhScheduleResult result : machineResults) {
            if (result != null
                    && StringUtils.equals(machineCode, result.getLhMachineCode())
                    && isReleasedFirstDayNoPlanPlaceholderResult(context, result)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 按对象引用去重追加机台结果，避免 Map 与结果列表命中同一结果时重复处理。
     *
     * @param machineResults 机台结果集合
     * @param result 待追加结果
     */
    private void addMachineResultIfAbsent(List<LhScheduleResult> machineResults, LhScheduleResult result) {
        if (result == null) {
            return;
        }
        for (LhScheduleResult existsResult : machineResults) {
            if (existsResult == result) {
                return;
            }
        }
        machineResults.add(result);
    }

    /**
     * 恢复首日无计划续作占位结果提前消费的共享日计划账本。
     * <p>占位结果被新增SKU抢占后，相应额度必须先回滚，再交给补偿SKU重新按新增换模链路消费。</p>
     *
     * @param sourceSku 原续作SKU
     */
    private void restoreContinuousPlaceholderQuota(LhScheduleContext context, SkuScheduleDTO sourceSku) {
        if (sourceSku == null || CollectionUtils.isEmpty(sourceSku.getDailyPlanQuotaMap())) {
            return;
        }
        for (SkuDailyPlanQuotaDTO quota : sourceSku.getDailyPlanQuotaMap().values()) {
            if (quota == null) {
                continue;
            }
            quota.setScheduledQty(0);
            quota.setRemainingQty(Math.max(0, quota.getDayPlanQty()));
            quota.setShiftFillOverQty(0);
            quota.setCarryLossQty(0);
            quota.setFutureBorrowQty(0);
            quota.setActualQty(0);
            quota.setCumulativeQty(0);
            quota.setFinalLossQty(0);
            quota.setCompleted(false);
        }
        SkuDailyPlanQuotaUtil.refreshRollingFields(sourceSku.getDailyPlanQuotaMap());
        DailyMachineExpansionPlanner.syncSharedQuotaEffectiveCarryForwardQty(context, sourceSku, 0);
    }

    /**
     * 为被抢占机台的原续作SKU生成补偿SKU，留待下一轮新增链路重新选机。
     *
     * @param context 排程上下文
     * @param sourceSku 原续作SKU
     * @param placeholderResult 被撤销的占位结果
     * @param deferredCompensationSkuList 延后补排集合
     */
    private void appendDeferredContinuousCompensationSku(LhScheduleContext context,
                                                         SkuScheduleDTO sourceSku,
                                                         LhScheduleResult placeholderResult,
                                                         List<SkuScheduleDTO> deferredCompensationSkuList) {
        if (sourceSku == null || placeholderResult == null || deferredCompensationSkuList == null) {
            return;
        }
        if (hasDeferredContinuousCompensationSku(context, sourceSku, deferredCompensationSkuList)) {
            return;
        }
        int compensationQty = placeholderResult.getDailyPlanQty() != null
                ? placeholderResult.getDailyPlanQty() : 0;
        if (compensationQty <= 0) {
            compensationQty = Math.max(0, sourceSku.resolveTargetScheduleQty());
        }
        if (compensationQty <= 0) {
            return;
        }
        SkuScheduleDTO compensationSku = new SkuScheduleDTO();
        BeanUtil.copyProperties(sourceSku, compensationSku);
        ProductionQuantityPolicy policy = ProductionQuantityPolicy.from(sourceSku, sourceSku.isStrictTargetQty());
        compensationSku.setScheduleType(NEW_SPEC_SCHEDULE_TYPE);
        compensationSku.setContinuousMachineCode(null);
        compensationSku.setPreferredContinuousMachineCode(sourceSku.getContinuousMachineCode());
        compensationSku.setContinuousCompensationSku(true);
        compensationSku.setTargetScheduleQty(compensationQty);
        compensationSku.setPendingQty(compensationQty);
        compensationSku.setRemainingScheduleQty(compensationQty);
        compensationSku.setStrictTargetQty(policy.isStrictUpperLimit());
        compensationSku.setDailyPlanQuotaMap(sourceSku.getDailyPlanQuotaMap());
        deferredCompensationSkuList.add(compensationSku);
    }

    private boolean hasDeferredContinuousCompensationSku(LhScheduleContext context,
                                                         SkuScheduleDTO sourceSku,
                                                         List<SkuScheduleDTO> deferredCompensationSkuList) {
        if (sourceSku == null) {
            return true;
        }
        if (!CollectionUtils.isEmpty(context.getNewSpecSkuList())) {
            for (SkuScheduleDTO pendingSku : context.getNewSpecSkuList()) {
                if (pendingSku != null
                        && StringUtils.equals(sourceSku.getMaterialCode(), pendingSku.getMaterialCode())
                        && pendingSku.getDailyPlanQuotaMap() == sourceSku.getDailyPlanQuotaMap()) {
                    return true;
                }
            }
        }
        if (!CollectionUtils.isEmpty(deferredCompensationSkuList)) {
            for (SkuScheduleDTO pendingSku : deferredCompensationSkuList) {
                if (pendingSku != null
                        && StringUtils.equals(sourceSku.getMaterialCode(), pendingSku.getMaterialCode())
                        && pendingSku.getDailyPlanQuotaMap() == sourceSku.getDailyPlanQuotaMap()) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 将补偿SKU追加到待排列表，并同步刷新本轮待排SKU类型计数。
     *
     * @param context 排程上下文
     * @param deferredCompensationSkuList 延后补排集合
     */
    private void appendDeferredCompensationSkuList(LhScheduleContext context,
                                                   List<SkuScheduleDTO> deferredCompensationSkuList) {
        if (context == null || CollectionUtils.isEmpty(deferredCompensationSkuList)) {
            return;
        }
        for (int i = deferredCompensationSkuList.size() - 1; i >= 0; i--) {
            SkuScheduleDTO compensationSku = deferredCompensationSkuList.get(i);
            if (compensationSku == null) {
                continue;
            }
            context.getNewSpecSkuList().add(0, compensationSku);
        }
        refreshPendingNewSpecSkuTypeCounts(context);
    }

    /**
     * 判断结果是否属于“首日无计划但后续有计划”的释放续作占位结果。
     *
     * @param context 排程上下文
     * @param result 排程结果
     * @return true-释放续作占位结果
     */
    private boolean isReleasedFirstDayNoPlanPlaceholderResult(LhScheduleContext context, LhScheduleResult result) {
        if (context == null || result == null
                || !StringUtils.equals("01", result.getScheduleType())
                || !StringUtils.equals("0", result.getIsTypeBlock())
                || StringUtils.isEmpty(result.getLhMachineCode())
                || CollectionUtils.isEmpty(context.getFirstDayNoPlanReleasedContinuousMachineCodeSet())) {
            return false;
        }
        return context.getFirstDayNoPlanReleasedContinuousMachineCodeSet().contains(result.getLhMachineCode());
    }

    /**
     * 解析排程结果对应的来源SKU。
     * <p>优先命中运行态映射；未注册时回退到物料编码查找，兼容旧测试夹具。</p>
     *
     * @param context 排程上下文
     * @param result 排程结果
     * @return 来源SKU
     */
    private SkuScheduleDTO resolveResultSourceSku(LhScheduleContext context, LhScheduleResult result) {
        if (context == null || result == null) {
            return null;
        }
        SkuScheduleDTO sourceSku = context.getScheduleResultSourceSkuMap().get(result);
        if (sourceSku != null) {
            return sourceSku;
        }
        return findSkuDto(context, result.getMaterialCode());
    }

    /**
     * 初始化新增待排SKU类型计数，供选机阶段日志与特殊机台保护规则复用。
     *
     * @param context 排程上下文
     */
    private void initializePendingNewSpecSkuTypeCounts(LhScheduleContext context) {
        refreshPendingNewSpecSkuTypeCounts(context);
        if (context == null) {
            return;
        }
        log.info("新增待排SKU类型计数初始化, 试制SKU: {}, 量试SKU: {}, 小批量SKU: {}, 正规SKU: {}",
                context.getPendingTrialNewSpecSkuCount(),
                context.getPendingMassTrialNewSpecSkuCount(),
                context.getPendingSmallBatchNewSpecSkuCount(),
                context.getPendingFormalNewSpecSkuCount());
    }

    /**
     * 刷新新增待排SKU类型计数。
     * <p>小批量单控保留只统计“当前窗口内仍有dayN剩余额度”的小批量SKU，
     * 避免窗口额度已为0的小批量继续冻结单控机台。</p>
     *
     * @param context 排程上下文
     */
    private void refreshPendingNewSpecSkuTypeCounts(LhScheduleContext context) {
        if (context == null) {
            return;
        }
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
                if (hasPendingWindowQuotaSmallBatchDemand(pendingSku)) {
                    smallBatchCount++;
                }
                continue;
            }
            formalCount++;
        }
        context.setPendingFormalNewSpecSkuCount(formalCount);
        context.setPendingTrialNewSpecSkuCount(trialCount);
        context.setPendingMassTrialNewSpecSkuCount(massTrialCount);
        context.setPendingSmallBatchNewSpecSkuCount(smallBatchCount);
    }

    /**
     * 判断小批量SKU在当前窗口内是否仍有待排日计划额度。
     * <p>只要窗口内 dayN 全为0，即使SKU类型上属于小批量，也不再继续占用单控保留名额。</p>
     *
     * @param sku SKU
     * @return true-窗口内仍有待排额度
     */
    private boolean hasPendingWindowQuotaSmallBatchDemand(SkuScheduleDTO sku) {
        if (!isSmallBatchSku(sku) || CollectionUtils.isEmpty(sku.getDailyPlanQuotaMap())) {
            return false;
        }
        for (SkuDailyPlanQuotaDTO quota : sku.getDailyPlanQuotaMap().values()) {
            if (quota == null) {
                continue;
            }
            if (Math.max(0, quota.getDayPlanQty()) > 0 && Math.max(0, quota.getRemainingQty()) > 0) {
                return true;
            }
        }
        return false;
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
     * 处理共用胎胚收尾零余量未排。
     * <p>该分支必须在候选机台匹配前完成，避免目标量为0的SKU继续走通用失败链路并覆盖业务未排原因。</p>
     *
     * @param context 排程上下文
     * @param iterator 新增SKU迭代器
     * @param sku 当前SKU
     * @param sharedEmbryoZeroSurplusEnding 是否命中共用胎胚零余量收尾
     * @param unscheduledReasonCountMap 未排原因统计
     * @return true-已写未排并移出待排队列；false-不需要处理
     */
    private boolean handleSharedEmbryoZeroSurplusEndingIfNecessary(LhScheduleContext context,
                                                                   Iterator<SkuScheduleDTO> iterator,
                                                                   SkuScheduleDTO sku,
                                                                   boolean sharedEmbryoZeroSurplusEnding,
                                                                   Map<String, Integer> unscheduledReasonCountMap) {
        if (!sharedEmbryoZeroSurplusEnding || sku.resolveTargetScheduleQty() > 0) {
            return false;
        }
        addUnscheduledResult(context, sku, 0,
                SHARED_EMBRYO_ZERO_SURPLUS_UNSCHEDULED_REASON, unscheduledReasonCountMap);
        getTargetScheduleQtyResolver().removeActiveEmbryoSku(
                context, sku, SHARED_EMBRYO_ZERO_SURPLUS_UNSCHEDULED_REASON);
        removeCurrentNewSpecSku(context, iterator, sku);
        log.info("新增共用胎胚收尾零余量写入未排, materialCode: {}, embryoCode: {}, surplusQty: {}, embryoStock: {}",
                sku.getMaterialCode(), sku.getEmbryoCode(), sku.getSurplusQty(), sku.getEmbryoStock());
        return true;
    }

    /**
     * 处理新增收尾小余量且前日 T+1 夜班未排满的不排产规则。
     *
     * <p>该规则必须在收尾目标量上调前执行，判断依据仍是 SKU 原始硫化余量，避免被
     * MAX(余量, 胎胚库存) 口径放大后漏判。</p>
     *
     * @param context 排程上下文
     * @param iterator 新增SKU迭代器
     * @param sku 当前SKU
     * @param isEnding 是否收尾
     * @param unscheduledReasonCountMap 未排原因统计
     * @return true-已写未排并移出待排队列；false-不需要处理
     */
    private boolean handleSmallEndingSurplusSkipIfNecessary(LhScheduleContext context,
                                                            Iterator<SkuScheduleDTO> iterator,
                                                            SkuScheduleDTO sku,
                                                            boolean isEnding,
                                                            Map<String, Integer> unscheduledReasonCountMap) {
        if (!SmallEndingSurplusSkipRule.shouldSkip(context, sku, isEnding)) {
            if (isSmallEndingSurplusToleranceMatched(context, sku, isEnding)) {
                traceSmallEndingSurplusJudge(context, sku, isEnding, false);
            }
            return false;
        }
        addUnscheduledResult(context, sku, Math.max(0, sku.getSurplusQty()),
                SMALL_ENDING_SURPLUS_UNSCHEDULED_REASON, unscheduledReasonCountMap);
        getTargetScheduleQtyResolver().removeActiveEmbryoSku(
                context, sku, SMALL_ENDING_SURPLUS_UNSCHEDULED_REASON);
        removeCurrentNewSpecSku(context, iterator, sku);
        traceSmallEndingSurplusJudge(context, sku, isEnding, true);
        return true;
    }

    /**
     * 判断新增 SKU 是否已进入收尾小余量阈值范围。
     *
     * @param context 排程上下文
     * @param sku 当前SKU
     * @param isEnding 是否收尾
     * @return true-收尾且余量小于等于参数值；false-不进入前日夜班判断
     */
    private boolean isSmallEndingSurplusToleranceMatched(LhScheduleContext context,
                                                         SkuScheduleDTO sku,
                                                         boolean isEnding) {
        return isEnding && Objects.nonNull(sku)
                && Math.max(0, sku.getSurplusQty()) <= SmallEndingSurplusSkipRule.resolveToleranceQty(context);
    }

    /**
     * 输出新增收尾小余量规则判断日志。
     *
     * @param context 排程上下文
     * @param sku 当前SKU
     * @param isEnding 是否收尾
     * @param skipped 是否跳过排产
     */
    private void traceSmallEndingSurplusJudge(LhScheduleContext context,
                                              SkuScheduleDTO sku,
                                              boolean isEnding,
                                              boolean skipped) {
        if (Objects.isNull(sku) || !isEnding) {
            return;
        }
        int toleranceQty = SmallEndingSurplusSkipRule.resolveToleranceQty(context);
        int previousNightPlanQty = SmallEndingSurplusSkipRule.resolveTargetPreviousT1NightPlanQty(
                context, sku.getMaterialCode());
        boolean previousNightFull = SmallEndingSurplusSkipRule.isTargetPreviousT1NightFull(context, sku);
        StringBuilder detail = new StringBuilder(192);
        detail.append("新增收尾小余量业务目标日前一日夜班判断, materialCode: ")
                .append(sku.getMaterialCode())
                .append(", surplusQty: ")
                .append(Math.max(0, sku.getSurplusQty()))
                .append(", toleranceQty: ")
                .append(toleranceQty)
                .append(", targetPreviousT1NightPlanQty: ")
                .append(previousNightPlanQty)
                .append(", shiftCapacity: ")
                .append(sku.getShiftCapacity())
                .append(", targetPreviousT1NightFull: ")
                .append(previousNightFull)
                .append(", skipSchedule: ")
                .append(skipped);
        if (skipped) {
            detail.append(", unscheduledReason: ").append(SMALL_ENDING_SURPLUS_UNSCHEDULED_REASON);
        }
        log.info(detail.toString());
        PriorityTraceLogHelper.appendProcessLog(context, "新增收尾小余量不排产", detail.toString());
    }

    /**
     * 移除当前新增待排SKU，并同步刷新类型计数。
     * <p>当前SKU排产前可能追加历史欠产并改变日计划账本，出队后使用全量刷新，
     * 避免按出队时可变状态做增量扣减导致单控保护计数偏移。</p>
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
        refreshPendingNewSpecSkuTypeCounts(context);
    }

    /**
     * 解析无候选机台时的业务原因。
     *
     * @param context 排程上下文
     * @param sku SKU
     * @return 未排原因
     */
    private String resolveNoCandidateMachineReason(LhScheduleContext context, SkuScheduleDTO sku) {
        String mouldChangeLimitBlockedReason = resolveMouldChangeLimitBlockedReason(context, sku);
        if (StringUtils.isNotEmpty(mouldChangeLimitBlockedReason)) {
            return mouldChangeLimitBlockedReason;
        }
        if (isTypeRuleBlocked(context, sku) && isTrialConstructionStage(sku)) {
            return "试制SKU只能使用单控机台，但当前无可用单控机台或单控机台产能不足，无法排产";
        }
        if (isTypeRuleBlocked(context, sku)
                && !isTrialConstructionStage(sku)
                && !isMassTrialSku(sku)
                && context != null
                && context.getPendingSmallBatchNewSpecSkuCount() > 0) {
            return "待排小批量SKU未完成，单控机台优先保留给小批量SKU，当前正规SKU无法使用单控机台";
        }
        if (isSpecialMaterialSupportBlocked(context, sku)) {
            return "特殊材料SKU无匹配特殊支持机台，无法排产";
        }
        return "无可用硫化机台";
    }

    /**
     * 解析候选机台尝试失败后的未排原因。
     *
     * @param context 排程上下文
     * @param sku SKU
     * @param failReason 失败原因
     * @return 未排原因
     */
    private String resolveScheduleFailureReason(LhScheduleContext context, SkuScheduleDTO sku,
                                                NewSpecFailReasonEnum failReason) {
        String mouldChangeLimitBlockedReason = resolveMouldChangeLimitBlockedReason(context, sku);
        if (StringUtils.isNotEmpty(mouldChangeLimitBlockedReason)) {
            return mouldChangeLimitBlockedReason;
        }
        if (isTrialConstructionStage(sku)
                && NewSpecFailReasonEnum.NO_CAPACITY_IN_SCHEDULE_WINDOW == failReason) {
            return "试制SKU只能使用单控机台，但单控机台已被全局排序更靠前的SKU占用，或当前单控机台产能不足，无法排产";
        }
        return failReason.getDescription();
    }

    /**
     * 解析换模/换活字块日上限阻塞原因。
     *
     * @param context 排程上下文
     * @param sku SKU
     * @return 阻塞原因，无则返回null
     */
    private String resolveMouldChangeLimitBlockedReason(LhScheduleContext context, SkuScheduleDTO sku) {
        if (context == null || sku == null || StringUtils.isEmpty(sku.getMaterialCode())) {
            return null;
        }
        return context.getMouldChangeLimitBlockedReasonMap().get(sku.getMaterialCode());
    }

    /**
     * 判断是否命中特殊材料支持能力阻塞。
     *
     * @param context 排程上下文
     * @param sku SKU
     * @return true-基础条件可匹配，但缺少特殊支持机台
     */
    private boolean isSpecialMaterialSupportBlocked(LhScheduleContext context, SkuScheduleDTO sku) {
        SpecialMaterialMatchResult matchResult = LhSpecialMaterialUtil.resolveMatchResult(context, sku);
        if (context == null || sku == null || matchResult == null || !matchResult.isSpecial()
                || CollectionUtils.isEmpty(context.getMachineScheduleMap())) {
            return false;
        }
        boolean hasBaseMatchedMachine = false;
        for (MachineScheduleDTO machine : context.getMachineScheduleMap().values()) {
            if (machine == null || !MachineStatusUtil.isEnabled(machine.getStatus())) {
                continue;
            }
            if (!LhMachineHardMatchUtil.isInchInRange(
                    LhMachineHardMatchUtil.parseInch(sku.getProSize()),
                    machine.getDimensionMinimum(), machine.getDimensionMaximum())) {
                continue;
            }
            if (!LhMachineHardMatchUtil.isMouldSetMatched(context, sku, machine)) {
                continue;
            }
            hasBaseMatchedMachine = true;
            if (LhMachineHardMatchUtil.isSpecialMaterialSupported(matchResult, machine)) {
                return false;
            }
        }
        return hasBaseMatchedMachine;
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
        IMouldChangeBalanceStrategy localSearchMouldChangeBalance =
                resolveLocalSearchMouldChangeBalance(context, mouldChangeBalance);
        return localSearchMachineAllocator.selectBestMachine(
                context, windowSkuList, candidates, shifts, machineMatch, localSearchMouldChangeBalance,
                inspectionBalance, capacityCalculate);
    }

    /**
     * 解析局部搜索使用的换模分配策略。
     * <p>关闭换模均衡时，评估链路也必须使用基础换模口径，避免机台评估被配额均衡影响。</p>
     *
     * @param context 排程上下文
     * @param mouldChangeBalance 原换模均衡策略
     * @return 局部搜索使用的换模分配策略
     */
    private IMouldChangeBalanceStrategy resolveLocalSearchMouldChangeBalance(
            LhScheduleContext context,
            IMouldChangeBalanceStrategy mouldChangeBalance) {
        if (isChangeoverBalanceEnabled(context)) {
            return mouldChangeBalance;
        }
        return new IMouldChangeBalanceStrategy() {
            @Override
            public boolean hasCapacity(LhScheduleContext ctx, Date targetDate) {
                return true;
            }

            @Override
            public Date allocateMouldChange(LhScheduleContext ctx, String machineCode, Date endingTime) {
                return allocateBasicMouldChangeStartTime(
                        ctx, machineCode, endingTime, LhScheduleTimeUtil.getMouldChangeTotalHours(ctx));
            }

            @Override
            public Date allocateMouldChange(LhScheduleContext ctx,
                                            String machineCode,
                                            Date endingTime,
                                            int switchDurationHours) {
                return allocateBasicMouldChangeStartTime(ctx, machineCode, endingTime, switchDurationHours);
            }

            @Override
            public void rollbackMouldChange(LhScheduleContext ctx, Date allocatedTime) {
                // 基础换模分配不占用均衡配额，无需回滚。
            }

            @Override
            public int getRemainingCapacity(LhScheduleContext ctx, Date targetDate) {
                return Integer.MAX_VALUE;
            }
        };
    }

    private MachineScheduleDTO selectCandidateMachine(LhScheduleContext context,
                                                      SkuScheduleDTO sku,
                                                      NewSpecCandidateCache candidateCache,
                                                      Set<String> excludedMachineCodes,
                                                      IMachineMatchStrategy machineMatch,
                                                      MachineScheduleDTO preferredTrialMachine,
                                                      ProductionQuantityPolicy quantityPolicy) {
        List<MachineScheduleDTO> singleControlCandidates = candidateCache.getSingleControlCandidates();
        List<MachineScheduleDTO> normalCandidates = candidateCache.getNormalCandidates();
        logNewSpecMachineTypeSplit(context, sku, singleControlCandidates, normalCandidates,
                excludedMachineCodes, candidateCache);
        if (shouldOnlyUseSingleControlCandidate(sku)) {
            MachineScheduleDTO singleControlMachine = selectCandidateMachineFromScopedList(
                    context, sku, singleControlCandidates, machineMatch, preferredTrialMachine, quantityPolicy,
                    candidateCache);
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
                    context, sku, singleControlCandidates, machineMatch, preferredTrialMachine, quantityPolicy,
                    candidateCache);
            if (singleControlMachine != null) {
                log.info("新增排产{}SKU优先消化单控机台, materialCode: {}, machineCode: {}, remainingSingleControlCount: {}, normalCandidateCount: {}",
                        resolveNewSpecSkuType(sku), sku.getMaterialCode(), singleControlMachine.getMachineCode(),
                        singleControlCandidates.size(), normalCandidates.size());
                return singleControlMachine;
            }
            log.info("新增排产{}SKU单控机台均无法承接，开始尝试普通机台, materialCode: {}, normalCandidateCount: {}",
                    resolveNewSpecSkuType(sku), sku.getMaterialCode(), normalCandidates.size());
            return selectCandidateMachineFromScopedList(
                    context, sku, normalCandidates, machineMatch, null, quantityPolicy,
                    candidateCache);
        }
        MachineScheduleDTO normalMachine = selectCandidateMachineFromScopedList(
                context, sku, normalCandidates, machineMatch, null, quantityPolicy,
                candidateCache);
        if (normalMachine != null) {
            return normalMachine;
        }
        return selectCandidateMachineFromScopedList(
                context, sku, singleControlCandidates, machineMatch, null, quantityPolicy,
                candidateCache);
    }

    /**
     * 校验新增机台的模具资源并预占模具。
     * <p>增机台会让同一SKU同时占用多台机台，必须按候选机台模数扣减可用模具数量。
     * 如果模具不足，只能跳过当前候选机台继续尝试后续机台，不能强行生成不满足模具条件的排程结果。</p>
     *
     * @param context 排程上下文
     * @param sku 当前SKU
     * @param candidateMachine 候选机台
     * @param originalAddMachineCount 原候选增机台数量
     * @param actualAllowedAddMachineCount 已成功落地的增机台数量
     * @return 模具资源分配结果
     */
    private MouldResourceAllocationResult tryAllocateMouldResourceForAddMachine(
            LhScheduleContext context,
            SkuScheduleDTO sku,
            MachineScheduleDTO candidateMachine,
            int originalAddMachineCount,
            int actualAllowedAddMachineCount) {
        MouldResourceContext mouldResourceContext = resolveMouldResourceContext(context);
        MouldResourceAllocationResult allocationResult = mouldResourceContext.tryAllocate(
                sku.getMaterialCode(), candidateMachine.getMachineCode());
        String productionType = sku.isContinuousCompensationSku() ? "续作排产" : "新增排产";
        if (allocationResult.isAllowed()) {
            log.debug("SKU增机台模具资源校验通过, materialCode: {}, scheduleDate: {}, productionType: {}, "
                            + "machineCode: {}, machineMouldType: {}, requiredMouldQty: {}, "
                            + "availableMouldQty: {}, occupiedMouldQty: {}, remainingAvailableMouldQty: {}, "
                            + "releasedMouldCodes: {}, allocatedMouldCodes: {}",
                    sku.getMaterialCode(), LhScheduleTimeUtil.formatDate(context.getScheduleDate()), productionType,
                    candidateMachine.getMachineCode(), resolveMachineMouldTypeText(allocationResult.getRequiredMouldQty()),
                    allocationResult.getRequiredMouldQty(), allocationResult.getAvailableMouldQty(),
                    allocationResult.getOccupiedMouldQty(), allocationResult.getRemainingAvailableMouldQty(),
                    allocationResult.getReleasedMouldCodeList(), allocationResult.getAllocatedMouldCodeList());
            return allocationResult;
        }
        log.info("SKU增机台模具资源不足跳过候选机台, materialCode: {}, scheduleDate: {}, productionType: {}, "
                        + "originalAddMachineCount: {}, actualAllowedAddMachineCount: {}, candidateMachineCode: {}, "
                        + "machineMouldType: {}, requiredMouldQty: {}, availableMouldQty: {}, occupiedMouldQty: {}, "
                        + "remainingAvailableMouldQty: {}, occupiedMouldCodes: {}, unavailableMouldCodes: {}, skipReason: {}",
                sku.getMaterialCode(), LhScheduleTimeUtil.formatDate(context.getScheduleDate()), productionType,
                originalAddMachineCount, actualAllowedAddMachineCount, candidateMachine.getMachineCode(),
                resolveMachineMouldTypeText(allocationResult.getRequiredMouldQty()),
                allocationResult.getRequiredMouldQty(), allocationResult.getAvailableMouldQty(),
                allocationResult.getOccupiedMouldQty(), allocationResult.getRemainingAvailableMouldQty(),
                allocationResult.getOccupiedMouldCodeList(), allocationResult.getUnavailableMouldCodeList(),
                allocationResult.getSkipReason().getDescription());
        return allocationResult;
    }

    /**
     * 获取新增链路模具资源上下文。
     *
     * @param context 排程上下文
     * @return 模具资源上下文
     */
    private MouldResourceContext resolveMouldResourceContext(LhScheduleContext context) {
        if (context.getMouldResourceContext() == null) {
            context.setMouldResourceContext(MouldResourceContext.from(context));
        }
        return context.getMouldResourceContext();
    }

    /**
     * 释放候选机台预占模具。
     *
     * @param context 排程上下文
     * @param sku 当前SKU
     * @param allocationResult 模具资源分配结果
     */
    private void rollbackMouldResourceAllocation(LhScheduleContext context,
                                                 SkuScheduleDTO sku,
                                                 MouldResourceAllocationResult allocationResult) {
        if (context == null || sku == null || allocationResult == null || !allocationResult.isAllowed()) {
            return;
        }
        resolveMouldResourceContext(context).release(sku.getMaterialCode(), allocationResult);
    }

    /**
     * 解析机台模数文本。
     *
     * @param requiredMouldQty 所需模具数量
     * @return 单模/双模
     */
    private String resolveMachineMouldTypeText(int requiredMouldQty) {
        return requiredMouldQty > 1 ? "双模" : "单模";
    }

    private MachineScheduleDTO selectCandidateMachineFromScopedList(LhScheduleContext context,
                                                                    SkuScheduleDTO sku,
                                                                    List<MachineScheduleDTO> scopedCandidates,
                                                                    IMachineMatchStrategy machineMatch,
                                                                    MachineScheduleDTO preferredTrialMachine,
                                                                    ProductionQuantityPolicy quantityPolicy,
                                                                    NewSpecCandidateCache candidateCache) {
        if (CollectionUtils.isEmpty(scopedCandidates)) {
            return null;
        }
        MachineScheduleDTO preferredContinuousMachine =
                resolvePreferredContinuousCompensationMachine(sku, scopedCandidates);
        if (preferredContinuousMachine != null) {
            log.info("新增排产补偿SKU优先锁回原续作机台, materialCode: {}, machineCode: {}",
                    sku.getMaterialCode(), preferredContinuousMachine.getMachineCode());
            return preferredContinuousMachine;
        }
        if (preferredTrialMachine != null && containsMachine(scopedCandidates, preferredTrialMachine.getMachineCode())) {
            log.info("新增排产优先尝试试制/小批量预选机台, materialCode: {}, machineCode: {}",
                    sku.getMaterialCode(), preferredTrialMachine.getMachineCode());
            return preferredTrialMachine;
        }
        if (quantityPolicy != null && quantityPolicy.isFullRunForNonTailMachine()) {
            return machineMatch.selectBestMachine(context, sku, scopedCandidates, EMPTY_STRING_SET);
        }
        MachineScheduleDTO finishRemainingFirstMachine = resolveCanFinishRemainingQtyFirst(
                context, sku, scopedCandidates, EMPTY_STRING_SET, candidateCache);
        if (finishRemainingFirstMachine != null) {
            log.info("新增排产优先选择可单机收完剩余量的机台, materialCode: {}, machineCode: {}, remainingQty: {}",
                    sku.getMaterialCode(), finishRemainingFirstMachine.getMachineCode(),
                    Math.max(0, sku.getRemainingScheduleQty()));
            return finishRemainingFirstMachine;
        }
        MachineScheduleDTO tailConcentratedMachine = resolveTailConcentratedSplitMachine(
                context, sku, scopedCandidates, EMPTY_STRING_SET, candidateCache);
        if (tailConcentratedMachine != null) {
            log.info("新增排产优先选择可保留尾量集中能力的机台, materialCode: {}, machineCode: {}, remainingQty: {}",
                    sku.getMaterialCode(), tailConcentratedMachine.getMachineCode(),
                    Math.max(0, sku.getRemainingScheduleQty()));
            return tailConcentratedMachine;
        }
        return machineMatch.selectBestMachine(context, sku, scopedCandidates, EMPTY_STRING_SET);
    }

    /**
     * 解析续作释放补偿 SKU 在当前选机回合的原续作优先机台。
     *
     * @param sku 当前待排 SKU
     * @param scopedCandidates 当前作用域候选机台
     * @return 可直接锁回的原续作机台；不存在时返回 null
     */
    private MachineScheduleDTO resolvePreferredContinuousCompensationMachine(SkuScheduleDTO sku,
                                                                             List<MachineScheduleDTO> scopedCandidates) {
        if (sku == null || !sku.isContinuousCompensationSku()
                || StringUtils.isEmpty(sku.getPreferredContinuousMachineCode())
                || CollectionUtils.isEmpty(scopedCandidates)) {
            return null;
        }
        for (MachineScheduleDTO candidate : scopedCandidates) {
            if (candidate == null) {
                continue;
            }
            if (StringUtils.equals(candidate.getMachineCode(), sku.getPreferredContinuousMachineCode())) {
                return candidate;
            }
        }
        return null;
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

    private void logNewSpecMachineCandidateSnapshot(LhScheduleContext context,
                                                    SkuScheduleDTO sku,
                                                    List<MachineScheduleDTO> candidates,
                                                    Set<String> excludedMachineCodes,
                                                    Map<String, String> excludedMachineReasonMap) {
        if (sku == null) {
            return;
        }
        boolean needLog = sku.isSmallBatchValidation()
                || isMassTrialSku(sku)
                || isTrialConstructionStage(sku)
                || containsMachineCode(candidates, "K1501L")
                || containsMachineCode(candidates, "K1501R");
        if (!needLog) {
            return;
        }
        log.info("新增SKU候选快照, materialCode: {}, skuType: {}, surplusQty: {}, remainingQty: {}, threshold: {}, isSmallBatch: {}, "
                        + "待排小批量SKU数: {}, 候选机台: {}, 排除机台: {}, K1501L候选: {}, K1501R候选: {}, 已有排除原因: {}",
                sku.getMaterialCode(), resolveNewSpecSkuType(sku), sku.getSurplusQty(),
                sku.getRemainingScheduleQty(), resolveSmallBatchThreshold(context), sku.isSmallBatchValidation(),
                context == null ? 0 : context.getPendingSmallBatchNewSpecSkuCount(),
                joinMachineCodes(candidates), CollectionUtils.isEmpty(excludedMachineCodes) ? "-" : String.join(",", excludedMachineCodes),
                containsMachineCode(candidates, "K1501L"), containsMachineCode(candidates, "K1501R"),
                CollectionUtils.isEmpty(excludedMachineReasonMap) ? "-" : excludedMachineReasonMap.values());
    }

    private void logNewSpecMachineTypeSplit(LhScheduleContext context,
                                            SkuScheduleDTO sku,
                                            List<MachineScheduleDTO> singleControlCandidates,
                                            List<MachineScheduleDTO> normalCandidates,
                                            Set<String> excludedMachineCodes,
                                            NewSpecCandidateCache candidateCache) {
        if (sku == null) {
            return;
        }
        boolean needLog = sku.isSmallBatchValidation()
                || isMassTrialSku(sku)
                || isTrialConstructionStage(sku)
                || containsMachineCode(singleControlCandidates, "K1501L")
                || containsMachineCode(singleControlCandidates, "K1501R")
                || containsMachineCode(normalCandidates, "K1501L")
                || containsMachineCode(normalCandidates, "K1501R");
        if (!needLog) {
            return;
        }
        log.info("新增SKU选机分组, materialCode: {}, skuType: {}, 待排小批量SKU数: {}, 单控候选: {}, 普通候选: {}, "
                        + "单控剩余产能: {}, K1501L单控: {}, K1501R单控: {}, K1501L普通: {}, K1501R普通: {}, 已排除机台: {}",
                sku.getMaterialCode(), resolveNewSpecSkuType(sku),
                context == null ? 0 : context.getPendingSmallBatchNewSpecSkuCount(),
                joinMachineCodes(singleControlCandidates), joinMachineCodes(normalCandidates),
                resolveMachineCapacitySummary(context, sku, singleControlCandidates, candidateCache),
                containsMachineCode(singleControlCandidates, "K1501L"),
                containsMachineCode(singleControlCandidates, "K1501R"),
                containsMachineCode(normalCandidates, "K1501L"),
                containsMachineCode(normalCandidates, "K1501R"),
                CollectionUtils.isEmpty(excludedMachineCodes) ? "-" : String.join(",", excludedMachineCodes));
    }

    private boolean containsMachineCode(List<MachineScheduleDTO> candidates, String machineCode) {
        if (CollectionUtils.isEmpty(candidates) || StringUtils.isEmpty(machineCode)) {
            return false;
        }
        for (MachineScheduleDTO candidate : candidates) {
            if (candidate != null && StringUtils.equalsIgnoreCase(machineCode, candidate.getMachineCode())) {
                return true;
            }
        }
        return false;
    }

    private String joinMachineCodes(List<MachineScheduleDTO> candidates) {
        if (CollectionUtils.isEmpty(candidates)) {
            return "-";
        }
        StringBuilder builder = new StringBuilder();
        for (MachineScheduleDTO candidate : candidates) {
            if (candidate == null || StringUtils.isEmpty(candidate.getMachineCode())) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(",");
            }
            builder.append(candidate.getMachineCode());
        }
        return builder.length() == 0 ? "-" : builder.toString();
    }

    private String resolveMachineCapacitySummary(LhScheduleContext context,
                                                 SkuScheduleDTO sku,
                                                 List<MachineScheduleDTO> candidates,
                                                 NewSpecCandidateCache candidateCache) {
        if (context == null || sku == null || CollectionUtils.isEmpty(candidates)) {
            return "-";
        }
        StringBuilder builder = new StringBuilder();
        for (MachineScheduleDTO candidate : candidates) {
            if (candidate == null || StringUtils.isEmpty(candidate.getMachineCode())) {
                continue;
            }
            int availableCapacity = resolveCachedMachineAvailableCapacityInWindow(
                    context, sku, candidate, candidateCache);
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(candidate.getMachineCode()).append("=").append(availableCapacity);
        }
        return builder.length() == 0 ? "-" : builder.toString();
    }

    private int resolveSmallBatchThreshold(LhScheduleContext context) {
        if (context != null && Objects.nonNull(context.getScheduleConfig())) {
            return context.getScheduleConfig().getSmallBatchSkuThreshold();
        }
        if (context == null) {
            return LhScheduleConstant.SMALL_BATCH_SKU_THRESHOLD;
        }
        return context.getParamIntValue(LhScheduleParamConstant.SMALL_BATCH_SKU_THRESHOLD,
                LhScheduleConstant.SMALL_BATCH_SKU_THRESHOLD);
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
        String configPlusShiftType = ShiftCapacityResolverUtil.resolveOddShiftCapacityPlusShiftType(context);
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
            int actualShiftPlanQty = ShiftCapacityResolverUtil.resolveActualShiftPlanQty(
                    shiftCapacity, shift, configPlusShiftType, ScheduleTypeEnum.NEW_SPEC.getCode());
            boolean oddShiftAdjustEnabled = ShiftCapacityResolverUtil.isOddShiftCapacityAdjustEnabled(
                    shiftCapacity, shift, configPlusShiftType, ScheduleTypeEnum.NEW_SPEC.getCode());
            log.debug("奇数班产修正检查, 当前流程: 新增排程, materialCode: {}, machineCode: {}, 参数是否配置: {}, "
                            + "参数值: {}, 配置值是否合法: {}, 是否启用: {}, 未启用原因: {}, 原始班产: {}, "
                            + "班次序号: {}, 当前班别: {}, 当前班次修正后的计划量: {}, 班产落库字段值: {}",
                    sku.getMaterialCode(), machine.getMachineCode(), StringUtils.isNotEmpty(configPlusShiftType),
                    configPlusShiftType,
                    ShiftCapacityResolverUtil.isOddShiftCapacityPlusShiftTypeValid(configPlusShiftType),
                    oddShiftAdjustEnabled,
                    ShiftCapacityResolverUtil.resolveOddShiftCapacityDisabledReason(
                            shiftCapacity, shift, configPlusShiftType, ScheduleTypeEnum.NEW_SPEC.getCode()),
                    shiftCapacity, shift.getShiftIndex(), shift.resolveShiftTypeEnum(), actualShiftPlanQty,
                    shiftCapacity);
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
                    dryIceDurationHours,
                    shift,
                    configPlusShiftType,
                    ScheduleTypeEnum.NEW_SPEC.getCode());
            shiftMaxQty = ShiftProductionControlUtil.deductCapacityByControl(control, shiftMaxQty, mouldQty);
            if (shiftMaxQty <= 0) {
                continue;
            }
            if (oddShiftAdjustEnabled) {
                log.info("奇数班产修正命中, 当前流程: 新增排程, materialCode: {}, machineCode: {}, 参数值: {}, "
                                + "原始班产: {}, 班次序号: {}, 当前班别: {}, 修正后班次计划量: {}, 班产落库字段值: {}",
                        sku.getMaterialCode(), machine.getMachineCode(), configPlusShiftType, shiftCapacity,
                        shift.getShiftIndex(), shift.resolveShiftTypeEnum(), actualShiftPlanQty, shiftCapacity);
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
                                                       MachineScheduleDTO candidateMachine,
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
        if (isNoWindowHistoryShortageMouldMachineCountEnabled(sku)) {
            // 窗口无日计划但存在历史欠产时，机台数以 mould_change_info 为准，不能被单机台窗口产能短路。
            return false;
        }
        if (candidateTargetQty <= 0 || maxQtyToWindowEnd < candidateTargetQty) {
            return false;
        }
        if (isSmallBatchSingleControlMachine(context, sku, candidateMachine)) {
            // 小批量 SKU 优先占用单控运行态机台，命中后应补满该单控侧窗口，避免被后续普通 SKU 截断。
            return true;
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
     * 判断当前候选是否为小批量 SKU 命中的单控运行态机台。
     *
     * @param context 排程上下文
     * @param sku SKU
     * @param candidateMachine 当前候选机台
     * @return true-小批量命中单控运行态机台
     */
    private boolean isSmallBatchSingleControlMachine(LhScheduleContext context,
                                                     SkuScheduleDTO sku,
                                                     MachineScheduleDTO candidateMachine) {
        if (Objects.isNull(sku) || Objects.isNull(candidateMachine) || !sku.isSmallBatchValidation()) {
            return false;
        }
        return LhSingleControlMachineUtil.isConfiguredSingleControlMachine(
                context, candidateMachine.getMachineCode());
    }

    /**
     * 新增非收尾首日无可用日计划额度时，将首个可排时间推进到首个可承接的生产日。
     *
     * @param context 排程上下文
     * @param sku SKU
     * @param firstProductionStartTime 当前首个可排时间
     * @param shifts 排程窗口班次
     * @param isEnding 是否收尾
     * @return 调整后的首个可排时间
     */
    private Date alignFirstProductionStartTimeByDailyPlan(LhScheduleContext context,
                                                          SkuScheduleDTO sku,
                                                          Date firstProductionStartTime,
                                                          List<LhShiftConfigVO> shifts,
                                                          boolean isEnding) {
        if (!shouldDelayFirstProductionForNoPlanDate(sku, firstProductionStartTime, isEnding)) {
            return firstProductionStartTime;
        }
        LocalDate productionDate = firstProductionStartTime.toInstant()
                .atZone(ZoneId.systemDefault()).toLocalDate();
        SkuDailyPlanQuotaDTO currentQuota = sku.getDailyPlanQuotaMap().get(productionDate);
        if (hasSchedulableDailyPlanQuota(sku, currentQuota)) {
            return firstProductionStartTime;
        }
        LocalDate nextPlanDate = resolveNextPositiveDailyPlanDate(
                sku, sku.getDailyPlanQuotaMap(), productionDate, resolveScheduleTargetLocalDate(context));
        if (Objects.isNull(nextPlanDate)) {
            return firstProductionStartTime;
        }
        Date nextPlanDateStartTime = resolveFirstShiftStartTime(shifts, nextPlanDate);
        if (Objects.isNull(nextPlanDateStartTime) || !nextPlanDateStartTime.after(firstProductionStartTime)) {
            return firstProductionStartTime;
        }
        log.info("新增SKU首个可排时间按日计划额度顺延, materialCode: {}, compensationSku: {}, "
                        + "fromProductionDate: {}, toProductionDate: {}, fromStartTime: {}, toStartTime: {}",
                sku.getMaterialCode(), sku.isContinuousCompensationSku(), productionDate, nextPlanDate,
                LhScheduleTimeUtil.formatDateTime(firstProductionStartTime),
                LhScheduleTimeUtil.formatDateTime(nextPlanDateStartTime));
        return nextPlanDateStartTime;
    }

    private boolean shouldDelayFirstProductionForNoPlanDate(SkuScheduleDTO sku,
                                                            Date firstProductionStartTime,
                                                            boolean isEnding) {
        if (Objects.isNull(sku) || Objects.isNull(firstProductionStartTime) || isEnding
                || CollectionUtils.isEmpty(sku.getDailyPlanQuotaMap())) {
            return false;
        }
        if (sku.isContinuousCompensationSku()) {
            return true;
        }
        if (Math.max(0, sku.getMonthlyHistoryShortageQty()) > 0) {
            return false;
        }
        return !StringUtils.equals(ConstructionStageEnum.TRIAL.getCode(), sku.getConstructionStage());
    }

    private boolean hasSchedulableDailyPlanQuota(SkuScheduleDTO sku, SkuDailyPlanQuotaDTO quota) {
        if (Objects.nonNull(sku) && sku.isContinuousCompensationSku()) {
            // 续作补偿只能承接 S4.4 后剩余的日计划额度，首日已满足时不能在首日借用后续额度换模补量。
            return Objects.nonNull(quota) && Math.max(0, quota.getRemainingQty()) > 0;
        }
        return hasPositiveDailyPlanQuota(quota);
    }

    private boolean hasPositiveDailyPlanQuota(SkuDailyPlanQuotaDTO quota) {
        return Objects.nonNull(quota)
                && (Math.max(0, quota.getDayPlanQty()) > 0 || Math.max(0, quota.getRemainingQty()) > 0);
    }

    private LocalDate resolveNextPositiveDailyPlanDate(SkuScheduleDTO sku,
                                                       Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap,
                                                       LocalDate productionDate,
                                                       LocalDate windowEndDate) {
        if (CollectionUtils.isEmpty(quotaMap) || Objects.isNull(productionDate)) {
            return null;
        }
        for (Map.Entry<LocalDate, SkuDailyPlanQuotaDTO> entry : quotaMap.entrySet()) {
            LocalDate date = entry.getKey();
            if (Objects.isNull(date) || !date.isAfter(productionDate)
                    || (Objects.nonNull(windowEndDate) && date.isAfter(windowEndDate))) {
                continue;
            }
            if (hasSchedulableDailyPlanQuota(sku, entry.getValue())) {
                return date;
            }
        }
        return null;
    }

    private Date resolveFirstShiftStartTime(List<LhShiftConfigVO> shifts, LocalDate productionDate) {
        if (CollectionUtils.isEmpty(shifts) || Objects.isNull(productionDate)) {
            return null;
        }
        for (LhShiftConfigVO shift : shifts) {
            if (Objects.isNull(shift) || Objects.isNull(shift.getShiftStartDateTime())
                    || Objects.isNull(shift.getWorkDate())) {
                continue;
            }
            LocalDate shiftWorkDate = shift.getWorkDate().toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
            if (productionDate.equals(shiftWorkDate)) {
                return shift.getShiftStartDateTime();
            }
        }
        return null;
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
        int remainingTargetQty = Math.max(0, targetQty - scheduledQty);
        if (remainingTargetQty <= 0 || defaultPlanQty <= 0) {
            return defaultPlanQty;
        }
        int availableMachineCount = countAvailableCandidateMachines(candidates, excludedMachineCodes);
        int requiredMachineCountByDailyCapacity = resolveRequiredMachineCountByDailyCapacity(
                context, sku, candidates, excludedMachineCodes, policy, segment, candidateMachine,
                shifts, capacityCalculate, remainingTargetQty, availableMachineCount);
        if (shouldFillMachineToWindowEndForFutureDayDemand(
                context, sku, policy, segment, requiredMachineCountByDailyCapacity)) {
            segment.setFutureDayDemandMachineCount(requiredMachineCountByDailyCapacity);
            log.info("新增SKU因T+3日计划需求保留窗口内满班, materialCode: {}, machineCode: {}, "
                            + "remainingTargetQty: {}, maxQtyToWindowEnd: {}, dayN推导机台数: {}",
                    sku.getMaterialCode(), segment.getMachineCode(), remainingTargetQty,
                    segment.getMaxQtyToWindowEnd(), requiredMachineCountByDailyCapacity);
            return segment.getMaxQtyToWindowEnd();
        }
        boolean suppressTotalExpansion = isDailyCapacitySimulationSatisfied(
                sku, requiredMachineCountByDailyCapacity);
        if (MachineScheduleRole.FULL_RUN_MACHINE == segment.getRole()
                && shouldUseFormalNonEndingMinimumTarget(context, sku, policy)
                && hasMultiplePositiveQuotaDays(sku)
                && !suppressTotalExpansion) {
            return defaultPlanQty;
        }
        boolean needAddMachineByTotal = !suppressTotalExpansion
                && scheduledQty + segment.getMaxQtyToWindowEnd() < targetQty;
        boolean needAddMachineByDailyCapacity = requiredMachineCountByDailyCapacity > 1;
        if (!needAddMachineByTotal && !needAddMachineByDailyCapacity) {
            if (suppressTotalExpansion) {
                segment.setStopAfterCurrentForSmallShortage(true);
            }
            if (policy.isAllowFillStartedShift()) {
                log.info("新增SKU尾机台进入非收尾补满判定, materialCode: {}, machineCode: {}, "
                                + "remainingTargetQty: {}, defaultPlanQty: {}, maxQtyToWindowEnd: {}, role: {}",
                        sku.getMaterialCode(), segment.getMachineCode(), remainingTargetQty,
                        defaultPlanQty, segment.getMaxQtyToWindowEnd(), segment.getRole());
                return resolveSettledTailMachinePlanQty(segment, remainingTargetQty, defaultPlanQty);
            }
            log.info("新增SKU当前班次因严格目标量达标停止扩量, materialCode: {}, machineCode: {}, "
                            + "remainingTargetQty: {}, maxQtyToWindowEnd: {}, role: {}",
                    sku.getMaterialCode(), segment.getMachineCode(), remainingTargetQty,
                    segment.getMaxQtyToWindowEnd(), segment.getRole());
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
     * 判断是否因 T+3 日计划需求保留当前机台到窗口结束。
     * <p>本规则只适用于欠产未超过阈值的新增排产非收尾 SKU：
     * dayN 模拟已确认需要多机台保障 T+3 日计划时，T+2 的可用班次也应按班产排满；
     * 实际扣账仍不提前消费 T+3，超出 T~T+2 额度的部分沿用原满班补齐账本记录。</p>
     *
     * @param context 排程上下文
     * @param sku SKU
     * @param policy 排产数量策略
     * @param segment 当前机台生产段
     * @param requiredMachineCountByDailyCapacity dayN 模拟推导机台数
     * @return true-当前机台按窗口内有效产能排满；false-沿用原拆量
     */
    private boolean shouldFillMachineToWindowEndForFutureDayDemand(
            LhScheduleContext context,
            SkuScheduleDTO sku,
            ProductionQuantityPolicy policy,
            MachineProductionSegment segment,
            int requiredMachineCountByDailyCapacity) {
        if (context == null || sku == null || policy == null || segment == null
                || requiredMachineCountByDailyCapacity <= 1
                || segment.getMaxQtyToWindowEnd() <= 0
                || policy.isEnding()
                || policy.isStrictUpperLimit()
                || !policy.isAllowFillStartedShift()) {
            return false;
        }
        int threshold = resolveNewSpecShortageAddMachineThreshold(context);
        if (threshold <= 0 || Math.max(0, sku.getMonthlyHistoryShortageQty()) > threshold) {
            return false;
        }
        LocalDate windowEndDate = resolveScheduleTargetLocalDate(context);
        if (Objects.isNull(windowEndDate) || CollectionUtils.isEmpty(sku.getDailyPlanQuotaMap())) {
            return false;
        }
        SkuDailyPlanQuotaDTO nextDayQuota = sku.getDailyPlanQuotaMap().get(windowEndDate.plusDays(1));
        return (Objects.nonNull(nextDayQuota) && Math.max(0, nextDayQuota.getDayPlanQty()) > 0)
                || Math.max(0, sku.getNextDayPlanQtyAfterWindow()) > 0;
    }

    /**
     * 判断 dayN 理论产能模拟是否已经确认当前启用机台满足增机台规则。
     * <p>小欠产模式下，8班窗口总产能和后一天3班产能均按理论班产判断；
     * 该结果用于阻断后续按真实换模后窗口缺口继续扩机台。</p>
     *
     * @param sku SKU
     * @param requiredMachineCountByDailyCapacity dayN 模拟推导的当前新增阶段所需机台数
     * @return true-当前机台已满足增机台规则；false-仍允许按缺口继续尝试后续机台
     */
    private boolean isDailyCapacitySimulationSatisfied(SkuScheduleDTO sku,
                                                       int requiredMachineCountByDailyCapacity) {
        return requiredMachineCountByDailyCapacity == 1
                && sku != null
                && sku.getShiftCapacity() > 0
                && !CollectionUtils.isEmpty(sku.getDailyPlanQuotaMap());
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
        if (defaultPlanQty >= segment.getMaxQtyToWindowEnd()) {
            log.info("新增SKU尾机台保持整段补满, materialCode: {}, machineCode: {}, "
                            + "remainingTargetQty: {}, roundedRemainingQty: {}, defaultPlanQty: {}, maxQtyToWindowEnd: {}",
                    segment.getMaterialCode(), segment.getMachineCode(), remainingTargetQty,
                    roundedRemainingQty, defaultPlanQty, segment.getMaxQtyToWindowEnd());
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
        if (policy.isStrictUpperLimit() && !policy.isEnding()
                && !isNoWindowHistoryShortageMouldMachineCountEnabled(sku)) {
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
        LocalDate windowEndDate = resolveScheduleTargetLocalDate(context);
        request.setDailyPlanQuotaMap(buildSimulationQuotaMap(sku, remainingTargetQty, windowEndDate));
        List<Map<LocalDate, Integer>> existingMachineCapacityMaps = buildExistingSameMaterialCapacityMaps(
                context, sku, candidateMachine, shifts, request.getDailyPlanQuotaMap());
        request.setMachineDailyCapacityList(buildSimulationMachineCapacityList(
                context, sku, candidates, excludedMachineCodes, policy, segment, candidateMachine,
                shifts, capacityCalculate, request.getDailyPlanQuotaMap(), existingMachineCapacityMaps));
        request.setInitialActiveMachines(Math.max(1, existingMachineCapacityMaps.size() + 1));
        request.setShiftCapacity(Math.max(0, sku.getShiftCapacity()));
        String configPlusShiftType = ShiftCapacityResolverUtil.resolveOddShiftCapacityPlusShiftType(context);
        request.setSingleMachineWindowCapacityQty(ShiftCapacityResolverUtil.sumActualShiftPlanQty(
                shifts, Math.max(0, sku.getShiftCapacity()), configPlusShiftType, ScheduleTypeEnum.NEW_SPEC.getCode()));
        request.setSingleMachineDailyCapacityMap(ShiftCapacityResolverUtil.sumActualShiftPlanQtyByWorkDate(
                shifts, Math.max(0, sku.getShiftCapacity()), configPlusShiftType, ScheduleTypeEnum.NEW_SPEC.getCode()));
        request.setShortageLookAheadDays(resolveNewSpecShortageLookAheadDays(context));
        int monthlyHistoryShortageQty = Math.max(0, sku.getMonthlyHistoryShortageQty());
        request.setMonthlyHistoryShortageQty(monthlyHistoryShortageQty);
        request.setScheduleDayFinishQty(Math.max(0, sku.getScheduleDayFinishQty()));
        request.setWindowMonthPlanQty(sumSimulationWindowMonthPlanQty(sku.getDailyPlanQuotaMap()));
        request.setShortageAddMachineThreshold(resolveNewSpecShortageAddMachineThreshold(context));
        request.setWindowEndDate(windowEndDate);
        request.setWindowLastDayNextPlanLookAheadEnabled(true);
        request.setSceneType("newSpec");
        DailyMachineCapacitySimulationResult simulationResult =
                DailyMachineCapacitySimulationUtil.simulateExpansion(request);
        logDailyMachineCapacitySimulation(sku, segment, simulationResult);
        int requiredMachineCountByDailyCapacity = resolveRequiredNewSpecMachineCount(
                simulationResult.getFinalActiveMachines(), existingMachineCapacityMaps.size());
        int requiredMachineCountByMouldInfo = resolveRequiredShortageOnlyMachineCountByMouldInfo(
                sku, candidateMachine, existingMachineCapacityMaps.size(), availableMachineCount);
        return Math.max(requiredMachineCountByDailyCapacity, requiredMachineCountByMouldInfo);
    }

    /**
     * 判断是否启用“窗口无日计划 + 本月历史欠产”的计划模数机台数约束。
     * <p>该约束只要求窗口 dayN 原计划为 0 且本月历史欠产大于 0：
     * 月底仍有计划时，当前窗口只补历史欠产，不能提前消耗未来计划；
     * 月底无后续计划时，SKU 按整体收尾清量，但增机台数量仍要尊重月计划指定的使用模数。</p>
     *
     * @param sku SKU
     * @return true-启用计划模数机台数约束
     */
    private boolean isNoWindowHistoryShortageMouldMachineCountEnabled(SkuScheduleDTO sku) {
        return resolvePlannedMouldCountForNoWindowHistoryShortage(sku, true) > 0;
    }

    /**
     * 按月计划 mould_change_info 推导当前新增阶段还需要启用的机台数。
     * <p>mould_change_info 形如 4-2-2，仅取第一段作为计划使用模数；
     * 再按当前候选机台单模/双模模台数计算 ceil(计划使用模数 / 单台机台模数)。
     * 已经落地的同 SKU 机台会从总需求机台数中扣除，避免第二台以后重复按总机台数拆分。</p>
     *
     * @param sku SKU
     * @param candidateMachine 当前候选机台
     * @param existingMachineCount 已经启用的同 SKU 机台数
     * @param availableMachineCount 当前仍可尝试的候选机台数
     * @return 当前新增阶段还需要启用的机台数；0 表示不启用该约束
     */
    private int resolveRequiredShortageOnlyMachineCountByMouldInfo(SkuScheduleDTO sku,
                                                                   MachineScheduleDTO candidateMachine,
                                                                   int existingMachineCount,
                                                                   int availableMachineCount) {
        int plannedMouldCount = resolvePlannedMouldCountForNoWindowHistoryShortage(sku, true);
        if (plannedMouldCount <= 0 || Objects.isNull(candidateMachine) || availableMachineCount <= 0) {
            return 0;
        }
        int machineMouldCount = ShiftCapacityResolverUtil.resolveMachineMouldQty(candidateMachine);
        if (machineMouldCount <= 0) {
            log.warn("窗口无日计划历史欠产模数约束跳过，机台模数异常, materialCode: {}, machineCode: {}, machineMouldCount: {}",
                    sku.getMaterialCode(), candidateMachine.getMachineCode(), machineMouldCount);
            return 0;
        }
        int requiredTotalMachineCount = divideCeiling(plannedMouldCount, machineMouldCount);
        int requiredCurrentMachineCount = requiredTotalMachineCount - Math.max(0, existingMachineCount);
        if (requiredCurrentMachineCount <= 0) {
            return 0;
        }
        requiredCurrentMachineCount = Math.min(requiredCurrentMachineCount, availableMachineCount);
        log.info("窗口无日计划历史欠产按计划模数计算机台数, materialCode: {}, mouldChangeInfo: {}, "
                        + "计划使用模数: {}, 当前机台: {}, 单台机台模数: {}, 已启用机台数: {}, 仍需机台数: {}",
                sku.getMaterialCode(), sku.getMouldChangeInfo(), plannedMouldCount,
                candidateMachine.getMachineCode(), machineMouldCount, existingMachineCount,
                requiredCurrentMachineCount);
        return requiredCurrentMachineCount;
    }

    /**
     * 解析窗口无日计划且存在历史欠产场景的计划使用模数。
     * <p>异常数据只记录日志并跳过该约束，不强行默认单模或双模，避免无业务依据地改变原有排程结果。</p>
     *
     * @param sku SKU
     * @param logWarning 是否打印异常日志
     * @return 计划使用模数；0 表示不启用该约束
     */
    private int resolvePlannedMouldCountForNoWindowHistoryShortage(SkuScheduleDTO sku, boolean logWarning) {
        if (!isNoWindowPlanHistoryShortageSku(sku)) {
            return 0;
        }
        String mouldChangeInfo = sku.getMouldChangeInfo();
        if (StringUtils.isEmpty(mouldChangeInfo)) {
            logInvalidMouldChangeInfo(logWarning, sku, mouldChangeInfo, "为空");
            return 0;
        }
        String[] parts = mouldChangeInfo.split("-");
        String firstPart = parts.length > 0 ? parts[0].trim() : StringUtils.EMPTY;
        if (StringUtils.isEmpty(firstPart)) {
            logInvalidMouldChangeInfo(logWarning, sku, mouldChangeInfo, "第一段为空");
            return 0;
        }
        try {
            int plannedMouldCount = Integer.parseInt(firstPart);
            if (plannedMouldCount <= 0) {
                logInvalidMouldChangeInfo(logWarning, sku, mouldChangeInfo, "第一段小于等于0");
                return 0;
            }
            return plannedMouldCount;
        } catch (NumberFormatException ex) {
            logInvalidMouldChangeInfo(logWarning, sku, mouldChangeInfo, "第一段无法解析为数字");
            return 0;
        }
    }

    /**
     * 判断 SKU 是否为“窗口无日计划 + 本月历史欠产”场景。
     * <p>历史欠产是启用该规则的硬前提；若历史欠产小于等于 0，即使窗口无日计划，
     * 也不能为了收尾或满产而额外按 mould_change_info 扩机。</p>
     *
     * @param sku SKU
     * @return true-窗口无日计划且存在历史欠产
     */
    private boolean isNoWindowPlanHistoryShortageSku(SkuScheduleDTO sku) {
        if (Objects.isNull(sku) || Math.max(0, sku.getMonthlyHistoryShortageQty()) <= 0
                || CollectionUtils.isEmpty(sku.getDailyPlanQuotaMap())) {
            return false;
        }
        for (SkuDailyPlanQuotaDTO quota : sku.getDailyPlanQuotaMap().values()) {
            if (Objects.nonNull(quota) && Math.max(0, quota.getDayPlanQty()) > 0) {
                return false;
            }
        }
        return true;
    }

    private void logInvalidMouldChangeInfo(boolean logWarning,
                                           SkuScheduleDTO sku,
                                           String mouldChangeInfo,
                                           String reason) {
        if (!logWarning || Objects.isNull(sku)) {
            return;
        }
        log.warn("窗口无日计划历史欠产模数约束跳过，mouldChangeInfo异常, materialCode: {}, mouldChangeInfo: {}, reason: {}",
                sku.getMaterialCode(), mouldChangeInfo, reason);
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
        return DailyMachineExpansionPlanner.buildSimulationQuotaMap(quotaMap, remainingTargetQty);
    }

    /**
     * 构建新增排产 dayN 模拟账本快照，并保留 T+3 原始日计划用于 T+2 后看判断。
     * <p>本方法只影响加机台模拟：T+3 计划量用于判断是否保留/新增机台，
     * 实际排产扣账仍沿用 T~T+2 的追补截止日，不提前消耗 T+3 月计划。</p>
     *
     * @param quotaMap 原日计划账本
     * @param remainingTargetQty 本轮剩余目标量
     * @param windowEndDate 排程窗口结束日
     * @return 模拟账本
     */
    private Map<LocalDate, SkuDailyPlanQuotaDTO> buildSimulationQuotaMap(
            SkuScheduleDTO sku,
            int remainingTargetQty,
            LocalDate windowEndDate) {
        Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap =
                Objects.isNull(sku) ? null : sku.getDailyPlanQuotaMap();
        Map<LocalDate, SkuDailyPlanQuotaDTO> simulationQuotaMap =
                buildSimulationQuotaMap(quotaMap, remainingTargetQty);
        keepNextDayPlanForWindowLastDayLookAhead(sku, quotaMap, simulationQuotaMap, windowEndDate);
        return simulationQuotaMap;
    }

    /**
     * 保留窗口后第一天的原始日计划。
     *
     * @param sourceQuotaMap 原日计划账本
     * @param simulationQuotaMap 模拟账本
     * @param windowEndDate 排程窗口结束日
     */
    private void keepNextDayPlanForWindowLastDayLookAhead(
            SkuScheduleDTO sku,
            Map<LocalDate, SkuDailyPlanQuotaDTO> sourceQuotaMap,
            Map<LocalDate, SkuDailyPlanQuotaDTO> simulationQuotaMap,
            LocalDate windowEndDate) {
        if (Objects.isNull(sku) || CollectionUtils.isEmpty(simulationQuotaMap) || Objects.isNull(windowEndDate)) {
            return;
        }
        LocalDate nextPlanDate = windowEndDate.plusDays(1);
        int sourceDayPlanQty = resolveNextDayPlanQtyAfterWindow(sku, sourceQuotaMap, nextPlanDate);
        if (sourceDayPlanQty <= 0) {
            return;
        }
        SkuDailyPlanQuotaDTO simulationQuota = simulationQuotaMap.get(nextPlanDate);
        if (Objects.isNull(simulationQuota)) {
            simulationQuota = new SkuDailyPlanQuotaDTO();
            simulationQuota.setMaterialCode(sku.getMaterialCode());
            simulationQuota.setProductionDate(nextPlanDate);
            simulationQuotaMap.put(nextPlanDate, simulationQuota);
        } else if (sourceDayPlanQty <= Math.max(0, simulationQuota.getDayPlanQty())) {
            return;
        }
        simulationQuota.setDayPlanQty(sourceDayPlanQty);
        simulationQuota.setRemainingQty(Math.max(Math.max(0, simulationQuota.getRemainingQty()), sourceDayPlanQty));
        log.info("新增SKU dayN模拟保留T+3日计划用于窗口末日后看, materialCode: {}, productionDate: {}, dayPlanQty: {}",
                simulationQuota.getMaterialCode(), nextPlanDate, sourceDayPlanQty);
    }

    /**
     * 解析窗口后第一天日计划量。
     *
     * @param sku SKU
     * @param sourceQuotaMap 原日计划账本
     * @param nextPlanDate 窗口后第一天
     * @return T+3 日计划量
     */
    private int resolveNextDayPlanQtyAfterWindow(SkuScheduleDTO sku,
                                                 Map<LocalDate, SkuDailyPlanQuotaDTO> sourceQuotaMap,
                                                 LocalDate nextPlanDate) {
        if (!CollectionUtils.isEmpty(sourceQuotaMap)) {
            SkuDailyPlanQuotaDTO sourceQuota = sourceQuotaMap.get(nextPlanDate);
            if (Objects.nonNull(sourceQuota) && Math.max(0, sourceQuota.getDayPlanQty()) > 0) {
                return Math.max(0, sourceQuota.getDayPlanQty());
            }
        }
        return Objects.isNull(sku) ? 0 : Math.max(0, sku.getNextDayPlanQtyAfterWindow());
    }

    /**
     * 汇总新增排产模拟使用的 T~T+2 原始月计划量。
     * <p>强制欠产增机台判断需要使用月计划 dayN 汇总，不能使用已追加历史欠产后的 remainingQty，
     * 否则会把历史欠产重复计入窗口计划，导致超阈值 SKU 过度加机台。</p>
     *
     * @param quotaMap 日计划额度账本
     * @return T~T+2 原始月计划量汇总
     */
    private int sumSimulationWindowMonthPlanQty(Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap) {
        if (CollectionUtils.isEmpty(quotaMap)) {
            return 0;
        }
        int planQty = 0;
        for (SkuDailyPlanQuotaDTO quota : quotaMap.values()) {
            if (quota == null) {
                continue;
            }
            planQty += Math.max(0, quota.getDayPlanQty());
        }
        return Math.max(0, planQty);
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
            if (!isExistingSameMaterialActiveResult(context, result, sku, currentMachineCode)) {
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
     * @param context 排程上下文
     * @param result 排程结果
     * @param sku 当前 SKU
     * @param currentMachineCode 当前候选机台编码
     * @return true-属于已启用机台
     */
    private boolean isExistingSameMaterialActiveResult(LhScheduleContext context,
                                                       LhScheduleResult result,
                                                       SkuScheduleDTO sku,
                                                       String currentMachineCode) {
        if (result == null
                || sku == null
                || StringUtils.isEmpty(sku.getMaterialCode())
                || !StringUtils.equals(sku.getMaterialCode(), result.getMaterialCode())
                || StringUtils.equals(currentMachineCode, result.getLhMachineCode())
                || StringUtils.isEmpty(result.getLhMachineCode())
                || resolveResultScheduledQty(result) <= 0) {
            return false;
        }
        if (StringUtils.equals(NEW_SPEC_SCHEDULE_TYPE, result.getScheduleType())
                || StringUtils.equals(ScheduleTypeEnum.TYPE_BLOCK.getCode(), result.getScheduleType())) {
            return true;
        }
        if (!StringUtils.equals(ScheduleTypeEnum.CONTINUOUS.getCode(), result.getScheduleType())) {
            return false;
        }
        SkuScheduleDTO sourceSku = resolveResultSourceSku(context, result);
        /*
         * 续作补偿转入 S4.5 后，当前补偿 SKU 与来源续作 SKU 共用同一份日计划账本。
         * 只有同账本续作结果才能作为“当前已选机台窗口有效产能”参与扩机判断，
         * 避免同物料但不同月计划/不同补偿来源的续作结果串入产能。
         */
        return sourceSku != null && sourceSku.getDailyPlanQuotaMap() == sku.getDailyPlanQuotaMap();
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
        switchReadyTime = alignNewSpecSwitchReadyTimeToWindowStart(context, shifts, switchReadyTime);
        switchReadyTime = adjustReleasedMachineSwitchReadyTime(context, sku, candidate, switchReadyTime);
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
        shiftCapacityMap = FirstInspectionQtyUtil.applyFirstInspectionQtyToCapacityMap(
                context, shifts, mouldChangeCompleteTime, shiftCapacityMap, runtimeShiftCapacity,
                sku.resolveTargetScheduleQty(), ScheduleTypeEnum.NEW_SPEC.getCode());
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
                            + "dayN计划: {}, 当前日判断计划: {}, carryShortage: {}, 当日需求: {}, 当日产能: {}, "
                            + "当日欠产: {}, 当前日计划满足: {}, 是否进入后看: {}, 后看日期: {}, "
                            + "决策模式: {}, 是否超过阈值: {}, 窗口8班产能: {}, "
                            + "窗口计划总量: {}, 欠产阈值: {}, T日晚班完成: {}, 窗口有效产能: {}, "
                            + "窗口后剩余欠产: {}, 后一天计划: {}, 后一天3班产能: {}, 累计需求: {}, "
                            + "累计产能: {}, 启用机台: {}, 新增机台: {}, 是否加机台: {}, 未满足: {}, 原因: {}",
                    sku.getMaterialCode(), segment.getMachineCode(), decision.getProductionDate(),
                    decision.getLookAheadEndDate(), decision.getTodayPlanQty(), decision.getCurrentDayPlanQty(),
                    decision.getCarryShortageQty(), decision.getTodayRequiredQty(),
                    decision.getTodayCapacityQty(), decision.getDayShortageQty(),
                    decision.isCurrentDayPlanSatisfied(), decision.isNextDayLookAheadEntered(),
                    decision.getNextProductionDate(), decision.getDecisionMode(), decision.isShortageThresholdExceeded(),
                    decision.getWindowTotalCapacityQty(), decision.getWindowPlanQty(),
                    decision.getShortageAddMachineThreshold(), decision.getScheduleDayFinishQty(),
                    decision.getWindowEffectiveCapacityQty(), decision.getWindowRemainingShortageQty(),
                    decision.getNextDayPlanQty(), decision.getNextDayThreeShiftCapacityQty(),
                    decision.getDemandQty(), decision.getCapacityQty(),
                    decision.getActiveMachineCount(), decision.getAddedMachineCount(),
                    decision.getAddedMachineCount() > 0, decision.getUnmetQty(), decision.getReason());
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
     * 获取新增排产欠产增机台阈值。
     *
     * @param context 排程上下文
     * @return 欠产阈值
     */
    private int resolveNewSpecShortageAddMachineThreshold(LhScheduleContext context) {
        return DailyMachineExpansionPlanner.resolveShortageAddMachineThreshold(context);
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
                context, sku, candidateMachine, mouldChangeStartTime, firstProductionStartTime,
                shifts, ScheduleTypeEnum.NEW_SPEC.getCode());
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
                                                                 Set<String> excludedMachineCodes,
                                                                 NewSpecCandidateCache candidateCache) {
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
            int machineCapacity = resolveCachedMachineAvailableCapacityInWindow(
                    context, sku, candidate, candidateCache);
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
                                                                   Set<String> excludedMachineCodes,
                                                                   NewSpecCandidateCache candidateCache) {
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
            int machineCapacity = resolveCachedMachineAvailableCapacityInWindow(
                    context, sku, candidate, candidateCache);
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

    /**
     * 获取候选机台窗口可用产能。
     * <p>同一SKU的一次选机中，“可单机收完”和“尾量集中”会重复读取同一机台窗口产能，
     * 这里使用当前SKU内短生命周期缓存，成功落地结果后即清空，避免机台运行态变化后复用旧产能。</p>
     *
     * @param context 排程上下文
     * @param sku SKU
     * @param candidate 候选机台
     * @param candidateCache 当前SKU候选机台缓存上下文
     * @return 窗口可用产能
     */
    private int resolveCachedMachineAvailableCapacityInWindow(
            LhScheduleContext context,
            SkuScheduleDTO sku,
            MachineScheduleDTO candidate,
            NewSpecCandidateCache candidateCache) {
        if (candidate == null || StringUtils.isEmpty(candidate.getMachineCode())) {
            return 0;
        }
        if (candidateCache == null) {
            return getTargetScheduleQtyResolver()
                    .calcMachineAvailableCapacityInWindow(context, sku, candidate);
        }
        Integer cachedCapacity = candidateCache.getCandidateWindowCapacity(candidate.getMachineCode());
        if (cachedCapacity != null) {
            return cachedCapacity;
        }
        int machineCapacity = getTargetScheduleQtyResolver()
                .calcMachineAvailableCapacityInWindow(context, sku, candidate);
        candidateCache.putCandidateWindowCapacity(candidate.getMachineCode(), machineCapacity);
        return machineCapacity;
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
        if (sku.isSmallBatchValidation()) {
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
                || StringUtils.equals(ConstructionStageEnum.MASS_TRIAL.getCode(), sku.getConstructionStage());
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
        return StringUtils.equals(ConstructionStageEnum.MASS_TRIAL.getCode(), sku.getConstructionStage());
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

    private String resolveConstructionStageDesc(SkuScheduleDTO sku) {
        if (isTrialConstructionStage(sku)) {
            return "试制";
        }
        if (isMassTrialSku(sku)) {
            return "量试";
        }
        if (isSmallBatchSku(sku)) {
            return "小批量";
        }
        return "正式";
    }

    private String resolveNewSpecDisplayType(SkuScheduleDTO sku) {
        if (isTrialConstructionStage(sku)) {
            return "试制组";
        }
        if (isMassTrialSku(sku)) {
            return "量试组";
        }
        return "正规组";
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
        private RoundScheduleSummary(int scheduledCount, boolean progressed) {
            this.scheduledCount = scheduledCount;
            this.progressed = progressed;
        }

        private int getScheduledCount() {
            return scheduledCount;
        }

        private boolean isProgressed() {
            return progressed;
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
                                                         boolean isEnding,
                                                         MouldResourceAllocationResult mouldResourceAllocationResult) {
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
        result.setTotalFinishQty(sku.getFinishedQty());
        result.setIsEnd(isEnding ? "1" : "0");
        result.setIsDelivery(sku.isDeliveryLocked() ? "1" : "0");
        result.setIsRelease("0");
        result.setDataSource("0");
        result.setIsDelete(0);
        result.setScheduleType(NEW_SPEC_SCHEDULE_TYPE);
        result.setIsChangeMould("1");
        result.setIsTypeBlock("0");
        result.setConstructionStage(sku.getConstructionStage());
        // 产品状态从月计划获取
        result.setProductStatus(sku.getProductStatus());

        // 通过物料编码+产品状态查询SKU与示方书关系获取文字/硫化/制造示方书号
        String embryoNo = null;
        String textNo = null;
        String lhNo = null;
        String lhType = null;
        MdmSkuConstructionRef constructionRef = context.findSkuConstructionRef(
                sku.getMaterialCode(), sku.getProductStatus());
        if (constructionRef != null) {
            embryoNo = constructionRef.getEmbryoNo();
            textNo = constructionRef.getTextNo();
            lhNo = constructionRef.getLhNo();
            lhType = constructionRef.getLhType();
        }
        // 设置1-8班硫化示方书号和硫化示方书类型
        result.setClass1LhNo(lhNo);
        result.setClass1LhType(lhType);
        result.setClass2LhNo(lhNo);
        result.setClass2LhType(lhType);
        result.setClass3LhNo(lhNo);
        result.setClass3LhType(lhType);
        result.setClass4LhNo(lhNo);
        result.setClass4LhType(lhType);
        result.setClass5LhNo(lhNo);
        result.setClass5LhType(lhType);
        result.setClass6LhNo(lhNo);
        result.setClass6LhType(lhType);
        result.setClass7LhNo(lhNo);
        result.setClass7LhType(lhType);
        result.setClass8LhNo(lhNo);
        result.setClass8LhType(lhType);
        // 文字/硫化/制造示方书号回写：关系查不到时置空，以关系值为准
        result.setLhNo(lhNo);
        result.setChangedTrialStatus(lhType);
        result.setEmbryoNo(embryoNo);
        result.setTextNo(textNo);
        result.setMonthPlanVersion(sku.getMonthPlanVersion());
        result.setProductionVersion(sku.getProductionVersion());
        result.setIsTrial(sku.isTrial() ? "1" : "0");
        result.setMachineOrder(machine.getMachineOrder());
        result.setRealScheduleDate(context.getScheduleDate());
        result.setProductionStatus("0");
        result.setMouldCode(resolveActualMouldCodeForNewSpecResult(
                context, sku, machine, mouldQty, mouldResourceAllocationResult));
        result.setHasSpecialMaterial(LhSpecialMaterialUtil.resolveHasSpecialMaterial(context, sku));
        // 保存真实换模开始时间，供下游换模计划表直接复用。
        result.setMouldChangeStartTime(mouldChangeStartTime);

        // 按班次分配计划量；普通换模首检数量按换模完成时间落班，8小时换模耗时不再额外增加。
        int pendingQty = sku.resolveTargetScheduleQty();
        List<MachineCleaningWindowDTO> cleaningWindowList = resolveEffectiveCleaningWindowList(
                context, result.getLhMachineCode(), mouldChangeStartTime, startTime);
        List<MachineMaintenanceWindowDTO> maintenanceWindowList = resolveMachineMaintenanceWindowList(
                context, result.getLhMachineCode());
        distributeToShifts(context, result, shifts, startTime,
                runtimeShiftCapacity, sku.getLhTimeSeconds(), mouldQty, pendingQty, cleaningWindowList,
                maintenanceWindowList, sku, isEnding, mouldChangeEndTime);
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
     * 同SKU多机台排产统一收口。
     * <p>先做SKU收尾同班次尾量归集，再做非收尾辅助机台释放，最后才尝试机台尾量错峰。</p>
     *
     * @param context 排程上下文
     * @param sku 当前SKU
     * @param shifts 排程窗口班次
     * @param quantityPolicy 数量策略
     * @param isEnding 是否SKU收尾
     */
    private void adjustSameSkuMultiMachineAllocation(LhScheduleContext context,
                                                     SkuScheduleDTO sku,
                                                     List<LhShiftConfigVO> shifts,
                                                     ProductionQuantityPolicy quantityPolicy,
                                                     boolean isEnding) {
        List<LhScheduleResult> sameSkuResults = collectSameSkuNewSpecResults(context, sku, null);
        if (CollectionUtils.isEmpty(sameSkuResults) || sameSkuResults.size() < 2) {
            return;
        }
        String beforeSummary = buildSameSkuAllocationSummary(sameSkuResults);
        boolean tailConcentrated = false;
        boolean auxiliaryReleased = false;
        boolean staggered = false;
        if (isEnding) {
            tailConcentrated = concentrateEndingTailWithinSameShift(context, sku, shifts, sameSkuResults);
        }
        if (!isEnding && quantityPolicy != null
                && quantityPolicy.isAllowFillStartedShift()
                && !quantityPolicy.isStrictUpperLimit()) {
            auxiliaryReleased = releaseAuxiliaryMachineForNonEnding(
                    context, sku, shifts, quantityPolicy, sameSkuResults);
        }
        staggered = adjustSameSkuMultiMachineEndingStagger(context, sku, shifts);
        if (tailConcentrated || auxiliaryReleased || staggered) {
            refreshNewSpecEndingFlagByResult(context);
            log.info("新增SKU同SKU多机台收口, materialCode: {}, skuType: {}, isEnding: {}, sameSkuMultiMachine: 1, "
                            + "tailConcentrated: {}, auxiliaryReleased: {}, machineTailStaggered: {}, before: {}, after: {}",
                    sku.getMaterialCode(), resolveNewSpecSkuType(sku), isEnding,
                    oneZero(tailConcentrated), oneZero(auxiliaryReleased), oneZero(staggered),
                    beforeSummary, buildSameSkuAllocationSummary(sameSkuResults));
        }
    }

    private boolean concentrateEndingTailWithinSameShift(LhScheduleContext context,
                                                         SkuScheduleDTO sku,
                                                         List<LhShiftConfigVO> shifts,
                                                         List<LhScheduleResult> sameSkuResults) {
        if (context == null || sku == null || CollectionUtils.isEmpty(shifts)
                || CollectionUtils.isEmpty(sameSkuResults)) {
            return false;
        }
        Map<Integer, List<LhScheduleResult>> endingShiftResultMap = new LinkedHashMap<>(4);
        for (LhScheduleResult result : sameSkuResults) {
            int lastShiftIndex = resolveLastPlannedShiftIndex(result);
            if (lastShiftIndex <= 0) {
                continue;
            }
            Integer endingQty = ShiftFieldUtil.getShiftPlanQty(result, lastShiftIndex);
            if (endingQty == null || endingQty <= 0) {
                continue;
            }
            endingShiftResultMap.computeIfAbsent(lastShiftIndex, key -> new ArrayList<LhScheduleResult>(2))
                    .add(result);
        }
        boolean adjusted = false;
        for (Map.Entry<Integer, List<LhScheduleResult>> entry : endingShiftResultMap.entrySet()) {
            if (entry.getValue().size() < 2) {
                continue;
            }
            if (concentrateEndingTailOnShift(context, sku, shifts, entry.getKey(), entry.getValue())) {
                adjusted = true;
            }
        }
        return adjusted;
    }

    private boolean concentrateEndingTailOnShift(LhScheduleContext context,
                                                 SkuScheduleDTO sku,
                                                 List<LhShiftConfigVO> shifts,
                                                 int endingShiftIndex,
                                                 List<LhScheduleResult> results) {
        LhShiftConfigVO endingShift = findShiftByIndex(shifts, endingShiftIndex);
        if (endingShift == null || CollectionUtils.isEmpty(results) || results.size() < 2) {
            return false;
        }
        int endingDemandQty = resolveEndingDemandQty(context, results.get(0));
        int scheduledBeforeShift = resolveSameSkuScheduledQtyBeforeShift(results, endingShiftIndex);
        int sameShiftTotalCapacity = resolveSameShiftAvailableCapacity(context, results, endingShift);
        int remainingQty = Math.max(0, endingDemandQty - scheduledBeforeShift);
        if (remainingQty <= 0 || remainingQty >= sameShiftTotalCapacity) {
            return false;
        }
        List<LhScheduleResult> sortedResults = new ArrayList<LhScheduleResult>(results);
        sortedResults.sort(buildSameSkuPrimaryComparator(endingShiftIndex));
        Map<LhScheduleResult, Integer> originalShiftQtyMap = new LinkedHashMap<LhScheduleResult, Integer>(sortedResults.size());
        int remainingToAllocate = remainingQty;
        boolean changed = false;
        for (LhScheduleResult result : sortedResults) {
            Integer originalQty = ShiftFieldUtil.getShiftPlanQty(result, endingShiftIndex);
            if (originalQty == null || originalQty <= 0) {
                continue;
            }
            originalShiftQtyMap.put(result, originalQty);
            int shiftCapacity = Math.max(originalQty, Math.max(
                    resolveAvailableShiftQtyForEndingStagger(context, result, endingShift),
                    resolveResultBaseShiftCapacity(result)));
            int newQty = Math.min(Math.max(0, shiftCapacity), Math.max(0, remainingToAllocate));
            setShiftPlanQty(result, endingShiftIndex, newQty,
                    newQty > 0 ? endingShift.getShiftStartDateTime() : null, null);
            remainingToAllocate = Math.max(0, remainingToAllocate - newQty);
            changed = changed || newQty != originalQty;
        }
        if (!changed) {
            return false;
        }
        for (LhScheduleResult result : sortedResults) {
            refreshResultSummary(context, result);
            refreshMachineStateAfterEndingStagger(context, result);
        }
        log.info("新增SKU收尾同班次尾量归集, materialCode: {}, shiftIndex: {}, scheduledBeforeShift: {}, "
                        + "endingDemandQty: {}, sameShiftCapacity: {}, primaryMachine: {}, before: {}, after: {}",
                sku.getMaterialCode(), endingShiftIndex, scheduledBeforeShift, endingDemandQty, sameShiftTotalCapacity,
                sortedResults.isEmpty() ? null : sortedResults.get(0).getLhMachineCode(),
                buildShiftQtySummary(originalShiftQtyMap, endingShiftIndex),
                buildShiftQtySummary(sortedResults, endingShiftIndex));
        return true;
    }

    private boolean releaseAuxiliaryMachineForNonEnding(LhScheduleContext context,
                                                        SkuScheduleDTO sku,
                                                        List<LhShiftConfigVO> shifts,
                                                        ProductionQuantityPolicy quantityPolicy,
                                                        List<LhScheduleResult> sameSkuResults) {
        if (context == null || sku == null || CollectionUtils.isEmpty(shifts)
                || CollectionUtils.isEmpty(sameSkuResults) || sameSkuResults.size() < 2
                || quantityPolicy == null || quantityPolicy.isStrictUpperLimit()) {
            return false;
        }
        Map<LocalDate, List<LhShiftConfigVO>> shiftMapByDate = groupShiftsByWorkDate(shifts);
        if (CollectionUtils.isEmpty(shiftMapByDate)) {
            return false;
        }
        List<LhScheduleResult> sortedResults = new ArrayList<LhScheduleResult>(sameSkuResults);
        sortedResults.sort(buildSameSkuPrimaryComparator(0));
        LhScheduleResult primaryResult = sortedResults.get(0);
        Set<String> releasedMachineCodes = new LinkedHashSet<String>(4);
        Set<String> protectedNightShiftKeySet = new HashSet<String>(4);
        boolean changed = false;
        int carryShortage = 0;
        for (Map.Entry<LocalDate, List<LhShiftConfigVO>> entry : shiftMapByDate.entrySet()) {
            LocalDate productionDate = entry.getKey();
            int requiredQty = Math.max(0, carryShortage + resolveDayPlanQty(sku, productionDate));
            int actualQty = 0;
            for (LhShiftConfigVO shift : entry.getValue()) {
                List<LhScheduleResult> shiftResults = resolveShiftPlannedResults(sortedResults, shift.getShiftIndex());
                if (CollectionUtils.isEmpty(shiftResults)) {
                    continue;
                }
                shiftResults.sort(buildSameSkuPrimaryComparator(shift.getShiftIndex()));
                for (LhScheduleResult result : shiftResults) {
                    Integer shiftQty = ShiftFieldUtil.getShiftPlanQty(result, shift.getShiftIndex());
                    if (shiftQty == null || shiftQty <= 0) {
                        continue;
                    }
                    String machineShiftKey = buildMachineShiftKey(result.getLhMachineCode(), shift.getShiftIndex());
                    if (protectedNightShiftKeySet.contains(machineShiftKey)) {
                        actualQty += shiftQty;
                        log.info("新增SKU辅助机台晚班保留, materialCode: {}, productionDate: {}, shiftIndex: {}, "
                                        + "machine: {}, reason: 中班结束后进入晚班不可换模，不能在同轮释放中清掉已保留晚班",
                                sku.getMaterialCode(), productionDate, shift.getShiftIndex(),
                                result.getLhMachineCode());
                        continue;
                    }
                    boolean primaryMachine = result == primaryResult;
                    boolean necessary = primaryMachine || actualQty < requiredQty;
                    if (!necessary) {
                        if (shouldKeepAuxiliaryShiftForFutureDayDemand(
                                sku, shifts, sameSkuResults, result, productionDate)) {
                            actualQty += shiftQty;
                            log.info("新增SKU辅助机台保留, materialCode: {}, productionDate: {}, shiftIndex: {}, "
                                            + "machine: {}, reason: 后续dayN目标仍需当前辅机承接",
                                    sku.getMaterialCode(), productionDate, shift.getShiftIndex(),
                                    result.getLhMachineCode());
                            continue;
                        }
                        boolean nightShiftProtected = applyNightNoMouldChangeContinuationFill(
                                context, sku, result, shifts, quantityPolicy, shift.getShiftIndex());
                        if (nightShiftProtected) {
                            protectedNightShiftKeySet.add(buildMachineShiftKey(
                                    result.getLhMachineCode(), shift.getShiftIndex() + 1));
                        }
                        setShiftPlanQty(result, shift.getShiftIndex(), 0, null, null);
                        refreshResultSummary(context, result);
                        refreshMachineStateAfterEndingStagger(context, result);
                        releasedMachineCodes.add(result.getLhMachineCode());
                        changed = true;
                        log.info("新增SKU辅助机台释放, materialCode: {}, productionDate: {}, shiftIndex: {}, "
                                        + "primaryMachine: {}, releasedMachine: {}, carryShortage: {}, dayPlanQty: {}, "
                                        + "requiredQty: {}, actualQtyBeforeRelease: {}, reason: 当前日目标量+欠产已满足",
                                sku.getMaterialCode(), productionDate, shift.getShiftIndex(), primaryResult.getLhMachineCode(),
                                result.getLhMachineCode(), carryShortage, resolveDayPlanQty(sku, productionDate),
                                requiredQty, actualQty);
                        if (nightShiftProtected) {
                            log.info("新增SKU辅助机台晚班保留, materialCode: {}, releasedMachine: {}, shiftIndex: {}, "
                                            + "reason: 中班结束后进入晚班不可换模，当前SKU继续无换模生产",
                                    sku.getMaterialCode(), result.getLhMachineCode(), shift.getShiftIndex() + 1);
                        }
                        continue;
                    }
                    actualQty += shiftQty;
                }
            }
            carryShortage = Math.max(0, requiredQty - actualQty);
        }
        if (changed) {
            log.info("新增SKU非收尾辅助机台释放汇总, materialCode: {}, primaryMachine: {}, releasedMachines: {}, after: {}",
                    sku.getMaterialCode(), primaryResult.getLhMachineCode(),
                    StringUtils.join(releasedMachineCodes, ","), buildSameSkuAllocationSummary(sortedResults));
        }
        return changed;
    }

    /**
     * 构造机台班次保护键，避免辅助机台释放遍历中把中班后保留的晚班再次清掉。
     *
     * @param machineCode 机台编码
     * @param shiftIndex 班次序号
     * @return 机台班次保护键
     */
    private String buildMachineShiftKey(String machineCode, Integer shiftIndex) {
        return machineCode + "#" + shiftIndex;
    }

    private boolean shouldKeepAuxiliaryShiftForFutureDayDemand(SkuScheduleDTO sku,
                                                               List<LhShiftConfigVO> shifts,
                                                               List<LhScheduleResult> sameSkuResults,
                                                               LhScheduleResult currentResult,
                                                               LocalDate productionDate) {
        if (sku == null || currentResult == null || productionDate == null
                || CollectionUtils.isEmpty(shifts) || CollectionUtils.isEmpty(sameSkuResults)) {
            return false;
        }
        LocalDate nextPlannedWorkDate = resolveNextPositivePlanDate(sku, productionDate);
        if (nextPlannedWorkDate == null) {
            nextPlannedWorkDate = resolveNextPlannedWorkDate(currentResult, shifts, productionDate);
        }
        if (nextPlannedWorkDate == null) {
            return shouldKeepAuxiliaryShiftForWindowNextDayDemand(
                    sku, shifts, sameSkuResults, currentResult, productionDate);
        }
        Map<LocalDate, List<LhShiftConfigVO>> shiftMapByDate = groupShiftsByWorkDate(shifts);
        List<LhShiftConfigVO> nextDateShifts = shiftMapByDate.get(nextPlannedWorkDate);
        if (CollectionUtils.isEmpty(nextDateShifts)) {
            return shouldKeepAuxiliaryShiftForWindowNextDayDemand(
                    sku, shifts, sameSkuResults, currentResult, productionDate);
        }
        int nextDateRequiredQty = resolveSameSkuRequiredQtyForDate(sku, shifts, sameSkuResults, nextPlannedWorkDate);
        if (nextDateRequiredQty <= 0) {
            return false;
        }
        int scheduledQtyWithoutCurrent = resolveSameSkuScheduledQtyByShiftsExcludingResult(
                sameSkuResults, nextDateShifts, currentResult);
        return scheduledQtyWithoutCurrent < nextDateRequiredQty;
    }

    /**
     * 判断窗口末日辅助机台是否需要因 T+3 日计划继续保留。
     * <p>T+3 不进入 T～T+2 实际扣账账本，但同 SKU 多机台收口不能把 dayN 模拟保留下来的机台班次清掉。</p>
     *
     * @param sku 当前 SKU
     * @param shifts 排程窗口班次
     * @param sameSkuResults 同 SKU 结果
     * @param currentResult 当前辅助机台结果
     * @param productionDate 当前生产日
     * @return true-保留当前辅助机台；false-允许按原逻辑释放
     */
    private boolean shouldKeepAuxiliaryShiftForWindowNextDayDemand(SkuScheduleDTO sku,
                                                                   List<LhShiftConfigVO> shifts,
                                                                   List<LhScheduleResult> sameSkuResults,
                                                                   LhScheduleResult currentResult,
                                                                   LocalDate productionDate) {
        if (sku == null || currentResult == null || productionDate == null
                || CollectionUtils.isEmpty(shifts) || CollectionUtils.isEmpty(sameSkuResults)
                || Math.max(0, sku.getNextDayPlanQtyAfterWindow()) <= 0
                || Math.max(0, sku.getShiftCapacity()) <= 0) {
            return false;
        }
        Map<LocalDate, List<LhShiftConfigVO>> shiftMapByDate = groupShiftsByWorkDate(shifts);
        if (CollectionUtils.isEmpty(shiftMapByDate) || !productionDate.equals(resolveLastWindowWorkDate(shiftMapByDate))) {
            return false;
        }
        int availableShiftCount = resolveSchedulableShiftCount(shiftMapByDate.get(productionDate));
        if (availableShiftCount <= 0) {
            return false;
        }
        int singleMachineNextDayCapacity = availableShiftCount * Math.max(0, sku.getShiftCapacity());
        int requiredMachineCount = divideCeiling(
                Math.max(0, sku.getNextDayPlanQtyAfterWindow()), singleMachineNextDayCapacity);
        int machineCountWithoutCurrent = countDistinctSameSkuMachinesExcludingResult(sameSkuResults, currentResult);
        boolean keep = machineCountWithoutCurrent < requiredMachineCount;
        if (keep) {
            log.info("新增SKU辅助机台保留, materialCode: {}, productionDate: {}, machine: {}, "
                            + "reason: T+3日计划仍需当前辅机承接, nextDayPlanQty: {}, requiredMachineCount: {}, "
                            + "machineCountWithoutCurrent: {}",
                    sku.getMaterialCode(), productionDate, currentResult.getLhMachineCode(),
                    Math.max(0, sku.getNextDayPlanQtyAfterWindow()), requiredMachineCount, machineCountWithoutCurrent);
        }
        return keep;
    }

    /**
     * 解析排程窗口最后一个业务日。
     *
     * @param shiftMapByDate 按业务日分组的班次
     * @return 最后一个业务日
     */
    private LocalDate resolveLastWindowWorkDate(Map<LocalDate, List<LhShiftConfigVO>> shiftMapByDate) {
        LocalDate lastDate = null;
        for (LocalDate productionDate : shiftMapByDate.keySet()) {
            if (productionDate != null && (lastDate == null || productionDate.isAfter(lastDate))) {
                lastDate = productionDate;
            }
        }
        return lastDate;
    }

    /**
     * 统计有效排产班次数。
     *
     * @param shifts 业务日班次
     * @return 有效班次数
     */
    private int resolveSchedulableShiftCount(List<LhShiftConfigVO> shifts) {
        if (CollectionUtils.isEmpty(shifts)) {
            return 0;
        }
        int count = 0;
        for (LhShiftConfigVO shift : shifts) {
            if (shift != null && shift.getShiftIndex() != null) {
                count++;
            }
        }
        return count;
    }

    /**
     * 统计排除当前结果后的同 SKU 机台数。
     *
     * @param sameSkuResults 同 SKU 结果
     * @param excludedResult 当前结果
     * @return 去重机台数
     */
    private int countDistinctSameSkuMachinesExcludingResult(List<LhScheduleResult> sameSkuResults,
                                                            LhScheduleResult excludedResult) {
        if (CollectionUtils.isEmpty(sameSkuResults)) {
            return 0;
        }
        Set<String> machineCodeSet = new HashSet<String>(sameSkuResults.size());
        for (LhScheduleResult result : sameSkuResults) {
            if (result == null || result == excludedResult || StringUtils.isEmpty(result.getLhMachineCode())) {
                continue;
            }
            machineCodeSet.add(result.getLhMachineCode());
        }
        return machineCodeSet.size();
    }

    /**
     * 解析当前生产日之后仍有日计划的最近业务日。
     * <p>新增非收尾增机台可能在 T+1 提前借用 T+2 计划；辅助机台释放时必须按后续 dayN 需求判断，
     * 不能只看辅助机台自身是否已经落到后续日期，否则会把提前承接未来计划的第二台清零。</p>
     *
     * @param sku 当前 SKU
     * @param productionDate 当前生产日
     * @return 后续有计划的最近业务日
     */
    private LocalDate resolveNextPositivePlanDate(SkuScheduleDTO sku, LocalDate productionDate) {
        if (sku == null || productionDate == null || CollectionUtils.isEmpty(sku.getDailyPlanQuotaMap())) {
            return null;
        }
        for (Map.Entry<LocalDate, SkuDailyPlanQuotaDTO> entry : sku.getDailyPlanQuotaMap().entrySet()) {
            LocalDate quotaDate = entry.getKey();
            SkuDailyPlanQuotaDTO quota = entry.getValue();
            if (quotaDate == null || !quotaDate.isAfter(productionDate) || quota == null) {
                continue;
            }
            if (Math.max(0, quota.getDayPlanQty()) > 0 || Math.max(0, quota.getRemainingQty()) > 0) {
                return quotaDate;
            }
        }
        return null;
    }

    private LocalDate resolveNextPlannedWorkDate(LhScheduleResult result,
                                                 List<LhShiftConfigVO> shifts,
                                                 LocalDate currentWorkDate) {
        if (result == null || currentWorkDate == null || CollectionUtils.isEmpty(shifts)) {
            return null;
        }
        LocalDate nextWorkDate = null;
        for (LhShiftConfigVO shift : shifts) {
            LocalDate shiftWorkDate = resolveShiftWorkDate(shift);
            if (shiftWorkDate == null || !shiftWorkDate.isAfter(currentWorkDate)
                    || shift.getShiftIndex() == null) {
                continue;
            }
            Integer shiftQty = ShiftFieldUtil.getShiftPlanQty(result, shift.getShiftIndex());
            if (shiftQty == null || shiftQty <= 0) {
                continue;
            }
            if (nextWorkDate == null || shiftWorkDate.isBefore(nextWorkDate)) {
                nextWorkDate = shiftWorkDate;
            }
        }
        return nextWorkDate;
    }

    /**
     * 同SKU多机台机台收尾时，针对早班/中班同班次收尾做尾量错开。
     * <p>这里处理的是机台尾量，不改变SKU收尾判断；晚班不调整，避免破坏“晚班不能换模”下的有效产能。</p>
     *
     * @param context 排程上下文
     * @param sku 当前SKU
     * @param shifts 排程窗口班次
     * @return true-已调整；false-未调整
     */
    private boolean adjustSameSkuMultiMachineEndingStagger(LhScheduleContext context,
                                                           SkuScheduleDTO sku,
                                                           List<LhShiftConfigVO> shifts) {
        if (context == null || sku == null || CollectionUtils.isEmpty(shifts)
                || CollectionUtils.isEmpty(context.getScheduleResultList())) {
            return false;
        }
        List<LhScheduleResult> sameSkuEndingResults = collectSameSkuNewSpecResults(context, sku, null);
        if (sameSkuEndingResults.size() < 2) {
            return false;
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
        boolean adjusted = false;
        for (Map.Entry<Integer, List<LhScheduleResult>> entry : endingShiftResultMap.entrySet()) {
            if (entry.getValue().size() >= 2
                    && tryStaggerSameShiftEnding(context, sku, shifts, entry.getKey(), entry.getValue())) {
                adjusted = true;
            }
        }
        return adjusted;
    }

    private boolean tryStaggerSameShiftEnding(LhScheduleContext context,
                                              SkuScheduleDTO sku,
                                              List<LhShiftConfigVO> shifts,
                                              int endingShiftIndex,
                                              List<LhScheduleResult> results) {
        LhShiftConfigVO endingShift = findShiftByIndex(shifts, endingShiftIndex);
        LhShiftConfigVO nextShift = findShiftByIndex(shifts, endingShiftIndex + 1);
        if (endingShift == null || nextShift == null) {
            return false;
        }
        boolean nightShift = StringUtils.equals(ShiftEnum.NIGHT_SHIFT.getCode(), endingShift.getShiftType());
        log.info("同SKU多机台机台收尾错峰判断, materialCode: {}, 收尾班次: {}, 是否晚班: {}, 是否同SKU多机台收尾: 1",
                sku.getMaterialCode(), endingShiftIndex, nightShift);
        if (nightShift) {
            log.info("同SKU多机台机台收尾错峰跳过, materialCode: {}, 收尾班次: {}, 原因: 晚班不可换模不强制错峰",
                    sku.getMaterialCode(), endingShiftIndex);
            return false;
        }
        if (!isSameWorkDate(endingShift.getWorkDate(), nextShift.getWorkDate())) {
            return false;
        }
        List<LhScheduleResult> sortedResults = new ArrayList<LhScheduleResult>(results);
        sortedResults.sort(buildSameSkuPrimaryComparator(endingShiftIndex));
        LhScheduleResult donor = resolveTailDonorResult(sortedResults, endingShiftIndex);
        LhScheduleResult receiver = resolveTailReceiverResult(context, sku, sortedResults, donor, nextShift);
        if (donor == null || receiver == null) {
            return false;
        }
        Integer donorQty = ShiftFieldUtil.getShiftPlanQty(donor, endingShiftIndex);
        if (donorQty == null || donorQty <= 0) {
            return false;
        }
        int donorShiftCapacity = resolveAvailableShiftQtyForEndingStagger(context, donor, endingShift);
        if (donorShiftCapacity > 0 && donorQty >= donorShiftCapacity) {
            log.info("同SKU多机台机台收尾错峰跳过, materialCode: {}, 释放机台: {}, 收尾班次: {}, "
                            + "原因: 当前班次为满班产量，不属于可释放尾量",
                    sku.getMaterialCode(), donor.getLhMachineCode(), endingShiftIndex);
            return false;
        }
        int nextShiftCapacity = resolveAvailableShiftQtyForEndingStagger(context, receiver, nextShift);
        if (nextShiftCapacity <= 0 || donorQty > nextShiftCapacity) {
            log.info("同SKU多机台机台收尾错峰跳过, materialCode: {}, 承接机台: {}, 需转移: {}, 可用: {}",
                    sku.getMaterialCode(), receiver.getLhMachineCode(), donorQty, nextShiftCapacity);
            return false;
        }
        setShiftPlanQty(donor, endingShiftIndex, 0, null, null);
        Integer receiverExistingQty = ShiftFieldUtil.getShiftPlanQty(receiver, nextShift.getShiftIndex());
        int receiverQty = Math.max(0, receiverExistingQty == null ? 0 : receiverExistingQty) + donorQty;
        setShiftPlanQty(receiver, nextShift.getShiftIndex(), receiverQty, nextShift.getShiftStartDateTime(), null);
        refreshResultSummary(context, donor);
        refreshResultSummary(context, receiver);
        refreshMachineStateAfterEndingStagger(context, donor);
        refreshMachineStateAfterEndingStagger(context, receiver);
        log.info("同SKU多机台机台收尾尾量错开, materialCode: {}, 释放机台: {}, 承接机台: {}, "
                        + "原收尾班次: {}, 承接班次: {}, 转移数量: {}",
                sku.getMaterialCode(), donor.getLhMachineCode(), receiver.getLhMachineCode(),
                endingShiftIndex, nextShift.getShiftIndex(), donorQty);
        return true;
    }

    private List<LhScheduleResult> collectSameSkuNewSpecResults(LhScheduleContext context,
                                                                SkuScheduleDTO sku,
                                                                LhScheduleResult currentResult) {
        List<LhScheduleResult> sameSkuResults = new ArrayList<LhScheduleResult>(4);
        if (context == null || sku == null || StringUtils.isEmpty(sku.getMaterialCode())) {
            return sameSkuResults;
        }
        if (!CollectionUtils.isEmpty(context.getScheduleResultList())) {
            for (LhScheduleResult result : context.getScheduleResultList()) {
                if (result == null
                        || !NEW_SPEC_SCHEDULE_TYPE.equals(result.getScheduleType())
                        || "1".equals(result.getIsTypeBlock())
                        || !StringUtils.equals(sku.getMaterialCode(), result.getMaterialCode())
                        || ShiftFieldUtil.resolveScheduledQty(result) <= 0) {
                    continue;
                }
                sameSkuResults.add(result);
            }
        }
        if (currentResult != null
                && NEW_SPEC_SCHEDULE_TYPE.equals(currentResult.getScheduleType())
                && !"1".equals(currentResult.getIsTypeBlock())
                && StringUtils.equals(sku.getMaterialCode(), currentResult.getMaterialCode())
                && !sameSkuResults.contains(currentResult)) {
            sameSkuResults.add(currentResult);
        }
        return sameSkuResults;
    }

    private Comparator<LhScheduleResult> buildSameSkuPrimaryComparator(int shiftIndex) {
        return Comparator
                .comparingInt((LhScheduleResult result) -> {
                    int firstShiftIndex = resolveFirstPlannedShiftIndex(result);
                    return firstShiftIndex > 0 ? firstShiftIndex : Integer.MAX_VALUE;
                })
                .thenComparing((LhScheduleResult left, LhScheduleResult right) ->
                        Integer.compare(resolveResultScheduledQty(right), resolveResultScheduledQty(left)))
                .thenComparing((LhScheduleResult left, LhScheduleResult right) ->
                        Integer.compare(resolveScheduledQtyBeforeShift(right, shiftIndex),
                                resolveScheduledQtyBeforeShift(left, shiftIndex)))
                .thenComparing(result -> StringUtils.defaultString(result.getLhMachineCode()));
    }

    private int resolveScheduledQtyBeforeShift(LhScheduleResult result, int shiftIndex) {
        if (result == null || shiftIndex <= 1) {
            return 0;
        }
        int scheduledQty = 0;
        for (int currentShiftIndex = 1; currentShiftIndex < shiftIndex; currentShiftIndex++) {
            Integer shiftQty = ShiftFieldUtil.getShiftPlanQty(result, currentShiftIndex);
            if (shiftQty != null && shiftQty > 0) {
                scheduledQty += shiftQty;
            }
        }
        return scheduledQty;
    }

    private int resolveSameSkuScheduledQtyBeforeShift(List<LhScheduleResult> results, int shiftIndex) {
        if (CollectionUtils.isEmpty(results) || shiftIndex <= 1) {
            return 0;
        }
        int totalQty = 0;
        for (LhScheduleResult result : results) {
            totalQty += resolveScheduledQtyBeforeShift(result, shiftIndex);
        }
        return totalQty;
    }

    private int resolveSameShiftAvailableCapacity(LhScheduleContext context,
                                                  List<LhScheduleResult> results,
                                                  LhShiftConfigVO shift) {
        if (CollectionUtils.isEmpty(results) || shift == null) {
            return 0;
        }
        int totalCapacity = 0;
        for (LhScheduleResult result : results) {
            Integer currentQty = ShiftFieldUtil.getShiftPlanQty(result, shift.getShiftIndex());
            if (currentQty == null || currentQty <= 0) {
                continue;
            }
            int availableQty = resolveAvailableShiftQtyForEndingStagger(context, result, shift);
            int baseShiftCapacity = resolveResultBaseShiftCapacity(result);
            totalCapacity += Math.max(currentQty, Math.max(availableQty, baseShiftCapacity));
        }
        return totalCapacity;
    }

    private int resolveDayPlanQty(SkuScheduleDTO sku, LocalDate productionDate) {
        if (sku == null || productionDate == null || CollectionUtils.isEmpty(sku.getDailyPlanQuotaMap())) {
            return 0;
        }
        SkuDailyPlanQuotaDTO quota = sku.getDailyPlanQuotaMap().get(productionDate);
        return quota == null ? 0 : Math.max(0, quota.getDayPlanQty());
    }

    private Map<LocalDate, List<LhShiftConfigVO>> groupShiftsByWorkDate(List<LhShiftConfigVO> shifts) {
        Map<LocalDate, List<LhShiftConfigVO>> shiftMap = new LinkedHashMap<LocalDate, List<LhShiftConfigVO>>(4);
        if (CollectionUtils.isEmpty(shifts)) {
            return shiftMap;
        }
        for (LhShiftConfigVO shift : shifts) {
            LocalDate workDate = resolveShiftWorkDate(shift);
            if (workDate == null) {
                continue;
            }
            shiftMap.computeIfAbsent(workDate, key -> new ArrayList<LhShiftConfigVO>(4)).add(shift);
        }
        return shiftMap;
    }

    private LocalDate resolveShiftWorkDate(LhShiftConfigVO shift) {
        if (shift == null || shift.getWorkDate() == null) {
            return null;
        }
        return shift.getWorkDate().toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
    }

    private List<LhScheduleResult> resolveShiftPlannedResults(List<LhScheduleResult> results, Integer shiftIndex) {
        List<LhScheduleResult> shiftResults = new ArrayList<LhScheduleResult>(4);
        if (CollectionUtils.isEmpty(results) || shiftIndex == null) {
            return shiftResults;
        }
        for (LhScheduleResult result : results) {
            Integer shiftQty = ShiftFieldUtil.getShiftPlanQty(result, shiftIndex);
            if (shiftQty != null && shiftQty > 0) {
                shiftResults.add(result);
            }
        }
        return shiftResults;
    }

    private String buildSameSkuAllocationSummary(List<LhScheduleResult> results) {
        if (CollectionUtils.isEmpty(results)) {
            return "-";
        }
        List<String> machineSummaryList = new ArrayList<String>(results.size());
        List<LhScheduleResult> sortedResults = new ArrayList<LhScheduleResult>(results);
        sortedResults.sort(Comparator.comparing(result -> StringUtils.defaultString(result.getLhMachineCode())));
        for (LhScheduleResult result : sortedResults) {
            machineSummaryList.add(buildMachineShiftSummary(result));
        }
        return StringUtils.join(machineSummaryList, "; ");
    }

    private String buildMachineShiftSummary(LhScheduleResult result) {
        if (result == null) {
            return "-";
        }
        StringBuilder builder = new StringBuilder(128);
        builder.append(StringUtils.defaultString(result.getLhMachineCode())).append("[");
        for (int shiftIndex = 1; shiftIndex <= LhScheduleConstant.MAX_SHIFT_SLOT_COUNT; shiftIndex++) {
            if (shiftIndex > 1) {
                builder.append(",");
            }
            Integer shiftQty = ShiftFieldUtil.getShiftPlanQty(result, shiftIndex);
            builder.append("C").append(shiftIndex).append("=")
                    .append(shiftQty == null ? 0 : Math.max(0, shiftQty));
        }
        builder.append("]");
        return builder.toString();
    }

    private String buildShiftQtySummary(Map<LhScheduleResult, Integer> shiftQtyMap, int shiftIndex) {
        if (CollectionUtils.isEmpty(shiftQtyMap)) {
            return "-";
        }
        List<String> summaryList = new ArrayList<String>(shiftQtyMap.size());
        for (Map.Entry<LhScheduleResult, Integer> entry : shiftQtyMap.entrySet()) {
            summaryList.add(StringUtils.defaultString(entry.getKey().getLhMachineCode())
                    + "[C" + shiftIndex + "=" + Math.max(0, entry.getValue()) + "]");
        }
        return StringUtils.join(summaryList, "; ");
    }

    private String buildShiftQtySummary(List<LhScheduleResult> results, int shiftIndex) {
        if (CollectionUtils.isEmpty(results)) {
            return "-";
        }
        Map<LhScheduleResult, Integer> shiftQtyMap = new LinkedHashMap<LhScheduleResult, Integer>(results.size());
        for (LhScheduleResult result : results) {
            Integer shiftQty = ShiftFieldUtil.getShiftPlanQty(result, shiftIndex);
            shiftQtyMap.put(result, shiftQty == null ? 0 : shiftQty);
        }
        return buildShiftQtySummary(shiftQtyMap, shiftIndex);
    }

    private String oneZero(boolean value) {
        return value ? "1" : "0";
    }

    private LhScheduleResult resolveTailDonorResult(List<LhScheduleResult> results, int endingShiftIndex) {
        if (CollectionUtils.isEmpty(results)) {
            return null;
        }
        List<LhScheduleResult> sortedResults = new ArrayList<LhScheduleResult>(results);
        sortedResults.sort(buildSameSkuPrimaryComparator(endingShiftIndex).reversed());
        for (LhScheduleResult result : sortedResults) {
            Integer qty = ShiftFieldUtil.getShiftPlanQty(result, endingShiftIndex);
            if (qty != null && qty > 0) {
                return result;
            }
        }
        return null;
    }

    private LhScheduleResult resolveTailReceiverResult(LhScheduleContext context,
                                                       SkuScheduleDTO sku,
                                                       List<LhScheduleResult> results,
                                                       LhScheduleResult donor,
                                                       LhShiftConfigVO nextShift) {
        if (CollectionUtils.isEmpty(results) || nextShift == null) {
            return null;
        }
        List<LhScheduleResult> sortedResults = new ArrayList<LhScheduleResult>(results);
        sortedResults.sort(buildSameSkuPrimaryComparator(nextShift.getShiftIndex()));
        for (LhScheduleResult result : sortedResults) {
            if (result == null || result == donor) {
                continue;
            }
            if (isMachineShiftOccupiedByOtherSku(context, sku, result, nextShift)) {
                continue;
            }
            Integer nextShiftQty = ShiftFieldUtil.getShiftPlanQty(result, nextShift.getShiftIndex());
            if (nextShiftQty != null && nextShiftQty > 0) {
                return result;
            }
        }
        for (LhScheduleResult result : sortedResults) {
            if (result == null || result == donor) {
                continue;
            }
            if (!isMachineShiftOccupiedByOtherSku(context, sku, result, nextShift)) {
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
                dryIceDurationHours,
                targetShift,
                ShiftCapacityResolverUtil.resolveOddShiftCapacityPlusShiftType(context),
                ScheduleTypeEnum.NEW_SPEC.getCode());
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

    /**
     * 晚班不可换模时，当前SKU在本机台无换模续作补下一晚班。
     * <p>非收尾SKU按可用晚班班产补满；收尾SKU只允许在剩余收尾目标量范围内补量，不能超排。</p>
     *
     * @param context 排程上下文
     * @param sku 当前SKU
     * @param result 当前机台结果
     * @param shifts 班次列表
     * @param quantityPolicy 数量控制策略
     * @return true-已保留或补充晚班；false-未命中规则
     */
    private boolean applyNightNoMouldChangeContinuationFill(LhScheduleContext context,
                                                            SkuScheduleDTO sku,
                                                            LhScheduleResult result,
                                                            List<LhShiftConfigVO> shifts,
                                                            ProductionQuantityPolicy quantityPolicy) {
        return applyNightNoMouldChangeContinuationFill(context, sku, result, shifts, quantityPolicy, null);
    }

    private boolean applyNightNoMouldChangeContinuationFill(LhScheduleContext context,
                                                            SkuScheduleDTO sku,
                                                            LhScheduleResult result,
                                                            List<LhShiftConfigVO> shifts,
                                                            ProductionQuantityPolicy quantityPolicy,
                                                            Integer releaseShiftIndex) {
        if (context == null || sku == null || result == null || CollectionUtils.isEmpty(shifts)
                || quantityPolicy == null) {
            return false;
        }
        boolean endingPolicy = quantityPolicy.isEnding();
        if (!endingPolicy && (!quantityPolicy.isAllowFillStartedShift() || quantityPolicy.isStrictUpperLimit())) {
            return false;
        }
        if ("1".equals(result.getIsEnd()) && !endingPolicy) {
            return false;
        }
        int lastShiftIndex = releaseShiftIndex == null ? resolveLastPlannedShiftIndex(result) : releaseShiftIndex;
        if (lastShiftIndex <= 0) {
            return false;
        }
        LhShiftConfigVO currentShift = findShiftByIndex(shifts, lastShiftIndex);
        LhShiftConfigVO nextShift = findShiftByIndex(shifts, lastShiftIndex + 1);
        if (!isAfternoonToNoMouldChangeNightShift(currentShift, nextShift)
                || nextShift.getShiftStartDateTime() == null
                || !LhScheduleTimeUtil.isNoMouldChangeTime(context, nextShift.getShiftStartDateTime())) {
            return false;
        }
        if (isMachineShiftOccupiedByOtherSku(context, sku, result, nextShift)) {
            log.info("晚班不可换模续作补满跳过, materialCode: {}, 机台: {}, 晚班班次: {}, 原因: 下一晚班已被其他SKU占用",
                    sku.getMaterialCode(), result.getLhMachineCode(), nextShift.getShiftIndex());
            return false;
        }
        boolean pendingResultBeforePersist = releaseShiftIndex == null
                && isNewSpecResultPendingPersist(context, result);
        if (releaseShiftIndex == null && !endingPolicy && !pendingResultBeforePersist
                && !isNightContinuationFillNecessary(context, sku, result, shifts, nextShift)) {
            log.info("晚班不可换模续作补满跳过, materialCode: {}, 机台: {}, 晚班班次: {}, 原因: 当前机台为辅助机台且主承接机台已可覆盖当日目标",
                    sku.getMaterialCode(), result.getLhMachineCode(), nextShift.getShiftIndex());
            return false;
        }
        int realSurplusRemainingQty = resolveRealSurplusRemainingQty(context, sku, result);
        if (realSurplusRemainingQty <= 0) {
            return false;
        }
        Integer existingQty = ShiftFieldUtil.getShiftPlanQty(result, nextShift.getShiftIndex());
        int currentQty = existingQty == null ? 0 : Math.max(0, existingQty);
        int availableQty = Math.max(0, resolveAvailableShiftQtyForEndingStagger(context, result, nextShift) - currentQty);
        int fillQty = Math.min(availableQty, realSurplusRemainingQty);
        if (fillQty <= 0) {
            return currentQty > 0;
        }
        setShiftPlanQty(result, nextShift.getShiftIndex(), currentQty + fillQty,
                nextShift.getShiftStartDateTime(), null);
        refreshResultSummary(context, result);
        log.info("晚班不可换模续作补满命中, materialCode: {}, 机台: {}, 当前收尾班次: {}, 晚班班次: {}, "
                        + "补满数量: {}, 真实余量剩余: {}, 原因: 晚班不可换模且当前SKU可无换模续作",
                sku.getMaterialCode(), result.getLhMachineCode(), lastShiftIndex, nextShift.getShiftIndex(),
                fillQty, realSurplusRemainingQty);
        return true;
    }

    /**
     * 判断新增结果是否处于落地前状态。
     * <p>尾机台初始排到中班结束时，结果尚未加入上下文；此时下个晚班不可换模，不能套用已落地辅助机的主机覆盖跳过保护。</p>
     *
     * @param context 排程上下文
     * @param result 当前新增结果
     * @return true-新增结果尚未落地；false-结果已落地或无效
     */
    private boolean isNewSpecResultPendingPersist(LhScheduleContext context, LhScheduleResult result) {
        if (context == null || result == null
                || !NEW_SPEC_SCHEDULE_TYPE.equals(result.getScheduleType())
                || CollectionUtils.isEmpty(context.getScheduleResultList())) {
            return false;
        }
        for (LhScheduleResult scheduleResult : context.getScheduleResultList()) {
            if (scheduleResult == result) {
                return false;
            }
        }
        return true;
    }

    /**
     * 判断当前结果是否从中班收尾进入不可换模晚班。
     * <p>中班结束后如果直接下机，后续SKU在晚班无法换模开产；当前SKU已在机，可继续无换模生产晚班。</p>
     *
     * @param currentShift 当前最后有量班次
     * @param nextShift 下一班次
     * @return true-中班后紧接不可换模晚班
     */
    private boolean isAfternoonToNoMouldChangeNightShift(LhShiftConfigVO currentShift,
                                                         LhShiftConfigVO nextShift) {
        return currentShift != null
                && nextShift != null
                && StringUtils.equals(ShiftEnum.AFTERNOON_SHIFT.getCode(), currentShift.getShiftType())
                && nextShift.isNightShift()
                && !nextShift.isAllowMouldChange();
    }

    private boolean isNightContinuationFillNecessary(LhScheduleContext context,
                                                     SkuScheduleDTO sku,
                                                     LhScheduleResult currentResult,
                                                     List<LhShiftConfigVO> shifts,
                                                     LhShiftConfigVO nextShift) {
        List<LhScheduleResult> sameSkuResults = collectSameSkuNewSpecResults(context, sku, currentResult);
        if (sameSkuResults.size() < 2) {
            return true;
        }
        List<LhScheduleResult> sortedResults = new ArrayList<LhScheduleResult>(sameSkuResults);
        sortedResults.sort(buildSameSkuPrimaryComparator(nextShift.getShiftIndex()));
        LhScheduleResult primaryResult = sortedResults.get(0);
        if (currentResult == primaryResult) {
            return true;
        }
        LocalDate productionDate = resolveShiftWorkDate(nextShift);
        if (productionDate == null) {
            return true;
        }
        int dayRequiredQty = resolveSameSkuRequiredQtyForDate(sku, shifts, sameSkuResults, productionDate);
        List<LhShiftConfigVO> sameDateShifts = groupShiftsByWorkDate(shifts).get(productionDate);
        int dayScheduledQty = resolveSameSkuScheduledQtyByShifts(sameSkuResults, sameDateShifts);
        return dayScheduledQty < dayRequiredQty;
    }

    /**
     * 计算当前SKU真实余量扣除已排后的剩余量。
     *
     * @param context 排程上下文
     * @param sku 当前SKU
     * @param currentResult 当前结果
     * @return 剩余真实余量
     */
    private int resolveRealSurplusRemainingQty(LhScheduleContext context,
                                               SkuScheduleDTO sku,
                                               LhScheduleResult currentResult) {
        if (sku == null || sku.getSurplusQty() <= 0) {
            return 0;
        }
        int scheduledQty = ShiftFieldUtil.resolveScheduledQty(currentResult);
        if (context != null && !CollectionUtils.isEmpty(context.getScheduleResultList())) {
            for (LhScheduleResult result : context.getScheduleResultList()) {
                if (result == null || result == currentResult
                        || !StringUtils.equals(sku.getMaterialCode(), result.getMaterialCode())) {
                    continue;
                }
                scheduledQty += ShiftFieldUtil.resolveScheduledQty(result);
            }
        }
        return Math.max(0, sku.getSurplusQty() - scheduledQty);
    }

    private int resolveSameSkuRequiredQtyForDate(SkuScheduleDTO sku,
                                                 List<LhShiftConfigVO> shifts,
                                                 List<LhScheduleResult> sameSkuResults,
                                                 LocalDate targetDate) {
        Map<LocalDate, List<LhShiftConfigVO>> shiftMapByDate = groupShiftsByWorkDate(shifts);
        if (CollectionUtils.isEmpty(shiftMapByDate) || targetDate == null) {
            return 0;
        }
        int carryShortage = 0;
        int requiredQty = 0;
        for (Map.Entry<LocalDate, List<LhShiftConfigVO>> entry : shiftMapByDate.entrySet()) {
            LocalDate productionDate = entry.getKey();
            requiredQty = Math.max(0, carryShortage + resolveDayPlanQty(sku, productionDate));
            int actualQty = resolveSameSkuScheduledQtyByShifts(sameSkuResults, entry.getValue());
            if (productionDate.equals(targetDate)) {
                return requiredQty;
            }
            carryShortage = Math.max(0, requiredQty - actualQty);
        }
        return requiredQty;
    }

    private int resolveSameSkuScheduledQtyByShifts(List<LhScheduleResult> sameSkuResults,
                                                   List<LhShiftConfigVO> shifts) {
        if (CollectionUtils.isEmpty(sameSkuResults) || CollectionUtils.isEmpty(shifts)) {
            return 0;
        }
        int totalQty = 0;
        for (LhScheduleResult result : sameSkuResults) {
            for (LhShiftConfigVO shift : shifts) {
                if (shift == null || shift.getShiftIndex() == null) {
                    continue;
                }
                Integer shiftQty = ShiftFieldUtil.getShiftPlanQty(result, shift.getShiftIndex());
                if (shiftQty != null && shiftQty > 0) {
                    totalQty += shiftQty;
                }
            }
        }
        return totalQty;
    }

    private int resolveSameSkuScheduledQtyByShiftsExcludingResult(List<LhScheduleResult> sameSkuResults,
                                                                  List<LhShiftConfigVO> shifts,
                                                                  LhScheduleResult excludedResult) {
        if (CollectionUtils.isEmpty(sameSkuResults) || CollectionUtils.isEmpty(shifts)) {
            return 0;
        }
        int totalQty = 0;
        for (LhScheduleResult result : sameSkuResults) {
            if (result == null || result == excludedResult) {
                continue;
            }
            for (LhShiftConfigVO shift : shifts) {
                if (shift == null || shift.getShiftIndex() == null) {
                    continue;
                }
                Integer shiftQty = ShiftFieldUtil.getShiftPlanQty(result, shift.getShiftIndex());
                if (shiftQty != null && shiftQty > 0) {
                    totalQty += shiftQty;
                }
            }
        }
        return totalQty;
    }

    /**
     * 判断承接机台目标班次是否已被其他SKU占用。
     *
     * @param context 排程上下文
     * @param sku 当前SKU
     * @param receiver 承接结果
     * @param targetShift 目标班次
     * @return true-其他SKU已占用
     */
    private boolean isMachineShiftOccupiedByOtherSku(LhScheduleContext context,
                                                     SkuScheduleDTO sku,
                                                     LhScheduleResult receiver,
                                                     LhShiftConfigVO targetShift) {
        if (context == null || sku == null || receiver == null || targetShift == null
                || StringUtils.isEmpty(receiver.getLhMachineCode())
                || targetShift.getShiftIndex() == null) {
            return false;
        }
        List<LhScheduleResult> machineResults = CollectionUtils.isEmpty(context.getMachineAssignmentMap())
                ? null : context.getMachineAssignmentMap().get(receiver.getLhMachineCode());
        if (isMachineShiftOccupiedByOtherSku(machineResults, sku, receiver, targetShift)) {
            return true;
        }
        return isMachineShiftOccupiedByOtherSku(context.getScheduleResultList(), sku, receiver, targetShift);
    }

    /**
     * 判断结果列表中是否存在同机台同班次其他SKU计划。
     *
     * @param results 结果列表
     * @param sku 当前SKU
     * @param receiver 承接结果
     * @param targetShift 目标班次
     * @return true-其他SKU已占用
     */
    private boolean isMachineShiftOccupiedByOtherSku(List<LhScheduleResult> results,
                                                     SkuScheduleDTO sku,
                                                     LhScheduleResult receiver,
                                                     LhShiftConfigVO targetShift) {
        if (CollectionUtils.isEmpty(results)) {
            return false;
        }
        for (LhScheduleResult result : results) {
            if (result == null || result == receiver
                    || !StringUtils.equals(receiver.getLhMachineCode(), result.getLhMachineCode())) {
                continue;
            }
            Integer planQty = ShiftFieldUtil.getShiftPlanQty(result, targetShift.getShiftIndex());
            if (planQty == null || planQty <= 0) {
                continue;
            }
            if (!StringUtils.equals(sku.getMaterialCode(), result.getMaterialCode())) {
                return true;
            }
        }
        return false;
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
        List<LhScheduleResult> assignedResults = context.getMachineAssignmentMap().get(result.getLhMachineCode());
        LhScheduleResult latestResult = resolveLatestAssignedResult(assignedResults);
        if (latestResult != null) {
            LhScheduleResult previousResult = resolvePreviousAssignedResult(assignedResults, latestResult);
            applyMachineStateFromResult(context, machine, latestResult, previousResult);
            return;
        }
        restoreMachineStateFromInitial(context, result.getLhMachineCode(), machine);
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
     * <p>仅收尾SKU才按共用胎胚规则（仅取硫化余量）；非收尾SKU继续按 MAX(余量, 胎胚库存)，
     * 避免共用胎胚导致非收尾SKU的 isEnd 被误翻转为 "1"。</p>
     *
     * @param context 排程上下文
     * @param result 排程结果
     * @return 收尾比较量
     */
    private int resolveEndingDemandQty(LhScheduleContext context, LhScheduleResult result) {
        SkuScheduleDTO sku = findSkuDto(context, result.getMaterialCode());
        int surplusQty = sku != null ? Math.max(0, sku.getSurplusQty())
                : Math.max(0, result.getMouldSurplusQty() == null ? 0 : result.getMouldSurplusQty());
        int embryoStock = sku != null ? Math.max(0, sku.getEmbryoStock())
                : Math.max(0, result.getEmbryoStock() == null ? 0 : result.getEmbryoStock());
        // 仅收尾SKU才按共用胎胚规则（仅取硫化余量），非收尾SKU保持原口径
        if (sku != null
                && SkuTagEnum.ENDING.getCode().equals(sku.getSkuTag())
                && getTargetScheduleQtyResolver().isSharedEmbryoInWindow(context, sku)) {
            return surplusQty;
        }
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
                                   boolean isEnding,
                                   Date mouldChangeCompleteTime) {
        if (lhTimeSeconds <= 0 || mouldQty <= 0 || remaining <= 0 || startTime == null) {
            return remaining;
        }
        /*
         * 普通换模首检数量归属口径：
         * 1. 换模8小时已包含首检，不额外增加首检时间；
         * 2. 首检只影响数量归属和班产占用；
         * 3. 归属班次由换模完成时间落点决定；
         * 4. 首检数量参与排产量、余量消耗和班产上限校验。
         */
        int firstInspectionShiftIndex = FirstInspectionQtyUtil.resolveAttributionShiftIndex(
                shifts, mouldChangeCompleteTime);
        int firstInspectionQty = FirstInspectionQtyUtil.addFirstInspectionQtyToResult(
                context, result, shifts, mouldChangeCompleteTime, shiftCapacity, remaining,
                ScheduleTypeEnum.NEW_SPEC.getCode());
        remaining -= firstInspectionQty;
        Map<Integer, ShiftRuntimeState> stateMap = context.getShiftRuntimeStateMap();
        int dryIceLossQty = context.getParamIntValue(
                LhScheduleParamConstant.DRY_ICE_LOSS_QTY, LhScheduleConstant.DRY_ICE_LOSS_QTY);
        int dryIceDurationHours = context.getParamIntValue(
                LhScheduleParamConstant.DRY_ICE_DURATION_HOURS, LhScheduleConstant.DRY_ICE_DURATION_HOURS);
        String configPlusShiftType = ShiftCapacityResolverUtil.resolveOddShiftCapacityPlusShiftType(context);

        // 试制非收尾SKU在本轮分配内按日期追踪已消费日计划额度，防止同一天多个班次重复消费。
        // 新增排产仅补欠产场景复用该账本做滚动额度预演，避免窗口日计划为0时跨天班次被误裁。
        Map<LocalDate, Integer> trialDailyConsumedMap = null;
        if (sku != null && sku.isStrictTargetQty() && !isEnding) {
            Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap = sku.getDailyPlanQuotaMap();
            if (quotaMap != null && !quotaMap.isEmpty()) {
                trialDailyConsumedMap = new LinkedHashMap<>(4);
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
                    dryIceDurationHours,
                    shift,
                    configPlusShiftType,
                    ScheduleTypeEnum.NEW_SPEC.getCode());
            shiftMaxQty = ShiftProductionControlUtil.deductCapacityByControl(control, shiftMaxQty, mouldQty);
            shiftMaxQty = FirstInspectionQtyUtil.resolveNormalCapacityAfterFirstInspection(
                    context, shift, shiftMaxQty, firstInspectionShiftIndex, firstInspectionQty,
                    shiftCapacity, ScheduleTypeEnum.NEW_SPEC.getCode());
            if (shiftMaxQty <= 0) {
                continue;
            }

            // 试制非收尾SKU严格按照日计划额度限制班次可排量上限，不允许超出当日计划量补满班次
            if (trialDailyConsumedMap != null) {
                int dailyQuotaCap = sku != null && sku.isStrictNewSpecShortageOnly()
                        ? resolveStrictNewSpecRollingQuotaCap(context, sku, shift.getWorkDate(), mouldQty,
                                trialDailyConsumedMap)
                        : resolveDailyQuotaCap(sku, shift.getWorkDate(), mouldQty, trialDailyConsumedMap);
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
                Integer existingQty = ShiftFieldUtil.getShiftPlanQty(result, shift.getShiftIndex());
                Date existingStartTime = ShiftFieldUtil.getShiftStartTime(result, shift.getShiftIndex());
                int mergedQty = Math.max(0, existingQty == null ? 0 : existingQty) + shiftQty;
                setShiftPlanQty(result, shift.getShiftIndex(), mergedQty,
                        existingStartTime == null ? effectiveStart : existingStartTime, shiftPlanEndTime);
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
     * 解析新增排产仅补历史欠产时的滚动额度上限。
     * <p>该场景 T～T+2 日计划为0，欠产额度只追加在首日账本；班次跨天时仍应消费同一欠产池，
     * 不能按后续工作日0计划直接截断。</p>
     *
     * @param context 排程上下文
     * @param sku SKU排程DTO
     * @param workDate 班次归属工作日
     * @param mouldQty 模台数
     * @param trialDailyConsumedMap 本轮分配内已预占额度
     * @return 滚动额度上限，-1表示无需限制
     */
    private int resolveStrictNewSpecRollingQuotaCap(LhScheduleContext context,
                                                    SkuScheduleDTO sku,
                                                    Date workDate,
                                                    int mouldQty,
                                                    Map<LocalDate, Integer> trialDailyConsumedMap) {
        if (workDate == null || sku == null || CollectionUtils.isEmpty(sku.getDailyPlanQuotaMap())) {
            return -1;
        }
        LocalDate productionDate = workDate.toInstant()
                .atZone(ZoneId.systemDefault()).toLocalDate();
        if (!sku.getDailyPlanQuotaMap().containsKey(productionDate)) {
            return 0;
        }
        Map<LocalDate, SkuDailyPlanQuotaDTO> trialQuotaMap = buildSimulationQuotaMap(
                sku.getDailyPlanQuotaMap(), SkuDailyPlanQuotaUtil.sumRemainingQty(sku.getDailyPlanQuotaMap()));
        replayTrialConsumedQuota(context, trialQuotaMap, trialDailyConsumedMap);
        int totalRemainingQty = SkuDailyPlanQuotaUtil.sumRemainingQty(trialQuotaMap);
        if (totalRemainingQty <= 0) {
            return 0;
        }
        int dailyQuotaCap = SkuDailyPlanQuotaUtil.consumeRollingQuota(
                trialQuotaMap, productionDate, totalRemainingQty,
                resolveLookAheadEndDate(context, trialQuotaMap, productionDate));
        int resolvedMouldQty = ShiftCapacityResolverUtil.resolveMachineMouldQty(mouldQty);
        if (resolvedMouldQty > 1) {
            dailyQuotaCap = (dailyQuotaCap / resolvedMouldQty) * resolvedMouldQty;
        }
        return Math.max(dailyQuotaCap, 0);
    }

    /**
     * 将本轮已分配班次量回放到模拟账本，供后续班次计算剩余额度。
     *
     * @param context 排程上下文
     * @param trialQuotaMap 模拟日计划账本
     * @param trialDailyConsumedMap 本轮分配内已预占额度
     */
    private void replayTrialConsumedQuota(LhScheduleContext context,
                                          Map<LocalDate, SkuDailyPlanQuotaDTO> trialQuotaMap,
                                          Map<LocalDate, Integer> trialDailyConsumedMap) {
        if (CollectionUtils.isEmpty(trialQuotaMap) || CollectionUtils.isEmpty(trialDailyConsumedMap)) {
            return;
        }
        for (LocalDate productionDate : trialQuotaMap.keySet()) {
            Integer consumedQty = trialDailyConsumedMap.get(productionDate);
            if (consumedQty == null || consumedQty <= 0) {
                continue;
            }
            SkuDailyPlanQuotaUtil.consumeRollingQuota(
                    trialQuotaMap, productionDate, consumedQty,
                    resolveLookAheadEndDate(context, trialQuotaMap, productionDate));
        }
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

    /**
     * 新增换模只能从当前排程窗口首班开始发起，不能借用窗口外的空闲时段提前换模。
     *
     * @param context 排程上下文
     * @param shifts 排程窗口班次
     * @param switchReadyTime 当前候选机台的可切换时间
     * @return 与排程窗口首班对齐后的可切换时间
     */
    private Date alignNewSpecSwitchReadyTimeToWindowStart(LhScheduleContext context,
                                                          List<LhShiftConfigVO> shifts,
                                                          Date switchReadyTime) {
        if (switchReadyTime == null) {
            return null;
        }
        Date windowStartTime = resolveScheduleWindowStartTime(context, shifts);
        if (windowStartTime != null && switchReadyTime.before(windowStartTime)) {
            return windowStartTime;
        }
        return switchReadyTime;
    }

    /**
     * 解析当前排程窗口首班开始时间。
     *
     * @param context 排程上下文
     * @param shifts 排程窗口班次
     * @return 窗口首班开始时间
     */
    private Date resolveScheduleWindowStartTime(LhScheduleContext context, List<LhShiftConfigVO> shifts) {
        if (!CollectionUtils.isEmpty(shifts) && shifts.get(0).getShiftStartDateTime() != null) {
            return shifts.get(0).getShiftStartDateTime();
        }
        if (context != null && context.getScheduleDate() != null) {
            return context.getScheduleDate();
        }
        return null;
    }

    /**
     * 调整续作释放机台的新增换模就绪时间。
     * <p>收尾SKU首日降模释放出的机台，若承接同日有计划且单段换模的新SKU，需要保留早班尾量生产窗口。</p>
     *
     * @param context 排程上下文
     * @param sku 新增SKU
     * @param machine 候选机台
     * @param switchReadyTime 原换模就绪时间
     * @return 调整后的换模就绪时间
     */
    private Date adjustReleasedMachineSwitchReadyTime(LhScheduleContext context,
                                                      SkuScheduleDTO sku,
                                                      MachineScheduleDTO machine,
                                                      Date switchReadyTime) {
        if (context == null || sku == null || machine == null || switchReadyTime == null
                || CollectionUtils.isEmpty(context.getReleasedContinuousMachineCodeSet())
                || !context.getReleasedContinuousMachineCodeSet().contains(machine.getMachineCode())
                || !hasPositivePlanOnSwitchReadyDate(sku, switchReadyTime)
                || StringUtils.isEmpty(resolveSingleMouldChangeSegment(sku.getMouldChangeInfo()))) {
            return switchReadyTime;
        }
        Date windowStartTime = resolveScheduleWindowStartTime(context, context.getScheduleWindowShifts());
        if (windowStartTime == null || !switchReadyTime.equals(windowStartTime)) {
            return switchReadyTime;
        }
        Date adjustedTime = LhScheduleTimeUtil.addHours(switchReadyTime, RELEASED_MACHINE_MORNING_TAIL_HOURS);
        log.info("新增SKU释放机台保留早班尾量, materialCode: {}, machineCode: {}, beforeReadyTime: {}, afterReadyTime: {}",
                sku.getMaterialCode(), machine.getMachineCode(),
                LhScheduleTimeUtil.formatDateTime(switchReadyTime),
                LhScheduleTimeUtil.formatDateTime(adjustedTime));
        return adjustedTime;
    }

    /**
     * 判断SKU在换模就绪业务日是否有正日计划。
     *
     * @param sku 新增SKU
     * @param switchReadyTime 换模就绪时间
     * @return true-存在正日计划
     */
    private boolean hasPositivePlanOnSwitchReadyDate(SkuScheduleDTO sku, Date switchReadyTime) {
        if (sku == null || switchReadyTime == null || CollectionUtils.isEmpty(sku.getDailyPlanQuotaMap())) {
            return false;
        }
        LocalDate productionDate = switchReadyTime.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
        SkuDailyPlanQuotaDTO quota = sku.getDailyPlanQuotaMap().get(productionDate);
        return quota != null && quota.getDayPlanQty() > 0;
    }

    /**
     * 解析单段换模信息。
     *
     * @param mouldChangeInfo 换模信息
     * @return 单段换模值，多段或空值返回空
     */
    private String resolveSingleMouldChangeSegment(String mouldChangeInfo) {
        if (StringUtils.isEmpty(mouldChangeInfo)) {
            return null;
        }
        String[] segments = StringUtils.split(mouldChangeInfo, ",");
        if (segments == null || segments.length != 1) {
            return null;
        }
        return StringUtils.trimToNull(segments[0]);
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

    /**
     * 解析新增换模结果实际使用的模具号。
     *
     * @param context 排程上下文
     * @param sku 当前SKU
     * @param machine 当前机台
     * @param mouldQty 机台模数
     * @param allocationResult 模具资源分配结果
     * @return 实际使用模具号，多个逗号分隔
     */
    private String resolveActualMouldCodeForNewSpecResult(LhScheduleContext context,
                                                          SkuScheduleDTO sku,
                                                          MachineScheduleDTO machine,
                                                          int mouldQty,
                                                          MouldResourceAllocationResult allocationResult) {
        int requiredMouldQty = ShiftCapacityResolverUtil.resolveMachineMouldQty(mouldQty);
        if (allocationResult == null || CollectionUtils.isEmpty(allocationResult.getAllocatedMouldCodeList())) {
            log.info("新增排产结果实际模具号为空, materialCode: {}, machineCode: {}, requiredMouldQty: {}",
                    sku.getMaterialCode(), machine.getMachineCode(), requiredMouldQty);
            return null;
        }
        if (allocationResult.getAllocatedMouldCodeList().size() < requiredMouldQty) {
            log.info("新增排产结果实际模具数量不足, materialCode: {}, machineCode: {}, requiredMouldQty: {}, "
                            + "allocatedMouldCodes: {}",
                    sku.getMaterialCode(), machine.getMachineCode(), requiredMouldQty,
                    allocationResult.getAllocatedMouldCodeList());
            return null;
        }
        String actualMouldCode = StringUtils.join(allocationResult.getAllocatedMouldCodeList(), ",");
        log.debug("新增排产结果写入实际模具号, batchNo: {}, materialCode: {}, machineCode: {}, "
                        + "requiredMouldQty: {}, actualMouldCode: {}",
                context.getBatchNo(), sku.getMaterialCode(), machine.getMachineCode(), requiredMouldQty, actualMouldCode);
        return actualMouldCode;
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
            return mouldChangeBalance.allocateMouldChange(
                    context, machineCode, switchReadyTime, switchDurationHours,
                    sku, IMouldChangeBalanceStrategy.ACTION_NEW_SPEC_MOULD_CHANGE);
        }
        // 先把已有结果和滚动继承结果里的同胎胚换模班次回填到占用表，避免新增规格只感知本轮登记的占用。
        preloadGreenTireChangeoverOccupancy(context);
        Date cursorTime = switchReadyTime;
        for (int attempt = 0; attempt < LhScheduleConstant.MAX_SHIFT_SLOT_COUNT * 2; attempt++) {
            Date allocatedTime = mouldChangeBalance.allocateMouldChange(
                    context, machineCode, cursorTime, switchDurationHours,
                    sku, IMouldChangeBalanceStrategy.ACTION_NEW_SPEC_MOULD_CHANGE);
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
        if (isChangeoverBalanceEnabled(context) && mouldChangeBalance != null) {
            mouldChangeBalance.rollbackMouldChange(context, mouldChangeStartTime);
        }
        rollbackGreenTireChangeoverShift(context, sku, mouldChangeStartTime);
    }

    /**
     * 解析新增排产的换模开始时间。
     * <p>基础换模耗时、停机重叠和晚班不可换模永远保留；换模均衡配额仅在开关开启时生效。</p>
     *
     * @param context 排程上下文
     * @param machineCode 机台编码
     * @param switchReadyTime 机台可切换时间
     * @param switchDurationHours 换模耗时
     * @param mouldChangeBalance 换模均衡策略
     * @return 实际换模开始时间；无法安排时返回 null
     */
    private Date allocateNewSpecMouldChangeStartTime(LhScheduleContext context,
                                                     SkuScheduleDTO sku,
                                                     String machineCode,
                                                     Date switchReadyTime,
                                                     int switchDurationHours,
                                                     IMouldChangeBalanceStrategy mouldChangeBalance) {
        if (isChangeoverBalanceEnabled(context)) {
            return mouldChangeBalance.allocateMouldChange(
                    context, machineCode, switchReadyTime, switchDurationHours,
                    sku, IMouldChangeBalanceStrategy.ACTION_NEW_SPEC_MOULD_CHANGE);
        }
        return allocateBasicMouldChangeStartTime(context, machineCode, switchReadyTime, switchDurationHours);
    }

    /**
     * 基础换模时间分配。
     * <p>关闭换模均衡时，只保留停机重叠与晚班不可换模约束，不再校验早/中班及日累计换模配额。</p>
     *
     * @param context 排程上下文
     * @param machineCode 机台编码
     * @param switchReadyTime 机台可切换时间
     * @param switchDurationHours 换模耗时
     * @return 实际换模开始时间；无法安排时返回 null
     */
    private Date allocateBasicMouldChangeStartTime(LhScheduleContext context,
                                                   String machineCode,
                                                   Date switchReadyTime,
                                                   int switchDurationHours) {
        if (switchReadyTime == null) {
            return null;
        }
        Date adjustedTime = switchReadyTime;
        for (int attempt = 0; attempt < NEW_SPEC_CHANGEOVER_PROBE_LIMIT; attempt++) {
            Date downtimeAdjustedTime = resolveDowntimeAdjustedMouldChangeStartTime(
                    context, machineCode, adjustedTime, switchDurationHours);
            if (downtimeAdjustedTime != null && downtimeAdjustedTime.after(adjustedTime)) {
                adjustedTime = downtimeAdjustedTime;
                continue;
            }
            if (LhScheduleTimeUtil.isNoMouldChangeTime(context, adjustedTime)) {
                adjustedTime = LhScheduleTimeUtil.resolveNextMorningAfterNoMouldChangeWindow(context, adjustedTime);
                continue;
            }
            return adjustedTime;
        }
        log.warn("新增排产基础换模时间分配失败, machineCode: {}, switchReadyTime: {}, switchDurationHours: {}",
                machineCode, LhScheduleTimeUtil.formatDateTime(switchReadyTime), switchDurationHours);
        return null;
    }

    /**
     * 扣除设备停机后的最早换模开始时间。
     *
     * @param context 排程上下文
     * @param machineCode 机台编码
     * @param candidateStartTime 候选换模开始时间
     * @param switchDurationHours 换模耗时
     * @return 停机顺延后的开始时间
     */
    private Date resolveDowntimeAdjustedMouldChangeStartTime(LhScheduleContext context,
                                                             String machineCode,
                                                             Date candidateStartTime,
                                                             int switchDurationHours) {
        if (context == null
                || StringUtils.isEmpty(machineCode)
                || candidateStartTime == null
                || CollectionUtils.isEmpty(context.getDevicePlanShutList())) {
            return candidateStartTime;
        }
        Date candidateEndTime = LhScheduleTimeUtil.addHours(candidateStartTime, switchDurationHours);
        Date latestOverlapEndTime = null;
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
        return latestOverlapEndTime != null ? latestOverlapEndTime : candidateStartTime;
    }

    /**
     * 判断新增排产是否启用换模均衡。
     *
     * @param context 排程上下文
     * @return true-启用；false-关闭
     */
    private boolean isChangeoverBalanceEnabled(LhScheduleContext context) {
        LhScheduleConfig scheduleConfig = context != null ? context.getScheduleConfig() : null;
        if (scheduleConfig == null) {
            return LhScheduleConstant.ENABLE_CHANGEOVER_BALANCE == 1;
        }
        return scheduleConfig.isChangeoverBalanceEnabled();
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

    /**
     * 解析机台新增换模接续起点。
     *
     * @param context 排程上下文
     * @param machine 候选机台
     * @param shifts 排程窗口班次
     * @return 机台已占用结束时间
     */
    private Date resolveMachineOccupationEndTime(LhScheduleContext context,
                                                 MachineScheduleDTO machine,
                                                 List<LhShiftConfigVO> shifts) {
        Date machineEndTime = Objects.nonNull(machine) ? machine.getEstimatedEndTime() : null;
        Date assignedEndTime = Objects.nonNull(machine)
                ? resolveLatestAssignedEndTime(context, machine.getMachineCode()) : null;
        Date occupationEndTime = resolveLaterTime(machineEndTime, assignedEndTime);
        if (Objects.nonNull(occupationEndTime)) {
            return occupationEndTime;
        }
        return resolveDefaultMachineEndTime(context, shifts);
    }

    /**
     * 获取同一机台已登记有效结果的最新结束时间。
     *
     * @param context 排程上下文
     * @param machineCode 机台编码
     * @return 最新有效结果结束时间
     */
    private Date resolveLatestAssignedEndTime(LhScheduleContext context, String machineCode) {
        if (Objects.isNull(context) || StringUtils.isEmpty(machineCode)) {
            return null;
        }
        LhScheduleResult latestResult = resolveLatestAssignedResult(
                context.getMachineAssignmentMap().get(machineCode));
        return Objects.nonNull(latestResult) ? latestResult.getSpecEndTime() : null;
    }

    /**
     * 获取两个时间中较晚的一个。
     *
     * @param first 第一个时间
     * @param second 第二个时间
     * @return 较晚时间
     */
    private Date resolveLaterTime(Date first, Date second) {
        if (Objects.isNull(first)) {
            return second;
        }
        if (Objects.isNull(second)) {
            return first;
        }
        return first.after(second) ? first : second;
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
        if (context.getWindowEndDate() == null) {
            return null;
        }
        return context.getWindowEndDate().toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
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
    private boolean needMoreMachine(LhScheduleContext context, SkuScheduleDTO sku) {
        return DailyMachineExpansionPlanner.needMoreMachine(context, sku);
    }

    /**
     * 判断小额历史欠产是否允许继续向后滚动，不再为清欠产追加新机台。
     *
     * @param context 排程上下文
     * @param sku SKU排程DTO
     * @return true-后续日计划已满足，可停止扩机台
     */
    private boolean isSmallShortageRollingSatisfied(LhScheduleContext context, SkuScheduleDTO sku) {
        return DailyMachineExpansionPlanner.isSmallShortageRollingSatisfied(context, sku);
    }

    /**
     * 判断当前SKU是否属于欠产未超阈值的普通新增排产场景。
     *
     * @param context 排程上下文
     * @param sku SKU排程DTO
     * @return true-小额欠产允许后续滚动；false-仍按原有目标量扩机台
     */
    private boolean shouldAllowSmallShortageRolling(LhScheduleContext context, SkuScheduleDTO sku) {
        return DailyMachineExpansionPlanner.shouldAllowSmallShortageRolling(context, sku);
    }

    /**
     * 判断除首日以外的后续日计划额度是否已经满足。
     *
     * @param sku SKU排程DTO
     * @return true-后续日期无剩余额度；false-仍有后续日计划未满足
     */
    private boolean isFutureQuotaSatisfied(SkuScheduleDTO sku) {
        return DailyMachineExpansionPlanner.isFutureQuotaSatisfied(sku);
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
