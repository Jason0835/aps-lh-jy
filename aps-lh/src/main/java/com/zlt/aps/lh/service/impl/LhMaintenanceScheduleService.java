package com.zlt.aps.lh.service.impl;

import com.zlt.aps.lh.api.constant.LhScheduleConstant;
import com.zlt.aps.lh.api.constant.LhScheduleParamConstant;
import com.zlt.aps.lh.api.domain.dto.MachineMaintenanceWindowDTO;
import com.zlt.aps.lh.api.domain.dto.MachineScheduleDTO;
import com.zlt.aps.lh.api.domain.entity.LhMachineOnlineInfo;
import com.zlt.aps.lh.api.domain.entity.LhPrecisionPlan;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.util.LhSingleControlMachineUtil;
import com.zlt.aps.lh.util.LhScheduleTimeUtil;
import com.zlt.aps.mdm.api.domain.entity.MdmWorkCalendar;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.util.Calendar;
import java.util.Date;
import java.util.Objects;

/**
 * 硫化机精度保养计划排程服务。
 *
 * @author APS
 */
@Slf4j
@Component
public class LhMaintenanceScheduleService {

    /** 普通收尾触发原因 */
    private static final String TRIGGER_REASON_AFTER_ENDING = "首个规格收尾后保养";
    /** 长期在机触发原因 */
    private static final String TRIGGER_REASON_FORCE_DOWN = "长期在机强制下机";
    /** 长期在机天数阈值 */
    private static final int LONG_ONLINE_DAYS = 30;
    /** 启用配置值 */
    private static final int ENABLED = 1;

    /**
     * 首个规格收尾后尝试挂载保养窗口。
     *
     * @param context 排程上下文
     * @param machine 机台
     * @param endingTime 首个规格收尾时间
     * @return true-已安排保养；false-未安排保养
     */
    public boolean tryAttachMaintenanceAfterFirstEnding(LhScheduleContext context,
                                                        MachineScheduleDTO machine,
                                                        Date endingTime) {
        if (!isBasicValid(context, machine) || Objects.isNull(endingTime)
                || !CollectionUtils.isEmpty(machine.getMaintenanceWindowList())) {
            return false;
        }
        String lookupMachineCode = machine.getMachineCode();
        if (!hasNoRecentOnlineRecord(context, lookupMachineCode)) {
            return false;
        }
        LhPrecisionPlan plan = context.getMaintenancePlanMap().get(lookupMachineCode);
        if (!isPlanUncompleted(plan)) {
            return false;
        }
        if (!isPlanDueSoon(context, plan)) {
            return false;
        }
        return attachMaintenanceWindow(context, machine, plan, LhScheduleTimeUtil.clearTime(endingTime),
                false, TRIGGER_REASON_AFTER_ENDING);
    }

    /**
     * 长期在机到期前检查时尝试挂载强制下机保养窗口。
     *
     * @param context 排程上下文
     * @param machine 机台
     * @return true-已安排保养；false-未安排保养
     */
    public boolean tryAttachLongOnlineMaintenance(LhScheduleContext context, MachineScheduleDTO machine) {
        if (!isBasicValid(context, machine) || !CollectionUtils.isEmpty(machine.getMaintenanceWindowList())) {
            return false;
        }
        String lookupMachineCode = machine.getMachineCode();
        LhPrecisionPlan plan = context.getMaintenancePlanMap().get(lookupMachineCode);
        Integer daysToDue = resolveDaysToDue(plan);
        if (Objects.isNull(plan) || Objects.isNull(daysToDue) || Objects.isNull(context.getScheduleDate())) {
            return false;
        }
        LhMachineOnlineInfo onlineInfo = context.getMachineOnlineInfoMap().get(lookupMachineCode);
        if (Objects.isNull(onlineInfo)
                || !LhSingleControlMachineUtil.isLeftRightCompatible(machine.getMachineCode(), onlineInfo.getLrMolds())
                || Objects.isNull(onlineInfo.getOnlineDate())) {
            return false;
        }
        int onlineDays = diffDays(onlineInfo.getOnlineDate(), context.getScheduleDate());
        int forceCheckDays = getParamInt(context, LhScheduleParamConstant.MAINTENANCE_FORCE_CHECK_DAYS,
                LhScheduleConstant.MAINTENANCE_FORCE_CHECK_DAYS);
        if (onlineDays <= LONG_ONLINE_DAYS || daysToDue > forceCheckDays) {
            return false;
        }
        log.info("硫化机长期在机触发保养检查, 机台: {}, 在机日期: {}, 在机天数: {}, 距到期天数: {}, 提前检查天数: {}",
                machine.getMachineCode(), LhScheduleTimeUtil.formatDate(onlineInfo.getOnlineDate()), onlineDays,
                daysToDue, forceCheckDays);
        return attachMaintenanceWindow(context, machine, plan, LhScheduleTimeUtil.clearTime(context.getScheduleDate()),
                true, TRIGGER_REASON_FORCE_DOWN);
    }

    /**
     * 根据保养窗口顺延切换开始时间。
     *
     * @param machine 机台
     * @param candidateStartTime 候选切换开始时间
     * @param switchDurationHours 切换耗时
     * @return 顺延后的切换开始时间
     */
    public Date delaySwitchStartByMaintenance(MachineScheduleDTO machine,
                                              Date candidateStartTime,
                                              int switchDurationHours) {
        if (Objects.isNull(machine) || Objects.isNull(candidateStartTime)
                || CollectionUtils.isEmpty(machine.getMaintenanceWindowList())) {
            return candidateStartTime;
        }
        Date adjustedStartTime = candidateStartTime;
        int maxAttempts = Math.max(machine.getMaintenanceWindowList().size() + 1, 4);
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            Date candidateEndTime = LhScheduleTimeUtil.addHours(adjustedStartTime, switchDurationHours);
            Date latestOverlapEndTime = null;
            for (MachineMaintenanceWindowDTO window : machine.getMaintenanceWindowList()) {
                if (!isWindowOverlap(window, adjustedStartTime, candidateEndTime)) {
                    continue;
                }
                latestOverlapEndTime = later(latestOverlapEndTime, window.getMaintenanceEndTime());
            }
            if (Objects.isNull(latestOverlapEndTime) || !latestOverlapEndTime.after(adjustedStartTime)) {
                return adjustedStartTime;
            }
            log.debug("切换窗口命中保养占用，顺延切换开始, 机台: {}, 原开始: {}, 顺延到: {}",
                    machine.getMachineCode(), LhScheduleTimeUtil.formatDateTime(adjustedStartTime),
                    LhScheduleTimeUtil.formatDateTime(latestOverlapEndTime));
            adjustedStartTime = latestOverlapEndTime;
        }
        return adjustedStartTime;
    }

    /**
     * 解析维保恢复后的开产时间。
     *
     * @param context 排程上下文
     * @param machine 机台
     * @param defaultReadyTime 默认就绪时间
     * @return 恢复后的开产时间
     */
    public Date resolveMaintenanceResumeProductionTime(LhScheduleContext context,
                                                       MachineScheduleDTO machine,
                                                       Date defaultReadyTime) {
        Date maintenanceEndTime = resolveMaintenanceEndTime(context, machine, null);
        if (Objects.isNull(maintenanceEndTime)) {
            return defaultReadyTime;
        }
        Date resumeProductionTime = LhScheduleTimeUtil.addMinutes(
                maintenanceEndTime, LhScheduleTimeUtil.getCapsulePreheatMinutes(context));
        if (Objects.isNull(defaultReadyTime) || resumeProductionTime.after(defaultReadyTime)) {
            return resumeProductionTime;
        }
        return defaultReadyTime;
    }

    /**
     * 判断当前切换是否应套用维保重叠专用时长。
     *
     * @param context 排程上下文
     * @param machine 机台
     * @param referenceTime 切换参考起点
     * @return true-需要套用；false-不需要
     */
    public boolean shouldApplyMaintenanceOverlapSwitchRule(LhScheduleContext context,
                                                           MachineScheduleDTO machine,
                                                           Date referenceTime) {
        Date maintenanceEndTime = resolveMaintenanceEndTime(context, machine, referenceTime);
        return Objects.nonNull(referenceTime)
                && Objects.nonNull(maintenanceEndTime)
                && referenceTime.before(maintenanceEndTime);
    }

    /**
     * 解析机台当前生效的维保结束时间。
     *
     * @param context 排程上下文
     * @param machine 机台
     * @return 维保结束时间；未命中返回 null
     */
    public Date resolveMaintenanceEndTime(LhScheduleContext context, MachineScheduleDTO machine) {
        return resolveMaintenanceEndTime(context, machine, null);
    }

    /**
     * 判断正常换模窗口是否与机台维保窗口物理重叠。
     * <p>试制SKU换模需在早班完成，当维保重叠规则触发时（shouldApplyMaintenanceOverlapSwitchRule=true），
     * 需进一步检查以换模开始时间 + 正常换模时长构建的换模窗口，是否与维保窗口存在物理时间重叠。
     * 若不重叠，说明换模可在维保开始前完成，试制SKU可使用正常换模，无需等待维保结束。</p>
     *
     * @param context 排程上下文
     * @param machine 机台运行态
     * @param switchStartTime 换模开始时间（通常为机台就绪时间）
     * @param switchDurationHours 正常换模时长（小时）
     * @return true-换模窗口与维保窗口有实际时间重叠；false-无重叠或无机台维保计划
     */
    public boolean isNormalSwitchOverlapMaintenance(LhScheduleContext context,
                                                     MachineScheduleDTO machine,
                                                     Date switchStartTime,
                                                     int switchDurationHours) {
        if (Objects.isNull(machine) || !machine.isHasMaintenancePlan()
                || Objects.isNull(switchStartTime) || switchDurationHours <= 0) {
            return false;
        }
        // 计算正常换模窗口结束时间
        Date switchEndTime = LhScheduleTimeUtil.addHours(switchStartTime, switchDurationHours);
        if (CollectionUtils.isEmpty(machine.getMaintenanceWindowList())) {
            return false;
        }
        for (MachineMaintenanceWindowDTO window : machine.getMaintenanceWindowList()) {
            if (Objects.isNull(window)
                    || Objects.isNull(window.getMaintenanceStartTime())
                    || Objects.isNull(window.getMaintenanceEndTime())
                    || !window.getMaintenanceStartTime().before(window.getMaintenanceEndTime())) {
                continue;
            }
            // 换模窗口与维保窗口有实际重叠：换模开始 < 维保结束 且 换模结束 > 维保开始
            if (switchStartTime.before(window.getMaintenanceEndTime())
                    && switchEndTime.after(window.getMaintenanceStartTime())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 清除机台当前所有维保窗口。
     * <p>试制SKU换模需在早班完成，当维保窗口与早班换模窗口物理重叠时，
     * 需先清除维保窗口使试制换模能在早班进行，维保将在后续排程迭代中重新安排。</p>
     *
     * @param machine 机台运行态
     */
    public void clearMaintenanceWindows(MachineScheduleDTO machine) {
        if (Objects.isNull(machine)) {
            return;
        }
        if (!CollectionUtils.isEmpty(machine.getMaintenanceWindowList())) {
            log.info("试制SKU换模与维保窗口重叠，清除机台维保窗口以便早班换模, 机台: {}, 维保窗口数: {}",
                    machine.getMachineCode(), machine.getMaintenanceWindowList().size());
            machine.getMaintenanceWindowList().clear();
            machine.setHasMaintenancePlan(false);
            machine.setMaintenancePlanTime(null);
        }
    }

    private boolean attachMaintenanceWindow(LhScheduleContext context,
                                            MachineScheduleDTO machine,
                                            LhPrecisionPlan plan,
                                            Date candidateDate,
                                            boolean forceDown,
                                            String triggerReason) {
        Date initialCandidateDate = LhScheduleTimeUtil.clearTime(candidateDate);
        Date planDate = resolveAvailableMaintenanceDate(context, candidateDate);
        int startHour = getParamInt(context, LhScheduleParamConstant.MAINTENANCE_START_HOUR,
                LhScheduleConstant.MAINTENANCE_START_HOUR);
        int durationHours = getParamInt(context, LhScheduleParamConstant.MAINTENANCE_DURATION_HOURS,
                LhScheduleConstant.MAINTENANCE_DURATION_HOURS);
        Date startTime = LhScheduleTimeUtil.buildTime(planDate, startHour, 0, 0);
        Date endTime = LhScheduleTimeUtil.addHours(startTime, durationHours);

        MachineMaintenanceWindowDTO window = new MachineMaintenanceWindowDTO();
        window.setMachineCode(machine.getMachineCode());
        window.setPlanDate(planDate);
        window.setMaintenanceStartTime(startTime);
        window.setMaintenanceEndTime(endTime);
        window.setForceDown(forceDown);
        window.setTriggerReason(triggerReason);
        machine.getMaintenanceWindowList().add(window);
        machine.setHasMaintenancePlan(true);
        machine.setMaintenancePlanTime(planDate);
        increaseDailyMaintenanceCount(context, planDate);
        if (!sameDay(initialCandidateDate, planDate)) {
            log.info("硫化机保养日期发生顺延, 机台: {}, 原候选日期: {}, 最终日期: {}, 距到期天数: {}, 原因: {}",
                    machine.getMachineCode(),
                    LhScheduleTimeUtil.formatDate(initialCandidateDate),
                    LhScheduleTimeUtil.formatDate(planDate),
                    resolveDaysToDue(plan),
                    triggerReason);
        }
        log.info("硫化机保养窗口已安排, 机台: {}, 距到期天数: {}, 保养开始: {}, 保养结束: {}, 强制下机: {}, 原因: {}",
                machine.getMachineCode(), resolveDaysToDue(plan),
                LhScheduleTimeUtil.formatDateTime(startTime), LhScheduleTimeUtil.formatDateTime(endTime),
                forceDown, triggerReason);
        return true;
    }

    private Date resolveAvailableMaintenanceDate(LhScheduleContext context, Date candidateDate) {
        Date cursorDate = LhScheduleTimeUtil.clearTime(candidateDate);
        while (!isDateAvailable(context, cursorDate)) {
            log.info("保养日期不满足约束，顺延一天, 日期: {}, 原因: {}",
                    LhScheduleTimeUtil.formatDate(cursorDate),
                    resolveDateUnavailableReason(context, cursorDate));
            cursorDate = LhScheduleTimeUtil.addDays(cursorDate, 1);
        }
        return cursorDate;
    }

    private boolean isDateAvailable(LhScheduleContext context, Date targetDate) {
        return StringUtils.isEmpty(resolveDateUnavailableReason(context, targetDate));
    }

    private String resolveDateUnavailableReason(LhScheduleContext context, Date targetDate) {
        String dateKey = LhScheduleTimeUtil.formatDate(targetDate);
        int usedCount = context.getDailyMaintenanceCountMap().getOrDefault(dateKey, 0);
        int dailyLimit = getParamInt(context, LhScheduleParamConstant.MAINTENANCE_DAILY_LIMIT,
                LhScheduleConstant.MAINTENANCE_DAILY_LIMIT);
        if (usedCount >= dailyLimit) {
            return "当天保养台数已达上限(" + usedCount + "/" + dailyLimit + ")";
        }
        if (!isSundayAllowed(context) && isSunday(targetDate)) {
            return "周日不安排保养";
        }
        if (!isInventoryDayAllowed(context) && isLastDayOfMonth(targetDate)) {
            return "盘点日不安排保养";
        }
        if (isHolidayOrHolidayBeforeDay(context, targetDate)) {
            return "节假日前限制天数内不安排保养";
        }
        return null;
    }

    private boolean isPlanDueSoon(LhScheduleContext context, LhPrecisionPlan plan) {
        Integer daysToDue = resolveDaysToDue(plan);
        if (Objects.isNull(plan) || Objects.isNull(daysToDue) || Objects.isNull(context.getScheduleDate())) {
            return false;
        }
        int warningDays = getParamInt(context, LhScheduleParamConstant.MAINTENANCE_WARNING_DAYS,
                LhScheduleConstant.MAINTENANCE_WARNING_DAYS);
        return daysToDue <= warningDays;
    }

    /**
     * 判断机台是否满足“首个规格收尾后”触发保养的基本前提。
     *
     * @param context 排程上下文
     * @param machine 机台
     * @param lookupMachineCode 查询机台编码
     * @return true-满足触发前提；false-不满足
     */
    private boolean hasNoRecentOnlineRecord(LhScheduleContext context, String lookupMachineCode) {
        if (Objects.isNull(context) || StringUtils.isEmpty(lookupMachineCode)
                || Objects.isNull(context.getScheduleDate())) {
            return false;
        }
        LhMachineOnlineInfo onlineInfo = context.getMachineOnlineInfoMap().get(lookupMachineCode);
        if (Objects.isNull(onlineInfo)
                || Objects.isNull(onlineInfo.getOnlineDate())
                || (StringUtils.isEmpty(onlineInfo.getMaterialCode())
                && StringUtils.isEmpty(onlineInfo.getMesMaterialCode()))) {
            return true;
        }
        return diffDays(onlineInfo.getOnlineDate(), context.getScheduleDate()) > LONG_ONLINE_DAYS;
    }

    /**
     * 解析距离到期天数。
     *
     * @param plan 精度保养计划
     * @return 距离到期天数；缺失返回 null
     */
    private Integer resolveDaysToDue(LhPrecisionPlan plan) {
        if (Objects.isNull(plan)) {
            return null;
        }
        return plan.getDaysToDue();
    }

    /**
     * 判断精度保养计划是否未完成。
     *
     * @param plan 精度保养计划
     * @return true-未完成；false-已完成或计划缺失
     */
    private boolean isPlanUncompleted(LhPrecisionPlan plan) {
        return Objects.nonNull(plan) && "0".equals(plan.getCompletionStatus());
    }

    private boolean isHolidayOrHolidayBeforeDay(LhScheduleContext context, Date targetDate) {
        if (CollectionUtils.isEmpty(context.getWorkCalendarList())) {
            return false;
        }
        int blockDays = getParamInt(context, LhScheduleParamConstant.MAINTENANCE_HOLIDAY_BLOCK_DAYS,
                LhScheduleConstant.MAINTENANCE_HOLIDAY_BLOCK_DAYS);
        for (MdmWorkCalendar calendar : context.getWorkCalendarList()) {
            if (Objects.isNull(calendar) || Objects.isNull(calendar.getProductionDate())
                    || !"0".equals(calendar.getDayFlag())) {
                continue;
            }
            int daysToHoliday = diffDays(targetDate, calendar.getProductionDate());
            if (daysToHoliday >= 0 && daysToHoliday <= blockDays) {
                return true;
            }
        }
        return false;
    }

    private Date resolveMaintenanceEndTime(LhScheduleContext context,
                                           MachineScheduleDTO machine,
                                           Date referenceTime) {
        if (Objects.isNull(machine) || !machine.isHasMaintenancePlan()) {
            return null;
        }
        Date matchedEndTime = null;
        if (!CollectionUtils.isEmpty(machine.getMaintenanceWindowList())) {
            for (MachineMaintenanceWindowDTO maintenanceWindow : machine.getMaintenanceWindowList()) {
                if (Objects.isNull(maintenanceWindow)
                        || Objects.isNull(maintenanceWindow.getMaintenanceStartTime())
                        || Objects.isNull(maintenanceWindow.getMaintenanceEndTime())
                        || !maintenanceWindow.getMaintenanceStartTime().before(maintenanceWindow.getMaintenanceEndTime())) {
                    continue;
                }
                if (Objects.nonNull(referenceTime) && !referenceTime.before(maintenanceWindow.getMaintenanceEndTime())) {
                    continue;
                }
                matchedEndTime = later(matchedEndTime, maintenanceWindow.getMaintenanceEndTime());
            }
            if (Objects.nonNull(matchedEndTime)) {
                return matchedEndTime;
            }
        }
        if (Objects.isNull(machine.getMaintenancePlanTime())) {
            return null;
        }
        int maintenanceStartHour = getParamInt(context, LhScheduleParamConstant.MAINTENANCE_START_HOUR,
                LhScheduleConstant.MAINTENANCE_START_HOUR);
        int maintenanceDurationHours = getParamInt(context, LhScheduleParamConstant.MAINTENANCE_DURATION_HOURS,
                LhScheduleConstant.MAINTENANCE_DURATION_HOURS);
        Date maintenanceStartTime = LhScheduleTimeUtil.buildTime(
                machine.getMaintenancePlanTime(), maintenanceStartHour, 0, 0);
        Date maintenanceEndTime = LhScheduleTimeUtil.addHours(maintenanceStartTime, maintenanceDurationHours);
        if (Objects.nonNull(referenceTime) && !referenceTime.before(maintenanceEndTime)) {
            return null;
        }
        return maintenanceEndTime;
    }

    private void increaseDailyMaintenanceCount(LhScheduleContext context, Date planDate) {
        String dateKey = LhScheduleTimeUtil.formatDate(planDate);
        Integer usedCount = context.getDailyMaintenanceCountMap().get(dateKey);
        context.getDailyMaintenanceCountMap().put(dateKey, Objects.isNull(usedCount) ? 1 : usedCount + 1);
    }

    private boolean isBasicValid(LhScheduleContext context, MachineScheduleDTO machine) {
        return Objects.nonNull(context)
                && Objects.nonNull(machine)
                && StringUtils.isNotEmpty(machine.getMachineCode());
    }

    private boolean isSundayAllowed(LhScheduleContext context) {
        return getParamInt(context, LhScheduleParamConstant.ALLOW_MAINTENANCE_ON_SUNDAY,
                LhScheduleConstant.ALLOW_MAINTENANCE_ON_SUNDAY) == ENABLED;
    }

    private boolean isInventoryDayAllowed(LhScheduleContext context) {
        return getParamInt(context, LhScheduleParamConstant.ALLOW_MAINTENANCE_ON_INVENTORY_DAY,
                LhScheduleConstant.ALLOW_MAINTENANCE_ON_INVENTORY_DAY) == ENABLED;
    }

    private boolean isSunday(Date date) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(date);
        return calendar.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY;
    }

    private boolean isLastDayOfMonth(Date date) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(date);
        return calendar.get(Calendar.DAY_OF_MONTH) == calendar.getActualMaximum(Calendar.DAY_OF_MONTH);
    }

    private int diffDays(Date startDate, Date endDate) {
        long startTime = LhScheduleTimeUtil.clearTime(startDate).getTime();
        long endTime = LhScheduleTimeUtil.clearTime(endDate).getTime();
        return (int) ((endTime - startTime) / (24L * 60L * 60L * 1000L));
    }

    private int getParamInt(LhScheduleContext context, String paramCode, int defaultValue) {
        return context.getParamIntValue(paramCode, defaultValue);
    }

    private boolean isWindowOverlap(MachineMaintenanceWindowDTO window, Date startTime, Date endTime) {
        return Objects.nonNull(window)
                && Objects.nonNull(window.getMaintenanceStartTime())
                && Objects.nonNull(window.getMaintenanceEndTime())
                && window.getMaintenanceStartTime().before(window.getMaintenanceEndTime())
                && startTime.before(window.getMaintenanceEndTime())
                && endTime.after(window.getMaintenanceStartTime());
    }

    private Date later(Date current, Date candidate) {
        if (Objects.isNull(candidate)) {
            return current;
        }
        if (Objects.isNull(current) || candidate.after(current)) {
            return candidate;
        }
        return current;
    }

    private boolean sameDay(Date left, Date right) {
        if (Objects.isNull(left) || Objects.isNull(right)) {
            return false;
        }
        return LhScheduleTimeUtil.clearTime(left).equals(LhScheduleTimeUtil.clearTime(right));
    }
}
