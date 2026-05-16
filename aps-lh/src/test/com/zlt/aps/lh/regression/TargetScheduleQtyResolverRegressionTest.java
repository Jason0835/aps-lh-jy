package com.zlt.aps.lh.regression;

import com.zlt.aps.lh.api.constant.LhScheduleParamConstant;
import com.zlt.aps.lh.api.domain.dto.SkuScheduleDTO;
import com.zlt.aps.lh.component.TargetScheduleQtyResolver;
import com.zlt.aps.lh.context.LhScheduleConfig;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.engine.strategy.impl.DefaultProductionShutdownStrategy;
import com.zlt.aps.lh.util.LhScheduleTimeUtil;
import com.zlt.aps.mdm.api.domain.entity.MdmWorkCalendar;
import org.junit.jupiter.api.Test;

import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 排产目标量解析回归。
 */
class TargetScheduleQtyResolverRegressionTest {

    private final TargetScheduleQtyResolver resolver = new TargetScheduleQtyResolver();
    private final DefaultProductionShutdownStrategy productionShutdownStrategy = new DefaultProductionShutdownStrategy();

    @Test
    void resolveInitialTargetQty_shouldUsePendingQtyWhenStockExceedsSurplus() {
        LhScheduleContext context = new LhScheduleContext();
        context.setScheduleConfig(createConfig("0"));
        SkuScheduleDTO sku = new SkuScheduleDTO();
        sku.setSurplusQty(80);
        sku.setPendingQty(120);

        int targetQty = resolver.resolveInitialTargetQty(context, sku);

        assertEquals(120, targetQty, "按需求排产时应允许胎胚库存放大后的待排量生效");
    }

    @Test
    void resolveInitialTargetQty_fullCapacityModeShouldUseCapacityLimitAfterStockExpansion() {
        LhScheduleContext context = new LhScheduleContext();
        context.setScheduleDate(date(2026, 4, 22));
        context.setScheduleWindowShifts(LhScheduleTimeUtil.buildDefaultScheduleShifts(context, context.getScheduleDate()));
        context.setScheduleConfig(createConfig("1"));
        SkuScheduleDTO sku = new SkuScheduleDTO();
        sku.setSurplusQty(80);
        sku.setPendingQty(200);
        sku.setShiftCapacity(10);

        int targetQty = resolver.resolveInitialTargetQty(context, sku);

        assertEquals(80, targetQty, "满排模式应在库存放大后继续受窗口理论产能限制");
    }

    @Test
    void resolveInitialTargetQty_fullCapacityModeShouldDeductCalendarStoppedShift() {
        LhScheduleContext context = new LhScheduleContext();
        context.setScheduleDate(date(2026, 4, 22));
        context.setScheduleWindowShifts(LhScheduleTimeUtil.buildDefaultScheduleShifts(context, context.getScheduleDate()));
        context.setScheduleConfig(createConfig("1"));
        context.setWorkCalendarList(Collections.singletonList(calendar(2026, 4, 22, "1", "1", "0", "1", 100)));
        productionShutdownStrategy.prepareOpenStopContext(context);
        SkuScheduleDTO sku = new SkuScheduleDTO();
        sku.setPendingQty(200);
        sku.setShiftCapacity(10);

        int targetQty = resolver.resolveInitialTargetQty(context, sku);

        assertEquals(70, targetQty, "满排窗口理论产能应扣除工作日历停产班次");
    }

    @Test
    void upsizeEndingTargetQty_shouldIgnoreWindowPlanQtyForEndingSku() {
        LhScheduleContext context = new LhScheduleContext();
        context.setScheduleConfig(createConfig("0"));
        SkuScheduleDTO sku = new SkuScheduleDTO();
        sku.setMaterialCode("3302002216");
        sku.setTargetScheduleQty(16);
        sku.setPendingQty(16);
        sku.setSurplusQty(20);
        sku.setEmbryoStock(18);
        sku.setWindowPlanQty(16);
        sku.setWindowRemainingPlanQty(16);

        int targetQty = resolver.upsizeEndingTargetQty(context, sku);

        assertEquals(20, targetQty, "收尾SKU目标量应按硫化余量/胎胚库存取大，不再受窗口计划量限制");
        assertEquals(20, sku.resolveTargetScheduleQty(), "收尾目标量回写后应保持放大后的目标值");
    }

    @Test
    void upsizeEndingTargetQty_shouldNotReduceTargetByWindowCapacity() {
        TargetScheduleQtyResolver resolver = new TargetScheduleQtyResolver() {
            @Override
            public int calcSkuTotalAvailableCapacityInWindow(LhScheduleContext context, SkuScheduleDTO sku) {
                return 40;
            }
        };
        LhScheduleContext context = new LhScheduleContext();
        context.setScheduleConfig(createConfig("1"));
        SkuScheduleDTO sku = new SkuScheduleDTO();
        sku.setMaterialCode("3302002217");
        sku.setTargetScheduleQty(16);
        sku.setPendingQty(16);
        sku.setSurplusQty(46);
        sku.setEmbryoStock(50);
        sku.setWindowPlanQty(16);
        sku.setWindowRemainingPlanQty(16);

        int targetQty = resolver.upsizeEndingTargetQty(context, sku);

        assertEquals(50, targetQty, "收尾目标量应严格取max(余量,胎胚库存)，产能不足只能形成未排，不能压低目标");
        assertEquals(50, sku.resolveTargetScheduleQty(), "收尾目标量不应被窗口产能压低");
    }

    private static LhScheduleConfig createConfig(String fullCapacityMode) {
        Map<String, String> paramMap = new HashMap<>(4);
        paramMap.put(LhScheduleParamConstant.ENABLE_FULL_CAPACITY_SCHEDULING, fullCapacityMode);
        return new LhScheduleConfig(paramMap);
    }

    private static java.util.Date date(int y, int month, int day) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(Calendar.YEAR, y);
        c.set(Calendar.MONTH, month - 1);
        c.set(Calendar.DAY_OF_MONTH, day);
        return c.getTime();
    }

    private static MdmWorkCalendar calendar(int year, int month, int day, String dayFlag,
                                            String oneShiftFlag, String twoShiftFlag,
                                            String threeShiftFlag, Integer rate) {
        MdmWorkCalendar calendar = new MdmWorkCalendar();
        calendar.setProcCode("02");
        calendar.setYear(year);
        calendar.setMonth(month);
        calendar.setDay(day);
        calendar.setDayFlag(dayFlag);
        calendar.setOneShiftFlag(oneShiftFlag);
        calendar.setTwoShiftFlag(twoShiftFlag);
        calendar.setThreeShiftFlag(threeShiftFlag);
        calendar.setRate(rate);
        return calendar;
    }
}
