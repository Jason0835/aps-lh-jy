package com.zlt.aps.lh.engine.strategy.impl;

import com.zlt.aps.lh.api.domain.dto.MachineScheduleDTO;
import com.zlt.aps.lh.api.domain.dto.SkuDailyPlanQuotaDTO;
import com.zlt.aps.lh.api.domain.dto.SkuScheduleDTO;
import com.zlt.aps.lh.api.domain.entity.LhMachineOnlineInfo;
import com.zlt.aps.lh.api.domain.entity.LhScheduleResult;
import com.zlt.aps.lh.api.domain.vo.LhShiftConfigVO;
import com.zlt.aps.lh.api.enums.ConstructionStageEnum;
import com.zlt.aps.lh.api.enums.ShiftEnum;
import com.zlt.aps.lh.api.enums.SkuTagEnum;
import com.zlt.aps.lh.api.constant.LhScheduleParamConstant;
import com.zlt.aps.lh.component.OrderNoGenerator;
import com.zlt.aps.lh.component.TargetScheduleQtyResolver;
import com.zlt.aps.lh.context.LhScheduleConfig;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.engine.strategy.IEndingJudgmentStrategy;
import com.zlt.aps.lh.engine.strategy.support.DailyMachineExpansionPlanner;
import com.zlt.aps.lh.util.LhScheduleTimeUtil;
import com.zlt.aps.lh.util.ShiftFieldUtil;
import com.zlt.aps.lh.util.SkuDailyPlanQuotaUtil;
import com.zlt.aps.mdm.api.domain.entity.MdmSkuLhCapacity;
import com.zlt.aps.mp.api.domain.entity.FactoryMonthPlanProductionFinalResult;
import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.CollectionUtils;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ContinuousProductionStrategy 滚动衔接续作测试。
 *
 * @author APS
 */
public class ContinuousProductionStrategyTest {

    private static final ZoneId ZONE_ID = ZoneId.of("Asia/Shanghai");

    @Test
    public void applySingleMachineContinuousTargetRule_shouldUseEmbryoStockEndingForNonEndingSku() {
        ContinuousProductionStrategy strategy = new ContinuousProductionStrategy();
        LhScheduleContext context = new LhScheduleContext();
        context.getEmbryoIsEndMap().put("EMB-END-01", "1");
        SkuScheduleDTO sku = new SkuScheduleDTO();
        sku.setMaterialCode("3302005001");
        sku.setEmbryoCode("EMB-END-01");
        sku.setSurplusQty(80);
        sku.setEmbryoStock(5);
        sku.setTargetScheduleQty(80);
        sku.setRemainingScheduleQty(80);
        sku.setMouldQty(2);
        MachineScheduleDTO machine = new MachineScheduleDTO();
        machine.setMachineCode("K1501");
        machine.setMaxMoldNum(2);

        ReflectionTestUtils.invokeMethod(strategy, "applySingleMachineContinuousTargetRule",
                context, sku, machine, null, Collections.<LhShiftConfigVO>emptyList(), false, true, null);

        assertEquals(5, sku.resolveTargetScheduleQty());
        assertEquals(5, sku.getRemainingScheduleQty());
        assertTrue(sku.isStrictTargetQty());
    }

    @Test
    public void shouldNotSkipSmallEndingSurplusWhenEmbryoStockEndingIsConfigured() {
        ContinuousProductionStrategy strategy = new ContinuousProductionStrategy();
        LhScheduleContext context = new LhScheduleContext();
        context.getEmbryoIsEndMap().put("EMB-END-03", "1");
        SkuScheduleDTO sku = new SkuScheduleDTO();
        sku.setMaterialCode("3302005003");
        sku.setEmbryoCode("EMB-END-03");
        sku.setSurplusQty(1);
        sku.setEmbryoStock(5);
        sku.setShiftCapacity(16);

        Boolean originalSkip = ReflectionTestUtils.invokeMethod(strategy,
                "shouldSkipSmallEndingSurplusContinuous", context, sku, true);
        Boolean guardedSkip = ReflectionTestUtils.invokeMethod(strategy,
                "shouldSkipSmallEndingSurplusContinuousConsideringEmbryoEnding", context, sku, true);

        assertTrue(originalSkip, "旧SKU收尾小余量规则在该场景会命中");
        assertFalse(guardedSkip, "成型胎胚库存收尾应优先按胎胚库存排产，不能提前未排");
    }

    @Test
    public void selectPreferredSkuFromCandidates_shouldPickFirstForPriorityOneCandidates() {
        ContinuousProductionStrategy strategy = new ContinuousProductionStrategy();
        IEndingJudgmentStrategy endingJudgmentStrategy = mock(IEndingJudgmentStrategy.class);
        ReflectionTestUtils.setField(strategy, "endingJudgmentStrategy", endingJudgmentStrategy);

        SkuScheduleDTO sku1585 = sku("3302001585");
        SkuScheduleDTO sku2022 = sku("3302002022");
        List<SkuScheduleDTO> priorityOneCandidates = Arrays.asList(sku1585, sku2022);
        when(endingJudgmentStrategy.isCurrentWindowEnding(any(LhScheduleContext.class), same(sku2022))).thenReturn(true);

        SkuScheduleDTO selected = ReflectionTestUtils.invokeMethod(
                strategy, "selectPreferredSkuFromCandidates", new LhScheduleContext(), priorityOneCandidates);

        assertEquals("3302001585", selected.getMaterialCode(),
                "第一层候选应严格按月度排序首位选料，不允许收尾SKU插队");
    }

    @Test
    public void selectPreferredSkuFromCandidates_shouldPickFirstForPriorityTwoCandidates() {
        ContinuousProductionStrategy strategy = new ContinuousProductionStrategy();
        IEndingJudgmentStrategy endingJudgmentStrategy = mock(IEndingJudgmentStrategy.class);
        ReflectionTestUtils.setField(strategy, "endingJudgmentStrategy", endingJudgmentStrategy);

        SkuScheduleDTO sku1585 = sku("3302001585");
        SkuScheduleDTO sku2022 = sku("3302002022");
        List<SkuScheduleDTO> priorityTwoCandidates = Arrays.asList(sku1585, sku2022);
        when(endingJudgmentStrategy.isCurrentWindowEnding(any(LhScheduleContext.class), same(sku2022))).thenReturn(true);

        SkuScheduleDTO selected = ReflectionTestUtils.invokeMethod(
                strategy, "selectPreferredSkuFromCandidates", new LhScheduleContext(), priorityTwoCandidates);

        assertEquals("3302001585", selected.getMaterialCode(),
                "第二层候选应严格按月度排序首位选料，不允许收尾SKU插队");
    }

    @Test
    public void selectPreferredSkuFromCandidates_shouldReturnNullWhenCandidatesEmpty() {
        ContinuousProductionStrategy strategy = new ContinuousProductionStrategy();

        SkuScheduleDTO selected = ReflectionTestUtils.invokeMethod(
                strategy, "selectPreferredSkuFromCandidates", new LhScheduleContext(), Collections.emptyList());

        assertNull(selected, "候选为空时应返回null");
    }

    /**
     * 用例说明：滚动衔接已继承同机同料结果时，续作剩余计划应并入继承结果，
     * 且只能从追加窗口继续排，不能再从重叠窗口首班重新排一条新记录。
     *
     * @throws Exception 反射注入异常
     */
    @Test
    public void shouldMergeRollingInheritedContinuousResultAndContinueFromAppendWindow() throws Exception {
        ContinuousProductionStrategy strategy = new ContinuousProductionStrategy();
        injectField(strategy, "orderNoGenerator", new OrderNoGenerator());
        injectField(strategy, "targetScheduleQtyResolver", new TargetScheduleQtyResolver());
        injectField(strategy, "endingJudgmentStrategy", new StubEndingJudgmentStrategy());

        LhScheduleContext context = buildRollingContext();
        LhScheduleResult inheritedResult = buildInheritedResult(context);
        context.getScheduleResultList().add(inheritedResult);
        context.getRollingInheritedScheduleResultList().add(inheritedResult);
        context.getMachineAssignmentMap().put("K1111", new ArrayList<>(Collections.singletonList(inheritedResult)));

        strategy.scheduleContinuousEnding(context);

        Assertions.assertEquals(1, context.getScheduleResultList().size());

        LhScheduleResult mergedResult = context.getScheduleResultList().get(0);
        Assertions.assertSame(inheritedResult, mergedResult);
        for (int shiftIndex = 1; shiftIndex <= 8; shiftIndex++) {
            Assertions.assertEquals(Integer.valueOf(16),
                    ShiftFieldUtil.getShiftPlanQty(mergedResult, shiftIndex));
        }
        Assertions.assertEquals(Integer.valueOf(128), mergedResult.getDailyPlanQty());
        Assertions.assertTrue(mergedResult.getSpecEndTime().after(toDate(2026, 4, 26, 21, 28, 0)));
    }

    /**
     * 用例说明：滚动继承结果即使是新增类型（02），续作追加也应并入同机同料继承结果，
     * 不能再额外新建一条续作记录。
     *
     * @throws Exception 反射注入异常
     */
    @Test
    public void shouldMergeRollingInheritedNewSpecResultAndAvoidDuplicateRow() throws Exception {
        ContinuousProductionStrategy strategy = new ContinuousProductionStrategy();
        injectField(strategy, "orderNoGenerator", new OrderNoGenerator());
        injectField(strategy, "targetScheduleQtyResolver", new TargetScheduleQtyResolver());
        injectField(strategy, "endingJudgmentStrategy", new StubEndingJudgmentStrategy());

        LhScheduleContext context = buildRollingContext();
        LhScheduleResult inheritedResult = buildInheritedResult(context);
        inheritedResult.setScheduleType("02");
        inheritedResult.setIsChangeMould("1");
        inheritedResult.setIsTypeBlock("1");
        context.getScheduleResultList().add(inheritedResult);
        context.getRollingInheritedScheduleResultList().add(inheritedResult);
        context.getMachineAssignmentMap().put("K1111", new ArrayList<>(Collections.singletonList(inheritedResult)));

        strategy.scheduleContinuousEnding(context);

        Assertions.assertEquals(1, context.getScheduleResultList().size());
        LhScheduleResult mergedResult = context.getScheduleResultList().get(0);
        Assertions.assertSame(inheritedResult, mergedResult);
        Assertions.assertEquals("01", mergedResult.getScheduleType());
        Assertions.assertEquals("0", mergedResult.getIsTypeBlock());
        Assertions.assertEquals("0", mergedResult.getIsChangeMould());
        for (int shiftIndex = 1; shiftIndex <= 8; shiftIndex++) {
            Assertions.assertEquals(Integer.valueOf(16),
                    ShiftFieldUtil.getShiftPlanQty(mergedResult, shiftIndex));
        }
        Assertions.assertEquals(Integer.valueOf(128), mergedResult.getDailyPlanQty());
    }

    /**
     * 用例说明：滚动衔接存在继承结果但追加窗口排不出量时，不应移除待排SKU。
     *
     * @throws Exception 反射注入异常
     */
    @Test
    public void shouldKeepPendingSkuWhenRollingAppendProducesNoQty() throws Exception {
        ContinuousProductionStrategy strategy = new ContinuousProductionStrategy();
        injectField(strategy, "orderNoGenerator", new OrderNoGenerator());
        injectField(strategy, "targetScheduleQtyResolver", new TargetScheduleQtyResolver());
        injectField(strategy, "endingJudgmentStrategy", new StubEndingJudgmentStrategy());

        LhScheduleContext context = buildRollingContext();
        context.getMachineScheduleMap().get("K1111").setEstimatedEndTime(toDate(2026, 4, 27, 23, 0, 0));
        LhScheduleResult inheritedResult = buildInheritedResult(context);
        context.getScheduleResultList().add(inheritedResult);
        context.getRollingInheritedScheduleResultList().add(inheritedResult);
        context.getMachineAssignmentMap().put("K1111", new ArrayList<>(Collections.singletonList(inheritedResult)));

        strategy.scheduleContinuousEnding(context);

        Assertions.assertEquals(1, context.getScheduleResultList().size());
        Assertions.assertEquals(1, context.getStructureSkuMap().size());
        Assertions.assertEquals(1, context.getContinuousSkuList().size());
        Assertions.assertSame(inheritedResult, context.getScheduleResultList().get(0));
    }

    @Test
    public void adjustEmbryoStock_shouldResetIsEndWhenFinalPlanQtyLessThanMaxDemand() throws Exception {
        ContinuousProductionStrategy strategy = new ContinuousProductionStrategy();

        LhScheduleContext context = new LhScheduleContext();
        context.setScheduleDate(toDate(2026, 4, 25, 0, 0, 0));
        context.setScheduleWindowShifts(LhScheduleTimeUtil.buildDefaultScheduleShifts(context, context.getScheduleDate()));
        SkuScheduleDTO sourceSku = sku("MAT-CHECK");
        context.setContinuousSkuList(Collections.singletonList(sourceSku));

        LhScheduleResult result = new LhScheduleResult();
        result.setScheduleType("01");
        result.setMaterialCode("MAT-CHECK");
        result.setEmbryoCode("EMB-1");
        result.setDailyPlanQty(88);
        result.setMouldSurplusQty(80);
        result.setEmbryoStock(140);
        result.setIsEnd("1");
        context.getScheduleResultList().add(result);
        context.getScheduleResultSourceSkuMap().put(result, sourceSku);

        strategy.adjustEmbryoStock(context);

        assertEquals("0", context.getScheduleResultList().get(0).getIsEnd(),
                "续作结果最终计划量小于max(硫化余量,胎胚库存)时，应回写为正常");
    }

    @Test
    public void adjustEmbryoStock_shouldConsumeAllocatedStockByMaterialCode() {
        ContinuousProductionStrategy strategy = new ContinuousProductionStrategy();

        LhScheduleContext context = new LhScheduleContext();
        context.setScheduleDate(toDate(2026, 4, 25, 0, 0, 0));
        context.setScheduleWindowShifts(LhScheduleTimeUtil.buildDefaultScheduleShifts(context, context.getScheduleDate()));
        SkuScheduleDTO firstSku = buildSku("MAT-SHARE-A", "EMB-SAME", 50);
        SkuScheduleDTO secondSku = buildSku("MAT-SHARE-B", "EMB-SAME", 100);
        context.setContinuousSkuList(Arrays.asList(firstSku, secondSku));

        LhScheduleResult firstResult = buildContinuousResult("MAT-SHARE-A", "EMB-SAME", 80);
        LhScheduleResult secondResult = buildContinuousResult("MAT-SHARE-B", "EMB-SAME", 80);
        context.getScheduleResultList().add(firstResult);
        context.getScheduleResultList().add(secondResult);
        context.getScheduleResultSourceSkuMap().put(firstResult, firstSku);
        context.getScheduleResultSourceSkuMap().put(secondResult, secondSku);

        strategy.adjustEmbryoStock(context);

        assertEquals(50, context.getScheduleResultList().get(0).getDailyPlanQty().intValue(),
                "同胎胚不同SKU应按各自分摊库存裁剪");
        assertEquals(80, context.getScheduleResultList().get(1).getDailyPlanQty().intValue(),
                "同胎胚不同SKU不应被前一个SKU按胎胚编号扣减库存");
    }

    /**
     * 用例说明：换活字块已扣减SKU实际账本后，续作班次重分配若下调最终结果量，
     * 应把差额恢复到账本，保证实际消费账本与最终有效排程量一致。
     *
     * @throws Exception 反射注入异常
     */
    @Test
    public void syncTypeBlockProductionLedgerAfterRedistribute_shouldRestoreReducedQty() throws Exception {
        ContinuousProductionStrategy strategy = new ContinuousProductionStrategy();
        injectField(strategy, "targetScheduleQtyResolver", new TargetScheduleQtyResolver());

        LhScheduleContext context = new LhScheduleContext();
        SkuScheduleDTO sourceSku = sku("3302001513");
        LhScheduleResult result = new LhScheduleResult();
        result.setScheduleType("03");
        result.setIsTypeBlock("1");
        result.setMaterialCode("3302001513");
        result.setLhMachineCode("K1105");
        ShiftFieldUtil.setShiftPlanQty(result, 2, 14, null, null);
        ShiftFieldUtil.setShiftPlanQty(result, 3, 18, null, null);
        ShiftFieldUtil.setShiftPlanQty(result, 4, 18, null, null);
        ShiftFieldUtil.setShiftPlanQty(result, 5, 14, null, null);
        ShiftFieldUtil.setShiftPlanQty(result, 6, 18, null, null);
        ShiftFieldUtil.setShiftPlanQty(result, 7, 18, null, null);
        ShiftFieldUtil.setShiftPlanQty(result, 8, 14, null, null);
        ShiftFieldUtil.syncDailyPlanQty(result);
        context.getScheduleResultSourceSkuMap().put(result, sourceSku);
        context.getSkuProductionRemainingQtyMap().put("3302001513", 1232);

        ReflectionTestUtils.invokeMethod(strategy,
                "syncTypeBlockProductionLedgerAfterRedistribute", context, result, 118);

        assertEquals(Integer.valueOf(1236), context.getSkuProductionRemainingQtyMap().get("3302001513"),
                "后置重分配少排4条时，应恢复4条实际账本");
    }

    @Test
    public void adjustEmbryoStock_shouldKeepFormalContinuousFullCapacityResult() {
        ContinuousProductionStrategy strategy = new ContinuousProductionStrategy();

        LhScheduleContext context = new LhScheduleContext();
        context.setScheduleDate(toDate(2026, 4, 25, 0, 0, 0));
        context.setScheduleWindowShifts(LhScheduleTimeUtil.buildDefaultScheduleShifts(context, context.getScheduleDate()));

        SkuScheduleDTO sku = new SkuScheduleDTO();
        sku.setMaterialCode("MAT-FORMAL");
        sku.setEmbryoCode("EMB-FORMAL");
        sku.setEmbryoStock(15);
        sku.setConstructionStage("03");
        sku.setTrial(false);
        sku.setStrictTargetQty(false);
        context.setContinuousSkuList(Collections.singletonList(sku));

        LhScheduleResult result = buildContinuousShiftResult("MAT-FORMAL", "EMB-FORMAL", 15, "0",
                16, 16, 16, 16, 16, 16, 16, 16);
        context.getScheduleResultList().add(result);
        context.getScheduleResultSourceSkuMap().put(result, sku);

        strategy.adjustEmbryoStock(context);

        assertEquals(128, context.getScheduleResultList().get(0).getDailyPlanQty().intValue(),
                "正式非收尾续作应保留满班补齐结果，不应被胎胚库存后置裁减");
        assertEquals(Integer.valueOf(16), ShiftFieldUtil.getShiftPlanQty(result, 1));
        assertEquals(Integer.valueOf(16), ShiftFieldUtil.getShiftPlanQty(result, 8));
    }

    @Test
    public void adjustEmbryoStock_shouldKeepEndingContinuousTargetQty() {
        ContinuousProductionStrategy strategy = new ContinuousProductionStrategy();

        LhScheduleContext context = new LhScheduleContext();
        context.setScheduleDate(toDate(2026, 4, 25, 0, 0, 0));
        context.setScheduleWindowShifts(LhScheduleTimeUtil.buildDefaultScheduleShifts(context, context.getScheduleDate()));

        SkuScheduleDTO sku = new SkuScheduleDTO();
        sku.setMaterialCode("MAT-END");
        sku.setEmbryoCode("EMB-END");
        sku.setEmbryoStock(15);
        sku.setConstructionStage("03");
        sku.setTrial(false);
        sku.setStrictTargetQty(true);
        context.setContinuousSkuList(Collections.singletonList(sku));

        LhScheduleResult result = buildContinuousShiftResult("MAT-END", "EMB-END", 15, "1",
                16, 16, 16, 16, 16, 16, 16, 16);
        context.getScheduleResultList().add(result);
        context.getScheduleResultSourceSkuMap().put(result, sku);

        strategy.adjustEmbryoStock(context);

        assertEquals(128, context.getScheduleResultList().get(0).getDailyPlanQty().intValue(),
                "收尾续作目标量已按max(余量,胎胚库存)计算，不应再被胎胚库存后置裁减");
    }

    @Test
    public void scheduleReduceMould_shouldAppendCompensationWhenContinuousSkuHasNoResult() {
        ContinuousProductionStrategy strategy = new ContinuousProductionStrategy();

        LhScheduleContext context = new LhScheduleContext();
        context.setScheduleDate(toDate(2026, 5, 1, 0, 0, 0));
        context.setScheduleTargetDate(toDate(2026, 5, 3, 0, 0, 0));
        context.setScheduleWindowShifts(LhScheduleTimeUtil.buildDefaultScheduleShifts(context, context.getScheduleDate()));
        context.setMachineScheduleMap(new LinkedHashMap<String, MachineScheduleDTO>());

        List<LhShiftConfigVO> shifts = context.getScheduleWindowShifts();
        LhShiftConfigVO firstShift = shifts.get(0);

        SkuScheduleDTO sku = new SkuScheduleDTO();
        sku.setMaterialCode("3302000001");
        sku.setMaterialDesc("12R22.5 TEST");
        sku.setStructureName("12R22.5-TEST");
        sku.setSpecCode("12R22.5");
        sku.setEmbryoCode("EMB-NO-RESULT");
        sku.setTargetScheduleQty(80);
        sku.setPendingQty(80);
        sku.setSurplusQty(999);
        sku.setWindowPlanQty(48);
        sku.setShiftCapacity(16);
        sku.setLhTimeSeconds(3600);
        Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap = new LinkedHashMap<LocalDate, SkuDailyPlanQuotaDTO>(4);
        quotaMap.put(toLocalDate(firstShift), quota("3302000001", toLocalDate(firstShift), 48));
        sku.setDailyPlanQuotaMap(quotaMap);
        sku.setContinuousMachineCode("K1107");
        context.setContinuousSkuList(Collections.singletonList(sku));

        strategy.scheduleReduceMould(context);

        Assertions.assertEquals(1, context.getNewSpecSkuList().size(),
                "续作SKU完全没有结果时，也应转入新增规格链路继续补量");
        SkuScheduleDTO compensationSku = context.getNewSpecSkuList().get(0);
        Assertions.assertSame(sku.getDailyPlanQuotaMap(), compensationSku.getDailyPlanQuotaMap(),
                "零结果补偿SKU也必须共享原SKU日计划账本");
        Assertions.assertEquals(48, compensationSku.resolveTargetScheduleQty());
        Assertions.assertEquals(48, compensationSku.getRemainingScheduleQty());
        Assertions.assertNull(compensationSku.getContinuousMachineCode(), "补偿SKU应交由新增换模链路重新选机");
        Assertions.assertEquals("K1107",
                ReflectionTestUtils.getField(compensationSku, "preferredContinuousMachineCode"),
                "补偿SKU应保留原续作机台，供轮到自己时优先锁回");
    }

    @Test
    public void scheduleReduceMould_shouldAppendNewSpecCompensationWhenContinuousTargetNotMet() {
        ContinuousProductionStrategy strategy = new ContinuousProductionStrategy();

        LhScheduleContext context = new LhScheduleContext();
        context.setScheduleDate(toDate(2026, 5, 1, 0, 0, 0));
        context.setScheduleTargetDate(toDate(2026, 5, 3, 0, 0, 0));
        context.setScheduleWindowShifts(LhScheduleTimeUtil.buildDefaultScheduleShifts(context, context.getScheduleDate()));
        context.setMachineScheduleMap(new LinkedHashMap<String, MachineScheduleDTO>());

        MachineScheduleDTO machine = new MachineScheduleDTO();
        machine.setMachineCode("K1105");
        context.getMachineScheduleMap().put(machine.getMachineCode(), machine);

        List<LhShiftConfigVO> shifts = context.getScheduleWindowShifts();
        LhShiftConfigVO firstShift = shifts.get(0);

        SkuScheduleDTO sku = new SkuScheduleDTO();
        sku.setMaterialCode("3302001724");
        sku.setMaterialDesc("385/65R22.5 20PR JY588");
        sku.setStructureName("385/65R22.5-JY588");
        sku.setSpecCode("385/65R22.5");
        sku.setEmbryoCode("EMB-COMP");
        sku.setTargetScheduleQty(158);
        sku.setPendingQty(158);
        sku.setSurplusQty(999);
        sku.setWindowPlanQty(158);
        sku.setShiftCapacity(16);
        sku.setLhTimeSeconds(3600);
        Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap = new LinkedHashMap<LocalDate, SkuDailyPlanQuotaDTO>(4);
        quotaMap.put(toLocalDate(firstShift), quota("3302001724", toLocalDate(firstShift), 158));
        sku.setDailyPlanQuotaMap(quotaMap);
        sku.setContinuousMachineCode("K1105");
        context.setContinuousSkuList(Collections.singletonList(sku));

        LhScheduleResult result = new LhScheduleResult();
        result.setScheduleType("01");
        result.setIsTypeBlock("0");
        result.setMaterialCode("3302001724");
        result.setLhMachineCode("K1105");
        result.setEmbryoCode("EMB-COMP");
        result.setLhTime(3600);
        result.setMouldQty(1);
        result.setSpecEndTime(firstShift.getShiftEndDateTime());
        ShiftFieldUtil.setShiftPlanQty(result, firstShift.getShiftIndex(), 112,
                firstShift.getShiftStartDateTime(), firstShift.getShiftEndDateTime());
        ShiftFieldUtil.syncDailyPlanQty(result);
        context.getScheduleResultList().add(result);
        context.getScheduleResultSourceSkuMap().put(result, sku);

        strategy.scheduleReduceMould(context);

        Assertions.assertEquals(1, context.getNewSpecSkuList().size(), "续作不足时应转入新增规格链路继续补量");
        SkuScheduleDTO compensationSku = context.getNewSpecSkuList().get(0);
        Assertions.assertNotSame(sku, compensationSku);
        Assertions.assertSame(sku.getDailyPlanQuotaMap(), compensationSku.getDailyPlanQuotaMap(),
                "补偿SKU必须共享原SKU日计划账本，避免重复消费窗口额度");
        Assertions.assertEquals(46, compensationSku.resolveTargetScheduleQty());
        Assertions.assertEquals(46, compensationSku.getRemainingScheduleQty());
        Assertions.assertNull(compensationSku.getContinuousMachineCode(), "补偿SKU应交由新增换模链路重新选机");
        Assertions.assertEquals("K1105",
                ReflectionTestUtils.getField(compensationSku, "preferredContinuousMachineCode"),
                "续作补偿SKU应记住原续作机台，供新增阶段自己回合优先锁回");
    }

    @Test
    public void scheduleReduceMould_shouldNotAppendCompensationWhenDailyLookAheadCapacitySatisfied() {
        ContinuousProductionStrategy strategy = new ContinuousProductionStrategy();

        LhScheduleContext context = new LhScheduleContext();
        context.setScheduleDate(toDate(2026, 5, 1, 0, 0, 0));
        context.setScheduleTargetDate(toDate(2026, 5, 3, 0, 0, 0));
        context.setScheduleWindowShifts(LhScheduleTimeUtil.buildDefaultScheduleShifts(context, context.getScheduleDate()));
        context.setMachineScheduleMap(new LinkedHashMap<String, MachineScheduleDTO>());

        MachineScheduleDTO machine = new MachineScheduleDTO();
        machine.setMachineCode("K2024");
        context.getMachineScheduleMap().put(machine.getMachineCode(), machine);

        List<LhShiftConfigVO> shifts = context.getScheduleWindowShifts();
        Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap = buildQuotaMap(
                shifts.get(0), shifts.get(2), shifts.get(5), 50, 50, 50);
        SkuScheduleDTO sku = new SkuScheduleDTO();
        sku.setMaterialCode("3302002654");
        sku.setMaterialDesc("11R22.5 149/146M 18PR AF508 BL4HAM");
        sku.setStructureName("11R22.5-JF568");
        sku.setSpecCode("11R22.5");
        sku.setEmbryoCode("EMB-COMP-DAY");
        sku.setConstructionStage(ConstructionStageEnum.FORMAL.getCode());
        sku.setTargetScheduleQty(136);
        sku.setPendingQty(136);
        sku.setSurplusQty(999);
        sku.setWindowPlanQty(150);
        sku.setWindowRemainingPlanQty(150);
        sku.setShiftCapacity(17);
        sku.setLhTimeSeconds(3600);
        sku.setDailyPlanQuotaMap(quotaMap);
        sku.setContinuousMachineCode("K2024");
        context.setContinuousSkuList(Collections.singletonList(sku));

        LhScheduleResult result = baseContinuationResult("3302002654", "K2024", false);
        result.setEmbryoCode("EMB-COMP-DAY");
        result.setSingleMouldShiftQty(17);
        for (LhShiftConfigVO shift : shifts) {
            ShiftFieldUtil.setShiftPlanQty(result, shift.getShiftIndex(), 17,
                    shift.getShiftStartDateTime(), shift.getShiftEndDateTime());
        }
        ShiftFieldUtil.syncDailyPlanQty(result);
        result.setSpecEndTime(shifts.get(shifts.size() - 1).getShiftEndDateTime());
        result.setTdaySpecEndTime(result.getSpecEndTime());
        context.getScheduleResultList().add(result);
        context.getScheduleResultSourceSkuMap().put(result, sku);

        strategy.scheduleReduceMould(context);

        Assertions.assertTrue(context.getNewSpecSkuList().isEmpty(),
                "欠产未超阈值且17*3已满足后续50日计划时，不应为了窗口剩余14生成补偿SKU");
        Assertions.assertEquals(14, SkuDailyPlanQuotaUtil.sumRemainingQty(sku.getDailyPlanQuotaMap()),
                "小额剩余额度允许滚动到后续窗口，不应强制转S4.5补齐");
    }

    @Test
    public void scheduleReduceMould_shouldNotAppendCompensationForSmallShortageWhenFuturePlanSatisfied() {
        ContinuousProductionStrategy strategy = new ContinuousProductionStrategy();

        LhScheduleContext context = new LhScheduleContext();
        context.setScheduleDate(toDate(2026, 5, 1, 0, 0, 0));
        context.setScheduleTargetDate(toDate(2026, 5, 3, 0, 0, 0));
        context.setScheduleWindowShifts(LhScheduleTimeUtil.buildDefaultScheduleShifts(context, context.getScheduleDate()));

        List<LhShiftConfigVO> shifts = context.getScheduleWindowShifts();
        LhShiftConfigVO firstShift = shifts.get(0);
        LhShiftConfigVO nextDayShift = resolveNextWorkDateShift(shifts, firstShift);
        Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap = buildQuotaMap(
                firstShift, nextDayShift, 48, 48);
        quotaMap.get(toLocalDate(firstShift)).setRemainingQty(148);
        SkuScheduleDTO sku = buildContinuationSku(
                "MAT-SMALL-SHORTAGE", ConstructionStageEnum.FORMAL.getCode(), false, 196, quotaMap);
        sku.setMonthlyHistoryShortageQty(100);
        sku.setEffectiveCarryForwardQty(100);
        sku.setWindowPlanQty(196);
        sku.setWindowRemainingPlanQty(196);
        sku.setContinuousMachineCode("K2026");
        context.setContinuousSkuList(Collections.singletonList(sku));

        LhScheduleResult result = baseContinuationResult("MAT-SMALL-SHORTAGE", "K2026", false);
        result.setEmbryoCode("EMB-MAT-SMALL-SHORTAGE");
        result.setLhTime(3600);
        result.setMouldQty(1);
        result.setSingleMouldShiftQty(16);
        ShiftFieldUtil.setShiftPlanQty(result, firstShift.getShiftIndex(), 48,
                firstShift.getShiftStartDateTime(), firstShift.getShiftEndDateTime());
        ShiftFieldUtil.setShiftPlanQty(result, nextDayShift.getShiftIndex(), 48,
                nextDayShift.getShiftStartDateTime(), nextDayShift.getShiftEndDateTime());
        ShiftFieldUtil.syncDailyPlanQty(result);
        context.getScheduleResultList().add(result);
        context.getScheduleResultSourceSkuMap().put(result, sku);

        strategy.scheduleReduceMould(context);

        Assertions.assertEquals(0, context.getNewSpecSkuList().size(),
                "小额历史欠产未超阈值且后续日计划已满足时，不应只为首日历史欠产余额生成补偿SKU");
        Assertions.assertEquals(100, SkuDailyPlanQuotaUtil.sumRemainingQty(sku.getDailyPlanQuotaMap()),
                "首日历史欠产余额允许继续滚动，不应被续作补偿强制清完");
    }

    @Test
    public void scheduleReduceMould_shouldIgnoreTypeBlockResultWhenCheckingSmallShortageFuturePlan() {
        ContinuousProductionStrategy strategy = new ContinuousProductionStrategy();

        LhScheduleContext context = new LhScheduleContext();
        context.setScheduleDate(toDate(2026, 5, 1, 0, 0, 0));
        context.setScheduleTargetDate(toDate(2026, 5, 3, 0, 0, 0));
        context.setScheduleWindowShifts(LhScheduleTimeUtil.buildDefaultScheduleShifts(context, context.getScheduleDate()));

        List<LhShiftConfigVO> shifts = context.getScheduleWindowShifts();
        LhShiftConfigVO firstShift = shifts.get(0);
        LhShiftConfigVO nextDayShift = resolveNextWorkDateShift(shifts, firstShift);
        Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap = buildQuotaMap(firstShift, nextDayShift, 48, 48);
        quotaMap.get(toLocalDate(firstShift)).setRemainingQty(148);
        SkuScheduleDTO sku = buildContinuationSku(
                "MAT-SMALL-TYPEBLOCK", ConstructionStageEnum.FORMAL.getCode(), false, 196, quotaMap);
        sku.setMonthlyHistoryShortageQty(100);
        sku.setEffectiveCarryForwardQty(100);
        sku.setWindowPlanQty(196);
        sku.setWindowRemainingPlanQty(196);
        sku.setContinuousMachineCode("K2027");
        context.setContinuousSkuList(Collections.singletonList(sku));

        LhScheduleResult continuousResult = baseContinuationResult("MAT-SMALL-TYPEBLOCK", "K2027", false);
        ShiftFieldUtil.setShiftPlanQty(continuousResult, firstShift.getShiftIndex(), 48,
                firstShift.getShiftStartDateTime(), firstShift.getShiftEndDateTime());
        ShiftFieldUtil.syncDailyPlanQty(continuousResult);
        context.getScheduleResultList().add(continuousResult);
        context.getScheduleResultSourceSkuMap().put(continuousResult, sku);

        LhScheduleResult typeBlockResult = baseContinuationResult("MAT-SMALL-TYPEBLOCK", "K2028", false);
        typeBlockResult.setIsTypeBlock("1");
        ShiftFieldUtil.setShiftPlanQty(typeBlockResult, nextDayShift.getShiftIndex(), 48,
                nextDayShift.getShiftStartDateTime(), nextDayShift.getShiftEndDateTime());
        ShiftFieldUtil.syncDailyPlanQty(typeBlockResult);
        context.getScheduleResultList().add(typeBlockResult);
        context.getScheduleResultSourceSkuMap().put(typeBlockResult, sku);

        strategy.scheduleReduceMould(context);

        Assertions.assertEquals(1, context.getNewSpecSkuList().size(),
                "换活字块结果不能作为纯续作覆盖后续日计划，否则会漏生成S4.5补偿SKU");
        SkuScheduleDTO compensationSku = context.getNewSpecSkuList().get(0);
        Assertions.assertEquals(100, compensationSku.resolveTargetScheduleQty(),
                "补偿量应按纯续作未覆盖的动态目标缺口计算");
        Assertions.assertSame(sku.getDailyPlanQuotaMap(), compensationSku.getDailyPlanQuotaMap(),
                "补偿SKU必须继续共享来源续作账本");
    }

    @Test
    public void scheduleReduceMould_shouldAppendDynamicCompensationForLargeHistoryShortage() {
        ContinuousProductionStrategy strategy = new ContinuousProductionStrategy();

        LhScheduleContext context = new LhScheduleContext();
        context.setScheduleDate(toDate(2026, 5, 1, 0, 0, 0));
        context.setScheduleTargetDate(toDate(2026, 5, 3, 0, 0, 0));
        context.setScheduleWindowShifts(LhScheduleTimeUtil.buildDefaultScheduleShifts(context, context.getScheduleDate()));

        List<LhShiftConfigVO> shifts = context.getScheduleWindowShifts();
        LhShiftConfigVO firstShift = shifts.get(0);
        LhShiftConfigVO nextDayShift = resolveNextWorkDateShift(shifts, firstShift);
        Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap = buildQuotaMap(firstShift, nextDayShift, 48, 48);
        SkuScheduleDTO sku = buildContinuationSku(
                "MAT-LARGE-SHORTAGE", ConstructionStageEnum.FORMAL.getCode(), false, 296, quotaMap);
        sku.setMonthlyHistoryShortageQty(200);
        sku.setWindowPlanQty(296);
        sku.setWindowRemainingPlanQty(296);
        sku.setContinuousMachineCode("K2028");
        context.setContinuousSkuList(Collections.singletonList(sku));

        LhScheduleResult result = baseContinuationResult("MAT-LARGE-SHORTAGE", "K2028", false);
        result.setEmbryoCode("EMB-MAT-LARGE-SHORTAGE");
        result.setLhTime(3600);
        result.setMouldQty(1);
        result.setSingleMouldShiftQty(16);
        ShiftFieldUtil.setShiftPlanQty(result, firstShift.getShiftIndex(), 48,
                firstShift.getShiftStartDateTime(), firstShift.getShiftEndDateTime());
        ShiftFieldUtil.syncDailyPlanQty(result);
        context.getScheduleResultList().add(result);
        context.getScheduleResultSourceSkuMap().put(result, sku);

        strategy.scheduleReduceMould(context);

        Assertions.assertEquals(1, context.getNewSpecSkuList().size(),
                "本月历史欠产超过阈值且续作窗口产能不足时，应生成补偿SKU进入S4.5重新选机");
        SkuScheduleDTO compensationSku = context.getNewSpecSkuList().get(0);
        Assertions.assertEquals(248, compensationSku.resolveTargetScheduleQty(),
                "补偿量应按窗口剩余缺口动态计算，不能退化为固定单班或单机台补量");
        Assertions.assertEquals(248, compensationSku.getRemainingScheduleQty(),
                "补偿SKU剩余量应与窗口缺口保持一致");
        Assertions.assertSame(sku.getDailyPlanQuotaMap(), compensationSku.getDailyPlanQuotaMap(),
                "大欠产补偿SKU也必须共享原续作账本，避免重复消费");
    }

    @Test
    public void scheduleReduceMould_shouldNotAppendCompensationWhenLargeShortageBackToThreshold() {
        ContinuousProductionStrategy strategy = new ContinuousProductionStrategy();

        LhScheduleContext context = buildMultiDayContinuationContext(
                ConstructionStageEnum.FORMAL.getCode(), 336, 48, 48, 48,
                10, 5, "K2028", "K2029");
        context.setScheduleConfig(new LhScheduleConfig(Collections.singletonMap(
                LhScheduleParamConstant.NEW_SPEC_SHORTAGE_ADD_MACHINE_THRESHOLD, "150")));
        for (SkuScheduleDTO sku : context.getContinuousSkuList()) {
            sku.setMonthlyHistoryShortageQty(192);
            sku.setScheduleDayFinishQty(0);
            sku.setWindowPlanQty(336);
            sku.setWindowRemainingPlanQty(336);
        }

        Boolean forcedShortageWindowSatisfied = ReflectionTestUtils.invokeMethod(
                strategy, "isForcedShortageWindowSatisfied", context, context.getContinuousSkuList().get(0), 144, 224);
        Assertions.assertTrue(Boolean.TRUE.equals(forcedShortageWindowSatisfied),
                "历史欠产超过阈值且两台机台窗口有效产能足够时，应命中阈值回落分支");

        strategy.scheduleReduceMould(context);

        Assertions.assertTrue(context.getNewSpecSkuList().isEmpty(),
                "两台续作机台窗口有效产能已让剩余欠产回到阈值以内，不应继续生成S4.5补偿SKU");
    }

    @Test
    public void scheduleReduceMould_shouldNotAppendCompensationWhenSharedQuotaExhausted() {
        ContinuousProductionStrategy strategy = new ContinuousProductionStrategy();

        LhScheduleContext context = new LhScheduleContext();
        context.setScheduleDate(toDate(2026, 5, 1, 0, 0, 0));
        context.setScheduleTargetDate(toDate(2026, 5, 3, 0, 0, 0));
        context.setScheduleWindowShifts(LhScheduleTimeUtil.buildDefaultScheduleShifts(context, context.getScheduleDate()));
        context.setMachineScheduleMap(new LinkedHashMap<String, MachineScheduleDTO>());

        MachineScheduleDTO machine = new MachineScheduleDTO();
        machine.setMachineCode("K1106");
        context.getMachineScheduleMap().put(machine.getMachineCode(), machine);

        List<LhShiftConfigVO> shifts = context.getScheduleWindowShifts();
        LhShiftConfigVO firstShift = shifts.get(0);

        SkuScheduleDTO sku = new SkuScheduleDTO();
        sku.setMaterialCode("3302002654");
        sku.setMaterialDesc("12R22.5 JY701");
        sku.setStructureName("12R22.5-JY701");
        sku.setSpecCode("12R22.5");
        sku.setEmbryoCode("EMB-COMP-0");
        sku.setTargetScheduleQty(158);
        sku.setPendingQty(158);
        sku.setSurplusQty(999);
        sku.setWindowPlanQty(112);
        sku.setShiftCapacity(16);
        sku.setLhTimeSeconds(3600);
        Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap = new LinkedHashMap<LocalDate, SkuDailyPlanQuotaDTO>(4);
        quotaMap.put(toLocalDate(firstShift), quota("3302002654", toLocalDate(firstShift), 112));
        sku.setDailyPlanQuotaMap(quotaMap);
        sku.setContinuousMachineCode("K1106");
        context.setContinuousSkuList(Collections.singletonList(sku));

        LhScheduleResult result = new LhScheduleResult();
        result.setScheduleType("01");
        result.setIsTypeBlock("0");
        result.setMaterialCode("3302002654");
        result.setLhMachineCode("K1106");
        result.setEmbryoCode("EMB-COMP-0");
        result.setLhTime(3600);
        result.setMouldQty(1);
        result.setSpecEndTime(firstShift.getShiftEndDateTime());
        ShiftFieldUtil.setShiftPlanQty(result, firstShift.getShiftIndex(), 112,
                firstShift.getShiftStartDateTime(), firstShift.getShiftEndDateTime());
        ShiftFieldUtil.syncDailyPlanQty(result);
        context.getScheduleResultList().add(result);
        context.getScheduleResultSourceSkuMap().put(result, sku);

        strategy.scheduleReduceMould(context);

        Assertions.assertTrue(context.getNewSpecSkuList().isEmpty(),
                "共享日计划账本剩余为0时，续作不足也不应继续生成补偿SKU");
    }

    @Test
    public void syncContinuousDailyPlanQuota_shouldTrimResultQtyAndAvoidDoubleConsume() {
        ContinuousProductionStrategy strategy = new ContinuousProductionStrategy();

        LhScheduleContext context = new LhScheduleContext();
        context.setScheduleDate(toDate(2026, 4, 25, 0, 0, 0));
        context.setScheduleTargetDate(toDate(2026, 4, 27, 0, 0, 0));
        context.setScheduleWindowShifts(LhScheduleTimeUtil.buildDefaultScheduleShifts(context, context.getScheduleDate()));
        context.setMachineScheduleMap(new LinkedHashMap<String, MachineScheduleDTO>());

        MachineScheduleDTO machine = new MachineScheduleDTO();
        machine.setMachineCode("M-QUOTA");
        context.getMachineScheduleMap().put(machine.getMachineCode(), machine);

        List<LhShiftConfigVO> shifts = context.getScheduleWindowShifts();
        LhShiftConfigVO firstShift = shifts.get(0);
        LhShiftConfigVO nextDayShift = resolveNextWorkDateShift(shifts, firstShift);

        SkuScheduleDTO sku = new SkuScheduleDTO();
        sku.setMaterialCode("MAT-QUOTA");
        sku.setTrial(true);
        sku.setStrictTargetQty(true);
        sku.setStrictNewSpecShortageOnly(true);
        sku.setConstructionStage(ConstructionStageEnum.TRIAL.getCode());
        sku.setDailyPlanQuotaMap(buildQuotaMap(firstShift, nextDayShift, 6, 4));
        context.setContinuousSkuList(Collections.singletonList(sku));

        LhScheduleResult result = new LhScheduleResult();
        result.setScheduleType("01");
        result.setIsTypeBlock("0");
        result.setMaterialCode("MAT-QUOTA");
        result.setLhMachineCode("M-QUOTA");
        result.setLhTime(3600);
        result.setMouldQty(1);
        ShiftFieldUtil.setShiftPlanQty(result, firstShift.getShiftIndex(), 8,
                firstShift.getShiftStartDateTime(), firstShift.getShiftEndDateTime());
        ShiftFieldUtil.setShiftPlanQty(result, nextDayShift.getShiftIndex(), 8,
                nextDayShift.getShiftStartDateTime(), nextDayShift.getShiftEndDateTime());
        ShiftFieldUtil.syncDailyPlanQty(result);
        context.getScheduleResultList().add(result);

        ReflectionTestUtils.invokeMethod(strategy, "syncContinuousDailyPlanQuota", context, shifts);
        ReflectionTestUtils.invokeMethod(strategy, "syncContinuousDailyPlanQuota", context, shifts);

        assertEquals(10, result.getDailyPlanQty().intValue(), "续作账本同步后，结果行计划量必须被窗口总量硬封顶");
        assertEquals(Integer.valueOf(8), ShiftFieldUtil.getShiftPlanQty(result, firstShift.getShiftIndex()));
        assertEquals(Integer.valueOf(2), ShiftFieldUtil.getShiftPlanQty(result, nextDayShift.getShiftIndex()));
        assertEquals(6, sku.getShiftFillOverQty(), "重复同步同一上下文时，不应再次累计超排量");
        assertEquals(6, context.getSkuShiftFillOverQtyMap().get("MAT-QUOTA").intValue());
    }

    @Test
    public void syncContinuousDailyPlanQuota_shouldNotTrimNormalContinuousByDayPlanQuota() {
        ContinuousProductionStrategy strategy = new ContinuousProductionStrategy();

        LhScheduleContext context = new LhScheduleContext();
        context.setScheduleDate(toDate(2026, 6, 14, 0, 0, 0));
        context.setScheduleTargetDate(toDate(2026, 6, 16, 0, 0, 0));
        context.setScheduleWindowShifts(LhScheduleTimeUtil.buildDefaultScheduleShifts(context, context.getScheduleDate()));
        context.setMachineScheduleMap(new LinkedHashMap<String, MachineScheduleDTO>());

        MachineScheduleDTO machine = new MachineScheduleDTO();
        machine.setMachineCode("K1905");
        context.getMachineScheduleMap().put(machine.getMachineCode(), machine);

        List<LhShiftConfigVO> shifts = context.getScheduleWindowShifts();
        LhShiftConfigVO firstShift = shifts.get(0);
        LhShiftConfigVO nextDayShift = resolveNextWorkDateShift(shifts, firstShift);
        LhShiftConfigVO thirdDayShift = resolveNextWorkDateShift(shifts, nextDayShift);

        SkuScheduleDTO sku = new SkuScheduleDTO();
        sku.setMaterialCode("3302002326");
        sku.setSurplusQty(210);
        sku.setStrictTargetQty(true);
        sku.setStrictNewSpecShortageOnly(false);
        sku.setConstructionStage(ConstructionStageEnum.FORMAL.getCode());
        sku.setDailyPlanQuotaMap(buildQuotaMap(firstShift, nextDayShift, thirdDayShift, 30, 0, 0));
        context.setContinuousSkuList(Collections.singletonList(sku));

        LhScheduleResult result = new LhScheduleResult();
        result.setScheduleType("01");
        result.setIsTypeBlock("0");
        result.setMaterialCode("3302002326");
        result.setLhMachineCode("K1905");
        result.setLhTime(3600);
        result.setMouldQty(2);
        for (LhShiftConfigVO shift : shifts) {
            ShiftFieldUtil.setShiftPlanQty(result, shift.getShiftIndex(), 16,
                    shift.getShiftStartDateTime(), shift.getShiftEndDateTime());
        }
        ShiftFieldUtil.syncDailyPlanQty(result);
        context.getScheduleResultList().add(result);
        context.getScheduleResultSourceSkuMap().put(result, sku);

        ReflectionTestUtils.invokeMethod(strategy, "syncContinuousDailyPlanQuota", context, shifts);

        assertEquals(128, result.getDailyPlanQty().intValue(),
                "正常续作的日计划量只应扣账和判断增机台，不应回裁当天排产量");
        assertEquals(Integer.valueOf(16), ShiftFieldUtil.getShiftPlanQty(result, firstShift.getShiftIndex()));
        assertEquals(Integer.valueOf(16), ShiftFieldUtil.getShiftPlanQty(result, thirdDayShift.getShiftIndex()));
        assertEquals(98, sku.getShiftFillOverQty(), "超过dayN账本的满班量只记录为超排，不截断结果");
    }

    @Test
    public void syncContinuousDailyPlanQuota_shouldUseSourceSkuMapForDuplicateMaterialCode() {
        ContinuousProductionStrategy strategy = new ContinuousProductionStrategy();

        LhScheduleContext context = new LhScheduleContext();
        context.setScheduleDate(toDate(2026, 4, 25, 0, 0, 0));
        context.setScheduleTargetDate(toDate(2026, 4, 27, 0, 0, 0));
        context.setScheduleWindowShifts(LhScheduleTimeUtil.buildDefaultScheduleShifts(context, context.getScheduleDate()));

        List<LhShiftConfigVO> shifts = context.getScheduleWindowShifts();
        LhShiftConfigVO firstShift = shifts.get(0);
        LhShiftConfigVO nextDayShift = resolveNextWorkDateShift(shifts, firstShift);

        SkuScheduleDTO firstSku = new SkuScheduleDTO();
        firstSku.setMaterialCode("MAT-DUP");
        firstSku.setTrial(true);
        firstSku.setStrictTargetQty(true);
        firstSku.setConstructionStage(ConstructionStageEnum.TRIAL.getCode());
        firstSku.setTargetScheduleQty(10);
        Map<LocalDate, SkuDailyPlanQuotaDTO> firstQuotaMap = new LinkedHashMap<LocalDate, SkuDailyPlanQuotaDTO>(4);
        firstQuotaMap.put(toLocalDate(firstShift), quota("MAT-DUP", toLocalDate(firstShift), 6));
        firstQuotaMap.put(toLocalDate(nextDayShift), quota("MAT-DUP", toLocalDate(nextDayShift), 0));
        firstSku.setDailyPlanQuotaMap(firstQuotaMap);

        SkuScheduleDTO secondSku = new SkuScheduleDTO();
        secondSku.setMaterialCode("MAT-DUP");
        secondSku.setTrial(true);
        secondSku.setStrictTargetQty(true);
        secondSku.setConstructionStage(ConstructionStageEnum.TRIAL.getCode());
        secondSku.setTargetScheduleQty(10);
        secondSku.setDailyPlanQuotaMap(new LinkedHashMap<LocalDate, SkuDailyPlanQuotaDTO>(4));
        context.setContinuousSkuList(Arrays.asList(firstSku, secondSku));

        LhScheduleResult firstResult = new LhScheduleResult();
        firstResult.setScheduleType("01");
        firstResult.setIsTypeBlock("0");
        firstResult.setMaterialCode("MAT-DUP");
        firstResult.setLhMachineCode("K1901");
        ShiftFieldUtil.setShiftPlanQty(firstResult, firstShift.getShiftIndex(), 8,
                firstShift.getShiftStartDateTime(), firstShift.getShiftEndDateTime());
        ShiftFieldUtil.syncDailyPlanQty(firstResult);

        LhScheduleResult secondResult = new LhScheduleResult();
        secondResult.setScheduleType("01");
        secondResult.setIsTypeBlock("0");
        secondResult.setMaterialCode("MAT-DUP");
        secondResult.setLhMachineCode("K1902");
        ShiftFieldUtil.setShiftPlanQty(secondResult, nextDayShift.getShiftIndex(), 8,
                nextDayShift.getShiftStartDateTime(), nextDayShift.getShiftEndDateTime());
        ShiftFieldUtil.syncDailyPlanQty(secondResult);

        context.getScheduleResultList().add(firstResult);
        context.getScheduleResultList().add(secondResult);
        context.getScheduleResultSourceSkuMap().put(firstResult, firstSku);
        context.getScheduleResultSourceSkuMap().put(secondResult, secondSku);

        ReflectionTestUtils.invokeMethod(strategy, "syncContinuousDailyPlanQuota", context, shifts);

        assertEquals(6, firstResult.getDailyPlanQty().intValue(), "首条重复物料结果应只消费自己的首日额度");
        assertEquals(4, secondResult.getDailyPlanQty().intValue(), "第二条重复物料结果即使无dayN账本也应消费共享实际账本");
        assertEquals(2, firstSku.getShiftFillOverQty(), "首条结果的超排量应记录在首个来源SKU账本");
        assertEquals(0, secondSku.getShiftFillOverQty(), "无dayN账本的来源SKU不应再追加超排量");
        assertEquals(Integer.valueOf(0), context.getSkuProductionRemainingQtyMap().get("MAT-DUP"),
                "同物料续作结果应共享并扣尽实际消费账本");
    }

    @Test
    public void syncContinuousDailyPlanQuota_shouldSkipTypeBlockResult() {
        ContinuousProductionStrategy strategy = new ContinuousProductionStrategy();

        LhScheduleContext context = new LhScheduleContext();
        context.setScheduleDate(toDate(2026, 4, 1, 0, 0, 0));
        context.setScheduleTargetDate(toDate(2026, 4, 3, 0, 0, 0));
        context.setScheduleWindowShifts(LhScheduleTimeUtil.buildDefaultScheduleShifts(context, context.getScheduleDate()));

        List<LhShiftConfigVO> shifts = context.getScheduleWindowShifts();
        LhShiftConfigVO firstShift = shifts.get(0);

        SkuScheduleDTO sku = sku("3302002795");
        sku.setTargetScheduleQty(98);
        Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap = new LinkedHashMap<LocalDate, SkuDailyPlanQuotaDTO>(4);
        quotaMap.put(toLocalDate(firstShift), quota("3302002795", toLocalDate(firstShift), 48));
        sku.setDailyPlanQuotaMap(quotaMap);
        context.setContinuousSkuList(Collections.singletonList(sku));

        LhScheduleResult result = new LhScheduleResult();
        result.setScheduleType("03");
        result.setIsTypeBlock("1");
        result.setIsEnd("1");
        result.setMaterialCode("3302002795");
        result.setLhMachineCode("K2024");
        result.setLhTime(0);
        result.setMouldQty(1);
        ShiftFieldUtil.setShiftPlanQty(result, firstShift.getShiftIndex(), 60,
                firstShift.getShiftStartDateTime(), firstShift.getShiftEndDateTime());
        ShiftFieldUtil.syncDailyPlanQty(result);
        context.getScheduleResultList().add(result);
        context.getScheduleResultSourceSkuMap().put(result, sku);

        ReflectionTestUtils.invokeMethod(strategy, "syncContinuousDailyPlanQuota", context, shifts);

        assertEquals(Integer.valueOf(60), ShiftFieldUtil.getShiftPlanQty(result, firstShift.getShiftIndex()),
                "换活字块结果不应被续作 quota 同步链裁减");
        assertEquals(48, sku.getDailyPlanQuotaMap().get(toLocalDate(firstShift)).getRemainingQty(),
                "换活字块结果不应消费续作共享日计划账本");
    }

    @Test
    public void scheduleReduceMould_shouldRecheckIsEndAfterPlanQtyReduced() {
        ContinuousProductionStrategy strategy = new ContinuousProductionStrategy();

        LhScheduleContext context = new LhScheduleContext();
        context.setScheduleDate(toDate(2026, 4, 25, 0, 0, 0));
        context.setScheduleTargetDate(toDate(2026, 4, 27, 0, 0, 0));
        context.setScheduleWindowShifts(LhScheduleTimeUtil.buildDefaultScheduleShifts(context, context.getScheduleDate()));
        context.setMachineScheduleMap(new LinkedHashMap<String, MachineScheduleDTO>());

        MachineScheduleDTO machine1 = new MachineScheduleDTO();
        machine1.setMachineCode("M1");
        machine1.setCapsuleUsageCount(1);
        context.getMachineScheduleMap().put("M1", machine1);
        MachineScheduleDTO machine2 = new MachineScheduleDTO();
        machine2.setMachineCode("M2");
        machine2.setCapsuleUsageCount(2);
        context.getMachineScheduleMap().put("M2", machine2);

        SkuScheduleDTO sku = new SkuScheduleDTO();
        sku.setMaterialCode("MAT-REDUCE");
        sku.setTargetScheduleQty(80);
        context.setContinuousSkuList(Collections.singletonList(sku));

        LhScheduleResult result1 = new LhScheduleResult();
        result1.setScheduleType("01");
        result1.setIsTypeBlock("0");
        result1.setMaterialCode("MAT-REDUCE");
        result1.setLhMachineCode("M1");
        result1.setEmbryoCode("EMB-1");
        result1.setMouldSurplusQty(100);
        result1.setEmbryoStock(120);
        result1.setIsEnd("1");
        result1.setLhTime(3600);
        result1.setMouldQty(2);
        result1.setSingleMouldShiftQty(30);
        ShiftFieldUtil.setShiftPlanQty(result1, 1, 60, null, null);
        ShiftFieldUtil.syncDailyPlanQty(result1);

        LhScheduleResult result2 = new LhScheduleResult();
        result2.setScheduleType("01");
        result2.setIsTypeBlock("0");
        result2.setMaterialCode("MAT-REDUCE");
        result2.setLhMachineCode("M2");
        result2.setEmbryoCode("EMB-1");
        result2.setMouldSurplusQty(100);
        result2.setEmbryoStock(120);
        result2.setIsEnd("1");
        result2.setLhTime(3600);
        result2.setMouldQty(2);
        result2.setSingleMouldShiftQty(30);
        ShiftFieldUtil.setShiftPlanQty(result2, 1, 60, null, null);
        ShiftFieldUtil.syncDailyPlanQty(result2);

        context.getScheduleResultList().add(result1);
        context.getScheduleResultList().add(result2);
        context.getScheduleResultSourceSkuMap().put(result1, sku);
        context.getScheduleResultSourceSkuMap().put(result2, sku);

        strategy.scheduleReduceMould(context);

        assertEquals(2, context.getScheduleResultList().size());
        assertEquals("0", context.getScheduleResultList().get(0).getIsEnd(),
                "降模后计划量低于max(硫化余量,胎胚库存)时，应回写为正常");
        assertEquals("0", context.getScheduleResultList().get(1).getIsEnd(),
                "降模后计划量低于max(硫化余量,胎胚库存)时，应回写为正常");
    }

    @Test
    public void scheduleReduceMould_shouldKeepFullEmbryoStockAfterZeroPlanResultRemoved() {
        ContinuousProductionStrategy strategy = new ContinuousProductionStrategy();

        LhScheduleContext context = new LhScheduleContext();
        context.setScheduleDate(toDate(2026, 4, 25, 0, 0, 0));
        context.setScheduleTargetDate(toDate(2026, 4, 27, 0, 0, 0));
        context.setMachineScheduleMap(new LinkedHashMap<String, MachineScheduleDTO>());

        MachineScheduleDTO machine1 = new MachineScheduleDTO();
        machine1.setMachineCode("M1");
        machine1.setCapsuleUsageCount(2);
        context.getMachineScheduleMap().put("M1", machine1);
        MachineScheduleDTO machine2 = new MachineScheduleDTO();
        machine2.setMachineCode("M2");
        machine2.setCapsuleUsageCount(1);
        context.getMachineScheduleMap().put("M2", machine2);

        SkuScheduleDTO sku = new SkuScheduleDTO();
        sku.setMaterialCode("MAT-STOCK");
        sku.setEmbryoStock(120);
        sku.setTargetScheduleQty(60);
        context.setContinuousSkuList(Collections.singletonList(sku));

        LhScheduleResult result1 = new LhScheduleResult();
        result1.setScheduleType("01");
        result1.setIsTypeBlock("0");
        result1.setMaterialCode("MAT-STOCK");
        result1.setLhMachineCode("M1");
        result1.setEmbryoCode("EMB-1");
        result1.setEmbryoStock(120);
        result1.setMouldQty(2);
        result1.setSingleMouldShiftQty(30);
        ShiftFieldUtil.setShiftPlanQty(result1, 1, 60, null, null);
        ShiftFieldUtil.syncDailyPlanQty(result1);

        LhScheduleResult result2 = new LhScheduleResult();
        result2.setScheduleType("01");
        result2.setIsTypeBlock("0");
        result2.setMaterialCode("MAT-STOCK");
        result2.setLhMachineCode("M2");
        result2.setEmbryoCode("EMB-1");
        result2.setEmbryoStock(120);
        result2.setMouldQty(2);
        result2.setSingleMouldShiftQty(30);
        ShiftFieldUtil.setShiftPlanQty(result2, 1, 60, null, null);
        ShiftFieldUtil.syncDailyPlanQty(result2);

        context.getScheduleResultList().add(result1);
        context.getScheduleResultList().add(result2);
        context.getScheduleResultSourceSkuMap().put(result1, sku);
        context.getScheduleResultSourceSkuMap().put(result2, sku);

        strategy.scheduleReduceMould(context);

        assertEquals(1, context.getScheduleResultList().size(), "零计划续作结果应在收口阶段移除");
        assertEquals("M1", context.getScheduleResultList().get(0).getLhMachineCode());
        assertEquals(120, context.getScheduleResultList().get(0).getEmbryoStock(),
                "多机台续作被裁成单条后，应保留来源SKU的完整胎胚库存口径");
    }

    @Test
    public void scheduleReduceMould_shouldNotReduceWhenDayPlanNeedsTwoMachines() {
        ContinuousProductionStrategy strategy = new ContinuousProductionStrategy();
        LhScheduleContext context = buildMultiMachineContinuationContext(
                ConstructionStageEnum.FORMAL.getCode(), false, 96, 96, 10, 5, "K1101", "K1102");

        strategy.scheduleReduceMould(context);

        assertEquals(2, context.getScheduleResultList().size(), "dayN需要两台机台产能时不应降模");
        assertEquals(96, sumScheduledQty(context));
    }

    @Test
    public void scheduleReduceMould_shouldReduceMachineWhenSingleMachineCapacityMeetsDayPlan() {
        ContinuousProductionStrategy strategy = new ContinuousProductionStrategy();
        LhScheduleContext context = buildMultiMachineContinuationContext(
                ConstructionStageEnum.FORMAL.getCode(), false, 48, 48, 10, 5, "K1101", "K1102");

        strategy.scheduleReduceMould(context);

        assertEquals(1, context.getScheduleResultList().size(), "dayN下降到单台可满足时应移除下机机台结果");
        LhScheduleResult retainedResult = context.getScheduleResultList().get(0);
        assertEquals("K1101", retainedResult.getLhMachineCode(), "胶囊使用次数更多的机台应优先保留");
        assertEquals(48, retainedResult.getDailyPlanQty().intValue());
    }

    @Test
    public void scheduleReduceMould_shouldKeepOnlyOneFullMachineWhenSingleMachineCoversWindowPlan() {
        ContinuousProductionStrategy strategy = new ContinuousProductionStrategy();
        LhScheduleContext context = buildThreeMachineEndingContinuationContext();

        strategy.scheduleReduceMould(context);

        assertEquals(1, countPositiveResults(context), "单台8班窗口产能已覆盖窗口计划时，不应保留第二台续作机台");
        assertEquals(128, findResultByMachineCode(context, "K1905").getDailyPlanQty().intValue(),
                "K1905 应排满 C1~C8，不应因日计划30提前下机");
        assertEquals(128, sumScheduledQty(context), "当前只需要一台续作机台排满，不应通过第二台清完全部余量");
        assertTrue(context.getUnscheduledResultList().stream()
                        .noneMatch(result -> "3302002326".equals(result.getMaterialCode())),
                "单机降模后剩余余量不应作为当前窗口未排缺口");
        assertEquals(210, context.getContinuousSkuList().get(0).resolveTargetScheduleQty(),
                "降模只改变保留机台和本轮分配，不应改写SKU收尾目标量");
    }

    @Test
    public void scheduleReduceMould_shouldNotCompensateRemovedMachineWhenSkuCopiesUseDifferentQuotaMap() {
        ContinuousProductionStrategy strategy = new ContinuousProductionStrategy();
        LhScheduleContext context = buildThreeMachineEndingContinuationContext();
        List<LhShiftConfigVO> shifts = context.getScheduleWindowShifts();
        LhShiftConfigVO firstShift = shifts.get(0);
        LhShiftConfigVO nextDayShift = resolveNextWorkDateShift(shifts, firstShift);
        LhShiftConfigVO thirdDayShift = resolveNextWorkDateShift(shifts, nextDayShift);
        for (SkuScheduleDTO sku : context.getContinuousSkuList()) {
            // 真实初始化中同物料多台续作可能各自持有运行态账本副本，降模标记不能依赖账本对象身份。
            sku.setDailyPlanQuotaMap(buildQuotaMap(firstShift, nextDayShift, thirdDayShift, 30, 0, 0));
        }

        strategy.scheduleReduceMould(context);

        assertEquals(1, countPositiveResults(context), "不同账本副本也应按同SKU降模，只保留一台续作机台");
        assertEquals(128, findResultByMachineCode(context, "K1905").getDailyPlanQty().intValue(),
                "保留机台应按完整窗口排满 C1~C8");
        assertTrue(context.getScheduleResultList().stream()
                        .filter(result -> "K1924".equals(result.getLhMachineCode()))
                        .noneMatch(result -> ShiftFieldUtil.resolveScheduledQty(result) > 0),
                "释放机台不应被后置补偿按剩余余量补回");
    }

    @Test
    public void scheduleReduceMould_shouldUseOriginalMonthPlanQtyForSingleMachineReduction() {
        ContinuousProductionStrategy strategy = new ContinuousProductionStrategy();
        LhScheduleContext context = buildThreeMachineEndingContinuationContext();
        List<LhShiftConfigVO> shifts = context.getScheduleWindowShifts();
        LhShiftConfigVO firstShift = shifts.get(0);
        LhShiftConfigVO nextDayShift = resolveNextWorkDateShift(shifts, firstShift);
        LhShiftConfigVO thirdDayShift = resolveNextWorkDateShift(shifts, nextDayShift);
        for (SkuScheduleDTO sku : context.getContinuousSkuList()) {
            // 运行态账本会合入历史欠产，本场景降模判断必须回到月计划DAY_13=30。
            sku.setDailyPlanQuotaMap(buildQuotaMap(firstShift, nextDayShift, thirdDayShift, 168, 0, 0));
        }
        FactoryMonthPlanProductionFinalResult plan = new FactoryMonthPlanProductionFinalResult();
        plan.setMaterialCode("3302002326");
        plan.setYear(2026);
        plan.setMonth(6);
        plan.setDay13(30);
        context.setMonthPlanList(Collections.singletonList(plan));

        strategy.scheduleReduceMould(context);

        assertEquals(1, countPositiveResults(context), "单机降模应按月计划T日量30判断，而不是按追补账本168保留两台");
        assertEquals(128, findResultByMachineCode(context, "K1905").getDailyPlanQty().intValue());
    }

    @Test
    public void scheduleReduceMould_shouldKeepOneMachineForEndingSkuWhenOnlyFirstDayQuotaExists() {
        ContinuousProductionStrategy strategy = new ContinuousProductionStrategy();
        LhScheduleContext context = buildThreeMachineEndingContinuationContext();
        replaceContinuousQuotaWithFirstDayOnly(context, 30);

        strategy.scheduleReduceMould(context);

        assertEquals(1, countPositiveResults(context), "收尾SKU仅首日账本有计划且单机覆盖窗口计划时，不应额外保留机台");
        assertEquals(128, findResultByMachineCode(context, "K1905").getDailyPlanQty().intValue(),
                "单日账本场景也应让保留机台排满完整窗口");
        assertTrue(context.getUnscheduledResultList().stream()
                        .noneMatch(result -> "3302002326".equals(result.getMaterialCode())),
                "单日账本场景单机降模后不应追加剩余余量未排");
    }

    @Test
    public void scheduleReduceMould_shouldNotAppendUnscheduledWhenTargetMetByMaterialResults() {
        ContinuousProductionStrategy strategy = new ContinuousProductionStrategy();
        LhScheduleContext context = buildThreeMachineEndingContinuationContext();
        List<LhShiftConfigVO> shifts = context.getScheduleWindowShifts();
        LhShiftConfigVO firstShift = shifts.get(0);
        LhShiftConfigVO nextDayShift = resolveNextWorkDateShift(shifts, firstShift);
        LhShiftConfigVO thirdDayShift = resolveNextWorkDateShift(shifts, nextDayShift);
        for (SkuScheduleDTO sku : context.getContinuousSkuList()) {
            // 真实初始化中同物料多台续作可能是不同SKU副本，不能只按账本identity判断剩余未排。
            sku.setDailyPlanQuotaMap(buildQuotaMap(firstShift, nextDayShift, thirdDayShift, 30, 0, 0));
            // 窗口日计划或欠产目标可能高于硫化余量，零结果未排应按清尾物理上限对账。
            sku.setTargetScheduleQty(84);
            sku.setPendingQty(84);
            sku.setSurplusQty(74);
            sku.setEmbryoStock(24);
        }

        strategy.scheduleReduceMould(context);

        assertEquals(74, sumScheduledQty(context), "同物料有效结果已满足清尾目标量");
        assertTrue(context.getUnscheduledResultList().stream()
                        .noneMatch(result -> "3302002326".equals(result.getMaterialCode())),
                "同物料有效排量已满足目标时，零结果移除不应再产生未排误报");
    }

    @Test
    public void scheduleReduceMould_shouldReduceMachineFromSecondDayWhenLaterDayPlanDrops() {
        ContinuousProductionStrategy strategy = new ContinuousProductionStrategy();
        LhScheduleContext context = buildMultiDayContinuationContext(
                ConstructionStageEnum.FORMAL.getCode(), 112, 64, 48, 10, 5, "K1405", "K1702");
        context.setScheduleConfig(new LhScheduleConfig(Collections.singletonMap(
                LhScheduleParamConstant.NEW_SPEC_SHORTAGE_LOOK_AHEAD_DAYS, "1")));

        strategy.scheduleReduceMould(context);

        assertEquals(2, context.getScheduleResultList().size(),
                "只允许向后追补1天且day2仍追不回时，第一天仍有排产量的下机机台结果不应被整条移除");
        LhScheduleResult retainedResult = findResultByMachineCode(context, "K1405");
        LhScheduleResult removedFromSecondDayResult = findResultByMachineCode(context, "K1702");
        assertEquals(80, retainedResult.getDailyPlanQty().intValue(),
                "K1405 应保留 day1 的 2 个班次和 day2 的 3 个班次");
        assertEquals(48, removedFromSecondDayResult.getDailyPlanQty().intValue(),
                "K1702 中班结束后进入不可换模晚班，应保留 day1 早中晚 3 个班次后再下机");
        assertEquals(Integer.valueOf(16), ShiftFieldUtil.getShiftPlanQty(removedFromSecondDayResult, 1));
        assertEquals(Integer.valueOf(16), ShiftFieldUtil.getShiftPlanQty(removedFromSecondDayResult, 2));
        assertEquals(Integer.valueOf(16), ShiftFieldUtil.getShiftPlanQty(removedFromSecondDayResult, 3));
        assertEquals(Integer.valueOf(0), ShiftFieldUtil.getShiftPlanQty(removedFromSecondDayResult, 4));
        assertEquals(Integer.valueOf(0), ShiftFieldUtil.getShiftPlanQty(removedFromSecondDayResult, 5));
        assertEquals(Integer.valueOf(0), ShiftFieldUtil.getShiftPlanQty(removedFromSecondDayResult, 6));
        assertEquals(128, sumScheduledQty(context),
                "按新规则中班后补满晚班，总量应收口为 day1 80 + day2 48");
    }

    @Test
    public void scheduleReduceMould_shouldUseDownMachineToFillFirstDayWhenLookAheadCanRecoverShortage() {
        ContinuousProductionStrategy strategy = new ContinuousProductionStrategy();
        LhScheduleContext context = buildMultiDayContinuationContext(
                ConstructionStageEnum.FORMAL.getCode(), 64, 48, 16, 10, 5, "K1405", "K1702");

        strategy.scheduleReduceMould(context);

        assertEquals(2, context.getScheduleResultList().size(),
                "day1单台不足时，下机机台只补足当天剩余量，后续窗口释放");
        LhScheduleResult retainedResult = findResultByMachineCode(context, "K1405");
        LhScheduleResult downMachineResult = findResultByMachineCode(context, "K1702");
        assertEquals("K1405", retainedResult.getLhMachineCode(), "降模后应保留胶囊次数更多的机台");
        assertEquals(Integer.valueOf(16), ShiftFieldUtil.getShiftPlanQty(retainedResult, 1));
        assertEquals(Integer.valueOf(16), ShiftFieldUtil.getShiftPlanQty(retainedResult, 2));
        assertEquals(Integer.valueOf(16), ShiftFieldUtil.getShiftPlanQty(retainedResult, 3));
        assertEquals(Integer.valueOf(16), ShiftFieldUtil.getShiftPlanQty(retainedResult, 4));
        assertEquals(Integer.valueOf(16), ShiftFieldUtil.getShiftPlanQty(retainedResult, 5));
        assertEquals(Integer.valueOf(16), ShiftFieldUtil.getShiftPlanQty(downMachineResult, 1));
        assertEquals(Integer.valueOf(0), ShiftFieldUtil.getShiftPlanQty(downMachineResult, 2));
        assertEquals(Integer.valueOf(0), ShiftFieldUtil.getShiftPlanQty(downMachineResult, 3));
        assertEquals(96, sumScheduledQty(context),
                "保留机台继续满班续作，下机机台只补足day1剩余量");
    }

    @Test
    public void scheduleReduceMould_shouldKeepNightShiftWhenDownMachineReleasedAfterAfternoon() {
        ContinuousProductionStrategy strategy = new ContinuousProductionStrategy();
        LhScheduleContext context = buildMultiDayContinuationContext(
                ConstructionStageEnum.FORMAL.getCode(), 128, 80, 48, 10, 5, "K1405", "K1702");

        strategy.scheduleReduceMould(context);

        LhScheduleResult downMachineResult = findResultByMachineCode(context, "K1702");
        assertEquals(Integer.valueOf(16), ShiftFieldUtil.getShiftPlanQty(downMachineResult, 1));
        assertEquals(Integer.valueOf(16), ShiftFieldUtil.getShiftPlanQty(downMachineResult, 2));
        assertEquals(Integer.valueOf(16), ShiftFieldUtil.getShiftPlanQty(downMachineResult, 3),
                "下机机台中班结束后进入不可换模晚班时，当前SKU应继续补满晚班后再释放");
        assertEquals(48, downMachineResult.getDailyPlanQty().intValue(),
                "晚班不可换模补满后，下机机台应保留早中晚三个班次产量");
    }

    @Test
    public void applyNoMouldChangeNightFillBeforeRelease_shouldFillCurrentAfternoonBeforeNightShift() {
        ContinuousProductionStrategy strategy = new ContinuousProductionStrategy();
        LhScheduleContext context = new LhScheduleContext();
        context.setFactoryCode("116");
        context.setBatchNo("LHPC-TEST-CONTINUATION-NIGHT-FILL");
        context.setScheduleDate(toDate(2026, 6, 14, 0, 0, 0));
        context.setScheduleTargetDate(toDate(2026, 6, 16, 0, 0, 0));
        context.setScheduleWindowShifts(LhScheduleTimeUtil.buildDefaultScheduleShifts(context, context.getScheduleDate()));
        addMachine(context, "K1902", 0);

        List<LhShiftConfigVO> shifts = context.getScheduleWindowShifts();
        Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap = buildQuotaMap(
                shifts.get(0), shifts.get(3), shifts.get(6), 100, 100, 100);
        SkuScheduleDTO sourceSku = buildContinuationSku(
                "3302001069", ConstructionStageEnum.FORMAL.getCode(), false, 300, quotaMap);
        sourceSku.setShiftCapacity(18);
        sourceSku.setSurplusQty(916);
        sourceSku.setContinuousMachineCode("K1902");

        LhScheduleResult result = baseContinuationResult(sourceSku.getMaterialCode(), "K1902", false);
        result.setSingleMouldShiftQty(18);
        ShiftFieldUtil.setShiftPlanQty(result, 3, 10,
                shifts.get(2).getShiftStartDateTime(), shifts.get(2).getShiftEndDateTime());
        ShiftFieldUtil.setShiftPlanQty(result, 4, 18,
                shifts.get(3).getShiftStartDateTime(), shifts.get(3).getShiftEndDateTime());
        ShiftFieldUtil.setShiftPlanQty(result, 5, 8,
                shifts.get(4).getShiftStartDateTime(), shifts.get(4).getShiftEndDateTime());
        ShiftFieldUtil.syncDailyPlanQty(result);
        context.getScheduleResultList().add(result);
        context.getScheduleResultSourceSkuMap().put(result, sourceSku);

        Boolean filled = ReflectionTestUtils.invokeMethod(strategy, "applyNoMouldChangeNightFillBeforeRelease",
                context, sourceSku, result, shifts, false);

        assertTrue(Boolean.TRUE.equals(filled), "续作中班结束后进入不可换模晚班时应命中补满规则");
        assertEquals(Integer.valueOf(18), ShiftFieldUtil.getShiftPlanQty(result, 5),
                "续作晚班补满前，应先把仍可生产的当前中班补到班产");
        assertEquals(Integer.valueOf(18), ShiftFieldUtil.getShiftPlanQty(result, 6),
                "当前中班补满后，下一不可换模晚班仍应补满班产");
        assertEquals(64, result.getDailyPlanQty().intValue(),
                "续作前班次和晚班补满后结果汇总量应刷新");
    }

    @Test
    public void scheduleReduceMould_shouldReduceByLookAheadEvenWhenFutureDayPlanDoesNotDrop() {
        ContinuousProductionStrategy strategy = new ContinuousProductionStrategy();
        LhScheduleContext context = buildMultiDayContinuationContext(
                ConstructionStageEnum.FORMAL.getCode(), 96, 48, 48, 10, 5, "K1405", "K1702");

        strategy.scheduleReduceMould(context);

        assertEquals(2, context.getScheduleResultList().size(),
                "未来dayN计划量未下降时，也应按追补窗口模拟释放冗余机台");
        LhScheduleResult retainedResult = findResultByMachineCode(context, "K1405");
        LhScheduleResult downMachineResult = findResultByMachineCode(context, "K1702");
        assertEquals(80, retainedResult.getDailyPlanQty().intValue());
        assertEquals(16, downMachineResult.getDailyPlanQty().intValue());
        assertEquals(96, sumScheduledQty(context),
                "保留机台满足后续追补窗口，下机机台仅补足day1剩余量");
    }

    @Test
    public void scheduleReduceMould_shouldFillFormalMultiDayContinuationToFullShiftCapacityWhenKeptMachineRemains() {
        ContinuousProductionStrategy strategy = new ContinuousProductionStrategy();
        LhScheduleContext context = buildMultiDayContinuationContext(
                ConstructionStageEnum.FORMAL.getCode(), 128, 64, 48, 48, 10, 5, "K1405", "K1702");

        strategy.scheduleReduceMould(context);

        LhScheduleResult retainedResult = findResultByMachineCode(context, "K1405");
        LhScheduleResult removedFromSecondDayResult = findResultByMachineCode(context, "K1702");
        assertEquals(128, retainedResult.getDailyPlanQty().intValue(),
                "正规非收尾续作多机台场景下，K1405 在 day2/day3 保留后应补满当天剩余班次产能");
        assertEquals(48, removedFromSecondDayResult.getDailyPlanQty().intValue(),
                "K1702 中班结束后进入不可换模晚班，应保留 day1 早中晚 3 个班次后再下机");
        assertEquals(Integer.valueOf(16), ShiftFieldUtil.getShiftPlanQty(removedFromSecondDayResult, 3));
        assertEquals(Integer.valueOf(16), ShiftFieldUtil.getShiftPlanQty(retainedResult, 6));
        assertEquals(Integer.valueOf(16), ShiftFieldUtil.getShiftPlanQty(retainedResult, 7));
        assertEquals(Integer.valueOf(16), ShiftFieldUtil.getShiftPlanQty(retainedResult, 8));
        assertEquals(176, sumScheduledQty(context),
                "day1=64、day2=48、day3=48 且下机中班后补晚班，总量应为 176");
    }

    @Test
    public void scheduleReduceMould_shouldRemoveMachineWithSmallerCapsuleUsageFirst() {
        ContinuousProductionStrategy strategy = new ContinuousProductionStrategy();
        LhScheduleContext context = buildMultiMachineContinuationContext(
                ConstructionStageEnum.FORMAL.getCode(), false, 48, 48, 3, 9, "K1101", "K1102");

        strategy.scheduleReduceMould(context);

        assertEquals(1, context.getScheduleResultList().size());
        assertEquals("K1102", context.getScheduleResultList().get(0).getLhMachineCode(),
                "胶囊使用次数少的K1101应优先下机");
    }

    @Test
    public void scheduleReduceMould_shouldRemoveLargerMachineCodeWhenCapsuleUsageTied() {
        ContinuousProductionStrategy strategy = new ContinuousProductionStrategy();
        LhScheduleContext context = buildMultiMachineContinuationContext(
                ConstructionStageEnum.FORMAL.getCode(), false, 48, 48, 5, 5, "K1101", "K1102");

        strategy.scheduleReduceMould(context);

        assertEquals(1, context.getScheduleResultList().size());
        assertEquals("K1101", context.getScheduleResultList().get(0).getLhMachineCode(),
                "胶囊次数相同时机台编码大的K1102应优先下机");
    }

    @Test
    public void formatContinuationMachineDetails_shouldIncludeMachineCapsuleUsageAndCapacity() {
        ContinuousProductionStrategy strategy = new ContinuousProductionStrategy();
        LhScheduleContext context = buildMultiMachineContinuationContext(
                ConstructionStageEnum.FORMAL.getCode(), false, 48, 48, 10, 5, "K1405", "K1702");
        Map<LhScheduleResult, Integer> capacityMap = new IdentityHashMap<LhScheduleResult, Integer>(4);
        List<LhScheduleResult> results = context.getScheduleResultList();
        capacityMap.put(results.get(0), 48);
        capacityMap.put(results.get(1), 32);

        String details = ReflectionTestUtils.invokeMethod(
                strategy, "formatContinuationMachineDetails", context, results, capacityMap);

        assertEquals("K1405(胶囊次数=10,日产能=48);K1702(胶囊次数=5,日产能=32)", details,
                "日志明细必须同时包含机台、胶囊次数和日产能，便于直接判断K1702是否因降模下机");
    }

    @Test
    public void scheduleReduceMould_shouldFillFormalNonEndingKeptMachineToShiftCapacity() {
        ContinuousProductionStrategy strategy = new ContinuousProductionStrategy();
        LhScheduleContext context = buildMultiMachineContinuationContext(
                ConstructionStageEnum.FORMAL.getCode(), false, 40, 40, 10, 5, "K1101", "K1102");

        strategy.scheduleReduceMould(context);

        assertEquals(1, context.getScheduleResultList().size());
        assertEquals(48, context.getScheduleResultList().get(0).getDailyPlanQty().intValue(),
                "正规非收尾保留机台应按当天可用班次补满班产");
    }

    @Test
    public void scheduleReduceMould_shouldFillMassTrialNonEndingKeptMachineToShiftCapacity() {
        ContinuousProductionStrategy strategy = new ContinuousProductionStrategy();
        LhScheduleContext context = buildMultiMachineContinuationContext(
                ConstructionStageEnum.MASS_TRIAL.getCode(), false, 40, 40, 10, 5, "K1101", "K1102");

        strategy.scheduleReduceMould(context);

        assertEquals(1, context.getScheduleResultList().size());
        assertEquals(48, context.getScheduleResultList().get(0).getDailyPlanQty().intValue(),
                "量试非收尾保留机台应按当天可用班次补满班产");
    }

    @Test
    public void scheduleReduceMould_shouldKeepPrototypeNonEndingWithinDayPlan() {
        ContinuousProductionStrategy strategy = new ContinuousProductionStrategy();
        LhScheduleContext context = buildMultiMachineContinuationContext(
                ConstructionStageEnum.TRIAL.getCode(), false, 40, 40, 10, 5, "K1101", "K1102");

        strategy.scheduleReduceMould(context);

        assertEquals(1, context.getScheduleResultList().size());
        assertEquals(40, context.getScheduleResultList().get(0).getDailyPlanQty().intValue(),
                "试制非收尾必须严格按dayN，不允许补满到48");
    }

    @Test
    public void scheduleReduceMould_shouldKeepEndingWithinTargetQtyForAllStages() {
        ContinuousProductionStrategy strategy = new ContinuousProductionStrategy();
        LhScheduleContext context = buildMultiMachineContinuationContext(
                ConstructionStageEnum.FORMAL.getCode(), true, 40, 40, 10, 5, "K1101", "K1102");

        strategy.scheduleReduceMould(context);

        assertEquals(1, context.getScheduleResultList().size());
        assertEquals(40, context.getScheduleResultList().get(0).getDailyPlanQty().intValue(),
                "收尾场景必须严格按目标量，不允许补满到48");
    }

    @Test
    public void scheduleReduceMould_shouldAggregateContinuousEndingWithinSameNightShift() {
        ContinuousProductionStrategy strategy = new ContinuousProductionStrategy();
        LhScheduleContext context = buildMultiMachineEndingStaggerContext(ShiftEnum.NIGHT_SHIFT.getCode());
        LhShiftConfigVO nightShift = findShiftByType(context.getScheduleWindowShifts(), ShiftEnum.NIGHT_SHIFT.getCode());

        strategy.scheduleReduceMould(context);

        assertEquals(1, context.getScheduleResultList().size(), "同班次尾量归集后应释放零计划机台");
        LhScheduleResult retainedResult = context.getScheduleResultList().get(0);
        assertEquals(16, retainedResult.getDailyPlanQty().intValue());
        assertEquals(Integer.valueOf(16), ShiftFieldUtil.getShiftPlanQty(retainedResult, nightShift.getShiftIndex()));
        assertEquals(16, sumScheduledQty(context));
    }

    @Test
    public void scheduleReduceMould_shouldAggregateContinuousEndingWithinSameShift() {
        ContinuousProductionStrategy strategy = new ContinuousProductionStrategy();
        LhScheduleContext context = buildMultiMachineEndingStaggerContext(ShiftEnum.MORNING_SHIFT.getCode());

        strategy.scheduleReduceMould(context);

        assertEquals(1, context.getScheduleResultList().size(), "同班次尾量归集后应释放零计划机台");
        LhScheduleResult retainedResult = context.getScheduleResultList().get(0);
        LhShiftConfigVO morningShift = findShiftByType(context.getScheduleWindowShifts(), ShiftEnum.MORNING_SHIFT.getCode());
        assertEquals(16, retainedResult.getDailyPlanQty().intValue());
        assertEquals(Integer.valueOf(16), ShiftFieldUtil.getShiftPlanQty(retainedResult, morningShift.getShiftIndex()));
        assertNull(ShiftFieldUtil.getShiftPlanQty(retainedResult, morningShift.getShiftIndex() + 1));
    }

    @Test
    public void scheduleReduceMould_shouldConcentrateSameShiftEndingTailOnRetainedPrimaryMachine() {
        ContinuousProductionStrategy strategy = new ContinuousProductionStrategy();
        LhScheduleContext context = new LhScheduleContext();
        context.setFactoryCode("116");
        context.setBatchNo("LHPC-TEST-3302002546");
        context.setScheduleDate(toDate(2026, 5, 1, 0, 0, 0));
        context.setScheduleTargetDate(toDate(2026, 5, 3, 0, 0, 0));
        context.setScheduleWindowShifts(LhScheduleTimeUtil.buildDefaultScheduleShifts(context, context.getScheduleDate()));

        addMachine(context, "K1113", 10);
        addMachine(context, "K2024", 5);

        List<LhShiftConfigVO> shifts = context.getScheduleWindowShifts();
        LhShiftConfigVO morningShift = findShiftByType(shifts, ShiftEnum.MORNING_SHIFT.getCode());
        Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap = new LinkedHashMap<LocalDate, SkuDailyPlanQuotaDTO>(4);
        quotaMap.put(toLocalDate(morningShift), quota("3302002546", toLocalDate(morningShift), 16));

        SkuScheduleDTO primarySku = buildContinuationSku(
                "3302002546", ConstructionStageEnum.FORMAL.getCode(), true, 16, quotaMap);
        primarySku.setContinuousMachineCode("K1113");
        SkuScheduleDTO auxSku = buildContinuationSku(
                "3302002546", ConstructionStageEnum.FORMAL.getCode(), true, 16, quotaMap);
        auxSku.setContinuousMachineCode("K2024");
        context.setContinuousSkuList(Arrays.asList(primarySku, auxSku));

        LhScheduleResult primaryResult = buildSingleShiftContinuationResult(
                "3302002546", "K1113", morningShift, 8, true);
        LhScheduleResult auxResult = buildSingleShiftContinuationResult(
                "3302002546", "K2024", morningShift, 8, true);
        context.getScheduleResultList().add(primaryResult);
        context.getScheduleResultList().add(auxResult);
        context.getScheduleResultSourceSkuMap().put(primaryResult, primarySku);
        context.getScheduleResultSourceSkuMap().put(auxResult, auxSku);
        context.getMachineAssignmentMap().put("K1113",
                new ArrayList<LhScheduleResult>(Collections.singletonList(primaryResult)));
        context.getMachineAssignmentMap().put("K2024",
                new ArrayList<LhScheduleResult>(Collections.singletonList(auxResult)));

        strategy.scheduleReduceMould(context);

        assertEquals(1, context.getScheduleResultList().size(), "3302002546 同班次收尾后应只保留主机结果");
        LhScheduleResult retainedResult = context.getScheduleResultList().get(0);
        assertEquals("K1113", retainedResult.getLhMachineCode(), "3302002546 应保留胶囊次数更高的主机");
        assertEquals(Integer.valueOf(16), ShiftFieldUtil.getShiftPlanQty(retainedResult, morningShift.getShiftIndex()));
        assertNull(ShiftFieldUtil.getShiftPlanQty(retainedResult, morningShift.getShiftIndex() + 1),
                "3302002546 当前实现会把同班次尾量继续留在原班次，先锁定主机归集机台");
        assertEquals(16, retainedResult.getDailyPlanQty().intValue(), "3302002546 尾量集中后主机汇总量应刷新为16");
    }

    @Test
    public void adjustContinuousSameSkuMultiMachineEndingStagger_shouldKeepAtMostOneRemainderPerShift() {
        ContinuousProductionStrategy strategy = new ContinuousProductionStrategy();
        LhScheduleContext context = buildMultiMachineEndingStaggerContext(
                ShiftEnum.MORNING_SHIFT.getCode(), true);
        LhShiftConfigVO morningShift = findShiftByType(
                context.getScheduleWindowShifts(), ShiftEnum.MORNING_SHIFT.getCode());
        addMachine(context, "K1103", 4);
        SkuScheduleDTO thirdSku = buildContinuationSku(
                "MAT-END-STAGGER", ConstructionStageEnum.FORMAL.getCode(), true, 20,
                context.getContinuousSkuList().get(0).getDailyPlanQuotaMap());
        thirdSku.setContinuousMachineCode("K1103");
        context.setContinuousSkuList(new ArrayList<SkuScheduleDTO>(context.getContinuousSkuList()));
        context.getContinuousSkuList().add(thirdSku);
        LhScheduleResult firstResult = findResultByMachineCode(context, "K1101");
        LhScheduleResult secondResult = findResultByMachineCode(context, "K1102");
        ShiftFieldUtil.setShiftPlanQty(firstResult, morningShift.getShiftIndex(), 8,
                morningShift.getShiftStartDateTime(), morningShift.getShiftEndDateTime());
        ShiftFieldUtil.syncDailyPlanQty(firstResult);
        ShiftFieldUtil.setShiftPlanQty(secondResult, morningShift.getShiftIndex(), 7,
                morningShift.getShiftStartDateTime(), morningShift.getShiftEndDateTime());
        ShiftFieldUtil.syncDailyPlanQty(secondResult);
        LhScheduleResult thirdResult = buildSingleShiftContinuationResult(
                "MAT-END-STAGGER", "K1103", morningShift, 5, true);
        context.getScheduleResultList().add(thirdResult);
        context.getScheduleResultSourceSkuMap().put(thirdResult, thirdSku);
        context.getMachineAssignmentMap().put("K1103",
                new ArrayList<LhScheduleResult>(Collections.singletonList(thirdResult)));

        ReflectionTestUtils.invokeMethod(strategy, "adjustContinuousSameSkuMultiMachineEndingStagger",
                context, context.getScheduleWindowShifts());

        assertEquals(Integer.valueOf(16), ShiftFieldUtil.getShiftPlanQty(firstResult, morningShift.getShiftIndex()));
        assertEquals(Integer.valueOf(4), ShiftFieldUtil.getShiftPlanQty(secondResult, morningShift.getShiftIndex()));
        assertEquals(0, thirdResult.getDailyPlanQty().intValue(), "保留排序靠后的机台应被同班次归集清空");
        assertEquals(20, sumScheduledQty(context), "同班次尾量归集不得改变 continuation group 总量");

        int remainderMachineCount = 0;
        for (LhScheduleResult result : Arrays.asList(firstResult, secondResult, thirdResult)) {
            Integer shiftQty = ShiftFieldUtil.getShiftPlanQty(result, morningShift.getShiftIndex());
            if (shiftQty != null && shiftQty > 0 && shiftQty < 16) {
                remainderMachineCount++;
            }
        }
        assertEquals(1, remainderMachineCount, "同班次尾量归集后最多只允许一台机台保留零头");
    }

    @Test
    public void scheduleReduceMould_shouldNotCreateUnscheduledResultWhenSharedQuotaGroupIsSatisfied() {
        ContinuousProductionStrategy strategy = new ContinuousProductionStrategy();
        LhScheduleContext context = buildMultiMachineContinuationContext(
                ConstructionStageEnum.FORMAL.getCode(), false, 48, 48, 10, 5, "K1101", "K1102");

        strategy.scheduleReduceMould(context);

        assertTrue(CollectionUtils.isEmpty(context.getUnscheduledResultList()),
                "共享账本多机台降模后，只要组内保留结果已满足目标量，就不应误记未排");
    }

    @Test
    public void scheduleReduceMould_shouldKeepFullEmbryoStockForEachSharedQuotaMachineResult() {
        ContinuousProductionStrategy strategy = new ContinuousProductionStrategy();
        LhScheduleContext context = buildMultiMachineContinuationContext(
                ConstructionStageEnum.FORMAL.getCode(), false, 96, 96, 10, 5, "K1101", "K1102");
        for (SkuScheduleDTO sku : context.getContinuousSkuList()) {
            sku.setEmbryoStock(120);
        }

        strategy.scheduleReduceMould(context);

        List<Integer> embryoStockList = context.getScheduleResultList().stream()
                .map(LhScheduleResult::getEmbryoStock)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        assertFalse(embryoStockList.isEmpty(), "共享账本多机台组应保留有效结果");
        assertTrue(embryoStockList.stream().allMatch(stock -> stock == 120),
                "共享账本多机台组的每条结果都应保留来源SKU的完整胎胚库存");
    }

    @Test
    public void refreshContinuousEndingFlagByResult_shouldNotBleedAcrossDifferentQuotaGroupsOfSameMaterial() {
        ContinuousProductionStrategy strategy = new ContinuousProductionStrategy();
        LhScheduleContext context = buildSameMaterialDifferentQuotaGroupContext();

        ReflectionTestUtils.invokeMethod(strategy, "refreshContinuousEndingFlagByResult", context);

        assertEquals("1", context.getScheduleResultList().get(0).getIsEnd(),
                "满足本组收尾目标的账本组应保留收尾标记");
        assertEquals("0", context.getScheduleResultList().get(1).getIsEnd(),
                "不同账本组即使物料相同，也不应被另一组的排量串成收尾");
    }

    @Test
    public void refreshContinuousEndingFlagByResult_shouldFailFastWhenSourceSkuMappingMissing() {
        ContinuousProductionStrategy strategy = new ContinuousProductionStrategy();
        LhScheduleContext context = buildSameMaterialDifferentQuotaGroupContext();
        context.getScheduleResultSourceSkuMap().clear();

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> ReflectionTestUtils.invokeMethod(strategy, "refreshContinuousEndingFlagByResult", context));
        assertTrue(exception.getMessage().contains("sourceSku"),
                "缺失来源映射时应显式报错，不能静默按 materialCode 串组");
    }

    @Test
    public void scheduleReduceMould_shouldUseDownMachineOnlyToFillFirstDayRemainingQty() {
        ContinuousProductionStrategy strategy = new ContinuousProductionStrategy();
        LhScheduleContext context = buildExcelContinuationContext(
                "3302001271", 154, 184, 184, 512, "1",
                new String[]{"K2022", "K1205", "K1614", "K1906", "K1412"});

        strategy.scheduleReduceMould(context);

        LhScheduleResult downMachine = findResultByMachineCode(context, "K1412");
        assertEquals(Integer.valueOf(16), ShiftFieldUtil.getShiftPlanQty(downMachine, 1));
        assertEquals(Integer.valueOf(10), ShiftFieldUtil.getShiftPlanQty(downMachine, 2));
        assertEquals(Integer.valueOf(16), ShiftFieldUtil.getShiftPlanQty(downMachine, 3));
        assertEquals(42, downMachine.getDailyPlanQty().intValue(),
                "下机机台补足day1剩余量后若中班结束进入不可换模晚班，应继续补满晚班");
        assertEquals(5, context.getScheduleResultList().size(), "第一天下机机台有排产量，不应被零计划收口移除");
    }

    @Test
    public void scheduleReduceMould_shouldReduceMachinesByDayForExcelThirdScenario() {
        ContinuousProductionStrategy strategy = new ContinuousProductionStrategy();
        LhScheduleContext context = buildExcelContinuationContext(
                "3302000465", 96, 96, 96, 288, "1",
                new String[]{"K1601", "K1801", "K1505", "K1504", "K1213"});

        strategy.scheduleReduceMould(context);

        LhScheduleResult firstKept = findResultByMachineCode(context, "K1601");
        LhScheduleResult secondKept = findResultByMachineCode(context, "K1801");
        LhScheduleResult firstDayOnly = findResultByMachineCode(context, "K1505");
        assertEquals(128, firstKept.getDailyPlanQty().intValue());
        assertEquals(128, secondKept.getDailyPlanQty().intValue());
        assertEquals(48, firstDayOnly.getDailyPlanQty().intValue());
        assertEquals(Integer.valueOf(16), ShiftFieldUtil.getShiftPlanQty(firstDayOnly, 1));
        assertEquals(Integer.valueOf(16), ShiftFieldUtil.getShiftPlanQty(firstDayOnly, 2));
        assertEquals(Integer.valueOf(16), ShiftFieldUtil.getShiftPlanQty(firstDayOnly, 3));
        assertEquals(3, context.getScheduleResultList().size(), "零排产机台应释放并移出续作结果");
    }

    @Test
    public void scheduleReduceMould_shouldKeepSingleMachineNonEndingFullWindow() {
        ContinuousProductionStrategy strategy = new ContinuousProductionStrategy();
        LhScheduleContext context = buildExcelContinuationContext(
                "3302002336", 8, 48, 48, 104, "1", new String[]{"K1307"});

        strategy.scheduleReduceMould(context);

        LhScheduleResult result = findResultByMachineCode(context, "K1307");
        assertEquals(128, result.getDailyPlanQty().intValue(), "单机台非收尾续作不执行降模，应排满3天8班");
        assertEquals(Integer.valueOf(16), ShiftFieldUtil.getShiftPlanQty(result, 8));
    }

    @Test
    public void scheduleReduceMould_shouldStartFromNextDayWhenFirstDayHasNoPlan() {
        ContinuousProductionStrategy strategy = new ContinuousProductionStrategy();
        LhScheduleContext context = buildExcelContinuationContext(
                "3302001075", 0, 32, 46, 78, "1", new String[]{"K1406", "K1712"});

        strategy.scheduleReduceMould(context);

        LhScheduleResult kept = findResultByMachineCode(context, "K1406");
        assertEquals(Integer.valueOf(0), ShiftFieldUtil.getShiftPlanQty(kept, 1));
        assertEquals(Integer.valueOf(0), ShiftFieldUtil.getShiftPlanQty(kept, 2));
        assertEquals(Integer.valueOf(16), ShiftFieldUtil.getShiftPlanQty(kept, 3));
        assertEquals(Integer.valueOf(16), ShiftFieldUtil.getShiftPlanQty(kept, 8));
        assertEquals(1, context.getScheduleResultList().size(), "day1无计划时多余续作机台应释放");
    }

    @Test
    public void scheduleReduceMould_shouldRecordReleasedMachineWhenZeroPlanResultRemoved() {
        ContinuousProductionStrategy strategy = new ContinuousProductionStrategy();
        LhScheduleContext context = buildExcelContinuationContext(
                "3302001075", 0, 32, 46, 78, "1", new String[]{"K1406", "K1712"});

        strategy.scheduleReduceMould(context);

        assertTrue(context.getReleasedContinuousMachineCodeSet().contains("K1712"),
                "day1无计划导致零计划续作结果移除时，应记录释放机台供新增选机降优先级使用");
    }

    @Test
    public void scheduleContinuousEnding_shouldReleaseSingleMachineWhenFirstDayHasNoPlan()
            throws Exception {
        ContinuousProductionStrategy strategy = new ContinuousProductionStrategy();
        injectField(strategy, "orderNoGenerator", new OrderNoGenerator());
        injectField(strategy, "targetScheduleQtyResolver", new TargetScheduleQtyResolver());
        injectField(strategy, "endingJudgmentStrategy", new StubEndingJudgmentStrategy());

        LhScheduleContext context = buildSingleMachineDayOneNoPlanContinuousContext();

        strategy.scheduleContinuousEnding(context);

        assertEquals(0, context.getScheduleResultList().size(),
                "day1无计划但后续仍有计划时，原续作机台不应继续从day2起排续作");
        assertTrue(context.getReleasedContinuousMachineCodeSet().contains("K1501L"),
                "续作首日无计划时，应记录原续作机台，供换活字块和新增排产消费");
        assertTrue(context.getFirstDayNoPlanReleasedContinuousMachineCodeSet().contains("K1501L"),
                "首日无计划但后续有计划的释放机台应写入稳定识别集合");
    }

    @Test
    public void scheduleContinuousEnding_shouldKeepMachineStateReleasedWhenFirstDayHasNoPlan()
            throws Exception {
        ContinuousProductionStrategy strategy = new ContinuousProductionStrategy();
        injectField(strategy, "orderNoGenerator", new OrderNoGenerator());
        injectField(strategy, "targetScheduleQtyResolver", new TargetScheduleQtyResolver());
        injectField(strategy, "endingJudgmentStrategy", new StubEndingJudgmentStrategy());

        LhScheduleContext context = buildSingleMachineDayOneNoPlanContinuousContext();
        MachineScheduleDTO machine = context.getMachineScheduleMap().get("K1501L");
        machine.setCurrentMaterialCode("3302001075");
        machine.setCurrentMaterialDesc("3302001075");
        machine.setEstimatedEndTime(null);

        strategy.scheduleContinuousEnding(context);

        assertTrue(context.getReleasedContinuousMachineCodeSet().contains("K1501L"),
                "首日无计划的续作机台应进入释放集合");
        assertNull(machine.getEstimatedEndTime(),
                "首日无计划但后续仍有计划时，S4.4 收口后不应把续作结果终态继续占在机台运行态上");
    }

    @Test
    public void scheduleContinuousEnding_shouldPreRegisterFirstDayNoPlanMachineBeforeProcessingOtherContinuousSku()
            throws Exception {
        ContinuousProductionStrategy strategy = new ContinuousProductionStrategy();
        injectField(strategy, "orderNoGenerator", new OrderNoGenerator());
        injectField(strategy, "targetScheduleQtyResolver", new TargetScheduleQtyResolver());
        injectField(strategy, "endingJudgmentStrategy", new AssertReleasedMachineRegisteredBeforeFirstSkuStrategy());

        LhScheduleContext context = buildPreRegisterReleasedMachineContext();

        strategy.scheduleContinuousEnding(context);

        assertTrue(context.getReleasedContinuousMachineCodeSet().contains("K1105"),
                "首日无计划释放机台应在续作主循环开始前完成预登记，供S4.4内新增预判选机降优先级");
    }

    @Test
    public void resolveContinuousActualMouldCode_shouldUseOnlineInMachineMouldCode() throws Exception {
        ContinuousProductionStrategy strategy = new ContinuousProductionStrategy();
        LhScheduleContext context = new LhScheduleContext();
        MachineScheduleDTO machine = new MachineScheduleDTO();
        machine.setMachineCode("K1501");
        machine.setMaxMoldNum(2);
        SkuScheduleDTO sku = sku("MAT-CONTINUOUS");
        LhMachineOnlineInfo onlineInfo = new LhMachineOnlineInfo();
        onlineInfo.setLhCode("K1501");
        onlineInfo.setInMachineMouldCode("CM001, CM002");
        context.getMachineOnlineInfoMap().put("K1501", onlineInfo);

        String mouldCode = ReflectionTestUtils.invokeMethod(
                strategy, "resolveContinuousActualMouldCode", context, machine, sku);

        assertEquals("CM001,CM002", mouldCode,
                "续作结果模具号必须取硫化在机信息中的实际在机模具号");
    }

    @Test
    public void scheduleContinuousEnding_shouldReleaseMachineWhenWindowHasNoDailyPlan() throws Exception {
        ContinuousProductionStrategy strategy = new ContinuousProductionStrategy();
        injectField(strategy, "endingJudgmentStrategy", new StubEndingJudgmentStrategy());
        LhScheduleContext context = buildWindowNoPlanContinuousContext();
        SkuScheduleDTO sku = context.getContinuousSkuList().get(0);
        sku.setSurplusQty(0);
        sku.setPendingQty(0);
        sku.setTargetScheduleQty(0);
        sku.setFutureMonthPlanQtyAfterWindow(62);

        strategy.scheduleContinuousEnding(context);

        assertEquals(0, context.getScheduleResultList().size(), "窗口内无日计划的续作SKU不应占用机台");
        assertEquals(1, context.getUnscheduledResultList().size());
        assertTrue(context.getUnscheduledResultList().get(0).getUnscheduledReason()
                .contains("当前排程窗口内无日计划量"));
        assertTrue(context.getReleasedContinuousMachineCodeSet().contains("K1712"),
                "窗口全无日计划时，应释放续作机台给新增排产，但只作为降优先级标识");
    }

    @Test
    public void scheduleContinuousEnding_shouldReleaseWhenWindowNoPlanButFuturePlanExistsEvenWithSurplus()
            throws Exception {
        ContinuousProductionStrategy strategy = new ContinuousProductionStrategy();
        injectField(strategy, "orderNoGenerator", new OrderNoGenerator());
        injectField(strategy, "targetScheduleQtyResolver", new TargetScheduleQtyResolver());
        injectField(strategy, "endingJudgmentStrategy", new StubEndingJudgmentStrategy());
        LhScheduleContext context = buildWindowNoPlanContinuousContext();
        SkuScheduleDTO sku = context.getContinuousSkuList().get(0);
        sku.setSurplusQty(80);
        sku.setPendingQty(80);
        sku.setTargetScheduleQty(80);
        sku.setFutureMonthPlanQtyAfterWindow(62);

        strategy.scheduleContinuousEnding(context);

        assertEquals(0, context.getScheduleResultList().size(),
                "窗口全无日计划且后续远期才有计划时，即使有硫化余量也不能提前排产");
        assertEquals(1, context.getUnscheduledResultList().size());
        assertTrue(context.getUnscheduledResultList().get(0).getUnscheduledReason()
                .contains("当前排程窗口内无日计划量"));
        assertTrue(context.getReleasedContinuousMachineCodeSet().contains("K1712"),
                "窗口无计划且未来有计划的续作机台应释放给当前窗口其他SKU使用");
    }

    @Test
    public void scheduleContinuousEnding_shouldExposeMachineAfterSurplusFinished() throws Exception {
        ContinuousProductionStrategy strategy = new ContinuousProductionStrategy();
        injectField(strategy, "orderNoGenerator", new OrderNoGenerator());
        injectField(strategy, "targetScheduleQtyResolver", new TargetScheduleQtyResolver());
        injectField(strategy, "endingJudgmentStrategy", new StubEndingJudgmentStrategy());
        LhScheduleContext context = buildWindowNoPlanContinuousContext();
        SkuScheduleDTO sku = context.getContinuousSkuList().get(0);
        sku.setSurplusQty(20);
        sku.setPendingQty(20);
        sku.setTargetScheduleQty(20);
        sku.setFutureMonthPlanQtyAfterWindow(0);

        strategy.scheduleContinuousEnding(context);
        strategy.scheduleReduceMould(context);

        assertFalse(context.getScheduleResultList().isEmpty(), "有余量的续作必须生成排产结果");
        LhScheduleResult result = context.getScheduleResultList().get(0);
        MachineScheduleDTO machine = context.getMachineScheduleMap().get("K1712");
        assertEquals("1", result.getIsEnd());
        assertTrue(machine.isEnding());
        assertEquals(result.getSpecEndTime(), machine.getEstimatedEndTime());
    }

    @Test
    public void scheduleContinuousEnding_shouldScheduleEndingWhenWindowAndFuturePlanEmptyButHistoryShortageExists()
            throws Exception {
        ContinuousProductionStrategy strategy = new ContinuousProductionStrategy();
        injectField(strategy, "orderNoGenerator", new OrderNoGenerator());
        injectField(strategy, "targetScheduleQtyResolver", new TargetScheduleQtyResolver());
        injectField(strategy, "endingJudgmentStrategy", new StubEndingJudgmentStrategy());
        LhScheduleContext context = buildWindowNoPlanContinuousContext();
        SkuScheduleDTO sku = context.getContinuousSkuList().get(0);
        sku.setTargetScheduleQty(40);
        sku.setPendingQty(40);
        sku.setSurplusQty(40);
        sku.setMonthlyHistoryShortageQty(40);

        strategy.scheduleContinuousEnding(context);

        assertEquals(1, context.getScheduleResultList().size(),
                "窗口和月底均无计划但存在本月历史欠产时，续作应按收尾清量而不是直接释放");
        LhScheduleResult result = context.getScheduleResultList().get(0);
        assertEquals("1", result.getIsEnd(), "窗口和月底均无计划时应按收尾口径落结果");
        assertTrue(result.getDailyPlanQty() > 0, "历史欠产清量必须真实落产量，不能只生成0量结果");
        assertTrue(result.getDailyPlanQty() <= 40, "收尾清量不允许超出目标量");
        assertTrue(!context.getReleasedContinuousMachineCodeSet().contains("K1712"),
                "存在本月历史欠产时不应把续作机台登记为窗口无计划释放机台");
        assertEquals(40, sku.getEffectiveCarryForwardQty(), "历史欠产真实入账后应同步已入账状态");
        assertEquals(0, context.getUnscheduledResultList().size());
    }

    @Test
    public void scheduleReduceMould_shouldKeepDynamicEndingQtyWhenWindowAndFuturePlanEmpty()
            throws Exception {
        ContinuousProductionStrategy strategy = new ContinuousProductionStrategy();
        injectField(strategy, "orderNoGenerator", new OrderNoGenerator());
        injectField(strategy, "targetScheduleQtyResolver", new TargetScheduleQtyResolver());
        injectField(strategy, "endingJudgmentStrategy", new StubEndingJudgmentStrategy());
        LhScheduleContext context = buildWindowNoPlanContinuousContext();
        SkuScheduleDTO sku = context.getContinuousSkuList().get(0);
        sku.setMaterialCode("3302002182");
        sku.setTargetScheduleQty(144);
        sku.setPendingQty(144);
        sku.setSurplusQty(83);
        sku.setEmbryoStock(33);
        sku.setShiftCapacity(18);
        sku.setMonthlyHistoryShortageQty(17);
        MachineScheduleDTO machine = context.getMachineScheduleMap().get("K1712");
        machine.setMaxMoldNum(2);
        MdmSkuLhCapacity capacity = new MdmSkuLhCapacity();
        capacity.setMaterialCode("3302002182");
        capacity.setClassCapacity(18);
        capacity.setStandardCapacity(52);
        context.getSkuLhCapacityMap().put("3302002182", capacity);

        strategy.scheduleContinuousEnding(context);
        strategy.scheduleReduceMould(context);

        assertEquals(SkuTagEnum.ENDING.getCode(), sku.getSkuTag(),
                "窗口及月底均无计划的动态收尾必须同步统一收尾标签");
        assertEquals(1, sku.getEndingDaysRemaining(),
                "动态收尾应按当前窗口可完成口径标记剩余一天");
        assertEquals(1, context.getScheduleResultList().size());
        LhScheduleResult result = context.getScheduleResultList().get(0);
        assertEquals(84, ShiftFieldUtil.resolveScheduledQty(result),
                "余量83在双模机台应按既有模数规则排成84，不能回裁为历史欠产17");
        assertEquals("1", result.getIsEnd(), "完整排完动态收尾目标后结果必须标记收尾");
        assertTrue(result.getClass2PlanQty() > 0,
                "动态收尾必须跨多个班次排产，不能只保留C1");
    }

    @Test
    public void scheduleReduceMould_shouldAppendCompensationAfterFirstDayNoPlanRelease()
            throws Exception {
        ContinuousProductionStrategy strategy = new ContinuousProductionStrategy();
        injectField(strategy, "orderNoGenerator", new OrderNoGenerator());
        injectField(strategy, "targetScheduleQtyResolver", new TargetScheduleQtyResolver());
        injectField(strategy, "endingJudgmentStrategy", new StubEndingJudgmentStrategy());

        LhScheduleContext context = buildSingleMachineDayOneNoPlanContinuousContext();

        strategy.scheduleContinuousEnding(context);
        strategy.scheduleReduceMould(context);

        assertEquals(0, context.getScheduleResultList().size(),
                "首日无计划释放后不应残留原续作排产结果");
        assertEquals(1, context.getNewSpecSkuList().size(),
                "day2/day3仍有计划量时，原续作SKU应转入新增排产补偿");
        SkuScheduleDTO compensationSku = context.getNewSpecSkuList().get(0);
        SkuScheduleDTO sourceSku = context.getContinuousSkuList().get(0);
        assertEquals("3302001075", compensationSku.getMaterialCode());
        assertSame(sourceSku.getDailyPlanQuotaMap(), compensationSku.getDailyPlanQuotaMap(),
                "补偿SKU必须共享原续作SKU日计划账本，避免day2/day3计划量丢失");
        assertNull(compensationSku.getContinuousMachineCode(),
                "补偿SKU应交由新增换模链路重新选机");
        assertEquals("K1501L",
                ReflectionTestUtils.getField(compensationSku, "preferredContinuousMachineCode"),
                "补偿SKU应保留原续作机台，供新增阶段轮到自己时优先识别");
    }

    @Test
    public void scheduleContinuousEnding_shouldStrictlyScheduleShortageWhenWindowNoPlanButFuturePlanExists()
            throws Exception {
        ContinuousProductionStrategy strategy = new ContinuousProductionStrategy();
        injectField(strategy, "orderNoGenerator", new OrderNoGenerator());
        injectField(strategy, "targetScheduleQtyResolver", new TargetScheduleQtyResolver());
        injectField(strategy, "endingJudgmentStrategy", new StubEndingJudgmentStrategy());
        LhScheduleContext context = buildWindowNoPlanContinuousContext();
        SkuScheduleDTO sku = context.getContinuousSkuList().get(0);
        sku.setTargetScheduleQty(120);
        sku.setPendingQty(120);
        sku.setSurplusQty(120);
        sku.setMonthlyHistoryShortageQty(40);
        sku.setFutureMonthPlanQtyAfterWindow(200);

        strategy.scheduleContinuousEnding(context);

        assertEquals(1, context.getScheduleResultList().size(),
                "窗口无计划但月底仍有计划时，续作本窗口应仅补本月历史欠产");
        LhScheduleResult result = context.getScheduleResultList().get(0);
        assertEquals("0", result.getIsEnd(), "月底仍有后续计划时不能把SKU判定为整体收尾");
        assertTrue(sku.isStrictNewSpecShortageOnly(), "仅补历史欠产场景必须启用严格目标量");
        assertTrue(result.getDailyPlanQty() <= 40, "仅补本月欠产时不允许提前消耗T+3以后计划");
        assertTrue(!StringUtils.equals(SkuTagEnum.ENDING.getCode(), sku.getSkuTag()),
                "月底仍有计划的仅补欠产场景不能误标为收尾");
    }

    @Test
    public void prepareShortageQuota_shouldNotMarkCarryForwardWhenQuotaMapEmpty() {
        LhScheduleContext context = new LhScheduleContext();
        context.setScheduleDate(toDate(2026, 5, 1, 0, 0, 0));

        SkuScheduleDTO sku = buildContinuationSku(
                "MAT-EMPTY-QUOTA", ConstructionStageEnum.FORMAL.getCode(), false,
                40, new LinkedHashMap<LocalDate, SkuDailyPlanQuotaDTO>(0));
        sku.setMonthlyHistoryShortageQty(40);

        DailyMachineExpansionPlanner.prepareShortageQuota(context, sku, "续作排产测试");

        assertEquals(0, sku.getEffectiveCarryForwardQty(),
                "日计划账本为空时不能把未实际追加的历史欠产标记为已入账");
        assertTrue(sku.getDailyPlanQuotaMap().isEmpty(), "空账本场景不能通过兜底造账本");
    }

    private LhScheduleContext buildExcelContinuationContext(String materialCode,
                                                            int firstDayQty,
                                                            int secondDayQty,
                                                            int thirdDayQty,
                                                            int targetQty,
                                                            String lookAheadDays,
                                                            String[] machineCodes) {
        LhScheduleContext context = new LhScheduleContext();
        context.setFactoryCode("116");
        context.setBatchNo("LHPC-TEST-EXCEL");
        context.setScheduleConfig(new LhScheduleConfig(Collections.singletonMap(
                LhScheduleParamConstant.CONTINUOUS_SHORTAGE_LOOK_AHEAD_DAYS, lookAheadDays)));
        context.setScheduleDate(toDate(2026, 5, 1, 0, 0, 0));
        context.setScheduleTargetDate(toDate(2026, 5, 3, 0, 0, 0));
        context.setScheduleWindowShifts(LhScheduleTimeUtil.buildDefaultScheduleShifts(context, context.getScheduleDate()));

        List<LhShiftConfigVO> shifts = context.getScheduleWindowShifts();
        Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap = buildQuotaMap(
                shifts.get(0), shifts.get(2), shifts.get(5), firstDayQty, secondDayQty, thirdDayQty);
        List<SkuScheduleDTO> continuousSkuList = new ArrayList<SkuScheduleDTO>(machineCodes.length);
        for (int index = 0; index < machineCodes.length; index++) {
            String machineCode = machineCodes[index];
            addMachine(context, machineCode, machineCodes.length - index);
            SkuScheduleDTO sku = buildContinuationSku(
                    materialCode, ConstructionStageEnum.FORMAL.getCode(), false, targetQty, quotaMap);
            sku.setContinuousMachineCode(machineCode);
            continuousSkuList.add(sku);

            LhScheduleResult result = buildFullWindowContinuationResult(materialCode, machineCode, shifts);
            context.getScheduleResultList().add(result);
            context.getScheduleResultSourceSkuMap().put(result, sku);
            context.getMachineAssignmentMap().put(machineCode,
                    new ArrayList<LhScheduleResult>(Collections.singletonList(result)));
        }
        context.setContinuousSkuList(continuousSkuList);
        return context;
    }

    private LhScheduleContext buildSingleMachineDayOneNoPlanContinuousContext() {
        LhScheduleContext context = new LhScheduleContext();
        context.setFactoryCode("116");
        context.setBatchNo("LHPC-TEST-DAY1-NO-PLAN");
        context.setScheduleConfig(new LhScheduleConfig(Collections.singletonMap(
                LhScheduleParamConstant.CONTINUOUS_SHORTAGE_LOOK_AHEAD_DAYS, "1")));
        context.setScheduleDate(toDate(2026, 5, 1, 0, 0, 0));
        context.setScheduleTargetDate(toDate(2026, 5, 3, 0, 0, 0));
        context.setScheduleWindowShifts(LhScheduleTimeUtil.buildDefaultScheduleShifts(context, context.getScheduleDate()));
        addMachine(context, "K1501L", 1);

        List<LhShiftConfigVO> shifts = context.getScheduleWindowShifts();
        Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap = buildQuotaMap(
                shifts.get(0), shifts.get(2), shifts.get(5), 0, 32, 46);
        SkuScheduleDTO sku = buildContinuationSku(
                "3302001075", ConstructionStageEnum.FORMAL.getCode(), false, 78, quotaMap);
        sku.setContinuousMachineCode("K1501L");
        context.setContinuousSkuList(Collections.singletonList(sku));
        return context;
    }

    private LhScheduleContext buildPreRegisterReleasedMachineContext() {
        LhScheduleContext context = new LhScheduleContext();
        context.setFactoryCode("116");
        context.setBatchNo("LHPC-TEST-PRE-REGISTER");
        context.setScheduleConfig(new LhScheduleConfig(Collections.singletonMap(
                LhScheduleParamConstant.CONTINUOUS_SHORTAGE_LOOK_AHEAD_DAYS, "1")));
        context.setScheduleDate(toDate(2026, 5, 1, 0, 0, 0));
        context.setScheduleTargetDate(toDate(2026, 5, 3, 0, 0, 0));
        context.setScheduleWindowShifts(LhScheduleTimeUtil.buildDefaultScheduleShifts(context, context.getScheduleDate()));
        addMachine(context, "K1002", 1);
        addMachine(context, "K1105", 1);

        List<LhShiftConfigVO> shifts = context.getScheduleWindowShifts();
        Map<LocalDate, SkuDailyPlanQuotaDTO> normalQuotaMap = buildQuotaMap(
                shifts.get(0), shifts.get(2), shifts.get(5), 32, 0, 0);
        SkuScheduleDTO normalSku = buildContinuationSku(
                "MAT-FIRST", ConstructionStageEnum.FORMAL.getCode(), false, 32, normalQuotaMap);
        normalSku.setContinuousMachineCode("K1002");

        Map<LocalDate, SkuDailyPlanQuotaDTO> releasedQuotaMap = buildQuotaMap(
                shifts.get(0), shifts.get(2), shifts.get(5), 0, 32, 46);
        SkuScheduleDTO releasedSku = buildContinuationSku(
                "3302002546", ConstructionStageEnum.FORMAL.getCode(), false, 78, releasedQuotaMap);
        releasedSku.setContinuousMachineCode("K1105");
        context.setContinuousSkuList(Arrays.asList(normalSku, releasedSku));
        return context;
    }

    private LhScheduleContext buildWindowNoPlanContinuousContext() {
        LhScheduleContext context = new LhScheduleContext();
        context.setFactoryCode("116");
        context.setBatchNo("LHPC-TEST-NO-PLAN");
        context.setScheduleDate(toDate(2026, 5, 1, 0, 0, 0));
        context.setScheduleTargetDate(toDate(2026, 5, 3, 0, 0, 0));
        context.setScheduleWindowShifts(LhScheduleTimeUtil.buildDefaultScheduleShifts(context, context.getScheduleDate()));
        addMachine(context, "K1712", 1);

        List<LhShiftConfigVO> shifts = context.getScheduleWindowShifts();
        Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap = buildQuotaMap(
                shifts.get(0), shifts.get(2), shifts.get(5), 0, 0, 0);
        SkuScheduleDTO sku = buildContinuationSku(
                "3302001077", ConstructionStageEnum.FORMAL.getCode(), false, 80, quotaMap);
        sku.setContinuousMachineCode("K1712");
        context.setContinuousSkuList(Collections.singletonList(sku));
        return context;
    }

    /**
     * 构建同SKU两台续作降模测试上下文。
     *
     * @param constructionStage 施工阶段
     * @param ending 是否收尾
     * @param targetQty 目标量
     * @param dayPlanQty 当日dayN计划量
     * @param firstCapsuleUsage 第一台胶囊使用次数
     * @param secondCapsuleUsage 第二台胶囊使用次数
     * @param firstMachineCode 第一台机台
     * @param secondMachineCode 第二台机台
     * @return 排程上下文
     */
    private LhScheduleContext buildMultiMachineContinuationContext(String constructionStage,
                                                                   boolean ending,
                                                                   int targetQty,
                                                                   int dayPlanQty,
                                                                   int firstCapsuleUsage,
                                                                   int secondCapsuleUsage,
                                                                   String firstMachineCode,
                                                                   String secondMachineCode) {
        LhScheduleContext context = new LhScheduleContext();
        context.setFactoryCode("116");
        context.setBatchNo("LHPC-TEST-CONTINUATION");
        context.setScheduleDate(toDate(2026, 5, 1, 0, 0, 0));
        context.setScheduleTargetDate(toDate(2026, 5, 3, 0, 0, 0));
        context.setScheduleWindowShifts(LhScheduleTimeUtil.buildDefaultScheduleShifts(context, context.getScheduleDate()));

        addMachine(context, firstMachineCode, firstCapsuleUsage);
        addMachine(context, secondMachineCode, secondCapsuleUsage);

        List<LhShiftConfigVO> shifts = context.getScheduleWindowShifts();
        LhShiftConfigVO firstShift = shifts.get(0);
        Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap = new LinkedHashMap<LocalDate, SkuDailyPlanQuotaDTO>(4);
        quotaMap.put(toLocalDate(firstShift), quota("MAT-MULTI", toLocalDate(firstShift), dayPlanQty));

        SkuScheduleDTO sourceSku = buildContinuationSku("MAT-MULTI", constructionStage, ending, targetQty, quotaMap);
        sourceSku.setContinuousMachineCode(firstMachineCode);
        SkuScheduleDTO copySku = buildContinuationSku("MAT-MULTI", constructionStage, ending, targetQty, quotaMap);
        copySku.setContinuousMachineCode(secondMachineCode);
        context.setContinuousSkuList(Arrays.asList(sourceSku, copySku));

        LhScheduleResult firstResult = buildContinuationResult(
                "MAT-MULTI", firstMachineCode, ending, shifts, 16, 16, 16);
        LhScheduleResult secondResult = buildContinuationResult(
                "MAT-MULTI", secondMachineCode, ending, shifts, 16, 16, 16);
        context.getScheduleResultList().add(firstResult);
        context.getScheduleResultList().add(secondResult);
        context.getScheduleResultSourceSkuMap().put(firstResult, sourceSku);
        context.getScheduleResultSourceSkuMap().put(secondResult, copySku);
        context.getMachineAssignmentMap().put(firstMachineCode, new ArrayList<LhScheduleResult>(Collections.singletonList(firstResult)));
        context.getMachineAssignmentMap().put(secondMachineCode, new ArrayList<LhScheduleResult>(Collections.singletonList(secondResult)));
        return context;
    }

    private LhScheduleContext buildThreeMachineEndingContinuationContext() {
        LhScheduleContext context = new LhScheduleContext();
        context.setFactoryCode("116");
        context.setBatchNo("LHPC-TEST-CONTINUATION-ENDING");
        context.setScheduleDate(toDate(2026, 6, 13, 0, 0, 0));
        context.setScheduleTargetDate(toDate(2026, 6, 14, 0, 0, 0));
        context.setScheduleWindowShifts(LhScheduleTimeUtil.buildDefaultScheduleShifts(context, context.getScheduleDate()));

        addMachine(context, "K1516", 1);
        addMachine(context, "K1905", 10);
        addMachine(context, "K1924", 5);

        List<LhShiftConfigVO> shifts = context.getScheduleWindowShifts();
        LhShiftConfigVO firstShift = shifts.get(0);
        LhShiftConfigVO nextDayShift = resolveNextWorkDateShift(shifts, firstShift);
        LhShiftConfigVO thirdDayShift = resolveNextWorkDateShift(shifts, nextDayShift);
        Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap = buildQuotaMap(
                firstShift, nextDayShift, thirdDayShift, 30, 0, 0);

        SkuScheduleDTO firstSku = buildContinuationSku(
                "3302002326", ConstructionStageEnum.FORMAL.getCode(), true, 210, quotaMap);
        firstSku.setContinuousMachineCode("K1516");
        SkuScheduleDTO secondSku = buildContinuationSku(
                "3302002326", ConstructionStageEnum.FORMAL.getCode(), true, 210, quotaMap);
        secondSku.setContinuousMachineCode("K1905");
        SkuScheduleDTO thirdSku = buildContinuationSku(
                "3302002326", ConstructionStageEnum.FORMAL.getCode(), true, 210, quotaMap);
        thirdSku.setContinuousMachineCode("K1924");
        context.setContinuousSkuList(Arrays.asList(firstSku, secondSku, thirdSku));

        LhScheduleResult firstResult = buildContinuationResult(
                "3302002326", "K1516", true, shifts, 12, 0, 0);
        LhScheduleResult secondResult = buildContinuationResult(
                "3302002326", "K1905", true, shifts, 16, 16, 16);
        LhScheduleResult thirdResult = buildContinuationResult(
                "3302002326", "K1924", true, shifts, 16, 16, 16);
        context.getScheduleResultList().add(firstResult);
        context.getScheduleResultList().add(secondResult);
        context.getScheduleResultList().add(thirdResult);
        context.getScheduleResultSourceSkuMap().put(firstResult, firstSku);
        context.getScheduleResultSourceSkuMap().put(secondResult, secondSku);
        context.getScheduleResultSourceSkuMap().put(thirdResult, thirdSku);
        context.getMachineAssignmentMap().put("K1516",
                new ArrayList<LhScheduleResult>(Collections.singletonList(firstResult)));
        context.getMachineAssignmentMap().put("K1905",
                new ArrayList<LhScheduleResult>(Collections.singletonList(secondResult)));
        context.getMachineAssignmentMap().put("K1924",
                new ArrayList<LhScheduleResult>(Collections.singletonList(thirdResult)));
        return context;
    }

    private void replaceContinuousQuotaWithFirstDayOnly(LhScheduleContext context, int firstDayPlanQty) {
        List<LhShiftConfigVO> shifts = context.getScheduleWindowShifts();
        LhShiftConfigVO firstShift = shifts.get(0);
        Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap = new LinkedHashMap<LocalDate, SkuDailyPlanQuotaDTO>(2);
        quotaMap.put(toLocalDate(firstShift), quota("3302002326", toLocalDate(firstShift), firstDayPlanQty));
        for (SkuScheduleDTO sku : context.getContinuousSkuList()) {
            sku.setDailyPlanQuotaMap(quotaMap);
        }
    }

    private LhScheduleContext buildMultiDayContinuationContext(String constructionStage,
                                                               int targetQty,
                                                               int firstDayQty,
                                                               int secondDayQty,
                                                               int firstCapsuleUsage,
                                                               int secondCapsuleUsage,
                                                               String firstMachineCode,
                                                               String secondMachineCode) {
        return buildMultiDayContinuationContext(constructionStage, targetQty, firstDayQty, secondDayQty, 0,
                firstCapsuleUsage, secondCapsuleUsage, firstMachineCode, secondMachineCode);
    }

    private LhScheduleContext buildMultiDayContinuationContext(String constructionStage,
                                                               int targetQty,
                                                               int firstDayQty,
                                                               int secondDayQty,
                                                               int thirdDayQty,
                                                               int firstCapsuleUsage,
                                                               int secondCapsuleUsage,
                                                               String firstMachineCode,
                                                               String secondMachineCode) {
        LhScheduleContext context = new LhScheduleContext();
        context.setFactoryCode("116");
        context.setBatchNo("LHPC-TEST-CONTINUATION-BY-DAY");
        context.setScheduleDate(toDate(2026, 5, 1, 0, 0, 0));
        context.setScheduleTargetDate(toDate(2026, 5, 3, 0, 0, 0));
        context.setScheduleWindowShifts(LhScheduleTimeUtil.buildDefaultScheduleShifts(context, context.getScheduleDate()));

        addMachine(context, firstMachineCode, firstCapsuleUsage);
        addMachine(context, secondMachineCode, secondCapsuleUsage);

        List<LhShiftConfigVO> shifts = context.getScheduleWindowShifts();
        LhShiftConfigVO firstShift = shifts.get(0);
        LhShiftConfigVO nextDayShift = resolveNextWorkDateShift(shifts, firstShift);
        LhShiftConfigVO thirdDayShift = resolveNextWorkDateShift(shifts, nextDayShift);
        Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap = buildQuotaMap(
                firstShift, nextDayShift, thirdDayShift, firstDayQty, secondDayQty, thirdDayQty);

        SkuScheduleDTO sourceSku = buildContinuationSku("MAT-MULTI-DAY", constructionStage, false, targetQty, quotaMap);
        sourceSku.setContinuousMachineCode(firstMachineCode);
        SkuScheduleDTO copySku = buildContinuationSku("MAT-MULTI-DAY", constructionStage, false, targetQty, quotaMap);
        copySku.setContinuousMachineCode(secondMachineCode);
        context.setContinuousSkuList(Arrays.asList(sourceSku, copySku));

        LhScheduleResult firstResult = buildContinuousShiftResult(
                "MAT-MULTI-DAY", "EMB-MAT-MULTI-DAY", 0, "0", 16, 16, 16, 16, 16, 16, 0, 0);
        firstResult.setLhMachineCode(firstMachineCode);
        firstResult.setLhMachineName(firstMachineCode);
        firstResult.setLhTime(3600);
        firstResult.setMouldQty(1);
        firstResult.setSingleMouldShiftQty(16);
        fillShiftDateTime(firstResult, shifts, 6);

        LhScheduleResult secondResult = buildContinuousShiftResult(
                "MAT-MULTI-DAY", "EMB-MAT-MULTI-DAY", 0, "0", 16, 16, 16, 16, 16, 16, 0, 0);
        secondResult.setLhMachineCode(secondMachineCode);
        secondResult.setLhMachineName(secondMachineCode);
        secondResult.setLhTime(3600);
        secondResult.setMouldQty(1);
        secondResult.setSingleMouldShiftQty(16);
        fillShiftDateTime(secondResult, shifts, 6);

        context.getScheduleResultList().add(firstResult);
        context.getScheduleResultList().add(secondResult);
        context.getScheduleResultSourceSkuMap().put(firstResult, sourceSku);
        context.getScheduleResultSourceSkuMap().put(secondResult, copySku);
        context.getMachineAssignmentMap().put(firstMachineCode,
                new ArrayList<LhScheduleResult>(Collections.singletonList(firstResult)));
        context.getMachineAssignmentMap().put(secondMachineCode,
                new ArrayList<LhScheduleResult>(Collections.singletonList(secondResult)));
        return context;
    }

    /**
     * 构建续作同SKU多机台同班次尾量归集测试上下文。
     *
     * @param endingShiftType 收尾班次类型
     * @return 排程上下文
     */
    private LhScheduleContext buildMultiMachineEndingStaggerContext(String endingShiftType) {
        return buildMultiMachineEndingStaggerContext(endingShiftType, true);
    }

    private LhScheduleContext buildMultiMachineEndingStaggerContext(String endingShiftType, boolean ending) {
        LhScheduleContext context = new LhScheduleContext();
        context.setFactoryCode("116");
        context.setBatchNo("LHPC-TEST-STAGGER");
        context.setScheduleDate(toDate(2026, 5, 1, 0, 0, 0));
        context.setScheduleTargetDate(toDate(2026, 5, 3, 0, 0, 0));
        context.setScheduleWindowShifts(LhScheduleTimeUtil.buildDefaultScheduleShifts(context, context.getScheduleDate()));

        addMachine(context, "K1101", 10);
        addMachine(context, "K1102", 5);

        List<LhShiftConfigVO> shifts = context.getScheduleWindowShifts();
        LhShiftConfigVO endingShift = findShiftByType(shifts, endingShiftType);
        Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap = new LinkedHashMap<LocalDate, SkuDailyPlanQuotaDTO>(4);
        quotaMap.put(toLocalDate(endingShift), quota("MAT-END-STAGGER", toLocalDate(endingShift), 16));

        SkuScheduleDTO firstSku = buildContinuationSku(
                "MAT-END-STAGGER", ConstructionStageEnum.FORMAL.getCode(), ending, 16, quotaMap);
        firstSku.setContinuousMachineCode("K1101");
        SkuScheduleDTO secondSku = buildContinuationSku(
                "MAT-END-STAGGER", ConstructionStageEnum.FORMAL.getCode(), ending, 16, quotaMap);
        secondSku.setContinuousMachineCode("K1102");
        context.setContinuousSkuList(Arrays.asList(firstSku, secondSku));

        LhScheduleResult firstResult = buildSingleShiftContinuationResult(
                "MAT-END-STAGGER", "K1101", endingShift, 8, ending);
        LhScheduleResult secondResult = buildSingleShiftContinuationResult(
                "MAT-END-STAGGER", "K1102", endingShift, 8, ending);
        context.getScheduleResultList().add(firstResult);
        context.getScheduleResultList().add(secondResult);
        context.getScheduleResultSourceSkuMap().put(firstResult, firstSku);
        context.getScheduleResultSourceSkuMap().put(secondResult, secondSku);
        context.getMachineAssignmentMap().put("K1101", new ArrayList<LhScheduleResult>(Collections.singletonList(firstResult)));
        context.getMachineAssignmentMap().put("K1102", new ArrayList<LhScheduleResult>(Collections.singletonList(secondResult)));
        return context;
    }

    private void addMachine(LhScheduleContext context, String machineCode, int capsuleUsageCount) {
        MachineScheduleDTO machine = new MachineScheduleDTO();
        machine.setMachineCode(machineCode);
        machine.setMachineName(machineCode);
        machine.setMaxMoldNum(1);
        machine.setCapsuleUsageCount(capsuleUsageCount);
        context.getMachineScheduleMap().put(machineCode, machine);
        context.getInitialMachineScheduleMap().put(machineCode, machine);
    }

    private SkuScheduleDTO buildContinuationSku(String materialCode,
                                                String constructionStage,
                                                boolean ending,
                                                int targetQty,
                                                Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap) {
        SkuScheduleDTO sku = new SkuScheduleDTO();
        sku.setMaterialCode(materialCode);
        sku.setMaterialDesc(materialCode);
        sku.setStructureName("STRUCT-" + materialCode);
        sku.setSpecCode("SPEC-" + materialCode);
        sku.setEmbryoCode("EMB-" + materialCode);
        sku.setConstructionStage(constructionStage);
        sku.setTrial(StringUtils.equals(ConstructionStageEnum.TRIAL.getCode(), constructionStage)
                || StringUtils.equals(ConstructionStageEnum.MASS_TRIAL.getCode(), constructionStage));
        sku.setStrictTargetQty(ending || StringUtils.equals(ConstructionStageEnum.TRIAL.getCode(), constructionStage));
        sku.setTargetScheduleQty(targetQty);
        sku.setPendingQty(targetQty);
        sku.setSurplusQty(ending ? targetQty : 800);
        sku.setEmbryoStock(0);
        sku.setShiftCapacity(16);
        sku.setLhTimeSeconds(3600);
        sku.setMouldQty(1);
        sku.setDailyPlanQuotaMap(quotaMap);
        sku.setWindowRemainingPlanQty(SkuDailyPlanQuotaUtil.sumRemainingQty(quotaMap));
        return sku;
    }

    private LhScheduleResult buildContinuationResult(String materialCode,
                                                     String machineCode,
                                                     boolean ending,
                                                     List<LhShiftConfigVO> shifts,
                                                     int class1Qty,
                                                     int class2Qty,
                                                     int class3Qty) {
        LhScheduleResult result = baseContinuationResult(materialCode, machineCode, ending);
        ShiftFieldUtil.setShiftPlanQty(result, shifts.get(0).getShiftIndex(), class1Qty,
                shifts.get(0).getShiftStartDateTime(), shifts.get(0).getShiftEndDateTime());
        ShiftFieldUtil.setShiftPlanQty(result, shifts.get(1).getShiftIndex(), class2Qty,
                shifts.get(1).getShiftStartDateTime(), shifts.get(1).getShiftEndDateTime());
        ShiftFieldUtil.setShiftPlanQty(result, shifts.get(2).getShiftIndex(), class3Qty,
                shifts.get(2).getShiftStartDateTime(), shifts.get(2).getShiftEndDateTime());
        ShiftFieldUtil.syncDailyPlanQty(result);
        result.setSpecEndTime(shifts.get(2).getShiftEndDateTime());
        result.setTdaySpecEndTime(result.getSpecEndTime());
        return result;
    }

    private LhScheduleResult buildFullWindowContinuationResult(String materialCode,
                                                               String machineCode,
                                                               List<LhShiftConfigVO> shifts) {
        LhScheduleResult result = baseContinuationResult(materialCode, machineCode, false);
        for (LhShiftConfigVO shift : shifts) {
            ShiftFieldUtil.setShiftPlanQty(result, shift.getShiftIndex(), 16,
                    shift.getShiftStartDateTime(), shift.getShiftEndDateTime());
        }
        ShiftFieldUtil.syncDailyPlanQty(result);
        result.setSpecEndTime(shifts.get(shifts.size() - 1).getShiftEndDateTime());
        result.setTdaySpecEndTime(result.getSpecEndTime());
        return result;
    }

    private LhScheduleResult buildSingleShiftContinuationResult(String materialCode,
                                                                String machineCode,
                                                                LhShiftConfigVO shift,
                                                                int qty) {
        return buildSingleShiftContinuationResult(materialCode, machineCode, shift, qty, true);
    }

    private LhScheduleResult buildSingleShiftContinuationResult(String materialCode,
                                                                String machineCode,
                                                                LhShiftConfigVO shift,
                                                                int qty,
                                                                boolean ending) {
        LhScheduleResult result = baseContinuationResult(materialCode, machineCode, ending);
        ShiftFieldUtil.setShiftPlanQty(result, shift.getShiftIndex(), qty,
                shift.getShiftStartDateTime(), shift.getShiftEndDateTime());
        ShiftFieldUtil.syncDailyPlanQty(result);
        result.setSpecEndTime(shift.getShiftEndDateTime());
        result.setTdaySpecEndTime(result.getSpecEndTime());
        return result;
    }

    private LhScheduleResult baseContinuationResult(String materialCode, String machineCode, boolean ending) {
        LhScheduleResult result = new LhScheduleResult();
        result.setScheduleType("01");
        result.setIsTypeBlock("0");
        result.setMaterialCode(materialCode);
        result.setMaterialDesc(materialCode);
        result.setLhMachineCode(machineCode);
        result.setLhMachineName(machineCode);
        result.setEmbryoCode("EMB-" + materialCode);
        result.setMouldSurplusQty(ending ? 16 : 800);
        result.setEmbryoStock(0);
        result.setLhTime(3600);
        result.setMouldQty(1);
        result.setSingleMouldShiftQty(16);
        result.setIsEnd(ending ? "1" : "0");
        return result;
    }

    private LhShiftConfigVO findShiftByType(List<LhShiftConfigVO> shifts, String shiftType) {
        for (LhShiftConfigVO shift : shifts) {
            if (StringUtils.equals(shiftType, shift.getShiftType())) {
                return shift;
            }
        }
        throw new IllegalStateException("测试夹具未找到指定班次: " + shiftType);
    }

    private int sumScheduledQty(LhScheduleContext context) {
        int total = 0;
        for (LhScheduleResult result : context.getScheduleResultList()) {
            total += ShiftFieldUtil.resolveScheduledQty(result);
        }
        return total;
    }

    private int countPositiveResults(LhScheduleContext context) {
        int total = 0;
        for (LhScheduleResult result : context.getScheduleResultList()) {
            if (ShiftFieldUtil.resolveScheduledQty(result) > 0) {
                total++;
            }
        }
        return total;
    }

    private LhScheduleResult findResultByMachineCode(LhScheduleContext context, String machineCode) {
        for (LhScheduleResult result : context.getScheduleResultList()) {
            if (StringUtils.equals(machineCode, result.getLhMachineCode())) {
                return result;
            }
        }
        throw new IllegalStateException("测试夹具未找到机台结果: " + machineCode);
    }

    private LhScheduleContext buildSameMaterialDifferentQuotaGroupContext() {
        LhScheduleContext context = new LhScheduleContext();
        context.setFactoryCode("116");
        context.setBatchNo("LHPC-TEST-GROUP-ENDING");
        context.setScheduleDate(toDate(2026, 5, 1, 0, 0, 0));
        context.setScheduleTargetDate(toDate(2026, 5, 3, 0, 0, 0));
        context.setScheduleWindowShifts(LhScheduleTimeUtil.buildDefaultScheduleShifts(context, context.getScheduleDate()));

        List<LhShiftConfigVO> shifts = context.getScheduleWindowShifts();
        LhShiftConfigVO firstShift = shifts.get(0);

        Map<LocalDate, SkuDailyPlanQuotaDTO> firstQuotaMap = new LinkedHashMap<LocalDate, SkuDailyPlanQuotaDTO>(4);
        firstQuotaMap.put(toLocalDate(firstShift), quota("MAT-SAME", toLocalDate(firstShift), 16));
        SkuScheduleDTO firstSku = buildContinuationSku(
                "MAT-SAME", ConstructionStageEnum.FORMAL.getCode(), true, 16, firstQuotaMap);
        firstSku.setContinuousMachineCode("K1101");
        firstSku.setSurplusQty(16);
        firstSku.setEmbryoStock(0);

        Map<LocalDate, SkuDailyPlanQuotaDTO> secondQuotaMap = new LinkedHashMap<LocalDate, SkuDailyPlanQuotaDTO>(4);
        secondQuotaMap.put(toLocalDate(firstShift), quota("MAT-SAME", toLocalDate(firstShift), 40));
        SkuScheduleDTO secondSku = buildContinuationSku(
                "MAT-SAME", ConstructionStageEnum.FORMAL.getCode(), true, 40, secondQuotaMap);
        secondSku.setContinuousMachineCode("K1102");
        secondSku.setSurplusQty(40);
        secondSku.setEmbryoStock(0);

        context.setContinuousSkuList(Arrays.asList(firstSku, secondSku));

        LhScheduleResult firstResult = buildSingleShiftContinuationResult("MAT-SAME", "K1101", firstShift, 16);
        LhScheduleResult secondResult = buildSingleShiftContinuationResult("MAT-SAME", "K1102", firstShift, 8);
        firstResult.setIsEnd("0");
        secondResult.setIsEnd("0");
        context.getScheduleResultList().add(firstResult);
        context.getScheduleResultList().add(secondResult);
        context.getScheduleResultSourceSkuMap().put(firstResult, firstSku);
        context.getScheduleResultSourceSkuMap().put(secondResult, secondSku);
        return context;
    }

    /**
     * 构建滚动排程上下文。
     *
     * @return 排程上下文
     */
    private LhScheduleContext buildRollingContext() {
        LhScheduleContext context = new LhScheduleContext();
        context.setFactoryCode("116");
        context.setBatchNo("LHPC20260427001");
        context.setScheduleDate(toDate(2026, 4, 25, 0, 0, 0));
        context.setScheduleTargetDate(toDate(2026, 4, 27, 0, 0, 0));
        context.setRollingScheduleHandoff(true);

        LhShiftConfigVO[] shifts = LhScheduleTimeUtil.buildDefaultScheduleShifts(
                context, context.getScheduleDate()).toArray(new LhShiftConfigVO[0]);
        context.setScheduleWindowShifts(Arrays.asList(shifts));
        LhScheduleTimeUtil.initShiftRuntimeStateMap(context, context.getScheduleWindowShifts());

        MachineScheduleDTO machine = new MachineScheduleDTO();
        machine.setMachineCode("K1111");
        machine.setMachineName("益神");
        machine.setMaxMoldNum(1);
        machine.setCurrentMaterialCode("3302001313");
        machine.setCurrentMaterialDesc("385/65R22.5 164K 24PR JY598 BL4EJY");
        machine.setEstimatedEndTime(toDate(2026, 4, 26, 21, 28, 0));
        Map<String, MachineScheduleDTO> machineMap = new LinkedHashMap<>();
        machineMap.put(machine.getMachineCode(), machine);
        context.setMachineScheduleMap(machineMap);
        context.getInitialMachineScheduleMap().put(machine.getMachineCode(), machine);

        SkuScheduleDTO sku = new SkuScheduleDTO();
        sku.setMaterialCode("3302001313");
        sku.setMaterialDesc("385/65R22.5 164K 24PR JY598 BL4EJY");
        sku.setStructureName("385/65R22.5-JY598四层");
        sku.setSpecCode("385/65R22.5-JY598四层");
        sku.setEmbryoCode("330201268");
        sku.setShiftCapacity(16);
        sku.setLhTimeSeconds(3600);
        sku.setMonthPlanQty(9999);
        sku.setSurplusQty(9999);
        sku.setPendingQty(48);
        sku.setTargetScheduleQty(48);
        sku.setContinuousMachineCode("K1111");
        context.setContinuousSkuList(Collections.singletonList(sku));
        context.getStructureSkuMap().put(sku.getStructureName(), Collections.singletonList(sku));
        return context;
    }

    private LhScheduleResult buildContinuousShiftResult(String materialCode,
                                                        String embryoCode,
                                                        int embryoStock,
                                                        String isEnd,
                                                        int class1PlanQty,
                                                        int class2PlanQty,
                                                        int class3PlanQty,
                                                        int class4PlanQty,
                                                        int class5PlanQty,
                                                        int class6PlanQty,
                                                        int class7PlanQty,
                                                        int class8PlanQty) {
        LhScheduleResult result = new LhScheduleResult();
        result.setScheduleType("01");
        result.setIsTypeBlock("0");
        result.setMaterialCode(materialCode);
        result.setEmbryoCode(embryoCode);
        result.setEmbryoStock(embryoStock);
        result.setIsEnd(isEnd);
        ShiftFieldUtil.setShiftPlanQty(result, 1, class1PlanQty, null, null);
        ShiftFieldUtil.setShiftPlanQty(result, 2, class2PlanQty, null, null);
        ShiftFieldUtil.setShiftPlanQty(result, 3, class3PlanQty, null, null);
        ShiftFieldUtil.setShiftPlanQty(result, 4, class4PlanQty, null, null);
        ShiftFieldUtil.setShiftPlanQty(result, 5, class5PlanQty, null, null);
        ShiftFieldUtil.setShiftPlanQty(result, 6, class6PlanQty, null, null);
        ShiftFieldUtil.setShiftPlanQty(result, 7, class7PlanQty, null, null);
        ShiftFieldUtil.setShiftPlanQty(result, 8, class8PlanQty, null, null);
        ShiftFieldUtil.syncDailyPlanQty(result);
        return result;
    }

    /**
     * 构建已继承的滚动排程结果。
     *
     * @param context 排程上下文
     * @return 继承结果
     */
    private LhScheduleResult buildInheritedResult(LhScheduleContext context) {
        LhScheduleResult result = new LhScheduleResult();
        result.setFactoryCode(context.getFactoryCode());
        result.setBatchNo(context.getBatchNo());
        result.setScheduleDate(context.getScheduleTargetDate());
        result.setRealScheduleDate(context.getScheduleDate());
        result.setLhMachineCode("K1111");
        result.setLhMachineName("益神");
        result.setMaterialCode("3302001313");
        result.setMaterialDesc("385/65R22.5 164K 24PR JY598 BL4EJY");
        result.setSpecCode("385/65R22.5-JY598四层");
        result.setEmbryoCode("330201268");
        result.setScheduleType("01");
        result.setIsTypeBlock("0");
        result.setIsEnd("0");
        result.setLhTime(3600);
        result.setMouldQty(1);
        result.setSingleMouldShiftQty(16);
        result.setRollingInherited(true);

        for (int shiftIndex = 1; shiftIndex <= 5; shiftIndex++) {
            LhShiftConfigVO shift = context.getScheduleWindowShifts().get(shiftIndex - 1);
            ShiftFieldUtil.setShiftPlanQty(result, shiftIndex, 16,
                    shift.getShiftStartDateTime(), shift.getShiftEndDateTime());
        }
        ShiftFieldUtil.syncDailyPlanQty(result);
        result.setSpecEndTime(toDate(2026, 4, 26, 21, 28, 0));
        result.setTdaySpecEndTime(result.getSpecEndTime());
        return result;
    }

    /**
     * 反射注入私有依赖。
     *
     * @param target 目标对象
     * @param fieldName 字段名
     * @param value 注入值
     * @throws Exception 反射异常
     */
    private void injectField(Object target, String fieldName, Object value) throws Exception {
        Field field = ContinuousProductionStrategy.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    /**
     * 构建仅含物料编码的SKU。
     *
     * @param materialCode 物料编码
     * @return SKU
     */
    private SkuScheduleDTO sku(String materialCode) {
        SkuScheduleDTO sku = new SkuScheduleDTO();
        sku.setMaterialCode(materialCode);
        return sku;
    }

    /**
     * 构建带分摊库存的SKU。
     *
     * @param materialCode 物料编码
     * @param embryoCode 胎胚编码
     * @param embryoStock 分摊胎胚库存
     * @return SKU
     */
    private SkuScheduleDTO buildSku(String materialCode, String embryoCode, int embryoStock) {
        SkuScheduleDTO sku = sku(materialCode);
        sku.setEmbryoCode(embryoCode);
        sku.setEmbryoStock(embryoStock);
        sku.setTrial(true);
        sku.setStrictTargetQty(true);
        sku.setConstructionStage(ConstructionStageEnum.TRIAL.getCode());
        return sku;
    }

    private LhShiftConfigVO resolveNextWorkDateShift(List<LhShiftConfigVO> shifts, LhShiftConfigVO firstShift) {
        for (LhShiftConfigVO shift : shifts) {
            if (shift.getWorkDate() != null
                    && firstShift.getWorkDate() != null
                    && shift.getWorkDate().after(firstShift.getWorkDate())) {
                return shift;
            }
        }
        throw new IllegalStateException("测试夹具未找到跨天班次");
    }

    private LinkedHashMap<LocalDate, SkuDailyPlanQuotaDTO> buildQuotaMap(LhShiftConfigVO firstShift,
                                                                         LhShiftConfigVO nextDayShift,
                                                                         int firstDayQty,
                                                                         int nextDayQty) {
        return buildQuotaMap(firstShift, nextDayShift, null, firstDayQty, nextDayQty, 0);
    }

    private LinkedHashMap<LocalDate, SkuDailyPlanQuotaDTO> buildQuotaMap(LhShiftConfigVO firstShift,
                                                                         LhShiftConfigVO nextDayShift,
                                                                         LhShiftConfigVO thirdDayShift,
                                                                         int firstDayQty,
                                                                         int nextDayQty,
                                                                         int thirdDayQty) {
        LinkedHashMap<LocalDate, SkuDailyPlanQuotaDTO> quotaMap = new LinkedHashMap<LocalDate, SkuDailyPlanQuotaDTO>(4);
        quotaMap.put(toLocalDate(firstShift), quota("MAT-QUOTA", toLocalDate(firstShift), firstDayQty));
        quotaMap.put(toLocalDate(nextDayShift), quota("MAT-QUOTA", toLocalDate(nextDayShift), nextDayQty));
        if (thirdDayShift != null) {
            quotaMap.put(toLocalDate(thirdDayShift), quota("MAT-QUOTA", toLocalDate(thirdDayShift), thirdDayQty));
        }
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

    private void fillShiftDateTime(LhScheduleResult result, List<LhShiftConfigVO> shifts, int shiftCount) {
        for (int index = 0; index < shiftCount; index++) {
            LhShiftConfigVO shift = shifts.get(index);
            Integer planQty = ShiftFieldUtil.getShiftPlanQty(result, shift.getShiftIndex());
            if (planQty == null || planQty <= 0) {
                continue;
            }
            ShiftFieldUtil.setShiftPlanQty(result, shift.getShiftIndex(), planQty,
                    shift.getShiftStartDateTime(), shift.getShiftEndDateTime());
        }
        ShiftFieldUtil.syncDailyPlanQty(result);
    }

    private LocalDate toLocalDate(LhShiftConfigVO shift) {
        return shift.getWorkDate().toInstant().atZone(ZONE_ID).toLocalDate();
    }

    /**
     * 构建续作排程结果。
     *
     * @param materialCode 物料编码
     * @param embryoCode 胎胚编码
     * @param planQty 计划量
     * @return 续作排程结果
     */
    private LhScheduleResult buildContinuousResult(String materialCode, String embryoCode, int planQty) {
        LhScheduleResult result = new LhScheduleResult();
        result.setScheduleType("01");
        result.setMaterialCode(materialCode);
        result.setEmbryoCode(embryoCode);
        result.setMouldSurplusQty(0);
        result.setEmbryoStock(planQty);
        result.setLhTime(3600);
        result.setMouldQty(1);
        result.setSingleMouldShiftQty(100);
        result.setIsEnd("0");
        ShiftFieldUtil.setShiftPlanQty(result, 1, planQty, null, null);
        ShiftFieldUtil.syncDailyPlanQty(result);
        return result;
    }

    /**
     * 生成指定时刻的 Date。
     *
     * @param year 年
     * @param month 月
     * @param day 日
     * @param hour 时
     * @param minute 分
     * @param second 秒
     * @return Date 实例
     */
    private Date toDate(int year, int month, int day, int hour, int minute, int second) {
        return Date.from(LocalDateTime.of(year, month, day, hour, minute, second)
                .atZone(ZONE_ID)
                .toInstant());
    }

    /**
     * 固定返回“非收尾”的收尾判定桩实现。
     */
    private static class StubEndingJudgmentStrategy implements IEndingJudgmentStrategy {

        @Override
        public boolean isEnding(LhScheduleContext context, SkuScheduleDTO sku) {
            return false;
        }

        @Override
        public int calculateEndingShifts(LhScheduleContext context, SkuScheduleDTO sku) {
            return 0;
        }

        @Override
        public int calculateEndingDays(LhScheduleContext context, SkuScheduleDTO sku) {
            return 0;
        }
    }

    /**
     * 校验续作主循环处理首条SKU前，后续首日无计划机台已完成释放预登记。
     */
    private static class AssertReleasedMachineRegisteredBeforeFirstSkuStrategy extends StubEndingJudgmentStrategy {

        @Override
        public boolean isEnding(LhScheduleContext context, SkuScheduleDTO sku) {
            if (sku != null && StringUtils.equals("MAT-FIRST", sku.getMaterialCode())) {
                assertTrue(context.getReleasedContinuousMachineCodeSet().contains("K1105"),
                        "处理其他续作SKU前，应先登记K1105为首日无计划释放机台");
            }
            return false;
        }
    }
}
