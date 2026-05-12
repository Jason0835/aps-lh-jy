package com.zlt.aps.lh.util;

import com.zlt.aps.lh.api.domain.entity.LhScheduleResult;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

/**
 * LhMultiMachineDistributionUtil 分摊规则测试。
 *
 * @author APS
 */
public class LhMultiMachineDistributionUtilTest {

    /**
     * 用例说明：多机台仅胎胚库存按机台数均分（最后一台补尾差），硫化余量保持不分摊。
     */
    @Test
    public void shouldDistributeEmbryoStockOnlyWithoutSurplus() {
        LhScheduleResult first = buildResult("K1105", 22);
        LhScheduleResult second = buildResult("K1111", 30);
        LhScheduleResult third = buildResult("K1501R", 7);
        List<LhScheduleResult> materialResults = Arrays.asList(first, second, third);

        // 余量 30 不再分摊，各机台余量保持原始值（null）
        // 胎胚库存 14 按机台数均分
        LhMultiMachineDistributionUtil.distributeForSingleMaterial(materialResults, 30, 14);

        // 余量不分摊，保持原始 null
        Assertions.assertNull(first.getMouldSurplusQty());
        Assertions.assertNull(second.getMouldSurplusQty());
        Assertions.assertNull(third.getMouldSurplusQty());
        // 胎胚库存仍然分摊
        Assertions.assertEquals(4, first.getEmbryoStock());
        Assertions.assertEquals(4, second.getEmbryoStock());
        Assertions.assertEquals(6, third.getEmbryoStock());
    }

    private LhScheduleResult buildResult(String machineCode, int dailyPlanQty) {
        LhScheduleResult result = new LhScheduleResult();
        result.setLhMachineCode(machineCode);
        result.setDailyPlanQty(dailyPlanQty);
        return result;
    }
}
