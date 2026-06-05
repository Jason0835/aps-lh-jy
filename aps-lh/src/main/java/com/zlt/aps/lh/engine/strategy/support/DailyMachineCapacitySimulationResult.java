package com.zlt.aps.lh.engine.strategy.support;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * dayN 机台产能模拟结果。
 */
@Data
public class DailyMachineCapacitySimulationResult implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 最终启用机台数 */
    private int finalActiveMachines;

    /** 累计新增机台数 */
    private int totalAddedMachineCount;

    /**
     * 最终仍未满足数量。
     * <p>普通模式下表示窗口滚动后仍未消化的欠产量；
     * 欠产阈值强制模式下表示“距离阈值仍不足的缺口”，不是窗口后实际剩余欠产。</p>
     */
    private int totalUnmetQty;

    /** 逐日决策明细 */
    private List<DailyMachineCapacityDayDecision> dayDecisionList =
            new ArrayList<DailyMachineCapacityDayDecision>(4);
}
