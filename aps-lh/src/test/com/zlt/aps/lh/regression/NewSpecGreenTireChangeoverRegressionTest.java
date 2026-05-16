package com.zlt.aps.lh.regression;

import com.zlt.aps.lh.api.domain.dto.SkuScheduleDTO;
import com.zlt.aps.lh.api.domain.entity.LhScheduleResult;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.engine.strategy.IMouldChangeBalanceStrategy;
import com.zlt.aps.lh.engine.strategy.impl.NewSpecProductionStrategy;
import com.zlt.aps.lh.service.impl.RollingScheduleHandoffService;
import com.zlt.aps.lh.util.LhScheduleTimeUtil;
import com.zlt.aps.lh.util.ShiftFieldUtil;
import com.zlt.aps.mp.api.domain.entity.FactoryMonthPlanProductionFinalResult;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.lang.reflect.Method;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 新增规格同胎胚换模错峰回归测试。
 */
class NewSpecGreenTireChangeoverRegressionTest {

    @Test
    void allocateGreenTireAwareMouldChange_shouldSkipRollingInheritedOccupiedShift() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        LhScheduleContext context = new LhScheduleContext();
        context.setScheduleDate(dateTime(2026, 4, 13, 0, 0));
        context.setScheduleWindowShifts(LhScheduleTimeUtil.buildDefaultScheduleShifts(
                context, context.getScheduleDate()));

        LhScheduleResult inheritedResult = buildChangeResult(
                "K1105", "MAT-A", "EMB-A", dateTime(2026, 4, 13, 6, 0));
        inheritedResult.setRollingInherited(true);
        context.getScheduleResultList().add(inheritedResult);
        context.getRollingInheritedScheduleResultList().add(inheritedResult);

        SkuScheduleDTO sku = new SkuScheduleDTO();
        sku.setMaterialCode("MAT-B");
        sku.setEmbryoCode("EMB-A");

        Method method = NewSpecProductionStrategy.class.getDeclaredMethod(
                "allocateGreenTireAwareMouldChange",
                LhScheduleContext.class,
                SkuScheduleDTO.class,
                String.class,
                Date.class,
                int.class,
                IMouldChangeBalanceStrategy.class);
        method.setAccessible(true);

        Date allocated = (Date) method.invoke(
                strategy,
                context,
                sku,
                "K1110",
                dateTime(2026, 4, 13, 6, 30),
                4,
                new ProbeAtReadyTimeBalanceStrategy());

        assertEquals(dateTime(2026, 4, 13, 14, 0), allocated);
    }

    @Test
    void rollingInheritedResult_shouldPreserveRealMouldChangeStartTime() {
        RollingScheduleHandoffService service = new RollingScheduleHandoffService();
        LhScheduleContext context = new LhScheduleContext();
        context.setFactoryCode("116");
        context.setBatchNo("LHPC20260426001");
        context.setMonthPlanVersion("MP-V1");
        context.setProductionVersion("PV-V1");
        context.setScheduleDate(dateTime(2026, 4, 24, 0, 0));
        context.setScheduleTargetDate(dateTime(2026, 4, 26, 0, 0));
        context.setScheduleWindowShifts(LhScheduleTimeUtil.buildDefaultScheduleShifts(
                context, context.getScheduleDate()));
        context.setMachineScheduleMap(new LinkedHashMap<String, com.zlt.aps.lh.api.domain.dto.MachineScheduleDTO>());
        context.setInitialMachineScheduleMap(new LinkedHashMap<String, com.zlt.aps.lh.api.domain.dto.MachineScheduleDTO>());
        context.setMonthPlanList(Collections.singletonList(buildPlan("MAT-A")));
        context.setPreviousScheduleResultList(Collections.singletonList(buildRollingInheritedSourceResult()));

        service.apply(context);

        assertEquals(1, context.getRollingInheritedScheduleResultList().size());
        assertEquals(dateTime(2026, 4, 24, 10, 0),
                context.getRollingInheritedScheduleResultList().get(0).getMouldChangeStartTime());
    }

    private static LhScheduleResult buildChangeResult(String machineCode,
                                                      String materialCode,
                                                      String embryoCode,
                                                      Date mouldChangeStartTime) {
        LhScheduleResult result = new LhScheduleResult();
        result.setLhMachineCode(machineCode);
        result.setMaterialCode(materialCode);
        result.setEmbryoCode(embryoCode);
        result.setScheduleType("02");
        result.setIsChangeMould("1");
        result.setMouldCode("MOULD-" + materialCode);
        result.setDailyPlanQty(16);
        result.setSpecEndTime(dateTime(2026, 4, 13, 14, 0));
        result.setMouldChangeStartTime(mouldChangeStartTime);
        return result;
    }

    private static Date dateTime(int year, int month, int day, int hour, int minute) {
        java.util.Calendar calendar = java.util.Calendar.getInstance();
        calendar.clear();
        calendar.set(java.util.Calendar.YEAR, year);
        calendar.set(java.util.Calendar.MONTH, month - 1);
        calendar.set(java.util.Calendar.DAY_OF_MONTH, day);
        calendar.set(java.util.Calendar.HOUR_OF_DAY, hour);
        calendar.set(java.util.Calendar.MINUTE, minute);
        return calendar.getTime();
    }

    private static FactoryMonthPlanProductionFinalResult buildPlan(String materialCode) {
        FactoryMonthPlanProductionFinalResult plan = new FactoryMonthPlanProductionFinalResult();
        plan.setMaterialCode(materialCode);
        plan.setMaterialDesc("物料A");
        plan.setStructureName("S1");
        plan.setMonthPlanVersion("MP-V1");
        plan.setProductionVersion("PV-V1");
        return plan;
    }

    private static LhScheduleResult buildRollingInheritedSourceResult() {
        LhScheduleResult result = new LhScheduleResult();
        result.setFactoryCode("116");
        result.setBatchNo("LHPC20260425001");
        result.setScheduleDate(dateTime(2026, 4, 25, 0, 0));
        result.setRealScheduleDate(dateTime(2026, 4, 23, 0, 0));
        result.setMonthPlanVersion("MP-V1");
        result.setProductionVersion("PV-V1");
        result.setLhMachineCode("K1105");
        result.setLhMachineName("机台1");
        result.setMaterialCode("MAT-A");
        result.setMaterialDesc("物料A");
        result.setSpecCode("SPEC-A");
        result.setScheduleType("02");
        result.setIsChangeMould("1");
        result.setIsTypeBlock("0");
        result.setMouldChangeStartTime(dateTime(2026, 4, 24, 10, 0));
        result.setSpecEndTime(dateTime(2026, 4, 24, 22, 0));
        ShiftFieldUtil.setShiftPlanQty(result, 5, 16,
                dateTime(2026, 4, 24, 14, 0), dateTime(2026, 4, 24, 22, 0));
        result.setDailyPlanQty(16);
        return result;
    }

    /**
     * 按当前探测起点回放换模分配，便于验证同胎胚冲突时是否会顺延到下一班次。
     */
    private static class ProbeAtReadyTimeBalanceStrategy implements IMouldChangeBalanceStrategy {

        @Override
        public boolean hasCapacity(LhScheduleContext context, Date targetDate) {
            return true;
        }

        @Override
        public Date allocateMouldChange(LhScheduleContext context, String machineCode, Date endingTime) {
            return endingTime;
        }

        @Override
        public int getRemainingCapacity(LhScheduleContext context, Date targetDate) {
            return Integer.MAX_VALUE;
        }
    }
}
