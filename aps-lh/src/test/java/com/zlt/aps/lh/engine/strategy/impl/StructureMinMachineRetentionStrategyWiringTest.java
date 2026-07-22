package com.zlt.aps.lh.engine.strategy.impl;

import com.zlt.aps.lh.api.domain.dto.SkuScheduleDTO;
import com.zlt.aps.lh.api.domain.entity.LhScheduleResult;
import com.zlt.aps.lh.api.enums.ScheduleTypeEnum;
import com.zlt.aps.lh.api.enums.SkuTagEnum;
import com.zlt.aps.lh.component.StructureMinMachineRetentionService;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.util.ShiftFieldUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
     * SKU余量收尾已由同SKU正量机台承载时，全零冗余机台必须正常释放，不能再触发结构保机。
     */
    @Test
    public void shouldReleaseEndingRedundantZeroMachineWithoutStructureRetention() {
        StructureMinMachineRetentionService retentionService = mock(StructureMinMachineRetentionService.class);
        ContinuousProductionStrategy strategy = continuousStrategy(retentionService);
        LhScheduleContext context = new LhScheduleContext();
        SkuScheduleDTO sourceSku = sourceSku();
        sourceSku.setSkuTag(SkuTagEnum.ENDING.getCode());
        sourceSku.setStrictTargetQty(true);
        LhScheduleResult zeroResult = resultWithShiftPlan("K1001", 0);
        LhScheduleResult carrierResult = resultWithShiftPlan("K1002", 10);
        context.getScheduleResultList().add(zeroResult);
        context.getScheduleResultList().add(carrierResult);

        Boolean retained = ReflectionTestUtils.invokeMethod(strategy,
                "completeContinuousMachineOfflineDecision",
                context, sourceSku, zeroResult, 1, 1, "收尾冗余机台测试");

        Assertions.assertFalse(Boolean.TRUE.equals(retained));
        Assertions.assertEquals(0,
                context.getContinuousReducedMachineReleaseBoundaryShiftIndex("K1001"));
        Assertions.assertEquals("0", zeroResult.getIsStructureMinMachineRetained());
        verify(retentionService, never()).retainMachineBeforeOffline(
                any(), any(), any(), anyInt(), anyInt(), anyString());
    }

    /**
     * 续作零结果最终收口必须真正移除已放行的收尾冗余结果，并解除机台分配占用。
     */
    @Test
    public void shouldRemoveReleasedEndingRedundantZeroResultAtFinalClosing() {
        StructureMinMachineRetentionService retentionService = mock(StructureMinMachineRetentionService.class);
        ContinuousProductionStrategy strategy = continuousStrategy(retentionService);
        LhScheduleContext context = new LhScheduleContext();
        SkuScheduleDTO sourceSku = sourceSku();
        sourceSku.setSkuTag(SkuTagEnum.ENDING.getCode());
        sourceSku.setStrictTargetQty(true);
        sourceSku.setTargetScheduleQty(10);
        LhScheduleResult zeroResult = resultWithShiftPlan("K1001", 0);
        LhScheduleResult carrierResult = resultWithShiftPlan("K1002", 10);
        context.getScheduleResultList().add(zeroResult);
        context.getScheduleResultList().add(carrierResult);
        context.getScheduleResultSourceSkuMap().put(zeroResult, sourceSku);
        context.getScheduleResultSourceSkuMap().put(carrierResult, sourceSku);
        context.getMachineAssignmentMap().put("K1001", new ArrayList<LhScheduleResult>());
        context.getMachineAssignmentMap().get("K1001").add(zeroResult);

        ReflectionTestUtils.invokeMethod(strategy, "finalizeZeroPlanContinuousResults", context);

        Assertions.assertEquals(1, context.getScheduleResultList().size());
        Assertions.assertSame(carrierResult, context.getScheduleResultList().get(0));
        Assertions.assertFalse(context.getMachineAssignmentMap().containsKey("K1001"));
        Assertions.assertTrue(context.getReleasedContinuousMachineCodeSet().contains("K1001"));
        Assertions.assertEquals(0,
                context.getContinuousReducedMachineReleaseBoundaryShiftIndex("K1001"));
        verify(retentionService, never()).retainMachineBeforeOffline(
                any(), any(), any(), anyInt(), anyInt(), anyString());
    }

    /** 收尾SKU全部结果均为零时可能是库存或账本裁剪，必须继续执行结构最低机台判断。 */
    @Test
    public void shouldRetainEndingZeroMachineWhenNoPositiveCarrierExists() {
        StructureMinMachineRetentionService retentionService = mock(StructureMinMachineRetentionService.class);
        when(retentionService.retainMachineBeforeOffline(
                any(), any(), any(), anyInt(), anyInt(), anyString())).thenReturn(true);
        ContinuousProductionStrategy strategy = continuousStrategy(retentionService);
        LhScheduleContext context = new LhScheduleContext();
        SkuScheduleDTO sourceSku = sourceSku();
        sourceSku.setSkuTag(SkuTagEnum.ENDING.getCode());
        LhScheduleResult zeroResult = resultWithShiftPlan("K1001", 0);
        context.getScheduleResultList().add(zeroResult);

        Boolean retained = ReflectionTestUtils.invokeMethod(strategy,
                "completeContinuousMachineOfflineDecision",
                context, sourceSku, zeroResult, 1, 1, "收尾全部裁零测试");

        Assertions.assertTrue(Boolean.TRUE.equals(retained));
        Assertions.assertNull(context.getContinuousReducedMachineReleaseBoundaryShiftIndex("K1001"));
        verify(retentionService).retainMachineBeforeOffline(
                any(), any(), any(), anyInt(), anyInt(), anyString());
    }

    /** 非收尾SKU即使存在同SKU正量机台，零量机台也必须继续执行结构最低机台判断。 */
    @Test
    public void shouldRetainNonEndingZeroMachineEvenWhenPositiveCarrierExists() {
        StructureMinMachineRetentionService retentionService = mock(StructureMinMachineRetentionService.class);
        when(retentionService.retainMachineBeforeOffline(
                any(), any(), any(), anyInt(), anyInt(), anyString())).thenReturn(true);
        ContinuousProductionStrategy strategy = continuousStrategy(retentionService);
        LhScheduleContext context = new LhScheduleContext();
        SkuScheduleDTO sourceSku = sourceSku();
        sourceSku.setSkuTag(SkuTagEnum.NORMAL.getCode());
        LhScheduleResult zeroResult = resultWithShiftPlan("K1001", 0);
        LhScheduleResult carrierResult = resultWithShiftPlan("K1002", 10);
        context.getScheduleResultList().add(zeroResult);
        context.getScheduleResultList().add(carrierResult);

        Boolean retained = ReflectionTestUtils.invokeMethod(strategy,
                "completeContinuousMachineOfflineDecision",
                context, sourceSku, zeroResult, 1, 1, "非收尾零机台测试");

        Assertions.assertTrue(Boolean.TRUE.equals(retained));
        Assertions.assertNull(context.getContinuousReducedMachineReleaseBoundaryShiftIndex("K1001"));
    }

    /**
     * 未命中保机的机台必须在方法返回前登记释放边界，保证下一台连续下机读取最新运行态。
     */
    @Test
    public void shouldRegisterReleaseBoundaryImmediatelyAfterOfflineDecision() {
        StructureMinMachineRetentionService retentionService = mock(StructureMinMachineRetentionService.class);
        when(retentionService.retainMachineBeforeOffline(
                any(), any(), any(), anyInt(), anyInt(), anyString())).thenReturn(false);
        ContinuousProductionStrategy strategy = continuousStrategy(retentionService);
        LhScheduleContext context = new LhScheduleContext();
        SkuScheduleDTO sourceSku = sourceSku();
        sourceSku.setSkuTag(SkuTagEnum.NORMAL.getCode());
        LhScheduleResult firstResult = resultWithShiftPlan("K1001", 0);
        LhScheduleResult secondResult = resultWithShiftPlan("K1002", 0);
        context.getScheduleResultList().add(firstResult);
        context.getScheduleResultList().add(secondResult);

        ReflectionTestUtils.invokeMethod(strategy, "completeContinuousMachineOfflineDecision",
                context, sourceSku, firstResult, 1, 1, "连续下机第一次");

        Assertions.assertEquals(0,
                context.getContinuousReducedMachineReleaseBoundaryShiftIndex("K1001"));
        Assertions.assertNull(context.getContinuousReducedMachineReleaseBoundaryShiftIndex("K1002"));
        ReflectionTestUtils.invokeMethod(strategy, "completeContinuousMachineOfflineDecision",
                context, sourceSku, secondResult, 1, 1, "连续下机第二次");
        Assertions.assertEquals(0,
                context.getContinuousReducedMachineReleaseBoundaryShiftIndex("K1002"));
    }

    /**
     * 逐日降模已经存在较晚释放边界时，本次更早下机必须重新执行保机判断并向前刷新边界。
     *
     * <p>该场景用于防止第一台机台仍保留旧边界，导致第二台连续下机继续使用相同的结构在机数。</p>
     */
    @Test
    public void shouldRefreshEarlierReleaseBoundaryBeforeNextOfflineDecision() {
        StructureMinMachineRetentionService retentionService = mock(StructureMinMachineRetentionService.class);
        when(retentionService.retainMachineBeforeOffline(
                any(), any(), any(), anyInt(), anyInt(), anyString())).thenReturn(false);
        ContinuousProductionStrategy strategy = continuousStrategy(retentionService);
        LhScheduleContext context = new LhScheduleContext();
        SkuScheduleDTO sourceSku = sourceSku();
        sourceSku.setSkuTag(SkuTagEnum.NORMAL.getCode());
        LhScheduleResult result = resultWithShiftPlan("K1001", 10);
        context.getScheduleResultList().add(result);
        context.registerContinuousReducedMachineReleaseBoundary("K1001", 7);

        // 本次降模后最新末班为第一班，旧的第七班边界必须立即向前刷新，供下一台实时统计。
        ReflectionTestUtils.invokeMethod(strategy, "completeContinuousMachineOfflineDecision",
                context, sourceSku, result, 1, 7, "逐日降模边界刷新测试");

        Assertions.assertEquals(1,
                context.getContinuousReducedMachineReleaseBoundaryShiftIndex("K1001"));
        verify(retentionService).retainMachineBeforeOffline(
                any(), any(), any(), anyInt(), anyInt(), anyString());
    }

    /**
     * 构建首班有量结果，确保局部入口能解析下机班次为第二班。
     *
     * @return 测试结果
     */
    private LhScheduleResult resultWithFirstShiftPlan() {
        return resultWithShiftPlan("K1001", 10);
    }

    /**
     * 构建指定机台和首班计划量的续作结果。
     *
     * @param machineCode 机台编码
     * @param planQty 首班计划量
     * @return 续作结果
     */
    private LhScheduleResult resultWithShiftPlan(String machineCode, int planQty) {
        LhScheduleResult result = new LhScheduleResult();
        result.setLhMachineCode(machineCode);
        result.setMaterialCode("SKU1");
        result.setProductStatus("S");
        result.setStructureName("S1");
        result.setScheduleType(ScheduleTypeEnum.CONTINUOUS.getCode());
        ShiftFieldUtil.setShiftPlanQty(result, 1, planQty, null, null);
        result.setDailyPlanQty(planQty);
        return result;
    }

    /**
     * 构建注入统一结构保机服务的续作策略。
     *
     * @param retentionService 结构最低机台保留服务
     * @return 续作策略
     */
    private ContinuousProductionStrategy continuousStrategy(
            StructureMinMachineRetentionService retentionService) {
        ContinuousProductionStrategy strategy = new ContinuousProductionStrategy();
        ReflectionTestUtils.setField(strategy, "structureMinMachineRetentionService", retentionService);
        return strategy;
    }

    /**
     * 构建测试SKU。
     *
     * @return 测试SKU
     */
    private SkuScheduleDTO sourceSku() {
        SkuScheduleDTO sku = new SkuScheduleDTO();
        sku.setMaterialCode("SKU1");
        sku.setProductStatus("S");
        sku.setStructureName("S1");
        return sku;
    }
}
