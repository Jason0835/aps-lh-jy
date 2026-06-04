package com.zlt.aps.lh.handler;

import com.zlt.aps.lh.api.constant.LhScheduleParamConstant;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.context.LhScheduleConfig;
import com.zlt.aps.lh.api.domain.dto.SkuDailyPlanQuotaDTO;
import com.zlt.aps.lh.api.domain.dto.SkuScheduleDTO;
import com.zlt.aps.mdm.api.domain.entity.MdmSkuLhCapacity;
import com.zlt.aps.mp.api.domain.entity.FactoryMonthPlanProductionFinalResult;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

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

/**
 * ScheduleAdjustHandler 胎胚库存分摊测试。
 *
 * @author APS
 */
public class ScheduleAdjustHandlerTest {

    /**
     * 用例说明：同胎胚库存应按 SKU 日硫化量分摊，而不是按标准产能分摊。
     *
     * @throws Exception 反射调用异常
     */
    @Test
    public void shouldAllocateEmbryoStockByDailyPlanQty() throws Exception {
        ScheduleAdjustHandler handler = new ScheduleAdjustHandler();
        LhScheduleContext context = new LhScheduleContext();

        FactoryMonthPlanProductionFinalResult firstPlan = buildMonthPlan("3302001575", "EMB-01", 20);
        FactoryMonthPlanProductionFinalResult secondPlan = buildMonthPlan("3302001724", "EMB-01", 80);
        context.setMonthPlanList(Arrays.asList(firstPlan, secondPlan));
        context.getEmbryoRealtimeStockMap().put("EMB-01", 100);

        context.getSkuLhCapacityMap().put("3302001575", buildCapacity(100));
        context.getSkuLhCapacityMap().put("3302001724", buildCapacity(100));

        Map<String, Integer> embryoSumMap = invokeBuildEmbryoStandardCapacitySumMap(handler, context);
        int allocatedStock = invokeResolveAllocatedEmbryoStock(handler, context, firstPlan, embryoSumMap);

        Assertions.assertEquals(20, allocatedStock);
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
        context.getMaterialMonthFinishedQtyMap().put("3302001575", 230);
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

    private Map<String, Integer> invokeBuildEmbryoStandardCapacitySumMap(ScheduleAdjustHandler handler,
                                                                         LhScheduleContext context) throws Exception {
        Method method = ScheduleAdjustHandler.class.getDeclaredMethod(
                "buildEmbryoStandardCapacitySumMap", LhScheduleContext.class);
        method.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Integer> result = (Map<String, Integer>) method.invoke(handler, context);
        return result;
    }

    private int invokeResolveAllocatedEmbryoStock(ScheduleAdjustHandler handler,
                                                  LhScheduleContext context,
                                                  FactoryMonthPlanProductionFinalResult plan,
                                                  Map<String, Integer> embryoSumMap) throws Exception {
        Method method = ScheduleAdjustHandler.class.getDeclaredMethod(
                "resolveAllocatedEmbryoStock", LhScheduleContext.class,
                FactoryMonthPlanProductionFinalResult.class, Map.class);
        method.setAccessible(true);
        return (Integer) method.invoke(handler, context, plan, embryoSumMap);
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

    private SkuScheduleDTO getFirstGatheredSku(LhScheduleContext context) {
        List<SkuScheduleDTO> skuList = context.getStructureSkuMap().values().iterator().next();
        return skuList.get(0);
    }

    private SkuDailyPlanQuotaDTO getQuota(SkuScheduleDTO sku, LocalDate productionDate) {
        return sku.getDailyPlanQuotaMap().get(productionDate);
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
        return capacity;
    }
}
