package com.zlt.aps.lh.engine.strategy.impl;

import com.zlt.aps.lh.api.domain.dto.SkuScheduleDTO;
import com.zlt.aps.lh.context.LhScheduleContext;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

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

        Boolean sharedZeroSurplus = ReflectionTestUtils.invokeMethod(
                strategy, "isSharedEmbryoZeroSurplusSku", context, sku);

        Assertions.assertFalse(Boolean.TRUE.equals(sharedZeroSurplus));
    }
}
