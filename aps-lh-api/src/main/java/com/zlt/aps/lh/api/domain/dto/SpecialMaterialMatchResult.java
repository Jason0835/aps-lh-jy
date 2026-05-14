package com.zlt.aps.lh.api.domain.dto;

import lombok.Data;
import org.apache.commons.lang3.StringUtils;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

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
    /** 特殊物料分类集合 */
    private List<String> categories = new ArrayList<String>();
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
        result.setCategories(Collections.<String>emptyList());
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
        return special(Collections.singletonList(category), matchSource);
    }

    /**
     * 构建特殊物料结果。
     *
     * @param categories 分类集合
     * @param matchSource 命中来源
     * @return 特殊物料结果
     */
    public static SpecialMaterialMatchResult special(Collection<String> categories, String matchSource) {
        SpecialMaterialMatchResult result = new SpecialMaterialMatchResult();
        result.setSpecial(true);
        result.setCategories(categories == null
                ? Collections.<String>emptyList()
                : new ArrayList<String>(categories));
        result.setMatchSource(matchSource);
        return result;
    }

    /**
     * 获取首个特殊物料分类，兼容旧单分类调用口径。
     *
     * @return 首个分类，未命中返回null
     */
    public String getCategory() {
        return categories.isEmpty() ? null : categories.get(0);
    }

    /**
     * 获取用于日志输出的分类展示文本。
     *
     * @return 多分类逗号拼接文本
     */
    public String getCategoryDisplayText() {
        return categories.isEmpty() ? null : StringUtils.join(categories, ",");
    }
}
