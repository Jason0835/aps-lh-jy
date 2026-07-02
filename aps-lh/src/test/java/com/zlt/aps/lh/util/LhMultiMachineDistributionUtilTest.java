package com.zlt.aps.lh.util;

import com.zlt.aps.lh.api.domain.entity.LhScheduleResult;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

/**
 * LhMultiMachineDistributionUtil 库存回写规则测试。
 *
 * @author APS
 */
public class LhMultiMachineDistributionUtilTest {

    /**
     * 用例说明：同一SKU在多台机台排产时，每条机台结果都保留原始胎胚库存。
     */
    @Test
    public void shouldKeepFullEmbryoStockForEveryMachineOfSameMaterial() {
        LhScheduleResult first = buildResult("K1105", 22);
        LhScheduleResult second = buildResult("K1111", 30);
        LhScheduleResult third = buildResult("K1501R", 7);
        List<LhScheduleResult> materialResults = Arrays.asList(first, second, third);

        // 不改变硫化余量，每条同SKU机台结果都保留100条原始胎胚库存。
        LhMultiMachineDistributionUtil.retainFullEmbryoStockForSingleMaterial(materialResults, 100);

        Assertions.assertNull(first.getMouldSurplusQty());
        Assertions.assertNull(second.getMouldSurplusQty());
        Assertions.assertNull(third.getMouldSurplusQty());
        Assertions.assertEquals(100, first.getEmbryoStock());
        Assertions.assertEquals(100, second.getEmbryoStock());
        Assertions.assertEquals(100, third.getEmbryoStock());
    }

    private LhScheduleResult buildResult(String machineCode, int dailyPlanQty) {
        LhScheduleResult result = new LhScheduleResult();
        result.setLhMachineCode(machineCode);
        result.setDailyPlanQty(dailyPlanQty);
        return result;
    }
}
