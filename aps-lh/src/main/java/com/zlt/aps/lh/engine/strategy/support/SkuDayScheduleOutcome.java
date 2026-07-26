package com.zlt.aps.lh.engine.strategy.support;

/**
 * 单个 SKU 在当前业务日完成排产尝试后的结果。
 *
 * @author APS
 */
public enum SkuDayScheduleOutcome {

    /** 当前 SKU 的业务目标和运行态账本均已完成 */
    COMPLETED,
    /** 当前日已经形成有效结果，但仍需在下一业务日原机台连续生产 */
    SCHEDULED_AND_CARRY_OVER,
    /** 当前日时间或资源暂时不足，需要进入下一业务日重新尝试 */
    DEFER_TO_NEXT_DAY,
    /** 当前日没有形成新增量，但仍保留后续业务日资格 */
    NO_PROGRESS_TODAY,
    /** 已确认硬性不可排，或窗口最后一日仍未完成，已经写入最终未排 */
    FINAL_UNSCHEDULED
}
