package com.zlt.aps.lh.regression;

import com.zlt.aps.lh.api.constant.LhScheduleParamConstant;
import com.zlt.aps.lh.api.domain.dto.MachineCleaningWindowDTO;
import com.zlt.aps.lh.api.domain.dto.MachineScheduleDTO;
import com.zlt.aps.lh.api.domain.dto.SkuScheduleDTO;
import com.zlt.aps.lh.api.domain.entity.LhMachineOnlineInfo;
import com.zlt.aps.lh.api.domain.entity.LhPrecisionPlan;
import com.zlt.aps.lh.api.domain.entity.LhScheduleProcessLog;
import com.zlt.aps.lh.api.domain.entity.LhScheduleResult;
import com.zlt.aps.lh.api.domain.entity.LhSpecifyMachine;
import com.zlt.aps.lh.api.enums.JobTypeEnum;
import com.zlt.aps.lh.component.OrderNoGenerator;
import com.zlt.aps.lh.context.LhScheduleConfig;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.engine.strategy.impl.DefaultCapacityCalculateStrategy;
import com.zlt.aps.lh.engine.strategy.impl.DefaultFirstInspectionBalanceStrategy;
import com.zlt.aps.lh.engine.strategy.impl.DefaultMouldChangeBalanceStrategy;
import com.zlt.aps.lh.engine.strategy.IEndingJudgmentStrategy;
import com.zlt.aps.lh.engine.strategy.impl.ContinuousProductionStrategy;
import com.zlt.aps.lh.engine.strategy.impl.TypeBlockProductionStrategy;
import com.zlt.aps.lh.util.LhScheduleTimeUtil;
import com.zlt.aps.lh.util.ShiftFieldUtil;
import com.zlt.aps.mdm.api.domain.entity.MdmMaterialInfo;
import com.zlt.aps.mdm.api.domain.entity.MdmDevicePlanShut;
import com.zlt.aps.mdm.api.domain.entity.MdmSkuMouldRel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 续作阶段同产品结构直续/换活字块回归测试。
 */
@ExtendWith(MockitoExtension.class)
class ContinuousProductionTypeBlockRegressionTest {

    @Mock
    private OrderNoGenerator orderNoGenerator;

    @Mock
    private IEndingJudgmentStrategy endingJudgmentStrategy;

    @Spy
    private DefaultMouldChangeBalanceStrategy mouldChangeBalanceStrategy = new DefaultMouldChangeBalanceStrategy();

    @Spy
    private DefaultFirstInspectionBalanceStrategy firstInspectionBalanceStrategy =
            new DefaultFirstInspectionBalanceStrategy();

    @Spy
    private DefaultCapacityCalculateStrategy capacityCalculateStrategy = new DefaultCapacityCalculateStrategy();

    @InjectMocks
    private ContinuousProductionStrategy strategy;
    @InjectMocks
    private TypeBlockProductionStrategy typeBlockProductionStrategy;

    @Test
    void scheduleTypeBlockChange_shouldSkipSameStructureDirectContinuousAndUsePriorityOneCandidate() {
        LhScheduleContext context = newContext();
        context.getMachineScheduleMap().put("M1", buildMachine("M1", "MAT-C1"));
        context.getContinuousSkuList().add(buildContinuousSku("MAT-C1", "M1", "EMB-1", "STRUCT-A", "SPEC-A", "PAT-A", 1));
        context.getNewSpecSkuList().add(buildNewSku("MAT-S1", "EMB-1", "STRUCT-A", "SPEC-X", "PAT-X", 4));
        context.getNewSpecSkuList().add(buildNewSku("MAT-P1", "EMB-9", "STRUCT-B", "SPEC-B", "PAT-A", 4));
        putMaterialInfo(context, "MAT-C1", "胎胚描述-A", "SPEC-A", "PAT-A", "PAT-A");
        putMaterialInfo(context, "MAT-S1", "胎胚描述-X", "SPEC-X", "PAT-X", "PAT-X");
        putMaterialInfo(context, "MAT-P1", "胎胚描述-A", "SPEC-B", "PAT-A", "PAT-A");
        putMouldRel(context, "MAT-C1", "MOULD-1");
        putMouldRel(context, "MAT-S1", "MOULD-1");
        putMouldRel(context, "MAT-P1", "MOULD-1");

        when(orderNoGenerator.generateOrderNo(any())).thenReturn("ORD-1", "ORD-2");
        when(endingJudgmentStrategy.isEnding(any(), any())).thenAnswer(invocation -> {
            SkuScheduleDTO sku = invocation.getArgument(1);
            return "MAT-C1".equals(sku.getMaterialCode()) || "MAT-P1".equals(sku.getMaterialCode());
        });

        strategy.scheduleContinuousEnding(context);
        typeBlockProductionStrategy.scheduleTypeBlockChange(context);

        assertEquals(2, context.getScheduleResultList().size());
        LhScheduleResult continuousResult = context.getScheduleResultList().get(0);
        LhScheduleResult followUpResult = context.getScheduleResultList().get(1);
        assertEquals("MAT-P1", followUpResult.getMaterialCode());
        assertEquals("03", followUpResult.getScheduleType());
        assertEquals("0", continuousResult.getIsTypeBlock());
        assertEquals("1", followUpResult.getIsEnd());
        assertEquals("1", followUpResult.getIsChangeMould());
        assertEquals("1", followUpResult.getIsTypeBlock());
        assertNotNull(followUpResult.getSpecEndTime());
        assertNotNull(followUpResult.getTdaySpecEndTime());
        assertEquals(LhScheduleTimeUtil.addHours(continuousResult.getSpecEndTime(),
                        LhScheduleTimeUtil.getTypeBlockChangeTotalHours(context)),
                resolveFirstStartTime(followUpResult));
        assertEquals("MAT-P1", context.getMachineScheduleMap().get("M1").getCurrentMaterialCode());
        assertEquals(1, context.getNewSpecSkuList().size());
        assertEquals("MAT-S1", context.getNewSpecSkuList().get(0).getMaterialCode());
    }

    @Test
    void scheduleTypeBlockChange_shouldPrioritizeLimitSpecifySkuAfterEnding() {
        LhScheduleContext context = newContext();
        enableSpecifyMachineRule(context);
        context.getMachineScheduleMap().put("M1", buildMachine("M1", "MAT-C1"));
        context.getContinuousSkuList().add(buildContinuousSku("MAT-C1", "M1", "EMB-1", "STRUCT-A", "SPEC-A", "PAT-A", 1));
        context.getNewSpecSkuList().add(buildNewSku("MAT-PRIORITY", "EMB-9", "STRUCT-B", "SPEC-B", "PAT-A", 4));
        context.getNewSpecSkuList().add(buildNewSku("MAT-SPECIFY", "EMB-1", "STRUCT-C", "SPEC-A", "PAT-B", 4));
        context.getSpecifyMachineMap().put("MAT-SPECIFY", Arrays.asList(
                specifyMachine("MAT-SPECIFY", "M1", JobTypeEnum.RESTRICTED.getCode())));
        putMaterialInfo(context, "MAT-C1", "胎胚描述-A", "SPEC-A", "PAT-A", "PAT-A");
        putMaterialInfo(context, "MAT-PRIORITY", "胎胚描述-A", "SPEC-B", "PAT-A", "PAT-A");
        putMaterialInfo(context, "MAT-SPECIFY", "胎胚描述-X", "SPEC-A", "PAT-B", "PAT-B");
        putMouldRel(context, "MAT-C1", "MOULD-1");
        putMouldRel(context, "MAT-PRIORITY", "MOULD-1");
        putMouldRel(context, "MAT-SPECIFY", "MOULD-1");

        when(orderNoGenerator.generateOrderNo(any())).thenReturn("ORD-1", "ORD-2");
        when(endingJudgmentStrategy.isEnding(any(), any())).thenAnswer(invocation -> {
            SkuScheduleDTO sku = invocation.getArgument(1);
            return sku != null && "MAT-C1".equals(sku.getMaterialCode());
        });

        strategy.scheduleContinuousEnding(context);
        typeBlockProductionStrategy.scheduleTypeBlockChange(context);

        assertEquals(2, context.getScheduleResultList().size());
        assertEquals("MAT-SPECIFY", context.getScheduleResultList().get(1).getMaterialCode(),
                "收尾机台应优先衔接当前机台配置的限制作业定点物料");
        assertEquals(1, context.getNewSpecSkuList().size());
        assertEquals("MAT-PRIORITY", context.getNewSpecSkuList().get(0).getMaterialCode());
    }

    @Test
    void scheduleContinuousEnding_shouldSqueezeLastWorkDayForLimitSpecifySku() {
        LhScheduleContext context = newContext();
        enableSpecifyMachineRule(context);
        context.getMachineScheduleMap().put("M1", buildMachine("M1", "MAT-C1"));
        context.getContinuousSkuList().add(buildContinuousSku("MAT-C1", "M1", "EMB-1", "STRUCT-A", "SPEC-A", "PAT-A", 80));
        context.getNewSpecSkuList().add(buildNewSku("MAT-SPECIFY", "EMB-1", "STRUCT-B", "SPEC-A", "PAT-B", 8));
        context.getSpecifyMachineMap().put("MAT-SPECIFY", Arrays.asList(
                specifyMachine("MAT-SPECIFY", "M1", JobTypeEnum.RESTRICTED.getCode())));
        putMouldRel(context, "MAT-C1", "MOULD-1");
        putMouldRel(context, "MAT-SPECIFY", "MOULD-1");

        when(orderNoGenerator.generateOrderNo(any())).thenReturn("ORD-1", "ORD-2");
        when(endingJudgmentStrategy.isEnding(any(), any())).thenReturn(false);

        strategy.scheduleContinuousEnding(context);

        LhScheduleResult continuousResult = context.getScheduleResultList().get(0);
        assertNull(ShiftFieldUtil.getShiftStartTime(continuousResult, 6),
                "触发挤量后，当前在机物料不应占用最后业务日夜班");
        assertNull(ShiftFieldUtil.getShiftStartTime(continuousResult, 7),
                "触发挤量后，当前在机物料不应占用最后业务日早班");
        assertNull(ShiftFieldUtil.getShiftStartTime(continuousResult, 8),
                "触发挤量后，当前在机物料不应占用最后业务日中班");

        typeBlockProductionStrategy.scheduleTypeBlockChange(context);

        assertEquals(2, context.getScheduleResultList().size());
        assertEquals("MAT-SPECIFY", context.getScheduleResultList().get(1).getMaterialCode(),
                "让出的最后业务日班次应优先分配给定点物料");
    }

    @Test
    void scheduleContinuousEnding_shouldNotSqueezeWhenSpecifyMachineRuleDisabled() {
        LhScheduleContext context = newContext();
        context.getMachineScheduleMap().put("M1", buildMachine("M1", "MAT-C1"));
        context.getContinuousSkuList().add(buildContinuousSku("MAT-C1", "M1", "EMB-1", "STRUCT-A", "SPEC-A", "PAT-A", 80));
        context.getNewSpecSkuList().add(buildNewSku("MAT-SPECIFY", "EMB-1", "STRUCT-B", "SPEC-A", "PAT-B", 8));
        context.getSpecifyMachineMap().put("MAT-SPECIFY", Arrays.asList(
                specifyMachine("MAT-SPECIFY", "M1", JobTypeEnum.RESTRICTED.getCode())));
        putMouldRel(context, "MAT-C1", "MOULD-1");
        putMouldRel(context, "MAT-SPECIFY", "MOULD-1");

        when(orderNoGenerator.generateOrderNo(any())).thenReturn("ORD-1", "ORD-2");
        when(endingJudgmentStrategy.isEnding(any(), any())).thenReturn(false);

        strategy.scheduleContinuousEnding(context);

        LhScheduleResult continuousResult = context.getScheduleResultList().get(0);
        assertNotNull(ShiftFieldUtil.getShiftStartTime(continuousResult, 6),
                "关闭开关后不应为定点物料挤出最后业务日夜班");
        assertNotNull(ShiftFieldUtil.getShiftStartTime(continuousResult, 7),
                "关闭开关后不应为定点物料挤出最后业务日早班");
        assertNotNull(ShiftFieldUtil.getShiftStartTime(continuousResult, 8),
                "关闭开关后不应为定点物料挤出最后业务日中班");
        assertTrue(context.getSpecifyMachineReservedMaterialMap().isEmpty(), "关闭开关后不应写入定点预留物料");
        assertTrue(context.getSpecifyMachineReservedSwitchStartTimeMap().isEmpty(), "关闭开关后不应写入定点预留时间");
    }

    @Test
    void scheduleContinuousEnding_shouldNotSqueezeWhenSpecifySkuCannotUseReservedMachine() {
        LhScheduleContext context = newContext();
        enableSpecifyMachineRule(context);
        context.getMachineScheduleMap().put("M1", buildMachine("M1", "MAT-C1"));
        context.getContinuousSkuList().add(buildContinuousSku("MAT-C1", "M1", "EMB-1", "STRUCT-A", "SPEC-A", "PAT-A", 80));
        context.getNewSpecSkuList().add(buildNewSku("MAT-SPECIFY", "EMB-9", "STRUCT-B", "SPEC-B", "PAT-B", 8));
        context.getSpecifyMachineMap().put("MAT-SPECIFY", Arrays.asList(
                specifyMachine("MAT-SPECIFY", "M1", JobTypeEnum.RESTRICTED.getCode())));
        // 预留切换起点会先落到 2026-04-19 14:00，需同时堵住 19 号中班与 20 号白班，才算真正不可排。
        context.getDailyMouldChangeCountMap().put("2026-04-19", new int[]{8, 7});
        context.getDailyMouldChangeCountMap().put("2026-04-20", new int[]{8, 7});
        putMouldRel(context, "MAT-C1", "MOULD-1");
        putMouldRel(context, "MAT-SPECIFY", "MOULD-2");

        when(orderNoGenerator.generateOrderNo(any())).thenReturn("ORD-1", "ORD-2");
        when(endingJudgmentStrategy.isEnding(any(), any())).thenReturn(false);

        strategy.scheduleContinuousEnding(context);

        LhScheduleResult continuousResult = context.getScheduleResultList().get(0);
        assertNotNull(ShiftFieldUtil.getShiftStartTime(continuousResult, 6),
                "定点物料在预留机台上不可排时，不应提前挤掉当前续作的最后业务日夜班");
        assertNotNull(ShiftFieldUtil.getShiftStartTime(continuousResult, 7),
                "定点物料在预留机台上不可排时，不应提前挤掉当前续作的最后业务日早班");
        assertNotNull(ShiftFieldUtil.getShiftStartTime(continuousResult, 8),
                "定点物料在预留机台上不可排时，不应提前挤掉当前续作的最后业务日中班");
    }

    @Test
    void scheduleTypeBlockChange_shouldReserveMachineForSpecifySkuRequiringNewSpecMouldChange() {
        LhScheduleContext context = newContext();
        enableSpecifyMachineRule(context);
        context.getMachineScheduleMap().put("M1", buildMachine("M1", "MAT-C1"));
        context.getContinuousSkuList().add(buildContinuousSku("MAT-C1", "M1", "EMB-1", "STRUCT-A", "SPEC-A", "PAT-A", 1));
        context.getNewSpecSkuList().add(buildNewSku("MAT-T1", "EMB-1", "STRUCT-B", "SPEC-A", "PAT-B", 4));
        context.getNewSpecSkuList().add(buildNewSku("MAT-SPECIFY", "EMB-9", "STRUCT-C", "SPEC-B", "PAT-C", 4));
        context.getSpecifyMachineMap().put("MAT-SPECIFY", Arrays.asList(
                specifyMachine("MAT-SPECIFY", "M1", JobTypeEnum.RESTRICTED.getCode())));
        putMaterialInfo(context, "MAT-C1", "胎胚描述-A", "SPEC-A", "PAT-A", "PAT-A");
        putMaterialInfo(context, "MAT-T1", "胎胚描述-A", "SPEC-A", "PAT-B", "PAT-B");
        putMaterialInfo(context, "MAT-SPECIFY", "胎胚描述-X", "SPEC-B", "PAT-C", "PAT-C");
        putMouldRel(context, "MAT-C1", "MOULD-1");
        putMouldRel(context, "MAT-T1", "MOULD-1");
        putMouldRel(context, "MAT-SPECIFY", "MOULD-2");

        when(orderNoGenerator.generateOrderNo(any())).thenReturn("ORD-1", "ORD-2");
        when(endingJudgmentStrategy.isEnding(any(), any())).thenAnswer(invocation -> {
            SkuScheduleDTO sku = invocation.getArgument(1);
            return sku != null && "MAT-C1".equals(sku.getMaterialCode());
        });

        strategy.scheduleContinuousEnding(context);
        typeBlockProductionStrategy.scheduleTypeBlockChange(context);

        assertEquals(1, context.getScheduleResultList().size(),
                "机台存在需走新增换模链路的定点物料时，S4.4 不应先让普通换活字块物料抢机");
        assertEquals(Arrays.asList("MAT-T1", "MAT-SPECIFY"),
                Arrays.asList(
                        context.getNewSpecSkuList().get(0).getMaterialCode(),
                        context.getNewSpecSkuList().get(1).getMaterialCode()));
    }

    @Test
    void scheduleTypeBlockChange_shouldDeferDifferentMouldCandidateToNewSpecStage() {
        LhScheduleContext context = newContext();
        context.getMachineScheduleMap().put("K1105", buildMachine("K1105", "3302001585"));
        context.getContinuousSkuList().add(buildContinuousSku(
                "3302001585", "K1105", "EMB-1", "STRUCT-A", "SPEC-A", "PAT-A", 1));
        context.getNewSpecSkuList().add(buildNewSku("3302002654", "EMB-1", "STRUCT-B", "SPEC-A", "PAT-B", 4));
        putMaterialInfo(context, "3302001585", "胎胚描述-A", "SPEC-A", "PAT-A", "PAT-A");
        putMaterialInfo(context, "3302002654", "胎胚描述-B", "SPEC-A", "PAT-B", "PAT-B");
        putMouldRel(context, "3302001585", "MOULD-OLD");
        putMouldRel(context, "3302002654", "MOULD-NEW");

        when(orderNoGenerator.generateOrderNo(any())).thenReturn("ORD-1", "ORD-2");
        when(endingJudgmentStrategy.isEnding(any(), any())).thenAnswer(invocation -> {
            SkuScheduleDTO sku = invocation.getArgument(1);
            return sku != null && "3302001585".equals(sku.getMaterialCode());
        });

        strategy.scheduleContinuousEnding(context);
        typeBlockProductionStrategy.scheduleTypeBlockChange(context);

        assertEquals(1, context.getScheduleResultList().size(),
                "不同模具候选应留给S4.5新增换模主链，S4.4不能直接按换活字块抢跑");
        assertEquals("3302001585", context.getScheduleResultList().get(0).getMaterialCode());
        assertEquals(1, context.getNewSpecSkuList().size());
        assertEquals("3302002654", context.getNewSpecSkuList().get(0).getMaterialCode());
        assertEquals("3302001585", context.getMachineScheduleMap().get("K1105").getCurrentMaterialCode());
    }

    @Test
    void scheduleTypeBlockChange_shouldUpdateMachineEstimatedEndTimeWithActualCompletion() {
        LhScheduleContext context = newContext();
        context.getMachineScheduleMap().put("M1", buildMachine("M1", "MAT-C1"));
        context.getContinuousSkuList().add(buildContinuousSku("MAT-C1", "M1", "EMB-1", "STRUCT-A", "SPEC-A", "PAT-A", 1));
        context.getNewSpecSkuList().add(buildNewSku("MAT-S1", "EMB-1", "STRUCT-A", "SPEC-A", "PAT-A", 1));
        putMouldRel(context, "MAT-C1", "MOULD-1");
        putMouldRel(context, "MAT-S1", "MOULD-1");

        when(orderNoGenerator.generateOrderNo(any())).thenReturn("ORD-1", "ORD-2");
        when(endingJudgmentStrategy.isEnding(any(), any())).thenAnswer(invocation -> {
            SkuScheduleDTO sku = invocation.getArgument(1);
            return sku != null && "MAT-C1".equals(sku.getMaterialCode());
        });

        strategy.scheduleContinuousEnding(context);
        typeBlockProductionStrategy.scheduleTypeBlockChange(context);

        LhScheduleResult followUpResult = context.getScheduleResultList().get(1);
        MachineScheduleDTO machine = context.getMachineScheduleMap().get("M1");
        Date expectedCompletionTime = resolveActualCompletionTime(followUpResult);
        Date lastShiftEndTime = resolveLastShiftEndTime(followUpResult);
        assertEquals("0", followUpResult.getIsEnd());
        assertEquals(expectedCompletionTime, followUpResult.getSpecEndTime());
        assertEquals(expectedCompletionTime, followUpResult.getTdaySpecEndTime());
        assertEquals(expectedCompletionTime, machine.getEstimatedEndTime());
        assertEquals(lastShiftEndTime, machine.getEstimatedEndTime(),
                "不再等待喷砂顺延后，机台预计完工时间应直接等于最后一段实际排产完工时刻");
    }

    @Test
    void scheduleTypeBlockChange_shouldUseTypeBlockHoursInsteadOfInspectionHours() {
        LhScheduleContext context = newContext();
        context.setScheduleConfig(createConfig(3, 9));
        context.getMachineScheduleMap().put("M1", buildMachine("M1", "MAT-C1"));
        context.getContinuousSkuList().add(buildContinuousSku("MAT-C1", "M1", "EMB-1", "STRUCT-A", "SPEC-A", "PAT-A", 1));
        context.getNewSpecSkuList().add(buildNewSku("MAT-T1", "EMB-1", "STRUCT-B", "SPEC-A", "PAT-B", 4));
        putMouldRel(context, "MAT-C1", "MOULD-1");
        putMouldRel(context, "MAT-T1", "MOULD-1");

        when(orderNoGenerator.generateOrderNo(any())).thenReturn("ORD-1", "ORD-2");
        when(endingJudgmentStrategy.isEnding(any(), any())).thenAnswer(invocation -> {
            SkuScheduleDTO sku = invocation.getArgument(1);
            return sku != null && "MAT-C1".equals(sku.getMaterialCode());
        });

        strategy.scheduleContinuousEnding(context);
        typeBlockProductionStrategy.scheduleTypeBlockChange(context);

        LhScheduleResult continuousResult = context.getScheduleResultList().get(0);
        LhScheduleResult typeBlockResult = context.getScheduleResultList().get(1);
        Date expectedStartTime = LhScheduleTimeUtil.addHours(continuousResult.getSpecEndTime(), 3);
        assertEquals("MAT-T1", typeBlockResult.getMaterialCode());
        assertEquals(expectedStartTime, resolveFirstStartTime(typeBlockResult));
        assertEquals(continuousResult.getSpecEndTime(),
                ReflectionTestUtils.getField(typeBlockResult, "mouldChangeStartTime"));
        assertEquals("1", typeBlockResult.getIsChangeMould());
        assertNotNull(typeBlockResult.getMouldCode());
        assertFalse(expectedStartTime.equals(LhScheduleTimeUtil.addHours(continuousResult.getSpecEndTime(), 9)));
    }

    @Test
    void scheduleTypeBlockChange_shouldWriteCleaningTypeBlockAnalysisWhenWindowHitsShift() {
        LhScheduleContext context = newContext();
        MachineScheduleDTO machine = buildMachine("M1", "MAT-C1");
        MachineCleaningWindowDTO cleaningWindow = new MachineCleaningWindowDTO();
        cleaningWindow.setCleanType("01");
        // 清洗窗口与换活字块窗口严格相交，应写入组合原因分析。
        cleaningWindow.setCleanStartTime(dateTime(2026, 4, 18, 9, 0, 0));
        cleaningWindow.setCleanEndTime(dateTime(2026, 4, 18, 11, 0, 0));
        cleaningWindow.setReadyTime(dateTime(2026, 4, 18, 11, 0, 0));
        List<MachineCleaningWindowDTO> cleaningWindowList = new ArrayList<>();
        cleaningWindowList.add(cleaningWindow);
        machine.setCleaningWindowList(cleaningWindowList);
        context.getMachineScheduleMap().put("M1", machine);
        context.getContinuousSkuList().add(buildContinuousSku("MAT-C1", "M1", "EMB-1", "STRUCT-A", "SPEC-A", "PAT-A", 1));
        context.getNewSpecSkuList().add(buildNewSku("MAT-T1", "EMB-1", "STRUCT-B", "SPEC-A", "PAT-B", 1));
        putMouldRel(context, "MAT-C1", "MOULD-1");
        putMouldRel(context, "MAT-T1", "MOULD-1");

        when(orderNoGenerator.generateOrderNo(any())).thenReturn("ORD-1", "ORD-2");
        when(endingJudgmentStrategy.isEnding(any(), any())).thenAnswer(invocation -> {
            SkuScheduleDTO sku = invocation.getArgument(1);
            return sku != null && "MAT-C1".equals(sku.getMaterialCode());
        });

        strategy.scheduleContinuousEnding(context);
        typeBlockProductionStrategy.scheduleTypeBlockChange(context);

        LhScheduleResult typeBlockResult = context.getScheduleResultList().get(1);
        int firstPlannedShift = resolveFirstPlannedShiftIndex(typeBlockResult);
        assertEquals("模具清洗+换活字块", ShiftFieldUtil.getShiftAnalysis(typeBlockResult, firstPlannedShift));
    }

    @Test
    void scheduleTypeBlockChange_shouldNotWriteCleaningTypeBlockAnalysisWithoutCleaningWindow() {
        LhScheduleContext context = newContext();
        context.getMachineScheduleMap().put("M1", buildMachine("M1", "MAT-C1"));
        context.getContinuousSkuList().add(buildContinuousSku("MAT-C1", "M1", "EMB-1", "STRUCT-A", "SPEC-A", "PAT-A", 1));
        context.getNewSpecSkuList().add(buildNewSku("MAT-T1", "EMB-1", "STRUCT-B", "SPEC-A", "PAT-B", 1));
        putMouldRel(context, "MAT-C1", "MOULD-1");
        putMouldRel(context, "MAT-T1", "MOULD-1");

        when(orderNoGenerator.generateOrderNo(any())).thenReturn("ORD-1", "ORD-2");
        when(endingJudgmentStrategy.isEnding(any(), any())).thenAnswer(invocation -> {
            SkuScheduleDTO sku = invocation.getArgument(1);
            return sku != null && "MAT-C1".equals(sku.getMaterialCode());
        });

        strategy.scheduleContinuousEnding(context);
        typeBlockProductionStrategy.scheduleTypeBlockChange(context);

        LhScheduleResult typeBlockResult = context.getScheduleResultList().get(1);
        int firstPlannedShift = resolveFirstPlannedShiftIndex(typeBlockResult);
        assertNull(ShiftFieldUtil.getShiftAnalysis(typeBlockResult, firstPlannedShift));
    }

    @Test
    void scheduleTypeBlockChange_shouldNotWriteCleaningTypeBlockAnalysisWhenOnlyTouchBoundary() {
        LhScheduleContext context = newContext();
        context.setScheduleConfig(createConfig(3, 9));
        MachineScheduleDTO machine = buildMachine("M1", "MAT-C1");
        context.getMachineScheduleMap().put("M1", machine);
        context.getContinuousSkuList().add(buildContinuousSku("MAT-C1", "M1", "EMB-1", "STRUCT-A", "SPEC-A", "PAT-A", 1));
        context.getNewSpecSkuList().add(buildNewSku("MAT-T1", "EMB-1", "STRUCT-B", "SPEC-A", "PAT-B", 1));
        putMouldRel(context, "MAT-C1", "MOULD-1");
        putMouldRel(context, "MAT-T1", "MOULD-1");

        when(orderNoGenerator.generateOrderNo(any())).thenReturn("ORD-1", "ORD-2");
        when(endingJudgmentStrategy.isEnding(any(), any())).thenAnswer(invocation -> {
            SkuScheduleDTO sku = invocation.getArgument(1);
            return sku != null && "MAT-C1".equals(sku.getMaterialCode());
        });

        strategy.scheduleContinuousEnding(context);
        LhScheduleResult continuousResult = context.getScheduleResultList().get(0);
        Date typeBlockStartBoundary = LhScheduleTimeUtil.addHours(
                continuousResult.getSpecEndTime(),
                LhScheduleTimeUtil.getTypeBlockChangeTotalHours(context));

        MachineCleaningWindowDTO cleaningWindow = new MachineCleaningWindowDTO();
        cleaningWindow.setCleanType("01");
        cleaningWindow.setCleanStartTime(typeBlockStartBoundary);
        cleaningWindow.setCleanEndTime(LhScheduleTimeUtil.addHours(typeBlockStartBoundary, 1));
        cleaningWindow.setReadyTime(LhScheduleTimeUtil.addHours(typeBlockStartBoundary, 1));
        List<MachineCleaningWindowDTO> cleaningWindowList = new ArrayList<>();
        cleaningWindowList.add(cleaningWindow);
        machine.setCleaningWindowList(cleaningWindowList);

        typeBlockProductionStrategy.scheduleTypeBlockChange(context);

        LhScheduleResult typeBlockResult = context.getScheduleResultList().get(1);
        int firstPlannedShift = resolveFirstPlannedShiftIndex(typeBlockResult);
        assertNull(ShiftFieldUtil.getShiftAnalysis(typeBlockResult, firstPlannedShift));
    }

    @Test
    void scheduleTypeBlockChange_shouldIgnoreDryIceOverlapAndWriteCleaningAnalysis() {
        LhScheduleContext baselineContext = newContext();
        Date scheduleDate = date(2026, 4, 22);
        baselineContext.setScheduleDate(scheduleDate);
        baselineContext.setScheduleTargetDate(scheduleDate);
        baselineContext.setScheduleWindowShifts(LhScheduleTimeUtil.buildDefaultScheduleShifts(baselineContext, scheduleDate));
        MachineScheduleDTO baselineMachine = buildMachine("K2003", "MAT-C1");
        baselineContext.getMachineScheduleMap().put("K2003", baselineMachine);
        baselineContext.getContinuousSkuList().add(buildContinuousSku("MAT-C1", "K2003", "EMB-1", "STRUCT-A", "SPEC-A", "PAT-A", 1));
        baselineContext.getNewSpecSkuList().add(buildNewSku("MAT-T1", "EMB-1", "STRUCT-B", "SPEC-A", "PAT-B", 8));
        putMouldRel(baselineContext, "MAT-C1", "MOULD-1");
        putMouldRel(baselineContext, "MAT-T1", "MOULD-1");
        when(orderNoGenerator.generateOrderNo(any())).thenReturn("BASE-1", "BASE-2", "ORD-1", "ORD-2");
        when(endingJudgmentStrategy.isEnding(any(), any())).thenAnswer(invocation -> {
            SkuScheduleDTO sku = invocation.getArgument(1);
            return sku != null && "MAT-C1".equals(sku.getMaterialCode());
        });
        strategy.scheduleContinuousEnding(baselineContext);
        typeBlockProductionStrategy.scheduleTypeBlockChange(baselineContext);
        LhScheduleResult baselineTypeBlockResult = baselineContext.getScheduleResultList().get(1);

        LhScheduleContext context = newContext();
        context.setScheduleDate(scheduleDate);
        context.setScheduleTargetDate(scheduleDate);
        context.setScheduleWindowShifts(LhScheduleTimeUtil.buildDefaultScheduleShifts(context, scheduleDate));
        MachineScheduleDTO machine = buildMachine("K2003", "MAT-C1");
        context.getMachineScheduleMap().put("K2003", machine);
        context.getContinuousSkuList().add(buildContinuousSku("MAT-C1", "K2003", "EMB-1", "STRUCT-A", "SPEC-A", "PAT-A", 1));
        context.getNewSpecSkuList().add(buildNewSku("MAT-T1", "EMB-1", "STRUCT-B", "SPEC-A", "PAT-B", 8));
        putMouldRel(context, "MAT-C1", "MOULD-1");
        putMouldRel(context, "MAT-T1", "MOULD-1");

        when(orderNoGenerator.generateOrderNo(any())).thenReturn("ORD-1", "ORD-2");
        when(endingJudgmentStrategy.isEnding(any(), any())).thenAnswer(invocation -> {
            SkuScheduleDTO sku = invocation.getArgument(1);
            return sku != null && "MAT-C1".equals(sku.getMaterialCode());
        });

        strategy.scheduleContinuousEnding(context);
        LhScheduleResult continuousResult = context.getScheduleResultList().get(0);
        MachineCleaningWindowDTO cleaningWindow = new MachineCleaningWindowDTO();
        cleaningWindow.setCleanType("01");
        cleaningWindow.setCleanStartTime(continuousResult.getSpecEndTime());
        cleaningWindow.setCleanEndTime(LhScheduleTimeUtil.addHours(continuousResult.getSpecEndTime(), 2));
        cleaningWindow.setReadyTime(LhScheduleTimeUtil.addHours(continuousResult.getSpecEndTime(), 2));
        List<MachineCleaningWindowDTO> cleaningWindowList = new ArrayList<>();
        cleaningWindowList.add(cleaningWindow);
        machine.setCleaningWindowList(cleaningWindowList);
        typeBlockProductionStrategy.scheduleTypeBlockChange(context);

        LhScheduleResult typeBlockResult = context.getScheduleResultList().get(1);
        assertEquals(LhScheduleTimeUtil.addHours(continuousResult.getSpecEndTime(),
                        LhScheduleTimeUtil.getTypeBlockChangeTotalHours(context)),
                resolveFirstStartTime(typeBlockResult),
                "干冰与换活字块重叠时，不应再按清洗结束时间顺延开产");
        assertEquals(continuousResult.getSpecEndTime(),
                ReflectionTestUtils.getField(typeBlockResult, "mouldChangeStartTime"));
        assertEquals(ShiftFieldUtil.getShiftPlanQty(baselineTypeBlockResult, resolveFirstPlannedShiftIndex(baselineTypeBlockResult)),
                ShiftFieldUtil.getShiftPlanQty(typeBlockResult, resolveFirstPlannedShiftIndex(typeBlockResult)),
                "干冰与换活字块重叠时，排产量应与无清洗基线保持一致");
        assertEquals("模具清洗+换活字块",
                ShiftFieldUtil.getShiftAnalysis(typeBlockResult, resolveFirstPlannedShiftIndex(typeBlockResult)));
    }

    @Test
    void scheduleTypeBlockChange_shouldIgnoreSandBlastDelayAndOnlyKeepTypeBlockDuration() {
        LhScheduleContext baselineContext = newContext();
        MachineScheduleDTO baselineMachine = buildMachine("M1", "MAT-C1");
        baselineContext.getMachineScheduleMap().put("M1", baselineMachine);
        baselineContext.getContinuousSkuList().add(buildContinuousSku("MAT-C1", "M1", "EMB-1", "STRUCT-A", "SPEC-A", "PAT-A", 1));
        baselineContext.getNewSpecSkuList().add(buildNewSku("MAT-T1", "EMB-1", "STRUCT-B", "SPEC-A", "PAT-B", 8));
        putMouldRel(baselineContext, "MAT-C1", "MOULD-1");
        putMouldRel(baselineContext, "MAT-T1", "MOULD-1");
        when(orderNoGenerator.generateOrderNo(any())).thenReturn("BASE-1", "BASE-2", "ORD-1", "ORD-2");
        when(endingJudgmentStrategy.isEnding(any(), any())).thenAnswer(invocation -> {
            SkuScheduleDTO sku = invocation.getArgument(1);
            return sku != null && "MAT-C1".equals(sku.getMaterialCode());
        });
        strategy.scheduleContinuousEnding(baselineContext);
        typeBlockProductionStrategy.scheduleTypeBlockChange(baselineContext);
        LhScheduleResult baselineTypeBlockResult = baselineContext.getScheduleResultList().get(1);

        LhScheduleContext context = newContext();
        MachineScheduleDTO machine = buildMachine("M1", "MAT-C1");
        context.getMachineScheduleMap().put("M1", machine);
        context.getContinuousSkuList().add(buildContinuousSku("MAT-C1", "M1", "EMB-1", "STRUCT-A", "SPEC-A", "PAT-A", 1));
        context.getNewSpecSkuList().add(buildNewSku("MAT-T1", "EMB-1", "STRUCT-B", "SPEC-A", "PAT-B", 8));
        putMouldRel(context, "MAT-C1", "MOULD-1");
        putMouldRel(context, "MAT-T1", "MOULD-1");

        when(orderNoGenerator.generateOrderNo(any())).thenReturn("ORD-1", "ORD-2");
        when(endingJudgmentStrategy.isEnding(any(), any())).thenAnswer(invocation -> {
            SkuScheduleDTO sku = invocation.getArgument(1);
            return sku != null && "MAT-C1".equals(sku.getMaterialCode());
        });

        strategy.scheduleContinuousEnding(context);
        LhScheduleResult continuousResult = context.getScheduleResultList().get(0);
        MachineCleaningWindowDTO cleaningWindow = new MachineCleaningWindowDTO();
        cleaningWindow.setCleanType("02");
        cleaningWindow.setCleanStartTime(continuousResult.getSpecEndTime());
        cleaningWindow.setCleanEndTime(LhScheduleTimeUtil.addHours(continuousResult.getSpecEndTime(), 12));
        cleaningWindow.setReadyTime(LhScheduleTimeUtil.addHours(continuousResult.getSpecEndTime(), 10));
        List<MachineCleaningWindowDTO> cleaningWindowList = new ArrayList<>();
        cleaningWindowList.add(cleaningWindow);
        machine.setCleaningWindowList(cleaningWindowList);
        typeBlockProductionStrategy.scheduleTypeBlockChange(context);

        LhScheduleResult typeBlockResult = context.getScheduleResultList().get(1);
        assertEquals(LhScheduleTimeUtil.addHours(continuousResult.getSpecEndTime(), 8),
                resolveFirstStartTime(typeBlockResult),
                "喷砂与换活字块重叠时，不应再等待喷砂结束后才追加换活字块");
        assertEquals(continuousResult.getSpecEndTime(),
                ReflectionTestUtils.getField(typeBlockResult, "mouldChangeStartTime"));
        assertEquals(baselineTypeBlockResult.getDailyPlanQty(), typeBlockResult.getDailyPlanQty(),
                "喷砂与换活字块重叠时，不再计入喷砂清洗时间后，总排产量应与无清洗基线一致");
        assertEquals(7, ShiftFieldUtil.getShiftPlanQty(typeBlockResult, resolveFirstPlannedShiftIndex(typeBlockResult)),
                "不再计入喷砂清洗时间后，首个残班应按换活字块8小时后的剩余有效时间折算");
        assertEquals("模具清洗+换活字块", ShiftFieldUtil.getShiftAnalysis(typeBlockResult, resolveFirstPlannedShiftIndex(typeBlockResult)),
                "喷砂重叠但不再顺延时，首个有效排产班次仍应保留模具清洗+换活字块原因分析");
    }

    @Test
    void scheduleTypeBlockChange_shouldIgnoreSandBlastAndStillRespectDowntimeAndNoSwitchWindow() {
        LhScheduleContext context = newContext();
        MachineScheduleDTO machine = buildMachine("M1", "MAT-C1");
        context.getMachineScheduleMap().put("M1", machine);
        context.getContinuousSkuList().add(buildContinuousSku("MAT-C1", "M1", "EMB-1", "STRUCT-A", "SPEC-A", "PAT-A", 1));
        context.getNewSpecSkuList().add(buildNewSku("MAT-T1", "EMB-1", "STRUCT-B", "SPEC-A", "PAT-B", 8));
        putMouldRel(context, "MAT-C1", "MOULD-1");
        putMouldRel(context, "MAT-T1", "MOULD-1");

        when(orderNoGenerator.generateOrderNo(any())).thenReturn("ORD-1", "ORD-2");
        when(endingJudgmentStrategy.isEnding(any(), any())).thenAnswer(invocation -> {
            SkuScheduleDTO sku = invocation.getArgument(1);
            return sku != null && "MAT-C1".equals(sku.getMaterialCode());
        });

        strategy.scheduleContinuousEnding(context);
        LhScheduleResult continuousResult = context.getScheduleResultList().get(0);

        MachineCleaningWindowDTO cleaningWindow = new MachineCleaningWindowDTO();
        cleaningWindow.setCleanType("02");
        cleaningWindow.setCleanStartTime(continuousResult.getSpecEndTime());
        cleaningWindow.setCleanEndTime(LhScheduleTimeUtil.addHours(continuousResult.getSpecEndTime(), 12));
        cleaningWindow.setReadyTime(LhScheduleTimeUtil.addHours(continuousResult.getSpecEndTime(), 10));
        machine.setCleaningWindowList(Arrays.asList(cleaningWindow));

        MdmDevicePlanShut planShut = new MdmDevicePlanShut();
        planShut.setMachineCode("M1");
        planShut.setBeginDate(continuousResult.getSpecEndTime());
        planShut.setEndDate(LhScheduleTimeUtil.addHours(continuousResult.getSpecEndTime(), 2));
        context.getDevicePlanShutList().add(planShut);

        typeBlockProductionStrategy.scheduleTypeBlockChange(context);

        assertEquals(2, context.getScheduleResultList().size(), "喷砂后又遇到停机顺延时，换活字块结果仍应正常生成");
        LhScheduleResult typeBlockResult = context.getScheduleResultList().get(1);
        assertEquals(dateTime(2026, 4, 18, 9, 0, 0),
                ReflectionTestUtils.getField(typeBlockResult, "mouldChangeStartTime"),
                "喷砂重叠不再顺延后，若只命中停机窗口，应顺延到停机结束时刻再开始换活字块");
        int firstPlannedShiftIndex = resolveFirstPlannedShiftIndex(typeBlockResult);
        assertTrue(firstPlannedShiftIndex > 0, "顺延后应仍存在首个有效排产班次");
        assertEquals("模具清洗+换活字块", ShiftFieldUtil.getShiftAnalysis(typeBlockResult, firstPlannedShiftIndex),
                "喷砂重叠不再顺延但仍被停机继续顺延时，首个排产班次仍应保留模具清洗+换活字块分析");
    }

    @Test
    void scheduleTypeBlockChange_shouldSelectFirstSkuWithinPriorityTwoCandidates() {
        LhScheduleContext context = newContext();
        context.getMachineScheduleMap().put("M1", buildMachine("M1", "MAT-C1"));
        context.getContinuousSkuList().add(buildContinuousSku("MAT-C1", "M1", "EMB-1", "STRUCT-A", "SPEC-A", "PAT-A", 1));
        context.getNewSpecSkuList().add(buildNewSku("MAT-T1", "EMB-1", "STRUCT-B", "SPEC-A", "PAT-B", 4));
        context.getNewSpecSkuList().add(buildNewSku("MAT-T2", "EMB-1", "STRUCT-C", "SPEC-A", "PAT-C", 4));
        putMaterialInfo(context, "MAT-C1", "胎胚描述-A", "SPEC-A", "PAT-A", "PAT-A");
        putMaterialInfo(context, "MAT-T1", "胎胚描述-X", "SPEC-A", "PAT-B", "PAT-B");
        putMaterialInfo(context, "MAT-T2", "胎胚描述-Y", "SPEC-A", "PAT-C", "PAT-C");
        putMouldRel(context, "MAT-C1", "MOULD-1");
        putMouldRel(context, "MAT-T1", "MOULD-1");
        putMouldRel(context, "MAT-T2", "MOULD-1");

        when(orderNoGenerator.generateOrderNo(any())).thenReturn("ORD-1", "ORD-2");
        when(endingJudgmentStrategy.isEnding(any(), any())).thenAnswer(invocation -> {
            SkuScheduleDTO sku = invocation.getArgument(1);
            return "MAT-C1".equals(sku.getMaterialCode()) || "MAT-T2".equals(sku.getMaterialCode());
        });

        strategy.scheduleContinuousEnding(context);
        typeBlockProductionStrategy.scheduleTypeBlockChange(context);

        LhScheduleResult typeBlockResult = context.getScheduleResultList().get(1);
        assertEquals("MAT-T1", typeBlockResult.getMaterialCode());
        assertEquals(1, context.getNewSpecSkuList().size());
        assertEquals("MAT-T2", context.getNewSpecSkuList().get(0).getMaterialCode());
    }

    @Test
    void scheduleTypeBlockChange_shouldFilterSpecialSkuWhenMachineDoesNotSupportCategory() {
        LhScheduleContext context = newContext();
        MachineScheduleDTO machine = buildMachine("M1", "MAT-C1");
        machine.setSupportChipTire("0");
        context.getMachineScheduleMap().put("M1", machine);
        context.getContinuousSkuList().add(buildContinuousSku("MAT-C1", "M1", "EMB-1", "STRUCT-A", "SPEC-A", "PAT-A", 1));
        context.getNewSpecSkuList().add(buildNewSku("MAT-T1", "EMB-9", "STRUCT-B", "SPEC-B", "PAT-A", 4));
        context.getSpecialMaterialCategoryByMaterialCode().put("MAT-T1", java.util.Collections.singleton("03"));
        putMaterialInfo(context, "MAT-C1", "胎胚描述-A", "SPEC-A", "PAT-A", "PAT-A");
        putMaterialInfo(context, "MAT-T1", "胎胚描述-A", "SPEC-B", "PAT-A", "PAT-A");
        putMouldRel(context, "MAT-C1", "MOULD-1");
        putMouldRel(context, "MAT-T1", "MOULD-1");

        when(orderNoGenerator.generateOrderNo(any())).thenReturn("ORD-1");
        when(endingJudgmentStrategy.isEnding(any(), any())).thenAnswer(invocation -> {
            SkuScheduleDTO sku = invocation.getArgument(1);
            return sku != null && "MAT-C1".equals(sku.getMaterialCode());
        });

        strategy.scheduleContinuousEnding(context);
        typeBlockProductionStrategy.scheduleTypeBlockChange(context);

        assertEquals(1, context.getScheduleResultList().size());
        assertEquals("MAT-C1", context.getScheduleResultList().get(0).getMaterialCode());
        assertEquals(1, context.getNewSpecSkuList().size());
        assertEquals("MAT-T1", context.getNewSpecSkuList().get(0).getMaterialCode());
    }

    @Test
    void scheduleTypeBlockChange_shouldStopWhenPriorityOneAndTwoBothMiss() {
        LhScheduleContext context = newContext();
        context.getMachineScheduleMap().put("M1", buildMachine("M1", "MAT-C1"));
        context.getContinuousSkuList().add(buildContinuousSku("MAT-C1", "M1", "EMB-1", "STRUCT-A", "SPEC-A", "PAT-A", 1));
        context.getNewSpecSkuList().add(buildNewSku("MAT-T1", "EMB-2", "STRUCT-B", "SPEC-B", "PAT-B", 4));
        putMaterialInfo(context, "MAT-C1", "胎胚描述-A", "SPEC-A", "PAT-A", "PAT-A");
        putMaterialInfo(context, "MAT-T1", "胎胚描述-B", "SPEC-B", "PAT-B", "PAT-B");
        putMouldRel(context, "MAT-C1", "MOULD-1");
        putMouldRel(context, "MAT-T1", "MOULD-1");

        when(orderNoGenerator.generateOrderNo(any())).thenReturn("ORD-1");
        when(endingJudgmentStrategy.isEnding(any(), any())).thenAnswer(invocation -> {
            SkuScheduleDTO sku = invocation.getArgument(1);
            return sku != null && "MAT-C1".equals(sku.getMaterialCode());
        });

        strategy.scheduleContinuousEnding(context);
        typeBlockProductionStrategy.scheduleTypeBlockChange(context);

        assertEquals(1, context.getScheduleResultList().size());
        assertEquals("MAT-C1", context.getScheduleResultList().get(0).getMaterialCode());
        assertEquals(1, context.getNewSpecSkuList().size());
        assertEquals("MAT-T1", context.getNewSpecSkuList().get(0).getMaterialCode());
    }

    @Test
    void scheduleTypeBlockChange_shouldRequireStrictMainPatternForPriorityOne() {
        LhScheduleContext context = newContext();
        context.getMachineScheduleMap().put("M1", buildMachine("M1", "MAT-C1"));
        context.getContinuousSkuList().add(buildContinuousSku("MAT-C1", "M1", "EMB-1", "STRUCT-A", "SPEC-A", "PAT-A", 1));
        SkuScheduleDTO sku = buildNewSku("MAT-T1", "EMB-9", "STRUCT-B", "SPEC-B", "PAT-A", 4);
        sku.setMainPattern(null);
        context.getNewSpecSkuList().add(sku);
        putMaterialInfo(context, "MAT-C1", "胎胚描述-A", "SPEC-A", "PAT-A", "PAT-A");
        putMaterialInfo(context, "MAT-T1", "胎胚描述-A", "SPEC-B", null, "PAT-A");
        putMouldRel(context, "MAT-C1", "MOULD-1");
        putMouldRel(context, "MAT-T1", "MOULD-1");

        when(orderNoGenerator.generateOrderNo(any())).thenReturn("ORD-1");
        when(endingJudgmentStrategy.isEnding(any(), any())).thenAnswer(invocation -> {
            SkuScheduleDTO targetSku = invocation.getArgument(1);
            return "MAT-C1".equals(targetSku.getMaterialCode());
        });

        strategy.scheduleContinuousEnding(context);
        typeBlockProductionStrategy.scheduleTypeBlockChange(context);

        assertEquals(1, context.getScheduleResultList().size());
        assertEquals("MAT-C1", context.getScheduleResultList().get(0).getMaterialCode());
        assertEquals(1, context.getNewSpecSkuList().size());
        assertEquals("MAT-T1", context.getNewSpecSkuList().get(0).getMaterialCode());
    }

    @Test
    void scheduleTypeBlockChange_shouldSkipMachinesThatDoNotEndInWindow() {
        LhScheduleContext context = newContext();
        context.getMachineScheduleMap().put("M1", buildMachine("M1", "MAT-C1"));
        context.getContinuousSkuList().add(buildContinuousSku("MAT-C1", "M1", "EMB-1", "STRUCT-A", "SPEC-A", "PAT-A", 6));
        context.getNewSpecSkuList().add(buildNewSku("MAT-T1", "EMB-1", "STRUCT-B", "SPEC-A", "PAT-B", 4));
        putMouldRel(context, "MAT-C1", "MOULD-1");
        putMouldRel(context, "MAT-T1", "MOULD-1");

        when(orderNoGenerator.generateOrderNo(any())).thenReturn("ORD-1");
        when(endingJudgmentStrategy.isEnding(any(), any())).thenReturn(false);

        strategy.scheduleContinuousEnding(context);
        typeBlockProductionStrategy.scheduleTypeBlockChange(context);

        assertEquals(1, context.getScheduleResultList().size());
        assertEquals("MAT-C1", context.getScheduleResultList().get(0).getMaterialCode());
        assertEquals(1, context.getNewSpecSkuList().size());
        assertEquals("MAT-T1", context.getNewSpecSkuList().get(0).getMaterialCode());
    }

    @Test
    void scheduleTypeBlockChange_shouldFallbackWhenPreviousDayHasNoSameMachineSku() {
        LhScheduleContext context = newContext();
        context.getMachineScheduleMap().put("M1", buildMachine("M1", "MAT-C1"));
        putMachineOnlineInfo(context, "M1", "MAT-C1");
        context.getNewSpecSkuList().add(buildNewSku("MAT-T1", "EMB-1", "STRUCT-B", "SPEC-A", "PAT-A", 2));
        putMaterialInfo(context, "MAT-C1", "胎胚描述-A", "SPEC-A", "PAT-A", "PAT-A");
        putMaterialInfo(context, "MAT-T1", "胎胚描述-A", "SPEC-B", "PAT-A", "PAT-A");
        putMouldRel(context, "MAT-C1", "MOULD-1");
        putMouldRel(context, "MAT-T1", "MOULD-1");

        typeBlockProductionStrategy.scheduleTypeBlockChange(context);

        assertEquals(1, context.getScheduleResultList().size());
        assertEquals("MAT-T1", context.getScheduleResultList().get(0).getMaterialCode());
    }

    @Test
    void scheduleTypeBlockChange_shouldFallbackWhenPreviousDayLatestSameMachineSkuEnded() {
        LhScheduleContext context = newContext();
        context.getMachineScheduleMap().put("M1", buildMachine("M1", "MAT-C1"));
        putMachineOnlineInfo(context, "M1", "MAT-C1");
        context.getNewSpecSkuList().add(buildNewSku("MAT-T1", "EMB-1", "STRUCT-B", "SPEC-A", "PAT-A", 2));
        putMaterialInfo(context, "MAT-C1", "胎胚描述-A", "SPEC-A", "PAT-A", "PAT-A");
        putMaterialInfo(context, "MAT-T1", "胎胚描述-A", "SPEC-B", "PAT-A", "PAT-A");
        putMouldRel(context, "MAT-C1", "MOULD-1");
        putMouldRel(context, "MAT-T1", "MOULD-1");
        context.getPreviousScheduleResultList().add(
                buildPreviousScheduleResult("M1", "MAT-C1", "1",
                        dateTime(2026, 4, 17, 8, 0, 0), dateTime(2026, 4, 17, 8, 5, 0)));

        typeBlockProductionStrategy.scheduleTypeBlockChange(context);

        assertEquals(1, context.getScheduleResultList().size());
        assertEquals("MAT-T1", context.getScheduleResultList().get(0).getMaterialCode());
    }

    @Test
    void scheduleTypeBlockChange_shouldSkipFallbackWhenPreviousDayLatestSameMachineSkuNotEnd() {
        LhScheduleContext context = newTraceContext();
        context.getMachineScheduleMap().put("M1", buildMachine("M1", "MAT-C1"));
        putMachineOnlineInfo(context, "M1", "MAT-C1");
        context.getNewSpecSkuList().add(buildNewSku("MAT-T1", "EMB-1", "STRUCT-B", "SPEC-A", "PAT-A", 2));
        putMaterialInfo(context, "MAT-C1", "胎胚描述-A", "SPEC-A", "PAT-A", "PAT-A");
        putMaterialInfo(context, "MAT-T1", "胎胚描述-A", "SPEC-B", "PAT-A", "PAT-A");
        putMouldRel(context, "MAT-C1", "MOULD-1");
        putMouldRel(context, "MAT-T1", "MOULD-1");
        context.getPreviousScheduleResultList().add(
                buildPreviousScheduleResult("M1", "MAT-C1", "0",
                        dateTime(2026, 4, 17, 9, 0, 0), dateTime(2026, 4, 17, 9, 5, 0)));

        typeBlockProductionStrategy.scheduleTypeBlockChange(context);

        assertEquals(0, context.getScheduleResultList().size());
        assertEquals(1, context.getNewSpecSkuList().size());
        StringBuilder allLogText = new StringBuilder();
        for (LhScheduleProcessLog scheduleLog : context.getScheduleLogList()) {
            allLogText.append(scheduleLog.getLogDetail()).append('\n');
        }
        assertTrue(allLogText.toString().contains("T-1 最新记录未收尾，跳过兜底反查"));
    }

    @Test
    void scheduleTypeBlockChange_shouldUseLatestPreviousDayRecordWhenMixedEndFlags() {
        LhScheduleContext context = newContext();
        context.getMachineScheduleMap().put("M1", buildMachine("M1", "MAT-C1"));
        putMachineOnlineInfo(context, "M1", "MAT-C1");
        context.getNewSpecSkuList().add(buildNewSku("MAT-T1", "EMB-1", "STRUCT-B", "SPEC-A", "PAT-A", 2));
        putMaterialInfo(context, "MAT-C1", "胎胚描述-A", "SPEC-A", "PAT-A", "PAT-A");
        putMaterialInfo(context, "MAT-T1", "胎胚描述-A", "SPEC-B", "PAT-A", "PAT-A");
        putMouldRel(context, "MAT-C1", "MOULD-1");
        putMouldRel(context, "MAT-T1", "MOULD-1");
        context.getPreviousScheduleResultList().add(
                buildPreviousScheduleResult("M1", "MAT-C1", "1",
                        dateTime(2026, 4, 17, 7, 0, 0), dateTime(2026, 4, 17, 10, 0, 0)));
        context.getPreviousScheduleResultList().add(
                buildPreviousScheduleResult("M1", "MAT-C1", "0",
                        dateTime(2026, 4, 17, 9, 0, 0), dateTime(2026, 4, 17, 9, 30, 0)));

        typeBlockProductionStrategy.scheduleTypeBlockChange(context);

        assertEquals(0, context.getScheduleResultList().size());
        assertEquals(1, context.getNewSpecSkuList().size());
    }

    @Test
    void scheduleTypeBlockChange_shouldPrioritizeEndingMachineBeforeFallbackMachine() {
        LhScheduleContext context = newContext();
        context.getMachineScheduleMap().put("M1", buildMachine("M1", "MAT-C1"));
        context.getMachineScheduleMap().put("M2", buildMachine("M2", "MAT-C2"));
        context.getContinuousSkuList().add(buildContinuousSku("MAT-C1", "M1", "EMB-1", "STRUCT-A", "SPEC-A", "PAT-A", 2));
        context.getNewSpecSkuList().add(buildNewSku("MAT-T1", "EMB-9", "STRUCT-B", "SPEC-B", "PAT-A", 2));
        putMachineOnlineInfo(context, "M2", "MAT-C2");
        putMaterialInfo(context, "MAT-C1", "胎胚描述-A", "SPEC-A", "PAT-A", "PAT-A");
        putMaterialInfo(context, "MAT-C2", "胎胚描述-A", "SPEC-C", "PAT-A", "PAT-A");
        putMaterialInfo(context, "MAT-T1", "胎胚描述-A", "SPEC-B", "PAT-A", "PAT-A");
        putMouldRel(context, "MAT-C1", "MOULD-1");
        putMouldRel(context, "MAT-C2", "MOULD-1");
        putMouldRel(context, "MAT-T1", "MOULD-1");
        context.getPreviousScheduleResultList().add(
                buildPreviousScheduleResult("M2", "MAT-C2", "1",
                        dateTime(2026, 4, 17, 8, 0, 0), dateTime(2026, 4, 17, 8, 5, 0)));

        when(orderNoGenerator.generateOrderNo(any())).thenReturn("ORD-1", "ORD-2");
        when(endingJudgmentStrategy.isEnding(any(), any())).thenAnswer(invocation -> {
            SkuScheduleDTO sku = invocation.getArgument(1);
            return sku != null && "MAT-C1".equals(sku.getMaterialCode());
        });

        strategy.scheduleContinuousEnding(context);
        typeBlockProductionStrategy.scheduleTypeBlockChange(context);

        assertEquals(2, context.getScheduleResultList().size());
        assertEquals(Arrays.asList("MAT-C1", "MAT-T1"),
                Arrays.asList(
                        context.getScheduleResultList().get(0).getMaterialCode(),
                        context.getScheduleResultList().get(1).getMaterialCode()));
        assertEquals("M1", context.getScheduleResultList().get(1).getLhMachineCode());
        assertEquals("MAT-C2", context.getMachineScheduleMap().get("M2").getCurrentMaterialCode());
        assertEquals(0, context.getNewSpecSkuList().size());
    }

    @Test
    void scheduleTypeBlockChange_shouldFollowUpdatedMachineEndTimeOrder() {
        LhScheduleContext context = newContext();
        context.getMachineScheduleMap().put("M1", buildMachine("M1", "MAT-C1"));
        context.getMachineScheduleMap().put("M2", buildMachine("M2", "MAT-C2"));
        context.getContinuousSkuList().add(buildContinuousSku("MAT-C1", "M1", "EMB-1", "STRUCT-A", "SPEC-A", "PAT-A", 1));
        context.getContinuousSkuList().add(buildContinuousSku("MAT-C2", "M2", "EMB-2", "STRUCT-D", "SPEC-D", "PAT-D", 3));
        context.getNewSpecSkuList().add(buildNewSku("MAT-T1", "EMB-1", "STRUCT-B", "SPEC-A", "PAT-B", 4));
        context.getNewSpecSkuList().add(buildNewSku("MAT-T2", "EMB-2", "STRUCT-E", "SPEC-D", "PAT-E", 4));
        putMouldRel(context, "MAT-C1", "MOULD-1");
        putMouldRel(context, "MAT-C2", "MOULD-2");
        putMouldRel(context, "MAT-T1", "MOULD-1");
        putMouldRel(context, "MAT-T2", "MOULD-2");

        when(orderNoGenerator.generateOrderNo(any())).thenReturn("ORD-1", "ORD-2", "ORD-3", "ORD-4");
        when(endingJudgmentStrategy.isEnding(any(), any())).thenAnswer(invocation -> {
            SkuScheduleDTO sku = invocation.getArgument(1);
            return sku.getMaterialCode().startsWith("MAT-C");
        });

        strategy.scheduleContinuousEnding(context);
        typeBlockProductionStrategy.scheduleTypeBlockChange(context);

        List<LhScheduleResult> results = context.getScheduleResultList();
        assertEquals(Arrays.asList("MAT-C1", "MAT-C2", "MAT-T1", "MAT-T2"),
                Arrays.asList(
                        results.get(0).getMaterialCode(),
                        results.get(1).getMaterialCode(),
                        results.get(2).getMaterialCode(),
                        results.get(3).getMaterialCode()));
        assertEquals(LhScheduleTimeUtil.addHours(results.get(0).getSpecEndTime(),
                        LhScheduleTimeUtil.getTypeBlockChangeTotalHours(context)),
                resolveFirstStartTime(results.get(2)));
        assertEquals(LhScheduleTimeUtil.addHours(results.get(1).getSpecEndTime(),
                        LhScheduleTimeUtil.getTypeBlockChangeTotalHours(context)),
                resolveFirstStartTime(results.get(3)));
    }

    @Test
    void scheduleTypeBlockChange_shouldContinueAtNightWhenEndingBefore20() {
        LhScheduleContext context = newContext();
        context.getMachineScheduleMap().put("M1", buildMachine("M1", "MAT-C1"));
        context.getContinuousSkuList().add(buildContinuousSku("MAT-C1", "M1", "EMB-1", "STRUCT-A", "SPEC-A", "PAT-A", 13));
        context.getNewSpecSkuList().add(buildNewSku("MAT-T1", "EMB-1", "STRUCT-B", "SPEC-A", "PAT-B", 1));
        putMouldRel(context, "MAT-C1", "MOULD-1");
        putMouldRel(context, "MAT-T1", "MOULD-1");

        when(orderNoGenerator.generateOrderNo(any())).thenReturn("ORD-1", "ORD-2");
        when(endingJudgmentStrategy.isEnding(any(), any())).thenAnswer(invocation -> {
            SkuScheduleDTO sku = invocation.getArgument(1);
            return sku != null && "MAT-C1".equals(sku.getMaterialCode());
        });

        strategy.scheduleContinuousEnding(context);
        LhScheduleResult continuousResult = context.getScheduleResultList().get(0);
        assertEquals(dateTime(2026, 4, 18, 19, 0, 0), continuousResult.getSpecEndTime(),
                "收尾时间应按真实完工时刻回写，不能放大到班次结束");

        typeBlockProductionStrategy.scheduleTypeBlockChange(context);
        LhScheduleResult typeBlockResult = context.getScheduleResultList().get(1);
        assertEquals(dateTime(2026, 4, 19, 3, 0, 0), resolveFirstStartTime(typeBlockResult),
                "20:00前收尾应允许连续切换，夜班继续生产");
    }

    @Test
    void scheduleTypeBlockChange_shouldAllowSwitchStartWhenEndingAt20() {
        LhScheduleContext context = newContext();
        context.getMachineScheduleMap().put("M1", buildMachine("M1", "MAT-C1"));
        context.getContinuousSkuList().add(buildContinuousSku("MAT-C1", "M1", "EMB-1", "STRUCT-A", "SPEC-A", "PAT-A", 14));
        context.getNewSpecSkuList().add(buildNewSku("MAT-T1", "EMB-1", "STRUCT-B", "SPEC-A", "PAT-B", 1));
        putMouldRel(context, "MAT-C1", "MOULD-1");
        putMouldRel(context, "MAT-T1", "MOULD-1");

        when(orderNoGenerator.generateOrderNo(any())).thenReturn("ORD-1", "ORD-2");
        when(endingJudgmentStrategy.isEnding(any(), any())).thenAnswer(invocation -> {
            SkuScheduleDTO sku = invocation.getArgument(1);
            return sku != null && "MAT-C1".equals(sku.getMaterialCode());
        });

        strategy.scheduleContinuousEnding(context);
        LhScheduleResult continuousResult = context.getScheduleResultList().get(0);
        assertEquals(dateTime(2026, 4, 18, 20, 0, 0), continuousResult.getSpecEndTime(),
                "收尾时间应按真实完工时刻回写，不能放大到班次结束");

        typeBlockProductionStrategy.scheduleTypeBlockChange(context);
        LhScheduleResult typeBlockResult = context.getScheduleResultList().get(1);
        assertEquals(dateTime(2026, 4, 19, 4, 0, 0), resolveFirstStartTime(typeBlockResult),
                "20:00 整点允许发起换活字块，生产可在换活字块完成后开始");
    }

    @Test
    void scheduleTypeBlockChange_shouldUseMaintenanceOverlapSwitchHoursAndInspection() {
        LhScheduleContext context = newContext();
        context.setScheduleDate(date(2026, 4, 22));
        context.setScheduleTargetDate(date(2026, 4, 22));
        context.setScheduleWindowShifts(LhScheduleTimeUtil.buildDefaultScheduleShifts(context, context.getScheduleDate()));
        Map<String, String> paramMap = new HashMap<>(8);
        paramMap.put(LhScheduleParamConstant.MAINTENANCE_START_HOUR, "8");
        paramMap.put(LhScheduleParamConstant.MAINTENANCE_DURATION_HOURS, "7");
        paramMap.put(LhScheduleParamConstant.CAPSULE_PREHEAT_HOURS, "2.5");
        context.setScheduleConfig(new LhScheduleConfig(paramMap));

        MachineScheduleDTO machine = buildMachine("K2030", "MAT-C1");
        machine.setEnding(true);
        machine.setEstimatedEndTime(dateTime(2026, 4, 22, 6, 0, 0));
        context.getMachineScheduleMap().put(machine.getMachineCode(), machine);
        context.getMaintenancePlanMap().put(machine.getMachineCode(),
                buildPrecisionPlan(machine.getMachineCode(), date(2026, 5, 10)));
        context.getNewSpecSkuList().add(buildNewSku("MAT-T1", "EMB-1", "STRUCT-B", "SPEC-A", "PAT-B", 8));
        putMaterialInfo(context, "MAT-C1", "胎胚描述-A", "SPEC-A", "PAT-A", "PAT-A");
        putMaterialInfo(context, "MAT-T1", "胎胚描述-A", "SPEC-A", "PAT-B", "PAT-B");
        putMouldRel(context, "MAT-C1", "MOULD-1");
        putMouldRel(context, "MAT-T1", "MOULD-1");

        when(orderNoGenerator.generateOrderNo(any())).thenReturn("ORD-1");
        when(endingJudgmentStrategy.isEnding(any(), any())).thenReturn(false);

        typeBlockProductionStrategy.scheduleTypeBlockChange(context);

        assertEquals(1, context.getScheduleResultList().size(), "维保与换活字块重叠时应正常生成衔接结果");
        LhScheduleResult typeBlockResult = context.getScheduleResultList().get(0);
        assertEquals(dateTime(2026, 4, 22, 20, 0, 0), resolveFirstStartTime(typeBlockResult),
                "维保与换活字块重叠时，开产时间应为15:00+4小时换活字块+1小时首检");
        assertEquals(dateTime(2026, 4, 22, 15, 0, 0),
                ReflectionTestUtils.getField(typeBlockResult, "mouldChangeStartTime"),
                "维保重叠时，换活字块开始时间应从维保结束时刻起算");
    }

    @Test
    void scheduleTypeBlockChange_shouldDelayTypeBlockToNextMorningWhenDowntimeOverlapsAndNightForbidden() {
        LhScheduleContext context = newContext();
        context.setScheduleDate(date(2026, 4, 21));
        context.setScheduleTargetDate(date(2026, 4, 23));
        context.setScheduleWindowShifts(LhScheduleTimeUtil.buildDefaultScheduleShifts(context, context.getScheduleDate()));
        MachineScheduleDTO machine = buildMachine("K2024", "3302001556");
        machine.setEnding(true);
        machine.setEstimatedEndTime(dateTime(2026, 4, 21, 6, 0, 0));
        context.getMachineScheduleMap().put("K2024", machine);
        context.getNewSpecSkuList().add(buildNewSku("3302002174", "EMB-1", "STRUCT-A", "SPEC-A", "PAT-A", 50));
        putMaterialInfo(context, "3302001556", "胎胚描述-A", "SPEC-A", "PAT-A", "PAT-A");
        putMaterialInfo(context, "3302002174", "胎胚描述-A", "SPEC-A", "PAT-A", "PAT-A");
        context.getDevicePlanShutList().add(buildDevicePlanShut(
                "K2024", dateTime(2026, 4, 21, 6, 0, 0), dateTime(2026, 4, 21, 23, 59, 59)));
        putMouldRel(context, "3302001556", "MOULD-1");
        putMouldRel(context, "3302002174", "MOULD-1");

        when(orderNoGenerator.generateOrderNo(any())).thenReturn("ORD-1");
        when(endingJudgmentStrategy.isEnding(any(), any())).thenReturn(false);

        typeBlockProductionStrategy.scheduleTypeBlockChange(context);

        assertEquals(1, context.getScheduleResultList().size());
        LhScheduleResult typeBlockResult = context.getScheduleResultList().get(0);
        assertEquals("3302002174", typeBlockResult.getMaterialCode());
        assertEquals(dateTime(2026, 4, 22, 14, 0, 0), resolveFirstStartTime(typeBlockResult),
                "停机重叠且夜班禁换时，应顺延到次日早班发起换活字块，次日中班开始生产");
        Integer class3PlanQty = ShiftFieldUtil.getShiftPlanQty(typeBlockResult, 3);
        assertTrue(class3PlanQty == null || class3PlanQty <= 0, "停机日夜班不应出现换活字块后的生产计划量");
    }

    @Test
    void scheduleTypeBlockChange_shouldChainPriorityOneCandidatesBeforeSpecFallbackOnSameMachine() {
        LhScheduleContext context = newContext();
        context.setScheduleDate(date(2026, 4, 24));
        context.setScheduleTargetDate(date(2026, 4, 24));
        context.setScheduleWindowShifts(LhScheduleTimeUtil.buildDefaultScheduleShifts(context, context.getScheduleDate()));
        MachineScheduleDTO machine = buildMachine("K2024", "3302001556");
        machine.setEnding(true);
        machine.setEstimatedEndTime(dateTime(2026, 4, 24, 6, 0, 0));
        context.getMachineScheduleMap().put("K2024", machine);

        context.getNewSpecSkuList().add(buildNewSku("3302002174", "EMB-1", "STRUCT-A", "SPEC-A", "PAT-A", 1));
        context.getNewSpecSkuList().add(buildNewSku("3302001647", "EMB-1", "STRUCT-B", "SPEC-B", "PAT-A", 1));
        context.getNewSpecSkuList().add(buildNewSku("3302001002", "EMB-9", "STRUCT-C", "SPEC-B", "PAT-Z", 1));

        putMaterialInfo(context, "3302001556", "胎胚描述-A", "SPEC-A", "PAT-A", "PAT-A");
        putMaterialInfo(context, "3302002174", "胎胚描述-A", "SPEC-A", "PAT-A", "PAT-A");
        putMaterialInfo(context, "3302001647", "胎胚描述-A", "SPEC-B", "PAT-A", "PAT-A");
        putMaterialInfo(context, "3302001002", "胎胚描述-B", "SPEC-B", "PAT-Z", "PAT-Z");

        putMouldRel(context, "3302001556", "MOULD-1");
        putMouldRel(context, "3302002174", "MOULD-1");
        putMouldRel(context, "3302001647", "MOULD-1");
        putMouldRel(context, "3302001002", "MOULD-2");

        when(orderNoGenerator.generateOrderNo(any())).thenReturn("ORD-1", "ORD-2", "ORD-3");
        when(endingJudgmentStrategy.isEnding(any(), any())).thenAnswer(invocation -> {
            SkuScheduleDTO sku = invocation.getArgument(1);
            return "3302002174".equals(sku.getMaterialCode());
        });

        typeBlockProductionStrategy.scheduleTypeBlockChange(context);

        assertTrue(context.getScheduleResultList().size() >= 2);
        List<String> scheduledMaterials = new ArrayList<>(context.getScheduleResultList().size());
        for (LhScheduleResult result : context.getScheduleResultList()) {
            scheduledMaterials.add(result.getMaterialCode());
        }
        assertEquals("3302002174", scheduledMaterials.get(0));
        assertEquals("3302001647", scheduledMaterials.get(1));
    }

    @Test
    void scheduleTypeBlockChange_shouldResortAcrossMachinesAfterEachSuccess() {
        LhScheduleContext context = newContext();
        context.getMachineScheduleMap().put("M1", buildMachine("M1", "MAT-C1"));
        context.getMachineScheduleMap().put("M2", buildMachine("M2", "MAT-C2"));
        context.getContinuousSkuList().add(buildContinuousSku("MAT-C1", "M1", "EMB-1", "STRUCT-A", "SPEC-A", "PAT-A", 1));
        context.getContinuousSkuList().add(buildContinuousSku("MAT-C2", "M2", "EMB-2", "STRUCT-B", "SPEC-B", "PAT-B", 3));
        context.getNewSpecSkuList().add(buildNewSku("MAT-T1", "EMB-1", "STRUCT-A1", "SPEC-A1", "PAT-A", 4));
        context.getNewSpecSkuList().add(buildNewSku("MAT-T2", "EMB-2", "STRUCT-B1", "SPEC-B1", "PAT-B", 1));
        context.getNewSpecSkuList().add(buildNewSku("MAT-T3", "EMB-1", "STRUCT-A2", "SPEC-A2", "PAT-A", 1));
        putMaterialInfo(context, "MAT-C1", "胎胚描述-A", "SPEC-A", "PAT-A", "PAT-A");
        putMaterialInfo(context, "MAT-C2", "胎胚描述-B", "SPEC-B", "PAT-B", "PAT-B");
        putMaterialInfo(context, "MAT-T1", "胎胚描述-A", "SPEC-A1", "PAT-A", "PAT-A");
        putMaterialInfo(context, "MAT-T2", "胎胚描述-B", "SPEC-B1", "PAT-B", "PAT-B");
        putMaterialInfo(context, "MAT-T3", "胎胚描述-A", "SPEC-A2", "PAT-A", "PAT-A");
        putMouldRel(context, "MAT-C1", "MOULD-1");
        putMouldRel(context, "MAT-C2", "MOULD-2");
        putMouldRel(context, "MAT-T1", "MOULD-1");
        putMouldRel(context, "MAT-T2", "MOULD-2");
        putMouldRel(context, "MAT-T3", "MOULD-1");

        when(orderNoGenerator.generateOrderNo(any())).thenReturn("ORD-1", "ORD-2", "ORD-3", "ORD-4", "ORD-5");
        when(endingJudgmentStrategy.isEnding(any(), any())).thenAnswer(invocation -> {
            SkuScheduleDTO sku = invocation.getArgument(1);
            return "MAT-C1".equals(sku.getMaterialCode())
                    || "MAT-C2".equals(sku.getMaterialCode())
                    || "MAT-T1".equals(sku.getMaterialCode());
        });

        strategy.scheduleContinuousEnding(context);
        typeBlockProductionStrategy.scheduleTypeBlockChange(context);

        List<String> scheduledMaterials = new ArrayList<>(context.getScheduleResultList().size());
        for (LhScheduleResult result : context.getScheduleResultList()) {
            scheduledMaterials.add(result.getMaterialCode());
        }
        assertEquals(Arrays.asList("MAT-C1", "MAT-C2", "MAT-T1", "MAT-T2", "MAT-T3"), scheduledMaterials);
    }

    @Test
    void scheduleTypeBlockChange_shouldStopChainWhenMachineBecomesNonEnding() {
        LhScheduleContext context = newContext();
        context.setScheduleDate(date(2026, 4, 24));
        context.setScheduleTargetDate(date(2026, 4, 24));
        context.setScheduleWindowShifts(LhScheduleTimeUtil.buildDefaultScheduleShifts(context, context.getScheduleDate()));
        MachineScheduleDTO machine = buildMachine("K2024", "3302001556");
        machine.setEnding(true);
        machine.setEstimatedEndTime(dateTime(2026, 4, 24, 6, 0, 0));
        context.getMachineScheduleMap().put("K2024", machine);

        context.getNewSpecSkuList().add(buildNewSku("3302002174", "EMB-1", "STRUCT-A", "SPEC-A", "PAT-A", 1));
        context.getNewSpecSkuList().add(buildNewSku("3302001647", "EMB-1", "STRUCT-B", "SPEC-B", "PAT-A", 1));
        context.getNewSpecSkuList().add(buildNewSku("3302001648", "EMB-1", "STRUCT-C", "SPEC-C", "PAT-A", 1));

        putMaterialInfo(context, "3302001556", "胎胚描述-A", "SPEC-A", "PAT-A", "PAT-A");
        putMaterialInfo(context, "3302002174", "胎胚描述-A", "SPEC-A", "PAT-A", "PAT-A");
        putMaterialInfo(context, "3302001647", "胎胚描述-A", "SPEC-B", "PAT-A", "PAT-A");
        putMaterialInfo(context, "3302001648", "胎胚描述-A", "SPEC-C", "PAT-A", "PAT-A");

        putMouldRel(context, "3302001556", "MOULD-1");
        putMouldRel(context, "3302002174", "MOULD-1");
        putMouldRel(context, "3302001647", "MOULD-1");
        putMouldRel(context, "3302001648", "MOULD-1");

        when(orderNoGenerator.generateOrderNo(any())).thenReturn("ORD-1", "ORD-2", "ORD-3");
        when(endingJudgmentStrategy.isEnding(any(), any())).thenAnswer(invocation -> {
            SkuScheduleDTO sku = invocation.getArgument(1);
            return "3302002174".equals(sku.getMaterialCode());
        });

        typeBlockProductionStrategy.scheduleTypeBlockChange(context);

        List<String> scheduledMaterials = new ArrayList<>(context.getScheduleResultList().size());
        for (LhScheduleResult result : context.getScheduleResultList()) {
            scheduledMaterials.add(result.getMaterialCode());
        }
        assertEquals(Arrays.asList("3302002174", "3302001647"), scheduledMaterials);
        assertEquals(1, context.getNewSpecSkuList().size());
        assertEquals("3302001648", context.getNewSpecSkuList().get(0).getMaterialCode());
    }

    @Test
    void scheduleTypeBlockChange_shouldWriteTraceLogsAfterActualEndTimeUpdate() {
        LhScheduleContext context = newTraceContext();
        context.getMachineScheduleMap().put("M1", buildMachine("M1", "MAT-C1"));
        context.getMachineScheduleMap().put("M2", buildMachine("M2", "MAT-C2"));
        context.getContinuousSkuList().add(buildContinuousSku("MAT-C1", "M1", "EMB-1", "STRUCT-A", "SPEC-A", "PAT-A", 1));
        context.getContinuousSkuList().add(buildContinuousSku("MAT-C2", "M2", "EMB-2", "STRUCT-D", "SPEC-D", "PAT-D", 3));
        context.getNewSpecSkuList().add(buildNewSku("MAT-T1", "EMB-1", "STRUCT-B", "SPEC-A", "PAT-B", 4));
        context.getNewSpecSkuList().add(buildNewSku("MAT-T2", "EMB-2", "STRUCT-E", "SPEC-D", "PAT-E", 4));
        putMaterialInfo(context, "MAT-C1", "胎胚描述-A", "SPEC-A", "PAT-A", "PAT-A");
        putMaterialInfo(context, "MAT-C2", "胎胚描述-B", "SPEC-D", "PAT-D", "PAT-D");
        putMaterialInfo(context, "MAT-T1", "胎胚描述-A", "SPEC-A", "PAT-B", "PAT-B");
        putMaterialInfo(context, "MAT-T2", "胎胚描述-B", "SPEC-D", "PAT-E", "PAT-E");
        putMouldRel(context, "MAT-C1", "MOULD-1");
        putMouldRel(context, "MAT-C2", "MOULD-2");
        putMouldRel(context, "MAT-T1", "MOULD-1");
        putMouldRel(context, "MAT-T2", "MOULD-2");

        when(orderNoGenerator.generateOrderNo(any())).thenReturn("ORD-1", "ORD-2", "ORD-3", "ORD-4");
        when(endingJudgmentStrategy.isEnding(any(), any())).thenAnswer(invocation -> {
            SkuScheduleDTO sku = invocation.getArgument(1);
            return sku.getMaterialCode().startsWith("MAT-C");
        });

        strategy.scheduleContinuousEnding(context);
        typeBlockProductionStrategy.scheduleTypeBlockChange(context);

        assertTrue(context.getScheduleLogList().size() >= 4);
        StringBuilder allLogText = new StringBuilder();
        for (LhScheduleProcessLog scheduleLog : context.getScheduleLogList()) {
            allLogText.append(scheduleLog.getTitle()).append('\n')
                    .append(scheduleLog.getLogDetail()).append('\n');
        }
        String logText = allLogText.toString();
        assertTrue(logText.contains("续作收尾真实时间回写"));
        assertTrue(logText.contains("衔接机台排序总览"));
        assertTrue(logText.contains("收尾机台衔接决策"));
        assertTrue(logText.contains("M1"));
        assertTrue(logText.contains("M2"));
        assertTrue(logText.contains("MAT-T1"));
        assertTrue(logText.contains("MAT-T2"));
        assertTrue(logText.contains("第一层"));
    }

    private LhScheduleContext newContext() {
        LhScheduleContext context = new LhScheduleContext();
        context.setFactoryCode("116");
        context.setBatchNo("LHPC20260418001");
        context.setScheduleDate(date(2026, 4, 18));
        context.setScheduleTargetDate(date(2026, 4, 20));
        context.setScheduleWindowShifts(LhScheduleTimeUtil.buildDefaultScheduleShifts(context, context.getScheduleDate()));
        context.setMachineScheduleMap(new LinkedHashMap<>());
        return context;
    }

    private LhScheduleContext newTraceContext() {
        LhScheduleContext context = newContext();
        Map<String, String> paramMap = new HashMap<>(4);
        paramMap.put(LhScheduleParamConstant.ENABLE_PRIORITY_TRACE_LOG, "1");
        context.setScheduleConfig(new LhScheduleConfig(paramMap));
        return context;
    }

    private void enableSpecifyMachineRule(LhScheduleContext context) {
        Map<String, String> paramMap = new HashMap<>(1);
        paramMap.put(LhScheduleParamConstant.ENABLE_SPECIFY_MACHINE_RULE, "1");
        context.setScheduleConfig(new LhScheduleConfig(paramMap));
    }

    private LhScheduleConfig createConfig(int typeBlockHours, int inspectionHours) {
        Map<String, String> paramMap = new HashMap<>(4);
        paramMap.put(LhScheduleParamConstant.TYPE_BLOCK_CHANGE_TOTAL_HOURS, String.valueOf(typeBlockHours));
        paramMap.put(LhScheduleParamConstant.FIRST_INSPECTION_HOURS, String.valueOf(inspectionHours));
        return new LhScheduleConfig(paramMap);
    }

    private MachineScheduleDTO buildMachine(String machineCode, String currentMaterialCode) {
        MachineScheduleDTO machine = new MachineScheduleDTO();
        machine.setMachineCode(machineCode);
        machine.setMachineName(machineCode);
        machine.setCurrentMaterialCode(currentMaterialCode);
        machine.setMaxMoldNum(1);
        machine.setEstimatedEndTime(dateTime(2026, 4, 18, 6, 0, 0));
        return machine;
    }

    private LhPrecisionPlan buildPrecisionPlan(String machineCode, Date dueDate) {
        LhPrecisionPlan plan = new LhPrecisionPlan();
        plan.setFactoryCode("116");
        plan.setMachineCode(machineCode);
        plan.setDueDate(dueDate);
        plan.setDaysToDue(10);
        plan.setCompletionStatus("0");
        return plan;
    }

    private SkuScheduleDTO buildContinuousSku(String materialCode,
                                              String machineCode,
                                              String embryoCode,
                                              String structureName,
                                              String specCode,
                                              String pattern,
                                              int pendingQty) {
        SkuScheduleDTO sku = new SkuScheduleDTO();
        sku.setMaterialCode(materialCode);
        sku.setMaterialDesc(materialCode);
        sku.setStructureName(structureName);
        sku.setEmbryoCode(embryoCode);
        sku.setSpecCode(specCode);
        sku.setMainPattern(pattern);
        sku.setPattern(pattern);
        sku.setContinuousMachineCode(machineCode);
        sku.setMonthPlanQty(pendingQty);
        sku.setWindowPlanQty(pendingQty);
        sku.setPendingQty(pendingQty);
        sku.setShiftCapacity(8);
        sku.setLhTimeSeconds(3600);
        sku.setScheduleType("01");
        return sku;
    }

    private SkuScheduleDTO buildNewSku(String materialCode,
                                       String embryoCode,
                                       String structureName,
                                       String specCode,
                                       String pattern,
                                       int pendingQty) {
        SkuScheduleDTO sku = new SkuScheduleDTO();
        sku.setMaterialCode(materialCode);
        sku.setMaterialDesc(materialCode);
        sku.setStructureName(structureName);
        sku.setEmbryoCode(embryoCode);
        sku.setSpecCode(specCode);
        sku.setMainPattern(pattern);
        sku.setPattern(pattern);
        sku.setMonthPlanQty(pendingQty);
        sku.setWindowPlanQty(pendingQty);
        sku.setPendingQty(pendingQty);
        sku.setShiftCapacity(8);
        sku.setLhTimeSeconds(3600);
        sku.setScheduleType("02");
        return sku;
    }

    private void putMachineOnlineInfo(LhScheduleContext context, String machineCode, String materialCode) {
        LhMachineOnlineInfo machineOnlineInfo = new LhMachineOnlineInfo();
        machineOnlineInfo.setLhCode(machineCode);
        machineOnlineInfo.setMaterialCode(materialCode);
        context.getMachineOnlineInfoMap().put(machineCode, machineOnlineInfo);
    }

    private LhScheduleResult buildPreviousScheduleResult(String machineCode,
                                                         String materialCode,
                                                         String isEnd,
                                                         Date specEndTime,
                                                         Date createTime) {
        LhScheduleResult previousResult = new LhScheduleResult();
        previousResult.setLhMachineCode(machineCode);
        previousResult.setMaterialCode(materialCode);
        previousResult.setIsEnd(isEnd);
        previousResult.setSpecEndTime(specEndTime);
        previousResult.setCreateTime(createTime);
        return previousResult;
    }

    private void putMouldRel(LhScheduleContext context, String materialCode, String mouldCode) {
        MdmSkuMouldRel relation = new MdmSkuMouldRel();
        relation.setMaterialCode(materialCode);
        relation.setMouldCode(mouldCode);
        context.getSkuMouldRelMap().put(materialCode, Arrays.asList(relation));
    }

    private LhSpecifyMachine specifyMachine(String materialCode, String machineCode, String jobType) {
        LhSpecifyMachine specifyMachine = new LhSpecifyMachine();
        specifyMachine.setSpecCode(materialCode);
        specifyMachine.setMachineCode(machineCode);
        specifyMachine.setJobType(jobType);
        return specifyMachine;
    }

    private void putMaterialInfo(LhScheduleContext context,
                                 String materialCode,
                                 String embryoDesc,
                                 String specCode,
                                 String mainPattern,
                                 String pattern) {
        MdmMaterialInfo materialInfo = new MdmMaterialInfo();
        materialInfo.setMaterialCode(materialCode);
        materialInfo.setEmbryoDesc(embryoDesc);
        materialInfo.setSpecifications(specCode);
        materialInfo.setMainPattern(mainPattern);
        materialInfo.setPattern(pattern);
        context.getMaterialInfoMap().put(materialCode, materialInfo);
    }

    private MdmDevicePlanShut buildDevicePlanShut(String machineCode, Date beginDate, Date endDate) {
        MdmDevicePlanShut planShut = new MdmDevicePlanShut();
        planShut.setMachineCode(machineCode);
        planShut.setBeginDate(beginDate);
        planShut.setEndDate(endDate);
        return planShut;
    }

    private Date resolveFirstStartTime(LhScheduleResult result) {
        for (int shiftIndex = 1; shiftIndex <= 8; shiftIndex++) {
            Date shiftStartTime = ShiftFieldUtil.getShiftStartTime(result, shiftIndex);
            if (shiftStartTime != null) {
                return shiftStartTime;
            }
        }
        return null;
    }

    private int resolveFirstPlannedShiftIndex(LhScheduleResult result) {
        for (int shiftIndex = 1; shiftIndex <= 8; shiftIndex++) {
            Integer shiftPlanQty = ShiftFieldUtil.getShiftPlanQty(result, shiftIndex);
            if (shiftPlanQty != null && shiftPlanQty > 0) {
                return shiftIndex;
            }
        }
        return 1;
    }

    private Date resolveActualCompletionTime(LhScheduleResult result) {
        int lhTimeSeconds = result.getLhTime() != null ? result.getLhTime() : 0;
        int mouldQty = result.getMouldQty() != null && result.getMouldQty() > 0 ? result.getMouldQty() : 1;
        Date completionTime = null;
        for (int shiftIndex = 1; shiftIndex <= 8; shiftIndex++) {
            Integer shiftPlanQty = ShiftFieldUtil.getShiftPlanQty(result, shiftIndex);
            Date shiftStartTime = ShiftFieldUtil.getShiftStartTime(result, shiftIndex);
            if (shiftPlanQty == null || shiftPlanQty <= 0 || shiftStartTime == null) {
                continue;
            }
            long secondsNeeded = (long) Math.ceil((double) shiftPlanQty / mouldQty) * lhTimeSeconds;
            Date shiftCompletionTime = new Date(shiftStartTime.getTime() + secondsNeeded * 1000L);
            if (completionTime == null || shiftCompletionTime.after(completionTime)) {
                completionTime = shiftCompletionTime;
            }
        }
        return completionTime;
    }

    private Date resolveLastShiftEndTime(LhScheduleResult result) {
        Date lastShiftEndTime = null;
        for (int shiftIndex = 1; shiftIndex <= 8; shiftIndex++) {
            Date shiftEndTime = ShiftFieldUtil.getShiftEndTime(result, shiftIndex);
            if (shiftEndTime != null && (lastShiftEndTime == null || shiftEndTime.after(lastShiftEndTime))) {
                lastShiftEndTime = shiftEndTime;
            }
        }
        return lastShiftEndTime;
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
