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
import com.zlt.aps.lh.engine.strategy.support.EarlyProductionRuntimePlan;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    void captureSubstitutionContinuationSnapshot_shouldExcludeNewProductionResult() {
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
                handler, "captureSubstitutionContinuationSnapshot", context);

        assertTrue(context.getSpecialMaterialContinuationResultSnapshot()
                .contains(continuationResult));
        assertFalse(context.getSpecialMaterialContinuationResultSnapshot()
                .contains(newProductionResult));
    }

    /**
     * 验证新增业务排序完成后，历史反选只登记指定机台指令，不能重排 SKU 队列。
     */
    @Test
    void handle_shouldKeepBusinessPriorityOrderAfterHistoricalReverseSelection() {
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
        // 真实历史反选策略只登记“机台+后物料”指令，不得改写排序器已经确定的队列。
        doAnswer(invocation -> null).when(historicalReverseSelectionStrategy).reverseSelect(context);
        doAnswer(invocation -> {
            assertSame(normalHighPrioritySku, context.getNewSpecSkuList().get(0),
                    "历史指定机台只能影响选机，不能覆盖 SKU 业务排序第一顺位");
            assertSame(historicalReverseSku, context.getNewSpecSkuList().get(1),
                    "历史反选目标 SKU 必须保留排序器给出的原有相对位置");
            return null;
        }).when(strategy).scheduleNewSpecs(
                any(LhScheduleContext.class), any(IMachineMatchStrategy.class),
                any(IMouldChangeBalanceStrategy.class), any(IFirstInspectionBalanceStrategy.class),
                any(ICapacityCalculateStrategy.class));

        handler.handle(context);
    }

    /**
     * 验证提前生产中心运行视图覆盖完整 S4.5 生命周期：胎胚调整和最终收尾复核执行时
     * 视图仍然有效，全部后处理结束后再统一清理。
     */
    @Test
    void handle_shouldKeepEarlyProductionRuntimeViewUntilAllPostStepsFinish() {
        when(strategyFactory.getProductionStrategy("02")).thenReturn(strategy);
        when(strategyFactory.getSkuPriorityStrategy()).thenReturn(skuPriorityStrategy);
        when(strategyFactory.getMachineMatchStrategy()).thenReturn(machineMatchStrategy);
        when(strategyFactory.getMouldChangeBalanceStrategy()).thenReturn(mouldChangeBalanceStrategy);
        when(strategyFactory.getFirstInspectionBalanceStrategy()).thenReturn(firstInspectionBalanceStrategy);
        when(strategyFactory.getCapacityCalculateStrategy()).thenReturn(capacityCalculateStrategy);

        LhScheduleContext context = new LhScheduleContext();
        SkuScheduleDTO sku = new SkuScheduleDTO();
        sku.setMaterialCode("3302002746");
        sku.setProductStatus("S");
        EarlyProductionRuntimePlan runtimePlan = new EarlyProductionRuntimePlan();
        runtimePlan.setFutureOnlyCandidate(true);
        runtimePlan.setActive(true);
        runtimePlan.setEffectiveTargetQty(102);
        context.registerEarlyProductionRuntimePlan(sku, runtimePlan);

        doAnswer(invocation -> {
            assertNotNull(context.getEarlyProductionRuntimePlan(sku),
                    "班次分配执行时中心运行视图不得提前清理");
            return null;
        }).when(strategy).allocateShiftPlanQty(context);
        doAnswer(invocation -> {
            assertNotNull(context.getEarlyProductionRuntimePlan(sku),
                    "胎胚调整和最终isEnd复核执行时中心运行视图不得提前清理");
            return null;
        }).when(strategy).adjustEmbryoStock(context);
        doAnswer(invocation -> {
            assertNotNull(context.getEarlyProductionRuntimePlan(sku),
                    "降模后处理执行时中心运行视图不得提前清理");
            return null;
        }).when(strategy).scheduleReduceMould(context);

        handler.handle(context);

        assertNull(context.getEarlyProductionRuntimePlan(sku),
                "完整S4.5结束后必须清理提前生产临时运行视图");
    }
}
