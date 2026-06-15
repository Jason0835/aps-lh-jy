package com.zlt.aps.lh.engine.strategy.support;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDate;

/**
 * dayN 机台产能模拟逐日决策。
 */
@Data
public class DailyMachineCapacityDayDecision implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 当前生产日期 */
    private LocalDate productionDate;

    /** 允许追补的截止日期 */
    private LocalDate lookAheadEndDate;

    /** 当前 dayN 日计划量 */
    private int todayPlanQty;

    /** 扣减T日晚班已完成量后的当前日计划判断量 */
    private int currentDayPlanQty;

    /** 进入当前日之前累计待追补量 */
    private int carryShortageQty;

    /** 当前日实际需求量：carryShortage + todayPlanQty */
    private int todayRequiredQty;

    /** 当前启用机台在当前日的有效产能 */
    private int todayCapacityQty;

    /** 当前日结束后仍需后续追补的欠产量 */
    private int dayShortageQty;

    /** 当前日期到追补截止日的需求量 */
    private int demandQty;

    /** 当前启用机台在追补窗口内的产能 */
    private int capacityQty;

    /** 决策模式 */
    private String decisionMode;

    /** 当前启用机台8班窗口总产能 */
    private int windowTotalCapacityQty;

    /** T日到T+2窗口日计划总量 */
    private int windowPlanQty;

    /** 欠产强制增机台模式下，T~T+2原始月计划总量 */
    private int windowMonthPlanQty;

    /** T日晚班已完成量 */
    private int scheduleDayFinishQty;

    /** 当前启用机台在T~T+2窗口内的有效产能 */
    private int windowEffectiveCapacityQty;

    /** 按公式计算的窗口后剩余欠产 */
    private int windowRemainingShortageQty;

    /** 欠产强制增机台阈值 */
    private int shortageAddMachineThreshold;

    /** 是否超过欠产增机台阈值 */
    private boolean shortageThresholdExceeded;

    /** 后一天日月计划量 */
    private int nextDayPlanQty;

    /** 后看生产日期 */
    private LocalDate nextProductionDate;

    /** 当前启用机台后一天3班理论产能 */
    private int nextDayThreeShiftCapacityQty;

    /** 当前日月计划量是否已被当前机台产能满足 */
    private boolean currentDayPlanSatisfied;

    /** 是否进入后看下一日计划判断 */
    private boolean nextDayLookAheadEntered;

    /** 当天模拟后的启用机台数 */
    private int activeMachineCount;

    /** 当天新增机台数 */
    private int addedMachineCount;

    /** 仍未满足的数量 */
    private int unmetQty;

    /** 是否执行扩机或保留 */
    private boolean changed;

    /** 决策原因 */
    private String reason;
}
