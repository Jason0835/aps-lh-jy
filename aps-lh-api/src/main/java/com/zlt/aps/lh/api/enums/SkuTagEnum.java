/**
 * Copyright (c) 2008, 智立通（厦门）科技有限公司 All rights reserved。
 */
package com.zlt.aps.lh.api.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * SKU状态标记枚举。
 *
 * <p>由收尾判断策略写入 SKU DTO，后续影响严格目标量、结果 {@code IS_END}
 * 和班次收尾标记。</p>
 *
 * @author zlt
 */
@Getter
@AllArgsConstructor
public enum SkuTagEnum {

    /** 01-常规，非收尾 SKU，可按对应策略补满可用班次 */
    NORMAL("01", "常规"),
    /** 02-收尾，必须按收尾目标量严格控制，并触发机台收尾/换活字块判断 */
    ENDING("02", "收尾");

    /** 标记编码 */
    private final String code;

    /** 标记描述 */
    private final String description;

    /**
     * 根据编码获取枚举
     *
     * @param code 标记编码
     * @return SKU状态标记枚举，未找到返回null
     */
    public static SkuTagEnum getByCode(String code) {
        if (code == null) {
            return null;
        }
        for (SkuTagEnum e : values()) {
            if (e.getCode().equals(code)) {
                return e;
            }
        }
        return null;
    }
}
