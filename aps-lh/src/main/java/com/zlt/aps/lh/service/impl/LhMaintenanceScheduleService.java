package com.zlt.aps.lh.service.impl;

import com.zlt.aps.lh.api.constant.LhScheduleConstant;
import com.zlt.aps.lh.api.constant.LhScheduleParamConstant;
import com.zlt.aps.lh.api.domain.dto.MachineMaintenanceWindowDTO;
import com.zlt.aps.lh.api.domain.dto.MachineScheduleDTO;
import com.zlt.aps.lh.api.domain.entity.LhMachineOnlineInfo;
import com.zlt.aps.lh.api.domain.entity.LhPrecisionPlan;
import com.zlt.aps.lh.api.domain.vo.LhShiftConfigVO;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.util.LhSingleControlMachineUtil;
import com.zlt.aps.lh.util.LhScheduleTimeUtil;
import com.zlt.aps.lh.util.PriorityTraceLogHelper;
import com.zlt.aps.mdm.api.domain.entity.MdmWorkCalendar;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

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
    /** 每日最多保养一台物理硫化机 */
    private static final int DAILY_MAINTENANCE_LIMIT = 1;
    /** 精度保养过程日志标题 */
    private static final String MAINTENANCE_PROCESS_LOG_TITLE = "精准计划保养判断";
    /** 精度保养最终安排日志标题 */
    private static final String MAINTENANCE_FINAL_LOG_TITLE = "精准计划最终安排";

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
        LhPrecisionPlan plan = resolveMaintenancePlan(context, lookupMachineCode);
        if (!isPlanUncompleted(plan)) {
            appendMaintenanceProcessLog(context, MAINTENANCE_PROCESS_LOG_TITLE, lookupMachineCode, plan,
                    null, "未找到未完成的年度精准计划", "跳过");
            return false;
        }
        if (!isPlanDueSoon(context, plan)) {
            return false;
        }
        Date physicalEndingTime = resolvePhysicalEndingTime(context, machine, endingTime);
        if (Objects.isNull(physicalEndingTime)) {
            appendMaintenanceProcessLog(context, MAINTENANCE_PROCESS_LOG_TITLE, lookupMachineCode, plan,
                    endingTime, "单控配对侧尚未收尾", "等待自然收尾");
            return false;
        }
        appendMaintenanceProcessLog(context, MAINTENANCE_PROCESS_LOG_TITLE, lookupMachineCode, plan,
                physicalEndingTime,
                "机台第一个SKU物理机台最晚收尾=" + PriorityTraceLogHelper.formatDateTime(physicalEndingTime)
                        + "，固定保养开始=08:00",
                physicalEndingTime.after(buildMaintenanceStartTime(
                        context, LhScheduleTimeUtil.clearTime(physicalEndingTime)))
                        ? "08:00后收尾，从下一自然日开始顺延" : "当天08:00可作为候选");
        Date candidateDate = resolveNormalCandidateDate(context, physicalEndingTime);
        Date planDate = resolveAvailableMaintenanceDate(context, candidateDate, lookupMachineCode, plan);
        return attachMaintenanceWindow(context, machine, plan, planDate,
                false, TRIGGER_REASON_AFTER_ENDING);
    }

    /**
     * 长期在机到期前检查时尝试挂载强制下机保养窗口。
     *
     * @param context 排程上下文
     * @param machine 机台
     * @param predictedNaturalEndingTime 按真实班次产能预测的物理机台最晚自然收尾时间；无法证明时传 null
     * @return true-已安排保养；false-未安排保养
     */
    public boolean tryAttachLongOnlineMaintenance(LhScheduleContext context,
                                                  MachineScheduleDTO machine,
                                                  Date predictedNaturalEndingTime) {
        if (!isBasicValid(context, machine) || !CollectionUtils.isEmpty(machine.getMaintenanceWindowList())) {
            return false;
        }
        String lookupMachineCode = machine.getMachineCode();
        LhPrecisionPlan plan = resolveMaintenancePlan(context, lookupMachineCode);
        Integer daysToDue = resolveDaysToDue(context, plan);
        if (Objects.isNull(plan) || Objects.isNull(daysToDue) || Objects.isNull(context.getScheduleDate())) {
            return false;
        }
        int onlineDays = resolvePhysicalOnlineDays(context, machine);
        if (onlineDays < 0) {
            return false;
        }
        int forceCheckDays = getParamInt(context, LhScheduleParamConstant.MAINTENANCE_FORCE_CHECK_DAYS,
                LhScheduleConstant.MAINTENANCE_FORCE_CHECK_DAYS);
        if (onlineDays <= LONG_ONLINE_DAYS || daysToDue > forceCheckDays) {
            appendMaintenanceProcessLog(context, MAINTENANCE_PROCESS_LOG_TITLE, lookupMachineCode, plan,
                    null, "长期在机天数=" + onlineDays + "，提前检查天数=" + forceCheckDays,
                    "未触发强制检查");
            return false;
        }
        Date candidateDate = resolveAvailableMaintenanceDate(context,
                LhScheduleTimeUtil.clearTime(context.getScheduleDate()), lookupMachineCode, plan);
        Date candidateStartTime = buildMaintenanceStartTime(context, candidateDate);
        boolean canEndNaturally = Objects.nonNull(predictedNaturalEndingTime)
                && !predictedNaturalEndingTime.after(candidateStartTime);
        log.info("硫化机长期在机触发保养检查, 机台: {}, 在机天数: {}, 距到期天数: {}, 提前检查天数: {}, "
                        + "预测自然收尾: {}, 候选保养开始: {}, 可自然收尾: {}",
                machine.getMachineCode(), onlineDays, daysToDue, forceCheckDays,
                LhScheduleTimeUtil.formatDateTime(predictedNaturalEndingTime),
                LhScheduleTimeUtil.formatDateTime(candidateStartTime), canEndNaturally);
        appendMaintenanceProcessLog(context, MAINTENANCE_PROCESS_LOG_TITLE, lookupMachineCode, plan,
                candidateStartTime,
                "长期在机天数=" + onlineDays + "，预测自然收尾="
                        + PriorityTraceLogHelper.formatDateTime(predictedNaturalEndingTime),
                canEndNaturally ? "等待自然收尾" : "强制下机");
        if (canEndNaturally) {
            return false;
        }
        return attachMaintenanceWindow(context, machine, plan, candidateDate,
                true, TRIGGER_REASON_FORCE_DOWN);
    }

    /**
     * 判断机台是否需要执行长期在机自然收尾预测。
     * <p>调用方仅在该方法返回 true 时进行逐班次预测，避免对所有续作机台重复计算完整窗口产能。</p>
     *
     * @param context 排程上下文
     * @param machine 运行态机台
     * @return true-连续在机超过30天且已进入到期前检查窗口；false-无需检查
     */
    public boolean shouldCheckLongOnlineMaintenance(LhScheduleContext context, MachineScheduleDTO machine) {
        if (!isBasicValid(context, machine) || !CollectionUtils.isEmpty(machine.getMaintenanceWindowList())) {
            return false;
        }
        LhPrecisionPlan plan = resolveMaintenancePlan(context, machine.getMachineCode());
        Integer daysToDue = resolveDaysToDue(context, plan);
        if (!isPlanUncompleted(plan) || Objects.isNull(daysToDue)) {
            return false;
        }
        int forceCheckDays = getParamInt(context, LhScheduleParamConstant.MAINTENANCE_FORCE_CHECK_DAYS,
                LhScheduleConstant.MAINTENANCE_FORCE_CHECK_DAYS);
        return resolvePhysicalOnlineDays(context, machine) > LONG_ONLINE_DAYS
                && daysToDue <= forceCheckDays;
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
        if (Objects.isNull(defaultReadyTime)) {
            return null;
        }
        // 当前排程班次窗口内即将执行的保养必须先完成再安排后续SKU；窗口外的未来保养则只有
        // 当前就绪时刻真实落入其占用区间时才顺延，避免数周后的计划提前锁死当前机台。
        Date resumeProductionTime = resolveMaintenanceResumeTime(context, machine, defaultReadyTime);
        if (Objects.isNull(resumeProductionTime)) {
            return defaultReadyTime;
        }
        if (resumeProductionTime.after(defaultReadyTime)) {
            // 选机和产能预演可能重复计算相同就绪时刻；完全相同的调整只写一次过程日志，
            // 既保留业务对账证据，也避免同一机台产生大量重复日志。
            if (registerMaintenanceResumeDelayLog(context, machine.getMachineCode(),
                    defaultReadyTime, resumeProductionTime)) {
                appendMaintenanceProcessLog(context, MAINTENANCE_PROCESS_LOG_TITLE, machine.getMachineCode(),
                        resolveMaintenancePlan(context, machine.getMachineCode()), resumeProductionTime,
                        "保养及胶囊预热占用导致机台就绪时间顺延，原就绪="
                                + PriorityTraceLogHelper.formatDateTime(defaultReadyTime),
                        "最早开产=" + PriorityTraceLogHelper.formatDateTime(resumeProductionTime));
            }
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
     * @param context 排程上下文
     * @param machine 机台运行态
     */
    public void clearMaintenanceWindows(LhScheduleContext context, MachineScheduleDTO machine) {
        if (Objects.isNull(machine)) {
            return;
        }
        Set<String> releasedDateKeySet = new LinkedHashSet<>();
        collectMaintenanceDateKeys(machine, releasedDateKeySet);
        MachineScheduleDTO pairMachine = LhSingleControlMachineUtil.resolvePairMachine(context, machine.getMachineCode());
        collectMaintenanceDateKeys(pairMachine, releasedDateKeySet);
        int windowCount = sizeOfMaintenanceWindows(machine) + sizeOfMaintenanceWindows(pairMachine);
        if (windowCount <= 0) {
            return;
        }
        log.info("试制SKU换模与维保窗口重叠，清除物理机台维保窗口以便早班换模, 机台: {}, 配对侧: {}, 维保窗口数: {}",
                machine.getMachineCode(), Objects.nonNull(pairMachine) ? pairMachine.getMachineCode() : "-", windowCount);
        clearMachineMaintenanceState(machine);
        clearMachineMaintenanceState(pairMachine);
        releaseDailyMaintenanceQuota(context, machine.getMachineCode(), releasedDateKeySet);
        appendMaintenanceProcessLog(context, MAINTENANCE_PROCESS_LOG_TITLE, machine.getMachineCode(),
                resolveMaintenancePlan(context, machine.getMachineCode()), null,
                "试制SKU早班换模与保养窗口重叠，已同步清除物理机台L/R窗口并释放每日额度",
                "等待后续重新安排");
    }

    private boolean attachMaintenanceWindow(LhScheduleContext context,
                                            MachineScheduleDTO machine,
                                            LhPrecisionPlan plan,
                                            Date planDate,
                                            boolean forceDown,
                                            String triggerReason) {
        int durationHours = getParamInt(context, LhScheduleParamConstant.MAINTENANCE_DURATION_HOURS,
                LhScheduleConstant.MAINTENANCE_DURATION_HOURS);
        Date startTime = buildMaintenanceStartTime(context, planDate);
        Date endTime = LhScheduleTimeUtil.addHours(startTime, durationHours);
        Date productionResumeTime = LhScheduleTimeUtil.addMinutes(
                endTime, LhScheduleTimeUtil.getCapsulePreheatMinutes(context));

        // 单控 L/R 属于同一物理机台：任一侧触发时两侧同步挂载相同占用窗口，
        // 但每一侧窗口保留各自精准计划主键，排程完成后分别回填 SCHEDULE_DATE。
        attachMaintenanceWindowToMachine(context, machine, plan, planDate, startTime, endTime,
                productionResumeTime, forceDown, triggerReason);
        MachineScheduleDTO pairMachine = LhSingleControlMachineUtil.resolvePairMachine(context, machine.getMachineCode());
        if (Objects.nonNull(pairMachine)) {
            LhPrecisionPlan pairPlan = context.getMaintenancePlanMap().get(pairMachine.getMachineCode());
            attachMaintenanceWindowToMachine(context, pairMachine, pairPlan, planDate, startTime, endTime,
                    productionResumeTime, forceDown, triggerReason);
        }
        increaseDailyMaintenanceCount(context, planDate, machine.getMachineCode());
        log.info("硫化机保养窗口已安排, 机台: {}, 配对侧: {}, 保养类型: {}, 计划到期: {}, 距到期天数: {}, "
                        + "保养开始: {}, 保养结束: {}, 预热完成及最早开产: {}, 强制下机: {}, 原因: {}",
                machine.getMachineCode(), Objects.nonNull(pairMachine) ? pairMachine.getMachineCode() : "-",
                plan.getPrecisionType(), LhScheduleTimeUtil.formatDate(resolvePlanDueDate(context, plan)),
                resolveDaysToDue(context, plan), LhScheduleTimeUtil.formatDateTime(startTime),
                LhScheduleTimeUtil.formatDateTime(endTime), LhScheduleTimeUtil.formatDateTime(productionResumeTime),
                forceDown, triggerReason);
        appendMaintenanceProcessLog(context, MAINTENANCE_FINAL_LOG_TITLE, machine.getMachineCode(), plan,
                startTime,
                "保养结束=" + PriorityTraceLogHelper.formatDateTime(endTime)
                        + "，预热完成及最早开产=" + PriorityTraceLogHelper.formatDateTime(productionResumeTime)
                        + "，物理机台=" + LhSingleControlMachineUtil.resolvePhysicalMachineCode(machine.getMachineCode()),
                        (forceDown ? "强制下机" : "自然收尾后保养") + "，原因=" + triggerReason);
        return true;
    }

    /**
     * 为单侧运行态机台挂载精度保养窗口。
     * <p>保养窗口保留真实保养结束时间，胶囊预热完成时间单独保存为最早开产时间，
     * 便于结果摘要展示 08:00～15:00，同时让产能计算完整阻断至默认 17:30。</p>
     *
     * @param context 排程上下文
     * @param targetMachine 目标运行态机台
     * @param targetPlan 目标机台年度精准计划，历史数据缺失时允许为空
     * @param planDate 最终保养日期
     * @param startTime 保养开始时间
     * @param endTime 保养结束时间
     * @param productionResumeTime 预热完成及最早开产时间
     * @param forceDown 是否强制下机
     * @param triggerReason 触发原因
     */
    private void attachMaintenanceWindowToMachine(LhScheduleContext context,
                                                   MachineScheduleDTO targetMachine,
                                                   LhPrecisionPlan targetPlan,
                                                   Date planDate,
                                                   Date startTime,
                                                   Date endTime,
                                                   Date productionResumeTime,
                                                   boolean forceDown,
                                                   String triggerReason) {
        if (Objects.isNull(targetMachine) || !CollectionUtils.isEmpty(targetMachine.getMaintenanceWindowList())) {
            return;
        }
        MachineMaintenanceWindowDTO window = new MachineMaintenanceWindowDTO();
        window.setPrecisionPlanId(Objects.nonNull(targetPlan) ? targetPlan.getId() : null);
        window.setMachineCode(targetMachine.getMachineCode());
        window.setMaintenanceType(Objects.nonNull(targetPlan) ? targetPlan.getPrecisionType() : null);
        window.setSourcePlanDate(Objects.nonNull(targetPlan) ? targetPlan.getPlanDate() : null);
        window.setDueDate(resolvePlanDueDate(context, targetPlan));
        window.setDaysToDue(resolveDaysToDue(context, targetPlan));
        window.setPlanDate(planDate);
        window.setMaintenanceStartTime(startTime);
        window.setMaintenanceEndTime(endTime);
        window.setProductionResumeTime(productionResumeTime);
        window.setForceDown(forceDown);
        window.setTriggerReason(triggerReason);
        targetMachine.getMaintenanceWindowList().add(window);
        targetMachine.setHasMaintenancePlan(true);
        targetMachine.setMaintenancePlanTime(planDate);
    }

    /**
     * 收集机台已安排保养窗口的日期键。
     *
     * @param machine 运行态机台
     * @param dateKeySet 日期键集合
     */
    private void collectMaintenanceDateKeys(MachineScheduleDTO machine, Set<String> dateKeySet) {
        if (Objects.isNull(machine) || Objects.isNull(dateKeySet)
                || CollectionUtils.isEmpty(machine.getMaintenanceWindowList())) {
            return;
        }
        for (MachineMaintenanceWindowDTO window : machine.getMaintenanceWindowList()) {
            if (Objects.nonNull(window) && Objects.nonNull(window.getPlanDate())) {
                dateKeySet.add(LhScheduleTimeUtil.formatDate(window.getPlanDate()));
            }
        }
    }

    /**
     * 计算机台保养窗口数量。
     *
     * @param machine 运行态机台
     * @return 保养窗口数量
     */
    private int sizeOfMaintenanceWindows(MachineScheduleDTO machine) {
        return Objects.isNull(machine) || CollectionUtils.isEmpty(machine.getMaintenanceWindowList())
                ? 0 : machine.getMaintenanceWindowList().size();
    }

    /**
     * 清除单侧运行态机台保养状态。
     *
     * @param machine 运行态机台
     */
    private void clearMachineMaintenanceState(MachineScheduleDTO machine) {
        if (Objects.isNull(machine)) {
            return;
        }
        machine.getMaintenanceWindowList().clear();
        machine.setHasMaintenancePlan(false);
        machine.setMaintenancePlanTime(null);
    }

    /**
     * 释放被清除保养窗口占用的每日物理机台额度。
     *
     * @param context 排程上下文
     * @param machineCode 任一侧运行态机台编码
     * @param dateKeySet 待释放日期键集合
     */
    private void releaseDailyMaintenanceQuota(LhScheduleContext context,
                                              String machineCode,
                                              Set<String> dateKeySet) {
        if (Objects.isNull(context) || CollectionUtils.isEmpty(dateKeySet)) {
            return;
        }
        String physicalMachineCode = LhSingleControlMachineUtil.resolvePhysicalMachineCode(machineCode);
        for (String dateKey : dateKeySet) {
            Set<String> occupiedMachineSet = context.getDailyMaintenancePhysicalMachineSetMap().get(dateKey);
            if (CollectionUtils.isEmpty(occupiedMachineSet)) {
                context.getDailyMaintenanceCountMap().remove(dateKey);
                continue;
            }
            occupiedMachineSet.remove(physicalMachineCode);
            if (CollectionUtils.isEmpty(occupiedMachineSet)) {
                context.getDailyMaintenancePhysicalMachineSetMap().remove(dateKey);
                context.getDailyMaintenanceCountMap().remove(dateKey);
            } else {
                context.getDailyMaintenanceCountMap().put(dateKey, occupiedMachineSet.size());
            }
        }
    }

    private Date resolveAvailableMaintenanceDate(LhScheduleContext context,
                                                 Date candidateDate,
                                                 String machineCode,
                                                 LhPrecisionPlan plan) {
        Date cursorDate = LhScheduleTimeUtil.clearTime(candidateDate);
        while (true) {
            String unavailableReason = resolveDateUnavailableReason(context, cursorDate);
            if (StringUtils.isEmpty(unavailableReason)) {
                String dateKey = LhScheduleTimeUtil.formatDate(cursorDate);
                StringBuilder ruleBuilder = new StringBuilder(128);
                ruleBuilder.append("当天保养物理机台数=")
                        .append(resolveDailyMaintenanceCount(context, dateKey))
                        .append("/").append(DAILY_MAINTENANCE_LIMIT)
                        .append("，周日命中=").append(isSunday(cursorDate))
                        .append("，盘点日命中=").append(isLastDayOfMonth(cursorDate))
                        .append("，节假日前限制命中=")
                        .append(isHolidayOrHolidayBeforeDay(context, cursorDate));
                appendMaintenanceProcessLog(context, MAINTENANCE_PROCESS_LOG_TITLE, machineCode, plan,
                        cursorDate, ruleBuilder.toString(), "日期可用");
                return cursorDate;
            }
            log.info("保养日期不满足约束，顺延一天, 日期: {}, 原因: {}",
                    LhScheduleTimeUtil.formatDate(cursorDate),
                    unavailableReason);
            appendMaintenanceProcessLog(context, MAINTENANCE_PROCESS_LOG_TITLE, machineCode, plan,
                    cursorDate, unavailableReason, "顺延一天");
            cursorDate = LhScheduleTimeUtil.addDays(cursorDate, 1);
        }
    }

    private String resolveDateUnavailableReason(LhScheduleContext context, Date targetDate) {
        String dateKey = LhScheduleTimeUtil.formatDate(targetDate);
        int usedCount = resolveDailyMaintenanceCount(context, dateKey);
        if (usedCount >= DAILY_MAINTENANCE_LIMIT) {
            return "当天保养台数已达上限(" + usedCount + "/" + DAILY_MAINTENANCE_LIMIT + ")";
        }
        if (!isSundayAllowed(context) && isSunday(targetDate)) {
            return "周日不安排保养";
        }
        if (isLastDayOfMonth(targetDate)) {
            return "盘点日不安排保养";
        }
        if (isHolidayOrHolidayBeforeDay(context, targetDate)) {
            return "节假日前限制天数内不安排保养";
        }
        return null;
    }

    private boolean isPlanDueSoon(LhScheduleContext context, LhPrecisionPlan plan) {
        Integer daysToDue = resolveDaysToDue(context, plan);
        if (Objects.isNull(plan) || Objects.isNull(daysToDue) || Objects.isNull(context.getScheduleDate())) {
            return false;
        }
        int warningDays = getParamInt(context, LhScheduleParamConstant.MAINTENANCE_WARNING_DAYS,
                LhScheduleConstant.MAINTENANCE_WARNING_DAYS);
        boolean dueSoon = daysToDue <= warningDays;
        appendMaintenanceProcessLog(context, MAINTENANCE_PROCESS_LOG_TITLE, plan.getMachineCode(), plan,
                null, "预警阈值=" + warningDays + "，是否进入预警范围=" + dueSoon,
                dueSoon ? "进入30天预警范围" : "未进入预警范围");
        return dueSoon;
    }

    /**
     * 解析机台对应的未完成精准计划。
     * <p>单控 L/R 两侧原则上各自维护年度计划。历史数据若暂时只维护一侧，允许读取配对侧计划完成
     * 本次排程判断，但回填仍只会处理窗口中携带的真实计划主键，不会伪造计划。</p>
     *
     * @param context 排程上下文
     * @param machineCode 运行态机台编码
     * @return 精度保养计划；不存在时返回 null
     */
    private LhPrecisionPlan resolveMaintenancePlan(LhScheduleContext context, String machineCode) {
        if (Objects.isNull(context) || StringUtils.isEmpty(machineCode)
                || CollectionUtils.isEmpty(context.getMaintenancePlanMap())) {
            return null;
        }
        LhPrecisionPlan plan = context.getMaintenancePlanMap().get(machineCode);
        if (Objects.nonNull(plan)) {
            return plan;
        }
        String pairMachineCode = LhSingleControlMachineUtil.resolvePairMachineCode(machineCode);
        return StringUtils.isEmpty(pairMachineCode)
                ? null : context.getMaintenancePlanMap().get(pairMachineCode);
    }

    /**
     * 解析计划有效到期日。
     * <p>数据库历史数据允许部分日期字段为空，统一按 DUE_DATE、PLAN_DATE、排程日加静态到期天数的顺序解析。
     * 该顺序只用于解释已有业务字段，不生成或修改年度计划。</p>
     *
     * @param context 排程上下文
     * @param plan 精度保养计划
     * @return 有效到期日；无法解析时返回 null
     */
    private Date resolvePlanDueDate(LhScheduleContext context, LhPrecisionPlan plan) {
        if (Objects.isNull(plan)) {
            return null;
        }
        if (Objects.nonNull(plan.getDueDate())) {
            return plan.getDueDate();
        }
        if (Objects.nonNull(plan.getPlanDate())) {
            return plan.getPlanDate();
        }
        if (Objects.nonNull(context) && Objects.nonNull(context.getScheduleDate())
                && Objects.nonNull(plan.getDaysToDue())) {
            return LhScheduleTimeUtil.addDays(context.getScheduleDate(), plan.getDaysToDue());
        }
        return null;
    }

    /**
     * 按本次排程日实时计算距离到期天数。
     * <p>优先根据有效到期日计算，避免历史复跑继续使用数据库中相对当前日期维护的静态天数；
     * 仅当所有日期字段均为空时保留原 DAYS_TO_DUE 口径。</p>
     *
     * @param context 排程上下文
     * @param plan 精度保养计划
     * @return 距离到期天数；缺失返回 null
     */
    private Integer resolveDaysToDue(LhScheduleContext context, LhPrecisionPlan plan) {
        if (Objects.isNull(plan)) {
            return null;
        }
        Date dueDate = resolvePlanDueDate(context, plan);
        if (Objects.nonNull(dueDate) && Objects.nonNull(context)
                && Objects.nonNull(context.getScheduleDate())) {
            return diffDays(context.getScheduleDate(), dueDate);
        }
        return plan.getDaysToDue();
    }

    /**
     * 根据首个规格真实收尾时间解析正常保养候选日。
     * <p>保养固定在 08:00 开始：收尾不晚于固定开始时间时可使用当天；晚于固定开始时间时，
     * 必须从下一自然日开始寻找可用日期，禁止把保养提前到前规格仍在生产的时段。</p>
     *
     * @param context 排程上下文
     * @param endingTime 物理机台最晚收尾时间
     * @return 正常保养候选日零点
     */
    private Date resolveNormalCandidateDate(LhScheduleContext context, Date endingTime) {
        Date endingDate = LhScheduleTimeUtil.clearTime(endingTime);
        Date fixedStartTime = buildMaintenanceStartTime(context, endingDate);
        return endingTime.after(fixedStartTime)
                ? LhScheduleTimeUtil.addDays(endingDate, 1) : endingDate;
    }

    /**
     * 构建保养固定开始时间。
     *
     * @param context 排程上下文
     * @param planDate 最终保养日期
     * @return 固定开始时间，默认当天 08:00
     */
    private Date buildMaintenanceStartTime(LhScheduleContext context, Date planDate) {
        int startHour = getParamInt(context, LhScheduleParamConstant.MAINTENANCE_START_HOUR,
                LhScheduleConstant.MAINTENANCE_START_HOUR);
        if (startHour > 23) {
            log.warn("硫化保养固定开始小时参数超出0～23，使用默认值, paramCode: {}, rawValue: {}, defaultValue: {}",
                    LhScheduleParamConstant.MAINTENANCE_START_HOUR, startHour,
                    LhScheduleConstant.MAINTENANCE_START_HOUR);
            startHour = LhScheduleConstant.MAINTENANCE_START_HOUR;
        }
        return LhScheduleTimeUtil.buildTime(planDate, startHour, 0, 0);
    }

    /**
     * 解析单控物理机台的最晚自然收尾时间。
     * <p>L/R 视为同一物理机台。配对侧仍有在产物料但没有明确收尾结果时，本侧不能单独安排正常保养；
     * 两侧均已收尾或配对侧空闲时，以两侧最晚收尾时间作为候选日期依据。</p>
     *
     * @param context 排程上下文
     * @param machine 当前运行态机台
     * @param endingTime 当前侧收尾时间
     * @return 物理机台最晚收尾时间；配对侧尚未收尾时返回 null
     */
    private Date resolvePhysicalEndingTime(LhScheduleContext context,
                                           MachineScheduleDTO machine,
                                           Date endingTime) {
        MachineScheduleDTO pairMachine = LhSingleControlMachineUtil.resolvePairMachine(context,
                machine.getMachineCode());
        if (Objects.isNull(pairMachine)) {
            return endingTime;
        }
        boolean pairActive = StringUtils.isNotEmpty(pairMachine.getCurrentMaterialCode());
        if (pairActive && !pairMachine.isEnding()) {
            return null;
        }
        if (pairActive && Objects.isNull(pairMachine.getEstimatedEndTime())) {
            return null;
        }
        return later(endingTime, pairMachine.getEstimatedEndTime());
    }

    /**
     * 解析单控物理机台连续在机天数。
     * <p>任一侧超过阈值即按整台物理机进入长期在机检查，避免仅检查触发侧而遗漏配对侧。</p>
     *
     * @param context 排程上下文
     * @param machine 当前运行态机台
     * @return 物理机台最大连续在机天数；缺少有效在机日期时返回 -1
     */
    private int resolvePhysicalOnlineDays(LhScheduleContext context, MachineScheduleDTO machine) {
        int currentOnlineDays = resolveOnlineDays(context, machine);
        MachineScheduleDTO pairMachine = LhSingleControlMachineUtil.resolvePairMachine(context,
                machine.getMachineCode());
        int pairOnlineDays = resolveOnlineDays(context, pairMachine);
        return Math.max(currentOnlineDays, pairOnlineDays);
    }

    /**
     * 解析单侧运行态机台连续在机天数。
     *
     * @param context 排程上下文
     * @param machine 运行态机台
     * @return 连续在机天数；无有效在机记录返回 -1
     */
    private int resolveOnlineDays(LhScheduleContext context, MachineScheduleDTO machine) {
        if (Objects.isNull(context) || Objects.isNull(machine) || Objects.isNull(context.getScheduleDate())) {
            return -1;
        }
        LhMachineOnlineInfo onlineInfo = context.getMachineOnlineInfoMap().get(machine.getMachineCode());
        if (Objects.isNull(onlineInfo) || Objects.isNull(onlineInfo.getOnlineDate())) {
            return -1;
        }
        return diffDays(onlineInfo.getOnlineDate(), context.getScheduleDate());
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
                // 带参考时间的查询只匹配参考时刻真实落入的保养窗口，不能把未来保养误判为当前重叠。
                if (Objects.nonNull(referenceTime)
                        && (referenceTime.before(maintenanceWindow.getMaintenanceStartTime())
                        || !referenceTime.before(maintenanceWindow.getMaintenanceEndTime()))) {
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
        if (Objects.nonNull(referenceTime)
                && (referenceTime.before(maintenanceStartTime) || !referenceTime.before(maintenanceEndTime))) {
            return null;
        }
        return maintenanceEndTime;
    }

    /**
     * 解析保养及胶囊预热全部结束后的最晚恢复生产时间。
     * <p>该时间专用于产能和后续 SKU 就绪判断，不改变保养摘要展示的真实保养结束时间。</p>
     *
     * @param context 排程上下文
     * @param machine 运行态机台
     * @param referenceTime 机台当前就绪时间
     * @return 当前班次范围内待执行保养，或参考时间落入未来保养占用区间时的恢复时间；未命中返回 null
     */
    private Date resolveMaintenanceResumeTime(LhScheduleContext context,
                                              MachineScheduleDTO machine,
                                              Date referenceTime) {
        if (Objects.isNull(machine) || !machine.isHasMaintenancePlan() || Objects.isNull(referenceTime)) {
            return null;
        }
        Date matchedResumeTime = null;
        if (!CollectionUtils.isEmpty(machine.getMaintenanceWindowList())) {
            for (MachineMaintenanceWindowDTO maintenanceWindow : machine.getMaintenanceWindowList()) {
                if (Objects.isNull(maintenanceWindow)
                        || Objects.isNull(maintenanceWindow.getMaintenanceStartTime())
                        || Objects.isNull(maintenanceWindow.getMaintenanceEndTime())) {
                    continue;
                }
                Date resumeTime = maintenanceWindow.getProductionResumeTime();
                if (Objects.isNull(resumeTime)) {
                    resumeTime = LhScheduleTimeUtil.addMinutes(maintenanceWindow.getMaintenanceEndTime(),
                            LhScheduleTimeUtil.getCapsulePreheatMinutes(context));
                }
                if (!shouldDelayReadyTimeByMaintenance(context, referenceTime,
                        maintenanceWindow.getMaintenanceStartTime(), resumeTime)) {
                    continue;
                }
                matchedResumeTime = later(matchedResumeTime, resumeTime);
            }
            if (Objects.nonNull(matchedResumeTime)) {
                return matchedResumeTime;
            }
        }
        if (Objects.isNull(machine.getMaintenancePlanTime())) {
            return null;
        }
        Date maintenanceStartTime = buildMaintenanceStartTime(context, machine.getMaintenancePlanTime());
        int durationHours = getParamInt(context, LhScheduleParamConstant.MAINTENANCE_DURATION_HOURS,
                LhScheduleConstant.MAINTENANCE_DURATION_HOURS);
        Date maintenanceEndTime = LhScheduleTimeUtil.addHours(maintenanceStartTime, durationHours);
        Date resumeTime = LhScheduleTimeUtil.addMinutes(maintenanceEndTime,
                LhScheduleTimeUtil.getCapsulePreheatMinutes(context));
        if (!shouldDelayReadyTimeByMaintenance(
                context, referenceTime, maintenanceStartTime, resumeTime)) {
            return null;
        }
        return resumeTime;
    }

    /**
     * 判断保养窗口是否必须顺延当前机台就绪时间。
     * <p>当前固定班次范围内的保养已经是本批必须执行的时间轴任务，后续 SKU 即使在08:00前具备
     * 就绪条件，也必须等待保养及预热完成；超出本批班次范围的未来保养仍允许机台在保养前生产，
     * 仅当参考时刻真实落入占用区间时才顺延。</p>
     *
     * @param context 排程上下文
     * @param referenceTime 当前机台就绪时间
     * @param maintenanceStartTime 保养开始时间
     * @param resumeProductionTime 胶囊预热完成及最早开产时间
     * @return true-需要顺延到预热完成；false-保持原就绪时间
     */
    private boolean shouldDelayReadyTimeByMaintenance(LhScheduleContext context,
                                                      Date referenceTime,
                                                      Date maintenanceStartTime,
                                                      Date resumeProductionTime) {
        if (isMaintenanceOccupationWithinScheduleShifts(
                context, maintenanceStartTime, resumeProductionTime)) {
            return Objects.nonNull(referenceTime) && referenceTime.before(resumeProductionTime);
        }
        return isTimeWithinMaintenanceOccupation(
                referenceTime, maintenanceStartTime, resumeProductionTime);
    }

    /**
     * 判断保养及预热占用区间是否与本批固定标准班次相交。
     *
     * @param context 排程上下文
     * @param maintenanceStartTime 保养开始时间
     * @param resumeProductionTime 胶囊预热完成及最早开产时间
     * @return true-已进入当前排程班次范围；false-属于窗口外未来保养或时间无效
     */
    private boolean isMaintenanceOccupationWithinScheduleShifts(LhScheduleContext context,
                                                                Date maintenanceStartTime,
                                                                Date resumeProductionTime) {
        if (Objects.isNull(context)
                || CollectionUtils.isEmpty(context.getScheduleWindowShifts())
                || Objects.isNull(maintenanceStartTime)
                || Objects.isNull(resumeProductionTime)
                || !maintenanceStartTime.before(resumeProductionTime)) {
            return false;
        }
        for (LhShiftConfigVO shift : context.getScheduleWindowShifts()) {
            if (Objects.isNull(shift)
                    || Objects.isNull(shift.getShiftStartDateTime())
                    || Objects.isNull(shift.getShiftEndDateTime())) {
                continue;
            }
            if (maintenanceStartTime.before(shift.getShiftEndDateTime())
                    && resumeProductionTime.after(shift.getShiftStartDateTime())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断参考时刻是否处于保养和胶囊预热形成的完整不可生产区间。
     * <p>区间采用左闭右开口径：等于保养开始时必须等待，等于预热完成时可以立即恢复生产。</p>
     *
     * @param referenceTime 参考时刻
     * @param maintenanceStartTime 保养开始时刻
     * @param resumeProductionTime 胶囊预热完成及最早开产时刻
     * @return true-处于不可生产区间；false-保养前、预热完成后或时间数据无效
     */
    private boolean isTimeWithinMaintenanceOccupation(Date referenceTime,
                                                       Date maintenanceStartTime,
                                                       Date resumeProductionTime) {
        return Objects.nonNull(referenceTime)
                && Objects.nonNull(maintenanceStartTime)
                && Objects.nonNull(resumeProductionTime)
                && maintenanceStartTime.before(resumeProductionTime)
                && !referenceTime.before(maintenanceStartTime)
                && referenceTime.before(resumeProductionTime);
    }

    /**
     * 登记一次真实发生的保养就绪时间顺延日志。
     *
     * @param context 排程上下文
     * @param machineCode 机台编码
     * @param originalReadyTime 原就绪时间
     * @param resumeProductionTime 预热完成及最早开产时间
     * @return true-本组合首次登记，应写日志；false-相同日志已记录
     */
    private boolean registerMaintenanceResumeDelayLog(LhScheduleContext context,
                                                      String machineCode,
                                                      Date originalReadyTime,
                                                      Date resumeProductionTime) {
        if (Objects.isNull(context)) {
            return true;
        }
        String logKey = new StringBuilder(64)
                .append(machineCode).append('|')
                .append(originalReadyTime.getTime()).append('|')
                .append(resumeProductionTime.getTime())
                .toString();
        return context.getMaintenanceResumeDelayLogKeySet().add(logKey);
    }

    /**
     * 按物理机台增加每日保养占用数。
     * <p>单控 L/R 两个运行态窗口只登记一个物理机台编码，保证每日额度只计一次。</p>
     *
     * @param context 排程上下文
     * @param planDate 最终保养日期
     * @param machineCode 任一侧运行态机台编码
     */
    private void increaseDailyMaintenanceCount(LhScheduleContext context,
                                               Date planDate,
                                               String machineCode) {
        String dateKey = LhScheduleTimeUtil.formatDate(planDate);
        Set<String> occupiedMachineSet = context.getDailyMaintenancePhysicalMachineSetMap()
                .computeIfAbsent(dateKey, key -> new LinkedHashSet<>());
        occupiedMachineSet.add(LhSingleControlMachineUtil.resolvePhysicalMachineCode(machineCode));
        context.getDailyMaintenanceCountMap().put(dateKey, occupiedMachineSet.size());
    }

    /**
     * 解析指定日期已占用的物理机台保养数量。
     * <p>兼容历史测试或旧初始化逻辑仅写入 countMap 的场景，最终取两种运行态口径的较大值。</p>
     *
     * @param context 排程上下文
     * @param dateKey 日期键
     * @return 已占用物理机台数量
     */
    private int resolveDailyMaintenanceCount(LhScheduleContext context, String dateKey) {
        Integer legacyCount = context.getDailyMaintenanceCountMap().get(dateKey);
        Set<String> occupiedMachineSet = context.getDailyMaintenancePhysicalMachineSetMap().get(dateKey);
        int physicalCount = CollectionUtils.isEmpty(occupiedMachineSet) ? 0 : occupiedMachineSet.size();
        return Math.max(Objects.isNull(legacyCount) ? 0 : legacyCount, physicalCount);
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
        if (Objects.isNull(context)) {
            return defaultValue;
        }
        String rawValue = context.getParamValue(paramCode, StringUtils.EMPTY);
        if (StringUtils.isEmpty(rawValue)) {
            log.warn("硫化保养参数为空，使用业务默认值, paramCode: {}, defaultValue: {}",
                    paramCode, defaultValue);
            return defaultValue;
        }
        try {
            int value = Integer.parseInt(rawValue.trim());
            if (value >= 0) {
                return value;
            }
        } catch (NumberFormatException ignored) {
            // 参数异常统一在下方记录一次告警并返回业务默认值。
        }
        log.warn("硫化保养参数为空、非法或为负数，使用业务默认值, paramCode: {}, rawValue: {}, defaultValue: {}",
                paramCode, rawValue, defaultValue);
        return defaultValue;
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

    /**
     * 写入可对账的精准计划排程过程日志。
     *
     * @param context 排程上下文
     * @param title 日志标题
     * @param machineCode 运行态机台编码
     * @param plan 精度保养计划
     * @param candidateDate 候选或最终保养时间
     * @param hitRule 命中规则及判断明细
     * @param result 最终处理结果
     */
    private void appendMaintenanceProcessLog(LhScheduleContext context,
                                             String title,
                                             String machineCode,
                                             LhPrecisionPlan plan,
                                             Date candidateDate,
                                             String hitRule,
                                             String result) {
        if (Objects.isNull(context)) {
            return;
        }
        StringBuilder detailBuilder = new StringBuilder(256);
        detailBuilder.append("机台=").append(PriorityTraceLogHelper.safeText(machineCode))
                .append("，物理机台=")
                .append(PriorityTraceLogHelper.safeText(
                        LhSingleControlMachineUtil.resolvePhysicalMachineCode(machineCode)))
                .append("，保养类型=")
                .append(PriorityTraceLogHelper.safeText(
                        Objects.nonNull(plan) ? plan.getPrecisionType() : null))
                .append("，计划到期=")
                .append(PriorityTraceLogHelper.formatDateTime(resolvePlanDueDate(context, plan)))
                .append("，距到期天数=")
                .append(PriorityTraceLogHelper.safeText(resolveDaysToDue(context, plan)))
                .append("，候选时间=")
                .append(PriorityTraceLogHelper.formatDateTime(candidateDate))
                .append("，命中规则=").append(PriorityTraceLogHelper.safeText(hitRule))
                .append("，处理结果=").append(PriorityTraceLogHelper.safeText(result));
        PriorityTraceLogHelper.appendProcessLog(context, title, detailBuilder.toString());
    }
}
