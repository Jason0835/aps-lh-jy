package com.zlt.aps.lh.regression;

import com.zlt.aps.lh.api.constant.LhScheduleParamConstant;
import com.zlt.aps.lh.api.domain.dto.MachineCleaningWindowDTO;
import com.zlt.aps.lh.api.domain.dto.MachineScheduleDTO;
import com.zlt.aps.lh.api.domain.dto.SkuScheduleDTO;
import com.zlt.aps.lh.api.enums.CleaningTypeEnum;
import com.zlt.aps.lh.component.TargetScheduleQtyResolver;
import com.zlt.aps.lh.context.LhScheduleConfig;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.engine.strategy.IMachineMatchStrategy;
import com.zlt.aps.lh.engine.strategy.impl.DefaultProductionShutdownStrategy;
import com.zlt.aps.lh.util.LhScheduleTimeUtil;
import com.zlt.aps.mdm.api.domain.entity.MdmWorkCalendar;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void evaluateStructureEndingCapacity_shouldDeductMouldChangeFromFiveDayEffectiveCapacity() {
        TargetScheduleQtyResolver resolver = new TargetScheduleQtyResolver();
        ReflectionTestUtils.setField(resolver, "machineMatchStrategy", fixedMachineMatch(machine("K1002", "OTHER")));
        LhScheduleContext context = new LhScheduleContext();
        context.setScheduleDate(date(2026, 5, 1));
        context.setScheduleWindowShifts(LhScheduleTimeUtil.buildDefaultScheduleShifts(context, context.getScheduleDate()));
        context.setScheduleConfig(createConfig("0", "5"));
        SkuScheduleDTO sku = new SkuScheduleDTO();
        sku.setMaterialCode("3302002357");
        sku.setSurplusQty(290);
        sku.setShiftCapacity(22);

        TargetScheduleQtyResolver.StructureEndingCapacitySnapshot snapshot =
                resolver.evaluateStructureEndingCapacity(context, sku);

        assertEquals(14, snapshot.getTheoreticalShiftCount(), "5天理论班次数应覆盖 14 个班次");
        assertEquals(1, snapshot.getDeductedChangeoverShiftCount(), "首次换模应折算扣减 1 个班次");
        assertEquals(13, snapshot.getEffectiveShiftCount(), "扣除换模后仅剩 13 个有效班次");
        assertEquals(286, snapshot.getEffectiveCapacityQty(), "有效产能应按 22 * 13 收敛");
        assertEquals(6, snapshot.getEndingDaysWithinStructureWindow(), "5天内无法收尾时应返回阈值外天数");
        assertFalse(snapshot.isHitStructureEnding(), "286 < 290 时不应命中结构五天内收尾");
    }

    @Test
    void evaluateStructureEndingCapacity_shouldNotRepeatDeductChangeoverForContinuousMachine() {
        TargetScheduleQtyResolver resolver = new TargetScheduleQtyResolver();
        ReflectionTestUtils.setField(resolver, "machineMatchStrategy", fixedMachineMatch(machine("K1002", "3302002357")));
        LhScheduleContext context = new LhScheduleContext();
        context.setScheduleDate(date(2026, 5, 1));
        context.setScheduleWindowShifts(LhScheduleTimeUtil.buildDefaultScheduleShifts(context, context.getScheduleDate()));
        context.setScheduleConfig(createConfig("0", "5"));
        SkuScheduleDTO sku = new SkuScheduleDTO();
        sku.setMaterialCode("3302002357");
        sku.setSurplusQty(286);
        sku.setShiftCapacity(22);

        TargetScheduleQtyResolver.StructureEndingCapacitySnapshot snapshot =
                resolver.evaluateStructureEndingCapacity(context, sku);

        assertEquals(14, snapshot.getTheoreticalShiftCount(), "续作场景应保留完整理论班次数");
        assertEquals(0, snapshot.getDeductedChangeoverShiftCount(), "续作场景不应重复扣减换模班次");
        assertEquals(14, snapshot.getEffectiveShiftCount(), "续作场景有效班次数不应减少");
        assertEquals(308, snapshot.getEffectiveCapacityQty(), "续作场景应保留完整有效产能");
        assertTrue(snapshot.isHitStructureEnding(), "有效产能覆盖余量时应命中结构五天内收尾");
    }

    @Test
    void canFinishSurplusInActualWindow_shouldDeductMachineCleaningWindow() {
        TargetScheduleQtyResolver resolver = new TargetScheduleQtyResolver();
        MachineScheduleDTO machine = machine("K1002", "3302002169");
        machine.getCleaningWindowList().add(cleaningWindow("K1002",
                dateTime(2026, 5, 1, 6), dateTime(2026, 5, 1, 14)));
        ReflectionTestUtils.setField(resolver, "machineMatchStrategy", fixedMachineMatch(machine));
        LhScheduleContext context = new LhScheduleContext();
        context.setScheduleDate(date(2026, 5, 1));
        context.setScheduleWindowShifts(LhScheduleTimeUtil.buildDefaultScheduleShifts(context, context.getScheduleDate()));
        context.setScheduleConfig(createConfig("1"));
        SkuScheduleDTO sku = new SkuScheduleDTO();
        sku.setMaterialCode("3302002169");
        sku.setSurplusQty(120);
        sku.setShiftCapacity(16);

        assertFalse(resolver.canFinishSurplusInActualWindow(context, sku),
                "真实窗口产能判断应扣除候选机台清洗窗口");
    }

    private static LhScheduleConfig createConfig(String fullCapacityMode) {
        return createConfig(fullCapacityMode, "5");
    }

    private static LhScheduleConfig createConfig(String fullCapacityMode, String structureEndingDays) {
        Map<String, String> paramMap = new HashMap<>(8);
        paramMap.put(LhScheduleParamConstant.ENABLE_FULL_CAPACITY_SCHEDULING, fullCapacityMode);
        paramMap.put(LhScheduleParamConstant.STRUCTURE_ENDING_DAYS, structureEndingDays);
        return new LhScheduleConfig(paramMap);
    }

    private static IMachineMatchStrategy fixedMachineMatch(MachineScheduleDTO machine) {
        return new IMachineMatchStrategy() {
            @Override
            public List<MachineScheduleDTO> matchMachines(LhScheduleContext context, SkuScheduleDTO sku) {
                return Collections.singletonList(machine);
            }

            @Override
            public MachineScheduleDTO selectBestMachine(LhScheduleContext context,
                                                        SkuScheduleDTO sku,
                                                        List<MachineScheduleDTO> candidates,
                                                        java.util.Set<String> excludedMachineCodes) {
                return machine;
            }
        };
    }

    private static MachineScheduleDTO machine(String machineCode, String previousMaterialCode) {
        MachineScheduleDTO machine = new MachineScheduleDTO();
        machine.setMachineCode(machineCode);
        machine.setPreviousMaterialCode(previousMaterialCode);
        return machine;
    }

    private static MachineCleaningWindowDTO cleaningWindow(String machineCode, java.util.Date startTime,
                                                           java.util.Date endTime) {
        MachineCleaningWindowDTO cleaningWindow = new MachineCleaningWindowDTO();
        cleaningWindow.setLhCode(machineCode);
        cleaningWindow.setCleanType(CleaningTypeEnum.SAND_BLAST.getCode());
        cleaningWindow.setCleanStartTime(startTime);
        cleaningWindow.setCleanEndTime(endTime);
        cleaningWindow.setReadyTime(endTime);
        return cleaningWindow;
    }

    private static java.util.Date date(int y, int month, int day) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(Calendar.YEAR, y);
        c.set(Calendar.MONTH, month - 1);
        c.set(Calendar.DAY_OF_MONTH, day);
        return c.getTime();
    }

    private static java.util.Date dateTime(int y, int month, int day, int hour) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(Calendar.YEAR, y);
        c.set(Calendar.MONTH, month - 1);
        c.set(Calendar.DAY_OF_MONTH, day);
        c.set(Calendar.HOUR_OF_DAY, hour);
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
