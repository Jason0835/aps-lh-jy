package com.zlt.aps.lh.engine.strategy.impl;

import com.zlt.aps.lh.api.domain.dto.MachineScheduleDTO;
import com.zlt.aps.lh.api.domain.dto.SkuDailyPlanQuotaDTO;
import com.zlt.aps.lh.api.domain.dto.SkuScheduleDTO;
import com.zlt.aps.lh.component.TargetScheduleQtyResolver;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.engine.strategy.IMachineMatchStrategy;
import com.zlt.aps.lh.util.LhScheduleTimeUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
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
        injectTargetScheduleQtyResolver(strategy, new TargetScheduleQtyResolver());

        LhScheduleContext context = new LhScheduleContext();
        context.setScheduleDate(toDate(2026, 5, 13, 0, 0, 0));
        context.setScheduleWindowShifts(Collections.singletonList(
                LhScheduleTimeUtil.buildDefaultScheduleShifts(context, context.getScheduleDate()).get(0)));

        SkuScheduleDTO sku = new SkuScheduleDTO();
        sku.setMaterialCode("3302001575");
        sku.setRemainingScheduleQty(20);
        sku.setShiftCapacity(0);
        sku.setLhTimeSeconds(3600);

        MachineScheduleDTO firstMachine = buildMachine("K1105", 1);
        MachineScheduleDTO secondMachine = buildMachine("K1111", 4);
        List<MachineScheduleDTO> candidates = Arrays.asList(firstMachine, secondMachine);

        MachineScheduleDTO selected = invokeSelectCandidateMachine(
                strategy,
                context,
                sku,
                candidates,
                Collections.<String>emptySet(),
                new FirstCandidateMachineMatchStrategy(),
                null);

        Assertions.assertNotNull(selected);
        Assertions.assertEquals("K1111", selected.getMachineCode());
    }

    /**
     * 用例说明：当没有单机能一次收完剩余量时，应优先选择较小产能机台，
     * 把剩余尾量集中留给另一台机台，避免更早空出的机台直接吃满。
     *
     * @throws Exception 反射调用异常
     */
    @Test
    public void shouldPreferSmallerCapacityMachineWhenItHelpsConcentrateTailQty() throws Exception {
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
                null);

        Assertions.assertNotNull(selected);
        Assertions.assertEquals("K1110", selected.getMachineCode());
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
                                                            MachineScheduleDTO preferredTrialMachine) throws Exception {
        Method method = NewSpecProductionStrategy.class.getDeclaredMethod(
                "selectCandidateMachine",
                LhScheduleContext.class,
                SkuScheduleDTO.class,
                List.class,
                Set.class,
                IMachineMatchStrategy.class,
                MachineScheduleDTO.class);
        method.setAccessible(true);
        return (MachineScheduleDTO) method.invoke(
                strategy, context, sku, candidates, new HashSet<String>(excludedMachineCodes),
                machineMatch, preferredTrialMachine);
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
