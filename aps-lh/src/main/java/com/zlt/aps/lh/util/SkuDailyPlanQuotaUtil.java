package com.zlt.aps.lh.util;

import com.zlt.aps.lh.api.domain.dto.SkuDailyPlanQuotaDTO;
import org.springframework.util.CollectionUtils;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * SKU 日计划额度账本工具类。
 * <p>统一处理滚动补欠产、未来计划预占和窗口总量封顶，避免续作、新增排产各自消费日额度。</p>
 *
 * @author APS
 */
public final class SkuDailyPlanQuotaUtil {

    private SkuDailyPlanQuotaUtil() {
    }

    /**
     * 汇总当前窗口剩余日计划额度。
     *
     * @param quotaMap 日计划额度账本
     * @return 剩余额度汇总
     */
    public static int sumRemainingQty(Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap) {
        if (CollectionUtils.isEmpty(quotaMap)) {
            return 0;
        }
        int totalQty = 0;
        for (SkuDailyPlanQuotaDTO quota : quotaMap.values()) {
            if (Objects.isNull(quota)) {
                continue;
            }
            totalQty += Math.max(0, quota.getRemainingQty());
        }
        return Math.max(0, totalQty);
    }

    /**
     * 将日计划账本剩余额度压回窗口可排上限内。
     * <p>欠产补产优先保留靠前日期额度，超过窗口上限的部分从后续日期向前扣减。</p>
     *
     * @param quotaMap 日计划额度账本
     * @param windowRemainingLimit 窗口剩余可排上限
     */
    public static void capRemainingQtyByWindowLimit(Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap,
                                                    int windowRemainingLimit) {
        if (CollectionUtils.isEmpty(quotaMap)) {
            return;
        }
        int limitQty = Math.max(0, windowRemainingLimit);
        int totalRemainingQty = sumRemainingQty(quotaMap);
        int overflowQty = totalRemainingQty - limitQty;
        if (overflowQty <= 0) {
            refreshRollingFields(quotaMap);
            return;
        }
        List<SkuDailyPlanQuotaDTO> quotaList = new ArrayList<>(quotaMap.values());
        Collections.reverse(quotaList);
        int remainingOverflowQty = overflowQty;
        for (SkuDailyPlanQuotaDTO quota : quotaList) {
            if (remainingOverflowQty <= 0 || Objects.isNull(quota)) {
                break;
            }
            int deductionQty = Math.min(Math.max(0, quota.getRemainingQty()), remainingOverflowQty);
            quota.setRemainingQty(Math.max(0, quota.getRemainingQty() - deductionQty));
            remainingOverflowQty -= deductionQty;
        }
        refreshRollingFields(quotaMap);
    }

    /**
     * 按滚动补欠产顺序消费日计划额度。
     * <p>先消费当前日期及之前未完成额度，再允许预占后续 dayN 计划量；整体不超出账本剩余额度。</p>
     *
     * @param quotaMap 日计划额度账本
     * @param productionDate 实际生产日期
     * @param planQty 本次计划排产量
     * @return 实际消费的额度
     */
    public static int consumeRollingQuota(Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap,
                                          LocalDate productionDate,
                                          int planQty) {
        if (CollectionUtils.isEmpty(quotaMap) || Objects.isNull(productionDate) || planQty <= 0) {
            return 0;
        }
        int consumedQty = consumeQuotaBeforeOrOnDate(quotaMap, productionDate, planQty);
        if (consumedQty < planQty) {
            consumedQty += consumeQuotaAfterDate(quotaMap, productionDate, planQty - consumedQty);
        }
        SkuDailyPlanQuotaDTO productionQuota = quotaMap.get(productionDate);
        if (Objects.nonNull(productionQuota) && consumedQty > 0) {
            productionQuota.setActualQty(productionQuota.getActualQty() + consumedQty);
        }
        refreshRollingFields(quotaMap);
        return consumedQty;
    }

    /**
     * 刷新滚动欠产、累计排产和最终欠产字段。
     *
     * @param quotaMap 日计划额度账本
     */
    public static void refreshRollingFields(Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap) {
        if (CollectionUtils.isEmpty(quotaMap)) {
            return;
        }
        int cumulativeQty = 0;
        int carryLossQty = 0;
        SkuDailyPlanQuotaDTO lastQuota = null;
        for (SkuDailyPlanQuotaDTO quota : quotaMap.values()) {
            if (Objects.isNull(quota)) {
                continue;
            }
            int scheduledQty = Math.max(0, quota.getScheduledQty());
            int remainingQty = Math.max(0, quota.getRemainingQty());
            int effectiveDemandQty = Math.max(0, scheduledQty + remainingQty);
            cumulativeQty += scheduledQty;
            carryLossQty = Math.max(0, carryLossQty + effectiveDemandQty - scheduledQty);
            quota.setCumulativeQty(cumulativeQty);
            quota.setCarryLossQty(carryLossQty);
            quota.setCompleted(remainingQty <= 0);
            quota.setFinalLossQty(0);
            lastQuota = quota;
        }
        if (Objects.nonNull(lastQuota)) {
            lastQuota.setFinalLossQty(sumRemainingQty(quotaMap));
        }
    }

    private static int consumeQuotaBeforeOrOnDate(Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap,
                                                  LocalDate productionDate,
                                                  int planQty) {
        int consumedQty = 0;
        for (Map.Entry<LocalDate, SkuDailyPlanQuotaDTO> entry : quotaMap.entrySet()) {
            if (entry.getKey().isAfter(productionDate)) {
                continue;
            }
            consumedQty += consumeSingleQuota(entry.getValue(), planQty - consumedQty);
            if (consumedQty >= planQty) {
                break;
            }
        }
        return consumedQty;
    }

    private static int consumeQuotaAfterDate(Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap,
                                             LocalDate productionDate,
                                             int planQty) {
        int consumedQty = 0;
        SkuDailyPlanQuotaDTO productionQuota = quotaMap.get(productionDate);
        for (Map.Entry<LocalDate, SkuDailyPlanQuotaDTO> entry : quotaMap.entrySet()) {
            if (!entry.getKey().isAfter(productionDate)) {
                continue;
            }
            int consumeQty = consumeSingleQuota(entry.getValue(), planQty - consumedQty);
            if (consumeQty > 0 && Objects.nonNull(productionQuota)) {
                productionQuota.setFutureBorrowQty(productionQuota.getFutureBorrowQty() + consumeQty);
            }
            consumedQty += consumeQty;
            if (consumedQty >= planQty) {
                break;
            }
        }
        return consumedQty;
    }

    private static int consumeSingleQuota(SkuDailyPlanQuotaDTO quota, int planQty) {
        if (Objects.isNull(quota) || planQty <= 0 || quota.getRemainingQty() <= 0) {
            return 0;
        }
        int consumeQty = Math.min(quota.getRemainingQty(), planQty);
        quota.setRemainingQty(Math.max(0, quota.getRemainingQty() - consumeQty));
        quota.setScheduledQty(quota.getScheduledQty() + consumeQty);
        return consumeQty;
    }
}
