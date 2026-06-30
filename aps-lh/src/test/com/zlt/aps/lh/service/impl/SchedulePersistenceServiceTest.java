package com.zlt.aps.lh.service.impl;

import com.zlt.aps.lh.api.domain.entity.LhScheduleResult;
import com.zlt.aps.lh.context.LhScheduleContext;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
