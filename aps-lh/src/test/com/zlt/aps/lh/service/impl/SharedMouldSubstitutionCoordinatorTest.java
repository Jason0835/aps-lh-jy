package com.zlt.aps.lh.service.impl;

import com.zlt.aps.lh.api.domain.dto.MachineScheduleDTO;
import com.zlt.aps.lh.api.domain.dto.SkuDailyPlanQuotaDTO;
import com.zlt.aps.lh.api.domain.dto.SkuScheduleDTO;
import com.zlt.aps.lh.api.domain.entity.LhScheduleResult;
import com.zlt.aps.lh.api.domain.entity.LhUnscheduledResult;
import com.zlt.aps.lh.api.domain.vo.LhShiftConfigVO;
import com.zlt.aps.lh.api.enums.ScheduleTypeEnum;
import com.zlt.aps.lh.api.enums.ShiftEnum;
import com.zlt.aps.lh.component.MonthPlanDateResolver;
import com.zlt.aps.lh.component.TargetScheduleQtyResolver;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.engine.strategy.support.ContinuationCutoverResult;
import com.zlt.aps.lh.engine.strategy.support.ScheduleSubstitutionDirective;
import com.zlt.aps.lh.engine.strategy.support.SharedMouldSubstitutionPlan;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SKU 无模具可用时共用模具联动置换协调器测试。
 *
 * <p>测试聚焦协调器新增的硬门槛、A/B 联动校验和通用快照回滚；B 的换模均衡、晚班禁换模、
 * 20:00 后顺延和每日 15 次上限继续由既有
 * {@code DefaultMouldChangeBalanceStrategyRegressionTest} 覆盖。</p>
 *
 * @author APS
 */
public class SharedMouldSubstitutionCoordinatorTest {

    /**
     * 验证 B 有剩余模具且存在新机台时，A 无换模接管、B 在下机后的合法时间换模重启，
     * 并由一个新物理机台精确承接全部截断尾量。
     */
    @Test
    public void executePlan_shouldTakeOverAndRelocateContinuationAsOneAtomicGroup() {
        SharedMouldSubstitutionCoordinator coordinator =
                new SharedMouldSubstitutionCoordinator();
        ContinuationCutoverService cutoverService =
                Mockito.mock(ContinuationCutoverService.class);
        SpecifiedNewSpecSchedulingService schedulingService =
                Mockito.mock(SpecifiedNewSpecSchedulingService.class);
        ReflectionTestUtils.setField(
                coordinator, "continuationCutoverService", cutoverService);
        ReflectionTestUtils.setField(
                coordinator, "specifiedNewSpecSchedulingService", schedulingService);
        LhScheduleContext context = new LhScheduleContext();
        MachineScheduleDTO sourceMachine = buildMachine("K1201");
        context.getMachineScheduleMap().put(
                sourceMachine.getMachineCode(), sourceMachine);

        Date offlineTime = toDateTime(
                LocalDate.of(2026, 7, 23), 20, 30);
        Date delayedMouldChangeTime = toDateTime(
                LocalDate.of(2026, 7, 24), 6, 0);
        Date delayedProductionTime = toDateTime(
                LocalDate.of(2026, 7, 24), 14, 0);
        SharedMouldSubstitutionPlan plan =
                buildPlan(offlineTime);
        ContinuationCutoverResult cutoverResult =
                new ContinuationCutoverResult();
        cutoverResult.setRemovedQty(4);
        Mockito.when(cutoverService.cutover(
                        context, plan.getContinuationSku(),
                        plan.getOriginalPhysicalMachineCode(), offlineTime))
                .thenReturn(cutoverResult);

        LhScheduleResult targetResult = buildResult(
                "SKU-A", "K1201", "M-SHARED",
                2, offlineTime, "0");
        LhScheduleResult relocationResult = buildResult(
                "SKU-B", "K1301", "M-FREE",
                4, delayedProductionTime, "1");
        relocationResult.setMouldChangeStartTime(
                delayedMouldChangeTime);
        Mockito.when(schedulingService.schedule(
                        Mockito.eq(context),
                        Mockito.any(SkuScheduleDTO.class),
                        Mockito.any(ScheduleSubstitutionDirective.class)))
                .thenAnswer(invocation -> {
                    ScheduleSubstitutionDirective directive =
                            invocation.getArgument(2);
                    if (directive.isTakeoverWithoutMouldChange()) {
                        return Collections.singletonList(targetResult);
                    }
                    Assertions.assertEquals(
                            4, directive.getExactScheduleQty(),
                            "B 主链必须携带与实际截断尾量一致的严格数量上限");
                    return Collections.singletonList(
                            relocationResult);
                });

        ReflectionTestUtils.invokeMethod(
                coordinator, "executePlan", context, plan, false);

        Assertions.assertEquals(4, plan.getRelocatedQty());
        Assertions.assertEquals("K1301", plan.getRelocationMachineCode());
        Assertions.assertEquals(
                delayedMouldChangeTime,
                plan.getRelocationMouldChangeTime(),
                "B 在 20:00 后下机时，协调器必须接受原换模链顺延后的合法换模时间");
        Assertions.assertEquals(
                delayedProductionTime,
                plan.getRelocationProductionStartTime());
        Assertions.assertEquals(
                Collections.singletonList("M-FREE"),
                plan.getRelocationMouldCodeMap().get("K1301"));
        Assertions.assertEquals(
                ScheduleTypeEnum.NEW_SPEC.getCode(),
                targetResult.getScheduleType(),
                "A 接管结果仍属于新增排产结果");
        Assertions.assertEquals(
                "0", targetResult.getIsChangeMould(),
                "A 必须直接继承原机台和共用模具，不得生成换模");
        Assertions.assertEquals(
                "0", targetResult.getIsTypeBlock(),
                "A 接管不得被误标记为换活字块");
        Assertions.assertEquals(
                "M-SHARED", targetResult.getMouldCode(),
                "A 必须继承 B 原续作机台当前实际使用的精确共用模具");
    }

    /**
     * 验证 B 有剩余模具但找不到新机台时，正式联动必须整体回滚；
     * A 临时结果和原机台接管状态均不得残留。
     */
    @Test
    public void commitPlan_shouldRollbackWhenContinuationHasNoAvailableMachine() {
        SharedMouldSubstitutionCoordinator coordinator =
                new SharedMouldSubstitutionCoordinator();
        ContinuationCutoverService cutoverService =
                Mockito.mock(ContinuationCutoverService.class);
        SpecifiedNewSpecSchedulingService schedulingService =
                Mockito.mock(SpecifiedNewSpecSchedulingService.class);
        ReflectionTestUtils.setField(
                coordinator, "continuationCutoverService", cutoverService);
        ReflectionTestUtils.setField(
                coordinator, "specifiedNewSpecSchedulingService", schedulingService);
        LhScheduleContext context = new LhScheduleContext();
        Date originalEndTime = toDateTime(
                LocalDate.of(2026, 7, 25), 8, 0);
        MachineScheduleDTO sourceMachine = buildMachine("K1201");
        sourceMachine.setEstimatedEndTime(originalEndTime);
        sourceMachine.setNextMaterialCode("SKU-B");
        context.getMachineScheduleMap().put(
                sourceMachine.getMachineCode(), sourceMachine);
        LhScheduleResult originalContinuationResult = buildResult(
                "SKU-B", "K1201", "M-SHARED",
                8, toDateTime(LocalDate.of(2026, 7, 23), 8, 0), "0");
        originalContinuationResult.setScheduleType(
                ScheduleTypeEnum.CONTINUOUS.getCode());
        context.getScheduleResultList().add(
                originalContinuationResult);

        Date offlineTime = toDateTime(
                LocalDate.of(2026, 7, 23), 16, 0);
        SharedMouldSubstitutionPlan plan =
                buildPlan(offlineTime);
        plan.setRelocatedQty(4);
        plan.setTargetTakeoverTime(offlineTime);
        ContinuationCutoverResult cutoverResult =
                new ContinuationCutoverResult();
        cutoverResult.setRemovedQty(4);
        Mockito.when(cutoverService.cutover(
                        context, plan.getContinuationSku(),
                        plan.getOriginalPhysicalMachineCode(), offlineTime))
                .thenReturn(cutoverResult);
        LhScheduleResult targetResult = buildResult(
                "SKU-A", "K1201", "M-SHARED",
                2, offlineTime, "0");
        Mockito.when(schedulingService.schedule(
                        Mockito.eq(context),
                        Mockito.any(SkuScheduleDTO.class),
                        Mockito.any(ScheduleSubstitutionDirective.class)))
                .thenAnswer(invocation -> {
                    ScheduleSubstitutionDirective directive =
                            invocation.getArgument(2);
                    if (directive.isTakeoverWithoutMouldChange()) {
                        context.getScheduleResultList().add(targetResult);
                        return Collections.singletonList(targetResult);
                    }
                    return Collections.emptyList();
                });

        Boolean committed = ReflectionTestUtils.invokeMethod(
                coordinator, "commitPlan", context, plan,
                new LhUnscheduledResult());

        Assertions.assertFalse(committed);
        Assertions.assertEquals(
                Collections.singletonList(originalContinuationResult),
                context.getScheduleResultList(),
                "B 迁移失败后不得保留 A 的临时接管结果");
        MachineScheduleDTO restoredSourceMachine =
                context.getMachineScheduleMap().get("K1201");
        Assertions.assertEquals(
                originalEndTime,
                restoredSourceMachine.getEstimatedEndTime());
        Assertions.assertEquals(
                "SKU-B", restoredSourceMachine.getNextMaterialCode());
        Assertions.assertTrue(
                context.getSharedMouldSubstitutionRecordList().isEmpty());
    }

    /**
     * 验证 B 无剩余模具时，候选在进入预演前即被拒绝，并把完整 A/B、共模、原机台和时间字段
     * 写入批次过程日志；尚未发生的迁移机台、换模和重新开产必须明确标记为“未生成”。
     */
    @Test
    public void appendCandidateFailureProcessLog_shouldKeepCompleteAuditContext() {
        SharedMouldSubstitutionCoordinator coordinator =
                new SharedMouldSubstitutionCoordinator();
        LhScheduleContext context = new LhScheduleContext();
        context.setBatchNo("LHPC-TEST-SHARED-MOULD-FAILURE");
        Date takeoverTargetTime = toDateTime(
                LocalDate.of(2026, 7, 30), 6, 0);

        ReflectionTestUtils.invokeMethod(
                coordinator, "appendCandidateFailureProcessLog",
                context, buildSku("SKU-A"), buildSku("SKU-B"),
                "K1201", Collections.singleton("M-SHARED"),
                takeoverTargetTime, "B 无剩余空闲有效模具");

        Assertions.assertEquals(1, context.getScheduleLogList().size());
        Assertions.assertEquals(
                "SKU 共用模具联动置换候选失败",
                context.getScheduleLogList().get(0).getTitle());
        String detail = context.getScheduleLogList().get(0).getLogDetail();
        Assertions.assertTrue(detail.contains("A=SKU-A"));
        Assertions.assertTrue(detail.contains("B=SKU-B"));
        Assertions.assertTrue(detail.contains("共用模具=[M-SHARED]"));
        Assertions.assertTrue(detail.contains("B剩余模具=[]"));
        Assertions.assertTrue(detail.contains("原机台=K1201"));
        Assertions.assertTrue(detail.contains("新机台=未生成"));
        Assertions.assertTrue(detail.contains("B下机=2026-07-30 06:00:00"));
        Assertions.assertTrue(detail.contains("A接管=2026-07-30 06:00:00"));
        Assertions.assertTrue(detail.contains("B换模=未生成"));
        Assertions.assertTrue(detail.contains("B重新开产=未生成"));
        Assertions.assertTrue(detail.contains("失败原因=B 无剩余空闲有效模具"));
    }

    /**
     * 验证新增排产或其他来源结果，即使机台和模具表面满足条件，也不得作为置换来源。
     */
    @Test
    public void isCurrentContinuationResult_shouldRejectNonContinuationMachine() {
        SharedMouldSubstitutionCoordinator coordinator =
                new SharedMouldSubstitutionCoordinator();
        LhScheduleContext context = new LhScheduleContext();
        MachineScheduleDTO initialMachine = buildMachine("K1201");
        initialMachine.setCurrentMaterialCode("SKU-B");
        context.getInitialMachineScheduleMap().put(
                initialMachine.getMachineCode(), initialMachine);
        LhScheduleResult newSpecResult = buildResult(
                "SKU-B", "K1201", "M-SHARED",
                4, toDateTime(LocalDate.of(2026, 7, 23), 8, 0), "1");
        newSpecResult.setScheduleType(
                ScheduleTypeEnum.NEW_SPEC.getCode());
        context.getScheduleResultList().add(newSpecResult);

        Boolean eligible = ReflectionTestUtils.invokeMethod(
                coordinator, "isCurrentContinuationResult",
                context, newSpecResult);

        Assertions.assertFalse(eligible,
                "非续作排产机台不得进入共用模具置换候选");
    }

    /**
     * 验证 A 在窗口内最早月计划日计划量大于 0 的日期，精确决定接管目标日期。
     */
    @Test
    public void resolvePlanDate_shouldUseEarliestPositiveDailyPlanDate() {
        SharedMouldSubstitutionCoordinator coordinator =
                new SharedMouldSubstitutionCoordinator();
        LhScheduleContext context = new LhScheduleContext();
        LocalDate scheduleDate = LocalDate.of(2026, 7, 23);
        context.setScheduleDate(toDateTime(scheduleDate, 0, 0));
        context.setWindowEndDate(
                toDateTime(scheduleDate.plusDays(2), 23, 0));
        context.getScheduleWindowShifts().add(
                buildShift(1, scheduleDate, 6, 0));
        context.getScheduleWindowShifts().add(
                buildShift(2, scheduleDate.plusDays(1), 6, 0));
        context.getScheduleWindowShifts().add(
                buildShift(3, scheduleDate.plusDays(2), 6, 0));
        SkuScheduleDTO targetSku = buildSku("SKU-A");
        FactoryMonthPlanProductionFinalResult monthPlan =
                new FactoryMonthPlanProductionFinalResult();
        monthPlan.setMaterialCode(targetSku.getMaterialCode());
        monthPlan.setProductStatus(targetSku.getProductStatus());
        monthPlan.setYear(2026);
        monthPlan.setMonth(7);
        monthPlan.setDay23(0);
        monthPlan.setDay24(12);
        monthPlan.setDay25(20);
        context.getLoadedMonthPlanList().add(monthPlan);

        SharedMouldPlanDateResolution resolution =
                ReflectionTestUtils.invokeMethod(
                        coordinator, "resolvePlanDate",
                        context, targetSku);

        Assertions.assertNotNull(resolution);
        Assertions.assertEquals(
                scheduleDate.plusDays(1),
                resolution.getFirstPositivePlanDate());
        Assertions.assertEquals(
                scheduleDate.plusDays(1),
                resolution.getTakeoverDate());
        Assertions.assertEquals(
                toDateTime(scheduleDate.plusDays(1), 6, 0),
                resolution.getTakeoverTargetTime());
    }

    /**
     * 验证 B 新结果不得继续使用已经转给 A 的共用模具，防止同一模具同时被两台机台占用。
     */
    @Test
    public void validateRelocation_shouldRejectTransferredMouldOverlap() {
        SharedMouldSubstitutionCoordinator coordinator =
                new SharedMouldSubstitutionCoordinator();
        Date offlineTime = toDateTime(
                LocalDate.of(2026, 7, 23), 16, 0);
        SharedMouldSubstitutionPlan plan =
                buildPlan(offlineTime);
        LhScheduleResult invalidRelocationResult = buildResult(
                "SKU-B", "K1301", "M-SHARED",
                4, toDateTime(LocalDate.of(2026, 7, 24), 8, 0), "1");
        invalidRelocationResult.setMouldChangeStartTime(
                toDateTime(LocalDate.of(2026, 7, 24), 6, 0));

        IllegalStateException exception = Assertions.assertThrows(
                IllegalStateException.class,
                () -> ReflectionTestUtils.invokeMethod(
                        coordinator, "validateRelocation",
                        plan,
                        Collections.singletonList(
                                invalidRelocationResult),
                        4, false));

        Assertions.assertTrue(
                exception.getMessage().contains("重复占用转交模具"));
    }

    /**
     * 验证 B 的新物理机台不得少排或多排截断尾量，必须精确承接。
     */
    @Test
    public void validateRelocation_shouldRejectQuantityMismatch() {
        SharedMouldSubstitutionCoordinator coordinator =
                new SharedMouldSubstitutionCoordinator();
        Date offlineTime = toDateTime(
                LocalDate.of(2026, 7, 23), 16, 0);
        SharedMouldSubstitutionPlan plan =
                buildPlan(offlineTime);
        LhScheduleResult underScheduledResult = buildResult(
                "SKU-B", "K1301", "M-FREE",
                3, toDateTime(LocalDate.of(2026, 7, 24), 8, 0), "1");
        underScheduledResult.setMouldChangeStartTime(
                toDateTime(LocalDate.of(2026, 7, 24), 6, 0));

        IllegalStateException exception = Assertions.assertThrows(
                IllegalStateException.class,
                () -> ReflectionTestUtils.invokeMethod(
                        coordinator, "validateRelocation",
                        plan,
                        Collections.singletonList(
                                underScheduledResult),
                        4, false));

        Assertions.assertTrue(
                exception.getMessage().contains("完整承接"));
    }

    /**
     * 验证公共续作下机服务只截断 B 下机后的尾量，并同步恢复生产余量、日计划和机台产能。
     */
    @Test
    public void continuationCutover_shouldRetainCompletedCyclesAndRestoreLedgers() {
        ContinuationCutoverService cutoverService =
                new ContinuationCutoverService();
        TargetScheduleQtyResolver targetScheduleQtyResolver =
                new TargetScheduleQtyResolver();
        ReflectionTestUtils.setField(
                cutoverService, "targetScheduleQtyResolver",
                targetScheduleQtyResolver);
        LhScheduleContext context = new LhScheduleContext();
        LocalDate productionDate = LocalDate.of(2026, 7, 23);
        context.getScheduleWindowShifts().add(
                buildShift(1, productionDate, 6, 0));
        MachineScheduleDTO machine = buildMachine("K1201");
        machine.setShiftRemainingCapacity(new int[9]);
        context.getMachineScheduleMap().put(
                machine.getMachineCode(), machine);
        context.getMachineShiftCapacityMap().put(
                machine.getMachineCode(),
                machine.getShiftRemainingCapacity());

        SkuScheduleDTO continuationSku = buildSku("SKU-B");
        continuationSku.setDailyPlanQuotaMap(
                new LinkedHashMap<LocalDate, SkuDailyPlanQuotaDTO>(2));
        SkuDailyPlanQuotaDTO quota =
                new SkuDailyPlanQuotaDTO();
        quota.setMaterialCode(
                continuationSku.getMaterialCode());
        quota.setProductionDate(productionDate);
        quota.setDayPlanQty(8);
        quota.setScheduledQty(8);
        quota.setRemainingQty(0);
        quota.setActualQty(8);
        continuationSku.getDailyPlanQuotaMap().put(
                productionDate, quota);
        context.getSkuProductionRemainingQtyMap().put(
                MonthPlanDateResolver.buildMaterialStatusKey(
                        continuationSku.getMaterialCode(),
                        continuationSku.getProductStatus()),
                0);
        LhScheduleResult continuationResult = buildResult(
                "SKU-B", "K1201", "M-SHARED", 8,
                toDateTime(productionDate, 8, 0), "0");
        continuationResult.setScheduleType(
                ScheduleTypeEnum.CONTINUOUS.getCode());
        continuationResult.setStructureName("ST-CONT");
        context.getScheduleResultList().add(
                continuationResult);
        context.getSpecialMaterialContinuationResultSnapshot().add(
                continuationResult);

        ContinuationCutoverResult result =
                cutoverService.cutover(
                        context, continuationSku, "K1201",
                        toDateTime(productionDate, 12, 0));

        Assertions.assertEquals(4, result.getRemovedQty());
        Assertions.assertEquals(
                Integer.valueOf(4),
                continuationResult.getClass1PlanQty());
        Assertions.assertEquals(
                Integer.valueOf(4),
                context.getSkuProductionRemainingQtyMap().get(
                        MonthPlanDateResolver.buildMaterialStatusKey(
                                continuationSku.getMaterialCode(),
                                continuationSku.getProductStatus())));
        Assertions.assertEquals(4, quota.getScheduledQty());
        Assertions.assertEquals(4, quota.getRemainingQty());
        Assertions.assertEquals(4, quota.getActualQty());
        Assertions.assertEquals(
                4, machine.getShiftRemainingCapacity()[1]);
    }

    private SharedMouldSubstitutionPlan buildPlan(
            Date offlineTime) {
        SharedMouldSubstitutionPlan plan =
                new SharedMouldSubstitutionPlan();
        plan.setTargetSku(buildSku("SKU-A"));
        plan.setContinuationSku(buildSku("SKU-B"));
        plan.setOriginalPhysicalMachineCode("K1201");
        plan.setTakeoverMachineCode("K1201");
        plan.setOriginalMachineCodeList(
                Collections.singletonList("K1201"));
        Map<String, List<String>> transferredMouldCodeMap =
                new LinkedHashMap<String, List<String>>(2);
        transferredMouldCodeMap.put(
                "K1201",
                Collections.singletonList("M-SHARED"));
        plan.setTransferredMouldCodeMap(
                transferredMouldCodeMap);
        plan.setContinuationOfflineTime(offlineTime);
        plan.setTakeoverTargetTime(offlineTime);
        return plan;
    }

    private SkuScheduleDTO buildSku(String materialCode) {
        SkuScheduleDTO sku = new SkuScheduleDTO();
        sku.setMaterialCode(materialCode);
        sku.setProductStatus("S");
        sku.setLhTimeSeconds(3600);
        sku.setMouldQty(1);
        sku.setTargetScheduleQty(8);
        sku.setPendingQty(8);
        sku.setRemainingScheduleQty(8);
        return sku;
    }

    private MachineScheduleDTO buildMachine(
            String machineCode) {
        MachineScheduleDTO machine = new MachineScheduleDTO();
        machine.setMachineCode(machineCode);
        machine.setMaxMoldNum(1);
        return machine;
    }

    private LhScheduleResult buildResult(
            String materialCode,
            String machineCode,
            String mouldCode,
            int qty,
            Date productionStartTime,
            String isChangeMould) {
        LhScheduleResult result = new LhScheduleResult();
        result.setMaterialCode(materialCode);
        result.setProductStatus("S");
        result.setLhMachineCode(machineCode);
        result.setMouldCode(mouldCode);
        result.setMouldQty(1);
        result.setIsChangeMould(isChangeMould);
        result.setIsTypeBlock("0");
        result.setScheduleType(
                ScheduleTypeEnum.NEW_SPEC.getCode());
        result.setClass1PlanQty(qty);
        result.setClass1StartTime(productionStartTime);
        result.setClass1EndTime(new Date(
                productionStartTime.getTime()
                        + qty * 60L * 60L * 1000L));
        result.setDailyPlanQty(qty);
        return result;
    }

    private LhShiftConfigVO buildShift(
            int shiftIndex,
            LocalDate workDate,
            int startHour,
            int startMinute) {
        LhShiftConfigVO shift = new LhShiftConfigVO();
        shift.setShiftIndex(shiftIndex);
        shift.setScheduleBaseDate(
                toDateTime(workDate, 0, 0));
        shift.setDateOffset(0);
        shift.setShiftType(
                ShiftEnum.MORNING_SHIFT.getCode());
        shift.setStartTime(String.format(
                "%02d:%02d", startHour, startMinute));
        shift.setEndTime(String.format(
                "%02d:%02d", startHour + 8, startMinute));
        return shift;
    }

    private static Date toDateTime(
            LocalDate date,
            int hour,
            int minute) {
        return Date.from(date.atTime(hour, minute)
                .atZone(ZoneId.systemDefault()).toInstant());
    }
}
