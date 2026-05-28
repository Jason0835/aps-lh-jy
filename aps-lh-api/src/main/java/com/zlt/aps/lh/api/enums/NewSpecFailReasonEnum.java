/**
 * Copyright (c) 2008, 智立通（厦门）科技有限公司 All rights reserved。
 */
package com.zlt.aps.lh.api.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 新增规格排产失败原因优先级枚举。
 *
 * <p>用于 S4.5 新增规格多候选尝试失败后选择最关键的未排原因。priority 数值越大，
 * 越优先作为最终未排原因展示。</p>
 *
 * @author APS
 */
@Getter
@AllArgsConstructor
public enum NewSpecFailReasonEnum {

    /** 01-机台选择失败，通常表示硬性准入、单控/定点或模具条件过滤后无候选 */
    MACHINE_SELECTION_FAILED("01", "机台选择失败", 1),
    /** 02-换模班次分配失败，表示候选机台存在但无法安排有效换模窗口 */
    MOULD_CHANGE_SHIFT_ALLOCATE_FAILED("02", "换模班次分配失败", 2),
    /** 03-首检班次分配失败，表示换模后首检窗口或首检配额无法满足 */
    FIRST_INSPECTION_SHIFT_ALLOCATE_FAILED("03", "首检班次分配失败", 3),
    /** 04-排程窗口内无可用产能，表示候选机台完成换模/首检后没有可生产班次 */
    NO_CAPACITY_IN_SCHEDULE_WINDOW("04", "排程窗口内无可用产能", 4);

    /** 原因编码 */
    private final String code;

    /** 原因描述 */
    private final String description;

    /** 优先级，数值越大优先级越高 */
    private final int priority;
}
