package com.zlt.aps.lh.component;

import com.zlt.aps.lh.api.domain.dto.CuringMonthPlanTotalResult;
import com.zlt.aps.lh.api.domain.dto.SkuScheduleDTO;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.engine.strategy.support.EarlyProductionRuntimePlan;
import com.zlt.aps.mp.api.domain.entity.FactoryMonthPlanProductionFinalResult;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SKU 提前生产数量口径计算测试。
 */
class EarlyProductionQuantityCalculatorTest {

    /**
     * 提前生产观察范围跨月时，应读取 futurePlanDate 真实所属月份计划段。
     */
    @Test
    void calculateMonthPlanTotal_shouldUseFutureMonthSegment() {
        LhScheduleContext context = new LhScheduleContext();
        FactoryMonthPlanProductionFinalResult julyPlan =
                buildPlan("3302000004", "S", 2026, 7);
        FactoryMonthPlanProductionFinalResult augustPlan =
                buildPlan("3302000004", "S", 2026, 8);
        augustPlan.setDay1(46);
        augustPlan.setDay2(50);
        attachMonthPlans(context, julyPlan, augustPlan);

        CuringMonthPlanTotalResult result =
                EarlyProductionQuantityCalculator.calculateMonthPlanTotal(
                        context, julyPlan, LocalDate.of(2026, 7, 30),
                        LocalDate.of(2026, 7, 31), 0, 0);

        assertTrue(result.isCrossMonth());
        assertEquals(LocalDate.of(2026, 8, 2), result.getBreakPointDate());
        assertEquals(96, result.getCrossMonthPlanTotal());
        assertEquals(96, result.getMonthPlanTotal());
    }

    /**
     * 提前生产观察范围跨年时，应按真实年份读取下一年一月 dayN。
     */
    @Test
    void calculateMonthPlanTotal_shouldUseNextYearPlanSegment() {
        LhScheduleContext context = new LhScheduleContext();
        FactoryMonthPlanProductionFinalResult decemberPlan =
                buildPlan("3302000005", "S", 2026, 12);
        FactoryMonthPlanProductionFinalResult januaryPlan =
                buildPlan("3302000005", "S", 2027, 1);
        januaryPlan.setDay1(32);
        januaryPlan.setDay2(18);
        attachMonthPlans(context, decemberPlan, januaryPlan);

        CuringMonthPlanTotalResult result =
                EarlyProductionQuantityCalculator.calculateMonthPlanTotal(
                        context, decemberPlan, LocalDate.of(2026, 12, 31),
                        LocalDate.of(2026, 12, 31), 0, 0);

        assertTrue(result.isCrossMonth());
        assertEquals(LocalDate.of(2027, 1, 2), result.getBreakPointDate());
        assertEquals(50, result.getCrossMonthPlanTotal());
        assertEquals(50, result.getMonthPlanTotal());
    }

    /**
     * 动态历史欠产必须按业务日和真实月份分别累计，单日超产不得抵扣其他日期欠产。
     */
    @Test
    void initializeMonthlyHistoryShortage_shouldSeparateBusinessDateAndMonth() {
        LhScheduleContext context = new LhScheduleContext();
        context.setScheduleDate(toDate(LocalDate.of(2026, 7, 30)));
        context.setWindowEndDate(toDate(LocalDate.of(2026, 8, 1)));
        FactoryMonthPlanProductionFinalResult julyPlan =
                buildPlan("3302000006", "S", 2026, 7);
        julyPlan.setDay29(100);
        julyPlan.setDay30(50);
        FactoryMonthPlanProductionFinalResult augustPlan =
                buildPlan("3302000006", "S", 2026, 8);
        context.setLoadedMonthPlanList(Arrays.asList(julyPlan, augustPlan));
        context.setMaterialMonthDailyFinishedQtyMap(new HashMap<String, Integer>(4));
        context.getMaterialMonthDailyFinishedQtyMap().put(
                "3302000006_S_2026-07-29", 40);
        context.getMaterialMonthDailyFinishedQtyMap().put(
                "3302000006_S_2026-07-30", 60);

        EarlyProductionQuantityCalculator
                .initializeMonthlyHistoryShortageByBusinessDate(context);

        assertEquals(60, context.getMonthlyHistoryShortageQtyMap()
                .get(LocalDate.of(2026, 7, 30)).get("3302000006_S"));
        assertEquals(60, context.getMonthlyHistoryShortageQtyMap()
                .get(LocalDate.of(2026, 7, 31)).get("3302000006_S"));
        assertTrue(context.getMonthlyHistoryShortageQtyMap()
                .get(LocalDate.of(2026, 8, 1)).isEmpty());
    }

    /**
     * 目标月份无正日计划、基础计划来自未来计划月时，应保留 futurePlanDate 所属月计划。
     */
    @Test
    void resolveTargetMonthPlan_shouldKeepFuturePlanMonth() {
        LhScheduleContext context = new LhScheduleContext();
        context.setScheduleDate(toDate(LocalDate.of(2026, 7, 30)));
        context.setWindowEndDate(toDate(LocalDate.of(2026, 7, 31)));
        FactoryMonthPlanProductionFinalResult julyPlan =
                buildPlan("3302000007", "S", 2026, 7);
        FactoryMonthPlanProductionFinalResult augustPlan =
                buildPlan("3302000007", "S", 2026, 8);
        augustPlan.setDay1(46);
        attachMonthPlans(context, julyPlan, augustPlan);

        FactoryMonthPlanProductionFinalResult result =
                EarlyProductionQuantityCalculator.resolveTargetMonthPlan(
                        context, augustPlan, LocalDate.of(2026, 7, 30));

        assertSame(augustPlan, result);
    }

    /**
     * 指定六个结构切换 SKU 无论当前月存在 TOTAL_QTY=0 记录还是当前月记录缺失，
     * 都必须按同一口径进入候选态，且候选态不得提前取得排产资格。
     */
    @Test
    void registerFutureOnlyCandidateView_shouldHandleSixSkusWithSameRoute() {
        LocalDate scheduleStartDate = LocalDate.of(2026, 7, 29);
        LhScheduleContext context = new LhScheduleContext();
        context.setScheduleDate(toDate(scheduleStartDate));
        context.setWindowEndDate(toDate(LocalDate.of(2026, 7, 31)));
        String[] materialCodes = {
                "3302001080", "3302001081", "3302001147",
                "3302001525", "3302001523", "3302001073"
        };
        List<FactoryMonthPlanProductionFinalResult> planList =
                new ArrayList<FactoryMonthPlanProductionFinalResult>(9);
        // 三条当前月存在记录但 TOTAL_QTY=0，另外三条不建当前月记录，验证两种数据形态同口径。
        for (String materialCode : Arrays.asList(
                "3302001081", "3302001523", "3302001073")) {
            FactoryMonthPlanProductionFinalResult julyPlan =
                    buildPlan(materialCode, "S", 2026, 7);
            julyPlan.setTotalQty(0);
            planList.add(julyPlan);
        }
        for (String materialCode : materialCodes) {
            FactoryMonthPlanProductionFinalResult augustPlan =
                    buildPlan(materialCode, "S", 2026, 8);
            augustPlan.setTotalQty(200);
            augustPlan.setDay1(50);
            augustPlan.setDay2(50);
            planList.add(augustPlan);
        }
        context.setMonthPlanList(planList);
        context.setLoadedMonthPlanList(planList);
        context.setMonthPlanByMaterialMonthMap(
                MonthPlanDateResolver.buildMaterialMonthPlanMap(planList));

        for (String materialCode : materialCodes) {
            SkuScheduleDTO sku = buildFormalSku(materialCode);
            EarlyProductionRuntimePlan runtimePlan =
                    EarlyProductionQuantityCalculator.registerFutureOnlyCandidateView(
                            context, sku, scheduleStartDate);

            assertNotNull(runtimePlan, materialCode + " 应进入提前生产候选视图");
            assertTrue(runtimePlan.isFutureOnlyCandidate());
            assertFalse(runtimePlan.isActive(), "候选注册时不得直接取得排产资格");
            assertEquals(0, runtimePlan.getCurrentMonthTotalQty());
            assertEquals(LocalDate.of(2026, 8, 1), runtimePlan.getFuturePlanDate());
            assertEquals(100, runtimePlan.getFutureMonthPlanTotalQty());
            assertEquals(100, runtimePlan.getFutureMonthSurplusQty());
            assertSame(runtimePlan, context.getEarlyProductionRuntimePlan(sku));
        }
    }

    /**
     * 当前业务月 TOTAL_QTY 大于0时必须沿用正常排产路由，不得注册 future-only 候选。
     */
    @Test
    void registerFutureOnlyCandidateView_shouldRejectPositiveCurrentMonthTotalQty() {
        LocalDate scheduleStartDate = LocalDate.of(2026, 7, 29);
        LhScheduleContext context = new LhScheduleContext();
        context.setScheduleDate(toDate(scheduleStartDate));
        context.setWindowEndDate(toDate(LocalDate.of(2026, 7, 31)));
        FactoryMonthPlanProductionFinalResult julyPlan =
                buildPlan("3302001999", "S", 2026, 7);
        julyPlan.setTotalQty(1);
        FactoryMonthPlanProductionFinalResult augustPlan =
                buildPlan("3302001999", "S", 2026, 8);
        augustPlan.setTotalQty(100);
        augustPlan.setDay1(50);
        attachMonthPlans(context, julyPlan, augustPlan);
        SkuScheduleDTO sku = buildFormalSku("3302001999");

        EarlyProductionRuntimePlan runtimePlan =
                EarlyProductionQuantityCalculator.registerFutureOnlyCandidateView(
                        context, sku, scheduleStartDate);

        assertNull(runtimePlan);
        assertFalse(context.isFutureOnlyEarlyProductionCandidate(sku));
    }

    /**
     * 当前月 TOTAL_QTY=0 时只清除正常排产目标，通用余量必须保持原值并由候选视图
     * 单独保存未来月余量。
     */
    @Test
    void applyCurrentMonthTotalRoute_shouldNotModifyGenericSurplusQty() {
        LocalDate scheduleStartDate = LocalDate.of(2026, 7, 29);
        LhScheduleContext context = new LhScheduleContext();
        context.setScheduleDate(toDate(scheduleStartDate));
        context.setWindowEndDate(toDate(LocalDate.of(2026, 7, 31)));
        FactoryMonthPlanProductionFinalResult julyPlan =
                buildPlan("3302001080", "S", 2026, 7);
        julyPlan.setTotalQty(0);
        FactoryMonthPlanProductionFinalResult augustPlan =
                buildPlan("3302001080", "S", 2026, 8);
        augustPlan.setTotalQty(128);
        augustPlan.setDay1(48);
        attachMonthPlans(context, julyPlan, augustPlan);
        SkuScheduleDTO sku = buildFormalSku("3302001080");
        sku.setSurplusQty(999);
        sku.setPendingQty(999);
        sku.setTargetScheduleQty(999);

        boolean normalProductionBlocked =
                EarlyProductionQuantityCalculator.applyCurrentMonthTotalRoute(
                        context, sku, scheduleStartDate, new TargetScheduleQtyResolver());

        assertTrue(normalProductionBlocked);
        assertEquals(999, sku.getSurplusQty(), "不得改动通用正常余量");
        assertEquals(0, sku.resolveTargetScheduleQty());
        assertTrue(context.isFutureOnlyEarlyProductionCandidate(sku));
        assertEquals(48, context.getEarlyProductionRuntimePlan(sku).getFutureMonthSurplusQty());
    }

    /**
     * 当前月 TOTAL_QTY=0 的提前生产视图激活后，收尾小余量规则必须读取实际消费账本，
     * 且部分排产后的实时剩余量不能退回运行视图初始化目标或通用余量。
     */
    @Test
    void resolveSmallEndingRuleQty_shouldUseActiveFutureOnlyRuntimeRemainingQty() {
        LhScheduleContext context = new LhScheduleContext();
        SkuScheduleDTO sku = buildFormalSku("3302002746");
        sku.setSurplusQty(0);
        sku.setTargetScheduleQty(102);
        TargetScheduleQtyResolver targetScheduleQtyResolver =
                new TargetScheduleQtyResolver();
        targetScheduleQtyResolver.syncProductionRemainingQtyToTarget(
                context, sku, 102, "提前生产测试初始化");

        EarlyProductionRuntimePlan runtimePlan = new EarlyProductionRuntimePlan();
        runtimePlan.setFutureOnlyCandidate(true);
        runtimePlan.setActive(true);
        runtimePlan.setEffectiveTargetQty(102);
        context.registerEarlyProductionRuntimePlan(sku, runtimePlan);
        // 模拟提前生产已经落地28条，规则必须读取扣减后的74条，而不是初始目标102或通用余量0。
        assertEquals(28, targetScheduleQtyResolver.deductProductionRemainingQty(
                context, sku, 28, "提前生产测试扣减", "K-TEST"));

        int ruleQty = EarlyProductionQuantityCalculator.resolveSmallEndingRuleQty(
                context, sku, targetScheduleQtyResolver);

        assertEquals(74, ruleQty);
        assertEquals(0, sku.getSurplusQty(), "提前生产规则计算不得改写通用余量");
        assertEquals(102, runtimePlan.getEffectiveTargetQty(), "运行视图初始化目标只作审计快照");
    }

    /**
     * 尚未激活的 future-only 候选不具备提前生产资格，收尾规则仍应使用通用余量，
     * 不得提前读取已经初始化但尚未授权消费的目标量账本。
     */
    @Test
    void resolveSmallEndingRuleQty_shouldKeepGenericSurplusForInactiveCandidate() {
        LhScheduleContext context = new LhScheduleContext();
        SkuScheduleDTO sku = buildFormalSku("3302002279");
        sku.setSurplusQty(2);
        TargetScheduleQtyResolver targetScheduleQtyResolver =
                new TargetScheduleQtyResolver();
        targetScheduleQtyResolver.syncProductionRemainingQtyToTarget(
                context, sku, 74, "候选态测试初始化");

        EarlyProductionRuntimePlan runtimePlan = new EarlyProductionRuntimePlan();
        runtimePlan.setFutureOnlyCandidate(true);
        runtimePlan.setActive(false);
        runtimePlan.setEffectiveTargetQty(74);
        context.registerEarlyProductionRuntimePlan(sku, runtimePlan);

        int ruleQty = EarlyProductionQuantityCalculator.resolveSmallEndingRuleQty(
                context, sku, targetScheduleQtyResolver);

        assertEquals(2, ruleQty);
        assertFalse(EarlyProductionQuantityCalculator
                .shouldUseRuntimeRemainingQtyForSmallEnding(context, sku));
    }

    /**
     * 激活态 future-only SKU 的普通收尾目标必须保持中心运行视图总目标，不能被
     * 通用余量0或局部胎胚库存覆盖；同时普通收尾处理不得重置已经扣减的实际消费账本。
     */
    @Test
    void ordinaryEnding_shouldPreserveActiveFutureOnlyRuntimeTargetAndRemainingLedger() {
        LhScheduleContext context = new LhScheduleContext();
        SkuScheduleDTO sku = buildFormalSku("3302002746");
        sku.setSurplusQty(0);
        sku.setEmbryoStock(29);
        sku.setMouldQty(2);
        sku.setTargetScheduleQty(102);
        TargetScheduleQtyResolver targetScheduleQtyResolver =
                new TargetScheduleQtyResolver();
        targetScheduleQtyResolver.syncProductionRemainingQtyToTarget(
                context, sku, 102, "提前生产收尾目标测试初始化");

        EarlyProductionRuntimePlan runtimePlan = new EarlyProductionRuntimePlan();
        runtimePlan.setFutureOnlyCandidate(true);
        runtimePlan.setActive(true);
        runtimePlan.setCurrentDate(LocalDate.of(2026, 7, 30));
        runtimePlan.setFuturePlanDate(LocalDate.of(2026, 8, 1));
        runtimePlan.setEffectiveTargetQty(102);
        context.registerEarlyProductionRuntimePlan(sku, runtimePlan);
        // 模拟已排28条，中心总目标仍为102，实际消费账本只剩74条。
        assertEquals(28, targetScheduleQtyResolver.deductProductionRemainingQty(
                context, sku, 28, "提前生产收尾目标测试扣减", "K1603"));

        Integer runtimeEndingTarget =
                EarlyProductionQuantityCalculator.resolveActiveFutureOnlyEndingTargetQty(
                        context, sku);
        int finalEndingTarget =
                targetScheduleQtyResolver.resolveFinalEndingTargetQty(context, sku);
        int preservedTarget =
                targetScheduleQtyResolver.upsizeEndingTargetQty(context, sku);

        assertEquals(102, runtimeEndingTarget);
        assertEquals(102, finalEndingTarget, "排前和排后收尾判断必须使用中心总目标");
        assertEquals(102, preservedTarget, "普通收尾不得把中心目标覆盖为胎胚库存29");
        assertEquals(102, sku.resolveTargetScheduleQty());
        assertEquals(74, sku.getRemainingScheduleQty());
        assertEquals(74, targetScheduleQtyResolver.resolveProductionRemainingQty(context, sku),
                "普通收尾处理不得把已扣减账本重置为102");
        assertFalse(targetScheduleQtyResolver.isSharedEmbryoZeroSurplusEnding(context, sku),
                "中心运行视图不得因通用余量0命中共用胎胚零余量未排");
        assertEquals(0, sku.getSurplusQty(), "不得修改通用正常余量");
    }

    /**
     * 将多月计划同时挂载到日期解析索引。
     *
     * @param context 排程上下文
     * @param plans 多月月计划
     */
    private static void attachMonthPlans(
            LhScheduleContext context,
            FactoryMonthPlanProductionFinalResult... plans) {
        context.setMonthPlanList(Arrays.asList(plans));
        context.setLoadedMonthPlanList(Arrays.asList(plans));
        context.setMonthPlanByMaterialMonthMap(
                MonthPlanDateResolver.buildMaterialMonthPlanMap(Arrays.asList(plans)));
    }

    /**
     * 构造测试月计划。
     *
     * @param materialCode 物料编码
     * @param productStatus 产品状态
     * @param year 年份
     * @param month 月份
     * @return 月计划
     */
    private static FactoryMonthPlanProductionFinalResult buildPlan(
            String materialCode,
            String productStatus,
            int year,
            int month) {
        FactoryMonthPlanProductionFinalResult plan =
                new FactoryMonthPlanProductionFinalResult();
        plan.setFactoryCode("116");
        plan.setMaterialCode(materialCode);
        plan.setProductStatus(productStatus);
        plan.setYear(year);
        plan.setMonth(month);
        plan.setProductionVersion("PV" + year + month);
        return plan;
    }

    /**
     * 构造正规新增 SKU。
     *
     * @param materialCode 物料编码
     * @return 正规新增 SKU
     */
    private static SkuScheduleDTO buildFormalSku(String materialCode) {
        SkuScheduleDTO sku = new SkuScheduleDTO();
        sku.setMaterialCode(materialCode);
        sku.setProductStatus("S");
        sku.setConstructionStage("03");
        sku.setScheduleType("02");
        return sku;
    }

    /**
     * LocalDate 转 Date。
     *
     * @param date 日期
     * @return Date
     */
    private static Date toDate(LocalDate date) {
        return Date.from(date.atStartOfDay(ZoneId.systemDefault()).toInstant());
    }
}
