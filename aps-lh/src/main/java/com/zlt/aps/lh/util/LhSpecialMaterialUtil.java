package com.zlt.aps.lh.util;

import com.zlt.aps.lh.api.domain.dto.SkuScheduleDTO;
import com.zlt.aps.lh.context.LhScheduleContext;
import org.apache.commons.lang3.StringUtils;

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
        boolean hasSpecialMaterial = Objects.nonNull(context)
                && Objects.nonNull(sku)
                && StringUtils.isNotEmpty(sku.getEmbryoCode())
                && context.getSpecialMaterialEmbryoCodeSet().contains(sku.getEmbryoCode());
        return hasSpecialMaterial ? YES_FLAG : NO_FLAG;
    }
}
