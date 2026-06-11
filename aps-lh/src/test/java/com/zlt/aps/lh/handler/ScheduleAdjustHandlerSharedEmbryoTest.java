package com.zlt.aps.lh.handler;

import com.zlt.aps.lh.api.domain.dto.SkuScheduleDTO;
import com.zlt.aps.lh.api.domain.entity.LhUnscheduledResult;
import com.zlt.aps.lh.component.TargetScheduleQtyResolver;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.engine.strategy.IEndingJudgmentStrategy;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * S4.3共用胎胚零余量收尾预剔除测试。
 *
 * @author APS
 */
public class ScheduleAdjustHandlerSharedEmbryoTest {

    /**
     * 用例说明：共用胎胚收尾SKU余量为0、胎胚库存大于0时，进入排产前即写未排并剔除。
     *
     * @throws Exception 反射调用异常
     */
    @Test
    public void shouldPruneSharedEmbryoZeroSurplusEndingBeforeClassify() throws Exception {
        ScheduleAdjustHandler handler = new ScheduleAdjustHandler();
        ReflectionTestUtils.setField(handler, "targetScheduleQtyResolver", new TargetScheduleQtyResolver());
        ReflectionTestUtils.setField(handler, "endingJudgmentStrategy", buildEndingJudgmentStrategy());

        LhScheduleContext context = new LhScheduleContext();
        SkuScheduleDTO zeroSurplusSku = buildSku("3302002369", "215103935", 0, 2, 2);
        SkuScheduleDTO anotherSku = buildSku("3302002279", "215103935", 124, 2, 124);
        Map<String, List<SkuScheduleDTO>> structureSkuMap = new LinkedHashMap<String, List<SkuScheduleDTO>>(4);
        structureSkuMap.put("STRUCT-01", new ArrayList<SkuScheduleDTO>(Arrays.asList(zeroSurplusSku, anotherSku)));
        context.setStructureSkuMap(structureSkuMap);
        context.getActiveEmbryoSkuMap().put("215103935",
                new ArrayList<String>(Arrays.asList("3302002369", "3302002279")));

        invokePruneSharedEmbryoZeroSurplusEndingSkus(handler, context);

        Assertions.assertEquals(1, context.getUnscheduledResultList().size());
        LhUnscheduledResult unscheduled = context.getUnscheduledResultList().get(0);
        Assertions.assertEquals("3302002369", unscheduled.getMaterialCode());
        Assertions.assertEquals(Integer.valueOf(0), unscheduled.getUnscheduledQty());
        Assertions.assertTrue(unscheduled.getUnscheduledReason().contains("共用胎胚收尾仅按硫化余量"));
        Assertions.assertEquals(1, context.getStructureSkuMap().get("STRUCT-01").size());
        Assertions.assertEquals("3302002279", context.getStructureSkuMap().get("STRUCT-01").get(0).getMaterialCode());
        Assertions.assertEquals(Arrays.asList("3302002279"), context.getActiveEmbryoSkuMap().get("215103935"));
    }

    private void invokePruneSharedEmbryoZeroSurplusEndingSkus(ScheduleAdjustHandler handler,
                                                              LhScheduleContext context) throws Exception {
        Method method = ScheduleAdjustHandler.class.getDeclaredMethod(
                "pruneSharedEmbryoZeroSurplusEndingSkus", LhScheduleContext.class);
        method.setAccessible(true);
        method.invoke(handler, context);
    }

    private IEndingJudgmentStrategy buildEndingJudgmentStrategy() {
        return new IEndingJudgmentStrategy() {
            @Override
            public boolean isEnding(LhScheduleContext context, SkuScheduleDTO sku) {
                return true;
            }

            @Override
            public int calculateEndingShifts(LhScheduleContext context, SkuScheduleDTO sku) {
                return 1;
            }

            @Override
            public int calculateEndingDays(LhScheduleContext context, SkuScheduleDTO sku) {
                return 1;
            }
        };
    }

    private SkuScheduleDTO buildSku(String materialCode,
                                    String embryoCode,
                                    int surplusQty,
                                    int embryoStock,
                                    int targetScheduleQty) {
        SkuScheduleDTO sku = new SkuScheduleDTO();
        sku.setMaterialCode(materialCode);
        sku.setEmbryoCode(embryoCode);
        sku.setSurplusQty(surplusQty);
        sku.setEmbryoStock(embryoStock);
        sku.setTargetScheduleQty(targetScheduleQty);
        sku.setRemainingScheduleQty(targetScheduleQty);
        return sku;
    }
}
