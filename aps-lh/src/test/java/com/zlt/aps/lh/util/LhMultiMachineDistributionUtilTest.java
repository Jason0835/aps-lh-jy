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
     * 用例说明：多机台余量和胎胚库存需要按机台数均分，最后一台补尾差。
     */
    @Test
    public void shouldDistributeSurplusAndEmbryoStockEvenlyByMachineCount() {
        LhScheduleResult first = buildResult("K1105", 22);
        LhScheduleResult second = buildResult("K1111", 30);
        LhScheduleResult third = buildResult("K1501R", 7);
        List<LhScheduleResult> materialResults = Arrays.asList(first, second, third);

        LhMultiMachineDistributionUtil.distributeForSingleMaterial(materialResults, 30, 14);

        Assertions.assertEquals(10, first.getMouldSurplusQty());
        Assertions.assertEquals(10, second.getMouldSurplusQty());
        Assertions.assertEquals(10, third.getMouldSurplusQty());
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
