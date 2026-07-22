package com.zlt.aps.lh.engine.strategy.support;

import com.zlt.aps.lh.api.domain.dto.SkuDailyPlanQuotaDTO;
import com.zlt.aps.lh.api.domain.dto.SkuScheduleDTO;
import com.zlt.aps.lh.api.domain.entity.LhMouldChangePlan;
import com.zlt.aps.lh.api.domain.entity.LhUnscheduledResult;
import com.zlt.aps.lh.api.enums.ConstructionStageEnum;
import com.zlt.aps.lh.component.MonthPlanDateResolver;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.util.LhScheduleTimeUtil;
import com.zlt.aps.lh.util.PriorityTraceLogHelper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.util.CollectionUtils;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;

/**
 * 新增待排SKU前置未排规则。
 *
 * <p>统一处理收尾小余量和仅历史欠产两项规则，并固定按“收尾小余量优先、仅历史欠产其次”执行。
 * 换活字块和新增排产必须复用本规则，避免SKU在S4.4被提前消费后绕过S4.5未排判断。</p>
 *
 * @author APS
 */
@Slf4j
public final class PendingSkuUnscheduledRule {

    /** 非续作SKU在排程及提前生产范围内无日计划、且无前日交替承接关系的统一未排原因 */
    public static final String DAILY_PLAN_ADMISSION_UNSCHEDULED_REASON =
            "排程窗口及提前生产范围内无日计划量，且无前日排程T+1交替计划";

    /** 续作试制、量试SKU完整判断范围无日计划量的统一未排原因 */
    public static final String CONTINUOUS_TRIAL_DAILY_PLAN_ADMISSION_UNSCHEDULED_REASON =
            "试制、量试月计划排产量全部为0，跳过排产";

    /** 仅历史欠产且最近一次已有完成量的统一未排原因 */
    public static final String HISTORY_SHORTAGE_UNSCHEDULED_REASON =
            "仅历史欠产、后续无月计划，且最近一次（前一次）已有完成量，本次跳过不排";

    /** 自动排程数据来源 */
    private static final String AUTO_DATA_SOURCE = "0";

    private PendingSkuUnscheduledRule() {
    }

    /**
     * 评估非续作SKU是否因完整准入范围无日计划量且无前日交替承接关系而直接进入未排。
     * <p>判断范围由两部分组成：SKU已初始化的排程窗口计划量覆盖窗口首日至窗口末日；窗口结束日之后
     * 复用提前生产判断器，按硫化参数配置的提前生产天数阈值继续向后查找。完整范围无正计划量时，
     * 再按“后物料”检查前日排程生成的T+1交替计划；任一条件放行仅代表继续进入现有排产流程，
     * 不代表必须生成排程结果。</p>
     * <p>日计划严格沿用物料编码和产品状态复合维度；历史交替计划没有产品状态字段，因此承接关系
     * 只按物料编码匹配。前日排程的T+1日对应当前排程窗口首日，仅该日计划可作为本次准入依据。</p>
     * <p>本方法只负责判断和构造未排结果，不修改SKU目标量、排产集合、胎胚库存或日计划账本。</p>
     *
     * @param context 排程上下文，提供排程窗口、提前生产参数和跨月月计划数据
     * @param sku 待评估的新增SKU
     * @return 命中规则时返回未排结果；未命中返回null
     */
    public static LhUnscheduledResult evaluateDailyPlanAdmission(LhScheduleContext context,
                                                                  SkuScheduleDTO sku) {
        if (Objects.isNull(context) || Objects.isNull(sku)
                || Objects.isNull(context.getWindowEndDate())) {
            return null;
        }
        LocalDate windowStartDate = toLocalDate(context.getScheduleDate());
        LocalDate windowEndDate = toLocalDate(context.getWindowEndDate());
        if (Objects.isNull(windowEndDate)) {
            return null;
        }

        // 窗口内原始日计划和窗口后N天任一有量时，保留SKU继续走现有排产主链。
        if (hasPositiveDailyPlanInAdmissionRange(context, sku, windowEndDate)) {
            return null;
        }

        // 前日排程T+1日交替计划已明确安排当前物料承接时，保留SKU并继续复用历史反选和普通新增主链。
        if (hasPreviousT1MouldChangePlan(context, sku.getMaterialCode())) {
            return null;
        }

        int earlyProductionDaysThreshold = EarlyProductionChecker.resolveEarlyProductionDaysThreshold(context);
        LocalDate admissionEndDate = windowEndDate.plusDays(earlyProductionDaysThreshold);
        String detail = String.format("工厂: %s, 批次: %s, 物料: %s, 产品状态: %s, 施工阶段: %s, "
                        + "排程窗口: %s～%s, 提前生产天数: %d, 准入截止日: %s, T+1交替计划命中: 否, 原因: %s",
                context.getFactoryCode(), context.getBatchNo(), sku.getMaterialCode(), sku.getProductStatus(),
                sku.getConstructionStage(), windowStartDate, windowEndDate, earlyProductionDaysThreshold,
                admissionEndDate, DAILY_PLAN_ADMISSION_UNSCHEDULED_REASON);
        log.info("非续作SKU日计划准入拦截, factoryCode: {}, batchNo: {}, materialCode: {}, "
                        + "productStatus: {}, constructionStage: {}, windowStartDate: {}, windowEndDate: {}, "
                        + "earlyProductionDaysThreshold: {}, admissionEndDate: {}, previousT1ChangeoverMatched: false, "
                        + "reason: {}",
                context.getFactoryCode(), context.getBatchNo(), sku.getMaterialCode(), sku.getProductStatus(),
                sku.getConstructionStage(), windowStartDate, windowEndDate, earlyProductionDaysThreshold,
                admissionEndDate, DAILY_PLAN_ADMISSION_UNSCHEDULED_REASON);
        PriorityTraceLogHelper.appendProcessLog(context, "SKU无计划量不排产", detail);
        LhUnscheduledResult unscheduledResult = buildUnscheduledResult(
                context, sku, 0, DAILY_PLAN_ADMISSION_UNSCHEDULED_REASON);
        // 月计划版本和生产版本属于本次准入未排结果的对账字段，仅在新增规则命中时回填，
        // 避免扩展公共构造方法后改变既有收尾小余量、仅历史欠产规则的落库内容。
        unscheduledResult.setMonthPlanVersion(sku.getMonthPlanVersion());
        unscheduledResult.setProductionVersion(sku.getProductionVersion());
        return unscheduledResult;
    }

    /**
     * 评估续作试制、量试SKU是否因完整准入范围无日计划量而直接进入未排。
     * <p>本规则仅按施工阶段识别试制和量试，判断范围与非续作日计划准入完全一致。
     * 前日T+1交替计划只是非续作候选的放行条件，不得放行全范围为零的续作试制、量试SKU。</p>
     * <p>本方法只负责判断和构造未排结果，不修改续作列表、机台或日计划账本。</p>
     *
     * @param context 排程上下文，提供排程窗口、提前生产参数和跨月月计划数据
     * @param sku 已识别为续作的SKU
     * @return 命中规则时返回未排结果；非试制量试或范围内有计划时返回null
     */
    public static LhUnscheduledResult evaluateContinuousTrialDailyPlanAdmission(
            LhScheduleContext context, SkuScheduleDTO sku) {
        if (Objects.isNull(context) || Objects.isNull(sku)
                || !isTrialOrMassTrialSku(sku)
                || Objects.isNull(context.getWindowEndDate())) {
            return null;
        }
        LocalDate windowStartDate = toLocalDate(context.getScheduleDate());
        LocalDate windowEndDate = toLocalDate(context.getWindowEndDate());
        if (Objects.isNull(windowEndDate)
                || hasPositiveDailyPlanInAdmissionRange(context, sku, windowEndDate)) {
            return null;
        }

        int earlyProductionDaysThreshold = EarlyProductionChecker.resolveEarlyProductionDaysThreshold(context);
        LocalDate admissionEndDate = windowEndDate.plusDays(earlyProductionDaysThreshold);
        String detail = String.format("工厂: %s, 批次: %s, 物料: %s, 产品状态: %s, 施工阶段: %s, "
                        + "续作机台: %s, 排程窗口: %s～%s, 提前生产天数: %d, 准入截止日: %s, 原因: %s",
                context.getFactoryCode(), context.getBatchNo(), sku.getMaterialCode(), sku.getProductStatus(),
                sku.getConstructionStage(), sku.getContinuousMachineCode(), windowStartDate, windowEndDate,
                earlyProductionDaysThreshold, admissionEndDate,
                CONTINUOUS_TRIAL_DAILY_PLAN_ADMISSION_UNSCHEDULED_REASON);
        log.info("续作试制量试SKU日计划准入拦截, factoryCode: {}, batchNo: {}, materialCode: {}, "
                        + "productStatus: {}, constructionStage: {}, continuousMachineCode: {}, windowStartDate: {}, "
                        + "windowEndDate: {}, earlyProductionDaysThreshold: {}, admissionEndDate: {}, reason: {}",
                context.getFactoryCode(), context.getBatchNo(), sku.getMaterialCode(), sku.getProductStatus(),
                sku.getConstructionStage(), sku.getContinuousMachineCode(), windowStartDate, windowEndDate,
                earlyProductionDaysThreshold, admissionEndDate,
                CONTINUOUS_TRIAL_DAILY_PLAN_ADMISSION_UNSCHEDULED_REASON);
        PriorityTraceLogHelper.appendProcessLog(context, "续作试制量试无计划量不排产", detail);
        LhUnscheduledResult unscheduledResult = buildUnscheduledResult(
                context, sku, 0, CONTINUOUS_TRIAL_DAILY_PLAN_ADMISSION_UNSCHEDULED_REASON);
        unscheduledResult.setMonthPlanVersion(sku.getMonthPlanVersion());
        unscheduledResult.setProductionVersion(sku.getProductionVersion());
        return unscheduledResult;
    }

    /**
     * 判断完整准入范围内是否存在正日计划量。
     * <p>窗口内使用SKU初始化时按月计划DAY_N汇总的原始计划量；窗口结束后
     * 继续复用提前生产判断器，按物料、产品状态和实际年月读取后续DAY_N。</p>
     *
     * @param context 排程上下文
     * @param sku 待判断SKU
     * @param windowEndDate 排程窗口结束日
     * @return true-范围内至少一天有正日计划量；false-全范围无正日计划量
     */
    private static boolean hasPositiveDailyPlanInAdmissionRange(LhScheduleContext context,
                                                                 SkuScheduleDTO sku,
                                                                 LocalDate windowEndDate) {
        if (sku.getWindowPlanQty() > 0) {
            return true;
        }
        // 以窗口最后一天为基准后看N天，复用提前生产参数、跨月取数和产品状态口径。
        return Objects.nonNull(EarlyProductionChecker.resolveFirstFuturePlanDate(
                context, sku, windowEndDate));
    }

    /**
     * 判断SKU施工阶段是否为试制或量试。
     *
     * @param sku SKU排程信息
     * @return true-试制或量试；false-其他施工阶段
     */
    private static boolean isTrialOrMassTrialSku(SkuScheduleDTO sku) {
        return Objects.nonNull(sku)
                && (StringUtils.equals(ConstructionStageEnum.TRIAL.getCode(), sku.getConstructionStage())
                || StringUtils.equals(ConstructionStageEnum.MASS_TRIAL.getCode(), sku.getConstructionStage()));
    }

    /**
     * 判断前日排程生成的T+1模具交替计划是否由当前物料承接。
     * <p>基础数据层已将查询日期固定为当前业务目标日前一日，并只加载换模、换活字块类型；
     * 此处继续校验交替实际日期必须属于当前窗口首日，避免把历史窗口其他日期的计划误当成T+1承接。</p>
     *
     * @param context 排程上下文
     * @param materialCode 当前SKU物料编码
     * @return true-存在以后物料匹配的T+1交替计划；false-不存在
     */
    private static boolean hasPreviousT1MouldChangePlan(LhScheduleContext context, String materialCode) {
        if (Objects.isNull(context) || StringUtils.isEmpty(materialCode)
                || Objects.isNull(context.getScheduleDate())
                || CollectionUtils.isEmpty(context.getHistoricalReverseMouldChangePlanList())) {
            return false;
        }
        for (LhMouldChangePlan plan : context.getHistoricalReverseMouldChangePlanList()) {
            if (Objects.nonNull(plan)
                    && StringUtils.equals(materialCode, plan.getAfterMaterialCode())
                    && LhScheduleTimeUtil.isSameDay(plan.getPlanDate(), context.getScheduleDate())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 将日期转换为排程业务日。
     *
     * @param date 日期
     * @return 本地业务日；日期为空时返回null
     */
    private static LocalDate toLocalDate(java.util.Date date) {
        if (Objects.isNull(date)) {
            return null;
        }
        return date.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
    }

    /**
     * 按优先级评估新增待排SKU是否需要直接进入未排。
     *
     * @param context 排程上下文
     * @param sku 新增待排SKU
     * @param smallEndingRuleEnding 是否按收尾小余量规则视为收尾
     * @param embryoStockEndingTargetApplied 是否已命中成型胎胚库存收尾目标
     * @return 命中规则时返回未排结果；未命中返回null
     */
    public static LhUnscheduledResult evaluate(LhScheduleContext context,
                                               SkuScheduleDTO sku,
                                               boolean smallEndingRuleEnding,
                                               boolean embryoStockEndingTargetApplied) {
        if (Objects.isNull(context) || Objects.isNull(sku)) {
            return null;
        }
        // 收尾小余量使用原始硫化余量判断，优先级高于仅历史欠产规则。
        int surplusQty = Math.max(0, sku.getSurplusQty());
        int toleranceQty = SmallEndingSurplusSkipRule.resolveToleranceQty(context);
        if (!embryoStockEndingTargetApplied && smallEndingRuleEnding && surplusQty <= toleranceQty) {
            int previousNightPlanQty = SmallEndingSurplusSkipRule.resolveTargetPreviousT1NightPlanQty(
                    context, sku.getMaterialCode());
            boolean smallEndingSkipped = SmallEndingSurplusSkipRule.shouldSkip(
                    context, sku, smallEndingRuleEnding);
            log.info("待排SKU收尾小余量规则判断, materialCode: {}, surplusQty: {}, toleranceQty: {}, "
                            + "previousT1NightPlanQty: {}, shiftCapacity: {}, skipped: {}",
                    sku.getMaterialCode(), surplusQty, toleranceQty, previousNightPlanQty,
                    sku.getShiftCapacity(), smallEndingSkipped);
            if (smallEndingSkipped) {
                return buildUnscheduledResult(context, sku, surplusQty,
                        SmallEndingSurplusSkipRule.UNSCHEDULED_REASON);
            }
        }
        Integer latestPreviousFinishedQty = resolveLatestPreviousFinishedQty(
                context, sku.getMaterialCode(), sku.getProductStatus());
        if (!shouldSkipHistoryShortage(context, sku, latestPreviousFinishedQty)) {
            return null;
        }
        int historyShortageRemainingQty = resolveHistoryShortageRemainingQty(sku);
        log.info("待排SKU仅历史欠产规则命中, materialCode: {}, monthlyHistoryShortageQty: {}, "
                        + "effectiveLastMonthOverdueQty: {}, historyShortageRemainingQty: {}, scheduleDate: {}, "
                        + "latestPreviousFinishedQty: {}, reason: {}",
                sku.getMaterialCode(), sku.getMonthlyHistoryShortageQty(), sku.getEffectiveLastMonthOverdueQty(),
                historyShortageRemainingQty, context.getScheduleDate(), latestPreviousFinishedQty,
                HISTORY_SHORTAGE_UNSCHEDULED_REASON);
        return buildUnscheduledResult(context, sku, historyShortageRemainingQty,
                HISTORY_SHORTAGE_UNSCHEDULED_REASON);
    }

    /**
     * 判断SKU是否命中仅历史欠产跳过规则。
     *
     * @param context 排程上下文
     * @param sku 新增待排SKU
     * @param latestPreviousFinishedQty 当前月最近一次非空日完成量
     * @return true-命中仅历史欠产规则；false-不命中
     */
    public static boolean shouldSkipHistoryShortage(LhScheduleContext context,
                                                    SkuScheduleDTO sku,
                                                    Integer latestPreviousFinishedQty) {
        if (Objects.isNull(context) || Objects.isNull(sku) || sku.isContinuousCompensationSku()) {
            return false;
        }
        // 本月历史欠产和有效上月欠产都属于历史欠产来源，统一按当前净硫化余量判断是否仍需补排。
        if (resolveHistoryShortageRemainingQty(sku) <= 0 || !isWindowDayPlanEmpty(sku)) {
            return false;
        }
        if (resolveCurrentMonthPlanQtyFromScheduleDate(context, sku) > 0) {
            return false;
        }
        // 最近一次完成量为0是有效数据，但不能触发跳过，也不得继续向更早日期回溯。
        return Objects.nonNull(latestPreviousFinishedQty) && latestPreviousFinishedQty > 0;
    }

    /**
     * 解析仅历史欠产形成的当前净余量。
     * <p>本月历史欠产或有效上月欠产任一为正时，说明当前余量存在历史欠产来源；未排数量必须使用
     * 已扣除本月累计完成量后的净硫化余量，不能直接使用原始上月欠产量，避免未排数量虚增。</p>
     *
     * @param sku 新增待排SKU
     * @return 仅历史欠产净余量；不存在正向历史欠产来源时返回0
     */
    private static int resolveHistoryShortageRemainingQty(SkuScheduleDTO sku) {
        if (Objects.isNull(sku)) {
            return 0;
        }
        boolean hasHistoryShortageSource = Math.max(0, sku.getMonthlyHistoryShortageQty()) > 0
                || Math.max(0, sku.getEffectiveLastMonthOverdueQty()) > 0;
        return hasHistoryShortageSource ? Math.max(0, sku.getSurplusQty()) : 0;
    }

    /**
     * 解析当前月月初至排程日T-1最近一次非空日完成量。
     *
     * @param context 排程上下文
     * @param materialCode 物料编码
     * @param productStatus 产品状态
     * @return 最近一次非空日完成量；当前月无有效记录时返回null
     */
    public static Integer resolveLatestPreviousFinishedQty(LhScheduleContext context,
                                                           String materialCode,
                                                           String productStatus) {
        if (Objects.isNull(context) || Objects.isNull(context.getScheduleDate())
                || StringUtils.isEmpty(materialCode)
                || CollectionUtils.isEmpty(context.getMaterialMonthDailyFinishedQtyMap())) {
            return null;
        }
        LocalDate scheduleDate = context.getScheduleDate().toInstant()
                .atZone(ZoneId.systemDefault()).toLocalDate();
        LocalDate monthStart = scheduleDate.withDayOfMonth(1);
        for (LocalDate finishDate = scheduleDate.minusDays(1);
             !finishDate.isBefore(monthStart); finishDate = finishDate.minusDays(1)) {
            Integer finishedQty = resolveDailyFinishedQty(context, materialCode, productStatus, finishDate);
            if (Objects.nonNull(finishedQty)) {
                return Math.max(finishedQty, 0);
            }
        }
        return null;
    }

    /**
     * 判断当前排程窗口dayN月计划量是否全为0。
     *
     * @param sku 新增待排SKU
     * @return true-窗口内dayN均为0；false-仍有计划或账本缺失
     */
    private static boolean isWindowDayPlanEmpty(SkuScheduleDTO sku) {
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
     * 统计当前排程月从排程日T到月底的日计划量。
     *
     * @param context 排程上下文
     * @param sku 新增待排SKU
     * @return 当前排程月T至月底日计划总量
     */
    private static int resolveCurrentMonthPlanQtyFromScheduleDate(LhScheduleContext context, SkuScheduleDTO sku) {
        if (Objects.isNull(context) || Objects.isNull(sku) || Objects.isNull(context.getScheduleDate())
                || StringUtils.isEmpty(sku.getMaterialCode())) {
            return 0;
        }
        LocalDate scheduleDate = context.getScheduleDate().toInstant()
                .atZone(ZoneId.systemDefault()).toLocalDate();
        LocalDate monthEndDate = scheduleDate.withDayOfMonth(scheduleDate.lengthOfMonth());
        int totalQty = 0;
        for (LocalDate cursor = scheduleDate; !cursor.isAfter(monthEndDate); cursor = cursor.plusDays(1)) {
            totalQty += Math.max(0, MonthPlanDateResolver.resolveDayQty(
                    context, sku.getMaterialCode(), sku.getProductStatus(), cursor));
        }
        return totalQty;
    }

    /**
     * 按物料、产品状态和完成日期读取逐日完成量。
     *
     * @param context 排程上下文
     * @param materialCode 物料编码
     * @param productStatus 产品状态
     * @param finishDate 完成日期
     * @return 日完成量；无记录返回null
     */
    private static Integer resolveDailyFinishedQty(LhScheduleContext context,
                                                   String materialCode,
                                                   String productStatus,
                                                   LocalDate finishDate) {
        String materialStatusKey = MonthPlanDateResolver.buildMaterialStatusKey(materialCode, productStatus);
        Integer finishedQty = context.getMaterialMonthDailyFinishedQtyMap()
                .get(materialStatusKey + "_" + finishDate);
        if (Objects.nonNull(finishedQty) || StringUtils.equals(materialStatusKey, materialCode)) {
            return finishedQty;
        }
        return context.getMaterialMonthDailyFinishedQtyMap().get(materialCode + "_" + finishDate);
    }

    /**
     * 构建规则命中的未排结果。
     *
     * @param context 排程上下文
     * @param sku 新增待排SKU
     * @param unscheduledQty 未排数量
     * @param reason 未排原因
     * @return 未排结果
     */
    private static LhUnscheduledResult buildUnscheduledResult(LhScheduleContext context,
                                                              SkuScheduleDTO sku,
                                                              int unscheduledQty,
                                                              String reason) {
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
        return unscheduled;
    }
}
