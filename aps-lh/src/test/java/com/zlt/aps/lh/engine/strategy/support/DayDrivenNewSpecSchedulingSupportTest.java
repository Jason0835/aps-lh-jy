package com.zlt.aps.lh.engine.strategy.support;

import com.zlt.aps.lh.api.domain.dto.SkuScheduleDTO;
import com.zlt.aps.lh.api.domain.entity.LhScheduleResult;
import com.zlt.aps.lh.api.domain.vo.LhShiftConfigVO;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.util.LhScheduleTimeUtil;
import com.zlt.aps.lh.util.ShiftFieldUtil;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 新增排产按日驱动基础设施测试。
 *
 * <p>本测试只验证日编排新增对象的稳定契约，不重复验证既有选机、换模、首检、
 * 单控及排满算法，避免把本次编排层改造变成另一套平行排程内核。</p>
 *
 * @author APS
 */
class DayDrivenNewSpecSchedulingSupportTest {

    /**
     * 验证八班窗口严格按业务日拆成2、3、3个班次。
     */
    @Test
    void groupByWorkDate_shouldKeepTwoThreeThreeShiftLayout() {
        LhScheduleContext context = new LhScheduleContext();
        Date scheduleDate = toDate(LocalDate.of(2026, 7, 25));
        List<LhShiftConfigVO> shifts =
                LhScheduleTimeUtil.buildDefaultScheduleShifts(context, scheduleDate);

        LinkedHashMap<LocalDate, List<LhShiftConfigVO>> dayShiftMap =
                LhScheduleTimeUtil.groupByWorkDate(shifts);
        List<List<LhShiftConfigVO>> dailyShiftList =
                new ArrayList<List<LhShiftConfigVO>>(dayShiftMap.values());

        assertEquals(3, dayShiftMap.size());
        assertEquals(Arrays.asList(1, 2), resolveShiftIndexes(dailyShiftList.get(0)));
        assertEquals(Arrays.asList(3, 4, 5), resolveShiftIndexes(dailyShiftList.get(1)));
        assertEquals(Arrays.asList(6, 7, 8), resolveShiftIndexes(dailyShiftList.get(2)));
    }

    /**
     * 验证延期、完成和最终未排只改变生命周期，不改变原SKU顺序。
     */
    @Test
    void dayDrivenState_shouldKeepOriginalOrderAcrossDailyLifecycle() {
        SkuScheduleDTO firstSku = buildSku("3302000001");
        SkuScheduleDTO secondSku = buildSku("3302000002");
        DayDrivenScheduleState state =
                new DayDrivenScheduleState(Arrays.asList(firstSku, secondSku));

        state.defer(new DeferredScheduleTask(
                firstSku, LocalDate.of(2026, 7, 25),
                LocalDate.of(2026, 7, 26),
                DailySchedulePhase.TODAY_PLAN_AND_LOCKED, "当前日无机台"));
        state.complete(secondSku);

        List<SkuScheduleDTO> pendingSkuList =
                state.getPendingSkuListInOriginalOrder();
        assertEquals(1, pendingSkuList.size());
        assertSame(firstSku, pendingSkuList.get(0));
        assertEquals(SkuDayScheduleOutcome.DEFER_TO_NEXT_DAY,
                state.getCurrentDayOutcome(firstSku));
        assertTrue(state.isCompleted(secondSku));

        state.finalizeUnscheduled(firstSku);
        assertTrue(state.getPendingSkuListInOriginalOrder().isEmpty());
        assertEquals(SkuDayScheduleOutcome.FINAL_UNSCHEDULED,
                state.getCurrentDayOutcome(firstSku));
    }

    /**
     * 验证同一结果跨日追加时只计算当前业务日正向增量，并可恢复原基线。
     */
    @Test
    void scheduleResultBaseline_shouldCalculateOnlyCurrentDayDeltaAndRestore() {
        LhScheduleContext context = new LhScheduleContext();
        Date scheduleDate = toDate(LocalDate.of(2026, 7, 25));
        List<LhShiftConfigVO> shifts =
                LhScheduleTimeUtil.buildDefaultScheduleShifts(context, scheduleDate);
        LhScheduleResult result = new LhScheduleResult();
        ShiftFieldUtil.setShiftPlanQty(result, 1, 10,
                shifts.get(0).getShiftStartDateTime(), shifts.get(0).getShiftEndDateTime());
        ShiftFieldUtil.setShiftAnalysis(result, 1, "首检");
        result.setDailyPlanQty(10);
        result.setSpecEndTime(shifts.get(0).getShiftEndDateTime());
        ScheduleResultBaseline baseline =
                ScheduleResultBaseline.capture(result, shifts);

        List<LhShiftConfigVO> secondDayShifts = shifts.subList(2, 5);
        ShiftFieldUtil.setShiftPlanQty(result, 3, 8,
                shifts.get(2).getShiftStartDateTime(), shifts.get(2).getShiftEndDateTime());
        ShiftFieldUtil.setShiftPlanQty(result, 4, 12,
                shifts.get(3).getShiftStartDateTime(), shifts.get(3).getShiftEndDateTime());
        ShiftFieldUtil.setShiftAnalysis(result, 1, "首检,换胶囊");
        ShiftFieldUtil.setShiftAnalysis(result, 3, "换胶囊");
        result.setDailyPlanQty(30);
        result.setSpecEndTime(shifts.get(3).getShiftEndDateTime());

        assertEquals(20, baseline.calculatePositiveDelta(result, secondDayShifts));

        baseline.restore(result);
        assertEquals(10, ShiftFieldUtil.getShiftPlanQty(result, 1));
        assertNull(ShiftFieldUtil.getShiftPlanQty(result, 3));
        assertNull(ShiftFieldUtil.getShiftPlanQty(result, 4));
        assertEquals("首检", ShiftFieldUtil.getShiftAnalysis(result, 1));
        assertNull(ShiftFieldUtil.getShiftAnalysis(result, 3));
        assertEquals(10, result.getDailyPlanQty());
        assertEquals(shifts.get(0).getShiftEndDateTime(), result.getSpecEndTime());
    }

    /**
     * 验证日窗口采用左闭右开边界，日终时刻不能写入当前日。
     */
    @Test
    void dayScheduleContext_shouldUseHalfOpenDayWindow() {
        LhScheduleContext context = new LhScheduleContext();
        Date scheduleDate = toDate(LocalDate.of(2026, 7, 25));
        List<LhShiftConfigVO> shifts =
                LhScheduleTimeUtil.buildDefaultScheduleShifts(context, scheduleDate);
        DayScheduleContext dayContext = new DayScheduleContext(
                LocalDate.of(2026, 7, 25), shifts.subList(0, 2), true, false);

        assertTrue(dayContext.contains(dayContext.getDayStartTime()));
        assertFalse(dayContext.contains(dayContext.getDayEndTime()));
        assertTrue(dayContext.reachesOrPassesDayEnd(dayContext.getDayEndTime()));
        assertTrue(dayContext.isFirstScheduleDay());
        assertFalse(dayContext.isLastScheduleDay());
        assertNull(dayContext.getCurrentPhase());
    }

    /**
     * 验证普通机台和单控配对侧都会进入“已绑定机台”集合，供新增阶段排除重复选机。
     */
    @Test
    void dayDrivenState_shouldExposeAllBoundMachinesForSku() {
        SkuScheduleDTO sku = buildSku("3302000001");
        LhScheduleResult primaryResult = new LhScheduleResult();
        LhScheduleResult pairResult = new LhScheduleResult();
        DayDrivenScheduleState state =
                new DayDrivenScheduleState(Arrays.asList(sku));
        state.registerBinding(new ActiveMachineBinding(
                "3302000001/S", sku, "K1501L", "K1501R",
                primaryResult, pairResult, false));

        Set<String> boundMachineCodeSet =
                state.getBoundMachineCodesBySku(sku);

        assertEquals(2, boundMachineCodeSet.size());
        assertEquals(Arrays.asList("K1501L", "K1501R"),
                new ArrayList<String>(boundMachineCodeSet));
    }

    /**
     * 验证非收尾结果即使当前日目标已满足，仍被识别为需要跨日保留的物理在机绑定。
     */
    @Test
    void dayDrivenState_shouldKeepNonEndingBindingForNextBusinessDay() {
        SkuScheduleDTO sku = buildSku("3302000001");
        LhScheduleResult nonEndingResult = new LhScheduleResult();
        nonEndingResult.setIsEnd("0");
        DayDrivenScheduleState state =
                new DayDrivenScheduleState(Arrays.asList(sku));
        state.registerBinding(new ActiveMachineBinding(
                "3302000001/S", sku, "K1501", null,
                nonEndingResult, null, false));

        assertTrue(state.hasNonEndingBinding(sku));

        nonEndingResult.setIsEnd("1");
        assertTrue(state.hasNonEndingBinding(sku),
                "窗口级isEnd刷新不得改写首次上机时固化的绑定业务属性");

        DayDrivenScheduleState endingState =
                new DayDrivenScheduleState(Arrays.asList(sku));
        endingState.registerBinding(new ActiveMachineBinding(
                "3302000001/S", sku, "K1502", null,
                nonEndingResult, null, true));
        assertFalse(endingState.hasNonEndingBinding(sku));
    }

    /**
     * 验证 T+2 任一阶段暂时失败时仍保留 pending 状态，必须由全部阶段完成后的窗口收口统一写最终未排。
     */
    @Test
    void dayDrivenState_shouldKeepLastDayFailurePendingUntilWindowFinalize() {
        SkuScheduleDTO sku = buildSku("3302000003");
        DayDrivenScheduleState state =
                new DayDrivenScheduleState(Collections.singletonList(sku));
        LocalDate lastBusinessDate = LocalDate.of(2026, 7, 27);

        state.defer(new DeferredScheduleTask(
                sku, lastBusinessDate, lastBusinessDate.plusDays(1),
                DailySchedulePhase.ADD_MACHINE, "当前阶段候选机台均无可用产能"));

        assertTrue(state.isPending(sku), "T+2 加机台失败后仍须保留给同日提前生产阶段和窗口收口");
        assertFalse(state.isFinalUnscheduled(sku));
        assertEquals("当前阶段候选机台均无可用产能", state.getDeferredTask(sku).getReason());

        state.finalizeUnscheduled(sku);
        assertFalse(state.isPending(sku));
        assertTrue(state.isFinalUnscheduled(sku));
    }

    /**
     * 提取班次索引，便于核对每日班次布局。
     *
     * @param shifts 待核对班次
     * @return 班次索引列表
     */
    private List<Integer> resolveShiftIndexes(List<LhShiftConfigVO> shifts) {
        ArrayList<Integer> shiftIndexList =
                new ArrayList<Integer>(shifts.size());
        for (LhShiftConfigVO shift : shifts) {
            shiftIndexList.add(shift.getShiftIndex());
        }
        return shiftIndexList;
    }

    /**
     * 构建最小SKU测试数据。
     *
     * @param materialCode 物料编码
     * @return SKU测试对象
     */
    private SkuScheduleDTO buildSku(String materialCode) {
        SkuScheduleDTO sku = new SkuScheduleDTO();
        sku.setMaterialCode(materialCode);
        return sku;
    }

    /**
     * 将业务日期转换为系统默认时区零点。
     *
     * @param localDate 业务日期
     * @return 日期对象
     */
    private Date toDate(LocalDate localDate) {
        return Date.from(localDate.atStartOfDay(ZoneId.systemDefault()).toInstant());
    }
}
