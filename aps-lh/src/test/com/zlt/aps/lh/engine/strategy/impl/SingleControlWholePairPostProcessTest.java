package com.zlt.aps.lh.engine.strategy.impl;

import com.zlt.aps.lh.api.domain.dto.SkuScheduleDTO;
import com.zlt.aps.lh.api.domain.entity.LhScheduleResult;
import com.zlt.aps.lh.api.domain.vo.LhShiftConfigVO;
import com.zlt.aps.lh.api.enums.SingleControlMachineModeEnum;
import com.zlt.aps.lh.api.enums.ShiftEnum;
import com.zlt.aps.lh.api.enums.TrialStatusEnum;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.util.LhSingleControlMachineUtil;
import com.zlt.aps.lh.util.ShiftFieldUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;

/**
 * 单控双模组后处理回归测试。
 */
class SingleControlWholePairPostProcessTest {

    /**
     * 双模 L/R 各5条进入收尾归集后必须保持各5条，不能归集成10+0。
     */
    @Test
    void concentrateEndingTailOnShift_shouldKeepWholePairSidesEqual() {
        LhScheduleContext context = new LhScheduleContext();
        SkuScheduleDTO sku = sku("WHOLE-PAIR", 10);
        freezeMode(context, sku, SingleControlMachineModeEnum.WHOLE_PAIR);
        context.getNewSpecSkuList().add(sku);
        LhScheduleResult leftResult = result("K1501L", sku.getMaterialCode(), 5);
        LhScheduleResult rightResult = result("K1501R", sku.getMaterialCode(), 5);
        LhShiftConfigVO shift = shift(2);

        Boolean changed = ReflectionTestUtils.invokeMethod(new NewSpecProductionStrategy(),
                "concentrateEndingTailOnShift", context, sku, Collections.singletonList(shift), 2,
                Arrays.asList(leftResult, rightResult));

        Assertions.assertFalse(Boolean.TRUE.equals(changed));
        Assertions.assertEquals(Integer.valueOf(5), ShiftFieldUtil.getShiftPlanQty(leftResult, 2));
        Assertions.assertEquals(Integer.valueOf(5), ShiftFieldUtil.getShiftPlanQty(rightResult, 2));
    }

    /**
     * 单模两侧仍是独立排产单元，原有同班次尾量归集可以集中到其中一侧。
     */
    @Test
    void concentrateEndingTailOnShift_shouldKeepIndependentBehaviorForSingleSideMode() {
        LhScheduleContext context = new LhScheduleContext();
        SkuScheduleDTO sku = sku("SINGLE-SIDE", 10);
        freezeMode(context, sku, SingleControlMachineModeEnum.SINGLE_SIDE);
        context.getNewSpecSkuList().add(sku);
        LhScheduleResult leftResult = result("K1501L", sku.getMaterialCode(), 5);
        LhScheduleResult rightResult = result("K1501R", sku.getMaterialCode(), 5);
        LhShiftConfigVO shift = shift(2);

        Boolean changed = ReflectionTestUtils.invokeMethod(new NewSpecProductionStrategy(),
                "concentrateEndingTailOnShift", context, sku, Collections.singletonList(shift), 2,
                Arrays.asList(leftResult, rightResult));

        Assertions.assertTrue(Boolean.TRUE.equals(changed));
        List<Integer> actualQtyList = Arrays.asList(
                ShiftFieldUtil.getShiftPlanQty(leftResult, 2), ShiftFieldUtil.getShiftPlanQty(rightResult, 2));
        Assertions.assertTrue(actualQtyList.contains(10));
        Assertions.assertTrue(actualQtyList.contains(0));
    }

    /**
     * 构建SKU。
     *
     * @param materialCode 物料编码
     * @param targetQty 本轮目标量
     * @return SKU
     */
    private SkuScheduleDTO sku(String materialCode, int targetQty) {
        SkuScheduleDTO sku = new SkuScheduleDTO();
        sku.setMaterialCode(materialCode);
        sku.setProductStatus(TrialStatusEnum.FORMAL.getCode());
        sku.setTargetScheduleQty(targetQty);
        sku.setPendingQty(targetQty);
        sku.setSurplusQty(targetQty);
        sku.setEmbryoStock(0);
        return sku;
    }

    /**
     * 写入测试模式快照。
     *
     * @param context 排程上下文
     * @param sku SKU
     * @param mode 冻结模式
     */
    private void freezeMode(LhScheduleContext context,
                            SkuScheduleDTO sku,
                            SingleControlMachineModeEnum mode) {
        context.setScheduleDate(new Date(0L));
        context.setScheduleTargetDate(new Date(0L));
        context.getSingleControlModeSnapshotMap().put(
                LhSingleControlMachineUtil.buildSkuModeKey(sku), mode);
        context.setSingleControlModeSnapshotInitialized(true);
    }

    /**
     * 构建单侧结果。
     *
     * @param machineCode 机台编码
     * @param materialCode 物料编码
     * @param shiftQty 班次量
     * @return 排程结果
     */
    private LhScheduleResult result(String machineCode, String materialCode, int shiftQty) {
        LhScheduleResult result = new LhScheduleResult();
        result.setLhMachineCode(machineCode);
        result.setMaterialCode(materialCode);
        result.setScheduleType("02");
        result.setMouldQty(1);
        result.setSingleMouldShiftQty(10);
        result.setLhTime(3600);
        ShiftFieldUtil.setShiftPlanQty(result, 2, shiftQty, new Date(1000L), new Date(2000L));
        ShiftFieldUtil.syncDailyPlanQty(result);
        return result;
    }

    /**
     * 构建班次。
     *
     * @param shiftIndex 班次序号
     * @return 班次
     */
    private LhShiftConfigVO shift(int shiftIndex) {
        LhShiftConfigVO shift = new LhShiftConfigVO();
        shift.setShiftIndex(shiftIndex);
        shift.setScheduleBaseDate(new Date(0L));
        shift.setDateOffset(0);
        shift.setShiftType(ShiftEnum.AFTERNOON_SHIFT.getCode());
        shift.setStartTime("14:00");
        shift.setEndTime("22:00");
        return shift;
    }
}
