package com.zlt.aps.lh.service.impl;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.zlt.aps.lh.api.domain.entity.LhScheduleResult;
import com.zlt.aps.lh.api.enums.TrialStatusEnum;
import com.zlt.aps.lh.context.LhScheduleContext;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SchedulePersistenceService} 保存前字段补齐测试。
 */
class SchedulePersistenceServiceTest {

    @Test
    void fillClassEndFlags_shouldMarkMachineLastShiftBeforeWindowEndAndKeepWindowEndNormalWhenSkuNotEnding()
            throws Exception {
        SchedulePersistenceService service = new SchedulePersistenceService();
        List<LhScheduleResult> results = Arrays.asList(
                buildNewSpecResult("K1311", 2, 16, 16, 16, 14, 16, null, null),
                buildNewSpecResult("K1405", 2, 16, 16, 16, 14, 16, null, null),
                buildNewSpecResult("K2024", 2, 16, 16, 16, 14, 16, null, null),
                buildNewSpecResult("K2025", null, 4, 16, 16, 14, 16, 14, null),
                buildNewSpecResult("K1002", null, 4, 16, 16, 14, 16, 16, 14));

        invokeFillClassEndFlags(service, results);

        assertEquals("1", results.get(0).getClass6IsEnd(), "K1311 第 6 班后不再排产，应标记为机台收尾");
        assertEquals("1", results.get(1).getClass6IsEnd(), "K1405 第 6 班后不再排产，应标记为机台收尾");
        assertEquals("1", results.get(2).getClass6IsEnd(), "K2024 第 6 班后不再排产，应标记为机台收尾");
        assertEquals("1", results.get(3).getClass7IsEnd(), "K2025 第 7 班后不再排产，应标记为机台收尾");
        assertEquals("0", results.get(4).getClass8IsEnd(), "K1002 排到窗口最后班次且 SKU 未整体收尾，不应标记收尾");
    }

    @Test
    void fillClassEndFlags_shouldMarkSingleMachineLastShiftWhenItStopsBeforeWindowEnd() throws Exception {
        SchedulePersistenceService service = new SchedulePersistenceService();
        List<LhScheduleResult> results = Arrays.asList(
                buildNewSpecResult("K1311", 2, 16, 16, 16, 14, 16, null, null));

        invokeFillClassEndFlags(service, results);

        assertEquals("1", results.get(0).getClass6IsEnd(), "单机台在窗口内提前停排，应标记机台收尾");
    }

    @Test
    void fillClassEndFlags_shouldMarkWindowEndShiftWhenSkuEnding() throws Exception {
        SchedulePersistenceService service = new SchedulePersistenceService();
        LhScheduleResult result = buildNewSpecResult("K1002", null, 4, 16, 16, 14, 16, 16, 14);
        result.setIsEnd("1");

        invokeFillClassEndFlags(service, Arrays.asList(result));

        assertEquals("1", result.getClass8IsEnd(), "排到窗口最后班次时，只有 SKU 整体收尾才标记收尾");
    }

    @Test
    void fillClassEndFlags_shouldOnlyMarkLatestResultWhenSameMachineHasMultipleRows() throws Exception {
        SchedulePersistenceService service = new SchedulePersistenceService();
        LhScheduleResult earlyResult = buildNewSpecResult("K1311", 2, 16, 16, 8, null, null, null, null);
        LhScheduleResult latestResult = buildNewSpecResult("K1311", null, null, null, null, 14, 16, null, null);

        invokeFillClassEndFlags(service, Arrays.asList(earlyResult, latestResult));

        assertEquals("0", earlyResult.getClass4IsEnd(), "同机台后续仍有排产时，早段结果不应标记收尾");
        assertEquals("1", latestResult.getClass6IsEnd(), "同机台最后一条有量结果应标记机台收尾");
    }

    /**
     * 验证保存前先完成原有收尾计算，再仅按产品状态覆盖有计划量班次。
     *
     * @throws Exception 反射调用异常
     */
    @Test
    void fillClassEndFlags_shouldOverrideOnlyPlannedTrialShiftsAndKeepOriginalScheduleFields() throws Exception {
        SchedulePersistenceService service = new SchedulePersistenceService();
        Date startTime = new Date(1000L);
        Date endTime = new Date(2000L);

        LhScheduleResult massTrialEnding = buildResultWithStatus(
                "330200-T-END", "K-T-END", TrialStatusEnum.MASS_TRIAL.getCode(), "1",
                10, 0, 5, null, null, null, null, null);
        massTrialEnding.setClass1StartTime(startTime);
        massTrialEnding.setClass1EndTime(endTime);
        LhScheduleResult massTrialNotEnding = buildResultWithStatus(
                "330200-T-NORMAL", "K-T-NORMAL", TrialStatusEnum.MASS_TRIAL.getCode(), "0",
                null, null, null, null, null, null, null, 6);
        LhScheduleResult trialEnding = buildResultWithStatus(
                "330200-X-END", "K-X-END", TrialStatusEnum.TRIAL.getCode(), "1",
                7, null, 3, null, null, null, null, null);
        LhScheduleResult trialNotEnding = buildResultWithStatus(
                "330200-X-NORMAL", "K-X-NORMAL", TrialStatusEnum.TRIAL.getCode(), "0",
                null, null, null, null, null, null, null, 8);
        LhScheduleResult formal = buildResultWithStatus(
                "330200-S", "K-S", TrialStatusEnum.FORMAL.getCode(), "1",
                4, null, 2, null, null, null, null, null);
        LhScheduleResult emptyStatus = buildResultWithStatus(
                "330200-EMPTY", "K-EMPTY", "", "1",
                4, null, 2, null, null, null, null, null);
        LhScheduleResult unknownStatus = buildResultWithStatus(
                "330200-UNKNOWN", "K-UNKNOWN", "UNKNOWN", "1",
                4, null, 2, null, null, null, null, null);

        invokeFillClassEndFlags(service, Arrays.asList(
                massTrialEnding, massTrialNotEnding, trialEnding, trialNotEnding,
                formal, emptyStatus, unknownStatus));

        assertEquals("2", massTrialEnding.getClass1IsEnd(), "量试收尾结果的非末班有量班次也应覆盖为2");
        assertNull(massTrialEnding.getClass2IsEnd(), "零量班次必须保持空值");
        assertEquals("2", massTrialEnding.getClass3IsEnd(), "量试收尾班次应覆盖原收尾值为2");
        assertEquals("2", massTrialNotEnding.getClass8IsEnd(), "量试非收尾班次也应覆盖为2");
        assertEquals("3", trialEnding.getClass1IsEnd(), "试验收尾结果的非末班有量班次也应覆盖为3");
        assertNull(trialEnding.getClass2IsEnd(), "无计划量班次必须保持空值");
        assertEquals("3", trialEnding.getClass3IsEnd(), "试验收尾班次应覆盖原收尾值为3");
        assertEquals("3", trialNotEnding.getClass8IsEnd(), "试验非收尾班次也应覆盖为3");
        assertEquals("0", formal.getClass1IsEnd(), "正规状态应保留原正常标记");
        assertEquals("1", formal.getClass3IsEnd(), "正规状态应保留原收尾标记");
        assertEquals("0", emptyStatus.getClass1IsEnd(), "产品状态为空时应保留原正常标记");
        assertEquals("1", emptyStatus.getClass3IsEnd(), "产品状态为空时应保留原收尾标记");
        assertEquals("0", unknownStatus.getClass1IsEnd(), "未知产品状态应保留原正常标记");
        assertEquals("1", unknownStatus.getClass3IsEnd(), "未知产品状态应保留原收尾标记");
        assertEquals("1", massTrialEnding.getIsEnd(), "产品状态覆盖不得修改结果行收尾标识");
        assertEquals(10, massTrialEnding.getClass1PlanQty(), "产品状态覆盖不得修改班次计划量");
        assertEquals(startTime, massTrialEnding.getClass1StartTime(), "产品状态覆盖不得修改班次开始时间");
        assertEquals(endTime, massTrialEnding.getClass1EndTime(), "产品状态覆盖不得修改班次结束时间");
    }

    /**
     * 验证班次收尾日志包含产品状态，并输出状态覆盖后的最终班次摘要。
     *
     * @throws Exception 反射调用异常
     */
    @Test
    void fillClassEndFlags_shouldLogProductStatusAndFinalTrialShiftSummary() throws Exception {
        SchedulePersistenceService service = new SchedulePersistenceService();
        LhScheduleResult result = buildResultWithStatus(
                "330200-T-LOG", "K-T-LOG", TrialStatusEnum.MASS_TRIAL.getCode(), "0",
                5, null, null, null, null, null, null, 6);
        LhScheduleResult trialResult = buildResultWithStatus(
                "330200-X-LOG", "K-X-LOG", TrialStatusEnum.TRIAL.getCode(), "0",
                4, null, null, null, null, null, null, 7);
        ListAppender<ILoggingEvent> appender = attachAppender();
        try {
            invokeFillClassEndFlags(service, Arrays.asList(result, trialResult));
        } finally {
            detachAppender(appender);
        }

        List<String> messageList = appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .collect(Collectors.toList());
        assertTrue(messageList.stream()
                        .anyMatch(message -> message.contains("产品状态: T")
                                && message.contains("class1IsEnd=2")
                                && message.contains("class8IsEnd=2")),
                "量试日志应同时包含产品状态和覆盖后的最终2班次摘要");
        assertTrue(messageList.stream()
                        .anyMatch(message -> message.contains("产品状态: X")
                                && message.contains("class1IsEnd=3")
                                && message.contains("class8IsEnd=3")),
                "试验/试制日志应同时包含产品状态和覆盖后的最终3班次摘要");
    }

    private void invokeFillClassEndFlags(SchedulePersistenceService service,
                                         List<LhScheduleResult> results) throws Exception {
        Method method = SchedulePersistenceService.class.getDeclaredMethod(
                "fillClassEndFlags", LhScheduleContext.class, List.class);
        method.setAccessible(true);
        method.invoke(service, null, results);
    }

    private LhScheduleResult buildNewSpecResult(String machineCode,
                                                Integer class1PlanQty,
                                                Integer class2PlanQty,
                                                Integer class3PlanQty,
                                                Integer class4PlanQty,
                                                Integer class5PlanQty,
                                                Integer class6PlanQty,
                                                Integer class7PlanQty,
                                                Integer class8PlanQty) {
        LhScheduleResult result = new LhScheduleResult();
        result.setMaterialCode("3302001074");
        result.setLhMachineCode(machineCode);
        result.setScheduleType("02");
        result.setIsTypeBlock("0");
        result.setIsEnd("0");
        result.setClass1PlanQty(class1PlanQty);
        result.setClass2PlanQty(class2PlanQty);
        result.setClass3PlanQty(class3PlanQty);
        result.setClass4PlanQty(class4PlanQty);
        result.setClass5PlanQty(class5PlanQty);
        result.setClass6PlanQty(class6PlanQty);
        result.setClass7PlanQty(class7PlanQty);
        result.setClass8PlanQty(class8PlanQty);
        return result;
    }

    /**
     * 构造指定产品状态的排程结果。
     *
     * @param materialCode 物料编码
     * @param machineCode 机台编码
     * @param productStatus 产品状态
     * @param isEnd 结果行是否收尾
     * @param class1PlanQty 1班计划量
     * @param class2PlanQty 2班计划量
     * @param class3PlanQty 3班计划量
     * @param class4PlanQty 4班计划量
     * @param class5PlanQty 5班计划量
     * @param class6PlanQty 6班计划量
     * @param class7PlanQty 7班计划量
     * @param class8PlanQty 8班计划量
     * @return 排程结果
     */
    private LhScheduleResult buildResultWithStatus(String materialCode,
                                                   String machineCode,
                                                   String productStatus,
                                                   String isEnd,
                                                   Integer class1PlanQty,
                                                   Integer class2PlanQty,
                                                   Integer class3PlanQty,
                                                   Integer class4PlanQty,
                                                   Integer class5PlanQty,
                                                   Integer class6PlanQty,
                                                   Integer class7PlanQty,
                                                   Integer class8PlanQty) {
        LhScheduleResult result = buildNewSpecResult(machineCode,
                class1PlanQty, class2PlanQty, class3PlanQty, class4PlanQty,
                class5PlanQty, class6PlanQty, class7PlanQty, class8PlanQty);
        result.setMaterialCode(materialCode);
        result.setProductStatus(productStatus);
        result.setIsEnd(isEnd);
        return result;
    }

    /**
     * 挂载持久化服务日志采集器。
     *
     * @return 日志采集器
     */
    private ListAppender<ILoggingEvent> attachAppender() {
        Logger logger = (Logger) LoggerFactory.getLogger(SchedulePersistenceService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    /**
     * 移除持久化服务日志采集器，避免影响其他测试。
     *
     * @param appender 日志采集器
     */
    private void detachAppender(ListAppender<ILoggingEvent> appender) {
        Logger logger = (Logger) LoggerFactory.getLogger(SchedulePersistenceService.class);
        logger.detachAppender(appender);
        appender.stop();
    }
}
