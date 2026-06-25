package com.zlt.aps.lh.regression;

import com.zlt.aps.lh.api.domain.dto.MachineScheduleDTO;
import com.zlt.aps.lh.api.domain.dto.SkuScheduleDTO;
import com.zlt.aps.lh.api.domain.entity.LhUnscheduledResult;
import com.zlt.aps.lh.component.OrderNoGenerator;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.engine.factory.ScheduleStrategyFactory;
import com.zlt.aps.lh.engine.strategy.ICapacityCalculateStrategy;
import com.zlt.aps.lh.engine.strategy.IEndingJudgmentStrategy;
import com.zlt.aps.lh.engine.strategy.IFirstInspectionBalanceStrategy;
import com.zlt.aps.lh.engine.strategy.IMachineMatchStrategy;
import com.zlt.aps.lh.engine.strategy.IMouldChangeBalanceStrategy;
import com.zlt.aps.lh.engine.strategy.ISkuPriorityStrategy;
import com.zlt.aps.lh.engine.strategy.impl.LocalSearchMachineAllocatorStrategy;
import com.zlt.aps.lh.engine.strategy.impl.NewSpecProductionStrategy;
import com.zlt.aps.lh.handler.NewProductionHandler;
import com.zlt.aps.lh.util.LhScheduleTimeUtil;
import com.zlt.aps.mdm.api.domain.entity.MdmSkuMouldRel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 回归：通过 NewProductionHandler 真实链路覆盖新增零计划结果移除/转未排语义。
 */
@ExtendWith(MockitoExtension.class)
class NewProductionHandlerZeroPlanRegressionTest {

    @Mock
    private ScheduleStrategyFactory strategyFactory;

    @Mock
    private ISkuPriorityStrategy skuPriorityStrategy;

    @Mock
    private IMachineMatchStrategy machineMatchStrategy;

    @Mock
    private IMouldChangeBalanceStrategy mouldChangeBalanceStrategy;

    @Mock
    private IFirstInspectionBalanceStrategy firstInspectionBalanceStrategy;

    @Mock
    private ICapacityCalculateStrategy capacityCalculateStrategy;

    @Mock
    private OrderNoGenerator orderNoGenerator;

    @Mock
    private IEndingJudgmentStrategy endingJudgmentStrategy;

    @Mock
    private LocalSearchMachineAllocatorStrategy localSearchMachineAllocator;

    @InjectMocks
    private NewSpecProductionStrategy newSpecProductionStrategy;

    @InjectMocks
    private NewProductionHandler newProductionHandler;

    @Test
    void handle_shouldRemoveZeroPlanNewSpecResultAndWriteUnscheduled() {
        LhScheduleContext context = newContext();
        MachineScheduleDTO machine = buildMachine();
        SkuScheduleDTO sku = buildSku();
        context.getMachineScheduleMap().put(machine.getMachineCode(), machine);
        context.getNewSpecSkuList().add(sku);
        context.getStructureSkuMap().put("STRUCT-ZERO", Arrays.asList(sku));
        putMouldRel(context, sku.getMaterialCode(), "MOULD-1");

        when(strategyFactory.getProductionStrategy("02")).thenReturn(newSpecProductionStrategy);
        when(strategyFactory.getSkuPriorityStrategy()).thenReturn(skuPriorityStrategy);
        when(strategyFactory.getMachineMatchStrategy()).thenReturn(machineMatchStrategy);
        when(strategyFactory.getMouldChangeBalanceStrategy()).thenReturn(mouldChangeBalanceStrategy);
        when(strategyFactory.getFirstInspectionBalanceStrategy()).thenReturn(firstInspectionBalanceStrategy);
        when(strategyFactory.getCapacityCalculateStrategy()).thenReturn(capacityCalculateStrategy);

        when(machineMatchStrategy.matchMachines(any(LhScheduleContext.class), any(SkuScheduleDTO.class)))
                .thenReturn(Collections.singletonList(machine));
        when(machineMatchStrategy.selectBestMachine(any(LhScheduleContext.class), any(SkuScheduleDTO.class),
                any(List.class), any(Set.class)))
                .thenAnswer(invocation -> {
                    Set<String> excluded = invocation.getArgument(3);
                    return excluded.contains(machine.getMachineCode()) ? null : machine;
                });
        when(mouldChangeBalanceStrategy.allocateMouldChange(any(LhScheduleContext.class), anyString(), any(Date.class)))
                .thenAnswer(invocation -> invocation.getArgument(2));
        when(firstInspectionBalanceStrategy.allocateInspection(any(LhScheduleContext.class), anyString(), any(Date.class)))
                .thenAnswer(invocation -> invocation.getArgument(2));
        when(capacityCalculateStrategy.calculateStartTime(any(LhScheduleContext.class), anyString(), any(Date.class)))
                .thenAnswer(invocation -> invocation.getArgument(2));
        when(orderNoGenerator.generateOrderNo(any(Date.class))).thenReturn("ORD-1");
        when(endingJudgmentStrategy.isCurrentWindowEnding(any(LhScheduleContext.class), any(SkuScheduleDTO.class))).thenReturn(false);

        newProductionHandler.handle(context);

        assertEquals(0, context.getScheduleResultList().size(), "新增零计划结果应在S4.5链路中被移除");
        assertEquals(1, context.getUnscheduledResultList().size(), "新增零计划结果应转未排");
        LhUnscheduledResult unscheduledResult = context.getUnscheduledResultList().get(0);
        assertEquals("MAT-ZERO", unscheduledResult.getMaterialCode());
        assertEquals(1, unscheduledResult.getUnscheduledQty());
        assertEquals("新增结果裁剪为0", unscheduledResult.getUnscheduledReason());
        assertEquals("STRUCT-ZERO", unscheduledResult.getStructureName());
        assertEquals("SPEC-Z", unscheduledResult.getSpecCode());
        assertEquals("EMB-1", unscheduledResult.getEmbryoCode());
        assertEquals(1, unscheduledResult.getMouldQty());
        assertEquals("MAT-BASE", machine.getCurrentMaterialCode(), "机台当前物料应回滚到初始状态");
        assertEquals(dateTime(2026, 4, 17, 6, 0), machine.getEstimatedEndTime(), "机台预计完工应回滚到初始值");
        assertTrue(context.getMachineAssignmentMap().isEmpty(), "零计划结果移除后不应残留机台分配记录");
    }

    @Test
    void handle_shouldKeepSkuMetadataAvailableForPostAdjustAndCleanupStructureMapAfterwards() {
        LhScheduleContext context = newContext();
        MachineScheduleDTO machine = buildMachine();
        SkuScheduleDTO sku = buildSku();
        sku.setPendingQty(2);
        sku.setWindowPlanQty(2);
        sku.setMonthPlanQty(2);
        sku.setEmbryoStock(1);
        context.getMachineScheduleMap().put(machine.getMachineCode(), machine);
        context.getNewSpecSkuList().add(sku);
        context.getStructureSkuMap().put("STRUCT-ZERO", Arrays.asList(sku));
        putMouldRel(context, sku.getMaterialCode(), "MOULD-1");

        when(strategyFactory.getProductionStrategy("02")).thenReturn(newSpecProductionStrategy);
        when(strategyFactory.getSkuPriorityStrategy()).thenReturn(skuPriorityStrategy);
        when(strategyFactory.getMachineMatchStrategy()).thenReturn(machineMatchStrategy);
        when(strategyFactory.getMouldChangeBalanceStrategy()).thenReturn(mouldChangeBalanceStrategy);
        when(strategyFactory.getFirstInspectionBalanceStrategy()).thenReturn(firstInspectionBalanceStrategy);
        when(strategyFactory.getCapacityCalculateStrategy()).thenReturn(capacityCalculateStrategy);

        when(machineMatchStrategy.matchMachines(any(LhScheduleContext.class), any(SkuScheduleDTO.class)))
                .thenReturn(Collections.singletonList(machine));
        when(machineMatchStrategy.selectBestMachine(any(LhScheduleContext.class), any(SkuScheduleDTO.class),
                any(List.class), any(Set.class)))
                .thenAnswer(invocation -> {
                    Set<String> excluded = invocation.getArgument(3);
                    return excluded.contains(machine.getMachineCode()) ? null : machine;
                });
        when(mouldChangeBalanceStrategy.allocateMouldChange(any(LhScheduleContext.class), anyString(), any(Date.class)))
                .thenAnswer(invocation -> invocation.getArgument(2));
        when(firstInspectionBalanceStrategy.allocateInspection(any(LhScheduleContext.class), anyString(), any(Date.class)))
                .thenAnswer(invocation -> invocation.getArgument(2));
        when(capacityCalculateStrategy.calculateStartTime(any(LhScheduleContext.class), anyString(), any(Date.class)))
                .thenAnswer(invocation -> invocation.getArgument(2));
        when(orderNoGenerator.generateOrderNo(any(Date.class))).thenReturn("ORD-2");
        when(endingJudgmentStrategy.isCurrentWindowEnding(any(LhScheduleContext.class), any(SkuScheduleDTO.class))).thenReturn(false);

        newProductionHandler.handle(context);

        assertEquals(1, context.getScheduleResultList().size(), "库存裁剪后应保留有效新增结果");
        assertEquals(1, context.getScheduleResultList().get(0).getDailyPlanQty(), "应按胎胚库存裁减到1");
        assertEquals("MAT-ZERO", machine.getCurrentMaterialCode(), "机台当前物料应回写为新增结果物料");
        assertEquals("22.5", machine.getPreviousProSize(), "后置机台状态回写仍应能拿到SKU规格信息");
        assertTrue(context.getUnscheduledResultList().isEmpty(), "存在有效保留结果时不应额外生成未排");
        assertTrue(context.getStructureSkuMap().isEmpty(), "S4.5 收口后结构视图应只保留当前待排SKU");
    }

    private LhScheduleContext newContext() {
        LhScheduleContext context = new LhScheduleContext();
        Date scheduleDate = dateTime(2026, 4, 17, 0, 0);
        context.setFactoryCode("116");
        context.setBatchNo("LHPC20260418001");
        context.setScheduleDate(scheduleDate);
        context.setScheduleTargetDate(scheduleDate);
        context.setScheduleWindowShifts(LhScheduleTimeUtil.buildDefaultScheduleShifts(context, scheduleDate));
        context.setMachineScheduleMap(new LinkedHashMap<>());
        return context;
    }

    private MachineScheduleDTO buildMachine() {
        MachineScheduleDTO machine = new MachineScheduleDTO();
        machine.setMachineCode("M-ZERO");
        machine.setMachineName("裁剪机台");
        machine.setCurrentMaterialCode("MAT-BASE");
        machine.setCurrentMaterialDesc("基础物料");
        machine.setPreviousSpecCode("BASE-SPEC");
        machine.setPreviousProSize("22.5");
        machine.setEstimatedEndTime(dateTime(2026, 4, 17, 6, 0));
        machine.setMaxMoldNum(1);
        return machine;
    }

    private SkuScheduleDTO buildSku() {
        SkuScheduleDTO sku = new SkuScheduleDTO();
        sku.setMaterialCode("MAT-ZERO");
        sku.setMaterialDesc("零计划物料");
        sku.setSpecCode("SPEC-Z");
        sku.setSpecDesc("SPEC-Z");
        sku.setProSize("22.5");
        sku.setEmbryoCode("EMB-1");
        sku.setStructureName("STRUCT-ZERO");
        sku.setPendingQty(1);
        sku.setWindowPlanQty(1);
        sku.setMonthPlanQty(1);
        sku.setEmbryoStock(0);
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

    private static Date dateTime(int year, int month, int day, int hour, int minute) {
        Calendar calendar = Calendar.getInstance();
        calendar.clear();
        calendar.set(Calendar.YEAR, year);
        calendar.set(Calendar.MONTH, month - 1);
        calendar.set(Calendar.DAY_OF_MONTH, day);
        calendar.set(Calendar.HOUR_OF_DAY, hour);
        calendar.set(Calendar.MINUTE, minute);
        return calendar.getTime();
    }
}
