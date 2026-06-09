package com.zlt.aps.lh.engine.strategy.impl;

import com.zlt.aps.lh.api.constant.LhScheduleParamConstant;
import com.zlt.aps.lh.api.domain.dto.SkuDailyPlanQuotaDTO;
import com.zlt.aps.lh.api.domain.dto.MachineScheduleDTO;
import com.zlt.aps.lh.api.domain.dto.SkuScheduleDTO;
import com.zlt.aps.lh.api.domain.entity.LhMachineOnlineInfo;
import com.zlt.aps.lh.api.domain.entity.LhScheduleResult;
import com.zlt.aps.lh.api.domain.vo.LhShiftConfigVO;
import com.zlt.aps.lh.api.enums.ScheduleTypeEnum;
import com.zlt.aps.lh.component.OrderNoGenerator;
import com.zlt.aps.lh.component.TargetScheduleQtyResolver;
import com.zlt.aps.lh.context.LhScheduleConfig;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.engine.strategy.IEndingJudgmentStrategy;
import com.zlt.aps.lh.engine.strategy.support.DailyMachineCapacityDayDecision;
import com.zlt.aps.lh.engine.strategy.support.DailyMachineCapacitySimulationRequest;
import com.zlt.aps.lh.engine.strategy.support.DailyMachineCapacitySimulationResult;
import com.zlt.aps.lh.engine.strategy.support.DailyMachineCapacitySimulationUtil;
import com.zlt.aps.lh.engine.strategy.support.MachineProductionSegment;
import com.zlt.aps.lh.engine.strategy.support.MachineScheduleRole;
import com.zlt.aps.lh.util.LhScheduleTimeUtil;
import com.zlt.aps.lh.util.ShiftFieldUtil;
import com.zlt.aps.mdm.api.domain.entity.MdmSkuMouldRel;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
     * 欠产未超过阈值时，T+2 也必须继续后看 T+3 日计划量决定是否保留/新增机台。
     */
    @Test
    public void shouldLookAheadNextDayPlanOnWindowLastDayWhenSmallShortage() {
        DailyMachineCapacitySimulationRequest request = new DailyMachineCapacitySimulationRequest();
        request.setMaterialCode("3302001236");
        request.setDailyPlanQuotaMap(buildQuotaMap(0, 8, 48, 96));
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
        Assertions.assertEquals(Integer.valueOf(14), result.getClass8PlanQty());
        Assertions.assertEquals(Integer.valueOf(14), result.getDailyPlanQty());
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

    private SkuDailyPlanQuotaDTO buildQuota(int qty) {
        SkuDailyPlanQuotaDTO quota = new SkuDailyPlanQuotaDTO();
        quota.setDayPlanQty(qty);
        quota.setRemainingQty(qty);
        return quota;
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

    private Date dateTime(int year, int month, int day, int hour, int minute) {
        return Date.from(LocalDateTime.of(year, month, day, hour, minute)
                .atZone(ZoneId.systemDefault()).toInstant());
    }
}
