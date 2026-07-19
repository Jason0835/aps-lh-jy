/**
 * Copyright (c) 2008, 智立通（厦门）科技有限公司 All rights reserved。
 */
package com.zlt.aps.lh.engine.strategy.impl;

import com.zlt.aps.lh.api.domain.dto.SkuScheduleDTO;
import com.zlt.aps.lh.api.enums.MachineStopTypeEnum;
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
 * <p>启用换模均衡后，每日总次数为硬限制，早/中班次数仅用于共用胎胚均衡，夜班不切换。</p>
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
        if (!isChangeoverBalanceEnabled(context)) {
            return allocateLegacyMouldChange(context, machineCode, endingTime, switchDurationHours);
        }
        return allocateMouldChange(context, machineCode, endingTime, switchDurationHours, null, ACTION_CHANGEOVER);
    }

    @Override
    public Date allocateMouldChange(LhScheduleContext context,
                                    String machineCode,
                                    Date endingTime,
                                    int switchDurationHours,
                                    SkuScheduleDTO sku,
                                    String actionType) {
        if (!isChangeoverBalanceEnabled(context)) {
            return allocateLegacyMouldChange(context, machineCode, endingTime, switchDurationHours);
        }
        if (endingTime == null) {
            return null;
        }

        clearBlockedReason(context, sku);
        Date adjustedTime = endingTime;

        // 最多向后探索有限次数，避免极端数据导致死循环
        for (int attempt = 0; attempt < MAX_ALLOCATION_ATTEMPTS; attempt++) {
            // 先处理设备停机窗口：05允许并行，其他停机仍从停机结束时刻继续判断。
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
            int dailyLimit = getDailyLimit(context);
            int totalUsed = getTotalUsed(counts);

            if (totalUsed >= dailyLimit) {
                if (isOnOrAfterScheduleTargetDate(context, adjustedTime)) {
                    recordBlockedReason(context, sku, dailyLimit);
                    log.warn("换模/换活字块每日次数达到T+2上限，进入未排, materialCode: {}, embryoCode: {}, "
                                    + "actionType: {}, 日期: {}, 当天总次数: {}/{}, 早班次数: {}, 中班次数: {}",
                            sku == null ? null : sku.getMaterialCode(),
                            sku == null ? null : sku.getEmbryoCode(),
                            StringUtils.defaultIfEmpty(actionType, ACTION_CHANGEOVER),
                            dateKey, totalUsed, dailyLimit, counts[IDX_MORNING], counts[IDX_AFTERNOON]);
                    return null;
                }
                Date nextDayMorningStart = getNextCalendarDayMorningStart(context, adjustedTime);
                log.info("换模/换活字块每日次数已达上限，顺延到后一天, materialCode: {}, embryoCode: {}, "
                                + "actionType: {}, 当前日期: {}, 顺延日期: {}, 当天总次数: {}/{}",
                        sku == null ? null : sku.getMaterialCode(),
                        sku == null ? null : sku.getEmbryoCode(),
                        StringUtils.defaultIfEmpty(actionType, ACTION_CHANGEOVER),
                        dateKey, LhScheduleTimeUtil.formatDate(nextDayMorningStart), totalUsed, dailyLimit);
                adjustedTime = nextDayMorningStart;
                continue;
            }

            boolean sharedEmbryo = isSharedEmbryo(context, sku)
                    && !StringUtils.equals(actionType, ACTION_EARLY_PRODUCTION_NEW_SPEC_MOULD_CHANGE);
            Date balancedTime = sharedEmbryo
                    ? resolveSharedEmbryoBalancedTime(context, adjustedTime, counts) : adjustedTime;
            if (balancedTime != null && balancedTime.after(adjustedTime)) {
                adjustedTime = balancedTime;
                continue;
            }

            if (registerMouldChangeCount(context, adjustedTime)) {
                int[] updatedCounts = context.getDailyMouldChangeCountMap().get(dateKey);
                log.info("换模/换活字块班次落点完成, materialCode: {}, embryoCode: {}, 是否共用胎胚: {}, "
                                + "actionType: {}, 日期: {}, 当天总次数: {}/{}, 早班次数: {}, 中班次数: {}, 最终换模班次: {}",
                        sku == null ? null : sku.getMaterialCode(),
                        sku == null ? null : sku.getEmbryoCode(),
                        sharedEmbryo,
                        StringUtils.defaultIfEmpty(actionType, ACTION_CHANGEOVER),
                        dateKey, getTotalUsed(updatedCounts), dailyLimit,
                        updatedCounts[IDX_MORNING], updatedCounts[IDX_AFTERNOON],
                        LhScheduleTimeUtil.isMorningShift(context, adjustedTime) ? "早班" : "中班");
                return adjustedTime;
            }
            adjustedTime = getNextCalendarDayMorningStart(context, adjustedTime);
        }

        log.warn("换模均衡分配失败，无可用换模班次, 原始时间: {}",
                LhScheduleTimeUtil.formatDateTime(endingTime));
        return null;
    }

    /**
     * 旧换模均衡逻辑。
     * <p>参数关闭时保持原早班8次/中班7次硬限制口径不变，避免关闭态排程结果漂移。</p>
     */
    private Date allocateLegacyMouldChange(LhScheduleContext context,
                                           String machineCode,
                                           Date endingTime,
                                           int switchDurationHours) {
        if (endingTime == null) {
            return null;
        }

        Date adjustedTime = endingTime;

        // 最多向后探索有限次数，避免极端数据导致死循环
        for (int attempt = 0; attempt < MAX_ALLOCATION_ATTEMPTS; attempt++) {
            // 先处理设备停机窗口：05允许并行，其他停机仍从停机结束时刻继续判断。
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
     * 判断是否启用换模均衡新口径。
     *
     * @param context 排程上下文
     * @return true-启用；false-关闭
     */
    private boolean isChangeoverBalanceEnabled(LhScheduleContext context) {
        return context != null
                && context.getScheduleConfig() != null
                && context.getScheduleConfig().isChangeoverBalanceEnabled();
    }

    /**
     * 统计当天已使用换模/换活字块总次数。
     *
     * @param counts 当天早/中班计数
     * @return 当天总次数
     */
    private int getTotalUsed(int[] counts) {
        if (counts == null || counts.length < 2) {
            return 0;
        }
        return counts[IDX_MORNING] + counts[IDX_AFTERNOON];
    }

    /**
     * 判断当前SKU是否属于本月共用胎胚。
     *
     * @param context 排程上下文
     * @param sku 当前SKU
     * @return true-共用胎胚；false-单胎胚或无法识别
     */
    private boolean isSharedEmbryo(LhScheduleContext context, SkuScheduleDTO sku) {
        if (context == null || sku == null || StringUtils.isEmpty(sku.getMaterialCode())) {
            return false;
        }
        return Boolean.TRUE.equals(context.getMaterialSharedEmbryoMap().get(sku.getMaterialCode()));
    }

    /**
     * 解析共用胎胚本次应落定的均衡班次。
     * <p>只能在不早于当前可切换时间的候选班次内均衡。
     * 单胎胚不会进入本方法（调用方已通过 isSharedEmbryo 过滤）。
     * 共用胎胚均衡规则：只有原本落早班且早班次数已达阈值（再落会超过）时，才挪到中班；
     * 原本落中班的不因中班次数多而强制挪动。</p>
     *
     * @param context 排程上下文
     * @param candidateTime 当前候选切换时间
     * @param counts 当天早/中班计数
     * @return 均衡后的候选切换时间
     */
    private Date resolveSharedEmbryoBalancedTime(LhScheduleContext context, Date candidateTime, int[] counts) {
        if (candidateTime == null || counts == null || counts.length < 2) {
            return candidateTime;
        }
        // 共用胎胚换模均衡：只有原本落早班且早班次数已超过阈值时，才挪到中班
        // 中班不强制挪动，单胎胚不进入此方法
        int morningLimit = getMorningLimit(context);
        if (LhScheduleTimeUtil.isMorningShift(context, candidateTime)
                && counts[IDX_MORNING] >= morningLimit) {
            return LhScheduleTimeUtil.getAfternoonShiftStart(context, candidateTime);
        }
        return candidateTime;
    }

    /**
     * 登记本次换模/换活字块次数。
     *
     * @param context 排程上下文
     * @param allocatedTime 已落定切换时间
     * @return true-登记成功；false-无法登记
     */
    private boolean registerMouldChangeCount(LhScheduleContext context, Date allocatedTime) {
        if (context == null || allocatedTime == null) {
            return false;
        }
        String dateKey = formatDateKey(allocatedTime);
        int[] counts = context.getDailyMouldChangeCountMap().computeIfAbsent(dateKey, key -> new int[]{0, 0});
        if (LhScheduleTimeUtil.isMorningShift(context, allocatedTime)) {
            counts[IDX_MORNING]++;
            return true;
        }
        if (LhScheduleTimeUtil.isAfternoonShift(context, allocatedTime)) {
            counts[IDX_AFTERNOON]++;
            return true;
        }
        return false;
    }

    /**
     * 判断候选日期是否已经达到排程窗口最后一天。
     *
     * @param context 排程上下文
     * @param candidateTime 候选切换时间
     * @return true-已到窗口结束日(T+2)或更晚；false-仍可顺延
     */
    private boolean isOnOrAfterScheduleTargetDate(LhScheduleContext context, Date candidateTime) {
        if (context == null || context.getWindowEndDate() == null || candidateTime == null) {
            return false;
        }
        Date candidateDate = LhScheduleTimeUtil.clearTime(candidateTime);
        Date targetDate = LhScheduleTimeUtil.clearTime(context.getWindowEndDate());
        return !candidateDate.before(targetDate);
    }

    /**
     * 记录T+2换模/换活字块日上限阻塞原因，供未排结果复用。
     *
     * @param context 排程上下文
     * @param sku 当前SKU
     * @param dailyLimit 每日换模/换活字块上限
     */
    private void recordBlockedReason(LhScheduleContext context, SkuScheduleDTO sku, int dailyLimit) {
        if (context == null || sku == null || StringUtils.isEmpty(sku.getMaterialCode())) {
            return;
        }
        context.getMouldChangeLimitBlockedReasonMap().put(sku.getMaterialCode(),
                "窗口结束日 换模/换活字块次数超过每日" + dailyLimit + "次上限");
    }

    /**
     * 清理当前SKU上一次换模上限阻塞原因，避免候选重试成功后残留旧原因。
     *
     * @param context 排程上下文
     * @param sku 当前SKU
     */
    private void clearBlockedReason(LhScheduleContext context, SkuScheduleDTO sku) {
        if (context == null || sku == null || StringUtils.isEmpty(sku.getMaterialCode())) {
            return;
        }
        context.getMouldChangeLimitBlockedReasonMap().remove(sku.getMaterialCode());
    }

    /**
     * 解析扣除设备停机后的最早换模开始时间。
     * <p>05-计划性维修属于下机维修，换模或换活字块允许在维修窗口内并行完成，因此不再把
     * 切换开始时间顺延到 05 结束；后续由统一维修时间轴执行 max(维修结束, 切换结束)+预热。
     * 00～04、06、09 等其他停机仍保持原顺延语义，不扩大本次规则影响范围。</p>
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
                    || StringUtils.equals(MachineStopTypeEnum.PLANNED_REPAIR.getCode(),
                    planShut.getMachineStopType())
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
