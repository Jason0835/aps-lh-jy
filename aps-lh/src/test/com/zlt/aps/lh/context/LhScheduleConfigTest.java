package com.zlt.aps.lh.context;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.zlt.aps.lh.api.constant.LhScheduleParamConstant;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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

    /**
     * 用例说明：未配置硫化定点机台规则开关时默认关闭。
     */
    @Test
    public void shouldDisableSpecifyMachineRuleByDefault() {
        LhScheduleConfig config = new LhScheduleConfig(new HashMap<String, String>(0));

        Assertions.assertFalse(config.isSpecifyMachineRuleEnabled());
    }

    /**
     * 用例说明：硫化定点机台规则开关配置为1时启用。
     */
    @Test
    public void shouldEnableSpecifyMachineRuleWhenConfiguredOne() {
        Map<String, String> paramMap = new HashMap<>(1);
        paramMap.put(LhScheduleParamConstant.ENABLE_SPECIFY_MACHINE_RULE, "1");

        LhScheduleConfig config = new LhScheduleConfig(paramMap);

        Assertions.assertTrue(config.isSpecifyMachineRuleEnabled());
    }

    /**
     * 用例说明：单控基准机台与小批量阈值应可从配置快照读取。
     */
    @Test
    public void shouldReadSingleControlMachineAndSmallBatchConfig() {
        Map<String, String> paramMap = new HashMap<>(2);
        paramMap.put(LhScheduleParamConstant.SINGLE_CONTROL_MACHINE_CODES, "K1501,K1502");
        paramMap.put(LhScheduleParamConstant.SMALL_BATCH_SKU_THRESHOLD, "80");

        LhScheduleConfig config = new LhScheduleConfig(paramMap);

        Assertions.assertEquals("K1501,K1502", config.getSingleControlMachineCodes());
        Assertions.assertEquals(80, config.getSmallBatchSkuThreshold());
    }

    /**
     * 用例说明：新增排产欠产追补判断天数默认值为2，配置后按配置值读取。
     */
    @Test
    public void shouldReadNewSpecShortageLookAheadDaysConfig() {
        LhScheduleConfig defaultConfig = new LhScheduleConfig(new HashMap<String, String>(0));
        Assertions.assertEquals(2, defaultConfig.getNewSpecShortageLookAheadDays());

        Map<String, String> paramMap = new HashMap<>(1);
        paramMap.put(LhScheduleParamConstant.NEW_SPEC_SHORTAGE_LOOK_AHEAD_DAYS, "3");

        LhScheduleConfig config = new LhScheduleConfig(paramMap);

        Assertions.assertEquals(3, config.getNewSpecShortageLookAheadDays());
    }

    /**
     * 用例说明：新增排产欠产增机台阈值默认200，配置后按配置值读取。
     */
    @Test
    public void shouldReadNewSpecShortageAddMachineThresholdConfig() {
        LhScheduleConfig defaultConfig = new LhScheduleConfig(new HashMap<String, String>(0));
        Assertions.assertEquals(200, defaultConfig.getNewSpecShortageAddMachineThreshold());

        Map<String, String> paramMap = new HashMap<>(1);
        paramMap.put(LhScheduleParamConstant.NEW_SPEC_SHORTAGE_ADD_MACHINE_THRESHOLD, "200");

        LhScheduleConfig config = new LhScheduleConfig(paramMap);

        Assertions.assertEquals(200, config.getNewSpecShortageAddMachineThreshold());
    }

    /**
     * 用例说明：同班次总计划量上限默认2800，配置为0或负数时由策略入口按不限制处理。
     */
    @Test
    public void shouldReadClassTotalQtyUpLimitConfig() {
        LhScheduleConfig defaultConfig = new LhScheduleConfig(new HashMap<String, String>(0));
        Assertions.assertEquals(2800, defaultConfig.getClassTotalQtyUpLimit());

        Map<String, String> configuredParamMap = new HashMap<>(1);
        configuredParamMap.put(LhScheduleParamConstant.CLASS_TOTAL_QTY_UP_LIMIT, "100");
        Assertions.assertEquals(100, new LhScheduleConfig(configuredParamMap).getClassTotalQtyUpLimit());

        Map<String, String> zeroParamMap = new HashMap<>(1);
        zeroParamMap.put(LhScheduleParamConstant.CLASS_TOTAL_QTY_UP_LIMIT, "0");
        Assertions.assertEquals(0, new LhScheduleConfig(zeroParamMap).getClassTotalQtyUpLimit());

        Map<String, String> negativeParamMap = new HashMap<>(1);
        negativeParamMap.put(LhScheduleParamConstant.CLASS_TOTAL_QTY_UP_LIMIT, "-1");
        Assertions.assertEquals(-1, new LhScheduleConfig(negativeParamMap).getClassTotalQtyUpLimit());

        Map<String, String> textParamMap = new HashMap<>(1);
        textParamMap.put(LhScheduleParamConstant.CLASS_TOTAL_QTY_UP_LIMIT, "abc");
        Assertions.assertEquals(2800, new LhScheduleConfig(textParamMap).getClassTotalQtyUpLimit());
    }

    /**
     * 用例说明：换胶囊参数默认450/2，合法配置应从配置快照读取。
     */
    @Test
    public void shouldReadCapsuleReplacementConfig() {
        LhScheduleConfig defaultConfig = new LhScheduleConfig(new HashMap<String, String>(0));
        Assertions.assertEquals(450, defaultConfig.getCapsuleUsageUpperLimit());
        Assertions.assertEquals(2, defaultConfig.getCapsuleChangeLossQty());

        Map<String, String> paramMap = new HashMap<String, String>(2);
        paramMap.put(LhScheduleParamConstant.CAPSULE_FORCE_DOWN_COUNT, "500");
        paramMap.put(LhScheduleParamConstant.CAPSULE_CHANGE_LOSS_QTY, "4");
        LhScheduleConfig configured = new LhScheduleConfig(paramMap);

        Assertions.assertEquals(500, configured.getCapsuleUsageUpperLimit());
        Assertions.assertEquals(4, configured.getCapsuleChangeLossQty());
    }

    /**
     * 用例说明：收尾小余量允许欠产偏差默认2，配置后按配置值读取。
     */
    @Test
    public void shouldReadContinuousEndingSurplusToleranceConfig() {
        LhScheduleConfig defaultConfig = new LhScheduleConfig(new HashMap<String, String>(0));
        Assertions.assertEquals(2, defaultConfig.getContinuousEndingSurplusToleranceQty());

        Map<String, String> paramMap = new HashMap<>(1);
        paramMap.put(LhScheduleParamConstant.CONTINUOUS_ENDING_SURPLUS_TOLERANCE_QTY, "3");

        LhScheduleConfig config = new LhScheduleConfig(paramMap);

        Assertions.assertEquals(3, config.getContinuousEndingSurplusToleranceQty());
    }

    /**
     * 用例说明：奇数班产计划量加一班别默认不配置，配置后保留原始值交由产能入口判断合法性。
     */
    @Test
    public void shouldReadOddShiftCapacityPlusShiftTypeConfig() {
        LhScheduleConfig defaultConfig = new LhScheduleConfig(new HashMap<String, String>(0));
        Assertions.assertEquals("", defaultConfig.getOddShiftCapacityPlusShiftType());

        Map<String, String> paramMap = new HashMap<>(1);
        paramMap.put(LhScheduleParamConstant.ODD_SHIFT_CAPACITY_PLUS_SHIFT_TYPE, "2");
        LhScheduleConfig config = new LhScheduleConfig(paramMap);
        Assertions.assertEquals("2", config.getOddShiftCapacityPlusShiftType());

        Map<String, String> invalidParamMap = new HashMap<>(1);
        invalidParamMap.put(LhScheduleParamConstant.ODD_SHIFT_CAPACITY_PLUS_SHIFT_TYPE, "9");
        LhScheduleConfig invalidConfig = new LhScheduleConfig(invalidParamMap);
        Assertions.assertEquals("9", invalidConfig.getOddShiftCapacityPlusShiftType());
    }

    /**
     * 用例说明：日标准产量剩余班次默认中班，配置晚班/早班/中班时按配置读取，非法值回退中班。
     */
    @Test
    public void shouldReadDailyStandardCapacityRemainShiftTypeConfig() {
        LhScheduleConfig defaultConfig = new LhScheduleConfig(new HashMap<String, String>(0));
        Assertions.assertEquals("3", defaultConfig.getDailyStandardCapacityRemainShiftType());

        Map<String, String> nightParamMap = new HashMap<>(1);
        nightParamMap.put(LhScheduleParamConstant.DAILY_STANDARD_CAPACITY_REMAIN_SHIFT_TYPE, "1");
        Assertions.assertEquals("1", new LhScheduleConfig(nightParamMap).getDailyStandardCapacityRemainShiftType());

        Map<String, String> morningParamMap = new HashMap<>(1);
        morningParamMap.put(LhScheduleParamConstant.DAILY_STANDARD_CAPACITY_REMAIN_SHIFT_TYPE, "2");
        Assertions.assertEquals("2", new LhScheduleConfig(morningParamMap).getDailyStandardCapacityRemainShiftType());

        Map<String, String> invalidParamMap = new HashMap<>(1);
        invalidParamMap.put(LhScheduleParamConstant.DAILY_STANDARD_CAPACITY_REMAIN_SHIFT_TYPE, "9");
        Assertions.assertEquals("3", new LhScheduleConfig(invalidParamMap).getDailyStandardCapacityRemainShiftType());
    }

    /**
     * 用例说明：收尾小余量允许欠产偏差异常配置按默认2处理。
     */
    @Test
    public void shouldFallbackDefaultWhenContinuousEndingSurplusToleranceInvalid() {
        Map<String, String> negativeParamMap = new HashMap<>(1);
        negativeParamMap.put(LhScheduleParamConstant.CONTINUOUS_ENDING_SURPLUS_TOLERANCE_QTY, "-1");
        LhScheduleConfig negativeConfig = new LhScheduleConfig(negativeParamMap);
        Assertions.assertEquals(2, negativeConfig.getContinuousEndingSurplusToleranceQty());

        Map<String, String> textParamMap = new HashMap<>(1);
        textParamMap.put(LhScheduleParamConstant.CONTINUOUS_ENDING_SURPLUS_TOLERANCE_QTY, "abc");
        LhScheduleConfig textConfig = new LhScheduleConfig(textParamMap);
        Assertions.assertEquals(2, textConfig.getContinuousEndingSurplusToleranceQty());
    }

    /**
     * 用例说明：新增排产/续作补偿共用欠产增机台阈值，异常值按默认200处理。
     */
    @Test
    public void shouldFallbackDefaultWhenNewSpecShortageAddMachineThresholdInvalid() {
        Map<String, String> zeroParamMap = new HashMap<>(1);
        zeroParamMap.put(LhScheduleParamConstant.NEW_SPEC_SHORTAGE_ADD_MACHINE_THRESHOLD, "0");
        LhScheduleConfig zeroConfig = new LhScheduleConfig(zeroParamMap);
        Assertions.assertEquals(200, zeroConfig.getNewSpecShortageAddMachineThreshold());

        Map<String, String> negativeParamMap = new HashMap<>(1);
        negativeParamMap.put(LhScheduleParamConstant.NEW_SPEC_SHORTAGE_ADD_MACHINE_THRESHOLD, "-1");
        LhScheduleConfig negativeConfig = new LhScheduleConfig(negativeParamMap);
        Assertions.assertEquals(200, negativeConfig.getNewSpecShortageAddMachineThreshold());

        Map<String, String> textParamMap = new HashMap<>(1);
        textParamMap.put(LhScheduleParamConstant.NEW_SPEC_SHORTAGE_ADD_MACHINE_THRESHOLD, "abc");
        LhScheduleConfig textConfig = new LhScheduleConfig(textParamMap);
        Assertions.assertEquals(200, textConfig.getNewSpecShortageAddMachineThreshold());
    }

    /**
     * 用例说明：新增排产/续作补偿共用欠产增机台阈值缺失时，应记录告警并按默认200处理。
     */
    @Test
    public void shouldWarnWhenNewSpecShortageAddMachineThresholdMissing() {
        ListAppender<ILoggingEvent> appender = attachAppender();
        try {
            LhScheduleConfig config = new LhScheduleConfig(new HashMap<String, String>(0));
            Assertions.assertEquals(200, config.getNewSpecShortageAddMachineThreshold());
        } finally {
            detachAppender(appender);
        }

        List<String> messages = appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .collect(Collectors.toList());
        Assertions.assertTrue(messages.stream().anyMatch(message -> message.contains("欠产增机台阈值缺失")
                        && message.contains(LhScheduleParamConstant.NEW_SPEC_SHORTAGE_ADD_MACHINE_THRESHOLD)),
                "缺失参数时应记录默认回落告警，便于排查工厂参数未配置");
    }

    /**
     * 用例说明：新增排产换模均衡开关默认开启，配置为0时才关闭。
     */
    @Test
    public void shouldReadChangeoverBalanceSwitchConfig() {
        LhScheduleConfig defaultConfig = new LhScheduleConfig(new HashMap<String, String>(0));
        Assertions.assertTrue(defaultConfig.isChangeoverBalanceEnabled());

        Map<String, String> enabledParamMap = new HashMap<String, String>(1);
        enabledParamMap.put(LhScheduleParamConstant.ENABLE_CHANGEOVER_BALANCE, "1");
        LhScheduleConfig enabledConfig = new LhScheduleConfig(enabledParamMap);
        Assertions.assertTrue(enabledConfig.isChangeoverBalanceEnabled());
    }

    /**
     * 用例说明：续作欠产追补判断天数使用独立参数，默认1天，允许配置为0。
     */
    @Test
    public void shouldReadContinuousShortageLookAheadDaysConfig() {
        LhScheduleConfig defaultConfig = new LhScheduleConfig(new HashMap<String, String>(0));
        Assertions.assertEquals(1, defaultConfig.getContinuousShortageLookAheadDays());

        Map<String, String> zeroParamMap = new HashMap<>(1);
        zeroParamMap.put(LhScheduleParamConstant.CONTINUOUS_SHORTAGE_LOOK_AHEAD_DAYS, "0");
        LhScheduleConfig zeroConfig = new LhScheduleConfig(zeroParamMap);
        Assertions.assertEquals(0, zeroConfig.getContinuousShortageLookAheadDays());

        Map<String, String> twoParamMap = new HashMap<>(1);
        twoParamMap.put(LhScheduleParamConstant.CONTINUOUS_SHORTAGE_LOOK_AHEAD_DAYS, "2");
        LhScheduleConfig twoConfig = new LhScheduleConfig(twoParamMap);
        Assertions.assertEquals(2, twoConfig.getContinuousShortageLookAheadDays());
    }

    /**
     * 用例说明：在机模具下机校验天数默认2天，只接受1～3。
     */
    @Test
    public void shouldReadContinuousMouldOfflineCheckDaysConfig() {
        LhScheduleConfig defaultConfig = new LhScheduleConfig(new HashMap<String, String>(0));
        Assertions.assertEquals(2, defaultConfig.getContinuousMouldOfflineCheckDays());

        Map<String, String> validParamMap = new HashMap<>(1);
        validParamMap.put(LhScheduleParamConstant.CONTINUOUS_MOULD_OFFLINE_CHECK_DAYS, "3");
        Assertions.assertEquals(3,
                new LhScheduleConfig(validParamMap).getContinuousMouldOfflineCheckDays());

        Map<String, String> zeroParamMap = new HashMap<>(1);
        zeroParamMap.put(LhScheduleParamConstant.CONTINUOUS_MOULD_OFFLINE_CHECK_DAYS, "0");
        Assertions.assertEquals(2,
                new LhScheduleConfig(zeroParamMap).getContinuousMouldOfflineCheckDays());

        Map<String, String> overParamMap = new HashMap<>(1);
        overParamMap.put(LhScheduleParamConstant.CONTINUOUS_MOULD_OFFLINE_CHECK_DAYS, "4");
        Assertions.assertEquals(2,
                new LhScheduleConfig(overParamMap).getContinuousMouldOfflineCheckDays());
    }

    /**
     * 用例说明：本月历史欠产追加默认开启，配置为0时才关闭。
     */
    @Test
    public void shouldReadCarryForwardQtySwitchConfig() {
        LhScheduleConfig defaultConfig = new LhScheduleConfig(new HashMap<String, String>(0));
        Assertions.assertTrue(defaultConfig.isCarryForwardQtyEnabled());

        Map<String, String> paramMap = new HashMap<>(1);
        paramMap.put(LhScheduleParamConstant.ENABLE_CARRY_FORWARD_QTY, "1");
        LhScheduleConfig config = new LhScheduleConfig(paramMap);

        Assertions.assertTrue(config.isCarryForwardQtyEnabled());
    }

    private ListAppender<ILoggingEvent> attachAppender() {
        Logger logger = (Logger) LoggerFactory.getLogger(LhScheduleConfig.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private void detachAppender(ListAppender<ILoggingEvent> appender) {
        Logger logger = (Logger) LoggerFactory.getLogger(LhScheduleConfig.class);
        logger.detachAppender(appender);
    }
}
