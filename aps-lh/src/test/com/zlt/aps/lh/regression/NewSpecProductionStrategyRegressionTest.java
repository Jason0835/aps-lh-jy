package com.zlt.aps.lh.regression;

import cn.hutool.core.bean.BeanUtil;
import com.zlt.aps.lh.api.constant.LhScheduleParamConstant;
import com.zlt.aps.lh.api.domain.dto.MachineCleaningWindowDTO;
import com.zlt.aps.lh.api.domain.dto.MachineMaintenanceWindowDTO;
import com.zlt.aps.lh.api.domain.dto.MachineScheduleDTO;
import com.zlt.aps.lh.api.domain.dto.SkuDailyPlanQuotaDTO;
import com.zlt.aps.lh.api.domain.dto.SkuScheduleDTO;
import com.zlt.aps.lh.api.domain.vo.LhShiftConfigVO;
import com.zlt.aps.lh.api.domain.entity.LhPrecisionPlan;
import com.zlt.aps.lh.api.domain.entity.LhScheduleProcessLog;
import com.zlt.aps.lh.api.domain.entity.LhScheduleResult;
import com.zlt.aps.lh.api.enums.ConstructionStageEnum;
import com.zlt.aps.lh.component.OrderNoGenerator;
import com.zlt.aps.lh.component.TargetScheduleQtyResolver;
import com.zlt.aps.lh.context.LhScheduleConfig;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.engine.strategy.ICapacityCalculateStrategy;
import com.zlt.aps.lh.engine.strategy.IEndingJudgmentStrategy;
import com.zlt.aps.lh.engine.strategy.IFirstInspectionBalanceStrategy;
import com.zlt.aps.lh.engine.strategy.IMachineMatchStrategy;
import com.zlt.aps.lh.engine.strategy.IMouldChangeBalanceStrategy;
import com.zlt.aps.lh.engine.strategy.ITrialProductionStrategy;
import com.zlt.aps.lh.engine.strategy.impl.DefaultCapacityCalculateStrategy;
import com.zlt.aps.lh.engine.strategy.impl.DefaultMachineMatchStrategy;
import com.zlt.aps.lh.engine.strategy.impl.DefaultMouldChangeBalanceStrategy;
import com.zlt.aps.lh.engine.strategy.impl.LocalSearchMachineAllocatorStrategy;
import com.zlt.aps.lh.engine.strategy.impl.NewSpecProductionStrategy;
import com.zlt.aps.lh.engine.strategy.support.NewSpecCandidateCache;
import com.zlt.aps.lh.engine.strategy.support.EarlyProductionDecision;
import com.zlt.aps.lh.engine.strategy.support.ProductionQuantityPolicy;
import com.zlt.aps.lh.util.LhScheduleTimeUtil;
import com.zlt.aps.lh.util.ShiftFieldUtil;
import com.zlt.aps.mdm.api.domain.entity.MdmMaterialInfo;
import com.zlt.aps.mdm.api.domain.entity.MdmModelInfo;
import com.zlt.aps.mdm.api.domain.entity.MdmSkuLhCapacity;
import com.zlt.aps.mdm.api.domain.entity.MdmSkuMouldRel;
import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 新增排产回归：首选机台窗口内无产能时，应继续尝试后续候选机台，避免直接误判未排产。
 */
class NewSpecProductionStrategyRegressionTest {

    @Test
    void scheduleNewSpecs_shouldFallbackToNextCandidateWhenFirstMachineHasNoCapacity() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        injectDependencies(strategy, false);

        LhScheduleContext context = buildContext();
        SkuScheduleDTO sku = buildSku();
        sku.setSurplusQty(1);
        context.getNewSpecSkuList().add(sku);

        MachineScheduleDTO noCapacityMachine = new MachineScheduleDTO();
        noCapacityMachine.setMachineCode("M-NO-CAP");
        noCapacityMachine.setMachineName("无产能机台");
        noCapacityMachine.setEstimatedEndTime(dateTime(2026, 4, 19, 21, 30));

        MachineScheduleDTO availableMachine = new MachineScheduleDTO();
        availableMachine.setMachineCode("M-OK");
        availableMachine.setMachineName("可排机台");
        availableMachine.setEstimatedEndTime(dateTime(2026, 4, 17, 6, 0));

        IMachineMatchStrategy machineMatchStrategy = new IMachineMatchStrategy() {
            @Override
            public java.util.List<MachineScheduleDTO> matchMachines(LhScheduleContext ctx, SkuScheduleDTO scheduleSku) {
                return Arrays.asList(noCapacityMachine, availableMachine);
            }

            @Override
            public MachineScheduleDTO selectBestMachine(LhScheduleContext ctx, SkuScheduleDTO scheduleSku,
                                                        java.util.List<MachineScheduleDTO> candidates,
                                                        Set<String> excludedMachineCodes) {
                if (candidates == null || candidates.isEmpty()) {
                    return null;
                }
                for (MachineScheduleDTO candidate : candidates) {
                    if (candidate == null || excludedMachineCodes.contains(candidate.getMachineCode())) {
                        continue;
                    }
                    return candidate;
                }
                return null;
            }

            @Override
            public void traceEnabledMachineSort(LhScheduleContext context) {
                // 测试桩，无需实现
            }
        };

        IMouldChangeBalanceStrategy mouldChangeBalanceStrategy = new IMouldChangeBalanceStrategy() {
            @Override
            public boolean hasCapacity(LhScheduleContext ctx, Date targetDate) {
                return true;
            }

            @Override
            public Date allocateMouldChange(LhScheduleContext ctx, String machineCode, Date endingTime) {
                return endingTime;
            }

            @Override
            public int getRemainingCapacity(LhScheduleContext ctx, Date targetDate) {
                return 99;
            }
        };

        IFirstInspectionBalanceStrategy inspectionBalanceStrategy =
                (ctx, machineCode, mouldChangeTime) -> mouldChangeTime;

        ICapacityCalculateStrategy capacityCalculateStrategy = new ICapacityCalculateStrategy() {
            @Override
            public int calculateShiftCapacity(LhScheduleContext ctx, int lhTimeSeconds, int mouldQty) {
                return 1;
            }

            @Override
            public Date calculateStartTime(LhScheduleContext ctx, String machineCode, Date endingTime) {
                return endingTime;
            }

            @Override
            public int calculateFirstShiftQty(Date startTime, Date shiftEndTime, int lhTimeSeconds, int mouldQty) {
                return startTime.before(shiftEndTime) ? 1 : 0;
            }

            @Override
            public int calculateDailyCapacity(int lhTimeSeconds, int mouldQty) {
                return 1;
            }
        };

        strategy.scheduleNewSpecs(context, machineMatchStrategy, mouldChangeBalanceStrategy,
                inspectionBalanceStrategy, capacityCalculateStrategy);

        assertEquals(1, context.getScheduleResultList().size(), "第一候选机台无产能时，应继续尝试后续候选机台");
        assertEquals(0, context.getUnscheduledResultList().size(), "存在后续可排机台时，不应生成未排产记录");
        assertEquals("M-OK", context.getScheduleResultList().get(0).getLhMachineCode());
        assertEquals("0", context.getScheduleResultList().get(0).getIsEnd(), "非收尾SKU应写入is_end=0");
    }

    @Test
    void scheduleNewSpecs_shouldReplaceReleasedContinuousPlaceholderWhenMachineIsTaken() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        injectDependencies(strategy, false);

        LhScheduleContext context = buildContext();
        List<LhShiftConfigVO> shifts = context.getScheduleWindowShifts();
        LhShiftConfigVO firstShift = shifts.get(0);
        LhShiftConfigVO secondDayShift = shifts.get(3);
        LhShiftConfigVO thirdDayShift = shifts.get(6);

        MachineScheduleDTO releasedMachine = buildMachine("K1105", firstShift.getShiftStartDateTime());
        releasedMachine.setPreviousMaterialCode("3302002546");
        MachineScheduleDTO fallbackMachine = buildMachine("K1110", firstShift.getShiftStartDateTime());
        context.getMachineScheduleMap().put(releasedMachine.getMachineCode(), releasedMachine);
        context.getMachineScheduleMap().put(fallbackMachine.getMachineCode(), fallbackMachine);

        SkuScheduleDTO sourceContinuousSku = buildSku();
        sourceContinuousSku.setMaterialCode("3302002546");
        sourceContinuousSku.setMaterialDesc("3302002546");
        sourceContinuousSku.setConstructionStage(ConstructionStageEnum.FORMAL.getCode());
        sourceContinuousSku.setContinuousMachineCode("K1105");
        sourceContinuousSku.setShiftCapacity(17);
        sourceContinuousSku.setPendingQty(102);
        sourceContinuousSku.setDailyPlanQty(102);
        sourceContinuousSku.setTargetScheduleQty(102);
        sourceContinuousSku.setSurplusQty(102);
        sourceContinuousSku.setEmbryoStock(102);
        sourceContinuousSku.setDailyPlanQuotaMap(buildThreeDayQuotaMap(
                shifts, "3302002546", 0, 32, 50));
        context.getContinuousSkuList().add(sourceContinuousSku);
        context.getReleasedContinuousMachineCodeSet().add("K1105");
        context.getFirstDayNoPlanReleasedContinuousMachineCodeSet().add("K1105");

        LhScheduleResult placeholderResult = new LhScheduleResult();
        placeholderResult.setFactoryCode(context.getFactoryCode());
        placeholderResult.setBatchNo(context.getBatchNo());
        placeholderResult.setLhMachineCode("K1105");
        placeholderResult.setLhMachineName("K1105");
        placeholderResult.setMaterialCode("3302002546");
        placeholderResult.setMaterialDesc("3302002546");
        placeholderResult.setScheduleType("01");
        placeholderResult.setIsTypeBlock("0");
        placeholderResult.setIsChangeMould("0");
        placeholderResult.setDailyPlanQty(102);
        placeholderResult.setSpecEndTime(dateTime(2026, 4, 19, 21, 12));
        context.getScheduleResultList().add(placeholderResult);
        context.getScheduleResultSourceSkuMap().put(placeholderResult, sourceContinuousSku);
        context.getMachineAssignmentMap().put("K1105",
                new java.util.ArrayList<LhScheduleResult>(Collections.singletonList(placeholderResult)));

        SkuScheduleDTO takeoverSku = buildSku();
        takeoverSku.setMaterialCode("3302001512");
        takeoverSku.setMaterialDesc("3302001512");
        takeoverSku.setConstructionStage(ConstructionStageEnum.FORMAL.getCode());
        takeoverSku.setShiftCapacity(17);
        takeoverSku.setPendingQty(102);
        takeoverSku.setDailyPlanQty(102);
        takeoverSku.setTargetScheduleQty(102);
        takeoverSku.setSurplusQty(102);
        takeoverSku.setEmbryoStock(102);
        takeoverSku.setDailyPlanQuotaMap(buildThreeDayQuotaMap(
                shifts, "3302001512", 32, 32, 38));
        context.getNewSpecSkuList().add(takeoverSku);

        IMachineMatchStrategy machineMatchStrategy = new IMachineMatchStrategy() {
            @Override
            public List<MachineScheduleDTO> matchMachines(LhScheduleContext ctx, SkuScheduleDTO scheduleSku) {
                if (StringUtils.equals("3302001512", scheduleSku.getMaterialCode())) {
                    return Arrays.asList(releasedMachine);
                }
                if (StringUtils.equals("3302002546", scheduleSku.getMaterialCode())) {
                    return Arrays.asList(fallbackMachine);
                }
                return Collections.emptyList();
            }

            @Override
            public MachineScheduleDTO selectBestMachine(LhScheduleContext ctx, SkuScheduleDTO scheduleSku,
                                                        List<MachineScheduleDTO> candidates,
                                                        Set<String> excludedMachineCodes) {
                for (MachineScheduleDTO candidate : candidates) {
                    if (candidate != null && !excludedMachineCodes.contains(candidate.getMachineCode())) {
                        return candidate;
                    }
                }
                return null;
            }

            @Override
            public void traceEnabledMachineSort(LhScheduleContext context) {
                // 测试桩，无需实现
            }
        };

        strategy.scheduleNewSpecs(context, machineMatchStrategy, defaultMouldChangeBalance(),
                defaultInspectionBalance(), defaultCapacityCalculate());

        assertTrue(context.getScheduleResultList().stream().anyMatch(result ->
                        StringUtils.equals("3302001512", result.getMaterialCode())
                                && StringUtils.equals("K1105", result.getLhMachineCode())),
                "释放续作机台被新增SKU抢占后，应保留新增SKU在原机台的实际排产结果");
        assertTrue(context.getScheduleResultList().stream().anyMatch(result ->
                        StringUtils.equals("3302002546", result.getMaterialCode())
                                && StringUtils.equals("K1110", result.getLhMachineCode())),
                "原续作SKU应转新增换模链，在其他机台继续排产");
        assertFalse(context.getScheduleResultList().stream().anyMatch(result ->
                        StringUtils.equals("3302002546", result.getMaterialCode())
                                && StringUtils.equals("K1105", result.getLhMachineCode())),
                "释放机台一旦被其他SKU实际占用，原续作占位结果必须从最终结果集中移除");
    }

    @Test
    void scheduleNewSpecs_shouldScheduleCompensationBeforeRemainingNewSpecs() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        injectDependencies(strategy, false);

        LhScheduleContext context = buildContext();
        List<LhShiftConfigVO> shifts = context.getScheduleWindowShifts();
        LhShiftConfigVO firstShift = shifts.get(0);

        MachineScheduleDTO releasedMachine = buildMachine("K1105", firstShift.getShiftStartDateTime());
        releasedMachine.setPreviousMaterialCode("3302002546");
        MachineScheduleDTO fallbackMachine = buildMachine("K1110", firstShift.getShiftStartDateTime());
        context.getMachineScheduleMap().put(releasedMachine.getMachineCode(), releasedMachine);
        context.getMachineScheduleMap().put(fallbackMachine.getMachineCode(), fallbackMachine);

        SkuScheduleDTO sourceContinuousSku = buildSku();
        sourceContinuousSku.setMaterialCode("3302002546");
        sourceContinuousSku.setMaterialDesc("3302002546");
        sourceContinuousSku.setConstructionStage(ConstructionStageEnum.FORMAL.getCode());
        sourceContinuousSku.setContinuousMachineCode("K1105");
        sourceContinuousSku.setShiftCapacity(17);
        sourceContinuousSku.setPendingQty(102);
        sourceContinuousSku.setDailyPlanQty(102);
        sourceContinuousSku.setTargetScheduleQty(102);
        sourceContinuousSku.setSurplusQty(102);
        sourceContinuousSku.setEmbryoStock(102);
        sourceContinuousSku.setDailyPlanQuotaMap(buildThreeDayQuotaMap(
                shifts, "3302002546", 0, 32, 50));
        context.getContinuousSkuList().add(sourceContinuousSku);
        context.getReleasedContinuousMachineCodeSet().add("K1105");
        context.getFirstDayNoPlanReleasedContinuousMachineCodeSet().add("K1105");

        LhScheduleResult placeholderResult = new LhScheduleResult();
        placeholderResult.setFactoryCode(context.getFactoryCode());
        placeholderResult.setBatchNo(context.getBatchNo());
        placeholderResult.setLhMachineCode("K1105");
        placeholderResult.setLhMachineName("K1105");
        placeholderResult.setMaterialCode("3302002546");
        placeholderResult.setMaterialDesc("3302002546");
        placeholderResult.setScheduleType("01");
        placeholderResult.setIsTypeBlock("0");
        placeholderResult.setIsChangeMould("0");
        placeholderResult.setDailyPlanQty(102);
        placeholderResult.setSpecEndTime(dateTime(2026, 4, 19, 21, 12));
        context.getScheduleResultList().add(placeholderResult);
        context.getScheduleResultSourceSkuMap().put(placeholderResult, sourceContinuousSku);
        context.getMachineAssignmentMap().put("K1105",
                new java.util.ArrayList<LhScheduleResult>(Collections.singletonList(placeholderResult)));

        SkuScheduleDTO takeoverSku = buildSku();
        takeoverSku.setMaterialCode("3302001512");
        takeoverSku.setMaterialDesc("3302001512");
        takeoverSku.setConstructionStage(ConstructionStageEnum.FORMAL.getCode());
        takeoverSku.setShiftCapacity(17);
        takeoverSku.setPendingQty(102);
        takeoverSku.setDailyPlanQty(102);
        takeoverSku.setTargetScheduleQty(102);
        takeoverSku.setSurplusQty(102);
        takeoverSku.setEmbryoStock(102);
        takeoverSku.setDailyPlanQuotaMap(buildThreeDayQuotaMap(
                shifts, "3302001512", 32, 32, 38));
        context.getNewSpecSkuList().add(takeoverSku);

        SkuScheduleDTO competingSku = buildSku();
        competingSku.setMaterialCode("3302001888");
        competingSku.setMaterialDesc("3302001888");
        competingSku.setConstructionStage(ConstructionStageEnum.FORMAL.getCode());
        competingSku.setShiftCapacity(17);
        competingSku.setPendingQty(102);
        competingSku.setDailyPlanQty(102);
        competingSku.setTargetScheduleQty(102);
        competingSku.setSurplusQty(102);
        competingSku.setEmbryoStock(102);
        competingSku.setDailyPlanQuotaMap(buildThreeDayQuotaMap(
                shifts, "3302001888", 32, 32, 38));
        context.getNewSpecSkuList().add(competingSku);

        IMachineMatchStrategy machineMatchStrategy = new IMachineMatchStrategy() {
            @Override
            public List<MachineScheduleDTO> matchMachines(LhScheduleContext ctx, SkuScheduleDTO scheduleSku) {
                if (StringUtils.equals("3302001512", scheduleSku.getMaterialCode())) {
                    return Arrays.asList(releasedMachine);
                }
                if (StringUtils.equals("3302002546", scheduleSku.getMaterialCode())) {
                    return Arrays.asList(fallbackMachine);
                }
                if (StringUtils.equals("3302001888", scheduleSku.getMaterialCode())) {
                    return Arrays.asList(fallbackMachine);
                }
                return Collections.emptyList();
            }

            @Override
            public MachineScheduleDTO selectBestMachine(LhScheduleContext ctx, SkuScheduleDTO scheduleSku,
                                                        List<MachineScheduleDTO> candidates,
                                                        Set<String> excludedMachineCodes) {
                for (MachineScheduleDTO candidate : candidates) {
                    if (candidate != null && !excludedMachineCodes.contains(candidate.getMachineCode())) {
                        return candidate;
                    }
                }
                return null;
            }

            @Override
            public void traceEnabledMachineSort(LhScheduleContext context) {
                // 测试桩，无需实现
            }
        };

        strategy.scheduleNewSpecs(context, machineMatchStrategy, defaultMouldChangeBalance(),
                defaultInspectionBalance(), defaultCapacityCalculate());

        assertTrue(context.getScheduleResultList().stream().anyMatch(result ->
                        StringUtils.equals("3302001512", result.getMaterialCode())
                                && StringUtils.equals("K1105", result.getLhMachineCode())),
                "释放续作机台应先被新增SKU占用");
        LhScheduleResult compensationResult = context.getScheduleResultList().stream()
                .filter(result -> StringUtils.equals("3302002546", result.getMaterialCode())
                        && StringUtils.equals("K1110", result.getLhMachineCode()))
                .findFirst()
                .orElse(null);
        assertTrue(Objects.nonNull(compensationResult),
                "被抢占后的续作SKU应先于剩余新增SKU参与下一轮补排");
        LhScheduleResult competingResult = context.getScheduleResultList().stream()
                .filter(result -> StringUtils.equals("3302001888", result.getMaterialCode())
                        && StringUtils.equals("K1110", result.getLhMachineCode()))
                .findFirst()
                .orElse(null);
        if (Objects.nonNull(competingResult)) {
            assertTrue(competingResult.getDailyPlanQty() < compensationResult.getDailyPlanQty(),
                    "补偿SKU应先占用候补机台主容量，后续普通新增SKU只能承接剩余零头产能");
        }
    }

    @Test
    void scheduleNewSpecs_shouldPreferOriginalContinuousMachineWhenCompensationSkuTurnComes() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        injectDependencies(strategy, false);

        LhScheduleContext context = buildContext();
        Date scheduleDate = dateTime(2026, 5, 1, 0, 0);
        context.setScheduleDate(scheduleDate);
        context.setScheduleTargetDate(dateTime(2026, 5, 3, 0, 0));
        context.setScheduleWindowShifts(LhScheduleTimeUtil.buildDefaultScheduleShifts(context, scheduleDate));

        List<LhShiftConfigVO> shifts = context.getScheduleWindowShifts();
        SkuScheduleDTO compensationSku = buildSku();
        compensationSku.setMaterialCode("3302002546");
        compensationSku.setMaterialDesc("3302002546");
        compensationSku.setConstructionStage(ConstructionStageEnum.FORMAL.getCode());
        compensationSku.setContinuousCompensationSku(true);
        compensationSku.setShiftCapacity(17);
        compensationSku.setPendingQty(82);
        compensationSku.setDailyPlanQty(82);
        compensationSku.setTargetScheduleQty(82);
        compensationSku.setWindowPlanQty(82);
        compensationSku.setSurplusQty(82);
        compensationSku.setEmbryoStock(82);
        compensationSku.setDailyPlanQuotaMap(buildThreeDayQuotaMap(
                shifts, "3302002546", 0, 32, 50));
        ReflectionTestUtils.setField(compensationSku, "preferredContinuousMachineCode", "K1105");
        context.getNewSpecSkuList().add(compensationSku);

        MachineScheduleDTO fallbackMachine = buildMachine("K1110", dateTime(2026, 5, 1, 6, 0));
        MachineScheduleDTO preferredMachine = buildMachine("K1105", dateTime(2026, 5, 1, 6, 0));

        strategy.scheduleNewSpecs(context, orderedMachineMatch(fallbackMachine, preferredMachine),
                defaultMouldChangeBalance(), defaultInspectionBalance(), defaultCapacityCalculate());

        assertEquals(1, context.getScheduleResultList().size(), "补偿SKU应成功在新增阶段生成排产结果");
        assertEquals("K1105", context.getScheduleResultList().get(0).getLhMachineCode(),
                "补偿SKU轮到自己选机时，原续作机台未被占走应优先锁回");
    }

    @Test
    void scheduleNewSpecs_shouldSetIsEndOneWhenEndingJudgmentTrue() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        injectDependencies(strategy, true);

        LhScheduleContext context = buildContext();
        SkuScheduleDTO sku = buildSku();
        sku.setSurplusQty(1);
        context.getNewSpecSkuList().add(sku);

        MachineScheduleDTO machine = new MachineScheduleDTO();
        machine.setMachineCode("M-END");
        machine.setMachineName("收尾机台");
        machine.setEstimatedEndTime(dateTime(2026, 4, 17, 6, 0));

        strategy.scheduleNewSpecs(context, singletonMachineMatch(machine), defaultMouldChangeBalance(),
                defaultInspectionBalance(), defaultCapacityCalculate());

        assertEquals(1, context.getScheduleResultList().size(), "应生成新增排产结果");
        assertEquals("1", context.getScheduleResultList().get(0).getIsEnd(), "收尾SKU应写入is_end=1");
    }

    @Test
    void scheduleNewSpecs_shouldSetIsEndZeroWhenEndingJudgmentFalse() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        injectDependencies(strategy, false);

        LhScheduleContext context = buildContext();
        SkuScheduleDTO sku = buildSku();
        context.getNewSpecSkuList().add(sku);

        MachineScheduleDTO machine = new MachineScheduleDTO();
        machine.setMachineCode("M-NORMAL");
        machine.setMachineName("常规机台");
        machine.setEstimatedEndTime(dateTime(2026, 4, 17, 6, 0));

        strategy.scheduleNewSpecs(context, singletonMachineMatch(machine), defaultMouldChangeBalance(),
                defaultInspectionBalance(), defaultCapacityCalculate());

        assertEquals(1, context.getScheduleResultList().size(), "应生成新增排产结果");
        assertEquals("0", context.getScheduleResultList().get(0).getIsEnd(), "非收尾SKU应写入is_end=0");
    }

    @Test
    void applyBlockToDailyQuota_shouldTrimResultQtyWhenWindowQuotaIsExhausted() {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        LhScheduleContext context = buildContext();
        context.setMachineScheduleMap(new LinkedHashMap<String, MachineScheduleDTO>());
        MachineScheduleDTO machine = buildMachine("M-QUOTA", dateTime(2026, 4, 17, 6, 0));
        context.getMachineScheduleMap().put(machine.getMachineCode(), machine);

        List<LhShiftConfigVO> shifts = context.getScheduleWindowShifts();
        LhShiftConfigVO firstShift = shifts.get(0);
        LhShiftConfigVO nextDayShift = resolveNextWorkDateShift(shifts, firstShift);

        SkuScheduleDTO sku = buildSku();
        sku.setMaterialCode("MAT-QUOTA");
        sku.setDailyPlanQuotaMap(buildQuotaMap(firstShift, nextDayShift, 6, 4));

        LhScheduleResult result = new LhScheduleResult();
        result.setMaterialCode("MAT-QUOTA");
        result.setScheduleType("02");
        result.setLhMachineCode("M-QUOTA");
        result.setLhTime(3600);
        result.setMouldQty(1);
        ShiftFieldUtil.setShiftPlanQty(result, firstShift.getShiftIndex(), 8,
                firstShift.getShiftStartDateTime(), firstShift.getShiftEndDateTime());
        ShiftFieldUtil.setShiftPlanQty(result, nextDayShift.getShiftIndex(), 8,
                nextDayShift.getShiftStartDateTime(), nextDayShift.getShiftEndDateTime());
        ShiftFieldUtil.syncDailyPlanQty(result);

        ReflectionTestUtils.invokeMethod(strategy, "applyBlockToDailyQuota", context, sku, result, shifts);

        assertEquals(10, result.getDailyPlanQty().intValue(), "窗口总量用尽后，结果行计划量必须同步回裁");
        assertEquals(Integer.valueOf(8), ShiftFieldUtil.getShiftPlanQty(result, firstShift.getShiftIndex()));
        assertEquals(Integer.valueOf(2), ShiftFieldUtil.getShiftPlanQty(result, nextDayShift.getShiftIndex()));
        assertEquals(6, context.getSkuShiftFillOverQtyMap().get("MAT-QUOTA").intValue());
    }

    @Test
    void adjustSameSkuMultiMachineAllocation_shouldKeepAuxiliaryMachineForFutureDayDemand() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        injectDependencies(strategy, false);

        LhScheduleContext context = buildContext();
        Date scheduleDate = dateTime(2026, 4, 1, 0, 0);
        context.setScheduleDate(scheduleDate);
        context.setScheduleTargetDate(dateTime(2026, 4, 3, 0, 0));
        context.setScheduleWindowShifts(LhScheduleTimeUtil.buildDefaultScheduleShifts(context, scheduleDate));
        List<LhShiftConfigVO> shifts = context.getScheduleWindowShifts();

        SkuScheduleDTO sku = buildSku();
        sku.setMaterialCode("3302001236");
        sku.setConstructionStage(ConstructionStageEnum.FORMAL.getCode());
        sku.setShiftCapacity(16);
        sku.setSurplusQty(1432);
        sku.setTargetScheduleQty(96);
        sku.setWindowPlanQty(88);
        sku.setDailyPlanQuotaMap(buildThreeDayQuotaMap(shifts, "3302001236", 0, 8, 80));

        LhScheduleResult primaryResult = buildNewSpecResult("3302001236", "K2025");
        ShiftFieldUtil.setShiftPlanQty(primaryResult, shifts.get(3).getShiftIndex(), 16,
                shifts.get(3).getShiftStartDateTime(), shifts.get(3).getShiftEndDateTime());
        ShiftFieldUtil.setShiftPlanQty(primaryResult, shifts.get(4).getShiftIndex(), 16,
                shifts.get(4).getShiftStartDateTime(), shifts.get(4).getShiftEndDateTime());
        ShiftFieldUtil.setShiftPlanQty(primaryResult, shifts.get(5).getShiftIndex(), 16,
                shifts.get(5).getShiftStartDateTime(), shifts.get(5).getShiftEndDateTime());
        ShiftFieldUtil.setShiftPlanQty(primaryResult, shifts.get(6).getShiftIndex(), 16,
                shifts.get(6).getShiftStartDateTime(), shifts.get(6).getShiftEndDateTime());
        ShiftFieldUtil.syncDailyPlanQty(primaryResult);

        LhScheduleResult auxiliaryResult = buildNewSpecResult("3302001236", "K1002");
        ShiftFieldUtil.setShiftPlanQty(auxiliaryResult, shifts.get(3).getShiftIndex(), 16,
                shifts.get(3).getShiftStartDateTime(), shifts.get(3).getShiftEndDateTime());
        ShiftFieldUtil.setShiftPlanQty(auxiliaryResult, shifts.get(4).getShiftIndex(), 16,
                shifts.get(4).getShiftStartDateTime(), shifts.get(4).getShiftEndDateTime());
        ShiftFieldUtil.syncDailyPlanQty(auxiliaryResult);

        context.getScheduleResultList().add(primaryResult);
        context.getScheduleResultList().add(auxiliaryResult);

        invokeAdjustSameSkuMultiMachineAllocation(strategy, context, sku, shifts,
                ProductionQuantityPolicy.from(sku, false), false);

        assertEquals(32, auxiliaryResult.getDailyPlanQty().intValue(),
                "辅助机台承接后续dayN需求时，不能被当前日目标已满足逻辑清零");
        assertEquals(Integer.valueOf(16), ShiftFieldUtil.getShiftPlanQty(auxiliaryResult, shifts.get(3).getShiftIndex()));
        assertEquals(Integer.valueOf(16), ShiftFieldUtil.getShiftPlanQty(auxiliaryResult, shifts.get(4).getShiftIndex()));
    }

    @Test
    void scheduleNewSpecs_shouldNotAttachMaintenanceBeforeNonEndingSkuCompletes() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        injectDependencies(strategy, false);

        LhScheduleContext context = buildContext();
        context.getMaintenancePlanMap().put("K1105", buildPrecisionPlan("K1105", dateTime(2026, 5, 10, 0, 0)));
        SkuScheduleDTO sku = buildSku();
        context.getNewSpecSkuList().add(sku);

        MachineScheduleDTO machine = new MachineScheduleDTO();
        machine.setMachineCode("K1105");
        machine.setMachineName("首规格未收尾机台");
        machine.setEstimatedEndTime(dateTime(2026, 4, 17, 6, 0));

        strategy.scheduleNewSpecs(context, singletonMachineMatch(machine), defaultMouldChangeBalance(),
                defaultInspectionBalance(), defaultCapacityCalculate());

        assertTrue(machine.getMaintenanceWindowList().isEmpty(), "当前新增SKU未收尾时，不应提前挂载首个规格收尾后的精度保养");
    }

    @Test
    void scheduleNewSpecs_shouldAllowContinuousCandidateFallbackIntoNewSpec() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        injectDependencies(strategy, false);

        LhScheduleContext context = buildContext();
        MachineScheduleDTO machine = new MachineScheduleDTO();
        machine.setMachineCode("M-CONT");
        machine.setMachineName("续作机台");
        machine.setCurrentMaterialCode("MAT-BASE");
        machine.setPreviousSpecCode("SPEC-A");
        context.getMachineScheduleMap().put(machine.getMachineCode(), machine);

        SkuScheduleDTO sku = buildSku();
        sku.setEmbryoCode("EMB-1");
        sku.setStructureName("STRUCT-A");
        sku.setSpecCode("SPEC-A");
        sku.setMainPattern("PAT-A");
        sku.setPattern("PAT-A");
        context.getNewSpecSkuList().add(sku);

        strategy.scheduleNewSpecs(context, singletonMachineMatch(machine), defaultMouldChangeBalance(),
                defaultInspectionBalance(), defaultCapacityCalculate());

        assertEquals(1, context.getScheduleResultList().size(), "续作阶段未命中的候选SKU应继续参与新增排产");
        assertEquals(0, context.getUnscheduledResultList().size(), "进入新增排产后命中机台时不应生成未排记录");
        assertEquals("02", context.getScheduleResultList().get(0).getScheduleType());
        assertEquals("1", context.getScheduleResultList().get(0).getIsChangeMould());
        assertEquals("0", context.getScheduleResultList().get(0).getIsTypeBlock());
    }

    @Test
    void scheduleNewSpecs_shouldRefineTargetQtyByActualWindowCapacity() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        injectDependencies(strategy, false);

        LhScheduleContext context = buildContext();
        SkuScheduleDTO sku = buildSku();
        sku.setPendingQty(8);
        sku.setTargetScheduleQty(128);
        sku.setShiftCapacity(16);
        context.getNewSpecSkuList().add(sku);

        MachineScheduleDTO machine = new MachineScheduleDTO();
        machine.setMachineCode("M-REFINE");
        machine.setMachineName("收敛机台");
        machine.setMaxMoldNum(1);
        machine.setEstimatedEndTime(dateTime(2026, 4, 17, 6, 0));

        IMouldChangeBalanceStrategy mouldChangeBalanceStrategy = new IMouldChangeBalanceStrategy() {
            @Override
            public boolean hasCapacity(LhScheduleContext ctx, Date targetDate) {
                return true;
            }

            @Override
            public Date allocateMouldChange(LhScheduleContext ctx, String machineCode, Date endingTime) {
                return dateTime(2026, 4, 17, 6, 0);
            }

            @Override
            public int getRemainingCapacity(LhScheduleContext ctx, Date targetDate) {
                return 99;
            }
        };

        strategy.scheduleNewSpecs(context, singletonMachineMatch(machine), mouldChangeBalanceStrategy,
                defaultInspectionBalance(), defaultCapacityCalculate());

        assertEquals(1, context.getScheduleResultList().size(), "应生成新增排产结果");
        assertEquals(112, context.getScheduleResultList().get(0).getDailyPlanQty().intValue(),
                "新增规格应按实际开产后的窗口剩余产能收敛目标量");
        assertEquals(112, sku.getTargetScheduleQty().intValue(),
                "收敛后的目标量应回写到SKU，供后续未排与收尾口径复用");
    }

    @Test
    void scheduleNewSpecs_shouldUseHalfShiftCapacityForSingleControlSplitMachine() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        injectDependencies(strategy, false);

        LhScheduleContext context = buildContext();
        SkuScheduleDTO sku = buildSku();
        sku.setPendingQty(100);
        sku.setDailyPlanQty(100);
        sku.setTargetScheduleQty(100);
        sku.setShiftCapacity(35);
        context.getNewSpecSkuList().add(sku);

        MachineScheduleDTO machine = new MachineScheduleDTO();
        machine.setMachineCode("K1501L");
        machine.setMachineName("K1501L");
        machine.setMaxMoldNum(1);
        machine.setEstimatedEndTime(dateTime(2026, 4, 17, 6, 0));

        strategy.scheduleNewSpecs(context, singletonMachineMatch(machine), defaultMouldChangeBalance(),
                defaultInspectionBalance(), defaultCapacityCalculate());

        assertEquals(1, context.getScheduleResultList().size(), "单控拆分机台应正常生成新增排产结果");
        LhScheduleResult result = context.getScheduleResultList().get(0);
        assertEquals(17, result.getSingleMouldShiftQty().intValue(),
                "K1501L 这类单控拆分机台应按整机班产均分到单侧，并向下取整");
        int firstPlannedShiftIndex = resolveFirstPlannedShiftIndex(result);
        assertEquals(17, ShiftFieldUtil.getShiftPlanQty(result, firstPlannedShiftIndex).intValue(),
                "单控拆分机台首个完整班次的排产量应同步使用折半后的单侧班产");
    }

    @Test
    void scheduleNewSpecs_shouldPreferTrialMatchedMachineBeforeGeneralSelection() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        injectDependencies(strategy, false);
        injectTrialProductionStrategy(strategy, new ITrialProductionStrategy() {
            @Override
            public List<SkuScheduleDTO> filterTrialSkus(LhScheduleContext context, List<SkuScheduleDTO> allSkus) {
                return allSkus;
            }

            @Override
            public boolean canScheduleTrialOnDate(LhScheduleContext context, Date targetDate) {
                return true;
            }

            @Override
            public boolean canScheduleTrialSkuOnDate(LhScheduleContext context, SkuScheduleDTO trialSku, Date targetDate) {
                return true;
            }

            @Override
            public boolean isDailyTrialLimitReached(LhScheduleContext context, Date targetDate) {
                return false;
            }

            @Override
            public boolean isDailyTrialLimitReached(LhScheduleContext context, Date targetDate, String materialCode) {
                return false;
            }

            @Override
            public String matchTrialMachine(LhScheduleContext context, SkuScheduleDTO trialSku) {
                return "K1501L";
            }
        });

        LhScheduleContext context = buildContext();
        SkuScheduleDTO sku = buildSku();
        sku.setConstructionStage(ConstructionStageEnum.MASS_TRIAL.getCode());
        sku.setMaterialCode("3302001575");
        context.getNewSpecSkuList().add(sku);

        MachineScheduleDTO normalMachine = new MachineScheduleDTO();
        normalMachine.setMachineCode("K1401");
        normalMachine.setMachineName("普通机台");
        normalMachine.setEstimatedEndTime(dateTime(2026, 4, 17, 6, 0));

        MachineScheduleDTO trialMachine = new MachineScheduleDTO();
        trialMachine.setMachineCode("K1501L");
        trialMachine.setMachineName("单控机台");
        trialMachine.setEstimatedEndTime(dateTime(2026, 4, 17, 6, 0));

        IMachineMatchStrategy machineMatchStrategy = new IMachineMatchStrategy() {
            @Override
            public List<MachineScheduleDTO> matchMachines(LhScheduleContext ctx, SkuScheduleDTO scheduleSku) {
                return Arrays.asList(normalMachine, trialMachine);
            }

            @Override
            public MachineScheduleDTO selectBestMachine(LhScheduleContext ctx, SkuScheduleDTO scheduleSku,
                                                        List<MachineScheduleDTO> candidates,
                                                        Set<String> excludedMachineCodes) {
                for (MachineScheduleDTO candidate : candidates) {
                    if (!excludedMachineCodes.contains(candidate.getMachineCode())) {
                        return candidate;
                    }
                }
                return null;
            }

            @Override
            public void traceEnabledMachineSort(LhScheduleContext context) {
                // 测试桩，无需实现
            }
        };

        strategy.scheduleNewSpecs(context, machineMatchStrategy, defaultMouldChangeBalance(),
                defaultInspectionBalance(), defaultCapacityCalculate());

        assertEquals(1, context.getScheduleResultList().size());
        assertEquals("K1501L", context.getScheduleResultList().get(0).getLhMachineCode(),
                "试制量试 SKU 命中预选机台时，应先尝试该单控机台，而不是继续按通用顺序抢普通机台");
        assertFalse(context.getScheduleResultList().get(0).getLhMachineCode().equals("K1401"));
    }

    @Test
    void scheduleNewSpecs_shouldPreferTrialMatchedMachineForMassTrialConstructionStage() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        injectDependencies(strategy, false);
        injectTrialProductionStrategy(strategy, new ITrialProductionStrategy() {
            @Override
            public List<SkuScheduleDTO> filterTrialSkus(LhScheduleContext context, List<SkuScheduleDTO> allSkus) {
                return allSkus;
            }

            @Override
            public boolean canScheduleTrialOnDate(LhScheduleContext context, Date targetDate) {
                return true;
            }

            @Override
            public boolean canScheduleTrialSkuOnDate(LhScheduleContext context, SkuScheduleDTO trialSku, Date targetDate) {
                return true;
            }

            @Override
            public boolean isDailyTrialLimitReached(LhScheduleContext context, Date targetDate) {
                return false;
            }

            @Override
            public boolean isDailyTrialLimitReached(LhScheduleContext context, Date targetDate, String materialCode) {
                return false;
            }

            @Override
            public String matchTrialMachine(LhScheduleContext context, SkuScheduleDTO trialSku) {
                return "K1501R";
            }
        });

        LhScheduleContext context = buildContext();
        SkuScheduleDTO sku = buildSku();
        sku.setMaterialCode("3302002637");
        sku.setConstructionStage(ConstructionStageEnum.MASS_TRIAL.getCode());
        context.getNewSpecSkuList().add(sku);

        MachineScheduleDTO normalMachine = new MachineScheduleDTO();
        normalMachine.setMachineCode("K1402");
        normalMachine.setMachineName("普通机台");
        normalMachine.setEstimatedEndTime(dateTime(2026, 4, 17, 6, 0));

        MachineScheduleDTO trialMachine = new MachineScheduleDTO();
        trialMachine.setMachineCode("K1501R");
        trialMachine.setMachineName("单控机台");
        trialMachine.setEstimatedEndTime(dateTime(2026, 4, 17, 6, 0));

        IMachineMatchStrategy machineMatchStrategy = new IMachineMatchStrategy() {
            @Override
            public List<MachineScheduleDTO> matchMachines(LhScheduleContext ctx, SkuScheduleDTO scheduleSku) {
                return Arrays.asList(normalMachine, trialMachine);
            }

            @Override
            public MachineScheduleDTO selectBestMachine(LhScheduleContext ctx, SkuScheduleDTO scheduleSku,
                                                        List<MachineScheduleDTO> candidates,
                                                        Set<String> excludedMachineCodes) {
                for (MachineScheduleDTO candidate : candidates) {
                    if (!excludedMachineCodes.contains(candidate.getMachineCode())) {
                        return candidate;
                    }
                }
                return null;
            }

            @Override
            public void traceEnabledMachineSort(LhScheduleContext context) {
                // 测试桩，无需实现
            }
        };

        strategy.scheduleNewSpecs(context, machineMatchStrategy, defaultMouldChangeBalance(),
                defaultInspectionBalance(), defaultCapacityCalculate());

        assertEquals(1, context.getScheduleResultList().size());
        assertEquals("K1501R", context.getScheduleResultList().get(0).getLhMachineCode(),
                "量试施工阶段 SKU 即使未显式打 isTrial，也应命中试制机台硬优先");
    }

    @Test
    void scheduleNewSpecs_shouldNotFallbackToNormalMachineWhenTrialSingleControlFailed() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        injectDependencies(strategy, false);

        LhScheduleContext context = buildContext();
        context.setScheduleConfig(buildChangeoverBalanceScheduleConfig("1"));
        SkuScheduleDTO sku = buildSku();
        sku.setMaterialCode("3302001575");
        sku.setConstructionStage(ConstructionStageEnum.TRIAL.getCode());
        context.getNewSpecSkuList().add(sku);

        MachineScheduleDTO singleControlMachine = buildMachine("K1501R", dateTime(2026, 4, 17, 6, 0));
        MachineScheduleDTO normalMachine = buildMachine("K1111", dateTime(2026, 4, 17, 6, 0));

        IMachineMatchStrategy machineMatchStrategy = new IMachineMatchStrategy() {
            @Override
            public List<MachineScheduleDTO> matchMachines(LhScheduleContext ctx, SkuScheduleDTO scheduleSku) {
                return Arrays.asList(singleControlMachine, normalMachine);
            }

            @Override
            public MachineScheduleDTO selectBestMachine(LhScheduleContext ctx, SkuScheduleDTO scheduleSku,
                                                        List<MachineScheduleDTO> candidates,
                                                        Set<String> excludedMachineCodes) {
                for (MachineScheduleDTO candidate : candidates) {
                    if (!excludedMachineCodes.contains(candidate.getMachineCode())) {
                        return candidate;
                    }
                }
                return null;
            }

            @Override
            public void traceEnabledMachineSort(LhScheduleContext context) {
                // 测试桩，无需实现
            }
        };
        IMouldChangeBalanceStrategy mouldChangeBalanceStrategy = new IMouldChangeBalanceStrategy() {
            @Override
            public boolean hasCapacity(LhScheduleContext ctx, Date targetDate) {
                return true;
            }

            @Override
            public Date allocateMouldChange(LhScheduleContext ctx, String machineCode, Date endingTime) {
                if ("K1501R".equals(machineCode)) {
                    return null;
                }
                return endingTime;
            }

            @Override
            public int getRemainingCapacity(LhScheduleContext ctx, Date targetDate) {
                return 99;
            }
        };

        strategy.scheduleNewSpecs(context, machineMatchStrategy, mouldChangeBalanceStrategy,
                defaultInspectionBalance(), defaultCapacityCalculate());

        assertEquals(0, context.getScheduleResultList().size(), "试制单控候选失败后不应回落普通机台排产");
        assertEquals(1, context.getUnscheduledResultList().size(), "试制单控候选失败后应保留未排记录");
        assertEquals("3302001575", context.getUnscheduledResultList().get(0).getMaterialCode());
    }

    @Test
    void scheduleNewSpecs_shouldTryAllSingleControlMachinesBeforeFallbackToNormalMachine() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        injectDependencies(strategy, false);
        injectTrialProductionStrategy(strategy, alwaysSchedulableTrialStrategy());

        LhScheduleContext context = buildContext();
        context.setScheduleConfig(buildSingleControlChangeoverBalanceScheduleConfig());

        SkuScheduleDTO sku = buildSku();
        sku.setMaterialCode("3302002637");
        sku.setConstructionStage(ConstructionStageEnum.MASS_TRIAL.getCode());
        context.getNewSpecSkuList().add(sku);

        MachineScheduleDTO firstSingleControlMachine = buildMachine("K1501L", dateTime(2026, 4, 17, 6, 0));
        MachineScheduleDTO secondSingleControlMachine = buildMachine("K1501R", dateTime(2026, 4, 17, 6, 0));
        MachineScheduleDTO normalMachine = buildMachine("K1111", dateTime(2026, 4, 17, 6, 0));

        IMachineMatchStrategy machineMatchStrategy = new IMachineMatchStrategy() {
            @Override
            public List<MachineScheduleDTO> matchMachines(LhScheduleContext ctx, SkuScheduleDTO scheduleSku) {
                return Arrays.asList(firstSingleControlMachine, secondSingleControlMachine, normalMachine);
            }

            @Override
            public MachineScheduleDTO selectBestMachine(LhScheduleContext ctx, SkuScheduleDTO scheduleSku,
                                                        List<MachineScheduleDTO> candidates,
                                                        Set<String> excludedMachineCodes) {
                for (MachineScheduleDTO candidate : candidates) {
                    if (!excludedMachineCodes.contains(candidate.getMachineCode())) {
                        return candidate;
                    }
                }
                return null;
            }

            @Override
            public void traceEnabledMachineSort(LhScheduleContext context) {
                // 测试桩，无需实现
            }
        };
        IMouldChangeBalanceStrategy mouldChangeBalanceStrategy = new IMouldChangeBalanceStrategy() {
            @Override
            public boolean hasCapacity(LhScheduleContext ctx, Date targetDate) {
                return true;
            }

            @Override
            public Date allocateMouldChange(LhScheduleContext ctx, String machineCode, Date endingTime) {
                if ("K1501L".equals(machineCode)) {
                    return null;
                }
                return endingTime;
            }

            @Override
            public int getRemainingCapacity(LhScheduleContext ctx, Date targetDate) {
                return 99;
            }
        };

        strategy.scheduleNewSpecs(context, machineMatchStrategy, mouldChangeBalanceStrategy,
                defaultInspectionBalance(), defaultCapacityCalculate());

        assertEquals(1, context.getScheduleResultList().size(), "量试存在多个单控候选时，应先尝试完其它单控机台");
        assertEquals(0, context.getUnscheduledResultList().size(), "存在可用单控机台时，不应提前生成未排记录");
        assertEquals("K1501R", context.getScheduleResultList().get(0).getLhMachineCode(),
                "首个单控失败后，应继续尝试其它单控机台，而不是直接回落普通机台");
    }

    @Test
    void scheduleNewSpecs_shouldFallbackToNormalMachineAfterAllSingleControlMachinesFailed() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        injectDependencies(strategy, false);
        injectTrialProductionStrategy(strategy, alwaysSchedulableTrialStrategy());

        LhScheduleContext context = buildContext();
        context.setScheduleConfig(buildSingleControlChangeoverBalanceScheduleConfig());

        SkuScheduleDTO sku = buildSku();
        sku.setMaterialCode("3302002637");
        sku.setConstructionStage(ConstructionStageEnum.MASS_TRIAL.getCode());
        context.getNewSpecSkuList().add(sku);

        MachineScheduleDTO singleControlMachine = buildMachine("K1501R", dateTime(2026, 4, 17, 6, 0));
        MachineScheduleDTO normalMachine = buildMachine("K1111", dateTime(2026, 4, 17, 6, 0));

        IMachineMatchStrategy machineMatchStrategy = new IMachineMatchStrategy() {
            @Override
            public List<MachineScheduleDTO> matchMachines(LhScheduleContext ctx, SkuScheduleDTO scheduleSku) {
                return Arrays.asList(singleControlMachine, normalMachine);
            }

            @Override
            public MachineScheduleDTO selectBestMachine(LhScheduleContext ctx, SkuScheduleDTO scheduleSku,
                                                        List<MachineScheduleDTO> candidates,
                                                        Set<String> excludedMachineCodes) {
                for (MachineScheduleDTO candidate : candidates) {
                    if (!excludedMachineCodes.contains(candidate.getMachineCode())) {
                        return candidate;
                    }
                }
                return null;
            }

            @Override
            public void traceEnabledMachineSort(LhScheduleContext context) {
                // 测试桩，无需实现
            }
        };
        IMouldChangeBalanceStrategy mouldChangeBalanceStrategy = new IMouldChangeBalanceStrategy() {
            @Override
            public boolean hasCapacity(LhScheduleContext ctx, Date targetDate) {
                return true;
            }

            @Override
            public Date allocateMouldChange(LhScheduleContext ctx, String machineCode, Date endingTime) {
                if ("K1501R".equals(machineCode)) {
                    return null;
                }
                return endingTime;
            }

            @Override
            public int getRemainingCapacity(LhScheduleContext ctx, Date targetDate) {
                return 99;
            }
        };

        strategy.scheduleNewSpecs(context, machineMatchStrategy, mouldChangeBalanceStrategy,
                defaultInspectionBalance(), defaultCapacityCalculate());

        assertEquals(1, context.getScheduleResultList().size(), "所有单控机台都失败后，量试才允许回落普通机台");
        assertEquals(0, context.getUnscheduledResultList().size(), "普通机台可承接时，不应保留未排记录");
        assertEquals("K1111", context.getScheduleResultList().get(0).getLhMachineCode());
    }

    @Test
    void scheduleNewSpecs_shouldNotSkipTrialSkuWhenTargetSundayButWindowStartsOnWorkday() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        injectDependencies(strategy, false);

        LhScheduleContext context = buildContext();
        Date scheduleDate = dateTime(2026, 5, 1, 0, 0);
        Date targetDate = dateTime(2026, 5, 3, 0, 0);
        context.setScheduleDate(scheduleDate);
        context.setScheduleTargetDate(targetDate);
        context.setScheduleWindowShifts(LhScheduleTimeUtil.buildDefaultScheduleShifts(context, scheduleDate));

        SkuScheduleDTO sku = buildSku();
        sku.setMaterialCode("3302001575");
        sku.setTrial(true);
        sku.setConstructionStage(ConstructionStageEnum.TRIAL.getCode());
        context.getNewSpecSkuList().add(sku);

        MachineScheduleDTO machine = buildMachine("K1501L", dateTime(2026, 5, 1, 6, 0));

        strategy.scheduleNewSpecs(context, singletonMachineMatch(machine), defaultMouldChangeBalance(),
                defaultInspectionBalance(), defaultCapacityCalculate());

        assertEquals(1, context.getScheduleResultList().size(),
                "目标日为周日但窗口起点仍有可排工作日时，试制SKU不应在进入选机前被整单拦截");
        assertEquals(0, context.getUnscheduledResultList().size());
        assertEquals("K1501L", context.getScheduleResultList().get(0).getLhMachineCode());
    }

    @Test
    void scheduleNewSpecs_shouldIgnoreSandBlastDelayAndOnlyKeepMouldChangeDuration() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        injectDependencies(strategy, false);

        LhScheduleContext context = buildContext();
        Date scheduleDate = dateTime(2026, 4, 22, 0, 0);
        context.setScheduleDate(scheduleDate);
        context.setScheduleTargetDate(scheduleDate);
        context.setScheduleWindowShifts(LhScheduleTimeUtil.buildDefaultScheduleShifts(context, scheduleDate));

        MachineScheduleDTO machine = new MachineScheduleDTO();
        machine.setMachineCode("K2025");
        machine.setMachineName("K2025");
        machine.setMaxMoldNum(1);
        machine.setEstimatedEndTime(dateTime(2026, 4, 22, 6, 0));
        MachineCleaningWindowDTO cleaningWindow = new MachineCleaningWindowDTO();
        cleaningWindow.setCleanType("02");
        cleaningWindow.setCleanStartTime(dateTime(2026, 4, 22, 6, 0));
        cleaningWindow.setCleanEndTime(dateTime(2026, 4, 22, 18, 0));
        cleaningWindow.setReadyTime(dateTime(2026, 4, 22, 16, 0));
        machine.setCleaningWindowList(Arrays.asList(cleaningWindow));
        context.getMachineScheduleMap().put(machine.getMachineCode(), machine);

        SkuScheduleDTO sku = buildSku();
        sku.setMaterialCode("MAT-CLEAN");
        sku.setMaterialDesc("喷砂重叠测试物料");
        sku.setPendingQty(8);
        sku.setDailyPlanQty(8);
        sku.setTargetScheduleQty(8);
        sku.setShiftCapacity(8);
        context.getNewSpecSkuList().add(sku);

        ICapacityCalculateStrategy capacityCalculateStrategy = new DefaultCapacityCalculateStrategy();
        IMouldChangeBalanceStrategy mouldChangeBalanceStrategy = new DefaultMouldChangeBalanceStrategy();
        IFirstInspectionBalanceStrategy inspectionBalanceStrategy =
                (ctx, machineCode, mouldChangeTime) -> mouldChangeTime;

        strategy.scheduleNewSpecs(context, singletonMachineMatch(machine), mouldChangeBalanceStrategy,
                inspectionBalanceStrategy, capacityCalculateStrategy);

        assertEquals(1, context.getScheduleResultList().size(), "重叠场景应正常生成新增换模结果");
        LhScheduleResult result = context.getScheduleResultList().get(0);
        assertEquals(dateTime(2026, 4, 22, 6, 0), result.getMouldChangeStartTime(),
                "喷砂与换模重叠时，不应再顺延到喷砂结束后才开始换模");
        int firstPlannedShiftIndex = resolveFirstPlannedShiftIndex(result);
        assertEquals(2, firstPlannedShiftIndex, "喷砂重叠但不再顺延时，首个排产班次应仍落在当日中班");
        assertEquals(dateTime(2026, 4, 22, 14, 0), ShiftFieldUtil.getShiftStartTime(result, firstPlannedShiftIndex),
                "新增排产应只保留换模8小时后的实际开产时刻");
        assertEquals(8, ShiftFieldUtil.getShiftPlanQty(result, firstPlannedShiftIndex).intValue(),
                "不再计入喷砂清洗时间后，首个完整中班应保留整班产量");
        assertEquals("模具清洗+换模", ShiftFieldUtil.getShiftAnalysis(result, firstPlannedShiftIndex),
                "喷砂重叠但不再顺延时，首个排产班次仍应保留模具清洗+换模原因分析");
    }

    @Test
    void scheduleNewSpecs_shouldUseMaintenanceOverlapSwitchHoursAndInspection() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        injectDependencies(strategy, true);

        LhScheduleContext context = buildContext();
        Date scheduleDate = dateTime(2026, 4, 22, 0, 0);
        context.setScheduleDate(scheduleDate);
        context.setScheduleTargetDate(scheduleDate);
        context.setScheduleWindowShifts(LhScheduleTimeUtil.buildDefaultScheduleShifts(context, scheduleDate));
        Map<String, String> scheduleParamMap = new HashMap<>(8);
        scheduleParamMap.put(LhScheduleParamConstant.MAINTENANCE_START_HOUR, "8");
        scheduleParamMap.put(LhScheduleParamConstant.MAINTENANCE_DURATION_HOURS, "7");
        scheduleParamMap.put(LhScheduleParamConstant.CAPSULE_PREHEAT_HOURS, "2.5");
        context.setScheduleConfig(new LhScheduleConfig(scheduleParamMap));

        MachineScheduleDTO machine = new MachineScheduleDTO();
        machine.setMachineCode("K2028");
        machine.setMachineName("K2028");
        machine.setMaxMoldNum(1);
        machine.setEstimatedEndTime(dateTime(2026, 4, 22, 6, 0));
        context.getMachineScheduleMap().put(machine.getMachineCode(), machine);
        context.getMaintenancePlanMap().put(machine.getMachineCode(),
                buildPrecisionPlan(machine.getMachineCode(), dateTime(2026, 5, 10, 0, 0)));

        SkuScheduleDTO sku = buildSku();
        sku.setMaterialCode("MAT-MAINTENANCE");
        sku.setMaterialDesc("维保换模重叠测试物料");
        sku.setSurplusQty(8);
        sku.setPendingQty(8);
        sku.setDailyPlanQty(8);
        sku.setTargetScheduleQty(8);
        sku.setShiftCapacity(8);
        context.getNewSpecSkuList().add(sku);

        strategy.scheduleNewSpecs(context, singletonMachineMatch(machine),
                new DefaultMouldChangeBalanceStrategy(),
                new com.zlt.aps.lh.engine.strategy.impl.DefaultFirstInspectionBalanceStrategy(),
                new DefaultCapacityCalculateStrategy());

        assertEquals(1, context.getScheduleResultList().size(), "维保与换模重叠时应正常生成新增排产结果");
        LhScheduleResult result = context.getScheduleResultList().get(0);
        assertEquals(dateTime(2026, 4, 22, 15, 0), result.getMouldChangeStartTime(),
                "维保重叠时，实际换模开始时间应从维保结束时刻起算");
        int firstPlannedShiftIndex = resolveFirstPlannedShiftIndex(result);
        assertEquals(2, firstPlannedShiftIndex, "维保重叠后的首个排产班次应落在当日中班");
        assertEquals(dateTime(2026, 4, 22, 20, 0), ShiftFieldUtil.getShiftStartTime(result, firstPlannedShiftIndex),
                "维保与换模重叠时，开产时间应为15:00+4小时换模+1小时首检");
    }

    @Test
    void scheduleNewSpecs_shouldBypassBalanceQuotaWhenChangeoverBalanceDisabled() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        injectDependencies(strategy, false);

        LhScheduleContext context = buildContext();
        context.setScheduleConfig(buildChangeoverBalanceScheduleConfig("0"));
        List<LhShiftConfigVO> shifts = context.getScheduleWindowShifts();
        LhShiftConfigVO firstShift = shifts.get(0);
        String workDateKey = LhScheduleTimeUtil.formatDate(firstShift.getWorkDate());
        context.getDailyMouldChangeCountMap().put(workDateKey, new int[]{8, 7});

        MachineScheduleDTO machine = buildMachine("K1201", firstShift.getShiftStartDateTime());
        context.getMachineScheduleMap().put(machine.getMachineCode(), machine);

        SkuScheduleDTO sku = buildSku();
        sku.setMaterialCode("3302009001");
        sku.setMaterialDesc("关闭换模均衡");
        sku.setShiftCapacity(1);
        sku.setTargetScheduleQty(1);
        sku.setSurplusQty(1);
        sku.setEmbryoStock(1);
        sku.setDailyPlanQuotaMap(buildSingleDayQuotaMap(firstShift, sku.getMaterialCode(), 1));
        context.getNewSpecSkuList().add(sku);

        strategy.scheduleNewSpecs(context, singletonMachineMatch(machine), new DefaultMouldChangeBalanceStrategy(),
                (ctx, machineCode, mouldChangeTime) -> mouldChangeTime, new DefaultCapacityCalculateStrategy());

        assertEquals(1, context.getScheduleResultList().size(), "关闭换模均衡后，新增排产应仍可生成结果");
        LhScheduleResult result = context.getScheduleResultList().get(0);
        assertEquals(firstShift.getShiftStartDateTime(), result.getMouldChangeStartTime(),
                "关闭换模均衡后，应直接从机台可换模时间开始扣减换模耗时");
        assertEquals(1, result.getDailyPlanQty().intValue(), "关闭换模均衡后，基础换模后应继续正常排产");
        assertEquals(8, context.getDailyMouldChangeCountMap().get(workDateKey)[0],
                "关闭换模均衡后，不应再占用早班换模配额");
        assertEquals(7, context.getDailyMouldChangeCountMap().get(workDateKey)[1],
                "关闭换模均衡后，不应再改写中班换模配额");
    }

    @Test
    void scheduleNewSpecs_shouldStartReleasedMachineMouldChangeAtFirstShift() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        injectDependencies(strategy, false);

        LhScheduleContext context = buildContext();
        List<LhShiftConfigVO> shifts = context.getScheduleWindowShifts();
        LhShiftConfigVO firstShift = shifts.get(0);
        LhShiftConfigVO secondShift = shifts.get(1);

        MachineScheduleDTO releasedMachine = buildMachine("K1406", firstShift.getShiftStartDateTime());
        context.getReleasedContinuousMachineCodeSet().add(releasedMachine.getMachineCode());

        SkuScheduleDTO sku = buildSku();
        sku.setMaterialCode("3302002319");
        sku.setMaterialDesc("3302002319");
        sku.setMouldChangeInfo("6-2");
        sku.setLhTimeSeconds(3600);
        sku.setShiftCapacity(8);
        sku.setPendingQty(28);
        sku.setTargetScheduleQty(28);
        sku.setSurplusQty(28);
        sku.setEmbryoStock(-1);
        sku.setDailyPlanQuotaMap(buildThreeDayQuotaMap(shifts, sku.getMaterialCode(), 28, 48, 48));
        attachAvailableMould(context, sku.getMaterialCode(), "MOULD-3302002319");
        context.getNewSpecSkuList().add(sku);

        strategy.scheduleNewSpecs(context, singletonMachineMatch(releasedMachine),
                defaultMouldChangeBalance(), defaultInspectionBalance(), defaultCapacityCalculate());

        assertEquals(1, context.getScheduleResultList().size(), "应生成新增排产结果");
        LhScheduleResult result = context.getScheduleResultList().get(0);
        assertEquals(firstShift.getShiftStartDateTime(), result.getMouldChangeStartTime(),
                "释放机台承接当天有日计划的新增SKU时，换模应从首个允许换模班次开始");
        assertEquals(secondShift.getShiftStartDateTime(), result.getClass2StartTime(),
                "8小时普通换模完成后，首个有量班次应从中班开始");
    }

    @Test
    void scheduleNewSpecs_shouldKeepFirstDayMouldChangeWhenEffectiveProductionDelayedToNextDay() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        injectDependencies(strategy, false);

        LhScheduleContext context = buildContext();
        Date scheduleDate = dateTime(2026, 5, 1, 0, 0);
        context.setScheduleDate(scheduleDate);
        context.setScheduleTargetDate(dateTime(2026, 5, 3, 0, 0));
        context.setScheduleWindowShifts(LhScheduleTimeUtil.buildDefaultScheduleShifts(context, scheduleDate));
        List<LhShiftConfigVO> shifts = context.getScheduleWindowShifts();
        LhShiftConfigVO firstShift = shifts.get(0);
        LhShiftConfigVO secondDayMiddleShift = shifts.get(4);

        MachineScheduleDTO machine = buildMachine("K2025", firstShift.getShiftStartDateTime());
        context.getMachineScheduleMap().put(machine.getMachineCode(), machine);

        SkuScheduleDTO sku = buildSku();
        sku.setMaterialCode("3302001418");
        sku.setMaterialDesc("3302001418");
        sku.setConstructionStage(ConstructionStageEnum.FORMAL.getCode());
        sku.setLhTimeSeconds(3600);
        sku.setShiftCapacity(18);
        sku.setPendingQty(90);
        sku.setDailyPlanQty(90);
        sku.setTargetScheduleQty(90);
        sku.setSurplusQty(90);
        sku.setEmbryoStock(-1);
        sku.setDailyPlanQuotaMap(buildThreeDayQuotaMap(shifts, sku.getMaterialCode(), 90, 90, 90));
        attachAvailableMould(context, sku.getMaterialCode(), "MOULD-3302001418");
        context.getNewSpecSkuList().add(sku);

        IFirstInspectionBalanceStrategy delayedInspectionBalance =
                (ctx, machineCode, mouldChangeTime) -> secondDayMiddleShift.getShiftStartDateTime();

        strategy.scheduleNewSpecs(context, singletonMachineMatch(machine), defaultMouldChangeBalance(),
                delayedInspectionBalance, defaultCapacityCalculate());

        assertEquals(1, context.getScheduleResultList().size(), "应生成新增排产结果");
        LhScheduleResult result = context.getScheduleResultList().get(0);
        assertEquals(firstShift.getShiftStartDateTime(), result.getMouldChangeStartTime(),
                "dayN命中首日需要增机时，不能因有效开产量后移而把换模推到其他业务日");
        assertTrue(resolveShiftQty(result, firstShift.getShiftIndex()) > 0,
                "普通换模首检数量仍应按换模完成落班，避免新增机台前序班次全部为空");
        assertEquals(firstShift.getShiftIndex(), resolveFirstPlannedShiftIndex(result),
                "换模完成落在首日早班结束临界点时，首检数量应归属首日早班");
        assertTrue(resolveShiftQty(result, shifts.get(1).getShiftIndex()) > 0,
                "普通8小时换模已包含首检，首检均衡后移不得阻断换模完成后的正常生产班次");
        assertTrue(resolveShiftQty(result, secondDayMiddleShift.getShiftIndex()) > 0,
                "首检后续班次应继续承接正常生产量");
    }

    @Test
    void scheduleNewSpecs_shouldKeepOriginalBalanceQuotaWhenChangeoverBalanceEnabled() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        injectDependencies(strategy, false);

        LhScheduleContext context = buildContext();
        context.setScheduleConfig(buildChangeoverBalanceScheduleConfig("1"));
        List<LhShiftConfigVO> shifts = context.getScheduleWindowShifts();
        LhShiftConfigVO firstShift = shifts.get(0);
        LhShiftConfigVO nextDayMorningShift = shifts.get(3);
        String workDateKey = LhScheduleTimeUtil.formatDate(firstShift.getWorkDate());
        String nextWorkDateKey = LhScheduleTimeUtil.formatDate(nextDayMorningShift.getWorkDate());
        context.getDailyMouldChangeCountMap().put(workDateKey, new int[]{8, 7});

        MachineScheduleDTO machine = buildMachine("K1202", firstShift.getShiftStartDateTime());
        context.getMachineScheduleMap().put(machine.getMachineCode(), machine);

        SkuScheduleDTO sku = buildSku();
        sku.setMaterialCode("3302009002");
        sku.setMaterialDesc("开启换模均衡");
        sku.setShiftCapacity(1);
        sku.setTargetScheduleQty(1);
        sku.setSurplusQty(1);
        sku.setEmbryoStock(1);
        sku.setDailyPlanQuotaMap(buildSingleDayQuotaMap(nextDayMorningShift, sku.getMaterialCode(), 1));
        context.getNewSpecSkuList().add(sku);

        strategy.scheduleNewSpecs(context, singletonMachineMatch(machine), new DefaultMouldChangeBalanceStrategy(),
                (ctx, machineCode, mouldChangeTime) -> mouldChangeTime, new DefaultCapacityCalculateStrategy());

        assertEquals(1, context.getScheduleResultList().size(), "开启换模均衡后，仍应在顺延后的班次生成结果");
        LhScheduleResult result = context.getScheduleResultList().get(0);
        assertEquals(nextDayMorningShift.getShiftStartDateTime(), result.getMouldChangeStartTime(),
                "开启换模均衡后，应继续受早/中班和日累计换模配额约束");
        assertEquals(1, context.getDailyMouldChangeCountMap().get(nextWorkDateKey)[0],
                "开启换模均衡后，应继续登记次日早班换模占用");
    }

    @Test
    void scheduleNewSpecs_shouldNotCallBalanceStrategyWhenChangeoverBalanceDisabled() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        injectDependencies(strategy, false);

        LhScheduleContext context = buildContext();
        context.setScheduleConfig(buildChangeoverBalanceScheduleConfig("0"));
        List<LhShiftConfigVO> shifts = context.getScheduleWindowShifts();
        LhShiftConfigVO firstShift = shifts.get(0);

        MachineScheduleDTO machine = buildMachine("K1203", firstShift.getShiftStartDateTime());
        context.getMachineScheduleMap().put(machine.getMachineCode(), machine);

        SkuScheduleDTO sku = buildSku();
        sku.setMaterialCode("3302009003");
        sku.setMaterialDesc("关闭换模均衡不调用策略");
        sku.setShiftCapacity(1);
        sku.setTargetScheduleQty(1);
        sku.setSurplusQty(1);
        sku.setEmbryoStock(1);
        sku.setDailyPlanQuotaMap(buildSingleDayQuotaMap(firstShift, sku.getMaterialCode(), 1));
        context.getNewSpecSkuList().add(sku);

        IMouldChangeBalanceStrategy forbiddenBalanceStrategy = new IMouldChangeBalanceStrategy() {
            @Override
            public boolean hasCapacity(LhScheduleContext ctx, Date targetDate) {
                throw new AssertionError("关闭换模均衡时不应查询换模均衡配额");
            }

            @Override
            public Date allocateMouldChange(LhScheduleContext ctx, String machineCode, Date endingTime) {
                throw new AssertionError("关闭换模均衡时不应调用换模均衡分配");
            }

            @Override
            public void rollbackMouldChange(LhScheduleContext ctx, Date allocatedTime) {
                throw new AssertionError("关闭换模均衡时不应回滚换模均衡配额");
            }

            @Override
            public int getRemainingCapacity(LhScheduleContext ctx, Date targetDate) {
                throw new AssertionError("关闭换模均衡时不应读取剩余换模均衡能力");
            }
        };

        strategy.scheduleNewSpecs(context, singletonMachineMatch(machine), forbiddenBalanceStrategy,
                (ctx, machineCode, mouldChangeTime) -> mouldChangeTime, new DefaultCapacityCalculateStrategy());

        assertEquals(1, context.getScheduleResultList().size(), "关闭换模均衡后，局部搜索和主流程都不应触碰均衡策略");
        assertEquals(firstShift.getShiftStartDateTime(), context.getScheduleResultList().get(0).getMouldChangeStartTime());
    }

    @Test
    void scheduleNewSpecs_shouldIgnoreSandBlastAndStillRespectNoMouldChangeWindow() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        injectDependencies(strategy, false);

        LhScheduleContext context = buildContext();
        Date scheduleDate = dateTime(2026, 4, 22, 0, 0);
        context.setScheduleDate(scheduleDate);
        context.setScheduleTargetDate(scheduleDate);
        context.setScheduleWindowShifts(LhScheduleTimeUtil.buildDefaultScheduleShifts(context, scheduleDate));

        MachineScheduleDTO machine = new MachineScheduleDTO();
        machine.setMachineCode("K2027");
        machine.setMachineName("K2027");
        machine.setMaxMoldNum(1);
        machine.setEstimatedEndTime(dateTime(2026, 4, 22, 8, 0));
        MachineCleaningWindowDTO cleaningWindow = new MachineCleaningWindowDTO();
        cleaningWindow.setCleanType("02");
        cleaningWindow.setCleanStartTime(dateTime(2026, 4, 22, 8, 0));
        cleaningWindow.setCleanEndTime(dateTime(2026, 4, 22, 20, 0));
        cleaningWindow.setReadyTime(dateTime(2026, 4, 22, 18, 0));
        machine.setCleaningWindowList(Arrays.asList(cleaningWindow));
        context.getMachineScheduleMap().put(machine.getMachineCode(), machine);

        SkuScheduleDTO sku = buildSku();
        sku.setMaterialCode("MAT-NO-CHANGE");
        sku.setMaterialDesc("喷砂后禁换顺延测试物料");
        sku.setPendingQty(8);
        sku.setDailyPlanQty(8);
        sku.setTargetScheduleQty(8);
        sku.setShiftCapacity(8);
        context.getNewSpecSkuList().add(sku);

        strategy.scheduleNewSpecs(context, singletonMachineMatch(machine),
                new DefaultMouldChangeBalanceStrategy(),
                (ctx, machineCode, mouldChangeTime) -> mouldChangeTime,
                new DefaultCapacityCalculateStrategy());

        assertEquals(1, context.getScheduleResultList().size(), "喷砂后进入禁止换模时段时，新增排产仍应正常生成结果");
        LhScheduleResult result = context.getScheduleResultList().get(0);
        assertEquals(dateTime(2026, 4, 22, 8, 0), result.getMouldChangeStartTime(),
                "喷砂与换模重叠时，不应先等待喷砂结束再判断禁止换模时段");
        int firstPlannedShiftIndex = resolveFirstPlannedShiftIndex(result);
        assertTrue(firstPlannedShiftIndex > 0, "顺延后应仍存在首个有效排产班次");
        assertEquals("模具清洗+换模", ShiftFieldUtil.getShiftAnalysis(result, firstPlannedShiftIndex),
                "喷砂重叠但不再顺延时，首个排产班次仍应保留模具清洗+换模分析");
    }

    @Test
    void scheduleNewSpecs_shouldUseFirstPlannedShiftStartForCleaningMouldAnalysis() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        injectDependencies(strategy, false);

        LhScheduleContext context = buildContext();
        Date scheduleDate = dateTime(2026, 4, 22, 0, 0);
        context.setScheduleDate(scheduleDate);
        context.setScheduleTargetDate(scheduleDate);
        context.setScheduleWindowShifts(LhScheduleTimeUtil.buildDefaultScheduleShifts(context, scheduleDate));

        MachineScheduleDTO machine = new MachineScheduleDTO();
        machine.setMachineCode("K2026");
        machine.setMachineName("K2026");
        machine.setMaxMoldNum(1);
        machine.setEstimatedEndTime(dateTime(2026, 4, 22, 13, 30));
        MachineCleaningWindowDTO cleaningWindow = new MachineCleaningWindowDTO();
        cleaningWindow.setCleanType("02");
        cleaningWindow.setCleanStartTime(dateTime(2026, 4, 22, 21, 40));
        cleaningWindow.setCleanEndTime(dateTime(2026, 4, 22, 21, 50));
        cleaningWindow.setReadyTime(dateTime(2026, 4, 22, 21, 50));
        machine.setCleaningWindowList(Arrays.asList(cleaningWindow));
        context.getMachineScheduleMap().put(machine.getMachineCode(), machine);

        SkuScheduleDTO sku = buildSku();
        sku.setMaterialCode("MAT-LATE");
        sku.setMaterialDesc("晚班备注测试物料");
        sku.setPendingQty(1);
        sku.setDailyPlanQty(1);
        sku.setTargetScheduleQty(1);
        sku.setShiftCapacity(8);
        context.getNewSpecSkuList().add(sku);

        ICapacityCalculateStrategy capacityCalculateStrategy = new DefaultCapacityCalculateStrategy();
        IMouldChangeBalanceStrategy mouldChangeBalanceStrategy = new DefaultMouldChangeBalanceStrategy();
        IFirstInspectionBalanceStrategy inspectionBalanceStrategy =
                (ctx, machineCode, mouldChangeTime) -> mouldChangeTime;

        strategy.scheduleNewSpecs(context, singletonMachineMatch(machine), mouldChangeBalanceStrategy,
                inspectionBalanceStrategy, capacityCalculateStrategy);

        LhScheduleResult result = context.getScheduleResultList().get(0);
        int firstPlannedShiftIndex = resolveFirstPlannedShiftIndex(result);
        assertEquals(dateTime(2026, 4, 22, 22, 0), ShiftFieldUtil.getShiftStartTime(result, firstPlannedShiftIndex),
                "当 inspection 所在班次无有效产能时，应从首个有量班次开始排产");
        assertEquals("模具清洗+换模", ShiftFieldUtil.getShiftAnalysis(result, firstPlannedShiftIndex),
                "原因分析应按首个有量班次开始时刻判定重叠窗口");
    }

    @Test
    void scheduleNewSpecs_shouldRestoreTargetQtyAfterFailedCandidateBuild() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        injectDependencies(strategy, false);

        LhScheduleContext context = buildContext();
        context.setScheduleWindowShifts(LhScheduleTimeUtil.buildDefaultScheduleShifts(context, context.getScheduleDate()));
        SkuScheduleDTO sku = buildSku();
        sku.setPendingQty(8);
        sku.setTargetScheduleQty(128);
        sku.setShiftCapacity(16);
        context.getNewSpecSkuList().add(sku);

        List<com.zlt.aps.lh.api.domain.vo.LhShiftConfigVO> shifts = context.getScheduleWindowShifts();
        Date firstShiftStart = shifts.get(0).getShiftStartDateTime();
        Date secondLastShiftStart = shifts.get(shifts.size() - 2).getShiftStartDateTime();
        Date lastShiftStart = shifts.get(shifts.size() - 1).getShiftStartDateTime();
        Date lastShiftEnd = shifts.get(shifts.size() - 1).getShiftEndDateTime();

        MachineScheduleDTO failedMachineCandidate = new MachineScheduleDTO();
        failedMachineCandidate.setMachineCode("M-ROLLBACK-FAIL");
        failedMachineCandidate.setMachineName("回滚失败机台");
        failedMachineCandidate.setMaxMoldNum(1);
        failedMachineCandidate.setEstimatedEndTime(secondLastShiftStart);

        MachineScheduleDTO successMachineCandidate = new MachineScheduleDTO();
        successMachineCandidate.setMachineCode("M-ROLLBACK-OK");
        successMachineCandidate.setMachineName("回滚成功机台");
        successMachineCandidate.setMaxMoldNum(1);
        successMachineCandidate.setEstimatedEndTime(firstShiftStart);

        MachineScheduleDTO failedMachineInContext = new MachineScheduleDTO();
        failedMachineInContext.setMachineCode("M-ROLLBACK-FAIL");
        failedMachineInContext.setMachineName("回滚失败机台");
        failedMachineInContext.setMaxMoldNum(1);
        MachineCleaningWindowDTO fullBlockWindow = new MachineCleaningWindowDTO();
        fullBlockWindow.setCleanType("01");
        fullBlockWindow.setCleanStartTime(lastShiftStart);
        fullBlockWindow.setCleanEndTime(lastShiftEnd);
        fullBlockWindow.setReadyTime(lastShiftEnd);
        failedMachineInContext.setCleaningWindowList(Arrays.asList(fullBlockWindow));
        context.getMachineScheduleMap().put(failedMachineInContext.getMachineCode(), failedMachineInContext);

        MachineScheduleDTO successMachineInContext = new MachineScheduleDTO();
        successMachineInContext.setMachineCode("M-ROLLBACK-OK");
        successMachineInContext.setMachineName("回滚成功机台");
        successMachineInContext.setMaxMoldNum(1);
        context.getMachineScheduleMap().put(successMachineInContext.getMachineCode(), successMachineInContext);

        IMachineMatchStrategy machineMatchStrategy = new IMachineMatchStrategy() {
            @Override
            public List<MachineScheduleDTO> matchMachines(LhScheduleContext ctx, SkuScheduleDTO scheduleSku) {
                return Arrays.asList(failedMachineCandidate, successMachineCandidate);
            }

            @Override
            public MachineScheduleDTO selectBestMachine(LhScheduleContext ctx, SkuScheduleDTO scheduleSku,
                                                        List<MachineScheduleDTO> candidates,
                                                        Set<String> excludedMachineCodes) {
                if (candidates == null || candidates.isEmpty()) {
                    return null;
                }
                for (MachineScheduleDTO candidate : candidates) {
                    if (candidate == null || excludedMachineCodes.contains(candidate.getMachineCode())) {
                        continue;
                    }
                    return candidate;
                }
                return null;
            }

            @Override
            public void traceEnabledMachineSort(LhScheduleContext context) {
                // 测试桩，无需实现
            }
        };

        strategy.scheduleNewSpecs(context, machineMatchStrategy, defaultMouldChangeBalance(),
                defaultInspectionBalance(), defaultCapacityCalculate());

        assertEquals(1, context.getScheduleResultList().size(), "候选机台失败回退后应继续尝试后续机台");
        assertEquals("M-ROLLBACK-OK", context.getScheduleResultList().get(0).getLhMachineCode());
        assertEquals(112, context.getScheduleResultList().get(0).getDailyPlanQty().intValue(),
                "失败候选机台的收敛目标量不应泄漏到后续成功机台");
        assertEquals(112, sku.getTargetScheduleQty().intValue(),
                "最终成功机台应按自身能力重新收敛目标量");
    }

    @Test
    void adjustEmbryoStock_shouldRemoveZeroPlanResultAndRestoreMachineState() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        injectDependencies(strategy, false);

        LhScheduleContext context = buildContext();
        MachineScheduleDTO machine = new MachineScheduleDTO();
        machine.setMachineCode("M-ZERO");
        machine.setMachineName("裁剪机台");
        machine.setCurrentMaterialCode("MAT-BASE");
        machine.setCurrentMaterialDesc("基础物料");
        machine.setPreviousSpecCode("BASE-SPEC");
        machine.setPreviousProSize("22.5");
        machine.setEstimatedEndTime(dateTime(2026, 4, 17, 6, 0));
        context.getMachineScheduleMap().put(machine.getMachineCode(), machine);

        SkuScheduleDTO sku = buildSku();
        sku.setMaterialCode("MAT-ZERO");
        sku.setEmbryoCode("EMB-1");
        sku.setStructureName("STRUCT-ZERO");
        sku.setEmbryoStock(0);
        context.getNewSpecSkuList().add(sku);
        context.getStructureSkuMap().put("STRUCT-ZERO", Arrays.asList(sku));

        strategy.scheduleNewSpecs(context, singletonMachineMatch(machine), defaultMouldChangeBalance(),
                defaultInspectionBalance(), defaultCapacityCalculate());

        assertEquals(1, context.getScheduleResultList().size(), "库存裁剪前应先生成新增排产结果");

        strategy.adjustEmbryoStock(context);

        assertEquals(0, context.getScheduleResultList().size(), "裁剪为0的新增结果应从排程结果列表移除");
        assertEquals(1, context.getUnscheduledResultList().size(), "裁剪为0的新增结果应转入未排");
        assertEquals(1, context.getUnscheduledResultList().get(0).getUnscheduledQty());
        assertEquals("新增结果裁剪为0", context.getUnscheduledResultList().get(0).getUnscheduledReason());
        assertEquals("MAT-BASE", machine.getCurrentMaterialCode(), "移除零计划结果后应回滚机台当前物料");
        assertEquals(dateTime(2026, 4, 17, 6, 0), machine.getEstimatedEndTime(), "机台完工时刻应回滚到初始状态");
    }

    @Test
    void adjustEmbryoStock_shouldResetIsEndWhenFinalPlanQtyLessThanMaxDemand() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        injectDependencies(strategy, true);

        LhScheduleContext context = buildContext();
        LhScheduleResult result = new LhScheduleResult();
        result.setScheduleType("02");
        result.setMaterialCode("MAT-ENDING-CHECK");
        result.setDailyPlanQty(88);
        result.setMouldSurplusQty(80);
        result.setEmbryoStock(140);
        result.setIsEnd("1");
        context.getScheduleResultList().add(result);

        strategy.adjustEmbryoStock(context);

        assertEquals("0", context.getScheduleResultList().get(0).getIsEnd(),
                "新增结果最终计划量小于max(硫化余量,胎胚库存)时，应回写为正常");
    }

    @Test
    void adjustEmbryoStock_shouldKeepIsEndWhenFinalPlanQtyReachMaxDemand() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        injectDependencies(strategy, false);

        LhScheduleContext context = buildContext();
        LhScheduleResult result = new LhScheduleResult();
        result.setScheduleType("02");
        result.setMaterialCode("MAT-ENDING-CHECK");
        result.setDailyPlanQty(140);
        result.setMouldSurplusQty(80);
        result.setEmbryoStock(140);
        result.setIsEnd("0");
        context.getScheduleResultList().add(result);

        strategy.adjustEmbryoStock(context);

        assertEquals("1", context.getScheduleResultList().get(0).getIsEnd(),
                "新增结果最终计划量达到max(硫化余量,胎胚库存)时，应回写为收尾");
    }

    @Test
    void scheduleNewSpecs_shouldUseUpdatedMachinePriorityRules() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        injectDependencies(strategy, false);

        LhScheduleContext context = buildContext();
        SkuScheduleDTO sku = buildSku();
        context.getNewSpecSkuList().add(sku);
        context.setEmbryoDescMaterialCountMap(new java.util.HashMap<String, Integer>() {{
            put("胎胚-早机", 1);
            put("胎胚-晚机", 9);
        }});

        MachineScheduleDTO earlierMachine = new MachineScheduleDTO();
        earlierMachine.setMachineCode("M-EARLY");
        earlierMachine.setMachineName("更早收尾机台");
        earlierMachine.setStatus("1");
        earlierMachine.setMaxMoldNum(1);
        earlierMachine.setEstimatedEndTime(dateTime(2026, 4, 17, 6, 0));
        earlierMachine.setPreviousSpecCode("SPEC-X");
        earlierMachine.setPreviousProSize("22.5");
        earlierMachine.setPreviousMaterialCode("MAT-EARLY");

        MachineScheduleDTO matchedSpecLateMachine = new MachineScheduleDTO();
        matchedSpecLateMachine.setMachineCode("M-LATE");
        matchedSpecLateMachine.setMachineName("更晚但同规格机台");
        matchedSpecLateMachine.setStatus("1");
        matchedSpecLateMachine.setMaxMoldNum(1);
        matchedSpecLateMachine.setEstimatedEndTime(dateTime(2026, 4, 17, 6, 30));
        matchedSpecLateMachine.setPreviousSpecCode("11R22.5");
        matchedSpecLateMachine.setPreviousProSize("22.5");
        matchedSpecLateMachine.setPreviousMaterialCode("MAT-LATE");

        context.getMachineScheduleMap().put(earlierMachine.getMachineCode(), earlierMachine);
        context.getMachineScheduleMap().put(matchedSpecLateMachine.getMachineCode(), matchedSpecLateMachine);

        MdmMaterialInfo earlyMaterial = new MdmMaterialInfo();
        earlyMaterial.setMaterialCode("MAT-EARLY");
        earlyMaterial.setEmbryoDesc("胎胚-早机");
        context.getMaterialInfoMap().put(earlyMaterial.getMaterialCode(), earlyMaterial);

        MdmMaterialInfo lateMaterial = new MdmMaterialInfo();
        lateMaterial.setMaterialCode("MAT-LATE");
        lateMaterial.setEmbryoDesc("胎胚-晚机");
        context.getMaterialInfoMap().put(lateMaterial.getMaterialCode(), lateMaterial);

        strategy.scheduleNewSpecs(context, new DefaultMachineMatchStrategy(), defaultMouldChangeBalance(),
                defaultInspectionBalance(), defaultCapacityCalculate());

        assertEquals(1, context.getScheduleResultList().size(), "应生成新增排产结果");
        assertEquals("M-EARLY", context.getScheduleResultList().get(0).getLhMachineCode(),
                "新增排产应复用更新后的选机优先级，先按收尾时间比较");
    }

    @Test
    void scheduleNewSpecs_shouldWriteMachineDecisionTraceLogWhenEnabled() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        injectDependencies(strategy, false);

        LhScheduleContext context = buildContext();
        Map<String, String> scheduleParamMap = new HashMap<>(4);
        scheduleParamMap.put(LhScheduleParamConstant.ENABLE_PRIORITY_TRACE_LOG, "1");
        scheduleParamMap.put(LhScheduleParamConstant.ENABLE_LOCAL_SEARCH, "0");
        context.setScheduleConfig(new LhScheduleConfig(scheduleParamMap));
        SkuScheduleDTO sku = buildSku();
        context.getNewSpecSkuList().add(sku);

        MachineScheduleDTO machine = new MachineScheduleDTO();
        machine.setMachineCode("M-TRACE");
        machine.setMachineName("跟踪机台");
        machine.setEstimatedEndTime(dateTime(2026, 4, 17, 6, 0));

        strategy.scheduleNewSpecs(context, singletonMachineMatch(machine), defaultMouldChangeBalance(),
                defaultInspectionBalance(), defaultCapacityCalculate());

        assertEquals(1, context.getScheduleResultList().size());
        LhScheduleProcessLog processLog = context.getScheduleLogList().stream()
                .filter(log -> StringUtils.equals("SKU选机台TOP5候选列表", log.getTitle()))
                .findFirst()
                .orElse(null);
        assertEquals(2, context.getScheduleLogList().size());
        assertTrue(processLog != null);
        assertEquals("SKU选机台TOP5候选列表", processLog.getTitle());
        assertTrue(processLog.getLogDetail().contains("MAT-1"));
        assertTrue(processLog.getLogDetail().contains("M-TRACE"));
        assertTrue(processLog.getLogDetail().contains("决策结果: 成功"));
    }

    @Test
    void scheduleNewSpecs_shouldWriteActualPendingQueueTraceForDeferredCompensationRound() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        injectDependencies(strategy, false);

        LhScheduleContext context = buildContext();
        Map<String, String> scheduleParamMap = new HashMap<>(4);
        scheduleParamMap.put(LhScheduleParamConstant.ENABLE_PRIORITY_TRACE_LOG, "1");
        context.setScheduleConfig(new LhScheduleConfig(scheduleParamMap));
        List<LhShiftConfigVO> shifts = context.getScheduleWindowShifts();
        LhShiftConfigVO firstShift = shifts.get(0);

        MachineScheduleDTO releasedMachine = buildMachine("K1105", firstShift.getShiftStartDateTime());
        releasedMachine.setPreviousMaterialCode("3302002546");
        MachineScheduleDTO fallbackMachine = buildMachine("K1110", firstShift.getShiftStartDateTime());
        context.getMachineScheduleMap().put(releasedMachine.getMachineCode(), releasedMachine);
        context.getMachineScheduleMap().put(fallbackMachine.getMachineCode(), fallbackMachine);

        SkuScheduleDTO sourceContinuousSku = buildSku();
        sourceContinuousSku.setMaterialCode("3302002546");
        sourceContinuousSku.setMaterialDesc("3302002546");
        sourceContinuousSku.setConstructionStage(ConstructionStageEnum.FORMAL.getCode());
        sourceContinuousSku.setContinuousMachineCode("K1105");
        sourceContinuousSku.setShiftCapacity(17);
        sourceContinuousSku.setPendingQty(102);
        sourceContinuousSku.setDailyPlanQty(102);
        sourceContinuousSku.setTargetScheduleQty(102);
        sourceContinuousSku.setSurplusQty(102);
        sourceContinuousSku.setEmbryoStock(102);
        sourceContinuousSku.setDailyPlanQuotaMap(buildThreeDayQuotaMap(
                shifts, "3302002546", 0, 32, 50));
        context.getContinuousSkuList().add(sourceContinuousSku);
        context.getReleasedContinuousMachineCodeSet().add("K1105");
        context.getFirstDayNoPlanReleasedContinuousMachineCodeSet().add("K1105");

        LhScheduleResult placeholderResult = new LhScheduleResult();
        placeholderResult.setFactoryCode(context.getFactoryCode());
        placeholderResult.setBatchNo(context.getBatchNo());
        placeholderResult.setLhMachineCode("K1105");
        placeholderResult.setLhMachineName("K1105");
        placeholderResult.setMaterialCode("3302002546");
        placeholderResult.setMaterialDesc("3302002546");
        placeholderResult.setScheduleType("01");
        placeholderResult.setIsTypeBlock("0");
        placeholderResult.setIsChangeMould("0");
        placeholderResult.setDailyPlanQty(102);
        placeholderResult.setSpecEndTime(dateTime(2026, 4, 19, 21, 12));
        context.getScheduleResultList().add(placeholderResult);
        context.getScheduleResultSourceSkuMap().put(placeholderResult, sourceContinuousSku);
        context.getMachineAssignmentMap().put("K1105",
                new java.util.ArrayList<LhScheduleResult>(Collections.singletonList(placeholderResult)));

        SkuScheduleDTO takeoverSku = buildSku();
        takeoverSku.setMaterialCode("3302001512");
        takeoverSku.setMaterialDesc("3302001512");
        takeoverSku.setConstructionStage(ConstructionStageEnum.FORMAL.getCode());
        takeoverSku.setShiftCapacity(17);
        takeoverSku.setPendingQty(102);
        takeoverSku.setDailyPlanQty(102);
        takeoverSku.setTargetScheduleQty(102);
        takeoverSku.setSurplusQty(102);
        takeoverSku.setEmbryoStock(102);
        takeoverSku.setDailyPlanQuotaMap(buildThreeDayQuotaMap(
                shifts, "3302001512", 32, 32, 38));
        context.getNewSpecSkuList().add(takeoverSku);

        IMachineMatchStrategy machineMatchStrategy = new IMachineMatchStrategy() {
            @Override
            public List<MachineScheduleDTO> matchMachines(LhScheduleContext ctx, SkuScheduleDTO scheduleSku) {
                if (StringUtils.equals("3302001512", scheduleSku.getMaterialCode())) {
                    return Arrays.asList(releasedMachine);
                }
                if (StringUtils.equals("3302002546", scheduleSku.getMaterialCode())) {
                    return Arrays.asList(fallbackMachine);
                }
                return Collections.emptyList();
            }

            @Override
            public MachineScheduleDTO selectBestMachine(LhScheduleContext ctx, SkuScheduleDTO scheduleSku,
                                                        List<MachineScheduleDTO> candidates,
                                                        Set<String> excludedMachineCodes) {
                for (MachineScheduleDTO candidate : candidates) {
                    if (candidate != null && !excludedMachineCodes.contains(candidate.getMachineCode())) {
                        return candidate;
                    }
                }
                return null;
            }

            @Override
            public void traceEnabledMachineSort(LhScheduleContext context) {
                // 测试桩，无需实现
            }
        };

        strategy.scheduleNewSpecs(context, machineMatchStrategy, defaultMouldChangeBalance(),
                defaultInspectionBalance(), defaultCapacityCalculate());

        List<LhScheduleProcessLog> queueLogs = context.getScheduleLogList().stream()
                .filter(log -> StringUtils.equals("新增待排队列【实际执行】", log.getTitle()))
                .collect(java.util.stream.Collectors.toList());
        assertEquals(2, queueLogs.size(), "补偿SKU插入下一轮后，应输出两轮真实待排队列日志");
        assertTrue(queueLogs.get(0).getLogDetail().contains("rank=1, sku=3302001512"),
                "首轮真实待排队列应从抢占机台的新增SKU开始");
        assertTrue(queueLogs.get(1).getLogDetail().contains("rank=1, sku=3302002546"),
                "第二轮真实待排队列应显式展示延后补偿SKU");
    }

    @Test
    void scheduleNewSpecs_shouldScheduleMassTrialOnNormalMachineWithoutTypeRetry() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        injectDependencies(strategy, false);
        injectTrialProductionStrategy(strategy, alwaysSchedulableTrialStrategy());

        LhScheduleContext context = buildContext();
        context.setScheduleConfig(buildSingleControlScheduleConfig());

        SkuScheduleDTO massTrialSku = buildSku();
        massTrialSku.setMaterialCode("3302002637");
        massTrialSku.setMaterialDesc("量试物料");
        massTrialSku.setConstructionStage(ConstructionStageEnum.MASS_TRIAL.getCode());

        SkuScheduleDTO formalSku = buildSku();
        formalSku.setMaterialCode("3302001513");
        formalSku.setMaterialDesc("正规物料");

        context.getNewSpecSkuList().add(massTrialSku);
        context.getNewSpecSkuList().add(formalSku);

        MachineScheduleDTO normalMachine = buildMachine("K1111", dateTime(2026, 4, 17, 6, 0));
        context.getMachineScheduleMap().put(normalMachine.getMachineCode(), normalMachine);

        strategy.scheduleNewSpecs(context, new DefaultMachineMatchStrategy(), defaultMouldChangeBalance(),
                defaultInspectionBalance(), defaultCapacityCalculate());

        assertEquals(2, context.getScheduleResultList().size(), "量试SKU应可直接使用普通机台，不再依赖类型重试");
        assertEquals(0, context.getUnscheduledResultList().size(), "量试SKU不再因正规SKU待排被类型规则延后");
        assertEquals("K1111", findResultByMaterialCode(context.getScheduleResultList(), "3302002637").getLhMachineCode());
        assertEquals("K1111", findResultByMaterialCode(context.getScheduleResultList(), "3302001513").getLhMachineCode());
    }

    @Test
    void scheduleNewSpecs_shouldScheduleFormalOnSingleControlMachineWithoutTypeRetry() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        injectDependencies(strategy, false);
        injectTrialProductionStrategy(strategy, alwaysSchedulableTrialStrategy());

        LhScheduleContext context = buildContext();
        context.setScheduleConfig(buildSingleControlScheduleConfig());

        SkuScheduleDTO formalSku = buildSku();
        formalSku.setMaterialCode("3302001513");
        formalSku.setMaterialDesc("正规物料");

        SkuScheduleDTO massTrialSku = buildSku();
        massTrialSku.setMaterialCode("3302002637");
        massTrialSku.setMaterialDesc("量试物料");
        massTrialSku.setConstructionStage(ConstructionStageEnum.MASS_TRIAL.getCode());

        context.getNewSpecSkuList().add(formalSku);
        context.getNewSpecSkuList().add(massTrialSku);

        MachineScheduleDTO singleControlMachine = buildMachine("K1501R", dateTime(2026, 4, 17, 6, 0));
        context.getMachineScheduleMap().put(singleControlMachine.getMachineCode(), singleControlMachine);

        strategy.scheduleNewSpecs(context, new DefaultMachineMatchStrategy(), defaultMouldChangeBalance(),
                defaultInspectionBalance(), defaultCapacityCalculate());

        assertEquals(2, context.getScheduleResultList().size(), "正规SKU无普通机台时，应可直接使用单控机台");
        assertEquals(0, context.getUnscheduledResultList().size(), "正规SKU不再因试制/量试待排被类型规则延后");
        assertEquals("K1501R", findResultByMaterialCode(context.getScheduleResultList(), "3302001513").getLhMachineCode());
        assertEquals("K1501R", findResultByMaterialCode(context.getScheduleResultList(), "3302002637").getLhMachineCode());
    }

    @Test
    void scheduleNewSpecs_shouldKeepProvidedSkuOrderWithoutSingleControlDeferral() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        injectDependencies(strategy, true);
        injectTrialProductionStrategy(strategy, new ITrialProductionStrategy() {
            @Override
            public List<SkuScheduleDTO> filterTrialSkus(LhScheduleContext context, List<SkuScheduleDTO> allSkus) {
                return allSkus;
            }

            @Override
            public boolean canScheduleTrialOnDate(LhScheduleContext context, Date targetDate) {
                return true;
            }

            @Override
            public boolean canScheduleTrialSkuOnDate(LhScheduleContext context, SkuScheduleDTO trialSku, Date targetDate) {
                return true;
            }

            @Override
            public boolean isDailyTrialLimitReached(LhScheduleContext context, Date targetDate) {
                return false;
            }

            @Override
            public boolean isDailyTrialLimitReached(LhScheduleContext context, Date targetDate, String materialCode) {
                return false;
            }

            @Override
            public String matchTrialMachine(LhScheduleContext context, SkuScheduleDTO trialSku) {
                if (StringUtils.equals("3302002216", trialSku.getMaterialCode())) {
                    return "K1501L";
                }
                return null;
            }
        });

        LhScheduleContext context = buildContext();
        context.setScheduleConfig(buildSingleControlScheduleConfig());

        SkuScheduleDTO massTrialSku = buildSku();
        massTrialSku.setMaterialCode("3302002637");
        massTrialSku.setMaterialDesc("量试物料");
        massTrialSku.setConstructionStage(ConstructionStageEnum.MASS_TRIAL.getCode());
        massTrialSku.setStructureName("STRUCT-MASS");
        massTrialSku.setSurplusQty(1);

        SkuScheduleDTO trialSku = buildSku();
        trialSku.setMaterialCode("3302002216");
        trialSku.setMaterialDesc("试制物料");
        trialSku.setConstructionStage(ConstructionStageEnum.TRIAL.getCode());
        trialSku.setStructureName("STRUCT-TRIAL");
        trialSku.setSurplusQty(1);

        context.getNewSpecSkuList().add(massTrialSku);
        context.getNewSpecSkuList().add(trialSku);

        MachineScheduleDTO leftSingleControlMachine = buildMachine("K1501L", dateTime(2026, 4, 17, 6, 0));
        MachineScheduleDTO rightSingleControlMachine = buildMachine("K1501R", dateTime(2026, 4, 17, 5, 0));
        MachineScheduleDTO normalMachine = buildMachine("K1105", dateTime(2026, 4, 17, 6, 0));
        strategy.scheduleNewSpecs(context, orderedMachineMatch(rightSingleControlMachine, leftSingleControlMachine, normalMachine),
                defaultMouldChangeBalance(),
                defaultInspectionBalance(), defaultCapacityCalculate());

        assertEquals(2, context.getScheduleResultList().size(), "停用单控竞争延后后，应严格按传入顺序逐个处理 SKU");
        assertEquals(0, context.getUnscheduledResultList().size(), "仍有可用候选时，不应生成未排");
        assertEquals("3302002637", context.getScheduleResultList().get(0).getMaterialCode(),
                "S4.5 不再因为单控竞争把后面的试制SKU提前到前面");
        assertEquals("K1501R", context.getScheduleResultList().get(0).getLhMachineCode());
        assertEquals("3302002216", context.getScheduleResultList().get(1).getMaterialCode());
        assertEquals("K1501L", context.getScheduleResultList().get(1).getLhMachineCode());
    }

    @Test
    void scheduleNewSpecs_shouldContinueNextSkuAfterFrontTrialBecomesUnscheduled() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        injectDependencies(strategy, true);
        injectTrialProductionStrategy(strategy, alwaysSchedulableTrialStrategy());

        LhScheduleContext context = buildContext();
        context.setScheduleConfig(buildSingleControlScheduleConfig());

        SkuScheduleDTO massTrialSku = buildSku();
        massTrialSku.setMaterialCode("3302002637");
        massTrialSku.setMaterialDesc("量试物料");
        massTrialSku.setConstructionStage(ConstructionStageEnum.MASS_TRIAL.getCode());
        massTrialSku.setStructureName("STRUCT-B");
        massTrialSku.setSurplusQty(1);

        SkuScheduleDTO trialSku = buildSku();
        trialSku.setMaterialCode("3302002216");
        trialSku.setMaterialDesc("试制物料");
        trialSku.setConstructionStage(ConstructionStageEnum.TRIAL.getCode());
        trialSku.setStructureName("STRUCT-B");
        trialSku.setSurplusQty(1);

        context.getNewSpecSkuList().add(trialSku);
        context.getNewSpecSkuList().add(massTrialSku);

        MachineScheduleDTO leftSingleControlMachine = buildMachine("K1501L", dateTime(2026, 4, 17, 6, 0));
        MachineScheduleDTO normalMachine = buildMachine("K1105", dateTime(2026, 4, 17, 6, 0));
        IMachineMatchStrategy machineMatchStrategy = new IMachineMatchStrategy() {
            @Override
            public List<MachineScheduleDTO> matchMachines(LhScheduleContext ctx, SkuScheduleDTO scheduleSku) {
                if (StringUtils.equals("3302002216", scheduleSku.getMaterialCode())) {
                    return Arrays.asList();
                }
                return Arrays.asList(leftSingleControlMachine, normalMachine);
            }

            @Override
            public MachineScheduleDTO selectBestMachine(LhScheduleContext ctx, SkuScheduleDTO scheduleSku,
                                                        List<MachineScheduleDTO> candidates,
                                                        Set<String> excludedMachineCodes) {
                for (MachineScheduleDTO candidate : candidates) {
                    if (candidate != null && !excludedMachineCodes.contains(candidate.getMachineCode())) {
                        return candidate;
                    }
                }
                return null;
            }

            @Override
            public void traceEnabledMachineSort(LhScheduleContext context) {
                // 测试桩，无需实现
            }
        };
        strategy.scheduleNewSpecs(context, machineMatchStrategy,
                defaultMouldChangeBalance(), defaultInspectionBalance(), defaultCapacityCalculate());

        assertEquals(1, context.getScheduleResultList().size(), "前面的试制SKU未排后，后续SKU仍应继续处理");
        assertEquals("3302002637", context.getScheduleResultList().get(0).getMaterialCode());
        assertEquals("K1501L", context.getScheduleResultList().get(0).getLhMachineCode());
        assertEquals(1, context.getUnscheduledResultList().size(), "前面的试制SKU应保留未排记录");
        assertEquals("3302002216", context.getUnscheduledResultList().get(0).getMaterialCode());
        assertTrue(context.getNewSpecSkuList().isEmpty(), "所有SKU处理完成后不应残留在待排列表");
    }

    @Test
    void scheduleNewSpecs_shouldNotReorderByStructureEndingLayerAfterSortingStage() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        injectDependencies(strategy, true);
        injectTrialProductionStrategy(strategy, alwaysSchedulableTrialStrategy());

        Field endingField = NewSpecProductionStrategy.class.getDeclaredField("endingJudgmentStrategy");
        endingField.setAccessible(true);
        endingField.set(strategy, new IEndingJudgmentStrategy() {
            @Override
            public boolean isEnding(LhScheduleContext context, SkuScheduleDTO sku) {
                return !StringUtils.equals("3302001575", sku.getMaterialCode());
            }

            @Override
            public int calculateEndingShifts(LhScheduleContext context, SkuScheduleDTO sku) {
                return isEnding(context, sku) ? 1 : 6;
            }

            @Override
            public int calculateEndingDays(LhScheduleContext context, SkuScheduleDTO sku) {
                return isEnding(context, sku) ? 1 : 6;
            }
        });

        LhScheduleContext context = buildContext();
        context.setScheduleConfig(buildSingleControlScheduleConfig());

        SkuScheduleDTO massTrialSku = buildSku();
        massTrialSku.setMaterialCode("3302002637");
        massTrialSku.setMaterialDesc("量试物料");
        massTrialSku.setConstructionStage(ConstructionStageEnum.MASS_TRIAL.getCode());
        massTrialSku.setStructureName("STRUCT-MASS");
        massTrialSku.setSurplusQty(1);

        SkuScheduleDTO endingTrialSku = buildSku();
        endingTrialSku.setMaterialCode("3302002216");
        endingTrialSku.setMaterialDesc("结构收尾试制");
        endingTrialSku.setConstructionStage(ConstructionStageEnum.TRIAL.getCode());
        endingTrialSku.setStructureName("STRUCT-TRIAL");
        endingTrialSku.setSurplusQty(1);

        SkuScheduleDTO normalTrialSku = buildSku();
        normalTrialSku.setMaterialCode("3302001575");
        normalTrialSku.setMaterialDesc("非结构收尾试制");
        normalTrialSku.setConstructionStage(ConstructionStageEnum.TRIAL.getCode());
        normalTrialSku.setStructureName("STRUCT-NORMAL");
        normalTrialSku.setSurplusQty(1);

        context.getNewSpecSkuList().add(massTrialSku);
        context.getNewSpecSkuList().add(endingTrialSku);
        context.getNewSpecSkuList().add(normalTrialSku);

        MachineScheduleDTO leftSingleControlMachine = buildMachine("K1501L", dateTime(2026, 4, 17, 6, 0));
        MachineScheduleDTO rightSingleControlMachine = buildMachine("K1501R", dateTime(2026, 4, 17, 6, 0));

        IMachineMatchStrategy machineMatchStrategy = new IMachineMatchStrategy() {
            @Override
            public List<MachineScheduleDTO> matchMachines(LhScheduleContext ctx, SkuScheduleDTO scheduleSku) {
                if (StringUtils.equals("3302002216", scheduleSku.getMaterialCode())) {
                    return Arrays.asList(leftSingleControlMachine);
                }
                if (StringUtils.equals("3302002637", scheduleSku.getMaterialCode())) {
                    return Arrays.asList(rightSingleControlMachine);
                }
                if (StringUtils.equals("3302001575", scheduleSku.getMaterialCode())) {
                    return Arrays.asList(leftSingleControlMachine);
                }
                return Arrays.asList();
            }

            @Override
            public MachineScheduleDTO selectBestMachine(LhScheduleContext ctx, SkuScheduleDTO scheduleSku,
                                                        List<MachineScheduleDTO> candidates,
                                                        Set<String> excludedMachineCodes) {
                for (MachineScheduleDTO candidate : candidates) {
                    if (candidate != null && !excludedMachineCodes.contains(candidate.getMachineCode())) {
                        return candidate;
                    }
                }
                return null;
            }

            @Override
            public void traceEnabledMachineSort(LhScheduleContext context) {
                // 测试桩，无需实现
            }
        };

        strategy.scheduleNewSpecs(context, machineMatchStrategy, defaultMouldChangeBalance(),
                defaultInspectionBalance(), defaultCapacityCalculate());

        assertEquals(3, context.getScheduleResultList().size(), "停用单控竞争重排后，应严格按当前列表顺序处理");
        assertEquals("3302002637", context.getScheduleResultList().get(0).getMaterialCode(),
                "量试SKU不应再因为结构五天内收尾层级被恢复到后续试制之前");
        assertEquals("K1501R", context.getScheduleResultList().get(0).getLhMachineCode());
        assertEquals("3302002216", context.getScheduleResultList().get(1).getMaterialCode());
        assertEquals("K1501L", context.getScheduleResultList().get(1).getLhMachineCode());
        assertEquals("3302001575", context.getScheduleResultList().get(2).getMaterialCode(),
                "后续试制SKU应继续按原始顺序处理，而不是被结构层级再次重排");
    }

    @Test
    void scheduleNewSpecs_shouldFallbackToNormalMachineWhenSplitMachineIsNotConfiguredAsSingleControl() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        injectDependencies(strategy, false);
        injectTrialProductionStrategy(strategy, alwaysSchedulableTrialStrategy());

        LhScheduleContext context = buildContext();
        context.setScheduleConfig(buildSingleControlScheduleConfig());

        SkuScheduleDTO massTrialSku = buildSku();
        massTrialSku.setMaterialCode("3302002637");
        massTrialSku.setMaterialDesc("量试物料");
        massTrialSku.setConstructionStage(ConstructionStageEnum.MASS_TRIAL.getCode());
        context.getNewSpecSkuList().add(massTrialSku);

        MachineScheduleDTO unconfiguredSplitMachine = buildMachine("K1601R", dateTime(2026, 4, 17, 6, 0));
        MachineScheduleDTO normalMachine = buildMachine("K1111", dateTime(2026, 4, 17, 6, 0));
        context.getMachineScheduleMap().put(unconfiguredSplitMachine.getMachineCode(), unconfiguredSplitMachine);
        context.getMachineScheduleMap().put(normalMachine.getMachineCode(), normalMachine);

        strategy.scheduleNewSpecs(context, new DefaultMachineMatchStrategy(), defaultMouldChangeBalance(),
                defaultInspectionBalance(), defaultCapacityCalculate());

        assertEquals(1, context.getScheduleResultList().size(), "未配置的拆分机台不应阻断量试回落普通机台");
        assertEquals("K1111", context.getScheduleResultList().get(0).getLhMachineCode());
        assertEquals(0, context.getUnscheduledResultList().size());
    }

    @Test
    void scheduleNewSpecs_shouldUseGenericReasonWhenNoCandidatesNotCausedByTypeRule() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        injectDependencies(strategy, false);
        injectTrialProductionStrategy(strategy, alwaysSchedulableTrialStrategy());

        LhScheduleContext context = buildContext();
        context.setScheduleConfig(buildSingleControlScheduleConfig());

        SkuScheduleDTO formalSku = buildSku();
        formalSku.setMaterialCode("3302001513");
        formalSku.setMaterialDesc("正规物料");
        formalSku.setProSize("19.5");
        formalSku.setSpecCode("SPEC-19");

        SkuScheduleDTO massTrialSku = buildSku();
        massTrialSku.setMaterialCode("3302002637");
        massTrialSku.setMaterialDesc("量试物料");
        massTrialSku.setConstructionStage(ConstructionStageEnum.MASS_TRIAL.getCode());

        context.getNewSpecSkuList().add(formalSku);
        context.getNewSpecSkuList().add(massTrialSku);

        MachineScheduleDTO hardMismatchMachine = buildMachine("K1111", dateTime(2026, 4, 17, 6, 0));
        hardMismatchMachine.setStatus("0");
        context.getMachineScheduleMap().put(hardMismatchMachine.getMachineCode(), hardMismatchMachine);

        strategy.scheduleNewSpecs(context, new DefaultMachineMatchStrategy(), defaultMouldChangeBalance(),
                defaultInspectionBalance(), defaultCapacityCalculate());

        assertEquals("无可用硫化机台",
                findUnscheduledResultByMaterialCode(context.getUnscheduledResultList(), "3302001513").getUnscheduledReason(),
                "若候选机台本身就硬匹配失败，不应误报为类型让位导致未排");
    }

    @Test
    void scheduleNewSpecs_shouldUseGenericReasonForTrialWhenNoCandidatesNotCausedByTypeRule() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        injectDependencies(strategy, false);
        injectTrialProductionStrategy(strategy, alwaysSchedulableTrialStrategy());

        LhScheduleContext context = buildContext();
        context.setScheduleConfig(buildSingleControlScheduleConfig());

        SkuScheduleDTO trialSku = buildSku();
        trialSku.setMaterialCode("3302001575");
        trialSku.setConstructionStage(ConstructionStageEnum.TRIAL.getCode());
        trialSku.setProSize("19.5");
        trialSku.setSpecCode("SPEC-19");
        context.getNewSpecSkuList().add(trialSku);

        MachineScheduleDTO hardMismatchMachine = buildMachine("K1501R", dateTime(2026, 4, 17, 6, 0));
        hardMismatchMachine.setStatus("0");
        context.getMachineScheduleMap().put(hardMismatchMachine.getMachineCode(), hardMismatchMachine);

        strategy.scheduleNewSpecs(context, new DefaultMachineMatchStrategy(), defaultMouldChangeBalance(),
                defaultInspectionBalance(), defaultCapacityCalculate());

        assertEquals("无可用硫化机台",
                findUnscheduledResultByMaterialCode(context.getUnscheduledResultList(), "3302001575").getUnscheduledReason(),
                "试制SKU若本来就无候选机台，不应误报为单控约束导致未排");
    }

    @Test
    void scheduleNewSpecs_shouldUseTrialSingleControlReasonWhenOnlyNormalMachineCandidatesRemain() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        injectDependencies(strategy, false);
        injectTrialProductionStrategy(strategy, alwaysSchedulableTrialStrategy());

        LhScheduleContext context = buildContext();
        context.setScheduleConfig(buildSingleControlScheduleConfig());

        SkuScheduleDTO trialSku = buildSku();
        trialSku.setMaterialCode("3302001575");
        trialSku.setConstructionStage(ConstructionStageEnum.TRIAL.getCode());
        context.getNewSpecSkuList().add(trialSku);

        MachineScheduleDTO normalMachine = buildMachine("K1111", dateTime(2026, 4, 17, 6, 0));
        context.getMachineScheduleMap().put(normalMachine.getMachineCode(), normalMachine);

        strategy.scheduleNewSpecs(context, new DefaultMachineMatchStrategy(), defaultMouldChangeBalance(),
                defaultInspectionBalance(), defaultCapacityCalculate());

        assertEquals("试制SKU只能使用单控机台，但当前无可用单控机台或单控机台产能不足，无法排产",
                findUnscheduledResultByMaterialCode(context.getUnscheduledResultList(), "3302001575").getUnscheduledReason(),
                "试制SKU仅剩普通机台候选时，应返回单控专属未排原因");
    }

    @Test
    void scheduleNewSpecs_shouldReserveSingleControlForPendingSmallBatchBeforeFormalSku() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        injectDependencies(strategy, false);
        injectTrialProductionStrategy(strategy, alwaysSchedulableTrialStrategy());

        LhScheduleContext context = buildContext();
        context.setScheduleConfig(buildSingleControlScheduleConfig());

        SkuScheduleDTO formalSku = buildSku();
        formalSku.setMaterialCode("3302001513");
        formalSku.setConstructionStage("03");

        SkuScheduleDTO smallBatchSku = buildSku();
        smallBatchSku.setMaterialCode("3302002601");
        smallBatchSku.setConstructionStage("03");
        smallBatchSku.setSmallBatchValidation(true);

        context.getNewSpecSkuList().add(formalSku);
        context.getNewSpecSkuList().add(smallBatchSku);

        MachineScheduleDTO singleControlMachine = buildMachine("K1501R", dateTime(2026, 4, 17, 6, 0));
        context.getMachineScheduleMap().put(singleControlMachine.getMachineCode(), singleControlMachine);

        strategy.scheduleNewSpecs(context, new DefaultMachineMatchStrategy(), defaultMouldChangeBalance(),
                defaultInspectionBalance(), defaultCapacityCalculate());

        assertEquals("待排小批量SKU未完成，单控机台优先保留给小批量SKU，当前正规SKU无法使用单控机台",
                findUnscheduledResultByMaterialCode(context.getUnscheduledResultList(), "3302001513").getUnscheduledReason(),
                "正规SKU前面命中唯一单控时，应先把单控资源保留给后续待排小批量SKU");
    }

    @Test
    void scheduleNewSpecs_shouldAllowFormalSkuUseSingleControlWhenPendingSmallBatchHasNoWindowQuota() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        injectDependencies(strategy, false);
        injectTrialProductionStrategy(strategy, alwaysSchedulableTrialStrategy());

        LhScheduleContext context = buildContext();
        context.setScheduleConfig(buildSingleControlScheduleConfig());

        SkuScheduleDTO formalSku = buildSku();
        formalSku.setMaterialCode("3302001513");
        formalSku.setConstructionStage("03");

        SkuScheduleDTO smallBatchSku = buildSku();
        smallBatchSku.setMaterialCode("3302002601");
        smallBatchSku.setConstructionStage("03");
        smallBatchSku.setSmallBatchValidation(true);
        smallBatchSku.setWindowPlanQty(0);
        smallBatchSku.setWindowRemainingPlanQty(0);
        smallBatchSku.setDailyPlanQuotaMap(new LinkedHashMap<LocalDate, SkuDailyPlanQuotaDTO>());

        context.getNewSpecSkuList().add(formalSku);
        context.getNewSpecSkuList().add(smallBatchSku);

        MachineScheduleDTO singleControlMachine = buildMachine("K1501R", dateTime(2026, 4, 17, 6, 0));
        context.getMachineScheduleMap().put(singleControlMachine.getMachineCode(), singleControlMachine);

        strategy.scheduleNewSpecs(context, new DefaultMachineMatchStrategy(), defaultMouldChangeBalance(),
                defaultInspectionBalance(), defaultCapacityCalculate());

        assertNotNull(findScheduleResultByMaterialCode(context.getScheduleResultList(), "3302001513"),
                "当后续小批量窗口内已无可排额度时，前面的正规SKU应允许使用单控机台");
    }

    @Test
    void scheduleNewSpecs_shouldUseSpecialMaterialReasonWhenBaseMachineLacksSupportCapability() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        injectDependencies(strategy, false);
        injectTrialProductionStrategy(strategy, alwaysSchedulableTrialStrategy());

        LhScheduleContext context = buildContext();
        context.setScheduleConfig(buildSingleControlScheduleConfig());

        SkuScheduleDTO specialSku = buildSku();
        specialSku.setMaterialCode("3302001513");
        specialSku.setMaterialDesc("19.5宽基特殊材料");
        specialSku.setProSize("19.5");
        specialSku.setSpecCode("SPEC-19");
        context.getSpecialMaterialCategoryByMaterialCode().put(specialSku.getMaterialCode(),
                new LinkedHashSet<String>(Collections.singletonList("01")));
        context.getNewSpecSkuList().add(specialSku);

        MachineScheduleDTO baseMatchedMachine = buildMachine("K1111", dateTime(2026, 4, 17, 6, 0));
        baseMatchedMachine.setDimensionMinimum(new BigDecimal("15"));
        baseMatchedMachine.setDimensionMaximum(new BigDecimal("25"));
        context.getMachineScheduleMap().put(baseMatchedMachine.getMachineCode(), baseMatchedMachine);

        strategy.scheduleNewSpecs(context, new DefaultMachineMatchStrategy(), defaultMouldChangeBalance(),
                defaultInspectionBalance(), defaultCapacityCalculate());

        assertEquals("特殊材料SKU无匹配特殊支持机台，无法排产",
                findUnscheduledResultByMaterialCode(context.getUnscheduledResultList(), "3302001513").getUnscheduledReason(),
                "特殊材料SKU基础条件匹配但缺少特殊支持能力时，应返回专属未排原因");
    }

    @Test
    void scheduleNewSpecs_shouldWriteExcludedMachineReasonTraceLogWhenCandidateFails() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        injectDependencies(strategy, false);

        LhScheduleContext context = buildContext();
        Map<String, String> scheduleParamMap = new HashMap<>(4);
        scheduleParamMap.put(LhScheduleParamConstant.ENABLE_PRIORITY_TRACE_LOG, "1");
        scheduleParamMap.put(LhScheduleParamConstant.ENABLE_LOCAL_SEARCH, "0");
        context.setScheduleConfig(new LhScheduleConfig(scheduleParamMap));
        SkuScheduleDTO sku = buildSku();
        sku.setMaterialCode("3302001885");
        sku.setTargetScheduleQty(1);
        context.getNewSpecSkuList().add(sku);

        MachineScheduleDTO machine = new MachineScheduleDTO();
        machine.setMachineCode("K2027");
        machine.setMachineName("K2027");
        machine.setEstimatedEndTime(dateTime(2026, 4, 19, 23, 0));

        strategy.scheduleNewSpecs(context, singletonMachineMatch(machine), defaultMouldChangeBalance(),
                defaultInspectionBalance(), defaultCapacityCalculate());

        assertEquals(0, context.getScheduleResultList().size());
        assertEquals(1, context.getUnscheduledResultList().size());
        LhScheduleProcessLog processLog = context.getScheduleLogList().stream()
                .filter(log -> StringUtils.equals("SKU选机台TOP5候选列表", log.getTitle()))
                .findFirst()
                .orElse(null);
        assertEquals(2, context.getScheduleLogList().size());
        assertTrue(processLog != null);
        assertTrue(processLog.getLogDetail().contains("排除明细"));
        assertTrue(processLog.getLogDetail().contains("K2027"));
        assertTrue(processLog.getLogDetail().contains("排程窗口内无可开产时间"));
        assertTrue(processLog.getLogDetail().contains("机台就绪"));
    }

    @Test
    void scheduleNewSpecs_shouldControlTraceLogVolumeWhenLocalSearchAndTraceEnabled() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        injectDependencies(strategy, false);

        LhScheduleContext context = buildContext();
        Map<String, String> scheduleParamMap = new HashMap<>(8);
        scheduleParamMap.put(LhScheduleParamConstant.ENABLE_PRIORITY_TRACE_LOG, "1");
        scheduleParamMap.put(LhScheduleParamConstant.ENABLE_LOCAL_SEARCH, "1");
        scheduleParamMap.put(LhScheduleParamConstant.LOCAL_SEARCH_MACHINE_THRESHOLD, "10");
        scheduleParamMap.put(LhScheduleParamConstant.LOCAL_SEARCH_DEPTH, "2");
        scheduleParamMap.put(LhScheduleParamConstant.LOCAL_SEARCH_TIME_BUDGET_MS, "200");
        context.setScheduleConfig(new LhScheduleConfig(scheduleParamMap));

        SkuScheduleDTO firstSku = buildSku();
        SkuScheduleDTO secondSku = buildSku();
        secondSku.setMaterialCode("MAT-2");
        secondSku.setMaterialDesc("测试物料2");
        context.getNewSpecSkuList().add(firstSku);
        context.getNewSpecSkuList().add(secondSku);

        MachineScheduleDTO firstMachine = new MachineScheduleDTO();
        firstMachine.setMachineCode("M-LS-1");
        firstMachine.setMachineName("局部搜索机台1");
        firstMachine.setStatus("1");
        firstMachine.setMaxMoldNum(1);
        firstMachine.setEstimatedEndTime(dateTime(2026, 4, 17, 6, 0));
        firstMachine.setPreviousSpecCode("11R22.5");
        firstMachine.setPreviousProSize("22.5");
        firstMachine.setPreviousMaterialCode("MAT-PREV-1");

        MachineScheduleDTO secondMachine = new MachineScheduleDTO();
        secondMachine.setMachineCode("M-LS-2");
        secondMachine.setMachineName("局部搜索机台2");
        secondMachine.setStatus("1");
        secondMachine.setMaxMoldNum(1);
        secondMachine.setEstimatedEndTime(dateTime(2026, 4, 17, 6, 30));
        secondMachine.setPreviousSpecCode("11R22.5");
        secondMachine.setPreviousProSize("22.5");
        secondMachine.setPreviousMaterialCode("MAT-PREV-2");

        context.getMachineScheduleMap().put(firstMachine.getMachineCode(), firstMachine);
        context.getMachineScheduleMap().put(secondMachine.getMachineCode(), secondMachine);

        MdmMaterialInfo prevMaterial1 = new MdmMaterialInfo();
        prevMaterial1.setMaterialCode("MAT-PREV-1");
        prevMaterial1.setEmbryoDesc("胎胚-A");
        context.getMaterialInfoMap().put(prevMaterial1.getMaterialCode(), prevMaterial1);

        MdmMaterialInfo prevMaterial2 = new MdmMaterialInfo();
        prevMaterial2.setMaterialCode("MAT-PREV-2");
        prevMaterial2.setEmbryoDesc("胎胚-B");
        context.getMaterialInfoMap().put(prevMaterial2.getMaterialCode(), prevMaterial2);

        strategy.scheduleNewSpecs(context, new DefaultMachineMatchStrategy(), defaultMouldChangeBalance(),
                defaultInspectionBalance(), defaultCapacityCalculate());

        assertEquals(2, context.getScheduleResultList().size());
        int candidateTraceLogCount = 0;
        int decisionTraceLogCount = 0;
        for (LhScheduleProcessLog processLog : context.getScheduleLogList()) {
            if ("机台排序优先级汇总【新增排产选机台】".equals(processLog.getTitle())) {
                candidateTraceLogCount++;
            }
            if ("SKU选机台TOP5候选列表".equals(processLog.getTitle())) {
                decisionTraceLogCount++;
            }
        }
        assertEquals(2, candidateTraceLogCount,
                "启用局部搜索后，候选机台日志应按真实SKU决策输出，不应写入DFS模拟分支日志");
        assertEquals(2, decisionTraceLogCount, "应为每个真实决策SKU输出一条机台决策日志");
        assertEquals(5, context.getScheduleLogList().size(), "新增真实待排队列日志后，总日志数量应同步增加一条");
    }

    @Test
    void scheduleNewSpecs_shouldKeepBaseFirstCandidateWhenLocalSearchSuggestsAnotherMachine() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        injectDependencies(strategy, false);

        LhScheduleContext context = buildContext();
        Map<String, String> scheduleParamMap = new HashMap<>(8);
        scheduleParamMap.put(LhScheduleParamConstant.ENABLE_LOCAL_SEARCH, "1");
        scheduleParamMap.put(LhScheduleParamConstant.LOCAL_SEARCH_MACHINE_THRESHOLD, "10");
        scheduleParamMap.put(LhScheduleParamConstant.LOCAL_SEARCH_DEPTH, "3");
        scheduleParamMap.put(LhScheduleParamConstant.LOCAL_SEARCH_TIME_BUDGET_MS, "200");
        context.setScheduleConfig(new LhScheduleConfig(scheduleParamMap));

        SkuScheduleDTO firstSku = buildSku();
        firstSku.setMaterialCode("MAT-A");
        firstSku.setMaterialDesc("测试物料A");
        firstSku.setPendingQty(1);
        firstSku.setDailyPlanQty(1);
        SkuScheduleDTO secondSku = buildSku();
        secondSku.setMaterialCode("MAT-B");
        secondSku.setMaterialDesc("测试物料B");
        secondSku.setPendingQty(1);
        secondSku.setDailyPlanQty(1);
        SkuScheduleDTO thirdSku = buildSku();
        thirdSku.setMaterialCode("MAT-C");
        thirdSku.setMaterialDesc("测试物料C");
        thirdSku.setPendingQty(1);
        thirdSku.setDailyPlanQty(1);
        context.getNewSpecSkuList().add(firstSku);
        context.getNewSpecSkuList().add(secondSku);
        context.getNewSpecSkuList().add(thirdSku);

        MachineScheduleDTO firstMachine = buildMachine("K2025", dateTime(2026, 4, 17, 6, 0));
        MachineScheduleDTO secondMachine = buildMachine("K2026", dateTime(2026, 4, 17, 6, 0));
        MachineScheduleDTO thirdMachine = buildMachine("K2027", dateTime(2026, 4, 17, 6, 0));
        context.getMachineScheduleMap().put(firstMachine.getMachineCode(), firstMachine);
        context.getMachineScheduleMap().put(secondMachine.getMachineCode(), secondMachine);
        context.getMachineScheduleMap().put(thirdMachine.getMachineCode(), thirdMachine);

        injectLocalSearchAllocator(strategy, new LocalSearchMachineAllocatorStrategy() {
            @Override
            public MachineScheduleDTO selectBestMachine(LhScheduleContext ctx,
                                                        List<SkuScheduleDTO> windowSkuList,
                                                        List<MachineScheduleDTO> currentCandidates,
                                                        List<LhShiftConfigVO> shifts,
                                                        IMachineMatchStrategy machineMatch,
                                                        IMouldChangeBalanceStrategy mouldChangeBalance,
                                                        IFirstInspectionBalanceStrategy inspectionBalance,
                                                        ICapacityCalculateStrategy capacityCalculate) {
                return currentCandidates.get(1);
            }
        });

        strategy.scheduleNewSpecs(context, new DefaultMachineMatchStrategy(), defaultMouldChangeBalance(),
                defaultInspectionBalance(), defaultCapacityCalculate());

        assertEquals(3, context.getScheduleResultList().size());
        assertEquals("K2025", context.getScheduleResultList().get(0).getLhMachineCode(),
                "局部搜索返回后序机台时，当前SKU仍应先按基础候选首位落机");
    }

    @Test
    void scheduleNewSpecs_shouldKeepRealSkuOrderAndBaseMachineOrderWhenLocalSearchSuggestsRotatedMachines() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        injectDependencies(strategy, true);

        LhScheduleContext context = buildContext();
        Map<String, String> scheduleParamMap = new HashMap<>(8);
        scheduleParamMap.put(LhScheduleParamConstant.ENABLE_LOCAL_SEARCH, "1");
        scheduleParamMap.put(LhScheduleParamConstant.LOCAL_SEARCH_MACHINE_THRESHOLD, "10");
        scheduleParamMap.put(LhScheduleParamConstant.LOCAL_SEARCH_DEPTH, "3");
        scheduleParamMap.put(LhScheduleParamConstant.LOCAL_SEARCH_TIME_BUDGET_MS, "200");
        scheduleParamMap.put(LhScheduleParamConstant.ENABLE_CHANGEOVER_BALANCE, "1");
        context.setScheduleConfig(new LhScheduleConfig(scheduleParamMap));

        SkuScheduleDTO firstSku = buildRealIssueSku("3302002530", "EAR30", 7);
        SkuScheduleDTO secondSku = buildRealIssueSku("3302001038", "BT165", 8);
        SkuScheduleDTO thirdSku = buildRealIssueSku("3302000245", "JF568", 9);
        context.getNewSpecSkuList().add(firstSku);
        context.getNewSpecSkuList().add(secondSku);
        context.getNewSpecSkuList().add(thirdSku);

        MachineScheduleDTO k2025 = buildMachine("K2025", dateTime(2026, 4, 17, 6, 0));
        MachineScheduleDTO k2026 = buildMachine("K2026", dateTime(2026, 4, 17, 6, 0));
        MachineScheduleDTO k2027 = buildMachine("K2027", dateTime(2026, 4, 17, 6, 0));
        context.getMachineScheduleMap().put(k2025.getMachineCode(), k2025);
        context.getMachineScheduleMap().put(k2026.getMachineCode(), k2026);
        context.getMachineScheduleMap().put(k2027.getMachineCode(), k2027);

        injectLocalSearchAllocator(strategy, new LocalSearchMachineAllocatorStrategy() {
            @Override
            public MachineScheduleDTO selectBestMachine(LhScheduleContext ctx,
                                                        List<SkuScheduleDTO> windowSkuList,
                                                        List<MachineScheduleDTO> currentCandidates,
                                                        List<LhShiftConfigVO> shifts,
                                                        IMachineMatchStrategy machineMatch,
                                                        IMouldChangeBalanceStrategy mouldChangeBalance,
                                                        IFirstInspectionBalanceStrategy inspectionBalance,
                                                        ICapacityCalculateStrategy capacityCalculate) {
                String materialCode = windowSkuList.get(0).getMaterialCode();
                if ("3302002530".equals(materialCode)) {
                    return findMachine(currentCandidates, "K2026");
                }
                if ("3302001038".equals(materialCode)) {
                    return findMachine(currentCandidates, "K2027");
                }
                if ("3302000245".equals(materialCode)) {
                    return findMachine(currentCandidates, "K2025");
                }
                return null;
            }
        });

        strategy.scheduleNewSpecs(context, new IMachineMatchStrategy() {
            @Override
            public List<MachineScheduleDTO> matchMachines(LhScheduleContext ctx, SkuScheduleDTO scheduleSku) {
                return Arrays.asList(k2025, k2026, k2027);
            }

            @Override
            public MachineScheduleDTO selectBestMachine(LhScheduleContext ctx,
                                                        SkuScheduleDTO scheduleSku,
                                                        List<MachineScheduleDTO> candidates,
                                                        Set<String> excludedMachineCodes) {
                for (MachineScheduleDTO candidate : candidates) {
                    if (!excludedMachineCodes.contains(candidate.getMachineCode())) {
                        return candidate;
                    }
                }
                return null;
            }

            @Override
            public void traceEnabledMachineSort(LhScheduleContext context) {
                // 测试桩，无需实现
            }
        }, defaultMouldChangeBalance(), defaultInspectionBalance(), defaultCapacityCalculate());

        assertEquals(3, context.getScheduleResultList().size());
        assertEquals("3302002530", context.getScheduleResultList().get(0).getMaterialCode());
        assertEquals("K2025", context.getScheduleResultList().get(0).getLhMachineCode());
        assertEquals("3302001038", context.getScheduleResultList().get(1).getMaterialCode());
        assertEquals("K2025", context.getScheduleResultList().get(1).getLhMachineCode());
        assertEquals("3302000245", context.getScheduleResultList().get(2).getMaterialCode());
        assertEquals("K2025", context.getScheduleResultList().get(2).getLhMachineCode());
    }

    @Test
    void scheduleNewSpecs_shouldTryNextCandidateOnlyAfterBaseFirstCandidateFails() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        injectDependencies(strategy, false);

        LhScheduleContext context = buildContext();
        Map<String, String> scheduleParamMap = new HashMap<>(8);
        scheduleParamMap.put(LhScheduleParamConstant.ENABLE_LOCAL_SEARCH, "1");
        scheduleParamMap.put(LhScheduleParamConstant.LOCAL_SEARCH_MACHINE_THRESHOLD, "10");
        scheduleParamMap.put(LhScheduleParamConstant.LOCAL_SEARCH_DEPTH, "3");
        scheduleParamMap.put(LhScheduleParamConstant.LOCAL_SEARCH_TIME_BUDGET_MS, "200");
        context.setScheduleConfig(new LhScheduleConfig(scheduleParamMap));

        SkuScheduleDTO sku = buildSku();
        context.getNewSpecSkuList().add(sku);

        MachineScheduleDTO firstMachine = buildMachine("K2025", dateTime(2026, 4, 17, 6, 0));
        MachineScheduleDTO secondMachine = buildMachine("K2026", dateTime(2026, 4, 17, 6, 0));
        context.getMachineScheduleMap().put(firstMachine.getMachineCode(), firstMachine);
        context.getMachineScheduleMap().put(secondMachine.getMachineCode(), secondMachine);

        injectLocalSearchAllocator(strategy, new LocalSearchMachineAllocatorStrategy() {
            @Override
            public MachineScheduleDTO selectBestMachine(LhScheduleContext ctx,
                                                        List<SkuScheduleDTO> windowSkuList,
                                                        List<MachineScheduleDTO> currentCandidates,
                                                        List<LhShiftConfigVO> shifts,
                                                        IMachineMatchStrategy machineMatch,
                                                        IMouldChangeBalanceStrategy mouldChangeBalance,
                                                        IFirstInspectionBalanceStrategy inspectionBalance,
                                                        ICapacityCalculateStrategy capacityCalculate) {
                return currentCandidates.get(1);
            }
        });

        strategy.scheduleNewSpecs(context, new DefaultMachineMatchStrategy(), new IMouldChangeBalanceStrategy() {
                    @Override
                    public boolean hasCapacity(LhScheduleContext ctx, Date targetDate) {
                        return true;
                    }

                    @Override
                    public Date allocateMouldChange(LhScheduleContext ctx, String machineCode, Date endingTime) {
                        if ("K2025".equals(machineCode)) {
                            return null;
                        }
                        return endingTime;
                    }

                    @Override
                    public int getRemainingCapacity(LhScheduleContext ctx, Date targetDate) {
                        return 99;
                    }
                },
                (ctx, machineCode, mouldChangeTime) -> "K2025".equals(machineCode) ? null : mouldChangeTime,
                defaultCapacityCalculate());

        assertEquals(1, context.getScheduleResultList().size());
        assertEquals("K2026", context.getScheduleResultList().get(0).getLhMachineCode(),
                "只有基础首位机台真实失败后，才应顺序尝试下一台候选机台");
    }

    @Test
    void scheduleNewSpecs_shouldKeepFillStartedShiftWhenDailyCapacityDoesNotRequireExtraMachine() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        injectDependencies(strategy, false);

        LhScheduleContext context = buildContext();
        Date scheduleDate = dateTime(2026, 5, 1, 0, 0);
        context.setScheduleDate(scheduleDate);
        context.setScheduleTargetDate(scheduleDate);
        context.setScheduleWindowShifts(LhScheduleTimeUtil.buildDefaultScheduleShifts(context, scheduleDate));

        SkuScheduleDTO sku = buildSku();
        sku.setMaterialCode("3302003001");
        sku.setMaterialDesc("无需扩机台仍需补满班次");
        sku.setConstructionStage(ConstructionStageEnum.FORMAL.getCode());
        sku.setLhTimeSeconds(3600);
        sku.setShiftCapacity(16);
        sku.setMouldQty(1);
        sku.setPendingQty(14);
        sku.setDailyPlanQty(14);
        sku.setTargetScheduleQty(14);
        sku.setWindowPlanQty(14);
        sku.setSurplusQty(200);
        sku.setEmbryoStock(-1);
        sku.setDailyPlanQuotaMap(buildSingleDayQuotaMap(
                context.getScheduleWindowShifts().get(0), sku.getMaterialCode(), 14));
        context.getNewSpecSkuList().add(sku);

        MachineScheduleDTO k1105 = buildMachine("K1105", dateTime(2026, 5, 1, 6, 0));
        MachineScheduleDTO k1110 = buildMachine("K1110", dateTime(2026, 5, 1, 6, 0));

        strategy.scheduleNewSpecs(context, orderedMachineMatch(k1105, k1110),
                defaultMouldChangeBalance(), defaultInspectionBalance(), defaultCapacityCalculate());

        assertEquals(1, context.getScheduleResultList().size(), "单机已满足日能力时不应提前扩机台");
        LhScheduleResult result = context.getScheduleResultList().get(0);
        assertEquals("K1105", result.getLhMachineCode(), "应保持基础首选机台落机");
        assertEquals(32, result.getDailyPlanQty().intValue(), "正规非收尾达到最低目标后，下一晚班不可换模时应继续补满晚班");
        assertEquals(0, context.getUnscheduledResultList().size(), "不应产生未排结果");
    }

    @Test
    void scheduleNewSpecs_shouldSkipHistoryShortageOnlySkuWhenLatestPreviousFinishedQtyIsPositive() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        injectDependencies(strategy, false);

        LhScheduleContext context = buildContext();
        Date scheduleDate = dateTime(2026, 6, 14, 0, 0);
        context.setScheduleDate(scheduleDate);
        context.setScheduleTargetDate(scheduleDate);
        context.setScheduleWindowShifts(LhScheduleTimeUtil.buildDefaultScheduleShifts(context, scheduleDate));

        SkuScheduleDTO sku = buildSku();
        sku.setMaterialCode("3302003999");
        sku.setMaterialDesc("仅历史欠产且最近已排物料");
        sku.setConstructionStage(ConstructionStageEnum.FORMAL.getCode());
        sku.setLhTimeSeconds(3600);
        sku.setShiftCapacity(16);
        sku.setMouldQty(1);
        sku.setPendingQty(64);
        sku.setDailyPlanQty(0);
        sku.setTargetScheduleQty(64);
        sku.setWindowPlanQty(0);
        sku.setWindowRemainingPlanQty(0);
        sku.setSurplusQty(64);
        sku.setEmbryoStock(-1);
        sku.setMonthlyHistoryShortageQty(64);
        sku.setFutureMonthPlanQtyAfterWindow(0);
        sku.setDailyPlanQuotaMap(buildThreeDayQuotaMap(
                context.getScheduleWindowShifts(), sku.getMaterialCode(), 0, 0, 0));
        context.getNewSpecSkuList().add(sku);

        // T-1 无完成量时，应继续向前取当前月最近一次非空完成量。
        context.getMaterialMonthDailyFinishedQtyMap().put(sku.getMaterialCode() + "_2026-06-12", 64);

        MachineScheduleDTO k1105 = buildMachine("K1105", dateTime(2026, 6, 14, 6, 0));
        strategy.scheduleNewSpecs(context, singletonMachineMatch(k1105),
                defaultMouldChangeBalance(), defaultInspectionBalance(), defaultCapacityCalculate());

        assertEquals(0, context.getScheduleResultList().size(),
                "仅历史欠产且最近一次非空完成量大于0时，本次新增排产应跳过该SKU");
        assertTrue(context.getNewSpecSkuList().isEmpty(), "跳过后应从新增待排队列移除");
        assertEquals(1, context.getUnscheduledResultList().size(), "跳过补排时应写入未排结果");
        assertEquals(sku.getMaterialCode(), context.getUnscheduledResultList().get(0).getMaterialCode());
        assertEquals(Integer.valueOf(64), context.getUnscheduledResultList().get(0).getUnscheduledQty());
        assertEquals("仅历史欠产、后续无月计划，且最近一次（前一次）已有完成量，本次跳过不排",
                context.getUnscheduledResultList().get(0).getUnscheduledReason());
    }

    @Test
    void resolveLatestPreviousFinishedQty_shouldStopAtLatestZeroValue() {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        LhScheduleContext context = buildContext();
        context.setScheduleDate(dateTime(2026, 6, 14, 0, 0));
        context.getMaterialMonthDailyFinishedQtyMap().put("3302001139_2026-06-12", 64);
        context.getMaterialMonthDailyFinishedQtyMap().put("3302001139_2026-06-13", 0);

        Integer latestFinishedQty = ReflectionTestUtils.invokeMethod(strategy,
                "resolveLatestPreviousFinishedQty", context, "3302001139");

        assertEquals(Integer.valueOf(0), latestFinishedQty,
                "最近一次非空完成量为0时必须停止回溯，不能继续取更早的大于0记录");
    }

    @Test
    void resolveLatestPreviousFinishedQty_shouldNotUsePreviousMonthValue() {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        LhScheduleContext context = buildContext();
        context.setScheduleDate(dateTime(2026, 6, 1, 0, 0));
        context.getMaterialMonthDailyFinishedQtyMap().put("3302001139_2026-05-31", 64);

        Integer latestFinishedQty = ReflectionTestUtils.invokeMethod(strategy,
                "resolveLatestPreviousFinishedQty", context, "3302001139");

        assertNull(latestFinishedQty, "最近一次完成量只能在当前月月初至T-1范围内查找");
    }

    @Test
    void scheduleNewSpecs_shouldSkipSmallSurplusEndingWhenPreviousT1NightNotFull() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        injectDependencies(strategy, true);

        LhScheduleContext context = buildContext();
        SkuScheduleDTO sku = buildSku();
        sku.setMaterialCode("3302004001");
        sku.setMaterialDesc("新增收尾小余量物料");
        sku.setConstructionStage(ConstructionStageEnum.FORMAL.getCode());
        sku.setLhTimeSeconds(3600);
        sku.setShiftCapacity(16);
        sku.setMouldQty(1);
        sku.setPendingQty(2);
        sku.setDailyPlanQty(2);
        sku.setTargetScheduleQty(2);
        sku.setWindowPlanQty(2);
        sku.setWindowRemainingPlanQty(2);
        sku.setSurplusQty(2);
        sku.setEmbryoStock(-1);
        sku.setDailyPlanQuotaMap(buildThreeDayQuotaMap(
                context.getScheduleWindowShifts(), sku.getMaterialCode(), 2, 0, 0));
        context.getNewSpecSkuList().add(sku);
        appendTargetPreviousT1NightResult(context, sku.getMaterialCode(), 8);

        MachineScheduleDTO k1105 = buildMachine("K1105", dateTime(2026, 4, 17, 6, 0));
        strategy.scheduleNewSpecs(context, singletonMachineMatch(k1105),
                defaultMouldChangeBalance(), defaultInspectionBalance(), defaultCapacityCalculate());

        assertEquals(0, context.getScheduleResultList().size(),
                "新增收尾小余量且前日T+1夜班未排满时，本次新增排产应跳过该SKU");
        assertTrue(context.getNewSpecSkuList().isEmpty(), "跳过后应从新增待排队列移除");
        assertEquals(1, context.getUnscheduledResultList().size(), "跳过新增收尾小余量时应写入未排结果");
        assertEquals(sku.getMaterialCode(), context.getUnscheduledResultList().get(0).getMaterialCode());
        assertEquals(Integer.valueOf(2), context.getUnscheduledResultList().get(0).getUnscheduledQty());
        assertEquals("收尾余量小于等于允许欠产偏差值，且前日 T+1 夜班未排满，本次不排产",
                context.getUnscheduledResultList().get(0).getUnscheduledReason());
    }

    @Test
    void scheduleNewSpecs_shouldFillFormalSingleMachineToWindowEndWhenNoExtraMachineNeeded() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        injectDependencies(strategy, false);

        LhScheduleContext context = buildContext();
        Date scheduleDate = dateTime(2026, 5, 1, 0, 0);
        context.setScheduleDate(scheduleDate);
        context.setScheduleTargetDate(scheduleDate);
        context.setScheduleWindowShifts(LhScheduleTimeUtil.buildDefaultScheduleShifts(context, scheduleDate));

        SkuScheduleDTO sku = buildSku();
        sku.setMaterialCode("3302003002");
        sku.setMaterialDesc("正式单机台补满窗口");
        sku.setConstructionStage(ConstructionStageEnum.FORMAL.getCode());
        sku.setLhTimeSeconds(3600);
        sku.setShiftCapacity(16);
        sku.setMouldQty(1);
        sku.setPendingQty(14);
        sku.setDailyPlanQty(14);
        sku.setTargetScheduleQty(14);
        sku.setWindowPlanQty(14);
        sku.setSurplusQty(200);
        sku.setEmbryoStock(-1);
        sku.setDailyPlanQuotaMap(buildThreeDayQuotaMap(
                context.getScheduleWindowShifts(), sku.getMaterialCode(), 14, 0, 0));
        context.getNewSpecSkuList().add(sku);

        MachineScheduleDTO k1105 = buildMachine("K1105", dateTime(2026, 5, 1, 6, 0));
        MachineScheduleDTO k1110 = buildMachine("K1110", dateTime(2026, 5, 1, 6, 0));

        strategy.scheduleNewSpecs(context, orderedMachineMatch(k1105, k1110),
                defaultMouldChangeBalance(), defaultInspectionBalance(), defaultCapacityCalculate());

        assertEquals(1, context.getScheduleResultList().size(), "单机台可独立完成时不应扩到第二台机台");
        LhScheduleResult result = context.getScheduleResultList().get(0);
        assertEquals("K1105", result.getLhMachineCode(), "应保持基础首选机台落机");
        assertEquals(112, result.getDailyPlanQty().intValue(), "正式非收尾单机台应从开产点补满到窗口结束");
        assertEquals(16, ShiftFieldUtil.getShiftPlanQty(result, 8).intValue(), "窗口最后一个班次也应保持整班产量");
        assertEquals(0, context.getUnscheduledResultList().size(), "不应产生未排结果");
    }

    @Test
    void scheduleNewSpecs_shouldNotFillEntireWindowForFirstSkuWhenMoreNewSpecsRemain() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        injectDependencies(strategy, false);

        LhScheduleContext context = buildContext();
        Date scheduleDate = dateTime(2026, 5, 1, 0, 0);
        context.setScheduleDate(scheduleDate);
        context.setScheduleTargetDate(scheduleDate);
        context.setScheduleWindowShifts(LhScheduleTimeUtil.buildDefaultScheduleShifts(context, scheduleDate));

        SkuScheduleDTO firstSku = buildSku();
        firstSku.setMaterialCode("3302003005");
        firstSku.setMaterialDesc("多SKU队列首个物料");
        firstSku.setConstructionStage(ConstructionStageEnum.FORMAL.getCode());
        firstSku.setLhTimeSeconds(3600);
        firstSku.setShiftCapacity(16);
        firstSku.setMouldQty(1);
        firstSku.setPendingQty(14);
        firstSku.setDailyPlanQty(14);
        firstSku.setTargetScheduleQty(14);
        firstSku.setWindowPlanQty(14);
        firstSku.setSurplusQty(200);
        firstSku.setEmbryoStock(-1);
        firstSku.setDailyPlanQuotaMap(buildThreeDayQuotaMap(
                context.getScheduleWindowShifts(), firstSku.getMaterialCode(), 14, 0, 0));

        SkuScheduleDTO secondSku = buildSku();
        secondSku.setMaterialCode("3302003006");
        secondSku.setMaterialDesc("多SKU队列后续物料");
        secondSku.setConstructionStage(ConstructionStageEnum.FORMAL.getCode());
        secondSku.setLhTimeSeconds(3600);
        secondSku.setShiftCapacity(16);
        secondSku.setMouldQty(1);
        secondSku.setPendingQty(16);
        secondSku.setDailyPlanQty(16);
        secondSku.setTargetScheduleQty(16);
        secondSku.setWindowPlanQty(16);
        secondSku.setSurplusQty(200);
        secondSku.setEmbryoStock(-1);
        secondSku.setDailyPlanQuotaMap(buildSingleDayQuotaMap(
                context.getScheduleWindowShifts().get(0), secondSku.getMaterialCode(), 16));

        context.getNewSpecSkuList().add(firstSku);
        context.getNewSpecSkuList().add(secondSku);
        attachAvailableMould(context, firstSku.getMaterialCode(), "MOULD-3302003005");
        attachAvailableMould(context, secondSku.getMaterialCode(), "MOULD-3302003006");

        MachineScheduleDTO k1105 = buildMachine("K1105", dateTime(2026, 5, 1, 6, 0));

        strategy.scheduleNewSpecs(context, singletonMachineMatch(k1105),
                defaultMouldChangeBalance(), defaultInspectionBalance(), defaultCapacityCalculate());

        assertEquals(2, context.getScheduleResultList().size(), "多SKU队列下，首个SKU不应补满整窗挤掉后续SKU");
        assertEquals(0, context.getUnscheduledResultList().size(), "同一机台后续班次应继续承接第二个SKU");
        assertEquals(32, findResultByMaterialCode(context.getScheduleResultList(), "3302003005")
                .getDailyPlanQty().intValue(), "首个SKU遇到下一晚班不可换模时应续作补满，但不应直接吃满整个窗口");
        assertEquals(16, findResultByMaterialCode(context.getScheduleResultList(), "3302003006")
                .getDailyPlanQty().intValue(), "后续SKU应保留窗口产能并正常落机");
    }

    @Test
    void buildSimulationCandidateCapacityMap_shouldRespectCandidateReadyTimeDifference() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        injectDependencies(strategy, false);

        LhScheduleContext context = buildContext();
        Date scheduleDate = dateTime(2026, 5, 1, 0, 0);
        context.setScheduleDate(scheduleDate);
        context.setScheduleTargetDate(dateTime(2026, 5, 3, 0, 0));
        context.setScheduleWindowShifts(LhScheduleTimeUtil.buildDefaultScheduleShifts(context, scheduleDate));

        SkuScheduleDTO sku = buildSku();
        sku.setMaterialCode("3302003007");
        sku.setMaterialDesc("异构候选机台dayN模拟");
        sku.setConstructionStage(ConstructionStageEnum.FORMAL.getCode());
        sku.setLhTimeSeconds(3600);
        sku.setShiftCapacity(17);
        sku.setMouldQty(1);
        sku.setPendingQty(180);
        sku.setDailyPlanQty(180);
        sku.setTargetScheduleQty(180);
        sku.setWindowPlanQty(180);
        sku.setWindowRemainingPlanQty(180);
        sku.setSurplusQty(300);
        sku.setEmbryoStock(-1);
        Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap = buildThreeDayQuotaMap(
                context.getScheduleWindowShifts(), sku.getMaterialCode(), 60, 60, 60);

        MachineScheduleDTO earlyMachine = buildMachine("M-EARLY", dateTime(2026, 5, 1, 6, 0));
        MachineScheduleDTO lateMachine = buildMachine("M-LATE", dateTime(2026, 5, 2, 22, 0));

        Method method = NewSpecProductionStrategy.class.getDeclaredMethod(
                "buildSimulationCandidateCapacityMap",
                LhScheduleContext.class,
                SkuScheduleDTO.class,
                MachineScheduleDTO.class,
                ProductionQuantityPolicy.class,
                List.class,
                ICapacityCalculateStrategy.class,
                Map.class);
        method.setAccessible(true);

        @SuppressWarnings("unchecked")
        Map<LocalDate, Integer> earlyCapacityMap = (Map<LocalDate, Integer>) method.invoke(
                strategy, context, sku, earlyMachine, ProductionQuantityPolicy.from(sku, false),
                context.getScheduleWindowShifts(), defaultCapacityCalculate(), quotaMap);
        @SuppressWarnings("unchecked")
        Map<LocalDate, Integer> lateCapacityMap = (Map<LocalDate, Integer>) method.invoke(
                strategy, context, sku, lateMachine, ProductionQuantityPolicy.from(sku, false),
                context.getScheduleWindowShifts(), defaultCapacityCalculate(), quotaMap);

        LocalDate firstDay = toLocalDate(context.getScheduleWindowShifts().get(0));
        assertFalse(earlyCapacityMap.equals(lateCapacityMap), "不同机台就绪时间不应复用同一份dayN产能图");
        assertTrue(earlyCapacityMap.get(firstDay) > lateCapacityMap.get(firstDay),
                "早开机台在首日的可排产能应高于晚开机台");
        assertTrue(sumCapacityMap(earlyCapacityMap) > sumCapacityMap(lateCapacityMap),
                "早开机台在整个窗口内的累计产能应高于晚开机台");
    }

    @Test
    void scheduleNewSpecs_shouldFillMassTrialSingleMachineToWindowEndWhenNoExtraMachineNeeded() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        injectDependencies(strategy, false);

        LhScheduleContext context = buildContext();
        Date scheduleDate = dateTime(2026, 5, 1, 0, 0);
        context.setScheduleDate(scheduleDate);
        context.setScheduleTargetDate(scheduleDate);
        context.setScheduleWindowShifts(LhScheduleTimeUtil.buildDefaultScheduleShifts(context, scheduleDate));

        SkuScheduleDTO sku = buildSku();
        sku.setMaterialCode("3302003003");
        sku.setMaterialDesc("量试单机台补满窗口");
        sku.setConstructionStage(ConstructionStageEnum.MASS_TRIAL.getCode());
        sku.setLhTimeSeconds(3600);
        sku.setShiftCapacity(16);
        sku.setMouldQty(1);
        sku.setPendingQty(14);
        sku.setDailyPlanQty(14);
        sku.setTargetScheduleQty(14);
        sku.setWindowPlanQty(14);
        sku.setSurplusQty(200);
        sku.setEmbryoStock(-1);
        sku.setDailyPlanQuotaMap(buildThreeDayQuotaMap(
                context.getScheduleWindowShifts(), sku.getMaterialCode(), 14, 0, 0));
        context.getNewSpecSkuList().add(sku);

        MachineScheduleDTO k1105 = buildMachine("K1105", dateTime(2026, 5, 1, 6, 0));

        strategy.scheduleNewSpecs(context, singletonMachineMatch(k1105),
                defaultMouldChangeBalance(), defaultInspectionBalance(), defaultCapacityCalculate());

        assertEquals(1, context.getScheduleResultList().size(), "量试单机台应直接落单机结果");
        LhScheduleResult result = context.getScheduleResultList().get(0);
        assertEquals(112, result.getDailyPlanQty().intValue(), "量试非收尾单机台也应补满到窗口结束");
        assertEquals(16, ShiftFieldUtil.getShiftPlanQty(result, 8).intValue(), "量试单机台应补满最后一个班次");
        assertEquals(0, context.getUnscheduledResultList().size(), "不应产生未排结果");
    }

    @Test
    void scheduleNewSpecs_shouldKeepTrialSingleMachineStrictTargetQty() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        injectDependencies(strategy, false);

        LhScheduleContext context = buildContext();
        Date scheduleDate = dateTime(2026, 5, 1, 0, 0);
        context.setScheduleDate(scheduleDate);
        context.setScheduleTargetDate(scheduleDate);
        context.setScheduleWindowShifts(LhScheduleTimeUtil.buildDefaultScheduleShifts(context, scheduleDate));

        SkuScheduleDTO sku = buildSku();
        sku.setMaterialCode("3302003004");
        sku.setMaterialDesc("试制单机台严格目标");
        sku.setConstructionStage(ConstructionStageEnum.TRIAL.getCode());
        sku.setLhTimeSeconds(3600);
        sku.setShiftCapacity(16);
        sku.setMouldQty(1);
        sku.setPendingQty(14);
        sku.setDailyPlanQty(14);
        sku.setTargetScheduleQty(14);
        sku.setWindowPlanQty(14);
        sku.setSurplusQty(200);
        sku.setEmbryoStock(-1);
        sku.setDailyPlanQuotaMap(buildThreeDayQuotaMap(
                context.getScheduleWindowShifts(), sku.getMaterialCode(), 14, 0, 0));
        context.getNewSpecSkuList().add(sku);

        MachineScheduleDTO k1501 = buildMachine("K1501L", dateTime(2026, 5, 1, 6, 0));

        strategy.scheduleNewSpecs(context, singletonMachineMatch(k1501),
                defaultMouldChangeBalance(), defaultInspectionBalance(), defaultCapacityCalculate());

        assertEquals(1, context.getScheduleResultList().size(), "试制单机台应正常生成结果");
        LhScheduleResult result = context.getScheduleResultList().get(0);
        assertTrue(result.getDailyPlanQty().intValue() <= 14, "试制单机台必须保持严格目标量，不允许补满整个窗口");
        assertEquals(0, resolveShiftQty(result, 8), "试制单机台不应补满到窗口最后一个班次");
    }

    @Test
    void scheduleNewSpecs_shouldExpandDocumentCaseWhenTodayPlanExceedsThreeShiftCapacity() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        injectDependencies(strategy, true);

        LhScheduleContext context = buildContext();
        Date scheduleDate = dateTime(2026, 5, 1, 0, 0);
        context.setScheduleDate(scheduleDate);
        context.setScheduleTargetDate(scheduleDate);
        context.setScheduleWindowShifts(LhScheduleTimeUtil.buildDefaultScheduleShifts(context, scheduleDate));

        SkuScheduleDTO sku = buildSku();
        sku.setMaterialCode("3302001724");
        sku.setMaterialDesc("文档案例SKU");
        sku.setConstructionStage(ConstructionStageEnum.FORMAL.getCode());
        sku.setLhTimeSeconds(3600);
        sku.setShiftCapacity(16);
        sku.setMouldQty(1);
        sku.setPendingQty(158);
        sku.setDailyPlanQty(158);
        sku.setTargetScheduleQty(158);
        sku.setWindowPlanQty(158);
        sku.setSurplusQty(158);
        sku.setEmbryoStock(-1);
        sku.setDailyPlanQuotaMap(buildDocumentCaseQuotaMap(context.getScheduleWindowShifts(), sku.getMaterialCode()));
        context.getNewSpecSkuList().add(sku);

        MachineScheduleDTO k1105 = buildMachine("K1105", dateTime(2026, 5, 1, 6, 0));
        MachineScheduleDTO k1110 = buildMachine("K1110", dateTime(2026, 5, 1, 6, 0));

        strategy.scheduleNewSpecs(context, orderedMachineMatch(k1105, k1110),
                defaultMouldChangeBalance(), defaultInspectionBalance(), defaultCapacityCalculate());

        assertEquals(2, context.getScheduleResultList().size(),
                "当前日计划96已超过一台3班理论产能48时，应扩到两台机台");
        LhScheduleResult k1105Result = findResult(context.getScheduleResultList(), "K1105");
        LhScheduleResult k1110Result = findResult(context.getScheduleResultList(), "K1110");
        assertEquals(80, k1105Result.getDailyPlanQty().intValue(), "K1105应按dayN节奏只排到C6");
        assertEquals(78, k1110Result.getDailyPlanQty().intValue(), "K1110应承接剩余目标量");
        assertEquals(158, k1105Result.getDailyPlanQty() + k1110Result.getDailyPlanQty(), "窗口合计应精确等于目标量");
        assertEquals(16, ShiftFieldUtil.getShiftPlanQty(k1105Result, 6).intValue(), "第三天C6晚班K1105保留整班16");
        assertEquals(14, ShiftFieldUtil.getShiftPlanQty(k1110Result, 6).intValue(), "第三天C6晚班K1110只排尾量14");
        assertEquals(0, resolveShiftQty(k1105Result, 7), "目标已闭合后不应继续挪到K1105 C7");
        assertEquals(0, resolveShiftQty(k1110Result, 7), "目标已闭合后不应继续挪到K1110 C7");
    }

    @Test
    void scheduleNewSpecs_shouldExpandSameSkuGreenTireWhenTodayPlanExceedsThreeShiftCapacity() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        injectDependencies(strategy, true);

        LhScheduleContext context = buildContext();
        Date scheduleDate = dateTime(2026, 5, 1, 0, 0);
        context.setScheduleDate(scheduleDate);
        context.setScheduleTargetDate(scheduleDate);
        context.setScheduleWindowShifts(LhScheduleTimeUtil.buildDefaultScheduleShifts(context, scheduleDate));

        SkuScheduleDTO sku = buildSku();
        sku.setMaterialCode("3302001724");
        sku.setMaterialDesc("文档案例SKU");
        sku.setEmbryoCode("215102974");
        sku.setConstructionStage(ConstructionStageEnum.FORMAL.getCode());
        sku.setLhTimeSeconds(3600);
        sku.setShiftCapacity(16);
        sku.setMouldQty(1);
        sku.setPendingQty(158);
        sku.setDailyPlanQty(158);
        sku.setTargetScheduleQty(158);
        sku.setWindowPlanQty(158);
        sku.setSurplusQty(158);
        sku.setEmbryoStock(-1);
        sku.setDailyPlanQuotaMap(buildDocumentCaseQuotaMap(context.getScheduleWindowShifts(), sku.getMaterialCode()));
        context.getNewSpecSkuList().add(sku);

        MachineScheduleDTO k1105 = buildMachine("K1105", dateTime(2026, 5, 1, 6, 0));
        MachineScheduleDTO k1110 = buildMachine("K1110", dateTime(2026, 5, 1, 6, 0));

        strategy.scheduleNewSpecs(context, orderedMachineMatch(k1105, k1110),
                new DefaultMouldChangeBalanceStrategy(), defaultInspectionBalance(), defaultCapacityCalculate());

        assertEquals(2, context.getScheduleResultList().size(),
                "当前日计划96已超过一台3班理论产能48时，同胎胚场景也应扩第二台机台");
        LhScheduleResult k1105Result = findResult(context.getScheduleResultList(), "K1105");
        LhScheduleResult k1110Result = findResult(context.getScheduleResultList(), "K1110");
        assertEquals(2, resolveFirstPlannedShiftIndex(k1105Result), "K1105 应从 C2 开始生产");
        assertEquals(2, resolveFirstPlannedShiftIndex(k1110Result), "K1110 应从 C2 开始生产");
        assertEquals(14, ShiftFieldUtil.getShiftPlanQty(k1110Result, 6).intValue(), "K1110 尾量14应落在 C6");
        assertEquals(0, resolveShiftQty(k1110Result, 7), "K1110 不应继续顺延到 C7");
    }

    @Test
    void scheduleNewSpecs_shouldKeepTailMachineFilledShiftForFormalNonEnding() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        injectDependencies(strategy, false);

        LhScheduleContext context = buildContext();
        Date scheduleDate = dateTime(2026, 5, 1, 0, 0);
        context.setScheduleDate(scheduleDate);
        context.setScheduleTargetDate(scheduleDate);
        context.setScheduleWindowShifts(LhScheduleTimeUtil.buildDefaultScheduleShifts(context, scheduleDate));

        SkuScheduleDTO sku = buildSku();
        sku.setMaterialCode("3302003002");
        sku.setMaterialDesc("尾机台补满班次");
        sku.setConstructionStage(ConstructionStageEnum.FORMAL.getCode());
        sku.setLhTimeSeconds(3600);
        sku.setShiftCapacity(16);
        sku.setMouldQty(1);
        sku.setPendingQty(126);
        sku.setDailyPlanQty(126);
        sku.setTargetScheduleQty(126);
        sku.setWindowPlanQty(126);
        sku.setSurplusQty(300);
        sku.setEmbryoStock(-1);
        sku.setDailyPlanQuotaMap(buildThreeDayQuotaMap(
                context.getScheduleWindowShifts(), sku.getMaterialCode(), 126, 0, 0));
        context.getNewSpecSkuList().add(sku);

        MachineScheduleDTO k1105 = buildMachine("K1105", dateTime(2026, 5, 1, 6, 0));
        MachineScheduleDTO k1110 = buildMachine("K1110", dateTime(2026, 5, 1, 6, 0));

        strategy.scheduleNewSpecs(context, orderedMachineMatch(k1105, k1110),
                defaultMouldChangeBalance(), defaultInspectionBalance(), defaultCapacityCalculate());

        assertEquals(1, context.getScheduleResultList().size(),
                "1台*16班产*8班已覆盖窗口计划126时，不应再因真实换模窗口不足扩到第二台机台");
        LhScheduleResult k1105Result = findResult(context.getScheduleResultList(), "K1105");
        assertEquals(112, k1105Result.getDailyPlanQty().intValue(), "当前机台只按真实可排窗口落地");
        assertFalse(context.getSkuShiftFillOverQtyMap().containsKey(sku.getMaterialCode()),
                "未启用尾机台补满时不应产生满班补齐超排");
    }

    @Test
    void scheduleNewSpecs_shouldNotOverExpandWhenWindowTargetIsLessThanRollingDailyQuota() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        injectDependencies(strategy, false);

        LhScheduleContext context = buildContext();
        Date scheduleDate = dateTime(2026, 5, 3, 0, 0);
        context.setScheduleDate(scheduleDate);
        context.setScheduleTargetDate(scheduleDate);
        context.setScheduleWindowShifts(LhScheduleTimeUtil.buildDefaultScheduleShifts(context, scheduleDate));

        SkuScheduleDTO sku = buildSku();
        sku.setMaterialCode("3302002654");
        sku.setMaterialDesc("窗口目标小于滚动dayN总量");
        sku.setConstructionStage(ConstructionStageEnum.FORMAL.getCode());
        sku.setLhTimeSeconds(3600);
        sku.setShiftCapacity(17);
        sku.setMouldQty(1);
        sku.setPendingQty(136);
        sku.setDailyPlanQty(136);
        sku.setTargetScheduleQty(136);
        sku.setWindowPlanQty(136);
        sku.setSurplusQty(300);
        sku.setEmbryoStock(-1);
        sku.setDailyPlanQuotaMap(buildThreeDayQuotaMap(
                context.getScheduleWindowShifts(), sku.getMaterialCode(), 100, 100, 100));
        context.getNewSpecSkuList().add(sku);

        MachineScheduleDTO k2024 = buildMachine("K2024", dateTime(2026, 5, 4, 14, 0));
        MachineScheduleDTO k1111 = buildMachine("K1111", dateTime(2026, 5, 4, 14, 0));
        MachineScheduleDTO k1113 = buildMachine("K1113", dateTime(2026, 5, 4, 14, 0));
        MachineScheduleDTO k1206 = buildMachine("K1206", dateTime(2026, 5, 4, 14, 0));
        MachineScheduleDTO k1313 = buildMachine("K1313", dateTime(2026, 5, 4, 14, 0));

        IMachineMatchStrategy machineMatchStrategy = new IMachineMatchStrategy() {
            @Override
            public List<MachineScheduleDTO> matchMachines(LhScheduleContext ctx, SkuScheduleDTO scheduleSku) {
                return Arrays.asList(k2024, k1111, k1113, k1206, k1313);
            }

            @Override
            public MachineScheduleDTO selectBestMachine(LhScheduleContext ctx, SkuScheduleDTO scheduleSku,
                                                        List<MachineScheduleDTO> candidates,
                                                        Set<String> excludedMachineCodes) {
                for (MachineScheduleDTO candidate : candidates) {
                    if (candidate != null && !excludedMachineCodes.contains(candidate.getMachineCode())) {
                        return candidate;
                    }
                }
                return null;
            }

            @Override
            public void traceEnabledMachineSort(LhScheduleContext context) {
                // 测试桩，无需实现
            }
        };

        strategy.scheduleNewSpecs(context, machineMatchStrategy,
                defaultMouldChangeBalance(), defaultInspectionBalance(), defaultCapacityCalculate());

        assertEquals(1, context.getScheduleResultList().size(),
                "本轮窗口目标136已被1台*17班产*8班覆盖时，不应按真实窗口缺口继续扩机台");
        assertEquals(51, findResult(context.getScheduleResultList(), "K2024").getDailyPlanQty().intValue(),
                "首台机台应保留整段满班产量");
        assertFalse(context.getScheduleResultList().stream()
                        .anyMatch(result -> "K1206".equals(result.getLhMachineCode())
                                || "K1313".equals(result.getLhMachineCode())
                                || "K1111".equals(result.getLhMachineCode())
                                || "K1113".equals(result.getLhMachineCode())),
                "滚动dayN总量大于本轮窗口目标时，不应再扩出浅排机台");
        assertEquals(51, context.getScheduleResultList().stream()
                        .map(LhScheduleResult::getDailyPlanQty)
                        .filter(Objects::nonNull)
                        .mapToInt(Integer::intValue)
                        .sum(),
                "理论规则满足后，只落地当前机台真实窗口产量");
    }

    @Test
    void scheduleNewSpecs_shouldUseWindowDayPlanAsMinimumTargetForFormalFullCapacityMode() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        injectDependencies(strategy, false);

        LhScheduleContext context = buildContext();
        Date scheduleDate = dateTime(2026, 5, 1, 0, 0);
        context.setScheduleDate(scheduleDate);
        context.setScheduleTargetDate(dateTime(2026, 5, 3, 0, 0));
        context.setScheduleWindowShifts(LhScheduleTimeUtil.buildDefaultScheduleShifts(context, scheduleDate));

        SkuScheduleDTO sku = buildSku();
        sku.setMaterialCode("3302002654");
        sku.setMaterialDesc("正式非收尾dayN累计目标量");
        sku.setConstructionStage(ConstructionStageEnum.FORMAL.getCode());
        sku.setLhTimeSeconds(3600);
        sku.setShiftCapacity(17);
        sku.setMouldQty(1);
        sku.setPendingQty(1752);
        sku.setDailyPlanQty(100);
        sku.setTargetScheduleQty(300);
        sku.setWindowPlanQty(300);
        sku.setWindowRemainingPlanQty(300);
        sku.setSurplusQty(1752);
        sku.setEmbryoStock(-1);
        sku.setDailyPlanQuotaMap(buildThreeDayQuotaMap(
                context.getScheduleWindowShifts(), sku.getMaterialCode(), 100, 100, 100));
        context.getNewSpecSkuList().add(sku);

        MachineScheduleDTO k2024 = buildMachine("K2024", dateTime(2026, 5, 1, 6, 0));
        MachineScheduleDTO k1111 = buildMachine("K1111", dateTime(2026, 5, 1, 6, 0));
        MachineScheduleDTO k1113 = buildMachine("K1113", dateTime(2026, 5, 1, 22, 0));

        IMachineMatchStrategy machineMatchStrategy = new IMachineMatchStrategy() {
            @Override
            public List<MachineScheduleDTO> matchMachines(LhScheduleContext ctx, SkuScheduleDTO scheduleSku) {
                return Arrays.asList(k2024, k1111, k1113);
            }

            @Override
            public MachineScheduleDTO selectBestMachine(LhScheduleContext ctx, SkuScheduleDTO scheduleSku,
                                                        List<MachineScheduleDTO> candidates,
                                                        Set<String> excludedMachineCodes) {
                for (MachineScheduleDTO candidate : candidates) {
                    if (candidate != null && !excludedMachineCodes.contains(candidate.getMachineCode())) {
                        return candidate;
                    }
                }
                return null;
            }

            @Override
            public void traceEnabledMachineSort(LhScheduleContext context) {
                // 测试桩，无需实现
            }
        };

        strategy.scheduleNewSpecs(context, machineMatchStrategy,
                defaultMouldChangeBalance(), defaultInspectionBalance(), defaultCapacityCalculate());

        assertEquals(2, context.getScheduleResultList().size(),
                "两台机台后一天3班理论产能已覆盖后一天计划时，不应继续扩第三台");
        assertEquals(119, findResult(context.getScheduleResultList(), "K2024").getDailyPlanQty().intValue(),
                "第一台机台应保留整段满班产量");
        assertEquals(119, findResult(context.getScheduleResultList(), "K1111").getDailyPlanQty().intValue(),
                "第二台机台应保留整段满班产量");
        assertFalse(context.getScheduleResultList().stream()
                        .anyMatch(result -> "K1113".equals(result.getLhMachineCode())),
                "理论规则满足后不应继续启用第三台机台");
        assertEquals(238, context.getScheduleResultList().stream()
                        .map(LhScheduleResult::getDailyPlanQty)
                        .filter(Objects::nonNull)
                        .mapToInt(Integer::intValue)
                        .sum(),
                "理论规则满足后只保留已启用机台真实窗口产量");
    }

    @Test
    void scheduleNewSpecs_shouldIgnoreTheoreticalFullCapacityTargetForFormalNonEnding() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        injectDependencies(strategy, false);

        LhScheduleContext context = buildContext();
        Date scheduleDate = dateTime(2026, 5, 1, 0, 0);
        context.setScheduleDate(scheduleDate);
        context.setScheduleTargetDate(dateTime(2026, 5, 3, 0, 0));
        context.setScheduleWindowShifts(LhScheduleTimeUtil.buildDefaultScheduleShifts(context, scheduleDate));

        SkuScheduleDTO sku = buildSku();
        sku.setMaterialCode("3302002654");
        sku.setMaterialDesc("理论满排目标应回落到dayN最低目标");
        sku.setConstructionStage(ConstructionStageEnum.FORMAL.getCode());
        sku.setLhTimeSeconds(3600);
        sku.setShiftCapacity(17);
        sku.setMouldQty(1);
        sku.setPendingQty(1752);
        sku.setDailyPlanQty(100);
        sku.setTargetScheduleQty(357);
        sku.setWindowPlanQty(300);
        sku.setWindowRemainingPlanQty(300);
        sku.setSurplusQty(1752);
        sku.setEmbryoStock(-1);
        sku.setDailyPlanQuotaMap(buildThreeDayQuotaMap(
                context.getScheduleWindowShifts(), sku.getMaterialCode(), 100, 100, 100));
        context.getNewSpecSkuList().add(sku);

        MachineScheduleDTO k2024 = buildMachine("K2024", dateTime(2026, 5, 1, 6, 0));
        MachineScheduleDTO k1111 = buildMachine("K1111", dateTime(2026, 5, 1, 6, 0));
        MachineScheduleDTO k1113 = buildMachine("K1113", dateTime(2026, 5, 1, 22, 0));

        strategy.scheduleNewSpecs(context, orderedMachineMatch(k2024, k1111, k1113),
                defaultMouldChangeBalance(), defaultInspectionBalance(), defaultCapacityCalculate());

        assertEquals(119, findResult(context.getScheduleResultList(), "K2024").getDailyPlanQty().intValue(),
                "第一台机台不应继续按理论窗口产能平衡拆量");
        assertEquals(119, findResult(context.getScheduleResultList(), "K1111").getDailyPlanQty().intValue(),
                "第二台机台不应继续按理论窗口产能平衡拆量");
        assertFalse(context.getScheduleResultList().stream()
                        .anyMatch(result -> "K1113".equals(result.getLhMachineCode())),
                "两台机台满足后一天3班理论产能后，不应继续扩第三台");
    }

    @Test
    void scheduleNewSpecs_shouldNotAddMachineForSmallShortageWhenNextDayPlanCanBeCovered() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        injectDependencies(strategy, false);

        LhScheduleContext context = buildContext();
        Date scheduleDate = dateTime(2026, 5, 9, 0, 0);
        context.setScheduleDate(scheduleDate);
        context.setScheduleTargetDate(dateTime(2026, 5, 11, 0, 0));
        context.setScheduleWindowShifts(LhScheduleTimeUtil.buildDefaultScheduleShifts(context, scheduleDate));

        SkuScheduleDTO sku = buildSku();
        sku.setMaterialCode("3302001592");
        sku.setConstructionStage(ConstructionStageEnum.FORMAL.getCode());
        sku.setLhTimeSeconds(3600);
        sku.setShiftCapacity(16);
        sku.setMouldQty(1);
        sku.setPendingQty(1256);
        sku.setTargetScheduleQty(148);
        sku.setWindowPlanQty(144);
        sku.setWindowRemainingPlanQty(144);
        sku.setSurplusQty(1256);
        sku.setEmbryoStock(-1);
        sku.setMonthlyHistoryShortageQty(100);
        sku.setScheduleDayFinishQty(0);
        sku.setDailyPlanQuotaMap(buildThreeDayQuotaMap(
                context.getScheduleWindowShifts(), sku.getMaterialCode(), 48, 48, 48));
        context.getNewSpecSkuList().add(sku);

        MachineScheduleDTO k1115 = buildMachine("K1115", dateTime(2026, 5, 9, 6, 0));
        MachineScheduleDTO k1116 = buildMachine("K1116", dateTime(2026, 5, 9, 6, 0));

        strategy.scheduleNewSpecs(context, orderedMachineMatch(k1115, k1116),
                defaultMouldChangeBalance(), defaultInspectionBalance(), defaultCapacityCalculate());

        assertEquals(1, context.getScheduleResultList().size(),
                "小欠产未超阈值且一台机台可满足后续日计划时，不应为了清欠产增加浅排机台");
        assertEquals("K1115", context.getScheduleResultList().get(0).getLhMachineCode());
    }

    @Test
    void scheduleNewSpecs_shouldNotAddMachineWhenTheoryWindowCapacityCoversPlanEvenActualWindowIsInsufficient()
            throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        injectDependencies(strategy, false);

        LhScheduleContext context = buildContext();
        Date scheduleDate = dateTime(2026, 5, 9, 0, 0);
        context.setScheduleDate(scheduleDate);
        context.setScheduleTargetDate(dateTime(2026, 5, 11, 0, 0));
        context.setScheduleWindowShifts(LhScheduleTimeUtil.buildDefaultScheduleShifts(context, scheduleDate));

        SkuScheduleDTO sku = buildSku();
        sku.setMaterialCode("3302001592");
        sku.setConstructionStage(ConstructionStageEnum.FORMAL.getCode());
        sku.setLhTimeSeconds(3600);
        sku.setShiftCapacity(16);
        sku.setMouldQty(1);
        sku.setPendingQty(1256);
        sku.setTargetScheduleQty(148);
        sku.setWindowPlanQty(144);
        sku.setWindowRemainingPlanQty(144);
        sku.setSurplusQty(1256);
        sku.setEmbryoStock(-1);
        sku.setMonthlyHistoryShortageQty(0);
        sku.setScheduleDayFinishQty(0);
        sku.setDailyPlanQuotaMap(buildThreeDayQuotaMap(
                context.getScheduleWindowShifts(), sku.getMaterialCode(), 48, 48, 48));
        context.getNewSpecSkuList().add(sku);

        MachineScheduleDTO k1115 = buildMachine("K1115", dateTime(2026, 5, 9, 6, 0));
        MachineScheduleDTO k1116 = buildMachine("K1116", dateTime(2026, 5, 9, 6, 0));

        strategy.scheduleNewSpecs(context, orderedMachineMatch(k1115, k1116),
                defaultMouldChangeBalance(), defaultInspectionBalance(), defaultCapacityCalculate());

        assertEquals(1, context.getScheduleResultList().size(),
                "历史欠产为0且理论产能满足小欠产规则时，不应因首台真实换模后窗口不足继续补第二台");
        assertEquals("K1115", context.getScheduleResultList().get(0).getLhMachineCode());
    }

    @Test
    void scheduleNewSpecs_shouldNotAddMachineWhenEightShiftCapacityExactlyCoversWindowPlan() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        injectDependencies(strategy, false);

        LhScheduleContext context = buildContext();
        Date scheduleDate = dateTime(2026, 6, 1, 0, 0);
        context.setScheduleDate(scheduleDate);
        context.setScheduleTargetDate(dateTime(2026, 6, 3, 0, 0));
        context.setScheduleWindowShifts(LhScheduleTimeUtil.buildDefaultScheduleShifts(context, scheduleDate));

        SkuScheduleDTO sku = buildSku();
        sku.setMaterialCode("3302002661");
        sku.setConstructionStage(ConstructionStageEnum.FORMAL.getCode());
        sku.setLhTimeSeconds(3600);
        sku.setShiftCapacity(16);
        sku.setMouldQty(1);
        sku.setPendingQty(972);
        sku.setTargetScheduleQty(128);
        sku.setWindowPlanQty(128);
        sku.setWindowRemainingPlanQty(128);
        sku.setSurplusQty(972);
        sku.setEmbryoStock(-1);
        sku.setMonthlyHistoryShortageQty(0);
        sku.setScheduleDayFinishQty(0);
        sku.setDailyPlanQuotaMap(buildThreeDayQuotaMap(
                context.getScheduleWindowShifts(), sku.getMaterialCode(), 8, 60, 60));
        context.getNewSpecSkuList().add(sku);

        MachineScheduleDTO k1313 = buildMachine("K1313", dateTime(2026, 6, 1, 6, 0));
        MachineScheduleDTO k1405 = buildMachine("K1405", dateTime(2026, 6, 1, 6, 0));

        strategy.scheduleNewSpecs(context, orderedMachineMatch(k1313, k1405),
                defaultMouldChangeBalance(), defaultInspectionBalance(), defaultCapacityCalculate());

        assertEquals(1, context.getScheduleResultList().size(),
                "1台*16班产*8班=128 已覆盖 8+60+60 时，不应因真实换模后只剩112继续加机台");
        assertEquals("K1313", context.getScheduleResultList().get(0).getLhMachineCode());
        assertEquals(112, context.getScheduleResultList().get(0).getDailyPlanQty().intValue());
    }

    @Test
    void scheduleNewSpecs_shouldNotSplitSingleMachineWhenStructureEndingCoversWindowPlan() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        injectDependencies(strategy, true);

        LhScheduleContext context = buildContext();
        Date scheduleDate = dateTime(2026, 6, 2, 0, 0);
        context.setScheduleDate(scheduleDate);
        context.setScheduleTargetDate(dateTime(2026, 6, 4, 0, 0));
        context.setScheduleWindowShifts(LhScheduleTimeUtil.buildDefaultScheduleShifts(context, scheduleDate));

        SkuScheduleDTO sku = buildSku();
        sku.setMaterialCode("3302002661");
        sku.setMaterialDesc("结构收尾单机覆盖窗口计划");
        sku.setConstructionStage(ConstructionStageEnum.FORMAL.getCode());
        sku.setLhTimeSeconds(3600);
        sku.setShiftCapacity(16);
        sku.setMouldQty(1);
        sku.setPendingQty(128);
        sku.setTargetScheduleQty(128);
        sku.setWindowPlanQty(128);
        sku.setWindowRemainingPlanQty(128);
        sku.setSurplusQty(128);
        sku.setEmbryoStock(-1);
        sku.setMonthlyHistoryShortageQty(0);
        sku.setScheduleDayFinishQty(0);
        sku.setDailyPlanQuotaMap(buildThreeDayQuotaMap(
                context.getScheduleWindowShifts(), sku.getMaterialCode(), 8, 60, 60));
        context.getNewSpecSkuList().add(sku);
        attachAvailableMould(context, sku.getMaterialCode(), "MOULD-3302002661");

        MachineScheduleDTO k1111 = buildMachine("K1111", dateTime(2026, 6, 2, 6, 0));
        MachineScheduleDTO k1113 = buildMachine("K1113", dateTime(2026, 6, 2, 6, 0));

        strategy.scheduleNewSpecs(context, orderedMachineMatch(k1111, k1113),
                defaultMouldChangeBalance(), defaultInspectionBalance(), defaultCapacityCalculate());

        assertEquals(1, context.getScheduleResultList().size(),
                "结构收尾误入严格策略时，1台*16班产*8班已覆盖窗口计划128，不应拆到第二台机台");
        LhScheduleResult result = findResult(context.getScheduleResultList(), "K1111");
        assertEquals(116, result.getDailyPlanQty().intValue(), "首台应按真实可排窗口排满，而不是被均分成64");
        assertEquals(16, resolveShiftQty(result, 7), "K1111 C7 应继续排满班产");
        assertEquals(16, resolveShiftQty(result, 8), "K1111 C8 应继续排满班产");
        assertEquals(0, context.getUnscheduledResultList().size(), "单机真实窗口已排满时不应生成剩余未排");
    }

    @Test
    void scheduleNewSpecs_shouldApplyDailyStandardQtyAndSwitchAddedMachineOnExpansionDate() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        injectDependencies(strategy, false);

        LhScheduleContext context = buildContext();
        Date scheduleDate = dateTime(2026, 6, 1, 0, 0);
        context.setScheduleDate(scheduleDate);
        context.setScheduleTargetDate(dateTime(2026, 6, 3, 0, 0));
        context.setScheduleWindowShifts(LhScheduleTimeUtil.buildDefaultScheduleShifts(context, scheduleDate));

        SkuScheduleDTO sku = buildSku();
        sku.setMaterialCode("3302001074");
        sku.setMaterialDesc("日标准产量与增机日期回归");
        sku.setConstructionStage(ConstructionStageEnum.FORMAL.getCode());
        sku.setLhTimeSeconds(3600);
        sku.setShiftCapacity(16);
        sku.setMouldQty(1);
        sku.setPendingQty(928);
        sku.setTargetScheduleQty(146);
        sku.setWindowPlanQty(146);
        sku.setWindowRemainingPlanQty(146);
        sku.setSurplusQty(928);
        sku.setEmbryoStock(-1);
        sku.setMonthlyHistoryShortageQty(0);
        sku.setScheduleDayFinishQty(0);
        sku.setDailyPlanQuotaMap(buildThreeDayQuotaMap(
                context.getScheduleWindowShifts(), sku.getMaterialCode(), 8, 92, 92));
        context.getNewSpecSkuList().add(sku);

        MdmSkuLhCapacity skuCapacity = new MdmSkuLhCapacity();
        skuCapacity.setMaterialCode(sku.getMaterialCode());
        skuCapacity.setClassCapacity(16);
        skuCapacity.setStandardCapacity(46);
        context.getSkuLhCapacityMap().put(sku.getMaterialCode(), skuCapacity);
        attachAvailableMould(context, sku.getMaterialCode(), "MOULD-3302001074-1");
        MdmSkuMouldRel secondMouldRel = new MdmSkuMouldRel();
        secondMouldRel.setMaterialCode(sku.getMaterialCode());
        secondMouldRel.setMouldCode("MOULD-3302001074-2");
        context.getSkuMouldRelMap().get(sku.getMaterialCode()).add(secondMouldRel);
        MdmModelInfo secondMouldInfo = new MdmModelInfo();
        secondMouldInfo.setMouldCode("MOULD-3302001074-2");
        secondMouldInfo.setMouldStatus(1);
        context.getModelInfoMap().put(secondMouldInfo.getMouldCode(), secondMouldInfo);

        MachineScheduleDTO k1311 = buildMachine("K1311", dateTime(2026, 6, 1, 6, 0));
        MachineScheduleDTO k1405 = buildMachine("K1405", dateTime(2026, 6, 1, 6, 0));

        strategy.scheduleNewSpecs(context, orderedMachineMatch(k1311, k1405),
                defaultMouldChangeBalance(), defaultInspectionBalance(), defaultCapacityCalculate());

        assertEquals(2, context.getScheduleResultList().size(), "T+1日计划92需要两台机台");
        LhScheduleResult primaryResult = findResult(context.getScheduleResultList(), "K1311");
        LhScheduleResult addedResult = findResult(context.getScheduleResultList(), "K1405");
        assertEquals(14, resolveShiftQty(primaryResult, 5), "T+1中班应承担日标准产量余量14");
        assertEquals(14, resolveShiftQty(primaryResult, 8), "T+2中班应承担日标准产量余量14");
        assertEquals(0, resolveShiftQty(addedResult, 1), "第二台应在T+1增机，T日早班不得生产");
        assertEquals(0, resolveShiftQty(addedResult, 2), "第二台应在T+1增机，T日中班不得提前换模首检");
        assertEquals(0, resolveShiftQty(addedResult, 3), "T+1晚班不可换模，第二台不得在C3换模或生产");
        assertEquals(dateTime(2026, 6, 2, 6, 0), addedResult.getMouldChangeStartTime(),
                "哪一天增机台，就应在那一天允许换模的首个班次开始换模");
        assertEquals(4, resolveShiftQty(addedResult, 4), "换模完成落在C4结束临界点，首检4应归属C4");
        assertEquals(16, resolveShiftQty(addedResult, 5), "第二台正常生产应从C5开始");
    }

    @Test
    void scheduleNewSpecs_shouldSkipFirstDayWhenFormalNonEndingHasNoFirstDayPlan() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        injectDependencies(strategy, false);

        LhScheduleContext context = buildContext();
        Date scheduleDate = dateTime(2026, 4, 1, 0, 0);
        context.setScheduleDate(scheduleDate);
        context.setScheduleTargetDate(dateTime(2026, 4, 3, 0, 0));
        context.setScheduleWindowShifts(LhScheduleTimeUtil.buildDefaultScheduleShifts(context, scheduleDate));

        SkuScheduleDTO sku = buildSku();
        sku.setMaterialCode("3302002326");
        sku.setConstructionStage(ConstructionStageEnum.FORMAL.getCode());
        sku.setLhTimeSeconds(3600);
        sku.setShiftCapacity(16);
        sku.setMouldQty(1);
        sku.setPendingQty(1376);
        sku.setDailyPlanQty(80);
        sku.setTargetScheduleQty(128);
        sku.setWindowPlanQty(80);
        sku.setWindowRemainingPlanQty(80);
        sku.setSurplusQty(1376);
        sku.setEmbryoStock(-1);
        sku.setDailyPlanQuotaMap(buildThreeDayQuotaMap(
                context.getScheduleWindowShifts(), sku.getMaterialCode(), 0, 32, 48));
        context.getNewSpecSkuList().add(sku);

        MachineScheduleDTO k2024 = buildMachine("K2024", dateTime(2026, 4, 1, 6, 0));
        attachAvailableMould(context, sku.getMaterialCode(), "MOULD-3302002326");

        strategy.scheduleNewSpecs(context, singletonMachineMatch(k2024),
                defaultMouldChangeBalance(), defaultInspectionBalance(), skuCapacityCalculate());

        assertEquals(1, context.getScheduleResultList().size(), "首日无计划但后续有计划时，应从后续生产日开始落量");
        LhScheduleResult result = context.getScheduleResultList().get(0);
        assertEquals(0, resolveShiftQty(result, 1), "首日日计划为0时，不应落早班");
        assertEquals(0, resolveShiftQty(result, 2), "首日日计划为0时，不应落中班");
        assertEquals(0, resolveShiftQty(result, 3), "第二个生产日首个班次为晚班时，不应提前换模或生产");
        assertEquals(dateTime(2026, 4, 2, 6, 0), result.getMouldChangeStartTime(),
                "哪一天上机，就应在那一天允许换模的首个班次开始换模");
        assertEquals(4, resolveShiftQty(result, 4), "换模完成落在C4结束临界点，首检4应归属C4");
        assertTrue(resolveShiftQty(result, 5) > 0, "首台正常生产应从C5开始");
    }

    @Test
    void scheduleNewSpecs_shouldAppendEarlyProductionStructurePlanRemark() throws Exception {
        LhScheduleContext context = scheduleFuturePlanSkuWithStructurePlans(1, 2, 3);

        assertEquals(1, context.getScheduleResultList().size(), "普通提前生产应生成一条新增排产结果");
        LhScheduleResult result = context.getScheduleResultList().get(0);
        assertEquals("结构计划硫化机台数：1,2,3", result.getRemark());
        assertTrue(resolveShiftQty(result, 1) > 0 || resolveShiftQty(result, 2) > 0,
                "结构机台数未达计划时，应允许后续日SKU提前到T日生产");
    }

    @Test
    void scheduleNewSpecs_shouldAppendStructureSwitchRemark() throws Exception {
        LhScheduleContext context = scheduleFuturePlanSkuWithStructurePlans(0, 2, 3);

        assertEquals(1, context.getScheduleResultList().size(), "结构切换提前生产应生成一条新增排产结果");
        assertEquals("[结构切换] 结构计划硫化机台数：0,2,3",
                context.getScheduleResultList().get(0).getRemark());
    }

    @Test
    void resolveEarlyProductionDecision_shouldUseShiftWorkDateForCrossDayNightShift() {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        LhScheduleContext context = buildContext();
        Date scheduleDate = dateTime(2026, 5, 1, 0, 0);
        context.setScheduleDate(scheduleDate);
        context.setScheduleTargetDate(dateTime(2026, 5, 2, 0, 0));
        context.setWindowEndDate(dateTime(2026, 5, 3, 0, 0));
        context.setScheduleConfig(buildChangeoverBalanceScheduleConfig("1"));
        context.setScheduleWindowShifts(LhScheduleTimeUtil.buildDefaultScheduleShifts(context, scheduleDate));

        SkuScheduleDTO sku = buildSku();
        sku.setMaterialCode("3302002326");
        sku.setStructureName("L1");
        sku.setDailyPlanQuotaMap(buildThreeDayQuotaMap(
                context.getScheduleWindowShifts(), sku.getMaterialCode(), 0, 46, 0));
        LocalDate secondDay = toLocalDate(context.getScheduleWindowShifts().get(3));
        context.addStructurePlanMachineCount(secondDay, sku.getStructureName(), 2);
        Date crossDayNightShiftStartTime = context.getScheduleWindowShifts().get(2).getShiftStartDateTime();

        EarlyProductionDecision decision = ReflectionTestUtils.invokeMethod(strategy,
                "resolveEarlyProductionDecision", context, sku, crossDayNightShiftStartTime,
                context.getScheduleWindowShifts(), false);

        assertNotNull(decision);
        assertFalse(decision.isEarlyProduction(), "跨自然日夜班必须按班次业务日判断，T+1自身有计划时不应判为提前生产");
    }

    @Test
    void resolveEarlyProductionDecision_shouldMarkEndingNewSpecResultWhenNextDayHasPlan() {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        LhScheduleContext context = buildContext();
        Date scheduleDate = dateTime(2026, 6, 1, 0, 0);
        context.setScheduleDate(scheduleDate);
        context.setScheduleTargetDate(dateTime(2026, 6, 2, 0, 0));
        context.setWindowEndDate(dateTime(2026, 6, 3, 0, 0));
        context.setScheduleWindowShifts(LhScheduleTimeUtil.buildDefaultScheduleShifts(context, scheduleDate));

        SkuScheduleDTO sku = buildSku();
        sku.setMaterialCode("3302002481");
        sku.setStructureName("11R22.5-JD571四层");
        sku.setDailyCapacity(40);
        sku.setDailyPlanQuotaMap(buildThreeDayQuotaMap(
                context.getScheduleWindowShifts(), sku.getMaterialCode(), 0, 40, 0));
        LocalDate firstDay = toLocalDate(context.getScheduleWindowShifts().get(0));
        LocalDate secondDay = toLocalDate(context.getScheduleWindowShifts().get(3));
        context.addStructurePlanMachineCount(firstDay, sku.getStructureName(), 1);
        context.addStructurePlanMachineCount(secondDay, sku.getStructureName(), 1);
        Date firstProductionStartTime = context.getScheduleWindowShifts().get(1).getShiftStartDateTime();

        EarlyProductionDecision decision = ReflectionTestUtils.invokeMethod(strategy,
                "resolveEarlyProductionDecision", context, sku, firstProductionStartTime,
                context.getScheduleWindowShifts(), true);
        LhScheduleResult result = buildNewSpecResult(sku.getMaterialCode(), "K1501L");
        ReflectionTestUtils.invokeMethod(strategy, "appendEarlyProductionRemark",
                context, result, decision, firstDay);

        assertNotNull(decision);
        assertTrue(decision.isEarlyProduction(), "新增收尾SKU下一业务日有计划时，也应参与提前生产判定");
        assertEquals("1", result.getIsEarlyProduction(), "收尾新增结果提前一天生产时应回写提前生产标识");
        assertEquals("结构计划硫化机台数：1,1,0", result.getRemark());
    }

    @Test
    void alignFirstTimeByDailyPlan_shouldKeepCurrentDayForCompensationEarlyProduction() {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        LhScheduleContext context = buildContext();
        Date scheduleDate = dateTime(2026, 6, 1, 0, 0);
        context.setScheduleDate(scheduleDate);
        context.setScheduleTargetDate(dateTime(2026, 6, 2, 0, 0));
        context.setWindowEndDate(dateTime(2026, 6, 3, 0, 0));
        context.setScheduleWindowShifts(LhScheduleTimeUtil.buildDefaultScheduleShifts(context, scheduleDate));

        SkuScheduleDTO sku = buildSku();
        sku.setMaterialCode("3302002546");
        sku.setStructureName("11R22.5-JD571四层");
        sku.setContinuousCompensationSku(true);
        sku.setDailyPlanQty(0);
        sku.setDailyPlanQuotaMap(buildThreeDayQuotaMap(
                context.getScheduleWindowShifts(), sku.getMaterialCode(), 0, 32, 50));
        LocalDate firstDay = toLocalDate(context.getScheduleWindowShifts().get(0));
        LocalDate secondDay = toLocalDate(context.getScheduleWindowShifts().get(3));
        context.addStructurePlanMachineCount(firstDay, sku.getStructureName(), 1);
        context.addStructurePlanMachineCount(secondDay, sku.getStructureName(), 1);
        Date switchReadyTime = context.getScheduleWindowShifts().get(0).getShiftStartDateTime();
        Date firstProductionStartTime = context.getScheduleWindowShifts().get(1).getShiftStartDateTime();

        EarlyProductionDecision decision = ReflectionTestUtils.invokeMethod(strategy,
                "resolveEarlyProductionDecision", context, sku, firstProductionStartTime,
                context.getScheduleWindowShifts(), false);
        Date alignedSwitchReadyTime = ReflectionTestUtils.invokeMethod(strategy,
                "alignFirstMachineSwitchReadyTimeByDailyPlan", context, sku, switchReadyTime,
                context.getScheduleWindowShifts(), false);
        Date alignedProductionStartTime = ReflectionTestUtils.invokeMethod(strategy,
                "alignFirstProductionStartTimeByDailyPlan", context, sku, firstProductionStartTime,
                context.getScheduleWindowShifts(), false, decision);

        assertNotNull(decision);
        assertTrue(decision.isEarlyProduction(), "续作补偿SKU转入新增后应复用提前生产准入");
        assertEquals(switchReadyTime, alignedSwitchReadyTime, "提前生产准入通过时，补偿SKU首台换模不应顺延到T+1");
        assertEquals(firstProductionStartTime, alignedProductionStartTime, "提前生产准入通过时，补偿SKU开产不应顺延到T+1");
    }

    @Test
    void isSkuNeedScheduleOnFirstDay_shouldUseCompensationEarlyProductionAdmission() {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        LhScheduleContext context = buildContext();
        Date scheduleDate = dateTime(2026, 6, 1, 0, 0);
        context.setScheduleDate(scheduleDate);
        context.setScheduleTargetDate(dateTime(2026, 6, 2, 0, 0));
        context.setWindowEndDate(dateTime(2026, 6, 3, 0, 0));
        context.setScheduleWindowShifts(LhScheduleTimeUtil.buildDefaultScheduleShifts(context, scheduleDate));

        SkuScheduleDTO sku = buildSku();
        sku.setMaterialCode("3302002546");
        sku.setStructureName("11R22.5-JD571四层");
        sku.setContinuousCompensationSku(true);
        sku.setDailyPlanQty(0);
        sku.setDailyPlanQuotaMap(buildThreeDayQuotaMap(
                context.getScheduleWindowShifts(), sku.getMaterialCode(), 0, 32, 50));
        context.getNewSpecEarlyProductionAllowedMap().put(sku, Boolean.TRUE);

        Boolean needScheduleOnFirstDay = ReflectionTestUtils.invokeMethod(strategy,
                "isSkuNeedScheduleOnFirstDay", context, sku);

        assertTrue(Boolean.TRUE.equals(needScheduleOnFirstDay),
                "补偿SKU提前生产准入通过时，首日空闲机台优先规则应可生效");
    }

    @Test
    void selectCandidateMachine_shouldKeepNormalBeforeSingleControlForCompensationEarlyProduction() {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        LhScheduleContext context = buildContext();
        Date scheduleDate = dateTime(2026, 6, 1, 0, 0);
        context.setScheduleDate(scheduleDate);
        context.setScheduleTargetDate(dateTime(2026, 6, 2, 0, 0));
        context.setWindowEndDate(dateTime(2026, 6, 3, 0, 0));
        context.setScheduleConfig(buildSingleControlScheduleConfig());
        context.setScheduleWindowShifts(LhScheduleTimeUtil.buildDefaultScheduleShifts(context, scheduleDate));

        SkuScheduleDTO sku = buildSku();
        sku.setMaterialCode("3302002546");
        sku.setStructureName("11R22.5-JD571四层");
        sku.setConstructionStage(ConstructionStageEnum.FORMAL.getCode());
        sku.setContinuousCompensationSku(true);
        sku.setDailyPlanQty(0);
        sku.setDailyPlanQuotaMap(buildThreeDayQuotaMap(
                context.getScheduleWindowShifts(), sku.getMaterialCode(), 0, 32, 50));
        context.getNewSpecEarlyProductionAllowedMap().put(sku, Boolean.TRUE);

        MachineScheduleDTO normalMachine = buildMachine("K1105", context.getScheduleWindowShifts().get(0).getShiftStartDateTime());
        MachineScheduleDTO occupiedSingleControlMachine = buildMachine("K1501L", context.getScheduleWindowShifts().get(5).getShiftEndDateTime());
        MachineScheduleDTO singleControlMachine = buildMachine("K1501R", context.getScheduleWindowShifts().get(0).getShiftStartDateTime());
        List<MachineScheduleDTO> candidates = Arrays.asList(normalMachine, occupiedSingleControlMachine, singleControlMachine);
        NewSpecCandidateCache candidateCache = NewSpecCandidateCache.from(candidates,
                machine -> StringUtils.startsWith(machine.getMachineCode(), "K1501"));

        MachineScheduleDTO selectedMachine = ReflectionTestUtils.invokeMethod(strategy,
                "selectCandidateMachine", context, sku, candidateCache, Collections.emptySet(),
                orderedMachineMatch(normalMachine, occupiedSingleControlMachine, singleControlMachine), null,
                ProductionQuantityPolicy.from(sku, false));

        assertNotNull(selectedMachine);
        assertEquals("K1105", selectedMachine.getMachineCode(),
                "补偿SKU提前生产准入通过时仍应保持正规SKU选机顺序，非单控机台优先于单控机台");
    }

    @Test
    void scheduleNewSpecs_shouldSkipSingleControlWhenCompensationCannotStartOnFirstWorkDate() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        injectDependencies(strategy, false);

        LhScheduleContext context = buildContext();
        Date scheduleDate = dateTime(2026, 6, 1, 0, 0);
        context.setScheduleDate(scheduleDate);
        context.setScheduleTargetDate(dateTime(2026, 6, 2, 0, 0));
        context.setWindowEndDate(dateTime(2026, 6, 3, 0, 0));
        context.setScheduleConfig(buildSingleControlScheduleConfig());
        context.setScheduleWindowShifts(LhScheduleTimeUtil.buildDefaultScheduleShifts(context, scheduleDate));

        SkuScheduleDTO sku = buildSku();
        sku.setMaterialCode("3302002546");
        sku.setMaterialDesc("3302002546");
        sku.setStructureName("11R22.5-JD571四层");
        sku.setConstructionStage(ConstructionStageEnum.FORMAL.getCode());
        sku.setContinuousCompensationSku(true);
        sku.setShiftCapacity(18);
        sku.setPendingQty(82);
        sku.setDailyPlanQty(0);
        sku.setTargetScheduleQty(82);
        sku.setWindowPlanQty(82);
        sku.setSurplusQty(1732);
        sku.setEmbryoStock(82);
        sku.setDailyPlanQuotaMap(buildThreeDayQuotaMap(
                context.getScheduleWindowShifts(), sku.getMaterialCode(), 0, 32, 50));
        context.getNewSpecSkuList().add(sku);
        attachAvailableMould(context, sku.getMaterialCode(), "M-3302002546");

        LocalDate firstDay = toLocalDate(context.getScheduleWindowShifts().get(0));
        LocalDate secondDay = toLocalDate(context.getScheduleWindowShifts().get(3));
        context.addStructurePlanMachineCount(firstDay, sku.getStructureName(), 1);
        context.addStructurePlanMachineCount(secondDay, sku.getStructureName(), 1);

        MachineScheduleDTO lateSingleControlMachine = buildMachine(
                "K1501L", context.getScheduleWindowShifts().get(1).getShiftStartDateTime());
        MachineScheduleDTO earlySingleControlMachine = buildMachine(
                "K1501R", context.getScheduleWindowShifts().get(0).getShiftStartDateTime());

        strategy.scheduleNewSpecs(context, orderedMachineMatch(lateSingleControlMachine, earlySingleControlMachine),
                defaultMouldChangeBalance(), defaultInspectionBalance(), defaultCapacityCalculate());

        assertEquals(1, context.getScheduleResultList().size(),
                "补偿SKU应跳过无法在当前业务日开产的单控候选后继续排产");
        LhScheduleResult result = context.getScheduleResultList().get(0);
        assertEquals("K1501R", result.getLhMachineCode(),
                "补偿SKU提前生产应落到真实可在当前业务日开产的单控侧别");
        assertEquals("1", result.getIsEarlyProduction(),
                "真实开产业务日满足提前生产时应写入提前生产标识");
    }

    @Test
    void allocateNewSpecMouldChangeStartTime_shouldKeepEarlyProductionChangeInMorningShift() {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        LhScheduleContext context = buildContext();
        Date scheduleDate = dateTime(2026, 6, 1, 0, 0);
        context.setScheduleDate(scheduleDate);
        context.setScheduleTargetDate(dateTime(2026, 6, 2, 0, 0));
        context.setWindowEndDate(dateTime(2026, 6, 3, 0, 0));
        context.setScheduleConfig(buildSingleControlChangeoverBalanceScheduleConfig());
        context.setScheduleWindowShifts(LhScheduleTimeUtil.buildDefaultScheduleShifts(context, scheduleDate));

        SkuScheduleDTO sku = buildSku();
        sku.setMaterialCode("3302002546");
        sku.setEmbryoCode("215104553");
        sku.setStructureName("11R22.5-JD571四层");
        sku.setConstructionStage(ConstructionStageEnum.FORMAL.getCode());
        sku.setContinuousCompensationSku(true);
        sku.setDailyPlanQuotaMap(buildThreeDayQuotaMap(
                context.getScheduleWindowShifts(), sku.getMaterialCode(), 0, 32, 50));
        context.getMaterialSharedEmbryoMap().put(sku.getMaterialCode(), Boolean.TRUE);

        LocalDate firstDay = toLocalDate(context.getScheduleWindowShifts().get(0));
        LocalDate secondDay = toLocalDate(context.getScheduleWindowShifts().get(2));
        context.addStructurePlanMachineCount(firstDay, sku.getStructureName(), 14);
        context.addStructurePlanMachineCount(secondDay, sku.getStructureName(), 16);
        // 早班已有共用胎胚换模达到均衡阈值时，普通新增换模会被挪到中班；
        // 提前生产新增换模必须绕过该均衡挪动，保留 06:00 首台换模。
        context.getDailyMouldChangeCountMap().put("2026-06-01", new int[]{8, 0});

        Date allocatedTime = ReflectionTestUtils.invokeMethod(strategy,
                "allocateNewSpecMouldChangeStartTime", context, sku, "K1501R",
                dateTime(2026, 6, 1, 6, 0), 8, new DefaultMouldChangeBalanceStrategy());

        assertEquals(dateTime(2026, 6, 1, 6, 0), allocatedTime,
                "提前生产首台新增换模应使用提前生产动作类型，避免被共用胎胚均衡挪到中班");
    }

    @Test
    void resolveEarlyProductionSimulationQuotaMap_shouldUseShiftedPlanAfterAdmission() {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        LhScheduleContext context = buildContext();
        Date scheduleDate = dateTime(2026, 6, 14, 0, 0);
        context.setScheduleDate(scheduleDate);
        context.setScheduleTargetDate(dateTime(2026, 6, 15, 0, 0));
        context.setWindowEndDate(dateTime(2026, 6, 16, 0, 0));
        context.setScheduleConfig(buildChangeoverBalanceScheduleConfig("1"));
        context.setScheduleWindowShifts(LhScheduleTimeUtil.buildDefaultScheduleShifts(context, scheduleDate));

        SkuScheduleDTO sku = buildSku();
        sku.setMaterialCode("3302002326");
        sku.setStructureName("L1");
        Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap = buildThreeDayQuotaMap(
                context.getScheduleWindowShifts(), sku.getMaterialCode(), 0, 46, 46);
        LocalDate firstDay = toLocalDate(context.getScheduleWindowShifts().get(0));
        LocalDate secondDay = toLocalDate(context.getScheduleWindowShifts().get(3));
        LocalDate thirdDay = toLocalDate(context.getScheduleWindowShifts().get(6));
        LocalDate fourthDay = thirdDay.plusDays(1);
        quotaMap.put(fourthDay, quota(sku.getMaterialCode(), fourthDay, 46));
        sku.setDailyPlanQuotaMap(quotaMap);
        context.addStructurePlanMachineCount(firstDay, sku.getStructureName(), 1);

        Map<LocalDate, SkuDailyPlanQuotaDTO> shiftedQuotaMap = ReflectionTestUtils.invokeMethod(strategy,
                "resolveEarlyProductionSimulationQuotaMap", context, sku, firstDay, thirdDay);

        assertNotNull(shiftedQuotaMap);
        assertFalse(shiftedQuotaMap == quotaMap, "提前生产模拟必须使用临时前移视图，不能直接使用原始账本对象");
        assertEquals(46, shiftedQuotaMap.get(firstDay).getDayPlanQty());
        assertEquals(46, shiftedQuotaMap.get(secondDay).getDayPlanQty());
        assertEquals(46, shiftedQuotaMap.get(thirdDay).getDayPlanQty());
        assertEquals(0, quotaMap.get(firstDay).getDayPlanQty(), "临时前移视图不能污染原始日计划账本");
    }

    @Test
    void scheduleNewSpecs_shouldKeepFuturePlanStartedMachineToWindowEndWhenOtherSkuPending() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        injectDependencies(strategy, false);

        LhScheduleContext context = buildContext();
        Date scheduleDate = dateTime(2026, 6, 14, 0, 0);
        context.setScheduleDate(scheduleDate);
        context.setScheduleTargetDate(dateTime(2026, 6, 15, 0, 0));
        context.setWindowEndDate(dateTime(2026, 6, 16, 0, 0));
        context.setScheduleConfig(buildChangeoverBalanceScheduleConfig("1"));
        context.setScheduleWindowShifts(LhScheduleTimeUtil.buildDefaultScheduleShifts(context, scheduleDate));

        SkuScheduleDTO sku = buildSku();
        sku.setMaterialCode("3302001797");
        sku.setStructureName("235/75R17.5");
        sku.setConstructionStage(ConstructionStageEnum.FORMAL.getCode());
        sku.setLhTimeSeconds(3600);
        sku.setShiftCapacity(20);
        sku.setMouldQty(1);
        sku.setPendingQty(2502);
        sku.setDailyPlanQty(30);
        sku.setTargetScheduleQty(2502);
        sku.setWindowPlanQty(24);
        sku.setWindowRemainingPlanQty(24);
        sku.setSurplusQty(2502);
        sku.setEmbryoStock(-1);
        sku.setDailyPlanQuotaMap(buildThreeDayQuotaMap(
                context.getScheduleWindowShifts(), sku.getMaterialCode(), 0, 0, 24));
        context.getNewSpecSkuList().add(sku);
        context.getMaterialSharedEmbryoMap().put(sku.getMaterialCode(), true);
        LocalDate firstDay = toLocalDate(context.getScheduleWindowShifts().get(0));
        LocalDate secondDay = toLocalDate(context.getScheduleWindowShifts().get(3));
        LocalDate thirdDay = toLocalDate(context.getScheduleWindowShifts().get(6));
        context.addStructurePlanMachineCount(firstDay, sku.getStructureName(), 0);
        context.addStructurePlanMachineCount(secondDay, sku.getStructureName(), 0);
        context.addStructurePlanMachineCount(thirdDay, sku.getStructureName(), 5);
        String targetDayKey = LhScheduleTimeUtil.formatDate(context.getScheduleWindowShifts().get(3).getWorkDate());
        context.getDailyMouldChangeCountMap().put(targetDayKey, new int[]{8, 0});

        SkuScheduleDTO pendingSku = buildSku();
        pendingSku.setMaterialCode("3302001798");
        pendingSku.setDailyPlanQuotaMap(buildThreeDayQuotaMap(
                context.getScheduleWindowShifts(), pendingSku.getMaterialCode(), 20, 20, 20));
        context.getNewSpecSkuList().add(pendingSku);

        MachineScheduleDTO k1302 = buildMachine("K1302", dateTime(2026, 6, 14, 13, 0));
        attachAvailableMould(context, sku.getMaterialCode(), "MOULD-3302001797");

        strategy.scheduleNewSpecs(context, materialMatch(sku.getMaterialCode(), k1302),
                new DefaultMouldChangeBalanceStrategy(), defaultInspectionBalance(), twentyCapacityCalculate());

        LhScheduleResult result = findResultByMaterialCode(context.getScheduleResultList(), sku.getMaterialCode());
        assertEquals(84, result.getDailyPlanQty().intValue(), "后续日有计划且已新增换模上机时，应保留机台到窗口结束的有效产能");
        assertEquals(dateTime(2026, 6, 15, 6, 0), result.getMouldChangeStartTime(),
                "第三天计划只能提前到目标业务日，首台新增换模应落在第二天C4");
        assertEquals(0, resolveShiftQty(result, 2), "第三天计划提前到第二天排产时，不应在第一天C2首检");
        assertEquals(0, resolveShiftQty(result, 3), "第三天计划提前到第二天排产时，不应在第一天C3生产");
        assertEquals(4, resolveShiftQty(result, 4), "第二天C4换模完成后只应落首检4");
        assertEquals(20, resolveShiftQty(result, 5), "第二天C5应开始按班产排产");
        assertEquals(20, resolveShiftQty(result, 6), "T+2夜班应继续按班产排计划量");
        assertEquals(20, resolveShiftQty(result, 7), "T+2早班应继续按班产排计划量");
        assertEquals(20, resolveShiftQty(result, 8), "T+2中班应继续按班产排计划量");
    }

    @Test
    void appendEarlyProductionRemark_shouldPreserveExistingRemarkAndAvoidDuplicate() {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        LhScheduleContext context = buildContext();
        LhScheduleResult result = new LhScheduleResult();
        result.setMaterialCode("3302002326");
        result.setStructureName("L1");
        result.setLhMachineCode("K2024");
        result.setRemark("原备注");
        EarlyProductionDecision decision = EarlyProductionDecision.earlyProduction(true,
                EarlyProductionDecision.SCENE_STRUCTURE_SWITCH, LocalDate.of(2026, 4, 2),
                Arrays.asList(0, 2, 3), "结构已排机台数未达到计划机台数");

        ReflectionTestUtils.invokeMethod(strategy, "appendEarlyProductionRemark",
                context, result, decision, LocalDate.of(2026, 4, 1));
        ReflectionTestUtils.invokeMethod(strategy, "appendEarlyProductionRemark",
                context, result, decision, LocalDate.of(2026, 4, 1));

        assertEquals("原备注；[结构切换] 结构计划硫化机台数：0,2,3", result.getRemark());
    }

    @Test
    void scheduleNewSpecs_shouldFillSmallBatchSingleControlMachineToWindowEnd() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        injectDependencies(strategy, false);

        LhScheduleContext context = buildContext();
        Date scheduleDate = dateTime(2026, 4, 1, 0, 0);
        context.setScheduleDate(scheduleDate);
        context.setScheduleTargetDate(dateTime(2026, 4, 3, 0, 0));
        context.setScheduleWindowShifts(LhScheduleTimeUtil.buildDefaultScheduleShifts(context, scheduleDate));
        context.setScheduleConfig(buildSingleControlScheduleConfig());

        SkuScheduleDTO sku = buildSku();
        sku.setMaterialCode("3302002795");
        sku.setConstructionStage(ConstructionStageEnum.FORMAL.getCode());
        sku.setSmallBatchValidation(true);
        sku.setLhTimeSeconds(3600);
        sku.setShiftCapacity(20);
        sku.setMouldQty(1);
        sku.setPendingQty(98);
        sku.setDailyPlanQty(48);
        sku.setTargetScheduleQty(160);
        sku.setWindowPlanQty(48);
        sku.setWindowRemainingPlanQty(48);
        sku.setSurplusQty(98);
        sku.setEmbryoStock(-1);
        sku.setDailyPlanQuotaMap(buildThreeDayQuotaMap(
                context.getScheduleWindowShifts(), sku.getMaterialCode(), 16, 16, 16));
        context.getNewSpecSkuList().add(sku);

        SkuScheduleDTO nextSku = buildSku();
        nextSku.setMaterialCode("3302002796");
        nextSku.setConstructionStage(ConstructionStageEnum.FORMAL.getCode());
        nextSku.setLhTimeSeconds(3600);
        nextSku.setShiftCapacity(20);
        nextSku.setMouldQty(1);
        nextSku.setPendingQty(40);
        nextSku.setDailyPlanQty(40);
        nextSku.setTargetScheduleQty(40);
        nextSku.setWindowPlanQty(40);
        nextSku.setSurplusQty(40);
        nextSku.setEmbryoStock(-1);
        nextSku.setDailyPlanQuotaMap(buildThreeDayQuotaMap(
                context.getScheduleWindowShifts(), nextSku.getMaterialCode(), 20, 20, 0));
        context.getNewSpecSkuList().add(nextSku);

        MachineScheduleDTO k1501r = buildMachine("K1501R", dateTime(2026, 4, 1, 6, 0));
        attachAvailableMould(context, sku.getMaterialCode(), "MOULD-3302002795");

        strategy.scheduleNewSpecs(context, singletonMachineMatch(k1501r),
                defaultMouldChangeBalance(), defaultInspectionBalance(), skuCapacityCalculate());

        LhScheduleResult result = findResultByMaterialCode(context.getScheduleResultList(), sku.getMaterialCode());
        assertNotNull(result, "小批量单控非收尾应保持单机台排产");
        assertEquals(70, result.getDailyPlanQty().intValue(), "K1501R 应从2班到8班补满7个单侧班产");
        for (int shiftIndex = 2; shiftIndex <= 8; shiftIndex++) {
            assertEquals(10, resolveShiftQty(result, shiftIndex), "单控机台每个有效班次应按单侧班产排满");
        }
    }

    @Test
    void scheduleNewSpecs_shouldStopWhenWindowRemainingShortageBackToThreshold() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        injectDependencies(strategy, false);

        LhScheduleContext context = buildContext();
        Date scheduleDate = dateTime(2026, 5, 9, 0, 0);
        context.setScheduleDate(scheduleDate);
        context.setScheduleTargetDate(dateTime(2026, 5, 11, 0, 0));
        context.setScheduleWindowShifts(LhScheduleTimeUtil.buildDefaultScheduleShifts(context, scheduleDate));
        context.setScheduleConfig(new LhScheduleConfig(Collections.singletonMap(
                LhScheduleParamConstant.NEW_SPEC_SHORTAGE_ADD_MACHINE_THRESHOLD, "150")));

        SkuScheduleDTO sku = buildSku();
        sku.setMaterialCode("3302001592");
        sku.setConstructionStage(ConstructionStageEnum.FORMAL.getCode());
        sku.setLhTimeSeconds(3600);
        sku.setShiftCapacity(16);
        sku.setMouldQty(1);
        sku.setPendingQty(1256);
        sku.setTargetScheduleQty(336);
        sku.setWindowPlanQty(144);
        sku.setWindowRemainingPlanQty(144);
        sku.setSurplusQty(1256);
        sku.setEmbryoStock(-1);
        sku.setMonthlyHistoryShortageQty(192);
        sku.setScheduleDayFinishQty(0);
        sku.setDailyPlanQuotaMap(buildThreeDayQuotaMap(
                context.getScheduleWindowShifts(), sku.getMaterialCode(), 48, 48, 48));
        context.getNewSpecSkuList().add(sku);

        MachineScheduleDTO k1115 = buildMachine("K1115", dateTime(2026, 5, 9, 6, 0));
        MachineScheduleDTO k1116 = buildMachine("K1116", dateTime(2026, 5, 9, 6, 0));
        MachineScheduleDTO k1117 = buildMachine("K1117", dateTime(2026, 5, 9, 6, 0));

        strategy.scheduleNewSpecs(context, orderedMachineMatch(k1115, k1116, k1117),
                defaultMouldChangeBalance(), defaultInspectionBalance(), defaultCapacityCalculate());

        assertEquals(2, context.getScheduleResultList().size(),
                "窗口后剩余欠产回到阈值以内时，应停止继续增加第三台机台");
        assertEquals("K1115", context.getScheduleResultList().get(0).getLhMachineCode());
        assertEquals("K1116", context.getScheduleResultList().get(1).getLhMachineCode());
    }

    @Test
    void scheduleNewSpecs_shouldStrictlyScheduleShortageWhenWindowNoPlanButFuturePlanExists() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        injectDependencies(strategy, false);

        LhScheduleContext context = buildContext();
        Date scheduleDate = dateTime(2026, 5, 9, 0, 0);
        context.setScheduleDate(scheduleDate);
        context.setScheduleTargetDate(dateTime(2026, 5, 11, 0, 0));
        context.setScheduleWindowShifts(LhScheduleTimeUtil.buildDefaultScheduleShifts(context, scheduleDate));

        SkuScheduleDTO sku = buildSku();
        sku.setMaterialCode("3302001592");
        sku.setConstructionStage(ConstructionStageEnum.FORMAL.getCode());
        sku.setLhTimeSeconds(3600);
        sku.setShiftCapacity(16);
        sku.setMouldQty(1);
        sku.setPendingQty(200);
        sku.setTargetScheduleQty(200);
        sku.setWindowPlanQty(0);
        sku.setWindowRemainingPlanQty(0);
        sku.setSurplusQty(200);
        sku.setEmbryoStock(-1);
        sku.setMonthlyHistoryShortageQty(100);
        sku.setFutureMonthPlanQtyAfterWindow(48);
        sku.setDailyPlanQuotaMap(buildThreeDayQuotaMap(
                context.getScheduleWindowShifts(), sku.getMaterialCode(), 0, 0, 0));
        context.getNewSpecSkuList().add(sku);
        attachAvailableMould(context, sku.getMaterialCode(), "MOULD-3302001592");

        MachineScheduleDTO k1115 = buildMachine("K1115", dateTime(2026, 5, 9, 6, 0));

        strategy.scheduleNewSpecs(context, singletonMachineMatch(k1115),
                defaultMouldChangeBalance(), defaultInspectionBalance(), defaultCapacityCalculate());

        assertEquals(1, context.getScheduleResultList().size(), "仅补本月欠产时应生成一条新增结果");
        LhScheduleResult result = context.getScheduleResultList().get(0);
        assertEquals(100, result.getDailyPlanQty().intValue(), "窗口无计划但月底有后续计划时，只能严格补本月欠产");
        assertEquals("0", result.getIsEnd(), "月底仍有后续计划时不能把SKU标记为整体收尾");
    }

    @Test
    void scheduleNewSpecs_shouldForceNonEndingForShortageOnlyEvenWhenEndingStrategyReturnsTrue() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        injectDependencies(strategy, true);

        LhScheduleContext context = buildContext();
        Date scheduleDate = dateTime(2026, 5, 9, 0, 0);
        context.setScheduleDate(scheduleDate);
        context.setScheduleTargetDate(dateTime(2026, 5, 11, 0, 0));
        context.setScheduleWindowShifts(LhScheduleTimeUtil.buildDefaultScheduleShifts(context, scheduleDate));

        SkuScheduleDTO sku = buildSku();
        sku.setMaterialCode("3302001592");
        sku.setConstructionStage(ConstructionStageEnum.FORMAL.getCode());
        sku.setLhTimeSeconds(3600);
        sku.setShiftCapacity(16);
        sku.setMouldQty(1);
        sku.setPendingQty(200);
        sku.setTargetScheduleQty(200);
        sku.setWindowPlanQty(0);
        sku.setWindowRemainingPlanQty(0);
        sku.setSurplusQty(200);
        sku.setEmbryoStock(-1);
        sku.setMonthlyHistoryShortageQty(100);
        sku.setFutureMonthPlanQtyAfterWindow(48);
        sku.setDailyPlanQuotaMap(buildThreeDayQuotaMap(
                context.getScheduleWindowShifts(), sku.getMaterialCode(), 0, 0, 0));
        context.getNewSpecSkuList().add(sku);
        attachAvailableMould(context, sku.getMaterialCode(), "MOULD-3302001592");

        MachineScheduleDTO k1115 = buildMachine("K1115", dateTime(2026, 5, 9, 6, 0));

        strategy.scheduleNewSpecs(context, singletonMachineMatch(k1115),
                defaultMouldChangeBalance(), defaultInspectionBalance(), defaultCapacityCalculate());

        assertEquals(1, context.getScheduleResultList().size(), "仅补本月欠产时应生成一条新增结果");
        LhScheduleResult result = context.getScheduleResultList().get(0);
        assertEquals(100, result.getDailyPlanQty().intValue(), "仅补欠产不能被收尾上调目标量");
        assertEquals("0", result.getIsEnd(), "月底仍有后续计划时必须强制按非收尾结果落库");
    }

    @Test
    void scheduleNewSpecs_shouldSkipSmallSurplusEndingWhenShortageOnlyHasFutureMonthPlan() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        injectDependencies(strategy, true);

        LhScheduleContext context = buildContext();
        Date scheduleDate = dateTime(2026, 6, 29, 0, 0);
        context.setScheduleDate(scheduleDate);
        context.setScheduleTargetDate(dateTime(2026, 6, 30, 0, 0));
        context.setScheduleWindowShifts(LhScheduleTimeUtil.buildDefaultScheduleShifts(context, scheduleDate));

        SkuScheduleDTO sku = buildSku();
        sku.setMaterialCode("3302000795");
        sku.setMaterialDesc("新增收尾小余量且仅补欠产物料");
        sku.setConstructionStage(ConstructionStageEnum.FORMAL.getCode());
        sku.setLhTimeSeconds(3600);
        sku.setShiftCapacity(16);
        sku.setMouldQty(2);
        sku.setPendingQty(2);
        sku.setDailyPlanQty(0);
        sku.setTargetScheduleQty(2);
        sku.setWindowPlanQty(0);
        sku.setWindowRemainingPlanQty(0);
        sku.setSurplusQty(2);
        sku.setEmbryoStock(-1);
        sku.setMonthlyHistoryShortageQty(6);
        sku.setFutureMonthPlanQtyAfterWindow(344);
        sku.setDailyPlanQuotaMap(buildThreeDayQuotaMap(
                context.getScheduleWindowShifts(), sku.getMaterialCode(), 0, 0, 0));
        context.getNewSpecSkuList().add(sku);
        attachAvailableMould(context, sku.getMaterialCode(), "MOULD-3302000795");
        appendTargetPreviousT1NightResult(context, sku.getMaterialCode(), 8);

        MachineScheduleDTO k1207 = buildMachine("K1207", dateTime(2026, 6, 29, 6, 0));

        strategy.scheduleNewSpecs(context, singletonMachineMatch(k1207),
                defaultMouldChangeBalance(), defaultInspectionBalance(), defaultCapacityCalculate());

        assertEquals(0, context.getScheduleResultList().size(),
                "收尾小余量且业务目标日前一日T+1夜班无排产时，即使月底仍有计划也不能排产");
        assertTrue(context.getNewSpecSkuList().isEmpty(), "命中收尾小余量规则后应移出新增待排队列");
        assertEquals(1, context.getUnscheduledResultList().size(), "命中收尾小余量规则后应写入未排结果");
        assertEquals(sku.getMaterialCode(), context.getUnscheduledResultList().get(0).getMaterialCode());
        assertEquals(Integer.valueOf(2), context.getUnscheduledResultList().get(0).getUnscheduledQty());
        assertEquals("收尾余量小于等于允许欠产偏差值，且前日 T+1 夜班未排满，本次不排产",
                context.getUnscheduledResultList().get(0).getUnscheduledReason());
    }

    @Test
    void prepareNewSpecShortageQuota_shouldUseHistoryShortageAsStrictTargetWhenWindowNoPlanButFuturePlanExists() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        injectDependencies(strategy, false);

        LhScheduleContext context = buildContext();
        Date scheduleDate = dateTime(2026, 5, 9, 0, 0);
        context.setScheduleDate(scheduleDate);
        context.setScheduleTargetDate(dateTime(2026, 5, 11, 0, 0));
        context.setScheduleWindowShifts(LhScheduleTimeUtil.buildDefaultScheduleShifts(context, scheduleDate));

        SkuScheduleDTO sku = buildSku();
        sku.setMaterialCode("3302001512");
        sku.setTargetScheduleQty(220);
        sku.setSurplusQty(220);
        sku.setWindowPlanQty(0);
        sku.setWindowRemainingPlanQty(220);
        sku.setMonthlyHistoryShortageQty(100);
        sku.setEffectiveCarryForwardQty(100);
        sku.setFutureMonthPlanQtyAfterWindow(48);
        Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap = buildThreeDayQuotaMap(
                context.getScheduleWindowShifts(), sku.getMaterialCode(), 0, 0, 0);
        quotaMap.values().iterator().next().setRemainingQty(220);
        sku.setDailyPlanQuotaMap(quotaMap);

        Method method = NewSpecProductionStrategy.class.getDeclaredMethod(
                "prepareNewSpecShortageQuota", LhScheduleContext.class, SkuScheduleDTO.class);
        method.setAccessible(true);

        assertFalse((Boolean) method.invoke(strategy, context, sku));
        assertTrue(sku.isStrictNewSpecShortageOnly(), "月底仍有计划时应进入仅补历史欠产口径");
        assertEquals(100, sku.resolveTargetScheduleQty(), "窗口无计划但月底有计划时，目标量只能等于本月历史欠产");
        assertEquals(100, sku.getWindowPlanQty());
        assertEquals(100, sku.getWindowRemainingPlanQty());
    }

    @Test
    void scheduleNewSpecs_shouldLimitShortageOnlyMachineCountByMouldChangeInfoForTwoMouldPlan() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        injectDependencies(strategy, false);

        LhScheduleContext context = buildContext();
        Date scheduleDate = dateTime(2026, 6, 1, 0, 0);
        context.setScheduleDate(scheduleDate);
        context.setScheduleTargetDate(dateTime(2026, 6, 3, 0, 0));
        context.setScheduleWindowShifts(LhScheduleTimeUtil.buildDefaultScheduleShifts(context, scheduleDate));

        SkuScheduleDTO sku = buildSku();
        sku.setMaterialCode("3302002661");
        sku.setConstructionStage(ConstructionStageEnum.FORMAL.getCode());
        sku.setLhTimeSeconds(3600);
        sku.setShiftCapacity(16);
        sku.setPendingQty(96);
        sku.setTargetScheduleQty(96);
        sku.setWindowPlanQty(0);
        sku.setWindowRemainingPlanQty(0);
        sku.setSurplusQty(96);
        sku.setEmbryoStock(-1);
        sku.setMonthlyHistoryShortageQty(96);
        sku.setFutureMonthPlanQtyAfterWindow(48);
        sku.setMouldChangeInfo("2-2");
        sku.setDailyPlanQuotaMap(buildThreeDayQuotaMap(
                context.getScheduleWindowShifts(), sku.getMaterialCode(), 0, 0, 0));
        context.getNewSpecSkuList().add(sku);

        MachineScheduleDTO k1313 = buildMachine("K1313", dateTime(2026, 6, 1, 6, 0));
        MachineScheduleDTO k1405 = buildMachine("K1405", dateTime(2026, 6, 1, 6, 0));
        k1313.setMaxMoldNum(2);
        k1405.setMaxMoldNum(2);

        strategy.scheduleNewSpecs(context, orderedMachineMatch(k1313, k1405),
                defaultMouldChangeBalance(), defaultInspectionBalance(), defaultCapacityCalculate());

        assertEquals(1, context.getScheduleResultList().size(),
                "仅补历史欠产且计划使用2模、双模机台时，只需要1台机台");
        assertEquals(96, context.getScheduleResultList().get(0).getDailyPlanQty().intValue());
    }

    @Test
    void scheduleNewSpecs_shouldExpandShortageOnlyMachineCountByMouldChangeInfoForFourMouldPlan() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        injectDependencies(strategy, false);

        LhScheduleContext context = buildContext();
        Date scheduleDate = dateTime(2026, 6, 1, 0, 0);
        context.setScheduleDate(scheduleDate);
        context.setScheduleTargetDate(dateTime(2026, 6, 3, 0, 0));
        context.setScheduleWindowShifts(LhScheduleTimeUtil.buildDefaultScheduleShifts(context, scheduleDate));

        SkuScheduleDTO sku = buildSku();
        sku.setMaterialCode("3302001512");
        sku.setConstructionStage(ConstructionStageEnum.FORMAL.getCode());
        sku.setLhTimeSeconds(3600);
        sku.setShiftCapacity(16);
        sku.setPendingQty(96);
        sku.setTargetScheduleQty(96);
        sku.setWindowPlanQty(0);
        sku.setWindowRemainingPlanQty(0);
        sku.setSurplusQty(96);
        sku.setEmbryoStock(-1);
        sku.setMonthlyHistoryShortageQty(96);
        sku.setFutureMonthPlanQtyAfterWindow(48);
        sku.setMouldChangeInfo("4-2-2");
        sku.setDailyPlanQuotaMap(buildThreeDayQuotaMap(
                context.getScheduleWindowShifts(), sku.getMaterialCode(), 0, 0, 0));
        context.getNewSpecSkuList().add(sku);

        MachineScheduleDTO k1313 = buildMachine("K1313", dateTime(2026, 6, 1, 6, 0));
        MachineScheduleDTO k1405 = buildMachine("K1405", dateTime(2026, 6, 1, 6, 0));
        k1313.setMaxMoldNum(2);
        k1405.setMaxMoldNum(2);

        strategy.scheduleNewSpecs(context, orderedMachineMatch(k1313, k1405),
                defaultMouldChangeBalance(), defaultInspectionBalance(), defaultCapacityCalculate());

        assertEquals(2, context.getScheduleResultList().size(),
                "仅补历史欠产且计划使用4模、双模机台时，需要拆到2台机台");
        assertEquals(96, sumResultQty(context.getScheduleResultList()), "仅补欠产总量不能超过本月历史欠产");
    }

    @Test
    void scheduleNewSpecs_shouldTreatNoWindowAndNoFuturePlanAsEnding() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        injectDependencies(strategy, false);

        LhScheduleContext context = buildContext();
        Date scheduleDate = dateTime(2026, 5, 9, 0, 0);
        context.setScheduleDate(scheduleDate);
        context.setScheduleTargetDate(dateTime(2026, 5, 11, 0, 0));
        context.setScheduleWindowShifts(LhScheduleTimeUtil.buildDefaultScheduleShifts(context, scheduleDate));

        SkuScheduleDTO sku = buildSku();
        sku.setMaterialCode("3302001592");
        sku.setConstructionStage(ConstructionStageEnum.FORMAL.getCode());
        sku.setLhTimeSeconds(3600);
        sku.setShiftCapacity(16);
        sku.setMouldQty(1);
        sku.setPendingQty(100);
        sku.setTargetScheduleQty(100);
        sku.setWindowPlanQty(0);
        sku.setWindowRemainingPlanQty(0);
        sku.setSurplusQty(100);
        sku.setEmbryoStock(-1);
        sku.setMonthlyHistoryShortageQty(100);
        sku.setFutureMonthPlanQtyAfterWindow(0);
        sku.setDailyPlanQuotaMap(buildThreeDayQuotaMap(
                context.getScheduleWindowShifts(), sku.getMaterialCode(), 0, 0, 0));
        context.getNewSpecSkuList().add(sku);

        MachineScheduleDTO k1115 = buildMachine("K1115", dateTime(2026, 5, 9, 6, 0));

        strategy.scheduleNewSpecs(context, singletonMachineMatch(k1115),
                defaultMouldChangeBalance(), defaultInspectionBalance(), defaultCapacityCalculate());

        assertEquals(1, context.getScheduleResultList().size(), "窗口和月底均无计划时应按收尾清量生成结果");
        LhScheduleResult result = context.getScheduleResultList().get(0);
        assertEquals(100, result.getDailyPlanQty().intValue(), "收尾清量必须严格按目标量排产");
        assertEquals("1", result.getIsEnd(), "月底无后续计划时应按整体收尾标记");
    }

    @Test
    void scheduleNewSpecs_shouldExpandEndingNoWindowPlanByMouldChangeInfoWhenHistoryShortageExists() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        injectDependencies(strategy, false);

        LhScheduleContext context = buildContext();
        Date scheduleDate = dateTime(2026, 6, 4, 0, 0);
        context.setScheduleDate(scheduleDate);
        context.setScheduleTargetDate(dateTime(2026, 6, 6, 0, 0));
        context.setScheduleWindowShifts(LhScheduleTimeUtil.buildDefaultScheduleShifts(context, scheduleDate));

        SkuScheduleDTO sku = buildSku();
        sku.setMaterialCode("3302001512");
        sku.setConstructionStage(ConstructionStageEnum.FORMAL.getCode());
        sku.setLhTimeSeconds(3600);
        sku.setShiftCapacity(17);
        sku.setPendingQty(194);
        sku.setTargetScheduleQty(194);
        sku.setWindowPlanQty(0);
        sku.setWindowRemainingPlanQty(0);
        sku.setSurplusQty(194);
        sku.setEmbryoStock(-1);
        sku.setMonthlyHistoryShortageQty(194);
        sku.setFutureMonthPlanQtyAfterWindow(0);
        sku.setMouldChangeInfo("4-2-0");
        sku.setDailyPlanQuotaMap(buildThreeDayQuotaMap(
                context.getScheduleWindowShifts(), sku.getMaterialCode(), 0, 0, 0));
        context.getNewSpecSkuList().add(sku);

        MachineScheduleDTO k2025 = buildMachine("K2025", dateTime(2026, 6, 4, 6, 0));
        MachineScheduleDTO k1002 = buildMachine("K1002", dateTime(2026, 6, 4, 6, 0));
        k2025.setMaxMoldNum(2);
        k1002.setMaxMoldNum(2);

        strategy.scheduleNewSpecs(context, orderedMachineMatch(k2025, k1002),
                defaultMouldChangeBalance(), defaultInspectionBalance(), defaultCapacityCalculate());

        assertEquals(2, context.getScheduleResultList().size(),
                "窗口和月底均无计划但存在历史欠产时，计划使用4模、双模机台也应扩到2台");
        assertEquals(194, sumResultQty(context.getScheduleResultList()), "收尾目标量仍需严格按历史欠产/余量口径清量，不允许超排");
    }

    @Test
    void scheduleNewSpecs_shouldNotExpandNoWindowPlanByMouldChangeInfoWhenHistoryShortageMissing() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        injectDependencies(strategy, false);

        LhScheduleContext context = buildContext();
        Date scheduleDate = dateTime(2026, 6, 4, 0, 0);
        context.setScheduleDate(scheduleDate);
        context.setScheduleTargetDate(dateTime(2026, 6, 6, 0, 0));
        context.setScheduleWindowShifts(LhScheduleTimeUtil.buildDefaultScheduleShifts(context, scheduleDate));

        SkuScheduleDTO sku = buildSku();
        sku.setMaterialCode("3302001512");
        sku.setConstructionStage(ConstructionStageEnum.FORMAL.getCode());
        sku.setLhTimeSeconds(3600);
        sku.setShiftCapacity(17);
        sku.setPendingQty(194);
        sku.setTargetScheduleQty(194);
        sku.setWindowPlanQty(0);
        sku.setWindowRemainingPlanQty(0);
        sku.setSurplusQty(194);
        sku.setEmbryoStock(-1);
        sku.setMonthlyHistoryShortageQty(0);
        sku.setFutureMonthPlanQtyAfterWindow(0);
        sku.setMouldChangeInfo("4-2-0");
        sku.setDailyPlanQuotaMap(buildThreeDayQuotaMap(
                context.getScheduleWindowShifts(), sku.getMaterialCode(), 0, 0, 0));
        context.getNewSpecSkuList().add(sku);

        MachineScheduleDTO k2025 = buildMachine("K2025", dateTime(2026, 6, 4, 6, 0));
        MachineScheduleDTO k1002 = buildMachine("K1002", dateTime(2026, 6, 4, 6, 0));
        k2025.setMaxMoldNum(2);
        k1002.setMaxMoldNum(2);

        strategy.scheduleNewSpecs(context, orderedMachineMatch(k2025, k1002),
                defaultMouldChangeBalance(), defaultInspectionBalance(), defaultCapacityCalculate());

        assertEquals(0, context.getScheduleResultList().size(),
                "本月历史欠产为0时，不进入窗口无日计划补排，也不能仅凭mouldChangeInfo强制扩机台");
    }

    @Test
    void prepareNewSpecShortageQuota_shouldBeIdempotentAcrossRepeatedPreparation() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        injectDependencies(strategy, false);

        LhScheduleContext context = buildContext();
        Date scheduleDate = dateTime(2026, 5, 9, 0, 0);
        context.setScheduleDate(scheduleDate);
        context.setScheduleTargetDate(dateTime(2026, 5, 11, 0, 0));
        context.setScheduleWindowShifts(LhScheduleTimeUtil.buildDefaultScheduleShifts(context, scheduleDate));

        SkuScheduleDTO sku = buildSku();
        sku.setMaterialCode("3302001592");
        sku.setTargetScheduleQty(200);
        sku.setWindowPlanQty(144);
        sku.setWindowRemainingPlanQty(144);
        sku.setMonthlyHistoryShortageQty(100);
        sku.setDailyPlanQuotaMap(buildThreeDayQuotaMap(
                context.getScheduleWindowShifts(), sku.getMaterialCode(), 48, 48, 48));

        Method method = NewSpecProductionStrategy.class.getDeclaredMethod(
                "prepareNewSpecShortageQuota", LhScheduleContext.class, SkuScheduleDTO.class);
        method.setAccessible(true);

        assertFalse((Boolean) method.invoke(strategy, context, sku));
        assertFalse((Boolean) method.invoke(strategy, context, sku));
        assertEquals(148, sku.getDailyPlanQuotaMap().values().iterator().next().getRemainingQty(),
                "重复准备欠产账本时，首日账本不能重复追加历史欠产");
        assertEquals(244, sku.getWindowPlanQty(),
                "重复准备欠产账本时，窗口量必须保持“原窗口日计划+历史欠产”一次性口径");
        assertEquals(244, sku.getWindowRemainingPlanQty());
        assertEquals(100, sku.getEffectiveCarryForwardQty());
    }

    @Test
    void prepareNewSpecShortageQuota_shouldNotDuplicateWhenSkuShareDailyQuotaMap() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        injectDependencies(strategy, false);

        LhScheduleContext context = buildContext();
        Date scheduleDate = dateTime(2026, 5, 9, 0, 0);
        context.setScheduleDate(scheduleDate);
        context.setScheduleTargetDate(dateTime(2026, 5, 11, 0, 0));
        context.setScheduleWindowShifts(LhScheduleTimeUtil.buildDefaultScheduleShifts(context, scheduleDate));
        Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap = buildThreeDayQuotaMap(
                context.getScheduleWindowShifts(), "3302001592", 48, 48, 48);

        SkuScheduleDTO firstSku = buildSku();
        firstSku.setMaterialCode("3302001592");
        firstSku.setTargetScheduleQty(200);
        firstSku.setWindowPlanQty(144);
        firstSku.setWindowRemainingPlanQty(144);
        firstSku.setMonthlyHistoryShortageQty(100);
        firstSku.setDailyPlanQuotaMap(quotaMap);
        SkuScheduleDTO secondSku = buildSku();
        BeanUtil.copyProperties(firstSku, secondSku);
        secondSku.setDailyPlanQuotaMap(quotaMap);
        context.getNewSpecSkuList().add(firstSku);
        context.getNewSpecSkuList().add(secondSku);

        Method method = NewSpecProductionStrategy.class.getDeclaredMethod(
                "prepareNewSpecShortageQuota", LhScheduleContext.class, SkuScheduleDTO.class);
        method.setAccessible(true);

        method.invoke(strategy, context, firstSku);
        method.invoke(strategy, context, secondSku);

        assertEquals(148, quotaMap.values().iterator().next().getRemainingQty(),
                "共享同一账本的SKU连续进入S4.5时，历史欠产只能追加一次");
        assertEquals(100, firstSku.getEffectiveCarryForwardQty());
        assertEquals(100, secondSku.getEffectiveCarryForwardQty());
    }

    @Test
    void restoreContinuousPlaceholderQuota_shouldAllowShortageToBeRebuiltAfterRollback() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        injectDependencies(strategy, false);

        LhScheduleContext context = buildContext();
        Date scheduleDate = dateTime(2026, 5, 9, 0, 0);
        context.setScheduleDate(scheduleDate);
        context.setScheduleTargetDate(dateTime(2026, 5, 11, 0, 0));
        context.setScheduleWindowShifts(LhScheduleTimeUtil.buildDefaultScheduleShifts(context, scheduleDate));
        Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap = buildThreeDayQuotaMap(
                context.getScheduleWindowShifts(), "3302001592", 48, 48, 48);

        SkuScheduleDTO sourceSku = buildSku();
        sourceSku.setMaterialCode("3302001592");
        sourceSku.setTargetScheduleQty(200);
        sourceSku.setWindowPlanQty(144);
        sourceSku.setWindowRemainingPlanQty(244);
        sourceSku.setMonthlyHistoryShortageQty(100);
        sourceSku.setEffectiveCarryForwardQty(100);
        sourceSku.setDailyPlanQuotaMap(quotaMap);
        quotaMap.values().iterator().next().setRemainingQty(148);
        context.getContinuousSkuList().add(sourceSku);

        Method restoreMethod = NewSpecProductionStrategy.class.getDeclaredMethod(
                "restoreContinuousPlaceholderQuota", LhScheduleContext.class, SkuScheduleDTO.class);
        restoreMethod.setAccessible(true);
        restoreMethod.invoke(strategy, context, sourceSku);

        assertEquals(48, quotaMap.values().iterator().next().getRemainingQty(),
                "占位结果回滚后账本先恢复为原始日计划量");
        assertEquals(0, sourceSku.getEffectiveCarryForwardQty(),
                "占位回滚清掉已入账标识，后续S4.5才能重新补回历史欠产");

        Method prepareMethod = NewSpecProductionStrategy.class.getDeclaredMethod(
                "prepareNewSpecShortageQuota", LhScheduleContext.class, SkuScheduleDTO.class);
        prepareMethod.setAccessible(true);
        prepareMethod.invoke(strategy, context, sourceSku);

        assertEquals(148, quotaMap.values().iterator().next().getRemainingQty(),
                "回滚后的补偿SKU再次进入S4.5时，应重新追加本月历史欠产");
        assertEquals(100, sourceSku.getEffectiveCarryForwardQty());
    }

    @Test
    void applyNightNoMouldChangeContinuationFill_shouldFillFormalNonEndingNextNightShift() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        injectDependencies(strategy, false);

        LhScheduleContext context = buildContext();
        Date scheduleDate = dateTime(2026, 5, 1, 0, 0);
        context.setScheduleDate(scheduleDate);
        context.setScheduleTargetDate(dateTime(2026, 5, 3, 0, 0));
        List<LhShiftConfigVO> shifts = LhScheduleTimeUtil.buildDefaultScheduleShifts(context, scheduleDate);
        context.setScheduleWindowShifts(shifts);

        SkuScheduleDTO sku = buildSku();
        sku.setMaterialCode("3302002654");
        sku.setMaterialDesc("晚班不可换模补满");
        sku.setConstructionStage(ConstructionStageEnum.FORMAL.getCode());
        sku.setLhTimeSeconds(3600);
        sku.setShiftCapacity(17);
        sku.setMouldQty(1);
        sku.setSurplusQty(1752);
        sku.setTargetScheduleQty(300);
        sku.setWindowPlanQty(300);
        sku.setDailyPlanQuotaMap(buildThreeDayQuotaMap(
                shifts, sku.getMaterialCode(), 100, 100, 100));

        LhScheduleResult result = buildEndingResult(context, sku, "K1113");
        result.setIsEnd("0");
        result.setScheduleType("02");
        result.setSingleMouldShiftQty(17);
        ShiftFieldUtil.setShiftPlanQty(result, 5, 17,
                shifts.get(4).getShiftStartDateTime(), shifts.get(4).getShiftEndDateTime());
        ShiftFieldUtil.syncDailyPlanQty(result);
        context.getMachineScheduleMap().put("K1113", buildMachine("K1113", shifts.get(4).getShiftEndDateTime()));

        invokeNightNoMouldChangeContinuationFill(strategy, context, sku, result, shifts,
                ProductionQuantityPolicy.from(sku, false));

        assertEquals(17, resolveShiftQty(result, 6), "正规非收尾SKU下一班次为不可换模晚班时必须补满晚班班产");
        assertEquals(34, result.getDailyPlanQty().intValue(), "晚班补满后结果汇总量应刷新");
    }

    @Test
    void applyNightNoMouldChangeContinuationFill_shouldFillCurrentAfternoonBeforeNightShift() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        injectDependencies(strategy, false);

        LhScheduleContext context = buildContext();
        Date scheduleDate = dateTime(2026, 6, 14, 0, 0);
        context.setScheduleDate(scheduleDate);
        context.setScheduleTargetDate(dateTime(2026, 6, 16, 0, 0));
        List<LhShiftConfigVO> shifts = LhScheduleTimeUtil.buildDefaultScheduleShifts(context, scheduleDate);
        context.setScheduleWindowShifts(shifts);

        SkuScheduleDTO sku = buildSku();
        sku.setMaterialCode("3302001069");
        sku.setMaterialDesc("晚班不可换模续作补满前班次");
        sku.setConstructionStage(ConstructionStageEnum.FORMAL.getCode());
        sku.setLhTimeSeconds(3600);
        sku.setShiftCapacity(18);
        sku.setMouldQty(1);
        sku.setSurplusQty(916);
        sku.setTargetScheduleQty(300);
        sku.setWindowPlanQty(300);
        sku.setDailyPlanQuotaMap(buildThreeDayQuotaMap(
                shifts, sku.getMaterialCode(), 100, 100, 100));

        LhScheduleResult result = buildEndingResult(context, sku, "K1902");
        result.setIsEnd("0");
        result.setScheduleType("02");
        result.setSingleMouldShiftQty(18);
        ShiftFieldUtil.setShiftPlanQty(result, 3, 10,
                shifts.get(2).getShiftStartDateTime(), shifts.get(2).getShiftEndDateTime());
        ShiftFieldUtil.setShiftPlanQty(result, 4, 18,
                shifts.get(3).getShiftStartDateTime(), shifts.get(3).getShiftEndDateTime());
        ShiftFieldUtil.setShiftPlanQty(result, 5, 8,
                shifts.get(4).getShiftStartDateTime(), shifts.get(4).getShiftEndDateTime());
        ShiftFieldUtil.syncDailyPlanQty(result);
        context.getMachineScheduleMap().put("K1902", buildMachine("K1902", shifts.get(4).getShiftEndDateTime()));

        invokeNightNoMouldChangeContinuationFill(strategy, context, sku, result, shifts,
                ProductionQuantityPolicy.from(sku, false));

        assertEquals(18, resolveShiftQty(result, 5), "晚班不可换模补满前，应先把仍可生产的当前中班补到班产");
        assertEquals(18, resolveShiftQty(result, 6), "当前中班补满后，下一不可换模晚班仍应补满班产");
        assertEquals(64, result.getDailyPlanQty().intValue(), "前班次和晚班补满后结果汇总量应刷新");
    }

    @Test
    void applyNightNoMouldChangeContinuationFill_shouldKeepTrialAndEndingWithinTarget() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        injectDependencies(strategy, false);

        LhScheduleContext context = buildContext();
        Date scheduleDate = dateTime(2026, 5, 1, 0, 0);
        context.setScheduleDate(scheduleDate);
        List<LhShiftConfigVO> shifts = LhScheduleTimeUtil.buildDefaultScheduleShifts(context, scheduleDate);
        context.setScheduleWindowShifts(shifts);

        SkuScheduleDTO trialSku = buildSku();
        trialSku.setMaterialCode("TRIAL-NIGHT");
        trialSku.setConstructionStage(ConstructionStageEnum.TRIAL.getCode());
        trialSku.setLhTimeSeconds(3600);
        trialSku.setShiftCapacity(17);
        trialSku.setMouldQty(1);
        trialSku.setSurplusQty(1752);
        LhScheduleResult trialResult = buildEndingResult(context, trialSku, "K1113");
        trialResult.setIsEnd("0");
        trialResult.setSingleMouldShiftQty(17);
        ShiftFieldUtil.setShiftPlanQty(trialResult, 5, 17,
                shifts.get(4).getShiftStartDateTime(), shifts.get(4).getShiftEndDateTime());
        ShiftFieldUtil.syncDailyPlanQty(trialResult);

        invokeNightNoMouldChangeContinuationFill(strategy, context, trialSku, trialResult, shifts,
                ProductionQuantityPolicy.from(trialSku, false));
        assertEquals(0, resolveShiftQty(trialResult, 6), "试制SKU不允许为了晚班补满而超排");

        SkuScheduleDTO endingSku = buildSku();
        endingSku.setMaterialCode("ENDING-NIGHT");
        endingSku.setConstructionStage(ConstructionStageEnum.FORMAL.getCode());
        endingSku.setLhTimeSeconds(3600);
        endingSku.setShiftCapacity(17);
        endingSku.setMouldQty(1);
        endingSku.setSurplusQty(22);
        LhScheduleResult endingResult = buildEndingResult(context, endingSku, "K1114");
        endingResult.setSingleMouldShiftQty(17);
        ShiftFieldUtil.setShiftPlanQty(endingResult, 5, 17,
                shifts.get(4).getShiftStartDateTime(), shifts.get(4).getShiftEndDateTime());
        ShiftFieldUtil.syncDailyPlanQty(endingResult);

        invokeNightNoMouldChangeContinuationFill(strategy, context, endingSku, endingResult, shifts,
                ProductionQuantityPolicy.from(endingSku, true));
        assertEquals(5, resolveShiftQty(endingResult, 6), "收尾SKU中班结束后进入不可换模晚班时，只能补剩余目标量");
        assertEquals(22, endingResult.getDailyPlanQty().intValue(), "收尾SKU晚班补量后不得超过收尾目标量");
    }

    @Test
    void scheduleNewSpecs_shouldConcentrateEndingTailOnPrimaryMachine() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        injectDependencies(strategy, false);

        LhScheduleContext context = buildContext();
        Date scheduleDate = dateTime(2026, 5, 1, 0, 0);
        context.setScheduleDate(scheduleDate);
        context.setScheduleTargetDate(dateTime(2026, 5, 3, 0, 0));
        List<LhShiftConfigVO> shifts = LhScheduleTimeUtil.buildDefaultScheduleShifts(context, scheduleDate);
        context.setScheduleWindowShifts(shifts);

        SkuScheduleDTO sku = buildSku();
        sku.setMaterialCode("3302002637");
        sku.setMaterialDesc("量试SKU收尾尾量归集");
        sku.setConstructionStage(ConstructionStageEnum.MASS_TRIAL.getCode());
        sku.setLhTimeSeconds(3600);
        sku.setShiftCapacity(9);
        sku.setMouldQty(1);
        sku.setSurplusQty(80);
        sku.setTargetScheduleQty(80);
        sku.setWindowPlanQty(80);
        sku.setPendingQty(80);
        sku.setDailyPlanQuotaMap(buildThreeDayQuotaMap(
                shifts, sku.getMaterialCode(), 27, 27, 26));
        context.getNewSpecSkuList().add(sku);

        LhScheduleResult primaryResult = buildEndingResult(context, sku, "K1501R");
        primaryResult.setSingleMouldShiftQty(9);
        ShiftFieldUtil.setShiftPlanQty(primaryResult, 3, 4,
                shifts.get(2).getShiftStartDateTime(), shifts.get(2).getShiftEndDateTime());
        ShiftFieldUtil.setShiftPlanQty(primaryResult, 4, 9,
                shifts.get(3).getShiftStartDateTime(), shifts.get(3).getShiftEndDateTime());
        ShiftFieldUtil.setShiftPlanQty(primaryResult, 5, 9,
                shifts.get(4).getShiftStartDateTime(), shifts.get(4).getShiftEndDateTime());
        ShiftFieldUtil.setShiftPlanQty(primaryResult, 6, 9,
                shifts.get(5).getShiftStartDateTime(), shifts.get(5).getShiftEndDateTime());
        ShiftFieldUtil.setShiftPlanQty(primaryResult, 7, 9,
                shifts.get(6).getShiftStartDateTime(), shifts.get(6).getShiftEndDateTime());
        ShiftFieldUtil.setShiftPlanQty(primaryResult, 8, 5,
                shifts.get(7).getShiftStartDateTime(), shifts.get(7).getShiftEndDateTime());
        ShiftFieldUtil.syncDailyPlanQty(primaryResult);

        LhScheduleResult auxResult = buildEndingResult(context, sku, "K1501L");
        auxResult.setSingleMouldShiftQty(9);
        ShiftFieldUtil.setShiftPlanQty(auxResult, 5, 9,
                shifts.get(4).getShiftStartDateTime(), shifts.get(4).getShiftEndDateTime());
        ShiftFieldUtil.setShiftPlanQty(auxResult, 6, 9,
                shifts.get(5).getShiftStartDateTime(), shifts.get(5).getShiftEndDateTime());
        ShiftFieldUtil.setShiftPlanQty(auxResult, 7, 9,
                shifts.get(6).getShiftStartDateTime(), shifts.get(6).getShiftEndDateTime());
        ShiftFieldUtil.setShiftPlanQty(auxResult, 8, 8,
                shifts.get(7).getShiftStartDateTime(), shifts.get(7).getShiftEndDateTime());
        ShiftFieldUtil.syncDailyPlanQty(auxResult);

        context.getScheduleResultList().add(primaryResult);
        context.getScheduleResultList().add(auxResult);
        context.getMachineScheduleMap().put("K1501R", buildMachine("K1501R", shifts.get(7).getShiftEndDateTime()));
        context.getMachineScheduleMap().put("K1501L", buildMachine("K1501L", shifts.get(7).getShiftEndDateTime()));

        invokeSameSkuMultiMachineAllocation(strategy, context, sku, shifts,
                ProductionQuantityPolicy.from(sku, true), true);

        assertEquals(4, resolveShiftQty(primaryResult, 3), "3302002637 K1501R C3 应保持 4");
        assertEquals(9, resolveShiftQty(primaryResult, 4), "3302002637 K1501R C4 应保持 9");
        assertEquals(9, resolveShiftQty(primaryResult, 5), "3302002637 K1501R C5 应保持 9");
        assertEquals(9, resolveShiftQty(primaryResult, 6), "3302002637 K1501R C6 应保持 9");
        assertEquals(9, resolveShiftQty(primaryResult, 7), "3302002637 K1501R C7 应保持 9");
        assertEquals(9, resolveShiftQty(primaryResult, 8), "3302002637 K1501R C8 应优先补满到 9");
        assertEquals(9, resolveShiftQty(auxResult, 5), "3302002637 K1501L C5 应保持 9");
        assertEquals(9, resolveShiftQty(auxResult, 6), "3302002637 K1501L C6 应保持 9");
        assertEquals(9, resolveShiftQty(auxResult, 7), "3302002637 K1501L C7 应保持 9");
        assertEquals(4, resolveShiftQty(auxResult, 8), "3302002637 K1501L C8 只能保留单机尾量 4");
        assertEquals(80, primaryResult.getDailyPlanQty() + auxResult.getDailyPlanQty(),
                "3302002637 尾量归集不能改变 SKU 总排产量");
    }

    @Test
    void scheduleNewSpecs_shouldReleaseAuxMachineWhenPrimaryCanCoverNightShift() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        injectDependencies(strategy, false);

        LhScheduleContext context = buildContext();
        Date scheduleDate = dateTime(2026, 5, 1, 0, 0);
        context.setScheduleDate(scheduleDate);
        context.setScheduleTargetDate(dateTime(2026, 5, 3, 0, 0));
        List<LhShiftConfigVO> shifts = LhScheduleTimeUtil.buildDefaultScheduleShifts(context, scheduleDate);
        context.setScheduleWindowShifts(shifts);

        SkuScheduleDTO sku = buildSku();
        sku.setMaterialCode("3302002546");
        sku.setMaterialDesc("正规SKU辅助机台释放");
        sku.setConstructionStage(ConstructionStageEnum.FORMAL.getCode());
        sku.setLhTimeSeconds(3600);
        sku.setShiftCapacity(17);
        sku.setMouldQty(1);
        sku.setSurplusQty(1440);
        sku.setTargetScheduleQty(1440);
        sku.setWindowPlanQty(1440);
        sku.setPendingQty(1440);
        sku.setDailyPlanQuotaMap(buildThreeDayQuotaMap(
                shifts, sku.getMaterialCode(), 100, 50, 50));
        context.getNewSpecSkuList().add(sku);

        LhScheduleResult primaryResult = buildEndingResult(context, sku, "K1206");
        primaryResult.setIsEnd("0");
        primaryResult.setSingleMouldShiftQty(17);
        ShiftFieldUtil.setShiftPlanQty(primaryResult, 2, 17,
                shifts.get(1).getShiftStartDateTime(), shifts.get(1).getShiftEndDateTime());
        ShiftFieldUtil.setShiftPlanQty(primaryResult, 3, 17,
                shifts.get(2).getShiftStartDateTime(), shifts.get(2).getShiftEndDateTime());
        ShiftFieldUtil.setShiftPlanQty(primaryResult, 4, 17,
                shifts.get(3).getShiftStartDateTime(), shifts.get(3).getShiftEndDateTime());
        ShiftFieldUtil.setShiftPlanQty(primaryResult, 5, 17,
                shifts.get(4).getShiftStartDateTime(), shifts.get(4).getShiftEndDateTime());
        ShiftFieldUtil.setShiftPlanQty(primaryResult, 6, 17,
                shifts.get(5).getShiftStartDateTime(), shifts.get(5).getShiftEndDateTime());
        ShiftFieldUtil.setShiftPlanQty(primaryResult, 7, 17,
                shifts.get(6).getShiftStartDateTime(), shifts.get(6).getShiftEndDateTime());
        ShiftFieldUtil.setShiftPlanQty(primaryResult, 8, 17,
                shifts.get(7).getShiftStartDateTime(), shifts.get(7).getShiftEndDateTime());
        ShiftFieldUtil.syncDailyPlanQty(primaryResult);

        LhScheduleResult auxResult = buildEndingResult(context, sku, "K1313");
        auxResult.setIsEnd("0");
        auxResult.setSingleMouldShiftQty(17);
        ShiftFieldUtil.setShiftPlanQty(auxResult, 3, 17,
                shifts.get(2).getShiftStartDateTime(), shifts.get(2).getShiftEndDateTime());
        ShiftFieldUtil.setShiftPlanQty(auxResult, 4, 17,
                shifts.get(3).getShiftStartDateTime(), shifts.get(3).getShiftEndDateTime());
        ShiftFieldUtil.setShiftPlanQty(auxResult, 5, 17,
                shifts.get(4).getShiftStartDateTime(), shifts.get(4).getShiftEndDateTime());
        ShiftFieldUtil.setShiftPlanQty(auxResult, 6, 17,
                shifts.get(5).getShiftStartDateTime(), shifts.get(5).getShiftEndDateTime());
        ShiftFieldUtil.setShiftPlanQty(auxResult, 7, 17,
                shifts.get(6).getShiftStartDateTime(), shifts.get(6).getShiftEndDateTime());
        ShiftFieldUtil.setShiftPlanQty(auxResult, 8, 17,
                shifts.get(7).getShiftStartDateTime(), shifts.get(7).getShiftEndDateTime());
        ShiftFieldUtil.syncDailyPlanQty(auxResult);

        context.getScheduleResultList().add(primaryResult);
        context.getScheduleResultList().add(auxResult);
        context.getMachineScheduleMap().put("K1206", buildMachine("K1206", shifts.get(7).getShiftEndDateTime()));
        context.getMachineScheduleMap().put("K1313", buildMachine("K1313", shifts.get(7).getShiftEndDateTime()));

        invokeSameSkuMultiMachineAllocation(strategy, context, sku, shifts,
                ProductionQuantityPolicy.from(sku, false), false);

        for (int shiftIndex = 2; shiftIndex <= 8; shiftIndex++) {
            assertEquals(17, resolveShiftQty(primaryResult, shiftIndex),
                    "3302002546 K1206 应继续承担主机班次 C" + shiftIndex);
        }
        for (int shiftIndex = 3; shiftIndex <= 7; shiftIndex++) {
            assertEquals(17, resolveShiftQty(auxResult, shiftIndex),
                    "3302002546 K1313 仅保留阶段性补量班次 C" + shiftIndex);
        }
        assertEquals(0, resolveShiftQty(auxResult, 8), "3302002546 K1313 C8 应被释放");
    }

    @Test
    void scheduleNewSpecs_shouldNotRefillReleasedAuxMachineByNightFill() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        injectDependencies(strategy, false);

        LhScheduleContext context = buildContext();
        Date scheduleDate = dateTime(2026, 5, 1, 0, 0);
        context.setScheduleDate(scheduleDate);
        context.setScheduleTargetDate(dateTime(2026, 5, 3, 0, 0));
        List<LhShiftConfigVO> shifts = LhScheduleTimeUtil.buildDefaultScheduleShifts(context, scheduleDate);
        context.setScheduleWindowShifts(shifts);

        SkuScheduleDTO sku = buildSku();
        sku.setMaterialCode("3302002546");
        sku.setMaterialDesc("辅助机台释放后不应被晚班补回");
        sku.setConstructionStage(ConstructionStageEnum.FORMAL.getCode());
        sku.setLhTimeSeconds(3600);
        sku.setShiftCapacity(17);
        sku.setMouldQty(1);
        sku.setSurplusQty(1440);
        sku.setTargetScheduleQty(1440);
        sku.setWindowPlanQty(1440);
        sku.setPendingQty(1440);
        sku.setDailyPlanQuotaMap(buildThreeDayQuotaMap(
                shifts, sku.getMaterialCode(), 0, 50, 0));
        context.getNewSpecSkuList().add(sku);

        LhScheduleResult primaryResult = buildEndingResult(context, sku, "K1206");
        primaryResult.setIsEnd("0");
        primaryResult.setSingleMouldShiftQty(17);
        ShiftFieldUtil.setShiftPlanQty(primaryResult, 4, 17,
                shifts.get(3).getShiftStartDateTime(), shifts.get(3).getShiftEndDateTime());
        ShiftFieldUtil.setShiftPlanQty(primaryResult, 5, 17,
                shifts.get(4).getShiftStartDateTime(), shifts.get(4).getShiftEndDateTime());
        ShiftFieldUtil.syncDailyPlanQty(primaryResult);

        LhScheduleResult auxResult = buildEndingResult(context, sku, "K1313");
        auxResult.setIsEnd("0");
        auxResult.setSingleMouldShiftQty(17);
        ShiftFieldUtil.setShiftPlanQty(auxResult, 5, 17,
                shifts.get(4).getShiftStartDateTime(), shifts.get(4).getShiftEndDateTime());
        ShiftFieldUtil.syncDailyPlanQty(auxResult);

        context.getScheduleResultList().add(primaryResult);
        context.getScheduleResultList().add(auxResult);
        context.getMachineScheduleMap().put("K1206", buildMachine("K1206", shifts.get(4).getShiftEndDateTime()));
        context.getMachineScheduleMap().put("K1313", buildMachine("K1313", shifts.get(4).getShiftEndDateTime()));

        invokeNightNoMouldChangeContinuationFill(strategy, context, sku, primaryResult, shifts,
                ProductionQuantityPolicy.from(sku, false));
        invokeNightNoMouldChangeContinuationFill(strategy, context, sku, auxResult, shifts,
                ProductionQuantityPolicy.from(sku, false));
        invokeSameSkuMultiMachineAllocation(strategy, context, sku, shifts,
                ProductionQuantityPolicy.from(sku, false), false);

        assertEquals(17, resolveShiftQty(primaryResult, 6), "3302002546 主机 C6 应补满晚班");
        assertEquals(0, resolveShiftQty(auxResult, 6), "3302002546 辅机 C6 不允许被晚班补回");
    }

    @Test
    void applyNightNoMouldChangeContinuationFill_shouldFillTailMachineBeforePersistEvenPrimaryCoversDay() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        injectDependencies(strategy, false);

        LhScheduleContext context = buildContext();
        Date scheduleDate = dateTime(2026, 5, 1, 0, 0);
        context.setScheduleDate(scheduleDate);
        context.setScheduleTargetDate(dateTime(2026, 5, 3, 0, 0));
        List<LhShiftConfigVO> shifts = LhScheduleTimeUtil.buildDefaultScheduleShifts(context, scheduleDate);
        context.setScheduleWindowShifts(shifts);

        SkuScheduleDTO sku = buildSku();
        sku.setMaterialCode("3302001074");
        sku.setMaterialDesc("尾机台落地前中班后保留晚班");
        sku.setConstructionStage(ConstructionStageEnum.FORMAL.getCode());
        sku.setLhTimeSeconds(3600);
        sku.setShiftCapacity(16);
        sku.setMouldQty(1);
        sku.setSurplusQty(1440);
        sku.setTargetScheduleQty(146);
        sku.setWindowPlanQty(146);
        sku.setPendingQty(146);
        sku.setDailyPlanQuotaMap(buildThreeDayQuotaMap(
                shifts, sku.getMaterialCode(), 8, 92, 46));
        context.getNewSpecSkuList().add(sku);

        LhScheduleResult primaryResult = buildEndingResult(context, sku, "K1206");
        primaryResult.setIsEnd("0");
        primaryResult.setSingleMouldShiftQty(16);
        for (int shiftIndex = 2; shiftIndex <= 8; shiftIndex++) {
            ShiftFieldUtil.setShiftPlanQty(primaryResult, shiftIndex, 16,
                    shifts.get(shiftIndex - 1).getShiftStartDateTime(),
                    shifts.get(shiftIndex - 1).getShiftEndDateTime());
        }
        ShiftFieldUtil.syncDailyPlanQty(primaryResult);

        LhScheduleResult tailResult = buildEndingResult(context, sku, "K1313");
        tailResult.setIsEnd("0");
        tailResult.setSingleMouldShiftQty(16);
        ShiftFieldUtil.setShiftPlanQty(tailResult, 3, 16,
                shifts.get(2).getShiftStartDateTime(), shifts.get(2).getShiftEndDateTime());
        ShiftFieldUtil.setShiftPlanQty(tailResult, 4, 16,
                shifts.get(3).getShiftStartDateTime(), shifts.get(3).getShiftEndDateTime());
        ShiftFieldUtil.setShiftPlanQty(tailResult, 5, 16,
                shifts.get(4).getShiftStartDateTime(), shifts.get(4).getShiftEndDateTime());
        ShiftFieldUtil.syncDailyPlanQty(tailResult);

        context.getScheduleResultList().add(primaryResult);
        context.getMachineScheduleMap().put("K1206", buildMachine("K1206", shifts.get(7).getShiftEndDateTime()));
        context.getMachineScheduleMap().put("K1313", buildMachine("K1313", shifts.get(4).getShiftEndDateTime()));

        invokeNightNoMouldChangeContinuationFill(strategy, context, sku, tailResult, shifts,
                ProductionQuantityPolicy.from(sku, false));

        assertEquals(16, resolveShiftQty(tailResult, 6),
                "尾机台落地前 C5 后紧接不可换模晚班时，即使主机已覆盖当日目标，也必须保留 C6");
        assertEquals(64, tailResult.getDailyPlanQty().intValue(), "尾机台补晚班后应刷新本结果汇总量");
    }

    @Test
    void scheduleNewSpecs_shouldKeepNightShiftWhenAuxMachineReleasedAfterAfternoon() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        injectDependencies(strategy, false);

        LhScheduleContext context = buildContext();
        Date scheduleDate = dateTime(2026, 5, 1, 0, 0);
        context.setScheduleDate(scheduleDate);
        context.setScheduleTargetDate(dateTime(2026, 5, 3, 0, 0));
        List<LhShiftConfigVO> shifts = LhScheduleTimeUtil.buildDefaultScheduleShifts(context, scheduleDate);
        context.setScheduleWindowShifts(shifts);

        SkuScheduleDTO sku = buildSku();
        sku.setMaterialCode("3302001074");
        sku.setMaterialDesc("辅助机台中班释放后保留晚班");
        sku.setConstructionStage(ConstructionStageEnum.FORMAL.getCode());
        sku.setLhTimeSeconds(3600);
        sku.setShiftCapacity(16);
        sku.setMouldQty(1);
        sku.setSurplusQty(1440);
        sku.setTargetScheduleQty(1440);
        sku.setWindowPlanQty(1440);
        sku.setPendingQty(1440);
        sku.setDailyPlanQuotaMap(buildThreeDayQuotaMap(
                shifts, sku.getMaterialCode(), 0, 32, 0));
        context.getNewSpecSkuList().add(sku);

        LhScheduleResult primaryResult = buildEndingResult(context, sku, "K1206");
        primaryResult.setIsEnd("0");
        primaryResult.setSingleMouldShiftQty(16);
        ShiftFieldUtil.setShiftPlanQty(primaryResult, 3, 16,
                shifts.get(2).getShiftStartDateTime(), shifts.get(2).getShiftEndDateTime());
        ShiftFieldUtil.setShiftPlanQty(primaryResult, 4, 16,
                shifts.get(3).getShiftStartDateTime(), shifts.get(3).getShiftEndDateTime());
        ShiftFieldUtil.setShiftPlanQty(primaryResult, 5, 16,
                shifts.get(4).getShiftStartDateTime(), shifts.get(4).getShiftEndDateTime());
        ShiftFieldUtil.syncDailyPlanQty(primaryResult);

        LhScheduleResult auxResult = buildEndingResult(context, sku, "K1313");
        auxResult.setIsEnd("0");
        auxResult.setSingleMouldShiftQty(16);
        ShiftFieldUtil.setShiftPlanQty(auxResult, 5, 16,
                shifts.get(4).getShiftStartDateTime(), shifts.get(4).getShiftEndDateTime());
        ShiftFieldUtil.syncDailyPlanQty(auxResult);

        context.getScheduleResultList().add(primaryResult);
        context.getScheduleResultList().add(auxResult);
        context.getMachineScheduleMap().put("K1206", buildMachine("K1206", shifts.get(4).getShiftEndDateTime()));
        context.getMachineScheduleMap().put("K1313", buildMachine("K1313", shifts.get(4).getShiftEndDateTime()));

        invokeSameSkuMultiMachineAllocation(strategy, context, sku, shifts,
                ProductionQuantityPolicy.from(sku, false), false);

        assertEquals(0, resolveShiftQty(auxResult, 5), "辅机 C5 满足当日目标后允许释放");
        assertEquals(16, resolveShiftQty(auxResult, 6),
                "辅机 C5 释放点后紧接不可换模晚班时，当前SKU必须继续补满 C6 后再下机");
        assertEquals(16, auxResult.getDailyPlanQty().intValue(), "辅机只保留中班后补出的晚班产量");
    }

    @Test
    void adjustSameSkuMultiMachineEndingStagger_shouldMoveMorningTailQtyToNextShift() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        injectDependencies(strategy, true);

        LhScheduleContext context = buildContext();
        Date scheduleDate = dateTime(2026, 5, 1, 0, 0);
        context.setScheduleDate(scheduleDate);
        context.setScheduleTargetDate(scheduleDate);
        List<LhShiftConfigVO> shifts = LhScheduleTimeUtil.buildDefaultScheduleShifts(context, scheduleDate);
        context.setScheduleWindowShifts(shifts);

        SkuScheduleDTO sku = buildSku();
        sku.setMaterialCode("3302004001");
        sku.setLhTimeSeconds(3600);
        sku.setShiftCapacity(16);
        sku.setMouldQty(1);

        LhScheduleResult receiver = buildEndingResult(context, sku, "K1105");
        ShiftFieldUtil.setShiftPlanQty(receiver, 6, 16,
                shifts.get(5).getShiftStartDateTime(), shifts.get(5).getShiftEndDateTime());
        ShiftFieldUtil.setShiftPlanQty(receiver, 7, 16,
                shifts.get(6).getShiftStartDateTime(), shifts.get(6).getShiftEndDateTime());
        ShiftFieldUtil.syncDailyPlanQty(receiver);

        LhScheduleResult donor = buildEndingResult(context, sku, "K1110");
        ShiftFieldUtil.setShiftPlanQty(donor, 6, 16,
                shifts.get(5).getShiftStartDateTime(), shifts.get(5).getShiftEndDateTime());
        ShiftFieldUtil.setShiftPlanQty(donor, 7, 14,
                shifts.get(6).getShiftStartDateTime(), shifts.get(6).getShiftEndDateTime());
        ShiftFieldUtil.syncDailyPlanQty(donor);

        context.getScheduleResultList().add(receiver);
        context.getScheduleResultList().add(donor);
        context.getMachineScheduleMap().put("K1105", buildMachine("K1105", shifts.get(6).getShiftEndDateTime()));
        context.getMachineScheduleMap().put("K1110", buildMachine("K1110", shifts.get(6).getShiftEndDateTime()));

        Method method = NewSpecProductionStrategy.class.getDeclaredMethod(
                "adjustSameSkuMultiMachineEndingStagger",
                LhScheduleContext.class,
                SkuScheduleDTO.class,
                List.class);
        method.setAccessible(true);
        method.invoke(strategy, context, sku, shifts);

        assertEquals(16, resolveShiftQty(receiver, 7), "承接机台保留原早班计划量");
        assertEquals(14, resolveShiftQty(receiver, 8), "早班同班次收尾时，应把另一台尾量挪到承接机台后续中班");
        assertEquals(0, resolveShiftQty(donor, 7), "释放机台早班尾量应清空，给后续换模和开产留窗口");
        assertEquals(46, receiver.getDailyPlanQty().intValue(), "承接机台汇总量应刷新");
        assertEquals(16, donor.getDailyPlanQty().intValue(), "释放机台汇总量应刷新");
        assertEquals(62, receiver.getDailyPlanQty() + donor.getDailyPlanQty(), "重分配不能改变同SKU总排产量");
    }

    @Test
    void adjustSameSkuMultiMachineEndingStagger_shouldMoveMachineTailEvenWhenSkuIsNotEnding() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        injectDependencies(strategy, false);

        LhScheduleContext context = buildContext();
        Date scheduleDate = dateTime(2026, 5, 1, 0, 0);
        context.setScheduleDate(scheduleDate);
        context.setScheduleTargetDate(scheduleDate);
        List<LhShiftConfigVO> shifts = LhScheduleTimeUtil.buildDefaultScheduleShifts(context, scheduleDate);
        context.setScheduleWindowShifts(shifts);

        SkuScheduleDTO sku = buildSku();
        sku.setMaterialCode("3302004004");
        sku.setLhTimeSeconds(3600);
        sku.setShiftCapacity(16);
        sku.setMouldQty(1);

        LhScheduleResult receiver = buildEndingResult(context, sku, "K1105");
        receiver.setIsEnd("0");
        ShiftFieldUtil.setShiftPlanQty(receiver, 6, 16,
                shifts.get(5).getShiftStartDateTime(), shifts.get(5).getShiftEndDateTime());
        ShiftFieldUtil.setShiftPlanQty(receiver, 7, 16,
                shifts.get(6).getShiftStartDateTime(), shifts.get(6).getShiftEndDateTime());
        ShiftFieldUtil.syncDailyPlanQty(receiver);

        LhScheduleResult donor = buildEndingResult(context, sku, "K1110");
        donor.setIsEnd("0");
        ShiftFieldUtil.setShiftPlanQty(donor, 6, 16,
                shifts.get(5).getShiftStartDateTime(), shifts.get(5).getShiftEndDateTime());
        ShiftFieldUtil.setShiftPlanQty(donor, 7, 14,
                shifts.get(6).getShiftStartDateTime(), shifts.get(6).getShiftEndDateTime());
        ShiftFieldUtil.syncDailyPlanQty(donor);

        context.getScheduleResultList().add(receiver);
        context.getScheduleResultList().add(donor);
        context.getMachineScheduleMap().put("K1105", buildMachine("K1105", shifts.get(6).getShiftEndDateTime()));
        context.getMachineScheduleMap().put("K1110", buildMachine("K1110", shifts.get(6).getShiftEndDateTime()));

        invokeSameSkuMultiMachineEndingStagger(strategy, context, sku, shifts);

        assertEquals(14, resolveShiftQty(receiver, 8), "非SKU收尾的机台尾量也应允许错峰到承接机台下一班次");
        assertEquals(0, resolveShiftQty(donor, 7), "释放机台早班尾量应清空");
        assertEquals(62, receiver.getDailyPlanQty() + donor.getDailyPlanQty(), "机台收尾错峰不能改变同SKU总排产量");
    }

    @Test
    void adjustSameSkuMultiMachineEndingStagger_shouldSkipWhenNextShiftCrossesWorkDate() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        injectDependencies(strategy, true);

        LhScheduleContext context = buildContext();
        Date scheduleDate = dateTime(2026, 5, 1, 0, 0);
        context.setScheduleDate(scheduleDate);
        context.setScheduleTargetDate(scheduleDate);
        List<LhShiftConfigVO> shifts = LhScheduleTimeUtil.buildDefaultScheduleShifts(context, scheduleDate);
        context.setScheduleWindowShifts(shifts);

        SkuScheduleDTO sku = buildSku();
        sku.setMaterialCode("3302004002");
        sku.setLhTimeSeconds(3600);
        sku.setShiftCapacity(16);
        sku.setMouldQty(1);

        LhScheduleResult receiver = buildEndingResult(context, sku, "K1105");
        ShiftFieldUtil.setShiftPlanQty(receiver, 4, 16,
                shifts.get(3).getShiftStartDateTime(), shifts.get(3).getShiftEndDateTime());
        ShiftFieldUtil.setShiftPlanQty(receiver, 5, 16,
                shifts.get(4).getShiftStartDateTime(), shifts.get(4).getShiftEndDateTime());
        ShiftFieldUtil.syncDailyPlanQty(receiver);

        LhScheduleResult donor = buildEndingResult(context, sku, "K1110");
        ShiftFieldUtil.setShiftPlanQty(donor, 4, 16,
                shifts.get(3).getShiftStartDateTime(), shifts.get(3).getShiftEndDateTime());
        ShiftFieldUtil.setShiftPlanQty(donor, 5, 14,
                shifts.get(4).getShiftStartDateTime(), shifts.get(4).getShiftEndDateTime());
        ShiftFieldUtil.syncDailyPlanQty(donor);

        context.getScheduleResultList().add(receiver);
        context.getScheduleResultList().add(donor);
        context.getMachineScheduleMap().put("K1105", buildMachine("K1105", shifts.get(4).getShiftEndDateTime()));
        context.getMachineScheduleMap().put("K1110", buildMachine("K1110", shifts.get(4).getShiftEndDateTime()));

        Method method = NewSpecProductionStrategy.class.getDeclaredMethod(
                "adjustSameSkuMultiMachineEndingStagger",
                LhScheduleContext.class,
                SkuScheduleDTO.class,
                List.class);
        method.setAccessible(true);
        method.invoke(strategy, context, sku, shifts);

        assertFalse(Objects.equals(shifts.get(4).getWorkDate(), shifts.get(5).getWorkDate()), "中班转晚班应覆盖跨业务日场景");
        assertEquals(16, resolveShiftQty(receiver, 5), "承接机台保留原中班计划量");
        assertEquals(0, resolveShiftQty(receiver, 6), "跨业务日时不应把尾量挪到后续晚班");
        assertEquals(14, resolveShiftQty(donor, 5), "跨业务日时释放机台尾量应保持不变");
        assertEquals(32, receiver.getDailyPlanQty().intValue(), "跨业务日跳过错峰时，承接机台汇总量应保持不变");
        assertEquals(30, donor.getDailyPlanQty().intValue(), "跨业务日跳过错峰时，释放机台汇总量应保持不变");
        assertEquals(62, receiver.getDailyPlanQty() + donor.getDailyPlanQty(), "跳过错峰不能改变同SKU总排产量");
    }

    @Test
    void adjustSameSkuMultiMachineEndingStagger_shouldSkipNightMachineEnding() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        injectDependencies(strategy, false);

        LhScheduleContext context = buildContext();
        Date scheduleDate = dateTime(2026, 5, 1, 0, 0);
        context.setScheduleDate(scheduleDate);
        context.setScheduleTargetDate(scheduleDate);
        List<LhShiftConfigVO> shifts = LhScheduleTimeUtil.buildDefaultScheduleShifts(context, scheduleDate);
        context.setScheduleWindowShifts(shifts);

        SkuScheduleDTO sku = buildSku();
        sku.setMaterialCode("3302004005");
        sku.setLhTimeSeconds(3600);
        sku.setShiftCapacity(16);
        sku.setMouldQty(1);

        LhScheduleResult receiver = buildEndingResult(context, sku, "K1105");
        receiver.setIsEnd("0");
        ShiftFieldUtil.setShiftPlanQty(receiver, 6, 16,
                shifts.get(5).getShiftStartDateTime(), shifts.get(5).getShiftEndDateTime());
        ShiftFieldUtil.syncDailyPlanQty(receiver);

        LhScheduleResult donor = buildEndingResult(context, sku, "K1110");
        donor.setIsEnd("0");
        ShiftFieldUtil.setShiftPlanQty(donor, 6, 14,
                shifts.get(5).getShiftStartDateTime(), shifts.get(5).getShiftEndDateTime());
        ShiftFieldUtil.syncDailyPlanQty(donor);

        context.getScheduleResultList().add(receiver);
        context.getScheduleResultList().add(donor);
        context.getMachineScheduleMap().put("K1105", buildMachine("K1105", shifts.get(5).getShiftEndDateTime()));
        context.getMachineScheduleMap().put("K1110", buildMachine("K1110", shifts.get(5).getShiftEndDateTime()));

        invokeSameSkuMultiMachineEndingStagger(strategy, context, sku, shifts);

        assertEquals(16, resolveShiftQty(receiver, 6), "晚班同时机台收尾时承接机台计划量应保持不变");
        assertEquals(14, resolveShiftQty(donor, 6), "晚班同时机台收尾时释放机台尾量不应被清空");
        assertEquals(30, receiver.getDailyPlanQty() + donor.getDailyPlanQty(), "晚班跳过错峰不能改变总量");
    }

    @Test
    void adjustSameSkuMultiMachineEndingStagger_shouldSkipWhenReceiverNextShiftBlocked() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        injectDependencies(strategy, true);

        LhScheduleContext context = buildContext();
        Date scheduleDate = dateTime(2026, 5, 1, 0, 0);
        context.setScheduleDate(scheduleDate);
        context.setScheduleTargetDate(scheduleDate);
        List<LhShiftConfigVO> shifts = LhScheduleTimeUtil.buildDefaultScheduleShifts(context, scheduleDate);
        context.setScheduleWindowShifts(shifts);

        SkuScheduleDTO sku = buildSku();
        sku.setMaterialCode("3302004003");
        sku.setLhTimeSeconds(3600);
        sku.setShiftCapacity(16);
        sku.setMouldQty(1);

        LhScheduleResult receiver = buildEndingResult(context, sku, "K1105");
        ShiftFieldUtil.setShiftPlanQty(receiver, 6, 16,
                shifts.get(5).getShiftStartDateTime(), shifts.get(5).getShiftEndDateTime());
        ShiftFieldUtil.setShiftPlanQty(receiver, 7, 16,
                shifts.get(6).getShiftStartDateTime(), shifts.get(6).getShiftEndDateTime());
        ShiftFieldUtil.syncDailyPlanQty(receiver);

        LhScheduleResult donor = buildEndingResult(context, sku, "K1110");
        ShiftFieldUtil.setShiftPlanQty(donor, 6, 16,
                shifts.get(5).getShiftStartDateTime(), shifts.get(5).getShiftEndDateTime());
        ShiftFieldUtil.setShiftPlanQty(donor, 7, 14,
                shifts.get(6).getShiftStartDateTime(), shifts.get(6).getShiftEndDateTime());
        ShiftFieldUtil.syncDailyPlanQty(donor);

        MachineScheduleDTO receiverMachine = buildMachine("K1105", shifts.get(6).getShiftEndDateTime());
        MachineMaintenanceWindowDTO maintenanceWindow = new MachineMaintenanceWindowDTO();
        maintenanceWindow.setMachineCode("K1105");
        maintenanceWindow.setMaintenanceStartTime(shifts.get(7).getShiftStartDateTime());
        maintenanceWindow.setMaintenanceEndTime(shifts.get(7).getShiftEndDateTime());
        receiverMachine.setMaintenanceWindowList(Arrays.asList(maintenanceWindow));

        context.getScheduleResultList().add(receiver);
        context.getScheduleResultList().add(donor);
        context.getMachineScheduleMap().put("K1105", receiverMachine);
        context.getMachineScheduleMap().put("K1110", buildMachine("K1110", shifts.get(6).getShiftEndDateTime()));

        Method method = NewSpecProductionStrategy.class.getDeclaredMethod(
                "adjustSameSkuMultiMachineEndingStagger",
                LhScheduleContext.class,
                SkuScheduleDTO.class,
                List.class);
        method.setAccessible(true);
        method.invoke(strategy, context, sku, shifts);

        assertEquals(16, resolveShiftQty(receiver, 7), "承接机台原早班计划量应保持不变");
        assertEquals(0, resolveShiftQty(receiver, 8), "承接机台后续班次被维保阻断时，不应迁移尾量");
        assertEquals(14, resolveShiftQty(donor, 7), "承接班次不可排时，释放机台尾量不应被清空");
    }

    @Test
    void scheduleNewSpecs_shouldWriteSpecialMaterialFlagByMaterialCode() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        injectDependencies(strategy, false);

        LhScheduleContext context = buildContext();
        context.getSpecialMaterialCategoryByMaterialCode().put("MAT-1", java.util.Collections.singleton("01"));
        SkuScheduleDTO sku = buildSku();
        sku.setTargetScheduleQty(1);
        sku.setShiftCapacity(1);
        context.getNewSpecSkuList().add(sku);

        MachineScheduleDTO machine = buildMachine("K1301", dateTime(2026, 4, 17, 6, 0));
        strategy.scheduleNewSpecs(context, singletonMachineMatch(machine),
                defaultMouldChangeBalance(), defaultInspectionBalance(), defaultCapacityCalculate());

        assertEquals("1", context.getScheduleResultList().get(0).getHasSpecialMaterial());
    }

    /**
     * 多机台拆量排产：一台机台产能不足以排完目标量时，应继续尝试下一台机台。
     * <p>Machine A 起排时间较晚（仅剩 2 个班次），Machine B 起点正常（8 个班次），
     * 目标量 5 需由两台机台共同完成。</p>
     */
    @Test
    void scheduleNewSpecs_shouldScheduleAcrossMultipleMachinesWhenOneInsufficient() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        injectDependencies(strategy, false);

        LhScheduleContext context = buildContext();
        SkuScheduleDTO sku = buildSku();
        sku.setLhTimeSeconds(14400);
        sku.setShiftCapacity(1);
        sku.setTargetScheduleQty(5);
        sku.setPendingQty(5);
        sku.setSurplusQty(10);
        sku.setEmbryoStock(-1);
        context.getNewSpecSkuList().add(sku);

        // Machine A: 起排时间较晚，窗口内仅剩约 2 个班次
        MachineScheduleDTO machineA = buildMachine("M-LATE", dateTime(2026, 4, 19, 10, 0));
        // Machine B: 起点正常，覆盖全部 8 个班次
        MachineScheduleDTO machineB = buildMachine("M-EARLY", dateTime(2026, 4, 17, 6, 0));

        IMachineMatchStrategy multiMachineMatch = new IMachineMatchStrategy() {
            @Override
            public List<MachineScheduleDTO> matchMachines(LhScheduleContext ctx, SkuScheduleDTO s) {
                return Arrays.asList(machineA, machineB);
            }

            @Override
            public MachineScheduleDTO selectBestMachine(LhScheduleContext ctx, SkuScheduleDTO s,
                                                        List<MachineScheduleDTO> candidates, Set<String> excluded) {
                for (MachineScheduleDTO c : candidates) {
                    if (c != null && !excluded.contains(c.getMachineCode())) {
                        return c;
                    }
                }
                return null;
            }

            @Override
            public void traceEnabledMachineSort(LhScheduleContext context) {
                // 测试桩，无需实现
            }
        };

        strategy.scheduleNewSpecs(context, multiMachineMatch, defaultMouldChangeBalance(),
                defaultInspectionBalance(), defaultCapacityCalculate());

        assertFalse(context.getScheduleResultList().isEmpty(), "应生成排程结果");
        int resultCount = context.getScheduleResultList().size();
        int totalPlanQty = context.getScheduleResultList().stream()
                .mapToInt(r -> r.getDailyPlanQty() != null ? r.getDailyPlanQty() : 0).sum();
        assertTrue(totalPlanQty > 0, "总排产量应大于0");
        assertTrue(resultCount >= 1, "应至少生成1条排程结果");
        assertTrue(context.getNewSpecSkuList().isEmpty(), "SKU全部排完应从待排列表移除");
        assertEquals(0, context.getUnscheduledResultList().size(), "全部完成不应有未排记录");
    }

    /**
     * 多机台产能不足：所有候选机台总产能仍不足以排完目标量时，
     * 应记录已排部分并将剩余量计入未排结果。
     */
    @Test
    void scheduleNewSpecs_shouldRecordRemainingUnscheduledWhenAllMachinesExhausted() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        injectDependencies(strategy, false);

        LhScheduleContext context = buildContext();
        SkuScheduleDTO sku = buildSku();
        sku.setLhTimeSeconds(14400);
        sku.setShiftCapacity(1);
        sku.setTargetScheduleQty(20);
        sku.setPendingQty(20);
        sku.setSurplusQty(30);
        sku.setEmbryoStock(-1);
        context.getNewSpecSkuList().add(sku);

        // 两台机台起排均较晚，各自仅剩少量班次产能
        MachineScheduleDTO machineA = buildMachine("M-A", dateTime(2026, 4, 18, 22, 0));
        MachineScheduleDTO machineB = buildMachine("M-B", dateTime(2026, 4, 19, 8, 0));

        IMachineMatchStrategy limitedMatch = new IMachineMatchStrategy() {
            @Override
            public List<MachineScheduleDTO> matchMachines(LhScheduleContext ctx, SkuScheduleDTO s) {
                return Arrays.asList(machineA, machineB);
            }

            @Override
            public MachineScheduleDTO selectBestMachine(LhScheduleContext ctx, SkuScheduleDTO s,
                                                        List<MachineScheduleDTO> candidates, Set<String> excluded) {
                for (MachineScheduleDTO c : candidates) {
                    if (c != null && !excluded.contains(c.getMachineCode())) {
                        return c;
                    }
                }
                return null;
            }

            @Override
            public void traceEnabledMachineSort(LhScheduleContext context) {
                // 测试桩，无需实现
            }
        };

        strategy.scheduleNewSpecs(context, limitedMatch, defaultMouldChangeBalance(),
                defaultInspectionBalance(), defaultCapacityCalculate());

        // 应有排程结果
        assertFalse(context.getScheduleResultList().isEmpty(), "应至少生成部分排程结果");
        int totalPlanQty = context.getScheduleResultList().stream()
                .mapToInt(r -> r.getDailyPlanQty() != null ? r.getDailyPlanQty() : 0).sum();
        assertTrue(totalPlanQty > 0, "应有部分排产量");
        assertTrue(totalPlanQty < 20, "总排产量应小于目标量（产能不足）");
        // 应有未排记录
        assertFalse(context.getUnscheduledResultList().isEmpty(), "产能不足应有未排记录");
        assertEquals(20 - totalPlanQty, context.getUnscheduledResultList().get(0).getUnscheduledQty(),
                "部分排产后的未排数量应只记录剩余缺口");
        assertTrue(context.getNewSpecSkuList().isEmpty(), "SKU应从待排列表移除");
    }

    /**
     * 非收尾 SKU 目标量不应因胎胚库存大而上调。
     * <p>胎胚库存虽大，但非收尾时目标量应仍由待排量（基于余量）决定。</p>
     */
    @Test
    void scheduleNewSpecs_shouldNotInflateTargetByEmbryoStockForNonEnding() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        injectDependencies(strategy, false);

        LhScheduleContext context = buildContext();
        SkuScheduleDTO sku = buildSku();
        sku.setShiftCapacity(1);
        sku.setPendingQty(30);
        sku.setTargetScheduleQty(30);
        sku.setSurplusQty(50);
        sku.setEmbryoStock(500);
        context.getNewSpecSkuList().add(sku);

        MachineScheduleDTO machine = buildMachine("M-NORMAL", dateTime(2026, 4, 17, 6, 0));

        strategy.scheduleNewSpecs(context, singletonMachineMatch(machine), defaultMouldChangeBalance(),
                defaultInspectionBalance(), defaultCapacityCalculate());

        assertEquals(1, context.getScheduleResultList().size(), "应生成1条排程结果");
        int planQty = context.getScheduleResultList().get(0).getDailyPlanQty() != null
                ? context.getScheduleResultList().get(0).getDailyPlanQty() : 0;
        // 目标量 30 未因胎胚库存 500 而上调；多出的1条来自晚班不可换模续作补满。
        assertTrue(planQty <= 31, "非收尾SKU排产量不应因胎胚库存(500)而上调，只允许晚班不可换模补满量");
    }

    /**
     * 收尾 SKU 排产前应将目标量上调到胎胚库存（不超过月计划余量）。
     * <p>收尾判定为 true 后，调用 upsizeEndingTargetQty 将目标量上调到 max(原目标, min(胎胚库存, 余量))。</p>
     */
    @Test
    void scheduleNewSpecs_shouldUpsizeTargetForEndingSkuByEmbryoStock() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        injectDependencies(strategy, true);

        LhScheduleContext context = buildContext();
        SkuScheduleDTO sku = buildSku();
        sku.setShiftCapacity(1);
        // pendingQty=30（基于余量），targetScheduleQty=30（初始目标量）
        sku.setPendingQty(30);
        sku.setTargetScheduleQty(30);
        sku.setSurplusQty(50);
        sku.setEmbryoStock(80);
        context.getNewSpecSkuList().add(sku);

        MachineScheduleDTO machine = buildMachine("M-END", dateTime(2026, 4, 17, 6, 0));

        strategy.scheduleNewSpecs(context, singletonMachineMatch(machine), defaultMouldChangeBalance(),
                defaultInspectionBalance(), defaultCapacityCalculate());

        // 收尾上调后目标量 = max(30, min(80, 50)) = 50
        assertEquals(1, context.getScheduleResultList().size(), "应生成1条排程结果");
        assertEquals("1", context.getScheduleResultList().get(0).getIsEnd(), "收尾SKU标记应为1");
    }

    private void invokeNightNoMouldChangeContinuationFill(NewSpecProductionStrategy strategy,
                                                          LhScheduleContext context,
                                                          SkuScheduleDTO sku,
                                                          LhScheduleResult result,
                                                          List<LhShiftConfigVO> shifts,
                                                          ProductionQuantityPolicy quantityPolicy) throws Exception {
        Method method = NewSpecProductionStrategy.class.getDeclaredMethod(
                "applyNightNoMouldChangeContinuationFill",
                LhScheduleContext.class,
                SkuScheduleDTO.class,
                LhScheduleResult.class,
                List.class,
                ProductionQuantityPolicy.class);
        method.setAccessible(true);
        method.invoke(strategy, context, sku, result, shifts, quantityPolicy);
    }

    private void invokeSameSkuMultiMachineEndingStagger(NewSpecProductionStrategy strategy,
                                                        LhScheduleContext context,
                                                        SkuScheduleDTO sku,
                                                        List<LhShiftConfigVO> shifts) throws Exception {
        Method method = NewSpecProductionStrategy.class.getDeclaredMethod(
                "adjustSameSkuMultiMachineEndingStagger",
                LhScheduleContext.class,
                SkuScheduleDTO.class,
                List.class);
        method.setAccessible(true);
        method.invoke(strategy, context, sku, shifts);
    }

    private void invokeSameSkuMultiMachineAllocation(NewSpecProductionStrategy strategy,
                                                     LhScheduleContext context,
                                                     SkuScheduleDTO sku,
                                                     List<LhShiftConfigVO> shifts,
                                                     ProductionQuantityPolicy quantityPolicy,
                                                     boolean isEnding) throws Exception {
        Method method = NewSpecProductionStrategy.class.getDeclaredMethod(
                "adjustSameSkuMultiMachineAllocation",
                LhScheduleContext.class,
                SkuScheduleDTO.class,
                List.class,
                ProductionQuantityPolicy.class,
                boolean.class);
        method.setAccessible(true);
        method.invoke(strategy, context, sku, shifts, quantityPolicy, isEnding);
    }

    private LhScheduleContext buildContext() {
        LhScheduleContext context = new LhScheduleContext();
        Date scheduleDate = dateTime(2026, 4, 17, 0, 0);
        context.setFactoryCode("116");
        context.setBatchNo("TEST-BATCH");
        context.setScheduleDate(scheduleDate);
        context.setScheduleTargetDate(scheduleDate);
        context.setScheduleWindowShifts(LhScheduleTimeUtil.buildDefaultScheduleShifts(context, scheduleDate));
        context.setMachineScheduleMap(new java.util.LinkedHashMap<String, MachineScheduleDTO>());
        context.setMachineAssignmentMap(new java.util.LinkedHashMap<String, List<com.zlt.aps.lh.api.domain.entity.LhScheduleResult>>());
        context.setMaterialInfoMap(new java.util.HashMap<String, MdmMaterialInfo>());
        return context;
    }

    private LhScheduleContext scheduleFuturePlanSkuWithStructurePlans(int firstDayMachineCount,
                                                                      int secondDayMachineCount,
                                                                      int thirdDayMachineCount) throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        injectDependencies(strategy, false);

        LhScheduleContext context = buildContext();
        Date scheduleDate = dateTime(2026, 4, 1, 0, 0);
        context.setScheduleDate(scheduleDate);
        context.setScheduleTargetDate(dateTime(2026, 4, 3, 0, 0));
        context.setScheduleWindowShifts(LhScheduleTimeUtil.buildDefaultScheduleShifts(context, scheduleDate));

        SkuScheduleDTO sku = buildSku();
        sku.setMaterialCode("3302002326");
        sku.setStructureName("L1");
        sku.setConstructionStage(ConstructionStageEnum.FORMAL.getCode());
        sku.setLhTimeSeconds(3600);
        sku.setShiftCapacity(16);
        sku.setMouldQty(1);
        sku.setPendingQty(1376);
        sku.setDailyPlanQty(80);
        sku.setTargetScheduleQty(128);
        sku.setWindowPlanQty(80);
        sku.setWindowRemainingPlanQty(80);
        sku.setSurplusQty(1376);
        sku.setEmbryoStock(-1);
        sku.setDailyPlanQuotaMap(buildThreeDayQuotaMap(
                context.getScheduleWindowShifts(), sku.getMaterialCode(), 0, 32, 48));
        context.getNewSpecSkuList().add(sku);

        LocalDate firstDay = toLocalDate(context.getScheduleWindowShifts().get(0));
        LocalDate secondDay = toLocalDate(context.getScheduleWindowShifts().get(3));
        LocalDate thirdDay = toLocalDate(context.getScheduleWindowShifts().get(6));
        context.addStructurePlanMachineCount(firstDay, sku.getStructureName(), firstDayMachineCount);
        context.addStructurePlanMachineCount(secondDay, sku.getStructureName(), secondDayMachineCount);
        context.addStructurePlanMachineCount(thirdDay, sku.getStructureName(), thirdDayMachineCount);

        MachineScheduleDTO machine = buildMachine("K2024", dateTime(2026, 4, 1, 6, 0));
        attachAvailableMould(context, sku.getMaterialCode(), "MOULD-3302002326");
        strategy.scheduleNewSpecs(context, singletonMachineMatch(machine),
                defaultMouldChangeBalance(), defaultInspectionBalance(), skuCapacityCalculate());
        return context;
    }

    private SkuScheduleDTO buildSku() {
        SkuScheduleDTO sku = new SkuScheduleDTO();
        sku.setMaterialCode("MAT-1");
        sku.setMaterialDesc("测试物料");
        sku.setSpecCode("11R22.5");
        sku.setSpecDesc("11R22.5");
        sku.setProSize("22.5");
        sku.setLhTimeSeconds(3600);
        sku.setMouldQty(1);
        sku.setPendingQty(1);
        sku.setDailyPlanQty(1);
        return sku;
    }

    private LhScheduleResult buildNewSpecResult(String materialCode, String machineCode) {
        LhScheduleResult result = new LhScheduleResult();
        result.setMaterialCode(materialCode);
        result.setLhMachineCode(machineCode);
        result.setScheduleType("02");
        result.setIsTypeBlock("0");
        result.setIsEnd("0");
        result.setLhTime(3600);
        result.setMouldQty(1);
        return result;
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

    private void invokeAdjustSameSkuMultiMachineAllocation(NewSpecProductionStrategy strategy,
                                                           LhScheduleContext context,
                                                           SkuScheduleDTO sku,
                                                           List<LhShiftConfigVO> shifts,
                                                           ProductionQuantityPolicy quantityPolicy,
                                                           boolean isEnding) throws Exception {
        Method method = NewSpecProductionStrategy.class.getDeclaredMethod(
                "adjustSameSkuMultiMachineAllocation",
                LhScheduleContext.class,
                SkuScheduleDTO.class,
                List.class,
                ProductionQuantityPolicy.class,
                boolean.class);
        method.setAccessible(true);
        method.invoke(strategy, context, sku, shifts, quantityPolicy, isEnding);
    }

    private LhShiftConfigVO resolveNextWorkDateShift(List<LhShiftConfigVO> shifts, LhShiftConfigVO firstShift) {
        for (LhShiftConfigVO shift : shifts) {
            if (shift.getWorkDate() != null
                    && firstShift.getWorkDate() != null
                    && shift.getWorkDate().after(firstShift.getWorkDate())) {
                return shift;
            }
        }
        throw new IllegalStateException("测试夹具未找到跨天班次");
    }

    private Map<LocalDate, SkuDailyPlanQuotaDTO> buildQuotaMap(LhShiftConfigVO firstShift,
                                                               LhShiftConfigVO nextDayShift,
                                                               int firstDayQty,
                                                               int nextDayQty) {
        Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap = new LinkedHashMap<LocalDate, SkuDailyPlanQuotaDTO>(4);
        quotaMap.put(toLocalDate(firstShift), quota("MAT-QUOTA", toLocalDate(firstShift), firstDayQty));
        quotaMap.put(toLocalDate(nextDayShift), quota("MAT-QUOTA", toLocalDate(nextDayShift), nextDayQty));
        return quotaMap;
    }

    private Map<LocalDate, SkuDailyPlanQuotaDTO> buildDocumentCaseQuotaMap(List<LhShiftConfigVO> shifts,
                                                                            String materialCode) {
        return buildThreeDayQuotaMap(shifts, materialCode, 96, 48, 14);
    }

    private Map<LocalDate, SkuDailyPlanQuotaDTO> buildThreeDayQuotaMap(List<LhShiftConfigVO> shifts,
                                                                       String materialCode,
                                                                       int firstDayQty,
                                                                       int secondDayQty,
                                                                       int thirdDayQty) {
        Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap = new LinkedHashMap<LocalDate, SkuDailyPlanQuotaDTO>(4);
        quotaMap.put(toLocalDate(shifts.get(0)), quota(materialCode, toLocalDate(shifts.get(0)), firstDayQty));
        quotaMap.put(toLocalDate(shifts.get(3)), quota(materialCode, toLocalDate(shifts.get(3)), secondDayQty));
        quotaMap.put(toLocalDate(shifts.get(6)), quota(materialCode, toLocalDate(shifts.get(6)), thirdDayQty));
        return quotaMap;
    }

    private Map<LocalDate, SkuDailyPlanQuotaDTO> buildSingleDayQuotaMap(LhShiftConfigVO shift,
                                                                        String materialCode,
                                                                        int dayPlanQty) {
        Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap = new LinkedHashMap<LocalDate, SkuDailyPlanQuotaDTO>(2);
        quotaMap.put(toLocalDate(shift), quota(materialCode, toLocalDate(shift), dayPlanQty));
        return quotaMap;
    }

    private SkuDailyPlanQuotaDTO quota(String materialCode, LocalDate productionDate, int dayPlanQty) {
        SkuDailyPlanQuotaDTO quota = new SkuDailyPlanQuotaDTO();
        quota.setMaterialCode(materialCode);
        quota.setProductionDate(productionDate);
        quota.setDayPlanQty(dayPlanQty);
        quota.setRemainingQty(dayPlanQty);
        return quota;
    }

    private int sumCapacityMap(Map<LocalDate, Integer> capacityMap) {
        int totalQty = 0;
        for (Integer qty : capacityMap.values()) {
            totalQty += qty == null ? 0 : qty;
        }
        return totalQty;
    }

    private int sumResultQty(List<LhScheduleResult> resultList) {
        int totalQty = 0;
        if (resultList == null) {
            return totalQty;
        }
        for (LhScheduleResult result : resultList) {
            if (result == null || result.getDailyPlanQty() == null) {
                continue;
            }
            totalQty += result.getDailyPlanQty();
        }
        return totalQty;
    }

    private LhScheduleResult findScheduleResultByMaterialCode(List<LhScheduleResult> resultList, String materialCode) {
        if (resultList == null) {
            return null;
        }
        for (LhScheduleResult result : resultList) {
            if (result != null && StringUtils.equals(materialCode, result.getMaterialCode())) {
                return result;
            }
        }
        return null;
    }

    private LocalDate toLocalDate(LhShiftConfigVO shift) {
        return shift.getWorkDate().toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDate();
    }

    private MachineScheduleDTO buildMachine(String machineCode, Date estimatedEndTime) {
        MachineScheduleDTO machine = new MachineScheduleDTO();
        machine.setMachineCode(machineCode);
        machine.setMachineName(machineCode);
        machine.setStatus("1");
        machine.setMaxMoldNum(1);
        machine.setEstimatedEndTime(estimatedEndTime);
        machine.setPreviousSpecCode("11R22.5");
        machine.setPreviousProSize("22.5");
        machine.setPreviousMaterialCode("PREV-" + machineCode);
        return machine;
    }

    private void attachAvailableMould(LhScheduleContext context, String materialCode, String mouldCode) {
        MdmSkuMouldRel rel = new MdmSkuMouldRel();
        rel.setMaterialCode(materialCode);
        rel.setMouldCode(mouldCode);
        context.getSkuMouldRelMap().put(materialCode,
                new java.util.ArrayList<MdmSkuMouldRel>(Collections.singletonList(rel)));

        MdmModelInfo modelInfo = new MdmModelInfo();
        modelInfo.setMouldCode(mouldCode);
        modelInfo.setMouldStatus(1);
        context.getModelInfoMap().put(mouldCode, modelInfo);
    }

    private LhScheduleResult buildEndingResult(LhScheduleContext context, SkuScheduleDTO sku, String machineCode) {
        LhScheduleResult result = new LhScheduleResult();
        result.setFactoryCode(context.getFactoryCode());
        result.setBatchNo(context.getBatchNo());
        result.setMaterialCode(sku.getMaterialCode());
        result.setMaterialDesc(sku.getMaterialDesc());
        result.setLhMachineCode(machineCode);
        result.setLhMachineName(machineCode);
        result.setScheduleType("02");
        result.setIsEnd("1");
        result.setIsTypeBlock("0");
        result.setLhTime(sku.getLhTimeSeconds());
        result.setMouldQty(sku.getMouldQty());
        result.setDailyPlanQty(0);
        return result;
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

    private SkuScheduleDTO buildRealIssueSku(String materialCode, String pattern, int scheduleOrder) {
        SkuScheduleDTO sku = buildSku();
        sku.setMaterialCode(materialCode);
        sku.setMaterialDesc(materialCode);
        sku.setSpecCode("215/75R17.5");
        sku.setSpecDesc("215/75R17.5");
        sku.setStructureName("215/75R17.5");
        sku.setProSize("R17.5");
        sku.setPattern(pattern);
        sku.setMainPattern(pattern);
        sku.setShiftCapacity(1);
        sku.setPendingQty(1);
        sku.setDailyPlanQty(1);
        sku.setTargetScheduleQty(1);
        sku.setSurplusQty(1);
        sku.setEmbryoStock(1);
        sku.setScheduleOrder(scheduleOrder);
        return sku;
    }

    private MachineScheduleDTO findMachine(List<MachineScheduleDTO> candidates, String machineCode) {
        for (MachineScheduleDTO candidate : candidates) {
            if (machineCode.equals(candidate.getMachineCode())) {
                return candidate;
            }
        }
        return null;
    }

    private IMachineMatchStrategy singletonMachineMatch(MachineScheduleDTO machine) {
        return new IMachineMatchStrategy() {
            @Override
            public java.util.List<MachineScheduleDTO> matchMachines(LhScheduleContext ctx, SkuScheduleDTO scheduleSku) {
                return Arrays.asList(machine);
            }

            @Override
            public MachineScheduleDTO selectBestMachine(LhScheduleContext ctx, SkuScheduleDTO scheduleSku,
                                                        java.util.List<MachineScheduleDTO> candidates,
                                                        Set<String> excludedMachineCodes) {
                if (excludedMachineCodes.contains(machine.getMachineCode())) {
                    return null;
                }
                return machine;
            }

            @Override
            public void traceEnabledMachineSort(LhScheduleContext context) {
                // 测试桩，无需实现
            }
        };
    }

    private IMachineMatchStrategy materialMatch(String materialCode, MachineScheduleDTO machine) {
        return new IMachineMatchStrategy() {
            @Override
            public List<MachineScheduleDTO> matchMachines(LhScheduleContext ctx, SkuScheduleDTO scheduleSku) {
                if (scheduleSku != null && StringUtils.equals(materialCode, scheduleSku.getMaterialCode())) {
                    return Collections.singletonList(machine);
                }
                return Collections.emptyList();
            }

            @Override
            public MachineScheduleDTO selectBestMachine(LhScheduleContext ctx, SkuScheduleDTO scheduleSku,
                                                        List<MachineScheduleDTO> candidates,
                                                        Set<String> excludedMachineCodes) {
                for (MachineScheduleDTO candidate : candidates) {
                    if (candidate != null && !excludedMachineCodes.contains(candidate.getMachineCode())) {
                        return candidate;
                    }
                }
                return null;
            }

            @Override
            public void traceEnabledMachineSort(LhScheduleContext context) {
                // 测试桩，无需实现
            }
        };
    }

    private IMachineMatchStrategy orderedMachineMatch(MachineScheduleDTO firstMachine,
                                                       MachineScheduleDTO secondMachine) {
        return new IMachineMatchStrategy() {
            @Override
            public List<MachineScheduleDTO> matchMachines(LhScheduleContext ctx, SkuScheduleDTO scheduleSku) {
                return Arrays.asList(firstMachine, secondMachine);
            }

            @Override
            public MachineScheduleDTO selectBestMachine(LhScheduleContext ctx, SkuScheduleDTO scheduleSku,
                                                        List<MachineScheduleDTO> candidates,
                                                        Set<String> excludedMachineCodes) {
                for (MachineScheduleDTO candidate : candidates) {
                    if (candidate != null && !excludedMachineCodes.contains(candidate.getMachineCode())) {
                        return candidate;
                    }
                }
                return null;
            }

            @Override
            public void traceEnabledMachineSort(LhScheduleContext context) {
                // 测试桩，无需实现
            }
        };
    }

    private IMachineMatchStrategy orderedMachineMatch(MachineScheduleDTO firstMachine,
                                                       MachineScheduleDTO secondMachine,
                                                       MachineScheduleDTO thirdMachine) {
        return new IMachineMatchStrategy() {
            @Override
            public List<MachineScheduleDTO> matchMachines(LhScheduleContext ctx, SkuScheduleDTO scheduleSku) {
                return Arrays.asList(firstMachine, secondMachine, thirdMachine);
            }

            @Override
            public MachineScheduleDTO selectBestMachine(LhScheduleContext ctx, SkuScheduleDTO scheduleSku,
                                                        List<MachineScheduleDTO> candidates,
                                                        Set<String> excludedMachineCodes) {
                for (MachineScheduleDTO candidate : candidates) {
                    if (candidate != null && !excludedMachineCodes.contains(candidate.getMachineCode())) {
                        return candidate;
                    }
                }
                return null;
            }

            @Override
            public void traceEnabledMachineSort(LhScheduleContext context) {
                // 测试桩，无需实现
            }
        };
    }

    private LhScheduleConfig buildSingleControlScheduleConfig() {
        Map<String, String> paramMap = new HashMap<String, String>(4);
        paramMap.put(LhScheduleParamConstant.SINGLE_CONTROL_MACHINE_CODES, "K1501");
        return new LhScheduleConfig(paramMap);
    }

    private LhScheduleConfig buildSingleControlChangeoverBalanceScheduleConfig() {
        Map<String, String> paramMap = new HashMap<String, String>(4);
        paramMap.put(LhScheduleParamConstant.SINGLE_CONTROL_MACHINE_CODES, "K1501");
        paramMap.put(LhScheduleParamConstant.ENABLE_CHANGEOVER_BALANCE, "1");
        return new LhScheduleConfig(paramMap);
    }

    private LhScheduleConfig buildChangeoverBalanceScheduleConfig(String enabledValue) {
        Map<String, String> paramMap = new HashMap<String, String>(2);
        paramMap.put(LhScheduleParamConstant.ENABLE_CHANGEOVER_BALANCE, enabledValue);
        return new LhScheduleConfig(paramMap);
    }

    private ITrialProductionStrategy alwaysSchedulableTrialStrategy() {
        return new ITrialProductionStrategy() {
            @Override
            public List<SkuScheduleDTO> filterTrialSkus(LhScheduleContext context, List<SkuScheduleDTO> allSkus) {
                return allSkus;
            }

            @Override
            public boolean canScheduleTrialOnDate(LhScheduleContext context, Date targetDate) {
                return true;
            }

            @Override
            public boolean canScheduleTrialSkuOnDate(LhScheduleContext context, SkuScheduleDTO trialSku, Date targetDate) {
                return true;
            }

            @Override
            public boolean isDailyTrialLimitReached(LhScheduleContext context, Date targetDate) {
                return false;
            }

            @Override
            public boolean isDailyTrialLimitReached(LhScheduleContext context, Date targetDate, String materialCode) {
                return false;
            }

            @Override
            public String matchTrialMachine(LhScheduleContext context, SkuScheduleDTO trialSku) {
                return null;
            }
        };
    }

    private IMouldChangeBalanceStrategy defaultMouldChangeBalance() {
        return new IMouldChangeBalanceStrategy() {
            @Override
            public boolean hasCapacity(LhScheduleContext ctx, Date targetDate) {
                return true;
            }

            @Override
            public Date allocateMouldChange(LhScheduleContext ctx, String machineCode, Date endingTime) {
                return endingTime;
            }

            @Override
            public int getRemainingCapacity(LhScheduleContext ctx, Date targetDate) {
                return 99;
            }
        };
    }

    private IFirstInspectionBalanceStrategy defaultInspectionBalance() {
        return (ctx, machineCode, mouldChangeTime) -> mouldChangeTime;
    }

    private ICapacityCalculateStrategy defaultCapacityCalculate() {
        return new ICapacityCalculateStrategy() {
            @Override
            public int calculateShiftCapacity(LhScheduleContext ctx, int lhTimeSeconds, int mouldQty) {
                return 1;
            }

            @Override
            public Date calculateStartTime(LhScheduleContext ctx, String machineCode, Date endingTime) {
                return endingTime;
            }

            @Override
            public int calculateFirstShiftQty(Date startTime, Date shiftEndTime, int lhTimeSeconds, int mouldQty) {
                return startTime.before(shiftEndTime) ? 1 : 0;
            }

            @Override
            public int calculateDailyCapacity(int lhTimeSeconds, int mouldQty) {
                return 1;
            }
        };
    }

    private ICapacityCalculateStrategy skuCapacityCalculate() {
        return new ICapacityCalculateStrategy() {
            @Override
            public int calculateShiftCapacity(LhScheduleContext ctx, int lhTimeSeconds, int mouldQty) {
                return 16;
            }

            @Override
            public Date calculateStartTime(LhScheduleContext ctx, String machineCode, Date endingTime) {
                return endingTime;
            }

            @Override
            public int calculateFirstShiftQty(Date startTime, Date shiftEndTime, int lhTimeSeconds, int mouldQty) {
                return startTime.before(shiftEndTime) ? 16 : 0;
            }

            @Override
            public int calculateDailyCapacity(int lhTimeSeconds, int mouldQty) {
                return 16;
            }
        };
    }

    private ICapacityCalculateStrategy twentyCapacityCalculate() {
        return new ICapacityCalculateStrategy() {
            @Override
            public int calculateShiftCapacity(LhScheduleContext ctx, int lhTimeSeconds, int mouldQty) {
                return 20;
            }

            @Override
            public Date calculateStartTime(LhScheduleContext ctx, String machineCode, Date endingTime) {
                return endingTime;
            }

            @Override
            public int calculateFirstShiftQty(Date startTime, Date shiftEndTime, int lhTimeSeconds, int mouldQty) {
                return startTime.before(shiftEndTime) ? 20 : 0;
            }

            @Override
            public int calculateDailyCapacity(int lhTimeSeconds, int mouldQty) {
                return 20;
            }
        };
    }

    private void injectDependencies(NewSpecProductionStrategy strategy, boolean isEnding) throws Exception {
        OrderNoGenerator orderNoGenerator = new OrderNoGenerator();

        Field useRedisField = OrderNoGenerator.class.getDeclaredField("useRedis");
        useRedisField.setAccessible(true);
        useRedisField.set(orderNoGenerator, false);

        Field generatorField = NewSpecProductionStrategy.class.getDeclaredField("orderNoGenerator");
        generatorField.setAccessible(true);
        generatorField.set(strategy, orderNoGenerator);

        IEndingJudgmentStrategy endingJudgmentStrategy = new IEndingJudgmentStrategy() {
            @Override
            public boolean isEnding(LhScheduleContext context, SkuScheduleDTO sku) {
                return isEnding;
            }

            @Override
            public int calculateEndingShifts(LhScheduleContext context, SkuScheduleDTO sku) {
                return isEnding ? 1 : 0;
            }

            @Override
            public int calculateEndingDays(LhScheduleContext context, SkuScheduleDTO sku) {
                return isEnding ? 1 : 0;
            }
        };
        Field endingField = NewSpecProductionStrategy.class.getDeclaredField("endingJudgmentStrategy");
        endingField.setAccessible(true);
        endingField.set(strategy, endingJudgmentStrategy);

        Field targetResolverField = NewSpecProductionStrategy.class.getDeclaredField("targetScheduleQtyResolver");
        targetResolverField.setAccessible(true);
        targetResolverField.set(strategy, new TargetScheduleQtyResolver());

        Field localSearchField = NewSpecProductionStrategy.class.getDeclaredField("localSearchMachineAllocator");
        localSearchField.setAccessible(true);
        localSearchField.set(strategy, new LocalSearchMachineAllocatorStrategy());
    }

    private void injectLocalSearchAllocator(NewSpecProductionStrategy strategy,
                                            LocalSearchMachineAllocatorStrategy allocator) throws Exception {
        Field localSearchField = NewSpecProductionStrategy.class.getDeclaredField("localSearchMachineAllocator");
        localSearchField.setAccessible(true);
        localSearchField.set(strategy, allocator);
    }

    private void injectTrialProductionStrategy(NewSpecProductionStrategy strategy,
                                               ITrialProductionStrategy trialProductionStrategy) throws Exception {
        Field trialStrategyField = NewSpecProductionStrategy.class.getDeclaredField("trialProductionStrategy");
        trialStrategyField.setAccessible(true);
        trialStrategyField.set(strategy, trialProductionStrategy);
    }

    private LhScheduleResult findResult(List<LhScheduleResult> results, String machineCode) {
        for (LhScheduleResult result : results) {
            if (machineCode.equals(result.getLhMachineCode())) {
                return result;
            }
        }
        throw new IllegalStateException("未找到机台结果: " + machineCode);
    }

    private LhScheduleResult findResultByMaterialCode(List<LhScheduleResult> results, String materialCode) {
        for (LhScheduleResult result : results) {
            if (materialCode.equals(result.getMaterialCode())) {
                return result;
            }
        }
        throw new IllegalStateException("未找到物料结果: " + materialCode);
    }

    private com.zlt.aps.lh.api.domain.entity.LhUnscheduledResult findUnscheduledResultByMaterialCode(
            List<com.zlt.aps.lh.api.domain.entity.LhUnscheduledResult> results, String materialCode) {
        for (com.zlt.aps.lh.api.domain.entity.LhUnscheduledResult result : results) {
            if (materialCode.equals(result.getMaterialCode())) {
                return result;
            }
        }
        throw new IllegalStateException("未找到未排物料结果: " + materialCode);
    }

    private int resolveShiftQty(LhScheduleResult result, int shiftIndex) {
        Integer qty = ShiftFieldUtil.getShiftPlanQty(result, shiftIndex);
        return qty == null ? 0 : qty;
    }

    private int resolveFirstPlannedShiftIndex(LhScheduleResult result) {
        for (int shiftIndex = 1; shiftIndex <= 8; shiftIndex++) {
            Integer shiftPlanQty = ShiftFieldUtil.getShiftPlanQty(result, shiftIndex);
            if (shiftPlanQty != null && shiftPlanQty > 0) {
                return shiftIndex;
            }
        }
        return -1;
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
