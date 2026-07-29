package com.zlt.aps.lh.regression;

import com.zlt.aps.lh.api.domain.dto.SkuDailyPlanQuotaDTO;
import com.zlt.aps.lh.util.SkuDailyPlanQuotaUtil;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

/**
 * 日计划额度账本滚动补欠产回归。
 */
class SkuDailyPlanQuotaUtilRegressionTest {

    @Test
    void consumeRollingQuota_shouldCarryLossAndBorrowFutureWithinWindowTotal() {
        LocalDate day1 = LocalDate.of(2026, 5, 3);
        LocalDate day2 = LocalDate.of(2026, 5, 4);
        LocalDate day3 = LocalDate.of(2026, 5, 5);
        Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap = new LinkedHashMap<>(4);
        quotaMap.put(day1, quota("3302001724", day1, 96));
        quotaMap.put(day2, quota("3302001724", day2, 48));
        quotaMap.put(day3, quota("3302001724", day3, 14));

        int firstDayActualQty = SkuDailyPlanQuotaUtil.consumeRollingQuota(quotaMap, day1, 32);
        int secondDayActualQty = SkuDailyPlanQuotaUtil.consumeRollingQuota(quotaMap, day2, 96);
        int thirdDayActualQty = SkuDailyPlanQuotaUtil.consumeRollingQuota(quotaMap, day3, 30);

        assertEquals(32, firstDayActualQty);
        assertEquals(96, secondDayActualQty);
        assertEquals(30, thirdDayActualQty);
        assertEquals(158, quotaMap.values().stream().mapToInt(SkuDailyPlanQuotaDTO::getScheduledQty).sum());
        assertEquals(0, SkuDailyPlanQuotaUtil.sumRemainingQty(quotaMap));
        assertEquals(0, quotaMap.get(day3).getFinalLossQty());
    }

    @Test
    void consumeRollingQuota_shouldRecordFutureBorrowOnCurrentDay() {
        LocalDate day1 = LocalDate.of(2026, 5, 3);
        LocalDate day2 = LocalDate.of(2026, 5, 4);
        Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap = new LinkedHashMap<>(4);
        quotaMap.put(day1, quota("3302001724", day1, 40));
        quotaMap.put(day2, quota("3302001724", day2, 20));

        int consumedQty = SkuDailyPlanQuotaUtil.consumeRollingQuota(quotaMap, day1, 50);

        assertEquals(50, consumedQty);
        assertEquals(40, quotaMap.get(day1).getScheduledQty());
        assertEquals(10, quotaMap.get(day1).getFutureBorrowQty());
        assertEquals(10, quotaMap.get(day2).getScheduledQty());
        assertEquals(10, quotaMap.get(day2).getRemainingQty());
        assertEquals(0, quotaMap.get(day1).getFinalLossQty());
        assertEquals(10, quotaMap.get(day2).getFinalLossQty());
    }

    @Test
    void consumeRollingQuota_shouldRecordActualQtyByConsumedQty() {
        LocalDate day1 = LocalDate.of(2026, 5, 3);
        LocalDate day2 = LocalDate.of(2026, 5, 4);
        Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap = new LinkedHashMap<>(4);
        quotaMap.put(day1, quota("3302001724", day1, 20));
        quotaMap.put(day2, quota("3302001724", day2, 20));

        int consumedQty = SkuDailyPlanQuotaUtil.consumeRollingQuota(quotaMap, day1, 50);

        assertEquals(40, consumedQty);
        assertEquals(40, quotaMap.get(day1).getActualQty(), "actualQty 应记录窗口内实际消费到的量，而不是原始申请量");
        assertEquals(0, quotaMap.get(day2).getRemainingQty());
    }

    @Test
    void consumeRollingQuota_shouldNotBorrowBeyondLookAheadEndDate() {
        LocalDate day1 = LocalDate.of(2026, 5, 3);
        LocalDate day2 = LocalDate.of(2026, 5, 4);
        LocalDate day3 = LocalDate.of(2026, 5, 5);
        Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap = new LinkedHashMap<>(4);
        quotaMap.put(day1, quota("3302001724", day1, 20));
        quotaMap.put(day2, quota("3302001724", day2, 20));
        quotaMap.put(day3, quota("3302001724", day3, 20));

        int consumedQty = SkuDailyPlanQuotaUtil.consumeRollingQuota(quotaMap, day1, 50, day2);

        assertEquals(40, consumedQty, "受限消费只能消耗 day1 和追补截止日 day2 以内的额度");
        assertEquals(20, quotaMap.get(day1).getScheduledQty());
        assertEquals(20, quotaMap.get(day1).getFutureBorrowQty());
        assertEquals(20, quotaMap.get(day2).getScheduledQty());
        assertEquals(20, quotaMap.get(day3).getRemainingQty(), "day3 超出追补截止日，不允许被提前借用");
        assertEquals(20, quotaMap.get(day3).getFinalLossQty());
    }

    /**
     * 跨日回滚必须撤销当前生产日最后借用的未来额度，不能从无关日期任意退量。
     */
    @Test
    void restoreRollingQuota_shouldReverseExactProductionDayConsumption() {
        LocalDate day1 = LocalDate.of(2026, 5, 3);
        LocalDate day2 = LocalDate.of(2026, 5, 4);
        LocalDate day3 = LocalDate.of(2026, 5, 5);
        Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap = new LinkedHashMap<>(4);
        quotaMap.put(day1, quota("3302001724", day1, 20));
        quotaMap.put(day2, quota("3302001724", day2, 20));
        quotaMap.put(day3, quota("3302001724", day3, 20));
        SkuDailyPlanQuotaUtil.consumeRollingQuota(quotaMap, day1, 35, day2);
        SkuDailyPlanQuotaUtil.consumeRollingQuota(quotaMap, day3, 15, day3);

        int restoredQty = SkuDailyPlanQuotaUtil.restoreRollingQuota(
                quotaMap, day1, 15, day2);

        assertEquals(15, restoredQty);
        assertEquals(20, quotaMap.get(day1).getScheduledQty(),
                "day1自身已先消费的额度必须保留");
        assertEquals(5, quotaMap.get(day2).getScheduledQty(),
                "day3后续生产已补用的5条历史额度必须保留，仅撤销day1借用的15条");
        assertEquals(10, quotaMap.get(day3).getScheduledQty(),
                "其他生产日实际采用的day3额度不得被本次回滚改动");
        assertEquals(20, quotaMap.get(day1).getActualQty(),
                "实际产量只从触发回滚的生产日扣减");
        assertEquals(15, quotaMap.get(day3).getActualQty());
    }

    @Test
    void buildShiftedEarlyProductionQuotaMap_shouldMoveNextDayPlansWithoutMutatingSource() {
        LocalDate day1 = LocalDate.of(2026, 6, 14);
        LocalDate day2 = LocalDate.of(2026, 6, 15);
        LocalDate day3 = LocalDate.of(2026, 6, 16);
        LocalDate day4 = LocalDate.of(2026, 6, 17);
        Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap = new LinkedHashMap<>(4);
        quotaMap.put(day1, quota("3302001724", day1, 0));
        quotaMap.put(day2, quota("3302001724", day2, 46));
        quotaMap.put(day3, quota("3302001724", day3, 46));
        quotaMap.put(day4, quota("3302001724", day4, 46));

        Map<LocalDate, SkuDailyPlanQuotaDTO> shiftedQuotaMap =
                SkuDailyPlanQuotaUtil.buildShiftedEarlyProductionQuotaMap(quotaMap, day1, day3);

        assertEquals(46, shiftedQuotaMap.get(day1).getDayPlanQty());
        assertEquals(46, shiftedQuotaMap.get(day2).getDayPlanQty());
        assertEquals(46, shiftedQuotaMap.get(day3).getDayPlanQty());
        assertEquals(0, quotaMap.get(day1).getDayPlanQty(), "原始日计划账本不能被提前生产视图污染");
        assertNotSame(quotaMap.get(day1), shiftedQuotaMap.get(day1), "前移视图必须克隆日计划对象");
    }

    @Test
    void buildShiftedEarlyProductionQuotaMap_shouldUseZeroWhenSourceNextDayMissing() {
        LocalDate day1 = LocalDate.of(2026, 6, 14);
        LocalDate day2 = LocalDate.of(2026, 6, 15);
        LocalDate day3 = LocalDate.of(2026, 6, 16);
        Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap = new LinkedHashMap<>(4);
        quotaMap.put(day1, quota("3302001724", day1, 0));
        quotaMap.put(day2, quota("3302001724", day2, 46));
        quotaMap.put(day3, quota("3302001724", day3, 46));

        Map<LocalDate, SkuDailyPlanQuotaDTO> shiftedQuotaMap =
                SkuDailyPlanQuotaUtil.buildShiftedEarlyProductionQuotaMap(quotaMap, day1, day3);

        assertEquals(46, shiftedQuotaMap.get(day1).getDayPlanQty());
        assertEquals(46, shiftedQuotaMap.get(day2).getDayPlanQty());
        assertEquals(0, shiftedQuotaMap.get(day3).getDayPlanQty(), "缺少T+3原始计划时，T+2临时计划按0处理");
    }

    @Test
    void buildShiftedEarlyProductionQuotaMap_shouldMovePlanByFuturePlanDateGap() {
        LocalDate day1 = LocalDate.of(2026, 6, 14);
        LocalDate day2 = LocalDate.of(2026, 6, 15);
        LocalDate day3 = LocalDate.of(2026, 6, 16);
        LocalDate day4 = LocalDate.of(2026, 6, 17);
        LocalDate day5 = LocalDate.of(2026, 6, 18);
        Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap = new LinkedHashMap<>(8);
        quotaMap.put(day1, quota("3302001724", day1, 0));
        quotaMap.put(day2, quota("3302001724", day2, 0));
        quotaMap.put(day3, quota("3302001724", day3, 0));
        quotaMap.put(day4, quota("3302001724", day4, 46));
        quotaMap.put(day5, quota("3302001724", day5, 50));

        Map<LocalDate, SkuDailyPlanQuotaDTO> shiftedQuotaMap =
                SkuDailyPlanQuotaUtil.buildShiftedEarlyProductionQuotaMap(quotaMap, day1, day3, day4);

        assertEquals(46, shiftedQuotaMap.get(day1).getDayPlanQty(), "T+3计划应前移到T日参与节奏判断");
        assertEquals(50, shiftedQuotaMap.get(day2).getDayPlanQty(), "T+4计划应随同前移到T+1日参与节奏判断");
        assertEquals(0, shiftedQuotaMap.get(day3).getDayPlanQty(), "缺少T+5计划时T+2临时计划按0处理");
        assertEquals(0, quotaMap.get(day1).getDayPlanQty(), "动态前移不能污染原始账本");
        assertNotSame(quotaMap.get(day4), shiftedQuotaMap.get(day1), "动态前移视图必须克隆来源对象");
    }

    /**
     * 提前两天时，T～T+2 必须分别读取原始 T+2～T+4，且跨月日期仍按真实日期映射。
     */
    @Test
    void buildShiftedEarlyProductionQuotaMap_shouldShiftTwoDaysAcrossMonthBoundary() {
        LocalDate day1 = LocalDate.of(2026, 7, 31);
        LocalDate day2 = LocalDate.of(2026, 8, 1);
        LocalDate day3 = LocalDate.of(2026, 8, 2);
        LocalDate day4 = LocalDate.of(2026, 8, 3);
        LocalDate day5 = LocalDate.of(2026, 8, 4);
        Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap = new LinkedHashMap<LocalDate, SkuDailyPlanQuotaDTO>(8);
        quotaMap.put(day1, quota("3302001724", day1, 0));
        quotaMap.put(day2, quota("3302001724", day2, 0));
        quotaMap.put(day3, quota("3302001724", day3, 46));
        quotaMap.put(day4, quota("3302001724", day4, 50));
        quotaMap.put(day5, quota("3302001724", day5, 54));

        Map<LocalDate, SkuDailyPlanQuotaDTO> shiftedQuotaMap =
                SkuDailyPlanQuotaUtil.buildShiftedEarlyProductionQuotaMap(
                        quotaMap, day1, day3, day3);

        assertEquals(46, shiftedQuotaMap.get(day1).getDayPlanQty());
        assertEquals(50, shiftedQuotaMap.get(day2).getDayPlanQty());
        assertEquals(54, shiftedQuotaMap.get(day3).getDayPlanQty());
        assertEquals(0, quotaMap.get(day1).getDayPlanQty(),
                "跨月前移不得修改原始七月日计划");
        assertEquals(46, quotaMap.get(day3).getDayPlanQty(),
                "跨月前移不得修改原始八月日计划");
    }

    private SkuDailyPlanQuotaDTO quota(String materialCode, LocalDate productionDate, int dayPlanQty) {
        SkuDailyPlanQuotaDTO quota = new SkuDailyPlanQuotaDTO();
        quota.setMaterialCode(materialCode);
        quota.setProductionDate(productionDate);
        quota.setDayPlanQty(dayPlanQty);
        quota.setRemainingQty(dayPlanQty);
        return quota;
    }
}
