package com.zlt.aps.lh.engine.strategy.support;

import com.zlt.aps.lh.api.domain.dto.SkuScheduleDTO;

import java.time.LocalDate;
import java.util.EnumSet;
import java.util.Objects;

/**
 * 新增排产单个业务日的 SKU 候选。
 *
 * <p>该对象只承载日编排元数据，不复制 SKU 的模具、胎胚、目标量、日计划账本等运行态数据。
 * 所有资源计算仍直接读取 {@link SkuScheduleDTO} 和排程上下文，保证全窗口只有一份权威状态。</p>
 *
 * @author APS
 */
public class DailyNewSpecCandidate {

    /** 物料编码和产品状态组成的业务键，仅用于日志和候选去重诊断 */
    private final String skuKey;
    /** 现有新增排产 SKU 对象，保留 S4.5 排序和全部运行态账本 */
    private final SkuScheduleDTO sku;
    /** 当前业务日命中的全部候选来源 */
    private final EnumSet<DailyCandidateReason> reasons =
            EnumSet.noneOf(DailyCandidateReason.class);
    /** 本次准入实际消费或提前拉取的计划日期 */
    private LocalDate targetPlanDate;
    /** 构建候选时读取的当前日实时日计划余额 */
    private int realtimeDayPlanRemainingQty;
    /** 当前 SKU 是否已经存在跨日在机绑定 */
    private boolean boundOnMachine;

    /**
     * 创建单个业务日候选。
     *
     * @param skuKey 物料和产品状态业务键
     * @param sku 现有新增排产 SKU
     */
    public DailyNewSpecCandidate(String skuKey, SkuScheduleDTO sku) {
        this.skuKey = skuKey;
        this.sku = Objects.requireNonNull(sku, "新增排产SKU不能为空");
    }

    /**
     * 增加候选来源。
     *
     * @param reason 候选来源
     */
    public void addReason(DailyCandidateReason reason) {
        if (Objects.nonNull(reason)) {
            reasons.add(reason);
        }
    }

    /**
     * 判断是否命中指定候选来源。
     *
     * @param reason 候选来源
     * @return true-已命中；false-未命中
     */
    public boolean hasReason(DailyCandidateReason reason) {
        return Objects.nonNull(reason) && reasons.contains(reason);
    }

    public String getSkuKey() {
        return skuKey;
    }

    public SkuScheduleDTO getSku() {
        return sku;
    }

    public EnumSet<DailyCandidateReason> getReasons() {
        return EnumSet.copyOf(reasons);
    }

    public LocalDate getTargetPlanDate() {
        return targetPlanDate;
    }

    public void setTargetPlanDate(LocalDate targetPlanDate) {
        this.targetPlanDate = targetPlanDate;
    }

    public int getRealtimeDayPlanRemainingQty() {
        return realtimeDayPlanRemainingQty;
    }

    public void setRealtimeDayPlanRemainingQty(int realtimeDayPlanRemainingQty) {
        this.realtimeDayPlanRemainingQty = Math.max(0, realtimeDayPlanRemainingQty);
    }

    public boolean isBoundOnMachine() {
        return boundOnMachine;
    }

    public void setBoundOnMachine(boolean boundOnMachine) {
        this.boundOnMachine = boundOnMachine;
    }
}
