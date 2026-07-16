package com.zlt.aps.lh.engine.strategy;

import com.zlt.aps.lh.context.LhScheduleContext;

/**
 * 前日模具交替计划机台反选SKU策略。
 *
 * <p>该策略只负责编排历史计划、固定机台和后物料关系，不复制换活字块或新增换模算法。</p>
 */
public interface IHistoricalMouldChangeReverseSelectionStrategy {

    /**
     * 执行前日交替计划机台反选。
     *
     * @param context 排程上下文
     */
    void reverseSelect(LhScheduleContext context);
}
