package com.zlt.aps.lh.engine.strategy.impl;

import com.zlt.aps.lh.api.constant.LhScheduleConstant;
import com.zlt.aps.lh.api.constant.LhScheduleParamConstant;
import com.zlt.aps.lh.api.domain.entity.LhSpecifyMachine;
import com.zlt.aps.lh.api.enums.ConstructionStageEnum;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.api.domain.dto.SkuScheduleDTO;
import com.zlt.aps.lh.engine.strategy.ITrialProductionStrategy;
import com.zlt.aps.lh.util.LhScheduleTimeUtil;
import com.zlt.aps.lh.util.LhSpecifyMachineUtil;
import com.zlt.aps.lh.util.MachineStatusUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Slf4j
@Component
public class DefaultTrialProductionStrategy implements ITrialProductionStrategy {

    @Override
    public List<SkuScheduleDTO> filterTrialSkus(LhScheduleContext context, List<SkuScheduleDTO> allSkus) {
        // 筛选出施工阶段为试制(01)/量试(02)的SKU
        log.debug("筛选试制量试SKU");
        return allSkus.stream()
                .filter(Objects::nonNull)
                .filter(sku -> sku.isTrial() || isTrialStage(sku.getConstructionStage()))
                .collect(Collectors.toList());
    }

    @Override
    public boolean canScheduleTrialOnDate(LhScheduleContext context, Date targetDate) {
        if (Objects.isNull(targetDate)) {
            return false;
        }
        // 周日不排试制量试
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.setTime(targetDate);
        if (cal.get(java.util.Calendar.DAY_OF_WEEK) == java.util.Calendar.SUNDAY) {
            log.debug("周日不安排试制量试, 日期: {}", LhScheduleTimeUtil.formatDate(targetDate));
            return false;
        }
        return true;
    }

    @Override
    public boolean canScheduleTrialSkuOnDate(LhScheduleContext context, SkuScheduleDTO trialSku, Date targetDate) {
        if (!canScheduleTrialOnDate(context, targetDate)) {
            return false;
        }
        if (Objects.isNull(trialSku) || Objects.isNull(trialSku.getBeginDay()) || Objects.isNull(targetDate)) {
            return true;
        }
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.setTime(targetDate);
        if (cal.get(java.util.Calendar.DAY_OF_MONTH) == trialSku.getBeginDay()) {
            log.debug("结构起产日不安排试制量试, 物料: {}, 日期: {}",
                    trialSku.getMaterialCode(), LhScheduleTimeUtil.formatDate(targetDate));
            return false;
        }
        return true;
    }

    @Override
    public boolean isDailyTrialLimitReached(LhScheduleContext context, Date targetDate) {
        return isDailyTrialLimitReached(context, targetDate, null);
    }

    @Override
    public boolean isDailyTrialLimitReached(LhScheduleContext context, Date targetDate, String materialCode) {
        // 统计当日已排的不同试制量试物料数量
        if (Objects.isNull(targetDate)) {
            return true;
        }
        int limit = context.getParamIntValue(LhScheduleParamConstant.TRIAL_DAILY_LIMIT, LhScheduleConstant.TRIAL_DAILY_LIMIT);
        long trialCount = context.getScheduleResultList().stream()
                .filter(r -> "1".equals(r.getIsTrial()))
                .filter(r -> isSameDay(r.getScheduleDate(), targetDate))
                .map(r -> r.getMaterialCode())
                .filter(StringUtils::isNotEmpty)
                .distinct()
                .count();
        if (StringUtils.isNotEmpty(materialCode)) {
            boolean alreadyScheduled = context.getScheduleResultList().stream()
                    .filter(r -> "1".equals(r.getIsTrial()))
                    .filter(r -> isSameDay(r.getScheduleDate(), targetDate))
                    .anyMatch(r -> StringUtils.equals(materialCode, r.getMaterialCode()));
            if (alreadyScheduled) {
                return false;
            }
        }
        return trialCount >= limit;
    }

    @Override
    public String matchTrialMachine(LhScheduleContext context, SkuScheduleDTO trialSku) {
        // 试制量试优先匹配定点机台中的第一台可用机台
        if (StringUtils.isEmpty(trialSku.getMaterialCode())) {
            return null;
        }
        List<LhSpecifyMachine> specifyList = LhSpecifyMachineUtil.listLimitSpecifyMachinesByMaterialCode(
                context, trialSku.getMaterialCode());
        if (CollectionUtils.isEmpty(specifyList)) {
            return null;
        }
        // 按限制作业机台顺序选择第一台启用机台
        for (LhSpecifyMachine specify : specifyList) {
            for (String runtimeMachineCode
                    : Collections.singletonList(specify.getMachineCode())) {
                com.zlt.aps.lh.api.domain.dto.MachineScheduleDTO machine =
                        context.getMachineScheduleMap().get(runtimeMachineCode);
                if (Objects.nonNull(machine) && MachineStatusUtil.isEnabled(machine.getStatus())) {
                    log.debug("试制量试机台匹配, SKU: {}, 机台: {}", trialSku.getMaterialCode(), runtimeMachineCode);
                    return runtimeMachineCode;
                }
            }
        }
        return null;
    }

    /**
     * 判断施工阶段是否为试制/量试。
     *
     * @param constructionStage 施工阶段
     * @return true-试制/量试
     */
    private boolean isTrialStage(String constructionStage) {
        return StringUtils.equals(ConstructionStageEnum.TRIAL.getCode(), constructionStage)
                || StringUtils.equals(ConstructionStageEnum.MASS_TRIAL.getCode(), constructionStage);
    }

    private boolean isSameDay(java.util.Date d1, java.util.Date d2) {
        if (Objects.isNull(d1) || Objects.isNull(d2)) {
            return false;
        }
        java.util.Calendar c1 = java.util.Calendar.getInstance();
        java.util.Calendar c2 = java.util.Calendar.getInstance();
        c1.setTime(d1);
        c2.setTime(d2);
        return c1.get(java.util.Calendar.YEAR) == c2.get(java.util.Calendar.YEAR)
                && c1.get(java.util.Calendar.DAY_OF_YEAR) == c2.get(java.util.Calendar.DAY_OF_YEAR);
    }
}
