package com.zlt.aps.lh.regression;

import com.zlt.aps.lh.api.constant.LhScheduleConstant;
import com.zlt.aps.lh.api.domain.dto.MachineScheduleDTO;
import com.zlt.aps.lh.api.domain.dto.SkuDailyPlanQuotaDTO;
import com.zlt.aps.lh.api.domain.dto.SkuScheduleDTO;
import com.zlt.aps.lh.api.domain.entity.LhMachineInfo;
import com.zlt.aps.lh.api.domain.entity.LhMachineOnlineInfo;
import com.zlt.aps.lh.api.domain.entity.LhScheduleResult;
import com.zlt.aps.lh.api.enums.ConstructionStageEnum;
import com.zlt.aps.lh.component.SkuDecrementChecker;
import com.zlt.aps.lh.component.MonthPlanDateResolver;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.handler.DataInitHandler;
import com.zlt.aps.lh.handler.ScheduleAdjustHandler;
import com.zlt.aps.lh.engine.strategy.support.PendingSkuUnscheduledRule;
import com.zlt.aps.lh.util.LhScheduleTimeUtil;
import com.zlt.aps.lh.util.ShiftFieldUtil;
import com.zlt.aps.mdm.api.domain.entity.MdmDevicePlanShut;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 续作与新增SKU归类回归：校验续作匹配顺序及窗口无计划新增SKU准入。
 */
class ContinuousSkuAssignmentRegressionTest {

    private final ScheduleAdjustHandler handler = buildHandler();

    /**
     * 构建续作分类处理器，并补齐当前分类入口必需的减量清单组件。
     *
     * @return 可直接执行续作分类私有方法的处理器
     */
    private ScheduleAdjustHandler buildHandler() {
        ScheduleAdjustHandler scheduleAdjustHandler = new ScheduleAdjustHandler();
        ReflectionTestUtils.setField(scheduleAdjustHandler, "skuDecrementChecker", new SkuDecrementChecker());
        return scheduleAdjustHandler;
    }

    @Test
    void classifyContinuousAndNewSkus_shouldKeepPlannedRepairMachineAsContinuous() {
        LhScheduleContext context = new LhScheduleContext();
        context.setScheduleDate(dateTime(2026, 7, 6, 0, 0));
        context.setScheduleTargetDate(dateTime(2026, 7, 8, 0, 0));
        context.setScheduleWindowShifts(LhScheduleTimeUtil.buildDefaultScheduleShifts(
                context, context.getScheduleDate()));

        SkuScheduleDTO sku = buildSku("3302000750", "STRUCT-0750");
        Map<String, java.util.List<SkuScheduleDTO>> structureSkuMap = new LinkedHashMap<>();
        structureSkuMap.put("STRUCT-0750", new ArrayList<SkuScheduleDTO>(Collections.singletonList(sku)));
        context.setStructureSkuMap(structureSkuMap);

        LhMachineInfo machineInfo = new LhMachineInfo();
        machineInfo.setMachineCode("K1110");
        machineInfo.setMachineName("K1110");
        machineInfo.setStatus("1");
        machineInfo.setMaxMoldNum(2);
        context.setMachineInfoMap(new LinkedHashMap<String, LhMachineInfo>());
        context.getMachineInfoMap().put("K1110", machineInfo);

        context.setMachineOnlineInfoMap(new LinkedHashMap<String, LhMachineOnlineInfo>());
        context.getMachineOnlineInfoMap().put("K1110", buildOnlineInfo("K1110", "3302000750"));

        MdmDevicePlanShut plannedRepair = new MdmDevicePlanShut();
        plannedRepair.setMachineCode("K1110");
        plannedRepair.setMachineStopType("05");
        plannedRepair.setBeginDate(dateTime(2026, 7, 8, 8, 0));
        plannedRepair.setEndDate(dateTime(2026, 7, 8, 16, 0));
        context.setDevicePlanShutList(Collections.singletonList(plannedRepair));

        // 先走真实初始化入口，再执行续作/新增分类，验证05维修不会通过全局状态阻断续作。
        ReflectionTestUtils.invokeMethod(new DataInitHandler(), "buildStandardDataObjects", context);
        ReflectionTestUtils.invokeMethod(handler, "classifyContinuousAndNewSkus", context);

        assertEquals(1, context.getContinuousSkuList().size());
        assertEquals("3302000750", context.getContinuousSkuList().get(0).getMaterialCode());
        assertEquals("K1110", context.getContinuousSkuList().get(0).getContinuousMachineCode());
        assertEquals("01", context.getContinuousSkuList().get(0).getScheduleType());
        assertTrue(context.getNewSpecSkuList().isEmpty());
    }

    @Test
    void classifyContinuousAndNewSkus_shouldSkipMesMachineThatIsNotSchedulable() {
        LhScheduleContext context = new LhScheduleContext();

        SkuScheduleDTO sku = new SkuScheduleDTO();
        sku.setMaterialCode("3302001316");
        sku.setMaterialDesc("测试物料");
        sku.setStructureName("STRUCT-1");
        sku.setProductStatus("S");

        Map<String, java.util.List<SkuScheduleDTO>> structureSkuMap = new LinkedHashMap<>();
        structureSkuMap.put("STRUCT-1", new ArrayList<SkuScheduleDTO>());
        structureSkuMap.get("STRUCT-1").add(sku);
        context.setStructureSkuMap(structureSkuMap);

        Map<String, LhMachineOnlineInfo> machineOnlineInfoMap = new LinkedHashMap<>();
        machineOnlineInfoMap.put("K1110", buildOnlineInfo("K1110", "3302001316"));
        machineOnlineInfoMap.put("K1113", buildOnlineInfo("K1113", "3302001316"));
        context.setMachineOnlineInfoMap(machineOnlineInfoMap);

        Map<String, MachineScheduleDTO> machineScheduleMap = new LinkedHashMap<>();
        machineScheduleMap.put("K1113", buildMachine("K1113", "1"));
        context.setMachineScheduleMap(machineScheduleMap);

        ReflectionTestUtils.invokeMethod(handler, "classifyContinuousAndNewSkus", context);

        assertEquals(1, context.getContinuousSkuList().size());
        assertEquals("K1113", context.getContinuousSkuList().get(0).getContinuousMachineCode());
        assertEquals(0, context.getNewSpecSkuList().size());
    }

    @Test
    void classifyContinuousAndNewSkus_shouldPreferRollingInheritedMaterialOverMesSnapshot() {
        LhScheduleContext context = new LhScheduleContext();
        context.setRollingScheduleHandoff(true);

        Map<String, java.util.List<SkuScheduleDTO>> structureSkuMap = new LinkedHashMap<>();
        structureSkuMap.put("STRUCT-1", new ArrayList<SkuScheduleDTO>());
        structureSkuMap.get("STRUCT-1").add(buildSku("3302001585", "STRUCT-1"));
        structureSkuMap.get("STRUCT-1").add(buildSku("3302001270", "STRUCT-1"));
        context.setStructureSkuMap(structureSkuMap);

        Map<String, LhMachineOnlineInfo> machineOnlineInfoMap = new LinkedHashMap<>();
        machineOnlineInfoMap.put("K1105", buildOnlineInfo("K1105", "3302001270"));
        context.setMachineOnlineInfoMap(machineOnlineInfoMap);

        Map<String, MachineScheduleDTO> machineScheduleMap = new LinkedHashMap<>();
        machineScheduleMap.put("K1105", buildMachine("K1105", "1"));
        machineScheduleMap.get("K1105").setCurrentMaterialCode("3302001585");
        context.setMachineScheduleMap(machineScheduleMap);
        context.getRollingInheritedScheduleResultList().add(buildInheritedResult("K1105", "3302001585", "0"));

        ReflectionTestUtils.invokeMethod(handler, "classifyContinuousAndNewSkus", context);

        assertEquals(1, context.getContinuousSkuList().size());
        assertEquals("3302001585", context.getContinuousSkuList().get(0).getMaterialCode());
        assertEquals("K1105", context.getContinuousSkuList().get(0).getContinuousMachineCode());
        assertEquals(1, context.getNewSpecSkuList().size());
        assertEquals("3302001270", context.getNewSpecSkuList().get(0).getMaterialCode());
    }

    @Test
    void classifyContinuousAndNewSkus_shouldAssignRollingInheritedMaterialWithoutMesSnapshot() {
        LhScheduleContext context = new LhScheduleContext();
        context.setRollingScheduleHandoff(true);

        Map<String, java.util.List<SkuScheduleDTO>> structureSkuMap = new LinkedHashMap<>();
        structureSkuMap.put("STRUCT-1", new ArrayList<SkuScheduleDTO>());
        structureSkuMap.get("STRUCT-1").add(buildSku("3302001002", "STRUCT-1"));
        context.setStructureSkuMap(structureSkuMap);

        Map<String, MachineScheduleDTO> machineScheduleMap = new LinkedHashMap<>();
        machineScheduleMap.put("K2003", buildMachine("K2003", "1"));
        machineScheduleMap.get("K2003").setCurrentMaterialCode("3302001002");
        context.setMachineScheduleMap(machineScheduleMap);
        context.getRollingInheritedScheduleResultList().add(buildInheritedResult("K2003", "3302001002", "0"));

        ReflectionTestUtils.invokeMethod(handler, "classifyContinuousAndNewSkus", context);

        assertEquals(1, context.getContinuousSkuList().size());
        assertEquals("3302001002", context.getContinuousSkuList().get(0).getMaterialCode());
        assertEquals("K2003", context.getContinuousSkuList().get(0).getContinuousMachineCode());
        assertEquals(0, context.getNewSpecSkuList().size());
    }

    /**
     * 滚动衔接优先于MES快照时，也必须按继承结果的物料编码与产品状态承接SKU。
     */
    @Test
    void classifyContinuousAndNewSkus_shouldUseRollingInheritedProductStatus() {
        LhScheduleContext context = new LhScheduleContext();
        context.setRollingScheduleHandoff(true);
        String materialCode = "3302002217";

        SkuScheduleDTO normalSku = buildSku(materialCode, "STRUCT-1", "S");
        SkuScheduleDTO trialSku = buildSku(materialCode, "STRUCT-1", "T");
        context.setStructureSkuMap(new LinkedHashMap<String, java.util.List<SkuScheduleDTO>>());
        context.getStructureSkuMap().put("STRUCT-1",
                new ArrayList<SkuScheduleDTO>(Arrays.asList(normalSku, trialSku)));

        context.setMachineOnlineInfoMap(new LinkedHashMap<String, LhMachineOnlineInfo>());
        context.getMachineOnlineInfoMap().put("K1101", buildOnlineInfo("K1101", materialCode, "S"));
        context.setMachineScheduleMap(new LinkedHashMap<String, MachineScheduleDTO>());
        context.getMachineScheduleMap().put("K1101", buildMachine("K1101", "1"));
        context.getMachineScheduleMap().get("K1101").setCurrentMaterialCode(materialCode);
        context.getRollingInheritedScheduleResultList().add(
                buildInheritedResult("K1101", materialCode, "T", "0"));

        ReflectionTestUtils.invokeMethod(handler, "classifyContinuousAndNewSkus", context);

        assertEquals(1, context.getContinuousSkuList().size());
        assertEquals("T", context.getContinuousSkuList().get(0).getProductStatus());
        assertEquals(1, context.getNewSpecSkuList().size());
        assertEquals("S", context.getNewSpecSkuList().get(0).getProductStatus());
    }

    /**
     * 同物料多状态专用链跨窗口时，最新X/T结果必须还原为正规续作并锁回原机台。
     */
    @Test
    void classifyContinuousAndNewSkus_shouldRestoreFormalCarrierForMarkedSpecialChain() {
        LhScheduleContext context = new LhScheduleContext();
        context.setFactoryCode("116");
        context.setBatchNo("LHPC20260716001");
        context.setRollingScheduleHandoff(true);
        String materialCode = "3302002217";

        SkuScheduleDTO formalSku = buildSku(materialCode, "STRUCT-1", "S");
        SkuScheduleDTO trialSku = buildSku(materialCode, "STRUCT-1", "X");
        trialSku.setSurplusQty(20);
        trialSku.setPendingQty(20);
        trialSku.setTargetScheduleQty(20);
        context.setStructureSkuMap(new LinkedHashMap<String, java.util.List<SkuScheduleDTO>>());
        context.getStructureSkuMap().put("STRUCT-1",
                new ArrayList<SkuScheduleDTO>(Arrays.asList(formalSku, trialSku)));

        context.setMachineOnlineInfoMap(new LinkedHashMap<String, LhMachineOnlineInfo>());
        context.getMachineOnlineInfoMap().put(
                "K1101", buildOnlineInfo("K1101", materialCode, "X"));
        context.setMachineScheduleMap(new LinkedHashMap<String, MachineScheduleDTO>());
        context.getMachineScheduleMap().put("K1101", buildMachine("K1101", "1"));
        context.getMachineScheduleMap().get("K1101").setCurrentMaterialCode(materialCode);
        LhScheduleResult inheritedResult = buildInheritedResult(
                "K1101", materialCode, "X", "1");
        ShiftFieldUtil.appendShiftAnalysis(
                inheritedResult, 1,
                LhScheduleConstant.SAME_MATERIAL_STATUS_CONTINUATION_ANALYSIS);
        context.getRollingInheritedScheduleResultList().add(inheritedResult);

        ReflectionTestUtils.invokeMethod(handler, "classifyContinuousAndNewSkus", context);

        assertEquals(1, context.getContinuousSkuList().size());
        assertEquals("S", context.getContinuousSkuList().get(0).getProductStatus());
        assertEquals("K1101", context.getContinuousSkuList().get(0).getContinuousMachineCode());
        assertEquals(1, context.getNewSpecSkuList().size());
        assertEquals("X", context.getNewSpecSkuList().get(0).getProductStatus());
        assertEquals("K1101",
                context.getNewSpecSkuList().get(0).getPreferredContinuousMachineCode());
    }

    /**
     * 同一物料存在不同产品状态时，MES在机只能承接状态一致的月计划SKU。
     */
    @Test
    void classifyContinuousAndNewSkus_shouldMatchMesSkuByMaterialAndProductStatus() {
        LhScheduleContext context = new LhScheduleContext();
        String materialCode = "3302002217";

        SkuScheduleDTO normalSku = buildSku(materialCode, "STRUCT-1", "S");
        SkuScheduleDTO trialSku = buildSku(materialCode, "STRUCT-1", "T");
        Map<String, java.util.List<SkuScheduleDTO>> structureSkuMap = new LinkedHashMap<>();
        structureSkuMap.put("STRUCT-1", new ArrayList<SkuScheduleDTO>(Arrays.asList(normalSku, trialSku)));
        context.setStructureSkuMap(structureSkuMap);

        context.setMachineOnlineInfoMap(new LinkedHashMap<String, LhMachineOnlineInfo>());
        context.getMachineOnlineInfoMap().put("K1101", buildOnlineInfo("K1101", materialCode, "T"));
        context.setMachineScheduleMap(new LinkedHashMap<String, MachineScheduleDTO>());
        context.getMachineScheduleMap().put("K1101", buildMachine("K1101", "1"));

        ReflectionTestUtils.invokeMethod(handler, "classifyContinuousAndNewSkus", context);

        assertEquals(1, context.getContinuousSkuList().size());
        assertEquals("T", context.getContinuousSkuList().get(0).getProductStatus());
        assertEquals("K1101", context.getContinuousSkuList().get(0).getContinuousMachineCode());
        assertEquals(1, context.getNewSpecSkuList().size());
        assertEquals("S", context.getNewSpecSkuList().get(0).getProductStatus());
    }

    /**
     * MES在机产品状态缺失时，必须按正规S精确承接月计划SKU。
     */
    @Test
    void classifyContinuousAndNewSkus_shouldFallbackToMaterialWhenMesProductStatusMissing() {
        LhScheduleContext context = new LhScheduleContext();
        SkuScheduleDTO sku = buildSku("3302002217", "STRUCT-1", "S");
        Map<String, java.util.List<SkuScheduleDTO>> structureSkuMap = new LinkedHashMap<>();
        structureSkuMap.put("STRUCT-1", new ArrayList<SkuScheduleDTO>(Collections.singletonList(sku)));
        context.setStructureSkuMap(structureSkuMap);

        LhMachineOnlineInfo onlineInfo = buildOnlineInfo("K1101", "3302002217", null);
        context.setMachineOnlineInfoMap(new LinkedHashMap<String, LhMachineOnlineInfo>());
        context.getMachineOnlineInfoMap().put("K1101", onlineInfo);
        context.setMachineScheduleMap(new LinkedHashMap<String, MachineScheduleDTO>());
        context.getMachineScheduleMap().put("K1101", buildMachine("K1101", "1"));

        ReflectionTestUtils.invokeMethod(handler, "classifyContinuousAndNewSkus", context);

        assertEquals(1, context.getContinuousSkuList().size());
        assertEquals("S", context.getContinuousSkuList().get(0).getProductStatus());
        assertTrue(context.getNewSpecSkuList().isEmpty());
    }

    /**
     * 同物料S/T/X并存且两台MES空状态时，两台都必须承接S并共享S账本。
     */
    @Test
    void classifyContinuousAndNewSkus_shouldAssignAllBlankStatusMachinesToFormalSku() {
        LhScheduleContext context = new LhScheduleContext();
        String materialCode = "3302001404";
        SkuScheduleDTO formalSku = buildSku(materialCode, "STRUCT-1", "S");
        formalSku.setDailyPlanQuotaMap(new LinkedHashMap<>());
        SkuScheduleDTO trialSku = buildSku(materialCode, "STRUCT-1", "T");
        SkuScheduleDTO pilotSku = buildSku(materialCode, "STRUCT-1", "X");
        context.setStructureSkuMap(new LinkedHashMap<String, java.util.List<SkuScheduleDTO>>());
        context.getStructureSkuMap().put("STRUCT-1",
                new ArrayList<SkuScheduleDTO>(Arrays.asList(trialSku, pilotSku, formalSku)));
        context.setMachineOnlineInfoMap(new LinkedHashMap<String, LhMachineOnlineInfo>());
        context.getMachineOnlineInfoMap().put("K1210", buildOnlineInfo("K1210", materialCode, null));
        context.getMachineOnlineInfoMap().put("K1610", buildOnlineInfo("K1610", materialCode, " "));
        context.setMachineScheduleMap(new LinkedHashMap<String, MachineScheduleDTO>());
        context.getMachineScheduleMap().put("K1210", buildMachine("K1210", "1"));
        context.getMachineScheduleMap().put("K1610", buildMachine("K1610", "1"));

        ReflectionTestUtils.invokeMethod(handler, "classifyContinuousAndNewSkus", context);

        assertEquals(2, context.getContinuousSkuList().size());
        assertTrue(context.getContinuousSkuList().stream()
                .allMatch(sku -> "S".equals(sku.getProductStatus())));
        assertSame(context.getContinuousSkuList().get(0).getDailyPlanQuotaMap(),
                context.getContinuousSkuList().get(1).getDailyPlanQuotaMap());
        assertEquals(2, context.getNewSpecSkuList().size());
        assertTrue(context.getNewSpecSkuList().stream()
                .anyMatch(sku -> "T".equals(sku.getProductStatus())));
        assertTrue(context.getNewSpecSkuList().stream()
                .anyMatch(sku -> "X".equals(sku.getProductStatus())));
        assertTrue(context.getAllSkuScheduleDtoMap().containsKey(
                MonthPlanDateResolver.buildMaterialStatusKey(materialCode, "S")));
        assertTrue(context.getAllSkuScheduleDtoMap().containsKey(
                MonthPlanDateResolver.buildMaterialStatusKey(materialCode, "T")));
        assertTrue(context.getAllSkuScheduleDtoMap().containsKey(
                MonthPlanDateResolver.buildMaterialStatusKey(materialCode, "X")));
    }

    /**
     * 未知状态记录在前时，不得抢占后续能精确匹配的产品状态SKU。
     */
    @Test
    void classifyContinuousAndNewSkus_shouldReserveExactStatusBeforeMaterialFallback() {
        LhScheduleContext context = new LhScheduleContext();
        String materialCode = "3302001586";
        SkuScheduleDTO formalSku = buildSku(materialCode, "STRUCT-1", "S");
        SkuScheduleDTO trialSku = buildSku(materialCode, "STRUCT-1", "T");
        context.setStructureSkuMap(new LinkedHashMap<String, java.util.List<SkuScheduleDTO>>());
        context.getStructureSkuMap().put("STRUCT-1",
                new ArrayList<SkuScheduleDTO>(Arrays.asList(trialSku, formalSku)));
        context.setMachineOnlineInfoMap(new LinkedHashMap<String, LhMachineOnlineInfo>());
        context.getMachineOnlineInfoMap().put("K1101", buildOnlineInfo("K1101", materialCode, "UNKNOWN"));
        context.getMachineOnlineInfoMap().put("K1102", buildOnlineInfo("K1102", materialCode, "T"));
        context.setMachineScheduleMap(new LinkedHashMap<String, MachineScheduleDTO>());
        context.getMachineScheduleMap().put("K1101", buildMachine("K1101", "1"));
        context.getMachineScheduleMap().put("K1102", buildMachine("K1102", "1"));

        ReflectionTestUtils.invokeMethod(handler, "classifyContinuousAndNewSkus", context);

        assertEquals(2, context.getContinuousSkuList().size());
        assertTrue(context.getContinuousSkuList().stream()
                .anyMatch(sku -> "K1102".equals(sku.getContinuousMachineCode())
                        && "T".equals(sku.getProductStatus())));
        assertTrue(context.getContinuousSkuList().stream()
                .anyMatch(sku -> "K1101".equals(sku.getContinuousMachineCode())
                        && "S".equals(sku.getProductStatus())));
    }

    /**
     * MES在机产品状态在月计划中不存在时，降级按物料编码承接归集顺序中的首个SKU。
     */
    @Test
    void classifyContinuousAndNewSkus_shouldFallbackToMaterialWhenProductStatusNotMatched() {
        LhScheduleContext context = new LhScheduleContext();
        String materialCode = "3302002217";
        SkuScheduleDTO normalSku = buildSku(materialCode, "STRUCT-1", "S");
        context.setStructureSkuMap(new LinkedHashMap<String, java.util.List<SkuScheduleDTO>>());
        context.getStructureSkuMap().put("STRUCT-1",
                new ArrayList<SkuScheduleDTO>(Collections.singletonList(normalSku)));

        context.setMachineOnlineInfoMap(new LinkedHashMap<String, LhMachineOnlineInfo>());
        context.getMachineOnlineInfoMap().put("K1101", buildOnlineInfo("K1101", materialCode, "X"));
        context.setMachineScheduleMap(new LinkedHashMap<String, MachineScheduleDTO>());
        context.getMachineScheduleMap().put("K1101", buildMachine("K1101", "1"));

        ReflectionTestUtils.invokeMethod(handler, "classifyContinuousAndNewSkus", context);

        assertEquals(1, context.getContinuousSkuList().size());
        assertEquals("S", context.getContinuousSkuList().get(0).getProductStatus());
        assertTrue(context.getNewSpecSkuList().isEmpty());
    }

    @Test
    void copySkuForContinuousMachine_shouldKeepEffectiveLastMonthOverdueQty() {
        SkuScheduleDTO source = buildSku("3302002637", "285/70R19.5");
        source.setEffectiveLastMonthOverdueQty(18);

        SkuScheduleDTO copy = ReflectionTestUtils.invokeMethod(
                handler, "copySkuForContinuousMachine", source, "K2010");

        assertEquals(18, copy.getEffectiveLastMonthOverdueQty());
    }

    @Test
    void classifyContinuousAndNewSkus_shouldWriteUnscheduledWhenOnlyFuturePlanExistsEvenWithLastMonthShortage() {
        LhScheduleContext context = buildClassificationContext();
        SkuScheduleDTO sku = buildSku("3302002637", "285/70R19.5");
        sku.setEmbryoCode("EMBRYO-2637");
        sku.setWindowPlanQty(0);
        sku.setFutureMonthPlanQtyAfterWindow(62);
        sku.setMonthlyHistoryShortageQty(0);
        sku.setEffectiveLastMonthOverdueQty(1);
        sku.setSurplusQty(276);
        sku.setTargetScheduleQty(144);
        context.getStructureSkuMap().put(sku.getStructureName(),
                new ArrayList<SkuScheduleDTO>(Arrays.asList(sku)));
        context.getActiveEmbryoSkuMap().put(sku.getEmbryoCode(),
                new ArrayList<String>(Arrays.asList(MonthPlanDateResolver.buildMaterialStatusKey(
                        sku.getMaterialCode(), sku.getProductStatus()))));

        ReflectionTestUtils.invokeMethod(handler, "classifyContinuousAndNewSkus", context);

        assertEquals(0, context.getNewSpecSkuList().size());
        assertEquals(1, context.getUnscheduledResultList().size());
        assertEquals(0, context.getUnscheduledResultList().get(0).getUnscheduledQty().intValue());
        assertEquals("当前排程窗口无日计划，后续远期有计划，禁止提前消耗未来计划",
                context.getUnscheduledResultList().get(0).getUnscheduledReason());
        assertTrue(context.getStructureSkuMap().isEmpty());
        assertTrue(context.getActiveEmbryoSkuMap().isEmpty());
        assertEquals(1, context.getScheduleLogList().size());
    }

    @Test
    void classifyContinuousAndNewSkus_shouldKeepNewSkuWhenCurrentMonthShortageExists() {
        assertWindowNoPlanSkuKeptAsNew(10, 0, 62, 0);
    }

    @Test
    void classifyContinuousAndNewSkus_shouldKeepNewSkuWhenWindowPlanExists() {
        assertWindowNoPlanSkuKeptAsNew(0, 0, 62, 18);
    }

    @Test
    void classifyContinuousAndNewSkus_shouldKeepOverallEndingSkuWhenNoFuturePlanExists() {
        assertWindowNoPlanSkuKeptAsNew(0, 0, 0, 0);
    }

    /**
     * 试制SKU在排程及可提前生产范围内无日计划时，必须写未排并彻底移出后置索引。
     */
    @Test
    void classifyContinuousAndNewSkus_shouldRemoveTrialSkuWhenAdmissionRangeHasNoPlan() {
        LhScheduleContext context = buildClassificationContext();
        context.setScheduleDate(dateTime(2026, 7, 1, 0, 0));
        context.setScheduleTargetDate(dateTime(2026, 7, 3, 0, 0));
        context.setWindowEndDate(dateTime(2026, 7, 3, 0, 0));
        SkuScheduleDTO trialSku = buildSku("3302003001", "STRUCT-TRIAL", "X");
        trialSku.setConstructionStage(ConstructionStageEnum.TRIAL.getCode());
        trialSku.setWindowPlanQty(0);
        trialSku.setSurplusQty(60);
        trialSku.setTargetScheduleQty(60);
        trialSku.setEmbryoCode("EMBRYO-TRIAL");
        context.getStructureSkuMap().put(trialSku.getStructureName(),
                new ArrayList<SkuScheduleDTO>(Collections.singletonList(trialSku)));
        context.getActiveEmbryoSkuMap().put(trialSku.getEmbryoCode(),
                new ArrayList<String>(Collections.singletonList(
                        MonthPlanDateResolver.buildMaterialStatusKey(
                                trialSku.getMaterialCode(), trialSku.getProductStatus()))));

        ReflectionTestUtils.invokeMethod(handler, "classifyContinuousAndNewSkus", context);

        assertTrue(context.getNewSpecSkuList().isEmpty());
        assertTrue(context.getStructureSkuMap().isEmpty());
        assertTrue(context.getActiveEmbryoSkuMap().isEmpty());
        assertTrue(context.getAllSkuScheduleDtoMap().isEmpty());
        assertEquals(0, trialSku.resolveTargetScheduleQty());
        assertEquals(1, context.getUnscheduledResultList().size());
        assertEquals(PendingSkuUnscheduledRule.TRIAL_DAILY_PLAN_UNSCHEDULED_REASON,
                context.getUnscheduledResultList().get(0).getUnscheduledReason());
    }

    /**
     * 已按物料和产品状态识别为续作的试制SKU，不执行新增SKU日计划未排规则。
     */
    @Test
    void classifyContinuousAndNewSkus_shouldKeepTrialContinuousSku() {
        LhScheduleContext context = buildClassificationContext();
        SkuScheduleDTO trialSku = buildSku("3302003002", "STRUCT-TRIAL", "X");
        trialSku.setConstructionStage(ConstructionStageEnum.TRIAL.getCode());
        context.getStructureSkuMap().put(trialSku.getStructureName(),
                new ArrayList<SkuScheduleDTO>(Collections.singletonList(trialSku)));
        context.getMachineOnlineInfoMap().put("K1101", buildOnlineInfo("K1101", trialSku.getMaterialCode(), "X"));
        context.getMachineScheduleMap().put("K1101", buildMachine("K1101", "1"));

        ReflectionTestUtils.invokeMethod(handler, "classifyContinuousAndNewSkus", context);

        assertEquals(1, context.getContinuousSkuList().size());
        assertEquals("3302003002", context.getContinuousSkuList().get(0).getMaterialCode());
        assertTrue(context.getNewSpecSkuList().isEmpty());
        assertTrue(context.getUnscheduledResultList().isEmpty());
    }

    /**
     * 试制、量试SKU在提前范围内存在计划时，放行后的新增列表必须保持原遍历顺序。
     */
    @Test
    void classifyContinuousAndNewSkus_shouldKeepOriginalOrderWhenTrialSkusAreAllowed() {
        LhScheduleContext context = buildClassificationContext();
        context.setScheduleDate(dateTime(2026, 7, 1, 0, 0));
        context.setScheduleTargetDate(dateTime(2026, 7, 3, 0, 0));
        context.setWindowEndDate(dateTime(2026, 7, 3, 0, 0));
        SkuScheduleDTO trialSku = buildSku("3302003003", "STRUCT-ORDER", "X");
        trialSku.setConstructionStage(ConstructionStageEnum.TRIAL.getCode());
        attachFutureQuota(trialSku, LocalDate.of(2026, 7, 5), 20);
        SkuScheduleDTO formalSku = buildSku("3302003004", "STRUCT-ORDER", "S");
        formalSku.setConstructionStage(ConstructionStageEnum.FORMAL.getCode());
        SkuScheduleDTO massTrialSku = buildSku("3302003005", "STRUCT-ORDER", "T");
        massTrialSku.setConstructionStage(ConstructionStageEnum.MASS_TRIAL.getCode());
        attachFutureQuota(massTrialSku, LocalDate.of(2026, 7, 4), 20);
        context.getStructureSkuMap().put("STRUCT-ORDER",
                new ArrayList<SkuScheduleDTO>(Arrays.asList(trialSku, formalSku, massTrialSku)));

        ReflectionTestUtils.invokeMethod(handler, "classifyContinuousAndNewSkus", context);

        assertEquals(3, context.getNewSpecSkuList().size());
        assertSame(trialSku, context.getNewSpecSkuList().get(0));
        assertSame(formalSku, context.getNewSpecSkuList().get(1));
        assertSame(massTrialSku, context.getNewSpecSkuList().get(2));
    }

    private void assertWindowNoPlanSkuKeptAsNew(int historyShortageQty,
                                                 int lastMonthOverdueQty,
                                                 int futurePlanQty,
                                                 int windowPlanQty) {
        LhScheduleContext context = buildClassificationContext();
        SkuScheduleDTO sku = buildSku("MAT-BOUNDARY", "STRUCT-BOUNDARY");
        sku.setWindowPlanQty(windowPlanQty);
        sku.setFutureMonthPlanQtyAfterWindow(futurePlanQty);
        sku.setMonthlyHistoryShortageQty(historyShortageQty);
        sku.setEffectiveLastMonthOverdueQty(lastMonthOverdueQty);
        sku.setSurplusQty(100);
        sku.setTargetScheduleQty(100);
        context.getStructureSkuMap().put(sku.getStructureName(),
                new ArrayList<SkuScheduleDTO>(Arrays.asList(sku)));

        ReflectionTestUtils.invokeMethod(handler, "classifyContinuousAndNewSkus", context);

        assertEquals(1, context.getNewSpecSkuList().size());
        assertEquals(0, context.getUnscheduledResultList().size());
    }

    private LhScheduleContext buildClassificationContext() {
        LhScheduleContext context = new LhScheduleContext();
        Date scheduleDate = new Date();
        context.setFactoryCode("116");
        context.setBatchNo("LHPC-CLASSIFY-TEST");
        context.setScheduleTargetDate(scheduleDate);
        context.setScheduleDate(scheduleDate);
        context.setWindowEndDate(scheduleDate);
        context.setStructureSkuMap(new LinkedHashMap<String, java.util.List<SkuScheduleDTO>>());
        context.setMachineOnlineInfoMap(new LinkedHashMap<String, LhMachineOnlineInfo>());
        context.setMachineScheduleMap(new LinkedHashMap<String, MachineScheduleDTO>());
        return context;
    }

    private LhMachineOnlineInfo buildOnlineInfo(String machineCode, String materialCode) {
        return buildOnlineInfo(machineCode, materialCode, "S");
    }

    private LhMachineOnlineInfo buildOnlineInfo(String machineCode,
                                                String materialCode,
                                                String productStatus) {
        LhMachineOnlineInfo onlineInfo = new LhMachineOnlineInfo();
        onlineInfo.setLhCode(machineCode);
        onlineInfo.setMaterialCode(materialCode);
        onlineInfo.setProductStatus(productStatus);
        return onlineInfo;
    }

    private SkuScheduleDTO buildSku(String materialCode, String structureName) {
        return buildSku(materialCode, structureName, "S");
    }

    private SkuScheduleDTO buildSku(String materialCode, String structureName, String productStatus) {
        SkuScheduleDTO sku = new SkuScheduleDTO();
        sku.setMaterialCode(materialCode);
        sku.setMaterialDesc("测试物料-" + materialCode);
        sku.setStructureName(structureName);
        sku.setProductStatus(productStatus);
        return sku;
    }

    /**
     * 为SKU附加窗口结束后的原始日计划额度。
     *
     * @param sku SKU排程信息
     * @param productionDate 未来计划日期
     * @param dayPlanQty 日计划量
     */
    private void attachFutureQuota(SkuScheduleDTO sku, LocalDate productionDate, int dayPlanQty) {
        Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap =
                new LinkedHashMap<LocalDate, SkuDailyPlanQuotaDTO>(4);
        SkuDailyPlanQuotaDTO quota = new SkuDailyPlanQuotaDTO();
        quota.setProductionDate(productionDate);
        quota.setDayPlanQty(dayPlanQty);
        quota.setRemainingQty(dayPlanQty);
        quotaMap.put(productionDate, quota);
        sku.setDailyPlanQuotaMap(quotaMap);
    }

    private MachineScheduleDTO buildMachine(String machineCode, String status) {
        MachineScheduleDTO machine = new MachineScheduleDTO();
        machine.setMachineCode(machineCode);
        machine.setStatus(status);
        return machine;
    }

    private LhScheduleResult buildInheritedResult(String machineCode, String materialCode, String isEnd) {
        return buildInheritedResult(machineCode, materialCode, "S", isEnd);
    }

    private LhScheduleResult buildInheritedResult(String machineCode,
                                                  String materialCode,
                                                  String productStatus,
                                                  String isEnd) {
        LhScheduleResult result = new LhScheduleResult();
        result.setLhMachineCode(machineCode);
        result.setMaterialCode(materialCode);
        result.setProductStatus(productStatus);
        result.setIsEnd(isEnd);
        result.setSpecEndTime(new Date());
        result.setRollingInherited(true);
        return result;
    }

    private Date dateTime(int year, int month, int day, int hour, int minute) {
        Calendar calendar = Calendar.getInstance();
        calendar.clear();
        calendar.set(year, month - 1, day, hour, minute, 0);
        return calendar.getTime();
    }
}
