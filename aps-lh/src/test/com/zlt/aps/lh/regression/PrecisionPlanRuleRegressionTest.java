package com.zlt.aps.lh.regression;

import com.zlt.aps.lh.api.domain.dto.MachineMaintenanceWindowDTO;
import com.zlt.aps.lh.api.domain.dto.MachineScheduleDTO;
import com.zlt.aps.lh.api.domain.dto.SkuDailyPlanQuotaDTO;
import com.zlt.aps.lh.api.domain.dto.SkuScheduleDTO;
import com.zlt.aps.lh.api.domain.entity.LhScheduleResult;
import com.zlt.aps.lh.component.TargetScheduleQtyResolver;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.engine.strategy.impl.ContinuousProductionStrategy;
import com.zlt.aps.lh.handler.ResultValidationHandler;
import com.zlt.aps.lh.service.impl.LhMaintenanceScheduleService;
import com.zlt.aps.lh.util.LhScheduleTimeUtil;
import com.zlt.aps.lh.util.ShiftFieldUtil;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 精度计划06:00强制下机及最终插排撤销回归测试。
 */
class PrecisionPlanRuleRegressionTest {

    /**
     * 3天内强制精度必须真实截断结果，并把截断量等额恢复至生产余量、dayN账本和未排结果。
     */
    @Test
    void precisionForceDown_shouldRestoreAllLedgersWithExactReason() {
        Date scheduleDate = dateTime(2026, 7, 27, 0, 0);
        LhScheduleContext context = buildContext(scheduleDate);
        MachineScheduleDTO machine = buildMachine("K1001", true);
        context.getMachineScheduleMap().put(machine.getMachineCode(), machine);
        context.getMachineShiftCapacityMap().put(
                machine.getMachineCode(), machine.getShiftRemainingCapacity());

        SkuScheduleDTO sku = buildSku("MAT-FORCE", scheduleDate, 10);
        context.getSkuProductionRemainingQtyMap().put("MAT-FORCE_S", 0);
        LhScheduleResult result = buildResult(
                machine.getMachineCode(), sku.getMaterialCode(),
                dateTime(2026, 7, 27, 6, 0),
                dateTime(2026, 7, 27, 14, 0), 10);

        ContinuousProductionStrategy strategy = new ContinuousProductionStrategy();
        ReflectionTestUtils.setField(
                strategy, "maintenanceScheduleService", new LhMaintenanceScheduleService());
        ReflectionTestUtils.setField(
                strategy, "targetScheduleQtyResolver", new TargetScheduleQtyResolver());

        Integer removedQty = ReflectionTestUtils.invokeMethod(
                strategy, "applyPrecisionForceDownIfNecessary",
                context, machine, sku, result, context.getScheduleWindowShifts());

        assertEquals(10, removedQty);
        assertEquals(0, result.getDailyPlanQty());
        SkuDailyPlanQuotaDTO quota = sku.getDailyPlanQuotaMap().values().iterator().next();
        assertEquals(0, quota.getScheduledQty());
        assertEquals(10, quota.getRemainingQty());
        assertEquals(0, quota.getActualQty());
        assertEquals(10, context.getSkuProductionRemainingQtyMap().values()
                .stream().mapToInt(Integer::intValue).sum());
        assertEquals(10, context.getUnscheduledResultList().get(0).getUnscheduledQty());
        assertTrue(context.getUnscheduledResultList().get(0).getUnscheduledReason()
                .contains("精度计划到期强制下机"));
    }

    /**
     * 保存前发现插排最终收尾晚于06:00时，只撤销身份标记结果并恢复账本，前SKU不受影响。
     */
    @Test
    void finalValidation_shouldRollbackOnlyMarkedPreInsertResultAfterSix() {
        Date scheduleDate = dateTime(2026, 7, 27, 0, 0);
        LhScheduleContext context = buildContext(scheduleDate);
        MachineScheduleDTO machine = buildMachine("K1001", false);
        machine.setPreviousMaterialCode("MAT-CURRENT");
        machine.setCurrentMaterialCode("MAT-INSERT");
        context.getMachineScheduleMap().put(machine.getMachineCode(), machine);
        context.getMachineShiftCapacityMap().put(
                machine.getMachineCode(), machine.getShiftRemainingCapacity());
        SkuScheduleDTO sku = buildSku("MAT-INSERT", scheduleDate, 10);
        context.getSkuProductionRemainingQtyMap().put("MAT-INSERT_S", 0);
        LhScheduleResult result = buildResult(
                machine.getMachineCode(), sku.getMaterialCode(),
                dateTime(2026, 7, 27, 0, 0),
                dateTime(2026, 7, 27, 6, 1), 10);
        result.setMouldChangeStartTime(dateTime(2026, 7, 26, 14, 0));
        context.getScheduleResultList().add(result);
        context.getScheduleResultSourceSkuMap().put(result, sku);
        context.getPrecisionPreInsertResultSet().add(result);
        context.getMachineAssignmentMap().put(
                machine.getMachineCode(), new java.util.ArrayList<LhScheduleResult>(
                        Collections.singletonList(result)));
        context.getDailyMouldChangeCountMap().put("2026-07-26", new int[]{0, 1});
        context.getDailyFirstInspectionCountMap().put("2026-07-27", new int[]{1, 0});
        context.getShiftFirstInspectionCountMap().put("2026-07-27#1", 1);
        context.getPrecisionPreInsertMouldChangeTimeMap().put(
                result, dateTime(2026, 7, 26, 14, 0));
        context.getPrecisionPreInsertInspectionTimeMap().put(
                result, dateTime(2026, 7, 27, 6, 0));
        context.getPrecisionPreInsertInspectionShiftIndexMap().put(result, 1);

        ResultValidationHandler handler = new ResultValidationHandler();
        ReflectionTestUtils.setField(
                handler, "targetScheduleQtyResolver", new TargetScheduleQtyResolver());
        ReflectionTestUtils.invokeMethod(
                handler, "rollbackInvalidPrecisionPreInsertResults", context);

        assertFalse(context.getScheduleResultList().contains(result));
        assertFalse(context.getMachineAssignmentMap().containsKey(machine.getMachineCode()));
        SkuDailyPlanQuotaDTO quota = sku.getDailyPlanQuotaMap().values().iterator().next();
        assertEquals(0, quota.getScheduledQty());
        assertEquals(10, quota.getRemainingQty());
        assertEquals(10, machine.getShiftRemainingCapacity()[1],
                "最终撤销必须释放结果占用的班次产能");
        assertEquals("MAT-CURRENT", machine.getCurrentMaterialCode(),
                "最终撤销后机台运行态不能继续保留已删除插排SKU");
        assertEquals(0, context.getDailyMouldChangeCountMap().get("2026-07-26")[1],
                "最终撤销必须释放换模次数");
        assertEquals(0, context.getDailyFirstInspectionCountMap().get("2026-07-27")[0],
                "最终撤销必须释放首检均衡额度");
        assertFalse(context.getShiftFirstInspectionCountMap().containsKey("2026-07-27#1"),
                "最终撤销必须释放首检数量顺序");
        assertEquals(10, context.getUnscheduledResultList().get(0).getUnscheduledQty());
        assertTrue(context.getUnscheduledResultList().get(0).getUnscheduledReason()
                .contains("机台空等至精度开始"));
    }

    /**
     * 换活字块结果虽然记录切换开始时间，但未占用换模均衡名额，最终撤销不得误减其他SKU的换模计数。
     */
    @Test
    void finalRollback_shouldNotReleaseUnallocatedMouldChangeQuota() {
        LhScheduleContext context = buildContext(dateTime(2026, 7, 27, 0, 0));
        LhScheduleResult typeBlockResult = buildResult(
                "K1001", "MAT-TYPE-BLOCK",
                dateTime(2026, 7, 27, 0, 0),
                dateTime(2026, 7, 27, 5, 0), 10);
        typeBlockResult.setMouldChangeStartTime(dateTime(2026, 7, 26, 14, 0));
        context.getDailyMouldChangeCountMap().put("2026-07-26", new int[]{0, 1});

        ResultValidationHandler handler = new ResultValidationHandler();
        ReflectionTestUtils.invokeMethod(
                handler, "rollbackPrecisionPreInsertResources",
                context, Collections.singletonList(typeBlockResult));

        assertEquals(1, context.getDailyMouldChangeCountMap().get("2026-07-26")[1],
                "未登记真实换模占用的换活字块结果不得释放换模名额");
    }

    /**
     * 构造带标准八班次的排程上下文。
     *
     * @param scheduleDate 排程日
     * @return 排程上下文
     */
    private LhScheduleContext buildContext(Date scheduleDate) {
        LhScheduleContext context = new LhScheduleContext();
        context.setFactoryCode("116");
        context.setBatchNo("TEST-PRECISION");
        context.setScheduleDate(scheduleDate);
        context.setScheduleTargetDate(scheduleDate);
        context.setScheduleWindowShifts(
                LhScheduleTimeUtil.buildDefaultScheduleShifts(context, scheduleDate));
        return context;
    }

    /**
     * 构造带精度窗口的机台。
     *
     * @param machineCode 机台编码
     * @param forceDown 是否强制下机
     * @return 机台
     */
    private MachineScheduleDTO buildMachine(String machineCode, boolean forceDown) {
        MachineScheduleDTO machine = new MachineScheduleDTO();
        machine.setMachineCode(machineCode);
        machine.setMachineName(machineCode);
        machine.setCurrentMaterialCode("MAT-CURRENT");
        machine.setShiftRemainingCapacity(new int[9]);
        MachineMaintenanceWindowDTO window = new MachineMaintenanceWindowDTO();
        window.setMachineCode(machineCode);
        window.setPlanDate(dateTime(2026, 7, 27, 0, 0));
        window.setProductionCutoffTime(dateTime(2026, 7, 27, 6, 0));
        window.setMaintenanceStartTime(dateTime(2026, 7, 27, 8, 0));
        window.setMaintenanceEndTime(dateTime(2026, 7, 27, 15, 0));
        window.setProductionResumeTime(dateTime(2026, 7, 27, 17, 30));
        window.setPreInsertAllowed(!forceDown);
        window.setPreInsertScheduled(!forceDown);
        window.setForceDown(forceDown);
        machine.getMaintenanceWindowList().add(window);
        return machine;
    }

    /**
     * 构造已经消费10条dayN额度的SKU。
     *
     * @param materialCode 物料编码
     * @param productionDate 生产日期
     * @param qty 已排数量
     * @return SKU
     */
    private SkuScheduleDTO buildSku(String materialCode, Date productionDate, int qty) {
        SkuScheduleDTO sku = new SkuScheduleDTO();
        sku.setMaterialCode(materialCode);
        sku.setProductStatus("S");
        sku.setMouldQty(1);
        sku.setLhTimeSeconds(3600);
        sku.setTargetScheduleQty(qty);
        sku.setRemainingScheduleQty(0);
        LocalDate localDate = productionDate.toInstant()
                .atZone(java.time.ZoneId.systemDefault()).toLocalDate();
        SkuDailyPlanQuotaDTO quota = new SkuDailyPlanQuotaDTO();
        quota.setMaterialCode(materialCode);
        quota.setProductionDate(localDate);
        quota.setDayPlanQty(qty);
        quota.setScheduledQty(qty);
        quota.setActualQty(qty);
        sku.setDailyPlanQuotaMap(new LinkedHashMap<LocalDate, SkuDailyPlanQuotaDTO>(1));
        sku.getDailyPlanQuotaMap().put(localDate, quota);
        return sku;
    }

    /**
     * 构造单班次结果。
     *
     * @param machineCode 机台编码
     * @param materialCode 物料编码
     * @param startTime 开产时间
     * @param endTime 收尾时间
     * @param qty 计划量
     * @return 排程结果
     */
    private LhScheduleResult buildResult(String machineCode,
                                         String materialCode,
                                         Date startTime,
                                         Date endTime,
                                         int qty) {
        LhScheduleResult result = new LhScheduleResult();
        result.setLhMachineCode(machineCode);
        result.setMaterialCode(materialCode);
        result.setProductStatus("S");
        result.setMouldQty(1);
        result.setLhTime(3600);
        result.setDailyPlanQty(qty);
        result.setSpecEndTime(endTime);
        ShiftFieldUtil.setShiftPlanQty(result, 1, qty, startTime, endTime);
        return result;
    }

    /**
     * 构造无毫秒时间。
     *
     * @return 指定时间
     */
    private Date dateTime(int year, int month, int day, int hour, int minute) {
        Calendar calendar = Calendar.getInstance();
        calendar.clear();
        calendar.set(year, month - 1, day, hour, minute, 0);
        return calendar.getTime();
    }
}
