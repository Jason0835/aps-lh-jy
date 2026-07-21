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
import com.zlt.aps.lh.component.StructureMinMachineRetentionService;
import com.zlt.aps.lh.component.SkuDecrementChecker;
import com.zlt.aps.lh.component.CapsuleReplacementRuleService;
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
import com.zlt.aps.lh.api.enums.MouldChangeTypeEnum;
import com.zlt.aps.lh.api.enums.MachineStopTypeEnum;
import com.zlt.aps.lh.api.enums.ScheduleTypeEnum;
import com.zlt.aps.lh.api.enums.ShiftEnum;
import com.zlt.aps.lh.api.enums.SkuScheduleSourceTypeEnum;
import com.zlt.aps.lh.api.enums.SkuTagEnum;
import com.zlt.aps.lh.component.MonthPlanDateResolver;
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
import com.zlt.aps.lh.engine.strategy.support.EarlyProductionChecker;
import com.zlt.aps.lh.engine.strategy.support.EarlyProductionDecision;
import com.zlt.aps.lh.engine.strategy.support.HistoricalReverseSelectionDirective;
import com.zlt.aps.lh.engine.strategy.support.MachineProductionSegment;
import com.zlt.aps.lh.engine.strategy.support.MachineScheduleRole;
import com.zlt.aps.lh.engine.strategy.support.MouldResourceAllocationResult;
import com.zlt.aps.lh.engine.strategy.support.MouldResourceContext;
import com.zlt.aps.lh.engine.strategy.support.NewSpecCandidateCache;
import com.zlt.aps.lh.engine.strategy.support.PendingSkuUnscheduledRule;
import com.zlt.aps.lh.engine.strategy.support.ProductionQuantityPolicy;
import com.zlt.aps.lh.engine.strategy.support.SmallEndingSurplusSkipRule;
import com.zlt.aps.lh.engine.strategy.support.SpecifiedMachineMatchResult;
import com.zlt.aps.lh.service.impl.LhMaintenanceScheduleService;
import com.zlt.aps.lh.util.CleaningScheduleRuleUtil;
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
import com.zlt.aps.mdm.api.domain.entity.MdmMaterialInfo;
import com.zlt.aps.utils.ProductSpecificationsUtils;
import com.zlt.aps.mdm.api.domain.entity.MdmSkuConstructionRef;
import com.zlt.aps.mdm.api.domain.entity.MdmSkuMouldRel;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import javax.annotation.Resource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
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
    /** 历史交替计划无产品状态时按正规状态归一化 */
    private static final String FORMAL_PRODUCT_STATUS = "S";
    /** 命中SKU减量清单的未排备注（与SkuDecrementChecker文案保持一致） */
    private static final String SKU_DECREMENT_UNSCHEDULED_REASON = "命中SKU减量清单，不进行排产";
    private static final String ZERO_PLAN_UNSCHEDULED_REASON = "新增结果裁剪为0";
    private static final String SHARED_EMBRYO_ZERO_SURPLUS_UNSCHEDULED_REASON =
            "共用胎胚且硫化余量为0";
    private static final String SMALL_ENDING_SURPLUS_UNSCHEDULED_REASON =
            SmallEndingSurplusSkipRule.UNSCHEDULED_REASON;
    private static final String TARGET_SKU_MOULD_ALL_OCCUPIED_UNSCHEDULED_REASON =
            "目标 SKU 模具全部被占用";
    private static final int NEW_SPEC_CHANGEOVER_PROBE_LIMIT = 16;
    /** 反向匹配规格层级:同规格 */
    private static final int REVERSE_MATCH_SPEC_LEVEL_SAME_SPEC = 0;
    /** 反向匹配规格层级:同断面宽 */
    private static final int REVERSE_MATCH_SPEC_LEVEL_SAME_WIDTH = 1;
    /** 反向匹配规格层级:同英寸 */
    private static final int REVERSE_MATCH_SPEC_LEVEL_SAME_INCH = 2;
    /** 反向匹配规格层级:无匹配 */
    private static final int REVERSE_MATCH_SPEC_LEVEL_NONE = 3;
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
    private SkuDecrementChecker skuDecrementChecker;
    @Resource
    private LhMaintenanceScheduleService maintenanceScheduleService;
    @Resource
    private StructureMinMachineRetentionService structureMinMachineRetentionService =
            new StructureMinMachineRetentionService();
    @Resource
    private ITrialProductionStrategy trialProductionStrategy;
    /** 胶囊次数累计与换胶囊班次扣减统一入口 */
    @Resource
    private CapsuleReplacementRuleService capsuleReplacementRuleService = new CapsuleReplacementRuleService();

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
        Map<String, Integer> materialEmbryoStockMap = new LinkedHashMap<>(16);
        Map<String, List<LhScheduleResult>> materialResultMap = new LinkedHashMap<>(16);
        for (LhScheduleResult result : context.getScheduleResultList()) {
            if (!NEW_SPEC_SCHEDULE_TYPE.equals(result.getScheduleType())
                    || "1".equals(result.getIsTypeBlock())) {
                continue;
            }
            if (result.getEmbryoCode() == null) {
                continue;
            }
            SkuScheduleDTO sku = findSkuDto(
                    context, result.getMaterialCode(), result.getProductStatus());
            if (sku == null || sku.getEmbryoStock() < 0) {
                continue;
            }
            int planQty = ShiftFieldUtil.resolveScheduledQty(result);
            materialTotalPlanMap.merge(result.getMaterialCode(), planQty, Integer::sum);
            materialEmbryoStockMap.merge(result.getMaterialCode(), sku.getEmbryoStock(), Math::max);
            materialResultMap.computeIfAbsent(result.getMaterialCode(), key -> new ArrayList<LhScheduleResult>())
                    .add(result);
        }
        // 按汇总计划量统一裁剪同物料的所有结果
        List<LhShiftConfigVO> shifts = LhScheduleTimeUtil.getScheduleShifts(context, context.getScheduleDate());
        for (Map.Entry<String, List<LhScheduleResult>> entry : materialResultMap.entrySet()) {
            String materialCode = entry.getKey();
            int totalPlan = materialTotalPlanMap.getOrDefault(materialCode, 0);
            int embryoStock = materialEmbryoStockMap.getOrDefault(materialCode, 0);
            if (totalPlan <= 0 || totalPlan <= embryoStock) {
                continue;
            }
            if (shouldKeepAllProductStatusFullCapacity(context, entry.getValue())) {
                log.info("正式新增跳过胎胚库存后置裁减, materialCode: {}, totalPlan: {}, embryoStock: {}",
                        materialCode, totalPlan, embryoStock);
                continue;
            }
            // 库存不足时按物料整体裁剪，避免逐条逐班取整导致总量丢失。
            ShiftFieldUtil.scaleGroupedShiftPlanQty(entry.getValue(), shifts, embryoStock);
            for (LhScheduleResult result : entry.getValue()) {
                refreshResultSummary(context, result);
            }
        }
        // 同SKU多机台只拆分排产量，每条结果保留SKU级完整胎胚库存。
        retainMultiMachineEmbryoStock(context);
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

    /**
     * 判断同物料全部产品状态是否都允许保留满班结果。
     * <p>库存仍按物料整体约束，但严格目标状态不得被其它非严格状态带着跳过库存裁剪。</p>
     *
     * @param context 排程上下文
     * @param materialResults 同物料结果列表
     * @return true-全部状态均允许保留满班结果
     */
    private boolean shouldKeepAllProductStatusFullCapacity(LhScheduleContext context,
                                                           List<LhScheduleResult> materialResults) {
        if (CollectionUtils.isEmpty(materialResults)) {
            return false;
        }
        Map<String, List<LhScheduleResult>> skuResultMap = new LinkedHashMap<String, List<LhScheduleResult>>(4);
        for (LhScheduleResult result : materialResults) {
            if (result == null) {
                continue;
            }
            String skuKey = MonthPlanDateResolver.buildMaterialStatusKey(
                    result.getMaterialCode(), result.getProductStatus());
            skuResultMap.computeIfAbsent(skuKey, key -> new ArrayList<LhScheduleResult>(2)).add(result);
        }
        for (List<LhScheduleResult> skuResults : skuResultMap.values()) {
            LhScheduleResult firstResult = skuResults.get(0);
            SkuScheduleDTO sku = findSkuDto(
                    context, firstResult.getMaterialCode(), firstResult.getProductStatus());
            if (!shouldKeepFormalNewSpecFullCapacity(sku, skuResults)) {
                return false;
            }
        }
        return !skuResultMap.isEmpty();
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
        rebuildScheduledMachineCountMap(context, shifts);
        Map<String, Integer> unscheduledReasonCountMap = new LinkedHashMap<>(8);
        initializePendingNewSpecSkuTypeCounts(context);
        int scheduledCount = schedulePendingNewSpecs(context, machineMatch, mouldChangeBalance,
                inspectionBalance, capacityCalculate, shifts, unscheduledReasonCountMap);
        // 新增主链结束后统一核查收尾机台，给出尾部产能是否被利用的可对账原因。
        traceReleasedMachineTailCapacityAudit(context, shifts);
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
                                + ", 预计收尾=" + PriorityTraceLogHelper.oneZero(endingJudgmentStrategy.isExpectedEnding(context, sku)));
            }
        }
        PriorityTraceLogHelper.appendTitleFooter(detailBuilder);
        PriorityTraceLogHelper.logSortSummary(log, context, title, detailBuilder.toString());
    }

    /**
     * 输出收尾释放机台在 S4.5 新增排产后的尾部产能利用核查日志。
     * <p>该日志只做可观测性补充，不改变候选机台排序和排产结果；用于说明收尾机台
     * 为什么有后续新增结果，或者为什么没有被新增排产承接。</p>
     *
     * @param context 排程上下文
     * @param shifts 排程窗口班次
     */
    private void traceReleasedMachineTailCapacityAudit(LhScheduleContext context, List<LhShiftConfigVO> shifts) {
        if (!PriorityTraceLogHelper.isEnabled(context) || Objects.isNull(context)
                || CollectionUtils.isEmpty(context.getScheduleResultList())) {
            return;
        }
        String title = "释放机台尾部产能核查";
        StringBuilder detailBuilder = new StringBuilder(2048);
        PriorityTraceLogHelper.appendTitleHeader(detailBuilder, title);
        PriorityTraceLogHelper.appendLine(detailBuilder,
                PriorityTraceLogHelper.kv("排程日期", PriorityTraceLogHelper.formatDateTime(context.getScheduleDate()))
                        + ", " + PriorityTraceLogHelper.kv("步骤", context.getCurrentStep()));

        int auditCount = 0;
        for (LhScheduleResult result : context.getScheduleResultList()) {
            // 只核查真正收尾并释放机台的结果，普通非收尾结果不参与尾部产能审计。
            if (!isEndingMachineReleaseResult(result)) {
                continue;
            }
            auditCount++;
            LhScheduleResult nextResult = findNextResultOnSameMachine(context, result);
            MachineScheduleDTO machine = CollectionUtils.isEmpty(context.getMachineScheduleMap())
                    ? null : context.getMachineScheduleMap().get(result.getLhMachineCode());
            int tailShiftCount = countTailShiftAfterRelease(shifts, result.getSpecEndTime());
            String reason = resolveReleasedMachineTailAuditReason(context, machine, result, nextResult, tailShiftCount);
            PriorityTraceLogHelper.appendLine(detailBuilder,
                    "- " + PriorityTraceLogHelper.kv("机台", result.getLhMachineCode())
                            + ", " + PriorityTraceLogHelper.kv("收尾物料", result.getMaterialCode())
                            + ", " + PriorityTraceLogHelper.kv("收尾类型", result.getScheduleType())
                            + ", " + PriorityTraceLogHelper.kv("收尾时间",
                                    PriorityTraceLogHelper.formatDateTime(result.getSpecEndTime()))
                            + ", " + PriorityTraceLogHelper.kv("剩余班次数", tailShiftCount)
                            + ", " + PriorityTraceLogHelper.kv("核查结果", reason));
        }
        if (auditCount == 0) {
            PriorityTraceLogHelper.appendLine(detailBuilder, "无收尾释放机台需要核查");
        }
        PriorityTraceLogHelper.appendTitleFooter(detailBuilder);
        PriorityTraceLogHelper.logSortSummary(log, context, title, detailBuilder.toString());
    }

    /**
     * 判断结果是否为会释放机台的收尾结果。
     *
     * @param result 排程结果
     * @return true-收尾释放结果；false-无需核查
     */
    private boolean isEndingMachineReleaseResult(LhScheduleResult result) {
        return Objects.nonNull(result)
                && StringUtils.isNotEmpty(result.getLhMachineCode())
                && "1".equals(result.getIsEnd())
                && Objects.nonNull(result.getSpecEndTime());
    }

    /**
     * 查找同机台在当前收尾结果后的下一条排产结果。
     *
     * @param context 排程上下文
     * @param releaseResult 收尾释放结果
     * @return 下一条结果；没有则返回 null
     */
    private LhScheduleResult findNextResultOnSameMachine(LhScheduleContext context, LhScheduleResult releaseResult) {
        LhScheduleResult nextResult = null;
        for (LhScheduleResult result : context.getScheduleResultList()) {
            if (result == releaseResult || Objects.isNull(result)
                    || !StringUtils.equals(releaseResult.getLhMachineCode(), result.getLhMachineCode())
                    || Objects.isNull(result.getSpecEndTime())
                    || !result.getSpecEndTime().after(releaseResult.getSpecEndTime())) {
                continue;
            }
            if (Objects.isNull(nextResult) || result.getSpecEndTime().before(nextResult.getSpecEndTime())) {
                nextResult = result;
            }
        }
        return nextResult;
    }

    /**
     * 统计收尾时间后的剩余班次数。
     *
     * @param shifts 排程窗口班次
     * @param releaseTime 收尾释放时间
     * @return 收尾后仍覆盖的班次数
     */
    private int countTailShiftAfterRelease(List<LhShiftConfigVO> shifts, Date releaseTime) {
        if (CollectionUtils.isEmpty(shifts) || Objects.isNull(releaseTime)) {
            return 0;
        }
        int count = 0;
        for (LhShiftConfigVO shift : shifts) {
            if (Objects.nonNull(shift) && Objects.nonNull(shift.getShiftEndDateTime())
                    && shift.getShiftEndDateTime().after(releaseTime)) {
                count++;
            }
        }
        return count;
    }

    /**
     * 解析收尾释放机台尾部产能核查原因。
     *
     * @param context 排程上下文
     * @param machine 机台运行态
     * @param releaseResult 收尾释放结果
     * @param nextResult 同机台后续结果
     * @param tailShiftCount 收尾后剩余班次数
     * @return 核查原因
     */
    private String resolveReleasedMachineTailAuditReason(LhScheduleContext context,
                                                         MachineScheduleDTO machine,
                                                         LhScheduleResult releaseResult,
                                                         LhScheduleResult nextResult,
                                                         int tailShiftCount) {
        if (Objects.nonNull(nextResult)) {
            // 同机台已有后续结果，说明尾部产能已被新增或换活字块继续利用。
            return "已利用，后续物料=" + PriorityTraceLogHelper.safeText(nextResult.getMaterialCode())
                    + "，后续类型=" + PriorityTraceLogHelper.safeText(nextResult.getScheduleType())
                    + "，后续完工=" + PriorityTraceLogHelper.formatDateTime(nextResult.getSpecEndTime());
        }
        if (tailShiftCount <= 0) {
            // 收尾时间已经贴近窗口末端，没有可承接的后续班次。
            return "收尾后排程窗口内无剩余班次";
        }
        if (CollectionUtils.isEmpty(context.getNewSpecSkuList())) {
            // 新增主链已经消费完待排队列，此时不再强行制造换模或兜底补排。
            return "新增待排队列已无剩余SKU";
        }
        if (Objects.isNull(machine)) {
            return "机台运行态不存在，无法参与新增候选匹配";
        }
        if (!hasHardMatchedPendingNewSpecSku(context, machine)) {
            // 有剩余待排 SKU，但寸口、模套、特殊材料等硬约束不允许当前机台承接。
            return "剩余新增SKU与机台硬约束不匹配";
        }
        return "存在硬匹配待排SKU但未落地，需查看新增候选机台回裁跳过或新增选机过滤日志";
    }

    /**
     * 判断剩余新增 SKU 中是否存在与机台硬匹配的物料。
     *
     * @param context 排程上下文
     * @param machine 机台
     * @return true-存在硬匹配 SKU；false-不存在
     */
    private boolean hasHardMatchedPendingNewSpecSku(LhScheduleContext context, MachineScheduleDTO machine) {
        if (CollectionUtils.isEmpty(context.getNewSpecSkuList()) || Objects.isNull(machine)) {
            return false;
        }
        for (SkuScheduleDTO sku : context.getNewSpecSkuList()) {
            if (sku == null || sku.resolveTargetScheduleQty() <= 0) {
                continue;
            }
            if (LhMachineHardMatchUtil.isMachineHardMatched(context, sku, machine)) {
                return true;
            }
        }
        return false;
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
        // 单控反向匹配推荐映射:materialCode -> 配对侧机台编码,单边粒度SKU排上单控一侧后设置,目标SKU选机时优先使用
        Map<String, String> reverseMatchPreferredMachineMap = new HashMap<String, String>(4);
        // 单控反向匹配预留机台编码集合:配对侧机台被反向匹配推荐后,非推荐目标SKU选机时排除,使配对侧留给推荐目标SKU
        Set<String> reverseMatchReservedMachineCodes = new HashSet<String>(4);
        while (iterator.hasNext()) {
            // 上一个SKU可能刚出队；在当前SKU生成候选前统一刷新结构保护，避免提前复用已占机台。
            structureMinMachineRetentionService.refreshRetention(context);
            SkuScheduleDTO sku = iterator.next();
            boolean currentSkuRemoved = false;
            // 兜底校验：动态生成的补偿SKU若命中减量清单，写未排并跳过（去重set保证不重复写未排）
            if (skuDecrementChecker.isDecrementHit(context, sku)) {
                boolean written = skuDecrementChecker.handleDecrementHit(context, sku);
                if (written) {
                    unscheduledReasonCountMap.merge(SKU_DECREMENT_UNSCHEDULED_REASON, 1, Integer::sum);
                }
                removeCurrentNewSpecSku(context, iterator, sku);
                progressed = true;
                log.info("新增主循环兜底拦截命中减量清单SKU, materialCode: {}, 已写入未排: {}", sku.getMaterialCode(), written);
                continue;
            }
            // 续作、换活字块未消费完的 SKU 在此继续参与 S4.5，并统一按小余量优先顺序执行未排判断。
            boolean isEnding = endingJudgmentStrategy.isCurrentWindowEnding(context, sku);
            boolean forceEndingByNoFuturePlan = prepareNewSpecShortageQuota(context, sku);
            boolean smallEndingSurplusRuleEnding = isEnding;
            if (forceEndingByNoFuturePlan) {
                // 窗口和月底均无未来计划时，新增按收尾清量处理，目标量允许结合胎胚库存上调。
                isEnding = true;
                smallEndingSurplusRuleEnding = true;
            } else if (sku.isStrictNewSpecShortageOnly()) {
                /*
                 * 窗口无计划但月底仍有计划时，排产结果仍按非收尾严格补本月历史欠产；
                 * 但收尾小余量规则必须保留原始收尾口径，避免硫化余量已收尾的小尾数绕过未排判断。
                 */
                isEnding = false;
            }
            // 成型胎胚库存收尾优先于SKU收尾判断，直接按胎胚库存严格控量。
            boolean embryoStockEndingTargetApplied = getTargetScheduleQtyResolver()
                    .applyEmbryoStockEndingTargetQtyIfNecessary(context, sku, "新增排产");
            // 在目标量上调和正式排产前统一判断未排规则，固定收尾小余量优先于仅历史欠产。
            LhUnscheduledResult ruleUnscheduledResult = PendingSkuUnscheduledRule.evaluate(
                    context, sku, smallEndingSurplusRuleEnding, embryoStockEndingTargetApplied);
            if (Objects.nonNull(ruleUnscheduledResult)) {
                context.getUnscheduledResultList().add(ruleUnscheduledResult);
                String unscheduledReason = ruleUnscheduledResult.getUnscheduledReason();
                unscheduledReasonCountMap.merge(unscheduledReason, 1, Integer::sum);
                removeCurrentNewSpecSku(context, iterator, sku);
                if (StringUtils.equals(SMALL_ENDING_SURPLUS_UNSCHEDULED_REASON, unscheduledReason)
                        || embryoStockEndingTargetApplied) {
                    getTargetScheduleQtyResolver().removeActiveEmbryoSku(context, sku, unscheduledReason);
                }
                if (StringUtils.equals(SMALL_ENDING_SURPLUS_UNSCHEDULED_REASON, unscheduledReason)) {
                    traceSmallEndingSurplusJudge(context, sku, smallEndingSurplusRuleEnding, true);
                }
                progressed = true;
                log.info("新增SKU命中前置未排规则, materialCode: {}, unscheduledQty: {}, reason: {}",
                        sku.getMaterialCode(), ruleUnscheduledResult.getUnscheduledQty(), unscheduledReason);
                continue;
            }
            // 收尾SKU在排产前上调目标量（考虑胎胚库存），非收尾SKU保持按余量计算的目标量
            boolean sharedEmbryoZeroSurplusEnding = false;
            if (isEnding && !embryoStockEndingTargetApplied) {
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
            if (embryoStockEndingTargetApplied) {
                quantityPolicy.setAllowFillStartedShift(false);
                quantityPolicy.setStrictUpperLimit(true);
                quantityPolicy.setFullRunForNonTailMachine(false);
            }
            sku.setStrictTargetQty(quantityPolicy.isStrictUpperLimit());
            log.info("新增SKU开始排产, materialCode: {}, 结构: {}, 规格: {}, 月计划量: {}, 目标量: {}, "
                            + "day1/day2/day3窗口量: {}, 余量: {}, 胎胚库存: {}, 是否收尾: {}, "
                            + "允许补满已开班次: {}, 严格禁止超排: {}, 非最后机台满排: {}",
                    sku.getMaterialCode(), sku.getStructureName(), sku.getSpecCode(),
                    sku.getMonthPlanQty(), sku.resolveTargetScheduleQty(), sku.getWindowPlanQty(),
                    sku.getSurplusQty(), sku.getEmbryoStock(), isEnding,
                    quantityPolicy.isAllowFillStartedShift(), quantityPolicy.isStrictUpperLimit(),
                    quantityPolicy.isFullRunForNonTailMachine());
            if (shouldSkipNewSpecBecauseContinuousSatisfiesOriginalDayMinimum(context, sku, quantityPolicy)) {
                removeCurrentNewSpecSku(context, iterator, sku);
                progressed = true;
                continue;
            }

            if (shouldSkipTrialSku(context, sku)) {
                addUnscheduledResult(context, sku, "试制量试当日不可排产", unscheduledReasonCountMap);
                removeCurrentNewSpecSku(context, iterator, sku);
                progressed = true;
                continue;
            }

            // 1. 匹配候选机台：只做硬性准入和候选排序，换模/首检/产能在后续逐台试算。
            context.getNewSpecTypeRuleBlockedMap().remove(sku);
            refreshNewSpecEarlyProductionAdmission(context, sku, shifts, isEnding);
            List<MachineScheduleDTO> candidates = machineMatch.matchMachines(context, sku);
            /*
             * 前日交替计划指定机台在普通候选排序完成后单独做硬约束复核并提到候选头部。
             * 这不会改变普通候选之间的顺序；指定机台失败后，当前SKU继续使用原普通候选列表。
             */
            candidates = prioritizeHistoricalReverseSpecifiedMachines(
                    context, sku, candidates, machineMatch);
            logNewSpecMachineCandidateSnapshot(context, sku, candidates, EMPTY_STRING_SET, null);
            if (candidates.isEmpty()) {
                // 初始候选为空时仍记录本次实际选机使用的空列表，便于按 SKU 对账失败原因。
                machineMatch.traceMachinePriorityOrder(context, sku, Collections.<MachineScheduleDTO>emptyList());
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
            // 初始化多机台拆量剩余量：dayN只做节奏判断，实际拆机按SKU实际消费账本剩余额度收敛。
            int remainingQty = resolveSchedulableRemainingQty(context, sku);
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
            // dayN模拟按新增顺序记录机台生效日期，后续第N台不得提前到其对应业务日之前生产。
            List<LocalDate> addMachineProductionDateList = new ArrayList<LocalDate>(4);
            int originalAddMachineCount = countAvailableCandidateMachines(candidates, EMPTY_STRING_SET);
            int actualAllowedAddMachineCount = 0;
            LhScheduleResult lastScheduledResult = null;
            MachineProductionSegment lastScheduledSegment = null;
            NewSpecCandidateCache candidateCache = NewSpecCandidateCache.from(candidates,
                    machine -> isSingleControlMachine(context, machine.getMachineCode()));
            while (true) {
                // 上一轮指定机台失败时，基于真实候选失败原因结算反选指令，然后继续普通新增选机。
                finalizeRejectedHistoricalReverseDirectives(
                        context, sku, excludedMachineCodes, excludedMachineReasonMap);
                logNewSpecMachineCandidateSnapshot(context, sku, candidates, excludedMachineCodes, excludedMachineReasonMap);
                MachineScheduleDTO candidateMachine = null;
                List<MachineScheduleDTO> orderedCandidates = new ArrayList<>(candidates.size());
                HistoricalReverseSelectionDirective historicalDirective =
                        findNextHistoricalReverseDirective(
                                context, sku, candidates, excludedMachineCodes);
                if (Objects.nonNull(historicalDirective)) {
                    candidateMachine = findMachineInList(
                            candidates, historicalDirective.getEffectiveMachineCode());
                    List<MachineScheduleDTO> availableCandidates =
                            filterExcludedCandidates(candidates, excludedMachineCodes);
                    fillSelectedCandidateOrder(availableCandidates, candidateMachine, orderedCandidates);
                    log.info("前日交替计划指定机台优先尝试, materialCode: {}, productStatus: {}, "
                                    + "historicalShift: {}, mappedShift: {}, historicalMachine: {}, effectiveMachine: {}",
                            sku.getMaterialCode(), sku.getProductStatus(),
                            historicalDirective.getHistoricalShiftIndex(),
                            historicalDirective.getMappedShiftIndex(),
                            historicalDirective.getMachineCode(),
                            historicalDirective.getEffectiveMachineCode());
                }
                // 单控反向匹配推荐机台优先:当前SKU为反向匹配目标且推荐机台在候选中时,优先选择配对侧
                String reverseMatchSkuKey = LhSingleControlMachineUtil.buildSkuModeKey(sku);
                String preferredPairMachineCode = reverseMatchPreferredMachineMap.get(reverseMatchSkuKey);
                if (Objects.isNull(candidateMachine)
                        && StringUtils.isNotEmpty(preferredPairMachineCode)
                        && LhSingleControlMachineUtil.isSingleSideGranularitySku(context, sku)
                        && containsMachine(candidates, preferredPairMachineCode)
                        && !excludedMachineCodes.contains(preferredPairMachineCode)) {
                    candidateMachine = findMachineInList(candidates, preferredPairMachineCode);
                    reverseMatchPreferredMachineMap.remove(reverseMatchSkuKey);
                    // 推荐目标SKU选中预留机台后,释放预留
                    reverseMatchReservedMachineCodes.remove(preferredPairMachineCode);
                    log.info("单控反向匹配推荐机台优先选择, materialCode: {}, machineCode: {}",
                            sku.getMaterialCode(), preferredPairMachineCode);
                    List<MachineScheduleDTO> availableCandidates =
                            filterExcludedCandidates(candidates, excludedMachineCodes);
                    fillSelectedCandidateOrder(availableCandidates, candidateMachine, orderedCandidates);
                }
                if (candidateMachine == null) {
                    // 非反向匹配推荐目标SKU选机时,排除被反向匹配预留的单控机台,使配对侧留给推荐目标SKU
                    if (LhSingleControlMachineUtil.isSingleSideGranularitySku(context, sku)
                            && !CollectionUtils.isEmpty(reverseMatchReservedMachineCodes)) {
                        for (String reservedMachineCode : reverseMatchReservedMachineCodes) {
                            if (containsMachine(candidates, reservedMachineCode)) {
                                excludedMachineCodes.add(reservedMachineCode);
                            }
                        }
                    }
                    candidateMachine = selectCandidateMachine(
                            context, sku, candidateCache, excludedMachineCodes, machineMatch,
                            preferredTrialMachine, quantityPolicy, orderedCandidates);
                }
                // 直接记录本次实际用于取首台机台的同一有序列表，不为日志重新过滤或排序。
                machineMatch.traceMachinePriorityOrder(context, sku, orderedCandidates);
                // 实际排产也直接读取该列表第一台，确保日志序号与后续选机使用的数据完全一致。
                candidateMachine = CollectionUtils.isEmpty(orderedCandidates) ? null : orderedCandidates.get(0);
                if (Objects.isNull(candidateMachine)) {
                    break;
                }
                String machineCode = candidateMachine.getMachineCode();
                // 候选可能来自普通排序，按实际选中机台重新确认本轮是否属于历史指定机台尝试。
                historicalDirective = findHistoricalReverseDirective(
                        context, sku, machineCode, false);
                LocalDate currentAddMachineProductionDate = resolveCurrentAddMachineProductionDate(
                        sku, addMachineProductionDateList, actualAllowedAddMachineCount);
                if (StringUtils.isEmpty(machineCode)) {
                    log.warn("候选机台编码为空，跳过新增SKU排产, materialCode: {}, 目标量: {}",
                            sku.getMaterialCode(), sku.resolveTargetScheduleQty());
                    failReason = selectHigherPriorityFailReason(
                            failReason, NewSpecFailReasonEnum.MACHINE_SELECTION_FAILED);
                    break;
                }
                refreshCurrentScheduleDate(context, sku, currentAddMachineProductionDate);
                // SKU新增机台必须先按候选机台模数预占可用模具；模具不足只跳过当前机台，不能中断排程主链。
                MouldResourceAllocationResult mouldResourceAllocationResult = tryAllocateMouldResourceForAddMachine(
                        context, sku, candidateMachine, originalAddMachineCount, actualAllowedAddMachineCount);
                MouldResourceAllocationResult pairMouldResourceAllocationResult = null;
                MachineScheduleDTO pairSingleControlMachine = resolveWholeSingleControlPairMachine(context, sku, candidateMachine);
                boolean wholeSingleControlUnit = Objects.nonNull(pairSingleControlMachine);
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
                if (wholeSingleControlUnit) {
                    // 正规SKU使用单控机台时，L/R两边必须作为一个物理整机同步预占模具；副侧失败则主侧也回滚。
                    pairMouldResourceAllocationResult = tryAllocateMouldResourceForAddMachine(
                            context, sku, pairSingleControlMachine, originalAddMachineCount, actualAllowedAddMachineCount);
                    if (!pairMouldResourceAllocationResult.isAllowed()) {
                        rollbackMouldResourceAllocation(context, sku, mouldResourceAllocationResult);
                        excludedMachineCodes.add(machineCode);
                        excludedMachineCodes.add(pairSingleControlMachine.getMachineCode());
                        candidateCache.removeMachine(machineCode);
                        candidateCache.removeMachine(pairSingleControlMachine.getMachineCode());
                        recordExcludedMachineReason(excludedMachineReasonMap, machineCode,
                                pairMouldResourceAllocationResult.getSkipReason().getDescription(),
                                null, null, null, null, null, null, null, null, null);
                        failReason = selectHigherPriorityFailReason(
                                failReason, NewSpecFailReasonEnum.MACHINE_SELECTION_FAILED);
                        log.info("双模SKU单控整机副侧模具资源不足，整机候选回滚, materialCode: {}, leftMachine: {}, rightMachine: {}, reason: {}",
                                sku.getMaterialCode(), machineCode, pairSingleControlMachine.getMachineCode(),
                                pairMouldResourceAllocationResult.getSkipReason().getDescription());
                        continue;
                    }
                }

                // 3. 计算机台可开工时间（考虑机台当前预计完工和能力策略约束）
                Date endingTime = resolveMachineOccupationEndTime(context, sku, candidateMachine, shifts);
                if (isEnding) {
                    getMaintenanceScheduleService().tryAttachMaintenanceAfterFirstEnding(
                            context, candidateMachine, endingTime);
                }
                // 试制SKU换模需在早班完成：维保窗口挂载后，检查正序换模窗口是否与维保窗口物理重叠。
                // 若重叠则清除维保窗口，使后续 calculateStartTime 不被维保推迟，换模可在早班开始；
                // 维保将在后续排程迭代中重新安排。
                // 注意：endingTime可能落在禁止换模时段(晚班20:00-次日06:00)，此时实际换模开始时间
                // 应为次日早班，需先对齐到早班后再检查重叠，否则会遗漏晚班endingTime对应的早班换模窗口。
                if (isTrialConstructionStage(sku)) {
                    int trialNormalSwitchHours = LhScheduleTimeUtil.getMouldChangeTotalHours(context);
                    // 试制SKU换模必须在早班开始,若endingTime落在禁止换模时段,对齐到次日早班后再检查重叠
                    Date trialSwitchStartTime = LhScheduleTimeUtil.isNoMouldChangeTime(context, endingTime)
                            ? LhScheduleTimeUtil.resolveNextMorningAfterNoMouldChangeWindow(context, endingTime)
                            : endingTime;
                    if (getMaintenanceScheduleService().isNormalSwitchOverlapMaintenance(
                            context, candidateMachine, trialSwitchStartTime, trialNormalSwitchHours)) {
                        log.info("试制SKU换模窗口与维保窗口物理重叠，清除维保窗口以便早班换模, "
                                        + "materialCode: {}, machineCode: {}, endingTime: {}, switchStartTime: {}, normalSwitchHours: {}",
                                sku.getMaterialCode(), machineCode,
                                LhScheduleTimeUtil.formatDateTime(endingTime),
                                LhScheduleTimeUtil.formatDateTime(trialSwitchStartTime), trialNormalSwitchHours);
                        getMaintenanceScheduleService().clearMaintenanceWindows(context, candidateMachine);
                    }
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
                // 试制SKU换模需在早班完成，不受开产模式限制；非试制SKU仍受开产模式约束
                switchReadyTime = ShiftProductionControlUtil.resolveEarliestSwitchStartTime(
                        context, switchReadyTime, sku);
                switchReadyTime = alignNewSpecSwitchReadyTimeToWindowStart(context, shifts, switchReadyTime);
                // 历史只继承映射班次，不继承具体时刻；本批切换最早起点按当前映射班次开始时间重新对齐。
                switchReadyTime = alignHistoricalReverseSwitchReadyTime(
                        context, historicalDirective, switchReadyTime);

                // 4. 分配换模窗口；晚班不可换模、换模上限和维保重叠都在分配器中统一收口。
                // 基础换模时间永远执行，换模均衡仅在开关开启时介入。
                Date mouldChangeStartTime = null;
                Date mouldChangeCompleteTime = null;
                LhShiftConfigVO firstInspectionAttributionShift = null;
                Date inspectionTime = null;
                Date productionStartTime = null;
                NewSpecFailReasonEnum switchAllocateFailReason = null;
                int switchDurationHours = maintenanceOverlapSwitch
                        ? LhScheduleTimeUtil.getMaintenanceOverlapSwitchHours(context)
                        : LhScheduleTimeUtil.getMouldChangeTotalHours(context);
                // 续作增机补偿的首台与后续机台统一按 dayN 首次增机日对齐换模。
                switchReadyTime = alignSwitchReadyTimeByAddMachineDate(
                        context, sku, switchReadyTime, shifts, totalScheduledQty,
                        currentAddMachineProductionDate, isEnding);
                mouldChangeStartTime = allocateNewSpecMouldChangeStartTime(
                        context, sku, machineCode, switchReadyTime, switchDurationHours, mouldChangeBalance);
                if (Objects.nonNull(mouldChangeStartTime)
                        && !isHistoricalReverseMouldChangeInMappedShift(
                        context, historicalDirective, mouldChangeStartTime)) {
                    /*
                     * 分配器可能因晚班禁换模、停机、保养或换模配额把切换推迟到后续班次。
                     * 映射班次是本需求硬约束，因此完整回滚本候选的换模和模具预占，再让SKU进入普通候选。
                     */
                    rollbackMouldChangeAllocation(context, sku, mouldChangeBalance, mouldChangeStartTime);
                    rollbackMouldResourceAllocation(context, sku, mouldResourceAllocationResult,
                            pairMouldResourceAllocationResult);
                    excludedMachineCodes.add(machineCode);
                    candidateCache.removeMachine(machineCode);
                    String mappedShiftFailureReason =
                            "按本批机台状态重新分配后，换模开始时间未落在历史映射班次";
                    recordExcludedMachineReason(excludedMachineReasonMap, machineCode,
                            mappedShiftFailureReason,
                            machineReadyTime, switchReadyTime, mouldChangeStartTime, null,
                            null, null, null, null, null);
                    markHistoricalReverseDirectiveFailed(
                            context, historicalDirective, mappedShiftFailureReason);
                    failReason = selectHigherPriorityFailReason(
                            failReason, NewSpecFailReasonEnum.MOULD_CHANGE_SHIFT_ALLOCATE_FAILED);
                    continue;
                }
                if (mouldChangeStartTime == null) {
                    log.debug("新增SKU换模窗口分配失败, materialCode: {}, 机台: {}, 机台就绪: {}, 目标量: {}",
                            sku.getMaterialCode(), machineCode,
                            LhScheduleTimeUtil.formatDateTime(switchReadyTime), sku.resolveTargetScheduleQty());
                    switchAllocateFailReason = NewSpecFailReasonEnum.MOULD_CHANGE_SHIFT_ALLOCATE_FAILED;
                }
                if (mouldChangeStartTime != null) {
                    mouldChangeCompleteTime = LhScheduleTimeUtil.addHours(mouldChangeStartTime, switchDurationHours);
                    boolean plannedRepairAffectingSwitch = ShiftCapacityResolverUtil.isPlannedRepairAffectingSwitch(
                            context, context.getDevicePlanShutList(), machineCode, endingTime,
                            mouldChangeStartTime, mouldChangeCompleteTime);
                    Date plannedRepairReadyTime = ShiftCapacityResolverUtil.resolvePlannedRepairProductionReadyTime(
                            context, context.getDevicePlanShutList(), machineCode, endingTime,
                            mouldChangeStartTime, mouldChangeCompleteTime);
                    /*
                     * 新增规格命中计划性维修时，换模允许与维修并行；首检归属必须从
                     * max(维修结束, 换模结束)+SYS0307009 预热完成时刻开始，且不再额外增加1小时。
                     * 未命中05时继续使用原换模完成时刻，保持既有新增规格行为不变。
                     */
                    Date firstInspectionBaseTime = plannedRepairAffectingSwitch
                            ? plannedRepairReadyTime : mouldChangeCompleteTime;
                    firstInspectionAttributionShift = FirstInspectionQtyUtil.resolveFirstInspectionAttributionShift(
                            context, sku, shifts, firstInspectionBaseTime, ScheduleTypeEnum.NEW_SPEC.getCode());
                    Date firstInspectionAttributionTime = FirstInspectionQtyUtil.resolveFirstInspectionAttributionTime(
                            context, sku, shifts, firstInspectionBaseTime, ScheduleTypeEnum.NEW_SPEC.getCode());
                    if (firstInspectionAttributionTime == null) {
                        log.debug("新增SKU首检归属班次为空, materialCode: {}, 机台: {}, 换模开始: {}, 换模完成: {}",
                                sku.getMaterialCode(), machineCode,
                                LhScheduleTimeUtil.formatDateTime(mouldChangeStartTime),
                                LhScheduleTimeUtil.formatDateTime(mouldChangeCompleteTime));
                        rollbackMouldChangeAllocation(context, sku, mouldChangeBalance, mouldChangeStartTime);
                        mouldChangeStartTime = null;
                        switchAllocateFailReason = NewSpecFailReasonEnum.FIRST_INSPECTION_SHIFT_ALLOCATE_FAILED;
                    } else {
                        inspectionTime = inspectionBalance.allocateInspection(
                                context, machineCode, firstInspectionAttributionTime);
                        if (inspectionTime == null) {
                            log.debug("新增SKU首检分配失败, materialCode: {}, 机台: {}, 换模开始: {}, 换模完成: {}",
                                    sku.getMaterialCode(), machineCode,
                                    LhScheduleTimeUtil.formatDateTime(mouldChangeStartTime),
                                    LhScheduleTimeUtil.formatDateTime(mouldChangeCompleteTime));
                            rollbackMouldChangeAllocation(context, sku, mouldChangeBalance, mouldChangeStartTime);
                            mouldChangeStartTime = null;
                            switchAllocateFailReason = NewSpecFailReasonEnum.FIRST_INSPECTION_SHIFT_ALLOCATE_FAILED;
                        } else {
                            /*
                             * 普通 SKU 的8小时换模已包含首检，首检均衡只占用首检资源，不得再推迟正常生产；
                             * 试制 SKU 首检任务仍由均衡策略登记，但生产量改由中班固定2小时产能上限控制；
                             * 维保重叠专用口径仍按“4小时切换 + 1小时首检”顺延开产。
                             */
                            Date defaultProductionStartTime = plannedRepairAffectingSwitch
                                    ? plannedRepairReadyTime : maintenanceOverlapSwitch
                                    ? LhScheduleTimeUtil.addHours(
                                            inspectionTime, LhScheduleTimeUtil.getFirstInspectionHours(context))
                                    : mouldChangeCompleteTime;
                            // 试制SKU早班换模后只能在同业务日中班开始生产，早班仍只保存真实换模占用。
                            productionStartTime = FirstInspectionQtyUtil.resolveTrialProductionStartTime(
                                    context, sku, shifts, firstInspectionBaseTime, defaultProductionStartTime,
                                    ScheduleTypeEnum.NEW_SPEC.getCode());
                            if (plannedRepairAffectingSwitch) {
                                log.info("新增SKU计划性维修时间轴生效, materialCode: {}, machineCode: {}, "
                                                + "switchStartTime: {}, switchEndTime: {}, preheatMinutes: {}, "
                                                + "productionReadyTime: {}, firstInspectionExtraHours: 0",
                                        sku.getMaterialCode(), machineCode,
                                        LhScheduleTimeUtil.formatDateTime(mouldChangeStartTime),
                                        LhScheduleTimeUtil.formatDateTime(mouldChangeCompleteTime),
                                        LhScheduleTimeUtil.getCapsulePreheatMinutes(context),
                                        LhScheduleTimeUtil.formatDateTime(plannedRepairReadyTime));
                            }
                            // 清洗与普通换模重叠时只执行换模，开产时间仍按换模/首检规则计算；清洗原因由结果备注单独记录。
                        }
                    }
                }
                if (mouldChangeStartTime == null) {
                    rollbackMouldResourceAllocation(context, sku, mouldResourceAllocationResult,
                            pairMouldResourceAllocationResult);
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
                if (shouldSkipCompensationEarlySingleControlCandidate(context, sku, candidateMachine,
                        productionStartTime, shifts)) {
                    rollbackMouldChangeAllocation(context, sku, mouldChangeBalance, mouldChangeStartTime);
                    rollbackMouldResourceAllocation(context, sku, mouldResourceAllocationResult,
                            pairMouldResourceAllocationResult);
                    excludedMachineCodes.add(machineCode);
                    candidateCache.removeMachine(machineCode);
                    recordExcludedMachineReason(excludedMachineReasonMap, machineCode,
                            "提前生产开产未落在当前业务日", machineReadyTime, switchReadyTime,
                            mouldChangeStartTime, mouldChangeCompleteTime, inspectionTime,
                            productionStartTime, null, null, null);
                    failReason = selectHigherPriorityFailReason(
                            failReason, NewSpecFailReasonEnum.NO_CAPACITY_IN_SCHEDULE_WINDOW);
                    log.info("续作补偿提前生产单控候选跳过, materialCode: {}, machineCode: {}, "
                                    + "productionStartTime: {}, firstWorkDate: {}, reason: {}",
                            sku.getMaterialCode(), machineCode,
                            LhScheduleTimeUtil.formatDateTime(productionStartTime),
                            resolveFirstShiftDate(context), "开产业务日不在当前业务日");
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
                EarlyProductionDecision earlyProductionDecision = resolveEarlyProductionDecision(
                        context, sku, firstProductionStartTime, shifts, isEnding);
                // 补偿 SKU 已由续作中心链路确定首次增机日，不得再被已消费的剩余日计划额度推迟。
                firstProductionStartTime = alignProductionStartTimeByAddMachineDate(
                        context, sku, firstProductionStartTime, shifts, totalScheduledQty,
                        currentAddMachineProductionDate, isEnding, earlyProductionDecision);
                if (firstProductionStartTime == null) {
                    log.debug("新增SKU排程窗口内无可开产时间, materialCode: {}, 机台: {}, 首检时间: {}, 班产: {}, 硫化时间: {}, 模数: {}",
                            sku.getMaterialCode(), machineCode,
                            LhScheduleTimeUtil.formatDateTime(productionStartTime),
                            sku.getShiftCapacity(), sku.getLhTimeSeconds(), machineMouldQty);
                    inspectionBalance.rollbackInspection(context, inspectionTime);
                    rollbackMouldChangeAllocation(context, sku, mouldChangeBalance, mouldChangeStartTime);
                    rollbackMouldResourceAllocation(context, sku, mouldResourceAllocationResult,
                            pairMouldResourceAllocationResult);
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
                // 普通 SKU 按换模完成班次合并首检数量；试制 SKU 不写首检条数，仅在中班应用固定2小时产能上限。
                shiftCapacityMap = FirstInspectionQtyUtil.applyFirstInspectionQtyToCapacityMap(
                        context, sku, shifts, firstInspectionAttributionShift, shiftCapacityMap,
                        runtimeShiftCapacity, dynamicTargetQty,
                        ScheduleTypeEnum.NEW_SPEC.getCode(), machineCode);
                shiftCapacityMap = applyDailyStandardCapacityAdjust(
                        context, sku, machineCode, shifts, shiftCapacityMap, runtimeShiftCapacity);
                int maxQtyToWindowEnd = sumShiftCapacity(shiftCapacityMap);
                MachineProductionSegment segment = buildMachineProductionSegment(
                        context, sku, machineCode, mouldChangeStartTime, firstProductionStartTime,
                        maxQtyToWindowEnd, runtimeShiftCapacity, shiftCapacityMap);
                MachineScheduleRole role = resolveMachineScheduleRole(quantityPolicy, totalScheduledQty,
                        maxQtyToWindowEnd, candidateTargetQty);
                segment.setRole(role);
                boolean singleMachineWindowFill = shouldFillSingleMachineToWindowEnd(
                        context, sku, candidateMachine, isEnding, totalScheduledQty,
                        candidateTargetQty, maxQtyToWindowEnd, earlyProductionDecision);
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
                if (CollectionUtils.isEmpty(addMachineProductionDateList)
                        && !CollectionUtils.isEmpty(segment.getAddMachineProductionDateList())) {
                    addMachineProductionDateList.addAll(segment.getAddMachineProductionDateList());
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
                    if (segment.isExistingSameMaterialSatisfied()) {
                        log.info("新增SKU已有同物料机台满足dayN规则，跳过当前新增候选, materialCode: {}, "
                                        + "candidateMachine: {}, existingResultCount: {}",
                                sku.getMaterialCode(), machineCode,
                                countExistingSameMaterialResults(context, sku, machineCode));
                        inspectionBalance.rollbackInspection(context, inspectionTime);
                        rollbackMouldChangeAllocation(context, sku, mouldChangeBalance, mouldChangeStartTime);
                        rollbackMouldResourceAllocation(context, sku, mouldResourceAllocationResult,
                                pairMouldResourceAllocationResult);
                        removeCurrentNewSpecSku(context, iterator, sku);
                        currentSkuRemoved = true;
                        progressed = true;
                        scheduled = true;
                        break;
                    }
                    log.debug("新增SKU动态分配后本机台计划量为0, materialCode: {}, 机台: {}, 目标量: {}, 换模开始: {}, 开产时间: {}",
                            sku.getMaterialCode(), machineCode, candidateTargetQty,
                            LhScheduleTimeUtil.formatDateTime(mouldChangeStartTime),
                            LhScheduleTimeUtil.formatDateTime(firstProductionStartTime));
                    appendNewSpecCandidateRejectedProcessLog(context, sku, machineCode,
                            "动态分配后本机台计划量为0",
                            machineReadyTime, switchReadyTime, mouldChangeStartTime, mouldChangeCompleteTime,
                            firstProductionStartTime, maxQtyToWindowEnd, machinePlanQty, null);
                    inspectionBalance.rollbackInspection(context, inspectionTime);
                    rollbackMouldChangeAllocation(context, sku, mouldChangeBalance, mouldChangeStartTime);
                    rollbackMouldResourceAllocation(context, sku, mouldResourceAllocationResult,
                            pairMouldResourceAllocationResult);
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
                        mouldChangeCompleteTime, shifts, machineMouldQty, isEnding,
                        mouldResourceAllocationResult, shiftCapacityMap, firstInspectionAttributionShift);
                if (result == null || result.getDailyPlanQty() == null || result.getDailyPlanQty() <= 0) {
                    log.debug("新增SKU结果无有效班次计划量, materialCode: {}, 机台: {}, 目标量: {}, 开产时间: {}",
                            sku.getMaterialCode(), machineCode, sku.resolveTargetScheduleQty(),
                            LhScheduleTimeUtil.formatDateTime(firstProductionStartTime));
                    appendNewSpecCandidateRejectedProcessLog(context, sku, machineCode,
                            "结果无有效班次计划量",
                            machineReadyTime, switchReadyTime, mouldChangeStartTime, mouldChangeCompleteTime,
                            firstProductionStartTime, maxQtyToWindowEnd, machinePlanQty, null);
                    // 无有效产能时回滚首检和换模占用，避免影响后续SKU排产
                    inspectionBalance.rollbackInspection(context, inspectionTime);
                    FirstInspectionQtyUtil.rollbackFirstInspectionSequence(context, firstInspectionAttributionShift);
                    rollbackMouldChangeAllocation(context, sku, mouldChangeBalance, mouldChangeStartTime);
                    rollbackMouldResourceAllocation(context, sku, mouldResourceAllocationResult,
                            pairMouldResourceAllocationResult);
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
                applyDailyStandardPlanQtyToResult(context, sku, result, shifts, runtimeShiftCapacity);
                LhScheduleResult pairResult = wholeSingleControlUnit
                        ? buildWholeSingleControlPairResult(context, result, pairSingleControlMachine, sku,
                                machineMouldQty, pairMouldResourceAllocationResult)
                        : null;
                // 7. 先消费dayN节奏账本，再落地结果与刷新机台状态；非收尾实际排产由SKU实际消费账本控制。
                // 收尾/试制等严格目标量会被截断；正规/量试非收尾允许记录满班补齐超排。
                int machineScheduledQty = wholeSingleControlUnit
                        ? applyWholeSingleControlBlockToDailyQuota(context, sku, result, pairResult, shifts)
                        : applyBlockToDailyQuota(context, sku, result, shifts);
                if (machineScheduledQty <= 0) {
                    appendNewSpecCandidateRejectedProcessLog(context, sku, machineCode,
                            "日计划额度回裁后为0",
                            machineReadyTime, switchReadyTime, mouldChangeStartTime, mouldChangeCompleteTime,
                            firstProductionStartTime, maxQtyToWindowEnd, machinePlanQty, machineScheduledQty);
                    inspectionBalance.rollbackInspection(context, inspectionTime);
                    rollbackMouldChangeAllocation(context, sku, mouldChangeBalance, mouldChangeStartTime);
                    rollbackMouldResourceAllocation(context, sku, mouldResourceAllocationResult,
                            pairMouldResourceAllocationResult);
                    sku.setTargetScheduleQty(baseTargetScheduleQty);
                    remainingQty = resolveSchedulableRemainingQty(context, sku);
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
                LocalDate resultBusinessDate = firstProductionStartTime.toInstant()
                        .atZone(ZoneId.systemDefault()).toLocalDate();
                // 仅对通过既有资源约束且最终有有效计划量的新增结果追加提前生产审计备注。
                appendEarlyProductionRemark(context, result, earlyProductionDecision, resultBusinessDate);
                context.getScheduleResultList().add(result);
                context.getScheduleResultSourceSkuMap().put(result, sku);
                if (ShiftCapacityResolverUtil.isPlannedRepairAffectingSwitch(
                        context, context.getDevicePlanShutList(), machineCode, endingTime,
                        mouldChangeStartTime, mouldChangeCompleteTime)) {
                    // 仅在候选最终形成有效结果后写过程日志，避免失败候选污染批次审计记录。
                    Date repairProductionReadyTime = ShiftCapacityResolverUtil.resolvePlannedRepairProductionReadyTime(
                            context, context.getDevicePlanShutList(), machineCode, endingTime,
                            mouldChangeStartTime, mouldChangeCompleteTime);
                    StringBuilder repairTimelineDetail = new StringBuilder(256);
                    PriorityTraceLogHelper.appendLine(repairTimelineDetail,
                            "机台=" + machineCode + ", SKU=" + sku.getMaterialCode() + ", 切换类型=换模");
                    PriorityTraceLogHelper.appendLine(repairTimelineDetail,
                            "切换开始=" + LhScheduleTimeUtil.formatDateTime(mouldChangeStartTime)
                                    + ", 切换结束=" + LhScheduleTimeUtil.formatDateTime(mouldChangeCompleteTime));
                    PriorityTraceLogHelper.appendLine(repairTimelineDetail,
                            "预热分钟数=" + LhScheduleTimeUtil.getCapsulePreheatMinutes(context)
                                    + ", 最早开产=" + LhScheduleTimeUtil.formatDateTime(repairProductionReadyTime)
                                    + ", 实际首个生产=" + LhScheduleTimeUtil.formatDateTime(firstProductionStartTime)
                                    + ", 首检额外等待小时=0");
                    PriorityTraceLogHelper.appendProcessLog(
                            context, "计划性维修与换模重叠时间轴", repairTimelineDetail.toString().trim());
                }
                // 指定机台真正生成有效结果后才登记成功和保护；失败候选不会污染后续普通新增排产。
                markHistoricalReverseDirectiveSucceeded(
                        context, historicalDirective, sku, result);
                updateMachineState(context, candidateMachine, sku, result);
                registerMachineAssignment(context, machineCode, result);
                recordScheduledMachineForResult(context, result, shifts);
                clearSpecifyReservation(context, machineCode, sku.getMaterialCode());
                if (wholeSingleControlUnit) {
                    // 冻结为双模的SKU必须同时写入配对侧，配对侧沿用主侧整组裁剪后的班次数量。
                    context.getScheduleResultList().add(pairResult);
                    context.getScheduleResultSourceSkuMap().put(pairResult, sku);
                    updateMachineState(context, pairSingleControlMachine, sku, pairResult);
                    registerMachineAssignment(context, pairSingleControlMachine.getMachineCode(), pairResult);
                    recordScheduledMachineForResult(context, pairResult, shifts);
                    clearSpecifyReservation(context, pairSingleControlMachine.getMachineCode(), sku.getMaterialCode());
                }
                // 单边粒度SKU排上单控机台一侧后,尝试为配对侧反向匹配SKU
                if (!wholeSingleControlUnit && isSingleControlMachine(context, machineCode)) {
                    tryReverseMatchPairSingleControlSku(
                            context, sku, machineCode, machineMatch, reverseMatchPreferredMachineMap,
                            reverseMatchReservedMachineCodes);
                }
                candidateCache.clearCapacityCache();
                scheduledCount++;
                actualAllowedAddMachineCount++;
                progressed = true;
                scheduled = true;
                finalMachine = candidateMachine;
                finalProductionStartTime = firstProductionStartTime;
                lastScheduledResult = result;
                lastScheduledSegment = segment;
                // 累计本机台实际排产量，递减多机台剩余量；剩余量仍需结合 dayN 账本判断是否继续加机台。
                totalScheduledQty += machineScheduledQty;
                // businessTargetQty 保留进入本 SKU 时的业务目标，避免 dayN 节奏量被当前机台吃完后提前退出。
                int businessTargetQty = Objects.nonNull(baseTargetScheduleQty)
                        ? Math.max(0, baseTargetScheduleQty) : dynamicTargetQty;
                /*
                 * dayN 模拟已确认当前有效机台数满足节奏时，停止结论必须直接控制主循环。
                 * 剩余业务目标由后续滚动窗口承接，普通空闲机台和续作释放尾部机台都不得再次打开。
                 */
                boolean continueAddMachineBeforeRemaining = needMoreMachine(context, sku)
                        && !segment.isStopAfterCurrentForSmallShortage();
                if (segment.isStopAfterCurrentForSmallShortage()) {
                    // 小额欠产允许滚动时，当前机台已覆盖后续日计划，不再为了首日欠产余额继续扩机。
                    dynamicTargetQty = totalScheduledQty;
                    appendNewSpecDailyRhythmStopProcessLog(context, sku, machineCode,
                            businessTargetQty, totalScheduledQty,
                            "当前有效机台数已满足当前日优先dayN节奏");
                }
                if (continueAddMachineBeforeRemaining && dynamicTargetQty < businessTargetQty) {
                    // dayN 判断仍要求扩机时，继续按原业务目标保留下一台机台待排量。
                    dynamicTargetQty = businessTargetQty;
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
                // 排产前和单台落地后统一使用 dayN 停止标识，避免尾部候选覆盖中心模拟结论。
                boolean continueAddMachineAfterCurrent = needMoreMachine(context, sku)
                        && !segment.isStopAfterCurrentForSmallShortage();
                if (remainingQty <= 0 || !continueAddMachineAfterCurrent) {
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
            /*
             * 同一物料可能存在多条历史机台指令。前一条成功后若实际复合账本已经归零，
             * 后续指令不能再次消费待排量，明确记为“无余量”后交由正常结束逻辑收口。
             */
            finalizeNoRemainingHistoricalReverseDirectives(context, sku);
            if (scheduled && !currentSkuRemoved && remainingQty > 0 && needMoreMachine(context, sku)) {
                int refillQty = refillScheduledResultAfterAddMachineFailure(
                        context, sku, lastScheduledResult, lastScheduledSegment, shifts, quantityPolicy, remainingQty);
                if (refillQty > 0) {
                    totalScheduledQty += refillQty;
                    remainingQty = Math.max(0, remainingQty - refillQty);
                    sku.setRemainingScheduleQty(remainingQty);
                    if (remainingQty <= 0 || !needMoreMachine(context, sku)) {
                        removeCurrentNewSpecSku(context, iterator, sku);
                        currentSkuRemoved = true;
                        log.info("新增SKU增机台失败后原机台回填已满足目标, materialCode: {}, 总排产量: {}, 剩余: {}",
                                sku.getMaterialCode(), totalScheduledQty, remainingQty);
                    }
                }
            }
            if (scheduled) {
                adjustSameSkuMultiMachineAllocation(context, sku, shifts, quantityPolicy, isEnding);
                rebuildScheduledMachineCountMap(context, shifts);
                /*
                 * 当前SKU的多机台收口完成后立即刷新结构保护。结构仍有待排SKU时只保护已占用机台；
                 * 最后一个SKU出队后才执行最终机台数统计和计划量0占位，不影响本SKU数量账本。
                 */
                structureMinMachineRetentionService.refreshRetention(context);
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
        // 本轮最后一个SKU出队后没有下一次循环入口，需在返回前完成最终结构统计和机台释放时间刷新。
        structureMinMachineRetentionService.refreshRetention(context);
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
            getTargetScheduleQtyResolver().restoreProductionRemainingQty(
                    context, sourceSku, resolveResultScheduledQty(placeholderResult),
                    "首日无计划续作占位撤销", placeholderResult.getLhMachineCode());
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
                        && StringUtils.equals(StringUtils.trimToEmpty(sourceSku.getProductStatus()),
                        StringUtils.trimToEmpty(pendingSku.getProductStatus()))
                        && pendingSku.getDailyPlanQuotaMap() == sourceSku.getDailyPlanQuotaMap()) {
                    return true;
                }
            }
        }
        if (!CollectionUtils.isEmpty(deferredCompensationSkuList)) {
            for (SkuScheduleDTO pendingSku : deferredCompensationSkuList) {
                if (pendingSku != null
                        && StringUtils.equals(sourceSku.getMaterialCode(), pendingSku.getMaterialCode())
                        && StringUtils.equals(StringUtils.trimToEmpty(sourceSku.getProductStatus()),
                        StringUtils.trimToEmpty(pendingSku.getProductStatus()))
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
        return findSkuDto(context, result.getMaterialCode(), result.getProductStatus());
    }

    /**
     * 初始化新增待排SKU类型计数，供选机阶段日志和规则排查复用。
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
     * <p>小批量已并入正规组排序；这里仍保留独立计数，只用于日志和规则排查。</p>
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
     * <p>只要窗口内 dayN 全为0，即使SKU类型上属于小批量，也不再计入待排小批量统计。</p>
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
                .append(", scheduleTargetDate: ")
                .append(LhScheduleTimeUtil.formatDate(context.getScheduleTargetDate()))
                .append(", surplusQty: ")
                .append(Math.max(0, sku.getSurplusQty()))
                .append(", monthlyHistoryShortageQty: ")
                .append(Math.max(0, sku.getMonthlyHistoryShortageQty()))
                .append(", futurePlanQtyAfterWindow: ")
                .append(Math.max(0, sku.getFutureMonthPlanQtyAfterWindow()))
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
        // 新增SKU出队时同步维护结构待排视图，供结构最低机台数规则准确识别“全部SKU已处理完成”。
        context.removePendingSkuFromStructureMap(sku);
        context.getNewSpecTypeRuleBlockedMap().remove(sku);
        context.getNewSpecEarlyProductionAllowedMap().remove(sku);
        refreshPendingNewSpecSkuTypeCounts(context);
    }

    /**
     * 将当前SKU尚未尝试的历史指定机台提到候选列表头部。
     *
     * <p>普通候选机台仍由原机台匹配策略完整过滤和排序。本方法只调用指定机台硬过滤入口，
     * 把通过约束的历史机台按历史指令顺序前置；不重新计算或改变其他候选的相对顺序。</p>
     *
     * @param context 排程上下文
     * @param sku 当前新增SKU
     * @param normalCandidates 普通选机有序候选
     * @param machineMatch 机台匹配策略
     * @return 指定机台在前、普通候选原顺序在后的候选列表
     */
    private List<MachineScheduleDTO> prioritizeHistoricalReverseSpecifiedMachines(
            LhScheduleContext context,
            SkuScheduleDTO sku,
            List<MachineScheduleDTO> normalCandidates,
            IMachineMatchStrategy machineMatch) {
        List<HistoricalReverseSelectionDirective> directives =
                context.getHistoricalReverseSelectionDirectiveList();
        if (CollectionUtils.isEmpty(directives)) {
            return normalCandidates;
        }
        List<MachineScheduleDTO> specifiedCandidates =
                new ArrayList<MachineScheduleDTO>(directives.size());
        Set<String> specifiedMachineCodeSet = new LinkedHashSet<String>(directives.size());
        for (HistoricalReverseSelectionDirective directive : directives) {
            if (!isPendingHistoricalReverseDirectiveForSku(directive, sku)) {
                continue;
            }
            SpecifiedMachineMatchResult matchResult = machineMatch.matchSpecifiedMachine(
                    context, sku, directive.getMachineCode());
            if (!matchResult.isSuccess()) {
                markHistoricalReverseDirectiveFailed(
                        context, directive, matchResult.getFailureReason());
                continue;
            }
            MachineScheduleDTO specifiedMachine = matchResult.getMachine();
            directive.setEffectiveMachineCode(specifiedMachine.getMachineCode());
            if (specifiedMachineCodeSet.add(specifiedMachine.getMachineCode())) {
                specifiedCandidates.add(specifiedMachine);
            }
        }
        if (CollectionUtils.isEmpty(specifiedCandidates)) {
            return normalCandidates;
        }
        int normalSize = CollectionUtils.isEmpty(normalCandidates) ? 0 : normalCandidates.size();
        List<MachineScheduleDTO> prioritized =
                new ArrayList<MachineScheduleDTO>(specifiedCandidates.size() + normalSize);
        prioritized.addAll(specifiedCandidates);
        if (!CollectionUtils.isEmpty(normalCandidates)) {
            for (MachineScheduleDTO candidate : normalCandidates) {
                if (Objects.nonNull(candidate)
                        && !specifiedMachineCodeSet.contains(candidate.getMachineCode())) {
                    prioritized.add(candidate);
                }
            }
        }
        return prioritized;
    }

    /**
     * 查找下一条尚未尝试且候选机台可用的历史指令。
     *
     * @param context 排程上下文
     * @param sku 当前SKU
     * @param candidates 当前候选机台
     * @param excludedMachineCodes 当前已排除机台
     * @return 下一条指定机台指令；没有返回null
     */
    private HistoricalReverseSelectionDirective findNextHistoricalReverseDirective(
            LhScheduleContext context,
            SkuScheduleDTO sku,
            List<MachineScheduleDTO> candidates,
            Set<String> excludedMachineCodes) {
        for (HistoricalReverseSelectionDirective directive
                : context.getHistoricalReverseSelectionDirectiveList()) {
            if (!isPendingHistoricalReverseDirectiveForSku(directive, sku)
                    || StringUtils.isEmpty(directive.getEffectiveMachineCode())
                    || excludedMachineCodes.contains(directive.getEffectiveMachineCode())
                    || !containsMachine(candidates, directive.getEffectiveMachineCode())) {
                continue;
            }
            return directive;
        }
        return null;
    }

    /**
     * 按当前SKU和实际候选机台查找反选指令。
     *
     * @param context 排程上下文
     * @param sku 当前SKU
     * @param machineCode 实际候选机台编码
     * @param includeAttempted 是否允许返回已完成指令
     * @return 对应指令；没有返回null
     */
    private HistoricalReverseSelectionDirective findHistoricalReverseDirective(
            LhScheduleContext context,
            SkuScheduleDTO sku,
            String machineCode,
            boolean includeAttempted) {
        for (HistoricalReverseSelectionDirective directive
                : context.getHistoricalReverseSelectionDirectiveList()) {
            if ((!includeAttempted && directive.isAttempted())
                    || !isSameHistoricalReverseSku(directive, sku)
                    || !StringUtils.equals(machineCode, directive.getEffectiveMachineCode())) {
                continue;
            }
            return directive;
        }
        return null;
    }

    /**
     * 结算上一轮已经被新增主链排除的指定机台指令。
     *
     * @param context 排程上下文
     * @param sku 当前SKU
     * @param excludedMachineCodes 已排除机台
     * @param excludedMachineReasonMap 机台失败原因
     */
    private void finalizeRejectedHistoricalReverseDirectives(
            LhScheduleContext context,
            SkuScheduleDTO sku,
            Set<String> excludedMachineCodes,
            Map<String, String> excludedMachineReasonMap) {
        if (CollectionUtils.isEmpty(excludedMachineCodes)) {
            return;
        }
        for (HistoricalReverseSelectionDirective directive
                : context.getHistoricalReverseSelectionDirectiveList()) {
            if (!isPendingHistoricalReverseDirectiveForSku(directive, sku)
                    || StringUtils.isEmpty(directive.getEffectiveMachineCode())
                    || !excludedMachineCodes.contains(directive.getEffectiveMachineCode())) {
                continue;
            }
            String reason = excludedMachineReasonMap.get(directive.getEffectiveMachineCode());
            markHistoricalReverseDirectiveFailed(context, directive,
                    StringUtils.isNotEmpty(reason) ? reason : "指定机台未通过新增排产主链约束");
        }
    }

    /**
     * 将当前SKU已无实际余量时尚未尝试的历史指令标记失败。
     *
     * @param context 排程上下文
     * @param sku 当前SKU
     */
    private void finalizeNoRemainingHistoricalReverseDirectives(
            LhScheduleContext context,
            SkuScheduleDTO sku) {
        if (getTargetScheduleQtyResolver().resolveProductionRemainingQty(context, sku) > 0) {
            return;
        }
        for (HistoricalReverseSelectionDirective directive
                : context.getHistoricalReverseSelectionDirectiveList()) {
            if (isPendingHistoricalReverseDirectiveForSku(directive, sku)) {
                markHistoricalReverseDirectiveFailed(
                        context, directive, "前序反选已消费全部待排量，当前指定机台不再重复排产");
            }
        }
    }

    /**
     * 将指定机台切换就绪时间下限对齐到历史映射班次开始。
     *
     * @param context 排程上下文
     * @param directive 当前反选指令
     * @param switchReadyTime 现有规则计算的切换就绪时间
     * @return 取现有就绪时间和映射班次开始时间的较晚值
     */
    private Date alignHistoricalReverseSwitchReadyTime(
            LhScheduleContext context,
            HistoricalReverseSelectionDirective directive,
            Date switchReadyTime) {
        if (Objects.isNull(directive)) {
            return switchReadyTime;
        }
        LhShiftConfigVO mappedShift = LhScheduleTimeUtil.getShiftByIndex(
                context, context.getScheduleDate(), directive.getMappedShiftIndex());
        if (Objects.isNull(mappedShift) || Objects.isNull(mappedShift.getShiftStartDateTime())) {
            return switchReadyTime;
        }
        if (Objects.isNull(switchReadyTime)
                || switchReadyTime.before(mappedShift.getShiftStartDateTime())) {
            return mappedShift.getShiftStartDateTime();
        }
        return switchReadyTime;
    }

    /**
     * 校验实际换模开始时间是否仍落在历史映射班次。
     *
     * @param context 排程上下文
     * @param directive 当前反选指令
     * @param mouldChangeStartTime 当前规则实际分配的换模开始时间
     * @return true-普通候选或落在映射班次；false-指定机台硬约束失败
     */
    private boolean isHistoricalReverseMouldChangeInMappedShift(
            LhScheduleContext context,
            HistoricalReverseSelectionDirective directive,
            Date mouldChangeStartTime) {
        if (Objects.isNull(directive)) {
            return true;
        }
        return Objects.nonNull(mouldChangeStartTime)
                && LhScheduleTimeUtil.getShiftIndex(
                context, context.getScheduleDate(), mouldChangeStartTime)
                == directive.getMappedShiftIndex();
    }

    /**
     * 标记指定机台新增排产成功，并登记后续保护上下文。
     *
     * @param context 排程上下文
     * @param directive 当前反选指令
     * @param sku 当前SKU
     * @param result 新增主链生成的有效结果
     */
    private void markHistoricalReverseDirectiveSucceeded(
            LhScheduleContext context,
            HistoricalReverseSelectionDirective directive,
            SkuScheduleDTO sku,
            LhScheduleResult result) {
        if (Objects.isNull(directive) || Objects.isNull(result)) {
            return;
        }
        directive.setAttempted(true);
        directive.setSuccess(true);
        directive.setActualChangeType(MouldChangeTypeEnum.REGULAR.getCode());
        directive.setResultReason("指定机台复用新增换模主链排产成功");
        context.registerHistoricalReverseSelectedMachine(
                directive.getMaterialCode(), sku.getProductStatus(), directive.getMachineCode());
        context.protectHistoricalReverseResult(result);
        appendHistoricalReverseNewSpecLog(context, directive, "成功", directive.getResultReason());
    }

    /**
     * 标记指定机台反选失败。
     *
     * @param context 排程上下文
     * @param directive 当前反选指令
     * @param reason 明确失败原因
     */
    private void markHistoricalReverseDirectiveFailed(
            LhScheduleContext context,
            HistoricalReverseSelectionDirective directive,
            String reason) {
        if (Objects.isNull(directive) || directive.isAttempted()) {
            return;
        }
        directive.setAttempted(true);
        directive.setSuccess(false);
        directive.setActualChangeType(MouldChangeTypeEnum.REGULAR.getCode());
        directive.setResultReason(StringUtils.defaultIfEmpty(
                reason, "指定机台未通过新增排产主链约束"));
        appendHistoricalReverseNewSpecLog(
                context, directive, "失败", directive.getResultReason());
    }

    /**
     * 判断指令是否为当前SKU仍待执行的正规换模指令。
     *
     * @param directive 反选指令
     * @param sku 当前SKU
     * @return true-待新增主链执行
     */
    private boolean isPendingHistoricalReverseDirectiveForSku(
            HistoricalReverseSelectionDirective directive,
            SkuScheduleDTO sku) {
        return Objects.nonNull(directive)
                && !directive.isAttempted()
                && StringUtils.equals(MouldChangeTypeEnum.REGULAR.getCode(),
                directive.getActualChangeType())
                && isSameHistoricalReverseSku(directive, sku);
    }

    /**
     * 判断历史指令与当前物料状态是否一致。
     *
     * @param directive 反选指令
     * @param sku 当前SKU
     * @return true-物料和归一化产品状态一致
     */
    private boolean isSameHistoricalReverseSku(
            HistoricalReverseSelectionDirective directive,
            SkuScheduleDTO sku) {
        return Objects.nonNull(directive) && Objects.nonNull(sku)
                && StringUtils.equals(directive.getMaterialCode(), sku.getMaterialCode())
                && StringUtils.equals(normalizeHistoricalReverseProductStatus(
                directive.getProductStatus()),
                normalizeHistoricalReverseProductStatus(sku.getProductStatus()));
    }

    /**
     * 归一化历史反选产品状态。
     *
     * @param productStatus 产品状态
     * @return 空状态按正规S处理
     */
    private String normalizeHistoricalReverseProductStatus(String productStatus) {
        return StringUtils.isEmpty(productStatus)
                ? FORMAL_PRODUCT_STATUS : productStatus;
    }

    /**
     * 输出新增主链中的反选结果日志和过程日志。
     *
     * @param context 排程上下文
     * @param directive 反选指令
     * @param result 结果状态
     * @param reason 结果说明
     */
    private void appendHistoricalReverseNewSpecLog(
            LhScheduleContext context,
            HistoricalReverseSelectionDirective directive,
            String result,
            String reason) {
        String detail = "scheduleTargetDate="
                + LhScheduleTimeUtil.formatDate(context.getScheduleTargetDate())
                + ", historicalShift=" + directive.getHistoricalShiftIndex()
                + ", mappedShift=" + directive.getMappedShiftIndex()
                + ", historicalMachine=" + directive.getMachineCode()
                + ", effectiveMachine=" + directive.getEffectiveMachineCode()
                + ", afterMaterialCode=" + directive.getMaterialCode()
                + ", productStatus=" + directive.getProductStatus()
                + ", result=" + result
                + ", reason=" + reason;
        log.info("前日交替计划指定机台新增排产, {}", detail);
        PriorityTraceLogHelper.appendProcessLog(
                context, "前日交替计划机台反选", detail);
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
            if (LhSingleControlMachineUtil.isWholeMachineGranularitySku(context, sku)) {
                return "试制SKU双模的单控L/R整组与普通机台均无法承接，无法排产";
            }
            return "试制SKU单模只能使用单控机台单边，但当前无可用单控机台或单控机台产能不足，无法排产";
        }
        if (isSpecialMaterialSupportBlocked(context, sku)) {
            return "特殊材料SKU无匹配特殊支持机台，无法排产";
        }
        if (isTargetSkuMouldAllOccupied(context, sku)) {
            return TARGET_SKU_MOULD_ALL_OCCUPIED_UNSCHEDULED_REASON;
        }
        return "无可用硫化机台";
    }

    /**
     * 判断目标SKU的模具是否已全部被当前排程结果占用。
     * <p>候选机台硬过滤会在所有目标SKU模具均被占用时返回空候选，这里只复用相同运行态数据
     * 细化未排原因，不改变新增排产候选筛选和排序规则。</p>
     *
     * @param context 排程上下文
     * @param sku SKU
     * @return true-目标SKU模具全部被占用；false-仍保留原无可用硫化机台原因
     */
    private boolean isTargetSkuMouldAllOccupied(LhScheduleContext context, SkuScheduleDTO sku) {
        if (Objects.isNull(context) || Objects.isNull(sku) || StringUtils.isEmpty(sku.getMaterialCode())) {
            return false;
        }
        List<MdmSkuMouldRel> mouldRelList = context.getSkuMouldRelMap().get(sku.getMaterialCode());
        if (CollectionUtils.isEmpty(mouldRelList)) {
            return false;
        }
        Set<String> skuMouldCodeSet = new LinkedHashSet<String>(mouldRelList.size());
        for (MdmSkuMouldRel mouldRel : mouldRelList) {
            if (Objects.isNull(mouldRel) || StringUtils.isEmpty(mouldRel.getMouldCode())) {
                continue;
            }
            skuMouldCodeSet.add(StringUtils.trim(mouldRel.getMouldCode()));
        }
        if (CollectionUtils.isEmpty(skuMouldCodeSet)) {
            return false;
        }
        Set<String> occupiedMouldCodeSet = collectOccupiedMouldCodes(context);
        return !CollectionUtils.isEmpty(occupiedMouldCodeSet)
                && occupiedMouldCodeSet.containsAll(skuMouldCodeSet);
    }

    /**
     * 汇总当前已排结果占用的模具号。
     *
     * @param context 排程上下文
     * @return 已占用模具号集合
     */
    private Set<String> collectOccupiedMouldCodes(LhScheduleContext context) {
        Set<String> occupiedMouldCodeSet = new HashSet<String>(16);
        if (Objects.isNull(context) || CollectionUtils.isEmpty(context.getMachineAssignmentMap())) {
            return occupiedMouldCodeSet;
        }
        for (List<LhScheduleResult> resultList : context.getMachineAssignmentMap().values()) {
            if (CollectionUtils.isEmpty(resultList)) {
                continue;
            }
            for (LhScheduleResult result : resultList) {
                if (isReleasedFirstDayNoPlanPlaceholderResult(context, result)) {
                    continue;
                }
                if (Objects.isNull(result) || StringUtils.isEmpty(result.getMouldCode())) {
                    continue;
                }
                String[] mouldCodeArray = StringUtils.split(result.getMouldCode(), ",");
                if (Objects.isNull(mouldCodeArray)) {
                    continue;
                }
                for (String mouldCode : mouldCodeArray) {
                    String normalizedMouldCode = StringUtils.trim(mouldCode);
                    if (StringUtils.isNotEmpty(normalizedMouldCode)) {
                        occupiedMouldCodeSet.add(normalizedMouldCode);
                    }
                }
            }
        }
        return occupiedMouldCodeSet;
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
                && LhSingleControlMachineUtil.isSingleSideGranularitySku(context, sku)
                && NewSpecFailReasonEnum.NO_CAPACITY_IN_SCHEDULE_WINDOW == failReason) {
            return "试制SKU单模只能使用单控机台单边，但单控机台已被全局排序更靠前的SKU占用，或当前单控机台产能不足，无法排产";
        }
        if (isTrialConstructionStage(sku)
                && LhSingleControlMachineUtil.isWholeMachineGranularitySku(context, sku)
                && NewSpecFailReasonEnum.NO_CAPACITY_IN_SCHEDULE_WINDOW == failReason) {
            return "试制SKU双模的单控L/R整组与普通机台均无可用产能，无法排产";
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
        return selectCandidateMachine(context, sku, candidateCache, excludedMachineCodes, machineMatch,
                preferredTrialMachine, quantityPolicy, new ArrayList<MachineScheduleDTO>(0));
    }

    /**
     * 选择当前实际尝试的机台，并同步回传同一次选机使用的候选顺序。
     *
     * @param context 排程上下文
     * @param sku 当前待选机SKU
     * @param candidateCache 当前SKU候选缓存
     * @param excludedMachineCodes 已排除机台编码
     * @param machineMatch 机台匹配策略
     * @param preferredTrialMachine 试制、量试或小批量预选机台
     * @param quantityPolicy 排产量策略
     * @param orderedCandidates 本次实际使用的候选顺序输出参数
     * @return 当前实际尝试的机台；无候选时返回null
     */
    private MachineScheduleDTO selectCandidateMachine(LhScheduleContext context,
                                                       SkuScheduleDTO sku,
                                                       NewSpecCandidateCache candidateCache,
                                                       Set<String> excludedMachineCodes,
                                                       IMachineMatchStrategy machineMatch,
                                                       MachineScheduleDTO preferredTrialMachine,
                                                       ProductionQuantityPolicy quantityPolicy,
                                                       List<MachineScheduleDTO> orderedCandidates) {
        List<MachineScheduleDTO> singleControlCandidates = filterExcludedCandidates(
                candidateCache.getSingleControlCandidates(), excludedMachineCodes);
        List<MachineScheduleDTO> normalCandidates = filterExcludedCandidates(
                candidateCache.getNormalCandidates(), excludedMachineCodes);
        logNewSpecMachineTypeSplit(context, sku, singleControlCandidates, normalCandidates,
                excludedMachineCodes, candidateCache);
        if (shouldOnlyUseSingleControlCandidate(context, sku)) {
            MachineScheduleDTO singleControlMachine = selectCandidateMachineFromScopedList(
                    context, sku, singleControlCandidates, machineMatch, preferredTrialMachine, quantityPolicy,
                    candidateCache);
            if (singleControlMachine != null) {
                log.info("新增排产{}SKU仅尝试单控机台, materialCode: {}, machineCode: {}",
                        resolveNewSpecSkuType(sku), sku.getMaterialCode(), singleControlMachine.getMachineCode());
                fillSelectedCandidateOrder(singleControlCandidates, singleControlMachine, orderedCandidates);
                return singleControlMachine;
            }
            log.info("新增排产{}SKU单控候选均已排除，不回落普通机台, materialCode: {}",
                    resolveNewSpecSkuType(sku), sku.getMaterialCode());
            return null;
        }
        if (shouldPreferSingleControlBeforeNormalCandidate(context, sku)
                && !CollectionUtils.isEmpty(singleControlCandidates)) {
            MachineScheduleDTO reusedSingleControlMachine = resolvePreferredSingleControlReuseMachine(
                    context, sku, singleControlCandidates);
            if (reusedSingleControlMachine != null) {
                log.info("新增排产{}SKU优先复用高优先级SKU刚占用的单控机台, materialCode: {}, machineCode: {}",
                        resolveNewSpecSkuType(sku), sku.getMaterialCode(), reusedSingleControlMachine.getMachineCode());
                fillSelectedCandidateOrder(singleControlCandidates, reusedSingleControlMachine, orderedCandidates);
                return reusedSingleControlMachine;
            }
            MachineScheduleDTO singleControlMachine = selectCandidateMachineFromScopedList(
                    context, sku, singleControlCandidates, machineMatch, preferredTrialMachine, quantityPolicy,
                    candidateCache);
            if (singleControlMachine != null) {
                log.info("新增排产{}SKU优先消化单控机台, materialCode: {}, machineCode: {}, remainingSingleControlCount: {}, normalCandidateCount: {}",
                        resolveNewSpecSkuType(sku), sku.getMaterialCode(), singleControlMachine.getMachineCode(),
                        singleControlCandidates.size(), normalCandidates.size());
                fillSelectedCandidateOrder(singleControlCandidates, singleControlMachine, orderedCandidates);
                return singleControlMachine;
            }
            log.info("新增排产{}SKU单控机台均无法承接，开始尝试普通机台, materialCode: {}, normalCandidateCount: {}",
                    resolveNewSpecSkuType(sku), sku.getMaterialCode(), normalCandidates.size());
            MachineScheduleDTO normalMachine = selectCandidateMachineFromScopedList(
                    context, sku, normalCandidates, machineMatch, null, quantityPolicy, candidateCache);
            fillSelectedCandidateOrder(normalCandidates, normalMachine, orderedCandidates);
            return normalMachine;
        }
        MachineScheduleDTO normalMachine = selectCandidateMachineFromScopedList(
                context, sku, normalCandidates, machineMatch, null, quantityPolicy,
                candidateCache);
        if (normalMachine != null) {
            fillSelectedCandidateOrder(normalCandidates, normalMachine, orderedCandidates);
            return normalMachine;
        }
        MachineScheduleDTO singleControlMachine = selectCandidateMachineFromScopedList(
                context, sku, singleControlCandidates, machineMatch, null, quantityPolicy,
                candidateCache);
        fillSelectedCandidateOrder(singleControlCandidates, singleControlMachine, orderedCandidates);
        return singleControlMachine;
    }

    /**
     * 将当前选机逻辑确定的实际首选机台放到候选列表首位。
     * <p>该方法只复制当前有效作用域并移动已选机台，不重新过滤或排序；实际排产和日志都直接读取
     * 该列表第一台，保证日志首选机台与后续实际尝试机台一致。</p>
     *
     * @param scopedCandidates 当前已完成动态过滤的候选作用域
     * @param selectedMachine 当前选机逻辑确定的首选机台
     * @param orderedCandidates 本次实际使用的候选顺序输出参数
     */
    private void fillSelectedCandidateOrder(List<MachineScheduleDTO> scopedCandidates,
                                            MachineScheduleDTO selectedMachine,
                                            List<MachineScheduleDTO> orderedCandidates) {
        orderedCandidates.clear();
        if (Objects.isNull(selectedMachine) || CollectionUtils.isEmpty(scopedCandidates)) {
            return;
        }
        orderedCandidates.add(selectedMachine);
        for (MachineScheduleDTO candidate : scopedCandidates) {
            if (Objects.isNull(candidate)
                    || StringUtils.equals(candidate.getMachineCode(), selectedMachine.getMachineCode())) {
                continue;
            }
            orderedCandidates.add(candidate);
        }
    }

    /**
     * 过滤本轮已经排除的候选机台。
     *
     * @param candidates 候选机台
     * @param excludedMachineCodes 已排除机台编码
     * @return 可继续参与本轮选机的候选机台
     */
    private List<MachineScheduleDTO> filterExcludedCandidates(List<MachineScheduleDTO> candidates,
                                                              Set<String> excludedMachineCodes) {
        if (CollectionUtils.isEmpty(candidates) || CollectionUtils.isEmpty(excludedMachineCodes)) {
            return candidates;
        }
        List<MachineScheduleDTO> filteredCandidates = new ArrayList<>(candidates.size());
        for (MachineScheduleDTO candidate : candidates) {
            if (candidate == null || StringUtils.isEmpty(candidate.getMachineCode())
                    || excludedMachineCodes.contains(candidate.getMachineCode())) {
                continue;
            }
            filteredCandidates.add(candidate);
        }
        return filteredCandidates;
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
        if (Objects.nonNull(context) && Objects.isNull(context.getCurrentScheduleDate())) {
            refreshCurrentScheduleDate(context, sku, null);
        }
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
     * 新增SKU分配模具前刷新上下文当前业务日期。
     * <p>模具资源上下文本身只负责占用和释放，不负责推导排程日期；
     * 因此在策略层进入模具预占前，先把当前候选机台所属业务日写入排程上下文。</p>
     *
     * @param context 排程上下文
     * @param sku 当前新增SKU
     * @param preferredProductionDate dayN扩机台推导出的当前候选生效业务日
     */
    private void refreshCurrentScheduleDate(LhScheduleContext context,
                                                                 SkuScheduleDTO sku,
                                                                 LocalDate preferredProductionDate) {
        Date currentScheduleDate = resolveCurrentScheduleDate(
                context, sku, preferredProductionDate);
        if (Objects.nonNull(context) && Objects.nonNull(currentScheduleDate)) {
            context.setCurrentScheduleDate(currentScheduleDate);
        }
    }

    /**
     * 解析新增SKU模具预占前应写入上下文的当前业务日期。
     *
     * @param context 排程上下文
     * @param sku 当前新增SKU
     * @param preferredProductionDate dayN扩机台推导出的当前候选生效业务日
     * @return 当前业务日期，取不到时返回null
     */
    private Date resolveCurrentScheduleDate(LhScheduleContext context,
                                                              SkuScheduleDTO sku,
                                                              LocalDate preferredProductionDate) {
        if (Objects.nonNull(preferredProductionDate)) {
            return toDate(preferredProductionDate);
        }
        if (Objects.nonNull(sku) && !CollectionUtils.isEmpty(sku.getDailyPlanQuotaMap())) {
            for (LocalDate productionDate : sku.getDailyPlanQuotaMap().keySet()) {
                if (Objects.nonNull(productionDate)) {
                    return toDate(productionDate);
                }
            }
        }
        if (Objects.nonNull(context) && Objects.nonNull(context.getScheduleDate())) {
            return LhScheduleTimeUtil.clearTime(context.getScheduleDate());
        }
        return null;
    }

    /**
     * 将业务日期转换为系统默认时区下的当天零点。
     *
     * @param productionDate 业务日期
     * @return 当天零点日期
     */
    private Date toDate(LocalDate productionDate) {
        if (Objects.isNull(productionDate)) {
            return null;
        }
        return Date.from(productionDate.atStartOfDay(ZoneId.systemDefault()).toInstant());
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
     * 回滚单控整机 L/R 两侧模具预占。
     * <p>正规 SKU 使用单控机台时，主侧和副侧是同一个物理整机排产单元；
     * 任一后续约束失败，都必须同时释放两侧已经预占的模具，避免留下半边资源占用。</p>
     *
     * @param context 排程上下文
     * @param sku 当前SKU
     * @param primaryAllocationResult 主侧模具分配结果
     * @param pairAllocationResult 配对侧模具分配结果
     */
    private void rollbackMouldResourceAllocation(LhScheduleContext context,
                                                 SkuScheduleDTO sku,
                                                 MouldResourceAllocationResult primaryAllocationResult,
                                                 MouldResourceAllocationResult pairAllocationResult) {
        rollbackMouldResourceAllocation(context, sku, pairAllocationResult);
        rollbackMouldResourceAllocation(context, sku, primaryAllocationResult);
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
            if (isContinuationAddMachineCandidate(sku)) {
                // 续作加机台候选已经进入新增统一排序，轮到该SKU时先尝试原续作机台，失败后再回落普通候选。
                log.info("新增排产续作加机台候选优先尝试原续作机台, materialCode: {}, machineCode: {}",
                        sku.getMaterialCode(), preferredContinuousMachine.getMachineCode());
                return preferredContinuousMachine;
            }
            MachineScheduleDTO todayIdleMachine = resolveTodayIdleMachineBeforePreferred(
                    context, sku, scopedCandidates, preferredContinuousMachine);
            if (todayIdleMachine != null) {
                log.info("新增排产当天空闲机台优先覆盖补偿锁回, materialCode: {}, preferredMachine: {}, idleMachine: {}",
                        sku.getMaterialCode(), preferredContinuousMachine.getMachineCode(),
                        todayIdleMachine.getMachineCode());
                return todayIdleMachine;
            }
            log.info("新增排产补偿SKU优先锁回原续作机台, materialCode: {}, machineCode: {}",
                    sku.getMaterialCode(), preferredContinuousMachine.getMachineCode());
            return preferredContinuousMachine;
        }
        if (preferredTrialMachine != null && containsMachine(scopedCandidates, preferredTrialMachine.getMachineCode())) {
            log.info("新增排产优先尝试试制/量试/小批量预选机台, materialCode: {}, machineCode: {}",
                    sku.getMaterialCode(), preferredTrialMachine.getMachineCode());
            return preferredTrialMachine;
        }
        if (quantityPolicy != null && quantityPolicy.isFullRunForNonTailMachine()) {
            MachineScheduleDTO selectedMachine = machineMatch.selectBestMachine(context, sku, scopedCandidates,
                    EMPTY_STRING_SET);
            MachineScheduleDTO todayIdleMachine = resolveTodayIdleMachineBeforePreferred(
                    context, sku, scopedCandidates, selectedMachine);
            if (todayIdleMachine != null) {
                log.info("新增排产当天空闲机台优先覆盖满排候选, materialCode: {}, preferredMachine: {}, idleMachine: {}",
                        sku.getMaterialCode(), selectedMachine.getMachineCode(), todayIdleMachine.getMachineCode());
                return todayIdleMachine;
            }
            return selectedMachine;
        }
        MachineScheduleDTO finishRemainingFirstMachine = resolveCanFinishRemainingQtyFirst(
                context, sku, scopedCandidates, EMPTY_STRING_SET, candidateCache);
        if (finishRemainingFirstMachine != null) {
            MachineScheduleDTO todayIdleMachine = resolveTodayIdleMachineCanFinishRemainingQty(
                    context, sku, scopedCandidates, EMPTY_STRING_SET, candidateCache, finishRemainingFirstMachine);
            if (todayIdleMachine != null) {
                log.info("新增排产当天空闲机台优先覆盖单机收完, materialCode: {}, preferredMachine: {}, idleMachine: {}, remainingQty: {}",
                        sku.getMaterialCode(), finishRemainingFirstMachine.getMachineCode(),
                        todayIdleMachine.getMachineCode(), Math.max(0, sku.getRemainingScheduleQty()));
                return todayIdleMachine;
            }
            log.info("新增排产优先选择可单机收完剩余量的机台, materialCode: {}, machineCode: {}, remainingQty: {}",
                    sku.getMaterialCode(), finishRemainingFirstMachine.getMachineCode(),
                    Math.max(0, sku.getRemainingScheduleQty()));
            return finishRemainingFirstMachine;
        }
        MachineScheduleDTO tailConcentratedMachine = resolveTailConcentratedSplitMachine(
                context, sku, scopedCandidates, EMPTY_STRING_SET, candidateCache);
        if (tailConcentratedMachine != null) {
            MachineScheduleDTO todayIdleMachine = resolveTodayIdleTailConcentratedMachine(
                    context, sku, scopedCandidates, EMPTY_STRING_SET, candidateCache, tailConcentratedMachine);
            if (todayIdleMachine != null) {
                log.info("新增排产当天空闲机台优先覆盖尾量集中, materialCode: {}, preferredMachine: {}, idleMachine: {}, remainingQty: {}",
                        sku.getMaterialCode(), tailConcentratedMachine.getMachineCode(),
                        todayIdleMachine.getMachineCode(), Math.max(0, sku.getRemainingScheduleQty()));
                return todayIdleMachine;
            }
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
        if (isContinuationAddMachineCandidate(sku)) {
            // 续作增机台补偿已经进入新增统一排序，选机必须走普通新增候选排序，不锁回原续作机台。
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
     * 判断当前新增候选是否来源于续作加机台需求。
     *
     * @param sku 当前待排SKU
     * @return true-续作加机台候选，false-其他新增候选
     */
    private boolean isContinuationAddMachineCandidate(SkuScheduleDTO sku) {
        return sku != null && sku.isContinuousCompensationSku()
                && SkuScheduleSourceTypeEnum.isContinuationAddMachine(sku.getSourceType());
    }

    /**
     * 解析可覆盖补偿锁回的当天空闲候选机台。
     *
     * @param context 排程上下文
     * @param sku 当前 SKU
     * @param candidates 作用域候选机台
     * @param preferredMachine 补偿锁回机台
     * @return 当天空闲机台；不存在时返回 null
     */
    private MachineScheduleDTO resolveTodayIdleMachineBeforePreferred(LhScheduleContext context,
                                                                      SkuScheduleDTO sku,
                                                                      List<MachineScheduleDTO> candidates,
                                                                      MachineScheduleDTO preferredMachine) {
        if (preferredMachine == null || isTodayIdleMachine(context, sku, preferredMachine)
                || CollectionUtils.isEmpty(candidates)) {
            return null;
        }
        for (MachineScheduleDTO candidate : candidates) {
            if (candidate != null && isTodayIdleMachine(context, sku, candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * 解析可覆盖单机收完优先的当天空闲候选机台。
     *
     * @param context 排程上下文
     * @param sku 当前 SKU
     * @param candidates 作用域候选机台
     * @param excludedMachineCodes 已排除机台
     * @param candidateCache 候选机台缓存
     * @param selectedMachine 原单机收完机台
     * @return 当天空闲且可单机收完的机台；不存在时返回 null
     */
    private MachineScheduleDTO resolveTodayIdleMachineCanFinishRemainingQty(LhScheduleContext context,
                                                                            SkuScheduleDTO sku,
                                                                            List<MachineScheduleDTO> candidates,
                                                                            Set<String> excludedMachineCodes,
                                                                            NewSpecCandidateCache candidateCache,
                                                                            MachineScheduleDTO selectedMachine) {
        if (selectedMachine == null || isTodayIdleMachine(context, sku, selectedMachine)
                || CollectionUtils.isEmpty(candidates)) {
            return null;
        }
        int remainingQty = resolveCurrentRemainingQty(sku);
        if (remainingQty <= 0) {
            return null;
        }
        for (MachineScheduleDTO candidate : candidates) {
            if (isInvalidScopedCandidate(candidate, excludedMachineCodes)
                    || !isTodayIdleMachine(context, sku, candidate)) {
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
     * 解析可覆盖尾量集中优先的当天空闲候选机台。
     *
     * @param context 排程上下文
     * @param sku 当前 SKU
     * @param candidates 作用域候选机台
     * @param excludedMachineCodes 已排除机台
     * @param candidateCache 候选机台缓存
     * @param selectedMachine 原尾量集中机台
     * @return 当天空闲且满足尾量集中条件的机台；不存在时返回 null
     */
    private MachineScheduleDTO resolveTodayIdleTailConcentratedMachine(LhScheduleContext context,
                                                                       SkuScheduleDTO sku,
                                                                       List<MachineScheduleDTO> candidates,
                                                                       Set<String> excludedMachineCodes,
                                                                       NewSpecCandidateCache candidateCache,
                                                                       MachineScheduleDTO selectedMachine) {
        if (selectedMachine == null || isTodayIdleMachine(context, sku, selectedMachine)
                || CollectionUtils.isEmpty(candidates)) {
            return null;
        }
        int remainingQty = resolveCurrentRemainingQty(sku);
        if (remainingQty <= 0) {
            return null;
        }
        Map<MachineScheduleDTO, Integer> machineCapacityMap = buildPartialCapacityMap(
                context, sku, candidates, excludedMachineCodes, candidateCache, remainingQty);
        if (machineCapacityMap.size() < 2) {
            return null;
        }
        for (Map.Entry<MachineScheduleDTO, Integer> entry : machineCapacityMap.entrySet()) {
            if (!isTodayIdleMachine(context, sku, entry.getKey())) {
                continue;
            }
            if (canKeepTailConcentrated(entry, machineCapacityMap, remainingQty)) {
                return entry.getKey();
            }
        }
        return null;
    }

    /**
     * 构建小于剩余量的候选机台窗口产能 Map。
     *
     * @param context 排程上下文
     * @param sku 当前 SKU
     * @param candidates 候选机台
     * @param excludedMachineCodes 已排除机台
     * @param candidateCache 候选机台缓存
     * @param remainingQty 剩余排产量
     * @return 机台产能 Map
     */
    private Map<MachineScheduleDTO, Integer> buildPartialCapacityMap(LhScheduleContext context,
                                                                     SkuScheduleDTO sku,
                                                                     List<MachineScheduleDTO> candidates,
                                                                     Set<String> excludedMachineCodes,
                                                                     NewSpecCandidateCache candidateCache,
                                                                     int remainingQty) {
        Map<MachineScheduleDTO, Integer> machineCapacityMap = new LinkedHashMap<>(candidates.size());
        for (MachineScheduleDTO candidate : candidates) {
            if (isInvalidScopedCandidate(candidate, excludedMachineCodes)) {
                continue;
            }
            int machineCapacity = resolveCachedMachineAvailableCapacityInWindow(
                    context, sku, candidate, candidateCache);
            if (machineCapacity > 0 && machineCapacity < remainingQty) {
                machineCapacityMap.put(candidate, machineCapacity);
            }
        }
        return machineCapacityMap;
    }

    /**
     * 判断候选机台是否能保留尾量集中能力。
     *
     * @param entry 当前候选机台产能
     * @param machineCapacityMap 机台产能 Map
     * @param remainingQty 剩余排产量
     * @return true-满足尾量集中条件
     */
    private boolean canKeepTailConcentrated(Map.Entry<MachineScheduleDTO, Integer> entry,
                                            Map<MachineScheduleDTO, Integer> machineCapacityMap,
                                            int remainingQty) {
        int tailQty = remainingQty - entry.getValue();
        int otherMaxCapacity = 0;
        for (Map.Entry<MachineScheduleDTO, Integer> otherEntry : machineCapacityMap.entrySet()) {
            if (otherEntry.getKey() == entry.getKey()) {
                continue;
            }
            otherMaxCapacity = Math.max(otherMaxCapacity, otherEntry.getValue());
        }
        return otherMaxCapacity >= tailQty;
    }

    /**
     * 判断候选机台是否无效。
     *
     * @param candidate 候选机台
     * @param excludedMachineCodes 已排除机台
     * @return true-无效
     */
    private boolean isInvalidScopedCandidate(MachineScheduleDTO candidate, Set<String> excludedMachineCodes) {
        return candidate == null
                || StringUtils.isEmpty(candidate.getMachineCode())
                || (!CollectionUtils.isEmpty(excludedMachineCodes)
                && excludedMachineCodes.contains(candidate.getMachineCode()));
    }

    /**
     * 判断候选机台是否为当天空闲且可首班承接。
     *
     * @param context 排程上下文
     * @param sku 当前 SKU
     * @param machine 候选机台
     * @return true-当天空闲
     */
    private boolean isTodayIdleMachine(LhScheduleContext context, SkuScheduleDTO sku, MachineScheduleDTO machine) {
        if (!isTodayIdleMachinePriorityEnabled(context)
                || !isSkuNeedScheduleOnFirstDay(context, sku)
                || context == null || machine == null || StringUtils.isEmpty(machine.getMachineCode())) {
            return false;
        }
        List<LhScheduleResult> assignedResults = CollectionUtils.isEmpty(context.getMachineAssignmentMap())
                ? null : context.getMachineAssignmentMap().get(machine.getMachineCode());
        if (!CollectionUtils.isEmpty(assignedResults)) {
            for (LhScheduleResult assignedResult : assignedResults) {
                if (!isReleasedFirstDayNoPlanPlaceholderResult(context, assignedResult)) {
                    return false;
                }
            }
        }
        Date referenceTime = resolveAlignedCandidateReferenceTime(context, machine);
        if (referenceTime == null || CollectionUtils.isEmpty(context.getScheduleWindowShifts())) {
            return false;
        }
        Date windowStartTime = context.getScheduleWindowShifts().get(0).getShiftStartDateTime();
        return windowStartTime != null && !referenceTime.after(windowStartTime);
    }

    /**
     * 判断续作补偿提前生产的单控候选是否应跳过。
     * <p>补偿 SKU 按既有选机顺序试算到单控候选时，真实开产业务日仍需落在窗口首日；
     * 若落到下一业务日夜班，则不属于提前生产，应继续尝试其他单控候选。</p>
     *
     * @param context 排程上下文
     * @param sku 当前 SKU
     * @param machine 候选机台
     * @param productionStartTime 开产时间
     * @param shifts 排程窗口班次
     * @return true-跳过当前候选
     */
    private boolean shouldSkipCompensationEarlySingleControlCandidate(LhScheduleContext context,
                                                                     SkuScheduleDTO sku,
                                                                     MachineScheduleDTO machine,
                                                                     Date productionStartTime,
                                                                     List<LhShiftConfigVO> shifts) {
        if (!isCompensationEarlyProductionAllowed(context, sku)
                || machine == null
                || !isSingleControlMachine(context, machine.getMachineCode())) {
            return false;
        }
        LocalDate firstWorkDate = resolveFirstShiftDate(context);
        LocalDate productionWorkDate = resolveProductionWorkDate(shifts, productionStartTime);
        return firstWorkDate != null && productionWorkDate != null && !firstWorkDate.equals(productionWorkDate);
    }

    /**
     * 解析候选机台对齐后的待排起点。
     *
     * @param context 排程上下文
     * @param machine 候选机台
     * @return 对齐后的待排起点
     */
    private Date resolveAlignedCandidateReferenceTime(LhScheduleContext context, MachineScheduleDTO machine) {
        Date referenceTime = machine != null ? machine.getEstimatedEndTime() : null;
        if (referenceTime == null && context != null) {
            referenceTime = context.getScheduleDate() != null ? context.getScheduleDate() : context.getScheduleTargetDate();
        }
        if (referenceTime == null || context == null || CollectionUtils.isEmpty(context.getScheduleWindowShifts())) {
            return referenceTime;
        }
        Date windowStartTime = context.getScheduleWindowShifts().get(0).getShiftStartDateTime();
        if (windowStartTime != null && referenceTime.before(windowStartTime)) {
            return windowStartTime;
        }
        return referenceTime;
    }

    /**
     * 判断当天空闲机台优先规则是否启用。
     *
     * @param context 排程上下文
     * @return true-启用
     */
    private boolean isTodayIdleMachinePriorityEnabled(LhScheduleContext context) {
        return context != null && context.getParamIntValue(
                LhScheduleParamConstant.ENABLE_TODAY_IDLE_MACHINE_PRIORITY,
                LhScheduleConstant.ENABLE_TODAY_IDLE_MACHINE_PRIORITY) == 1;
    }

    /**
     * 判断续作补偿SKU是否已通过新增提前生产准入。
     *
     * @param context 排程上下文
     * @param sku 当前 SKU
     * @return true-续作补偿提前生产准入通过
     */
    private boolean isCompensationEarlyProductionAllowed(LhScheduleContext context, SkuScheduleDTO sku) {
        return context != null
                && sku != null
                && sku.isContinuousCompensationSku()
                && Boolean.TRUE.equals(context.getNewSpecEarlyProductionAllowedMap().get(sku));
    }

    /**
     * 判断 SKU 是否需要在窗口首日排产。
     *
     * @param context 排程上下文
     * @param sku 当前 SKU
     * @return true-首日需要排产
     */
    private boolean isSkuNeedScheduleOnFirstDay(LhScheduleContext context, SkuScheduleDTO sku) {
        if (context == null || sku == null) {
            return false;
        }
        LocalDate firstShiftDate = resolveFirstShiftDate(context);
        if (firstShiftDate != null && !CollectionUtils.isEmpty(sku.getDailyPlanQuotaMap())) {
            SkuDailyPlanQuotaDTO quota = sku.getDailyPlanQuotaMap().get(firstShiftDate);
            if (quota != null && (quota.getDayPlanQty() > 0 || quota.getRemainingQty() > 0)) {
                return true;
            }
        }
        if (sku.getDailyPlanQty() > 0) {
            return true;
        }
        if (isCompensationEarlyProductionAllowed(context, sku)) {
            // 续作补偿已通过提前生产准入时，应参与窗口首日选机判断，避免无日计划量被直接顺延。
            return true;
        }
        if (sku.getEffectiveCarryForwardQty() > 0 || sku.getMonthlyHistoryShortageQty() > 0) {
            return true;
        }
        int targetQty = resolveCurrentRemainingQty(sku);
        return targetQty > 0 && StringUtils.equals(SkuTagEnum.ENDING.getCode(), sku.getSkuTag());
    }

    /**
     * 解析当前剩余排产量。
     *
     * @param sku 当前 SKU
     * @return 剩余排产量
     */
    private int resolveCurrentRemainingQty(SkuScheduleDTO sku) {
        if (sku == null) {
            return 0;
        }
        return sku.getRemainingScheduleQty() > 0
                ? sku.getRemainingScheduleQty() : sku.resolveTargetScheduleQty();
    }

    /**
     * 解析排程窗口首班业务日期。
     *
     * @param context 排程上下文
     * @return 首班业务日期
     */
    private LocalDate resolveFirstShiftDate(LhScheduleContext context) {
        if (context == null || CollectionUtils.isEmpty(context.getScheduleWindowShifts())) {
            return null;
        }
        LhShiftConfigVO firstShift = context.getScheduleWindowShifts().get(0);
        if (firstShift == null || firstShift.getWorkDate() == null) {
            return null;
        }
        return firstShift.getWorkDate().toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
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
     * 解析冻结为双模的 SKU 单控整机配对侧机台。
     * <p>模式只读取 S4.3 快照，与试制、量试、正规或小批量分类无关。</p>
     *
     * @param context 排程上下文
     * @param sku 当前SKU
     * @param machine 当前候选机台
     * @return 配对侧机台；非整机粒度或配对侧不存在时返回 null
     */
    private MachineScheduleDTO resolveWholeSingleControlPairMachine(LhScheduleContext context,
                                                                    SkuScheduleDTO sku,
                                                                    MachineScheduleDTO machine) {
        if (!LhSingleControlMachineUtil.isWholeMachineGranularitySku(context, sku)
                || Objects.isNull(machine)
                || !isSingleControlMachine(context, machine.getMachineCode())) {
            return null;
        }
        MachineScheduleDTO pairMachine = LhSingleControlMachineUtil.resolvePairMachine(context, machine.getMachineCode());
        if (Objects.isNull(pairMachine)) {
            log.warn("双模SKU单控整机配对侧缺失，当前候选不能单边排产, materialCode: {}, machineCode: {}",
                    sku.getMaterialCode(), machine.getMachineCode());
        }
        return pairMachine;
    }

    /**
     * 判断当前SKU是否应仅尝试单控候选机台。
     * <p>只有冻结为单模的试制SKU禁止普通机台；冻结为双模的试制SKU必须先尝试单控L/R整组，
     * 整组均无法承接后允许进入普通机台候选组。快照缺失时保持原有从严行为，避免误落普通机台。</p>
     *
     * @param context 排程上下文
     * @param sku SKU
     * @return true-仅尝试单控候选
     */
    private boolean shouldOnlyUseSingleControlCandidate(LhScheduleContext context, SkuScheduleDTO sku) {
        if (sku == null) {
            return false;
        }
        if (isTrialConstructionStage(sku)) {
            return !LhSingleControlMachineUtil.isWholeMachineGranularitySku(context, sku);
        }
        return false;
    }

    /**
     * 判断当前SKU是否必须先尝试完单控候选，再进入普通机台候选组。
     * <p>复用量试、小批量已有的两阶段选机链，并将冻结为双模的试制SKU纳入该链路，
     * 防止单控整组与普通机台混合后被局部搜索、单机收完等通用规则提前选中普通机台。</p>
     *
     * @param context 排程上下文
     * @param sku 当前待排SKU
     * @return true-单控候选组优先，全部失败后才允许普通机台
     */
    private boolean shouldPreferSingleControlBeforeNormalCandidate(LhScheduleContext context, SkuScheduleDTO sku) {
        return isMassTrialOrSmallBatchSku(sku)
                || (isTrialConstructionStage(sku)
                && LhSingleControlMachineUtil.isWholeMachineGranularitySku(context, sku));
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
        log.info("新增SKU候选快照, materialCode: {}, skuType: {}, surplusQty: {}, remainingQty: {}, smallBatchTotalQtyThreshold: {}, isSmallBatch: {}, "
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
        return LhScheduleConstant.SMALL_BATCH_SKU_THRESHOLD;
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
     * 从候选机台列表中查找指定编码的机台。
     *
     * @param candidates 候选机台列表
     * @param machineCode 机台编码
     * @return 匹配的机台;不存在时返回null
     */
    private MachineScheduleDTO findMachineInList(List<MachineScheduleDTO> candidates, String machineCode) {
        if (CollectionUtils.isEmpty(candidates) || StringUtils.isEmpty(machineCode)) {
            return null;
        }
        for (MachineScheduleDTO candidate : candidates) {
            if (candidate != null && StringUtils.equals(machineCode, candidate.getMachineCode())) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * 单控机台反向匹配:单边粒度SKU排上一侧后,为配对侧查找可排SKU。
     * <p>当试制、量试、小批量SKU排上单控机台一侧(K1501L)后,尝试为配对侧(K1501R)
     * 从待排SKU列表中反向查找可排的试制、量试、小批量SKU。匹配按以下优先级排序:
     * 1. SKU类型优先级:试制 > 量试 > 小批量;
     * 2. 同一类型内按规格匹配:同规格 > 同断面宽 > 同英寸;
     * 3. 断面宽从规格中解析,复用ProductSpecificationsUtils。
     * 如果没有合适SKU,返回null,配对侧允许空闲,不强制排产。</p>
     *
     * @param context 排程上下文
     * @param currentSku 当前已排上单控一侧的SKU
     * @param currentMachineCode 当前已排机台编码
     * @param iterator 新增SKU列表迭代器,用于前移匹配到的SKU
     * @return 配对侧机台编码;无匹配时返回null
     */
    private void tryReverseMatchPairSingleControlSku(LhScheduleContext context,
                                                    SkuScheduleDTO currentSku,
                                                    String currentMachineCode,
                                                    IMachineMatchStrategy machineMatch,
                                                    Map<String, String> reverseMatchPreferredMachineMap,
                                                    Set<String> reverseMatchReservedMachineCodes) {
        if (context == null || currentSku == null || StringUtils.isEmpty(currentMachineCode)
                || machineMatch == null || reverseMatchPreferredMachineMap == null
                || reverseMatchReservedMachineCodes == null) {
            return;
        }
        // 只有本次排程已冻结为单模的SKU才触发反向匹配，与SKU类型和小批量阈值无关。
        if (!LhSingleControlMachineUtil.isSingleSideGranularitySku(context, currentSku)) {
            return;
        }
        // 解析配对侧机台编码
        String pairMachineCode = LhSingleControlMachineUtil.resolvePairMachineCode(currentMachineCode);
        if (StringUtils.isEmpty(pairMachineCode)) {
            return;
        }
        // 检查配对侧是否空闲(没有被排产结果占用)
        if (!isSingleControlPairSideAvailable(context, pairMachineCode)) {
            return;
        }
        // 从待排SKU列表中查找冻结为单模且配对侧通过全部硬约束的SKU。
        SkuScheduleDTO matchedSku = findReverseMatchSku(
                context, currentSku, pairMachineCode, machineMatch);
        if (matchedSku == null) {
            log.info("单控反向匹配未找到合适SKU,配对侧允许空闲, currentMachine: {}, pairMachine: {}, materialCode: {}",
                    currentMachineCode, pairMachineCode, currentSku.getMaterialCode());
            return;
        }
        // 记录推荐映射:目标SKU物料编码 -> 配对侧机台编码,使该SKU在后续选机时优先选择配对侧
        reverseMatchPreferredMachineMap.put(
                LhSingleControlMachineUtil.buildSkuModeKey(matchedSku), pairMachineCode);
        // 预留配对侧机台:非推荐目标SKU选机时排除该机台,使配对侧留给推荐目标SKU
        reverseMatchReservedMachineCodes.add(pairMachineCode);
        log.info("单控反向匹配成功, currentMachine: {}, pairMachine: {}, currentMaterial: {}, matchedMaterial: {}",
                currentMachineCode, pairMachineCode, currentSku.getMaterialCode(), matchedSku.getMaterialCode());
    }

    /**
     * 判断单控配对侧机台是否空闲(没有有效排产结果占用)。
     *
     * @param context 排程上下文
     * @param pairMachineCode 配对侧机台编码
     * @return true-空闲可用
     */
    private boolean isSingleControlPairSideAvailable(LhScheduleContext context, String pairMachineCode) {
        if (context == null || StringUtils.isEmpty(pairMachineCode)) {
            return false;
        }
        List<LhScheduleResult> assignedResults = context.getMachineAssignmentMap().get(pairMachineCode);
        if (CollectionUtils.isEmpty(assignedResults)) {
            return true;
        }
        for (LhScheduleResult result : assignedResults) {
            if (result != null && result.getDailyPlanQty() != null && result.getDailyPlanQty() > 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * 从待排SKU列表中查找可反向匹配的冻结单模SKU。
     * <p>候选范围不再按试制、量试、小批量或100条阈值限制。
     * 先按规格匹配层级排序(同规格>同断面宽>同英寸),层级相同再按SKU类型优先级排序(试制>量试>小批量)。
     * 规格匹配为分层过滤条件,不满足任何规格匹配层级的候选不参与反向匹配,配对侧允许空闲。</p>
     *
     * @param context 排程上下文
     * @param currentSku 当前已排SKU
     * @param pairMachineCode 配对侧机台编码
     * @return 最佳匹配SKU;无规格匹配时返回null
     */
    private SkuScheduleDTO findReverseMatchSku(LhScheduleContext context,
                                               SkuScheduleDTO currentSku,
                                               String pairMachineCode,
                                               IMachineMatchStrategy machineMatch) {
        if (context == null || currentSku == null || CollectionUtils.isEmpty(context.getNewSpecSkuList())) {
            return null;
        }
        List<SkuScheduleDTO> candidates = new ArrayList<SkuScheduleDTO>(8);
        for (SkuScheduleDTO sku : context.getNewSpecSkuList()) {
            if (sku == null || sku == currentSku) {
                continue;
            }
            // 反向候选只读取本次排程冻结模式，不再读取SKU类型。
            if (!LhSingleControlMachineUtil.isSingleSideGranularitySku(context, sku)) {
                continue;
            }
            // 必须仍有本轮可排量，并且指定配对侧通过与正式选机一致的硬约束。
            if (resolveSchedulableRemainingQty(context, sku) <= 0
                    || !machineMatch.isEligibleSingleControlSide(context, sku, pairMachineCode)) {
                continue;
            }
            candidates.add(sku);
        }
        if (CollectionUtils.isEmpty(candidates)) {
            return null;
        }
        // 先按规格匹配层级排序(同规格>同断面宽>同英寸>无匹配),层级相同再按SKU类型优先级排序(试制>量试>小批量)
        candidates.sort((left, right) -> {
            int result = resolveReverseMatchSpecLevel(context, currentSku, left)
                    - resolveReverseMatchSpecLevel(context, currentSku, right);
            if (result != 0) {
                return result;
            }
            return compareReverseMatchSkuTypePriority(left, right);
        });
        // 排序后第一个候选为最优匹配;若最优候选规格匹配层级为"无匹配",则配对侧允许空闲
        SkuScheduleDTO bestCandidate = candidates.get(0);
        if (resolveReverseMatchSpecLevel(context, currentSku, bestCandidate) >= REVERSE_MATCH_SPEC_LEVEL_NONE) {
            return null;
        }
        return bestCandidate;
    }

    /**
     * 比较反向匹配SKU类型优先级:试制 > 量试 > 小批量。
     *
     * @param left 左侧SKU
     * @param right 右侧SKU
     * @return 比较结果
     */
    private int compareReverseMatchSkuTypePriority(SkuScheduleDTO left, SkuScheduleDTO right) {
        return resolveReverseMatchSkuTypeScore(left) - resolveReverseMatchSkuTypeScore(right);
    }

    /**
     * 解析反向匹配SKU类型得分:试制0、量试1、小批量2,得分越低优先级越高。
     *
     * @param sku SKU
     * @return 类型得分
     */
    private int resolveReverseMatchSkuTypeScore(SkuScheduleDTO sku) {
        if (sku == null) {
            return Integer.MAX_VALUE;
        }
        // 反向匹配SKU类型得分:试制0(最高优先)、量试1、小批量2,得分越低优先级越高
        if (isTrialConstructionStage(sku)) {
            return 0;
        }
        if (isMassTrialSku(sku)) {
            return 1;
        }
        return 2;
    }

    /**
     * 解析候选SKU与当前SKU的规格匹配层级。
     * <p>规格匹配作为分层过滤条件,层级越低优先级越高:
     * 同规格(0) > 同断面宽(1) > 同英寸(2) > 无匹配(3)。
     * 断面宽从规格中解析,复用ProductSpecificationsUtils。</p>
     *
     * @param context 排程上下文
     * @param currentSku 当前已排SKU
     * @param candidate 候选SKU
     * @return 规格匹配层级;0-同规格,1-同断面宽,2-同英寸,3-无匹配
     */
    private int resolveReverseMatchSpecLevel(LhScheduleContext context,
                                             SkuScheduleDTO currentSku,
                                             SkuScheduleDTO candidate) {
        String currentSpecCode = StringUtils.defaultString(currentSku.getSpecCode());
        String currentProSize = resolveSkuProSize(context, currentSku);
        // 同规格
        if (StringUtils.equals(currentSpecCode, StringUtils.defaultString(candidate.getSpecCode()))) {
            return REVERSE_MATCH_SPEC_LEVEL_SAME_SPEC;
        }
        // 同断面宽,断面宽从规格中解析,复用ProductSpecificationsUtils
        String currentSectionWidth = resolveSectionWidthFromSpec(currentProSize);
        String candidateSectionWidth = resolveSectionWidthFromSpec(resolveSkuProSize(context, candidate));
        if (StringUtils.isNotEmpty(currentSectionWidth)
                && StringUtils.equals(currentSectionWidth, candidateSectionWidth)) {
            return REVERSE_MATCH_SPEC_LEVEL_SAME_WIDTH;
        }
        // 同英寸
        BigDecimal currentInch = LhMachineHardMatchUtil.parseInch(currentProSize);
        BigDecimal candidateInch = LhMachineHardMatchUtil.parseInch(resolveSkuProSize(context, candidate));
        if (currentInch != null && candidateInch != null && currentInch.compareTo(candidateInch) == 0) {
            return REVERSE_MATCH_SPEC_LEVEL_SAME_INCH;
        }
        // 无匹配
        return REVERSE_MATCH_SPEC_LEVEL_NONE;
    }

    /**
     * 解析SKU的规格尺寸字符串。
     *
     * @param context 排程上下文
     * @param sku SKU
     * @return 规格尺寸字符串
     */
    private String resolveSkuProSize(LhScheduleContext context, SkuScheduleDTO sku) {
        if (sku == null) {
            return null;
        }
        if (StringUtils.isNotEmpty(sku.getProSize())) {
            return sku.getProSize();
        }
        if (context != null && context.getMaterialInfoMap() != null && sku.getMaterialCode() != null) {
            MdmMaterialInfo materialInfo = context.getMaterialInfoMap().get(sku.getMaterialCode());
            if (materialInfo != null) {
                return materialInfo.getProSize();
            }
        }
        return null;
    }

    /**
     * 从规格字符串中解析断面宽。
     * <p>复用ProductSpecificationsUtils.parseSectionWidthAndAspectRatio,
     * 返回List第一个元素为断面宽(毫米),解析失败时返回null。</p>
     *
     * @param proSize 规格字符串
     * @return 断面宽字符串;解析失败时返回null
     */
    private String resolveSectionWidthFromSpec(String proSize) {
        if (StringUtils.isEmpty(proSize)) {
            return null;
        }
        List<Integer> parsed = ProductSpecificationsUtils.parseSectionWidthAndAspectRatio(proSize);
        if (parsed == null || parsed.isEmpty()) {
            return null;
        }
        return String.valueOf(parsed.get(0));
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
        // 计算班次上限时同步清洗专用规则，避免3天内可收尾SKU仍扣干冰/喷砂清洗产能。
        List<MachineCleaningWindowDTO> cleaningWindowList = resolveEffectiveCleaningWindowList(
                context, machine.getMachineCode(), sku, mouldChangeStartTime, firstProductionStartTime);
        List<MachineMaintenanceWindowDTO> maintenanceWindowList = resolveMachineMaintenanceWindowList(
                context, machine.getMachineCode());
        Date cursorStartTime = firstProductionStartTime;
        int dryIceLossQty = context.getParamIntValue(
                LhScheduleParamConstant.DRY_ICE_LOSS_QTY, LhScheduleConstant.DRY_ICE_LOSS_QTY);
        int dryIceDurationHours = context.getParamIntValue(
                LhScheduleParamConstant.DRY_ICE_DURATION_HOURS, LhScheduleConstant.DRY_ICE_DURATION_HOURS);
        int plannedRepairFixedQty = context.getParamIntValue(
                LhScheduleParamConstant.PLANNED_REPAIR_FIXED_QTY, LhScheduleConstant.PLANNED_REPAIR_FIXED_QTY);
        String configPlusShiftType = ShiftCapacityResolverUtil.resolveOddShiftCapacityPlusShiftType(context);
        String remainShiftType = ShiftCapacityResolverUtil.resolveDailyStandardCapacityRemainShiftType(context);
        int remainShiftCapacityUpperLimit =
                ShiftCapacityResolverUtil.resolveDailyStandardRemainShiftCapacityUpperLimit(
                        context, sku.getMaterialCode(), shiftCapacity);
        boolean singleControlMachine = isSingleControlMachine(context, machine.getMachineCode());
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
            // 日标准量高于“班产×3”时，仅剩余班次使用独立理论上限计算真实可排量。
            int currentShiftCapacity = !singleControlMachine
                    && ShiftCapacityResolverUtil.isDailyStandardRemainShift(shift, remainShiftType)
                    ? remainShiftCapacityUpperLimit : shiftCapacity;
            int actualShiftPlanQty = ShiftCapacityResolverUtil.resolveActualShiftPlanQty(
                    currentShiftCapacity, shift, configPlusShiftType, ScheduleTypeEnum.NEW_SPEC.getCode());
            boolean oddShiftAdjustEnabled = ShiftCapacityResolverUtil.isOddShiftCapacityAdjustEnabled(
                    currentShiftCapacity, shift, configPlusShiftType, ScheduleTypeEnum.NEW_SPEC.getCode());
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
                    currentShiftCapacity,
                    sku.getLhTimeSeconds(),
                    mouldQty,
                    ShiftCapacityResolverUtil.resolveShiftDurationSeconds(shift),
                    dryIceLossQty,
                    dryIceDurationHours,
                    shift,
                    configPlusShiftType,
                    ScheduleTypeEnum.NEW_SPEC.getCode(),
                    plannedRepairFixedQty);
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
     * 按SKU日标准产量修正新增排程班次计划量。
     *
     * @param context 排程上下文
     * @param sku SKU
     * @param machineCode 机台编码
     * @param shifts 班次列表
     * @param shiftCapacityMap 原班次计划量
     * @param runtimeShiftCapacity 运行态班产
     * @return 修正后的班次计划量
     */
    private Map<Integer, Integer> applyDailyStandardCapacityAdjust(LhScheduleContext context,
                                                                   SkuScheduleDTO sku,
                                                                   String machineCode,
                                                                   List<LhShiftConfigVO> shifts,
                                                                   Map<Integer, Integer> shiftCapacityMap,
                                                                   int runtimeShiftCapacity) {
        String remainShiftType = ShiftCapacityResolverUtil.resolveDailyStandardCapacityRemainShiftType(context);
        boolean singleControlMachine = isSingleControlMachine(context, machineCode);
        int dailyStandardQty = ShiftCapacityResolverUtil.resolveDailyStandardQty(context, sku.getMaterialCode());
        int remainShiftCapacityUpperLimit =
                ShiftCapacityResolverUtil.resolveDailyStandardRemainShiftCapacityUpperLimit(
                        context, sku.getMaterialCode(), runtimeShiftCapacity);
        Map<Integer, Integer> adjustedMap = ShiftCapacityResolverUtil.adjustShiftPlanQtyMapByDailyStandard(
                shifts, shiftCapacityMap, shiftCapacityMap, dailyStandardQty, runtimeShiftCapacity,
                remainShiftCapacityUpperLimit,
                remainShiftType, singleControlMachine, ScheduleTypeEnum.NEW_SPEC.getCode());
        if (!Objects.equals(shiftCapacityMap, adjustedMap)) {
            log.info("日标准产量班次计划量修正, 当前流程: 新增排程, materialCode: {}, machineCode: {}, "
                            + "是否单控机台: {}, SKU日标准产量: {}, 班产: {}, 剩余班次理论上限: {}, "
                            + "日标准产量剩余班次参数值: {}, "
                            + "修正前班次计划量: {}, 修正后班次计划量: {}",
                    sku.getMaterialCode(), machineCode, singleControlMachine, dailyStandardQty,
                    runtimeShiftCapacity, remainShiftCapacityUpperLimit, remainShiftType,
                    shiftCapacityMap, adjustedMap);
        }
        return adjustedMap;
    }

    /**
     * 对补满后的新增排程结果再次应用日标准产量规则，避免后置补量突破已修正班次上限。
     *
     * @param context 排程上下文
     * @param sku SKU
     * @param result 排程结果
     * @param shifts 班次列表
     * @param runtimeShiftCapacity 运行态班产
     */
    private void applyDailyStandardPlanQtyToResult(LhScheduleContext context,
                                                   SkuScheduleDTO sku,
                                                   LhScheduleResult result,
                                                   List<LhShiftConfigVO> shifts,
                                                   int runtimeShiftCapacity) {
        if (Objects.isNull(context) || Objects.isNull(sku) || Objects.isNull(result)
                || CollectionUtils.isEmpty(shifts) || runtimeShiftCapacity <= 0) {
            return;
        }
        Map<Integer, Integer> rawPlanQtyMap = new LinkedHashMap<Integer, Integer>(shifts.size());
        for (LhShiftConfigVO shift : shifts) {
            Integer planQty = ShiftFieldUtil.getShiftPlanQty(result, shift.getShiftIndex());
            rawPlanQtyMap.put(shift.getShiftIndex(), Objects.isNull(planQty) ? 0 : Math.max(0, planQty));
        }
        Map<Integer, Integer> adjustedPlanQtyMap = applyDailyStandardCapacityAdjust(
                context, sku, result.getLhMachineCode(), shifts, rawPlanQtyMap, runtimeShiftCapacity);
        if (Objects.equals(rawPlanQtyMap, adjustedPlanQtyMap)) {
            return;
        }
        int lhTimeSeconds = Objects.isNull(result.getLhTime()) ? 0 : Math.max(0, result.getLhTime());
        int mouldQty = ShiftCapacityResolverUtil.resolveMachineMouldQty(
                Objects.isNull(result.getMouldQty()) ? 0 : result.getMouldQty());
        // 结果后置修正也必须复用同一清洗过滤口径，避免日标准产量回裁时重新扣除已跳过的清洗。
        List<MachineCleaningWindowDTO> cleaningWindowList = resolveEffectiveCleaningWindowList(
                context, result, resolveFirstPlannedShiftStartTime(result));
        List<MachineMaintenanceWindowDTO> maintenanceWindowList = resolveMachineMaintenanceWindowList(
                context, result.getLhMachineCode());
        for (LhShiftConfigVO shift : shifts) {
            int shiftIndex = shift.getShiftIndex();
            int beforeQty = rawPlanQtyMap.get(shiftIndex);
            Integer adjustedQty = adjustedPlanQtyMap.get(shiftIndex);
            int afterQty = Objects.isNull(adjustedQty) ? beforeQty : Math.max(0, adjustedQty);
            if (beforeQty == afterQty) {
                continue;
            }
            Date startTime = ShiftFieldUtil.getShiftStartTime(result, shiftIndex);
            if (Objects.isNull(startTime)) {
                startTime = shift.getShiftStartDateTime();
            }
            Date endTime = null;
            if (afterQty > 0 && lhTimeSeconds > 0) {
                long secondsNeeded = (long) Math.ceil((double) afterQty / mouldQty) * lhTimeSeconds;
                endTime = ShiftCapacityResolverUtil.resolveCompletionTimeWithDowntimes(
                        context.getDevicePlanShutList(), cleaningWindowList, maintenanceWindowList,
                        result.getLhMachineCode(), startTime, secondsNeeded);
            }
            ShiftFieldUtil.setShiftPlanQty(result, shiftIndex, afterQty,
                    afterQty > 0 ? startTime : null, endTime);
        }
        refreshResultSummary(context, result);
        log.info("日标准产量结果计划量收敛, 当前流程: 新增排程, materialCode: {}, machineCode: {}, "
                        + "修正前班次计划量: {}, 修正后班次计划量: {}",
                sku.getMaterialCode(), result.getLhMachineCode(), rawPlanQtyMap, adjustedPlanQtyMap);
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
     * 解析新增排产正式/量试非收尾场景的业务目标量。
     * <p>dayN 只参与排产节奏和增机台判断，不再作为非收尾 SKU 的实际排产硬目标。</p>
     *
     * @param context 排程上下文
     * @param sku SKU
     * @param policy 排产数量策略
     * @return 业务目标量
     */
    private int resolveFormalNonEndingMinimumTargetQty(LhScheduleContext context,
                                                       SkuScheduleDTO sku,
                                                       ProductionQuantityPolicy policy) {
        if (sku == null) {
            return 0;
        }
        int businessTargetQty = Math.max(0, sku.resolveTargetScheduleQty());
        if (shouldUseFormalNonEndingMinimumTarget(context, sku, policy)) {
            log.info("新增SKU正式非收尾目标量按业务目标保留, materialCode: {}, businessTargetQty: {}, "
                            + "windowRemainingPlanQty: {}, windowPlanQty: {}, dailyPlanRemainingQty: {}",
                    sku.getMaterialCode(), businessTargetQty, sku.getWindowRemainingPlanQty(),
                    sku.getWindowPlanQty(), SkuDailyPlanQuotaUtil.sumRemainingQty(sku.getDailyPlanQuotaMap()));
        }
        return businessTargetQty;
    }

    /**
     * 判断当前是否使用新增排产正式/量试非收尾业务目标量口径。
     *
     * @param context 排程上下文
     * @param sku SKU
     * @param policy 排产数量策略
     * @return true-使用业务目标量并保留 dayN 增机判断
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
                                                       int maxQtyToWindowEnd,
                                                       EarlyProductionDecision earlyProductionDecision) {
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
        if (isAllowedFuturePlanEarlyProduction(earlyProductionDecision)) {
            /*
             * 后续日计划 SKU 已通过提前生产准入并完成新增换模上机后，
             * 当前机台应保留到窗口结束，避免只按被提前借用的 dayN 小计划截断 C6~C8。
             */
            log.info("提前生产新增换模保留机台到窗口结束, materialCode: {}, machineCode: {}, "
                            + "futurePlanDate: {}, targetQty: {}, maxQtyToWindowEnd: {}",
                    sku.getMaterialCode(), candidateMachine == null ? null : candidateMachine.getMachineCode(),
                    earlyProductionDecision.getFuturePlanDate(), candidateTargetQty, maxQtyToWindowEnd);
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

    private boolean isAllowedFuturePlanEarlyProduction(EarlyProductionDecision earlyProductionDecision) {
        return earlyProductionDecision != null
                && earlyProductionDecision.isEarlyProduction()
                && earlyProductionDecision.isAllowed()
                && earlyProductionDecision.getFuturePlanDate() != null;
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
     * 按 dayN 增机生效日或普通首台日计划规则对齐换模就绪时间。
     * <p>续作增机补偿的首台候选也已有明确的增机日，必须优先按该日期对齐，
     * 不得再进入“剩余日计划额度为 0 则顺延”的普通首台路径。</p>
     *
     * @param context 排程上下文
     * @param sku 当前 SKU
     * @param switchReadyTime 资源约束计算后的换模就绪时间
     * @param shifts 排程窗口班次
     * @param totalScheduledQty 当前 SKU 累计已排量
     * @param addMachineProductionDate dayN 模拟确定的当前增机生效日
     * @param isEnding 是否收尾
     * @return 对齐后的换模就绪时间
     */
    private Date alignSwitchReadyTimeByAddMachineDate(LhScheduleContext context,
                                                       SkuScheduleDTO sku,
                                                       Date switchReadyTime,
                                                       List<LhShiftConfigVO> shifts,
                                                       int totalScheduledQty,
                                                       LocalDate addMachineProductionDate,
                                                       boolean isEnding) {
        if (Objects.nonNull(addMachineProductionDate)) {
            Date alignedSwitchReadyTime = alignAddedMachineSwitchReadyTime(
                    sku, switchReadyTime, shifts, totalScheduledQty, addMachineProductionDate);
            log.info("新增SKU按dayN增机生效日对齐换模, materialCode: {}, totalScheduledQty: {}, "
                            + "addMachineProductionDate: {}, beforeSwitchReadyTime: {}, afterSwitchReadyTime: {}",
                    Objects.isNull(sku) ? null : sku.getMaterialCode(), totalScheduledQty,
                    addMachineProductionDate, LhScheduleTimeUtil.formatDateTime(switchReadyTime),
                    LhScheduleTimeUtil.formatDateTime(alignedSwitchReadyTime));
            return alignedSwitchReadyTime;
        }
        if (totalScheduledQty <= 0) {
            return alignFirstMachineSwitchReadyTimeByDailyPlan(
                    context, sku, switchReadyTime, shifts, isEnding);
        }
        return switchReadyTime;
    }

    /**
     * 按 dayN 增机生效日或普通首台日计划规则对齐开产时间。
     * <p>实际资源约束仍可将开产推迟到增机日之后，但已消费的日计划剩余额度不再二次推迟补偿机台。</p>
     *
     * @param context 排程上下文
     * @param sku 当前 SKU
     * @param productionStartTime 资源约束计算后的开产时间
     * @param shifts 排程窗口班次
     * @param totalScheduledQty 当前 SKU 累计已排量
     * @param addMachineProductionDate dayN 模拟确定的当前增机生效日
     * @param isEnding 是否收尾
     * @param earlyProductionDecision 提前生产判定结果
     * @return 对齐后的开产时间
     */
    private Date alignProductionStartTimeByAddMachineDate(LhScheduleContext context,
                                                           SkuScheduleDTO sku,
                                                           Date productionStartTime,
                                                           List<LhShiftConfigVO> shifts,
                                                           int totalScheduledQty,
                                                           LocalDate addMachineProductionDate,
                                                           boolean isEnding,
                                                           EarlyProductionDecision earlyProductionDecision) {
        if (Objects.nonNull(addMachineProductionDate)) {
            Date alignedProductionStartTime = alignAddedMachineProductionStartTime(
                    sku, productionStartTime, shifts, totalScheduledQty, addMachineProductionDate);
            log.info("新增SKU按dayN增机生效日对齐开产, materialCode: {}, totalScheduledQty: {}, "
                            + "addMachineProductionDate: {}, beforeProductionStartTime: {}, afterProductionStartTime: {}",
                    Objects.isNull(sku) ? null : sku.getMaterialCode(), totalScheduledQty,
                    addMachineProductionDate, LhScheduleTimeUtil.formatDateTime(productionStartTime),
                    LhScheduleTimeUtil.formatDateTime(alignedProductionStartTime));
            return alignedProductionStartTime;
        }
        return alignFirstProductionStartTimeByDailyPlan(
                context, sku, productionStartTime, shifts, isEnding, earlyProductionDecision);
    }

    /**
     * 新增非收尾首日无可用日计划额度时，将首个可排时间推进到首个可承接的生产日。
     *
     * @param context 排程上下文
     * @param sku SKU
     * @param firstProductionStartTime 当前首个可排时间
     * @param shifts 排程窗口班次
     * @param isEnding 是否收尾
     * @param earlyProductionDecision 提前生产判定结果
     * @return 调整后的首个可排时间
     */
    private Date alignFirstProductionStartTimeByDailyPlan(LhScheduleContext context,
                                                          SkuScheduleDTO sku,
                                                          Date firstProductionStartTime,
                                                          List<LhShiftConfigVO> shifts,
                                                          boolean isEnding,
                                                          EarlyProductionDecision earlyProductionDecision) {
        if (Objects.isNull(sku) || Objects.isNull(firstProductionStartTime)
                || isEnding || CollectionUtils.isEmpty(sku.getDailyPlanQuotaMap())) {
            return firstProductionStartTime;
        }
        LocalDate productionDate = resolveProductionWorkDate(shifts, firstProductionStartTime);
        if (Objects.isNull(productionDate)) {
            return firstProductionStartTime;
        }
        SkuDailyPlanQuotaDTO currentQuota = sku.getDailyPlanQuotaMap().get(productionDate);
        if (hasSchedulableDailyPlanQuota(sku, currentQuota)) {
            return firstProductionStartTime;
        }
        LocalDate nextPlanDate = resolveNextPositiveDailyPlanDate(
                sku, sku.getDailyPlanQuotaMap(), productionDate, resolveScheduleTargetLocalDate(context));
        if (Objects.isNull(nextPlanDate)) {
            return firstProductionStartTime;
        }
        // 提前生产准入优先于“首日无 dayN 顺延”，续作补偿复用同一判定结果。
        if (isAllowedFuturePlanEarlyProduction(earlyProductionDecision)) {
            log.info("新增SKU提前生产准入通过，保留当前业务日开产, materialCode: {}, "
                            + "fromProductionDate: {}, futurePlanDate: {}, firstProductionStartTime: {}",
                    sku.getMaterialCode(), productionDate, nextPlanDate,
                    LhScheduleTimeUtil.formatDateTime(firstProductionStartTime));
            return firstProductionStartTime;
        }
        if (sku.isContinuousCompensationSku()
                && !shouldDelayFirstProductionForNoPlanDate(sku, firstProductionStartTime, isEnding)) {
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

    /**
     * 首台机台首日无可用日计划额度时，将换模就绪时间推进到首个可承接生产日内的允许换模班次。
     *
     * @param context 排程上下文
     * @param sku SKU
     * @param switchReadyTime 当前换模就绪时间
     * @param shifts 排程窗口班次
     * @param isEnding 是否收尾
     * @return 调整后的换模就绪时间
     */
    private Date alignFirstMachineSwitchReadyTimeByDailyPlan(LhScheduleContext context,
                                                             SkuScheduleDTO sku,
                                                             Date switchReadyTime,
                                                             List<LhShiftConfigVO> shifts,
                                                             boolean isEnding) {
        if (Objects.isNull(sku) || Objects.isNull(switchReadyTime)
                || isEnding || CollectionUtils.isEmpty(sku.getDailyPlanQuotaMap())) {
            return switchReadyTime;
        }
        LocalDate productionDate = resolveProductionWorkDate(shifts, switchReadyTime);
        if (Objects.isNull(productionDate)) {
            return switchReadyTime;
        }
        SkuDailyPlanQuotaDTO currentQuota = sku.getDailyPlanQuotaMap().get(productionDate);
        if (hasSchedulableDailyPlanQuota(sku, currentQuota)) {
            return switchReadyTime;
        }
        LocalDate nextPlanDate = resolveNextPositiveDailyPlanDate(
                sku, sku.getDailyPlanQuotaMap(), productionDate, resolveScheduleTargetLocalDate(context));
        if (Objects.isNull(nextPlanDate)) {
            return switchReadyTime;
        }
        EarlyProductionDecision earlyProductionDecision = resolveEarlyProductionDecision(
                context, sku, switchReadyTime, shifts, isEnding);
        if (isAllowedFuturePlanEarlyProduction(earlyProductionDecision)) {
            if (isEnding || sku.isContinuousCompensationSku()) {
                log.info("新增SKU提前生产准入通过，保留当前业务日首台换模, materialCode: {}, isEnding: {}, "
                                + "compensationSku: {}, fromProductionDate: {}, futurePlanDate: {}, switchReadyTime: {}",
                        sku.getMaterialCode(), isEnding, sku.isContinuousCompensationSku(),
                        productionDate, earlyProductionDecision.getFuturePlanDate(),
                        LhScheduleTimeUtil.formatDateTime(switchReadyTime));
                return switchReadyTime;
            }
            Date targetDaySwitchReadyTime = resolveTargetDaySwitchReadyTime(
                    context, shifts, productionDate, switchReadyTime);
            if (Objects.nonNull(targetDaySwitchReadyTime)) {
                log.info("新增SKU提前生产换模日期按目标业务日顺延, materialCode: {}, "
                                + "fromProductionDate: {}, targetProductionDate: {}, "
                                + "fromSwitchReadyTime: {}, toSwitchReadyTime: {}",
                        sku.getMaterialCode(), productionDate, resolveScheduleBusinessLocalDate(context),
                        LhScheduleTimeUtil.formatDateTime(switchReadyTime),
                        LhScheduleTimeUtil.formatDateTime(targetDaySwitchReadyTime));
                return targetDaySwitchReadyTime;
            }
            return switchReadyTime;
        }
        if (sku.isContinuousCompensationSku()
                && !shouldDelayFirstProductionForNoPlanDate(sku, switchReadyTime, isEnding)) {
            return switchReadyTime;
        }
        Date nextSwitchReadyTime = resolveFirstAllowMouldChangeShiftStartTime(shifts, nextPlanDate);
        if (Objects.isNull(nextSwitchReadyTime) || !nextSwitchReadyTime.after(switchReadyTime)) {
            return switchReadyTime;
        }
        log.info("新增SKU首台换模日期按上机日顺延, materialCode: {}, fromProductionDate: {}, "
                        + "toProductionDate: {}, fromSwitchReadyTime: {}, toSwitchReadyTime: {}",
                sku.getMaterialCode(), productionDate, nextPlanDate,
                LhScheduleTimeUtil.formatDateTime(switchReadyTime),
                LhScheduleTimeUtil.formatDateTime(nextSwitchReadyTime));
        return nextSwitchReadyTime;
    }

    /**
     * 提前生产首台上机时，换模最早只能落在排程目标业务日的首个允许换模班次。
     *
     * @param context 排程上下文
     * @param shifts 排程窗口班次
     * @param productionDate 当前换模就绪所在业务日
     * @param switchReadyTime 当前换模就绪时间
     * @return 目标业务日换模就绪时间；无需顺延时返回 null
     */
    private Date resolveTargetDaySwitchReadyTime(LhScheduleContext context,
                                                 List<LhShiftConfigVO> shifts,
                                                 LocalDate productionDate,
                                                 Date switchReadyTime) {
        LocalDate targetBusinessDate = resolveScheduleBusinessLocalDate(context);
        if (Objects.isNull(targetBusinessDate) || Objects.isNull(productionDate)
                || !productionDate.isBefore(targetBusinessDate)) {
            return null;
        }
        Date targetDaySwitchReadyTime = resolveFirstAllowMouldChangeShiftStartTime(
                shifts, targetBusinessDate);
        if (Objects.isNull(targetDaySwitchReadyTime) || !targetDaySwitchReadyTime.after(switchReadyTime)) {
            return null;
        }
        return targetDaySwitchReadyTime;
    }

    /**
     * 将后续新增机台的首个可排时间推进到dayN模拟确定的增机业务日。
     *
     * @param sku SKU
     * @param firstProductionStartTime 当前首个可排时间
     * @param shifts 排程窗口班次
     * @param totalScheduledQty 当前SKU累计已排量
     * @param addMachineProductionDate 首次需要增机的业务日期
     * @return 调整后的首个可排时间
     */
    private Date alignAddedMachineProductionStartTime(SkuScheduleDTO sku,
                                                       Date firstProductionStartTime,
                                                       List<LhShiftConfigVO> shifts,
                                                       int totalScheduledQty,
                                                       LocalDate addMachineProductionDate) {
        if (Objects.isNull(firstProductionStartTime) || Objects.isNull(addMachineProductionDate)) {
            return firstProductionStartTime;
        }
        Date addMachineStartTime = resolveFirstShiftStartTime(shifts, addMachineProductionDate);
        if (Objects.isNull(addMachineStartTime) || !addMachineStartTime.after(firstProductionStartTime)) {
            return firstProductionStartTime;
        }
        log.info("新增SKU增机生效日期顺延, materialCode: {}, addMachineProductionDate: {}, "
                        + "fromStartTime: {}, toStartTime: {}",
                Objects.isNull(sku) ? null : sku.getMaterialCode(), addMachineProductionDate,
                LhScheduleTimeUtil.formatDateTime(firstProductionStartTime),
                LhScheduleTimeUtil.formatDateTime(addMachineStartTime));
        return addMachineStartTime;
    }

    /**
     * 将后续新增机台的换模就绪时间推进到增机业务日内首个允许换模班次。
     *
     * @param sku SKU
     * @param switchReadyTime 当前换模就绪时间
     * @param shifts 排程窗口班次
     * @param totalScheduledQty 当前SKU累计已排量
     * @param addMachineProductionDate 首次需要增机的业务日期
     * @return 调整后的换模就绪时间
     */
    private Date alignAddedMachineSwitchReadyTime(SkuScheduleDTO sku,
                                                  Date switchReadyTime,
                                                  List<LhShiftConfigVO> shifts,
                                                  int totalScheduledQty,
                                                  LocalDate addMachineProductionDate) {
        if (Objects.isNull(switchReadyTime) || Objects.isNull(addMachineProductionDate)) {
            return switchReadyTime;
        }
        Date addMachineSwitchReadyTime = resolveFirstAllowMouldChangeShiftStartTime(shifts, addMachineProductionDate);
        if (Objects.isNull(addMachineSwitchReadyTime)) {
            return switchReadyTime;
        }
        if (!addMachineSwitchReadyTime.after(switchReadyTime)) {
            return switchReadyTime;
        }
        log.info("新增SKU增机换模日期按增机日顺延, materialCode: {}, addMachineProductionDate: {}, "
                        + "fromSwitchReadyTime: {}, toSwitchReadyTime: {}",
                Objects.isNull(sku) ? null : sku.getMaterialCode(), addMachineProductionDate,
                LhScheduleTimeUtil.formatDateTime(switchReadyTime),
                LhScheduleTimeUtil.formatDateTime(addMachineSwitchReadyTime));
        return addMachineSwitchReadyTime;
    }

    /**
     * 生成当前候选机台的提前生产判定结果。
     *
     * @param context 排程上下文
     * @param sku 当前 SKU
     * @param firstProductionStartTime 候选机台首个可排时间
     * @param shifts 排程窗口班次
     * @param isEnding 是否按 SKU 收尾
     * @return 当前候选机台的提前生产判定结果
     */
    private EarlyProductionDecision resolveEarlyProductionDecision(LhScheduleContext context,
                                                                    SkuScheduleDTO sku,
                                                                    Date firstProductionStartTime,
                                                                    List<LhShiftConfigVO> shifts,
                                                                    boolean isEnding) {
        if (Objects.isNull(context) || Objects.isNull(sku) || Objects.isNull(firstProductionStartTime)
                || CollectionUtils.isEmpty(sku.getDailyPlanQuotaMap())) {
            return EarlyProductionDecision.notEarlyProduction(true, "非提前生产判定范围");
        }
        LocalDate productionDate = resolveProductionWorkDate(shifts, firstProductionStartTime);
        if (Objects.isNull(productionDate)) {
            productionDate = firstProductionStartTime.toInstant()
                    .atZone(ZoneId.systemDefault()).toLocalDate();
        }
        return EarlyProductionChecker.checkEarlyProduction(context, sku, productionDate,
                resolveScheduleWindowStartLocalDate(context), resolveScheduleTargetLocalDate(context),
                resolveNewSpecShortageAddMachineThreshold(context));
    }

    /**
     * 刷新新增SKU提前生产选机准入结果。
     *
     * @param context 排程上下文
     * @param sku 当前 SKU
     * @param shifts 排程窗口班次
     * @param isEnding 是否按 SKU 收尾
     */
    private void refreshNewSpecEarlyProductionAdmission(LhScheduleContext context,
                                                        SkuScheduleDTO sku,
                                                        List<LhShiftConfigVO> shifts,
                                                        boolean isEnding) {
        if (Objects.isNull(context) || Objects.isNull(sku)) {
            return;
        }
        context.getNewSpecEarlyProductionAllowedMap().remove(sku);
        if (!sku.isContinuousCompensationSku() && !isEnding) {
            return;
        }
        LocalDate windowStartDate = resolveScheduleWindowStartLocalDate(context);
        if (Objects.isNull(windowStartDate)) {
            return;
        }
        Date windowStartTime = Date.from(windowStartDate.atStartOfDay(ZoneId.systemDefault()).toInstant());
        EarlyProductionDecision decision = resolveEarlyProductionDecision(context, sku, windowStartTime, shifts, isEnding);
        if (!isAllowedFuturePlanEarlyProduction(decision)) {
            return;
        }
        context.getNewSpecEarlyProductionAllowedMap().put(sku, Boolean.TRUE);
        log.info("新增SKU提前生产选机准入通过, materialCode: {}, isEnding: {}, compensationSku: {}, "
                        + "windowStartDate: {}, futurePlanDate: {}, sceneType: {}",
                sku.getMaterialCode(), isEnding, sku.isContinuousCompensationSku(), windowStartDate,
                decision.getFuturePlanDate(), decision.getSceneType());
    }

    /**
     * 将提前生产结构机台数追加到硫化排程结果备注。
     *
     * @param context 排程上下文
     * @param result 硫化排程结果
     * @param decision 提前生产判定结果
     * @param businessDate 实际开产业务日期
     */
    private void appendEarlyProductionRemark(LhScheduleContext context,
                                              LhScheduleResult result,
                                              EarlyProductionDecision decision,
                                              LocalDate businessDate) {
        if (Objects.isNull(result) || Objects.isNull(decision)) {
            return;
        }
        // 与提前生产备注同源回写标识：命中场景且准入通过的结果统一标记为 1
        if (decision.isEarlyProduction() && decision.isAllowed()) {
            result.setIsEarlyProduction("1");
        }
        String remarkFragment = decision.buildRemark();
        if (StringUtils.isEmpty(remarkFragment) || StringUtils.contains(result.getRemark(), remarkFragment)) {
            return;
        }
        String oldRemark = result.getRemark();
        if (StringUtils.isEmpty(oldRemark)) {
            result.setRemark(remarkFragment);
        } else {
            result.setRemark(new StringBuilder(oldRemark.length() + remarkFragment.length() + 1)
                    .append(oldRemark).append('；').append(remarkFragment).toString());
        }
        log.info("提前生产结果备注追加, factoryCode: {}, businessDate: {}, materialCode: {}, "
                        + "structureName: {}, machineCode: {}, sceneType: {}, remark: {}",
                Objects.isNull(context) ? null : context.getFactoryCode(), businessDate,
                result.getMaterialCode(), result.getStructureName(), result.getLhMachineCode(),
                decision.getSceneType(), result.getRemark());
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
     * 解析指定业务日内首个允许换模的班次开始时间。
     *
     * @param shifts 排程窗口班次
     * @param productionDate 业务日期
     * @return 首个允许换模班次开始时间
     */
    private Date resolveFirstAllowMouldChangeShiftStartTime(List<LhShiftConfigVO> shifts,
                                                            LocalDate productionDate) {
        if (CollectionUtils.isEmpty(shifts) || Objects.isNull(productionDate)) {
            return null;
        }
        for (LhShiftConfigVO shift : shifts) {
            if (Objects.isNull(shift) || Objects.isNull(shift.getShiftStartDateTime())
                    || Objects.isNull(shift.getWorkDate()) || !shift.isAllowMouldChange()) {
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
     * 基于当前排程结果重建结构/SKU已排机台统计。
     *
     * @param context 排程上下文
     * @param shifts 排程窗口班次
     */
    private void rebuildScheduledMachineCountMap(LhScheduleContext context, List<LhShiftConfigVO> shifts) {
        if (Objects.isNull(context)) {
            return;
        }
        context.clearScheduledMachineCountMaps();
        if (CollectionUtils.isEmpty(context.getScheduleResultList()) || CollectionUtils.isEmpty(shifts)) {
            return;
        }
        int recordCount = 0;
        for (LhScheduleResult result : context.getScheduleResultList()) {
            recordCount += recordScheduledMachineForResult(context, result, shifts, false);
        }
        log.debug("提前生产已排机台统计重建完成, factoryCode: {}, resultCount: {}, recordDateCount: {}",
                context.getFactoryCode(), context.getScheduleResultList().size(), recordCount);
    }

    /**
     * 根据排程结果登记结构/SKU已排机台。
     *
     * @param context 排程上下文
     * @param result 排程结果
     * @param shifts 排程窗口班次
     */
    private void recordScheduledMachineForResult(LhScheduleContext context,
                                                 LhScheduleResult result,
                                                 List<LhShiftConfigVO> shifts) {
        recordScheduledMachineForResult(context, result, shifts, true);
    }

    /**
     * 根据排程结果登记结构/SKU已排机台。
     *
     * @param context 排程上下文
     * @param result 排程结果
     * @param shifts 排程窗口班次
     * @param logEnabled 是否输出回写日志
     * @return 登记的业务日数量
     */
    private int recordScheduledMachineForResult(LhScheduleContext context,
                                                LhScheduleResult result,
                                                List<LhShiftConfigVO> shifts,
                                                boolean logEnabled) {
        if (Objects.isNull(context) || Objects.isNull(result) || CollectionUtils.isEmpty(shifts)
                || StringUtils.isEmpty(result.getLhMachineCode())) {
            return 0;
        }
        Set<LocalDate> recordedDateSet = new LinkedHashSet<LocalDate>(3);
        for (LhShiftConfigVO shift : shifts) {
            if (Objects.isNull(shift) || Objects.isNull(shift.getShiftIndex())) {
                continue;
            }
            Integer planQty = ShiftFieldUtil.getShiftPlanQty(result, shift.getShiftIndex());
            if (Objects.isNull(planQty) || planQty <= 0) {
                continue;
            }
            LocalDate businessDate = resolveShiftWorkDate(shift);
            if (Objects.nonNull(businessDate)) {
                recordedDateSet.add(businessDate);
            }
        }
        for (LocalDate businessDate : recordedDateSet) {
            context.recordScheduledMachine(businessDate, result.getStructureName(),
                    result.getMaterialCode(), result.getProductStatus(), result.getLhMachineCode());
            if (logEnabled) {
                log.info("新增机台回写提前生产统计, factoryCode: {}, businessDate: {}, materialCode: {}, "
                                + "structureName: {}, machineCode: {}, structureScheduledTotal: {}, skuScheduledTotal: {}",
                        context.getFactoryCode(), businessDate, result.getMaterialCode(), result.getStructureName(),
                        result.getLhMachineCode(),
                        context.getStructureScheduledMachineCount(businessDate, result.getStructureName()),
                        context.getSkuScheduledMachineCount(
                                businessDate, result.getMaterialCode(), result.getProductStatus()));
            }
        }
        return recordedDateSet.size();
    }

    /**
     * 解析生产段开产业务日。
     *
     * @param segment 生产段
     * @param shifts 排程窗口班次
     * @return 开产业务日
     */
    private LocalDate resolveSegmentStartProductionDate(MachineProductionSegment segment,
                                                        List<LhShiftConfigVO> shifts) {
        if (Objects.isNull(segment)) {
            return null;
        }
        LhShiftConfigVO shift = findShiftByIndex(shifts, segment.getStartProductionShiftIndex());
        return resolveShiftWorkDate(shift);
    }

    /**
     * 根据生产时刻解析所属班次的业务日期。
     *
     * @param shifts 排程窗口班次
     * @param productionTime 生产时刻
     * @return 所属班次业务日期；未命中排程窗口返回null
     */
    private LocalDate resolveProductionWorkDate(List<LhShiftConfigVO> shifts, Date productionTime) {
        if (CollectionUtils.isEmpty(shifts) || Objects.isNull(productionTime)) {
            return null;
        }
        for (LhShiftConfigVO shift : shifts) {
            if (Objects.isNull(shift) || Objects.isNull(shift.getShiftStartDateTime())
                    || Objects.isNull(shift.getShiftEndDateTime())) {
                continue;
            }
            if (!productionTime.before(shift.getShiftStartDateTime())
                    && productionTime.before(shift.getShiftEndDateTime())) {
                return resolveShiftWorkDate(shift);
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
        int singleMachineTargetQty = resolveCurrentMachineCoverTargetQty(sku, policy, segment, remainingTargetQty);
        if (singleMachineTargetQty > 0) {
            return singleMachineTargetQty;
        }
        int availableMachineCount = countAvailableCandidateMachines(candidates, excludedMachineCodes);
        int requiredMachineCountByDailyCapacity = resolveRequiredMachineCountByDailyCapacity(
                context, sku, candidates, excludedMachineCodes, policy, segment, candidateMachine,
                shifts, capacityCalculate, remainingTargetQty, availableMachineCount);
        if (requiredMachineCountByDailyCapacity == 0 && segment.isExistingSameMaterialSatisfied()) {
            // 已有同物料机台满足逐日加机台规则时，当前候选不再因目标剩余继续新增。
            log.info("新增SKU已有同物料机台满足dayN增机台规则，跳过当前新增候选, "
                            + "materialCode: {}, machineCode: {}, remainingTargetQty: {}, "
                            + "remainingScheduleQty: {}, existingSameMaterialSatisfied: true",
                    sku.getMaterialCode(), segment.getMachineCode(), remainingTargetQty,
                    sku.getRemainingScheduleQty());
            appendNewSpecDailyRhythmStopProcessLog(context, sku, segment.getMachineCode(),
                    targetQty, scheduledQty,
                    "已有同物料有效机台满足当前日优先dayN节奏");
            return 0;
        }
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
     * 当前机台窗口有效产能已覆盖严格业务目标时，停止 dayN 拆第二台机台。
     * <p>收尾/严格目标 SKU 的实际排产上限来自 SKU 实际消费账本和目标量。
     * 如果当前机台已经能消化完整目标，就不需要再按 T/T+1 日计划节奏提前拆量。</p>
     *
     * @param sku SKU
     * @param policy 数量策略
     * @param segment 当前机台生产段
     * @param remainingTargetQty 当前剩余业务目标量
     * @return 当前机台计划量；0 表示继续原动态拆量逻辑
     */
    private int resolveCurrentMachineCoverTargetQty(SkuScheduleDTO sku,
                                                    ProductionQuantityPolicy policy,
                                                    MachineProductionSegment segment,
                                                    int remainingTargetQty) {
        if (Objects.isNull(sku) || Objects.isNull(policy) || Objects.isNull(segment)
                || remainingTargetQty <= 0 || segment.getMaxQtyToWindowEnd() <= 0) {
            return 0;
        }
        if (!policy.isStrictUpperLimit() && !policy.isEnding()) {
            return 0;
        }
        if (segment.getMaxQtyToWindowEnd() < remainingTargetQty) {
            return 0;
        }
        log.info("新增SKU当前机台窗口产能覆盖严格目标，停止增机台拆量, materialCode: {}, machineCode: {}, "
                        + "remainingTargetQty: {}, maxQtyToWindowEnd: {}, isEnding: {}, strictUpperLimit: {}",
                sku.getMaterialCode(), segment.getMachineCode(), remainingTargetQty,
                segment.getMaxQtyToWindowEnd(), policy.isEnding(), policy.isStrictUpperLimit());
        return Math.min(remainingTargetQty, segment.getMaxQtyToWindowEnd());
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
     * <p>小欠产模式下，当前日和后一天均按单日理论产能判断；
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
        LocalDate currentProductionDate = resolveSegmentStartProductionDate(segment, shifts);
        Map<LocalDate, SkuDailyPlanQuotaDTO> simulationSourceQuotaMap =
                resolveEarlyProductionSimulationQuotaMap(context, sku, currentProductionDate, windowEndDate);
        int effectiveRemainingTargetQty = resolveEffectiveSimulationRemainingTargetQty(
                sku, simulationSourceQuotaMap, remainingTargetQty);
        request.setDailyPlanQuotaMap(buildSimulationQuotaMap(
                sku, simulationSourceQuotaMap, effectiveRemainingTargetQty, windowEndDate));
        List<Map<LocalDate, Integer>> existingMachineCapacityMaps = buildExistingSameMaterialCapacityMaps(
                context, sku, candidateMachine, shifts, request.getDailyPlanQuotaMap());
        request.setMachineDailyCapacityList(buildSimulationMachineCapacityList(
                context, sku, candidates, excludedMachineCodes, policy, segment, candidateMachine,
                shifts, capacityCalculate, request.getDailyPlanQuotaMap(), existingMachineCapacityMaps));
        request.setInitialActiveMachines(Math.max(1, existingMachineCapacityMaps.size() + 1));
        // 冻结为单模的SKU使用单控机台时，单台日硫化标准量折半，
        // 避免扩机台模拟高估单控单侧机台产能，导致加机台数量不足
        int simulationShiftCapacity = Math.max(0, sku.getShiftCapacity());
        int simulationDailyStandardQty = resolveNewSpecDailyStandardQty(context, sku);
        boolean singleControlSideCapacity = LhSingleControlMachineUtil.isSingleSideGranularitySku(context, sku)
                && Objects.nonNull(candidateMachine)
                && LhSingleControlMachineUtil.isConfiguredSingleControlMachine(
                        context, candidateMachine.getMachineCode());
        if (singleControlSideCapacity) {
            simulationShiftCapacity = Math.max(1, simulationShiftCapacity / 2);
            simulationDailyStandardQty = Math.max(1, simulationDailyStandardQty / 2);
        }
        request.setShiftCapacity(simulationShiftCapacity);
        String configPlusShiftType = ShiftCapacityResolverUtil.resolveOddShiftCapacityPlusShiftType(context);
        request.setSingleMachineWindowCapacityQty(ShiftCapacityResolverUtil.sumActualShiftPlanQty(
                shifts, simulationShiftCapacity, configPlusShiftType, ScheduleTypeEnum.NEW_SPEC.getCode()));
        // 该产能图只用于 dayN 是否增加机台判断，T 日和后续业务日统一使用正式日硫化标准。
        // 候选机台的真实可排量仍由 machineDailyCapacityList 和窗口班次产能计算，不改变实际排产语义。
        request.setSingleMachineDailyCapacityMap(buildAddMachineDailyTheoryCapacityMap(
                request.getDailyPlanQuotaMap(), simulationDailyStandardQty));
        request.setShortageLookAheadDays(resolveNewSpecShortageLookAheadDays(context));
        int monthlyHistoryShortageQty = Math.max(0, sku.getMonthlyHistoryShortageQty());
        request.setMonthlyHistoryShortageQty(monthlyHistoryShortageQty);
        request.setScheduleDayFinishQty(Math.max(0, sku.getScheduleDayFinishQty()));
        request.setWindowMonthPlanQty(sumSimulationWindowMonthPlanQty(simulationSourceQuotaMap));
        request.setShortageAddMachineThreshold(resolveNewSpecShortageAddMachineThreshold(context));
        request.setWindowEndDate(windowEndDate);
        request.setWindowLastDayNextPlanLookAheadEnabled(true);
        LocalDate firstFuturePlanDate = EarlyProductionChecker.resolveFirstFuturePlanDate(
                context, sku, currentProductionDate);
        if (EarlyProductionChecker.isEndingStructureLargeSurplus(
                context, sku, currentProductionDate, firstFuturePlanDate)) {
            request.setForceShortageWindowMode(true);
            int currentPlanMachineCount = context.getStructurePlanMachineCount(
                    currentProductionDate, sku.getStructureName());
            int futurePlanMachineCount = context.getStructurePlanMachineCount(
                    firstFuturePlanDate, sku.getStructureName());
            log.info("新增SKU结构收尾大余量进入强制加机台模拟, factoryCode: {}, materialCode: {}, "
                            + "structureName: {}, productionDate: {}, futurePlanDate: {}, "
                            + "currentPlanMachineCount: {}, futurePlanMachineCount: {}, "
                            + "historyShortageQty: {}, skuScheduledMachineCount: {}, dailyCapacity: {}, "
                            + "result: true, reason: 结构已收尾且SKU余量较大",
                    context.getFactoryCode(), sku.getMaterialCode(), sku.getStructureName(),
                    currentProductionDate, firstFuturePlanDate, currentPlanMachineCount, futurePlanMachineCount,
                    monthlyHistoryShortageQty,
                    context.getSkuScheduledMachineCount(
                            currentProductionDate, sku.getMaterialCode(), sku.getProductStatus()),
                    Math.max(0, sku.getDailyCapacity()));
        }
        request.setSceneType("newSpec");
        DailyMachineCapacitySimulationResult simulationResult =
                DailyMachineCapacitySimulationUtil.simulateExpansion(request);
        logDailyMachineCapacitySimulation(sku, segment, simulationResult);
        int requiredMachineCountByDailyCapacity = resolveRequiredNewSpecMachineCount(
                simulationResult.getFinalActiveMachines(), existingMachineCapacityMaps.size());
        int dailyRhythmMachineCountCap = resolveDailyRhythmMachineCountCap(request);
        if (dailyRhythmMachineCountCap > 0 && requiredMachineCountByDailyCapacity > 0) {
            requiredMachineCountByDailyCapacity =
                    Math.min(requiredMachineCountByDailyCapacity, dailyRhythmMachineCountCap);
        }
        // 已有同物料机台是否满足 dayN 节奏，按 dayN 节奏总机台数（dailyRhythmMachineCountCap）判断，
        // 不使用按月计划余量扩出的 finalActiveMachines，避免余量大时误扩机台（如 3302001271 dayN=46,46,46，
        // 余量 700 驱动 finalActiveMachines=2，但 dayN 节奏只需 1 台，已有 1 台即满足、不再增机）。
        // 续作增机台补偿SKU已由续作链路按 dayN 节奏确定需要新增机台（continuationShortageMachineCount>0），
        // 此处不得因 simulateExpansion 在当前日满足即停止而误判已有续作机台满足，导致补偿SKU无法落第2台
        // （如 3302001590 dayN=48,48,68，T+3=96，缺口1台）；shortage=0 的补偿SKU（如 dayN 全满足）仍受此约束。
        boolean compensationShortageAddMachine = sku.isContinuousCompensationSku()
                && Math.max(0, sku.getContinuationShortageMachineCount()) > 0;
        if (!compensationShortageAddMachine
                && isExistingSameMaterialSimulationSatisfied(
                request, existingMachineCapacityMaps, dailyRhythmMachineCountCap)) {
            segment.setExistingSameMaterialSatisfied(true);
            log.info("新增SKU已有同物料机台满足dayN增机台规则, materialCode: {}, machineCode: {}, "
                            + "existingMachineCount: {}, dailyRhythmMachineCountCap: {}, remainingTargetQty: {}",
                    sku.getMaterialCode(), segment.getMachineCode(), existingMachineCapacityMaps.size(),
                    dailyRhythmMachineCountCap, remainingTargetQty);
            return 0;
        }
        segment.setAddMachineProductionDateList(resolveAddMachineProductionDateList(simulationResult));
        int requiredMachineCountByMouldInfo = resolveRequiredShortageOnlyMachineCountByMouldInfo(
                sku, candidateMachine, existingMachineCapacityMaps.size(), availableMachineCount);
        int requiredMachineCount = Math.max(requiredMachineCountByDailyCapacity, requiredMachineCountByMouldInfo);
        log.info("新增SKU dayN扩机台模拟结果, materialCode: {}, machineCode: {}, remainingTargetQty: {}, "
                        + "windowDayPlanQty: {}, finalActiveMachines: {}, existingSameMaterialMachineCount: {}, "
                        + "dailyCapacityRequiredMachineCount: {}, mouldInfoRequiredMachineCount: {}, "
                        + "dailyRhythmMachineCountCap: {}, requiredMachineCount: {}, dayNTargetCap: false",
                sku.getMaterialCode(), segment.getMachineCode(), remainingTargetQty,
                sumSimulationWindowMonthPlanQty(request.getDailyPlanQuotaMap(), request.getWindowEndDate()),
                simulationResult.getFinalActiveMachines(), existingMachineCapacityMaps.size(),
                requiredMachineCountByDailyCapacity, requiredMachineCountByMouldInfo,
                dailyRhythmMachineCountCap, requiredMachineCount);
        appendDailyMachineExpansionProcessLog(context, sku, segment, remainingTargetQty,
                request, simulationResult, existingMachineCapacityMaps.size(),
                requiredMachineCountByDailyCapacity, requiredMachineCountByMouldInfo,
                dailyRhythmMachineCountCap, requiredMachineCount);
        return requiredMachineCount;
    }

    /**
     * 追加新增排产 dayN 扩机台模拟过程日志。
     *
     * @param context 排程上下文
     * @param sku SKU
     * @param segment 当前机台生产段
     * @param remainingTargetQty 本轮剩余目标量
     * @param request 模拟请求
     * @param simulationResult 模拟结果
     * @param existingSameMaterialMachineCount 已有同物料机台数
     * @param requiredMachineCountByDailyCapacity dayN 节奏推导机台数
     * @param requiredMachineCountByMouldInfo 模具信息推导机台数
     * @param dailyRhythmMachineCountCap dayN 标准机台数上限
     * @param requiredMachineCount 最终需要总机台数
     */
    private void appendDailyMachineExpansionProcessLog(LhScheduleContext context,
                                                       SkuScheduleDTO sku,
                                                       MachineProductionSegment segment,
                                                       int remainingTargetQty,
                                                       DailyMachineCapacitySimulationRequest request,
                                                       DailyMachineCapacitySimulationResult simulationResult,
                                                       int existingSameMaterialMachineCount,
                                                       int requiredMachineCountByDailyCapacity,
                                                       int requiredMachineCountByMouldInfo,
                                                       int dailyRhythmMachineCountCap,
                                                       int requiredMachineCount) {
        if (Objects.isNull(context) || Objects.isNull(sku)
                || Objects.isNull(segment) || Objects.isNull(request)
                || Objects.isNull(simulationResult)) {
            return;
        }
        String detail = new StringBuilder(256)
                .append("materialCode=").append(sku.getMaterialCode())
                .append(", machineCode=").append(segment.getMachineCode())
                .append(", remainingTargetQty=").append(remainingTargetQty)
                .append(", windowDayPlanQty=")
                .append(sumSimulationWindowMonthPlanQty(request.getDailyPlanQuotaMap(), request.getWindowEndDate()))
                .append(", finalActiveMachines=").append(simulationResult.getFinalActiveMachines())
                .append(", existingSameMaterialMachineCount=").append(existingSameMaterialMachineCount)
                .append(", dailyCapacityRequiredMachineCount=").append(requiredMachineCountByDailyCapacity)
                .append(", mouldInfoRequiredMachineCount=").append(requiredMachineCountByMouldInfo)
                .append(", dailyRhythmMachineCountCap=").append(dailyRhythmMachineCountCap)
                .append(", requiredMachineCount=").append(requiredMachineCount)
                .append(", dayNTargetCap=false")
                .toString();
        PriorityTraceLogHelper.appendProcessLog(context, "新增SKU dayN扩机台模拟", detail);
    }

    /**
     * 判断已有同物料机台是否已经满足 dayN 增机台规则。
     *
     * @param request 原模拟请求
     * @param existingMachineCapacityMaps 已有同物料机台产能图
     * @return true-已有机台已满足，无需当前新增候选
     */
    private boolean isExistingSameMaterialSimulationSatisfied(
            DailyMachineCapacitySimulationRequest request,
            List<Map<LocalDate, Integer>> existingMachineCapacityMaps,
            int requiredActiveMachineCountByDailyCapacity) {
        if (Objects.isNull(request) || CollectionUtils.isEmpty(existingMachineCapacityMaps)) {
            return false;
        }
        if (requiredActiveMachineCountByDailyCapacity > 0
                && existingMachineCapacityMaps.size() >= requiredActiveMachineCountByDailyCapacity) {
            return true;
        }
        DailyMachineCapacitySimulationRequest existingOnlyRequest = new DailyMachineCapacitySimulationRequest();
        BeanUtil.copyProperties(request, existingOnlyRequest);
        existingOnlyRequest.setMachineDailyCapacityList(existingMachineCapacityMaps);
        existingOnlyRequest.setInitialActiveMachines(existingMachineCapacityMaps.size());
        DailyMachineCapacitySimulationResult existingOnlyResult =
                DailyMachineCapacitySimulationUtil.simulateExpansion(existingOnlyRequest);
        // 只有已有同物料机台实际消化完 dayN 缺口时，才允许跳过当前新增候选。
        return existingOnlyResult.getTotalUnmetQty() <= 0
                && existingOnlyResult.getFinalActiveMachines() <= existingMachineCapacityMaps.size()
                && existingOnlyResult.getTotalAddedMachineCount() == 0;
    }

    /**
     * 判断新增 SKU 是否已由同物料纯续作机台满足原始 dayN 最小机台数。
     * <p>S4.5 原始新增列表可能仍保留同物料 SKU，如果 MES 在机续作机台已经覆盖 dayN 节奏，
     * 不能再因为硫化余量、目标剩余或欠产未清零重新新增换模上机。</p>
     *
     * @param context 排程上下文
     * @param sku 当前新增SKU
     * @param quantityPolicy 数量控制策略
     * @return true-跳过新增排产；false-继续走新增选机
     */
    private boolean shouldSkipNewSpecBecauseContinuousSatisfiesOriginalDayMinimum(
            LhScheduleContext context,
            SkuScheduleDTO sku,
            ProductionQuantityPolicy quantityPolicy) {
        if (Objects.isNull(context) || Objects.isNull(sku) || Objects.isNull(quantityPolicy)
                || StringUtils.isEmpty(sku.getMaterialCode())) {
            return false;
        }
        Set<String> continuousMachineCodes = resolvePureContinuousMachineCodes(
                context, sku.getMaterialCode(), sku.getProductStatus());
        if (CollectionUtils.isEmpty(continuousMachineCodes)) {
            return false;
        }
        int dailyStandardQty = resolveNewSpecDailyStandardQty(context, sku);
        if (dailyStandardQty <= 0) {
            return false;
        }
        List<LocalDate> checkDateList = resolveNewSpecDayMinimumCheckDates(context, sku);
        if (!hasPositiveOriginalNewSpecDayPlan(context, sku, checkDateList)) {
            return false;
        }
        // 欠产超过阈值时走强制增机台模式（spec 7），不用逐日后看判断是否跳过新增
        int shortageAddMachineThreshold = DailyMachineExpansionPlanner.resolveShortageAddMachineThreshold(context);
        int historyShortageQty = Math.max(0, sku.getMonthlyHistoryShortageQty());
        if (shortageAddMachineThreshold > 0 && historyShortageQty > shortageAddMachineThreshold) {
            return false;
        }
        // 复用续作公共逐日后看判断，统一新增排产与续作的加机台口径，避免产能口径和末日处理不一致
        LocalDate firstAddMachineDate = DailyMachineExpansionPlanner.resolveFirstDailyLookAheadAddMachineDate(
                context, sku, continuousMachineCodes.size(), ScheduleTypeEnum.NEW_SPEC.getCode());
        if (Objects.nonNull(firstAddMachineDate)) {
            log.info("新增SKU同物料续作机台不足，继续新增选机, materialCode: {}, 首次需加机日期: {}, "
                            + "SKU日标准产量: {}, 已有续作机台数: {}, 续作机台: {}, 判断口径: 当前日不足且下一生产日也不足",
                    sku.getMaterialCode(), firstAddMachineDate, dailyStandardQty,
                    continuousMachineCodes.size(), String.join(",", continuousMachineCodes));
            return false;
        }
        log.info("新增SKU跳过，同物料已有纯续作机台满足当前日优先dayN节奏, materialCode: {}, "
                        + "SKU日标准产量: {}, 已有续作机台数: {}, 续作机台: {}, 判断口径: 当前日满足则不因后续dayN单日增大提前加机台",
                sku.getMaterialCode(), dailyStandardQty,
                continuousMachineCodes.size(), String.join(",", continuousMachineCodes));
        appendContinuousSatisfiedNewSpecSkipProcessLog(
                context, sku, dailyStandardQty, continuousMachineCodes.size(), continuousMachineCodes);
        return true;
    }

    /**
     * 判断原始 dayN 是否存在正计划量。
     *
     * @param context 排程上下文
     * @param sku 当前新增SKU
     * @param checkDateList 待检查业务日
     * @return true-存在正计划量；false-没有正计划量
     */
    private boolean hasPositiveOriginalNewSpecDayPlan(LhScheduleContext context,
                                                      SkuScheduleDTO sku,
                                                      List<LocalDate> checkDateList) {
        if (CollectionUtils.isEmpty(checkDateList)) {
            return false;
        }
        for (LocalDate productionDate : checkDateList) {
            if (resolveOriginalNewSpecDayPlanQty(context, sku, productionDate) > 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * 收集同物料纯续作机台编码。
     *
     * @param context 排程上下文
     * @param materialCode 物料编码
     * @param productStatus 产品状态
     * @return 纯续作机台编码集合
     */
    private Set<String> resolvePureContinuousMachineCodes(LhScheduleContext context,
                                                          String materialCode,
                                                          String productStatus) {
        Set<String> machineCodes = new LinkedHashSet<String>(4);
        if (Objects.isNull(context) || StringUtils.isEmpty(materialCode)
                || CollectionUtils.isEmpty(context.getScheduleResultList())) {
            return machineCodes;
        }
        for (LhScheduleResult result : context.getScheduleResultList()) {
            if (Objects.isNull(result)
                    || !StringUtils.equals(materialCode, result.getMaterialCode())
                    || !StringUtils.equals(StringUtils.trimToEmpty(productStatus),
                    StringUtils.trimToEmpty(result.getProductStatus()))
                    || !StringUtils.equals(ScheduleTypeEnum.CONTINUOUS.getCode(), result.getScheduleType())
                    || StringUtils.equals("1", result.getIsTypeBlock())
                    || StringUtils.isEmpty(result.getLhMachineCode())
                    || resolveResultScheduledQty(result) <= 0) {
                continue;
            }
            machineCodes.add(result.getLhMachineCode());
        }
        return machineCodes;
    }

    /**
     * 解析新增排产同物料续作保护使用的业务日集合。
     *
     * @param context 排程上下文
     * @param sku 当前新增SKU
     * @return 需要检查的业务日集合
     */
    private List<LocalDate> resolveNewSpecDayMinimumCheckDates(LhScheduleContext context, SkuScheduleDTO sku) {
        Set<LocalDate> productionDateSet = new LinkedHashSet<LocalDate>(4);
        List<LhShiftConfigVO> shifts = context.getScheduleWindowShifts();
        if (CollectionUtils.isEmpty(shifts)) {
            shifts = LhScheduleTimeUtil.getScheduleShifts(context, context.getScheduleDate());
        }
        if (!CollectionUtils.isEmpty(shifts)) {
            for (LhShiftConfigVO shift : shifts) {
                if (Objects.isNull(shift) || Objects.isNull(shift.getWorkDate())) {
                    continue;
                }
                productionDateSet.add(shift.getWorkDate().toInstant()
                        .atZone(ZoneId.systemDefault()).toLocalDate());
            }
        }
        if (CollectionUtils.isEmpty(productionDateSet) && !CollectionUtils.isEmpty(sku.getDailyPlanQuotaMap())) {
            productionDateSet.addAll(sku.getDailyPlanQuotaMap().keySet());
        }
        return new ArrayList<LocalDate>(productionDateSet);
    }

    /**
     * 解析新增排产使用的原始 dayN 计划量。
     *
     * @param context 排程上下文
     * @param sku 当前新增SKU
     * @param productionDate 业务日
     * @return 原始dayN计划量
     */
    private int resolveOriginalNewSpecDayPlanQty(LhScheduleContext context,
                                                 SkuScheduleDTO sku,
                                                 LocalDate productionDate) {
        int dayPlanQty = MonthPlanDateResolver.resolveDayQty(
                context, sku.getMaterialCode(), sku.getProductStatus(), productionDate);
        if (dayPlanQty > 0 || CollectionUtils.isEmpty(sku.getDailyPlanQuotaMap())) {
            return dayPlanQty;
        }
        SkuDailyPlanQuotaDTO quota = sku.getDailyPlanQuotaMap().get(productionDate);
        return Objects.isNull(quota) ? 0 : Math.max(0, quota.getDayPlanQty());
    }

    /**
     * 解析新增排产同物料续作保护使用的 SKU 日标准产量。
     *
     * @param context 排程上下文
     * @param sku 当前新增SKU
     * @return SKU日标准产量
     */
    private int resolveNewSpecDailyStandardQty(LhScheduleContext context, SkuScheduleDTO sku) {
        int dailyStandardQty = ShiftCapacityResolverUtil.resolveDailyStandardQty(context, sku.getMaterialCode());
        if (dailyStandardQty <= 0) {
            dailyStandardQty = Math.max(0, sku.getDailyCapacity());
        }
        if (dailyStandardQty <= 0) {
            dailyStandardQty = Math.max(0, sku.getShiftCapacity())
                    * LhScheduleConstant.DEFAULT_SHIFTS_PER_DAY;
        }
        return dailyStandardQty;
    }

    /**
     * 构建新增扩机判断专用的正式日硫化标准产能图。
     * <p>T 日、T+1、T+2 及后看业务日使用相同单机日标准；该产能图不得用于实际班次排产。</p>
     *
     * @param quotaMap dayN 日计划账本
     * @param dailyStandardQty 单机正式日硫化标准
     * @return 按业务日展开的加机台判断产能图
     */
    private Map<LocalDate, Integer> buildAddMachineDailyTheoryCapacityMap(
            Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap,
            int dailyStandardQty) {
        Map<LocalDate, Integer> capacityMap = new LinkedHashMap<LocalDate, Integer>(
                CollectionUtils.isEmpty(quotaMap) ? 0 : Math.max(4, quotaMap.size() * 2));
        if (CollectionUtils.isEmpty(quotaMap) || dailyStandardQty <= 0) {
            return capacityMap;
        }
        for (LocalDate productionDate : quotaMap.keySet()) {
            if (Objects.nonNull(productionDate)) {
                capacityMap.put(productionDate, dailyStandardQty);
            }
        }
        return capacityMap;
    }

    /**
     * 记录新增排产因同物料续作满足 dayN 而跳过的过程日志。
     *
     * @param context 排程上下文
     * @param sku 当前新增SKU
     * @param dailyStandardQty SKU日标准产量
     * @param continuousMachineCount 已有纯续作机台数
     * @param continuousMachineCodes 续作机台集合
     */
    private void appendContinuousSatisfiedNewSpecSkipProcessLog(LhScheduleContext context,
                                                                SkuScheduleDTO sku,
                                                                int dailyStandardQty,
                                                                int continuousMachineCount,
                                                                Set<String> continuousMachineCodes) {
        String detail = new StringBuilder(192)
                .append("materialCode=").append(sku.getMaterialCode())
                .append(", dailyStandardQty=").append(dailyStandardQty)
                .append(", continuousMachineCount=").append(continuousMachineCount)
                .append(", continuousMachines=").append(String.join(",", continuousMachineCodes))
                .append(", reason=已有纯续作机台满足当前日优先dayN节奏")
                .toString();
        PriorityTraceLogHelper.appendProcessLog(context, "新增排产同物料续作满足dayN跳过", detail);
    }

    /**
     * 解析 dayN 节奏对应的标准机台数上限。
     * <p>新增排产扩机台判断按单机日标准产能推导“需要几台”，当前候选换模损失只影响实际排量，
     * 不应把 dayN 规则上限继续放大。</p>
     *
     * @param request dayN 模拟请求
     * @return 标准机台数上限，0表示无法解析
     */
    private int resolveDailyRhythmMachineCountCap(DailyMachineCapacitySimulationRequest request) {
        if (Objects.isNull(request) || CollectionUtils.isEmpty(request.getDailyPlanQuotaMap())
                || CollectionUtils.isEmpty(request.getSingleMachineDailyCapacityMap())) {
            return 0;
        }
        int maxDayPlanQty = 0;
        for (Map.Entry<LocalDate, SkuDailyPlanQuotaDTO> entry : request.getDailyPlanQuotaMap().entrySet()) {
            LocalDate productionDate = entry.getKey();
            if (Objects.isNull(productionDate) || isAfterSimulationWindowEnd(productionDate, request.getWindowEndDate())
                    || Objects.isNull(entry.getValue())) {
                continue;
            }
            maxDayPlanQty = Math.max(maxDayPlanQty, Math.max(0, entry.getValue().getDayPlanQty()));
        }
        int maxSingleMachineDailyCapacity = 0;
        for (Integer dailyCapacity : request.getSingleMachineDailyCapacityMap().values()) {
            if (Objects.nonNull(dailyCapacity)) {
                maxSingleMachineDailyCapacity = Math.max(maxSingleMachineDailyCapacity, Math.max(0, dailyCapacity));
            }
        }
        if (maxDayPlanQty <= 0 || maxSingleMachineDailyCapacity <= 0) {
            return 0;
        }
        return Math.max(1, divideCeiling(maxDayPlanQty, maxSingleMachineDailyCapacity));
    }

    /**
     * 判断模拟日期是否超过窗口结束日。
     *
     * @param productionDate 模拟生产日
     * @param windowEndDate 窗口结束日
     * @return true-超过窗口；false-窗口内或无法判断
     */
    private boolean isAfterSimulationWindowEnd(LocalDate productionDate, LocalDate windowEndDate) {
        return Objects.nonNull(productionDate)
                && Objects.nonNull(windowEndDate)
                && productionDate.isAfter(windowEndDate);
    }

    /**
     * 统计当前 SKU 已落地的同物料结果数。
     *
     * @param context 排程上下文
     * @param sku 当前 SKU
     * @param currentMachineCode 当前候选机台
     * @return 已落地同物料结果数
     */
    private int countExistingSameMaterialResults(LhScheduleContext context,
                                                 SkuScheduleDTO sku,
                                                 String currentMachineCode) {
        if (Objects.isNull(context) || Objects.isNull(sku)
                || CollectionUtils.isEmpty(context.getScheduleResultList())) {
            return 0;
        }
        int count = 0;
        for (LhScheduleResult result : context.getScheduleResultList()) {
            if (isExistingSameMaterialActiveResult(context, result, sku, currentMachineCode)) {
                count++;
            }
        }
        return count;
    }

    /**
     * 从逐日模拟结果中按新增顺序解析各机台的生效业务日期。
     *
     * @param simulationResult dayN机台模拟结果
     * @return 新增机台生效业务日期列表
     */
    private List<LocalDate> resolveAddMachineProductionDateList(
            DailyMachineCapacitySimulationResult simulationResult) {
        List<LocalDate> productionDateList = new ArrayList<LocalDate>(4);
        if (Objects.isNull(simulationResult)
                || CollectionUtils.isEmpty(simulationResult.getDayDecisionList())) {
            return productionDateList;
        }
        for (DailyMachineCapacityDayDecision decision : simulationResult.getDayDecisionList()) {
            if (Objects.isNull(decision) || Objects.isNull(decision.getProductionDate())
                    || decision.getAddedMachineCount() <= 0) {
                continue;
            }
            for (int index = 0; index < decision.getAddedMachineCount(); index++) {
                productionDateList.add(decision.getProductionDate());
            }
        }
        return productionDateList;
    }

    /**
     * 按当前已成功排产机台数解析下一台新增机台的生效日期。
     *
     * @param addMachineProductionDateList 新增机台生效日期列表
     * @param scheduledMachineCount 当前已成功排产机台数
     * @return 当前候选作为新增机台时的生效日期；首台或未配置时返回null
     */
    private LocalDate resolveCurrentAddMachineProductionDate(
            SkuScheduleDTO sku,
            List<LocalDate> addMachineProductionDateList,
            int scheduledMachineCount) {
        if (scheduledMachineCount <= 0) {
            if (isContinuationAddMachineCandidate(sku)) {
                return sku.getFirstAddMachineProductionDate();
            }
            return null;
        }
        if (CollectionUtils.isEmpty(addMachineProductionDateList)) {
            return null;
        }
        int addedMachineIndex = scheduledMachineCount - 1;
        if (addedMachineIndex >= addMachineProductionDateList.size()) {
            return null;
        }
        return addMachineProductionDateList.get(addedMachineIndex);
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
     * 构建新增排产严格扣账试算账本快照。
     *
     * @param quotaMap 原日计划账本
     * @param remainingTargetQty 本轮剩余目标量
     * @return 模拟账本
     */
    private Map<LocalDate, SkuDailyPlanQuotaDTO> buildSimulationQuotaMap(
            Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap,
            int remainingTargetQty) {
        return DailyMachineExpansionPlanner.buildTargetCappedSimulationQuotaMap(quotaMap, remainingTargetQty);
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
        return buildSimulationQuotaMap(sku, quotaMap, remainingTargetQty, windowEndDate);
    }

    /**
     * 构建新增排产 dayN 扩机台模拟账本快照。
     * <p>dayN 只作为节奏和资源判断依据，不允许被本轮剩余目标量截断；
     * 实际排产量仍由目标量、日计划扣账、胎胚、硫化余量和模具资源控制。</p>
     * <p>提前生产场景传入前移后的临时日计划视图；普通场景仍传入原始日计划账本。</p>
     *
     * @param sku SKU
     * @param quotaMap 模拟来源账本
     * @param remainingTargetQty 本轮剩余目标量
     * @param windowEndDate 排程窗口结束日
     * @return 模拟账本
     */
    private Map<LocalDate, SkuDailyPlanQuotaDTO> buildSimulationQuotaMap(
            SkuScheduleDTO sku,
            Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap,
            int remainingTargetQty,
            LocalDate windowEndDate) {
        Map<LocalDate, SkuDailyPlanQuotaDTO> simulationQuotaMap =
                buildDailyRhythmSimulationQuotaMap(sku, quotaMap);
        if (Objects.isNull(sku) || quotaMap == sku.getDailyPlanQuotaMap()) {
            keepNextDayPlanForWindowLastDayLookAhead(sku, quotaMap, simulationQuotaMap, windowEndDate);
        }
        return simulationQuotaMap;
    }

    /**
     * 构建不按目标量截断的 dayN 节奏模拟账本。
     *
     * @param sku SKU
     * @param quotaMap 模拟来源账本
     * @return dayN 节奏模拟账本
     */
    private Map<LocalDate, SkuDailyPlanQuotaDTO> buildDailyRhythmSimulationQuotaMap(
            SkuScheduleDTO sku,
            Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap) {
        if (CollectionUtils.isEmpty(quotaMap)) {
            return new LinkedHashMap<LocalDate, SkuDailyPlanQuotaDTO>(0);
        }
        Map<LocalDate, SkuDailyPlanQuotaDTO> simulationQuotaMap =
                new LinkedHashMap<LocalDate, SkuDailyPlanQuotaDTO>(Math.max(4, quotaMap.size() * 2));
        for (Map.Entry<LocalDate, SkuDailyPlanQuotaDTO> entry : quotaMap.entrySet()) {
            SkuDailyPlanQuotaDTO sourceQuota = entry.getValue();
            if (Objects.isNull(sourceQuota)) {
                continue;
            }
            SkuDailyPlanQuotaDTO quota = new SkuDailyPlanQuotaDTO();
            quota.setMaterialCode(StringUtils.isNotEmpty(sourceQuota.getMaterialCode())
                    ? sourceQuota.getMaterialCode() : Objects.isNull(sku) ? null : sku.getMaterialCode());
            quota.setProductionDate(Objects.nonNull(sourceQuota.getProductionDate())
                    ? sourceQuota.getProductionDate() : entry.getKey());
            quota.setDayPlanQty(Math.max(0, sourceQuota.getDayPlanQty()));
            quota.setRemainingQty(Math.max(0, sourceQuota.getRemainingQty()));
            simulationQuotaMap.put(entry.getKey(), quota);
        }
        return simulationQuotaMap;
    }

    /**
     * 解析新增排产模拟使用的日计划账本。
     * <p>SKU提前生产准入通过后，只在当前加机台模拟中使用按实际提前天数前移的临时日计划视图；
     * 不回写月计划，也不替换 SKU 原始 {@code dailyPlanQuotaMap}。</p>
     *
     * @param context 排程上下文
     * @param sku SKU
     * @param currentProductionDate 当前候选机台生效业务日
     * @param windowEndDate 排程窗口结束日
     * @return 模拟来源账本
     */
    private Map<LocalDate, SkuDailyPlanQuotaDTO> resolveEarlyProductionSimulationQuotaMap(
            LhScheduleContext context,
            SkuScheduleDTO sku,
            LocalDate currentProductionDate,
            LocalDate windowEndDate) {
        Map<LocalDate, SkuDailyPlanQuotaDTO> sourceQuotaMap =
                Objects.isNull(sku) ? null : sku.getDailyPlanQuotaMap();
        if (Objects.isNull(context) || Objects.isNull(sku) || Objects.isNull(currentProductionDate)
                || Objects.isNull(windowEndDate) || CollectionUtils.isEmpty(sourceQuotaMap)) {
            return sourceQuotaMap;
        }
        EarlyProductionDecision decision = EarlyProductionChecker.checkEarlyProduction(
                context, sku, currentProductionDate, resolveScheduleWindowStartLocalDate(context),
                windowEndDate, resolveNewSpecShortageAddMachineThreshold(context));
        if (Objects.isNull(decision) || !decision.isAllowed() || !decision.isEarlyProduction()) {
            return sourceQuotaMap;
        }
        Map<LocalDate, SkuDailyPlanQuotaDTO> shiftedQuotaMap =
                SkuDailyPlanQuotaUtil.buildShiftedEarlyProductionQuotaMap(
                        sourceQuotaMap, currentProductionDate, windowEndDate, decision.getFuturePlanDate());
        if (CollectionUtils.isEmpty(shiftedQuotaMap)) {
            return sourceQuotaMap;
        }
        log.info("新增SKU提前生产模拟使用前移日计划视图, materialCode: {}, currentDate: {}, "
                        + "futurePlanDate: {}, originalWindowPlanQty: {}, shiftedWindowPlanQty: {}",
                sku.getMaterialCode(), currentProductionDate, decision.getFuturePlanDate(),
                sumSimulationWindowMonthPlanQty(sourceQuotaMap), sumSimulationWindowMonthPlanQty(shiftedQuotaMap));
        return shiftedQuotaMap;
    }

    /**
     * 解析提前生产模拟剩余目标量。
     * <p>提前生产日计划前移后，加机台判断必须按前移后的 T～T+2 临时计划量计算，避免继续使用 0,46,46 误判。</p>
     *
     * @param sku SKU
     * @param simulationSourceQuotaMap 模拟来源账本
     * @param remainingTargetQty 原剩余目标量
     * @return 模拟剩余目标量
     */
    private int resolveEffectiveSimulationRemainingTargetQty(SkuScheduleDTO sku,
                                                             Map<LocalDate, SkuDailyPlanQuotaDTO> simulationSourceQuotaMap,
                                                             int remainingTargetQty) {
        int targetQty = Math.max(0, remainingTargetQty);
        if (Objects.isNull(sku) || simulationSourceQuotaMap == sku.getDailyPlanQuotaMap()) {
            return targetQty;
        }
        return Math.max(targetQty, SkuDailyPlanQuotaUtil.sumRemainingQty(simulationSourceQuotaMap));
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
     * 汇总新增排产模拟窗口内的原始月计划量。
     *
     * @param quotaMap 日计划额度账本
     * @param windowEndDate 窗口结束日
     * @return 窗口内原始月计划量汇总
     */
    private int sumSimulationWindowMonthPlanQty(Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap,
                                                LocalDate windowEndDate) {
        if (CollectionUtils.isEmpty(quotaMap)) {
            return 0;
        }
        int planQty = 0;
        for (Map.Entry<LocalDate, SkuDailyPlanQuotaDTO> entry : quotaMap.entrySet()) {
            if (Objects.isNull(entry.getKey()) || isAfterSimulationWindowEnd(entry.getKey(), windowEndDate)
                    || Objects.isNull(entry.getValue())) {
                continue;
            }
            planQty += Math.max(0, entry.getValue().getDayPlanQty());
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
                || !StringUtils.equals(StringUtils.trimToEmpty(sku.getProductStatus()),
                StringUtils.trimToEmpty(result.getProductStatus()))
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
        // 试制SKU换模需在早班完成，不受开产模式限制
        switchReadyTime = ShiftProductionControlUtil.resolveEarliestSwitchStartTime(
                context, switchReadyTime, sku);
        switchReadyTime = alignNewSpecSwitchReadyTimeToWindowStart(context, shifts, switchReadyTime);
        int switchDurationHours = maintenanceOverlapSwitch
                ? LhScheduleTimeUtil.getMaintenanceOverlapSwitchHours(context)
                : LhScheduleTimeUtil.getMouldChangeTotalHours(context);
        Date mouldChangeStartTime = switchReadyTime;
        Date mouldChangeCompleteTime = LhScheduleTimeUtil.addHours(mouldChangeStartTime, switchDurationHours);
        boolean plannedRepairAffectingSwitch = ShiftCapacityResolverUtil.isPlannedRepairAffectingSwitch(
                context, context.getDevicePlanShutList(), candidate.getMachineCode(), endingTime,
                mouldChangeStartTime, mouldChangeCompleteTime);
        Date firstInspectionBaseTime = plannedRepairAffectingSwitch
                ? ShiftCapacityResolverUtil.resolvePlannedRepairProductionReadyTime(
                context, context.getDevicePlanShutList(), candidate.getMachineCode(), endingTime,
                mouldChangeStartTime, mouldChangeCompleteTime)
                : mouldChangeCompleteTime;
        // 候选模拟必须与最终落地一致：维修命中时预热完成即可首检/生产，不额外增加首检小时。
        Date productionStartTime = plannedRepairAffectingSwitch
                ? firstInspectionBaseTime : maintenanceOverlapSwitch
                ? LhScheduleTimeUtil.addHours(
                mouldChangeCompleteTime, LhScheduleTimeUtil.getFirstInspectionHours(context))
                : mouldChangeCompleteTime;
        productionStartTime = FirstInspectionQtyUtil.resolveTrialProductionStartTime(
                context, sku, shifts, firstInspectionBaseTime, productionStartTime,
                ScheduleTypeEnum.NEW_SPEC.getCode());
        if (productionStartTime == null) {
            return capacityMap;
        }
        LhShiftConfigVO firstInspectionAttributionShift = FirstInspectionQtyUtil.resolveFirstInspectionAttributionShift(
                context, sku, shifts, firstInspectionBaseTime, ScheduleTypeEnum.NEW_SPEC.getCode());
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
                context, sku, shifts, firstInspectionAttributionShift, shiftCapacityMap, runtimeShiftCapacity,
                sku.resolveTargetScheduleQty(), ScheduleTypeEnum.NEW_SPEC.getCode(), candidate.getMachineCode());
        shiftCapacityMap = applyDailyStandardCapacityAdjust(
                context, sku, candidate.getMachineCode(), shifts, shiftCapacityMap, runtimeShiftCapacity);
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
                            + "窗口后剩余欠产: {}, 后一天计划: {}, 后一天正式日硫化标准产能: {}, 累计需求: {}, "
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
     * <p>该方法只在当前候选作用域内选机：试制单模仅传入单控单边，试制双模与量试/小批量
     * 由上层先传入单控候选组，该组全部失败后再单独传入普通机台候选组，不会混合抢占。</p>
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
        // 当前作用域同时包含单控候选时，试制/量试SKU只在该单控组内执行单机收完判断。
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
            if (!endingJudgmentStrategy.isStructureEndingForPriority(context, pendingSku)) {
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
        boolean isEnding = endingJudgmentStrategy.isExpectedEnding(context, sku);
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
     * 记录新增候选机台通过换模推导后被计划量回裁的短过程日志。
     *
     * @param context 排程上下文
     * @param sku 当前SKU
     * @param machineCode 候选机台
     * @param reason 排除原因
     * @param machineReadyTime 机台就绪时间
     * @param switchReadyTime 可切换时间
     * @param mouldChangeStartTime 换模开始时间
     * @param mouldChangeCompleteTime 换模完成时间
     * @param productionStartTime 开产时间
     * @param maxQtyToWindowEnd 窗口最大可排量
     * @param machinePlanQty 本机台计划量
     * @param machineScheduledQty 日计划回裁后排产量
     */
    private void appendNewSpecCandidateRejectedProcessLog(LhScheduleContext context,
                                                          SkuScheduleDTO sku,
                                                          String machineCode,
                                                          String reason,
                                                          Date machineReadyTime,
                                                          Date switchReadyTime,
                                                          Date mouldChangeStartTime,
                                                          Date mouldChangeCompleteTime,
                                                          Date productionStartTime,
                                                          Integer maxQtyToWindowEnd,
                                                          Integer machinePlanQty,
                                                          Integer machineScheduledQty) {
        if (Objects.isNull(context) || Objects.isNull(sku) || StringUtils.isEmpty(machineCode)) {
            return;
        }
        String detail = new StringBuilder(320)
                .append("scheduleDate=").append(LhScheduleTimeUtil.formatDate(context.getScheduleTargetDate()))
                .append(", materialCode=").append(sku.getMaterialCode())
                .append(", machineCode=").append(machineCode)
                .append(", dailyStandardQty=").append(resolveNewSpecDailyStandardQty(context, sku))
                .append(", machineReadyTime=").append(PriorityTraceLogHelper.formatDateTime(machineReadyTime))
                .append(", switchReadyTime=").append(PriorityTraceLogHelper.formatDateTime(switchReadyTime))
                .append(", mouldChangeStartTime=").append(PriorityTraceLogHelper.formatDateTime(mouldChangeStartTime))
                .append(", mouldChangeCompleteTime=").append(PriorityTraceLogHelper.formatDateTime(mouldChangeCompleteTime))
                .append(", productionStartTime=").append(PriorityTraceLogHelper.formatDateTime(productionStartTime))
                .append(", maxQtyToWindowEnd=").append(PriorityTraceLogHelper.safeText(maxQtyToWindowEnd))
                .append(", machinePlanQty=").append(PriorityTraceLogHelper.safeText(machinePlanQty))
                .append(", machineScheduledQty=").append(PriorityTraceLogHelper.safeText(machineScheduledQty))
                .append(", reason=").append(PriorityTraceLogHelper.safeText(reason))
                .toString();
        PriorityTraceLogHelper.appendProcessLog(context, "新增候选机台回裁跳过", detail);
    }

    /**
     * 记录新增排产因当前日优先 dayN 节奏满足而停止扩机台的决策。
     * <p>日志同时输出已有同物料机台及排程来源、月计划日节奏和理论日产能，
     * 用于证明普通空闲候选和续作释放尾部候选均未进入后续扩机判断。</p>
     *
     * @param context 排程上下文
     * @param sku 当前新增SKU
     * @param candidateMachineCode 当前待判断或刚完成排产的机台编码
     * @param businessTargetQty 本轮业务目标量
     * @param scheduledQty 本轮累计已排量
     * @param reason 停止扩机台原因
     */
    private void appendNewSpecDailyRhythmStopProcessLog(LhScheduleContext context,
                                                        SkuScheduleDTO sku,
                                                        String candidateMachineCode,
                                                        int businessTargetQty,
                                                        int scheduledQty,
                                                        String reason) {
        if (Objects.isNull(context) || Objects.isNull(sku)) {
            return;
        }
        String existingMachineSummary = resolveExistingSameMaterialMachineSummary(context, sku);
        String detail = new StringBuilder(384)
                .append("scheduleDate=").append(LhScheduleTimeUtil.formatDate(context.getScheduleTargetDate()))
                .append(", materialCode=").append(sku.getMaterialCode())
                .append(", candidateMachineCode=").append(PriorityTraceLogHelper.safeText(candidateMachineCode))
                .append(", existingMachines=").append(existingMachineSummary)
                .append(", dayN=").append(resolveDailyPlanRhythmSummary(sku))
                .append(", dailyStandardQty=").append(resolveNewSpecDailyStandardQty(context, sku))
                .append(", theoreticalMachineCount=")
                .append(countExistingSameMaterialResults(context, sku, null))
                .append(", businessTargetQty=").append(businessTargetQty)
                .append(", scheduledQty=").append(scheduledQty)
                .append(", stopAddMachine=true")
                .append(", enterReleasedTailCandidate=false")
                .append(", reason=").append(PriorityTraceLogHelper.safeText(reason))
                .toString();
        PriorityTraceLogHelper.appendProcessLog(context, "新增dayN满足停止扩机台", detail);
    }

    /**
     * 汇总当前 SKU 已落地的有效机台及其排程来源。
     *
     * @param context 排程上下文
     * @param sku 当前SKU
     * @return 机台编码/排程类型列表；无有效结果时返回短横线
     */
    private String resolveExistingSameMaterialMachineSummary(LhScheduleContext context, SkuScheduleDTO sku) {
        if (Objects.isNull(context) || Objects.isNull(sku)
                || CollectionUtils.isEmpty(context.getScheduleResultList())) {
            return "-";
        }
        StringBuilder summaryBuilder = new StringBuilder(64);
        Set<String> addedMachineCodes = new LinkedHashSet<String>(4);
        for (LhScheduleResult result : context.getScheduleResultList()) {
            if (!isExistingSameMaterialActiveResult(context, result, sku, null)
                    || !addedMachineCodes.add(result.getLhMachineCode())) {
                continue;
            }
            if (summaryBuilder.length() > 0) {
                summaryBuilder.append(",");
            }
            summaryBuilder.append(result.getLhMachineCode())
                    .append("/")
                    .append(PriorityTraceLogHelper.safeText(result.getScheduleType()));
        }
        return summaryBuilder.length() > 0 ? summaryBuilder.toString() : "-";
    }

    /**
     * 汇总当前 SKU 月计划 dayN 日计划量。
     *
     * @param sku 当前SKU
     * @return 生产日期=日计划量列表；无账本时返回短横线
     */
    private String resolveDailyPlanRhythmSummary(SkuScheduleDTO sku) {
        if (Objects.isNull(sku) || CollectionUtils.isEmpty(sku.getDailyPlanQuotaMap())) {
            return "-";
        }
        StringBuilder summaryBuilder = new StringBuilder(96);
        for (Map.Entry<LocalDate, SkuDailyPlanQuotaDTO> entry : sku.getDailyPlanQuotaMap().entrySet()) {
            if (summaryBuilder.length() > 0) {
                summaryBuilder.append(",");
            }
            summaryBuilder.append(entry.getKey())
                    .append("=")
                    .append(Objects.nonNull(entry.getValue()) ? entry.getValue().getDayPlanQty() : 0);
        }
        return summaryBuilder.toString();
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
        return buildNewSpecScheduleResult(context, machine, sku, startTime, mouldChangeStartTime,
                mouldChangeEndTime, shifts, mouldQty, isEnding, mouldResourceAllocationResult,
                Collections.<Integer, Integer>emptyMap());
    }

    /**
     * 构建新增规格排程结果，并按修正后的班次上限分配计划量。
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
                                                         MouldResourceAllocationResult mouldResourceAllocationResult,
                                                         Map<Integer, Integer> shiftPlanCapacityMap) {
        LhShiftConfigVO firstInspectionAttributionShift = FirstInspectionQtyUtil.resolveFirstInspectionAttributionShift(
                context, sku, shifts, mouldChangeEndTime, ScheduleTypeEnum.NEW_SPEC.getCode());
        return buildNewSpecScheduleResult(context, machine, sku, startTime, mouldChangeStartTime,
                mouldChangeEndTime, shifts, mouldQty, isEnding, mouldResourceAllocationResult,
                shiftPlanCapacityMap, firstInspectionAttributionShift);
    }

    /**
     * 构建新增规格排程结果，并按修正后的班次上限和首检归属班次分配计划量。
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
                                                         MouldResourceAllocationResult mouldResourceAllocationResult,
                                                         Map<Integer, Integer> shiftPlanCapacityMap,
                                                         LhShiftConfigVO firstInspectionAttributionShift) {
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
        // 日标准产量：复用上下文 SKU 日硫化产能主数据，无主数据则为 0
        result.setStandardCapacity(ShiftCapacityResolverUtil.resolveDailyStandardQty(
                context, sku.getMaterialCode()));
        // 默认非提前生产，命中后由 appendEarlyProductionRemark 与备注同源置 1
        result.setIsEarlyProduction("0");
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

        // 按班次分配计划量；试制SKU早班换模后首检任务归属中班，但不生成首检条数，8小时换模耗时不再额外增加。
        int pendingQty = sku.resolveTargetScheduleQty();
        // 构建结果分班前过滤清洗窗口：清洗+换模不额外扣产能，3天内可收尾SKU不安排清洗。
        List<MachineCleaningWindowDTO> cleaningWindowList = resolveEffectiveCleaningWindowList(
                context, result.getLhMachineCode(), sku, mouldChangeStartTime, startTime);
        List<MachineMaintenanceWindowDTO> maintenanceWindowList = resolveMachineMaintenanceWindowList(
                context, result.getLhMachineCode());
        distributeToShifts(context, result, shifts, startTime,
                runtimeShiftCapacity, sku.getLhTimeSeconds(), mouldQty, pendingQty, cleaningWindowList,
                maintenanceWindowList, sku, isEnding, mouldChangeEndTime, shiftPlanCapacityMap,
                firstInspectionAttributionShift);
        boolean plannedRepairAffectingSwitch = ShiftCapacityResolverUtil.isPlannedRepairAffectingSwitch(
                context, context.getDevicePlanShutList(), result.getLhMachineCode(), machine.getEstimatedEndTime(),
                mouldChangeStartTime, mouldChangeEndTime);
        if (plannedRepairAffectingSwitch && Objects.nonNull(firstInspectionAttributionShift)) {
            /*
             * 首检工具写首检条数时会先使用标准班次起点；维修场景必须再对齐到
             * max(维修结束, 换模结束)+预热，避免最终结果看起来在预热完成前已经开始首检。
             */
            Date firstInspectionReadyTime = ShiftCapacityResolverUtil.resolvePlannedRepairProductionReadyTime(
                    context, context.getDevicePlanShutList(), result.getLhMachineCode(), machine.getEstimatedEndTime(),
                    mouldChangeStartTime, mouldChangeEndTime);
            ShiftFieldUtil.alignShiftStartTimeNotBefore(
                    result, firstInspectionAttributionShift.getShiftIndex(), firstInspectionReadyTime);
        }
        refreshResultSummary(context, result);
        return result;
    }

    /**
     * 构建双模 SKU 单控整机配对侧排程结果。
     * <p>配对侧必须与主侧保持相同 SKU、相同开产/结束时间和相同班次计划量；
     * 因此先复制主侧已完成的班次分配，再仅替换机台、左右模、工单号和实际模具号。</p>
     *
     * @param context 排程上下文
     * @param primaryResult 主侧结果
     * @param pairMachine 配对侧机台
     * @param sku 当前SKU
     * @param mouldQty 模台数
     * @param pairAllocationResult 配对侧模具分配结果
     * @return 配对侧结果
     */
    private LhScheduleResult buildWholeSingleControlPairResult(LhScheduleContext context,
                                                               LhScheduleResult primaryResult,
                                                               MachineScheduleDTO pairMachine,
                                                               SkuScheduleDTO sku,
                                                               int mouldQty,
                                                               MouldResourceAllocationResult pairAllocationResult) {
        if (Objects.isNull(primaryResult) || Objects.isNull(pairMachine)) {
            return null;
        }
        LhScheduleResult pairResult = new LhScheduleResult();
        BeanUtil.copyProperties(primaryResult, pairResult);
        pairResult.setOrderNo(generateOrderNo(context));
        pairResult.setLhMachineCode(pairMachine.getMachineCode());
        pairResult.setLhMachineName(pairMachine.getMachineName());
        pairResult.setLeftRightMould(LeftRightMouldUtil.resolveLeftRightMould(
                pairResult.getLeftRightMould(), pairMachine.getMachineCode()));
        pairResult.setMachineOrder(pairMachine.getMachineOrder());
        pairResult.setMouldCode(resolveActualMouldCodeForNewSpecResult(
                context, sku, pairMachine, mouldQty, pairAllocationResult));
        // 主侧已代表物理整机完成一次换胶囊判断，配对侧只复制计划量，不重复展示换胶囊备注。
        for (int shiftIndex = 1; shiftIndex <= LhScheduleConstant.MAX_SHIFT_SLOT_COUNT; shiftIndex++) {
            ShiftFieldUtil.removeShiftAnalysis(
                    pairResult, shiftIndex, CapsuleReplacementRuleService.CAPSULE_REPLACEMENT_ANALYSIS);
        }
        refreshResultSummary(context, pairResult);
        return pairResult;
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
        /*
         * 前日交替计划反选结果固定的是“机台+SKU”关系。普通多机台收口可能清空辅助机台、
         * 归集尾量或把收尾尾量搬到另一台，都会破坏该固定关系；只要当前SKU存在反选保护结果，
         * 本轮跳过同SKU后置搬量。结果数量和时间仍由前面的真实主链计算，不复制历史时间。
         */
        for (LhScheduleResult result : sameSkuResults) {
            if (context.isHistoricalReverseProtectedResult(result)) {
                log.info("新增SKU同SKU多机台收口跳过, materialCode: {}, protectedMachine: {}, "
                                + "reason: 前日交替计划机台反选结果需保持机台与SKU关系",
                        sku.getMaterialCode(), result.getLhMachineCode());
                return;
            }
        }
        String beforeSummary = buildSameSkuAllocationSummary(sameSkuResults);
        boolean tailConcentrated = false;
        boolean auxiliaryReleased = false;
        boolean staggered = false;
        if (isEnding) {
            tailConcentrated = concentrateEndingTailWithinSameShift(context, sku, shifts, sameSkuResults);
        }
        // 双模 L/R 是同一物理排产组，不能进入按单台结果释放的辅助机台链路。
        List<LhScheduleResult> independentResults = resolveIndependentPostProcessResults(
                context, sku, sameSkuResults);
        if (!isEnding && independentResults.size() >= 2 && quantityPolicy != null
                && quantityPolicy.isAllowFillStartedShift()
                && !quantityPolicy.isStrictUpperLimit()) {
            auxiliaryReleased = releaseAuxiliaryMachineForNonEnding(
                    context, sku, shifts, quantityPolicy, independentResults);
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

    /**
     * 增机台失败后回填已成功机台的尾部有效产能。
     * <p>动态拆量先给后续候选机台预留产量；当后续候选因模具、换模或窗口资源失败时，
     * 已在机 SKU 应继续吃满当前机台可用尾部产能。回填通过增量结果复用实际消费账本和 dayN 节奏扣账，
     * 避免绕开硫化余量、收尾目标量和日计划审计。</p>
     *
     * @param context 排程上下文
     * @param sku SKU
     * @param result 已成功落地的排程结果
     * @param segment 已成功机台的生产段
     * @param shifts 排程窗口班次
     * @param quantityPolicy 数量策略
     * @param remainingQty 多机台拆量剩余量
     * @return 实际回填量
     */
    private int refillScheduledResultAfterAddMachineFailure(LhScheduleContext context,
                                                            SkuScheduleDTO sku,
                                                            LhScheduleResult result,
                                                            MachineProductionSegment segment,
                                                            List<LhShiftConfigVO> shifts,
                                                            ProductionQuantityPolicy quantityPolicy,
                                                            int remainingQty) {
        if (Objects.isNull(context) || Objects.isNull(sku) || Objects.isNull(result)
                || Objects.isNull(segment) || CollectionUtils.isEmpty(shifts) || remainingQty <= 0) {
            return 0;
        }
        if (!StringUtils.equals(sku.getMaterialCode(), result.getMaterialCode())
                || !StringUtils.equals(StringUtils.trimToEmpty(sku.getProductStatus()),
                StringUtils.trimToEmpty(result.getProductStatus()))
                || !StringUtils.equals(result.getLhMachineCode(), segment.getMachineCode())
                || CollectionUtils.isEmpty(segment.getShiftCapacityMap())) {
            return 0;
        }
        if (isWholeSingleControlResult(context, sku, result)) {
            // 当前回填段只描述单侧机台产能，无法证明配对侧在相同班次仍有等量尾部产能。
            // 双模组宁可保留剩余量给后续排程，也不能只增加一侧而破坏 L/R 同步。
            log.info("新增SKU双模组跳过单侧尾部回填, materialCode: {}, machineCode: {}, pairMachineCode: {}, "
                            + "remainingQty: {}, reason: 缺少配对侧等量尾部产能段",
                    sku.getMaterialCode(), result.getLhMachineCode(),
                    LhSingleControlMachineUtil.resolvePairMachineCode(result.getLhMachineCode()), remainingQty);
            return 0;
        }
        int currentScheduledQty = ShiftFieldUtil.resolveScheduledQty(result);
        int availableTailQty = Math.max(0, segment.getMaxQtyToWindowEnd() - currentScheduledQty);
        int refillLimitQty = Math.min(Math.max(0, remainingQty), availableTailQty);
        if (refillLimitQty <= 0) {
            return 0;
        }
        LhScheduleResult deltaResult = buildAddMachineFailureRefillDeltaResult(result);
        int deltaQty = allocateRefillDeltaToShifts(context, sku, deltaResult, result, segment, shifts, refillLimitQty);
        if (deltaQty <= 0) {
            return 0;
        }
        int actualRefillQty = applyBlockToDailyQuota(context, sku, deltaResult, shifts);
        if (actualRefillQty <= 0) {
            return 0;
        }
        mergeRefillDeltaResult(result, deltaResult, shifts);
        refreshResultSummary(context, result);
        MachineScheduleDTO machine = context.getMachineScheduleMap().get(result.getLhMachineCode());
        if (Objects.nonNull(machine)) {
            updateMachineState(context, machine, sku, result);
        }
        recordScheduledMachineForResult(context, result, shifts, false);
        log.info("新增SKU增机台候选失败后回填原机台尾部产能, materialCode: {}, machineCode: {}, "
                        + "refillLimitQty: {}, actualRefillQty: {}, beforeQty: {}, afterQty: {}, remainingQty: {}, "
                        + "strictUpperLimit: {}",
                sku.getMaterialCode(), result.getLhMachineCode(), refillLimitQty, actualRefillQty,
                currentScheduledQty, ShiftFieldUtil.resolveScheduledQty(result), remainingQty,
                quantityPolicy != null && quantityPolicy.isStrictUpperLimit());
        return actualRefillQty;
    }

    /**
     * 构建增机台失败回填的增量结果。
     *
     * @param sourceResult 原排程结果
     * @return 增量结果
     */
    private LhScheduleResult buildAddMachineFailureRefillDeltaResult(LhScheduleResult sourceResult) {
        LhScheduleResult deltaResult = new LhScheduleResult();
        deltaResult.setMaterialCode(sourceResult.getMaterialCode());
        deltaResult.setMaterialDesc(sourceResult.getMaterialDesc());
        deltaResult.setStructureName(sourceResult.getStructureName());
        deltaResult.setSpecCode(sourceResult.getSpecCode());
        deltaResult.setSpecDesc(sourceResult.getSpecDesc());
        deltaResult.setEmbryoCode(sourceResult.getEmbryoCode());
        deltaResult.setLhMachineCode(sourceResult.getLhMachineCode());
        deltaResult.setScheduleType(sourceResult.getScheduleType());
        deltaResult.setIsEnd(sourceResult.getIsEnd());
        deltaResult.setIsChangeMould(sourceResult.getIsChangeMould());
        deltaResult.setIsTypeBlock(sourceResult.getIsTypeBlock());
        deltaResult.setMouldQty(sourceResult.getMouldQty());
        deltaResult.setSingleMouldShiftQty(sourceResult.getSingleMouldShiftQty());
        deltaResult.setLhTime(sourceResult.getLhTime());
        return deltaResult;
    }

    /**
     * 将回填增量分配到原机台仍有空余的班次。
     *
     * @param context 排程上下文
     * @param sku SKU
     * @param deltaResult 增量结果
     * @param sourceResult 原排程结果
     * @param segment 原机台生产段
     * @param shifts 班次列表
     * @param refillLimitQty 最大回填量
     * @return 增量分配量
     */
    private int allocateRefillDeltaToShifts(LhScheduleContext context,
                                            SkuScheduleDTO sku,
                                            LhScheduleResult deltaResult,
                                            LhScheduleResult sourceResult,
                                            MachineProductionSegment segment,
                                            List<LhShiftConfigVO> shifts,
                                            int refillLimitQty) {
        int mouldQty = ShiftCapacityResolverUtil.resolveMachineMouldQty(
                sourceResult.getMouldQty() == null ? 0 : sourceResult.getMouldQty());
        if (mouldQty <= 0) {
            return 0;
        }
        int remainingRefillQty = Math.max(0, refillLimitQty);
        int allocatedQty = 0;
        for (LhShiftConfigVO shift : shifts) {
            if (remainingRefillQty <= 0 || Objects.isNull(shift) || Objects.isNull(shift.getShiftIndex())) {
                break;
            }
            Integer shiftCapacity = segment.getShiftCapacityMap().get(shift.getShiftIndex());
            if (Objects.isNull(shiftCapacity) || shiftCapacity <= 0) {
                continue;
            }
            Integer existingQty = ShiftFieldUtil.getShiftPlanQty(sourceResult, shift.getShiftIndex());
            int currentQty = existingQty == null ? 0 : Math.max(0, existingQty);
            int availableQty = Math.max(0, shiftCapacity - currentQty);
            if (availableQty <= 0) {
                continue;
            }
            int shiftRefillQty = ShiftCapacityResolverUtil.normalizeAllocatedShiftQty(
                    Math.min(remainingRefillQty, availableQty), availableQty, mouldQty);
            if (shiftRefillQty <= 0) {
                continue;
            }
            if (!canIncreaseShiftQtyByClassTotalLimit(context, sku, sourceResult, shift.getShiftIndex(), shiftRefillQty,
                    "新增SKU增机台失败后原机台回填")) {
                continue;
            }
            // 回填是在原结果已有产量基础上的真实增量，必须用原结果判断胶囊次数并承载换胶囊备注。
            shiftRefillQty = capsuleReplacementRuleService.resolveActualPlanQty(
                    context, sourceResult, shift, shiftRefillQty, mouldQty,
                    "新增SKU增机台失败后原机台回填");
            if (shiftRefillQty <= 0) {
                continue;
            }
            Date shiftStartTime = currentQty > 0
                    ? ShiftFieldUtil.getShiftStartTime(sourceResult, shift.getShiftIndex())
                    : shift.getShiftStartDateTime();
            setShiftPlanQty(deltaResult, shift.getShiftIndex(), shiftRefillQty,
                    shiftStartTime, shift.getShiftEndDateTime());
            remainingRefillQty -= shiftRefillQty;
            allocatedQty += shiftRefillQty;
        }
        ShiftFieldUtil.syncDailyPlanQty(deltaResult);
        return allocatedQty;
    }

    /**
     * 将通过扣账后的回填增量合并回原排程结果。
     *
     * @param targetResult 原排程结果
     * @param deltaResult 增量结果
     * @param shifts 班次列表
     */
    private void mergeRefillDeltaResult(LhScheduleResult targetResult,
                                        LhScheduleResult deltaResult,
                                        List<LhShiftConfigVO> shifts) {
        for (LhShiftConfigVO shift : shifts) {
            if (Objects.isNull(shift) || Objects.isNull(shift.getShiftIndex())) {
                continue;
            }
            Integer deltaQty = ShiftFieldUtil.getShiftPlanQty(deltaResult, shift.getShiftIndex());
            if (Objects.isNull(deltaQty) || deltaQty <= 0) {
                continue;
            }
            Integer existingQty = ShiftFieldUtil.getShiftPlanQty(targetResult, shift.getShiftIndex());
            Date existingStartTime = ShiftFieldUtil.getShiftStartTime(targetResult, shift.getShiftIndex());
            Date deltaStartTime = ShiftFieldUtil.getShiftStartTime(deltaResult, shift.getShiftIndex());
            Date deltaEndTime = ShiftFieldUtil.getShiftEndTime(deltaResult, shift.getShiftIndex());
            int mergedQty = Math.max(0, existingQty == null ? 0 : existingQty) + deltaQty;
            setShiftPlanQty(targetResult, shift.getShiftIndex(), mergedQty,
                    existingStartTime == null ? deltaStartTime : existingStartTime, deltaEndTime);
        }
        ShiftFieldUtil.syncDailyPlanQty(targetResult);
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
        Map<LhScheduleResult, Integer> targetShiftQtyMap = new LinkedHashMap<LhScheduleResult, Integer>(sortedResults.size());
        Set<String> processedWholeMachineCodeSet = new HashSet<String>(4);
        int remainingToAllocate = remainingQty;
        boolean changed = false;
        for (LhScheduleResult result : sortedResults) {
            Integer originalQty = ShiftFieldUtil.getShiftPlanQty(result, endingShiftIndex);
            if (originalQty == null || originalQty <= 0) {
                continue;
            }
            if (isWholeSingleControlResult(context, sku, result)) {
                String physicalMachineCode = LhSingleControlMachineUtil.resolvePhysicalMachineCode(
                        result.getLhMachineCode());
                if (!processedWholeMachineCodeSet.add(physicalMachineCode)) {
                    continue;
                }
                LhScheduleResult pairResult = findPairResult(results, result);
                if (Objects.isNull(pairResult)) {
                    // 配对侧缺失交由保存前强校验阻断，不能把缺侧结果继续当普通单机归集。
                    continue;
                }
                Integer pairOriginalQty = ShiftFieldUtil.getShiftPlanQty(pairResult, endingShiftIndex);
                int resolvedPairOriginalQty = Objects.isNull(pairOriginalQty) ? 0 : Math.max(0, pairOriginalQty);
                originalShiftQtyMap.put(result, originalQty);
                originalShiftQtyMap.put(pairResult, resolvedPairOriginalQty);
                int groupShiftCapacity = resolveWholeSingleControlShiftCapacity(
                        context, result, pairResult, endingShift);
                int groupTargetQty = Math.min(Math.max(0, groupShiftCapacity), Math.max(0, remainingToAllocate));
                // 双模组总量必须可均分到 L/R，奇数尾量留给其他独立机台或后续滚动排程。
                groupTargetQty -= groupTargetQty % 2;
                int sideTargetQty = groupTargetQty / 2;
                targetShiftQtyMap.put(result, sideTargetQty);
                targetShiftQtyMap.put(pairResult, sideTargetQty);
                remainingToAllocate = Math.max(0, remainingToAllocate - groupTargetQty);
                changed = changed || sideTargetQty != originalQty
                        || sideTargetQty != resolvedPairOriginalQty;
                continue;
            }
            originalShiftQtyMap.put(result, originalQty);
            int shiftCapacity = Math.max(originalQty, Math.max(
                    resolveAvailableShiftQtyForEndingStagger(context, result, endingShift),
                    resolveResultBaseShiftCapacity(result)));
            int newQty = Math.min(Math.max(0, shiftCapacity), Math.max(0, remainingToAllocate));
            targetShiftQtyMap.put(result, newQty);
            remainingToAllocate = Math.max(0, remainingToAllocate - newQty);
            changed = changed || newQty != originalQty;
        }
        if (!changed) {
            return false;
        }
        if (!canApplyShiftTargetQtyByClassTotalLimit(context, sku, endingShiftIndex, targetShiftQtyMap,
                "新增SKU收尾同班次尾量归集")) {
            return false;
        }
        for (Map.Entry<LhScheduleResult, Integer> targetEntry : targetShiftQtyMap.entrySet()) {
            int newQty = Math.max(0, targetEntry.getValue() == null ? 0 : targetEntry.getValue());
            setShiftPlanQty(targetEntry.getKey(), endingShiftIndex, newQty,
                    newQty > 0 ? endingShift.getShiftStartDateTime() : null, null);
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
        // 双模组不能作为普通 donor/receiver 单侧转移尾量，仅保留可独立处理的普通机台或单模结果。
        List<LhScheduleResult> sameSkuEndingResults = resolveIndependentPostProcessResults(
                context, sku, collectSameSkuNewSpecResults(context, sku, null));
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
        if (!canIncreaseShiftQtyByClassTotalLimit(context, sku, receiver, nextShift.getShiftIndex(), donorQty,
                "同SKU多机台机台收尾尾量错开")) {
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
                        || !StringUtils.equals(StringUtils.trimToEmpty(sku.getProductStatus()),
                        StringUtils.trimToEmpty(result.getProductStatus()))
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
                && StringUtils.equals(StringUtils.trimToEmpty(sku.getProductStatus()),
                StringUtils.trimToEmpty(currentResult.getProductStatus()))
                && !sameSkuResults.contains(currentResult)) {
            sameSkuResults.add(currentResult);
        }
        return sameSkuResults;
    }

    /**
     * 过滤只能按物理组处理的双模 L/R 结果，供普通单机释放和错峰逻辑使用。
     *
     * @param context 排程上下文
     * @param sku 当前SKU
     * @param results 同SKU结果
     * @return 可按独立机台处理的结果
     */
    private List<LhScheduleResult> resolveIndependentPostProcessResults(LhScheduleContext context,
                                                                         SkuScheduleDTO sku,
                                                                         List<LhScheduleResult> results) {
        List<LhScheduleResult> independentResults = new ArrayList<LhScheduleResult>(
                CollectionUtils.isEmpty(results) ? 0 : results.size());
        if (CollectionUtils.isEmpty(results)) {
            return independentResults;
        }
        for (LhScheduleResult result : results) {
            if (!isWholeSingleControlResult(context, sku, result)) {
                independentResults.add(result);
            }
        }
        return independentResults;
    }

    /**
     * 判断结果是否属于当前SKU冻结的双模单控物理组。
     *
     * @param context 排程上下文
     * @param sku 当前SKU
     * @param result 排程结果
     * @return true-必须与配对侧同步处理
     */
    private boolean isWholeSingleControlResult(LhScheduleContext context,
                                               SkuScheduleDTO sku,
                                               LhScheduleResult result) {
        return Objects.nonNull(context)
                && Objects.nonNull(sku)
                && Objects.nonNull(result)
                && LhSingleControlMachineUtil.isWholeMachineGranularitySku(context, sku)
                && LhSingleControlMachineUtil.isConfiguredSingleControlMachine(
                context, result.getLhMachineCode());
    }

    /**
     * 从同一批结果中查找当前单控侧的配对侧结果。
     *
     * @param results 同SKU结果
     * @param currentResult 当前侧结果
     * @return 配对侧结果；不存在时返回null
     */
    private LhScheduleResult findPairResult(List<LhScheduleResult> results,
                                            LhScheduleResult currentResult) {
        if (CollectionUtils.isEmpty(results) || Objects.isNull(currentResult)) {
            return null;
        }
        String pairMachineCode = LhSingleControlMachineUtil.resolvePairMachineCode(
                currentResult.getLhMachineCode());
        for (LhScheduleResult candidate : results) {
            if (candidate != currentResult
                    && Objects.nonNull(candidate)
                    && StringUtils.equals(pairMachineCode, candidate.getLhMachineCode())
                    && StringUtils.equals(currentResult.getMaterialCode(), candidate.getMaterialCode())) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * 计算双模物理组在指定班次可承接的两侧合计量。
     *
     * @param context 排程上下文
     * @param primaryResult 主侧结果
     * @param pairResult 配对侧结果
     * @param shift 当前班次
     * @return L/R两侧合计可排量
     */
    private int resolveWholeSingleControlShiftCapacity(LhScheduleContext context,
                                                       LhScheduleResult primaryResult,
                                                       LhScheduleResult pairResult,
                                                       LhShiftConfigVO shift) {
        int primaryCapacity = Math.max(resolveResultBaseShiftCapacity(primaryResult),
                resolveAvailableShiftQtyForEndingStagger(context, primaryResult, shift));
        int pairCapacity = Math.max(resolveResultBaseShiftCapacity(pairResult),
                resolveAvailableShiftQtyForEndingStagger(context, pairResult, shift));
        return Math.max(0, primaryCapacity) + Math.max(0, pairCapacity);
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
        int plannedRepairFixedQty = context.getParamIntValue(
                LhScheduleParamConstant.PLANNED_REPAIR_FIXED_QTY, LhScheduleConstant.PLANNED_REPAIR_FIXED_QTY);
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
                ScheduleTypeEnum.NEW_SPEC.getCode(),
                plannedRepairFixedQty);
        int capacityBeforeCapsuleReplacement = Math.max(0,
                ShiftProductionControlUtil.deductCapacityByControl(
                        control, shiftMaxQty, result.getMouldQty()));
        // 收尾错峰及跨班补量必须保留正式落班时已发生的换胶囊固定产能损失。
        return capsuleReplacementRuleService.resolveReplacementShiftCapacityUpperLimit(
                context, result, targetShift, capacityBeforeCapsuleReplacement);
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
        Integer currentShiftExistingQty = ShiftFieldUtil.getShiftPlanQty(result, currentShift.getShiftIndex());
        int currentShiftBeforeQty = currentShiftExistingQty == null ? 0 : Math.max(0, currentShiftExistingQty);
        int currentShiftAvailableQty = Math.max(0,
                resolveAvailableShiftQtyForEndingStagger(context, result, currentShift) - currentShiftBeforeQty);
        // 晚班不可换模衔接时，当前中班仍可生产的产能先补满，再承接下一晚班。
        int currentShiftFillQty = Math.min(currentShiftAvailableQty, realSurplusRemainingQty);
        int beforeFillRealSurplusRemainingQty = realSurplusRemainingQty;
        if (currentShiftFillQty > 0) {
            if (canIncreaseShiftQtyByClassTotalLimit(context, sku, result, currentShift.getShiftIndex(),
                    currentShiftFillQty, "晚班不可换模当前班次补量")) {
                // 当前班补量先执行换胶囊扣减，扣减差额继续保留在真实余量中供下一晚班排产。
                currentShiftFillQty = capsuleReplacementRuleService.resolveActualPlanQty(
                        context, result, currentShift, currentShiftFillQty,
                        Objects.isNull(result.getMouldQty()) ? 1 : result.getMouldQty(),
                        "新增排产不可换模当前班补量");
                Date currentShiftStartTime = ShiftFieldUtil.getShiftStartTime(result, currentShift.getShiftIndex());
                setShiftPlanQty(result, currentShift.getShiftIndex(), currentShiftBeforeQty + currentShiftFillQty,
                        currentShiftStartTime == null ? currentShift.getShiftStartDateTime() : currentShiftStartTime,
                        currentShift.getShiftEndDateTime());
                realSurplusRemainingQty = Math.max(0, realSurplusRemainingQty - currentShiftFillQty);
            } else {
                currentShiftFillQty = 0;
            }
        }
        Integer existingQty = ShiftFieldUtil.getShiftPlanQty(result, nextShift.getShiftIndex());
        int currentQty = existingQty == null ? 0 : Math.max(0, existingQty);
        int availableQty = Math.max(0, resolveAvailableShiftQtyForEndingStagger(context, result, nextShift) - currentQty);
        int fillQty = Math.min(availableQty, realSurplusRemainingQty);
        if (fillQty <= 0 && currentShiftFillQty <= 0) {
            return currentQty > 0 || currentShiftBeforeQty > 0;
        }
        if (fillQty > 0) {
            if (canIncreaseShiftQtyByClassTotalLimit(context, sku, result, nextShift.getShiftIndex(), fillQty,
                    "晚班不可换模晚班补量")) {
                // 下一晚班同样属于正式落班增量，实际余量只消费换胶囊扣减后的数量。
                fillQty = capsuleReplacementRuleService.resolveActualPlanQty(
                        context, result, nextShift, fillQty,
                        Objects.isNull(result.getMouldQty()) ? 1 : result.getMouldQty(),
                        "新增排产不可换模晚班补量");
                setShiftPlanQty(result, nextShift.getShiftIndex(), currentQty + fillQty,
                        nextShift.getShiftStartDateTime(), null);
            } else {
                fillQty = 0;
            }
        }
        int nightShiftAfterQty = currentQty + fillQty;
        refreshResultSummary(context, result);
        log.info("晚班不可换模续作补满命中, materialCode: {}, 机台: {}, 当前收尾班次: {}, 晚班班次: {}, "
                        + "当前班次补前: {}, 当前班次补后: {}, 晚班补前: {}, 晚班补后: {}, "
                        + "补满数量: {}, 真实余量剩余: {}, 原因: 晚班不可换模且当前SKU可无换模续作",
                sku.getMaterialCode(), result.getLhMachineCode(), lastShiftIndex, nextShift.getShiftIndex(),
                currentShiftBeforeQty, currentShiftBeforeQty + currentShiftFillQty, currentQty, nightShiftAfterQty,
                currentShiftFillQty + fillQty, beforeFillRealSurplusRemainingQty);
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
                        || !StringUtils.equals(sku.getMaterialCode(), result.getMaterialCode())
                        || !StringUtils.equals(StringUtils.trimToEmpty(sku.getProductStatus()),
                        StringUtils.trimToEmpty(result.getProductStatus()))) {
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
        // 按物料状态复合键汇总新增结果，避免S/T/X共用收尾目标。
        Map<String, Integer> skuTotalPlanQtyMap = new LinkedHashMap<>(16);
        Map<String, SkuScheduleDTO> skuMap = new LinkedHashMap<>(16);
        for (LhScheduleResult result : context.getScheduleResultList()) {
            if (result == null || !NEW_SPEC_SCHEDULE_TYPE.equals(result.getScheduleType())) {
                continue;
            }
            String materialCode = result.getMaterialCode();
            if (StringUtils.isEmpty(materialCode)) {
                continue;
            }
            String skuKey = MonthPlanDateResolver.buildMaterialStatusKey(
                    materialCode, result.getProductStatus());
            int planQty = resolveResultScheduledQty(result);
            skuTotalPlanQtyMap.merge(skuKey, planQty, Integer::sum);
            if (!skuMap.containsKey(skuKey)) {
                skuMap.put(skuKey, findSkuDto(context, materialCode, result.getProductStatus()));
            }
        }
        // 基于汇总计划量统一设置同物料所有结果的收尾标记
        for (LhScheduleResult result : context.getScheduleResultList()) {
            if (result == null || !NEW_SPEC_SCHEDULE_TYPE.equals(result.getScheduleType())
                    || StringUtils.isEmpty(result.getMaterialCode())) {
                continue;
            }
            String skuKey = MonthPlanDateResolver.buildMaterialStatusKey(
                    result.getMaterialCode(), result.getProductStatus());
            int totalPlanQty = skuTotalPlanQtyMap.getOrDefault(skuKey, 0);
            SkuScheduleDTO sku = skuMap.get(skuKey);
            result.setIsEnd(endingJudgmentStrategy.isFinalEnding(context, sku, totalPlanQty) ? "1" : "0");
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
        SkuScheduleDTO sku = findSkuDto(
                context, result.getMaterialCode(), result.getProductStatus());
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
     * 兼容原有分班入口，并按换模完成时刻解析首检归属班次。
     * <p>主流程已经显式传入维修预热后的首检归属班次；该重载保留给既有调用方及回归测试，
     * 未命中计划性维修时与改造前行为完全一致。</p>
     *
     * @param context 排程上下文
     * @param result 排程结果
     * @param shifts 排程班次
     * @param startTime 正式生产开始时间
     * @param shiftCapacity 标准班产
     * @param lhTimeSeconds 硫化周期秒数
     * @param mouldQty 模台数
     * @param remaining 待排量
     * @param cleaningWindowList 清洗窗口
     * @param maintenanceWindowList 保养窗口
     * @param sku SKU排程DTO
     * @param isEnding 是否收尾
     * @param mouldChangeCompleteTime 换模完成时间
     * @param shiftPlanCapacityMap 班次计划量上限
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
                                   Date mouldChangeCompleteTime,
                                   Map<Integer, Integer> shiftPlanCapacityMap) {
        LhShiftConfigVO firstInspectionAttributionShift = FirstInspectionQtyUtil
                .resolveFirstInspectionAttributionShift(
                        context, sku, shifts, mouldChangeCompleteTime, ScheduleTypeEnum.NEW_SPEC.getCode());
        return distributeToShifts(context, result, shifts, startTime, shiftCapacity, lhTimeSeconds, mouldQty,
                remaining, cleaningWindowList, maintenanceWindowList, sku, isEnding, mouldChangeCompleteTime,
                shiftPlanCapacityMap, firstInspectionAttributionShift);
    }

    /**
     * 将计划量分配到各班次（从开产时间开始）。
     * <p>试制非收尾SKU会根据日计划额度限制每个班次的排产量；计划性维修场景由调用方传入
     * 预热完成后的首检归属班次，避免维修完成时刻被错误用于首检占班。</p>
     *
     * @param context 排程上下文
     * @param result 排程结果
     * @param shifts 排程班次
     * @param startTime 正式生产开始时间
     * @param shiftCapacity 标准班产
     * @param lhTimeSeconds 硫化周期秒数
     * @param mouldQty 模台数
     * @param remaining 待排量
     * @param cleaningWindowList 清洗窗口
     * @param maintenanceWindowList 保养及容量专用维修窗口
     * @param sku SKU排程DTO（用于获取日计划额度账本和目标量控制标记）
     * @param isEnding 是否收尾
     * @param mouldChangeCompleteTime 换模完成时间，用于首检结果字段回填
     * @param shiftPlanCapacityMap 已按日标准产量修正的班次计划量上限
     * @param firstInspectionAttributionShift 首检归属班次
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
                                   Date mouldChangeCompleteTime,
                                   Map<Integer, Integer> shiftPlanCapacityMap,
                                   LhShiftConfigVO firstInspectionAttributionShift) {
        if (lhTimeSeconds <= 0 || mouldQty <= 0 || remaining <= 0 || startTime == null) {
            return remaining;
        }
        /*
         * 普通换模首检数量归属口径：
         * 1. 换模8小时已包含首检，不额外增加首检时间；
         * 2. 首检只影响数量归属和班产占用；
         * 3. 非试制归属班次由换模完成时间落点决定，试制早班切换后归同业务日中班；
         * 4. 非试制首检数量参与排产量、余量消耗和班产上限校验；试制首检仅通过中班固定2小时上限体现。
         */
        LhShiftConfigVO firstInspectionShift = firstInspectionAttributionShift;
        int previewFirstInspectionQty = FirstInspectionQtyUtil.resolvePreviewFirstInspectionQty(
                context, sku, firstInspectionShift, shiftCapacity, remaining, ScheduleTypeEnum.NEW_SPEC.getCode(),
                result.getLhMachineCode());
        int remainingBeforeFirstInspection = remaining;
        int firstInspectionQty = 0;
        int firstInspectionCapsuleLossQty = 0;
        if (previewFirstInspectionQty > 0 && Objects.nonNull(firstInspectionShift)
                && canIncreaseShiftQtyByClassTotalLimit(context, sku, result, firstInspectionShift.getShiftIndex(),
                previewFirstInspectionQty, "新增排产首检数量归属")) {
            /*
             * 首检条数属于真实生产量，也可能在首检生产过程中跨越胶囊上限。
             * 此处先记录实际扣减量，后续同班常规产量还要同时扣除这部分班产和需求上限，
             * 防止被扣的2条又在同一个班次以常规产量补回；差额必须保留给下一班继续排产。
             */
            int adjustedFirstInspectionQty = capsuleReplacementRuleService.resolveActualPlanQty(
                    context, result, firstInspectionShift, previewFirstInspectionQty, mouldQty,
                    "新增排产首检");
            firstInspectionCapsuleLossQty = Math.max(0,
                    previewFirstInspectionQty - adjustedFirstInspectionQty);
            if (adjustedFirstInspectionQty > 0) {
                firstInspectionQty = FirstInspectionQtyUtil.addFirstInspectionQtyToResult(
                        context, sku, result, firstInspectionShift, mouldChangeCompleteTime, shiftCapacity,
                        adjustedFirstInspectionQty, ScheduleTypeEnum.NEW_SPEC.getCode());
            }
        }
        remaining -= firstInspectionQty;
        Map<Integer, ShiftRuntimeState> stateMap = context.getShiftRuntimeStateMap();
        int dryIceLossQty = context.getParamIntValue(
                LhScheduleParamConstant.DRY_ICE_LOSS_QTY, LhScheduleConstant.DRY_ICE_LOSS_QTY);
        int dryIceDurationHours = context.getParamIntValue(
                LhScheduleParamConstant.DRY_ICE_DURATION_HOURS, LhScheduleConstant.DRY_ICE_DURATION_HOURS);
        int plannedRepairFixedQty = context.getParamIntValue(
                LhScheduleParamConstant.PLANNED_REPAIR_FIXED_QTY, LhScheduleConstant.PLANNED_REPAIR_FIXED_QTY);
        String configPlusShiftType = ShiftCapacityResolverUtil.resolveOddShiftCapacityPlusShiftType(context);

        // 试制非收尾SKU在本轮分配内按日期追踪已消费日计划额度，防止同一天多个班次重复消费。
        // 新增排产仅补欠产场景复用该账本做滚动额度预演，避免窗口日计划为0时跨天班次被误裁。
        Map<LocalDate, Integer> trialDailyConsumedMap = null;
        if (shouldApplyStrictNonEndingQuotaLimit(sku, isEnding)) {
            Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap = sku.getDailyPlanQuotaMap();
            if (quotaMap != null && !quotaMap.isEmpty()) {
                trialDailyConsumedMap = new LinkedHashMap<>(4);
            }
        }

        boolean started = false;
        // SYS0303004仅控制新增SKU起排班次（上机班次），SKU上机后后续班次不再受限制。
        // 首检已排入时不立即标记为上机，需在起排班次循环中完成SYS0303004判断后再标记。
        boolean skuStartedOnMachine = false;
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
                logNewSpecShiftSkip(result, shift, remaining, shiftCapacity, 0,
                        0, "班次管控不可排");
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
                    ScheduleTypeEnum.NEW_SPEC.getCode(),
                    plannedRepairFixedQty);
            shiftMaxQty = ShiftProductionControlUtil.deductCapacityByControl(control, shiftMaxQty, mouldQty);
            shiftMaxQty = FirstInspectionQtyUtil.resolveNormalCapacityAfterFirstInspection(
                    context, sku, shift, shiftMaxQty,
                    Objects.isNull(firstInspectionShift) ? -1 : firstInspectionShift.getShiftIndex(),
                    firstInspectionQty,
                    shiftCapacity, ScheduleTypeEnum.NEW_SPEC.getCode(), result.getLhMachineCode());
            boolean isCurrentShiftFirstInspectionShift = Objects.nonNull(firstInspectionShift)
                    && Objects.equals(firstInspectionShift.getShiftIndex(), shift.getShiftIndex());
            if (isCurrentShiftFirstInspectionShift && firstInspectionCapsuleLossQty > 0) {
                /*
                 * 换胶囊固定占用1小时，首检阶段已经触发时，当前班剩余常规产能仍须扣除同一份损失。
                 * 同时按扣减前需求量封顶，避免余量较小时把已扣数量重新补回当前班次。
                 */
                shiftMaxQty = Math.max(0, shiftMaxQty - firstInspectionCapsuleLossQty);
                int currentShiftDemandCap = Math.max(0,
                        remainingBeforeFirstInspection - firstInspectionQty - firstInspectionCapsuleLossQty);
                shiftMaxQty = Math.min(shiftMaxQty, currentShiftDemandCap);
            }
            int physicalShiftMaxQty = shiftMaxQty;
            Integer dailyStandardShiftLimit = CollectionUtils.isEmpty(shiftPlanCapacityMap)
                    ? null : shiftPlanCapacityMap.get(shift.getShiftIndex());
            if (Objects.nonNull(dailyStandardShiftLimit)) {
                // 模拟、目标量和最终落班统一使用日标准产量修正后的班次上限，班产字段保持原值。
                shiftMaxQty = Math.min(shiftMaxQty, Math.max(0, dailyStandardShiftLimit));
            }
            if (shiftMaxQty <= 0) {
                String skipReason = physicalShiftMaxQty <= 0
                        ? "停机/清洗/保养/首检/班次管控扣减后无可用产能"
                        : "日标准产量修正后无可用产能";
                logNewSpecShiftSkip(result, shift, remaining, shiftCapacity,
                        physicalShiftMaxQty, shiftMaxQty, skipReason);
                continue;
            }

            // SYS0303004起排班次判断：SKU尚未上机时不做剩余容量收敛（不部分填充），
            // 完整班产超限时整体顺延或仅保留首检，避免部分填充导致生产不连续。
            // SKU已上机后不再受SYS0303004限制，直接按班产和原有约束排产。

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
                    logNewSpecShiftSkip(result, shift, remaining, shiftCapacity,
                            physicalShiftMaxQty, shiftMaxQty, "试制非收尾日计划额度账本回裁为0");
                    continue;
                }
            }

            int shiftQty = getTargetScheduleQtyResolver().resolveAllocatedShiftQty(
                    context, sku, Math.min(remaining, shiftMaxQty), shiftMaxQty, mouldQty);
            if (shiftQty > 0) {
                // 起排班次（SKU尚未上机）需判断SYS0303004同班次总计划量上限
                if (!skuStartedOnMachine && !canIncreaseShiftQtyByClassTotalLimit(context, sku, result,
                        shift.getShiftIndex(), shiftQty, "新增排产起排班次判断")) {
                    // 完整班产超过SYS0303004上限，判断首检特殊规则
                    if (firstInspectionQty > 0 && isCurrentShiftFirstInspectionShift) {
                        // 首检已排入当前班次（首检归属班次=当前班次），SKU视为已经上机。
                        // 当前班次仅保留首检计划量，不排常规产量，后续班次不再受SYS0303004限制。
                        skuStartedOnMachine = true;
                        logNewSpecShiftSkip(result, shift, remaining, shiftCapacity,
                                physicalShiftMaxQty, shiftMaxQty,
                                "同班次总计划量上限不足，首检已排入，起排班次仅保留首检");
                        continue;
                    } else if (firstInspectionQty > 0) {
                        // 首检已排入更早班次（首检归属班次 < 当前班次），SKU已经上机。
                        // 当前班次不再受SYS0303004限制，直接排常规产量，避免中间班次空量。
                        skuStartedOnMachine = true;
                        log.info("新增排产首检已排入更早班次，SKU已上机，当前班次跳过SYS0303004限制, "
                                + "batchNo: {}, materialCode: {}, machineCode: {}, classNo: class{}",
                                context.getBatchNo(), result.getMaterialCode(),
                                result.getLhMachineCode(), shift.getShiftIndex());
                    } else {
                        // 无首检排入，当前班次不能作为起排班次，顺延到下一个班次继续判断
                        logNewSpecShiftSkip(result, shift, remaining, shiftCapacity,
                                physicalShiftMaxQty, shiftMaxQty,
                                "同班次总计划量上限不足，起排班次顺延");
                        continue;
                    }
                }
                // 起排上限等既有规则通过后再扣换胶囊产能，避免胶囊规则反向改变SKU起排和选机判断。
                shiftQty = capsuleReplacementRuleService.resolveActualPlanQty(
                        context, result, shift, shiftQty, mouldQty, "新增排产");
                if (shiftQty <= 0) {
                    logNewSpecShiftSkip(result, shift, remaining, shiftCapacity,
                            physicalShiftMaxQty, shiftMaxQty, "换胶囊固定扣减后本班实际排产量为0");
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
                Integer existingQty = ShiftFieldUtil.getShiftPlanQty(result, shift.getShiftIndex());
                Date existingStartTime = ShiftFieldUtil.getShiftStartTime(result, shift.getShiftIndex());
                int mergedQty = Math.max(0, existingQty == null ? 0 : existingQty) + shiftQty;
                setShiftPlanQty(result, shift.getShiftIndex(), mergedQty,
                        existingStartTime == null ? effectiveStart : existingStartTime, shiftPlanEndTime);
                remaining -= shiftQty;
                // SKU已上机（常规排产已写入），后续班次不再受SYS0303004限制
                skuStartedOnMachine = true;

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
            } else {
                logNewSpecShiftSkip(result, shift, remaining, shiftCapacity,
                        physicalShiftMaxQty, shiftMaxQty, "目标量或硫化余量账本回裁为0");
            }
        }
        return remaining;
    }

    /**
     * 记录新增排产班次跳过原因，便于核对已换模上机 SKU 中间空班是否存在硬约束。
     *
     * @param result 新增排程结果
     * @param shift 当前班次
     * @param remaining 当前剩余目标量
     * @param shiftCapacity 原始班产
     * @param physicalShiftMaxQty 停机/清洗/保养/首检/班次管控扣减后的物理可用产能
     * @param finalShiftMaxQty 日标准或日计划账本修正后的最终可排产能
     * @param skipReason 跳过原因
     */
    private void logNewSpecShiftSkip(LhScheduleResult result,
                                     LhShiftConfigVO shift,
                                     int remaining,
                                     int shiftCapacity,
                                     int physicalShiftMaxQty,
                                     int finalShiftMaxQty,
                                     String skipReason) {
        if (Objects.isNull(result) || Objects.isNull(shift)) {
            return;
        }
        log.info("连续排产班次跳过诊断, 当前流程: 新增排产, materialCode: {}, machineCode: {}, 班次: {}, "
                        + "剩余余量: {}, 原始班产: {}, 班次物理可用产能: {}, 最终班次可用产能: {}, "
                        + "是否跳过: {}, 跳过原因: {}",
                result.getMaterialCode(), result.getLhMachineCode(), shift.getShiftIndex(), remaining,
                shiftCapacity, physicalShiftMaxQty, finalShiftMaxQty, true, skipReason);
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
     * 判断当前班次增加指定计划量后是否仍满足同班次总计划量上限。
     *
     * @param context 排程上下文
     * @param sku 当前新增SKU
     * @param result 当前排程结果
     * @param shiftIndex 班次索引
     * @param incrementQty 本次拟增加计划量
     * @param action 业务动作说明
     * @return true-允许写入；false-超过同班次总计划量上限，需要跳过本次写入
     */
    private boolean canIncreaseShiftQtyByClassTotalLimit(LhScheduleContext context,
                                                         SkuScheduleDTO sku,
                                                         LhScheduleResult result,
                                                         Integer shiftIndex,
                                                         int incrementQty,
                                                         String action) {
        if (Objects.isNull(shiftIndex) || incrementQty <= 0) {
            return true;
        }
        int classTotalQtyLimit = resolveClassTotalQtyLimit(context);
        if (classTotalQtyLimit <= 0) {
            return true;
        }
        // 复用剩余容量口径判断增量是否超限，起排班次判断和后置重分配统一使用
        int remainingCapacity = resolveClassTotalRemainingCapacity(context, result, shiftIndex);
        if (remainingCapacity >= incrementQty) {
            return true;
        }
        int currentClassScheduledQty = classTotalQtyLimit - remainingCapacity;
        int projectedQty = currentClassScheduledQty + incrementQty;
        logClassTotalQtyLimitSkip(context, sku, result, shiftIndex, currentClassScheduledQty,
                incrementQty, projectedQty, classTotalQtyLimit, action);
        return false;
    }

    /**
     * 判断一组同班次重分配后的总计划量是否满足同班次总计划量上限。
     *
     * @param context 排程上下文
     * @param sku 当前新增SKU
     * @param shiftIndex 班次索引
     * @param targetQtyMap 重分配后的结果班次量
     * @param action 业务动作说明
     * @return true-允许重分配；false-超过上限，需要保持原分布
     */
    private boolean canApplyShiftTargetQtyByClassTotalLimit(LhScheduleContext context,
                                                            SkuScheduleDTO sku,
                                                            Integer shiftIndex,
                                                            Map<LhScheduleResult, Integer> targetQtyMap,
                                                            String action) {
        if (Objects.isNull(shiftIndex) || CollectionUtils.isEmpty(targetQtyMap)) {
            return true;
        }
        int classTotalQtyLimit = resolveClassTotalQtyLimit(context);
        if (classTotalQtyLimit <= 0) {
            return true;
        }
        int projectedQty = 0;
        if (Objects.nonNull(context) && !CollectionUtils.isEmpty(context.getScheduleResultList())) {
            for (LhScheduleResult scheduleResult : context.getScheduleResultList()) {
                if (Objects.isNull(scheduleResult)) {
                    continue;
                }
                if (targetQtyMap.containsKey(scheduleResult)) {
                    Integer targetQty = targetQtyMap.get(scheduleResult);
                    projectedQty += Math.max(0, Objects.isNull(targetQty) ? 0 : targetQty);
                    continue;
                }
                projectedQty += resolvePositiveShiftQty(scheduleResult, shiftIndex);
            }
        }
        for (Map.Entry<LhScheduleResult, Integer> entry : targetQtyMap.entrySet()) {
            if (isResultPersistedInContext(context, entry.getKey())) {
                continue;
            }
            Integer targetQty = entry.getValue();
            projectedQty += Math.max(0, Objects.isNull(targetQty) ? 0 : targetQty);
        }
        if (projectedQty <= classTotalQtyLimit) {
            return true;
        }
        int currentClassScheduledQty = resolveClassShiftScheduledQty(context, shiftIndex);
        int increaseQty = Math.max(0, projectedQty - currentClassScheduledQty);
        LhScheduleResult logResult = resolveFirstTargetResult(targetQtyMap);
        logClassTotalQtyLimitSkip(context, sku, logResult, shiftIndex,
                currentClassScheduledQty, increaseQty, projectedQty, classTotalQtyLimit, action);
        return false;
    }

    /**
     * 解析同班次重分配日志使用的代表结果。
     *
     * @param targetQtyMap 重分配后的结果班次量
     * @return 用于输出机台号的排程结果
     */
    private LhScheduleResult resolveFirstTargetResult(Map<LhScheduleResult, Integer> targetQtyMap) {
        if (CollectionUtils.isEmpty(targetQtyMap)) {
            return null;
        }
        for (LhScheduleResult result : targetQtyMap.keySet()) {
            if (Objects.nonNull(result)) {
                return result;
            }
        }
        return null;
    }

    /**
     * 解析同班次总计划量上限配置。
     *
     * @param context 排程上下文
     * @return 上限值，<=0 表示不限制
     */
    private int resolveClassTotalQtyLimit(LhScheduleContext context) {
        if (Objects.isNull(context)) {
            return LhScheduleConstant.CLASS_TOTAL_QTY_UP_LIMIT;
        }
        return context.getParamIntValue(LhScheduleParamConstant.CLASS_TOTAL_QTY_UP_LIMIT,
                LhScheduleConstant.CLASS_TOTAL_QTY_UP_LIMIT);
    }

    /**
     * 汇总当前已排结果中指定班次的计划量。
     *
     * @param context 排程上下文
     * @param shiftIndex 班次索引
     * @return 已排总量
     */
    private int resolveClassShiftScheduledQty(LhScheduleContext context, Integer shiftIndex) {
        if (Objects.isNull(context) || Objects.isNull(shiftIndex)
                || CollectionUtils.isEmpty(context.getScheduleResultList())) {
            return 0;
        }
        int totalQty = 0;
        for (LhScheduleResult scheduleResult : context.getScheduleResultList()) {
            totalQty += resolvePositiveShiftQty(scheduleResult, shiftIndex);
        }
        return totalQty;
    }

    /**
     * 解析指定班次的同班次总计划量上限剩余容量。
     * <p>与 {@link #canIncreaseShiftQtyByClassTotalLimit} 统一口径：统计当前排程上下文内
     * 同班次已排总量，未持久化结果需叠加当前结果已有量避免重复计算。
     * 起排班次判断时用于校验完整班产是否超限，不再做部分填充收敛。</p>
     *
     * @param context 排程上下文
     * @param result 当前排程结果
     * @param shiftIndex 班次索引
     * @return 剩余可排容量；上限<=0表示不限制时返回 Integer.MAX_VALUE
     */
    private int resolveClassTotalRemainingCapacity(LhScheduleContext context,
                                                   LhScheduleResult result,
                                                   Integer shiftIndex) {
        int classTotalQtyLimit = resolveClassTotalQtyLimit(context);
        if (classTotalQtyLimit <= 0 || Objects.isNull(shiftIndex)) {
            return Integer.MAX_VALUE;
        }
        int currentClassScheduledQty = resolveClassShiftScheduledQty(context, shiftIndex);
        if (!isResultPersistedInContext(context, result)) {
            currentClassScheduledQty += resolvePositiveShiftQty(result, shiftIndex);
        }
        return Math.max(0, classTotalQtyLimit - currentClassScheduledQty);
    }

    /**
     * 获取结果行指定班次的正向计划量。
     *
     * @param result 排程结果
     * @param shiftIndex 班次索引
     * @return 班次计划量
     */
    private int resolvePositiveShiftQty(LhScheduleResult result, Integer shiftIndex) {
        if (Objects.isNull(result) || Objects.isNull(shiftIndex)) {
            return 0;
        }
        Integer planQty = ShiftFieldUtil.getShiftPlanQty(result, shiftIndex);
        return Math.max(0, Objects.isNull(planQty) ? 0 : planQty);
    }

    /**
     * 判断结果对象是否已经加入当前排程结果列表。
     *
     * @param context 排程上下文
     * @param result 排程结果
     * @return true-已加入；false-尚未加入
     */
    private boolean isResultPersistedInContext(LhScheduleContext context, LhScheduleResult result) {
        if (Objects.isNull(context) || Objects.isNull(result)
                || CollectionUtils.isEmpty(context.getScheduleResultList())) {
            return false;
        }
        for (LhScheduleResult scheduleResult : context.getScheduleResultList()) {
            if (scheduleResult == result) {
                return true;
            }
        }
        return false;
    }

    /**
     * 打印同班次总计划量超限跳过日志。
     */
    private void logClassTotalQtyLimitSkip(LhScheduleContext context,
                                           SkuScheduleDTO sku,
                                           LhScheduleResult result,
                                           Integer shiftIndex,
                                           int currentClassScheduledQty,
                                           int planQty,
                                           int projectedQty,
                                           int classTotalQtyLimit,
                                           String action) {
        log.info("新增排产班次总计划量超过上限，跳过当前班次, batchNo: {}, materialCode: {}, "
                        + "machineCode: {}, classNo: class{}, 已排总量: {}, 拟排量: {}, 预计总量: {}, 上限: {}, 动作: {}",
                Objects.isNull(context) ? null : context.getBatchNo(),
                Objects.nonNull(sku) ? sku.getMaterialCode()
                        : (Objects.isNull(result) ? null : result.getMaterialCode()),
                Objects.isNull(result) ? null : result.getLhMachineCode(), shiftIndex,
                currentClassScheduledQty, planQty, projectedQty, classTotalQtyLimit, action);
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
        Map<String, SkuScheduleDTO> zeroPlanSkuMap = new LinkedHashMap<>(8);
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
            invalidateHistoricalReverseResultAfterPostAdjust(context, result);
            result.setSpecEndTime(null);
            result.setTdaySpecEndTime(null);
            zeroPlanResults.add(result);
            if (StringUtils.isEmpty(result.getMaterialCode())) {
                continue;
            }
            SkuScheduleDTO sku = findSkuDto(
                    context, result.getMaterialCode(), result.getProductStatus());
            int unscheduledQty = resolveRemainingUnscheduledQty(context, sku);
            if (unscheduledQty > 0) {
                String skuKey = MonthPlanDateResolver.buildMaterialStatusKey(
                        result.getMaterialCode(), result.getProductStatus());
                zeroPlanQtyMap.putIfAbsent(skuKey, unscheduledQty);
                zeroPlanSkuMap.putIfAbsent(skuKey, sku);
            }
        }
        for (Map.Entry<String, Integer> entry : zeroPlanQtyMap.entrySet()) {
            mergeUnscheduledResultBySku(context, zeroPlanSkuMap.get(entry.getKey()), entry.getValue());
        }
        if (!CollectionUtils.isEmpty(zeroPlanResults)) {
            context.getScheduleResultList().removeAll(zeroPlanResults);
            removeResultsFromMachineAssignments(context, zeroPlanResults);
        }
        normalizeUnscheduledResultsBySku(context);
    }

    /**
     * 后置胎胚库存裁剪把反选结果裁为0时，撤销成功登记并记录最终失败。
     *
     * <p>反选不得突破胎胚库存硬约束。结果在主链落地时可能暂时成功，但后置物料级库存统一
     * 裁剪后若变为0，最终关系已经不存在，必须撤销保护和机台登记，避免后续误判为反选成功。</p>
     *
     * @param context 排程上下文
     * @param result 被裁为0的新增结果
     */
    private void invalidateHistoricalReverseResultAfterPostAdjust(
            LhScheduleContext context,
            LhScheduleResult result) {
        if (!context.isHistoricalReverseProtectedResult(result)) {
            return;
        }
        HistoricalReverseSelectionDirective directive = null;
        for (HistoricalReverseSelectionDirective currentDirective
                : context.getHistoricalReverseSelectionDirectiveList()) {
            if (StringUtils.equals(currentDirective.getMaterialCode(), result.getMaterialCode())
                    && StringUtils.equals(normalizeHistoricalReverseProductStatus(
                    currentDirective.getProductStatus()),
                    normalizeHistoricalReverseProductStatus(result.getProductStatus()))
                    && StringUtils.equals(currentDirective.getEffectiveMachineCode(),
                    result.getLhMachineCode())) {
                directive = currentDirective;
                break;
            }
        }
        if (Objects.nonNull(directive)) {
            directive.setSuccess(false);
            directive.setResultReason("反选结果被胎胚库存后置硬约束裁剪为0");
            context.unregisterHistoricalReverseSelectedMachine(
                    directive.getMaterialCode(), directive.getProductStatus(), directive.getMachineCode());
            appendHistoricalReverseNewSpecLog(
                    context, directive, "失败", directive.getResultReason());
        }
        context.unprotectHistoricalReverseResult(result);
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
            // 完工时间重算沿用结果级清洗过滤，避免被已跳过的清洗窗口再次顺延。
            List<MachineCleaningWindowDTO> cleaningWindowList = resolveEffectiveCleaningWindowList(
                    context, result, resolveFirstPlannedShiftStartTime(result));
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

    private List<MachineCleaningWindowDTO> resolveMachineCleaningWindowList(LhScheduleContext context, String machineCode) {
        MachineScheduleDTO machine = context.getMachineScheduleMap().get(machineCode);
        if (machine == null || CollectionUtils.isEmpty(machine.getCleaningWindowList())) {
            return new ArrayList<>();
        }
        return machine.getCleaningWindowList();
    }

    private List<MachineMaintenanceWindowDTO> resolveMachineMaintenanceWindowList(LhScheduleContext context, String machineCode) {
        MachineScheduleDTO machine = context.getMachineScheduleMap().get(machineCode);
        List<MachineMaintenanceWindowDTO> maintenanceWindowList = machine == null
                ? new ArrayList<>() : machine.getMaintenanceWindowList();
        // 新增规格的候选产能、目标量预演和最终落点统一扣除维修及SYS0307009预热窗口。
        return ShiftCapacityResolverUtil.resolveCapacityMaintenanceWindowList(
                context, context.getDevicePlanShutList(), machineCode, maintenanceWindowList);
    }

    /**
     * 获取机台真实精度保养窗口，仅供最终停机摘要展示。
     *
     * @param context 排程上下文
     * @param machineCode 机台编号
     * @return 真实精度保养窗口，不包含计划性维修容量窗口
     */
    private List<MachineMaintenanceWindowDTO> resolveActualMachineMaintenanceWindowList(
            LhScheduleContext context, String machineCode) {
        MachineScheduleDTO machine = context.getMachineScheduleMap().get(machineCode);
        return machine == null || CollectionUtils.isEmpty(machine.getMaintenanceWindowList())
                ? new ArrayList<>() : machine.getMaintenanceWindowList();
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
        List<LhShiftConfigVO> scheduleWindowShifts = context.getScheduleWindowShifts();
        ResultDowntimeSummaryUtil.fillDowntimeSummary(
                result,
                resolveActualMachineMaintenanceWindowList(context, result.getLhMachineCode()),
                resolveEffectiveCleaningWindowList(context, result, firstPlannedShiftStartTime),
                resolveMachineShutdownWindowList(context, result.getLhMachineCode()),
                scheduleWindowShifts);
        Date mouldChangeCompleteTime = Objects.nonNull(result.getMouldChangeStartTime())
                ? LhScheduleTimeUtil.addHours(result.getMouldChangeStartTime(),
                LhScheduleTimeUtil.getMouldChangeTotalHours(context)) : firstPlannedShiftStartTime;
        // 新增换模与清洗重叠时清洗不额外占用时间，但必须用原始清洗窗口按真实换模 8 小时窗口补充“清洗+换模”原因备注。
        ResultDowntimeSummaryUtil.appendCleaningMouldChangeAnalysis(
                result,
                resolveMachineCleaningWindowList(context, result.getLhMachineCode()),
                result.getMouldChangeStartTime(),
                mouldChangeCompleteTime,
                scheduleWindowShifts);
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
                                                                              SkuScheduleDTO sku,
                                                                              Date switchStartTime,
                                                                              Date firstProductionStartTime) {
        List<MachineCleaningWindowDTO> cleaningWindowList = resolveMachineCleaningWindowList(context, machineCode);
        if (CleaningScheduleRuleUtil.shouldSkipCleaningBySkuEnding(context, sku)) {
            return new ArrayList<>(0);
        }
        return new ArrayList<>(MachineCleaningOverlapUtil.excludeOverlapWindows(
                cleaningWindowList, switchStartTime, firstProductionStartTime));
    }

    /**
     * 解析新增结果在排产阶段需要生效的清洗窗口。
     *
     * @param context 排程上下文
     * @param result 排程结果
     * @param firstProductionStartTime 首个可排产开始时间
     * @return 有效清洗窗口列表
     */
    private List<MachineCleaningWindowDTO> resolveEffectiveCleaningWindowList(LhScheduleContext context,
                                                                              LhScheduleResult result,
                                                                              Date firstProductionStartTime) {
        if (Objects.isNull(result)) {
            return new ArrayList<>(0);
        }
        List<MachineCleaningWindowDTO> cleaningWindowList = resolveMachineCleaningWindowList(
                context, result.getLhMachineCode());
        if (CleaningScheduleRuleUtil.shouldSkipCleaningByResultEnding(result)) {
            return new ArrayList<>(0);
        }
        Date switchEndTime = resolveCleaningSwitchEndTime(context, result, firstProductionStartTime);
        return new ArrayList<>(MachineCleaningOverlapUtil.excludeOverlapWindows(
                cleaningWindowList, result.getMouldChangeStartTime(), switchEndTime));
    }

    /**
     * 解析清洗与切换重叠过滤使用的切换结束时间。
     *
     * <p>新增换模首检可能落在换模开始班次，导致首个有计划量班次早于真实换模完成时间；
     * 清洗+换模只能按真实 8 小时换模窗口判断，不能用首检班次开始时间截断。</p>
     *
     * @param context 排程上下文
     * @param result 排程结果
     * @param firstProductionStartTime 首个有计划量班次开始时间
     * @return 清洗重叠过滤使用的切换结束时间
     */
    private Date resolveCleaningSwitchEndTime(LhScheduleContext context,
                                              LhScheduleResult result,
                                              Date firstProductionStartTime) {
        if (Objects.nonNull(result) && Objects.nonNull(result.getMouldChangeStartTime())) {
            return LhScheduleTimeUtil.addHours(result.getMouldChangeStartTime(),
                    LhScheduleTimeUtil.getMouldChangeTotalHours(context));
        }
        return firstProductionStartTime;
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
            String actionType = isEarlyProductionTargetDayMouldChange(context, sku, switchReadyTime)
                    ? IMouldChangeBalanceStrategy.ACTION_EARLY_PRODUCTION_NEW_SPEC_MOULD_CHANGE
                    : IMouldChangeBalanceStrategy.ACTION_NEW_SPEC_MOULD_CHANGE;
            return mouldChangeBalance.allocateMouldChange(
                    context, machineCode, switchReadyTime, switchDurationHours,
                    sku, actionType);
        }
        return allocateBasicMouldChangeStartTime(context, machineCode, switchReadyTime, switchDurationHours);
    }

    /**
     * 判断当前换模是否为后续日计划提前到目标业务日的首台新增换模。
     *
     * @param context 排程上下文
     * @param sku 当前 SKU
     * @param switchReadyTime 换模就绪时间
     * @return true-提前生产目标日首台换模；false-普通新增换模
     */
    private boolean isEarlyProductionTargetDayMouldChange(LhScheduleContext context,
                                                          SkuScheduleDTO sku,
                                                          Date switchReadyTime) {
        if (Objects.isNull(context) || Objects.isNull(sku) || Objects.isNull(switchReadyTime)
                || CollectionUtils.isEmpty(sku.getDailyPlanQuotaMap())) {
            return false;
        }
        LocalDate productionDate = resolveProductionWorkDate(context.getScheduleWindowShifts(), switchReadyTime);
        LocalDate windowStartDate = resolveScheduleWindowStartLocalDate(context);
        if (Objects.isNull(productionDate) || Objects.isNull(windowStartDate)
                || !windowStartDate.equals(productionDate)) {
            return false;
        }
        SkuDailyPlanQuotaDTO currentQuota = sku.getDailyPlanQuotaMap().get(productionDate);
        if (hasSchedulableDailyPlanQuota(sku, currentQuota)) {
            return false;
        }
        EarlyProductionDecision earlyProductionDecision = resolveEarlyProductionDecision(
                context, sku, switchReadyTime, context.getScheduleWindowShifts(), false);
        return isAllowedFuturePlanEarlyProduction(earlyProductionDecision);
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
     * <p>05计划性维修允许与换模并行，后续统一按最大结束时间追加SYS0307009预热；
     * 其他停机类型继续顺延，确保关闭换模均衡开关时也与启用路径保持一致。</p>
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
                    || StringUtils.equals(MachineStopTypeEnum.PLANNED_REPAIR.getCode(),
                    planShut.getMachineStopType())
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
     * @param sku 来源SKU
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
        Date retentionEndTime = Objects.nonNull(machine)
                ? context.getStructureMinMachineRetentionEndTimeMap().get(machine.getMachineCode()) : null;
        occupationEndTime = resolveLaterTime(occupationEndTime, retentionEndTime);
        if (Objects.nonNull(occupationEndTime)) {
            return occupationEndTime;
        }
        return resolveDefaultMachineEndTime(context, shifts);
    }

    /**
     * 按 SKU 粒度解析新增换模接续起点。
     * <p>正规 SKU 使用单控机台时，左右侧必须作为整机同步占用，因此最早接续时间取 L/R 两侧
     * 预计结束时间和当前已登记结果结束时间中的较晚值；单边粒度 SKU 仍沿用当前侧时间。</p>
     *
     * @param context 排程上下文
     * @param sku 当前SKU
     * @param machine 候选机台
     * @param shifts 排程窗口班次
     * @return 机台已占用结束时间
     */
    private Date resolveMachineOccupationEndTime(LhScheduleContext context,
                                                 SkuScheduleDTO sku,
                                                 MachineScheduleDTO machine,
                                                 List<LhShiftConfigVO> shifts) {
        Date currentSideEndTime = resolveMachineOccupationEndTime(context, machine, shifts);
        if (!LhSingleControlMachineUtil.isWholeMachineGranularitySku(context, sku)
                || Objects.isNull(machine)
                || !isSingleControlMachine(context, machine.getMachineCode())) {
            return currentSideEndTime;
        }
        MachineScheduleDTO pairMachine = LhSingleControlMachineUtil.resolvePairMachine(context, machine.getMachineCode());
        Date pairSideEndTime = resolveMachineOccupationEndTime(context, pairMachine, shifts);
        return resolveLaterTime(currentSideEndTime, pairSideEndTime);
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
        unscheduled.setProductStatus(sku.getProductStatus());
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
        // 命中胎胚库存硬目标的新增SKU进入未排后，必须退出运行态有效集合，触发同胎胚剩余SKU二次分摊。
        if (getTargetScheduleQtyResolver().isEmbryoStockEnding(context, sku)) {
            getTargetScheduleQtyResolver().removeActiveEmbryoSku(context, sku, reason);
        }
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
     * 将排产块的班次数量同步到SKU实际消费账本和dayN节奏账本。
     * <p>先按SKU实际消费账本裁剪结果，避免同物料多入口重复消费；再按班次归属日期消费dayN节奏额度。
     * 如果班次产能大于dayN节奏剩余额度，非收尾结果保留实际排产量并记录满班补齐超排量。</p>
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
        int cappedQty = getTargetScheduleQtyResolver().capResultByProductionRemainingQty(
                context, sku, result, shifts, "新增排产");
        if (cappedQty <= 0) {
            return 0;
        }
        Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap = sku.getDailyPlanQuotaMap();
        if (quotaMap == null || quotaMap.isEmpty()) {
            refreshResultSummary(context, result);
            int actualQty = result.getDailyPlanQty() != null ? result.getDailyPlanQty() : 0;
            getTargetScheduleQtyResolver().deductProductionRemainingQty(
                    context, sku, actualQty, "新增排产", result.getLhMachineCode());
            return actualQty;
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
            // 按 dayN 节奏账本消费当日与允许追补窗口，仅用于节奏判断和满班补齐超排记录
            int consumed = SkuDailyPlanQuotaUtil.consumeRollingQuota(
                    quotaMap, productionDate, planQty, resolveLookAheadEndDate(context, quotaMap, productionDate));
            int overQty = planQty - consumed;
            if (overQty > 0) {
                boolean endingResult = "1".equals(result.getIsEnd());
                // 收尾结果必须严格截断，且不再记录满班补齐超排；
                // 试制等严格目标量场景仍需回裁，但保留超排账本用于追踪被截掉的补满量。
                if (endingResult || shouldApplyStrictNonEndingQuotaLimit(sku, endingResult)) {
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
            String skuKey = MonthPlanDateResolver.buildMaterialStatusKey(
                    sku.getMaterialCode(), sku.getProductStatus());
            context.getSkuShiftFillOverQtyMap().merge(skuKey, totalShiftFillOverQty, Integer::sum);
        }
        refreshResultSummary(context, result);
        int actualQty = result.getDailyPlanQty() != null ? result.getDailyPlanQty() : 0;
        getTargetScheduleQtyResolver().deductProductionRemainingQty(
                context, sku, actualQty, "新增排产", result.getLhMachineCode());
        return actualQty;
    }

    /**
     * 将双模 SKU 单控整机结果同步消费到日计划和实际排产账本。
     * <p>整机排产必须保证 L/R 两边班次计划量完全一致，因此先构造一条“整机组结果”
     * 按两边合计量做账本裁剪和日计划扣减，再把裁剪后的组数量均分回两侧。
     * 如果账本只允许奇数尾量，为了保证左右一致，按可成对的偶数量落地，剩余 1 条留给后续未排/滚动处理。</p>
     *
     * @param context 排程上下文
     * @param sku 当前SKU
     * @param primaryResult 主侧结果
     * @param pairResult 配对侧结果
     * @param shifts 排程窗口班次
     * @return L/R 两侧合计实际排产量
     */
    private int applyWholeSingleControlBlockToDailyQuota(LhScheduleContext context,
                                                         SkuScheduleDTO sku,
                                                         LhScheduleResult primaryResult,
                                                         LhScheduleResult pairResult,
                                                         List<LhShiftConfigVO> shifts) {
        if (Objects.isNull(primaryResult) || Objects.isNull(pairResult)) {
            return 0;
        }
        LhScheduleResult groupResult = buildWholeSingleControlGroupResult(primaryResult);
        int cappedQty = getTargetScheduleQtyResolver().capResultByProductionRemainingQty(
                context, sku, groupResult, shifts, "新增排产-单控整机");
        if (cappedQty <= 0) {
            copyWholeSingleControlGroupQtyToSides(context, groupResult, primaryResult, pairResult);
            return 0;
        }
        Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap = sku.getDailyPlanQuotaMap();
        if (CollectionUtils.isEmpty(quotaMap)) {
            copyWholeSingleControlGroupQtyToSides(context, groupResult, primaryResult, pairResult);
            int actualQty = resolveWholeSingleControlActualQty(context, primaryResult, pairResult);
            getTargetScheduleQtyResolver().deductProductionRemainingQty(
                    context, sku, actualQty, "新增排产-单控整机", primaryResult.getLhMachineCode());
            return actualQty;
        }
        int totalShiftFillOverQty = 0;
        for (LhShiftConfigVO shift : shifts) {
            Integer groupPlanQty = ShiftFieldUtil.getShiftPlanQty(groupResult, shift.getShiftIndex());
            if (Objects.isNull(groupPlanQty) || groupPlanQty <= 0 || Objects.isNull(shift.getWorkDate())) {
                continue;
            }
            LocalDate productionDate = shift.getWorkDate().toInstant()
                    .atZone(ZoneId.systemDefault()).toLocalDate();
            SkuDailyPlanQuotaDTO quota = quotaMap.get(productionDate);
            if (Objects.isNull(quota)) {
                continue;
            }
            int consumed = SkuDailyPlanQuotaUtil.consumeRollingQuota(
                    quotaMap, productionDate, groupPlanQty, resolveLookAheadEndDate(context, quotaMap, productionDate));
            int overQty = groupPlanQty - consumed;
            if (overQty > 0) {
                boolean endingResult = StringUtils.equals("1", primaryResult.getIsEnd());
                if (endingResult || shouldApplyStrictNonEndingQuotaLimit(sku, endingResult)) {
                    // 单控整机必须左右一致，严格回裁时只保留可均分到两侧的偶数量。
                    int pairedConsumedQty = consumed - consumed % 2;
                    trimShiftPlanQty(groupResult, shift.getShiftIndex(), pairedConsumedQty);
                    if (endingResult) {
                        continue;
                    }
                }
                quota.setShiftFillOverQty(quota.getShiftFillOverQty() + overQty);
                totalShiftFillOverQty += overQty;
                log.debug("单控整机班次满班补齐超排, materialCode: {}, 日期: {}, 班次: {}, 整机排产量: {}, 超排: {}",
                        sku.getMaterialCode(), productionDate, shift.getShiftIndex(), groupPlanQty, overQty);
            }
        }
        if (totalShiftFillOverQty > 0) {
            sku.setShiftFillOverQty(sku.getShiftFillOverQty() + totalShiftFillOverQty);
            String skuKey = MonthPlanDateResolver.buildMaterialStatusKey(
                    sku.getMaterialCode(), sku.getProductStatus());
            context.getSkuShiftFillOverQtyMap().merge(skuKey, totalShiftFillOverQty, Integer::sum);
        }
        copyWholeSingleControlGroupQtyToSides(context, groupResult, primaryResult, pairResult);
        int actualQty = resolveWholeSingleControlActualQty(context, primaryResult, pairResult);
        getTargetScheduleQtyResolver().deductProductionRemainingQty(
                context, sku, actualQty, "新增排产-单控整机", primaryResult.getLhMachineCode());
        return actualQty;
    }

    /**
     * 构建单控整机组结果，班次计划量为左右两侧合计量。
     *
     * @param sideResult 单侧结果
     * @return 整机组结果
     */
    private LhScheduleResult buildWholeSingleControlGroupResult(LhScheduleResult sideResult) {
        LhScheduleResult groupResult = new LhScheduleResult();
        BeanUtil.copyProperties(sideResult, groupResult);
        for (int shiftIndex = 1; shiftIndex <= LhScheduleConstant.MAX_SHIFT_SLOT_COUNT; shiftIndex++) {
            Integer sideQty = ShiftFieldUtil.getShiftPlanQty(sideResult, shiftIndex);
            if (Objects.isNull(sideQty) || sideQty <= 0) {
                ShiftFieldUtil.setShiftPlanQty(groupResult, shiftIndex, sideQty,
                        ShiftFieldUtil.getShiftStartTime(sideResult, shiftIndex),
                        ShiftFieldUtil.getShiftEndTime(sideResult, shiftIndex));
                continue;
            }
            ShiftFieldUtil.setShiftPlanQty(groupResult, shiftIndex, sideQty * 2,
                    ShiftFieldUtil.getShiftStartTime(sideResult, shiftIndex),
                    ShiftFieldUtil.getShiftEndTime(sideResult, shiftIndex));
        }
        ShiftFieldUtil.syncDailyPlanQty(groupResult);
        return groupResult;
    }

    /**
     * 将整机组班次数量同步回 L/R 两侧。
     *
     * @param context 排程上下文
     * @param groupResult 整机组结果
     * @param primaryResult 主侧结果
     * @param pairResult 配对侧结果
     */
    private void copyWholeSingleControlGroupQtyToSides(LhScheduleContext context,
                                                       LhScheduleResult groupResult,
                                                       LhScheduleResult primaryResult,
                                                       LhScheduleResult pairResult) {
        for (int shiftIndex = 1; shiftIndex <= LhScheduleConstant.MAX_SHIFT_SLOT_COUNT; shiftIndex++) {
            Integer groupQty = ShiftFieldUtil.getShiftPlanQty(groupResult, shiftIndex);
            int sideQty = Objects.isNull(groupQty) || groupQty <= 0 ? 0 : groupQty / 2;
            Date shiftStartTime = sideQty > 0 ? ShiftFieldUtil.getShiftStartTime(groupResult, shiftIndex) : null;
            Date shiftEndTime = sideQty > 0 ? ShiftFieldUtil.getShiftEndTime(groupResult, shiftIndex) : null;
            ShiftFieldUtil.setShiftPlanQty(primaryResult, shiftIndex, sideQty, shiftStartTime, shiftEndTime);
            ShiftFieldUtil.setShiftPlanQty(pairResult, shiftIndex, sideQty, shiftStartTime, shiftEndTime);
        }
        refreshResultSummary(context, primaryResult);
        refreshResultSummary(context, pairResult);
    }

    /**
     * 解析单控整机两侧合计实际排产量。
     *
     * @param context 排程上下文
     * @param primaryResult 主侧结果
     * @param pairResult 配对侧结果
     * @return 两侧合计排产量
     */
    private int resolveWholeSingleControlActualQty(LhScheduleContext context,
                                                   LhScheduleResult primaryResult,
                                                   LhScheduleResult pairResult) {
        refreshResultSummary(context, primaryResult);
        refreshResultSummary(context, pairResult);
        int primaryQty = Objects.nonNull(primaryResult.getDailyPlanQty()) ? primaryResult.getDailyPlanQty() : 0;
        int pairQty = Objects.nonNull(pairResult.getDailyPlanQty()) ? pairResult.getDailyPlanQty() : 0;
        return primaryQty + pairQty;
    }

    /**
     * 判断非收尾结果是否需要按严格日计划额度回裁。
     * <p>正式 SKU 可能因结构收尾判断带上 strict 标记，但最终结果仍是非收尾；
     * 这类场景应保留满班补齐量，避免单机可满足时被日计划账本裁空后续班次。</p>
     *
     * @param sku SKU排程DTO
     * @param endingResult 当前结果是否收尾
     * @return true-非收尾也需要严格回裁；false-允许保留满班补齐量
     */
    private boolean shouldApplyStrictNonEndingQuotaLimit(SkuScheduleDTO sku, boolean endingResult) {
        if (Objects.isNull(sku) || endingResult || !sku.isStrictTargetQty()) {
            return false;
        }
        return StringUtils.equals(ConstructionStageEnum.TRIAL.getCode(), sku.getConstructionStage())
                || sku.isStrictNewSpecShortageOnly();
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
     * 解析排程窗口 T 日。
     *
     * @param context 排程上下文
     * @return 排程窗口 T 日
     */
    private LocalDate resolveScheduleWindowStartLocalDate(LhScheduleContext context) {
        if (Objects.isNull(context)) {
            return null;
        }
        if (!CollectionUtils.isEmpty(context.getScheduleWindowShifts())) {
            for (LhShiftConfigVO shift : context.getScheduleWindowShifts()) {
                if (Objects.nonNull(shift) && Objects.nonNull(shift.getWorkDate())) {
                    return shift.getWorkDate().toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
                }
            }
        }
        if (Objects.isNull(context.getScheduleDate())) {
            return null;
        }
        return context.getScheduleDate().toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
    }

    /**
     * 解析排程请求对应的目标业务日。
     *
     * @param context 排程上下文
     * @return 目标业务日
     */
    private LocalDate resolveScheduleBusinessLocalDate(LhScheduleContext context) {
        if (Objects.isNull(context) || Objects.isNull(context.getScheduleTargetDate())) {
            return null;
        }
        return context.getScheduleTargetDate().toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
    }

    /**
     * 解析排程窗口结束业务日期。
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
    private int resolveSchedulableRemainingQty(LhScheduleContext context, SkuScheduleDTO sku) {
        if (sku == null) {
            return 0;
        }
        ProductionQuantityPolicy policy = ProductionQuantityPolicy.from(sku, sku.isStrictTargetQty());
        if (!policy.isStrictUpperLimit()) {
            // 正规/量试非收尾的 dayN 只作为节奏与资源判断依据，实际排产量按SKU运行态账本共享扣减。
            return getTargetScheduleQtyResolver().resolveProductionRemainingQty(context, sku);
        }
        if (!StringUtils.equals(ConstructionStageEnum.TRIAL.getCode(), sku.getConstructionStage())
                && !sku.isStrictNewSpecShortageOnly()) {
            // 收尾目标、硫化余量、胎胚库存等严格业务目标不得被 dayN 或窗口计划改小。
            return sku.resolveTargetScheduleQty();
        }
        int remainingQuotaQty = SkuDailyPlanQuotaUtil.sumRemainingQty(sku.getDailyPlanQuotaMap());
        if (remainingQuotaQty > 0) {
            int windowRemainingQty = resolveWindowRemainingQty(sku);
            return Math.min(sku.resolveTargetScheduleQty(), Math.min(remainingQuotaQty, windowRemainingQty));
        }
        return sku.resolveTargetScheduleQty();
    }

    /**
     * 解析新增规格本轮可继续落结果的剩余量。
     * <p>仅保留给历史单元测试的无上下文入口；真实排程必须调用带上下文方法，
     * 以便按“物料+产品状态”共享运行态账本。</p>
     *
     * @param sku SKU排程DTO
     * @return 本轮可继续排产量
     */
    private int resolveSchedulableRemainingQty(SkuScheduleDTO sku) {
        return resolveSchedulableRemainingQty(null, sku);
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
     * 按物料编码与产品状态精确查找SKU。
     *
     * @param context 排程上下文
     * @param materialCode 物料编码
     * @param productStatus 产品状态
     * @return 精确匹配的SKU，未找到返回null
     */
    private SkuScheduleDTO findSkuDto(LhScheduleContext context,
                                      String materialCode,
                                      String productStatus) {
        if (context == null || StringUtils.isEmpty(materialCode)) {
            return null;
        }
        String skuKey = MonthPlanDateResolver.buildMaterialStatusKey(materialCode, productStatus);
        SkuScheduleDTO indexedSku = context.getAllSkuScheduleDtoMap().get(skuKey);
        if (indexedSku != null) {
            return indexedSku;
        }
        for (SkuScheduleDTO sku : context.getNewSpecSkuList()) {
            if (isSameSku(materialCode, productStatus, sku)) {
                return sku;
            }
        }
        for (SkuScheduleDTO sku : context.getContinuousSkuList()) {
            if (isSameSku(materialCode, productStatus, sku)) {
                return sku;
            }
        }
        return null;
    }

    /**
     * 判断物料编码和产品状态是否同时一致。
     *
     * @param materialCode 物料编码
     * @param productStatus 产品状态
     * @param sku 待比较SKU
     * @return true-同一业务SKU
     */
    private boolean isSameSku(String materialCode, String productStatus, SkuScheduleDTO sku) {
        return sku != null
                && StringUtils.equals(materialCode, sku.getMaterialCode())
                && StringUtils.equals(StringUtils.trimToEmpty(productStatus),
                StringUtils.trimToEmpty(sku.getProductStatus()));
    }

    /**
     * 计算新增零计划结果转未排时的剩余待排数量。
     *
     * @param context 排程上下文
     * @param materialCode 物料编码
     * @return 未排数量
     */
    private int resolveRemainingUnscheduledQty(LhScheduleContext context, SkuScheduleDTO sku) {
        if (sku == null) {
            return 0;
        }
        int targetScheduleQty = sku.resolveTargetScheduleQty();
        int retainedQty = resolveEffectiveScheduledQty(
                context, sku.getMaterialCode(), sku.getProductStatus());
        return Math.max(targetScheduleQty - retainedQty, 0);
    }

    /**
     * 统计同物料仍保留在新增结果列表中的有效计划量。
     *
     * @param context 排程上下文
     * @param materialCode 物料编码
     * @param productStatus 产品状态
     * @return 有效计划量
     */
    private int resolveEffectiveScheduledQty(LhScheduleContext context,
                                             String materialCode,
                                             String productStatus) {
        if (context == null || StringUtils.isEmpty(materialCode) || CollectionUtils.isEmpty(context.getScheduleResultList())) {
            return 0;
        }
        int totalQty = 0;
        for (LhScheduleResult result : context.getScheduleResultList()) {
            if (result == null
                    || !StringUtils.equals(materialCode, result.getMaterialCode())
                    || !StringUtils.equals(StringUtils.trimToEmpty(productStatus),
                    StringUtils.trimToEmpty(result.getProductStatus()))
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
     * @param sku 来源SKU
     * @param unscheduledQty 未排数量
     */
    private void mergeUnscheduledResultBySku(LhScheduleContext context, SkuScheduleDTO sku, int unscheduledQty) {
        if (context == null || sku == null || StringUtils.isEmpty(sku.getMaterialCode()) || unscheduledQty <= 0) {
            return;
        }
        LhUnscheduledResult existing = findUnscheduledResultBySku(
                context, sku.getMaterialCode(), sku.getProductStatus());
        if (existing != null) {
            int existingQty = existing.getUnscheduledQty() != null ? existing.getUnscheduledQty() : 0;
            existing.setUnscheduledQty(existingQty + unscheduledQty);
            if (StringUtils.isEmpty(existing.getUnscheduledReason())) {
                existing.setUnscheduledReason(ZERO_PLAN_UNSCHEDULED_REASON);
            }
            return;
        }
        LhUnscheduledResult unscheduled = new LhUnscheduledResult();
        unscheduled.setFactoryCode(context.getFactoryCode());
        unscheduled.setBatchNo(context.getBatchNo());
        unscheduled.setScheduleDate(context.getScheduleTargetDate());
        unscheduled.setMaterialCode(sku.getMaterialCode());
        unscheduled.setProductStatus(sku.getProductStatus());
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
     * @param productStatus 产品状态
     * @return 未排结果
     */
    private LhUnscheduledResult findUnscheduledResultBySku(LhScheduleContext context,
                                                           String materialCode,
                                                           String productStatus) {
        if (context == null || CollectionUtils.isEmpty(context.getUnscheduledResultList())) {
            return null;
        }
        for (LhUnscheduledResult unscheduledResult : context.getUnscheduledResultList()) {
            if (StringUtils.equals(materialCode, unscheduledResult.getMaterialCode())
                    && StringUtils.equals(StringUtils.trimToEmpty(productStatus),
                    StringUtils.trimToEmpty(unscheduledResult.getProductStatus()))) {
                return unscheduledResult;
            }
        }
        return null;
    }

    /**
     * 对未排结果按物料编码与产品状态去重合并。
     *
     * @param context 排程上下文
     */
    private void normalizeUnscheduledResultsBySku(LhScheduleContext context) {
        if (context == null || CollectionUtils.isEmpty(context.getUnscheduledResultList())) {
            return;
        }
        Map<String, LhUnscheduledResult> mergedMap = new LinkedHashMap<>(context.getUnscheduledResultList().size());
        for (LhUnscheduledResult unscheduledResult : context.getUnscheduledResultList()) {
            if (unscheduledResult == null || StringUtils.isEmpty(unscheduledResult.getMaterialCode())) {
                continue;
            }
            String skuKey = MonthPlanDateResolver.buildMaterialStatusKey(
                    unscheduledResult.getMaterialCode(), unscheduledResult.getProductStatus());
            if (!mergedMap.containsKey(skuKey)) {
                mergedMap.put(skuKey, unscheduledResult);
                continue;
            }
            LhUnscheduledResult existing = mergedMap.get(skuKey);
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
     * 回写多机台新增结果的SKU完整胎胚库存。
     * <p>同SKU多机台仅拆分排产量，不进入共用胎胚库存分摊。</p>
     *
     * @param context 排程上下文
     */
    private void retainMultiMachineEmbryoStock(LhScheduleContext context) {
        if (context == null || CollectionUtils.isEmpty(context.getScheduleResultList())) {
            return;
        }
        // 按物料状态复合键汇总新增结果（排除换活字块）。
        Map<String, List<LhScheduleResult>> skuResultsMap = new LinkedHashMap<>(16);
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
            String skuKey = MonthPlanDateResolver.buildMaterialStatusKey(
                    result.getMaterialCode(), result.getProductStatus());
            skuResultsMap.computeIfAbsent(skuKey, k -> new ArrayList<>()).add(result);
        }
        // 同一物料的每条新增机台结果统一保留SKU级胎胚库存。
        for (Map.Entry<String, List<LhScheduleResult>> entry : skuResultsMap.entrySet()) {
            List<LhScheduleResult> materialResults = entry.getValue();
            if (materialResults.size() <= 1) {
                continue;
            }
            LhScheduleResult firstResult = materialResults.get(0);
            String materialCode = firstResult.getMaterialCode();
            SkuScheduleDTO sku = findSkuDto(
                    context, materialCode, firstResult.getProductStatus());
            if (sku == null) {
                continue;
            }
            int totalEmbryoStock = Math.max(0, sku.getEmbryoStock());
            // 同SKU多机台只拆分排产量，每条结果都保留SKU已分配的完整胎胚库存。
            LhMultiMachineDistributionUtil.retainFullEmbryoStockForSingleMaterial(
                    materialResults, totalEmbryoStock);
            log.debug("多机台新增胎胚库存完整回写完成, materialCode: {}, 机台数: {}, SKU胎胚库存: {}",
                    materialCode, materialResults.size(), totalEmbryoStock);
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
        SkuScheduleDTO sku = findSkuDto(
                context, result.getMaterialCode(), result.getProductStatus());
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
