package com.zlt.aps.lh.service.impl;

import com.zlt.aps.lh.api.constant.LhScheduleConstant;
import com.zlt.aps.lh.api.constant.LhScheduleParamConstant;
import com.zlt.aps.lh.api.domain.dto.MachineMaintenanceWindowDTO;
import com.zlt.aps.lh.api.domain.dto.MachineCleaningWindowDTO;
import com.zlt.aps.lh.api.domain.dto.MachineScheduleDTO;
import com.zlt.aps.lh.api.domain.dto.SkuScheduleDTO;
import com.zlt.aps.lh.api.domain.entity.LhScheduleResult;
import com.zlt.aps.lh.api.domain.entity.LhMachineOnlineInfo;
import com.zlt.aps.lh.api.domain.entity.LhPrecisionPlan;
import com.zlt.aps.lh.api.domain.vo.LhShiftConfigVO;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.util.LhSingleControlMachineUtil;
import com.zlt.aps.lh.util.LhScheduleTimeUtil;
import com.zlt.aps.lh.util.PriorityTraceLogHelper;
import com.zlt.aps.mdm.api.domain.entity.MdmWorkCalendar;
import com.zlt.aps.mdm.api.domain.entity.MdmDevicePlanShut;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.util.Calendar;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
    /** 3天内精度计划强制下机触发原因 */
    public static final String TRIGGER_REASON_FORCE_DOWN = "精度计划到期强制下机";
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
     * 在续作排产前按业务优先级统一预留精度计划窗口。
     *
     * <p>计划日期不早于排程T日是进入本轮调度的首要前提，确保精度只能提前、不能延后；
     * 满足日期前提后直接读取数据源 DAYS_TO_DUE 作为预警和强制判断口径。普通4～30天计划不会
     * 截断当前在机SKU；若当前已知收尾晚于最早候选日06:00，则寻找计划日前自然收尾后的最近
     * 合规执行日，并只在两者之间开放小余量插排。3天内计划优先占用计划日前最早合规日。</p>
     *
     * @param context 排程上下文
     */
    public void prepareMaintenancePlanWindows(LhScheduleContext context) {
        if (Objects.isNull(context) || CollectionUtils.isEmpty(context.getOrderedMaintenancePlanList())
                || CollectionUtils.isEmpty(context.getMachineScheduleMap())
                || Objects.isNull(context.getScheduleDate())) {
            return;
        }
        context.setMaintenancePreDecisionCompleted(true);
        int forceDays = getParamInt(context, LhScheduleParamConstant.MAINTENANCE_FORCE_CHECK_DAYS,
                LhScheduleConstant.MAINTENANCE_FORCE_CHECK_DAYS);
        List<LhPrecisionPlan> orderedPlans =
                new ArrayList<LhPrecisionPlan>(context.getOrderedMaintenancePlanList());
        // 中心服务再次执行稳定排序，避免调用方绕过初始化服务或测试手工构造上下文时，
        // 精度优先级退化为数据查询顺序。daysToDue直接取数据源字段，不按日期重新推导。
        orderedPlans.sort(Comparator
                .comparing(LhPrecisionPlan::getDaysToDue,
                        Comparator.nullsLast(Integer::compareTo))
                .thenComparing(LhPrecisionPlan::getPlanDate,
                        Comparator.nullsLast(Date::compareTo))
                .thenComparing(plan -> LhSingleControlMachineUtil.resolvePhysicalMachineCode(
                        plan.getMachineCode()), Comparator.nullsLast(String::compareTo)));
        context.setOrderedMaintenancePlanList(orderedPlans);
        Set<String> handledPhysicalMachineSet = new HashSet<String>(
                Math.max(4, orderedPlans.size() * 2));
        StringBuilder orderLog = new StringBuilder(192);
        int order = 0;
        for (LhPrecisionPlan plan : orderedPlans) {
            if (!isPlanUncompleted(plan) || Objects.isNull(plan.getDaysToDue())
                    || StringUtils.isEmpty(plan.getMachineCode())) {
                continue;
            }
            // 中心入口再次校验计划日期，避免测试构造、历史调用或未来新增入口绕过基础数据查询条件。
            if (!isPlanDateEligible(context, plan)) {
                appendMaintenanceProcessLog(context, MAINTENANCE_PROCESS_LOG_TITLE,
                        plan.getMachineCode(), plan, null,
                        "计划日期早于排程T日或计划日期为空",
                        "不进入本轮精度调度，禁止延后执行");
                log.info("精度计划日期不满足本轮触发前提，跳过, 机台: {}, 计划日期: {}, T日: {}, "
                                + "距到期天数: {}",
                        plan.getMachineCode(), LhScheduleTimeUtil.formatDate(plan.getPlanDate()),
                        LhScheduleTimeUtil.formatDate(context.getScheduleDate()), plan.getDaysToDue());
                continue;
            }
            String physicalMachineCode = LhSingleControlMachineUtil.resolvePhysicalMachineCode(
                    plan.getMachineCode());
            if (!handledPhysicalMachineSet.add(physicalMachineCode)) {
                appendMaintenanceProcessLog(context, MAINTENANCE_PROCESS_LOG_TITLE,
                        plan.getMachineCode(), plan, null,
                        "同一物理机台已有更高优先级精度计划", "本批跳过重复计划");
                continue;
            }
            MachineScheduleDTO machine = resolveRuntimeMachine(context, plan.getMachineCode());
            if (Objects.isNull(machine) || !CollectionUtils.isEmpty(machine.getMaintenanceWindowList())) {
                continue;
            }
            order++;
            if (orderLog.length() > 0) {
                orderLog.append(" -> ");
            }
            orderLog.append(order).append(':').append(physicalMachineCode)
                    .append('(').append(plan.getDaysToDue()).append("天)");

            Date firstCandidateDate = resolveAvailableMaintenanceDate(context,
                    LhScheduleTimeUtil.clearTime(context.getScheduleDate()), machine.getMachineCode(), plan);
            Date knownEndingTime = resolvePhysicalKnownEndingTime(context, machine);
            Date firstCutoffTime = buildProductionCutoffTime(firstCandidateDate);
            boolean forceDown = plan.getDaysToDue() <= forceDays
                    && (Objects.isNull(knownEndingTime) || knownEndingTime.after(firstCutoffTime));
            boolean preInsertAllowed = false;
            Date finalPlanDate = firstCandidateDate;

            /*
             * 普通4～30天计划不允许在前SKU收尾时间未知时直接占用最早执行日。
             * 中心预决策一旦挂窗，后续续作链会按该窗口限制生产；若此处把“未知”误判成
             * “可以在06:00前收尾”，既可能提前阻断正常续作，也无法按规则寻找自然收尾后的
             * 最近合规日。因此普通计划本轮明确暂缓，等待后续滚动取得可靠收尾时间；
             * 3天内计划仍按硬性要求挂窗并由强制下机链处理。
             */
            if (plan.getDaysToDue() > forceDays && Objects.isNull(knownEndingTime)) {
                context.getMaintenanceDeferredPhysicalMachineCodeSet().add(physicalMachineCode);
                appendMaintenanceProcessLog(context, MAINTENANCE_PROCESS_LOG_TITLE,
                        machine.getMachineCode(), plan, null,
                        "普通精度计划前SKU预计收尾时间未知，无法完成06:00截止判断",
                        "本轮暂缓挂窗，等待后续滚动重新决策");
                log.warn("普通精度计划前SKU收尾时间未知，本轮暂缓挂窗, 机台: {}, "
                                + "计划日期: {}, 距到期天数: {}, 强制阈值天数: {}",
                        machine.getMachineCode(), LhScheduleTimeUtil.formatDate(plan.getPlanDate()),
                        plan.getDaysToDue(), forceDays);
                continue;
            }

            if (!forceDown && Objects.nonNull(knownEndingTime) && knownEndingTime.after(firstCutoffTime)) {
                // 最新业务口径以PLAN_DATE作为允许执行的最后日期，DUE_DATE不参与延后边界判断。
                Date dueDate = LhScheduleTimeUtil.clearTime(plan.getPlanDate());
                Date cursorDate = LhScheduleTimeUtil.addDays(firstCandidateDate, 1);
                Date availableDate = resolveAvailableMaintenanceDate(
                        context, cursorDate, machine.getMachineCode(), plan);
                while (Objects.nonNull(dueDate) && !availableDate.after(dueDate)
                        && knownEndingTime.after(buildProductionCutoffTime(availableDate))) {
                    cursorDate = LhScheduleTimeUtil.addDays(availableDate, 1);
                    availableDate = resolveAvailableMaintenanceDate(
                            context, cursorDate, machine.getMachineCode(), plan);
                }
                if (Objects.nonNull(dueDate) && availableDate.after(dueDate)) {
                    appendMaintenanceProcessLog(context, MAINTENANCE_PROCESS_LOG_TITLE,
                            machine.getMachineCode(), plan, knownEndingTime,
                            "普通精度计划在到期日前无自然收尾窗口", "等待后续滚动进入3天强制范围");
                    continue;
                }
                finalPlanDate = availableDate;
                preInsertAllowed = knownEndingTime.before(buildProductionCutoffTime(finalPlanDate));
            }

            if (!isExecutionDateWithinPlanDate(plan, finalPlanDate)) {
                log.warn("精度计划在计划日前无合规执行日，本轮取消安排, 机台: {}, 计划日期: {}, "
                                + "距到期天数: {}, 候选执行日期: {}",
                        machine.getMachineCode(), LhScheduleTimeUtil.formatDate(plan.getPlanDate()),
                        plan.getDaysToDue(), LhScheduleTimeUtil.formatDate(finalPlanDate));
                appendMaintenanceProcessLog(context, MAINTENANCE_PROCESS_LOG_TITLE,
                        machine.getMachineCode(), plan, finalPlanDate,
                        "计划日前日期均被每日台数、周日、盘点日、节假日或停机任务占用",
                        "取消安排，禁止延后执行");
                continue;
            }
            attachMaintenanceWindow(context, machine, plan, finalPlanDate,
                    forceDown, preInsertAllowed,
                    forceDown ? TRIGGER_REASON_FORCE_DOWN : TRIGGER_REASON_AFTER_ENDING);
        }
        log.info("精度计划处理排序完成, 工厂: {}, 目标日: {}, 计划数: {}, 排序结果: {}",
                context.getFactoryCode(), LhScheduleTimeUtil.formatDate(context.getScheduleDate()),
                order, orderLog);
    }

    /**
     * 校验换活字块、新增排产或特殊材料候选是否允许占用精度计划前窗口。
     *
     * <p>只有明确标记为可插排的4～30天计划才允许在执行日06:00前新增SKU，并且必须一次排完
     * SKU完整待排量。3天强制计划、前SKU本可在06:00前自然收尾的计划以及已接受过插排的窗口，
     * 均禁止再次填充。候选从预热完成后开始时属于正常后续生产，不受本方法的50条限制。</p>
     *
     * @param context 排程上下文
     * @param machine 候选机台
     * @param sku 候选SKU
     * @param pendingQty 候选进入本机台前的完整真实待排量
     * @param plannedQty 本次结果最终计划量
     * @param preparationStartTime 换模或换活字块开始时间；无准备任务时传开产时间
     * @param productionStartTime 实际开产时间
     * @param completionTime 最后一个正量班次的实际完成时间
     * @return 空字符串表示允许；非空为具体排除原因
     */
    public String resolvePrecisionCandidateRejectReason(LhScheduleContext context,
                                                        MachineScheduleDTO machine,
                                                        SkuScheduleDTO sku,
                                                        int pendingQty,
                                                        int plannedQty,
                                                        Date preparationStartTime,
                                                        Date productionStartTime,
                                                        Date completionTime) {
        MachineMaintenanceWindowDTO window = resolveFirstMaintenanceWindow(machine);
        if (Objects.isNull(window) || Objects.isNull(window.getProductionCutoffTime())
                || Objects.isNull(window.getProductionResumeTime())) {
            return StringUtils.EMPTY;
        }
        Date taskStartTime = Objects.nonNull(preparationStartTime)
                ? preparationStartTime : productionStartTime;
        if (Objects.isNull(taskStartTime)) {
            return "候选缺少换型或开产开始时间";
        }
        /*
         * 中心预决策会一次性为30天内全部精度计划分配执行日；当每日台数限制使某些窗口被顺延到
         * 当前滚动排程窗口之外时，该未来窗口不能提前锁死本轮正常生产。只有精度生产截止已经进入
         * 当前排程窗口，才把本次新增/换活字块结果视为“精度前插排”并执行完整余量及06:00过滤。
         */
        if (Objects.nonNull(context.getWindowEndDate())
                && !window.getProductionCutoffTime().before(
                LhScheduleTimeUtil.addDays(
                        LhScheduleTimeUtil.clearTime(context.getWindowEndDate()), 1))) {
            return StringUtils.EMPTY;
        }
        // 预热完成后开始属于精度后的正常生产，不受前置插排阈值约束。
        if (!taskStartTime.before(window.getProductionResumeTime())) {
            return StringUtils.EMPTY;
        }
        String rejectReason = StringUtils.EMPTY;
        int maxQty = resolvePrecisionPreInsertMaxQty(context);
        if (!window.isPreInsertAllowed()) {
            rejectReason = window.isForceDown()
                    ? "3天内强制精度计划禁止插排SKU"
                    : "前SKU可在06:00前自然收尾，按规则保留为空等";
        } else if (window.isPreInsertScheduled()) {
            rejectReason = "同一物理机台精度前窗口已安排插排SKU";
        } else if (pendingQty <= 0) {
            rejectReason = "SKU无真实待排量";
        } else if (pendingQty > maxQty) {
            rejectReason = "完整待排量超过精度前插排上限" + maxQty;
        } else if (plannedQty != pendingQty) {
            rejectReason = plannedQty > pendingQty
                    ? "结果计划量超过真实待排量，禁止精度前超排"
                    : "结果未完整排完待排量，禁止截断SKU";
        } else if (Objects.isNull(productionStartTime) || Objects.isNull(completionTime)) {
            rejectReason = "无法计算候选完整时间轴";
        } else if (taskStartTime.after(window.getProductionCutoffTime())
                || completionTime.after(window.getProductionCutoffTime())) {
            rejectReason = "换型、首检或生产完成时间晚于精度执行日06:00";
        }
        log.info("精度前插排候选判断, 机台: {}, 计划日期: {}, 距到期天数: {}, SKU: {}, "
                        + "待排量: {}, 计划量: {}, 准备开始: {}, 预计开产: {}, 预计收尾: {}, "
                        + "生产截止: {}, 结论: {}, 原因: {}",
                machine.getMachineCode(), LhScheduleTimeUtil.formatDate(window.getPlanDate()),
                window.getDaysToDue(), Objects.nonNull(sku) ? sku.getMaterialCode() : "-",
                pendingQty, plannedQty, LhScheduleTimeUtil.formatDateTime(taskStartTime),
                LhScheduleTimeUtil.formatDateTime(productionStartTime),
                LhScheduleTimeUtil.formatDateTime(completionTime),
                LhScheduleTimeUtil.formatDateTime(window.getProductionCutoffTime()),
                StringUtils.isEmpty(rejectReason) ? "接受" : "排除",
                StringUtils.isEmpty(rejectReason) ? "完整时间轴可在06:00前完成" : rejectReason);
        return rejectReason;
    }

    /**
     * 解析精度前插排最大完整待排量。
     *
     * @param context 排程上下文
     * @return 正整数阈值；缺失、非数字或非正数时返回默认50
     */
    private int resolvePrecisionPreInsertMaxQty(LhScheduleContext context) {
        if (Objects.nonNull(context) && Objects.nonNull(context.getScheduleConfig())) {
            return context.getScheduleConfig().getPrecisionPreInsertMaxQty();
        }
        int configuredValue = getParamInt(
                context, LhScheduleParamConstant.PRECISION_PRE_INSERT_MAX_QTY,
                LhScheduleConstant.PRECISION_PRE_INSERT_MAX_QTY);
        if (configuredValue > 0) {
            return configuredValue;
        }
        log.warn("精度前插排最大计划量配置非正数，使用默认值, paramCode: {}, value: {}, defaultValue: {}",
                LhScheduleParamConstant.PRECISION_PRE_INSERT_MAX_QTY, configuredValue,
                LhScheduleConstant.PRECISION_PRE_INSERT_MAX_QTY);
        return LhScheduleConstant.PRECISION_PRE_INSERT_MAX_QTY;
    }

    /**
     * 在候选结果正式提交后登记精度前窗口已被使用，并同步物理机台L/R两侧。
     *
     * @param context 排程上下文
     * @param machine 已接受插排的机台
     * @param result 已正式提交的插排结果
     */
    public void markPrecisionPreInsertScheduled(LhScheduleContext context,
                                                MachineScheduleDTO machine,
                                                LhScheduleResult result) {
        markPrecisionPreInsertScheduled(machine);
        markPrecisionPreInsertScheduled(LhSingleControlMachineUtil.resolvePairMachine(
                context, machine.getMachineCode()));
        if (Objects.nonNull(result)) {
            context.getPrecisionPreInsertResultSet().add(result);
        }
    }

    /**
     * 判断候选任务是否真实落在精度计划生产截止日前，应在提交后登记为精度前插排。
     *
     * @param machine 候选机台
     * @param taskStartTime 换型或开产开始时间
     * @return true-属于精度前插排；false-无精度窗口或属于预热后的正常生产
     */
    public boolean shouldMarkPrecisionPreInsert(MachineScheduleDTO machine, Date taskStartTime) {
        MachineMaintenanceWindowDTO window = resolveFirstMaintenanceWindow(machine);
        return Objects.nonNull(window) && window.isPreInsertAllowed()
                && Objects.nonNull(taskStartTime)
                && taskStartTime.before(window.getProductionCutoffTime());
    }

    /**
     * 判断机台是否存在尚未使用的精度前插排窗口。
     *
     * @param machine 机台
     * @return true-允许继续按现有顺序遍历小余量候选
     */
    public boolean hasOpenPrecisionPreInsertWindow(MachineScheduleDTO machine) {
        MachineMaintenanceWindowDTO window = resolveFirstMaintenanceWindow(machine);
        return Objects.nonNull(window) && window.isPreInsertAllowed()
                && !window.isPreInsertScheduled();
    }

    /**
     * 获取3天内精度计划的强制下机截止时间。
     *
     * @param machine 机台
     * @return 执行日06:00；非强制计划返回null
     */
    public Date resolveForceDownCutoffTime(MachineScheduleDTO machine) {
        MachineMaintenanceWindowDTO window = resolveFirstMaintenanceWindow(machine);
        return Objects.nonNull(window) && window.isForceDown()
                ? window.getProductionCutoffTime() : null;
    }

    private void markPrecisionPreInsertScheduled(MachineScheduleDTO machine) {
        MachineMaintenanceWindowDTO window = resolveFirstMaintenanceWindow(machine);
        if (Objects.nonNull(window) && window.isPreInsertAllowed()) {
            window.setPreInsertScheduled(true);
        }
    }

    /**
     * 判断结果是否属于精度执行日前已接受的插排结果。
     *
     * @param machine 机台
     * @param result 结果
     * @return true-结果在精度截止日前完成且窗口已标记为插排
     */
    public boolean isPrecisionPreInsertResult(MachineScheduleDTO machine, LhScheduleResult result) {
        MachineMaintenanceWindowDTO window = resolveFirstMaintenanceWindow(machine);
        return Objects.nonNull(window) && window.isPreInsertScheduled()
                && Objects.nonNull(result) && Objects.nonNull(result.getSpecEndTime())
                && !result.getSpecEndTime().after(window.getProductionCutoffTime());
    }

    private MachineMaintenanceWindowDTO resolveFirstMaintenanceWindow(MachineScheduleDTO machine) {
        return Objects.isNull(machine) || CollectionUtils.isEmpty(machine.getMaintenanceWindowList())
                ? null : machine.getMaintenanceWindowList().get(0);
    }

    /**
     * 解析精准计划对应的运行态机台。
     *
     * @param context 排程上下文
     * @param planMachineCode 计划机台编码
     * @return 运行态机台；物理机拆分为L/R时优先返回左侧
     */
    private MachineScheduleDTO resolveRuntimeMachine(LhScheduleContext context, String planMachineCode) {
        MachineScheduleDTO machine = context.getMachineScheduleMap().get(planMachineCode);
        if (Objects.nonNull(machine)) {
            return machine;
        }
        String leftMachineCode = LhSingleControlMachineUtil.resolveLeftMachineCode(planMachineCode);
        machine = context.getMachineScheduleMap().get(leftMachineCode);
        if (Objects.nonNull(machine)) {
            return machine;
        }
        return context.getMachineScheduleMap().get(
                LhSingleControlMachineUtil.resolveRightMachineCode(planMachineCode));
    }

    /**
     * 读取物理机台两侧当前已知的最晚收尾时间。
     *
     * @param context 排程上下文
     * @param machine 任一侧运行态机台
     * @return 最晚收尾时间；在机侧缺少预计收尾时间时返回null
     */
    private Date resolvePhysicalKnownEndingTime(LhScheduleContext context, MachineScheduleDTO machine) {
        Date endingTime = resolveKnownEndingTime(context, machine);
        if (StringUtils.isNotEmpty(machine.getCurrentMaterialCode()) && Objects.isNull(endingTime)) {
            return null;
        }
        MachineScheduleDTO pairMachine = LhSingleControlMachineUtil.resolvePairMachine(
                context, machine.getMachineCode());
        Date pairEndingTime = resolveKnownEndingTime(context, pairMachine);
        if (Objects.nonNull(pairMachine) && StringUtils.isNotEmpty(pairMachine.getCurrentMaterialCode())
                && Objects.isNull(pairEndingTime)) {
            return null;
        }
        return later(endingTime, pairEndingTime);
    }

    /**
     * 读取单侧机台已知收尾时间；空闲机台以当前排程日零点表示已经就绪。
     *
     * @param context 排程上下文
     * @param machine 运行态机台
     * @return 已知收尾时间
     */
    private Date resolveKnownEndingTime(LhScheduleContext context, MachineScheduleDTO machine) {
        if (Objects.isNull(machine)) {
            return null;
        }
        // 空闲机台没有预计收尾时间并不代表时间未知，应视为排程日开始时已经就绪。
        // 该区分可避免3天内精准计划把空闲机台误判为“必须强制下机”。
        if (StringUtils.isEmpty(machine.getCurrentMaterialCode())
                && Objects.isNull(machine.getEstimatedEndTime())) {
            return context.getScheduleDate();
        }
        return machine.getEstimatedEndTime();
    }

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
        String physicalMachineCode = LhSingleControlMachineUtil.resolvePhysicalMachineCode(
                machine.getMachineCode());
        /*
         * S4.4入口已经完成中心预决策时，只有“前SKU收尾时间未知”的普通计划允许在
         * 续作结果形成后补决策一次。其他计划必须服从中心排序与已分配日期，禁止旧入口
         * 再挂载第二个窗口或绕过到期日前结论。
         */
        boolean deferredByUnknownEnding = context.getMaintenanceDeferredPhysicalMachineCodeSet()
                .contains(physicalMachineCode);
        if (context.isMaintenancePreDecisionCompleted() && !deferredByUnknownEnding) {
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
                        + "，生产截止=06:00，精度固定开始=08:00",
                physicalEndingTime.after(buildProductionCutoffTime(
                        LhScheduleTimeUtil.clearTime(physicalEndingTime)))
                        ? "06:00后收尾，从下一自然日开始顺延" : "当天08:00可作为候选");
        Date candidateDate = resolveNormalCandidateDate(context, physicalEndingTime);
        Date planDate = resolveAvailableMaintenanceDate(context, candidateDate, lookupMachineCode, plan);
        boolean preInsertAllowed = deferredByUnknownEnding
                && physicalEndingTime.after(buildProductionCutoffTime(
                LhScheduleTimeUtil.clearTime(physicalEndingTime)))
                && physicalEndingTime.before(buildProductionCutoffTime(planDate));
        boolean attached = attachMaintenanceWindow(context, machine, plan, planDate,
                false, preInsertAllowed, TRIGGER_REASON_AFTER_ENDING);
        if (attached) {
            context.getMaintenanceDeferredPhysicalMachineCodeSet().remove(physicalMachineCode);
        }
        return attached;
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
        if (context.isMaintenancePreDecisionCompleted()) {
            return false;
        }
        String lookupMachineCode = machine.getMachineCode();
        LhPrecisionPlan plan = resolveMaintenancePlan(context, lookupMachineCode);
        Integer daysToDue = resolveDaysToDue(plan);
        if (Objects.isNull(plan) || Objects.isNull(daysToDue) || Objects.isNull(context.getScheduleDate())) {
            return false;
        }
        if (!isPlanDateEligible(context, plan)) {
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
                true, false, TRIGGER_REASON_FORCE_DOWN);
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
        if (context.isMaintenancePreDecisionCompleted()) {
            return false;
        }
        LhPrecisionPlan plan = resolveMaintenancePlan(context, machine.getMachineCode());
        Integer daysToDue = resolveDaysToDue(plan);
        if (!isPlanUncompleted(plan) || Objects.isNull(daysToDue)
                || !isPlanDateEligible(context, plan)) {
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
                Date occupationEndTime = Objects.nonNull(window.getProductionResumeTime())
                        ? window.getProductionResumeTime() : window.getMaintenanceEndTime();
                if (!isWindowOverlap(window, adjustedStartTime, candidateEndTime, occupationEndTime)) {
                    continue;
                }
                latestOverlapEndTime = later(latestOverlapEndTime, occupationEndTime);
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
     * 判断正规换模候选窗口是否与精度计划完整占用区间重叠。
     * <p>完整占用区间包含保养及胶囊预热。换模时长由调用方传入现有
     * {@code SYS0302009} 解析结果，本方法不读取维保重叠专用4小时参数。</p>
     *
     * @param context 排程上下文，用于按现有参数补算胶囊预热完成时间
     * @param machine 机台运行态
     * @param candidateStartTime 正规换模原始候选开始时间
     * @param mouldChangeHours 正规换模时长
     * @return true-换模与精度计划完整占用区间重叠；false-不重叠
     */
    public boolean shouldParallelMouldChangeWithMaintenance(LhScheduleContext context,
                                                            MachineScheduleDTO machine,
                                                            Date candidateStartTime,
                                                            int mouldChangeHours) {
        if (Objects.isNull(candidateStartTime) || mouldChangeHours <= 0) {
            return false;
        }
        Date alignedCandidateStartTime = resolveRegularMouldChangeCandidateStartTime(
                context, candidateStartTime);
        Date candidateEndTime = LhScheduleTimeUtil.addHours(alignedCandidateStartTime, mouldChangeHours);
        return hasMouldChangeMaintenanceOverlap(
                context, machine, alignedCandidateStartTime, candidateEndTime);
    }

    /**
     * 解析精度计划与正规换模并行时的换模开始时间。
     * <p>前序SKU已在保养开始前结束时，将换模对齐到精度计划开始时刻，使两项任务同时执行；
     * 若原始候选时间已经晚于保养开始，则禁止向前倒推，保持原候选时间。</p>
     *
     * @param context 排程上下文
     * @param machine 机台运行态
     * @param candidateStartTime 正规换模原始候选开始时间
     * @param mouldChangeHours 正规换模时长
     * @return 并行换模开始时间；未命中重叠时返回原候选时间
     */
    public Date resolveParallelMouldChangeStartTime(LhScheduleContext context,
                                                    MachineScheduleDTO machine,
                                                    Date candidateStartTime,
                                                    int mouldChangeHours) {
        if (Objects.isNull(candidateStartTime) || mouldChangeHours <= 0
                || Objects.isNull(machine) || CollectionUtils.isEmpty(machine.getMaintenanceWindowList())) {
            return candidateStartTime;
        }
        Date alignedCandidateStartTime = resolveRegularMouldChangeCandidateStartTime(
                context, candidateStartTime);
        Date candidateEndTime = LhScheduleTimeUtil.addHours(alignedCandidateStartTime, mouldChangeHours);
        for (MachineMaintenanceWindowDTO window : machine.getMaintenanceWindowList()) {
            Date resumeTime = resolveWindowResumeTime(context, window);
            if (!isWindowOverlap(window, alignedCandidateStartTime, candidateEndTime, resumeTime)) {
                continue;
            }
            if (!alignedCandidateStartTime.after(window.getMaintenanceStartTime())) {
                return window.getMaintenanceStartTime();
            }
            return alignedCandidateStartTime;
        }
        return candidateStartTime;
    }

    /**
     * 解析正规换模与精度计划并行后的最终恢复生产时间。
     * <p>完成点取“正规换模结束”和“保养结束+现有胶囊预热参数”的最大值，重叠时间只计算一次。</p>
     *
     * @param context 排程上下文
     * @param machine 机台运行态
     * @param mouldChangeStartTime 正规换模开始时间
     * @param mouldChangeEndTime 正规换模结束时间
     * @return 最终恢复生产时间；未命中精度计划重叠时返回换模结束时间
     */
    public Date resolveParallelMouldChangeReadyTime(LhScheduleContext context,
                                                    MachineScheduleDTO machine,
                                                    Date mouldChangeStartTime,
                                                    Date mouldChangeEndTime) {
        if (Objects.isNull(mouldChangeStartTime) || Objects.isNull(mouldChangeEndTime)
                || !mouldChangeStartTime.before(mouldChangeEndTime)
                || Objects.isNull(machine) || CollectionUtils.isEmpty(machine.getMaintenanceWindowList())) {
            return mouldChangeEndTime;
        }
        Date readyTime = mouldChangeEndTime;
        for (MachineMaintenanceWindowDTO window : machine.getMaintenanceWindowList()) {
            Date resumeTime = resolveWindowResumeTime(context, window);
            if (isWindowOverlap(window, mouldChangeStartTime, mouldChangeEndTime, resumeTime)) {
                readyTime = later(readyTime, resumeTime);
            }
        }
        return readyTime;
    }

    /**
     * 判断实际正规换模窗口是否与精度计划完整占用区间重叠。
     *
     * @param context 排程上下文
     * @param machine 机台运行态
     * @param mouldChangeStartTime 正规换模开始时间
     * @param mouldChangeEndTime 正规换模结束时间
     * @return true-实际重叠；false-未重叠
     */
    public boolean hasMouldChangeMaintenanceOverlap(LhScheduleContext context,
                                                    MachineScheduleDTO machine,
                                                    Date mouldChangeStartTime,
                                                    Date mouldChangeEndTime) {
        if (Objects.isNull(machine) || CollectionUtils.isEmpty(machine.getMaintenanceWindowList())
                || Objects.isNull(mouldChangeStartTime) || Objects.isNull(mouldChangeEndTime)
                || !mouldChangeStartTime.before(mouldChangeEndTime)) {
            return false;
        }
        for (MachineMaintenanceWindowDTO window : machine.getMaintenanceWindowList()) {
            Date resumeTime = resolveWindowResumeTime(context, window);
            if (isWindowOverlap(window, mouldChangeStartTime, mouldChangeEndTime, resumeTime)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 解析与实际正规换模区间重叠的精度计划开始时间。
     *
     * @param context 排程上下文
     * @param machine 机台运行态
     * @param mouldChangeStartTime 正规换模开始时间
     * @param mouldChangeEndTime 正规换模结束时间
     * @return 最早重叠精度计划开始时间；未命中时返回空
     */
    public Date resolveOverlappedMaintenanceStartTime(LhScheduleContext context,
                                                       MachineScheduleDTO machine,
                                                       Date mouldChangeStartTime,
                                                       Date mouldChangeEndTime) {
        if (Objects.isNull(machine) || CollectionUtils.isEmpty(machine.getMaintenanceWindowList())) {
            return null;
        }
        Date maintenanceStartTime = null;
        for (MachineMaintenanceWindowDTO window : machine.getMaintenanceWindowList()) {
            Date resumeTime = resolveWindowResumeTime(context, window);
            if (!isWindowOverlap(window, mouldChangeStartTime, mouldChangeEndTime, resumeTime)) {
                continue;
            }
            if (Objects.isNull(maintenanceStartTime)
                    || window.getMaintenanceStartTime().before(maintenanceStartTime)) {
                maintenanceStartTime = window.getMaintenanceStartTime();
            }
        }
        return maintenanceStartTime;
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
        // 最新规则明确禁止换模、换活字块与精度计划并行。所有调用方继续复用原入口，
        // 但统一返回false，随后走正常切换时长并由delaySwitchStartByMaintenance顺延至预热结束。
        return false;
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
                                            boolean preInsertAllowed,
                                            String triggerReason) {
        // 最终挂窗前再次守住“只能提前、不能延后”边界。日期硬约束若导致候选日晚于计划日期，
        // 本轮不得挂载精度窗口，也不得通过强制下机把历史计划延后到当前或未来日期执行。
        if (!isExecutionDateWithinPlanDate(plan, planDate)) {
            appendMaintenanceProcessLog(context, MAINTENANCE_PROCESS_LOG_TITLE,
                    machine.getMachineCode(), plan, planDate,
                    "候选执行日期晚于精度计划日期",
                    "取消挂窗，禁止延后执行");
            log.warn("精度候选执行日期晚于计划日期，本轮取消安排, 机台: {}, 计划日期: {}, "
                            + "候选执行日期: {}, 距到期天数: {}",
                    machine.getMachineCode(), LhScheduleTimeUtil.formatDate(plan.getPlanDate()),
                    LhScheduleTimeUtil.formatDate(planDate), plan.getDaysToDue());
            return false;
        }
        int durationHours = getParamInt(context, LhScheduleParamConstant.MAINTENANCE_DURATION_HOURS,
                LhScheduleConstant.MAINTENANCE_DURATION_HOURS);
        Date startTime = buildMaintenanceStartTime(context, planDate);
        Date endTime = LhScheduleTimeUtil.addHours(startTime, durationHours);
        Date productionResumeTime = LhScheduleTimeUtil.addMinutes(
                endTime, LhScheduleTimeUtil.getCapsulePreheatMinutes(context));
        Date productionCutoffTime = buildProductionCutoffTime(planDate);

        // 单控 L/R 属于同一物理机台：任一侧触发时两侧同步挂载相同占用窗口，
        // 但每一侧窗口保留各自精准计划主键，排程完成后分别回填 SCHEDULE_DATE。
        attachMaintenanceWindowToMachine(context, machine, plan, planDate, startTime, endTime,
                productionResumeTime, productionCutoffTime, forceDown, preInsertAllowed, triggerReason);
        MachineScheduleDTO pairMachine = LhSingleControlMachineUtil.resolvePairMachine(context, machine.getMachineCode());
        if (Objects.nonNull(pairMachine)) {
            LhPrecisionPlan pairPlan = context.getMaintenancePlanMap().get(pairMachine.getMachineCode());
            attachMaintenanceWindowToMachine(context, pairMachine, pairPlan, planDate, startTime, endTime,
                    productionResumeTime, productionCutoffTime, forceDown, preInsertAllowed, triggerReason);
        }
        increaseDailyMaintenanceCount(context, planDate, machine.getMachineCode());
        log.info("硫化机保养窗口已安排, 机台: {}, 配对侧: {}, 保养类型: {}, 计划到期: {}, 距到期天数: {}, "
                        + "生产截止: {}, 保养开始: {}, 保养结束: {}, 预热完成及最早开产: {}, "
                        + "允许精度前插排: {}, 强制下机: {}, 原因: {}",
                machine.getMachineCode(), Objects.nonNull(pairMachine) ? pairMachine.getMachineCode() : "-",
                plan.getPrecisionType(), LhScheduleTimeUtil.formatDate(resolvePlanDueDate(context, plan)),
                resolveDaysToDue(plan), LhScheduleTimeUtil.formatDateTime(productionCutoffTime),
                LhScheduleTimeUtil.formatDateTime(startTime),
                LhScheduleTimeUtil.formatDateTime(endTime), LhScheduleTimeUtil.formatDateTime(productionResumeTime),
                preInsertAllowed, forceDown, triggerReason);
        appendMaintenanceProcessLog(context, MAINTENANCE_FINAL_LOG_TITLE, machine.getMachineCode(), plan,
                startTime,
                "保养结束=" + PriorityTraceLogHelper.formatDateTime(endTime)
                        + "，预热完成及最早开产=" + PriorityTraceLogHelper.formatDateTime(productionResumeTime)
                        + "，生产截止=" + PriorityTraceLogHelper.formatDateTime(productionCutoffTime)
                        + "，允许插排=" + preInsertAllowed
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
     * @param productionCutoffTime 精度执行日前生产和准备任务截止时间
     * @param forceDown 是否强制下机
     * @param preInsertAllowed 是否允许在截止时间前插排完整小余量SKU
     * @param triggerReason 触发原因
     */
    private void attachMaintenanceWindowToMachine(LhScheduleContext context,
                                                   MachineScheduleDTO targetMachine,
                                                   LhPrecisionPlan targetPlan,
                                                   Date planDate,
                                                   Date startTime,
                                                   Date endTime,
                                                   Date productionResumeTime,
                                                   Date productionCutoffTime,
                                                   boolean forceDown,
                                                   boolean preInsertAllowed,
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
        window.setDaysToDue(resolveDaysToDue(targetPlan));
        window.setPlanDate(planDate);
        window.setMaintenanceStartTime(startTime);
        window.setMaintenanceEndTime(endTime);
        window.setProductionResumeTime(productionResumeTime);
        window.setProductionCutoffTime(productionCutoffTime);
        window.setPreInsertAllowed(preInsertAllowed);
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
        Date searchStartDate = cursorDate;
        int skippedDays = 0;
        String firstUnavailableReason = StringUtils.EMPTY;
        String lastUnavailableReason = StringUtils.EMPTY;
        while (true) {
            String unavailableReason = resolveDateUnavailableReason(context, cursorDate, machineCode);
            if (StringUtils.isEmpty(unavailableReason)) {
                String dateKey = LhScheduleTimeUtil.formatDate(cursorDate);
                StringBuilder ruleBuilder = new StringBuilder(192);
                ruleBuilder.append("搜索起点=")
                        .append(LhScheduleTimeUtil.formatDate(searchStartDate))
                        .append("，顺延天数=").append(skippedDays)
                        .append("，首个排除原因=")
                        .append(StringUtils.isEmpty(firstUnavailableReason)
                                ? "无" : firstUnavailableReason)
                        .append("，最后排除原因=")
                        .append(StringUtils.isEmpty(lastUnavailableReason)
                                ? "无" : lastUnavailableReason)
                        .append("，最终当天保养物理机台数=")
                        .append(resolveDailyMaintenanceCount(context, dateKey))
                        .append("/").append(DAILY_MAINTENANCE_LIMIT)
                        .append("，周日命中=").append(isSunday(cursorDate))
                        .append("，盘点日命中=").append(isLastDayOfMonth(cursorDate))
                        .append("，节假日前限制命中=")
                        .append(isHolidayOrHolidayBeforeDay(context, cursorDate));
                appendMaintenanceProcessLog(context, MAINTENANCE_PROCESS_LOG_TITLE, machineCode, plan,
                        cursorDate, ruleBuilder.toString(), "日期可用");
                log.info("精度计划执行日期搜索完成, 机台: {}, 搜索起点: {}, 最终日期: {}, "
                                + "顺延天数: {}, 首个排除原因: {}, 最后排除原因: {}",
                        machineCode, LhScheduleTimeUtil.formatDate(searchStartDate),
                        LhScheduleTimeUtil.formatDate(cursorDate), skippedDays,
                        StringUtils.isEmpty(firstUnavailableReason) ? "无" : firstUnavailableReason,
                        StringUtils.isEmpty(lastUnavailableReason) ? "无" : lastUnavailableReason);
                return cursorDate;
            }
            if (StringUtils.isEmpty(firstUnavailableReason)) {
                firstUnavailableReason = unavailableReason;
            }
            lastUnavailableReason = unavailableReason;
            skippedDays++;
            cursorDate = LhScheduleTimeUtil.addDays(cursorDate, 1);
        }
    }

    private String resolveDateUnavailableReason(LhScheduleContext context,
                                                Date targetDate,
                                                String machineCode) {
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
        String timelineConflictReason = resolveFixedTimelineConflictReason(
                context, targetDate, machineCode);
        if (StringUtils.isNotEmpty(timelineConflictReason)) {
            return timelineConflictReason;
        }
        return null;
    }

    /**
     * 判断精度计划及胶囊预热占用是否与机台既有停机、维修或清洗窗口重叠。
     *
     * @param context 排程上下文
     * @param targetDate 精度候选执行日期
     * @param machineCode 任一侧运行态机台编码
     * @return 冲突原因；无冲突返回空字符串
     */
    private String resolveFixedTimelineConflictReason(LhScheduleContext context,
                                                      Date targetDate,
                                                      String machineCode) {
        Date maintenanceStartTime = buildMaintenanceStartTime(context, targetDate);
        Date maintenanceEndTime = LhScheduleTimeUtil.addHours(maintenanceStartTime,
                getParamInt(context, LhScheduleParamConstant.MAINTENANCE_DURATION_HOURS,
                        LhScheduleConstant.MAINTENANCE_DURATION_HOURS));
        Date occupationEndTime = LhScheduleTimeUtil.addMinutes(maintenanceEndTime,
                LhScheduleTimeUtil.getCapsulePreheatMinutes(context));
        String physicalMachineCode = LhSingleControlMachineUtil.resolvePhysicalMachineCode(machineCode);
        if (!CollectionUtils.isEmpty(context.getDevicePlanShutList())) {
            for (MdmDevicePlanShut planShut : context.getDevicePlanShutList()) {
                if (Objects.isNull(planShut) || Objects.isNull(planShut.getBeginDate())
                        || Objects.isNull(planShut.getEndDate())
                        || !StringUtils.equals(physicalMachineCode,
                        LhSingleControlMachineUtil.resolvePhysicalMachineCode(planShut.getMachineCode()))) {
                    continue;
                }
                if (isTimeRangeOverlap(maintenanceStartTime, occupationEndTime,
                        planShut.getBeginDate(), planShut.getEndDate())) {
                    return "精度及预热窗口与设备停机/维修计划重叠";
                }
            }
        }
        for (MachineScheduleDTO runtimeMachine : context.getMachineScheduleMap().values()) {
            if (Objects.isNull(runtimeMachine)
                    || !StringUtils.equals(physicalMachineCode,
                    LhSingleControlMachineUtil.resolvePhysicalMachineCode(runtimeMachine.getMachineCode()))
                    || CollectionUtils.isEmpty(runtimeMachine.getCleaningWindowList())) {
                continue;
            }
            for (MachineCleaningWindowDTO cleaningWindow : runtimeMachine.getCleaningWindowList()) {
                if (Objects.nonNull(cleaningWindow)
                        && isTimeRangeOverlap(maintenanceStartTime, occupationEndTime,
                        cleaningWindow.getCleanStartTime(), cleaningWindow.getReadyTime())) {
                    return "精度及预热窗口与机台清洗计划重叠";
                }
            }
        }
        return StringUtils.EMPTY;
    }

    /**
     * 判断两个左闭右开时间区间是否重叠。
     *
     * @param firstStart 第一区间开始
     * @param firstEnd 第一区间结束
     * @param secondStart 第二区间开始
     * @param secondEnd 第二区间结束
     * @return true-存在实际重叠；false-无重叠或时间无效
     */
    private boolean isTimeRangeOverlap(Date firstStart,
                                       Date firstEnd,
                                       Date secondStart,
                                       Date secondEnd) {
        return Objects.nonNull(firstStart) && Objects.nonNull(firstEnd)
                && Objects.nonNull(secondStart) && Objects.nonNull(secondEnd)
                && firstStart.before(firstEnd) && secondStart.before(secondEnd)
                && firstStart.before(secondEnd) && secondStart.before(firstEnd);
    }

    private boolean isPlanDueSoon(LhScheduleContext context, LhPrecisionPlan plan) {
        Integer daysToDue = resolveDaysToDue(plan);
        if (Objects.isNull(plan) || Objects.isNull(daysToDue) || Objects.isNull(context.getScheduleDate())) {
            return false;
        }
        if (!isPlanDateEligible(context, plan)) {
            appendMaintenanceProcessLog(context, MAINTENANCE_PROCESS_LOG_TITLE, plan.getMachineCode(), plan,
                    null, "计划日期早于排程T日或计划日期为空",
                    "不进入本轮精度调度，禁止延后执行");
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
     * 判断精度计划日期是否具备本轮触发资格。
     *
     * <p>精度计划允许从T日起提前到计划日前执行，但计划日期一旦早于T日即视为已经错过本轮可执行窗口，
     * 不得把历史未完成计划延后到本轮执行。DAYS_TO_DUE仍只负责30天预警和3天强制分级，不能替代
     * PLAN_DATE >= T这一日期准入条件。</p>
     *
     * @param context 排程上下文
     * @param plan 精度计划
     * @return true-计划日期不早于T日；false-计划日期为空或早于T日
     */
    private boolean isPlanDateEligible(LhScheduleContext context, LhPrecisionPlan plan) {
        if (Objects.isNull(context) || Objects.isNull(context.getScheduleDate())
                || Objects.isNull(plan) || Objects.isNull(plan.getPlanDate())) {
            return false;
        }
        Date scheduleDate = LhScheduleTimeUtil.clearTime(context.getScheduleDate());
        Date precisionPlanDate = LhScheduleTimeUtil.clearTime(plan.getPlanDate());
        return !precisionPlanDate.before(scheduleDate);
    }

    /**
     * 判断最终精度执行日是否未超过计划日期。
     *
     * @param plan 精度计划
     * @param executionDate 候选执行日
     * @return true-候选日不晚于计划日；false-日期缺失或候选日晚于计划日
     */
    private boolean isExecutionDateWithinPlanDate(LhPrecisionPlan plan, Date executionDate) {
        if (Objects.isNull(plan) || Objects.isNull(plan.getPlanDate())
                || Objects.isNull(executionDate)) {
            return false;
        }
        Date precisionPlanDate = LhScheduleTimeUtil.clearTime(plan.getPlanDate());
        Date candidateDate = LhScheduleTimeUtil.clearTime(executionDate);
        return !candidateDate.after(precisionPlanDate);
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
     * 读取距离到期日剩余天数。
     * <p>精准计划预警和长期在机提前检查统一直接使用数据源维护的 DAYS_TO_DUE 字段，
     * 不再根据 DUE_DATE、PLAN_DATE 与排程T日重新计算，避免同一计划出现两套触发口径。</p>
     *
     * @param plan 精度保养计划
     * @return 距离到期日剩余天数；字段缺失返回 null
     */
    private Integer resolveDaysToDue(LhPrecisionPlan plan) {
        return Objects.isNull(plan) ? null : plan.getDaysToDue();
    }

    /**
     * 根据首个规格真实收尾时间解析正常精度候选日。
     * <p>前规格必须在精度执行日06:00前完整收尾：收尾不晚于06:00时可使用当天；晚于06:00时，
     * 必须从下一自然日开始寻找可用日期，禁止把保养提前到前规格仍在生产的时段。</p>
     *
     * @param context 排程上下文
     * @param endingTime 物理机台最晚收尾时间
     * @return 正常保养候选日零点
     */
    private Date resolveNormalCandidateDate(LhScheduleContext context, Date endingTime) {
        Date endingDate = LhScheduleTimeUtil.clearTime(endingTime);
        Date productionCutoffTime = buildProductionCutoffTime(endingDate);
        return endingTime.after(productionCutoffTime)
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
     * 构建精度执行日前生产与准备任务的固定截止时间。
     *
     * @param planDate 精度最终执行日期
     * @return 执行日当天06:00
     */
    private Date buildProductionCutoffTime(Date planDate) {
        return LhScheduleTimeUtil.buildTime(planDate,
                LhScheduleConstant.PRECISION_PRODUCTION_CUTOFF_HOUR, 0, 0);
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

    /**
     * 解析精度计划完整占用结束时间。
     * <p>优先复用窗口初始化时根据保养时长参数和胶囊预热参数计算的恢复生产时间；
     * 历史测试数据未写恢复时间时，再按保养结束时间叠加现有胶囊预热参数计算。</p>
     *
     * @param context 排程上下文
     * @param window 精度计划窗口
     * @return 精度计划及胶囊预热完成时间；窗口无效时返回空
     */
    private Date resolveWindowResumeTime(LhScheduleContext context, MachineMaintenanceWindowDTO window) {
        if (Objects.isNull(window) || Objects.isNull(window.getMaintenanceEndTime())) {
            return null;
        }
        if (Objects.nonNull(window.getProductionResumeTime())) {
            return window.getProductionResumeTime();
        }
        return LhScheduleTimeUtil.addMinutes(
                window.getMaintenanceEndTime(), LhScheduleTimeUtil.getCapsulePreheatMinutes(context));
    }

    /**
     * 将正规换模原始候选时间对齐到允许换模的早班起点。
     * <p>晚班候选必须先按现有禁止换模参数顺延，否则00:00等候选会因原始8小时窗口恰好止于
     * 精度计划开始边界而漏判，导致主排程、dayN模拟和定点预判时间轴不一致。</p>
     *
     * @param context 排程上下文
     * @param candidateStartTime 原始换模候选时间
     * @return 对齐后的正规换模候选时间
     */
    private Date resolveRegularMouldChangeCandidateStartTime(LhScheduleContext context,
                                                              Date candidateStartTime) {
        if (Objects.isNull(candidateStartTime)
                || !LhScheduleTimeUtil.isNoMouldChangeTime(context, candidateStartTime)) {
            return candidateStartTime;
        }
        return LhScheduleTimeUtil.resolveNextMorningAfterNoMouldChangeWindow(context, candidateStartTime);
    }

    /**
     * 判断指定任务区间是否与精度计划完整占用区间重叠。
     *
     * @param window 精度计划窗口
     * @param startTime 任务开始时间
     * @param endTime 任务结束时间
     * @param occupationEndTime 精度计划及胶囊预热完成时间
     * @return true-区间重叠，false-区间不重叠或参数无效
     */
    private boolean isWindowOverlap(MachineMaintenanceWindowDTO window,
                                    Date startTime,
                                    Date endTime,
                                    Date occupationEndTime) {
        return Objects.nonNull(window)
                && Objects.nonNull(window.getMaintenanceStartTime())
                && Objects.nonNull(startTime)
                && Objects.nonNull(endTime)
                && Objects.nonNull(occupationEndTime)
                && window.getMaintenanceStartTime().before(occupationEndTime)
                && startTime.before(endTime)
                && startTime.before(occupationEndTime)
                && endTime.after(window.getMaintenanceStartTime());
    }

    private boolean isWindowOverlap(MachineMaintenanceWindowDTO window, Date startTime, Date endTime) {
        return isWindowOverlap(window, startTime, endTime,
                Objects.nonNull(window) ? window.getMaintenanceEndTime() : null);
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
                .append(PriorityTraceLogHelper.safeText(resolveDaysToDue(plan)))
                .append("，候选时间=")
                .append(PriorityTraceLogHelper.formatDateTime(candidateDate))
                .append("，命中规则=").append(PriorityTraceLogHelper.safeText(hitRule))
                .append("，处理结果=").append(PriorityTraceLogHelper.safeText(result));
        PriorityTraceLogHelper.appendProcessLog(context, title, detailBuilder.toString());
    }
}
