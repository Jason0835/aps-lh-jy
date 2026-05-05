package com.zlt.aps.lh.regression;

import com.zlt.aps.lh.api.domain.dto.MachineCleaningWindowDTO;
import com.zlt.aps.lh.api.domain.dto.MachineScheduleDTO;
import com.zlt.aps.lh.api.domain.entity.LhMachineInfo;
import com.zlt.aps.lh.api.domain.entity.LhMouldCleanPlan;
import com.zlt.aps.lh.api.domain.entity.LhPrecisionPlan;
import com.zlt.aps.lh.api.domain.entity.LhScheduleResult;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.handler.DataInitHandler;
import com.zlt.aps.lh.util.LhScheduleTimeUtil;
import com.zlt.aps.mdm.api.domain.entity.MdmDevicePlanShut;
import com.zlt.aps.lh.api.domain.entity.LhMachineOnlineInfo;
import com.zlt.aps.mdm.api.domain.entity.MdmMaterialInfo;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Calendar;
import java.util.Collections;
import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 机台状态初始化：在机、前规格、清洗、保养、维修和预计结束时间应被补齐。
 */
class MachineStateInitRegressionTest {

    private final DataInitHandler handler = new DataInitHandler();

    @Test
    void buildStandardDataObjects_populatesMachineStateSnapshot() {
        LhScheduleContext context = new LhScheduleContext();
        context.setScheduleDate(date(2026, 4, 11));
        context.setScheduleTargetDate(date(2026, 4, 13));
        context.setScheduleWindowShifts(LhScheduleTimeUtil.buildDefaultScheduleShifts(context, context.getScheduleDate()));

        LhMachineInfo machineInfo = new LhMachineInfo();
        machineInfo.setMachineCode("M1");
        machineInfo.setMachineName("机台1");
        machineInfo.setStatus("0");
        machineInfo.setMaxMoldNum(2);
        context.setMachineInfoMap(new LinkedHashMap<>());
        context.getMachineInfoMap().put("M1", machineInfo);

        LhMachineOnlineInfo onlineInfo = new LhMachineOnlineInfo();
        onlineInfo.setLhCode("M1");
        onlineInfo.setMaterialCode("MAT-1");
        onlineInfo.setSpecDesc("在线描述");
        context.getMachineOnlineInfoMap().put("M1", onlineInfo);

        MdmMaterialInfo materialInfo = new MdmMaterialInfo();
        materialInfo.setMaterialCode("MAT-1");
        materialInfo.setMaterialDesc("主数据描述");
        materialInfo.setSpecifications("SPEC-1");
        materialInfo.setProSize("18");
        context.getMaterialInfoMap().put("MAT-1", materialInfo);

        LhMouldCleanPlan dryIce = new LhMouldCleanPlan();
        dryIce.setLhCode("M1");
        dryIce.setCleanType("01");
        dryIce.setLeftRightMould("L");
        dryIce.setCleanTime(dateTime(2026, 4, 12, 8, 0));
        LhMouldCleanPlan sandBlast = new LhMouldCleanPlan();
        sandBlast.setLhCode("M1");
        sandBlast.setCleanType("02");
        sandBlast.setLeftRightMould("R");
        sandBlast.setCleanTime(dateTime(2026, 4, 12, 7, 30));
        context.setCleaningPlanList(java.util.Arrays.asList(dryIce, sandBlast));

        LhPrecisionPlan maintenancePlan = new LhPrecisionPlan();
        maintenancePlan.setMachineCode("M1");
        maintenancePlan.setPlanDate(date(2026, 4, 12));
        context.getMaintenancePlanMap().put("M1", maintenancePlan);

        MdmDevicePlanShut repair = new MdmDevicePlanShut();
        repair.setMachineCode("M1");
        repair.setMachineStopType("06");
        repair.setBeginDate(dateTime(2026, 4, 13, 10, 0));
        repair.setEndDate(dateTime(2026, 4, 13, 18, 0));
        context.setDevicePlanShutList(Collections.singletonList(repair));

        LhScheduleResult previous = new LhScheduleResult();
        previous.setLhMachineCode("M1");
        previous.setSpecEndTime(dateTime(2026, 4, 11, 20, 0));
        context.setPreviousScheduleResultList(Collections.singletonList(previous));

        ReflectionTestUtils.invokeMethod(handler, "buildStandardDataObjects", context);

        MachineScheduleDTO machine = context.getMachineScheduleMap().get("M1");
        assertEquals("MAT-1", machine.getCurrentMaterialCode());
        assertEquals("主数据描述", machine.getCurrentMaterialDesc());
        assertEquals("SPEC-1", machine.getPreviousSpecCode());
        assertEquals("18", machine.getPreviousProSize());
        assertTrue(machine.isHasDryIceCleaning());
        assertTrue(machine.isHasSandBlastCleaning());
        assertEquals(dateTime(2026, 4, 12, 7, 30), machine.getCleaningPlanTime());
        assertEquals(2, machine.getCleaningWindowList().size());
        assertTrue(!machine.isHasMaintenancePlan());
        assertTrue(machine.isHasRepairPlan());
        assertEquals(dateTime(2026, 4, 13, 10, 0), machine.getRepairPlanTime());
        assertEquals(dateTime(2026, 4, 11, 20, 0), machine.getEstimatedEndTime());
        MachineCleaningWindowDTO firstCleaningWindow = machine.getCleaningWindowList().get(0);
        assertEquals("02", firstCleaningWindow.getCleanType());
        assertEquals("R", firstCleaningWindow.getLeftRightMould());
        assertEquals(dateTime(2026, 4, 12, 7, 30), firstCleaningWindow.getCleanStartTime());
        assertEquals(dateTime(2026, 4, 12, 19, 30), firstCleaningWindow.getCleanEndTime());
        assertEquals(dateTime(2026, 4, 12, 17, 30), firstCleaningWindow.getReadyTime());

        MachineCleaningWindowDTO secondCleaningWindow = machine.getCleaningWindowList().get(1);
        assertEquals("01", secondCleaningWindow.getCleanType());
        assertEquals("L", secondCleaningWindow.getLeftRightMould());
        assertEquals(dateTime(2026, 4, 12, 8, 0), secondCleaningWindow.getCleanStartTime());
        assertEquals(dateTime(2026, 4, 12, 11, 0), secondCleaningWindow.getCleanEndTime());
        assertEquals(dateTime(2026, 4, 12, 11, 0), secondCleaningWindow.getReadyTime());

        MachineScheduleDTO snapshot = context.getInitialMachineScheduleMap().get("M1");
        assertEquals(machine.getCurrentMaterialCode(), snapshot.getCurrentMaterialCode());
        assertEquals(machine.getPreviousSpecCode(), snapshot.getPreviousSpecCode());
        assertEquals(machine.getEstimatedEndTime(), snapshot.getEstimatedEndTime());
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
