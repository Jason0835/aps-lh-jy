package com.zlt.aps.lh.api.domain.dto;

import lombok.Data;

import java.time.LocalDate;

/**
 * 硫化月计划总量计算结果。
 */
@Data
public class CuringMonthPlanTotalResult {

    /** 硫化月计划总量 */
    private int monthPlanTotal;

    /** 排程窗口开始日期 */
    private LocalDate scheduleStartDate;

    /** 窗口内最晚有计划日期 */
    private LocalDate latestPlanDateInWindow;

    /** 月计划断点日 */
    private LocalDate breakPointDate;

    /** 是否跨月 */
    private boolean crossMonth;

    /** T日所属月份计划总量 */
    private int currentMonthPlanTotal;

    /** 跨月月份计划总量 */
    private int crossMonthPlanTotal;

    /** 计算场景 */
    private String calculateScene;
}
