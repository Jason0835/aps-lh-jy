package com.zlt.aps.lh.api.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.apache.commons.lang3.StringUtils;

/**
 * SKU新增排产候选来源类型。
 *
 * <p>该枚举只用于排程运行态识别候选来源，不改变排程结果 {@code SCHEDULE_TYPE} 落库语义。</p>
 *
 * @author APS
 */
@Getter
@AllArgsConstructor
public enum SkuScheduleSourceTypeEnum {

    /** 普通新增排产候选 */
    NORMAL_NEW_SPEC("NORMAL_NEW_SPEC", "普通新增"),
    /** 换活字块后仍未排完并回流新增排产的候选 */
    TYPE_BLOCK_TO_NEW_SPEC("TYPE_BLOCK_TO_NEW_SPEC", "换活字块转新增"),
    /** 续作阶段仅生成加机台需求，进入新增排产统一竞争的候选 */
    CONTINUATION_ADD_MACHINE("CONTINUATION_ADD_MACHINE", "续作加机台");

    /** 来源编码 */
    private final String code;
    /** 来源描述 */
    private final String description;

    /**
     * 判断是否为续作加机台候选。
     *
     * @param sourceType 来源类型编码
     * @return true-续作加机台候选，false-其他来源
     */
    public static boolean isContinuationAddMachine(String sourceType) {
        return StringUtils.equals(CONTINUATION_ADD_MACHINE.getCode(), sourceType);
    }
}
