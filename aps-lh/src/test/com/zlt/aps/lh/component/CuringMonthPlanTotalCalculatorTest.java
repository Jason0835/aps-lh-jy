package com.zlt.aps.lh.component;

import com.zlt.aps.lh.api.domain.dto.CuringMonthPlanTotalResult;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.mp.api.domain.entity.FactoryMonthPlanProductionFinalResult;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 硫化月计划总量计算测试。
 */
class CuringMonthPlanTotalCalculatorTest {

    @Test
    void calculate_shouldAddCurrentMonthAndCrossMonthSegmentWhenLatestWindowPlanIsCrossMonth() {
        LhScheduleContext context = new LhScheduleContext();
        FactoryMonthPlanProductionFinalResult junePlan = buildPlan("3302000001", 2026, 6);
        junePlan.setDay29(48);
        junePlan.setDay30(0);
        FactoryMonthPlanProductionFinalResult julyPlan = buildPlan("3302000001", 2026, 7);
        julyPlan.setDay1(32);
        julyPlan.setDay2(18);
        julyPlan.setDay3(0);
        context.setMonthPlanList(Arrays.asList(junePlan, julyPlan));

        CuringMonthPlanTotalResult result = CuringMonthPlanTotalCalculator.calculate(context, junePlan,
                LocalDate.of(2026, 6, 29), LocalDate.of(2026, 7, 1), 0, 0);

        assertTrue(result.isCrossMonth());
        assertEquals(LocalDate.of(2026, 7, 2), result.getBreakPointDate());
        assertEquals(48, result.getCurrentMonthPlanTotal());
        assertEquals(50, result.getCrossMonthPlanTotal());
        assertEquals(98, result.getMonthPlanTotal());
    }

    @Test
    void calculate_shouldNotUseNextMonthPlanWhenWindowHasNoPlanAndCurrentMonthHasNoFuturePlan() {
        LhScheduleContext context = new LhScheduleContext();
        FactoryMonthPlanProductionFinalResult junePlan = buildPlan("3302000002", 2026, 6);
        FactoryMonthPlanProductionFinalResult julyPlan = buildPlan("3302000002", 2026, 7);
        julyPlan.setDay1(80);
        julyPlan.setDay2(40);
        context.setMonthPlanList(Arrays.asList(junePlan, julyPlan));

        CuringMonthPlanTotalResult result = CuringMonthPlanTotalCalculator.calculate(context, junePlan,
                LocalDate.of(2026, 6, 28), LocalDate.of(2026, 6, 30), 0, 0);

        assertEquals(0, result.getMonthPlanTotal());
        assertEquals(0, result.getCrossMonthPlanTotal());
    }

    private static FactoryMonthPlanProductionFinalResult buildPlan(String materialCode, int year, int month) {
        FactoryMonthPlanProductionFinalResult plan = new FactoryMonthPlanProductionFinalResult();
        plan.setFactoryCode("116");
        plan.setMaterialCode(materialCode);
        plan.setYear(year);
        plan.setMonth(month);
        plan.setProductionVersion("PV" + year + month);
        return plan;
    }
}
