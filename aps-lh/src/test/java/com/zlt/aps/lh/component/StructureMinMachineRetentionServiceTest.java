package com.zlt.aps.lh.component;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.zlt.aps.lh.api.domain.dto.MachineScheduleDTO;
import com.zlt.aps.lh.api.domain.dto.SkuScheduleDTO;
import com.zlt.aps.lh.api.domain.entity.LhScheduleResult;
import com.zlt.aps.lh.api.domain.vo.LhShiftConfigVO;
import com.zlt.aps.lh.context.LhScheduleConfig;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.context.EmbryoStockConsumeLedger;
import com.zlt.aps.maindata.mapper.FactoryParamMapper;
import com.zlt.aps.maindata.mapper.MdmMonCycleSchStruConfEntityMapper;
import com.zlt.aps.lh.util.LhScheduleTimeUtil;
import com.zlt.aps.lh.util.ShiftFieldUtil;
import com.zlt.aps.mp.api.domain.entity.MdmMonCycleSchStruConf;
import com.zlt.aps.mp.api.domain.entity.FactoryParam;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 结构收尾最低机台数保留规则测试。
 *
 * <p>覆盖周期/常规配置读取、最晚有量班次统计、计划量0占位、单控物理机台口径、
 * 临时候选保护、统一释放、全结构标识和重复执行幂等性。</p>
 *
 * @author APS
 */
public class StructureMinMachineRetentionServiceTest {

    private StructureMinMachineRetentionService service;

    @BeforeEach
    public void setUp() {
        service = new StructureMinMachineRetentionService();
    }

    /** 周期结构按分厂、年月、结构和来源类型01读取MIN_VULCANIZING_MACHINE。 */
    @Test
    public void shouldReadCycleStructureMinimumMachineCountFromLocalMapper() {
        MdmMonCycleSchStruConfEntityMapper cycleMapper = mock(MdmMonCycleSchStruConfEntityMapper.class);
        MdmMonCycleSchStruConf config = new MdmMonCycleSchStruConf();
        config.setMinVulcanizingMachine(4);
        when(cycleMapper.selectList(any())).thenReturn(Arrays.asList(config));
        ReflectionTestUtils.setField(service, "cycleStructureConfigMapper", cycleMapper);

        LhScheduleContext context = baseContext();
        SkuScheduleDTO sku = structureSku("S1", "01", 2026, 7);

        int minimumMachineCount = service.resolveMinimumMachineCount(
                context, "S1", Arrays.asList(sku));

        Assertions.assertEquals(4, minimumMachineCount);
        /*
         * 不只验证返回值，还直接核对本地Mapper查询条件，防止实体字段调整后仍误查旧来源列。
         * 参数值同时覆盖分厂、年月、结构和SOURCE_TYPE=01，确保周期配置维度与业务口径一致。
         */
        LambdaQueryWrapper<MdmMonCycleSchStruConf> wrapper =
                captureCycleStructureConfigWrapper(cycleMapper);
        Assertions.assertTrue(wrapper.getSqlSegment().toUpperCase().contains("SOURCE_TYPE"));
        Assertions.assertTrue(wrapper.getParamNameValuePairs().containsValue("116"));
        Assertions.assertTrue(wrapper.getParamNameValuePairs().containsValue(2026));
        Assertions.assertTrue(wrapper.getParamNameValuePairs().containsValue(7));
        Assertions.assertTrue(wrapper.getParamNameValuePairs().containsValue("S1"));
        Assertions.assertTrue(wrapper.getParamNameValuePairs().containsValue("01"));
    }

    /** 常规结构按分厂和SYS0204012读取并解析最低机台数。 */
    @Test
    public void shouldReadRegularStructureMinimumMachineCountFromLocalMapper() {
        FactoryParamMapper factoryParamMapper = mock(FactoryParamMapper.class);
        FactoryParam param = new FactoryParam();
        param.setParamValue(" 5 ");
        when(factoryParamMapper.selectList(any())).thenReturn(Arrays.asList(param));
        ReflectionTestUtils.setField(service, "factoryParamMapper", factoryParamMapper);

        LhScheduleContext context = baseContext();
        SkuScheduleDTO sku = structureSku("S1", "02", 2026, 7);

        int minimumMachineCount = service.resolveMinimumMachineCount(
                context, "S1", Arrays.asList(sku));

        Assertions.assertEquals(5, minimumMachineCount);
    }

    /** 最晚班次机台数小于最低值时，在提前收尾机台原结果行补计划量0、时间和备注。 */
    @Test
    public void shouldFillZeroPlaceholderOnOriginalResultWhenLatestShiftMachineCountBelowMinimum() {
        LhScheduleContext context = retentionContext(4);
        LhScheduleResult machine1 = plannedResult(context, "K1001", 1, 6, 10);
        LhScheduleResult machine2 = plannedResult(context, "K1002", 1, 6, 10);
        LhScheduleResult machine3 = plannedResult(context, "K1003", 1, 2, 10);
        context.getScheduleResultList().addAll(Arrays.asList(machine1, machine2, machine3));
        registerMachine(context, "K1001");
        registerMachine(context, "K1002");
        registerMachine(context, "K1003");
        int originalResultCount = context.getScheduleResultList().size();

        service.refreshRetention(context);

        Assertions.assertEquals(originalResultCount, context.getScheduleResultList().size());
        for (int shiftIndex = 3; shiftIndex <= 6; shiftIndex++) {
            LhShiftConfigVO shift = context.getScheduleWindowShifts().get(shiftIndex - 1);
            Assertions.assertEquals(0, ShiftFieldUtil.getShiftPlanQty(machine3, shiftIndex));
            Assertions.assertEquals(shift.getShiftStartDateTime(),
                    ShiftFieldUtil.getShiftStartTime(machine3, shiftIndex));
            Assertions.assertEquals(shift.getShiftEndDateTime(),
                    ShiftFieldUtil.getShiftEndTime(machine3, shiftIndex));
            Assertions.assertEquals(StructureMinMachineRetentionService.RETENTION_ANALYSIS,
                    ShiftFieldUtil.getShiftAnalysis(machine3, shiftIndex));
        }
        Assertions.assertEquals("1", machine1.getIsStructureMinMachineRetained());
        Assertions.assertEquals("1", machine2.getIsStructureMinMachineRetained());
        Assertions.assertEquals("1", machine3.getIsStructureMinMachineRetained());
        Assertions.assertEquals(context.getScheduleWindowShifts().get(5).getShiftEndDateTime(),
                context.getStructureMinMachineRetentionEndTimeMap().get("K1003"));
    }

    /** 最晚班次实际机台数等于最低值时不触发占位和延迟释放。 */
    @Test
    public void shouldNotRetainWhenLatestShiftMachineCountEqualsMinimum() {
        LhScheduleContext context = retentionContext(2);
        LhScheduleResult machine1 = plannedResult(context, "K1001", 1, 6, 10);
        LhScheduleResult machine2 = plannedResult(context, "K1002", 1, 6, 10);
        LhScheduleResult machine3 = plannedResult(context, "K1003", 1, 2, 10);
        context.getScheduleResultList().addAll(Arrays.asList(machine1, machine2, machine3));

        service.refreshRetention(context);

        Assertions.assertNull(ShiftFieldUtil.getShiftPlanQty(machine3, 3));
        Assertions.assertEquals("0", machine1.getIsStructureMinMachineRetained());
        Assertions.assertTrue(context.getStructureMinMachineRetentionEndTimeMap().isEmpty());
    }

    /** 计划量0不参与最晚班次、生产机台数、产量及运行态数量账本统计。 */
    @Test
    public void shouldExcludeZeroPlanFromStatisticsAndQuantityLedgers() {
        LhScheduleContext context = retentionContext(4);
        LhScheduleResult machine1 = plannedResult(context, "K1001", 1, 6, 10);
        LhScheduleResult machine2 = plannedResult(context, "K1002", 1, 6, 10);
        LhScheduleResult machine3 = plannedResult(context, "K1003", 1, 2, 10);
        LhScheduleResult zeroOnlyMachine = plannedResult(context, "K1004", -1, -1, 0);
        LhShiftConfigVO shift8 = context.getScheduleWindowShifts().get(7);
        ShiftFieldUtil.setShiftPlanQty(zeroOnlyMachine, 8, 0,
                shift8.getShiftStartDateTime(), shift8.getShiftEndDateTime());
        context.getScheduleResultList().addAll(Arrays.asList(
                machine1, machine2, machine3, zeroOnlyMachine));
        context.getSkuProductionRemainingQtyMap().put("SKU1_S", 100);
        EmbryoStockConsumeLedger embryoLedger = new EmbryoStockConsumeLedger();
        embryoLedger.setEmbryoCode("EMB-01");
        embryoLedger.setTargetQty(100);
        embryoLedger.setConsumedQty(5);
        embryoLedger.setRemainQty(95);
        context.getEmbryoStockConsumeLedgerMap().put("EMB-01_2026-07-20", embryoLedger);
        int originalScheduledQty = totalScheduledQty(context.getScheduleResultList());
        int originalRemainingQty = context.getSkuProductionRemainingQtyMap().get("SKU1_S");
        int originalEmbryoConsumedQty = embryoLedger.getConsumedQty();
        int originalEmbryoRemainQty = embryoLedger.getRemainQty();

        service.refreshRetention(context);

        Assertions.assertEquals(originalScheduledQty, totalScheduledQty(context.getScheduleResultList()));
        Assertions.assertEquals(originalRemainingQty,
                context.getSkuProductionRemainingQtyMap().get("SKU1_S"));
        Assertions.assertEquals(originalEmbryoConsumedQty, embryoLedger.getConsumedQty());
        Assertions.assertEquals(originalEmbryoRemainQty, embryoLedger.getRemainQty());
        Assertions.assertFalse(context.getStructureMinMachineRetentionEndTimeMap().containsKey("K1004"));
        Assertions.assertEquals(6, ShiftFieldUtil.resolveLastPlannedShiftIndex(machine1));
        Assertions.assertEquals(-1, ShiftFieldUtil.resolveLastPlannedShiftIndex(zeroOnlyMachine));
    }

    /** 结构未完成时机台不能被其它结构选择，完成后按最晚班次结束时间释放。 */
    @Test
    public void shouldProtectMachineUntilStructureFinalReleaseTime() {
        LhScheduleContext context = retentionContext(4);
        SkuScheduleDTO pendingSku = structureSku("S1", "02", 2026, 7);
        context.getStructureSkuMap().put("S1", Arrays.asList(pendingSku));
        context.getScheduleResultList().add(plannedResult(context, "K1003", 1, 2, 10));
        registerMachine(context, "K1003");

        service.refreshRetention(context);

        Assertions.assertTrue(context.isEndingStructureProtectedMachine("K1003"));

        context.getStructureSkuMap().clear();
        service.refreshRetention(context);

        Assertions.assertFalse(context.isEndingStructureProtectedMachine("K1003"));
        Assertions.assertEquals(context.getScheduleWindowShifts().get(1).getShiftEndDateTime(),
                context.getStructureMinMachineRetentionEndTimeMap().get("K1003"));
        Assertions.assertEquals(context.getScheduleWindowShifts().get(1).getShiftEndDateTime(),
                context.getMachineScheduleMap().get("K1003").getEstimatedEndTime());
    }

    /** 窗口末班生产机台数已达最低值时，应提前确认不命中并释放临时保护。 */
    @Test
    public void shouldConfirmNonRetentionEarlyWhenWindowLastShiftAlreadyMeetsMinimum() {
        LhScheduleContext context = retentionContext(4);
        SkuScheduleDTO pendingSku = structureSku("S1", "02", 2026, 7);
        context.getStructureSkuMap().put("S1", Arrays.asList(pendingSku));
        LhScheduleResult machine1 = plannedResult(context, "K1001", 1, 8, 10);
        LhScheduleResult machine2 = plannedResult(context, "K1002", 1, 8, 10);
        LhScheduleResult machine3 = plannedResult(context, "K1003", 1, 8, 10);
        LhScheduleResult machine4 = plannedResult(context, "K1004", 1, 8, 10);
        LhScheduleResult earlyMachine = plannedResult(context, "K1005", 1, 2, 10);
        context.getScheduleResultList().addAll(Arrays.asList(
                machine1, machine2, machine3, machine4, earlyMachine));

        service.refreshRetention(context);

        Assertions.assertTrue(context.getStructureMinMachineConfirmedNonRetainedStructureSet().contains("S1"));
        Assertions.assertTrue(context.getEndingStructureProtectedMachineMap().isEmpty(),
                "末班机台数已经达标时，不得继续拦截正常换活字块、历史反选和新增选机");
        Assertions.assertNull(ShiftFieldUtil.getShiftPlanQty(earlyMachine, 3),
                "提前确认未命中只释放临时保护，不得补计划量0占位");
        Assertions.assertTrue(context.getStructureMinMachineRetentionEndTimeMap().isEmpty());
        for (LhScheduleResult result : context.getScheduleResultList()) {
            Assertions.assertEquals("0", result.getIsStructureMinMachineRetained());
        }

        // 重复刷新仍应保持提前未命中状态，不能重新把已释放机台加入临时保护。
        service.refreshRetention(context);
        Assertions.assertTrue(context.getEndingStructureProtectedMachineMap().isEmpty());
    }

    /** 单控L/R按物理整机去重；命中结构的正常、收尾和占位结果全部标记为1。 */
    @Test
    public void shouldCountSingleControlSidesAsOnePhysicalMachineAndMarkAllResults() {
        LhScheduleContext context = retentionContext(2);
        LhScheduleResult left = plannedResult(context, "K1501L", 1, 6, 10);
        LhScheduleResult right = plannedResult(context, "K1501R", 1, 6, 10);
        LhScheduleResult early = plannedResult(context, "K1502L", 1, 2, 10);
        context.getScheduleResultList().addAll(Arrays.asList(left, right, early));

        service.refreshRetention(context);

        Assertions.assertEquals("1", left.getIsStructureMinMachineRetained());
        Assertions.assertEquals("1", right.getIsStructureMinMachineRetained());
        Assertions.assertEquals("1", early.getIsStructureMinMachineRetained());
        Assertions.assertEquals(0, ShiftFieldUtil.getShiftPlanQty(early, 6));
    }

    /** 重复刷新不新增结果行、不重复占位备注，也不改变实际排产量。 */
    @Test
    public void shouldBeIdempotentWithoutDuplicateResultOrAnalysis() {
        LhScheduleContext context = retentionContext(4);
        LhScheduleResult machine1 = plannedResult(context, "K1001", 1, 6, 10);
        LhScheduleResult machine2 = plannedResult(context, "K1002", 1, 6, 10);
        LhScheduleResult machine3 = plannedResult(context, "K1003", 1, 2, 10);
        context.getScheduleResultList().addAll(Arrays.asList(machine1, machine2, machine3));
        int originalResultCount = context.getScheduleResultList().size();
        int originalScheduledQty = totalScheduledQty(context.getScheduleResultList());

        service.refreshRetention(context);
        service.refreshRetention(context);

        Assertions.assertEquals(originalResultCount, context.getScheduleResultList().size());
        Assertions.assertEquals(originalScheduledQty, totalScheduledQty(context.getScheduleResultList()));
        Assertions.assertEquals(StructureMinMachineRetentionService.RETENTION_ANALYSIS,
                ShiftFieldUtil.getShiftAnalysis(machine3, 3));
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
     * 构建可直接执行最终保留判断的上下文。
     *
     * @param minimumMachineCount 结构最低机台数
     * @return 排程上下文
     */
    private LhScheduleContext retentionContext(int minimumMachineCount) {
        LhScheduleContext context = baseContext();
        SkuScheduleDTO sku = structureSku("S1", "02", 2026, 7);
        context.getCurrentWindowEndingStructureSkuMap().put("S1", Arrays.asList(sku));
        context.getStructureMinVulcanizingMachineMap().put("S1", minimumMachineCount);
        context.setStructureSkuMap(new LinkedHashMap<String, List<SkuScheduleDTO>>(0));
        return context;
    }

    /**
     * 构建结构SKU。
     *
     * @param structureName 结构名称
     * @param structureType 结构类型
     * @param year 月计划年
     * @param month 月计划月
     * @return SKU
     */
    private SkuScheduleDTO structureSku(String structureName, String structureType, int year, int month) {
        SkuScheduleDTO sku = new SkuScheduleDTO();
        sku.setMaterialCode("SKU1");
        sku.setProductStatus("S");
        sku.setStructureName(structureName);
        sku.setStructureType(structureType);
        sku.setMonthPlanYear(year);
        sku.setMonthPlanMonth(month);
        return sku;
    }

    /**
     * 构建结构结果并写入连续有量班次。
     *
     * @param context 排程上下文
     * @param machineCode 机台编码
     * @param firstShift 首个有量班次；小于1表示无有量班次
     * @param lastShift 最后有量班次
     * @param qty 每班计划量
     * @return 排程结果
     */
    private LhScheduleResult plannedResult(LhScheduleContext context,
                                           String machineCode,
                                           int firstShift,
                                           int lastShift,
                                           int qty) {
        LhScheduleResult result = new LhScheduleResult();
        result.setLhMachineCode(machineCode);
        result.setMaterialCode("SKU1");
        result.setProductStatus("S");
        result.setStructureName("S1");
        result.setDailyPlanQty(0);
        if (firstShift >= 1 && lastShift >= firstShift) {
            int dailyPlanQty = 0;
            for (int shiftIndex = firstShift; shiftIndex <= lastShift; shiftIndex++) {
                LhShiftConfigVO shift = context.getScheduleWindowShifts().get(shiftIndex - 1);
                ShiftFieldUtil.setShiftPlanQty(result, shiftIndex, qty,
                        shift.getShiftStartDateTime(), shift.getShiftEndDateTime());
                dailyPlanQty += qty;
            }
            result.setDailyPlanQty(dailyPlanQty);
        }
        return result;
    }

    /**
     * 注册测试机台。
     *
     * @param context 排程上下文
     * @param machineCode 机台编码
     */
    private void registerMachine(LhScheduleContext context, String machineCode) {
        MachineScheduleDTO machine = new MachineScheduleDTO();
        machine.setMachineCode(machineCode);
        context.getMachineScheduleMap().put(machineCode, machine);
    }

    /**
     * 汇总全部结果的实际计划量，计划量0自然不增加汇总值。
     *
     * @param results 结果列表
     * @return 实际计划量
     */
    private int totalScheduledQty(List<LhScheduleResult> results) {
        int totalQty = 0;
        for (LhScheduleResult result : results) {
            totalQty += ShiftFieldUtil.resolveScheduledQty(result);
        }
        return totalQty;
    }

    /**
     * 捕获周期结构配置本地Mapper的查询条件。
     *
     * @param cycleMapper 周期结构配置Mapper
     * @return 周期结构配置查询条件
     */
    @SuppressWarnings("unchecked")
    private LambdaQueryWrapper<MdmMonCycleSchStruConf> captureCycleStructureConfigWrapper(
            MdmMonCycleSchStruConfEntityMapper cycleMapper) {
        initializeTableInfo(MdmMonCycleSchStruConf.class);
        ArgumentCaptor<LambdaQueryWrapper> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(cycleMapper).selectList(captor.capture());
        return (LambdaQueryWrapper<MdmMonCycleSchStruConf>) captor.getValue();
    }

    /**
     * 初始化实体表信息，避免非Spring单测解析Lambda查询字段时缺少列缓存。
     *
     * @param entityClass 实体类型
     */
    private void initializeTableInfo(Class<?> entityClass) {
        if (Objects.nonNull(TableInfoHelper.getTableInfo(entityClass))) {
            return;
        }
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(
                new MybatisConfiguration(), entityClass.getName());
        TableInfoHelper.initTableInfo(assistant, entityClass);
    }

}
