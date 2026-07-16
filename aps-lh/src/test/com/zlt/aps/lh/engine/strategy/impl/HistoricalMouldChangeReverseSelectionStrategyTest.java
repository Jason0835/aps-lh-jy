package com.zlt.aps.lh.engine.strategy.impl;

import com.zlt.aps.lh.api.domain.dto.MachineScheduleDTO;
import com.zlt.aps.lh.api.domain.dto.SkuScheduleDTO;
import com.zlt.aps.lh.api.domain.entity.LhMouldChangePlan;
import com.zlt.aps.lh.api.domain.entity.LhScheduleResult;
import com.zlt.aps.lh.component.TargetScheduleQtyResolver;
import com.zlt.aps.lh.context.LhScheduleConfig;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.engine.strategy.IMachineMatchStrategy;
import com.zlt.aps.lh.engine.strategy.ITypeBlockProductionStrategy;
import com.zlt.aps.lh.engine.strategy.support.HistoricalReverseSelectionDirective;
import com.zlt.aps.lh.engine.strategy.support.SpecifiedMachineMatchResult;
import com.zlt.aps.lh.engine.strategy.support.SpecifiedMachineScheduleResult;
import com.zlt.aps.lh.util.LhScheduleTimeUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 前日交替计划机台反选策略测试。
 */
@ExtendWith(MockitoExtension.class)
class HistoricalMouldChangeReverseSelectionStrategyTest {

    @Mock
    private IMachineMatchStrategy machineMatchStrategy;

    @Mock
    private ITypeBlockProductionStrategy typeBlockProductionStrategy;

    @Mock
    private TargetScheduleQtyResolver targetScheduleQtyResolver;

    @InjectMocks
    private HistoricalMouldChangeReverseSelectionStrategy strategy;

    @Test
    void reverseSelect_shouldMapShiftSortDeduplicateAndPromoteSku() {
        LhScheduleContext context = baseContext();
        SkuScheduleDTO normalSku = sku("MAT-NORMAL", "S", 1);
        SkuScheduleDTO shiftFiveSku = sku("MAT-5", "S", 2);
        SkuScheduleDTO shiftFourSku = sku("MAT-4", "S", 3);
        context.getNewSpecSkuList().addAll(Arrays.asList(normalSku, shiftFiveSku, shiftFourSku));

        LhMouldChangePlan shiftFive = plan(5L, "K1002", "MAT-5", date(2026, 7, 14, 14, 30), 2);
        LhMouldChangePlan shiftFour = plan(3L, "K1001", "MAT-4", date(2026, 7, 14, 6, 30), 1);
        LhMouldChangePlan duplicate = plan(4L, "K1001", "MAT-4", date(2026, 7, 14, 7, 0), 3);
        context.setHistoricalReverseMouldChangePlanList(
                Arrays.asList(shiftFive, duplicate, shiftFour));
        MachineScheduleDTO machineOne = machine("K1001");
        MachineScheduleDTO machineTwo = machine("K1002");
        context.getMachineScheduleMap().put(machineOne.getMachineCode(), machineOne);
        context.getMachineScheduleMap().put(machineTwo.getMachineCode(), machineTwo);

        when(targetScheduleQtyResolver.resolveProductionRemainingQty(any(), any())).thenReturn(10);
        when(typeBlockProductionStrategy.tryScheduleSpecifiedMachine(any(), any(), any(), anyInt()))
                .thenReturn(SpecifiedMachineScheduleResult.notApplicable("当前不是换活字块"));
        when(machineMatchStrategy.matchSpecifiedMachine(any(), any(), anyString()))
                .thenAnswer(invocation -> SpecifiedMachineMatchResult.success(
                        context.getMachineScheduleMap().get(invocation.getArgument(2))));

        strategy.reverseSelect(context);

        List<HistoricalReverseSelectionDirective> directives =
                context.getHistoricalReverseSelectionDirectiveList();
        assertEquals(2, directives.size());
        assertEquals(4, directives.get(0).getHistoricalShiftIndex());
        assertEquals(1, directives.get(0).getMappedShiftIndex());
        assertEquals("MAT-4", directives.get(0).getMaterialCode());
        assertEquals(5, directives.get(1).getHistoricalShiftIndex());
        assertEquals(2, directives.get(1).getMappedShiftIndex());
        assertEquals("MAT-5", directives.get(1).getMaterialCode());
        assertSame(shiftFourSku, context.getNewSpecSkuList().get(0));
        assertSame(shiftFiveSku, context.getNewSpecSkuList().get(1));
        assertSame(normalSku, context.getNewSpecSkuList().get(2));
    }

    @Test
    void reverseSelect_shouldTreatExistingMachineMaterialShiftAsSatisfied() {
        LhScheduleContext context = baseContext();
        LhMouldChangePlan history = plan(
                10L, "K2001", "MAT-A", date(2026, 7, 14, 6, 15), 1);
        context.setHistoricalReverseMouldChangePlanList(Collections.singletonList(history));
        LhScheduleResult existing = new LhScheduleResult();
        existing.setLhMachineCode("K2001");
        existing.setMaterialCode("MAT-A");
        existing.setProductStatus("S");
        existing.setClass1PlanQty(8);
        existing.setDailyPlanQty(8);
        context.getScheduleResultList().add(existing);

        strategy.reverseSelect(context);

        HistoricalReverseSelectionDirective directive =
                context.getHistoricalReverseSelectionDirectiveList().get(0);
        assertTrue(directive.isAlreadySatisfied());
        assertTrue(directive.isSuccess());
        assertTrue(context.isHistoricalReverseProtectedResult(existing));
        assertTrue(context.getHistoricalReverseSelectedMachineCodes("MAT-A", "S").contains("K2001"));
        verify(machineMatchStrategy, never()).matchSpecifiedMachine(any(), any(), anyString());
    }

    @Test
    void reverseSelect_shouldFailWithoutRemainingQtyAndKeepNormalQueue() {
        LhScheduleContext context = baseContext();
        SkuScheduleDTO first = sku("MAT-FIRST", "S", 1);
        SkuScheduleDTO target = sku("MAT-ZERO", "S", 2);
        context.getNewSpecSkuList().addAll(Arrays.asList(first, target));
        context.setHistoricalReverseMouldChangePlanList(Collections.singletonList(
                plan(20L, "K3001", "MAT-ZERO", date(2026, 7, 14, 6, 20), 1)));
        when(targetScheduleQtyResolver.resolveProductionRemainingQty(any(), any())).thenReturn(0);

        strategy.reverseSelect(context);

        HistoricalReverseSelectionDirective directive =
                context.getHistoricalReverseSelectionDirectiveList().get(0);
        assertTrue(directive.isAttempted());
        assertFalse(directive.isSuccess());
        assertEquals("后物料不存在可消费待排量或目标量", directive.getResultReason());
        assertSame(first, context.getNewSpecSkuList().get(0));
        assertSame(target, context.getNewSpecSkuList().get(1));
    }

    @Test
    void reverseSelect_shouldKeepSkuForNormalNewSpecWhenSpecifiedMachineHardFilterFails() {
        LhScheduleContext context = baseContext();
        SkuScheduleDTO first = sku("MAT-FIRST", "S", 1);
        SkuScheduleDTO target = sku("MAT-TARGET", "S", 2);
        context.getNewSpecSkuList().addAll(Arrays.asList(first, target));
        context.setHistoricalReverseMouldChangePlanList(Collections.singletonList(
                plan(30L, "K4001", "MAT-TARGET", date(2026, 7, 14, 6, 25), 1)));
        MachineScheduleDTO machine = machine("K4001");
        context.getMachineScheduleMap().put(machine.getMachineCode(), machine);
        when(targetScheduleQtyResolver.resolveProductionRemainingQty(any(), any())).thenReturn(12);
        when(typeBlockProductionStrategy.tryScheduleSpecifiedMachine(any(), any(), any(), anyInt()))
                .thenReturn(SpecifiedMachineScheduleResult.notApplicable("当前不是换活字块"));
        when(machineMatchStrategy.matchSpecifiedMachine(any(), any(), anyString()))
                .thenReturn(SpecifiedMachineMatchResult.failed("目标SKU可用模具已被占用"));

        strategy.reverseSelect(context);

        HistoricalReverseSelectionDirective directive =
                context.getHistoricalReverseSelectionDirectiveList().get(0);
        assertTrue(directive.isAttempted());
        assertFalse(directive.isSuccess());
        assertEquals("目标SKU可用模具已被占用", directive.getResultReason());
        // 反选失败不改变原普通新增顺序，也不把目标SKU移出待排队列。
        assertSame(first, context.getNewSpecSkuList().get(0));
        assertSame(target, context.getNewSpecSkuList().get(1));
        assertTrue(context.getUnscheduledResultList().isEmpty());
    }

    @Test
    void reverseSelect_shouldChooseFirstPositiveRemainingProductStatus() {
        LhScheduleContext context = baseContext();
        SkuScheduleDTO higherPriorityWithoutQty = sku("MAT-MULTI", "S", 1);
        SkuScheduleDTO lowerPriorityWithQty = sku("MAT-MULTI", "X", 2);
        context.getNewSpecSkuList().addAll(
                Arrays.asList(higherPriorityWithoutQty, lowerPriorityWithQty));
        context.setHistoricalReverseMouldChangePlanList(Collections.singletonList(
                plan(40L, "K5001", "MAT-MULTI", date(2026, 7, 14, 6, 30), 1)));
        MachineScheduleDTO machine = machine("K5001");
        context.getMachineScheduleMap().put(machine.getMachineCode(), machine);
        when(targetScheduleQtyResolver.resolveProductionRemainingQty(
                any(), any())).thenReturn(0, 10);
        when(typeBlockProductionStrategy.tryScheduleSpecifiedMachine(any(), any(), any(), anyInt()))
                .thenReturn(SpecifiedMachineScheduleResult.notApplicable("当前不是换活字块"));
        when(machineMatchStrategy.matchSpecifiedMachine(any(), any(), anyString()))
                .thenReturn(SpecifiedMachineMatchResult.success(machine));

        strategy.reverseSelect(context);

        HistoricalReverseSelectionDirective directive =
                context.getHistoricalReverseSelectionDirectiveList().get(0);
        assertEquals("X", directive.getProductStatus());
        assertSame(lowerPriorityWithQty, context.getNewSpecSkuList().get(0));
        assertFalse(directive.isAttempted());
    }

    /**
     * 构建以2026-07-14为当前排程日的测试上下文。
     *
     * @return 测试上下文
     */
    private LhScheduleContext baseContext() {
        LhScheduleContext context = new LhScheduleContext();
        Date scheduleDate = date(2026, 7, 14, 0, 0);
        context.setFactoryCode("116");
        context.setScheduleDate(scheduleDate);
        context.setScheduleTargetDate(scheduleDate);
        context.setScheduleConfig(new LhScheduleConfig(new HashMap<String, String>(0)));
        // 模拟真实DataInit已加载当前班次窗口，验证历史还原不会误用当前窗口日期。
        context.setScheduleWindowShifts(
                LhScheduleTimeUtil.buildDefaultScheduleShifts(context, scheduleDate));
        return context;
    }

    /**
     * 构建历史交替计划。
     *
     * @param id 主键
     * @param machineCode 机台编码
     * @param afterMaterialCode 后物料
     * @param planDate 交替时间
     * @param planOrder 计划顺位
     * @return 历史交替计划
     */
    private LhMouldChangePlan plan(Long id,
                                   String machineCode,
                                   String afterMaterialCode,
                                   Date planDate,
                                   int planOrder) {
        LhMouldChangePlan plan = new LhMouldChangePlan();
        plan.setId(id);
        plan.setLhMachineCode(machineCode);
        plan.setAfterMaterialCode(afterMaterialCode);
        plan.setPlanDate(planDate);
        plan.setPlanOrder(planOrder);
        plan.setChangeMouldType("01");
        return plan;
    }

    /**
     * 构建SKU。
     *
     * @param materialCode 物料编码
     * @param productStatus 产品状态
     * @param sortRank 优先级
     * @return SKU
     */
    private SkuScheduleDTO sku(String materialCode, String productStatus, int sortRank) {
        SkuScheduleDTO sku = new SkuScheduleDTO();
        sku.setMaterialCode(materialCode);
        sku.setProductStatus(productStatus);
        sku.setSortRank(sortRank);
        sku.setTargetScheduleQty(10);
        return sku;
    }

    /**
     * 构建机台。
     *
     * @param machineCode 机台编码
     * @return 机台
     */
    private MachineScheduleDTO machine(String machineCode) {
        MachineScheduleDTO machine = new MachineScheduleDTO();
        machine.setMachineCode(machineCode);
        return machine;
    }

    /**
     * 构建本地时区日期。
     *
     * @param year 年
     * @param month 月
     * @param day 日
     * @param hour 时
     * @param minute 分
     * @return 日期
     */
    private Date date(int year, int month, int day, int hour, int minute) {
        Calendar calendar = Calendar.getInstance();
        calendar.clear();
        calendar.set(year, month - 1, day, hour, minute, 0);
        return calendar.getTime();
    }
}
