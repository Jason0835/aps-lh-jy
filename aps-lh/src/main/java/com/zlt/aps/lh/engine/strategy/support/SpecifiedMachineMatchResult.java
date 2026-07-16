package com.zlt.aps.lh.engine.strategy.support;

import com.zlt.aps.lh.api.domain.dto.MachineScheduleDTO;
import lombok.Getter;

import java.util.Objects;

/**
 * 指定机台硬约束匹配结果。
 *
 * <p>该对象用于“机台反选SKU”场景返回机台和明确失败原因。普通候选机台匹配仍返回有序列表，
 * 两种入口共享硬过滤，但指定机台入口不参与最早收尾窗口和候选优先级排序。</p>
 */
@Getter
public class SpecifiedMachineMatchResult {

    /** 通过全部机台硬约束后的实际排产机台 */
    private final MachineScheduleDTO machine;

    /** 未通过指定机台匹配时的明确业务原因 */
    private final String failureReason;

    /**
     * 构造指定机台匹配结果。
     *
     * @param machine 实际排产机台
     * @param failureReason 失败原因
     */
    private SpecifiedMachineMatchResult(MachineScheduleDTO machine, String failureReason) {
        this.machine = machine;
        this.failureReason = failureReason;
    }

    /**
     * 构建成功结果。
     *
     * @param machine 通过硬约束的机台
     * @return 成功匹配结果
     */
    public static SpecifiedMachineMatchResult success(MachineScheduleDTO machine) {
        return new SpecifiedMachineMatchResult(machine, null);
    }

    /**
     * 构建失败结果。
     *
     * @param failureReason 明确失败原因
     * @return 失败匹配结果
     */
    public static SpecifiedMachineMatchResult failed(String failureReason) {
        return new SpecifiedMachineMatchResult(null, failureReason);
    }

    /**
     * 判断是否匹配成功。
     *
     * @return true-指定机台通过硬约束；false-匹配失败
     */
    public boolean isSuccess() {
        return Objects.nonNull(machine);
    }
}
