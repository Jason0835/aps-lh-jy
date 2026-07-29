package com.zlt.aps.lh.engine.strategy.impl;

import com.zlt.aps.lh.api.constant.LhScheduleParamConstant;
import com.zlt.aps.lh.api.domain.dto.MachineScheduleDTO;
import com.zlt.aps.lh.api.domain.dto.SkuDailyPlanQuotaDTO;
import com.zlt.aps.lh.api.domain.dto.SkuScheduleDTO;
import com.zlt.aps.lh.api.domain.entity.LhScheduleResult;
import com.zlt.aps.lh.api.domain.vo.LhShiftConfigVO;
import com.zlt.aps.lh.api.enums.SkuTagEnum;
import com.zlt.aps.lh.component.TargetScheduleQtyResolver;
import com.zlt.aps.lh.context.LhScheduleConfig;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.util.LhScheduleTimeUtil;
import com.zlt.aps.lh.util.ShiftFieldUtil;
import com.zlt.aps.mdm.api.domain.entity.MdmSkuLhCapacity;
import com.zlt.aps.mp.api.domain.entity.FactoryMonthPlanProductionFinalResult;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 换活字块目标量规则测试。
 *
 * @author APS
 */
public class TypeBlockProductionStrategyTest {

    /**
     * 用例说明：换活字块排产已耗尽本产品状态账本时，不得按裁剪前目标量回流新增排产。
     */
    @Test
    public void resolveRemainingQtyForNewSchedule_shouldStopWhenStatusLedgerExhausted() {
        TypeBlockProductionStrategy strategy = new TypeBlockProductionStrategy();
        TargetScheduleQtyResolver resolver = new TargetScheduleQtyResolver();
        ReflectionTestUtils.setField(strategy, "targetScheduleQtyResolver", resolver);
        LhScheduleContext context = new LhScheduleContext();
        SkuScheduleDTO sku = new SkuScheduleDTO();
        sku.setMaterialCode("3302001897");
        sku.setProductStatus("X");
        sku.setSurplusQty(4);
        sku.setTargetScheduleQty(112);
        resolver.initializeProductionRemainingQty(context, sku, 112, "换活字块多状态回流测试");
        resolver.deductProductionRemainingQty(context, sku, 4, "换活字块测试", "K2001");

        Integer remainingQty = ReflectionTestUtils.invokeMethod(strategy,
                "resolveRemainingQtyForNewSchedule", context, sku, 112, 4);

        Assertions.assertEquals(Integer.valueOf(0), remainingQty);
    }

    /**
     * 用例说明：成型胎胚库存收尾时，不能因共用胎胚硫化余量为0提前进入未排。
     */
    @Test
    public void isSharedEmbryoZeroSurplusSku_shouldIgnoreEmbryoStockEnding() {
        TypeBlockProductionStrategy strategy = new TypeBlockProductionStrategy();
        LhScheduleContext context = new LhScheduleContext();
        context.getMaterialSharedEmbryoMap().put("3302005002", true);
        context.getEmbryoEndingFlagMap().put("EMB-END-02", 1);
        SkuScheduleDTO sku = new SkuScheduleDTO();
        sku.setMaterialCode("3302005002");
        sku.setEmbryoCode("EMB-END-02");
        sku.setSurplusQty(0);
        sku.setEmbryoStock(5);
        sku.setSkuTag(SkuTagEnum.ENDING.getCode());
        sku.setEndingDaysRemaining(1);

        Boolean sharedZeroSurplus = ReflectionTestUtils.invokeMethod(
                strategy, "isSharedEmbryoZeroSurplusSku", context, sku);

        Assertions.assertFalse(Boolean.TRUE.equals(sharedZeroSurplus));
    }

    /**
     * 用例说明：双模换活字块应按L/R合计10条只扣一次账本，再均分为每侧5条。
     */
    @Test
    public void applyWholeSingleControlTypeBlockToDailyQuota_shouldDeductGroupQtyOnce() {
        TypeBlockProductionStrategy strategy = new TypeBlockProductionStrategy();
        TargetScheduleQtyResolver targetScheduleQtyResolver = new TargetScheduleQtyResolver();
        ReflectionTestUtils.setField(strategy, "targetScheduleQtyResolver", targetScheduleQtyResolver);
        LhScheduleContext context = new LhScheduleContext();
        SkuScheduleDTO sku = new SkuScheduleDTO();
        sku.setMaterialCode("DOUBLE-TYPE-BLOCK");
        sku.setTargetScheduleQty(10);
        sku.setMouldQty(1);
        targetScheduleQtyResolver.initializeProductionRemainingQty(context, sku, 10, "双模换活字块测试");

        LhScheduleResult primaryResult = result("K1501L", 5);
        LhScheduleResult pairResult = result("K1501R", 5);

        Integer actualQty = ReflectionTestUtils.invokeMethod(strategy,
                "applyWholeSingleControlTypeBlockToDailyQuota",
                context, sku, primaryResult, pairResult, Collections.emptyList());

        Assertions.assertEquals(Integer.valueOf(10), actualQty);
        Assertions.assertEquals(Integer.valueOf(5), primaryResult.getClass1PlanQty());
        Assertions.assertEquals(Integer.valueOf(5), pairResult.getClass1PlanQty());
        Assertions.assertEquals(Integer.valueOf(0),
                context.getSkuProductionRemainingQtyMap().get(sku.getMaterialCode()));
    }

    /**
     * 用例说明：换活字块只有结构命中参数时才允许使用日标准量理论上限和补差。
     */
    @Test
    public void calculateDailyStandardShiftCapacityMap_shouldGateByStructureList() {
        TypeBlockProductionStrategy strategy = new TypeBlockProductionStrategy();
        LhScheduleContext context = new LhScheduleContext();
        Date scheduleDate = Date.from(LocalDate.of(2026, 7, 25)
                .atStartOfDay(ZoneId.systemDefault()).toInstant());
        context.setScheduleDate(scheduleDate);
        context.setScheduleWindowShifts(LhScheduleTimeUtil.buildDefaultScheduleShifts(context, scheduleDate));
        context.setScheduleConfig(new LhScheduleConfig(Collections.singletonMap(
                LhScheduleParamConstant.DAILY_STANDARD_CAPACITY_STRUCTURE_LIST, "PCR-日标准")));

        MdmSkuLhCapacity capacity = new MdmSkuLhCapacity();
        capacity.setMaterialCode("3302002177");
        capacity.setClassCapacity(16);
        capacity.setStandardCapacity(50);
        capacity.setApsCapacity(54);
        context.getSkuLhCapacityMap().put(capacity.getMaterialCode(), capacity);

        LhScheduleResult result = new LhScheduleResult();
        result.setMaterialCode(capacity.getMaterialCode());
        result.setLhMachineCode("K1611");
        result.setStructureName("PCR-日标准");
        List<LhShiftConfigVO> shifts = context.getScheduleWindowShifts();
        Map<Integer, Integer> matchedCapacityMap = ReflectionTestUtils.invokeMethod(
                strategy, "calculateDailyStandardShiftCapacityMap",
                context, result, shifts, shifts.get(0).getShiftStartDateTime(),
                16, 2880, 2, Collections.emptyList(), Collections.emptyList());

        result.setStructureName("PCR-未配置");
        Map<Integer, Integer> unmatchedCapacityMap = ReflectionTestUtils.invokeMethod(
                strategy, "calculateDailyStandardShiftCapacityMap",
                context, result, shifts, shifts.get(0).getShiftStartDateTime(),
                16, 2880, 2, Collections.emptyList(), Collections.emptyList());

        Assertions.assertEquals(Integer.valueOf(18), matchedCapacityMap.get(5),
                "命中结构的中班应使用日标准量理论上限补足到18");
        Assertions.assertEquals(Integer.valueOf(16), unmatchedCapacityMap.get(5),
                "未命中结构的中班应保持原始班产16");
        Assertions.assertEquals(Integer.valueOf(16), unmatchedCapacityMap.get(8),
                "未命中结构的后续业务日中班也不得启用日标准量补差");
    }

    /**
     * 用例说明：换活字块候选同时命中收尾小余量和仅历史欠产时，应按小余量原因和数量写入未排。
     */
    @Test
    public void handlePendingSkuUnscheduledRule_shouldPreferSmallEndingSurplus() {
        TypeBlockProductionStrategy strategy = new TypeBlockProductionStrategy();
        ReflectionTestUtils.setField(strategy, "targetScheduleQtyResolver", new TargetScheduleQtyResolver());

        LhScheduleContext context = new LhScheduleContext();
        context.setFactoryCode("116");
        context.setBatchNo("TEST-TYPE-BLOCK-PRIORITY");
        Date scheduleDate = Date.from(LocalDate.of(2026, 6, 14)
                .atStartOfDay(ZoneId.systemDefault()).toInstant());
        context.setScheduleDate(scheduleDate);
        context.setScheduleTargetDate(scheduleDate);

        SkuScheduleDTO sku = new SkuScheduleDTO();
        sku.setMaterialCode("3302001320");
        sku.setMaterialDesc("换活字块双规则命中物料");
        sku.setProductStatus("S");
        sku.setShiftCapacity(16);
        sku.setMouldQty(1);
        sku.setSurplusQty(1);
        sku.setMonthlyHistoryShortageQty(13);
        SkuDailyPlanQuotaDTO quota = new SkuDailyPlanQuotaDTO();
        quota.setMaterialCode(sku.getMaterialCode());
        quota.setProductionDate(LocalDate.of(2026, 6, 14));
        quota.setDayPlanQty(0);
        sku.setDailyPlanQuotaMap(new LinkedHashMap<LocalDate, SkuDailyPlanQuotaDTO>(2));
        sku.getDailyPlanQuotaMap().put(quota.getProductionDate(), quota);
        context.getMaterialMonthDailyFinishedQtyMap().put("3302001320_S_2026-06-12", 18);
        context.getNewSpecSkuList().add(sku);

        MachineScheduleDTO machine = new MachineScheduleDTO();
        machine.setMachineCode("K1105");
        StringBuilder failureReason = new StringBuilder(128);
        Boolean handled = ReflectionTestUtils.invokeMethod(strategy,
                "handlePendingSkuUnscheduledRuleIfNecessary",
                context, machine, sku, true, false, failureReason);

        Assertions.assertTrue(Boolean.TRUE.equals(handled));
        Assertions.assertTrue(context.getNewSpecSkuList().isEmpty());
        Assertions.assertEquals(1, context.getUnscheduledResultList().size());
        Assertions.assertEquals(Integer.valueOf(1), context.getUnscheduledResultList().get(0).getUnscheduledQty());
        Assertions.assertEquals("收尾余量小于等于允许欠产偏差值，且前日 T+1 夜班未排满，本次不排产",
                context.getUnscheduledResultList().get(0).getUnscheduledReason());
        Assertions.assertTrue(failureReason.toString().contains("收尾余量小于等于允许欠产偏差值"));
    }

    /**
     * 用例说明：换活字块候选仅由有效上月欠产形成净余量时，应在候选落地前统一拦截。
     */
    @Test
    public void handlePendingSkuUnscheduledRule_shouldSkipEffectiveLastMonthShortageOnlyRemainingQty() {
        TypeBlockProductionStrategy strategy = new TypeBlockProductionStrategy();
        ReflectionTestUtils.setField(strategy, "targetScheduleQtyResolver", new TargetScheduleQtyResolver());

        LhScheduleContext context = new LhScheduleContext();
        context.setFactoryCode("116");
        context.setBatchNo("TEST-TYPE-BLOCK-LAST-MONTH-SHORTAGE");
        Date scheduleDate = Date.from(LocalDate.of(2026, 7, 14)
                .atStartOfDay(ZoneId.systemDefault()).toInstant());
        context.setScheduleDate(scheduleDate);
        context.setScheduleTargetDate(scheduleDate);

        SkuScheduleDTO sku = new SkuScheduleDTO();
        sku.setMaterialCode("3302002317");
        sku.setMaterialDesc("换活字块仅有效上月欠产物料");
        sku.setProductStatus("S");
        sku.setMouldQty(1);
        sku.setSurplusQty(13);
        sku.setMonthlyHistoryShortageQty(0);
        sku.setEffectiveLastMonthOverdueQty(15);
        SkuDailyPlanQuotaDTO quota = new SkuDailyPlanQuotaDTO();
        quota.setMaterialCode(sku.getMaterialCode());
        quota.setProductionDate(LocalDate.of(2026, 7, 14));
        quota.setDayPlanQty(0);
        sku.setDailyPlanQuotaMap(new LinkedHashMap<LocalDate, SkuDailyPlanQuotaDTO>(2));
        sku.getDailyPlanQuotaMap().put(quota.getProductionDate(), quota);
        context.getMaterialMonthDailyFinishedQtyMap().put("3302002317_S_2026-07-02", 50);
        context.getNewSpecSkuList().add(sku);

        MachineScheduleDTO machine = new MachineScheduleDTO();
        machine.setMachineCode("K1216");
        StringBuilder failureReason = new StringBuilder(128);
        Boolean handled = ReflectionTestUtils.invokeMethod(strategy,
                "handlePendingSkuUnscheduledRuleIfNecessary",
                context, machine, sku, false, false, failureReason);

        Assertions.assertTrue(Boolean.TRUE.equals(handled));
        Assertions.assertTrue(context.getNewSpecSkuList().isEmpty());
        Assertions.assertEquals(1, context.getUnscheduledResultList().size());
        Assertions.assertEquals(Integer.valueOf(13),
                context.getUnscheduledResultList().get(0).getUnscheduledQty());
        Assertions.assertEquals("仅历史欠产、后续无月计划，且最近一次（前一次）已有完成量，本次跳过不排",
                context.getUnscheduledResultList().get(0).getUnscheduledReason());
        Assertions.assertTrue(failureReason.toString().contains("仅历史欠产"));
    }

    /**
     * 用例说明：换活字块实际开产业务日只能读取原始 dayN，
     * 当前日为 0 时即使未来日有计划也不得主动提前拉取。
     */
    @Test
    public void resolveTypeBlockOriginalDayPlanQty_shouldRequireActualWorkDatePlan() {
        TypeBlockProductionStrategy strategy = new TypeBlockProductionStrategy();
        LhScheduleContext context = new LhScheduleContext();
        FactoryMonthPlanProductionFinalResult plan =
                new FactoryMonthPlanProductionFinalResult();
        plan.setMaterialCode("3302003001");
        plan.setProductStatus("S");
        plan.setYear(2026);
        plan.setMonth(7);
        plan.setDay30(0);
        plan.setDay31(46);
        context.setMonthPlanList(Collections.singletonList(plan));

        SkuScheduleDTO sku = new SkuScheduleDTO();
        sku.setMaterialCode(plan.getMaterialCode());
        sku.setProductStatus(plan.getProductStatus());

        Integer currentDayPlanQty = ReflectionTestUtils.invokeMethod(
                strategy, "resolveTypeBlockOriginalDayPlanQty",
                context, sku, LocalDate.of(2026, 7, 30));
        Integer futureDayPlanQty = ReflectionTestUtils.invokeMethod(
                strategy, "resolveTypeBlockOriginalDayPlanQty",
                context, sku, LocalDate.of(2026, 7, 31));

        Assertions.assertEquals(Integer.valueOf(0), currentDayPlanQty,
                "换活字块当前业务日原始计划为0时必须禁止开产");
        Assertions.assertEquals(Integer.valueOf(46), futureDayPlanQty,
                "到达真实计划业务日后才允许复用换活字块主链");
    }

    /**
     * 构建单侧换活字块结果。
     *
     * @param machineCode 机台编码
     * @param planQty 单侧计划量
     * @return 排程结果
     */
    private LhScheduleResult result(String machineCode, int planQty) {
        LhScheduleResult result = new LhScheduleResult();
        result.setLhMachineCode(machineCode);
        result.setMaterialCode("DOUBLE-TYPE-BLOCK");
        result.setMouldQty(1);
        result.setLhTime(3600);
        ShiftFieldUtil.setShiftPlanQty(result, 1, planQty, null, null);
        ShiftFieldUtil.syncDailyPlanQty(result);
        return result;
    }
}
