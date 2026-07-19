package com.zlt.aps.lh.util;

import com.zlt.aps.lh.api.domain.dto.MachineCleaningWindowDTO;
import com.zlt.aps.lh.api.domain.dto.MachineMaintenanceWindowDTO;
import com.zlt.aps.lh.api.domain.vo.LhShiftConfigVO;
import com.zlt.aps.lh.api.enums.ScheduleTypeEnum;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.mdm.api.domain.entity.MdmDevicePlanShut;
import com.zlt.aps.mdm.api.domain.entity.MdmSkuLhCapacity;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void plannedRepairStartShift_shouldKeepConfiguredFixedPlanQty() {
        Date shiftStart = dateTime(2026, 7, 8, 6, 0);
        Date shiftEnd = dateTime(2026, 7, 8, 14, 0);
        MdmDevicePlanShut plannedRepair = buildStop(
                "K1110", dateTime(2026, 7, 8, 8, 0), dateTime(2026, 7, 8, 16, 0));
        plannedRepair.setMachineStopType("05");

        int shiftQty = ShiftCapacityResolverUtil.resolveShiftCapacityWithDowntime(
                Arrays.asList(plannedRepair), null, "K1110", shiftStart, shiftEnd,
                22, 2160, 2, 8 * 3600L, 6, 3, 2);

        assertEquals(2, shiftQty, "05维修开始班次仍应按SYS0308010配置的固定量排产");
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
                null, cleaningWindowList, "K1514", shiftStart, shiftEnd, 18, 1600, 1, 8 * 3600L, 6, 3, 0);
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
                null, cleaningWindowList, "K1514", morningShiftStart, morningShiftEnd, 18, 1600, 1, 8 * 3600L, 6, 3, 0);
        int noonShiftQty = ShiftCapacityResolverUtil.resolveShiftCapacityWithDowntime(
                null, cleaningWindowList, "K1514", noonShiftStart, noonShiftEnd, 18, 1600, 1, 8 * 3600L, 6, 3, 0);

        assertEquals(6, morningShiftQty, "喷砂清洗跨班时，早班应按重叠时长折算后仅保留 6 条产能");
        assertEquals(9, noonShiftQty, "喷砂清洗跨班时，中班应继续按重叠时长折算扣减");
    }

    @Test
    void resolveShiftCapacity_shouldRoundPartialDoubleMouldClassCapacityDownToEvenMultiple() {
        int shiftQty = ShiftCapacityResolverUtil.resolveShiftCapacity(
                22, 2160, 2, 8 * 3600L, 4 * 3600L);

        assertEquals(12, shiftQty, "双模机台残班按班产主数据折算时，应向上收敛到模台数整数倍");
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
                null, cleaningWindowList, "K2025", shiftStart, shiftEnd, 21, 2160, 2, 8 * 3600L, 6, 3, 0);

        assertEquals(12, shiftQty, "双模机台喷砂扣量后的残班计划量应向上收敛为偶数");
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
                null, cleaningWindowList, "K1514", shiftStart, shiftEnd, 18, 1600, 1, 8 * 3600L, 6, 3, 0);

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
                null, cleaningWindowList, "K1110", shiftStart, shiftEnd, 22, 2160, 2, 8 * 3600L, 6, 3, 0);
        Date completionTime = ShiftCapacityResolverUtil.resolveShiftPlanEndTime(
                null, cleaningWindowList, "K1110", shiftStart, shiftEnd, 14, 13);

        assertEquals(14, shiftQty, "双模机台命中 3 小时干冰时，应按剩余 5/8 时间折算到 14");
        assertEquals(dateTime(2026, 4, 22, 22, 23, 5), completionTime,
                "向上收敛到 14 条跨过干冰窗口，完工时间超出班末");
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
                null, null, maintenanceWindowList, "K1110", shiftStart, shiftEnd, 22, 2160, 2, 8 * 3600L, 6, 3, 0);
        Date completionTime = ShiftCapacityResolverUtil.resolveShiftPlanEndTime(
                null, null, maintenanceWindowList, "K1110", shiftStart, shiftEnd, 20, 19);

        assertEquals(20, shiftQty, "保养占用中班 1 小时后，应按剩余 7/8 时间折算班产并向上收敛到双模偶数");
        assertEquals(dateTime(2026, 4, 22, 22, 22, 7), completionTime,
                "向上收敛到 20 条需跨过保养窗口，完工时间超出班末");
    }

    @Test
    void maintenancePreheatWindow_shouldBlockProductionUntilSeventeenThirty() {
        MachineMaintenanceWindowDTO maintenanceWindow = buildMaintenanceWindow("K1110",
                dateTime(2026, 4, 22, 8, 0), dateTime(2026, 4, 22, 15, 0));
        maintenanceWindow.setProductionResumeTime(dateTime(2026, 4, 22, 17, 30));

        int preheatWindowQty = ShiftCapacityResolverUtil.resolveShiftCapacityWithDowntime(
                null, null, Arrays.asList(maintenanceWindow), "K1110",
                dateTime(2026, 4, 22, 15, 0), dateTime(2026, 4, 22, 17, 30),
                22, 2160, 2, 8 * 3600L, 6, 3, 0);
        int afterPreheatQty = ShiftCapacityResolverUtil.resolveShiftCapacityWithDowntime(
                null, null, Arrays.asList(maintenanceWindow), "K1110",
                dateTime(2026, 4, 22, 17, 30), dateTime(2026, 4, 22, 22, 0),
                22, 2160, 2, 8 * 3600L, 6, 3, 0);

        assertEquals(0, preheatWindowQty, "15:00～17:30胶囊预热期间产能必须为0");
        assertTrue(afterPreheatQty > 0, "17:30胶囊预热完成后机台才允许恢复生产");
    }

    @Test
    void maintenanceAndPlannedStopOverlap_shouldDeductMergedUnionOnlyOnce() {
        MachineMaintenanceWindowDTO maintenanceWindow = buildMaintenanceWindow("K1110",
                dateTime(2026, 4, 22, 8, 0), dateTime(2026, 4, 22, 15, 0));
        maintenanceWindow.setProductionResumeTime(dateTime(2026, 4, 22, 17, 30));
        MdmDevicePlanShut plannedStop = buildStop("K1110",
                dateTime(2026, 4, 22, 14, 0), dateTime(2026, 4, 22, 18, 0));

        long overlapSeconds = ShiftCapacityResolverUtil.resolveDowntimeOverlapSeconds(
                Arrays.asList(plannedStop), null, Arrays.asList(maintenanceWindow), "K1110",
                dateTime(2026, 4, 22, 6, 0), dateTime(2026, 4, 22, 22, 0));

        assertEquals(10 * 3600L, overlapSeconds,
                "保养预热08:00～17:30与停机14:00～18:00应按08:00～18:00并集扣减，不能重复扣减");
    }

    @Test
    void resolveActualShiftPlanQty_shouldKeepOriginalWhenConfigMissingInvalidEvenOrSceneNotMatched() {
        LhShiftConfigVO morningShift = findMorningShift(date(2026, 4, 17));

        assertEquals(17, ShiftCapacityResolverUtil.resolveActualShiftPlanQty(
                17, morningShift, null, ScheduleTypeEnum.NEW_SPEC.getCode()));
        assertEquals(17, ShiftCapacityResolverUtil.resolveActualShiftPlanQty(
                17, morningShift, "", ScheduleTypeEnum.NEW_SPEC.getCode()));
        assertEquals(17, ShiftCapacityResolverUtil.resolveActualShiftPlanQty(
                17, morningShift, "9", ScheduleTypeEnum.NEW_SPEC.getCode()));
        assertEquals(18, ShiftCapacityResolverUtil.resolveActualShiftPlanQty(
                18, morningShift, "2", ScheduleTypeEnum.NEW_SPEC.getCode()));
        assertEquals(17, ShiftCapacityResolverUtil.resolveActualShiftPlanQty(
                17, morningShift, "2", "99"));
    }

    @Test
    void resolveActualShiftPlanQty_shouldAdjustMorningShiftWhenConfigIsTwo() {
        List<LhShiftConfigVO> shifts = LhScheduleTimeUtil.buildDefaultScheduleShifts(
                new LhScheduleContext(), date(2026, 4, 17));

        assertEquals(18, actualQty(shifts, 1, "2"));
        assertEquals(16, actualQty(shifts, 2, "2"));
        assertEquals(16, actualQty(shifts, 3, "2"));
        assertEquals(18, actualQty(shifts, 4, "2"));
        assertEquals(16, actualQty(shifts, 5, "2"));
        assertEquals(16, actualQty(shifts, 6, "2"));
        assertEquals(18, actualQty(shifts, 7, "2"));
        assertEquals(16, actualQty(shifts, 8, "2"));
    }

    @Test
    void resolveActualShiftPlanQty_shouldAdjustAfternoonShiftWhenConfigIsThree() {
        List<LhShiftConfigVO> shifts = LhScheduleTimeUtil.buildDefaultScheduleShifts(
                new LhScheduleContext(), date(2026, 4, 17));

        assertEquals(16, actualQty(shifts, 1, "3"));
        assertEquals(18, actualQty(shifts, 2, "3"));
        assertEquals(16, actualQty(shifts, 3, "3"));
        assertEquals(16, actualQty(shifts, 4, "3"));
        assertEquals(18, actualQty(shifts, 5, "3"));
        assertEquals(16, actualQty(shifts, 6, "3"));
        assertEquals(16, actualQty(shifts, 7, "3"));
        assertEquals(18, actualQty(shifts, 8, "3"));
    }

    @Test
    void resolveActualShiftPlanQty_shouldAdjustNightShiftWhenConfigIsOne() {
        List<LhShiftConfigVO> shifts = LhScheduleTimeUtil.buildDefaultScheduleShifts(
                new LhScheduleContext(), date(2026, 4, 17));

        assertEquals(16, actualQty(shifts, 1, "1"));
        assertEquals(16, actualQty(shifts, 2, "1"));
        assertEquals(18, actualQty(shifts, 3, "1"));
        assertEquals(16, actualQty(shifts, 4, "1"));
        assertEquals(16, actualQty(shifts, 5, "1"));
        assertEquals(18, actualQty(shifts, 6, "1"));
        assertEquals(16, actualQty(shifts, 7, "1"));
        assertEquals(16, actualQty(shifts, 8, "1"));
    }

    @Test
    void calculateShiftPlanQtyByDailyStandard_shouldPutRemainderOnDefaultAfternoonShift() {
        List<LhShiftConfigVO> shifts = LhScheduleTimeUtil.buildDefaultScheduleShifts(
                new LhScheduleContext(), date(2026, 4, 17));
        Map<Integer, Integer> sameDayPlanQtyMap = sameDayPlanQtyMap(shifts, 1, 16, 2, 16, 3, 16);

        int morningQty = ShiftCapacityResolverUtil.calculateShiftPlanQtyByDailyStandard(
                46, 16, 16, shifts.get(0), "3", sameDayPlanQtyMap, false,
                ScheduleTypeEnum.NEW_SPEC.getCode());
        int nightQty = ShiftCapacityResolverUtil.calculateShiftPlanQtyByDailyStandard(
                46, 16, 16, shifts.get(2), "3", sameDayPlanQtyMap, false,
                ScheduleTypeEnum.NEW_SPEC.getCode());
        int afternoonQty = ShiftCapacityResolverUtil.calculateShiftPlanQtyByDailyStandard(
                46, 16, 16, shifts.get(1), "3", sameDayPlanQtyMap, false,
                ScheduleTypeEnum.NEW_SPEC.getCode());

        assertEquals(16, morningQty, "早班不是剩余班次，应保持班产上限");
        assertEquals(16, nightQty, "晚班不是剩余班次，应保持班产上限");
        assertEquals(14, afternoonQty, "中班为剩余班次时，应承接日标准产量余量");
    }

    @Test
    void calculateShiftPlanQtyByDailyStandard_shouldSupportNightAndMorningRemainShift() {
        List<LhShiftConfigVO> shifts = LhScheduleTimeUtil.buildDefaultScheduleShifts(
                new LhScheduleContext(), date(2026, 4, 17));
        Map<Integer, Integer> sameDayPlanQtyMap = sameDayPlanQtyMap(shifts, 1, 16, 2, 16, 3, 16);

        int nightQty = ShiftCapacityResolverUtil.calculateShiftPlanQtyByDailyStandard(
                46, 16, 16, shifts.get(2), "1", sameDayPlanQtyMap, false,
                ScheduleTypeEnum.CONTINUOUS.getCode());
        int morningQty = ShiftCapacityResolverUtil.calculateShiftPlanQtyByDailyStandard(
                46, 16, 16, shifts.get(0), "2", sameDayPlanQtyMap, false,
                ScheduleTypeEnum.TYPE_BLOCK.getCode());

        assertEquals(14, nightQty, "参数配置晚班时，晚班应承接余量");
        assertEquals(14, morningQty, "参数配置早班时，早班应承接余量");
    }

    @Test
    void calculateShiftPlanQtyByDailyStandard_shouldUseClassCapacityWhenOtherShiftNotFull() {
        List<LhShiftConfigVO> shifts = LhScheduleTimeUtil.buildDefaultScheduleShifts(
                new LhScheduleContext(), date(2026, 4, 17));
        Map<Integer, Integer> sameDayPlanQtyMap = sameDayPlanQtyMap(shifts, 1, 0, 2, 16, 3, 16);

        int afternoonQty = ShiftCapacityResolverUtil.calculateShiftPlanQtyByDailyStandard(
                46, 16, 16, shifts.get(1), "3", sameDayPlanQtyMap, false,
                ScheduleTypeEnum.NEW_SPEC.getCode());

        assertEquals(16, afternoonQty, "其他班次不足班产时，剩余班次应按班产排");
    }

    @Test
    void calculateShiftPlanQtyByDailyStandard_shouldApplyRemainderToContinuousBoundaryDay() {
        List<LhShiftConfigVO> shifts = LhScheduleTimeUtil.buildDefaultScheduleShifts(
                new LhScheduleContext(), date(2026, 6, 14));
        Map<Integer, Integer> firstDayPlanQtyMap = sameDayPlanQtyMap(shifts, 1, 18, 2, 18);

        int continuousAfternoonQty = ShiftCapacityResolverUtil.calculateShiftPlanQtyByDailyStandard(
                50, 18, 18, shifts.get(1), "3", firstDayPlanQtyMap, false,
                ScheduleTypeEnum.CONTINUOUS.getCode());
        int newSpecAfternoonQty = ShiftCapacityResolverUtil.calculateShiftPlanQtyByDailyStandard(
                50, 18, 18, shifts.get(1), "3", firstDayPlanQtyMap, false,
                ScheduleTypeEnum.NEW_SPEC.getCode());

        assertEquals(14, continuousAfternoonQty,
                "续作首个业务日缺少窗口外夜班时，仍应按完整业务日计算中班余量");
        assertEquals(18, newSpecAfternoonQty,
                "新增排产仍需其他两个班次都达到班产，首个业务日中班应保持可排班产");
    }

    @Test
    void calculateShiftPlanQtyByDailyStandard_shouldApplyActualOtherShiftQtyForContinuousSchedule() {
        List<LhShiftConfigVO> shifts = LhScheduleTimeUtil.buildDefaultScheduleShifts(
                new LhScheduleContext(), date(2026, 6, 14));
        Map<Integer, Integer> sameDayPlanQtyMap = sameDayPlanQtyMap(shifts, 3, 18, 4, 16, 5, 18);

        int afternoonQty = ShiftCapacityResolverUtil.calculateShiftPlanQtyByDailyStandard(
                50, 18, 18, shifts.get(4), "3", sameDayPlanQtyMap, false,
                ScheduleTypeEnum.CONTINUOUS.getCode());

        assertEquals(16, afternoonQty,
                "续作其他班次未满时仍应按实际计划量合计计算中班余量");
    }

    @Test
    void calculateShiftPlanQtyByDailyStandard_shouldRaiseContinuousRemainShiftToFormulaQty() {
        List<LhShiftConfigVO> shifts = LhScheduleTimeUtil.buildDefaultScheduleShifts(
                new LhScheduleContext(), date(2026, 6, 14));
        Map<Integer, Integer> sameDayPlanQtyMap = sameDayPlanQtyMap(shifts, 6, 18, 7, 18, 8, 10);

        int afternoonQty = ShiftCapacityResolverUtil.calculateShiftPlanQtyByDailyStandard(
                50, 18, 10, shifts.get(7), "3", sameDayPlanQtyMap, false,
                ScheduleTypeEnum.CONTINUOUS.getCode());

        assertEquals(14, afternoonQty,
                "续作剩余班次低于公式结果时，也应按日标准产量公式修正到14");
    }

    @Test
    void calculateShiftPlanQtyByDailyStandard_shouldUseIndependentRemainShiftUpperLimit() {
        List<LhShiftConfigVO> shifts = LhScheduleTimeUtil.buildDefaultScheduleShifts(
                new LhScheduleContext(), date(2026, 7, 15));
        Map<Integer, Integer> sameDayPlanQtyMap = sameDayPlanQtyMap(shifts, 1, 16, 2, 16, 3, 16);

        int afternoonQty = ShiftCapacityResolverUtil.calculateShiftPlanQtyByDailyStandard(
                50, 16, 18, 16, shifts.get(1), "3", sameDayPlanQtyMap, false,
                ScheduleTypeEnum.CONTINUOUS.getCode());

        assertEquals(18, afternoonQty,
                "日标准量50高于班产16的三班合计时，中班应在独立理论上限18内补足日标准差额");
    }

    @Test
    void calculateShiftPlanQtyByDailyStandard_shouldNotRaiseRestrictedRemainShiftAboveRawCapacity() {
        List<LhShiftConfigVO> shifts = LhScheduleTimeUtil.buildDefaultScheduleShifts(
                new LhScheduleContext(), date(2026, 7, 15));
        Map<Integer, Integer> sameDayPlanQtyMap = sameDayPlanQtyMap(shifts, 1, 16, 2, 14, 3, 16);

        Map<Integer, Integer> adjustedMap = ShiftCapacityResolverUtil.adjustShiftPlanQtyMapByDailyStandard(
                shifts, sameDayPlanQtyMap, sameDayPlanQtyMap, 50, 16, 18, "3", false,
                ScheduleTypeEnum.CONTINUOUS.getCode());
        int afternoonQty = adjustedMap.get(shifts.get(1).getShiftIndex());

        assertEquals(14, afternoonQty,
                "中班已被停机清洗等约束压缩时，不得为了补足日标准量反向抬高实际可排量");
    }

    @Test
    void calculateShiftPlanQtyByDailyStandard_shouldNotCutSchedulableRemainShiftToZero() {
        List<LhShiftConfigVO> shifts = LhScheduleTimeUtil.buildDefaultScheduleShifts(
                new LhScheduleContext(), date(2026, 7, 3));
        Map<Integer, Integer> sameDayPlanQtyMap = sameDayPlanQtyMap(shifts, 3, 16, 4, 16, 5, 16);

        int nightQty = ShiftCapacityResolverUtil.calculateShiftPlanQtyByDailyStandard(
                30, 16, 16, shifts.get(2), "1", sameDayPlanQtyMap, false,
                ScheduleTypeEnum.NEW_SPEC.getCode());
        int continuousNightQty = ShiftCapacityResolverUtil.calculateShiftPlanQtyByDailyStandard(
                30, 16, 16, shifts.get(2), "1", sameDayPlanQtyMap, false,
                ScheduleTypeEnum.CONTINUOUS.getCode());

        assertEquals(16, nightQty, "新增SKU已完成换模并进入连续生产链路时，日标准余量为负不能把可生产晚班裁成0");
        assertEquals(16, continuousNightQty, "续作SKU已在机生产时，日标准余量为负不能把可生产晚班裁成0");
    }

    @Test
    void calculateShiftPlanQtyByDailyStandard_shouldKeepAfternoonFullWhenNightShiftNotFull() {
        List<LhShiftConfigVO> shifts = LhScheduleTimeUtil.buildDefaultScheduleShifts(
                new LhScheduleContext(), date(2026, 6, 14));
        Map<Integer, Integer> sameDayPlanQtyMap = sameDayPlanQtyMap(shifts, 3, 10, 4, 18, 5, 18);

        int afternoonQty = ShiftCapacityResolverUtil.calculateShiftPlanQtyByDailyStandard(
                52, 18, 18, shifts.get(4), "3", sameDayPlanQtyMap, false,
                ScheduleTypeEnum.NEW_SPEC.getCode());

        assertEquals(18, afternoonQty, "同一业务日晚班不足班产时，中班不应按日标准余量回裁为16");
    }

    @Test
    void calculateShiftPlanQtyByDailyStandard_shouldKeepSingleControlAndNeverExceedClassCapacity() {
        List<LhShiftConfigVO> shifts = LhScheduleTimeUtil.buildDefaultScheduleShifts(
                new LhScheduleContext(), date(2026, 4, 17));
        Map<Integer, Integer> sameDayPlanQtyMap = sameDayPlanQtyMap(shifts, 1, 16, 2, 16, 3, 16);

        int singleControlQty = ShiftCapacityResolverUtil.calculateShiftPlanQtyByDailyStandard(
                46, 16, 16, shifts.get(1), "3", sameDayPlanQtyMap, true,
                ScheduleTypeEnum.NEW_SPEC.getCode());
        int cappedQty = ShiftCapacityResolverUtil.calculateShiftPlanQtyByDailyStandard(
                60, 16, 20, shifts.get(0), "3", sameDayPlanQtyMap, false,
                ScheduleTypeEnum.NEW_SPEC.getCode());

        assertEquals(16, singleControlQty, "单控机台不应用日标准产量班次修正");
        assertEquals(16, cappedQty, "普通机台班次计划量不能超过原始班产");
    }

    @Test
    void resolveDailyStandardQty_shouldUseStandardCapacityOnly() {
        MdmSkuLhCapacity capacity = new MdmSkuLhCapacity();
        capacity.setStandardCapacity(46);
        capacity.setApsCapacity(99);

        int dailyStandardQty = ShiftCapacityResolverUtil.resolveDailyStandardQty(capacity);

        assertEquals(46, dailyStandardQty, "日标准产量修正规则必须取SKU日硫化产能的标准产能字段");
    }

    @Test
    void resolveDailyStandardRemainShiftCapacityUpperLimit_shouldUseApsCapacityOnlyAsUpperLimit() {
        LhScheduleContext context = new LhScheduleContext();
        MdmSkuLhCapacity capacity = new MdmSkuLhCapacity();
        capacity.setMaterialCode("3302002177");
        capacity.setClassCapacity(16);
        capacity.setStandardCapacity(50);
        capacity.setApsCapacity(54);
        context.getSkuLhCapacityMap().put("3302002177", capacity);

        int upperLimit = ShiftCapacityResolverUtil.resolveDailyStandardRemainShiftCapacityUpperLimit(
                context, "3302002177", 16);

        assertEquals(18, upperLimit, "APS日产54只应折算为中班理论上限18，日标准量仍由标准产能50决定");
    }

    private int actualQty(List<LhShiftConfigVO> shifts, int shiftIndex, String configPlusShiftType) {
        return ShiftCapacityResolverUtil.resolveActualShiftPlanQty(
                17, shifts.get(shiftIndex - 1), configPlusShiftType, ScheduleTypeEnum.NEW_SPEC.getCode());
    }

    private Map<Integer, Integer> sameDayPlanQtyMap(List<LhShiftConfigVO> shifts,
                                                    int firstShiftIndex,
                                                    int firstQty,
                                                    int secondShiftIndex,
                                                    int secondQty,
                                                    int thirdShiftIndex,
                                                    int thirdQty) {
        Map<Integer, Integer> sameDayPlanQtyMap = new LinkedHashMap<Integer, Integer>(3);
        sameDayPlanQtyMap.put(shifts.get(firstShiftIndex - 1).getShiftIndex(), firstQty);
        sameDayPlanQtyMap.put(shifts.get(secondShiftIndex - 1).getShiftIndex(), secondQty);
        sameDayPlanQtyMap.put(shifts.get(thirdShiftIndex - 1).getShiftIndex(), thirdQty);
        return sameDayPlanQtyMap;
    }

    private Map<Integer, Integer> sameDayPlanQtyMap(List<LhShiftConfigVO> shifts,
                                                    int firstShiftIndex,
                                                    int firstQty,
                                                    int secondShiftIndex,
                                                    int secondQty) {
        Map<Integer, Integer> sameDayPlanQtyMap = new LinkedHashMap<Integer, Integer>(2);
        sameDayPlanQtyMap.put(shifts.get(firstShiftIndex - 1).getShiftIndex(), firstQty);
        sameDayPlanQtyMap.put(shifts.get(secondShiftIndex - 1).getShiftIndex(), secondQty);
        return sameDayPlanQtyMap;
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
