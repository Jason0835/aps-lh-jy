/**
 * Copyright (c) 2008, 智立通（厦门）科技有限公司 All rights reserved。
 */
package com.zlt.aps.lh.api.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 停机类型枚举
 *
 * @author zlt
 */
@Getter
@AllArgsConstructor
public enum MachineStopTypeEnum {

    PRECISION_CHECK("00", "精度校验"),
    LUBRICATION("01", "润滑"),
    INSPECTION("02", "巡检点检"),
    PREDICTIVE_MAINTENANCE("03", "预见性维护"),
    PREVENTIVE_MAINTENANCE("04", "预防性维护"),
    PLANNED_REPAIR("05", "计划性维修"),
    TEMPORARY_FAULT("06", "临时性故障"),
    DRY_ICE_CLEANING("07", "干冰清洗"),
    SANDBLASTING_CLEANING("08", "喷砂清洗"),
    TAKE_STOCK("09", "盘点");

    /** 类型编码 */
    private final String code;

    /** 类型描述 */
    private final String description;

    /**
     * 根据编码获取枚举
     *
     * @param code 类型编码
     * @return 停机类型枚举，未找到返回null
     */
    public static MachineStopTypeEnum getByCode(String code) {
        if (code == null) {
            return null;
        }
        for (MachineStopTypeEnum e : values()) {
            if (e.getCode().equals(code)) {
                return e;
            }
        }
        return null;
    }
}
