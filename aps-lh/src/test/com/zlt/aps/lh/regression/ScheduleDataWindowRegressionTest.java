package com.zlt.aps.lh.regression;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.zlt.aps.cx.api.domain.entity.CxStock;
import com.zlt.aps.lh.api.constant.LhScheduleConstant;
import com.zlt.aps.lh.api.constant.LhScheduleParamConstant;
import com.zlt.aps.lh.context.LhScheduleConfig;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.api.domain.entity.LhDayFinishQty;
import com.zlt.aps.lh.api.domain.entity.LhMachineInfo;
import com.zlt.aps.lh.api.domain.entity.LhMachineOnlineInfo;
import com.zlt.aps.lh.api.domain.entity.LhMouldCleanPlan;
import com.zlt.aps.lh.api.domain.entity.LhMouldChangePlan;
import com.zlt.aps.lh.api.domain.entity.LhScheduleResult;
import com.zlt.aps.lh.api.domain.entity.LhSpecialMaterialBom;
import com.zlt.aps.lh.api.enums.DeleteFlagEnum;
import com.zlt.aps.lh.mapper.FactoryMonthPlanProductionFinalResultMapper;
import com.zlt.aps.lh.mapper.LhDayFinishQtyMapper;
import com.zlt.aps.lh.mapper.LhMachineInfoMapper;
import com.zlt.aps.lh.mapper.LhMouldCleanPlanMapper;
import com.zlt.aps.lh.mapper.LhMouldChangePlanEntityMapper;
import com.zlt.aps.lh.mapper.LhScheduleResultMapper;
import com.zlt.aps.lh.mapper.LhSpecialMaterialBomEntityMapper;
import com.zlt.aps.lh.mapper.LhSpecifyMachineMapper;
import com.zlt.aps.lh.mapper.CxStockMapper;
import com.zlt.aps.lh.mapper.LhPrecisionPlanMapper;
import com.zlt.aps.lh.mapper.MdmDevicePlanShutMapper;
import com.zlt.aps.lh.mapper.MdmCapsuleChuckMapper;
import com.zlt.aps.lh.mapper.LhMachineOnlineInfoMapper;
import com.zlt.aps.lh.mapper.LhRepairCapsuleMapper;
import com.zlt.aps.lh.mapper.LhScheFinishQtyMapper;
import com.zlt.aps.lh.mapper.MdmMaterialInfoMapper;
import com.zlt.aps.lh.mapper.MdmModelInfoMapper;
import com.zlt.aps.lh.mapper.MdmMonthSurplusMapper;
import com.zlt.aps.lh.mapper.MdmSkuConstructionRefMapper;
import com.zlt.aps.lh.mapper.MdmSkuLhCapacityMapper;
import com.zlt.aps.lh.mapper.MdmSkuMouldRelMapper;
import com.zlt.aps.lh.mapper.MdmWorkCalendarMapper;
import com.zlt.aps.lh.mapper.MpFactoryProductionVersionMapper;
import com.zlt.aps.lh.service.impl.LhBaseDataServiceImpl;
import com.zlt.aps.mdm.api.domain.entity.MdmDevicePlanShut;
import com.zlt.aps.mdm.api.domain.entity.MdmWorkCalendar;
import com.zlt.aps.mp.api.domain.entity.MpFactoryProductionVersion;
import com.zlt.aps.mp.api.domain.entity.FactoryMonthPlanProductionFinalResult;
import com.zlt.aps.lh.util.LhScheduleTimeUtil;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

/**
 * 排程数据窗口回归：典型目标日 → T 日 → [startDate, endDate) 与日历/清洗/停机查询一致。
 */
@ExtendWith(MockitoExtension.class)
class ScheduleDataWindowRegressionTest {

    @Mock
    private FactoryMonthPlanProductionFinalResultMapper monthPlanMapper;
    @Mock
    private MpFactoryProductionVersionMapper mpFactoryProductionVersionMapper;
    @Mock
    private MdmWorkCalendarMapper workCalendarMapper;
    @Mock
    private MdmSkuLhCapacityMapper skuLhCapacityMapper;
    @Mock
    private MdmDevicePlanShutMapper devicePlanShutMapper;
    @Mock
    private LhSpecialMaterialBomEntityMapper lhSpecialMaterialBomEntityMapper;
    @Mock
    private MdmSkuMouldRelMapper skuMouldRelMapper;
    @Mock
    private MdmModelInfoMapper mdmModelInfoMapper;
    @Mock
    private LhMachineInfoMapper lhMachineInfoMapper;
    @Mock
    private LhMouldCleanPlanMapper lhMouldCleanPlanMapper;
    @Mock
    private LhMouldChangePlanEntityMapper lhMouldChangePlanMapper;
    @Mock
    private MdmMonthSurplusMapper monthSurplusMapper;
    @Mock
    private LhDayFinishQtyMapper lhDayFinishQtyMapper;
    @Mock
    private MdmMaterialInfoMapper mdmMaterialInfoMapper;
    @Mock
    private MdmCapsuleChuckMapper mdmCapsuleChuckMapper;
    @Mock
    private LhMachineOnlineInfoMapper lhMachineOnlineInfoMapper;
    @Mock
    private LhSpecifyMachineMapper lhSpecifyMachineMapper;
    @Mock
    private LhRepairCapsuleMapper lhRepairCapsuleMapper;
    @Mock
    private LhPrecisionPlanMapper lhPrecisionPlanMapper;
    @Mock
    private LhScheduleResultMapper lhScheduleResultMapper;
    @Mock
    private CxStockMapper cxStockMapper;
    @Mock
    private LhScheFinishQtyMapper lhScheFinishQtyMapper;
    @Mock
    private MdmSkuConstructionRefMapper skuConstructionRefMapper;

    @InjectMocks
    private LhBaseDataServiceImpl lhBaseDataService;

    /**
     * 典型例：目标日 2026-04-04 → T=2026-04-02，窗口覆盖 4/2～4/4 三个日历日；校验 T 日与 [start,end) 公式。
     */
    @Test
    void scheduleWindow_targetDayTDayAndHalfOpenEndMatchFormula() {
        Date target = date(2026, 4, 4);
        Date targetClear = LhScheduleTimeUtil.clearTime(target);
        int offsetDays = Math.max(0, LhScheduleConstant.SCHEDULE_DAYS - 1);
        Date tDay = LhScheduleTimeUtil.addDays(targetClear, -offsetDays);

        assertEquals(date(2026, 4, 2), stripTime(tDay), "T 日应为目标日前移 SCHEDULE_DAYS-1 天");

        Date startDate = LhScheduleTimeUtil.clearTime(tDay);
        Date endDate = LhScheduleTimeUtil.addDays(startDate, LhScheduleConstant.SCHEDULE_DAYS);

        assertEquals(date(2026, 4, 2), stripTime(startDate));
        assertEquals(date(2026, 4, 5), stripTime(endDate), "endDate 为 T+SCHEDULE_DAYS 日 0 点，[start,end) 含 T～T+2");

        assertTrue(date(2026, 4, 4).before(endDate), "目标日 4/4 0 点应 < endDate（4/5 0 点）");
        assertTrue(!date(2026, 4, 5).before(endDate), "T+3 日 0 点等于 endDate，productionDate < endDate 不含该日");
    }

    @Test
    void loadAllBaseData_shouldExtendCalendarAndShutWindowButKeepCleaningScheduleWindow() {
        String factoryCode = "FC01";
        Date target = LhScheduleTimeUtil.clearTime(date(2026, 4, 4));
        int offsetDays = Math.max(0, LhScheduleConstant.SCHEDULE_DAYS - 1);
        Date scheduleDate = LhScheduleTimeUtil.addDays(target, -offsetDays);
        Date startDate = LhScheduleTimeUtil.clearTime(scheduleDate);
        Date endDate = LhScheduleTimeUtil.addDays(startDate, LhScheduleConstant.SCHEDULE_DAYS);
        Date controlStartDate = LhScheduleTimeUtil.addDays(startDate, -1);

        prepareRequiredBaseMocks();
        when(lhMachineOnlineInfoMapper.selectList(any())).thenReturn(Collections.emptyList());

        LhScheduleContext context = newScheduleContext();
        context.setFactoryCode(factoryCode);
        context.setScheduleTargetDate(target);
        context.setScheduleDate(scheduleDate);

        lhBaseDataService.loadAllBaseData(context);

        // 喷砂可前移一天，因此工作日历与设备停机需要覆盖 T-1；清洗计划仍只加载当前排程窗口。
        LambdaQueryWrapper<MdmWorkCalendar> workCalendarWrapper = captureWorkCalendarWrapper();
        LambdaQueryWrapper<MdmDevicePlanShut> devicePlanShutWrapper = captureDevicePlanShutWrapper();
        LambdaQueryWrapper<LhMouldCleanPlan> cleaningPlanWrapper = captureCleaningPlanWrapper();
        assertWrapperContainsDate(workCalendarWrapper, controlStartDate);
        assertWrapperContainsDate(workCalendarWrapper, endDate);
        assertWrapperContainsDate(devicePlanShutWrapper, controlStartDate);
        assertWrapperContainsDate(devicePlanShutWrapper, endDate);
        assertWrapperContainsDate(cleaningPlanWrapper, startDate);
        assertWrapperContainsDate(cleaningPlanWrapper, endDate);
        assertWrapperNotContainsDate(cleaningPlanWrapper, controlStartDate);
        verify(lhScheduleResultMapper, times(2)).selectList(any());
    }

    @Test
    void loadAllBaseData_forceRescheduleShouldLoadPreviousDataFromTMinusOne() {
        String factoryCode = "FC01";
        Date target = LhScheduleTimeUtil.clearTime(date(2026, 4, 26));
        Date scheduleDate = LhScheduleTimeUtil.clearTime(date(2026, 4, 24));

        prepareRequiredBaseMocks();
        when(lhMachineOnlineInfoMapper.selectList(any())).thenReturn(Collections.emptyList());

        LhScheduleContext context = newScheduleContext();
        context.setFactoryCode(factoryCode);
        context.setScheduleTargetDate(target);
        context.setScheduleDate(scheduleDate);
        context.getLhParamsMap().put(LhScheduleParamConstant.FORCE_RESCHEDULE, "1");

        lhBaseDataService.loadAllBaseData(context);

        List<LambdaQueryWrapper<LhScheduleResult>> scheduleResultWrappers = captureScheduleResultWrappers();
        assertQueryContainsExpectedDate(scheduleResultWrappers.get(0),
                stripTime(date(2026, 4, 23)), stripTime(date(2026, 4, 25)));
        assertQueryContainsExpectedDate(scheduleResultWrappers.get(1),
                stripTime(date(2026, 4, 25)), stripTime(date(2026, 4, 23)));
        assertQueryContainsExpectedDate(captureMouldChangePlanWrapper(),
                stripTime(date(2026, 4, 23)), stripTime(date(2026, 4, 25)));
    }

    @Test
    void loadAllBaseData_forceRescheduleShouldLoadDayFinishQtyFromTMinusOne() {
        Date target = LhScheduleTimeUtil.clearTime(date(2026, 4, 26));
        Date scheduleDate = LhScheduleTimeUtil.clearTime(date(2026, 4, 24));
        prepareRequiredBaseMocks();
        when(lhMachineOnlineInfoMapper.selectList(any())).thenReturn(Collections.emptyList());

        LhScheduleContext context = newScheduleContext();
        context.setFactoryCode("FC01");
        context.setScheduleTargetDate(target);
        context.setScheduleDate(scheduleDate);
        context.getLhParamsMap().put(LhScheduleParamConstant.FORCE_RESCHEDULE, "1");

        lhBaseDataService.loadAllBaseData(context);

        List<LambdaQueryWrapper<LhDayFinishQty>> wrappers = captureDayFinishQtyWrappers();
        assertQueryContainsDateRange(wrappers.get(0), stripTime(date(2026, 4, 23)), stripTime(date(2026, 4, 24)));
    }

    @Test
    void loadAllBaseData_shouldUseNearestOnlineInfoPerMachineInLookbackWindow() {
        Date target = LhScheduleTimeUtil.clearTime(date(2026, 4, 17));
        Date scheduleDate = LhScheduleTimeUtil.addDays(target, -2);
        prepareRequiredBaseMocks();

        // 模拟查询结果已按 onlineDate/updateTime 倒序；同机台保留第一条即最近记录。
        LhMachineOnlineInfo machineARecent = new LhMachineOnlineInfo();
        machineARecent.setOnlineDate(date(2026, 4, 14));
        machineARecent.setUpdateTime(dateTime(2026, 4, 14, 8, 30, 0));
        machineARecent.setLhCode("K1501");
        machineARecent.setMaterialCode("MAT-A-NEW");

        LhMachineOnlineInfo machineAOld = new LhMachineOnlineInfo();
        machineAOld.setOnlineDate(date(2026, 4, 13));
        machineAOld.setUpdateTime(dateTime(2026, 4, 13, 9, 0, 0));
        machineAOld.setLhCode("K1501");
        machineAOld.setMaterialCode("MAT-A-OLD");

        // 同机台同日记录通过 updateTime 决定优先级（ONLINE_DATE 在数据库是 date 类型）。
        LhMachineOnlineInfo machineBRecentByUpdateTime = new LhMachineOnlineInfo();
        machineBRecentByUpdateTime.setOnlineDate(date(2026, 4, 12));
        machineBRecentByUpdateTime.setUpdateTime(dateTime(2026, 4, 12, 10, 0, 0));
        machineBRecentByUpdateTime.setLhCode("K1502");
        machineBRecentByUpdateTime.setMaterialCode("MAT-B-LATE");

        LhMachineOnlineInfo machineBOldByUpdateTime = new LhMachineOnlineInfo();
        machineBOldByUpdateTime.setOnlineDate(date(2026, 4, 12));
        machineBOldByUpdateTime.setUpdateTime(dateTime(2026, 4, 12, 9, 0, 0));
        machineBOldByUpdateTime.setLhCode("K1502");
        machineBOldByUpdateTime.setMaterialCode("MAT-B-EARLY");

        when(lhMachineOnlineInfoMapper.selectList(any())).thenReturn(
                Arrays.asList(machineARecent, machineAOld, machineBRecentByUpdateTime, machineBOldByUpdateTime));

        LhScheduleContext context = newScheduleContext();
        context.setFactoryCode("FC01");
        context.setScheduleTargetDate(target);
        context.setScheduleDate(scheduleDate);
        context.getLhParamsMap().put(LhScheduleParamConstant.MACHINE_ONLINE_LOOKBACK_DAYS, "3");

        lhBaseDataService.loadAllBaseData(context);

        assertEquals(2, context.getMachineOnlineInfoMap().size());
        assertEquals("MAT-A-NEW", context.getMachineOnlineInfoMap().get("K1501").getMaterialCode());
        assertEquals("MAT-B-LATE", context.getMachineOnlineInfoMap().get("K1502").getMaterialCode());
    }

    @Test
    void loadAllBaseData_shouldOrderSameDayOnlineInfoByDataVersion() {
        Date scheduleDate = LhScheduleTimeUtil.clearTime(date(2026, 4, 15));
        when(lhMachineOnlineInfoMapper.selectList(any())).thenReturn(Collections.emptyList());

        LhScheduleContext context = newScheduleContext();
        context.setFactoryCode("FC01");
        context.setScheduleDate(scheduleDate);

        ReflectionTestUtils.invokeMethod(lhBaseDataService, "loadMachineOnlineInfo",
                context, "FC01", scheduleDate, 1);

        LambdaQueryWrapper<LhMachineOnlineInfo> wrapper = captureMachineOnlineInfoWrapper();
        String sqlSegment = wrapper.getSqlSegment().toLowerCase(Locale.ROOT);
        assertTrue(sqlSegment.contains("data_version"), "同日多条MES在机快照必须按版本号继续倒序，避免取到当天早班旧物料");
    }

    @Test
    void loadAllBaseData_shouldKeepEmptyWhenNoDataWithinLookbackWindow() {
        Date target = LhScheduleTimeUtil.clearTime(date(2026, 4, 17));
        Date scheduleDate = LhScheduleTimeUtil.addDays(target, -2);
        prepareRequiredBaseMocks();
        when(lhMachineOnlineInfoMapper.selectList(any())).thenReturn(Collections.emptyList());

        LhScheduleContext context = newScheduleContext();
        context.setFactoryCode("FC01");
        context.setScheduleTargetDate(target);
        context.setScheduleDate(scheduleDate);
        context.getLhParamsMap().put(LhScheduleParamConstant.MACHINE_ONLINE_LOOKBACK_DAYS, "1");

        lhBaseDataService.loadAllBaseData(context);

        assertTrue(context.getMachineOnlineInfoMap().isEmpty());
    }

    @Test
    void loadAllBaseData_shouldLoadDayFinishQtyAndAggregateMonthFinishedQtyUntilTargetDate() {
        Date target = LhScheduleTimeUtil.clearTime(date(2026, 4, 17));
        Date scheduleDate = LhScheduleTimeUtil.addDays(target, -2);
        prepareRequiredBaseMocks();
        when(lhMachineOnlineInfoMapper.selectList(any())).thenReturn(Collections.emptyList());

        LhDayFinishQty previousDayFinishQty = new LhDayFinishQty();
        previousDayFinishQty.setFinishDate(dateTime(2026, 4, 16, 8, 30, 0));
        previousDayFinishQty.setMaterialCode("MAT-TODAY");
        previousDayFinishQty.setDayFinishQty(BigDecimal.valueOf(20));

        LhDayFinishQty monthFinishQtyA = new LhDayFinishQty();
        monthFinishQtyA.setFinishDate(dateTime(2026, 4, 2, 9, 0, 0));
        monthFinishQtyA.setMaterialCode("MAT-MONTH");
        monthFinishQtyA.setDayFinishQty(BigDecimal.valueOf(60));

        LhDayFinishQty monthFinishQtyB = new LhDayFinishQty();
        monthFinishQtyB.setFinishDate(dateTime(2026, 4, 17, 13, 15, 0));
        monthFinishQtyB.setMaterialCode("MAT-MONTH");
        monthFinishQtyB.setDayFinishQty(BigDecimal.valueOf(20));

        LhDayFinishQty otherMaterialMonthFinishQty = new LhDayFinishQty();
        otherMaterialMonthFinishQty.setFinishDate(dateTime(2026, 4, 15, 10, 0, 0));
        otherMaterialMonthFinishQty.setMaterialCode("MAT-OTHER");
        otherMaterialMonthFinishQty.setDayFinishQty(BigDecimal.valueOf(9));

        when(lhDayFinishQtyMapper.selectList(any())).thenReturn(
                Collections.singletonList(previousDayFinishQty),
                Arrays.asList(monthFinishQtyA, monthFinishQtyB, otherMaterialMonthFinishQty));

        LhScheduleContext context = newScheduleContext();
        context.setFactoryCode("FC01");
        context.setScheduleTargetDate(target);
        context.setScheduleDate(scheduleDate);
        context.getLhParamsMap().put(LhScheduleParamConstant.FORCE_RESCHEDULE, "0");

        lhBaseDataService.loadAllBaseData(context);

        assertEquals(1, context.getMaterialDayFinishedQtyMap().size());
        assertEquals(20, context.getMaterialDayFinishedQtyMap().get("MAT-TODAY_2026-04-16").intValue());
        assertEquals(80, context.getMaterialMonthFinishedQtyMap().get("MAT-MONTH").intValue());
        assertEquals(9, context.getMaterialMonthFinishedQtyMap().get("MAT-OTHER").intValue());

        List<LambdaQueryWrapper<LhDayFinishQty>> wrappers = captureDayFinishQtyWrappers();
        assertQueryContainsDateRange(wrappers.get(0), stripTime(date(2026, 4, 16)), stripTime(date(2026, 4, 17)));
        assertQueryContainsDateRange(wrappers.get(1), stripTime(date(2026, 4, 1)), stripTime(date(2026, 4, 18)));
        assertQueryCompatibleWithNullDeleteFlag(wrappers.get(0));
        assertQueryCompatibleWithNullDeleteFlag(wrappers.get(1));
    }

    @Test
    void loadAllBaseData_shouldLoadEmbryoRealtimeStockByScheduleDateAndAggregateByEmbryoCode() {
        Date target = LhScheduleTimeUtil.clearTime(date(2026, 4, 17));
        Date scheduleDate = LhScheduleTimeUtil.addDays(target, -2);
        prepareRequiredBaseMocks();
        when(lhMachineOnlineInfoMapper.selectList(any())).thenReturn(Collections.emptyList());

        FactoryMonthPlanProductionFinalResult planA = new FactoryMonthPlanProductionFinalResult();
        planA.setMaterialCode("MAT-A");
        planA.setEmbryoCode("EMB-A");
        FactoryMonthPlanProductionFinalResult planB = new FactoryMonthPlanProductionFinalResult();
        planB.setMaterialCode("MAT-B");
        planB.setEmbryoCode("EMB-B");
        when(monthPlanMapper.selectList(any())).thenReturn(Arrays.asList(planA, planB));

        CxStock stockA1 = new CxStock();
        stockA1.setEmbryoCode("EMB-A");
        stockA1.setStockNum(40);
        CxStock stockA2 = new CxStock();
        stockA2.setEmbryoCode("EMB-A");
        stockA2.setStockNum(12);
        CxStock stockB = new CxStock();
        stockB.setEmbryoCode("EMB-B");
        stockB.setStockNum(9);
        when(cxStockMapper.selectList(any())).thenReturn(Arrays.asList(stockA1, stockA2, stockB));

        LhScheduleContext context = newScheduleContext();
        context.setFactoryCode("FC01");
        context.setScheduleTargetDate(target);
        context.setScheduleDate(scheduleDate);

        lhBaseDataService.loadAllBaseData(context);

        assertEquals(52, context.getEmbryoRealtimeStockMap().get("EMB-A").intValue());
        assertEquals(9, context.getEmbryoRealtimeStockMap().get("EMB-B").intValue());
        LambdaQueryWrapper<CxStock> wrapper = captureCxStockWrapper();
        assertQueryContainsExpectedDate(wrapper, scheduleDate, target);
        assertTrue(wrapper.getParamNameValuePairs().containsValue("FC01"));
        assertParamContainsEmbryoCodes(wrapper, "EMB-A", "EMB-B");
    }

    @Test
    void loadAllBaseData_shouldPrecomputeSpecialMaterialCategoryMaps() {
        Date target = LhScheduleTimeUtil.clearTime(date(2026, 4, 17));
        Date scheduleDate = LhScheduleTimeUtil.addDays(target, -2);
        prepareRequiredBaseMocks();
        when(lhMachineOnlineInfoMapper.selectList(any())).thenReturn(Collections.emptyList());

        FactoryMonthPlanProductionFinalResult materialHitPlan = new FactoryMonthPlanProductionFinalResult();
        materialHitPlan.setMaterialCode("MAT-HIT");
        materialHitPlan.setStructureName("STRUCT-HIT");
        FactoryMonthPlanProductionFinalResult structureHitPlan = new FactoryMonthPlanProductionFinalResult();
        structureHitPlan.setMaterialCode("MAT-STRUCT");
        structureHitPlan.setStructureName("STRUCT-ONLY");
        when(monthPlanMapper.selectList(any())).thenReturn(Arrays.asList(materialHitPlan, structureHitPlan));

        LhSpecialMaterialBom materialBom = new LhSpecialMaterialBom();
        materialBom.setMaterialCode("MAT-HIT");
        materialBom.setStructureName("STRUCT-HIT");
        materialBom.setCategory("02");
        LhSpecialMaterialBom structureBom = new LhSpecialMaterialBom();
        structureBom.setStructureName("STRUCT-ONLY");
        structureBom.setCategory("03");
        when(lhSpecialMaterialBomEntityMapper.selectList(any())).thenReturn(Arrays.asList(materialBom, structureBom));

        LhScheduleContext context = newScheduleContext();
        context.setFactoryCode("FC01");
        context.setScheduleTargetDate(target);
        context.setScheduleDate(scheduleDate);

        lhBaseDataService.loadAllBaseData(context);

        assertEquals("02", context.getSpecialMaterialCategoryByMaterialCode().get("MAT-HIT"));
        assertEquals("03", context.getSpecialMaterialCategoryByStructureName().get("STRUCT-ONLY"));
        assertFalse(context.getSpecialMaterialCategoryByMaterialCode().containsKey("MAT-STRUCT"),
                "只配置结构名称时，不应误写物料编码特殊分类Map");
    }

    @Test
    void loadAllBaseData_shouldAggregateMonthFinishedQtyUsingTargetMonthWhenWindowCrossesMonth() {
        Date target = LhScheduleTimeUtil.clearTime(date(2026, 5, 2));
        Date scheduleDate = LhScheduleTimeUtil.addDays(target, -2);
        prepareRequiredBaseMocks();
        when(lhMachineOnlineInfoMapper.selectList(any())).thenReturn(Collections.emptyList());

        LhDayFinishQty previousDayFinishQty = new LhDayFinishQty();
        previousDayFinishQty.setFinishDate(dateTime(2026, 5, 1, 9, 0, 0));
        previousDayFinishQty.setMaterialCode("MAT-TODAY");
        previousDayFinishQty.setDayFinishQty(BigDecimal.valueOf(6));

        LhDayFinishQty targetMonthFinishQtyA = new LhDayFinishQty();
        targetMonthFinishQtyA.setFinishDate(dateTime(2026, 5, 1, 10, 0, 0));
        targetMonthFinishQtyA.setMaterialCode("MAT-CROSS");
        targetMonthFinishQtyA.setDayFinishQty(BigDecimal.valueOf(15));

        LhDayFinishQty targetMonthFinishQtyB = new LhDayFinishQty();
        targetMonthFinishQtyB.setFinishDate(dateTime(2026, 5, 2, 15, 0, 0));
        targetMonthFinishQtyB.setMaterialCode("MAT-CROSS");
        targetMonthFinishQtyB.setDayFinishQty(BigDecimal.valueOf(7));

        when(lhDayFinishQtyMapper.selectList(any())).thenReturn(
                Collections.singletonList(previousDayFinishQty),
                Arrays.asList(targetMonthFinishQtyA, targetMonthFinishQtyB));

        LhScheduleContext context = newScheduleContext();
        context.setFactoryCode("FC01");
        context.setScheduleTargetDate(target);
        context.setScheduleDate(scheduleDate);
        context.getLhParamsMap().put(LhScheduleParamConstant.FORCE_RESCHEDULE, "0");

        lhBaseDataService.loadAllBaseData(context);

        assertEquals(22, context.getMaterialMonthFinishedQtyMap().get("MAT-CROSS").intValue());

        List<LambdaQueryWrapper<LhDayFinishQty>> wrappers = captureDayFinishQtyWrappers();
        assertQueryContainsDateRange(wrappers.get(1), stripTime(date(2026, 5, 1)), stripTime(date(2026, 5, 3)));
        assertQueryCompatibleWithNullDeleteFlag(wrappers.get(1));
    }

    @Test
    void resolveDayFinishedQty_shouldTreatNullAsZero() {
        LhDayFinishQty finishQty = new LhDayFinishQty();
        finishQty.setDayFinishQty(BigDecimal.valueOf(11));
        Integer finishedQty = ReflectionTestUtils.invokeMethod(lhBaseDataService,
                "resolveDayFinishedQty", finishQty);
        assertEquals(11, finishedQty.intValue());

        finishQty.setDayFinishQty(null);
        Integer nullFinishedQty = ReflectionTestUtils.invokeMethod(lhBaseDataService,
                "resolveDayFinishedQty", finishQty);
        assertEquals(0, nullFinishedQty.intValue());
    }

    private void prepareRequiredBaseMocks() {
        ReflectionTestUtils.setField(lhBaseDataService, "lhDataInitExecutor", (Executor) Runnable::run);
        MpFactoryProductionVersion finalVersion = new MpFactoryProductionVersion();
        finalVersion.setProductionVersion("PV_REGRESSION_01");
        when(mpFactoryProductionVersionMapper.selectList(any())).thenReturn(Collections.singletonList(finalVersion));

        when(monthPlanMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(workCalendarMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(skuLhCapacityMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(devicePlanShutMapper.selectList(any())).thenReturn(Collections.emptyList());
        lenient().when(skuMouldRelMapper.selectList(any())).thenReturn(Collections.emptyList());
        lenient().when(mdmModelInfoMapper.selectList(any())).thenReturn(Collections.emptyList());
        lenient().when(lhSpecialMaterialBomEntityMapper.selectList(any())).thenReturn(Collections.emptyList());

        LhMachineInfo machine = new LhMachineInfo();
        machine.setMachineCode("M1");
        machine.setStatus("0");
        machine.setIsDelete(DeleteFlagEnum.NORMAL.getCode());
        when(lhMachineInfoMapper.selectList(any())).thenReturn(Collections.singletonList(machine));

        when(lhMouldCleanPlanMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(lhMouldChangePlanMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(monthSurplusMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(lhDayFinishQtyMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(mdmMaterialInfoMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(mdmCapsuleChuckMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(lhSpecifyMachineMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(lhRepairCapsuleMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(lhPrecisionPlanMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(lhScheduleResultMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(lhScheFinishQtyMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(skuConstructionRefMapper.selectList(any())).thenReturn(Collections.emptyList());
    }

    /**
     * 构建带默认配置快照的排程上下文，避免基础数据初始化测试缺少配置对象。
     *
     * @return 排程上下文
     */
    private LhScheduleContext newScheduleContext() {
        LhScheduleContext context = new LhScheduleContext();
        context.setScheduleConfig(new LhScheduleConfig(Collections.emptyMap()));
        return context;
    }

    /**
     * 抓取日完成量查询使用的 wrapper，确保回归测试能直接校验查询条件。
     *
     * @return 按调用顺序捕获到的 wrapper 列表
     */
    @SuppressWarnings("unchecked")
    private List<LambdaQueryWrapper<LhDayFinishQty>> captureDayFinishQtyWrappers() {
        initializeDayFinishQtyTableInfo();
        ArgumentCaptor<LambdaQueryWrapper> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(lhDayFinishQtyMapper, times(2)).selectList(captor.capture());
        return (List<LambdaQueryWrapper<LhDayFinishQty>>) (List<?>) captor.getAllValues();
    }

    /**
     * 抓取前日排程结果查询条件。
     *
     * @return 前日排程查询 wrapper
     */
    @SuppressWarnings("unchecked")
    private List<LambdaQueryWrapper<LhScheduleResult>> captureScheduleResultWrappers() {
        initializeTableInfo(LhScheduleResult.class);
        ArgumentCaptor<LambdaQueryWrapper> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(lhScheduleResultMapper, times(2)).selectList(captor.capture());
        return (List<LambdaQueryWrapper<LhScheduleResult>>) (List<?>) captor.getAllValues();
    }

    /**
     * 抓取前日模具交替计划查询条件。
     *
     * @return 前日模具交替计划查询 wrapper
     */
    @SuppressWarnings("unchecked")
    private LambdaQueryWrapper<LhMouldChangePlan> captureMouldChangePlanWrapper() {
        initializeTableInfo(LhMouldChangePlan.class);
        ArgumentCaptor<LambdaQueryWrapper> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(lhMouldChangePlanMapper).selectList(captor.capture());
        return (LambdaQueryWrapper<LhMouldChangePlan>) captor.getValue();
    }

    /**
     * 抓取胎胚库存查询条件。
     *
     * @return 胎胚库存查询 wrapper
     */
    @SuppressWarnings("unchecked")
    private LambdaQueryWrapper<CxStock> captureCxStockWrapper() {
        initializeTableInfo(CxStock.class);
        ArgumentCaptor<LambdaQueryWrapper> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(cxStockMapper).selectList(captor.capture());
        return (LambdaQueryWrapper<CxStock>) captor.getValue();
    }

    /**
     * 抓取工作日历查询条件。
     *
     * @return 工作日历查询 wrapper
     */
    @SuppressWarnings("unchecked")
    private LambdaQueryWrapper<MdmWorkCalendar> captureWorkCalendarWrapper() {
        initializeTableInfo(MdmWorkCalendar.class);
        ArgumentCaptor<LambdaQueryWrapper> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(workCalendarMapper).selectList(captor.capture());
        return (LambdaQueryWrapper<MdmWorkCalendar>) captor.getValue();
    }

    /**
     * 抓取设备停机计划查询条件。
     *
     * @return 设备停机计划查询 wrapper
     */
    @SuppressWarnings("unchecked")
    private LambdaQueryWrapper<MdmDevicePlanShut> captureDevicePlanShutWrapper() {
        initializeTableInfo(MdmDevicePlanShut.class);
        ArgumentCaptor<LambdaQueryWrapper> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(devicePlanShutMapper).selectList(captor.capture());
        return (LambdaQueryWrapper<MdmDevicePlanShut>) captor.getValue();
    }

    /**
     * 抓取模具清洗计划查询条件。
     *
     * @return 模具清洗计划查询 wrapper
     */
    @SuppressWarnings("unchecked")
    private LambdaQueryWrapper<LhMouldCleanPlan> captureCleaningPlanWrapper() {
        initializeTableInfo(LhMouldCleanPlan.class);
        ArgumentCaptor<LambdaQueryWrapper> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(lhMouldCleanPlanMapper).selectList(captor.capture());
        return (LambdaQueryWrapper<LhMouldCleanPlan>) captor.getValue();
    }

    /**
     * 抓取MES在机信息查询条件。
     *
     * @return MES在机信息查询 wrapper
     */
    @SuppressWarnings("unchecked")
    private LambdaQueryWrapper<LhMachineOnlineInfo> captureMachineOnlineInfoWrapper() {
        initializeTableInfo(LhMachineOnlineInfo.class);
        ArgumentCaptor<LambdaQueryWrapper> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(lhMachineOnlineInfoMapper).selectList(captor.capture());
        return (LambdaQueryWrapper<LhMachineOnlineInfo>) captor.getValue();
    }

    /**
     * 校验胎胚编码集合参数。
     *
     * @param wrapper 查询条件
     * @param embryoCodes 胎胚编码
     */
    private void assertParamContainsEmbryoCodes(LambdaQueryWrapper<CxStock> wrapper, String... embryoCodes) {
        assertTrue(Arrays.stream(embryoCodes)
                .allMatch(embryoCode -> wrapper.getParamNameValuePairs().containsValue(embryoCode)));
    }

    /**
     * 初始化实体表信息，避免测试环境下解析 wrapper SQL 时缺少 lambda cache。
     */
    private void initializeDayFinishQtyTableInfo() {
        initializeTableInfo(LhDayFinishQty.class);
    }

    /**
     * 初始化实体表信息，避免测试环境下解析 wrapper SQL 时缺少 lambda cache。
     *
     * @param entityClass 实体类型
     */
    private void initializeTableInfo(Class<?> entityClass) {
        if (TableInfoHelper.getTableInfo(entityClass) != null) {
            return;
        }
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(),
                entityClass.getName());
        TableInfoHelper.initTableInfo(assistant, entityClass);
    }

    /**
     * 校验查询使用半开区间日期条件，防止回退为等值匹配。
     *
     * @param wrapper    查询条件
     * @param rangeStart 区间起点（含）
     * @param rangeEnd   区间终点（不含）
     */
    private void assertQueryContainsDateRange(LambdaQueryWrapper<LhDayFinishQty> wrapper, Date rangeStart, Date rangeEnd) {
        String sqlSegment = wrapper.getSqlSegment().toLowerCase(Locale.ROOT);
        Map<String, Object> paramMap = wrapper.getParamNameValuePairs();
        assertTrue(sqlSegment.contains("finish_date"));
        assertTrue(sqlSegment.contains(">="));
        assertTrue(sqlSegment.contains("<"));
        assertFalse(sqlSegment.contains("finish_date ="));
        assertTrue(paramMapContainsDate(paramMap, rangeStart));
        assertTrue(paramMapContainsDate(paramMap, rangeEnd));
    }

    /**
     * 判断查询参数中是否包含指定日期。
     *
     * @param paramMap 查询参数
     * @param expectedDate 预期日期
     * @return 是否包含指定日期
     */
    private boolean paramMapContainsDate(Map<String, Object> paramMap, Date expectedDate) {
        return paramMap.values().stream()
                .filter(Date.class::isInstance)
                .map(Date.class::cast)
                .anyMatch(date -> date.getTime() == expectedDate.getTime());
    }

    /**
     * 校验查询条件包含指定日期参数。
     *
     * @param wrapper 查询条件
     * @param expectedDate 预期日期
     */
    private void assertWrapperContainsDate(LambdaQueryWrapper<?> wrapper, Date expectedDate) {
        wrapper.getSqlSegment();
        assertTrue(paramMapContainsDate(wrapper.getParamNameValuePairs(), expectedDate));
    }

    /**
     * 校验查询条件不包含指定日期参数。
     *
     * @param wrapper 查询条件
     * @param unexpectedDate 非预期日期
     */
    private void assertWrapperNotContainsDate(LambdaQueryWrapper<?> wrapper, Date unexpectedDate) {
        wrapper.getSqlSegment();
        assertFalse(paramMapContainsDate(wrapper.getParamNameValuePairs(), unexpectedDate));
    }

    /**
     * 校验删除标记兼容 `0` 与 `NULL`，避免快照数据被遗漏。
     *
     * @param wrapper 查询条件
     */
    private void assertQueryCompatibleWithNullDeleteFlag(LambdaQueryWrapper<LhDayFinishQty> wrapper) {
        String sqlSegment = wrapper.getSqlSegment().toLowerCase(Locale.ROOT);
        Map<String, Object> paramMap = wrapper.getParamNameValuePairs();
        assertTrue(sqlSegment.contains("is_delete"));
        assertTrue(sqlSegment.contains("is null"));
        assertTrue(sqlSegment.contains("or"));
        assertTrue(paramMap.containsValue(DeleteFlagEnum.NORMAL.getCode()));
    }

    /**
     * 校验查询命中预期日期，且不再使用目标日前一日作为强制重排基线。
     *
     * @param wrapper        查询条件
     * @param expectedDate   预期日期
     * @param unexpectedDate 非预期日期
     */
    private void assertQueryContainsExpectedDate(LambdaQueryWrapper<?> wrapper, Date expectedDate, Date unexpectedDate) {
        wrapper.getSqlSegment();
        Map<String, Object> paramMap = wrapper.getParamNameValuePairs();
        String expectedDateText = LhScheduleTimeUtil.formatDate(expectedDate);
        String unexpectedDateText = LhScheduleTimeUtil.formatDate(unexpectedDate);
        String paramSummary = paramMap.values().stream()
                .map(value -> value + "(" + value.getClass().getSimpleName() + ")")
                .collect(Collectors.joining(","));
        assertTrue(paramMap.values().stream().anyMatch(value -> isSameDate(value, expectedDateText)), paramSummary);
        assertFalse(paramMap.values().stream().anyMatch(value -> isSameDate(value, unexpectedDateText)), paramSummary);
    }

    /**
     * 判断查询参数是否为指定自然日。
     *
     * @param value 查询参数值
     * @param dateText 日期文本
     * @return true-同一天
     */
    private boolean isSameDate(Object value, String dateText) {
        if (!(value instanceof Date)) {
            return false;
        }
        return dateText.equals(LhScheduleTimeUtil.formatDate((Date) value));
    }

    private static Date date(int y, int month, int day) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(Calendar.YEAR, y);
        c.set(Calendar.MONTH, month - 1);
        c.set(Calendar.DAY_OF_MONTH, day);
        return c.getTime();
    }

    private static Date stripTime(Date d) {
        return LhScheduleTimeUtil.clearTime(d);
    }

    private static Date dateTime(int y, int month, int day, int hour, int minute, int second) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(Calendar.YEAR, y);
        c.set(Calendar.MONTH, month - 1);
        c.set(Calendar.DAY_OF_MONTH, day);
        c.set(Calendar.HOUR_OF_DAY, hour);
        c.set(Calendar.MINUTE, minute);
        c.set(Calendar.SECOND, second);
        return c.getTime();
    }
}
