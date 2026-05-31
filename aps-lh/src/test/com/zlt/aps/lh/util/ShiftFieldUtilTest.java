package com.zlt.aps.lh.util;

import com.zlt.aps.lh.api.domain.entity.LhScheduleResult;
import com.zlt.aps.lh.api.domain.vo.LhShiftConfigVO;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link ShiftFieldUtil} BeanUtil 读写与越界行为
 */
class ShiftFieldUtilTest {

    @Test
    void setAndGet_planQty_roundTrip() {
        LhScheduleResult r = new LhScheduleResult();
        Date s = new Date(1L);
        Date e = new Date(2L);
        ShiftFieldUtil.setShiftPlanQty(r, 1, 10, s, e);
        assertEquals(10, ShiftFieldUtil.getShiftPlanQty(r, 1));
        assertEquals(s, ShiftFieldUtil.getShiftStartTime(r, 1));
        assertEquals(e, ShiftFieldUtil.getShiftEndTime(r, 1));
    }

    @Test
    void setAndGet_slot8() {
        LhScheduleResult r = new LhScheduleResult();
        ShiftFieldUtil.setShiftPlanQty(r, 8, 3, null, null);
        assertEquals(3, ShiftFieldUtil.getShiftPlanQty(r, 8));
    }

    @Test
    void invalidIndex_returnsNull() {
        LhScheduleResult r = new LhScheduleResult();
        assertNull(ShiftFieldUtil.getShiftPlanQty(r, 0));
        assertNull(ShiftFieldUtil.getShiftPlanQty(r, 9));
    }

    @Test
    void clearUnplannedShiftCureFormulaFields_shouldOnlyClearShiftWithoutPlanQty() {
        LhScheduleResult result = buildResult(10, 0, 5, 0, 0, 0, 0, 0);
        result.setClass1LhNo("LH-1");
        result.setClass1LhType("S");
        result.setClass2LhNo("LH-2");
        result.setClass2LhType("S");
        result.setClass3LhNo("LH-3");
        result.setClass3LhType("T");

        ShiftFieldUtil.clearUnplannedShiftCureFormulaFields(result);

        assertEquals("LH-1", result.getClass1LhNo());
        assertEquals("S", result.getClass1LhType());
        assertNull(result.getClass2LhNo());
        assertNull(result.getClass2LhType());
        assertEquals("LH-3", result.getClass3LhNo());
        assertEquals("T", result.getClass3LhType());
        assertNull(result.getClass4LhNo());
        assertNull(result.getClass4LhType());
    }

    @Test
    void shouldPreserveSingleResultTotalWhenScalingShiftPlanQty() {
        LhScheduleResult result = buildResult(16, 16, 16, 16, 16, 16, 16, 16);

        ShiftFieldUtil.scaleGroupedShiftPlanQty(Arrays.asList(result), buildShifts(8), 15);

        assertEquals(15, ShiftFieldUtil.resolveScheduledQty(result));
        assertEquals(2, ShiftFieldUtil.getShiftPlanQty(result, 1));
        assertEquals(1, ShiftFieldUtil.getShiftPlanQty(result, 8));
    }

    @Test
    void shouldPreserveGroupedTotalWhenScalingMultipleResults() {
        LhScheduleResult firstResult = buildResult(16, 16, 16, 16, 0, 0, 0, 0);
        LhScheduleResult secondResult = buildResult(0, 0, 0, 0, 16, 16, 16, 16);

        ShiftFieldUtil.scaleGroupedShiftPlanQty(
                Arrays.asList(firstResult, secondResult), buildShifts(8), 15);

        assertEquals(15,
                ShiftFieldUtil.resolveScheduledQty(firstResult) + ShiftFieldUtil.resolveScheduledQty(secondResult));
        assertEquals(8, ShiftFieldUtil.resolveScheduledQty(firstResult));
        assertEquals(7, ShiftFieldUtil.resolveScheduledQty(secondResult));
    }

    private List<LhShiftConfigVO> buildShifts(int count) {
        List<LhShiftConfigVO> shifts = new ArrayList<>(count);
        for (int i = 1; i <= count; i++) {
            LhShiftConfigVO shift = new LhShiftConfigVO();
            shift.setShiftIndex(i);
            shifts.add(shift);
        }
        return shifts;
    }

    private LhScheduleResult buildResult(int class1PlanQty,
                                         int class2PlanQty,
                                         int class3PlanQty,
                                         int class4PlanQty,
                                         int class5PlanQty,
                                         int class6PlanQty,
                                         int class7PlanQty,
                                         int class8PlanQty) {
        LhScheduleResult result = new LhScheduleResult();
        result.setClass1PlanQty(class1PlanQty);
        result.setClass2PlanQty(class2PlanQty);
        result.setClass3PlanQty(class3PlanQty);
        result.setClass4PlanQty(class4PlanQty);
        result.setClass5PlanQty(class5PlanQty);
        result.setClass6PlanQty(class6PlanQty);
        result.setClass7PlanQty(class7PlanQty);
        result.setClass8PlanQty(class8PlanQty);
        return result;
    }
}
