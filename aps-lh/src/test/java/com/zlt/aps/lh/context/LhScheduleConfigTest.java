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
     * 用例说明：新增排产换模均衡开关默认关闭，配置为1时才启用。
     */
    @Test
    public void shouldReadChangeoverBalanceSwitchConfig() {
        LhScheduleConfig defaultConfig = new LhScheduleConfig(new HashMap<String, String>(0));
        Assertions.assertFalse(defaultConfig.isChangeoverBalanceEnabled());

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
     * 用例说明：本月历史欠产追加默认关闭，配置为1时才启用。
     */
    @Test
    public void shouldReadCarryForwardQtySwitchConfig() {
        LhScheduleConfig defaultConfig = new LhScheduleConfig(new HashMap<String, String>(0));
        Assertions.assertFalse(defaultConfig.isCarryForwardQtyEnabled());

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
