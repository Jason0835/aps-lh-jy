package com.zlt.aps.lh.engine.strategy.impl;

import com.zlt.aps.lh.api.constant.LhScheduleParamConstant;
import com.zlt.aps.lh.api.domain.dto.ShiftProductionControlDTO;
import com.zlt.aps.lh.api.domain.vo.LhShiftConfigVO;
import com.zlt.aps.lh.context.LhScheduleConfig;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.util.LhScheduleTimeUtil;
import com.zlt.aps.mdm.api.domain.entity.MdmWorkCalendar;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 默认开停产策略测试。
 *
 * @author APS
 */
class DefaultProductionShutdownStrategyTest {

    private final DefaultProductionShutdownStrategy strategy = new DefaultProductionShutdownStrategy();

    @Test
    void prepareOpenStopContext_shouldDefaultShiftOpenWhenCalendarMissing() {
        LhScheduleContext context = buildContext(date(2026, 4, 22));

        strategy.prepareOpenStopContext(context);

        ShiftProductionControlDTO firstControl = context.getShiftProductionControlMap().get(1);
        assertNotNull(firstControl);
        assertTrue(firstControl.isCanSchedule());
        assertEquals(BigDecimal.ONE, firstControl.getCapacityRate());
        assertEquals(firstControl.getShiftStartTime(), firstControl.getEffectiveStartTime());
        assertEquals(firstControl.getShiftEndTime(), firstControl.getEffectiveEndTime());
    }

    @Test
    void prepareOpenStopContext_shouldApplyShiftStopAndCalendarRate() {
        LhScheduleContext context = buildContext(date(2026, 4, 22));
        context.setWorkCalendarList(Arrays.asList(calendar(2026, 4, 22, "1", "1", "0", "1", 50)));

        strategy.prepareOpenStopContext(context);

        ShiftProductionControlDTO morningControl = context.getShiftProductionControlMap().get(1);
        ShiftProductionControlDTO afternoonControl = context.getShiftProductionControlMap().get(2);
        assertFalse(morningControl.isCanSchedule());
        assertEquals("工作日历班次停产", morningControl.getUnavailableReason());
        assertTrue(afternoonControl.isCanSchedule());
        assertEquals(new BigDecimal("0.50"), afternoonControl.getCapacityRate());
    }

    @Test
    void prepareOpenStopContext_shouldMoveOpenMoldTimeFromNightToNextMorning() {
        LhScheduleContext context = buildContext(date(2026, 4, 22));
        Map<String, String> paramMap = openStopParams();
        paramMap.put(LhScheduleParamConstant.CURING_OPEN_MOLD_TIME, "2026-04-22 23:00:00");
        context.setScheduleConfig(new LhScheduleConfig(paramMap));

        strategy.prepareOpenStopContext(context);

        ShiftProductionControlDTO openShift = context.getOpenProductionShift();
        assertNotNull(openShift);
        assertEquals("02", openShift.getShiftCode());
        assertEquals(dateTime(2026, 4, 23, 6, 0, 0), openShift.getEffectiveStartTime());
        assertFalse(context.getShiftProductionControlMap().get(3).isCanSchedule());
        assertTrue(context.getShiftProductionControlMap().get(4).isCanSchedule());
    }

    @Test
    void prepareOpenStopContext_shouldCutStopPotShiftByMinute() {
        LhScheduleContext context = buildContext(date(2026, 4, 22));
        Map<String, String> paramMap = openStopParams();
        paramMap.put(LhScheduleParamConstant.CURING_STOP_POT_TIME, "2026-04-22 20:00:00");
        context.setScheduleConfig(new LhScheduleConfig(paramMap));

        strategy.prepareOpenStopContext(context);

        ShiftProductionControlDTO stopShift = context.getStopProductionShift();
        ShiftProductionControlDTO afternoonControl = context.getShiftProductionControlMap().get(2);
        ShiftProductionControlDTO nightControl = context.getShiftProductionControlMap().get(3);
        assertNotNull(stopShift);
        assertEquals("03", stopShift.getShiftCode());
        assertTrue(afternoonControl.isCanSchedule());
        assertEquals(dateTime(2026, 4, 22, 20, 0, 0), afternoonControl.getEffectiveEndTime());
        assertFalse(nightControl.isCanSchedule());
        assertEquals("停锅时间后不可排产", nightControl.getUnavailableReason());
    }

    private LhScheduleContext buildContext(java.util.Date scheduleDate) {
        LhScheduleContext context = new LhScheduleContext();
        context.setFactoryCode("116");
        context.setScheduleDate(scheduleDate);
        List<LhShiftConfigVO> shifts = LhScheduleTimeUtil.buildDefaultScheduleShifts(context, scheduleDate);
        context.setScheduleWindowShifts(shifts);
        context.setScheduleConfig(new LhScheduleConfig(new HashMap<String, String>(0)));
        return context;
    }

    private Map<String, String> openStopParams() {
        Map<String, String> paramMap = new HashMap<>(4);
        paramMap.put(LhScheduleParamConstant.ENABLE_OPEN_STOP_PRODUCTION_CONTROL, "1");
        return paramMap;
    }

    private MdmWorkCalendar calendar(int year, int month, int day, String dayFlag,
                                     String oneShiftFlag, String twoShiftFlag,
                                     String threeShiftFlag, Integer rate) {
        MdmWorkCalendar calendar = new MdmWorkCalendar();
        calendar.setProcCode("02");
        calendar.setYear(year);
        calendar.setMonth(month);
        calendar.setDay(day);
        calendar.setDayFlag(dayFlag);
        calendar.setOneShiftFlag(oneShiftFlag);
        calendar.setTwoShiftFlag(twoShiftFlag);
        calendar.setThreeShiftFlag(threeShiftFlag);
        calendar.setRate(rate);
        return calendar;
    }

    private static java.util.Date date(int year, int month, int day) {
        Calendar calendar = Calendar.getInstance();
        calendar.clear();
        calendar.set(Calendar.YEAR, year);
        calendar.set(Calendar.MONTH, month - 1);
        calendar.set(Calendar.DAY_OF_MONTH, day);
        return calendar.getTime();
    }

    private static java.util.Date dateTime(int year, int month, int day, int hour, int minute, int second) {
        Calendar calendar = Calendar.getInstance();
        calendar.clear();
        calendar.set(Calendar.YEAR, year);
        calendar.set(Calendar.MONTH, month - 1);
        calendar.set(Calendar.DAY_OF_MONTH, day);
        calendar.set(Calendar.HOUR_OF_DAY, hour);
        calendar.set(Calendar.MINUTE, minute);
        calendar.set(Calendar.SECOND, second);
        return calendar.getTime();
    }
}
