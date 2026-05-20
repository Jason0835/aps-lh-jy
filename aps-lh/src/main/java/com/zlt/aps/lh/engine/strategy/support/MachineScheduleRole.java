package com.zlt.aps.lh.engine.strategy.support;

/**
 * 多机台动态补量中的机台排产角色。
 *
 * @author APS
 */
public enum MachineScheduleRole {

    /** 非最后机台：旧顺序模式排满窗口后续班次，dayN动态模式按拆分计划承担非尾段 */
    FULL_RUN_MACHINE,
    /** 最后一台机台：只排到满足窗口目标量 */
    TAIL_MACHINE
}
