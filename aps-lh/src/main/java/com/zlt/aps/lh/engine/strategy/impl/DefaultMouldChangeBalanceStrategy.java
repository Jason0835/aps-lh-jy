/**
 * Copyright (c) 2008, 智立通（厦门）科技有限公司 All rights reserved。
 */
package com.zlt.aps.lh.engine.strategy.impl;

import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.engine.strategy.IMouldChangeBalanceStrategy;
import com.zlt.aps.lh.util.LhScheduleTimeUtil;
import com.zlt.aps.mdm.api.domain.entity.MdmDevicePlanShut;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.util.Date;

/**
 * 默认模具切换均衡策略实现
 * <p>控制每日模具切换总数(最多15台)和早/中班切换均衡(早班8台, 中班7台), 夜班不切换</p>
 *
 * @author APS
 */
@Slf4j
@Component
public class DefaultMouldChangeBalanceStrategy implements IMouldChangeBalanceStrategy {

    /** dailyMouldChangeCountMap value数组下标：[0]=早班换模数, [1]=中班换模数 */
    private static final int IDX_MORNING = 0;
    private static final int IDX_AFTERNOON = 1;
    private static final int MAX_ALLOCATION_ATTEMPTS = 16;

    @Override
    public boolean hasCapacity(LhScheduleContext context, Date targetDate) {
        String dateKey = formatDateKey(targetDate);
        int[] counts = context.getDailyMouldChangeCountMap().getOrDefault(dateKey, new int[]{0, 0});
        int totalUsed = counts[IDX_MORNING] + counts[IDX_AFTERNOON];
        int dailyLimit = getDailyLimit(context);
        return totalUsed < dailyLimit;
    }

    @Override
    public Date allocateMouldChange(LhScheduleContext context, String machineCode, Date endingTime) {
        return allocateMouldChange(
                context,
                machineCode,
                endingTime,
                LhScheduleTimeUtil.getMouldChangeTotalHours(context));
    }

    @Override
    public Date allocateMouldChange(LhScheduleContext context,
                                    String machineCode,
                                    Date endingTime,
                                    int switchDurationHours) {
        if (endingTime == null) {
            return null;
        }

        Date adjustedTime = endingTime;

        // 最多向后探索有限次数，避免极端数据导致死循环
        for (int attempt = 0; attempt < MAX_ALLOCATION_ATTEMPTS; attempt++) {
            // 先扣掉设备停机窗口，确保“停机后再换模”从停机结束时刻继续判断。
            Date downtimeAdjustedTime = resolveDowntimeAdjustedStartTime(
                    context, machineCode, adjustedTime, switchDurationHours);
            if (downtimeAdjustedTime.after(adjustedTime)) {
                adjustedTime = downtimeAdjustedTime;
                continue;
            }

            // 若在禁止换模时间段内（20:00-次日6:00），顺延到禁止时段结束后的第一个早班（凌晨段为当日早班，晚间段为次日早班）
            if (LhScheduleTimeUtil.isNoMouldChangeTime(context, adjustedTime)) {
                adjustedTime = LhScheduleTimeUtil.resolveNextMorningAfterNoMouldChangeWindow(context, adjustedTime);
                continue;
            }

            String dateKey = formatDateKey(adjustedTime);
            int[] counts = context.getDailyMouldChangeCountMap().computeIfAbsent(dateKey, k -> new int[]{0, 0});

            int morningLimit = getMorningLimit(context);
            int afternoonLimit = getAfternoonLimit(context);

            if (LhScheduleTimeUtil.isMorningShift(context, adjustedTime)) {
                // 当前时间在早班
                if (counts[IDX_MORNING] < morningLimit) {
                    counts[IDX_MORNING]++;
                    log.debug("换模分配到早班, 日期: {}, 早班已用: {}/{}", dateKey, counts[IDX_MORNING], morningLimit);
                    return adjustedTime;
                }
                // 早班已满，换模后移到当天中班开始时间
                adjustedTime = LhScheduleTimeUtil.getAfternoonShiftStart(context, adjustedTime);
                continue;
            }

            if (LhScheduleTimeUtil.isAfternoonShift(context, adjustedTime)) {
                // 当前时间在中班
                if (counts[IDX_AFTERNOON] < afternoonLimit) {
                    counts[IDX_AFTERNOON]++;
                    log.debug("换模分配到中班, 日期: {}, 中班已用: {}/{}", dateKey, counts[IDX_AFTERNOON], afternoonLimit);
                    return adjustedTime;
                }
                // 中班也满了，延后到日历次日早班（与禁止换模窗口后的「当日早班」语义不同）
                adjustedTime = getNextCalendarDayMorningStart(context, adjustedTime);
                continue;
            }

            // 夜班不换模，直接顺延到日历次日早班（常规配置下多由禁止换模分支先行处理）
            adjustedTime = getNextCalendarDayMorningStart(context, adjustedTime);
        }

        log.warn("换模均衡分配失败，无可用换模班次, 原始时间: {}",
                LhScheduleTimeUtil.formatDateTime(endingTime));
        return null;
    }

    @Override
    public void rollbackMouldChange(LhScheduleContext context, Date allocatedTime) {
        if (context == null || allocatedTime == null) {
            return;
        }
        String dateKey = formatDateKey(allocatedTime);
        int[] counts = context.getDailyMouldChangeCountMap().get(dateKey);
        if (counts == null) {
            return;
        }
        if (LhScheduleTimeUtil.isMorningShift(context, allocatedTime) && counts[IDX_MORNING] > 0) {
            counts[IDX_MORNING]--;
            return;
        }
        if (LhScheduleTimeUtil.isAfternoonShift(context, allocatedTime) && counts[IDX_AFTERNOON] > 0) {
            counts[IDX_AFTERNOON]--;
        }
    }

    @Override
    public int getRemainingCapacity(LhScheduleContext context, Date targetDate) {
        String dateKey = formatDateKey(targetDate);
        int[] counts = context.getDailyMouldChangeCountMap().getOrDefault(dateKey, new int[]{0, 0});
        int totalUsed = counts[IDX_MORNING] + counts[IDX_AFTERNOON];
        int dailyLimit = getDailyLimit(context);
        return Math.max(0, dailyLimit - totalUsed);
    }

    /**
     * 解析扣除设备停机后的最早换模开始时间。
     * <p>若候选换模窗口命中设备停机，则顺延到该停机结束时间。</p>
     */
    private Date resolveDowntimeAdjustedStartTime(LhScheduleContext context,
                                                  String machineCode,
                                                  Date candidateStartTime,
                                                  int switchDurationHours) {
        if (context == null
                || StringUtils.isEmpty(machineCode)
                || candidateStartTime == null
                || CollectionUtils.isEmpty(context.getDevicePlanShutList())) {
            return candidateStartTime;
        }
        Date candidateEndTime = LhScheduleTimeUtil.addHours(
                candidateStartTime, switchDurationHours);
        Date latestOverlapEndTime = null;
        for (MdmDevicePlanShut planShut : context.getDevicePlanShutList()) {
            if (planShut == null
                    || !StringUtils.equals(machineCode, planShut.getMachineCode())
                    || planShut.getBeginDate() == null
                    || planShut.getEndDate() == null
                    || !planShut.getBeginDate().before(planShut.getEndDate())) {
                continue;
            }
            if (!candidateStartTime.before(planShut.getEndDate())
                    || !planShut.getBeginDate().before(candidateEndTime)) {
                continue;
            }
            if (latestOverlapEndTime == null || planShut.getEndDate().after(latestOverlapEndTime)) {
                latestOverlapEndTime = planShut.getEndDate();
            }
        }
        return latestOverlapEndTime != null ? latestOverlapEndTime : candidateStartTime;
    }

    /**
     * 日历次日早班开始时间（用于中班换模配额已满等「已进入可换模日段」后的再顺延）
     */
    private Date getNextCalendarDayMorningStart(LhScheduleContext context, Date currentTime) {
        Date nextDay = LhScheduleTimeUtil.addDays(LhScheduleTimeUtil.clearTime(currentTime), 1);
        return LhScheduleTimeUtil.buildTime(nextDay, LhScheduleTimeUtil.getMorningStartHour(context), 0, 0);
    }

    private String formatDateKey(Date date) {
        return LhScheduleTimeUtil.formatDate(date);
    }

    private int getDailyLimit(LhScheduleContext context) {
        return LhScheduleTimeUtil.getDailyMouldChangeLimit(context);
    }

    private int getMorningLimit(LhScheduleContext context) {
        return LhScheduleTimeUtil.getMorningMouldChangeLimit(context);
    }

    private int getAfternoonLimit(LhScheduleContext context) {
        return LhScheduleTimeUtil.getAfternoonMouldChangeLimit(context);
    }
}
