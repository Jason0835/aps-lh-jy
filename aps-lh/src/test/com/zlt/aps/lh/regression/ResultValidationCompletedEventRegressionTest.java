package com.zlt.aps.lh.regression;

import com.zlt.aps.lh.api.domain.entity.LhScheduleResult;
import com.zlt.aps.lh.api.enums.EventTypeEnum;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.engine.observer.ScheduleEvent;
import com.zlt.aps.lh.engine.observer.ScheduleEventPublisher;
import com.zlt.aps.lh.handler.ResultValidationHandler;
import com.zlt.aps.lh.service.impl.SchedulePersistenceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;

/**
 * S4.6 完成事件发布回归测试。
 */
@ExtendWith(MockitoExtension.class)
class ResultValidationCompletedEventRegressionTest {

    @Mock
    private ScheduleEventPublisher scheduleEventPublisher;

    @Mock
    private SchedulePersistenceService schedulePersistenceService;

    @InjectMocks
    private ResultValidationHandler handler;

    @Test
    void handle_shouldPublishCompletedEventAfterPersistence() {
        LhScheduleContext context = new LhScheduleContext();
        context.setFactoryCode("116");
        context.setBatchNo("LHPC20260507003");
        context.setScheduleTargetDate(new Date());

        LhScheduleResult result = new LhScheduleResult();
        result.setBatchNo(context.getBatchNo());
        result.setFactoryCode("116");
        result.setLhMachineCode("M1");
        result.setMaterialCode("MAT-1");
        result.setScheduleType("01");
        result.setDailyPlanQty(10);
        result.setScheduleDate(context.getScheduleTargetDate());
        result.setSpecEndTime(new Date());
        result.setIsChangeMould("0");
        context.setScheduleResultList(Collections.singletonList(result));

        handler.handle(context);

        verify(schedulePersistenceService).replaceScheduleAtomically(context);
        ArgumentCaptor<ScheduleEvent> captor = ArgumentCaptor.forClass(ScheduleEvent.class);
        verify(scheduleEventPublisher).publish(captor.capture());
        assertEquals(EventTypeEnum.SCHEDULE_COMPLETED, captor.getValue().getEventType());
        assertEquals(context.getBatchNo(), captor.getValue().getBatchNo());
    }
}
