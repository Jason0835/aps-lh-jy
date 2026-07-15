package com.zlt.aps.lh.engine.strategy.support;

import com.zlt.aps.lh.api.domain.dto.SkuDailyPlanQuotaDTO;
import com.zlt.aps.lh.api.domain.dto.SkuScheduleDTO;
import com.zlt.aps.lh.api.constant.LhScheduleParamConstant;
import com.zlt.aps.lh.context.LhScheduleConfig;
import com.zlt.aps.lh.context.LhScheduleContext;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SKU提前生产准入判断回归测试。
 */
class EarlyProductionCheckerTest {

    @Test
    void checkEarlyProduction_shouldReturnStructureSwitchRemarkWithThreeDayMachineCounts() {
        LocalDate day1 = LocalDate.of(2026, 6, 12);
        LocalDate day2 = LocalDate.of(2026, 6, 13);
        LocalDate day3 = LocalDate.of(2026, 6, 14);
        LhScheduleContext context = contextWithStructurePlan(day1, "L1", 0);
        context.addStructurePlanMachineCount(day2, "L1", 2);
        context.addStructurePlanMachineCount(day3, "L1", 4);
        SkuScheduleDTO sku = sku("3302001001", "L1", 100, 40,
                quotaMap(day1, day2, day3, 0, 60, 0));

        EarlyProductionDecision decision = EarlyProductionChecker.checkEarlyProduction(
                context, sku, day1, day1, day3, 200);

        assertTrue(decision.isAllowed());
        assertTrue(decision.isEarlyProduction());
        assertEquals(EarlyProductionDecision.SCENE_STRUCTURE_SWITCH, decision.getSceneType());
        assertEquals(Arrays.asList(0, 2, 4), decision.getStructurePlanMachineCounts());
        assertEquals("[结构切换] 结构计划硫化机台数：0,2,4", decision.buildRemark());
    }

    @Test
    void checkEarlyProduction_shouldReturnStructureEndingRemarkWhenLargeSurplusAllowed() {
        LocalDate day1 = LocalDate.of(2026, 6, 12);
        LocalDate day2 = LocalDate.of(2026, 6, 13);
        LocalDate day3 = LocalDate.of(2026, 6, 14);
        LhScheduleContext context = contextWithStructurePlan(day1, "L1", 0);
        context.recordScheduledMachine(day1, "L1", "3302001001", null, "K1101");
        SkuScheduleDTO sku = sku("3302001001", "L1", 90, 40,
                quotaMap(day1, day2, day3, 0, 60, 0));

        EarlyProductionDecision decision = EarlyProductionChecker.checkEarlyProduction(
                context, sku, day1, day1, day3, 200);

        assertTrue(decision.isAllowed());
        assertEquals(EarlyProductionDecision.SCENE_STRUCTURE_ENDING, decision.getSceneType());
        assertEquals("[结构收尾] 结构计划硫化机台数：0,0,0", decision.buildRemark());
    }

    @Test
    void checkEarlyProduction_shouldUseNormalSceneWhenShortageExceedsThreshold() {
        LocalDate day1 = LocalDate.of(2026, 6, 12);
        LocalDate day2 = LocalDate.of(2026, 6, 13);
        LocalDate day3 = LocalDate.of(2026, 6, 14);
        LhScheduleContext context = contextWithStructurePlan(day1, "L1", 0);
        context.addStructurePlanMachineCount(day2, "L1", 2);
        context.addStructurePlanMachineCount(day3, "L1", 4);
        SkuScheduleDTO sku = sku("3302001001", "L1", 250, 40,
                quotaMap(day1, day2, day3, 0, 60, 0));

        EarlyProductionDecision decision = EarlyProductionChecker.checkEarlyProduction(
                context, sku, day1, day1, day3, 200);

        assertTrue(decision.isAllowed());
        assertEquals(EarlyProductionDecision.SCENE_NORMAL, decision.getSceneType());
        assertEquals("结构计划硫化机台数：0,2,4", decision.buildRemark());
    }

    @Test
    void checkEarlyProduction_shouldNotBuildRemarkWhenCurrentDayHasPlan() {
        LocalDate day1 = LocalDate.of(2026, 6, 12);
        LocalDate day2 = LocalDate.of(2026, 6, 13);
        LocalDate day3 = LocalDate.of(2026, 6, 14);
        LhScheduleContext context = contextWithStructurePlan(day1, "L1", 1);
        SkuScheduleDTO sku = sku("3302001001", "L1", 0, 40,
                quotaMap(day1, day2, day3, 20, 60, 0));

        EarlyProductionDecision decision = EarlyProductionChecker.checkEarlyProduction(
                context, sku, day1, day1, day3, 200);

        assertTrue(decision.isAllowed());
        assertFalse(decision.isEarlyProduction());
        assertEquals("", decision.buildRemark());
    }

    @Test
    void canEnterEarlyProductionCheck_shouldAllowFuturePlanSkuWhenStructureMachineNotFull() {
        LocalDate day1 = LocalDate.of(2026, 6, 12);
        LocalDate day2 = LocalDate.of(2026, 6, 13);
        LocalDate day3 = LocalDate.of(2026, 6, 14);
        LhScheduleContext context = contextWithStructurePlan(day1, "L1", 2);
        context.recordScheduledMachine(day1, "L1", "3302001001", null, "K1101");
        SkuScheduleDTO sku = sku("3302001001", "L1", 100, 40,
                quotaMap(day1, day2, day3, 0, 60, 0));

        boolean allowed = EarlyProductionChecker.canEnterEarlyProductionCheck(
                context, sku, day1, day3, 200);

        assertTrue(allowed, "结构已排机台数小于计划硫化机台数时，应允许后续日SKU提前进入新增判断");
    }

    @Test
    void canEnterEarlyProductionCheck_shouldAllowSecondFutureDayWhenDefaultThresholdIsTwo() {
        LocalDate day1 = LocalDate.of(2026, 6, 12);
        LocalDate day2 = LocalDate.of(2026, 6, 13);
        LocalDate day3 = LocalDate.of(2026, 6, 14);
        LhScheduleContext context = contextWithStructurePlan(day1, "L1", 2);
        context.recordScheduledMachine(day1, "L1", "3302001001", null, "K1101");
        SkuScheduleDTO sku = sku("3302001001", "L1", 100, 40,
                quotaMap(day1, day2, day3, 0, 0, 60));

        boolean allowed = EarlyProductionChecker.canEnterEarlyProductionCheck(
                context, sku, day1, day3, 200);

        assertTrue(allowed, "默认提前生产阈值为2天，T日应允许判断T+2日计划量");
    }

    @Test
    void canEnterEarlyProductionCheck_shouldRejectPlanBeyondDefaultThresholdTwo() {
        LocalDate day1 = LocalDate.of(2026, 6, 12);
        LocalDate day2 = LocalDate.of(2026, 6, 13);
        LocalDate day3 = LocalDate.of(2026, 6, 14);
        LocalDate day4 = LocalDate.of(2026, 6, 15);
        LhScheduleContext context = contextWithStructurePlan(day1, "L1", 2);
        context.recordScheduledMachine(day1, "L1", "3302001001", null, "K1101");
        SkuScheduleDTO sku = sku("3302001001", "L1", 100, 40,
                quotaMap(new LocalDate[]{day1, day2, day3, day4}, new int[]{0, 0, 0, 60}));

        boolean allowed = EarlyProductionChecker.canEnterEarlyProductionCheck(
                context, sku, day1, day4, 200);

        assertFalse(allowed, "默认提前生产阈值为2天，T日不能提前消耗T+3日计划量");
    }

    @Test
    void canEnterEarlyProductionCheck_shouldAllowThirdFutureDayWhenThresholdIsThree() {
        LocalDate day1 = LocalDate.of(2026, 6, 12);
        LocalDate day2 = LocalDate.of(2026, 6, 13);
        LocalDate day3 = LocalDate.of(2026, 6, 14);
        LocalDate day4 = LocalDate.of(2026, 6, 15);
        LhScheduleContext context = contextWithStructurePlan(day1, "L1", 2);
        context.setScheduleConfig(scheduleConfigWithEarlyProductionDays(3));
        context.recordScheduledMachine(day1, "L1", "3302001001", null, "K1101");
        SkuScheduleDTO sku = sku("3302001001", "L1", 100, 40,
                quotaMap(new LocalDate[]{day1, day2, day3, day4}, new int[]{0, 0, 0, 60}));

        EarlyProductionDecision decision = EarlyProductionChecker.checkEarlyProduction(
                context, sku, day1, day1, day4, 200);

        assertTrue(decision.isAllowed(), "提前生产阈值为3天时，T日应允许判断T+3日计划量");
        assertEquals(day4, decision.getFuturePlanDate());
    }

    @Test
    void canEnterEarlyProductionCheck_shouldRejectFuturePlanSkuWhenStructureMachineReachedPlan() {
        LocalDate day1 = LocalDate.of(2026, 6, 12);
        LocalDate day2 = LocalDate.of(2026, 6, 13);
        LocalDate day3 = LocalDate.of(2026, 6, 14);
        LhScheduleContext context = contextWithStructurePlan(day1, "L1", 1);
        context.recordScheduledMachine(day1, "L1", "3302001001", null, "K1101");
        SkuScheduleDTO sku = sku("3302001001", "L1", 100, 40,
                quotaMap(day1, day2, day3, 0, 60, 0));

        boolean allowed = EarlyProductionChecker.canEnterEarlyProductionCheck(
                context, sku, day1, day3, 200);

        assertFalse(allowed, "结构已排机台数达到计划机台数时，不应提前生产后续日SKU");
    }

    @Test
    void canEnterEarlyProductionCheck_shouldUseFutureStructurePlanWhenCurrentStructurePlanIsZero() {
        LocalDate day1 = LocalDate.of(2026, 6, 12);
        LocalDate day2 = LocalDate.of(2026, 6, 13);
        LocalDate day3 = LocalDate.of(2026, 6, 14);
        LhScheduleContext context = contextWithStructurePlan(day1, "L1", 0);
        context.addStructurePlanMachineCount(day2, "L1", 2);
        context.recordScheduledMachine(day1, "L1", "3302001001", null, "K1101");
        SkuScheduleDTO sku = sku("3302001001", "L1", 100, 40,
                quotaMap(day1, day2, day3, 0, 60, 0));

        boolean allowed = EarlyProductionChecker.canEnterEarlyProductionCheck(
                context, sku, day1, day3, 200);

        assertTrue(allowed, "当前日结构计划为0时，应改用后续第一个计划日的结构计划机台数判断");
    }

    @Test
    void canEnterEarlyProductionCheck_shouldNotUseThirdDayStructurePlanWhenNextDayStructurePlanIsZero() {
        LocalDate day1 = LocalDate.of(2026, 6, 12);
        LocalDate day2 = LocalDate.of(2026, 6, 13);
        LocalDate day3 = LocalDate.of(2026, 6, 14);
        LhScheduleContext context = contextWithStructurePlan(day1, "L1", 0);
        context.addStructurePlanMachineCount(day2, "L1", 0);
        context.addStructurePlanMachineCount(day3, "L1", 2);
        SkuScheduleDTO sku = sku("3302001001", "L1", 0, 40,
                quotaMap(day1, day2, day3, 0, 60, 0));

        boolean allowed = EarlyProductionChecker.canEnterEarlyProductionCheck(
                context, sku, day1, day3, 200);

        assertFalse(allowed, "结构切换只允许取下一天结构计划，不能继续取T+2结构计划机台数");
    }

    @Test
    void canEnterEarlyProductionCheck_shouldAllowEndingStructureWhenHistoryShortageExceedsSkuDailyCapacity() {
        LocalDate day1 = LocalDate.of(2026, 6, 12);
        LocalDate day2 = LocalDate.of(2026, 6, 13);
        LocalDate day3 = LocalDate.of(2026, 6, 14);
        LhScheduleContext context = contextWithStructurePlan(day1, "L1", 0);
        context.addStructurePlanMachineCount(day2, "L1", 0);
        context.recordScheduledMachine(day1, "L1", "3302001001", null, "K1101");
        SkuScheduleDTO sku = sku("3302001001", "L1", 90, 40,
                quotaMap(day1, day2, day3, 0, 60, 0));

        boolean allowed = EarlyProductionChecker.canEnterEarlyProductionCheck(
                context, sku, day1, day3, 200);

        assertTrue(allowed, "结构已收尾且SKU余量大于已排机台日硫化量时，应允许进入强制加机台判断");
    }

    @Test
    void canEnterEarlyProductionCheck_shouldKeepOriginalLogicWhenCurrentDayHasPlanOrShortageExceeded() {
        LocalDate day1 = LocalDate.of(2026, 6, 12);
        LocalDate day2 = LocalDate.of(2026, 6, 13);
        LocalDate day3 = LocalDate.of(2026, 6, 14);
        LhScheduleContext context = contextWithStructurePlan(day1, "L1", 0);
        SkuScheduleDTO currentPlanSku = sku("3302001001", "L1", 0, 40,
                quotaMap(day1, day2, day3, 20, 60, 0));
        SkuScheduleDTO shortageExceededSku = sku("3302001002", "L1", 250, 40,
                quotaMap(day1, day2, day3, 0, 60, 0));

        assertTrue(EarlyProductionChecker.canEnterEarlyProductionCheck(
                context, currentPlanSku, day1, day3, 200), "当前日已有计划量时应直接走原逻辑");
        assertTrue(EarlyProductionChecker.canEnterEarlyProductionCheck(
                context, shortageExceededSku, day1, day3, 200), "欠产超过阈值时应直接走原强制加机台逻辑");
    }

    @Test
    void isEndingStructureLargeSurplus_shouldRejectWhenFuturePlanMachineExists() {
        LocalDate day1 = LocalDate.of(2026, 6, 12);
        LocalDate day2 = LocalDate.of(2026, 6, 13);
        LocalDate day3 = LocalDate.of(2026, 6, 14);
        LhScheduleContext context = contextWithStructurePlan(day1, "L1", 0);
        context.addStructurePlanMachineCount(day2, "L1", 2);
        context.recordScheduledMachine(day1, "L1", "3302001001", null, "K1101");
        SkuScheduleDTO sku = sku("3302001001", "L1", 90, 40,
                quotaMap(day1, day2, day3, 0, 60, 0));

        boolean endingLargeSurplus = EarlyProductionChecker.isEndingStructureLargeSurplus(
                context, sku, day1, day2);

        assertFalse(endingLargeSurplus, "后续首个计划日仍有结构计划机台时，不应误判为结构已收尾");
    }

    @Test
    void isEndingStructureLargeSurplus_shouldAvoidIntegerOverflow() {
        LocalDate day1 = LocalDate.of(2026, 6, 12);
        LocalDate day2 = LocalDate.of(2026, 6, 13);
        LocalDate day3 = LocalDate.of(2026, 6, 14);
        LhScheduleContext context = contextWithStructurePlan(day1, "L1", 0);
        context.addStructurePlanMachineCount(day2, "L1", 0);
        context.recordScheduledMachine(day1, "L1", "3302001001", null, "K1101");
        context.recordScheduledMachine(day1, "L1", "3302001001", null, "K1102");
        SkuScheduleDTO sku = sku("3302001001", "L1", Integer.MAX_VALUE, Integer.MAX_VALUE,
                quotaMap(day1, day2, day3, 0, 60, 0));

        boolean endingLargeSurplus = EarlyProductionChecker.isEndingStructureLargeSurplus(
                context, sku, day1, day2);

        assertFalse(endingLargeSurplus, "已排机台日产乘积超过int范围时，不应因溢出误判为大余量");
    }

    private LhScheduleContext contextWithStructurePlan(LocalDate date, String structureName, int machineCount) {
        LhScheduleContext context = new LhScheduleContext();
        context.addStructurePlanMachineCount(date, structureName, machineCount);
        return context;
    }

    private LhScheduleConfig scheduleConfigWithEarlyProductionDays(int threshold) {
        Map<String, String> paramMap = new HashMap<String, String>(4);
        paramMap.put(LhScheduleParamConstant.EARLY_PRODUCTION_DAYS_THRESHOLD, String.valueOf(threshold));
        return new LhScheduleConfig(paramMap);
    }

    private SkuScheduleDTO sku(String materialCode,
                               String structureName,
                               int historyShortageQty,
                               int dailyCapacity,
                               Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap) {
        SkuScheduleDTO sku = new SkuScheduleDTO();
        sku.setMaterialCode(materialCode);
        sku.setStructureName(structureName);
        sku.setMonthlyHistoryShortageQty(historyShortageQty);
        sku.setDailyCapacity(dailyCapacity);
        sku.setDailyPlanQuotaMap(quotaMap);
        return sku;
    }

    private Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap(LocalDate day1,
                                                          LocalDate day2,
                                                          LocalDate day3,
                                                          int day1Qty,
                                                          int day2Qty,
                                                          int day3Qty) {
        Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap = new LinkedHashMap<LocalDate, SkuDailyPlanQuotaDTO>(4);
        for (LocalDate date : Arrays.asList(day1, day2, day3)) {
            quotaMap.put(date, quota(date, 0));
        }
        quotaMap.get(day1).setDayPlanQty(day1Qty);
        quotaMap.get(day1).setRemainingQty(day1Qty);
        quotaMap.get(day2).setDayPlanQty(day2Qty);
        quotaMap.get(day2).setRemainingQty(day2Qty);
        quotaMap.get(day3).setDayPlanQty(day3Qty);
        quotaMap.get(day3).setRemainingQty(day3Qty);
        return quotaMap;
    }

    private Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap(LocalDate[] dates, int[] dayPlanQtyArray) {
        Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap =
                new LinkedHashMap<LocalDate, SkuDailyPlanQuotaDTO>(dates.length);
        for (int index = 0; index < dates.length; index++) {
            LocalDate date = dates[index];
            int dayPlanQty = dayPlanQtyArray[index];
            quotaMap.put(date, quota(date, dayPlanQty));
        }
        return quotaMap;
    }

    private SkuDailyPlanQuotaDTO quota(LocalDate date, int qty) {
        SkuDailyPlanQuotaDTO quota = new SkuDailyPlanQuotaDTO();
        quota.setProductionDate(date);
        quota.setDayPlanQty(qty);
        quota.setRemainingQty(qty);
        return quota;
    }
}
