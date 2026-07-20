package com.zlt.aps.lh.regression;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.zlt.aps.lh.api.constant.LhScheduleParamConstant;
import com.zlt.aps.lh.api.domain.dto.CleaningScheduleDateFillItem;
import com.zlt.aps.lh.api.domain.dto.MachineCleaningWindowDTO;
import com.zlt.aps.lh.api.domain.dto.MachineScheduleDTO;
import com.zlt.aps.lh.api.domain.dto.SkuScheduleDTO;
import com.zlt.aps.lh.api.domain.entity.LhMouldChangePlan;
import com.zlt.aps.lh.api.domain.entity.LhMachineOnlineInfo;
import com.zlt.aps.lh.api.domain.entity.LhScheduleResult;
import com.zlt.aps.lh.api.enums.CleaningTypeEnum;
import com.zlt.aps.lh.api.enums.MouldChangeTypeEnum;
import com.zlt.aps.lh.component.MonthPlanDateResolver;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.exception.ScheduleException;
import com.zlt.aps.lh.handler.ResultValidationHandler;
import com.zlt.aps.lh.util.LhScheduleTimeUtil;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 结果校验处理器左右模回归测试。
 */
class ResultValidationHandlerLeftRightMouldRegressionTest {

    @Test
    void postValidation_shouldRoundDoubleMouldOddShiftQtyBeforeSave() {
        ResultValidationHandler handler = new ResultValidationHandler();
        LhScheduleContext context = newContext();
        LhScheduleResult result = new LhScheduleResult();
        result.setFactoryCode("116");
        result.setBatchNo("LHPC20260614001");
        result.setLhMachineCode("K1902");
        result.setMaterialCode("3302002530");
        result.setScheduleType("02");
        result.setMouldQty(2);
        result.setIsRelease("0");
        result.setDailyPlanQty(3);
        result.setClass3PlanQty(3);
        result.setClass3StartTime(dateTime(2026, 6, 13, 22, 0));
        result.setClass3EndTime(dateTime(2026, 6, 14, 6, 0));
        result.setSpecEndTime(dateTime(2026, 6, 14, 6, 0));
        context.getScheduleResultList().add(result);

        ReflectionTestUtils.invokeMethod(handler, "postValidation", context);

        assertEquals(Integer.valueOf(4), result.getClass3PlanQty());
        assertEquals(Integer.valueOf(4), result.getDailyPlanQty());
    }

    @Test
    void postValidation_shouldKeepOddShiftQtyForEmbryoStockEnding() {
        ResultValidationHandler handler = new ResultValidationHandler();
        LhScheduleContext context = newContext();
        context.getEmbryoEndingFlagMap().put("EMB-END-07", 1);
        LhScheduleResult result = new LhScheduleResult();
        result.setFactoryCode("116");
        result.setBatchNo("LHPC20260614001");
        result.setLhMachineCode("K1902");
        result.setMaterialCode("3302003009");
        result.setEmbryoCode("EMB-END-07");
        result.setScheduleType("02");
        result.setMouldQty(2);
        result.setIsRelease("0");
        result.setDailyPlanQty(3);
        result.setClass3PlanQty(3);
        result.setClass3StartTime(dateTime(2026, 6, 13, 22, 0));
        result.setClass3EndTime(dateTime(2026, 6, 14, 6, 0));
        result.setSpecEndTime(dateTime(2026, 6, 14, 6, 0));
        context.getScheduleResultList().add(result);

        ReflectionTestUtils.invokeMethod(handler, "postValidation", context);

        assertEquals(Integer.valueOf(3), result.getClass3PlanQty());
        assertEquals(Integer.valueOf(3), result.getDailyPlanQty());
    }

    @Test
    void validateFormalQuantityPolicy_shouldAllowExactlyOneShiftFillOver() {
        ResultValidationHandler handler = new ResultValidationHandler();
        LhScheduleContext context = newContext();
        SkuScheduleDTO sku = newSku("3302002654");
        assertDoesNotThrow(() -> ReflectionTestUtils.invokeMethod(handler,
                "validateFormalQuantityPolicy", context, sku, 153, 136, 17));
        assertThrows(ScheduleException.class, () -> ReflectionTestUtils.invokeMethod(handler,
                "validateFormalQuantityPolicy", context, sku, 170, 136, 17));
    }

    @Test
    void validateFormalQuantityPolicy_shouldAllowShiftFillOverQtyAcrossMultipleFilledShifts() {
        ResultValidationHandler handler = new ResultValidationHandler();
        LhScheduleContext context = newContext();
        SkuScheduleDTO sku = newSku("3302002654");
        sku.setShiftFillOverQty(23);

        assertDoesNotThrow(() -> ReflectionTestUtils.invokeMethod(handler,
                "validateFormalQuantityPolicy", context, sku, 323, 300, 17));
        assertThrows(ScheduleException.class, () -> ReflectionTestUtils.invokeMethod(handler,
                "validateFormalQuantityPolicy", context, sku, 324, 300, 17));
    }

    @Test
    void generateMouldChangePlan_shouldKeepResultLeftRightMould() {
        ResultValidationHandler handler = new ResultValidationHandler();
        LhScheduleContext context = newContext();
        context.setMachineScheduleMap(new LinkedHashMap<>());
        context.setInitialMachineScheduleMap(new LinkedHashMap<>());
        LhScheduleResult result = new LhScheduleResult();
        result.setFactoryCode("116");
        result.setBatchNo("LHPC20260417003");
        result.setLhMachineCode("K1501");
        result.setLhMachineName("华澳");
        result.setScheduleType("02");
        result.setIsChangeMould("1");
        result.setLeftRightMould("R");
        result.setMaterialCode("3302001690");
        result.setMaterialDesc("11R24.5 149/146L 16PR JD727 BL4HJY");
        result.setMouldCode("HM20231203902");
        result.setDailyPlanQty(10);
        result.setClass1StartTime(dateTime(2026, 4, 17, 7, 0));
        result.setSpecEndTime(dateTime(2026, 4, 17, 14, 0));
        context.getScheduleResultList().add(result);

        ReflectionTestUtils.invokeMethod(handler, "generateMouldChangePlan", context);
        assertEquals(1, context.getMouldChangePlanList().size());
        LhMouldChangePlan plan = context.getMouldChangePlanList().get(0);
        assertEquals("R", plan.getLeftRightMould());
    }

    @Test
    void generateMouldChangePlan_shouldUseSnapshotAsBeforeAndResultAsAfterForFirstPlan() {
        ResultValidationHandler handler = new ResultValidationHandler();
        LhScheduleContext context = newContext();
        context.setMachineScheduleMap(new LinkedHashMap<>());
        context.setInitialMachineScheduleMap(new LinkedHashMap<>());
        MachineScheduleDTO machine = new MachineScheduleDTO();
        machine.setMachineCode("K1501");
        machine.setCurrentMaterialCode("MAT-ONLINE");
        machine.setCurrentMaterialDesc("当前在机物料");
        context.getMachineScheduleMap().put("K1501", machine);
        context.getInitialMachineScheduleMap().put("K1501", machine);

        LhScheduleResult result = buildChangeResult("K1501", "MAT-NEW", "排程物料", dateTime(2026, 4, 17, 7, 0),
                dateTime(2026, 4, 17, 14, 0));
        context.getScheduleResultList().add(result);

        ReflectionTestUtils.invokeMethod(handler, "generateMouldChangePlan", context);
        assertEquals(1, context.getMouldChangePlanList().size());
        LhMouldChangePlan plan = context.getMouldChangePlanList().get(0);
        assertEquals("MAT-ONLINE", plan.getBeforeMaterialCode());
        assertEquals("当前在机物料", plan.getBeforeMaterialDesc());
        assertEquals("MAT-NEW", plan.getAfterMaterialCode());
        assertEquals("排程物料", plan.getAfterMaterialDesc());
    }

    /**
     * 续作降模机台仍有前物料余量，因正规换模生成交替计划时，应按时间下机。
     */
    @Test
    void generateMouldChangePlan_shouldUseTimeEndTypeForReducedContinuationRegularChange() {
        ResultValidationHandler handler = new ResultValidationHandler();
        LhScheduleContext context = newContext();
        prepareInitialMachine(context, "K1501", "MAT-BEFORE", "降模前物料");
        registerReducedContinuationSnapshot(context, "K1501", "MAT-BEFORE", 18, 6);

        LhScheduleResult result = buildChangeResult("K1501", "MAT-AFTER", "换模后物料",
                dateTime(2026, 4, 17, 7, 0), dateTime(2026, 4, 17, 14, 0));
        // 后物料是否收尾不得影响 END_TYPE，本用例故意设为收尾，验证判断只读取前物料快照。
        result.setIsEnd("1");
        context.getScheduleResultList().add(result);

        ReflectionTestUtils.invokeMethod(handler, "generateMouldChangePlan", context);

        assertEquals("1", context.getMouldChangePlanList().get(0).getEndType());
    }

    /**
     * 续作降模机台仍有前物料余量，因换活字块生成交替计划时，应按时间下机。
     */
    @Test
    void generateMouldChangePlan_shouldUseTimeEndTypeForReducedContinuationTypeBlockChange() {
        ResultValidationHandler handler = new ResultValidationHandler();
        LhScheduleContext context = newContext();
        prepareInitialMachine(context, "K1502", "MAT-BEFORE", "降模前物料");
        registerReducedContinuationSnapshot(context, "K1502", "MAT-BEFORE", 12, 4);

        LhScheduleResult result = buildChangeResult("K1502", "MAT-AFTER", "换活字块后物料",
                dateTime(2026, 4, 17, 7, 0), dateTime(2026, 4, 17, 14, 0));
        result.setIsTypeBlock("1");
        result.setIsEnd("1");
        context.getScheduleResultList().add(result);

        ReflectionTestUtils.invokeMethod(handler, "generateMouldChangePlan", context);

        assertEquals(MouldChangeTypeEnum.TYPE_BLOCK.getCode(),
                context.getMouldChangePlanList().get(0).getChangeMouldType());
        assertEquals("1", context.getMouldChangePlanList().get(0).getEndType());
    }

    /**
     * 喷砂、干冰清洗只有在清洗时点的前物料属于续作降模且仍有余量时，才按时间下机。
     */
    @Test
    void generateMouldChangePlan_shouldUseTimeEndTypeForReducedContinuationCleaningPlans() {
        ResultValidationHandler handler = new ResultValidationHandler();
        LhScheduleContext context = newContext();
        MachineScheduleDTO sandBlastMachine = buildCleaningMachine(
                "K1503", "MAT-SAND", CleaningTypeEnum.SAND_BLAST.getCode(), dateTime(2026, 4, 17, 8, 0));
        MachineScheduleDTO dryIceMachine = buildCleaningMachine(
                "K1504", "MAT-DRY", CleaningTypeEnum.DRY_ICE.getCode(), dateTime(2026, 4, 17, 9, 0));
        // 最终机台态故意设为收尾，验证清洗 END_TYPE 不再读取后置机台 ending 状态。
        sandBlastMachine.setEnding(true);
        dryIceMachine.setEnding(true);
        context.getMachineScheduleMap().put("K1503", sandBlastMachine);
        context.getInitialMachineScheduleMap().put("K1503", sandBlastMachine);
        context.getMachineScheduleMap().put("K1504", dryIceMachine);
        context.getInitialMachineScheduleMap().put("K1504", dryIceMachine);
        registerReducedContinuationSnapshot(context, "K1503", "MAT-SAND", 9, 3);
        registerReducedContinuationSnapshot(context, "K1504", "MAT-DRY", 7, 1);

        ReflectionTestUtils.invokeMethod(handler, "generateMouldChangePlan", context);

        assertEquals(2, context.getMouldChangePlanList().size());
        assertTrue(context.getMouldChangePlanList().stream().allMatch(plan -> "1".equals(plan.getEndType())));
    }

    /**
     * 前物料正常按余量收尾而非续作降模释放时，即使后续换模也必须按余量收尾下机。
     */
    @Test
    void generateMouldChangePlan_shouldUseRemainingQtyEndTypeForNormalEndingBeforeMaterial() {
        ResultValidationHandler handler = new ResultValidationHandler();
        LhScheduleContext context = newContext();
        prepareInitialMachine(context, "K1505", "MAT-BEFORE", "正常收尾前物料");
        LhScheduleResult result = buildChangeResult("K1505", "MAT-AFTER", "换模后物料",
                dateTime(2026, 4, 17, 7, 0), dateTime(2026, 4, 17, 14, 0));
        context.getScheduleResultList().add(result);

        ReflectionTestUtils.invokeMethod(handler, "generateMouldChangePlan", context);

        assertEquals("0", context.getMouldChangePlanList().get(0).getEndType());
    }

    /**
     * 前物料虽然属于续作降模下机，但硫化余量已经为0时，仍应按余量收尾下机。
     */
    @Test
    void generateMouldChangePlan_shouldUseRemainingQtyEndTypeWhenReducedContinuationHasNoSurplus() {
        ResultValidationHandler handler = new ResultValidationHandler();
        LhScheduleContext context = newContext();
        prepareInitialMachine(context, "K1508", "MAT-BEFORE", "零余量降模前物料");
        registerReducedContinuationSnapshot(context, "K1508", "MAT-BEFORE", 0, 0);
        LhScheduleResult result = buildChangeResult("K1508", "MAT-AFTER", "换模后物料",
                dateTime(2026, 4, 17, 7, 0), dateTime(2026, 4, 17, 14, 0));
        context.getScheduleResultList().add(result);

        ReflectionTestUtils.invokeMethod(handler, "generateMouldChangePlan", context);

        assertEquals("0", context.getMouldChangePlanList().get(0).getEndType());
    }

    /**
     * 前物料虽然由续作降模释放且排前余量大于0，但本次排程已将该SKU余量排完时，应按余量收尾下机。
     */
    @Test
    void generateMouldChangePlan_shouldUseRemainingQtyEndTypeWhenBeforeMaterialCanFinishThisSchedule() {
        ResultValidationHandler handler = new ResultValidationHandler();
        LhScheduleContext context = newContext();
        prepareInitialMachine(context, "K1509", "MAT-BEFORE", "本次可收尾前物料");
        // 初始余量大于0，但运行态剩余账本已扣减为0，表示该前物料SKU可在本次排程中收尾。
        registerReducedContinuationSnapshot(context, "K1509", "MAT-BEFORE", 18, 0);
        LhScheduleResult result = buildChangeResult("K1509", "MAT-AFTER", "换模后物料",
                dateTime(2026, 4, 17, 7, 0), dateTime(2026, 4, 17, 14, 0));
        context.getScheduleResultList().add(result);

        ReflectionTestUtils.invokeMethod(handler, "generateMouldChangePlan", context);

        assertEquals("0", context.getMouldChangePlanList().get(0).getEndType());
    }

    /**
     * 小余量阈值跳过只属于续作释放，不属于续作降模，后续交替计划必须按余量收尾下机。
     */
    @Test
    void generateMouldChangePlan_shouldUseRemainingQtyEndTypeForSmallSurplusSkippedBeforeMaterial() {
        ResultValidationHandler handler = new ResultValidationHandler();
        LhScheduleContext context = newContext();
        prepareInitialMachine(context, "K1506", "MAT-SMALL", "小余量跳过物料");
        // 复刻小余量规则已有释放结果，但不登记续作降模快照。
        context.getReleasedContinuousMachineCodeSet().add("K1506");
        context.getTypeBlockReleasedContinuousMachineCodeSet().add("K1506");
        LhScheduleResult result = buildChangeResult("K1506", "MAT-AFTER", "换活字块后物料",
                dateTime(2026, 4, 17, 7, 0), dateTime(2026, 4, 17, 14, 0));
        result.setIsTypeBlock("1");
        context.getScheduleResultList().add(result);

        ReflectionTestUtils.invokeMethod(handler, "generateMouldChangePlan", context);

        assertEquals("0", context.getMouldChangePlanList().get(0).getEndType());
    }

    /**
     * 后物料即使存在续作降模快照，只要交替计划前物料不满足条件，仍必须按余量收尾下机。
     */
    @Test
    void generateMouldChangePlan_shouldIgnoreAfterMaterialWhenResolvingEndType() {
        ResultValidationHandler handler = new ResultValidationHandler();
        LhScheduleContext context = newContext();
        prepareInitialMachine(context, "K1507", "MAT-BEFORE", "不满足条件的前物料");
        registerReducedContinuationSnapshot(context, "K1507", "MAT-AFTER", 20, 8);
        LhScheduleResult result = buildChangeResult("K1507", "MAT-AFTER", "满足条件的后物料",
                dateTime(2026, 4, 17, 7, 0), dateTime(2026, 4, 17, 14, 0));
        context.getScheduleResultList().add(result);

        ReflectionTestUtils.invokeMethod(handler, "generateMouldChangePlan", context);

        assertEquals("0", context.getMouldChangePlanList().get(0).getEndType());
    }

    @Test
    void generateMouldChangePlan_shouldRollBeforeAfterMaterialAcrossPlans() {
        ResultValidationHandler handler = new ResultValidationHandler();
        LhScheduleContext context = newContext();
        context.setMachineScheduleMap(new LinkedHashMap<>());
        context.setInitialMachineScheduleMap(new LinkedHashMap<>());
        MachineScheduleDTO machine = new MachineScheduleDTO();
        machine.setMachineCode("K1501");
        machine.setCurrentMaterialCode("MAT-ONLINE");
        machine.setCurrentMaterialDesc("当前在机物料");
        context.getMachineScheduleMap().put("K1501", machine);
        context.getInitialMachineScheduleMap().put("K1501", machine);

        LhScheduleResult first = buildChangeResult("K1501", "MAT-A", "物料A", dateTime(2026, 4, 17, 7, 0),
                dateTime(2026, 4, 17, 10, 0));
        LhScheduleResult second = buildChangeResult("K1501", "MAT-B", "物料B", dateTime(2026, 4, 17, 11, 0),
                dateTime(2026, 4, 17, 14, 0));
        context.getScheduleResultList().add(first);
        context.getScheduleResultList().add(second);

        ReflectionTestUtils.invokeMethod(handler, "generateMouldChangePlan", context);
        assertEquals(2, context.getMouldChangePlanList().size());

        LhMouldChangePlan firstPlan = context.getMouldChangePlanList().get(0);
        assertEquals("MAT-ONLINE", firstPlan.getBeforeMaterialCode());
        assertEquals("当前在机物料", firstPlan.getBeforeMaterialDesc());
        assertEquals("MAT-A", firstPlan.getAfterMaterialCode());
        assertEquals("物料A", firstPlan.getAfterMaterialDesc());

        LhMouldChangePlan secondPlan = context.getMouldChangePlanList().get(1);
        assertEquals("MAT-A", secondPlan.getBeforeMaterialCode());
        assertEquals("物料A", secondPlan.getBeforeMaterialDesc());
        assertEquals("MAT-B", secondPlan.getAfterMaterialCode());
        assertEquals("物料B", secondPlan.getAfterMaterialDesc());
    }

    @Test
    void generateMouldChangePlan_shouldSkipRegularPlanWhenConsecutiveResultsShareMaterialDespiteDifferentProductStatus() {
        ResultValidationHandler handler = new ResultValidationHandler();
        LhScheduleContext context = newContext();
        context.setMachineScheduleMap(new LinkedHashMap<>());
        context.setInitialMachineScheduleMap(new LinkedHashMap<>());
        MachineScheduleDTO machine = new MachineScheduleDTO();
        machine.setMachineCode("K1501");
        machine.setCurrentMaterialCode("MAT-ONLINE");
        machine.setCurrentMaterialDesc("当前在机物料");
        context.getMachineScheduleMap().put("K1501", machine);
        context.getInitialMachineScheduleMap().put("K1501", machine);

        LhScheduleResult formalResult = buildChangeResult("K1501", "MAT-SAME", "同物料正规示方",
                dateTime(2026, 4, 17, 7, 0), dateTime(2026, 4, 17, 10, 0));
        formalResult.setProductStatus("S");
        LhScheduleResult trialResult = buildChangeResult("K1501", "MAT-SAME", "同物料试验示方",
                dateTime(2026, 4, 17, 11, 0), dateTime(2026, 4, 17, 14, 0));
        trialResult.setProductStatus("T");
        context.getScheduleResultList().add(formalResult);
        context.getScheduleResultList().add(trialResult);

        ReflectionTestUtils.invokeMethod(handler, "generateMouldChangePlan", context);

        assertEquals(1, context.getMouldChangePlanList().size());
        assertEquals("MAT-ONLINE", context.getMouldChangePlanList().get(0).getBeforeMaterialCode());
        assertEquals("MAT-SAME", context.getMouldChangePlanList().get(0).getAfterMaterialCode());
        assertEquals(MouldChangeTypeEnum.REGULAR.getCode(),
                context.getMouldChangePlanList().get(0).getChangeMouldType());
    }

    @Test
    void generateMouldChangePlan_shouldSkipTypeBlockPlanWhenConsecutiveResultsShareMaterialDespiteDifferentProductStatus() {
        ResultValidationHandler handler = new ResultValidationHandler();
        LhScheduleContext context = newContext();
        context.setMachineScheduleMap(new LinkedHashMap<>());
        context.setInitialMachineScheduleMap(new LinkedHashMap<>());
        MachineScheduleDTO machine = new MachineScheduleDTO();
        machine.setMachineCode("K1501");
        machine.setCurrentMaterialCode("MAT-ONLINE");
        machine.setCurrentMaterialDesc("当前在机物料");
        context.getMachineScheduleMap().put("K1501", machine);
        context.getInitialMachineScheduleMap().put("K1501", machine);

        LhScheduleResult formalResult = buildChangeResult("K1501", "MAT-SAME", "同物料正规示方",
                dateTime(2026, 4, 17, 7, 0), dateTime(2026, 4, 17, 10, 0));
        formalResult.setIsTypeBlock("1");
        formalResult.setProductStatus("S");
        LhScheduleResult trialResult = buildChangeResult("K1501", "MAT-SAME", "同物料试验示方",
                dateTime(2026, 4, 17, 11, 0), dateTime(2026, 4, 17, 14, 0));
        trialResult.setIsTypeBlock("1");
        trialResult.setProductStatus("T");
        context.getScheduleResultList().add(formalResult);
        context.getScheduleResultList().add(trialResult);

        ReflectionTestUtils.invokeMethod(handler, "generateMouldChangePlan", context);

        assertEquals(1, context.getMouldChangePlanList().size());
        assertEquals(MouldChangeTypeEnum.TYPE_BLOCK.getCode(),
                context.getMouldChangePlanList().get(0).getChangeMouldType());
    }

    @Test
    void generateMouldChangePlan_shouldAdvanceRollingStateAfterSkippingSameMaterialResult() {
        ResultValidationHandler handler = new ResultValidationHandler();
        LhScheduleContext context = newContext();
        context.setMachineScheduleMap(new LinkedHashMap<>());
        context.setInitialMachineScheduleMap(new LinkedHashMap<>());
        MachineScheduleDTO machine = new MachineScheduleDTO();
        machine.setMachineCode("K1501");
        machine.setCurrentMaterialCode("MAT-SAME");
        machine.setCurrentMaterialDesc("当前在机物料");
        context.getMachineScheduleMap().put("K1501", machine);
        context.getInitialMachineScheduleMap().put("K1501", machine);

        LhScheduleResult sameMaterialResult = buildChangeResult("K1501", "MAT-SAME", "同物料续作",
                dateTime(2026, 4, 17, 7, 0), dateTime(2026, 4, 17, 10, 0));
        LhScheduleResult nextMaterialResult = buildChangeResult("K1501", "MAT-NEXT", "下一物料",
                dateTime(2026, 4, 17, 11, 0), dateTime(2026, 4, 17, 14, 0));
        context.getScheduleResultList().add(sameMaterialResult);
        context.getScheduleResultList().add(nextMaterialResult);
        AtomicInteger changePlanSequence = (AtomicInteger) ReflectionTestUtils.getField(
                ResultValidationHandler.class, "CHG_SEQ");
        int sequenceBeforeGenerate = changePlanSequence.get();

        ReflectionTestUtils.invokeMethod(handler, "generateMouldChangePlan", context);

        assertEquals(1, context.getMouldChangePlanList().size());
        LhMouldChangePlan plan = context.getMouldChangePlanList().get(0);
        assertEquals(1, plan.getPlanOrder());
        assertEquals("MAT-SAME", plan.getBeforeMaterialCode());
        assertEquals("MAT-NEXT", plan.getAfterMaterialCode());
        assertEquals(sameMaterialResult.getSpecEndTime(), plan.getChangeTime());
        assertTrue(plan.getOrderNo().endsWith(String.format("%03d", (sequenceBeforeGenerate + 1) % 1000)));
        assertEquals(sequenceBeforeGenerate + 1, changePlanSequence.get());
    }

    @Test
    void generateMouldChangePlan_shouldKeepPlanWhenEitherMaterialCodeIsMissing() {
        ResultValidationHandler handler = new ResultValidationHandler();
        LhScheduleContext context = newContext();
        context.setMachineScheduleMap(new LinkedHashMap<>());
        context.setInitialMachineScheduleMap(new LinkedHashMap<>());
        MachineScheduleDTO machine = new MachineScheduleDTO();
        machine.setMachineCode("K1501");
        context.getMachineScheduleMap().put("K1501", machine);
        context.getInitialMachineScheduleMap().put("K1501", machine);

        LhScheduleResult beforeMaterialMissingResult = buildChangeResult("K1501", "MAT-NEW", "新上物料",
                dateTime(2026, 4, 17, 7, 0), dateTime(2026, 4, 17, 14, 0));
        LhScheduleResult afterMaterialMissingResult = buildChangeResult("K1501", null, "缺失物料编码",
                dateTime(2026, 4, 17, 15, 0), dateTime(2026, 4, 17, 22, 0));
        context.getScheduleResultList().add(beforeMaterialMissingResult);
        context.getScheduleResultList().add(afterMaterialMissingResult);

        ReflectionTestUtils.invokeMethod(handler, "generateMouldChangePlan", context);

        assertEquals(2, context.getMouldChangePlanList().size());
        assertEquals(null, context.getMouldChangePlanList().get(0).getBeforeMaterialCode());
        assertEquals("MAT-NEW", context.getMouldChangePlanList().get(0).getAfterMaterialCode());
        assertEquals("MAT-NEW", context.getMouldChangePlanList().get(1).getBeforeMaterialCode());
        assertEquals(null, context.getMouldChangePlanList().get(1).getAfterMaterialCode());
    }

    @Test
    void generateMouldChangePlan_shouldKeepEndingToNewSkuFlowAcrossDays() {
        ResultValidationHandler handler = new ResultValidationHandler();
        LhScheduleContext context = newContext();
        context.setScheduleTargetDate(date(2026, 4, 23));
        context.setMachineScheduleMap(new LinkedHashMap<>());
        context.setInitialMachineScheduleMap(new LinkedHashMap<>());
        MachineScheduleDTO machine = new MachineScheduleDTO();
        machine.setMachineCode("K1113");
        machine.setCurrentMaterialCode("MAT-MES");
        machine.setCurrentMaterialDesc("MES在机物料");
        context.getMachineScheduleMap().put("K1113", machine);
        context.getInitialMachineScheduleMap().put("K1113", machine);

        LhScheduleResult endingResult = buildChangeResult("K1113", "MAT-END", "收尾物料",
                dateTime(2026, 4, 21, 14, 0), dateTime(2026, 4, 22, 14, 0));
        LhScheduleResult newSkuResult = buildChangeResult("K1113", "MAT-NEW", "新上物料",
                dateTime(2026, 4, 23, 14, 0), dateTime(2026, 4, 23, 22, 0));
        context.getScheduleResultList().add(endingResult);
        context.getScheduleResultList().add(newSkuResult);

        ReflectionTestUtils.invokeMethod(handler, "generateMouldChangePlan", context);
        assertEquals(2, context.getMouldChangePlanList().size());

        LhMouldChangePlan firstPlan = context.getMouldChangePlanList().get(0);
        assertEquals("MAT-MES", firstPlan.getBeforeMaterialCode());
        assertEquals("MES在机物料", firstPlan.getBeforeMaterialDesc());
        assertEquals("MAT-END", firstPlan.getAfterMaterialCode());
        assertEquals("收尾物料", firstPlan.getAfterMaterialDesc());

        LhMouldChangePlan secondPlan = context.getMouldChangePlanList().get(1);
        assertEquals("MAT-END", secondPlan.getBeforeMaterialCode());
        assertEquals("收尾物料", secondPlan.getBeforeMaterialDesc());
        assertEquals("MAT-NEW", secondPlan.getAfterMaterialCode());
        assertEquals("新上物料", secondPlan.getAfterMaterialDesc());
    }

    @Test
    void generateMouldChangePlan_shouldAlignPlanDateAndChangeTimeToRealMouldChangeStartTime() {
        ResultValidationHandler handler = new ResultValidationHandler();
        LhScheduleContext context = newContext();
        context.setMachineScheduleMap(new LinkedHashMap<>());
        context.setInitialMachineScheduleMap(new LinkedHashMap<>());

        LhScheduleResult result = buildChangeResult("K2024", "MAT-NEW", "新上物料",
                dateTime(2026, 4, 22, 14, 0), dateTime(2026, 4, 22, 22, 0));
        Date mouldChangeStartTime = dateTime(2026, 4, 22, 6, 0);
        ReflectionTestUtils.setField(result, "mouldChangeStartTime", mouldChangeStartTime);
        context.getScheduleResultList().add(result);

        ReflectionTestUtils.invokeMethod(handler, "generateMouldChangePlan", context);

        assertEquals(1, context.getMouldChangePlanList().size());
        LhMouldChangePlan plan = context.getMouldChangePlanList().get(0);
        assertEquals(mouldChangeStartTime, plan.getPlanDate());
        assertEquals(mouldChangeStartTime, plan.getChangeTime());
    }

    @Test
    void generateMouldChangePlan_shouldStoreMorningShiftCodeForShiftIndex() {
        ResultValidationHandler handler = new ResultValidationHandler();
        LhScheduleContext context = newContext();
        context.setMachineScheduleMap(new LinkedHashMap<>());
        context.setInitialMachineScheduleMap(new LinkedHashMap<>());

        LhScheduleResult result = buildChangeResult("K2024", "MAT-MORNING", "早班物料",
                dateTime(2026, 4, 17, 7, 0), dateTime(2026, 4, 17, 14, 0));
        Date mouldChangeStartTime = dateTime(2026, 4, 17, 6, 0);
        ReflectionTestUtils.setField(result, "mouldChangeStartTime", mouldChangeStartTime);
        context.getScheduleResultList().add(result);

        ReflectionTestUtils.invokeMethod(handler, "generateMouldChangePlan", context);

        assertEquals(1, context.getMouldChangePlanList().size());
        assertEquals("02", context.getMouldChangePlanList().get(0).getClassIndex());
    }

    @Test
    void generateMouldChangePlan_shouldStoreAfternoonShiftCodeForShiftIndex() {
        ResultValidationHandler handler = new ResultValidationHandler();
        LhScheduleContext context = newContext();
        context.setMachineScheduleMap(new LinkedHashMap<>());
        context.setInitialMachineScheduleMap(new LinkedHashMap<>());

        LhScheduleResult result = buildChangeResult("K2025", "MAT-AFTERNOON", "中班物料",
                dateTime(2026, 4, 17, 15, 0), dateTime(2026, 4, 17, 22, 0));
        Date mouldChangeStartTime = dateTime(2026, 4, 17, 14, 0);
        ReflectionTestUtils.setField(result, "mouldChangeStartTime", mouldChangeStartTime);
        context.getScheduleResultList().add(result);

        ReflectionTestUtils.invokeMethod(handler, "generateMouldChangePlan", context);

        assertEquals(1, context.getMouldChangePlanList().size());
        assertEquals("03", context.getMouldChangePlanList().get(0).getClassIndex());
    }

    @Test
    void generateMouldChangePlan_shouldStoreNightShiftCodeAcrossDays() {
        ResultValidationHandler handler = new ResultValidationHandler();
        LhScheduleContext context = newContext();
        context.setMachineScheduleMap(new LinkedHashMap<>());
        context.setInitialMachineScheduleMap(new LinkedHashMap<>());

        LhScheduleResult result = buildChangeResult("K2026", "MAT-NIGHT", "夜班物料",
                dateTime(2026, 4, 18, 1, 0), dateTime(2026, 4, 18, 6, 0));
        Date mouldChangeStartTime = dateTime(2026, 4, 17, 22, 0);
        ReflectionTestUtils.setField(result, "mouldChangeStartTime", mouldChangeStartTime);
        context.getScheduleResultList().add(result);

        ReflectionTestUtils.invokeMethod(handler, "generateMouldChangePlan", context);

        assertEquals(1, context.getMouldChangePlanList().size());
        assertEquals("01", context.getMouldChangePlanList().get(0).getClassIndex());
    }

    @Test
    void generateMouldChangePlan_shouldKeepClassIndexEmptyWhenTimeOutsideShiftWindow() {
        ResultValidationHandler handler = new ResultValidationHandler();
        LhScheduleContext context = newContext();
        context.setScheduleDate(date(2026, 4, 17));
        context.setWindowEndDate(date(2026, 4, 19));
        context.setScheduleWindowShifts(LhScheduleTimeUtil.buildDefaultScheduleShifts(
                context, context.getScheduleDate()));
        context.setMachineScheduleMap(new LinkedHashMap<>());
        context.setInitialMachineScheduleMap(new LinkedHashMap<>());

        LhScheduleResult result = buildChangeResult("K2027", "MAT-UNKNOWN", "窗口外物料",
                dateTime(2026, 4, 20, 7, 0), dateTime(2026, 4, 20, 14, 0));
        Date mouldChangeStartTime = dateTime(2026, 4, 20, 6, 0);
        ReflectionTestUtils.setField(result, "mouldChangeStartTime", mouldChangeStartTime);
        context.getScheduleResultList().add(result);

        Logger logger = (Logger) LoggerFactory.getLogger(ResultValidationHandler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            ReflectionTestUtils.invokeMethod(handler, "generateMouldChangePlan", context);
        } finally {
            logger.detachAppender(appender);
        }

        assertEquals(1, context.getMouldChangePlanList().size());
        assertEquals(null, context.getMouldChangePlanList().get(0).getClassIndex());
        assertTrue(appender.list.stream().anyMatch(event -> event.getFormattedMessage()
                .contains("模具交替计划时间超出排程窗口")));
    }

    @Test
    void generateMouldChangePlan_shouldKeepInheritedPlanAndOnlyAppendNewPlan() {
        ResultValidationHandler handler = new ResultValidationHandler();
        LhScheduleContext context = newContext();
        context.setMachineScheduleMap(new LinkedHashMap<>());
        context.setInitialMachineScheduleMap(new LinkedHashMap<>());

        LhMouldChangePlan inheritedPlan = new LhMouldChangePlan();
        inheritedPlan.setFactoryCode("116");
        inheritedPlan.setLhResultBatchNo("LHPC20260417003");
        inheritedPlan.setScheduleDate(date(2026, 4, 17));
        inheritedPlan.setLhMachineCode("K1501");
        inheritedPlan.setChangeMouldType("02");
        context.getMouldChangePlanList().add(inheritedPlan);

        LhScheduleResult inheritedResult = buildChangeResult("K1501", "MAT-INHERITED", "继承物料",
                dateTime(2026, 4, 17, 7, 0), dateTime(2026, 4, 17, 14, 0));
        inheritedResult.setRollingInherited(true);
        context.getScheduleResultList().add(inheritedResult);

        LhScheduleResult newResult = buildChangeResult("K1502", "MAT-NEW", "新增物料",
                dateTime(2026, 4, 17, 15, 0), dateTime(2026, 4, 17, 22, 0));
        context.getScheduleResultList().add(newResult);

        ReflectionTestUtils.invokeMethod(handler, "generateMouldChangePlan", context);

        assertEquals(2, context.getMouldChangePlanList().size());
        assertEquals("02", context.getMouldChangePlanList().get(0).getChangeMouldType());
        assertEquals("K1501", context.getMouldChangePlanList().get(0).getLhMachineCode());
        assertEquals("K1502", context.getMouldChangePlanList().get(1).getLhMachineCode());
    }

    @Test
    void generateMouldChangePlan_shouldAppendCleaningWindowPlans() {
        ResultValidationHandler handler = new ResultValidationHandler();
        LhScheduleContext context = newContext();
        context.setMachineScheduleMap(new LinkedHashMap<>());
        context.setInitialMachineScheduleMap(new LinkedHashMap<>());
        MachineScheduleDTO machine = new MachineScheduleDTO();
        machine.setMachineCode("K1501");
        machine.setMachineName("华澳");
        machine.setCurrentMaterialCode("MAT-ONLINE");
        machine.setCurrentMaterialDesc("当前在机物料");

        MachineCleaningWindowDTO dryIceWindow = new MachineCleaningWindowDTO();
        dryIceWindow.setLhCode("K1501");
        dryIceWindow.setCleanType(CleaningTypeEnum.DRY_ICE.getCode());
        dryIceWindow.setCleanStartTime(dateTime(2026, 4, 17, 8, 0));
        dryIceWindow.setCleanEndTime(dateTime(2026, 4, 17, 11, 0));
        dryIceWindow.setLeftRightMould("LR");
        dryIceWindow.setMouldCode("MOULD-001");
        dryIceWindow.setRemark("干冰计划");
        machine.setCleaningWindowList(Collections.singletonList(dryIceWindow));

        context.getMachineScheduleMap().put("K1501", machine);
        context.getInitialMachineScheduleMap().put("K1501", machine);

        ReflectionTestUtils.invokeMethod(handler, "generateMouldChangePlan", context);

        assertEquals(1, context.getMouldChangePlanList().size());
        LhMouldChangePlan plan = context.getMouldChangePlanList().get(0);
        assertEquals(MouldChangeTypeEnum.DRY_ICE.getCode(), plan.getChangeMouldType());
        assertEquals("K1501", plan.getLhMachineCode());
        assertEquals("LR", plan.getLeftRightMould());
        assertEquals("MOULD-001", plan.getMouldCode());
        assertEquals(dryIceWindow.getCleanStartTime(), plan.getPlanDate());
        assertEquals(dryIceWindow.getCleanStartTime(), plan.getChangeTime());
        assertEquals("干冰计划", plan.getRemark());
    }

    /**
     * 清洗与正规换模实际重叠时，只生成正规换模计划，不再重复生成干冰清洗计划。
     */
    @Test
    void generateMouldChangePlan_shouldSkipCleaningPlanWhenRegularMouldChangeOverlaps() {
        ResultValidationHandler handler = new ResultValidationHandler();
        LhScheduleContext context = newContext();
        prepareInitialMachine(context, "K1311", "MAT-ONLINE", "当前在机物料");
        MachineCleaningWindowDTO cleaningWindow = buildCleaningWindow(
                "K1311", CleaningTypeEnum.DRY_ICE.getCode(), dateTime(2026, 4, 17, 8, 0), 4101L);
        context.getMachineScheduleMap().get("K1311")
                .setCleaningWindowList(Collections.singletonList(cleaningWindow));
        LhScheduleResult changeResult = buildChangeResult("K1311", "MAT-AFTER", "换模后物料",
                dateTime(2026, 4, 17, 7, 0), dateTime(2026, 4, 17, 15, 0));
        context.getScheduleResultList().add(changeResult);

        ReflectionTestUtils.invokeMethod(handler, "generateMouldChangePlan", context);

        assertEquals(1, context.getMouldChangePlanList().size());
        assertEquals(MouldChangeTypeEnum.REGULAR.getCode(),
                context.getMouldChangePlanList().get(0).getChangeMouldType());
    }

    /**
     * 三天内收尾优先于换模重叠：不生成清洗计划，收尾班次写喷砂清洗+收尾，日期回填最终收尾日零点。
     */
    @Test
    void finalCleaningDisposition_shouldPreferEndingAndFillFinalEndingDate() {
        ResultValidationHandler handler = new ResultValidationHandler();
        LhScheduleContext context = newContext();
        prepareInitialMachine(context, "K1101", "MAT-ONLINE", "换模前物料");
        LhMachineOnlineInfo onlineInfo = new LhMachineOnlineInfo();
        onlineInfo.setLhCode("K1101");
        onlineInfo.setMaterialCode("MAT-ONLINE");
        onlineInfo.setProductStatus("S");
        context.getMachineOnlineInfoMap().put("K1101", onlineInfo);
        MachineCleaningWindowDTO cleaningWindow = buildCleaningWindow(
                "K1101", CleaningTypeEnum.SAND_BLAST.getCode(), dateTime(2026, 4, 20, 8, 0), 4201L);
        context.getMachineScheduleMap().get("K1101")
                .setCleaningWindowList(Collections.singletonList(cleaningWindow));
        context.getCleaningScheduleDateFillList().add(buildCleaningFillItem(
                4201L, "K1101", CleaningTypeEnum.SAND_BLAST.getCode(), dateTime(2026, 4, 20, 8, 0)));

        LhScheduleResult endingResult = buildChangeResult("K1101", "MAT-ENDING", "收尾物料",
                dateTime(2026, 4, 20, 6, 0), dateTime(2026, 4, 22, 6, 53));
        endingResult.setProductStatus("S");
        endingResult.setMouldSurplusQty(110);
        endingResult.setStandardCapacity(54);
        endingResult.setClass1PlanQty(10);
        context.getScheduleResultList().add(endingResult);

        ReflectionTestUtils.invokeMethod(handler, "finalizeCleaningDisposition", context);
        ReflectionTestUtils.invokeMethod(handler, "generateMouldChangePlan", context);

        assertEquals(1, context.getMouldChangePlanList().size(), "只保留正规换模计划");
        assertEquals(MouldChangeTypeEnum.REGULAR.getCode(),
                context.getMouldChangePlanList().get(0).getChangeMouldType());
        assertTrue(endingResult.getClass1Analysis().contains("喷砂清洗+收尾"));
        assertEquals(date(2026, 4, 22), context.getCleaningScheduleDateFillList().get(0).getScheduleDate());
        assertEquals("收尾未安排清洗", context.getCleaningScheduleDateFillList().get(0).getFillReason());
    }

    /**
     * 独立清洗保持原行为：生成清洗交替计划，并将设备停机排程日期归零到实际清洗日。
     */
    @Test
    void finalCleaningDisposition_shouldKeepStandaloneCleaningPlanAndClearScheduleTime() {
        ResultValidationHandler handler = new ResultValidationHandler();
        LhScheduleContext context = newContext();
        prepareInitialMachine(context, "K1201", "MAT-ONLINE", "当前在机物料");
        MachineCleaningWindowDTO cleaningWindow = buildCleaningWindow(
                "K1201", CleaningTypeEnum.DRY_ICE.getCode(), dateTime(2026, 4, 17, 14, 0), 4301L);
        context.getMachineScheduleMap().get("K1201")
                .setCleaningWindowList(Collections.singletonList(cleaningWindow));
        context.getCleaningScheduleDateFillList().add(buildCleaningFillItem(
                4301L, "K1201", CleaningTypeEnum.DRY_ICE.getCode(), dateTime(2026, 4, 17, 14, 0)));

        ReflectionTestUtils.invokeMethod(handler, "finalizeCleaningDisposition", context);
        ReflectionTestUtils.invokeMethod(handler, "generateMouldChangePlan", context);

        assertEquals(1, context.getMouldChangePlanList().size());
        assertEquals(MouldChangeTypeEnum.DRY_ICE.getCode(),
                context.getMouldChangePlanList().get(0).getChangeMouldType());
        assertEquals(date(2026, 4, 17), context.getCleaningScheduleDateFillList().get(0).getScheduleDate());
        assertEquals("独立清洗", context.getCleaningScheduleDateFillList().get(0).getFillReason());
    }

    @Test
    void generateMouldChangePlan_shouldResolveCleaningLeftRightMouldByMachineCodeSuffix() {
        ResultValidationHandler handler = new ResultValidationHandler();
        LhScheduleContext context = newContext();
        context.setMachineScheduleMap(new LinkedHashMap<>());
        context.setInitialMachineScheduleMap(new LinkedHashMap<>());
        MachineScheduleDTO machine = new MachineScheduleDTO();
        machine.setMachineCode("K1501L");
        machine.setMachineName("华澳左模");
        machine.setCurrentMaterialCode("MAT-ONLINE");
        machine.setCurrentMaterialDesc("当前在机物料");

        MachineCleaningWindowDTO dryIceWindow = new MachineCleaningWindowDTO();
        dryIceWindow.setLhCode("K1501L");
        dryIceWindow.setCleanType(CleaningTypeEnum.DRY_ICE.getCode());
        dryIceWindow.setCleanStartTime(dateTime(2026, 4, 17, 8, 0));
        dryIceWindow.setCleanEndTime(dateTime(2026, 4, 17, 11, 0));
        // 清洗计划原始值为 LR，但单模机台应按编码后缀赋值 L
        dryIceWindow.setLeftRightMould("LR");
        dryIceWindow.setMouldCode("MOULD-001");
        dryIceWindow.setRemark("单模干冰计划");
        machine.setCleaningWindowList(Collections.singletonList(dryIceWindow));

        context.getMachineScheduleMap().put("K1501L", machine);
        context.getInitialMachineScheduleMap().put("K1501L", machine);

        ReflectionTestUtils.invokeMethod(handler, "generateMouldChangePlan", context);

        assertEquals(1, context.getMouldChangePlanList().size());
        LhMouldChangePlan plan = context.getMouldChangePlanList().get(0);
        // 单模机台 K1501L 应赋值 L，而不是清洗计划原始值 LR
        assertEquals("L", plan.getLeftRightMould());
    }

    @Test
    void generateMouldChangePlan_shouldResolveCleaningWindowMaterialByCleaningTime() {
        ResultValidationHandler handler = new ResultValidationHandler();
        LhScheduleContext context = newContext();
        context.setMachineScheduleMap(new LinkedHashMap<>());
        context.setInitialMachineScheduleMap(new LinkedHashMap<>());

        MachineScheduleDTO machine = new MachineScheduleDTO();
        machine.setMachineCode("K1501");
        machine.setMachineName("华澳");
        machine.setCurrentMaterialCode("MAT-FINAL");
        machine.setCurrentMaterialDesc("排程结束物料");
        MachineScheduleDTO initialMachine = new MachineScheduleDTO();
        initialMachine.setMachineCode("K1501");
        initialMachine.setCurrentMaterialCode("MAT-ONLINE");
        initialMachine.setCurrentMaterialDesc("MES在机物料");

        MachineCleaningWindowDTO sandBlastWindow = new MachineCleaningWindowDTO();
        sandBlastWindow.setLhCode("K1501");
        sandBlastWindow.setCleanType(CleaningTypeEnum.SAND_BLAST.getCode());
        sandBlastWindow.setCleanStartTime(dateTime(2026, 4, 17, 8, 0));
        sandBlastWindow.setCleanEndTime(dateTime(2026, 4, 17, 20, 0));
        sandBlastWindow.setMouldCode("MOULD-001");
        machine.setCleaningWindowList(Collections.singletonList(sandBlastWindow));

        context.getMachineScheduleMap().put("K1501", machine);
        context.getInitialMachineScheduleMap().put("K1501", initialMachine);
        context.getScheduleResultList().add(buildChangeResult("K1501", "MAT-A", "物料A",
                dateTime(2026, 4, 17, 6, 0), dateTime(2026, 4, 17, 7, 0)));

        ReflectionTestUtils.invokeMethod(handler, "generateMouldChangePlan", context);

        assertEquals(2, context.getMouldChangePlanList().size());
        LhMouldChangePlan cleaningPlan = context.getMouldChangePlanList().get(1);
        assertEquals("MAT-A", cleaningPlan.getBeforeMaterialCode());
        assertEquals("物料A", cleaningPlan.getBeforeMaterialDesc());
        assertEquals("MAT-A", cleaningPlan.getAfterMaterialCode());
        assertEquals("物料A", cleaningPlan.getAfterMaterialDesc());
    }

    @Test
    void validateManualSundaySandBlastThreshold_shouldOnlyWarnWhenAlternatePlanCountReachesThreshold() throws Exception {
        ResultValidationHandler handler = new ResultValidationHandler();
        LhScheduleContext context = newContext();
        context.setScheduleTargetDate(date(2026, 4, 19));
        context.setMachineScheduleMap(new LinkedHashMap<>());
        context.setInitialMachineScheduleMap(new LinkedHashMap<>());
        context.getLhParamsMap().put(LhScheduleParamConstant.SAND_BLAST_SKIP_SUNDAY_ENABLED, "1");
        context.getLhParamsMap().put(LhScheduleParamConstant.SAND_BLAST_ALLOW_SUNDAY_MANUAL_ENABLED, "1");
        context.getLhParamsMap().put(LhScheduleParamConstant.SAND_BLAST_SUNDAY_MIN_ALTERNATE_PLAN_COUNT, "1");

        MachineScheduleDTO machine = new MachineScheduleDTO();
        machine.setMachineCode("K1501");
        machine.setMachineName("华澳");
        MachineCleaningWindowDTO sandBlastWindow = new MachineCleaningWindowDTO();
        sandBlastWindow.setLhCode("K1501");
        sandBlastWindow.setCleanType(CleaningTypeEnum.SAND_BLAST.getCode());
        sandBlastWindow.setDataSource("0");
        sandBlastWindow.setCleanStartTime(dateTime(2026, 4, 19, 8, 0));
        sandBlastWindow.setCleanEndTime(dateTime(2026, 4, 19, 20, 0));
        machine.setCleaningWindowList(Collections.singletonList(sandBlastWindow));
        context.getMachineScheduleMap().put("K1501", machine);
        context.getInitialMachineScheduleMap().put("K1501", machine);
        context.getScheduleResultList().add(buildChangeResult("K1501", "MAT-A", "物料A",
                dateTime(2026, 4, 19, 6, 0), dateTime(2026, 4, 19, 7, 0)));

        ReflectionTestUtils.invokeMethod(handler, "generateMouldChangePlan", context);

        invokeManualSundaySandBlastValidationWithoutException(handler, context);
        assertEquals(2, context.getMouldChangePlanList().size());
        assertEquals(1, context.getMouldChangePlanList().stream()
                .filter(plan -> MouldChangeTypeEnum.REGULAR.getCode().equals(plan.getChangeMouldType()))
                .count());
        assertEquals(1, context.getMouldChangePlanList().stream()
                .filter(plan -> MouldChangeTypeEnum.SAND_BLAST.getCode().equals(plan.getChangeMouldType()))
                .count());
    }

    @Test
    void validateManualSundaySandBlastThreshold_shouldExcludeCurrentCleaningPlanFromCount() throws Exception {
        ResultValidationHandler handler = new ResultValidationHandler();
        LhScheduleContext context = newContext();
        context.setScheduleTargetDate(date(2026, 4, 19));
        context.setMachineScheduleMap(new LinkedHashMap<>());
        context.setInitialMachineScheduleMap(new LinkedHashMap<>());
        context.getLhParamsMap().put(LhScheduleParamConstant.SAND_BLAST_SKIP_SUNDAY_ENABLED, "1");
        context.getLhParamsMap().put(LhScheduleParamConstant.SAND_BLAST_ALLOW_SUNDAY_MANUAL_ENABLED, "1");
        context.getLhParamsMap().put(LhScheduleParamConstant.SAND_BLAST_SUNDAY_MIN_ALTERNATE_PLAN_COUNT, "2");

        MachineScheduleDTO machine = new MachineScheduleDTO();
        machine.setMachineCode("K1501");
        machine.setMachineName("华澳");
        MachineCleaningWindowDTO sandBlastWindow = new MachineCleaningWindowDTO();
        sandBlastWindow.setLhCode("K1501");
        sandBlastWindow.setCleanType(CleaningTypeEnum.SAND_BLAST.getCode());
        sandBlastWindow.setDataSource("0");
        sandBlastWindow.setCleanStartTime(dateTime(2026, 4, 19, 8, 0));
        sandBlastWindow.setCleanEndTime(dateTime(2026, 4, 19, 20, 0));
        machine.setCleaningWindowList(Collections.singletonList(sandBlastWindow));
        context.getMachineScheduleMap().put("K1501", machine);
        context.getInitialMachineScheduleMap().put("K1501", machine);
        context.getScheduleResultList().add(buildChangeResult("K1501", "MAT-A", "物料A",
                dateTime(2026, 4, 19, 6, 0), dateTime(2026, 4, 19, 7, 0)));

        ReflectionTestUtils.invokeMethod(handler, "generateMouldChangePlan", context);

        invokeManualSundaySandBlastValidationWithoutException(handler, context);
        assertEquals(2, context.getMouldChangePlanList().size());
    }

    private LhScheduleContext newContext() {
        LhScheduleContext context = new LhScheduleContext();
        context.setFactoryCode("116");
        context.setBatchNo("LHPC20260417003");
        context.setScheduleTargetDate(date(2026, 4, 17));
        return context;
    }

    private SkuScheduleDTO newSku(String materialCode) {
        SkuScheduleDTO sku = new SkuScheduleDTO();
        sku.setMaterialCode(materialCode);
        return sku;
    }

    /**
     * 准备生成模具交替计划所需的机台初始前物料快照。
     *
     * @param context 排程上下文
     * @param machineCode 机台编码
     * @param materialCode 前物料编码
     * @param materialDesc 前物料描述
     */
    private void prepareInitialMachine(LhScheduleContext context, String machineCode,
                                       String materialCode, String materialDesc) {
        context.setMachineScheduleMap(new LinkedHashMap<>());
        context.setInitialMachineScheduleMap(new LinkedHashMap<>());
        MachineScheduleDTO machine = new MachineScheduleDTO();
        machine.setMachineCode(machineCode);
        machine.setCurrentMaterialCode(materialCode);
        machine.setCurrentMaterialDesc(materialDesc);
        context.getMachineScheduleMap().put(machineCode, machine);
        context.getInitialMachineScheduleMap().put(machineCode, machine);
    }

    /**
     * 登记测试所需的续作降模前物料 SKU 及其本次排程剩余账本。
     *
     * @param context 排程上下文
     * @param machineCode 机台编码
     * @param materialCode 降模前物料编码
     * @param surplusQty 排程前已计算的硫化余量
     * @param remainingQty 本次排程全部入口扣减后的剩余量
     */
    private void registerReducedContinuationSnapshot(LhScheduleContext context, String machineCode,
                                                     String materialCode, int surplusQty, int remainingQty) {
        SkuScheduleDTO beforeSku = new SkuScheduleDTO();
        beforeSku.setMaterialCode(materialCode);
        beforeSku.setSurplusQty(surplusQty);
        context.getReducedContinuationMachineBeforeSkuMap()
                .computeIfAbsent(machineCode, key -> new LinkedHashMap<>())
                .put(materialCode, beforeSku);
        String skuKey = MonthPlanDateResolver.buildMaterialStatusKey(materialCode, beforeSku.getProductStatus());
        context.getSkuProductionRemainingQtyMap().put(skuKey, remainingQty);
    }

    /**
     * 构造带单个清洗窗口的机台。
     *
     * @param machineCode 机台编码
     * @param materialCode 清洗时点前物料编码
     * @param cleaningType 清洗类型
     * @param cleanStartTime 清洗开始时间
     * @return 清洗机台
     */
    private MachineScheduleDTO buildCleaningMachine(String machineCode, String materialCode,
                                                    String cleaningType, Date cleanStartTime) {
        MachineScheduleDTO machine = new MachineScheduleDTO();
        machine.setMachineCode(machineCode);
        machine.setMachineName("华澳");
        machine.setCurrentMaterialCode(materialCode);
        machine.setCurrentMaterialDesc("清洗前物料");
        MachineCleaningWindowDTO cleaningWindow = new MachineCleaningWindowDTO();
        cleaningWindow.setLhCode(machineCode);
        cleaningWindow.setCleanType(cleaningType);
        cleaningWindow.setCleanStartTime(cleanStartTime);
        cleaningWindow.setCleanEndTime(new Date(cleanStartTime.getTime() + 3 * 60 * 60 * 1000L));
        cleaningWindow.setMouldCode("MOULD-" + machineCode);
        machine.setCleaningWindowList(Collections.singletonList(cleaningWindow));
        return machine;
    }

    /**
     * 构造带来源设备停机计划主键的清洗窗口。
     */
    private MachineCleaningWindowDTO buildCleaningWindow(String machineCode, String cleanType,
                                                          Date cleanStartTime, Long sourcePlanId) {
        MachineCleaningWindowDTO cleaningWindow = new MachineCleaningWindowDTO();
        cleaningWindow.setSourcePlanId(sourcePlanId);
        cleaningWindow.setLhCode(machineCode);
        cleaningWindow.setCleanType(cleanType);
        cleaningWindow.setCleanStartTime(cleanStartTime);
        cleaningWindow.setCleanEndTime(new Date(cleanStartTime.getTime() + 3 * 60 * 60 * 1000L));
        cleaningWindow.setReadyTime(cleaningWindow.getCleanEndTime());
        cleaningWindow.setMouldCode("MOULD-" + machineCode);
        return cleaningWindow;
    }

    /**
     * 构造清洗设备停机计划排程日期回填项。
     */
    private CleaningScheduleDateFillItem buildCleaningFillItem(Long planId, String machineCode,
                                                                String cleanType, Date scheduleDate) {
        CleaningScheduleDateFillItem fillItem = new CleaningScheduleDateFillItem();
        fillItem.setPlanId(planId);
        fillItem.setMachineCode(machineCode);
        fillItem.setCleanType(cleanType);
        fillItem.setScheduleDate(scheduleDate);
        fillItem.setFillReason("清洗成功");
        return fillItem;
    }

    private void invokeManualSundaySandBlastValidationWithoutException(ResultValidationHandler handler, LhScheduleContext context)
            throws Exception {
        Method method = ResultValidationHandler.class.getDeclaredMethod(
                "validateManualSundaySandBlastThreshold", LhScheduleContext.class);
        method.setAccessible(true);
        method.invoke(handler, context);
    }

    private LhScheduleResult buildChangeResult(String machineCode, String materialCode, String materialDesc,
                                               Date startTime, Date endTime) {
        LhScheduleResult result = new LhScheduleResult();
        result.setFactoryCode("116");
        result.setBatchNo("LHPC20260417003");
        result.setLhMachineCode(machineCode);
        result.setLhMachineName("华澳");
        result.setScheduleType("02");
        result.setIsChangeMould("1");
        result.setLeftRightMould("R");
        result.setMaterialCode(materialCode);
        result.setMaterialDesc(materialDesc);
        result.setMouldCode("HM20231203902");
        result.setClass1StartTime(startTime);
        result.setSpecEndTime(endTime);
        result.setDailyPlanQty(10);
        return result;
    }

    private static Date date(int year, int month, int day) {
        Calendar calendar = Calendar.getInstance();
        calendar.clear();
        calendar.set(Calendar.YEAR, year);
        calendar.set(Calendar.MONTH, month - 1);
        calendar.set(Calendar.DAY_OF_MONTH, day);
        return calendar.getTime();
    }

    private static Date dateTime(int year, int month, int day, int hour, int minute) {
        Calendar calendar = Calendar.getInstance();
        calendar.clear();
        calendar.set(Calendar.YEAR, year);
        calendar.set(Calendar.MONTH, month - 1);
        calendar.set(Calendar.DAY_OF_MONTH, day);
        calendar.set(Calendar.HOUR_OF_DAY, hour);
        calendar.set(Calendar.MINUTE, minute);
        return calendar.getTime();
    }
}
