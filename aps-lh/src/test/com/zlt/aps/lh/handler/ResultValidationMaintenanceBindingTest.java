package com.zlt.aps.lh.handler;

import com.zlt.aps.lh.api.domain.dto.MachineMaintenanceWindowDTO;
import com.zlt.aps.lh.api.domain.dto.MachineScheduleDTO;
import com.zlt.aps.lh.api.domain.entity.LhScheduleResult;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.util.LhScheduleTimeUtil;
import com.zlt.aps.lh.util.ShiftFieldUtil;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Calendar;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link ResultValidationHandler} 精度保养最终结果绑定回归测试。
 */
class ResultValidationMaintenanceBindingTest {

    /**
     * 同一机台存在多条结果时，应先清除中间态残留，再只给首条保养后结果绑定摘要和固定原因。
     */
    @Test
    void bindMaintenanceWindows_shouldBindOnlyFirstPostMaintenanceResult() {
        LhScheduleContext context = buildContext();
        MachineScheduleDTO machine = buildMachineWithWindow(
                dateTime(2026, 5, 21, 8, 0),
                dateTime(2026, 5, 21, 15, 0),
                dateTime(2026, 5, 21, 17, 30));
        context.getMachineScheduleMap().put(machine.getMachineCode(), machine);

        LhScheduleResult beforeMaintenance = buildResult("MAT-BEFORE",
                dateTime(2026, 5, 21, 6, 0), dateTime(2026, 5, 21, 8, 0));
        beforeMaintenance.setMaintenanceStartTime(dateTime(2026, 5, 21, 8, 0));
        beforeMaintenance.setMaintenanceEndTime(dateTime(2026, 5, 21, 15, 0));
        ShiftFieldUtil.setShiftAnalysis(beforeMaintenance, 2, "精度计划");
        LhScheduleResult afterMaintenance = buildResult("MAT-AFTER",
                dateTime(2026, 5, 21, 14, 0), dateTime(2026, 5, 21, 20, 0));
        ShiftFieldUtil.setShiftPlanQty(afterMaintenance, 1, 4,
                dateTime(2026, 5, 21, 6, 0), dateTime(2026, 5, 21, 8, 0));
        LhScheduleResult nextSkuResult = buildResult("MAT-NEXT",
                dateTime(2026, 5, 22, 6, 0), dateTime(2026, 5, 22, 14, 0));
        context.getScheduleResultList().add(beforeMaintenance);
        context.getScheduleResultList().add(afterMaintenance);
        context.getScheduleResultList().add(nextSkuResult);

        ReflectionTestUtils.invokeMethod(
                new ResultValidationHandler(), "bindMaintenanceWindowsToFinalResults", context);

        assertNull(beforeMaintenance.getMaintenanceStartTime());
        assertNull(beforeMaintenance.getMaintenanceEndTime());
        assertNull(ShiftFieldUtil.getShiftAnalysis(beforeMaintenance, 2),
                "保养前结果不得残留精度计划原因");
        assertEquals(dateTime(2026, 5, 21, 8, 0), afterMaintenance.getMaintenanceStartTime());
        assertEquals(dateTime(2026, 5, 21, 15, 0), afterMaintenance.getMaintenanceEndTime());
        assertEquals(dateTime(2026, 5, 21, 17, 30),
                ShiftFieldUtil.getShiftStartTime(afterMaintenance, 2),
                "保养后有量班次的结果开始时间必须对齐到胶囊预热完成时间");
        assertEquals(Integer.valueOf(10), ShiftFieldUtil.getShiftPlanQty(afterMaintenance, 2),
                "开始时间对齐不得改变已按有效产能计算出的班次计划量");
        assertEquals("精度计划", ShiftFieldUtil.getShiftAnalysis(afterMaintenance, 2),
                "保养结束所在中班必须且只能在绑定结果上备注精度计划");
        assertNull(nextSkuResult.getMaintenanceStartTime(),
                "同一SKU跨保养恢复生产时，不得把摘要错误绑定到更晚的下一SKU");
        assertNull(ShiftFieldUtil.getShiftAnalysis(nextSkuResult, 2));
    }

    /**
     * 保养结束时间超出当前固定班次字段范围时，不得把未来保养摘要回退绑定到当前最后结果。
     */
    @Test
    void bindMaintenanceWindows_shouldSkipFutureMaintenanceOutsideCurrentShifts() {
        LhScheduleContext context = buildContext();
        MachineScheduleDTO machine = buildMachineWithWindow(
                dateTime(2026, 6, 10, 8, 0),
                dateTime(2026, 6, 10, 15, 0),
                dateTime(2026, 6, 10, 17, 30));
        context.getMachineScheduleMap().put(machine.getMachineCode(), machine);

        LhScheduleResult currentResult = buildResult("MAT-CURRENT",
                dateTime(2026, 5, 21, 6, 0), dateTime(2026, 5, 21, 14, 0));
        currentResult.setMaintenanceStartTime(dateTime(2026, 6, 10, 8, 0));
        currentResult.setMaintenanceEndTime(dateTime(2026, 6, 10, 15, 0));
        ShiftFieldUtil.setShiftAnalysis(currentResult, 2, "精度计划");
        context.getScheduleResultList().add(currentResult);

        ReflectionTestUtils.invokeMethod(
                new ResultValidationHandler(), "bindMaintenanceWindowsToFinalResults", context);

        assertNull(currentResult.getMaintenanceStartTime());
        assertNull(currentResult.getMaintenanceEndTime());
        assertNull(ShiftFieldUtil.getShiftAnalysis(currentResult, 2),
                "未来保养未进入本批班次时不得污染当前结果原因");
    }

    /**
     * 构造三日标准班次排程上下文。
     *
     * @return 测试上下文
     */
    private LhScheduleContext buildContext() {
        LhScheduleContext context = new LhScheduleContext();
        context.setScheduleDate(dateTime(2026, 5, 21, 0, 0));
        context.setScheduleTargetDate(dateTime(2026, 5, 23, 0, 0));
        context.setScheduleWindowShifts(
                LhScheduleTimeUtil.buildDefaultScheduleShifts(context, context.getScheduleDate()));
        return context;
    }

    /**
     * 构造带单个精度保养窗口的运行态机台。
     *
     * @param startTime 保养开始时间
     * @param endTime 保养结束时间
     * @param resumeTime 胶囊预热完成时间
     * @return 运行态机台
     */
    private MachineScheduleDTO buildMachineWithWindow(Date startTime, Date endTime, Date resumeTime) {
        MachineScheduleDTO machine = new MachineScheduleDTO();
        machine.setMachineCode("K1001");
        machine.setHasMaintenancePlan(true);
        MachineMaintenanceWindowDTO window = new MachineMaintenanceWindowDTO();
        window.setMachineCode(machine.getMachineCode());
        window.setMaintenanceStartTime(startTime);
        window.setMaintenanceEndTime(endTime);
        window.setProductionResumeTime(resumeTime);
        machine.getMaintenanceWindowList().add(window);
        return machine;
    }

    /**
     * 构造单班有量的排程结果。
     *
     * @param materialCode 物料编码
     * @param productionStartTime 生产开始时间
     * @param productionEndTime 生产结束时间
     * @return 排程结果
     */
    private LhScheduleResult buildResult(String materialCode,
                                         Date productionStartTime,
                                         Date productionEndTime) {
        LhScheduleResult result = new LhScheduleResult();
        result.setLhMachineCode("K1001");
        result.setMaterialCode(materialCode);
        result.setSpecEndTime(productionEndTime);
        ShiftFieldUtil.setShiftPlanQty(result, 2, 10, productionStartTime, productionEndTime);
        return result;
    }

    /**
     * 构造测试时间。
     *
     * @param year 年
     * @param month 月
     * @param day 日
     * @param hour 时
     * @param minute 分
     * @return 时间
     */
    private Date dateTime(int year, int month, int day, int hour, int minute) {
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
