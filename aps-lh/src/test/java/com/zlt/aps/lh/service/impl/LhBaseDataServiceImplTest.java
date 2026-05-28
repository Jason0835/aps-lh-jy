package com.zlt.aps.lh.service.impl;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zlt.aps.cx.api.domain.entity.CxStock;
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
import com.zlt.aps.mp.api.domain.entity.FactoryMonthPlanProductionFinalResult;
import com.zlt.aps.mp.api.domain.entity.MpFactoryProductionVersion;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.concurrent.Executor;
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

    private LhBaseDataServiceImpl buildServiceWithDefaultMocks() throws Exception {
        LhBaseDataServiceImpl service = new LhBaseDataServiceImpl();
        injectField(service, "monthPlanMapper", mockMapper(FactoryMonthPlanProductionFinalResultMapper.class));
        injectField(service, "mpFactoryProductionVersionMapper", buildProductionVersionMapper());
        injectField(service, "mpAdjustResultMapper", mockMapper(MpAdjustResultMapper.class));
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
