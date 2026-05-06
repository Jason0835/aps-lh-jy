package com.zlt.aps.lh.engine.chain.validators;

import com.zlt.aps.lh.api.constant.LhDataValidationGroupConstant;
import com.zlt.aps.lh.api.domain.entity.LhSpecialMaterialBom;
import com.zlt.aps.lh.api.enums.LhSpecialMaterialCategoryEnum;
import com.zlt.aps.lh.api.enums.ValidationPolicyEnum;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.engine.chain.IDataValidator;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 特殊物料清单配置校验器。
 *
 * @author APS
 */
@Slf4j
@Component
public class SpecialMaterialBomValidator implements IDataValidator {

    private static final String VALIDATOR_KEY = "specialMaterialBomValidator";
    private static final int MAX_ERROR_DETAIL_COUNT = 10;

    @Override
    public boolean validate(LhScheduleContext context) {
        if (CollectionUtils.isEmpty(context.getSpecialMaterialBomList())) {
            log.info("特殊物料清单校验通过, 配置数: 0");
            return true;
        }
        List<String> emptyKeyErrorList = new ArrayList<>(MAX_ERROR_DETAIL_COUNT);
        List<String> invalidCategoryErrorList = new ArrayList<>(MAX_ERROR_DETAIL_COUNT);
        List<String> duplicatedMaterialCategoryErrorList = new ArrayList<>(MAX_ERROR_DETAIL_COUNT);
        List<String> duplicatedStructureCategoryErrorList = new ArrayList<>(MAX_ERROR_DETAIL_COUNT);
        Map<String, String> materialCategoryMap = new HashMap<>(16);
        Map<String, Integer> materialFirstRowMap = new HashMap<>(16);
        Map<String, String> structureCategoryMap = new HashMap<>(16);
        Map<String, Integer> structureFirstRowMap = new HashMap<>(16);
        Set<String> materialConflictKeySet = new HashSet<>(8);
        Set<String> structureConflictKeySet = new HashSet<>(8);
        int rowIndex = 0;
        for (LhSpecialMaterialBom bom : context.getSpecialMaterialBomList()) {
            rowIndex++;
            if (Objects.isNull(bom)) {
                continue;
            }
            String materialCode = normalizeText(bom.getMaterialCode());
            String structureName = normalizeText(bom.getStructureName());
            if (StringUtils.isEmpty(materialCode) && StringUtils.isEmpty(structureName)) {
                addErrorDetail(emptyKeyErrorList, buildRowText(rowIndex, bom));
            }
            if (!LhSpecialMaterialCategoryEnum.isValid(bom.getCategory())) {
                addErrorDetail(invalidCategoryErrorList, buildRowText(rowIndex, bom));
                continue;
            }
            if (StringUtils.isNotEmpty(materialCode)) {
                collectCategoryConflict(duplicatedMaterialCategoryErrorList,
                        materialCategoryMap, materialFirstRowMap, materialConflictKeySet,
                        materialCode, bom.getCategory(), rowIndex, "物料编码");
            }
            if (StringUtils.isEmpty(materialCode) && StringUtils.isNotEmpty(structureName)) {
                collectCategoryConflict(duplicatedStructureCategoryErrorList,
                        structureCategoryMap, structureFirstRowMap, structureConflictKeySet,
                        structureName, bom.getCategory(), rowIndex, "结构名称");
            }
        }
        if (!CollectionUtils.isEmpty(emptyKeyErrorList)) {
            context.addValidationError("[" + getValidatorName()
                    + "] 特殊物料清单结构名称和物料编码至少填写一个: "
                    + String.join("；", emptyKeyErrorList));
        }
        if (!CollectionUtils.isEmpty(invalidCategoryErrorList)) {
            context.addValidationError("[" + getValidatorName()
                    + "] 特殊物料清单分类只能为01/02/03: "
                    + String.join("；", invalidCategoryErrorList));
        }
        if (!CollectionUtils.isEmpty(duplicatedMaterialCategoryErrorList)) {
            context.addValidationError("[" + getValidatorName()
                    + "] 物料编码存在重复分类配置: "
                    + String.join("；", duplicatedMaterialCategoryErrorList));
        }
        if (!CollectionUtils.isEmpty(duplicatedStructureCategoryErrorList)) {
            context.addValidationError("[" + getValidatorName()
                    + "] 结构名称存在重复分类配置: "
                    + String.join("；", duplicatedStructureCategoryErrorList));
        }
        boolean passed = CollectionUtils.isEmpty(emptyKeyErrorList)
                && CollectionUtils.isEmpty(invalidCategoryErrorList)
                && CollectionUtils.isEmpty(duplicatedMaterialCategoryErrorList)
                && CollectionUtils.isEmpty(duplicatedStructureCategoryErrorList);
        if (passed) {
            log.info("特殊物料清单校验通过, 配置数: {}", context.getSpecialMaterialBomList().size());
        } else {
            log.warn("特殊物料清单校验未通过, 空键错误: {}, 分类错误: {}, 物料编码冲突: {}, 结构名称冲突: {}",
                    emptyKeyErrorList.size(), invalidCategoryErrorList.size(),
                    duplicatedMaterialCategoryErrorList.size(), duplicatedStructureCategoryErrorList.size());
        }
        return passed;
    }

    @Override
    public String getValidatorName() {
        return "特殊物料清单校验";
    }

    /**
     * 获取校验器唯一标识。
     *
     * @return 校验器唯一标识
     */
    @Override
    public String getValidatorKey() {
        return VALIDATOR_KEY;
    }

    @Override
    public int getGroup() {
        return LhDataValidationGroupConstant.BASE_DATA_INTEGRITY;
    }

    @Override
    public ValidationPolicyEnum getValidationPolicy() {
        return ValidationPolicyEnum.COLLECT_ALL;
    }

    @Override
    public int getOrder() {
        return 35;
    }

    /**
     * 添加错误明细，控制单条错误长度。
     *
     * @param errorList 错误列表
     * @param errorDetail 错误明细
     */
    private void addErrorDetail(List<String> errorList, String errorDetail) {
        if (errorList.size() < MAX_ERROR_DETAIL_COUNT) {
            errorList.add(errorDetail);
        }
    }

    /**
     * 收集同键不同分类的冲突配置。
     *
     * @param errorList 冲突错误列表
     * @param categoryMap 首次出现的分类Map
     * @param firstRowMap 首次出现的行号Map
     * @param conflictKeySet 已记录冲突的键集合
     * @param key 当前命中键
     * @param category 当前分类
     * @param rowIndex 当前行号
     * @param keyLabel 键名称
     */
    private void collectCategoryConflict(List<String> errorList,
                                         Map<String, String> categoryMap,
                                         Map<String, Integer> firstRowMap,
                                         Set<String> conflictKeySet,
                                         String key,
                                         String category,
                                         int rowIndex,
                                         String keyLabel) {
        if (!categoryMap.containsKey(key)) {
            categoryMap.put(key, category);
            firstRowMap.put(key, rowIndex);
            return;
        }
        String firstCategory = categoryMap.get(key);
        if (!StringUtils.equals(firstCategory, category) && conflictKeySet.add(key)) {
            Integer firstRow = firstRowMap.get(key);
            addErrorDetail(errorList, keyLabel + "=" + key
                    + "(第" + firstRow + "条分类=" + firstCategory
                    + ", 第" + rowIndex + "条分类=" + category + ")");
        }
    }

    /**
     * 构建配置行描述。
     *
     * @param rowIndex 行号
     * @param bom 特殊物料清单配置
     * @return 配置行描述
     */
    private String buildRowText(int rowIndex, LhSpecialMaterialBom bom) {
        return "第" + rowIndex + "条"
                + "(物料编码=" + StringUtils.defaultString(bom.getMaterialCode())
                + ", 结构名称=" + StringUtils.defaultString(bom.getStructureName())
                + ", 分类=" + StringUtils.defaultString(bom.getCategory()) + ")";
    }

    /**
     * 清洗配置匹配文本。
     *
     * @param value 原始值
     * @return 清洗后文本
     */
    private String normalizeText(String value) {
        if (StringUtils.isEmpty(value)) {
            return null;
        }
        String trimValue = value.trim();
        return StringUtils.isEmpty(trimValue) ? null : trimValue;
    }
}
