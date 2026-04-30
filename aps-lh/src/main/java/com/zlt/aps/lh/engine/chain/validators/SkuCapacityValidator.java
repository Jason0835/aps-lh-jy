package com.zlt.aps.lh.engine.chain.validators;

import com.zlt.aps.lh.api.constant.LhDataValidationGroupConstant;
import com.zlt.aps.lh.api.enums.ValidationPolicyEnum;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.engine.chain.IDataValidator;
import com.zlt.aps.mdm.api.domain.entity.MdmSkuLhCapacity;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * SKU日硫化产能校验器
 *
 * @author APS
 */
@Slf4j
@Component
public class SkuCapacityValidator implements IDataValidator {
    private static final String VALIDATOR_KEY = "skuCapacityValidator";
    private static final String SKU_DELIMITER = "、";
    private static final String INVALID_TIME_ERROR_TEMPLATE = "[%s] 物料编码 %s 对应的标准产能无效";

    @Override
    public boolean validate(LhScheduleContext context) {
        if (context.getSkuLhCapacityMap() == null || context.getSkuLhCapacityMap().isEmpty()) {
            log.warn("SKU日硫化产能数据为空, 工厂: {}", context.getFactoryCode());
            context.addValidationError("[" + getValidatorName() + "] SKU日硫化产能数据为空, 工厂: "
                    + context.getFactoryDisplayName());
            return false;
        }
        long missingCapacityCount = context.getMonthPlanList().stream()
                .filter(p -> p.getMaterialCode() != null
                        && !context.getSkuLhCapacityMap().containsKey(p.getMaterialCode()))
                .count();
        if (missingCapacityCount > 0) {
            log.warn("有{}个月计划SKU缺少硫化产能数据", missingCapacityCount);
            context.addValidationError("[" + getValidatorName() + "] 有 " + missingCapacityCount + " 个月计划SKU缺少硫化产能数据");
            return false;
        }
        // 仅校验月计划中出现的SKU，保持首现顺序用于稳定错误展示
        Set<String> monthPlanSkuSet = context.getMonthPlanList().stream()
                .map(p -> p.getMaterialCode())
                .filter(StringUtils::isNotEmpty)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        List<String> invalidSkuList = monthPlanSkuSet.stream()
                .filter(materialCode -> {
                    MdmSkuLhCapacity skuLhCapacity = context.getSkuLhCapacityMap().get(materialCode);
                    if (Objects.isNull(skuLhCapacity)) {
                        return true;
                    }
                    return Objects.isNull(skuLhCapacity.getStandardCapacity())
                            || skuLhCapacity.getStandardCapacity() <= 0;
                })
                .collect(Collectors.toList());
        if (!invalidSkuList.isEmpty()) {
            String invalidSkuText = String.join(SKU_DELIMITER, invalidSkuList);
            log.warn("月计划SKU标准产能无效, 工厂: {}, 物料编码: {}", context.getFactoryCode(), invalidSkuText);
            context.addValidationError(String.format(INVALID_TIME_ERROR_TEMPLATE, getValidatorName(), invalidSkuText));
            return false;
        }
        return true;
    }

    @Override
    public String getValidatorName() {
        return "SKU日硫化产能校验";
    }

    /**
     * 获取校验器唯一标识
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
        return 50;
    }
}
