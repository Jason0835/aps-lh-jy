package com.zlt.aps.lh.component;

import com.zlt.aps.lh.api.domain.dto.SkuScheduleDTO;
import com.zlt.aps.lh.api.domain.dto.SkuDailyPlanQuotaDTO;
import com.zlt.aps.lh.api.domain.entity.LhScheduleResult;
import com.zlt.aps.lh.api.domain.entity.LhUnscheduledResult;
import com.zlt.aps.lh.api.domain.vo.LhShiftConfigVO;
import com.zlt.aps.lh.context.EmbryoStockConsumeLedger;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.engine.strategy.impl.NewSpecProductionStrategy;
import com.zlt.aps.lh.util.ShiftFieldUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
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
        setScheduleDate(context);
        context.getEmbryoEndingFlagMap().put("EMB-01", 1);
        SkuScheduleDTO completedSku = buildEndingSku("3302002369", "EMB-01", 1, 30, 1);
        SkuScheduleDTO firstRemainingSku = buildEndingSku("3302002370", "EMB-01", 1, 30, 1);
        SkuScheduleDTO secondRemainingSku = buildEndingSku("3302002371", "EMB-01", 1, 30, 2);
        context.setNewSpecSkuList(Arrays.asList(completedSku, firstRemainingSku, secondRemainingSku));
        context.getEmbryoRealtimeStockMap().put("EMB-01", 100);
        resolver.refreshActiveEmbryoSkuMap(context);

        resolver.removeActiveEmbryoSku(context, completedSku, "收尾完成");

        Assertions.assertEquals(Arrays.asList("3302002370", "3302002371"),
                context.getActiveEmbryoSkuMap().get("EMB-01"));
        Assertions.assertEquals(100, firstRemainingSku.getEmbryoStock());
        Assertions.assertEquals(100, secondRemainingSku.getEmbryoStock());
        Assertions.assertEquals(Integer.valueOf(33),
                context.getEmbryoStockSkuQuotaMap().get("3302002370"));
        Assertions.assertEquals(Integer.valueOf(67),
                context.getEmbryoStockSkuQuotaMap().get("3302002371"));
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
     * 用例说明：成型胎胚库存收尾优先于SKU收尾规则，必须严格按胎胚库存排产，不做模台数向上修正。
     */
    @Test
    public void shouldUseEmbryoStockEndingTargetWithoutMouldRounding() {
        LhScheduleContext context = new LhScheduleContext();
        setScheduleDate(context);
        context.getEmbryoEndingFlagMap().put("EMB-02", 1);
        SkuScheduleDTO sku = buildSku("3302002530", "EMB-02", 10, 3, 10);
        sku.setSkuTag("02");
        sku.setEndingDaysRemaining(1);
        sku.setMouldQty(2);

        int targetQty = resolver.upsizeEndingTargetQty(context, sku);

        Assertions.assertEquals(3, targetQty);
        Assertions.assertEquals(3, sku.resolveTargetScheduleQty());
        Assertions.assertEquals(3, sku.getRemainingScheduleQty());
        Assertions.assertTrue(sku.isStrictTargetQty());
        Assertions.assertEquals(Integer.valueOf(3),
                context.getSkuProductionRemainingQtyMap().get("3302002530"));
    }

    /**
     * 用例说明：成型胎胚库存收尾必须同时满足SKU在T日收尾；非T日收尾不按库存硬目标排产。
     */
    @Test
    public void shouldNotApplyEmbryoStockEndingTargetForNonTDayEndingSku() {
        LhScheduleContext context = new LhScheduleContext();
        setScheduleDate(context);
        context.getEmbryoEndingFlagMap().put("EMB-03", 1);
        SkuScheduleDTO sku = buildSku("3302003005", "EMB-03", 80, 5, 80);
        sku.setMouldQty(2);

        boolean applied = resolver.applyEmbryoStockEndingTargetQtyIfNecessary(context, sku, "新增排产");

        Assertions.assertFalse(applied);
        Assertions.assertNull(sku.getSkuTag());
        Assertions.assertEquals(80, sku.resolveTargetScheduleQty());
        Assertions.assertEquals(80, sku.getRemainingScheduleQty());
        Assertions.assertFalse(context.getSkuProductionRemainingQtyMap().containsKey("3302003005"));
        Assertions.assertTrue(context.getEmbryoStockConsumeLedgerMap().isEmpty());
    }

    /**
     * 用例说明：未配置成型胎胚库存收尾时，SKU收尾仍沿用现有MAX和模台数修正规则。
     */
    @Test
    public void shouldKeepExistingEndingRuleWhenEmbryoStockEndingIsZero() {
        LhScheduleContext context = new LhScheduleContext();
        context.getEmbryoEndingFlagMap().put("EMB-04", 0);
        SkuScheduleDTO sku = buildSku("3302003006", "EMB-04", 3, 3, 3);
        sku.setMouldQty(2);

        int targetQty = resolver.upsizeEndingTargetQty(context, sku);

        Assertions.assertEquals(4, targetQty);
        Assertions.assertEquals(4, sku.resolveTargetScheduleQty());
    }

    /**
     * 用例说明：成型胎胚库存收尾目标量高于当前账本时，需要补齐运行态日计划账本，避免后续被dayN回裁。
     */
    @Test
    public void shouldSyncDailyQuotaWhenEmbryoStockEndingTargetExceedsQuota() {
        LhScheduleContext context = new LhScheduleContext();
        setScheduleDate(context);
        context.getEmbryoEndingFlagMap().put("EMB-05", 1);
        SkuScheduleDTO sku = buildSku("3302003007", "EMB-05", 2, 5, 2);
        sku.setSkuTag("02");
        sku.setEndingDaysRemaining(1);
        Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap = new LinkedHashMap<LocalDate, SkuDailyPlanQuotaDTO>(4);
        quotaMap.put(LocalDate.of(2026, 6, 29), buildQuota(2, 2));
        sku.setDailyPlanQuotaMap(quotaMap);
        sku.setWindowPlanQty(2);
        sku.setWindowRemainingPlanQty(2);

        boolean applied = resolver.applyEmbryoStockEndingTargetQtyIfNecessary(context, sku, "新增排产");

        Assertions.assertTrue(applied);
        Assertions.assertEquals(5, sku.resolveTargetScheduleQty());
        Assertions.assertEquals(5, sku.getWindowPlanQty());
        Assertions.assertEquals(5, sku.getWindowRemainingPlanQty());
        Assertions.assertEquals(5, quotaMap.get(LocalDate.of(2026, 6, 29)).getRemainingQty());
    }

    /**
     * 用例说明：成型胎胚库存收尾跨入口重复应用时，不能把已扣减的实际消费账本恢复成完整胎胚库存。
     */
    @Test
    public void shouldKeepDeductedLedgerWhenEmbryoStockEndingAppliedAgain() {
        LhScheduleContext context = new LhScheduleContext();
        setScheduleDate(context);
        context.getEmbryoEndingFlagMap().put("EMB-06", 1);
        SkuScheduleDTO sku = buildSku("3302003008", "EMB-06", 10, 5, 10);
        sku.setSkuTag("02");
        sku.setEndingDaysRemaining(1);

        boolean firstApplied = resolver.applyEmbryoStockEndingTargetQtyIfNecessary(context, sku, "换活字块");
        int deductedQty = resolver.deductProductionRemainingQty(context, sku, 3, "换活字块", "K1105");
        sku.setTargetScheduleQty(10);
        sku.setRemainingScheduleQty(10);
        boolean secondApplied = resolver.applyEmbryoStockEndingTargetQtyIfNecessary(context, sku, "新增排产");

        Assertions.assertTrue(firstApplied);
        Assertions.assertEquals(3, deductedQty);
        Assertions.assertTrue(secondApplied);
        Assertions.assertEquals(5, sku.resolveTargetScheduleQty());
        Assertions.assertEquals(2, sku.getRemainingScheduleQty());
        Assertions.assertEquals(Integer.valueOf(2),
                context.getSkuProductionRemainingQtyMap().get("3302003008"));
        EmbryoStockConsumeLedger ledger = context.getEmbryoStockConsumeLedgerMap().values().iterator().next();
        Assertions.assertEquals(5, ledger.getTargetQty().intValue());
        Assertions.assertEquals(3, ledger.getConsumedQty().intValue());
        Assertions.assertEquals(2, ledger.getRemainQty().intValue());
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
     * 用例说明：非收尾 SKU 的实际消费账本应以硫化余量为准，不被 dayN 目标量截断。
     */
    @Test
    public void shouldInitializeProductionLedgerBySurplusForNonEndingSku() {
        LhScheduleContext context = new LhScheduleContext();
        SkuScheduleDTO sku = buildSku("3302003001", "EMB-02", 300, 0, 16);

        int remainingQty = resolver.resolveProductionRemainingQty(context, sku);

        Assertions.assertEquals(300, remainingQty);
        Assertions.assertEquals(Integer.valueOf(300),
                context.getSkuProductionRemainingQtyMap().get("3302003001"));
    }

    /**
     * 用例说明：同一 SKU 多机台排产必须共享同一实际消费账本。
     */
    @Test
    public void shouldShareProductionLedgerAcrossSameMaterialSkuCopies() {
        LhScheduleContext context = new LhScheduleContext();
        SkuScheduleDTO firstSku = buildSku("3302003001", "EMB-02", 300, 0, 16);
        SkuScheduleDTO secondSku = buildSku("3302003001", "EMB-02", 300, 0, 16);
        resolver.resolveProductionRemainingQty(context, firstSku);

        int deductedQty = resolver.deductProductionRemainingQty(
                context, secondSku, 96, "新增排产", "K1105");

        Assertions.assertEquals(96, deductedQty);
        Assertions.assertEquals(204, resolver.resolveProductionRemainingQty(context, firstSku));
        Assertions.assertEquals(Integer.valueOf(204),
                context.getSkuProductionRemainingQtyMap().get("3302003001"));
    }

    /**
     * 用例说明：同物料不同产品状态必须使用独立实际消费账本。
     */
    @Test
    public void shouldIsolateProductionLedgerByProductStatus() {
        LhScheduleContext context = new LhScheduleContext();
        SkuScheduleDTO formalSku = buildSku("3302001404", "EMB-MULTI", 10, 0, 10);
        formalSku.setProductStatus("S");
        SkuScheduleDTO trialSku = buildSku("3302001404", "EMB-MULTI", 4, 0, 4);
        trialSku.setProductStatus("T");
        resolver.resolveProductionRemainingQty(context, formalSku);
        resolver.resolveProductionRemainingQty(context, trialSku);

        int deductedQty = resolver.deductProductionRemainingQty(
                context, formalSku, 3, "新增排产", "K1210");

        Assertions.assertEquals(3, deductedQty);
        Assertions.assertEquals(7, resolver.resolveProductionRemainingQty(context, formalSku));
        Assertions.assertEquals(4, resolver.resolveProductionRemainingQty(context, trialSku));
        Assertions.assertEquals(Integer.valueOf(7),
                context.getSkuProductionRemainingQtyMap().get("3302001404_S"));
        Assertions.assertEquals(Integer.valueOf(4),
                context.getSkuProductionRemainingQtyMap().get("3302001404_T"));
    }

    /**
     * 用例说明：同物料多产品状态独立分配SKU额度，但必须共同消费同一胎胚组级库存。
     */
    @Test
    public void shouldShareEmbryoStockLedgerAcrossProductStatuses() {
        LhScheduleContext context = new LhScheduleContext();
        setScheduleDate(context);
        context.getEmbryoEndingFlagMap().put("EMB-MULTI", 1);
        context.getEmbryoRealtimeStockMap().put("EMB-MULTI", 100);
        SkuScheduleDTO formalSku = buildEndingSku("3302001404", "EMB-MULTI", 1, 100, 1);
        formalSku.setProductStatus("S");
        SkuScheduleDTO trialSku = buildEndingSku("3302001404", "EMB-MULTI", 1, 100, 1);
        trialSku.setProductStatus("T");
        context.setNewSpecSkuList(Arrays.asList(formalSku, trialSku));
        resolver.refreshActiveEmbryoSkuMap(context);
        resolver.refreshAllSharedEmbryoStockAllocations(context, "多状态胎胚库存测试");
        LhShiftConfigVO shift = new LhShiftConfigVO();
        shift.setShiftIndex(1);

        LhScheduleResult formalResult = buildResult("3302001404", "EMB-MULTI", "K1210", 80);
        int formalCappedQty = resolver.capResultByProductionRemainingQty(
                context, formalSku, formalResult, Collections.singletonList(shift), "续作排产");
        resolver.deductProductionRemainingQty(context, formalSku, formalCappedQty, "续作排产", "K1210");
        LhScheduleResult trialResult = buildResult("3302001404", "EMB-MULTI", "K1211", 80);
        int trialCappedQty = resolver.capResultByProductionRemainingQty(
                context, trialSku, trialResult, Collections.singletonList(shift), "新增排产");
        resolver.deductProductionRemainingQty(context, trialSku, trialCappedQty, "新增排产", "K1211");

        Assertions.assertEquals(Integer.valueOf(50),
                context.getEmbryoStockSkuQuotaMap().get("3302001404_S"));
        Assertions.assertEquals(Integer.valueOf(50),
                context.getEmbryoStockSkuQuotaMap().get("3302001404_T"));
        Assertions.assertEquals(50, formalCappedQty);
        Assertions.assertEquals(50, trialCappedQty);
        EmbryoStockConsumeLedger ledger = context.getEmbryoStockConsumeLedgerMap().values().iterator().next();
        Assertions.assertEquals(100, ledger.getConsumedQty().intValue());
        Assertions.assertEquals(0, ledger.getRemainQty().intValue());
    }

    /**
     * 用例说明：结果行超过SKU实际消费账本剩余量时，应裁剪到剩余额度。
     */
    @Test
    public void shouldCapResultByProductionLedgerRemainingQty() {
        LhScheduleContext context = new LhScheduleContext();
        SkuScheduleDTO sku = buildSku("3302003003", "EMB-02", 100, 0, 16);
        LhScheduleResult result = new LhScheduleResult();
        result.setMaterialCode("3302003003");
        result.setLhMachineCode("K1106");
        ShiftFieldUtil.setShiftPlanQty(result, 1, 80, new Date(), null);
        ShiftFieldUtil.setShiftPlanQty(result, 2, 40, new Date(), null);
        ShiftFieldUtil.syncDailyPlanQty(result);
        LhShiftConfigVO firstShift = new LhShiftConfigVO();
        firstShift.setShiftIndex(1);
        LhShiftConfigVO secondShift = new LhShiftConfigVO();
        secondShift.setShiftIndex(2);

        int cappedQty = resolver.capResultByProductionRemainingQty(
                context, sku, result, Arrays.asList(firstShift, secondShift), "新增排产");

        Assertions.assertEquals(100, cappedQty);
        Assertions.assertEquals(Integer.valueOf(80), ShiftFieldUtil.getShiftPlanQty(result, 1));
        Assertions.assertEquals(Integer.valueOf(20), ShiftFieldUtil.getShiftPlanQty(result, 2));
        Assertions.assertEquals(Integer.valueOf(100), result.getDailyPlanQty());
    }

    /**
     * 用例说明：收尾补满允许超量只放宽已登记的补满部分，普通超排仍必须按实际消费账本回裁。
     */
    @Test
    public void shouldRetainEndingFillAllowedOverQtyWhenCappingProductionLedger() {
        LhScheduleContext context = new LhScheduleContext();
        SkuScheduleDTO sku = buildSku("3302003008", "EMB-11", 8, 0, 8);
        sku.setSkuTag("02");
        sku.setStrictTargetQty(true);
        context.getSkuProductionRemainingQtyMap().put("3302003008", 8);
        LhScheduleResult result = new LhScheduleResult();
        result.setMaterialCode("3302003008");
        result.setLhMachineCode("K1109");
        result.setMouldQty(2);
        ShiftFieldUtil.setShiftPlanQty(result, 1, 40, new Date(), null);
        ShiftFieldUtil.syncDailyPlanQty(result);
        context.getEndingFillAllowedOverQtyMap().put(result, 28);
        LhShiftConfigVO firstShift = new LhShiftConfigVO();
        firstShift.setShiftIndex(1);

        int cappedQty = resolver.capResultByProductionRemainingQty(
                context, sku, result, Collections.singletonList(firstShift), "续作排产");

        Assertions.assertEquals(36, cappedQty);
        Assertions.assertEquals(Integer.valueOf(36), ShiftFieldUtil.getShiftPlanQty(result, 1));
        Assertions.assertEquals(Integer.valueOf(36), result.getDailyPlanQty());
    }

    /**
     * 用例说明：双模结果按实际消费账本回裁时，班次量不能被裁成奇数或非模数倍。
     */
    @Test
    public void shouldCapDoubleMouldResultByMouldMultiple() {
        LhScheduleContext context = new LhScheduleContext();
        SkuScheduleDTO sku = buildSku("3302003004", "EMB-04", 69, 0, 69);
        LhScheduleResult result = new LhScheduleResult();
        result.setMaterialCode("3302003004");
        result.setLhMachineCode("K1107");
        result.setMouldQty(2);
        ShiftFieldUtil.setShiftPlanQty(result, 1, 48, new Date(), null);
        ShiftFieldUtil.setShiftPlanQty(result, 2, 48, new Date(), null);
        ShiftFieldUtil.syncDailyPlanQty(result);
        LhShiftConfigVO firstShift = new LhShiftConfigVO();
        firstShift.setShiftIndex(1);
        LhShiftConfigVO secondShift = new LhShiftConfigVO();
        secondShift.setShiftIndex(2);

        int cappedQty = resolver.capResultByProductionRemainingQty(
                context, sku, result, Arrays.asList(firstShift, secondShift), "新增排产");

        Assertions.assertEquals(68, cappedQty);
        Assertions.assertEquals(Integer.valueOf(48), ShiftFieldUtil.getShiftPlanQty(result, 1));
        Assertions.assertEquals(Integer.valueOf(20), ShiftFieldUtil.getShiftPlanQty(result, 2));
        Assertions.assertEquals(Integer.valueOf(68), result.getDailyPlanQty());
    }

    /**
     * 用例说明：成型胎胚库存收尾按奇数胎胚库存严格落地，班次分配和账本回裁都不能按模台数修正。
     */
    @Test
    public void shouldKeepOddQtyWhenEmbryoStockEndingAllocatesAndCapsResult() {
        LhScheduleContext context = new LhScheduleContext();
        setScheduleDate(context);
        context.getEmbryoEndingFlagMap().put("EMB-07", 1);
        SkuScheduleDTO sku = buildSku("3302003009", "EMB-07", 10, 3, 3);
        sku.setSkuTag("02");
        sku.setEndingDaysRemaining(1);
        sku.setMouldQty(2);
        resolver.applyEmbryoStockEndingTargetQtyIfNecessary(context, sku, "新增排产");
        int allocatedQty = resolver.resolveAllocatedShiftQty(context, sku, 3, 16, 2);
        LhScheduleResult result = new LhScheduleResult();
        result.setMaterialCode("3302003009");
        result.setEmbryoCode("EMB-07");
        result.setLhMachineCode("K1108");
        result.setMouldQty(2);
        ShiftFieldUtil.setShiftPlanQty(result, 1, 4, new Date(), null);
        ShiftFieldUtil.syncDailyPlanQty(result);
        context.getSkuProductionRemainingQtyMap().put("3302003009", 3);
        LhShiftConfigVO firstShift = new LhShiftConfigVO();
        firstShift.setShiftIndex(1);

        int cappedQty = resolver.capResultByProductionRemainingQty(
                context, sku, result, Collections.singletonList(firstShift), "新增排产");

        Assertions.assertEquals(3, allocatedQty);
        Assertions.assertEquals(3, cappedQty);
        Assertions.assertEquals(Integer.valueOf(3), ShiftFieldUtil.getShiftPlanQty(result, 1));
        Assertions.assertEquals(Integer.valueOf(3), result.getDailyPlanQty());
    }

    /**
     * 用例说明：单胎胚在T日胎胚收尾时，必须按原始胎胚库存建立独立账本并作为硬目标排产。
     */
    @Test
    public void shouldCreateLedgerForSingleEmbryoTDayStockEnding() {
        LhScheduleContext context = new LhScheduleContext();
        setScheduleDate(context);
        context.getEmbryoEndingFlagMap().put("EMB-08", 1);
        SkuScheduleDTO sku = buildSku("3302003010", "EMB-08", 80, 5, 80);
        sku.setSkuTag("02");
        sku.setEndingDaysRemaining(1);
        sku.setMouldQty(2);

        boolean applied = resolver.applyEmbryoStockEndingTargetQtyIfNecessary(context, sku, "新增排产");

        Assertions.assertTrue(applied);
        Assertions.assertEquals(5, sku.resolveTargetScheduleQty());
        Assertions.assertEquals(5, sku.getRemainingScheduleQty());
        Assertions.assertEquals(Integer.valueOf(5),
                context.getSkuProductionRemainingQtyMap().get("3302003010"));
        EmbryoStockConsumeLedger ledger = context.getEmbryoStockConsumeLedgerMap().values().iterator().next();
        Assertions.assertEquals("EMB-08", ledger.getEmbryoCode());
        Assertions.assertEquals(LocalDate.of(2026, 7, 2), ledger.getScheduleDate());
        Assertions.assertEquals(5, ledger.getOriginalStockQty().intValue());
        Assertions.assertEquals(5, ledger.getTargetQty().intValue());
        Assertions.assertEquals(0, ledger.getConsumedQty().intValue());
        Assertions.assertEquals(5, ledger.getRemainQty().intValue());
    }

    /**
     * 用例说明：共用胎胚T日同日收尾时，库存落库值保留原始库存，内部按SKU额度和组级账本扣减。
     */
    @Test
    public void shouldUseSharedEmbryoLedgerAndKeepOriginalStockOnSku() {
        LhScheduleContext context = new LhScheduleContext();
        setScheduleDate(context);
        context.getEmbryoEndingFlagMap().put("EMB-09", 1);
        context.getEmbryoRealtimeStockMap().put("EMB-09", 100);
        SkuScheduleDTO firstSku = buildEndingSku("3302003011", "EMB-09", 1, 100, 1);
        SkuScheduleDTO secondSku = buildEndingSku("3302003012", "EMB-09", 1, 100, 2);
        context.setNewSpecSkuList(Arrays.asList(firstSku, secondSku));
        resolver.refreshActiveEmbryoSkuMap(context);
        LhShiftConfigVO firstShift = new LhShiftConfigVO();
        firstShift.setShiftIndex(1);

        resolver.refreshAllSharedEmbryoStockAllocations(context, "测试初始化");
        LhScheduleResult firstResult = buildResult("3302003011", "EMB-09", "K1105", 80);
        int firstCappedQty = resolver.capResultByProductionRemainingQty(
                context, firstSku, firstResult, Collections.singletonList(firstShift), "新增排产");
        int firstDeductedQty = resolver.deductProductionRemainingQty(
                context, firstSku, firstCappedQty, "新增排产", "K1105");
        LhScheduleResult secondResult = buildResult("3302003012", "EMB-09", "K1106", 90);
        int secondCappedQty = resolver.capResultByProductionRemainingQty(
                context, secondSku, secondResult, Collections.singletonList(firstShift), "新增排产");
        int secondDeductedQty = resolver.deductProductionRemainingQty(
                context, secondSku, secondCappedQty, "新增排产", "K1106");

        Assertions.assertEquals(100, firstSku.getEmbryoStock());
        Assertions.assertEquals(100, secondSku.getEmbryoStock());
        Assertions.assertEquals(Integer.valueOf(33),
                context.getEmbryoStockSkuQuotaMap().get("3302003011"));
        Assertions.assertEquals(Integer.valueOf(67),
                context.getEmbryoStockSkuQuotaMap().get("3302003012"));
        Assertions.assertEquals(33, firstCappedQty);
        Assertions.assertEquals(33, firstDeductedQty);
        Assertions.assertEquals(67, secondCappedQty);
        Assertions.assertEquals(67, secondDeductedQty);
        EmbryoStockConsumeLedger ledger = context.getEmbryoStockConsumeLedgerMap().values().iterator().next();
        Assertions.assertEquals(100, ledger.getTargetQty().intValue());
        Assertions.assertEquals(100, ledger.getConsumedQty().intValue());
        Assertions.assertEquals(0, ledger.getRemainQty().intValue());
    }

    /**
     * 用例说明：收尾目标量上调后，实际消费账本应同步覆盖为收尾目标量。
     */
    @Test
    public void shouldSyncProductionLedgerWhenEndingTargetUpsized() {
        LhScheduleContext context = new LhScheduleContext();
        SkuScheduleDTO sku = buildSku("3302003002", "EMB-03", 20, 80, 20);
        context.setNewSpecSkuList(Collections.singletonList(sku));
        resolver.resolveProductionRemainingQty(context, sku);

        int targetQty = resolver.upsizeEndingTargetQty(context, sku);

        Assertions.assertEquals(80, targetQty);
        Assertions.assertEquals(80, resolver.resolveProductionRemainingQty(context, sku));
        Assertions.assertEquals(Integer.valueOf(80),
                context.getSkuProductionRemainingQtyMap().get("3302003002"));
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

    /**
     * 用例说明：命中胎胚库存账本的新增 SKU 写入未排时，必须退出胎胚有效集合并触发剩余 SKU 重新分摊。
     *
     * @throws Exception 反射调用异常
     */
    @Test
    public void shouldRefreshSharedEmbryoAllocationWhenEmbryoStockEndingSkuUnscheduled() throws Exception {
        LhScheduleContext context = new LhScheduleContext();
        setScheduleDate(context);
        context.getEmbryoEndingFlagMap().put("EMB-10", 1);
        context.getEmbryoRealtimeStockMap().put("EMB-10", 35);
        SkuScheduleDTO failedSku = buildEndingSku("3302002655", "EMB-10", 1, 35, 1);
        SkuScheduleDTO firstRemainingSku = buildEndingSku("3302002654", "EMB-10", 1, 35, 1);
        SkuScheduleDTO secondRemainingSku = buildEndingSku("3302002177", "EMB-10", 1, 35, 1);
        context.setNewSpecSkuList(new ArrayList<SkuScheduleDTO>(
                Arrays.asList(failedSku, firstRemainingSku, secondRemainingSku)));
        resolver.refreshAllSharedEmbryoStockAllocations(context, "测试初始化");
        Assertions.assertEquals(Integer.valueOf(11), context.getEmbryoStockSkuQuotaMap().get("3302002655"));
        Assertions.assertEquals(Integer.valueOf(13), context.getEmbryoStockSkuQuotaMap().get("3302002177"));
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        injectTargetScheduleQtyResolver(strategy);

        invokeAddUnscheduledResult(strategy, context, failedSku, 11, "首检班次分配失败");

        Assertions.assertEquals(Arrays.asList("3302002654", "3302002177"),
                context.getActiveEmbryoSkuMap().get("EMB-10"));
        Assertions.assertFalse(context.getEmbryoStockSkuQuotaMap().containsKey("3302002655"));
        Assertions.assertFalse(context.getEmbryoStockHardTargetMaterialSet().contains("3302002655"));
        Assertions.assertEquals(Integer.valueOf(17), context.getEmbryoStockSkuQuotaMap().get("3302002654"));
        Assertions.assertEquals(Integer.valueOf(18), context.getEmbryoStockSkuQuotaMap().get("3302002177"));
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

    private void invokeAddUnscheduledResult(NewSpecProductionStrategy strategy,
                                            LhScheduleContext context,
                                            SkuScheduleDTO sku,
                                            int unscheduledQty,
                                            String reason) throws Exception {
        Method method = NewSpecProductionStrategy.class.getDeclaredMethod(
                "addUnscheduledResult", LhScheduleContext.class, SkuScheduleDTO.class, int.class, String.class);
        method.setAccessible(true);
        method.invoke(strategy, context, sku, unscheduledQty, reason);
    }

    private void injectTargetScheduleQtyResolver(NewSpecProductionStrategy strategy) throws Exception {
        java.lang.reflect.Field field = NewSpecProductionStrategy.class.getDeclaredField("targetScheduleQtyResolver");
        field.setAccessible(true);
        field.set(strategy, resolver);
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

    private SkuDailyPlanQuotaDTO buildQuota(int dayPlanQty, int remainingQty) {
        SkuDailyPlanQuotaDTO quota = new SkuDailyPlanQuotaDTO();
        quota.setDayPlanQty(dayPlanQty);
        quota.setRemainingQty(remainingQty);
        return quota;
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

    private LhScheduleResult buildResult(String materialCode,
                                         String embryoCode,
                                         String machineCode,
                                         int planQty) {
        LhScheduleResult result = new LhScheduleResult();
        result.setMaterialCode(materialCode);
        result.setEmbryoCode(embryoCode);
        result.setLhMachineCode(machineCode);
        ShiftFieldUtil.setShiftPlanQty(result, 1, planQty, new Date(), null);
        ShiftFieldUtil.syncDailyPlanQty(result);
        return result;
    }

    private void setScheduleDate(LhScheduleContext context) {
        context.setScheduleDate(Date.from(LocalDate.of(2026, 7, 2)
                .atStartOfDay(ZoneId.systemDefault()).toInstant()));
    }
}
