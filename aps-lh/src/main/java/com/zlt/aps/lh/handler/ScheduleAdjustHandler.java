package com.zlt.aps.lh.handler;

import com.zlt.aps.lh.api.constant.LhScheduleConstant;
import com.zlt.aps.lh.api.constant.LhScheduleParamConstant;
import com.zlt.aps.lh.component.TargetScheduleQtyResolver;
import com.zlt.aps.lh.component.SkuDecrementChecker;
import com.zlt.aps.lh.component.SingleControlModeSnapshotInitializer;
import com.zlt.aps.lh.component.StructureMinMachineRetentionService;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.api.domain.dto.MachineScheduleDTO;
import com.zlt.aps.lh.api.domain.dto.SkuDailyPlanQuotaDTO;
import com.zlt.aps.lh.api.domain.dto.SkuScheduleDTO;
import com.zlt.aps.lh.api.domain.vo.LhShiftConfigVO;
import com.zlt.aps.lh.api.domain.entity.LhUnscheduledResult;
import com.zlt.aps.lh.api.domain.entity.LhScheduleResult;
import com.zlt.aps.lh.api.enums.ConstructionStageEnum;
import com.zlt.aps.lh.api.enums.ScheduleStepEnum;
import com.zlt.aps.lh.api.enums.ScheduleTypeEnum;
import com.zlt.aps.lh.api.enums.SkuTagEnum;
import com.zlt.aps.lh.api.enums.TrialStatusEnum;
import com.zlt.aps.lh.engine.strategy.IEndingJudgmentStrategy;
import com.zlt.aps.lh.engine.strategy.support.PendingSkuUnscheduledRule;
import com.zlt.aps.lh.api.domain.dto.CuringMonthPlanTotalResult;
import com.zlt.aps.lh.component.CuringMonthPlanTotalCalculator;
import com.zlt.aps.lh.component.MonthPlanDateResolver;
import com.zlt.aps.lh.util.MonthPlanDayQtyUtil;
import com.zlt.aps.lh.util.PriorityTraceLogHelper;
import com.zlt.aps.lh.util.ShiftFieldUtil;
import com.zlt.aps.lh.util.LhScheduleTimeUtil;
import com.zlt.aps.lh.util.SkuDailyPlanQuotaUtil;
import com.zlt.aps.lh.api.domain.entity.LhMachineOnlineInfo;
import com.zlt.aps.mdm.api.domain.entity.MdmSkuLhCapacity;
import com.zlt.aps.mp.api.domain.entity.FactoryMonthPlanProductionFinalResult;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * S4.3 排程调整与SKU归集处理器。
 *
 * <p>主要职责：</p>
 * <ul>
 *   <li>按当前排程月份累计历史欠产，只将本月已发生日期的欠产向本窗口传导，忽略上月欠产和超产；</li>
 *   <li>从月计划结果构建 {@link SkuScheduleDTO}，映射物料、结构、胎胚、dayN、余量、库存和产能；</li>
 *   <li>按产品结构归集 SKU，标记 SKU 收尾、结构收尾、续作和新增；</li>
 *   <li>初始化日计划额度账本，供后续 S4.4/S4.5 按班次消费。</li>
 * </ul>
 *
 * <p>该步骤会修改上下文中的 SKU 列表、结构分组、欠产传导 Map 和未排结果列表，
 * 但不直接生成正常排程结果。</p>
 *
 * @author APS
 */
@Slf4j
@Component
public class ScheduleAdjustHandler extends AbsScheduleStepHandler {

    /** 无排产目标量未排产提示 */
    private static final String NO_PLAN_QTY_REASON_TEMPLATE = "物料：%s 没有排产目标量，不进行排产";
    /** 余量与胎胚库存均为0时的未排产提示 */
    private static final String ZERO_SURPLUS_AND_EMBRYO_REASON_TEMPLATE =
            "物料：%s 余量为0且胎胚库存为0，不需要排产";
    /** 无窗口计划量但存在余量/正向结转目标量提示 */
    private static final String TARGET_QTY_ONLY_WARN_TEMPLATE =
            "物料：%s 当前排程窗口没有计划量，但存在月计划余量/正向结转目标量[%d]，继续排产";
    /** 开产管控缺口未排提示 */
    private static final String OPEN_PRODUCTION_SHORTAGE_REASON_TEMPLATE =
            "物料：%s 开产管控导致排产目标量低于待排量，待排量[%d]，目标量[%d]，缺口[%d]，缺口比例[%s]，阈值[%s]";
    /** 满排模式下无窗口计划量仍继续排产提示 */
    private static final String FULL_CAPACITY_WARN_TEMPLATE =
            "物料：%s 当前排程窗口没有计划量，但按产能满排模式生成排产目标量[%d]，继续排产";
    /** 共用胎胚余量为0未排提示 */
    private static final String SHARED_EMBRYO_ZERO_SURPLUS_UNSCHEDULED_REASON =
            "共用胎胚且硫化余量为0";
    /** 上月超欠产有效标识 */
    private static final String LAST_MONTH_OVERDUE_VALID_FLAG = "1";
    /** 自动排程数据来源 */
    private static final String DATA_SOURCE_AUTO = "0";
    /** 正常删除标识 */
    private static final int DELETE_FLAG_NORMAL = 0;
    /** 比例展示小数位 */
    private static final int RATE_DISPLAY_SCALE = 4;
    /** 月计划最小自然日 */
    private static final int MIN_DAY_OF_MONTH = 1;
    /** 月计划最大自然日 */
    private static final int MAX_DAY_OF_MONTH = 31;

    @Resource
    private IEndingJudgmentStrategy endingJudgmentStrategy;
    @Resource
    private TargetScheduleQtyResolver targetScheduleQtyResolver;

    @Resource
    private SkuDecrementChecker skuDecrementChecker;
    @Resource
    private SingleControlModeSnapshotInitializer singleControlModeSnapshotInitializer;
    @Resource
    private StructureMinMachineRetentionService structureMinMachineRetentionService;

    @Override
    protected void doHandle(LhScheduleContext context) {
        log.info("排程调整与SKU归集开始, 工厂: {}, 目标日: {}, T日: {}, 月计划记录数: {}, 前批次结果数: {}",
                context.getFactoryCode(), LhScheduleTimeUtil.formatDate(context.getScheduleTargetDate()),
                LhScheduleTimeUtil.formatDate(context.getScheduleDate()),
                context.getMonthPlanList().size(), context.getPreviousScheduleResultList().size());
        // S4.3.1 前日排程欠/超产量调整
        adjustPreviousSchedule(context);

        // S4.3.2 按产品结构归集SKU，计算硫化余量
        gatherSkuByStructure(context);

        // S4.3.3 标注收尾SKU（3天内可收尾）
        markEndingSkus(context);
        // 共用胎胚库存只有在不同SKU同一天收尾时才按标准产能分摊，依赖收尾标注结果统一刷新。
        getTargetScheduleQtyResolver().refreshAllSharedEmbryoStockAllocations(context, "S4.3收尾标注完成");

        // S4.3.3.1 共用胎胚零余量SKU先出队，后续排产只使用动态归一化后的胎胚组
        pruneSharedEmbryoZeroSurplusSkus(context);

        /*
         * S4.3.3.2 冻结当前3天、8班窗口可全部收尾的结构，并按月计划结构类型读取最低机台数。
         * 必须在续作/新增分类前完成，后续结构待排视图会随着SKU出队而动态缩小，不能再作为全量结构快照。
         */
        if (Objects.nonNull(structureMinMachineRetentionService)) {
            structureMinMachineRetentionService.initializeEligibleStructures(context);
        }

        // S4.3.4 区分续作SKU和新增SKU
        classifyContinuousAndNewSkus(context);

        // 单控模式必须在续作、新增和换活字块开始前一次性冻结，后续待排量递减不得改变本轮模式。
        singleControlModeSnapshotInitializer.initialize(context);

        log.info("排程调整与SKU归集完成, 续作SKU: {}个, 新增SKU: {}个",
                context.getContinuousSkuList().size(), context.getNewSpecSkuList().size());
    }

    /**
     * 归集当前排程月份的历史欠产。
     * <p>
     * 只统计当前排程月份内、且日期早于 T 日的日欠产：
     * 欠产 = max(日计划量 - 日完成量, 0)<br/>
     * 上月欠产不参与当前月追补；本月超产只记日志，不允许抵扣后续计划。
     * </p>
     *
     * @param context 排程上下文
     */
    private void adjustPreviousSchedule(LhScheduleContext context) {
        if (CollectionUtils.isEmpty(context.getMonthPlanList()) || Objects.isNull(context.getScheduleDate())) {
            log.warn("月计划或排程日期为空，跳过本月历史欠产归集, 工厂: {}, T日: {}",
                    context.getFactoryCode(), LhScheduleTimeUtil.formatDate(context.getScheduleDate()));
            return;
        }
        Map<String, Integer> carryForwardQtyMap = new LinkedHashMap<>();
        LocalDate scheduleDate = toLocalDate(context.getScheduleDate());
        LocalDate historyEndDate = scheduleDate.minusDays(1);
        LocalDate monthStartDate = scheduleDate.withDayOfMonth(MIN_DAY_OF_MONTH);
        logIgnoredPreviousMonthCarryForward(context, monthStartDate);
        if (historyEndDate.isBefore(monthStartDate)) {
            context.setCarryForwardQtyMap(carryForwardQtyMap);
            log.info("本月历史欠产归集完成, 当前为月初首日, factoryCode: {}, scheduleDate: {}, historyShortageSkuCount: 0",
                    context.getFactoryCode(), scheduleDate);
            return;
        }
        for (FactoryMonthPlanProductionFinalResult plan : context.getMonthPlanList()) {
            if (Objects.isNull(plan) || StringUtils.isEmpty(plan.getMaterialCode())) {
                continue;
            }
            MonthlyShortageSummary shortageSummary = calculateCurrentMonthShortageSummary(
                    context, plan, monthStartDate, historyEndDate);
            if (shortageSummary.getShortageQty() > 0) {
                String materialStatusKey = MonthPlanDateResolver.buildMaterialStatusKey(
                        plan.getMaterialCode(), plan.getProductStatus());
                // 本月历史欠产按物料+产品状态分账，避免同物料不同示方状态互相覆盖。
                carryForwardQtyMap.put(materialStatusKey, shortageSummary.getShortageQty());
            }
        }
        context.setCarryForwardQtyMap(carryForwardQtyMap);
        log.info("本月历史欠产归集完成, factoryCode: {}, scheduleDate: {}, historyRange: {}~{}, historyShortageSkuCount: {}",
                context.getFactoryCode(), scheduleDate, monthStartDate, historyEndDate, carryForwardQtyMap.size());
    }

    /**
     * 从月度计划获取T日SKU数据，按产品结构归集，计算硫化余量
     * <p>
     * 硫化余量 = Max(月度计划总量 - 已完成量 + 有效上月超欠产量, 0)
     * 其中有效上月超欠产量可正可负：正值=欠产（增加余量），负值=超产（扣减余量）
     * </p>
     *
     * @param context 排程上下文
     */
    private void gatherSkuByStructure(LhScheduleContext context) {
        List<FactoryMonthPlanProductionFinalResult> monthPlanList = context.getMonthPlanList();
        if (monthPlanList == null || monthPlanList.isEmpty()) {
            log.warn("月生产计划为空，无法归集SKU");
            return;
        }

        // 按结构归集SKU（key=结构名称，value=该结构下的SKU排程DTO列表）
        Map<String, List<SkuScheduleDTO>> structureSkuMap = new LinkedHashMap<>();
        List<SkuScheduleDTO> validScheduleSkuList = new ArrayList<>(monthPlanList.size());

        for (FactoryMonthPlanProductionFinalResult plan : monthPlanList) {
            // 计算硫化余量：统一使用月计划、已完成量与有效上月超欠产量（含超产负值），不读取月余量表作为兜底。
            SurplusCalculation surplus = calculateSurplusQty(context, plan);
            SkuScheduleDTO dto = buildSkuScheduleDTO(context, plan, surplus);

            // 产品结构为空，跳过
            if (StringUtils.isEmpty(plan.getStructureName())) {
                log.warn("月计划产品结构为空，跳过SKU归集, materialCode: {}, 计划量: {}, 月计划版本: {}",
                        plan.getMaterialCode(), plan.getTotalQty(), plan.getMonthPlanVersion());
                continue;
            }

            int targetScheduleQty = dto.resolveTargetScheduleQty();

            // 当前无排产目标量时，直接记未排产并跳过。
            if (targetScheduleQty <= 0) {
                addNoPlanUnscheduledResult(context, dto);
                continue;
            }

            // 排程窗口没有计划量但存在余量/正向结转目标量时，允许继续排产，并给出明确告警。
            if (dto.getWindowPlanQty() <= 0) {
                if (getTargetScheduleQtyResolver().isFullCapacityMode(context)) {
                    log.warn(String.format(FULL_CAPACITY_WARN_TEMPLATE, dto.getMaterialCode(), targetScheduleQty));
                } else {
                    log.warn(String.format(TARGET_QTY_ONLY_WARN_TEMPLATE, dto.getMaterialCode(), targetScheduleQty));
                }
            }

            structureSkuMap.computeIfAbsent(plan.getStructureName(), k -> new ArrayList<>()).add(dto);
            validScheduleSkuList.add(dto);
        }

        context.setMaterialSharedEmbryoMap(buildMaterialSharedEmbryoMap(validScheduleSkuList));
        context.setActiveEmbryoSkuMap(buildActiveEmbryoSkuMap(validScheduleSkuList));
        context.setStructureSkuMap(structureSkuMap);
        int totalSkuCount = structureSkuMap.values().stream().mapToInt(List::size).sum();
        log.info("SKU按结构归集完成, 结构数量: {}, SKU总数: {}", structureSkuMap.size(), totalSkuCount);
    }

    /**
     * 构建本月待排物料胎胚共用关系。
     * <p>只统计已具备排产目标量且进入结构分组的SKU，避免无目标量或基础字段异常物料影响换模均衡判断。</p>
     *
     * @param skuList 有效待排SKU列表
     * @return 物料胎胚共用关系Map
     */
    private Map<String, Boolean> buildMaterialSharedEmbryoMap(List<SkuScheduleDTO> skuList) {
        Map<String, Set<String>> embryoMaterialSetMap = new LinkedHashMap<>();
        if (CollectionUtils.isEmpty(skuList)) {
            return new LinkedHashMap<>(0);
        }
        for (SkuScheduleDTO sku : skuList) {
            if (sku == null || StringUtils.isEmpty(sku.getMaterialCode())
                    || StringUtils.isEmpty(sku.getEmbryoCode())) {
                continue;
            }
            embryoMaterialSetMap.computeIfAbsent(sku.getEmbryoCode(), key -> new HashSet<String>(4))
                    .add(sku.getMaterialCode());
        }
        Map<String, Boolean> materialSharedEmbryoMap = new LinkedHashMap<>(skuList.size());
        for (SkuScheduleDTO sku : skuList) {
            if (sku == null || StringUtils.isEmpty(sku.getMaterialCode())) {
                continue;
            }
            Set<String> materialSet = embryoMaterialSetMap.get(sku.getEmbryoCode());
            materialSharedEmbryoMap.put(sku.getMaterialCode(),
                    !CollectionUtils.isEmpty(materialSet) && materialSet.size() > 1);
        }
        return materialSharedEmbryoMap;
    }

    /**
     * 构建当前有效参与排产的胎胚SKU集合。
     * <p>只记录已进入结构分组的有效SKU，后续收尾目标量计算会按运行态继续刷新。</p>
     *
     * @param skuList 有效待排SKU列表
     * @return 胎胚有效待排SKU集合
     */
    private Map<String, List<String>> buildActiveEmbryoSkuMap(List<SkuScheduleDTO> skuList) {
        Map<String, List<String>> activeEmbryoSkuMap = new LinkedHashMap<>();
        if (CollectionUtils.isEmpty(skuList)) {
            return activeEmbryoSkuMap;
        }
        for (SkuScheduleDTO sku : skuList) {
            if (sku == null || StringUtils.isEmpty(sku.getMaterialCode())
                    || StringUtils.isEmpty(sku.getEmbryoCode())) {
                continue;
            }
            List<String> activeSkuList = activeEmbryoSkuMap.computeIfAbsent(
                    sku.getEmbryoCode(), key -> new ArrayList<String>(4));
            String skuKey = MonthPlanDateResolver.buildMaterialStatusKey(
                    sku.getMaterialCode(), sku.getProductStatus());
            if (!activeSkuList.contains(skuKey)) {
                activeSkuList.add(skuKey);
            }
        }
        return activeEmbryoSkuMap;
    }

    /**
     * 预剔除共用胎胚零余量SKU。
     * <p>动态共用胎胚要以本轮排程初始有效SKU集合为准先处理零余量SKU，
     * 避免零余量SKU参与新增、续作、换活字块候选、目标量计算和胎胚库存分配。</p>
     *
     * @param context 排程上下文
     */
    private void pruneSharedEmbryoZeroSurplusSkus(LhScheduleContext context) {
        if (Objects.isNull(context) || CollectionUtils.isEmpty(context.getStructureSkuMap())) {
            return;
        }
        List<SkuScheduleDTO> pruneSkuList = collectSharedEmbryoZeroSurplusSkus(context);
        if (CollectionUtils.isEmpty(pruneSkuList)) {
            return;
        }
        Set<String> affectedEmbryoSet = new HashSet<>(8);
        for (Map.Entry<String, List<SkuScheduleDTO>> entry : context.getStructureSkuMap().entrySet()) {
            List<SkuScheduleDTO> skuList = entry.getValue();
            if (CollectionUtils.isEmpty(skuList)) {
                continue;
            }
            Iterator<SkuScheduleDTO> iterator = skuList.iterator();
            while (iterator.hasNext()) {
                SkuScheduleDTO sku = iterator.next();
                if (!pruneSkuList.contains(sku)) {
                    continue;
                }
                sku.setTargetScheduleQty(0);
                sku.setRemainingScheduleQty(0);
                addSharedEmbryoZeroSurplusUnscheduledResult(context, sku);
                getTargetScheduleQtyResolver().removeActiveEmbryoSku(
                        context, sku, SHARED_EMBRYO_ZERO_SURPLUS_UNSCHEDULED_REASON);
                affectedEmbryoSet.add(sku.getEmbryoCode());
                iterator.remove();
            }
        }
        context.getStructureSkuMap().entrySet().removeIf(entry -> CollectionUtils.isEmpty(entry.getValue()));
        List<SkuScheduleDTO> remainingSkuList = collectStructureSkus(context);
        context.setMaterialSharedEmbryoMap(buildMaterialSharedEmbryoMap(remainingSkuList));
        context.setActiveEmbryoSkuMap(buildActiveEmbryoSkuMap(remainingSkuList));
        normalizeDynamicSingleEmbryoEndingSkus(context, affectedEmbryoSet, remainingSkuList);
        logNormalizedEmbryoGroups(context, affectedEmbryoSet);
        log.info("共用胎胚零余量SKU预剔除完成, 剔除数量: {}", pruneSkuList.size());
    }

    /**
     * 基于预处理开始时的动态共用胎胚组收集零余量SKU。
     * <p>先收集后剔除，避免同胎胚多个零余量SKU在逐个移除时被误判为单胎胚。</p>
     *
     * @param context 排程上下文
     * @return 需要剔除的SKU列表
     */
    private List<SkuScheduleDTO> collectSharedEmbryoZeroSurplusSkus(LhScheduleContext context) {
        List<SkuScheduleDTO> pruneSkuList = new ArrayList<>(8);
        for (List<SkuScheduleDTO> skuList : context.getStructureSkuMap().values()) {
            if (CollectionUtils.isEmpty(skuList)) {
                continue;
            }
            for (SkuScheduleDTO sku : skuList) {
                if (isSharedEmbryoZeroSurplusSku(context, sku)) {
                    pruneSkuList.add(sku);
                }
            }
        }
        return pruneSkuList;
    }

    /**
     * 判断是否为共用胎胚零余量SKU。
     *
     * @param context 排程上下文
     * @param sku SKU排程DTO
     * @return true-命中共用胎胚零余量；false-未命中
     */
    private boolean isSharedEmbryoZeroSurplusSku(LhScheduleContext context, SkuScheduleDTO sku) {
        if (Objects.isNull(sku)
                || sku.getSurplusQty() > 0
                || StringUtils.isEmpty(sku.getEmbryoCode())
                || StringUtils.isEmpty(sku.getMaterialCode())
                || !getTargetScheduleQtyResolver().isSharedEmbryoInWindow(context, sku)) {
            return false;
        }
        // 胎胚库存收尾生产者候选保留：共用胎胚组内消纳胎胚库存的自然生产者不得一刀切未排，
        // 由动态转单胎胚归一化转为单胎胚按胎胚库存排产，避免胎胚库存无法消纳（违反动态转单胎胚/单胎胚放宽规则）。
        return !isEmbryoStockEndingProducerCandidate(context, sku);
    }

    /**
     * 判断当前SKU是否为胎胚库存收尾生产者候选。
     * <p>共用胎胚组内余量为0但胎胚仍处于收尾且有库存时，前一业务日(T-1)仍在产的SKU
     * 是消纳胎胚库存的自然生产者，应在预剔除阶段保留，由动态转单胎胚归一化转为单胎胚按胎胚库存排产。</p>
     *
     * @param context 排程上下文
     * @param sku 当前SKU
     * @return true-生产者候选，应保留不预剔除；false-非生产者，可预剔除
     */
    private boolean isEmbryoStockEndingProducerCandidate(LhScheduleContext context, SkuScheduleDTO sku) {
        // 胎胚收尾标识必须为是，否则该胎胚不属于清尾场景，零余量SKU照常预剔除
        if (!getTargetScheduleQtyResolver().isEmbryoStockEndingFlagYes(context, sku.getEmbryoCode())) {
            return false;
        }
        // 胎胚库存为0时无需消纳库存，零余量SKU照常预剔除
        if (sku.getEmbryoStock() <= 0) {
            return false;
        }
        // 前一业务日(T-1)有日计划，表示昨日仍在产，是消纳胎胚库存的自然生产者
        LocalDate previousDay = toLocalDate(context.getScheduleDate()).minusDays(1);
        int previousDayPlanQty = MonthPlanDateResolver.resolveDayQty(
                context, sku.getMaterialCode(), sku.getProductStatus(), previousDay);
        return previousDayPlanQty > 0;
    }

    /**
     * 写入共用胎胚零余量未排结果。
     *
     * @param context 排程上下文
     * @param sku SKU排程DTO
     */
    private void addSharedEmbryoZeroSurplusUnscheduledResult(LhScheduleContext context, SkuScheduleDTO sku) {
        LhUnscheduledResult unscheduled = buildBaseUnscheduledResult(context, sku);
        unscheduled.setUnscheduledQty(0);
        unscheduled.setUnscheduledReason(SHARED_EMBRYO_ZERO_SURPLUS_UNSCHEDULED_REASON);
        context.getUnscheduledResultList().add(unscheduled);
        log.info("共用胎胚零余量SKU写入未排, materialCode: {}, embryoCode: {}, "
                        + "原始共用SKU数: {}, 有效共用SKU数: {}, 是否动态共用: {}, "
                        + "余量: {}, 胎胚库存: {}, 目标量: {}, 未排原因: {}",
                sku.getMaterialCode(), sku.getEmbryoCode(),
                resolveOriginalSharedEmbryoSkuCount(context, sku),
                resolveActiveEmbryoSkuCount(context, sku),
                getTargetScheduleQtyResolver().isSharedEmbryoInWindow(context, sku),
                sku.getSurplusQty(), sku.getEmbryoStock(), 0,
                SHARED_EMBRYO_ZERO_SURPLUS_UNSCHEDULED_REASON);
    }

    /**
     * 收集结构分组中仍可进入排产的SKU。
     *
     * @param context 排程上下文
     * @return 剩余SKU列表
     */
    private List<SkuScheduleDTO> collectStructureSkus(LhScheduleContext context) {
        if (Objects.isNull(context) || CollectionUtils.isEmpty(context.getStructureSkuMap())) {
            return new ArrayList<>(0);
        }
        List<SkuScheduleDTO> remainingSkuList = new ArrayList<>(16);
        for (List<SkuScheduleDTO> skuList : context.getStructureSkuMap().values()) {
            if (!CollectionUtils.isEmpty(skuList)) {
                remainingSkuList.addAll(skuList);
            }
        }
        return remainingSkuList;
    }

    /**
     * 记录共用胎胚剔除后的动态归一化结果。
     *
     * @param context 排程上下文
     * @param affectedEmbryoSet 发生剔除的胎胚集合
     */
    private void logNormalizedEmbryoGroups(LhScheduleContext context, Set<String> affectedEmbryoSet) {
        if (Objects.isNull(context) || CollectionUtils.isEmpty(affectedEmbryoSet)) {
            return;
        }
        for (String embryoCode : affectedEmbryoSet) {
            List<String> remainingSkuList = context.getActiveEmbryoSkuMap().get(embryoCode);
            int remainingCount = CollectionUtils.isEmpty(remainingSkuList) ? 0 : remainingSkuList.size();
            log.info("共用胎胚零余量剔除后动态归一化, embryoCode: {}, 剩余SKU: {}, 剩余SKU数: {}, 是否动态转单胎胚: {}",
                    embryoCode, CollectionUtils.isEmpty(remainingSkuList) ? new ArrayList<String>(0) : remainingSkuList,
                    remainingCount, remainingCount == 1);
        }
    }

    /**
     * 将共用胎胚剔除后只剩一个可排SKU的胎胚组动态转为单胎胚收尾。
     * <p>该场景必须复用单胎胚收尾目标量口径：MAX(硫化余量, 胎胚库存)，
     * 同时由目标量解析器同步日计划账本，避免后续 S4.4/S4.5 回裁为原窗口量。</p>
     *
     * @param context 排程上下文
     * @param affectedEmbryoSet 发生剔除的胎胚集合
     * @param remainingSkuList 剩余可排SKU
     */
    private void normalizeDynamicSingleEmbryoEndingSkus(LhScheduleContext context,
                                                        Set<String> affectedEmbryoSet,
                                                        List<SkuScheduleDTO> remainingSkuList) {
        if (Objects.isNull(context) || CollectionUtils.isEmpty(affectedEmbryoSet)
                || CollectionUtils.isEmpty(remainingSkuList)) {
            return;
        }
        Map<String, SkuScheduleDTO> remainingSkuMap = new LinkedHashMap<>(remainingSkuList.size());
        for (SkuScheduleDTO sku : remainingSkuList) {
            if (Objects.nonNull(sku) && StringUtils.isNotEmpty(sku.getMaterialCode())) {
                String skuKey = MonthPlanDateResolver.buildMaterialStatusKey(
                        sku.getMaterialCode(), sku.getProductStatus());
                remainingSkuMap.put(skuKey, sku);
            }
        }
        for (String embryoCode : affectedEmbryoSet) {
            List<String> activeSkuList = context.getActiveEmbryoSkuMap().get(embryoCode);
            if (CollectionUtils.isEmpty(activeSkuList) || activeSkuList.size() != 1) {
                continue;
            }
            SkuScheduleDTO remainingSku = remainingSkuMap.get(activeSkuList.get(0));
            if (Objects.isNull(remainingSku)) {
                continue;
            }
            remainingSku.setSkuTag(SkuTagEnum.ENDING.getCode());
            remainingSku.setEndingDaysRemaining(1);
            context.getDynamicSingleEmbryoEndingMaterialSet().add(remainingSku.getMaterialCode());
            int beforeTargetQty = remainingSku.resolveTargetScheduleQty();
            int targetQty = getTargetScheduleQtyResolver().upsizeEndingTargetQty(context, remainingSku);
            log.info("共用胎胚剔除后动态转单胎胚收尾, materialCode: {}, embryoCode: {}, "
                            + "原目标量: {}, 动态目标量: {}, 余量: {}, 胎胚库存: {}",
                    remainingSku.getMaterialCode(), remainingSku.getEmbryoCode(),
                    beforeTargetQty, targetQty, remainingSku.getSurplusQty(), remainingSku.getEmbryoStock());
        }
    }

    /**
     * 统计同胎胚原始SKU数量。
     *
     * @param context 排程上下文
     * @param sku SKU排程DTO
     * @return 同胎胚SKU数量
     */
    private int resolveOriginalSharedEmbryoSkuCount(LhScheduleContext context, SkuScheduleDTO sku) {
        if (Objects.isNull(context) || Objects.isNull(sku) || StringUtils.isEmpty(sku.getEmbryoCode())) {
            return 0;
        }
        Set<String> skuKeySet = new HashSet<>(8);
        for (List<SkuScheduleDTO> skuList : context.getStructureSkuMap().values()) {
            if (CollectionUtils.isEmpty(skuList)) {
                continue;
            }
            for (SkuScheduleDTO candidateSku : skuList) {
                if (Objects.nonNull(candidateSku)
                        && StringUtils.equals(sku.getEmbryoCode(), candidateSku.getEmbryoCode())
                        && StringUtils.isNotEmpty(candidateSku.getMaterialCode())) {
                    skuKeySet.add(MonthPlanDateResolver.buildMaterialStatusKey(
                            candidateSku.getMaterialCode(), candidateSku.getProductStatus()));
                }
            }
        }
        return skuKeySet.size();
    }

    /**
     * 统计当前胎胚有效SKU数量。
     *
     * @param context 排程上下文
     * @param sku SKU排程DTO
     * @return 有效SKU数量
     */
    private int resolveActiveEmbryoSkuCount(LhScheduleContext context, SkuScheduleDTO sku) {
        if (Objects.isNull(context) || Objects.isNull(sku) || StringUtils.isEmpty(sku.getEmbryoCode())
                || CollectionUtils.isEmpty(context.getActiveEmbryoSkuMap())) {
            return 0;
        }
        List<String> activeSkuList = context.getActiveEmbryoSkuMap().get(sku.getEmbryoCode());
        return CollectionUtils.isEmpty(activeSkuList) ? 0 : activeSkuList.size();
    }
    
    /**
     * 计算指定月计划记录的硫化余量（只读计算，不修改上下文）。
     * <p>供基础数据初始化阶段以胎胚维度合并余量使用，口径与排程阶段 {@link #calculateSurplusQty} 完全一致。</p>
     *
     * @param context 排程上下文
     * @param plan    月生产计划记录
     * @return 硫化余量（Max(月度计划总量 - 已完成量 + 有效上月超欠产量, 0)）
     */
    public int calculatePlanSurplusQty(LhScheduleContext context, FactoryMonthPlanProductionFinalResult plan) {
        return this.calculateSurplusQty(context, plan).getSurplusQty();
    }

    /**
     * 计算SKU的硫化余量
     * <p>
     * 硫化余量 = Max(月度计划总量 - 已完成量 + 有效上月超欠产量, 0)。
     * 不再将逐日超产量加回剩余需求，避免月累计完成量已超月计划时余量被虚增。
     * 有效上月超欠产量可正可负：正值表示欠产（增加余量），负值表示超产（扣减余量）。
     * </p>
     *
     * @param context 排程上下文
     * @param plan    月生产计划记录
     * @return 硫化余量计算结果
     */
    private SurplusCalculation calculateSurplusQty(LhScheduleContext context, FactoryMonthPlanProductionFinalResult plan) {
        int actualFinishedQty = calculateFinishedQty(context, plan);
        int scheDayFinishQty = resolveScheDayFinishQty(
                context, plan.getMaterialCode(), plan.getProductStatus());
        int lastMonthOverdueQty = resolveEffectiveLastMonthOverdueQty(plan);
        CuringMonthPlanTotalResult monthPlanTotalResult = CuringMonthPlanTotalCalculator.calculate(
                context, plan, toLocalDate(context.getScheduleDate()), toLocalDate(context.getWindowEndDate()),
                actualFinishedQty, lastMonthOverdueQty);
        int totalPlanQty = monthPlanTotalResult.getMonthPlanTotal();
        int remainingDemandQty = Math.max(0, totalPlanQty - actualFinishedQty + lastMonthOverdueQty);
        // 保留逐日超产统计用于诊断日志，不参与余量计算
        int ignoredOverProductionQty = calculateIgnoredOverProductionQty(context, plan);
        if (lastMonthOverdueQty != 0 || scheDayFinishQty > 0 || monthPlanTotalResult.isCrossMonth()) {
            log.info("硫化余量计算完成, materialCode: {}, monthPlanQty: {}, monthFinishedAndScheDayQty: {}, "
                            + "scheDayFinishQty: {}, lastMonthValidFlag: {}, lastMonthOverdueQty: {}, surplusQty: {}, "
                            + "crossMonth: {}, breakPointDate: {}, currentMonthPlanTotal: {}, crossMonthPlanTotal: {}, scene: {}",
                    plan.getMaterialCode(), totalPlanQty, actualFinishedQty, scheDayFinishQty,
                    plan.getLastMonthValidFlag(), lastMonthOverdueQty, remainingDemandQty,
                    monthPlanTotalResult.isCrossMonth(), monthPlanTotalResult.getBreakPointDate(),
                    monthPlanTotalResult.getCurrentMonthPlanTotal(), monthPlanTotalResult.getCrossMonthPlanTotal(),
                    monthPlanTotalResult.getCalculateScene());
        }
        return new SurplusCalculation(remainingDemandQty, actualFinishedQty, ignoredOverProductionQty,
                lastMonthOverdueQty, totalPlanQty);
    }

    /**
     * 解析有效上月超欠产数量。
     * <p>正值表示欠产（需补排），负值表示超产（需扣减余量），仅在有效标识为1时生效。</p>
     *
     * @param plan 月生产计划记录
     * @return 有效上月超欠产数量（正=欠产，负=超产）
     */
    private int resolveEffectiveLastMonthOverdueQty(FactoryMonthPlanProductionFinalResult plan) {
        if (Objects.isNull(plan) || !StringUtils.equals(LAST_MONTH_OVERDUE_VALID_FLAG,
                StringUtils.trimToEmpty(plan.getLastMonthValidFlag()))) {
            return 0;
        }
        return safeInt(plan.getLastMonthOverdueQty());
    }

    /**
     * 计算指定SKU的已完成量（月累计完成量 + T日晚班完成量）
     *
     * @param context 排程上下文
     * @param plan    月生产计划记录
     * @return 已完成量
     */
    private int calculateFinishedQty(LhScheduleContext context, FactoryMonthPlanProductionFinalResult plan) {
        // 已完成量 = 月累计完成量（截至T-1日）+ T日排程晚班完成量（class1FinishQty）
        String materialCode = plan.getMaterialCode();
        String productStatus = plan.getProductStatus();
        if (StringUtils.isNotEmpty(materialCode)) {
            Integer monthFinishedQty = resolveMaterialMonthFinishedQty(context, plan);
            if (Objects.nonNull(monthFinishedQty)) {
                return Math.max(monthFinishedQty, 0)
                        + resolveScheDayFinishQty(context, materialCode, productStatus);
            }
            if (canFallbackToPreviousFinishedQty(context)) {
                Integer dayFinishedQty = context.getMaterialDayFinishedQtyMap().get(
                        buildMaterialDayKey(materialCode, productStatus, resolvePreviousScheduleDate(context)));
                if (Objects.nonNull(dayFinishedQty)) {
                    return Math.max(dayFinishedQty, 0);
                }

                int finishedQty = 0;
                for (LhScheduleResult result : context.getPreviousScheduleResultList()) {
                    if (materialCode.equals(result.getMaterialCode())
                            && StringUtils.equals(StringUtils.trimToEmpty(productStatus),
                            StringUtils.trimToEmpty(result.getProductStatus()))) {
                        finishedQty += resolveShiftFinishedQty(result, context);
                    }
                }
                return finishedQty;
            }
        }
        return 0;
    }

    /**
     * 按月计划所属年月解析月累计完成量。
     *
     * @param context 排程上下文
     * @param plan 月计划
     * @return 月累计完成量，未初始化时返回null
     */
    private Integer resolveMaterialMonthFinishedQty(LhScheduleContext context,
                                                    FactoryMonthPlanProductionFinalResult plan) {
        if (Objects.isNull(context) || Objects.isNull(plan) || StringUtils.isEmpty(plan.getMaterialCode())) {
            return null;
        }
        String materialStatusKey = buildMaterialStatusKey(plan.getMaterialCode(), plan.getProductStatus());
        if (Objects.nonNull(plan.getYear()) && Objects.nonNull(plan.getMonth())
                && !CollectionUtils.isEmpty(context.getMaterialMonthFinishedQtyByMonthMap())) {
            String materialMonthKey = MonthPlanDateResolver.buildMaterialMonthKey(
                    materialStatusKey, plan.getYear(), plan.getMonth());
            Integer monthFinishedQty = context.getMaterialMonthFinishedQtyByMonthMap().get(materialMonthKey);
            if (Objects.nonNull(monthFinishedQty)) {
                return monthFinishedQty;
            }
        }
        return context.getMaterialMonthFinishedQtyMap().get(materialStatusKey);
    }

    /**
     * 统计本月已发生日期和 T 日晚班中的逐日超产量（仅用于诊断日志，不参与硫化余量计算）。
     *
     * @param context 排程上下文
     * @param plan 月生产计划记录
     * @return 被忽略的超产量
     */
    private int calculateIgnoredOverProductionQty(LhScheduleContext context,
                                                  FactoryMonthPlanProductionFinalResult plan) {
        if (Objects.isNull(context) || Objects.isNull(context.getScheduleDate()) || Objects.isNull(plan)) {
            return 0;
        }
        LocalDate scheduleDate = toLocalDate(context.getScheduleDate());
        LocalDate historyEndDate = scheduleDate.minusDays(1);
        LocalDate monthStartDate = scheduleDate.withDayOfMonth(MIN_DAY_OF_MONTH);
        int ignoredOverProductionQty = 0;
        if (!historyEndDate.isBefore(monthStartDate)) {
            MonthlyShortageSummary shortageSummary = calculateCurrentMonthShortageSummary(
                    context, plan, monthStartDate, historyEndDate);
            ignoredOverProductionQty += shortageSummary.getIgnoredOverProductionQty();
        }
        int currentDayPlanQty = MonthPlanDateResolver.resolveDayQty(
                context, plan.getMaterialCode(), plan.getProductStatus(), scheduleDate);
        int scheDayFinishQty = resolveScheDayFinishQty(
                context, plan.getMaterialCode(), plan.getProductStatus());
        int currentDayIgnoredOverQty = Math.max(0, scheDayFinishQty - currentDayPlanQty);
        if (currentDayIgnoredOverQty > 0) {
            ignoredOverProductionQty += currentDayIgnoredOverQty;
            log.info("T日晚班超产忽略抵扣, materialCode: {}, productStatus: {}, scheduleDate: {}, dayPlanQty: {}, scheDayFinishQty: {}, ignoredOverQty: {}",
                    plan.getMaterialCode(), plan.getProductStatus(), scheduleDate, currentDayPlanQty,
                    scheDayFinishQty, currentDayIgnoredOverQty);
        }
        return ignoredOverProductionQty;
    }

    /**
     * 获取指定业务SKU的T日排程班次完成量（class1FinishQty汇总值）。
     *
     * @param context       排程上下文
     * @param materialCode  物料编码
     * @param productStatus 产品状态
     * @return T日班次完成量，无记录时返回0
     */
    private int resolveScheDayFinishQty(LhScheduleContext context,
                                        String materialCode,
                                        String productStatus) {
        if (StringUtils.isEmpty(materialCode)) {
            return 0;
        }
        String materialStatusKey = MonthPlanDateResolver.buildMaterialStatusKey(materialCode, productStatus);
        Integer scheDayFinishQty = context.getMaterialScheDayFinishQtyMap().get(materialStatusKey);
        return Objects.nonNull(scheDayFinishQty) ? Math.max(scheDayFinishQty, 0) : 0;
    }

    /**
     * 仅当前一日基线与窗口T-1一致时，才允许回退使用前一日完成量/前一日排程结果。
     *
     * @param context 排程上下文
     * @return true-允许回退
     */
    private boolean canFallbackToPreviousFinishedQty(LhScheduleContext context) {
        if (Objects.isNull(context.getScheduleDate())) {
            return false;
        }
        Date previousScheduleDate = resolvePreviousScheduleDate(context);
        Date windowPreviousDate = LhScheduleTimeUtil.clearTime(LhScheduleTimeUtil.addDays(context.getScheduleDate(), -1));
        return Objects.nonNull(previousScheduleDate)
                && previousScheduleDate.equals(windowPreviousDate);
    }

    /**
     * 根据月生产计划构建SKU排程DTO
     *
     * @param context    排程上下文
     * @param plan       月生产计划记录
     * @param surplus 硫化余量
     * @return SKU排程DTO
     */
    private SkuScheduleDTO buildSkuScheduleDTO(LhScheduleContext context,
                                               FactoryMonthPlanProductionFinalResult plan,
                                               SurplusCalculation surplus) {
        FactoryMonthPlanProductionFinalResult targetMonthPlan = resolveTargetMonthPlan(context, plan);
        SkuScheduleDTO dto = new SkuScheduleDTO();
        dto.setMaterialCode(plan.getMaterialCode());
        dto.setMaterialDesc(plan.getMaterialDesc());
        dto.setStructureName(plan.getStructureName());
        dto.setStructureType(targetMonthPlan.getStructureType());
        dto.setEmbryoCode(plan.getEmbryoCode());
        dto.setMainMaterialDesc(plan.getMainMaterialDesc());
        dto.setSpecCode(plan.getSpecifications());
        dto.setProSize(plan.getProSize());
        dto.setPattern(plan.getPattern());
        dto.setMainPattern(plan.getMainPattern());
        dto.setBrand(plan.getBrand());
        // 产品状态是SKU运行态复合键的一部分，必须在任何目标量、胎胚额度和实际消费账本初始化之前写入。
        dto.setProductStatus(targetMonthPlan.getProductStatus());

        // 计划量信息
        dto.setMonthPlanQty(surplus.getMonthPlanTotal());
        dto.setFinishedQty(surplus.getActualFinishedQty());
        String materialStatusKey = MonthPlanDateResolver.buildMaterialStatusKey(
                plan.getMaterialCode(), plan.getProductStatus());
        int rawCarryForwardQty = context.getCarryForwardQtyMap().getOrDefault(materialStatusKey, 0);
        int carryForwardQty = resolveEffectiveCarryForwardQty(context, plan.getMaterialCode(), rawCarryForwardQty);
        int scheDayFinishQty = resolveScheDayFinishQty(
                context, plan.getMaterialCode(), plan.getProductStatus());
        int windowPlanQty = MonthPlanDateResolver.resolveWindowPlanQty(context, plan.getMaterialCode(),
                plan.getProductStatus(),
                toLocalDate(context.getScheduleDate()), toLocalDate(context.getWindowEndDate()));
        // 继承量已由滚动衔接占用，需从窗口待排量中扣减，防止重复排产
        int inheritedPlanQty = Math.max(0, context.getInheritedPlanQtyMap().getOrDefault(materialStatusKey, 0));
        dto.setWindowPlanQty(windowPlanQty);
        dto.setMonthlyHistoryShortageQty(Math.max(0, rawCarryForwardQty));
        dto.setEffectiveLastMonthOverdueQty(surplus.getLastMonthOverdueQty());
        dto.setEffectiveCarryForwardQty(Math.max(0, carryForwardQty));
        dto.setScheduleDayFinishQty(Math.max(0, scheDayFinishQty));
        dto.setFutureMonthPlanQtyAfterWindow(resolveFutureMonthPlanQtyAfterWindow(context, plan));
        dto.setNextDayPlanQtyAfterWindow(resolveNextDayPlanQtyAfterWindow(context, plan));

        // 初始化日计划额度账本：按排程窗口日期读取月计划 dayN，扣减继承量。
        // day1/day2/day3 的业务日期由窗口 T日～目标日决定，不能按字段名固定绑定自然日。
        Map<LocalDate, SkuDailyPlanQuotaDTO> dailyPlanQuotaMap = buildDailyPlanQuotaMap(
                context, plan, dto.getMaterialCode());
        deductInheritedFromDailyQuota(dailyPlanQuotaMap, inheritedPlanQty);
        deductScheDayFinishFromDailyQuota(
                context, dailyPlanQuotaMap, dto.getMaterialCode(), dto.getProductStatus());

        // 只把“本月历史欠产”叠加到首日日计划账本，不处理上月欠产和超产抵扣。
        applyCarryForwardToDailyQuota(dailyPlanQuotaMap, carryForwardQty, dto.getMaterialCode());
        dto.setDailyPlanQuotaMap(dailyPlanQuotaMap);

        // 窗口内日计划剩余量汇总（已扣减继承量，已叠加欠产传导）
        int windowRemainingPlanQty = SkuDailyPlanQuotaUtil.sumRemainingQty(dailyPlanQuotaMap);
        dto.setWindowRemainingPlanQty(windowRemainingPlanQty);

        dto.setSurplusQty(surplus.getSurplusQty());
        dto.setEmbryoStock(resolveRawEmbryoStock(context, plan));
        // 待排量保持"需求口径"：使用月计划余量扣减滚动继承量，再与胎胚库存取大。
        // 本月历史欠产已体现在首日日计划账本中，不能再次重复叠加。
        int basePendingQty = resolveBasePendingQty(surplus.getSurplusQty(), inheritedPlanQty, dto.getEmbryoStock());
        if (context.isStopProductionMode()) {
            // 停产收尾按"停产日含损耗计划量"和"胎胚库存"取大，优先把停锅前可收的量拉齐。
            basePendingQty = resolveStopProductionDemandQty(context, plan, dto.getEmbryoStock());
        }
        dto.setPendingQty(Math.max(0, basePendingQty));
        dto.setDailyPlanQty(targetMonthPlan.getDayVulcanizationQty() != null
                ? targetMonthPlan.getDayVulcanizationQty() : 0);

        // 产能信息（从SKU日硫化产能Map获取）
        MdmSkuLhCapacity capacity = context.getSkuLhCapacityMap().get(plan.getMaterialCode());
        if (capacity != null) {
            // 硫化时间（秒），curingTime来自月计划，若无则用3600秒（1小时）作为默认
            int lhTimeSeconds = targetMonthPlan.getCuringTime() != null ? targetMonthPlan.getCuringTime() : 3600;
            dto.setLhTimeSeconds(lhTimeSeconds);
            dto.setShiftCapacity(capacity.getClassCapacity() != null ? capacity.getClassCapacity() : 0);
        } else {
            // 无产能数据时使用默认值
            log.warn("SKU硫化产能缺失，使用默认硫化时间继续计算, materialCode: {}, specCode: {}, structureName: {}",
                    plan.getMaterialCode(), plan.getSpecifications(), plan.getStructureName());
            dto.setLhTimeSeconds(3600);
        }

        // 填充日硫化产能
        fillDailyCapacity(dto, capacity);
        dto.setTargetScheduleQty(getTargetScheduleQtyResolver().resolveInitialTargetQty(context, dto));
        getTargetScheduleQtyResolver().initializeProductionRemainingQty(
                context, dto, dto.resolveTargetScheduleQty(), "SKU初始化");
        appendOpenProductionShortageIfNecessary(context, dto);
        log.debug("SKU待排量计算完成, materialCode: {}, 结构: {}, 月计划: {}, 窗口计划: {}, 窗口剩余: {}, "
                        + "已完成: {}, 有效上月超欠产: {}, 忽略超产: {}, 余量: {}, 待排: {}, 目标量: {}, 班产: {}",
                dto.getMaterialCode(), dto.getStructureName(), dto.getMonthPlanQty(), dto.getWindowPlanQty(),
                dto.getWindowRemainingPlanQty(), dto.getFinishedQty(), surplus.getLastMonthOverdueQty(),
                surplus.getIgnoredOverProductionQty(), dto.getSurplusQty(), dto.getPendingQty(),
                dto.getTargetScheduleQty(), dto.getShiftCapacity());
        if (context.isRollingScheduleHandoff() || inheritedPlanQty > 0) {
            log.info("滚动待排量拆解, 物料: {}, 窗口计划量: {}, 已继承量: {}, 本月历史欠产量: {}, 待排量: {}, 余量: {}, 目标量: {}",
                    dto.getMaterialCode(), windowPlanQty, inheritedPlanQty, carryForwardQty,
                    dto.getPendingQty(), dto.getSurplusQty(), dto.getTargetScheduleQty());
        }

        // 优先级信息
        dto.setSupplyChainPriority(targetMonthPlan.getProductionType());
        dto.setProductionType(targetMonthPlan.getProductionType());
        dto.setDeliveryLocked(isDeliveryLocked(context, targetMonthPlan));
        dto.setDelayDays(resolveDelayDays(context, plan));
        dto.setHighPriorityPendingQty(safeInt(targetMonthPlan.getHeightProductionQty()));
        dto.setCycleProductionPendingQty(safeInt(targetMonthPlan.getCycleProductionQty()));
        dto.setMidPriorityPendingQty(safeInt(targetMonthPlan.getMidProductionQty()));
        dto.setConventionProductionPendingQty(safeInt(targetMonthPlan.getConventionProductionQty()));

        // 施工阶段：试制/量试/正规来自月计划，后续影响排序、单控机台选择和严格目标量。
        dto.setConstructionStage(targetMonthPlan.getConstructionStage());
        dto.setTrialDemandQty(safeInt(targetMonthPlan.getTrialQty()));
        dto.setBeginDay(targetMonthPlan.getBeginDay());
        // 月计划结构结束日，仅用于全量SKU排序日志展示，不参与排序与排产
        dto.setEndDay(targetMonthPlan.getEndDay());
        // 月计划模具变化信息只在 S4.5 窗口无日计划历史欠产补排时用于判断计划使用模数。
        dto.setMouldChangeInfo(targetMonthPlan.getMouldChangeInfo());
        dto.setTrial(isTrialStage(targetMonthPlan.getConstructionStage()));
        // 正规SKU仍按月计划表原始 totalQty 标记小批量，仅供分类和排序；不得再决定单控单模/双模。
        // 这里不能再用运行态余量，否则大计划 SKU 会在后期余量变小后被误判成小批量。
        int monthPlanTotalQty = safeInt(targetMonthPlan.getTotalQty());
        boolean isSmallBatch = !dto.isTrial()
                && monthPlanTotalQty <= LhScheduleConstant.SMALL_BATCH_SKU_THRESHOLD;
        dto.setSmallBatchValidation(isSmallBatch);
        if (isSmallBatch) {
            log.info("小批量SKU判定命中, 物料编码: {}, 施工阶段: {}, 月计划totalQty: {}, 固定阈值: {}",
                    dto.getMaterialCode(), dto.getConstructionStage(),
                    monthPlanTotalQty, LhScheduleConstant.SMALL_BATCH_SKU_THRESHOLD);
        }

        // 示方书信息
        dto.setEmbryoNo(targetMonthPlan.getEmbryoNo());
        dto.setTextNo(targetMonthPlan.getTextNo());
        dto.setLhNo(targetMonthPlan.getLhNo());

        // 版本信息
        dto.setMonthPlanVersion(targetMonthPlan.getMonthPlanVersion());
        dto.setProductionVersion(targetMonthPlan.getProductionVersion());

        // 月计划所属年月（来自月计划），用于SKU减量清单按年月精确匹配
        dto.setMonthPlanYear(targetMonthPlan.getYear());
        dto.setMonthPlanMonth(targetMonthPlan.getMonth());

        // 试制SKU严格限制目标量，不允许超出dayN补满班次；量试/正规仍可按后续策略补满可用班次。
        if (StringUtils.equals(ConstructionStageEnum.TRIAL.getCode(), dto.getConstructionStage())) {
            dto.setStrictTargetQty(true);
        }

        // 默认标记为常规
        dto.setSkuTag(SkuTagEnum.NORMAL.getCode());

        return dto;
    }

    /**
     * 解析结果行应使用的目标月月计划。
     * <p>SKU 归集基础计划可能来自窗口 T 日所在月，但结果行的版本、排产分类和产品状态
     * 需要对齐到 scheduleTargetDate 所属月份，避免跨月保存时串到基础计划月份。</p>
     *
     * @param context 排程上下文
     * @param plan SKU归集基础月计划
     * @return 目标月月计划，缺失时回退基础计划
     */
    private FactoryMonthPlanProductionFinalResult resolveTargetMonthPlan(LhScheduleContext context,
                                                                         FactoryMonthPlanProductionFinalResult plan) {
        if (Objects.isNull(context) || Objects.isNull(plan) || StringUtils.isEmpty(plan.getMaterialCode())) {
            return plan;
        }
        LocalDate targetDate = toLocalDate(context.getScheduleTargetDate());
        if (Objects.isNull(targetDate)) {
            return plan;
        }
        FactoryMonthPlanProductionFinalResult targetMonthPlan =
                MonthPlanDateResolver.resolvePlan(context, plan.getMaterialCode(), plan.getProductStatus(), targetDate);
        return Objects.nonNull(targetMonthPlan) ? targetMonthPlan : plan;
    }

    /**
     * 判断施工阶段是否为试制/量试。
     *
     * @param constructionStage 施工阶段
     * @return true-试制/量试
     */
    private boolean isTrialStage(String constructionStage) {
        return StringUtils.equals(ConstructionStageEnum.TRIAL.getCode(), constructionStage)
                || StringUtils.equals(ConstructionStageEnum.MASS_TRIAL.getCode(), constructionStage);
    }

    /**
     * 解析SKU原始胎胚库存。
     * <p>共用胎胚是否分摊依赖收尾标注结果，不能在DTO构建阶段提前分摊。</p>
     *
     * @param context 排程上下文
     * @param plan 月计划
     * @return SKU原始胎胚库存，-1表示库存未知
     */
    private int resolveRawEmbryoStock(LhScheduleContext context,
                                      FactoryMonthPlanProductionFinalResult plan) {
        if (StringUtils.isEmpty(plan.getEmbryoCode())
                || !context.getEmbryoRealtimeStockMap().containsKey(plan.getEmbryoCode())) {
            return -1;
        }
        Integer embryoStock = context.getEmbryoRealtimeStockMap().get(plan.getEmbryoCode());
        if (Objects.isNull(embryoStock)) {
            return -1;
        }
        return embryoStock;
    }

    /**
     * 构建SKU在排程窗口内的日计划额度账本。
     * <p>按排程窗口覆盖的每个自然日，读取月计划对应 dayN 的日计划量，初始化每日额度。</p>
     * <p>依赖 {@link MonthPlanDateResolver#resolveDayQty} 按业务日期所属年月和产品状态读取 dayN 字段。</p>
     *
     * @param context 排程上下文
     * @param plan 月计划记录
     * @param materialCode 物料编码
     * @return 按日期排序的日计划额度Map，key=生产日期
     */
    private Map<LocalDate, SkuDailyPlanQuotaDTO> buildDailyPlanQuotaMap(LhScheduleContext context,
                                                                        FactoryMonthPlanProductionFinalResult plan,
                                                                        String materialCode) {
        Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap = new LinkedHashMap<>();
        if (Objects.isNull(context.getScheduleDate()) || Objects.isNull(context.getWindowEndDate())) {
            return quotaMap;
        }
        Date startDate = LhScheduleTimeUtil.clearTime(context.getScheduleDate());
        Date endDate = LhScheduleTimeUtil.clearTime(context.getWindowEndDate());
        if (startDate.after(endDate)) {
            return quotaMap;
        }
        // 按自然日顺序遍历窗口日期，读取月计划 DAY_n 初始化每日额度
        Calendar cursor = Calendar.getInstance();
        cursor.setTime(startDate);
        Calendar endCalendar = Calendar.getInstance();
        endCalendar.setTime(endDate);
        while (!cursor.after(endCalendar)) {
            LocalDate productionDate = cursor.getTime().toInstant()
                    .atZone(ZoneId.systemDefault()).toLocalDate();
            int dayPlanQty = MonthPlanDateResolver.resolveDayQty(
                    context, materialCode, plan.getProductStatus(), productionDate);
            SkuDailyPlanQuotaDTO quota = new SkuDailyPlanQuotaDTO();
            quota.setMaterialCode(materialCode);
            quota.setProductionDate(productionDate);
            quota.setDayPlanQty(dayPlanQty);
            quota.setScheduledQty(0);
            quota.setRemainingQty(dayPlanQty);
            quota.setShiftFillOverQty(0);
            quota.setCarryLossQty(0);
            quota.setFutureBorrowQty(0);
            quota.setActualQty(0);
            quota.setCumulativeQty(0);
            quota.setFinalLossQty(0);
            quota.setCompleted(false);
            quotaMap.put(productionDate, quota);
            cursor.add(Calendar.DAY_OF_MONTH, 1);
        }
        SkuDailyPlanQuotaUtil.refreshRollingFields(quotaMap);
        log.debug("日计划额度账本初始化, materialCode: {}, 窗口日期数: {}, 总额度: {}",
                materialCode, quotaMap.size(),
                quotaMap.values().stream().mapToInt(SkuDailyPlanQuotaDTO::getDayPlanQty).sum());
        return quotaMap;
    }

    /**
     * 从日计划额度账本中按日期顺序扣减继承量。
     * <p>滚动衔接中已继承的排产量需要从窗口计划额度中扣减，避免重复排产。
     * 扣减策略：从最早日期开始依次扣减，直到继承量全部扣完。</p>
     *
     * @param dailyPlanQuotaMap 日计划额度账本
     * @param inheritedPlanQty 待扣减的继承量
     */
    private void deductInheritedFromDailyQuota(Map<LocalDate, SkuDailyPlanQuotaDTO> dailyPlanQuotaMap,
                                               int inheritedPlanQty) {
        if (inheritedPlanQty <= 0 || dailyPlanQuotaMap == null || dailyPlanQuotaMap.isEmpty()) {
            return;
        }
        int remainingToDeduct = inheritedPlanQty;
        for (SkuDailyPlanQuotaDTO quota : dailyPlanQuotaMap.values()) {
            if (remainingToDeduct <= 0) {
                break;
            }
            int deduction = Math.min(quota.getRemainingQty(), remainingToDeduct);
            quota.setScheduledQty(quota.getScheduledQty() + deduction);
            quota.setRemainingQty(Math.max(0, quota.getRemainingQty() - deduction));
            remainingToDeduct -= deduction;
        }
        SkuDailyPlanQuotaUtil.refreshRollingFields(dailyPlanQuotaMap);
        if (remainingToDeduct > 0) {
            log.debug("继承量超出窗口日计划总额度, 继承量: {}, 窗口总额度: {}, 超出: {}",
                    inheritedPlanQty,
                    dailyPlanQuotaMap.values().stream().mapToInt(SkuDailyPlanQuotaDTO::getDayPlanQty).sum(),
                    remainingToDeduct);
        }
    }

    /**
     * 从日计划额度账本中扣减T日排程晚班完成量。
     * <p>T日晚班完成量已经计入月计划完成量，日计划账本也必须同步扣减，避免续作首日重复排产。</p>
     *
     * @param context 排程上下文
     * @param dailyPlanQuotaMap 日计划额度账本
     * @param materialCode 物料编码
     * @param productStatus 产品状态
     */
    private void deductScheDayFinishFromDailyQuota(LhScheduleContext context,
                                                   Map<LocalDate, SkuDailyPlanQuotaDTO> dailyPlanQuotaMap,
                                                   String materialCode,
                                                   String productStatus) {
        int scheDayFinishQty = resolveScheDayFinishQty(context, materialCode, productStatus);
        if (scheDayFinishQty <= 0) {
            return;
        }
        int deductedQty = deductQuotaByDateOrder(dailyPlanQuotaMap, scheDayFinishQty);
        if (deductedQty > 0) {
            log.info("T日排程晚班完成量扣减日计划账本, materialCode: {}, productStatus: {}, finishQty: {}, deductedQty: {}, windowRemainingQty: {}",
                    materialCode, productStatus, scheDayFinishQty, deductedQty,
                    SkuDailyPlanQuotaUtil.sumRemainingQty(dailyPlanQuotaMap));
        }
        if (deductedQty < scheDayFinishQty) {
            log.debug("T日排程晚班完成量超出窗口日计划额度, materialCode: {}, productStatus: {}, finishQty: {}, deductedQty: {}, overflowQty: {}",
                    materialCode, productStatus, scheDayFinishQty, deductedQty, scheDayFinishQty - deductedQty);
        }
    }

    /**
     * 将本月历史欠产写入首日日计划额度账本。
     * <p>只处理当前排程月份内、早于 T 日的历史欠产；上月欠产和超产都不参与当前窗口。</p>
     *
     * @param dailyPlanQuotaMap 日计划额度账本
     * @param carryForwardQty 本月历史欠产量
     * @param materialCode 物料编码
     */
    private void applyCarryForwardToDailyQuota(Map<LocalDate, SkuDailyPlanQuotaDTO> dailyPlanQuotaMap,
                                               int carryForwardQty,
                                               String materialCode) {
        if (carryForwardQty <= 0 || CollectionUtils.isEmpty(dailyPlanQuotaMap)) {
            return;
        }
        SkuDailyPlanQuotaDTO firstDayQuota = dailyPlanQuotaMap.values().iterator().next();
        firstDayQuota.setRemainingQty(firstDayQuota.getRemainingQty() + carryForwardQty);
        SkuDailyPlanQuotaUtil.refreshRollingFields(dailyPlanQuotaMap);
        log.info("本月历史欠产追加到首日日计划账本, materialCode: {}, historyShortageQty: {}, firstDate: {}, windowRemainingQty: {}",
                materialCode, carryForwardQty, firstDayQuota.getProductionDate(),
                SkuDailyPlanQuotaUtil.sumRemainingQty(dailyPlanQuotaMap));
    }

    /**
     * 解析当前窗口实际生效的本月历史欠产量。
     * <p>只允许正向欠产进入当前窗口；超产和上月欠产已经在归集阶段被过滤。</p>
     *
     * @param context 排程上下文
     * @param materialCode 物料编码
     * @param rawCarryForwardQty 原始本月历史欠产量
     * @return 生效净值
     */
    private int resolveEffectiveCarryForwardQty(LhScheduleContext context,
                                                String materialCode,
                                                int rawCarryForwardQty) {
        int positiveCarryForwardQty = Math.max(0, rawCarryForwardQty);
        if (positiveCarryForwardQty == 0 || isCarryForwardQtyEnabled(context)) {
            return positiveCarryForwardQty;
        }
        log.debug("本月历史欠产追加开关关闭，净值不进入当前窗口需求, materialCode: {}, carryForwardQty: {}",
                materialCode, positiveCarryForwardQty);
        return 0;
    }

    /**
     * 判断是否启用本月历史欠产追加。
     *
     * @param context 排程上下文
     * @return true-启用；false-关闭
     */
    private boolean isCarryForwardQtyEnabled(LhScheduleContext context) {
        if (Objects.nonNull(context) && Objects.nonNull(context.getScheduleConfig())) {
            return context.getScheduleConfig().isCarryForwardQtyEnabled();
        }
        return context.getParamIntValue(LhScheduleParamConstant.ENABLE_CARRY_FORWARD_QTY,
                LhScheduleConstant.ENABLE_CARRY_FORWARD_QTY) == 1;
    }

    /**
     * 按日期顺序扣减日计划额度。
     *
     * @param dailyPlanQuotaMap 日计划额度账本
     * @param qty 待扣减数量
     * @return 实际扣减数量
     */
    private int deductQuotaByDateOrder(Map<LocalDate, SkuDailyPlanQuotaDTO> dailyPlanQuotaMap, int qty) {
        if (qty <= 0 || CollectionUtils.isEmpty(dailyPlanQuotaMap)) {
            return 0;
        }
        int remainingToDeduct = qty;
        int deductedQty = 0;
        for (SkuDailyPlanQuotaDTO quota : dailyPlanQuotaMap.values()) {
            if (remainingToDeduct <= 0) {
                break;
            }
            if (Objects.isNull(quota)) {
                continue;
            }
            int deduction = Math.min(Math.max(0, quota.getRemainingQty()), remainingToDeduct);
            quota.setScheduledQty(quota.getScheduledQty() + deduction);
            quota.setRemainingQty(Math.max(0, quota.getRemainingQty() - deduction));
            deductedQty += deduction;
            remainingToDeduct -= deduction;
        }
        SkuDailyPlanQuotaUtil.refreshRollingFields(dailyPlanQuotaMap);
        return deductedQty;
    }

    /**
     * 解析停产收尾需求量。
     *
     * @param context 排程上下文
     * @param plan 月计划
     * @param embryoStock 胎胚库存
     * @return 停产收尾需求量
     */
    private int resolveStopProductionDemandQty(LhScheduleContext context,
                                               FactoryMonthPlanProductionFinalResult plan,
                                               int embryoStock) {
        int stopDayPlanQty = resolveStopDayPlanQty(context, plan);
        int lossIncludedStopDayPlanQty = resolveLossIncludedStopDayPlanQty(plan, stopDayPlanQty);
        int demandQty = Math.max(lossIncludedStopDayPlanQty, Math.max(0, embryoStock));
        log.info("停产收尾需求量计算完成, materialCode={}, stopDayPlanQty={}, lossIncludedStopDayPlanQty={}, embryoStock={}, demandQty={}",
                plan.getMaterialCode(), stopDayPlanQty, lossIncludedStopDayPlanQty, embryoStock, demandQty);
        return demandQty;
    }

    /**
     * 解析常规待排需求量。
     * <p>待排需求 = Max(月计划余量 - 已继承量, 0) 与胎胚库存取大。</p>
     *
     * @param surplusQty 月计划余量
     * @param inheritedPlanQty 已继承量
     * @param embryoStock 胎胚库存
     * @return 待排需求量
     */
    private int resolveBasePendingQty(int surplusQty,
                                      int inheritedPlanQty,
                                      int embryoStock) {
        int surplusDemandQty = Math.max(0, surplusQty - Math.max(0, inheritedPlanQty));
        int effectiveEmbryoStock = Math.max(0, embryoStock);
        return Math.max(surplusDemandQty, effectiveEmbryoStock);
    }

    /**
     * 解析停产日月计划量。
     *
     * @param context 排程上下文
     * @param plan 月计划
     * @return 停产日计划量
     */
    private int resolveStopDayPlanQty(LhScheduleContext context, FactoryMonthPlanProductionFinalResult plan) {
        if (Objects.isNull(context.getCuringStopPotTime())) {
            return 0;
        }
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(context.getCuringStopPotTime());
        LocalDate stopDate = calendar.getTime().toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
        FactoryMonthPlanProductionFinalResult stopDatePlan =
                MonthPlanDateResolver.resolvePlan(context, plan.getMaterialCode(), plan.getProductStatus(), stopDate);
        if (Objects.nonNull(stopDatePlan)) {
            return Math.max(0, MonthPlanDayQtyUtil.resolveDayQty(stopDatePlan, stopDate.getDayOfMonth()));
        }
        return Math.max(0, MonthPlanDayQtyUtil.resolveDayQty(plan, stopDate.getDayOfMonth()));
    }

    /**
     * 汇总排程窗口结束后的后续月计划日量。
     * <p>仅扫描当前已加载月计划覆盖的自然月，避免把未加载月份误判为 0 计划。</p>
     * <p>仅用于 S4.5 新增排产区分“本月整体收尾”和“当前窗口仅补欠产”，不参与 S4.4 续作。</p>
     *
     * @param context 排程上下文
     * @param plan 月计划记录
     * @return 窗口结束后到已加载覆盖末日的后续日计划汇总
     */
    private int resolveFutureMonthPlanQtyAfterWindow(LhScheduleContext context,
                                                     FactoryMonthPlanProductionFinalResult plan) {
        if (Objects.isNull(context) || Objects.isNull(plan)
                || Objects.isNull(context.getScheduleDate()) || Objects.isNull(context.getWindowEndDate())) {
            return 0;
        }
        LocalDate targetDate = toLocalDate(context.getWindowEndDate());
        LocalDate cursor = targetDate.plusDays(1);
        LocalDate scanEndDate = resolveFuturePlanScanEndDate(context, plan.getMaterialCode(), plan.getProductStatus(),
                targetDate.plusMonths(1).withDayOfMonth(targetDate.plusMonths(1).lengthOfMonth()));
        if (cursor.isAfter(scanEndDate)) {
            return 0;
        }
        int futurePlanQty = 0;
        while (!cursor.isAfter(scanEndDate)) {
            futurePlanQty += MonthPlanDateResolver.resolveDayQty(
                    context, plan.getMaterialCode(), plan.getProductStatus(), cursor);
            cursor = cursor.plusDays(1);
        }
        return Math.max(0, futurePlanQty);
    }

    /**
     * 解析后续计划扫描上界。
     * <p>优先以当前已加载月计划的最晚自然月月末为上界，避免读取未加载月份时把真实未来计划误判为 0。</p>
     *
     * @param context 排程上下文
     * @param materialCode 物料编码
     * @param productStatus 产品状态
     * @param defaultEndDate 默认扫描上界
     * @return 实际扫描上界
     */
    private LocalDate resolveFuturePlanScanEndDate(LhScheduleContext context,
                                                   String materialCode,
                                                   String productStatus,
                                                   LocalDate defaultEndDate) {
        if (Objects.isNull(context) || StringUtils.isEmpty(materialCode) || Objects.isNull(defaultEndDate)) {
            return defaultEndDate;
        }
        List<FactoryMonthPlanProductionFinalResult> loadedPlanList = context.getLoadedMonthPlanList();
        if (CollectionUtils.isEmpty(loadedPlanList)) {
            loadedPlanList = context.getMonthPlanList();
        }
        String trimmedProductStatus = StringUtils.trimToEmpty(productStatus);
        LocalDate loadedCoverageEndDate = null;
        for (FactoryMonthPlanProductionFinalResult loadedPlan : loadedPlanList) {
            if (Objects.isNull(loadedPlan) || !StringUtils.equals(materialCode, loadedPlan.getMaterialCode())) {
                continue;
            }
            boolean productStatusMatched = StringUtils.isEmpty(trimmedProductStatus)
                    || StringUtils.equals(trimmedProductStatus, StringUtils.trimToEmpty(loadedPlan.getProductStatus()));
            if (!productStatusMatched
                    || Objects.isNull(loadedPlan.getYear())
                    || Objects.isNull(loadedPlan.getMonth())) {
                continue;
            }
            LocalDate monthStartDate = LocalDate.of(loadedPlan.getYear(), loadedPlan.getMonth(), 1);
            LocalDate monthEndDate = monthStartDate.withDayOfMonth(monthStartDate.lengthOfMonth());
            if (Objects.isNull(loadedCoverageEndDate) || monthEndDate.isAfter(loadedCoverageEndDate)) {
                loadedCoverageEndDate = monthEndDate;
            }
        }
        if (Objects.isNull(loadedCoverageEndDate)) {
            return defaultEndDate;
        }
        return loadedCoverageEndDate.isBefore(defaultEndDate) ? loadedCoverageEndDate : defaultEndDate;
    }

    /**
     * 解析排程窗口结束后第一天的月计划日量。
     * <p>用于新增排产欠产未超阈值时的 T+2 后看 T+3 判断；
     * 只作为增机台和窗口内满班保留依据，不写入 T～T+2 日计划扣账账本。</p>
     *
     * @param context 排程上下文
     * @param plan 月计划记录
     * @return 窗口后第一天日计划量
     */
    private int resolveNextDayPlanQtyAfterWindow(LhScheduleContext context,
                                                 FactoryMonthPlanProductionFinalResult plan) {
        if (Objects.isNull(context) || Objects.isNull(plan)
                || Objects.isNull(context.getScheduleDate()) || Objects.isNull(context.getWindowEndDate())) {
            return 0;
        }
        LocalDate targetDate = toLocalDate(context.getWindowEndDate());
        LocalDate nextDate = targetDate.plusDays(1);
        return MonthPlanDateResolver.resolveDayQty(
                context, plan.getMaterialCode(), plan.getProductStatus(), nextDate);
    }

    /**
     * 按月计划含损耗需求折算停产日计划量。
     *
     * @param plan 月计划
     * @param stopDayPlanQty 停产日计划量
     * @return 含损耗停产日计划量
     */
    private int resolveLossIncludedStopDayPlanQty(FactoryMonthPlanProductionFinalResult plan, int stopDayPlanQty) {
        if (stopDayPlanQty <= 0
                || Objects.isNull(plan.getFactProdReqQty())
                || Objects.isNull(plan.getTotalQty())
                || plan.getTotalQty() <= 0
                || plan.getFactProdReqQty() <= plan.getTotalQty()) {
            return Math.max(stopDayPlanQty, 0);
        }
        return BigDecimal.valueOf(stopDayPlanQty)
                .multiply(BigDecimal.valueOf(plan.getFactProdReqQty()))
                .divide(BigDecimal.valueOf(plan.getTotalQty()), 0, RoundingMode.UP)
                .intValue();
    }

    /**
     * 达到开产欠产阈值时写入现有未排结果链路。
     *
     * @param context 排程上下文
     * @param sku SKU排程DTO
     * @return void
     */
    private void appendOpenProductionShortageIfNecessary(LhScheduleContext context, SkuScheduleDTO sku) {
        if (!context.isOpenProductionMode() || Objects.isNull(sku)) {
            return;
        }
        int pendingQty = Math.max(0, sku.getPendingQty());
        int targetQty = Math.max(0, sku.resolveTargetScheduleQty());
        int shortageQty = pendingQty - targetQty;
        if (pendingQty <= 0 || shortageQty <= 0) {
            return;
        }
        BigDecimal shortageRate = BigDecimal.valueOf(shortageQty)
                .divide(BigDecimal.valueOf(pendingQty), RATE_DISPLAY_SCALE, RoundingMode.HALF_UP);
        BigDecimal thresholdRate = Objects.nonNull(context.getOpenProductionShortageThresholdRate())
                ? context.getOpenProductionShortageThresholdRate() : LhScheduleConstant.OPEN_PRODUCTION_SHORTAGE_THRESHOLD_RATE;
        if (shortageRate.compareTo(thresholdRate) < 0) {
            return;
        }
        String reason = String.format(OPEN_PRODUCTION_SHORTAGE_REASON_TEMPLATE,
                sku.getMaterialCode(), pendingQty, targetQty, shortageQty,
                shortageRate.stripTrailingZeros().toPlainString(),
                thresholdRate.stripTrailingZeros().toPlainString());
        log.warn(reason);

        LhUnscheduledResult unscheduled = buildBaseUnscheduledResult(context, sku);
        unscheduled.setUnscheduledQty(shortageQty);
        unscheduled.setUnscheduledReason(reason);
        context.getUnscheduledResultList().add(unscheduled);
    }

    /**
     * 追加"无计划量不排产"的未排结果。
     *
     * @param context 排程上下文
     * @param sku SKU排程DTO
     */
    private void addNoPlanUnscheduledResult(LhScheduleContext context, SkuScheduleDTO sku) {
        String reason = resolveNoPlanUnscheduledReason(sku);
        log.warn(reason);

        LhUnscheduledResult unscheduled = buildBaseUnscheduledResult(context, sku);
        unscheduled.setUnscheduledQty(0);
        unscheduled.setUnscheduledReason(reason);
        context.getUnscheduledResultList().add(unscheduled);
    }

    /**
     * 解析无目标量场景的未排原因。
     *
     * @param sku SKU排程DTO
     * @return 未排原因
     */
    private String resolveNoPlanUnscheduledReason(SkuScheduleDTO sku) {
        if (isZeroSurplusAndEmbryoStockSku(sku)) {
            return String.format(ZERO_SURPLUS_AND_EMBRYO_REASON_TEMPLATE, sku.getMaterialCode());
        }
        return String.format(NO_PLAN_QTY_REASON_TEMPLATE, sku.getMaterialCode());
    }

    /**
     * 判断是否命中“余量为0且胎胚库存为0”的免排场景。
     *
     * @param sku SKU排程DTO
     * @return true-命中，false-未命中
     */
    private boolean isZeroSurplusAndEmbryoStockSku(SkuScheduleDTO sku) {
        if (Objects.isNull(sku)) {
            return false;
        }
        return sku.getSurplusQty() <= 0 && sku.getEmbryoStock() <= 0;
    }

    /**
     * 构建未排结果公共字段。
     *
     * @param context 排程上下文
     * @param sku SKU排程DTO
     * @return 未排结果
     */
    private LhUnscheduledResult buildBaseUnscheduledResult(LhScheduleContext context, SkuScheduleDTO sku) {
        LhUnscheduledResult unscheduled = new LhUnscheduledResult();
        unscheduled.setFactoryCode(context.getFactoryCode());
        unscheduled.setBatchNo(context.getBatchNo());
        unscheduled.setScheduleDate(context.getScheduleTargetDate());
        unscheduled.setMonthPlanVersion(sku.getMonthPlanVersion());
        unscheduled.setProductionVersion(sku.getProductionVersion());
        unscheduled.setMaterialCode(sku.getMaterialCode());
        unscheduled.setProductStatus(sku.getProductStatus());
        unscheduled.setStructureName(sku.getStructureName());
        unscheduled.setMaterialDesc(sku.getMaterialDesc());
        unscheduled.setMainMaterialDesc(sku.getMainMaterialDesc());
        unscheduled.setSpecCode(sku.getSpecCode());
        unscheduled.setEmbryoCode(sku.getEmbryoCode());
        unscheduled.setMouldQty(sku.getMouldQty());
        unscheduled.setDataSource(DATA_SOURCE_AUTO);
        unscheduled.setIsDelete(DELETE_FLAG_NORMAL);
        return unscheduled;
    }

    private int resolveShiftFinishedQty(LhScheduleResult result, LhScheduleContext context) {
        List<LhShiftConfigVO> shifts = context.getScheduleWindowShifts();
        if (CollectionUtils.isEmpty(shifts)) {
            shifts = LhScheduleTimeUtil.getScheduleShifts(context, context.getScheduleDate());
        }
        int finishedQty = 0;
        for (LhShiftConfigVO shift : shifts) {
            finishedQty += safeInt(ShiftFieldUtil.getShiftFinishQty(result, shift.getShiftIndex()));
        }
        return finishedQty;
    }

    /**
     * 解析欠/超产传导使用的计划量。
     *
     * @param context 排程上下文
     * @param result 前批次排程结果
     * @return 参与传导的计划量
     */
    private int resolveCarryForwardPlanQty(LhScheduleContext context, LhScheduleResult result) {
        if (!context.isRollingScheduleHandoff()) {
            return ShiftFieldUtil.sumPlanQty(result, LhScheduleConstant.MAX_SHIFT_SLOT_COUNT);
        }
        // 结转边界 = 排程窗口最早班次开始时间，此前的班次量参与欠超产传导，此后的已被继承
        Date carryForwardBoundaryTime = resolveCarryForwardBoundaryTime(context);
        int totalQty = 0;
        for (int shiftIndex = 1; shiftIndex <= LhScheduleConstant.MAX_SHIFT_SLOT_COUNT; shiftIndex++) {
            Date endTime = ShiftFieldUtil.getShiftEndTime(result, shiftIndex);
            // 滚动衔接场景下，只结转完整结束在继承窗口前的班次，避免与继承量重复。
            if (Objects.nonNull(endTime) && !endTime.after(carryForwardBoundaryTime)) {
                totalQty += safeInt(ShiftFieldUtil.getShiftPlanQty(result, shiftIndex));
            }
        }
        return totalQty;
    }

    /**
     * 解析滚动排程结转边界。
     *
     * @param context 排程上下文
     * @return 结转边界时间
     */
    private Date resolveCarryForwardBoundaryTime(LhScheduleContext context) {
        return context.getScheduleWindowShifts().stream()
                .map(LhShiftConfigVO::getShiftStartDateTime)
                .filter(Objects::nonNull)
                .min(Date::compareTo)
                .orElseThrow(() -> new IllegalStateException("滚动排程结转边界解析失败：排程窗口班次为空"));
    }

    /**
     * 获取指定日期的物料日完成量（按"物料+产品状态+日期"聚合）。
     *
     * @param context 排程上下文
     * @param plan 月计划
     * @param finishDate 完成日期
     * @return 日完成量
     */
    private int resolveMaterialDayFinishedQty(LhScheduleContext context,
                                              FactoryMonthPlanProductionFinalResult plan,
                                              Date finishDate) {
        if (Objects.isNull(plan) || StringUtils.isEmpty(plan.getMaterialCode()) || Objects.isNull(finishDate)) {
            return 0;
        }
        String key = buildMaterialDayKey(plan.getMaterialCode(), plan.getProductStatus(), finishDate);
        Integer dayFinishedQty = context.getMaterialDayFinishedQtyMap().get(key);
        return Objects.nonNull(dayFinishedQty) ? Math.max(dayFinishedQty, 0) : 0;
    }

    /**
     * 统计当前排程月份内、且早于 T 日的历史欠产摘要。
     *
     * @param context 排程上下文
     * @param plan 月计划
     * @param monthStartDate 当前排程月份起始日
     * @param historyEndDate 历史统计截止日（T-1）
     * @return 历史欠产摘要
     */
    private MonthlyShortageSummary calculateCurrentMonthShortageSummary(LhScheduleContext context,
                                                                       FactoryMonthPlanProductionFinalResult plan,
                                                                       LocalDate monthStartDate,
                                                                       LocalDate historyEndDate) {
        if (Objects.isNull(context) || Objects.isNull(plan) || StringUtils.isEmpty(plan.getMaterialCode())
                || Objects.isNull(monthStartDate) || Objects.isNull(historyEndDate)
                || historyEndDate.isBefore(monthStartDate)) {
            return MonthlyShortageSummary.empty();
        }
        int shortageQty = 0;
        int ignoredOverProductionQty = 0;
        StringBuilder detailBuilder = new StringBuilder(128);
        LocalDate cursor = monthStartDate;
        while (!cursor.isAfter(historyEndDate)) {
            int dayPlanQty = MonthPlanDateResolver.resolveDayQty(
                    context, plan.getMaterialCode(), plan.getProductStatus(), cursor);
            int finishedQty = resolveMonthDailyFinishedQty(context, plan, cursor);
            int dayShortageQty = Math.max(dayPlanQty - finishedQty, 0);
            int dayIgnoredOverQty = Math.max(finishedQty - dayPlanQty, 0);
            shortageQty += dayShortageQty;
            ignoredOverProductionQty += dayIgnoredOverQty;
            if (detailBuilder.length() > 0) {
                detailBuilder.append("; ");
            }
            detailBuilder.append(cursor)
                    .append("[计划=").append(dayPlanQty)
                    .append(", 完成=").append(finishedQty)
                    .append(", 欠产=").append(dayShortageQty)
                    .append(", 忽略超产=").append(dayIgnoredOverQty)
                    .append("]");
            cursor = cursor.plusDays(1);
        }
        log.info("本月历史欠产统计, materialCode: {}, productStatus: {}, scheduleMonth: {}, historyRange: {}~{}, shortageQty: {}, ignoredOverProductionQty: {}, detail: {}",
                plan.getMaterialCode(), plan.getProductStatus(), monthStartDate.getMonthValue(), monthStartDate, historyEndDate,
                shortageQty, ignoredOverProductionQty, detailBuilder.toString());
        return new MonthlyShortageSummary(shortageQty, ignoredOverProductionQty);
    }

    /**
     * 记录被忽略的上月欠产边界，便于核对月初不跨月追补。
     *
     * @param context 排程上下文
     * @param monthStartDate 当前排程月份起始日
     */
    private void logIgnoredPreviousMonthCarryForward(LhScheduleContext context, LocalDate monthStartDate) {
        Date previousScheduleDate = resolvePreviousScheduleDate(context);
        if (Objects.isNull(previousScheduleDate)) {
            return;
        }
        LocalDate previousDate = toLocalDate(previousScheduleDate);
        if (!previousDate.isBefore(monthStartDate)) {
            return;
        }
        log.info("上月欠产边界已忽略, factoryCode: {}, ignoredDate: {}, currentMonthStart: {}",
                context.getFactoryCode(), previousDate, monthStartDate);
    }

    /**
     * 获取当前排程月份内某个物料+产品状态在指定自然日的完成量。
     *
     * @param context 排程上下文
     * @param plan 月计划
     * @param productionDate 自然日
     * @return 日完成量
     */
    private int resolveMonthDailyFinishedQty(LhScheduleContext context,
                                             FactoryMonthPlanProductionFinalResult plan,
                                             LocalDate productionDate) {
        if (Objects.isNull(context) || Objects.isNull(plan) || StringUtils.isEmpty(plan.getMaterialCode())
                || Objects.isNull(productionDate)) {
            return 0;
        }
        Integer finishedQty = context.getMaterialMonthDailyFinishedQtyMap()
                .get(buildMaterialStatusKey(plan.getMaterialCode(), plan.getProductStatus()) + "_" + productionDate);
        return Objects.nonNull(finishedQty) ? Math.max(finishedQty, 0) : 0;
    }

    /**
     * 构建"物料+产品状态"聚合Key。
     *
     * @param materialCode 物料编码
     * @param productStatus 产品状态
     * @return 聚合Key
     */
    private String buildMaterialStatusKey(String materialCode, String productStatus) {
        String trimmedProductStatus = StringUtils.trimToEmpty(productStatus);
        if (StringUtils.isEmpty(trimmedProductStatus)) {
            return materialCode;
        }
        return materialCode + "_" + trimmedProductStatus;
    }

    /**
     * 构建"物料+产品状态+日期"聚合Key。
     *
     * @param materialCode 物料编码
     * @param productStatus 产品状态
     * @param date 日期
     * @return 聚合Key
     */
    private String buildMaterialDayKey(String materialCode, String productStatus, Date date) {
        return buildMaterialStatusKey(materialCode, productStatus) + "_"
                + LhScheduleTimeUtil.formatDate(LhScheduleTimeUtil.clearTime(date));
    }

    /**
     * Date 转 LocalDate。
     *
     * @param date 日期
     * @return LocalDate
     */
    private LocalDate toLocalDate(Date date) {
        return LhScheduleTimeUtil.clearTime(date).toInstant()
                .atZone(ZoneId.systemDefault()).toLocalDate();
    }

    /**
     * 解析前日排程日期。
     *
     * @param context 排程上下文
     * @return 前日日期
     */
    private Date resolvePreviousScheduleDate(LhScheduleContext context) {
        // 滚动衔接或强制重排时，前日基线以窗口起点T日前一日为准。
        if (isPreviousBaselineFromScheduleDate(context) && Objects.nonNull(context.getScheduleDate())) {
            return LhScheduleTimeUtil.clearTime(LhScheduleTimeUtil.addDays(context.getScheduleDate(), -1));
        }
        if (Objects.nonNull(context.getScheduleTargetDate())) {
            return LhScheduleTimeUtil.clearTime(LhScheduleTimeUtil.addDays(context.getScheduleTargetDate(), -1));
        }
        return LhScheduleTimeUtil.clearTime(context.getScheduleDate());
    }

    /**
     * 判断前日传导基线是否应以窗口起点T日计算。
     *
     * @param context 排程上下文
     * @return true-使用T日前一日
     */
    private boolean isPreviousBaselineFromScheduleDate(LhScheduleContext context) {
        return context.isRollingScheduleHandoff()
                || context.getParamIntValue(LhScheduleParamConstant.FORCE_RESCHEDULE,
                        LhScheduleConstant.FORCE_RESCHEDULE) == LhScheduleConstant.FORCE_RESCHEDULE_ENABLED;
    }

    /**
     * 填充日硫化产能，供统一收尾判定策略（待排量与日产对比）使用
     *
     * @param dto      SKU排程DTO（需已设置 dailyPlanQty、shiftCapacity）
     * @param capacity SKU硫化产能主数据，可为null
     */
    private void fillDailyCapacity(SkuScheduleDTO dto, MdmSkuLhCapacity capacity) {
        int dailyCap = 0;
        if (capacity != null) {
            if (capacity.getApsCapacity() != null && capacity.getApsCapacity() > 0) {
                dailyCap = capacity.getApsCapacity();
            } else if (capacity.getStandardCapacity() != null && capacity.getStandardCapacity() > 0) {
                dailyCap = capacity.getStandardCapacity();
            }
        }
        if (dailyCap <= 0 && dto.getShiftCapacity() > 0) {
            dailyCap = dto.getShiftCapacity() * LhScheduleConstant.DEFAULT_SHIFTS_PER_DAY;
        }
        if (dailyCap <= 0 && dto.getDailyPlanQty() > 0) {
            dailyCap = dto.getDailyPlanQty();
        }
        dto.setDailyCapacity(dailyCap);
    }

    /**
     * 判断SKU目标月是否有交期锁定。
     * <p>从月计划 IS_LOCK_SCHEDULE 字段取值：1-锁定，0-未锁定。</p>
     *
     * @param context 排程上下文
     * @param plan 目标月计划
     * @return true-有锁定交期
     */
    private boolean isDeliveryLocked(LhScheduleContext context, FactoryMonthPlanProductionFinalResult plan) {
        if (Objects.isNull(context) || Objects.isNull(plan)) {
            return false;
        }
        return StringUtils.equals("1", StringUtils.trimToEmpty(plan.getIsLockSchedule()));
    }

    /**
     * 基于月计划 day1～day31 中最早有计划量的日期计算延迟上机天数。
     * <p>
     * beginDay 取值口径：不再直接使用月计划的 beginDay 字段，而是遍历 day1～day31，
     * 取第一个计划量 > 0 的 dayN 作为 beginDay。
     * 若 day1～day31 全部为 0 或 null，则返回 null，不参与延期排序。
     * </p>
     * <p>
     * 计算公式：beginDate（year+month+beginDay构建完整日期）- T日（scheduleDate），
     * 负数表示已过开始日（延误），正数表示尚未到开始日（富余），null表示无法计算。
     * </p>
     *
     * @param context 排程上下文
     * @param plan    月生产计划
     * @return 延迟天数=最早有计划量日期距T日的天数差（beginDate - scheduleDate），无法计算时返回null
     */
    private Integer resolveDelayDays(LhScheduleContext context, FactoryMonthPlanProductionFinalResult plan) {
        if (context.getScheduleDate() == null) {
            return null;
        }
        if (plan == null) {
            return null;
        }
        // 从 day1～day31 中查找最早有计划量的日期，替代原 beginDay 字段
        int firstPlannedDay = MonthPlanDayQtyUtil.resolveFirstPlannedDay(plan);
        if (firstPlannedDay < MIN_DAY_OF_MONTH) {
            // day1～day31 全部为 0 或 null，无法确定起产日，不参与延期排序
            log.info("延期天数计算跳过, 物料: {}, 原因: day1~day31均无计划量",
                    plan.getMaterialCode());
            return null;
        }
        Integer year = plan.getYear();
        Integer month = plan.getMonth();
        if (year == null || month == null) {
            return null;
        }
        // 使用最早有计划量的日期构建起产日（清零时分秒毫秒）
        Calendar beginCal = Calendar.getInstance();
        beginCal.set(year, month - 1, firstPlannedDay, 0, 0, 0);
        beginCal.set(Calendar.MILLISECOND, 0);
        // 构建T日日期（清零时分秒毫秒）
        Calendar scheduleCal = Calendar.getInstance();
        scheduleCal.setTime(context.getScheduleDate());
        scheduleCal.set(Calendar.HOUR_OF_DAY, 0);
        scheduleCal.set(Calendar.MINUTE, 0);
        scheduleCal.set(Calendar.SECOND, 0);
        scheduleCal.set(Calendar.MILLISECOND, 0);
        // 计算天数差：beginDate - scheduleDate
        long diffMillis = beginCal.getTimeInMillis() - scheduleCal.getTimeInMillis();
        int delayDays = (int) (diffMillis / (24 * 60 * 60 * 1000));
        log.info("延期天数计算, 物料: {}, 原beginDay: {}, 最早有计划量dayN: {}, 延期天数: {}",
                plan.getMaterialCode(), plan.getBeginDay(), firstPlannedDay, delayDays);
        return delayDays;
    }

    /**
     * 标注收尾SKU
     * <p>委托收尾判定策略接口，与续作收尾判定、排序规则保持一致</p>
     *
     * @param context 排程上下文
     */
    private void markEndingSkus(LhScheduleContext context) {
        int endingCount = 0;
        for (List<SkuScheduleDTO> skuList : context.getStructureSkuMap().values()) {
            for (SkuScheduleDTO sku : skuList) {
                if (endingJudgmentStrategy.isExpectedEnding(context, sku)) {
                    sku.setSkuTag(SkuTagEnum.ENDING.getCode());
                    int endingDays = endingJudgmentStrategy.calculateEndingDays(context, sku);
                    if (endingDays < 0) {
                        // 班产缺失无法折算班次数时，收尾日保守记为 1
                        sku.setEndingDaysRemaining(1);
                    } else {
                        sku.setEndingDaysRemaining(endingDays);
                    }
                    endingCount++;
                }
            }
        }
        log.info("收尾SKU标注完成, 收尾数量: {}", endingCount);
    }

    /**
     * 区分续作SKU和新增SKU
     * <p>
     * 续作SKU：MES在机信息显示当前正在生产的规格，直接延续<br/>
     * 新增SKU：月计划中需要上机但当前未在产的规格，需换模上机
     * </p>
     *
     * @param context 排程上下文
     */
    private void classifyContinuousAndNewSkus(LhScheduleContext context) {
        List<SkuScheduleDTO> continuousSkuList = new ArrayList<>();
        List<SkuScheduleDTO> newSpecSkuList = new ArrayList<>();
        List<SkuScheduleDTO> blockedDailyPlanSkuList = new ArrayList<SkuScheduleDTO>(8);
        Map<String, List<SkuScheduleDTO>> skuByMaterialMap = buildSkuByMaterialMap(context);
        Map<String, SkuScheduleDTO> continuousTemplateMap = new LinkedHashMap<String, SkuScheduleDTO>(16);

        // 第一轮仅做“物料+产品状态”精确匹配，先为所有机台保留可精确命中的月计划SKU。
        assignContinuousSkus(context, skuByMaterialMap, continuousTemplateMap, continuousSkuList, false);
        // 第二轮才沿用既有物料降级，避免未知状态机台抢占后续可精确匹配的S/T/X月计划SKU。
        assignContinuousSkus(context, skuByMaterialMap, continuousTemplateMap, continuousSkuList, true);

        for (List<SkuScheduleDTO> skuList : context.getStructureSkuMap().values()) {
            for (SkuScheduleDTO sku : skuList) {
                if (StringUtils.equals(ScheduleTypeEnum.CONTINUOUS.getCode(), sku.getScheduleType())) {
                    continue;
                }
                /*
                 * 非续作SKU正式进入换活字块、历史交替反选和普通新增选机前，统一判断T～窗口结束日后N天
                 * 是否存在日计划量；完整范围无量时，再按后物料检查前日排程T+1交替承接关系。
                 * 该前置规则只决定是否进入后续主链，不改变当前遍历顺序、SKU排序或任何资源校验逻辑。
                 */
                LhUnscheduledResult dailyPlanUnscheduledResult =
                        PendingSkuUnscheduledRule.evaluateDailyPlanAdmission(context, sku);
                if (Objects.nonNull(dailyPlanUnscheduledResult)) {
                    context.getUnscheduledResultList().add(dailyPlanUnscheduledResult);
                    blockedDailyPlanSkuList.add(sku);
                    continue;
                }
                // 未命中MES在机记录的SKU按新增规格处理。
                sku.setScheduleType(ScheduleTypeEnum.NEW_SPEC.getCode());
                sku.setContinuousMachineCode(null);
                newSpecSkuList.add(sku);
            }
        }

        // 遍历完成后统一清理，避免修改正在遍历的结构SKU集合。
        for (SkuScheduleDTO blockedSku : blockedDailyPlanSkuList) {
            cleanupBlockedNewSku(context, blockedSku,
                    PendingSkuUnscheduledRule.DAILY_PLAN_ADMISSION_UNSCHEDULED_REASON);
        }

        // 续作匹配完成后，在机物料本次不需要排程（余量为0/共用胎胚零余量/未排等）的机台，
        // 收尾时间重置为排程窗口首班开始时间，使该机台从窗口起点即可参与新增排产换模。
        resetIdleMachineEndingTimeToWindowStart(context, continuousSkuList);

        context.setContinuousSkuList(continuousSkuList);
        context.setNewSpecSkuList(newSpecSkuList);
        // 填充全量SKU排程信息复合索引，供S4.5.1置换等后置阶段精确找回产品状态对应SKU。
        for (SkuScheduleDTO sku : continuousSkuList) {
            registerAllSkuScheduleDto(context, sku);
        }
        for (SkuScheduleDTO sku : newSpecSkuList) {
            registerAllSkuScheduleDto(context, sku);
        }
        // SKU减量清单统一前置过滤：命中减量清单的SKU不进入任何排产入口，写未排并从排产集合移除
        skuDecrementChecker.filterDecrementSkus(context);
        log.info("续作/新增SKU区分完成, 续作: {}个, 新增: {}个", continuousSkuList.size(), newSpecSkuList.size());
    }

    /**
     * 清理已判定不进入新增排产的SKU运行态数据。
     * <p>该方法必须在结构SKU遍历完成后调用，避免遍历过程中修改结构集合。清理后SKU不会进入新增、
     * 换活字块、空闲产能补排或胎胚动态分配入口。</p>
     *
     * @param context 排程上下文
     * @param sku 被拦截的SKU
     * @param reason 未排原因，用于胎胚活跃集合清理日志
     */
    private void cleanupBlockedNewSku(LhScheduleContext context, SkuScheduleDTO sku, String reason) {
        if (Objects.isNull(context) || Objects.isNull(sku)) {
            return;
        }
        sku.setTargetScheduleQty(0);
        sku.setRemainingScheduleQty(0);
        context.removePendingSkuFromStructureMap(sku);
        context.getAllSkuScheduleDtoMap().remove(MonthPlanDateResolver.buildMaterialStatusKey(
                sku.getMaterialCode(), sku.getProductStatus()));
        getTargetScheduleQtyResolver().removeActiveEmbryoSku(context, sku, reason);
    }

    /**
     * 按精确优先、降级在后的顺序分配MES与滚动续作SKU。
     *
     * @param context 排程上下文
     * @param skuByMaterialMap 物料编码到待匹配SKU列表
     * @param continuousTemplateMap 已命中产品状态的续作模板
     * @param continuousSkuList 续作SKU结果列表
     * @param allowMaterialFallback 是否允许产品状态未命中时按物料降级
     */
    private void assignContinuousSkus(LhScheduleContext context,
                                      Map<String, List<SkuScheduleDTO>> skuByMaterialMap,
                                      Map<String, SkuScheduleDTO> continuousTemplateMap,
                                      List<SkuScheduleDTO> continuousSkuList,
                                      boolean allowMaterialFallback) {
        // 保持MES最近快照顺序消费，同时优先承接滚动衔接后的机台当前物料。
        Map<String, MachineScheduleDTO> schedulableMachineMap = context.getMachineScheduleMap();
        for (Map.Entry<String, LhMachineOnlineInfo> entry : context.getMachineOnlineInfoMap().entrySet()) {
            if (CollectionUtils.isEmpty(schedulableMachineMap)
                    || !schedulableMachineMap.containsKey(entry.getKey())) {
                continue;
            }
            LhScheduleResult rollingResult = resolveRollingContinuousResult(
                    context, entry.getKey(), schedulableMachineMap.get(entry.getKey()));
            String materialCode = Objects.nonNull(rollingResult)
                    ? rollingResult.getMaterialCode() : entry.getValue().getMaterialCode();
            String productStatus = Objects.nonNull(rollingResult)
                    ? resolveSameMaterialStatusRollingProductStatus(
                    context, entry.getKey(), rollingResult, skuByMaterialMap)
                    : entry.getValue().getProductStatus();
            assignContinuousSku(entry.getKey(), materialCode, productStatus, skuByMaterialMap,
                    continuousTemplateMap, continuousSkuList, allowMaterialFallback);
        }

        if (context.isRollingScheduleHandoff() && !CollectionUtils.isEmpty(schedulableMachineMap)) {
            for (Map.Entry<String, MachineScheduleDTO> entry : schedulableMachineMap.entrySet()) {
                LhScheduleResult rollingResult = resolveRollingContinuousResult(
                        context, entry.getKey(), entry.getValue());
                assignContinuousSku(entry.getKey(),
                        Objects.nonNull(rollingResult) ? rollingResult.getMaterialCode() : null,
                        Objects.nonNull(rollingResult)
                                ? resolveSameMaterialStatusRollingProductStatus(
                                context, entry.getKey(), rollingResult, skuByMaterialMap)
                                : null,
                        skuByMaterialMap, continuousTemplateMap, continuousSkuList, allowMaterialFallback);
            }
        }
    }

    /**
     * 还原同物料多状态状态链跨滚动窗口后的正规续作身份和原承接机台。
     *
     * <p>专用X/T结果会在每个有效班次写入持久化标记。下一滚动窗口即使最新结果仍是X/T，
     * 也必须把同物料正规SKU继续识别为该机台的续作，并把剩余X/T锁回同一机台。</p>
     *
     * @param context 排程上下文
     * @param machineCode 滚动继承机台编码
     * @param rollingResult 最新滚动继承结果
     * @param skuByMaterialMap 当前待匹配SKU集合
     * @return 续作匹配应使用的产品状态
     */
    private String resolveSameMaterialStatusRollingProductStatus(
            LhScheduleContext context,
            String machineCode,
            LhScheduleResult rollingResult,
            Map<String, List<SkuScheduleDTO>> skuByMaterialMap) {
        if (Objects.isNull(rollingResult)
                || !containsSameMaterialStatusContinuationAnalysis(rollingResult)) {
            return Objects.nonNull(rollingResult) ? rollingResult.getProductStatus() : null;
        }
        List<SkuScheduleDTO> sameMaterialSkuList = skuByMaterialMap.get(
                rollingResult.getMaterialCode());
        if (CollectionUtils.isEmpty(sameMaterialSkuList)) {
            return rollingResult.getProductStatus();
        }
        boolean hasFormalSku = false;
        for (SkuScheduleDTO sku : sameMaterialSkuList) {
            if (Objects.isNull(sku)) {
                continue;
            }
            String productStatus = normalizeOnlineProductStatus(sku.getProductStatus());
            if (StringUtils.equals(TrialStatusEnum.FORMAL.getCode(), productStatus)) {
                hasFormalSku = true;
                continue;
            }
            if ((StringUtils.equals(TrialStatusEnum.TRIAL.getCode(), productStatus)
                    || StringUtils.equals(TrialStatusEnum.MASS_TRIAL.getCode(), productStatus))
                    && sku.resolveTargetScheduleQty() > 0) {
                sku.setPreferredContinuousMachineCode(machineCode);
            }
        }
        if (!hasFormalSku) {
            return rollingResult.getProductStatus();
        }
        log.info("同物料多状态续作跨窗口身份还原, factoryCode: {}, batchNo: {}, materialCode: {}, "
                        + "rollingProductStatus: {}, formalProductStatus: {}, carrierMachineCode: {}",
                context.getFactoryCode(), context.getBatchNo(), rollingResult.getMaterialCode(),
                rollingResult.getProductStatus(), TrialStatusEnum.FORMAL.getCode(), machineCode);
        return TrialStatusEnum.FORMAL.getCode();
    }

    /**
     * 判断滚动继承结果是否带有同物料多状态续作链标记。
     *
     * @param result 滚动继承结果
     * @return true-专用状态链结果；false-普通续作结果
     */
    private boolean containsSameMaterialStatusContinuationAnalysis(LhScheduleResult result) {
        if (Objects.isNull(result)) {
            return false;
        }
        for (int shiftIndex = 1;
             shiftIndex <= LhScheduleConstant.MAX_SHIFT_SLOT_COUNT;
             shiftIndex++) {
            if (StringUtils.contains(
                    ShiftFieldUtil.getShiftAnalysis(result, shiftIndex),
                    LhScheduleConstant.SAME_MATERIAL_STATUS_CONTINUATION_ANALYSIS)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 将SKU登记到物料状态复合索引。
     *
     * @param context 排程上下文
     * @param sku SKU排程信息
     */
    private void registerAllSkuScheduleDto(LhScheduleContext context, SkuScheduleDTO sku) {
        if (Objects.isNull(context) || Objects.isNull(sku) || StringUtils.isEmpty(sku.getMaterialCode())) {
            return;
        }
        String skuKey = MonthPlanDateResolver.buildMaterialStatusKey(
                sku.getMaterialCode(), sku.getProductStatus());
        context.getAllSkuScheduleDtoMap().putIfAbsent(skuKey, sku);
    }

    /**
     * 在机物料不需要排程的机台，收尾时间重置为排程窗口首班开始时间。
     * <p>续作SKU匹配完成后，如果机台在机物料本次不需要排产（余量为0、共用胎胚零余量、
     * 列入未排等），该机台不应被在机物料的规格结束时间锚定，而应从窗口首班开始
     * 即可参与新增排产选机和换模。</p>
     *
     * @param context 排程上下文
     * @param continuousSkuList 已匹配的续作SKU列表
     */
    private void resetIdleMachineEndingTimeToWindowStart(LhScheduleContext context,
                                                         List<SkuScheduleDTO> continuousSkuList) {
        if (Objects.isNull(context) || Objects.isNull(context.getScheduleConfig())
                || !context.getScheduleConfig().isForceRescheduleEnabled()
                || CollectionUtils.isEmpty(context.getMachineScheduleMap())
                || CollectionUtils.isEmpty(context.getScheduleWindowShifts())) {
            return;
        }
        // 收集已分配续作SKU的机台编码
        Set<String> continuousMachineCodeSet = new HashSet<String>(8);
        if (!CollectionUtils.isEmpty(continuousSkuList)) {
            for (SkuScheduleDTO sku : continuousSkuList) {
                if (Objects.nonNull(sku) && StringUtils.isNotEmpty(sku.getContinuousMachineCode())) {
                    continuousMachineCodeSet.add(sku.getContinuousMachineCode());
                }
            }
        }
        // 排程窗口首班开始时间
        Date windowStartTime = context.getScheduleWindowShifts().stream()
                .map(LhShiftConfigVO::getShiftStartDateTime)
                .filter(Objects::nonNull)
                .min(Date::compareTo)
                .orElse(null);
        if (Objects.isNull(windowStartTime)) {
            return;
        }
        for (Map.Entry<String, MachineScheduleDTO> entry : context.getMachineScheduleMap().entrySet()) {
            MachineScheduleDTO machine = entry.getValue();
            if (Objects.isNull(machine) || StringUtils.isEmpty(machine.getCurrentMaterialCode())) {
                continue;
            }
            // 已分配续作SKU的机台保留在机物料的收尾时间
            if (continuousMachineCodeSet.contains(entry.getKey())) {
                continue;
            }
            // 在机物料不需要排程，收尾时间重置为窗口首班开始时间，
            // 使机台从窗口起点即可参与新增排产换模，不被在机物料的规格结束时间锚定。
            Date originalEndTime = machine.getEstimatedEndTime();
            machine.setEstimatedEndTime(windowStartTime);
            // 同步更新初始机台快照，避免续作策略 restoreMachineStateFromInitial 回退到旧收尾时间。
            MachineScheduleDTO initialMachine = context.getInitialMachineScheduleMap().get(entry.getKey());
            if (Objects.nonNull(initialMachine)) {
                initialMachine.setEstimatedEndTime(windowStartTime);
            }
            // 同步修正滚动衔接继承结果的结束时间，避免 resolveLatestAssignedEndTime 仍取旧时间；
            // 保留继承结果用于识别机台当前物料和前规格，不能直接删除导致换模匹配画像丢失。
            alignInheritedMachineAssignmentEndTime(context, entry.getKey(),
                    machine.getCurrentMaterialCode(), windowStartTime);
            log.info("在机物料不需要排程，机台收尾时间重置为窗口首班开始, 机台: {}, 在机物料: {}, "
                            + "原收尾时间: {}, 窗口首班: {}",
                    entry.getKey(), machine.getCurrentMaterialCode(),
                    LhScheduleTimeUtil.formatDateTime(originalEndTime),
                    LhScheduleTimeUtil.formatDateTime(windowStartTime));
        }
    }

    /**
     * 将不再续作的在机物料继承结果结束时间对齐到排程窗口起点。
     * <p>强制重排会继承目标日前一日结果到机台分配账本。当前在机SKU本次无需排程时，
     * 继承结果只用于保留前规格识别，不再代表本窗口内的真实占用，因此仅修正结束时间，
     * 不删除继承结果。</p>
     *
     * @param context 排程上下文
     * @param machineCode 机台编码
     * @param materialCode 当前在机物料编码
     * @param windowStartTime 排程窗口首班开始时间
     */
    private void alignInheritedMachineAssignmentEndTime(LhScheduleContext context,
                                                        String machineCode,
                                                        String materialCode,
                                                        Date windowStartTime) {
        if (Objects.isNull(context) || StringUtils.isEmpty(machineCode)
                || StringUtils.isEmpty(materialCode) || Objects.isNull(windowStartTime)) {
            return;
        }
        List<LhScheduleResult> assignedResultList = context.getMachineAssignmentMap().get(machineCode);
        if (CollectionUtils.isEmpty(assignedResultList)) {
            return;
        }
        for (LhScheduleResult assignedResult : assignedResultList) {
            if (Objects.nonNull(assignedResult)
                    && StringUtils.equals(materialCode, assignedResult.getMaterialCode())) {
                assignedResult.setSpecEndTime(windowStartTime);
            }
        }
    }

    /**
     * 按物料编码归集待排SKU，保持原有归集顺序供机台依次消费。
     * <p>匹配时先在同物料列表中查找产品状态一致的SKU，未命中再按列表顺序降级承接同物料SKU。</p>
     *
     * @param context 排程上下文
     * @return 物料编码 -> 待匹配SKU列表
     */
    private Map<String, List<SkuScheduleDTO>> buildSkuByMaterialMap(LhScheduleContext context) {
        Map<String, List<SkuScheduleDTO>> skuByMaterialMap = new LinkedHashMap<>();
        for (List<SkuScheduleDTO> skuList : context.getStructureSkuMap().values()) {
            for (SkuScheduleDTO sku : skuList) {
                sku.setScheduleType(null);
                sku.setContinuousMachineCode(null);
                String materialCode = StringUtils.trim(sku.getMaterialCode());
                if (StringUtils.isEmpty(materialCode)) {
                    continue;
                }
                skuByMaterialMap.computeIfAbsent(materialCode, key -> new ArrayList<>()).add(sku);
            }
        }
        return skuByMaterialMap;
    }

    /**
     * 按机台最近在机记录匹配续作SKU。
     * <p>先按物料编码与产品状态精确匹配；精确匹配失败后，再降级按物料编码匹配。</p>
     *
     * @param machineCode 机台编码
     * @param materialCode 在机物料编码
     * @param productStatus 在机产品状态
     * @param skuByMaterialMap 物料编码 -> 待匹配SKU列表
     * @param continuousTemplateMap 已精确匹配的物料状态续作模板
     * @param continuousSkuList 续作SKU列表
     * @param allowMaterialFallback 是否允许按物料编码降级
     */
    private void assignContinuousSku(String machineCode,
                                     String materialCode,
                                     String productStatus,
                                     Map<String, List<SkuScheduleDTO>> skuByMaterialMap,
                                     Map<String, SkuScheduleDTO> continuousTemplateMap,
                                     List<SkuScheduleDTO> continuousSkuList,
                                     boolean allowMaterialFallback) {
        String normalizedMaterialCode = StringUtils.trim(materialCode);
        if (StringUtils.isEmpty(machineCode) || StringUtils.isEmpty(normalizedMaterialCode)) {
            return;
        }
        // MES快照循环已完成分配时，滚动衔接循环不得再次为同一机台降级匹配其他状态SKU。
        for (SkuScheduleDTO continuousSku : continuousSkuList) {
            if (Objects.nonNull(continuousSku)
                    && StringUtils.equals(machineCode, continuousSku.getContinuousMachineCode())) {
                return;
            }
        }
        String normalizedProductStatus = normalizeOnlineProductStatus(productStatus);
        String exactSkuKey = MonthPlanDateResolver.buildMaterialStatusKey(
                normalizedMaterialCode, normalizedProductStatus);
        SkuScheduleDTO templateSku = continuousTemplateMap.get(exactSkuKey);
        if (Objects.nonNull(templateSku)) {
            SkuScheduleDTO matchedSku = copySkuForContinuousMachine(templateSku, machineCode);
            matchedSku.setScheduleType(ScheduleTypeEnum.CONTINUOUS.getCode());
            continuousSkuList.add(matchedSku);
            return;
        }
        List<SkuScheduleDTO> matchedSkuList = skuByMaterialMap.get(normalizedMaterialCode);
        int matchedSkuIndex = resolveProductStatusMatchedSkuIndex(matchedSkuList, normalizedProductStatus);
        if (matchedSkuIndex < 0 && !allowMaterialFallback) {
            return;
        }
        if (matchedSkuIndex < 0) {
            if (CollectionUtils.isEmpty(matchedSkuList)) {
                return;
            }
            // 精确候选已全部预留后，非空未知状态继续沿用既有物料编码降级行为。
            matchedSkuIndex = 0;
            log.warn("续作SKU产品状态未精确匹配，降级按物料编码匹配, machineCode: {}, materialCode: {}, "
                            + "onlineProductStatus: {}, fallbackProductStatus: {}",
                    machineCode, normalizedMaterialCode, normalizedProductStatus,
                    matchedSkuList.get(matchedSkuIndex).getProductStatus());
        }
        // 首台机台消费真实SKU并登记模板；后续同状态机台通过模板副本共享同一日计划额度账本。
        SkuScheduleDTO matchedSku = matchedSkuList.remove(matchedSkuIndex);
        matchedSku.setScheduleType(ScheduleTypeEnum.CONTINUOUS.getCode());
        matchedSku.setContinuousMachineCode(machineCode);
        continuousSkuList.add(matchedSku);
        String matchedSkuKey = MonthPlanDateResolver.buildMaterialStatusKey(
                matchedSku.getMaterialCode(), matchedSku.getProductStatus());
        continuousTemplateMap.putIfAbsent(matchedSkuKey, matchedSku);
    }

    /**
     * 归一化MES或滚动续作产品状态。
     * <p>业务明确规定在机产品状态为空时属于正规SKU，因此空值统一转换为S。</p>
     *
     * @param productStatus 原始产品状态
     * @return 归一化后的产品状态
     */
    private String normalizeOnlineProductStatus(String productStatus) {
        String normalizedProductStatus = StringUtils.trim(productStatus);
        return StringUtils.isEmpty(normalizedProductStatus)
                ? TrialStatusEnum.FORMAL.getCode() : normalizedProductStatus;
    }

    /**
     * 查找同物料SKU列表中产品状态一致的候选位置。
     *
     * @param matchedSkuList 同物料待匹配SKU列表
     * @param productStatus MES在机或滚动继承产品状态
     * @return 状态一致的SKU位置；未匹配返回-1
     */
    private int resolveProductStatusMatchedSkuIndex(List<SkuScheduleDTO> matchedSkuList, String productStatus) {
        String normalizedProductStatus = StringUtils.trim(productStatus);
        if (CollectionUtils.isEmpty(matchedSkuList) || StringUtils.isEmpty(normalizedProductStatus)) {
            return -1;
        }
        for (int index = 0; index < matchedSkuList.size(); index++) {
            SkuScheduleDTO sku = matchedSkuList.get(index);
            if (Objects.nonNull(sku) && StringUtils.equals(
                    normalizedProductStatus, StringUtils.trim(sku.getProductStatus()))) {
                return index;
            }
        }
        return -1;
    }

    /**
     * 为同物料多机台续作场景创建SKU副本。
     * <p>副本复制源SKU的核心计划量、产能、状态字段，并<b>共享</b> {@code dailyPlanQuotaMap}，
     * 确保多台机台排产时共用同一个日计划额度账本。</p>
     *
     * @param source 源SKU（模板）
     * @param machineCode 目标机台编码
     * @return 副本SKU，sharedDailyPlanQuotaMap 指向源SKU的同一实例
     */
    private SkuScheduleDTO copySkuForContinuousMachine(SkuScheduleDTO source, String machineCode) {
        SkuScheduleDTO copy = new SkuScheduleDTO();
        // 基本信息
        copy.setMaterialCode(source.getMaterialCode());
        copy.setMaterialDesc(source.getMaterialDesc());
        copy.setStructureName(source.getStructureName());
        copy.setEmbryoCode(source.getEmbryoCode());
        copy.setMainMaterialDesc(source.getMainMaterialDesc());
        copy.setSpecCode(source.getSpecCode());
        copy.setSpecDesc(source.getSpecDesc());
        copy.setProSize(source.getProSize());
        copy.setPattern(source.getPattern());
        copy.setMainPattern(source.getMainPattern());
        copy.setBrand(source.getBrand());
        // 计划量信息
        copy.setMonthPlanQty(source.getMonthPlanQty());
        copy.setFinishedQty(source.getFinishedQty());
        copy.setSurplusQty(source.getSurplusQty());
        copy.setWindowPlanQty(source.getWindowPlanQty());
        copy.setDailyPlanQty(source.getDailyPlanQty());
        copy.setPendingQty(source.getPendingQty());
        copy.setTargetScheduleQty(source.getTargetScheduleQty());
        // 产能信息
        copy.setLhTimeSeconds(source.getLhTimeSeconds());
        copy.setShiftCapacity(source.getShiftCapacity());
        copy.setDailyCapacity(source.getDailyCapacity());
        copy.setMouldQty(source.getMouldQty());
        copy.setMouldChangeInfo(source.getMouldChangeInfo());
        // 状态标记
        copy.setSkuTag(source.getSkuTag());
        copy.setTrial(source.isTrial());
        copy.setConstructionStage(source.getConstructionStage());
        copy.setTrialDemandQty(source.getTrialDemandQty());
        copy.setSmallBatchValidation(source.isSmallBatchValidation());
        copy.setBeginDay(source.getBeginDay());
        // 优先级信息
        copy.setPriorityCode(source.getPriorityCode());
        copy.setScheduleOrder(source.getScheduleOrder());
        copy.setDeliveryLocked(source.isDeliveryLocked());
        copy.setDelayDays(source.getDelayDays());
        copy.setSupplyChainPriority(source.getSupplyChainPriority());
        copy.setProductionType(source.getProductionType());
        copy.setStructureType(source.getStructureType());
        copy.setHighPriorityPendingQty(source.getHighPriorityPendingQty());
        copy.setCycleProductionPendingQty(source.getCycleProductionPendingQty());
        copy.setMidPriorityPendingQty(source.getMidPriorityPendingQty());
        copy.setConventionProductionPendingQty(source.getConventionProductionPendingQty());
        // 胎胚信息
        copy.setEmbryoStock(source.getEmbryoStock());
        copy.setEmbryoSupplyHours(source.getEmbryoSupplyHours());
        // 目标量控制字段
        copy.setStrictTargetQty(source.isStrictTargetQty());
        // 多机台排产相关 —— 共享日计划额度账本
        copy.setRemainingScheduleQty(source.getRemainingScheduleQty());
        copy.setDailyPlanQuotaMap(source.getDailyPlanQuotaMap());
        copy.setWindowRemainingPlanQty(source.getWindowRemainingPlanQty());
        copy.setShiftFillOverQty(source.getShiftFillOverQty());
        copy.setMonthlyHistoryShortageQty(source.getMonthlyHistoryShortageQty());
        copy.setEffectiveLastMonthOverdueQty(source.getEffectiveLastMonthOverdueQty());
        copy.setEffectiveCarryForwardQty(source.getEffectiveCarryForwardQty());
        copy.setScheduleDayFinishQty(source.getScheduleDayFinishQty());
        copy.setFutureMonthPlanQtyAfterWindow(source.getFutureMonthPlanQtyAfterWindow());
        copy.setNextDayPlanQtyAfterWindow(source.getNextDayPlanQtyAfterWindow());
        // 收尾信息
        copy.setEndingDaysRemaining(source.getEndingDaysRemaining());
        // 新增排产目标量控制
        copy.setStrictNewSpecShortageOnly(source.isStrictNewSpecShortageOnly());
        // 版本信息
        copy.setMonthPlanVersion(source.getMonthPlanVersion());
        copy.setProductionVersion(source.getProductionVersion());
        copy.setEmbryoNo(source.getEmbryoNo());
        copy.setTextNo(source.getTextNo());
        copy.setLhNo(source.getLhNo());
        copy.setProductStatus(source.getProductStatus());
        // 月计划所属年月（减量清单匹配用），随续作副本同步复制
        copy.setMonthPlanYear(source.getMonthPlanYear());
        copy.setMonthPlanMonth(source.getMonthPlanMonth());
        // 机台信息 —— 指定目标机台
        copy.setContinuousMachineCode(machineCode);
        log.debug("同物料多机台续作副本已创建, materialCode: {}, targetMachine: {}",
                source.getMaterialCode(), machineCode);
        return copy;
    }

    /**
     * 解析滚动衔接后机台应继续承接的最新未收尾结果。
     *
     * @param context 排程上下文
     * @param machineCode 机台编码
     * @param machine 机台状态
     * @return 最新未收尾继承结果；不存在时返回null
     */
    private LhScheduleResult resolveRollingContinuousResult(LhScheduleContext context,
                                                            String machineCode,
                                                            MachineScheduleDTO machine) {
        if (context == null
                || !context.isRollingScheduleHandoff()
                || machine == null
                || StringUtils.isEmpty(machineCode)
                || StringUtils.isEmpty(machine.getCurrentMaterialCode())
                || CollectionUtils.isEmpty(context.getRollingInheritedScheduleResultList())) {
            return null;
        }
        LhScheduleResult latestInheritedResult = null;
        for (LhScheduleResult inheritedResult : context.getRollingInheritedScheduleResultList()) {
            if (inheritedResult == null
                    || !StringUtils.equals(machineCode, inheritedResult.getLhMachineCode())
                    || !StringUtils.equals(machine.getCurrentMaterialCode(), inheritedResult.getMaterialCode())) {
                continue;
            }
            if (latestInheritedResult == null) {
                latestInheritedResult = inheritedResult;
                continue;
            }
            Date latestSpecEndTime = latestInheritedResult.getSpecEndTime();
            Date currentSpecEndTime = inheritedResult.getSpecEndTime();
            if (latestSpecEndTime == null
                    || (currentSpecEndTime != null && currentSpecEndTime.after(latestSpecEndTime))) {
                latestInheritedResult = inheritedResult;
            }
        }
        if (latestInheritedResult == null) {
            return null;
        }
        /*
         * 普通收尾结果仍按原逻辑结束续作；专用同物料状态链即使X/T在窗口末班完成，
         * 下一窗口仍需据此恢复正规SKU到原机台，不能因为isEnd=1丢失承接关系。
         */
        if (StringUtils.equals("1", latestInheritedResult.getIsEnd())
                && !containsSameMaterialStatusContinuationAnalysis(latestInheritedResult)) {
            return null;
        }
        return latestInheritedResult;
    }

    /**
     * 安全获取Integer值，null时返回0
     *
     * @param value Integer值
     * @return int值
     */
    private int safeInt(Integer value) {
        return Objects.nonNull(value) ? value : 0;
    }

    /**
     * 获取目标排产量解析器。
     *
     * @return 目标排产量解析器
     */
    private TargetScheduleQtyResolver getTargetScheduleQtyResolver() {
        return Objects.nonNull(targetScheduleQtyResolver)
                ? targetScheduleQtyResolver
                : new TargetScheduleQtyResolver();
    }

    /**
     * 余量计算结果。
     */
    private static class SurplusCalculation {

        private final int surplusQty;
        private final int actualFinishedQty;
        private final int ignoredOverProductionQty;
        private final int lastMonthOverdueQty;
        private final int monthPlanTotal;

        private SurplusCalculation(int surplusQty, int actualFinishedQty, int ignoredOverProductionQty,
                                   int lastMonthOverdueQty, int monthPlanTotal) {
            this.surplusQty = surplusQty;
            this.actualFinishedQty = Math.max(0, actualFinishedQty);
            this.ignoredOverProductionQty = Math.max(0, ignoredOverProductionQty);
            this.lastMonthOverdueQty = lastMonthOverdueQty;
            this.monthPlanTotal = Math.max(0, monthPlanTotal);
        }

        public int getSurplusQty() {
            return surplusQty;
        }

        public int getActualFinishedQty() {
            return actualFinishedQty;
        }

        public int getIgnoredOverProductionQty() {
            return ignoredOverProductionQty;
        }

        /**
         * 上月超欠产数量。
         * 正=欠产（增加余量），负=超产（扣减余量），0=无有效超欠产或标志无效。
         */
        public int getLastMonthOverdueQty() {
            return lastMonthOverdueQty;
        }

        /**
         * 硫化月计划总量。
         *
         * @return 月计划总量
         */
        public int getMonthPlanTotal() {
            return monthPlanTotal;
        }
    }

    /**
     * 本月历史欠产摘要。
     */
    private static class MonthlyShortageSummary {

        private final int shortageQty;
        private final int ignoredOverProductionQty;

        private MonthlyShortageSummary(int shortageQty, int ignoredOverProductionQty) {
            this.shortageQty = Math.max(0, shortageQty);
            this.ignoredOverProductionQty = Math.max(0, ignoredOverProductionQty);
        }

        private static MonthlyShortageSummary empty() {
            return new MonthlyShortageSummary(0, 0);
        }

        public int getShortageQty() {
            return shortageQty;
        }

        public int getIgnoredOverProductionQty() {
            return ignoredOverProductionQty;
        }
    }

    @Override
    protected String getStepName() {
        return ScheduleStepEnum.S4_3_ADJUST_AND_GATHER.getDescription();
    }
}
