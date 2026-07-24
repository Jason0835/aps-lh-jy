package com.zlt.aps.lh.handler;

import com.zlt.aps.lh.api.domain.dto.MachineScheduleDTO;
import com.zlt.aps.lh.api.domain.dto.SkuScheduleDTO;
import com.zlt.aps.lh.api.domain.entity.LhScheduleResult;
import com.zlt.aps.lh.api.enums.ScheduleTypeEnum;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.engine.factory.ScheduleStrategyFactory;
import com.zlt.aps.lh.engine.strategy.ICapacityCalculateStrategy;
import com.zlt.aps.lh.engine.strategy.IFirstInspectionBalanceStrategy;
import com.zlt.aps.lh.engine.strategy.IHistoricalMouldChangeReverseSelectionStrategy;
import com.zlt.aps.lh.engine.strategy.IMachineMatchStrategy;
import com.zlt.aps.lh.engine.strategy.IMouldChangeBalanceStrategy;
import com.zlt.aps.lh.engine.strategy.IProductionStrategy;
import com.zlt.aps.lh.engine.strategy.ISkuPriorityStrategy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.when;

/**
 * S4.5 新增排产步骤顺序回归测试。
 */
@ExtendWith(MockitoExtension.class)
class NewProductionHandlerTest {

    @Mock
    private ScheduleStrategyFactory strategyFactory;

    @Mock
    private IProductionStrategy strategy;

    @Mock
    private ISkuPriorityStrategy skuPriorityStrategy;

    @Mock
    private IHistoricalMouldChangeReverseSelectionStrategy historicalReverseSelectionStrategy;

    @Mock
    private IMachineMatchStrategy machineMatchStrategy;

    @Mock
    private IMouldChangeBalanceStrategy mouldChangeBalanceStrategy;

    @Mock
    private IFirstInspectionBalanceStrategy firstInspectionBalanceStrategy;

    @Mock
    private ICapacityCalculateStrategy capacityCalculateStrategy;

    @InjectMocks
    private NewProductionHandler handler;

    @Test
    void handle_shouldRunNewSpecPostStepsAfterScheduleNewSpecs() {
        when(strategyFactory.getProductionStrategy("02")).thenReturn(strategy);
        when(strategyFactory.getSkuPriorityStrategy()).thenReturn(skuPriorityStrategy);
        when(strategyFactory.getMachineMatchStrategy()).thenReturn(machineMatchStrategy);
        when(strategyFactory.getMouldChangeBalanceStrategy()).thenReturn(mouldChangeBalanceStrategy);
        when(strategyFactory.getFirstInspectionBalanceStrategy()).thenReturn(firstInspectionBalanceStrategy);
        when(strategyFactory.getCapacityCalculateStrategy()).thenReturn(capacityCalculateStrategy);

        handler.handle(new LhScheduleContext());

        InOrder inOrder = inOrder(
                skuPriorityStrategy, historicalReverseSelectionStrategy, strategy);
        inOrder.verify(skuPriorityStrategy).sortByPriority(any(LhScheduleContext.class));
        inOrder.verify(historicalReverseSelectionStrategy).reverseSelect(any(LhScheduleContext.class));
        inOrder.verify(strategy).scheduleNewSpecs(any(LhScheduleContext.class),
                any(IMachineMatchStrategy.class),
                any(IMouldChangeBalanceStrategy.class),
                any(IFirstInspectionBalanceStrategy.class),
                any(ICapacityCalculateStrategy.class));
        inOrder.verify(strategy).allocateShiftPlanQty(any(LhScheduleContext.class));
        inOrder.verify(strategy).adjustEmbryoStock(any(LhScheduleContext.class));
        inOrder.verify(strategy).scheduleReduceMould(any(LhScheduleContext.class));
    }

    /**
     * 验证特殊材料置换快照只冻结排程开始时真实在机的续作结果。
     */
    @Test
    void captureSpecialMaterialContinuationSnapshot_shouldExcludeNewProductionResult() {
        LhScheduleContext context = new LhScheduleContext();
        MachineScheduleDTO initialMachine = new MachineScheduleDTO();
        initialMachine.setMachineCode("K1201");
        initialMachine.setCurrentMaterialCode("CONT-SKU");
        context.getInitialMachineScheduleMap().put("K1201", initialMachine);

        LhScheduleResult continuationResult = new LhScheduleResult();
        continuationResult.setLhMachineCode("K1201");
        continuationResult.setMaterialCode("CONT-SKU");
        continuationResult.setScheduleType(ScheduleTypeEnum.CONTINUOUS.getCode());
        LhScheduleResult newProductionResult = new LhScheduleResult();
        newProductionResult.setLhMachineCode("K1201");
        newProductionResult.setMaterialCode("NEW-SKU");
        newProductionResult.setScheduleType(ScheduleTypeEnum.NEW_SPEC.getCode());
        context.getScheduleResultList().add(continuationResult);
        context.getScheduleResultList().add(newProductionResult);

        // 直接调用冻结入口，验证对象身份集合不会把随后生成的新增结果纳入置换范围。
        ReflectionTestUtils.invokeMethod(
                handler, "captureSpecialMaterialContinuationSnapshot", context);

        assertTrue(context.getSpecialMaterialContinuationResultSnapshot()
                .contains(continuationResult));
        assertFalse(context.getSpecialMaterialContinuationResultSnapshot()
                .contains(newProductionResult));
    }

    /**
     * 验证新增业务排序完成后，历史反选仍能把目标SKU恢复到普通新增SKU之前。
     */
    @Test
    void handle_shouldKeepHistoricalReverseSkuAheadAfterBusinessPrioritySort() {
        when(strategyFactory.getProductionStrategy("02")).thenReturn(strategy);
        when(strategyFactory.getSkuPriorityStrategy()).thenReturn(skuPriorityStrategy);
        when(strategyFactory.getMachineMatchStrategy()).thenReturn(machineMatchStrategy);
        when(strategyFactory.getMouldChangeBalanceStrategy()).thenReturn(mouldChangeBalanceStrategy);
        when(strategyFactory.getFirstInspectionBalanceStrategy()).thenReturn(firstInspectionBalanceStrategy);
        when(strategyFactory.getCapacityCalculateStrategy()).thenReturn(capacityCalculateStrategy);

        LhScheduleContext context = new LhScheduleContext();
        SkuScheduleDTO normalHighPrioritySku = new SkuScheduleDTO();
        normalHighPrioritySku.setMaterialCode("3302001589");
        SkuScheduleDTO historicalReverseSku = new SkuScheduleDTO();
        historicalReverseSku.setMaterialCode("3302001274");
        context.getNewSpecSkuList().addAll(
                Arrays.asList(historicalReverseSku, normalHighPrioritySku));

        doAnswer(invocation -> {
            context.getNewSpecSkuList().clear();
            context.getNewSpecSkuList().addAll(
                    Arrays.asList(normalHighPrioritySku, historicalReverseSku));
            return null;
        }).when(skuPriorityStrategy).sortByPriority(context);
        doAnswer(invocation -> {
            context.getNewSpecSkuList().clear();
            context.getNewSpecSkuList().addAll(
                    Arrays.asList(historicalReverseSku, normalHighPrioritySku));
            return null;
        }).when(historicalReverseSelectionStrategy).reverseSelect(context);
        doAnswer(invocation -> {
            assertSame(historicalReverseSku, context.getNewSpecSkuList().get(0),
                    "历史反选SKU必须在普通新增选机前保持第一顺位");
            assertSame(normalHighPrioritySku, context.getNewSpecSkuList().get(1),
                    "普通高优先级SKU不得再次覆盖历史反选执行顺序");
            return null;
        }).when(strategy).scheduleNewSpecs(
                any(LhScheduleContext.class), any(IMachineMatchStrategy.class),
                any(IMouldChangeBalanceStrategy.class), any(IFirstInspectionBalanceStrategy.class),
                any(ICapacityCalculateStrategy.class));

        handler.handle(context);
    }
}
