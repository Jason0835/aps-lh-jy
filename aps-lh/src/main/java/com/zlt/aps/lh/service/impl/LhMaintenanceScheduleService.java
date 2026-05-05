package com.zlt.aps.lh.service.impl;

import com.zlt.aps.lh.api.constant.LhScheduleConstant;
import com.zlt.aps.lh.api.constant.LhScheduleParamConstant;
import com.zlt.aps.lh.api.domain.dto.MachineMaintenanceWindowDTO;
import com.zlt.aps.lh.api.domain.dto.MachineScheduleDTO;
import com.zlt.aps.lh.api.domain.entity.LhMachineOnlineInfo;
import com.zlt.aps.lh.api.domain.entity.LhPrecisionPlan;
import com.zlt.aps.lh.context.LhScheduleContext;
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
        LhPrecisionPlan plan = context.getMaintenancePlanMap().get(machine.getMachineCode());
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
        LhPrecisionPlan plan = context.getMaintenancePlanMap().get(machine.getMachineCode());
        if (Objects.isNull(plan) || Objects.isNull(plan.getDueDate()) || Objects.isNull(context.getScheduleDate())) {
            return false;
        }
        LhMachineOnlineInfo onlineInfo = context.getMachineOnlineInfoMap().get(machine.getMachineCode());
        if (Objects.isNull(onlineInfo) || Objects.isNull(onlineInfo.getOnlineDate())) {
            return false;
        }
        int onlineDays = diffDays(onlineInfo.getOnlineDate(), context.getScheduleDate());
        int dueDays = diffDays(context.getScheduleDate(), plan.getDueDate());
        int forceCheckDays = getParamInt(context, LhScheduleParamConstant.MAINTENANCE_FORCE_CHECK_DAYS,
                LhScheduleConstant.MAINTENANCE_FORCE_CHECK_DAYS);
        if (onlineDays <= LONG_ONLINE_DAYS || dueDays > forceCheckDays) {
            return false;
        }
        log.info("硫化机长期在机触发保养检查, 机台: {}, 在机日期: {}, 在机天数: {}, 到期日: {}, 提前检查天数: {}",
                machine.getMachineCode(), LhScheduleTimeUtil.formatDate(onlineInfo.getOnlineDate()), onlineDays,
                LhScheduleTimeUtil.formatDate(plan.getDueDate()), forceCheckDays);
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

    private boolean attachMaintenanceWindow(LhScheduleContext context,
                                            MachineScheduleDTO machine,
                                            LhPrecisionPlan plan,
                                            Date candidateDate,
                                            boolean forceDown,
                                            String triggerReason) {
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
        log.info("硫化机保养窗口已安排, 机台: {}, 到期日: {}, 保养开始: {}, 保养结束: {}, 强制下机: {}, 原因: {}",
                machine.getMachineCode(), LhScheduleTimeUtil.formatDate(plan.getDueDate()),
                LhScheduleTimeUtil.formatDateTime(startTime), LhScheduleTimeUtil.formatDateTime(endTime),
                forceDown, triggerReason);
        return true;
    }

    private Date resolveAvailableMaintenanceDate(LhScheduleContext context, Date candidateDate) {
        Date cursorDate = LhScheduleTimeUtil.clearTime(candidateDate);
        while (!isDateAvailable(context, cursorDate)) {
            log.debug("保养日期不满足约束，顺延一天, 日期: {}", LhScheduleTimeUtil.formatDate(cursorDate));
            cursorDate = LhScheduleTimeUtil.addDays(cursorDate, 1);
        }
        return cursorDate;
    }

    private boolean isDateAvailable(LhScheduleContext context, Date targetDate) {
        String dateKey = LhScheduleTimeUtil.formatDate(targetDate);
        int usedCount = context.getDailyMaintenanceCountMap().getOrDefault(dateKey, 0);
        int dailyLimit = getParamInt(context, LhScheduleParamConstant.MAINTENANCE_DAILY_LIMIT,
                LhScheduleConstant.MAINTENANCE_DAILY_LIMIT);
        if (usedCount >= dailyLimit) {
            return false;
        }
        if (!isSundayAllowed(context) && isSunday(targetDate)) {
            return false;
        }
        if (!isInventoryDayAllowed(context) && isLastDayOfMonth(targetDate)) {
            return false;
        }
        if (isHolidayOrHolidayBeforeDay(context, targetDate)) {
            return false;
        }
        return true;
    }

    private boolean isPlanDueSoon(LhScheduleContext context, LhPrecisionPlan plan) {
        if (Objects.isNull(plan) || Objects.isNull(plan.getDueDate()) || Objects.isNull(context.getScheduleDate())) {
            return false;
        }
        int warningDays = getParamInt(context, LhScheduleParamConstant.MAINTENANCE_WARNING_DAYS,
                LhScheduleConstant.MAINTENANCE_WARNING_DAYS);
        return diffDays(context.getScheduleDate(), plan.getDueDate()) <= warningDays;
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
}
