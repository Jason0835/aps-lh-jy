package com.zlt.aps.lh.engine.strategy;

import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.api.domain.dto.SkuScheduleDTO;

import java.util.Date;
import java.util.List;

/**
 * 试制/量试排产策略
 * <p>处理试制量试的特殊排产规则：单日上限、指定机台、周日禁排等</p>
 */
public interface ITrialProductionStrategy {
    List<SkuScheduleDTO> filterTrialSkus(LhScheduleContext context, List<SkuScheduleDTO> allSkus);

    boolean canScheduleTrialOnDate(LhScheduleContext context, Date targetDate);

    boolean canScheduleTrialSkuOnDate(LhScheduleContext context, SkuScheduleDTO trialSku, Date targetDate);

    default boolean isDailyTrialLimitReached(LhScheduleContext context, Date targetDate) {
        return isDailyTrialLimitReached(context, targetDate, null);
    }

    boolean isDailyTrialLimitReached(LhScheduleContext context, Date targetDate, String materialCode);

    String matchTrialMachine(LhScheduleContext context, SkuScheduleDTO trialSku);
}
