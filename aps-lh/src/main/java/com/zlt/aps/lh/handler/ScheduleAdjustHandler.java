package com.zlt.aps.lh.handler;

import com.zlt.aps.lh.api.constant.LhScheduleConstant;
import com.zlt.aps.lh.api.constant.LhScheduleParamConstant;
import com.zlt.aps.lh.component.TargetScheduleQtyResolver;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.api.domain.dto.MachineScheduleDTO;
import com.zlt.aps.lh.api.domain.dto.SkuScheduleDTO;
import com.zlt.aps.lh.api.domain.vo.LhShiftConfigVO;
import com.zlt.aps.lh.api.domain.entity.LhUnscheduledResult;
import com.zlt.aps.lh.api.domain.entity.LhScheduleResult;
import com.zlt.aps.lh.api.enums.ScheduleStepEnum;
import com.zlt.aps.lh.api.enums.ScheduleTypeEnum;
import com.zlt.aps.lh.api.enums.SkuTagEnum;
import com.zlt.aps.lh.engine.strategy.IEndingJudgmentStrategy;
import com.zlt.aps.lh.util.MonthPlanDayQtyUtil;
import com.zlt.aps.lh.util.ShiftFieldUtil;
import com.zlt.aps.lh.util.LhScheduleTimeUtil;
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
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * S4.3 排程调整与SKU归集处理器
 * <p>基于前日排程修正产量，从月计划获取SKU，按结构归集，计算硫化余量，标记收尾/续作状态</p>
 *
 * @author APS
 */
@Slf4j
@Component
public class ScheduleAdjustHandler extends AbsScheduleStepHandler {

    /** 无排产目标量未排产提示 */
    private static final String NO_PLAN_QTY_REASON_TEMPLATE = "物料：%s 没有排产目标量，不进行排产";
    /** 无窗口计划量但存在余量/正向结转目标量提示 */
    private static final String TARGET_QTY_ONLY_WARN_TEMPLATE =
            "物料：%s 当前排程窗口没有计划量，但存在月计划余量/正向结转目标量[%d]，继续排产";
    /** 开产管控缺口未排提示 */
    private static final String OPEN_PRODUCTION_SHORTAGE_REASON_TEMPLATE =
            "物料：%s 开产管控导致排产目标量低于待排量，待排量[%d]，目标量[%d]，缺口[%d]，缺口比例[%s]，阈值[%s]";
    /** 满排模式下无窗口计划量仍继续排产提示 */
    private static final String FULL_CAPACITY_WARN_TEMPLATE =
            "物料：%s 当前排程窗口没有计划量，但按产能满排模式生成排产目标量[%d]，继续排产";
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

        // S4.3.4 区分续作SKU和新增SKU
        classifyContinuousAndNewSkus(context);

        log.info("排程调整与SKU归集完成, 续作SKU: {}个, 新增SKU: {}个",
                context.getContinuousSkuList().size(), context.getNewSpecSkuList().size());
    }

    /**
     * 对前日排程结果做欠/超产量调整（原地修改列表）。
     * <p>
     * 欠产（夜班计划 > 实际完成）：将差额追加到T日早班的计划量中<br/>
     * 超产（实际完成 > 计划）：可冲抵后续班次计划<br/>
     * 已收尾的SKU从续作列表中去除
     * </p>
     *
     * @param context 排程上下文
     */
    private void adjustPreviousSchedule(LhScheduleContext context) {
        List<LhScheduleResult> previousScheduleList = context.getPreviousScheduleResultList();
        if (previousScheduleList == null || previousScheduleList.isEmpty()) {
            log.warn("前日排程结果为空，跳过欠产/超产传导, 工厂: {}, T日: {}",
                    context.getFactoryCode(), LhScheduleTimeUtil.formatDate(context.getScheduleDate()));
            return;
        }
        Date previousScheduleDate = resolvePreviousScheduleDate(context);
        Map<String, Integer> materialPlannedQtyMap = new LinkedHashMap<>();
        for (LhScheduleResult result : previousScheduleList) {
            if (StringUtils.isEmpty(result.getMaterialCode())) {
                continue;
            }
            // 滚动衔接时只结转继承窗口之前的班次量，避免与继承量重复
            int plannedQty = resolveCarryForwardPlanQty(context, result);
            materialPlannedQtyMap.merge(result.getMaterialCode(), plannedQty, Integer::sum);
        }
        Map<String, Integer> carryForwardQtyMap = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : materialPlannedQtyMap.entrySet()) {
            String materialCode = entry.getKey();
            int plannedQty = entry.getValue();
            int finishedQty = resolveMaterialDayFinishedQty(context, materialCode, previousScheduleDate);
            int diffQty = plannedQty - finishedQty;
            if (diffQty != 0) {
                carryForwardQtyMap.put(materialCode, diffQty);
                log.debug("欠产/超产传导: 日期[{}] SKU[{}] 前日计划总量[{}] 日完成量[{}] 净值[{}]",
                        LhScheduleTimeUtil.formatDate(previousScheduleDate), materialCode, plannedQty, finishedQty, diffQty);
            }
        }
        context.setCarryForwardQtyMap(carryForwardQtyMap);
        log.info("前日排程欠产/超产净值归集完成, 记录数: {}, 影响SKU数: {}",
                previousScheduleList.size(), carryForwardQtyMap.size());
    }

    /**
     * 从月度计划获取T日SKU数据，按产品结构归集，计算硫化余量
     * <p>
     * 硫化余量 = 月度计划量 - 硫化已完成量
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

        for (FactoryMonthPlanProductionFinalResult plan : monthPlanList) {
            // 计算硫化余量
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
        }

        context.setStructureSkuMap(structureSkuMap);
        int totalSkuCount = structureSkuMap.values().stream().mapToInt(List::size).sum();
        log.info("SKU按结构归集完成, 结构数量: {}, SKU总数: {}", structureSkuMap.size(), totalSkuCount);
    }

    /**
     * 计算SKU的硫化余量
     * <p>
     * 统一按 月度计划总量 - 已完成量 计算，不再读取月余量表（T_MDM_MONTH_SURPLUS）
     * </p>
     *
     * @param context 排程上下文
     * @param plan    月生产计划记录
     * @return 硫化余量
     */
    private SurplusCalculation calculateSurplusQty(LhScheduleContext context, FactoryMonthPlanProductionFinalResult plan) {
        // 统一按月计划总量减已完成量计算余量，不再使用月余量表兜底。
        int totalPlanQty = plan.getTotalQty() != null ? plan.getTotalQty() : 0;
        int finishedQty = calculateFinishedQty(context, plan);
        return new SurplusCalculation(Math.max(0, totalPlanQty - finishedQty));
    }

    /**
     * 计算指定SKU的已完成量（汇总各班次完成量）
     *
     * @param context 排程上下文
     * @param plan    月生产计划记录
     * @return 已完成量
     */
    private int calculateFinishedQty(LhScheduleContext context, FactoryMonthPlanProductionFinalResult plan) {
        // 严格使用基础数据阶段按物料汇总好的月累计完成量（截至窗口T-1，含当天）。
        String materialCode = plan.getMaterialCode();
        if (StringUtils.isNotEmpty(materialCode)) {
            Integer monthFinishedQty = context.getMaterialMonthFinishedQtyMap().get(materialCode);
            if (Objects.nonNull(monthFinishedQty)) {
                return Math.max(monthFinishedQty, 0);
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
        dto.setFinishedQty(Math.max(0, dto.getMonthPlanQty() - surplus.getSurplusQty()));
        int carryForwardQty = Math.max(0, context.getCarryForwardQtyMap().getOrDefault(plan.getMaterialCode(), 0));
        int windowPlanQty = MonthPlanDayQtyUtil.resolveWindowPlanQty(
                plan, context.getScheduleDate(), context.getScheduleTargetDate());
        // 继承量已由滚动衔接占用，需从窗口待排量中扣减，防止重复排产
        int inheritedPlanQty = Math.max(0, context.getInheritedPlanQtyMap().getOrDefault(plan.getMaterialCode(), 0));
        dto.setWindowPlanQty(windowPlanQty);
        dto.setSurplusQty(surplus.getSurplusQty());
        dto.setEmbryoStock(context.getEmbryoRealtimeStockMap().getOrDefault(plan.getEmbryoCode(), -1));
        // 待排量以“余量/库存取大”为基线，再叠加滚动继承扣减与欠产传导，避免重复排产。
        int basePendingQty = Math.max(surplus.getSurplusQty(), Math.max(0, dto.getEmbryoStock()));
        if (context.isStopProductionMode()) {
            basePendingQty = resolveStopProductionDemandQty(context, plan, dto.getEmbryoStock());
        }
        dto.setPendingQty(Math.max(0, basePendingQty - inheritedPlanQty) + carryForwardQty);
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
        log.debug("SKU待排量计算完成, materialCode: {}, 结构: {}, 月计划: {}, 窗口计划: {}, 已完成: {}, 余量: {}, 待排: {}, 目标量: {}, 班产: {}",
                dto.getMaterialCode(), dto.getStructureName(), dto.getMonthPlanQty(), dto.getWindowPlanQty(),
                dto.getFinishedQty(), dto.getSurplusQty(), dto.getPendingQty(), dto.getTargetScheduleQty(),
                dto.getShiftCapacity());
        if (context.isRollingScheduleHandoff() || inheritedPlanQty > 0) {
            log.info("滚动待排量拆解, 物料: {}, 窗口计划量: {}, 已继承量: {}, 欠产传导量: {}, 待排量: {}, 余量: {}, 目标量: {}",
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

        // 施工阶段
        dto.setConstructionStage(plan.getConstructionStage());

        // 示方书信息
        dto.setEmbryoNo(plan.getEmbryoNo());
        dto.setTextNo(plan.getTextNo());
        dto.setLhNo(plan.getLhNo());

        // 版本信息
        dto.setMonthPlanVersion(plan.getMonthPlanVersion());
        dto.setProductionVersion(plan.getProductionVersion());

        // 默认标记为常规
        dto.setSkuTag(SkuTagEnum.NORMAL.getCode());

        return dto;
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
     * 追加“无计划量不排产”的未排结果。
     *
     * @param context 排程上下文
     * @param sku SKU排程DTO
     */
    private void addNoPlanUnscheduledResult(LhScheduleContext context, SkuScheduleDTO sku) {
        String reason = String.format(NO_PLAN_QTY_REASON_TEMPLATE, sku.getMaterialCode());
        log.warn(reason);

        LhUnscheduledResult unscheduled = buildBaseUnscheduledResult(context, sku);
        unscheduled.setUnscheduledQty(0);
        unscheduled.setUnscheduledReason(reason);
        context.getUnscheduledResultList().add(unscheduled);
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
     * 获取指定日期的物料日完成量（按“物料+日期”聚合）。
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
     * 构建“物料+日期”聚合Key。
     *
     * @param materialCode 物料编码
     * @param date 日期
     * @return 聚合Key
     */
    private String buildMaterialDayKey(String materialCode, Date date) {
        return materialCode + "_" + LhScheduleTimeUtil.formatDate(LhScheduleTimeUtil.clearTime(date));
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
     * 基于月计划开始日期（BEGIN_DAY）计算延迟上机天数。
     *
     * @param context 排程上下文
     * @param plan 月生产计划
     * @return 延迟天数；BEGIN_DAY 为空或非法时返回 -1
     */
    private int resolveDelayDays(LhScheduleContext context, FactoryMonthPlanProductionFinalResult plan) {
        if (context.getScheduleDate() == null) {
            return -1;
        }
        Integer beginDay = plan != null ? plan.getBeginDay() : null;
        if (beginDay == null || beginDay < MIN_DAY_OF_MONTH || beginDay > MAX_DAY_OF_MONTH) {
            return -1;
        }
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(context.getScheduleDate());
        int scheduleDayOfMonth = calendar.get(Calendar.DAY_OF_MONTH);
        return Math.max(scheduleDayOfMonth - beginDay, 0);
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

        // 保持MES最近快照顺序消费，同时优先承接滚动衔接后的机台当前物料。
        Map<String, MachineScheduleDTO> schedulableMachineMap = context.getMachineScheduleMap();
        for (Map.Entry<String, LhMachineOnlineInfo> entry : context.getMachineOnlineInfoMap().entrySet()) {
            if (CollectionUtils.isEmpty(schedulableMachineMap)
                    || !schedulableMachineMap.containsKey(entry.getKey())) {
                continue;
            }
            String materialCode = resolveContinuousMaterialCode(
                    context, entry.getKey(), schedulableMachineMap.get(entry.getKey()), entry.getValue());
            assignContinuousSku(entry.getKey(), materialCode, skuByMaterialMap, continuousSkuList);
        }

        if (context.isRollingScheduleHandoff() && !CollectionUtils.isEmpty(schedulableMachineMap)) {
            for (Map.Entry<String, MachineScheduleDTO> entry : schedulableMachineMap.entrySet()) {
                String materialCode = resolveRollingContinuousMaterialCode(context, entry.getKey(), entry.getValue());
                assignContinuousSku(entry.getKey(), materialCode, skuByMaterialMap, continuousSkuList);
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
                                     List<SkuScheduleDTO> continuousSkuList) {
        if (StringUtils.isEmpty(machineCode) || StringUtils.isEmpty(materialCode)) {
            return;
        }
        List<SkuScheduleDTO> matchedSkuList = skuByMaterialMap.get(materialCode);
        if (CollectionUtils.isEmpty(matchedSkuList)) {
            return;
        }
        // 同一物料存在多条SKU时，按归集顺序逐条消费，且仅允许有效机台占用。
        SkuScheduleDTO matchedSku = matchedSkuList.remove(0);
        matchedSku.setScheduleType(ScheduleTypeEnum.CONTINUOUS.getCode());
        matchedSku.setContinuousMachineCode(machineCode);
        continuousSkuList.add(matchedSku);
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

        private SurplusCalculation(int surplusQty) {
            this.surplusQty = surplusQty;
        }

        public int getSurplusQty() {
            return surplusQty;
        }
    }

    @Override
    protected String getStepName() {
        return ScheduleStepEnum.S4_3_ADJUST_AND_GATHER.getDescription();
    }
}
