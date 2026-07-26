package com.zlt.aps.lh.engine.strategy.support;

/**
 * 新增排产单个业务日内的资源竞争阶段。
 *
 * <p>阶段顺序是按天驱动改造的核心约束：已上机延续优先于当天计划，
 * 当天计划优先于加机台，加机台优先于提前生产。枚举顺序不得用于替代
 * 明确的阶段调用顺序，避免后续增加枚举值时无意改变业务优先级。</p>
 *
 * @author APS
 */
public enum DailySchedulePhase {

    /** 延续前一业务日已经上机的新增 SKU */
    CARRY_OVER,
    /** 当前日计划、历史反选和锁定任务 */
    TODAY_PLAN_AND_LOCKED,
    /** 当前业务日已经到期的加机台需求 */
    ADD_MACHINE,
    /** 使用前三阶段完成后的剩余资源执行提前生产 */
    EARLY_PRODUCTION,
    /** 当日状态收口以及窗口末日最终未排处理 */
    FINALIZE
}
