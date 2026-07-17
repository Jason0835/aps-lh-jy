package com.zlt.aps.lh.component;

import com.zlt.aps.lh.api.constant.LhScheduleParamConstant;
import com.zlt.aps.lh.api.domain.entity.LhRepairCapsule;
import com.zlt.aps.lh.api.domain.entity.LhScheduleResult;
import com.zlt.aps.lh.api.domain.vo.LhShiftConfigVO;
import com.zlt.aps.lh.api.enums.SingleControlMachineModeEnum;
import com.zlt.aps.lh.context.LhScheduleConfig;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.util.LhScheduleTimeUtil;
import com.zlt.aps.lh.util.ShiftFieldUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 胶囊使用次数与换胶囊班次扣减规则测试。
 *
 * @author APS
 */
public class CapsuleReplacementRuleServiceTest {

    private CapsuleReplacementRuleService service;
    private LhScheduleContext context;
    private List<LhShiftConfigVO> shifts;

    @BeforeEach
    public void setUp() {
        service = new CapsuleReplacementRuleService();
        context = new LhScheduleContext();
        context.setBatchNo("CAPSULE-TEST");
        context.setFactoryCode("116");
        context.setScheduleDate(java.util.Date.from(
                LocalDate.of(2026, 7, 17).atStartOfDay(ZoneId.systemDefault()).toInstant()));
        context.setScheduleConfig(new LhScheduleConfig(new HashMap<String, String>(0)));
        shifts = LhScheduleTimeUtil.buildDefaultScheduleShifts(context, context.getScheduleDate());
        context.setScheduleWindowShifts(shifts);
    }

    /** 未达到上限时不扣减，并按实际16条累计。 */
    @Test
    public void shouldAccumulateActualQtyWithoutReplacementBelowLimit() {
        registerCapsule("K1101", 430, 0);
        LhScheduleResult result = result("K1101", 1);

        int actualQty = service.resolveActualPlanQty(context, result, shifts.get(0), 16, 1, "测试");

        Assertions.assertEquals(16, actualQty);
        Assertions.assertEquals(446, service.getRuntimeUsage(context, "K1101", 1));
        Assertions.assertNull(ShiftFieldUtil.getShiftAnalysis(result, 1));
    }

    /** 生产后刚好达到450次时不换胶囊。 */
    @Test
    public void shouldNotReplaceWhenUsageExactlyReachesLimit() {
        registerCapsule("K1101", 434, 0);
        LhScheduleResult result = result("K1101", 1);

        int actualQty = service.resolveActualPlanQty(context, result, shifts.get(0), 16, 1, "测试");

        Assertions.assertEquals(16, actualQty);
        Assertions.assertEquals(450, service.getRuntimeUsage(context, "K1101", 1));
        Assertions.assertNull(ShiftFieldUtil.getShiftAnalysis(result, 1));
    }

    /** 440+16严格超过450时扣2条并追加换胶囊备注。 */
    @Test
    public void shouldDeductTwoAndAppendAnalysisWhenCrossingLimit() {
        registerCapsule("K1101", 440, 0);
        LhScheduleResult result = result("K1101", 1);
        ShiftFieldUtil.setShiftAnalysis(result, 1, "干冰清洗");

        int actualQty = service.resolveActualPlanQty(context, result, shifts.get(0), 16, 1, "测试");

        Assertions.assertEquals(14, actualQty);
        Assertions.assertEquals(4, service.getRuntimeUsage(context, "K1101", 1));
        Assertions.assertEquals("干冰清洗,换胶囊", ShiftFieldUtil.getShiftAnalysis(result, 1));
    }

    /** 候选量小于扣减值时归零，换胶囊完成后下一班仍可继续排剩余量。 */
    @Test
    public void shouldKeepSmallRemainderForNextShiftAfterZeroPlanReplacement() {
        registerCapsule("K1101", 450, 0);
        LhScheduleResult result = result("K1101", 1);

        int firstShiftQty = service.resolveActualPlanQty(context, result, shifts.get(0), 1, 1, "测试");
        int nextShiftQty = service.resolveActualPlanQty(context, result, shifts.get(1), 1, 1, "测试");

        Assertions.assertEquals(0, firstShiftQty);
        Assertions.assertEquals(1, nextShiftQty);
        Assertions.assertEquals(1, service.getRuntimeUsage(context, "K1101", 1));
        Assertions.assertEquals("换胶囊", ShiftFieldUtil.getShiftAnalysis(result, 1));
    }

    /** 换胶囊后的新胶囊次数应继续按下一班实际产量累计。 */
    @Test
    public void shouldAccumulateNewCapsuleUsageAcrossFollowingShift() {
        registerCapsule("K1101", 440, 0);
        LhScheduleResult result = result("K1101", 1);

        int firstShiftQty = service.resolveActualPlanQty(context, result, shifts.get(0), 16, 1, "测试");
        ShiftFieldUtil.setShiftPlanQty(result, 1, firstShiftQty,
                shifts.get(0).getShiftStartDateTime(), shifts.get(0).getShiftEndDateTime());
        int nextShiftQty = service.resolveActualPlanQty(context, result, shifts.get(1), 2, 1, "测试");

        Assertions.assertEquals(14, firstShiftQty);
        Assertions.assertEquals(2, nextShiftQty);
        Assertions.assertEquals(6, service.getRuntimeUsage(context, "K1101", 1));
    }

    /** 同一结果班次重复调用不得再次扣减，也不得把首次扣除的2条补回。 */
    @Test
    public void shouldDeductOnlyOnceForSamePhysicalMachineAndShift() {
        registerCapsule("K1101", 440, 0);
        LhScheduleResult result = result("K1101", 1);

        int firstQty = service.resolveActualPlanQty(context, result, shifts.get(0), 16, 1, "测试");
        ShiftFieldUtil.setShiftPlanQty(result, 1, firstQty,
                shifts.get(0).getShiftStartDateTime(), shifts.get(0).getShiftEndDateTime());
        int appendedQty = service.resolveActualPlanQty(context, result, shifts.get(0), 2, 1, "测试补量");

        Assertions.assertEquals(14, firstQty);
        Assertions.assertEquals(0, appendedQty);
        Assertions.assertEquals(4, service.getRuntimeUsage(context, "K1101", 1));
        Assertions.assertEquals("换胶囊", ShiftFieldUtil.getShiftAnalysis(result, 1));
        Assertions.assertEquals(1, context.getCapsuleReplacementShiftKeySet().size());
    }

    /** 余量只有10条且累计刚好450时，不得因理论班产16而误触发。 */
    @Test
    public void shouldNotReplaceWhenRemainderDoesNotActuallyCrossLimit() {
        registerCapsule("K1101", 440, 0);
        LhScheduleResult result = result("K1101", 1);

        int actualQty = service.resolveActualPlanQty(context, result, shifts.get(0), 10, 1, "测试");

        Assertions.assertEquals(10, actualQty);
        Assertions.assertEquals(450, service.getRuntimeUsage(context, "K1101", 1));
        Assertions.assertNull(ShiftFieldUtil.getShiftAnalysis(result, 1));
    }

    /** 同班多结果应先累计前一结果，后一个结果跨限时只扣一次。 */
    @Test
    public void shouldAccumulateMultipleResultsBeforeCheckingLaterResultInSameShift() {
        registerCapsule("K1101", 440, 0);
        LhScheduleResult firstResult = result("K1101", 1);
        int firstQty = service.resolveActualPlanQty(context, firstResult, shifts.get(0), 10, 1, "测试前段");
        ShiftFieldUtil.setShiftPlanQty(firstResult, 1, firstQty,
                shifts.get(0).getShiftStartDateTime(), shifts.get(0).getShiftEndDateTime());
        context.getScheduleResultList().add(firstResult);
        LhScheduleResult secondResult = result("K1101", 1);

        int secondQty = service.resolveActualPlanQty(context, secondResult, shifts.get(0), 6, 1, "测试后段");

        Assertions.assertEquals(10, firstQty);
        Assertions.assertEquals(4, secondQty);
        Assertions.assertEquals(4, service.getRuntimeUsage(context, "K1101", 1));
        Assertions.assertEquals("换胶囊", ShiftFieldUtil.getShiftAnalysis(secondResult, 1));
    }

    /** 普通双模总量16按两侧各8次判断，触发后实际14按两侧各7次累计。 */
    @Test
    public void shouldSplitDoubleMouldUsageByCapsulePosition() {
        registerCapsule("K1701", 445, 445);
        LhScheduleResult result = result("K1701", 2);

        int actualQty = service.resolveActualPlanQty(context, result, shifts.get(0), 16, 2, "测试双模");

        Assertions.assertEquals(14, actualQty);
        Assertions.assertEquals(2, service.getRuntimeUsage(context, "K1701", 1));
        Assertions.assertEquals(2, service.getRuntimeUsage(context, "K1701", 2));
    }

    /** L/R整机候选为单侧8条时，物理整机固定扣2条应折算为单侧扣1条。 */
    @Test
    public void shouldDeductWholeSingleControlPairOnlyOnce() {
        registerCapsule("K1501", 445, 445);
        context.getSingleControlModeSnapshotMap().put(
                MonthPlanDateResolver.buildMaterialStatusKey("3302000001", "S"),
                SingleControlMachineModeEnum.WHOLE_PAIR);
        LhScheduleResult result = result("K1501L", 1);

        int sideActualQty = service.resolveActualPlanQty(context, result, shifts.get(0), 8, 1, "测试单控整机");

        Assertions.assertEquals(7, sideActualQty);
        Assertions.assertEquals(2, service.getRuntimeUsage(context, "K1501", 1));
        Assertions.assertEquals(2, service.getRuntimeUsage(context, "K1501", 2));
    }

    /** L侧先扣产能、R侧随后跨限时，最终重建仍应分别得到两侧新胶囊次数且物理班次只扣一次。 */
    @Test
    public void shouldRebuildBothSingleControlSidesAfterOnePhysicalShiftDeduction() {
        registerCapsule("K1501", 445, 445);
        LhScheduleResult leftResult = result("K1501L", 1);
        int leftQty = service.resolveActualPlanQty(
                context, leftResult, shifts.get(0), 8, 1, "测试单控L侧");
        ShiftFieldUtil.setShiftPlanQty(leftResult, 1, leftQty,
                shifts.get(0).getShiftStartDateTime(), shifts.get(0).getShiftEndDateTime());
        context.getScheduleResultList().add(leftResult);

        LhScheduleResult rightResult = result("K1501R", 1);
        int rightQty = service.resolveActualPlanQty(
                context, rightResult, shifts.get(0), 8, 1, "测试单控R侧");
        ShiftFieldUtil.setShiftPlanQty(rightResult, 1, rightQty,
                shifts.get(0).getShiftStartDateTime(), shifts.get(0).getShiftEndDateTime());
        context.getScheduleResultList().add(rightResult);

        service.verifyFinalState(context);

        Assertions.assertEquals(6, leftQty);
        Assertions.assertEquals(8, rightQty);
        Assertions.assertEquals(1, service.getRuntimeUsage(context, "K1501", 1));
        Assertions.assertEquals(3, service.getRuntimeUsage(context, "K1501", 2));
        Assertions.assertEquals(1, context.getCapsuleReplacementShiftKeySet().size());
        Assertions.assertEquals("换胶囊", ShiftFieldUtil.getShiftAnalysis(leftResult, 1));
        Assertions.assertNull(ShiftFieldUtil.getShiftAnalysis(rightResult, 1));
    }

    /** 后置复制导致同一物理机台同班出现重复备注时，最终核对只能保留一条且不得改动计划量。 */
    @Test
    public void shouldRemoveDuplicateAnalysisWithoutChangingPlanQty() {
        registerCapsule("K1501", 440, 440);
        LhScheduleResult leftResult = result("K1501L", 1);
        LhScheduleResult rightResult = result("K1501R", 1);
        ShiftFieldUtil.setShiftPlanQty(leftResult, 1, 6,
                shifts.get(0).getShiftStartDateTime(), shifts.get(0).getShiftEndDateTime());
        ShiftFieldUtil.setShiftPlanQty(rightResult, 1, 8,
                shifts.get(0).getShiftStartDateTime(), shifts.get(0).getShiftEndDateTime());
        ShiftFieldUtil.setShiftAnalysis(leftResult, 1, "首检,换胶囊");
        ShiftFieldUtil.setShiftAnalysis(rightResult, 1, "清洗,换胶囊");
        context.getScheduleResultList().add(leftResult);
        context.getScheduleResultList().add(rightResult);

        service.verifyFinalState(context);

        Assertions.assertEquals(6, ShiftFieldUtil.getShiftPlanQty(leftResult, 1));
        Assertions.assertEquals(8, ShiftFieldUtil.getShiftPlanQty(rightResult, 1));
        Assertions.assertEquals("首检,换胶囊", ShiftFieldUtil.getShiftAnalysis(leftResult, 1));
        Assertions.assertEquals("清洗", ShiftFieldUtil.getShiftAnalysis(rightResult, 1));
    }

    /** 自定义参数必须直接参与严格超限判断和班次扣减。 */
    @Test
    public void shouldUseConfiguredUpperLimitAndLossQty() {
        Map<String, String> paramMap = new HashMap<String, String>(2);
        paramMap.put(LhScheduleParamConstant.CAPSULE_FORCE_DOWN_COUNT, "10");
        paramMap.put(LhScheduleParamConstant.CAPSULE_CHANGE_LOSS_QTY, "3");
        context.setScheduleConfig(new LhScheduleConfig(paramMap));
        registerCapsule("K1101", 8, 0);
        LhScheduleResult result = result("K1101", 1);

        int actualQty = service.resolveActualPlanQty(context, result, shifts.get(0), 4, 1, "测试参数");

        Assertions.assertEquals(1, actualQty);
        Assertions.assertEquals(0, service.getRuntimeUsage(context, "K1101", 1));
    }

    /** 已触发换胶囊的普通机台班次，后置重算产能仍必须保留固定2条损失。 */
    @Test
    public void shouldKeepReplacementLossWhenPostProcessRecalculatesCapacity() {
        registerCapsule("K1101", 440, 0);
        LhScheduleResult result = result("K1101", 1);
        int actualQty = service.resolveActualPlanQty(
                context, result, shifts.get(0), 16, 1, "测试正式落班");
        ShiftFieldUtil.setShiftPlanQty(result, 1, actualQty,
                shifts.get(0).getShiftStartDateTime(), shifts.get(0).getShiftEndDateTime());

        int postProcessCapacity = service.resolveReplacementShiftCapacityUpperLimit(
                context, result, shifts.get(0), 16);
        int repeatedPostProcessCapacity = service.resolveReplacementShiftCapacityUpperLimit(
                context, result, shifts.get(0), 14);

        Assertions.assertEquals(14, postProcessCapacity);
        Assertions.assertEquals(14, repeatedPostProcessCapacity);
        Assertions.assertEquals("换胶囊", ShiftFieldUtil.getShiftAnalysis(result, 1));
        Assertions.assertEquals(4, service.getRuntimeUsage(context, "K1101", 1));
    }

    /** 最终收敛只能撤销补回量，传入已低于记录上限的量时不得再次扣减。 */
    @Test
    public void shouldLimitFinalQtyByRecordedCapacityWithoutSecondDeduction() {
        registerCapsule("K1101", 440, 0);
        LhScheduleResult result = result("K1101", 1);
        int actualQty = service.resolveActualPlanQty(
                context, result, shifts.get(0), 16, 1, "测试正式落班");
        ShiftFieldUtil.setShiftPlanQty(result, 1, actualQty,
                shifts.get(0).getShiftStartDateTime(), shifts.get(0).getShiftEndDateTime());

        int restoredQty = service.limitByRecordedReplacementCapacity(
                context, result, shifts.get(0), 16);
        int alreadyReducedQty = service.limitByRecordedReplacementCapacity(
                context, result, shifts.get(0), 12);

        Assertions.assertEquals(14, restoredQty);
        Assertions.assertEquals(12, alreadyReducedQty);
        Assertions.assertEquals(4, service.getRuntimeUsage(context, "K1101", 1));
    }

    /** 未触发换胶囊的班次，后置重算产能不得被无条件扣减。 */
    @Test
    public void shouldKeepOriginalCapacityForShiftWithoutReplacement() {
        registerCapsule("K1101", 430, 0);
        LhScheduleResult result = result("K1101", 1);

        int postProcessCapacity = service.resolveReplacementShiftCapacityUpperLimit(
                context, result, shifts.get(0), 16);

        Assertions.assertEquals(16, postProcessCapacity);
        Assertions.assertNull(ShiftFieldUtil.getShiftAnalysis(result, 1));
    }

    /** L/R整机结果按单侧保存，后置产能上限应与正式落班一致按单侧扣1条。 */
    @Test
    public void shouldKeepWholePairSideLossWhenPostProcessRecalculatesCapacity() {
        registerCapsule("K1501", 445, 445);
        context.getSingleControlModeSnapshotMap().put(
                MonthPlanDateResolver.buildMaterialStatusKey("3302000001", "S"),
                SingleControlMachineModeEnum.WHOLE_PAIR);
        LhScheduleResult result = result("K1501L", 1);
        int actualQty = service.resolveActualPlanQty(
                context, result, shifts.get(0), 8, 1, "测试单控整机正式落班");
        ShiftFieldUtil.setShiftPlanQty(result, 1, actualQty,
                shifts.get(0).getShiftStartDateTime(), shifts.get(0).getShiftEndDateTime());

        int postProcessCapacity = service.resolveReplacementShiftCapacityUpperLimit(
                context, result, shifts.get(0), 8);

        Assertions.assertEquals(7, postProcessCapacity);
        Assertions.assertEquals(2, service.getRuntimeUsage(context, "K1501", 1));
        Assertions.assertEquals(2, service.getRuntimeUsage(context, "K1501", 2));
    }

    private LhScheduleResult result(String machineCode, int mouldQty) {
        LhScheduleResult result = new LhScheduleResult();
        result.setBatchNo(context.getBatchNo());
        result.setFactoryCode(context.getFactoryCode());
        result.setMaterialCode("3302000001");
        result.setProductStatus("S");
        result.setLhMachineCode(machineCode);
        result.setMouldQty(mouldQty);
        return result;
    }

    private void registerCapsule(String machineCode, int firstUsage, int secondUsage) {
        LhRepairCapsule capsule = new LhRepairCapsule();
        capsule.setLhCode(machineCode);
        capsule.setReplaceCapsuleCount(firstUsage);
        capsule.setReplaceCapsuleCount2(secondUsage);
        context.getCapsuleUsageMap().put(machineCode, capsule);
    }
}
