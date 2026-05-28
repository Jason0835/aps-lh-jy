/**
 * Copyright (c) 2008, 智立通（厦门）科技有限公司 All rights reserved。
 */
package com.zlt.aps.lh.api.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 模具交替类型枚举。
 *
 * <p>对应模具交替计划 {@code CHANGE_MOULD_TYPE} 字段，由 S4.6 根据排程结果和清洗计划生成。</p>
 *
 * @author zlt
 */
@Getter
@AllArgsConstructor
public enum MouldChangeTypeEnum {

    /** 01-正规换模，新增规格需要完整换模时使用 */
    REGULAR("01", "正规"),
    /** 02-更换活字块，换活字块排产结果生成对应交替计划时使用 */
    TYPE_BLOCK("02", "更换活字块"),
    /** 03-模具喷砂清洗，由清洗计划补充生成的交替计划使用 */
    SAND_BLAST("03", "模具喷砂清洗"),
    /** 04-模具干冰清洗，由清洗计划补充生成的交替计划使用 */
    DRY_ICE("04", "模具干冰清洗");

    /** 类型编码 */
    private final String code;

    /** 类型描述 */
    private final String description;

    /**
     * 根据编码获取枚举
     *
     * @param code 类型编码
     * @return 模具交替类型枚举，未找到返回null
     */
    public static MouldChangeTypeEnum getByCode(String code) {
        if (code == null) {
            return null;
        }
        for (MouldChangeTypeEnum e : values()) {
            if (e.getCode().equals(code)) {
                return e;
            }
        }
        return null;
    }
}
