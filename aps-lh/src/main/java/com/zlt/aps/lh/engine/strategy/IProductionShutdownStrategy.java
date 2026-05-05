package com.zlt.aps.lh.engine.strategy;

import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.api.domain.dto.ShiftProductionControlDTO;
import com.zlt.aps.lh.api.domain.dto.SkuScheduleDTO;
import com.zlt.aps.lh.api.domain.vo.LhShiftConfigVO;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 开停产处理策略
 * <p>处理停产递减、开产首日产能调整</p>
 */
public interface IProductionShutdownStrategy {
    /**
     * 准备开停产上下文。
     *
     * @param context 排程上下文
     * @return void
     */
    void prepareOpenStopContext(LhScheduleContext context);

    /**
     * 推算开产班次。
     *
     * @param context 排程上下文
     * @param curingOpenMoldTime 硫化开模时间
     * @param processCode 工序编码
     * @return 开产班次管控信息
     */
    ShiftProductionControlDTO resolveOpenProductionShift(LhScheduleContext context, Date curingOpenMoldTime, String processCode);

    /**
     * 推算停产班次。
     *
     * @param context 排程上下文
     * @param curingStopPotTime 硫化停锅时间
     * @param processCode 工序编码
     * @return 停产班次管控信息
     */
    ShiftProductionControlDTO resolveStopProductionShift(LhScheduleContext context, Date curingStopPotTime, String processCode);

    /**
     * 按开停产和工作日历解析班次有效可排窗口。
     *
     * @param context 排程上下文
     * @param shift 班次
     * @param requestedStartTime 请求开产时间
     * @return 班次排产管控信息
     */
    ShiftProductionControlDTO resolveEffectiveShiftControl(LhScheduleContext context, LhShiftConfigVO shift, Date requestedStartTime);

    /**
     * 按班次管控比例扣减产能。
     *
     * @param control 班次排产管控信息
     * @param originalCapacity 原始产能
     * @param mouldQty 模台数
     * @return 扣减后的产能
     */
    int deductCapacityByOpenStopRule(ShiftProductionControlDTO control, int originalCapacity, int mouldQty);

    BigDecimal calculateShutdownRate(LhScheduleContext context, String machineCode, Date targetDate);

    boolean isShutdownDay(LhScheduleContext context, String machineCode, Date targetDate);

    boolean isStartupDay(LhScheduleContext context, String machineCode, Date targetDate);

    int adjustCapacityForShutdown(LhScheduleContext context, SkuScheduleDTO skuDto, int originalCapacity);
}
