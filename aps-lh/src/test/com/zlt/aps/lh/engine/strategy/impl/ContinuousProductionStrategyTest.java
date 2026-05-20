package com.zlt.aps.lh.engine.strategy.impl;

import com.zlt.aps.lh.api.domain.dto.MachineScheduleDTO;
import com.zlt.aps.lh.api.domain.dto.SkuDailyPlanQuotaDTO;
import com.zlt.aps.lh.api.domain.dto.SkuScheduleDTO;
import com.zlt.aps.lh.api.domain.entity.LhScheduleResult;
import com.zlt.aps.lh.api.domain.vo.LhShiftConfigVO;
import com.zlt.aps.lh.api.enums.ConstructionStageEnum;
import com.zlt.aps.lh.api.enums.ShiftEnum;
import com.zlt.aps.lh.component.OrderNoGenerator;
import com.zlt.aps.lh.component.TargetScheduleQtyResolver;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.engine.strategy.IEndingJudgmentStrategy;
import com.zlt.aps.lh.util.LhScheduleTimeUtil;
import com.zlt.aps.lh.util.ShiftFieldUtil;
import com.zlt.aps.lh.util.SkuDailyPlanQuotaUtil;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
        Map<LocalDate, SkuDailyPlanQuotaDTO> firstQuotaMap = new LinkedHashMap<LocalDate, SkuDailyPlanQuotaDTO>(4);
        firstQuotaMap.put(toLocalDate(firstShift), quota("MAT-DUP", toLocalDate(firstShift), 6));
        firstQuotaMap.put(toLocalDate(nextDayShift), quota("MAT-DUP", toLocalDate(nextDayShift), 0));
        firstSku.setDailyPlanQuotaMap(firstQuotaMap);

        SkuScheduleDTO secondSku = new SkuScheduleDTO();
        secondSku.setMaterialCode("MAT-DUP");
        secondSku.setTrial(true);
        secondSku.setStrictTargetQty(true);
        secondSku.setConstructionStage(ConstructionStageEnum.TRIAL.getCode());
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
    public void scheduleReduceMould_shouldReduceMachineFromSecondDayWhenLaterDayPlanDrops() {
        ContinuousProductionStrategy strategy = new ContinuousProductionStrategy();
        LhScheduleContext context = buildMultiDayContinuationContext(
                ConstructionStageEnum.FORMAL.getCode(), 112, 64, 48, 10, 5, "K1405", "K1702");

        strategy.scheduleReduceMould(context);

        assertEquals(2, context.getScheduleResultList().size(), "第一天仍有排产量的下机机台结果不应被整条移除");
        LhScheduleResult retainedResult = findResultByMachineCode(context, "K1405");
        LhScheduleResult removedFromSecondDayResult = findResultByMachineCode(context, "K1702");
        assertEquals(80, retainedResult.getDailyPlanQty().intValue(),
                "K1405 应保留 day1 的 2 个班次和 day2 的 3 个班次");
        assertEquals(32, removedFromSecondDayResult.getDailyPlanQty().intValue(),
                "K1702 只应保留 day1 的 2 个班次，day2 起下机");
        assertEquals(Integer.valueOf(16), ShiftFieldUtil.getShiftPlanQty(removedFromSecondDayResult, 1));
        assertEquals(Integer.valueOf(16), ShiftFieldUtil.getShiftPlanQty(removedFromSecondDayResult, 2));
        assertEquals(Integer.valueOf(0), ShiftFieldUtil.getShiftPlanQty(removedFromSecondDayResult, 3));
        assertEquals(Integer.valueOf(0), ShiftFieldUtil.getShiftPlanQty(removedFromSecondDayResult, 4));
        assertEquals(Integer.valueOf(0), ShiftFieldUtil.getShiftPlanQty(removedFromSecondDayResult, 5));
        assertEquals(Integer.valueOf(0), ShiftFieldUtil.getShiftPlanQty(removedFromSecondDayResult, 6));
        assertEquals(112, sumScheduledQty(context),
                "按 2/3/3 班窗口降模后，总量应收口为 day1 64 + day2 48");
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
        assertEquals(32, removedFromSecondDayResult.getDailyPlanQty().intValue(),
                "K1702 仍只保留 day1 的 2 个班次，day2 起下机");
        assertEquals(Integer.valueOf(16), ShiftFieldUtil.getShiftPlanQty(retainedResult, 6));
        assertEquals(Integer.valueOf(16), ShiftFieldUtil.getShiftPlanQty(retainedResult, 7));
        assertEquals(Integer.valueOf(16), ShiftFieldUtil.getShiftPlanQty(retainedResult, 8));
        assertEquals(160, sumScheduledQty(context),
                "day1=64、day2=48、day3=48 的文档案例下，总量应为 160");
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
    public void scheduleReduceMould_shouldNotForceStaggerWhenContinuousEndingAtNightShift() {
        ContinuousProductionStrategy strategy = new ContinuousProductionStrategy();
        LhScheduleContext context = buildMultiMachineEndingStaggerContext(ShiftEnum.NIGHT_SHIFT.getCode());

        strategy.scheduleReduceMould(context);

        assertEquals(2, context.getScheduleResultList().size(), "晚班收尾不强制错开");
        for (LhScheduleResult result : context.getScheduleResultList()) {
            assertEquals(8, result.getDailyPlanQty().intValue());
        }
        assertEquals(16, sumScheduledQty(context));
    }

    @Test
    public void scheduleReduceMould_shouldStaggerContinuousEndingAtMorningOrAfternoonShift() {
        ContinuousProductionStrategy strategy = new ContinuousProductionStrategy();
        LhScheduleContext context = buildMultiMachineEndingStaggerContext(ShiftEnum.MORNING_SHIFT.getCode());

        strategy.scheduleReduceMould(context);

        assertEquals(1, context.getScheduleResultList().size(), "早中班同SKU多机台收尾应尝试错开并释放一台机台");
        LhScheduleResult retainedResult = context.getScheduleResultList().get(0);
        LhShiftConfigVO morningShift = findShiftByType(context.getScheduleWindowShifts(), ShiftEnum.MORNING_SHIFT.getCode());
        assertEquals(16, retainedResult.getDailyPlanQty().intValue());
        assertEquals(Integer.valueOf(8), ShiftFieldUtil.getShiftPlanQty(retainedResult, morningShift.getShiftIndex()));
        assertEquals(Integer.valueOf(8), ShiftFieldUtil.getShiftPlanQty(retainedResult, morningShift.getShiftIndex() + 1));
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
    public void scheduleReduceMould_shouldKeepEmbryoStockSumConsistentForSharedQuotaMultiMachineGroup() {
        ContinuousProductionStrategy strategy = new ContinuousProductionStrategy();
        LhScheduleContext context = buildMultiMachineContinuationContext(
                ConstructionStageEnum.FORMAL.getCode(), false, 96, 96, 10, 5, "K1101", "K1102");
        for (SkuScheduleDTO sku : context.getContinuousSkuList()) {
            sku.setEmbryoStock(120);
        }

        strategy.scheduleReduceMould(context);

        int totalEmbryoStock = context.getScheduleResultList().stream()
                .map(LhScheduleResult::getEmbryoStock)
                .filter(Objects::nonNull)
                .mapToInt(Integer::intValue)
                .sum();
        assertEquals(120, totalEmbryoStock,
                "共享账本多机台组保留两台时，最终结果上的胎胚库存总和应与来源SKU一致");
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
     * 构建续作同SKU多机台收尾错峰测试上下文。
     *
     * @param endingShiftType 收尾班次类型
     * @return 排程上下文
     */
    private LhScheduleContext buildMultiMachineEndingStaggerContext(String endingShiftType) {
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
                "MAT-END-STAGGER", ConstructionStageEnum.FORMAL.getCode(), true, 16, quotaMap);
        firstSku.setContinuousMachineCode("K1101");
        SkuScheduleDTO secondSku = buildContinuationSku(
                "MAT-END-STAGGER", ConstructionStageEnum.FORMAL.getCode(), true, 16, quotaMap);
        secondSku.setContinuousMachineCode("K1102");
        context.setContinuousSkuList(Arrays.asList(firstSku, secondSku));

        LhScheduleResult firstResult = buildSingleShiftContinuationResult(
                "MAT-END-STAGGER", "K1101", endingShift, 8);
        LhScheduleResult secondResult = buildSingleShiftContinuationResult(
                "MAT-END-STAGGER", "K1102", endingShift, 8);
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

    private LhScheduleResult buildSingleShiftContinuationResult(String materialCode,
                                                                String machineCode,
                                                                LhShiftConfigVO shift,
                                                                int qty) {
        LhScheduleResult result = baseContinuationResult(materialCode, machineCode, true);
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
}
