package com.zlt.aps.lh.util;

import com.zlt.aps.lh.api.domain.dto.MachineCleaningWindowDTO;
import com.zlt.aps.lh.api.domain.dto.MachineMaintenanceWindowDTO;
import com.zlt.aps.lh.api.domain.entity.LhScheduleResult;
import com.zlt.aps.lh.api.domain.vo.LhShiftConfigVO;
import com.zlt.aps.lh.api.enums.CleaningTypeEnum;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.mdm.api.domain.entity.MdmDevicePlanShut;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
                Arrays.asList(firstShutdown, secondShutdown),
                buildScheduleWindowShifts());

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
                result, Collections.emptyList(), Arrays.asList(dryIceWindow, sandBlastWindow),
                Collections.emptyList(), buildScheduleWindowShifts());
        ResultDowntimeSummaryUtil.appendCleaningMouldChangeAnalysis(
                result, Arrays.asList(dryIceWindow, sandBlastWindow),
                dateTime(2026, 5, 21, 6, 0), dateTime(2026, 5, 21, 14, 0),
                buildScheduleWindowShifts());

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
                result, Collections.emptyList(), Collections.singletonList(dryIceWindow),
                Collections.emptyList(), buildScheduleWindowShifts());

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

        assertFalse(MachineCleaningOverlapUtil.excludeOverlapWindows(Collections.singletonList(sandBlastWindow),
                dateTime(2026, 5, 21, 6, 0), dateTime(2026, 5, 21, 14, 0)).isEmpty(),
                "当前逻辑按实际清洗窗口判断，清洗恰好从换模完成边界开始时不视为重叠");

        ResultDowntimeSummaryUtil.fillDowntimeSummary(
                result, Collections.emptyList(), Collections.singletonList(sandBlastWindow),
                Collections.emptyList(), buildScheduleWindowShifts());

        assertNull(ShiftFieldUtil.getShiftAnalysis(result, 1));
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
                Collections.singletonList(shutdown),
                buildScheduleWindowShifts());

        assertNull(result.getMaintenanceStartTime());
        assertNull(result.getMaintenanceEndTime());
        assertEquals(dateTime(2026, 5, 22, 8, 0), result.getCleaningStartTime());
        assertEquals(dateTime(2026, 5, 22, 9, 0), result.getCleaningEndTime());
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

    @Test
    void bindMaintenanceSummary_shouldAppendPrecisionPlanAtMaintenanceEndShift() {
        LhScheduleResult result = new LhScheduleResult();
        MachineMaintenanceWindowDTO maintenanceWindow = new MachineMaintenanceWindowDTO();
        maintenanceWindow.setMaintenanceStartTime(dateTime(2026, 5, 21, 8, 0));
        maintenanceWindow.setMaintenanceEndTime(dateTime(2026, 5, 21, 15, 0));

        boolean bound = ResultDowntimeSummaryUtil.bindMaintenanceSummaryAndAnalysis(
                result, Collections.singletonList(maintenanceWindow), buildScheduleWindowShifts());

        assertTrue(bound);
        assertEquals(dateTime(2026, 5, 21, 8, 0), result.getMaintenanceStartTime());
        assertEquals(dateTime(2026, 5, 21, 15, 0), result.getMaintenanceEndTime());
        assertEquals("精度计划", ShiftFieldUtil.getShiftAnalysis(result, 2),
                "保养结束15:00落在中班，原因分析必须备注精度计划");
    }

    /**
     * 未来保养尚未进入本批标准班次时，只保留运行态窗口和回填信息，不得污染当前排程结果。
     */
    @Test
    void bindMaintenanceSummary_shouldSkipFutureWindowOutsideScheduleShifts() {
        LhScheduleResult result = new LhScheduleResult();
        MachineMaintenanceWindowDTO maintenanceWindow = new MachineMaintenanceWindowDTO();
        maintenanceWindow.setMaintenanceStartTime(dateTime(2026, 6, 10, 8, 0));
        maintenanceWindow.setMaintenanceEndTime(dateTime(2026, 6, 10, 15, 0));

        boolean bound = ResultDowntimeSummaryUtil.bindMaintenanceSummaryAndAnalysis(
                result, Collections.singletonList(maintenanceWindow), buildScheduleWindowShifts());

        assertFalse(bound, "超出当前八班次范围的未来保养不得绑定到当前结果");
        assertNull(result.getMaintenanceStartTime());
        assertNull(result.getMaintenanceEndTime());
        for (int shiftIndex = 1; shiftIndex <= 8; shiftIndex++) {
            assertNull(ShiftFieldUtil.getShiftAnalysis(result, shiftIndex));
        }
    }

    /**
     * 最终唯一绑定前清理精度原因时，清洗等其他原因必须原样保留。
     */
    @Test
    void clearMaintenanceSummaryAndAnalysis_shouldKeepOtherShiftReasons() {
        LhScheduleResult result = new LhScheduleResult();
        result.setMaintenanceStartTime(dateTime(2026, 5, 21, 8, 0));
        result.setMaintenanceEndTime(dateTime(2026, 5, 21, 15, 0));
        ShiftFieldUtil.setShiftAnalysis(result, 2, "精度计划,干冰清洗");

        ResultDowntimeSummaryUtil.clearMaintenanceSummaryAndAnalysis(result);

        assertNull(result.getMaintenanceStartTime());
        assertNull(result.getMaintenanceEndTime());
        assertEquals("干冰清洗", ShiftFieldUtil.getShiftAnalysis(result, 2),
                "清理精度原因时不得删除其他既有原因");
    }

    private Date dateTime(int year, int month, int day, int hour, int minute) {
        Calendar calendar = Calendar.getInstance();
        calendar.set(year, month - 1, day, hour, minute, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTime();
    }

    /**
     * 构造与测试结果时间一致的标准排程班次。
     *
     * @return 2026-05-21 起始的三日排程班次
     */
    private List<LhShiftConfigVO> buildScheduleWindowShifts() {
        LhScheduleContext context = new LhScheduleContext();
        context.setScheduleDate(dateTime(2026, 5, 21, 0, 0));
        context.setScheduleTargetDate(dateTime(2026, 5, 23, 0, 0));
        return LhScheduleTimeUtil.buildDefaultScheduleShifts(context, context.getScheduleDate());
    }
}
