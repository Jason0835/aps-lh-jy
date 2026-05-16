package com.zlt.aps.lh.engine.strategy.support;

import com.zlt.aps.lh.api.domain.dto.SkuScheduleDTO;
import com.zlt.aps.lh.api.enums.ConstructionStageEnum;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * 排产数量策略测试。
 *
 * @author APS
 */
public class ProductionQuantityPolicyTest {

    @Test
    public void shouldAllowFormalNonEndingSkuFillStartedShift() {
        SkuScheduleDTO sku = new SkuScheduleDTO();
        sku.setConstructionStage(ConstructionStageEnum.FORMAL.getCode());

        ProductionQuantityPolicy policy = ProductionQuantityPolicy.from(sku, false);

        Assertions.assertTrue(policy.isAllowFillStartedShift());
        Assertions.assertFalse(policy.isStrictUpperLimit());
        Assertions.assertTrue(policy.isFullRunForNonTailMachine());
    }

    @Test
    public void shouldTreatMassTrialNonEndingSkuAsFormalQuantityPolicy() {
        SkuScheduleDTO sku = new SkuScheduleDTO();
        sku.setTrial(true);
        sku.setConstructionStage(ConstructionStageEnum.MASS_TRIAL.getCode());

        ProductionQuantityPolicy policy = ProductionQuantityPolicy.from(sku, false);

        Assertions.assertTrue(policy.isAllowFillStartedShift());
        Assertions.assertFalse(policy.isStrictUpperLimit());
        Assertions.assertTrue(policy.isFullRunForNonTailMachine());
    }

    @Test
    public void shouldKeepTrialNonEndingSkuStrict() {
        SkuScheduleDTO sku = new SkuScheduleDTO();
        sku.setTrial(true);
        sku.setConstructionStage(ConstructionStageEnum.TRIAL.getCode());

        ProductionQuantityPolicy policy = ProductionQuantityPolicy.from(sku, false);

        Assertions.assertFalse(policy.isAllowFillStartedShift());
        Assertions.assertTrue(policy.isStrictUpperLimit());
        Assertions.assertFalse(policy.isFullRunForNonTailMachine());
    }

    @Test
    public void shouldKeepEndingSkuStrict() {
        SkuScheduleDTO sku = new SkuScheduleDTO();
        sku.setConstructionStage(ConstructionStageEnum.FORMAL.getCode());

        ProductionQuantityPolicy policy = ProductionQuantityPolicy.from(sku, true);

        Assertions.assertFalse(policy.isAllowFillStartedShift());
        Assertions.assertTrue(policy.isStrictUpperLimit());
        Assertions.assertFalse(policy.isFullRunForNonTailMachine());
    }

    @Test
    public void shouldKeepFormalEndingSkuStrictWhenEndingComesFromWindowCapacityRule() {
        SkuScheduleDTO sku = new SkuScheduleDTO();
        sku.setConstructionStage(ConstructionStageEnum.FORMAL.getCode());
        sku.setTargetScheduleQty(158);
        sku.setWindowPlanQty(158);
        sku.setSurplusQty(158);
        sku.setDailyCapacity(48);

        ProductionQuantityPolicy policy = ProductionQuantityPolicy.from(sku, true);

        Assertions.assertFalse(policy.isAllowFillStartedShift());
        Assertions.assertTrue(policy.isStrictUpperLimit());
        Assertions.assertFalse(policy.isFullRunForNonTailMachine());
    }

    @Test
    public void shouldKeepEndingSkuStrictWhenEndingTargetExceedsWindowPlan() {
        SkuScheduleDTO sku = new SkuScheduleDTO();
        sku.setConstructionStage(ConstructionStageEnum.FORMAL.getCode());
        sku.setTargetScheduleQty(50);
        sku.setWindowPlanQty(46);
        sku.setSurplusQty(46);
        sku.setEmbryoStock(50);
        sku.setDailyCapacity(48);

        ProductionQuantityPolicy policy = ProductionQuantityPolicy.from(sku, true);

        Assertions.assertFalse(policy.isAllowFillStartedShift());
        Assertions.assertTrue(policy.isStrictUpperLimit());
        Assertions.assertFalse(policy.isFullRunForNonTailMachine());
    }

    @Test
    public void shouldKeepSmallTailEndingSkuStrict() {
        SkuScheduleDTO sku = new SkuScheduleDTO();
        sku.setConstructionStage(ConstructionStageEnum.FORMAL.getCode());
        sku.setTargetScheduleQty(46);
        sku.setWindowPlanQty(46);
        sku.setSurplusQty(46);
        sku.setDailyCapacity(48);

        ProductionQuantityPolicy policy = ProductionQuantityPolicy.from(sku, true);

        Assertions.assertFalse(policy.isAllowFillStartedShift());
        Assertions.assertTrue(policy.isStrictUpperLimit());
        Assertions.assertFalse(policy.isFullRunForNonTailMachine());
    }
}
