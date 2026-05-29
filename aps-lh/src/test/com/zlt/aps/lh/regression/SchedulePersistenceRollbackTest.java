package com.zlt.aps.lh.regression;

import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.api.constant.LhScheduleConstant;
import com.zlt.aps.lh.api.domain.dto.MachineScheduleDTO;
import com.zlt.aps.lh.api.domain.entity.LhScheduleResult;
import com.zlt.aps.lh.mapper.LhMouldChangePlanEntityMapper;
import com.zlt.aps.lh.mapper.LhScheduleProcessLogMapper;
import com.zlt.aps.lh.mapper.LhScheduleResultMapper;
import com.zlt.aps.lh.mapper.LhUnscheduledResultMapper;
import com.zlt.aps.lh.service.ILhScheduleResultService;
import com.zlt.aps.lh.service.impl.SchedulePersistenceService;
import com.zlt.aps.lh.util.ShiftFieldUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 原子替换持久化：保存异常必须抛出，不能静默吞掉。
 */
@ExtendWith(MockitoExtension.class)
class SchedulePersistenceRollbackTest {

    private static final String CLASS_END_NORMAL = "0";

    private static final String CLASS_END_MARK = "1";

    @Mock
    private LhScheduleResultMapper scheduleResultMapper;

    @Mock
    private LhUnscheduledResultMapper unscheduledResultMapper;

    @Mock
    private LhMouldChangePlanEntityMapper mouldChangePlanMapper;

    @Mock
    private LhScheduleProcessLogMapper processLogMapper;

    @Mock
    private ILhScheduleResultService scheduleResultService;

    @InjectMocks
    private SchedulePersistenceService schedulePersistenceService;

    @Test
    void replaceScheduleAtomically_throwsWhenInsertFails() {
        LhScheduleContext context = new LhScheduleContext();
        context.setFactoryCode("FC01");
        context.setBatchNo("LHPC20260413001");
        context.setScheduleTargetDate(date(2026, 4, 13));

        LhScheduleResult oldResult = new LhScheduleResult();
        oldResult.setBatchNo("OLD-BATCH");
        oldResult.setFactoryCode("FC01");
        oldResult.setScheduleDate(date(2026, 4, 13));

        LhScheduleResult newResult = new LhScheduleResult();
        newResult.setBatchNo("LHPC20260413001");
        newResult.setFactoryCode("FC01");
        newResult.setScheduleDate(date(2026, 4, 13));
        context.setScheduleResultList(Collections.singletonList(newResult));

        when(scheduleResultService.countReleasedByDate(any(), anyString())).thenReturn(0);
        when(scheduleResultMapper.selectList(any())).thenReturn(Collections.singletonList(oldResult));
        when(scheduleResultMapper.delete(any())).thenReturn(1);
        when(unscheduledResultMapper.delete(any())).thenReturn(0);
        when(mouldChangePlanMapper.delete(any())).thenReturn(0);
        when(processLogMapper.delete(any())).thenReturn(1);
        when(scheduleResultMapper.insertBatch(any())).thenThrow(new RuntimeException("insert failed"));

        assertThrows(RuntimeException.class, () -> schedulePersistenceService.replaceScheduleAtomically(context));

        verify(processLogMapper).delete(any());
        verify(unscheduledResultMapper, never()).insertBatch(any());
        verify(mouldChangePlanMapper, never()).insertBatch(any());
        verify(processLogMapper, never()).insertBatch(any());
    }

    @Test
    void replaceScheduleAtomically_marksSingleEndingMachineLastPlannedShift() {
        LhScheduleContext context = buildContext();
        LhScheduleResult result = buildResult("3302001724", "K1105", "1");
        ShiftFieldUtil.setShiftPlanQty(result, 3, 16, null, null);
        ShiftFieldUtil.setShiftPlanQty(result, 5, 14, null, null);
        ShiftFieldUtil.syncDailyPlanQty(result);
        context.setScheduleResultList(Collections.singletonList(result));
        mockReplaceDependencies();

        schedulePersistenceService.replaceScheduleAtomically(context);

        List<LhScheduleResult> savedResults = captureSavedResults();
        assertClassEndFlags(savedResults.get(0), 5);
    }

    @Test
    void replaceScheduleAtomically_marksOnlyAuxiliaryMachineForNonEndingMultiMachine() {
        LhScheduleContext context = buildContext();
        LhScheduleResult primaryResult = buildResult("3302002546", "K1206", "0");
        ShiftFieldUtil.setShiftPlanQty(primaryResult, 2, 17, null, null);
        ShiftFieldUtil.setShiftPlanQty(primaryResult, 3, 17, null, null);
        ShiftFieldUtil.syncDailyPlanQty(primaryResult);
        LhScheduleResult auxiliaryResult = buildResult("3302002546", "K1313", "0");
        ShiftFieldUtil.setShiftPlanQty(auxiliaryResult, 3, 17, null, null);
        ShiftFieldUtil.setShiftPlanQty(auxiliaryResult, 4, 8, null, null);
        ShiftFieldUtil.syncDailyPlanQty(auxiliaryResult);
        context.setScheduleResultList(Arrays.asList(primaryResult, auxiliaryResult));
        mockReplaceDependencies();

        schedulePersistenceService.replaceScheduleAtomically(context);

        List<LhScheduleResult> savedResults = captureSavedResults();
        assertClassEndFlags(savedResults.get(0), 0);
        assertClassEndFlags(savedResults.get(1), 4);
    }

    @Test
    void replaceScheduleAtomically_usesContinuousKeepOrderForNonEndingMultiMachine() {
        LhScheduleContext context = buildContext();
        MachineScheduleDTO lowCapsuleMachine = new MachineScheduleDTO();
        lowCapsuleMachine.setMachineCode("K1206");
        lowCapsuleMachine.setCapsuleUsageCount(1);
        MachineScheduleDTO highCapsuleMachine = new MachineScheduleDTO();
        highCapsuleMachine.setMachineCode("K1313");
        highCapsuleMachine.setCapsuleUsageCount(9);
        context.getMachineScheduleMap().put("K1206", lowCapsuleMachine);
        context.getMachineScheduleMap().put("K1313", highCapsuleMachine);

        LhScheduleResult auxiliaryResult = buildResult("3302002546", "K1206", "0");
        auxiliaryResult.setScheduleType("01");
        ShiftFieldUtil.setShiftPlanQty(auxiliaryResult, 2, 17, null, null);
        ShiftFieldUtil.setShiftPlanQty(auxiliaryResult, 3, 17, null, null);
        ShiftFieldUtil.syncDailyPlanQty(auxiliaryResult);
        LhScheduleResult primaryResult = buildResult("3302002546", "K1313", "0");
        primaryResult.setScheduleType("01");
        ShiftFieldUtil.setShiftPlanQty(primaryResult, 2, 17, null, null);
        ShiftFieldUtil.setShiftPlanQty(primaryResult, 3, 17, null, null);
        ShiftFieldUtil.syncDailyPlanQty(primaryResult);
        context.setScheduleResultList(Arrays.asList(auxiliaryResult, primaryResult));
        mockReplaceDependencies();

        schedulePersistenceService.replaceScheduleAtomically(context);

        List<LhScheduleResult> savedResults = captureSavedResults();
        assertClassEndFlags(savedResults.get(0), 3);
        assertClassEndFlags(savedResults.get(1), 0);
    }

    private LhScheduleContext buildContext() {
        LhScheduleContext context = new LhScheduleContext();
        context.setFactoryCode("FC01");
        context.setBatchNo("LHPC20260413001");
        context.setScheduleTargetDate(date(2026, 4, 13));
        return context;
    }

    private LhScheduleResult buildResult(String materialCode, String machineCode, String isEnd) {
        LhScheduleResult result = new LhScheduleResult();
        result.setFactoryCode("FC01");
        result.setBatchNo("LHPC20260413001");
        result.setScheduleDate(date(2026, 4, 13));
        result.setMaterialCode(materialCode);
        result.setLhMachineCode(machineCode);
        result.setScheduleType("02");
        result.setIsTypeBlock("0");
        result.setIsEnd(isEnd);
        return result;
    }

    private void mockReplaceDependencies() {
        when(scheduleResultService.countReleasedByDate(any(), anyString())).thenReturn(0);
        when(scheduleResultMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(scheduleResultMapper.delete(any())).thenReturn(0);
        when(unscheduledResultMapper.delete(any())).thenReturn(0);
        when(mouldChangePlanMapper.delete(any())).thenReturn(0);
    }

    @SuppressWarnings("unchecked")
    private List<LhScheduleResult> captureSavedResults() {
        ArgumentCaptor<List> captor = ArgumentCaptor.forClass(List.class);
        verify(scheduleResultMapper).insertBatch(captor.capture());
        return captor.getValue();
    }

    private void assertClassEndFlags(LhScheduleResult result, int expectedEndShiftIndex) {
        for (int shiftIndex = 1; shiftIndex <= LhScheduleConstant.MAX_SHIFT_SLOT_COUNT; shiftIndex++) {
            String expectedValue = shiftIndex == expectedEndShiftIndex ? CLASS_END_MARK : CLASS_END_NORMAL;
            assertEquals(expectedValue, ShiftFieldUtil.getShiftIsEnd(result, shiftIndex));
        }
    }

    private static java.util.Date date(int y, int month, int day) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(Calendar.YEAR, y);
        c.set(Calendar.MONTH, month - 1);
        c.set(Calendar.DAY_OF_MONTH, day);
        return c.getTime();
    }
}
