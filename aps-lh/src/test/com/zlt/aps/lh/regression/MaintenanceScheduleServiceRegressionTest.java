package com.zlt.aps.lh.regression;

import com.zlt.aps.lh.api.constant.LhScheduleParamConstant;
import com.zlt.aps.lh.api.domain.dto.MachineMaintenanceWindowDTO;
import com.zlt.aps.lh.api.domain.dto.MachineScheduleDTO;
import com.zlt.aps.lh.api.domain.entity.LhMachineOnlineInfo;
import com.zlt.aps.lh.api.domain.entity.LhPrecisionPlan;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.service.impl.LhMaintenanceScheduleService;
import com.zlt.aps.lh.util.LhScheduleTimeUtil;
import com.zlt.aps.mdm.api.domain.entity.MdmWorkCalendar;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Calendar;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 硫化精度保养计划排程回归测试。
 */
class MaintenanceScheduleServiceRegressionTest {

    private final LhMaintenanceScheduleService service = new LhMaintenanceScheduleService();

    @Test
    void tryAttachMaintenanceAfterFirstEnding_shouldDelayWhenEndingAfterSix() {
        LhScheduleContext context = buildContext(date(2026, 4, 20));
        context.getMaintenancePlanMap().put("K1001", buildPrecisionPlan("K1001", date(2026, 5, 10), 20));
        MachineScheduleDTO machine = buildMachine("K1001");

        boolean scheduled = service.tryAttachMaintenanceAfterFirstEnding(
                context, machine, dateTime(2026, 4, 20, 6, 30));

        assertTrue(scheduled, "到期 30 天内且首次收尾后应安排保养");
        assertEquals(1, machine.getMaintenanceWindowList().size());
        MachineMaintenanceWindowDTO window = machine.getMaintenanceWindowList().get(0);
        assertEquals(dateTime(2026, 4, 21, 8, 0), window.getMaintenanceStartTime());
        assertEquals(dateTime(2026, 4, 21, 15, 0), window.getMaintenanceEndTime());
        assertEquals(dateTime(2026, 4, 21, 17, 30), window.getProductionResumeTime());
        assertEquals(1, context.getDailyMaintenanceCountMap().get("2026-04-21").intValue());
        assertTrue(context.getScheduleLogList().stream()
                        .anyMatch(item -> "精准计划最终安排".equals(item.getTitle())
                                && item.getLogDetail().contains("最早开产=2026-04-21 17:30:00")),
                "最终保养开始、结束和最早开产必须写入排程过程日志");
    }

    @Test
    void tryAttachMaintenanceAfterFirstEnding_shouldDelayToNextDayWhenEndingAfterSix() {
        LhScheduleContext context = buildContext(date(2026, 4, 20));
        context.getMaintenancePlanMap().put("K1001", buildPrecisionPlan("K1001", date(2026, 5, 10), 20));
        MachineScheduleDTO machine = buildMachine("K1001");

        boolean scheduled = service.tryAttachMaintenanceAfterFirstEnding(
                context, machine, dateTime(2026, 4, 20, 8, 1));

        assertTrue(scheduled);
        assertEquals(dateTime(2026, 4, 21, 8, 0),
                machine.getMaintenanceWindowList().get(0).getMaintenanceStartTime(),
                "06:00后收尾不得回排当天精度，必须从下一自然日08:00开始寻找");
    }

    @Test
    void tryAttachMaintenanceAfterFirstEnding_shouldDelayWhenDailyPhysicalMachineLimitReached() {
        LhScheduleContext context = buildContext(date(2026, 4, 20));
        context.getMaintenancePlanMap().put("K1001", buildPrecisionPlan("K1001", date(2026, 5, 10), 20));
        context.getDailyMaintenanceCountMap().put("2026-04-20", 1);
        MachineScheduleDTO machine = buildMachine("K1001");

        boolean scheduled = service.tryAttachMaintenanceAfterFirstEnding(
                context, machine, dateTime(2026, 4, 20, 8, 0));

        assertTrue(scheduled);
        assertEquals(dateTime(2026, 4, 21, 8, 0),
                machine.getMaintenanceWindowList().get(0).getMaintenanceStartTime());
    }

    @Test
    void tryAttachMaintenanceAfterFirstEnding_shouldNotDelayPastPlanDateAtYearEnd() {
        LhScheduleContext context = buildContext(date(2026, 12, 31));
        context.getMaintenancePlanMap().put("K1001", buildPrecisionPlan("K1001", date(2026, 12, 31), 0));
        MachineScheduleDTO machine = buildMachine("K1001");

        boolean scheduled = service.tryAttachMaintenanceAfterFirstEnding(
                context, machine, dateTime(2026, 12, 31, 7, 59));

        assertFalse(scheduled, "计划日命中盘点且没有更早合规日期时，不得跨年延后执行");
        assertTrue(machine.getMaintenanceWindowList().isEmpty());
    }

    @Test
    void tryAttachMaintenanceAfterFirstEnding_shouldDelayWhenSundayInventoryAndHolidayBeforeDaysBlocked() {
        LhScheduleContext context = buildContext(date(2026, 5, 3));
        context.getMaintenancePlanMap().put("K1001", buildPrecisionPlan("K1001", date(2026, 5, 13), 10));
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
    void tryAttachMaintenanceAfterFirstEnding_shouldRespectSundayParameter() {
        LhScheduleContext blockedContext = buildContext(date(2026, 5, 3));
        blockedContext.getMaintenancePlanMap().put(
                "K1001", buildPrecisionPlan("K1001", date(2026, 5, 13), 10));
        MachineScheduleDTO blockedMachine = buildMachine("K1001");

        service.tryAttachMaintenanceAfterFirstEnding(
                blockedContext, blockedMachine, dateTime(2026, 5, 3, 7, 0));

        assertEquals(dateTime(2026, 5, 4, 8, 0),
                blockedMachine.getMaintenanceWindowList().get(0).getMaintenanceStartTime(),
                "SYS0307005=0时周日应顺延到下一可用日");

        LhScheduleContext allowedContext = buildContext(date(2026, 5, 3));
        allowedContext.getLhParamsMap().put(LhScheduleParamConstant.ALLOW_MAINTENANCE_ON_SUNDAY, "1");
        allowedContext.getMaintenancePlanMap().put(
                "K1001", buildPrecisionPlan("K1001", date(2026, 5, 13), 10));
        MachineScheduleDTO allowedMachine = buildMachine("K1001");

        service.tryAttachMaintenanceAfterFirstEnding(
                allowedContext, allowedMachine, dateTime(2026, 5, 3, 6, 0));

        assertEquals(dateTime(2026, 5, 3, 8, 0),
                allowedMachine.getMaintenanceWindowList().get(0).getMaintenanceStartTime(),
                "SYS0307005=1时允许周日安排");
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

        boolean scheduled = service.tryAttachLongOnlineMaintenance(
                context, machine, dateTime(2026, 4, 21, 12, 0));

        assertTrue(scheduled, "长期在机且到期前检查应安排强制下机保养");
        MachineMaintenanceWindowDTO window = machine.getMaintenanceWindowList().get(0);
        assertTrue(window.isForceDown(), "长期在机触发的保养窗口应标记强制下机");
        assertEquals("精度计划到期强制下机", window.getTriggerReason());
    }

    @Test
    void tryAttachLongOnlineMaintenance_shouldWaitWhenPredictedEndingBeforeCandidateStart() {
        LhScheduleContext context = buildContext(date(2026, 4, 20));
        context.getMaintenancePlanMap().put("K1001", buildPrecisionPlan("K1001", date(2026, 4, 22), 2));
        context.getMachineOnlineInfoMap().put("K1001", buildOnlineInfo("K1001", date(2026, 3, 1)));
        MachineScheduleDTO machine = buildMachine("K1001");

        boolean scheduled = service.tryAttachLongOnlineMaintenance(
                context, machine, dateTime(2026, 4, 20, 7, 30));

        assertFalse(scheduled, "预测可在候选保养08:00前自然收尾时不应强制下机");
        assertTrue(machine.getMaintenanceWindowList().isEmpty());
    }

    @Test
    void tryAttachMaintenanceAfterFirstEnding_shouldUseDaysToDueWhenDueDateMissing() {
        LhScheduleContext context = buildContext(date(2026, 4, 27));
        context.getMaintenancePlanMap().put("K2025", buildPrecisionPlan("K2025", null, 18));
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
    void tryAttachMaintenanceAfterFirstEnding_shouldUseDaysToDueInsteadOfDueDateDifference() {
        LhScheduleContext context = buildContext(date(2026, 4, 20));
        context.getMaintenancePlanMap().put(
                "K2025", buildPrecisionPlan("K2025", date(2026, 12, 31), 20));
        MachineScheduleDTO machine = buildMachine("K2025");

        boolean scheduled = service.tryAttachMaintenanceAfterFirstEnding(
                context, machine, dateTime(2026, 4, 20, 7, 0));

        assertTrue(scheduled, "即使到期日期与T日相差超过30天，也必须直接按daysToDue=20进入预警范围");
        assertEquals(1, machine.getMaintenanceWindowList().size());
    }

    @Test
    void tryAttachMaintenanceAfterFirstEnding_shouldNotReplaceDaysToDueWithDueDateDifference() {
        LhScheduleContext context = buildContext(date(2026, 4, 20));
        context.getMaintenancePlanMap().put(
                "K2025", buildPrecisionPlan("K2025", date(2026, 4, 21), 31));
        MachineScheduleDTO machine = buildMachine("K2025");

        boolean scheduled = service.tryAttachMaintenanceAfterFirstEnding(
                context, machine, dateTime(2026, 4, 20, 7, 0));

        assertFalse(scheduled, "即使到期日期距T日仅1天，也必须直接按daysToDue=31判定未进入预警范围");
        assertTrue(machine.getMaintenanceWindowList().isEmpty());
    }

    /**
     * 计划日期早于T日代表已经错过允许提前执行的窗口，即使daysToDue进入强制范围也不得延后触发。
     */
    @Test
    void tryAttachMaintenanceAfterFirstEnding_shouldSkipPlanDateBeforeTDay() {
        LhScheduleContext context = buildContext(date(2026, 7, 24));
        LhPrecisionPlan plan = buildPrecisionPlan(
                "K1902", date(2026, 2, 4), -170);
        context.getMaintenancePlanMap().put("K1902", plan);
        MachineScheduleDTO machine = buildMachine("K1902");

        boolean scheduled = service.tryAttachMaintenanceAfterFirstEnding(
                context, machine, dateTime(2026, 7, 24, 23, 0));

        assertFalse(scheduled, "PLAN_DATE早于T日的历史精度计划不得延后执行");
        assertTrue(machine.getMaintenanceWindowList().isEmpty());
        assertTrue(context.getScheduleLogList().stream()
                        .anyMatch(item -> item.getLogDetail().contains("禁止延后执行")),
                "跳过历史计划的日期原因必须写入过程日志");
    }

    /**
     * 日期硬约束使候选日超过计划日期时必须取消安排，不能顺延到计划日之后。
     */
    @Test
    void prepareMaintenancePlanWindows_shouldNotScheduleAfterPlanDate() {
        LhScheduleContext context = buildContext(date(2026, 7, 24));
        context.getDailyMaintenanceCountMap().put("2026-07-24", 1);
        LhPrecisionPlan plan = buildPrecisionPlan(
                "K1902", date(2026, 7, 24), 0);
        context.setOrderedMaintenancePlanList(Collections.singletonList(plan));
        context.getMaintenancePlanMap().put("K1902", plan);
        MachineScheduleDTO machine = buildMachine("K1902");
        context.getMachineScheduleMap().put("K1902", machine);

        service.prepareMaintenancePlanWindows(context);

        assertTrue(machine.getMaintenanceWindowList().isEmpty(),
                "计划日额度已满时不得把精度顺延到计划日之后");
        assertTrue(context.getScheduleLogList().stream()
                        .anyMatch(item -> item.getLogDetail().contains("禁止延后执行")),
                "无计划日前合规日期时必须记录取消安排原因");
    }

    @Test
    void tryAttachMaintenanceAfterFirstEnding_shouldCreateWindowWhenMachineHasNoRecentOnlineRecord() {
        LhScheduleContext context = buildContext(date(2026, 5, 3));
        context.getMaintenancePlanMap().put("K1105", buildPrecisionPlan("K1105", null, 5));
        MachineScheduleDTO machine = buildMachine("K1105");

        boolean scheduled = service.tryAttachMaintenanceAfterFirstEnding(
                context, machine, dateTime(2026, 5, 3, 6, 0));

        assertTrue(scheduled, "近一个月无 MES 在机记录且当前规格已收尾时，应视为首个规格收尾并安排精度计划");
        assertEquals(1, machine.getMaintenanceWindowList().size(), "满足首个规格收尾条件时，应写入保养窗口");
    }

    @Test
    void tryAttachMaintenanceAfterFirstEnding_shouldUseRealEndingEvenWhenMesHasRecentOnlineRecord() {
        LhScheduleContext context = buildContext(date(2026, 5, 3));
        context.getMaintenancePlanMap().put("K1105", buildPrecisionPlan("K1105", null, 5));
        context.getMachineOnlineInfoMap().put("K1105", buildOnlineInfo("K1105", date(2026, 4, 20)));
        MachineScheduleDTO machine = buildMachine("K1105");

        boolean scheduled = service.tryAttachMaintenanceAfterFirstEnding(
                context, machine, dateTime(2026, 5, 3, 6, 0));

        assertTrue(scheduled, "正常保养以首个SKU真实收尾和30天预警为准，不应被近期MES在机记录拦截");
        assertEquals(1, machine.getMaintenanceWindowList().size());
    }

    @Test
    void tryAttachMaintenanceAfterFirstEnding_shouldSynchronizeSingleControlPairAndCountOnce() {
        LhScheduleContext context = buildContext(date(2026, 4, 20));
        LhPrecisionPlan leftPlan = buildPrecisionPlan("K1501L", date(2026, 5, 10), 20);
        leftPlan.setId(1501L);
        LhPrecisionPlan rightPlan = buildPrecisionPlan("K1501R", date(2026, 5, 10), 20);
        rightPlan.setId(1502L);
        context.getMaintenancePlanMap().put("K1501L", leftPlan);
        context.getMaintenancePlanMap().put("K1501R", rightPlan);
        MachineScheduleDTO leftMachine = buildMachine("K1501L");
        MachineScheduleDTO rightMachine = buildMachine("K1501R");
        context.getMachineScheduleMap().put("K1501L", leftMachine);
        context.getMachineScheduleMap().put("K1501R", rightMachine);

        boolean scheduled = service.tryAttachMaintenanceAfterFirstEnding(
                context, leftMachine, dateTime(2026, 4, 20, 6, 0));

        assertTrue(scheduled);
        assertEquals(1, leftMachine.getMaintenanceWindowList().size());
        assertEquals(1, rightMachine.getMaintenanceWindowList().size());
        assertEquals(1501L, leftMachine.getMaintenanceWindowList().get(0).getPrecisionPlanId());
        assertEquals(1502L, rightMachine.getMaintenanceWindowList().get(0).getPrecisionPlanId());
        assertEquals(1, context.getDailyMaintenanceCountMap().get("2026-04-20").intValue(),
                "L/R同步挂窗只能占用一个物理机台每日额度");
    }

    @Test
    void tryAttachMaintenanceAfterFirstEnding_shouldUseDefaultsWhenParametersInvalid() {
        LhScheduleContext context = buildContext(date(2026, 4, 20));
        context.getLhParamsMap().put(LhScheduleParamConstant.MAINTENANCE_START_HOUR, "-1");
        context.getLhParamsMap().put(LhScheduleParamConstant.MAINTENANCE_DURATION_HOURS, "非法值");
        context.getLhParamsMap().put(LhScheduleParamConstant.CAPSULE_PREHEAT_HOURS, "-2");
        context.getMaintenancePlanMap().put("K1001", buildPrecisionPlan("K1001", date(2026, 5, 10), 20));
        MachineScheduleDTO machine = buildMachine("K1001");

        service.tryAttachMaintenanceAfterFirstEnding(
                context, machine, dateTime(2026, 4, 20, 6, 0));

        MachineMaintenanceWindowDTO window = machine.getMaintenanceWindowList().get(0);
        assertEquals(dateTime(2026, 4, 20, 8, 0), window.getMaintenanceStartTime());
        assertEquals(dateTime(2026, 4, 20, 15, 0), window.getMaintenanceEndTime());
        assertEquals(dateTime(2026, 4, 20, 17, 30), window.getProductionResumeTime());
    }

    /**
     * 未来保养开始前机台仍应保持原就绪时间；只有真正落入保养或预热区间时才顺延到最早开产时间。
     */
    @Test
    void resolveMaintenanceResumeProductionTime_shouldOnlyDelayInsideMaintenanceOccupation() {
        LhScheduleContext context = buildContext(date(2026, 4, 20));
        context.getMaintenancePlanMap().put("K1001", buildPrecisionPlan("K1001", date(2026, 4, 20), 0));
        MachineScheduleDTO machine = buildMachine("K1001");
        machine.setHasMaintenancePlan(true);
        machine.setMaintenancePlanTime(date(2026, 4, 20));
        MachineMaintenanceWindowDTO window = new MachineMaintenanceWindowDTO();
        window.setMachineCode("K1001");
        window.setMaintenanceStartTime(dateTime(2026, 4, 20, 8, 0));
        window.setMaintenanceEndTime(dateTime(2026, 4, 20, 15, 0));
        window.setProductionResumeTime(dateTime(2026, 4, 20, 17, 30));
        machine.getMaintenanceWindowList().add(window);

        assertEquals(dateTime(2026, 4, 19, 22, 0), service.resolveMaintenanceResumeProductionTime(
                context, machine, dateTime(2026, 4, 19, 22, 0)),
                "未来保养不得提前锁死保养日前的正常生产");
        assertEquals(dateTime(2026, 4, 20, 7, 59), service.resolveMaintenanceResumeProductionTime(
                context, machine, dateTime(2026, 4, 20, 7, 59)),
                "保养开始前的就绪时间不得无条件推迟到预热完成");
        assertEquals(dateTime(2026, 4, 20, 17, 30), service.resolveMaintenanceResumeProductionTime(
                context, machine, dateTime(2026, 4, 20, 8, 0)),
                "保养开始边界必须顺延到预热完成");
        assertEquals(dateTime(2026, 4, 20, 17, 30), service.resolveMaintenanceResumeProductionTime(
                context, machine, dateTime(2026, 4, 20, 16, 0)),
                "胶囊预热期间必须顺延到预热完成");
        assertEquals(dateTime(2026, 4, 20, 17, 30), service.resolveMaintenanceResumeProductionTime(
                context, machine, dateTime(2026, 4, 20, 17, 30)),
                "等于预热完成边界时可以立即生产");
    }

    /**
     * 已进入当前固定班次范围的保养属于本批必须执行任务，保养后的SKU必须等待预热完成；
     * 超出当前班次范围的未来保养不得提前锁机。
     */
    @Test
    void resolveMaintenanceResumeProductionTime_shouldBlockCurrentWindowButNotFutureWindow() {
        LhScheduleContext context = buildContext(date(2026, 4, 20));
        context.setScheduleTargetDate(date(2026, 4, 22));
        context.setScheduleWindowShifts(
                LhScheduleTimeUtil.buildDefaultScheduleShifts(context, context.getScheduleDate()));

        MachineScheduleDTO currentWindowMachine = buildMachine("K1001");
        currentWindowMachine.setHasMaintenancePlan(true);
        MachineMaintenanceWindowDTO currentWindow = new MachineMaintenanceWindowDTO();
        currentWindow.setMaintenanceStartTime(dateTime(2026, 4, 20, 8, 0));
        currentWindow.setMaintenanceEndTime(dateTime(2026, 4, 20, 15, 0));
        currentWindow.setProductionResumeTime(dateTime(2026, 4, 20, 17, 30));
        currentWindowMachine.getMaintenanceWindowList().add(currentWindow);

        assertEquals(dateTime(2026, 4, 20, 17, 30), service.resolveMaintenanceResumeProductionTime(
                context, currentWindowMachine, dateTime(2026, 4, 20, 7, 59)),
                "当前班次范围内即将执行保养时，后续SKU必须等待预热完成");

        MachineScheduleDTO futureMachine = buildMachine("K1002");
        futureMachine.setHasMaintenancePlan(true);
        MachineMaintenanceWindowDTO futureWindow = new MachineMaintenanceWindowDTO();
        futureWindow.setMaintenanceStartTime(dateTime(2026, 5, 10, 8, 0));
        futureWindow.setMaintenanceEndTime(dateTime(2026, 5, 10, 15, 0));
        futureWindow.setProductionResumeTime(dateTime(2026, 5, 10, 17, 30));
        futureMachine.getMaintenanceWindowList().add(futureWindow);

        assertEquals(dateTime(2026, 4, 20, 7, 59), service.resolveMaintenanceResumeProductionTime(
                context, futureMachine, dateTime(2026, 4, 20, 7, 59)),
                "窗口外未来保养不得提前锁死当前机台");
    }

    /**
     * 相同机台、原就绪时间和恢复时间在候选预演中重复计算时，只保留一条顺延过程日志。
     */
    @Test
    void resolveMaintenanceResumeProductionTime_shouldDeduplicateSameDelayLog() {
        LhScheduleContext context = buildContext(date(2026, 4, 20));
        context.getMaintenancePlanMap().put("K1001", buildPrecisionPlan("K1001", date(2026, 4, 20), 0));
        MachineScheduleDTO machine = buildMachine("K1001");
        machine.setHasMaintenancePlan(true);
        machine.setMaintenancePlanTime(date(2026, 4, 20));
        MachineMaintenanceWindowDTO window = new MachineMaintenanceWindowDTO();
        window.setMachineCode("K1001");
        window.setMaintenanceStartTime(dateTime(2026, 4, 20, 8, 0));
        window.setMaintenanceEndTime(dateTime(2026, 4, 20, 15, 0));
        window.setProductionResumeTime(dateTime(2026, 4, 20, 17, 30));
        machine.getMaintenanceWindowList().add(window);

        service.resolveMaintenanceResumeProductionTime(context, machine, dateTime(2026, 4, 20, 16, 0));
        service.resolveMaintenanceResumeProductionTime(context, machine, dateTime(2026, 4, 20, 16, 0));

        long delayLogCount = context.getScheduleLogList().stream()
                .filter(item -> item.getLogDetail().contains("保养及胶囊预热占用导致机台就绪时间顺延"))
                .count();
        assertEquals(1L, delayLogCount, "完全相同的就绪时间调整只能写一条过程日志");
    }

    /**
     * 维保重叠专用切换时长只能在切换参考时刻真实落入保养窗口时启用。
     */
    @Test
    void shouldApplyMaintenanceOverlapSwitchRule_shouldIgnoreFutureMaintenance() {
        LhScheduleContext context = buildContext(date(2026, 4, 20));
        MachineScheduleDTO machine = buildMachine("K1001");
        machine.setHasMaintenancePlan(true);
        machine.setMaintenancePlanTime(date(2026, 4, 20));
        MachineMaintenanceWindowDTO window = new MachineMaintenanceWindowDTO();
        window.setMachineCode("K1001");
        window.setMaintenanceStartTime(dateTime(2026, 4, 20, 8, 0));
        window.setMaintenanceEndTime(dateTime(2026, 4, 20, 15, 0));
        machine.getMaintenanceWindowList().add(window);

        assertFalse(service.shouldApplyMaintenanceOverlapSwitchRule(
                context, machine, dateTime(2026, 4, 19, 22, 0)),
                "未来保养不得提前启用维保重叠专用切换时长");
        assertFalse(service.shouldApplyMaintenanceOverlapSwitchRule(
                context, machine, dateTime(2026, 4, 20, 8, 0)),
                "最新规则禁止任何换模或换活字块与精度计划并行");
        assertFalse(service.shouldApplyMaintenanceOverlapSwitchRule(
                context, machine, dateTime(2026, 4, 20, 15, 0)),
                "保养结束边界不再属于保养物理重叠区间");
    }

    /**
     * 正规换模与精度窗口冲突时必须整体顺延到胶囊预热结束，不再并行执行。
     */
    @Test
    void delaySwitchStartByMaintenance_shouldDelayWholeSwitchAfterPrecisionPreheat() {
        LhScheduleContext context = buildContext(date(2026, 4, 20));
        MachineScheduleDTO machine = buildMachine("K1001");
        MachineMaintenanceWindowDTO window = new MachineMaintenanceWindowDTO();
        window.setMachineCode("K1001");
        window.setMaintenanceStartTime(dateTime(2026, 4, 20, 8, 0));
        window.setMaintenanceEndTime(dateTime(2026, 4, 20, 15, 0));
        window.setProductionResumeTime(dateTime(2026, 4, 20, 17, 30));
        machine.getMaintenanceWindowList().add(window);

        Date delayedStartTime = service.delaySwitchStartByMaintenance(
                machine, dateTime(2026, 4, 20, 6, 0), 8);

        assertEquals(dateTime(2026, 4, 20, 17, 30), delayedStartTime,
                "06:00开始的8小时换模会跨越精度窗口，必须整体顺延到预热结束");
    }

    @Test
    void resolvePrecisionCandidateRejectReason_shouldAcceptOnlyWholePendingQtyBeforeSix() {
        LhScheduleContext context = buildContext(date(2026, 4, 20));
        context.getLhParamsMap().put(LhScheduleParamConstant.PRECISION_PRE_INSERT_MAX_QTY, "50");
        MachineScheduleDTO machine = buildMachine("K1001");
        MachineMaintenanceWindowDTO window = new MachineMaintenanceWindowDTO();
        window.setPlanDate(date(2026, 4, 21));
        window.setDaysToDue(10);
        window.setPreInsertAllowed(true);
        window.setProductionCutoffTime(dateTime(2026, 4, 21, 6, 0));
        window.setProductionResumeTime(dateTime(2026, 4, 21, 17, 30));
        machine.getMaintenanceWindowList().add(window);
        com.zlt.aps.lh.api.domain.dto.SkuScheduleDTO sku =
                new com.zlt.aps.lh.api.domain.dto.SkuScheduleDTO();
        sku.setMaterialCode("MAT-50");

        assertEquals("", service.resolvePrecisionCandidateRejectReason(
                context, machine, sku, 50, 50,
                dateTime(2026, 4, 20, 22, 0),
                dateTime(2026, 4, 21, 0, 0),
                dateTime(2026, 4, 21, 6, 0)),
                "最终收尾恰好等于06:00且完整排完50条时应接受");
        assertTrue(service.resolvePrecisionCandidateRejectReason(
                context, machine, sku, 50, 49,
                dateTime(2026, 4, 20, 22, 0),
                dateTime(2026, 4, 21, 0, 0),
                dateTime(2026, 4, 21, 5, 59)).contains("禁止截断SKU"),
                "不得只截取部分待排量填充空闲时间");
        assertTrue(service.resolvePrecisionCandidateRejectReason(
                context, machine, sku, 50, 50,
                dateTime(2026, 4, 20, 22, 0),
                dateTime(2026, 4, 21, 0, 0),
                dateTime(2026, 4, 21, 6, 1)).contains("晚于"),
                "晚于06:00一分钟必须排除");
        context.getLhParamsMap().put(LhScheduleParamConstant.PRECISION_PRE_INSERT_MAX_QTY, "0");
        assertTrue(service.resolvePrecisionCandidateRejectReason(
                context, machine, sku, 51, 51,
                dateTime(2026, 4, 20, 22, 0),
                dateTime(2026, 4, 21, 0, 0),
                dateTime(2026, 4, 21, 5, 59)).contains("50"),
                "阈值配置为0时必须回到默认50，不能静默降为1或取消限制");
    }

    /**
     * 精度窗口被每日台数等硬约束顺延到当前滚动窗口之外时，不得提前锁死本轮正常生产。
     */
    @Test
    void resolvePrecisionCandidateRejectReason_shouldIgnoreWindowOutsideCurrentScheduleRange() {
        LhScheduleContext context = buildContext(date(2026, 4, 20));
        context.setWindowEndDate(date(2026, 4, 22));
        MachineScheduleDTO machine = buildMachine("K1001");
        MachineMaintenanceWindowDTO window = new MachineMaintenanceWindowDTO();
        window.setPlanDate(date(2026, 5, 10));
        window.setDaysToDue(20);
        window.setPreInsertAllowed(false);
        window.setProductionCutoffTime(dateTime(2026, 5, 10, 6, 0));
        window.setProductionResumeTime(dateTime(2026, 5, 10, 17, 30));
        machine.getMaintenanceWindowList().add(window);
        com.zlt.aps.lh.api.domain.dto.SkuScheduleDTO sku =
                new com.zlt.aps.lh.api.domain.dto.SkuScheduleDTO();
        sku.setMaterialCode("MAT-NORMAL");

        assertEquals("", service.resolvePrecisionCandidateRejectReason(
                context, machine, sku, 200, 24,
                dateTime(2026, 4, 20, 6, 0),
                dateTime(2026, 4, 20, 14, 0),
                dateTime(2026, 4, 20, 22, 0)),
                "当前滚动窗口之外的未来精度计划不得把正常新增SKU误判为精度前插排");
    }

    @Test
    void prepareMaintenancePlanWindows_shouldPrioritizeUrgentThenMachineCode() {
        LhScheduleContext context = buildContext(date(2026, 4, 20));
        LhPrecisionPlan ordinaryPlan = buildPrecisionPlan(
                "K2002", date(2026, 4, 30), 10);
        ordinaryPlan.setPlanDate(date(2026, 4, 20));
        LhPrecisionPlan urgentLargeCodePlan = buildPrecisionPlan(
                "K2001", date(2026, 4, 22), 2);
        urgentLargeCodePlan.setPlanDate(date(2026, 4, 20));
        LhPrecisionPlan urgentSmallCodePlan = buildPrecisionPlan(
                "K1001", date(2026, 4, 22), 2);
        urgentSmallCodePlan.setPlanDate(date(2026, 4, 20));
        context.setOrderedMaintenancePlanList(Arrays.asList(
                ordinaryPlan, urgentLargeCodePlan, urgentSmallCodePlan));
        MachineScheduleDTO ordinaryMachine = buildMachine("K2002");
        MachineScheduleDTO urgentLargeCodeMachine = buildMachine("K2001");
        MachineScheduleDTO urgentSmallCodeMachine = buildMachine("K1001");
        context.getMachineScheduleMap().put("K2002", ordinaryMachine);
        context.getMachineScheduleMap().put("K2001", urgentLargeCodeMachine);
        context.getMachineScheduleMap().put("K1001", urgentSmallCodeMachine);

        service.prepareMaintenancePlanWindows(context);

        assertEquals("K1001", context.getOrderedMaintenancePlanList().get(0).getMachineCode(),
                "同到期天数、同计划日期必须按物理机台编码升序");
        assertEquals("K2001", context.getOrderedMaintenancePlanList().get(1).getMachineCode());
        assertEquals("K2002", context.getOrderedMaintenancePlanList().get(2).getMachineCode(),
                "普通30天内计划必须排在3天强制计划之后");
        assertEquals(dateTime(2026, 4, 20, 8, 0),
                urgentSmallCodeMachine.getMaintenanceWindowList().get(0).getMaintenanceStartTime());
        assertTrue(urgentLargeCodeMachine.getMaintenanceWindowList().isEmpty(),
                "同日额度被更高排序机台占用后，第二台不得延后到计划日期之后");
    }

    /**
     * 普通精度计划在前SKU收尾时间未知时必须暂缓，不能误占最早执行日。
     */
    @Test
    void prepareMaintenancePlanWindows_shouldDeferOrdinaryPlanWhenEndingTimeUnknown() {
        LhScheduleContext context = buildContext(date(2026, 4, 20));
        LhPrecisionPlan plan = buildPrecisionPlan(
                "K1001", date(2026, 5, 10), 20);
        context.setOrderedMaintenancePlanList(Collections.singletonList(plan));
        context.getMaintenancePlanMap().put("K1001", plan);
        MachineScheduleDTO machine = buildMachine("K1001");
        machine.setCurrentMaterialCode("MAT-RUNNING");
        machine.setEstimatedEndTime(null);
        context.getMachineScheduleMap().put(machine.getMachineCode(), machine);

        service.prepareMaintenancePlanWindows(context);

        assertTrue(machine.getMaintenanceWindowList().isEmpty(),
                "4～30天普通计划无法确认前SKU收尾时不得提前挂载精度窗口");
        assertTrue(context.getScheduleLogList().stream()
                        .anyMatch(item -> item.getLogDetail().contains("收尾时间未知")),
                "暂缓原因必须进入过程日志，便于后续滚动排程审计");

        boolean attachedAfterEnding = service.tryAttachMaintenanceAfterFirstEnding(
                context, machine, dateTime(2026, 4, 20, 8, 0));

        assertTrue(attachedAfterEnding,
                "续作主链取得真实收尾时间后必须补做精度决策，不能整批永久跳过");
        assertEquals(dateTime(2026, 4, 21, 8, 0),
                machine.getMaintenanceWindowList().get(0).getMaintenanceStartTime());
        assertTrue(machine.getMaintenanceWindowList().get(0).isPreInsertAllowed(),
                "06:00后自然收尾并顺延到次日时，应开放收尾至次日06:00的小余量插排窗口");
        assertTrue(context.getMaintenanceDeferredPhysicalMachineCodeSet().isEmpty(),
                "补决策成功后必须清除暂缓标记，防止重复挂窗");
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
        context.getLhParamsMap().put(LhScheduleParamConstant.CAPSULE_PREHEAT_HOURS, "2.5");
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
        // 回归用例默认让计划日期与传入到期日期一致；专门验证DUE_DATE缺失的用例仍提供未来PLAN_DATE，
        // 以便单独验证daysToDue口径，不与本次新增的PLAN_DATE >= T准入条件混淆。
        plan.setPlanDate(Objects.nonNull(dueDate) ? dueDate : date(2026, 12, 31));
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
