package com.zlt.aps.lh.component;

import com.zlt.aps.lh.api.domain.dto.SkuScheduleDTO;
import com.zlt.aps.lh.api.domain.entity.LhUnscheduledResult;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.engine.strategy.impl.NewSpecProductionStrategy;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * 收尾目标量与动态共用胎胚测试。
 *
 * @author APS
 */
public class TargetScheduleQtyResolverTest {

    private final TargetScheduleQtyResolver resolver = new TargetScheduleQtyResolver();

    /**
     * 用例说明：共用胎胚收尾 SKU 余量为0时，不能用胎胚库存抬高目标量。
     */
    @Test
    public void shouldNotUseEmbryoStockWhenSharedEmbryoEndingSurplusIsZero() {
        LhScheduleContext context = new LhScheduleContext();
        SkuScheduleDTO zeroSurplusSku = buildSku("3302002369", "EMB-01", 0, 2, 2);
        SkuScheduleDTO anotherSku = buildSku("3302002370", "EMB-01", 5, 8, 8);
        context.setNewSpecSkuList(Arrays.asList(zeroSurplusSku, anotherSku));
        context.getMaterialSharedEmbryoMap().put("3302002369", true);
        context.getMaterialSharedEmbryoMap().put("3302002370", true);
        resolver.refreshActiveEmbryoSkuMap(context);

        int targetQty = resolver.upsizeEndingTargetQty(context, zeroSurplusSku);

        Assertions.assertEquals(0, targetQty);
        Assertions.assertFalse(resolver.isSharedEmbryoInWindow(context, zeroSurplusSku));
        Assertions.assertTrue(context.getMaterialSharedEmbryoMap().get("3302002369"));
        Assertions.assertTrue(context.getMaterialSharedEmbryoMap().get("3302002370"));
        Assertions.assertEquals(Collections.singletonList("3302002370"),
                context.getActiveEmbryoSkuMap().get("EMB-01"));
    }

    /**
     * 用例说明：剔除余量为0的共用胎胚 SKU 后，剩余 SKU 应重新识别为单胎胚并按 MAX 规则计算。
     */
    @Test
    public void shouldReclassifyRemainingSkuAsSingleEmbryoAfterZeroSurplusSkuRemoved() {
        LhScheduleContext context = new LhScheduleContext();
        SkuScheduleDTO zeroSurplusSku = buildSku("3302002369", "EMB-01", 0, 2, 2);
        SkuScheduleDTO anotherSku = buildSku("3302002370", "EMB-01", 5, 8, 5);
        context.setNewSpecSkuList(Arrays.asList(zeroSurplusSku, anotherSku));
        context.getMaterialSharedEmbryoMap().put("3302002369", true);
        context.getMaterialSharedEmbryoMap().put("3302002370", true);
        resolver.refreshActiveEmbryoSkuMap(context);
        resolver.upsizeEndingTargetQty(context, zeroSurplusSku);

        int targetQty = resolver.upsizeEndingTargetQty(context, anotherSku);

        Assertions.assertEquals(8, targetQty);
        Assertions.assertFalse(resolver.isSharedEmbryoInWindow(context, anotherSku));
    }

    /**
     * 用例说明：两个同胎胚 SKU 余量都大于0时，仍按动态共用胎胚识别。
     */
    @Test
    public void shouldKeepSharedEmbryoWhenBothSkusHavePositiveSurplus() {
        LhScheduleContext context = new LhScheduleContext();
        SkuScheduleDTO firstSku = buildSku("3302002369", "EMB-01", 3, 9, 9);
        SkuScheduleDTO secondSku = buildSku("3302002370", "EMB-01", 5, 8, 8);
        context.setNewSpecSkuList(Arrays.asList(firstSku, secondSku));
        context.getMaterialSharedEmbryoMap().put("3302002369", true);
        context.getMaterialSharedEmbryoMap().put("3302002370", true);
        resolver.refreshActiveEmbryoSkuMap(context);

        int targetQty = resolver.upsizeEndingTargetQty(context, firstSku);

        Assertions.assertEquals(3, targetQty);
        Assertions.assertTrue(resolver.isSharedEmbryoInWindow(context, secondSku));
        Assertions.assertTrue(context.getMaterialSharedEmbryoMap().get("3302002369"));
        Assertions.assertTrue(context.getMaterialSharedEmbryoMap().get("3302002370"));
    }

    /**
     * 用例说明：共用胎胚中一个 SKU 收尾完成后，后续分摊必须剔除该 SKU，并让最后一个 SKU 承接尾差。
     */
    @Test
    public void shouldReallocateSharedEmbryoStockAfterCompletedSkuRemoved() {
        LhScheduleContext context = new LhScheduleContext();
        SkuScheduleDTO completedSku = buildEndingSku("3302002369", "EMB-01", 1, 30, 1);
        SkuScheduleDTO firstRemainingSku = buildEndingSku("3302002370", "EMB-01", 1, 30, 1);
        SkuScheduleDTO secondRemainingSku = buildEndingSku("3302002371", "EMB-01", 1, 30, 2);
        context.setNewSpecSkuList(Arrays.asList(completedSku, firstRemainingSku, secondRemainingSku));
        context.getEmbryoRealtimeStockMap().put("EMB-01", 100);
        resolver.refreshActiveEmbryoSkuMap(context);

        resolver.removeActiveEmbryoSku(context, completedSku, "收尾完成");

        Assertions.assertEquals(Arrays.asList("3302002370", "3302002371"),
                context.getActiveEmbryoSkuMap().get("EMB-01"));
        Assertions.assertEquals(33, firstRemainingSku.getEmbryoStock());
        Assertions.assertEquals(67, secondRemainingSku.getEmbryoStock());
    }

    /**
     * 用例说明：共用胎胚剔除后只剩一个 SKU 时，后续应按单胎胚使用完整胎胚库存。
     */
    @Test
    public void shouldUseFullEmbryoStockWhenOnlyOneSkuRemainsAfterRemoval() {
        LhScheduleContext context = new LhScheduleContext();
        SkuScheduleDTO completedSku = buildEndingSku("3302002369", "EMB-01", 1, 45, 1);
        SkuScheduleDTO remainingSku = buildEndingSku("3302002370", "EMB-01", 1, 55, 1);
        context.setNewSpecSkuList(Arrays.asList(completedSku, remainingSku));
        context.getEmbryoRealtimeStockMap().put("EMB-01", 100);
        resolver.refreshActiveEmbryoSkuMap(context);

        resolver.removeActiveEmbryoSku(context, completedSku, "收尾完成");

        Assertions.assertEquals(Collections.singletonList("3302002370"),
                context.getActiveEmbryoSkuMap().get("EMB-01"));
        Assertions.assertEquals(100, remainingSku.getEmbryoStock());
        Assertions.assertFalse(resolver.isSharedEmbryoInWindow(context, remainingSku));
    }

    /**
     * 用例说明：非共用胎胚收尾 SKU 余量为0且胎胚库存大于0时，仍按 MAX 规则排产。
     */
    @Test
    public void shouldUseMaxRuleForSingleEmbryoEndingWhenSurplusIsZero() {
        LhScheduleContext context = new LhScheduleContext();
        SkuScheduleDTO sku = buildSku("3302002369", "EMB-01", 0, 2, 2);
        context.setNewSpecSkuList(Collections.singletonList(sku));
        resolver.refreshActiveEmbryoSkuMap(context);

        int targetQty = resolver.upsizeEndingTargetQty(context, sku);

        Assertions.assertEquals(2, targetQty);
        Assertions.assertFalse(resolver.isSharedEmbryoInWindow(context, sku));
    }

    /**
     * 用例说明：双模收尾 SKU 目标量为奇数时，应按模台数向上收敛到偶数。
     */
    @Test
    public void shouldRoundEndingTargetUpToMouldMultipleForDoubleMouldSku() {
        LhScheduleContext context = new LhScheduleContext();
        SkuScheduleDTO sku = buildSku("3302002530", "EMB-02", 3, 3, 3);
        sku.setMouldQty(2);
        context.setNewSpecSkuList(Collections.singletonList(sku));
        resolver.refreshActiveEmbryoSkuMap(context);

        int targetQty = resolver.upsizeEndingTargetQty(context, sku);

        Assertions.assertEquals(4, targetQty);
        Assertions.assertEquals(4, sku.resolveTargetScheduleQty());
        Assertions.assertEquals(4, sku.getRemainingScheduleQty());
    }

    /**
     * 用例说明：当前SKU初始目标量已经为0时，仍要先按动态有效SKU集合识别共用胎胚。
     */
    @Test
    public void shouldKeepZeroTargetSkuActiveBeforeEndingDecision() {
        LhScheduleContext context = new LhScheduleContext();
        SkuScheduleDTO zeroTargetSku = buildSku("3302002369", "EMB-01", 0, 2, 0);
        SkuScheduleDTO anotherSku = buildSku("3302002370", "EMB-01", 5, 8, 8);
        context.setNewSpecSkuList(Arrays.asList(zeroTargetSku, anotherSku));
        resolver.refreshActiveEmbryoSkuMap(context);

        int targetQty = resolver.upsizeEndingTargetQty(context, zeroTargetSku);

        Assertions.assertEquals(0, targetQty);
        Assertions.assertEquals(Collections.singletonList("3302002370"),
                context.getActiveEmbryoSkuMap().get("EMB-01"));
    }

    /**
     * 用例说明：S4.5新增链路命中共用胎胚零余量收尾时，应直接写入未排并移出待排队列。
     *
     * @throws Exception 反射调用异常
     */
    @Test
    public void shouldAppendUnscheduledWhenNewSpecSharedEmbryoEndingTargetIsZero() throws Exception {
        LhScheduleContext context = new LhScheduleContext();
        SkuScheduleDTO zeroSurplusSku = buildSku("3302002369", "EMB-01", 0, 2, 2);
        SkuScheduleDTO anotherSku = buildSku("3302002370", "EMB-01", 5, 8, 8);
        context.setNewSpecSkuList(new ArrayList<SkuScheduleDTO>(Arrays.asList(zeroSurplusSku, anotherSku)));
        resolver.refreshActiveEmbryoSkuMap(context);
        boolean sharedZeroEnding = resolver.isSharedEmbryoZeroSurplusEnding(context, zeroSurplusSku);
        resolver.upsizeEndingTargetQty(context, zeroSurplusSku);
        Iterator<SkuScheduleDTO> iterator = context.getNewSpecSkuList().iterator();
        iterator.next();
        Map<String, Integer> reasonCountMap = new HashMap<String, Integer>(4);

        boolean handled = invokeHandleSharedEmbryoZeroSurplusEnding(
                new NewSpecProductionStrategy(), context, iterator, zeroSurplusSku, sharedZeroEnding, reasonCountMap);

        Assertions.assertTrue(handled);
        Assertions.assertEquals(1, context.getUnscheduledResultList().size());
        LhUnscheduledResult unscheduled = context.getUnscheduledResultList().get(0);
        Assertions.assertEquals("3302002369", unscheduled.getMaterialCode());
        Assertions.assertEquals(Integer.valueOf(0), unscheduled.getUnscheduledQty());
        Assertions.assertTrue(unscheduled.getUnscheduledReason().contains("共用胎胚且硫化余量为0"));
        Assertions.assertEquals(1, context.getNewSpecSkuList().size());
        Assertions.assertEquals("3302002370", context.getNewSpecSkuList().get(0).getMaterialCode());
    }

    private boolean invokeHandleSharedEmbryoZeroSurplusEnding(NewSpecProductionStrategy strategy,
                                                              LhScheduleContext context,
                                                              Iterator<SkuScheduleDTO> iterator,
                                                              SkuScheduleDTO sku,
                                                              boolean sharedEmbryoZeroSurplusEnding,
                                                              Map<String, Integer> reasonCountMap) throws Exception {
        Method method = NewSpecProductionStrategy.class.getDeclaredMethod(
                "handleSharedEmbryoZeroSurplusEndingIfNecessary",
                LhScheduleContext.class, Iterator.class, SkuScheduleDTO.class, boolean.class, Map.class);
        method.setAccessible(true);
        return (Boolean) method.invoke(strategy, context, iterator, sku, sharedEmbryoZeroSurplusEnding, reasonCountMap);
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

    private SkuScheduleDTO buildEndingSku(String materialCode,
                                          String embryoCode,
                                          int endingDays,
                                          int embryoStock,
                                          int dailyCapacity) {
        SkuScheduleDTO sku = buildSku(materialCode, embryoCode, 10, embryoStock, 10);
        sku.setSkuTag("02");
        sku.setEndingDaysRemaining(endingDays);
        sku.setDailyCapacity(dailyCapacity);
        return sku;
    }
}
