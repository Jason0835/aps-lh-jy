package com.zlt.aps.lh.regression;

import com.zlt.aps.lh.api.constant.LhScheduleParamConstant;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.engine.strategy.impl.DefaultFirstInspectionBalanceStrategy;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 首检每班上限回归测试：支持 -1 或缺省不限制，且保留有限额行为。
 */
class DefaultFirstInspectionBalanceStrategyRegressionTest {

    private final DefaultFirstInspectionBalanceStrategy strategy = new DefaultFirstInspectionBalanceStrategy();

    @Test
    void allocateInspection_shouldNotLimitWhenParamIsMinusOne() {
        LhScheduleContext context = newContext("-1");
        Date morningTime = dateTime(2026, 4, 11, 6, 0);
        for (int i = 0; i < 6; i++) {
            Date allocated = strategy.allocateInspection(context, "M1", morningTime);
            assertNotNull(allocated, "参数为-1时不应触发每班上限");
            assertEquals(morningTime, allocated);
        }
    }

    @Test
    void allocateInspection_shouldUseDefaultUnlimitedWhenParamMissing() {
        LhScheduleContext context = newContext(null);
        Date morningTime = dateTime(2026, 4, 11, 6, 0);
        for (int i = 0; i < 6; i++) {
            Date allocated = strategy.allocateInspection(context, "M1", morningTime);
            assertNotNull(allocated, "参数缺省时应按默认值-1处理为不限量");
            assertEquals(morningTime, allocated);
        }
    }

    @Test
    void allocateInspection_shouldKeepLimitWhenParamIsFive() {
        LhScheduleContext context = newContext("5");
        Date morningTime = dateTime(2026, 4, 11, 6, 0);
        for (int i = 0; i < 5; i++) {
            Date allocated = strategy.allocateInspection(context, "M1", morningTime);
            assertEquals(morningTime, allocated, "前5次应分配在早班");
        }
        Date sixth = strategy.allocateInspection(context, "M1", morningTime);
        assertEquals(dateTime(2026, 4, 11, 14, 0), sixth, "第6次应顺延到当天中班");
    }

    @Test
    void allocateInspection_shouldRejectWhenParamIsZero() {
        LhScheduleContext context = newContext("0");
        Date morningTime = dateTime(2026, 4, 11, 6, 0);
        Date allocated = strategy.allocateInspection(context, "M1", morningTime);
        assertNull(allocated, "参数为0时每班不可分配首检");
    }

    @Test
    void allocateInspection_shouldAllowNightWithoutConsumingMorningAfternoonQuota() {
        LhScheduleContext context = newContext("1");
        Date nightTime = dateTime(2026, 4, 11, 22, 30);
        for (int i = 0; i < 3; i++) {
            Date allocated = strategy.allocateInspection(context, "M1", nightTime);
            assertEquals(nightTime, allocated, "夜班首检应允许分配");
        }

        Date morningTime = dateTime(2026, 4, 11, 6, 0);
        Date firstMorning = strategy.allocateInspection(context, "M1", morningTime);
        assertEquals(morningTime, firstMorning, "夜班首检不应占用早班上限");

        Date secondMorning = strategy.allocateInspection(context, "M1", morningTime);
        assertEquals(dateTime(2026, 4, 11, 14, 0), secondMorning, "早班达到上限后应顺延到中班");
    }

    @Test
    void allocateInspection_shouldKeepAfternoonWhenMouldChangeCompletesAtNoChangeBoundary() {
        LhScheduleContext context = newContext("-1");
        Date afternoonBoundaryTime = dateTime(2026, 4, 11, 20, 0);

        Date allocated = strategy.allocateInspection(context, "K1516", afternoonBoundaryTime);

        assertEquals(afternoonBoundaryTime, allocated,
                "普通换模完成正好落在20点边界时，首检数量应归属中班，不应顺延到次日早班");
    }

    private LhScheduleContext newContext(String maxFirstInspectionPerShift) {
        LhScheduleContext context = new LhScheduleContext();
        if (maxFirstInspectionPerShift != null) {
            Map<String, String> lhParamsMap = new HashMap<>(4);
            lhParamsMap.put(LhScheduleParamConstant.MAX_FIRST_INSPECTION_PER_SHIFT, maxFirstInspectionPerShift);
            context.setLhParamsMap(lhParamsMap);
        }
        return context;
    }

    private static Date dateTime(int year, int month, int day, int hour, int minute) {
        java.util.Calendar calendar = java.util.Calendar.getInstance();
        calendar.clear();
        calendar.set(java.util.Calendar.YEAR, year);
        calendar.set(java.util.Calendar.MONTH, month - 1);
        calendar.set(java.util.Calendar.DAY_OF_MONTH, day);
        calendar.set(java.util.Calendar.HOUR_OF_DAY, hour);
        calendar.set(java.util.Calendar.MINUTE, minute);
        return calendar.getTime();
    }
}
