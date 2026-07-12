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
     * 三个不同试验SKU即使初始目标量都大于4，也必须全部冻结为单模。
     */
    @Test
    void initialize_shouldFreezeAllEligibleTrialSkusToSingleSideWhenCountReachesThree() {
        LhScheduleContext context = new LhScheduleContext();
        SkuScheduleDTO trialA = sku("TRIAL-A", TrialStatusEnum.TRIAL.getCode(), 20);
        SkuScheduleDTO trialB = sku("TRIAL-B", TrialStatusEnum.TRIAL.getCode(), 10);
        SkuScheduleDTO trialC = sku("TRIAL-C", TrialStatusEnum.TRIAL.getCode(), 6);
        context.getNewSpecSkuList().add(trialA);
        context.getNewSpecSkuList().add(trialB);
        context.getNewSpecSkuList().add(trialC);

        initializer(true).initialize(context);

        Assertions.assertEquals(3, context.getSingleControlEligibleTrialSkuKeySet().size());
        Assertions.assertTrue(LhSingleControlMachineUtil.isSingleSideGranularitySku(context, trialA));
        Assertions.assertTrue(LhSingleControlMachineUtil.isSingleSideGranularitySku(context, trialB));
        Assertions.assertTrue(LhSingleControlMachineUtil.isSingleSideGranularitySku(context, trialC));
    }

    /**
     * 不足三个试验SKU时统一按4条边界判断，施工阶段“试制”不得冒充产品状态“试验”。
     */
    @Test
    void initialize_shouldUseInitialTargetBoundaryAndOnlyCountProductStatusTrial() {
        LhScheduleContext context = new LhScheduleContext();
        SkuScheduleDTO trialA = sku("TRIAL-A", TrialStatusEnum.TRIAL.getCode(), 10);
        SkuScheduleDTO trialB = sku("TRIAL-B", TrialStatusEnum.TRIAL.getCode(), 5);
        SkuScheduleDTO constructionTrial = sku("CONSTRUCTION-TRIAL", TrialStatusEnum.FORMAL.getCode(), 3);
        constructionTrial.setConstructionStage(ConstructionStageEnum.TRIAL.getCode());
        context.getNewSpecSkuList().add(trialA);
        context.getNewSpecSkuList().add(trialB);
        context.getNewSpecSkuList().add(constructionTrial);

        initializer(true).initialize(context);

        Assertions.assertEquals(2, context.getSingleControlEligibleTrialSkuKeySet().size());
        Assertions.assertTrue(LhSingleControlMachineUtil.isWholeMachineGranularitySku(context, trialA));
        Assertions.assertTrue(LhSingleControlMachineUtil.isWholeMachineGranularitySku(context, trialB));
        Assertions.assertTrue(LhSingleControlMachineUtil.isSingleSideGranularitySku(context, constructionTrial));
    }

    /**
     * 目标量为0的试验SKU不计数；快照生成后修改目标量并再次调用初始化也不得改变模式。
     */
    @Test
    void initialize_shouldExcludeZeroTargetAndKeepModeFrozenAfterQuantityChanges() {
        LhScheduleContext context = new LhScheduleContext();
        SkuScheduleDTO trialA = sku("TRIAL-A", TrialStatusEnum.TRIAL.getCode(), 20);
        SkuScheduleDTO trialB = sku("TRIAL-B", TrialStatusEnum.TRIAL.getCode(), 20);
        SkuScheduleDTO trialC = sku("TRIAL-C", TrialStatusEnum.TRIAL.getCode(), 20);
        SkuScheduleDTO zeroTrial = sku("TRIAL-ZERO", TrialStatusEnum.TRIAL.getCode(), 0);
        context.getNewSpecSkuList().add(trialA);
        context.getNewSpecSkuList().add(trialB);
        context.getNewSpecSkuList().add(trialC);
        context.getNewSpecSkuList().add(zeroTrial);
        SingleControlModeSnapshotInitializer initializer = initializer(true);

        initializer.initialize(context);
        trialA.setTargetScheduleQty(1);
        context.getNewSpecSkuList().remove(trialC);
        initializer.initialize(context);

        Assertions.assertEquals(3, context.getSingleControlEligibleTrialSkuKeySet().size());
        Assertions.assertEquals(20, context.getSingleControlInitialTargetQtyMap().get(
                LhSingleControlMachineUtil.buildSkuModeKey(trialA)));
        Assertions.assertEquals(SingleControlMachineModeEnum.SINGLE_SIDE,
                context.getSingleControlModeSnapshotMap().get(
                        LhSingleControlMachineUtil.buildSkuModeKey(trialA)));
    }

    /**
     * 快照缺失时不得抛出异常，应返回空模式并让单边、整机判断均不命中。
     */
    @Test
    void resolveFrozenMode_shouldReturnNullWhenSnapshotMissing() {
        LhScheduleContext context = new LhScheduleContext();
        SkuScheduleDTO sku = sku("SNAPSHOT-MISSING", TrialStatusEnum.FORMAL.getCode(), 10);

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
     * @param productStatus 产品状态
     * @param targetQty 初始目标量
     * @return SKU排程DTO
     */
    private SkuScheduleDTO sku(String materialCode, String productStatus, int targetQty) {
        SkuScheduleDTO sku = new SkuScheduleDTO();
        sku.setMaterialCode(materialCode);
        sku.setProductStatus(productStatus);
        sku.setConstructionStage(ConstructionStageEnum.FORMAL.getCode());
        sku.setTargetScheduleQty(targetQty);
        sku.setPendingQty(targetQty);
        return sku;
    }
}
