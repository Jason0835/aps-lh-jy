package com.zlt.aps.lh.engine.strategy;

import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.api.domain.dto.SkuScheduleDTO;

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
