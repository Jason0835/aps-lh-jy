package com.zlt.aps.lh.regression;

import com.zlt.aps.lh.api.domain.dto.MachineScheduleDTO;
import com.zlt.aps.lh.api.domain.dto.SkuScheduleDTO;
import com.zlt.aps.lh.api.domain.entity.LhScheduleResult;
import com.zlt.aps.lh.component.OrderNoGenerator;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.engine.strategy.IEndingJudgmentStrategy;
import com.zlt.aps.lh.engine.strategy.impl.ContinuousProductionStrategy;
import com.zlt.aps.lh.engine.strategy.impl.DefaultCapacityCalculateStrategy;
import com.zlt.aps.lh.engine.strategy.impl.DefaultFirstInspectionBalanceStrategy;
import com.zlt.aps.lh.engine.strategy.impl.DefaultMouldChangeBalanceStrategy;
import com.zlt.aps.lh.engine.strategy.impl.TypeBlockProductionStrategy;
import com.zlt.aps.lh.util.LhScheduleTimeUtil;
import com.zlt.aps.mdm.api.domain.entity.MdmSkuMouldRel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Calendar;
import java.util.Date;
import java.util.Arrays;
import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 回归：换活字块结果必须登记来源SKU，避免S4.6漏校验scheduleType=03结果。
 */
@ExtendWith(MockitoExtension.class)
class TypeBlockResultSourceSkuRegressionTest {

    @Mock
    private OrderNoGenerator orderNoGenerator;

    @Mock
    private IEndingJudgmentStrategy endingJudgmentStrategy;

    @Spy
    private DefaultMouldChangeBalanceStrategy mouldChangeBalanceStrategy = new DefaultMouldChangeBalanceStrategy();

    @Spy
    private DefaultFirstInspectionBalanceStrategy firstInspectionBalanceStrategy =
            new DefaultFirstInspectionBalanceStrategy();

    @Spy
    private DefaultCapacityCalculateStrategy capacityCalculateStrategy = new DefaultCapacityCalculateStrategy();

    @InjectMocks
    private ContinuousProductionStrategy continuousProductionStrategy;

    @InjectMocks
    private TypeBlockProductionStrategy typeBlockProductionStrategy;

    @Test
    void scheduleTypeBlockChange_shouldRegisterSourceSkuForFollowUpResult() {
        LhScheduleContext context = newContext();
        context.getMachineScheduleMap().put("M1", buildMachine("M1", "MAT-C1"));
        context.getContinuousSkuList().add(buildContinuousSku(
                "MAT-C1", "M1", "EMB-1", "STRUCT-A", "SPEC-A", "PAT-A", 1));
        SkuScheduleDTO typeBlockSku = buildNewSku("MAT-T1", "EMB-1", "STRUCT-B", "SPEC-A", "PAT-B", 1);
        context.getNewSpecSkuList().add(typeBlockSku);
        putMouldRel(context, "MAT-C1", "MOULD-1");
        putMouldRel(context, "MAT-T1", "MOULD-1");

        when(orderNoGenerator.generateOrderNo(any())).thenReturn("ORD-1", "ORD-2");
        when(endingJudgmentStrategy.isCurrentWindowEnding(any(), any())).thenAnswer(invocation -> {
            SkuScheduleDTO sku = invocation.getArgument(1);
            return sku != null && "MAT-C1".equals(sku.getMaterialCode());
        });

        continuousProductionStrategy.scheduleContinuousEnding(context);
        typeBlockProductionStrategy.scheduleTypeBlockChange(context);

        LhScheduleResult typeBlockResult = context.getScheduleResultList().stream()
                .filter(result -> "1".equals(result.getIsTypeBlock()))
                .filter(result -> "MAT-T1".equals(result.getMaterialCode()))
                .findFirst()
                .orElse(null);
        assertNotNull(typeBlockResult, "换活字块结果应成功落入排程结果");
        assertEquals("03", typeBlockResult.getScheduleType());
        assertEquals(typeBlockSku, context.getScheduleResultSourceSkuMap().get(typeBlockResult),
                "换活字块结果必须登记来源SKU");
    }

    private LhScheduleContext newContext() {
        LhScheduleContext context = new LhScheduleContext();
        context.setFactoryCode("116");
        context.setBatchNo("LHPC20260418101");
        context.setScheduleDate(date(2026, 4, 18));
        context.setScheduleTargetDate(date(2026, 4, 20));
        context.setScheduleWindowShifts(LhScheduleTimeUtil.buildDefaultScheduleShifts(context, context.getScheduleDate()));
        context.setMachineScheduleMap(new LinkedHashMap<String, MachineScheduleDTO>(2));
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
        sku.setSurplusQty(pendingQty);
        sku.setEmbryoStock(pendingQty);
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
        sku.setSurplusQty(pendingQty);
        sku.setEmbryoStock(pendingQty);
        sku.setShiftCapacity(8);
        sku.setLhTimeSeconds(3600);
        sku.setScheduleType("02");
        return sku;
    }

    private void putMouldRel(LhScheduleContext context, String materialCode, String mouldCode) {
        MdmSkuMouldRel mouldRel = new MdmSkuMouldRel();
        mouldRel.setMaterialCode(materialCode);
        mouldRel.setMouldCode(mouldCode);
        context.getSkuMouldRelMap().put(materialCode, Arrays.asList(mouldRel));
    }

    private Date date(int year, int month, int day) {
        Calendar calendar = Calendar.getInstance();
        calendar.clear();
        calendar.set(year, month - 1, day, 0, 0, 0);
        return calendar.getTime();
    }

    private Date dateTime(int year, int month, int day, int hour, int minute, int second) {
        Calendar calendar = Calendar.getInstance();
        calendar.clear();
        calendar.set(year, month - 1, day, hour, minute, second);
        return calendar.getTime();
    }
}
