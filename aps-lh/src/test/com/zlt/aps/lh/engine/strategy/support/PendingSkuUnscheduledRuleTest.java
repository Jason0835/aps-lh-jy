package com.zlt.aps.lh.engine.strategy.support;

import com.zlt.aps.lh.api.constant.LhScheduleParamConstant;
import com.zlt.aps.lh.api.domain.dto.SkuDailyPlanQuotaDTO;
import com.zlt.aps.lh.api.domain.dto.SkuScheduleDTO;
import com.zlt.aps.lh.api.domain.entity.LhMouldChangePlan;
import com.zlt.aps.lh.api.domain.entity.LhUnscheduledResult;
import com.zlt.aps.lh.api.enums.ConstructionStageEnum;
import com.zlt.aps.lh.component.MonthPlanDateResolver;
import com.zlt.aps.lh.context.LhScheduleConfig;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.mp.api.domain.entity.FactoryMonthPlanProductionFinalResult;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 新增待排SKU前置未排公共规则测试。
 */
class PendingSkuUnscheduledRuleTest {

    /**
     * 排程窗口及可提前生产范围内日计划量全部为0时，试制SKU必须进入未排。
     */
    @Test
    void evaluateDailyPlanAdmission_shouldReturnUnscheduledWhenAllPlanQtyIsZero() {
        LocalDate scheduleDate = LocalDate.of(2026, 7, 1);
        LhScheduleContext context = buildContext(scheduleDate, scheduleDate.plusDays(2), 2);
        SkuScheduleDTO sku = buildSku("3302001001", "X", ConstructionStageEnum.TRIAL.getCode());
        sku.setMonthPlanVersion("MP-V1");
        sku.setProductionVersion("PP-V1");

        LhUnscheduledResult result = PendingSkuUnscheduledRule.evaluateDailyPlanAdmission(context, sku);

        assertNotNull(result);
        assertEquals(0, result.getUnscheduledQty().intValue());
        assertEquals(PendingSkuUnscheduledRule.DAILY_PLAN_ADMISSION_UNSCHEDULED_REASON,
                result.getUnscheduledReason());
        assertEquals("X", result.getProductStatus());
        assertEquals("MP-V1", result.getMonthPlanVersion());
        assertEquals("PP-V1", result.getProductionVersion());
    }

    /**
     * 窗口内为0、窗口结束后的提前生产范围内存在计划时，只放行现有流程，不生成未排。
     */
    @Test
    void evaluateDailyPlanAdmission_shouldAllowWhenFuturePlanExistsWithinThreshold() {
        LocalDate scheduleDate = LocalDate.of(2026, 7, 1);
        LocalDate windowEndDate = scheduleDate.plusDays(2);
        LhScheduleContext context = buildContext(scheduleDate, windowEndDate, 2);
        SkuScheduleDTO sku = buildSku("3302001002", "T", ConstructionStageEnum.MASS_TRIAL.getCode());
        sku.setDailyPlanQuotaMap(quotaMap(windowEndDate.plusDays(2), 40));

        LhUnscheduledResult result = PendingSkuUnscheduledRule.evaluateDailyPlanAdmission(context, sku);

        assertNull(result);
    }

    /**
     * 同一未来计划日在阈值1时应阻塞、阈值2时应放行，验证规则使用实际参数值。
     */
    @Test
    void evaluateDailyPlanAdmission_shouldUseConfiguredThreshold() {
        LocalDate scheduleDate = LocalDate.of(2026, 7, 1);
        LocalDate windowEndDate = scheduleDate.plusDays(2);
        SkuScheduleDTO sku = buildSku("3302001003", "X", ConstructionStageEnum.TRIAL.getCode());
        sku.setDailyPlanQuotaMap(quotaMap(windowEndDate.plusDays(2), 40));

        LhUnscheduledResult thresholdOneResult = PendingSkuUnscheduledRule.evaluateDailyPlanAdmission(
                buildContext(scheduleDate, windowEndDate, 1), sku);
        LhUnscheduledResult thresholdTwoResult = PendingSkuUnscheduledRule.evaluateDailyPlanAdmission(
                buildContext(scheduleDate, windowEndDate, 2), sku);

        assertNotNull(thresholdOneResult);
        assertNull(thresholdTwoResult);
    }

    /**
     * 跨月判断必须按实际月份和产品状态取数，同物料正规计划不得放行试制SKU。
     */
    @Test
    void evaluateDailyPlanAdmission_shouldReadCrossMonthPlanByProductStatus() {
        LocalDate scheduleDate = LocalDate.of(2026, 7, 30);
        LocalDate windowEndDate = LocalDate.of(2026, 8, 1);
        LhScheduleContext context = buildContext(scheduleDate, windowEndDate, 2);
        FactoryMonthPlanProductionFinalResult formalPlan = monthPlan(
                "3302001004", "S", 2026, 8, 3, 50);
        FactoryMonthPlanProductionFinalResult trialPlan = monthPlan(
                "3302001004", "X", 2026, 8, 3, 0);
        attachMonthPlans(context, formalPlan, trialPlan);
        SkuScheduleDTO trialSku = buildSku(
                "3302001004", "X", ConstructionStageEnum.TRIAL.getCode());

        LhUnscheduledResult result = PendingSkuUnscheduledRule.evaluateDailyPlanAdmission(context, trialSku);

        assertNotNull(result, "同物料正规状态有计划时，不得串状态放行试制SKU");
    }

    /**
     * 提前生产范围跨月且下一月当前产品状态存在计划时，必须按实际年月读取并放行。
     */
    @Test
    void evaluateDailyPlanAdmission_shouldAllowCrossMonthPlanForSameProductStatus() {
        LocalDate scheduleDate = LocalDate.of(2026, 7, 30);
        LocalDate windowEndDate = LocalDate.of(2026, 8, 1);
        LhScheduleContext context = buildContext(scheduleDate, windowEndDate, 2);
        FactoryMonthPlanProductionFinalResult trialPlan = monthPlan(
                "3302001009", "X", 2026, 8, 3, 48);
        attachMonthPlans(context, trialPlan);
        SkuScheduleDTO trialSku = buildSku(
                "3302001009", "X", ConstructionStageEnum.TRIAL.getCode());

        LhUnscheduledResult result = PendingSkuUnscheduledRule.evaluateDailyPlanAdmission(context, trialSku);

        assertNull(result);
    }

    /**
     * 跨年窗口后存在量试计划时，应读取下一年度月份并放行。
     */
    @Test
    void evaluateDailyPlanAdmission_shouldAllowCrossYearFuturePlan() {
        LocalDate scheduleDate = LocalDate.of(2026, 12, 30);
        LocalDate windowEndDate = LocalDate.of(2027, 1, 1);
        LhScheduleContext context = buildContext(scheduleDate, windowEndDate, 2);
        FactoryMonthPlanProductionFinalResult massTrialPlan = monthPlan(
                "3302001005", "T", 2027, 1, 3, 36);
        attachMonthPlans(context, massTrialPlan);
        SkuScheduleDTO massTrialSku = buildSku(
                "3302001005", "T", ConstructionStageEnum.MASS_TRIAL.getCode());

        LhUnscheduledResult result = PendingSkuUnscheduledRule.evaluateDailyPlanAdmission(
                context, massTrialSku);

        assertNull(result);
    }

    /**
     * 正规SKU同样属于非续作待排范围，完整范围无计划且无交替承接时必须进入未排。
     */
    @Test
    void evaluateDailyPlanAdmission_shouldApplyToFormalSku() {
        LocalDate scheduleDate = LocalDate.of(2026, 7, 1);
        LhScheduleContext context = buildContext(scheduleDate, scheduleDate.plusDays(2), 2);
        SkuScheduleDTO formalSku = buildSku(
                "3302001006", "S", ConstructionStageEnum.FORMAL.getCode());

        LhUnscheduledResult result = PendingSkuUnscheduledRule.evaluateDailyPlanAdmission(context, formalSku);

        assertNotNull(result);
        assertEquals(PendingSkuUnscheduledRule.DAILY_PLAN_ADMISSION_UNSCHEDULED_REASON,
                result.getUnscheduledReason());
    }

    /**
     * 完整范围无日计划量但前日排程T+1交替计划后物料匹配时，必须保留SKU继续执行现有排产主链。
     */
    @Test
    void evaluateDailyPlanAdmission_shouldAllowWhenPreviousT1ChangeoverMatchesAfterMaterial() {
        LocalDate scheduleDate = LocalDate.of(2026, 7, 1);
        LhScheduleContext context = buildContext(scheduleDate, scheduleDate.plusDays(2), 2);
        SkuScheduleDTO sku = buildSku("3302001007", "S", ConstructionStageEnum.FORMAL.getCode());
        LhMouldChangePlan plan = new LhMouldChangePlan();
        plan.setAfterMaterialCode(sku.getMaterialCode());
        plan.setPlanDate(toDate(scheduleDate));
        context.setHistoricalReverseMouldChangePlanList(Arrays.asList(plan));

        LhUnscheduledResult result = PendingSkuUnscheduledRule.evaluateDailyPlanAdmission(context, sku);

        assertNull(result);
    }

    /**
     * 后物料虽匹配但交替日期不是前批次T+1日时，不得作为本次准入放行依据。
     */
    @Test
    void evaluateDailyPlanAdmission_shouldIgnoreChangeoverOutsidePreviousT1Date() {
        LocalDate scheduleDate = LocalDate.of(2026, 7, 1);
        LhScheduleContext context = buildContext(scheduleDate, scheduleDate.plusDays(2), 2);
        SkuScheduleDTO sku = buildSku("3302001008", "S", ConstructionStageEnum.FORMAL.getCode());
        LhMouldChangePlan plan = new LhMouldChangePlan();
        plan.setAfterMaterialCode(sku.getMaterialCode());
        plan.setPlanDate(toDate(scheduleDate.plusDays(1)));
        context.setHistoricalReverseMouldChangePlanList(Arrays.asList(plan));

        LhUnscheduledResult result = PendingSkuUnscheduledRule.evaluateDailyPlanAdmission(context, sku);

        assertNotNull(result);
    }

    /**
     * 续作试制SKU完整准入范围无日计划量时，必须生成专用未排结果。
     */
    @Test
    void evaluateContinuousTrialDailyPlanAdmission_shouldBlockTrialSkuWhenAllPlanQtyIsZero() {
        LocalDate scheduleDate = LocalDate.of(2026, 7, 23);
        LhScheduleContext context = buildContext(scheduleDate, scheduleDate.plusDays(2), 2);
        SkuScheduleDTO trialSku = buildSku(
                "3302002468", "X", ConstructionStageEnum.TRIAL.getCode());
        trialSku.setMonthPlanVersion("MP-X");
        trialSku.setProductionVersion("PP-X");

        LhUnscheduledResult result =
                PendingSkuUnscheduledRule.evaluateContinuousTrialDailyPlanAdmission(context, trialSku);

        assertNotNull(result);
        assertEquals(0, result.getUnscheduledQty().intValue());
        assertEquals(PendingSkuUnscheduledRule.CONTINUOUS_TRIAL_DAILY_PLAN_ADMISSION_UNSCHEDULED_REASON,
                result.getUnscheduledReason());
        assertEquals("MP-X", result.getMonthPlanVersion());
        assertEquals("PP-X", result.getProductionVersion());
    }

    /**
     * 续作量试SKU完整准入范围无日计划量时，必须生成专用未排结果。
     */
    @Test
    void evaluateContinuousTrialDailyPlanAdmission_shouldBlockMassTrialSkuWhenAllPlanQtyIsZero() {
        LocalDate scheduleDate = LocalDate.of(2026, 7, 23);
        LhScheduleContext context = buildContext(scheduleDate, scheduleDate.plusDays(2), 2);
        SkuScheduleDTO massTrialSku = buildSku(
                "3302002469", "T", ConstructionStageEnum.MASS_TRIAL.getCode());

        LhUnscheduledResult result =
                PendingSkuUnscheduledRule.evaluateContinuousTrialDailyPlanAdmission(context, massTrialSku);

        assertNotNull(result);
        assertEquals(PendingSkuUnscheduledRule.CONTINUOUS_TRIAL_DAILY_PLAN_ADMISSION_UNSCHEDULED_REASON,
                result.getUnscheduledReason());
    }

    /**
     * 续作试制、量试SKU在提前生产范围任一天有量时，应继续现有续作逻辑。
     */
    @Test
    void evaluateContinuousTrialDailyPlanAdmission_shouldAllowWhenFuturePlanExists() {
        LocalDate scheduleDate = LocalDate.of(2026, 7, 23);
        LocalDate windowEndDate = scheduleDate.plusDays(2);
        LhScheduleContext context = buildContext(scheduleDate, windowEndDate, 2);
        SkuScheduleDTO trialSku = buildSku(
                "3302002470", "X", ConstructionStageEnum.TRIAL.getCode());
        trialSku.setDailyPlanQuotaMap(quotaMap(windowEndDate.plusDays(2), 20));

        LhUnscheduledResult result =
                PendingSkuUnscheduledRule.evaluateContinuousTrialDailyPlanAdmission(context, trialSku);

        assertNull(result);
    }

    /**
     * 正规续作SKU即使判断范围全零，也不得命中试制量试专用规则。
     */
    @Test
    void evaluateContinuousTrialDailyPlanAdmission_shouldIgnoreFormalSku() {
        LocalDate scheduleDate = LocalDate.of(2026, 7, 23);
        LhScheduleContext context = buildContext(scheduleDate, scheduleDate.plusDays(2), 2);
        SkuScheduleDTO formalSku = buildSku(
                "3302002471", "S", ConstructionStageEnum.FORMAL.getCode());

        LhUnscheduledResult result =
                PendingSkuUnscheduledRule.evaluateContinuousTrialDailyPlanAdmission(context, formalSku);

        assertNull(result);
    }

    /**
     * 前日T+1交替计划只放行非续作候选，不得放行全范围为零的续作试制SKU。
     */
    @Test
    void evaluateContinuousTrialDailyPlanAdmission_shouldNotAllowPreviousT1Changeover() {
        LocalDate scheduleDate = LocalDate.of(2026, 7, 23);
        LhScheduleContext context = buildContext(scheduleDate, scheduleDate.plusDays(2), 2);
        SkuScheduleDTO trialSku = buildSku(
                "3302002472", "X", ConstructionStageEnum.TRIAL.getCode());
        LhMouldChangePlan plan = new LhMouldChangePlan();
        plan.setAfterMaterialCode(trialSku.getMaterialCode());
        plan.setPlanDate(toDate(scheduleDate));
        context.setHistoricalReverseMouldChangePlanList(Arrays.asList(plan));

        LhUnscheduledResult result =
                PendingSkuUnscheduledRule.evaluateContinuousTrialDailyPlanAdmission(context, trialSku);

        assertNotNull(result);
    }

    /**
     * 构造排程上下文。
     *
     * @param scheduleDate 排程窗口首日
     * @param windowEndDate 排程窗口最后一天
     * @param threshold 提前生产天数阈值
     * @return 排程上下文
     */
    private LhScheduleContext buildContext(LocalDate scheduleDate,
                                           LocalDate windowEndDate,
                                           int threshold) {
        LhScheduleContext context = new LhScheduleContext();
        context.setFactoryCode("116");
        context.setBatchNo("LHPC-RULE-TEST");
        context.setScheduleDate(toDate(scheduleDate));
        context.setScheduleTargetDate(toDate(windowEndDate));
        context.setWindowEndDate(toDate(windowEndDate));
        Map<String, String> paramMap = new HashMap<String, String>(4);
        paramMap.put(LhScheduleParamConstant.EARLY_PRODUCTION_DAYS_THRESHOLD, String.valueOf(threshold));
        context.setScheduleConfig(new LhScheduleConfig(paramMap));
        return context;
    }

    /**
     * 构造测试SKU。
     *
     * @param materialCode 物料编码
     * @param productStatus 产品状态
     * @param constructionStage 施工阶段
     * @return SKU排程信息
     */
    private SkuScheduleDTO buildSku(String materialCode,
                                    String productStatus,
                                    String constructionStage) {
        SkuScheduleDTO sku = new SkuScheduleDTO();
        sku.setMaterialCode(materialCode);
        sku.setMaterialDesc("测试物料-" + materialCode);
        sku.setStructureName("STRUCT-1");
        sku.setProductStatus(productStatus);
        sku.setConstructionStage(constructionStage);
        sku.setWindowPlanQty(0);
        sku.setDailyPlanQuotaMap(new LinkedHashMap<LocalDate, SkuDailyPlanQuotaDTO>(4));
        return sku;
    }

    /**
     * 构造单日额度账本。
     *
     * @param productionDate 计划日期
     * @param dayPlanQty 日计划量
     * @return 日计划额度账本
     */
    private Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap(LocalDate productionDate, int dayPlanQty) {
        Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap =
                new LinkedHashMap<LocalDate, SkuDailyPlanQuotaDTO>(4);
        SkuDailyPlanQuotaDTO quota = new SkuDailyPlanQuotaDTO();
        quota.setProductionDate(productionDate);
        quota.setDayPlanQty(dayPlanQty);
        quota.setRemainingQty(dayPlanQty);
        quotaMap.put(productionDate, quota);
        return quotaMap;
    }

    /**
     * 构造指定日期日计划量的月计划。
     *
     * @param materialCode 物料编码
     * @param productStatus 产品状态
     * @param year 年
     * @param month 月
     * @param dayOfMonth 日
     * @param dayPlanQty 日计划量
     * @return 月计划记录
     */
    private FactoryMonthPlanProductionFinalResult monthPlan(String materialCode,
                                                            String productStatus,
                                                            int year,
                                                            int month,
                                                            int dayOfMonth,
                                                            int dayPlanQty) {
        FactoryMonthPlanProductionFinalResult plan = new FactoryMonthPlanProductionFinalResult();
        plan.setMaterialCode(materialCode);
        plan.setProductStatus(productStatus);
        plan.setYear(year);
        plan.setMonth(month);
        if (dayOfMonth == 3) {
            plan.setDay3(dayPlanQty);
        }
        return plan;
    }

    /**
     * 将月计划写入上下文跨月索引。
     *
     * @param context 排程上下文
     * @param plans 月计划记录
     */
    private void attachMonthPlans(LhScheduleContext context,
                                  FactoryMonthPlanProductionFinalResult... plans) {
        context.setLoadedMonthPlanList(Arrays.asList(plans));
        context.setMonthPlanByMaterialMonthMap(
                MonthPlanDateResolver.buildMaterialMonthPlanMap(context.getLoadedMonthPlanList()));
    }

    /**
     * 将业务日转换为Date。
     *
     * @param date 业务日
     * @return Date
     */
    private Date toDate(LocalDate date) {
        return Date.from(date.atStartOfDay(ZoneId.systemDefault()).toInstant());
    }
}
