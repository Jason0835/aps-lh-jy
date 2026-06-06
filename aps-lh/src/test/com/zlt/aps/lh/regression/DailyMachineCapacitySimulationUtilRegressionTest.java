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
import static org.junit.jupiter.api.Assertions.assertFalse;

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
    void simulateExpansion_shouldStopWhenWindowRemainingShortageBackToThreshold() {
        LocalDate day1 = LocalDate.of(2026, 5, 9);
        LocalDate day2 = LocalDate.of(2026, 5, 10);
        LocalDate day3 = LocalDate.of(2026, 5, 11);
        DailyMachineCapacitySimulationRequest request = new DailyMachineCapacitySimulationRequest();
        request.setMaterialCode("3302001592");
        Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap = quotaMap(day1, day2, day3, 48, 48, 48);
        quotaMap.get(day1).setRemainingQty(240);
        request.setDailyPlanQuotaMap(quotaMap);
        request.setMachineDailyCapacityList(machineCapacityList(day1, day2, day3, 0, 48, 64, 3));
        request.setInitialActiveMachines(1);
        request.setShiftCapacity(16);
        request.setShortageLookAheadDays(2);
        request.setShortageAddMachineThreshold(150);
        request.setMonthlyHistoryShortageQty(192);
        request.setWindowEndDate(day3);
        request.setSceneType("newSpec");

        DailyMachineCapacitySimulationResult result =
                DailyMachineCapacitySimulationUtil.simulateExpansion(request);

        assertEquals(2, result.getFinalActiveMachines(),
                "窗口后剩余欠产回到阈值以内时，应停止继续增加第三台机台");
        assertEquals(1, result.getTotalAddedMachineCount(), "缺口需要两台机台时不能写死只加一台");
        assertEquals("欠产阈值窗口回落", result.getDayDecisionList().get(0).getDecisionMode());
        assertEquals(144, result.getDayDecisionList().get(0).getWindowMonthPlanQty());
        assertEquals(224, result.getDayDecisionList().get(0).getWindowEffectiveCapacityQty());
        assertEquals(112, result.getDayDecisionList().get(0).getWindowRemainingShortageQty());
        assertEquals(0, result.getDayDecisionList().get(0).getUnmetQty());
        assertEquals(0, result.getTotalUnmetQty(), "窗口后剩余欠产已回到阈值以内时，不应再记录未满足缺口");
    }

    @Test
    void simulateExpansion_shouldKeepUnmetWhenForcedShortageCandidateExhausted() {
        LocalDate day1 = LocalDate.of(2026, 5, 9);
        LocalDate day2 = LocalDate.of(2026, 5, 10);
        LocalDate day3 = LocalDate.of(2026, 5, 11);
        DailyMachineCapacitySimulationRequest request = new DailyMachineCapacitySimulationRequest();
        request.setMaterialCode("3302001592");
        request.setDailyPlanQuotaMap(quotaMap(day1, day2, day3, 48, 48, 48));
        request.setMachineDailyCapacityList(machineCapacityList(day1, day2, day3, 0, 25, 25, 2));
        request.setInitialActiveMachines(1);
        request.setShiftCapacity(16);
        request.setShortageLookAheadDays(2);
        request.setShortageAddMachineThreshold(150);
        request.setMonthlyHistoryShortageQty(192);
        request.setWindowEndDate(day3);
        request.setSceneType("newSpec");

        DailyMachineCapacitySimulationResult result =
                DailyMachineCapacitySimulationUtil.simulateExpansion(request);

        assertEquals(2, result.getFinalActiveMachines(), "候选机台耗尽后必须停止，不能无限加机台");
        assertEquals(1, result.getTotalAddedMachineCount());
        assertEquals(86, result.getTotalUnmetQty(), "候选耗尽后应记录距离阈值仍不足的产能缺口");
        assertEquals(236, result.getDayDecisionList().get(0).getWindowRemainingShortageQty());
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
        request.setShiftCapacity(16);
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
    void simulateExpansion_shouldNotAddMachineWhenEightShiftWindowCapacityCoversPlan() {
        LocalDate day1 = LocalDate.of(2026, 5, 9);
        LocalDate day2 = LocalDate.of(2026, 5, 10);
        LocalDate day3 = LocalDate.of(2026, 5, 11);
        DailyMachineCapacitySimulationRequest request = new DailyMachineCapacitySimulationRequest();
        request.setMaterialCode("3302001592");
        Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap = quotaMap(day1, day2, day3, 8, 60, 60);
        quotaMap.get(day1).setRemainingQty(108);
        request.setDailyPlanQuotaMap(quotaMap);
        request.setMachineDailyCapacityList(machineCapacityList(day1, day2, day3, 16, 48, 48, 2));
        request.setInitialActiveMachines(1);
        request.setShiftCapacity(16);
        request.setShortageLookAheadDays(2);
        request.setShortageAddMachineThreshold(150);
        request.setMonthlyHistoryShortageQty(100);
        request.setWindowEndDate(day3);
        request.setSceneType("newSpec");

        DailyMachineCapacitySimulationResult result =
                DailyMachineCapacitySimulationUtil.simulateExpansion(request);

        assertEquals(1, result.getFinalActiveMachines(),
                "小欠产未超阈值且1台机台8班总产能覆盖窗口日计划总和时，不应增机台");
        assertEquals(0, result.getTotalAddedMachineCount());
        assertEquals(128, result.getDayDecisionList().get(0).getWindowTotalCapacityQty());
        assertEquals(128, result.getDayDecisionList().get(0).getWindowPlanQty());
    }

    @Test
    void simulateExpansion_shouldAddMachineOnSecondDayWhenThirdDayPlanExceedsThreeShiftCapacity() {
        LocalDate day1 = LocalDate.of(2026, 4, 1);
        LocalDate day2 = LocalDate.of(2026, 4, 2);
        LocalDate day3 = LocalDate.of(2026, 4, 3);
        DailyMachineCapacitySimulationRequest request = new DailyMachineCapacitySimulationRequest();
        request.setMaterialCode("3302001236");
        request.setDailyPlanQuotaMap(quotaMap(day1, day2, day3, 0, 8, 80));
        request.setMachineDailyCapacityList(machineCapacityList(day1, day2, day3, 112, 112, 112, 2));
        request.setInitialActiveMachines(1);
        request.setShiftCapacity(16);
        request.setShortageLookAheadDays(2);
        request.setShortageAddMachineThreshold(150);
        request.setMonthlyHistoryShortageQty(0);
        request.setWindowEndDate(day3);
        request.setSceneType("newSpec");

        DailyMachineCapacitySimulationResult result =
                DailyMachineCapacitySimulationUtil.simulateExpansion(request);

        assertEquals(2, result.getFinalActiveMachines(),
                "第二天一台机台无法满足第三天3班计划时，应提前新增一台机台");
        assertEquals(1, result.getTotalAddedMachineCount());
        assertEquals(80, result.getDayDecisionList().get(1).getNextDayPlanQty());
        assertEquals(1, result.getDayDecisionList().get(1).getAddedMachineCount());
    }

    @Test
    void simulateExpansion_shouldKeepSmallShortageModeWhenHistoryShortageIsZero() {
        LocalDate day1 = LocalDate.of(2026, 5, 9);
        LocalDate day2 = LocalDate.of(2026, 5, 10);
        LocalDate day3 = LocalDate.of(2026, 5, 11);
        DailyMachineCapacitySimulationRequest request = new DailyMachineCapacitySimulationRequest();
        request.setMaterialCode("3302001592");
        Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap = quotaMap(day1, day2, day3, 8, 60, 60);
        request.setDailyPlanQuotaMap(quotaMap);
        request.setMachineDailyCapacityList(machineCapacityList(day1, day2, day3, 16, 48, 48, 2));
        request.setInitialActiveMachines(1);
        request.setShiftCapacity(16);
        request.setShortageLookAheadDays(2);
        request.setShortageAddMachineThreshold(150);
        request.setMonthlyHistoryShortageQty(0);
        request.setWindowEndDate(day3);
        request.setSceneType("newSpec");

        DailyMachineCapacitySimulationResult result =
                DailyMachineCapacitySimulationUtil.simulateExpansion(request);

        assertEquals(1, result.getFinalActiveMachines(),
                "历史欠产为0时仍应进入小欠产口径，不能退回窗口需消化量强制补机");
        assertEquals(0, result.getTotalAddedMachineCount());
        assertEquals("8班窗口总产能", result.getDayDecisionList().get(0).getDecisionMode());
        assertFalse(result.getDayDecisionList().get(0).isShortageThresholdExceeded());
    }

    @Test
    void simulateExpansion_shouldUseRemainingWindowPlanQtyAfterFirstDay() {
        LocalDate day1 = LocalDate.of(2026, 5, 9);
        LocalDate day2 = LocalDate.of(2026, 5, 10);
        LocalDate day3 = LocalDate.of(2026, 5, 11);
        DailyMachineCapacitySimulationRequest request = new DailyMachineCapacitySimulationRequest();
        request.setMaterialCode("3302001592");
        request.setDailyPlanQuotaMap(quotaMap(day1, day2, day3, 31, 30, 40));
        request.setMachineDailyCapacityList(machineCapacityList(day1, day2, day3, 31, 30, 40, 2));
        request.setInitialActiveMachines(1);
        request.setShiftCapacity(10);
        request.setShortageLookAheadDays(2);
        request.setShortageAddMachineThreshold(150);
        request.setMonthlyHistoryShortageQty(100);
        request.setWindowEndDate(day3);
        request.setSceneType("newSpec");

        DailyMachineCapacitySimulationResult result =
                DailyMachineCapacitySimulationUtil.simulateExpansion(request);

        assertEquals(1, result.getFinalActiveMachines(),
                "T+1 应按剩余窗口总量判断，不能继续拿首窗总量触发补机");
        assertEquals("8班窗口总产能", result.getDayDecisionList().get(1).getDecisionMode());
        assertEquals(70, result.getDayDecisionList().get(1).getWindowPlanQty());
    }

    @Test
    void simulateExpansion_shouldAddMachineWhenNextDayThreeShiftCapacityIsInsufficient() {
        LocalDate day1 = LocalDate.of(2026, 5, 9);
        LocalDate day2 = LocalDate.of(2026, 5, 10);
        LocalDate day3 = LocalDate.of(2026, 5, 11);
        DailyMachineCapacitySimulationRequest request = new DailyMachineCapacitySimulationRequest();
        request.setMaterialCode("3302001592");
        Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap = quotaMap(day1, day2, day3, 48, 60, 60);
        quotaMap.get(day1).setRemainingQty(148);
        request.setDailyPlanQuotaMap(quotaMap);
        request.setMachineDailyCapacityList(machineCapacityList(day1, day2, day3, 48, 48, 48, 2));
        request.setInitialActiveMachines(1);
        request.setShiftCapacity(16);
        request.setShortageLookAheadDays(2);
        request.setShortageAddMachineThreshold(150);
        request.setMonthlyHistoryShortageQty(100);
        request.setWindowEndDate(day3);
        request.setSceneType("newSpec");

        DailyMachineCapacitySimulationResult result =
                DailyMachineCapacitySimulationUtil.simulateExpansion(request);

        assertEquals(2, result.getFinalActiveMachines(),
                "小欠产未超阈值且窗口8班总产能不足、后一天3班产能不足时，应增机台");
        assertEquals(1, result.getTotalAddedMachineCount());
        assertEquals(1, result.getDayDecisionList().get(0).getAddedMachineCount());
    }

    @Test
    void simulateExpansion_shouldAddMachineWhenTodayPlanExceedsTodayCapacityEvenNextDayCanCover() {
        LocalDate day1 = LocalDate.of(2026, 6, 1);
        LocalDate day2 = LocalDate.of(2026, 6, 2);
        LocalDate day3 = LocalDate.of(2026, 6, 3);
        DailyMachineCapacitySimulationRequest request = new DailyMachineCapacitySimulationRequest();
        request.setMaterialCode("3302001512");
        request.setDailyPlanQuotaMap(quotaMap(day1, day2, day3, 100, 50, 44));
        request.setMachineDailyCapacityList(machineCapacityList(day1, day2, day3, 0, 17, 51, 2));
        request.setInitialActiveMachines(1);
        request.setShiftCapacity(17);
        request.setShortageLookAheadDays(2);
        request.setShortageAddMachineThreshold(200);
        request.setMonthlyHistoryShortageQty(0);
        request.setWindowEndDate(day3);
        request.setSceneType("newSpec");

        DailyMachineCapacitySimulationResult result =
                DailyMachineCapacitySimulationUtil.simulateExpansion(request);

        assertEquals(2, result.getFinalActiveMachines(),
                "窗口8班理论产能不足且当前日计划大于当前日有效产能时，即使后一天3班理论产能满足也应增机台");
        assertEquals(1, result.getTotalAddedMachineCount());
        assertEquals(1, result.getDayDecisionList().get(0).getAddedMachineCount());
    }

    @Test
    void simulateExpansion_shouldSwitchToWindowDemandWhenRollingShortageExceedsThreshold() {
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
        request.setShiftCapacity(16);
        request.setShortageLookAheadDays(2);
        request.setShortageAddMachineThreshold(150);
        request.setMonthlyHistoryShortageQty(150);
        request.setWindowEndDate(day3);
        request.setSceneType("newSpec");

        DailyMachineCapacitySimulationResult result =
                DailyMachineCapacitySimulationUtil.simulateExpansion(request);

        assertEquals(1, result.getFinalActiveMachines(),
                "本月前日累计欠产未超过阈值时，不应因T+1滚动欠产超过阈值切换强制增机台");
        assertEquals(0, result.getTotalAddedMachineCount());
        assertFalse(result.getDayDecisionList().get(1).isShortageThresholdExceeded());
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
