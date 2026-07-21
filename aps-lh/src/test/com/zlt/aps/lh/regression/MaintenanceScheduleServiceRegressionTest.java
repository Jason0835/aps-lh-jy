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
import java.util.Collections;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        MachineScheduleDTO machine = buildMachine("K1001");

        boolean scheduled = service.tryAttachMaintenanceAfterFirstEnding(
                context, machine, dateTime(2026, 4, 20, 6, 30));

        assertTrue(scheduled, "到期 30 天内且首次收尾后应安排保养");
        assertEquals(1, machine.getMaintenanceWindowList().size());
        MachineMaintenanceWindowDTO window = machine.getMaintenanceWindowList().get(0);
        assertEquals(dateTime(2026, 4, 20, 8, 0), window.getMaintenanceStartTime());
        assertEquals(dateTime(2026, 4, 20, 15, 0), window.getMaintenanceEndTime());
        assertEquals(dateTime(2026, 4, 20, 17, 30), window.getProductionResumeTime());
        assertEquals(1, context.getDailyMaintenanceCountMap().get("2026-04-20").intValue());
        assertTrue(context.getScheduleLogList().stream()
                        .anyMatch(item -> "精准计划最终安排".equals(item.getTitle())
                                && item.getLogDetail().contains("最早开产=2026-04-20 17:30:00")),
                "最终保养开始、结束和最早开产必须写入排程过程日志");
    }

    @Test
    void tryAttachMaintenanceAfterFirstEnding_shouldDelayToNextDayWhenEndingAfterEight() {
        LhScheduleContext context = buildContext(date(2026, 4, 20));
        context.getMaintenancePlanMap().put("K1001", buildPrecisionPlan("K1001", date(2026, 5, 10), 20));
        MachineScheduleDTO machine = buildMachine("K1001");

        boolean scheduled = service.tryAttachMaintenanceAfterFirstEnding(
                context, machine, dateTime(2026, 4, 20, 8, 1));

        assertTrue(scheduled);
        assertEquals(dateTime(2026, 4, 21, 8, 0),
                machine.getMaintenanceWindowList().get(0).getMaintenanceStartTime(),
                "08:00后收尾不得回排当天保养，必须从下一自然日08:00开始寻找");
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
    void tryAttachMaintenanceAfterFirstEnding_shouldDelayMonthEndAndCrossYear() {
        LhScheduleContext context = buildContext(date(2026, 12, 31));
        context.getMaintenancePlanMap().put("K1001", buildPrecisionPlan("K1001", date(2026, 12, 31), 0));
        MachineScheduleDTO machine = buildMachine("K1001");

        boolean scheduled = service.tryAttachMaintenanceAfterFirstEnding(
                context, machine, dateTime(2026, 12, 31, 7, 59));

        assertTrue(scheduled);
        assertEquals(dateTime(2027, 1, 1, 8, 0),
                machine.getMaintenanceWindowList().get(0).getMaintenanceStartTime(),
                "12月31日盘点日应顺延并正确跨年");
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
                allowedContext, allowedMachine, dateTime(2026, 5, 3, 7, 0));

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
        assertEquals("长期在机强制下机", window.getTriggerReason());
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
                context, leftMachine, dateTime(2026, 4, 20, 8, 0));

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
                context, machine, dateTime(2026, 4, 20, 7, 0));

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
        assertTrue(service.shouldApplyMaintenanceOverlapSwitchRule(
                context, machine, dateTime(2026, 4, 20, 8, 0)),
                "保养开始边界应启用维保重叠规则");
        assertFalse(service.shouldApplyMaintenanceOverlapSwitchRule(
                context, machine, dateTime(2026, 4, 20, 15, 0)),
                "保养结束边界不再属于保养物理重叠区间");
    }

    /**
     * 正规换模与精度计划重叠时必须从精度计划开始点并行，恢复时间取两个任务的最大结束时间。
     */
    @Test
    void resolveParallelMouldChangeReadyTime_shouldTakeMaximumEndTime() {
        LhScheduleContext context = buildContext(date(2026, 4, 20));
        MachineScheduleDTO machine = buildMachine("K1001");
        MachineMaintenanceWindowDTO window = new MachineMaintenanceWindowDTO();
        window.setMachineCode("K1001");
        window.setMaintenanceStartTime(dateTime(2026, 4, 20, 8, 0));
        window.setMaintenanceEndTime(dateTime(2026, 4, 20, 15, 0));
        window.setProductionResumeTime(dateTime(2026, 4, 20, 17, 30));
        machine.getMaintenanceWindowList().add(window);

        Date nightCandidateParallelStartTime = service.resolveParallelMouldChangeStartTime(
                context, machine, dateTime(2026, 4, 20, 0, 0), 8);
        Date parallelStartTime = service.resolveParallelMouldChangeStartTime(
                context, machine, dateTime(2026, 4, 20, 6, 0), 8);
        Date normalMouldChangeEndTime = LhScheduleTimeUtil.addHours(parallelStartTime, 8);
        Date maintenanceLongerReadyTime = service.resolveParallelMouldChangeReadyTime(
                context, machine, parallelStartTime, normalMouldChangeEndTime);
        Date longMouldChangeEndTime = LhScheduleTimeUtil.addHours(parallelStartTime, 10);
        Date mouldChangeLongerReadyTime = service.resolveParallelMouldChangeReadyTime(
                context, machine, parallelStartTime, longMouldChangeEndTime);

        assertEquals(dateTime(2026, 4, 20, 8, 0), nightCandidateParallelStartTime,
                "晚班候选必须先对齐到允许换模的早班，再与精度计划并行");
        assertEquals(dateTime(2026, 4, 20, 8, 0), parallelStartTime,
                "前序SKU在08:00前结束时，正规换模应与精度计划同时开始");
        assertEquals(dateTime(2026, 4, 20, 17, 30), maintenanceLongerReadyTime,
                "默认8小时换模短于7小时保养加2.5小时预热，必须17:30恢复生产");
        assertEquals(dateTime(2026, 4, 20, 18, 0), mouldChangeLongerReadyTime,
                "换模时长更长时必须取换模结束时间");
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
