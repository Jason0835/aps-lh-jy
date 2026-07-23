package com.zlt.aps.lh.handler;

import com.zlt.aps.lh.api.domain.dto.MachineCleaningWindowDTO;
import com.zlt.aps.lh.api.domain.entity.LhScheduleResult;
import com.zlt.aps.lh.api.domain.entity.LhMouldCleanPlan;
import com.zlt.aps.lh.api.constant.LhScheduleParamConstant;
import com.zlt.aps.lh.api.domain.vo.LhShiftConfigVO;
import com.zlt.aps.lh.api.enums.CleaningTypeEnum;
import com.zlt.aps.lh.api.enums.ShiftEnum;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.service.impl.LhCleaningScheduleService;
import com.zlt.aps.mdm.api.domain.entity.MdmSkuMouldRel;
import com.zlt.aps.mdm.api.domain.entity.MdmDevicePlanShut;
import com.zlt.aps.lh.api.domain.entity.LhMachineOnlineInfo;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collections;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * DataInitHandler 清洗窗口构建测试。
 *
 * @author APS
 */
public class DataInitHandlerTest {

    private static final ZoneId ZONE_ID = ZoneId.of("Asia/Shanghai");

    /**
     * 用例说明：MES 在机物料与前批次同机台物料不一致时，不应使用前批次结束时间抬高续作起排点。
     *
     * @throws Exception 反射调用异常
     */
    @Test
    public void shouldUseWindowStartWhenOnlineMaterialDiffersFromPreviousResult() throws Exception {
        LhScheduleContext context = new LhScheduleContext();
        LhMachineOnlineInfo onlineInfo = new LhMachineOnlineInfo();
        onlineInfo.setLhCode("K1608");
        onlineInfo.setMaterialCode("3202000220");
        context.getMachineOnlineInfoMap().put("K1608", onlineInfo);
        context.setScheduleWindowShifts(Collections.singletonList(buildShift(1, 0,
                "06:00:00", "14:00:00")));

        LhScheduleResult previousResult = new LhScheduleResult();
        previousResult.setLhMachineCode("K1608");
        previousResult.setMaterialCode("3202000092");
        previousResult.setSpecEndTime(toDate(2026, 6, 11, 22, 0, 0));
        context.setPreviousScheduleResultList(Collections.singletonList(previousResult));

        Date estimatedEndTime = invokeResolveInitialEstimatedEndTime(context, "K1608");

        Assertions.assertEquals(toDate(2026, 6, 11, 6, 0, 0), estimatedEndTime);
    }

    /**
     * 用例说明：MES 在机物料与前批次同机台物料一致时，仍沿用前批次规格结束时间衔接续作。
     *
     * @throws Exception 反射调用异常
     */
    @Test
    public void shouldUsePreviousEndTimeWhenOnlineMaterialMatchesPreviousResult() throws Exception {
        LhScheduleContext context = new LhScheduleContext();
        LhMachineOnlineInfo onlineInfo = new LhMachineOnlineInfo();
        onlineInfo.setLhCode("K1608");
        onlineInfo.setMaterialCode("3202000220");
        context.getMachineOnlineInfoMap().put("K1608", onlineInfo);
        context.setScheduleWindowShifts(Collections.singletonList(buildShift(1, 0,
                "06:00:00", "14:00:00")));

        LhScheduleResult previousResult = new LhScheduleResult();
        previousResult.setLhMachineCode("K1608");
        previousResult.setMaterialCode("3202000220");
        previousResult.setSpecEndTime(toDate(2026, 6, 11, 22, 0, 0));
        context.setPreviousScheduleResultList(Collections.singletonList(previousResult));

        Date estimatedEndTime = invokeResolveInitialEstimatedEndTime(context, "K1608");

        Assertions.assertEquals(toDate(2026, 6, 11, 22, 0, 0), estimatedEndTime);
    }

    /**
     * 用例说明：强制重排时，机台没有 MES 在机记录，不能继承前批次晚班结束时间占用本次窗口。
     *
     * @throws Exception 反射调用异常
     */
    @Test
    public void shouldUseWindowStartWhenForceRescheduleMachineHasNoOnlineMaterial() throws Exception {
        LhScheduleContext context = new LhScheduleContext();
        context.setFactoryCode("116");
        context.getLhParamsMap().put(LhScheduleParamConstant.FORCE_RESCHEDULE, "1");
        context.setScheduleWindowShifts(Collections.singletonList(buildShift(1, 0,
                "06:00:00", "14:00:00")));

        LhScheduleResult previousResult = new LhScheduleResult();
        previousResult.setLhMachineCode("K1002");
        previousResult.setMaterialCode("3202000072");
        previousResult.setSpecEndTime(toDate(2026, 6, 11, 22, 0, 0));
        context.setPreviousScheduleResultList(Collections.singletonList(previousResult));

        Date estimatedEndTime = invokeResolveInitialEstimatedEndTime(context, "K1002");

        Assertions.assertEquals(toDate(2026, 6, 11, 6, 0, 0), estimatedEndTime);
    }

    /**
     * 用例说明：非强制重排仍保留原滚动衔接口径，无 MES 在机记录时不改变历史结束时间读取规则。
     *
     * @throws Exception 反射调用异常
     */
    @Test
    public void shouldKeepPreviousEndTimeWithoutForceRescheduleWhenMachineHasNoOnlineMaterial() throws Exception {
        LhScheduleContext context = new LhScheduleContext();
        context.getLhParamsMap().put(LhScheduleParamConstant.FORCE_RESCHEDULE, "0");
        context.setScheduleWindowShifts(Collections.singletonList(buildShift(1, 0,
                "06:00:00", "14:00:00")));

        LhScheduleResult previousResult = new LhScheduleResult();
        previousResult.setLhMachineCode("K1002");
        previousResult.setMaterialCode("3202000072");
        previousResult.setSpecEndTime(toDate(2026, 6, 11, 22, 0, 0));
        context.setPreviousScheduleResultList(Collections.singletonList(previousResult));

        Date estimatedEndTime = invokeResolveInitialEstimatedEndTime(context, "K1002");

        Assertions.assertEquals(toDate(2026, 6, 11, 22, 0, 0), estimatedEndTime);
    }

    /**
     * 用例说明：强制重排时，前批次结束时间早于排程窗口首班，不允许把窗口外时间带入本次排程。
     *
     * @throws Exception 反射调用异常
     */
    @Test
    public void shouldUseWindowStartWhenForceReschedulePreviousEndBeforeWindow() throws Exception {
        LhScheduleContext context = new LhScheduleContext();
        context.getLhParamsMap().put(LhScheduleParamConstant.FORCE_RESCHEDULE, "1");
        LhMachineOnlineInfo onlineInfo = new LhMachineOnlineInfo();
        onlineInfo.setLhCode("K1401");
        onlineInfo.setMaterialCode("3302002187");
        context.getMachineOnlineInfoMap().put("K1401", onlineInfo);
        context.setScheduleWindowShifts(Collections.singletonList(buildShift(1, 0,
                "06:00:00", "14:00:00")));

        LhScheduleResult previousResult = new LhScheduleResult();
        previousResult.setLhMachineCode("K1401");
        previousResult.setMaterialCode("3302002187");
        previousResult.setSpecEndTime(toDate(2026, 6, 10, 12, 13, 20));
        context.setPreviousScheduleResultList(Collections.singletonList(previousResult));

        Date estimatedEndTime = invokeResolveInitialEstimatedEndTime(context, "K1401");

        Assertions.assertEquals(toDate(2026, 6, 11, 6, 0, 0), estimatedEndTime);
    }

    /**
     * 用例说明：干冰清洗时间早于允许窗口时，清洗窗口应调整到当天 07:30。
     *
     * @throws Exception 反射调用异常
     */
    @Test
    public void shouldAdjustDryIceCleaningStartToWorkStartWhenBeforeRange() throws Exception {
        LhScheduleContext context = new LhScheduleContext();
        context.getLhParamsMap().put(LhScheduleParamConstant.DRY_ICE_WORK_START_TIME, "07:30");
        context.getLhParamsMap().put(LhScheduleParamConstant.DRY_ICE_WORK_END_TIME, "17:00");

        LhMouldCleanPlan plan = new LhMouldCleanPlan();
        plan.setLhCode("K1313");
        plan.setCleanType(CleaningTypeEnum.DRY_ICE.getCode());
        plan.setCleanTime(toDate(2026, 4, 22, 6, 30, 0));

        MachineCleaningWindowDTO window = invokeBuildCleaningWindow(context, plan);

        Assertions.assertNotNull(window);
        Assertions.assertEquals(toDate(2026, 4, 22, 7, 30, 0), window.getCleanStartTime());
        Assertions.assertEquals(toDate(2026, 4, 22, 10, 30, 0), window.getCleanEndTime());
    }

    /**
     * 用例说明：干冰清洗时间晚于允许窗口时，清洗窗口应调整到次日 07:30。
     *
     * @throws Exception 反射调用异常
     */
    @Test
    public void shouldAdjustDryIceCleaningStartToNextWorkStartWhenAfterRange() throws Exception {
        LhScheduleContext context = new LhScheduleContext();
        context.getLhParamsMap().put(LhScheduleParamConstant.DRY_ICE_WORK_START_TIME, "07:30");
        context.getLhParamsMap().put(LhScheduleParamConstant.DRY_ICE_WORK_END_TIME, "17:00");

        LhMouldCleanPlan plan = new LhMouldCleanPlan();
        plan.setLhCode("K1313");
        plan.setCleanType(CleaningTypeEnum.DRY_ICE.getCode());
        plan.setCleanTime(toDate(2026, 4, 22, 18, 10, 0));

        MachineCleaningWindowDTO window = invokeBuildCleaningWindow(context, plan);

        Assertions.assertNotNull(window);
        Assertions.assertEquals(toDate(2026, 4, 23, 7, 30, 0), window.getCleanStartTime());
        Assertions.assertEquals(toDate(2026, 4, 23, 10, 30, 0), window.getCleanEndTime());
    }

    /**
     * 用例说明：清洗窗口应携带机台、数据来源、备注，并从在机物料模具关系推导模具号。
     *
     * @throws Exception 反射调用异常
     */
    @Test
    public void shouldFillCleaningWindowMetadataAndResolveMouldCode() throws Exception {
        LhScheduleContext context = new LhScheduleContext();
        LhMachineOnlineInfo onlineInfo = new LhMachineOnlineInfo();
        onlineInfo.setLhCode("K1313");
        onlineInfo.setMaterialCode("MAT-001");
        context.getMachineOnlineInfoMap().put("K1313", onlineInfo);
        MdmSkuMouldRel rel = new MdmSkuMouldRel();
        rel.setMouldCode("MOULD-001");
        context.getSkuMouldRelMap().put("MAT-001", Collections.singletonList(rel));

        LhMouldCleanPlan plan = new LhMouldCleanPlan();
        plan.setLhCode("K1313");
        plan.setCleanType(CleaningTypeEnum.DRY_ICE.getCode());
        plan.setCleanTime(toDate(2026, 4, 22, 9, 0, 0));
        plan.setDataSource("0");
        plan.setRemark("计划员导入");

        MachineCleaningWindowDTO window = invokeBuildCleaningWindow(context, plan);

        Assertions.assertNotNull(window);
        Assertions.assertEquals("K1313", window.getLhCode());
        Assertions.assertEquals("MOULD-001", window.getMouldCode());
        Assertions.assertEquals("0", window.getDataSource());
        Assertions.assertEquals("计划员导入", window.getRemark());
    }

    /**
     * 用例说明：喷砂清洗同日超过配置上限时，初始化校验不再中断，第二台按时间顺序顺延到次日。
     *
     * @throws Exception 反射调用异常
     */
    @Test
    public void shouldShiftSecondSandBlastToNextDayWhenDailyLimitExceeded() throws Exception {
        LhScheduleContext context = new LhScheduleContext();
        context.setFactoryCode("116");
        context.getLhParamsMap().put(LhScheduleParamConstant.SAND_BLAST_DAILY_LIMIT, "1");
        LhMouldCleanPlan firstPlan = buildCleanPlan("K1313", CleaningTypeEnum.SAND_BLAST.getCode(),
                toDate(2026, 4, 22, 9, 0, 0));
        LhMouldCleanPlan secondPlan = buildCleanPlan("K1314", CleaningTypeEnum.SAND_BLAST.getCode(),
                toDate(2026, 4, 22, 10, 0, 0));
        context.setCleaningPlanList(Arrays.asList(
                firstPlan,
                secondPlan));

        Map<String, List<MachineCleaningWindowDTO>> windowMap = invokeResolveScheduledCleaningWindowMap(context);
        MachineCleaningWindowDTO firstWindow = windowMap.get("K1313").get(0);
        MachineCleaningWindowDTO secondWindow = windowMap.get("K1314").get(0);

        Assertions.assertFalse(context.isInterrupted());
        Assertions.assertEquals(toDate(2026, 4, 22, 9, 0, 0), firstWindow.getCleanStartTime());
        Assertions.assertEquals(toDate(2026, 4, 23, 10, 0, 0), secondWindow.getCleanStartTime());
        Assertions.assertEquals(toDate(2026, 4, 23, 22, 0, 0), secondWindow.getCleanEndTime());
        Assertions.assertEquals(toDate(2026, 4, 23, 20, 0, 0), secondWindow.getReadyTime());
    }

    /**
     * 用例说明：喷砂清洗命中维保日时不再中断，运行态清洗窗口前移到前一日。
     *
     * @throws Exception 反射调用异常
     */
    @Test
    public void shouldMoveSandBlastWindowToPreviousDayWhenMaintenanceDateMatched() throws Exception {
        LhScheduleContext context = new LhScheduleContext();
        context.setFactoryCode("116");
        context.getLhParamsMap().put(LhScheduleParamConstant.SAND_BLAST_MAINTENANCE_DATES, "15,28");
        context.getLhParamsMap().put(LhScheduleParamConstant.SAND_BLAST_ALLOW_ON_MAINTENANCE_DATE, "0");
        LhMouldCleanPlan plan = buildCleanPlan("K1313", CleaningTypeEnum.SAND_BLAST.getCode(),
                toDate(2026, 4, 15, 9, 0, 0));
        context.setCleaningPlanList(Collections.singletonList(plan));

        MachineCleaningWindowDTO window = invokeResolveScheduledCleaningWindowMap(context).get("K1313").get(0);

        Assertions.assertFalse(context.isInterrupted());
        Assertions.assertEquals(toDate(2026, 4, 14, 9, 0, 0), window.getCleanStartTime());
        Assertions.assertEquals(toDate(2026, 4, 14, 21, 0, 0), window.getCleanEndTime());
        Assertions.assertEquals(toDate(2026, 4, 14, 19, 0, 0), window.getReadyTime());
    }

    /**
     * 用例说明：系统来源喷砂命中维保日时也按运行态窗口前移处理，不再按来源阻断。
     *
     * @throws Exception 反射调用异常
     */
    @Test
    public void shouldAllowSystemSandBlastOnMaintenanceDateAndMoveRuntimeWindow() throws Exception {
        LhScheduleContext context = new LhScheduleContext();
        context.setFactoryCode("116");
        context.getLhParamsMap().put(LhScheduleParamConstant.SAND_BLAST_MAINTENANCE_DATES, "15,28");
        context.getLhParamsMap().put(LhScheduleParamConstant.SAND_BLAST_ALLOW_ON_MAINTENANCE_DATE, "1");
        LhMouldCleanPlan plan = buildCleanPlan("K1313", CleaningTypeEnum.SAND_BLAST.getCode(),
                toDate(2026, 4, 15, 9, 0, 0));
        context.setCleaningPlanList(Collections.singletonList(plan));

        MachineCleaningWindowDTO window = invokeResolveScheduledCleaningWindowMap(context).get("K1313").get(0);

        Assertions.assertFalse(context.isInterrupted());
        Assertions.assertEquals(toDate(2026, 4, 14, 9, 0, 0), window.getCleanStartTime());
    }

    /**
     * 用例说明：维保日手工喷砂也统一按运行态窗口前移处理。
     *
     * @throws Exception 反射调用异常
     */
    @Test
    public void shouldMoveManualSandBlastWindowOnMaintenanceDate() throws Exception {
        LhScheduleContext context = new LhScheduleContext();
        context.setFactoryCode("116");
        context.getLhParamsMap().put(LhScheduleParamConstant.SAND_BLAST_MAINTENANCE_DATES, "15,28");
        context.getLhParamsMap().put(LhScheduleParamConstant.SAND_BLAST_ALLOW_ON_MAINTENANCE_DATE, "1");
        LhMouldCleanPlan plan = buildCleanPlan("K1313", CleaningTypeEnum.SAND_BLAST.getCode(),
                toDate(2026, 4, 15, 9, 0, 0));
        plan.setDataSource("0");
        context.setCleaningPlanList(Collections.singletonList(plan));

        MachineCleaningWindowDTO window = invokeResolveScheduledCleaningWindowMap(context).get("K1313").get(0);

        Assertions.assertFalse(context.isInterrupted());
        Assertions.assertEquals(toDate(2026, 4, 14, 9, 0, 0), window.getCleanStartTime());
    }

    /**
     * 用例说明：喷砂命中连续禁排日时，应继续前移到更早的可排日期，而不是错误顺延到后一天。
     *
     * @throws Exception 反射调用异常
     */
    @Test
    public void shouldContinueMoveSandBlastBackwardWhenPreviousDayStillForbidden() throws Exception {
        LhScheduleContext context = new LhScheduleContext();
        context.setFactoryCode("116");
        context.getLhParamsMap().put(LhScheduleParamConstant.SAND_BLAST_MAINTENANCE_DATES, "17");

        LhMouldCleanPlan plan = buildCleanPlan("K1313", CleaningTypeEnum.SAND_BLAST.getCode(),
                toDate(2026, 4, 19, 9, 0, 0));
        context.setCleaningPlanList(Collections.singletonList(plan));
        context.setWorkCalendarList(Collections.singletonList(buildHolidayCalendar(toDate(2026, 4, 18, 0, 0, 0))));

        MachineCleaningWindowDTO window = invokeResolveScheduledCleaningWindowMap(context).get("K1313").get(0);

        Assertions.assertEquals(toDate(2026, 4, 16, 9, 0, 0), window.getCleanStartTime());
    }

    /**
     * 用例说明：周日手工喷砂阈值改为结果校验阶段执行，初始化阶段不应因空交替计划列表直接放行或阻断。
     *
     * @throws Exception 反射调用异常
     */
    @Test
    public void shouldDeferManualSundaySandBlastThresholdValidationToResultStage() throws Exception {
        LhScheduleContext context = new LhScheduleContext();
        context.setFactoryCode("116");
        context.getLhParamsMap().put(LhScheduleParamConstant.SAND_BLAST_SKIP_SUNDAY_ENABLED, "1");
        context.getLhParamsMap().put(LhScheduleParamConstant.SAND_BLAST_ALLOW_SUNDAY_MANUAL_ENABLED, "1");
        context.getLhParamsMap().put(LhScheduleParamConstant.SAND_BLAST_SUNDAY_MIN_ALTERNATE_PLAN_COUNT, "2");
        LhMouldCleanPlan plan = buildCleanPlan("K1313", CleaningTypeEnum.SAND_BLAST.getCode(),
                toDate(2026, 4, 19, 9, 0, 0));
        plan.setDataSource("0");
        context.setCleaningPlanList(Collections.singletonList(plan));

        Assertions.assertFalse(context.isInterrupted());
    }

    /**
     * 用例说明：干冰早班超过 2 台时不再中断，第三台按时间顺序顺延到中班。
     *
     * @throws Exception 反射调用异常
     */
    @Test
    public void shouldShiftDryIceToAfternoonWhenMorningLimitExceeded() throws Exception {
        LhScheduleContext context = buildDryIceLimitContext();
        LhMouldCleanPlan firstPlan = buildCleanPlan("K1301", CleaningTypeEnum.DRY_ICE.getCode(),
                toDate(2026, 4, 22, 7, 30, 0));
        LhMouldCleanPlan secondPlan = buildCleanPlan("K1302", CleaningTypeEnum.DRY_ICE.getCode(),
                toDate(2026, 4, 22, 8, 30, 0));
        LhMouldCleanPlan thirdPlan = buildCleanPlan("K1303", CleaningTypeEnum.DRY_ICE.getCode(),
                toDate(2026, 4, 22, 9, 30, 0));
        context.setCleaningPlanList(Arrays.asList(firstPlan, secondPlan, thirdPlan));

        Map<String, List<MachineCleaningWindowDTO>> windowMap = invokeResolveScheduledCleaningWindowMap(context);
        MachineCleaningWindowDTO firstWindow = windowMap.get("K1301").get(0);
        MachineCleaningWindowDTO secondWindow = windowMap.get("K1302").get(0);
        MachineCleaningWindowDTO thirdWindow = windowMap.get("K1303").get(0);

        Assertions.assertFalse(context.isInterrupted());
        Assertions.assertEquals(toDate(2026, 4, 22, 7, 30, 0), firstWindow.getCleanStartTime());
        Assertions.assertEquals(toDate(2026, 4, 22, 8, 30, 0), secondWindow.getCleanStartTime());
        Assertions.assertEquals(toDate(2026, 4, 22, 14, 0, 0), thirdWindow.getCleanStartTime());
    }

    /**
     * 用例说明：干冰中班超过 1 台时不再中断，第二台按时间顺序顺延到次日早班。
     *
     * @throws Exception 反射调用异常
     */
    @Test
    public void shouldShiftDryIceToNextMorningWhenAfternoonLimitExceeded() throws Exception {
        LhScheduleContext context = buildDryIceLimitContext();
        LhMouldCleanPlan firstPlan = buildCleanPlan("K1301", CleaningTypeEnum.DRY_ICE.getCode(),
                toDate(2026, 4, 22, 14, 0, 0));
        LhMouldCleanPlan secondPlan = buildCleanPlan("K1302", CleaningTypeEnum.DRY_ICE.getCode(),
                toDate(2026, 4, 22, 15, 0, 0));
        context.setCleaningPlanList(Arrays.asList(firstPlan, secondPlan));

        Map<String, List<MachineCleaningWindowDTO>> windowMap = invokeResolveScheduledCleaningWindowMap(context);
        MachineCleaningWindowDTO firstWindow = windowMap.get("K1301").get(0);
        MachineCleaningWindowDTO secondWindow = windowMap.get("K1302").get(0);

        Assertions.assertFalse(context.isInterrupted());
        Assertions.assertEquals(toDate(2026, 4, 22, 14, 0, 0), firstWindow.getCleanStartTime());
        Assertions.assertEquals(toDate(2026, 4, 23, 7, 30, 0), secondWindow.getCleanStartTime());
    }

    /**
     * 用例说明：干冰同日超过 3 台时不再中断，第四台按时间顺序顺延到次日早班。
     *
     * @throws Exception 反射调用异常
     */
    @Test
    public void shouldShiftDryIceToNextMorningWhenDailyLimitExceeded() throws Exception {
        LhScheduleContext context = buildDryIceLimitContext();
        context.getLhParamsMap().put(LhScheduleParamConstant.DRY_ICE_MORNING_SHIFT_LIMIT, "3");
        context.getLhParamsMap().put(LhScheduleParamConstant.DRY_ICE_AFTERNOON_SHIFT_LIMIT, "3");
        LhMouldCleanPlan firstPlan = buildCleanPlan("K1301", CleaningTypeEnum.DRY_ICE.getCode(),
                toDate(2026, 4, 22, 7, 30, 0));
        LhMouldCleanPlan secondPlan = buildCleanPlan("K1302", CleaningTypeEnum.DRY_ICE.getCode(),
                toDate(2026, 4, 22, 8, 30, 0));
        LhMouldCleanPlan thirdPlan = buildCleanPlan("K1303", CleaningTypeEnum.DRY_ICE.getCode(),
                toDate(2026, 4, 22, 14, 0, 0));
        LhMouldCleanPlan fourthPlan = buildCleanPlan("K1304", CleaningTypeEnum.DRY_ICE.getCode(),
                toDate(2026, 4, 22, 15, 0, 0));
        context.setCleaningPlanList(Arrays.asList(firstPlan, secondPlan, thirdPlan, fourthPlan));

        MachineCleaningWindowDTO fourthWindow = invokeResolveScheduledCleaningWindowMap(context).get("K1304").get(0);

        Assertions.assertFalse(context.isInterrupted());
        Assertions.assertEquals(toDate(2026, 4, 23, 7, 30, 0), fourthWindow.getCleanStartTime());
    }

    /**
     * 用例说明：干冰因停机顺延后若实际跨入中班，应占用中班名额，后续同日中班清洗需顺延到次日。
     *
     * @throws Exception 反射调用异常
     */
    @Test
    public void shouldTreatDowntimeDelayedDryIceAsAfternoonUsageWhenWindowCrossesAfternoon() throws Exception {
        LhScheduleContext context = buildDryIceLimitContext();
        Date afternoonCrossStopStart = toDate(2026, 4, 22, 7, 0, 0);
        Date afternoonCrossStopEnd = toDate(2026, 4, 22, 13, 30, 0);
        MdmDevicePlanShut stop = new MdmDevicePlanShut();
        stop.setMachineCode("K1313");
        stop.setBeginDate(afternoonCrossStopStart);
        stop.setEndDate(afternoonCrossStopEnd);
        context.setDevicePlanShutList(Collections.singletonList(stop));

        LhMouldCleanPlan firstPlan = buildCleanPlan("K1313", CleaningTypeEnum.DRY_ICE.getCode(),
                toDate(2026, 4, 22, 7, 30, 0));
        LhMouldCleanPlan secondPlan = buildCleanPlan("K1206", CleaningTypeEnum.DRY_ICE.getCode(),
                toDate(2026, 4, 22, 8, 0, 0));
        LhMouldCleanPlan thirdPlan = buildCleanPlan("K1110", CleaningTypeEnum.DRY_ICE.getCode(),
                toDate(2026, 4, 22, 9, 0, 0));
        LhMouldCleanPlan fourthPlan = buildCleanPlan("K2024", CleaningTypeEnum.DRY_ICE.getCode(),
                toDate(2026, 4, 22, 14, 0, 0));
        context.setCleaningPlanList(Arrays.asList(firstPlan, secondPlan, thirdPlan, fourthPlan));

        Map<String, List<MachineCleaningWindowDTO>> windowMap = invokeResolveScheduledCleaningWindowMap(context);
        MachineCleaningWindowDTO firstWindow = windowMap.get("K1313").get(0);
        MachineCleaningWindowDTO secondWindow = windowMap.get("K1206").get(0);
        MachineCleaningWindowDTO thirdWindow = windowMap.get("K1110").get(0);
        MachineCleaningWindowDTO fourthWindow = windowMap.get("K2024").get(0);

        Assertions.assertEquals(toDate(2026, 4, 22, 13, 30, 0), firstWindow.getCleanStartTime());
        Assertions.assertEquals(toDate(2026, 4, 22, 8, 0, 0), secondWindow.getCleanStartTime());
        Assertions.assertEquals(toDate(2026, 4, 22, 9, 0, 0), thirdWindow.getCleanStartTime());
        Assertions.assertEquals(toDate(2026, 4, 23, 7, 30, 0), fourthWindow.getCleanStartTime());
    }

    /**
     * 用例说明：干冰清洗时间命中计划停机时，清洗开始时间应顺延到停机结束时刻。
     *
     * @throws Exception 反射调用异常
     */
    @Test
    public void shouldShiftDryIceCleaningWindowToStopEndWhenOverlap() throws Exception {
        // 准备停机窗口：2026-04-22 06:00:00 ~ 2026-04-22 23:59:59
        Date stopStart = toDate(2026, 4, 22, 6, 0, 0);
        Date stopEnd = toDate(2026, 4, 22, 23, 59, 59);
        LhScheduleContext context = buildContextWithStop("K1313", stopStart, stopEnd);

        // 准备清洗计划：干冰，发生在停机窗口内
        LhMouldCleanPlan plan = new LhMouldCleanPlan();
        plan.setLhCode("K1313");
        plan.setCleanType(CleaningTypeEnum.DRY_ICE.getCode());
        plan.setCleanTime(toDate(2026, 4, 22, 6, 30, 55));

        MachineCleaningWindowDTO window = invokeBuildCleaningWindow(context, plan);

        Assertions.assertNotNull(window);
        Assertions.assertEquals(stopEnd, window.getCleanStartTime());
        Assertions.assertEquals(toDate(2026, 4, 23, 2, 59, 59), window.getCleanEndTime());
    }

    /**
     * 用例说明：喷砂清洗命中计划停机时，停机扣减按 12 小时口径，机台就绪仍保持喷砂清洗原时长口径。
     *
     * @throws Exception 反射调用异常
     */
    @Test
    public void shouldShiftSandBlastCleaningWindowToStopEndAndSeparateDowntimeAndReadyTime() throws Exception {
        // 准备停机窗口：2026-04-22 06:00:00 ~ 2026-04-22 23:59:59
        Date stopStart = toDate(2026, 4, 22, 6, 0, 0);
        Date stopEnd = toDate(2026, 4, 22, 23, 59, 59);
        LhScheduleContext context = buildContextWithStop("K1111", stopStart, stopEnd);

        // 准备清洗计划：喷砂，发生在停机窗口内
        LhMouldCleanPlan plan = new LhMouldCleanPlan();
        plan.setLhCode("K1111");
        plan.setCleanType(CleaningTypeEnum.SAND_BLAST.getCode());
        plan.setCleanTime(toDate(2026, 4, 22, 9, 44, 51));

        MachineCleaningWindowDTO window = invokeBuildCleaningWindow(context, plan);

        Assertions.assertNotNull(window);
        Assertions.assertEquals(stopEnd, window.getCleanStartTime());
        Assertions.assertEquals(toDate(2026, 4, 23, 11, 59, 59), window.getCleanEndTime());
        Assertions.assertEquals(toDate(2026, 4, 23, 9, 59, 59), window.getReadyTime());
    }

    /**
     * 反射调用私有方法 buildCleaningWindow。
     *
     * @param context 排程上下文
     * @param plan 清洗计划
     * @return 清洗窗口
     * @throws Exception 反射异常
     */
    private MachineCleaningWindowDTO invokeBuildCleaningWindow(LhScheduleContext context, LhMouldCleanPlan plan)
            throws Exception {
        LhCleaningScheduleService service = new LhCleaningScheduleService();
        Method method = LhCleaningScheduleService.class.getDeclaredMethod(
                "buildCleaningWindow", LhScheduleContext.class, LhMouldCleanPlan.class);
        method.setAccessible(true);
        return (MachineCleaningWindowDTO) method.invoke(service, context, plan);
    }

    /**
     * 反射调用私有方法 resolveInitialEstimatedEndTime。
     *
     * @param context 排程上下文
     * @param machineCode 机台编码
     * @return 初始预计结束时间
     * @throws Exception 反射异常
     */
    private Date invokeResolveInitialEstimatedEndTime(LhScheduleContext context, String machineCode)
            throws Exception {
        DataInitHandler handler = new DataInitHandler();
        Method method = DataInitHandler.class.getDeclaredMethod(
                "resolveInitialEstimatedEndTime", LhScheduleContext.class, String.class);
        method.setAccessible(true);
        return (Date) method.invoke(handler, context, machineCode);
    }

    /**
     * 反射调用私有方法 resolveScheduledCleaningWindowMap。
     *
     * @param context 排程上下文
     * @return 运行态清洗窗口Map
     * @throws Exception 反射异常
     */
    @SuppressWarnings("unchecked")
    private Map<String, List<MachineCleaningWindowDTO>> invokeResolveScheduledCleaningWindowMap(LhScheduleContext context)
            throws Exception {
        LhCleaningScheduleService service = new LhCleaningScheduleService();
        Method method = LhCleaningScheduleService.class.getDeclaredMethod(
                "buildScheduledCleaningWindowMap", LhScheduleContext.class);
        method.setAccessible(true);
        return (Map<String, List<MachineCleaningWindowDTO>>) method.invoke(service, context);
    }

    /**
     * 构建清洗计划。
     *
     * @param machineCode 机台编号
     * @param cleanType 清洗类型
     * @param cleanTime 清洗时间
     * @return 清洗计划
     */
    private LhMouldCleanPlan buildCleanPlan(String machineCode, String cleanType, Date cleanTime) {
        LhMouldCleanPlan plan = new LhMouldCleanPlan();
        plan.setFactoryCode("116");
        plan.setLhCode(machineCode);
        plan.setCleanType(cleanType);
        plan.setCleanTime(cleanTime);
        plan.setDataSource("1");
        return plan;
    }

    /**
     * 构建干冰清洗数量约束测试上下文。
     *
     * @return 排程上下文
     */
    private LhScheduleContext buildDryIceLimitContext() {
        LhScheduleContext context = new LhScheduleContext();
        context.setFactoryCode("116");
        context.getLhParamsMap().put(LhScheduleParamConstant.DRY_ICE_WORK_START_TIME, "07:30");
        context.getLhParamsMap().put(LhScheduleParamConstant.DRY_ICE_WORK_END_TIME, "17:00");
        context.getLhParamsMap().put(LhScheduleParamConstant.DRY_ICE_DAILY_LIMIT, "3");
        context.getLhParamsMap().put(LhScheduleParamConstant.DRY_ICE_MORNING_SHIFT_LIMIT, "2");
        context.getLhParamsMap().put(LhScheduleParamConstant.DRY_ICE_AFTERNOON_SHIFT_LIMIT, "1");
        return context;
    }

    /**
     * 构建包含单条停机记录的上下文。
     *
     * @param machineCode 机台编号
     * @param stopStart 停机开始
     * @param stopEnd 停机结束
     * @return 排程上下文
     */
    private LhScheduleContext buildContextWithStop(String machineCode, Date stopStart, Date stopEnd) {
        LhScheduleContext context = new LhScheduleContext();
        MdmDevicePlanShut stop = new MdmDevicePlanShut();
        stop.setMachineCode(machineCode);
        stop.setBeginDate(stopStart);
        stop.setEndDate(stopEnd);
        context.setDevicePlanShutList(Arrays.asList(stop));
        return context;
    }

    private LhShiftConfigVO buildShift(int shiftIndex, int dateOffset, String startTime, String endTime) {
        LhShiftConfigVO shift = new LhShiftConfigVO();
        shift.setScheduleBaseDate(toDate(2026, 6, 11, 0, 0, 0));
        shift.setShiftIndex(shiftIndex);
        shift.setDateOffset(dateOffset);
        shift.setShiftType(ShiftEnum.MORNING_SHIFT.getCode());
        shift.setStartTime(startTime);
        shift.setEndTime(endTime);
        return shift;
    }

    private com.zlt.aps.mdm.api.domain.entity.MdmWorkCalendar buildHolidayCalendar(Date holidayDate) {
        com.zlt.aps.mdm.api.domain.entity.MdmWorkCalendar calendar =
                new com.zlt.aps.mdm.api.domain.entity.MdmWorkCalendar();
        calendar.setProductionDate(holidayDate);
        calendar.setDayFlag("0");
        return calendar;
    }

    /**
     * 生成指定时刻的 Date。
     *
     * @param year 年
     * @param month 月
     * @param day 日
     * @param hour 时
     * @param minute 分
     * @param second 秒
     * @return Date 实例
     */
    private Date toDate(int year, int month, int day, int hour, int minute, int second) {
        return Date.from(LocalDateTime.of(year, month, day, hour, minute, second)
                .atZone(ZONE_ID)
                .toInstant());
    }
}
