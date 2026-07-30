package com.zlt.aps.lh.service.impl;

import lombok.Getter;

import java.time.LocalDate;
import java.util.Date;

/**
 * 共用模具置换中 A 的计划来源日与窗口内实际接管日期解析结果。
 *
 * @author APS
 */
@Getter
final class SharedMouldPlanDateResolution {

    /** A 在窗口及提前生产范围内最早出现正日计划量的来源日期。 */
    private final LocalDate firstPositivePlanDate;
    /** A 实际允许在 T～T+2 窗口内接管的业务日期。 */
    private final LocalDate takeoverDate;
    /** 接管业务日最早班次开始时间。 */
    private final Date takeoverTargetTime;

    /**
     * 构造日期解析结果。
     *
     * @param firstPositivePlanDate 正计划来源日期
     * @param takeoverDate 实际接管业务日期
     * @param takeoverTargetTime 实际接管目标时间
     */
    SharedMouldPlanDateResolution(
            LocalDate firstPositivePlanDate,
            LocalDate takeoverDate,
            Date takeoverTargetTime) {
        this.firstPositivePlanDate = firstPositivePlanDate;
        this.takeoverDate = takeoverDate;
        this.takeoverTargetTime = takeoverTargetTime;
    }
}
