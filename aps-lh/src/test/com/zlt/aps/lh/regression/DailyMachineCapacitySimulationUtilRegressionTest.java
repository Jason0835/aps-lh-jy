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
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void simulateExpansion_shouldStopWhenCurrentDayPlanAndRemainingQtyAreCovered() {
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
        request.setShiftCapacity(10);
        request.setShortageLookAheadDays(1);
        request.setWindowEndDate(day3);
        request.setSceneType("newSpec");

        DailyMachineCapacitySimulationResult result =
                DailyMachineCapacitySimulationUtil.simulateExpansion(request);

        assertEquals(1, result.getFinalActiveMachines(), "当前日计划和账本余量已覆盖时不应继续后看");
        assertEquals(0, result.getTotalAddedMachineCount());
        assertEquals(1, result.getDayDecisionList().size());
        assertTrue(result.getDayDecisionList().get(0).isCurrentDayPlanSatisfied());
        assertFalse(result.getDayDecisionList().get(0).isNextDayLookAheadEntered());
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
    void simulateExpansion_shouldAddMachineWhenRolledDailyRhythmRequiresIt() {
        LocalDate day1 = LocalDate.of(2026, 5, 9);
        LocalDate day2 = LocalDate.of(2026, 5, 10);
        LocalDate day3 = LocalDate.of(2026, 5, 11);
        DailyMachineCapacitySimulationRequest request = new DailyMachineCapacitySimulationRequest();
        request.setMaterialCode("3302001592");
        request.setDailyPlanQuotaMap(quotaMap(day1, day2, day3, 60, 60, 0));
        request.setMachineDailyCapacityList(machineCapacityList(day1, day2, day3, 48, 48, 48, 2));
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
                "滚动到60/60业务日后，当前日和下一日均超过单日理论产能时应增机台");
        assertEquals(1, result.getTotalAddedMachineCount());
        assertEquals(1, result.getDayDecisionList().get(0).getAddedMachineCount());
    }

    @Test
    void simulateExpansion_shouldNotLookAheadWhenCurrentDayPlanSatisfied() {
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

        assertEquals(1, result.getFinalActiveMachines(),
                "当前日计划已满足时，应立即停止增机判断，不再后看后续计划");
        assertEquals(0, result.getTotalAddedMachineCount());
        assertEquals(1, result.getDayDecisionList().size());
        assertEquals(0, result.getDayDecisionList().get(0).getAddedMachineCount());
        assertEquals("当前日计划已满足", result.getDayDecisionList().get(0).getDecisionMode());
        assertFalse(result.getDayDecisionList().get(0).isNextDayLookAheadEntered());
    }

    @Test
    void simulateExpansion_shouldStopAtCurrentDayWhenPlanSatisfiedAndHistoryShortageIsZero() {
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
                "当前日计划已满足时本轮必须停止，后续较大计划由滚动后的业务日重新判断");
        assertEquals(0, result.getTotalAddedMachineCount());
        assertEquals(1, result.getDayDecisionList().size());
        assertEquals("当前日计划已满足", result.getDayDecisionList().get(0).getDecisionMode());
        assertFalse(result.getDayDecisionList().get(0).isShortageThresholdExceeded());
        assertFalse(result.getDayDecisionList().get(0).isNextDayLookAheadEntered());
    }

    @Test
    void simulateExpansion_shouldStopWhenSecondDayPlanSatisfied() {
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
                "T+1 当前日计划已满足时，应停止当日增机判断");
        assertEquals("当前日计划已满足", result.getDayDecisionList().get(1).getDecisionMode());
        assertEquals(70, result.getDayDecisionList().get(1).getWindowPlanQty());
        assertEquals(30, result.getDayDecisionList().get(1).getCurrentDayPlanQty());
        assertEquals(30, result.getDayDecisionList().get(1).getTodayCapacityQty());
        assertFalse(result.getDayDecisionList().get(1).isNextDayLookAheadEntered());
    }

    @Test
    void simulateExpansion_shouldKeepSingleMachineWhenDailyPlanEqualsDailyStandard() {
        LocalDate day1 = LocalDate.of(2026, 5, 9);
        LocalDate day2 = LocalDate.of(2026, 5, 10);
        LocalDate day3 = LocalDate.of(2026, 5, 11);
        DailyMachineCapacitySimulationRequest request = new DailyMachineCapacitySimulationRequest();
        request.setMaterialCode("3302002661");
        request.setDailyPlanQuotaMap(quotaMap(day1, day2, day3, 50, 50, 50));
        request.setMachineDailyCapacityList(machineCapacityList(day1, day2, day3, 50, 50, 50, 2));
        request.setInitialActiveMachines(1);
        request.setShiftCapacity(50);
        request.setShortageLookAheadDays(2);
        request.setShortageAddMachineThreshold(150);
        request.setMonthlyHistoryShortageQty(0);
        request.setWindowEndDate(day3);
        request.setSceneType("newSpec");

        DailyMachineCapacitySimulationResult result =
                DailyMachineCapacitySimulationUtil.simulateExpansion(request);

        assertEquals(1, result.getFinalActiveMachines(), "单台机台日理论产能等于dayN节奏时不得新增第二台");
        assertEquals(0, result.getTotalAddedMachineCount());
    }

    @Test
    void simulateExpansion_shouldUseDailyStandardForTDayEvenWhenActualShiftCapacityIsLower() {
        LocalDate day1 = LocalDate.of(2026, 7, 15);
        LocalDate day2 = LocalDate.of(2026, 7, 16);
        LocalDate day3 = LocalDate.of(2026, 7, 17);
        DailyMachineCapacitySimulationRequest request = new DailyMachineCapacitySimulationRequest();
        request.setMaterialCode("3302002177");
        request.setDailyPlanQuotaMap(quotaMap(day1, day2, day3, 50, 50, 50));
        request.setMachineDailyCapacityList(machineCapacityList(day1, day2, day3, 32, 48, 48, 2));
        request.setInitialActiveMachines(1);
        request.setShiftCapacity(16);
        request.setSingleMachineWindowCapacityQty(128);
        Map<LocalDate, Integer> dailyStandardCapacityMap = new LinkedHashMap<LocalDate, Integer>(4);
        dailyStandardCapacityMap.put(day1, 50);
        dailyStandardCapacityMap.put(day2, 50);
        dailyStandardCapacityMap.put(day3, 50);
        request.setSingleMachineDailyCapacityMap(dailyStandardCapacityMap);
        request.setScheduleDayFinishQty(12);
        request.setShortageLookAheadDays(1);
        request.setShortageAddMachineThreshold(100);
        request.setMonthlyHistoryShortageQty(0);
        request.setWindowEndDate(day3);
        request.setSceneType("newSpec");

        DailyMachineCapacitySimulationResult result =
                DailyMachineCapacitySimulationUtil.simulateExpansion(request);

        assertEquals(1, result.getFinalActiveMachines(),
                "T日加机判断必须按正式日硫化标准50，不能按实际残班产能32新增第二台");
        assertEquals(0, result.getTotalAddedMachineCount());
        assertEquals(50, result.getDayDecisionList().get(0).getCapacityQty());
        assertEquals("当前日计划已满足", result.getDayDecisionList().get(0).getDecisionMode());
    }

    @Test
    void simulateExpansion_shouldAddMachineWhenRolledCurrentAndNextDayPlanExceedDailyStandard() {
        LocalDate day1 = LocalDate.of(2026, 5, 9);
        LocalDate day2 = LocalDate.of(2026, 5, 10);
        LocalDate day3 = LocalDate.of(2026, 5, 11);
        DailyMachineCapacitySimulationRequest request = new DailyMachineCapacitySimulationRequest();
        request.setMaterialCode("3302001074");
        request.setDailyPlanQuotaMap(quotaMap(day1, day2, day3, 92, 92, 0));
        request.setMachineDailyCapacityList(machineCapacityList(day1, day2, day3, 48, 48, 48, 2));
        request.setInitialActiveMachines(1);
        request.setShiftCapacity(16);
        request.setShortageLookAheadDays(2);
        request.setShortageAddMachineThreshold(150);
        request.setMonthlyHistoryShortageQty(0);
        request.setWindowEndDate(day3);
        request.setSceneType("newSpec");

        DailyMachineCapacitySimulationResult result =
                DailyMachineCapacitySimulationUtil.simulateExpansion(request);

        assertEquals(2, result.getFinalActiveMachines(), "滚动后当前日和下一日均超过单台日理论产能时应新增第二台");
        assertEquals(1, result.getTotalAddedMachineCount());
        assertEquals(1, result.getDayDecisionList().get(0).getAddedMachineCount());
        assertEquals(day1, result.getDayDecisionList().get(0).getProductionDate());
    }

    @Test
    void simulateExpansion_shouldAddMachineWhenRolledCurrentAndNextDayCapacityInsufficient() {
        LocalDate day1 = LocalDate.of(2026, 5, 9);
        LocalDate day2 = LocalDate.of(2026, 5, 10);
        LocalDate day3 = LocalDate.of(2026, 5, 11);
        DailyMachineCapacitySimulationRequest request = new DailyMachineCapacitySimulationRequest();
        request.setMaterialCode("3302001592");
        request.setDailyPlanQuotaMap(quotaMap(day1, day2, day3, 60, 60, 0));
        request.setMachineDailyCapacityList(machineCapacityList(day1, day2, day3, 48, 48, 48, 2));
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
                "滚动到新业务日后，当前日和下一日均不足时应增机台");
        assertEquals(1, result.getTotalAddedMachineCount());
        assertEquals(1, result.getDayDecisionList().get(0).getAddedMachineCount());
    }

    @Test
    void simulateExpansion_shouldNotAddMachineWhenNextDayPlanCanCoverCurrentDayRhythm() {
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

        assertEquals(1, result.getFinalActiveMachines(),
                "当前日未满足但下一日计划已被当前机台理论日产能覆盖时，不应增加机台");
        assertEquals(0, result.getTotalAddedMachineCount());
        assertEquals(0, result.getDayDecisionList().get(0).getAddedMachineCount());
        assertEquals("后一天正式日硫化标准", result.getDayDecisionList().get(0).getDecisionMode());
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
        assertFalse(result.getDayDecisionList().get(0).isShortageThresholdExceeded());
    }

    @Test
    void simulateExpansion_shouldUseForcedWindowModeWhenEndingStructureLargeSurplus() {
        LocalDate day1 = LocalDate.of(2026, 6, 12);
        LocalDate day2 = LocalDate.of(2026, 6, 13);
        LocalDate day3 = LocalDate.of(2026, 6, 14);
        DailyMachineCapacitySimulationRequest request = new DailyMachineCapacitySimulationRequest();
        request.setMaterialCode("3302001592");
        request.setDailyPlanQuotaMap(quotaMap(day1, day2, day3, 0, 60, 60));
        request.setMachineDailyCapacityList(machineCapacityList(day1, day2, day3, 0, 40, 40, 2));
        request.setInitialActiveMachines(1);
        request.setShiftCapacity(20);
        request.setShortageLookAheadDays(2);
        request.setShortageAddMachineThreshold(100);
        request.setMonthlyHistoryShortageQty(120);
        request.setWindowEndDate(day3);
        request.setForceShortageWindowMode(true);
        request.setSceneType("newSpec");

        DailyMachineCapacitySimulationResult result =
                DailyMachineCapacitySimulationUtil.simulateExpansion(request);

        assertEquals("欠产阈值窗口回落", result.getDayDecisionList().get(0).getDecisionMode());
        assertTrue(result.getDayDecisionList().get(0).isShortageThresholdExceeded(),
                "结构收尾大余量触发强制模式时，即使欠产未超过阈值，也应按窗口后剩余欠产判断");
        assertEquals(2, result.getFinalActiveMachines());
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
