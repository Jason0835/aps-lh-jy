package com.zlt.aps.lh.regression;

import com.zlt.aps.lh.api.domain.dto.MachineScheduleDTO;
import com.zlt.aps.lh.api.domain.dto.SkuScheduleDTO;
import com.zlt.aps.lh.api.domain.entity.LhUnscheduledResult;
import com.zlt.aps.lh.component.OrderNoGenerator;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.engine.strategy.ICapacityCalculateStrategy;
import com.zlt.aps.lh.engine.strategy.IEndingJudgmentStrategy;
import com.zlt.aps.lh.engine.strategy.IFirstInspectionBalanceStrategy;
import com.zlt.aps.lh.engine.strategy.IMachineMatchStrategy;
import com.zlt.aps.lh.engine.strategy.IMouldChangeBalanceStrategy;
import com.zlt.aps.lh.engine.strategy.impl.NewSpecProductionStrategy;
import com.zlt.aps.lh.util.LhScheduleTimeUtil;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 新增排产失败原因回归：多候选机台失败时，应保留优先级更高的阻塞原因。
 */
class NewSpecFailReasonPriorityRegressionTest {

    @Test
    void scheduleNewSpecs_shouldKeepHighestPriorityFailReason() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        injectDependencies(strategy, false);

        LhScheduleContext context = buildContext();
        SkuScheduleDTO sku = buildSku();
        context.getNewSpecSkuList().add(sku);

        MachineScheduleDTO mouldFailMachine = new MachineScheduleDTO();
        mouldFailMachine.setMachineCode("M-MOULD-FAIL");
        mouldFailMachine.setMachineName("换模失败机台");
        mouldFailMachine.setEstimatedEndTime(dateTime(2026, 4, 19, 6, 0));

        MachineScheduleDTO noCapacityMachine = new MachineScheduleDTO();
        noCapacityMachine.setMachineCode("M-NO-CAP");
        noCapacityMachine.setMachineName("无产能机台");
        noCapacityMachine.setEstimatedEndTime(dateTime(2026, 4, 19, 7, 0));

        IMachineMatchStrategy machineMatchStrategy = new IMachineMatchStrategy() {
            @Override
            public java.util.List<MachineScheduleDTO> matchMachines(LhScheduleContext ctx, SkuScheduleDTO scheduleSku) {
                return Arrays.asList(mouldFailMachine, noCapacityMachine);
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

        Date mouldFailReadyTime = dateTime(2026, 4, 19, 8, 0);
        Date noCapacityReadyTime = dateTime(2026, 4, 22, 9, 0);

        IMouldChangeBalanceStrategy mouldChangeBalanceStrategy = new IMouldChangeBalanceStrategy() {
            @Override
            public boolean hasCapacity(LhScheduleContext ctx, Date targetDate) {
                return true;
            }

            @Override
            public Date allocateMouldChange(LhScheduleContext ctx, String machineCode, Date endingTime) {
                if (mouldFailReadyTime.equals(endingTime)) {
                    return null;
                }
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
                return 0;
            }

            @Override
            public Date calculateStartTime(LhScheduleContext ctx, String machineCode, Date endingTime) {
                if ("M-MOULD-FAIL".equals(machineCode)) {
                    return mouldFailReadyTime;
                }
                return noCapacityReadyTime;
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

        strategy.scheduleNewSpecs(context, machineMatchStrategy, mouldChangeBalanceStrategy,
                inspectionBalanceStrategy, capacityCalculateStrategy);

        assertEquals(0, context.getScheduleResultList().size(), "两个候选机台都失败时不应产出排产结果");
        assertEquals(1, context.getUnscheduledResultList().size(), "应生成1条未排产记录");
        LhUnscheduledResult unscheduledResult = context.getUnscheduledResultList().get(0);
        assertEquals("排程窗口内无可用产能", unscheduledResult.getUnscheduledReason(),
                "应选择优先级更高的失败原因");
    }

    private LhScheduleContext buildContext() {
        LhScheduleContext context = new LhScheduleContext();
        Date scheduleDate = dateTime(2026, 4, 19, 0, 0);
        context.setFactoryCode("116");
        context.setBatchNo("TEST-BATCH");
        context.setScheduleDate(scheduleDate);
        context.setScheduleTargetDate(scheduleDate);
        context.setScheduleWindowShifts(LhScheduleTimeUtil.buildDefaultScheduleShifts(context, scheduleDate));
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
