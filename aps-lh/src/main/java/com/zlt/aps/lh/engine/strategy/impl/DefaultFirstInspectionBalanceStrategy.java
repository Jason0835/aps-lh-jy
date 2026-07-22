/**
 * Copyright (c) 2008, 智立通（厦门）科技有限公司 All rights reserved。
 */
package com.zlt.aps.lh.engine.strategy.impl;

import cn.hutool.core.date.DateUtil;
import com.zlt.aps.lh.api.constant.LhScheduleConstant;
import com.zlt.aps.lh.api.constant.LhScheduleParamConstant;
import com.zlt.aps.lh.api.domain.vo.LhShiftConfigVO;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.engine.strategy.IFirstInspectionBalanceStrategy;
import com.zlt.aps.lh.util.FirstInspectionQtyUtil;
import com.zlt.aps.lh.util.LhScheduleTimeUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Objects;

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
    public Date previewInspection(LhScheduleContext context, String machineCode, Date mouldChangeTime) {
        return resolveInspectionTime(context, machineCode, mouldChangeTime, false);
    }

    @Override
    public Date allocateInspection(LhScheduleContext context, String machineCode, Date mouldChangeTime) {
        return resolveInspectionTime(context, machineCode, mouldChangeTime, true);
    }

    /**
     * 解析首检均衡后的实际执行时间。
     * <p>预演和正式分配复用同一套班次识别与额度计算；仅正式分配修改计数并输出分配日志，
     * 避免候选选机阶段提前消费首检资源。</p>
     *
     * @param context 排程上下文
     * @param machineCode 机台编号
     * @param mouldChangeTime 换模完成及首检基准时间
     * @param commit 是否正式提交首检班次计数
     * @return 首检执行时间；无可用班次时返回null
     */
    private Date resolveInspectionTime(LhScheduleContext context,
                                       String machineCode,
                                       Date mouldChangeTime,
                                       boolean commit) {
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
            int[] counts = context.getDailyFirstInspectionCountMap().get(dateKey);
            if (Objects.isNull(counts)) {
                counts = new int[]{0, 0};
                if (commit) {
                    context.getDailyFirstInspectionCountMap().put(dateKey, counts);
                }
            }

            LhShiftConfigVO attributionShift = resolveAttributionShift(context, inspectionTime);
            if (isMorningShift(context, attributionShift, inspectionTime)) {
                if (canAllocateInShift(counts[IDX_MORNING], maxPerShift, unlimitedPerShift)) {
                    if (commit) {
                        counts[IDX_MORNING]++;
                        log.debug("首检分配到早班, 机台: {}, 日期: {}, 早班已用: {}/{}",
                                machineCode, dateKey, counts[IDX_MORNING], maxPerShift);
                    }
                    return inspectionTime;
                }
                // 早班已满：判断中班是否还有首检名额。
                Date afternoonStart = LhScheduleTimeUtil.getAfternoonShiftStart(context, inspectionTime);
                if (canAllocateInShift(counts[IDX_AFTERNOON], maxPerShift, unlimitedPerShift)) {
                    Date effectiveAfternoonTime = alignMovedInspectionTimeToShift(context, afternoonStart);
                    if (commit) {
                        counts[IDX_AFTERNOON]++;
                        log.debug("首检移至中班, 机台: {}, 日期: {}, 中班已用: {}/{}",
                                machineCode, dateKey, counts[IDX_AFTERNOON], maxPerShift);
                    }
                    return effectiveAfternoonTime;
                }
                // 两班都满，延后到次日早班
                inspectionTime = getNextDayMorningStart(context, inspectionTime);
                continue;
            }

            if (isAfternoonShift(context, attributionShift, inspectionTime)) {
                /*
                 * 首检使用已完成换模的时间落点，中班全班均可按班次名额分配。
                 * 20:00后禁止“开始换模”的约束已由换模分配器处理，不得再用它拒绝
                 * 已合法开始、并在中班完成的换模首检。
                 */
                if (canAllocateInShift(counts[IDX_AFTERNOON], maxPerShift, unlimitedPerShift)) {
                    if (commit) {
                        counts[IDX_AFTERNOON]++;
                        log.debug("首检分配到中班, 机台: {}, 日期: {}, 中班已用: {}/{}",
                                machineCode, dateKey, counts[IDX_AFTERNOON], maxPerShift);
                    }
                    return inspectionTime;
                }
                // 中班首检名额已满，换到次日早班。
                inspectionTime = getNextDayMorningStart(context, inspectionTime);
                continue;
            }

            if (isNightShift(context, attributionShift, inspectionTime)) {
                // 夜班首检允许执行，但不占早/中班上限计数。
                if (commit) {
                    log.debug("首检分配到夜班(不占上限), 机台: {}, 日期: {}", machineCode, dateKey);
                }
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
        LhShiftConfigVO attributionShift = resolveAttributionShift(context, inspectionTime);
        if (isMorningShift(context, attributionShift, inspectionTime) && counts[IDX_MORNING] > 0) {
            counts[IDX_MORNING]--;
            return;
        }
        if (isAfternoonShift(context, attributionShift, inspectionTime) && counts[IDX_AFTERNOON] > 0) {
            counts[IDX_AFTERNOON]--;
        }
    }

    /**
     * 使用首检数量归属的同一班次边界识别资源班次。
     *
     * @param context 排程上下文
     * @param inspectionTime 首检时间
     * @return 命中的排程班次；窗口未初始化时返回null
     */
    private LhShiftConfigVO resolveAttributionShift(LhScheduleContext context, Date inspectionTime) {
        List<LhShiftConfigVO> shifts = Objects.isNull(context) ? null : context.getScheduleWindowShifts();
        return FirstInspectionQtyUtil.resolveAttributionShift(shifts, inspectionTime);
    }

    private boolean isMorningShift(LhScheduleContext context,
                                   LhShiftConfigVO shift,
                                   Date inspectionTime) {
        return Objects.nonNull(shift) ? shift.isMorningShift()
                : LhScheduleTimeUtil.isMorningShift(context, inspectionTime);
    }

    private boolean isAfternoonShift(LhScheduleContext context,
                                     LhShiftConfigVO shift,
                                     Date inspectionTime) {
        return Objects.nonNull(shift) ? shift.isAfternoonShift()
                : LhScheduleTimeUtil.isAfternoonShift(context, inspectionTime);
    }

    private boolean isNightShift(LhScheduleContext context,
                                 LhShiftConfigVO shift,
                                 Date inspectionTime) {
        return Objects.nonNull(shift) ? shift.isNightShift()
                : LhScheduleTimeUtil.isNightShift(context, inspectionTime);
    }

    /**
     * 首检从前一班顺延到下一班时避开重叠边界。
     * <p>首检数量规则规定边界时刻归前一班，因此顺延时间在下一班起点后增加1秒，
     * 保证资源班次和数量归属班次完全一致。未初始化排程窗口时保持原兼容行为。</p>
     *
     * @param context 排程上下文
     * @param shiftStartTime 下一班开始时间
     * @return 可明确归入下一班的首检时间
     */
    private Date alignMovedInspectionTimeToShift(LhScheduleContext context, Date shiftStartTime) {
        if (Objects.isNull(context) || Objects.isNull(shiftStartTime)
                || CollectionUtils.isEmpty(context.getScheduleWindowShifts())) {
            return shiftStartTime;
        }
        return DateUtil.offsetSecond(shiftStartTime, 1);
    }

    /**
     * 获取日历次日早班开始时间。
     * <p>用于早/中班首检配额用尽后的再顺延；语义为「clearTime(current)+1 天」的早班，
     * 与 {@link LhScheduleTimeUtil#resolveNextMorningAfterNoMouldChangeWindow}（禁止换模跨凌晨回退到当日早班）不同。</p>
     */
    private Date getNextDayMorningStart(LhScheduleContext context, Date currentTime) {
        Date nextDay = LhScheduleTimeUtil.addDays(LhScheduleTimeUtil.clearTime(currentTime), 1);
        Date morningStart = LhScheduleTimeUtil.buildTime(
                nextDay, LhScheduleTimeUtil.getMorningStartHour(context), 0, 0);
        return alignMovedInspectionTimeToShift(context, morningStart);
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
