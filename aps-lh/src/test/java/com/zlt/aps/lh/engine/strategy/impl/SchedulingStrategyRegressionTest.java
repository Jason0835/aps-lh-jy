package com.zlt.aps.lh.engine.strategy.impl;

import com.zlt.aps.lh.api.constant.LhScheduleParamConstant;
import com.zlt.aps.lh.api.domain.dto.SkuDailyPlanQuotaDTO;
import com.zlt.aps.lh.api.domain.dto.MachineScheduleDTO;
import com.zlt.aps.lh.api.domain.dto.SkuScheduleDTO;
import com.zlt.aps.lh.api.domain.entity.LhMachineOnlineInfo;
import com.zlt.aps.lh.api.domain.entity.LhScheduleResult;
import com.zlt.aps.lh.api.domain.entity.LhUnscheduledResult;
import com.zlt.aps.lh.api.domain.vo.LhShiftConfigVO;
import com.zlt.aps.lh.api.enums.ConstructionStageEnum;
import com.zlt.aps.lh.api.enums.ScheduleTypeEnum;
import com.zlt.aps.lh.api.enums.SkuScheduleSourceTypeEnum;
import com.zlt.aps.lh.api.enums.SkuTagEnum;
import com.zlt.aps.lh.component.OrderNoGenerator;
import com.zlt.aps.lh.component.TargetScheduleQtyResolver;
import com.zlt.aps.lh.context.LhScheduleConfig;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.engine.strategy.ICapacityCalculateStrategy;
import com.zlt.aps.lh.engine.strategy.IEndingJudgmentStrategy;
import com.zlt.aps.lh.engine.strategy.IMouldChangeBalanceStrategy;
import com.zlt.aps.lh.engine.strategy.support.DailyMachineCapacityDayDecision;
import com.zlt.aps.lh.engine.strategy.support.DailyMachineCapacitySimulationRequest;
import com.zlt.aps.lh.engine.strategy.support.DailyMachineCapacitySimulationResult;
import com.zlt.aps.lh.engine.strategy.support.DailyMachineCapacitySimulationUtil;
import com.zlt.aps.lh.engine.strategy.support.DailyMachineExpansionPlanner;
import com.zlt.aps.lh.engine.strategy.support.DailyMachineShortageQuotaPlan;
import com.zlt.aps.lh.engine.strategy.support.MachineProductionSegment;
import com.zlt.aps.lh.engine.strategy.support.MachineScheduleRole;
import com.zlt.aps.lh.engine.strategy.support.MouldResourceAllocationResult;
import com.zlt.aps.lh.engine.strategy.support.ProductionQuantityPolicy;
import com.zlt.aps.lh.util.LhScheduleTimeUtil;
import com.zlt.aps.lh.util.ShiftFieldUtil;
import com.zlt.aps.mdm.api.domain.entity.MdmSkuLhCapacity;
import com.zlt.aps.mdm.api.domain.entity.MdmSkuMouldRel;
import com.zlt.aps.mp.api.domain.entity.FactoryMonthPlanProductionFinalResult;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Date;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 排程策略回归测试。
 *
 * @author APS
 */
public class SchedulingStrategyRegressionTest {

    /**
     * 共用胎胚启用换模均衡时，早班次数已达阈值8次，应顺延到中班执行。
     */
    @Test
    public void shouldBalanceSharedEmbryoChangeoverWhenMorningExceedsLimit() {
        DefaultMouldChangeBalanceStrategy strategy = new DefaultMouldChangeBalanceStrategy();
        LhScheduleContext context = buildChangeoverBalanceContext();
        context.getDailyMouldChangeCountMap().put("2026-06-01", new int[]{8, 2});
        SkuScheduleDTO sku = buildChangeoverSku("3302001001", "E001");
        context.getMaterialSharedEmbryoMap().put(sku.getMaterialCode(), true);

        Date allocatedTime = strategy.allocateMouldChange(
                context, "K1101", dateTime(2026, 6, 1, 8, 0), 1, sku, "新增换模");

        Assertions.assertEquals(dateTime(2026, 6, 1, 14, 0), allocatedTime);
        Assertions.assertArrayEquals(new int[]{8, 3}, context.getDailyMouldChangeCountMap().get("2026-06-01"));
    }

    /**
     * 共用胎胚早班次数未达阈值8次时，仍留在早班，不挪到中班。
     */
    @Test
    public void shouldKeepSharedEmbryoInMorningWhenBelowLimit() {
        DefaultMouldChangeBalanceStrategy strategy = new DefaultMouldChangeBalanceStrategy();
        LhScheduleContext context = buildChangeoverBalanceContext();
        context.getDailyMouldChangeCountMap().put("2026-06-01", new int[]{6, 3});
        SkuScheduleDTO sku = buildChangeoverSku("3302001018", "E018");
        context.getMaterialSharedEmbryoMap().put(sku.getMaterialCode(), true);

        Date allocatedTime = strategy.allocateMouldChange(
                context, "K1118", dateTime(2026, 6, 1, 8, 0), 1, sku, "新增换模");

        Assertions.assertEquals(dateTime(2026, 6, 1, 8, 0), allocatedTime);
        Assertions.assertArrayEquals(new int[]{7, 3}, context.getDailyMouldChangeCountMap().get("2026-06-01"));
    }
    /**
     * 共用胎胚原本落中班，中班次数已达参考值7次，不强制挪动。
     */
    @Test
    public void shouldKeepSharedEmbryoInAfternoonWhenAfternoonReachesReference() {
        DefaultMouldChangeBalanceStrategy strategy = new DefaultMouldChangeBalanceStrategy();
        LhScheduleContext context = buildChangeoverBalanceContext();
        context.getDailyMouldChangeCountMap().put("2026-06-01", new int[]{3, 7});
        SkuScheduleDTO sku = buildChangeoverSku("3302001019", "E019");
        context.getMaterialSharedEmbryoMap().put(sku.getMaterialCode(), true);

        Date allocatedTime = strategy.allocateMouldChange(
                context, "K1119", dateTime(2026, 6, 1, 14, 0), 1, sku, "新增换模");

        Assertions.assertEquals(dateTime(2026, 6, 1, 14, 0), allocatedTime);
        Assertions.assertArrayEquals(new int[]{3, 8}, context.getDailyMouldChangeCountMap().get("2026-06-01"));
    }

    /**
     * 单胎胚只受每日总次数限制，不因早班达到均衡参考值而强制挪到中班。
     */
    @Test
    public void shouldKeepSingleEmbryoChangeoverOnCurrentShiftWhenDailyLimitAvailable() {
        DefaultMouldChangeBalanceStrategy strategy = new DefaultMouldChangeBalanceStrategy();
        LhScheduleContext context = buildChangeoverBalanceContext();
        context.getDailyMouldChangeCountMap().put("2026-06-01", new int[]{8, 0});
        SkuScheduleDTO sku = buildChangeoverSku("3302001002", "E002");
        context.getMaterialSharedEmbryoMap().put(sku.getMaterialCode(), false);

        Date allocatedTime = strategy.allocateMouldChange(
                context, "K1102", dateTime(2026, 6, 1, 8, 0), 1, sku, "新增换模");

        Assertions.assertEquals(dateTime(2026, 6, 1, 8, 0), allocatedTime);
        Assertions.assertArrayEquals(new int[]{9, 0}, context.getDailyMouldChangeCountMap().get("2026-06-01"));
    }

    /**
     * 普通换模 8 小时已包含首检，首检数量只归属到换模完成落点班次并占用该班次计划量。
     */
    @Test
    public void shouldAssignFirstInspectionQtyToChangeoverCompletionShiftWithoutIncreasingTargetQty() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        injectNewSpecBuildDependencies(strategy);
        LhScheduleContext context = buildNewSpecFirstInspectionContext();
        MachineScheduleDTO machine = buildNewSpecMachine("K1101");
        context.getMachineScheduleMap().put(machine.getMachineCode(), machine);
        SkuScheduleDTO sku = buildNewSpecFirstInspectionSku(16);

        LhScheduleResult result = invokeBuildNewSpecScheduleResult(
                strategy, context, machine, sku,
                dateTime(2026, 6, 1, 14, 0),
                dateTime(2026, 6, 1, 6, 0),
                dateTime(2026, 6, 1, 14, 0),
                context.getScheduleWindowShifts(), 1, false);

        Assertions.assertEquals(4, ShiftFieldUtil.getShiftPlanQty(result, 1));
        Assertions.assertEquals(12, ShiftFieldUtil.getShiftPlanQty(result, 2));
        Assertions.assertEquals(16, result.getDailyPlanQty());
    }

    /**
     * 试制SKU早班完成换模后，首检和生产必须推迟到同业务日中班，早班只保留换模占用。
     */
    @Test
    public void shouldMoveTrialSkuFirstInspectionAndProductionToAfternoonAfterMorningChangeover() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        injectNewSpecBuildDependencies(strategy);
        LhScheduleContext context = buildNewSpecFirstInspectionContext();
        MachineScheduleDTO machine = buildNewSpecMachine("K1101");
        context.getMachineScheduleMap().put(machine.getMachineCode(), machine);
        SkuScheduleDTO sku = buildNewSpecFirstInspectionSku(16);
        sku.setConstructionStage(ConstructionStageEnum.TRIAL.getCode());

        LhScheduleResult result = invokeBuildNewSpecScheduleResult(
                strategy, context, machine, sku,
                dateTime(2026, 6, 1, 14, 0),
                dateTime(2026, 6, 1, 6, 0),
                dateTime(2026, 6, 1, 14, 0),
                context.getScheduleWindowShifts(), 1, false);

        Assertions.assertEquals(0, resolveShiftPlanQty(result, 1));
        Assertions.assertEquals(16, resolveShiftPlanQty(result, 2));
        Assertions.assertFalse(context.getShiftFirstInspectionCountMap().containsKey("2026-06-01#1"));
        Assertions.assertEquals(1, context.getShiftFirstInspectionCountMap().get("2026-06-01#2").intValue());
    }

    /**
     * 量试、正规、小批量SKU仍按换模完成班次归属首检，不因早班换模完成被强制推迟到中班。
     */
    @Test
    public void shouldKeepNonTrialSkuFirstInspectionOnMorningAfterMorningChangeover() throws Exception {
        assertNewSpecMorningChangeoverKeepsMorning(ConstructionStageEnum.MASS_TRIAL.getCode(), false);
        assertNewSpecMorningChangeoverKeepsMorning(ConstructionStageEnum.FORMAL.getCode(), false);
        assertNewSpecMorningChangeoverKeepsMorning(ConstructionStageEnum.FORMAL.getCode(), true);
    }

    /**
     * 试制SKU中班完成换模时沿用现有中班归属，不需要额外推迟。
     */
    @Test
    public void shouldKeepTrialSkuFirstInspectionOnAfternoonWhenChangeoverCompletesInAfternoon() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        injectNewSpecBuildDependencies(strategy);
        LhScheduleContext context = buildNewSpecFirstInspectionContext();
        MachineScheduleDTO machine = buildNewSpecMachine("K1101");
        context.getMachineScheduleMap().put(machine.getMachineCode(), machine);
        SkuScheduleDTO sku = buildNewSpecFirstInspectionSku(16);
        sku.setConstructionStage(ConstructionStageEnum.TRIAL.getCode());

        LhScheduleResult result = invokeBuildNewSpecScheduleResult(
                strategy, context, machine, sku,
                dateTime(2026, 6, 1, 15, 0),
                dateTime(2026, 6, 1, 14, 0),
                dateTime(2026, 6, 1, 15, 0),
                context.getScheduleWindowShifts(), 1, false);

        Assertions.assertEquals(0, resolveShiftPlanQty(result, 1));
        Assertions.assertEquals(16, resolveShiftPlanQty(result, 2));
        Assertions.assertEquals(1, context.getShiftFirstInspectionCountMap().get("2026-06-01#2").intValue());
    }

    /**
     * 试制SKU早班完成换活字块后，首检同样归属中班，避免S4.4在早班落首检量。
     */
    @Test
    public void shouldMoveTrialSkuTypeBlockFirstInspectionToAfternoonAfterMorningSwitch() throws Exception {
        TypeBlockProductionStrategy strategy = new TypeBlockProductionStrategy();
        injectTypeBlockAppendDependencies(strategy);
        LhScheduleContext context = buildNewSpecFirstInspectionContext();
        MachineScheduleDTO machine = buildNewSpecMachine("K1305");
        context.getMachineScheduleMap().put(machine.getMachineCode(), machine);
        SkuScheduleDTO sku = buildNewSpecFirstInspectionSku(16);
        sku.setConstructionStage(ConstructionStageEnum.TRIAL.getCode());

        LhScheduleResult result = invokeBuildTypeBlockScheduleResult(
                strategy, context, machine, sku,
                dateTime(2026, 6, 1, 14, 0),
                dateTime(2026, 6, 1, 6, 0),
                context.getScheduleWindowShifts(), 1, false);

        Assertions.assertEquals(0, resolveShiftPlanQty(result, 1));
        Assertions.assertEquals(16, resolveShiftPlanQty(result, 2));
        Assertions.assertFalse(context.getShiftFirstInspectionCountMap().containsKey("2026-06-01#1"));
        Assertions.assertEquals(1, context.getShiftFirstInspectionCountMap().get("2026-06-01#2").intValue());
    }

    /**
     * 同一班次内，换模首检数量应按统一顺序取参数：前2台取4，第3台及之后取2，单控机台再折半。
     */
    @Test
    public void shouldApplyOrderedFirstInspectionQtyForChangeoverInSameShift() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        injectNewSpecBuildDependencies(strategy);
        LhScheduleContext context = buildNewSpecFirstInspectionContext();
        Map<String, String> paramMap = new HashMap<String, String>(4);
        paramMap.put("SYS0303002", "4");
        paramMap.put("SYS0303003", "2");
        context.setScheduleConfig(new LhScheduleConfig(paramMap));

        LhScheduleResult firstResult = buildNewSpecFirstInspectionResult(strategy, context,
                buildNewSpecMachine("K1101"), buildNewSpecFirstInspectionSku(16));
        LhScheduleResult secondResult = buildNewSpecFirstInspectionResult(strategy, context,
                buildNewSpecMachine("K1102"), buildNewSpecFirstInspectionSku(16));
        LhScheduleResult thirdResult = buildNewSpecFirstInspectionResult(strategy, context,
                buildNewSpecMachine("K1103"), buildNewSpecFirstInspectionSku(16));
        LhScheduleResult singleControlResult = buildNewSpecFirstInspectionResult(strategy, context,
                buildNewSpecMachine("K1501L"), buildNewSpecFirstInspectionSku(16));

        Assertions.assertEquals(4, ShiftFieldUtil.getShiftPlanQty(firstResult, 1));
        Assertions.assertEquals(4, ShiftFieldUtil.getShiftPlanQty(secondResult, 1));
        Assertions.assertEquals(2, ShiftFieldUtil.getShiftPlanQty(thirdResult, 1));
        Assertions.assertEquals(1, ShiftFieldUtil.getShiftPlanQty(singleControlResult, 1));
        Assertions.assertEquals(4, context.getShiftFirstInspectionCountMap().get("2026-06-01#1").intValue());
    }

    /**
     * T+2 当日换模/换活字块总次数已满时，应返回失败并记录可直接写未排的原因。
     */
    @Test
    public void shouldBlockChangeoverWhenTargetDayDailyLimitReached() {
        DefaultMouldChangeBalanceStrategy strategy = new DefaultMouldChangeBalanceStrategy();
        LhScheduleContext context = buildChangeoverBalanceContext();
        context.getDailyMouldChangeCountMap().put("2026-06-01", new int[]{8, 7});
        context.getDailyMouldChangeCountMap().put("2026-06-02", new int[]{8, 7});
        context.getDailyMouldChangeCountMap().put("2026-06-03", new int[]{8, 7});
        SkuScheduleDTO sku = buildChangeoverSku("3302001003", "E003");
        context.getMaterialSharedEmbryoMap().put(sku.getMaterialCode(), true);

        Date allocatedTime = strategy.allocateMouldChange(
                context, "K1103", dateTime(2026, 6, 1, 8, 0), 1, sku, "换活字块");

        Assertions.assertNull(allocatedTime);
        Assertions.assertTrue(context.getMouldChangeLimitBlockedReasonMap().get(sku.getMaterialCode())
                .contains("T+2 换模/换活字块次数超过每日15次上限"));
    }

    /**
     * 换活字块只有 1 台可直接承接时，如果后续还能转新增换模扩机，目标量不能被压死到单台窗口产能。
     */
    @Test
    public void shouldKeepQuotaDemandForTypeBlockExpansion() throws Exception {
        TypeBlockProductionStrategy strategy = new TypeBlockProductionStrategy();
        SkuScheduleDTO sku = buildSkuForTypeBlockExpansion();

        Method method = TypeBlockProductionStrategy.class.getDeclaredMethod(
                "resolveSingleMachineTypeBlockTargetQty",
                SkuScheduleDTO.class, int.class, boolean.class);
        method.setAccessible(true);
        int targetQty = (Integer) method.invoke(strategy, sku, 119, true);

        Assertions.assertEquals(300, targetQty);
    }

    /**
     * 当 dayN 模拟确认尾机台不需要再多吃一整班时，应去掉额外补上的那一班。
     */
    @Test
    public void shouldTrimExtraTailShiftWhenDailyCapacityIsSatisfied() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        MachineProductionSegment segment = new MachineProductionSegment();
        segment.setRole(MachineScheduleRole.TAIL_MACHINE);
        segment.setShiftCapacity(17);
        segment.setMaxQtyToWindowEnd(119);

        Method method = NewSpecProductionStrategy.class.getDeclaredMethod(
                "resolveSettledTailMachinePlanQty",
                MachineProductionSegment.class, int.class, int.class);
        method.setAccessible(true);
        int planQty = (Integer) method.invoke(strategy, segment, 81, 102);

        Assertions.assertEquals(85, planQty);
    }

    /**
     * dayN 扩机模拟需要把已落地的换活字块结果视为已启用机台，避免 S4.5 再重复扩一台。
     */
    @Test
    public void shouldIncludeExistingTypeBlockMachineInDailyCapacitySimulation() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        LhScheduleContext context = new LhScheduleContext();
        List<LhShiftConfigVO> shifts = buildSimulationShifts();
        context.setScheduleWindowShifts(shifts);

        LhScheduleResult typeBlockResult = new LhScheduleResult();
        typeBlockResult.setMaterialCode("3302002654");
        typeBlockResult.setLhMachineCode("K2024");
        typeBlockResult.setScheduleType("03");
        ShiftFieldUtil.setShiftPlanQty(typeBlockResult, 1, 17, new Date(), new Date());
        ShiftFieldUtil.setShiftPlanQty(typeBlockResult, 2, 17, new Date(), new Date());
        ShiftFieldUtil.setShiftPlanQty(typeBlockResult, 3, 17, new Date(), new Date());
        ShiftFieldUtil.syncDailyPlanQty(typeBlockResult);
        context.setScheduleResultList(new ArrayList<LhScheduleResult>());
        context.getScheduleResultList().add(typeBlockResult);

        SkuScheduleDTO sku = buildSkuForTypeBlockExpansion();
        MachineScheduleDTO currentMachine = new MachineScheduleDTO();
        currentMachine.setMachineCode("K1111");

        Method capacityMapMethod = NewSpecProductionStrategy.class.getDeclaredMethod(
                "buildExistingSameMaterialCapacityMaps",
                LhScheduleContext.class, SkuScheduleDTO.class, MachineScheduleDTO.class,
                List.class, Map.class);
        capacityMapMethod.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<Map<LocalDate, Integer>> existingCapacityMaps =
                (List<Map<LocalDate, Integer>>) capacityMapMethod.invoke(
                        strategy, context, sku, currentMachine, shifts, sku.getDailyPlanQuotaMap());

        Assertions.assertEquals(1, existingCapacityMaps.size());
        Assertions.assertEquals(Integer.valueOf(17), existingCapacityMaps.get(0).get(LocalDate.of(2026, 5, 1)));
        Assertions.assertEquals(Integer.valueOf(17), existingCapacityMaps.get(0).get(LocalDate.of(2026, 5, 2)));
        Assertions.assertEquals(Integer.valueOf(17), existingCapacityMaps.get(0).get(LocalDate.of(2026, 5, 3)));

        Method machineCountMethod = NewSpecProductionStrategy.class.getDeclaredMethod(
                "resolveRequiredNewSpecMachineCount", int.class, int.class);
        machineCountMethod.setAccessible(true);
        int requiredMachineCount = (Integer) machineCountMethod.invoke(strategy, 3, existingCapacityMaps.size());

        Assertions.assertEquals(2, requiredMachineCount);
    }

    /**
     * 续作补偿转 S4.5 新增时，dayN 扩机模拟必须把同账本续作结果视为已启用机台。
     */
    @Test
    public void shouldIncludeExistingContinuousMachineInDailyCapacitySimulation() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        LhScheduleContext context = new LhScheduleContext();
        List<LhShiftConfigVO> shifts = buildSimulationShifts();
        context.setScheduleWindowShifts(shifts);

        SkuScheduleDTO sourceSku = buildSkuForTypeBlockExpansion();
        SkuScheduleDTO compensationSku = buildSkuForTypeBlockExpansion();
        compensationSku.setDailyPlanQuotaMap(sourceSku.getDailyPlanQuotaMap());

        LhScheduleResult continuousResult = new LhScheduleResult();
        continuousResult.setMaterialCode("3302002654");
        continuousResult.setLhMachineCode("K2024");
        continuousResult.setScheduleType(ScheduleTypeEnum.CONTINUOUS.getCode());
        ShiftFieldUtil.setShiftPlanQty(continuousResult, 1, 17, new Date(), new Date());
        ShiftFieldUtil.setShiftPlanQty(continuousResult, 2, 17, new Date(), new Date());
        ShiftFieldUtil.setShiftPlanQty(continuousResult, 3, 17, new Date(), new Date());
        ShiftFieldUtil.syncDailyPlanQty(continuousResult);
        context.setScheduleResultList(new ArrayList<LhScheduleResult>());
        context.getScheduleResultList().add(continuousResult);
        context.getScheduleResultSourceSkuMap().put(continuousResult, sourceSku);

        MachineScheduleDTO currentMachine = new MachineScheduleDTO();
        currentMachine.setMachineCode("K1110");

        Method capacityMapMethod = NewSpecProductionStrategy.class.getDeclaredMethod(
                "buildExistingSameMaterialCapacityMaps",
                LhScheduleContext.class, SkuScheduleDTO.class, MachineScheduleDTO.class,
                List.class, Map.class);
        capacityMapMethod.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<Map<LocalDate, Integer>> existingCapacityMaps =
                (List<Map<LocalDate, Integer>>) capacityMapMethod.invoke(
                        strategy, context, compensationSku, currentMachine, shifts,
                        compensationSku.getDailyPlanQuotaMap());

        Assertions.assertEquals(1, existingCapacityMaps.size());
        Assertions.assertEquals(Integer.valueOf(17), existingCapacityMaps.get(0).get(LocalDate.of(2026, 5, 1)));
        Assertions.assertEquals(Integer.valueOf(17), existingCapacityMaps.get(0).get(LocalDate.of(2026, 5, 2)));
        Assertions.assertEquals(Integer.valueOf(17), existingCapacityMaps.get(0).get(LocalDate.of(2026, 5, 3)));
    }

    /**
     * 已有同物料机台满足 dayN 节奏时，即使业务目标仍有剩余，也不能继续新增候选机台。
     */
    @Test
    public void shouldSkipAddingMachineWhenExistingSameMaterialSatisfiesDayNButTargetRemains() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        LhScheduleContext context = buildContinuousReduceContext();
        List<LhShiftConfigVO> shifts = context.getScheduleWindowShifts();

        SkuScheduleDTO sourceSku = buildSkuForTypeBlockExpansion();
        SkuScheduleDTO compensationSku = buildSkuForTypeBlockExpansion();
        compensationSku.setTargetScheduleQty(240);
        compensationSku.setRemainingScheduleQty(240);
        compensationSku.setShiftCapacity(16);
        Map<LocalDate, SkuDailyPlanQuotaDTO> sharedQuotaMap = buildQuotaMapByShifts(shifts, 10, 10, 10);
        sourceSku.setDailyPlanQuotaMap(sharedQuotaMap);
        compensationSku.setDailyPlanQuotaMap(sharedQuotaMap);

        LhScheduleResult existingResult = new LhScheduleResult();
        existingResult.setMaterialCode(compensationSku.getMaterialCode());
        existingResult.setLhMachineCode("K2024");
        existingResult.setScheduleType(ScheduleTypeEnum.CONTINUOUS.getCode());
        for (LhShiftConfigVO shift : shifts) {
            ShiftFieldUtil.setShiftPlanQty(existingResult, shift.getShiftIndex(), 80, new Date(), new Date());
        }
        ShiftFieldUtil.syncDailyPlanQty(existingResult);
        context.getScheduleResultList().add(existingResult);
        context.getScheduleResultSourceSkuMap().put(existingResult, sourceSku);

        MachineScheduleDTO candidateMachine = new MachineScheduleDTO();
        candidateMachine.setMachineCode("K1110");
        MachineScheduleDTO secondCandidateMachine = new MachineScheduleDTO();
        secondCandidateMachine.setMachineCode("K1111");
        List<MachineScheduleDTO> candidates = new ArrayList<MachineScheduleDTO>(2);
        candidates.add(candidateMachine);
        candidates.add(secondCandidateMachine);
        Set<String> excludedMachineCodes = new HashSet<String>(2);

        MachineProductionSegment segment = new MachineProductionSegment();
        segment.setMachineCode(candidateMachine.getMachineCode());
        segment.setRole(MachineScheduleRole.TAIL_MACHINE);
        segment.setShiftCapacity(16);
        segment.setMaxQtyToWindowEnd(120);
        segment.setStartProductionShiftIndex(1);
        Map<Integer, Integer> shiftCapacityMap = new LinkedHashMap<Integer, Integer>(3);
        shiftCapacityMap.put(1, 40);
        shiftCapacityMap.put(2, 40);
        shiftCapacityMap.put(3, 40);
        segment.setShiftCapacityMap(shiftCapacityMap);

        Method method = NewSpecProductionStrategy.class.getDeclaredMethod(
                "resolveDynamicMachinePlanQtyByDailyCapacity",
                LhScheduleContext.class, SkuScheduleDTO.class, List.class, Set.class,
                ProductionQuantityPolicy.class, MachineProductionSegment.class, MachineScheduleDTO.class,
                List.class, ICapacityCalculateStrategy.class, int.class, int.class, int.class);
        method.setAccessible(true);
        int planQty = (Integer) method.invoke(strategy, context, compensationSku, candidates, excludedMachineCodes,
                ProductionQuantityPolicy.from(compensationSku, false), segment, candidateMachine, shifts,
                buildFixedCapacityCalculateStrategy(), 240, 0, 120);

        Assertions.assertEquals(0, planQty);
    }

    /**
     * 当前机台窗口有效产能已覆盖收尾目标时，不应再按 T/T+1 dayN 节奏拆第二台机台。
     */
    @Test
    public void shouldKeepSingleMachineWhenCurrentMachineCoversEndingTarget() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        LhScheduleContext context = buildContinuousReduceContext();
        List<LhShiftConfigVO> shifts = context.getScheduleWindowShifts();
        SkuScheduleDTO sku = buildContinuousSku("3302001555", 16, 108,
                buildQuotaMapByShifts(shifts, 8, 50, 50));
        sku.setSurplusQty(108);
        sku.setStrictTargetQty(true);

        MachineScheduleDTO candidateMachine = new MachineScheduleDTO();
        candidateMachine.setMachineCode("K1110");
        MachineScheduleDTO secondCandidateMachine = new MachineScheduleDTO();
        secondCandidateMachine.setMachineCode("K1111");
        List<MachineScheduleDTO> candidates = new ArrayList<MachineScheduleDTO>(2);
        candidates.add(candidateMachine);
        candidates.add(secondCandidateMachine);

        MachineProductionSegment segment = new MachineProductionSegment();
        segment.setMachineCode(candidateMachine.getMachineCode());
        segment.setRole(MachineScheduleRole.TAIL_MACHINE);
        segment.setShiftCapacity(16);
        segment.setMaxQtyToWindowEnd(116);
        segment.setStartProductionShiftIndex(1);
        Map<Integer, Integer> shiftCapacityMap = new LinkedHashMap<Integer, Integer>(8);
        for (LhShiftConfigVO shift : shifts) {
            shiftCapacityMap.put(shift.getShiftIndex(), 16);
        }
        segment.setShiftCapacityMap(shiftCapacityMap);

        Method method = NewSpecProductionStrategy.class.getDeclaredMethod(
                "resolveDynamicMachinePlanQtyByDailyCapacity",
                LhScheduleContext.class, SkuScheduleDTO.class, List.class, Set.class,
                ProductionQuantityPolicy.class, MachineProductionSegment.class, MachineScheduleDTO.class,
                List.class, ICapacityCalculateStrategy.class, int.class, int.class, int.class);
        method.setAccessible(true);
        int planQty = (Integer) method.invoke(strategy, context, sku, candidates, new HashSet<String>(2),
                ProductionQuantityPolicy.from(sku, true), segment, candidateMachine, shifts,
                buildFixedCapacityCalculateStrategy(), 108, 0, 108);

        Assertions.assertEquals(108, planQty);
    }

    /**
     * 增机台候选失败后，已成功排产机台应继续回填到自身尾部有效产能。
     */
    @Test
    public void shouldRefillScheduledMachineTailWhenAddMachineCandidatesFail() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        LhScheduleContext context = buildContinuousReduceContext();
        List<LhShiftConfigVO> shifts = context.getScheduleWindowShifts();
        SkuScheduleDTO sku = buildContinuousSku("3302002661", 16, 128,
                buildQuotaMapByShifts(shifts, 8, 60, 60));
        sku.setSurplusQty(128);
        context.getSkuProductionRemainingQtyMap().put(sku.getMaterialCode(), 64);

        LhScheduleResult result = new LhScheduleResult();
        result.setMaterialCode(sku.getMaterialCode());
        result.setLhMachineCode("K1111");
        result.setScheduleType(ScheduleTypeEnum.NEW_SPEC.getCode());
        result.setIsEnd("0");
        result.setMouldQty(1);
        result.setSingleMouldShiftQty(16);
        result.setLhTime(3600);
        ShiftFieldUtil.setShiftPlanQty(result, shifts.get(0).getShiftIndex(), 16,
                shifts.get(0).getShiftStartDateTime(), shifts.get(0).getShiftEndDateTime());
        ShiftFieldUtil.setShiftPlanQty(result, shifts.get(1).getShiftIndex(), 16,
                shifts.get(1).getShiftStartDateTime(), shifts.get(1).getShiftEndDateTime());
        ShiftFieldUtil.setShiftPlanQty(result, shifts.get(2).getShiftIndex(), 16,
                shifts.get(2).getShiftStartDateTime(), shifts.get(2).getShiftEndDateTime());
        ShiftFieldUtil.setShiftPlanQty(result, shifts.get(3).getShiftIndex(), 16,
                shifts.get(3).getShiftStartDateTime(), shifts.get(3).getShiftEndDateTime());
        ShiftFieldUtil.syncDailyPlanQty(result);
        context.getScheduleResultList().add(result);
        context.getScheduleResultSourceSkuMap().put(result, sku);

        MachineProductionSegment segment = new MachineProductionSegment();
        segment.setMachineCode("K1111");
        segment.setShiftCapacity(16);
        segment.setMaxQtyToWindowEnd(96);
        Map<Integer, Integer> shiftCapacityMap = new LinkedHashMap<Integer, Integer>(8);
        for (int index = 0; index < shifts.size(); index++) {
            LhShiftConfigVO shift = shifts.get(index);
            shiftCapacityMap.put(shift.getShiftIndex(), index < 6 ? 16 : 0);
        }
        segment.setShiftCapacityMap(shiftCapacityMap);

        Method method = NewSpecProductionStrategy.class.getDeclaredMethod(
                "refillScheduledResultAfterAddMachineFailure",
                LhScheduleContext.class, SkuScheduleDTO.class, LhScheduleResult.class,
                MachineProductionSegment.class, List.class, ProductionQuantityPolicy.class, int.class);
        method.setAccessible(true);
        int refillQty = (Integer) method.invoke(strategy, context, sku, result, segment, shifts,
                ProductionQuantityPolicy.from(sku, false), 64);

        Assertions.assertEquals(32, refillQty);
        Assertions.assertEquals(96, ShiftFieldUtil.resolveScheduledQty(result));
        Assertions.assertEquals(Integer.valueOf(32),
                context.getSkuProductionRemainingQtyMap().get(sku.getMaterialCode()));
    }

    /**
     * dayN 节奏已满足但业务目标仍有剩余时，仅续作收尾释放的机台尾部产能允许继续承接新增SKU。
     */
    @Test
    public void shouldContinueForTailCapacityWhenDailyRhythmSatisfiedButCandidateRemains() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        LhScheduleContext context = buildContinuousReduceContext();
        SkuScheduleDTO sku = buildContinuousSku("3302002661", 16, 128, buildQuotaMap(8, 60, 60));
        MachineScheduleDTO firstMachine = buildNewSpecMachine("K1110");
        MachineScheduleDTO tailMachine = buildNewSpecMachine("K1614");
        tailMachine.setEstimatedEndTime(context.getScheduleWindowShifts().get(1).getShiftStartDateTime());
        List<MachineScheduleDTO> candidates = new ArrayList<MachineScheduleDTO>(2);
        candidates.add(firstMachine);
        candidates.add(tailMachine);
        Set<String> excludedMachineCodes = new HashSet<String>(2);

        Method method = NewSpecProductionStrategy.class.getDeclaredMethod(
                "shouldContinueForTailCapacity",
                LhScheduleContext.class, SkuScheduleDTO.class, List.class, Set.class,
                String.class, int.class, int.class, int.class, boolean.class);
        method.setAccessible(true);
        boolean continueForTailCapacity = (Boolean) method.invoke(strategy, context, sku, candidates,
                excludedMachineCodes, firstMachine.getMachineCode(), 64, 64, 128, false);

        Assertions.assertTrue(continueForTailCapacity);
    }

    /**
     * dayN 节奏已满足后，普通整窗空闲机台不能再因为业务目标剩余被继续打开。
     */
    @Test
    public void shouldStopWhenDailyRhythmSatisfiedAndOnlyIdleCandidateRemains() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        LhScheduleContext context = buildContinuousReduceContext();
        SkuScheduleDTO sku = buildContinuousSku("3302002176", 46, 682, buildQuotaMap(46, 46, 46));
        MachineScheduleDTO firstMachine = buildNewSpecMachine("K2027");
        MachineScheduleDTO idleMachine = buildNewSpecMachine("k1001");
        List<MachineScheduleDTO> candidates = new ArrayList<MachineScheduleDTO>(2);
        candidates.add(firstMachine);
        candidates.add(idleMachine);
        Set<String> excludedMachineCodes = new HashSet<String>(2);

        Method method = NewSpecProductionStrategy.class.getDeclaredMethod(
                "shouldContinueForTailCapacity",
                LhScheduleContext.class, SkuScheduleDTO.class, List.class, Set.class,
                String.class, int.class, int.class, int.class, boolean.class);
        method.setAccessible(true);
        boolean continueForTailCapacity = (Boolean) method.invoke(strategy, context, sku, candidates,
                excludedMachineCodes, firstMachine.getMachineCode(), 46, 46, 682, false);

        Assertions.assertFalse(continueForTailCapacity);
    }

    /**
     * 续作日计划下降时，非收尾多机台也必须按业务日降模，不能被“非收尾不降模”提前跳过。
     */
    @Test
    public void shouldReduceContinuousMachinesWhenFutureDayPlanDrops() throws Exception {
        ContinuousProductionStrategy strategy = new ContinuousProductionStrategy();
        injectContinuousNonEndingDependencies(strategy);
        LhScheduleContext context = buildContinuousReduceContext();
        List<LhShiftConfigVO> shifts = context.getScheduleWindowShifts();
        SkuScheduleDTO sku = buildContinuousSku("3302001075", 16, 256, buildQuotaMapByShifts(shifts, 92, 92, 46));
        LhScheduleResult firstResult = buildContinuousResult("3302001075", "K1406", 16, shifts, "0");
        LhScheduleResult secondResult = buildContinuousResult("3302001075", "K1712", 16, shifts, "0");
        context.getScheduleResultList().add(firstResult);
        context.getScheduleResultList().add(secondResult);
        context.getScheduleResultSourceSkuMap().put(firstResult, sku);
        context.getScheduleResultSourceSkuMap().put(secondResult, sku);

        strategy.scheduleReduceMould(context);

        int thirdDayMachineCount = countPositiveMachineByWorkDate(context.getScheduleResultList(), shifts,
                resolveShiftWorkDate(shifts, 3));
        Assertions.assertEquals(1, thirdDayMachineCount);
    }

    /**
     * 续作日计划恒定且单机日产能可满足时，应立即减少一台续作机台。
     */
    @Test
    public void shouldReduceContinuousMachinesWhenConstantDailyPlanFitsOneMachine() throws Exception {
        ContinuousProductionStrategy strategy = new ContinuousProductionStrategy();
        injectContinuousNonEndingDependencies(strategy);
        LhScheduleContext context = buildContinuousReduceContext();
        List<LhShiftConfigVO> shifts = context.getScheduleWindowShifts();
        Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap = buildQuotaMapByShifts(shifts, 46, 46, 46);
        quotaMap.get(resolveShiftWorkDate(shifts, 1)).setRemainingQty(312);
        SkuScheduleDTO sku = buildContinuousSku("3302001075", 16, 404, quotaMap);
        sku.setMonthlyHistoryShortageQty(298);
        sku.setScheduleDayFinishQty(32);
        MdmSkuLhCapacity capacity = new MdmSkuLhCapacity();
        capacity.setMaterialCode("3302001075");
        capacity.setClassCapacity(16);
        capacity.setStandardCapacity(46);
        context.getSkuLhCapacityMap().put("3302001075", capacity);
        LhScheduleResult firstResult = buildContinuousResult("3302001075", "K1406", 16, shifts, "0");
        LhScheduleResult secondResult = buildContinuousResult("3302001075", "K1712", 16, shifts, "0");
        context.getScheduleResultList().add(firstResult);
        context.getScheduleResultList().add(secondResult);
        context.getScheduleResultSourceSkuMap().put(firstResult, sku);
        context.getScheduleResultSourceSkuMap().put(secondResult, sku);

        strategy.scheduleReduceMould(context);

        for (int dayIndex = 1; dayIndex <= 3; dayIndex++) {
            LocalDate workDate = resolveShiftWorkDate(shifts, dayIndex);
            Assertions.assertEquals(1,
                    countPositiveMachineByWorkDate(context.getScheduleResultList(), shifts, workDate),
                    "业务日应只保留一台续作机台: " + workDate);
        }
        Assertions.assertEquals(1, context.getScheduleResultList().size());
        Assertions.assertEquals(Integer.valueOf(122), context.getScheduleResultList().get(0).getDailyPlanQty());
    }

    /**
     * 续作日计划恒定且 dayN 需要两台机台时，不得因首日完成量扣减或后续追补需求为0误降到一台。
     */
    @Test
    public void shouldKeepRequiredContinuousMachinesWhenConstantDailyPlanNeedsTwoMachines() throws Exception {
        ContinuousProductionStrategy strategy = new ContinuousProductionStrategy();
        injectContinuousNonEndingDependencies(strategy);
        LhScheduleContext context = buildContinuousReduceContext();
        List<LhShiftConfigVO> shifts = context.getScheduleWindowShifts();
        Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap = buildQuotaMapByShifts(shifts, 96, 96, 96);
        quotaMap.get(resolveShiftWorkDate(shifts, 1)).setRemainingQty(48);
        appendMonthPlan(context, "3302001033", resolveShiftWorkDate(shifts, 1), 96, 96, 96);
        SkuScheduleDTO sku = buildContinuousSku("3302001033", 16, 820, quotaMap);
        sku.setScheduleDayFinishQty(48);
        sku.setStrictTargetQty(true);
        MdmSkuLhCapacity capacity = new MdmSkuLhCapacity();
        capacity.setMaterialCode("3302001033");
        capacity.setClassCapacity(16);
        capacity.setStandardCapacity(48);
        context.getSkuLhCapacityMap().put("3302001033", capacity);
        LhScheduleResult firstResult = buildContinuousResult("3302001033", "K1509", 16, shifts, "0");
        LhScheduleResult secondResult = buildContinuousResult("3302001033", "K1614", 16, shifts, "0");
        context.getScheduleResultList().add(firstResult);
        context.getScheduleResultList().add(secondResult);
        context.getScheduleResultSourceSkuMap().put(firstResult, sku);
        context.getScheduleResultSourceSkuMap().put(secondResult, sku);

        strategy.scheduleReduceMould(context);

        for (int dayIndex = 1; dayIndex <= 3; dayIndex++) {
            LocalDate workDate = resolveShiftWorkDate(shifts, dayIndex);
            Assertions.assertEquals(2,
                    countPositiveMachineByWorkDate(context.getScheduleResultList(), shifts, workDate),
                    "业务日不得低于 dayN 最小续作机台数: " + workDate);
        }
        Assertions.assertEquals(2, context.getScheduleResultList().size());
        Assertions.assertTrue(context.getReducedContinuationGroupKeySet().isEmpty());
    }

    /**
     * 多机台续作窗口内日计划仍有需求且后续下降时，即使前置收尾标记命中，也必须按天降模。
     */
    @Test
    public void shouldReduceEndingMarkedContinuousByWorkDateWhenFutureDayPlanDrops() throws Exception {
        ContinuousProductionStrategy strategy = new ContinuousProductionStrategy();
        injectContinuousNonEndingDependencies(strategy);
        LhScheduleContext context = buildContinuousReduceContext();
        List<LhShiftConfigVO> shifts = context.getScheduleWindowShifts();
        SkuScheduleDTO sku = buildContinuousSku("3302002326", 16, 425, buildQuotaMapByShifts(shifts, 192, 192, 30));
        String[] machineCodes = new String[]{"K1516", "K1607", "K1905", "K1924"};
        for (String machineCode : machineCodes) {
            LhScheduleResult result = buildContinuousResult("3302002326", machineCode, 16, shifts, "1");
            context.getScheduleResultList().add(result);
            context.getScheduleResultSourceSkuMap().put(result, sku);
        }

        strategy.scheduleReduceMould(context);

        LocalDate secondDay = resolveShiftWorkDate(shifts, 2);
        LocalDate thirdDay = resolveShiftWorkDate(shifts, 3);
        Assertions.assertTrue(sumPlanQtyByWorkDate(context.getScheduleResultList(), shifts, secondDay) >= 192);
        Assertions.assertEquals(1, countPositiveMachineByWorkDate(context.getScheduleResultList(), shifts, thirdDay));
    }

    /**
     * 续作目标量大于窗口日计划时，仍应按 dayN 日计划计算保留机台数，释放超过当日需求的机台。
     */
    @Test
    public void shouldReduceContinuousMachinesByDailyPlanWhenTargetExceedsWindowPlan() throws Exception {
        ContinuousProductionStrategy strategy = new ContinuousProductionStrategy();
        injectContinuousNonEndingDependencies(strategy);
        LhScheduleContext context = buildContinuousReduceContext();
        List<LhShiftConfigVO> shifts = context.getScheduleWindowShifts();
        SkuScheduleDTO sku = buildContinuousSku("3302001002", 16, 640,
                buildQuotaMapByShifts(shifts, 232, 192, 192));
        String[] machineCodes = new String[]{"K1501", "K1502", "K1503", "K1504", "K1505"};
        for (String machineCode : machineCodes) {
            LhScheduleResult result = buildContinuousResult("3302001002", machineCode, 16, shifts, "0");
            context.getScheduleResultList().add(result);
            context.getScheduleResultSourceSkuMap().put(result, sku);
        }

        strategy.scheduleReduceMould(context);

        LocalDate secondDay = resolveShiftWorkDate(shifts, 2);
        Assertions.assertEquals(4,
                countPositiveMachineByWorkDate(context.getScheduleResultList(), shifts, secondDay));
    }

    /**
     * 续作已经按日计划降模释放机台后，S4.5补偿入口不能再把同物料释放机台补回。
     */
    @Test
    public void shouldNotAppendCompensationAfterContinuousReduceMachine() throws Exception {
        ContinuousProductionStrategy strategy = new ContinuousProductionStrategy();
        injectContinuousNonEndingDependencies(strategy);
        LhScheduleContext context = buildContinuousReduceContext();
        List<LhShiftConfigVO> shifts = context.getScheduleWindowShifts();
        SkuScheduleDTO sku = buildContinuousSku("3302001002", 16, 821,
                buildQuotaMapByShifts(shifts, 240, 232, 192));
        sku.setMonthlyHistoryShortageQty(237);
        sku.setScheduleDayFinishQty(80);
        context.getContinuousSkuList().add(sku);
        String[] machineCodes = new String[]{"K1111", "K1507", "K1610", "K2002", "K2018"};
        for (String machineCode : machineCodes) {
            LhScheduleResult result = buildContinuousResult("3302001002", machineCode, 16, shifts, "0");
            context.getScheduleResultList().add(result);
            context.getScheduleResultSourceSkuMap().put(result, sku);
        }

        strategy.scheduleReduceMould(context);
        Method method = ContinuousProductionStrategy.class.getDeclaredMethod(
                "appendContinuousCompensationSkuList", LhScheduleContext.class);
        method.setAccessible(true);
        method.invoke(strategy, context);

        LocalDate thirdDay = resolveShiftWorkDate(shifts, 3);
        Assertions.assertEquals(4,
                countPositiveMachineByWorkDate(context.getScheduleResultList(), shifts, thirdDay));
        Assertions.assertTrue(context.getNewSpecSkuList().isEmpty());
    }

    /**
     * 续作现有机台数不满足后续 dayN 最小机台数时，必须生成续作增机台补偿SKU进入新增排产统一排序。
     */
    @Test
    public void shouldAppendContinuationAddMachineCompensationWhenDayNNeedsMoreMachines() throws Exception {
        ContinuousProductionStrategy strategy = new ContinuousProductionStrategy();
        injectContinuousEndingDependencies(strategy);
        LhScheduleContext context = buildContinuousReduceContext();
        List<LhShiftConfigVO> shifts = context.getScheduleWindowShifts();
        Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap = buildQuotaMapByShifts(shifts, 96, 128, 144);
        for (SkuDailyPlanQuotaDTO quota : quotaMap.values()) {
            quota.setRemainingQty(0);
        }
        SkuScheduleDTO sku = buildContinuousSku("3302001078", 16, 256, quotaMap);
        sku.setScheduleDayFinishQty(32);
        sku.setStrictTargetQty(true);
        sku.setRemainingScheduleQty(0);
        context.getContinuousSkuList().add(sku);
        LhScheduleResult firstResult = buildContinuousResult("3302001078", "K1106", 16, shifts, "0");
        LhScheduleResult secondResult = buildContinuousResult("3302001078", "K1609", 16, shifts, "0");
        context.getScheduleResultList().add(firstResult);
        context.getScheduleResultList().add(secondResult);
        context.getScheduleResultSourceSkuMap().put(firstResult, sku);
        context.getScheduleResultSourceSkuMap().put(secondResult, sku);

        Method method = ContinuousProductionStrategy.class.getDeclaredMethod(
                "appendContinuousCompensationSkuList", LhScheduleContext.class);
        method.setAccessible(true);
        method.invoke(strategy, context);

        Assertions.assertEquals(1, context.getNewSpecSkuList().size());
        SkuScheduleDTO compensationSku = context.getNewSpecSkuList().get(0);
        Assertions.assertTrue(compensationSku.isContinuousCompensationSku());
        Assertions.assertEquals(ScheduleTypeEnum.NEW_SPEC.getCode(), compensationSku.getScheduleType());
        Assertions.assertEquals(SkuScheduleSourceTypeEnum.CONTINUATION_ADD_MACHINE.getCode(),
                compensationSku.getSourceType());
        Assertions.assertSame(sku.getDailyPlanQuotaMap(), compensationSku.getDailyPlanQuotaMap());
        Assertions.assertTrue(compensationSku.resolveTargetScheduleQty() > 0);
        Method getter = SkuScheduleDTO.class.getMethod("getFirstAddMachineProductionDate");
        Assertions.assertEquals(resolveShiftWorkDate(shifts, 2), getter.invoke(compensationSku));
    }

    /**
     * 续作增机台补偿SKU已进入新增统一排序，选机时不得再锁回原续作机台。
     */
    @Test
    public void shouldNotLockContinuationAddMachineCompensationBackToOriginalMachine() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        SkuScheduleDTO sku = new SkuScheduleDTO();
        sku.setMaterialCode("3302001078");
        sku.setContinuousCompensationSku(true);
        sku.setSourceType(SkuScheduleSourceTypeEnum.CONTINUATION_ADD_MACHINE.getCode());
        sku.setPreferredContinuousMachineCode("K1106");
        List<MachineScheduleDTO> candidates = new ArrayList<MachineScheduleDTO>(2);
        candidates.add(buildNewSpecMachine("K1106"));
        candidates.add(buildNewSpecMachine("K1609"));

        Method method = NewSpecProductionStrategy.class.getDeclaredMethod(
                "resolvePreferredContinuousCompensationMachine", SkuScheduleDTO.class, List.class);
        method.setAccessible(true);
        MachineScheduleDTO selectedMachine = (MachineScheduleDTO) method.invoke(strategy, sku, candidates);

        Assertions.assertNull(selectedMachine);
    }

    /**
     * 收尾续作即使左右单控来自不同运行态对象，也必须按同物料硫化余量严格控量。
     */
    @Test
    public void shouldCapEndingSingleControlContinuousByStrictTargetAcrossRuntimeMachines() throws Exception {
        ContinuousProductionStrategy strategy = new ContinuousProductionStrategy();
        injectContinuousEndingDependencies(strategy);
        LhScheduleContext context = buildContinuousReduceContext();
        List<LhShiftConfigVO> shifts = context.getScheduleWindowShifts();
        SkuScheduleDTO leftSku = buildContinuousSku("3302002481", 9, 144, buildQuotaMapByShifts(shifts, 0, 0, 0));
        SkuScheduleDTO rightSku = buildContinuousSku("3302002481", 9, 144, buildQuotaMapByShifts(shifts, 0, 0, 0));
        leftSku.setSurplusQty(6);
        rightSku.setSurplusQty(6);
        leftSku.setMonthlyHistoryShortageQty(15);
        rightSku.setMonthlyHistoryShortageQty(15);
        leftSku.setFutureMonthPlanQtyAfterWindow(0);
        rightSku.setFutureMonthPlanQtyAfterWindow(0);
        leftSku.setSkuTag(SkuTagEnum.ENDING.getCode());
        rightSku.setSkuTag(SkuTagEnum.ENDING.getCode());
        applyContinuousNoFutureEndingStrictTarget(strategy, leftSku,
                DailyMachineExpansionPlanner.prepareShortageQuota(context, leftSku, "续作排产"));
        applyContinuousNoFutureEndingStrictTarget(strategy, rightSku,
                DailyMachineExpansionPlanner.prepareShortageQuota(context, rightSku, "续作排产"));
        LhScheduleResult leftResult = buildContinuousResult("3302002481", "K1501L", 9, shifts, "1");
        LhScheduleResult rightResult = buildContinuousResult("3302002481", "K1501R", 9, shifts, "1");
        context.getScheduleResultList().add(leftResult);
        context.getScheduleResultList().add(rightResult);
        context.getScheduleResultSourceSkuMap().put(leftResult, leftSku);
        context.getScheduleResultSourceSkuMap().put(rightResult, rightSku);

        strategy.scheduleReduceMould(context);

        int totalPlanQty = context.getScheduleResultList().stream()
                .filter(result -> "3302002481".equals(result.getMaterialCode()))
                .mapToInt(ShiftFieldUtil::resolveScheduledQty)
                .sum();
        long activeMachineCount = context.getScheduleResultList().stream()
                .filter(result -> "3302002481".equals(result.getMaterialCode()))
                .filter(result -> ShiftFieldUtil.resolveScheduledQty(result) > 0)
                .count();
        Assertions.assertEquals(6, totalPlanQty);
        Assertions.assertEquals(1L, activeMachineCount);
    }

    /**
     * 多机台续作已判定收尾时，应统一按收尾目标量收口，不能继续按窗口日计划保留两台单控机台。
     */
    @Test
    public void shouldCapMultiMachineEndingContinuousByMaxSurplusAndEmbryoStock() throws Exception {
        ContinuousProductionStrategy strategy = new ContinuousProductionStrategy();
        injectContinuousEndingDependencies(strategy);
        LhScheduleContext context = buildContinuousReduceContext();
        List<LhShiftConfigVO> shifts = context.getScheduleWindowShifts();
        SkuScheduleDTO sku = buildContinuousSku("3302002706", 8, 108, buildQuotaMapByShifts(shifts, 48, 48, 12));
        sku.setSurplusQty(9);
        sku.setEmbryoStock(7);
        sku.setSkuTag(SkuTagEnum.ENDING.getCode());
        LhScheduleResult leftResult = buildContinuousResult("3302002706", "K1501L", 8, shifts, "1");
        LhScheduleResult rightResult = buildContinuousResult("3302002706", "K1501R", 8, shifts, "1");
        leftResult.setMouldQty(1);
        rightResult.setMouldQty(1);
        context.getScheduleResultList().add(leftResult);
        context.getScheduleResultList().add(rightResult);
        context.getScheduleResultSourceSkuMap().put(leftResult, sku);
        context.getScheduleResultSourceSkuMap().put(rightResult, sku);

        strategy.scheduleReduceMould(context);

        int totalPlanQty = context.getScheduleResultList().stream()
                .filter(result -> "3302002706".equals(result.getMaterialCode()))
                .mapToInt(ShiftFieldUtil::resolveScheduledQty)
                .sum();
        long activeMachineCount = context.getScheduleResultList().stream()
                .filter(result -> "3302002706".equals(result.getMaterialCode()))
                .filter(result -> ShiftFieldUtil.resolveScheduledQty(result) > 0)
                .count();
        Assertions.assertEquals(9, totalPlanQty);
        Assertions.assertEquals(1L, activeMachineCount);
    }

    /**
     * 续作单机理论窗口产能已覆盖硫化余量时，不应仅因窗口日计划账本剩余额度继续转新增补机台。
     */
    @Test
    public void shouldSkipContinuousCompensationWhenSurplusCoveredByOneMachineCapacity() throws Exception {
        ContinuousProductionStrategy strategy = new ContinuousProductionStrategy();
        LhScheduleContext context = buildContinuousReduceContext();
        List<LhShiftConfigVO> shifts = context.getScheduleWindowShifts();
        SkuScheduleDTO sku = buildContinuousSku("3302001466", 18, 162, buildQuotaMapByShifts(shifts, 54, 54, 54));
        sku.setSurplusQty(101);
        sku.setWindowPlanQty(162);
        sku.setWindowRemainingPlanQty(62);
        LhScheduleResult result = buildContinuousResult("3302001466", "K1908", 18, shifts, "0");
        clearShiftPlanQty(result, shifts, 7);
        clearShiftPlanQty(result, shifts, 8);
        ShiftFieldUtil.syncDailyPlanQty(result);
        context.getScheduleResultList().add(result);
        context.getScheduleResultSourceSkuMap().put(result, sku);

        Method method = ContinuousProductionStrategy.class.getDeclaredMethod(
                "isContinuousDailyCapacitySatisfied", LhScheduleContext.class, SkuScheduleDTO.class);
        method.setAccessible(true);
        boolean satisfied = (Boolean) method.invoke(strategy, context, sku);

        Assertions.assertTrue(satisfied);
    }

    /**
     * 续作已有机台的理论 dayN 判断不能覆盖真实补偿缺口；仍有目标缺口时必须转 S4.5 新增补机台。
     */
    @Test
    public void shouldAppendContinuousCompensationWhenTheorySatisfiedButTargetRemains() throws Exception {
        ContinuousProductionStrategy strategy = new ContinuousProductionStrategy();
        LhScheduleContext context = buildContinuousReduceContext();
        List<LhShiftConfigVO> shifts = context.getScheduleWindowShifts();
        SkuScheduleDTO sku = buildContinuousSku("3302001271", 16, 1978,
                buildQuotaMapByShifts(shifts, 1368, 230, 230));
        sku.setStrictTargetQty(true);
        sku.setMonthlyHistoryShortageQty(210);
        sku.setScheduleDayFinishQty(60);
        context.getContinuousSkuList().add(sku);
        String[] machineCodes = new String[]{"K1104", "K1412", "K1512", "K1917"};
        for (String machineCode : machineCodes) {
            LhScheduleResult result = buildContinuousResult("3302001271", machineCode, 16, shifts, "0");
            setShiftPlanQty(result, shifts, 2, 14);
            setShiftPlanQty(result, shifts, 5, 14);
            setShiftPlanQty(result, shifts, 8, 14);
            ShiftFieldUtil.syncDailyPlanQty(result);
            context.getScheduleResultList().add(result);
            context.getScheduleResultSourceSkuMap().put(result, sku);
        }

        Method method = ContinuousProductionStrategy.class.getDeclaredMethod(
                "appendContinuousCompensationSkuList", LhScheduleContext.class);
        method.setAccessible(true);
        method.invoke(strategy, context);

        Assertions.assertFalse(context.getNewSpecSkuList().isEmpty());
        SkuScheduleDTO compensationSku = context.getNewSpecSkuList().get(0);
        Assertions.assertEquals(1550, compensationSku.getTargetScheduleQty());
        Assertions.assertEquals(1550, compensationSku.getPendingQty());
        Assertions.assertEquals(1550, compensationSku.getRemainingScheduleQty());
    }

    /**
     * 续作触发加机台时，只生成新增排产候选，并标记运行态来源为续作加机台。
     */
    @Test
    public void shouldMarkContinuationAddMachineSourceWhenAppendingNewSpecCandidate() throws Exception {
        ContinuousProductionStrategy strategy = new ContinuousProductionStrategy();
        LhScheduleContext context = buildContinuousReduceContext();
        List<LhShiftConfigVO> shifts = context.getScheduleWindowShifts();
        SkuScheduleDTO sku = buildContinuousSku("3302001271", 16, 1978,
                buildQuotaMapByShifts(shifts, 1368, 230, 230));
        sku.setContinuousMachineCode("K1104");
        sku.setStrictTargetQty(true);
        sku.setMonthlyHistoryShortageQty(210);
        sku.setScheduleDayFinishQty(60);
        context.getContinuousSkuList().add(sku);
        String[] machineCodes = new String[]{"K1104", "K1412", "K1512", "K1917"};
        for (String machineCode : machineCodes) {
            LhScheduleResult result = buildContinuousResult("3302001271", machineCode, 16, shifts, "0");
            setShiftPlanQty(result, shifts, 2, 14);
            setShiftPlanQty(result, shifts, 5, 14);
            setShiftPlanQty(result, shifts, 8, 14);
            ShiftFieldUtil.syncDailyPlanQty(result);
            context.getScheduleResultList().add(result);
            context.getScheduleResultSourceSkuMap().put(result, sku);
        }

        Method method = ContinuousProductionStrategy.class.getDeclaredMethod(
                "appendContinuousCompensationSkuList", LhScheduleContext.class);
        method.setAccessible(true);
        method.invoke(strategy, context);

        Assertions.assertFalse(context.getNewSpecSkuList().isEmpty());
        SkuScheduleDTO addMachineCandidate = context.getNewSpecSkuList().get(0);
        Assertions.assertEquals(SkuScheduleSourceTypeEnum.CONTINUATION_ADD_MACHINE.getCode(),
                addMachineCandidate.getSourceType());
        Assertions.assertEquals(ScheduleTypeEnum.NEW_SPEC.getCode(), addMachineCandidate.getScheduleType());
        Assertions.assertTrue(addMachineCandidate.isContinuousCompensationSku());
        Assertions.assertEquals("K1104", addMachineCandidate.getPreferredContinuousMachineCode());
        Assertions.assertSame(sku.getDailyPlanQuotaMap(), addMachineCandidate.getDailyPlanQuotaMap());
    }

    /**
     * 续作已按硫化余量建立严格收尾目标后，再次准备欠产账本不能用历史欠产抬高目标量。
     */
    @Test
    public void shouldKeepStrictEndingTargetWhenPreparingShortageQuotaAgain() {
        LhScheduleContext context = buildContinuousReduceContext();
        List<LhShiftConfigVO> shifts = context.getScheduleWindowShifts();
        SkuScheduleDTO sku = buildContinuousSku("3302002546", 18, 32,
                buildQuotaMapByShifts(shifts, 50, 50, 50));
        sku.setMonthlyHistoryShortageQty(887);
        sku.setScheduleDayFinishQty(18);
        sku.setSkuTag(SkuTagEnum.ENDING.getCode());
        sku.setStrictTargetQty(true);

        DailyMachineExpansionPlanner.prepareShortageQuota(context, sku, "续作排产补偿");

        Assertions.assertEquals(32, sku.resolveTargetScheduleQty());
        Assertions.assertEquals(32, sku.getRemainingScheduleQty());
    }

    /**
     * 续作小欠产逐日判断首日应扣减T日晚班已完成量，避免首日计划实际已满足时提前转新增加机台。
     */
    @Test
    public void shouldStillRequireContinuousCompensationWhenSecondDayPlanNotCovered() {
        LhScheduleContext context = buildContinuousReduceContext();
        List<LhShiftConfigVO> shifts = context.getScheduleWindowShifts();
        SkuScheduleDTO sku = buildContinuousSku("3302001589", 16, 128,
                buildQuotaMapByShifts(shifts, 48, 96, 96));
        sku.setMonthlyHistoryShortageQty(32);
        sku.setWindowPlanQty(240);
        sku.setScheduleDayFinishQty(16);

        boolean satisfied = DailyMachineExpansionPlanner.isDailyLookAheadCapacitySatisfied(
                context, sku, 1, ScheduleTypeEnum.CONTINUOUS.getCode());
        LocalDate addMachineDate = DailyMachineExpansionPlanner.resolveFirstDailyLookAheadAddMachineDate(
                context, sku, 1, ScheduleTypeEnum.CONTINUOUS.getCode());

        Assertions.assertFalse(satisfied);
        Assertions.assertEquals(new ArrayList<LocalDate>(sku.getDailyPlanQuotaMap().keySet()).get(1), addMachineDate);
    }

    /**
     * 欠产未超过阈值时，窗口末日当前机台仍无法满足当日计划，必须判定需要加机台。
     */
    @Test
    public void shouldRequireAddMachineWhenWindowLastDayPlanNotCovered() {
        LhScheduleContext context = buildContinuousReduceContext();
        List<LhShiftConfigVO> shifts = context.getScheduleWindowShifts();
        Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap = buildQuotaMapByShifts(shifts, 48, 48, 96);
        SkuScheduleDTO sku = buildContinuousSku("3302000745", 16, 128,
                quotaMap);
        sku.setMonthlyHistoryShortageQty(32);
        sku.setWindowPlanQty(192);
        sku.setScheduleDayFinishQty(0);

        Assertions.assertEquals(3, sku.getDailyPlanQuotaMap().size());
        boolean satisfied = DailyMachineExpansionPlanner.isDailyLookAheadCapacitySatisfied(
                context, sku, 1, ScheduleTypeEnum.CONTINUOUS.getCode());
        LocalDate addMachineDate = DailyMachineExpansionPlanner.resolveFirstDailyLookAheadAddMachineDate(
                context, sku, 1, ScheduleTypeEnum.CONTINUOUS.getCode());

        Assertions.assertFalse(satisfied);
        Assertions.assertEquals(new ArrayList<LocalDate>(sku.getDailyPlanQuotaMap().keySet()).get(2), addMachineDate);
    }

    /**
     * dayN 只能作为节奏和资源判断依据，不能作为正规非收尾新增 SKU 的实际排产硬上限。
     */
    @Test
    public void shouldNotLimitNonEndingNewSpecTargetByDailyPlanQuota() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        SkuScheduleDTO sku = buildContinuousSku("3302001033", 16, 160, buildQuotaMap(2, 2, 2));
        sku.setWindowPlanQty(6);
        sku.setWindowRemainingPlanQty(6);

        Method method = NewSpecProductionStrategy.class.getDeclaredMethod(
                "resolveSchedulableRemainingQty", SkuScheduleDTO.class);
        method.setAccessible(true);
        int remainingQty = (Integer) method.invoke(strategy, sku);

        Assertions.assertEquals(160, remainingQty);
    }

    /**
     * 新增排产 dayN 扩机台模拟必须保留完整日计划节奏，不能被本轮剩余目标量截断。
     */
    @Test
    public void shouldKeepFullDailyPlanForNewSpecMachineExpansionSimulation() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        SkuScheduleDTO sku = buildContinuousSku("3302001418", 18, 144, buildQuotaMap(162, 162, 162));
        Map<LocalDate, SkuDailyPlanQuotaDTO> simulationQuotaMap = invokeBuildSimulationQuotaMap(
                strategy, sku, sku.getDailyPlanQuotaMap(), 144, LocalDate.of(2026, 5, 3));

        Assertions.assertEquals(162, simulationQuotaMap.get(LocalDate.of(2026, 5, 1)).getDayPlanQty());
        Assertions.assertEquals(162, simulationQuotaMap.get(LocalDate.of(2026, 5, 2)).getDayPlanQty());
        Assertions.assertEquals(162, simulationQuotaMap.get(LocalDate.of(2026, 5, 3)).getDayPlanQty());

        DailyMachineCapacitySimulationRequest request = new DailyMachineCapacitySimulationRequest();
        request.setMaterialCode("3302001418");
        request.setDailyPlanQuotaMap(simulationQuotaMap);
        request.setMachineDailyCapacityList(buildDailyCapacityMaps(3, 36, 54, 54));
        request.setInitialActiveMachines(1);
        request.setShiftCapacity(18);
        request.setShortageLookAheadDays(2);
        request.setShortageAddMachineThreshold(150);
        request.setMonthlyHistoryShortageQty(0);
        request.setWindowEndDate(LocalDate.of(2026, 5, 3));
        request.setSceneType("newSpec");

        DailyMachineCapacitySimulationResult result =
                DailyMachineCapacitySimulationUtil.simulateExpansion(request);

        Assertions.assertEquals(3, result.getFinalActiveMachines());
        Assertions.assertEquals(2, result.getTotalAddedMachineCount());
    }

    /**
     * 公共 dayN 模拟账本构造也必须保留完整日计划节奏，避免后续调用回退到目标量截断旧口径。
     */
    @Test
    public void shouldKeepFullDailyPlanWhenBuildingPublicExpansionSimulationQuotaMap() {
        Map<LocalDate, SkuDailyPlanQuotaDTO> simulationQuotaMap =
                DailyMachineExpansionPlanner.buildSimulationQuotaMap(buildQuotaMap(162, 162, 162), 144);

        Assertions.assertEquals(162, simulationQuotaMap.get(LocalDate.of(2026, 5, 1)).getDayPlanQty());
        Assertions.assertEquals(162, simulationQuotaMap.get(LocalDate.of(2026, 5, 2)).getDayPlanQty());
        Assertions.assertEquals(162, simulationQuotaMap.get(LocalDate.of(2026, 5, 3)).getDayPlanQty());
    }

    /**
     * 新增排产 dayN 模拟保留完整节奏后，3302002654 类场景仍只需要新增一台机台。
     */
    @Test
    public void shouldOnlyAddOneMachineWhenTwoMachinesCoverNewSpecDailyPlan() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        SkuScheduleDTO sku = buildContinuousSku("3302002654", 25, 144, buildQuotaMap(100, 100, 100));
        Map<LocalDate, SkuDailyPlanQuotaDTO> simulationQuotaMap = invokeBuildSimulationQuotaMap(
                strategy, sku, sku.getDailyPlanQuotaMap(), 144, LocalDate.of(2026, 5, 3));

        Assertions.assertEquals(100, simulationQuotaMap.get(LocalDate.of(2026, 5, 1)).getDayPlanQty());
        Assertions.assertEquals(100, simulationQuotaMap.get(LocalDate.of(2026, 5, 2)).getDayPlanQty());
        Assertions.assertEquals(100, simulationQuotaMap.get(LocalDate.of(2026, 5, 3)).getDayPlanQty());

        DailyMachineCapacitySimulationRequest request = new DailyMachineCapacitySimulationRequest();
        request.setMaterialCode("3302002654");
        request.setDailyPlanQuotaMap(simulationQuotaMap);
        request.setMachineDailyCapacityList(buildDailyCapacityMaps(2, 50, 50, 50));
        request.setInitialActiveMachines(1);
        request.setShiftCapacity(25);
        request.setShortageLookAheadDays(2);
        request.setShortageAddMachineThreshold(150);
        request.setMonthlyHistoryShortageQty(0);
        request.setWindowEndDate(LocalDate.of(2026, 5, 3));
        request.setSceneType("newSpec");

        DailyMachineCapacitySimulationResult result =
                DailyMachineCapacitySimulationUtil.simulateExpansion(request);

        Assertions.assertEquals(2, result.getFinalActiveMachines());
        Assertions.assertEquals(1, result.getTotalAddedMachineCount());
    }

    /**
     * 已有同物料机台模拟不能把“无可新增机台”误判成“dayN已经满足”。
     */
    @Test
    public void shouldNotTreatExistingSameMaterialAsSatisfiedWhenDailyPlanStillUnmet() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        DailyMachineCapacitySimulationRequest request = new DailyMachineCapacitySimulationRequest();
        request.setMaterialCode("3302001418");
        request.setDailyPlanQuotaMap(buildQuotaMap(162, 162, 162));
        request.setMachineDailyCapacityList(buildDailyCapacityMaps(3, 36, 54, 54));
        request.setInitialActiveMachines(1);
        request.setShiftCapacity(18);
        request.setShortageLookAheadDays(2);
        request.setShortageAddMachineThreshold(150);
        request.setMonthlyHistoryShortageQty(0);
        request.setWindowEndDate(LocalDate.of(2026, 5, 3));
        request.setSceneType("newSpec");
        List<Map<LocalDate, Integer>> existingMachineCapacityMaps = buildDailyCapacityMaps(1, 36, 54, 54);

        boolean satisfied = invokeIsExistingSameMaterialSimulationSatisfied(
                strategy, request, existingMachineCapacityMaps, 3);

        Assertions.assertFalse(satisfied);
    }

    /**
     * 正式非收尾新增 SKU 不得把 day1+day2+day3 当作多机台排产目标量，dayN 只参与节奏和增机判断。
     */
    @Test
    public void shouldKeepFormalNonEndingNewSpecBusinessTargetWhenWindowPlanIsSmall() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        LhScheduleContext context = buildContinuousReduceContext();
        SkuScheduleDTO sku = buildContinuousSku("3302000745", 16, 320, buildQuotaMap(48, 144, 144));
        sku.setWindowPlanQty(336);
        sku.setWindowRemainingPlanQty(144);
        sku.setStrictTargetQty(false);

        Method method = NewSpecProductionStrategy.class.getDeclaredMethod(
                "resolveFormalNonEndingMinimumTargetQty",
                LhScheduleContext.class, SkuScheduleDTO.class, ProductionQuantityPolicy.class);
        method.setAccessible(true);
        int targetQty = (Integer) method.invoke(strategy, context, sku,
                ProductionQuantityPolicy.from(sku, false));

        Assertions.assertEquals(320, targetQty);
    }

    /**
     * dayN 不得覆盖正式 SKU 的严格收尾目标，新增链路也不能把目标量裁成窗口日计划小量。
     */
    @Test
    public void shouldNotLimitStrictNewSpecTargetByDailyPlanQuota() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        SkuScheduleDTO sku = buildContinuousSku("3302001033", 16, 160, buildQuotaMap(2, 2, 2));
        sku.setStrictTargetQty(true);
        sku.setWindowPlanQty(6);
        sku.setWindowRemainingPlanQty(6);

        Method method = NewSpecProductionStrategy.class.getDeclaredMethod(
                "resolveSchedulableRemainingQty", SkuScheduleDTO.class);
        method.setAccessible(true);
        int remainingQty = (Integer) method.invoke(strategy, sku);

        Assertions.assertEquals(160, remainingQty);
    }

    /**
     * 已有续作机台满足 dayN 节奏时，不得因历史欠产或业务目标剩余继续新增补偿机台。
     */
    @Test
    public void shouldSkipContinuousCompensationWhenDailyPlanAlreadySatisfiedEvenWithShortage() throws Exception {
        ContinuousProductionStrategy strategy = new ContinuousProductionStrategy();
        LhScheduleContext context = buildContinuousReduceContext();
        List<LhShiftConfigVO> shifts = context.getScheduleWindowShifts();
        SkuScheduleDTO sku = buildContinuousSku("3302001033", 16, 160, buildQuotaMapByShifts(shifts, 2, 2, 2));
        sku.setMonthlyHistoryShortageQty(240);
        LhScheduleResult result = buildContinuousResult("3302001033", "K1614", 16, shifts, "0");
        clearShiftPlanQty(result, shifts, 7);
        clearShiftPlanQty(result, shifts, 8);
        ShiftFieldUtil.syncDailyPlanQty(result);
        context.getScheduleResultList().add(result);
        context.getScheduleResultSourceSkuMap().put(result, sku);

        Method method = ContinuousProductionStrategy.class.getDeclaredMethod(
                "resolveContinuousCompensationQty", LhScheduleContext.class, SkuScheduleDTO.class);
        method.setAccessible(true);
        int compensationQty = (Integer) method.invoke(strategy, context, sku);

        Assertions.assertEquals(0, compensationQty);
    }

    /**
     * 续作单机已经覆盖 dayN 节奏时，不能仅因硫化余量仍有剩余再转新增补偿机台。
     */
    @Test
    public void shouldSkipContinuousCompensationWhenSingleMachineSatisfiesDailyRhythm() throws Exception {
        ContinuousProductionStrategy strategy = new ContinuousProductionStrategy();
        LhScheduleContext context = buildContinuousReduceContext();
        List<LhShiftConfigVO> shifts = context.getScheduleWindowShifts();
        SkuScheduleDTO sku = buildContinuousSku("3302002654", 18, 1500,
                buildQuotaMapByShifts(shifts, 50, 50, 50));
        sku.setSurplusQty(1500);
        LhScheduleResult result = buildContinuousResult("3302002654", "K2024", 18, shifts, "0");
        context.getScheduleResultList().add(result);
        context.getScheduleResultSourceSkuMap().put(result, sku);

        Method method = ContinuousProductionStrategy.class.getDeclaredMethod(
                "resolveContinuousCompensationQty", LhScheduleContext.class, SkuScheduleDTO.class);
        method.setAccessible(true);
        int compensationQty = (Integer) method.invoke(strategy, context, sku);

        Assertions.assertEquals(0, compensationQty);
    }

    /**
     * 已有四台续作机台满足 dayN 节奏时，不能仅因硫化余量或目标剩余再回流新增补机台。
     */
    @Test
    public void shouldSkipContinuousCompensationWhenExistingMachinesSatisfyDailyRhythm() throws Exception {
        ContinuousProductionStrategy strategy = new ContinuousProductionStrategy();
        LhScheduleContext context = buildContinuousReduceContext();
        List<LhShiftConfigVO> shifts = context.getScheduleWindowShifts();
        SkuScheduleDTO sku = buildContinuousSku("3302001316", 16, 5184,
                buildQuotaMapByShifts(shifts, 184, 184, 184));
        sku.setSurplusQty(5184);
        MdmSkuLhCapacity capacity = new MdmSkuLhCapacity();
        capacity.setMaterialCode("3302001316");
        capacity.setClassCapacity(16);
        capacity.setStandardCapacity(46);
        context.getSkuLhCapacityMap().put("3302001316", capacity);
        String[] machineCodes = new String[]{"K1113", "K1506", "K1916", "K2017"};
        for (String machineCode : machineCodes) {
            LhScheduleResult result = buildContinuousResult("3302001316", machineCode, 16, shifts, "0");
            context.getScheduleResultList().add(result);
            context.getScheduleResultSourceSkuMap().put(result, sku);
        }

        Method method = ContinuousProductionStrategy.class.getDeclaredMethod(
                "resolveContinuousCompensationQty", LhScheduleContext.class, SkuScheduleDTO.class);
        method.setAccessible(true);
        int compensationQty = (Integer) method.invoke(strategy, context, sku);

        Assertions.assertEquals(0, compensationQty);
    }

    /**
     * S4.5 原始新增列表里同物料已由纯续作机台满足 dayN 时，不得再走新增换模上机。
     */
    @Test
    public void shouldSkipNewSpecWhenPureContinuousMachinesSatisfyOriginalDayMinimum() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        LhScheduleContext context = buildContinuousReduceContext();
        List<LhShiftConfigVO> shifts = context.getScheduleWindowShifts();
        LocalDate firstDate = resolveShiftWorkDate(shifts, 1);
        appendMonthPlan(context, "3302001316", firstDate, 184, 184, 184);
        SkuScheduleDTO sku = buildContinuousSku("3302001316", 16, 5184,
                buildQuotaMapByShifts(shifts, 184, 184, 184));
        sku.setSurplusQty(5184);
        MdmSkuLhCapacity capacity = new MdmSkuLhCapacity();
        capacity.setMaterialCode("3302001316");
        capacity.setClassCapacity(16);
        capacity.setStandardCapacity(46);
        context.getSkuLhCapacityMap().put("3302001316", capacity);
        String[] machineCodes = new String[]{"K1113", "K1506", "K1916", "K2017"};
        for (String machineCode : machineCodes) {
            LhScheduleResult result = buildContinuousResult("3302001316", machineCode, 16, shifts, "0");
            context.getScheduleResultList().add(result);
        }

        Method method = NewSpecProductionStrategy.class.getDeclaredMethod(
                "shouldSkipNewSpecBecauseContinuousSatisfiesOriginalDayMinimum",
                LhScheduleContext.class, SkuScheduleDTO.class, ProductionQuantityPolicy.class);
        method.setAccessible(true);
        boolean skip = (Boolean) method.invoke(
                strategy, context, sku, ProductionQuantityPolicy.from(sku, false));

        Assertions.assertTrue(skip);
        sku.setStrictNewSpecShortageOnly(true);
        boolean strictShortageSkip = (Boolean) method.invoke(
                strategy, context, sku, ProductionQuantityPolicy.from(sku, false));
        Assertions.assertTrue(strictShortageSkip);
    }

    /**
     * dayN 不得覆盖收尾目标量；严格目标存在缺口时，续作补偿必须按业务目标补齐。
     */
    @Test
    public void shouldNotLimitStrictEndingCompensationQtyByDailyPlanQuota() throws Exception {
        ContinuousProductionStrategy strategy = new ContinuousProductionStrategy();
        LhScheduleContext context = buildContinuousReduceContext();
        List<LhShiftConfigVO> shifts = context.getScheduleWindowShifts();
        SkuScheduleDTO sku = buildContinuousSku("3302001033", 16, 160, buildQuotaMapByShifts(shifts, 2, 2, 2));
        sku.setStrictTargetQty(true);
        sku.setMonthlyHistoryShortageQty(240);
        LhScheduleResult result = buildContinuousResult("3302001033", "K1614", 16, shifts, "1");
        clearShiftPlanQty(result, shifts, 7);
        clearShiftPlanQty(result, shifts, 8);
        ShiftFieldUtil.syncDailyPlanQty(result);
        context.getScheduleResultList().add(result);
        context.getScheduleResultSourceSkuMap().put(result, sku);

        Method method = ContinuousProductionStrategy.class.getDeclaredMethod(
                "resolveContinuousCompensationQty", LhScheduleContext.class, SkuScheduleDTO.class);
        method.setAccessible(true);
        int compensationQty = (Integer) method.invoke(strategy, context, sku);

        Assertions.assertEquals(64, compensationQty);
    }

    /**
     * 续作单机降模后，如果 dayN 后看仍判定需要补机台，不能用单机降模标记直接吞掉补偿量。
     */
    @Test
    public void shouldCompensateAfterSingleMachineReductionWhenDailyPlanStillNeedsMachine() throws Exception {
        ContinuousProductionStrategy strategy = new ContinuousProductionStrategy();
        LhScheduleContext context = buildContinuousReduceContext();
        List<LhShiftConfigVO> shifts = context.getScheduleWindowShifts();
        SkuScheduleDTO sku = buildContinuousSku("3302000745", 16, 337,
                buildQuotaMapByShifts(shifts, 96, 96, 144));
        sku.setStrictTargetQty(true);
        context.getSingleMachineReducedContinuationGroupKeySet().add("3302000745#SINGLE_MACHINE_REDUCED");
        LhScheduleResult result = buildContinuousResult("3302000745", "K1905", 16, shifts, "1");
        context.getScheduleResultList().add(result);
        context.getScheduleResultSourceSkuMap().put(result, sku);

        Method method = ContinuousProductionStrategy.class.getDeclaredMethod(
                "resolveContinuousCompensationQty", LhScheduleContext.class, SkuScheduleDTO.class);
        method.setAccessible(true);
        int compensationQty = (Integer) method.invoke(strategy, context, sku);

        Assertions.assertEquals(209, compensationQty);
    }

    /**
     * 续作首日有原始日计划时，即使额度被T日晚班扣完，也应从窗口首班起排。
     */
    @Test
    public void shouldStartContinuousFromFirstShiftWhenOriginalFirstDayHasPlan() throws Exception {
        ContinuousProductionStrategy strategy = new ContinuousProductionStrategy();
        LhScheduleContext context = buildContinuousReduceContext();
        List<LhShiftConfigVO> shifts = context.getScheduleWindowShifts();
        SkuScheduleDTO sku = buildContinuousSku("3202000220", 16, 128, buildQuotaMapByShifts(shifts, 8, 48, 48));
        sku.getDailyPlanQuotaMap().get(resolveShiftWorkDate(shifts, 1)).setRemainingQty(0);
        MachineScheduleDTO machine = new MachineScheduleDTO();
        machine.setMachineCode("K1608");

        Method method = ContinuousProductionStrategy.class.getDeclaredMethod(
                "resolveContinuousStartTime", LhScheduleContext.class, SkuScheduleDTO.class,
                MachineScheduleDTO.class, List.class, boolean.class);
        method.setAccessible(true);
        Date nonEndingStartTime = (Date) method.invoke(strategy, context, sku, machine, shifts, false);
        Date endingStartTime = (Date) method.invoke(strategy, context, sku, machine, shifts, true);

        Assertions.assertEquals(shifts.get(0).getShiftStartDateTime(), nonEndingStartTime);
        Assertions.assertEquals(shifts.get(0).getShiftStartDateTime(), endingStartTime);
    }

    /**
     * 续作排产以原始dayN判断起排日，以硫化余量作为排产目标；T日已完成量不能导致跳过C1/C2。
     */
    @Test
    public void shouldScheduleEndingContinuousFromFirstShiftByOriginalDayPlanWhenFirstDayRemainingConsumed() throws Exception {
        ContinuousProductionStrategy strategy = new ContinuousProductionStrategy();
        injectContinuousEndingDependencies(strategy);
        LhScheduleContext context = buildContinuousReduceContext();
        context.setScheduleDate(dateTime(2026, 6, 27, 0, 0));
        context.setScheduleTargetDate(dateTime(2026, 6, 27, 0, 0));
        context.setScheduleWindowShifts(LhScheduleTimeUtil.buildDefaultScheduleShifts(context, context.getScheduleDate()));
        List<LhShiftConfigVO> shifts = context.getScheduleWindowShifts();
        SkuScheduleDTO sku = buildContinuousSku("3302001454", 18, 42,
                buildQuotaMapByShifts(shifts, 8, 42, 0));
        sku.setContinuousMachineCode("K2021");
        sku.setScheduleDayFinishQty(18);
        sku.setLhTimeSeconds(3600);
        sku.setMonthPlanQty(50);
        sku.setEmbryoStock(42);
        SkuDailyPlanQuotaDTO day1Quota = sku.getDailyPlanQuotaMap().get(resolveShiftWorkDate(shifts, 1));
        day1Quota.setScheduledQty(18);
        day1Quota.setRemainingQty(0);
        appendMonthPlan(context, sku.getMaterialCode(), resolveShiftWorkDate(shifts, 1), 8, 42, 0);
        MdmSkuLhCapacity capacity = new MdmSkuLhCapacity();
        capacity.setMaterialCode(sku.getMaterialCode());
        capacity.setStandardCapacity(52);
        context.getSkuLhCapacityMap().put(sku.getMaterialCode(), capacity);
        context.getContinuousSkuList().add(sku);
        MachineScheduleDTO machine = new MachineScheduleDTO();
        machine.setMachineCode("K2021");
        machine.setMachineName("K2021");
        machine.setCurrentMaterialCode(sku.getMaterialCode());
        machine.setMaxMoldNum(2);
        machine.setEstimatedEndTime(shifts.get(0).getShiftStartDateTime());
        context.getMachineScheduleMap().put(machine.getMachineCode(), machine);
        context.getInitialMachineScheduleMap().put(machine.getMachineCode(), machine);

        strategy.scheduleContinuousEnding(context);

        Assertions.assertEquals(1, context.getScheduleResultList().size());
        LhScheduleResult result = context.getScheduleResultList().get(0);
        Assertions.assertEquals("3302001454", result.getMaterialCode());
        Assertions.assertEquals("K2021", result.getLhMachineCode());
        Assertions.assertEquals(18, resolveShiftPlanQty(result, 1));
        Assertions.assertEquals(16, resolveShiftPlanQty(result, 2));
        Assertions.assertEquals(8, resolveShiftPlanQty(result, 3));
        Assertions.assertEquals(0, resolveShiftPlanQty(result, 4));
        Assertions.assertEquals(0, resolveShiftPlanQty(result, 5));
        Assertions.assertEquals(Integer.valueOf(42), result.getDailyPlanQty());
    }

    /**
     * 首日无计划但后续窗口有正日计划的 MES 在机同物料，必须保留续作身份，不能释放后转新增换模。
     */
    @Test
    public void shouldKeepContinuousIdentityWhenFirstDayNoPlanButFuturePlanExists() throws Exception {
        ContinuousProductionStrategy strategy = new ContinuousProductionStrategy();
        LhScheduleContext context = buildContinuousReduceContext();
        List<LhShiftConfigVO> shifts = context.getScheduleWindowShifts();
        SkuScheduleDTO sku = buildContinuousSku("3302002759", 18, 300,
                buildQuotaMapByShifts(shifts, 0, 54, 54));
        sku.setContinuousMachineCode("K2009");

        Method method = ContinuousProductionStrategy.class.getDeclaredMethod(
                "shouldReleaseFirstDayNoPlanContinuousSku",
                LhScheduleContext.class, SkuScheduleDTO.class, List.class, DailyMachineShortageQuotaPlan.class);
        method.setAccessible(true);
        boolean release = (Boolean) method.invoke(strategy, context, sku, shifts, null);

        Assertions.assertFalse(release);
    }

    /**
     * 续作收尾后，新增选机和新增排产都必须从收尾完成时间承接，不能按整窗占用到窗口末尾。
     */
    @Test
    public void shouldReuseMachineAfterContinuousEndingSpecEndTime() throws Exception {
        LhScheduleContext context = buildContinuousReduceContext();
        List<LhShiftConfigVO> shifts = context.getScheduleWindowShifts();
        Date endingTime = shifts.get(4).getShiftEndDateTime();
        MachineScheduleDTO machine = new MachineScheduleDTO();
        machine.setMachineCode("K1807");
        machine.setEstimatedEndTime(endingTime);
        machine.setEnding(true);
        LhScheduleResult endingResult = buildContinuousResult("3302002686", "K1807", 16, shifts, "1");
        endingResult.setDailyPlanQty(64);
        endingResult.setSpecEndTime(endingTime);
        List<LhScheduleResult> assignedResults = new ArrayList<LhScheduleResult>(1);
        assignedResults.add(endingResult);
        context.getMachineAssignmentMap().put("K1807", assignedResults);

        DefaultMachineMatchStrategy matchStrategy = new DefaultMachineMatchStrategy();
        Method matchMethod = DefaultMachineMatchStrategy.class.getDeclaredMethod(
                "resolveCandidateReferenceTime", LhScheduleContext.class, MachineScheduleDTO.class);
        matchMethod.setAccessible(true);
        Date candidateReferenceTime = (Date) matchMethod.invoke(matchStrategy, context, machine);

        NewSpecProductionStrategy newSpecStrategy = new NewSpecProductionStrategy();
        Method newSpecMethod = NewSpecProductionStrategy.class.getDeclaredMethod(
                "resolveMachineOccupationEndTime", LhScheduleContext.class, MachineScheduleDTO.class, List.class);
        newSpecMethod.setAccessible(true);
        Date occupationEndTime = (Date) newSpecMethod.invoke(newSpecStrategy, context, machine, shifts);

        Assertions.assertEquals(endingTime, candidateReferenceTime);
        Assertions.assertEquals(endingTime, occupationEndTime);
    }

    /**
     * 收尾释放机台进入新增候选后若被日计划回裁为0，必须留下短日志说明未复用原因。
     */
    @Test
    public void shouldLogReasonWhenReleasedTailMachineCandidateIsTrimmedToZero() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        LhScheduleContext context = buildContinuousReduceContext();
        SkuScheduleDTO sku = buildContinuousSku("3302001317", 16, 244,
                buildQuotaMapByShifts(context.getScheduleWindowShifts(), 122, 122, 122));
        MdmSkuLhCapacity capacity = new MdmSkuLhCapacity();
        capacity.setMaterialCode("3302001317");
        capacity.setStandardCapacity(122);
        context.getSkuLhCapacityMap().put("3302001317", capacity);

        Method method = NewSpecProductionStrategy.class.getDeclaredMethod(
                "appendNewSpecCandidateRejectedProcessLog",
                LhScheduleContext.class, SkuScheduleDTO.class, String.class, String.class,
                Date.class, Date.class, Date.class, Date.class, Date.class,
                Integer.class, Integer.class, Integer.class);
        method.setAccessible(true);
        method.invoke(strategy, context, sku, "K1807", "日计划额度回裁后为0",
                dateTime(2026, 6, 27, 17, 0),
                dateTime(2026, 6, 27, 17, 0),
                dateTime(2026, 6, 27, 17, 0),
                dateTime(2026, 6, 28, 1, 0),
                dateTime(2026, 6, 28, 1, 0),
                0, 0, 0);

        Assertions.assertEquals(1, context.getScheduleLogList().size());
        Assertions.assertEquals("新增候选机台回裁跳过", context.getScheduleLogList().get(0).getTitle());
        String detail = context.getScheduleLogList().get(0).getLogDetail();
        Assertions.assertTrue(detail.contains("materialCode=3302001317"));
        Assertions.assertTrue(detail.contains("machineCode=K1807"));
        Assertions.assertTrue(detail.contains("dailyStandardQty=122"));
        Assertions.assertTrue(detail.contains("reason=日计划额度回裁后为0"));
    }

    /**
     * 续作收尾小余量在允许欠产偏差内，且前日 T+1 夜班未排满时，应直接写未排并释放原续作机台。
     */
    @Test
    public void shouldSkipSmallSurplusEndingContinuousAndReleaseMachine() throws Exception {
        ContinuousProductionStrategy strategy = new ContinuousProductionStrategy();
        injectContinuousEndingDependencies(strategy);
        LhScheduleContext context = buildContinuousReduceContext();
        List<LhShiftConfigVO> shifts = context.getScheduleWindowShifts();
        SkuScheduleDTO sku = buildContinuousSku("3302002999", 16, 2, buildQuotaMapByShifts(shifts, 2, 0, 0));
        sku.setContinuousMachineCode("K1201");
        sku.setSurplusQty(2);
        context.getContinuousSkuList().add(sku);
        appendTargetPreviousT1NightResult(context, sku.getMaterialCode(), 8);
        MachineScheduleDTO machine = new MachineScheduleDTO();
        machine.setMachineCode("K1201");
        machine.setCurrentMaterialCode("3302002999");
        machine.setEstimatedEndTime(shifts.get(0).getShiftStartDateTime());
        context.getMachineScheduleMap().put(machine.getMachineCode(), machine);
        context.getInitialMachineScheduleMap().put(machine.getMachineCode(), machine);

        strategy.scheduleContinuousEnding(context);

        Assertions.assertEquals(0, context.getScheduleResultList().size());
        Assertions.assertTrue(context.getReleasedContinuousMachineCodeSet().contains("K1201"));
        Assertions.assertTrue(context.getTypeBlockReleasedContinuousMachineCodeSet().contains("K1201"));
        Assertions.assertEquals(1, context.getUnscheduledResultList().size());
        LhUnscheduledResult unscheduledResult = context.getUnscheduledResultList().get(0);
        Assertions.assertEquals("3302002999", unscheduledResult.getMaterialCode());
        Assertions.assertEquals(Integer.valueOf(2), unscheduledResult.getUnscheduledQty());
        Assertions.assertEquals("收尾余量小于等于允许欠产偏差值，且前日 T+1 夜班未排满，本次不排产",
                unscheduledResult.getUnscheduledReason());
    }

    /**
     * 续作收尾小余量若前日 T+1 夜班已排满，应继续按原续作规则排产。
     */
    @Test
    public void shouldKeepSmallSurplusEndingContinuousWhenPreviousT1NightFull() throws Exception {
        ContinuousProductionStrategy strategy = new ContinuousProductionStrategy();
        injectContinuousEndingDependencies(strategy);
        LhScheduleContext context = buildContinuousReduceContext();
        List<LhShiftConfigVO> shifts = context.getScheduleWindowShifts();
        SkuScheduleDTO sku = buildContinuousSku("3302003000", 16, 2, buildQuotaMapByShifts(shifts, 2, 0, 0));
        sku.setContinuousMachineCode("K1202");
        sku.setSurplusQty(2);
        context.getContinuousSkuList().add(sku);
        appendTargetPreviousT1NightResult(context, sku.getMaterialCode(), 16);
        MachineScheduleDTO machine = new MachineScheduleDTO();
        machine.setMachineCode("K1202");
        machine.setCurrentMaterialCode("3302003000");
        machine.setEstimatedEndTime(shifts.get(0).getShiftStartDateTime());
        context.getMachineScheduleMap().put(machine.getMachineCode(), machine);
        context.getInitialMachineScheduleMap().put(machine.getMachineCode(), machine);

        strategy.scheduleContinuousEnding(context);

        Assertions.assertEquals(1, context.getScheduleResultList().size());
        Assertions.assertFalse(context.getReleasedContinuousMachineCodeSet().contains("K1202"));
        Assertions.assertTrue(context.getUnscheduledResultList().isEmpty());
    }

    /**
     * 强制重排下，收尾小余量规则仍应取业务目标日前一日结果，不受窗口T日前一日滚动基线影响。
     */
    @Test
    public void shouldUseTargetPreviousResultForSmallSurplusRuleWhenForceReschedule() throws Exception {
        ContinuousProductionStrategy strategy = new ContinuousProductionStrategy();
        injectContinuousEndingDependencies(strategy);
        LhScheduleContext context = buildContinuousReduceContext();
        context.getLhParamsMap().put(LhScheduleParamConstant.FORCE_RESCHEDULE, "1");
        context.setScheduleTargetDate(dateTime(2026, 6, 14, 0, 0));
        List<LhShiftConfigVO> shifts = context.getScheduleWindowShifts();
        SkuScheduleDTO sku = buildContinuousSku("3302003001", 16, 2, buildQuotaMapByShifts(shifts, 2, 0, 0));
        sku.setContinuousMachineCode("K1203");
        sku.setSurplusQty(2);
        context.getContinuousSkuList().add(sku);
        appendPreviousT1NightResult(context, sku.getMaterialCode(), 16);
        appendTargetPreviousT1NightResult(context, sku.getMaterialCode(), 8);
        MachineScheduleDTO machine = new MachineScheduleDTO();
        machine.setMachineCode("K1203");
        machine.setCurrentMaterialCode("3302003001");
        machine.setEstimatedEndTime(shifts.get(0).getShiftStartDateTime());
        context.getMachineScheduleMap().put(machine.getMachineCode(), machine);
        context.getInitialMachineScheduleMap().put(machine.getMachineCode(), machine);

        strategy.scheduleContinuousEnding(context);

        Assertions.assertEquals(0, context.getScheduleResultList().size());
        Assertions.assertTrue(context.getReleasedContinuousMachineCodeSet().contains("K1203"));
        Assertions.assertEquals(1, context.getUnscheduledResultList().size());
        Assertions.assertEquals("收尾余量小于等于允许欠产偏差值，且前日 T+1 夜班未排满，本次不排产",
                context.getUnscheduledResultList().get(0).getUnscheduledReason());
    }

    /**
     * 续作收尾小余量释放机台应优先进入换活字块候选。
     */
    @Test
    public void shouldUseSmallSurplusReleasedMachineAsTypeBlockCandidate() throws Exception {
        TypeBlockProductionStrategy strategy = new TypeBlockProductionStrategy();
        LhScheduleContext context = buildContinuousReduceContext();
        MachineScheduleDTO machine = new MachineScheduleDTO();
        machine.setMachineCode("K1201");
        machine.setCurrentMaterialCode("3302002999");
        machine.setEstimatedEndTime(context.getScheduleWindowShifts().get(0).getShiftStartDateTime());
        context.getMachineScheduleMap().put(machine.getMachineCode(), machine);
        context.getTypeBlockReleasedContinuousMachineCodeSet().add(machine.getMachineCode());

        Method method = TypeBlockProductionStrategy.class.getDeclaredMethod(
                "resolveReleasedTypeBlockMachines", LhScheduleContext.class);
        method.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<MachineScheduleDTO> candidateMachines =
                (List<MachineScheduleDTO>) method.invoke(strategy, context);

        Assertions.assertEquals(1, candidateMachines.size());
        Assertions.assertEquals("K1201", candidateMachines.get(0).getMachineCode());
    }

    /**
     * 欠产未超过阈值且当前日计划未满足时，T+2 仍需后看 T+3 日计划量决定是否保留/新增机台。
     */
    @Test
    public void shouldLookAheadNextDayPlanOnWindowLastDayWhenSmallShortage() {
        DailyMachineCapacitySimulationRequest request = new DailyMachineCapacitySimulationRequest();
        request.setMaterialCode("3302001236");
        request.setDailyPlanQuotaMap(buildQuotaMap(0, 8, 64, 96));
        request.setMachineDailyCapacityList(buildDailyCapacityMaps(2));
        request.setInitialActiveMachines(1);
        request.setShiftCapacity(16);
        request.setShortageLookAheadDays(1);
        request.setShortageAddMachineThreshold(150);
        request.setMonthlyHistoryShortageQty(0);
        request.setWindowEndDate(LocalDate.of(2026, 5, 3));
        request.setWindowLastDayNextPlanLookAheadEnabled(true);
        request.setSceneType("newSpec");

        DailyMachineCapacitySimulationResult result =
                DailyMachineCapacitySimulationUtil.simulateExpansion(request);

        Assertions.assertEquals(2, result.getFinalActiveMachines());
        DailyMachineCapacityDayDecision windowLastDayDecision = result.getDayDecisionList().get(2);
        Assertions.assertEquals(LocalDate.of(2026, 5, 3), windowLastDayDecision.getProductionDate());
        Assertions.assertEquals(LocalDate.of(2026, 5, 4), windowLastDayDecision.getLookAheadEndDate());
        Assertions.assertEquals(96, windowLastDayDecision.getNextDayPlanQty());
        Assertions.assertEquals(1, windowLastDayDecision.getAddedMachineCount());
    }

    /**
     * 欠产未超过阈值时，当前日计划已被当前机台数满足，应直接停止当日加机台判断，不再后看下一日计划。
     */
    @Test
    public void shouldSkipNextDayLookAheadWhenCurrentDayPlanSatisfied() {
        DailyMachineCapacitySimulationRequest request = new DailyMachineCapacitySimulationRequest();
        request.setMaterialCode("3302001237");
        request.setDailyPlanQuotaMap(buildQuotaMap(32, 144, 0));
        request.setMachineDailyCapacityList(buildDailyCapacityMaps(3));
        request.setInitialActiveMachines(1);
        request.setShiftCapacity(16);
        request.setShortageLookAheadDays(1);
        request.setShortageAddMachineThreshold(150);
        request.setMonthlyHistoryShortageQty(0);
        request.setWindowEndDate(LocalDate.of(2026, 5, 3));
        request.setWindowLastDayNextPlanLookAheadEnabled(true);
        request.setSceneType("newSpec");

        DailyMachineCapacitySimulationResult result =
                DailyMachineCapacitySimulationUtil.simulateExpansion(request);

        DailyMachineCapacityDayDecision firstDayDecision = result.getDayDecisionList().get(0);
        Assertions.assertEquals(LocalDate.of(2026, 5, 1), firstDayDecision.getProductionDate());
        Assertions.assertTrue(firstDayDecision.isCurrentDayPlanSatisfied());
        Assertions.assertFalse(firstDayDecision.isNextDayLookAheadEntered());
        Assertions.assertEquals(0, firstDayDecision.getAddedMachineCount());
    }

    /**
     * 欠产未超过阈值时，首日小计划已满足不加机台；滚动到次日后，当前日和下一日均超过单机日标准才加机台。
     */
    @Test
    public void shouldAddMachineOnSecondDayWhenCurrentAndNextDayExceedDailyStandardForSixtyPlan() {
        DailyMachineCapacitySimulationRequest request = buildDailyStandardRhythmRequest("3302002661", 8, 60, 60);

        DailyMachineCapacitySimulationResult result =
                DailyMachineCapacitySimulationUtil.simulateExpansion(request);

        Assertions.assertEquals(2, result.getFinalActiveMachines());
        Assertions.assertEquals(1, result.getTotalAddedMachineCount());
        DailyMachineCapacityDayDecision firstDayDecision = result.getDayDecisionList().get(0);
        Assertions.assertEquals(LocalDate.of(2026, 5, 1), firstDayDecision.getProductionDate());
        Assertions.assertTrue(firstDayDecision.isCurrentDayPlanSatisfied());
        Assertions.assertEquals(0, firstDayDecision.getAddedMachineCount());
        DailyMachineCapacityDayDecision secondDayDecision = result.getDayDecisionList().get(1);
        Assertions.assertEquals(LocalDate.of(2026, 5, 2), secondDayDecision.getProductionDate());
        Assertions.assertEquals(60, secondDayDecision.getCurrentDayPlanQty());
        Assertions.assertEquals(60, secondDayDecision.getNextDayPlanQty());
        Assertions.assertEquals(1, secondDayDecision.getAddedMachineCount());
    }

    /**
     * 欠产未超过阈值时，50/50 连续两日均超过单机日标准48，也必须在当前业务日补一台机台。
     */
    @Test
    public void shouldAddMachineOnSecondDayWhenCurrentAndNextDayExceedDailyStandardForFiftyPlan() {
        DailyMachineCapacitySimulationRequest request = buildDailyStandardRhythmRequest("3302001555", 8, 50, 50);

        DailyMachineCapacitySimulationResult result =
                DailyMachineCapacitySimulationUtil.simulateExpansion(request);

        Assertions.assertEquals(2, result.getFinalActiveMachines());
        Assertions.assertEquals(1, result.getTotalAddedMachineCount());
        DailyMachineCapacityDayDecision secondDayDecision = result.getDayDecisionList().get(1);
        Assertions.assertEquals(LocalDate.of(2026, 5, 2), secondDayDecision.getProductionDate());
        Assertions.assertEquals(50, secondDayDecision.getCurrentDayPlanQty());
        Assertions.assertEquals(50, secondDayDecision.getNextDayPlanQty());
        Assertions.assertEquals(1, secondDayDecision.getAddedMachineCount());
    }

    /**
     * 欠产未超过阈值时，只有当天和后一天计划均无法由当前机台满足，才允许增加机台。
     */
    @Test
    public void shouldKeepSingleMachineWhenCurrentDayMissesButNextDayIsCovered() {
        DailyMachineCapacitySimulationRequest request = new DailyMachineCapacitySimulationRequest();
        request.setMaterialCode("3302001074");
        request.setDailyPlanQuotaMap(buildQuotaMap(8, 92, 46, 46));
        request.setMachineDailyCapacityList(buildDailyCapacityMaps(2));
        request.setInitialActiveMachines(1);
        request.setShiftCapacity(16);
        request.setShortageLookAheadDays(1);
        request.setShortageAddMachineThreshold(200);
        request.setMonthlyHistoryShortageQty(0);
        request.setWindowEndDate(LocalDate.of(2026, 5, 3));
        request.setWindowLastDayNextPlanLookAheadEnabled(true);
        request.setSceneType("newSpec");

        DailyMachineCapacitySimulationResult result =
                DailyMachineCapacitySimulationUtil.simulateExpansion(request);

        Assertions.assertEquals(1, result.getFinalActiveMachines());
        DailyMachineCapacityDayDecision secondDayDecision = result.getDayDecisionList().get(1);
        Assertions.assertFalse(secondDayDecision.isCurrentDayPlanSatisfied());
        Assertions.assertTrue(secondDayDecision.isNextDayLookAheadEntered());
        Assertions.assertEquals(LocalDate.of(2026, 5, 3), secondDayDecision.getNextProductionDate());
        Assertions.assertEquals(46, secondDayDecision.getNextDayPlanQty());
        Assertions.assertEquals(0, secondDayDecision.getAddedMachineCount());
    }

    /**
     * T+1夜班虽然从T日22点开始，但提前生产判断必须使用T+1业务日，不能使用自然日。
     *
     * @throws Exception 反射调用异常
     */
    @Test
    public void shouldResolveCrossDayNightShiftByBusinessDate() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        LhScheduleContext context = new LhScheduleContext();
        context.setScheduleDate(dateTime(2026, 5, 1, 0, 0));
        List<LhShiftConfigVO> shifts = LhScheduleTimeUtil.buildDefaultScheduleShifts(
                context, context.getScheduleDate());
        LhShiftConfigVO tPlusOneNightShift = shifts.get(2);

        Method method = NewSpecProductionStrategy.class.getDeclaredMethod(
                "resolveProductionWorkDate", List.class, Date.class);
        method.setAccessible(true);
        LocalDate productionWorkDate = (LocalDate) method.invoke(
                strategy, shifts, tPlusOneNightShift.getShiftStartDateTime());

        Assertions.assertEquals(LocalDate.of(2026, 5, 2), productionWorkDate,
                "跨日夜班必须按班次业务日判断提前生产");
    }

    /**
     * 同胎胚同模具且仅存在历史欠产额度时，换活字块必须先准备账本并落 S4.4 结果。
     */
    @Test
    public void shouldAppendTypeBlockResultWhenResidualShiftHasCapacity() throws Exception {
        TypeBlockProductionStrategy strategy = new TypeBlockProductionStrategy();
        injectTypeBlockAppendDependencies(strategy);
        LhScheduleContext context = buildTypeBlockAppendContext();
        List<LhShiftConfigVO> shifts = context.getScheduleWindowShifts();
        MachineScheduleDTO machine = buildTypeBlockMachine();
        SkuScheduleDTO sku = buildTypeBlockSkuWithRollingQuota();
        context.getMachineScheduleMap().put(machine.getMachineCode(), machine);
        context.getNewSpecSkuList().add(sku);

        Method method = TypeBlockProductionStrategy.class.getDeclaredMethod(
                "appendTypeBlockResultWithRollback",
                LhScheduleContext.class, MachineScheduleDTO.class, SkuScheduleDTO.class,
                Date.class, Date.class, List.class, boolean.class, StringBuilder.class);
        method.setAccessible(true);
        StringBuilder failureReason = new StringBuilder(64);
        boolean success = (Boolean) method.invoke(strategy, context, machine, sku,
                dateTime(2026, 6, 8, 15, 27), dateTime(2026, 6, 8, 7, 27),
                shifts, true, failureReason);

        Assertions.assertTrue(success);
        Assertions.assertEquals("", failureReason.toString());
        Assertions.assertEquals(1, context.getScheduleResultList().size());
        LhScheduleResult result = context.getScheduleResultList().get(0);
        Assertions.assertEquals("03", result.getScheduleType());
        Assertions.assertEquals("1", result.getIsTypeBlock());
        Assertions.assertEquals("1", result.getIsChangeMould());
        Assertions.assertEquals(Integer.valueOf(20), result.getClass8PlanQty());
        Assertions.assertEquals(Integer.valueOf(20), result.getDailyPlanQty());
    }

    /**
     * 共用胎胚且硫化余量为0时，换活字块不能按班产补量，也不能继续回流新增排产。
     */
    @Test
    public void shouldBlockTypeBlockWhenSharedEmbryoSurplusIsZero() throws Exception {
        TypeBlockProductionStrategy strategy = new TypeBlockProductionStrategy();
        injectTypeBlockAppendDependencies(strategy);
        LhScheduleContext context = buildTypeBlockAppendContext();
        List<LhShiftConfigVO> shifts = context.getScheduleWindowShifts();
        MachineScheduleDTO machine = buildTypeBlockMachine();
        SkuScheduleDTO sku = buildTypeBlockSkuWithRollingQuota();
        sku.setSurplusQty(0);
        context.getMachineScheduleMap().put(machine.getMachineCode(), machine);
        context.getNewSpecSkuList().add(sku);
        context.getMaterialSharedEmbryoMap().put(sku.getMaterialCode(), true);

        Method method = TypeBlockProductionStrategy.class.getDeclaredMethod(
                "appendTypeBlockResultWithRollback",
                LhScheduleContext.class, MachineScheduleDTO.class, SkuScheduleDTO.class,
                Date.class, Date.class, List.class, boolean.class, StringBuilder.class);
        method.setAccessible(true);
        StringBuilder failureReason = new StringBuilder(64);
        boolean success = (Boolean) method.invoke(strategy, context, machine, sku,
                dateTime(2026, 6, 8, 15, 27), dateTime(2026, 6, 8, 7, 27),
                shifts, true, failureReason);

        Assertions.assertFalse(success);
        Assertions.assertEquals(0, context.getScheduleResultList().size());
        Assertions.assertEquals(1, context.getUnscheduledResultList().size());
        Assertions.assertFalse(context.getNewSpecSkuList().contains(sku));
        Assertions.assertTrue(failureReason.toString().contains("共用胎胚且硫化余量为0"));
    }

    /**
     * 换活字块不释放、不重选新模具，结果模具号应沿用当前机台在机物料的实际模具号。
     */
    @Test
    public void shouldUseCurrentMachineMouldCodeForTypeBlockResult() throws Exception {
        TypeBlockProductionStrategy strategy = new TypeBlockProductionStrategy();
        LhScheduleContext context = buildChangeoverBalanceContext();
        putMouldRels(context, "SKU-CURRENT", "M001", "M002");
        putMouldRels(context, "SKU-NEXT", "M001", "M002", "M099");
        MachineScheduleDTO machine = new MachineScheduleDTO();
        machine.setMachineCode("K1305");
        machine.setCurrentMaterialCode("SKU-CURRENT");
        machine.setMaxMoldNum(2);
        SkuScheduleDTO sku = new SkuScheduleDTO();
        sku.setMaterialCode("SKU-NEXT");
        LhMachineOnlineInfo onlineInfo = new LhMachineOnlineInfo();
        onlineInfo.setLhCode("K1305");
        onlineInfo.setInMachineMouldCode("M010, M011");
        context.getMachineOnlineInfoMap().put("K1305", onlineInfo);

        Method method = TypeBlockProductionStrategy.class.getDeclaredMethod(
                "resolveTypeBlockActualMouldCode", LhScheduleContext.class, MachineScheduleDTO.class, SkuScheduleDTO.class);
        method.setAccessible(true);
        String mouldCode = (String) method.invoke(strategy, context, machine, sku);

        Assertions.assertEquals("M010,M011", mouldCode);
    }

    /**
     * 新增SKU候选为空且目标SKU所有模具都被已排结果占用时，未排原因应明确指向模具占用。
     */
    @Test
    public void shouldUseMouldOccupiedReasonWhenTargetSkuMouldAllOccupied() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        LhScheduleContext context = buildChangeoverBalanceContext();
        SkuScheduleDTO sku = buildNewSpecFirstInspectionSku(80);
        sku.setMaterialCode("3302001078");
        sku.setProSize("R22.5");
        putMouldRels(context, sku.getMaterialCode(), "M001", "M002");
        appendAssignedResultWithMould(context, "K1106", sku.getMaterialCode(), "M001,M002");

        Method method = NewSpecProductionStrategy.class.getDeclaredMethod(
                "resolveNoCandidateMachineReason", LhScheduleContext.class, SkuScheduleDTO.class);
        method.setAccessible(true);
        String reason = (String) method.invoke(strategy, context, sku);

        Assertions.assertEquals("目标 SKU 模具全部被占用", reason);
    }

    /**
     * 新增SKU候选为空但仍有未占用模具时，仍保留通用无可用硫化机台原因。
     */
    @Test
    public void shouldKeepGenericReasonWhenTargetSkuHasFreeMould() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        LhScheduleContext context = buildChangeoverBalanceContext();
        SkuScheduleDTO sku = buildNewSpecFirstInspectionSku(80);
        sku.setMaterialCode("3302001079");
        sku.setProSize("R22.5");
        putMouldRels(context, sku.getMaterialCode(), "M001", "M002");
        appendAssignedResultWithMould(context, "K1106", sku.getMaterialCode(), "M001");

        Method method = NewSpecProductionStrategy.class.getDeclaredMethod(
                "resolveNoCandidateMachineReason", LhScheduleContext.class, SkuScheduleDTO.class);
        method.setAccessible(true);
        String reason = (String) method.invoke(strategy, context, sku);

        Assertions.assertEquals("无可用硫化机台", reason);
    }

    private SkuScheduleDTO buildSkuForTypeBlockExpansion() {
        SkuScheduleDTO sku = new SkuScheduleDTO();
        sku.setMaterialCode("3302002654");
        sku.setTargetScheduleQty(136);
        sku.setWindowPlanQty(300);
        sku.setWindowRemainingPlanQty(300);
        sku.setSurplusQty(1752);
        sku.setDailyPlanQuotaMap(buildQuotaMap(100, 100, 100));
        return sku;
    }

    private Map<LocalDate, SkuDailyPlanQuotaDTO> buildQuotaMap(int day1Qty, int day2Qty, int day3Qty) {
        Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap = new LinkedHashMap<LocalDate, SkuDailyPlanQuotaDTO>(4);
        quotaMap.put(LocalDate.of(2026, 5, 1), buildQuota(day1Qty));
        quotaMap.put(LocalDate.of(2026, 5, 2), buildQuota(day2Qty));
        quotaMap.put(LocalDate.of(2026, 5, 3), buildQuota(day3Qty));
        return quotaMap;
    }

    private Map<LocalDate, SkuDailyPlanQuotaDTO> buildQuotaMap(
            int day1Qty, int day2Qty, int day3Qty, int day4Qty) {
        Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap = buildQuotaMap(day1Qty, day2Qty, day3Qty);
        quotaMap.put(LocalDate.of(2026, 5, 4), buildQuota(day4Qty));
        return quotaMap;
    }

    private List<Map<LocalDate, Integer>> buildDailyCapacityMaps(int machineCount) {
        List<Map<LocalDate, Integer>> machineCapacityList =
                new ArrayList<Map<LocalDate, Integer>>(Math.max(1, machineCount));
        for (int index = 0; index < machineCount; index++) {
            Map<LocalDate, Integer> capacityMap = new LinkedHashMap<LocalDate, Integer>(4);
            capacityMap.put(LocalDate.of(2026, 5, 1), 32);
            capacityMap.put(LocalDate.of(2026, 5, 2), 48);
            capacityMap.put(LocalDate.of(2026, 5, 3), 48);
            machineCapacityList.add(capacityMap);
        }
        return machineCapacityList;
    }

    private List<Map<LocalDate, Integer>> buildDailyCapacityMaps(int machineCount,
                                                                 int day1Capacity,
                                                                 int day2Capacity,
                                                                 int day3Capacity) {
        List<Map<LocalDate, Integer>> machineCapacityList =
                new ArrayList<Map<LocalDate, Integer>>(Math.max(1, machineCount));
        for (int index = 0; index < machineCount; index++) {
            Map<LocalDate, Integer> capacityMap = new LinkedHashMap<LocalDate, Integer>(4);
            capacityMap.put(LocalDate.of(2026, 5, 1), day1Capacity);
            capacityMap.put(LocalDate.of(2026, 5, 2), day2Capacity);
            capacityMap.put(LocalDate.of(2026, 5, 3), day3Capacity);
            machineCapacityList.add(capacityMap);
        }
        return machineCapacityList;
    }

    @SuppressWarnings("unchecked")
    private Map<LocalDate, SkuDailyPlanQuotaDTO> invokeBuildSimulationQuotaMap(
            NewSpecProductionStrategy strategy,
            SkuScheduleDTO sku,
            Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap,
            int remainingTargetQty,
            LocalDate windowEndDate) throws Exception {
        Method method = NewSpecProductionStrategy.class.getDeclaredMethod(
                "buildSimulationQuotaMap", SkuScheduleDTO.class, Map.class, int.class, LocalDate.class);
        method.setAccessible(true);
        return (Map<LocalDate, SkuDailyPlanQuotaDTO>) method.invoke(
                strategy, sku, quotaMap, remainingTargetQty, windowEndDate);
    }

    private boolean invokeIsExistingSameMaterialSimulationSatisfied(
            NewSpecProductionStrategy strategy,
            DailyMachineCapacitySimulationRequest request,
            List<Map<LocalDate, Integer>> existingMachineCapacityMaps,
            int requiredMachineCountByDailyCapacity) throws Exception {
        Method method = NewSpecProductionStrategy.class.getDeclaredMethod(
                "isExistingSameMaterialSimulationSatisfied",
                DailyMachineCapacitySimulationRequest.class, List.class, int.class);
        method.setAccessible(true);
        return (Boolean) method.invoke(
                strategy, request, existingMachineCapacityMaps, requiredMachineCountByDailyCapacity);
    }

    private DailyMachineCapacitySimulationRequest buildDailyStandardRhythmRequest(String materialCode,
                                                                                  int day1Qty,
                                                                                  int day2Qty,
                                                                                  int day3Qty) {
        DailyMachineCapacitySimulationRequest request = new DailyMachineCapacitySimulationRequest();
        request.setMaterialCode(materialCode);
        request.setDailyPlanQuotaMap(buildQuotaMap(day1Qty, day2Qty, day3Qty));
        request.setMachineDailyCapacityList(buildDailyStandardRhythmCapacityMaps(day1Qty, day2Qty, day3Qty));
        request.setInitialActiveMachines(1);
        request.setShiftCapacity(16);
        request.setShortageLookAheadDays(1);
        request.setShortageAddMachineThreshold(150);
        request.setMonthlyHistoryShortageQty(0);
        request.setWindowEndDate(LocalDate.of(2026, 5, 3));
        request.setWindowLastDayNextPlanLookAheadEnabled(true);
        request.setSceneType("newSpec");
        return request;
    }

    private List<Map<LocalDate, Integer>> buildDailyStandardRhythmCapacityMaps(int day1Qty,
                                                                               int day2Qty,
                                                                               int day3Qty) {
        List<Map<LocalDate, Integer>> machineCapacityList = new ArrayList<Map<LocalDate, Integer>>(2);
        Map<LocalDate, Integer> existingMachineCapacityMap = new LinkedHashMap<LocalDate, Integer>(4);
        existingMachineCapacityMap.put(LocalDate.of(2026, 5, 1), day1Qty);
        existingMachineCapacityMap.put(LocalDate.of(2026, 5, 2), day2Qty);
        existingMachineCapacityMap.put(LocalDate.of(2026, 5, 3), day3Qty);
        machineCapacityList.add(existingMachineCapacityMap);
        Map<LocalDate, Integer> candidateMachineCapacityMap = new LinkedHashMap<LocalDate, Integer>(4);
        candidateMachineCapacityMap.put(LocalDate.of(2026, 5, 1), 0);
        candidateMachineCapacityMap.put(LocalDate.of(2026, 5, 2), 48);
        candidateMachineCapacityMap.put(LocalDate.of(2026, 5, 3), 48);
        machineCapacityList.add(candidateMachineCapacityMap);
        return machineCapacityList;
    }

    private SkuDailyPlanQuotaDTO buildQuota(int qty) {
        SkuDailyPlanQuotaDTO quota = new SkuDailyPlanQuotaDTO();
        quota.setDayPlanQty(qty);
        quota.setRemainingQty(qty);
        return quota;
    }

    private LhScheduleContext buildContinuousReduceContext() {
        LhScheduleContext context = buildChangeoverBalanceContext();
        context.setScheduleDate(dateTime(2026, 6, 11, 0, 0));
        context.setScheduleTargetDate(dateTime(2026, 6, 12, 0, 0));
        context.setScheduleWindowShifts(LhScheduleTimeUtil.buildDefaultScheduleShifts(context, context.getScheduleDate()));
        context.setScheduleResultList(new ArrayList<LhScheduleResult>());
        context.setContinuousSkuList(new ArrayList<SkuScheduleDTO>());
        context.setNewSpecSkuList(new ArrayList<SkuScheduleDTO>());
        return context;
    }

    private void applyContinuousNoFutureEndingStrictTarget(ContinuousProductionStrategy strategy,
                                                           SkuScheduleDTO sku,
                                                           Object shortageQuotaPlan) throws Exception {
        Method method = ContinuousProductionStrategy.class.getDeclaredMethod(
                "applyContinuousNoFutureEndingStrictTarget", SkuScheduleDTO.class,
                shortageQuotaPlan.getClass());
        method.setAccessible(true);
        method.invoke(strategy, sku, shortageQuotaPlan);
    }

    private SkuScheduleDTO buildContinuousSku(String materialCode,
                                              int shiftCapacity,
                                              int targetQty,
                                              Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap) {
        SkuScheduleDTO sku = new SkuScheduleDTO();
        sku.setMaterialCode(materialCode);
        sku.setShiftCapacity(shiftCapacity);
        sku.setTargetScheduleQty(targetQty);
        sku.setRemainingScheduleQty(targetQty);
        sku.setWindowPlanQty(targetQty);
        sku.setWindowRemainingPlanQty(targetQty);
        sku.setSurplusQty(targetQty);
        sku.setDailyPlanQuotaMap(quotaMap);
        return sku;
    }

    private LhScheduleResult buildContinuousResult(String materialCode,
                                                   String machineCode,
                                                   int shiftCapacity,
                                                   List<LhShiftConfigVO> shifts,
                                                   String isEnd) {
        LhScheduleResult result = new LhScheduleResult();
        result.setMaterialCode(materialCode);
        result.setLhMachineCode(machineCode);
        result.setScheduleType(ScheduleTypeEnum.CONTINUOUS.getCode());
        result.setIsTypeBlock("0");
        result.setIsEnd(isEnd);
        result.setMouldQty(2);
        result.setSingleMouldShiftQty(shiftCapacity);
        result.setLhTime(3600);
        for (LhShiftConfigVO shift : shifts) {
            ShiftFieldUtil.setShiftPlanQty(result, shift.getShiftIndex(), shiftCapacity,
                    shift.getShiftStartDateTime(), shift.getShiftEndDateTime());
        }
        ShiftFieldUtil.syncDailyPlanQty(result);
        return result;
    }

    private void appendPreviousT1NightResult(LhScheduleContext context, String materialCode, int nightPlanQty) {
        Integer nightShiftIndex = LhScheduleTimeUtil.findFirstNightShiftIndexWithOffset(
                context.getScheduleWindowShifts(), 1);
        LhScheduleResult previousResult = new LhScheduleResult();
        previousResult.setMaterialCode(materialCode);
        previousResult.setSingleMouldShiftQty(16);
        ShiftFieldUtil.setShiftPlanQty(previousResult, nightShiftIndex, nightPlanQty, null, null);
        ShiftFieldUtil.syncDailyPlanQty(previousResult);
        context.getPreviousScheduleResultList().add(previousResult);
    }

    private void appendTargetPreviousT1NightResult(LhScheduleContext context, String materialCode, int nightPlanQty) {
        Integer nightShiftIndex = LhScheduleTimeUtil.findFirstNightShiftIndexWithOffset(
                context.getScheduleWindowShifts(), 1);
        LhScheduleResult previousResult = new LhScheduleResult();
        previousResult.setMaterialCode(materialCode);
        previousResult.setSingleMouldShiftQty(16);
        ShiftFieldUtil.setShiftPlanQty(previousResult, nightShiftIndex, nightPlanQty, null, null);
        ShiftFieldUtil.syncDailyPlanQty(previousResult);
        context.getTargetPreviousScheduleResultList().add(previousResult);
    }

    private Map<LocalDate, SkuDailyPlanQuotaDTO> buildQuotaMapByShifts(List<LhShiftConfigVO> shifts,
                                                                        int day1Qty,
                                                                        int day2Qty,
                                                                        int day3Qty) {
        Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap = new LinkedHashMap<LocalDate, SkuDailyPlanQuotaDTO>(4);
        int[] qtyArray = new int[]{day1Qty, day2Qty, day3Qty};
        for (LhShiftConfigVO shift : shifts) {
            LocalDate workDate = shift.getWorkDate().toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
            if (quotaMap.containsKey(workDate)) {
                continue;
            }
            int index = Math.min(quotaMap.size(), qtyArray.length - 1);
            quotaMap.put(workDate, buildQuota(qtyArray[index]));
        }
        return quotaMap;
    }

    /**
     * 注入测试用原始月计划，用于区分运行态dayN账本剩余额度和月计划原始日计划量。
     *
     * @param context 排程上下文
     * @param materialCode 物料编码
     * @param firstDate 首个业务日
     * @param day1Qty 首日原始日计划量
     * @param day2Qty 第二日原始日计划量
     * @param day3Qty 第三日原始日计划量
     */
    private void appendMonthPlan(LhScheduleContext context,
                                 String materialCode,
                                 LocalDate firstDate,
                                 int day1Qty,
                                 int day2Qty,
                                 int day3Qty) {
        FactoryMonthPlanProductionFinalResult plan = new FactoryMonthPlanProductionFinalResult();
        plan.setMaterialCode(materialCode);
        plan.setYear(firstDate.getYear());
        plan.setMonth(firstDate.getMonthValue());
        setMonthPlanDayQty(plan, firstDate, day1Qty);
        setMonthPlanDayQty(plan, firstDate.plusDays(1), day2Qty);
        setMonthPlanDayQty(plan, firstDate.plusDays(2), day3Qty);
        context.getMonthPlanList().add(plan);
        context.getMonthPlanByMaterialMonthMap().put(
                materialCode + "_" + firstDate.getYear() + "_" + firstDate.getMonthValue(), plan);
    }

    private void setMonthPlanDayQty(FactoryMonthPlanProductionFinalResult plan,
                                    LocalDate date,
                                    int qty) {
        switch (date.getDayOfMonth()) {
            case 11:
                plan.setDay11(qty);
                break;
            case 12:
                plan.setDay12(qty);
                break;
            case 13:
                plan.setDay13(qty);
                break;
            case 27:
                plan.setDay27(qty);
                break;
            case 28:
                plan.setDay28(qty);
                break;
            case 29:
                plan.setDay29(qty);
                break;
            default:
                throw new IllegalArgumentException("测试月计划日期未支持: " + date);
        }
    }

    private int countPositiveMachineByWorkDate(List<LhScheduleResult> results,
                                               List<LhShiftConfigVO> shifts,
                                               LocalDate workDate) {
        int count = 0;
        for (LhScheduleResult result : results) {
            int qty = 0;
            for (LhShiftConfigVO shift : shifts) {
                LocalDate currentDate = shift.getWorkDate().toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
                if (!workDate.equals(currentDate)) {
                    continue;
                }
                Integer shiftPlanQty = ShiftFieldUtil.getShiftPlanQty(result, shift.getShiftIndex());
                qty += shiftPlanQty == null ? 0 : Math.max(0, shiftPlanQty);
            }
            if (qty > 0) {
                count++;
            }
        }
        return count;
    }

    private int sumPlanQtyByWorkDate(List<LhScheduleResult> results,
                                     List<LhShiftConfigVO> shifts,
                                     LocalDate workDate) {
        int totalQty = 0;
        for (LhScheduleResult result : results) {
            for (LhShiftConfigVO shift : shifts) {
                LocalDate currentDate = shift.getWorkDate().toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
                if (!workDate.equals(currentDate)) {
                    continue;
                }
                Integer shiftPlanQty = ShiftFieldUtil.getShiftPlanQty(result, shift.getShiftIndex());
                totalQty += shiftPlanQty == null ? 0 : Math.max(0, shiftPlanQty);
            }
        }
        return totalQty;
    }

    private LocalDate resolveShiftWorkDate(List<LhShiftConfigVO> shifts, int dayIndex) {
        List<LocalDate> dateList = new ArrayList<LocalDate>(4);
        for (LhShiftConfigVO shift : shifts) {
            LocalDate workDate = shift.getWorkDate().toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
            if (!dateList.contains(workDate)) {
                dateList.add(workDate);
            }
        }
        return dateList.get(dayIndex - 1);
    }

    private void clearShiftPlanQty(LhScheduleResult result, List<LhShiftConfigVO> shifts, int shiftIndex) {
        for (LhShiftConfigVO shift : shifts) {
            if (shift.getShiftIndex() == shiftIndex) {
                ShiftFieldUtil.setShiftPlanQty(result, shiftIndex, 0, null, null);
                return;
            }
        }
    }

    private void setShiftPlanQty(LhScheduleResult result, List<LhShiftConfigVO> shifts, int shiftIndex, int planQty) {
        for (LhShiftConfigVO shift : shifts) {
            if (shift.getShiftIndex() == shiftIndex) {
                ShiftFieldUtil.setShiftPlanQty(result, shiftIndex, planQty,
                        shift.getShiftStartDateTime(), shift.getShiftEndDateTime());
                return;
            }
        }
    }

    private int resolveShiftPlanQty(LhScheduleResult result, int shiftIndex) {
        Integer planQty = ShiftFieldUtil.getShiftPlanQty(result, shiftIndex);
        return planQty == null ? 0 : Math.max(0, planQty);
    }

    private List<LhShiftConfigVO> buildSimulationShifts() {
        List<LhShiftConfigVO> shifts = new ArrayList<LhShiftConfigVO>(3);
        shifts.add(buildShift(1, 0));
        shifts.add(buildShift(2, 1));
        shifts.add(buildShift(3, 2));
        return shifts;
    }

    private LhShiftConfigVO buildShift(int shiftIndex, int dateOffset) {
        LhShiftConfigVO shift = new LhShiftConfigVO();
        shift.setShiftIndex(shiftIndex);
        shift.setDateOffset(dateOffset);
        shift.setScheduleBaseDate(Date.from(LocalDate.of(2026, 5, 1)
                .atStartOfDay(ZoneId.systemDefault()).toInstant()));
        return shift;
    }

    private void injectTypeBlockAppendDependencies(TypeBlockProductionStrategy strategy) throws Exception {
        OrderNoGenerator orderNoGenerator = new OrderNoGenerator();
        setField(orderNoGenerator, "useRedis", false);
        setField(strategy, "orderNoGenerator", orderNoGenerator);
        setField(strategy, "targetScheduleQtyResolver", new TargetScheduleQtyResolver());
        setField(strategy, "mouldChangeBalanceStrategy", new IMouldChangeBalanceStrategy() {
            @Override
            public boolean hasCapacity(LhScheduleContext context, Date targetDate) {
                return true;
            }

            @Override
            public Date allocateMouldChange(LhScheduleContext context, String machineCode, Date endingTime) {
                return endingTime;
            }

            @Override
            public int getRemainingCapacity(LhScheduleContext context, Date targetDate) {
                return 1;
            }
        });
        setField(strategy, "endingJudgmentStrategy", new IEndingJudgmentStrategy() {
            @Override
            public boolean isEnding(LhScheduleContext context, SkuScheduleDTO sku) {
                return true;
            }

            @Override
            public int calculateEndingShifts(LhScheduleContext context, SkuScheduleDTO sku) {
                return 1;
            }

            @Override
            public int calculateEndingDays(LhScheduleContext context, SkuScheduleDTO sku) {
                return 1;
            }
        });
    }

    private void injectContinuousEndingDependencies(ContinuousProductionStrategy strategy) throws Exception {
        OrderNoGenerator orderNoGenerator = new OrderNoGenerator();
        setField(orderNoGenerator, "useRedis", false);
        setField(strategy, "orderNoGenerator", orderNoGenerator);
        setField(strategy, "targetScheduleQtyResolver", new TargetScheduleQtyResolver());
        setField(strategy, "endingJudgmentStrategy", new IEndingJudgmentStrategy() {
            @Override
            public boolean isEnding(LhScheduleContext context, SkuScheduleDTO sku) {
                return true;
            }

            @Override
            public int calculateEndingShifts(LhScheduleContext context, SkuScheduleDTO sku) {
                return 1;
            }

            @Override
            public int calculateEndingDays(LhScheduleContext context, SkuScheduleDTO sku) {
                return 1;
            }
        });
    }

    /**
     * 注入非收尾续作测试依赖。
     *
     * @param strategy 续作排产策略
     * @throws Exception 反射设置依赖异常
     */
    private void injectContinuousNonEndingDependencies(ContinuousProductionStrategy strategy) throws Exception {
        injectContinuousEndingDependencies(strategy);
        setField(strategy, "endingJudgmentStrategy", new IEndingJudgmentStrategy() {
            @Override
            public boolean isEnding(LhScheduleContext context, SkuScheduleDTO sku) {
                return false;
            }

            @Override
            public int calculateEndingShifts(LhScheduleContext context, SkuScheduleDTO sku) {
                return 0;
            }

            @Override
            public int calculateEndingDays(LhScheduleContext context, SkuScheduleDTO sku) {
                return 0;
            }
        });
    }

    private void injectNewSpecBuildDependencies(NewSpecProductionStrategy strategy) throws Exception {
        OrderNoGenerator orderNoGenerator = new OrderNoGenerator();
        setField(orderNoGenerator, "useRedis", false);
        setField(strategy, "orderNoGenerator", orderNoGenerator);
        setField(strategy, "targetScheduleQtyResolver", new TargetScheduleQtyResolver());
    }

    private LhScheduleContext buildNewSpecFirstInspectionContext() {
        LhScheduleContext context = buildChangeoverBalanceContext();
        context.setBatchNo("TEST_BATCH");
        context.setFactoryCode("116");
        context.setScheduleTargetDate(dateTime(2026, 6, 3, 0, 0));
        context.setScheduleResultList(new ArrayList<LhScheduleResult>());
        context.setNewSpecSkuList(new ArrayList<SkuScheduleDTO>());
        return context;
    }

    private MachineScheduleDTO buildNewSpecMachine(String machineCode) {
        MachineScheduleDTO machine = new MachineScheduleDTO();
        machine.setMachineCode(machineCode);
        machine.setMachineName(machineCode);
        machine.setMaxMoldNum(1);
        return machine;
    }

    private ICapacityCalculateStrategy buildFixedCapacityCalculateStrategy() {
        return new ICapacityCalculateStrategy() {
            @Override
            public int calculateShiftCapacity(LhScheduleContext context, int lhTimeSeconds, int mouldQty) {
                return 16;
            }

            @Override
            public Date calculateStartTime(LhScheduleContext context, String machineCode, Date endingTime) {
                return endingTime;
            }

            @Override
            public int calculateFirstShiftQty(Date startTime, Date shiftEndTime, int lhTimeSeconds, int mouldQty) {
                return 16;
            }

            @Override
            public int calculateDailyCapacity(int lhTimeSeconds, int mouldQty) {
                return 48;
            }
        };
    }

    private SkuScheduleDTO buildNewSpecFirstInspectionSku(int targetQty) {
        SkuScheduleDTO sku = new SkuScheduleDTO();
        sku.setMaterialCode("3302001888");
        sku.setMaterialDesc("首检数量测试SKU");
        sku.setSpecCode("TEST_SPEC");
        sku.setSpecDesc("TEST_SPEC_DESC");
        sku.setEmbryoCode("E1888");
        sku.setShiftCapacity(16);
        sku.setLhTimeSeconds(1800);
        sku.setTargetScheduleQty(targetQty);
        sku.setRemainingScheduleQty(targetQty);
        sku.setWindowPlanQty(targetQty);
        sku.setWindowRemainingPlanQty(targetQty);
        sku.setSurplusQty(targetQty);
        sku.setEmbryoStock(targetQty);
        sku.setMonthPlanQty(targetQty);
        return sku;
    }

    private LhScheduleResult buildNewSpecFirstInspectionResult(NewSpecProductionStrategy strategy,
                                                               LhScheduleContext context,
                                                               MachineScheduleDTO machine,
                                                               SkuScheduleDTO sku) throws Exception {
        context.getMachineScheduleMap().put(machine.getMachineCode(), machine);
        LhScheduleResult result = invokeBuildNewSpecScheduleResult(
                strategy, context, machine, sku,
                dateTime(2026, 6, 1, 14, 0),
                dateTime(2026, 6, 1, 6, 0),
                dateTime(2026, 6, 1, 14, 0),
                context.getScheduleWindowShifts(), 1, false);
        context.getScheduleResultList().add(result);
        return result;
    }

    private void assertNewSpecMorningChangeoverKeepsMorning(String constructionStage,
                                                            boolean smallBatchValidation) throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        injectNewSpecBuildDependencies(strategy);
        LhScheduleContext context = buildNewSpecFirstInspectionContext();
        MachineScheduleDTO machine = buildNewSpecMachine("K1101");
        context.getMachineScheduleMap().put(machine.getMachineCode(), machine);
        SkuScheduleDTO sku = buildNewSpecFirstInspectionSku(16);
        sku.setConstructionStage(constructionStage);
        sku.setSmallBatchValidation(smallBatchValidation);

        LhScheduleResult result = invokeBuildNewSpecScheduleResult(
                strategy, context, machine, sku,
                dateTime(2026, 6, 1, 14, 0),
                dateTime(2026, 6, 1, 6, 0),
                dateTime(2026, 6, 1, 14, 0),
                context.getScheduleWindowShifts(), 1, false);

        Assertions.assertEquals(4, resolveShiftPlanQty(result, 1));
        Assertions.assertEquals(12, resolveShiftPlanQty(result, 2));
        Assertions.assertEquals(1, context.getShiftFirstInspectionCountMap().get("2026-06-01#1").intValue());
        Assertions.assertFalse(context.getShiftFirstInspectionCountMap().containsKey("2026-06-01#2"));
    }

    private LhScheduleResult invokeBuildNewSpecScheduleResult(NewSpecProductionStrategy strategy,
                                                              LhScheduleContext context,
                                                              MachineScheduleDTO machine,
                                                              SkuScheduleDTO sku,
                                                              Date startTime,
                                                              Date mouldChangeStartTime,
                                                              Date mouldChangeEndTime,
                                                              List<LhShiftConfigVO> shifts,
                                                              int mouldQty,
                                                              boolean isEnding) throws Exception {
        Method method = NewSpecProductionStrategy.class.getDeclaredMethod(
                "buildNewSpecScheduleResult", LhScheduleContext.class, MachineScheduleDTO.class,
                SkuScheduleDTO.class, Date.class, Date.class, Date.class, List.class, int.class,
                boolean.class, MouldResourceAllocationResult.class);
        method.setAccessible(true);
        return (LhScheduleResult) method.invoke(strategy, context, machine, sku, startTime,
                mouldChangeStartTime, mouldChangeEndTime, shifts, mouldQty, isEnding, null);
    }

    private LhScheduleResult invokeBuildTypeBlockScheduleResult(TypeBlockProductionStrategy strategy,
                                                                LhScheduleContext context,
                                                                MachineScheduleDTO machine,
                                                                SkuScheduleDTO sku,
                                                                Date startTime,
                                                                Date switchStartTime,
                                                                List<LhShiftConfigVO> shifts,
                                                                int mouldQty,
                                                                boolean isEnding) throws Exception {
        Method method = TypeBlockProductionStrategy.class.getDeclaredMethod(
                "buildScheduleResult", LhScheduleContext.class, MachineScheduleDTO.class,
                SkuScheduleDTO.class, Date.class, Date.class, List.class, int.class, boolean.class);
        method.setAccessible(true);
        return (LhScheduleResult) method.invoke(strategy, context, machine, sku, startTime,
                switchStartTime, shifts, mouldQty, isEnding);
    }

    private LhScheduleContext buildTypeBlockAppendContext() {
        LhScheduleContext context = buildChangeoverBalanceContext();
        context.setScheduleDate(dateTime(2026, 6, 6, 0, 0));
        context.setScheduleTargetDate(dateTime(2026, 6, 8, 0, 0));
        context.setBatchNo("TEST_BATCH");
        context.setFactoryCode("116");
        context.setScheduleWindowShifts(LhScheduleTimeUtil.buildDefaultScheduleShifts(context, context.getScheduleDate()));
        context.setScheduleResultList(new ArrayList<LhScheduleResult>());
        context.setNewSpecSkuList(new ArrayList<SkuScheduleDTO>());
        return context;
    }

    private MachineScheduleDTO buildTypeBlockMachine() {
        MachineScheduleDTO machine = new MachineScheduleDTO();
        machine.setMachineCode("K1305");
        machine.setMachineName("K1305");
        machine.setCurrentMaterialCode("3302002531");
        machine.setMaxMoldNum(2);
        machine.setEstimatedEndTime(dateTime(2026, 6, 8, 7, 27));
        return machine;
    }

    private SkuScheduleDTO buildTypeBlockSkuWithRollingQuota() {
        SkuScheduleDTO sku = new SkuScheduleDTO();
        sku.setMaterialCode("3302002642");
        sku.setMaterialDesc("215/75R17.5 128/126M 16PR UD150 BL3HEU DL");
        sku.setSpecCode("215/75R17.5");
        sku.setEmbryoCode("215103006");
        sku.setShiftCapacity(22);
        sku.setLhTimeSeconds(3600);
        sku.setTargetScheduleQty(176);
        sku.setRemainingScheduleQty(176);
        sku.setSurplusQty(15);
        sku.setEmbryoStock(20);
        sku.setMonthPlanQty(198);
        sku.setMonthlyHistoryShortageQty(15);
        Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap = new LinkedHashMap<LocalDate, SkuDailyPlanQuotaDTO>(4);
        quotaMap.put(LocalDate.of(2026, 6, 6), buildQuota(0));
        quotaMap.put(LocalDate.of(2026, 6, 7), buildQuota(0));
        quotaMap.put(LocalDate.of(2026, 6, 8), buildQuota(0));
        sku.setDailyPlanQuotaMap(quotaMap);
        return sku;
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private LhScheduleContext buildChangeoverBalanceContext() {
        LhScheduleContext context = new LhScheduleContext();
        context.setScheduleDate(dateTime(2026, 6, 1, 0, 0));
        context.setScheduleTargetDate(dateTime(2026, 6, 3, 0, 0));
        Map<String, String> paramMap = new HashMap<String, String>(4);
        paramMap.put(LhScheduleParamConstant.ENABLE_CHANGEOVER_BALANCE, "1");
        paramMap.put(LhScheduleParamConstant.DAILY_MOULD_CHANGE_LIMIT, "15");
        paramMap.put(LhScheduleParamConstant.MORNING_MOULD_CHANGE_LIMIT, "8");
        paramMap.put(LhScheduleParamConstant.AFTERNOON_MOULD_CHANGE_LIMIT, "7");
        context.setScheduleConfig(new LhScheduleConfig(paramMap));
        context.setScheduleWindowShifts(LhScheduleTimeUtil.buildDefaultScheduleShifts(context, context.getScheduleDate()));
        return context;
    }

    private SkuScheduleDTO buildChangeoverSku(String materialCode, String embryoCode) {
        SkuScheduleDTO sku = new SkuScheduleDTO();
        sku.setMaterialCode(materialCode);
        sku.setEmbryoCode(embryoCode);
        return sku;
    }

    private void putMouldRels(LhScheduleContext context, String materialCode, String... mouldCodes) {
        List<MdmSkuMouldRel> relationList = new ArrayList<MdmSkuMouldRel>(mouldCodes.length);
        for (String mouldCode : mouldCodes) {
            MdmSkuMouldRel relation = new MdmSkuMouldRel();
            relation.setMaterialCode(materialCode);
            relation.setMouldCode(mouldCode);
            relationList.add(relation);
        }
        context.getSkuMouldRelMap().put(materialCode, relationList);
    }

    private void appendAssignedResultWithMould(LhScheduleContext context, String machineCode,
                                               String materialCode, String mouldCode) {
        LhScheduleResult result = new LhScheduleResult();
        result.setMaterialCode(materialCode);
        result.setLhMachineCode(machineCode);
        result.setMouldCode(mouldCode);
        result.setDailyPlanQty(80);
        result.setSpecEndTime(dateTime(2026, 6, 3, 22, 0));
        context.getMachineAssignmentMap()
                .computeIfAbsent(machineCode, key -> new ArrayList<LhScheduleResult>(2))
                .add(result);
    }

    private Date dateTime(int year, int month, int day, int hour, int minute) {
        return Date.from(LocalDateTime.of(year, month, day, hour, minute)
                .atZone(ZoneId.systemDefault()).toInstant());
    }
}
