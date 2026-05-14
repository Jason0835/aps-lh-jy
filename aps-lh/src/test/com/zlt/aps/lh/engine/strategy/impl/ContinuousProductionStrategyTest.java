package com.zlt.aps.lh.engine.strategy.impl;

import com.zlt.aps.lh.api.domain.dto.MachineScheduleDTO;
import com.zlt.aps.lh.api.domain.dto.SkuDailyPlanQuotaDTO;
import com.zlt.aps.lh.api.domain.dto.SkuScheduleDTO;
import com.zlt.aps.lh.api.domain.entity.LhScheduleResult;
import com.zlt.aps.lh.api.domain.vo.LhShiftConfigVO;
import com.zlt.aps.lh.component.OrderNoGenerator;
import com.zlt.aps.lh.component.TargetScheduleQtyResolver;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.engine.strategy.IEndingJudgmentStrategy;
import com.zlt.aps.lh.util.LhScheduleTimeUtil;
import com.zlt.aps.lh.util.ShiftFieldUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    public void selectPreferredSkuFromCandidates_shouldPickFirstForPriorityOneCandidates() {
        ContinuousProductionStrategy strategy = new ContinuousProductionStrategy();
        IEndingJudgmentStrategy endingJudgmentStrategy = mock(IEndingJudgmentStrategy.class);
        ReflectionTestUtils.setField(strategy, "endingJudgmentStrategy", endingJudgmentStrategy);

        SkuScheduleDTO sku1585 = sku("3302001585");
        SkuScheduleDTO sku2022 = sku("3302002022");
        List<SkuScheduleDTO> priorityOneCandidates = Arrays.asList(sku1585, sku2022);
        when(endingJudgmentStrategy.isEnding(any(LhScheduleContext.class), same(sku2022))).thenReturn(true);

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
        when(endingJudgmentStrategy.isEnding(any(LhScheduleContext.class), same(sku2022))).thenReturn(true);

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

        LhScheduleResult result = new LhScheduleResult();
        result.setScheduleType("01");
        result.setMaterialCode("MAT-CHECK");
        result.setEmbryoCode("EMB-1");
        result.setDailyPlanQty(88);
        result.setMouldSurplusQty(80);
        result.setEmbryoStock(140);
        result.setIsEnd("1");
        context.getScheduleResultList().add(result);

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
        context.setContinuousSkuList(Arrays.asList(
                buildSku("MAT-SHARE-A", "EMB-SAME", 50),
                buildSku("MAT-SHARE-B", "EMB-SAME", 100)));
        context.getScheduleResultList().add(buildContinuousResult("MAT-SHARE-A", "EMB-SAME", 80));
        context.getScheduleResultList().add(buildContinuousResult("MAT-SHARE-B", "EMB-SAME", 80));

        strategy.adjustEmbryoStock(context);

        assertEquals(50, context.getScheduleResultList().get(0).getDailyPlanQty().intValue(),
                "同胎胚不同SKU应按各自分摊库存裁剪");
        assertEquals(80, context.getScheduleResultList().get(1).getDailyPlanQty().intValue(),
                "同胎胚不同SKU不应被前一个SKU按胎胚编号扣减库存");
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
    public void adjustEmbryoStock_shouldStillTrimEndingContinuousResultByEmbryoStock() {
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

        assertEquals(15, context.getScheduleResultList().get(0).getDailyPlanQty().intValue(),
                "收尾续作仍应保留胎胚库存裁减约束");
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
        Map<LocalDate, SkuDailyPlanQuotaDTO> firstQuotaMap = new LinkedHashMap<LocalDate, SkuDailyPlanQuotaDTO>(4);
        firstQuotaMap.put(toLocalDate(firstShift), quota("MAT-DUP", toLocalDate(firstShift), 6));
        firstQuotaMap.put(toLocalDate(nextDayShift), quota("MAT-DUP", toLocalDate(nextDayShift), 0));
        firstSku.setDailyPlanQuotaMap(firstQuotaMap);

        SkuScheduleDTO secondSku = new SkuScheduleDTO();
        secondSku.setMaterialCode("MAT-DUP");
        Map<LocalDate, SkuDailyPlanQuotaDTO> secondQuotaMap = new LinkedHashMap<LocalDate, SkuDailyPlanQuotaDTO>(4);
        secondQuotaMap.put(toLocalDate(firstShift), quota("MAT-DUP", toLocalDate(firstShift), 0));
        secondQuotaMap.put(toLocalDate(nextDayShift), quota("MAT-DUP", toLocalDate(nextDayShift), 4));
        secondSku.setDailyPlanQuotaMap(secondQuotaMap);
        context.setContinuousSkuList(Arrays.asList(firstSku, secondSku));

        LhScheduleResult firstResult = new LhScheduleResult();
        firstResult.setScheduleType("01");
        firstResult.setIsTypeBlock("0");
        firstResult.setMaterialCode("MAT-DUP");
        ShiftFieldUtil.setShiftPlanQty(firstResult, firstShift.getShiftIndex(), 8,
                firstShift.getShiftStartDateTime(), firstShift.getShiftEndDateTime());
        ShiftFieldUtil.syncDailyPlanQty(firstResult);

        LhScheduleResult secondResult = new LhScheduleResult();
        secondResult.setScheduleType("01");
        secondResult.setIsTypeBlock("0");
        secondResult.setMaterialCode("MAT-DUP");
        ShiftFieldUtil.setShiftPlanQty(secondResult, nextDayShift.getShiftIndex(), 8,
                nextDayShift.getShiftStartDateTime(), nextDayShift.getShiftEndDateTime());
        ShiftFieldUtil.syncDailyPlanQty(secondResult);

        context.getScheduleResultList().add(firstResult);
        context.getScheduleResultList().add(secondResult);
        context.getScheduleResultSourceSkuMap().put(firstResult, firstSku);
        context.getScheduleResultSourceSkuMap().put(secondResult, secondSku);

        ReflectionTestUtils.invokeMethod(strategy, "syncContinuousDailyPlanQuota", context, shifts);

        assertEquals(6, firstResult.getDailyPlanQty().intValue(), "首条重复物料结果应只消费自己的首日额度");
        assertEquals(4, secondResult.getDailyPlanQty().intValue(), "第二条重复物料结果应消费自己的次日额度");
        assertEquals(2, firstSku.getShiftFillOverQty(), "首条结果的超排量应记录在首个来源SKU账本");
        assertEquals(4, secondSku.getShiftFillOverQty(), "第二条结果的超排量应记录在第二个来源SKU账本");
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
        machine1.setCapsuleUsageCount(1);
        context.getMachineScheduleMap().put("M1", machine1);
        MachineScheduleDTO machine2 = new MachineScheduleDTO();
        machine2.setMachineCode("M2");
        machine2.setCapsuleUsageCount(2);
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
        LinkedHashMap<LocalDate, SkuDailyPlanQuotaDTO> quotaMap = new LinkedHashMap<LocalDate, SkuDailyPlanQuotaDTO>(4);
        quotaMap.put(toLocalDate(firstShift), quota("MAT-QUOTA", toLocalDate(firstShift), firstDayQty));
        quotaMap.put(toLocalDate(nextDayShift), quota("MAT-QUOTA", toLocalDate(nextDayShift), nextDayQty));
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
}
