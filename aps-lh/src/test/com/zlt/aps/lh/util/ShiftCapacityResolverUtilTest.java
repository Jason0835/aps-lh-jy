package com.zlt.aps.lh.util;

import com.zlt.aps.lh.api.domain.dto.MachineCleaningWindowDTO;
import com.zlt.aps.lh.api.domain.dto.MachineMaintenanceWindowDTO;
import com.zlt.aps.lh.api.domain.vo.LhShiftConfigVO;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.mdm.api.domain.entity.MdmDevicePlanShut;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link ShiftCapacityResolverUtil} 班产与残班折算回归测试。
 */
class ShiftCapacityResolverUtilTest {

    @Test
    void resolveShiftCapacity_shouldDeductClassCapacityByAvailableTime() {
        LhShiftConfigVO morningShift = findMorningShift(date(2026, 4, 17));

        int shiftQty = ShiftCapacityResolverUtil.resolveShiftCapacity(
                morningShift, dateTime(2026, 4, 17, 7, 0), 16, 3060, 2);

        assertEquals(14, shiftQty, "有班产主数据时，残班应按有效时长比例向下折算");
    }

    @Test
    void resolveShiftCapacity_shouldFallbackToLhTimeAndMachineMouldQty() {
        LhShiftConfigVO morningShift = findMorningShift(date(2026, 4, 17));

        int partialShiftQty = ShiftCapacityResolverUtil.resolveShiftCapacity(
                morningShift, dateTime(2026, 4, 17, 7, 0), 0, 3060, 2);
        int fullShiftQty = ShiftCapacityResolverUtil.resolveShiftCapacity(
                morningShift, dateTime(2026, 4, 17, 6, 0), 0, 3060, 2);

        assertEquals(16, partialShiftQty, "无班产主数据时，应按有效时长、硫化时长和机台模台数回退计算");
        assertEquals(18, fullShiftQty, "无班产主数据时，满班应按整班时长和机台模台数回退计算");
    }

    @Test
    void plannedStopInMiddleOfShift_shouldDeductCapacityAndDelayCompletion() {
        Date shiftStart = dateTime(2026, 4, 17, 6, 0);
        Date shiftEnd = dateTime(2026, 4, 17, 14, 0);
        List<MdmDevicePlanShut> stops = Arrays.asList(
                buildStop("M1", dateTime(2026, 4, 17, 10, 0), dateTime(2026, 4, 17, 12, 0))
        );

        long netSeconds = ShiftCapacityResolverUtil.resolveNetAvailableSeconds(stops, "M1", shiftStart, shiftEnd);
        Date completionTime = ShiftCapacityResolverUtil.resolveCompletionTimeWithPlannedStops(stops, "M1", shiftStart, 6 * 3600L);

        assertEquals(6 * 3600L, netSeconds, "班次中间停机 2 小时后，净可用时长应扣减为 6 小时");
        assertEquals(shiftEnd, completionTime, "纯生产 6 小时遇到中间停机，应顺延到 14:00 完工");
    }

    @Test
    void multipleStopsWithinSameShift_shouldAccumulateDeductionAndDelayCompletion() {
        Date shiftStart = dateTime(2026, 4, 17, 6, 0);
        Date shiftEnd = dateTime(2026, 4, 17, 14, 0);
        List<MdmDevicePlanShut> stops = Arrays.asList(
                buildStop("M1", dateTime(2026, 4, 17, 7, 0), dateTime(2026, 4, 17, 8, 0)),
                buildStop("M1", dateTime(2026, 4, 17, 10, 0), dateTime(2026, 4, 17, 11, 0))
        );

        long netSeconds = ShiftCapacityResolverUtil.resolveNetAvailableSeconds(stops, "M1", shiftStart, shiftEnd);
        Date completionTime = ShiftCapacityResolverUtil.resolveCompletionTimeWithPlannedStops(stops, "M1", shiftStart, 4 * 3600L);

        assertEquals(6 * 3600L, netSeconds, "同班多段停机应累计扣减停机时长");
        assertEquals(dateTime(2026, 4, 17, 12, 0), completionTime, "纯生产 4 小时需跳过两段停机空档");
    }

    @Test
    void adjacentAndOverlappingStops_shouldBeMergedWithoutDoubleDeduction() {
        Date shiftStart = dateTime(2026, 4, 17, 6, 0);
        Date shiftEnd = dateTime(2026, 4, 17, 14, 0);
        List<MdmDevicePlanShut> stops = Arrays.asList(
                buildStop("M1", dateTime(2026, 4, 17, 8, 0), dateTime(2026, 4, 17, 9, 0)),
                buildStop("M1", dateTime(2026, 4, 17, 9, 0), dateTime(2026, 4, 17, 10, 0)),
                buildStop("M1", dateTime(2026, 4, 17, 9, 30), dateTime(2026, 4, 17, 11, 0))
        );

        long netSeconds = ShiftCapacityResolverUtil.resolveNetAvailableSeconds(stops, "M1", shiftStart, shiftEnd);
        Date completionTime = ShiftCapacityResolverUtil.resolveCompletionTimeWithPlannedStops(stops, "M1", shiftStart, 5 * 3600L);

        assertEquals(5 * 3600L, netSeconds, "相邻/重叠停机应按并集时长扣减，避免重复扣减");
        assertEquals(shiftEnd, completionTime, "纯生产 5 小时应跨越停机并在 14:00 完工");
    }

    @Test
    void stopOnShiftBoundary_shouldNotAffectShiftWindow() {
        Date shiftStart = dateTime(2026, 4, 17, 6, 0);
        Date shiftEnd = dateTime(2026, 4, 17, 14, 0);
        List<MdmDevicePlanShut> stops = Arrays.asList(
                buildStop("M1", dateTime(2026, 4, 17, 5, 0), dateTime(2026, 4, 17, 6, 0)),
                buildStop("M1", dateTime(2026, 4, 17, 14, 0), dateTime(2026, 4, 17, 15, 0))
        );

        long netSeconds = ShiftCapacityResolverUtil.resolveNetAvailableSeconds(stops, "M1", shiftStart, shiftEnd);
        Date completionTime = ShiftCapacityResolverUtil.resolveCompletionTimeWithPlannedStops(stops, "M1", shiftStart, 4 * 3600L);

        assertEquals(8 * 3600L, netSeconds, "停机恰好在班次边界时，不应扣减班次内可用时长");
        assertEquals(dateTime(2026, 4, 17, 10, 0), completionTime, "边界停机不应影响班次内完工时刻");
    }

    @Test
    void dryIceCleaningWithinShift_shouldReduceShiftQtyAndDelayCompletion() {
        Date shiftStart = dateTime(2026, 4, 21, 6, 0);
        Date shiftEnd = dateTime(2026, 4, 21, 14, 0);
        List<MachineCleaningWindowDTO> cleaningWindowList = Arrays.asList(
                buildCleaningWindow("01",
                        dateTime(2026, 4, 21, 8, 22, 22),
                        dateTime(2026, 4, 21, 11, 22, 22),
                        dateTime(2026, 4, 21, 11, 22, 22))
        );

        int shiftQty = ShiftCapacityResolverUtil.resolveShiftCapacityWithDowntime(
                null, cleaningWindowList, "K1514", shiftStart, shiftEnd, 18, 1600, 1, 8 * 3600L, 6, 3);
        Date completionTime = ShiftCapacityResolverUtil.resolveShiftPlanEndTime(
                null, cleaningWindowList, "K1514", shiftStart, shiftEnd, 12, 12);

        assertEquals(11, shiftQty, "干冰清洗落在班次内时，应按剩余 5/8 有效时间折算班产");
        assertEquals(shiftEnd, completionTime, "干冰清洗导致班次满量压缩后，11 条应在班末完工");
    }

    @Test
    void sandBlastCleaningAcrossTwoShifts_shouldReduceBothShiftCapacities() {
        Date morningShiftStart = dateTime(2026, 4, 21, 6, 0);
        Date morningShiftEnd = dateTime(2026, 4, 21, 14, 0);
        Date noonShiftStart = dateTime(2026, 4, 21, 14, 0);
        Date noonShiftEnd = dateTime(2026, 4, 21, 22, 0);
        List<MachineCleaningWindowDTO> cleaningWindowList = Arrays.asList(
                buildCleaningWindow("02",
                        dateTime(2026, 4, 21, 8, 22, 22),
                        dateTime(2026, 4, 21, 18, 22, 22),
                        dateTime(2026, 4, 21, 20, 22, 22))
        );

        int morningShiftQty = ShiftCapacityResolverUtil.resolveShiftCapacityWithDowntime(
                null, cleaningWindowList, "K1514", morningShiftStart, morningShiftEnd, 18, 1600, 1, 8 * 3600L, 6, 3);
        int noonShiftQty = ShiftCapacityResolverUtil.resolveShiftCapacityWithDowntime(
                null, cleaningWindowList, "K1514", noonShiftStart, noonShiftEnd, 18, 1600, 1, 8 * 3600L, 6, 3);

        assertEquals(6, morningShiftQty, "喷砂清洗跨班时，早班应按重叠时长折算后仅保留 6 条产能");
        assertEquals(9, noonShiftQty, "喷砂清洗跨班时，中班应继续按重叠时长折算扣减");
    }

    @Test
    void resolveShiftCapacity_shouldRoundPartialDoubleMouldClassCapacityDownToEvenMultiple() {
        int shiftQty = ShiftCapacityResolverUtil.resolveShiftCapacity(
                22, 2160, 2, 8 * 3600L, 4 * 3600L);

        assertEquals(10, shiftQty, "双模机台残班按班产主数据折算时，应向下收敛到模台数整数倍");
    }

    @Test
    void resolveShiftCapacity_shouldKeepOddFullShiftCapacityWhenShiftIsFull() {
        int shiftQty = ShiftCapacityResolverUtil.resolveShiftCapacity(
                21, 2160, 2, 8 * 3600L, 8 * 3600L);

        assertEquals(21, shiftQty, "双模机台整班班产主数据为奇数时，本轮仍应保持原值不变");
    }

    @Test
    void resolveShiftCapacityWithDowntime_shouldRoundCleaningAdjustedOddResultDownToEvenMultipleForDoubleMould() {
        Date shiftStart = dateTime(2026, 4, 21, 6, 0);
        Date shiftEnd = dateTime(2026, 4, 21, 14, 0);
        List<MachineCleaningWindowDTO> cleaningWindowList = Arrays.asList(
                buildCleaningWindow("02",
                        dateTime(2026, 4, 21, 10, 0, 0),
                        dateTime(2026, 4, 21, 14, 0, 0),
                        dateTime(2026, 4, 21, 12, 0, 0))
        );

        int shiftQty = ShiftCapacityResolverUtil.resolveShiftCapacityWithDowntime(
                null, cleaningWindowList, "K2025", shiftStart, shiftEnd, 21, 2160, 2, 8 * 3600L, 6, 3);

        assertEquals(10, shiftQty, "双模机台喷砂扣量后的残班计划量应向下收敛为偶数");
    }

    @Test
    void dryIcePartialOverlap_shouldUseFixedDurationAsLossDenominator() {
        Date shiftStart = dateTime(2026, 4, 21, 6, 0);
        Date shiftEnd = dateTime(2026, 4, 21, 14, 0);
        List<MachineCleaningWindowDTO> cleaningWindowList = Arrays.asList(
                buildCleaningWindow("01",
                        dateTime(2026, 4, 21, 12, 0, 0),
                        dateTime(2026, 4, 21, 15, 0, 0),
                        dateTime(2026, 4, 21, 15, 0, 0))
        );

        int shiftQty = ShiftCapacityResolverUtil.resolveShiftCapacityWithDowntime(
                null, cleaningWindowList, "K1514", shiftStart, shiftEnd, 18, 1600, 1, 8 * 3600L, 6, 3);

        assertEquals(13, shiftQty, "干冰仅重叠 2 小时时，应按剩余有效生产时间折算班产");
    }

    @Test
    void dryIceCleaningWithinShift_shouldReduceDoubleMouldShiftQtyByRemainingTimeRatio() {
        Date shiftStart = dateTime(2026, 4, 22, 14, 0);
        Date shiftEnd = dateTime(2026, 4, 22, 22, 0);
        List<MachineCleaningWindowDTO> cleaningWindowList = Arrays.asList(
                buildCleaningWindow("01",
                        dateTime(2026, 4, 22, 15, 0, 0),
                        dateTime(2026, 4, 22, 18, 0, 0),
                        dateTime(2026, 4, 22, 18, 0, 0))
        );

        int shiftQty = ShiftCapacityResolverUtil.resolveShiftCapacityWithDowntime(
                null, cleaningWindowList, "K1110", shiftStart, shiftEnd, 22, 2160, 2, 8 * 3600L, 6, 3);
        Date completionTime = ShiftCapacityResolverUtil.resolveShiftPlanEndTime(
                null, cleaningWindowList, "K1110", shiftStart, shiftEnd, 12, 12);

        assertEquals(12, shiftQty, "双模机台命中 3 小时干冰时，应按剩余 5/8 时间折算到 12");
        assertEquals(shiftEnd, completionTime, "按剩余时间折算后的 12 条应在班末完工");
    }

    @Test
    void maintenanceWindowWithinShift_shouldReduceShiftQtyAndDelayCompletion() {
        Date shiftStart = dateTime(2026, 4, 22, 14, 0);
        Date shiftEnd = dateTime(2026, 4, 22, 22, 0);
        List<MachineMaintenanceWindowDTO> maintenanceWindowList = Arrays.asList(
                buildMaintenanceWindow("K1110",
                        dateTime(2026, 4, 22, 14, 0, 0),
                        dateTime(2026, 4, 22, 15, 0, 0))
        );

        int shiftQty = ShiftCapacityResolverUtil.resolveShiftCapacityWithDowntime(
                null, null, maintenanceWindowList, "K1110", shiftStart, shiftEnd, 22, 2160, 2, 8 * 3600L, 6, 3);
        Date completionTime = ShiftCapacityResolverUtil.resolveShiftPlanEndTime(
                null, null, maintenanceWindowList, "K1110", shiftStart, shiftEnd, 18, 18);

        assertEquals(18, shiftQty, "保养占用中班 1 小时后，应按剩余 7/8 时间折算班产并收敛到双模偶数");
        assertEquals(shiftEnd, completionTime, "保养窗口应进入时间轴，生产完工需跳过保养占用时段");
    }

    private LhShiftConfigVO findMorningShift(Date scheduleDate) {
        LhScheduleContext context = new LhScheduleContext();
        List<LhShiftConfigVO> shifts = LhScheduleTimeUtil.buildDefaultScheduleShifts(context, scheduleDate);
        for (LhShiftConfigVO shift : shifts) {
            if (shift.getShiftStartDateTime() != null && getHour(shift.getShiftStartDateTime()) == 6) {
                return shift;
            }
        }
        throw new IllegalStateException("未找到 06:00 开始的早班");
    }

    private int getHour(Date date) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(date);
        return calendar.get(Calendar.HOUR_OF_DAY);
    }

    private static Date date(int year, int month, int day) {
        Calendar calendar = Calendar.getInstance();
        calendar.clear();
        calendar.set(Calendar.YEAR, year);
        calendar.set(Calendar.MONTH, month - 1);
        calendar.set(Calendar.DAY_OF_MONTH, day);
        return calendar.getTime();
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

    private static Date dateTime(int year, int month, int day, int hour, int minute, int second) {
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

    private static MdmDevicePlanShut buildStop(String machineCode, Date beginDate, Date endDate) {
        MdmDevicePlanShut stop = new MdmDevicePlanShut();
        stop.setMachineCode(machineCode);
        stop.setBeginDate(beginDate);
        stop.setEndDate(endDate);
        return stop;
    }

    private static MachineCleaningWindowDTO buildCleaningWindow(String cleanType, Date cleanStartTime,
                                                                Date cleanEndTime, Date readyTime) {
        MachineCleaningWindowDTO cleaningWindow = new MachineCleaningWindowDTO();
        cleaningWindow.setCleanType(cleanType);
        cleaningWindow.setLeftRightMould("LR");
        cleaningWindow.setCleanStartTime(cleanStartTime);
        cleaningWindow.setCleanEndTime(cleanEndTime);
        cleaningWindow.setReadyTime(readyTime);
        return cleaningWindow;
    }

    private static MachineMaintenanceWindowDTO buildMaintenanceWindow(String machineCode, Date startTime, Date endTime) {
        MachineMaintenanceWindowDTO maintenanceWindow = new MachineMaintenanceWindowDTO();
        maintenanceWindow.setMachineCode(machineCode);
        maintenanceWindow.setMaintenanceStartTime(startTime);
        maintenanceWindow.setMaintenanceEndTime(endTime);
        return maintenanceWindow;
    }
}
