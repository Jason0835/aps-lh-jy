package com.zlt.aps.lh.regression;

import com.zlt.aps.lh.api.domain.entity.LhMouldChangePlan;
import com.zlt.aps.lh.api.domain.entity.LhScheduleResult;
import com.zlt.aps.lh.api.enums.MouldChangeTypeEnum;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.engine.observer.ScheduleEventPublisher;
import com.zlt.aps.lh.exception.ScheduleException;
import com.zlt.aps.lh.handler.ResultValidationHandler;
import com.zlt.aps.lh.service.impl.SchedulePersistenceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 结果强校验：关键字段缺失时必须阻断持久化。
 */
@ExtendWith(MockitoExtension.class)
class ResultValidationBlockingTest {

    @Mock
    private ScheduleEventPublisher scheduleEventPublisher;

    @Mock
    private SchedulePersistenceService schedulePersistenceService;

    @InjectMocks
    private ResultValidationHandler handler;

    @Test
    void handle_throwsWhenSpecEndTimeMissing() {
        LhScheduleContext context = new LhScheduleContext();
        context.setFactoryCode("FC01");
        context.setBatchNo("LHPC20260413001");

        LhScheduleResult result = new LhScheduleResult();
        result.setLhMachineCode("M1");
        result.setMaterialCode("MAT-1");
        result.setScheduleType("02");
        result.setIsChangeMould("1");
        result.setMouldCode("MOULD-1");
        result.setDailyPlanQty(1);
        context.setScheduleResultList(Collections.singletonList(result));

        assertThrows(ScheduleException.class, () -> handler.handle(context));

        verify(schedulePersistenceService, never()).replaceScheduleAtomically(context);
        verify(scheduleEventPublisher, never()).publish(any());
    }

    @Test
    void handle_throwsWhenZeroDailyPlanMissingSpecEndTime() {
        LhScheduleContext context = new LhScheduleContext();
        context.setFactoryCode("FC01");
        context.setBatchNo("LHPC20260413002");

        LhScheduleResult result = new LhScheduleResult();
        result.setLhMachineCode("M1");
        result.setMaterialCode("MAT-1");
        result.setScheduleType("01");
        result.setDailyPlanQty(0);
        result.setIsChangeMould("0");
        context.setScheduleResultList(Collections.singletonList(result));

        assertThrows(ScheduleException.class, () -> handler.handle(context));

        verify(schedulePersistenceService, never()).replaceScheduleAtomically(context);
        verify(scheduleEventPublisher, never()).publish(any());
    }

    @Test
    void handle_throwsWhenMorningMouldChangePlanExceedsLimit() {
        LhScheduleContext context = new LhScheduleContext();
        context.setFactoryCode("FC01");
        context.setBatchNo("LHPC20260413003");
        context.setScheduleTargetDate(date(2026, 4, 13));
        context.setScheduleResultList(new ArrayList<LhScheduleResult>());

        for (int index = 0; index < 9; index++) {
            LhMouldChangePlan plan = new LhMouldChangePlan();
            plan.setLhMachineCode("M" + index);
            plan.setLhMachineName("机台" + index);
            plan.setPlanDate(dateTime(2026, 4, 13, 6 + index / 2, (index % 2) * 30));
            plan.setChangeTime(plan.getPlanDate());
            plan.setChangeMouldType(index == 0
                    ? MouldChangeTypeEnum.TYPE_BLOCK.getCode()
                    : MouldChangeTypeEnum.REGULAR.getCode());
            plan.setIsDelete(0);
            context.getMouldChangePlanList().add(plan);
        }

        assertThrows(ScheduleException.class, () -> handler.handle(context));

        verify(schedulePersistenceService, never()).replaceScheduleAtomically(context);
        verify(scheduleEventPublisher, never()).publish(any());
    }

    private static java.util.Date date(int year, int month, int day) {
        Calendar calendar = Calendar.getInstance();
        calendar.clear();
        calendar.set(Calendar.YEAR, year);
        calendar.set(Calendar.MONTH, month - 1);
        calendar.set(Calendar.DAY_OF_MONTH, day);
        return calendar.getTime();
    }

    private static java.util.Date dateTime(int year, int month, int day, int hour, int minute) {
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
