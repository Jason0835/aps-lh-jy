package com.zlt.aps.lh.engine.strategy.impl;

import com.zlt.aps.lh.api.domain.dto.MachineScheduleDTO;
import com.zlt.aps.lh.api.domain.dto.SkuDailyPlanQuotaDTO;
import com.zlt.aps.lh.api.domain.dto.SkuScheduleDTO;
import com.zlt.aps.lh.api.domain.entity.LhScheduleResult;
import com.zlt.aps.lh.api.domain.vo.LhShiftConfigVO;
import com.zlt.aps.lh.api.enums.ConstructionStageEnum;
import com.zlt.aps.lh.component.TargetScheduleQtyResolver;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.engine.strategy.IMachineMatchStrategy;
import com.zlt.aps.lh.engine.strategy.support.MouldResourceAllocationResult;
import com.zlt.aps.lh.engine.strategy.support.ProductionQuantityPolicy;
import com.zlt.aps.lh.util.LhScheduleTimeUtil;
import com.zlt.aps.lh.util.ShiftFieldUtil;
import com.zlt.aps.mdm.api.domain.entity.MdmModelInfo;
import com.zlt.aps.mdm.api.domain.entity.MdmSkuMouldRel;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.lang.reflect.Field;
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
                                                            MachineScheduleDTO machine) {
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
                                                            MachineScheduleDTO machine) {
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

        Method method = NewSpecProductionStrategy.class.getDeclaredMethod(
                "resolveSchedulableRemainingQty", SkuScheduleDTO.class);
        method.setAccessible(true);

        Integer remainingQty = (Integer) method.invoke(strategy, sku);

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

        Method method = NewSpecProductionStrategy.class.getDeclaredMethod(
                "resolveSchedulableRemainingQty", SkuScheduleDTO.class);
        method.setAccessible(true);

        Integer remainingQty = (Integer) method.invoke(strategy, sku);

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

        resolver.upsizeEndingTargetQty(new LhScheduleContext(), sku);

        Method method = NewSpecProductionStrategy.class.getDeclaredMethod(
                "resolveSchedulableRemainingQty", SkuScheduleDTO.class);
        method.setAccessible(true);
        Integer remainingQty = (Integer) method.invoke(strategy, sku);

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
                List.class);
        method.setAccessible(true);

        Integer scheduledQty = (Integer) method.invoke(strategy, context, sku, result, shifts);

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
                List.class);
        method.setAccessible(true);

        Integer scheduledQty = (Integer) method.invoke(strategy, context, sku, result, shifts);

        Assertions.assertEquals(46, scheduledQty.intValue());
        Assertions.assertEquals(46, ShiftFieldUtil.getShiftPlanQty(result, shifts.get(2).getShiftIndex()).intValue());
        Assertions.assertEquals(0, sku.getShiftFillOverQty());
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

    private void injectTargetScheduleQtyResolver(NewSpecProductionStrategy strategy,
                                                 TargetScheduleQtyResolver resolver) throws Exception {
        Field field = NewSpecProductionStrategy.class.getDeclaredField("targetScheduleQtyResolver");
        field.setAccessible(true);
        field.set(strategy, resolver);
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
                List.class,
                Set.class,
                IMachineMatchStrategy.class,
                MachineScheduleDTO.class,
                ProductionQuantityPolicy.class);
        method.setAccessible(true);
        return (MachineScheduleDTO) method.invoke(
                strategy, context, sku, candidates, new HashSet<String>(excludedMachineCodes),
                machineMatch, preferredTrialMachine, quantityPolicy);
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
    }
}
