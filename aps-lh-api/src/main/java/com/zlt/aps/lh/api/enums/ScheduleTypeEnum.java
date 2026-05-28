/**
 * Copyright (c) 2008, 智立通（厦门）科技有限公司 All rights reserved。
 */
package com.zlt.aps.lh.api.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 排程类型枚举。
 *
 * <p>对应排程结果 {@code SCHEDULE_TYPE} 字段，也用于策略工厂选择排产策略。</p>
 *
 * @author zlt
 */
@Getter
@AllArgsConstructor
public enum ScheduleTypeEnum {

    /** 01-续作，S4.4 对 MES 在机/滚动继承规格继续排产 */
    CONTINUOUS("01", "续作"),
    /** 02-新增，S4.5 对新增待排 SKU 执行选机、换模、首检和班次分配 */
    NEW_SPEC("02", "新增"),
    /** 03-换活字块，S4.4 收尾后同胎胚同模具等条件满足时衔接下一规格 */
    TYPE_BLOCK("03", "换活字块");

    /** 类型编码 */
    private final String code;

    /** 类型描述 */
    private final String description;

    /**
     * 根据编码获取枚举
     *
     * @param code 类型编码
     * @return 排程类型枚举，未找到返回null
     */
    public static ScheduleTypeEnum getByCode(String code) {
        if (code == null) {
            return null;
        }
        for (ScheduleTypeEnum e : values()) {
            if (e.getCode().equals(code)) {
                return e;
            }
        }
        return null;
    }
}
