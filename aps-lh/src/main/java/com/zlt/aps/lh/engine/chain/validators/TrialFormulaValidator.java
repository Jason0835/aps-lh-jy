package com.zlt.aps.lh.engine.chain.validators;

import com.zlt.aps.lh.api.constant.LhDataValidationGroupConstant;
import com.zlt.aps.lh.api.enums.ConstructionStageEnum;
import com.zlt.aps.lh.api.enums.ValidationPolicyEnum;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.engine.chain.IDataValidator;
import com.zlt.aps.mp.api.domain.entity.FactoryMonthPlanProductionFinalResult;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 试制量试示方完整性校验器。
 */
@Slf4j
@Component
public class TrialFormulaValidator implements IDataValidator {

    private static final String VALIDATOR_KEY = "trialFormulaValidator";

    @Override
    public boolean validate(LhScheduleContext context) {
        if (Objects.isNull(context) || CollectionUtils.isEmpty(context.getMonthPlanList())) {
            return true;
        }
        boolean passed = true;
        for (FactoryMonthPlanProductionFinalResult plan : context.getMonthPlanList()) {
            if (!isEffectiveTrialPlan(plan)) {
                continue;
            }
            List<String> missingFieldList = resolveMissingFormulaFields(plan);
            if (CollectionUtils.isEmpty(missingFieldList)) {
                continue;
            }
            passed = false;
            String missingFieldText = String.join("、", missingFieldList);
            log.warn("试制量试物料示方缺失, materialCode: {}, missingFields: {}",
                    plan.getMaterialCode(), missingFieldText);
            context.addValidationError("[" + getValidatorName() + "] 试制/量试物料 "
                    + plan.getMaterialCode() + " 缺少" + missingFieldText);
        }
        return passed;
    }

    /**
     * 判断是否为有效试制/量试月计划。
     *
     * @param plan 月计划
     * @return true-需要校验
     */
    private boolean isEffectiveTrialPlan(FactoryMonthPlanProductionFinalResult plan) {
        if (Objects.isNull(plan) || Objects.isNull(plan.getTrialQty()) || plan.getTrialQty() <= 0) {
            return false;
        }
        return StringUtils.equals(ConstructionStageEnum.TRIAL.getCode(), plan.getConstructionStage())
                || StringUtils.equals(ConstructionStageEnum.MASS_TRIAL.getCode(), plan.getConstructionStage());
    }

    /**
     * 解析缺失示方字段。
     *
     * @param plan 月计划
     * @return 缺失字段名称
     */
    private List<String> resolveMissingFormulaFields(FactoryMonthPlanProductionFinalResult plan) {
        List<String> missingFieldList = new ArrayList<>(3);
        if (StringUtils.isEmpty(plan.getEmbryoNo())) {
            missingFieldList.add("制造示方");
        }
        if (StringUtils.isEmpty(plan.getTextNo())) {
            missingFieldList.add("文字示方");
        }
        if (StringUtils.isEmpty(plan.getLhNo())) {
            missingFieldList.add("硫化示方");
        }
        return missingFieldList;
    }

    @Override
    public String getValidatorName() {
        return "试制量试示方校验";
    }

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
        return 20;
    }
}
