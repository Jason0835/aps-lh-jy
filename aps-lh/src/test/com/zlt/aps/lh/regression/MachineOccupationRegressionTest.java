package com.zlt.aps.lh.regression;

import com.zlt.aps.lh.api.domain.dto.MachineScheduleDTO;
import com.zlt.aps.lh.api.domain.dto.SkuScheduleDTO;
import com.zlt.aps.lh.api.domain.entity.LhScheduleResult;
import com.zlt.aps.lh.component.OrderNoGenerator;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.engine.strategy.ICapacityCalculateStrategy;
import com.zlt.aps.lh.engine.strategy.IEndingJudgmentStrategy;
import com.zlt.aps.lh.engine.strategy.IFirstInspectionBalanceStrategy;
import com.zlt.aps.lh.engine.strategy.IMachineMatchStrategy;
import com.zlt.aps.lh.engine.strategy.IMouldChangeBalanceStrategy;
import com.zlt.aps.lh.engine.strategy.impl.NewSpecProductionStrategy;
import com.zlt.aps.lh.util.MouldStatusUtil;
import com.zlt.aps.lh.util.LhScheduleTimeUtil;
import com.zlt.aps.mdm.api.domain.entity.MdmSkuMouldRel;
import com.zlt.aps.mdm.api.domain.entity.MdmModelInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Calendar;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 机台占用推进：同机连续新增分配时，后一个结果不得与前一个重叠。
 */
@ExtendWith(MockitoExtension.class)
class MachineOccupationRegressionTest {

    @Mock
    private OrderNoGenerator orderNoGenerator;

    @InjectMocks
    private NewSpecProductionStrategy strategy;

    @Mock
    private IMachineMatchStrategy machineMatchStrategy;

    @Mock
    private IMouldChangeBalanceStrategy mouldChangeBalanceStrategy;

    @Mock
    private IFirstInspectionBalanceStrategy inspectionBalanceStrategy;

    @Mock
    private ICapacityCalculateStrategy capacityCalculateStrategy;

    @Mock
    private IEndingJudgmentStrategy endingJudgmentStrategy;

    @Test
    void scheduleNewSpecs_pushesMachineOccupationForward() {
        LhScheduleContext context = newContext();
        MachineScheduleDTO machine = new MachineScheduleDTO();
        machine.setMachineCode("M1");
        machine.setMachineName("M1");
        machine.setStatus("0");
        machine.setMaxMoldNum(2);
        machine.setEstimatedEndTime(dateTime(2026, 4, 11, 6, 0));
        context.setMachineScheduleMap(new LinkedHashMap<>());
        context.getMachineScheduleMap().put("M1", machine);

        context.getNewSpecSkuList().add(newSku("MAT-A", "SPEC-A", "17"));
        context.getNewSpecSkuList().add(newSku("MAT-B", "SPEC-B", "18"));
        enableMoulds(context, "MAT-A", "MOULD-A1", "MOULD-A2");
        enableMoulds(context, "MAT-B", "MOULD-B1", "MOULD-B2");

        when(orderNoGenerator.generateOrderNo(any())).thenReturn("LHGD-1", "LHGD-2");
        when(machineMatchStrategy.matchMachines(any(), any())).thenReturn(Collections.singletonList(machine));
        when(machineMatchStrategy.selectBestMachine(any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    java.util.List<MachineScheduleDTO> candidates = invocation.getArgument(2, java.util.List.class);
                    java.util.Set<String> excludedMachineCodes = invocation.getArgument(3, java.util.Set.class);
                    if (candidates == null || candidates.isEmpty()) {
                        return null;
                    }
                    for (MachineScheduleDTO candidate : candidates) {
                        if (candidate != null && !excludedMachineCodes.contains(candidate.getMachineCode())) {
                            return candidate;
                        }
                    }
                    return null;
                });
        when(capacityCalculateStrategy.calculateStartTime(any(), anyString(), any()))
                .thenAnswer(invocation -> invocation.getArgument(2));
        when(inspectionBalanceStrategy.allocateInspection(any(), anyString(), any()))
                .thenAnswer(invocation -> invocation.getArgument(2));
        when(endingJudgmentStrategy.isCurrentWindowEnding(any(), any())).thenReturn(false);
        strategy.scheduleNewSpecs(context, machineMatchStrategy, mouldChangeBalanceStrategy,
                inspectionBalanceStrategy, capacityCalculateStrategy);

        assertEquals(2, context.getScheduleResultList().size());
        LhScheduleResult first = context.getScheduleResultList().get(0);
        LhScheduleResult second = context.getScheduleResultList().get(1);
        assertTrue(earliestStart(second).compareTo(first.getSpecEndTime()) >= 0);
        assertEquals(second.getSpecEndTime(), machine.getEstimatedEndTime());
    }

    @Test
    void scheduleNewSpecs_shouldUseAssignedResultEndTimeWhenRuntimeMachineEndTimeMissing() {
        LhScheduleContext context = newContext();
        MachineScheduleDTO machine = new MachineScheduleDTO();
        machine.setMachineCode("M1");
        machine.setMachineName("M1");
        machine.setStatus("0");
        machine.setMaxMoldNum(2);
        machine.setCurrentMaterialCode("MAT-A");
        machine.setCurrentMaterialDesc("MAT-A-DESC");
        context.setMachineScheduleMap(new LinkedHashMap<>());
        context.getMachineScheduleMap().put("M1", machine);

        LhScheduleResult previousResult = new LhScheduleResult();
        previousResult.setLhMachineCode("M1");
        previousResult.setMaterialCode("MAT-A");
        previousResult.setMaterialDesc("MAT-A-DESC");
        previousResult.setDailyPlanQty(2);
        previousResult.setSpecEndTime(dateTime(2026, 4, 12, 6, 0));
        context.getMachineAssignmentMap().put("M1", new ArrayList<>(Collections.singletonList(previousResult)));

        context.getNewSpecSkuList().add(newSku("MAT-B", "SPEC-B", "18"));
        enableMoulds(context, "MAT-B", "MOULD-B1", "MOULD-B2");

        when(orderNoGenerator.generateOrderNo(any())).thenReturn("LHGD-1");
        when(machineMatchStrategy.matchMachines(any(), any())).thenReturn(Collections.singletonList(machine));
        when(machineMatchStrategy.selectBestMachine(any(), any(), any(), any()))
                .thenReturn(machine);
        when(capacityCalculateStrategy.calculateStartTime(any(), anyString(), any()))
                .thenAnswer(invocation -> invocation.getArgument(2));
        when(inspectionBalanceStrategy.allocateInspection(any(), anyString(), any()))
                .thenAnswer(invocation -> invocation.getArgument(2));
        when(endingJudgmentStrategy.isCurrentWindowEnding(any(), any())).thenReturn(false);

        strategy.scheduleNewSpecs(context, machineMatchStrategy, mouldChangeBalanceStrategy,
                inspectionBalanceStrategy, capacityCalculateStrategy);

        assertEquals(1, context.getScheduleResultList().size());
        LhScheduleResult nextResult = context.getScheduleResultList().get(0);
        assertTrue(earliestStart(nextResult).compareTo(previousResult.getSpecEndTime()) >= 0,
                "同一机台已有其他SKU有效结果时，后续SKU必须从上一结果结束后换模衔接");
    }

    private LhScheduleContext newContext() {
        LhScheduleContext context = new LhScheduleContext();
        context.setFactoryCode("FC01");
        context.setBatchNo("LHPC20260411001");
        context.setScheduleDate(date(2026, 4, 11));
        context.setScheduleTargetDate(date(2026, 4, 13));
        context.setScheduleWindowShifts(LhScheduleTimeUtil.buildDefaultScheduleShifts(context, context.getScheduleDate()));
        return context;
    }

    private SkuScheduleDTO newSku(String materialCode, String specCode, String proSize) {
        SkuScheduleDTO dto = new SkuScheduleDTO();
        dto.setMaterialCode(materialCode);
        dto.setMaterialDesc(materialCode + "-DESC");
        dto.setStructureName("S1");
        dto.setSpecCode(specCode);
        dto.setProSize(proSize);
        dto.setPendingQty(2);
        dto.setDailyPlanQty(2);
        dto.setLhTimeSeconds(3600);
        dto.setMouldQty(1);
        dto.setScheduleType("02");
        return dto;
    }

    private MdmSkuMouldRel mould(String mouldCode) {
        MdmSkuMouldRel rel = new MdmSkuMouldRel();
        rel.setMouldCode(mouldCode);
        return rel;
    }

    private void enableMoulds(LhScheduleContext context, String materialCode, String... mouldCodes) {
        List<MdmSkuMouldRel> relList = new ArrayList<>(mouldCodes.length);
        for (String mouldCode : mouldCodes) {
            MdmSkuMouldRel rel = mould(mouldCode);
            rel.setMaterialCode(materialCode);
            relList.add(rel);
            MdmModelInfo modelInfo = new MdmModelInfo();
            modelInfo.setMouldCode(mouldCode);
            modelInfo.setMouldStatus(MouldStatusUtil.STATUS_ENABLED);
            context.getModelInfoMap().put(mouldCode, modelInfo);
        }
        context.getSkuMouldRelMap().put(materialCode, relList);
    }

    private java.util.Date earliestStart(LhScheduleResult result) {
        java.util.Date[] starts = new java.util.Date[]{
                result.getClass1StartTime(), result.getClass2StartTime(), result.getClass3StartTime(), result.getClass4StartTime(),
                result.getClass5StartTime(), result.getClass6StartTime(), result.getClass7StartTime(), result.getClass8StartTime()
        };
        java.util.Date earliest = null;
        for (java.util.Date start : starts) {
            if (start != null && (earliest == null || start.before(earliest))) {
                earliest = start;
            }
        }
        return earliest;
    }

    private static java.util.Date date(int y, int month, int day) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(Calendar.YEAR, y);
        c.set(Calendar.MONTH, month - 1);
        c.set(Calendar.DAY_OF_MONTH, day);
        return c.getTime();
    }

    private static java.util.Date dateTime(int y, int month, int day, int hour, int minute) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(Calendar.YEAR, y);
        c.set(Calendar.MONTH, month - 1);
        c.set(Calendar.DAY_OF_MONTH, day);
        c.set(Calendar.HOUR_OF_DAY, hour);
        c.set(Calendar.MINUTE, minute);
        return c.getTime();
    }
}
