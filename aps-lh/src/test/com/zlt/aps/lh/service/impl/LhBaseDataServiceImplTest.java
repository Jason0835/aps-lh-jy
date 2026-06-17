package com.zlt.aps.lh.service.impl;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zlt.aps.cx.api.domain.entity.CxStock;
import com.zlt.aps.lh.api.domain.entity.LhDayFinishQty;
import com.zlt.aps.lh.api.domain.entity.LhMachineInfo;
import com.zlt.aps.lh.context.LhScheduleConfig;
import com.zlt.aps.lh.context.LhScheduleContext;
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
import com.zlt.aps.lh.mapper.MdmMonthSurplusMapper;
import com.zlt.aps.lh.mapper.MdmSkuConstructionRefMapper;
import com.zlt.aps.lh.mapper.MdmSkuLhCapacityMapper;
import com.zlt.aps.lh.mapper.MdmSkuMouldRelMapper;
import com.zlt.aps.lh.mapper.MdmWorkCalendarMapper;
import com.zlt.aps.lh.mapper.MpAdjustResultMapper;
import com.zlt.aps.lh.mapper.MpFactoryProductionVersionMapper;
import com.zlt.aps.lh.mapper.MpMonthPlanStatisticsMapper;
import com.zlt.aps.mdm.api.domain.entity.MdmModelInfo;
import com.zlt.aps.mdm.api.domain.entity.MdmSkuMouldRel;
import com.zlt.aps.mp.api.domain.entity.FactoryMonthPlanProductionFinalResult;
import com.zlt.aps.mp.api.domain.entity.MpFactoryProductionVersion;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
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

    private LhBaseDataServiceImpl buildServiceWithDefaultMocks() throws Exception {
        LhBaseDataServiceImpl service = new LhBaseDataServiceImpl();
        injectField(service, "monthPlanMapper", mockMapper(FactoryMonthPlanProductionFinalResultMapper.class));
        injectField(service, "mpFactoryProductionVersionMapper", buildProductionVersionMapper());
        injectField(service, "mpAdjustResultMapper", mockMapper(MpAdjustResultMapper.class));
        injectField(service, "monthPlanStatisticsMapper", mockMapper(MpMonthPlanStatisticsMapper.class));
        injectField(service, "workCalendarMapper", mockMapper(MdmWorkCalendarMapper.class));
        injectField(service, "skuLhCapacityMapper", mockMapper(MdmSkuLhCapacityMapper.class));
        injectField(service, "devicePlanShutMapper", mockMapper(MdmDevicePlanShutMapper.class));
        injectField(service, "skuMouldRelMapper", mockMapper(MdmSkuMouldRelMapper.class));
        injectField(service, "mdmModelInfoMapper", mockMapper(MdmModelInfoMapper.class));
        injectField(service, "lhMachineInfoMapper", buildMachineInfoMapper());
        injectField(service, "lhMouldCleanPlanMapper", mockMapper(LhMouldCleanPlanMapper.class));
        injectField(service, "monthSurplusMapper", mockMapper(MdmMonthSurplusMapper.class));
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
        return service;
    }

    private MpFactoryProductionVersionMapper buildProductionVersionMapper() {
        MpFactoryProductionVersion version = new MpFactoryProductionVersion();
        version.setProductionVersion("PV-001");
        MpFactoryProductionVersionMapper mapper = mockMapper(MpFactoryProductionVersionMapper.class);
        Mockito.when(mapper.selectList(ArgumentMatchers.any())).thenReturn(Collections.singletonList(version));
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

    private Date buildDate(int year, int month, int day) {
        Calendar calendar = Calendar.getInstance();
        calendar.clear();
        calendar.set(Calendar.YEAR, year);
        calendar.set(Calendar.MONTH, month - 1);
        calendar.set(Calendar.DAY_OF_MONTH, day);
        return calendar.getTime();
    }

    private void injectField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private <T extends BaseMapper<?>> T mockMapper(Class<T> mapperClass) {
        T mapper = Mockito.mock(mapperClass);
        Mockito.when(mapper.selectList(ArgumentMatchers.any())).thenReturn(new ArrayList<>());
        return mapper;
    }
}
