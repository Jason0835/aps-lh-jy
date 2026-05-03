package com.zlt.aps.lh.context;

import com.zlt.aps.lh.api.constant.LhScheduleParamConstant;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

/**
 * 硫化排程配置快照测试。
 *
 * @author APS
 */
public class LhScheduleConfigTest {

    /**
     * 用例说明：模具清洗预警与提前天数参数应可从配置快照读取。
     */
    @Test
    public void shouldReadMouldCleaningWarningAndAdvanceConfig() {
        Map<String, String> paramMap = new HashMap<>(8);
        paramMap.put(LhScheduleParamConstant.DRY_ICE_WARNING_DAYS, "6");
        paramMap.put(LhScheduleParamConstant.DRY_ICE_ADVANCE_DAYS, "3");
        paramMap.put(LhScheduleParamConstant.SAND_BLAST_WARNING_DAYS, "24");
        paramMap.put(LhScheduleParamConstant.SAND_BLAST_ADVANCE_DAYS, "4");
        paramMap.put(LhScheduleParamConstant.MOULD_CLEANING_ADVANCE_DAYS, "5");

        LhScheduleConfig config = new LhScheduleConfig(paramMap);

        Assertions.assertEquals(6, config.getDryIceWarningDays());
        Assertions.assertEquals(3, config.getDryIceAdvanceDays());
        Assertions.assertEquals(24, config.getSandBlastWarningDays());
        Assertions.assertEquals(4, config.getSandBlastAdvanceDays());
        Assertions.assertEquals(5, config.getMouldCleaningAdvanceDays());
    }
}
