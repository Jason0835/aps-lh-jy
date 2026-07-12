package com.zlt.aps.lh.engine.strategy.impl;

import com.zlt.aps.lh.api.domain.dto.SkuScheduleDTO;
import com.zlt.aps.lh.api.domain.entity.LhScheduleResult;
import com.zlt.aps.lh.api.enums.SkuTagEnum;
import com.zlt.aps.lh.component.TargetScheduleQtyResolver;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.util.ShiftFieldUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;

/**
 * 换活字块目标量规则测试。
 *
 * @author APS
 */
public class TypeBlockProductionStrategyTest {

    /**
     * 用例说明：成型胎胚库存收尾时，不能因共用胎胚硫化余量为0提前进入未排。
     */
    @Test
    public void isSharedEmbryoZeroSurplusSku_shouldIgnoreEmbryoStockEnding() {
        TypeBlockProductionStrategy strategy = new TypeBlockProductionStrategy();
        LhScheduleContext context = new LhScheduleContext();
        context.getMaterialSharedEmbryoMap().put("3302005002", true);
        context.getEmbryoEndingFlagMap().put("EMB-END-02", 1);
        SkuScheduleDTO sku = new SkuScheduleDTO();
        sku.setMaterialCode("3302005002");
        sku.setEmbryoCode("EMB-END-02");
        sku.setSurplusQty(0);
        sku.setEmbryoStock(5);
        sku.setSkuTag(SkuTagEnum.ENDING.getCode());
        sku.setEndingDaysRemaining(1);

        Boolean sharedZeroSurplus = ReflectionTestUtils.invokeMethod(
                strategy, "isSharedEmbryoZeroSurplusSku", context, sku);

        Assertions.assertFalse(Boolean.TRUE.equals(sharedZeroSurplus));
    }

    /**
     * 用例说明：双模换活字块应按L/R合计10条只扣一次账本，再均分为每侧5条。
     */
    @Test
    public void applyWholeSingleControlTypeBlockToDailyQuota_shouldDeductGroupQtyOnce() {
        TypeBlockProductionStrategy strategy = new TypeBlockProductionStrategy();
        TargetScheduleQtyResolver targetScheduleQtyResolver = new TargetScheduleQtyResolver();
        ReflectionTestUtils.setField(strategy, "targetScheduleQtyResolver", targetScheduleQtyResolver);
        LhScheduleContext context = new LhScheduleContext();
        SkuScheduleDTO sku = new SkuScheduleDTO();
        sku.setMaterialCode("DOUBLE-TYPE-BLOCK");
        sku.setTargetScheduleQty(10);
        sku.setMouldQty(1);
        targetScheduleQtyResolver.initializeProductionRemainingQty(context, sku, 10, "双模换活字块测试");

        LhScheduleResult primaryResult = result("K1501L", 5);
        LhScheduleResult pairResult = result("K1501R", 5);

        Integer actualQty = ReflectionTestUtils.invokeMethod(strategy,
                "applyWholeSingleControlTypeBlockToDailyQuota",
                context, sku, primaryResult, pairResult, Collections.emptyList());

        Assertions.assertEquals(Integer.valueOf(10), actualQty);
        Assertions.assertEquals(Integer.valueOf(5), primaryResult.getClass1PlanQty());
        Assertions.assertEquals(Integer.valueOf(5), pairResult.getClass1PlanQty());
        Assertions.assertEquals(Integer.valueOf(0),
                context.getSkuProductionRemainingQtyMap().get(sku.getMaterialCode()));
    }

    /**
     * 构建单侧换活字块结果。
     *
     * @param machineCode 机台编码
     * @param planQty 单侧计划量
     * @return 排程结果
     */
    private LhScheduleResult result(String machineCode, int planQty) {
        LhScheduleResult result = new LhScheduleResult();
        result.setLhMachineCode(machineCode);
        result.setMaterialCode("DOUBLE-TYPE-BLOCK");
        result.setMouldQty(1);
        result.setLhTime(3600);
        ShiftFieldUtil.setShiftPlanQty(result, 1, planQty, null, null);
        ShiftFieldUtil.syncDailyPlanQty(result);
        return result;
    }
}
