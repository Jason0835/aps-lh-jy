package com.zlt.aps.lh.engine.strategy.impl;

import com.zlt.aps.lh.component.StructureMinMachineRetentionService;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.engine.factory.ScheduleStrategyFactory;
import com.zlt.aps.lh.engine.strategy.IMachineMatchStrategy;
import com.zlt.aps.lh.engine.strategy.IProductionStrategy;
import com.zlt.aps.lh.engine.strategy.ISkuPriorityStrategy;
import com.zlt.aps.lh.engine.strategy.ITypeBlockProductionStrategy;
import com.zlt.aps.lh.handler.ContinuousProductionHandler;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.ReflectionUtils;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 结构停产保机阶段调用接线测试。
 *
 * <p>本类只验证调用时机和旧实时入口删除；结构数量、停机及占位语义由
 * {@code StructureMinMachineRetentionServiceTest} 覆盖。</p>
 *
 * @author APS
 */
public class StructureMinMachineRetentionStrategyWiringTest {

    /** 阶段判断必须在换活字块完成后、S4.5新增排产开始前执行。 */
    @Test
    public void shouldApplyPhaseRetentionAfterTypeBlock() {
        ContinuousProductionHandler handler = new ContinuousProductionHandler();
        ScheduleStrategyFactory strategyFactory =
                mock(ScheduleStrategyFactory.class);
        ISkuPriorityStrategy priorityStrategy =
                mock(ISkuPriorityStrategy.class);
        IProductionStrategy productionStrategy =
                mock(IProductionStrategy.class);
        ITypeBlockProductionStrategy typeBlockStrategy =
                mock(ITypeBlockProductionStrategy.class);
        IMachineMatchStrategy machineMatchStrategy =
                mock(IMachineMatchStrategy.class);
        StructureMinMachineRetentionService retentionService =
                mock(StructureMinMachineRetentionService.class);
        when(strategyFactory.getSkuPriorityStrategy())
                .thenReturn(priorityStrategy);
        when(strategyFactory.getProductionStrategy(any()))
                .thenReturn(productionStrategy);
        when(strategyFactory.getMachineMatchStrategy())
                .thenReturn(machineMatchStrategy);
        ReflectionTestUtils.setField(
                handler, "strategyFactory", strategyFactory);
        ReflectionTestUtils.setField(
                handler, "typeBlockProductionStrategy", typeBlockStrategy);
        ReflectionTestUtils.setField(
                handler, "structureMinMachineRetentionService",
                retentionService);
        LhScheduleContext context = new LhScheduleContext();

        ReflectionTestUtils.invokeMethod(handler, "doHandle", context);

        InOrder inOrder = inOrder(
                productionStrategy, typeBlockStrategy,
                retentionService, machineMatchStrategy);
        inOrder.verify(productionStrategy).scheduleReduceMould(context);
        inOrder.verify(typeBlockStrategy).scheduleTypeBlockChange(context);
        inOrder.verify(retentionService)
                .applyRetentionAfterContinuousAndTypeBlock(context);
        inOrder.verify(machineMatchStrategy)
                .traceEnabledMachineSort(context);
    }

    /** 续作和新增策略不得再保留逐台下机实时结构保机入口。 */
    @Test
    public void shouldRemoveRealtimeOfflineRetentionEntryPoints() {
        Assertions.assertNull(ReflectionUtils.findMethod(
                ContinuousProductionStrategy.class,
                "retainStructureMachineBeforeOfflineIfNecessary"));
        Assertions.assertNull(ReflectionUtils.findMethod(
                NewSpecProductionStrategy.class,
                "retainStructureMachineBeforeOfflineIfNecessary"));
        Assertions.assertNull(ReflectionUtils.findMethod(
                StructureMinMachineRetentionService.class,
                "retainMachineBeforeOffline"));
    }

}
