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
        List<String> wideBaseConflictErrorList = new ArrayList<>(MAX_ERROR_DETAIL_COUNT);
        Map<String, Set<String>> materialCategoryMap = new HashMap<>(16);
        Map<String, Integer> materialWideBase195FirstRowMap = new HashMap<>(16);
        Map<String, Integer> materialWideBase225FirstRowMap = new HashMap<>(16);
        Set<String> materialConflictKeySet = new HashSet<>(8);
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
                collectWideBaseConflict(wideBaseConflictErrorList, materialCategoryMap,
                        materialWideBase195FirstRowMap, materialWideBase225FirstRowMap,
                        materialConflictKeySet, materialCode, bom.getCategory(), rowIndex);
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
        if (!CollectionUtils.isEmpty(wideBaseConflictErrorList)) {
            context.addValidationError("[" + getValidatorName()
                    + "] 同一物料编码不能同时配置19.5寸宽基和22.5寸宽基: "
                    + String.join("；", wideBaseConflictErrorList));
        }
        boolean passed = CollectionUtils.isEmpty(emptyKeyErrorList)
                && CollectionUtils.isEmpty(invalidCategoryErrorList)
                && CollectionUtils.isEmpty(wideBaseConflictErrorList);
        if (passed) {
            log.info("特殊物料清单校验通过, 配置数: {}", context.getSpecialMaterialBomList().size());
        } else {
            log.warn("特殊物料清单校验未通过, 空键错误: {}, 分类错误: {}, 物料宽基冲突: {}",
                    emptyKeyErrorList.size(), invalidCategoryErrorList.size(),
                    wideBaseConflictErrorList.size());
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
     * 收集同一物料编码下 19.5 寸宽基与 22.5 寸宽基互斥冲突。
     *
     * @param errorList 冲突错误列表
     * @param categoryMap 已命中的分类集合
     * @param first195RowMap 首次出现 01 的行号Map
     * @param first225RowMap 首次出现 02 的行号Map
     * @param conflictKeySet 已记录冲突的键集合
     * @param key 当前命中键
     * @param category 当前分类
     * @param rowIndex 当前行号
     */
    private void collectWideBaseConflict(List<String> errorList,
                                         Map<String, Set<String>> categoryMap,
                                         Map<String, Integer> first195RowMap,
                                         Map<String, Integer> first225RowMap,
                                         Set<String> conflictKeySet,
                                         String key,
                                         String category,
                                         int rowIndex) {
        Set<String> categorySet = categoryMap.computeIfAbsent(key, value -> new HashSet<String>(4));
        categorySet.add(category);
        if (StringUtils.equals(LhSpecialMaterialCategoryEnum.WIDE_BASE_195.getCode(), category)) {
            first195RowMap.putIfAbsent(key, rowIndex);
        }
        if (StringUtils.equals(LhSpecialMaterialCategoryEnum.WIDE_BASE_225.getCode(), category)) {
            first225RowMap.putIfAbsent(key, rowIndex);
        }
        if (!categorySet.contains(LhSpecialMaterialCategoryEnum.WIDE_BASE_195.getCode())
                || !categorySet.contains(LhSpecialMaterialCategoryEnum.WIDE_BASE_225.getCode())
                || !conflictKeySet.add(key)) {
            return;
        }
        Integer first195Row = first195RowMap.get(key);
        Integer first225Row = first225RowMap.get(key);
        addErrorDetail(errorList, "物料编码=" + key
                + "(第" + first195Row + "条分类="
                + LhSpecialMaterialCategoryEnum.WIDE_BASE_195.getCode()
                + ", 第" + first225Row + "条分类="
                + LhSpecialMaterialCategoryEnum.WIDE_BASE_225.getCode() + ")");
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
