package com.zlt.aps.lh.engine.strategy.impl;

import com.zlt.aps.lh.api.domain.dto.MachineScheduleDTO;
import com.zlt.aps.lh.api.domain.dto.SkuDailyPlanQuotaDTO;
import com.zlt.aps.lh.api.domain.dto.SkuScheduleDTO;
import com.zlt.aps.lh.api.domain.entity.LhRepairCapsule;
import com.zlt.aps.lh.api.domain.entity.LhScheduleResult;
import com.zlt.aps.lh.api.domain.vo.LhShiftConfigVO;
import com.zlt.aps.lh.api.enums.ConstructionStageEnum;
import com.zlt.aps.lh.api.enums.MouldChangeTypeEnum;
import com.zlt.aps.lh.api.enums.SkuScheduleSourceTypeEnum;
import com.zlt.aps.lh.component.CapsuleReplacementRuleService;
import com.zlt.aps.lh.component.EarlyProductionQuantityCalculator;
import com.zlt.aps.lh.component.MonthPlanDateResolver;
import com.zlt.aps.lh.component.TargetScheduleQtyResolver;
import com.zlt.aps.lh.context.LhScheduleConfig;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.engine.strategy.IMachineMatchStrategy;
import com.zlt.aps.lh.engine.strategy.support.ActiveMachineBinding;
import com.zlt.aps.lh.engine.strategy.support.DayDrivenScheduleState;
import com.zlt.aps.lh.engine.strategy.support.DayScheduleContext;
import com.zlt.aps.lh.engine.strategy.support.DailyCandidateReason;
import com.zlt.aps.lh.engine.strategy.support.DailyNewSpecCandidate;
import com.zlt.aps.lh.engine.strategy.support.DailySchedulePhase;
import com.zlt.aps.lh.engine.strategy.support.EarlyProductionDecision;
import com.zlt.aps.lh.engine.strategy.support.EarlyProductionRuntimePlan;
import com.zlt.aps.lh.engine.strategy.support.HistoricalReverseSelectionDirective;
import com.zlt.aps.lh.engine.strategy.support.MouldResourceAllocationResult;
import com.zlt.aps.lh.engine.strategy.support.NewSpecCandidateCache;
import com.zlt.aps.lh.engine.strategy.support.ProductionQuantityPolicy;
import com.zlt.aps.lh.util.LhScheduleTimeUtil;
import com.zlt.aps.lh.util.ShiftFieldUtil;
import com.zlt.aps.mdm.api.domain.entity.MdmModelInfo;
import com.zlt.aps.mdm.api.domain.entity.MdmSkuMouldRel;
import com.zlt.aps.mp.api.domain.entity.FactoryMonthPlanProductionFinalResult;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * NewSpecProductionStrategy 选机规则测试。
 *
 * @author APS
 */
public class NewSpecProductionStrategyTest {

    /**
     * 用例说明：历史反选只锁定优先机台，日计划账本缺失或当前dayN为0时不得授予排产资格。
     */
    @Test
    public void shouldRequireCurrentDayPlanForHistoricalLockedCandidate() {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        LhScheduleContext context = new LhScheduleContext();
        context.setScheduleDate(toDate(2026, 7, 25, 0, 0, 0));
        List<LhShiftConfigVO> shifts =
                LhScheduleTimeUtil.buildDefaultScheduleShifts(context, context.getScheduleDate());
        LocalDate scheduleDate = resolveWorkDate(shifts.get(0));
        DayScheduleContext dayContext = new DayScheduleContext(
                scheduleDate, shifts.subList(0, 3), true, false);

        SkuScheduleDTO sku = new SkuScheduleDTO();
        sku.setMaterialCode("3302002369");
        sku.setProductStatus("S");
        sku.setPendingQty(100);
        sku.setSurplusQty(100);
        sku.setTargetScheduleQty(100);
        HistoricalReverseSelectionDirective directive = new HistoricalReverseSelectionDirective();
        directive.setMaterialCode(sku.getMaterialCode());
        directive.setProductStatus(sku.getProductStatus());
        directive.setMachineCode("K2001");
        directive.setEffectiveMachineCode("K2001");
        directive.setActualChangeType(MouldChangeTypeEnum.REGULAR.getCode());
        context.getHistoricalReverseSelectionDirectiveList().add(directive);
        DayDrivenScheduleState state =
                new DayDrivenScheduleState(Collections.singletonList(sku));

        DailyNewSpecCandidate zeroPlanCandidate = ReflectionTestUtils.invokeMethod(
                strategy, "buildDailyCandidate", context, dayContext, state,
                DailySchedulePhase.TODAY_PLAN_AND_LOCKED, sku);

        Assertions.assertNull(zeroPlanCandidate,
                "历史反选不得绕过当天日计划准入");

        Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap = new LinkedHashMap<LocalDate, SkuDailyPlanQuotaDTO>(2);
        quotaMap.put(scheduleDate, buildQuota(0, 60));
        sku.setDailyPlanQuotaMap(quotaMap);
        DailyNewSpecCandidate historyShiftedCandidate = ReflectionTestUtils.invokeMethod(
                strategy, "buildDailyCandidate", context, dayContext, state,
                DailySchedulePhase.TODAY_PLAN_AND_LOCKED, sku);
        Assertions.assertNull(historyShiftedCandidate,
                "临时追加的历史欠产使remainingQty大于0时，仍不得改变原始dayN为0的阶段归属");

        quotaMap.put(scheduleDate, buildQuota(60, 60));
        DailyNewSpecCandidate plannedCandidate = ReflectionTestUtils.invokeMethod(
                strategy, "buildDailyCandidate", context, dayContext, state,
                DailySchedulePhase.TODAY_PLAN_AND_LOCKED, sku);

        Assertions.assertNotNull(plannedCandidate);
        Assertions.assertTrue(plannedCandidate.hasReason(DailyCandidateReason.TODAY_PLAN));
        Assertions.assertTrue(plannedCandidate.hasReason(
                DailyCandidateReason.ALTERNATE_PLAN_REVERSE_SELECT));
    }

    /**
     * 用例说明：当前月 TOTAL_QTY=0 的 future-only 候选即使通用余量和目标量为正，
     * 也不得进入当天计划、正常加机台或历史欠产/收尾遗留阶段。
     */
    @Test
    public void shouldKeepFutureOnlyCandidateOutOfAllNormalPhases() {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        LhScheduleContext context = new LhScheduleContext();
        context.setScheduleDate(toDate(2026, 7, 29, 0, 0, 0));
        List<LhShiftConfigVO> shifts =
                LhScheduleTimeUtil.buildDefaultScheduleShifts(context, context.getScheduleDate());
        LocalDate scheduleDate = resolveWorkDate(shifts.get(0));
        DayScheduleContext dayContext = new DayScheduleContext(
                scheduleDate, shifts.subList(0, 2), true, false);
        SkuScheduleDTO sku = new SkuScheduleDTO();
        sku.setMaterialCode("3302001080");
        sku.setProductStatus("S");
        sku.setSurplusQty(500);
        sku.setPendingQty(500);
        sku.setTargetScheduleQty(500);
        Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap =
                new LinkedHashMap<LocalDate, SkuDailyPlanQuotaDTO>(1);
        quotaMap.put(scheduleDate, buildQuota(0, 0));
        sku.setDailyPlanQuotaMap(quotaMap);
        EarlyProductionRuntimePlan runtimePlan = new EarlyProductionRuntimePlan();
        runtimePlan.setFutureOnlyCandidate(true);
        runtimePlan.setActive(false);
        runtimePlan.setFuturePlanDate(LocalDate.of(2026, 8, 1));
        context.registerEarlyProductionRuntimePlan(sku, runtimePlan);
        DayDrivenScheduleState state =
                new DayDrivenScheduleState(Collections.singletonList(sku));

        // 调用处分别验证三个正常阶段入口，候选态不得与正常 SKU 同轮竞争资源。
        DailyNewSpecCandidate todayCandidate = ReflectionTestUtils.invokeMethod(
                strategy, "buildDailyCandidate", context, dayContext, state,
                DailySchedulePhase.TODAY_PLAN_AND_LOCKED, sku);
        DailyNewSpecCandidate addMachineCandidate = ReflectionTestUtils.invokeMethod(
                strategy, "buildDailyCandidate", context, dayContext, state,
                DailySchedulePhase.ADD_MACHINE, sku);
        DailyNewSpecCandidate legacyCandidate = ReflectionTestUtils.invokeMethod(
                strategy, "buildDailyCandidate", context, dayContext, state,
                DailySchedulePhase.ADD_MACHINE, sku, true);

        Assertions.assertNull(todayCandidate);
        Assertions.assertNull(addMachineCandidate);
        Assertions.assertNull(legacyCandidate);
    }

    /**
     * 用例说明：futurePlanDate 在 T 日尚未进入 N 天阈值时只保留候选；业务日推进后
     * 一旦进入阈值并通过结构切换准入，才激活临时前移账本和未来月目标量。
     */
    @Test
    public void shouldActivateFutureOnlyCandidateWhenFuturePlanEntersThreshold() {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        LocalDate scheduleStartDate = LocalDate.of(2026, 7, 29);
        LocalDate windowEndDate = LocalDate.of(2026, 7, 31);
        LhScheduleContext context = new LhScheduleContext();
        context.setScheduleDate(toDate(2026, 7, 29, 0, 0, 0));
        context.setScheduleTargetDate(toDate(2026, 7, 31, 0, 0, 0));
        context.setWindowEndDate(toDate(2026, 7, 31, 0, 0, 0));
        FactoryMonthPlanProductionFinalResult julyPlan =
                buildMonthPlan("3302001080", 2026, 7, 0);
        FactoryMonthPlanProductionFinalResult augustPlan =
                buildMonthPlan("3302001080", 2026, 8, 128);
        augustPlan.setDay1(48);
        augustPlan.setDay2(48);
        List<FactoryMonthPlanProductionFinalResult> planList =
                Arrays.asList(julyPlan, augustPlan);
        context.setMonthPlanList(planList);
        context.setLoadedMonthPlanList(planList);
        context.setMonthPlanByMaterialMonthMap(
                MonthPlanDateResolver.buildMaterialMonthPlanMap(planList));
        context.getStructurePlanMachineCountMap()
                .computeIfAbsent(LocalDate.of(2026, 8, 1),
                        key -> new LinkedHashMap<String, Integer>(1))
                .put("285/75R24.5", 7);

        SkuScheduleDTO sku = new SkuScheduleDTO();
        sku.setMaterialCode("3302001080");
        sku.setProductStatus("S");
        sku.setConstructionStage("03");
        sku.setScheduleType("02");
        sku.setStructureName("285/75R24.5");
        sku.setDailyCapacity(48);
        sku.setDailyPlanQuotaMap(new LinkedHashMap<LocalDate, SkuDailyPlanQuotaDTO>(3));
        sku.getDailyPlanQuotaMap().put(
                scheduleStartDate, buildQuota(0, 0));
        sku.getDailyPlanQuotaMap().put(
                scheduleStartDate.plusDays(1), buildQuota(0, 0));
        sku.getDailyPlanQuotaMap().put(
                windowEndDate, buildQuota(0, 0));
        context.getNewSpecSkuList().add(sku);
        context.getStructureSkuMap().put(
                sku.getStructureName(), new ArrayList<SkuScheduleDTO>(Collections.singletonList(sku)));
        EarlyProductionRuntimePlan candidatePlan =
                EarlyProductionQuantityCalculator.registerFutureOnlyCandidateView(
                        context, sku, scheduleStartDate);
        Assertions.assertNotNull(candidatePlan);
        List<LhShiftConfigVO> scheduleShifts =
                LhScheduleTimeUtil.buildDefaultScheduleShifts(context, context.getScheduleDate());

        // T日距离08.01为3天，超过默认阈值2天，只保留候选，不得生成运行态目标和前移账本。
        EarlyProductionRuntimePlan waitingPlan = ReflectionTestUtils.invokeMethod(
                strategy, "prepareEarlyProductionRuntimePlan", context,
                new DayScheduleContext(scheduleStartDate, scheduleShifts.subList(0, 2), true, false),
                sku);
        Assertions.assertSame(candidatePlan, waitingPlan);
        Assertions.assertFalse(waitingPlan.isActive());
        Assertions.assertEquals(0, sku.resolveTargetScheduleQty());

        // 业务日推进到07.30后，08.01进入2天阈值，并按未来结构计划7台完成结构切换准入。
        EarlyProductionRuntimePlan activePlan = ReflectionTestUtils.invokeMethod(
                strategy, "prepareEarlyProductionRuntimePlan", context,
                new DayScheduleContext(scheduleStartDate.plusDays(1),
                        scheduleShifts.subList(2, 5), false, false),
                sku);

        Assertions.assertSame(candidatePlan, activePlan);
        Assertions.assertTrue(activePlan.isActive());
        Assertions.assertTrue(activePlan.getDecision().isAllowed());
        Assertions.assertEquals(LocalDate.of(2026, 8, 1), activePlan.getFuturePlanDate());
        Assertions.assertEquals(96, activePlan.getFutureMonthSurplusQty());
        Assertions.assertEquals(96, activePlan.getEffectiveTargetQty());
        Assertions.assertEquals(48, activePlan.getShiftedDailyPlanQuotaMap()
                .get(LocalDate.of(2026, 7, 30)).getDayPlanQty());
        Assertions.assertEquals(96, sku.resolveTargetScheduleQty());
    }

    /**
     * 用例说明：提前生产已经排入部分数量后，最终剩余原因必须反映“已使用剩余资源”，
     * 不能误报为从未命中提前生产候选。
     */
    @Test
    public void shouldDescribePartialEarlyProductionRemainingAccurately() {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        LhScheduleContext context = new LhScheduleContext();
        SkuScheduleDTO sku = new SkuScheduleDTO();
        sku.setMaterialCode("3302001523");
        sku.setProductStatus("S");
        context.registerEarlyProductionRuntimePlan(sku, new EarlyProductionRuntimePlan());
        LhScheduleResult earlyResult = new LhScheduleResult();
        earlyResult.setMaterialCode(sku.getMaterialCode());
        earlyResult.setProductStatus(sku.getProductStatus());
        earlyResult.setIsEarlyProduction("1");
        context.getScheduleResultList().add(earlyResult);

        String reason = ReflectionTestUtils.invokeMethod(
                strategy, "buildEarlyProductionPartialRemainingReason", 394);
        Boolean partiallyScheduled = ReflectionTestUtils.invokeMethod(
                strategy, "hasPartiallyScheduledEarlyProductionResult", context, sku);

        Assertions.assertEquals(
                "提前生产已使用正常阶段后的剩余资源，按当前结构及日计划机台节奏不再扩机，"
                        + "剩余394保留原计划日期",
                reason);
        Assertions.assertFalse(reason.contains("未命中提前生产候选"));
        Assertions.assertTrue(Boolean.TRUE.equals(partiallyScheduled),
                "中心运行视图与提前生产结果同时存在时，应识别为提前生产部分成功");
    }

    /**
     * 用例说明：结构/SKU已排机台统计重建必须按机台 Set 去重，并在结果释放或班次计划量
     * 清零后删除旧缓存，避免残留机台数阻断后续提前生产准入。
     */
    @Test
    public void shouldRebuildScheduledMachineCountAfterResultReleaseOrPlanClear() {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        LhScheduleContext context = new LhScheduleContext();
        context.setScheduleDate(toDate(2026, 7, 29, 0, 0, 0));
        List<LhShiftConfigVO> shifts =
                LhScheduleTimeUtil.buildDefaultScheduleShifts(context, context.getScheduleDate());
        LocalDate businessDate = resolveWorkDate(shifts.get(0));

        LhScheduleResult firstSkuResult = buildScheduledMachineResult(
                "3302001001", "S", "STRUCT-01", "K1101", 10);
        firstSkuResult.setClass2PlanQty(10);
        LhScheduleResult secondSkuSameMachineResult = buildScheduledMachineResult(
                "3302001002", "S", "STRUCT-01", "K1101", 8);
        context.getScheduleResultList().add(firstSkuResult);
        context.getScheduleResultList().add(secondSkuSameMachineResult);

        ReflectionTestUtils.invokeMethod(
                strategy, "rebuildScheduledMachineCountMap", context, shifts);

        Assertions.assertEquals(
                1, context.getStructureScheduledMachineCount(businessDate, "STRUCT-01"),
                "同结构多个SKU共用同一机台时只能统计一台");
        Assertions.assertEquals(
                1, context.getSkuScheduledMachineCount(
                        businessDate, "3302001001", "S"),
                "同一SKU同一机台多个班次只能统计一台");
        Assertions.assertEquals(
                1, context.getSkuScheduledMachineCount(
                        businessDate, "3302001002", "S"));

        // 模拟结果释放和剩余结果班次计划量清零，再次重建时不得保留任何旧机台缓存。
        context.getScheduleResultList().remove(secondSkuSameMachineResult);
        firstSkuResult.setClass1PlanQty(0);
        firstSkuResult.setClass2PlanQty(0);
        ReflectionTestUtils.invokeMethod(
                strategy, "rebuildScheduledMachineCountMap", context, shifts);

        Assertions.assertEquals(
                0, context.getStructureScheduledMachineCount(businessDate, "STRUCT-01"));
        Assertions.assertEquals(
                0, context.getSkuScheduledMachineCount(
                        businessDate, "3302001001", "S"));
    }

    /**
     * 用例说明：存在单机可收完剩余量的候选机台时，应优先选择该机台。
     *
     * @throws Exception 反射调用异常
     */
    @Test
    public void shouldSelectMachineThatCanFinishRemainingQtyFirst() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        injectTargetScheduleQtyResolver(strategy, new TargetScheduleQtyResolver() {
            @Override
            public int calcMachineAvailableCapacityInWindow(LhScheduleContext context,
                                                            SkuScheduleDTO sku,
                                                            MachineScheduleDTO machine,
                                                            Date productionNotBeforeTime) {
                if ("K1111L".equals(machine.getMachineCode())) {
                    return 20;
                }
                if ("K1105L".equals(machine.getMachineCode())) {
                    return 16;
                }
                return 0;
            }
        });

        LhScheduleContext context = new LhScheduleContext();
        context.setScheduleDate(toDate(2026, 5, 13, 0, 0, 0));
        context.setScheduleWindowShifts(Collections.singletonList(
                LhScheduleTimeUtil.buildDefaultScheduleShifts(context, context.getScheduleDate()).get(0)));

        SkuScheduleDTO sku = new SkuScheduleDTO();
        sku.setMaterialCode("3302001575");
        sku.setRemainingScheduleQty(20);
        sku.setShiftCapacity(0);
        sku.setLhTimeSeconds(3600);
        sku.setTrial(true);
        sku.setStrictTargetQty(true);
        sku.setConstructionStage(ConstructionStageEnum.TRIAL.getCode());

        MachineScheduleDTO firstMachine = buildMachine("K1105L", 1);
        MachineScheduleDTO secondMachine = buildMachine("K1111L", 4);
        List<MachineScheduleDTO> candidates = Arrays.asList(firstMachine, secondMachine);

        MachineScheduleDTO selected = invokeSelectCandidateMachine(
                strategy,
                context,
                sku,
                candidates,
                Collections.<String>emptySet(),
                new FirstCandidateMachineMatchStrategy(),
                null,
                ProductionQuantityPolicy.from(sku, false));

        Assertions.assertNotNull(selected);
        Assertions.assertEquals("K1111L", selected.getMachineCode());
    }

    /**
     * 用例说明：正式非收尾SKU需要由角色判断决定非最后机台满排，
     * 不应提前改写候选机台顺序去优先选择尾量机台。
     *
     * @throws Exception 反射调用异常
     */
    @Test
    public void shouldKeepCandidateOrderForFormalDynamicFullRun() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        injectTargetScheduleQtyResolver(strategy, new TargetScheduleQtyResolver() {
            @Override
            public int calcMachineAvailableCapacityInWindow(LhScheduleContext context,
                                                            SkuScheduleDTO sku,
                                                            MachineScheduleDTO machine,
                                                            Date productionNotBeforeTime) {
                if ("K1105".equals(machine.getMachineCode())) {
                    return 112;
                }
                if ("K1110".equals(machine.getMachineCode())) {
                    return 64;
                }
                return 0;
            }
        });

        LhScheduleContext context = new LhScheduleContext();
        context.setScheduleDate(toDate(2026, 5, 13, 0, 0, 0));
        context.setScheduleWindowShifts(Collections.singletonList(
                LhScheduleTimeUtil.buildDefaultScheduleShifts(context, context.getScheduleDate()).get(0)));

        SkuScheduleDTO sku = new SkuScheduleDTO();
        sku.setMaterialCode("3302001724");
        sku.setRemainingScheduleQty(158);
        sku.setShiftCapacity(0);
        sku.setLhTimeSeconds(3600);
        sku.setConstructionStage(ConstructionStageEnum.FORMAL.getCode());

        MachineScheduleDTO firstMachine = buildMachine("K1105", 1);
        MachineScheduleDTO secondMachine = buildMachine("K1110", 1);
        List<MachineScheduleDTO> candidates = Arrays.asList(firstMachine, secondMachine);

        MachineScheduleDTO selected = invokeSelectCandidateMachine(
                strategy,
                context,
                sku,
                candidates,
                Collections.<String>emptySet(),
                new FirstCandidateMachineMatchStrategy(),
                null,
                ProductionQuantityPolicy.from(sku, false));

        Assertions.assertNotNull(selected);
        Assertions.assertEquals("K1105", selected.getMachineCode());
    }

    /**
     * 用例说明：续作补偿 SKU 进入新增阶段后，轮到自己选机时应优先锁回原续作机台，
     * 不应继续沿用普通新增候选顺序。
     *
     * @throws Exception 反射调用异常
     */
    @Test
    public void shouldPreferPreferredContinuousMachineForCompensationSku() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        LhScheduleContext context = new LhScheduleContext();

        SkuScheduleDTO sku = new SkuScheduleDTO();
        sku.setMaterialCode("3302002546");
        sku.setConstructionStage(ConstructionStageEnum.FORMAL.getCode());
        sku.setContinuousCompensationSku(true);
        ReflectionTestUtils.setField(sku, "preferredContinuousMachineCode", "K1105");

        MachineScheduleDTO firstMachine = buildMachine("K1110", 1);
        MachineScheduleDTO preferredMachine = buildMachine("K1105", 1);
        List<MachineScheduleDTO> candidates = Arrays.asList(firstMachine, preferredMachine);

        MachineScheduleDTO selected = invokeSelectCandidateMachine(
                strategy,
                context,
                sku,
                candidates,
                Collections.<String>emptySet(),
                new FirstCandidateMachineMatchStrategy(),
                null,
                ProductionQuantityPolicy.from(sku, false));

        Assertions.assertNotNull(selected);
        Assertions.assertEquals("K1105", selected.getMachineCode(),
                "补偿SKU轮到自己选机时，应优先锁回原续作机台");
    }

    /**
     * 用例说明：补偿锁回机台若当天已有有效占用，且同作用域存在当天空闲候选，
     * 应优先使用空闲机台，避免覆盖机台排序结果。
     *
     * @throws Exception 反射调用异常
     */
    @Test
    public void shouldPreferTodayIdleMachineOverPreferredContinuousMachineWhenFirstDayDemandExists() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        LhScheduleContext context = buildTodayIdleContext();

        SkuScheduleDTO sku = buildFirstDayDemandSku("3302002319", 32);
        sku.setContinuousCompensationSku(true);
        ReflectionTestUtils.setField(sku, "preferredContinuousMachineCode", "K1407");

        MachineScheduleDTO idleMachine = buildReadyMachine("K1105", toDate(2026, 4, 20, 8, 0, 0));
        MachineScheduleDTO preferredBusyMachine = buildReadyMachine("K1407", toDate(2026, 4, 21, 8, 0, 0));
        context.getMachineAssignmentMap().put("K1407", Collections.singletonList(
                buildAssignedResult("K1407", "3302001465", toDate(2026, 4, 21, 14, 0, 0))));
        List<MachineScheduleDTO> candidates = Arrays.asList(idleMachine, preferredBusyMachine);

        MachineScheduleDTO selected = invokeSelectCandidateMachineFromScopedList(
                strategy, context, sku, candidates, new FirstCandidateMachineMatchStrategy(),
                null, ProductionQuantityPolicy.from(sku, false));

        Assertions.assertNotNull(selected);
        Assertions.assertEquals("K1105", selected.getMachineCode(),
                "当天有空闲候选时，补偿锁回不得覆盖空闲优先");
    }

    /**
     * 用例说明：续作加机台候选已经进入新增候选池后，应先尝试原续作机台，
     * 不再被当天空闲机台优先规则覆盖；若原续作机台后续试算失败，再回落普通候选排序。
     *
     * @throws Exception 反射调用异常
     */
    @Test
    public void shouldPreferOriginalContinuousMachineForContinuationAddMachineSource() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        LhScheduleContext context = buildTodayIdleContext();

        SkuScheduleDTO sku = buildFirstDayDemandSku("3302002319", 32);
        sku.setContinuousCompensationSku(true);
        sku.setSourceType(SkuScheduleSourceTypeEnum.CONTINUATION_ADD_MACHINE.getCode());
        sku.setPreferredContinuousMachineCode("K1407");

        MachineScheduleDTO idleMachine = buildReadyMachine("K1105", toDate(2026, 4, 20, 8, 0, 0));
        MachineScheduleDTO preferredBusyMachine = buildReadyMachine("K1407", toDate(2026, 4, 21, 8, 0, 0));
        context.getMachineAssignmentMap().put("K1407", Collections.singletonList(
                buildAssignedResult("K1407", "3302001465", toDate(2026, 4, 21, 14, 0, 0))));
        List<MachineScheduleDTO> candidates = Arrays.asList(idleMachine, preferredBusyMachine);

        MachineScheduleDTO selected = invokeSelectCandidateMachineFromScopedList(
                strategy, context, sku, candidates, new FirstCandidateMachineMatchStrategy(),
                null, ProductionQuantityPolicy.from(sku, false));

        Assertions.assertNotNull(selected);
        Assertions.assertEquals("K1407", selected.getMachineCode(),
                "续作加机台候选轮到自己选机时，应先尝试原续作机台");
    }

    /**
     * 用例说明：可单机收完剩余量的候选中存在当天空闲机台时，
     * 应优先选择空闲机台而不是列表中更靠前的占用机台。
     *
     * @throws Exception 反射调用异常
     */
    @Test
    public void shouldPreferTodayIdleMachineWhenMultipleMachinesCanFinishRemainingQty() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        injectTargetScheduleQtyResolver(strategy, new TargetScheduleQtyResolver() {
            @Override
            public int calcMachineAvailableCapacityInWindow(LhScheduleContext context,
                                                            SkuScheduleDTO sku,
                                                            MachineScheduleDTO machine,
                                                            Date productionNotBeforeTime) {
                if ("K1407".equals(machine.getMachineCode()) || "K1105".equals(machine.getMachineCode())) {
                    return 40;
                }
                return 0;
            }
        });
        LhScheduleContext context = buildTodayIdleContext();

        SkuScheduleDTO sku = buildFirstDayDemandSku("3302002319", 32);
        MachineScheduleDTO busyMachine = buildReadyMachine("K1407", toDate(2026, 4, 21, 8, 0, 0));
        MachineScheduleDTO idleMachine = buildReadyMachine("K1105", toDate(2026, 4, 20, 8, 0, 0));
        context.getMachineAssignmentMap().put("K1407", Collections.singletonList(
                buildAssignedResult("K1407", "3302001465", toDate(2026, 4, 21, 14, 0, 0))));
        List<MachineScheduleDTO> candidates = Arrays.asList(busyMachine, idleMachine);

        MachineScheduleDTO selected = invokeSelectCandidateMachineFromScopedList(
                strategy, context, sku, candidates, new FirstCandidateMachineMatchStrategy(),
                null, ProductionQuantityPolicy.from(sku, false));

        Assertions.assertNotNull(selected);
        Assertions.assertEquals("K1105", selected.getMachineCode(),
                "多台机台均可单机收完时，应优先当天空闲机台");
    }

    /**
     * 用例说明：若原续作机台已不可选，则补偿 SKU 应回退到现有新增选机逻辑。
     *
     * @throws Exception 反射调用异常
     */
    @Test
    public void shouldFallbackWhenPreferredContinuousMachineIsExcluded() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        LhScheduleContext context = new LhScheduleContext();

        SkuScheduleDTO sku = new SkuScheduleDTO();
        sku.setMaterialCode("3302002546");
        sku.setConstructionStage(ConstructionStageEnum.FORMAL.getCode());
        sku.setContinuousCompensationSku(true);
        ReflectionTestUtils.setField(sku, "preferredContinuousMachineCode", "K1105");

        MachineScheduleDTO fallbackMachine = buildMachine("K1110", 1);
        MachineScheduleDTO preferredMachine = buildMachine("K1105", 1);
        List<MachineScheduleDTO> candidates = Arrays.asList(fallbackMachine, preferredMachine);

        MachineScheduleDTO selected = invokeSelectCandidateMachine(
                strategy,
                context,
                sku,
                candidates,
                Collections.singleton("K1105"),
                new FirstCandidateMachineMatchStrategy(),
                null,
                ProductionQuantityPolicy.from(sku, false));

        Assertions.assertNotNull(selected);
        Assertions.assertEquals("K1110", selected.getMachineCode(),
                "原续作机台不可选时，应回退到现有新增候选机台顺序");
    }

    /**
     * 用例说明：新增候选机台模数大于SKU剩余可用模具数时，策略层应拒绝当前候选机台。
     *
     * @throws Exception 反射调用异常
     */
    @Test
    public void shouldRejectCandidateMachineWhenMouldResourceIsNotEnough() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        LhScheduleContext context = buildMouldResourceContext("SKU-001",
                Collections.singletonList("M001"), Collections.singletonList(buildMachine("K1105", 2)));
        context.setScheduleDate(toDate(2026, 6, 4, 0, 0, 0));
        SkuScheduleDTO sku = new SkuScheduleDTO();
        sku.setMaterialCode("SKU-001");

        MouldResourceAllocationResult result = invokeTryAllocateMouldResourceForAddMachine(
                strategy, context, sku, buildMachine("K1105", 2), 1, 0);

        Assertions.assertFalse(result.isAllowed());
        Assertions.assertEquals(1, result.getAvailableMouldQty());
        Assertions.assertEquals(1, result.getRemainingAvailableMouldQty());
    }

    /**
     * 用例说明：新增候选机台分配模具前，应先把上下文当前排程日期设置为SKU日计划账本首个业务日。
     *
     * @throws Exception 反射调用异常
     */
    @Test
    public void shouldSetCurrentScheduleDateBeforeMouldResourceAllocation() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        LhScheduleContext context = buildMouldResourceContext("SKU-001",
                Collections.singletonList("M001"), Collections.singletonList(buildMachine("K1105", 1)));
        context.setScheduleDate(toDate(2026, 6, 4, 0, 0, 0));
        SkuScheduleDTO sku = new SkuScheduleDTO();
        sku.setMaterialCode("SKU-001");
        Map<LocalDate, SkuDailyPlanQuotaDTO> dailyPlanQuotaMap =
                new LinkedHashMap<LocalDate, SkuDailyPlanQuotaDTO>(2);
        SkuDailyPlanQuotaDTO firstQuota = new SkuDailyPlanQuotaDTO();
        firstQuota.setProductionDate(LocalDate.of(2026, 6, 5));
        firstQuota.setDayPlanQty(16);
        dailyPlanQuotaMap.put(firstQuota.getProductionDate(), firstQuota);
        sku.setDailyPlanQuotaMap(dailyPlanQuotaMap);

        Assertions.assertNull(context.getCurrentScheduleDate());
        MouldResourceAllocationResult result = invokeTryAllocateMouldResourceForAddMachine(
                strategy, context, sku, buildMachine("K1105", 1), 1, 0);

        Assertions.assertTrue(result.isAllowed());
        Assertions.assertEquals(toDate(2026, 6, 5, 0, 0, 0), context.getCurrentScheduleDate());
    }

    /**
     * 用例说明：候选机台通过模具校验后，如果后续换模、首检或产能失败，应释放本次预占模具。
     *
     * @throws Exception 反射调用异常
     */
    @Test
    public void shouldReleaseAllocatedMouldWhenNewSpecCandidateRollback() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        LhScheduleContext context = buildMouldResourceContext("SKU-001",
                Collections.singletonList("M001"), Arrays.asList(buildMachine("K1105", 1), buildMachine("K1110", 1)));
        context.setScheduleDate(toDate(2026, 6, 4, 0, 0, 0));
        SkuScheduleDTO sku = new SkuScheduleDTO();
        sku.setMaterialCode("SKU-001");

        MouldResourceAllocationResult first = invokeTryAllocateMouldResourceForAddMachine(
                strategy, context, sku, buildMachine("K1105", 1), 2, 0);
        invokeRollbackMouldResourceAllocation(strategy, context, sku, first);
        MouldResourceAllocationResult second = invokeTryAllocateMouldResourceForAddMachine(
                strategy, context, sku, buildMachine("K1110", 1), 2, 0);

        Assertions.assertTrue(first.isAllowed());
        Assertions.assertTrue(second.isAllowed());
        Assertions.assertEquals(Collections.singletonList("M001"), second.getAllocatedMouldCodeList());
    }

    /**
     * 用例说明：首日无计划续作占位被新增SKU抢占后，生成的补偿SKU仍应保留原续作机台，
     * 供后续新增补排轮到自己时优先锁回。
     *
     * @throws Exception 反射调用异常
     */
    @Test
    public void shouldKeepPreferredContinuousMachineWhenAppendingDeferredCompensationSku() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        LhScheduleContext context = new LhScheduleContext();

        SkuScheduleDTO sourceSku = new SkuScheduleDTO();
        sourceSku.setMaterialCode("3302002546");
        sourceSku.setContinuousMachineCode("K1105");
        sourceSku.setStrictTargetQty(true);
        sourceSku.setDailyPlanQuotaMap(new LinkedHashMap<LocalDate, SkuDailyPlanQuotaDTO>(4));

        LhScheduleResult placeholderResult = new LhScheduleResult();
        placeholderResult.setDailyPlanQty(82);

        List<SkuScheduleDTO> deferredCompensationSkuList = new java.util.ArrayList<SkuScheduleDTO>(1);
        Method method = NewSpecProductionStrategy.class.getDeclaredMethod(
                "appendDeferredContinuousCompensationSku",
                LhScheduleContext.class,
                SkuScheduleDTO.class,
                LhScheduleResult.class,
                List.class);
        method.setAccessible(true);

        method.invoke(strategy, context, sourceSku, placeholderResult, deferredCompensationSkuList);

        Assertions.assertEquals(1, deferredCompensationSkuList.size());
        SkuScheduleDTO compensationSku = deferredCompensationSkuList.get(0);
        Assertions.assertNull(compensationSku.getContinuousMachineCode(),
                "补偿SKU应清空续作机台，交由新增链路重新选机");
        Assertions.assertEquals("K1105",
                ReflectionTestUtils.getField(compensationSku, "preferredContinuousMachineCode"),
                "延后补排的补偿SKU应保留原续作机台，供轮到自己时优先锁回");
    }

    /**
     * 用例说明：目标量保留需求口径时，新增拆机剩余量仍应按日计划账本收敛。
     *
     * @throws Exception 反射调用异常
     */
    @Test
    public void shouldUseDailyQuotaAsSchedulableRemainingQtyWhenTargetIsLarger() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        SkuScheduleDTO sku = new SkuScheduleDTO();
        sku.setMaterialCode("3302001724");
        sku.setTargetScheduleQty(1032);
        sku.setWindowPlanQty(158);
        Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap = new LinkedHashMap<>(4);
        quotaMap.put(LocalDate.of(2026, 5, 1), buildQuota(160));
        quotaMap.put(LocalDate.of(2026, 5, 2), buildQuota(48));
        quotaMap.put(LocalDate.of(2026, 5, 3), buildQuota(14));
        sku.setDailyPlanQuotaMap(quotaMap);

        LhScheduleContext context = new LhScheduleContext();
        context.getSkuProductionRemainingQtyMap().put(sku.getMaterialCode(), 158);
        Method method = NewSpecProductionStrategy.class.getDeclaredMethod(
                "resolveSchedulableRemainingQty", LhScheduleContext.class, SkuScheduleDTO.class);
        method.setAccessible(true);

        Integer remainingQty = (Integer) method.invoke(strategy, context, sku);

        Assertions.assertEquals(158, remainingQty.intValue());
    }

    /**
     * 用例说明：多机台已消费部分日计划后，后续拆机剩余量应继续受窗口总量封顶。
     *
     * @throws Exception 反射调用异常
     */
    @Test
    public void shouldDeductConsumedQuotaWhenResolvingSchedulableRemainingQty() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        SkuScheduleDTO sku = new SkuScheduleDTO();
        sku.setMaterialCode("3302001724");
        sku.setTargetScheduleQty(158);
        sku.setWindowPlanQty(158);
        Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap = new LinkedHashMap<>(4);
        SkuDailyPlanQuotaDTO day1Quota = buildQuota(158);
        day1Quota.setScheduledQty(64);
        quotaMap.put(LocalDate.of(2026, 5, 1), day1Quota);
        sku.setDailyPlanQuotaMap(quotaMap);

        LhScheduleContext context = new LhScheduleContext();
        context.getSkuProductionRemainingQtyMap().put(sku.getMaterialCode(), 94);
        Method method = NewSpecProductionStrategy.class.getDeclaredMethod(
                "resolveSchedulableRemainingQty", LhScheduleContext.class, SkuScheduleDTO.class);
        method.setAccessible(true);

        Integer remainingQty = (Integer) method.invoke(strategy, context, sku);

        Assertions.assertEquals(94, remainingQty.intValue());
    }

    /**
     * 用例说明：单胎胚收尾目标量被胎胚库存上调后，日计划账本也要同步到收尾目标，
     * 避免新增排产后续按原 dayN 额度把 26 回裁成 5。
     *
     * @throws Exception 反射调用异常
     */
    @Test
    public void shouldSyncEndingQuotaWhenSingleEmbryoTargetUpsizedByEmbryoStock() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        TargetScheduleQtyResolver resolver = new TargetScheduleQtyResolver();
        SkuScheduleDTO sku = new SkuScheduleDTO();
        sku.setMaterialCode("3302002216");
        sku.setEmbryoCode("215101840");
        sku.setTargetScheduleQty(5);
        sku.setRemainingScheduleQty(5);
        sku.setWindowPlanQty(5);
        sku.setWindowRemainingPlanQty(5);
        sku.setSurplusQty(0);
        sku.setEmbryoStock(26);
        Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap = new LinkedHashMap<>(4);
        quotaMap.put(LocalDate.of(2026, 5, 25), buildQuota(5, 5));
        quotaMap.put(LocalDate.of(2026, 5, 26), buildQuota(0, 0));
        quotaMap.put(LocalDate.of(2026, 5, 27), buildQuota(0, 0));
        sku.setDailyPlanQuotaMap(quotaMap);

        LhScheduleContext context = new LhScheduleContext();
        resolver.upsizeEndingTargetQty(context, sku);

        Method method = NewSpecProductionStrategy.class.getDeclaredMethod(
                "resolveSchedulableRemainingQty", LhScheduleContext.class, SkuScheduleDTO.class);
        method.setAccessible(true);
        Integer remainingQty = (Integer) method.invoke(strategy, context, sku);

        Assertions.assertEquals(26, sku.resolveTargetScheduleQty());
        Assertions.assertEquals(26, sku.getWindowPlanQty());
        Assertions.assertEquals(26, sku.getWindowRemainingPlanQty());
        Assertions.assertEquals(26, remainingQty.intValue());
    }

    /**
     * 用例说明：量试非收尾按正式SKU处理，最后已开班班次允许补满，
     * 不能因为 sku.isTrial=true 被日计划账本回裁到严格上限。
     *
     * @throws Exception 反射调用异常
     */
    @Test
    public void shouldKeepMassTrialFilledShiftWhenApplyingDailyQuota() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        injectTargetScheduleQtyResolver(strategy, new TargetScheduleQtyResolver());
        LhScheduleContext context = new LhScheduleContext();
        context.setScheduleDate(toDate(2026, 5, 1, 0, 0, 0));
        context.getSkuProductionRemainingQtyMap().put("3302001724", 48);
        List<LhShiftConfigVO> shifts = LhScheduleTimeUtil.buildDefaultScheduleShifts(context, context.getScheduleDate());

        SkuScheduleDTO sku = new SkuScheduleDTO();
        sku.setMaterialCode("3302001724");
        sku.setTrial(true);
        sku.setConstructionStage(ConstructionStageEnum.MASS_TRIAL.getCode());
        sku.setWindowPlanQty(46);
        sku.setWindowRemainingPlanQty(46);
        Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap = new LinkedHashMap<>(4);
        LocalDate productionDate = shifts.get(0).getWorkDate().toInstant()
                .atZone(java.time.ZoneId.systemDefault()).toLocalDate();
        quotaMap.put(productionDate, buildQuota(46));
        sku.setDailyPlanQuotaMap(quotaMap);

        LhScheduleResult result = new LhScheduleResult();
        ShiftFieldUtil.setShiftPlanQty(result, shifts.get(0).getShiftIndex(), 48,
                shifts.get(0).getShiftStartDateTime(), shifts.get(0).getShiftEndDateTime());
        ShiftFieldUtil.syncDailyPlanQty(result);

        Method method = NewSpecProductionStrategy.class.getDeclaredMethod(
                "applyBlockToDailyQuota",
                LhScheduleContext.class,
                SkuScheduleDTO.class,
                LhScheduleResult.class,
                List.class,
                boolean.class);
        method.setAccessible(true);

        Integer scheduledQty = (Integer) method.invoke(strategy, context, sku, result, shifts, false);

        Assertions.assertEquals(48, scheduledQty.intValue());
        Assertions.assertEquals(48, ShiftFieldUtil.getShiftPlanQty(result, shifts.get(0).getShiftIndex()).intValue());
        Assertions.assertEquals(2, sku.getShiftFillOverQty());
    }

    /**
     * 用例说明：只要命中收尾场景，账本回写就必须严格按目标量截断，
     * 即使最后一个已开班班次有剩余产能，也不能再补满到 48。
     *
     * @throws Exception 反射调用异常
     */
    @Test
    public void shouldTrimEndingSkuToQuotaWhenApplyingDailyQuota() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        injectTargetScheduleQtyResolver(strategy, new TargetScheduleQtyResolver());
        LhScheduleContext context = new LhScheduleContext();
        context.setScheduleDate(toDate(2026, 5, 1, 0, 0, 0));
        List<LhShiftConfigVO> shifts = LhScheduleTimeUtil.buildDefaultScheduleShifts(context, context.getScheduleDate());

        SkuScheduleDTO sku = new SkuScheduleDTO();
        sku.setMaterialCode("3302001724");
        sku.setConstructionStage(ConstructionStageEnum.FORMAL.getCode());
        // 新增拆机进入尾机台时，SKU临时目标量已经被收敛到本机台计划量 48，
        // 但只要结果标记为收尾，就必须按日计划额度严格截断到 46。
        sku.setTargetScheduleQty(48);
        sku.setWindowPlanQty(158);
        sku.setSurplusQty(158);
        sku.setDailyCapacity(52);
        Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap = new LinkedHashMap<>(4);
        LocalDate productionDate = shifts.get(2).getWorkDate().toInstant()
                .atZone(java.time.ZoneId.systemDefault()).toLocalDate();
        quotaMap.put(productionDate, buildQuota(46));
        sku.setDailyPlanQuotaMap(quotaMap);
        sku.setStrictTargetQty(false);

        LhScheduleResult result = new LhScheduleResult();
        result.setIsEnd("1");
        ShiftFieldUtil.setShiftPlanQty(result, shifts.get(2).getShiftIndex(), 48,
                shifts.get(2).getShiftStartDateTime(), shifts.get(2).getShiftEndDateTime());
        ShiftFieldUtil.syncDailyPlanQty(result);

        Method method = NewSpecProductionStrategy.class.getDeclaredMethod(
                "applyBlockToDailyQuota",
                LhScheduleContext.class,
                SkuScheduleDTO.class,
                LhScheduleResult.class,
                List.class,
                boolean.class);
        method.setAccessible(true);

        Integer scheduledQty = (Integer) method.invoke(strategy, context, sku, result, shifts, false);

        Assertions.assertEquals(46, scheduledQty.intValue());
        Assertions.assertEquals(46, ShiftFieldUtil.getShiftPlanQty(result, shifts.get(2).getShiftIndex()).intValue());
        Assertions.assertEquals(0, sku.getShiftFillOverQty());
    }

    /**
     * 用例说明：收尾SKU跨日续排时，只有实时剩余量不超过当前业务日物理组产能，
     * 当前增量才属于最终严格收尾块；中间业务日不得读取结果行isEnd直接裁断班次。
     */
    @Test
    public void shouldOnlyMarkCarryOverFinalStrictBlockWhenRemainingFitsDayCapacity() {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        SkuScheduleDTO sku = new SkuScheduleDTO();
        sku.setMaterialCode("3302001593");
        LhScheduleResult result = new LhScheduleResult();
        result.setIsEnd("1");
        ActiveMachineBinding endingBinding = new ActiveMachineBinding(
                "3302001593/S", sku, "K1809", null,
                result, null, true);

        Boolean middleDayBlock = ReflectionTestUtils.invokeMethod(
                strategy, "isFinalStrictCarryOverBlock",
                endingBinding, 50, 48);
        Boolean finalDayBlock = ReflectionTestUtils.invokeMethod(
                strategy, "isFinalStrictCarryOverBlock",
                endingBinding, 48, 48);

        Assertions.assertFalse(Boolean.TRUE.equals(middleDayBlock),
                "剩余量仍大于当日产能时必须连续满产，不能按dayN尾量裁断");
        Assertions.assertTrue(Boolean.TRUE.equals(finalDayBlock),
                "剩余量可在当日收完时才进入最终严格收口");
    }

    /**
     * 用例说明：收尾目标量被前置补满规则抬高时，最终严格余量必须扣除本批次已经落地的
     * 新增结果，不能继续读取被抬高后的目标账本。
     */
    @Test
    public void shouldResolveStrictSurplusRemainingQtyFromActualScheduledResult() {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        LhScheduleContext context = new LhScheduleContext();
        SkuScheduleDTO sku = new SkuScheduleDTO();
        sku.setMaterialCode("3302002343");
        sku.setProductStatus("T");
        sku.setSurplusQty(60);
        sku.setTargetScheduleQty(112);

        LhScheduleResult scheduledResult = new LhScheduleResult();
        scheduledResult.setMaterialCode(sku.getMaterialCode());
        scheduledResult.setProductStatus(sku.getProductStatus());
        scheduledResult.setScheduleType("02");
        scheduledResult.setIsTypeBlock("0");
        scheduledResult.setClass1PlanQty(40);
        context.getScheduleResultList().add(scheduledResult);

        Integer remainingQty = ReflectionTestUtils.invokeMethod(
                strategy, "resolveStrictSurplusRemainingQty", context, sku);

        Assertions.assertEquals(20, remainingQty);
    }

    /**
     * 用例说明：首次上机时未识别为收尾的绑定，跨日后只要真实余量已能被当日物理产能收完，
     * 也必须立即进入严格收尾，不能继续使用首次绑定时固化的非收尾标签。
     */
    @Test
    public void shouldEnterCarryOverStrictEndingByRealtimeCapacityWithoutFrozenEndingFlag() {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        SkuScheduleDTO sku = new SkuScheduleDTO();
        sku.setMaterialCode("3302002343");
        ActiveMachineBinding nonEndingBinding = new ActiveMachineBinding(
                "3302002343/T", sku, "K2204", null,
                new LhScheduleResult(), null, false);

        Boolean finalBlock = ReflectionTestUtils.invokeMethod(
                strategy, "isFinalStrictCarryOverBlock",
                nonEndingBinding, 60, 100);

        Assertions.assertTrue(Boolean.TRUE.equals(finalBlock),
                "实时余量可在当前物理块收完时必须严格收口");
    }

    /**
     * 用例说明：单控整机最终收尾块必须按L/R物理组合计产能判断，
     * 不能只使用主侧单机产能导致提前进入严格裁剪。
     */
    @Test
    public void shouldUseWholeSingleControlCapacityForCarryOverFinalBlock() {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        SkuScheduleDTO sku = new SkuScheduleDTO();
        sku.setMaterialCode("3302001593");
        LhScheduleResult primaryResult = new LhScheduleResult();
        LhScheduleResult pairResult = new LhScheduleResult();
        ActiveMachineBinding endingBinding = new ActiveMachineBinding(
                "3302001593/S", sku, "K1809L", "K1809R",
                primaryResult, pairResult, true);

        Boolean finalBlock = ReflectionTestUtils.invokeMethod(
                strategy, "isFinalStrictCarryOverBlock",
                endingBinding, 96, 48);
        Boolean middleBlock = ReflectionTestUtils.invokeMethod(
                strategy, "isFinalStrictCarryOverBlock",
                endingBinding, 97, 48);

        Assertions.assertTrue(Boolean.TRUE.equals(finalBlock));
        Assertions.assertFalse(Boolean.TRUE.equals(middleBlock));
    }

    /**
     * 用例说明：单控整机在严格收尾场景下必须左右成对落地。
     * 当 dayN 仅剩奇数额度时，只能先消费可均分的偶数额度，不能先消费奇数再把 L/R 结果向下取整，
     * 否则日计划账本会比实际整机排产多扣 1 条。
     *
     * @throws Exception 反射调用异常
     */
    @Test
    public void shouldKeepSingleControlQuotaConsistentWhenStrictQuotaIsOdd() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        injectTargetScheduleQtyResolver(strategy, new TargetScheduleQtyResolver());
        LhScheduleContext context = new LhScheduleContext();
        context.setScheduleDate(toDate(2026, 7, 25, 0, 0, 0));
        List<LhShiftConfigVO> shifts = LhScheduleTimeUtil.buildDefaultScheduleShifts(
                context, context.getScheduleDate());
        LocalDate productionDate = resolveWorkDate(shifts.get(0));

        SkuScheduleDTO sku = new SkuScheduleDTO();
        sku.setMaterialCode("3302002999");
        sku.setConstructionStage(ConstructionStageEnum.TRIAL.getCode());
        sku.setStrictTargetQty(true);
        sku.setPendingQty(20);
        sku.setSurplusQty(20);
        sku.setTargetScheduleQty(20);
        Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap = new LinkedHashMap<>(4);
        SkuDailyPlanQuotaDTO quota = buildQuota(15, 15);
        quota.setMaterialCode(sku.getMaterialCode());
        quota.setProductionDate(productionDate);
        quotaMap.put(productionDate, quota);
        sku.setDailyPlanQuotaMap(quotaMap);
        context.getSkuProductionRemainingQtyMap().put(sku.getMaterialCode(), 20);

        LhScheduleResult primaryResult = buildCarryOverCapsuleResult("K1501L");
        primaryResult.setMaterialCode(sku.getMaterialCode());
        primaryResult.setIsEnd("1");
        LhScheduleResult pairResult = buildCarryOverCapsuleResult("K1501R");
        pairResult.setMaterialCode(sku.getMaterialCode());
        pairResult.setIsEnd("1");
        LhShiftConfigVO firstShift = shifts.get(0);
        ShiftFieldUtil.setShiftPlanQty(primaryResult, firstShift.getShiftIndex(), 10,
                firstShift.getShiftStartDateTime(), firstShift.getShiftEndDateTime());
        ShiftFieldUtil.setShiftPlanQty(pairResult, firstShift.getShiftIndex(), 10,
                firstShift.getShiftStartDateTime(), firstShift.getShiftEndDateTime());
        ShiftFieldUtil.syncDailyPlanQty(primaryResult);
        ShiftFieldUtil.syncDailyPlanQty(pairResult);

        Method method = NewSpecProductionStrategy.class.getDeclaredMethod(
                "applyWholeSingleControlBlockToDailyQuota",
                LhScheduleContext.class,
                SkuScheduleDTO.class,
                LhScheduleResult.class,
                LhScheduleResult.class,
                List.class,
                boolean.class);
        method.setAccessible(true);

        Integer actualQty = (Integer) method.invoke(
                strategy, context, sku, primaryResult, pairResult, shifts, false);

        Assertions.assertEquals(14, actualQty.intValue(), "严格单控整机只能落地可均分到L/R两侧的偶数数量");
        Assertions.assertEquals(7,
                ShiftFieldUtil.getShiftPlanQty(primaryResult, firstShift.getShiftIndex()).intValue(),
                "主侧班产必须与整机账本消费数量保持一半关系");
        Assertions.assertEquals(7,
                ShiftFieldUtil.getShiftPlanQty(pairResult, firstShift.getShiftIndex()).intValue(),
                "配对侧班产必须与主侧完全一致");
        Assertions.assertEquals(14, quota.getScheduledQty(), "dayN账本只能扣减实际整机落地的14条");
        Assertions.assertEquals(1, quota.getRemainingQty(), "未能成对落地的1条额度必须保留给后续排程");
        Assertions.assertEquals(14, quota.getActualQty(), "生产日期实际消费额度必须与整机实际排产量一致");
        Assertions.assertEquals(6, context.getSkuProductionRemainingQtyMap().get(sku.getMaterialCode()).intValue(),
                "SKU实际消费账本也只能扣除实际落地的14条");
    }

    /**
     * 用例说明：辅机是按后续 dayN 需求扩出来时，即使首日目标已满足，也不能把辅机首个承接班次释放掉。
     *
     * @throws Exception 反射调用异常
     */
    @Test
    public void shouldKeepAuxFirstShiftWhenFutureDayDemandStillNeedsAddedMachine() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        LhScheduleContext context = new LhScheduleContext();
        Date scheduleDate = toDate(2026, 6, 1, 0, 0, 0);
        context.setScheduleDate(scheduleDate);
        List<LhShiftConfigVO> shifts = LhScheduleTimeUtil.buildDefaultScheduleShifts(context, scheduleDate);
        context.setScheduleWindowShifts(shifts);
        context.setMachineScheduleMap(new LinkedHashMap<String, MachineScheduleDTO>(4));

        SkuScheduleDTO sku = new SkuScheduleDTO();
        sku.setMaterialCode("3302002661");
        sku.setConstructionStage(ConstructionStageEnum.FORMAL.getCode());
        sku.setShiftCapacity(16);
        Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap = new LinkedHashMap<>(4);
        quotaMap.put(resolveWorkDate(shifts.get(0)), buildQuota(8, 8));
        quotaMap.put(resolveWorkDate(shifts.get(3)), buildQuota(60, 60));
        quotaMap.put(resolveWorkDate(shifts.get(6)), buildQuota(60, 60));
        sku.setDailyPlanQuotaMap(quotaMap);

        LhScheduleResult primaryResult = buildSameSkuResult("K1313", 16);
        for (int shiftIndex = 2; shiftIndex <= 8; shiftIndex++) {
            ShiftFieldUtil.setShiftPlanQty(primaryResult, shiftIndex, 16,
                    shifts.get(shiftIndex - 1).getShiftStartDateTime(),
                    shifts.get(shiftIndex - 1).getShiftEndDateTime());
        }
        ShiftFieldUtil.syncDailyPlanQty(primaryResult);

        LhScheduleResult auxResult = buildSameSkuResult("K1405", 16);
        ShiftFieldUtil.setShiftPlanQty(auxResult, 2, 16,
                shifts.get(1).getShiftStartDateTime(), shifts.get(1).getShiftEndDateTime());
        ShiftFieldUtil.setShiftPlanQty(auxResult, 3, 16,
                shifts.get(2).getShiftStartDateTime(), shifts.get(2).getShiftEndDateTime());
        ShiftFieldUtil.syncDailyPlanQty(auxResult);

        context.getMachineScheduleMap().put("K1313", buildMachine("K1313", 1));
        context.getMachineScheduleMap().put("K1405", buildMachine("K1405", 1));

        Method method = NewSpecProductionStrategy.class.getDeclaredMethod(
                "releaseAuxiliaryMachineForNonEnding",
                LhScheduleContext.class,
                SkuScheduleDTO.class,
                List.class,
                ProductionQuantityPolicy.class,
                List.class);
        method.setAccessible(true);

        boolean changed = (Boolean) method.invoke(strategy, context, sku, shifts,
                buildFormalNonEndingPolicy(), Arrays.asList(primaryResult, auxResult));

        Assertions.assertFalse(changed, "3302002661 这类为后续 dayN 扩出的辅机，不应触发首日辅助机台释放");
        Assertions.assertEquals(16, ShiftFieldUtil.getShiftPlanQty(auxResult, 2).intValue());
        Assertions.assertEquals(16, ShiftFieldUtil.getShiftPlanQty(auxResult, 3).intValue());
    }

    /**
     * 用例说明：跨日续排把临时增量合并回原结果时，必须保留“换胶囊”班次事实。
     * 否则下一次胶囊运行态重建会认为首次跨限尚未处理，并在后续班次重复固定扣量。
     *
     * @throws Exception 反射调用异常
     */
    @Test
    public void shouldKeepCapsuleReplacementAnalysisAfterCarryOverDeltaMerge() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        LhScheduleContext context = new LhScheduleContext();
        context.setScheduleDate(toDate(2026, 7, 25, 0, 0, 0));
        context.setScheduleConfig(new LhScheduleConfig(new LinkedHashMap<String, String>(0)));
        List<LhShiftConfigVO> shifts =
                LhScheduleTimeUtil.buildDefaultScheduleShifts(context, context.getScheduleDate());
        context.setScheduleWindowShifts(shifts);

        LhScheduleResult targetResult = buildCarryOverCapsuleResult("K1101");
        LhScheduleResult deltaResult = buildCarryOverCapsuleResult("K1101");
        LhShiftConfigVO thirdShift = shifts.get(2);
        ShiftFieldUtil.setShiftPlanQty(deltaResult, thirdShift.getShiftIndex(), 14,
                thirdShift.getShiftStartDateTime(), thirdShift.getShiftEndDateTime());
        ShiftFieldUtil.setShiftAnalysis(targetResult, thirdShift.getShiftIndex(), "干冰清洗");
        ShiftFieldUtil.setShiftAnalysis(deltaResult, thirdShift.getShiftIndex(), "干冰清洗,换胶囊");

        invokeMergeDayShiftDelta(strategy, context, targetResult, deltaResult,
                Collections.singletonList(thirdShift), shifts);

        Assertions.assertEquals("干冰清洗,换胶囊",
                ShiftFieldUtil.getShiftAnalysis(targetResult, thirdShift.getShiftIndex()));

        /*
         * 以已合并的原结果重建胶囊运行态，再安排下一班。若合并遗漏“换胶囊”，
         * 此处会重新触发首次跨限扣量和备注；保留事实后应只按正常产量继续累计。
         */
        LhRepairCapsule capsule = new LhRepairCapsule();
        capsule.setLhCode("K1101");
        capsule.setReplaceCapsuleCount(440);
        capsule.setReplaceCapsuleCount2(0);
        context.getCapsuleUsageMap().put("K1101", capsule);
        context.getScheduleResultList().add(targetResult);
        CapsuleReplacementRuleService capsuleRuleService = new CapsuleReplacementRuleService();
        capsuleRuleService.rebuildRuntimeState(context, null);
        Assertions.assertTrue(context.getCapsuleThresholdHandledMachineSet().contains("K1101"));

        LhScheduleResult nextShiftResult = buildCarryOverCapsuleResult("K1101");
        int actualQty = capsuleRuleService.resolveActualPlanQty(
                context, nextShiftResult, shifts.get(3), 16, "跨日续排回归");
        Assertions.assertEquals(16, actualQty);
        Assertions.assertNull(ShiftFieldUtil.getShiftAnalysis(nextShiftResult, shifts.get(3).getShiftIndex()));
    }

    /**
     * 用例说明：按日循环已经确定当前业务日时，模具到货等日期敏感校验必须优先使用该业务日，
     * 不得被未来 dayN 加机日期提前覆盖。
     *
     * @throws Exception 反射调用异常
     */
    @Test
    public void shouldPreferCurrentBusinessDateWhenResolvingMouldAvailabilityDate() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        LhScheduleContext context = new LhScheduleContext();
        context.setScheduleDate(toDate(2026, 7, 25, 0, 0, 0));

        Method method = NewSpecProductionStrategy.class.getDeclaredMethod(
                "resolveCurrentScheduleDate", LhScheduleContext.class, LocalDate.class,
                SkuScheduleDTO.class, LocalDate.class);
        method.setAccessible(true);
        Date resolvedDate = (Date) method.invoke(strategy, context,
                LocalDate.of(2026, 7, 26), new SkuScheduleDTO(), LocalDate.of(2026, 7, 27));

        Assertions.assertEquals(toDate(2026, 7, 26, 0, 0, 0), resolvedDate);
    }

    /**
     * 用例说明：按日编排只接受完整的 2/3/3 八班窗口，缺班时必须在资源消费前中断。
     *
     * @throws Exception 反射调用异常
     */
    @Test
    public void shouldRejectIncompleteDayDrivenShiftLayout() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        LhScheduleContext context = new LhScheduleContext();
        context.setScheduleDate(toDate(2026, 7, 25, 0, 0, 0));
        List<LhShiftConfigVO> shifts = new ArrayList<LhShiftConfigVO>(
                LhScheduleTimeUtil.buildDefaultScheduleShifts(context, context.getScheduleDate()));
        shifts.remove(shifts.size() - 1);
        LinkedHashMap<LocalDate, List<LhShiftConfigVO>> dayShiftMap =
                LhScheduleTimeUtil.groupByWorkDate(shifts);

        Method method = NewSpecProductionStrategy.class.getDeclaredMethod(
                "validateDayDrivenShiftLayout", List.class, LinkedHashMap.class);
        method.setAccessible(true);
        InvocationTargetException exception = Assertions.assertThrows(InvocationTargetException.class,
                () -> method.invoke(strategy, shifts, dayShiftMap));

        Assertions.assertTrue(exception.getCause() instanceof IllegalStateException);
        Assertions.assertTrue(exception.getCause().getMessage().contains("必须提供8个班次"));
    }

    /**
     * 用例说明：前三个日内阶段即使满足未来日计划条件，也不得取得提前生产准入。
     *
     * @throws Exception 反射调用异常
     */
    @Test
    public void shouldRejectEarlyProductionBeforeEarlyProductionPhase() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        Method method = NewSpecProductionStrategy.class.getDeclaredMethod(
                "resolveEarlyProductionDecision", LhScheduleContext.class, SkuScheduleDTO.class,
                Date.class, List.class, boolean.class, DailySchedulePhase.class);
        method.setAccessible(true);

        EarlyProductionDecision decision = (EarlyProductionDecision) method.invoke(strategy,
                new LhScheduleContext(), new SkuScheduleDTO(), toDate(2026, 7, 25, 8, 0, 0),
                Collections.<LhShiftConfigVO>emptyList(), false, DailySchedulePhase.ADD_MACHINE);

        Assertions.assertFalse(decision.isEarlyProduction());
    }

    private void injectTargetScheduleQtyResolver(NewSpecProductionStrategy strategy,
                                                 TargetScheduleQtyResolver resolver) throws Exception {
        Field field = NewSpecProductionStrategy.class.getDeclaredField("targetScheduleQtyResolver");
        field.setAccessible(true);
        field.set(strategy, resolver);
    }

    /**
     * 通过反射调用跨日结果增量合并入口，验证真实合并分支的班次备注处理。
     *
     * @param strategy 新增排产策略
     * @param context 排程上下文
     * @param targetResult 原跨日结果
     * @param deltaResult 当前日临时增量结果
     * @param dayShifts 当前业务日班次
     * @param allShifts 完整排程窗口班次
     * @throws Exception 反射调用异常
     */
    private void invokeMergeDayShiftDelta(NewSpecProductionStrategy strategy,
                                          LhScheduleContext context,
                                          LhScheduleResult targetResult,
                                          LhScheduleResult deltaResult,
                                          List<LhShiftConfigVO> dayShifts,
                                          List<LhShiftConfigVO> allShifts) throws Exception {
        Method method = NewSpecProductionStrategy.class.getDeclaredMethod(
                "mergeDayShiftDelta", LhScheduleContext.class, LhScheduleResult.class,
                LhScheduleResult.class, List.class, List.class);
        method.setAccessible(true);
        method.invoke(strategy, context, targetResult, deltaResult, dayShifts, allShifts);
    }

    private MachineScheduleDTO invokeSelectCandidateMachine(NewSpecProductionStrategy strategy,
                                                            LhScheduleContext context,
                                                            SkuScheduleDTO sku,
                                                            List<MachineScheduleDTO> candidates,
                                                            Set<String> excludedMachineCodes,
                                                            IMachineMatchStrategy machineMatch,
                                                            MachineScheduleDTO preferredTrialMachine,
                                                            ProductionQuantityPolicy quantityPolicy) throws Exception {
        Method method = NewSpecProductionStrategy.class.getDeclaredMethod(
                "selectCandidateMachine",
                LhScheduleContext.class,
                SkuScheduleDTO.class,
                NewSpecCandidateCache.class,
                List.class,
                Set.class,
                IMachineMatchStrategy.class,
                MachineScheduleDTO.class,
                ProductionQuantityPolicy.class,
                List.class);
        method.setAccessible(true);
        NewSpecCandidateCache candidateCache = NewSpecCandidateCache.from(candidates,
                candidate -> Boolean.TRUE.equals(ReflectionTestUtils.invokeMethod(
                        strategy, "isSingleControlMachine", context, candidate.getMachineCode())));
        List<MachineScheduleDTO> orderedCandidates =
                new ArrayList<MachineScheduleDTO>(candidates.size());
        return (MachineScheduleDTO) method.invoke(
                strategy, context, sku, candidateCache, new ArrayList<MachineScheduleDTO>(candidates),
                new HashSet<String>(excludedMachineCodes), machineMatch, preferredTrialMachine,
                quantityPolicy, orderedCandidates);
    }

    private MachineScheduleDTO invokeSelectCandidateMachineFromScopedList(NewSpecProductionStrategy strategy,
                                                                          LhScheduleContext context,
                                                                          SkuScheduleDTO sku,
                                                                          List<MachineScheduleDTO> candidates,
                                                                          IMachineMatchStrategy machineMatch,
                                                                          MachineScheduleDTO preferredTrialMachine,
                                                                          ProductionQuantityPolicy quantityPolicy) throws Exception {
        Method method = NewSpecProductionStrategy.class.getDeclaredMethod(
                "selectCandidateMachineFromScopedList",
                LhScheduleContext.class,
                SkuScheduleDTO.class,
                List.class,
                IMachineMatchStrategy.class,
                MachineScheduleDTO.class,
                ProductionQuantityPolicy.class,
                NewSpecCandidateCache.class);
        method.setAccessible(true);
        NewSpecCandidateCache candidateCache = NewSpecCandidateCache.from(candidates, candidate -> false);
        return (MachineScheduleDTO) method.invoke(
                strategy, context, sku, candidates, machineMatch, preferredTrialMachine,
                quantityPolicy, candidateCache);
    }

    private LhScheduleContext buildTodayIdleContext() {
        LhScheduleContext context = new LhScheduleContext();
        context.setScheduleDate(toDate(2026, 4, 21, 7, 0, 0));
        context.setScheduleTargetDate(context.getScheduleDate());
        context.setScheduleWindowShifts(LhScheduleTimeUtil.buildDefaultScheduleShifts(context, context.getScheduleDate()));
        context.setMachineAssignmentMap(new LinkedHashMap<String, List<LhScheduleResult>>());
        return context;
    }

    private SkuScheduleDTO buildFirstDayDemandSku(String materialCode, int demandQty) {
        SkuScheduleDTO sku = new SkuScheduleDTO();
        sku.setMaterialCode(materialCode);
        sku.setDailyPlanQty(demandQty);
        sku.setRemainingScheduleQty(demandQty);
        sku.setTargetScheduleQty(demandQty);
        sku.setConstructionStage(ConstructionStageEnum.FORMAL.getCode());
        return sku;
    }

    private MachineScheduleDTO buildReadyMachine(String machineCode, Date estimatedEndTime) {
        MachineScheduleDTO machine = buildMachine(machineCode, 1);
        machine.setEstimatedEndTime(estimatedEndTime);
        machine.setStatus("1");
        return machine;
    }

    private LhScheduleResult buildAssignedResult(String machineCode, String materialCode, Date specEndTime) {
        LhScheduleResult result = new LhScheduleResult();
        result.setLhMachineCode(machineCode);
        result.setMaterialCode(materialCode);
        result.setScheduleType("02");
        result.setDailyPlanQty(16);
        result.setSpecEndTime(specEndTime);
        return result;
    }

    /**
     * 构造已占用指定业务日首班的最小排程结果。
     *
     * @param materialCode 物料编码
     * @param productStatus 产品状态
     * @param structureName 结构名称
     * @param machineCode 机台编码
     * @param class1PlanQty 首班计划量
     * @return 可用于结构/SKU机台统计重建的排程结果
     */
    private LhScheduleResult buildScheduledMachineResult(
            String materialCode,
            String productStatus,
            String structureName,
            String machineCode,
            int class1PlanQty) {
        LhScheduleResult result = new LhScheduleResult();
        result.setMaterialCode(materialCode);
        result.setProductStatus(productStatus);
        result.setStructureName(structureName);
        result.setLhMachineCode(machineCode);
        result.setClass1PlanQty(class1PlanQty);
        return result;
    }

    private MouldResourceAllocationResult invokeTryAllocateMouldResourceForAddMachine(
            NewSpecProductionStrategy strategy,
            LhScheduleContext context,
            SkuScheduleDTO sku,
            MachineScheduleDTO candidateMachine,
            int originalAddMachineCount,
            int actualAllowedAddMachineCount) throws Exception {
        Method method = NewSpecProductionStrategy.class.getDeclaredMethod(
                "tryAllocateMouldResourceForAddMachine",
                LhScheduleContext.class,
                SkuScheduleDTO.class,
                MachineScheduleDTO.class,
                int.class,
                int.class);
        method.setAccessible(true);
        return (MouldResourceAllocationResult) method.invoke(
                strategy, context, sku, candidateMachine, originalAddMachineCount, actualAllowedAddMachineCount);
    }

    private void invokeRollbackMouldResourceAllocation(NewSpecProductionStrategy strategy,
                                                       LhScheduleContext context,
                                                       SkuScheduleDTO sku,
                                                       MouldResourceAllocationResult allocationResult) throws Exception {
        Method method = NewSpecProductionStrategy.class.getDeclaredMethod(
                "rollbackMouldResourceAllocation",
                LhScheduleContext.class,
                SkuScheduleDTO.class,
                MouldResourceAllocationResult.class);
        method.setAccessible(true);
        method.invoke(strategy, context, sku, allocationResult);
    }

    private LhScheduleContext buildMouldResourceContext(String materialCode,
                                                        List<String> mouldCodeList,
                                                        List<MachineScheduleDTO> machineList) {
        LhScheduleContext context = new LhScheduleContext();
        Map<String, List<MdmSkuMouldRel>> skuMouldRelMap = new LinkedHashMap<>(4);
        List<MdmSkuMouldRel> relList = new ArrayList<MdmSkuMouldRel>(mouldCodeList.size());
        Map<String, MdmModelInfo> modelInfoMap = new LinkedHashMap<>(4);
        for (String mouldCode : mouldCodeList) {
            MdmSkuMouldRel rel = new MdmSkuMouldRel();
            rel.setMaterialCode(materialCode);
            rel.setMouldCode(mouldCode);
            relList.add(rel);
            MdmModelInfo modelInfo = new MdmModelInfo();
            modelInfo.setMouldCode(mouldCode);
            modelInfo.setMouldStatus(1);
            modelInfoMap.put(mouldCode, modelInfo);
        }
        skuMouldRelMap.put(materialCode, relList);
        Map<String, MachineScheduleDTO> machineScheduleMap = new LinkedHashMap<>(4);
        for (MachineScheduleDTO machine : machineList) {
            machineScheduleMap.put(machine.getMachineCode(), machine);
        }
        context.setSkuMouldRelMap(skuMouldRelMap);
        context.setModelInfoMap(modelInfoMap);
        context.setMachineScheduleMap(machineScheduleMap);
        return context;
    }

    private MachineScheduleDTO buildMachine(String machineCode, int maxMouldNum) {
        MachineScheduleDTO machine = new MachineScheduleDTO();
        machine.setMachineCode(machineCode);
        machine.setMaxMoldNum(maxMouldNum);
        return machine;
    }

    /**
     * 构建用于跨日换胶囊回归的最小结果行。
     *
     * @param machineCode 硫化机台编码
     * @return 包含班产计算必要字段的排程结果
     */
    private LhScheduleResult buildCarryOverCapsuleResult(String machineCode) {
        LhScheduleResult result = new LhScheduleResult();
        result.setLhMachineCode(machineCode);
        result.setMaterialCode("3302000001");
        result.setProductStatus("S");
        result.setMouldQty(1);
        result.setLhTime(3600);
        return result;
    }

    /**
     * 构造提前生产跨月测试所需月计划。
     *
     * @param materialCode 物料编码
     * @param year 年份
     * @param month 月份
     * @param totalQty 月计划 TOTAL_QTY
     * @return 月计划
     */
    private FactoryMonthPlanProductionFinalResult buildMonthPlan(
            String materialCode,
            int year,
            int month,
            int totalQty) {
        FactoryMonthPlanProductionFinalResult plan =
                new FactoryMonthPlanProductionFinalResult();
        plan.setFactoryCode("116");
        plan.setMaterialCode(materialCode);
        plan.setProductStatus("S");
        plan.setStructureName("285/75R24.5");
        plan.setConstructionStage("03");
        plan.setYear(year);
        plan.setMonth(month);
        plan.setTotalQty(totalQty);
        return plan;
    }

    private SkuDailyPlanQuotaDTO buildQuota(int remainingQty) {
        SkuDailyPlanQuotaDTO quota = new SkuDailyPlanQuotaDTO();
        quota.setRemainingQty(remainingQty);
        return quota;
    }

    private SkuDailyPlanQuotaDTO buildQuota(int dayPlanQty, int remainingQty) {
        SkuDailyPlanQuotaDTO quota = new SkuDailyPlanQuotaDTO();
        quota.setDayPlanQty(dayPlanQty);
        quota.setRemainingQty(remainingQty);
        return quota;
    }

    private LocalDate resolveWorkDate(LhShiftConfigVO shift) {
        return shift.getWorkDate().toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDate();
    }

    private LhScheduleResult buildSameSkuResult(String machineCode, int singleMouldShiftQty) {
        LhScheduleResult result = new LhScheduleResult();
        result.setLhMachineCode(machineCode);
        result.setSingleMouldShiftQty(singleMouldShiftQty);
        result.setLhTime(3600);
        result.setMouldQty(1);
        result.setIsEnd("0");
        return result;
    }

    private ProductionQuantityPolicy buildFormalNonEndingPolicy() {
        ProductionQuantityPolicy policy = new ProductionQuantityPolicy();
        policy.setEnding(false);
        policy.setTrialProduction(false);
        policy.setTrialRun(false);
        policy.setNormalProduction(true);
        policy.setAllowFillStartedShift(true);
        policy.setStrictUpperLimit(false);
        policy.setFullRunForNonTailMachine(true);
        return policy;
    }

    private Date toDate(int year, int month, int day, int hour, int minute, int second) {
        java.util.Calendar calendar = java.util.Calendar.getInstance();
        calendar.set(java.util.Calendar.YEAR, year);
        calendar.set(java.util.Calendar.MONTH, month - 1);
        calendar.set(java.util.Calendar.DAY_OF_MONTH, day);
        calendar.set(java.util.Calendar.HOUR_OF_DAY, hour);
        calendar.set(java.util.Calendar.MINUTE, minute);
        calendar.set(java.util.Calendar.SECOND, second);
        calendar.set(java.util.Calendar.MILLISECOND, 0);
        return calendar.getTime();
    }

    /**
     * 机台匹配桩：始终返回当前候选顺序的第一台。
     */
    private static class FirstCandidateMachineMatchStrategy implements IMachineMatchStrategy {

        @Override
        public List<MachineScheduleDTO> matchMachines(LhScheduleContext context, SkuScheduleDTO sku) {
            return Collections.emptyList();
        }

        @Override
        public MachineScheduleDTO selectBestMachine(LhScheduleContext context,
                                                    SkuScheduleDTO sku,
                                                    List<MachineScheduleDTO> candidates,
                                                    Set<String> excludedMachineCodes) {
            for (MachineScheduleDTO candidate : candidates) {
                if (candidate != null
                        && !excludedMachineCodes.contains(candidate.getMachineCode())) {
                    return candidate;
                }
            }
            return null;
        }

        @Override
        public void traceEnabledMachineSort(LhScheduleContext context) {
            // 测试桩，无需实现
        }
    }
}
