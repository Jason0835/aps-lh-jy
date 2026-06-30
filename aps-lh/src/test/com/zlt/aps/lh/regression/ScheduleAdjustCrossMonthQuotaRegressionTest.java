package com.zlt.aps.lh.regression;

import com.zlt.aps.lh.api.domain.dto.SkuDailyPlanQuotaDTO;
import com.zlt.aps.lh.api.domain.entity.LhScheduleResult;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.handler.ScheduleAdjustHandler;
import com.zlt.aps.lh.component.MonthPlanDateResolver;
import com.zlt.aps.lh.service.impl.SchedulePersistenceService;
import com.zlt.aps.mp.api.domain.entity.FactoryMonthPlanProductionFinalResult;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
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
        julyPlan.setProductStatus("A");
        context.setLoadedMonthPlanList(Arrays.asList(junePlan, julyPlan));
        context.setMonthPlanByMaterialMonthMap(MonthPlanDateResolver.buildMaterialMonthPlanMap(
                context.getLoadedMonthPlanList()));

        FactoryMonthPlanProductionFinalResult resolvedPlan = ReflectionTestUtils.invokeMethod(handler,
                "resolveTargetMonthPlan", context, junePlan);

        assertNotNull(resolvedPlan);
        assertEquals("MP-07", resolvedPlan.getMonthPlanVersion());
        assertEquals("PV-07", resolvedPlan.getProductionVersion());
        assertEquals("02", resolvedPlan.getProductionType());
        assertEquals("A", resolvedPlan.getProductStatus());
    }

    @Test
    void resolveTargetMonthPlan_shouldNotMatchDifferentProductStatusAcrossMonth() {
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
        assertEquals("MP-06", resolvedPlan.getMonthPlanVersion());
        assertEquals("PV-06", resolvedPlan.getProductionVersion());
        assertEquals("01", resolvedPlan.getProductionType());
        assertEquals("A", resolvedPlan.getProductStatus());
    }

    @Test
    void adjustPreviousSchedule_shouldCarryForwardByMaterialAndProductStatus() {
        ScheduleAdjustHandler handler = new ScheduleAdjustHandler();
        LhScheduleContext context = new LhScheduleContext();
        context.setFactoryCode("116");
        context.setScheduleDate(date(2026, 6, 3));
        FactoryMonthPlanProductionFinalResult formalPlan = buildPlan("3302000005", 2026, 6);
        formalPlan.setProductStatus("S");
        formalPlan.setDay1(10);
        formalPlan.setDay2(5);
        FactoryMonthPlanProductionFinalResult trialPlan = buildPlan("3302000005", 2026, 6);
        trialPlan.setProductStatus("T");
        trialPlan.setDay1(4);
        trialPlan.setDay2(6);
        context.setMonthPlanList(Arrays.asList(formalPlan, trialPlan));
        context.setLoadedMonthPlanList(context.getMonthPlanList());
        context.setMonthPlanByMaterialMonthMap(MonthPlanDateResolver.buildMaterialMonthPlanMap(
                context.getLoadedMonthPlanList()));
        context.getMaterialMonthDailyFinishedQtyMap().put("3302000005_S_2026-06-01", 3);
        context.getMaterialMonthDailyFinishedQtyMap().put("3302000005_T_2026-06-01", 1);

        ReflectionTestUtils.invokeMethod(handler, "adjustPreviousSchedule", context);

        assertEquals(Integer.valueOf(12), context.getCarryForwardQtyMap().get("3302000005_S"));
        assertEquals(Integer.valueOf(9), context.getCarryForwardQtyMap().get("3302000005_T"));
    }

    @Test
    void fillShortageQty_shouldUseMaterialAndProductStatusKey() {
        SchedulePersistenceService service = new SchedulePersistenceService();
        LhScheduleContext context = new LhScheduleContext();
        Map<String, Integer> carryForwardQtyMap = new LinkedHashMap<>();
        carryForwardQtyMap.put("3302000006_S", 12);
        carryForwardQtyMap.put("3302000006_T", 9);
        context.setCarryForwardQtyMap(carryForwardQtyMap);
        LhScheduleResult formalResult = buildResult("3302000006", "S");
        LhScheduleResult trialResult = buildResult("3302000006", "T");
        List<LhScheduleResult> scheduleResults = new ArrayList<>();
        scheduleResults.add(formalResult);
        scheduleResults.add(trialResult);

        ReflectionTestUtils.invokeMethod(service, "fillShortageQty", context, scheduleResults);

        assertEquals(Integer.valueOf(12), formalResult.getShortageQty());
        assertEquals(Integer.valueOf(9), trialResult.getShortageQty());
    }

    @Test
    void isDeliveryLocked_shouldReadFromMonthPlanIsLockSchedule() {
        ScheduleAdjustHandler handler = new ScheduleAdjustHandler();
        LhScheduleContext context = new LhScheduleContext();
        FactoryMonthPlanProductionFinalResult plan = buildPlan("3302000004", 2026, 7);

        // isLockSchedule = 0 时返回 false
        plan.setIsLockSchedule("0");
        Boolean locked0 = ReflectionTestUtils.invokeMethod(handler, "isDeliveryLocked", context, plan);
        assertEquals(Boolean.FALSE, locked0);

        // isLockSchedule = 1 时返回 true
        plan.setIsLockSchedule("1");
        Boolean locked1 = ReflectionTestUtils.invokeMethod(handler, "isDeliveryLocked", context, plan);
        assertEquals(Boolean.TRUE, locked1);
    }

    private static FactoryMonthPlanProductionFinalResult buildPlan(String materialCode, Integer year, Integer month) {
        FactoryMonthPlanProductionFinalResult plan = new FactoryMonthPlanProductionFinalResult();
        plan.setMaterialCode(materialCode);
        plan.setYear(year);
        plan.setMonth(month);
        return plan;
    }

    private static LhScheduleResult buildResult(String materialCode, String productStatus) {
        LhScheduleResult result = new LhScheduleResult();
        result.setMaterialCode(materialCode);
        result.setProductStatus(productStatus);
        return result;
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
