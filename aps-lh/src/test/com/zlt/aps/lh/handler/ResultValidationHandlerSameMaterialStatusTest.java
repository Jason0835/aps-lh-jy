/**
 * Copyright (c) 2008, 智立通（厦门）科技有限公司 All rights reserved。
 */
package com.zlt.aps.lh.handler;

import com.zlt.aps.lh.api.constant.LhScheduleConstant;
import com.zlt.aps.lh.api.domain.entity.LhScheduleResult;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.util.ShiftFieldUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 同物料多状态续作结果保存前校验聚焦测试。
 */
public class ResultValidationHandlerSameMaterialStatusTest {

    private static final String BATCH_NO = "LHPC20260716002";
    private static final String MATERIAL_CODE = "330200TEST";
    private static final String MACHINE_CODE = "LH01";

    private ResultValidationHandler handler;
    private LhScheduleContext context;

    /**
     * 初始化后置校验器和排程上下文。
     */
    @BeforeEach
    public void setUp() {
        this.handler = new ResultValidationHandler();
        this.context = new LhScheduleContext();
        this.context.setFactoryCode("116");
        this.context.setBatchNo(BATCH_NO);
    }

    /**
     * 验证专用状态链尾量不会被S4.6按模台数向上改大。
     */
    @Test
    public void shouldKeepExactTailForSameMaterialStatusContinuation() {
        LhScheduleResult result = this.buildResult(3);
        ShiftFieldUtil.setShiftAnalysis(
                result, 2, LhScheduleConstant.SAME_MATERIAL_STATUS_CONTINUATION_ANALYSIS);

        ReflectionTestUtils.invokeMethod(
                this.handler, "normalizeMouldMultiplePlanQty", this.context, result);

        assertEquals(3, ShiftFieldUtil.getShiftPlanQty(result, 2));
        assertEquals(3, ShiftFieldUtil.resolveScheduledQty(result));
    }

    /**
     * 验证普通双模结果仍保持原有的模台数向上收敛逻辑。
     */
    @Test
    public void shouldKeepExistingMouldMultipleNormalizationForOrdinaryResult() {
        LhScheduleResult result = this.buildResult(3);

        ReflectionTestUtils.invokeMethod(
                this.handler, "normalizeMouldMultiplePlanQty", this.context, result);

        assertEquals(4, ShiftFieldUtil.getShiftPlanQty(result, 2));
        assertEquals(4, ShiftFieldUtil.resolveScheduledQty(result));
    }

    /**
     * 构造双模班次结果。
     *
     * @param planQty 中班计划量
     * @return 排程结果
     */
    private LhScheduleResult buildResult(int planQty) {
        LhScheduleResult result = new LhScheduleResult();
        result.setBatchNo(BATCH_NO);
        result.setFactoryCode("116");
        result.setMaterialCode(MATERIAL_CODE);
        result.setProductStatus("T");
        result.setLhMachineCode(MACHINE_CODE);
        result.setMouldQty(2);
        ShiftFieldUtil.setShiftPlanQty(result, 2, planQty, null, null);
        ShiftFieldUtil.syncDailyPlanQty(result);
        return result;
    }
}
