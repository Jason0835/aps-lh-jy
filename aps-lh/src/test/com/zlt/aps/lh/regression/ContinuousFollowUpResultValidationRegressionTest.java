package com.zlt.aps.lh.regression;

import com.zlt.aps.lh.api.domain.dto.MachineScheduleDTO;
import com.zlt.aps.lh.api.domain.dto.SkuScheduleDTO;
import com.zlt.aps.lh.api.domain.entity.LhScheduleResult;
import com.zlt.aps.lh.component.OrderNoGenerator;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.engine.observer.ScheduleEventPublisher;
import com.zlt.aps.lh.engine.factory.ScheduleStrategyFactory;
import com.zlt.aps.lh.engine.strategy.IEndingJudgmentStrategy;
import com.zlt.aps.lh.engine.strategy.ISkuPriorityStrategy;
import com.zlt.aps.lh.engine.strategy.impl.ContinuousProductionStrategy;
import com.zlt.aps.lh.engine.strategy.impl.TypeBlockProductionStrategy;
import com.zlt.aps.lh.handler.ContinuousProductionHandler;
import com.zlt.aps.lh.handler.ResultValidationHandler;
import com.zlt.aps.lh.service.impl.SchedulePersistenceService;
import com.zlt.aps.lh.util.LhScheduleTimeUtil;
import com.zlt.aps.mdm.api.domain.entity.MdmSkuMouldRel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 端到端回归：续作衔接非收尾结果进入 S4.6 时不应再触发 specEndTime 缺失。
 */
@ExtendWith(MockitoExtension.class)
class ContinuousFollowUpResultValidationRegressionTest {

    @Mock
    private OrderNoGenerator orderNoGenerator;

    @Mock
    private IEndingJudgmentStrategy endingJudgmentStrategy;

    @InjectMocks
    private ContinuousProductionStrategy continuousProductionStrategy;

    @InjectMocks
    private TypeBlockProductionStrategy typeBlockProductionStrategy;

    @Mock
    private ScheduleStrategyFactory strategyFactory;

    @Mock
    private ISkuPriorityStrategy skuPriorityStrategy;

    @InjectMocks
    private ContinuousProductionHandler continuousProductionHandler;

    @Mock
    private ScheduleEventPublisher scheduleEventPublisher;

    @Mock
    private SchedulePersistenceService schedulePersistenceService;

    @InjectMocks
    private ResultValidationHandler resultValidationHandler;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(continuousProductionHandler,
                "typeBlockProductionStrategy", typeBlockProductionStrategy);
    }

    @Test
    void handle_shouldPassWhenFollowUpResultIsNonEnding() {
        LhScheduleContext context = newContext();
        context.getMachineScheduleMap().put("M1", buildMachine("M1", "MAT-C1"));
        context.getContinuousSkuList().add(buildContinuousSku("MAT-C1", "M1", "EMB-1", "STRUCT-A", "SPEC-A", "PAT-A", 1));
        context.getNewSpecSkuList().add(buildNewSku("MAT-S1", "EMB-1", "STRUCT-A", "SPEC-A", "PAT-A", 1));
        putMouldRel(context, "MAT-C1", "MOULD-1");
        putMouldRel(context, "MAT-S1", "MOULD-1");

        when(orderNoGenerator.generateOrderNo(any())).thenReturn("ORD-1", "ORD-2");
        when(endingJudgmentStrategy.isEnding(any(), any())).thenAnswer(invocation -> {
            SkuScheduleDTO sku = invocation.getArgument(1);
            return "MAT-C1".equals(sku.getMaterialCode());
        });
        when(strategyFactory.getSkuPriorityStrategy()).thenReturn(skuPriorityStrategy);
        when(strategyFactory.getProductionStrategy("01")).thenReturn(continuousProductionStrategy);

        // 走真实 S4.4 全链路，覆盖收尾、衔接、班次重分配、胎胚库存调整与降模步骤。
        continuousProductionHandler.handle(context);

        LhScheduleResult followUpResult = context.getScheduleResultList().stream()
                .filter(result -> "MAT-S1".equals(result.getMaterialCode()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("缺少续作衔接结果"));
        assertEquals("0", followUpResult.getIsEnd());
        assertNotNull(followUpResult.getSpecEndTime());
        assertNotNull(followUpResult.getTdaySpecEndTime());
        Date latestSpecEndTime = context.getScheduleResultList().stream()
                .filter(result -> "01".equals(result.getScheduleType()) || "1".equals(result.getIsTypeBlock()))
                .filter(result -> result.getDailyPlanQty() != null && result.getDailyPlanQty() > 0)
                .map(LhScheduleResult::getSpecEndTime)
                .filter(endTime -> endTime != null)
                .max(Date::compareTo)
                .orElse(null);
        assertEquals(latestSpecEndTime, context.getMachineScheduleMap().get("M1").getEstimatedEndTime());

        assertDoesNotThrow(() -> resultValidationHandler.handle(context));
        verify(schedulePersistenceService).replaceScheduleAtomically(context);
        verify(scheduleEventPublisher).publish(any());
    }

    @Test
    void handle_shouldRemoveZeroPlanResultAndSkipMouldChangePlan() {
        LhScheduleContext context = newContext();
        context.getMachineScheduleMap().put("M1", buildMachine("M1", "MAT-C1"));
        SkuScheduleDTO continuousSku = buildContinuousSku("MAT-C1", "M1", "EMB-1", "STRUCT-A", "SPEC-A", "PAT-A", 1);
        continuousSku.setEmbryoStock(0);
        context.getContinuousSkuList().add(continuousSku);
        SkuScheduleDTO typeBlockSku = buildNewSku("MAT-T1", "EMB-1", "STRUCT-B", "SPEC-A", "PAT-B", 1);
        context.getNewSpecSkuList().add(typeBlockSku);
        context.getStructureSkuMap().put("STRUCT-TEST", Arrays.asList(continuousSku, typeBlockSku));
        putMouldRel(context, "MAT-C1", "MOULD-1");
        putMouldRel(context, "MAT-T1", "MOULD-1");

        when(orderNoGenerator.generateOrderNo(any())).thenReturn("ORD-1", "ORD-2");
        when(endingJudgmentStrategy.isEnding(any(), any())).thenAnswer(invocation -> {
            SkuScheduleDTO sku = invocation.getArgument(1);
            return "MAT-C1".equals(sku.getMaterialCode());
        });
        when(strategyFactory.getSkuPriorityStrategy()).thenReturn(skuPriorityStrategy);
        when(strategyFactory.getProductionStrategy("01")).thenReturn(continuousProductionStrategy);

        continuousProductionHandler.handle(context);

        assertEquals(0, context.getScheduleResultList().size(), "零计划续作结果应从排程结果列表移除");
        long unscheduledTypeBlockCount = context.getUnscheduledResultList().stream()
                .filter(unscheduled -> "MAT-T1".equals(unscheduled.getMaterialCode()))
                .count();
        assertEquals(1, unscheduledTypeBlockCount);
        Integer typeBlockUnscheduledQty = context.getUnscheduledResultList().stream()
                .filter(unscheduled -> "MAT-T1".equals(unscheduled.getMaterialCode()))
                .map(unscheduled -> unscheduled.getUnscheduledQty())
                .findFirst()
                .orElse(-1);
        assertEquals(1, typeBlockUnscheduledQty);

        assertEquals(0, context.getMouldChangePlanList().size());
        assertDoesNotThrow(() -> resultValidationHandler.handle(context));
    }

    @Test
    void handle_shouldMergeZeroPlanUnscheduledByMaterialCode() {
        LhScheduleContext context = newContext();
        context.getMachineScheduleMap().put("M1", buildMachine("M1", "MAT-C1"));
        context.getMachineScheduleMap().put("M2", buildMachine("M2", "MAT-C1"));
        SkuScheduleDTO continuousSku1 = buildContinuousSku("MAT-C1", "M1", "EMB-1", "STRUCT-A", "SPEC-A", "PAT-A", 2);
        continuousSku1.setEmbryoStock(0);
        SkuScheduleDTO continuousSku2 = buildContinuousSku("MAT-C1", "M2", "EMB-1", "STRUCT-A", "SPEC-A", "PAT-A", 2);
        continuousSku2.setEmbryoStock(0);
        context.getContinuousSkuList().add(continuousSku1);
        context.getContinuousSkuList().add(continuousSku2);
        putMouldRel(context, "MAT-C1", "MOULD-1");

        when(orderNoGenerator.generateOrderNo(any())).thenReturn("ORD-1", "ORD-2");
        when(endingJudgmentStrategy.isEnding(any(), any())).thenReturn(true);
        when(strategyFactory.getSkuPriorityStrategy()).thenReturn(skuPriorityStrategy);
        when(strategyFactory.getProductionStrategy("01")).thenReturn(continuousProductionStrategy);

        continuousProductionHandler.handle(context);

        assertEquals(0, context.getScheduleResultList().size(), "同物料零计划续作结果应全部移除");

        long unscheduledCount = context.getUnscheduledResultList().stream()
                .filter(unscheduled -> "MAT-C1".equals(unscheduled.getMaterialCode()))
                .count();
        assertEquals(1, unscheduledCount);
        Integer unscheduledQty = context.getUnscheduledResultList().stream()
                .filter(unscheduled -> "MAT-C1".equals(unscheduled.getMaterialCode()))
                .map(unscheduled -> unscheduled.getUnscheduledQty())
                .findFirst()
                .orElse(-1);
        assertEquals(2, unscheduledQty);
    }

    @Test
    void handle_shouldNotWriteUnscheduledWhenOtherMachineRetainsEffectiveQty() {
        LhScheduleContext context = newContext();
        context.getMachineScheduleMap().put("M1", buildMachine("M1", "MAT-C1"));
        context.getMachineScheduleMap().put("M2", buildMachine("M2", "MAT-C1"));
        context.getContinuousSkuList().add(buildContinuousSku("MAT-C1", "M1", "EMB-1", "STRUCT-A", "SPEC-A", "PAT-A", 1));
        context.getContinuousSkuList().add(buildContinuousSku("MAT-C1", "M2", "EMB-1", "STRUCT-A", "SPEC-A", "PAT-A", 1));
        putMouldRel(context, "MAT-C1", "MOULD-1");

        when(orderNoGenerator.generateOrderNo(any())).thenReturn("ORD-1", "ORD-2");
        when(endingJudgmentStrategy.isEnding(any(), any())).thenReturn(true);
        when(strategyFactory.getSkuPriorityStrategy()).thenReturn(skuPriorityStrategy);
        when(strategyFactory.getProductionStrategy("01")).thenReturn(continuousProductionStrategy);

        continuousProductionHandler.handle(context);

        assertEquals(1, context.getScheduleResultList().size(), "同物料有有效续作保留时，不应把全部结果都转未排");
        long unscheduledCount = context.getUnscheduledResultList().stream()
                .filter(unscheduled -> "MAT-C1".equals(unscheduled.getMaterialCode()))
                .count();
        assertEquals(0, unscheduledCount, "有效计划量已覆盖待排量时，不应额外生成未排记录");
    }

    @Test
    void handle_shouldReduceDuplicateTypeBlockResultsByTargetQty() {
        LhScheduleContext context = newContext();
        context.getMachineScheduleMap().put("M1", buildMachine("M1", "MAT-C1"));
        context.getMachineScheduleMap().put("M2", buildMachine("M2", "MAT-C2"));
        SkuScheduleDTO continuousSku1 = buildContinuousSku("MAT-C1", "M1", "EMB-1", "STRUCT-A", "SPEC-A", "PAT-A", 1);
        SkuScheduleDTO continuousSku2 = buildContinuousSku("MAT-C2", "M2", "EMB-1", "STRUCT-B", "SPEC-A", "PAT-A", 1);
        SkuScheduleDTO typeBlockSku1 = buildNewSku("MAT-T1", "EMB-1", "STRUCT-T", "SPEC-A", "PAT-B", 1);
        SkuScheduleDTO typeBlockSku2 = buildNewSku("MAT-T1", "EMB-1", "STRUCT-T", "SPEC-A", "PAT-B", 1);
        context.getContinuousSkuList().add(continuousSku1);
        context.getContinuousSkuList().add(continuousSku2);
        context.getNewSpecSkuList().add(typeBlockSku1);
        context.getNewSpecSkuList().add(typeBlockSku2);
        context.getStructureSkuMap().put("STRUCT-C", Arrays.asList(continuousSku1, continuousSku2));
        context.getStructureSkuMap().put("STRUCT-T", Arrays.asList(typeBlockSku1, typeBlockSku2));
        putMouldRel(context, "MAT-C1", "MOULD-1");
        putMouldRel(context, "MAT-C2", "MOULD-1");
        putMouldRel(context, "MAT-T1", "MOULD-1");

        when(orderNoGenerator.generateOrderNo(any())).thenReturn("ORD-1", "ORD-2", "ORD-3", "ORD-4");
        when(endingJudgmentStrategy.isEnding(any(), any())).thenAnswer(invocation -> {
            SkuScheduleDTO sku = invocation.getArgument(1);
            return "MAT-C1".equals(sku.getMaterialCode()) || "MAT-C2".equals(sku.getMaterialCode());
        });
        when(strategyFactory.getSkuPriorityStrategy()).thenReturn(skuPriorityStrategy);
        when(strategyFactory.getProductionStrategy("01")).thenReturn(continuousProductionStrategy);

        continuousProductionHandler.handle(context);

        long typeBlockResultCount = context.getScheduleResultList().stream()
                .filter(result -> "MAT-T1".equals(result.getMaterialCode()))
                .count();
        assertEquals(1, typeBlockResultCount, "同物料换活字块结果应按目标量降模收口为1条有效结果");
        long unscheduledCount = context.getUnscheduledResultList().stream()
                .filter(unscheduled -> "MAT-T1".equals(unscheduled.getMaterialCode()))
                .count();
        assertEquals(0, unscheduledCount, "保留结果已覆盖目标量时不应补写未排");
        assertTrue(context.getStructureSkuMap().isEmpty(), "S4.4 收口后结构视图应只保留剩余新增待排SKU");
    }

    private LhScheduleContext newContext() {
        LhScheduleContext context = new LhScheduleContext();
        context.setFactoryCode("116");
        context.setBatchNo("LHPC20260418099");
        context.setScheduleDate(date(2026, 4, 18));
        context.setScheduleTargetDate(date(2026, 4, 20));
        context.setScheduleWindowShifts(LhScheduleTimeUtil.buildDefaultScheduleShifts(context, context.getScheduleDate()));
        context.setMachineScheduleMap(new LinkedHashMap<>());
        return context;
    }

    private MachineScheduleDTO buildMachine(String machineCode, String currentMaterialCode) {
        MachineScheduleDTO machine = new MachineScheduleDTO();
        machine.setMachineCode(machineCode);
        machine.setMachineName(machineCode);
        machine.setCurrentMaterialCode(currentMaterialCode);
        machine.setMaxMoldNum(1);
        machine.setEstimatedEndTime(dateTime(2026, 4, 18, 6, 0, 0));
        return machine;
    }

    private SkuScheduleDTO buildContinuousSku(String materialCode,
                                              String machineCode,
                                              String embryoCode,
                                              String structureName,
                                              String specCode,
                                              String pattern,
                                              int pendingQty) {
        SkuScheduleDTO sku = new SkuScheduleDTO();
        sku.setMaterialCode(materialCode);
        sku.setMaterialDesc(materialCode);
        sku.setStructureName(structureName);
        sku.setEmbryoCode(embryoCode);
        sku.setSpecCode(specCode);
        sku.setMainPattern(pattern);
        sku.setPattern(pattern);
        sku.setContinuousMachineCode(machineCode);
        sku.setMonthPlanQty(pendingQty);
        sku.setWindowPlanQty(pendingQty);
        sku.setPendingQty(pendingQty);
        sku.setEmbryoStock(1000);
        sku.setShiftCapacity(8);
        sku.setLhTimeSeconds(3600);
        sku.setScheduleType("01");
        return sku;
    }

    private SkuScheduleDTO buildNewSku(String materialCode,
                                       String embryoCode,
                                       String structureName,
                                       String specCode,
                                       String pattern,
                                       int pendingQty) {
        SkuScheduleDTO sku = new SkuScheduleDTO();
        sku.setMaterialCode(materialCode);
        sku.setMaterialDesc(materialCode);
        sku.setStructureName(structureName);
        sku.setEmbryoCode(embryoCode);
        sku.setSpecCode(specCode);
        sku.setMainPattern(pattern);
        sku.setPattern(pattern);
        sku.setMonthPlanQty(pendingQty);
        sku.setWindowPlanQty(pendingQty);
        sku.setPendingQty(pendingQty);
        sku.setEmbryoStock(1000);
        sku.setShiftCapacity(8);
        sku.setLhTimeSeconds(3600);
        sku.setScheduleType("02");
        return sku;
    }

    private void putMouldRel(LhScheduleContext context, String materialCode, String mouldCode) {
        MdmSkuMouldRel relation = new MdmSkuMouldRel();
        relation.setMaterialCode(materialCode);
        relation.setMouldCode(mouldCode);
        context.getSkuMouldRelMap().put(materialCode, Arrays.asList(relation));
    }

    private static Date date(int year, int month, int day) {
        Calendar calendar = Calendar.getInstance();
        calendar.clear();
        calendar.set(Calendar.YEAR, year);
        calendar.set(Calendar.MONTH, month - 1);
        calendar.set(Calendar.DAY_OF_MONTH, day);
        return calendar.getTime();
    }

    private static Date dateTime(int year, int month, int day, int hour, int minute, int second) {
        Calendar calendar = Calendar.getInstance();
        calendar.clear();
        calendar.set(Calendar.YEAR, year);
        calendar.set(Calendar.MONTH, month - 1);
        calendar.set(Calendar.DAY_OF_MONTH, day);
        calendar.set(Calendar.HOUR_OF_DAY, hour);
        calendar.set(Calendar.MINUTE, minute);
        calendar.set(Calendar.SECOND, second);
        return calendar.getTime();
    }
}
