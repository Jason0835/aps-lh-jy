package com.zlt.aps.lh.engine.strategy.impl;

import com.zlt.aps.lh.api.constant.LhScheduleParamConstant;
import com.zlt.aps.lh.api.domain.entity.LhScheduleResult;
import com.zlt.aps.lh.context.LhScheduleConfig;
import com.zlt.aps.lh.context.LhScheduleContext;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * 试制量试排产策略测试。
 */
public class DefaultTrialProductionStrategyTest {

    /**
     * 用例说明：同一试制物料已占用当日名额时，后续同物料月计划行不应再被“不同物料数上限”误拦截。
     */
    @Test
    public void shouldNotTreatSameMaterialAsNewTrialSlot() {
        DefaultTrialProductionStrategy strategy = new DefaultTrialProductionStrategy();
        LhScheduleContext context = new LhScheduleContext();
        Map<String, String> paramMap = new HashMap<>(1);
        paramMap.put(LhScheduleParamConstant.TRIAL_DAILY_LIMIT, "1");
        context.setScheduleConfig(new LhScheduleConfig(paramMap));

        Date targetDate = date(2026, 5, 9);
        LhScheduleResult result = new LhScheduleResult();
        result.setIsTrial("1");
        result.setMaterialCode("MAT-TRIAL");
        result.setScheduleDate(targetDate);
        context.setScheduleResultList(Collections.singletonList(result));

        Assertions.assertFalse(strategy.isDailyTrialLimitReached(context, targetDate, "MAT-TRIAL"));
        Assertions.assertTrue(strategy.isDailyTrialLimitReached(context, targetDate, "MAT-OTHER"));
    }

    private Date date(int year, int month, int day) {
        Calendar calendar = Calendar.getInstance();
        calendar.clear();
        calendar.set(Calendar.YEAR, year);
        calendar.set(Calendar.MONTH, month - 1);
        calendar.set(Calendar.DAY_OF_MONTH, day);
        return calendar.getTime();
    }
}
