package com.zlt.aps.lh.engine.chain.validators;

import com.zlt.aps.lh.api.constant.LhDataValidationGroupConstant;
import com.zlt.aps.lh.api.constant.LhScheduleConstant;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.api.enums.ValidationPolicyEnum;
import com.zlt.aps.lh.engine.chain.IDataValidator;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

/**
 * 工作日历校验器
 *
 * @author APS
 */
@Slf4j
@Component
public class WorkCalendarValidator implements IDataValidator {
    private static final String VALIDATOR_KEY = "workCalendarValidator";

    @Override
    public boolean validate(LhScheduleContext context) {
        if (context.getWorkCalendarList() == null || context.getWorkCalendarList().isEmpty()) {
            log.warn("工作日历数据为空, 工厂: {}", context.getFactoryCode());
            context.addValidationError("[" + getValidatorName() + "] 工作日历数据为空, 工厂: "
                    + context.getFactoryDisplayName());
            return false;
        }
        long lhCalendarCount = context.getWorkCalendarList().stream()
                // 必须存在硫化工序工作日历，否则后续班次管控无法可靠判断开停产。
                .filter(wc -> StringUtils.equals(LhScheduleConstant.PROC_CODE_LH, wc.getProcCode()))
                .count();
        if (lhCalendarCount == 0) {
            log.warn("工作日历中无硫化工序({})数据, 工厂: {}",
                    LhScheduleConstant.PROC_CODE_LH, context.getFactoryCode());
            context.addValidationError("[" + getValidatorName() + "] 工作日历中无硫化工序("
                    + LhScheduleConstant.PROC_CODE_LH + ")数据, 工厂: " + context.getFactoryDisplayName());
            return false;
        }
        log.info("工作日历校验通过, 硫化日历记录数: {}", lhCalendarCount);
        return true;
    }

    @Override
    public String getValidatorName() {
        return "工作日历校验";
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
        return 40;
    }
}
