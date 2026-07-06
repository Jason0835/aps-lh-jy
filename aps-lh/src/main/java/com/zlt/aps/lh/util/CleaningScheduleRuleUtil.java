package com.zlt.aps.lh.util;

import com.zlt.aps.lh.api.domain.dto.SkuScheduleDTO;
import com.zlt.aps.lh.api.domain.entity.LhScheduleResult;
import com.zlt.aps.lh.context.LhScheduleContext;

import java.util.Objects;

/**
 * 清洗计划业务规则工具。
 *
 * <p>该工具只承载清洗专用判断，避免干冰/喷砂清洗规则散落到新增、续作、换活字块等排程策略中。</p>
 */
public final class CleaningScheduleRuleUtil {

    /** SKU 从清洗时间点开始 3 天内可收尾时跳过清洗 */
    public static final int CLEANING_SKIP_ENDING_DAYS = 3;

    private CleaningScheduleRuleUtil() {
    }

    /**
     * 判断 SKU 是否命中 3 天内收尾跳过清洗规则。
     *
     * @param sku SKU 排程数据
     * @return true-应跳过清洗；false-不跳过清洗
     */
    public static boolean shouldSkipCleaningBySkuEnding(LhScheduleContext context, SkuScheduleDTO sku) {
        if (Objects.isNull(sku)) {
            return false;
        }
        int dailyCapacity = sku.getDailyCapacity();
        if (dailyCapacity <= 0) {
            // 清洗跳过按“SKU日标准产量”判断；SKU DTO 未带日标准产能时，从上下文主数据回查。
            dailyCapacity = ShiftCapacityResolverUtil.resolveDailyStandardQty(context, sku.getMaterialCode());
        }
        return isEndingWithinDays(sku.getSurplusQty(), dailyCapacity, CLEANING_SKIP_ENDING_DAYS);
    }

    /**
     * 判断排程结果是否命中 3 天内收尾跳过清洗规则。
     *
     * @param result 排程结果
     * @return true-应跳过清洗；false-不跳过清洗
     */
    public static boolean shouldSkipCleaningByResultEnding(LhScheduleResult result) {
        if (Objects.isNull(result)) {
            return false;
        }
        int surplusQty = Objects.isNull(result.getMouldSurplusQty()) ? 0 : result.getMouldSurplusQty();
        int dailyCapacity = Objects.isNull(result.getStandardCapacity()) ? 0 : result.getStandardCapacity();
        return isEndingWithinDays(surplusQty, dailyCapacity, CLEANING_SKIP_ENDING_DAYS);
    }

    /**
     * 判断指定余量按日标准产能是否可在阈值天数内收尾。
     *
     * @param surplusQty 硫化余量
     * @param dailyCapacity 日标准产能
     * @param thresholdDays 收尾天数阈值
     * @return true-阈值天数内可收尾；false-无法判定或超过阈值
     */
    public static boolean isEndingWithinDays(int surplusQty, int dailyCapacity, int thresholdDays) {
        if (surplusQty <= 0) {
            return true;
        }
        if (dailyCapacity <= 0 || thresholdDays <= 0) {
            return false;
        }
        int remainingDays = (surplusQty + dailyCapacity - 1) / dailyCapacity;
        return remainingDays <= thresholdDays;
    }
}
