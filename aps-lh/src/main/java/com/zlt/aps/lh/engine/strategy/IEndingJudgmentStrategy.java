package com.zlt.aps.lh.engine.strategy;

import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.api.domain.dto.SkuScheduleDTO;

import java.util.Objects;

/**
 * 收尾判定策略接口
 * <p>统一收尾判定逻辑，避免分散在多处导致不一致</p>
 *
 * @author APS
 */
public interface IEndingJudgmentStrategy {

    /**
     * 判断SKU是否处于收尾状态
     * <p>
     * 收尾判定规则（按优先级）：
     * <ol>
     *   <li>已明确标记为收尾（SkuTag.ENDING）</li>
     *   <li>硫化余量 <= 排程期内可生产总产能</li>
     *   <li>待排量 < 日产能（非满产运行）</li>
     * </ol>
     * </p>
     *
     * @param context 排程上下文
     * @param sku     SKU排程DTO
     * @return true表示处于收尾状态
     */
    boolean isEnding(LhScheduleContext context, SkuScheduleDTO sku);

    /**
     * 判断 SKU 是否为排前预计收尾。
     * <p>预计收尾仅用于排序日志、分析和排前参考，不直接控制实际排产控量。</p>
     *
     * @param context 排程上下文
     * @param sku SKU排程DTO
     * @return true-预计收尾；false-非预计收尾
     */
    default boolean isExpectedEnding(LhScheduleContext context, SkuScheduleDTO sku) {
        return isEnding(context, sku);
    }

    /**
     * 判断 SKU 是否为当前排程窗口收尾。
     * <p>当前窗口收尾用于实际排产控量，调用方应在实际机台集合确定后、生成班次计划量前调用。</p>
     *
     * @param context 排程上下文
     * @param sku SKU排程DTO
     * @return true-当前窗口收尾；false-当前窗口非收尾
     */
    default boolean isCurrentWindowEnding(LhScheduleContext context, SkuScheduleDTO sku) {
        return isEnding(context, sku);
    }

    /**
     * 判断 SKU 排后最终是否收尾。
     *
     * @param context 排程上下文
     * @param sku SKU排程DTO
     * @param actualScheduledQty 本次窗口实际总排产量
     * @return true-最终收尾；false-最终非收尾
     */
    default boolean isFinalEnding(LhScheduleContext context, SkuScheduleDTO sku, int actualScheduledQty) {
        if (Objects.isNull(sku)) {
            return false;
        }
        int surplusQty = Math.max(0, sku.getSurplusQty());
        int embryoStock = Math.max(0, sku.getEmbryoStock());
        int endingDemandQty = Math.max(surplusQty, embryoStock);
        return endingDemandQty > 0 && Math.max(0, actualScheduledQty) >= endingDemandQty;
    }

    /**
     * 判断 SKU 是否命中结构排序专用收尾。
     * <p>结构收尾只用于 SKU 排序优先级，不直接决定实际排产控量。</p>
     *
     * @param context 排程上下文
     * @param sku SKU排程DTO
     * @return true-结构排序收尾；false-未命中结构排序收尾
     */
    default boolean isStructureEndingForPriority(LhScheduleContext context, SkuScheduleDTO sku) {
        return isExpectedEnding(context, sku) && calculateEndingDaysForStructurePriority(context, sku) >= 0;
    }

    /**
     * 计算预计收尾所需班次数
     *
     * @param context 排程上下文
     * @param sku     SKU排程DTO
     * @return 预计收尾班次数，-1表示无法判定
     */
    int calculateEndingShifts(LhScheduleContext context, SkuScheduleDTO sku);

    /**
     * 计算预计收尾天数
     *
     * @param context 排程上下文
     * @param sku     SKU排程DTO
     * @return 预计收尾天数
     */
    int calculateEndingDays(LhScheduleContext context, SkuScheduleDTO sku);

    /**
     * 计算结构排序使用的真实收尾天数。
     * <p>默认复用通用收尾天数，具体实现可按候选机台真实窗口口径覆写。</p>
     *
     * @param context 排程上下文
     * @param sku SKU排程DTO
     * @return 结构排序使用的预计收尾天数
     */
    default int calculateEndingDaysForStructurePriority(LhScheduleContext context, SkuScheduleDTO sku) {
        return calculateEndingDays(context, sku);
    }
}
