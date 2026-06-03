package com.zlt.aps.lh.engine.strategy.support;

import lombok.Data;

import java.io.Serializable;

/**
 * 日计划欠产增机台准备结果。
 *
 * @author APS
 */
@Data
public class DailyMachineShortageQuotaPlan implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 是否当前窗口无日计划 */
    private boolean noWindowPlan;

    /** 是否窗口和月底均无计划，需要按收尾处理 */
    private boolean forceEndingByNoFuturePlan;

    /** 窗口日计划量 */
    private int windowDayPlanQty;

    /** 本月前日累计欠产量 */
    private int historyShortageQty;

    /** 欠产增机台阈值 */
    private int shortageAddMachineThreshold;

    /** 本次补充到首日账本的欠产差额 */
    private int additionalShortageQty;

    /** 日计划账本剩余额度 */
    private int quotaRemainingQty;

    /** T+3 到月底后续日计划量 */
    private int futureMonthPlanQtyAfterWindow;

    /** 严格目标量 */
    private int strictTargetQty;
}
