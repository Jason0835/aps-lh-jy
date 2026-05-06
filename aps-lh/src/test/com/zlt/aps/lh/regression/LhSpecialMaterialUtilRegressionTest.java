package com.zlt.aps.lh.regression;

import com.zlt.aps.lh.api.domain.dto.SkuScheduleDTO;
import com.zlt.aps.lh.api.domain.dto.SpecialMaterialMatchResult;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.util.LhSpecialMaterialUtil;
import org.junit.jupiter.api.Test;

import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 特殊材料判定回归测试。
 */
class LhSpecialMaterialUtilRegressionTest {

    @Test
    void resolveMatchResult_shouldPreferMaterialCodeBeforeStructureName() {
        LhScheduleContext context = buildContext();
        context.getSpecialMaterialCategoryByMaterialCode().put("MAT-001", "02");
        context.getSpecialMaterialCategoryByStructureName().put("STRUCT-A", "01");

        SpecialMaterialMatchResult result = LhSpecialMaterialUtil.resolveMatchResult(
                context, sku("MAT-001", "STRUCT-A"));

        assertTrue(result.isSpecial());
        assertEquals("02", result.getCategory());
        assertEquals(SpecialMaterialMatchResult.MATCH_SOURCE_MATERIAL_CODE, result.getMatchSource());
        assertEquals("1", LhSpecialMaterialUtil.resolveHasSpecialMaterial(context, sku("MAT-001", "STRUCT-A")));
    }

    @Test
    void resolveMatchResult_shouldUseStructureNameWhenMaterialCodeMissing() {
        LhScheduleContext context = buildContext();
        context.getSpecialMaterialCategoryByStructureName().put("STRUCT-A", "03");

        SpecialMaterialMatchResult result = LhSpecialMaterialUtil.resolveMatchResult(
                context, sku("MAT-002", "STRUCT-A"));

        assertTrue(result.isSpecial());
        assertEquals("03", result.getCategory());
        assertEquals(SpecialMaterialMatchResult.MATCH_SOURCE_STRUCTURE_NAME, result.getMatchSource());
    }

    @Test
    void resolveMatchResult_shouldIgnoreInvalidCategory() {
        LhScheduleContext context = buildContext();
        context.getSpecialMaterialCategoryByMaterialCode().put("MAT-001", "99");

        SpecialMaterialMatchResult result = LhSpecialMaterialUtil.resolveMatchResult(
                context, sku("MAT-001", "STRUCT-A"));

        assertFalse(result.isSpecial());
        assertEquals("0", LhSpecialMaterialUtil.resolveHasSpecialMaterial(context, sku("MAT-001", "STRUCT-A")));
    }

    private LhScheduleContext buildContext() {
        LhScheduleContext context = new LhScheduleContext();
        context.setSpecialMaterialCategoryByMaterialCode(new HashMap<String, String>());
        context.setSpecialMaterialCategoryByStructureName(new HashMap<String, String>());
        return context;
    }

    private SkuScheduleDTO sku(String materialCode, String structureName) {
        SkuScheduleDTO sku = new SkuScheduleDTO();
        sku.setMaterialCode(materialCode);
        sku.setStructureName(structureName);
        return sku;
    }
}
