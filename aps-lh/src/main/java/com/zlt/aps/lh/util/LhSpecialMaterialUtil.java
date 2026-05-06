package com.zlt.aps.lh.util;

import com.zlt.aps.lh.api.domain.dto.SpecialMaterialMatchResult;
import com.zlt.aps.lh.api.domain.dto.SkuScheduleDTO;
import com.zlt.aps.lh.api.enums.LhSpecialMaterialCategoryEnum;
import com.zlt.aps.lh.context.LhScheduleContext;
import org.apache.commons.lang3.StringUtils;
import org.springframework.util.CollectionUtils;

import java.util.Map;
import java.util.Objects;

/**
 * 硫化特殊材料字段工具。
 *
 * @author APS
 */
public final class LhSpecialMaterialUtil {

    private static final String YES_FLAG = "1";
    private static final String NO_FLAG = "0";

    private LhSpecialMaterialUtil() {
    }

    /**
     * 解析SKU是否含特殊材料。
     *
     * @param context 排程上下文
     * @param sku SKU排程信息
     * @return 是否含特殊材料，1-是，0-否
     */
    public static String resolveHasSpecialMaterial(LhScheduleContext context, SkuScheduleDTO sku) {
        return resolveMatchResult(context, sku).isSpecial() ? YES_FLAG : NO_FLAG;
    }

    /**
     * 解析SKU特殊物料命中结果。
     *
     * @param context 排程上下文
     * @param sku SKU排程信息
     * @return 特殊物料命中结果
     */
    public static SpecialMaterialMatchResult resolveMatchResult(LhScheduleContext context, SkuScheduleDTO sku) {
        if (Objects.isNull(context) || Objects.isNull(sku)) {
            return SpecialMaterialMatchResult.nonSpecial();
        }

        // 物料编码优先命中。
        String materialCode = normalizeToken(sku.getMaterialCode());
        SpecialMaterialMatchResult materialCodeResult = resolveByKey(
                context.getSpecialMaterialCategoryByMaterialCode(),
                materialCode,
                SpecialMaterialMatchResult.MATCH_SOURCE_MATERIAL_CODE);
        if (materialCodeResult.isSpecial()) {
            return materialCodeResult;
        }

        // 未命中物料编码时，再按结构名称命中。
        String structureName = normalizeToken(sku.getStructureName());
        return resolveByKey(
                context.getSpecialMaterialCategoryByStructureName(),
                structureName,
                SpecialMaterialMatchResult.MATCH_SOURCE_STRUCTURE_NAME);
    }

    /**
     * 按指定Map解析特殊物料命中结果。
     *
     * @param categoryMap 分类Map
     * @param key 命中键
     * @param matchSource 命中来源
     * @return 特殊物料命中结果
     */
    private static SpecialMaterialMatchResult resolveByKey(Map<String, String> categoryMap,
                                                           String key,
                                                           String matchSource) {
        if (CollectionUtils.isEmpty(categoryMap) || StringUtils.isEmpty(key)) {
            return SpecialMaterialMatchResult.nonSpecial();
        }
        String category = categoryMap.get(key);
        if (!LhSpecialMaterialCategoryEnum.isValid(category)) {
            return SpecialMaterialMatchResult.nonSpecial();
        }
        return SpecialMaterialMatchResult.special(category, matchSource);
    }

    /**
     * 统一清洗匹配字段。
     *
     * @param value 原始值
     * @return 清洗后字段
     */
    private static String normalizeToken(String value) {
        if (StringUtils.isEmpty(value)) {
            return null;
        }
        String trimValue = value.trim();
        return StringUtils.isEmpty(trimValue) ? null : trimValue;
    }
}
