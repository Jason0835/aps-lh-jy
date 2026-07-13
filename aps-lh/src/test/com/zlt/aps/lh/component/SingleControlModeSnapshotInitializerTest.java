package com.zlt.aps.lh.component;

import com.zlt.aps.lh.api.domain.dto.MachineScheduleDTO;
import com.zlt.aps.lh.api.domain.dto.SkuScheduleDTO;
import com.zlt.aps.lh.api.enums.ConstructionStageEnum;
import com.zlt.aps.lh.api.enums.SingleControlMachineModeEnum;
import com.zlt.aps.lh.api.enums.TrialStatusEnum;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.engine.strategy.IMachineMatchStrategy;
import com.zlt.aps.lh.util.LhSingleControlMachineUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * 单控机台模式冻结回归测试。
 */
class SingleControlModeSnapshotInitializerTest {

    /**
     * 两个不同试制SKU即使初始待排量都大于4，也必须全部冻结为单模。
     */
    @Test
    void initialize_shouldFreezeAllEligibleTrialSkusToSingleSideWhenCountReachesTwo() {
        LhScheduleContext context = new LhScheduleContext();
        SkuScheduleDTO trialA = sku("TRIAL-A", ConstructionStageEnum.TRIAL.getCode(), 20);
        SkuScheduleDTO trialB = sku("TRIAL-B", ConstructionStageEnum.TRIAL.getCode(), 10);
        context.getNewSpecSkuList().add(trialA);
        context.getNewSpecSkuList().add(trialB);
        putProductionLedger(context, trialA, trialB);

        initializer(true).initialize(context);

        Assertions.assertEquals(2, context.getSingleControlEligibleTrialSkuKeySet().size());
        Assertions.assertTrue(LhSingleControlMachineUtil.isSingleSideGranularitySku(context, trialA));
        Assertions.assertTrue(LhSingleControlMachineUtil.isSingleSideGranularitySku(context, trialB));
    }

    /**
     * 单控规则只按施工阶段识别试制SKU，产品状态X不得独立计入试制SKU数量。
     */
    @Test
    void initialize_shouldUseConstructionStageAsOnlyTrialProductionType() {
        LhScheduleContext context = new LhScheduleContext();
        SkuScheduleDTO constructionTrial = sku(
                "CONSTRUCTION-TRIAL", ConstructionStageEnum.TRIAL.getCode(), 5);
        SkuScheduleDTO productStatusOnlyTrial = sku(
                "PRODUCT-STATUS-X", ConstructionStageEnum.FORMAL.getCode(), 20);
        productStatusOnlyTrial.setProductStatus(TrialStatusEnum.TRIAL.getCode());
        context.getNewSpecSkuList().add(constructionTrial);
        context.getNewSpecSkuList().add(productStatusOnlyTrial);
        putProductionLedger(context, constructionTrial, productStatusOnlyTrial);

        initializer(true).initialize(context);

        Assertions.assertEquals(1, context.getSingleControlEligibleTrialSkuKeySet().size());
        Assertions.assertTrue(LhSingleControlMachineUtil.isWholeMachineGranularitySku(context, constructionTrial));
        Assertions.assertTrue(LhSingleControlMachineUtil.isWholeMachineGranularitySku(context, productStatusOnlyTrial));
    }

    /**
     * 初始待排量为0的试制SKU不计数；快照生成后修改数量和待排队列也不得改变模式。
     */
    @Test
    void initialize_shouldExcludeZeroTargetAndKeepModeFrozenAfterQuantityChanges() {
        LhScheduleContext context = new LhScheduleContext();
        SkuScheduleDTO trialA = sku("TRIAL-A", ConstructionStageEnum.TRIAL.getCode(), 20);
        SkuScheduleDTO trialB = sku("TRIAL-B", ConstructionStageEnum.TRIAL.getCode(), 20);
        SkuScheduleDTO zeroTrial = sku("TRIAL-ZERO", ConstructionStageEnum.TRIAL.getCode(), 0);
        context.getNewSpecSkuList().add(trialA);
        context.getNewSpecSkuList().add(trialB);
        context.getNewSpecSkuList().add(zeroTrial);
        putProductionLedger(context, trialA, trialB, zeroTrial);
        SingleControlModeSnapshotInitializer initializer = initializer(true);

        initializer.initialize(context);
        trialA.setTargetScheduleQty(1);
        context.getNewSpecSkuList().remove(trialB);
        initializer.initialize(context);

        Assertions.assertEquals(2, context.getSingleControlEligibleTrialSkuKeySet().size());
        Assertions.assertEquals(20, context.getSingleControlInitialTargetQtyMap().get(
                LhSingleControlMachineUtil.buildSkuModeKey(trialA)));
        Assertions.assertEquals(SingleControlMachineModeEnum.SINGLE_SIDE,
                context.getSingleControlModeSnapshotMap().get(
                        LhSingleControlMachineUtil.buildSkuModeKey(trialA)));
    }

    /**
     * 同一项目统一SKU键出现多次时只统计一次，不能因续作和新增对象重复而误触发多试制规则。
     */
    @Test
    void initialize_shouldCountDuplicateTrialSkuKeyOnlyOnce() {
        LhScheduleContext context = new LhScheduleContext();
        SkuScheduleDTO continuousTrial = sku("TRIAL-SAME", ConstructionStageEnum.TRIAL.getCode(), 10);
        SkuScheduleDTO newTrial = sku("TRIAL-SAME", ConstructionStageEnum.TRIAL.getCode(), 10);
        context.getContinuousSkuList().add(continuousTrial);
        context.getNewSpecSkuList().add(newTrial);
        putProductionLedger(context, continuousTrial);

        initializer(true).initialize(context);

        Assertions.assertEquals(1, context.getSingleControlEligibleTrialSkuKeySet().size());
        Assertions.assertTrue(LhSingleControlMachineUtil.isWholeMachineGranularitySku(context, continuousTrial));
        Assertions.assertTrue(LhSingleControlMachineUtil.isWholeMachineGranularitySku(context, newTrial));
    }

    /**
     * 未通过单控静态准入的试制SKU不计入本轮有效试制SKU数量。
     */
    @Test
    void initialize_shouldExcludeTrialSkusWithoutStaticSingleControlEligibility() {
        LhScheduleContext context = new LhScheduleContext();
        SkuScheduleDTO trialA = sku("TRIAL-A", ConstructionStageEnum.TRIAL.getCode(), 20);
        SkuScheduleDTO trialB = sku("TRIAL-B", ConstructionStageEnum.TRIAL.getCode(), 20);
        context.getNewSpecSkuList().add(trialA);
        context.getNewSpecSkuList().add(trialB);
        putProductionLedger(context, trialA, trialB);

        initializer(false).initialize(context);

        Assertions.assertTrue(context.getSingleControlEligibleTrialSkuKeySet().isEmpty());
        Assertions.assertTrue(LhSingleControlMachineUtil.isWholeMachineGranularitySku(context, trialA));
        Assertions.assertTrue(LhSingleControlMachineUtil.isWholeMachineGranularitySku(context, trialB));
    }

    /**
     * 满排理论目标量不得覆盖进入排产链路时已冻结的实际消费账本。
     */
    @Test
    void initialize_shouldUseProductionLedgerInsteadOfTheoreticalFullProductionTarget() {
        LhScheduleContext context = new LhScheduleContext();
        SkuScheduleDTO singleSideSku = sku("LEDGER-THREE", ConstructionStageEnum.FORMAL.getCode(), 160);
        SkuScheduleDTO wholePairSku = sku("LEDGER-FIVE", ConstructionStageEnum.FORMAL.getCode(), 160);
        context.getNewSpecSkuList().add(singleSideSku);
        context.getNewSpecSkuList().add(wholePairSku);
        context.getSkuProductionRemainingQtyMap().put(singleSideSku.getMaterialCode(), 3);
        context.getSkuProductionRemainingQtyMap().put(wholePairSku.getMaterialCode(), 5);

        initializer(true).initialize(context);

        Assertions.assertEquals(3, context.getSingleControlInitialTargetQtyMap().get(
                LhSingleControlMachineUtil.buildSkuModeKey(singleSideSku)));
        Assertions.assertEquals(5, context.getSingleControlInitialTargetQtyMap().get(
                LhSingleControlMachineUtil.buildSkuModeKey(wholePairSku)));
        Assertions.assertTrue(LhSingleControlMachineUtil.isSingleSideGranularitySku(context, singleSideSku));
        Assertions.assertTrue(LhSingleControlMachineUtil.isWholeMachineGranularitySku(context, wholePairSku));
    }

    /**
     * 试制SKU的strictTargetQty=true时，模式冻结仍必须使用surplusQty而非理论窗口产能。
     * <p>满排模式下targetScheduleQty=160代表窗口理论产能，但试制SKU实际余量只有3条，
     * 模式应按3<=4冻结为单模，不能被160>4误判为双模。</p>
     */
    @Test
    void initialize_shouldUseSurplusQtyForTrialSkuEvenWhenStrictTargetQtyIsTrue() {
        LhScheduleContext context = new LhScheduleContext();
        SkuScheduleDTO trialSku = sku("TRIAL-STRICT", ConstructionStageEnum.TRIAL.getCode(), 160);
        trialSku.setStrictTargetQty(true);
        trialSku.setSurplusQty(3);
        context.getNewSpecSkuList().add(trialSku);
        // 实际消费账本被TargetScheduleQtyResolver初始化为理论窗口产能160
        context.getSkuProductionRemainingQtyMap().put(trialSku.getMaterialCode(), 160);

        initializer(true).initialize(context);

        // 模式冻结必须使用surplusQty=3，而不是targetScheduleQty=160
        Assertions.assertEquals(3, context.getSingleControlInitialTargetQtyMap().get(
                LhSingleControlMachineUtil.buildSkuModeKey(trialSku)));
        Assertions.assertTrue(LhSingleControlMachineUtil.isSingleSideGranularitySku(context, trialSku));
    }

    /**
     * 快照缺失时不得抛出异常，应返回空模式并让单边、整机判断均不命中。
     */
    @Test
    void resolveFrozenMode_shouldReturnNullWhenSnapshotMissing() {
        LhScheduleContext context = new LhScheduleContext();
        SkuScheduleDTO sku = sku("SNAPSHOT-MISSING", ConstructionStageEnum.FORMAL.getCode(), 10);

        Assertions.assertNull(LhSingleControlMachineUtil.resolveFrozenMode(context, sku));
        Assertions.assertNull(LhSingleControlMachineUtil.resolveFrozenMode(null, sku));
        Assertions.assertNull(LhSingleControlMachineUtil.resolveFrozenMode(context, null));
        Assertions.assertFalse(LhSingleControlMachineUtil.isSingleSideGranularitySku(context, sku));
        Assertions.assertFalse(LhSingleControlMachineUtil.isWholeMachineGranularitySku(context, sku));
    }

    /**
     * 构建初始化器，并用只读机台匹配替身控制静态单控准入结果。
     *
     * @param eligible 是否存在静态可用单控侧
     * @return 初始化器
     */
    private SingleControlModeSnapshotInitializer initializer(final boolean eligible) {
        SingleControlModeSnapshotInitializer initializer = new SingleControlModeSnapshotInitializer();
        IMachineMatchStrategy machineMatchStrategy = new IMachineMatchStrategy() {
            @Override
            public List<MachineScheduleDTO> matchMachines(LhScheduleContext context, SkuScheduleDTO sku) {
                return Collections.emptyList();
            }

            @Override
            public boolean hasEligibleSingleControlSide(LhScheduleContext context, SkuScheduleDTO sku) {
                return eligible;
            }

            @Override
            public MachineScheduleDTO selectBestMachine(LhScheduleContext context,
                                                        SkuScheduleDTO sku,
                                                        List<MachineScheduleDTO> candidates,
                                                        Set<String> excludedMachineCodes) {
                return null;
            }

            @Override
            public void traceEnabledMachineSort(LhScheduleContext context) {
                // 冻结测试不涉及排序日志。
            }
        };
        ReflectionTestUtils.setField(initializer, "machineMatchStrategy", machineMatchStrategy);
        return initializer;
    }

    /**
     * 构建测试SKU。
     *
     * @param materialCode 物料编码
     * @param constructionStage 施工阶段
     * @param targetQty 初始目标量
     * @return SKU排程DTO
     */
    private SkuScheduleDTO sku(String materialCode, String constructionStage, int targetQty) {
        SkuScheduleDTO sku = new SkuScheduleDTO();
        sku.setMaterialCode(materialCode);
        // 产品状态继续作为统一SKU键的一部分，但不参与试制类型判断。
        sku.setProductStatus(TrialStatusEnum.FORMAL.getCode());
        sku.setConstructionStage(constructionStage);
        sku.setTargetScheduleQty(targetQty);
        sku.setPendingQty(targetQty);
        return sku;
    }

    /**
     * 按SKU目标量构造进入排产链路时的实际消费账本。
     *
     * @param context 排程上下文
     * @param skus 待写入账本的SKU
     */
    private void putProductionLedger(LhScheduleContext context, SkuScheduleDTO... skus) {
        for (SkuScheduleDTO sku : skus) {
            context.getSkuProductionRemainingQtyMap().put(
                    sku.getMaterialCode(), sku.resolveTargetScheduleQty());
        }
    }
}
