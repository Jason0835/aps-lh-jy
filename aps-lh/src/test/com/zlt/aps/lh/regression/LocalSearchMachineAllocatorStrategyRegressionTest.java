package com.zlt.aps.lh.regression;

import com.zlt.aps.lh.api.constant.LhScheduleParamConstant;
import com.zlt.aps.lh.api.domain.dto.MachineCleaningWindowDTO;
import com.zlt.aps.lh.api.domain.dto.MachineScheduleDTO;
import com.zlt.aps.lh.api.domain.dto.SkuScheduleDTO;
import com.zlt.aps.lh.api.domain.vo.LhShiftConfigVO;
import com.zlt.aps.lh.context.LhScheduleConfig;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.engine.strategy.ICapacityCalculateStrategy;
import com.zlt.aps.lh.engine.strategy.IFirstInspectionBalanceStrategy;
import com.zlt.aps.lh.engine.strategy.IMachineMatchStrategy;
import com.zlt.aps.lh.engine.strategy.IMouldChangeBalanceStrategy;
import com.zlt.aps.lh.engine.strategy.impl.LocalSearchMachineAllocatorStrategy;
import com.zlt.aps.lh.util.LhScheduleTimeUtil;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 局部搜索选机回归：开产时间应与首检分配时间一致，不再额外叠加首检时长。
 */
class LocalSearchMachineAllocatorStrategyRegressionTest {

    @Test
    void selectBestMachine_shouldUseInspectionTimeAsProductionStart() {
        LocalSearchMachineAllocatorStrategy strategy = new LocalSearchMachineAllocatorStrategy();
        LhScheduleContext context = newContext();

        MachineScheduleDTO machine = new MachineScheduleDTO();
        machine.setMachineCode("M1");
        machine.setMachineName("M1");
        machine.setMaxMoldNum(1);
        machine.setEstimatedEndTime(dateTime(2026, 4, 11, 12, 0));

        SkuScheduleDTO sku = new SkuScheduleDTO();
        sku.setMaterialCode("MAT-LOCAL");
        sku.setPendingQty(1);
        sku.setWindowPlanQty(1);
        sku.setShiftCapacity(0);
        sku.setLhTimeSeconds(1800);

        List<SkuScheduleDTO> windowSkuList = Collections.singletonList(sku);
        List<MachineScheduleDTO> candidates = Collections.singletonList(machine);
        List<LhShiftConfigVO> shifts = context.getScheduleWindowShifts();

        IMachineMatchStrategy machineMatchStrategy = new IMachineMatchStrategy() {
            @Override
            public List<MachineScheduleDTO> matchMachines(LhScheduleContext ctx, SkuScheduleDTO scheduleSku) {
                return candidates;
            }

            @Override
            public MachineScheduleDTO selectBestMachine(LhScheduleContext ctx,
                                                        SkuScheduleDTO scheduleSku,
                                                        List<MachineScheduleDTO> candidateMachines,
                                                        Set<String> excludedMachineCodes) {
                return machine;
            }
        };

        IMouldChangeBalanceStrategy mouldChangeBalanceStrategy = new IMouldChangeBalanceStrategy() {
            @Override
            public boolean hasCapacity(LhScheduleContext ctx, Date targetDate) {
                return true;
            }

            @Override
            public Date allocateMouldChange(LhScheduleContext ctx, String machineCode, Date endingTime) {
                return dateTime(2026, 4, 11, 15, 0);
            }

            @Override
            public int getRemainingCapacity(LhScheduleContext ctx, Date targetDate) {
                return 99;
            }
        };

        IFirstInspectionBalanceStrategy inspectionBalanceStrategy =
                (ctx, machineCode, mouldChangeTime) -> dateTime(2026, 4, 11, 21, 30);

        ICapacityCalculateStrategy capacityCalculateStrategy = new ICapacityCalculateStrategy() {
            @Override
            public int calculateShiftCapacity(LhScheduleContext ctx, int lhTimeSeconds, int mouldQty) {
                return 0;
            }

            @Override
            public Date calculateStartTime(LhScheduleContext ctx, String machineCode, Date endingTime) {
                return endingTime;
            }

            @Override
            public int calculateFirstShiftQty(Date startTime, Date shiftEndTime, int lhTimeSeconds, int mouldQty) {
                return 0;
            }

            @Override
            public int calculateDailyCapacity(int lhTimeSeconds, int mouldQty) {
                return 0;
            }
        };

        MachineScheduleDTO selected = strategy.selectBestMachine(
                context,
                windowSkuList,
                candidates,
                shifts,
                machineMatchStrategy,
                mouldChangeBalanceStrategy,
                inspectionBalanceStrategy,
                capacityCalculateStrategy);

        assertNotNull(selected, "局部搜索应在首检时间可用时给出候选机台");
        assertEquals("M1", selected.getMachineCode());
    }

    @Test
    void selectBestMachine_shouldUseWindowStartWhenEstimatedEndTimeMissing() {
        LocalSearchMachineAllocatorStrategy strategy = new LocalSearchMachineAllocatorStrategy();
        LhScheduleContext context = newContext();

        MachineScheduleDTO machine = new MachineScheduleDTO();
        machine.setMachineCode("M1");
        machine.setMachineName("M1");
        machine.setMaxMoldNum(1);

        SkuScheduleDTO sku = new SkuScheduleDTO();
        sku.setMaterialCode("MAT-LOCAL");
        sku.setPendingQty(1);
        sku.setWindowPlanQty(1);
        sku.setShiftCapacity(8);
        sku.setLhTimeSeconds(1800);

        List<SkuScheduleDTO> windowSkuList = Collections.singletonList(sku);
        List<MachineScheduleDTO> candidates = Collections.singletonList(machine);
        List<LhShiftConfigVO> shifts = context.getScheduleWindowShifts();
        Date expectedBaseTime = shifts.get(0).getShiftStartDateTime();
        final Date[] capturedEndingTime = new Date[1];

        IMachineMatchStrategy machineMatchStrategy = new IMachineMatchStrategy() {
            @Override
            public List<MachineScheduleDTO> matchMachines(LhScheduleContext ctx, SkuScheduleDTO scheduleSku) {
                return candidates;
            }

            @Override
            public MachineScheduleDTO selectBestMachine(LhScheduleContext ctx,
                                                        SkuScheduleDTO scheduleSku,
                                                        List<MachineScheduleDTO> candidateMachines,
                                                        Set<String> excludedMachineCodes) {
                return machine;
            }
        };

        IMouldChangeBalanceStrategy mouldChangeBalanceStrategy = new IMouldChangeBalanceStrategy() {
            @Override
            public boolean hasCapacity(LhScheduleContext ctx, Date targetDate) {
                return true;
            }

            @Override
            public Date allocateMouldChange(LhScheduleContext ctx, String machineCode, Date endingTime) {
                return dateTime(2026, 4, 11, 15, 0);
            }

            @Override
            public int getRemainingCapacity(LhScheduleContext ctx, Date targetDate) {
                return 99;
            }
        };

        IFirstInspectionBalanceStrategy inspectionBalanceStrategy =
                (ctx, machineCode, mouldChangeTime) -> dateTime(2026, 4, 11, 21, 30);

        ICapacityCalculateStrategy capacityCalculateStrategy = new ICapacityCalculateStrategy() {
            @Override
            public int calculateShiftCapacity(LhScheduleContext ctx, int lhTimeSeconds, int mouldQty) {
                return 0;
            }

            @Override
            public Date calculateStartTime(LhScheduleContext ctx, String machineCode, Date endingTime) {
                capturedEndingTime[0] = endingTime;
                return endingTime;
            }

            @Override
            public int calculateFirstShiftQty(Date startTime, Date shiftEndTime, int lhTimeSeconds, int mouldQty) {
                return 0;
            }

            @Override
            public int calculateDailyCapacity(int lhTimeSeconds, int mouldQty) {
                return 0;
            }
        };

        strategy.selectBestMachine(
                context,
                windowSkuList,
                candidates,
                shifts,
                machineMatchStrategy,
                mouldChangeBalanceStrategy,
                inspectionBalanceStrategy,
                capacityCalculateStrategy);

        assertNotNull(capturedEndingTime[0], "局部搜索应执行机台开工时间计算");
        assertEquals(expectedBaseTime, capturedEndingTime[0],
                "局部搜索兜底时间应与主流程一致，使用排程窗口基准时间");
    }

    @Test
    void estimateCapacity_shouldUseTargetScheduleQtyInsteadOfPendingQty() {
        LocalSearchMachineAllocatorStrategy strategy = new LocalSearchMachineAllocatorStrategy();
        LhScheduleContext context = newContext();

        MachineScheduleDTO machine = new MachineScheduleDTO();
        machine.setMachineCode("M2");
        machine.setMachineName("M2");
        machine.setMaxMoldNum(1);

        SkuScheduleDTO sku = new SkuScheduleDTO();
        sku.setMaterialCode("MAT-TARGET");
        sku.setPendingQty(100);
        sku.setWindowPlanQty(100);
        sku.setTargetScheduleQty(1);
        sku.setShiftCapacity(8);
        sku.setLhTimeSeconds(1800);

        Object estimate = ReflectionTestUtils.invokeMethod(
                strategy,
                "estimateCapacity",
                context,
                sku,
                machine,
                null,
                context.getScheduleWindowShifts().get(0).getShiftStartDateTime(),
                context.getScheduleWindowShifts());

        Integer totalQty = ReflectionTestUtils.invokeMethod(estimate, "getTotalQty");
        assertEquals(1, totalQty.intValue(), "局部搜索估产应优先受目标量限制，而不是沿用旧待排量");
    }

    @Test
    void estimateCapacity_shouldRefineTargetQtyByActualRemainingShifts() {
        LocalSearchMachineAllocatorStrategy strategy = new LocalSearchMachineAllocatorStrategy();
        LhScheduleContext context = newContext();
        context.setScheduleWindowShifts(LhScheduleTimeUtil.buildDefaultScheduleShifts(context, context.getScheduleDate()));

        MachineScheduleDTO machine = new MachineScheduleDTO();
        machine.setMachineCode("M3");
        machine.setMachineName("M3");
        machine.setMaxMoldNum(1);

        SkuScheduleDTO sku = new SkuScheduleDTO();
        sku.setMaterialCode("MAT-CAPACITY");
        sku.setPendingQty(8);
        sku.setWindowPlanQty(8);
        sku.setTargetScheduleQty(128);
        sku.setShiftCapacity(16);
        sku.setLhTimeSeconds(1800);

        Object estimate = ReflectionTestUtils.invokeMethod(
                strategy,
                "estimateCapacity",
                context,
                sku,
                machine,
                null,
                context.getScheduleWindowShifts().get(1).getShiftStartDateTime(),
                context.getScheduleWindowShifts());

        Integer totalQty = ReflectionTestUtils.invokeMethod(estimate, "getTotalQty");
        assertEquals(112, totalQty.intValue(), "局部搜索估产应按实际开产后的剩余班次产能收敛目标量");
    }

    @Test
    void estimateCapacity_shouldKeepUnscheduledPenaltyWhenTargetExceedsMachineCapacity() {
        LocalSearchMachineAllocatorStrategy strategy = new LocalSearchMachineAllocatorStrategy();
        LhScheduleContext context = newContext();
        context.setScheduleWindowShifts(LhScheduleTimeUtil.buildDefaultScheduleShifts(context, context.getScheduleDate()));

        MachineScheduleDTO machine = new MachineScheduleDTO();
        machine.setMachineCode("M4");
        machine.setMachineName("M4");
        machine.setMaxMoldNum(1);

        SkuScheduleDTO sku = new SkuScheduleDTO();
        sku.setMaterialCode("MAT-PENALTY");
        sku.setPendingQty(8);
        sku.setWindowPlanQty(8);
        sku.setTargetScheduleQty(200);
        sku.setShiftCapacity(16);
        sku.setLhTimeSeconds(1800);

        Date productionStartTime = context.getScheduleWindowShifts().get(1).getShiftStartDateTime();
        Object estimate = ReflectionTestUtils.invokeMethod(
                strategy,
                "estimateCapacity",
                context,
                sku,
                machine,
                null,
                productionStartTime,
                context.getScheduleWindowShifts());

        Integer totalQty = ReflectionTestUtils.invokeMethod(estimate, "getTotalQty");
        Long penaltyScore = ReflectionTestUtils.invokeMethod(estimate, "getPenaltyScore");
        assertEquals(112, totalQty.intValue(), "候选机台估产总量应受实际剩余班次上限约束");
        assertTrue(penaltyScore.longValue() >= 88L * 1_000_000L,
                "目标量超出机台能力时应保留未满足量罚分，不能在估产前被预先截断");
    }

    @Test
    void estimateCapacity_shouldIgnoreSandBlastLossWhenCleaningOverlapsMouldChange() {
        LocalSearchMachineAllocatorStrategy strategy = new LocalSearchMachineAllocatorStrategy();
        LhScheduleContext context = newContext();
        context.setScheduleDate(date(2026, 4, 22));
        context.setScheduleWindowShifts(LhScheduleTimeUtil.buildDefaultScheduleShifts(context, context.getScheduleDate()));

        MachineScheduleDTO machine = new MachineScheduleDTO();
        machine.setMachineCode("K2025");
        machine.setMachineName("K2025");
        machine.setMaxMoldNum(1);
        MachineCleaningWindowDTO cleaningWindow = new MachineCleaningWindowDTO();
        cleaningWindow.setCleanType("02");
        cleaningWindow.setCleanStartTime(dateTime(2026, 4, 22, 6, 0));
        cleaningWindow.setCleanEndTime(dateTime(2026, 4, 22, 18, 0));
        cleaningWindow.setReadyTime(dateTime(2026, 4, 22, 16, 0));
        machine.setCleaningWindowList(Collections.singletonList(cleaningWindow));

        SkuScheduleDTO sku = new SkuScheduleDTO();
        sku.setMaterialCode("MAT-CLEAN");
        sku.setPendingQty(8);
        sku.setWindowPlanQty(8);
        sku.setTargetScheduleQty(8);
        sku.setShiftCapacity(8);
        sku.setLhTimeSeconds(3600);

        Object estimate = ReflectionTestUtils.invokeMethod(
                strategy,
                "estimateCapacity",
                context,
                sku,
                machine,
                dateTime(2026, 4, 22, 6, 0),
                dateTime(2026, 4, 22, 14, 0),
                context.getScheduleWindowShifts());

        Integer totalQty = ReflectionTestUtils.invokeMethod(estimate, "getTotalQty");
        assertEquals(8, totalQty.intValue(), "局部搜索估产在重叠场景下不应再扣减喷砂清洗损失量");
    }

    @Test
    void reserveAssignment_shouldDelaySandBlastOverlapUntilCleaningEndTime() {
        LocalSearchMachineAllocatorStrategy strategy = new LocalSearchMachineAllocatorStrategy();
        LhScheduleContext context = newContext();
        context.setScheduleDate(date(2026, 4, 22));
        context.setScheduleWindowShifts(LhScheduleTimeUtil.buildDefaultScheduleShifts(context, context.getScheduleDate()));

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
        machine.setCleaningWindowList(Collections.singletonList(cleaningWindow));

        SkuScheduleDTO sku = new SkuScheduleDTO();
        sku.setMaterialCode("MAT-CLEAN");
        sku.setPendingQty(8);
        sku.setWindowPlanQty(8);
        sku.setTargetScheduleQty(8);
        sku.setShiftCapacity(8);
        sku.setLhTimeSeconds(3600);

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

        IFirstInspectionBalanceStrategy inspectionBalanceStrategy = (ctx, machineCode, mouldChangeTime) -> mouldChangeTime;

        ICapacityCalculateStrategy capacityCalculateStrategy = new ICapacityCalculateStrategy() {
            @Override
            public int calculateShiftCapacity(LhScheduleContext ctx, int lhTimeSeconds, int mouldQty) {
                return 0;
            }

            @Override
            public Date calculateStartTime(LhScheduleContext ctx, String machineCode, Date endingTime) {
                return endingTime;
            }

            @Override
            public int calculateFirstShiftQty(Date startTime, Date shiftEndTime, int lhTimeSeconds, int mouldQty) {
                return 0;
            }

            @Override
            public int calculateDailyCapacity(int lhTimeSeconds, int mouldQty) {
                return 0;
            }
        };

        Object token = ReflectionTestUtils.invokeMethod(
                strategy,
                "reserveAssignment",
                context,
                sku,
                machine,
                mouldChangeBalanceStrategy,
                inspectionBalanceStrategy,
                capacityCalculateStrategy,
                context.getScheduleWindowShifts(),
                new HashMap<String, Date>(),
                0);

        assertNotNull(token, "局部搜索在喷砂顺延后仍应保留该机台作为可行候选");
        Date mouldChangeStartTime = ReflectionTestUtils.invokeMethod(token, "getMouldChangeStartTime");
        Date inspectionTime = ReflectionTestUtils.invokeMethod(token, "getInspectionTime");
        Date specEndTime = ReflectionTestUtils.invokeMethod(token, "getSpecEndTime");
        assertEquals(dateTime(2026, 4, 22, 18, 0), mouldChangeStartTime,
                "局部搜索预占换模窗口时，应顺延到喷砂完整12小时结束后再开始换模");
        assertEquals(dateTime(2026, 4, 23, 2, 0), inspectionTime,
                "顺延后的首检结束时间应与正式排产口径一致");
        assertEquals(dateTime(2026, 4, 23, 10, 0), specEndTime,
                "局部搜索估产的完工时间应基于顺延后的夜班和早班实际产出重新计算");
    }

    private LhScheduleContext newContext() {
        LhScheduleContext context = new LhScheduleContext();
        Date scheduleDate = date(2026, 4, 11);
        context.setScheduleDate(scheduleDate);

        Map<String, String> paramMap = new HashMap<>(4);
        paramMap.put(LhScheduleParamConstant.LOCAL_SEARCH_TIME_BUDGET_MS, "5000");
        context.setScheduleConfig(new LhScheduleConfig(paramMap));

        List<LhShiftConfigVO> allShifts = LhScheduleTimeUtil.buildDefaultScheduleShifts(context, scheduleDate);
        context.setScheduleWindowShifts(new ArrayList<>(allShifts.subList(0, 2)));
        return context;
    }

    private static Date date(int year, int month, int day) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(Calendar.YEAR, year);
        c.set(Calendar.MONTH, month - 1);
        c.set(Calendar.DAY_OF_MONTH, day);
        return c.getTime();
    }

    private static Date dateTime(int year, int month, int day, int hour, int minute) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(Calendar.YEAR, year);
        c.set(Calendar.MONTH, month - 1);
        c.set(Calendar.DAY_OF_MONTH, day);
        c.set(Calendar.HOUR_OF_DAY, hour);
        c.set(Calendar.MINUTE, minute);
        return c.getTime();
    }
}
