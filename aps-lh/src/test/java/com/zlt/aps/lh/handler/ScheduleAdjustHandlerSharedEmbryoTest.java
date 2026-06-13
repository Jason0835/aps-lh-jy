package com.zlt.aps.lh.handler;

import com.zlt.aps.lh.api.domain.dto.SkuScheduleDTO;
import com.zlt.aps.lh.api.domain.entity.LhUnscheduledResult;
import com.zlt.aps.lh.component.TargetScheduleQtyResolver;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.engine.strategy.IEndingJudgmentStrategy;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
