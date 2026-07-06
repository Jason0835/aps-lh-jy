package com.zlt.aps.lh.util;

import com.zlt.aps.lh.api.domain.dto.MachineCleaningWindowDTO;
import com.zlt.aps.lh.api.domain.dto.MachineMaintenanceWindowDTO;
import com.zlt.aps.lh.api.domain.entity.LhScheduleResult;
import com.zlt.aps.lh.api.enums.CleaningTypeEnum;
import com.zlt.aps.mdm.api.domain.entity.MdmDevicePlanShut;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ResultDowntimeSummaryUtil} 结果停机摘要回填测试。
 */
class ResultDowntimeSummaryUtilTest {

    @Test
    void fillDowntimeSummary_shouldWriteEarliestStartAndLatestEndForIntersectedWindows() {
        LhScheduleResult result = new LhScheduleResult();
        ShiftFieldUtil.setShiftPlanQty(result, 1, 16, dateTime(2026, 5, 21, 8, 0), dateTime(2026, 5, 21, 16, 0));
        result.setSpecEndTime(dateTime(2026, 5, 21, 16, 0));

        MachineMaintenanceWindowDTO maintenanceWindow = new MachineMaintenanceWindowDTO();
        maintenanceWindow.setMaintenanceStartTime(dateTime(2026, 5, 21, 7, 30));
        maintenanceWindow.setMaintenanceEndTime(dateTime(2026, 5, 21, 10, 0));

        MachineCleaningWindowDTO firstCleaningWindow = new MachineCleaningWindowDTO();
        firstCleaningWindow.setCleanStartTime(dateTime(2026, 5, 21, 6, 0));
        firstCleaningWindow.setCleanEndTime(dateTime(2026, 5, 21, 8, 30));
        MachineCleaningWindowDTO secondCleaningWindow = new MachineCleaningWindowDTO();
        secondCleaningWindow.setCleanStartTime(dateTime(2026, 5, 21, 12, 0));
        secondCleaningWindow.setCleanEndTime(dateTime(2026, 5, 21, 13, 0));

        MdmDevicePlanShut firstShutdown = new MdmDevicePlanShut();
        firstShutdown.setBeginDate(dateTime(2026, 5, 21, 9, 0));
        firstShutdown.setEndDate(dateTime(2026, 5, 21, 10, 0));
        MdmDevicePlanShut secondShutdown = new MdmDevicePlanShut();
        secondShutdown.setBeginDate(dateTime(2026, 5, 21, 14, 0));
        secondShutdown.setEndDate(dateTime(2026, 5, 21, 17, 0));

        ResultDowntimeSummaryUtil.fillDowntimeSummary(
                result,
                Collections.singletonList(maintenanceWindow),
                Arrays.asList(firstCleaningWindow, secondCleaningWindow),
                Arrays.asList(firstShutdown, secondShutdown));

        assertEquals(dateTime(2026, 5, 21, 7, 30), result.getMaintenanceStartTime());
        assertEquals(dateTime(2026, 5, 21, 10, 0), result.getMaintenanceEndTime());
        assertEquals(dateTime(2026, 5, 21, 6, 0), result.getCleaningStartTime());
        assertEquals(dateTime(2026, 5, 21, 13, 0), result.getCleaningEndTime());
        assertEquals(dateTime(2026, 5, 21, 9, 0), result.getShutdownStartTime());
        assertEquals(dateTime(2026, 5, 21, 17, 0), result.getShutdownEndTime());
    }

    /**
     * 清洗与普通换模重叠时，清洗不再额外顺延换模，但必须在实际重叠班次写入固定原因备注。
     */
    @Test
    void fillDowntimeSummary_shouldAppendCleaningMouldChangeAnalysisWhenCleaningOverlapsMouldChange() {
        LhScheduleResult result = new LhScheduleResult();
        result.setMouldChangeStartTime(dateTime(2026, 5, 21, 6, 0));
        ShiftFieldUtil.setShiftPlanQty(result, 1, 16, dateTime(2026, 5, 21, 14, 0), dateTime(2026, 5, 21, 22, 0));
        result.setSpecEndTime(dateTime(2026, 5, 21, 22, 0));

        MachineCleaningWindowDTO dryIceWindow = new MachineCleaningWindowDTO();
        dryIceWindow.setCleanType(CleaningTypeEnum.DRY_ICE.getCode());
        dryIceWindow.setCleanStartTime(dateTime(2026, 5, 21, 6, 0));
        dryIceWindow.setCleanEndTime(dateTime(2026, 5, 21, 9, 0));
        MachineCleaningWindowDTO sandBlastWindow = new MachineCleaningWindowDTO();
        sandBlastWindow.setCleanType(CleaningTypeEnum.SAND_BLAST.getCode());
        sandBlastWindow.setCleanStartTime(dateTime(2026, 5, 21, 6, 0));
        sandBlastWindow.setCleanEndTime(dateTime(2026, 5, 21, 16, 0));

        ResultDowntimeSummaryUtil.fillDowntimeSummary(
                result, Collections.emptyList(), Arrays.asList(dryIceWindow, sandBlastWindow), Collections.emptyList());

        assertEquals("干冰清洗+换模,喷砂清洗+换模", ShiftFieldUtil.getShiftAnalysis(result, 1));
    }

    /**
     * 单独清洗没有与换模、精度、维修重叠时，应在实际开始清洗的班次写入简洁原因。
     */
    @Test
    void fillDowntimeSummary_shouldAppendStandaloneCleaningAnalysisAtCleaningStartShift() {
        LhScheduleResult result = new LhScheduleResult();
        ShiftFieldUtil.setShiftPlanQty(result, 1, 16, dateTime(2026, 5, 21, 6, 0), dateTime(2026, 5, 21, 14, 0));
        result.setSpecEndTime(dateTime(2026, 5, 21, 14, 0));

        MachineCleaningWindowDTO dryIceWindow = new MachineCleaningWindowDTO();
        dryIceWindow.setCleanType(CleaningTypeEnum.DRY_ICE.getCode());
        dryIceWindow.setCleanStartTime(dateTime(2026, 5, 21, 6, 0));
        dryIceWindow.setCleanEndTime(dateTime(2026, 5, 21, 9, 0));

        ResultDowntimeSummaryUtil.fillDowntimeSummary(
                result, Collections.emptyList(), Collections.singletonList(dryIceWindow), Collections.emptyList());

        assertEquals("干冰清洗", ShiftFieldUtil.getShiftAnalysis(result, 1));
    }

    /**
     * 设备停机计划来源清洗若原始计划窗口与换模重叠，应按“清洗+换模”备注，并从产能扣减清洗列表剔除。
     */
    @Test
    void sourcePlanOverlap_shouldAppendMouldChangeAnalysisAndExcludeCapacityCleaningWindow() {
        LhScheduleResult result = new LhScheduleResult();
        result.setMouldChangeStartTime(dateTime(2026, 5, 21, 6, 0));
        ShiftFieldUtil.setShiftPlanQty(result, 1, 16, dateTime(2026, 5, 21, 14, 0), dateTime(2026, 5, 21, 22, 0));
        result.setSpecEndTime(dateTime(2026, 5, 21, 22, 0));

        MachineCleaningWindowDTO sandBlastWindow = new MachineCleaningWindowDTO();
        sandBlastWindow.setCleanType(CleaningTypeEnum.SAND_BLAST.getCode());
        sandBlastWindow.setCleanStartTime(dateTime(2026, 5, 21, 14, 0));
        sandBlastWindow.setCleanEndTime(dateTime(2026, 5, 22, 0, 0));
        sandBlastWindow.setSourcePlanStartTime(dateTime(2026, 5, 21, 10, 0));
        sandBlastWindow.setSourcePlanEndTime(dateTime(2026, 5, 21, 19, 0));

        assertTrue(MachineCleaningOverlapUtil.excludeOverlapWindows(Collections.singletonList(sandBlastWindow),
                dateTime(2026, 5, 21, 6, 0), dateTime(2026, 5, 21, 14, 0)).isEmpty(),
                "来源计划窗口与换模重叠时，不再把该清洗窗口纳入产能扣减");

        ResultDowntimeSummaryUtil.fillDowntimeSummary(
                result, Collections.emptyList(), Collections.singletonList(sandBlastWindow), Collections.emptyList());

        assertEquals("喷砂清洗+换模", ShiftFieldUtil.getShiftAnalysis(result, 1));
    }

    @Test
    void fillDowntimeSummary_shouldKeepFieldsNullWhenNoWindowIntersectsResultRange() {
        LhScheduleResult result = new LhScheduleResult();
        ShiftFieldUtil.setShiftPlanQty(result, 1, 16, dateTime(2026, 5, 21, 8, 0), dateTime(2026, 5, 21, 16, 0));
        result.setSpecEndTime(dateTime(2026, 5, 21, 16, 0));

        MachineMaintenanceWindowDTO maintenanceWindow = new MachineMaintenanceWindowDTO();
        maintenanceWindow.setMaintenanceStartTime(dateTime(2026, 5, 22, 8, 0));
        maintenanceWindow.setMaintenanceEndTime(dateTime(2026, 5, 22, 15, 0));

        MachineCleaningWindowDTO cleaningWindow = new MachineCleaningWindowDTO();
        cleaningWindow.setCleanStartTime(dateTime(2026, 5, 22, 8, 0));
        cleaningWindow.setCleanEndTime(dateTime(2026, 5, 22, 9, 0));

        MdmDevicePlanShut shutdown = new MdmDevicePlanShut();
        shutdown.setBeginDate(dateTime(2026, 5, 22, 8, 0));
        shutdown.setEndDate(dateTime(2026, 5, 22, 9, 0));

        ResultDowntimeSummaryUtil.fillDowntimeSummary(
                result,
                Collections.singletonList(maintenanceWindow),
                Collections.singletonList(cleaningWindow),
                Collections.singletonList(shutdown));

        assertNull(result.getMaintenanceStartTime());
        assertNull(result.getMaintenanceEndTime());
        assertNull(result.getCleaningStartTime());
        assertNull(result.getCleaningEndTime());
        assertNull(result.getShutdownStartTime());
        assertNull(result.getShutdownEndTime());
    }

    @Test
    void clearDowntimeSummary_shouldRemoveInheritedDowntimeFields() {
        LhScheduleResult result = new LhScheduleResult();
        result.setMaintenanceStartTime(dateTime(2026, 5, 21, 8, 0));
        result.setMaintenanceEndTime(dateTime(2026, 5, 21, 15, 0));
        result.setCleaningStartTime(dateTime(2026, 5, 21, 10, 0));
        result.setCleaningEndTime(dateTime(2026, 5, 21, 11, 0));
        result.setShutdownStartTime(dateTime(2026, 5, 21, 12, 0));
        result.setShutdownEndTime(dateTime(2026, 5, 21, 13, 0));

        ResultDowntimeSummaryUtil.clearDowntimeSummary(result);

        assertNull(result.getMaintenanceStartTime());
        assertNull(result.getMaintenanceEndTime());
        assertNull(result.getCleaningStartTime());
        assertNull(result.getCleaningEndTime());
        assertNull(result.getShutdownStartTime());
        assertNull(result.getShutdownEndTime());
    }

    private Date dateTime(int year, int month, int day, int hour, int minute) {
        Calendar calendar = Calendar.getInstance();
        calendar.set(year, month - 1, day, hour, minute, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTime();
    }
}
