package com.zlt.aps.lh.regression;

import com.zlt.aps.lh.api.domain.dto.SkuDailyPlanQuotaDTO;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.handler.ScheduleAdjustHandler;
import com.zlt.aps.lh.component.MonthPlanDateResolver;
import com.zlt.aps.mp.api.domain.entity.FactoryMonthPlanProductionFinalResult;
import com.zlt.aps.mp.api.domain.entity.MpAdjustResult;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * S4.3 跨月日计划账本回归测试。
 */
class ScheduleAdjustCrossMonthQuotaRegressionTest {

    @Test
    void buildDailyPlanQuotaMap_shouldResolveCrossMonthDayQtyByBusinessDate() {
        ScheduleAdjustHandler handler = new ScheduleAdjustHandler();
        LhScheduleContext context = new LhScheduleContext();
        context.setScheduleDate(date(2026, 6, 29));
        context.setWindowEndDate(date(2026, 7, 1));
        FactoryMonthPlanProductionFinalResult junePlan = buildPlan("3302000001", 2026, 6);
        junePlan.setDay29(10);
        junePlan.setDay30(20);
        FactoryMonthPlanProductionFinalResult julyPlan = buildPlan("3302000001", 2026, 7);
        julyPlan.setDay1(30);
        context.setLoadedMonthPlanList(Arrays.asList(junePlan, julyPlan));
        context.setMonthPlanByMaterialMonthMap(MonthPlanDateResolver.buildMaterialMonthPlanMap(
                context.getLoadedMonthPlanList()));

        Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap = ReflectionTestUtils.invokeMethod(handler,
                "buildDailyPlanQuotaMap", context, junePlan, "3302000001");

        assertEquals(3, quotaMap.size());
        assertEquals(10, quotaMap.get(LocalDate.of(2026, 6, 29)).getDayPlanQty());
        assertEquals(20, quotaMap.get(LocalDate.of(2026, 6, 30)).getDayPlanQty());
        assertEquals(30, quotaMap.get(LocalDate.of(2026, 7, 1)).getDayPlanQty());
    }

    @Test
    void resolveFutureMonthPlanQtyAfterWindow_shouldStopAtLoadedMonthCoverage() {
        ScheduleAdjustHandler handler = new ScheduleAdjustHandler();
        LhScheduleContext context = new LhScheduleContext();
        context.setScheduleDate(date(2026, 6, 29));
        context.setWindowEndDate(date(2026, 7, 30));

        FactoryMonthPlanProductionFinalResult julyPlan = buildPlan("3302000002", 2026, 7);
        julyPlan.setDay31(9);
        FactoryMonthPlanProductionFinalResult fallbackPlan = buildPlan("3302000002", null, null);
        fallbackPlan.setDay1(100);
        context.setLoadedMonthPlanList(Arrays.asList(julyPlan, fallbackPlan));
        context.setMonthPlanByMaterialMonthMap(MonthPlanDateResolver.buildMaterialMonthPlanMap(
                context.getLoadedMonthPlanList()));

        Integer futureQty = ReflectionTestUtils.invokeMethod(handler,
                "resolveFutureMonthPlanQtyAfterWindow", context, julyPlan);

        assertEquals(9, futureQty);
    }

    @Test
    void resolveTargetMonthPlan_shouldPreferScheduleTargetDateMonth() {
        ScheduleAdjustHandler handler = new ScheduleAdjustHandler();
        LhScheduleContext context = new LhScheduleContext();
        context.setScheduleTargetDate(date(2026, 7, 1));
        FactoryMonthPlanProductionFinalResult junePlan = buildPlan("3302000003", 2026, 6);
        junePlan.setMonthPlanVersion("MP-06");
        junePlan.setProductionVersion("PV-06");
        junePlan.setProductionType("01");
        junePlan.setProductStatus("A");
        FactoryMonthPlanProductionFinalResult julyPlan = buildPlan("3302000003", 2026, 7);
        julyPlan.setMonthPlanVersion("MP-07");
        julyPlan.setProductionVersion("PV-07");
        julyPlan.setProductionType("02");
        julyPlan.setProductStatus("B");
        context.setLoadedMonthPlanList(Arrays.asList(junePlan, julyPlan));
        context.setMonthPlanByMaterialMonthMap(MonthPlanDateResolver.buildMaterialMonthPlanMap(
                context.getLoadedMonthPlanList()));

        FactoryMonthPlanProductionFinalResult resolvedPlan = ReflectionTestUtils.invokeMethod(handler,
                "resolveTargetMonthPlan", context, junePlan);

        assertNotNull(resolvedPlan);
        assertEquals("MP-07", resolvedPlan.getMonthPlanVersion());
        assertEquals("PV-07", resolvedPlan.getProductionVersion());
        assertEquals("02", resolvedPlan.getProductionType());
        assertEquals("B", resolvedPlan.getProductStatus());
    }

    @Test
    void isDeliveryLocked_shouldOnlyUseTargetMonthAdjustResult() {
        ScheduleAdjustHandler handler = new ScheduleAdjustHandler();
        LhScheduleContext context = new LhScheduleContext();
        FactoryMonthPlanProductionFinalResult julyPlan = buildPlan("3302000004", 2026, 7);
        MpAdjustResult juneAdjustResult = buildAdjustResult("3302000004", 2026, 6, "1");
        MpAdjustResult julyAdjustResult = buildAdjustResult("3302000004", 2026, 7, "0");
        context.getMpAdjustResultMap().put("3302000004", Arrays.asList(juneAdjustResult, julyAdjustResult));

        Boolean locked = ReflectionTestUtils.invokeMethod(handler, "isDeliveryLocked", context, julyPlan);

        assertEquals(Boolean.FALSE, locked);
    }

    private static FactoryMonthPlanProductionFinalResult buildPlan(String materialCode, Integer year, Integer month) {
        FactoryMonthPlanProductionFinalResult plan = new FactoryMonthPlanProductionFinalResult();
        plan.setMaterialCode(materialCode);
        plan.setYear(year);
        plan.setMonth(month);
        return plan;
    }

    private static MpAdjustResult buildAdjustResult(String materialCode, int year, int month, String isLockSchedule) {
        MpAdjustResult adjustResult = new MpAdjustResult();
        adjustResult.setMaterialCode(materialCode);
        adjustResult.setYear(year);
        adjustResult.setMonth(month);
        adjustResult.setIsLockSchedule(isLockSchedule);
        return adjustResult;
    }

    private static Date date(int year, int month, int day) {
        Calendar calendar = Calendar.getInstance();
        calendar.clear();
        calendar.set(Calendar.YEAR, year);
        calendar.set(Calendar.MONTH, month - 1);
        calendar.set(Calendar.DAY_OF_MONTH, day);
        return calendar.getTime();
    }
}
