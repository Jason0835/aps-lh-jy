package com.zlt.aps.lh.regression;

import com.zlt.aps.lh.api.domain.entity.LhScheduleResult;
import com.zlt.aps.lh.api.enums.EventTypeEnum;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.engine.observer.ScheduleEvent;
import com.zlt.aps.lh.engine.observer.ScheduleEventPublisher;
import com.zlt.aps.lh.engine.observer.listeners.PrecisionPlanScheduleDateFillListener;
import com.zlt.aps.lh.service.ILhPrecisionPlanService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 精度计划排程日期回填监听器回归测试。
 */
@ExtendWith(MockitoExtension.class)
class PrecisionPlanScheduleDateFillListenerTest {

    @Mock
    private ILhPrecisionPlanService lhPrecisionPlanService;

    @InjectMocks
    private PrecisionPlanScheduleDateFillListener listener;

    @Test
    void supports_shouldOnlyHandleScheduleCompletedEvent() {
        assertTrue(listener.supports(EventTypeEnum.SCHEDULE_COMPLETED));
        assertFalse(listener.supports(EventTypeEnum.RESULT_PUBLISHED));
    }

    @Test
    void publish_shouldBuildDistinctFillListAndSkipIncompleteRows() {
        ScheduleEventPublisher publisher = new ScheduleEventPublisher();
        ReflectionTestUtils.setField(publisher, "listeners", Collections.singletonList(listener));

        LhScheduleContext context = new LhScheduleContext();
        context.setBatchNo("LHPC20260507001");
        context.setFactoryCode("116");
        Date realScheduleDate = new Date();

        context.setScheduleResultList(Arrays.asList(
                buildResult("M1", "116", realScheduleDate),
                buildResult("M1", "116", realScheduleDate),
                buildResult("M2", "116", realScheduleDate),
                buildResult("", "116", realScheduleDate),
                buildResult("M3", "116", null)
        ));

        when(lhPrecisionPlanService.batchFillScheduleDate(anyList())).thenReturn(2);

        publisher.publish(ScheduleEvent.completed(context));

        ArgumentCaptor<List> captor = ArgumentCaptor.forClass(List.class);
        verify(lhPrecisionPlanService).batchFillScheduleDate(captor.capture());

        List<Map<String, Object>> fillList = captor.getValue();
        assertEquals(2, fillList.size());
        assertEquals("M1", fillList.get(0).get("machineCode"));
        assertEquals("116", fillList.get(0).get("factoryCode"));
        assertEquals(realScheduleDate, fillList.get(0).get("scheduleDate"));
        assertEquals("M2", fillList.get(1).get("machineCode"));
        assertEquals("116", fillList.get(1).get("factoryCode"));
        assertEquals(realScheduleDate, fillList.get(1).get("scheduleDate"));
    }

    @Test
    void publish_shouldIgnorePublishedEvent() {
        ScheduleEventPublisher publisher = new ScheduleEventPublisher();
        ReflectionTestUtils.setField(publisher, "listeners", Collections.singletonList(listener));

        LhScheduleContext context = new LhScheduleContext();
        context.setBatchNo("LHPC20260507002");
        context.setScheduleResultList(Collections.singletonList(buildResult("M1", "116", new Date())));

        publisher.publish(ScheduleEvent.published(context));

        verify(lhPrecisionPlanService, never()).batchFillScheduleDate(anyList());
    }

    @Test
    void publish_shouldNotPropagateExceptionWhenFillServiceFails() {
        ScheduleEventPublisher publisher = new ScheduleEventPublisher();
        ReflectionTestUtils.setField(publisher, "listeners", Collections.singletonList(listener));

        LhScheduleContext context = new LhScheduleContext();
        context.setBatchNo("LHPC20260507004");
        context.setFactoryCode("116");
        context.setScheduleResultList(Collections.singletonList(buildResult("M1", "116", new Date())));

        when(lhPrecisionPlanService.batchFillScheduleDate(anyList())).thenThrow(new RuntimeException("fill failed"));

        assertDoesNotThrow(() -> publisher.publish(ScheduleEvent.completed(context)));
        verify(lhPrecisionPlanService).batchFillScheduleDate(anyList());
    }

    private LhScheduleResult buildResult(String machineCode, String factoryCode, Date realScheduleDate) {
        LhScheduleResult result = new LhScheduleResult();
        result.setLhMachineCode(machineCode);
        result.setFactoryCode(factoryCode);
        result.setRealScheduleDate(realScheduleDate);
        return result;
    }
}
