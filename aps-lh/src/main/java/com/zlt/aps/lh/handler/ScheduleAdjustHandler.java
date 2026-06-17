package com.zlt.aps.lh.handler;

import com.zlt.aps.lh.api.constant.LhScheduleConstant;
import com.zlt.aps.lh.api.constant.LhScheduleParamConstant;
import com.zlt.aps.lh.component.TargetScheduleQtyResolver;
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
import com.zlt.aps.lh.engine.strategy.IEndingJudgmentStrategy;
import com.zlt.aps.lh.util.MonthPlanDayQtyUtil;
import com.zlt.aps.lh.util.ShiftFieldUtil;
import com.zlt.aps.lh.util.LhScheduleTimeUtil;
import com.zlt.aps.lh.util.SkuDailyPlanQuotaUtil;
import com.zlt.aps.lh.api.domain.entity.LhMachineOnlineInfo;
import com.zlt.aps.mdm.api.domain.entity.MdmSkuLhCapacity;
import com.zlt.aps.mp.api.domain.entity.FactoryMonthPlanProductionFinalResult;
import com.zlt.aps.mp.api.domain.entity.MpAdjustResult;
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
        targetScheduleQtyResolver.refreshAllSharedEmbryoStockAllocations(context, "S4.3收尾标注完成");

        // S4.3.3.1 共用胎胚零余量SKU先出队，后续排产只使用动态归一化后的胎胚组
        pruneSharedEmbryoZeroSurplusSkus(context);

        // S4.3.4 区分续作SKU和新增SKU
        classifyContinuousAndNewSkus(context);

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
                carryForwardQtyMap.put(plan.getMaterialCode(), shortageSummary.getShortageQty());
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
            if (!activeSkuList.contains(sku.getMaterialCode())) {
                activeSkuList.add(sku.getMaterialCode());
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
                targetScheduleQtyResolver.removeActiveEmbryoSku(
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
        return Objects.nonNull(sku)
                && sku.getSurplusQty() <= 0
                && StringUtils.isNotEmpty(sku.getEmbryoCode())
                && StringUtils.isNotEmpty(sku.getMaterialCode())
                && targetScheduleQtyResolver.isSharedEmbryoInWindow(context, sku);
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
                targetScheduleQtyResolver.isSharedEmbryoInWindow(context, sku),
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
                remainingSkuMap.put(sku.getMaterialCode(), sku);
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
        Set<String> materialSet = new HashSet<>(8);
        for (List<SkuScheduleDTO> skuList : context.getStructureSkuMap().values()) {
            if (CollectionUtils.isEmpty(skuList)) {
                continue;
            }
            for (SkuScheduleDTO candidateSku : skuList) {
                if (Objects.nonNull(candidateSku)
                        && StringUtils.equals(sku.getEmbryoCode(), candidateSku.getEmbryoCode())
                        && StringUtils.isNotEmpty(candidateSku.getMaterialCode())) {
                    materialSet.add(candidateSku.getMaterialCode());
                }
            }
        }
        return materialSet.size();
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
        int totalPlanQty = plan.getTotalQty() != null ? plan.getTotalQty() : 0;
        int actualFinishedQty = calculateFinishedQty(context, plan);
        int scheDayFinishQty = resolveScheDayFinishQty(context, plan.getMaterialCode());
        int lastMonthOverdueQty = resolveEffectiveLastMonthOverdueQty(plan);
        int remainingDemandQty = Math.max(0, totalPlanQty - actualFinishedQty + lastMonthOverdueQty);
        // 保留逐日超产统计用于诊断日志，不参与余量计算
        int ignoredOverProductionQty = calculateIgnoredOverProductionQty(context, plan);
        if (lastMonthOverdueQty != 0 || scheDayFinishQty > 0) {
            log.info("硫化余量计算完成, materialCode: {}, monthPlanQty: {}, monthFinishedAndScheDayQty: {}, "
                            + "scheDayFinishQty: {}, lastMonthValidFlag: {}, lastMonthOverdueQty: {}, surplusQty: {}",
                    plan.getMaterialCode(), totalPlanQty, actualFinishedQty, scheDayFinishQty,
                    plan.getLastMonthValidFlag(), lastMonthOverdueQty, remainingDemandQty);
        }
        return new SurplusCalculation(remainingDemandQty, actualFinishedQty, ignoredOverProductionQty,
                lastMonthOverdueQty);
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
        if (StringUtils.isNotEmpty(materialCode)) {
            Integer monthFinishedQty = context.getMaterialMonthFinishedQtyMap().get(materialCode);
            if (Objects.nonNull(monthFinishedQty)) {
                return Math.max(monthFinishedQty, 0) + resolveScheDayFinishQty(context, materialCode);
            }
            if (canFallbackToPreviousFinishedQty(context)) {
                Integer dayFinishedQty = context.getMaterialDayFinishedQtyMap().get(
                        buildMaterialDayKey(materialCode, resolvePreviousScheduleDate(context)));
                if (Objects.nonNull(dayFinishedQty)) {
                    return Math.max(dayFinishedQty, 0);
                }

                int finishedQty = 0;
                for (LhScheduleResult result : context.getPreviousScheduleResultList()) {
                    if (materialCode.equals(result.getMaterialCode())) {
                        finishedQty += resolveShiftFinishedQty(result, context);
                    }
                }
                return finishedQty;
            }
        }
        return 0;
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
        int currentDayPlanQty = MonthPlanDayQtyUtil.resolveDayQty(plan, scheduleDate.getDayOfMonth());
        int scheDayFinishQty = resolveScheDayFinishQty(context, plan.getMaterialCode());
        int currentDayIgnoredOverQty = Math.max(0, scheDayFinishQty - currentDayPlanQty);
        if (currentDayIgnoredOverQty > 0) {
            ignoredOverProductionQty += currentDayIgnoredOverQty;
            log.info("T日晚班超产忽略抵扣, materialCode: {}, scheduleDate: {}, dayPlanQty: {}, scheDayFinishQty: {}, ignoredOverQty: {}",
                    plan.getMaterialCode(), scheduleDate, currentDayPlanQty, scheDayFinishQty, currentDayIgnoredOverQty);
        }
        return ignoredOverProductionQty;
    }

    /**
     * 获取指定物料的T日排程班次完成量（class1FinishQty汇总值）。
     *
     * @param context       排程上下文
     * @param materialCode  物料编码
     * @return T日班次完成量，无记录时返回0
     */
    private int resolveScheDayFinishQty(LhScheduleContext context, String materialCode) {
        if (StringUtils.isEmpty(materialCode)) {
            return 0;
        }
        Integer scheDayFinishQty = context.getMaterialScheDayFinishQtyMap().get(materialCode);
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
        SkuScheduleDTO dto = new SkuScheduleDTO();
        dto.setMaterialCode(plan.getMaterialCode());
        dto.setMaterialDesc(plan.getMaterialDesc());
        dto.setStructureName(plan.getStructureName());
        dto.setEmbryoCode(plan.getEmbryoCode());
        dto.setMainMaterialDesc(plan.getMainMaterialDesc());
        dto.setSpecCode(plan.getSpecifications());
        dto.setProSize(plan.getProSize());
        dto.setPattern(plan.getPattern());
        dto.setMainPattern(plan.getMainPattern());
        dto.setBrand(plan.getBrand());

        // 计划量信息
        dto.setMonthPlanQty(plan.getTotalQty() != null ? plan.getTotalQty() : 0);
        dto.setFinishedQty(surplus.getActualFinishedQty());
        int rawCarryForwardQty = context.getCarryForwardQtyMap().getOrDefault(plan.getMaterialCode(), 0);
        int carryForwardQty = resolveEffectiveCarryForwardQty(context, plan.getMaterialCode(), rawCarryForwardQty);
        int scheDayFinishQty = resolveScheDayFinishQty(context, plan.getMaterialCode());
        int windowPlanQty = MonthPlanDayQtyUtil.resolveWindowPlanQty(
                plan, context.getScheduleDate(), context.getWindowEndDate());
        // 继承量已由滚动衔接占用，需从窗口待排量中扣减，防止重复排产
        int inheritedPlanQty = Math.max(0, context.getInheritedPlanQtyMap().getOrDefault(plan.getMaterialCode(), 0));
        dto.setWindowPlanQty(windowPlanQty);
        dto.setMonthlyHistoryShortageQty(Math.max(0, rawCarryForwardQty));
        dto.setEffectiveCarryForwardQty(Math.max(0, carryForwardQty));
        dto.setScheduleDayFinishQty(Math.max(0, scheDayFinishQty));
        dto.setFutureMonthPlanQtyAfterWindow(resolveFutureMonthPlanQtyAfterWindow(context, plan));
        dto.setNextDayPlanQtyAfterWindow(resolveNextDayPlanQtyAfterWindow(context, plan));

        // 初始化日计划额度账本：按排程窗口日期读取月计划 dayN，扣减继承量。
        // day1/day2/day3 的业务日期由窗口 T日～目标日决定，不能按字段名固定绑定自然日。
        Map<LocalDate, SkuDailyPlanQuotaDTO> dailyPlanQuotaMap = buildDailyPlanQuotaMap(
                context, plan, dto.getMaterialCode());
        deductInheritedFromDailyQuota(dailyPlanQuotaMap, inheritedPlanQty);
        deductScheDayFinishFromDailyQuota(context, dailyPlanQuotaMap, dto.getMaterialCode());

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
        dto.setDailyPlanQty(plan.getDayVulcanizationQty() != null ? plan.getDayVulcanizationQty() : 0);

        // 产能信息（从SKU日硫化产能Map获取）
        MdmSkuLhCapacity capacity = context.getSkuLhCapacityMap().get(plan.getMaterialCode());
        if (capacity != null) {
            // 硫化时间（秒），curingTime来自月计划，若无则用3600秒（1小时）作为默认
            int lhTimeSeconds = plan.getCuringTime() != null ? plan.getCuringTime() : 3600;
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
        dto.setSupplyChainPriority(plan.getProductionType());
        dto.setDeliveryLocked(isDeliveryLocked(context, plan.getMaterialCode()));
        dto.setDelayDays(resolveDelayDays(context, plan));
        dto.setHighPriorityPendingQty(safeInt(plan.getHeightProductionQty()));
        dto.setCycleProductionPendingQty(safeInt(plan.getCycleProductionQty()));
        dto.setMidPriorityPendingQty(safeInt(plan.getMidProductionQty()));
        dto.setConventionProductionPendingQty(safeInt(plan.getConventionProductionQty()));

        // 施工阶段：试制/量试/正规来自月计划，后续影响排序、单控机台选择和严格目标量。
        dto.setConstructionStage(plan.getConstructionStage());
        dto.setTrialDemandQty(safeInt(plan.getTrialQty()));
        dto.setBeginDay(plan.getBeginDay());
        // 月计划模具变化信息只在 S4.5 窗口无日计划历史欠产补排时用于判断计划使用模数。
        dto.setMouldChangeInfo(plan.getMouldChangeInfo());
        dto.setTrial(isTrialStage(plan.getConstructionStage()));
        // 正规SKU余量小于阈值时标记为小批量，供选机阶段在单控/普通机台之间应用类型约束
        int smallBatchThreshold = resolveSmallBatchSkuThreshold(context);
        boolean isSmallBatch = !dto.isTrial()
                && dto.getSurplusQty() < smallBatchThreshold;
        dto.setSmallBatchValidation(isSmallBatch);
        if (isSmallBatch) {
            log.info("小批量SKU判定命中, 物料编码: {}, 施工阶段: {}, 余量: {}, 阈值: {}",
                    dto.getMaterialCode(), dto.getConstructionStage(),
                    dto.getSurplusQty(), smallBatchThreshold);
        }

        // 示方书信息
        dto.setEmbryoNo(plan.getEmbryoNo());
        dto.setTextNo(plan.getTextNo());
        dto.setLhNo(plan.getLhNo());

        // 版本信息
        dto.setMonthPlanVersion(plan.getMonthPlanVersion());
        dto.setProductionVersion(plan.getProductionVersion());

        // 产品状态（来自月计划）
        dto.setProductStatus(plan.getProductStatus());

        // 试制SKU严格限制目标量，不允许超出dayN补满班次；量试/正规仍可按后续策略补满可用班次。
        if (StringUtils.equals(ConstructionStageEnum.TRIAL.getCode(), dto.getConstructionStage())) {
            dto.setStrictTargetQty(true);
        }

        // 默认标记为常规
        dto.setSkuTag(SkuTagEnum.NORMAL.getCode());

        return dto;
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
     * 解析小批量验证SKU阈值。
     *
     * @param context 排程上下文
     * @return 阈值
     */
    private int resolveSmallBatchSkuThreshold(LhScheduleContext context) {
        if (Objects.nonNull(context.getScheduleConfig())) {
            return context.getScheduleConfig().getSmallBatchSkuThreshold();
        }
        return context.getParamIntValue(LhScheduleParamConstant.SMALL_BATCH_SKU_THRESHOLD,
                LhScheduleConstant.SMALL_BATCH_SKU_THRESHOLD);
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
     * <p>依赖 {@link MonthPlanDayQtyUtil#resolveDayQty} 按日序读取 DAY_n 字段。</p>
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
        if (MonthPlanDayQtyUtil.isCrossMonthWindow(context.getScheduleDate(), context.getWindowEndDate())) {
            log.warn("排程窗口跨月，无法构建日计划额度账本, materialCode: {}, scheduleDate: {}, windowEndDate: {}",
                    materialCode,
                    LhScheduleTimeUtil.formatDate(context.getScheduleDate()),
                    LhScheduleTimeUtil.formatDate(context.getWindowEndDate()));
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
            int dayOfMonth = cursor.get(Calendar.DAY_OF_MONTH);
            int dayPlanQty = MonthPlanDayQtyUtil.resolveDayQty(plan, dayOfMonth);
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
     */
    private void deductScheDayFinishFromDailyQuota(LhScheduleContext context,
                                                   Map<LocalDate, SkuDailyPlanQuotaDTO> dailyPlanQuotaMap,
                                                   String materialCode) {
        int scheDayFinishQty = resolveScheDayFinishQty(context, materialCode);
        if (scheDayFinishQty <= 0) {
            return;
        }
        int deductedQty = deductQuotaByDateOrder(dailyPlanQuotaMap, scheDayFinishQty);
        if (deductedQty > 0) {
            log.info("T日排程晚班完成量扣减日计划账本, materialCode: {}, finishQty: {}, deductedQty: {}, windowRemainingQty: {}",
                    materialCode, scheDayFinishQty, deductedQty, SkuDailyPlanQuotaUtil.sumRemainingQty(dailyPlanQuotaMap));
        }
        if (deductedQty < scheDayFinishQty) {
            log.debug("T日排程晚班完成量超出窗口日计划额度, materialCode: {}, finishQty: {}, deductedQty: {}, overflowQty: {}",
                    materialCode, scheDayFinishQty, deductedQty, scheDayFinishQty - deductedQty);
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
        return MonthPlanDayQtyUtil.resolveDayQty(plan, calendar.get(Calendar.DAY_OF_MONTH));
    }

    /**
     * 汇总排程窗口结束后到月底的后续月计划日量。
     * <p>仅用于 S4.5 新增排产区分“本月整体收尾”和“当前窗口仅补欠产”，不参与 S4.4 续作。</p>
     *
     * @param context 排程上下文
     * @param plan 月计划记录
     * @return T+3 到月底后续日计划汇总
     */
    private int resolveFutureMonthPlanQtyAfterWindow(LhScheduleContext context,
                                                     FactoryMonthPlanProductionFinalResult plan) {
        if (Objects.isNull(context) || Objects.isNull(plan)
                || Objects.isNull(context.getScheduleDate()) || Objects.isNull(context.getWindowEndDate())) {
            return 0;
        }
        LocalDate scheduleDate = toLocalDate(context.getScheduleDate());
        LocalDate targetDate = toLocalDate(context.getWindowEndDate());
        LocalDate monthEndDate = scheduleDate.withDayOfMonth(scheduleDate.lengthOfMonth());
        LocalDate effectiveWindowEndDate = targetDate.isAfter(monthEndDate) ? monthEndDate : targetDate;
        if (!effectiveWindowEndDate.isBefore(monthEndDate)) {
            return 0;
        }
        int futurePlanQty = 0;
        for (int dayOfMonth = effectiveWindowEndDate.getDayOfMonth() + 1;
             dayOfMonth <= monthEndDate.getDayOfMonth(); dayOfMonth++) {
            futurePlanQty += MonthPlanDayQtyUtil.resolveDayQty(plan, dayOfMonth);
        }
        return Math.max(0, futurePlanQty);
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
        LocalDate scheduleDate = toLocalDate(context.getScheduleDate());
        LocalDate targetDate = toLocalDate(context.getWindowEndDate());
        LocalDate monthEndDate = scheduleDate.withDayOfMonth(scheduleDate.lengthOfMonth());
        if (!targetDate.isBefore(monthEndDate)) {
            return 0;
        }
        LocalDate nextDate = targetDate.plusDays(1);
        if (!scheduleDate.getMonth().equals(nextDate.getMonth())) {
            return 0;
        }
        return Math.max(0, MonthPlanDayQtyUtil.resolveDayQty(plan, nextDate.getDayOfMonth()));
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
     * 获取指定日期的物料日完成量（按"物料+日期"聚合）。
     *
     * @param context 排程上下文
     * @param materialCode 物料编码
     * @param finishDate 完成日期
     * @return 日完成量
     */
    private int resolveMaterialDayFinishedQty(LhScheduleContext context, String materialCode, Date finishDate) {
        if (StringUtils.isEmpty(materialCode) || Objects.isNull(finishDate)) {
            return 0;
        }
        String key = buildMaterialDayKey(materialCode, finishDate);
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
            int dayPlanQty = MonthPlanDayQtyUtil.resolveDayQty(plan, cursor.getDayOfMonth());
            int finishedQty = resolveMonthDailyFinishedQty(context, plan.getMaterialCode(), cursor);
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
        log.info("本月历史欠产统计, materialCode: {}, scheduleMonth: {}, historyRange: {}~{}, shortageQty: {}, ignoredOverProductionQty: {}, detail: {}",
                plan.getMaterialCode(), monthStartDate.getMonthValue(), monthStartDate, historyEndDate,
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
     * 获取当前排程月份内某个物料在指定自然日的完成量。
     *
     * @param context 排程上下文
     * @param materialCode 物料编码
     * @param productionDate 自然日
     * @return 日完成量
     */
    private int resolveMonthDailyFinishedQty(LhScheduleContext context,
                                             String materialCode,
                                             LocalDate productionDate) {
        if (Objects.isNull(context) || StringUtils.isEmpty(materialCode) || Objects.isNull(productionDate)) {
            return 0;
        }
        Integer finishedQty = context.getMaterialMonthDailyFinishedQtyMap()
                .get(materialCode + "_" + productionDate);
        return Objects.nonNull(finishedQty) ? Math.max(finishedQty, 0) : 0;
    }

    /**
     * 构建"物料+日期"聚合Key。
     *
     * @param materialCode 物料编码
     * @param date 日期
     * @return 聚合Key
     */
    private String buildMaterialDayKey(String materialCode, Date date) {
        return materialCode + "_" + LhScheduleTimeUtil.formatDate(LhScheduleTimeUtil.clearTime(date));
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
     * 判断SKU是否有交期锁定（周程滚动调整有锁定上机日期）
     *
     * @param context 排程上下文
     * @param materialCode 物料编码
     * @return true-有锁定交期
     */
    private boolean isDeliveryLocked(LhScheduleContext context, String materialCode) {
        if (StringUtils.isEmpty(materialCode)) {
            return false;
        }
        List<MpAdjustResult> adjustResults = context.getMpAdjustResultMap().get(materialCode);
        if (CollectionUtils.isEmpty(adjustResults)) {
            return false;
        }
        for (MpAdjustResult adjustResult : adjustResults) {
            if (StringUtils.equals("1", StringUtils.trimToEmpty(adjustResult.getIsLockSchedule()))) {
                return true;
            }
        }
        return false;
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
                if (endingJudgmentStrategy.isEnding(context, sku)) {
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
        Map<String, List<SkuScheduleDTO>> skuByMaterialMap = buildSkuByMaterialMap(context);
        Map<String, Integer> materialSkuCountMap = buildMaterialSkuCountMap(skuByMaterialMap);

        // 保持MES最近快照顺序消费，同时优先承接滚动衔接后的机台当前物料。
        Map<String, MachineScheduleDTO> schedulableMachineMap = context.getMachineScheduleMap();
        for (Map.Entry<String, LhMachineOnlineInfo> entry : context.getMachineOnlineInfoMap().entrySet()) {
            if (CollectionUtils.isEmpty(schedulableMachineMap)
                    || !schedulableMachineMap.containsKey(entry.getKey())) {
                continue;
            }
            String materialCode = resolveContinuousMaterialCode(
                    context, entry.getKey(), schedulableMachineMap.get(entry.getKey()), entry.getValue());
            assignContinuousSku(entry.getKey(), materialCode, skuByMaterialMap,
                    materialSkuCountMap, continuousSkuList);
        }

        if (context.isRollingScheduleHandoff() && !CollectionUtils.isEmpty(schedulableMachineMap)) {
            for (Map.Entry<String, MachineScheduleDTO> entry : schedulableMachineMap.entrySet()) {
                String materialCode = resolveRollingContinuousMaterialCode(context, entry.getKey(), entry.getValue());
                assignContinuousSku(entry.getKey(), materialCode, skuByMaterialMap,
                        materialSkuCountMap, continuousSkuList);
            }
        }

        for (List<SkuScheduleDTO> skuList : context.getStructureSkuMap().values()) {
            for (SkuScheduleDTO sku : skuList) {
                if (StringUtils.equals(ScheduleTypeEnum.CONTINUOUS.getCode(), sku.getScheduleType())) {
                    continue;
                }
                // 未命中MES在机记录的SKU按新增规格处理。
                sku.setScheduleType(ScheduleTypeEnum.NEW_SPEC.getCode());
                sku.setContinuousMachineCode(null);
                newSpecSkuList.add(sku);
            }
        }

        context.setContinuousSkuList(continuousSkuList);
        context.setNewSpecSkuList(newSpecSkuList);
        log.info("续作/新增SKU区分完成, 续作: {}个, 新增: {}个", continuousSkuList.size(), newSpecSkuList.size());
    }

    /**
     * 统计每个物料在月计划归集后的原始SKU条数。
     *
     * @param skuByMaterialMap 物料编码 -> 待匹配SKU列表
     * @return 物料原始SKU数量
     */
    private Map<String, Integer> buildMaterialSkuCountMap(Map<String, List<SkuScheduleDTO>> skuByMaterialMap) {
        Map<String, Integer> materialSkuCountMap = new LinkedHashMap<>(skuByMaterialMap.size());
        for (Map.Entry<String, List<SkuScheduleDTO>> entry : skuByMaterialMap.entrySet()) {
            materialSkuCountMap.put(entry.getKey(), entry.getValue().size());
        }
        return materialSkuCountMap;
    }

    /**
     * 按物料编码归集待排SKU，保持原有归集顺序供机台依次消费
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
                if (StringUtils.isEmpty(sku.getMaterialCode())) {
                    continue;
                }
                skuByMaterialMap.computeIfAbsent(sku.getMaterialCode(), k -> new ArrayList<>()).add(sku);
            }
        }
        return skuByMaterialMap;
    }

    /**
     * 按机台最近MES在机记录匹配续作SKU
     *
     * @param machineCode      机台编码
     * @param onlineInfo       机台最近MES在机记录
     * @param skuByMaterialMap 物料编码 -> 待匹配SKU列表
     * @param continuousSkuList 续作SKU列表
     */
    private void assignContinuousSku(String machineCode,
                                     String materialCode,
                                     Map<String, List<SkuScheduleDTO>> skuByMaterialMap,
                                     Map<String, Integer> materialSkuCountMap,
                                     List<SkuScheduleDTO> continuousSkuList) {
        if (StringUtils.isEmpty(machineCode) || StringUtils.isEmpty(materialCode)) {
            return;
        }
        List<SkuScheduleDTO> matchedSkuList = skuByMaterialMap.get(materialCode);
        if (CollectionUtils.isEmpty(matchedSkuList)) {
            return;
        }
        // 同一物料存在多条SKU时，按归集顺序逐条消费，且仅允许有效机台占用。
        // 仅有一条SKU但多台MES在机同物料时，不移除模板SKU，通过副本支持多机台续作。
        // 副本共享原SKU的dailyPlanQuotaMap，确保多机台同物料排产时共用同一日计划额度账本。
        SkuScheduleDTO matchedSku;
        if (matchedSkuList.size() > 1) {
            matchedSku = matchedSkuList.remove(0);
        } else {
            // 单SKU多机台：首台机台直接取原始SKU，后续机台创建副本
            SkuScheduleDTO originalSku = matchedSkuList.get(0);
            if (StringUtils.isEmpty(originalSku.getContinuousMachineCode())) {
                // 首台机台：直接取原始SKU，不移出列表
                matchedSku = originalSku;
            } else if (StringUtils.equals(machineCode, originalSku.getContinuousMachineCode())) {
                // 同一机台已在MES循环分配过（滚动衔接循环再次命中），跳过避免重复
                return;
            } else if (materialSkuCountMap.getOrDefault(materialCode, 0) > 1) {
                // 同物料存在多条月计划SKU时，仅允许逐条消费真实SKU，不再为额外机台复制模板SKU。
                return;
            } else {
                // 后续机台：创建副本，共享dailyPlanQuotaMap
                matchedSku = copySkuForContinuousMachine(originalSku, machineCode);
            }
        }
        matchedSku.setScheduleType(ScheduleTypeEnum.CONTINUOUS.getCode());
        matchedSku.setContinuousMachineCode(machineCode);
        continuousSkuList.add(matchedSku);
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
        // 机台信息 —— 指定目标机台
        copy.setContinuousMachineCode(machineCode);
        log.debug("同物料多机台续作副本已创建, materialCode: {}, targetMachine: {}",
                source.getMaterialCode(), machineCode);
        return copy;
    }

    /**
     * 解析机台本轮续作应承接的物料编码。
     * <p>滚动衔接已继承且未收尾时，以继承后的机台当前物料为准；否则沿用 MES 在机物料。</p>
     *
     * @param context 排程上下文
     * @param machineCode 机台编码
     * @param machine 机台状态
     * @param onlineInfo MES 在机快照
     * @return 续作物料编码
     */
    private String resolveContinuousMaterialCode(LhScheduleContext context,
                                                 String machineCode,
                                                 MachineScheduleDTO machine,
                                                 LhMachineOnlineInfo onlineInfo) {
        String rollingMaterialCode = resolveRollingContinuousMaterialCode(context, machineCode, machine);
        if (StringUtils.isNotEmpty(rollingMaterialCode)) {
            return rollingMaterialCode;
        }
        return onlineInfo != null ? onlineInfo.getMaterialCode() : null;
    }

    /**
     * 解析滚动衔接后机台应继续承接的当前物料。
     *
     * @param context 排程上下文
     * @param machineCode 机台编码
     * @param machine 机台状态
     * @return 未收尾的继承当前物料；不存在时返回 null
     */
    private String resolveRollingContinuousMaterialCode(LhScheduleContext context,
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
        if (latestInheritedResult == null || StringUtils.equals("1", latestInheritedResult.getIsEnd())) {
            return null;
        }
        return machine.getCurrentMaterialCode();
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

        private SurplusCalculation(int surplusQty, int actualFinishedQty, int ignoredOverProductionQty,
                                   int lastMonthOverdueQty) {
            this.surplusQty = surplusQty;
            this.actualFinishedQty = Math.max(0, actualFinishedQty);
            this.ignoredOverProductionQty = Math.max(0, ignoredOverProductionQty);
            this.lastMonthOverdueQty = lastMonthOverdueQty;
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
