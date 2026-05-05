package com.zlt.aps.lh.regression;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.zlt.aps.lh.api.constant.LhScheduleParamConstant;
import com.zlt.aps.lh.api.domain.dto.SkuScheduleDTO;
import com.zlt.aps.lh.api.domain.dto.MachineScheduleDTO;
import com.zlt.aps.lh.api.domain.entity.LhMachineOnlineInfo;
import com.zlt.aps.lh.api.domain.entity.LhScheduleResult;
import com.zlt.aps.lh.api.domain.entity.LhUnscheduledResult;
import com.zlt.aps.lh.api.enums.ScheduleTypeEnum;
import com.zlt.aps.lh.context.LhScheduleConfig;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.engine.strategy.impl.DefaultEndingJudgmentStrategy;
import com.zlt.aps.lh.handler.ScheduleAdjustHandler;
import com.zlt.aps.lh.util.LhScheduleTimeUtil;
import com.zlt.aps.lh.util.ShiftFieldUtil;
import com.zlt.aps.mdm.api.domain.entity.MdmSkuLhCapacity;
import com.zlt.aps.mp.api.domain.entity.FactoryMonthPlanProductionFinalResult;
import com.zlt.aps.mp.api.domain.entity.MpAdjustResult;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 欠产传导：前一日净欠产应进入当天需求对象，而不是回写昨天计划。
 */
class ScheduleAdjustCarryForwardRegressionTest {

    private final ScheduleAdjustHandler handler = new ScheduleAdjustHandler();

    @Test
    void doHandle_carriesForwardDeficitIntoTodayPendingQty() {
        ReflectionTestUtils.setField(handler, "endingJudgmentStrategy", new DefaultEndingJudgmentStrategy());

        LhScheduleContext context = new LhScheduleContext();
        context.setScheduleConfig(createConfig("0"));
        context.setScheduleDate(date(2026, 4, 11));
        context.setScheduleTargetDate(date(2026, 4, 13));
        context.setScheduleWindowShifts(LhScheduleTimeUtil.buildDefaultScheduleShifts(context, context.getScheduleDate()));

        FactoryMonthPlanProductionFinalResult plan = new FactoryMonthPlanProductionFinalResult();
        plan.setMaterialCode("MAT-1");
        plan.setMaterialDesc("MAT-1-DESC");
        plan.setStructureName("S1");
        plan.setSpecifications("SPEC-1");
        plan.setProSize("18");
        plan.setTotalQty(1000);
        plan.setDayVulcanizationQty(120);
        plan.setDay11(40);
        plan.setDay12(40);
        plan.setDay13(20);
        context.setMonthPlanList(Collections.singletonList(plan));

        MdmSkuLhCapacity capacity = new MdmSkuLhCapacity();
        capacity.setMaterialCode("MAT-1");
        capacity.setClassCapacity(30);
        capacity.setApsCapacity(90);
        context.getSkuLhCapacityMap().put("MAT-1", capacity);

        LhScheduleResult previous = new LhScheduleResult();
        previous.setLhMachineCode("M1");
        previous.setMaterialCode("MAT-1");
        previous.setClass1PlanQty(80);
        context.setPreviousScheduleResultList(Collections.singletonList(previous));

        context.getMaterialDayFinishedQtyMap().put("MAT-1_2026-04-12", 60);
        context.getMaterialMonthFinishedQtyMap().put("MAT-1", 60);

        ReflectionTestUtils.invokeMethod(handler, "doHandle", context);

        SkuScheduleDTO sku = context.getStructureSkuMap().get("S1").get(0);
        assertEquals(20, context.getCarryForwardQtyMap().get("MAT-1").intValue());
        assertEquals(100, sku.getWindowPlanQty());
        assertEquals(940, sku.getSurplusQty());
        assertEquals(960, sku.getPendingQty());
        assertEquals(960, sku.getTargetScheduleQty().intValue());
    }

    @Test
    void doHandle_shouldNotDoubleDeductWhenSameMaterialHasMultipleMachines() {
        ReflectionTestUtils.setField(handler, "endingJudgmentStrategy", new DefaultEndingJudgmentStrategy());

        LhScheduleContext context = new LhScheduleContext();
        context.setScheduleConfig(createConfig("0"));
        context.setScheduleDate(date(2026, 4, 11));
        context.setScheduleTargetDate(date(2026, 4, 13));
        context.setScheduleWindowShifts(LhScheduleTimeUtil.buildDefaultScheduleShifts(context, context.getScheduleDate()));

        FactoryMonthPlanProductionFinalResult plan = new FactoryMonthPlanProductionFinalResult();
        plan.setMaterialCode("MAT-MULTI");
        plan.setMaterialDesc("MAT-MULTI-DESC");
        plan.setStructureName("SM2");
        plan.setSpecifications("SPEC-M2");
        plan.setTotalQty(200);
        plan.setDay11(10);
        plan.setDay12(10);
        plan.setDay13(10);
        context.setMonthPlanList(Collections.singletonList(plan));

        LhScheduleResult previousA = new LhScheduleResult();
        previousA.setLhMachineCode("M-A");
        previousA.setMaterialCode("MAT-MULTI");
        previousA.setClass1PlanQty(50);
        LhScheduleResult previousB = new LhScheduleResult();
        previousB.setLhMachineCode("M-B");
        previousB.setMaterialCode("MAT-MULTI");
        previousB.setClass1PlanQty(30);
        context.setPreviousScheduleResultList(Arrays.asList(previousA, previousB));

        // 前日同物料日完成量只记一次，不按机台重复扣减。
        context.getMaterialDayFinishedQtyMap().put("MAT-MULTI_2026-04-12", 60);

        ReflectionTestUtils.invokeMethod(handler, "doHandle", context);

        SkuScheduleDTO sku = context.getStructureSkuMap().get("SM2").get(0);
        assertEquals(20, context.getCarryForwardQtyMap().get("MAT-MULTI").intValue());
        assertEquals(30, sku.getWindowPlanQty());
        assertEquals(220, sku.getPendingQty());
    }

    @Test
    void doHandle_shouldPreferMonthAccumulatedFinishedQtyForSurplus() {
        ReflectionTestUtils.setField(handler, "endingJudgmentStrategy", new DefaultEndingJudgmentStrategy());

        LhScheduleContext context = new LhScheduleContext();
        context.setScheduleConfig(createConfig("0"));
        context.setScheduleDate(date(2026, 4, 11));
        context.setScheduleTargetDate(date(2026, 4, 13));
        context.setScheduleWindowShifts(LhScheduleTimeUtil.buildDefaultScheduleShifts(context, context.getScheduleDate()));

        FactoryMonthPlanProductionFinalResult plan = new FactoryMonthPlanProductionFinalResult();
        plan.setMaterialCode("MAT-MONTH");
        plan.setMaterialDesc("MAT-MONTH-DESC");
        plan.setStructureName("SM");
        plan.setSpecifications("SPEC-M");
        plan.setTotalQty(1000);
        plan.setDay11(40);
        plan.setDay12(40);
        plan.setDay13(20);
        context.setMonthPlanList(Collections.singletonList(plan));

        // 月累计完成量（截至目标排产日）应优先于前日结果兜底值
        context.getMaterialMonthFinishedQtyMap().put("MAT-MONTH", 880);

        ReflectionTestUtils.invokeMethod(handler, "doHandle", context);

        SkuScheduleDTO sku = context.getStructureSkuMap().get("SM").get(0);
        assertEquals(120, sku.getSurplusQty());
        assertEquals(100, sku.getWindowPlanQty());
        assertEquals(120, sku.getTargetScheduleQty().intValue());
    }

    @Test
    void doHandle_shouldUseLargerQtyBetweenSurplusAndEmbryoStock() {
        ReflectionTestUtils.setField(handler, "endingJudgmentStrategy", new DefaultEndingJudgmentStrategy());

        LhScheduleContext context = new LhScheduleContext();
        context.setScheduleConfig(createConfig("0"));
        context.setScheduleDate(date(2026, 4, 11));
        context.setScheduleTargetDate(date(2026, 4, 13));
        context.setScheduleWindowShifts(LhScheduleTimeUtil.buildDefaultScheduleShifts(context, context.getScheduleDate()));
        context.getMaterialMonthFinishedQtyMap().put("MAT-STOCK-HIGH", 20);
        context.getEmbryoRealtimeStockMap().put("EMB-HIGH", 120);

        FactoryMonthPlanProductionFinalResult plan = new FactoryMonthPlanProductionFinalResult();
        plan.setMaterialCode("MAT-STOCK-HIGH");
        plan.setMaterialDesc("MAT-STOCK-HIGH-DESC");
        plan.setStructureName("S-STOCK-HIGH");
        plan.setSpecifications("SPEC-STOCK-HIGH");
        plan.setEmbryoCode("EMB-HIGH");
        plan.setTotalQty(100);
        plan.setDay11(30);
        context.setMonthPlanList(Collections.singletonList(plan));

        ReflectionTestUtils.invokeMethod(handler, "doHandle", context);

        SkuScheduleDTO sku = context.getStructureSkuMap().get("S-STOCK-HIGH").get(0);
        assertEquals(80, sku.getSurplusQty());
        assertEquals(120, sku.getEmbryoStock());
        assertEquals(120, sku.getPendingQty());
        assertEquals(120, sku.getTargetScheduleQty().intValue());
    }

    @Test
    void doHandle_shouldKeepSurplusWhenEmbryoStockIsLower() {
        ReflectionTestUtils.setField(handler, "endingJudgmentStrategy", new DefaultEndingJudgmentStrategy());

        LhScheduleContext context = new LhScheduleContext();
        context.setScheduleConfig(createConfig("0"));
        context.setScheduleDate(date(2026, 4, 11));
        context.setScheduleTargetDate(date(2026, 4, 13));
        context.setScheduleWindowShifts(LhScheduleTimeUtil.buildDefaultScheduleShifts(context, context.getScheduleDate()));
        context.getMaterialMonthFinishedQtyMap().put("MAT-STOCK-LOW", 30);
        context.getEmbryoRealtimeStockMap().put("EMB-LOW", 80);

        FactoryMonthPlanProductionFinalResult plan = new FactoryMonthPlanProductionFinalResult();
        plan.setMaterialCode("MAT-STOCK-LOW");
        plan.setMaterialDesc("MAT-STOCK-LOW-DESC");
        plan.setStructureName("S-STOCK-LOW");
        plan.setSpecifications("SPEC-STOCK-LOW");
        plan.setEmbryoCode("EMB-LOW");
        plan.setTotalQty(150);
        plan.setDay11(30);
        context.setMonthPlanList(Collections.singletonList(plan));

        ReflectionTestUtils.invokeMethod(handler, "doHandle", context);

        SkuScheduleDTO sku = context.getStructureSkuMap().get("S-STOCK-LOW").get(0);
        assertEquals(120, sku.getSurplusQty());
        assertEquals(80, sku.getEmbryoStock());
        assertEquals(120, sku.getPendingQty());
        assertEquals(120, sku.getTargetScheduleQty().intValue());
    }

    @Test
    void doHandle_shouldNotFallbackToPreviousDayFinishedQtyWhenMonthAccumulatedMissing() {
        ReflectionTestUtils.setField(handler, "endingJudgmentStrategy", new DefaultEndingJudgmentStrategy());

        LhScheduleContext context = new LhScheduleContext();
        context.setScheduleConfig(createConfig("0"));
        context.setScheduleDate(date(2026, 4, 20));
        context.setScheduleTargetDate(date(2026, 4, 22));
        context.setScheduleWindowShifts(LhScheduleTimeUtil.buildDefaultScheduleShifts(context, context.getScheduleDate()));

        FactoryMonthPlanProductionFinalResult plan = new FactoryMonthPlanProductionFinalResult();
        plan.setMaterialCode("MAT-T1");
        plan.setMaterialDesc("MAT-T1-DESC");
        plan.setStructureName("S-T1");
        plan.setSpecifications("SPEC-T1");
        plan.setTotalQty(140);
        plan.setDay20(22);
        plan.setDay21(22);
        plan.setDay22(30);
        context.setMonthPlanList(Collections.singletonList(plan));

        // 仅有目标日前一日完成量；月累计（截至窗口T-1）缺失时不应回退使用此值参与余量计算。
        context.getMaterialDayFinishedQtyMap().put("MAT-T1_2026-04-21", 66);

        ReflectionTestUtils.invokeMethod(handler, "doHandle", context);

        SkuScheduleDTO sku = context.getStructureSkuMap().get("S-T1").get(0);
        assertEquals(140, sku.getSurplusQty());
    }

    @Test
    void doHandle_shouldFallbackToPreviousDayFinishedQtyWhenPreviousBaselineMatchesWindowTMinusOne() {
        ReflectionTestUtils.setField(handler, "endingJudgmentStrategy", new DefaultEndingJudgmentStrategy());

        LhScheduleContext context = new LhScheduleContext();
        context.setScheduleConfig(createConfig("0"));
        context.setScheduleDate(date(2026, 4, 22));
        context.setScheduleTargetDate(date(2026, 4, 22));
        context.setScheduleWindowShifts(LhScheduleTimeUtil.buildDefaultScheduleShifts(context, context.getScheduleDate()));

        FactoryMonthPlanProductionFinalResult plan = new FactoryMonthPlanProductionFinalResult();
        plan.setMaterialCode("MAT-SAFE");
        plan.setMaterialDesc("MAT-SAFE-DESC");
        plan.setStructureName("S-SAFE");
        plan.setSpecifications("SPEC-SAFE");
        plan.setTotalQty(140);
        plan.setDay22(30);
        context.setMonthPlanList(Collections.singletonList(plan));

        context.getMaterialDayFinishedQtyMap().put("MAT-SAFE_2026-04-21", 66);

        ReflectionTestUtils.invokeMethod(handler, "doHandle", context);

        SkuScheduleDTO sku = context.getStructureSkuMap().get("S-SAFE").get(0);
        assertEquals(74, sku.getSurplusQty());
    }

    @Test
    void doHandle_skipsSkuWhenWindowPlanQtyAndCarryForwardAreBothZero() {
        ReflectionTestUtils.setField(handler, "endingJudgmentStrategy", new DefaultEndingJudgmentStrategy());

        LhScheduleContext context = new LhScheduleContext();
        context.setScheduleConfig(createConfig("0"));
        context.setScheduleDate(date(2026, 4, 11));
        context.setScheduleTargetDate(date(2026, 4, 13));
        context.setScheduleWindowShifts(LhScheduleTimeUtil.buildDefaultScheduleShifts(context, context.getScheduleDate()));

        FactoryMonthPlanProductionFinalResult plan = new FactoryMonthPlanProductionFinalResult();
        plan.setMaterialCode("MAT-2");
        plan.setMaterialDesc("MAT-2-DESC");
        plan.setStructureName("S2");
        plan.setSpecifications("SPEC-2");
        plan.setTotalQty(0);
        context.setMonthPlanList(Collections.singletonList(plan));

        ReflectionTestUtils.invokeMethod(handler, "doHandle", context);

        assertEquals(0, context.getStructureSkuMap().size());
        assertEquals(1, context.getUnscheduledResultList().size());
        assertEquals("物料：MAT-2 没有排产目标量，不进行排产",
                context.getUnscheduledResultList().get(0).getUnscheduledReason());
    }

    @Test
    void doHandle_keepsSkuWhenOnlyCarryForwardNeedsScheduling() {
        ReflectionTestUtils.setField(handler, "endingJudgmentStrategy", new DefaultEndingJudgmentStrategy());

        LhScheduleContext context = new LhScheduleContext();
        context.setScheduleConfig(createConfig("0"));
        context.setScheduleDate(date(2026, 4, 11));
        context.setScheduleTargetDate(date(2026, 4, 13));
        context.setScheduleWindowShifts(LhScheduleTimeUtil.buildDefaultScheduleShifts(context, context.getScheduleDate()));

        FactoryMonthPlanProductionFinalResult plan = new FactoryMonthPlanProductionFinalResult();
        plan.setMaterialCode("MAT-3");
        plan.setMaterialDesc("MAT-3-DESC");
        plan.setStructureName("S3");
        plan.setSpecifications("SPEC-3");
        plan.setTotalQty(200);
        context.setMonthPlanList(Collections.singletonList(plan));

        LhScheduleResult previous = new LhScheduleResult();
        previous.setLhMachineCode("M3");
        previous.setMaterialCode("MAT-3");
        previous.setClass1PlanQty(50);
        context.setPreviousScheduleResultList(Collections.singletonList(previous));

        context.getMaterialDayFinishedQtyMap().put("MAT-3_2026-04-12", 20);

        ReflectionTestUtils.invokeMethod(handler, "doHandle", context);

        SkuScheduleDTO sku = context.getStructureSkuMap().get("S3").get(0);
        assertEquals(0, sku.getWindowPlanQty());
        assertEquals(230, sku.getPendingQty());
        assertEquals(230, sku.getTargetScheduleQty().intValue());
        assertEquals(0, context.getUnscheduledResultList().size());
    }

    @Test
    void doHandle_shouldFillDeliveryLockDelayDaysAndSupplyPendingQty() {
        ReflectionTestUtils.setField(handler, "endingJudgmentStrategy", new DefaultEndingJudgmentStrategy());

        LhScheduleContext context = new LhScheduleContext();
        context.setScheduleConfig(createConfig("0"));
        context.setScheduleDate(date(2026, 4, 11));
        context.setScheduleTargetDate(date(2026, 4, 13));
        context.setScheduleWindowShifts(LhScheduleTimeUtil.buildDefaultScheduleShifts(context, context.getScheduleDate()));

        FactoryMonthPlanProductionFinalResult plan = new FactoryMonthPlanProductionFinalResult();
        plan.setMaterialCode("MAT-LOCK");
        plan.setMaterialDesc("MAT-LOCK-DESC");
        plan.setStructureName("S-LOCK");
        plan.setSpecifications("SPEC-LOCK");
        plan.setTotalQty(200);
        plan.setBeginDay(7);
        plan.setDay9(20);
        plan.setDay11(40);
        plan.setHeightProductionQty(11);
        plan.setCycleProductionQty(9);
        plan.setMidProductionQty(7);
        plan.setConventionProductionQty(5);
        context.setMonthPlanList(Collections.singletonList(plan));

        MpAdjustResult adjustResult = new MpAdjustResult();
        adjustResult.setMaterialCode("MAT-LOCK");
        adjustResult.setIsLockSchedule("1");
        context.getMpAdjustResultMap().put("MAT-LOCK", Collections.singletonList(adjustResult));

        ReflectionTestUtils.invokeMethod(handler, "doHandle", context);

        SkuScheduleDTO sku = context.getStructureSkuMap().get("S-LOCK").get(0);
        assertEquals(true, sku.isDeliveryLocked());
        assertEquals(4, sku.getDelayDays());
        assertEquals(11, sku.getHighPriorityPendingQty());
        assertEquals(9, sku.getCycleProductionPendingQty());
        assertEquals(7, sku.getMidPriorityPendingQty());
        assertEquals(5, sku.getConventionProductionPendingQty());
    }

    @Test
    void doHandle_shouldSetDelayDaysToMinusOneWhenBeginDayMissingOrInvalid() {
        ReflectionTestUtils.setField(handler, "endingJudgmentStrategy", new DefaultEndingJudgmentStrategy());

        LhScheduleContext context = new LhScheduleContext();
        context.setScheduleConfig(createConfig("0"));
        context.setScheduleDate(date(2026, 4, 11));
        context.setScheduleTargetDate(date(2026, 4, 13));
        context.setScheduleWindowShifts(LhScheduleTimeUtil.buildDefaultScheduleShifts(context, context.getScheduleDate()));

        FactoryMonthPlanProductionFinalResult missingBeginDayPlan = new FactoryMonthPlanProductionFinalResult();
        missingBeginDayPlan.setMaterialCode("MAT-BEGIN-MISS");
        missingBeginDayPlan.setMaterialDesc("MAT-BEGIN-MISS-DESC");
        missingBeginDayPlan.setStructureName("S-BEGIN-MISS");
        missingBeginDayPlan.setSpecifications("SPEC-BEGIN-MISS");
        missingBeginDayPlan.setTotalQty(100);
        missingBeginDayPlan.setDay11(30);

        FactoryMonthPlanProductionFinalResult invalidBeginDayPlan = new FactoryMonthPlanProductionFinalResult();
        invalidBeginDayPlan.setMaterialCode("MAT-BEGIN-INVALID");
        invalidBeginDayPlan.setMaterialDesc("MAT-BEGIN-INVALID-DESC");
        invalidBeginDayPlan.setStructureName("S-BEGIN-INVALID");
        invalidBeginDayPlan.setSpecifications("SPEC-BEGIN-INVALID");
        invalidBeginDayPlan.setTotalQty(100);
        invalidBeginDayPlan.setBeginDay(0);
        invalidBeginDayPlan.setDay11(30);

        context.setMonthPlanList(Arrays.asList(missingBeginDayPlan, invalidBeginDayPlan));

        ReflectionTestUtils.invokeMethod(handler, "doHandle", context);

        SkuScheduleDTO missingBeginDaySku = context.getStructureSkuMap().get("S-BEGIN-MISS").get(0);
        SkuScheduleDTO invalidBeginDaySku = context.getStructureSkuMap().get("S-BEGIN-INVALID").get(0);
        assertEquals(-1, missingBeginDaySku.getDelayDays());
        assertEquals(-1, invalidBeginDaySku.getDelayDays());
    }

    @Test
    void doHandle_keepsSkuWhenOnlySurplusNeedsScheduling() {
        ReflectionTestUtils.setField(handler, "endingJudgmentStrategy", new DefaultEndingJudgmentStrategy());

        LhScheduleContext context = new LhScheduleContext();
        context.setScheduleConfig(createConfig("0"));
        context.setScheduleDate(date(2026, 4, 11));
        context.setScheduleTargetDate(date(2026, 4, 13));
        context.setScheduleWindowShifts(LhScheduleTimeUtil.buildDefaultScheduleShifts(context, context.getScheduleDate()));

        FactoryMonthPlanProductionFinalResult plan = new FactoryMonthPlanProductionFinalResult();
        plan.setMaterialCode("MAT-4");
        plan.setMaterialDesc("MAT-4-DESC");
        plan.setStructureName("S4");
        plan.setSpecifications("SPEC-4");
        plan.setTotalQty(300);
        context.setMonthPlanList(Collections.singletonList(plan));

        ReflectionTestUtils.invokeMethod(handler, "doHandle", context);

        SkuScheduleDTO sku = context.getStructureSkuMap().get("S4").get(0);
        assertEquals(0, sku.getWindowPlanQty());
        assertEquals(300, sku.getPendingQty());
        assertEquals(300, sku.getTargetScheduleQty().intValue());
        assertEquals(0, context.getUnscheduledResultList().size());
    }

    @Test
    void doHandle_shouldUseWindowCapacityWhenFullCapacityModeEnabled() {
        ReflectionTestUtils.setField(handler, "endingJudgmentStrategy", new DefaultEndingJudgmentStrategy());

        LhScheduleContext context = new LhScheduleContext();
        context.setScheduleConfig(createConfig("1"));
        context.setScheduleDate(date(2026, 4, 11));
        context.setScheduleTargetDate(date(2026, 4, 13));
        context.setScheduleWindowShifts(LhScheduleTimeUtil.buildDefaultScheduleShifts(context, context.getScheduleDate()));

        FactoryMonthPlanProductionFinalResult plan = new FactoryMonthPlanProductionFinalResult();
        plan.setMaterialCode("MAT-FULL");
        plan.setMaterialDesc("MAT-FULL-DESC");
        plan.setStructureName("S-FULL");
        plan.setSpecifications("SPEC-FULL");
        plan.setTotalQty(300);
        context.setMonthPlanList(Collections.singletonList(plan));

        MdmSkuLhCapacity capacity = new MdmSkuLhCapacity();
        capacity.setMaterialCode("MAT-FULL");
        capacity.setClassCapacity(16);
        context.getSkuLhCapacityMap().put("MAT-FULL", capacity);

        ReflectionTestUtils.invokeMethod(handler, "doHandle", context);

        SkuScheduleDTO sku = context.getStructureSkuMap().get("S-FULL").get(0);
        assertEquals(0, sku.getWindowPlanQty());
        assertEquals(300, sku.getPendingQty());
        assertEquals(128, sku.getTargetScheduleQty().intValue());
        assertEquals(0, context.getUnscheduledResultList().size());
    }

    @Test
    void doHandle_shouldAppendOpenProductionShortageWhenThresholdReached() {
        ReflectionTestUtils.setField(handler, "endingJudgmentStrategy", new DefaultEndingJudgmentStrategy());

        LhScheduleContext context = new LhScheduleContext();
        context.setScheduleConfig(createConfig("1"));
        context.setScheduleDate(date(2026, 4, 11));
        context.setScheduleTargetDate(date(2026, 4, 13));
        context.setOpenProductionMode(true);
        context.setOpenProductionShortageThresholdRate(new BigDecimal("0.5"));
        context.setScheduleWindowShifts(LhScheduleTimeUtil.buildDefaultScheduleShifts(context, context.getScheduleDate()));

        FactoryMonthPlanProductionFinalResult plan = new FactoryMonthPlanProductionFinalResult();
        plan.setMaterialCode("MAT-OPEN-SHORT");
        plan.setMaterialDesc("MAT-OPEN-SHORT-DESC");
        plan.setStructureName("S-OPEN-SHORT");
        plan.setSpecifications("SPEC-OPEN-SHORT");
        plan.setTotalQty(300);
        context.setMonthPlanList(Collections.singletonList(plan));

        MdmSkuLhCapacity capacity = new MdmSkuLhCapacity();
        capacity.setMaterialCode("MAT-OPEN-SHORT");
        capacity.setClassCapacity(10);
        context.getSkuLhCapacityMap().put("MAT-OPEN-SHORT", capacity);

        ReflectionTestUtils.invokeMethod(handler, "doHandle", context);

        SkuScheduleDTO sku = context.getStructureSkuMap().get("S-OPEN-SHORT").get(0);
        LhUnscheduledResult unscheduled = context.getUnscheduledResultList().get(0);
        assertEquals(80, sku.getTargetScheduleQty().intValue());
        assertEquals(220, unscheduled.getUnscheduledQty().intValue());
        assertTrue(unscheduled.getUnscheduledReason().contains("开产管控导致排产目标量低于待排量"));
    }

    @Test
    void doHandle_shouldDeductInheritedPlanQtyFromRollingWindowPendingQty() {
        ReflectionTestUtils.setField(handler, "endingJudgmentStrategy", new DefaultEndingJudgmentStrategy());

        LhScheduleContext context = new LhScheduleContext();
        context.setScheduleConfig(createConfig("0"));
        context.setScheduleDate(date(2026, 4, 24));
        context.setScheduleTargetDate(date(2026, 4, 26));
        context.setScheduleWindowShifts(LhScheduleTimeUtil.buildDefaultScheduleShifts(context, context.getScheduleDate()));
        context.getInheritedPlanQtyMap().put("MAT-ROLL", 80);

        FactoryMonthPlanProductionFinalResult plan = new FactoryMonthPlanProductionFinalResult();
        plan.setMaterialCode("MAT-ROLL");
        plan.setMaterialDesc("MAT-ROLL-DESC");
        plan.setStructureName("S-ROLL");
        plan.setSpecifications("SPEC-ROLL");
        plan.setTotalQty(300);
        plan.setDay24(40);
        plan.setDay25(40);
        plan.setDay26(30);
        context.setMonthPlanList(Collections.singletonList(plan));

        ReflectionTestUtils.invokeMethod(handler, "doHandle", context);

        SkuScheduleDTO sku = context.getStructureSkuMap().get("S-ROLL").get(0);
        assertEquals(110, sku.getWindowPlanQty());
        assertEquals(220, sku.getPendingQty());
        assertEquals(220, sku.getTargetScheduleQty().intValue());
    }

    @Test
    void doHandle_shouldExcludeRollingInheritedWindowShiftFromCarryForwardQty() {
        ReflectionTestUtils.setField(handler, "endingJudgmentStrategy", new DefaultEndingJudgmentStrategy());

        LhScheduleContext context = new LhScheduleContext();
        context.setScheduleConfig(createConfig("0"));
        context.setScheduleDate(date(2026, 4, 24));
        context.setScheduleTargetDate(date(2026, 4, 26));
        context.setRollingScheduleHandoff(true);
        context.setScheduleWindowShifts(LhScheduleTimeUtil.buildDefaultScheduleShifts(context, context.getScheduleDate()));
        context.getInheritedPlanQtyMap().put("MAT-ROLL-DUP", 60);

        FactoryMonthPlanProductionFinalResult plan = new FactoryMonthPlanProductionFinalResult();
        plan.setMaterialCode("MAT-ROLL-DUP");
        plan.setMaterialDesc("MAT-ROLL-DUP-DESC");
        plan.setStructureName("S-ROLL-DUP");
        plan.setSpecifications("SPEC-ROLL-DUP");
        plan.setTotalQty(300);
        plan.setDay24(60);
        plan.setDay25(60);
        context.setMonthPlanList(Collections.singletonList(plan));

        LhScheduleResult previous = new LhScheduleResult();
        previous.setMaterialCode("MAT-ROLL-DUP");
        ShiftFieldUtil.setShiftPlanQty(previous, 1, 40,
                dateTime(2026, 4, 23, 6, 0), dateTime(2026, 4, 23, 14, 0));
        ShiftFieldUtil.setShiftPlanQty(previous, 2, 60,
                dateTime(2026, 4, 23, 22, 0), dateTime(2026, 4, 24, 7, 0));
        context.setPreviousScheduleResultList(Collections.singletonList(previous));
        context.getMaterialDayFinishedQtyMap().put("MAT-ROLL-DUP_2026-04-23", 40);

        ReflectionTestUtils.invokeMethod(handler, "doHandle", context);

        SkuScheduleDTO sku = context.getStructureSkuMap().get("S-ROLL-DUP").get(0);
        assertNull(context.getCarryForwardQtyMap().get("MAT-ROLL-DUP"));
        assertEquals(120, sku.getWindowPlanQty());
        assertEquals(200, sku.getPendingQty());
        assertEquals(200, sku.getTargetScheduleQty().intValue());
    }

    @Test
    void doHandle_forceRescheduleShouldUseTMinusOneAsCarryForwardBaseline() {
        ReflectionTestUtils.setField(handler, "endingJudgmentStrategy", new DefaultEndingJudgmentStrategy());

        LhScheduleContext context = new LhScheduleContext();
        context.setScheduleConfig(createConfig("0", "1"));
        context.setScheduleDate(date(2026, 4, 24));
        context.setScheduleTargetDate(date(2026, 4, 26));
        context.setScheduleWindowShifts(LhScheduleTimeUtil.buildDefaultScheduleShifts(context, context.getScheduleDate()));

        FactoryMonthPlanProductionFinalResult plan = new FactoryMonthPlanProductionFinalResult();
        plan.setMaterialCode("MAT-FORCE");
        plan.setMaterialDesc("MAT-FORCE-DESC");
        plan.setStructureName("S-FORCE");
        plan.setSpecifications("SPEC-FORCE");
        plan.setTotalQty(300);
        plan.setDay24(40);
        plan.setDay25(40);
        plan.setDay26(40);
        context.setMonthPlanList(Collections.singletonList(plan));

        LhScheduleResult previous = new LhScheduleResult();
        previous.setMaterialCode("MAT-FORCE");
        previous.setClass1PlanQty(80);
        context.setPreviousScheduleResultList(Collections.singletonList(previous));

        // 强制重排从窗口起点前一日传导，窗口内目标日前一日数据不应参与本次基线。
        context.getMaterialDayFinishedQtyMap().put("MAT-FORCE_2026-04-23", 50);
        context.getMaterialDayFinishedQtyMap().put("MAT-FORCE_2026-04-25", 80);

        ReflectionTestUtils.invokeMethod(handler, "doHandle", context);

        SkuScheduleDTO sku = context.getStructureSkuMap().get("S-FORCE").get(0);
        assertEquals(30, context.getCarryForwardQtyMap().get("MAT-FORCE").intValue());
        assertEquals(120, sku.getWindowPlanQty());
        assertEquals(280, sku.getPendingQty());
        assertEquals(280, sku.getTargetScheduleQty().intValue());
    }

    @Test
    void doHandle_shouldLogRollingPendingQtyBreakdown() {
        ReflectionTestUtils.setField(handler, "endingJudgmentStrategy", new DefaultEndingJudgmentStrategy());

        LhScheduleContext context = new LhScheduleContext();
        context.setScheduleConfig(createConfig("0"));
        context.setScheduleDate(date(2026, 4, 24));
        context.setScheduleTargetDate(date(2026, 4, 26));
        context.setRollingScheduleHandoff(true);
        context.setScheduleWindowShifts(LhScheduleTimeUtil.buildDefaultScheduleShifts(context, context.getScheduleDate()));
        context.getInheritedPlanQtyMap().put("MAT-ROLL-LOG", 60);

        FactoryMonthPlanProductionFinalResult plan = new FactoryMonthPlanProductionFinalResult();
        plan.setMaterialCode("MAT-ROLL-LOG");
        plan.setMaterialDesc("MAT-ROLL-LOG-DESC");
        plan.setStructureName("S-ROLL-LOG");
        plan.setSpecifications("SPEC-ROLL-LOG");
        plan.setTotalQty(300);
        plan.setDay24(60);
        plan.setDay25(60);
        context.setMonthPlanList(Collections.singletonList(plan));

        LhScheduleResult previous = new LhScheduleResult();
        previous.setMaterialCode("MAT-ROLL-LOG");
        ShiftFieldUtil.setShiftPlanQty(previous, 1, 40,
                dateTime(2026, 4, 23, 6, 0), dateTime(2026, 4, 23, 14, 0));
        context.setPreviousScheduleResultList(Collections.singletonList(previous));
        context.getMaterialDayFinishedQtyMap().put("MAT-ROLL-LOG_2026-04-23", 20);

        ListAppender<ILoggingEvent> appender = attachAppender();
        try {
            ReflectionTestUtils.invokeMethod(handler, "doHandle", context);
        } finally {
            detachAppender(appender);
        }

        List<String> messages = appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .collect(Collectors.toList());
        assertTrue(messages.stream().anyMatch(message -> message.contains("滚动待排量拆解")
                        && message.contains("MAT-ROLL-LOG")
                        && message.contains("窗口计划量")
                        && message.contains("已继承量")
                        && message.contains("欠产传导量")
                        && message.contains("待排量")),
                "应输出滚动待排量拆解日志，便于核对 windowPlanQty、inheritedPlanQty 和 pendingQty");
    }

    @Test
    void doHandle_shouldMatchContinuousSkuByMachineOnlineInfo() {
        ReflectionTestUtils.setField(handler, "endingJudgmentStrategy", new DefaultEndingJudgmentStrategy());

        LhScheduleContext context = new LhScheduleContext();
        context.setScheduleConfig(createConfig("0"));
        context.setScheduleDate(date(2026, 4, 11));
        context.setScheduleTargetDate(date(2026, 4, 13));
        context.setScheduleWindowShifts(LhScheduleTimeUtil.buildDefaultScheduleShifts(context, context.getScheduleDate()));
        context.setMonthPlanList(Arrays.asList(
                buildPlan("MAT-A", "S1", 100, 10),
                buildPlan("MAT-B", "S2", 100, 10),
                buildPlan("MAT-C", "S3", 100, 10)));
        context.setMachineOnlineInfoMap(buildMachineOnlineInfoMap(
                buildOnlineInfo("K1501", "MAT-A"),
                buildOnlineInfo("K1502", "MAT-X")));
        context.setMachineScheduleMap(buildMachineScheduleMap("K1501", "K1502"));

        ReflectionTestUtils.invokeMethod(handler, "doHandle", context);

        SkuScheduleDTO continuousSku = context.getStructureSkuMap().get("S1").get(0);
        SkuScheduleDTO newSku = context.getStructureSkuMap().get("S2").get(0);
        SkuScheduleDTO unmatchedSku = context.getStructureSkuMap().get("S3").get(0);
        assertEquals(1, context.getContinuousSkuList().size());
        assertEquals(2, context.getNewSpecSkuList().size());
        assertEquals(ScheduleTypeEnum.CONTINUOUS.getCode(), continuousSku.getScheduleType());
        assertEquals("K1501", continuousSku.getContinuousMachineCode());
        assertEquals(ScheduleTypeEnum.NEW_SPEC.getCode(), newSku.getScheduleType());
        assertNull(newSku.getContinuousMachineCode());
        assertEquals(ScheduleTypeEnum.NEW_SPEC.getCode(), unmatchedSku.getScheduleType());
        assertNull(unmatchedSku.getContinuousMachineCode());
    }

    @Test
    void doHandle_shouldConsumeDuplicatedMaterialSkusOncePerMachine() {
        ReflectionTestUtils.setField(handler, "endingJudgmentStrategy", new DefaultEndingJudgmentStrategy());

        LhScheduleContext context = new LhScheduleContext();
        context.setScheduleConfig(createConfig("0"));
        context.setScheduleDate(date(2026, 4, 11));
        context.setScheduleTargetDate(date(2026, 4, 13));
        context.setScheduleWindowShifts(LhScheduleTimeUtil.buildDefaultScheduleShifts(context, context.getScheduleDate()));
        context.setMonthPlanList(Arrays.asList(
                buildPlan("MAT-DUP", "S1", 100, 10),
                buildPlan("MAT-DUP", "S2", 100, 10)));
        context.setMachineOnlineInfoMap(buildMachineOnlineInfoMap(
                buildOnlineInfo("K1501", "MAT-DUP"),
                buildOnlineInfo("K1502", "MAT-DUP"),
                buildOnlineInfo("K1503", "MAT-DUP")));
        context.setMachineScheduleMap(buildMachineScheduleMap("K1501", "K1502", "K1503"));

        ReflectionTestUtils.invokeMethod(handler, "doHandle", context);

        SkuScheduleDTO firstSku = context.getStructureSkuMap().get("S1").get(0);
        SkuScheduleDTO secondSku = context.getStructureSkuMap().get("S2").get(0);
        assertEquals(2, context.getContinuousSkuList().size());
        assertEquals(0, context.getNewSpecSkuList().size());
        assertEquals("K1501", firstSku.getContinuousMachineCode());
        assertEquals("K1502", secondSku.getContinuousMachineCode());
    }

    private FactoryMonthPlanProductionFinalResult buildPlan(String materialCode, String structureName,
                                                            int totalQty, int day11Qty) {
        FactoryMonthPlanProductionFinalResult plan = new FactoryMonthPlanProductionFinalResult();
        plan.setMaterialCode(materialCode);
        plan.setMaterialDesc(materialCode + "-DESC");
        plan.setStructureName(structureName);
        plan.setSpecifications(structureName + "-SPEC");
        plan.setTotalQty(totalQty);
        plan.setDay11(day11Qty);
        return plan;
    }

    private Map<String, LhMachineOnlineInfo> buildMachineOnlineInfoMap(LhMachineOnlineInfo... onlineInfos) {
        Map<String, LhMachineOnlineInfo> machineOnlineInfoMap = new LinkedHashMap<>();
        for (LhMachineOnlineInfo onlineInfo : onlineInfos) {
            machineOnlineInfoMap.put(onlineInfo.getLhCode(), onlineInfo);
        }
        return machineOnlineInfoMap;
    }

    private Map<String, MachineScheduleDTO> buildMachineScheduleMap(String... machineCodes) {
        Map<String, MachineScheduleDTO> machineScheduleMap = new LinkedHashMap<>();
        for (String machineCode : machineCodes) {
            MachineScheduleDTO machine = new MachineScheduleDTO();
            machine.setMachineCode(machineCode);
            machine.setStatus("1");
            machineScheduleMap.put(machineCode, machine);
        }
        return machineScheduleMap;
    }

    private LhMachineOnlineInfo buildOnlineInfo(String machineCode, String materialCode) {
        LhMachineOnlineInfo onlineInfo = new LhMachineOnlineInfo();
        onlineInfo.setLhCode(machineCode);
        onlineInfo.setMaterialCode(materialCode);
        return onlineInfo;
    }

    private ListAppender<ILoggingEvent> attachAppender() {
        Logger logger = (Logger) LoggerFactory.getLogger(ScheduleAdjustHandler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private void detachAppender(ListAppender<ILoggingEvent> appender) {
        Logger logger = (Logger) LoggerFactory.getLogger(ScheduleAdjustHandler.class);
        logger.detachAppender(appender);
    }

    private static java.util.Date date(int y, int month, int day) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(Calendar.YEAR, y);
        c.set(Calendar.MONTH, month - 1);
        c.set(Calendar.DAY_OF_MONTH, day);
        return c.getTime();
    }

    private static java.util.Date dateTime(int y, int month, int day, int hour, int minute) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(Calendar.YEAR, y);
        c.set(Calendar.MONTH, month - 1);
        c.set(Calendar.DAY_OF_MONTH, day);
        c.set(Calendar.HOUR_OF_DAY, hour);
        c.set(Calendar.MINUTE, minute);
        return c.getTime();
    }

    private static LhScheduleConfig createConfig(String fullCapacityMode) {
        Map<String, String> paramMap = new HashMap<>(4);
        paramMap.put(LhScheduleParamConstant.ENABLE_FULL_CAPACITY_SCHEDULING, fullCapacityMode);
        paramMap.put(LhScheduleParamConstant.FORCE_RESCHEDULE, "0");
        return new LhScheduleConfig(paramMap);
    }

    private static LhScheduleConfig createConfig(String fullCapacityMode, String forceReschedule) {
        Map<String, String> paramMap = new HashMap<>(4);
        paramMap.put(LhScheduleParamConstant.ENABLE_FULL_CAPACITY_SCHEDULING, fullCapacityMode);
        paramMap.put(LhScheduleParamConstant.FORCE_RESCHEDULE, forceReschedule);
        return new LhScheduleConfig(paramMap);
    }
}
