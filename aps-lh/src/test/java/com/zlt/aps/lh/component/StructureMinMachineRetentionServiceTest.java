package com.zlt.aps.lh.component;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.zlt.aps.lh.api.domain.dto.MachineCleaningWindowDTO;
import com.zlt.aps.lh.api.domain.dto.MachineMaintenanceWindowDTO;
import com.zlt.aps.lh.api.domain.dto.MachineScheduleDTO;
import com.zlt.aps.lh.api.domain.dto.SkuScheduleDTO;
import com.zlt.aps.lh.api.domain.entity.LhScheduleResult;
import com.zlt.aps.lh.api.domain.vo.LhShiftConfigVO;
import com.zlt.aps.lh.context.EmbryoStockConsumeLedger;
import com.zlt.aps.lh.context.LhScheduleConfig;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.util.LhScheduleTimeUtil;
import com.zlt.aps.lh.util.ShiftFieldUtil;
import com.zlt.aps.maindata.mapper.FactoryParamMapper;
import com.zlt.aps.maindata.mapper.MdmMonCycleSchStruConfEntityMapper;
import com.zlt.aps.mdm.api.domain.entity.MdmDevicePlanShut;
import com.zlt.aps.mp.api.domain.entity.FactoryParam;
import com.zlt.aps.mp.api.domain.entity.MdmMonCycleSchStruConf;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 结构最低机台数实时下机规则测试。
 *
 * <p>覆盖全结构配置初始化、下机后等于最低值放行、清洗/精度/计划性维修零量在机、真实释放排除、
 * 保机零量占位与状态顺延、连续下机实时重算、单控物理机台去重和数量账本隔离。</p>
 *
 * @author APS
 */
public class StructureMinMachineRetentionServiceTest {

    private StructureMinMachineRetentionService service;

    @BeforeEach
    public void setUp() {
        service = new StructureMinMachineRetentionService();
    }

    /** 周期结构继续按分厂、年月、结构和来源类型01读取最低硫化机台数。 */
    @Test
    public void shouldReadCycleStructureMinimumMachineCountFromLocalMapper() {
        MdmMonCycleSchStruConfEntityMapper cycleMapper = mock(MdmMonCycleSchStruConfEntityMapper.class);
        MdmMonCycleSchStruConf config = new MdmMonCycleSchStruConf();
        config.setMinVulcanizingMachine(4);
        when(cycleMapper.selectList(any())).thenReturn(Arrays.asList(config));
        ReflectionTestUtils.setField(service, "cycleStructureConfigMapper", cycleMapper);

        LhScheduleContext context = baseContext();
        int minimumMachineCount = service.resolveMinimumMachineCount(
                context, "S1", Arrays.asList(structureSku("SKU1", "S1", "01")));

        Assertions.assertEquals(4, minimumMachineCount);
        LambdaQueryWrapper<MdmMonCycleSchStruConf> wrapper = captureCycleStructureConfigWrapper(cycleMapper);
        Assertions.assertTrue(wrapper.getSqlSegment().toUpperCase().contains("SOURCE_TYPE"));
        Assertions.assertTrue(wrapper.getParamNameValuePairs().containsValue("116"));
        Assertions.assertTrue(wrapper.getParamNameValuePairs().containsValue(2026));
        Assertions.assertTrue(wrapper.getParamNameValuePairs().containsValue(7));
        Assertions.assertTrue(wrapper.getParamNameValuePairs().containsValue("S1"));
        Assertions.assertTrue(wrapper.getParamNameValuePairs().containsValue("01"));
    }

    /** 常规结构继续按分厂和SYS0204012读取最低硫化机台数。 */
    @Test
    public void shouldReadRegularStructureMinimumMachineCountFromLocalMapper() {
        FactoryParamMapper factoryParamMapper = mock(FactoryParamMapper.class);
        FactoryParam param = new FactoryParam();
        param.setParamValue(" 5 ");
        when(factoryParamMapper.selectList(any())).thenReturn(Arrays.asList(param));
        ReflectionTestUtils.setField(service, "factoryParamMapper", factoryParamMapper);

        int minimumMachineCount = service.resolveMinimumMachineCount(
                baseContext(), "S1", Arrays.asList(structureSku("SKU1", "S1", "02")));

        Assertions.assertEquals(5, minimumMachineCount);
    }

    /** 不再调用3天内收尾判断，结构分组中的全部有效结构均初始化最低机台配置。 */
    @Test
    public void shouldInitializeAllStructuresWithoutThreeDayEndingGate() {
        FactoryParamMapper factoryParamMapper = mock(FactoryParamMapper.class);
        FactoryParam param = new FactoryParam();
        param.setParamValue("2");
        when(factoryParamMapper.selectList(any())).thenReturn(Arrays.asList(param));
        ReflectionTestUtils.setField(service, "factoryParamMapper", factoryParamMapper);
        LhScheduleContext context = baseContext();
        context.getStructureSkuMap().put("S1", Arrays.asList(structureSku("SKU1", "S1", "02")));

        service.initializeStructureMinimumMachineConfigs(context);

        Assertions.assertEquals(1, context.getStructureMinMachineSkuSnapshotMap().size());
        Assertions.assertEquals(2, context.getStructureMinVulcanizingMachineMap().get("S1"));
    }

    /** 下机后物理机台数恰好等于最低值时必须正常放行。 */
    @Test
    public void shouldAllowOfflineWhenAfterCountEqualsMinimum() {
        LhScheduleContext context = retentionContext(2);
        LhScheduleResult offlineResult = plannedResult(context, "K1001", "SKU1", "S1", 1, 1, 10);
        LhScheduleResult machine2 = plannedResult(context, "K1002", "SKU1", "S1", 2, 2, 10);
        LhScheduleResult machine3 = plannedResult(context, "K1003", "SKU1", "S1", 2, 2, 10);
        context.getScheduleResultList().addAll(Arrays.asList(offlineResult, machine2, machine3));
        registerMachine(context, "K1001", "SKU1");
        registerMachine(context, "K1002", "SKU1");
        registerMachine(context, "K1003", "SKU1");

        boolean retained = service.retainMachineBeforeOffline(
                context, sourceSku(), offlineResult, 2, 1, "等于最低值测试");

        Assertions.assertFalse(retained);
        Assertions.assertTrue(context.getStructureMinMachineRetentionEndTimeMap().isEmpty());
    }

    /** 清洗导致班次计划量为0时，只要物料关系未解除仍应统计为在机。 */
    @Test
    public void shouldCountZeroPlanCleaningMachineAsInMachine() {
        LhScheduleContext context = retentionContext(1);
        LhScheduleResult result = plannedResult(context, "K1001", "SKU1", "S1", 1, 1, 10);
        setZeroPlan(context, result, 2);
        context.getScheduleResultList().add(result);
        MachineScheduleDTO machine = registerMachine(context, "K1001", "SKU1");
        LhShiftConfigVO shift = shift(context, 2);
        MachineCleaningWindowDTO cleaningWindow = new MachineCleaningWindowDTO();
        cleaningWindow.setCleanStartTime(shift.getShiftStartDateTime());
        cleaningWindow.setCleanEndTime(shift.getShiftEndDateTime());
        machine.getCleaningWindowList().add(cleaningWindow);

        Set<String> machineCodes = service.collectStructureInMachinePhysicalCodes(context, "S1", 2);

        Assertions.assertEquals(1, machineCodes.size());
        Assertions.assertTrue(machineCodes.contains("K1001"));
    }

    /** 精度计划导致班次计划量为空时，只要物料关系未解除仍应统计为在机。 */
    @Test
    public void shouldCountNullPlanPrecisionMachineAsInMachine() {
        LhScheduleContext context = retentionContext(1);
        LhScheduleResult result = plannedResult(context, "K1001", "SKU1", "S1", 1, 1, 10);
        context.getScheduleResultList().add(result);
        MachineScheduleDTO machine = registerMachine(context, "K1001", "SKU1");
        LhShiftConfigVO shift = shift(context, 2);
        MachineMaintenanceWindowDTO precisionWindow = new MachineMaintenanceWindowDTO();
        precisionWindow.setMachineCode("K1001");
        precisionWindow.setMaintenanceStartTime(shift.getShiftStartDateTime());
        precisionWindow.setMaintenanceEndTime(shift.getShiftEndDateTime());
        machine.getMaintenanceWindowList().add(precisionWindow);

        Set<String> machineCodes = service.collectStructureInMachinePhysicalCodes(context, "S1", 2);

        Assertions.assertEquals(1, machineCodes.size());
    }

    /** 计划性维修导致班次计划量为0或空时，只要物料关系未解除仍应统计为在机。 */
    @Test
    public void shouldCountPlannedRepairMachineAsInMachine() {
        LhScheduleContext context = retentionContext(1);
        LhScheduleResult result = plannedResult(context, "K1001", "SKU1", "S1", 1, 1, 10);
        setZeroPlan(context, result, 2);
        context.getScheduleResultList().add(result);
        registerMachine(context, "K1001", "SKU1");
        LhShiftConfigVO shift = shift(context, 2);
        MdmDevicePlanShut repair = new MdmDevicePlanShut();
        repair.setMachineCode("K1001");
        repair.setMachineStopType("05");
        repair.setBeginDate(shift.getShiftStartDateTime());
        repair.setEndDate(shift.getShiftEndDateTime());
        context.getDevicePlanShutList().add(repair);

        Set<String> machineCodes = service.collectStructureInMachinePhysicalCodes(context, "S1", 2);

        Assertions.assertEquals(1, machineCodes.size());
    }

    /** 已登记真实释放边界的机台即使存在停机窗口也不得继续计入。 */
    @Test
    public void shouldExcludeTrulyReleasedMachineFromInMachineCount() {
        LhScheduleContext context = retentionContext(1);
        LhScheduleResult result = plannedResult(context, "K1001", "SKU1", "S1", 1, 1, 10);
        setZeroPlan(context, result, 2);
        context.getScheduleResultList().add(result);
        MachineScheduleDTO machine = registerMachine(context, "K1001", "SKU1");
        LhShiftConfigVO shift = shift(context, 2);
        MachineCleaningWindowDTO cleaningWindow = new MachineCleaningWindowDTO();
        cleaningWindow.setCleanStartTime(shift.getShiftStartDateTime());
        cleaningWindow.setCleanEndTime(shift.getShiftEndDateTime());
        machine.getCleaningWindowList().add(cleaningWindow);
        context.registerContinuousReducedMachineReleaseBoundary("K1001", 1);

        Set<String> machineCodes = service.collectStructureInMachinePhysicalCodes(context, "S1", 2);

        Assertions.assertTrue(machineCodes.isEmpty());
    }

    /** 后物料已在目标班次前接管机台时，即使仍有业务停机窗口也不得计入原结构。 */
    @Test
    public void shouldExcludeMachineTakenOverByLaterMaterial() {
        LhScheduleContext context = retentionContext(1);
        SkuScheduleDTO nextSku = structureSku("SKU2", "S2", "02");
        context.getStructureMinMachineSkuSnapshotMap().put("S2", Arrays.asList(nextSku));
        LhScheduleResult result = plannedResult(context, "K1001", "SKU1", "S1", 1, 1, 10);
        setZeroPlan(context, result, 2);
        context.getScheduleResultList().add(result);
        MachineScheduleDTO machine = registerMachine(context, "K1001", "SKU2");
        LhShiftConfigVO shift = shift(context, 2);
        MachineCleaningWindowDTO cleaningWindow = new MachineCleaningWindowDTO();
        cleaningWindow.setCleanStartTime(shift.getShiftStartDateTime());
        cleaningWindow.setCleanEndTime(shift.getShiftEndDateTime());
        machine.getCleaningWindowList().add(cleaningWindow);

        Set<String> machineCodes = service.collectStructureInMachinePhysicalCodes(context, "S1", 2);

        Assertions.assertTrue(machineCodes.isEmpty());
    }

    /** 命中保机后计划量保持0，结果结束时间、机台可用时间和占用状态统一顺延。 */
    @Test
    public void shouldRetainZeroPlanResultAndDelayMachineOccupation() {
        LhScheduleContext context = retentionContext(2);
        LhScheduleResult offlineResult = plannedResult(context, "K1001", "SKU1", "S1", 1, 1, 10);
        LhScheduleResult runningResult = plannedResult(context, "K1002", "SKU1", "S1", 1, 4, 10);
        setZeroPlan(context, offlineResult, 2);
        context.getScheduleResultList().addAll(Arrays.asList(offlineResult, runningResult));
        MachineScheduleDTO offlineMachine = registerMachine(context, "K1001", "SKU1");
        registerMachine(context, "K1002", "SKU1");

        boolean retained = service.retainMachineBeforeOffline(
                context, sourceSku(), offlineResult, 2, 1, "保机状态顺延测试");

        Date retentionEndTime = shift(context, 4).getShiftEndDateTime();
        Assertions.assertTrue(retained);
        Assertions.assertEquals(0, ShiftFieldUtil.getShiftPlanQty(offlineResult, 2));
        Assertions.assertEquals(0, ShiftFieldUtil.getShiftPlanQty(offlineResult, 4));
        Assertions.assertEquals(StructureMinMachineRetentionService.RETENTION_ANALYSIS,
                ShiftFieldUtil.getShiftAnalysis(offlineResult, 3));
        Assertions.assertEquals(retentionEndTime, offlineResult.getSpecEndTime());
        Assertions.assertEquals(retentionEndTime, offlineResult.getTdaySpecEndTime());
        Assertions.assertEquals(retentionEndTime, offlineMachine.getEstimatedEndTime());
        Assertions.assertTrue(offlineMachine.isEnding());
        Assertions.assertEquals("SKU1", offlineMachine.getCurrentMaterialCode());
        Assertions.assertEquals("1", offlineResult.getIsStructureMinMachineRetained());
    }

    /** 全零结果收口命中后仍保留原结果行，重复判断不新增结果或重复备注。 */
    @Test
    public void shouldKeepAllZeroResultAndRemainIdempotentWhenOfflineClosingHits() {
        LhScheduleContext context = retentionContext(2);
        LhScheduleResult zeroResult = plannedResult(context, "K1001", "SKU1", "S1", 1, 1, 0);
        LhScheduleResult runningResult = plannedResult(context, "K1002", "SKU1", "S1", 1, 3, 10);
        context.getScheduleResultList().addAll(Arrays.asList(zeroResult, runningResult));
        registerMachine(context, "K1001", "SKU1");
        registerMachine(context, "K1002", "SKU1");
        int originalResultCount = context.getScheduleResultList().size();

        boolean firstRetained = service.retainMachineBeforeOffline(
                context, sourceSku(), zeroResult, 1, 1, "全零结果收口");
        boolean secondRetained = service.retainMachineBeforeOffline(
                context, sourceSku(), zeroResult, 1, 1, "全零结果重复收口");
        LhScheduleResult laterResult = plannedResult(context, "K1003", "SKU1", "S1", 3, 3, 10);
        context.getScheduleResultList().add(laterResult);
        service.synchronizeRetainedState(context);

        Assertions.assertTrue(firstRetained);
        Assertions.assertTrue(secondRetained);
        Assertions.assertEquals(originalResultCount + 1, context.getScheduleResultList().size());
        Assertions.assertEquals(0, zeroResult.getDailyPlanQty());
        Assertions.assertEquals("0", zeroResult.getProductionStatus());
        Assertions.assertEquals("0", zeroResult.getIsEnd());
        Assertions.assertEquals(StructureMinMachineRetentionService.RETENTION_ANALYSIS,
                ShiftFieldUtil.getShiftAnalysis(zeroResult, 2));
        Assertions.assertEquals("1", laterResult.getIsStructureMinMachineRetained());
    }

    /** 同一结构连续下机时，每次基于最新释放边界和上次保机状态重新计算。 */
    @Test
    public void shouldRecalculateLatestStateForConsecutiveOfflineActions() {
        LhScheduleContext context = retentionContext(2);
        LhScheduleResult machine1 = plannedResult(context, "K1001", "SKU1", "S1", 1, 2, 10);
        LhScheduleResult machine2 = plannedResult(context, "K1002", "SKU1", "S1", 1, 2, 10);
        LhScheduleResult machine3 = plannedResult(context, "K1003", "SKU1", "S1", 1, 3, 10);
        context.getScheduleResultList().addAll(Arrays.asList(machine1, machine2, machine3));
        registerMachine(context, "K1001", "SKU1");
        registerMachine(context, "K1002", "SKU1");
        registerMachine(context, "K1003", "SKU1");
        setZeroPlan(context, machine1, 2);

        boolean firstRetained = service.retainMachineBeforeOffline(
                context, sourceSku(), machine1, 2, 2, "连续下机-第一次");
        Assertions.assertFalse(firstRetained);
        context.registerContinuousReducedMachineReleaseBoundary("K1001", 1);
        setZeroPlan(context, machine2, 2);

        boolean secondRetained = service.retainMachineBeforeOffline(
                context, sourceSku(), machine2, 2, 2, "连续下机-第二次");

        Assertions.assertTrue(secondRetained);
        Assertions.assertTrue(context.isStructureMinMachineRetained("K1002"));
    }

    /** 单控L/R按物理整机去重，重复保机不新增结果、不重复扣数量账本。 */
    @Test
    public void shouldDeduplicateSingleControlAndKeepIdempotentWithoutQuantitySideEffect() {
        LhScheduleContext context = retentionContext(2);
        LhScheduleResult left = plannedResult(context, "K1501L", "SKU1", "S1", 1, 1, 10);
        LhScheduleResult right = plannedResult(context, "K1501R", "SKU1", "S1", 2, 2, 10);
        LhScheduleResult other = plannedResult(context, "K1502L", "SKU1", "S1", 2, 3, 10);
        context.getScheduleResultList().addAll(Arrays.asList(left, right, other));
        registerMachine(context, "K1501L", "SKU1");
        registerMachine(context, "K1501R", "SKU1");
        registerMachine(context, "K1502L", "SKU1");
        EmbryoStockConsumeLedger ledger = new EmbryoStockConsumeLedger();
        ledger.setConsumedQty(5);
        ledger.setRemainQty(95);
        context.getEmbryoStockConsumeLedgerMap().put("EMB-01_2026-07-20", ledger);
        int originalResultCount = context.getScheduleResultList().size();
        int originalScheduledQty = totalScheduledQty(context.getScheduleResultList());

        Set<String> physicalCodes = service.collectStructureInMachinePhysicalCodes(context, "S1", 2);
        Assertions.assertEquals(2, physicalCodes.size());
        setZeroPlan(context, left, 2);
        boolean retained = service.retainMachineBeforeOffline(
                context, sourceSku(), left, 2, 1, "单控单侧下机");
        service.retainMachineBeforeOffline(context, sourceSku(), left, 2, 1, "单控重复判断");

        Assertions.assertFalse(retained, "配对侧仍在机时，单侧下机不减少物理机台数");
        Assertions.assertEquals(originalResultCount, context.getScheduleResultList().size());
        Assertions.assertEquals(originalScheduledQty, totalScheduledQty(context.getScheduleResultList()));
        Assertions.assertEquals(5, ledger.getConsumedQty());
        Assertions.assertEquals(95, ledger.getRemainQty());
    }

    /**
     * 构建基础排程上下文。
     *
     * @return 带8班窗口的上下文
     */
    private LhScheduleContext baseContext() {
        LhScheduleContext context = new LhScheduleContext();
        context.setFactoryCode("116");
        context.setBatchNo("STRUCTURE-RETENTION-TEST");
        Date scheduleDate = Date.from(LocalDate.of(2026, 7, 20)
                .atStartOfDay(ZoneId.systemDefault()).toInstant());
        context.setScheduleDate(scheduleDate);
        context.setScheduleTargetDate(scheduleDate);
        context.setScheduleConfig(new LhScheduleConfig(new HashMap<String, String>(0)));
        context.setScheduleWindowShifts(LhScheduleTimeUtil.buildDefaultScheduleShifts(context, scheduleDate));
        return context;
    }

    /**
     * 构建已初始化结构配置的上下文。
     *
     * @param minimumMachineCount 最低机台数
     * @return 排程上下文
     */
    private LhScheduleContext retentionContext(int minimumMachineCount) {
        LhScheduleContext context = baseContext();
        SkuScheduleDTO sku = sourceSku();
        context.getStructureMinMachineSkuSnapshotMap().put("S1", Arrays.asList(sku));
        context.getStructureMinVulcanizingMachineMap().put("S1", minimumMachineCount);
        context.setStructureSkuMap(new LinkedHashMap<String, List<SkuScheduleDTO>>(0));
        return context;
    }

    /** 构建规则测试SKU。 */
    private SkuScheduleDTO sourceSku() {
        return structureSku("SKU1", "S1", "02");
    }

    /**
     * 构建结构SKU。
     *
     * @param materialCode 物料编码
     * @param structureName 结构名称
     * @param structureType 结构类型
     * @return SKU
     */
    private SkuScheduleDTO structureSku(String materialCode, String structureName, String structureType) {
        SkuScheduleDTO sku = new SkuScheduleDTO();
        sku.setMaterialCode(materialCode);
        sku.setProductStatus("S");
        sku.setStructureName(structureName);
        sku.setStructureType(structureType);
        sku.setMonthPlanYear(2026);
        sku.setMonthPlanMonth(7);
        return sku;
    }

    /**
     * 构建带连续正量班次的结果。
     */
    private LhScheduleResult plannedResult(LhScheduleContext context,
                                           String machineCode,
                                           String materialCode,
                                           String structureName,
                                           int firstShift,
                                           int lastShift,
                                           int qty) {
        LhScheduleResult result = new LhScheduleResult();
        result.setLhMachineCode(machineCode);
        result.setMaterialCode(materialCode);
        result.setMaterialDesc(materialCode);
        result.setProductStatus("S");
        result.setStructureName(structureName);
        result.setDailyPlanQty(0);
        int dailyPlanQty = 0;
        for (int shiftIndex = firstShift; shiftIndex <= lastShift; shiftIndex++) {
            LhShiftConfigVO shift = shift(context, shiftIndex);
            ShiftFieldUtil.setShiftPlanQty(result, shiftIndex, qty,
                    shift.getShiftStartDateTime(), shift.getShiftEndDateTime());
            dailyPlanQty += qty;
        }
        result.setDailyPlanQty(dailyPlanQty);
        return result;
    }

    /**
     * 将指定班次设置为零量占位并同步结果总量。
     */
    private void setZeroPlan(LhScheduleContext context, LhScheduleResult result, int shiftIndex) {
        LhShiftConfigVO shift = shift(context, shiftIndex);
        ShiftFieldUtil.setShiftPlanQty(result, shiftIndex, 0,
                shift.getShiftStartDateTime(), shift.getShiftEndDateTime());
        ShiftFieldUtil.syncDailyPlanQty(result);
    }

    /**
     * 注册机台及当前物料关系。
     */
    private MachineScheduleDTO registerMachine(LhScheduleContext context,
                                               String machineCode,
                                               String materialCode) {
        MachineScheduleDTO machine = new MachineScheduleDTO();
        machine.setMachineCode(machineCode);
        machine.setCurrentMaterialCode(materialCode);
        context.getMachineScheduleMap().put(machineCode, machine);
        return machine;
    }

    /** 按索引读取测试班次。 */
    private LhShiftConfigVO shift(LhScheduleContext context, int shiftIndex) {
        return context.getScheduleWindowShifts().get(shiftIndex - 1);
    }

    /** 汇总全部实际排产量。 */
    private int totalScheduledQty(List<LhScheduleResult> results) {
        int totalQty = 0;
        for (LhScheduleResult result : results) {
            totalQty += ShiftFieldUtil.resolveScheduledQty(result);
        }
        return totalQty;
    }

    /** 捕获周期结构配置查询条件。 */
    @SuppressWarnings("unchecked")
    private LambdaQueryWrapper<MdmMonCycleSchStruConf> captureCycleStructureConfigWrapper(
            MdmMonCycleSchStruConfEntityMapper cycleMapper) {
        initializeTableInfo(MdmMonCycleSchStruConf.class);
        ArgumentCaptor<LambdaQueryWrapper> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(cycleMapper).selectList(captor.capture());
        return (LambdaQueryWrapper<MdmMonCycleSchStruConf>) captor.getValue();
    }

    /** 初始化实体表信息。 */
    private void initializeTableInfo(Class<?> entityClass) {
        if (Objects.nonNull(TableInfoHelper.getTableInfo(entityClass))) {
            return;
        }
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(
                new MybatisConfiguration(), entityClass.getName());
        TableInfoHelper.initTableInfo(assistant, entityClass);
    }
}
