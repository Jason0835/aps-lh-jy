/**
 * Copyright (c) 2008, 智立通（厦门）科技有限公司 All rights reserved。
 */
package com.zlt.aps.lh.api.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 施工阶段枚举。
 *
 * <p>对应月计划中的施工阶段字段，影响 SKU 排序、试制/量试识别、单控机台选择、
 * 严格目标量和硫化示方类型匹配。</p>
 *
 * @author zlt
 */
@Getter
@AllArgsConstructor
public enum ConstructionStageEnum {

    /** 00-无工艺，表示月计划未维护明确施工阶段，按非试制量试处理 */
    NO_PROCESS("00", "无工艺"),
    /** 01-试制，排产时严格控量，并要求使用单控机台 */
    TRIAL("01", "试制"),
    /** 02-量试，排序和选机有独立优先级，但不等同试制严格单控 */
    MASS_TRIAL("02", "量试"),
    /** 03-正式，按正规 SKU 主排序和普通/单控机台规则处理 */
    FORMAL("03", "正式");

    /** 阶段编码 */
    private final String code;

    /** 阶段描述 */
    private final String description;

    /**
     * 根据编码获取枚举
     *
     * @param code 阶段编码
     * @return 施工阶段枚举，未找到返回null
     */
    public static ConstructionStageEnum getByCode(String code) {
        if (code == null) {
            return null;
        }
        for (ConstructionStageEnum e : values()) {
            if (e.getCode().equals(code)) {
                return e;
            }
        }
        return null;
    }
}
