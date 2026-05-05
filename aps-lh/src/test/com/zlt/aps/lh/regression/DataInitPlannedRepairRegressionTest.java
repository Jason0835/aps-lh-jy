package com.zlt.aps.lh.regression;

import com.zlt.aps.lh.api.domain.dto.MachineScheduleDTO;
import com.zlt.aps.lh.api.domain.entity.LhMachineInfo;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.handler.DataInitHandler;
import com.zlt.aps.lh.util.LhScheduleTimeUtil;
import com.zlt.aps.mdm.api.domain.entity.MdmDevicePlanShut;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Calendar;
import java.util.Collections;
import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 计划性维修停机初始化回归测试。
 */
class DataInitPlannedRepairRegressionTest {

    private final DataInitHandler handler = new DataInitHandler();

    @Test
    void buildStandardDataObjects_shouldKeepPlannedRepairAsStopWindowOnly() {
        LhScheduleContext context = new LhScheduleContext();
        context.setScheduleDate(date(2026, 4, 21));
        context.setScheduleTargetDate(date(2026, 4, 23));
        context.setScheduleWindowShifts(LhScheduleTimeUtil.buildDefaultScheduleShifts(context, context.getScheduleDate()));

        LhMachineInfo machineInfo = new LhMachineInfo();
        machineInfo.setMachineCode("K2027");
        machineInfo.setMachineName("K2027");
        machineInfo.setStatus("0");
        machineInfo.setMaxMoldNum(2);
        context.setMachineInfoMap(new LinkedHashMap<>());
        context.getMachineInfoMap().put("K2027", machineInfo);

        MdmDevicePlanShut plannedRepair = new MdmDevicePlanShut();
        plannedRepair.setMachineCode("K2027");
        plannedRepair.setMachineStopType("05");
        plannedRepair.setBeginDate(dateTime(2026, 4, 22, 8, 0));
        plannedRepair.setEndDate(dateTime(2026, 4, 22, 16, 0));
        context.setDevicePlanShutList(Collections.singletonList(plannedRepair));

        ReflectionTestUtils.invokeMethod(handler, "buildStandardDataObjects", context);

        MachineScheduleDTO machine = context.getMachineScheduleMap().get("K2027");
        assertNotNull(machine);
        assertEquals(dateTime(2026, 4, 22, 8, 0), machine.getPlanStopStartTime());
        assertEquals(dateTime(2026, 4, 22, 16, 0), machine.getPlanStopEndTime());
        assertEquals("05", machine.getStopType());
        assertTrue(!machine.isHasRepairPlan(),
                "计划性维修停机应只作为停机窗口，不应在初始化阶段抬高机台维修就绪时间");
        assertEquals(null, machine.getRepairPlanTime());
    }

    private static java.util.Date date(int y, int month, int day) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(Calendar.YEAR, y);
        c.set(Calendar.MONTH, month - 1);
        c.set(Calendar.DAY_OF_MONTH, day);
        return c.getTime();
    }

    private static java.util.Date dateTime(int y, int month, int day, int hour, int minute) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(Calendar.YEAR, y);
        c.set(Calendar.MONTH, month - 1);
        c.set(Calendar.DAY_OF_MONTH, day);
        c.set(Calendar.HOUR_OF_DAY, hour);
        c.set(Calendar.MINUTE, minute);
        return c.getTime();
    }
}
