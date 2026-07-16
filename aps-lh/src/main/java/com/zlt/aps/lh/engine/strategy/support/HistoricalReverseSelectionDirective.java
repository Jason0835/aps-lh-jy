package com.zlt.aps.lh.engine.strategy.support;

import com.zlt.aps.lh.api.domain.entity.LhMouldChangePlan;
import lombok.Data;

/**
 * 前日交替计划机台反选指令。
 *
 * <p>历史计划只提供机台、后物料和映射班次。本批次的交替类型、换模/换活字块时间、
 * 首检、开产、收尾及实际排产量均由当前排程主链重新计算。</p>
 */
@Data
public class HistoricalReverseSelectionDirective {

    /** 原始历史交替计划，仅用于日志和对账，不复制其中的任何时间字段 */
    private LhMouldChangePlan historicalPlan;

    /** 历史窗口班次，只允许4或5 */
    private int historicalShiftIndex;

    /** 当前窗口映射班次：历史4映射1，历史5映射2 */
    private int mappedShiftIndex;

    /** 历史指定机台编码 */
    private String machineCode;

    /**
     * 实际交给排产主链的机台编码。
     * <p>大多数场景与历史机台一致；正规双模SKU指定单控右侧时，现有单控主链使用左侧作为
     * 物理整机代表，但成功后仍同步占用L/R两侧，不改变历史指定物理机台关系。</p>
     */
    private String effectiveMachineCode;

    /** 历史交替计划后物料编码 */
    private String materialCode;

    /** 当前实际消费账本选择的产品状态 */
    private String productStatus;

    /** 当前状态SKU在待排队列中的优先级，用于同物料多状态稳定选择 */
    private Integer skuSortRank;

    /** 是否已经完成指定机台尝试 */
    private boolean attempted;

    /** 是否由续作或换活字块前序排产直接满足 */
    private boolean alreadySatisfied;

    /** 是否反选排产成功 */
    private boolean success;

    /** 当前状态实际判定的交替类型，01-换模，02-换活字块 */
    private String actualChangeType;

    /** 成功说明或明确失败原因 */
    private String resultReason;
}
