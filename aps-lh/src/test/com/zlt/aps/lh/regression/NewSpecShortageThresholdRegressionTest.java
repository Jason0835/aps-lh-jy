package com.zlt.aps.lh.regression;

import com.zlt.aps.lh.api.domain.dto.MachineScheduleDTO;
import com.zlt.aps.lh.api.domain.dto.SkuDailyPlanQuotaDTO;
import com.zlt.aps.lh.api.domain.dto.SkuScheduleDTO;
import com.zlt.aps.lh.api.domain.entity.LhScheduleResult;
import com.zlt.aps.lh.component.OrderNoGenerator;
import com.zlt.aps.lh.api.constant.LhScheduleParamConstant;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.engine.strategy.ICapacityCalculateStrategy;
import com.zlt.aps.lh.engine.strategy.IEndingJudgmentStrategy;
import com.zlt.aps.lh.engine.strategy.IFirstInspectionBalanceStrategy;
import com.zlt.aps.lh.engine.strategy.IMachineMatchStrategy;
import com.zlt.aps.lh.engine.strategy.IMouldChangeBalanceStrategy;
import com.zlt.aps.lh.engine.strategy.impl.LocalSearchMachineAllocatorStrategy;
import com.zlt.aps.lh.engine.strategy.impl.NewSpecProductionStrategy;
import com.zlt.aps.lh.api.domain.vo.LhShiftConfigVO;
import com.zlt.aps.lh.api.enums.ConstructionStageEnum;
import com.zlt.aps.lh.component.TargetScheduleQtyResolver;
import com.zlt.aps.lh.context.LhScheduleConfig;
import com.zlt.aps.lh.util.LhScheduleTimeUtil;
import com.zlt.aps.lh.util.MouldStatusUtil;
import com.zlt.aps.mdm.api.domain.entity.MdmMaterialInfo;
import com.zlt.aps.mdm.api.domain.entity.MdmModelInfo;
import com.zlt.aps.mdm.api.domain.entity.MdmSkuMouldRel;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 新增排产欠产阈值增机台回归：
 * 直接从 S4.5 入口验证“窗口后剩余欠产回到阈值以内即停止”，
 * 避免只在公共模拟器层有断言，遗漏候选排序和真实排产链路回归。
 */
class NewSpecShortageThresholdRegressionTest {

    @Test
    void scheduleNewSpecs_shouldStopAddingMachineWhenWindowRemainingShortageBackToThreshold() throws Exception {
        NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
        injectDependencies(strategy, false);

        LhScheduleContext context = buildContext();
        Date scheduleDate = dateTime(2026, 5, 9, 0, 0);
        context.setScheduleDate(scheduleDate);
        context.setScheduleTargetDate(dateTime(2026, 5, 11, 0, 0));
        context.setScheduleWindowShifts(LhScheduleTimeUtil.buildDefaultScheduleShifts(context, scheduleDate));
        context.setScheduleConfig(new LhScheduleConfig(Collections.singletonMap(
                LhScheduleParamConstant.NEW_SPEC_SHORTAGE_ADD_MACHINE_THRESHOLD, "150")));

        SkuScheduleDTO sku = buildSku();
        sku.setMaterialCode("3302001592");
        sku.setMaterialDesc("3302001592");
        sku.setConstructionStage(ConstructionStageEnum.FORMAL.getCode());
        sku.setLhTimeSeconds(3600);
        sku.setShiftCapacity(16);
        sku.setMouldQty(1);
        sku.setPendingQty(1256);
        sku.setTargetScheduleQty(336);
        sku.setWindowPlanQty(144);
        sku.setWindowRemainingPlanQty(144);
        sku.setSurplusQty(1256);
        sku.setEmbryoStock(-1);
        sku.setMonthlyHistoryShortageQty(192);
        sku.setScheduleDayFinishQty(0);
        sku.setDailyPlanQuotaMap(buildThreeDayQuotaMap(
                context.getScheduleWindowShifts(), sku.getMaterialCode(), 48, 48, 48));
        context.getNewSpecSkuList().add(sku);
        enableMoulds(context, sku.getMaterialCode(), "MOULD-1", "MOULD-2", "MOULD-3");

        MachineScheduleDTO k1115 = buildMachine("K1115", dateTime(2026, 5, 9, 6, 0));
        MachineScheduleDTO k1116 = buildMachine("K1116", dateTime(2026, 5, 9, 6, 0));
        MachineScheduleDTO k1117 = buildMachine("K1117", dateTime(2026, 5, 9, 6, 0));

        strategy.scheduleNewSpecs(context, orderedMachineMatch(k1115, k1116, k1117),
                defaultMouldChangeBalance(), defaultInspectionBalance(), defaultCapacityCalculate());

        assertEquals(2, context.getScheduleResultList().size(),
                "窗口后剩余欠产回到阈值以内时，应停止继续增加第三台机台");
        assertEquals("K1115", context.getScheduleResultList().get(0).getLhMachineCode());
        assertEquals("K1116", context.getScheduleResultList().get(1).getLhMachineCode());
        assertFalse(containsMachine(context.getScheduleResultList(), "K1117"),
                "候选机台排序保留不变时，满足阈值回落后不应继续尝试第三台浅排机台");
    }

    private void injectDependencies(NewSpecProductionStrategy strategy, boolean isEnding) throws Exception {
        OrderNoGenerator orderNoGenerator = new OrderNoGenerator();

        Field useRedisField = OrderNoGenerator.class.getDeclaredField("useRedis");
        useRedisField.setAccessible(true);
        useRedisField.set(orderNoGenerator, false);

        Field generatorField = NewSpecProductionStrategy.class.getDeclaredField("orderNoGenerator");
        generatorField.setAccessible(true);
        generatorField.set(strategy, orderNoGenerator);

        IEndingJudgmentStrategy endingJudgmentStrategy = new IEndingJudgmentStrategy() {
            @Override
            public boolean isEnding(LhScheduleContext context, SkuScheduleDTO sku) {
                return isEnding;
            }

            @Override
            public int calculateEndingShifts(LhScheduleContext context, SkuScheduleDTO sku) {
                return isEnding ? 1 : 0;
            }

            @Override
            public int calculateEndingDays(LhScheduleContext context, SkuScheduleDTO sku) {
                return isEnding ? 1 : 0;
            }
        };
        Field endingField = NewSpecProductionStrategy.class.getDeclaredField("endingJudgmentStrategy");
        endingField.setAccessible(true);
        endingField.set(strategy, endingJudgmentStrategy);

        Field targetResolverField = NewSpecProductionStrategy.class.getDeclaredField("targetScheduleQtyResolver");
        targetResolverField.setAccessible(true);
        targetResolverField.set(strategy, new TargetScheduleQtyResolver());

        Field localSearchField = NewSpecProductionStrategy.class.getDeclaredField("localSearchMachineAllocator");
        localSearchField.setAccessible(true);
        localSearchField.set(strategy, new LocalSearchMachineAllocatorStrategy());
    }

    private LhScheduleContext buildContext() {
        LhScheduleContext context = new LhScheduleContext();
        Date scheduleDate = dateTime(2026, 4, 17, 0, 0);
        context.setFactoryCode("116");
        context.setBatchNo("TEST-BATCH");
        context.setScheduleDate(scheduleDate);
        context.setScheduleTargetDate(scheduleDate);
        context.setScheduleWindowShifts(LhScheduleTimeUtil.buildDefaultScheduleShifts(context, scheduleDate));
        context.setMachineScheduleMap(new LinkedHashMap<String, MachineScheduleDTO>());
        context.setMachineAssignmentMap(new LinkedHashMap<String, List<LhScheduleResult>>());
        context.setMaterialInfoMap(new java.util.HashMap<String, MdmMaterialInfo>());
        context.setSkuMouldRelMap(new LinkedHashMap<String, List<MdmSkuMouldRel>>());
        context.setModelInfoMap(new LinkedHashMap<String, MdmModelInfo>());
        return context;
    }

    private SkuScheduleDTO buildSku() {
        SkuScheduleDTO sku = new SkuScheduleDTO();
        sku.setMaterialCode("MAT-1");
        sku.setMaterialDesc("测试物料");
        sku.setSpecCode("11R22.5");
        sku.setSpecDesc("11R22.5");
        sku.setProSize("22.5");
        sku.setLhTimeSeconds(3600);
        sku.setMouldQty(1);
        sku.setPendingQty(1);
        sku.setDailyPlanQty(1);
        return sku;
    }

    private Map<LocalDate, SkuDailyPlanQuotaDTO> buildThreeDayQuotaMap(List<LhShiftConfigVO> shifts,
                                                                       String materialCode,
                                                                       int firstDayQty,
                                                                       int secondDayQty,
                                                                       int thirdDayQty) {
        Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap = new LinkedHashMap<LocalDate, SkuDailyPlanQuotaDTO>(4);
        quotaMap.put(toLocalDate(shifts.get(0)), quota(materialCode, toLocalDate(shifts.get(0)), firstDayQty));
        quotaMap.put(toLocalDate(shifts.get(3)), quota(materialCode, toLocalDate(shifts.get(3)), secondDayQty));
        quotaMap.put(toLocalDate(shifts.get(6)), quota(materialCode, toLocalDate(shifts.get(6)), thirdDayQty));
        return quotaMap;
    }

    private SkuDailyPlanQuotaDTO quota(String materialCode, LocalDate productionDate, int dayPlanQty) {
        SkuDailyPlanQuotaDTO quota = new SkuDailyPlanQuotaDTO();
        quota.setMaterialCode(materialCode);
        quota.setProductionDate(productionDate);
        quota.setDayPlanQty(dayPlanQty);
        quota.setRemainingQty(dayPlanQty);
        return quota;
    }

    private LocalDate toLocalDate(LhShiftConfigVO shift) {
        return shift.getWorkDate().toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDate();
    }

    private MachineScheduleDTO buildMachine(String machineCode, Date estimatedEndTime) {
        MachineScheduleDTO machine = new MachineScheduleDTO();
        machine.setMachineCode(machineCode);
        machine.setMachineName(machineCode);
        machine.setStatus("1");
        machine.setMaxMoldNum(1);
        machine.setEstimatedEndTime(estimatedEndTime);
        machine.setPreviousSpecCode("11R22.5");
        machine.setPreviousProSize("22.5");
        machine.setPreviousMaterialCode("PREV-" + machineCode);
        return machine;
    }

    private void enableMoulds(LhScheduleContext context, String materialCode, String... mouldCodes) {
        List<MdmSkuMouldRel> relList = new java.util.ArrayList<MdmSkuMouldRel>(mouldCodes.length);
        for (String mouldCode : mouldCodes) {
            MdmSkuMouldRel rel = new MdmSkuMouldRel();
            rel.setMaterialCode(materialCode);
            rel.setMouldCode(mouldCode);
            relList.add(rel);

            MdmModelInfo modelInfo = new MdmModelInfo();
            modelInfo.setMouldCode(mouldCode);
            modelInfo.setMouldStatus(MouldStatusUtil.STATUS_ENABLED);
            context.getModelInfoMap().put(mouldCode, modelInfo);
        }
        context.getSkuMouldRelMap().put(materialCode, relList);
    }

    private IMachineMatchStrategy orderedMachineMatch(MachineScheduleDTO firstMachine,
                                                      MachineScheduleDTO secondMachine,
                                                      MachineScheduleDTO thirdMachine) {
        return new IMachineMatchStrategy() {
            @Override
            public List<MachineScheduleDTO> matchMachines(LhScheduleContext ctx, SkuScheduleDTO scheduleSku) {
                return Arrays.asList(firstMachine, secondMachine, thirdMachine);
            }

            @Override
            public MachineScheduleDTO selectBestMachine(LhScheduleContext ctx, SkuScheduleDTO scheduleSku,
                                                        List<MachineScheduleDTO> candidates,
                                                        Set<String> excludedMachineCodes) {
                for (MachineScheduleDTO candidate : candidates) {
                    if (candidate != null && !excludedMachineCodes.contains(candidate.getMachineCode())) {
                        return candidate;
                    }
                }
                return null;
            }

            @Override
            public void traceEnabledMachineSort(LhScheduleContext context) {
                // 测试桩，无需实现
            }
        };
    }

    private IMouldChangeBalanceStrategy defaultMouldChangeBalance() {
        return new IMouldChangeBalanceStrategy() {
            @Override
            public boolean hasCapacity(LhScheduleContext ctx, Date targetDate) {
                return true;
            }

            @Override
            public Date allocateMouldChange(LhScheduleContext ctx, String machineCode, Date endingTime) {
                return endingTime;
            }

            @Override
            public int getRemainingCapacity(LhScheduleContext ctx, Date targetDate) {
                return 99;
            }
        };
    }

    private IFirstInspectionBalanceStrategy defaultInspectionBalance() {
        return (ctx, machineCode, mouldChangeTime) -> mouldChangeTime;
    }

    private ICapacityCalculateStrategy defaultCapacityCalculate() {
        return new ICapacityCalculateStrategy() {
            @Override
            public int calculateShiftCapacity(LhScheduleContext ctx, int lhTimeSeconds, int mouldQty) {
                return 1;
            }

            @Override
            public Date calculateStartTime(LhScheduleContext ctx, String machineCode, Date endingTime) {
                return endingTime;
            }

            @Override
            public int calculateFirstShiftQty(Date startTime, Date shiftEndTime, int lhTimeSeconds, int mouldQty) {
                return startTime.before(shiftEndTime) ? 1 : 0;
            }

            @Override
            public int calculateDailyCapacity(int lhTimeSeconds, int mouldQty) {
                return 1;
            }
        };
    }

    private boolean containsMachine(List<LhScheduleResult> resultList, String machineCode) {
        for (LhScheduleResult result : resultList) {
            if (result != null && machineCode.equals(result.getLhMachineCode())) {
                return true;
            }
        }
        return false;
    }

    private static Date dateTime(int year, int month, int day, int hour, int minute) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(Calendar.YEAR, year);
        c.set(Calendar.MONTH, month - 1);
        c.set(Calendar.DAY_OF_MONTH, day);
        c.set(Calendar.HOUR_OF_DAY, hour);
        c.set(Calendar.MINUTE, minute);
        return c.getTime();
    }
}
