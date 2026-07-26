package com.zlt.aps.lh.engine.strategy.support;

import com.zlt.aps.lh.api.domain.dto.SkuScheduleDTO;
import com.zlt.aps.lh.api.domain.entity.LhScheduleResult;

import java.util.Date;
import java.util.Objects;

/**
 * 新增 SKU 跨业务日在机绑定。
 *
 * <p>绑定只缓存“哪个 SKU 已经在何机台形成有效新增结果”。机台当前物料、预计结束时间和
 * 结果真实性仍以排程上下文中的机台运行态和结果列表为准，日终会进行一致性校验。</p>
 *
 * @author APS
 */
public class ActiveMachineBinding {

    /** 物料和产品状态业务键 */
    private final String skuKey;
    /** 现有 SKU 运行态对象 */
    private final SkuScheduleDTO sku;
    /** 主机台编码；普通机台或单控单边场景只有该机台 */
    private final String machineCode;
    /** 单控整机场景的配对侧机台编码 */
    private final String pairMachineCode;
    /** 首次上机时生成、后续按天追加班次量的主结果 */
    private final LhScheduleResult scheduleResult;
    /** 单控整机配对侧结果 */
    private final LhScheduleResult pairScheduleResult;
    /**
     * 是否按收尾目标上机。
     *
     * <p>该字段在首次上机时固化业务判定，跨日续排不得再读取结果行 {@code isEnd}
     * 作为过程控制状态，避免窗口级最终收尾标记与当前日增量收口语义互相污染。</p>
     */
    private final boolean endingTarget;
    /** 当前日收口后机台预计结束时间 */
    private Date estimatedEndTime;

    /**
     * 创建跨日在机绑定。
     *
     * @param skuKey SKU业务键
     * @param sku SKU运行态对象
     * @param machineCode 主机台编码
     * @param pairMachineCode 配对侧机台编码
     * @param scheduleResult 主结果
     * @param pairScheduleResult 配对侧结果
     * @param endingTarget 是否按收尾目标上机
     */
    public ActiveMachineBinding(String skuKey,
                                SkuScheduleDTO sku,
                                String machineCode,
                                String pairMachineCode,
                                LhScheduleResult scheduleResult,
                                LhScheduleResult pairScheduleResult,
                                boolean endingTarget) {
        this.skuKey = skuKey;
        this.sku = Objects.requireNonNull(sku, "在机绑定SKU不能为空");
        this.machineCode = machineCode;
        this.pairMachineCode = pairMachineCode;
        this.scheduleResult = Objects.requireNonNull(scheduleResult, "在机绑定结果不能为空");
        this.pairScheduleResult = pairScheduleResult;
        this.endingTarget = endingTarget;
        this.estimatedEndTime = scheduleResult.getSpecEndTime();
    }

    /**
     * 判断当前绑定是否为单控整机双侧结果。
     *
     * @return true-存在配对侧；false-普通机台或单控单边
     */
    public boolean hasPairMachine() {
        return Objects.nonNull(pairScheduleResult);
    }

    public String getSkuKey() {
        return skuKey;
    }

    public SkuScheduleDTO getSku() {
        return sku;
    }

    public String getMachineCode() {
        return machineCode;
    }

    public String getPairMachineCode() {
        return pairMachineCode;
    }

    public LhScheduleResult getScheduleResult() {
        return scheduleResult;
    }

    public LhScheduleResult getPairScheduleResult() {
        return pairScheduleResult;
    }

    public boolean isEndingTarget() {
        return endingTarget;
    }

    public Date getEstimatedEndTime() {
        return estimatedEndTime;
    }

    public void setEstimatedEndTime(Date estimatedEndTime) {
        this.estimatedEndTime = estimatedEndTime;
    }
}
