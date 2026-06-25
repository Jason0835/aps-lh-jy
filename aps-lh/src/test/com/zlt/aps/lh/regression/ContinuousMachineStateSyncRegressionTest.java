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
import com.zlt.aps.lh.engine.strategy.impl.ContinuousProductionStrategy;
import com.zlt.aps.lh.engine.strategy.impl.NewSpecProductionStrategy;
import com.zlt.aps.lh.util.LhScheduleTimeUtil;
import com.zlt.aps.mdm.api.domain.entity.MdmSkuMouldRel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 回归：S4.4 机台终态同步应纳入换活字块结果，避免 S4.5 重复占用同机台窗口。
 */
@ExtendWith(MockitoExtension.class)
class ContinuousMachineStateSyncRegressionTest {

    @Mock
    private OrderNoGenerator orderNoGenerator;

    @Mock
    private IEndingJudgmentStrategy endingJudgmentStrategy;

    @InjectMocks
    private ContinuousProductionStrategy continuousProductionStrategy;

    @InjectMocks
    private NewSpecProductionStrategy newSpecProductionStrategy;

    @Mock
    private IMachineMatchStrategy machineMatchStrategy;

    @Mock
    private IMouldChangeBalanceStrategy mouldChangeBalanceStrategy;

    @Mock
    private IFirstInspectionBalanceStrategy inspectionBalanceStrategy;

    @Mock
    private ICapacityCalculateStrategy capacityCalculateStrategy;

    @Test
    void scheduleReduceMould_shouldSyncMachineStateFromTypeBlockResult() {
        LhScheduleContext context = newContext();
        MachineScheduleDTO machine = buildMachine("M1", "MAT-OLD", dateTime(2026, 4, 21, 6, 0));
        context.getMachineScheduleMap().put(machine.getMachineCode(), machine);

        Date typeBlockEndTime = dateTime(2026, 4, 23, 18, 48);
        registerExistingResult(context, buildResult(
                "M1", "MAT-TB", "03", "1", 120, typeBlockEndTime));

        continuousProductionStrategy.scheduleReduceMould(context);

        assertEquals("MAT-TB", machine.getCurrentMaterialCode());
        assertEquals(typeBlockEndTime, machine.getEstimatedEndTime());
    }

    @Test
    void scheduleReduceMould_thenScheduleNewSpecs_shouldAvoidOverlapWithTypeBlockWindow() {
        LhScheduleContext context = newContext();
        MachineScheduleDTO machine = buildMachine("M1", "MAT-OLD", dateTime(2026, 4, 21, 6, 0));
        context.getMachineScheduleMap().put(machine.getMachineCode(), machine);
        Date typeBlockEndTime = dateTime(2026, 4, 23, 6, 0);
        registerExistingResult(context, buildResult(
                "M1", "MAT-TB", "03", "1", 120, typeBlockEndTime));
        continuousProductionStrategy.scheduleReduceMould(context);

        SkuScheduleDTO newSku = buildNewSku("MAT-NEW", "SPEC-NEW", "17");
        context.getNewSpecSkuList().add(newSku);
        context.getSkuMouldRelMap().put("MAT-NEW", Collections.singletonList(mould("MOULD-NEW")));

        when(orderNoGenerator.generateOrderNo(any())).thenReturn("LHGD-NEW-1");
        when(endingJudgmentStrategy.isCurrentWindowEnding(any(), any())).thenReturn(false);
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
        when(mouldChangeBalanceStrategy.allocateMouldChange(any(), anyString(), any(), anyInt()))
                .thenAnswer(invocation -> invocation.getArgument(2));
        when(inspectionBalanceStrategy.allocateInspection(any(), anyString(), any()))
                .thenAnswer(invocation -> invocation.getArgument(2));

        newSpecProductionStrategy.scheduleNewSpecs(
                context, machineMatchStrategy, mouldChangeBalanceStrategy,
                inspectionBalanceStrategy, capacityCalculateStrategy);

        LhScheduleResult newResult = context.getScheduleResultList().stream()
                .filter(result -> "MAT-NEW".equals(result.getMaterialCode()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("缺少新增排产结果"));
        assertTrue(earliestStart(newResult).compareTo(typeBlockEndTime) >= 0);
    }

    @Test
    void scheduleReduceMould_shouldKeepContinuousResultSyncBehavior() {
        LhScheduleContext context = newContext();
        MachineScheduleDTO machine = buildMachine("M1", "MAT-OLD", dateTime(2026, 4, 21, 6, 0));
        context.getMachineScheduleMap().put(machine.getMachineCode(), machine);
        Date continuousEndTime = dateTime(2026, 4, 23, 20, 0);
        registerExistingResult(context, buildResult(
                "M1", "MAT-C1", "01", "0", 100, continuousEndTime));

        continuousProductionStrategy.scheduleReduceMould(context);

        assertEquals("MAT-C1", machine.getCurrentMaterialCode());
        assertEquals(continuousEndTime, machine.getEstimatedEndTime());
    }

    private LhScheduleContext newContext() {
        LhScheduleContext context = new LhScheduleContext();
        context.setFactoryCode("116");
        context.setBatchNo("LHPC20260423006");
        context.setScheduleDate(date(2026, 4, 21));
        context.setScheduleTargetDate(date(2026, 4, 23));
        context.setScheduleWindowShifts(LhScheduleTimeUtil.buildDefaultScheduleShifts(context, context.getScheduleDate()));
        context.setMachineScheduleMap(new LinkedHashMap<>());
        return context;
    }

    private MachineScheduleDTO buildMachine(String machineCode, String currentMaterialCode, Date estimatedEndTime) {
        MachineScheduleDTO machine = new MachineScheduleDTO();
        machine.setMachineCode(machineCode);
        machine.setMachineName(machineCode);
        machine.setStatus("1");
        machine.setCurrentMaterialCode(currentMaterialCode);
        machine.setCurrentMaterialDesc(currentMaterialCode + "-DESC");
        machine.setEstimatedEndTime(estimatedEndTime);
        machine.setMaxMoldNum(2);
        return machine;
    }

    private LhScheduleResult buildResult(String machineCode,
                                         String materialCode,
                                         String scheduleType,
                                         String isTypeBlock,
                                         int dailyPlanQty,
                                         Date specEndTime) {
        LhScheduleResult result = new LhScheduleResult();
        result.setFactoryCode("116");
        result.setBatchNo("LHPC20260423006");
        result.setLhMachineCode(machineCode);
        result.setMaterialCode(materialCode);
        result.setMaterialDesc(materialCode + "-DESC");
        result.setSpecCode(materialCode + "-SPEC");
        result.setScheduleType(scheduleType);
        result.setIsTypeBlock(isTypeBlock);
        result.setDailyPlanQty(dailyPlanQty);
        result.setSpecEndTime(specEndTime);
        result.setTdaySpecEndTime(specEndTime);
        return result;
    }

    private void registerExistingResult(LhScheduleContext context, LhScheduleResult result) {
        context.getScheduleResultList().add(result);
        context.getScheduleResultSourceSkuMap().put(result, buildSourceSku(result.getMaterialCode(), result.getDailyPlanQty()));
    }

    private SkuScheduleDTO buildSourceSku(String materialCode, int targetQty) {
        SkuScheduleDTO dto = new SkuScheduleDTO();
        dto.setMaterialCode(materialCode);
        dto.setMaterialDesc(materialCode + "-DESC");
        dto.setPendingQty(targetQty);
        dto.setSurplusQty(targetQty);
        dto.setEmbryoStock(targetQty);
        dto.setTargetScheduleQty(targetQty);
        dto.setLhTimeSeconds(3600);
        dto.setShiftCapacity(20);
        dto.setMouldQty(1);
        return dto;
    }

    private SkuScheduleDTO buildNewSku(String materialCode, String specCode, String proSize) {
        SkuScheduleDTO dto = new SkuScheduleDTO();
        dto.setMaterialCode(materialCode);
        dto.setMaterialDesc(materialCode + "-DESC");
        dto.setStructureName("STRUCT-NEW");
        dto.setSpecCode(specCode);
        dto.setProSize(proSize);
        dto.setPendingQty(80);
        dto.setSurplusQty(80);
        dto.setTargetScheduleQty(80);
        dto.setLhTimeSeconds(3600);
        dto.setShiftCapacity(20);
        dto.setMouldQty(1);
        dto.setScheduleType("02");
        return dto;
    }

    private MdmSkuMouldRel mould(String mouldCode) {
        MdmSkuMouldRel rel = new MdmSkuMouldRel();
        rel.setMouldCode(mouldCode);
        return rel;
    }

    private Date earliestStart(LhScheduleResult result) {
        Date[] starts = new Date[]{
                result.getClass1StartTime(), result.getClass2StartTime(), result.getClass3StartTime(), result.getClass4StartTime(),
                result.getClass5StartTime(), result.getClass6StartTime(), result.getClass7StartTime(), result.getClass8StartTime()
        };
        Date earliest = null;
        for (Date start : starts) {
            if (start != null && (earliest == null || start.before(earliest))) {
                earliest = start;
            }
        }
        return earliest;
    }

    private static Date date(int y, int month, int day) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(Calendar.YEAR, y);
        c.set(Calendar.MONTH, month - 1);
        c.set(Calendar.DAY_OF_MONTH, day);
        return c.getTime();
    }

    private static Date dateTime(int y, int month, int day, int hour, int minute) {
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
