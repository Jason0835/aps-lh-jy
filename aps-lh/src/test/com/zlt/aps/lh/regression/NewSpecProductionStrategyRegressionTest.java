package com.zlt.aps.lh.regression;

import com.zlt.aps.lh.api.constant.LhScheduleParamConstant;
import com.zlt.aps.lh.api.domain.dto.MachineCleaningWindowDTO;
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
import com.zlt.aps.lh.util.LhScheduleTimeUtil;
import com.zlt.aps.lh.util.ShiftFieldUtil;
import com.zlt.aps.mdm.api.domain.entity.MdmMaterialInfo;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void scheduleNewSpecs_shouldSetIsEndOneWhenEndingJudgmentTrue() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        injectDependencies(strategy, true);

        LhScheduleContext context = buildContext();
        SkuScheduleDTO sku = buildSku();
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
        sku.setTrial(true);
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
        };

        strategy.scheduleNewSpecs(context, machineMatchStrategy, defaultMouldChangeBalance(),
                defaultInspectionBalance(), defaultCapacityCalculate());

        assertEquals(1, context.getScheduleResultList().size());
        assertEquals("K1501R", context.getScheduleResultList().get(0).getLhMachineCode(),
                "量试施工阶段 SKU 即使未显式打 isTrial，也应命中试制机台硬优先");
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
        assertEquals(1, context.getScheduleLogList().size());
        LhScheduleProcessLog processLog = context.getScheduleLogList().get(0);
        assertEquals("新增排产机台决策", processLog.getTitle());
        assertTrue(processLog.getLogDetail().contains("MAT-1"));
        assertTrue(processLog.getLogDetail().contains("M-TRACE"));
        assertTrue(processLog.getLogDetail().contains("决策结果=成功"));
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
            if ("新增排产候选机台排序明细".equals(processLog.getTitle())) {
                candidateTraceLogCount++;
            }
            if ("新增排产机台决策".equals(processLog.getTitle())) {
                decisionTraceLogCount++;
            }
        }
        assertEquals(2, candidateTraceLogCount,
                "启用局部搜索后，候选机台日志应按真实SKU决策输出，不应写入DFS模拟分支日志");
        assertEquals(2, decisionTraceLogCount, "应为每个真实决策SKU输出一条机台决策日志");
        assertEquals(4, context.getScheduleLogList().size(), "日志数量应受控在真实决策口径内");
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
                defaultInspectionBalance(), defaultCapacityCalculate());

        assertEquals(1, context.getScheduleResultList().size());
        assertEquals("K2026", context.getScheduleResultList().get(0).getLhMachineCode(),
                "只有基础首位机台真实失败后，才应顺序尝试下一台候选机台");
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
        // 目标量 30 未因胎胚库存 500 而上调
        assertTrue(planQty <= 30, "非收尾SKU排产量不应因胎胚库存(500)而上调超过目标量(30)");
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

    private SkuDailyPlanQuotaDTO quota(String materialCode, LocalDate productionDate, int dayPlanQty) {
        SkuDailyPlanQuotaDTO quota = new SkuDailyPlanQuotaDTO();
        quota.setMaterialCode(materialCode);
        quota.setProductionDate(productionDate);
        quota.setDayPlanQty(dayPlanQty);
        quota.setRemainingQty(dayPlanQty);
        return quota;
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
