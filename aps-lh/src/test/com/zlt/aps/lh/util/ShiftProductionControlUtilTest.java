package com.zlt.aps.lh.util;

import com.zlt.aps.lh.api.domain.dto.ShiftProductionControlDTO;
import com.zlt.aps.lh.context.LhScheduleContext;
import org.junit.jupiter.api.Test;

import java.util.Calendar;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 班次管控工具回归测试。
 */
class ShiftProductionControlUtilTest {

    @Test
    void resolveEarliestSwitchStartTime_shouldDelayToOpenProductionShiftStart() {
        LhScheduleContext context = new LhScheduleContext();
        context.setOpenProductionMode(true);
        ShiftProductionControlDTO openShift = new ShiftProductionControlDTO();
        openShift.setEffectiveStartTime(dateTime(2026, 5, 8, 6, 0));
        context.setOpenProductionShift(openShift);

        Date resolved = ShiftProductionControlUtil.resolveEarliestSwitchStartTime(
                context, dateTime(2026, 5, 7, 15, 0));

        assertEquals(dateTime(2026, 5, 8, 6, 0), resolved);
    }

    @Test
    void resolveEarliestSwitchStartTime_shouldKeepReadyTimeWhenAfterOpenProductionShift() {
        LhScheduleContext context = new LhScheduleContext();
        context.setOpenProductionMode(true);
        ShiftProductionControlDTO openShift = new ShiftProductionControlDTO();
        openShift.setEffectiveStartTime(dateTime(2026, 5, 8, 6, 0));
        context.setOpenProductionShift(openShift);

        Date resolved = ShiftProductionControlUtil.resolveEarliestSwitchStartTime(
                context, dateTime(2026, 5, 8, 9, 30));

        assertEquals(dateTime(2026, 5, 8, 9, 30), resolved);
    }

    @Test
    void resolveEarliestSwitchStartTime_shouldKeepReadyTimeWhenNotOpenProductionMode() {
        LhScheduleContext context = new LhScheduleContext();
        context.setOpenProductionMode(false);

        Date resolved = ShiftProductionControlUtil.resolveEarliestSwitchStartTime(
                context, dateTime(2026, 5, 7, 15, 0));

        assertEquals(dateTime(2026, 5, 7, 15, 0), resolved);
    }

    private static Date dateTime(int year, int month, int day, int hour, int minute) {
        Calendar calendar = Calendar.getInstance();
        calendar.clear();
        calendar.set(Calendar.YEAR, year);
        calendar.set(Calendar.MONTH, month - 1);
        calendar.set(Calendar.DAY_OF_MONTH, day);
        calendar.set(Calendar.HOUR_OF_DAY, hour);
        calendar.set(Calendar.MINUTE, minute);
        return calendar.getTime();
    }
}
