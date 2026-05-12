package com.zlt.aps.lh.api.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 产品状态枚举
 *
 * @author zlt
 */
@Getter
@AllArgsConstructor
public enum TrialStatusEnum {

    TRIAL("X", "试验示方"),
    MASS_TRIAL("T", "量试示方"),
    FORMAL("S", "正规示方");

    /** 状态编码 */
    private final String code;

    /** 状态描述 */
    private final String description;

    /**
     * 根据编码获取枚举
     *
     * @param code 状态编码
     * @return 产品状态枚举，未找到返回null
     */
    public static TrialStatusEnum getByCode(String code) {
        if (code == null) {
            return null;
        }
        for (TrialStatusEnum e : values()) {
            if (e.getCode().equals(code)) {
                return e;
            }
        }
        return null;
    }
}
