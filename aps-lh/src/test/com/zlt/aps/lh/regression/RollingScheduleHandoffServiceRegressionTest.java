package com.zlt.aps.lh.regression;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.zlt.aps.lh.api.domain.dto.MachineScheduleDTO;
import com.zlt.aps.lh.api.domain.entity.LhMouldChangePlan;
import com.zlt.aps.lh.api.domain.entity.LhScheduleResult;
import com.zlt.aps.lh.api.domain.vo.LhShiftConfigVO;
import com.zlt.aps.lh.api.enums.ShiftEnum;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.service.impl.RollingScheduleHandoffService;
import com.zlt.aps.lh.util.LhScheduleTimeUtil;
import com.zlt.aps.lh.util.ShiftFieldUtil;
import com.zlt.aps.mdm.api.domain.entity.MdmMaterialInfo;
import com.zlt.aps.mp.api.domain.entity.FactoryMonthPlanProductionFinalResult;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 滚动排程衔接回归测试。
 */
class RollingScheduleHandoffServiceRegressionTest {

    private final RollingScheduleHandoffService service = new RollingScheduleHandoffService();

    @Test
    void apply_shouldInheritOverlapShiftsAndUpdateMachineState() {
        LhScheduleContext context = newRollingContext();
        context.setMonthPlanList(Collections.singletonList(buildPlan("MAT-A")));
        context.setPreviousScheduleResultList(Collections.singletonList(buildPreviousResult()));
        context.setPreviousMouldChangePlanList(Collections.singletonList(buildPreviousTypeBlockPlan()));

        service.apply(context);

        assertEquals(1, context.getRollingInheritedScheduleResultList().size());
        LhScheduleResult inherited = context.getRollingInheritedScheduleResultList().get(0);
        assertTrue(inherited.isRollingInherited());
        assertEquals("LHPC20260426001", inherited.getBatchNo());
        assertEquals(date(2026, 4, 26), inherited.getScheduleDate());
        assertEquals(date(2026, 4, 24), inherited.getRealScheduleDate());
        assertEquals(10, ShiftFieldUtil.getShiftPlanQty(inherited, 1).intValue());
        assertEquals(50, ShiftFieldUtil.getShiftPlanQty(inherited, 5).intValue());
        assertNull(ShiftFieldUtil.getShiftPlanQty(inherited, 6));
        assertEquals(150, inherited.getDailyPlanQty().intValue());
        assertEquals(150, context.getInheritedPlanQtyMap().get("MAT-A").intValue());
        assertEquals(dateTime(2026, 4, 25, 22, 0), inherited.getSpecEndTime());

        MachineScheduleDTO machine = context.getMachineScheduleMap().get("M1");
        assertEquals("MAT-A", machine.getCurrentMaterialCode());
        assertEquals("物料A", machine.getCurrentMaterialDesc());
        assertEquals(dateTime(2026, 4, 25, 22, 0), machine.getEstimatedEndTime());
        assertEquals(machine.getCurrentMaterialCode(), context.getInitialMachineScheduleMap().get("M1").getCurrentMaterialCode());
        assertEquals(machine.getEstimatedEndTime(), context.getInitialMachineScheduleMap().get("M1").getEstimatedEndTime());
        assertEquals(1, context.getMachineAssignmentMap().get("M1").size());

        assertEquals(1, context.getMouldChangePlanList().size());
        LhMouldChangePlan inheritedPlan = context.getMouldChangePlanList().get(0);
        assertEquals("LHPC20260426001", inheritedPlan.getLhResultBatchNo());
        assertEquals(date(2026, 4, 26), inheritedPlan.getScheduleDate());
        assertEquals("02", inheritedPlan.getChangeMouldType());
        assertNull(inheritedPlan.getOrderNo());
    }

    @Test
    void apply_shouldMoveIdleMachineToAppendStartWhenRollingOverlapExists() {
        LhScheduleContext context = newRollingContext();
        context.setMonthPlanList(Collections.singletonList(buildPlan("MAT-A")));
        context.setPreviousScheduleResultList(Collections.singletonList(buildPreviousResult()));

        MachineScheduleDTO idleMachine = new MachineScheduleDTO();
        idleMachine.setMachineCode("M2");
        idleMachine.setCurrentMaterialCode("MAT-IDLE");
        idleMachine.setCurrentMaterialDesc("空闲物料");
        idleMachine.setEstimatedEndTime(dateTime(2026, 4, 24, 6, 0));
        context.getMachineScheduleMap().put("M2", idleMachine);
        context.getInitialMachineScheduleMap().put("M2", idleMachine);

        service.apply(context);

        assertEquals(dateTime(2026, 4, 25, 22, 0), context.getMachineScheduleMap().get("M2").getEstimatedEndTime());
        assertEquals(dateTime(2026, 4, 25, 22, 0), context.getInitialMachineScheduleMap().get("M2").getEstimatedEndTime());
    }

    @Test
    void apply_shouldInheritOverlapShiftsWhenPreviousShiftStartTimeChanged() {
        LhScheduleContext context = newRollingContext();
        context.setMonthPlanList(Collections.singletonList(buildPlan("MAT-A")));
        context.setPreviousScheduleResultList(Collections.singletonList(buildPreviousResultWithChangedShiftTime()));

        service.apply(context);

        assertEquals(1, context.getRollingInheritedScheduleResultList().size());
        LhScheduleResult inherited = context.getRollingInheritedScheduleResultList().get(0);
        assertEquals(10, ShiftFieldUtil.getShiftPlanQty(inherited, 1).intValue());
        assertEquals(20, ShiftFieldUtil.getShiftPlanQty(inherited, 2).intValue());
        assertEquals(30, ShiftFieldUtil.getShiftPlanQty(inherited, 3).intValue());
        assertEquals(40, ShiftFieldUtil.getShiftPlanQty(inherited, 4).intValue());
        assertEquals(50, ShiftFieldUtil.getShiftPlanQty(inherited, 5).intValue());
        assertEquals(150, inherited.getDailyPlanQty().intValue());
    }

    @Test
    void apply_shouldUpdateMachinePreviousProSizeFromInheritedMaterial() {
        LhScheduleContext context = newRollingContext();
        context.setMonthPlanList(Collections.singletonList(buildPlan("MAT-A")));
        context.setPreviousScheduleResultList(Collections.singletonList(buildPreviousResult()));
        context.getMaterialInfoMap().put("MAT-A", buildMaterialInfo("MAT-A", "18"));
        context.getMachineScheduleMap().get("M1").setPreviousProSize("16");
        context.getInitialMachineScheduleMap().get("M1").setPreviousProSize("16");

        service.apply(context);

        assertEquals("18", context.getMachineScheduleMap().get("M1").getPreviousProSize());
        assertEquals("18", context.getInitialMachineScheduleMap().get("M1").getPreviousProSize());
    }

    @Test
    void apply_shouldUseCurrentVersionOnInheritedResultWhenPreviousVersionIsEmpty() {
        LhScheduleContext context = newRollingContext();
        context.setMonthPlanList(Collections.singletonList(buildPlan("MAT-A")));
        LhScheduleResult previousResult = buildPreviousResult();
        previousResult.setMonthPlanVersion(null);
        previousResult.setProductionVersion(null);
        context.setPreviousScheduleResultList(Collections.singletonList(previousResult));

        service.apply(context);

        LhScheduleResult inherited = context.getRollingInheritedScheduleResultList().get(0);
        assertEquals("MP-V1", inherited.getMonthPlanVersion());
        assertEquals("PV-V1", inherited.getProductionVersion());
    }

    @Test
    void apply_shouldResolveAppendStartFromConfiguredTargetWorkDateShift() {
        LhScheduleContext context = newRollingContext();
        context.setScheduleWindowShifts(buildConfiguredScheduleShifts(context.getScheduleDate(), 7, 15, 23));
        context.setMonthPlanList(Collections.singletonList(buildPlan("MAT-A")));
        context.setPreviousScheduleResultList(Collections.singletonList(buildPreviousResultWithLateShiftBoundary()));

        MachineScheduleDTO idleMachine = new MachineScheduleDTO();
        idleMachine.setMachineCode("M2");
        idleMachine.setCurrentMaterialCode("MAT-IDLE");
        idleMachine.setCurrentMaterialDesc("空闲物料");
        idleMachine.setEstimatedEndTime(dateTime(2026, 4, 24, 7, 0));
        context.getMachineScheduleMap().put("M2", idleMachine);
        context.getInitialMachineScheduleMap().put("M2", idleMachine);

        service.apply(context);

        assertEquals(dateTime(2026, 4, 25, 23, 0), context.getMachineScheduleMap().get("M2").getEstimatedEndTime());
        assertEquals(dateTime(2026, 4, 25, 23, 0), context.getInitialMachineScheduleMap().get("M2").getEstimatedEndTime());
    }

    @Test
    void apply_shouldReturnEarlyWhenPreviousScheduleResultListIsEmpty() {
        LhScheduleContext context = newRollingContext();
        context.setMonthPlanList(Collections.singletonList(buildPlan("MAT-A")));
        context.setPreviousScheduleResultList(Collections.emptyList());

        service.apply(context);

        assertTrue(context.getRollingInheritedScheduleResultList().isEmpty());
        assertTrue(context.getInheritedPlanQtyMap().isEmpty());
    }

    @Test
    void apply_shouldInterruptWhenMonthPlanVersionMismatch() {
        LhScheduleContext context = newRollingContext();
        FactoryMonthPlanProductionFinalResult plan = buildPlan("MAT-A");
        plan.setMonthPlanVersion("MP-V2");
        context.setMonthPlanList(Collections.singletonList(plan));
        context.setPreviousScheduleResultList(Collections.singletonList(buildPreviousResult()));

        service.apply(context);

        assertTrue(context.isInterrupted());
        assertTrue(context.getInterruptReason().contains("月计划版本与本次不一致"));
    }

    @Test
    void apply_shouldInterruptWhenPreviousShiftCrossesInheritedWindowBoundary() {
        LhScheduleContext context = newRollingContext();
        context.setMonthPlanList(Collections.singletonList(buildPlan("MAT-A")));
        context.setPreviousScheduleResultList(Collections.singletonList(buildPreviousResultCrossingAppendBoundary()));

        service.apply(context);

        assertTrue(context.isInterrupted());
        assertTrue(context.getInterruptReason().contains("跨越继承窗口边界"));
    }

    @Test
    void apply_shouldLogInheritedWindowShiftMappingsAndSummary() {
        LhScheduleContext context = newRollingContext();
        context.setMonthPlanList(Collections.singletonList(buildPlan("MAT-A")));
        context.setPreviousScheduleResultList(Collections.singletonList(buildPreviousResult()));

        ListAppender<ILoggingEvent> appender = attachAppender();
        try {
            service.apply(context);
        } finally {
            detachAppender(appender);
        }

        List<String> messages = appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .collect(Collectors.toList());
        assertTrue(messages.stream().anyMatch(message -> message.contains("滚动排程衔接窗口")
                        && message.contains("继承起点")
                        && message.contains("追加起点")),
                "应输出继承窗口日志，便于确认滚动衔接边界");
        assertTrue(messages.stream().anyMatch(message -> message.contains("滚动衔接结果明细")
                        && message.contains("班次映射")
                        && message.contains("MAT-A")),
                "应输出继承结果班次映射日志，便于核对班次承接关系");
        assertTrue(messages.stream().anyMatch(message -> message.contains("滚动排程衔接完成")
                        && message.contains("继承计划量汇总")),
                "应输出继承计划量汇总日志，便于核对 inheritedPlanQtyMap");
    }

    private ListAppender<ILoggingEvent> attachAppender() {
        Logger logger = (Logger) LoggerFactory.getLogger(RollingScheduleHandoffService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private void detachAppender(ListAppender<ILoggingEvent> appender) {
        Logger logger = (Logger) LoggerFactory.getLogger(RollingScheduleHandoffService.class);
        logger.detachAppender(appender);
    }

    private LhScheduleContext newRollingContext() {
        LhScheduleContext context = new LhScheduleContext();
        context.setFactoryCode("116");
        context.setBatchNo("LHPC20260426001");
        context.setMonthPlanVersion("MP-V1");
        context.setProductionVersion("PV-V1");
        context.setScheduleDate(date(2026, 4, 24));
        context.setScheduleTargetDate(date(2026, 4, 26));
        context.setScheduleWindowShifts(LhScheduleTimeUtil.buildDefaultScheduleShifts(context, context.getScheduleDate()));

        MachineScheduleDTO machine = new MachineScheduleDTO();
        machine.setMachineCode("M1");
        machine.setMachineName("机台1");
        machine.setCurrentMaterialCode("MAT-OLD");
        machine.setCurrentMaterialDesc("旧物料");
        machine.setEstimatedEndTime(dateTime(2026, 4, 24, 6, 0));
        context.setMachineScheduleMap(new LinkedHashMap<>());
        context.setInitialMachineScheduleMap(new LinkedHashMap<>());
        context.getMachineScheduleMap().put("M1", machine);
        context.getInitialMachineScheduleMap().put("M1", machine);
        return context;
    }

    private FactoryMonthPlanProductionFinalResult buildPlan(String materialCode) {
        FactoryMonthPlanProductionFinalResult plan = new FactoryMonthPlanProductionFinalResult();
        plan.setMaterialCode(materialCode);
        plan.setMaterialDesc("物料A");
        plan.setStructureName("S1");
        plan.setMonthPlanVersion("MP-V1");
        plan.setProductionVersion("PV-V1");
        return plan;
    }

    private LhScheduleResult buildPreviousResult() {
        LhScheduleResult result = new LhScheduleResult();
        result.setFactoryCode("116");
        result.setBatchNo("LHPC20260425001");
        result.setScheduleDate(date(2026, 4, 25));
        result.setRealScheduleDate(date(2026, 4, 23));
        result.setMonthPlanVersion("MP-V1");
        result.setProductionVersion("PV-V1");
        result.setLhMachineCode("M1");
        result.setLhMachineName("机台1");
        result.setMaterialCode("MAT-A");
        result.setMaterialDesc("物料A");
        result.setSpecCode("SPEC-A");
        result.setScheduleType("01");
        result.setIsChangeMould("0");
        result.setIsTypeBlock("0");
        result.setSpecEndTime(dateTime(2026, 4, 25, 22, 0));
        ShiftFieldUtil.setShiftPlanQty(result, 4, 10,
                dateTime(2026, 4, 24, 6, 0), dateTime(2026, 4, 24, 14, 0));
        ShiftFieldUtil.setShiftPlanQty(result, 5, 20,
                dateTime(2026, 4, 24, 14, 0), dateTime(2026, 4, 24, 22, 0));
        ShiftFieldUtil.setShiftPlanQty(result, 6, 30,
                dateTime(2026, 4, 24, 22, 0), dateTime(2026, 4, 25, 6, 0));
        ShiftFieldUtil.setShiftPlanQty(result, 7, 40,
                dateTime(2026, 4, 25, 6, 0), dateTime(2026, 4, 25, 14, 0));
        ShiftFieldUtil.setShiftPlanQty(result, 8, 50,
                dateTime(2026, 4, 25, 14, 0), dateTime(2026, 4, 25, 22, 0));
        result.setDailyPlanQty(150);
        return result;
    }

    private LhScheduleResult buildPreviousResultWithLateShiftBoundary() {
        LhScheduleResult result = buildPreviousResult();
        ShiftFieldUtil.clearShiftPlanFields(result);
        ShiftFieldUtil.setShiftPlanQty(result, 4, 10,
                dateTime(2026, 4, 24, 7, 0), dateTime(2026, 4, 24, 15, 0));
        ShiftFieldUtil.setShiftPlanQty(result, 5, 20,
                dateTime(2026, 4, 24, 15, 0), dateTime(2026, 4, 24, 23, 0));
        ShiftFieldUtil.setShiftPlanQty(result, 6, 30,
                dateTime(2026, 4, 24, 23, 0), dateTime(2026, 4, 25, 7, 0));
        ShiftFieldUtil.setShiftPlanQty(result, 7, 40,
                dateTime(2026, 4, 25, 7, 0), dateTime(2026, 4, 25, 15, 0));
        ShiftFieldUtil.setShiftPlanQty(result, 8, 50,
                dateTime(2026, 4, 25, 15, 0), dateTime(2026, 4, 25, 23, 0));
        result.setSpecEndTime(dateTime(2026, 4, 25, 23, 0));
        return result;
    }

    private LhScheduleResult buildPreviousResultCrossingAppendBoundary() {
        LhScheduleResult result = buildPreviousResult();
        ShiftFieldUtil.setShiftPlanQty(result, 8, 50,
                dateTime(2026, 4, 25, 14, 0), dateTime(2026, 4, 25, 23, 0));
        result.setSpecEndTime(dateTime(2026, 4, 25, 23, 0));
        return result;
    }

    private LhScheduleResult buildPreviousResultWithChangedShiftTime() {
        LhScheduleResult result = buildPreviousResult();
        ShiftFieldUtil.clearShiftPlanFields(result);
        ShiftFieldUtil.setShiftPlanQty(result, 4, 10,
                dateTime(2026, 4, 24, 7, 0), dateTime(2026, 4, 24, 14, 0));
        ShiftFieldUtil.setShiftPlanQty(result, 5, 20,
                dateTime(2026, 4, 24, 15, 0), dateTime(2026, 4, 24, 22, 0));
        ShiftFieldUtil.setShiftPlanQty(result, 6, 30,
                dateTime(2026, 4, 24, 23, 0), dateTime(2026, 4, 25, 6, 0));
        ShiftFieldUtil.setShiftPlanQty(result, 7, 40,
                dateTime(2026, 4, 25, 7, 0), dateTime(2026, 4, 25, 14, 0));
        ShiftFieldUtil.setShiftPlanQty(result, 8, 50,
                dateTime(2026, 4, 25, 15, 0), dateTime(2026, 4, 25, 22, 0));
        return result;
    }

    private List<LhShiftConfigVO> buildConfiguredScheduleShifts(java.util.Date scheduleDate,
                                                                int morningHour,
                                                                int afternoonHour,
                                                                int nightHour) {
        return Arrays.asList(
                buildShift(scheduleDate, 1, ShiftEnum.MORNING_SHIFT, 0, morningHour, afternoonHour),
                buildShift(scheduleDate, 2, ShiftEnum.AFTERNOON_SHIFT, 0, afternoonHour, nightHour),
                buildShift(scheduleDate, 3, ShiftEnum.NIGHT_SHIFT, 1, nightHour, morningHour),
                buildShift(scheduleDate, 4, ShiftEnum.MORNING_SHIFT, 1, morningHour, afternoonHour),
                buildShift(scheduleDate, 5, ShiftEnum.AFTERNOON_SHIFT, 1, afternoonHour, nightHour),
                buildShift(scheduleDate, 6, ShiftEnum.NIGHT_SHIFT, 2, nightHour, morningHour),
                buildShift(scheduleDate, 7, ShiftEnum.MORNING_SHIFT, 2, morningHour, afternoonHour),
                buildShift(scheduleDate, 8, ShiftEnum.AFTERNOON_SHIFT, 2, afternoonHour, nightHour)
        );
    }

    private LhShiftConfigVO buildShift(java.util.Date scheduleDate,
                                       int shiftIndex,
                                       ShiftEnum shiftType,
                                       int dateOffset,
                                       int startHour,
                                       int endHour) {
        LhShiftConfigVO shift = new LhShiftConfigVO();
        shift.setScheduleBaseDate(scheduleDate);
        shift.setShiftIndex(shiftIndex);
        shift.setShiftType(shiftType.getCode());
        shift.setShiftCode(shiftType.getCode());
        shift.setDateOffset(dateOffset);
        shift.setStartTime(String.format("%02d:00:00", startHour));
        shift.setEndTime(String.format("%02d:00:00", endHour));
        return shift;
    }

    private MdmMaterialInfo buildMaterialInfo(String materialCode, String proSize) {
        MdmMaterialInfo materialInfo = new MdmMaterialInfo();
        materialInfo.setMaterialCode(materialCode);
        materialInfo.setProSize(proSize);
        return materialInfo;
    }

    private LhMouldChangePlan buildPreviousTypeBlockPlan() {
        LhMouldChangePlan plan = new LhMouldChangePlan();
        plan.setFactoryCode("116");
        plan.setLhResultBatchNo("LHPC20260425001");
        plan.setOrderNo("CHG20260425001");
        plan.setScheduleDate(date(2026, 4, 25));
        plan.setPlanDate(dateTime(2026, 4, 24, 6, 0));
        plan.setChangeTime(dateTime(2026, 4, 24, 6, 0));
        plan.setPlanOrder(1);
        plan.setLhMachineCode("M1");
        plan.setLhMachineName("机台1");
        plan.setBeforeMaterialCode("MAT-OLD");
        plan.setBeforeMaterialDesc("旧物料");
        plan.setAfterMaterialCode("MAT-A");
        plan.setAfterMaterialDesc("物料A");
        plan.setChangeMouldType("02");
        plan.setIsDelete(0);
        return plan;
    }

    private static java.util.Date date(int y, int month, int day) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(Calendar.YEAR, y);
        c.set(Calendar.MONTH, month - 1);
        c.set(Calendar.DAY_OF_MONTH, day);
        return c.getTime();
    }

    private static java.util.Date dateTime(int y, int month, int day, int hour, int minute) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(Calendar.YEAR, y);
        c.set(Calendar.MONTH, month - 1);
        c.set(Calendar.DAY_OF_MONTH, day);
        c.set(Calendar.HOUR_OF_DAY, hour);
        c.set(Calendar.MINUTE, minute);
        return c.getTime();
    }
}
