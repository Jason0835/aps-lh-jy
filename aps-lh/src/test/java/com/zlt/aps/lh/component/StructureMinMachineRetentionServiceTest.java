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
 * 结构收尾停产保机阶段级规则测试。
 *
 * <p>重点覆盖结构最晚正量班次、业务停机仍算在机、结构统一补零、前物料结构比较、
 * 同结构接管去重、单控物理机台去重以及数量账本隔离。</p>
 *
 * @author APS
 */
public class StructureMinMachineRetentionServiceTest {

    private StructureMinMachineRetentionService service;

    @BeforeEach
    public void setUp() {
        service = new StructureMinMachineRetentionService();
    }

    /** 周期结构按分厂、年月、结构和来源01读取最低硫化机台数。 */
    @Test
    public void shouldReadCycleStructureMinimumMachineCountFromLocalMapper() {
        MdmMonCycleSchStruConfEntityMapper cycleMapper =
                mock(MdmMonCycleSchStruConfEntityMapper.class);
        MdmMonCycleSchStruConf config = new MdmMonCycleSchStruConf();
        config.setMinVulcanizingMachine(4);
        when(cycleMapper.selectList(any())).thenReturn(Arrays.asList(config));
        ReflectionTestUtils.setField(service, "cycleStructureConfigMapper", cycleMapper);

        int minimumMachineCount = service.resolveMinimumMachineCount(
                baseContext(), "S1",
                Arrays.asList(structureSku("SKU1", "S1", "01")));

        Assertions.assertEquals(4, minimumMachineCount);
        LambdaQueryWrapper<MdmMonCycleSchStruConf> wrapper =
                captureCycleStructureConfigWrapper(cycleMapper);
        Assertions.assertTrue(wrapper.getSqlSegment().toUpperCase().contains("SOURCE_TYPE"));
        Assertions.assertTrue(wrapper.getParamNameValuePairs().containsValue("116"));
        Assertions.assertTrue(wrapper.getParamNameValuePairs().containsValue(2026));
        Assertions.assertTrue(wrapper.getParamNameValuePairs().containsValue(7));
        Assertions.assertTrue(wrapper.getParamNameValuePairs().containsValue("S1"));
        Assertions.assertTrue(wrapper.getParamNameValuePairs().containsValue("01"));
    }

    /** 常规结构按分厂和SYS0204012读取最低硫化机台数。 */
    @Test
    public void shouldReadRegularStructureMinimumMachineCountFromLocalMapper() {
        FactoryParamMapper factoryParamMapper = mock(FactoryParamMapper.class);
        FactoryParam param = new FactoryParam();
        param.setParamValue(" 5 ");
        when(factoryParamMapper.selectList(any())).thenReturn(Arrays.asList(param));
        ReflectionTestUtils.setField(service, "factoryParamMapper", factoryParamMapper);

        int minimumMachineCount = service.resolveMinimumMachineCount(
                baseContext(), "S1",
                Arrays.asList(structureSku("SKU1", "S1", "02")));

        Assertions.assertEquals(5, minimumMachineCount);
    }

    /** 常规结构参数非法时跳过结构，不使用默认最低机台数。 */
    @Test
    public void shouldSkipRegularStructureWhenParameterIsInvalid() {
        FactoryParamMapper factoryParamMapper = mock(FactoryParamMapper.class);
        FactoryParam param = new FactoryParam();
        param.setParamValue("非法数值");
        when(factoryParamMapper.selectList(any())).thenReturn(Arrays.asList(param));
        ReflectionTestUtils.setField(service, "factoryParamMapper", factoryParamMapper);

        int minimumMachineCount = service.resolveMinimumMachineCount(
                baseContext(), "S1",
                Arrays.asList(structureSku("SKU1", "S1", "02")));

        Assertions.assertEquals(-1, minimumMachineCount);
    }

    /** 不使用3天收尾准入，结构分组中的有效结构均初始化配置。 */
    @Test
    public void shouldInitializeAllStructuresWithoutThreeDayEndingGate() {
        FactoryParamMapper factoryParamMapper = mock(FactoryParamMapper.class);
        FactoryParam param = new FactoryParam();
        param.setParamValue("2");
        when(factoryParamMapper.selectList(any())).thenReturn(Arrays.asList(param));
        ReflectionTestUtils.setField(service, "factoryParamMapper", factoryParamMapper);
        LhScheduleContext context = baseContext();
        context.getStructureSkuMap().put(
                "S1", Arrays.asList(structureSku("SKU1", "S1", "02")));

        service.initializeStructureMinimumMachineConfigs(context);

        Assertions.assertEquals(1,
                context.getStructureMinMachineSkuSnapshotMap().size());
        Assertions.assertEquals(2,
                context.getStructureMinVulcanizingMachineMap().get("S1"));
    }

    /** 最晚班次在机数小于最低值时，提前收尾机台补零到结构最晚班并统一标记。 */
    @Test
    public void shouldRetainEarlyFinishedMachineToStructureLatestPositiveShift() {
        LhScheduleContext context = retentionContext(4);
        LhScheduleResult earlyResult =
                plannedResult(context, "K1001", "SKU1", "S1", 1, 2, 10);
        LhScheduleResult latestResult =
                plannedResult(context, "K1002", "SKU1", "S1", 1, 6, 10);
        context.getScheduleResultList().addAll(
                Arrays.asList(earlyResult, latestResult));
        MachineScheduleDTO earlyMachine =
                registerMachine(context, "K1001", "SKU1");
        registerMachine(context, "K1002", "SKU1");
        context.registerContinuousReducedMachineReleaseBoundary("K1001", 2);

        service.applyRetentionAfterContinuousAndTypeBlock(context);

        Date retentionEndTime = shift(context, 6).getShiftEndDateTime();
        Assertions.assertEquals(0,
                ShiftFieldUtil.getShiftPlanQty(earlyResult, 3));
        Assertions.assertEquals(0,
                ShiftFieldUtil.getShiftPlanQty(earlyResult, 6));
        Assertions.assertEquals(
                StructureMinMachineRetentionService.RETENTION_ANALYSIS,
                ShiftFieldUtil.getShiftAnalysis(earlyResult, 4));
        Assertions.assertEquals("1",
                earlyResult.getIsStructureMinMachineRetained());
        Assertions.assertEquals("1",
                latestResult.getIsStructureMinMachineRetained());
        Assertions.assertEquals(retentionEndTime,
                earlyMachine.getEstimatedEndTime());
        Assertions.assertEquals("SKU1",
                context.getStructureMinMachineRetentionPreMaterialMap()
                        .get("K1001"));
        Assertions.assertEquals("S1",
                context.getStructureMinMachineRetentionPreStructureMap()
                        .get("K1001"));
        Assertions.assertNull(
                context.getContinuousReducedMachineReleaseBoundaryShiftIndex("K1001"));
    }

    /** 最晚班次在机数等于最低值时不触发停产保机。 */
    @Test
    public void shouldNotRetainWhenLatestShiftMachineCountEqualsMinimum() {
        LhScheduleContext context = retentionContext(2);
        LhScheduleResult first =
                plannedResult(context, "K1001", "SKU1", "S1", 1, 6, 10);
        LhScheduleResult second =
                plannedResult(context, "K1002", "SKU1", "S1", 2, 6, 10);
        context.getScheduleResultList().addAll(Arrays.asList(first, second));
        registerMachine(context, "K1001", "SKU1");
        registerMachine(context, "K1002", "SKU1");

        service.applyRetentionAfterContinuousAndTypeBlock(context);

        Assertions.assertTrue(
                context.getStructureMinMachineRetentionEndTimeMap().isEmpty());
        Assertions.assertEquals("0",
                first.getIsStructureMinMachineRetained());
        Assertions.assertEquals("0",
                second.getIsStructureMinMachineRetained());
    }

    /** 最低机台数配置为0时合法但不触发保机。 */
    @Test
    public void shouldNotRetainWhenMinimumMachineCountIsZero() {
        LhScheduleContext context = retentionContext(0);
        LhScheduleResult result =
                plannedResult(context, "K1001", "SKU1", "S1", 1, 2, 10);
        context.getScheduleResultList().add(result);
        registerMachine(context, "K1001", "SKU1");

        service.applyRetentionAfterContinuousAndTypeBlock(context);

        Assertions.assertTrue(
                context.getStructureMinMachineRetentionEndTimeMap().isEmpty());
        Assertions.assertEquals("0",
                result.getIsStructureMinMachineRetained());
    }

    /** 清洗、精度和计划性维修的零量或空量班次均计为在机。 */
    @Test
    public void shouldCountCleaningPrecisionAndPlannedRepairAsInMachine() {
        LhScheduleContext context = retentionContext(4);
        LhScheduleResult running =
                plannedResult(context, "K1001", "SKU1", "S1", 1, 4, 10);
        LhScheduleResult cleaning =
                plannedResult(context, "K1002", "SKU1", "S1", 1, 1, 10);
        LhScheduleResult precision =
                plannedResult(context, "K1003", "SKU1", "S1", 1, 1, 10);
        LhScheduleResult repair =
                plannedResult(context, "K1004", "SKU1", "S1", 1, 1, 10);
        context.getScheduleResultList().addAll(
                Arrays.asList(running, cleaning, precision, repair));
        registerMachine(context, "K1001", "SKU1");
        MachineScheduleDTO cleaningMachine =
                registerMachine(context, "K1002", "SKU1");
        MachineScheduleDTO precisionMachine =
                registerMachine(context, "K1003", "SKU1");
        registerMachine(context, "K1004", "SKU1");
        LhShiftConfigVO targetShift = shift(context, 4);
        addCleaningWindow(cleaningMachine, targetShift);
        addPrecisionWindow(precisionMachine, targetShift);
        addPlannedRepair(context, "K1004", targetShift);

        Set<String> inMachineCodes =
                service.collectStructureInMachinePhysicalCodes(context, "S1", 4);
        service.applyRetentionAfterContinuousAndTypeBlock(context);

        Assertions.assertEquals(4, inMachineCodes.size());
        Assertions.assertTrue(
                context.getStructureMinMachineRetentionEndTimeMap().isEmpty());
    }

    /** 阶段重置旧运行态后，已有结构停产保机零量占用仍必须参与本次在机统计。 */
    @Test
    public void shouldCountExistingRetentionZeroPlaceholderDuringPhaseDecision() {
        LhScheduleContext context = retentionContext(2);
        LhScheduleResult retainedResult =
                plannedResult(context, "K1001", "SKU1", "S1", 1, 1, 10);
        LhScheduleResult latestResult =
                plannedResult(context, "K1002", "SKU1", "S1", 1, 4, 10);
        for (int shiftIndex = 2; shiftIndex <= 4; shiftIndex++) {
            setZeroPlan(context, retainedResult, shiftIndex);
            ShiftFieldUtil.appendShiftAnalysis(
                    retainedResult, shiftIndex,
                    StructureMinMachineRetentionService.RETENTION_ANALYSIS);
        }
        retainedResult.setIsStructureMinMachineRetained("1");
        context.getScheduleResultList().addAll(
                Arrays.asList(retainedResult, latestResult));
        registerMachine(context, "K1001", "SKU1");
        registerMachine(context, "K1002", "SKU1");
        context.getStructureMinMachineRetentionEndTimeMap()
                .put("K1001", shift(context, 4).getShiftEndDateTime());

        service.applyRetentionAfterContinuousAndTypeBlock(context);

        Assertions.assertTrue(
                context.getStructureMinMachineRetentionEndTimeMap().isEmpty());
        Assertions.assertEquals("0", retainedResult.getIsStructureMinMachineRetained());
        Assertions.assertEquals("0", latestResult.getIsStructureMinMachineRetained());
    }

    /** 同结构SKU提前接管，清理旧占位并把剩余占位转移到新结果。 */
    @Test
    public void shouldAllowSameStructureAndTransferRetentionPlaceholder() {
        LhScheduleContext context = retentionContext(3);
        LhScheduleResult oldResult =
                plannedResult(context, "K1001", "SKU1", "S1", 1, 2, 10);
        LhScheduleResult latestResult =
                plannedResult(context, "K1002", "SKU1", "S1", 1, 6, 10);
        context.getScheduleResultList().addAll(
                Arrays.asList(oldResult, latestResult));
        registerMachine(context, "K1001", "SKU1");
        registerMachine(context, "K1002", "SKU1");
        service.applyRetentionAfterContinuousAndTypeBlock(context);
        SkuScheduleDTO targetSku = structureSku("SKU2", "S1", "02");

        Assertions.assertFalse(
                service.isDifferentStructureRetentionBlocked(
                        context, targetSku, "K1001",
                        shift(context, 3).getShiftEndDateTime()));
        Assertions.assertEquals(
                shift(context, 2).getShiftEndDateTime(),
                service.resolveRetentionAwareOccupationEndTime(
                        context, targetSku, "K1001",
                        shift(context, 6).getShiftEndDateTime()));

        LhScheduleResult newResult =
                plannedResult(context, "K1001", "SKU2", "S1", 3, 4, 8);
        context.getScheduleResultList().add(newResult);
        service.synchronizeRetainedState(context);

        Assertions.assertNull(
                ShiftFieldUtil.getShiftPlanQty(oldResult, 3));
        Assertions.assertNull(
                ShiftFieldUtil.getShiftPlanQty(oldResult, 4));
        Assertions.assertEquals(0,
                ShiftFieldUtil.getShiftPlanQty(newResult, 5));
        Assertions.assertEquals(0,
                ShiftFieldUtil.getShiftPlanQty(newResult, 6));
        Assertions.assertEquals("SKU2",
                context.getStructureMinMachineRetentionPreMaterialMap()
                        .get("K1001"));

        // Handler、换活字块和特殊材料阶段都可能再次同步；重复调用不得把前物料回滚为SKU1。
        service.synchronizeRetainedState(context);
        Assertions.assertEquals("SKU2",
                context.getStructureMinMachineRetentionPreMaterialMap()
                        .get("K1001"));
        Assertions.assertNull(
                ShiftFieldUtil.getShiftPlanQty(oldResult, 3));
        Assertions.assertEquals(0,
                ShiftFieldUtil.getShiftPlanQty(newResult, 5));
    }

    /** 不同结构SKU在统一释放前被拦截，到期后恢复可用。 */
    @Test
    public void shouldBlockDifferentStructureUntilRetentionEnds() {
        LhScheduleContext context = retentionContext(3);
        LhScheduleResult early =
                plannedResult(context, "K1001", "SKU1", "S1", 1, 2, 10);
        LhScheduleResult latest =
                plannedResult(context, "K1002", "SKU1", "S1", 1, 6, 10);
        context.getScheduleResultList().addAll(Arrays.asList(early, latest));
        registerMachine(context, "K1001", "SKU1");
        registerMachine(context, "K1002", "SKU1");
        service.applyRetentionAfterContinuousAndTypeBlock(context);
        SkuScheduleDTO differentStructureSku =
                structureSku("SKU9", "S9", "02");
        Date retentionEndTime = shift(context, 6).getShiftEndDateTime();

        Assertions.assertTrue(
                service.isDifferentStructureRetentionBlocked(
                        context, differentStructureSku, "K1001",
                        retentionEndTime));
        Assertions.assertFalse(
                service.isDifferentStructureRetentionBlocked(
                        context, differentStructureSku, "K1001",
                        new Date(retentionEndTime.getTime() + 1L)));
    }

    /** 不同结构到期接管后必须解除运行态保机限制，后续原结构不能回退到旧前物料结束时间。 */
    @Test
    public void shouldReleaseRetentionRuntimeStateAfterDifferentStructureHandoff() {
        LhScheduleContext context = retentionContext(3);
        LhScheduleResult earlyResult =
                plannedResult(context, "K1001", "SKU1", "S1", 1, 2, 10);
        LhScheduleResult latestResult =
                plannedResult(context, "K1002", "SKU1", "S1", 1, 6, 10);
        context.getScheduleResultList().addAll(Arrays.asList(earlyResult, latestResult));
        registerMachine(context, "K1001", "SKU1");
        registerMachine(context, "K1002", "SKU1");
        service.applyRetentionAfterContinuousAndTypeBlock(context);

        Date retentionEndTime = shift(context, 6).getShiftEndDateTime();
        Date laterOccupationEndTime = shift(context, 8).getShiftEndDateTime();
        Assertions.assertEquals(laterOccupationEndTime,
                service.resolveRetentionAwareOccupationEndTime(
                        context, structureSku("SKU3", "S1", "02"), "K1001",
                        laterOccupationEndTime));

        LhScheduleResult differentStructureResult =
                plannedResult(context, "K1001", "SKU9", "S9", 7, 8, 10);
        context.getScheduleResultList().add(differentStructureResult);
        registerMachine(context, "K1001", "SKU9");
        service.synchronizeRetainedState(context);

        Assertions.assertFalse(
                context.getStructureMinMachineRetentionEndTimeMap().containsKey("K1001"));
        Assertions.assertFalse(
                context.getStructureMinMachineRetentionPreMaterialMap().containsKey("K1001"));
        Assertions.assertFalse(
                context.getStructureMinMachineRetentionPreStructureMap().containsKey("K1001"));
        Assertions.assertEquals(laterOccupationEndTime,
                service.resolveRetentionAwareOccupationEndTime(
                        context, structureSku("SKU3", "S1", "02"), "K1001",
                        laterOccupationEndTime));
    }

    /** 单控L/R按物理整机去重，补零不会修改产量和胎胚账本。 */
    @Test
    public void shouldDeduplicateSingleControlAndKeepQuantityLedgerUnchanged() {
        LhScheduleContext context = retentionContext(3);
        LhScheduleResult left =
                plannedResult(context, "K1501L", "SKU1", "S1", 1, 2, 10);
        LhScheduleResult right =
                plannedResult(context, "K1501R", "SKU1", "S1", 1, 4, 10);
        LhScheduleResult other =
                plannedResult(context, "K1502L", "SKU1", "S1", 1, 4, 10);
        context.getScheduleResultList().addAll(Arrays.asList(left, right, other));
        registerMachine(context, "K1501L", "SKU1");
        registerMachine(context, "K1501R", "SKU1");
        registerMachine(context, "K1502L", "SKU1");
        EmbryoStockConsumeLedger ledger = new EmbryoStockConsumeLedger();
        ledger.setConsumedQty(5);
        ledger.setRemainQty(95);
        context.getEmbryoStockConsumeLedgerMap()
                .put("EMB-01_2026-07-20", ledger);
        int originalResultCount = context.getScheduleResultList().size();
        int originalScheduledQty =
                totalScheduledQty(context.getScheduleResultList());

        Set<String> physicalCodes =
                service.collectStructureInMachinePhysicalCodes(context, "S1", 4);
        service.applyRetentionAfterContinuousAndTypeBlock(context);

        Assertions.assertEquals(2, physicalCodes.size());
        Assertions.assertEquals(originalResultCount,
                context.getScheduleResultList().size());
        Assertions.assertEquals(originalScheduledQty,
                totalScheduledQty(context.getScheduleResultList()));
        Assertions.assertEquals(5, ledger.getConsumedQty());
        Assertions.assertEquals(95, ledger.getRemainQty());
    }

    /** 没有正量班次时不存在结构最晚实际生产班次，因此不触发保机。 */
    @Test
    public void shouldSkipStructureWithoutPositivePlan() {
        LhScheduleContext context = retentionContext(2);
        LhScheduleResult zeroResult =
                plannedResult(context, "K1001", "SKU1", "S1", 1, 2, 0);
        context.getScheduleResultList().add(zeroResult);
        registerMachine(context, "K1001", "SKU1");

        service.applyRetentionAfterContinuousAndTypeBlock(context);

        Assertions.assertTrue(
                context.getStructureMinMachineRetentionEndTimeMap().isEmpty());
        Assertions.assertEquals("0",
                zeroResult.getIsStructureMinMachineRetained());
    }

    /** 构建基础排程上下文。 */
    private LhScheduleContext baseContext() {
        LhScheduleContext context = new LhScheduleContext();
        context.setFactoryCode("116");
        context.setBatchNo("STRUCTURE-RETENTION-TEST");
        Date scheduleDate = Date.from(LocalDate.of(2026, 7, 20)
                .atStartOfDay(ZoneId.systemDefault()).toInstant());
        context.setScheduleDate(scheduleDate);
        context.setScheduleTargetDate(scheduleDate);
        context.setScheduleConfig(
                new LhScheduleConfig(new HashMap<String, String>(0)));
        context.setScheduleWindowShifts(
                LhScheduleTimeUtil.buildDefaultScheduleShifts(
                        context, scheduleDate));
        return context;
    }

    /** 构建已初始化最低机台配置的上下文。 */
    private LhScheduleContext retentionContext(int minimumMachineCount) {
        LhScheduleContext context = baseContext();
        SkuScheduleDTO sku = structureSku("SKU1", "S1", "02");
        context.getStructureMinMachineSkuSnapshotMap()
                .put("S1", Arrays.asList(sku));
        context.getStructureMinVulcanizingMachineMap()
                .put("S1", minimumMachineCount);
        context.setStructureSkuMap(
                new LinkedHashMap<String, List<SkuScheduleDTO>>(0));
        return context;
    }

    /** 构建结构SKU。 */
    private SkuScheduleDTO structureSku(String materialCode,
                                        String structureName,
                                        String structureType) {
        SkuScheduleDTO sku = new SkuScheduleDTO();
        sku.setMaterialCode(materialCode);
        sku.setProductStatus("S");
        sku.setStructureName(structureName);
        sku.setStructureType(structureType);
        sku.setMonthPlanYear(2026);
        sku.setMonthPlanMonth(7);
        return sku;
    }

    /** 构建指定班次范围的结果。 */
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
        for (int shiftIndex = firstShift;
             shiftIndex <= lastShift; shiftIndex++) {
            LhShiftConfigVO shift = shift(context, shiftIndex);
            ShiftFieldUtil.setShiftPlanQty(
                    result, shiftIndex, qty,
                    shift.getShiftStartDateTime(),
                    shift.getShiftEndDateTime());
        }
        ShiftFieldUtil.syncDailyPlanQty(result);
        return result;
    }

    /** 设置零量占位班次。 */
    private void setZeroPlan(LhScheduleContext context,
                             LhScheduleResult result,
                             int shiftIndex) {
        LhShiftConfigVO shift = shift(context, shiftIndex);
        ShiftFieldUtil.setShiftPlanQty(
                result, shiftIndex, 0,
                shift.getShiftStartDateTime(),
                shift.getShiftEndDateTime());
        ShiftFieldUtil.syncDailyPlanQty(result);
    }

    /** 注册机台与当前物料关系。 */
    private MachineScheduleDTO registerMachine(LhScheduleContext context,
                                               String machineCode,
                                               String materialCode) {
        MachineScheduleDTO machine = new MachineScheduleDTO();
        machine.setMachineCode(machineCode);
        machine.setCurrentMaterialCode(materialCode);
        context.getMachineScheduleMap().put(machineCode, machine);
        return machine;
    }

    /** 增加覆盖目标班次的清洗窗口。 */
    private void addCleaningWindow(MachineScheduleDTO machine,
                                   LhShiftConfigVO shift) {
        MachineCleaningWindowDTO window = new MachineCleaningWindowDTO();
        window.setCleanStartTime(shift.getShiftStartDateTime());
        window.setCleanEndTime(shift.getShiftEndDateTime());
        machine.getCleaningWindowList().add(window);
    }

    /** 增加覆盖目标班次的精度计划窗口。 */
    private void addPrecisionWindow(MachineScheduleDTO machine,
                                    LhShiftConfigVO shift) {
        MachineMaintenanceWindowDTO window =
                new MachineMaintenanceWindowDTO();
        window.setMachineCode(machine.getMachineCode());
        window.setMaintenanceStartTime(shift.getShiftStartDateTime());
        window.setMaintenanceEndTime(shift.getShiftEndDateTime());
        machine.getMaintenanceWindowList().add(window);
    }

    /** 增加覆盖目标班次的05计划性维修。 */
    private void addPlannedRepair(LhScheduleContext context,
                                  String machineCode,
                                  LhShiftConfigVO shift) {
        MdmDevicePlanShut repair = new MdmDevicePlanShut();
        repair.setMachineCode(machineCode);
        repair.setMachineStopType("05");
        repair.setBeginDate(shift.getShiftStartDateTime());
        repair.setEndDate(shift.getShiftEndDateTime());
        context.getDevicePlanShutList().add(repair);
    }

    /** 读取测试班次。 */
    private LhShiftConfigVO shift(LhScheduleContext context,
                                  int shiftIndex) {
        return context.getScheduleWindowShifts().get(shiftIndex - 1);
    }

    /** 汇总实际计划量。 */
    private int totalScheduledQty(List<LhScheduleResult> results) {
        int totalQty = 0;
        for (LhScheduleResult result : results) {
            totalQty += ShiftFieldUtil.resolveScheduledQty(result);
        }
        return totalQty;
    }

    /** 捕获周期结构配置查询条件。 */
    @SuppressWarnings("unchecked")
    private LambdaQueryWrapper<MdmMonCycleSchStruConf>
            captureCycleStructureConfigWrapper(
            MdmMonCycleSchStruConfEntityMapper cycleMapper) {
        initializeTableInfo(MdmMonCycleSchStruConf.class);
        ArgumentCaptor<LambdaQueryWrapper> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(cycleMapper).selectList(captor.capture());
        return (LambdaQueryWrapper<MdmMonCycleSchStruConf>)
                captor.getValue();
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
