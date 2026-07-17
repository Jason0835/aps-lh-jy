package com.zlt.aps.lh.handler;

import com.zlt.aps.lh.api.domain.dto.SkuScheduleDTO;
import com.zlt.aps.lh.api.domain.entity.LhUnscheduledResult;
import com.zlt.aps.lh.component.TargetScheduleQtyResolver;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.engine.strategy.IEndingJudgmentStrategy;
import com.zlt.aps.lh.api.enums.SkuTagEnum;
import com.zlt.aps.mp.api.domain.entity.FactoryMonthPlanProductionFinalResult;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;

/**
 * S4.3共用胎胚零余量预剔除测试。
 *
 * @author APS
 */
public class ScheduleAdjustHandlerSharedEmbryoTest {

    /**
     * 用例说明：共用胎胚收尾SKU余量为0、胎胚库存大于0时，进入排产前即写未排并剔除。
     *
     * @throws Exception 反射调用异常
     */
    @Test
    public void shouldPruneSharedEmbryoZeroSurplusEndingBeforeClassify() throws Exception {
        ScheduleAdjustHandler handler = new ScheduleAdjustHandler();
        ReflectionTestUtils.setField(handler, "targetScheduleQtyResolver", new TargetScheduleQtyResolver());
        ReflectionTestUtils.setField(handler, "endingJudgmentStrategy", buildEndingJudgmentStrategy());

        LhScheduleContext context = new LhScheduleContext();
        SkuScheduleDTO zeroSurplusSku = buildSku("3302002369", "215103935", 0, 2, 2);
        SkuScheduleDTO anotherSku = buildSku("3302002279", "215103935", 124, 2, 124);
        Map<String, List<SkuScheduleDTO>> structureSkuMap = new LinkedHashMap<String, List<SkuScheduleDTO>>(4);
        structureSkuMap.put("STRUCT-01", new ArrayList<SkuScheduleDTO>(Arrays.asList(zeroSurplusSku, anotherSku)));
        context.setStructureSkuMap(structureSkuMap);
        context.getActiveEmbryoSkuMap().put("215103935",
                new ArrayList<String>(Arrays.asList("3302002369", "3302002279")));

        invokePruneSharedEmbryoZeroSurplusSkus(handler, context);

        Assertions.assertEquals(1, context.getUnscheduledResultList().size());
        LhUnscheduledResult unscheduled = context.getUnscheduledResultList().get(0);
        Assertions.assertEquals("3302002369", unscheduled.getMaterialCode());
        Assertions.assertEquals(Integer.valueOf(0), unscheduled.getUnscheduledQty());
        Assertions.assertTrue(unscheduled.getUnscheduledReason().contains("共用胎胚且硫化余量为0"));
        Assertions.assertEquals(1, context.getStructureSkuMap().get("STRUCT-01").size());
        Assertions.assertEquals("3302002279", context.getStructureSkuMap().get("STRUCT-01").get(0).getMaterialCode());
        Assertions.assertEquals(Arrays.asList("3302002279"), context.getActiveEmbryoSkuMap().get("215103935"));
    }

    /**
     * 用例说明：两个SKU共用胎胚时，零余量SKU必须先未排，剩余SKU动态转为单胎胚并按MAX规则计算。
     *
     * @throws Exception 反射调用异常
     */
    @Test
    public void shouldPruneZeroSurplusSharedEmbryoSkuAndReclassifyRemainingSkuAsSingleEmbryo() throws Exception {
        ScheduleAdjustHandler handler = new ScheduleAdjustHandler();
        TargetScheduleQtyResolver resolver = new TargetScheduleQtyResolver();
        ReflectionTestUtils.setField(handler, "targetScheduleQtyResolver", resolver);
        ReflectionTestUtils.setField(handler, "endingJudgmentStrategy", buildNonEndingJudgmentStrategy());

        LhScheduleContext context = new LhScheduleContext();
        SkuScheduleDTO zeroSurplusSku = buildSku("3302000836", "EMB-20260603", 0, 6, 6);
        SkuScheduleDTO positiveSurplusSku = buildSku("3302002078", "EMB-20260603", 5, 8, 5);
        putStructureSkus(context, zeroSurplusSku, positiveSurplusSku);
        context.getActiveEmbryoSkuMap().put("EMB-20260603",
                new ArrayList<String>(Arrays.asList("3302000836", "3302002078")));

        invokePruneSharedEmbryoZeroSurplusSkus(handler, context);
        int targetQty = positiveSurplusSku.resolveTargetScheduleQty();

        Assertions.assertEquals(1, context.getUnscheduledResultList().size());
        LhUnscheduledResult unscheduled = context.getUnscheduledResultList().get(0);
        Assertions.assertEquals("3302000836", unscheduled.getMaterialCode());
        Assertions.assertEquals(Integer.valueOf(0), unscheduled.getUnscheduledQty());
        Assertions.assertTrue(unscheduled.getUnscheduledReason().contains("共用胎胚且硫化余量为0"));
        Assertions.assertEquals(1, context.getStructureSkuMap().get("STRUCT-01").size());
        Assertions.assertEquals("3302002078", context.getStructureSkuMap().get("STRUCT-01").get(0).getMaterialCode());
        Assertions.assertEquals(Arrays.asList("3302002078"), context.getActiveEmbryoSkuMap().get("EMB-20260603"));
        Assertions.assertFalse(resolver.isSharedEmbryoInWindow(context, positiveSurplusSku));
        Assertions.assertEquals(8, targetQty);
        Assertions.assertEquals("02", positiveSurplusSku.getSkuTag());
        Assertions.assertTrue(context.getDynamicSingleEmbryoEndingMaterialSet().contains("3302002078"));
    }

    /**
     * 用例说明：三个SKU共用胎胚时，剔除一个零余量SKU后，剩余两个SKU仍按共用胎胚处理。
     *
     * @throws Exception 反射调用异常
     */
    @Test
    public void shouldKeepSharedEmbryoWhenTwoPositiveSurplusSkusRemainAfterPruning() throws Exception {
        ScheduleAdjustHandler handler = new ScheduleAdjustHandler();
        TargetScheduleQtyResolver resolver = new TargetScheduleQtyResolver();
        ReflectionTestUtils.setField(handler, "targetScheduleQtyResolver", resolver);
        ReflectionTestUtils.setField(handler, "endingJudgmentStrategy", buildNonEndingJudgmentStrategy());

        LhScheduleContext context = new LhScheduleContext();
        SkuScheduleDTO zeroSurplusSku = buildSku("3302000836", "EMB-20260603", 0, 6, 6);
        SkuScheduleDTO firstPositiveSku = buildSku("3302002078", "EMB-20260603", 5, 8, 5);
        SkuScheduleDTO secondPositiveSku = buildSku("3302002079", "EMB-20260603", 4, 7, 4);
        putStructureSkus(context, zeroSurplusSku, firstPositiveSku, secondPositiveSku);
        context.getActiveEmbryoSkuMap().put("EMB-20260603",
                new ArrayList<String>(Arrays.asList("3302000836", "3302002078", "3302002079")));

        invokePruneSharedEmbryoZeroSurplusSkus(handler, context);
        int targetQty = resolver.upsizeEndingTargetQty(context, firstPositiveSku);

        Assertions.assertEquals(1, context.getUnscheduledResultList().size());
        Assertions.assertEquals("3302000836", context.getUnscheduledResultList().get(0).getMaterialCode());
        Assertions.assertEquals(Arrays.asList("3302002078", "3302002079"),
                context.getActiveEmbryoSkuMap().get("EMB-20260603"));
        Assertions.assertTrue(resolver.isSharedEmbryoInWindow(context, firstPositiveSku));
        Assertions.assertEquals(5, targetQty);
    }

    /**
     * 用例说明：两个SKU共用胎胚且余量都为0时，两个SKU都写未排，胎胚组不再进入排产。
     *
     * @throws Exception 反射调用异常
     */
    @Test
    public void shouldPruneAllSkusWhenSharedEmbryoGroupHasNoPositiveSurplusSku() throws Exception {
        ScheduleAdjustHandler handler = new ScheduleAdjustHandler();
        TargetScheduleQtyResolver resolver = new TargetScheduleQtyResolver();
        ReflectionTestUtils.setField(handler, "targetScheduleQtyResolver", resolver);
        ReflectionTestUtils.setField(handler, "endingJudgmentStrategy", buildNonEndingJudgmentStrategy());

        LhScheduleContext context = new LhScheduleContext();
        SkuScheduleDTO firstZeroSurplusSku = buildSku("3302000836", "EMB-20260603", 0, 6, 6);
        SkuScheduleDTO secondZeroSurplusSku = buildSku("3302002078", "EMB-20260603", 0, 8, 8);
        putStructureSkus(context, firstZeroSurplusSku, secondZeroSurplusSku);
        context.getActiveEmbryoSkuMap().put("EMB-20260603",
                new ArrayList<String>(Arrays.asList("3302000836", "3302002078")));

        invokePruneSharedEmbryoZeroSurplusSkus(handler, context);

        Assertions.assertEquals(2, context.getUnscheduledResultList().size());
        Assertions.assertTrue(context.getStructureSkuMap().isEmpty());
        Assertions.assertFalse(context.getActiveEmbryoSkuMap().containsKey("EMB-20260603"));
    }

    /**
     * 用例说明：共用胎胚组内两个SKU余量均为0、胎胚收尾标识为是且胎胚库存大于0时，
     * 前一业务日(T-1)仍有日计划的生产者SKU必须保留，仅剔除非生产者SKU；
     * 非生产者剔除后生产者动态转为单胎胚，按胎胚库存收尾排产。
     *
     * @throws Exception 反射调用异常
     */
    @Test
    public void shouldRetainProducerCandidateAndPruneNonProducerWhenSharedEmbryoZeroSurplusEnding() throws Exception {
        ScheduleAdjustHandler handler = new ScheduleAdjustHandler();
        TargetScheduleQtyResolver resolver = new TargetScheduleQtyResolver();
        ReflectionTestUtils.setField(handler, "targetScheduleQtyResolver", resolver);
        ReflectionTestUtils.setField(handler, "endingJudgmentStrategy", buildNonEndingJudgmentStrategy());

        LhScheduleContext context = new LhScheduleContext();
        // 排程日2026-07-16，T-1为07-15，对应月计划DAY_15
        context.setScheduleDate(toDate(LocalDate.of(2026, 7, 16)));
        // 胎胚收尾标识为是，胎胚库存6需由生产者消纳
        context.getEmbryoEndingFlagMap().put("EMB-PRODUCER", 1);
        // 生产者前一业务日有日计划，非生产者无日计划
        context.getMonthPlanList().add(buildPlan("3302002225", 2026, 7, 15, 18));

        // 生产者：余量0、胎胚库存6、T-1有日计划 -> 保留
        SkuScheduleDTO producerSku = buildSku("3302002225", "EMB-PRODUCER", 0, 6, 6);
        // 非生产者：余量0、胎胚库存6、T-1无日计划 -> 剔除
        SkuScheduleDTO nonProducerSku = buildSku("3302001596", "EMB-PRODUCER", 0, 6, 6);
        putStructureSkus(context, producerSku, nonProducerSku);
        context.getActiveEmbryoSkuMap().put("EMB-PRODUCER",
                new ArrayList<String>(Arrays.asList("3302002225", "3302001596")));

        invokePruneSharedEmbryoZeroSurplusSkus(handler, context);

        // 非生产者写入未排，生产者保留在结构集合
        Assertions.assertEquals(1, context.getUnscheduledResultList().size());
        LhUnscheduledResult unscheduled = context.getUnscheduledResultList().get(0);
        Assertions.assertEquals("3302001596", unscheduled.getMaterialCode());
        Assertions.assertTrue(unscheduled.getUnscheduledReason().contains("共用胎胚且硫化余量为0"));
        Assertions.assertEquals(1, context.getStructureSkuMap().get("STRUCT-01").size());
        Assertions.assertEquals("3302002225", context.getStructureSkuMap().get("STRUCT-01").get(0).getMaterialCode());
        // 生产者动态转为单胎胚收尾，按胎胚库存排产
        Assertions.assertEquals(SkuTagEnum.ENDING.getCode(), producerSku.getSkuTag());
        Assertions.assertEquals(6, producerSku.resolveTargetScheduleQty());
        Assertions.assertTrue(context.getDynamicSingleEmbryoEndingMaterialSet().contains("3302002225"));
        Assertions.assertTrue(resolver.isEmbryoStockEnding(context, producerSku));
    }

    /**
     * 用例说明：胎胚收尾标识为是但胎胚库存为0时，无库存可消纳，零余量共用SKU仍全部剔除。
     *
     * @throws Exception 反射调用异常
     */
    @Test
    public void shouldPruneBothWhenEmbryoStockZeroEvenIfEndingFlagYes() throws Exception {
        ScheduleAdjustHandler handler = new ScheduleAdjustHandler();
        TargetScheduleQtyResolver resolver = new TargetScheduleQtyResolver();
        ReflectionTestUtils.setField(handler, "targetScheduleQtyResolver", resolver);
        ReflectionTestUtils.setField(handler, "endingJudgmentStrategy", buildNonEndingJudgmentStrategy());

        LhScheduleContext context = new LhScheduleContext();
        context.setScheduleDate(toDate(LocalDate.of(2026, 7, 16)));
        context.getEmbryoEndingFlagMap().put("EMB-NOSTOCK", 1);
        context.getMonthPlanList().add(buildPlan("3302002225", 2026, 7, 15, 18));

        // 胎胚库存为0，不满足生产者候选，两个零余量SKU均剔除
        SkuScheduleDTO firstSku = buildSku("3302002225", "EMB-NOSTOCK", 0, 0, 6);
        SkuScheduleDTO secondSku = buildSku("3302001596", "EMB-NOSTOCK", 0, 0, 6);
        putStructureSkus(context, firstSku, secondSku);
        context.getActiveEmbryoSkuMap().put("EMB-NOSTOCK",
                new ArrayList<String>(Arrays.asList("3302002225", "3302001596")));

        invokePruneSharedEmbryoZeroSurplusSkus(handler, context);

        Assertions.assertEquals(2, context.getUnscheduledResultList().size());
        Assertions.assertTrue(context.getStructureSkuMap().isEmpty());
        Assertions.assertFalse(context.getActiveEmbryoSkuMap().containsKey("EMB-NOSTOCK"));
    }

    /**
     * 用例说明：胎胚收尾标识为否时，即便前一业务日有日计划，零余量共用SKU仍全部剔除。
     *
     * @throws Exception 反射调用异常
     */
    @Test
    public void shouldPruneBothWhenEmbryoEndingFlagNoEvenWithPreviousDayPlan() throws Exception {
        ScheduleAdjustHandler handler = new ScheduleAdjustHandler();
        TargetScheduleQtyResolver resolver = new TargetScheduleQtyResolver();
        ReflectionTestUtils.setField(handler, "targetScheduleQtyResolver", resolver);
        ReflectionTestUtils.setField(handler, "endingJudgmentStrategy", buildNonEndingJudgmentStrategy());

        LhScheduleContext context = new LhScheduleContext();
        context.setScheduleDate(toDate(LocalDate.of(2026, 7, 16)));
        // 胎胚收尾标识为否（默认0），不属于清尾场景
        context.getEmbryoEndingFlagMap().put("EMB-NOTENDING", 0);
        context.getMonthPlanList().add(buildPlan("3302002225", 2026, 7, 15, 18));

        SkuScheduleDTO firstSku = buildSku("3302002225", "EMB-NOTENDING", 0, 6, 6);
        SkuScheduleDTO secondSku = buildSku("3302001596", "EMB-NOTENDING", 0, 6, 6);
        putStructureSkus(context, firstSku, secondSku);
        context.getActiveEmbryoSkuMap().put("EMB-NOTENDING",
                new ArrayList<String>(Arrays.asList("3302002225", "3302001596")));

        invokePruneSharedEmbryoZeroSurplusSkus(handler, context);

        Assertions.assertEquals(2, context.getUnscheduledResultList().size());
        Assertions.assertTrue(context.getStructureSkuMap().isEmpty());
        Assertions.assertFalse(context.getActiveEmbryoSkuMap().containsKey("EMB-NOTENDING"));
    }

    /**
     * 构建月计划记录，仅设置物料、年月与指定day字段的日计划量，供T-1日计划读取。
     *
     * @param materialCode 物料编码
     * @param year 年份
     * @param month 月份
     * @param day 月内日序号(1~31)
     * @param qty 日计划量
     * @return 月计划记录
     */
    private FactoryMonthPlanProductionFinalResult buildPlan(String materialCode, int year, int month, int day, int qty) {
        FactoryMonthPlanProductionFinalResult plan = new FactoryMonthPlanProductionFinalResult();
        plan.setMaterialCode(materialCode);
        plan.setYear(year);
        plan.setMonth(month);
        try {
            java.lang.reflect.Field field = FactoryMonthPlanProductionFinalResult.class.getDeclaredField("day" + day);
            field.setAccessible(true);
            field.set(plan, qty);
        } catch (Exception e) {
            throw new RuntimeException("设置月计划日计划量失败, day: " + day, e);
        }
        return plan;
    }

    /**
     * 将LocalDate转换为java.util.Date，避免java.sql.Date.toInstant()不支持。
     *
     * @param date 业务日期
     * @return java.util.Date
     */
    private Date toDate(LocalDate date) {
        return Date.from(date.atStartOfDay(ZoneId.systemDefault()).toInstant());
    }

    private void invokePruneSharedEmbryoZeroSurplusSkus(ScheduleAdjustHandler handler,
                                                        LhScheduleContext context) throws Exception {
        Method method = ScheduleAdjustHandler.class.getDeclaredMethod(
                "pruneSharedEmbryoZeroSurplusSkus", LhScheduleContext.class);
        method.setAccessible(true);
        method.invoke(handler, context);
    }

    private IEndingJudgmentStrategy buildEndingJudgmentStrategy() {
        return new IEndingJudgmentStrategy() {
            @Override
            public boolean isEnding(LhScheduleContext context, SkuScheduleDTO sku) {
                return true;
            }

            @Override
            public int calculateEndingShifts(LhScheduleContext context, SkuScheduleDTO sku) {
                return 1;
            }

            @Override
            public int calculateEndingDays(LhScheduleContext context, SkuScheduleDTO sku) {
                return 1;
            }
        };
    }

    private IEndingJudgmentStrategy buildNonEndingJudgmentStrategy() {
        return new IEndingJudgmentStrategy() {
            @Override
            public boolean isEnding(LhScheduleContext context, SkuScheduleDTO sku) {
                return false;
            }

            @Override
            public int calculateEndingShifts(LhScheduleContext context, SkuScheduleDTO sku) {
                return 0;
            }

            @Override
            public int calculateEndingDays(LhScheduleContext context, SkuScheduleDTO sku) {
                return 0;
            }
        };
    }

    private void putStructureSkus(LhScheduleContext context, SkuScheduleDTO... skus) {
        Map<String, List<SkuScheduleDTO>> structureSkuMap = new LinkedHashMap<String, List<SkuScheduleDTO>>(4);
        structureSkuMap.put("STRUCT-01", new ArrayList<SkuScheduleDTO>(Arrays.asList(skus)));
        context.setStructureSkuMap(structureSkuMap);
    }

    private SkuScheduleDTO buildSku(String materialCode,
                                    String embryoCode,
                                    int surplusQty,
                                    int embryoStock,
                                    int targetScheduleQty) {
        SkuScheduleDTO sku = new SkuScheduleDTO();
        sku.setMaterialCode(materialCode);
        sku.setEmbryoCode(embryoCode);
        sku.setSurplusQty(surplusQty);
        sku.setEmbryoStock(embryoStock);
        sku.setTargetScheduleQty(targetScheduleQty);
        sku.setRemainingScheduleQty(targetScheduleQty);
        return sku;
    }
}
