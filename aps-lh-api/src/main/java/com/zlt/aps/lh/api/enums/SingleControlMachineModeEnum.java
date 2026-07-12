package com.zlt.aps.lh.api.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 单控机台使用模式。
 *
 * <p>该模式在一次硫化排程进入续作前统一冻结，后续不得根据动态剩余量重新计算。</p>
 *
 * @author APS
 */
@Getter
@AllArgsConstructor
public enum SingleControlMachineModeEnum {

    /** 单模：L/R 两侧作为独立排产单元使用 */
    SINGLE_SIDE("01", "单模"),
    /** 双模：同一物理机台 L/R 两侧必须同步生产相同 SKU */
    WHOLE_PAIR("02", "双模");

    /** 模式编码 */
    private final String code;
    /** 模式说明 */
    private final String description;
}
