package com.zlt.aps.lh.regression;

import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.api.domain.entity.LhScheduleResult;
import com.zlt.aps.lh.mapper.LhMouldChangePlanEntityMapper;
import com.zlt.aps.lh.mapper.LhScheduleProcessLogMapper;
import com.zlt.aps.lh.mapper.LhScheduleResultMapper;
import com.zlt.aps.lh.mapper.LhUnscheduledResultMapper;
import com.zlt.aps.lh.service.ILhScheduleResultService;
import com.zlt.aps.lh.service.impl.SchedulePersistenceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Calendar;
import java.util.Collections;

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

    private static java.util.Date date(int y, int month, int day) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(Calendar.YEAR, y);
        c.set(Calendar.MONTH, month - 1);
        c.set(Calendar.DAY_OF_MONTH, day);
        return c.getTime();
    }
}
