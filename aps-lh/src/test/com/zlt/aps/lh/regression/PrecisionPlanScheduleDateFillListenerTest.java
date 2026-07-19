package com.zlt.aps.lh.regression;

import com.zlt.aps.lh.api.domain.dto.MachineMaintenanceWindowDTO;
import com.zlt.aps.lh.api.domain.dto.MachineScheduleDTO;
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

import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
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
        Date maintenanceStartTime = new Date();

        MachineScheduleDTO m1 = new MachineScheduleDTO();
        m1.setMachineCode("M1");
        m1.setHasMaintenancePlan(true);
        m1.getMaintenanceWindowList().add(buildWindow(101L, maintenanceStartTime));
        m1.getMaintenanceWindowList().add(buildWindow(101L, maintenanceStartTime));
        MachineScheduleDTO m2 = new MachineScheduleDTO();
        m2.setMachineCode("M2");
        m2.setHasMaintenancePlan(true);
        m2.getMaintenanceWindowList().add(buildWindow(102L, maintenanceStartTime));
        MachineScheduleDTO invalidMachine = new MachineScheduleDTO();
        invalidMachine.setMachineCode("M3");
        invalidMachine.getMaintenanceWindowList().add(buildWindow(null, maintenanceStartTime));
        context.setMachineScheduleMap(new LinkedHashMap<String, MachineScheduleDTO>());
        context.getMachineScheduleMap().put("M1", m1);
        context.getMachineScheduleMap().put("M2", m2);
        context.getMachineScheduleMap().put("M3", invalidMachine);

        when(lhPrecisionPlanService.batchFillScheduleDate(anyList())).thenReturn(2);

        publisher.publish(ScheduleEvent.completed(context));

        ArgumentCaptor<List> captor = ArgumentCaptor.forClass(List.class);
        verify(lhPrecisionPlanService).batchFillScheduleDate(captor.capture());

        List<Map<String, Object>> fillList = captor.getValue();
        assertEquals(2, fillList.size());
        assertEquals(101L, fillList.get(0).get("precisionPlanId"));
        assertEquals("M1", fillList.get(0).get("machineCode"));
        assertEquals("116", fillList.get(0).get("factoryCode"));
        assertEquals(maintenanceStartTime, fillList.get(0).get("scheduleDate"));
        assertEquals(102L, fillList.get(1).get("precisionPlanId"));
        assertEquals("M2", fillList.get(1).get("machineCode"));
        assertEquals("116", fillList.get(1).get("factoryCode"));
        assertEquals(maintenanceStartTime, fillList.get(1).get("scheduleDate"));
    }

    @Test
    void publish_shouldIgnorePublishedEvent() {
        ScheduleEventPublisher publisher = new ScheduleEventPublisher();
        ReflectionTestUtils.setField(publisher, "listeners", Collections.singletonList(listener));

        LhScheduleContext context = new LhScheduleContext();
        context.setBatchNo("LHPC20260507002");

        publisher.publish(ScheduleEvent.published(context));

        verify(lhPrecisionPlanService, never()).batchFillScheduleDate(anyList());
    }

    @Test
    void publish_shouldOnlyFillMachinesWithMaintenancePlan() {
        ScheduleEventPublisher publisher = new ScheduleEventPublisher();
        ReflectionTestUtils.setField(publisher, "listeners", Collections.singletonList(listener));

        LhScheduleContext context = new LhScheduleContext();
        context.setBatchNo("LHPC20260507003");
        context.setFactoryCode("116");
        Date maintenanceStartTime = new Date();

        MachineScheduleDTO k1105 = new MachineScheduleDTO();
        k1105.setMachineCode("K1105");
        k1105.setHasMaintenancePlan(false);
        MachineScheduleDTO k1113 = new MachineScheduleDTO();
        k1113.setMachineCode("K1113");
        k1113.setHasMaintenancePlan(true);
        k1113.getMaintenanceWindowList().add(buildWindow(1113L, maintenanceStartTime));
        context.setMachineScheduleMap(new LinkedHashMap<String, MachineScheduleDTO>());
        context.getMachineScheduleMap().put("K1105", k1105);
        context.getMachineScheduleMap().put("K1113", k1113);

        when(lhPrecisionPlanService.batchFillScheduleDate(anyList())).thenReturn(1);

        publisher.publish(ScheduleEvent.completed(context));

        ArgumentCaptor<List> captor = ArgumentCaptor.forClass(List.class);
        verify(lhPrecisionPlanService).batchFillScheduleDate(captor.capture());
        List<Map<String, Object>> fillList = captor.getValue();
        assertEquals(1, fillList.size());
        assertEquals("K1113", fillList.get(0).get("machineCode"));
    }

    @Test
    void publish_shouldNotPropagateExceptionWhenFillServiceFails() {
        ScheduleEventPublisher publisher = new ScheduleEventPublisher();
        ReflectionTestUtils.setField(publisher, "listeners", Collections.singletonList(listener));

        LhScheduleContext context = new LhScheduleContext();
        context.setBatchNo("LHPC20260507004");
        context.setFactoryCode("116");

        MachineScheduleDTO m1 = new MachineScheduleDTO();
        m1.setMachineCode("M1");
        m1.setHasMaintenancePlan(true);
        m1.getMaintenanceWindowList().add(buildWindow(101L, new Date()));
        context.setMachineScheduleMap(new LinkedHashMap<String, MachineScheduleDTO>());
        context.getMachineScheduleMap().put("M1", m1);

        when(lhPrecisionPlanService.batchFillScheduleDate(anyList())).thenThrow(new RuntimeException("fill failed"));

        assertDoesNotThrow(() -> publisher.publish(ScheduleEvent.completed(context)));
        verify(lhPrecisionPlanService).batchFillScheduleDate(anyList());
    }

    private MachineMaintenanceWindowDTO buildWindow(Long precisionPlanId, Date maintenanceStartTime) {
        MachineMaintenanceWindowDTO window = new MachineMaintenanceWindowDTO();
        window.setPrecisionPlanId(precisionPlanId);
        window.setMaintenanceStartTime(maintenanceStartTime);
        window.setMaintenanceEndTime(new Date(maintenanceStartTime.getTime() + 7 * 60 * 60 * 1000L));
        return window;
    }
}
