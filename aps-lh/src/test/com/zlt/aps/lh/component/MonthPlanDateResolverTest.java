package com.zlt.aps.lh.component;

import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.mp.api.domain.entity.FactoryMonthPlanProductionFinalResult;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 跨月月计划日期解析测试。
 */
class MonthPlanDateResolverTest {

    @Test
    void resolveDayQty_shouldReturnZeroWhenBizDateIsNull() {
        LhScheduleContext context = new LhScheduleContext();

        assertEquals(0, MonthPlanDateResolver.resolveDayQty(context, "3302000001", null));
    }

    @Test
    void resolveWindowPlanQty_shouldReadDayNByBusinessDateMonth() {
        LhScheduleContext context = new LhScheduleContext();
        FactoryMonthPlanProductionFinalResult junePlan = buildPlan("3302000001", 2026, 6);
        junePlan.setDay29(10);
        junePlan.setDay30(20);
        FactoryMonthPlanProductionFinalResult julyPlan = buildPlan("3302000001", 2026, 7);
        julyPlan.setDay1(30);
        context.setMonthPlanList(Arrays.asList(junePlan, julyPlan));

        int windowPlanQty = MonthPlanDateResolver.resolveWindowPlanQty(context, "3302000001",
                LocalDate.of(2026, 6, 29), LocalDate.of(2026, 7, 1));

        assertEquals(60, windowPlanQty);
        assertEquals(30, MonthPlanDateResolver.resolveDayQty(context, "3302000001",
                LocalDate.of(2026, 7, 1)));
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
