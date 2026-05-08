package com.zlt.aps.lh.regression;

import com.zlt.aps.lh.api.constant.LhScheduleParamConstant;
import com.zlt.aps.lh.api.domain.dto.MachineMaintenanceWindowDTO;
import com.zlt.aps.lh.api.domain.dto.MachineScheduleDTO;
import com.zlt.aps.lh.api.domain.entity.LhMachineOnlineInfo;
import com.zlt.aps.lh.api.domain.entity.LhPrecisionPlan;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.service.impl.LhMaintenanceScheduleService;
import com.zlt.aps.mdm.api.domain.entity.MdmWorkCalendar;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 硫化精度保养计划排程回归测试。
 */
class MaintenanceScheduleServiceRegressionTest {

    private final LhMaintenanceScheduleService service = new LhMaintenanceScheduleService();

    @Test
    void tryAttachMaintenanceAfterFirstEnding_shouldCreateFixedMorningWindowWhenDueSoon() {
        LhScheduleContext context = buildContext(date(2026, 4, 20));
        context.getMaintenancePlanMap().put("K1001", buildPrecisionPlan("K1001", date(2026, 5, 10), 20));
        context.getMachineOnlineInfoMap().put("K1001", buildOnlineInfo("K1001", date(2026, 3, 25)));
        MachineScheduleDTO machine = buildMachine("K1001");

        boolean scheduled = service.tryAttachMaintenanceAfterFirstEnding(
                context, machine, dateTime(2026, 4, 20, 6, 30));

        assertTrue(scheduled, "到期 30 天内且首次收尾后应安排保养");
        assertEquals(1, machine.getMaintenanceWindowList().size());
        MachineMaintenanceWindowDTO window = machine.getMaintenanceWindowList().get(0);
        assertEquals(dateTime(2026, 4, 20, 8, 0), window.getMaintenanceStartTime());
        assertEquals(dateTime(2026, 4, 20, 15, 0), window.getMaintenanceEndTime());
        assertEquals(1, context.getDailyMaintenanceCountMap().get("2026-04-20").intValue());
    }

    @Test
    void tryAttachMaintenanceAfterFirstEnding_shouldDelayWhenSundayInventoryAndHolidayBeforeDaysBlocked() {
        LhScheduleContext context = buildContext(date(2026, 5, 3));
        context.getMaintenancePlanMap().put("K1001", buildPrecisionPlan("K1001", date(2026, 5, 13), 10));
        context.getMachineOnlineInfoMap().put("K1001", buildOnlineInfo("K1001", date(2026, 4, 5)));
        context.getWorkCalendarList().add(buildHoliday(date(2026, 5, 5)));
        MachineScheduleDTO machine = buildMachine("K1001");

        boolean scheduled = service.tryAttachMaintenanceAfterFirstEnding(
                context, machine, dateTime(2026, 5, 3, 9, 0));

        assertTrue(scheduled, "保养日期应向后顺延到满足约束的日期");
        MachineMaintenanceWindowDTO window = machine.getMaintenanceWindowList().get(0);
        assertEquals(dateTime(2026, 5, 6, 8, 0), window.getMaintenanceStartTime());
        assertEquals(dateTime(2026, 5, 6, 15, 0), window.getMaintenanceEndTime());
    }

    @Test
    void tryAttachLongOnlineMaintenance_shouldMarkForceDownWhenMachineOnlineOverThirtyDays() {
        LhScheduleContext context = buildContext(date(2026, 4, 20));
        context.getMaintenancePlanMap().put("K1001", buildPrecisionPlan("K1001", date(2026, 4, 22), 2));
        LhMachineOnlineInfo onlineInfo = new LhMachineOnlineInfo();
        onlineInfo.setLhCode("K1001");
        onlineInfo.setOnlineDate(date(2026, 3, 1));
        context.getMachineOnlineInfoMap().put("K1001", onlineInfo);
        MachineScheduleDTO machine = buildMachine("K1001");

        boolean scheduled = service.tryAttachLongOnlineMaintenance(context, machine);

        assertTrue(scheduled, "长期在机且到期前检查应安排强制下机保养");
        MachineMaintenanceWindowDTO window = machine.getMaintenanceWindowList().get(0);
        assertTrue(window.isForceDown(), "长期在机触发的保养窗口应标记强制下机");
        assertEquals("长期在机强制下机", window.getTriggerReason());
    }

    @Test
    void tryAttachMaintenanceAfterFirstEnding_shouldUseDaysToDueWhenDueDateMissing() {
        LhScheduleContext context = buildContext(date(2026, 4, 27));
        context.getMaintenancePlanMap().put("K2025", buildPrecisionPlan("K2025", null, 18));
        context.getMachineOnlineInfoMap().put("K2025", buildOnlineInfo("K2025", date(2026, 3, 28)));
        MachineScheduleDTO machine = buildMachine("K2025");

        boolean scheduled = service.tryAttachMaintenanceAfterFirstEnding(
                context, machine, dateTime(2026, 4, 27, 1, 38));

        assertTrue(scheduled, "dueDate 为空时，只要 daysToDue 落在预警窗口内也应安排保养");
        assertEquals(1, machine.getMaintenanceWindowList().size());
        MachineMaintenanceWindowDTO window = machine.getMaintenanceWindowList().get(0);
        assertEquals(dateTime(2026, 4, 27, 8, 0), window.getMaintenanceStartTime());
        assertEquals(dateTime(2026, 4, 27, 15, 0), window.getMaintenanceEndTime());
    }

    @Test
    void tryAttachMaintenanceAfterFirstEnding_shouldSkipIdleMachineWithoutRecentOnlineRecord() {
        LhScheduleContext context = buildContext(date(2026, 5, 3));
        context.getMaintenancePlanMap().put("K1105", buildPrecisionPlan("K1105", null, 5));
        MachineScheduleDTO machine = buildMachine("K1105");

        boolean scheduled = service.tryAttachMaintenanceAfterFirstEnding(
                context, machine, dateTime(2026, 5, 3, 6, 0));

        assertTrue(!scheduled, "一个月内无 MES 在机记录且当前无在机物料时，不应提前触发首个规格收尾后的精度计划");
        assertTrue(machine.getMaintenanceWindowList().isEmpty(), "未满足首个规格收尾条件时，不应写入保养窗口");
    }

    @Test
    void tryAttachMaintenanceAfterFirstEnding_shouldSkipMachineWithOnlyCurrentMaterialButNoRecentMesOnline() {
        LhScheduleContext context = buildContext(date(2026, 5, 3));
        context.getMaintenancePlanMap().put("K1105", buildPrecisionPlan("K1105", null, 5));
        context.getMachineOnlineInfoMap().put("K1105", buildOnlineInfo("K1105", date(2026, 3, 1)));
        MachineScheduleDTO machine = buildMachine("K1105");
        machine.setCurrentMaterialCode("3302002391");

        boolean scheduled = service.tryAttachMaintenanceAfterFirstEnding(
                context, machine, dateTime(2026, 5, 3, 6, 0));

        assertTrue(!scheduled, "仅有运行态当前物料、但近30天无MES在机记录时，不应提前触发首个规格收尾后的精度计划");
        assertTrue(machine.getMaintenanceWindowList().isEmpty(), "近30天无MES在机记录时，不应写入保养窗口");
    }

    private static LhScheduleContext buildContext(Date scheduleDate) {
        LhScheduleContext context = new LhScheduleContext();
        context.setFactoryCode("116");
        context.setScheduleDate(scheduleDate);
        context.setScheduleTargetDate(scheduleDate);
        context.getLhParamsMap().put(LhScheduleParamConstant.MAINTENANCE_START_HOUR, "8");
        context.getLhParamsMap().put(LhScheduleParamConstant.MAINTENANCE_DURATION_HOURS, "7");
        context.getLhParamsMap().put(LhScheduleParamConstant.MAINTENANCE_DAILY_LIMIT, "1");
        context.getLhParamsMap().put(LhScheduleParamConstant.ALLOW_MAINTENANCE_ON_SUNDAY, "0");
        context.getLhParamsMap().put(LhScheduleParamConstant.ALLOW_MAINTENANCE_ON_INVENTORY_DAY, "0");
        context.getLhParamsMap().put(LhScheduleParamConstant.MAINTENANCE_HOLIDAY_BLOCK_DAYS, "2");
        context.getLhParamsMap().put(LhScheduleParamConstant.MAINTENANCE_FORCE_CHECK_DAYS, "3");
        context.getLhParamsMap().put(LhScheduleParamConstant.MAINTENANCE_WARNING_DAYS, "30");
        return context;
    }

    private static MachineScheduleDTO buildMachine(String machineCode) {
        MachineScheduleDTO machine = new MachineScheduleDTO();
        machine.setMachineCode(machineCode);
        machine.setMachineName(machineCode);
        return machine;
    }

    private static LhPrecisionPlan buildPrecisionPlan(String machineCode, Date dueDate, Integer daysToDue) {
        LhPrecisionPlan plan = new LhPrecisionPlan();
        plan.setFactoryCode("116");
        plan.setMachineCode(machineCode);
        plan.setYear(BigDecimal.valueOf(2026));
        plan.setDueDate(dueDate);
        plan.setDaysToDue(daysToDue);
        plan.setCompletionStatus("0");
        return plan;
    }

    private static LhMachineOnlineInfo buildOnlineInfo(String machineCode, Date onlineDate) {
        LhMachineOnlineInfo onlineInfo = new LhMachineOnlineInfo();
        onlineInfo.setLhCode(machineCode);
        onlineInfo.setOnlineDate(onlineDate);
        onlineInfo.setMaterialCode("MAT-ONLINE");
        return onlineInfo;
    }

    private static MdmWorkCalendar buildHoliday(Date productionDate) {
        MdmWorkCalendar calendar = new MdmWorkCalendar();
        calendar.setFactoryCode("116");
        calendar.setProcCode("02");
        calendar.setProductionDate(productionDate);
        calendar.setDayFlag("0");
        return calendar;
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
}
