package com.zlt.aps.lh.regression;

import com.zlt.aps.lh.api.domain.dto.SkuDailyPlanQuotaDTO;
import com.zlt.aps.lh.engine.strategy.support.DailyMachineCapacitySimulationRequest;
import com.zlt.aps.lh.engine.strategy.support.DailyMachineCapacitySimulationResult;
import com.zlt.aps.lh.engine.strategy.support.DailyMachineCapacitySimulationUtil;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * dayN 机台产能模拟回归。
 */
class DailyMachineCapacitySimulationUtilRegressionTest {

    @Test
    void simulateExpansion_shouldAddMachineByMinimumIncrementAndKeepActiveMachines() {
        LocalDate day1 = LocalDate.of(2026, 5, 3);
        LocalDate day2 = LocalDate.of(2026, 5, 4);
        LocalDate day3 = LocalDate.of(2026, 5, 5);
        DailyMachineCapacitySimulationRequest request = new DailyMachineCapacitySimulationRequest();
        request.setMaterialCode("3302002177");
        request.setDailyPlanQuotaMap(quotaMap(day1, day2, day3, 20, 20, 20));
        request.setMachineDailyCapacityList(machineCapacityList(day1, day2, day3, 10, 10, 10, 3));
        request.setInitialActiveMachines(1);
        request.setShortageLookAheadDays(2);
        request.setWindowEndDate(day3);
        request.setSceneType("newSpec");

        DailyMachineCapacitySimulationResult result =
                DailyMachineCapacitySimulationUtil.simulateExpansion(request);

        assertEquals(2, result.getFinalActiveMachines(), "day1-day3 一台追不回时只应新增一台");
        assertEquals(1, result.getTotalAddedMachineCount());
        assertEquals(0, result.getTotalUnmetQty());
        assertEquals(2, result.getDayDecisionList().get(1).getActiveMachineCount(),
                "day1 新增后的 activeMachines 应继续计入 day2/day3");
    }

    @Test
    void simulateExpansion_shouldCarryShortageAndAvoidImmediateMachineWhenLookAheadCanRecover() {
        LocalDate day1 = LocalDate.of(2026, 5, 3);
        LocalDate day2 = LocalDate.of(2026, 5, 4);
        LocalDate day3 = LocalDate.of(2026, 5, 5);
        DailyMachineCapacitySimulationRequest request = new DailyMachineCapacitySimulationRequest();
        request.setMaterialCode("3302002177");
        request.setDailyPlanQuotaMap(quotaMap(day1, day2, day3, 20, 20, 0));
        request.setMachineDailyCapacityList(machineCapacityList(day1, day2, day3, 10, 30, 0, 2));
        request.setInitialActiveMachines(1);
        request.setShortageLookAheadDays(1);
        request.setWindowEndDate(day3);
        request.setSceneType("newSpec");

        DailyMachineCapacitySimulationResult result =
                DailyMachineCapacitySimulationUtil.simulateExpansion(request);

        assertEquals(1, result.getFinalActiveMachines(), "day2 可追回 day1 欠产时不应立即新增机台");
        assertEquals(0, result.getTotalAddedMachineCount());
        assertEquals(0, result.getTotalUnmetQty());
        assertEquals(20, result.getDayDecisionList().get(0).getTodayRequiredQty());
        assertEquals(10, result.getDayDecisionList().get(0).getTodayCapacityQty());
        assertEquals(10, result.getDayDecisionList().get(0).getDayShortageQty());
        assertEquals(10, result.getDayDecisionList().get(1).getCarryShortageQty());
        assertEquals(30, result.getDayDecisionList().get(1).getTodayRequiredQty());
        assertEquals(0, result.getDayDecisionList().get(1).getDayShortageQty());
    }

    @Test
    void simulateExpansion_shouldNotTreatLaterRemainingQtyAsHistoricalShortageAgain() {
        LocalDate day1 = LocalDate.of(2026, 5, 3);
        LocalDate day2 = LocalDate.of(2026, 5, 4);
        LocalDate day3 = LocalDate.of(2026, 5, 5);
        DailyMachineCapacitySimulationRequest request = new DailyMachineCapacitySimulationRequest();
        request.setMaterialCode("3302002177");
        Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap = quotaMap(day1, day2, day3, 20, 20, 0);
        quotaMap.get(day1).setRemainingQty(30);
        quotaMap.get(day2).setRemainingQty(30);
        request.setDailyPlanQuotaMap(quotaMap);
        request.setMachineDailyCapacityList(machineCapacityList(day1, day2, day3, 30, 20, 0, 2));
        request.setInitialActiveMachines(1);
        request.setShortageLookAheadDays(1);
        request.setWindowEndDate(day3);
        request.setSceneType("newSpec");

        DailyMachineCapacitySimulationResult result =
                DailyMachineCapacitySimulationUtil.simulateExpansion(request);

        assertEquals(1, result.getFinalActiveMachines(), "非首日remaining含历史欠产时，不应重复触发新增机台");
        assertEquals(0, result.getTotalAddedMachineCount());
        assertEquals(10, result.getDayDecisionList().get(0).getCarryShortageQty(),
                "首日只允许用remaining-dayPlan初始化历史欠产");
        assertEquals(20, result.getDayDecisionList().get(1).getTodayPlanQty(),
                "非首日目标量必须使用dayN计划量，不能再次使用remainingQty");
        assertEquals(0, result.getTotalUnmetQty());
    }

    @Test
    void simulateExpansion_shouldAddMachineByShortageThresholdWindowDemand() {
        LocalDate day1 = LocalDate.of(2026, 5, 9);
        LocalDate day2 = LocalDate.of(2026, 5, 10);
        LocalDate day3 = LocalDate.of(2026, 5, 11);
        DailyMachineCapacitySimulationRequest request = new DailyMachineCapacitySimulationRequest();
        request.setMaterialCode("3302001592");
        Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap = quotaMap(day1, day2, day3, 48, 48, 48);
        quotaMap.get(day1).setRemainingQty(240);
        request.setDailyPlanQuotaMap(quotaMap);
        request.setMachineDailyCapacityList(machineCapacityList(day1, day2, day3, 32, 48, 48, 3));
        request.setInitialActiveMachines(1);
        request.setShortageLookAheadDays(2);
        request.setShortageAddMachineThreshold(150);
        request.setMonthlyHistoryShortageQty(192);
        request.setWindowEndDate(day3);
        request.setSceneType("newSpec");

        DailyMachineCapacitySimulationResult result =
                DailyMachineCapacitySimulationUtil.simulateExpansion(request);

        assertEquals(3, result.getFinalActiveMachines(), "大欠产超过阈值时应按窗口需消化量动态扩到三台机台");
        assertEquals(2, result.getTotalAddedMachineCount(), "缺口需要两台机台时不能写死只加一台");
    }

    @Test
    void simulateExpansion_shouldOnlyLookNextDayWhenShortageWithinThreshold() {
        LocalDate day1 = LocalDate.of(2026, 5, 9);
        LocalDate day2 = LocalDate.of(2026, 5, 10);
        LocalDate day3 = LocalDate.of(2026, 5, 11);
        DailyMachineCapacitySimulationRequest request = new DailyMachineCapacitySimulationRequest();
        request.setMaterialCode("3302001592");
        Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap = quotaMap(day1, day2, day3, 48, 48, 48);
        quotaMap.get(day1).setRemainingQty(148);
        request.setDailyPlanQuotaMap(quotaMap);
        request.setMachineDailyCapacityList(machineCapacityList(day1, day2, day3, 32, 48, 48, 2));
        request.setInitialActiveMachines(1);
        request.setShortageLookAheadDays(2);
        request.setShortageAddMachineThreshold(150);
        request.setMonthlyHistoryShortageQty(100);
        request.setWindowEndDate(day3);
        request.setSceneType("newSpec");

        DailyMachineCapacitySimulationResult result =
                DailyMachineCapacitySimulationUtil.simulateExpansion(request);

        assertEquals(1, result.getFinalActiveMachines(), "小欠产未超阈值且当前机台能满足后续日计划时不应增机台");
        assertEquals(0, result.getTotalAddedMachineCount());
    }

    @Test
    void simulateExpansion_shouldKeepSmallShortageModeWhenRollingShortageExceedsThreshold() {
        LocalDate day1 = LocalDate.of(2026, 5, 9);
        LocalDate day2 = LocalDate.of(2026, 5, 10);
        LocalDate day3 = LocalDate.of(2026, 5, 11);
        DailyMachineCapacitySimulationRequest request = new DailyMachineCapacitySimulationRequest();
        request.setMaterialCode("3302001592");
        Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap = quotaMap(day1, day2, day3, 48, 48, 48);
        quotaMap.get(day1).setRemainingQty(198);
        request.setDailyPlanQuotaMap(quotaMap);
        request.setMachineDailyCapacityList(machineCapacityList(day1, day2, day3, 0, 48, 48, 2));
        request.setInitialActiveMachines(1);
        request.setShortageLookAheadDays(2);
        request.setShortageAddMachineThreshold(150);
        request.setMonthlyHistoryShortageQty(150);
        request.setWindowEndDate(day3);
        request.setSceneType("newSpec");

        DailyMachineCapacitySimulationResult result =
                DailyMachineCapacitySimulationUtil.simulateExpansion(request);

        assertEquals(1, result.getFinalActiveMachines(),
                "历史欠产未超阈值时，即使滚动欠产放大，也应继续按小欠产逐日后看，不应切到窗口扩机");
        assertEquals(0, result.getTotalAddedMachineCount());
    }

    private Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap(LocalDate day1,
                                                          LocalDate day2,
                                                          LocalDate day3,
                                                          int day1Qty,
                                                          int day2Qty,
                                                          int day3Qty) {
        Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap = new LinkedHashMap<LocalDate, SkuDailyPlanQuotaDTO>(4);
        quotaMap.put(day1, quota(day1, day1Qty));
        quotaMap.put(day2, quota(day2, day2Qty));
        quotaMap.put(day3, quota(day3, day3Qty));
        return quotaMap;
    }

    private SkuDailyPlanQuotaDTO quota(LocalDate productionDate, int dayPlanQty) {
        SkuDailyPlanQuotaDTO quota = new SkuDailyPlanQuotaDTO();
        quota.setMaterialCode("3302002177");
        quota.setProductionDate(productionDate);
        quota.setDayPlanQty(dayPlanQty);
        quota.setRemainingQty(dayPlanQty);
        return quota;
    }

    private List<Map<LocalDate, Integer>> machineCapacityList(LocalDate day1,
                                                              LocalDate day2,
                                                              LocalDate day3,
                                                              int day1Capacity,
                                                              int day2Capacity,
                                                              int day3Capacity,
                                                              int machineCount) {
        List<Map<LocalDate, Integer>> capacityList =
                new ArrayList<Map<LocalDate, Integer>>(machineCount);
        for (int index = 0; index < machineCount; index++) {
            Map<LocalDate, Integer> capacityMap = new LinkedHashMap<LocalDate, Integer>(4);
            capacityMap.put(day1, day1Capacity);
            capacityMap.put(day2, day2Capacity);
            capacityMap.put(day3, day3Capacity);
            capacityList.add(capacityMap);
        }
        return capacityList;
    }
}
