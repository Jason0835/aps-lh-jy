/**
 * Copyright (c) 2008, 智立通（厦门）科技有限公司 All rights reserved。
 */
package com.zlt.aps.lh.engine.strategy.impl;

import com.zlt.aps.lh.api.constant.LhScheduleConstant;
import com.zlt.aps.lh.api.constant.LhScheduleParamConstant;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.engine.strategy.IFirstInspectionBalanceStrategy;
import com.zlt.aps.lh.util.LhScheduleTimeUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * 默认首检均衡策略实现
 * <p>将需首检任务均衡分配到早/中/夜班，其中夜班首检不占早/中班上限计数</p>
 *
 * @author APS
 */
@Slf4j
@Component
public class DefaultFirstInspectionBalanceStrategy implements IFirstInspectionBalanceStrategy {

    /** dailyFirstInspectionCountMap value数组下标：[0]=早班首检数, [1]=中班首检数 */
    private static final int IDX_MORNING = 0;
    private static final int IDX_AFTERNOON = 1;
    private static final String DATE_KEY_FORMAT = "yyyy-MM-dd";

    @Override
    public Date allocateInspection(LhScheduleContext context, String machineCode, Date mouldChangeTime) {
        if (mouldChangeTime == null) {
            return null;
        }

        int maxPerShift = context.getParamIntValue(LhScheduleParamConstant.MAX_FIRST_INSPECTION_PER_SHIFT,
                LhScheduleConstant.MAX_FIRST_INSPECTION_PER_SHIFT);
        boolean unlimitedPerShift = maxPerShift < 0;

        // 首检时间 = 换模完成时间（上机后的首检）
        Date inspectionTime = mouldChangeTime;

        // 最多向后探索5天
        for (int dayOffset = 0; dayOffset < 5; dayOffset++) {
            String dateKey = formatDateKey(inspectionTime);
            int[] counts = context.getDailyFirstInspectionCountMap().computeIfAbsent(dateKey, k -> new int[]{0, 0});

            if (LhScheduleTimeUtil.isMorningShift(context, inspectionTime)) {
                if (canAllocateInShift(counts[IDX_MORNING], maxPerShift, unlimitedPerShift)) {
                    counts[IDX_MORNING]++;
                    log.debug("首检分配到早班, 机台: {}, 日期: {}, 早班已用: {}/{}", machineCode, dateKey, counts[IDX_MORNING], maxPerShift);
                    return inspectionTime;
                }
                // 早班已满：判断中班是否有空间且时间窗口满足
                Date afternoonStart = LhScheduleTimeUtil.getAfternoonShiftStart(context, inspectionTime);
                if (canAllocateInShift(counts[IDX_AFTERNOON], maxPerShift, unlimitedPerShift)
                        && isAfternoonWindowValid(context, afternoonStart)) {
                    counts[IDX_AFTERNOON]++;
                    log.debug("首检移至中班, 机台: {}, 日期: {}, 中班已用: {}/{}", machineCode, dateKey, counts[IDX_AFTERNOON], maxPerShift);
                    return afternoonStart;
                }
                // 两班都满，延后到次日早班
                inspectionTime = getNextDayMorningStart(context, inspectionTime);
                continue;
            }

            if (LhScheduleTimeUtil.isAfternoonShift(context, inspectionTime)) {
                // 检查中班时间窗口是否足够（14:00-20:00之内可以首检）
                if (canAllocateInShift(counts[IDX_AFTERNOON], maxPerShift, unlimitedPerShift)
                        && isAfternoonWindowValid(context, inspectionTime)) {
                    counts[IDX_AFTERNOON]++;
                    log.debug("首检分配到中班, 机台: {}, 日期: {}, 中班已用: {}/{}", machineCode, dateKey, counts[IDX_AFTERNOON], maxPerShift);
                    return inspectionTime;
                }
                // 中班已满或时间窗口不足，换到次日早班
                inspectionTime = getNextDayMorningStart(context, inspectionTime);
                continue;
            }

            if (LhScheduleTimeUtil.isNightShift(context, inspectionTime)) {
                // 夜班首检允许执行，但不占早/中班上限计数。
                log.debug("首检分配到夜班(不占上限), 机台: {}, 日期: {}", machineCode, dateKey);
                return inspectionTime;
            }

            // 未命中任何班次时，保守顺延到次日早班
            inspectionTime = getNextDayMorningStart(context, inspectionTime);
        }

        log.warn("首检均衡分配失败，无可用班次, 机台: {}, 换模时间: {}",
                machineCode, LhScheduleTimeUtil.formatDateTime(mouldChangeTime));
        return null;
    }

    @Override
    public void rollbackInspection(LhScheduleContext context, Date inspectionTime) {
        if (context == null || inspectionTime == null) {
            return;
        }
        String dateKey = formatDateKey(inspectionTime);
        int[] counts = context.getDailyFirstInspectionCountMap().get(dateKey);
        if (counts == null) {
            return;
        }
        if (LhScheduleTimeUtil.isMorningShift(context, inspectionTime) && counts[IDX_MORNING] > 0) {
            counts[IDX_MORNING]--;
            return;
        }
        if (LhScheduleTimeUtil.isAfternoonShift(context, inspectionTime) && counts[IDX_AFTERNOON] > 0) {
            counts[IDX_AFTERNOON]--;
        }
    }

    /**
     * 中班时间窗口有效性检查
     * <p>中班可安排首检的时间窗口：14:00-20:00（禁止换模开始时间之前），需能完成换模+首检</p>
     */
    private boolean isAfternoonWindowValid(LhScheduleContext context, Date inspectionTime) {
        int noMouldChangeStartHour = LhScheduleTimeUtil.getNoMouldChangeStartHour(context);
        // 首检需在20:00前开始
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.setTime(inspectionTime);
        int hour = cal.get(java.util.Calendar.HOUR_OF_DAY);
        int minute = cal.get(java.util.Calendar.MINUTE);
        // 首检时间必须在20:00之前，且首检耗时1小时内能完成
        return hour < noMouldChangeStartHour
                || (hour == noMouldChangeStartHour - 1 && minute == 0);
    }

    /**
     * 获取日历次日早班开始时间。
     * <p>用于早/中班首检配额用尽后的再顺延；语义为「clearTime(current)+1 天」的早班，
     * 与 {@link LhScheduleTimeUtil#resolveNextMorningAfterNoMouldChangeWindow}（禁止换模跨凌晨回退到当日早班）不同。</p>
     */
    private Date getNextDayMorningStart(LhScheduleContext context, Date currentTime) {
        Date nextDay = LhScheduleTimeUtil.addDays(LhScheduleTimeUtil.clearTime(currentTime), 1);
        return LhScheduleTimeUtil.buildTime(nextDay, LhScheduleTimeUtil.getMorningStartHour(context), 0, 0);
    }

    private String formatDateKey(Date date) {
        // TODO 后续可统一替换为 Hutool 或 java.time 格式化，当前保持首检日维度计数键不变。
        return new SimpleDateFormat(DATE_KEY_FORMAT).format(date);
    }

    /**
     * 判断当前班次是否可继续分配首检。
     *
     * @param usedCount          已占用首检数量
     * @param maxPerShift        每班首检上限
     * @param unlimitedPerShift  是否不限量（true 表示不限量）
     * @return true-允许分配，false-不允许分配
     */
    private boolean canAllocateInShift(int usedCount, int maxPerShift, boolean unlimitedPerShift) {
        if (unlimitedPerShift) {
            return true;
        }
        return usedCount < maxPerShift;
    }
}
