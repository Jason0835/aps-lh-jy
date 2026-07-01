package com.zlt.aps.lh.handler;

import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.engine.factory.ScheduleStrategyFactory;
import com.zlt.aps.lh.engine.strategy.IMachineMatchStrategy;
import com.zlt.aps.lh.engine.strategy.IProductionStrategy;
import com.zlt.aps.lh.engine.strategy.ISkuPriorityStrategy;
import com.zlt.aps.lh.engine.strategy.ITypeBlockProductionStrategy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.when;

/**
 * S4.4 续作排产步骤顺序回归测试。
 */
@ExtendWith(MockitoExtension.class)
class ContinuousProductionHandlerTest {

    @Mock
    private ScheduleStrategyFactory strategyFactory;

    @Mock
    private IProductionStrategy strategy;

    @Mock
    private ISkuPriorityStrategy skuPriorityStrategy;
    @Mock
    private ITypeBlockProductionStrategy typeBlockProductionStrategy;
    @Mock
    private IMachineMatchStrategy machineMatchStrategy;

    @InjectMocks
    private ContinuousProductionHandler handler;

    @Test
    void handle_shouldSortBeforeContinuousEnding() {
        when(strategyFactory.getSkuPriorityStrategy()).thenReturn(skuPriorityStrategy);
        when(strategyFactory.getProductionStrategy("01")).thenReturn(strategy);
        when(strategyFactory.getMachineMatchStrategy()).thenReturn(machineMatchStrategy);

        handler.handle(new LhScheduleContext());

        InOrder inOrder = inOrder(skuPriorityStrategy, strategy, typeBlockProductionStrategy);
        inOrder.verify(skuPriorityStrategy).sortByPriority(any(LhScheduleContext.class));
        inOrder.verify(strategy).scheduleContinuousEnding(any(LhScheduleContext.class));
        inOrder.verify(strategy).allocateShiftPlanQty(any(LhScheduleContext.class));
        inOrder.verify(strategy).adjustEmbryoStock(any(LhScheduleContext.class));
        inOrder.verify(strategy).scheduleReduceMould(any(LhScheduleContext.class));
        inOrder.verify(typeBlockProductionStrategy).scheduleTypeBlockChange(any(LhScheduleContext.class));
    }
}
