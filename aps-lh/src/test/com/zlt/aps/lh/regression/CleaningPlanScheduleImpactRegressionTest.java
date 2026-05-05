package com.zlt.aps.lh.regression;

import com.zlt.aps.lh.api.constant.LhScheduleParamConstant;
import com.zlt.aps.lh.api.domain.dto.MachineCleaningWindowDTO;
import com.zlt.aps.lh.api.domain.dto.MachineScheduleDTO;
import com.zlt.aps.lh.api.domain.entity.LhScheduleResult;
import com.zlt.aps.lh.api.domain.vo.LhShiftConfigVO;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.engine.strategy.impl.ContinuousProductionStrategy;
import com.zlt.aps.lh.engine.strategy.impl.NewSpecProductionStrategy;
import com.zlt.aps.lh.util.LhScheduleTimeUtil;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 模具清洗影响排产回归：班次扣量与完工时间需同步生效。
 */
class CleaningPlanScheduleImpactRegressionTest {

    @Test
    void newSpecDistributeToShifts_shouldRefreshSpecEndTimeAfterCleaningLoss() {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        LhScheduleContext context = buildSingleShiftContext();
        MachineScheduleDTO machine = buildMachineWithDryIceCleaning();
        context.getMachineScheduleMap().put("K1514", machine);

        LhScheduleResult result = buildResult("K1514", "02");
        List<LhShiftConfigVO> shifts = context.getScheduleWindowShifts();
        Date shiftStartTime = shifts.get(0).getShiftStartDateTime();

        Integer remainingQty = ReflectionTestUtils.invokeMethod(strategy, "distributeToShifts",
                context, result, shifts, shiftStartTime, 18, 1600, 1, 18, machine.getCleaningWindowList());
        ReflectionTestUtils.invokeMethod(strategy, "refreshResultSummary", context, result);

        assertEquals(7, remainingQty.intValue(), "单班排产时，干冰清洗按剩余时间折算后仍应保留未排数量");
        assertEquals(11, result.getDailyPlanQty(), "新增排产班次计划量应按剩余有效时间折算");
        assertEquals(11, result.getClass1PlanQty());
        assertEquals(dateTime(2026, 4, 21, 14, 0, 0), result.getSpecEndTime());
        assertEquals(result.getSpecEndTime(), result.getTdaySpecEndTime());
    }

    @Test
    void continuousRedistributeShiftQty_shouldRefreshSpecEndTimeAfterCleaningLoss() {
        ContinuousProductionStrategy strategy = new ContinuousProductionStrategy();
        LhScheduleContext context = buildSingleShiftContext();
        context.getMachineScheduleMap().put("K1514", buildMachineWithDryIceCleaning());

        LhScheduleResult result = buildResult("K1514", "01");
        result.setIsEnd("1");
        result.setSingleMouldShiftQty(18);

        List<LhShiftConfigVO> shifts = context.getScheduleWindowShifts();
        ReflectionTestUtils.invokeMethod(strategy, "redistributeShiftQty", context, result, shifts, 18);

        assertEquals(11, result.getDailyPlanQty(), "续作重分配班次计划量应按剩余有效时间折算");
        assertEquals(11, result.getClass1PlanQty());
        assertNotNull(result.getSpecEndTime());
        assertEquals(dateTime(2026, 4, 21, 13, 53, 20), result.getSpecEndTime());
        assertEquals(result.getSpecEndTime(), result.getTdaySpecEndTime());
    }

    @Test
    void allocateShiftPlanQty_shouldApplyDryIceLossForTypeBlockResult() {
        ContinuousProductionStrategy strategy = new ContinuousProductionStrategy();
        LhScheduleContext context = buildSingleShiftContext();
        context.getMachineScheduleMap().put("K1514", buildMachineWithDryIceCleaning());

        LhScheduleResult result = buildResult("K1514", "02");
        result.setIsTypeBlock("1");
        result.setIsChangeMould("1");
        result.setIsEnd("1");
        result.setSingleMouldShiftQty(18);
        result.setClass1PlanQty(18);
        result.setClass1StartTime(dateTime(2026, 4, 21, 6, 0, 0));
        result.setClass1EndTime(dateTime(2026, 4, 21, 14, 0, 0));
        context.getScheduleResultList().add(result);

        strategy.allocateShiftPlanQty(context);

        assertEquals(11, result.getDailyPlanQty(), "换活字块结果重新分配班次时也应按剩余有效时间折算");
        assertEquals(11, result.getClass1PlanQty());
        assertNotNull(result.getSpecEndTime());
        assertEquals(dateTime(2026, 4, 21, 13, 53, 20), result.getSpecEndTime());
        assertEquals(result.getSpecEndTime(), result.getTdaySpecEndTime());
    }

    private LhScheduleContext buildSingleShiftContext() {
        LhScheduleContext context = new LhScheduleContext();
        Date scheduleDate = date(2026, 4, 21);
        context.setFactoryCode("116");
        context.setScheduleDate(scheduleDate);
        context.setScheduleTargetDate(scheduleDate);
        List<LhShiftConfigVO> allShifts = LhScheduleTimeUtil.buildDefaultScheduleShifts(context, scheduleDate);
        List<LhShiftConfigVO> singleShiftList = new ArrayList<>();
        singleShiftList.add(allShifts.get(0));
        context.setScheduleWindowShifts(singleShiftList);
        context.setMachineScheduleMap(new LinkedHashMap<>());
        context.setDevicePlanShutList(Collections.emptyList());
        context.getLhParamsMap().put(LhScheduleParamConstant.DRY_ICE_LOSS_QTY, "6");
        return context;
    }

    private MachineScheduleDTO buildMachineWithDryIceCleaning() {
        MachineCleaningWindowDTO cleaningWindow = new MachineCleaningWindowDTO();
        cleaningWindow.setCleanType("01");
        cleaningWindow.setLeftRightMould("LR");
        cleaningWindow.setCleanStartTime(dateTime(2026, 4, 21, 8, 22, 22));
        cleaningWindow.setCleanEndTime(dateTime(2026, 4, 21, 11, 22, 22));
        cleaningWindow.setReadyTime(dateTime(2026, 4, 21, 11, 22, 22));

        MachineScheduleDTO machine = new MachineScheduleDTO();
        machine.setMachineCode("K1514");
        machine.setMaxMoldNum(1);
        machine.setHasDryIceCleaning(true);
        List<MachineCleaningWindowDTO> cleaningWindowList = new ArrayList<>();
        cleaningWindowList.add(cleaningWindow);
        machine.setCleaningWindowList(cleaningWindowList);
        return machine;
    }

    private LhScheduleResult buildResult(String machineCode, String scheduleType) {
        LhScheduleResult result = new LhScheduleResult();
        result.setLhMachineCode(machineCode);
        result.setScheduleType(scheduleType);
        result.setLhTime(1600);
        result.setMouldQty(1);
        result.setSingleMouldShiftQty(18);
        return result;
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
