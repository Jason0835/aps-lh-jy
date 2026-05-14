/**
 * Copyright (c) 2008, 智立通（厦门）科技有限公司 All rights reserved。
 */
package com.zlt.aps.lh.api.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.apache.commons.lang3.StringUtils;

/**
 * 硫化特殊物料分类枚举。
 *
 * @author APS
 */
@Getter
@AllArgsConstructor
public enum LhSpecialMaterialCategoryEnum {

    /** 19.5寸宽基 */
    WIDE_BASE_195("01", "19.5寸宽基"),
    /** 22.5寸宽基 */
    WIDE_BASE_225("02", "22.5寸宽基"),
    /** 芯片胎 */
    CHIP_TIRE("03", "芯片胎");

    /** 分类编码 */
    private final String code;
    /** 分类描述 */
    private final String description;

    /**
     * 根据编码获取特殊物料分类。
     *
     * @param code 分类编码
     * @return 分类枚举，未找到返回null
     */
    public static LhSpecialMaterialCategoryEnum getByCode(String code) {
        if (StringUtils.isEmpty(code)) {
            return null;
        }
        for (LhSpecialMaterialCategoryEnum categoryEnum : values()) {
            if (StringUtils.equals(categoryEnum.getCode(), code)) {
                return categoryEnum;
            }
        }
        return null;
    }

    /**
     * 判断分类编码是否合法。
     *
     * @param code 分类编码
     * @return true-合法，false-非法
     */
    public static boolean isValid(String code) {
        return getByCode(code) != null;
    }
}
