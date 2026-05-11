package com.zlt.aps.lh.engine.strategy.impl;

import com.zlt.aps.lh.api.domain.dto.MachineScheduleDTO;
import com.zlt.aps.lh.api.domain.dto.SkuScheduleDTO;
import com.zlt.aps.lh.component.TargetScheduleQtyResolver;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.engine.strategy.IMachineMatchStrategy;
import com.zlt.aps.lh.util.LhScheduleTimeUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
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
