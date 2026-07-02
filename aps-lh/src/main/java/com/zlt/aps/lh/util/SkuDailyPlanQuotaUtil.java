package com.zlt.aps.lh.util;

import com.zlt.aps.lh.api.domain.dto.SkuDailyPlanQuotaDTO;
import org.springframework.util.CollectionUtils;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * SKU dayN节奏账本工具类。
 * <p>统一处理滚动补欠产、未来计划预占和窗口总量封顶，避免续作、新增排产各自消费dayN节奏额度。</p>
 * <p>该工具返回的是节奏账本消费量，不作为非收尾SKU实际落地排产量的硬上限。</p>
 *
 * @author APS
 */
public final class SkuDailyPlanQuotaUtil {

    private SkuDailyPlanQuotaUtil() {
    }

    /**
     * 汇总当前窗口剩余dayN节奏额度。
     *
     * @param quotaMap dayN节奏账本
     * @return 剩余节奏额度汇总
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
     * 将dayN节奏账本剩余额度压回窗口可排上限内。
     * <p>欠产补产优先保留靠前日期额度，超过窗口上限的部分从后续日期向前扣减。</p>
     *
     * @param quotaMap dayN节奏账本
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
     * 按滚动补欠产顺序消费dayN节奏额度。
     * <p>先消费当前日期及之前未完成额度，再允许预占后续 dayN 计划量；整体不超出账本剩余额度。</p>
     *
     * @param quotaMap dayN节奏账本
     * @param productionDate 实际生产日期
     * @param planQty 本次计划排产量
     * @return 实际消费的dayN节奏额度
     */
    public static int consumeRollingQuota(Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap,
                                          LocalDate productionDate,
                                          int planQty) {
        return consumeRollingQuota(quotaMap, productionDate, planQty, null);
    }

    /**
     * 按滚动补欠产顺序消费dayN节奏额度，并限制可预占的未来日期。
     * <p>先消费当前日期及之前未完成额度，再允许预占不晚于追补截止日的后续 dayN 计划量。</p>
     *
     * @param quotaMap dayN节奏账本
     * @param productionDate 实际生产日期
     * @param planQty 本次计划排产量
     * @param lookAheadEndDate 允许提前借用的最晚生产日期，null 表示沿用原公共语义
     * @return 实际消费的dayN节奏额度
     */
    public static int consumeRollingQuota(Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap,
                                          LocalDate productionDate,
                                          int planQty,
                                          LocalDate lookAheadEndDate) {
        if (CollectionUtils.isEmpty(quotaMap) || Objects.isNull(productionDate) || planQty <= 0) {
            return 0;
        }
        int consumedQty = consumeQuotaBeforeOrOnDate(quotaMap, productionDate, planQty);
        if (consumedQty < planQty) {
            consumedQty += consumeQuotaAfterDate(
                    quotaMap, productionDate, planQty - consumedQty, lookAheadEndDate);
        }
        SkuDailyPlanQuotaDTO productionQuota = quotaMap.get(productionDate);
        if (Objects.nonNull(productionQuota) && consumedQty > 0) {
            productionQuota.setActualQty(productionQuota.getActualQty() + consumedQty);
        }
        refreshRollingFields(quotaMap);
        return consumedQty;
    }

    /**
     * 构造提前生产临时日计划额度视图。
     * <p>历史三参调用仍按提前一天处理，因此当前业务日到窗口结束日逐日读取下一天额度；
     * 该方法只克隆运行态账本，不修改原始月计划日计划量和原始额度对象。</p>
     *
     * @param quotaMap 原始日计划额度账本
     * @param currentDate 当前业务日期
     * @param windowEndDate 排程窗口结束日期
     * @return 前移一天后的临时额度账本
     */
    public static Map<LocalDate, SkuDailyPlanQuotaDTO> buildShiftedEarlyProductionQuotaMap(
            Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap,
            LocalDate currentDate,
            LocalDate windowEndDate) {
        return buildShiftedEarlyProductionQuotaMap(quotaMap, currentDate, windowEndDate, 1);
    }

    /**
     * 构造提前生产临时日计划额度视图。
     * <p>按实际提前天数将未来 dayN 日计划前移到当前排程窗口，仅用于新增机台节奏判断和加机台模拟；
     * 该方法只克隆运行态账本，不修改原始月计划日计划量和原始额度对象。</p>
     *
     * @param quotaMap 原始日计划额度账本
     * @param currentDate 当前业务日期
     * @param windowEndDate 排程窗口结束日期
     * @param futurePlanDate 提前生产命中的未来计划日
     * @return 按实际提前天数前移后的临时额度账本
     */
    public static Map<LocalDate, SkuDailyPlanQuotaDTO> buildShiftedEarlyProductionQuotaMap(
            Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap,
            LocalDate currentDate,
            LocalDate windowEndDate,
            LocalDate futurePlanDate) {
        int shiftDays = 1;
        if (Objects.nonNull(currentDate) && Objects.nonNull(futurePlanDate)) {
            shiftDays = (int) Math.max(1, ChronoUnit.DAYS.between(currentDate, futurePlanDate));
        }
        return buildShiftedEarlyProductionQuotaMap(quotaMap, currentDate, windowEndDate, shiftDays);
    }

    /**
     * 构造提前生产临时日计划额度视图。
     *
     * @param quotaMap 原始日计划额度账本
     * @param currentDate 当前业务日期
     * @param windowEndDate 排程窗口结束日期
     * @param shiftDays 实际提前天数
     * @return 按提前天数前移后的临时额度账本
     */
    private static Map<LocalDate, SkuDailyPlanQuotaDTO> buildShiftedEarlyProductionQuotaMap(
            Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap,
            LocalDate currentDate,
            LocalDate windowEndDate,
            int shiftDays) {
        Map<LocalDate, SkuDailyPlanQuotaDTO> shiftedQuotaMap =
                new LinkedHashMap<LocalDate, SkuDailyPlanQuotaDTO>(4);
        if (CollectionUtils.isEmpty(quotaMap) || Objects.isNull(currentDate) || Objects.isNull(windowEndDate)
                || currentDate.isAfter(windowEndDate)) {
            return shiftedQuotaMap;
        }
        int safeShiftDays = Math.max(1, shiftDays);
        String materialCode = resolveMaterialCode(quotaMap);
        LocalDate date = currentDate;
        while (!date.isAfter(windowEndDate)) {
            SkuDailyPlanQuotaDTO sourceQuota = quotaMap.get(date.plusDays(safeShiftDays));
            shiftedQuotaMap.put(date, cloneQuotaForProductionDate(sourceQuota, date, materialCode));
            date = date.plusDays(1);
        }
        refreshRollingFields(shiftedQuotaMap);
        return shiftedQuotaMap;
    }

    /**
     * 解析允许提前借用日计划额度的截止日期。
     * <p>截止日受追补天数、排程窗口结束日和账本最后日期共同限制。</p>
     *
     * @param quotaMap 日计划额度账本
     * @param productionDate 实际生产日期
     * @param lookAheadDays 向后观察天数，不含当天
     * @param windowEndDate 排程窗口结束日期
     * @return 允许借用的最晚日期
     */
    public static LocalDate resolveLookAheadEndDate(Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap,
                                                    LocalDate productionDate,
                                                    int lookAheadDays,
                                                    LocalDate windowEndDate) {
        if (Objects.isNull(productionDate)) {
            return null;
        }
        LocalDate endDate = productionDate.plusDays(Math.max(0, lookAheadDays));
        if (Objects.nonNull(windowEndDate) && windowEndDate.isBefore(endDate)) {
            endDate = windowEndDate;
        }
        LocalDate lastQuotaDate = resolveLastQuotaDate(quotaMap);
        if (Objects.nonNull(lastQuotaDate) && lastQuotaDate.isBefore(endDate)) {
            endDate = lastQuotaDate;
        }
        return endDate;
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
                                             int planQty,
                                             LocalDate lookAheadEndDate) {
        int consumedQty = 0;
        SkuDailyPlanQuotaDTO productionQuota = quotaMap.get(productionDate);
        for (Map.Entry<LocalDate, SkuDailyPlanQuotaDTO> entry : quotaMap.entrySet()) {
            if (!entry.getKey().isAfter(productionDate)) {
                continue;
            }
            if (Objects.nonNull(lookAheadEndDate) && entry.getKey().isAfter(lookAheadEndDate)) {
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

    private static LocalDate resolveLastQuotaDate(Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap) {
        if (CollectionUtils.isEmpty(quotaMap)) {
            return null;
        }
        LocalDate lastQuotaDate = null;
        for (LocalDate quotaDate : quotaMap.keySet()) {
            if (Objects.isNull(quotaDate)) {
                continue;
            }
            if (Objects.isNull(lastQuotaDate) || quotaDate.isAfter(lastQuotaDate)) {
                lastQuotaDate = quotaDate;
            }
        }
        return lastQuotaDate;
    }

    private static String resolveMaterialCode(Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap) {
        if (CollectionUtils.isEmpty(quotaMap)) {
            return null;
        }
        for (SkuDailyPlanQuotaDTO quota : quotaMap.values()) {
            if (Objects.nonNull(quota)) {
                return quota.getMaterialCode();
            }
        }
        return null;
    }

    private static SkuDailyPlanQuotaDTO cloneQuotaForProductionDate(SkuDailyPlanQuotaDTO sourceQuota,
                                                                    LocalDate productionDate,
                                                                    String materialCode) {
        SkuDailyPlanQuotaDTO targetQuota = new SkuDailyPlanQuotaDTO();
        targetQuota.setProductionDate(productionDate);
        targetQuota.setMaterialCode(Objects.nonNull(sourceQuota) ? sourceQuota.getMaterialCode() : materialCode);
        if (Objects.isNull(sourceQuota)) {
            return targetQuota;
        }
        targetQuota.setDayPlanQty(Math.max(0, sourceQuota.getDayPlanQty()));
        targetQuota.setScheduledQty(Math.max(0, sourceQuota.getScheduledQty()));
        targetQuota.setRemainingQty(Math.max(0, sourceQuota.getRemainingQty()));
        targetQuota.setShiftFillOverQty(Math.max(0, sourceQuota.getShiftFillOverQty()));
        targetQuota.setCarryLossQty(Math.max(0, sourceQuota.getCarryLossQty()));
        targetQuota.setFutureBorrowQty(Math.max(0, sourceQuota.getFutureBorrowQty()));
        targetQuota.setActualQty(Math.max(0, sourceQuota.getActualQty()));
        targetQuota.setCumulativeQty(Math.max(0, sourceQuota.getCumulativeQty()));
        targetQuota.setFinalLossQty(Math.max(0, sourceQuota.getFinalLossQty()));
        targetQuota.setCompleted(sourceQuota.isCompleted());
        return targetQuota;
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
