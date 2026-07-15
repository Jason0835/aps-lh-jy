package com.zlt.aps.lh.regression;

import com.zlt.aps.lh.api.constant.LhScheduleParamConstant;
import com.zlt.aps.lh.api.domain.dto.MachineCleaningWindowDTO;
import com.zlt.aps.lh.api.domain.dto.MachineScheduleDTO;
import com.zlt.aps.lh.api.domain.dto.SkuDailyPlanQuotaDTO;
import com.zlt.aps.lh.api.domain.dto.SkuScheduleDTO;
import com.zlt.aps.lh.api.domain.entity.LhMachineOnlineInfo;
import com.zlt.aps.lh.api.domain.entity.LhRepairCapsule;
import com.zlt.aps.lh.api.domain.entity.LhScheduleResult;
import com.zlt.aps.lh.api.domain.vo.LhShiftConfigVO;
import com.zlt.aps.lh.component.OrderNoGenerator;
import com.zlt.aps.lh.context.LhScheduleConfig;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.engine.strategy.IEndingJudgmentStrategy;
import com.zlt.aps.lh.engine.strategy.impl.ContinuousProductionStrategy;
import com.zlt.aps.lh.exception.ScheduleException;
import com.zlt.aps.lh.handler.ResultValidationHandler;
import com.zlt.aps.lh.util.LhScheduleTimeUtil;
import com.zlt.aps.lh.util.ShiftFieldUtil;
import com.zlt.aps.mdm.api.domain.entity.MdmSkuLhCapacity;
import com.zlt.aps.mdm.api.domain.entity.MdmSkuMouldRel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 续作结果字段口径回归：月总量与本次实际排产量需分离保存。
 */
@ExtendWith(MockitoExtension.class)
class ContinuousProductionResultQtyRegressionTest {

    @Mock
    private OrderNoGenerator orderNoGenerator;

    @Mock
    private IEndingJudgmentStrategy endingJudgmentStrategy;

    @InjectMocks
    private ContinuousProductionStrategy strategy;

    @Test
    void scheduleContinuousEnding_shouldStoreMonthQtyAndScheduledQtySeparately() {
        LhScheduleContext context = newContext();
        MachineScheduleDTO machine = new MachineScheduleDTO();
        machine.setMachineCode("M1");
        machine.setMachineName("FC-M1");
        machine.setMaxMoldNum(2);
        context.setMachineScheduleMap(new LinkedHashMap<>());
        context.getMachineScheduleMap().put("M1", machine);

        SkuScheduleDTO sku = new SkuScheduleDTO();
        sku.setMaterialCode("MAT-C1");
        sku.setMaterialDesc("MAT-C1-DESC");
        sku.setStructureName("S1");
        sku.setSpecCode("SPEC-C1");
        sku.setEmbryoCode("EMB-1");
        sku.setContinuousMachineCode("M1");
        sku.setMonthPlanQty(50);
        sku.setWindowPlanQty(30);
        sku.setPendingQty(1000);
        sku.setTargetScheduleQty(30);
        sku.setShiftCapacity(16);
        sku.setLhTimeSeconds(3060);
        sku.setScheduleType("01");
        context.getContinuousSkuList().add(sku);

        when(orderNoGenerator.generateOrderNo(any())).thenReturn("LHGD20260411011");
        when(endingJudgmentStrategy.isCurrentWindowEnding(any(), any())).thenReturn(false);

        strategy.scheduleContinuousEnding(context);

        LhScheduleResult result = context.getScheduleResultList().get(0);
        assertEquals("LR", result.getLeftRightMould());
        assertEquals(50, result.getTotalDailyPlanQty());
        assertEquals(128, result.getDailyPlanQty(), "单机非收尾续作按满排窗口保存本次实际排产量");
        assertEquals(16, result.getClass1PlanQty());
        assertEquals(16, result.getClass2PlanQty());
        assertEquals(16, result.getClass8PlanQty());
    }

    @Test
    void scheduleContinuousEnding_shouldCapScheduledQtyByEightShiftCapacityWhenTargetExceedsCapacity() {
        LhScheduleContext context = newContext();
        MachineScheduleDTO machine = new MachineScheduleDTO();
        machine.setMachineCode("M2");
        machine.setMachineName("FC-M2");
        machine.setMaxMoldNum(2);
        context.setMachineScheduleMap(new LinkedHashMap<>());
        context.getMachineScheduleMap().put("M2", machine);

        SkuScheduleDTO sku = new SkuScheduleDTO();
        sku.setMaterialCode("MAT-C2");
        sku.setMaterialDesc("MAT-C2-DESC");
        sku.setStructureName("S2");
        sku.setSpecCode("SPEC-C2");
        sku.setEmbryoCode("EMB-2");
        sku.setContinuousMachineCode("M2");
        sku.setMonthPlanQty(300);
        sku.setWindowPlanQty(160);
        sku.setPendingQty(160);
        sku.setTargetScheduleQty(160);
        sku.setShiftCapacity(16);
        sku.setLhTimeSeconds(3060);
        sku.setScheduleType("01");
        context.getContinuousSkuList().add(sku);

        when(orderNoGenerator.generateOrderNo(any())).thenReturn("LHGD20260411012");
        when(endingJudgmentStrategy.isCurrentWindowEnding(any(), any())).thenReturn(false);

        strategy.scheduleContinuousEnding(context);

        LhScheduleResult result = context.getScheduleResultList().get(0);
        assertEquals(300, result.getTotalDailyPlanQty());
        assertEquals(128, result.getDailyPlanQty(), "目标量超过8班总产能时，应按8班最大产能排满");
        assertEquals(16, result.getClass1PlanQty());
        assertEquals(16, result.getClass8PlanQty());
    }

    @Test
    void buildScheduleResult_shouldRefineTargetQtyByMachineActualStartTime() {
        LhScheduleContext context = newContext();
        MachineScheduleDTO machine = new MachineScheduleDTO();
        machine.setMachineCode("M3");
        machine.setMachineName("FC-M3");
        machine.setMaxMoldNum(1);
        context.setMachineScheduleMap(new LinkedHashMap<>());
        context.getMachineScheduleMap().put("M3", machine);

        SkuScheduleDTO sku = new SkuScheduleDTO();
        sku.setMaterialCode("MAT-C3");
        sku.setMaterialDesc("MAT-C3-DESC");
        sku.setStructureName("S3");
        sku.setSpecCode("SPEC-C3");
        sku.setEmbryoCode("EMB-3");
        sku.setContinuousMachineCode("M3");
        sku.setMonthPlanQty(300);
        sku.setWindowPlanQty(8);
        sku.setPendingQty(8);
        sku.setTargetScheduleQty(128);
        sku.setShiftCapacity(16);
        sku.setLhTimeSeconds(3060);
        sku.setScheduleType("01");

        when(orderNoGenerator.generateOrderNo(any())).thenReturn("LHGD20260411013");
        Date startTime = context.getScheduleWindowShifts().get(1).getShiftStartDateTime();
        LhScheduleResult result = ReflectionTestUtils.invokeMethod(
                strategy,
                "buildScheduleResult",
                context,
                machine,
                sku,
                startTime,
                null,
                context.getScheduleWindowShifts(),
                1,
                false);

        assertEquals(112, result.getDailyPlanQty().intValue(), "续作应按机台实际开产后的剩余窗口产能收敛目标量");
        assertEquals(128, sku.getTargetScheduleQty().intValue(), "机台局部收敛值不应覆盖物料级全局目标量");
    }

    @Test
    void buildScheduleResult_shouldApplyDryIceTimeRatioDuringInitialContinuousDistribution() {
        LhScheduleContext context = newContext();
        MachineScheduleDTO machine = new MachineScheduleDTO();
        machine.setMachineCode("K1110");
        machine.setMachineName("FC-K1110");
        machine.setMaxMoldNum(2);
        machine.setCleaningWindowList(buildDryIceCleaningWindowList());
        context.setMachineScheduleMap(new LinkedHashMap<>());
        context.getMachineScheduleMap().put("K1110", machine);

        SkuScheduleDTO sku = new SkuScheduleDTO();
        sku.setMaterialCode("3302001884");
        sku.setMaterialDesc("MAT-K1110");
        sku.setStructureName("S5");
        sku.setSpecCode("SPEC-K1110");
        sku.setEmbryoCode("EMB-K1110");
        sku.setContinuousMachineCode("K1110");
        sku.setMonthPlanQty(200);
        sku.setWindowPlanQty(22);
        sku.setPendingQty(22);
        sku.setTargetScheduleQty(22);
        sku.setShiftCapacity(22);
        sku.setLhTimeSeconds(2160);
        sku.setScheduleType("01");

        when(orderNoGenerator.generateOrderNo(any())).thenReturn("LHGD20260411015");

        Date startTime = context.getScheduleWindowShifts().get(1).getShiftStartDateTime();
        LhScheduleResult result = ReflectionTestUtils.invokeMethod(
                strategy,
                "buildScheduleResult",
                context,
                machine,
                sku,
                startTime,
                null,
                context.getScheduleWindowShifts(),
                2,
                true);

        assertEquals(22, result.getDailyPlanQty().intValue(), "续作目标总量不变时，剩余量应继续顺延到后续班次");
        assertEquals(12, result.getClass2PlanQty().intValue(), "续作首次分班命中干冰 3 小时时，应按剩余 5/8 时间折算到 12");
        assertEquals(10, result.getClass3PlanQty().intValue(), "首班被压缩后的剩余计划量应顺延到下一班");
    }

    @Test
    void applyDailyStandardPlanQtyToContinuousResults_shouldTrimFinalAfternoonShiftOnly() {
        LhScheduleContext context = newContext();
        MdmSkuLhCapacity capacity = new MdmSkuLhCapacity();
        capacity.setMaterialCode("3302002218");
        capacity.setClassCapacity(18);
        capacity.setStandardCapacity(50);
        context.getSkuLhCapacityMap().put("3302002218", capacity);

        LhScheduleResult result = new LhScheduleResult();
        result.setMaterialCode("3302002218");
        result.setLhMachineCode("K1915");
        result.setScheduleType("01");
        result.setSingleMouldShiftQty(18);
        result.setMouldQty(2);
        result.setClass1PlanQty(18);
        result.setClass2PlanQty(14);
        result.setClass3PlanQty(18);
        result.setClass4PlanQty(18);
        result.setClass5PlanQty(14);
        result.setClass6PlanQty(18);
        result.setClass7PlanQty(18);
        result.setClass8PlanQty(18);
        context.getScheduleResultList().add(result);

        ReflectionTestUtils.invokeMethod(strategy,
                "applyDailyStandardPlanQtyToContinuousResults", context, context.getScheduleWindowShifts());

        assertEquals(14, result.getClass8PlanQty().intValue(),
                "续作后置补满后，中班仍应按日标准产量余量回裁");
        assertEquals(132, ShiftFieldUtil.resolveScheduledQty(result),
                "续作最终结果应按日标准产量公式收敛");
    }

    @Test
    void applyDailyStandardPlanQtyToContinuousResults_shouldRaiseFinalAfternoonShiftToFormulaQty() {
        LhScheduleContext context = newContext();
        MdmSkuLhCapacity capacity = new MdmSkuLhCapacity();
        capacity.setMaterialCode("3302002218");
        capacity.setClassCapacity(18);
        capacity.setStandardCapacity(50);
        context.getSkuLhCapacityMap().put("3302002218", capacity);

        LhScheduleResult result = new LhScheduleResult();
        result.setMaterialCode("3302002218");
        result.setLhMachineCode("K1514");
        result.setScheduleType("01");
        result.setSingleMouldShiftQty(18);
        result.setMouldQty(2);
        result.setLhTime(1800);
        result.setClass1PlanQty(18);
        result.setClass2PlanQty(14);
        result.setClass3PlanQty(18);
        result.setClass4PlanQty(18);
        result.setClass5PlanQty(14);
        result.setClass6PlanQty(18);
        result.setClass7PlanQty(18);
        result.setClass8PlanQty(10);
        result.setClass8StartTime(context.getScheduleWindowShifts().get(7).getShiftStartDateTime());
        context.getScheduleResultList().add(result);

        ReflectionTestUtils.invokeMethod(strategy,
                "applyDailyStandardPlanQtyToContinuousResults", context, context.getScheduleWindowShifts());

        assertEquals(14, result.getClass8PlanQty().intValue(),
                "续作中班原计划量不足公式结果时，应从10修正到14");
        assertEquals(132, ShiftFieldUtil.resolveScheduledQty(result),
                "续作最终结果应按日标准产量公式补足剩余班次");
    }

    @Test
    void applyDailyStandardPlanQtyToContinuousResults_shouldFillDailyStandardWhenClassCapacityIsLower() {
        LhScheduleContext context = newContext();
        MdmSkuLhCapacity capacity = new MdmSkuLhCapacity();
        capacity.setMaterialCode("3302002177");
        capacity.setClassCapacity(16);
        capacity.setStandardCapacity(50);
        capacity.setApsCapacity(54);
        context.getSkuLhCapacityMap().put("3302002177", capacity);

        LhScheduleResult result = new LhScheduleResult();
        result.setMaterialCode("3302002177");
        result.setLhMachineCode("K1611");
        result.setScheduleType("01");
        result.setSingleMouldShiftQty(16);
        result.setMouldQty(2);
        result.setLhTime(2880);
        result.setClass1PlanQty(16);
        result.setClass2PlanQty(16);
        result.setClass3PlanQty(16);
        result.setClass4PlanQty(16);
        result.setClass5PlanQty(16);
        result.setClass6PlanQty(16);
        result.setClass7PlanQty(16);
        result.setClass8PlanQty(16);
        context.getScheduleResultList().add(result);

        ReflectionTestUtils.invokeMethod(strategy,
                "applyDailyStandardPlanQtyToContinuousResults", context, context.getScheduleWindowShifts());

        assertEquals(18, result.getClass2PlanQty().intValue(), "T日中班应补足为18，使完整业务日产量达到50");
        assertEquals(18, result.getClass5PlanQty().intValue(), "T+1中班应补足为18，使完整业务日产量达到50");
        assertEquals(18, result.getClass8PlanQty().intValue(), "T+2中班应补足为18，使完整业务日产量达到50");
        assertEquals(134, ShiftFieldUtil.resolveScheduledQty(result), "窗口8班计划量应由128修正为134");
        assertEquals(16, result.getSingleMouldShiftQty().intValue(), "班产落库字段不得被日标准量修正覆盖");
    }

    @Test
    void applyDailyStandardPlanQtyToContinuousResults_shouldFillMainSaleEndingAfterTwentyWhenStructureNotFull() {
        LhScheduleContext context = newContext();
        LhShiftConfigVO afternoonShift = context.getScheduleWindowShifts().get(4);
        LocalDate businessDate = resolveShiftBusinessDate(afternoonShift);
        context.addStructurePlanMachineCount(businessDate, "PCR-01", 2);
        SkuScheduleDTO sku = buildMainSaleEndingSku("330200MAIN", "PCR-01", "01");
        markRuntimeSharedEmbryo(context, sku, "330200MAIN-SHARED");
        context.getEmbryoEndingFlagMap().put(sku.getEmbryoCode(), 0);
        LhScheduleResult result = buildMainSaleEndingResult(context, afternoonShift, "K1901", "330200MAIN", 8);
        context.getScheduleResultSourceSkuMap().put(result, sku);

        ReflectionTestUtils.invokeMethod(strategy,
                "applyDailyStandardPlanQtyToContinuousResults", context, context.getScheduleWindowShifts());

        assertEquals(18, result.getClass5PlanQty().intValue(), "主销收尾20点后应补满当天中班");
        assertEquals(18, result.getClass6PlanQty().intValue(), "主销收尾20点后应补满下一个晚班");
        assertEquals(1, context.getStructureScheduledMachineCount(businessDate, "PCR-01"),
                "补满后必须回写结构已排机台统计");
    }

    @Test
    void applyDailyStandardPlanQtyToContinuousResults_shouldFillRegularEndingAfterTwentyWhenEmbryoOnMachine() {
        LhScheduleContext context = newContext();
        LhShiftConfigVO afternoonShift = context.getScheduleWindowShifts().get(4);
        LocalDate businessDate = resolveShiftBusinessDate(afternoonShift);
        context.addStructurePlanMachineCount(businessDate, "PCR-01", 2);
        SkuScheduleDTO sku = buildMainSaleEndingSku("330200NORMAL", "PCR-01", "02");
        markRuntimeSharedEmbryo(context, sku, "330200NORMAL-SHARED");
        context.getEmbryoEndingFlagMap().put(sku.getEmbryoCode(), 0);
        LhScheduleResult result = buildMainSaleEndingResult(context, afternoonShift, "K1902", "330200NORMAL", 8);
        context.getScheduleResultSourceSkuMap().put(result, sku);

        ReflectionTestUtils.invokeMethod(strategy,
                "applyDailyStandardPlanQtyToContinuousResults", context, context.getScheduleWindowShifts());

        assertEquals(18, result.getClass5PlanQty().intValue(), "常规收尾SKU胎胚在机且20点后应补满当天中班");
        assertEquals(18, result.getClass6PlanQty().intValue(), "常规收尾SKU胎胚在机且20点后应补满下一个晚班");
    }

    @Test
    void applyDailyStandardPlanQtyToContinuousResults_shouldKeepOriginalEndingQtyWhenAutoFillDisabled() {
        LhScheduleContext context = newContext();
        setEndingAutoFillEnabled(context, false);
        LhShiftConfigVO afternoonShift = context.getScheduleWindowShifts().get(4);
        LocalDate businessDate = resolveShiftBusinessDate(afternoonShift);
        context.addStructurePlanMachineCount(businessDate, "PCR-01", 2);
        SkuScheduleDTO sku = buildMainSaleEndingSku("330200DISABLED", "PCR-01", "01");
        markRuntimeSharedEmbryo(context, sku, "330200DISABLED-SHARED");
        context.getEmbryoEndingFlagMap().put(sku.getEmbryoCode(), 0);
        LhScheduleResult result = buildMainSaleEndingResult(
                context, afternoonShift, "K1912", "330200DISABLED", 8);
        Date originalEndingTime = result.getSpecEndTime();
        context.getScheduleResultSourceSkuMap().put(result, sku);

        ReflectionTestUtils.invokeMethod(strategy,
                "applyDailyStandardPlanQtyToContinuousResults", context, context.getScheduleWindowShifts());

        assertEquals(Integer.valueOf(8), result.getClass5PlanQty(),
                "开关关闭时应保留SKU原收尾目标量");
        assertNull(result.getClass6PlanQty(), "开关关闭时不得新增下一夜班计划量");
        assertEquals(originalEndingTime, result.getSpecEndTime(), "开关关闭时不得延后收尾时间");
        assertEquals(0, context.getStructureScheduledMachineCount(businessDate, "PCR-01"),
                "开关关闭时不得登记收尾补满机台");
        assertEquals(0, context.getEndingFillAllowedOverQtyMap().size(),
                "开关关闭时不得登记收尾补满允许超量");
    }

    @Test
    void applyDailyStandardPlanQtyToContinuousResults_shouldKeepEndingFillWhenStrictTargetCappedAgain() {
        LhScheduleContext context = newContext();
        LhShiftConfigVO afternoonShift = context.getScheduleWindowShifts().get(4);
        LocalDate businessDate = resolveShiftBusinessDate(afternoonShift);
        context.addStructurePlanMachineCount(businessDate, "PCR-01", 2);
        SkuScheduleDTO sku = buildMainSaleEndingSku("330200NORMAL", "PCR-01", "02");
        sku.setTargetScheduleQty(8);
        sku.setRemainingScheduleQty(8);
        markRuntimeSharedEmbryo(context, sku, "330200NORMAL-SHARED");
        context.getEmbryoEndingFlagMap().put(sku.getEmbryoCode(), 0);
        LhScheduleResult result = buildMainSaleEndingResult(context, afternoonShift, "K1909", "330200NORMAL", 8);
        context.getScheduleResultSourceSkuMap().put(result, sku);

        ReflectionTestUtils.invokeMethod(strategy,
                "applyDailyStandardPlanQtyToContinuousResults", context, context.getScheduleWindowShifts());
        ReflectionTestUtils.invokeMethod(strategy, "capStrictEndingContinuationGroupToTarget",
                context, sku, Collections.singletonList(result), context.getScheduleWindowShifts());

        assertEquals(18, result.getClass5PlanQty().intValue(), "常规收尾补满中班不应被严格目标复核回裁");
        assertEquals(18, result.getClass6PlanQty().intValue(), "常规收尾补满夜班不应被严格目标复核回裁");
        assertEquals(36, ShiftFieldUtil.resolveScheduledQty(result), "补满允许超量应纳入最终严格收口上限");
    }

    @Test
    void applyDailyStandardPlanQtyToContinuousResults_shouldRecordOnlyFinalOverTargetQtyAsEndingFillAllowedOverQty() {
        LhScheduleContext context = newContext();
        LhShiftConfigVO afternoonShift = context.getScheduleWindowShifts().get(4);
        LocalDate businessDate = resolveShiftBusinessDate(afternoonShift);
        context.addStructurePlanMachineCount(businessDate, "PCR-01", 2);
        SkuScheduleDTO sku = buildMainSaleEndingSku("330200NORMAL", "PCR-01", "02");
        sku.setTargetScheduleQty(30);
        sku.setRemainingScheduleQty(30);
        markRuntimeSharedEmbryo(context, sku, "330200NORMAL-SHARED");
        context.getEmbryoEndingFlagMap().put(sku.getEmbryoCode(), 0);
        LhScheduleResult result = buildMainSaleEndingResult(context, afternoonShift, "K1910", "330200NORMAL", 8);
        context.getScheduleResultSourceSkuMap().put(result, sku);

        ReflectionTestUtils.invokeMethod(strategy,
                "applyDailyStandardPlanQtyToContinuousResults", context, context.getScheduleWindowShifts());
        ReflectionTestUtils.invokeMethod(strategy,
                "applyDailyStandardPlanQtyToContinuousResults", context, context.getScheduleWindowShifts());

        assertEquals(36, ShiftFieldUtil.resolveScheduledQty(result), "收尾补满后结果量应保持两班满班");
        assertEquals(Integer.valueOf(6), context.getEndingFillAllowedOverQtyMap().get(result),
                "允许超量只能按最终结果量超出SKU目标量部分覆盖登记，不能按本次补量累加");
    }

    @Test
    void resultValidation_shouldUseEndingFillAllowedOverQtyForStrictEndingLimit() {
        LhScheduleContext context = newContext();
        SkuScheduleDTO sku = buildMainSaleEndingSku("330200NORMAL", "PCR-01", "02");
        sku.setTargetScheduleQty(8);
        sku.setRemainingScheduleQty(8);
        LhScheduleResult result = new LhScheduleResult();
        result.setMaterialCode("330200NORMAL");
        result.setLhMachineCode("K1911");
        result.setMouldQty(2);
        result.setSingleMouldShiftQty(18);
        ShiftFieldUtil.setShiftPlanQty(result, 5, 18, new Date(), null);
        ShiftFieldUtil.setShiftPlanQty(result, 6, 18, new Date(), null);
        ShiftFieldUtil.syncDailyPlanQty(result);
        context.getScheduleResultList().add(result);
        context.getScheduleResultSourceSkuMap().put(result, sku);
        context.getEndingFillAllowedOverQtyMap().put(result, 28);
        ResultValidationHandler handler = new ResultValidationHandler();

        assertDoesNotThrow(() -> ReflectionTestUtils.invokeMethod(handler,
                "validateProductionQuantityPolicy", context), "目标量加允许超量内的收尾补满结果应通过最终校验");

        ShiftFieldUtil.setShiftPlanQty(result, 6, 20, new Date(), null);
        ShiftFieldUtil.syncDailyPlanQty(result);
        assertThrows(ScheduleException.class, () -> ReflectionTestUtils.invokeMethod(handler,
                "validateProductionQuantityPolicy", context), "超过目标量加允许超量后仍必须被最终校验拦截");
    }

    @Test
    void applyDailyStandardPlanQtyToContinuousResults_shouldNotFillWhenRuntimeSharedEmbryoMissing() {
        LhScheduleContext context = newContext();
        LhShiftConfigVO afternoonShift = context.getScheduleWindowShifts().get(4);
        LocalDate businessDate = resolveShiftBusinessDate(afternoonShift);
        context.addStructurePlanMachineCount(businessDate, "PCR-01", 2);
        SkuScheduleDTO sku = buildMainSaleEndingSku("330200SINGLE", "PCR-01", "01");
        markRuntimeSingleEmbryo(context, sku);
        context.getEmbryoEndingFlagMap().put(sku.getEmbryoCode(), 0);
        LhScheduleResult result = buildMainSaleEndingResult(context, afternoonShift, "K1908", "330200SINGLE", 8);
        context.getScheduleResultSourceSkuMap().put(result, sku);

        ReflectionTestUtils.invokeMethod(strategy,
                "applyDailyStandardPlanQtyToContinuousResults", context, context.getScheduleWindowShifts());

        assertEquals(8, result.getClass5PlanQty().intValue(), "非运行态共用胎胚不得补满中班");
        assertNull(result.getClass6PlanQty(), "非运行态共用胎胚不得补满下一个晚班");
    }

    @Test
    void applyDailyStandardPlanQtyToContinuousResults_shouldKeepOtherProductionTypeStrictTarget() {
        LhScheduleContext context = newContext();
        LhShiftConfigVO afternoonShift = context.getScheduleWindowShifts().get(4);
        LocalDate businessDate = resolveShiftBusinessDate(afternoonShift);
        context.addStructurePlanMachineCount(businessDate, "PCR-01", 2);
        SkuScheduleDTO sku = buildMainSaleEndingSku("330200OTHER", "PCR-01", "03");
        markRuntimeSharedEmbryo(context, sku, "330200OTHER-SHARED");
        context.getEmbryoEndingFlagMap().put(sku.getEmbryoCode(), 0);
        LhScheduleResult result = buildMainSaleEndingResult(context, afternoonShift, "K1905", "330200OTHER", 8);
        context.getScheduleResultSourceSkuMap().put(result, sku);

        ReflectionTestUtils.invokeMethod(strategy,
                "applyDailyStandardPlanQtyToContinuousResults", context, context.getScheduleWindowShifts());

        assertEquals(8, result.getClass5PlanQty().intValue(), "非01/02产品类型不得补满中班");
        assertNull(result.getClass6PlanQty(), "非01/02产品类型不得补满下一个晚班");
    }

    @Test
    void applyDailyStandardPlanQtyToContinuousResults_shouldNotFillWhenEmbryoEndingFlagIsNotOnMachine() {
        LhScheduleContext context = newContext();
        LhShiftConfigVO afternoonShift = context.getScheduleWindowShifts().get(4);
        LocalDate businessDate = resolveShiftBusinessDate(afternoonShift);
        context.addStructurePlanMachineCount(businessDate, "PCR-01", 2);
        SkuScheduleDTO sku = buildMainSaleEndingSku("330200EMBEND", "PCR-01", "01");
        markRuntimeSharedEmbryo(context, sku, "330200EMBEND-SHARED");
        context.getEmbryoEndingFlagMap().put(sku.getEmbryoCode(), 1);
        LhScheduleResult result = buildMainSaleEndingResult(context, afternoonShift, "K1906", "330200EMBEND", 8);
        context.getScheduleResultSourceSkuMap().put(result, sku);

        ReflectionTestUtils.invokeMethod(strategy,
                "applyDailyStandardPlanQtyToContinuousResults", context, context.getScheduleWindowShifts());

        assertEquals(8, result.getClass5PlanQty().intValue(), "胎胚收尾标识非0时不得补满中班");
        assertNull(result.getClass6PlanQty(), "胎胚收尾标识非0时不得补满下一个晚班");
    }

    @Test
    void applyDailyStandardPlanQtyToContinuousResults_shouldNotFillWhenEmbryoEndingFlagMissing() {
        LhScheduleContext context = newContext();
        LhShiftConfigVO afternoonShift = context.getScheduleWindowShifts().get(4);
        LocalDate businessDate = resolveShiftBusinessDate(afternoonShift);
        context.addStructurePlanMachineCount(businessDate, "PCR-01", 2);
        SkuScheduleDTO sku = buildMainSaleEndingSku("330200EMBMISSING", "PCR-01", "02");
        markRuntimeSharedEmbryo(context, sku, "330200EMBMISSING-SHARED");
        LhScheduleResult result = buildMainSaleEndingResult(context, afternoonShift, "K1907", "330200EMBMISSING", 8);
        context.getScheduleResultSourceSkuMap().put(result, sku);

        ReflectionTestUtils.invokeMethod(strategy,
                "applyDailyStandardPlanQtyToContinuousResults", context, context.getScheduleWindowShifts());

        assertEquals(8, result.getClass5PlanQty().intValue(), "胎胚收尾标识缺失时不得补满中班");
        assertNull(result.getClass6PlanQty(), "胎胚收尾标识缺失时不得补满下一个晚班");
    }

    @Test
    void applyDailyStandardPlanQtyToContinuousResults_shouldNotFillAtExactTwenty() {
        LhScheduleContext context = newContext();
        LhShiftConfigVO afternoonShift = context.getScheduleWindowShifts().get(4);
        LocalDate businessDate = resolveShiftBusinessDate(afternoonShift);
        context.addStructurePlanMachineCount(businessDate, "PCR-01", 2);
        SkuScheduleDTO sku = buildMainSaleEndingSku("330200MAIN", "PCR-01", "01");
        markRuntimeSharedEmbryo(context, sku, "330200MAIN-SHARED");
        context.getEmbryoEndingFlagMap().put(sku.getEmbryoCode(), 0);
        LhScheduleResult result = buildMainSaleEndingResult(context, afternoonShift, "K1903", "330200MAIN", 0);
        context.getScheduleResultSourceSkuMap().put(result, sku);

        ReflectionTestUtils.invokeMethod(strategy,
                "applyDailyStandardPlanQtyToContinuousResults", context, context.getScheduleWindowShifts());

        assertEquals(8, result.getClass5PlanQty().intValue(), "正好20:00不属于20:00之后");
        assertNull(result.getClass6PlanQty(), "正好20:00不得补满下一个晚班");
    }

    @Test
    void applyDailyStandardPlanQtyToContinuousResults_shouldNotFillWhenStructureMachineCountReached() {
        LhScheduleContext context = newContext();
        LhShiftConfigVO afternoonShift = context.getScheduleWindowShifts().get(4);
        LocalDate businessDate = resolveShiftBusinessDate(afternoonShift);
        context.addStructurePlanMachineCount(businessDate, "PCR-01", 1);
        context.recordScheduledMachine(businessDate, "PCR-01", "330200OTHER", null, "K1801");
        SkuScheduleDTO sku = buildMainSaleEndingSku("330200MAIN", "PCR-01", "01");
        markRuntimeSharedEmbryo(context, sku, "330200MAIN-SHARED");
        context.getEmbryoEndingFlagMap().put(sku.getEmbryoCode(), 0);
        LhScheduleResult result = buildMainSaleEndingResult(context, afternoonShift, "K1904", "330200MAIN", 8);
        context.getScheduleResultSourceSkuMap().put(result, sku);

        ReflectionTestUtils.invokeMethod(strategy,
                "applyDailyStandardPlanQtyToContinuousResults", context, context.getScheduleWindowShifts());

        assertEquals(8, result.getClass5PlanQty().intValue(), "结构机台数已达标时不得补满中班");
        assertNull(result.getClass6PlanQty(), "结构机台数已达标时不得补满下一个晚班");
    }

    @Test
    void buildScheduleResult_shouldWriteSpecialMaterialFlagByMaterialCode() {
        LhScheduleContext context = newContext();
        context.getSpecialMaterialCategoryByMaterialCode().put("MAT-SPECIAL", java.util.Collections.singleton("01"));
        MachineScheduleDTO machine = new MachineScheduleDTO();
        machine.setMachineCode("K1201");
        machine.setMachineName("FC-K1201");
        machine.setMaxMoldNum(1);

        SkuScheduleDTO sku = new SkuScheduleDTO();
        sku.setMaterialCode("MAT-SPECIAL");
        sku.setMaterialDesc("MAT-SPECIAL-DESC");
        sku.setStructureName("S-SPECIAL");
        sku.setSpecCode("SPEC-SPECIAL");
        sku.setEmbryoCode("EMB-SPECIAL");
        sku.setContinuousMachineCode("K1201");
        sku.setMonthPlanQty(30);
        sku.setWindowPlanQty(8);
        sku.setPendingQty(8);
        sku.setTargetScheduleQty(8);
        sku.setShiftCapacity(8);
        sku.setLhTimeSeconds(3600);
        sku.setScheduleType("01");

        when(orderNoGenerator.generateOrderNo(any())).thenReturn("LHGD20260411016");

        LhScheduleResult result = ReflectionTestUtils.invokeMethod(
                strategy,
                "buildScheduleResult",
                context,
                machine,
                sku,
                context.getScheduleWindowShifts().get(0).getShiftStartDateTime(),
                null,
                context.getScheduleWindowShifts(),
                1,
                false);

        assertEquals("1", result.getHasSpecialMaterial());
    }

    @Test
    void scheduleReduceMould_shouldKeepGlobalTargetWhenSingleMachineResultIsRefined() {
        LhScheduleContext context = newContext();
        MachineScheduleDTO machine1 = new MachineScheduleDTO();
        machine1.setMachineCode("M4");
        machine1.setMachineName("FC-M4");
        machine1.setMaxMoldNum(1);
        MachineScheduleDTO machine2 = new MachineScheduleDTO();
        machine2.setMachineCode("M5");
        machine2.setMachineName("FC-M5");
        machine2.setMaxMoldNum(1);
        context.setMachineScheduleMap(new LinkedHashMap<>());
        context.getMachineScheduleMap().put("M4", machine1);
        context.getMachineScheduleMap().put("M5", machine2);

        SkuScheduleDTO sku = new SkuScheduleDTO();
        sku.setMaterialCode("MAT-C4");
        sku.setMaterialDesc("MAT-C4-DESC");
        sku.setStructureName("S4");
        sku.setSpecCode("SPEC-C4");
        sku.setEmbryoCode("EMB-4");
        sku.setContinuousMachineCode("M4");
        sku.setMonthPlanQty(300);
        sku.setWindowPlanQty(8);
        sku.setPendingQty(8);
        sku.setTargetScheduleQty(128);
        sku.setShiftCapacity(16);
        sku.setLhTimeSeconds(3060);
        sku.setScheduleType("01");
        context.getContinuousSkuList().add(sku);

        when(orderNoGenerator.generateOrderNo(any())).thenReturn("LHGD20260411014");
        Date secondShiftStart = context.getScheduleWindowShifts().get(1).getShiftStartDateTime();
        LhScheduleResult refinedResult = ReflectionTestUtils.invokeMethod(
                strategy,
                "buildScheduleResult",
                context,
                machine1,
                sku,
                secondShiftStart,
                null,
                context.getScheduleWindowShifts(),
                1,
                false);
        context.getScheduleResultList().add(refinedResult);
        context.getScheduleResultSourceSkuMap().put(refinedResult, sku);

        LhScheduleResult extraResult = new LhScheduleResult();
        extraResult.setLhMachineCode("M5");
        extraResult.setMaterialCode("MAT-C4");
        extraResult.setScheduleType("01");
        extraResult.setLhTime(sku.getLhTimeSeconds());
        extraResult.setMouldQty(1);
        extraResult.setSingleMouldShiftQty(16);
        extraResult.setDailyPlanQty(8);
        extraResult.setClass1PlanQty(8);
        extraResult.setClass1StartTime(context.getScheduleWindowShifts().get(0).getShiftStartDateTime());
        extraResult.setClass1EndTime(context.getScheduleWindowShifts().get(0).getShiftEndDateTime());
        context.getScheduleResultList().add(extraResult);
        context.getScheduleResultSourceSkuMap().put(extraResult, sku);

        strategy.scheduleReduceMould(context);

        List<LhScheduleResult> materialResults = context.getScheduleResultList();
        int totalScheduledQty = materialResults.stream()
                .filter(result -> "MAT-C4".equals(result.getMaterialCode()))
                .mapToInt(LhScheduleResult::getDailyPlanQty)
                .sum();
        assertEquals(120, totalScheduledQty, "降模应按物料级全局目标量判断，不能被单机收敛值误缩到112");
        assertEquals(128, sku.getTargetScheduleQty().intValue(), "续作降模前后都应保留物料级全局目标量");
    }

    @Test
    void scheduleReduceMould_shouldAggregateSameShiftEndingWithinContinuationGroup() {
        LhScheduleContext context = newContext();
        MachineScheduleDTO machine1 = new MachineScheduleDTO();
        machine1.setMachineCode("M6");
        machine1.setMachineName("FC-M6");
        machine1.setMaxMoldNum(1);
        machine1.setCapsuleUsageCount(10);
        MachineScheduleDTO machine2 = new MachineScheduleDTO();
        machine2.setMachineCode("M7");
        machine2.setMachineName("FC-M7");
        machine2.setMaxMoldNum(1);
        machine2.setCapsuleUsageCount(5);
        context.setMachineScheduleMap(new LinkedHashMap<>());
        context.getMachineScheduleMap().put("M6", machine1);
        context.getMachineScheduleMap().put("M7", machine2);
        context.getInitialMachineScheduleMap().put("M6", machine1);
        context.getInitialMachineScheduleMap().put("M7", machine2);

        LhShiftConfigVO morningShift = context.getScheduleWindowShifts().get(0);
        Map<java.time.LocalDate, SkuDailyPlanQuotaDTO> quotaMap = new LinkedHashMap<>();
        java.time.LocalDate workDate = morningShift.getWorkDate().toInstant()
                .atZone(java.time.ZoneId.systemDefault()).toLocalDate();
        SkuDailyPlanQuotaDTO quota = new SkuDailyPlanQuotaDTO();
        quota.setMaterialCode("MAT-C5");
        quota.setProductionDate(workDate);
        quota.setDayPlanQty(16);
        quota.setRemainingQty(16);
        quotaMap.put(workDate, quota);

        SkuScheduleDTO sku = new SkuScheduleDTO();
        sku.setMaterialCode("MAT-C5");
        sku.setMaterialDesc("MAT-C5-DESC");
        sku.setStructureName("S5");
        sku.setSpecCode("SPEC-C5");
        sku.setEmbryoCode("EMB-5");
        sku.setContinuousMachineCode("M6");
        sku.setTargetScheduleQty(16);
        sku.setPendingQty(16);
        sku.setSurplusQty(16);
        sku.setShiftCapacity(16);
        sku.setLhTimeSeconds(3600);
        sku.setMouldQty(1);
        sku.setDailyPlanQuotaMap(quotaMap);
        context.getContinuousSkuList().add(sku);

        LhScheduleResult firstResult = buildSameShiftContinuousResult("M6", morningShift, 8);
        LhScheduleResult secondResult = buildSameShiftContinuousResult("M7", morningShift, 8);
        context.getScheduleResultList().add(firstResult);
        context.getScheduleResultList().add(secondResult);
        context.getScheduleResultSourceSkuMap().put(firstResult, sku);
        context.getScheduleResultSourceSkuMap().put(secondResult, sku);
        context.getMachineAssignmentMap().put("M6", new ArrayList<LhScheduleResult>());
        context.getMachineAssignmentMap().get("M6").add(firstResult);
        context.getMachineAssignmentMap().put("M7", new ArrayList<LhScheduleResult>());
        context.getMachineAssignmentMap().get("M7").add(secondResult);

        strategy.scheduleReduceMould(context);

        assertEquals(1, context.getScheduleResultList().size(), "同班次尾量归集后应清理零计划机台");
        LhScheduleResult retainedResult = context.getScheduleResultList().get(0);
        assertEquals("M6", retainedResult.getLhMachineCode(), "尾量应优先向保留排序更靠前机台集中");
        assertEquals(16, retainedResult.getDailyPlanQty().intValue());
        assertEquals(Integer.valueOf(16), retainedResult.getClass1PlanQty());
        assertNull(retainedResult.getClass2PlanQty(), "同班次归集不应再把尾量顺延到下一班次");
    }

    @Test
    void scheduleReduceMould_shouldPostponeHalfSharedEmbryoEndingMachinesToNextShift() {
        LhScheduleContext context = newContext();
        LhShiftConfigVO firstShift = context.getScheduleWindowShifts().get(0);
        LhShiftConfigVO secondShift = context.getScheduleWindowShifts().get(1);
        List<String> materialCodes = new ArrayList<>();
        for (int index = 1; index <= 6; index++) {
            String materialCode = "MAT-STAGGER-" + index;
            String machineCode = "KST0" + index;
            materialCodes.add(materialCode);
            registerMachine(context, machineCode);
            SkuScheduleDTO sku = buildSharedEmbryoStaggerSku(materialCode, "EMB-STAGGER", "01");
            LhScheduleResult result = buildSharedEmbryoStaggerResult(context, machineCode, sku, firstShift, 8);
            context.getScheduleResultList().add(result);
            context.getScheduleResultSourceSkuMap().put(result, sku);
            context.getMachineAssignmentMap().put(machineCode, new ArrayList<LhScheduleResult>());
            context.getMachineAssignmentMap().get(machineCode).add(result);
        }
        context.getActiveEmbryoSkuMap().put("EMB-STAGGER", materialCodes);
        context.getEmbryoEndingFlagMap().put("EMB-STAGGER", 0);

        strategy.scheduleReduceMould(context);

        assertSharedEmbryoStaggerPostponed(context, "KST01", secondShift, 24);
        assertSharedEmbryoStaggerPostponed(context, "KST02", secondShift, 24);
        assertSharedEmbryoStaggerPostponed(context, "KST03", secondShift, 24);
        assertSharedEmbryoStaggerKept(context, "KST04", 8);
        assertSharedEmbryoStaggerKept(context, "KST05", 8);
        assertSharedEmbryoStaggerKept(context, "KST06", 8);
    }

    @Test
    void sharedEmbryoEndingStagger_shouldKeepOriginalShiftWhenAutoFillDisabled() {
        LhScheduleContext context = newContext();
        setEndingAutoFillEnabled(context, false);
        LhShiftConfigVO firstShift = context.getScheduleWindowShifts().get(0);
        List<String> materialCodes = new ArrayList<>();
        for (int index = 1; index <= 2; index++) {
            String materialCode = "MAT-STAGGER-DISABLED-" + index;
            String machineCode = "KSD0" + index;
            materialCodes.add(materialCode);
            registerMachine(context, machineCode);
            SkuScheduleDTO sku = buildSharedEmbryoStaggerSku(materialCode, "EMB-STAGGER-DISABLED", "01");
            LhScheduleResult result = buildSharedEmbryoStaggerResult(context, machineCode, sku, firstShift, 8);
            context.getScheduleResultList().add(result);
            context.getScheduleResultSourceSkuMap().put(result, sku);
        }
        context.getActiveEmbryoSkuMap().put("EMB-STAGGER-DISABLED", materialCodes);
        context.getEmbryoEndingFlagMap().put("EMB-STAGGER-DISABLED", 0);

        ReflectionTestUtils.invokeMethod(strategy,
                "applySharedEmbryoEndingStaggerPostpone", context, context.getScheduleWindowShifts());

        assertSharedEmbryoStaggerKept(context, "KSD01", 8);
        assertSharedEmbryoStaggerKept(context, "KSD02", 8);
        assertEquals(firstShift.getShiftEndDateTime(), findResultByMachine(context, "KSD01").getSpecEndTime(),
                "开关关闭时收尾时间应保持原班次结束时间");
        assertEquals(0, context.getSharedEmbryoEndingStaggerAllowedOverQtyMap().size(),
                "开关关闭时不得登记错峰允许超量");
    }

    @Test
    void sharedEmbryoEndingStagger_shouldNotRecordOrRestoreReleasedCandidateWhenAutoFillDisabled() {
        LhScheduleContext context = newContext();
        setEndingAutoFillEnabled(context, false);
        LhShiftConfigVO firstShift = context.getScheduleWindowShifts().get(0);
        registerMachine(context, "KRD-DISABLED");
        SkuScheduleDTO sku = buildSharedEmbryoStaggerSku(
                "MAT-RELEASE-DISABLED", "EMB-RELEASE-DISABLED", "01");
        LhScheduleResult result = buildSharedEmbryoStaggerResult(
                context, "KRD-DISABLED", sku, firstShift, 8);
        context.getScheduleResultList().add(result);
        context.getScheduleResultSourceSkuMap().put(result, sku);

        ReflectionTestUtils.invokeMethod(strategy,
                "recordSharedEmbryoEndingStaggerReleaseCandidate", context, sku, result);
        ShiftFieldUtil.setShiftPlanQty(result, firstShift.getShiftIndex(), 0, null, null);
        ShiftFieldUtil.syncDailyPlanQty(result);
        ReflectionTestUtils.invokeMethod(strategy,
                "applySharedEmbryoEndingStaggerPostpone", context, context.getScheduleWindowShifts());

        assertEquals(0, context.getSharedEmbryoEndingStaggerReleaseShiftIndexMap().size(),
                "开关关闭时不得保存错峰释放班次快照");
        assertEquals(0, context.getSharedEmbryoEndingStaggerReleaseShiftQtyMap().size(),
                "开关关闭时不得保存错峰释放产量快照");
        assertEquals(0, result.getDailyPlanQty().intValue(),
                "开关关闭时已按正常降模释放的机台不得恢复产量");
        assertNull(result.getClass2PlanQty(), "开关关闭时不得生成延后班次计划量");
    }

    @Test
    void scheduleReduceMould_shouldSortSharedEmbryoEndingStaggerByMouldCommonalityAndCapsuleUsage() {
        LhScheduleContext context = newContext();
        LhShiftConfigVO firstShift = context.getScheduleWindowShifts().get(0);
        LhShiftConfigVO secondShift = context.getScheduleWindowShifts().get(1);
        List<String> materialCodes = new ArrayList<>();
        String[] machineCodes = {"KST11", "KST12", "KST13", "KST14"};
        String[] mouldCodes = {"M-ST-A", "M-ST-B", "M-ST-C", "M-ST-D"};
        int[] capsuleUsageCounts = {1, 20, 5, 1};
        for (int index = 0; index < machineCodes.length; index++) {
            String materialCode = "MAT-SORT-" + (index + 1);
            materialCodes.add(materialCode);
            registerMachine(context, machineCodes[index]);
            registerMachineMould(context, machineCodes[index], mouldCodes[index]);
            registerCapsuleUsage(context, machineCodes[index], capsuleUsageCounts[index]);
            SkuScheduleDTO sku = buildSharedEmbryoStaggerSku(materialCode, "EMB-SORT", "02");
            LhScheduleResult result = buildSharedEmbryoStaggerResult(context, machineCodes[index], sku, firstShift, 8);
            context.getScheduleResultList().add(result);
            context.getScheduleResultSourceSkuMap().put(result, sku);
        }
        registerMouldRel(context, "MAT-SORT-A1", "M-ST-A");
        registerMouldRel(context, "MAT-SORT-A2", "M-ST-A");
        registerMouldRel(context, "MAT-SORT-A3", "M-ST-A");
        registerMouldRel(context, "MAT-SORT-B1", "M-ST-B");
        registerMouldRel(context, "MAT-SORT-C1", "M-ST-C");
        registerMouldRel(context, "MAT-SORT-D1", "M-ST-D");
        registerMouldRel(context, "MAT-SORT-D2", "M-ST-D");
        context.getActiveEmbryoSkuMap().put("EMB-SORT", materialCodes);
        context.getEmbryoEndingFlagMap().put("EMB-SORT", 0);

        strategy.scheduleReduceMould(context);

        assertSharedEmbryoStaggerPostponed(context, "KST13", secondShift, 24);
        assertSharedEmbryoStaggerPostponed(context, "KST12", secondShift, 24);
        assertSharedEmbryoStaggerKept(context, "KST11", 8);
        assertSharedEmbryoStaggerKept(context, "KST14", 8);
    }

    @Test
    void scheduleReduceMould_shouldSkipSharedEmbryoEndingStaggerOnLastShift() {
        LhScheduleContext context = newContext();
        LhShiftConfigVO lastShift = context.getScheduleWindowShifts().get(7);
        List<String> materialCodes = new ArrayList<>();
        for (int index = 1; index <= 2; index++) {
            String materialCode = "MAT-LAST-" + index;
            String machineCode = "KLS0" + index;
            materialCodes.add(materialCode);
            registerMachine(context, machineCode);
            SkuScheduleDTO sku = buildSharedEmbryoStaggerSku(materialCode, "EMB-LAST", "01");
            LhScheduleResult result = buildSharedEmbryoStaggerResult(context, machineCode, sku, lastShift, 8);
            context.getScheduleResultList().add(result);
            context.getScheduleResultSourceSkuMap().put(result, sku);
        }
        context.getActiveEmbryoSkuMap().put("EMB-LAST", materialCodes);
        context.getEmbryoEndingFlagMap().put("EMB-LAST", 0);

        strategy.scheduleReduceMould(context);

        assertSharedEmbryoStaggerKept(context, "KLS01", 8);
        assertSharedEmbryoStaggerKept(context, "KLS02", 8);
    }

    @Test
    void allocateContinuationQtyForKeptMachines_shouldRecordReleasedMachineForSharedEmbryoEndingStagger() {
        LhScheduleContext context = newContext();
        LhShiftConfigVO firstShift = context.getScheduleWindowShifts().get(0);
        LhShiftConfigVO secondShift = context.getScheduleWindowShifts().get(1);
        registerMachine(context, "KRD01");
        registerMachine(context, "KRD02");
        registerCapsuleUsage(context, "KRD01", 100);
        registerCapsuleUsage(context, "KRD02", 1);
        SkuScheduleDTO sku = buildSharedEmbryoStaggerSku("MAT-REDUCE", "EMB-REDUCE", "01");
        List<String> activeMaterialCodes = new ArrayList<>();
        activeMaterialCodes.add("MAT-REDUCE");
        activeMaterialCodes.add("MAT-REDUCE-SHARED");
        context.getActiveEmbryoSkuMap().put("EMB-REDUCE", activeMaterialCodes);
        context.getEmbryoEndingFlagMap().put("EMB-REDUCE", 0);
        LhScheduleResult keptResult = buildSharedEmbryoStaggerResult(context, "KRD01", sku, firstShift, 8);
        LhScheduleResult releasedResult = buildSharedEmbryoStaggerResult(context, "KRD02", sku, firstShift, 8);
        context.getScheduleResultList().add(keptResult);
        context.getScheduleResultList().add(releasedResult);
        context.getScheduleResultSourceSkuMap().put(keptResult, sku);
        context.getScheduleResultSourceSkuMap().put(releasedResult, sku);
        List<LhScheduleResult> skuResults = new ArrayList<>();
        skuResults.add(keptResult);
        skuResults.add(releasedResult);
        Map<LhScheduleResult, Integer> capacityMap = new LinkedHashMap<>();
        capacityMap.put(keptResult, 8);
        capacityMap.put(releasedResult, 8);

        ReflectionTestUtils.invokeMethod(strategy, "allocateContinuationQtyForKeptMachines",
                context, sku, skuResults, Collections.singletonList(keptResult),
                capacityMap, 8, context.getScheduleWindowShifts());
        ReflectionTestUtils.invokeMethod(strategy,
                "applySharedEmbryoEndingStaggerPostpone", context, context.getScheduleWindowShifts());

        assertSharedEmbryoStaggerPostponed(context, "KRD02", secondShift, 24);
        assertSharedEmbryoStaggerKept(context, "KRD01", 8);
    }

    @Test
    void sharedEmbryoEndingStagger_shouldNotTrimRetainedMachineWhenPostponedResultProcessedFirst() {
        LhScheduleContext context = newContext();
        LhShiftConfigVO firstShift = context.getScheduleWindowShifts().get(0);
        LhShiftConfigVO secondShift = context.getScheduleWindowShifts().get(1);
        registerMachine(context, "KOR01");
        registerMachine(context, "KOR02");
        registerCapsuleUsage(context, "KOR01", 1);
        registerCapsuleUsage(context, "KOR02", 100);
        SkuScheduleDTO postponedSku = buildSharedEmbryoStaggerSku("MAT-ORDER", "EMB-ORDER", "01");
        SkuScheduleDTO retainedSku = buildSharedEmbryoStaggerSku("MAT-ORDER", "EMB-ORDER", "01");
        List<String> activeMaterialCodes = new ArrayList<>();
        activeMaterialCodes.add("MAT-ORDER");
        activeMaterialCodes.add("MAT-ORDER-SHARED");
        context.getActiveEmbryoSkuMap().put("EMB-ORDER", activeMaterialCodes);
        context.getEmbryoEndingFlagMap().put("EMB-ORDER", 0);

        // 刻意使用同物料不同DTO副本，并让优先后延机台排在结果列表前面，验证实际账本按物料扣减时不会误裁保留机台。
        LhScheduleResult postponedResult = buildSharedEmbryoStaggerResult(context, "KOR01", postponedSku, firstShift, 8);
        LhScheduleResult retainedResult = buildSharedEmbryoStaggerResult(context, "KOR02", retainedSku, firstShift, 8);
        context.getScheduleResultList().add(postponedResult);
        context.getScheduleResultList().add(retainedResult);
        context.getScheduleResultSourceSkuMap().put(postponedResult, postponedSku);
        context.getScheduleResultSourceSkuMap().put(retainedResult, retainedSku);

        ReflectionTestUtils.invokeMethod(strategy,
                "applySharedEmbryoEndingStaggerPostpone", context, context.getScheduleWindowShifts());
        ReflectionTestUtils.invokeMethod(strategy,
                "syncContinuousDailyPlanQuota", context, context.getScheduleWindowShifts());

        assertSharedEmbryoStaggerPostponed(context, "KOR01", secondShift, 24);
        assertSharedEmbryoStaggerKept(context, "KOR02", 8);
        assertEquals(Integer.valueOf(0), context.getSkuProductionRemainingQtyMap().get("MAT-ORDER"),
                "保留机台应优先占用SKU目标量，后延补量只作为错峰例外保留");
    }

    @Test
    void scheduleReduceMould_shouldSkipSharedEmbryoEndingStaggerWhenMachineAssignmentOccupied() {
        LhScheduleContext context = newContext();
        LhShiftConfigVO firstShift = context.getScheduleWindowShifts().get(0);
        LhShiftConfigVO secondShift = context.getScheduleWindowShifts().get(1);
        registerMachine(context, "KOC01");
        registerMachine(context, "KOC02");
        List<String> materialCodes = new ArrayList<>();
        materialCodes.add("MAT-OCCUPY-1");
        materialCodes.add("MAT-OCCUPY-2");
        context.getActiveEmbryoSkuMap().put("EMB-OCCUPY", materialCodes);
        context.getEmbryoEndingFlagMap().put("EMB-OCCUPY", 0);

        SkuScheduleDTO firstSku = buildSharedEmbryoStaggerSku("MAT-OCCUPY-1", "EMB-OCCUPY", "01");
        SkuScheduleDTO secondSku = buildSharedEmbryoStaggerSku("MAT-OCCUPY-2", "EMB-OCCUPY", "01");
        LhScheduleResult firstResult = buildSharedEmbryoStaggerResult(context, "KOC01", firstSku, firstShift, 8);
        LhScheduleResult secondResult = buildSharedEmbryoStaggerResult(context, "KOC02", secondSku, firstShift, 8);
        LhScheduleResult occupiedResult = buildSharedEmbryoStaggerResult(
                context, "KOC01", buildSharedEmbryoStaggerSku("MAT-OTHER", "EMB-OTHER", "01"), secondShift, 16);
        context.getScheduleResultList().add(firstResult);
        context.getScheduleResultList().add(secondResult);
        context.getScheduleResultSourceSkuMap().put(firstResult, firstSku);
        context.getScheduleResultSourceSkuMap().put(secondResult, secondSku);
        context.getMachineAssignmentMap().put("KOC01", new ArrayList<LhScheduleResult>());
        context.getMachineAssignmentMap().get("KOC01").add(firstResult);
        context.getMachineAssignmentMap().get("KOC01").add(occupiedResult);

        strategy.scheduleReduceMould(context);

        assertSharedEmbryoStaggerKept(context, "KOC01", 8);
        assertSharedEmbryoStaggerKept(context, "KOC02", 8);
    }

    @Test
    void scheduleReduceMould_shouldSkipSharedEmbryoEndingStaggerWhenScheduleResultOccupied() {
        LhScheduleContext context = newContext();
        LhShiftConfigVO firstShift = context.getScheduleWindowShifts().get(0);
        LhShiftConfigVO secondShift = context.getScheduleWindowShifts().get(1);
        registerMachine(context, "KSC01");
        registerMachine(context, "KSC02");
        List<String> materialCodes = new ArrayList<>();
        materialCodes.add("MAT-SCHEDULE-OCCUPY-1");
        materialCodes.add("MAT-SCHEDULE-OCCUPY-2");
        context.getActiveEmbryoSkuMap().put("EMB-SCHEDULE-OCCUPY", materialCodes);
        context.getEmbryoEndingFlagMap().put("EMB-SCHEDULE-OCCUPY", 0);

        SkuScheduleDTO firstSku = buildSharedEmbryoStaggerSku("MAT-SCHEDULE-OCCUPY-1", "EMB-SCHEDULE-OCCUPY", "01");
        SkuScheduleDTO secondSku = buildSharedEmbryoStaggerSku("MAT-SCHEDULE-OCCUPY-2", "EMB-SCHEDULE-OCCUPY", "01");
        LhScheduleResult firstResult = buildSharedEmbryoStaggerResult(context, "KSC01", firstSku, firstShift, 8);
        LhScheduleResult secondResult = buildSharedEmbryoStaggerResult(context, "KSC02", secondSku, firstShift, 8);
        LhScheduleResult occupiedResult = buildSharedEmbryoStaggerResult(
                context, "KSC01", buildSharedEmbryoStaggerSku("MAT-SCHEDULE-OTHER", "EMB-SCHEDULE-OTHER", "01"),
                secondShift, 16);
        occupiedResult.setScheduleType("03");
        context.getScheduleResultList().add(firstResult);
        context.getScheduleResultList().add(secondResult);
        context.getScheduleResultList().add(occupiedResult);
        context.getScheduleResultSourceSkuMap().put(firstResult, firstSku);
        context.getScheduleResultSourceSkuMap().put(secondResult, secondSku);

        strategy.scheduleReduceMould(context);

        assertSharedEmbryoStaggerKept(context, "KSC01", 8);
        assertSharedEmbryoStaggerKept(context, "KSC02", 8);
    }

    @Test
    void sharedEmbryoEndingStagger_shouldRollbackReleasedCandidateWhenApplyFails() {
        LhScheduleContext context = newContext();
        LhShiftConfigVO firstShift = context.getScheduleWindowShifts().get(0);
        LhShiftConfigVO secondShift = context.getScheduleWindowShifts().get(1);
        registerMachine(context, "KRB01");
        SkuScheduleDTO sku = buildSharedEmbryoStaggerSku("MAT-ROLLBACK", "EMB-ROLLBACK", "01");
        LhScheduleResult result = buildSharedEmbryoStaggerResult(context, "KRB01", sku, firstShift, 8);
        context.getScheduleResultList().add(result);
        context.getScheduleResultSourceSkuMap().put(result, sku);
        ReflectionTestUtils.invokeMethod(strategy,
                "recordSharedEmbryoEndingStaggerReleaseCandidate", context, sku, result);
        ShiftFieldUtil.setShiftPlanQty(result, firstShift.getShiftIndex(), 0, null, null);
        ShiftFieldUtil.syncDailyPlanQty(result);
        result.setSingleMouldShiftQty(0);

        Boolean applied = ReflectionTestUtils.invokeMethod(strategy,
                "applySharedEmbryoEndingStaggerPostponeResult", context, result, firstShift.getShiftIndex(),
                secondShift, context.getScheduleWindowShifts(), new LinkedHashMap<String, Integer>());

        assertEquals(Boolean.FALSE, applied, "下一班次无产能时不应完成后延");
        assertEquals(0, result.getDailyPlanQty().intValue(), "后延失败后应保持降模释放后的零计划状态");
        assertEquals(0, ShiftFieldUtil.getShiftPlanQty(result, firstShift.getShiftIndex()) == null
                ? 0 : ShiftFieldUtil.getShiftPlanQty(result, firstShift.getShiftIndex()).intValue(),
                "后延失败后不应恢复原收尾班次计划量");
    }

    private LhScheduleContext newContext() {
        LhScheduleContext context = new LhScheduleContext();
        context.setFactoryCode("116");
        context.setBatchNo("LHPC20260411011");
        context.setScheduleDate(date(2026, 4, 11));
        context.setScheduleTargetDate(date(2026, 4, 13));
        context.setScheduleWindowShifts(LhScheduleTimeUtil.buildDefaultScheduleShifts(context, context.getScheduleDate()));
        return context;
    }

    private List<MachineCleaningWindowDTO> buildDryIceCleaningWindowList() {
        MachineCleaningWindowDTO cleaningWindow = new MachineCleaningWindowDTO();
        cleaningWindow.setCleanType("01");
        cleaningWindow.setLeftRightMould("LR");
        cleaningWindow.setCleanStartTime(dateTime(2026, 4, 11, 15, 0, 0));
        cleaningWindow.setCleanEndTime(dateTime(2026, 4, 11, 18, 0, 0));
        cleaningWindow.setReadyTime(dateTime(2026, 4, 11, 18, 0, 0));
        List<MachineCleaningWindowDTO> cleaningWindowList = new ArrayList<>();
        cleaningWindowList.add(cleaningWindow);
        return cleaningWindowList;
    }

    private LhScheduleResult buildSameShiftContinuousResult(String machineCode, LhShiftConfigVO shift, int qty) {
        LhScheduleResult result = new LhScheduleResult();
        result.setScheduleType("01");
        result.setIsTypeBlock("0");
        result.setMaterialCode("MAT-C5");
        result.setMaterialDesc("MAT-C5-DESC");
        result.setLhMachineCode(machineCode);
        result.setLhMachineName(machineCode);
        result.setEmbryoCode("EMB-5");
        result.setMouldSurplusQty(16);
        result.setEmbryoStock(0);
        result.setLhTime(3600);
        result.setMouldQty(1);
        result.setSingleMouldShiftQty(16);
        result.setIsEnd("1");
        ShiftFieldUtil.setShiftPlanQty(result, shift.getShiftIndex(), qty,
                shift.getShiftStartDateTime(), shift.getShiftEndDateTime());
        ShiftFieldUtil.syncDailyPlanQty(result);
        result.setSpecEndTime(shift.getShiftEndDateTime());
        result.setTdaySpecEndTime(shift.getShiftEndDateTime());
        return result;
    }

    private void registerMachine(LhScheduleContext context, String machineCode) {
        MachineScheduleDTO machine = new MachineScheduleDTO();
        machine.setMachineCode(machineCode);
        machine.setMachineName(machineCode);
        machine.setMaxMoldNum(1);
        context.getMachineScheduleMap().put(machineCode, machine);
        context.getInitialMachineScheduleMap().put(machineCode, machine);
    }

    private void registerMachineMould(LhScheduleContext context, String machineCode, String mouldCode) {
        LhMachineOnlineInfo onlineInfo = new LhMachineOnlineInfo();
        onlineInfo.setLhCode(machineCode);
        onlineInfo.setInMachineMouldCode(mouldCode);
        context.getMachineOnlineInfoMap().put(machineCode, onlineInfo);
    }

    private void registerCapsuleUsage(LhScheduleContext context, String machineCode, int usageCount) {
        LhRepairCapsule capsule = new LhRepairCapsule();
        capsule.setReplaceCapsuleCount(usageCount);
        context.getCapsuleUsageMap().put(machineCode, capsule);
    }

    private void registerMouldRel(LhScheduleContext context, String materialCode, String mouldCode) {
        MdmSkuMouldRel rel = new MdmSkuMouldRel();
        rel.setMaterialCode(materialCode);
        rel.setMouldCode(mouldCode);
        context.getSkuMouldRelMap().computeIfAbsent(materialCode, key -> new ArrayList<MdmSkuMouldRel>()).add(rel);
    }

    private SkuScheduleDTO buildSharedEmbryoStaggerSku(String materialCode, String embryoCode, String productionType) {
        SkuScheduleDTO sku = new SkuScheduleDTO();
        sku.setMaterialCode(materialCode);
        sku.setMaterialDesc(materialCode + "-DESC");
        sku.setStructureName("PCR-STAGGER");
        sku.setSpecCode("SPEC-" + materialCode);
        sku.setEmbryoCode(embryoCode);
        sku.setSkuTag("02");
        sku.setProductionType(productionType);
        sku.setStrictTargetQty(true);
        sku.setTargetScheduleQty(8);
        sku.setPendingQty(8);
        sku.setSurplusQty(8);
        sku.setShiftCapacity(16);
        sku.setLhTimeSeconds(3600);
        sku.setMouldQty(1);
        sku.setScheduleType("01");
        return sku;
    }

    private LhScheduleResult buildSharedEmbryoStaggerResult(LhScheduleContext context,
                                                            String machineCode,
                                                            SkuScheduleDTO sku,
                                                            LhShiftConfigVO shift,
                                                            int qty) {
        LhScheduleResult result = new LhScheduleResult();
        result.setScheduleType("01");
        result.setIsTypeBlock("0");
        result.setMaterialCode(sku.getMaterialCode());
        result.setMaterialDesc(sku.getMaterialDesc());
        result.setStructureName(sku.getStructureName());
        result.setLhMachineCode(machineCode);
        result.setLhMachineName(machineCode);
        result.setEmbryoCode(sku.getEmbryoCode());
        result.setMouldSurplusQty(sku.getSurplusQty());
        result.setEmbryoStock(0);
        result.setLhTime(sku.getLhTimeSeconds());
        result.setMouldQty(1);
        result.setSingleMouldShiftQty(16);
        result.setIsEnd("1");
        ShiftFieldUtil.setShiftPlanQty(result, shift.getShiftIndex(), qty,
                shift.getShiftStartDateTime(), shift.getShiftEndDateTime());
        ShiftFieldUtil.syncDailyPlanQty(result);
        result.setSpecEndTime(shift.getShiftEndDateTime());
        result.setTdaySpecEndTime(shift.getShiftEndDateTime());
        context.getContinuousSkuList().add(sku);
        return result;
    }

    private void assertSharedEmbryoStaggerPostponed(LhScheduleContext context,
                                                    String machineCode,
                                                    LhShiftConfigVO nextShift,
                                                    int dailyPlanQty) {
        LhScheduleResult result = findResultByMachine(context, machineCode);
        assertEquals(dailyPlanQty, result.getDailyPlanQty().intValue(), "后延机台应保留原班次并补满下一班次");
        assertEquals(Integer.valueOf(16), ShiftFieldUtil.getShiftPlanQty(result, nextShift.getShiftIndex()),
                "后延机台下一班次应补满可排产能");
        assertEquals(nextShift.getShiftEndDateTime(), result.getSpecEndTime(), "后延后收尾时间必须推到下一班次");
    }

    private void assertSharedEmbryoStaggerKept(LhScheduleContext context, String machineCode, int dailyPlanQty) {
        LhScheduleResult result = findResultByMachine(context, machineCode);
        assertEquals(dailyPlanQty, result.getDailyPlanQty().intValue(), "保留收尾机台不应新增下一班次补量");
        Integer nextShiftPlanQty = result.getClass2PlanQty();
        assertEquals(0, nextShiftPlanQty == null ? 0 : nextShiftPlanQty.intValue(),
                "保留收尾机台不应被后延到下一班次");
    }

    private LhScheduleResult findResultByMachine(LhScheduleContext context, String machineCode) {
        return context.getScheduleResultList().stream()
                .filter(result -> machineCode.equals(result.getLhMachineCode()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("未找到机台结果: " + machineCode));
    }

    private SkuScheduleDTO buildMainSaleEndingSku(String materialCode, String structureName, String productionType) {
        SkuScheduleDTO sku = new SkuScheduleDTO();
        sku.setMaterialCode(materialCode);
        sku.setStructureName(structureName);
        sku.setEmbryoCode("EMB-" + materialCode);
        sku.setSkuTag("02");
        sku.setProductionType(productionType);
        sku.setStrictTargetQty(true);
        sku.setSurplusQty(8);
        sku.setEmbryoStock(8);
        sku.setMouldQty(2);
        return sku;
    }

    private void setEndingAutoFillEnabled(LhScheduleContext context, boolean enabled) {
        Map<String, String> paramMap = new LinkedHashMap<>();
        paramMap.put(LhScheduleParamConstant.ENDING_AUTO_FILL_ENABLED, enabled ? "1" : "0");
        context.setScheduleConfig(new LhScheduleConfig(paramMap));
    }

    private void markRuntimeSharedEmbryo(LhScheduleContext context, SkuScheduleDTO sku, String sharedMaterialCode) {
        List<String> activeSkuList = new ArrayList<>();
        activeSkuList.add(sku.getMaterialCode());
        activeSkuList.add(sharedMaterialCode);
        context.getActiveEmbryoSkuMap().put(sku.getEmbryoCode(), activeSkuList);
    }

    private void markRuntimeSingleEmbryo(LhScheduleContext context, SkuScheduleDTO sku) {
        List<String> activeSkuList = new ArrayList<>();
        activeSkuList.add(sku.getMaterialCode());
        context.getActiveEmbryoSkuMap().put(sku.getEmbryoCode(), activeSkuList);
    }

    private LhScheduleResult buildMainSaleEndingResult(LhScheduleContext context,
                                                       LhShiftConfigVO afternoonShift,
                                                       String machineCode,
                                                       String materialCode,
                                                       int minutesAfterTwenty) {
        LhScheduleResult result = new LhScheduleResult();
        result.setMaterialCode(materialCode);
        result.setStructureName("PCR-01");
        result.setLhMachineCode(machineCode);
        result.setScheduleType("01");
        result.setSingleMouldShiftQty(18);
        result.setMouldQty(2);
        result.setLhTime(1800);
        result.setIsEnd("1");
        Date endingTime = dateTime(2026, 4, 12, 20, minutesAfterTwenty, 0);
        ShiftFieldUtil.setShiftPlanQty(result, afternoonShift.getShiftIndex(), 8,
                afternoonShift.getShiftStartDateTime(), endingTime);
        ShiftFieldUtil.syncDailyPlanQty(result);
        result.setSpecEndTime(endingTime);
        result.setTdaySpecEndTime(endingTime);
        context.getScheduleResultList().add(result);
        return result;
    }

    private LocalDate resolveShiftBusinessDate(LhShiftConfigVO shift) {
        return shift.getWorkDate().toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
    }

    private static Date date(int year, int month, int day) {
        Calendar calendar = Calendar.getInstance();
        calendar.clear();
        calendar.set(Calendar.YEAR, year);
        calendar.set(Calendar.MONTH, month - 1);
        calendar.set(Calendar.DAY_OF_MONTH, day);
        return calendar.getTime();
    }

    private static Date dateTime(int year, int month, int day, int hour, int minute, int second) {
        Calendar calendar = Calendar.getInstance();
        calendar.clear();
        calendar.set(Calendar.YEAR, year);
        calendar.set(Calendar.MONTH, month - 1);
        calendar.set(Calendar.DAY_OF_MONTH, day);
        calendar.set(Calendar.HOUR_OF_DAY, hour);
        calendar.set(Calendar.MINUTE, minute);
        calendar.set(Calendar.SECOND, second);
        return calendar.getTime();
    }
}
