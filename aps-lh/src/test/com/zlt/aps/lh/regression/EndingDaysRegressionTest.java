package com.zlt.aps.lh.regression;

import com.zlt.aps.lh.api.constant.LhScheduleParamConstant;
import com.zlt.aps.lh.api.domain.dto.SkuScheduleDTO;
import com.zlt.aps.lh.component.TargetScheduleQtyResolver;
import com.zlt.aps.lh.context.LhScheduleConfig;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.engine.strategy.impl.DefaultEndingJudgmentStrategy;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 收尾天数折算边界回归。
 */
class EndingDaysRegressionTest {

    private final DefaultEndingJudgmentStrategy strategy = strategyWithCapacity(0);

    @Test
    void calculateEndingDays_usesCeilingAcrossThreeShiftsPerDay() {
        LhScheduleContext context = new LhScheduleContext();
        assertEquals(1, strategy.calculateEndingDays(context, sku(10, 10)));
        assertEquals(1, strategy.calculateEndingDays(context, sku(20, 10)));
        assertEquals(1, strategy.calculateEndingDays(context, sku(30, 10)));
        assertEquals(2, strategy.calculateEndingDays(context, sku(40, 10)));
    }

    @Test
    void isEnding_shouldUseTargetScheduleQtyInsteadOfSurplusQty() {
        DefaultEndingJudgmentStrategy strategy = strategyWithCapacity(90);
        LhScheduleContext context = new LhScheduleContext();
        context.setScheduleDate(new java.util.Date());
        SkuScheduleDTO dto = new SkuScheduleDTO();
        dto.setSurplusQty(10);
        dto.setPendingQty(10);
        dto.setTargetScheduleQty(120);
        dto.setShiftCapacity(10);
        dto.setDailyCapacity(90);

        assertFalse(strategy.isEnding(context, dto), "目标量已超过窗口总产能时，不应仅因余量较小而误判收尾");
        assertEquals(12, strategy.calculateEndingShifts(context, dto));
    }

    @Test
    void isEnding_shouldNotTreatFullCapacityWindowTargetAsEnding() {
        DefaultEndingJudgmentStrategy strategy = strategyWithCapacity(128);
        LhScheduleContext context = new LhScheduleContext();
        context.setScheduleDate(new java.util.Date());
        context.setScheduleConfig(new LhScheduleConfig(Collections.singletonMap(
                LhScheduleParamConstant.ENABLE_FULL_CAPACITY_SCHEDULING, "1")));
        SkuScheduleDTO dto = new SkuScheduleDTO();
        dto.setTargetScheduleQty(128);
        dto.setShiftCapacity(16);
        dto.setDailyCapacity(128);

        assertFalse(strategy.isEnding(context, dto), "满排模式下窗口产能封顶值不应直接触发收尾规则2");
        assertEquals(8, strategy.calculateEndingShifts(context, dto));
    }

    @Test
    void isEnding_fullCapacityMode_shouldUseMaxDemandQtyWhenSwitchEnabled() {
        DefaultEndingJudgmentStrategy strategy = strategyWithCapacity(160);
        LhScheduleContext context = new LhScheduleContext();
        context.setScheduleDate(new java.util.Date());
        context.setScheduleConfig(new LhScheduleConfig(Collections.singletonMap(
                LhScheduleParamConstant.ENABLE_FULL_CAPACITY_SCHEDULING, "1")));
        SkuScheduleDTO dto = new SkuScheduleDTO();
        dto.setTargetScheduleQty(160);
        dto.setSurplusQty(80);
        dto.setEmbryoStock(120);
        dto.setShiftCapacity(20);
        dto.setDailyCapacity(62);

        assertTrue(strategy.isEnding(context, dto), "满排模式启用开关后，应按max(余量,库存)与窗口产能比较命中规则2");
    }

    @Test
    void isEnding_fullCapacityMode_shouldSkipRule2WhenSwitchDisabled() {
        DefaultEndingJudgmentStrategy strategy = strategyWithCapacity(160);
        LhScheduleContext context = new LhScheduleContext();
        context.setScheduleDate(new java.util.Date());
        Map<String, String> paramMap = new HashMap<>(4);
        paramMap.put(LhScheduleParamConstant.ENABLE_FULL_CAPACITY_SCHEDULING, "1");
        paramMap.put(LhScheduleParamConstant.ENABLE_ENDING_BY_SURPLUS_IN_FULL_MODE, "0");
        context.setScheduleConfig(new LhScheduleConfig(paramMap));
        SkuScheduleDTO dto = new SkuScheduleDTO();
        dto.setTargetScheduleQty(160);
        dto.setSurplusQty(120);
        dto.setShiftCapacity(20);
        dto.setDailyCapacity(62);

        assertFalse(strategy.isEnding(context, dto), "满排模式关闭开关后，规则2应继续跳过");
    }

    @Test
    void isEnding_fullCapacityMode_shouldNotTreatLargeSurplusAsEndingWhenActualWindowCapacityIsInsufficient() {
        DefaultEndingJudgmentStrategy strategy = strategyWithCapacity(800);
        LhScheduleContext context = new LhScheduleContext();
        context.setScheduleDate(new java.util.Date());
        context.setScheduleConfig(new LhScheduleConfig(Collections.singletonMap(
                LhScheduleParamConstant.ENABLE_FULL_CAPACITY_SCHEDULING, "1")));
        SkuScheduleDTO dto = new SkuScheduleDTO();
        dto.setMaterialCode("3302002177");
        dto.setTargetScheduleQty(150);
        dto.setPendingQty(1140);
        dto.setSurplusQty(1140);
        dto.setShiftCapacity(17);
        dto.setDailyCapacity(51);
        dto.setWindowPlanQty(150);
        dto.setWindowRemainingPlanQty(150);

        assertFalse(strategy.isEnding(context, dto), "真实窗口产能无法覆盖硫化余量时，不应提前判收尾");
    }

    @Test
    void isEnding_fullCapacityMode_shouldUseActualWindowCapacityWhenWindowRemainingPlanIsSmallerThanSurplus() {
        DefaultEndingJudgmentStrategy strategy = strategyWithCapacity(112);
        LhScheduleContext context = new LhScheduleContext();
        context.setScheduleDate(new java.util.Date());
        context.setScheduleConfig(new LhScheduleConfig(Collections.singletonMap(
                LhScheduleParamConstant.ENABLE_FULL_CAPACITY_SCHEDULING, "1")));
        SkuScheduleDTO dto = new SkuScheduleDTO();
        dto.setMaterialCode("3302002169");
        dto.setTargetScheduleQty(128);
        dto.setPendingQty(102);
        dto.setSurplusQty(102);
        dto.setShiftCapacity(16);
        dto.setDailyCapacity(46);
        dto.setWindowPlanQty(100);
        dto.setWindowRemainingPlanQty(100);

        assertTrue(strategy.isEnding(context, dto), "满排按余量判收尾时，应按真实窗口产能覆盖硫化余量判收尾");
    }

    @Test
    void isEnding_fullCapacityMode_shouldKeepEndingWhenWindowRemainingPlanCanCoverDemand() {
        DefaultEndingJudgmentStrategy strategy = strategyWithCapacity(200);
        LhScheduleContext context = new LhScheduleContext();
        context.setScheduleDate(new java.util.Date());
        context.setScheduleConfig(new LhScheduleConfig(Collections.singletonMap(
                LhScheduleParamConstant.ENABLE_FULL_CAPACITY_SCHEDULING, "1")));
        SkuScheduleDTO dto = new SkuScheduleDTO();
        dto.setMaterialCode("3302001724");
        dto.setTargetScheduleQty(120);
        dto.setPendingQty(120);
        dto.setSurplusQty(120);
        dto.setShiftCapacity(20);
        dto.setDailyCapacity(80);
        dto.setWindowPlanQty(120);
        dto.setWindowRemainingPlanQty(120);

        assertTrue(strategy.isEnding(context, dto), "窗口剩余额度已覆盖收尾需求时，仍应保持收尾判定");
    }

    private SkuScheduleDTO sku(int pendingQty, int shiftCapacity) {
        SkuScheduleDTO dto = new SkuScheduleDTO();
        dto.setPendingQty(pendingQty);
        dto.setTargetScheduleQty(pendingQty);
        dto.setShiftCapacity(shiftCapacity);
        return dto;
    }

    private static DefaultEndingJudgmentStrategy strategyWithCapacity(int totalAvailableCapacity) {
        DefaultEndingJudgmentStrategy strategy = new DefaultEndingJudgmentStrategy();
        TargetScheduleQtyResolver resolver = new TargetScheduleQtyResolver() {
            @Override
            public int calcSkuTotalAvailableCapacityInWindow(LhScheduleContext context, SkuScheduleDTO sku) {
                return totalAvailableCapacity;
            }
        };
        ReflectionTestUtils.setField(strategy, "targetScheduleQtyResolver", resolver);
        return strategy;
    }
}
