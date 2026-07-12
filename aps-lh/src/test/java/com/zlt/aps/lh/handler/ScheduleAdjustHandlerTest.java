package com.zlt.aps.lh.handler;

import com.zlt.aps.lh.api.constant.LhScheduleParamConstant;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.context.LhScheduleConfig;
import com.zlt.aps.lh.api.domain.dto.SkuDailyPlanQuotaDTO;
import com.zlt.aps.lh.api.domain.dto.SkuScheduleDTO;
import com.zlt.aps.lh.component.TargetScheduleQtyResolver;
import com.zlt.aps.lh.component.SingleControlModeSnapshotInitializer;
import com.zlt.aps.lh.engine.strategy.IEndingJudgmentStrategy;
import com.zlt.aps.lh.engine.strategy.impl.DefaultMachineMatchStrategy;
import com.zlt.aps.mdm.api.domain.entity.MdmSkuLhCapacity;
import com.zlt.aps.mp.api.domain.entity.FactoryMonthPlanProductionFinalResult;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * ScheduleAdjustHandler 胎胚库存分摊测试。
 *
 * @author APS
 */
public class ScheduleAdjustHandlerTest {

    /**
     * 用例说明：不同 SKU 共用胎胚但不是同一天收尾时，不进入共用胎胚库存分摊。
     *
     * @throws Exception 反射调用异常
     */
    @Test
    public void shouldNotAllocateSharedEmbryoStockWhenEndingDaysAreDifferent() throws Exception {
        ScheduleAdjustHandler handler = buildHandlerWithEndingDays(
                buildEndingDaysMap("3302001575", 1, "3302001724", 2));
        LhScheduleContext context = buildEmbryoAllocationContext();
        FactoryMonthPlanProductionFinalResult firstPlan = buildSchedulePlan("3302001575", "结构A", 100, 20, 0, 0);
        firstPlan.setEmbryoCode("EMB-01");
        FactoryMonthPlanProductionFinalResult secondPlan = buildSchedulePlan("3302001724", "结构A", 100, 20, 0, 0);
        secondPlan.setEmbryoCode("EMB-01");
        context.setMonthPlanList(Arrays.asList(firstPlan, secondPlan));
        context.getEmbryoRealtimeStockMap().put("EMB-01", 100);
        context.getEmbryoEndingFlagMap().put("EMB-01", 1);
        context.getSkuLhCapacityMap().put("3302001575", buildCapacity(100));
        context.getSkuLhCapacityMap().put("3302001724", buildCapacity(100));

        invokeDoHandle(handler, context);

        SkuScheduleDTO firstSku = findNewSpecSku(context, "3302001575");
        SkuScheduleDTO secondSku = findNewSpecSku(context, "3302001724");
        Assertions.assertEquals(100, firstSku.getEmbryoStock());
        Assertions.assertEquals(100, secondSku.getEmbryoStock());
    }

    /**
     * 用例说明：不同 SKU 共用胎胚且T日同日收尾时，落库库存仍保留原始值，内部额度按标准产能占比分摊。
     *
     * @throws Exception 反射调用异常
     */
    @Test
    public void shouldAllocateSameDayEndingSharedEmbryoStockAndGiveRemainderToLastSku() throws Exception {
        ScheduleAdjustHandler handler = buildHandlerWithEndingDays(
                buildEndingDaysMap("3302001575", 1, "3302001724", 1));
        LhScheduleContext context = buildEmbryoAllocationContext();

        FactoryMonthPlanProductionFinalResult firstPlan = buildMonthPlan("3302001575", "EMB-01", 20);
        FactoryMonthPlanProductionFinalResult secondPlan = buildMonthPlan("3302001724", "EMB-01", 80);
        firstPlan.setStructureName("结构A");
        firstPlan.setTotalQty(100);
        firstPlan.setDay1(20);
        secondPlan.setStructureName("结构A");
        secondPlan.setTotalQty(100);
        secondPlan.setDay1(80);
        context.setMonthPlanList(Arrays.asList(firstPlan, secondPlan));
        context.getEmbryoRealtimeStockMap().put("EMB-01", 100);
        context.getEmbryoEndingFlagMap().put("EMB-01", 1);

        context.getSkuLhCapacityMap().put("3302001575", buildCapacity(1));
        context.getSkuLhCapacityMap().put("3302001724", buildCapacity(2));

        invokeDoHandle(handler, context);

        SkuScheduleDTO firstSku = findNewSpecSku(context, "3302001575");
        SkuScheduleDTO secondSku = findNewSpecSku(context, "3302001724");
        Assertions.assertEquals(100, firstSku.getEmbryoStock());
        Assertions.assertEquals(100, secondSku.getEmbryoStock());
        Assertions.assertEquals(Integer.valueOf(33),
                context.getEmbryoStockSkuQuotaMap().get("3302001575"));
        Assertions.assertEquals(Integer.valueOf(67),
                context.getEmbryoStockSkuQuotaMap().get("3302001724"));
    }

    /**
     * 用例说明：排程日是每月 1 号时，上月欠产不允许进入本月追补。
     *
     * @throws Exception 反射调用异常
     */
    @Test
    public void shouldIgnorePreviousMonthShortageOnFirstDayOfMonth() throws Exception {
        ScheduleAdjustHandler handler = new ScheduleAdjustHandler();
        LhScheduleContext context = new LhScheduleContext();
        context.setScheduleDate(toDate(LocalDate.of(2026, 5, 1)));
        context.setScheduleTargetDate(toDate(LocalDate.of(2026, 5, 1)));

        FactoryMonthPlanProductionFinalResult plan = buildSchedulePlan("3302001575", "结构A", 80, 80, 0, 0);
        context.setMonthPlanList(Collections.singletonList(plan));

        invokeAdjustPreviousSchedule(handler, context);
        invokeGatherSkuByStructure(handler, context);

        Assertions.assertTrue(context.getCarryForwardQtyMap().isEmpty());
        SkuScheduleDTO sku = getFirstGatheredSku(context);
        Assertions.assertEquals(80, sku.getPendingQty());
        Assertions.assertEquals(80, getQuota(sku, LocalDate.of(2026, 5, 1)).getRemainingQty());
    }

    /**
     * 用例说明：本月超产不抵扣后续计划，只累计本月内真实欠产。
     *
     * @throws Exception 反射调用异常
     */
    @Test
    public void shouldIgnoreCurrentMonthOverProductionAndCarryOnlyShortage() throws Exception {
        ScheduleAdjustHandler handler = new ScheduleAdjustHandler();
        LhScheduleContext context = new LhScheduleContext();
        context.setScheduleDate(toDate(LocalDate.of(2026, 5, 3)));
        context.setScheduleTargetDate(toDate(LocalDate.of(2026, 5, 3)));
        enableCarryForwardQty(context);

        FactoryMonthPlanProductionFinalResult plan = buildSchedulePlan("3302001575", "结构A", 300, 100, 100, 100);
        context.setMonthPlanList(Collections.singletonList(plan));
        context.getMaterialMonthFinishedQtyMap().put("3302001575", 180);
        setMonthDailyFinishedQtyMap(context, buildMonthFinishedQtyMap(
                LocalDate.of(2026, 5, 1), 150,
                LocalDate.of(2026, 5, 2), 80));

        invokeAdjustPreviousSchedule(handler, context);
        invokeGatherSkuByStructure(handler, context);

        Assertions.assertEquals(20, context.getCarryForwardQtyMap().get("3302001575"));
        SkuScheduleDTO sku = getFirstGatheredSku(context);
        Assertions.assertEquals(120, sku.getPendingQty());
        Assertions.assertEquals(120, getQuota(sku, LocalDate.of(2026, 5, 3)).getRemainingQty());
    }

    /**
     * 用例说明：上月超欠产标志有效时，硫化余量需要在扣减月累计完成量和T日晚班完成量后叠加上月超欠产。
     *
     * @throws Exception 反射调用异常
     */
    @Test
    public void shouldIncludeValidLastMonthOverdueQtyWhenCalculatingSurplus() throws Exception {
        ScheduleAdjustHandler handler = new ScheduleAdjustHandler();
        LhScheduleContext context = new LhScheduleContext();
        context.setScheduleDate(toDate(LocalDate.of(2026, 5, 1)));
        context.setScheduleTargetDate(toDate(LocalDate.of(2026, 5, 1)));

        FactoryMonthPlanProductionFinalResult plan = buildSchedulePlan("3302001575", "结构A", 100, 100, 0, 0);
        plan.setLastMonthValidFlag("1");
        plan.setLastMonthOverdueQty(30);
        context.setMonthPlanList(Collections.singletonList(plan));
        context.getMaterialMonthFinishedQtyMap().put("3302001575", 70);
        context.getMaterialScheDayFinishQtyMap().put("3302001575", 20);

        invokeGatherSkuByStructure(handler, context);

        SkuScheduleDTO sku = getFirstGatheredSku(context);
        Assertions.assertEquals(90, sku.getFinishedQty());
        Assertions.assertEquals(20, sku.getScheduleDayFinishQty());
        Assertions.assertEquals(40, sku.getSurplusQty());
        Assertions.assertEquals(40, sku.getPendingQty());
    }

    /**
     * 用例说明：LhDayFinishQty 来源的月累计完成量必须按月计划产品状态读取，T日晚班完成量仍按物料编码读取。
     *
     * @throws Exception 反射调用异常
     */
    @Test
    public void shouldReadMonthFinishedQtyByMaterialAndProductStatus() throws Exception {
        ScheduleAdjustHandler handler = new ScheduleAdjustHandler();
        LhScheduleContext context = new LhScheduleContext();
        context.setScheduleDate(toDate(LocalDate.of(2026, 6, 1)));
        context.setScheduleTargetDate(toDate(LocalDate.of(2026, 6, 1)));

        FactoryMonthPlanProductionFinalResult plan = buildSchedulePlan("3302001575", "结构A", 100, 100, 0, 0);
        plan.setProductStatus("S");
        context.setMonthPlanList(Collections.singletonList(plan));
        context.getMaterialMonthFinishedQtyMap().put("3302001575_S", 70);
        context.getMaterialMonthFinishedQtyMap().put("3302001575_T", 15);
        context.getMaterialScheDayFinishQtyMap().put("3302001575", 20);

        invokeGatherSkuByStructure(handler, context);

        SkuScheduleDTO sku = getFirstGatheredSku(context);
        Assertions.assertEquals(90, sku.getFinishedQty(), "月累计完成量应取S状态70，再叠加T日晚班20");
        Assertions.assertEquals(20, sku.getScheduleDayFinishQty(), "T日晚班完成量仍按物料编码读取");
        Assertions.assertEquals(10, sku.getSurplusQty());
        Assertions.assertEquals(10, sku.getPendingQty());
    }

    /**
     * 用例说明：上月超欠产标志有效且超欠产为负数（超产）时，硫化余量需要减去超产量。
     *
     * @throws Exception 反射调用异常
     */
    @Test
    public void shouldSubtractNegativeLastMonthOverdueQtyWhenCalculatingSurplus() throws Exception {
        ScheduleAdjustHandler handler = new ScheduleAdjustHandler();
        LhScheduleContext context = new LhScheduleContext();
        context.setScheduleDate(toDate(LocalDate.of(2026, 6, 1)));
        context.setScheduleTargetDate(toDate(LocalDate.of(2026, 6, 1)));

        FactoryMonthPlanProductionFinalResult plan = buildSchedulePlan("3302001575", "结构A", 100, 100, 0, 0);
        plan.setLastMonthValidFlag("1");
        plan.setLastMonthOverdueQty(-10);
        context.setMonthPlanList(Collections.singletonList(plan));
        context.getMaterialMonthFinishedQtyMap().put("3302001575", 50);
        context.getMaterialScheDayFinishQtyMap().put("3302001575", 20);

        invokeGatherSkuByStructure(handler, context);

        SkuScheduleDTO sku = getFirstGatheredSku(context);
        Assertions.assertEquals(70, sku.getFinishedQty());
        Assertions.assertEquals(20, sku.getScheduleDayFinishQty());
        // 余量 = max(0, 100 - 70 + (-10)) = 20
        Assertions.assertEquals(20, sku.getSurplusQty());
        Assertions.assertEquals(20, sku.getPendingQty());
    }

    /**
     * 用例说明：上月超欠产标志有效且超产较大时，硫化余量扣减后不低于0。
     *
     * @throws Exception 反射调用异常
     */
    @Test
    public void shouldClampSurplusToZeroWhenNegativeLastMonthOverdueQtyExceedsRemaining() throws Exception {
        ScheduleAdjustHandler handler = new ScheduleAdjustHandler();
        LhScheduleContext context = new LhScheduleContext();
        context.setScheduleDate(toDate(LocalDate.of(2026, 6, 1)));
        context.setScheduleTargetDate(toDate(LocalDate.of(2026, 6, 1)));

        FactoryMonthPlanProductionFinalResult plan = buildSchedulePlan("3302001575", "结构A", 100, 100, 0, 0);
        plan.setLastMonthValidFlag("1");
        plan.setLastMonthOverdueQty(-15);
        context.setMonthPlanList(Collections.singletonList(plan));
        context.getMaterialMonthFinishedQtyMap().put("3302001575", 70);
        context.getMaterialScheDayFinishQtyMap().put("3302001575", 20);

        invokeGatherSkuByStructure(handler, context);

        // 余量 = max(0, 100 - 90 + (-15)) = max(0, -5) = 0，目标量为0时不进入结构分组。
        Assertions.assertTrue(context.getStructureSkuMap().isEmpty());
        Assertions.assertEquals(1, context.getUnscheduledResultList().size());
        Assertions.assertEquals(0, context.getUnscheduledResultList().get(0).getUnscheduledQty());
    }

    /**
     * 用例说明：上月超欠产标志无效时，上月超欠产数量不参与硫化余量计算。
     *
     * @throws Exception 反射调用异常
     */
    @Test
    public void shouldIgnoreInvalidLastMonthOverdueQtyWhenCalculatingSurplus() throws Exception {
        ScheduleAdjustHandler handler = new ScheduleAdjustHandler();
        LhScheduleContext context = new LhScheduleContext();
        context.setScheduleDate(toDate(LocalDate.of(2026, 5, 1)));
        context.setScheduleTargetDate(toDate(LocalDate.of(2026, 5, 1)));

        FactoryMonthPlanProductionFinalResult plan = buildSchedulePlan("3302001575", "结构A", 100, 100, 0, 0);
        plan.setLastMonthValidFlag("0");
        plan.setLastMonthOverdueQty(30);
        context.setMonthPlanList(Collections.singletonList(plan));
        context.getMaterialMonthFinishedQtyMap().put("3302001575", 70);
        context.getMaterialScheDayFinishQtyMap().put("3302001575", 20);

        invokeGatherSkuByStructure(handler, context);

        SkuScheduleDTO sku = getFirstGatheredSku(context);
        Assertions.assertEquals(90, sku.getFinishedQty());
        Assertions.assertEquals(20, sku.getScheduleDayFinishQty());
        Assertions.assertEquals(10, sku.getSurplusQty());
        Assertions.assertEquals(10, sku.getPendingQty());
    }

    /**
     * 用例说明：上月超欠产标志无效且超欠产为负数（超产）时，超产量同样不参与硫化余量计算。
     *
     * @throws Exception 反射调用异常
     */
    @Test
    public void shouldIgnoreNegativeLastMonthOverdueQtyWhenFlagInvalid() throws Exception {
        ScheduleAdjustHandler handler = new ScheduleAdjustHandler();
        LhScheduleContext context = new LhScheduleContext();
        context.setScheduleDate(toDate(LocalDate.of(2026, 6, 1)));
        context.setScheduleTargetDate(toDate(LocalDate.of(2026, 6, 1)));

        FactoryMonthPlanProductionFinalResult plan = buildSchedulePlan("3302001575", "结构A", 100, 100, 0, 0);
        plan.setLastMonthValidFlag("0");
        plan.setLastMonthOverdueQty(-10);
        context.setMonthPlanList(Collections.singletonList(plan));
        context.getMaterialMonthFinishedQtyMap().put("3302001575", 70);
        context.getMaterialScheDayFinishQtyMap().put("3302001575", 20);

        invokeGatherSkuByStructure(handler, context);

        SkuScheduleDTO sku = getFirstGatheredSku(context);
        Assertions.assertEquals(90, sku.getFinishedQty());
        Assertions.assertEquals(20, sku.getScheduleDayFinishQty());
        // 标志无效时超欠产量不参与计算，余量 = max(0, 100 - 90 + 0) = 10
        Assertions.assertEquals(10, sku.getSurplusQty());
        Assertions.assertEquals(10, sku.getPendingQty());
    }

    /**
     * 用例说明：本月历史多日欠产需要累计进入当前排程日。
     *
     * @throws Exception 反射调用异常
     */
    @Test
    public void shouldAccumulateCurrentMonthHistoricalShortage() throws Exception {
        ScheduleAdjustHandler handler = new ScheduleAdjustHandler();
        LhScheduleContext context = new LhScheduleContext();
        context.setScheduleDate(toDate(LocalDate.of(2026, 5, 3)));
        context.setScheduleTargetDate(toDate(LocalDate.of(2026, 5, 3)));

        FactoryMonthPlanProductionFinalResult plan = buildSchedulePlan("3302001575", "结构A", 300, 100, 100, 100);
        context.setMonthPlanList(Collections.singletonList(plan));
        context.getMaterialMonthFinishedQtyMap().put("3302001575", 150);
        setMonthDailyFinishedQtyMap(context, buildMonthFinishedQtyMap(
                LocalDate.of(2026, 5, 1), 70,
                LocalDate.of(2026, 5, 2), 80));

        invokeAdjustPreviousSchedule(handler, context);

        Assertions.assertEquals(50, context.getCarryForwardQtyMap().get("3302001575"));
    }

    /**
     * 用例说明：本月历史欠产统计必须按月计划产品状态读取逐日完成量，不能混用同物料其他状态。
     *
     * @throws Exception 反射调用异常
     */
    @Test
    public void shouldReadMonthDailyFinishedQtyByMaterialAndProductStatus() throws Exception {
        ScheduleAdjustHandler handler = new ScheduleAdjustHandler();
        LhScheduleContext context = new LhScheduleContext();
        context.setScheduleDate(toDate(LocalDate.of(2026, 5, 3)));
        context.setScheduleTargetDate(toDate(LocalDate.of(2026, 5, 3)));
        enableCarryForwardQty(context);

        FactoryMonthPlanProductionFinalResult plan = buildSchedulePlan("3302001575", "结构A", 300, 100, 100, 100);
        plan.setProductStatus("S");
        context.setMonthPlanList(Collections.singletonList(plan));
        context.getMaterialMonthFinishedQtyMap().put("3302001575_S", 150);
        context.getMaterialMonthDailyFinishedQtyMap().put("3302001575_S_2026-05-01", 70);
        context.getMaterialMonthDailyFinishedQtyMap().put("3302001575_S_2026-05-02", 80);
        context.getMaterialMonthDailyFinishedQtyMap().put("3302001575_T_2026-05-01", 100);
        context.getMaterialMonthDailyFinishedQtyMap().put("3302001575_T_2026-05-02", 100);

        invokeAdjustPreviousSchedule(handler, context);

        Assertions.assertEquals(50, context.getCarryForwardQtyMap().get("3302001575"));
    }

    /**
     * 用例说明：窗口跨月时，“月底后续计划量”只统计当前排程月份内窗口结束后的剩余日计划。
     *
     * @throws Exception 反射调用异常
     */
    @Test
    public void shouldResolveFutureMonthPlanQtyWithinCurrentMonthWhenWindowCrossesMonthEnd() throws Exception {
        ScheduleAdjustHandler handler = new ScheduleAdjustHandler();
        LhScheduleContext context = new LhScheduleContext();
        context.setScheduleDate(toDate(LocalDate.of(2026, 1, 30)));
        context.setScheduleTargetDate(toDate(LocalDate.of(2026, 2, 1)));

        FactoryMonthPlanProductionFinalResult plan = buildSchedulePlan("3302001575", "结构A", 300, 0, 99, 0);
        plan.setDay30(10);
        plan.setDay31(20);

        int futurePlanQty = invokeResolveFutureMonthPlanQtyAfterWindow(handler, context, plan);

        Assertions.assertEquals(0, futurePlanQty);
    }

    /**
     * 用例说明：续作多机台副本进入新增排产时，必须继承 S4.5 欠产决策字段，避免丢失增机台判断上下文。
     *
     * @throws Exception 反射调用异常
     */
    @Test
    public void shouldCopyNewSpecShortageFieldsForContinuousMachineClone() throws Exception {
        ScheduleAdjustHandler handler = new ScheduleAdjustHandler();
        SkuScheduleDTO source = new SkuScheduleDTO();
        source.setMaterialCode("3302001575");
        source.setWindowPlanQty(144);
        source.setWindowRemainingPlanQty(244);
        source.setMonthlyHistoryShortageQty(100);
        source.setEffectiveCarryForwardQty(60);
        source.setScheduleDayFinishQty(20);
        source.setFutureMonthPlanQtyAfterWindow(48);
        source.setStrictNewSpecShortageOnly(true);
        source.setMouldChangeInfo("4-2-2");

        SkuScheduleDTO copy = invokeCopySkuForContinuousMachine(handler, source, "K1115");

        Assertions.assertEquals(100, copy.getMonthlyHistoryShortageQty());
        Assertions.assertEquals(60, copy.getEffectiveCarryForwardQty());
        Assertions.assertEquals(20, copy.getScheduleDayFinishQty());
        Assertions.assertEquals(48, copy.getFutureMonthPlanQtyAfterWindow());
        Assertions.assertTrue(copy.isStrictNewSpecShortageOnly());
        Assertions.assertEquals("4-2-2", copy.getMouldChangeInfo());
        Assertions.assertEquals("K1115", copy.getContinuousMachineCode());
    }

    /**
     * 用例说明：单控机台动态粒度改为使用月计划 totalQty 判断，
     * 数量刚好等于 100 时应标记为小批量，后续选机才能按单边机台处理。
     *
     * @throws Exception 反射调用异常
     */
    @Test
    public void shouldMarkSmallBatchWhenMonthPlanTotalEqualsThreshold() throws Exception {
        ScheduleAdjustHandler handler = new ScheduleAdjustHandler();
        LhScheduleContext context = new LhScheduleContext();
        context.setScheduleDate(toDate(LocalDate.of(2026, 5, 1)));
        context.setScheduleTargetDate(toDate(LocalDate.of(2026, 5, 1)));

        FactoryMonthPlanProductionFinalResult plan = buildSchedulePlan("3302001575", "结构A", 100, 100, 0, 0);
        context.setMonthPlanList(Collections.singletonList(plan));

        invokeGatherSkuByStructure(handler, context);

        SkuScheduleDTO sku = getFirstGatheredSku(context);
        Assertions.assertEquals(100, sku.getMonthPlanQty());
        Assertions.assertEquals(100, sku.getSurplusQty());
        Assertions.assertTrue(sku.isSmallBatchValidation(), "月计划 totalQty 等于 100 时应按小批量单边粒度处理");
    }

    /**
     * 用例说明：小批量口径改为月计划 totalQty 后，
     * 不能再因为“当前余量刚好变小”就把大计划 SKU 误判成小批量。
     *
     * @throws Exception 反射调用异常
     */
    @Test
    public void shouldNotMarkSmallBatchWhenOnlySurplusFallsBelowThreshold() throws Exception {
        ScheduleAdjustHandler handler = new ScheduleAdjustHandler();
        LhScheduleContext context = new LhScheduleContext();
        context.setScheduleDate(toDate(LocalDate.of(2026, 5, 1)));
        context.setScheduleTargetDate(toDate(LocalDate.of(2026, 5, 1)));

        FactoryMonthPlanProductionFinalResult plan = buildSchedulePlan("3302001575", "结构A", 200, 200, 0, 0);
        context.setMonthPlanList(Collections.singletonList(plan));
        context.getMaterialMonthFinishedQtyMap().put("3302001575", 150);

        invokeGatherSkuByStructure(handler, context);

        SkuScheduleDTO sku = getFirstGatheredSku(context);
        Assertions.assertEquals(200, sku.getMonthPlanQty());
        Assertions.assertEquals(50, sku.getSurplusQty());
        Assertions.assertFalse(sku.isSmallBatchValidation(), "月计划 totalQty 大于 100 时不应因为余量降低被判成小批量");
    }

    private void invokeAdjustPreviousSchedule(ScheduleAdjustHandler handler,
                                              LhScheduleContext context) throws Exception {
        Method method = ScheduleAdjustHandler.class.getDeclaredMethod(
                "adjustPreviousSchedule", LhScheduleContext.class);
        method.setAccessible(true);
        method.invoke(handler, context);
    }

    private void invokeGatherSkuByStructure(ScheduleAdjustHandler handler,
                                            LhScheduleContext context) throws Exception {
        ensureWindowEndDate(context);
        Method method = ScheduleAdjustHandler.class.getDeclaredMethod(
                "gatherSkuByStructure", LhScheduleContext.class);
        method.setAccessible(true);
        method.invoke(handler, context);
    }

    private int invokeResolveFutureMonthPlanQtyAfterWindow(ScheduleAdjustHandler handler,
                                                           LhScheduleContext context,
                                                           FactoryMonthPlanProductionFinalResult plan) throws Exception {
        Method method = ScheduleAdjustHandler.class.getDeclaredMethod(
                "resolveFutureMonthPlanQtyAfterWindow", LhScheduleContext.class,
                FactoryMonthPlanProductionFinalResult.class);
        method.setAccessible(true);
        return (Integer) method.invoke(handler, context, plan);
    }

    private SkuScheduleDTO invokeCopySkuForContinuousMachine(ScheduleAdjustHandler handler,
                                                             SkuScheduleDTO source,
                                                             String machineCode) throws Exception {
        Method method = ScheduleAdjustHandler.class.getDeclaredMethod(
                "copySkuForContinuousMachine", SkuScheduleDTO.class, String.class);
        method.setAccessible(true);
        return (SkuScheduleDTO) method.invoke(handler, source, machineCode);
    }

    private void invokeDoHandle(ScheduleAdjustHandler handler,
                                LhScheduleContext context) throws Exception {
        ensureWindowEndDate(context);
        Method method = ScheduleAdjustHandler.class.getDeclaredMethod("doHandle", LhScheduleContext.class);
        method.setAccessible(true);
        method.invoke(handler, context);
    }

    private SkuScheduleDTO getFirstGatheredSku(LhScheduleContext context) {
        List<SkuScheduleDTO> skuList = context.getStructureSkuMap().values().iterator().next();
        return skuList.get(0);
    }

    private SkuDailyPlanQuotaDTO getQuota(SkuScheduleDTO sku, LocalDate productionDate) {
        return sku.getDailyPlanQuotaMap().get(productionDate);
    }

    private SkuScheduleDTO findNewSpecSku(LhScheduleContext context, String materialCode) {
        for (SkuScheduleDTO sku : context.getNewSpecSkuList()) {
            if (Objects.nonNull(sku) && materialCode.equals(sku.getMaterialCode())) {
                return sku;
            }
        }
        return null;
    }

    private void setMonthDailyFinishedQtyMap(LhScheduleContext context,
                                             Map<String, Integer> monthDailyFinishedQtyMap) throws Exception {
        Field field = LhScheduleContext.class.getDeclaredField("materialMonthDailyFinishedQtyMap");
        field.setAccessible(true);
        field.set(context, monthDailyFinishedQtyMap);
    }

    private Map<String, Integer> buildMonthFinishedQtyMap(Object... items) {
        Map<String, Integer> result = new LinkedHashMap<String, Integer>(8);
        for (int index = 0; index < items.length; index += 2) {
            LocalDate date = (LocalDate) items[index];
            Integer qty = (Integer) items[index + 1];
            result.put("3302001575_" + date, qty);
        }
        return result;
    }

    private FactoryMonthPlanProductionFinalResult buildSchedulePlan(String materialCode,
                                                                    String structureName,
                                                                    int totalQty,
                                                                    int day1Qty,
                                                                    int day2Qty,
                                                                    int day3Qty) {
        FactoryMonthPlanProductionFinalResult plan = new FactoryMonthPlanProductionFinalResult();
        plan.setMaterialCode(materialCode);
        plan.setStructureName(structureName);
        plan.setTotalQty(totalQty);
        plan.setDayVulcanizationQty(day1Qty);
        plan.setDay1(day1Qty);
        plan.setDay2(day2Qty);
        plan.setDay3(day3Qty);
        return plan;
    }

    private LhScheduleContext buildEmbryoAllocationContext() {
        LhScheduleContext context = new LhScheduleContext();
        context.setFactoryCode("116");
        context.setScheduleDate(toDate(LocalDate.of(2026, 6, 10)));
        context.setScheduleTargetDate(toDate(LocalDate.of(2026, 6, 11)));
        context.setWindowEndDate(toDate(LocalDate.of(2026, 6, 12)));
        return context;
    }

    private ScheduleAdjustHandler buildHandlerWithEndingDays(final Map<String, Integer> endingDaysMap) {
        ScheduleAdjustHandler handler = new ScheduleAdjustHandler();
        ReflectionTestUtils.setField(handler, "targetScheduleQtyResolver", new TargetScheduleQtyResolver());
        SingleControlModeSnapshotInitializer snapshotInitializer = new SingleControlModeSnapshotInitializer();
        ReflectionTestUtils.setField(snapshotInitializer, "machineMatchStrategy", new DefaultMachineMatchStrategy());
        ReflectionTestUtils.setField(handler, "singleControlModeSnapshotInitializer", snapshotInitializer);
        ReflectionTestUtils.setField(handler, "endingJudgmentStrategy", new IEndingJudgmentStrategy() {
            @Override
            public boolean isEnding(LhScheduleContext context, SkuScheduleDTO sku) {
                return Objects.nonNull(sku) && endingDaysMap.containsKey(sku.getMaterialCode());
            }

            @Override
            public int calculateEndingShifts(LhScheduleContext context, SkuScheduleDTO sku) {
                return 1;
            }

            @Override
            public int calculateEndingDays(LhScheduleContext context, SkuScheduleDTO sku) {
                Integer endingDays = endingDaysMap.get(sku.getMaterialCode());
                return Objects.nonNull(endingDays) ? endingDays : -1;
            }
        });
        return handler;
    }

    private Map<String, Integer> buildEndingDaysMap(String firstMaterialCode,
                                                    int firstEndingDays,
                                                    String secondMaterialCode,
                                                    int secondEndingDays) {
        Map<String, Integer> endingDaysMap = new LinkedHashMap<String, Integer>(4);
        endingDaysMap.put(firstMaterialCode, firstEndingDays);
        endingDaysMap.put(secondMaterialCode, secondEndingDays);
        return endingDaysMap;
    }

    private void ensureWindowEndDate(LhScheduleContext context) {
        if (Objects.isNull(context) || Objects.nonNull(context.getWindowEndDate())
                || Objects.isNull(context.getScheduleDate())) {
            return;
        }
        LocalDate scheduleDate = context.getScheduleDate().toInstant()
                .atZone(ZoneId.systemDefault()).toLocalDate();
        context.setWindowEndDate(toDate(scheduleDate.plusDays(2)));
    }

    private Date toDate(LocalDate date) {
        return Date.from(date.atStartOfDay(ZoneId.systemDefault()).toInstant());
    }

    private void enableCarryForwardQty(LhScheduleContext context) {
        Map<String, String> paramMap = new HashMap<String, String>(4);
        paramMap.put(LhScheduleParamConstant.ENABLE_CARRY_FORWARD_QTY, "1");
        context.setScheduleConfig(new LhScheduleConfig(paramMap));
    }

    private FactoryMonthPlanProductionFinalResult buildMonthPlan(String materialCode,
                                                                 String embryoCode,
                                                                 int dayVulcanizationQty) {
        FactoryMonthPlanProductionFinalResult plan = new FactoryMonthPlanProductionFinalResult();
        plan.setMaterialCode(materialCode);
        plan.setEmbryoCode(embryoCode);
        plan.setDayVulcanizationQty(dayVulcanizationQty);
        return plan;
    }

    private MdmSkuLhCapacity buildCapacity(int standardCapacity) {
        MdmSkuLhCapacity capacity = new MdmSkuLhCapacity();
        capacity.setStandardCapacity(standardCapacity);
        capacity.setClassCapacity(standardCapacity);
        return capacity;
    }
}
