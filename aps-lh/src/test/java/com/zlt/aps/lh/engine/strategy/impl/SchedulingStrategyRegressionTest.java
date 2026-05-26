package com.zlt.aps.lh.engine.strategy.impl;

import com.zlt.aps.lh.api.domain.dto.SkuDailyPlanQuotaDTO;
import com.zlt.aps.lh.api.domain.dto.MachineScheduleDTO;
import com.zlt.aps.lh.api.domain.dto.SkuScheduleDTO;
import com.zlt.aps.lh.api.domain.entity.LhScheduleResult;
import com.zlt.aps.lh.api.domain.vo.LhShiftConfigVO;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.engine.strategy.support.MachineProductionSegment;
import com.zlt.aps.lh.engine.strategy.support.MachineScheduleRole;
import com.zlt.aps.lh.util.ShiftFieldUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Date;
import java.lang.reflect.Method;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 排程策略回归测试。
 *
 * @author APS
 */
public class SchedulingStrategyRegressionTest {

    /**
     * 换活字块只有 1 台可直接承接时，如果后续还能转新增换模扩机，目标量不能被压死到单台窗口产能。
     */
    @Test
    public void shouldKeepQuotaDemandForTypeBlockExpansion() throws Exception {
        TypeBlockProductionStrategy strategy = new TypeBlockProductionStrategy();
        SkuScheduleDTO sku = buildSkuForTypeBlockExpansion();

        Method method = TypeBlockProductionStrategy.class.getDeclaredMethod(
                "resolveSingleMachineTypeBlockTargetQty",
                SkuScheduleDTO.class, int.class, boolean.class);
        method.setAccessible(true);
        int targetQty = (Integer) method.invoke(strategy, sku, 119, true);

        Assertions.assertEquals(300, targetQty);
    }

    /**
     * 当 dayN 模拟确认尾机台不需要再多吃一整班时，应去掉额外补上的那一班。
     */
    @Test
    public void shouldTrimExtraTailShiftWhenDailyCapacityIsSatisfied() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        MachineProductionSegment segment = new MachineProductionSegment();
        segment.setRole(MachineScheduleRole.TAIL_MACHINE);
        segment.setShiftCapacity(17);
        segment.setMaxQtyToWindowEnd(119);

        Method method = NewSpecProductionStrategy.class.getDeclaredMethod(
                "resolveSettledTailMachinePlanQty",
                MachineProductionSegment.class, int.class, int.class);
        method.setAccessible(true);
        int planQty = (Integer) method.invoke(strategy, segment, 81, 102);

        Assertions.assertEquals(85, planQty);
    }

    /**
     * dayN 扩机模拟需要把已落地的换活字块结果视为已启用机台，避免 S4.5 再重复扩一台。
     */
    @Test
    public void shouldIncludeExistingTypeBlockMachineInDailyCapacitySimulation() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        LhScheduleContext context = new LhScheduleContext();
        List<LhShiftConfigVO> shifts = buildSimulationShifts();
        context.setScheduleWindowShifts(shifts);

        LhScheduleResult typeBlockResult = new LhScheduleResult();
        typeBlockResult.setMaterialCode("3302002654");
        typeBlockResult.setLhMachineCode("K2024");
        typeBlockResult.setScheduleType("03");
        ShiftFieldUtil.setShiftPlanQty(typeBlockResult, 1, 17, new Date(), new Date());
        ShiftFieldUtil.setShiftPlanQty(typeBlockResult, 2, 17, new Date(), new Date());
        ShiftFieldUtil.setShiftPlanQty(typeBlockResult, 3, 17, new Date(), new Date());
        ShiftFieldUtil.syncDailyPlanQty(typeBlockResult);
        context.setScheduleResultList(new ArrayList<LhScheduleResult>());
        context.getScheduleResultList().add(typeBlockResult);

        SkuScheduleDTO sku = buildSkuForTypeBlockExpansion();
        MachineScheduleDTO currentMachine = new MachineScheduleDTO();
        currentMachine.setMachineCode("K1111");

        Method capacityMapMethod = NewSpecProductionStrategy.class.getDeclaredMethod(
                "buildExistingSameMaterialCapacityMaps",
                LhScheduleContext.class, SkuScheduleDTO.class, MachineScheduleDTO.class,
                List.class, Map.class);
        capacityMapMethod.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<Map<LocalDate, Integer>> existingCapacityMaps =
                (List<Map<LocalDate, Integer>>) capacityMapMethod.invoke(
                        strategy, context, sku, currentMachine, shifts, sku.getDailyPlanQuotaMap());

        Assertions.assertEquals(1, existingCapacityMaps.size());
        Assertions.assertEquals(Integer.valueOf(17), existingCapacityMaps.get(0).get(LocalDate.of(2026, 5, 1)));
        Assertions.assertEquals(Integer.valueOf(17), existingCapacityMaps.get(0).get(LocalDate.of(2026, 5, 2)));
        Assertions.assertEquals(Integer.valueOf(17), existingCapacityMaps.get(0).get(LocalDate.of(2026, 5, 3)));

        Method machineCountMethod = NewSpecProductionStrategy.class.getDeclaredMethod(
                "resolveRequiredNewSpecMachineCount", int.class, int.class);
        machineCountMethod.setAccessible(true);
        int requiredMachineCount = (Integer) machineCountMethod.invoke(strategy, 3, existingCapacityMaps.size());

        Assertions.assertEquals(2, requiredMachineCount);
    }

    private SkuScheduleDTO buildSkuForTypeBlockExpansion() {
        SkuScheduleDTO sku = new SkuScheduleDTO();
        sku.setMaterialCode("3302002654");
        sku.setTargetScheduleQty(136);
        sku.setWindowPlanQty(300);
        sku.setWindowRemainingPlanQty(300);
        sku.setSurplusQty(1752);
        sku.setDailyPlanQuotaMap(buildQuotaMap(100, 100, 100));
        return sku;
    }

    private Map<LocalDate, SkuDailyPlanQuotaDTO> buildQuotaMap(int day1Qty, int day2Qty, int day3Qty) {
        Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap = new LinkedHashMap<LocalDate, SkuDailyPlanQuotaDTO>(4);
        quotaMap.put(LocalDate.of(2026, 5, 1), buildQuota(day1Qty));
        quotaMap.put(LocalDate.of(2026, 5, 2), buildQuota(day2Qty));
        quotaMap.put(LocalDate.of(2026, 5, 3), buildQuota(day3Qty));
        return quotaMap;
    }

    private SkuDailyPlanQuotaDTO buildQuota(int qty) {
        SkuDailyPlanQuotaDTO quota = new SkuDailyPlanQuotaDTO();
        quota.setDayPlanQty(qty);
        quota.setRemainingQty(qty);
        return quota;
    }

    private List<LhShiftConfigVO> buildSimulationShifts() {
        List<LhShiftConfigVO> shifts = new ArrayList<LhShiftConfigVO>(3);
        shifts.add(buildShift(1, 0));
        shifts.add(buildShift(2, 1));
        shifts.add(buildShift(3, 2));
        return shifts;
    }

    private LhShiftConfigVO buildShift(int shiftIndex, int dateOffset) {
        LhShiftConfigVO shift = new LhShiftConfigVO();
        shift.setShiftIndex(shiftIndex);
        shift.setDateOffset(dateOffset);
        shift.setScheduleBaseDate(Date.from(LocalDate.of(2026, 5, 1)
                .atStartOfDay(ZoneId.systemDefault()).toInstant()));
        return shift;
    }
}
