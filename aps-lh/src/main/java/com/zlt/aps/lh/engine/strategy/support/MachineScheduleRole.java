package com.zlt.aps.lh.engine.strategy.support;

/**
 * 多机台动态补量中的机台排产角色。
 *
 * @author APS
 */
public enum MachineScheduleRole {

    /** 非最后机台：开产后排满窗口后续所有可用班次 */
    FULL_RUN_MACHINE,
    /** 最后一台机台：只排到满足窗口目标量 */
    TAIL_MACHINE
}
