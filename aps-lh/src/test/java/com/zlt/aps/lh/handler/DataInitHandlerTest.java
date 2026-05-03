package com.zlt.aps.lh.handler;

import com.zlt.aps.lh.api.domain.dto.MachineCleaningWindowDTO;
import com.zlt.aps.lh.api.domain.entity.LhMouldCleanPlan;
import com.zlt.aps.lh.api.constant.LhScheduleParamConstant;
import com.zlt.aps.lh.api.enums.CleaningTypeEnum;
import com.zlt.aps.lh.context.LhScheduleContext;
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

/**
 * DataInitHandler 清洗窗口构建测试。
 *
 * @author APS
 */
public class DataInitHandlerTest {

    private static final ZoneId ZONE_ID = ZoneId.of("Asia/Shanghai");

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
     * 用例说明：喷砂清洗同日超过配置上限时，应中断并提示具体日期和机台。
     *
     * @throws Exception 反射调用异常
     */
    @Test
    public void shouldInterruptWhenSandBlastDailyLimitExceeded() throws Exception {
        LhScheduleContext context = new LhScheduleContext();
        context.setFactoryCode("116");
        context.getLhParamsMap().put(LhScheduleParamConstant.SAND_BLAST_DAILY_LIMIT, "1");
        context.setCleaningPlanList(Arrays.asList(
                buildCleanPlan("K1313", CleaningTypeEnum.SAND_BLAST.getCode(), toDate(2026, 4, 22, 9, 0, 0)),
                buildCleanPlan("K1314", CleaningTypeEnum.SAND_BLAST.getCode(), toDate(2026, 4, 22, 10, 0, 0))));

        invokeValidateCleaningPlanRules(context);

        Assertions.assertTrue(context.isInterrupted());
        Assertions.assertTrue(context.getInterruptReason().contains("2026-04-22"));
        Assertions.assertTrue(context.getInterruptReason().contains("K1313,K1314"));
    }

    /**
     * 用例说明：喷砂清洗命中维保日且未开启手工例外时，应中断并提示机台。
     *
     * @throws Exception 反射调用异常
     */
    @Test
    public void shouldInterruptWhenSandBlastOnMaintenanceDateWithoutPermission() throws Exception {
        LhScheduleContext context = new LhScheduleContext();
        context.setFactoryCode("116");
        context.getLhParamsMap().put(LhScheduleParamConstant.SAND_BLAST_MAINTENANCE_DATES, "15,28");
        context.getLhParamsMap().put(LhScheduleParamConstant.SAND_BLAST_ALLOW_ON_MAINTENANCE_DATE, "0");
        context.setCleaningPlanList(Collections.singletonList(
                buildCleanPlan("K1313", CleaningTypeEnum.SAND_BLAST.getCode(), toDate(2026, 4, 15, 9, 0, 0))));

        invokeValidateCleaningPlanRules(context);

        Assertions.assertTrue(context.isInterrupted());
        Assertions.assertTrue(context.getInterruptReason().contains("喷砂机维保日"));
        Assertions.assertTrue(context.getInterruptReason().contains("K1313"));
    }

    /**
     * 用例说明：维保日喷砂例外只允许手工计划，系统来源即使开启配置也应阻断。
     *
     * @throws Exception 反射调用异常
     */
    @Test
    public void shouldInterruptSystemSandBlastOnMaintenanceDateEvenWhenPermissionEnabled() throws Exception {
        LhScheduleContext context = new LhScheduleContext();
        context.setFactoryCode("116");
        context.getLhParamsMap().put(LhScheduleParamConstant.SAND_BLAST_MAINTENANCE_DATES, "15,28");
        context.getLhParamsMap().put(LhScheduleParamConstant.SAND_BLAST_ALLOW_ON_MAINTENANCE_DATE, "1");
        context.setCleaningPlanList(Collections.singletonList(
                buildCleanPlan("K1313", CleaningTypeEnum.SAND_BLAST.getCode(), toDate(2026, 4, 15, 9, 0, 0))));

        invokeValidateCleaningPlanRules(context);

        Assertions.assertTrue(context.isInterrupted());
        Assertions.assertTrue(context.getInterruptReason().contains("喷砂机维保日"));
    }

    /**
     * 用例说明：维保日喷砂在开启配置且计划为手工来源时允许通过。
     *
     * @throws Exception 反射调用异常
     */
    @Test
    public void shouldAllowManualSandBlastOnMaintenanceDateWhenPermissionEnabled() throws Exception {
        LhScheduleContext context = new LhScheduleContext();
        context.setFactoryCode("116");
        context.getLhParamsMap().put(LhScheduleParamConstant.SAND_BLAST_MAINTENANCE_DATES, "15,28");
        context.getLhParamsMap().put(LhScheduleParamConstant.SAND_BLAST_ALLOW_ON_MAINTENANCE_DATE, "1");
        LhMouldCleanPlan plan = buildCleanPlan("K1313", CleaningTypeEnum.SAND_BLAST.getCode(),
                toDate(2026, 4, 15, 9, 0, 0));
        plan.setDataSource("0");
        context.setCleaningPlanList(Collections.singletonList(plan));

        invokeValidateCleaningPlanRules(context);

        Assertions.assertFalse(context.isInterrupted());
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

        invokeValidateCleaningPlanRules(context);

        Assertions.assertFalse(context.isInterrupted());
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
        DataInitHandler handler = new DataInitHandler();
        Method method = DataInitHandler.class.getDeclaredMethod(
                "buildCleaningWindow", LhScheduleContext.class, LhMouldCleanPlan.class);
        method.setAccessible(true);
        return (MachineCleaningWindowDTO) method.invoke(handler, context, plan);
    }

    /**
     * 反射调用私有方法 validateCleaningPlanRules。
     *
     * @param context 排程上下文
     * @throws Exception 反射异常
     */
    private void invokeValidateCleaningPlanRules(LhScheduleContext context) throws Exception {
        DataInitHandler handler = new DataInitHandler();
        Method method = DataInitHandler.class.getDeclaredMethod("validateCleaningPlanRules", LhScheduleContext.class);
        method.setAccessible(true);
        method.invoke(handler, context);
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
