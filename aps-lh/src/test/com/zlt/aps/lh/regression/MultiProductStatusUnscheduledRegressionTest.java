package com.zlt.aps.lh.regression;

import com.zlt.aps.lh.api.domain.entity.LhUnscheduledResult;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.engine.strategy.impl.ContinuousProductionStrategy;
import com.zlt.aps.lh.engine.strategy.impl.NewSpecProductionStrategy;
import com.zlt.aps.lh.service.impl.SpecialMaterialMachineSubstitutionService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 同物料多产品状态未排归并回归测试。
 */
class MultiProductStatusUnscheduledRegressionTest {

    /**
     * 新增未排收口只允许合并物料与产品状态同时一致的记录。
     */
    @Test
    void newSpec_shouldMergeUnscheduledByMaterialAndProductStatus() {
        LhScheduleContext context = buildContext();

        ReflectionTestUtils.invokeMethod(
                new NewSpecProductionStrategy(), "normalizeUnscheduledResultsBySku", context);

        assertMergedByProductStatus(context.getUnscheduledResultList());
    }

    /**
     * 续作未排收口只允许合并物料与产品状态同时一致的记录。
     */
    @Test
    void continuous_shouldMergeUnscheduledByMaterialAndProductStatus() {
        LhScheduleContext context = buildContext();

        ReflectionTestUtils.invokeMethod(
                new ContinuousProductionStrategy(), "normalizeUnscheduledResultsBySku", context);

        assertMergedByProductStatus(context.getUnscheduledResultList());
    }

    /**
     * 特殊物料置换成功后只清理已排状态的全部未排记录，其他状态必须保留。
     */
    @Test
    void substitution_shouldRemoveAllUnscheduledRecordsForExactProductStatus() {
        LhScheduleContext context = buildContext();
        LhUnscheduledResult scheduledSku = buildUnscheduled("S", 1, "置换前未排");

        ReflectionTestUtils.invokeMethod(new SpecialMaterialMachineSubstitutionService(),
                "removeUnscheduledResult", context, scheduledSku);

        Assertions.assertEquals(1, context.getUnscheduledResultList().size());
        Assertions.assertEquals("T", context.getUnscheduledResultList().get(0).getProductStatus());
        Assertions.assertEquals(5, context.getUnscheduledResultList().get(0).getUnscheduledQty().intValue());
    }

    /**
     * 构建同物料多状态未排样本。
     *
     * @return 排程上下文
     */
    private LhScheduleContext buildContext() {
        LhScheduleContext context = new LhScheduleContext();
        context.setUnscheduledResultList(new ArrayList<LhUnscheduledResult>(Arrays.asList(
                buildUnscheduled("S", 3, "S原因一"),
                buildUnscheduled("T", 5, "T原因"),
                buildUnscheduled("S", 4, "S原因二"))));
        return context;
    }

    /**
     * 构建未排记录。
     *
     * @param productStatus 产品状态
     * @param qty 未排量
     * @param reason 未排原因
     * @return 未排记录
     */
    private LhUnscheduledResult buildUnscheduled(String productStatus, int qty, String reason) {
        LhUnscheduledResult result = new LhUnscheduledResult();
        result.setMaterialCode("3302001404");
        result.setProductStatus(productStatus);
        result.setUnscheduledQty(qty);
        result.setUnscheduledReason(reason);
        return result;
    }

    /**
     * 校验S合并、T独立。
     *
     * @param results 归并后未排结果
     */
    private void assertMergedByProductStatus(List<LhUnscheduledResult> results) {
        Assertions.assertEquals(2, results.size());
        LhUnscheduledResult formal = results.stream()
                .filter(result -> "S".equals(result.getProductStatus()))
                .findFirst().orElse(null);
        LhUnscheduledResult trial = results.stream()
                .filter(result -> "T".equals(result.getProductStatus()))
                .findFirst().orElse(null);
        Assertions.assertNotNull(formal);
        Assertions.assertNotNull(trial);
        Assertions.assertEquals(7, formal.getUnscheduledQty().intValue());
        Assertions.assertEquals(5, trial.getUnscheduledQty().intValue());
    }
}
