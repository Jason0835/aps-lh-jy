package com.zlt.aps.lh.engine.strategy.support;

import com.zlt.aps.lh.api.domain.entity.LhScheduleResult;
import lombok.Getter;

/**
 * 指定机台排产执行结果。
 *
 * <p>用于换活字块、新增换模两条成熟主链向反选策略返回统一状态。反选策略只负责编排，
 * 不直接修改资源账本；成功结果中的机台、SKU、时间和资源状态均由被委托的现有排产主链生成。</p>
 */
@Getter
public class SpecifiedMachineScheduleResult {

    /** 当前机台与SKU是否适用于调用的实际交替类型 */
    private final boolean applicable;

    /** 指定机台是否排产成功 */
    private final boolean success;

    /** 成功生成或已满足的排程结果 */
    private final LhScheduleResult scheduleResult;

    /** 实际交替类型编码，01-换模，02-换活字块 */
    private final String actualChangeType;

    /** 失败原因或成功说明 */
    private final String reason;

    /**
     * 构造指定机台排产结果。
     *
     * @param applicable 是否适用当前交替类型
     * @param success 是否成功
     * @param scheduleResult 排程结果
     * @param actualChangeType 实际交替类型
     * @param reason 结果说明
     */
    private SpecifiedMachineScheduleResult(boolean applicable,
                                           boolean success,
                                           LhScheduleResult scheduleResult,
                                           String actualChangeType,
                                           String reason) {
        this.applicable = applicable;
        this.success = success;
        this.scheduleResult = scheduleResult;
        this.actualChangeType = actualChangeType;
        this.reason = reason;
    }

    /**
     * 构建“不适用当前交替类型”结果。
     *
     * @param reason 不适用原因
     * @return 不适用结果
     */
    public static SpecifiedMachineScheduleResult notApplicable(String reason) {
        return new SpecifiedMachineScheduleResult(false, false, null, null, reason);
    }

    /**
     * 构建指定机台排产成功结果。
     *
     * @param scheduleResult 本次生成的排程结果
     * @param actualChangeType 实际交替类型
     * @return 成功结果
     */
    public static SpecifiedMachineScheduleResult success(LhScheduleResult scheduleResult,
                                                         String actualChangeType) {
        return new SpecifiedMachineScheduleResult(
                true, true, scheduleResult, actualChangeType, "指定机台排产成功");
    }

    /**
     * 构建指定机台排产失败结果。
     *
     * @param actualChangeType 实际交替类型
     * @param reason 明确失败原因
     * @return 失败结果
     */
    public static SpecifiedMachineScheduleResult failed(String actualChangeType, String reason) {
        return new SpecifiedMachineScheduleResult(
                true, false, null, actualChangeType, reason);
    }
}
