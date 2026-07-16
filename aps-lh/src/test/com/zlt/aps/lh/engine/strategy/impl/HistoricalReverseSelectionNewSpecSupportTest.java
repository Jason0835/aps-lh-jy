package com.zlt.aps.lh.engine.strategy.impl;

import com.zlt.aps.lh.api.domain.dto.MachineScheduleDTO;
import com.zlt.aps.lh.api.domain.dto.SkuScheduleDTO;
import com.zlt.aps.lh.context.LhScheduleConfig;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.engine.strategy.IMachineMatchStrategy;
import com.zlt.aps.lh.engine.strategy.support.HistoricalReverseSelectionDirective;
import com.zlt.aps.lh.engine.strategy.support.SpecifiedMachineMatchResult;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 前日反选指令接入新增排产主链的局部回归测试。
 */
class HistoricalReverseSelectionNewSpecSupportTest {

    private final NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();

    @Test
    @SuppressWarnings("unchecked")
    void specifiedMachine_shouldBeFirstWithoutChangingNormalCandidateOrder() {
        LhScheduleContext context = baseContext();
        SkuScheduleDTO sku = new SkuScheduleDTO();
        sku.setMaterialCode("MAT-A");
        sku.setProductStatus("S");
        HistoricalReverseSelectionDirective directive = directive("MAT-A", "S", "K1003", 1);
        context.getHistoricalReverseSelectionDirectiveList().add(directive);

        MachineScheduleDTO normalFirst = machine("K1001");
        MachineScheduleDTO normalSecond = machine("K1002");
        MachineScheduleDTO specified = machine("K1003");
        IMachineMatchStrategy machineMatch = mock(IMachineMatchStrategy.class);
        when(machineMatch.matchSpecifiedMachine(any(), any(), eq("K1003")))
                .thenReturn(SpecifiedMachineMatchResult.success(specified));

        List<MachineScheduleDTO> candidates = (List<MachineScheduleDTO>) ReflectionTestUtils.invokeMethod(
                strategy, "prioritizeHistoricalReverseSpecifiedMachines",
                context, sku, Arrays.asList(normalFirst, normalSecond), machineMatch);

        assertEquals("K1003", candidates.get(0).getMachineCode());
        assertEquals("K1001", candidates.get(1).getMachineCode());
        assertEquals("K1002", candidates.get(2).getMachineCode());
    }

    @Test
    void mappedShift_shouldBeHardConstraintForActualMouldChangeStart() {
        LhScheduleContext context = baseContext();
        HistoricalReverseSelectionDirective directive = directive("MAT-A", "S", "K1003", 1);

        Boolean inMappedShift = ReflectionTestUtils.invokeMethod(
                strategy, "isHistoricalReverseMouldChangeInMappedShift",
                context, directive, date(2026, 7, 14, 7, 30));
        Boolean outsideMappedShift = ReflectionTestUtils.invokeMethod(
                strategy, "isHistoricalReverseMouldChangeInMappedShift",
                context, directive, date(2026, 7, 14, 14, 30));

        assertTrue(Boolean.TRUE.equals(inMappedShift));
        assertFalse(Boolean.TRUE.equals(outsideMappedShift));
    }

    /**
     * 构建测试上下文。
     *
     * @return 测试上下文
     */
    private LhScheduleContext baseContext() {
        LhScheduleContext context = new LhScheduleContext();
        Date scheduleDate = date(2026, 7, 14, 0, 0);
        context.setScheduleDate(scheduleDate);
        context.setScheduleTargetDate(scheduleDate);
        context.setScheduleConfig(new LhScheduleConfig(new HashMap<String, String>(0)));
        return context;
    }

    /**
     * 构建待执行正规换模指令。
     *
     * @param materialCode 物料编码
     * @param productStatus 产品状态
     * @param machineCode 指定机台
     * @param mappedShiftIndex 映射班次
     * @return 反选指令
     */
    private HistoricalReverseSelectionDirective directive(String materialCode,
                                                           String productStatus,
                                                           String machineCode,
                                                           int mappedShiftIndex) {
        HistoricalReverseSelectionDirective directive = new HistoricalReverseSelectionDirective();
        directive.setMaterialCode(materialCode);
        directive.setProductStatus(productStatus);
        directive.setMachineCode(machineCode);
        directive.setEffectiveMachineCode(machineCode);
        directive.setMappedShiftIndex(mappedShiftIndex);
        directive.setHistoricalShiftIndex(mappedShiftIndex + 3);
        directive.setActualChangeType("01");
        return directive;
    }

    /**
     * 构建候选机台。
     *
     * @param machineCode 机台编码
     * @return 候选机台
     */
    private MachineScheduleDTO machine(String machineCode) {
        MachineScheduleDTO machine = new MachineScheduleDTO();
        machine.setMachineCode(machineCode);
        return machine;
    }

    /**
     * 构建本地时区日期。
     *
     * @param year 年
     * @param month 月
     * @param day 日
     * @param hour 时
     * @param minute 分
     * @return 日期
     */
    private Date date(int year, int month, int day, int hour, int minute) {
        Calendar calendar = Calendar.getInstance();
        calendar.clear();
        calendar.set(year, month - 1, day, hour, minute, 0);
        return calendar.getTime();
    }
}
