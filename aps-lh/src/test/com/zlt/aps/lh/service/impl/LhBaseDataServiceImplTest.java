package com.zlt.aps.lh.service.impl;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zlt.aps.cx.api.domain.entity.CxStock;
import com.zlt.aps.lh.api.constant.LhScheduleParamConstant;
import com.zlt.aps.lh.api.domain.entity.LhDayFinishQty;
import com.zlt.aps.lh.api.domain.entity.LhMachineInfo;
import com.zlt.aps.lh.context.LhScheduleConfig;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.handler.ScheduleAdjustHandler;
import com.zlt.aps.lh.mapper.CxStockMapper;
import com.zlt.aps.lh.mapper.FactoryMonthPlanProductionFinalResultMapper;
import com.zlt.aps.lh.mapper.LhDayFinishQtyMapper;
import com.zlt.aps.lh.mapper.LhMachineInfoMapper;
import com.zlt.aps.lh.mapper.LhMachineOnlineInfoMapper;
import com.zlt.aps.lh.mapper.LhMouldChangePlanEntityMapper;
import com.zlt.aps.lh.mapper.LhMouldCleanPlanMapper;
import com.zlt.aps.lh.mapper.LhPrecisionPlanMapper;
import com.zlt.aps.lh.mapper.LhRepairCapsuleMapper;
import com.zlt.aps.lh.mapper.LhScheduleResultMapper;
import com.zlt.aps.lh.mapper.LhScheFinishQtyMapper;
import com.zlt.aps.lh.mapper.LhSpecialMaterialBomEntityMapper;
import com.zlt.aps.lh.mapper.LhSpecifyMachineMapper;
import com.zlt.aps.lh.mapper.MdmCapsuleChuckMapper;
import com.zlt.aps.lh.mapper.MdmDevicePlanShutMapper;
import com.zlt.aps.lh.mapper.MdmMaterialInfoMapper;
import com.zlt.aps.lh.mapper.MdmModelInfoMapper;
import com.zlt.aps.lh.mapper.MdmSkuConstructionRefMapper;
import com.zlt.aps.lh.mapper.MdmSkuLhCapacityMapper;
import com.zlt.aps.lh.mapper.MdmSkuMouldRelMapper;
import com.zlt.aps.lh.mapper.MdmSkuScheduleCategoryMapper;
import com.zlt.aps.lh.mapper.MdmWorkCalendarMapper;
import com.zlt.aps.lh.mapper.MpFactoryProductionVersionMapper;
import com.zlt.aps.lh.mapper.MpMonthPlanStatisticsMapper;
import com.zlt.aps.mdm.api.domain.entity.MdmModelInfo;
import com.zlt.aps.mdm.api.domain.entity.MdmSkuMouldRel;
import com.zlt.aps.mp.api.domain.entity.FactoryMonthPlanProductionFinalResult;
import com.zlt.aps.mp.api.domain.entity.MpFactoryProductionVersion;
import com.zlt.aps.mp.api.domain.entity.MpMonthPlanStatistics;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.lang.reflect.Field;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 硫化基础数据加载并发编排测试。
 *
 * @author APS
 */
public class LhBaseDataServiceImplTest {

    /**
     * 用例说明：停产保机参数为3天时，月计划加载月份必须覆盖窗口前3天和窗口末日后3天。
     */
    @Test
    public void resolveMonthPlanRequiredMonthMapShouldCoverStopHoldCrossMonthRange() {
        LhBaseDataServiceImpl service = new LhBaseDataServiceImpl();

        Map<String, LocalDate> requiredMonthMap = ReflectionTestUtils.invokeMethod(
                service, "resolveMonthPlanRequiredMonthMap",
                buildDate(2026, 7, 1), buildDate(2026, 8, 4), 3);

        Assertions.assertNotNull(requiredMonthMap);
        Assertions.assertTrue(requiredMonthMap.containsValue(LocalDate.of(2026, 6, 1)),
                "窗口起点前3天跨月时必须加载上月月计划");
        Assertions.assertTrue(requiredMonthMap.containsValue(LocalDate.of(2026, 8, 1)),
                "窗口末日后3天跨月时必须加载下月月计划");

        Map<String, LocalDate> crossYearMonthMap = ReflectionTestUtils.invokeMethod(
                service, "resolveMonthPlanRequiredMonthMap",
                buildDate(2027, 1, 1), buildDate(2027, 1, 4), 3);
        Assertions.assertNotNull(crossYearMonthMap);
        Assertions.assertTrue(crossYearMonthMap.containsValue(LocalDate.of(2026, 12, 1)),
                "窗口起点前3天跨年时必须加载上年12月月计划");
        Assertions.assertTrue(crossYearMonthMap.containsValue(LocalDate.of(2027, 1, 1)),
                "跨年读取不得遗漏当前年度1月月计划");
    }

    /**
     * 用例说明：基础数据初始化必须使用注入的独立线程池执行异步任务。
     *
     * @throws Exception 反射注入异常
     */
    @Test
    public void loadAllBaseDataShouldUseConfiguredExecutor() throws Exception {
        LhBaseDataServiceImpl service = buildServiceWithDefaultMocks();
        AtomicInteger executeCount = new AtomicInteger();
        injectField(service, "lhDataInitExecutor", (Executor) command -> {
            executeCount.incrementAndGet();
            command.run();
        });

        service.loadAllBaseData(buildContext());

        Assertions.assertTrue(executeCount.get() > 0, "基础数据初始化应提交任务到独立线程池");
    }

    /**
     * 用例说明：依赖月计划的特殊物料清单和胎胚库存，必须在月计划加载完成后再执行。
     *
     * @throws Exception 反射注入异常
     */
    @Test
    public void loadAllBaseDataShouldRunMonthPlanDependentTasksAfterMonthPlanLoaded() throws Exception {
        LhBaseDataServiceImpl service = buildServiceWithDefaultMocks();
        LhScheduleContext context = buildContext();
        AtomicBoolean monthPlanLoaded = new AtomicBoolean(false);

        FactoryMonthPlanProductionFinalResult monthPlan = new FactoryMonthPlanProductionFinalResult();
        monthPlan.setMaterialCode("MAT-001");
        monthPlan.setEmbryoCode("EMB-001");
        monthPlan.setStructureName("STRUCT-001");
        FactoryMonthPlanProductionFinalResultMapper monthPlanMapper = mockMapper(FactoryMonthPlanProductionFinalResultMapper.class);
        Mockito.when(monthPlanMapper.selectList(ArgumentMatchers.any())).thenAnswer(invocation -> {
            monthPlanLoaded.set(true);
            return Collections.singletonList(monthPlan);
        });
        injectField(service, "monthPlanMapper", monthPlanMapper);

        LhSpecialMaterialBomEntityMapper specialMaterialBomMapper = mockMapper(LhSpecialMaterialBomEntityMapper.class);
        Mockito.when(specialMaterialBomMapper.selectList(ArgumentMatchers.any())).thenAnswer(invocation -> {
            Assertions.assertTrue(monthPlanLoaded.get(), "特殊物料清单加载前必须先完成月计划加载");
            Assertions.assertFalse(context.getMonthPlanList().isEmpty(), "特殊物料清单加载时上下文应已有月计划");
            return Collections.emptyList();
        });
        injectField(service, "lhSpecialMaterialBomEntityMapper", specialMaterialBomMapper);

        CxStock stock = new CxStock();
        stock.setEmbryoCode("EMB-001");
        stock.setStockNum(12);
        CxStockMapper cxStockMapper = mockMapper(CxStockMapper.class);
        Mockito.when(cxStockMapper.selectList(ArgumentMatchers.any())).thenAnswer(invocation -> {
            Assertions.assertTrue(monthPlanLoaded.get(), "胎胚库存加载前必须先完成月计划加载");
            Assertions.assertFalse(context.getMonthPlanList().isEmpty(), "胎胚库存加载时上下文应已有月计划");
            return Collections.singletonList(stock);
        });
        injectField(service, "cxStockMapper", cxStockMapper);

        injectField(service, "lhDataInitExecutor", (Executor) Runnable::run);

        service.loadAllBaseData(context);

        Assertions.assertEquals(1, context.getMonthPlanList().size());
        Assertions.assertEquals(Integer.valueOf(12), context.getEmbryoRealtimeStockMap().get("EMB-001"));
    }

    /**
     * 用例说明：月累计完成量任务依赖月计划物料列表，必须在月计划加载完成后再执行。
     *
     * @throws Exception 反射注入异常
     */
    @Test
    public void loadAllBaseDataShouldLoadMonthFinishedQtyAfterMonthPlanLoaded() throws Exception {
        LhBaseDataServiceImpl service = buildServiceWithDefaultMocks();
        LhScheduleContext context = buildContext();
        context.setScheduleDate(buildDate(2026, 6, 3));
        context.setScheduleTargetDate(buildDate(2026, 6, 5));
        AtomicBoolean monthPlanLoaded = new AtomicBoolean(false);
        AtomicBoolean monthFinishedQtyObserved = new AtomicBoolean(false);
        AtomicBoolean monthFinishedQtySawLoadedMonthPlan = new AtomicBoolean(false);
        AtomicInteger dayFinishQtyQueryCount = new AtomicInteger();
        CountDownLatch monthPlanQueryStarted = new CountDownLatch(1);
        CountDownLatch allowMonthPlanFinish = new CountDownLatch(1);

        FactoryMonthPlanProductionFinalResult monthPlan = new FactoryMonthPlanProductionFinalResult();
        monthPlan.setMaterialCode("3302001513");
        FactoryMonthPlanProductionFinalResultMapper monthPlanMapper = mockMapper(FactoryMonthPlanProductionFinalResultMapper.class);
        Mockito.when(monthPlanMapper.selectList(ArgumentMatchers.any())).thenAnswer(invocation -> {
            monthPlanQueryStarted.countDown();
            allowMonthPlanFinish.await(1, TimeUnit.SECONDS);
            monthPlanLoaded.set(true);
            return Collections.singletonList(monthPlan);
        });
        injectField(service, "monthPlanMapper", monthPlanMapper);

        LhDayFinishQtyMapper dayFinishQtyMapper = mockMapper(LhDayFinishQtyMapper.class);
        Mockito.when(dayFinishQtyMapper.selectList(ArgumentMatchers.any())).thenAnswer(invocation -> {
            int queryIndex = dayFinishQtyQueryCount.incrementAndGet();
            if (queryIndex == 2) {
                monthPlanQueryStarted.await(1, TimeUnit.SECONDS);
                monthFinishedQtyObserved.set(true);
                monthFinishedQtySawLoadedMonthPlan.set(monthPlanLoaded.get());
                allowMonthPlanFinish.countDown();
            }
            return Collections.emptyList();
        });
        injectField(service, "lhDayFinishQtyMapper", dayFinishQtyMapper);

        ExecutorService executorService = Executors.newFixedThreadPool(8);
        injectField(service, "lhDataInitExecutor", executorService);
        try {
            service.loadAllBaseData(context);
        } finally {
            executorService.shutdownNow();
        }

        Assertions.assertTrue(monthFinishedQtyObserved.get(), "测试需要实际命中月累计完成量查询");
        Assertions.assertTrue(monthFinishedQtySawLoadedMonthPlan.get(), "月累计完成量任务必须在月计划加载完成后执行");
    }

    /**
     * 用例说明：目标日属于新月份但排程窗口T日仍在月初时，月累计完成量只能按目标月统计，不能带入上月完成量。
     *
     * @throws Exception 反射注入异常
     */
    @Test
    public void loadAllBaseDataShouldNotUsePreviousMonthFinishedQtyForTargetMonthPlan() throws Exception {
        LhBaseDataServiceImpl service = buildServiceWithDefaultMocks();
        injectField(service, "lhDataInitExecutor", (Executor) Runnable::run);
        LhScheduleContext context = buildContext();
        context.setScheduleDate(buildDate(2026, 6, 1));
        context.setScheduleTargetDate(buildDate(2026, 6, 3));

        FactoryMonthPlanProductionFinalResult monthPlan = new FactoryMonthPlanProductionFinalResult();
        monthPlan.setMaterialCode("3302001513");
        FactoryMonthPlanProductionFinalResultMapper monthPlanMapper = mockMapper(FactoryMonthPlanProductionFinalResultMapper.class);
        Mockito.when(monthPlanMapper.selectList(ArgumentMatchers.any())).thenReturn(Collections.singletonList(monthPlan));
        injectField(service, "monthPlanMapper", monthPlanMapper);

        LhDayFinishQty previousMonthFinishQtyA = new LhDayFinishQty();
        previousMonthFinishQtyA.setFinishDate(buildDate(2026, 5, 7));
        previousMonthFinishQtyA.setMaterialCode("3302001513");
        previousMonthFinishQtyA.setDayFinishQty(BigDecimal.valueOf(103));
        LhDayFinishQty previousMonthFinishQtyB = new LhDayFinishQty();
        previousMonthFinishQtyB.setFinishDate(buildDate(2026, 5, 24));
        previousMonthFinishQtyB.setMaterialCode("3302001513");
        previousMonthFinishQtyB.setDayFinishQty(BigDecimal.valueOf(1344));
        LhDayFinishQty previousDayFinishQty = new LhDayFinishQty();
        previousDayFinishQty.setFinishDate(buildDate(2026, 5, 31));
        previousDayFinishQty.setMaterialCode("3302001513");
        previousDayFinishQty.setDayFinishQty(BigDecimal.valueOf(1447));

        LhDayFinishQtyMapper dayFinishQtyMapper = mockMapper(LhDayFinishQtyMapper.class);
        Mockito.when(dayFinishQtyMapper.selectList(ArgumentMatchers.any())).thenReturn(
                Collections.singletonList(previousDayFinishQty),
                Arrays.asList(previousMonthFinishQtyA, previousMonthFinishQtyB));
        injectField(service, "lhDayFinishQtyMapper", dayFinishQtyMapper);

        service.loadAllBaseData(context);

        Assertions.assertEquals(Integer.valueOf(1447),
                context.getMaterialDayFinishedQtyMap().get("3302001513_2026-05-31"),
                "测试数据需要保留T-1日完成量，防止月累计缺失时回退误判");
        Assertions.assertEquals(Integer.valueOf(0), context.getMaterialMonthFinishedQtyMap().get("3302001513"),
                "6月月计划累计完成量应明确为0，不能带入5月完成量或缺失后触发历史回退");
    }

    /**
     * 用例说明：本月逐日完成量Map必须排除NULL并保留0，确保最近一次完成量判断能区分空值与零值。
     *
     * @throws Exception 反射注入异常
     */
    @Test
    public void loadAllBaseDataShouldExcludeNullAndKeepZeroMonthDailyFinishedQty() throws Exception {
        LhBaseDataServiceImpl service = buildServiceWithDefaultMocks();
        injectField(service, "lhDataInitExecutor", (Executor) Runnable::run);
        LhScheduleContext context = buildContext();
        context.setScheduleDate(buildDate(2026, 6, 14));
        context.setScheduleTargetDate(buildDate(2026, 6, 14));

        FactoryMonthPlanProductionFinalResult monthPlan = new FactoryMonthPlanProductionFinalResult();
        monthPlan.setMaterialCode("3302001139");
        FactoryMonthPlanProductionFinalResultMapper monthPlanMapper =
                mockMapper(FactoryMonthPlanProductionFinalResultMapper.class);
        Mockito.when(monthPlanMapper.selectList(ArgumentMatchers.any()))
                .thenReturn(Collections.singletonList(monthPlan));
        injectField(service, "monthPlanMapper", monthPlanMapper);

        LhDayFinishQty nullFinishQty = buildDayFinishQty("3302001139", 2026, 6, 11, null);
        LhDayFinishQty zeroFinishQty = buildDayFinishQty("3302001139", 2026, 6, 12, BigDecimal.ZERO);
        LhDayFinishQty positiveFinishQty = buildDayFinishQty(
                "3302001139", 2026, 6, 13, BigDecimal.valueOf(32));
        LhDayFinishQtyMapper dayFinishQtyMapper = mockMapper(LhDayFinishQtyMapper.class);
        Mockito.when(dayFinishQtyMapper.selectList(ArgumentMatchers.any())).thenReturn(
                Arrays.asList(nullFinishQty, zeroFinishQty, positiveFinishQty),
                Collections.emptyList());
        injectField(service, "lhDayFinishQtyMapper", dayFinishQtyMapper);

        service.loadAllBaseData(context);

        Assertions.assertFalse(context.getMaterialMonthDailyFinishedQtyMap()
                .containsKey("3302001139_2026-06-11"), "NULL完成量不能写入本月逐日完成量Map");
        Assertions.assertEquals(Integer.valueOf(0), context.getMaterialMonthDailyFinishedQtyMap()
                .get("3302001139_2026-06-12"), "0是有效最近数据，必须保留日期键");
        Assertions.assertEquals(Integer.valueOf(32), context.getMaterialMonthDailyFinishedQtyMap()
                .get("3302001139_2026-06-13"), "正数完成量应按日期正常聚合");
    }

    /**
     * 用例说明：LhDayFinishQty 来源的日完成量和月累计完成量必须按物料编码+示方类型汇总，避免同物料不同产品状态串量。
     *
     * @throws Exception 反射注入异常
     */
    @Test
    public void loadAllBaseDataShouldAggregateDayFinishQtyByMaterialAndLhType() throws Exception {
        LhBaseDataServiceImpl service = buildServiceWithDefaultMocks();
        injectField(service, "lhDataInitExecutor", (Executor) Runnable::run);
        LhScheduleContext context = buildContext();
        context.setScheduleDate(buildDate(2026, 6, 14));
        context.setScheduleTargetDate(buildDate(2026, 6, 14));

        FactoryMonthPlanProductionFinalResult formalPlan = new FactoryMonthPlanProductionFinalResult();
        formalPlan.setMaterialCode("3302001139");
        formalPlan.setProductStatus("S");
        FactoryMonthPlanProductionFinalResult trialPlan = new FactoryMonthPlanProductionFinalResult();
        trialPlan.setMaterialCode("3302001139");
        trialPlan.setProductStatus("T");
        FactoryMonthPlanProductionFinalResultMapper monthPlanMapper =
                mockMapper(FactoryMonthPlanProductionFinalResultMapper.class);
        Mockito.when(monthPlanMapper.selectList(ArgumentMatchers.any()))
                .thenReturn(Arrays.asList(formalPlan, trialPlan));
        injectField(service, "monthPlanMapper", monthPlanMapper);

        LhDayFinishQty formalPreviousDayQty = buildDayFinishQty(
                "3302001139", "S", 2026, 6, 13, BigDecimal.valueOf(9));
        LhDayFinishQty trialPreviousDayQty = buildDayFinishQty(
                "3302001139", "T", 2026, 6, 13, BigDecimal.valueOf(7));
        LhDayFinishQty formalMonthQtyA = buildDayFinishQty(
                "3302001139", "S", 2026, 6, 11, BigDecimal.valueOf(10));
        LhDayFinishQty formalMonthQtyB = buildDayFinishQty(
                "3302001139", "S", 2026, 6, 12, BigDecimal.valueOf(20));
        LhDayFinishQty trialMonthQty = buildDayFinishQty(
                "3302001139", "T", 2026, 6, 12, BigDecimal.valueOf(5));
        LhDayFinishQtyMapper dayFinishQtyMapper = mockMapper(LhDayFinishQtyMapper.class);
        Mockito.when(dayFinishQtyMapper.selectList(ArgumentMatchers.any())).thenReturn(
                Arrays.asList(formalMonthQtyA, formalMonthQtyB, trialMonthQty),
                Arrays.asList(formalPreviousDayQty, trialPreviousDayQty));
        injectField(service, "lhDayFinishQtyMapper", dayFinishQtyMapper);

        service.loadAllBaseData(context);

        Assertions.assertEquals(Integer.valueOf(9),
                context.getMaterialDayFinishedQtyMap().get("3302001139_S_2026-06-13"));
        Assertions.assertEquals(Integer.valueOf(7),
                context.getMaterialDayFinishedQtyMap().get("3302001139_T_2026-06-13"));
        Assertions.assertFalse(context.getMaterialDayFinishedQtyMap().containsKey("3302001139_2026-06-13"),
                "日完成量不能再按物料+日期旧key聚合");
        Assertions.assertEquals(Integer.valueOf(30),
                context.getMaterialMonthFinishedQtyMap().get("3302001139_S"));
        Assertions.assertEquals(Integer.valueOf(5),
                context.getMaterialMonthFinishedQtyMap().get("3302001139_T"));
        Assertions.assertEquals(Integer.valueOf(10),
                context.getMaterialMonthDailyFinishedQtyMap().get("3302001139_S_2026-06-11"));
        Assertions.assertEquals(Integer.valueOf(20),
                context.getMaterialMonthDailyFinishedQtyMap().get("3302001139_S_2026-06-12"));
        Assertions.assertEquals(Integer.valueOf(5),
                context.getMaterialMonthDailyFinishedQtyMap().get("3302001139_T_2026-06-12"));
        Assertions.assertEquals(Integer.valueOf(30),
                context.getMaterialMonthFinishedQtyByMonthMap().get("3302001139_S_2026_6"));
        Assertions.assertEquals(Integer.valueOf(5),
                context.getMaterialMonthFinishedQtyByMonthMap().get("3302001139_T_2026_6"));
    }

    /**
     * 用例说明：跨月加载必须按各自然月定稿记录中的排产版本查询月计划，并保留需求版本供结构统计等链路使用。
     *
     * @throws Exception 反射注入异常
     */
    @Test
    public void loadAllBaseDataShouldUseYearMonthSpecificProductionVersionAcrossQueries() throws Exception {
        LhBaseDataServiceImpl service = buildServiceWithDefaultMocks();
        injectField(service, "lhDataInitExecutor", (Executor) Runnable::run);
        LhScheduleContext context = buildContext();
        context.setMonthPlanVersion("REQUEST-MP");
        context.setScheduleDate(buildDate(2026, 6, 29));
        context.setScheduleTargetDate(buildDate(2026, 7, 1));

        MpFactoryProductionVersionMapper versionMapper = mockMapper(MpFactoryProductionVersionMapper.class);
        Mockito.when(versionMapper.selectList(ArgumentMatchers.any())).thenReturn(
                Collections.singletonList(buildProductionVersion("PV-06", "MP-06")),
                Collections.singletonList(buildProductionVersion("PV-07", "MP-07")));
        injectField(service, "mpFactoryProductionVersionMapper", versionMapper);

        FactoryMonthPlanProductionFinalResult junePlan = new FactoryMonthPlanProductionFinalResult();
        junePlan.setFactoryCode("116");
        junePlan.setMaterialCode("3302001606");
        junePlan.setYear(2026);
        junePlan.setMonth(6);
        junePlan.setMonthPlanVersion("MP-06");
        junePlan.setProductionVersion("PV-06");
        junePlan.setEmbryoCode("EMB-06");
        junePlan.setStructureName("STRUCT-06");
        junePlan.setDay29(12);
        junePlan.setDay30(18);
        FactoryMonthPlanProductionFinalResult julyPlan = new FactoryMonthPlanProductionFinalResult();
        julyPlan.setFactoryCode("116");
        julyPlan.setMaterialCode("3302001606");
        julyPlan.setYear(2026);
        julyPlan.setMonth(7);
        julyPlan.setMonthPlanVersion("MP-07");
        julyPlan.setProductionVersion("PV-07");
        julyPlan.setEmbryoCode("EMB-07");
        julyPlan.setStructureName("STRUCT-07");
        julyPlan.setDay1(20);
        julyPlan.setDay2(16);
        FactoryMonthPlanProductionFinalResultMapper monthPlanMapper =
                mockMapper(FactoryMonthPlanProductionFinalResultMapper.class);
        Mockito.when(monthPlanMapper.selectList(ArgumentMatchers.any())).thenReturn(
                Collections.singletonList(junePlan),
                Collections.singletonList(julyPlan));
        injectField(service, "monthPlanMapper", monthPlanMapper);

        MpMonthPlanStatisticsMapper statisticsMapper = mockMapper(MpMonthPlanStatisticsMapper.class);
        MpMonthPlanStatistics juneStatistics = new MpMonthPlanStatistics();
        juneStatistics.setStructureName("STRUCT-06");
        juneStatistics.setDay29("{\"lhMachines\":1}");
        juneStatistics.setDay30("{\"lhMachines\":2}");
        MpMonthPlanStatistics julyStatistics = new MpMonthPlanStatistics();
        julyStatistics.setStructureName("STRUCT-07");
        julyStatistics.setDay1("{\"lhMachines\":3}");
        julyStatistics.setDay2("{\"lhMachines\":4}");
        Mockito.when(statisticsMapper.selectList(ArgumentMatchers.any())).thenReturn(
                Collections.singletonList(juneStatistics),
                Collections.singletonList(julyStatistics));
        injectField(service, "monthPlanStatisticsMapper", statisticsMapper);

        service.loadAllBaseData(context);

        Assertions.assertEquals("MP-07", context.getMonthPlanVersion(), "主上下文需求版本应优先使用目标业务日所在年月");
        Assertions.assertEquals("PV-07", context.getProductionVersion(), "主上下文排产版本应优先使用目标业务日所在年月");
        Assertions.assertEquals("MP-06", context.getMonthPlanVersionByYearMonthMap().get("2026_6"));
        Assertions.assertEquals("MP-07", context.getMonthPlanVersionByYearMonthMap().get("2026_7"));
        Assertions.assertEquals("PV-06", context.getProductionVersionByYearMonthMap().get("2026_6"));
        Assertions.assertEquals("PV-07", context.getProductionVersionByYearMonthMap().get("2026_7"));
        Assertions.assertEquals(2, context.getLoadedMonthPlanList().size(), "跨月应加载两个自然月的月计划");
    }

    /**
     * 用例说明：T 日为月初时，续作降模比较 T-1 原始日计划量需要额外加载上月定稿版本和月计划。
     *
     * @throws Exception 反射注入异常
     */
    @Test
    public void loadAllBaseDataShouldLoadPreviousMonthPlanWhenFirstScheduleDayIsMonthStart() throws Exception {
        LhBaseDataServiceImpl service = new LhBaseDataServiceImpl();

        Map<String, LocalDate> requiredMonthMap = ReflectionTestUtils.invokeMethod(service,
                "resolveMonthPlanRequiredMonthMap", buildDate(2026, 7, 1), buildDate(2026, 7, 4), 2);

        Assertions.assertNotNull(requiredMonthMap);
        Assertions.assertEquals(Arrays.asList("2026_6", "2026_7"),
                new ArrayList<String>(requiredMonthMap.keySet()),
                "月初排程的月计划加载范围必须同时覆盖 T-1 所属上月和 T 日所属当月");
    }

    /**
     * 用例说明：月计划查询只能按排产版本取数，不能再按需求版本过滤，否则会漏掉同排产版本下的调整版本试制/量试行。
     *
     * @throws Exception 反射调用异常
     */
    @Test
    public void queryMonthPlanShouldUseProductionVersionOnlyAndKeepAdjustedDemandRows() throws Exception {
        LhBaseDataServiceImpl service = buildServiceWithDefaultMocks();
        LhScheduleContext context = buildContext();
        context.setProductionVersion("I20260624154810");
        context.setMonthPlanVersion(null);
        AtomicBoolean monthPlanQueried = new AtomicBoolean(false);

        FactoryMonthPlanProductionFinalResult formalPlan = monthPlan(
                "3302000001", 2026, 7, "REQ20260619003", "I20260624154810");
        formalPlan.setConstructionStage("03");
        FactoryMonthPlanProductionFinalResult trialPlan = monthPlan(
                "3302002468", 2026, 7, "ADJ20260630001", "I20260624154810");
        trialPlan.setConstructionStage("01");

        FactoryMonthPlanProductionFinalResultMapper monthPlanMapper =
                mockMapper(FactoryMonthPlanProductionFinalResultMapper.class);
        Mockito.when(monthPlanMapper.selectList(ArgumentMatchers.any())).thenAnswer(invocation -> {
            monthPlanQueried.set(true);
            return Arrays.asList(formalPlan, trialPlan);
        });
        injectField(service, "monthPlanMapper", monthPlanMapper);

        List<FactoryMonthPlanProductionFinalResult> resultList = ReflectionTestUtils.invokeMethod(service,
                "queryMonthPlan", context, "116", 2026, 7);

        Assertions.assertTrue(monthPlanQueried.get(),
                "月计划查询不能再依赖需求版本，需求版本为空时仍应按排产版本查数");
        Assertions.assertFalse(context.isInterrupted(), "需求版本为空不能中断月计划查询");
        Assertions.assertEquals(2, resultList.size(), "同一排产版本下的原始需求行和调整需求行都应被加载");
        Assertions.assertTrue(resultList.stream()
                        .anyMatch(plan -> "3302002468".equals(plan.getMaterialCode())),
                "调整版本试制SKU应进入月计划结果集");
    }

    /**
     * 用例说明：提前生产最多可向后查看31天，基础数据必须提前加载窗口结束日后31天覆盖到的自然月。
     *
     * @throws Exception 反射注入异常
     */
    @Test
    public void loadAllBaseDataShouldLoadMonthsCoveredByEarlyProductionThreshold() throws Exception {
        LhBaseDataServiceImpl service = buildServiceWithDefaultMocks();
        injectField(service, "lhDataInitExecutor", (Executor) Runnable::run);
        LhScheduleContext context = buildContext();
        context.setScheduleDate(buildDate(2026, 7, 30));
        context.setScheduleTargetDate(buildDate(2026, 8, 1));
        context.setScheduleConfig(scheduleConfigWithEarlyProductionDays(31));

        MpFactoryProductionVersionMapper versionMapper = mockMapper(MpFactoryProductionVersionMapper.class);
        Mockito.when(versionMapper.selectList(ArgumentMatchers.any())).thenReturn(
                Collections.singletonList(buildProductionVersion("PV-07", "MP-07")),
                Collections.singletonList(buildProductionVersion("PV-08", "MP-08")),
                Collections.singletonList(buildProductionVersion("PV-09", "MP-09")));
        injectField(service, "mpFactoryProductionVersionMapper", versionMapper);

        FactoryMonthPlanProductionFinalResultMapper monthPlanMapper =
                mockMapper(FactoryMonthPlanProductionFinalResultMapper.class);
        Mockito.when(monthPlanMapper.selectList(ArgumentMatchers.any())).thenReturn(
                Collections.singletonList(monthPlan("3302001606", 2026, 7, "MP-07", "PV-07")),
                Collections.singletonList(monthPlan("3302001606", 2026, 8, "MP-08", "PV-08")),
                Collections.singletonList(monthPlan("3302001606", 2026, 9, "MP-09", "PV-09")));
        injectField(service, "monthPlanMapper", monthPlanMapper);

        MpMonthPlanStatisticsMapper statisticsMapper = mockMapper(MpMonthPlanStatisticsMapper.class);
        Mockito.when(statisticsMapper.selectList(ArgumentMatchers.any())).thenReturn(
                Collections.singletonList(monthStatistics("STRUCT-001")),
                Collections.singletonList(monthStatistics("STRUCT-001")),
                Collections.singletonList(monthStatistics("STRUCT-001")));
        injectField(service, "monthPlanStatisticsMapper", statisticsMapper);

        service.loadAllBaseData(context);

        Assertions.assertEquals("MP-09", context.getMonthPlanVersionByYearMonthMap().get("2026_9"),
                "提前生产31天视野覆盖到9月时，应加载9月需求版本");
        Assertions.assertEquals("PV-09", context.getProductionVersionByYearMonthMap().get("2026_9"),
                "提前生产31天视野覆盖到9月时，应加载9月排产版本");
        Assertions.assertEquals(3, context.getLoadedMonthPlanList().size(),
                "基础月计划应覆盖T日至窗口结束日后31天涉及的所有自然月");
    }

    /**
     * 用例说明：S4.3 SKU 归集基础月计划必须按物料编码+产品状态去重，不能丢失同物料的不同产品状态。
     *
     * @throws Exception 反射注入异常
     */
    @Test
    public void selectSchedulingMonthPlanListShouldKeepSameMaterialDifferentProductStatus() throws Exception {
        LhBaseDataServiceImpl service = buildServiceWithDefaultMocks();
        LhScheduleContext context = buildContext();
        context.setScheduleDate(buildDate(2026, 6, 29));
        context.setWindowEndDate(buildDate(2026, 7, 1));
        FactoryMonthPlanProductionFinalResult formalPlan = new FactoryMonthPlanProductionFinalResult();
        formalPlan.setMaterialCode("3302001606");
        formalPlan.setProductStatus("S");
        formalPlan.setYear(2026);
        formalPlan.setMonth(6);
        formalPlan.setDay29(12);
        FactoryMonthPlanProductionFinalResult trialPlan = new FactoryMonthPlanProductionFinalResult();
        trialPlan.setMaterialCode("3302001606");
        trialPlan.setProductStatus("T");
        trialPlan.setYear(2026);
        trialPlan.setMonth(6);
        trialPlan.setDay29(8);

        List<FactoryMonthPlanProductionFinalResult> selectedPlanList = ReflectionTestUtils.invokeMethod(service,
                "selectSchedulingMonthPlanList", context, Arrays.asList(formalPlan, trialPlan));

        Assertions.assertEquals(2, selectedPlanList.size(), "同物料不同产品状态必须分别生成归集基础计划");
    }

    /**
     * 用例说明：SKU与模具关系应在月计划加载后按本次月计划SKU范围查询，模具台账应在关系加载后按关联模具号范围查询。
     *
     * @throws Exception 反射注入异常
     */
    @Test
    public void loadAllBaseDataShouldLoadMouldDataByCurrentMonthPlanScope() throws Exception {
        LhBaseDataServiceImpl service = buildServiceWithDefaultMocks();
        injectField(service, "lhDataInitExecutor", (Executor) Runnable::run);
        LhScheduleContext context = buildContext();

        FactoryMonthPlanProductionFinalResult monthPlan = new FactoryMonthPlanProductionFinalResult();
        monthPlan.setMaterialCode("SKU-001");
        FactoryMonthPlanProductionFinalResultMapper monthPlanMapper = mockMapper(FactoryMonthPlanProductionFinalResultMapper.class);
        Mockito.when(monthPlanMapper.selectList(ArgumentMatchers.any())).thenReturn(Collections.singletonList(monthPlan));
        injectField(service, "monthPlanMapper", monthPlanMapper);

        MdmSkuMouldRel rel = new MdmSkuMouldRel();
        rel.setMaterialCode("SKU-001");
        rel.setMouldCode("M001");
        MdmSkuMouldRel unrelatedRel = new MdmSkuMouldRel();
        unrelatedRel.setMaterialCode("SKU-999");
        unrelatedRel.setMouldCode("M999");
        MdmSkuMouldRelMapper skuMouldRelMapper = mockMapper(MdmSkuMouldRelMapper.class);
        Mockito.when(skuMouldRelMapper.selectList(ArgumentMatchers.any())).thenAnswer(invocation -> {
            Assertions.assertFalse(context.getMonthPlanList().isEmpty(), "SKU与模具关系必须在月计划加载后执行");
            return Arrays.asList(rel, unrelatedRel);
        });
        injectField(service, "skuMouldRelMapper", skuMouldRelMapper);

        MdmModelInfo modelInfo = new MdmModelInfo();
        modelInfo.setMouldCode("M001");
        modelInfo.setMouldStatus(1);
        MdmModelInfo unrelatedModelInfo = new MdmModelInfo();
        unrelatedModelInfo.setMouldCode("M999");
        unrelatedModelInfo.setMouldStatus(1);
        MdmModelInfoMapper modelInfoMapper = mockMapper(MdmModelInfoMapper.class);
        Mockito.when(modelInfoMapper.selectList(ArgumentMatchers.any())).thenAnswer(invocation -> {
            Assertions.assertTrue(context.getSkuMouldRelMap().containsKey("SKU-001"),
                    "模具台账必须在SKU与模具关系加载后执行");
            return Arrays.asList(modelInfo, unrelatedModelInfo);
        });
        injectField(service, "mdmModelInfoMapper", modelInfoMapper);

        service.loadAllBaseData(context);

        Assertions.assertTrue(context.getSkuMouldRelMap().containsKey("SKU-001"));
        Assertions.assertFalse(context.getSkuMouldRelMap().containsKey("SKU-999"),
                "SKU与模具关系缓存只能保留本次月计划SKU");
        Assertions.assertTrue(context.getModelInfoMap().containsKey("M001"));
        Assertions.assertFalse(context.getModelInfoMap().containsKey("M999"),
                "模具台账缓存只能保留本次SKU关联模具号");
    }

    /**
     * 用例说明：月计划结构机台统计整批缺失时按空缓存继续排程。
     *
     * @throws Exception 反射注入异常
     */
    @Test
    public void loadAllBaseDataShouldContinueWhenMonthPlanStatisticsMissing() throws Exception {
        LhBaseDataServiceImpl service = buildServiceWithDefaultMocks();
        injectField(service, "monthPlanStatisticsMapper", mockMapper(MpMonthPlanStatisticsMapper.class));
        injectField(service, "lhDataInitExecutor", (Executor) Runnable::run);
        LhScheduleContext context = buildContext();

        service.loadAllBaseData(context);

        Assertions.assertFalse(context.isInterrupted(), "月计划结构机台统计缺失时不应中断排程");
        Assertions.assertTrue(context.getStructurePlanMachineCountMap().isEmpty(),
                "月计划结构机台统计缺失时应保留空缓存");
    }

    /**
     * 用例说明：月计划结构机台统计记录全部无有效结构时按空缓存继续排程。
     *
     * @throws Exception 反射注入异常
     */
    @Test
    public void loadAllBaseDataShouldContinueWhenMonthPlanStatisticsHaveNoValidStructure() throws Exception {
        LhBaseDataServiceImpl service = buildServiceWithDefaultMocks();
        MpMonthPlanStatistics invalidStatistics = new MpMonthPlanStatistics();
        MpMonthPlanStatisticsMapper mapper = mockMapper(MpMonthPlanStatisticsMapper.class);
        Mockito.when(mapper.selectList(ArgumentMatchers.any()))
                .thenReturn(Collections.singletonList(invalidStatistics));
        injectField(service, "monthPlanStatisticsMapper", mapper);
        injectField(service, "lhDataInitExecutor", (Executor) Runnable::run);
        LhScheduleContext context = buildContext();

        service.loadAllBaseData(context);

        Assertions.assertFalse(context.isInterrupted(), "月计划结构机台统计无有效结构时不应中断排程");
        Assertions.assertTrue(context.getStructurePlanMachineCountMap().isEmpty(),
                "无有效结构记录时不应写入结构机台缓存");
    }

    /**
     * 用例说明：月计划结构机台统计的结构名全为空格时跳过该记录并继续排程。
     *
     * @throws Exception 反射注入异常
     */
    @Test
    public void loadAllBaseDataShouldContinueWhenMonthPlanStatisticsStructureIsBlank() throws Exception {
        LhBaseDataServiceImpl service = buildServiceWithDefaultMocks();
        MpMonthPlanStatistics invalidStatistics = new MpMonthPlanStatistics();
        invalidStatistics.setStructureName("   ");
        MpMonthPlanStatisticsMapper mapper = mockMapper(MpMonthPlanStatisticsMapper.class);
        Mockito.when(mapper.selectList(ArgumentMatchers.any()))
                .thenReturn(Collections.singletonList(invalidStatistics));
        injectField(service, "monthPlanStatisticsMapper", mapper);
        injectField(service, "lhDataInitExecutor", (Executor) Runnable::run);
        LhScheduleContext context = buildContext();

        service.loadAllBaseData(context);

        Assertions.assertFalse(context.isInterrupted(), "全空格结构名不应中断排程");
        Assertions.assertTrue(context.getStructurePlanMachineCountMap().isEmpty(),
                "全空格结构名不能作为有效结构写入缓存");
    }

    /**
     * 用例说明：月计划结构机台统计缺失时记录告警并继续输出加载完成日志。
     *
     * @throws Exception 反射注入异常
     */
    @Test
    public void loadAllBaseDataShouldLogCompletedWhenMonthPlanStatisticsMissing() throws Exception {
        LhBaseDataServiceImpl service = buildServiceWithDefaultMocks();
        injectField(service, "monthPlanStatisticsMapper", mockMapper(MpMonthPlanStatisticsMapper.class));
        injectField(service, "lhDataInitExecutor", (Executor) Runnable::run);
        ListAppender<ILoggingEvent> appender = attachAppender();
        try {
            service.loadAllBaseData(buildContext());

            List<String> messageList = new ArrayList<String>(appender.list.size());
            for (ILoggingEvent event : appender.list) {
                messageList.add(event.getFormattedMessage());
            }
            Assertions.assertTrue(messageList.stream().anyMatch(message -> message.contains("月计划结构机台统计无数据")),
                    "统计缺失时应保留可对账告警");
            Assertions.assertTrue(messageList.stream().anyMatch(message -> message.contains("基础数据加载完成")),
                    "统计缺失时应继续完成基础数据加载");
        } finally {
            detachAppender(appender);
        }
    }

    /**
     * 用例说明：月计划结构机台统计 dayN 非法JSON时按0处理并继续排程。
     *
     * @throws Exception 反射注入异常
     */
    @Test
    public void loadAllBaseDataShouldContinueWhenMonthPlanStatisticsDayJsonInvalid() throws Exception {
        LhBaseDataServiceImpl service = buildServiceWithDefaultMocks();
        MpMonthPlanStatistics statistics = new MpMonthPlanStatistics();
        statistics.setStructureName("STRUCT-001");
        statistics.setDay1("invalid-json");
        MpMonthPlanStatisticsMapper mapper = mockMapper(MpMonthPlanStatisticsMapper.class);
        Mockito.when(mapper.selectList(ArgumentMatchers.any()))
                .thenReturn(Collections.singletonList(statistics));
        injectField(service, "monthPlanStatisticsMapper", mapper);
        injectField(service, "lhDataInitExecutor", (Executor) Runnable::run);
        LhScheduleContext context = buildContext();

        service.loadAllBaseData(context);

        Assertions.assertFalse(context.isInterrupted(), "dayN非法JSON不应中断排程");
        Assertions.assertEquals(0,
                context.getStructurePlanMachineCount(LocalDate.of(2026, 1, 1), "STRUCT-001"),
                "dayN非法JSON时当前结构计划机台数应按0处理");
    }

    private LhBaseDataServiceImpl buildServiceWithDefaultMocks() throws Exception {
        LhBaseDataServiceImpl service = new LhBaseDataServiceImpl();
        injectField(service, "monthPlanMapper", mockMapper(FactoryMonthPlanProductionFinalResultMapper.class));
        injectField(service, "mpFactoryProductionVersionMapper", buildProductionVersionMapper());
        injectField(service, "monthPlanStatisticsMapper", buildMonthPlanStatisticsMapper());
        injectField(service, "workCalendarMapper", mockMapper(MdmWorkCalendarMapper.class));
        injectField(service, "skuLhCapacityMapper", mockMapper(MdmSkuLhCapacityMapper.class));
        injectField(service, "devicePlanShutMapper", mockMapper(MdmDevicePlanShutMapper.class));
        injectField(service, "skuMouldRelMapper", mockMapper(MdmSkuMouldRelMapper.class));
        injectField(service, "mdmModelInfoMapper", mockMapper(MdmModelInfoMapper.class));
        injectField(service, "lhMachineInfoMapper", buildMachineInfoMapper());
        injectField(service, "lhMouldCleanPlanMapper", mockMapper(LhMouldCleanPlanMapper.class));
        injectField(service, "lhDayFinishQtyMapper", mockMapper(LhDayFinishQtyMapper.class));
        injectField(service, "lhScheFinishQtyMapper", mockMapper(LhScheFinishQtyMapper.class));
        injectField(service, "mdmMaterialInfoMapper", mockMapper(MdmMaterialInfoMapper.class));
        injectField(service, "mdmCapsuleChuckMapper", mockMapper(MdmCapsuleChuckMapper.class));
        injectField(service, "lhMachineOnlineInfoMapper", mockMapper(LhMachineOnlineInfoMapper.class));
        injectField(service, "lhSpecifyMachineMapper", mockMapper(LhSpecifyMachineMapper.class));
        injectField(service, "lhRepairCapsuleMapper", mockMapper(LhRepairCapsuleMapper.class));
        injectField(service, "lhPrecisionPlanMapper", mockMapper(LhPrecisionPlanMapper.class));
        injectField(service, "lhScheduleResultMapper", mockMapper(LhScheduleResultMapper.class));
        injectField(service, "lhMouldChangePlanMapper", mockMapper(LhMouldChangePlanEntityMapper.class));
        injectField(service, "cxStockMapper", mockMapper(CxStockMapper.class));
        injectField(service, "lhSpecialMaterialBomEntityMapper", mockMapper(LhSpecialMaterialBomEntityMapper.class));
        injectField(service, "skuConstructionRefMapper", mockMapper(MdmSkuConstructionRefMapper.class));
        injectField(service, "skuScheduleCategoryMapper", mockMapper(MdmSkuScheduleCategoryMapper.class));
        injectField(service, "scheduleAdjustHandler", Mockito.mock(ScheduleAdjustHandler.class));
        return service;
    }

    private MpFactoryProductionVersionMapper buildProductionVersionMapper() {
        MpFactoryProductionVersionMapper mapper = mockMapper(MpFactoryProductionVersionMapper.class);
        Mockito.when(mapper.selectList(ArgumentMatchers.any()))
                .thenReturn(Collections.singletonList(buildProductionVersion("PV-001", "MP-001")));
        return mapper;
    }

    private MpFactoryProductionVersion buildProductionVersion(String productionVersion, String monthPlanVersion) {
        MpFactoryProductionVersion version = new MpFactoryProductionVersion();
        version.setProductionVersion(productionVersion);
        version.setMonthPlanVersion(monthPlanVersion);
        return version;
    }

    private FactoryMonthPlanProductionFinalResult monthPlan(String materialCode, int year, int month,
                                                            String monthPlanVersion, String productionVersion) {
        FactoryMonthPlanProductionFinalResult plan = new FactoryMonthPlanProductionFinalResult();
        plan.setFactoryCode("116");
        plan.setMaterialCode(materialCode);
        plan.setYear(year);
        plan.setMonth(month);
        plan.setMonthPlanVersion(monthPlanVersion);
        plan.setProductionVersion(productionVersion);
        plan.setEmbryoCode("EMB-" + month);
        plan.setStructureName("STRUCT-001");
        plan.setDay1(10);
        return plan;
    }

    private MpMonthPlanStatistics monthStatistics(String structureName) {
        MpMonthPlanStatistics statistics = new MpMonthPlanStatistics();
        statistics.setStructureName(structureName);
        statistics.setDay1("{\"lhMachines\":1}");
        return statistics;
    }

    private MpMonthPlanStatisticsMapper buildMonthPlanStatisticsMapper() {
        MpMonthPlanStatistics statistics = new MpMonthPlanStatistics();
        statistics.setStructureName("STRUCT-001");
        MpMonthPlanStatisticsMapper mapper = mockMapper(MpMonthPlanStatisticsMapper.class);
        Mockito.when(mapper.selectList(ArgumentMatchers.any())).thenReturn(Collections.singletonList(statistics));
        return mapper;
    }

    private LhMachineInfoMapper buildMachineInfoMapper() {
        LhMachineInfo machineInfo = new LhMachineInfo();
        machineInfo.setMachineCode("K001");
        LhMachineInfoMapper mapper = mockMapper(LhMachineInfoMapper.class);
        Mockito.when(mapper.selectList(ArgumentMatchers.any())).thenReturn(Collections.singletonList(machineInfo));
        return mapper;
    }

    private LhScheduleContext buildContext() {
        LhScheduleContext context = new LhScheduleContext();
        context.setFactoryCode("116");
        context.setFactoryName("越南");
        context.setMonthPlanVersion("MP-001");
        context.setScheduleDate(new Date(1767110400000L));
        context.setScheduleTargetDate(new Date(1767283200000L));
        context.setScheduleConfig(new LhScheduleConfig(new HashMap<>(16)));
        return context;
    }

    private LhScheduleConfig scheduleConfigWithEarlyProductionDays(int threshold) {
        HashMap<String, String> paramMap = new HashMap<String, String>(16);
        paramMap.put(LhScheduleParamConstant.EARLY_PRODUCTION_DAYS_THRESHOLD, String.valueOf(threshold));
        return new LhScheduleConfig(paramMap);
    }

    private Date buildDate(int year, int month, int day) {
        Calendar calendar = Calendar.getInstance();
        calendar.clear();
        calendar.set(Calendar.YEAR, year);
        calendar.set(Calendar.MONTH, month - 1);
        calendar.set(Calendar.DAY_OF_MONTH, day);
        return calendar.getTime();
    }

    private LhDayFinishQty buildDayFinishQty(String materialCode, int year, int month,
                                             int day, BigDecimal finishedQty) {
        return buildDayFinishQty(materialCode, null, year, month, day, finishedQty);
    }

    private LhDayFinishQty buildDayFinishQty(String materialCode, String lhType, int year, int month,
                                             int day, BigDecimal finishedQty) {
        LhDayFinishQty result = new LhDayFinishQty();
        result.setMaterialCode(materialCode);
        result.setLhType(lhType);
        result.setFinishDate(buildDate(year, month, day));
        result.setDayFinishQty(finishedQty);
        return result;
    }

    private void injectField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private ListAppender<ILoggingEvent> attachAppender() {
        Logger logger = (Logger) LoggerFactory.getLogger(LhBaseDataServiceImpl.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private void detachAppender(ListAppender<ILoggingEvent> appender) {
        Logger logger = (Logger) LoggerFactory.getLogger(LhBaseDataServiceImpl.class);
        logger.detachAppender(appender);
        appender.stop();
    }

    private <T extends BaseMapper<?>> T mockMapper(Class<T> mapperClass) {
        T mapper = Mockito.mock(mapperClass);
        Mockito.when(mapper.selectList(ArgumentMatchers.any())).thenReturn(new ArrayList<>());
        return mapper;
    }

}
