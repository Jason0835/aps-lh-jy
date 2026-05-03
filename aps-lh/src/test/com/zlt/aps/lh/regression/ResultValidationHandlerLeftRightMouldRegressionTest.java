package com.zlt.aps.lh.regression;

import com.zlt.aps.lh.api.constant.LhScheduleParamConstant;
import com.zlt.aps.lh.api.domain.dto.MachineCleaningWindowDTO;
import com.zlt.aps.lh.api.domain.dto.MachineScheduleDTO;
import com.zlt.aps.lh.api.domain.entity.LhMouldChangePlan;
import com.zlt.aps.lh.api.domain.entity.LhScheduleResult;
import com.zlt.aps.lh.api.enums.CleaningTypeEnum;
import com.zlt.aps.lh.api.enums.MouldChangeTypeEnum;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.exception.ScheduleException;
import com.zlt.aps.lh.handler.ResultValidationHandler;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 结果校验处理器左右模回归测试。
 */
class ResultValidationHandlerLeftRightMouldRegressionTest {

    @Test
    void generateMouldChangePlan_shouldKeepResultLeftRightMould() {
        ResultValidationHandler handler = new ResultValidationHandler();
        LhScheduleContext context = newContext();
        context.setMachineScheduleMap(new LinkedHashMap<>());
        context.setInitialMachineScheduleMap(new LinkedHashMap<>());
        LhScheduleResult result = new LhScheduleResult();
        result.setFactoryCode("116");
        result.setBatchNo("LHPC20260417003");
        result.setLhMachineCode("K1501");
        result.setLhMachineName("华澳");
        result.setScheduleType("02");
        result.setIsChangeMould("1");
        result.setLeftRightMould("R");
        result.setMaterialCode("3302001690");
        result.setMaterialDesc("11R24.5 149/146L 16PR JD727 BL4HJY");
        result.setMouldCode("HM20231203902");
        result.setDailyPlanQty(10);
        result.setClass1StartTime(dateTime(2026, 4, 17, 7, 0));
        result.setSpecEndTime(dateTime(2026, 4, 17, 14, 0));
        context.getScheduleResultList().add(result);

        ReflectionTestUtils.invokeMethod(handler, "generateMouldChangePlan", context);
        assertEquals(1, context.getMouldChangePlanList().size());
        LhMouldChangePlan plan = context.getMouldChangePlanList().get(0);
        assertEquals("R", plan.getLeftRightMould());
    }

    @Test
    void generateMouldChangePlan_shouldUseSnapshotAsBeforeAndResultAsAfterForFirstPlan() {
        ResultValidationHandler handler = new ResultValidationHandler();
        LhScheduleContext context = newContext();
        context.setMachineScheduleMap(new LinkedHashMap<>());
        context.setInitialMachineScheduleMap(new LinkedHashMap<>());
        MachineScheduleDTO machine = new MachineScheduleDTO();
        machine.setMachineCode("K1501");
        machine.setCurrentMaterialCode("MAT-ONLINE");
        machine.setCurrentMaterialDesc("当前在机物料");
        context.getMachineScheduleMap().put("K1501", machine);
        context.getInitialMachineScheduleMap().put("K1501", machine);

        LhScheduleResult result = buildChangeResult("K1501", "MAT-NEW", "排程物料", dateTime(2026, 4, 17, 7, 0),
                dateTime(2026, 4, 17, 14, 0));
        context.getScheduleResultList().add(result);

        ReflectionTestUtils.invokeMethod(handler, "generateMouldChangePlan", context);
        assertEquals(1, context.getMouldChangePlanList().size());
        LhMouldChangePlan plan = context.getMouldChangePlanList().get(0);
        assertEquals("MAT-ONLINE", plan.getBeforeMaterialCode());
        assertEquals("当前在机物料", plan.getBeforeMaterialDesc());
        assertEquals("MAT-NEW", plan.getAfterMaterialCode());
        assertEquals("排程物料", plan.getAfterMaterialDesc());
    }

    @Test
    void generateMouldChangePlan_shouldRollBeforeAfterMaterialAcrossPlans() {
        ResultValidationHandler handler = new ResultValidationHandler();
        LhScheduleContext context = newContext();
        context.setMachineScheduleMap(new LinkedHashMap<>());
        context.setInitialMachineScheduleMap(new LinkedHashMap<>());
        MachineScheduleDTO machine = new MachineScheduleDTO();
        machine.setMachineCode("K1501");
        machine.setCurrentMaterialCode("MAT-ONLINE");
        machine.setCurrentMaterialDesc("当前在机物料");
        context.getMachineScheduleMap().put("K1501", machine);
        context.getInitialMachineScheduleMap().put("K1501", machine);

        LhScheduleResult first = buildChangeResult("K1501", "MAT-A", "物料A", dateTime(2026, 4, 17, 7, 0),
                dateTime(2026, 4, 17, 10, 0));
        LhScheduleResult second = buildChangeResult("K1501", "MAT-B", "物料B", dateTime(2026, 4, 17, 11, 0),
                dateTime(2026, 4, 17, 14, 0));
        context.getScheduleResultList().add(first);
        context.getScheduleResultList().add(second);

        ReflectionTestUtils.invokeMethod(handler, "generateMouldChangePlan", context);
        assertEquals(2, context.getMouldChangePlanList().size());

        LhMouldChangePlan firstPlan = context.getMouldChangePlanList().get(0);
        assertEquals("MAT-ONLINE", firstPlan.getBeforeMaterialCode());
        assertEquals("当前在机物料", firstPlan.getBeforeMaterialDesc());
        assertEquals("MAT-A", firstPlan.getAfterMaterialCode());
        assertEquals("物料A", firstPlan.getAfterMaterialDesc());

        LhMouldChangePlan secondPlan = context.getMouldChangePlanList().get(1);
        assertEquals("MAT-A", secondPlan.getBeforeMaterialCode());
        assertEquals("物料A", secondPlan.getBeforeMaterialDesc());
        assertEquals("MAT-B", secondPlan.getAfterMaterialCode());
        assertEquals("物料B", secondPlan.getAfterMaterialDesc());
    }

    @Test
    void generateMouldChangePlan_shouldKeepEndingToNewSkuFlowAcrossDays() {
        ResultValidationHandler handler = new ResultValidationHandler();
        LhScheduleContext context = newContext();
        context.setScheduleTargetDate(date(2026, 4, 23));
        context.setMachineScheduleMap(new LinkedHashMap<>());
        context.setInitialMachineScheduleMap(new LinkedHashMap<>());
        MachineScheduleDTO machine = new MachineScheduleDTO();
        machine.setMachineCode("K1113");
        machine.setCurrentMaterialCode("MAT-MES");
        machine.setCurrentMaterialDesc("MES在机物料");
        context.getMachineScheduleMap().put("K1113", machine);
        context.getInitialMachineScheduleMap().put("K1113", machine);

        LhScheduleResult endingResult = buildChangeResult("K1113", "MAT-END", "收尾物料",
                dateTime(2026, 4, 21, 14, 0), dateTime(2026, 4, 22, 14, 0));
        LhScheduleResult newSkuResult = buildChangeResult("K1113", "MAT-NEW", "新上物料",
                dateTime(2026, 4, 23, 14, 0), dateTime(2026, 4, 23, 22, 0));
        context.getScheduleResultList().add(endingResult);
        context.getScheduleResultList().add(newSkuResult);

        ReflectionTestUtils.invokeMethod(handler, "generateMouldChangePlan", context);
        assertEquals(2, context.getMouldChangePlanList().size());

        LhMouldChangePlan firstPlan = context.getMouldChangePlanList().get(0);
        assertEquals("MAT-MES", firstPlan.getBeforeMaterialCode());
        assertEquals("MES在机物料", firstPlan.getBeforeMaterialDesc());
        assertEquals("MAT-END", firstPlan.getAfterMaterialCode());
        assertEquals("收尾物料", firstPlan.getAfterMaterialDesc());

        LhMouldChangePlan secondPlan = context.getMouldChangePlanList().get(1);
        assertEquals("MAT-END", secondPlan.getBeforeMaterialCode());
        assertEquals("收尾物料", secondPlan.getBeforeMaterialDesc());
        assertEquals("MAT-NEW", secondPlan.getAfterMaterialCode());
        assertEquals("新上物料", secondPlan.getAfterMaterialDesc());
    }

    @Test
    void generateMouldChangePlan_shouldAlignPlanDateAndChangeTimeToRealMouldChangeStartTime() {
        ResultValidationHandler handler = new ResultValidationHandler();
        LhScheduleContext context = newContext();
        context.setMachineScheduleMap(new LinkedHashMap<>());
        context.setInitialMachineScheduleMap(new LinkedHashMap<>());

        LhScheduleResult result = buildChangeResult("K2024", "MAT-NEW", "新上物料",
                dateTime(2026, 4, 22, 14, 0), dateTime(2026, 4, 22, 22, 0));
        Date mouldChangeStartTime = dateTime(2026, 4, 22, 6, 0);
        ReflectionTestUtils.setField(result, "mouldChangeStartTime", mouldChangeStartTime);
        context.getScheduleResultList().add(result);

        ReflectionTestUtils.invokeMethod(handler, "generateMouldChangePlan", context);

        assertEquals(1, context.getMouldChangePlanList().size());
        LhMouldChangePlan plan = context.getMouldChangePlanList().get(0);
        assertEquals(mouldChangeStartTime, plan.getPlanDate());
        assertEquals(mouldChangeStartTime, plan.getChangeTime());
    }

    @Test
    void generateMouldChangePlan_shouldStoreMorningShiftCodeForShiftIndex() {
        ResultValidationHandler handler = new ResultValidationHandler();
        LhScheduleContext context = newContext();
        context.setMachineScheduleMap(new LinkedHashMap<>());
        context.setInitialMachineScheduleMap(new LinkedHashMap<>());

        LhScheduleResult result = buildChangeResult("K2024", "MAT-MORNING", "早班物料",
                dateTime(2026, 4, 17, 7, 0), dateTime(2026, 4, 17, 14, 0));
        Date mouldChangeStartTime = dateTime(2026, 4, 17, 6, 0);
        ReflectionTestUtils.setField(result, "mouldChangeStartTime", mouldChangeStartTime);
        context.getScheduleResultList().add(result);

        ReflectionTestUtils.invokeMethod(handler, "generateMouldChangePlan", context);

        assertEquals(1, context.getMouldChangePlanList().size());
        assertEquals("02", context.getMouldChangePlanList().get(0).getClassIndex());
    }

    @Test
    void generateMouldChangePlan_shouldStoreAfternoonShiftCodeForShiftIndex() {
        ResultValidationHandler handler = new ResultValidationHandler();
        LhScheduleContext context = newContext();
        context.setMachineScheduleMap(new LinkedHashMap<>());
        context.setInitialMachineScheduleMap(new LinkedHashMap<>());

        LhScheduleResult result = buildChangeResult("K2025", "MAT-AFTERNOON", "中班物料",
                dateTime(2026, 4, 17, 15, 0), dateTime(2026, 4, 17, 22, 0));
        Date mouldChangeStartTime = dateTime(2026, 4, 17, 14, 0);
        ReflectionTestUtils.setField(result, "mouldChangeStartTime", mouldChangeStartTime);
        context.getScheduleResultList().add(result);

        ReflectionTestUtils.invokeMethod(handler, "generateMouldChangePlan", context);

        assertEquals(1, context.getMouldChangePlanList().size());
        assertEquals("03", context.getMouldChangePlanList().get(0).getClassIndex());
    }

    @Test
    void generateMouldChangePlan_shouldStoreNightShiftCodeAcrossDays() {
        ResultValidationHandler handler = new ResultValidationHandler();
        LhScheduleContext context = newContext();
        context.setMachineScheduleMap(new LinkedHashMap<>());
        context.setInitialMachineScheduleMap(new LinkedHashMap<>());

        LhScheduleResult result = buildChangeResult("K2026", "MAT-NIGHT", "夜班物料",
                dateTime(2026, 4, 18, 1, 0), dateTime(2026, 4, 18, 6, 0));
        Date mouldChangeStartTime = dateTime(2026, 4, 17, 22, 0);
        ReflectionTestUtils.setField(result, "mouldChangeStartTime", mouldChangeStartTime);
        context.getScheduleResultList().add(result);

        ReflectionTestUtils.invokeMethod(handler, "generateMouldChangePlan", context);

        assertEquals(1, context.getMouldChangePlanList().size());
        assertEquals("01", context.getMouldChangePlanList().get(0).getClassIndex());
    }

    @Test
    void generateMouldChangePlan_shouldKeepClassIndexEmptyWhenTimeOutsideShiftWindow() {
        ResultValidationHandler handler = new ResultValidationHandler();
        LhScheduleContext context = newContext();
        context.setMachineScheduleMap(new LinkedHashMap<>());
        context.setInitialMachineScheduleMap(new LinkedHashMap<>());

        LhScheduleResult result = buildChangeResult("K2027", "MAT-UNKNOWN", "窗口外物料",
                dateTime(2026, 4, 20, 7, 0), dateTime(2026, 4, 20, 14, 0));
        Date mouldChangeStartTime = dateTime(2026, 4, 20, 6, 0);
        ReflectionTestUtils.setField(result, "mouldChangeStartTime", mouldChangeStartTime);
        context.getScheduleResultList().add(result);

        ReflectionTestUtils.invokeMethod(handler, "generateMouldChangePlan", context);

        assertEquals(1, context.getMouldChangePlanList().size());
        assertEquals(null, context.getMouldChangePlanList().get(0).getClassIndex());
    }

    @Test
    void generateMouldChangePlan_shouldKeepInheritedPlanAndOnlyAppendNewPlan() {
        ResultValidationHandler handler = new ResultValidationHandler();
        LhScheduleContext context = newContext();
        context.setMachineScheduleMap(new LinkedHashMap<>());
        context.setInitialMachineScheduleMap(new LinkedHashMap<>());

        LhMouldChangePlan inheritedPlan = new LhMouldChangePlan();
        inheritedPlan.setFactoryCode("116");
        inheritedPlan.setLhResultBatchNo("LHPC20260417003");
        inheritedPlan.setScheduleDate(date(2026, 4, 17));
        inheritedPlan.setLhMachineCode("K1501");
        inheritedPlan.setChangeMouldType("02");
        context.getMouldChangePlanList().add(inheritedPlan);

        LhScheduleResult inheritedResult = buildChangeResult("K1501", "MAT-INHERITED", "继承物料",
                dateTime(2026, 4, 17, 7, 0), dateTime(2026, 4, 17, 14, 0));
        inheritedResult.setRollingInherited(true);
        context.getScheduleResultList().add(inheritedResult);

        LhScheduleResult newResult = buildChangeResult("K1502", "MAT-NEW", "新增物料",
                dateTime(2026, 4, 17, 15, 0), dateTime(2026, 4, 17, 22, 0));
        context.getScheduleResultList().add(newResult);

        ReflectionTestUtils.invokeMethod(handler, "generateMouldChangePlan", context);

        assertEquals(2, context.getMouldChangePlanList().size());
        assertEquals("02", context.getMouldChangePlanList().get(0).getChangeMouldType());
        assertEquals("K1501", context.getMouldChangePlanList().get(0).getLhMachineCode());
        assertEquals("K1502", context.getMouldChangePlanList().get(1).getLhMachineCode());
    }

    @Test
    void generateMouldChangePlan_shouldAppendCleaningWindowPlans() {
        ResultValidationHandler handler = new ResultValidationHandler();
        LhScheduleContext context = newContext();
        context.setMachineScheduleMap(new LinkedHashMap<>());
        context.setInitialMachineScheduleMap(new LinkedHashMap<>());
        MachineScheduleDTO machine = new MachineScheduleDTO();
        machine.setMachineCode("K1501");
        machine.setMachineName("华澳");
        machine.setCurrentMaterialCode("MAT-ONLINE");
        machine.setCurrentMaterialDesc("当前在机物料");

        MachineCleaningWindowDTO dryIceWindow = new MachineCleaningWindowDTO();
        dryIceWindow.setLhCode("K1501");
        dryIceWindow.setCleanType(CleaningTypeEnum.DRY_ICE.getCode());
        dryIceWindow.setCleanStartTime(dateTime(2026, 4, 17, 8, 0));
        dryIceWindow.setCleanEndTime(dateTime(2026, 4, 17, 11, 0));
        dryIceWindow.setLeftRightMould("LR");
        dryIceWindow.setMouldCode("MOULD-001");
        dryIceWindow.setRemark("干冰计划");
        machine.setCleaningWindowList(Collections.singletonList(dryIceWindow));

        context.getMachineScheduleMap().put("K1501", machine);
        context.getInitialMachineScheduleMap().put("K1501", machine);

        ReflectionTestUtils.invokeMethod(handler, "generateMouldChangePlan", context);

        assertEquals(1, context.getMouldChangePlanList().size());
        LhMouldChangePlan plan = context.getMouldChangePlanList().get(0);
        assertEquals(MouldChangeTypeEnum.DRY_ICE.getCode(), plan.getChangeMouldType());
        assertEquals("K1501", plan.getLhMachineCode());
        assertEquals("LR", plan.getLeftRightMould());
        assertEquals("MOULD-001", plan.getMouldCode());
        assertEquals(dryIceWindow.getCleanStartTime(), plan.getPlanDate());
        assertEquals(dryIceWindow.getCleanStartTime(), plan.getChangeTime());
        assertEquals("干冰计划", plan.getRemark());
    }

    @Test
    void generateMouldChangePlan_shouldResolveCleaningWindowMaterialByCleaningTime() {
        ResultValidationHandler handler = new ResultValidationHandler();
        LhScheduleContext context = newContext();
        context.setMachineScheduleMap(new LinkedHashMap<>());
        context.setInitialMachineScheduleMap(new LinkedHashMap<>());

        MachineScheduleDTO machine = new MachineScheduleDTO();
        machine.setMachineCode("K1501");
        machine.setMachineName("华澳");
        machine.setCurrentMaterialCode("MAT-FINAL");
        machine.setCurrentMaterialDesc("排程结束物料");
        MachineScheduleDTO initialMachine = new MachineScheduleDTO();
        initialMachine.setMachineCode("K1501");
        initialMachine.setCurrentMaterialCode("MAT-ONLINE");
        initialMachine.setCurrentMaterialDesc("MES在机物料");

        MachineCleaningWindowDTO sandBlastWindow = new MachineCleaningWindowDTO();
        sandBlastWindow.setLhCode("K1501");
        sandBlastWindow.setCleanType(CleaningTypeEnum.SAND_BLAST.getCode());
        sandBlastWindow.setCleanStartTime(dateTime(2026, 4, 17, 8, 0));
        sandBlastWindow.setCleanEndTime(dateTime(2026, 4, 17, 20, 0));
        sandBlastWindow.setMouldCode("MOULD-001");
        machine.setCleaningWindowList(Collections.singletonList(sandBlastWindow));

        context.getMachineScheduleMap().put("K1501", machine);
        context.getInitialMachineScheduleMap().put("K1501", initialMachine);
        context.getScheduleResultList().add(buildChangeResult("K1501", "MAT-A", "物料A",
                dateTime(2026, 4, 17, 6, 0), dateTime(2026, 4, 17, 7, 0)));

        ReflectionTestUtils.invokeMethod(handler, "generateMouldChangePlan", context);

        assertEquals(2, context.getMouldChangePlanList().size());
        LhMouldChangePlan cleaningPlan = context.getMouldChangePlanList().get(1);
        assertEquals("MAT-A", cleaningPlan.getBeforeMaterialCode());
        assertEquals("物料A", cleaningPlan.getBeforeMaterialDesc());
        assertEquals("MAT-A", cleaningPlan.getAfterMaterialCode());
        assertEquals("物料A", cleaningPlan.getAfterMaterialDesc());
    }

    @Test
    void validateManualSundaySandBlastThreshold_shouldInterruptWhenAlternatePlanCountReachesThreshold() throws Exception {
        ResultValidationHandler handler = new ResultValidationHandler();
        LhScheduleContext context = newContext();
        context.setScheduleTargetDate(date(2026, 4, 19));
        context.setMachineScheduleMap(new LinkedHashMap<>());
        context.setInitialMachineScheduleMap(new LinkedHashMap<>());
        context.getLhParamsMap().put(LhScheduleParamConstant.SAND_BLAST_SKIP_SUNDAY_ENABLED, "1");
        context.getLhParamsMap().put(LhScheduleParamConstant.SAND_BLAST_ALLOW_SUNDAY_MANUAL_ENABLED, "1");
        context.getLhParamsMap().put(LhScheduleParamConstant.SAND_BLAST_SUNDAY_MIN_ALTERNATE_PLAN_COUNT, "1");

        MachineScheduleDTO machine = new MachineScheduleDTO();
        machine.setMachineCode("K1501");
        machine.setMachineName("华澳");
        MachineCleaningWindowDTO sandBlastWindow = new MachineCleaningWindowDTO();
        sandBlastWindow.setLhCode("K1501");
        sandBlastWindow.setCleanType(CleaningTypeEnum.SAND_BLAST.getCode());
        sandBlastWindow.setDataSource("0");
        sandBlastWindow.setCleanStartTime(dateTime(2026, 4, 19, 8, 0));
        sandBlastWindow.setCleanEndTime(dateTime(2026, 4, 19, 20, 0));
        machine.setCleaningWindowList(Collections.singletonList(sandBlastWindow));
        context.getMachineScheduleMap().put("K1501", machine);
        context.getInitialMachineScheduleMap().put("K1501", machine);
        context.getScheduleResultList().add(buildChangeResult("K1501", "MAT-A", "物料A",
                dateTime(2026, 4, 19, 6, 0), dateTime(2026, 4, 19, 7, 0)));

        ReflectionTestUtils.invokeMethod(handler, "generateMouldChangePlan", context);

        ScheduleException exception = invokeManualSundaySandBlastValidation(handler, context);
        assertTrue(exception.getMessage().contains("周日手工喷砂交替计划数量超限"));
        assertTrue(exception.getMessage().contains("2026-04-19"));
    }

    @Test
    void validateManualSundaySandBlastThreshold_shouldExcludeCurrentCleaningPlanFromCount() throws Exception {
        ResultValidationHandler handler = new ResultValidationHandler();
        LhScheduleContext context = newContext();
        context.setScheduleTargetDate(date(2026, 4, 19));
        context.setMachineScheduleMap(new LinkedHashMap<>());
        context.setInitialMachineScheduleMap(new LinkedHashMap<>());
        context.getLhParamsMap().put(LhScheduleParamConstant.SAND_BLAST_SKIP_SUNDAY_ENABLED, "1");
        context.getLhParamsMap().put(LhScheduleParamConstant.SAND_BLAST_ALLOW_SUNDAY_MANUAL_ENABLED, "1");
        context.getLhParamsMap().put(LhScheduleParamConstant.SAND_BLAST_SUNDAY_MIN_ALTERNATE_PLAN_COUNT, "2");

        MachineScheduleDTO machine = new MachineScheduleDTO();
        machine.setMachineCode("K1501");
        machine.setMachineName("华澳");
        MachineCleaningWindowDTO sandBlastWindow = new MachineCleaningWindowDTO();
        sandBlastWindow.setLhCode("K1501");
        sandBlastWindow.setCleanType(CleaningTypeEnum.SAND_BLAST.getCode());
        sandBlastWindow.setDataSource("0");
        sandBlastWindow.setCleanStartTime(dateTime(2026, 4, 19, 8, 0));
        sandBlastWindow.setCleanEndTime(dateTime(2026, 4, 19, 20, 0));
        machine.setCleaningWindowList(Collections.singletonList(sandBlastWindow));
        context.getMachineScheduleMap().put("K1501", machine);
        context.getInitialMachineScheduleMap().put("K1501", machine);
        context.getScheduleResultList().add(buildChangeResult("K1501", "MAT-A", "物料A",
                dateTime(2026, 4, 19, 6, 0), dateTime(2026, 4, 19, 7, 0)));

        ReflectionTestUtils.invokeMethod(handler, "generateMouldChangePlan", context);

        invokeManualSundaySandBlastValidationWithoutException(handler, context);
        assertEquals(2, context.getMouldChangePlanList().size());
    }

    private LhScheduleContext newContext() {
        LhScheduleContext context = new LhScheduleContext();
        context.setFactoryCode("116");
        context.setBatchNo("LHPC20260417003");
        context.setScheduleTargetDate(date(2026, 4, 17));
        return context;
    }

    private ScheduleException invokeManualSundaySandBlastValidation(ResultValidationHandler handler, LhScheduleContext context)
            throws Exception {
        Method method = ResultValidationHandler.class.getDeclaredMethod(
                "validateManualSundaySandBlastThreshold", LhScheduleContext.class);
        method.setAccessible(true);
        try {
            method.invoke(handler, context);
        } catch (InvocationTargetException ex) {
            if (ex.getCause() instanceof ScheduleException) {
                return (ScheduleException) ex.getCause();
            }
            throw ex;
        }
        throw new AssertionError("预期抛出 ScheduleException");
    }

    private void invokeManualSundaySandBlastValidationWithoutException(ResultValidationHandler handler, LhScheduleContext context)
            throws Exception {
        Method method = ResultValidationHandler.class.getDeclaredMethod(
                "validateManualSundaySandBlastThreshold", LhScheduleContext.class);
        method.setAccessible(true);
        method.invoke(handler, context);
    }

    private LhScheduleResult buildChangeResult(String machineCode, String materialCode, String materialDesc,
                                               Date startTime, Date endTime) {
        LhScheduleResult result = new LhScheduleResult();
        result.setFactoryCode("116");
        result.setBatchNo("LHPC20260417003");
        result.setLhMachineCode(machineCode);
        result.setLhMachineName("华澳");
        result.setScheduleType("02");
        result.setIsChangeMould("1");
        result.setLeftRightMould("R");
        result.setMaterialCode(materialCode);
        result.setMaterialDesc(materialDesc);
        result.setMouldCode("HM20231203902");
        result.setClass1StartTime(startTime);
        result.setSpecEndTime(endTime);
        result.setDailyPlanQty(10);
        return result;
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
