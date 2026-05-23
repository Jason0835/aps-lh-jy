package com.zlt.aps.lh.engine.strategy.impl;

import com.zlt.aps.lh.api.constant.LhScheduleParamConstant;
import com.zlt.aps.lh.api.domain.dto.MachineScheduleDTO;
import com.zlt.aps.lh.api.domain.dto.SkuScheduleDTO;
import com.zlt.aps.lh.api.domain.entity.LhScheduleProcessLog;
import com.zlt.aps.lh.api.enums.ConstructionStageEnum;
import com.zlt.aps.lh.api.enums.JobTypeEnum;
import com.zlt.aps.lh.api.enums.LhSpecialMaterialCategoryEnum;
import com.zlt.aps.lh.api.enums.ScheduleStepEnum;
import com.zlt.aps.lh.context.LhScheduleConfig;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.engine.strategy.IEndingJudgmentStrategy;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 默认SKU优先级排序回归测试。
 */
class DefaultSkuPriorityStrategyTest {

    private final DefaultSkuPriorityStrategy strategy = new DefaultSkuPriorityStrategy();
    private final IEndingJudgmentStrategy endingJudgmentStrategy = mock(IEndingJudgmentStrategy.class);

    DefaultSkuPriorityStrategyTest() {
        ReflectionTestUtils.setField(strategy, "endingJudgmentStrategy", endingJudgmentStrategy);
    }

    @Test
    void sortByPriority_shouldPreferDeliveryLockedSku() {
        SkuScheduleDTO unlocked = sku("MAT-A");
        SkuScheduleDTO locked = sku("MAT-B");
        locked.setDeliveryLocked(true);

        LhScheduleContext context = contextWithNewSpec(unlocked, locked);

        strategy.sortByPriority(context);

        assertEquals("MAT-B", context.getNewSpecSkuList().get(0).getMaterialCode());
        assertEquals("MAT-A", context.getNewSpecSkuList().get(1).getMaterialCode());
    }

    @Test
    void sortByPriority_shouldPreferSmallerDelayDays() {
        SkuScheduleDTO delayMore = sku("MAT-A");
        delayMore.setDelayDays(-15);
        SkuScheduleDTO delayLess = sku("MAT-B");
        delayLess.setDelayDays(-3);

        LhScheduleContext context = contextWithNewSpec(delayMore, delayLess);

        strategy.sortByPriority(context);

        assertEquals("MAT-A", context.getNewSpecSkuList().get(0).getMaterialCode());
        assertEquals("MAT-B", context.getNewSpecSkuList().get(1).getMaterialCode());
    }

    @Test
    void sortByPriority_shouldPreferLatestEndingSkuWhenStructureAllEndingPriorityHits() {
        SkuScheduleDTO normal = sku("MAT-N");
        normal.setStructureName("S2");
        SkuScheduleDTO endingLate = sku("MAT-L");
        endingLate.setStructureName("S1");
        endingLate.setEndingDaysRemaining(4);
        SkuScheduleDTO endingEarly = sku("MAT-E");
        endingEarly.setStructureName("S1");
        endingEarly.setEndingDaysRemaining(2);

        when(endingJudgmentStrategy.isEnding(any(LhScheduleContext.class), same(endingLate))).thenReturn(true);
        when(endingJudgmentStrategy.isEnding(any(LhScheduleContext.class), same(endingEarly))).thenReturn(true);
        when(endingJudgmentStrategy.isEnding(any(LhScheduleContext.class), same(normal))).thenReturn(false);

        LhScheduleContext context = contextWithNewSpec(normal, endingEarly, endingLate);
        Map<String, List<SkuScheduleDTO>> structureSkuMap = new LinkedHashMap<>();
        structureSkuMap.put("S1", Arrays.asList(endingEarly, endingLate));
        structureSkuMap.put("S2", Collections.singletonList(normal));
        context.setStructureSkuMap(structureSkuMap);
        context.setScheduleConfig(new LhScheduleConfig(Collections.singletonMap(
                LhScheduleParamConstant.STRUCTURE_ENDING_DAYS, "5")));

        strategy.sortByPriority(context);

        assertEquals("MAT-L", context.getNewSpecSkuList().get(0).getMaterialCode());
        assertEquals("MAT-E", context.getNewSpecSkuList().get(1).getMaterialCode());
        assertEquals("MAT-N", context.getNewSpecSkuList().get(2).getMaterialCode());
    }

    @Test
    void sortByPriority_shouldFallbackToSupplyChainWhenStructureContainsNonEndingSku() {
        SkuScheduleDTO sku2022 = sku("3302002022");
        sku2022.setStructureName("S1");
        sku2022.setEndingDaysRemaining(2);
        sku2022.setHighPriorityPendingQty(50);
        SkuScheduleDTO sku1585 = sku("3302001585");
        sku1585.setStructureName("S1");
        sku1585.setEndingDaysRemaining(0);
        sku1585.setHighPriorityPendingQty(6480);

        when(endingJudgmentStrategy.isEnding(any(LhScheduleContext.class), same(sku2022))).thenReturn(true);
        when(endingJudgmentStrategy.isEnding(any(LhScheduleContext.class), same(sku1585))).thenReturn(false);

        LhScheduleContext context = contextWithNewSpec(sku2022, sku1585);
        Map<String, List<SkuScheduleDTO>> structureSkuMap = new LinkedHashMap<>();
        structureSkuMap.put("S1", Arrays.asList(sku2022, sku1585));
        context.setStructureSkuMap(structureSkuMap);
        context.setScheduleConfig(new LhScheduleConfig(Collections.singletonMap(
                LhScheduleParamConstant.STRUCTURE_ENDING_DAYS, "5")));

        strategy.sortByPriority(context);

        assertEquals("3302001585", context.getNewSpecSkuList().get(0).getMaterialCode());
        assertEquals("3302002022", context.getNewSpecSkuList().get(1).getMaterialCode());
    }

    @Test
    void sortByPriority_shouldUseCurrentPendingStructureAfterConsumedSkuRemoved() {
        SkuScheduleDTO consumedNonEnding = sku("MAT-C");
        consumedNonEnding.setStructureName("S1");
        consumedNonEnding.setHighPriorityPendingQty(999);

        SkuScheduleDTO endingSku = sku("MAT-E");
        endingSku.setStructureName("S1");
        endingSku.setEndingDaysRemaining(4);
        endingSku.setHighPriorityPendingQty(1);

        SkuScheduleDTO supplyChainFirst = sku("MAT-H");
        supplyChainFirst.setStructureName("S2");
        supplyChainFirst.setHighPriorityPendingQty(500);

        when(endingJudgmentStrategy.isEnding(any(LhScheduleContext.class), same(consumedNonEnding))).thenReturn(false);
        when(endingJudgmentStrategy.isEnding(any(LhScheduleContext.class), same(endingSku))).thenReturn(true);
        when(endingJudgmentStrategy.isEnding(any(LhScheduleContext.class), same(supplyChainFirst))).thenReturn(false);

        LhScheduleContext context = contextWithNewSpec(endingSku, supplyChainFirst);
        Map<String, List<SkuScheduleDTO>> structureSkuMap = new LinkedHashMap<>();
        structureSkuMap.put("S1", Arrays.asList(consumedNonEnding, endingSku));
        structureSkuMap.put("S2", Collections.singletonList(supplyChainFirst));
        context.setStructureSkuMap(structureSkuMap);
        context.setScheduleConfig(new LhScheduleConfig(Collections.singletonMap(
                LhScheduleParamConstant.STRUCTURE_ENDING_DAYS, "5")));

        // 模拟 S4.4 已消费同结构非收尾SKU，structureSkuMap 需同步为当前待排视图。
        context.removePendingSkuFromStructureMap(consumedNonEnding);

        strategy.sortByPriority(context);

        assertEquals("MAT-E", context.getNewSpecSkuList().get(0).getMaterialCode());
        assertEquals("MAT-H", context.getNewSpecSkuList().get(1).getMaterialCode());
    }

    @Test
    void sortByPriority_shouldFallbackToDefaultStructureEndingDaysWhenConfigMissing() {
        SkuScheduleDTO normal = sku("MAT-N");
        normal.setStructureName("S2");
        SkuScheduleDTO ending = sku("MAT-E");
        ending.setStructureName("S1");
        ending.setEndingDaysRemaining(4);

        when(endingJudgmentStrategy.isEnding(any(LhScheduleContext.class), same(ending))).thenReturn(true);
        when(endingJudgmentStrategy.isEnding(any(LhScheduleContext.class), same(normal))).thenReturn(false);

        LhScheduleContext context = contextWithNewSpec(normal, ending);
        Map<String, List<SkuScheduleDTO>> structureSkuMap = new LinkedHashMap<>();
        structureSkuMap.put("S1", Collections.singletonList(ending));
        structureSkuMap.put("S2", Collections.singletonList(normal));
        context.setStructureSkuMap(structureSkuMap);

        // 不设置 scheduleConfig，验证走默认 5 天阈值。
        strategy.sortByPriority(context);

        assertEquals("MAT-E", context.getNewSpecSkuList().get(0).getMaterialCode());
        assertEquals("MAT-N", context.getNewSpecSkuList().get(1).getMaterialCode());
    }

    @Test
    void sortByPriority_shouldFallbackToSupplyChainWhenLatestEndingDaysTied() {
        SkuScheduleDTO highLarge = sku("MAT-H1");
        highLarge.setStructureName("S1");
        highLarge.setEndingDaysRemaining(2);
        highLarge.setHighPriorityPendingQty(100);
        SkuScheduleDTO highSmall = sku("MAT-H2");
        highSmall.setStructureName("S1");
        highSmall.setEndingDaysRemaining(2);
        highSmall.setHighPriorityPendingQty(50);

        when(endingJudgmentStrategy.isEnding(any(LhScheduleContext.class), same(highLarge))).thenReturn(true);
        when(endingJudgmentStrategy.isEnding(any(LhScheduleContext.class), same(highSmall))).thenReturn(true);

        LhScheduleContext context = contextWithNewSpec(highSmall, highLarge);
        Map<String, List<SkuScheduleDTO>> structureSkuMap = new LinkedHashMap<>();
        structureSkuMap.put("S1", Arrays.asList(highLarge, highSmall));
        context.setStructureSkuMap(structureSkuMap);
        context.setScheduleConfig(new LhScheduleConfig(Collections.singletonMap(
                LhScheduleParamConstant.STRUCTURE_ENDING_DAYS, "5")));

        strategy.sortByPriority(context);

        assertEquals("MAT-H1", context.getNewSpecSkuList().get(0).getMaterialCode());
        assertEquals("MAT-H2", context.getNewSpecSkuList().get(1).getMaterialCode());
    }

    @Test
    void sortByPriority_shouldCompareSupplyChainPendingQtyStepByStep() {
        SkuScheduleDTO cycleHigh = sku("MAT-C");
        cycleHigh.setCycleProductionPendingQty(10);
        SkuScheduleDTO highSmall = sku("MAT-H1");
        highSmall.setHighPriorityPendingQty(5);
        SkuScheduleDTO highLarge = sku("MAT-H2");
        highLarge.setHighPriorityPendingQty(5);
        highLarge.setCycleProductionPendingQty(8);

        LhScheduleContext context = contextWithNewSpec(cycleHigh, highSmall, highLarge);

        strategy.sortByPriority(context);

        assertEquals("MAT-H2", context.getNewSpecSkuList().get(0).getMaterialCode());
        assertEquals("MAT-H1", context.getNewSpecSkuList().get(1).getMaterialCode());
        assertEquals("MAT-C", context.getNewSpecSkuList().get(2).getMaterialCode());
    }

    @Test
    void sortByPriority_shouldKeepDeliveryLockAndDelayPriorityBeforeStructureAllEndingRule() {
        SkuScheduleDTO locked = sku("MAT-L");
        locked.setStructureName("S2");
        locked.setDeliveryLocked(true);
        locked.setDelayDays(0);
        locked.setHighPriorityPendingQty(1);

        SkuScheduleDTO delayHigh = sku("MAT-D");
        delayHigh.setStructureName("S2");
        delayHigh.setDelayDays(5);
        delayHigh.setHighPriorityPendingQty(1);

        SkuScheduleDTO structurePriority = sku("MAT-P");
        structurePriority.setStructureName("S1");
        structurePriority.setDelayDays(0);
        structurePriority.setEndingDaysRemaining(4);
        structurePriority.setHighPriorityPendingQty(9999);

        when(endingJudgmentStrategy.isEnding(any(LhScheduleContext.class), same(locked))).thenReturn(false);
        when(endingJudgmentStrategy.isEnding(any(LhScheduleContext.class), same(delayHigh))).thenReturn(false);
        when(endingJudgmentStrategy.isEnding(any(LhScheduleContext.class), same(structurePriority))).thenReturn(true);

        LhScheduleContext context = contextWithNewSpec(structurePriority, delayHigh, locked);
        Map<String, List<SkuScheduleDTO>> structureSkuMap = new LinkedHashMap<>();
        structureSkuMap.put("S1", Collections.singletonList(structurePriority));
        structureSkuMap.put("S2", Arrays.asList(locked, delayHigh));
        context.setStructureSkuMap(structureSkuMap);
        context.setScheduleConfig(new LhScheduleConfig(Collections.singletonMap(
                LhScheduleParamConstant.STRUCTURE_ENDING_DAYS, "5")));

        strategy.sortByPriority(context);

        assertEquals("MAT-L", context.getNewSpecSkuList().get(0).getMaterialCode());
        assertEquals("MAT-P", context.getNewSpecSkuList().get(1).getMaterialCode());
        assertEquals("MAT-D", context.getNewSpecSkuList().get(2).getMaterialCode());
    }

    @Test
    void sortByPriority_shouldWriteContinuousPriorityTraceLogWhenEnabled() {
        SkuScheduleDTO normal = sku("MAT-N");
        normal.setDelayDays(1);
        SkuScheduleDTO locked = sku("MAT-L");
        locked.setDeliveryLocked(true);

        LhScheduleContext context = new LhScheduleContext();
        context.setFactoryCode("116");
        context.setBatchNo("TRACE-BATCH");
        context.setCurrentStep(ScheduleStepEnum.S4_4_CONTINUOUS_PRODUCTION.getCode());
        context.setScheduleConfig(new LhScheduleConfig(Collections.singletonMap(
                LhScheduleParamConstant.ENABLE_PRIORITY_TRACE_LOG, "1")));
        context.setContinuousSkuList(new java.util.ArrayList<>(Arrays.asList(normal, locked)));

        strategy.sortByPriority(context);

        assertEquals("MAT-L", context.getContinuousSkuList().get(0).getMaterialCode());
        assertEquals(1, context.getScheduleLogList().size());
        LhScheduleProcessLog processLog = context.getScheduleLogList().get(0);
        assertEquals("SKU排序优先级汇总【续作】", processLog.getTitle());
        assertTrue(processLog.getLogDetail().contains("MAT-L"));
        assertTrue(processLog.getLogDetail().contains("MAT-N"));
        assertTrue(processLog.getLogDetail().contains("锁交期"));
        assertTrue(processLog.getLogDetail().contains("结构全收尾"));
        assertTrue(processLog.getLogDetail().indexOf("MAT-L") < processLog.getLogDetail().indexOf("MAT-N"));
    }

    @Test
    void sortByPriority_shouldMoveOpenProductionRestrictedSkuBehindWhenPriorityTied() {
        SkuScheduleDTO winter = sku("MAT-A");
        winter.setPattern("雪地");
        winter.setProSize("17");
        SkuScheduleDTO differentInch = sku("MAT-B");
        differentInch.setProSize("18");
        SkuScheduleDTO specialMaterial = sku("MAT-C");
        specialMaterial.setProSize("17");
        SkuScheduleDTO normal = sku("MAT-Z");
        normal.setProSize("17");

        LhScheduleContext context = contextWithNewSpec(winter, differentInch, specialMaterial, normal);
        context.setOpenProductionMode(true);
        context.setScheduleConfig(new LhScheduleConfig(Collections.singletonMap(
                LhScheduleParamConstant.OPEN_PRODUCTION_WINTER_TIRE_KEYWORDS, "雪地")));
        MachineScheduleDTO machine = new MachineScheduleDTO();
        machine.setMachineCode("K01");
        machine.setPreviousProSize("17");
        context.setMachineScheduleMap(new LinkedHashMap<String, MachineScheduleDTO>());
        context.getMachineScheduleMap().put(machine.getMachineCode(), machine);
        context.getSpecialMaterialCategoryByMaterialCode().put("MAT-C", Collections.singleton("01"));

        strategy.sortByPriority(context);

        assertEquals("MAT-Z", context.getNewSpecSkuList().get(0).getMaterialCode());
        assertEquals("MAT-A", context.getNewSpecSkuList().get(1).getMaterialCode());
        assertEquals("MAT-B", context.getNewSpecSkuList().get(2).getMaterialCode());
        assertEquals("MAT-C", context.getNewSpecSkuList().get(3).getMaterialCode());
    }

    @Test
    void sortByPriority_shouldNotPreferTrialOrSmallBatchBeforeFormalSku() {
        SkuScheduleDTO normal = sku("MAT-N");
        normal.setHighPriorityPendingQty(999);
        SkuScheduleDTO smallBatch = sku("MAT-S");
        smallBatch.setSmallBatchValidation(true);
        SkuScheduleDTO trial = sku("MAT-T");
        trial.setTrial(true);
        SkuScheduleDTO specify = sku("MAT-P");

        LhScheduleContext context = contextWithNewSpec(normal, smallBatch, trial, specify);
        context.setScheduleConfig(new LhScheduleConfig(Collections.singletonMap(
                LhScheduleParamConstant.ENABLE_SPECIFY_MACHINE_RULE, "1")));
        context.getSpecifyMachineMap().put("MAT-P", Collections.singletonList(new com.zlt.aps.lh.api.domain.entity.LhSpecifyMachine()));
        context.getSpecifyMachineMap().get("MAT-P").get(0).setSpecCode("MAT-P");
        context.getSpecifyMachineMap().get("MAT-P").get(0).setJobType(JobTypeEnum.RESTRICTED.getCode());
        context.getSpecifyMachineMap().get("MAT-P").get(0).setMachineCode("K1501L");

        strategy.sortByPriority(context);

        assertEquals("MAT-P", context.getNewSpecSkuList().get(0).getMaterialCode());
        assertEquals("MAT-N", context.getNewSpecSkuList().get(1).getMaterialCode(),
                "正规SKU高优待排量更高时，不应被试制或小批量提前");
        assertEquals("MAT-T", context.getNewSpecSkuList().get(2).getMaterialCode(),
                "前置排序条件相同时，应按试制 > 量试 > 小批量 > 正规补充排序");
        assertEquals("MAT-S", context.getNewSpecSkuList().get(3).getMaterialCode());
    }

    @Test
    void sortByPriority_shouldNotPreferTrialConstructionStageBeforeMassTrial() {
        SkuScheduleDTO massTrial = sku("3302002637");
        massTrial.setConstructionStage(ConstructionStageEnum.MASS_TRIAL.getCode());
        massTrial.setHighPriorityPendingQty(999);
        SkuScheduleDTO trial = sku("3302002216");
        trial.setConstructionStage(ConstructionStageEnum.TRIAL.getCode());

        LhScheduleContext context = contextWithNewSpec(massTrial, trial);

        strategy.sortByPriority(context);

        assertEquals("3302002637", context.getNewSpecSkuList().get(0).getMaterialCode(),
                "试制和量试不再作为排序优先层级，应继续按既有待排量等规则排序");
        assertEquals("3302002216", context.getNewSpecSkuList().get(1).getMaterialCode());
    }

    @Test
    void sortByPriority_shouldUseSkuTypeAsTieBreakerWhenAllPrimaryKeysEqual() {
        SkuScheduleDTO formal = sku("MAT-F");
        SkuScheduleDTO smallBatch = sku("MAT-S");
        smallBatch.setSmallBatchValidation(true);
        SkuScheduleDTO massTrial = sku("MAT-M");
        massTrial.setConstructionStage(ConstructionStageEnum.MASS_TRIAL.getCode());
        SkuScheduleDTO trial = sku("MAT-T");
        trial.setConstructionStage(ConstructionStageEnum.TRIAL.getCode());

        LhScheduleContext context = contextWithNewSpec(formal, smallBatch, massTrial, trial);

        strategy.sortByPriority(context);

        assertEquals("MAT-T", context.getNewSpecSkuList().get(0).getMaterialCode());
        assertEquals("MAT-M", context.getNewSpecSkuList().get(1).getMaterialCode());
        assertEquals("MAT-S", context.getNewSpecSkuList().get(2).getMaterialCode());
        assertEquals("MAT-F", context.getNewSpecSkuList().get(3).getMaterialCode());
    }

    @Test
    void sortByPriority_shouldKeepPrimarySortBeforeSkuTypeTieBreaker() {
        SkuScheduleDTO trial = sku("MAT-T");
        trial.setConstructionStage(ConstructionStageEnum.TRIAL.getCode());
        trial.setDelayDays(3);
        SkuScheduleDTO formal = sku("MAT-F");
        formal.setDelayDays(5);

        LhScheduleContext context = contextWithNewSpec(trial, formal);

        strategy.sortByPriority(context);

        assertEquals("MAT-T", context.getNewSpecSkuList().get(0).getMaterialCode(),
                "SKU类型只能作为同层级补充排序，不能覆盖更高优先级主排序条件");
        assertEquals("MAT-F", context.getNewSpecSkuList().get(1).getMaterialCode());
    }

    @Test
    void sortByPriority_shouldPreferStructureEndingBeforeTrialInNewSpecSort() {
        SkuScheduleDTO trialSku = sku("3302001575");
        trialSku.setStructureName("结构A");
        trialSku.setTrial(true);
        trialSku.setEndingDaysRemaining(1);

        SkuScheduleDTO endingSku = sku("3302001724");
        endingSku.setStructureName("结构B");
        endingSku.setEndingDaysRemaining(3);

        when(endingJudgmentStrategy.isEnding(any(LhScheduleContext.class), same(trialSku))).thenReturn(false);
        when(endingJudgmentStrategy.isEnding(any(LhScheduleContext.class), same(endingSku))).thenReturn(true);

        LhScheduleContext context = contextWithNewSpec(trialSku, endingSku);
        Map<String, List<SkuScheduleDTO>> structureSkuMap = new LinkedHashMap<>();
        structureSkuMap.put("结构A", Collections.singletonList(trialSku));
        structureSkuMap.put("结构B", Collections.singletonList(endingSku));
        context.setStructureSkuMap(structureSkuMap);
        context.setScheduleConfig(new LhScheduleConfig(Collections.singletonMap(
                LhScheduleParamConstant.STRUCTURE_ENDING_DAYS, "5")));

        strategy.sortByPriority(context);

        assertEquals("3302001724", context.getNewSpecSkuList().get(0).getMaterialCode());
        assertEquals("3302001575", context.getNewSpecSkuList().get(1).getMaterialCode());
    }

    @Test
    void sortByPriority_shouldPreferSpecialSkuWithSingleCandidateMachine() {
        SkuScheduleDTO normalSku = sku("A-NORMAL");
        normalSku.setStructureName("结构普通");
        SkuScheduleDTO specialSku = sku("Z-SPECIAL");
        specialSku.setStructureName("结构特殊");

        when(endingJudgmentStrategy.isEnding(any(LhScheduleContext.class), same(normalSku))).thenReturn(false);
        when(endingJudgmentStrategy.isEnding(any(LhScheduleContext.class), same(specialSku))).thenReturn(false);

        LhScheduleContext context = contextWithNewSpec(normalSku, specialSku);
        Map<String, List<SkuScheduleDTO>> structureSkuMap = new LinkedHashMap<>();
        structureSkuMap.put("结构普通", Collections.singletonList(normalSku));
        structureSkuMap.put("结构特殊", Collections.singletonList(specialSku));
        context.setStructureSkuMap(structureSkuMap);
        context.getSpecialMaterialCategoryByMaterialCode().put("Z-SPECIAL",
                new HashSet<String>(Collections.singletonList(LhSpecialMaterialCategoryEnum.CHIP_TIRE.getCode())));
        context.getMachineScheduleMap().put("K1105", machine("K1105", "0", "0", "0"));
        context.getMachineScheduleMap().put("K1001", machine("K1001", "0", "0", "1"));

        strategy.sortByPriority(context);

        assertEquals("Z-SPECIAL", context.getNewSpecSkuList().get(0).getMaterialCode());
        assertEquals("A-NORMAL", context.getNewSpecSkuList().get(1).getMaterialCode());
    }

    private LhScheduleContext contextWithNewSpec(SkuScheduleDTO... skus) {
        LhScheduleContext context = new LhScheduleContext();
        context.setNewSpecSkuList(new java.util.ArrayList<>(Arrays.asList(skus)));
        return context;
    }

    private SkuScheduleDTO sku(String materialCode) {
        SkuScheduleDTO sku = new SkuScheduleDTO();
        sku.setMaterialCode(materialCode);
        sku.setDelayDays(0);
        sku.setStructureName("DEFAULT");
        return sku;
    }

    private MachineScheduleDTO machine(String machineCode,
                                       String support195,
                                       String support225,
                                       String supportChip) {
        MachineScheduleDTO machine = new MachineScheduleDTO();
        machine.setMachineCode(machineCode);
        machine.setStatus("1");
        machine.setDimensionMinimum(new BigDecimal("15"));
        machine.setDimensionMaximum(new BigDecimal("30"));
        machine.setSupport195WideBase(support195);
        machine.setSupport225WideBase(support225);
        machine.setSupportChipTire(supportChip);
        return machine;
    }
}
