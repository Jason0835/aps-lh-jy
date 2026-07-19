package com.zlt.aps.lh.regression;

import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.engine.strategy.impl.DefaultMouldChangeBalanceStrategy;
import com.zlt.aps.mdm.api.domain.entity.MdmDevicePlanShut;
import org.junit.jupiter.api.Test;

import java.util.Calendar;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 换模均衡策略回归：05计划性维修允许并行切换，其他设备停机继续顺延，换模配额支持回滚。
 */
class DefaultMouldChangeBalanceStrategyRegressionTest {

    @Test
    void allocateMouldChange_shouldDelayToNextMorningWhenDowntimeEndsAtNight() {
        DefaultMouldChangeBalanceStrategy strategy = new DefaultMouldChangeBalanceStrategy();
        LhScheduleContext context = new LhScheduleContext();
        context.getDevicePlanShutList().add(planShut("K2024",
                dateTime(2026, 4, 21, 6, 0, 0),
                dateTime(2026, 4, 21, 23, 59, 59)));

        Date allocatedTime = strategy.allocateMouldChange(
                context,
                "K2024",
                dateTime(2026, 4, 21, 6, 0, 0));

        assertEquals(dateTime(2026, 4, 22, 6, 0, 0), allocatedTime,
                "停机结束落在夜班禁换模时段时，应顺延到次日早班再换模");
        assertNull(context.getDailyMouldChangeCountMap().get("2026-04-21"),
                "停机日不应占用当日换模配额");
        assertArrayEquals(new int[]{1, 0}, context.getDailyMouldChangeCountMap().get("2026-04-22"));
    }

    @Test
    void allocateMouldChange_shouldUseSameCalendarMorningWhenReadyInEarlyMorning() {
        DefaultMouldChangeBalanceStrategy strategy = new DefaultMouldChangeBalanceStrategy();
        LhScheduleContext context = new LhScheduleContext();

        Date allocatedTime = strategy.allocateMouldChange(
                context,
                "K2024",
                dateTime(2026, 4, 23, 2, 0, 0));

        assertEquals(dateTime(2026, 4, 23, 6, 0, 0), allocatedTime,
                "跨日夜班凌晨段就绪时，换模应落在当日早班，而非日历再顺延一天");
        assertArrayEquals(new int[]{1, 0}, context.getDailyMouldChangeCountMap().get("2026-04-23"));
    }

    @Test
    void allocateMouldChange_shouldUseNextCalendarMorningWhenReadyAfterNoonBanWindow() {
        DefaultMouldChangeBalanceStrategy strategy = new DefaultMouldChangeBalanceStrategy();
        LhScheduleContext context = new LhScheduleContext();

        Date allocatedTime = strategy.allocateMouldChange(
                context,
                "K2024",
                dateTime(2026, 4, 22, 21, 0, 0));

        assertEquals(dateTime(2026, 4, 23, 6, 0, 0), allocatedTime,
                "晚间禁止换模段就绪时，换模应落在次日早班");
        assertArrayEquals(new int[]{1, 0}, context.getDailyMouldChangeCountMap().get("2026-04-23"));
    }

    @Test
    void allocateMouldChange_shouldAllowBoundaryAtSixAndTwenty() {
        DefaultMouldChangeBalanceStrategy strategy = new DefaultMouldChangeBalanceStrategy();
        LhScheduleContext context = new LhScheduleContext();

        Date sixAllocated = strategy.allocateMouldChange(
                context, "K2024", dateTime(2026, 4, 23, 6, 0, 0));
        Date twentyAllocated = strategy.allocateMouldChange(
                context, "K2024", dateTime(2026, 4, 23, 20, 0, 0));
        Date twentyOneAllocated = strategy.allocateMouldChange(
                context, "K2024", dateTime(2026, 4, 23, 20, 1, 0));
        Date earlyMorningAllocated = strategy.allocateMouldChange(
                context, "K2024", dateTime(2026, 4, 24, 5, 59, 0));

        assertEquals(dateTime(2026, 4, 23, 6, 0, 0), sixAllocated,
                "06:00 整点允许开始换模");
        assertEquals(dateTime(2026, 4, 23, 20, 0, 0), twentyAllocated,
                "20:00 整点允许开始换模");
        assertEquals(dateTime(2026, 4, 24, 6, 0, 0), twentyOneAllocated,
                "20:00 后禁止开始换模，应顺延到次日早班");
        assertEquals(dateTime(2026, 4, 24, 6, 0, 0), earlyMorningAllocated,
                "06:00 前禁止开始换模，应顺延到当日早班");
    }

    @Test
    void rollbackMouldChange_shouldRestoreMorningQuotaAfterAllocate() {
        DefaultMouldChangeBalanceStrategy strategy = new DefaultMouldChangeBalanceStrategy();
        LhScheduleContext context = new LhScheduleContext();
        Date morningAllocated = strategy.allocateMouldChange(
                context, "K2024", dateTime(2026, 4, 23, 8, 0, 0));
        assertEquals(dateTime(2026, 4, 23, 8, 0, 0), morningAllocated);
        assertArrayEquals(new int[]{1, 0}, context.getDailyMouldChangeCountMap().get("2026-04-23"));

        strategy.rollbackMouldChange(context, morningAllocated);
        assertArrayEquals(new int[]{0, 0}, context.getDailyMouldChangeCountMap().get("2026-04-23"));
    }

    @Test
    void rollbackMouldChange_shouldRestoreAfternoonQuotaWhenMorningFull() {
        DefaultMouldChangeBalanceStrategy strategy = new DefaultMouldChangeBalanceStrategy();
        LhScheduleContext context = new LhScheduleContext();
        // 早班配额已满，下一笔换模应落在中班
        context.getDailyMouldChangeCountMap().put("2026-04-23", new int[]{8, 0});
        Date afternoonAllocated = strategy.allocateMouldChange(
                context, "K2024", dateTime(2026, 4, 23, 15, 0, 0));
        assertEquals(dateTime(2026, 4, 23, 15, 0, 0), afternoonAllocated);
        assertArrayEquals(new int[]{8, 1}, context.getDailyMouldChangeCountMap().get("2026-04-23"));

        strategy.rollbackMouldChange(context, afternoonAllocated);
        assertArrayEquals(new int[]{8, 0}, context.getDailyMouldChangeCountMap().get("2026-04-23"));
    }

    @Test
    void rollbackMouldChange_shouldNoOpWhenContextOrTimeNull() {
        DefaultMouldChangeBalanceStrategy strategy = new DefaultMouldChangeBalanceStrategy();
        LhScheduleContext context = new LhScheduleContext();
        Date t = dateTime(2026, 4, 23, 8, 0, 0);
        strategy.rollbackMouldChange(null, t);
        strategy.rollbackMouldChange(context, null);
        assertArrayEquals(new int[]{0, 0}, context.getDailyMouldChangeCountMap()
                .getOrDefault("2026-04-23", new int[]{0, 0}));
    }

    @Test
    void rollbackMouldChange_shouldNoOpWhenDateKeyMissingInMap() {
        DefaultMouldChangeBalanceStrategy strategy = new DefaultMouldChangeBalanceStrategy();
        LhScheduleContext context = new LhScheduleContext();
        strategy.rollbackMouldChange(context, dateTime(2026, 4, 23, 8, 0, 0));
        assertArrayEquals(new int[]{0, 0}, context.getDailyMouldChangeCountMap()
                .getOrDefault("2026-04-23", new int[]{0, 0}));
    }

    @Test
    void rollbackMouldChange_shouldNotDecrementBelowZeroForMorning() {
        DefaultMouldChangeBalanceStrategy strategy = new DefaultMouldChangeBalanceStrategy();
        LhScheduleContext context = new LhScheduleContext();
        context.getDailyMouldChangeCountMap().put("2026-04-23", new int[]{0, 0});
        strategy.rollbackMouldChange(context, dateTime(2026, 4, 23, 8, 0, 0));
        assertArrayEquals(new int[]{0, 0}, context.getDailyMouldChangeCountMap().get("2026-04-23"));
    }

    @Test
    void rollbackMouldChange_shouldNotChangeCountsWhenAllocatedTimeInNight() {
        DefaultMouldChangeBalanceStrategy strategy = new DefaultMouldChangeBalanceStrategy();
        LhScheduleContext context = new LhScheduleContext();
        context.getDailyMouldChangeCountMap().put("2026-04-23", new int[]{1, 0});
        strategy.rollbackMouldChange(context, dateTime(2026, 4, 23, 23, 0, 0));
        assertArrayEquals(new int[]{1, 0}, context.getDailyMouldChangeCountMap().get("2026-04-23"));
    }

    @Test
    void allocateMouldChange_shouldIgnoreDowntimeOfOtherMachine() {
        DefaultMouldChangeBalanceStrategy strategy = new DefaultMouldChangeBalanceStrategy();
        LhScheduleContext context = new LhScheduleContext();
        context.getDevicePlanShutList().add(planShut("K2024",
                dateTime(2026, 4, 21, 6, 0, 0),
                dateTime(2026, 4, 21, 23, 59, 59)));

        Date allocatedTime = strategy.allocateMouldChange(
                context,
                "K2025",
                dateTime(2026, 4, 21, 6, 0, 0));

        assertEquals(dateTime(2026, 4, 21, 6, 0, 0), allocatedTime,
                "其它机台的停机记录不应影响当前机台换模");
        assertArrayEquals(new int[]{1, 0}, context.getDailyMouldChangeCountMap().get("2026-04-21"),
                "06:00实际落在早班，其他机台停机不得把本机台配额错误记入中班");
    }

    @Test
    void allocateMouldChange_shouldUseSpecifiedSwitchDurationForTypeBlockOverlap() {
        DefaultMouldChangeBalanceStrategy strategy = new DefaultMouldChangeBalanceStrategy();
        LhScheduleContext context = new LhScheduleContext();
        context.getDevicePlanShutList().add(planShut("K2024",
                dateTime(2026, 4, 21, 9, 0, 0),
                dateTime(2026, 4, 21, 14, 0, 0)));

        Date mouldChangeAllocated = strategy.allocateMouldChange(
                context,
                "K2024",
                dateTime(2026, 4, 21, 6, 0, 0));
        Date typeBlockAllocated = strategy.allocateMouldChange(
                context,
                "K2024",
                dateTime(2026, 4, 22, 6, 0, 0),
                3);

        assertEquals(dateTime(2026, 4, 21, 14, 0, 0), mouldChangeAllocated,
                "正规换模仍应按默认换模总时长判断停机重叠");
        assertEquals(dateTime(2026, 4, 22, 6, 0, 0), typeBlockAllocated,
                "换活字块应按独立切换时长判断停机重叠，不应沿用换模总时长");
        assertArrayEquals(new int[]{0, 1}, context.getDailyMouldChangeCountMap().get("2026-04-21"));
        assertArrayEquals(new int[]{1, 0}, context.getDailyMouldChangeCountMap().get("2026-04-22"));
    }

    @Test
    void allocateMouldChange_shouldAllowMouldAndTypeBlockSwitchDuringPlannedRepairOnly() {
        DefaultMouldChangeBalanceStrategy strategy = new DefaultMouldChangeBalanceStrategy();
        LhScheduleContext plannedRepairContext = new LhScheduleContext();
        MdmDevicePlanShut plannedRepair = planShut("K2024",
                dateTime(2026, 7, 19, 8, 0, 0),
                dateTime(2026, 7, 19, 16, 0, 0));
        plannedRepair.setMachineStopType("05");
        plannedRepairContext.getDevicePlanShutList().add(plannedRepair);

        Date mouldChangeAllocated = strategy.allocateMouldChange(
                plannedRepairContext, "K2024", dateTime(2026, 7, 19, 6, 0, 0), 8);

        LhScheduleContext typeBlockContext = new LhScheduleContext();
        MdmDevicePlanShut typeBlockRepair = planShut("K2024",
                dateTime(2026, 7, 19, 8, 0, 0),
                dateTime(2026, 7, 19, 16, 0, 0));
        typeBlockRepair.setMachineStopType("05");
        typeBlockContext.getDevicePlanShutList().add(typeBlockRepair);
        Date typeBlockAllocated = strategy.allocateMouldChange(
                typeBlockContext, "K2024", dateTime(2026, 7, 19, 10, 0, 0), 3);

        assertEquals(dateTime(2026, 7, 19, 6, 0, 0), mouldChangeAllocated,
                "换模与05维修重叠时应保留原切换起点，后续统一按最大结束时间追加预热");
        assertEquals(dateTime(2026, 7, 19, 10, 0, 0), typeBlockAllocated,
                "换活字块使用同一分配器时也必须保持05并行语义");

        LhScheduleContext faultContext = new LhScheduleContext();
        MdmDevicePlanShut temporaryFault = planShut("K2024",
                dateTime(2026, 7, 21, 8, 0, 0),
                dateTime(2026, 7, 21, 16, 0, 0));
        temporaryFault.setMachineStopType("06");
        faultContext.getDevicePlanShutList().add(temporaryFault);
        Date faultAllocated = strategy.allocateMouldChange(
                faultContext, "K2024", dateTime(2026, 7, 21, 6, 0, 0), 8);

        assertEquals(dateTime(2026, 7, 21, 16, 0, 0), faultAllocated,
                "06及其他停机类型仍必须顺延切换，不得随05规则一起放开");
    }

    private MdmDevicePlanShut planShut(String machineCode, Date beginDate, Date endDate) {
        MdmDevicePlanShut planShut = new MdmDevicePlanShut();
        planShut.setMachineCode(machineCode);
        planShut.setBeginDate(beginDate);
        planShut.setEndDate(endDate);
        return planShut;
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
}
