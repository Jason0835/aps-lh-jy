package com.zlt.aps.lh.engine.chain.validators;

import com.zlt.aps.lh.api.domain.entity.LhSpecialMaterialBom;
import com.zlt.aps.lh.context.LhScheduleContext;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 特殊物料清单校验器测试。
 */
class SpecialMaterialBomValidatorTest {

    private final SpecialMaterialBomValidator validator = new SpecialMaterialBomValidator();

    @Test
    void validate_shouldFailWhenMaterialCodeCategoryConflicts() {
        LhScheduleContext context = buildContext();
        context.setSpecialMaterialBomList(Arrays.asList(
                bom(1L, "MAT-001", null, "01"),
                bom(2L, "MAT-001", null, "02")));

        boolean valid = validator.validate(context);

        assertFalse(valid);
        assertTrue(context.getValidationErrorList().stream()
                .anyMatch(error -> error.contains("物料编码存在重复分类配置") && error.contains("MAT-001")));
    }

    @Test
    void validate_shouldFailWhenStructureNameCategoryConflicts() {
        LhScheduleContext context = buildContext();
        context.setSpecialMaterialBomList(Arrays.asList(
                bom(1L, null, "STRUCT-A", "01"),
                bom(2L, null, "STRUCT-A", "03")));

        boolean valid = validator.validate(context);

        assertFalse(valid);
        assertTrue(context.getValidationErrorList().stream()
                .anyMatch(error -> error.contains("结构名称存在重复分类配置") && error.contains("STRUCT-A")));
    }

    @Test
    void validate_shouldPassWhenDuplicatedKeysShareSameCategory() {
        LhScheduleContext context = buildContext();
        context.setSpecialMaterialBomList(Arrays.asList(
                bom(1L, "MAT-001", null, "01"),
                bom(2L, "MAT-001", null, "01"),
                bom(3L, null, "STRUCT-A", "03"),
                bom(4L, null, "STRUCT-A", "03")));

        boolean valid = validator.validate(context);

        assertTrue(valid);
        assertTrue(context.getValidationErrorList().isEmpty());
    }

    private LhScheduleContext buildContext() {
        LhScheduleContext context = new LhScheduleContext();
        context.setFactoryCode("116");
        context.setFactoryName("测试工厂");
        context.setSpecialMaterialBomList(Collections.emptyList());
        return context;
    }

    private LhSpecialMaterialBom bom(Long id, String materialCode, String structureName, String category) {
        LhSpecialMaterialBom bom = new LhSpecialMaterialBom();
        bom.setId(id);
        bom.setMaterialCode(materialCode);
        bom.setStructureName(structureName);
        bom.setCategory(category);
        return bom;
    }
}
