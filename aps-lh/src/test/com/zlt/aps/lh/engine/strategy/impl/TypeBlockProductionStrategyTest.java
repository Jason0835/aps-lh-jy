package com.zlt.aps.lh.engine.strategy.impl;

import com.zlt.aps.lh.api.domain.dto.MachineScheduleDTO;
import com.zlt.aps.lh.api.domain.dto.SkuDailyPlanQuotaDTO;
import com.zlt.aps.lh.api.domain.dto.SkuScheduleDTO;
import com.zlt.aps.lh.api.domain.entity.LhScheduleResult;
import com.zlt.aps.lh.api.enums.SkuTagEnum;
import com.zlt.aps.lh.component.TargetScheduleQtyResolver;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.util.ShiftFieldUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;

/**
 * 换活字块目标量规则测试。
 *
 * @author APS
 */
public class TypeBlockProductionStrategyTest {

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
