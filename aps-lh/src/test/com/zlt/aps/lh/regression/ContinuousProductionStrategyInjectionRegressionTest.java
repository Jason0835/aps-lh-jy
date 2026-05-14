package com.zlt.aps.lh.regression;

import com.zlt.aps.lh.api.domain.dto.MachineScheduleDTO;
import com.zlt.aps.lh.api.domain.dto.ShiftProductionControlDTO;
import com.zlt.aps.lh.api.domain.dto.SkuScheduleDTO;
import com.zlt.aps.lh.api.domain.vo.LhShiftConfigVO;
import com.zlt.aps.lh.component.TargetScheduleQtyResolver;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.engine.strategy.ICapacityCalculateStrategy;
import com.zlt.aps.lh.engine.strategy.IFirstInspectionBalanceStrategy;
import com.zlt.aps.lh.engine.strategy.IMouldChangeBalanceStrategy;
import com.zlt.aps.lh.engine.strategy.impl.ContinuousProductionStrategy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ContinuousProductionStrategy 注入链路回归测试。
 */
@ExtendWith(MockitoExtension.class)
class ContinuousProductionStrategyInjectionRegressionTest {

    @Mock
    private IMouldChangeBalanceStrategy mouldChangeBalanceStrategy;

    @Mock
    private IFirstInspectionBalanceStrategy firstInspectionBalanceStrategy;

    @Mock
    private ICapacityCalculateStrategy capacityCalculateStrategy;

    @Mock
    private TargetScheduleQtyResolver targetScheduleQtyResolver;

    @InjectMocks
    private ContinuousProductionStrategy strategy;

    @Test
    void canScheduleSpecifySkuByNewSpecPath_shouldUseInjectedStrategies() {
        LhScheduleContext context = new LhScheduleContext();
        MachineScheduleDTO machine = new MachineScheduleDTO();
        machine.setMachineCode("M1");
        SkuScheduleDTO specifySku = new SkuScheduleDTO();
        specifySku.setMaterialCode("MAT-SPECIFY");
        List<LhShiftConfigVO> shifts = new ArrayList<>(1);
        shifts.add(new LhShiftConfigVO());
        Date endingTime = new Date(1767196800000L);
        Date readyTime = new Date(1767200400000L);
        Date mouldChangeStartTime = new Date(1767204000000L);

        when(capacityCalculateStrategy.calculateStartTime(context, "M1", endingTime)).thenReturn(readyTime);
        when(mouldChangeBalanceStrategy.allocateMouldChange(context, "M1", readyTime)).thenReturn(mouldChangeStartTime);
        when(firstInspectionBalanceStrategy.allocateInspection(any(LhScheduleContext.class), eq("M1"), any(Date.class)))
                .thenReturn(null);

        Boolean schedulable = ReflectionTestUtils.invokeMethod(
                strategy,
                "canScheduleSpecifySkuByNewSpecPath",
                context,
                machine,
                specifySku,
                shifts,
                endingTime);

        assertFalse(Boolean.TRUE.equals(schedulable));
        verify(capacityCalculateStrategy).calculateStartTime(context, "M1", endingTime);
        verify(mouldChangeBalanceStrategy).allocateMouldChange(context, "M1", readyTime);
        verify(firstInspectionBalanceStrategy).allocateInspection(any(LhScheduleContext.class), eq("M1"), any(Date.class));
        verify(mouldChangeBalanceStrategy).rollbackMouldChange(context, mouldChangeStartTime);
        verify(firstInspectionBalanceStrategy, never()).rollbackInspection(any(LhScheduleContext.class), any(Date.class));
        verify(targetScheduleQtyResolver, never()).refineTargetQtyByMachineCapacity(
                any(LhScheduleContext.class), any(SkuScheduleDTO.class), any(MachineScheduleDTO.class),
                any(Date.class), any(Date.class), any(List.class));
    }

    @Test
    void canScheduleSpecifySkuByNewSpecPath_shouldDelayMouldChangeUntilOpenProductionShift() {
        LhScheduleContext context = new LhScheduleContext();
        context.setOpenProductionMode(true);
        context.setOpenProductionShift(openProductionShift(dateTime(2026, 5, 8, 6, 0)));
        MachineScheduleDTO machine = new MachineScheduleDTO();
        machine.setMachineCode("M1");
        SkuScheduleDTO specifySku = new SkuScheduleDTO();
        specifySku.setMaterialCode("MAT-SPECIFY");
        List<LhShiftConfigVO> shifts = new ArrayList<>(1);
        shifts.add(new LhShiftConfigVO());
        Date endingTime = dateTime(2026, 5, 7, 15, 0);
        Date readyTime = dateTime(2026, 5, 7, 15, 0);
        Date switchReadyTime = dateTime(2026, 5, 8, 6, 0);
        Date mouldChangeStartTime = dateTime(2026, 5, 8, 6, 0);

        when(capacityCalculateStrategy.calculateStartTime(context, "M1", endingTime)).thenReturn(readyTime);
        when(mouldChangeBalanceStrategy.allocateMouldChange(context, "M1", switchReadyTime)).thenReturn(mouldChangeStartTime);
        when(firstInspectionBalanceStrategy.allocateInspection(any(LhScheduleContext.class), eq("M1"), any(Date.class)))
                .thenReturn(null);

        Boolean schedulable = ReflectionTestUtils.invokeMethod(
                strategy,
                "canScheduleSpecifySkuByNewSpecPath",
                context,
                machine,
                specifySku,
                shifts,
                endingTime);

        assertFalse(Boolean.TRUE.equals(schedulable));
        verify(mouldChangeBalanceStrategy).allocateMouldChange(context, "M1", switchReadyTime);
    }

    private ShiftProductionControlDTO openProductionShift(Date startTime) {
        ShiftProductionControlDTO dto = new ShiftProductionControlDTO();
        dto.setShiftCode("04");
        dto.setShiftIndex(4);
        dto.setShiftStartTime(startTime);
        dto.setEffectiveStartTime(startTime);
        dto.setEffectiveEndTime(dateTime(2026, 5, 8, 14, 0));
        dto.setCanSchedule(true);
        return dto;
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
