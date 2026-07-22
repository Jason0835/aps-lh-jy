package com.zlt.aps.lh.engine.strategy.impl;

import com.zlt.aps.lh.api.domain.dto.SkuScheduleDTO;
import com.zlt.aps.lh.api.domain.entity.LhScheduleResult;
import com.zlt.aps.lh.component.StructureMinMachineRetentionService;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.util.ShiftFieldUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 续作、新增真实下机局部入口接线测试。
 *
 * <p>具体数量、停机和状态语义由 {@code StructureMinMachineRetentionServiceTest} 覆盖；本类专门保证
 * 两条策略主链使用的局部下机封装均委托统一服务，避免后续重构时遗漏某一排程阶段。</p>
 *
 * @author APS
 */
public class StructureMinMachineRetentionStrategyWiringTest {

    /** 续作降模局部入口必须委托结构最低机台统一服务。 */
    @Test
    public void shouldDelegateContinuousOfflineDecisionToRetentionService() {
        StructureMinMachineRetentionService retentionService = mock(StructureMinMachineRetentionService.class);
        when(retentionService.retainMachineBeforeOffline(
                any(), any(), any(), anyInt(), anyInt(), anyString())).thenReturn(true);
        ContinuousProductionStrategy strategy = new ContinuousProductionStrategy();
        ReflectionTestUtils.setField(strategy, "structureMinMachineRetentionService", retentionService);
        LhScheduleResult result = resultWithFirstShiftPlan();

        Boolean retained = ReflectionTestUtils.invokeMethod(strategy,
                "retainStructureMachineBeforeOfflineIfNecessary",
                new LhScheduleContext(), sourceSku(), result, 1, 1, "续作测试下机");

        Assertions.assertTrue(Boolean.TRUE.equals(retained));
        verify(retentionService).retainMachineBeforeOffline(
                any(), any(), any(), anyInt(), anyInt(), anyString());
    }

    /** 新增辅助机台释放局部入口必须委托结构最低机台统一服务。 */
    @Test
    public void shouldDelegateNewSpecOfflineDecisionToRetentionService() {
        StructureMinMachineRetentionService retentionService = mock(StructureMinMachineRetentionService.class);
        when(retentionService.retainMachineBeforeOffline(
                any(), any(), any(), anyInt(), anyInt(), anyString())).thenReturn(true);
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        ReflectionTestUtils.setField(strategy, "structureMinMachineRetentionService", retentionService);
        LhScheduleResult result = resultWithFirstShiftPlan();

        Boolean retained = ReflectionTestUtils.invokeMethod(strategy,
                "retainStructureMachineBeforeOfflineIfNecessary",
                new LhScheduleContext(), sourceSku(), result, 1, 1, "新增测试下机");

        Assertions.assertTrue(Boolean.TRUE.equals(retained));
        verify(retentionService).retainMachineBeforeOffline(
                any(), any(), any(), anyInt(), anyInt(), anyString());
    }

    /**
     * 构建首班有量结果，确保局部入口能解析下机班次为第二班。
     *
     * @return 测试结果
     */
    private LhScheduleResult resultWithFirstShiftPlan() {
        LhScheduleResult result = new LhScheduleResult();
        result.setLhMachineCode("K1001");
        result.setMaterialCode("SKU1");
        result.setStructureName("S1");
        ShiftFieldUtil.setShiftPlanQty(result, 1, 10, null, null);
        result.setDailyPlanQty(10);
        return result;
    }

    /**
     * 构建测试SKU。
     *
     * @return 测试SKU
     */
    private SkuScheduleDTO sourceSku() {
        SkuScheduleDTO sku = new SkuScheduleDTO();
        sku.setMaterialCode("SKU1");
        sku.setStructureName("S1");
        return sku;
    }
}
