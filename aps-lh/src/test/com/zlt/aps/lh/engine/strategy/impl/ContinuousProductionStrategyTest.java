package com.zlt.aps.lh.engine.strategy.impl;

import com.zlt.aps.lh.api.domain.dto.MachineScheduleDTO;
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
