package com.zlt.aps.lh.handler;

import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.mdm.api.domain.entity.MdmSkuLhCapacity;
import com.zlt.aps.mp.api.domain.entity.FactoryMonthPlanProductionFinalResult;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;

/**
 * ScheduleAdjustHandler 胎胚库存分摊测试。
 *
 * @author APS
 */
public class ScheduleAdjustHandlerTest {

    /**
     * 用例说明：同胎胚库存应按 SKU 日硫化量分摊，而不是按标准产能分摊。
     *
     * @throws Exception 反射调用异常
     */
    @Test
    public void shouldAllocateEmbryoStockByDailyPlanQty() throws Exception {
        ScheduleAdjustHandler handler = new ScheduleAdjustHandler();
        LhScheduleContext context = new LhScheduleContext();

        FactoryMonthPlanProductionFinalResult firstPlan = buildMonthPlan("3302001575", "EMB-01", 20);
        FactoryMonthPlanProductionFinalResult secondPlan = buildMonthPlan("3302001724", "EMB-01", 80);
        context.setMonthPlanList(Arrays.asList(firstPlan, secondPlan));
        context.getEmbryoRealtimeStockMap().put("EMB-01", 100);

        context.getSkuLhCapacityMap().put("3302001575", buildCapacity(100));
        context.getSkuLhCapacityMap().put("3302001724", buildCapacity(100));

        Map<String, Integer> embryoSumMap = invokeBuildEmbryoStandardCapacitySumMap(handler, context);
        int allocatedStock = invokeResolveAllocatedEmbryoStock(handler, context, firstPlan, embryoSumMap);

        Assertions.assertEquals(20, allocatedStock);
    }

    private Map<String, Integer> invokeBuildEmbryoStandardCapacitySumMap(ScheduleAdjustHandler handler,
                                                                         LhScheduleContext context) throws Exception {
        Method method = ScheduleAdjustHandler.class.getDeclaredMethod(
                "buildEmbryoStandardCapacitySumMap", LhScheduleContext.class);
        method.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Integer> result = (Map<String, Integer>) method.invoke(handler, context);
        return result;
    }

    private int invokeResolveAllocatedEmbryoStock(ScheduleAdjustHandler handler,
                                                  LhScheduleContext context,
                                                  FactoryMonthPlanProductionFinalResult plan,
                                                  Map<String, Integer> embryoSumMap) throws Exception {
        Method method = ScheduleAdjustHandler.class.getDeclaredMethod(
                "resolveAllocatedEmbryoStock", LhScheduleContext.class,
                FactoryMonthPlanProductionFinalResult.class, Map.class);
        method.setAccessible(true);
        return (Integer) method.invoke(handler, context, plan, embryoSumMap);
    }

    private FactoryMonthPlanProductionFinalResult buildMonthPlan(String materialCode,
                                                                 String embryoCode,
                                                                 int dayVulcanizationQty) {
        FactoryMonthPlanProductionFinalResult plan = new FactoryMonthPlanProductionFinalResult();
        plan.setMaterialCode(materialCode);
        plan.setEmbryoCode(embryoCode);
        plan.setDayVulcanizationQty(dayVulcanizationQty);
        return plan;
    }

    private MdmSkuLhCapacity buildCapacity(int standardCapacity) {
        MdmSkuLhCapacity capacity = new MdmSkuLhCapacity();
        capacity.setStandardCapacity(standardCapacity);
        return capacity;
    }
}
