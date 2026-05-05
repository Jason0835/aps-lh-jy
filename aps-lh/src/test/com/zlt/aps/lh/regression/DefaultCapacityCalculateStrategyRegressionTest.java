package com.zlt.aps.lh.regression;

import com.zlt.aps.lh.api.domain.dto.MachineCleaningWindowDTO;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.api.domain.dto.MachineScheduleDTO;
import com.zlt.aps.lh.engine.strategy.impl.DefaultCapacityCalculateStrategy;
import com.zlt.aps.lh.util.LhScheduleTimeUtil;
import com.zlt.aps.mdm.api.domain.entity.MdmDevicePlanShut;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 产能系数相关：维修开产时间依赖上下文中的停机计划列表（由 loadDevicePlanShut 装入窗口内计划）。
 */
class DefaultCapacityCalculateStrategyRegressionTest {

    private final DefaultCapacityCalculateStrategy strategy = new DefaultCapacityCalculateStrategy();

    @Test
    void calculateStartTime_usesShutPlanDurationWithinScheduleWindow() {
        String machineCode = "M1";
        Date repairDay = date(2026, 4, 3);
        Date begin = LhScheduleTimeUtil.buildTime(repairDay, 8, 0, 0);
        Date end = LhScheduleTimeUtil.addHours(begin, 10);

        MdmDevicePlanShut shut = new MdmDevicePlanShut();
        shut.setMachineCode(machineCode);
        shut.setBeginDate(begin);
        shut.setEndDate(end);

        LhScheduleContext context = new LhScheduleContext();
        context.setDevicePlanShutList(Collections.singletonList(shut));

        MachineScheduleDTO dto = new MachineScheduleDTO();
        dto.setMachineCode(machineCode);
        dto.setHasRepairPlan(true);
        dto.setRepairPlanTime(repairDay);

        LinkedHashMap<String, MachineScheduleDTO> map = new LinkedHashMap<>();
        map.put(machineCode, dto);
        context.setMachineScheduleMap(map);

        Date ending = LhScheduleTimeUtil.buildTime(date(2026, 4, 2), 12, 0, 0);
        Date startTime = strategy.calculateStartTime(context, machineCode, ending);

        // 语义已调整为“机台准备就绪时间”，应至少覆盖维修 10 小时
        Date expectedMin = LhScheduleTimeUtil.addHours(begin, 10);
        assertTrue(!startTime.before(expectedMin), "机台准备就绪时间应反映停机计划时长（10h）");
    }

    @Test
    void calculateStartTime_shouldNotPreConsumeDryIceCleaningReadyTime() {
        String machineCode = "K1514";
        LhScheduleContext context = new LhScheduleContext();

        MachineCleaningWindowDTO dryIceWindow = new MachineCleaningWindowDTO();
        dryIceWindow.setCleanType("01");
        dryIceWindow.setLeftRightMould("LR");
        dryIceWindow.setCleanStartTime(dateTime(2026, 4, 21, 8, 22, 22));
        dryIceWindow.setCleanEndTime(dateTime(2026, 4, 21, 11, 22, 22));
        dryIceWindow.setReadyTime(dateTime(2026, 4, 21, 11, 22, 22));

        MachineScheduleDTO dto = new MachineScheduleDTO();
        dto.setMachineCode(machineCode);
        dto.setHasDryIceCleaning(true);
        dto.setCleaningWindowList(Arrays.asList(dryIceWindow));

        LinkedHashMap<String, MachineScheduleDTO> machineScheduleMap = new LinkedHashMap<>();
        machineScheduleMap.put(machineCode, dto);
        context.setMachineScheduleMap(machineScheduleMap);

        Date ending = dateTime(2026, 4, 21, 9, 0, 0);
        Date startTime = strategy.calculateStartTime(context, machineCode, ending);

        assertEquals(ending, startTime,
                "机台准备就绪时间不应再提前固化干冰清洗readyTime");
    }

    @Test
    void calculateStartTime_shouldNotPreConsumeSandBlastReadyTime() {
        String machineCode = "K1514";
        LhScheduleContext context = new LhScheduleContext();

        MachineCleaningWindowDTO sandBlastWindow = new MachineCleaningWindowDTO();
        sandBlastWindow.setCleanType("02");
        sandBlastWindow.setLeftRightMould("LR");
        sandBlastWindow.setCleanStartTime(dateTime(2026, 4, 21, 8, 22, 22));
        sandBlastWindow.setCleanEndTime(dateTime(2026, 4, 21, 18, 22, 22));
        sandBlastWindow.setReadyTime(dateTime(2026, 4, 21, 20, 22, 22));

        MachineScheduleDTO dto = new MachineScheduleDTO();
        dto.setMachineCode(machineCode);
        dto.setHasSandBlastCleaning(true);
        dto.setCleaningWindowList(Arrays.asList(sandBlastWindow));

        LinkedHashMap<String, MachineScheduleDTO> machineScheduleMap = new LinkedHashMap<>();
        machineScheduleMap.put(machineCode, dto);
        context.setMachineScheduleMap(machineScheduleMap);

        Date ending = dateTime(2026, 4, 21, 9, 0, 0);
        Date startTime = strategy.calculateStartTime(context, machineCode, ending);

        assertEquals(ending, startTime,
                "机台准备就绪时间不应再提前固化喷砂清洗readyTime");
    }

    @Test
    void calculateStartTime_shouldIgnoreCleaningWindowsInReadyTimeStage() {
        String machineCode = "K1514";
        LhScheduleContext context = new LhScheduleContext();

        MachineCleaningWindowDTO currentWindow = new MachineCleaningWindowDTO();
        currentWindow.setCleanType("01");
        currentWindow.setLeftRightMould("LR");
        currentWindow.setCleanStartTime(dateTime(2026, 4, 21, 8, 0, 0));
        currentWindow.setCleanEndTime(dateTime(2026, 4, 21, 11, 0, 0));
        currentWindow.setReadyTime(dateTime(2026, 4, 21, 11, 0, 0));

        MachineCleaningWindowDTO laterWindow = new MachineCleaningWindowDTO();
        laterWindow.setCleanType("02");
        laterWindow.setLeftRightMould("LR");
        laterWindow.setCleanStartTime(dateTime(2026, 4, 21, 14, 0, 0));
        laterWindow.setCleanEndTime(dateTime(2026, 4, 22, 0, 0, 0));
        laterWindow.setReadyTime(dateTime(2026, 4, 22, 2, 0, 0));

        MachineScheduleDTO dto = new MachineScheduleDTO();
        dto.setMachineCode(machineCode);
        dto.setCleaningWindowList(Arrays.asList(currentWindow, laterWindow));

        LinkedHashMap<String, MachineScheduleDTO> machineScheduleMap = new LinkedHashMap<>();
        machineScheduleMap.put(machineCode, dto);
        context.setMachineScheduleMap(machineScheduleMap);

        Date ending = dateTime(2026, 4, 21, 9, 0, 0);
        Date startTime = strategy.calculateStartTime(context, machineCode, ending);

        assertEquals(ending, startTime,
                "清洗与换模/换活字块的重叠判定应下沉到后续切换链路，不在readyTime阶段处理");
    }

    private static Date date(int y, int month, int day) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(Calendar.YEAR, y);
        c.set(Calendar.MONTH, month - 1);
        c.set(Calendar.DAY_OF_MONTH, day);
        return c.getTime();
    }

    private static Date dateTime(int y, int month, int day, int hour, int minute, int second) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(Calendar.YEAR, y);
        c.set(Calendar.MONTH, month - 1);
        c.set(Calendar.DAY_OF_MONTH, day);
        c.set(Calendar.HOUR_OF_DAY, hour);
        c.set(Calendar.MINUTE, minute);
        c.set(Calendar.SECOND, second);
        return c.getTime();
    }
}
