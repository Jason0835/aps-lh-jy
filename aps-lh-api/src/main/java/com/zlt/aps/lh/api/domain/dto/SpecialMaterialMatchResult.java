package com.zlt.aps.lh.api.domain.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 特殊物料命中结果。
 *
 * @author APS
 */
@Data
public class SpecialMaterialMatchResult implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 未命中特殊物料 */
    public static final String MATCH_SOURCE_NONE = "NONE";
    /** 物料编码命中 */
    public static final String MATCH_SOURCE_MATERIAL_CODE = "MATERIAL_CODE";
    /** 结构名称命中 */
    public static final String MATCH_SOURCE_STRUCTURE_NAME = "STRUCTURE_NAME";

    /** 是否特殊物料 */
    private boolean special;
    /** 特殊物料分类 */
    private String category;
    /** 命中来源 */
    private String matchSource;

    /**
     * 构建非特殊物料结果。
     *
     * @return 非特殊物料结果
     */
    public static SpecialMaterialMatchResult nonSpecial() {
        SpecialMaterialMatchResult result = new SpecialMaterialMatchResult();
        result.setSpecial(false);
        result.setMatchSource(MATCH_SOURCE_NONE);
        return result;
    }

    /**
     * 构建特殊物料结果。
     *
     * @param category 分类
     * @param matchSource 命中来源
     * @return 特殊物料结果
     */
    public static SpecialMaterialMatchResult special(String category, String matchSource) {
        SpecialMaterialMatchResult result = new SpecialMaterialMatchResult();
        result.setSpecial(true);
        result.setCategory(category);
        result.setMatchSource(matchSource);
        return result;
    }
}
