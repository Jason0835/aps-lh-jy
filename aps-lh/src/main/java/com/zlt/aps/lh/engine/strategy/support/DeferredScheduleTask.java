package com.zlt.aps.lh.engine.strategy.support;

import com.zlt.aps.lh.api.domain.dto.SkuScheduleDTO;

import java.time.LocalDate;
import java.util.Objects;

/**
 * 新增排产跨业务日延期任务。
 *
 * <p>延期任务只表示当前日暂时不能完成，不等同于最终未排。只有窗口最后一日仍失败，
 * 或现有业务规则已经确认硬性不可排时，才允许转为最终未排结果。</p>
 *
 * @author APS
 */
public class DeferredScheduleTask {

    /** 延期的现有 SKU 运行态对象 */
    private final SkuScheduleDTO sku;
    /** 发生延期的业务日期 */
    private final LocalDate deferredFromDate;
    /** 下一次允许重新尝试的业务日期 */
    private final LocalDate nextAttemptDate;
    /** 延期前所在日内阶段 */
    private final DailySchedulePhase sourcePhase;
    /** 当前日延期原因，仅用于日志和最终原因诊断 */
    private final String reason;

    /**
     * 创建延期任务。
     *
     * @param sku 延期 SKU
     * @param deferredFromDate 延期业务日期
     * @param nextAttemptDate 下一尝试日期
     * @param sourcePhase 延期来源阶段
     * @param reason 延期原因
     */
    public DeferredScheduleTask(SkuScheduleDTO sku,
                                LocalDate deferredFromDate,
                                LocalDate nextAttemptDate,
                                DailySchedulePhase sourcePhase,
                                String reason) {
        this.sku = Objects.requireNonNull(sku, "延期SKU不能为空");
        this.deferredFromDate = deferredFromDate;
        this.nextAttemptDate = nextAttemptDate;
        this.sourcePhase = sourcePhase;
        this.reason = reason;
    }

    public SkuScheduleDTO getSku() {
        return sku;
    }

    public LocalDate getDeferredFromDate() {
        return deferredFromDate;
    }

    public LocalDate getNextAttemptDate() {
        return nextAttemptDate;
    }

    public DailySchedulePhase getSourcePhase() {
        return sourcePhase;
    }

    public String getReason() {
        return reason;
    }
}
