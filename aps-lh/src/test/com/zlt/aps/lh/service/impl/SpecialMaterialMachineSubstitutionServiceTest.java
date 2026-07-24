package com.zlt.aps.lh.service.impl;

import com.zlt.aps.lh.api.domain.dto.MachineScheduleDTO;
import com.zlt.aps.lh.api.domain.dto.SkuDailyPlanQuotaDTO;
import com.zlt.aps.lh.api.domain.dto.SkuScheduleDTO;
import com.zlt.aps.lh.api.domain.entity.LhMouldChangePlan;
import com.zlt.aps.lh.api.domain.entity.LhPrecisionPlan;
import com.zlt.aps.lh.api.domain.entity.LhScheduleResult;
import com.zlt.aps.lh.api.domain.vo.LhShiftConfigVO;
import com.zlt.aps.lh.api.enums.MachineStopTypeEnum;
import com.zlt.aps.lh.api.enums.ShiftEnum;
import com.zlt.aps.lh.api.enums.SubstitutionTypeEnum;
import com.zlt.aps.lh.component.MonthPlanDateResolver;
import com.zlt.aps.lh.component.TargetScheduleQtyResolver;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.engine.strategy.IMachineMatchStrategy;
import com.zlt.aps.lh.engine.strategy.ITypeBlockProductionStrategy;
import com.zlt.aps.lh.engine.strategy.impl.NewSpecProductionStrategy;
import com.zlt.aps.lh.engine.strategy.support.DailyMachineExpansionPlanner;
import com.zlt.aps.lh.engine.strategy.support.SpecialMaterialSubstitutionRecord;
import com.zlt.aps.lh.engine.strategy.support.SpecifiedMachineMatchResult;
import com.zlt.aps.lh.engine.strategy.support.SpecifiedMachineScheduleResult;
import com.zlt.aps.lh.handler.ResultValidationHandler;
import com.zlt.aps.mdm.api.domain.entity.MdmDevicePlanShut;
import com.zlt.aps.mp.api.domain.entity.FactoryMonthPlanProductionFinalResult;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 特殊材料硫化机置换核心规则回归测试。
 *
 * @author APS
 */
public class SpecialMaterialMachineSubstitutionServiceTest {

    /**
     * 验证特殊材料不得早于月计划首个有量日期起排。
     */
    @Test
    public void resolveFirstPositivePlanDate_shouldReturnFirstPositiveMonthPlanDate() {
        SpecialMaterialMachineSubstitutionService service =
                new SpecialMaterialMachineSubstitutionService();
        LhScheduleContext context = new LhScheduleContext();
        LocalDate targetDate = LocalDate.of(2026, 7, 23);
        context.setScheduleDate(toDate(targetDate, 0));
        context.setWindowEndDate(toDate(targetDate.plusDays(2), 23));

        SkuScheduleDTO sku = new SkuScheduleDTO();
        sku.setMaterialCode("SPECIAL-T1");
        sku.setProductStatus("S");
        FactoryMonthPlanProductionFinalResult plan =
                new FactoryMonthPlanProductionFinalResult();
        plan.setMaterialCode(sku.getMaterialCode());
        plan.setProductStatus(sku.getProductStatus());
        plan.setYear(2026);
        plan.setMonth(7);
        plan.setDay23(0);
        plan.setDay24(12);
        context.getLoadedMonthPlanList().add(plan);

        LocalDate firstPlanDate = ReflectionTestUtils.invokeMethod(
                service, "resolveFirstPositivePlanDate", context, sku);

        Assertions.assertEquals(targetDate.plusDays(1), firstPlanDate);
    }

    /**
     * 验证第三优先级只读取精度/保养计划，05计划性维修不得进入维保层。
     */
    @Test
    public void selectMaintenanceCandidate_shouldExcludePlannedRepairType05() {
        SpecialMaterialMachineSubstitutionService service =
                new SpecialMaterialMachineSubstitutionService();
        LhScheduleContext context = new LhScheduleContext();
        LocalDate targetDate = LocalDate.of(2026, 7, 23);
        LhScheduleResult candidate = new LhScheduleResult();
        candidate.setLhMachineCode("K1201");
        List<LhScheduleResult> candidates = Collections.singletonList(candidate);

        MdmDevicePlanShut plannedRepair = new MdmDevicePlanShut();
        plannedRepair.setMachineCode(candidate.getLhMachineCode());
        plannedRepair.setMachineStopType(MachineStopTypeEnum.PLANNED_REPAIR.getCode());
        plannedRepair.setBeginDate(toDate(targetDate.plusDays(1), 8));
        context.getDevicePlanShutList().add(plannedRepair);

        LhScheduleResult repairOnlyCandidate = ReflectionTestUtils.invokeMethod(
                service, "selectMaintenanceCandidate", context, candidates, targetDate);
        Assertions.assertNull(repairOnlyCandidate, "05计划性维修不得命中第三优先级");

        LhPrecisionPlan maintenancePlan = new LhPrecisionPlan();
        maintenancePlan.setPlanDate(toDate(targetDate.plusDays(29), 8));
        context.getMaintenancePlanMap().put(candidate.getLhMachineCode(), maintenancePlan);
        LhScheduleResult maintenanceCandidate = ReflectionTestUtils.invokeMethod(
                service, "selectMaintenanceCandidate", context, candidates, targetDate);
        Assertions.assertSame(candidate, maintenanceCandidate);

        // 目标日计作第1天，T+30 已超出“30天内”包含式窗口。
        maintenancePlan.setPlanDate(toDate(targetDate.plusDays(30), 8));
        LhScheduleResult outOfWindowCandidate = ReflectionTestUtils.invokeMethod(
                service, "selectMaintenanceCandidate", context, candidates, targetDate);
        Assertions.assertNull(outOfWindowCandidate);
    }

    /**
     * 验证班次内下机只截断尾量，并把截断量恢复到剩余量、dayN账本和未排结果。
     */
    @Test
    public void truncateContinuationResults_shouldRetainCompletedCyclesAndRestoreLedgers() {
        SpecialMaterialMachineSubstitutionService service =
                new SpecialMaterialMachineSubstitutionService();
        TargetScheduleQtyResolver targetScheduleQtyResolver = new TargetScheduleQtyResolver();
        ReflectionTestUtils.setField(
                service, "targetScheduleQtyResolver", targetScheduleQtyResolver);
        LhScheduleContext context = new LhScheduleContext();
        LocalDate productionDate = LocalDate.of(2026, 7, 23);
        context.setScheduleTargetDate(toDate(productionDate, 0));
        context.setFactoryCode("116");
        context.setBatchNo("SPECIAL-SUBSTITUTION-TEST");
        context.getScheduleWindowShifts().add(
                buildShift(1, productionDate, 8, 16));

        SkuScheduleDTO sourceSku = new SkuScheduleDTO();
        sourceSku.setMaterialCode("CONT-SKU");
        sourceSku.setProductStatus("S");
        sourceSku.setLhTimeSeconds(3600);
        sourceSku.setMouldQty(1);
        sourceSku.setEmbryoStock(-1);
        SkuDailyPlanQuotaDTO quota = new SkuDailyPlanQuotaDTO();
        quota.setMaterialCode(sourceSku.getMaterialCode());
        quota.setProductionDate(productionDate);
        quota.setDayPlanQty(8);
        quota.setScheduledQty(8);
        quota.setRemainingQty(0);
        quota.setActualQty(8);
        sourceSku.setDailyPlanQuotaMap(
                new LinkedHashMap<LocalDate, SkuDailyPlanQuotaDTO>(2));
        sourceSku.getDailyPlanQuotaMap().put(productionDate, quota);
        context.getSkuProductionRemainingQtyMap().put(
                MonthPlanDateResolver.buildMaterialStatusKey(
                        sourceSku.getMaterialCode(), sourceSku.getProductStatus()), 0);

        LhScheduleResult continuationResult = new LhScheduleResult();
        continuationResult.setLhMachineCode("K1201");
        continuationResult.setMaterialCode(sourceSku.getMaterialCode());
        continuationResult.setProductStatus(sourceSku.getProductStatus());
        continuationResult.setClass1PlanQty(8);
        continuationResult.setClass1StartTime(toDate(productionDate, 8));
        continuationResult.setClass1EndTime(toDate(productionDate, 16));
        continuationResult.setDailyPlanQty(8);

        Map<LhScheduleResult, SkuScheduleDTO> sourceSkuMap =
                new IdentityHashMap<LhScheduleResult, SkuScheduleDTO>(2);
        sourceSkuMap.put(continuationResult, sourceSku);
        Map<Integer, Integer> removedQtyByShift =
                new LinkedHashMap<Integer, Integer>(8);
        Map<SkuScheduleDTO, Map<LocalDate, Integer>> removedQtyBySkuDate =
                new IdentityHashMap<SkuScheduleDTO, Map<LocalDate, Integer>>(2);

        @SuppressWarnings("unchecked")
        Map<SkuScheduleDTO, Integer> removedQtyBySku = ReflectionTestUtils.invokeMethod(
                service, "truncateContinuationResults",
                context, Collections.singletonList(continuationResult), sourceSkuMap,
                toDate(productionDate, 12), removedQtyByShift,
                removedQtyBySkuDate, false);

        Assertions.assertEquals(Integer.valueOf(4), continuationResult.getClass1PlanQty());
        Assertions.assertEquals(Integer.valueOf(4), removedQtyBySku.get(sourceSku));

        ReflectionTestUtils.invokeMethod(
                service, "restoreTruncatedSkuQtyAndWriteUnscheduled",
                context, "K1201", removedQtyBySku, removedQtyBySkuDate);

        Assertions.assertEquals(Integer.valueOf(4),
                context.getSkuProductionRemainingQtyMap().get(
                        MonthPlanDateResolver.buildMaterialStatusKey(
                                sourceSku.getMaterialCode(), sourceSku.getProductStatus())));
        Assertions.assertEquals(4, quota.getScheduledQty());
        Assertions.assertEquals(4, quota.getRemainingQty());
        Assertions.assertEquals(4, quota.getActualQty());
        Assertions.assertEquals(1, context.getUnscheduledResultList().size());
        Assertions.assertEquals(Integer.valueOf(4),
                context.getUnscheduledResultList().get(0).getUnscheduledQty());
    }

    /**
     * 验证特殊材料所需置换台数按窗口目标量计算，不固定为1台。
     */
    @Test
    public void resolveSpecialMaterialRequiredMachineCount_shouldAllowMultipleMachines() {
        LhScheduleContext context = new LhScheduleContext();
        LocalDate targetDate = LocalDate.of(2026, 7, 23);
        context.getScheduleWindowShifts().add(buildShift(1, targetDate, 8, 16));
        context.getScheduleWindowShifts().add(buildShift(2, targetDate.plusDays(1), 8, 16));
        SkuScheduleDTO sku = new SkuScheduleDTO();
        sku.setMaterialCode("SPECIAL-MULTI");
        sku.setDailyCapacity(10);

        int requiredMachineCount =
                DailyMachineExpansionPlanner.resolveSpecialMaterialRequiredMachineCount(
                        context, sku, targetDate, 35, 4);

        Assertions.assertEquals(2, requiredMachineCount);
    }

    /**
     * 验证置换接管机台时优先复用换活字块指定机台主链。
     */
    @Test
    public void scheduleSpecialMaterialOnSpecifiedMachine_shouldPreferTypeBlockMainFlow() {
        SpecialMaterialMachineSubstitutionService service =
                new SpecialMaterialMachineSubstitutionService();
        ITypeBlockProductionStrategy typeBlockProductionStrategy =
                Mockito.mock(ITypeBlockProductionStrategy.class);
        ReflectionTestUtils.setField(
                service, "typeBlockProductionStrategy", typeBlockProductionStrategy);
        LhScheduleContext context = new LhScheduleContext();
        MachineScheduleDTO machine = new MachineScheduleDTO();
        machine.setMachineCode("K1201");
        context.getMachineScheduleMap().put(machine.getMachineCode(), machine);
        SkuScheduleDTO specialSku = new SkuScheduleDTO();
        specialSku.setMaterialCode("SPECIAL-TYPE-BLOCK");
        specialSku.setProductStatus("S");
        LhScheduleResult typeBlockResult = new LhScheduleResult();
        typeBlockResult.setLhMachineCode(machine.getMachineCode());
        typeBlockResult.setMaterialCode(specialSku.getMaterialCode());
        typeBlockResult.setProductStatus(specialSku.getProductStatus());
        Date earliestSwitchTime =
                toDate(LocalDate.of(2026, 7, 23), 8);
        Mockito.when(typeBlockProductionStrategy.tryScheduleSpecialMaterialSubstitution(
                        context, machine, specialSku, earliestSwitchTime))
                .thenReturn(SpecifiedMachineScheduleResult.success(
                        typeBlockResult, "02"));

        LhScheduleResult actualResult = ReflectionTestUtils.invokeMethod(
                service, "scheduleSpecialMaterialOnSpecifiedMachine",
                context, specialSku, machine.getMachineCode(), earliestSwitchTime);

        Assertions.assertSame(typeBlockResult, actualResult);
        Assertions.assertTrue(context.getNewSpecSkuList().isEmpty(),
                "置换结束后必须恢复原新增待排列表");
        Assertions.assertNull(context.getSpecialMaterialSpecifiedMachineCode(),
                "置换临时指定机台指令必须清理");
    }

    /**
     * 验证正规换模复用新增主链时只能使用置换指定机台，不能回落其他新增候选。
     */
    @Test
    public void restrictSpecialMaterialSpecifiedMachine_shouldForbidFallbackMachine() {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        IMachineMatchStrategy machineMatchStrategy = Mockito.mock(IMachineMatchStrategy.class);
        LhScheduleContext context = new LhScheduleContext();
        SkuScheduleDTO specialSku = new SkuScheduleDTO();
        specialSku.setMaterialCode("SPECIAL-SPECIFIED");
        specialSku.setProductStatus("S");
        MachineScheduleDTO specifiedMachine = new MachineScheduleDTO();
        specifiedMachine.setMachineCode("K1201");
        MachineScheduleDTO otherNewMachine = new MachineScheduleDTO();
        otherNewMachine.setMachineCode("K1301");

        context.setSpecialMaterialSpecifiedMachineCode(specifiedMachine.getMachineCode());
        context.setSpecialMaterialSpecifiedSkuKey(
                MonthPlanDateResolver.buildMaterialStatusKey(
                        specialSku.getMaterialCode(), specialSku.getProductStatus()));
        Mockito.when(machineMatchStrategy.matchSpecifiedMachine(
                        context, specialSku, specifiedMachine.getMachineCode()))
                .thenReturn(SpecifiedMachineMatchResult.success(specifiedMachine));

        @SuppressWarnings("unchecked")
        List<MachineScheduleDTO> restrictedCandidates = ReflectionTestUtils.invokeMethod(
                strategy, "restrictSpecialMaterialSpecifiedMachine",
                context, specialSku, Collections.singletonList(otherNewMachine),
                machineMatchStrategy);

        Assertions.assertEquals(1, restrictedCandidates.size());
        Assertions.assertSame(specifiedMachine, restrictedCandidates.get(0));
        Assertions.assertFalse(restrictedCandidates.contains(otherNewMachine),
                "指定续作机台成功匹配后不得保留其他新增排产候选");

        Mockito.when(machineMatchStrategy.matchSpecifiedMachine(
                        context, specialSku, specifiedMachine.getMachineCode()))
                .thenReturn(SpecifiedMachineMatchResult.failed("模具状态不满足"));
        @SuppressWarnings("unchecked")
        List<MachineScheduleDTO> failedCandidates = ReflectionTestUtils.invokeMethod(
                strategy, "restrictSpecialMaterialSpecifiedMachine",
                context, specialSku, Collections.singletonList(otherNewMachine),
                machineMatchStrategy);
        Assertions.assertTrue(failedCandidates.isEmpty(),
                "指定机台硬约束失败后必须认定候选失败，禁止回落其他机台");
    }

    /**
     * 验证换模开始虽早于20:00，但执行过程跨入20:00以后时仍必须判为不可行。
     */
    @Test
    public void isWholeChangeExecutionTimeAllowed_shouldRejectExecutionAfterTwentyOClock() {
        SpecialMaterialMachineSubstitutionService service =
                new SpecialMaterialMachineSubstitutionService();
        LhScheduleContext context = new LhScheduleContext();
        LocalDate targetDate = LocalDate.of(2026, 7, 23);

        Boolean crossingForbiddenTime = ReflectionTestUtils.invokeMethod(
                service, "isWholeChangeExecutionTimeAllowed",
                context, toDate(targetDate, 19), 2);
        Boolean endingAtBoundary = ReflectionTestUtils.invokeMethod(
                service, "isWholeChangeExecutionTimeAllowed",
                context, toDate(targetDate, 18), 2);

        Assertions.assertFalse(crossingForbiddenTime);
        Assertions.assertTrue(endingAtBoundary,
                "换模执行在20:00整完成时未落入20:00之后，可继续沿用现有边界口径");
    }

    /**
     * 验证交替计划备注按机台、接管SKU、产品状态和实际换模时间精确匹配。
     */
    @Test
    public void appendSubstitutionRemark_shouldMatchExactScheduleResult() {
        ResultValidationHandler handler = new ResultValidationHandler();
        LhScheduleContext context = new LhScheduleContext();
        Date actualChangeStartTime =
                toDate(LocalDate.of(2026, 7, 24), 8);
        SpecialMaterialSubstitutionRecord record =
                new SpecialMaterialSubstitutionRecord();
        record.setMachineCode("K1201");
        record.setSpecialMaterialCode("SPECIAL-S");
        record.setSpecialProductStatus("S");
        record.setActualChangeStartTime(actualChangeStartTime);
        record.setRemark(SubstitutionTypeEnum.PRECISION_PLAN_SUBSTITUTION
                .buildRemark("K1201"));
        context.getSpecialMaterialSubstitutionRecordList().add(record);

        LhScheduleResult result = new LhScheduleResult();
        result.setLhMachineCode("K1201");
        result.setMaterialCode("SPECIAL-S");
        result.setProductStatus("S");
        LhMouldChangePlan plan = new LhMouldChangePlan();

        ReflectionTestUtils.invokeMethod(
                handler, "appendSubstitutionRemark",
                context, plan, result, actualChangeStartTime);

        Assertions.assertEquals("维保+置换 K1201", plan.getRemark());

        LhScheduleResult otherResult = new LhScheduleResult();
        otherResult.setLhMachineCode("K1201");
        otherResult.setMaterialCode("OTHER-SKU");
        otherResult.setProductStatus("S");
        LhMouldChangePlan otherPlan = new LhMouldChangePlan();
        ReflectionTestUtils.invokeMethod(
                handler, "appendSubstitutionRemark",
                context, otherPlan, otherResult, actualChangeStartTime);
        Assertions.assertNull(otherPlan.getRemark());
    }

    /**
     * 验证候选失败快照能恢复结果、机台和资源运行态。
     */
    @Test
    public void attemptSnapshot_shouldRestoreCandidateSideEffects() {
        LhScheduleContext context = new LhScheduleContext();
        LhScheduleResult continuationResult = new LhScheduleResult();
        continuationResult.setLhMachineCode("K1201");
        continuationResult.setMaterialCode("CONT-SNAPSHOT");
        continuationResult.setClass1PlanQty(8);
        context.getScheduleResultList().add(continuationResult);
        MachineScheduleDTO machine = new MachineScheduleDTO();
        machine.setMachineCode("K1201");
        machine.setCurrentMaterialCode("CONT-SNAPSHOT");
        context.getMachineScheduleMap().put(machine.getMachineCode(), machine);
        context.getCapsuleRuntimeUsageMap().put("K1201", 6);
        context.setPendingFormalNewSpecSkuCount(3);
        context.getEmbryoStockSkuQuotaMap().put("SPECIAL-SNAPSHOT", 12);
        context.getEmbryoStockHardTargetMaterialSet().add("SPECIAL-SNAPSHOT");
        context.getSkuDecrementKeySet().add("DECREMENT-BEFORE");
        context.getDecrementHandledSkuKeySet().add("HANDLED-BEFORE");

        SkuScheduleDTO specialSku = new SkuScheduleDTO();
        specialSku.setMaterialCode("SPECIAL-SNAPSHOT");
        specialSku.setTargetScheduleQty(12);
        specialSku.setDailyPlanQuotaMap(
                new LinkedHashMap<LocalDate, SkuDailyPlanQuotaDTO>(0));
        SpecialMaterialSubstitutionAttemptSnapshot snapshot =
                SpecialMaterialSubstitutionAttemptSnapshot.capture(context, specialSku);

        continuationResult.setClass1PlanQty(0);
        context.getScheduleResultList().clear();
        context.getMachineScheduleMap().get("K1201")
                .setCurrentMaterialCode("SPECIAL-SNAPSHOT");
        context.getCapsuleRuntimeUsageMap().put("K1201", 99);
        context.setPendingFormalNewSpecSkuCount(0);
        context.getEmbryoStockSkuQuotaMap().put("SPECIAL-SNAPSHOT", 99);
        context.getEmbryoStockHardTargetMaterialSet().clear();
        context.getSkuDecrementKeySet().add("DECREMENT-AFTER");
        context.getDecrementHandledSkuKeySet().clear();
        specialSku.setTargetScheduleQty(0);

        // 模拟候选排产失败，恢复必须覆盖排程结果和所有已触达的资源账本。
        snapshot.restore(context, specialSku);

        Assertions.assertEquals(1, context.getScheduleResultList().size());
        Assertions.assertSame(continuationResult, context.getScheduleResultList().get(0));
        Assertions.assertEquals(Integer.valueOf(8), continuationResult.getClass1PlanQty());
        Assertions.assertEquals("CONT-SNAPSHOT",
                context.getMachineScheduleMap().get("K1201").getCurrentMaterialCode());
        Assertions.assertEquals(Integer.valueOf(6),
                context.getCapsuleRuntimeUsageMap().get("K1201"));
        Assertions.assertEquals(3, context.getPendingFormalNewSpecSkuCount());
        Assertions.assertEquals(Integer.valueOf(12),
                context.getEmbryoStockSkuQuotaMap().get("SPECIAL-SNAPSHOT"));
        Assertions.assertEquals(Collections.singleton("SPECIAL-SNAPSHOT"),
                context.getEmbryoStockHardTargetMaterialSet());
        Assertions.assertEquals(Collections.singleton("DECREMENT-BEFORE"),
                context.getSkuDecrementKeySet());
        Assertions.assertEquals(Collections.singleton("HANDLED-BEFORE"),
                context.getDecrementHandledSkuKeySet());
        Assertions.assertEquals(Integer.valueOf(12), specialSku.getTargetScheduleQty());
    }

    /**
     * 构建测试班次。
     *
     * @param shiftIndex 班次索引
     * @param workDate 业务日
     * @param startHour 开始小时
     * @param endHour 结束小时
     * @return 班次配置
     */
    private LhShiftConfigVO buildShift(
            int shiftIndex,
            LocalDate workDate,
            int startHour,
            int endHour) {
        LhShiftConfigVO shift = new LhShiftConfigVO();
        shift.setShiftIndex(shiftIndex);
        shift.setScheduleBaseDate(toDate(workDate, 0));
        shift.setDateOffset(0);
        shift.setShiftType(ShiftEnum.MORNING_SHIFT.getCode());
        shift.setStartTime(String.format("%02d:00", startHour));
        shift.setEndTime(String.format("%02d:00", endHour));
        return shift;
    }

    /**
     * 构造指定业务日期和小时的时间。
     *
     * @param date 业务日期
     * @param hour 小时
     * @return 本地时区时间
     */
    private static Date toDate(LocalDate date, int hour) {
        return Date.from(date.atTime(hour, 0)
                .atZone(ZoneId.systemDefault()).toInstant());
    }
}
