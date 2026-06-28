package com.zlt.aps.lh.engine.chain.validators;

import com.zlt.aps.lh.api.constant.LhDataValidationGroupConstant;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.api.enums.ValidationPolicyEnum;
import com.zlt.aps.lh.engine.chain.IDataValidator;
import com.zlt.aps.lh.component.MonthPlanDateResolver;
import com.zlt.aps.lh.util.LhScheduleTimeUtil;
import com.zlt.aps.lh.util.MonthPlanDayQtyUtil;
import com.zlt.aps.lh.util.SkuConstructionRefResolverUtil;
import com.zlt.aps.mdm.api.domain.entity.MdmSkuConstructionRef;
import com.zlt.aps.mp.api.domain.entity.FactoryMonthPlanProductionFinalResult;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * SKU与示方书关系校验器
 * <p>按物料编码 + 产品状态查找示方书关系（支持降级匹配），校验 lhNo 和 lhType 是否为空。</p>
 * <p>降级规则：正规(S)→量试(T)→试制(X)；量试(T)→试制(X)；试制(X)不降级。</p>
 *
 * @author APS
 */
@Slf4j
@Component
public class SkuConstructionValidator implements IDataValidator {

    private static final String VALIDATOR_KEY = "skuConstructionValidator";

    @Override
    public boolean validate(LhScheduleContext context) {
        // 只校验月生产计划列表中的物料
        List<FactoryMonthPlanProductionFinalResult> monthPlanList = context.getMonthPlanList();
        if (CollectionUtils.isEmpty(monthPlanList)) {
            return true;
        }
        Map<String, MdmSkuConstructionRef> compositeKeyMap = context.getSkuConstructionRefCompositeKeyMap();
        if (CollectionUtils.isEmpty(compositeKeyMap)) {
            log.warn("SKU与示方书关系数据为空, 工厂: {}", context.getFactoryCode());
            context.addValidationError("[" + getValidatorName() + "] SKU与示方书关系数据为空, 工厂: "
                    + context.getFactoryDisplayName());
            return false;
        }
        // 遍历月计划物料，按物料编码+产品状态降级查找并校验 lhNo/lhType
        Map<String, String> missingFieldMap = new LinkedHashMap<>();
        for (FactoryMonthPlanProductionFinalResult plan : monthPlanList) {
            // 月计划排产量为空或为0的物料跳过校验
            if (plan.getTotalQty() == null || plan.getTotalQty() == 0) {
                continue;
            }
            // 排程窗口内所有日计划量均为0或空的物料跳过校验
            if (!hasWindowPlanQty(context, plan)) {
                continue;
            }
            String materialCode = plan.getMaterialCode();
            if (StringUtils.isEmpty(materialCode)) {
                continue;
            }
            String productStatus = plan.getProductStatus();
            String statusDesc = SkuConstructionRefResolverUtil.resolveProductStatusDesc(productStatus);
            // 使用降级匹配公共方法，与排程结果赋值逻辑保持一致
            MdmSkuConstructionRef ref = SkuConstructionRefResolverUtil.resolveCuringRecipeRef(
                    materialCode, productStatus, compositeKeyMap);
            if (Objects.isNull(ref)) {
                missingFieldMap.put(materialCode,
                        "产品状态:" + statusDesc + ", 未找到匹配硫化示方书号(已按降级规则查找)");
                continue;
            }
            // 校验 lhNo / lhType 是否为空
            if (StringUtils.isEmpty(ref.getLhNo()) && StringUtils.isEmpty(ref.getLhType())) {
                missingFieldMap.put(materialCode,
                        "产品状态:" + statusDesc + ", 硫化示方书号和硫化示方书类型均为空");
            } else if (StringUtils.isEmpty(ref.getLhNo())) {
                missingFieldMap.put(materialCode,
                        "产品状态:" + statusDesc + ", 硫化示方书号为空");
            } else if (StringUtils.isEmpty(ref.getLhType())) {
                missingFieldMap.put(materialCode,
                        "产品状态:" + statusDesc + ", 硫化示方书类型为空");
            }
        }
        if (!missingFieldMap.isEmpty()) {
            StringBuilder errorMsg = new StringBuilder("[").append(getValidatorName()).append("] ");
            errorMsg.append("硫化示方书数据不完整: ");
            for (Map.Entry<String, String> missingEntry : missingFieldMap.entrySet()) {
                errorMsg.append("[物料编码:").append(missingEntry.getKey())
                        .append(", ").append(missingEntry.getValue()).append("]; ");
            }
            String errorText = errorMsg.toString();
            log.warn("SKU与示方书关系校验失败, 工厂: {}, 异常物料数: {}, 详情: {}",
                    context.getFactoryCode(), missingFieldMap.size(), errorText);
            context.addValidationError(errorText);
            return false;
        }
        log.info("SKU与示方书关系校验通过, 月计划物料数: {}", monthPlanList.size());
        return true;
    }

    @Override
    public String getValidatorName() {
        return "SKU与示方书关系校验";
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
        return 25;
    }

    /**
     * 判断月计划在排程窗口内是否存在有效日计划量。
     * 跨月时自动查找对应月份的月计划记录。
     *
     * @param context 排程上下文
     * @param plan 当前月计划
     * @return true-窗口内存在有效计划量(&gt;0)，false-窗口内全部为0或null
     */
    private boolean hasWindowPlanQty(LhScheduleContext context, FactoryMonthPlanProductionFinalResult plan) {
        Date scheduleDate = context.getScheduleDate();
        Date windowEndDate = context.getWindowEndDate();
        if (Objects.isNull(scheduleDate) || Objects.isNull(windowEndDate)) {
            return true;
        }
        Calendar cursor = Calendar.getInstance();
        cursor.setTime(LhScheduleTimeUtil.clearTime(scheduleDate));
        Calendar endCal = Calendar.getInstance();
        endCal.setTime(LhScheduleTimeUtil.clearTime(windowEndDate));
        while (!cursor.after(endCal)) {
            int year = cursor.get(Calendar.YEAR);
            int month = cursor.get(Calendar.MONTH) + 1;
            int dayOfMonth = cursor.get(Calendar.DAY_OF_MONTH);
            // 如果遍历到不同月份，从跨月索引中查找对应月份的计划
            FactoryMonthPlanProductionFinalResult targetPlan = plan;
            if (Objects.nonNull(plan.getYear()) && Objects.nonNull(plan.getMonth())
                    && (plan.getYear().intValue() != year || plan.getMonth().intValue() != month)) {
                String key = MonthPlanDateResolver.buildMaterialMonthKey(plan.getMaterialCode(), year, month);
                targetPlan = context.getMonthPlanByMaterialMonthMap().get(key);
            }
            if (Objects.nonNull(targetPlan)) {
                int dayQty = MonthPlanDayQtyUtil.resolveDayQty(targetPlan, dayOfMonth);
                if (dayQty > 0) {
                    return true;
                }
            }
            cursor.add(Calendar.DAY_OF_MONTH, 1);
        }
        return false;
    }
}
