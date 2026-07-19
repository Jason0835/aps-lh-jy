package com.zlt.aps.lh.regression;

import com.zlt.aps.lh.api.domain.dto.MachineScheduleDTO;
import com.zlt.aps.lh.api.domain.entity.LhMachineInfo;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.engine.strategy.impl.DefaultCapacityCalculateStrategy;
import com.zlt.aps.lh.handler.DataInitHandler;
import com.zlt.aps.lh.util.LhScheduleTimeUtil;
import com.zlt.aps.mdm.api.domain.entity.MdmDevicePlanShut;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回归场景：计划性维修（05）不使用旧维修字段全局抬高 readyTime，真实维修与预热由容量时间轴处理。
 */
class PlannedRepairReadyTimeRegressionTest {

    private final DataInitHandler dataInitHandler = new DataInitHandler();
    private final DefaultCapacityCalculateStrategy capacityCalculateStrategy = new DefaultCapacityCalculateStrategy();

    @Test
    void calculateStartTime_shouldNotRaiseReadyTimeForPlannedRepairStopType() {
        LhScheduleContext context = new LhScheduleContext();
        context.setScheduleDate(date(2026, 4, 21));
        context.setScheduleTargetDate(date(2026, 4, 23));
        context.setScheduleWindowShifts(LhScheduleTimeUtil.buildDefaultScheduleShifts(context, context.getScheduleDate()));

        LhMachineInfo machineInfo = new LhMachineInfo();
        machineInfo.setMachineCode("K2027");
        machineInfo.setMachineName("K2027");
        machineInfo.setStatus("0");
        machineInfo.setMaxMoldNum(2);
        context.setMachineInfoMap(new LinkedHashMap<String, LhMachineInfo>());
        context.getMachineInfoMap().put("K2027", machineInfo);

        MdmDevicePlanShut plannedRepair = new MdmDevicePlanShut();
        plannedRepair.setMachineCode("K2027");
        plannedRepair.setMachineStopType("05");
        plannedRepair.setBeginDate(dateTime(2026, 4, 22, 8, 0));
        plannedRepair.setEndDate(dateTime(2026, 4, 22, 16, 0));
        context.setDevicePlanShutList(Collections.singletonList(plannedRepair));

        ReflectionTestUtils.invokeMethod(dataInitHandler, "buildStandardDataObjects", context);

        MachineScheduleDTO machine = context.getMachineScheduleMap().get("K2027");
        assertNotNull(machine);
        assertTrue(!machine.isHasRepairPlan(), "05 计划性维修不应命中 repairPlan 初始化");

        Date endingTime = dateTime(2026, 4, 21, 6, 0);
        Date readyTime = capacityCalculateStrategy.calculateStartTime(context, "K2027", endingTime);
        assertEquals(endingTime, readyTime,
                "05 应在后续统一维修+预热容量窗口生效，不应从排程窗口起点提前全局抬高 readyTime");
    }

    private static Date date(int y, int month, int day) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(Calendar.YEAR, y);
        c.set(Calendar.MONTH, month - 1);
        c.set(Calendar.DAY_OF_MONTH, day);
        return c.getTime();
    }

    private static Date dateTime(int y, int month, int day, int hour, int minute) {
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
