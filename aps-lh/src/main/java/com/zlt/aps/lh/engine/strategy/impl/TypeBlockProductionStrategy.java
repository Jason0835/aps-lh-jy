/**
 * Copyright (c) 2008, 智立通（厦门）科技有限公司 All rights reserved。
 */
package com.zlt.aps.lh.engine.strategy.impl;

import com.zlt.aps.lh.api.constant.LhScheduleConstant;
import com.zlt.aps.lh.api.constant.LhScheduleParamConstant;
import com.zlt.aps.lh.api.domain.dto.MachineCleaningWindowDTO;
import com.zlt.aps.lh.api.domain.dto.MachineMaintenanceWindowDTO;
import com.zlt.aps.lh.api.domain.dto.MachineScheduleDTO;
import com.zlt.aps.lh.api.domain.dto.ShiftProductionControlDTO;
import com.zlt.aps.lh.api.domain.dto.ShiftRuntimeState;
import com.zlt.aps.lh.api.domain.dto.SkuDailyPlanQuotaDTO;
import com.zlt.aps.lh.api.domain.dto.SkuScheduleDTO;
import com.zlt.aps.lh.api.domain.entity.LhScheduleResult;
import com.zlt.aps.lh.api.domain.entity.LhUnscheduledResult;
import com.zlt.aps.lh.api.domain.vo.LhShiftConfigVO;
import com.zlt.aps.lh.api.enums.ScheduleTypeEnum;
import com.zlt.aps.lh.api.enums.SkuTagEnum;
import com.zlt.aps.lh.component.OrderNoGenerator;
import com.zlt.aps.lh.component.TargetScheduleQtyResolver;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.engine.strategy.ICapacityCalculateStrategy;
import com.zlt.aps.lh.engine.strategy.IEndingJudgmentStrategy;
import com.zlt.aps.lh.engine.strategy.IFirstInspectionBalanceStrategy;
import com.zlt.aps.lh.engine.strategy.IMouldChangeBalanceStrategy;
import com.zlt.aps.lh.engine.strategy.ITypeBlockProductionStrategy;
import com.zlt.aps.lh.engine.strategy.support.DailyMachineExpansionPlanner;
import com.zlt.aps.lh.service.impl.LhMaintenanceScheduleService;
import com.zlt.aps.lh.util.LeftRightMouldUtil;
import com.zlt.aps.lh.util.LhMachineHardMatchUtil;
import com.zlt.aps.lh.util.LhMouldCodeUtil;
import com.zlt.aps.lh.util.LhScheduleTimeUtil;
import com.zlt.aps.lh.util.LhSingleControlMachineUtil;
import com.zlt.aps.lh.util.LhSpecialMaterialUtil;
import com.zlt.aps.lh.util.LhSpecifyMachineUtil;
import com.zlt.aps.lh.util.MachineCleaningOverlapUtil;
import com.zlt.aps.lh.util.PriorityTraceLogHelper;
import com.zlt.aps.lh.util.ResultDowntimeSummaryUtil;
import com.zlt.aps.lh.util.ShiftCapacityResolverUtil;
import com.zlt.aps.lh.util.ShiftFieldUtil;
import com.zlt.aps.lh.util.ShiftProductionControlUtil;
import com.zlt.aps.lh.util.SingleMouldShiftQtyUtil;
import com.zlt.aps.lh.util.SkuDailyPlanQuotaUtil;
import com.zlt.aps.mdm.api.domain.entity.MdmDevicePlanShut;
import com.zlt.aps.mdm.api.domain.entity.MdmMaterialInfo;
import com.zlt.aps.mdm.api.domain.entity.MdmSkuMouldRel;
import com.zlt.aps.mdm.api.domain.entity.MdmSkuConstructionRef;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import javax.annotation.Resource;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 换活字块排产子策略。
 *
 * <p>业务定位：</p>
 * <ul>
 *   <li>在 S4.4 续作收尾后，根据机台收尾时间寻找可以更换活字块衔接的新增 SKU；</li>
 *   <li>优先使用收尾机台，必要时使用在机前规格兜底机台；</li>
 *   <li>候选 SKU 需要满足同胎胚、同模具、可更换活字块、机台硬性准入等条件；</li>
 *   <li>结果落地后仍作为排程结果进入统一日计划账本、胎胚库存和后置校验链路。</li>
 * </ul>
 *
 * <p>注意：本类只处理“无需走新增换模主链”的换活字块衔接。若候选物料存在定点机台或新增换模
 * 更适合承接，会回流到 S4.5 新增规格链路。</p>
 *
 * @author APS
 */
@Slf4j
@Component("typeBlockProductionStrategy")
public class TypeBlockProductionStrategy implements ITypeBlockProductionStrategy {

    private static final String CONTINUOUS_SCHEDULE_TYPE = ScheduleTypeEnum.CONTINUOUS.getCode();
    private static final String TYPE_BLOCK_CLEANING_ANALYSIS = "模具清洗+换活字块";
    private static final String TYPE_BLOCK_TRIGGER_ENDING = "收尾触发";
    private static final String TYPE_BLOCK_TRIGGER_FIRST_DAY_NO_PLAN_RELEASE =
            "续作首日无计划释放触发";
    private static final String TYPE_BLOCK_TRIGGER_SMALL_ENDING_SURPLUS_RELEASE =
            "续作收尾小余量释放触发";
    private static final String TYPE_BLOCK_TRIGGER_FALLBACK = "在机前规格兜底触发";
    private static final String TYPE_BLOCK_SKIP_REASON_T1_NOT_END =
            "T-1 最新记录未收尾，跳过兜底反查";
    private static final String TYPE_BLOCK_SKIP_REASON_LIMIT_SPECIFY_RESERVED =
            "机台存在需走新增换模链路的定点物料，当前阶段预留给S4.5";
    private static final String SHARED_EMBRYO_ZERO_SURPLUS_UNSCHEDULED_REASON =
            "共用胎胚且硫化余量为0";
    private static final String YES_FLAG = "1";
    private static final String NO_FLAG = "0";
    private static final String AUTO_DATA_SOURCE = "0";
    private static final int TYPE_BLOCK_SWITCH_MAX_ATTEMPTS = 16;

    @Resource
    private OrderNoGenerator orderNoGenerator;
    @Resource
    private IEndingJudgmentStrategy endingJudgmentStrategy;
    @Resource
    private TargetScheduleQtyResolver targetScheduleQtyResolver;
    @Resource
    private LhMaintenanceScheduleService maintenanceScheduleService;
    @Resource
    private IMouldChangeBalanceStrategy mouldChangeBalanceStrategy;
    @Resource
    private IFirstInspectionBalanceStrategy firstInspectionBalanceStrategy;
    @Resource
    private ICapacityCalculateStrategy capacityCalculateStrategy;

    /**
     * 执行换活字块排产。
     *
     * @param context 排程上下文
     */
    @Override
    public void scheduleTypeBlockChange(LhScheduleContext context) {
        log.info("换活字块排产开始, 机台数: {}", context.getMachineScheduleMap().size());

        List<LhShiftConfigVO> shifts = LhScheduleTimeUtil.getScheduleShifts(context, context.getScheduleDate());

        // 基于续作收尾回写后的真实收尾时间，按机台收尾先后衔接换活字块。
        // 只有已标记收尾且有预计完工时刻的机台，才代表当前活字块可切换到下一规格。
        List<MachineScheduleDTO> endingMachines = context.getMachineScheduleMap().values().stream()
                .filter(m -> m.isEnding() && m.getEstimatedEndTime() != null)
                .collect(Collectors.toList());
        endingMachines.sort(Comparator.comparing(MachineScheduleDTO::getEstimatedEndTime));
        Map<String, String> machineTriggerSourceMap = new HashMap<>(Math.max(16, endingMachines.size() * 2));
        List<MachineScheduleDTO> candidateMachines = new ArrayList<>(endingMachines);
        for (MachineScheduleDTO endingMachine : endingMachines) {
            machineTriggerSourceMap.put(endingMachine.getMachineCode(), TYPE_BLOCK_TRIGGER_ENDING);
        }

        List<MachineScheduleDTO> releasedMachines = resolveReleasedTypeBlockMachines(context);
        releasedMachines.sort(Comparator.comparing(
                MachineScheduleDTO::getEstimatedEndTime, Comparator.nullsLast(Comparator.naturalOrder())));
        for (MachineScheduleDTO releasedMachine : releasedMachines) {
            String machineCode = releasedMachine.getMachineCode();
            if (StringUtils.isEmpty(machineCode) || machineTriggerSourceMap.containsKey(machineCode)) {
                continue;
            }
            candidateMachines.add(releasedMachine);
            String triggerSource = resolveReleasedTypeBlockTriggerSource(context, machineCode);
            machineTriggerSourceMap.put(machineCode, triggerSource);
        }

        List<MachineScheduleDTO> fallbackMachines = resolveTypeBlockFallbackMachines(context);
        fallbackMachines.sort(Comparator.comparing(MachineScheduleDTO::getEstimatedEndTime));
        for (MachineScheduleDTO fallbackMachine : fallbackMachines) {
            String machineCode = fallbackMachine.getMachineCode();
            if (StringUtils.isEmpty(machineCode) || machineTriggerSourceMap.containsKey(machineCode)) {
                continue;
            }
            candidateMachines.add(fallbackMachine);
            machineTriggerSourceMap.put(machineCode, TYPE_BLOCK_TRIGGER_FALLBACK);
        }
        // 换活字块仅处理最新收尾时间落在第一天 T 日的机台，其他机台保留给 S4.5 新增排产。
        candidateMachines = filterFirstDayEndingMachines(context, candidateMachines);
        for (MachineScheduleDTO candidateMachine : candidateMachines) {
            String triggerSource = machineTriggerSourceMap.get(candidateMachine.getMachineCode());
            if (StringUtils.equals(TYPE_BLOCK_TRIGGER_FIRST_DAY_NO_PLAN_RELEASE, triggerSource)
                    || StringUtils.equals(TYPE_BLOCK_TRIGGER_SMALL_ENDING_SURPLUS_RELEASE, triggerSource)) {
                log.info("续作释放机台进入换活字块匹配, machineCode: {}, currentMaterialCode: {}, triggerSource: {}",
                        candidateMachine.getMachineCode(), candidateMachine.getCurrentMaterialCode(), triggerSource);
            }
        }
        traceEndingMachineOrder(context, candidateMachines, machineTriggerSourceMap);
        log.info("换活字块候选机台准备完成, 收尾机台: {}, 续作释放机台: {}, 兜底机台: {}, 候选机台: {}, 待排新增SKU: {}",
                endingMachines.size(), releasedMachines.size(), fallbackMachines.size(), candidateMachines.size(),
                context.getNewSpecSkuList().size());

        // completedMachineMap 记录本轮不再尝试的机台，避免同一机台在一轮中反复失败重试。
        Map<String, Boolean> completedMachineMap = new HashMap<>(Math.max(16, candidateMachines.size() * 2));
        // 已回流 S4.5 的物料不再被 S4.4 二次抢回，避免换活字块和新增换模主链重复争抢同一 SKU。
        Set<String> returnedToNewSpecMaterialCodes = new LinkedHashSet<String>(16);
        int typeBlockScheduledCount = 0;
        while (!CollectionUtils.isEmpty(context.getNewSpecSkuList())) {
            List<MachineScheduleDTO> activeMachines = buildActiveMachineList(
                    context, candidateMachines, machineTriggerSourceMap, completedMachineMap);
            if (CollectionUtils.isEmpty(activeMachines)) {
                log.warn("换活字块无可继续尝试机台, 待排新增SKU: {}, 已完成机台: {}",
                        context.getNewSpecSkuList().size(), completedMachineMap.size());
                break;
            }
            activeMachines.sort((leftMachine, rightMachine) -> compareTypeBlockMachine(
                    context, leftMachine, rightMachine, machineTriggerSourceMap));

            boolean scheduledInCurrentRound = false;
            for (MachineScheduleDTO machine : activeMachines) {
                String machineCode = machine.getMachineCode();
                SkuScheduleDTO limitSpecifySku = selectLimitSpecifySkuByMachine(context, machine);
                // 定点物料若更适合走新增换模主链，则当前收尾机台预留给 S4.5，不在 S4.4 抢先换活字块。
                if (shouldReserveMachineForSpecifyNewSpec(context, machine, limitSpecifySku, shifts)) {
                    completedMachineMap.put(machineCode, true);
                    log.info("收尾机台预留给定点物料新增换模链路, machineCode: {}, materialCode: {}",
                            machineCode, limitSpecifySku.getMaterialCode());
                    continue;
                }
                SkuScheduleDTO specifySku = isTypeBlockCandidate(context, machine, limitSpecifySku)
                        ? limitSpecifySku : null;
                if (specifySku != null && StringUtils.isNotEmpty(specifySku.getMaterialCode())
                        && returnedToNewSpecMaterialCodes.contains(specifySku.getMaterialCode())) {
                    completedMachineMap.put(machineCode, true);
                    log.info("定点换活字块SKU已回流新增排产，跳过S4.4二次承接, machineCode: {}, materialCode: {}",
                            machineCode, specifySku.getMaterialCode());
                    continue;
                }
                if (specifySku != null && appendSpecifyTypeBlockResult(
                        context, machine, specifySku, shifts, completedMachineMap, activeMachines)) {
                    clearSpecifyReservation(context, machineCode, specifySku.getMaterialCode());
                    // 如果定点换活字块单台不足，剩余量仍保留在新增待排列表，后续统一交给 S4.5 补机台。
                    collectReturnedToNewSpecMaterial(returnedToNewSpecMaterialCodes, context, specifySku);
                    scheduledInCurrentRound = true;
                    typeBlockScheduledCount++;
                    break;
                }

                // 基于同胎胚、同模具、同主花纹等条件筛选可换活字块候选。
                // 该阶段只允许不更换整套模具的轻量衔接，完整换模能力评估必须留给 S4.5 新增主链。
                List<SkuScheduleDTO> typeBlockCandidates = filterTypeBlockCandidates(
                        context, machine, returnedToNewSpecMaterialCodes);
                if (StringUtils.equals(TYPE_BLOCK_TRIGGER_FIRST_DAY_NO_PLAN_RELEASE,
                        machineTriggerSourceMap.get(machineCode))) {
                    log.info("释放机台换活字块候选SKU列表, machineCode: {}, currentMaterialCode: {}, candidates: {}",
                            machineCode, machine.getCurrentMaterialCode(), buildSkuCodeSummary(typeBlockCandidates));
                }
                SkuScheduleDTO typeBlockSku = selectPreferredSkuFromCandidates(typeBlockCandidates);
                String matchedLayer = !CollectionUtils.isEmpty(typeBlockCandidates) ? "同胎胚+同模具" : "未命中";
                if (typeBlockSku == null) {
                    log.debug("换活字块未匹配到SKU, 机台: {}, 触发来源: {}, 候选数: {}",
                            machineCode, machineTriggerSourceMap.get(machineCode),
                            typeBlockCandidates.size());
                    traceTypeBlockDecision(context, machine, typeBlockCandidates,
                            null, matchedLayer, false, null, null, machineTriggerSourceMap.get(machineCode),
                            "未匹配到满足换活字块条件的候选SKU");
                    completedMachineMap.put(machineCode, true);
                    continue;
                }
                // 候选SKU如需完整换模/首检能力评估，则让渡给新增排产链路。
                if (shouldReserveMachineForNewSpecPath(context, machine, typeBlockSku, shifts)) {
                    completedMachineMap.put(machineCode, true);
                    log.info("候选SKU需走新增换模主链，当前阶段预留机台, machineCode: {}, materialCode: {}",
                            machineCode, typeBlockSku.getMaterialCode());
                    continue;
                }
                if (endingJudgmentStrategy.isEnding(context, typeBlockSku)) {
                    getMaintenanceScheduleService().tryAttachMaintenanceAfterFirstEnding(
                            context, machine, machine.getEstimatedEndTime());
                }
                // 换活字块切换起点需要避开晚班不可换模、保养、停机等窗口。
                Date typeBlockSwitchStartTime = allocateTypeBlockSwitchStartTime(
                        context, machine, typeBlockSku, machine.getEstimatedEndTime());
                Date typeBlockStartTime = resolveTypeBlockProductionStartTime(
                        context, machine, machine.getEstimatedEndTime(), typeBlockSwitchStartTime);
                int eligibleMachineCount = countEligibleTypeBlockMachines(context, typeBlockSku, activeMachines);
                // 换活字块本阶段不主动扩多台；eligibleMachineCount 只用于单台目标量口径判断。
                StringBuilder failureReason = new StringBuilder(128);
                boolean success = appendTypeBlockResultWithRollback(
                        context, machine, typeBlockSku, typeBlockStartTime, typeBlockSwitchStartTime, shifts,
                        eligibleMachineCount == 1, failureReason);
                traceTypeBlockDecision(context, machine, typeBlockCandidates,
                        typeBlockSku, matchedLayer, success, typeBlockSwitchStartTime, typeBlockStartTime,
                        machineTriggerSourceMap.get(machineCode), failureReason.toString());
                if (!success) {
                    log.warn("换活字块排产失败, 机台: {}, materialCode: {}, 结构: {}, 开始时间: {}, 匹配层级: {}, 失败原因: {}",
                            machineCode, typeBlockSku.getMaterialCode(), typeBlockSku.getStructureName(),
                            LhScheduleTimeUtil.formatDateTime(typeBlockStartTime), matchedLayer,
                            StringUtils.isNotEmpty(failureReason.toString()) ? failureReason.toString() : "-");
                    completedMachineMap.put(machineCode, true);
                    continue;
                }
                if (StringUtils.equals(TYPE_BLOCK_TRIGGER_FIRST_DAY_NO_PLAN_RELEASE,
                        machineTriggerSourceMap.get(machineCode))) {
                    log.info("释放机台换活字块最终选中SKU, machineCode: {}, sourceMaterialCode: {}, selectedMaterialCode: {}",
                            machineCode, machine.getPreviousMaterialCode(), typeBlockSku.getMaterialCode());
                }
                scheduledInCurrentRound = true;
                typeBlockScheduledCount++;
                collectReturnedToNewSpecMaterial(returnedToNewSpecMaterialCodes, context, typeBlockSku);
                if (!machine.isEnding()) {
                    completedMachineMap.put(machineCode, true);
                }
                // 每轮仅落一条结果，随后按更新后的机台收尾时间重新排序，避免旧排序继续影响后续衔接。
                // 这样同一机台连续换活字块时，会按最新完工时刻重新参与下一轮竞争。
                break;
            }
            if (!scheduledInCurrentRound) {
                log.warn("本轮换活字块未产生排程结果, 候选机台: {}, 待排新增SKU: {}",
                        activeMachines.size(), context.getNewSpecSkuList().size());
                break;
            }
        }
        log.info("换活字块排产结束, 新增结果数: {}, 剩余新增SKU: {}, 当前排程结果数: {}",
                typeBlockScheduledCount, context.getNewSpecSkuList().size(),
                context.getScheduleResultList().size());
    }

    /**
     * 过滤只允许在排程窗口第一天 T 日收尾的换活字块机台。
     *
     * @param context 排程上下文
     * @param candidateMachines 原候选机台
     * @return T 日收尾候选机台
     */
    private List<MachineScheduleDTO> filterFirstDayEndingMachines(
            LhScheduleContext context, List<MachineScheduleDTO> candidateMachines) {
        if (CollectionUtils.isEmpty(candidateMachines)) {
            return new ArrayList<MachineScheduleDTO>(0);
        }
        List<MachineScheduleDTO> firstDayMachines = new ArrayList<>(candidateMachines.size());
        List<String> skippedMachineSummaries = new ArrayList<>(candidateMachines.size());
        for (MachineScheduleDTO machine : candidateMachines) {
            if (isFirstDayEndingMachine(context, machine)) {
                firstDayMachines.add(machine);
                continue;
            }
            skippedMachineSummaries.add(String.format("%s@%s",
                    machine == null ? "-" : machine.getMachineCode(),
                    machine == null ? "-" : LhScheduleTimeUtil.formatDateTime(machine.getEstimatedEndTime())));
        }
        if (!CollectionUtils.isEmpty(skippedMachineSummaries)) {
            log.info("非T日收尾机台跳过换活字块, T日: {}, 过滤前候选: {}, 跳过机台数: {}, 跳过明细: {}",
                    LhScheduleTimeUtil.formatDate(context.getScheduleDate()), candidateMachines.size(),
                    skippedMachineSummaries.size(), String.join(",", skippedMachineSummaries));
        }
        return firstDayMachines;
    }

    /**
     * 判断机台最新预计收尾时间是否落在排程窗口第一天 T 日。
     *
     * @param context 排程上下文
     * @param machine 机台
     * @return true-T 日收尾，false-非 T 日收尾
     */
    private boolean isFirstDayEndingMachine(LhScheduleContext context, MachineScheduleDTO machine) {
        if (Objects.isNull(context)
                || Objects.isNull(context.getScheduleDate())
                || Objects.isNull(machine)
                || Objects.isNull(machine.getEstimatedEndTime())) {
            return false;
        }
        LocalDate firstDay = context.getScheduleDate().toInstant()
                .atZone(ZoneId.systemDefault()).toLocalDate();
        LocalDate endingDay = machine.getEstimatedEndTime().toInstant()
                .atZone(ZoneId.systemDefault()).toLocalDate();
        return firstDay.equals(endingDay);
    }

    /**
     * 构建本轮可尝试的机台列表。
     *
     * @param context 排程上下文
     * @param candidateMachines 候选机台
     * @param machineTriggerSourceMap 机台触发来源
     * @param completedMachineMap 已完成机台
     * @return 可尝试机台
     */
    private List<MachineScheduleDTO> buildActiveMachineList(LhScheduleContext context,
                                                            List<MachineScheduleDTO> candidateMachines,
                                                            Map<String, String> machineTriggerSourceMap,
                                                            Map<String, Boolean> completedMachineMap) {
        List<MachineScheduleDTO> activeMachines = new ArrayList<>(candidateMachines.size());
        for (MachineScheduleDTO machine : candidateMachines) {
            if (machine == null || StringUtils.isEmpty(machine.getMachineCode())) {
                continue;
            }
            String machineCode = machine.getMachineCode();
            if (Boolean.TRUE.equals(completedMachineMap.get(machineCode))) {
                continue;
            }
            if (!isFirstDayEndingMachine(context, machine)) {
                completedMachineMap.put(machineCode, true);
                log.info("机台最新收尾时间已不在T日，停止换活字块并交由新增排产, machineCode: {}, estimatedEndTime: {}, T日: {}",
                        machineCode, LhScheduleTimeUtil.formatDateTime(machine.getEstimatedEndTime()),
                        LhScheduleTimeUtil.formatDate(context.getScheduleDate()));
                continue;
            }
            String triggerSource = machineTriggerSourceMap.get(machineCode);
            if (StringUtils.equals(TYPE_BLOCK_TRIGGER_ENDING, triggerSource) && !machine.isEnding()) {
                completedMachineMap.put(machineCode, true);
                continue;
            }
            activeMachines.add(machine);
        }
        return activeMachines;
    }

    /**
     * 比较换活字块候选机台顺序。
     *
     * @param leftMachine 左机台
     * @param rightMachine 右机台
     * @param machineTriggerSourceMap 机台触发来源
     * @return 排序结果
     */
    private int compareTypeBlockMachine(LhScheduleContext context,
                                        MachineScheduleDTO leftMachine,
                                        MachineScheduleDTO rightMachine,
                                        Map<String, String> machineTriggerSourceMap) {
        String leftTriggerSource = machineTriggerSourceMap.get(leftMachine.getMachineCode());
        String rightTriggerSource = machineTriggerSourceMap.get(rightMachine.getMachineCode());
        int triggerOrderCompare = Integer.compare(
                resolveTypeBlockTriggerOrder(leftTriggerSource),
                resolveTypeBlockTriggerOrder(rightTriggerSource));
        if (triggerOrderCompare != 0) {
            return triggerOrderCompare;
        }
        Date leftReadyTime = resolveTypeBlockSortReadyTime(context, leftMachine);
        Date rightReadyTime = resolveTypeBlockSortReadyTime(context, rightMachine);
        if (leftReadyTime == null && rightReadyTime != null) {
            return 1;
        }
        if (leftReadyTime != null && rightReadyTime == null) {
            return -1;
        }
        if (leftReadyTime != null && rightReadyTime != null) {
            int readyTimeCompare = leftReadyTime.compareTo(rightReadyTime);
            if (readyTimeCompare != 0) {
                return readyTimeCompare;
            }
        }
        Date leftEndTime = leftMachine.getEstimatedEndTime();
        Date rightEndTime = rightMachine.getEstimatedEndTime();
        if (leftEndTime == null && rightEndTime == null) {
            return compareMachineIdentity(leftMachine, rightMachine);
        }
        if (leftEndTime == null) {
            return 1;
        }
        if (rightEndTime == null) {
            return -1;
        }
        int endTimeCompare = leftEndTime.compareTo(rightEndTime);
        if (endTimeCompare != 0) {
            return endTimeCompare;
        }
        return compareMachineIdentity(leftMachine, rightMachine);
    }

    /**
     * 追加定点物料换活字块结果。
     *
     * @param context 排程上下文
     * @param machine 机台
     * @param specifySku 定点物料
     * @param shifts 班次
     * @param completedMachineMap 已完成机台
     * @param activeMachines 当前轮可尝试机台
     * @return true-追加成功
     */
    private boolean appendSpecifyTypeBlockResult(LhScheduleContext context,
                                                 MachineScheduleDTO machine,
                                                 SkuScheduleDTO specifySku,
                                                 List<LhShiftConfigVO> shifts,
                                                 Map<String, Boolean> completedMachineMap,
                                                 List<MachineScheduleDTO> activeMachines) {
        Date typeBlockSwitchStartTime = allocateTypeBlockSwitchStartTime(
                context, machine, specifySku, machine.getEstimatedEndTime());
        Date typeBlockStartTime = resolveTypeBlockProductionStartTime(
                context, machine, machine.getEstimatedEndTime(), typeBlockSwitchStartTime);
        int eligibleMachineCount = countEligibleTypeBlockMachines(context, specifySku, activeMachines);
        StringBuilder failureReason = new StringBuilder(128);
        boolean success = appendTypeBlockResultWithRollback(
                context, machine, specifySku, typeBlockStartTime, typeBlockSwitchStartTime, shifts,
                eligibleMachineCount == 1, failureReason);
        if (success) {
            if (!machine.isEnding()) {
                completedMachineMap.put(machine.getMachineCode(), true);
            }
            log.info("收尾机台命中定点物料衔接, machineCode: {}, materialCode: {}, startTime: {}",
                    machine.getMachineCode(), specifySku.getMaterialCode(),
                    LhScheduleTimeUtil.formatDateTime(typeBlockStartTime));
            return true;
        }
        log.debug("定点物料衔接失败，继续原衔接匹配, machineCode: {}, materialCode: {}, 失败原因: {}",
                machine.getMachineCode(), specifySku.getMaterialCode(),
                StringUtils.isNotEmpty(failureReason.toString()) ? failureReason.toString() : "-");
        return false;
    }

    /**
     * 解析换活字块触发来源排序。
     *
     * @param triggerSource 触发来源
     * @return 排序值
     */
    private int resolveTypeBlockTriggerOrder(String triggerSource) {
        if (StringUtils.equals(TYPE_BLOCK_TRIGGER_ENDING, triggerSource)) {
            return 0;
        }
        if (StringUtils.equals(TYPE_BLOCK_TRIGGER_FIRST_DAY_NO_PLAN_RELEASE, triggerSource)) {
            return 1;
        }
        if (StringUtils.equals(TYPE_BLOCK_TRIGGER_FALLBACK, triggerSource)) {
            return 2;
        }
        return 3;
    }

    /**
     * 判断机台是否需要预留给需走新增换模链路的定点物料。
     *
     * @param context 排程上下文
     * @param machine 当前机台
     * @param specifySku 定点物料
     * @param shifts 排程窗口班次
     * @return true-当前阶段应预留，false-不预留
     */
    private boolean shouldReserveMachineForSpecifyNewSpec(LhScheduleContext context,
                                                          MachineScheduleDTO machine,
                                                          SkuScheduleDTO specifySku,
                                                          List<LhShiftConfigVO> shifts) {
        if (specifySku == null || !shouldPreferNewSpecPath(context, machine, specifySku)) {
            return false;
        }
        boolean schedulable = canScheduleSpecifySkuByNewSpecPath(
                context, machine, specifySku, shifts, machine.getEstimatedEndTime());
        if (!schedulable) {
            return false;
        }
        log.debug("机台命中需走新增换模链路的定点物料预留, machineCode: {}, materialCode: {}, reason: {}",
                machine.getMachineCode(), specifySku.getMaterialCode(), TYPE_BLOCK_SKIP_REASON_LIMIT_SPECIFY_RESERVED);
        return true;
    }

    /**
     * 判断普通候选是否应预留到新增换模主链处理。
     *
     * @param context 排程上下文
     * @param machine 当前机台
     * @param sku 候选SKU
     * @param shifts 排程窗口班次
     * @return true-当前阶段应预留，false-仍可在S4.4处理
     */
    private boolean shouldReserveMachineForNewSpecPath(LhScheduleContext context,
                                                       MachineScheduleDTO machine,
                                                       SkuScheduleDTO sku,
                                                       List<LhShiftConfigVO> shifts) {
        if (sku == null || !shouldPreferNewSpecPath(context, machine, sku)) {
            return false;
        }
        return canScheduleSpecifySkuByNewSpecPath(
                context, machine, sku, shifts, machine.getEstimatedEndTime());
    }

    /**
     * 选择当前机台配置的限制作业定点SKU。
     *
     * @param context 排程上下文
     * @param machine 当前机台
     * @return 定点SKU，未命中返回null
     */
    private SkuScheduleDTO selectLimitSpecifySkuByMachine(LhScheduleContext context, MachineScheduleDTO machine) {
        if (context == null || machine == null || StringUtils.isEmpty(machine.getMachineCode())
                || CollectionUtils.isEmpty(context.getNewSpecSkuList())) {
            return null;
        }
        String machineCode = machine.getMachineCode();
        for (SkuScheduleDTO sku : context.getNewSpecSkuList()) {
            if (sku == null || StringUtils.isEmpty(sku.getMaterialCode()) || sku.resolveTargetScheduleQty() <= 0) {
                continue;
            }
            if (LhSpecifyMachineUtil.isLimitSpecifyMachine(context, machineCode, sku.getMaterialCode())) {
                return sku;
            }
        }
        return null;
    }

    /**
     * 清理定点机台挤量预留信息。
     *
     * @param context 排程上下文
     * @param machineCode 机台编码
     * @param materialCode 物料编码
     */
    private void clearSpecifyReservation(LhScheduleContext context, String machineCode, String materialCode) {
        if (context == null || StringUtils.isEmpty(machineCode)) {
            return;
        }
        String reservedMaterialCode = context.getSpecifyMachineReservedMaterialMap().get(machineCode);
        if (StringUtils.isEmpty(materialCode) || StringUtils.equals(materialCode, reservedMaterialCode)) {
            context.getSpecifyMachineReservedMaterialMap().remove(machineCode);
            context.getSpecifyMachineReservedSwitchStartTimeMap().remove(machineCode);
        }
    }

    /**
     * 过滤满足换活字块条件的候选SKU。
     *
     * @param context 排程上下文
     * @param machine 机台
     * @return 候选SKU
     */
    private List<SkuScheduleDTO> filterTypeBlockCandidates(LhScheduleContext context,
                                                           MachineScheduleDTO machine,
                                                           Set<String> returnedToNewSpecMaterialCodes) {
        List<SkuScheduleDTO> candidateList = new ArrayList<>(context.getNewSpecSkuList().size());
        for (SkuScheduleDTO sku : context.getNewSpecSkuList()) {
            if (sku != null && !CollectionUtils.isEmpty(returnedToNewSpecMaterialCodes)
                    && returnedToNewSpecMaterialCodes.contains(sku.getMaterialCode())) {
                continue;
            }
            if (isTypeBlockCandidate(context, machine, sku, false)) {
                candidateList.add(sku);
            }
        }
        return candidateList;
    }

    /**
     * 记录已由换活字块首台承接但仍需回流 S4.5 的物料，避免 S4.4 再次按换活字块扩机。
     *
     * @param returnedToNewSpecMaterialCodes 回流新增排产物料集合
     * @param context 排程上下文
     * @param sku 当前 SKU
     */
    private void collectReturnedToNewSpecMaterial(Set<String> returnedToNewSpecMaterialCodes,
                                                  LhScheduleContext context,
                                                  SkuScheduleDTO sku) {
        if (returnedToNewSpecMaterialCodes == null
                || context == null || sku == null || StringUtils.isEmpty(sku.getMaterialCode())) {
            return;
        }
        if (context.getNewSpecSkuList().contains(sku) && sku.getRemainingScheduleQty() > 0) {
            returnedToNewSpecMaterialCodes.add(sku.getMaterialCode());
        }
    }

    /**
     * 按月度计划SKU排序结果选择候选首位。
     *
     * @param candidates 候选SKU
     * @return 选中SKU
     */
    private SkuScheduleDTO selectPreferredSkuFromCandidates(List<SkuScheduleDTO> candidates) {
        if (CollectionUtils.isEmpty(candidates)) {
            return null;
        }
        return candidates.get(0);
    }

    /**
     * 判断SKU是否满足换活字块条件：同胎胚且同模具。
     *
     * @param context 排程上下文
     * @param machine 机台
     * @param sku SKU
     * @return true-满足条件
     */
    private boolean isTypeBlockCandidate(LhScheduleContext context,
                                         MachineScheduleDTO machine,
                                         SkuScheduleDTO sku) {
        return isTypeBlockCandidate(context, machine, sku, true);
    }

    /**
     * 判断SKU是否满足换活字块条件。
     * <p>条件：同胎胚且同模具，则允许换活字块。</p>
     *
     * @param context 排程上下文
     * @param machine 机台
     * @param sku SKU
     * @param writeDecisionLog 是否输出判断日志
     * @return true-满足条件
     */
    private boolean isTypeBlockCandidate(LhScheduleContext context,
                                         MachineScheduleDTO machine,
                                         SkuScheduleDTO sku,
                                         boolean writeDecisionLog) {
        if (sku == null) {
            return false;
        }
        if (!isMachineHardMatched(context, machine, sku)) {
            log.debug("换活字块候选SKU未通过机台硬性准入, machineCode: {}, materialCode: {}",
                    machine == null ? null : machine.getMachineCode(), sku.getMaterialCode());
            return false;
        }
        return canChangeLetterBlock(context, machine, sku, writeDecisionLog);
    }

    /**
     * 判断机台当前物料与候选SKU是否为相同胎胚。
     *
     * @param context 排程上下文
     * @param machine 机台
     * @param sku SKU
     * @return true-相同胎胚
     */
    private boolean isSameEmbryo(LhScheduleContext context, MachineScheduleDTO machine, SkuScheduleDTO sku) {
        return isSameCarcass(context, machine, sku);
    }

    /**
     * 判断机台当前物料与候选SKU是否为相同胎胚描述。
     *
     * @param context 排程上下文
     * @param machine 机台
     * @param sku SKU
     * @return true-相同胎胚描述
     */
    private boolean isSameEmbryoDesc(LhScheduleContext context, MachineScheduleDTO machine, SkuScheduleDTO sku) {
        String machineEmbryoDesc = normalizeCompareToken(resolveMachineEmbryoDesc(context, machine));
        String skuEmbryoDesc = normalizeCompareToken(resolveSkuEmbryoDesc(context, sku));
        return StringUtils.isNotEmpty(machineEmbryoDesc)
                && StringUtils.equals(machineEmbryoDesc, skuEmbryoDesc);
    }

    /**
     * 判断机台当前物料与候选SKU是否同胎胚。
     * <p>胎胚代码和胎胚描述只要命中其一即可。</p>
     *
     * @param context 排程上下文
     * @param machine 机台
     * @param sku SKU
     * @return true-同胎胚
     */
    private boolean isSameCarcass(LhScheduleContext context, MachineScheduleDTO machine, SkuScheduleDTO sku) {
        String machineEmbryoCode = normalizeCompareToken(resolveMachineEmbryoCode(context, machine));
        String skuEmbryoCode = normalizeCompareToken(sku == null ? null : sku.getEmbryoCode());
        boolean sameEmbryoCode = StringUtils.isNotEmpty(machineEmbryoCode)
                && StringUtils.equals(machineEmbryoCode, skuEmbryoCode);
        if (sameEmbryoCode) {
            return true;
        }
        String machineEmbryoDesc = normalizeCompareToken(resolveMachineEmbryoDesc(context, machine));
        String skuEmbryoDesc = normalizeCompareToken(resolveSkuEmbryoDesc(context, sku));
        return StringUtils.isNotEmpty(machineEmbryoDesc)
                && StringUtils.equals(machineEmbryoDesc, skuEmbryoDesc);
    }

    /**
     * 判断机台当前物料与候选SKU是否存在相同模具。
     *
     * @param context 排程上下文
     * @param machine 机台
     * @param sku SKU
     * @return true-同模具
     */
    private boolean isSameMold(LhScheduleContext context, MachineScheduleDTO machine, SkuScheduleDTO sku) {
        if (machine == null || sku == null) {
            return false;
        }
        Set<String> machineMouldCodeSet = resolveMouldCodeSet(context, machine.getCurrentMaterialCode());
        Set<String> skuMouldCodeSet = resolveMouldCodeSet(context, sku.getMaterialCode());
        if (CollectionUtils.isEmpty(machineMouldCodeSet) || CollectionUtils.isEmpty(skuMouldCodeSet)) {
            return false;
        }
        for (String mouldCode : machineMouldCodeSet) {
            if (skuMouldCodeSet.contains(mouldCode)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断当前在机SKU与候选SKU是否允许换活字块。
     * <p>条件：同胎胚且同模具，则允许换活字块。</p>
     *
     * @param context 排程上下文
     * @param machine 机台
     * @param sku SKU
     * @param writeDecisionLog 是否输出判断日志
     * @return true-允许换活字块
     */
    private boolean canChangeLetterBlock(LhScheduleContext context,
                                         MachineScheduleDTO machine,
                                         SkuScheduleDTO sku,
                                         boolean writeDecisionLog) {
        String machineEmbryoCode = normalizeCompareToken(resolveMachineEmbryoCode(context, machine));
        String skuEmbryoCode = normalizeCompareToken(sku == null ? null : sku.getEmbryoCode());
        String machineEmbryoDesc = normalizeCompareToken(resolveMachineEmbryoDesc(context, machine));
        String skuEmbryoDesc = normalizeCompareToken(resolveSkuEmbryoDesc(context, sku));
        Set<String> machineMouldCodeSet = machine == null
                ? new LinkedHashSet<>(0) : resolveMouldCodeSet(context, machine.getCurrentMaterialCode());
        Set<String> skuMouldCodeSet = sku == null
                ? new LinkedHashSet<>(0) : resolveMouldCodeSet(context, sku.getMaterialCode());
        boolean sameCarcass = (StringUtils.isNotEmpty(machineEmbryoCode)
                && StringUtils.equals(machineEmbryoCode, skuEmbryoCode))
                || (StringUtils.isNotEmpty(machineEmbryoDesc)
                && StringUtils.equals(machineEmbryoDesc, skuEmbryoDesc));
        boolean sameMold = false;
        if (!CollectionUtils.isEmpty(machineMouldCodeSet) && !CollectionUtils.isEmpty(skuMouldCodeSet)) {
            for (String mouldCode : machineMouldCodeSet) {
                if (skuMouldCodeSet.contains(mouldCode)) {
                    sameMold = true;
                    break;
                }
            }
        }
        boolean matched = sameCarcass && sameMold;
        if (writeDecisionLog) {
            log.info("[换活字块匹配判断] 机台编码: {}, 在机SKU: {}, 候选SKU: {}, 在机胎胚代码: {}, 候选胎胚代码: {}, "
                            + "在机胎胚描述: {}, 候选胎胚描述: {}, 同胎胚: {}, 在机模具号集合: {}, 候选模具号集合: {}, "
                            + "同模具: {}, 是否可换活字块: {}",
                    machine == null ? null : machine.getMachineCode(),
                    machine == null ? null : machine.getCurrentMaterialCode(),
                    sku == null ? null : sku.getMaterialCode(),
                    machineEmbryoCode, skuEmbryoCode, machineEmbryoDesc, skuEmbryoDesc,
                    sameCarcass, machineMouldCodeSet, skuMouldCodeSet, sameMold,
                    matched);
        }
        return matched;
    }

    /**
     * 判断机台当前物料与候选SKU是否为相同主花纹。
     *
     * @param context 排程上下文
     * @param machine 机台
     * @param sku SKU
     * @return true-相同主花纹
     */
    private boolean isSameMainPatternStrict(LhScheduleContext context, MachineScheduleDTO machine, SkuScheduleDTO sku) {
        String machineMainPattern = resolveMachineMainPatternStrict(context, machine);
        String skuMainPattern = resolveSkuMainPatternStrict(context, sku);
        // 主花纹按严格口径比较，不回退到普通花纹字段。
        return StringUtils.isNotEmpty(machineMainPattern)
                && StringUtils.equals(machineMainPattern, skuMainPattern);
    }

    /**
     * 判断机台当前物料与候选SKU是否为相同规格。
     *
     * @param context 排程上下文
     * @param machine 机台
     * @param sku SKU
     * @return true-相同规格
     */
    private boolean isSameSpec(LhScheduleContext context, MachineScheduleDTO machine, SkuScheduleDTO sku) {
        String machineSpecCode = normalizeCompareToken(resolveMachineSpecCode(context, machine));
        String skuSpecCode = normalizeCompareToken(sku.getSpecCode());
        return StringUtils.isNotEmpty(machineSpecCode)
                && StringUtils.equals(machineSpecCode, skuSpecCode);
    }

    /**
     * 计算换活字块开产时间。
     *
     * @param context 排程上下文
     * @param machine 机台
     * @return 开产时间
     */
    private Date calcTypeBlockStartTime(LhScheduleContext context, MachineScheduleDTO machine) {
        if (machine == null) {
            return null;
        }
        Date switchStartTime = calcTypeBlockSwitchStartTime(context, machine, machine.getEstimatedEndTime());
        return resolveTypeBlockProductionStartTime(context, machine, machine.getEstimatedEndTime(), switchStartTime);
    }

    private Date calcTypeBlockSwitchStartTime(LhScheduleContext context, MachineScheduleDTO machine) {
        if (machine == null) {
            return null;
        }
        return calcTypeBlockSwitchStartTime(context, machine, machine.getEstimatedEndTime());
    }

    /**
     * 基于指定收尾时间计算换活字块开产时间。
     *
     * @param context 排程上下文
     * @param machine 机台
     * @param estimatedEndTime 预计收尾时间
     * @return 开产时间
     */
    private Date calcTypeBlockStartTime(LhScheduleContext context,
                                        MachineScheduleDTO machine,
                                        Date estimatedEndTime) {
        if (machine == null || estimatedEndTime == null) {
            return null;
        }
        Date switchStartTime = calcTypeBlockSwitchStartTime(context, machine, estimatedEndTime);
        return resolveTypeBlockProductionStartTime(context, machine, estimatedEndTime, switchStartTime);
    }

    /**
     * 基于指定收尾时间计算换活字块开始时间。
     *
     * @param context 排程上下文
     * @param machine 机台
     * @param estimatedEndTime 预计收尾时间
     * @return 换活字块开始时间
     */
    private Date calcTypeBlockSwitchStartTime(LhScheduleContext context,
                                              MachineScheduleDTO machine,
                                              Date estimatedEndTime) {
        if (machine == null || estimatedEndTime == null) {
            return null;
        }
        return allocateTypeBlockSwitchStartTime(context, machine, estimatedEndTime);
    }

    /**
     * 基于指定收尾时间分配换活字块开始时间。
     *
     * @param context 排程上下文
     * @param machine 机台
     * @param estimatedEndTime 预计收尾时间
     * @return 换活字块开始时间
     */
    private Date allocateTypeBlockSwitchStartTime(LhScheduleContext context,
                                                  MachineScheduleDTO machine,
                                                  Date estimatedEndTime) {
        return allocateTypeBlockSwitchStartTime(context, machine, null, estimatedEndTime);
    }

    /**
     * 基于指定收尾时间分配换活字块开始时间。
     *
     * @param context 排程上下文
     * @param machine 机台
     * @param sku 当前换活字块SKU
     * @param estimatedEndTime 预计收尾时间
     * @return 换活字块开始时间
     */
    private Date allocateTypeBlockSwitchStartTime(LhScheduleContext context,
                                                  MachineScheduleDTO machine,
                                                  SkuScheduleDTO sku,
                                                  Date estimatedEndTime) {
        if (machine == null || estimatedEndTime == null) {
            return null;
        }
        Date switchReadyTime = resolveTypeBlockSwitchReadyTime(context, machine, estimatedEndTime);
        if (switchReadyTime == null) {
            return null;
        }
        int switchDurationHours = resolveTypeBlockSwitchDurationHours(
                context, machine, estimatedEndTime, switchReadyTime);
        return getMouldChangeBalanceStrategy().allocateMouldChange(
                context,
                machine.getMachineCode(),
                switchReadyTime,
                switchDurationHours,
                sku,
                IMouldChangeBalanceStrategy.ACTION_TYPE_BLOCK_CHANGE);
    }

    /**
     * 基于指定收尾时间计算换活字块理论就绪时间。
     *
     * @param context 排程上下文
     * @param machine 机台
     * @param estimatedEndTime 预计收尾时间
     * @return 理论可切换时间
     */
    private Date resolveTypeBlockSwitchReadyTime(LhScheduleContext context,
                                                 MachineScheduleDTO machine,
                                                 Date estimatedEndTime) {
        if (machine == null || estimatedEndTime == null) {
            return null;
        }
        Date rawSwitchStartTime = resolveAllowedSwitchStartTime(
                context, machine.getMachineCode(), estimatedEndTime);
        if (rawSwitchStartTime == null) {
            return null;
        }
        Date switchReadyTime;
        if (getMaintenanceScheduleService().shouldApplyMaintenanceOverlapSwitchRule(context, machine, rawSwitchStartTime)) {
            switchReadyTime = getMaintenanceScheduleService().resolveMaintenanceEndTime(context, machine);
        } else {
            switchReadyTime = getMaintenanceScheduleService().delaySwitchStartByMaintenance(
                    machine, rawSwitchStartTime, LhScheduleTimeUtil.getTypeBlockChangeTotalHours(context));
        }
        switchReadyTime = ShiftProductionControlUtil.resolveEarliestSwitchStartTime(context, switchReadyTime);
        return getMaintenanceScheduleService().delaySwitchStartByMaintenance(
                machine, switchReadyTime, LhScheduleTimeUtil.getTypeBlockChangeTotalHours(context));
    }

    private Date resolveTypeBlockSortReadyTime(LhScheduleContext context, MachineScheduleDTO machine) {
        if (machine == null || machine.getEstimatedEndTime() == null) {
            return null;
        }
        return resolveTypeBlockSwitchReadyTime(context, machine, machine.getEstimatedEndTime());
    }

    private int resolveTypeBlockSwitchDurationHours(LhScheduleContext context,
                                                    MachineScheduleDTO machine,
                                                    Date estimatedEndTime,
                                                    Date switchStartTime) {
        if (isTypeBlockMaintenanceOverlapSwitch(context, machine, estimatedEndTime, switchStartTime)) {
            return LhScheduleTimeUtil.getMaintenanceOverlapSwitchHours(context);
        }
        return LhScheduleTimeUtil.getTypeBlockChangeTotalHours(context);
    }

    /**
     * 基于换活字块开始时间计算开产时间。
     *
     * @param context 排程上下文
     * @param machine 机台
     * @param estimatedEndTime 预计收尾时间
     * @param switchStartTime 换活字块开始时间
     * @return 开产时间
     */
    private Date resolveTypeBlockProductionStartTime(LhScheduleContext context,
                                                     MachineScheduleDTO machine,
                                                     Date estimatedEndTime,
                                                     Date switchStartTime) {
        if (switchStartTime == null) {
            return null;
        }
        if (isTypeBlockMaintenanceOverlapSwitch(context, machine, estimatedEndTime, switchStartTime)) {
            Date inspectionStartTime = LhScheduleTimeUtil.addHours(
                    switchStartTime, LhScheduleTimeUtil.getMaintenanceOverlapSwitchHours(context));
            return LhScheduleTimeUtil.addHours(
                    inspectionStartTime, LhScheduleTimeUtil.getFirstInspectionHours(context));
        }
        return LhScheduleTimeUtil.addHours(switchStartTime,
                LhScheduleTimeUtil.getTypeBlockChangeTotalHours(context));
    }

    /**
     * 判断换活字块是否仍应沿用维保重叠专用切换口径。
     *
     * @param context 排程上下文
     * @param machine 机台
     * @param estimatedEndTime 预计收尾时间
     * @param switchStartTime 实际切换开始时间
     * @return true-沿用维保重叠专用口径；false-按普通换活字块口径
     */
    private boolean isTypeBlockMaintenanceOverlapSwitch(LhScheduleContext context,
                                                        MachineScheduleDTO machine,
                                                        Date estimatedEndTime,
                                                        Date switchStartTime) {
        if (machine == null || estimatedEndTime == null || switchStartTime == null) {
            return false;
        }
        Date rawSwitchStartTime = resolveAllowedSwitchStartTime(
                context, machine.getMachineCode(), estimatedEndTime);
        if (!getMaintenanceScheduleService().shouldApplyMaintenanceOverlapSwitchRule(
                context, machine, rawSwitchStartTime)) {
            return false;
        }
        Date maintenanceEndTime = getMaintenanceScheduleService().resolveMaintenanceEndTime(context, machine);
        return maintenanceEndTime != null && !switchStartTime.after(maintenanceEndTime);
    }

    /**
     * 解析允许发起换活字块的开始时间。
     *
     * @param context 排程上下文
     * @param machineCode 机台编码
     * @param endingTime 收尾时间
     * @return 允许切换开始时间
     */
    private Date resolveAllowedSwitchStartTime(LhScheduleContext context,
                                               String machineCode,
                                               Date endingTime) {
        if (endingTime == null) {
            return null;
        }
        Date adjustedTime = endingTime;
        for (int attempt = 0; attempt < TYPE_BLOCK_SWITCH_MAX_ATTEMPTS; attempt++) {
            Date downtimeAdjustedTime = resolveDowntimeAdjustedSwitchStartTime(
                    context, machineCode, adjustedTime);
            if (downtimeAdjustedTime.after(adjustedTime)) {
                adjustedTime = downtimeAdjustedTime;
                continue;
            }
            if (!LhScheduleTimeUtil.isNoMouldChangeTime(context, adjustedTime)) {
                return adjustedTime;
            }
            adjustedTime = LhScheduleTimeUtil.resolveNextMorningAfterNoMouldChangeWindow(context, adjustedTime);
        }
        log.warn("换活字块切换起点达到最大尝试次数, 机台: {}, 原始时间: {}",
                machineCode, LhScheduleTimeUtil.formatDateTime(endingTime));
        return adjustedTime;
    }

    private int compareMachineIdentity(MachineScheduleDTO leftMachine, MachineScheduleDTO rightMachine) {
        int machineOrderCompare = Integer.compare(leftMachine.getMachineOrder(), rightMachine.getMachineOrder());
        if (machineOrderCompare != 0) {
            return machineOrderCompare;
        }
        return Comparator.nullsLast(String::compareTo)
                .compare(leftMachine.getMachineCode(), rightMachine.getMachineCode());
    }

    /**
     * 根据停机窗口顺延换活字块切换起点。
     *
     * @param context 排程上下文
     * @param machineCode 机台编码
     * @param candidateStartTime 候选开始时间
     * @return 顺延后开始时间
     */
    private Date resolveDowntimeAdjustedSwitchStartTime(LhScheduleContext context,
                                                        String machineCode,
                                                        Date candidateStartTime) {
        if (context == null
                || StringUtils.isEmpty(machineCode)
                || candidateStartTime == null) {
            return candidateStartTime;
        }
        Date candidateEndTime = LhScheduleTimeUtil.addHours(
                candidateStartTime, LhScheduleTimeUtil.getTypeBlockChangeTotalHours(context));
        Date latestOverlapEndTime = null;
        if (!CollectionUtils.isEmpty(context.getDevicePlanShutList())) {
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
        }
        return latestOverlapEndTime != null ? latestOverlapEndTime : candidateStartTime;
    }

    /**
     * 追加换活字块排程结果。
     *
     * @param context 排程上下文
     * @param machine 机台
     * @param sku SKU
     * @param startTime 开产时间
     * @param shifts 班次
     * @return true-成功
     */
    private boolean appendFollowUpResult(LhScheduleContext context,
                                         MachineScheduleDTO machine,
                                         SkuScheduleDTO sku,
                                         Date startTime,
                                         Date switchStartTime,
                                         List<LhShiftConfigVO> shifts,
                                         boolean isSingleMachine,
                                         StringBuilder failureReason) {
        if (startTime == null) {
            recordTypeBlockAppendFailure(failureReason, "换活字块开产时间为空");
            return false;
        }
        if (sku.resolveTargetScheduleQty() <= 0) {
            recordTypeBlockAppendFailure(failureReason, "换活字块目标量为0");
            log.info("换活字块目标量为0，跳过排产, machineCode: {}, materialCode: {}",
                    machine.getMachineCode(), sku.getMaterialCode());
            return false;
        }
        if (isSharedEmbryoZeroSurplusSku(context, sku)) {
            recordTypeBlockAppendFailure(failureReason, SHARED_EMBRYO_ZERO_SURPLUS_UNSCHEDULED_REASON);
            addSharedEmbryoZeroSurplusUnscheduledResult(context, sku);
            context.getNewSpecSkuList().remove(sku);
            getTargetScheduleQtyResolver().removeActiveEmbryoSku(
                    context, sku, SHARED_EMBRYO_ZERO_SURPLUS_UNSCHEDULED_REASON);
            log.info("换活字块共用胎胚余量为0，跳过排产并移出待排队列, machineCode: {}, materialCode: {}, "
                            + "embryoCode: {}, surplusQty: {}, embryoStock: {}",
                    machine.getMachineCode(), sku.getMaterialCode(), sku.getEmbryoCode(),
                    sku.getSurplusQty(), sku.getEmbryoStock());
            return false;
        }
        // 换活字块与新增排产共用历史欠产账本口径，避免窗口无日计划时 S4.4 被回裁为0后再落入S4.5换模。
        DailyMachineExpansionPlanner.prepareShortageQuota(context, sku, "换活字块");
        // 保存原目标量和严格目标量标识，换活字块单台试算失败时必须完整恢复，不能污染 S4.5 新增排产。
        Integer originalTargetScheduleQty = sku.getTargetScheduleQty();
        int originalRemainingScheduleQty = sku.getRemainingScheduleQty();
        boolean originalStrictTargetQty = sku.isStrictTargetQty();
        boolean isEnding = endingJudgmentStrategy.isEnding(context, sku);
        boolean typeBlockExpansionContinuation = hasScheduledTypeBlockResult(context, sku);
        applySingleMachineTypeBlockTargetRule(context, machine, sku, startTime, switchStartTime, shifts,
                isEnding, isSingleMachine, typeBlockExpansionContinuation);
        int adoptedTargetQty = sku.resolveTargetScheduleQty();
        int machineMouldQty = ShiftCapacityResolverUtil.resolveMachineMouldQty(machine);
        sku.setMouldQty(machineMouldQty);
        LhScheduleResult result = buildScheduleResult(
                context, machine, sku, startTime, switchStartTime, shifts, machineMouldQty, isEnding);
        if (result == null || result.getDailyPlanQty() == null || result.getDailyPlanQty() <= 0) {
            int dailyPlanQty = result == null || result.getDailyPlanQty() == null ? 0 : result.getDailyPlanQty();
            recordTypeBlockAppendFailure(failureReason, "换活字块结果班次量为0");
            log.info("换活字块结果班次量为0，跳过落地, machineCode: {}, materialCode: {}, startTime: {}, dailyPlanQty: {}",
                    machine.getMachineCode(), sku.getMaterialCode(),
                    LhScheduleTimeUtil.formatDateTime(startTime), dailyPlanQty);
            sku.setTargetScheduleQty(originalTargetScheduleQty);
            sku.setRemainingScheduleQty(originalRemainingScheduleQty);
            sku.setStrictTargetQty(originalStrictTargetQty);
            return false;
        }
        result.setScheduleType(ScheduleTypeEnum.TYPE_BLOCK.getCode());
        result.setIsChangeMould(YES_FLAG);
        result.setIsTypeBlock(YES_FLAG);
        result.setMouldCode(resolveTypeBlockActualMouldCode(context, machine, sku));
        // 换活字块虽然不是新增规格换模，但下游换模计划仍按真实切换开始时间生成。
        result.setMouldChangeStartTime(switchStartTime);
        result.setIsEnd(isEnding ? YES_FLAG : NO_FLAG);

        // 换活字块结果即便非收尾，也必须补齐可计算完工时刻，避免结果校验失败。
        Date actualCompletionTime = resolveActualCompletionTime(context, result);
        if (actualCompletionTime == null) {
            recordTypeBlockAppendFailure(failureReason, "换活字块实际完工时间为空");
            log.info("换活字块实际完工时间为空，跳过落地, machineCode: {}, materialCode: {}, startTime: {}, dailyPlanQty: {}",
                    machine.getMachineCode(), sku.getMaterialCode(),
                    LhScheduleTimeUtil.formatDateTime(startTime), result.getDailyPlanQty());
            return false;
        }
        result.setSpecEndTime(actualCompletionTime);
        result.setTdaySpecEndTime(actualCompletionTime);
        applyTypeBlockCleaningAnalysis(context, result, shifts);

        // 换活字块结果按日计划账本回裁，收尾严格截断，避免超产。
        // 非收尾正规/量试可保留满班补齐口径，剩余缺口继续留给 S4.5。
        int quotaTrimmedQty = applyTypeBlockToDailyQuota(context, sku, result, shifts);
        if (quotaTrimmedQty <= 0) {
            recordTypeBlockAppendFailure(failureReason, "换活字块日计划账本回裁后为0");
            log.info("换活字块日计划账本回裁后为0, 跳过落地, machineCode: {}, materialCode: {}, 原排产量: {}",
                    machine.getMachineCode(), sku.getMaterialCode(), result.getDailyPlanQty());
            sku.setTargetScheduleQty(originalTargetScheduleQty);
            sku.setRemainingScheduleQty(originalRemainingScheduleQty);
            sku.setStrictTargetQty(originalStrictTargetQty);
            return false;
        }

        context.getScheduleResultList().add(result);
        context.getScheduleResultSourceSkuMap().put(result, sku);
        registerMachineAssignment(context, machine.getMachineCode(), result);
        updateMachineState(context, machine, sku, result);
        int scheduledQty = result.getDailyPlanQty() == null ? 0 : result.getDailyPlanQty();
        int remainingQty = Math.max(0, adoptedTargetQty - scheduledQty);
        if (remainingQty > 0) {
            // 换活字块只在当前衔接机台落一段产能；单台不足时，不在 S4.4 继续扩第二台，
            // 而是把剩余量写回 SKU，交给 S4.5 新增排产重新选机、换模和扣账。
            sku.setTargetScheduleQty(remainingQty);
            sku.setRemainingScheduleQty(remainingQty);
            sku.setStrictTargetQty(originalStrictTargetQty);
            log.info("换活字块单台产能不足，剩余量回流新增排产, machineCode: {}, materialCode: {}, 已排: {}, "
                            + "remainingQtyForNewSchedule: {}, 回流阶段: S4.5新增排产/换模",
                    machine.getMachineCode(), sku.getMaterialCode(), scheduledQty, remainingQty);
            return true;
        }
        context.getNewSpecSkuList().remove(sku);
        log.debug("换活字块排产完成, 机台: {}, SKU: {}, 已排: {}, 剩余: {}",
                machine.getMachineCode(), sku.getMaterialCode(), scheduledQty, remainingQty);
        return true;
    }

    /**
     * 判断是否为共用胎胚零余量SKU。
     *
     * @param context 排程上下文
     * @param sku SKU
     * @return true-共用胎胚且硫化余量小于等于0；false-不命中
     */
    private boolean isSharedEmbryoZeroSurplusSku(LhScheduleContext context, SkuScheduleDTO sku) {
        if (context == null || sku == null || sku.getSurplusQty() > 0
                || StringUtils.isEmpty(sku.getMaterialCode())) {
            return false;
        }
        return Boolean.TRUE.equals(context.getMaterialSharedEmbryoMap().get(sku.getMaterialCode()));
    }

    /**
     * 写入换活字块共用胎胚零余量未排结果。
     *
     * @param context 排程上下文
     * @param sku SKU
     */
    private void addSharedEmbryoZeroSurplusUnscheduledResult(LhScheduleContext context, SkuScheduleDTO sku) {
        LhUnscheduledResult unscheduled = new LhUnscheduledResult();
        unscheduled.setFactoryCode(context.getFactoryCode());
        unscheduled.setBatchNo(context.getBatchNo());
        unscheduled.setMaterialCode(sku.getMaterialCode());
        unscheduled.setMaterialDesc(sku.getMaterialDesc());
        unscheduled.setScheduleDate(context.getScheduleTargetDate());
        unscheduled.setUnscheduledReason(SHARED_EMBRYO_ZERO_SURPLUS_UNSCHEDULED_REASON);
        unscheduled.setUnscheduledQty(0);
        unscheduled.setStructureName(sku.getStructureName());
        unscheduled.setMainMaterialDesc(sku.getMainMaterialDesc());
        unscheduled.setSpecCode(sku.getSpecCode());
        unscheduled.setEmbryoCode(sku.getEmbryoCode());
        unscheduled.setMouldQty(sku.getMouldQty());
        unscheduled.setDataSource(AUTO_DATA_SOURCE);
        unscheduled.setIsDelete(0);
        context.getUnscheduledResultList().add(unscheduled);
    }

    /**
     * 判断当前SKU是否已经落过换活字块结果。
     *
     * @param context 排程上下文
     * @param sku SKU
     * @return true-已经落过换活字块结果
     */
    private boolean hasScheduledTypeBlockResult(LhScheduleContext context, SkuScheduleDTO sku) {
        if (context == null || sku == null || StringUtils.isEmpty(sku.getMaterialCode())
                || CollectionUtils.isEmpty(context.getScheduleResultList())) {
            return false;
        }
        for (LhScheduleResult result : context.getScheduleResultList()) {
            if (result == null) {
                continue;
            }
            if (StringUtils.equals(sku.getMaterialCode(), result.getMaterialCode())
                    && StringUtils.equals(ScheduleTypeEnum.TYPE_BLOCK.getCode(), result.getScheduleType())
                    && StringUtils.equals(YES_FLAG, result.getIsTypeBlock())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 追加换活字块结果，并在失败时回滚已占用的模具切换配额。
     *
     * @param context 排程上下文
     * @param machine 机台
     * @param sku SKU
     * @param startTime 开产时间
     * @param switchStartTime 切换开始时间
     * @param shifts 班次
     * @return true-成功
     */
    private boolean appendTypeBlockResultWithRollback(LhScheduleContext context,
                                                      MachineScheduleDTO machine,
                                                      SkuScheduleDTO sku,
                                                      Date startTime,
                                                      Date switchStartTime,
                                                      List<LhShiftConfigVO> shifts,
                                                      boolean isSingleMachine,
                                                      StringBuilder failureReason) {
        boolean success = appendFollowUpResult(context, machine, sku, startTime, switchStartTime, shifts,
                isSingleMachine, failureReason);
        if (!success && switchStartTime != null) {
            // 换活字块结果落地失败时，回滚本轮已占用的切换配额。
            getMouldChangeBalanceStrategy().rollbackMouldChange(context, switchStartTime);
        }
        return success;
    }

    /**
     * 记录换活字块追加失败原因。
     *
     * @param failureReason 失败原因载体
     * @param reason 当前失败原因
     */
    private void recordTypeBlockAppendFailure(StringBuilder failureReason, String reason) {
        if (failureReason == null || StringUtils.isEmpty(reason)) {
            return;
        }
        if (failureReason.length() > 0) {
            failureReason.append("；");
        }
        failureReason.append(reason);
    }

    /**
     * 单机台换活字块目标量决策。
     *
     * @param context 排程上下文
     * @param machine 机台
     * @param sku SKU
     * @param startTime 开产时间
     * @param switchStartTime 切换开始时间
     * @param shifts 班次
     * @param isEnding 是否收尾
     * @param isSingleMachine 是否单机台
     * @param typeBlockExpansionContinuation 是否多机台续排剩余量
     */
    private void applySingleMachineTypeBlockTargetRule(LhScheduleContext context,
                                                       MachineScheduleDTO machine,
                                                       SkuScheduleDTO sku,
                                                       Date startTime,
                                                       Date switchStartTime,
                                                       List<LhShiftConfigVO> shifts,
                                                       boolean isEnding,
                                                       boolean isSingleMachine,
                                                       boolean typeBlockExpansionContinuation) {
        if (sku == null || machine == null) {
            return;
        }
        int originalTargetQty = sku.resolveTargetScheduleQty();
        int windowCapacityQty = startTime == null ? 0
                : getTargetScheduleQtyResolver().calcMachineAvailableCapacityByStartTime(
                context, sku, machine, switchStartTime, startTime, shifts,
                ScheduleTypeEnum.TYPE_BLOCK.getCode());
        String appliedRule = "沿用原规则";
        if (typeBlockExpansionContinuation) {
            sku.setStrictTargetQty(isEnding || sku.isStrictTargetQty());
            appliedRule = "多机台续排剩余目标量";
        } else if (isSingleMachine && isEnding) {
            getTargetScheduleQtyResolver().upsizeEndingTargetQty(context, sku);
            appliedRule = getTargetScheduleQtyResolver().isSharedEmbryoInWindow(context, sku)
                    ? "单机台收尾共用胎胚仅按余量" : "单机台收尾MAX(余量,胎胚库存)";
        } else if (isSingleMachine && getTargetScheduleQtyResolver().isFullCapacityMode(context)) {
            boolean newSpecExpansionAvailable = !DailyMachineExpansionPlanner.isDailyLookAheadCapacitySatisfied(
                    context, sku, 1, ScheduleTypeEnum.TYPE_BLOCK.getCode())
                    && hasSchedulableNewSpecExpansionMachine(context, machine, sku, shifts);
            int adoptedTargetQty = resolveSingleMachineTypeBlockTargetQty(
                    sku, windowCapacityQty, newSpecExpansionAvailable);
            sku.setTargetScheduleQty(adoptedTargetQty);
            sku.setRemainingScheduleQty(adoptedTargetQty);
            sku.setStrictTargetQty(false);
            appliedRule = newSpecExpansionAvailable
                    ? "单机台换活字块承接+新增换模扩机"
                    : resolveSingleMachineWindowRuleName(sku, adoptedTargetQty, windowCapacityQty);
        } else if (isEnding) {
            sku.setStrictTargetQty(true);
            appliedRule = isSingleMachine ? "单机台收尾严格原目标" : "多机台沿用原规则";
        } else if (!isSingleMachine) {
            appliedRule = "多机台沿用原规则";
        } else if (!getTargetScheduleQtyResolver().isFullCapacityMode(context)) {
            appliedRule = "按需求模式沿用原规则";
        }
        log.info("S4.4换活字块目标量决策, scene: typeBlock, materialCode: {}, machineCode: {}, isSingleMachine: {}, "
                        + "isEnding: {}, surplusQty: {}, embryoStock: {}, originalTargetQty: {}, windowCapacityQty: {}, "
                        + "adoptedTargetQty: {}, rule: {}",
                sku.getMaterialCode(), machine.getMachineCode(), isSingleMachine, isEnding,
                Math.max(0, sku.getSurplusQty()), Math.max(0, sku.getEmbryoStock()), originalTargetQty,
                windowCapacityQty, sku.resolveTargetScheduleQty(), appliedRule);
    }

    /**
     * 解析单机台换活字块在非收尾场景下的目标量。
     * <p>若后续仍有新增换模扩机能力，则保留窗口账本需求量，允许剩余量回流 S4.5；
     * 否则沿用当前单机台满排窗口口径。</p>
     *
     * @param sku SKU
     * @param windowCapacityQty 当前机台窗口产能
     * @param newSpecExpansionAvailable 是否存在可承接的新增换模机台
     * @return 目标量
     */
    private int resolveSingleMachineTypeBlockTargetQty(SkuScheduleDTO sku,
                                                       int windowCapacityQty,
                                                       boolean newSpecExpansionAvailable) {
        int adoptedTargetQty = Math.max(0, windowCapacityQty);
        if (newSpecExpansionAvailable) {
            adoptedTargetQty = Math.max(adoptedTargetQty, resolveTypeBlockExpansionDemandQty(sku));
        }
        int surplusQty = sku == null ? 0 : Math.max(0, sku.getSurplusQty());
        if (surplusQty > 0 && surplusQty < adoptedTargetQty) {
            return surplusQty;
        }
        return adoptedTargetQty;
    }

    /**
     * 解析单机台换活字块在可扩机场景下应保留的窗口需求量。
     *
     * @param sku SKU
     * @return 窗口需求量
     */
    private int resolveTypeBlockExpansionDemandQty(SkuScheduleDTO sku) {
        if (sku == null) {
            return 0;
        }
        int quotaDemandQty = SkuDailyPlanQuotaUtil.sumRemainingQty(sku.getDailyPlanQuotaMap());
        int windowRemainingQty = Math.max(0, sku.getWindowRemainingPlanQty());
        if (windowRemainingQty > 0) {
            quotaDemandQty = quotaDemandQty > 0 ? Math.min(quotaDemandQty, windowRemainingQty) : windowRemainingQty;
        }
        if (quotaDemandQty > 0) {
            return quotaDemandQty;
        }
        int windowPlanQty = Math.max(0, sku.getWindowPlanQty());
        if (windowPlanQty > 0) {
            return windowPlanQty;
        }
        return Math.max(0, sku.resolveTargetScheduleQty());
    }

    /**
     * 判断当前换活字块 SKU 是否仍有可承接的新增换模机台。
     *
     * @param context 排程上下文
     * @param currentMachine 当前换活字块机台
     * @param sku SKU
     * @param shifts 班次窗口
     * @return true-存在可承接机台
     */
    private boolean hasSchedulableNewSpecExpansionMachine(LhScheduleContext context,
                                                          MachineScheduleDTO currentMachine,
                                                          SkuScheduleDTO sku,
                                                          List<LhShiftConfigVO> shifts) {
        if (context == null
                || currentMachine == null
                || sku == null
                || CollectionUtils.isEmpty(shifts)
                || CollectionUtils.isEmpty(context.getMachineScheduleMap())) {
            return false;
        }
        for (MachineScheduleDTO candidateMachine : context.getMachineScheduleMap().values()) {
            if (candidateMachine == null
                    || StringUtils.isEmpty(candidateMachine.getMachineCode())
                    || StringUtils.equals(candidateMachine.getMachineCode(), currentMachine.getMachineCode())
                    || candidateMachine.getEstimatedEndTime() == null
                    || !isMachineHardMatched(context, candidateMachine, sku)) {
                continue;
            }
            if (canScheduleSpecifySkuByNewSpecPath(
                    context, candidateMachine, sku, shifts, candidateMachine.getEstimatedEndTime())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 解析单机台满排窗口规则日志名称。
     *
     * @param sku SKU
     * @param adoptedTargetQty 目标量
     * @param windowCapacityQty 当前机台窗口产能
     * @return 规则名称
     */
    private String resolveSingleMachineWindowRuleName(SkuScheduleDTO sku,
                                                      int adoptedTargetQty,
                                                      int windowCapacityQty) {
        if (sku != null && adoptedTargetQty < Math.max(0, windowCapacityQty)) {
            return "单机台非收尾满排窗口(余量封顶)";
        }
        return "单机台非收尾满排窗口";
    }

    /**
     * 统计当前轮可承接指定换活字块 SKU 的机台数。
     *
     * @param context 排程上下文
     * @param sku SKU
     * @param activeMachines 当前轮可尝试机台
     * @return 可承接机台数
     */
    private int countEligibleTypeBlockMachines(LhScheduleContext context,
                                               SkuScheduleDTO sku,
                                               List<MachineScheduleDTO> activeMachines) {
        if (context == null || sku == null || CollectionUtils.isEmpty(activeMachines)) {
            return 0;
        }
        int eligibleCount = 0;
        for (MachineScheduleDTO activeMachine : activeMachines) {
            if (activeMachine != null && isTypeBlockCandidate(context, activeMachine, sku, false)) {
                eligibleCount++;
            }
        }
        return eligibleCount;
    }

    /**
     * 判断定点物料在当前机台和窗口内是否可排。
     *
     * @param context 排程上下文
     * @param machine 当前机台
     * @param specifySku 定点物料
     * @param shifts 排程窗口班次
     * @param endingTime 机台切换起点
     * @return true-可排，false-不可排
     */
    private boolean canScheduleSpecifySkuOnMachine(LhScheduleContext context,
                                                   MachineScheduleDTO machine,
                                                   SkuScheduleDTO specifySku,
                                                   List<LhShiftConfigVO> shifts,
                                                   Date endingTime) {
        if (context == null
                || machine == null
                || specifySku == null
                || endingTime == null
                || CollectionUtils.isEmpty(shifts)
                || StringUtils.isEmpty(machine.getMachineCode())) {
            return false;
        }
        if (!isMachineHardMatched(context, machine, specifySku)) {
            log.debug("定点物料预判未通过机台硬性准入, machineCode: {}, materialCode: {}",
                    machine.getMachineCode(), specifySku.getMaterialCode());
            return false;
        }
        if (isTypeBlockCandidate(context, machine, specifySku)) {
            Date typeBlockSwitchStartTime = allocateTypeBlockSwitchStartTime(context, machine, specifySku, endingTime);
            Date typeBlockStartTime = resolveTypeBlockProductionStartTime(
                    context, machine, endingTime, typeBlockSwitchStartTime);
            if (typeBlockStartTime == null || typeBlockSwitchStartTime == null) {
                return false;
            }
            try {
                int refinedTargetQty = getTargetScheduleQtyResolver().refineTargetQtyByMachineCapacity(
                        context,
                        specifySku,
                        machine,
                        typeBlockSwitchStartTime,
                        typeBlockStartTime,
                        shifts,
                        ScheduleTypeEnum.TYPE_BLOCK.getCode());
                if (refinedTargetQty <= 0) {
                    log.debug("定点物料换活字块预判不可排, machineCode: {}, materialCode: {}, startTime: {}",
                            machine.getMachineCode(), specifySku.getMaterialCode(),
                            LhScheduleTimeUtil.formatDateTime(typeBlockStartTime));
                    return false;
                }
                return true;
            } finally {
                getMouldChangeBalanceStrategy().rollbackMouldChange(context, typeBlockSwitchStartTime);
            }
        }
        return canScheduleSpecifySkuByNewSpecPath(context, machine, specifySku, shifts, endingTime);
    }

    /**
     * 判断候选SKU是否应优先走新增换模主链。
     *
     * @param context 排程上下文
     * @param machine 当前机台
     * @param sku 候选SKU
     * @return true-应走新增换模主链
     */
    private boolean shouldPreferNewSpecPath(LhScheduleContext context,
                                             MachineScheduleDTO machine,
                                             SkuScheduleDTO sku) {
        if (!isTypeBlockCandidate(context, machine, sku)) {
            return true;
        }
        return false;
    }

    /**
     * 判断候选SKU是否满足机台硬性准入。
     *
     * @param context 排程上下文
     * @param machine 机台
     * @param sku SKU
     * @return true-满足，false-不满足
     */
    private boolean isMachineHardMatched(LhScheduleContext context,
                                         MachineScheduleDTO machine,
                                         SkuScheduleDTO sku) {
        return LhMachineHardMatchUtil.isMachineHardMatched(context, sku, machine);
    }

    /**
     * 按新增换模链路预判定点物料是否可排。
     *
     * @param context 排程上下文
     * @param machine 当前机台
     * @param specifySku 定点物料
     * @param shifts 排程窗口班次
     * @param endingTime 机台切换起点
     * @return true-可排，false-不可排
     */
    private boolean canScheduleSpecifySkuByNewSpecPath(LhScheduleContext context,
                                                       MachineScheduleDTO machine,
                                                       SkuScheduleDTO specifySku,
                                                       List<LhShiftConfigVO> shifts,
                                                       Date endingTime) {
        Date machineReadyTime = getCapacityCalculateStrategy().calculateStartTime(
                context, machine.getMachineCode(), endingTime);
        boolean maintenanceOverlapSwitch = getMaintenanceScheduleService()
                .shouldApplyMaintenanceOverlapSwitchRule(context, machine, endingTime);
        Date switchReadyTime = maintenanceOverlapSwitch
                ? getMaintenanceScheduleService().resolveMaintenanceEndTime(context, machine)
                : machineReadyTime;
        switchReadyTime = ShiftProductionControlUtil.resolveEarliestSwitchStartTime(context, switchReadyTime);
        int switchDurationHours = maintenanceOverlapSwitch
                ? LhScheduleTimeUtil.getMaintenanceOverlapSwitchHours(context)
                : LhScheduleTimeUtil.getMouldChangeTotalHours(context);
        Date mouldChangeStartTime = getMouldChangeBalanceStrategy().allocateMouldChange(
                context,
                machine.getMachineCode(),
                switchReadyTime,
                switchDurationHours,
                specifySku,
                IMouldChangeBalanceStrategy.ACTION_NEW_SPEC_MOULD_CHANGE);
        if (mouldChangeStartTime == null) {
            log.debug("定点物料新增换模预判不可排, machineCode: {}, materialCode: {}, 原因: 无可用换模窗口",
                    machine.getMachineCode(), specifySku.getMaterialCode());
            return false;
        }
        Date inspectionTime = null;
        try {
            Date mouldChangeCompleteTime = LhScheduleTimeUtil.addHours(mouldChangeStartTime, switchDurationHours);
            inspectionTime = getFirstInspectionBalanceStrategy().allocateInspection(
                    context, machine.getMachineCode(), mouldChangeCompleteTime);
            if (inspectionTime == null) {
                log.debug("定点物料新增换模预判不可排, machineCode: {}, materialCode: {}, 原因: 首检窗口分配失败",
                        machine.getMachineCode(), specifySku.getMaterialCode());
                return false;
            }
            Date productionStartTime = maintenanceOverlapSwitch
                    ? LhScheduleTimeUtil.addHours(inspectionTime, LhScheduleTimeUtil.getFirstInspectionHours(context))
                    : inspectionTime;
            int machineMouldQty = ShiftCapacityResolverUtil.resolveMachineMouldQty(machine);
            int runtimeShiftCapacity = ShiftCapacityResolverUtil.resolveRuntimeShiftCapacity(
                    context, machine, specifySku.getShiftCapacity());
            Date firstProductionStartTime = ShiftProductionControlUtil.resolveFirstSchedulableStartIgnoringCleaning(
                    context,
                    machine.getMachineCode(),
                    productionStartTime,
                    shifts,
                    runtimeShiftCapacity,
                    specifySku.getLhTimeSeconds(),
                    machineMouldQty);
            if (firstProductionStartTime == null) {
                log.debug("定点物料新增换模预判不可排, machineCode: {}, materialCode: {}, 原因: 窗口内无可开产时间",
                        machine.getMachineCode(), specifySku.getMaterialCode());
                return false;
            }
            int refinedTargetQty = getTargetScheduleQtyResolver().refineTargetQtyByMachineCapacity(
                    context, specifySku, machine, mouldChangeStartTime, firstProductionStartTime,
                    shifts, ScheduleTypeEnum.NEW_SPEC.getCode());
            if (refinedTargetQty <= 0) {
                log.debug("定点物料新增换模预判不可排, machineCode: {}, materialCode: {}, 原因: 收敛后目标量为0",
                        machine.getMachineCode(), specifySku.getMaterialCode());
                return false;
            }
            return true;
        } finally {
            if (inspectionTime != null) {
                getFirstInspectionBalanceStrategy().rollbackInspection(context, inspectionTime);
            }
            getMouldChangeBalanceStrategy().rollbackMouldChange(context, mouldChangeStartTime);
        }
    }

    /**
     * 判断当前候选是否需要走真实换模均衡能力。
     *
     * @param context 排程上下文
     * @param machine 当前机台
     * @param sku 候选SKU
     * @return true-需走新增换模主链
     */
    private boolean requiresMouldChangeBalance(LhScheduleContext context,
                                               MachineScheduleDTO machine,
                                               SkuScheduleDTO sku) {
        if (context == null
                || machine == null
                || sku == null
                || StringUtils.isEmpty(machine.getCurrentMaterialCode())
                || StringUtils.isEmpty(sku.getMaterialCode())) {
            return false;
        }
        Set<String> currentMouldCodes = resolveMouldCodeSet(context, machine.getCurrentMaterialCode());
        Set<String> targetMouldCodes = resolveMouldCodeSet(context, sku.getMaterialCode());
        if (CollectionUtils.isEmpty(currentMouldCodes) || CollectionUtils.isEmpty(targetMouldCodes)) {
            return false;
        }
        for (String targetMouldCode : targetMouldCodes) {
            if (currentMouldCodes.contains(targetMouldCode)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 输出衔接机台排序总览日志。
     *
     * @param context 排程上下文
     * @param endingMachines 衔接机台列表
     */
    private void traceEndingMachineOrder(LhScheduleContext context,
                                         List<MachineScheduleDTO> endingMachines,
                                         Map<String, String> machineTriggerSourceMap) {
        if (!PriorityTraceLogHelper.isEnabled(context)) {
            return;
        }
        String title = "衔接机台排序总览【换活字块衔接】";
        int topN = LhScheduleConstant.MACHINE_SORT_TRACE_TOP_N;
        int machineCount = PriorityTraceLogHelper.sizeOf(endingMachines);
        int outputCount = Math.min(topN, machineCount);

        StringBuilder detailBuilder = new StringBuilder(1024);
        PriorityTraceLogHelper.appendTitleHeader(detailBuilder, title);
        PriorityTraceLogHelper.appendLine(detailBuilder,
                PriorityTraceLogHelper.kv("排程日期", PriorityTraceLogHelper.formatDateTime(context.getScheduleDate()))
                        + ", " + PriorityTraceLogHelper.kv("候选机台数量", machineCount)
                        + ", " + PriorityTraceLogHelper.kv("输出范围", "TOP" + outputCount));

        if (CollectionUtils.isEmpty(endingMachines)) {
            PriorityTraceLogHelper.appendLine(detailBuilder, "无可输出的换活字块候选机台");
        } else {
            // 预建换活字块候选标记缓存，避免同机台重复遍历全体SKU
            Map<String, Boolean> canChangeLetterCache = new HashMap<>(Math.min(outputCount, 16));
            for (int i = 0; i < outputCount; i++) {
                MachineScheduleDTO m = endingMachines.get(i);
                canChangeLetterCache.put(m.getMachineCode(), resolveCanChangeLetterFlag(context, m));
            }

            List<String> levelNames = Arrays.asList(
                    "L1_触发来源", "L2_切换就绪时间", "L3_收尾时间");
            for (int i = 0; i < outputCount; i++) {
                MachineScheduleDTO machine = endingMachines.get(i);
                Date estimatedEndTime = machine.getEstimatedEndTime();
                Date readyTime = resolveTypeBlockSortReadyTime(context, machine);
                String triggerSource = machineTriggerSourceMap != null
                        ? machineTriggerSourceMap.get(machine.getMachineCode()) : null;
                int triggerOrder = resolveTypeBlockTriggerOrder(triggerSource);
                String triggerDesc = triggerOrder == 0 ? "收尾触发" : (triggerOrder == 1 ? "兜底触发" : "其他");
                boolean canChangeLetter = Boolean.TRUE.equals(canChangeLetterCache.get(machine.getMachineCode()));
                String machineEmbryoDesc = resolveMachineEmbryoDesc(context, machine);
                String machineEmbryoCode = resolveMachineEmbryoCode(context, machine);
                String machineMainPattern = resolveMachineMainPatternStrict(context, machine);
                String machineSpecCode = resolveMachineSpecCode(context, machine);

                List<String> sortKeyLevels = Arrays.asList(
                        "L1_触发来源=" + triggerDesc,
                        "L2_切换就绪=" + PriorityTraceLogHelper.formatDateTime(readyTime),
                        "L3_收尾时间=" + PriorityTraceLogHelper.formatDateTime(estimatedEndTime));
                String sortKey = PriorityTraceLogHelper.formatSortKey(sortKeyLevels);
                String hitLevel;
                if (triggerOrder == 0) {
                    hitLevel = "命中L1收尾触发优先";
                } else if (triggerOrder == 1) {
                    hitLevel = "命中L1兜底触发";
                } else {
                    hitLevel = "兜底排序";
                }

                PriorityTraceLogHelper.appendLine(detailBuilder,
                        (i + 1)
                                + ". " + PriorityTraceLogHelper.kv("机台", machine.getMachineCode())
                                + ", " + PriorityTraceLogHelper.kv("当前物料", machine.getCurrentMaterialCode())
                                + ", " + PriorityTraceLogHelper.kv("触发来源", triggerDesc)
                                + ", " + PriorityTraceLogHelper.kv("收尾", PriorityTraceLogHelper.oneZero(machine.isEnding()))
                                + ", " + PriorityTraceLogHelper.kv("收尾时间", PriorityTraceLogHelper.formatDateTime(estimatedEndTime))
                                + ", " + PriorityTraceLogHelper.kv("切换就绪时间", PriorityTraceLogHelper.formatDateTime(readyTime))
                                + ", " + PriorityTraceLogHelper.kv("可换活字块", PriorityTraceLogHelper.oneZero(canChangeLetter))
                                + ", " + PriorityTraceLogHelper.kv("胎胚代码", PriorityTraceLogHelper.safeText(machineEmbryoCode))
                                + ", " + PriorityTraceLogHelper.kv("胎胚描述", machineEmbryoDesc)
                                + ", " + PriorityTraceLogHelper.kv("主花纹", machineMainPattern)
                                + ", " + PriorityTraceLogHelper.kv("规格", machineSpecCode)
                                + ", " + PriorityTraceLogHelper.kv("机台顺序", machine.getMachineOrder())
                                + ", " + PriorityTraceLogHelper.kv("SortKey", sortKey)
                                + ", " + PriorityTraceLogHelper.kv("HitLevel", hitLevel));
            }
            if (machineCount > topN) {
                PriorityTraceLogHelper.appendLine(detailBuilder,
                        "... 共" + machineCount + "台，仅展示前" + topN + "台");
            }
        }
        PriorityTraceLogHelper.appendTitleFooter(detailBuilder);
        String detail = detailBuilder.toString().trim();
        PriorityTraceLogHelper.logSortSummary(log, context, title, detail);
    }

    /**
     * 判断机台当前在机物料是否可做换活字块衔接。
     *
     * @param context 排程上下文
     * @param machine 机台
     * @return true-可换活字块
     */
    private boolean resolveCanChangeLetterFlag(LhScheduleContext context, MachineScheduleDTO machine) {
        if (context == null || machine == null || StringUtils.isEmpty(machine.getCurrentMaterialCode())) {
            return false;
        }
        for (SkuScheduleDTO sku : context.getNewSpecSkuList()) {
            if (sku == null || StringUtils.isEmpty(sku.getMaterialCode())) {
                continue;
            }
            if (isTypeBlockCandidate(context, machine, sku, false)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 输出换活字块机台反选SKU决策日志（含TOP5候选SKU列表、过滤统计、SortKey、HitLevel）。
     *
     * @param context 排程上下文
     * @param machine 收尾机台
     * @param candidates 候选SKU
     * @param selectedSku 选中SKU
     * @param matchedLayer 命中层级
     * @param success 是否成功
     * @param switchStartTime 换活字块开始时间
     * @param startTime 开产时间
     * @param triggerSource 触发来源
     */
    private void traceTypeBlockDecision(LhScheduleContext context, MachineScheduleDTO machine,
                                        List<SkuScheduleDTO> candidates,
                                        SkuScheduleDTO selectedSku,
                                        String matchedLayer,
                                        boolean success,
                                        Date switchStartTime,
                                        Date startTime,
                                        String triggerSource,
                                        String failureReason) {
        if (!PriorityTraceLogHelper.isEnabled(context)) {
            return;
        }
        String title = "收尾机台衔接决策【换活字块机台反选SKU TOP5列表】";
        StringBuilder detailBuilder = new StringBuilder(1024);
        PriorityTraceLogHelper.appendTitleHeader(detailBuilder, title);

        String machineEmbryoDesc = resolveMachineEmbryoDesc(context, machine);
        String machineEmbryoCode = resolveMachineEmbryoCode(context, machine);
        String machineMainPattern = resolveMachineMainPatternStrict(context, machine);
        String machineSpecCode = resolveMachineSpecCode(context, machine);

        PriorityTraceLogHelper.appendLine(detailBuilder,
                PriorityTraceLogHelper.kv("排程日期", PriorityTraceLogHelper.formatDateTime(context.getScheduleDate()))
                        + ", " + PriorityTraceLogHelper.kv("当前机台", machine.getMachineCode())
                        + ", " + PriorityTraceLogHelper.kv("当前在机SKU", machine.getCurrentMaterialCode())
                        + ", " + PriorityTraceLogHelper.kv("当前胎胚代码", PriorityTraceLogHelper.safeText(machineEmbryoCode))
                        + ", " + PriorityTraceLogHelper.kv("当前胎胚描述", machineEmbryoDesc)
                        + ", " + PriorityTraceLogHelper.kv("当前主花纹", machineMainPattern)
                        + ", " + PriorityTraceLogHelper.kv("当前规格", machineSpecCode)
                        + ", " + PriorityTraceLogHelper.kv("收尾时间", PriorityTraceLogHelper.formatDateTime(machine.getEstimatedEndTime())));

        int totalCandidates = PriorityTraceLogHelper.sizeOf(candidates);
        int newSpecTotal = PriorityTraceLogHelper.sizeOf(context.getNewSpecSkuList());
        int filteredCount = newSpecTotal - totalCandidates;
        PriorityTraceLogHelper.appendLine(detailBuilder,
                PriorityTraceLogHelper.kv("候选SKU总数", totalCandidates)
                        + ", " + PriorityTraceLogHelper.kv("过滤SKU数", filteredCount)
                        + ", 过滤原因统计: 未满足换活字块准入条件=" + Math.max(0, filteredCount));

        // 输出候选 TOP5（同胎胚+同模具）
        int topN = LhScheduleConstant.TYPE_BLOCK_SKU_CANDIDATE_TOP_N;
        if (!CollectionUtils.isEmpty(candidates)) {
            int outputCount = Math.min(topN, totalCandidates);
            PriorityTraceLogHelper.appendLine(detailBuilder,
                    "候选(同胎胚+同模具) TOP" + outputCount + ":");
            appendSkuCandidateLines(detailBuilder, context, machine, candidates, outputCount);
        }

        // 最终选中
        String selectReason = resolveTypeBlockSelectReason(context, machine, selectedSku, candidates);
        PriorityTraceLogHelper.appendLine(detailBuilder,
                PriorityTraceLogHelper.kv("命中层级", matchedLayer)
                        + ", " + PriorityTraceLogHelper.kv("选中SKU", selectedSku == null ? "-" : selectedSku.getMaterialCode())
                        + ", " + PriorityTraceLogHelper.kv("选中原因", selectReason)
                        + ", " + PriorityTraceLogHelper.kv("衔接结果", success ? "成功" : "未衔接")
                        + ", " + PriorityTraceLogHelper.kv("失败原因", success ? "-" : PriorityTraceLogHelper.safeText(failureReason))
                        + ", " + PriorityTraceLogHelper.kv("换活字块开始时间", PriorityTraceLogHelper.formatDateTime(switchStartTime))
                        + ", " + PriorityTraceLogHelper.kv("开产时间", PriorityTraceLogHelper.formatDateTime(startTime)));

        PriorityTraceLogHelper.appendTitleFooter(detailBuilder);
        String detail = detailBuilder.toString().trim();
        PriorityTraceLogHelper.logSortSummary(log, context, title, detail);
    }

    /**
     * 逐行输出候选SKU明细。
     *
     * @param builder 日志构建器
     * @param context 排程上下文
     * @param machine 机台
     * @param candidates 候选SKU列表
     * @param outputCount 输出数量
     */
    private void appendSkuCandidateLines(StringBuilder builder, LhScheduleContext context,
                                         MachineScheduleDTO machine,
                                         List<SkuScheduleDTO> candidates,
                                         int outputCount) {
        // 机台当前在机模具号集合，仅计算一次
        Set<String> machineMouldCodeSet = resolveMouldCodeSet(context, machine.getCurrentMaterialCode());
        String machineMouldCodes = CollectionUtils.isEmpty(machineMouldCodeSet)
                ? "-" : String.join(",", machineMouldCodeSet);

        for (int i = 0; i < outputCount; i++) {
            SkuScheduleDTO sku = candidates.get(i);
            boolean sameCarcass = isSameCarcass(context, machine, sku);
            boolean sameMold = isSameMold(context, machine, sku);
            boolean canChange = isTypeBlockCandidate(context, machine, sku, false);
            String skuEmbryoDesc = resolveSkuEmbryoDesc(context, sku);

            // SKU所有模具号 及 与机台当前模具的交集
            Set<String> skuMouldCodeSet = resolveMouldCodeSet(context, sku.getMaterialCode());
            String skuMouldCodes = CollectionUtils.isEmpty(skuMouldCodeSet)
                    ? "-" : String.join(",", skuMouldCodeSet);
            String intersectMouldCodes = "-";
            if (!CollectionUtils.isEmpty(machineMouldCodeSet) && !CollectionUtils.isEmpty(skuMouldCodeSet)) {
                List<String> intersectList = new ArrayList<>(machineMouldCodeSet);
                intersectList.retainAll(skuMouldCodeSet);
                intersectMouldCodes = intersectList.isEmpty() ? "-" : String.join(",", intersectList);
            }

            String sortKey = PriorityTraceLogHelper.formatSortKey(Arrays.asList(
                    "L1_同胎胚同模具=" + (sameCarcass && sameMold ? 1 : 0),
                    "L2_物料编码兜底=" + PriorityTraceLogHelper.safeText(sku.getMaterialCode())));
            String hitLevel = sameCarcass && sameMold ? "命中L1同胎胚+同模具" : "-";

            PriorityTraceLogHelper.appendLine(builder,
                    (i + 1)
                            + ". " + PriorityTraceLogHelper.kv("物料编码", sku.getMaterialCode())
                            + ", " + PriorityTraceLogHelper.kv("描述", sku.getMaterialDesc())
                            + ", " + PriorityTraceLogHelper.kv("收尾", PriorityTraceLogHelper.oneZero(endingJudgmentStrategy.isEnding(context, sku)))
                            + ", " + PriorityTraceLogHelper.kv("待排产量", sku.resolveTargetScheduleQty())
                            + ", " + PriorityTraceLogHelper.kv("月计划余量", sku.getSurplusQty())
                            + ", " + PriorityTraceLogHelper.kv("胎胚库存", sku.getEmbryoStock())
                            + ", " + PriorityTraceLogHelper.kv("胎胚代码", PriorityTraceLogHelper.safeText(sku.getEmbryoCode()))
                            + ", " + PriorityTraceLogHelper.kv("胎胚描述", skuEmbryoDesc)
                            + ", " + PriorityTraceLogHelper.kv("规格", sku.getSpecCode())
                            + ", " + PriorityTraceLogHelper.kv("SKU模具号", skuMouldCodes)
                            + ", " + PriorityTraceLogHelper.kv("交集模具号", intersectMouldCodes)
                            + ", " + PriorityTraceLogHelper.kv("同胎胚", PriorityTraceLogHelper.oneZero(sameCarcass))
                            + ", " + PriorityTraceLogHelper.kv("同模具", PriorityTraceLogHelper.oneZero(sameMold))
                            + ", " + PriorityTraceLogHelper.kv("满足换活字块", PriorityTraceLogHelper.oneZero(canChange))
                            + ", " + PriorityTraceLogHelper.kv("SortKey", sortKey)
                            + ", " + PriorityTraceLogHelper.kv("HitLevel", hitLevel));
        }
    }

    /**
     * 解析换活字块选中SKU原因。
     *
     * @param context 排程上下文
     * @param machine 机台
     * @param selectedSku 选中SKU
     * @param candidates 候选SKU
     * @return 选中原因
     */
    private String resolveTypeBlockSelectReason(LhScheduleContext context, MachineScheduleDTO machine,
                                                SkuScheduleDTO selectedSku,
                                                List<SkuScheduleDTO> candidates) {
        if (selectedSku == null) {
            return "无候选SKU";
        }
        List<String> reasons = new ArrayList<>(4);
        if (!CollectionUtils.isEmpty(candidates) && candidates.contains(selectedSku)) {
            reasons.add("同胎胚+同模具");
        }
        if (isSameCarcass(context, machine, selectedSku)) {
            reasons.add("胎胚一致");
        }
        if (isSameMold(context, machine, selectedSku)) {
            reasons.add("模具一致");
        }
        if (isTypeBlockCandidate(context, machine, selectedSku, false)) {
            reasons.add("满足换活字块条件");
        }
        if (reasons.isEmpty()) {
            reasons.add("排序首位默认");
        }
        return String.join("，", reasons);
    }

    /**
     * 组装候选SKU编码摘要。
     *
     * @param skuList SKU列表
     * @return 摘要文本
     */
    private String buildSkuCodeSummary(List<SkuScheduleDTO> skuList) {
        if (CollectionUtils.isEmpty(skuList)) {
            return "-";
        }
        List<String> materialCodes = new ArrayList<>(skuList.size());
        for (SkuScheduleDTO sku : skuList) {
            materialCodes.add(PriorityTraceLogHelper.safeText(sku.getMaterialCode()));
        }
        return String.join(",", materialCodes);
    }

    /**
     * 识别释放后可优先参与换活字块的续作机台。
     *
     * <p>包括首日无计划释放机台和续作收尾小余量且前日 T+1 夜班未排满不排产释放机台。该入口只扩展 S4.4
     * 换活字块候选机台来源，不改变 S4.5 新增排序和机台筛选规则。</p>
     *
     * @param context 排程上下文
     * @return 释放机台列表
     */
    private List<MachineScheduleDTO> resolveReleasedTypeBlockMachines(LhScheduleContext context) {
        List<MachineScheduleDTO> releasedMachineList = new ArrayList<>();
        if (context == null
                || CollectionUtils.isEmpty(context.getMachineScheduleMap())) {
            return releasedMachineList;
        }
        Set<String> releasedMachineCodeSet = new LinkedHashSet<String>(16);
        if (!CollectionUtils.isEmpty(context.getFirstDayNoPlanReleasedContinuousMachineCodeSet())) {
            releasedMachineCodeSet.addAll(context.getFirstDayNoPlanReleasedContinuousMachineCodeSet());
        }
        if (!CollectionUtils.isEmpty(context.getTypeBlockReleasedContinuousMachineCodeSet())) {
            releasedMachineCodeSet.addAll(context.getTypeBlockReleasedContinuousMachineCodeSet());
        }
        for (String machineCode : releasedMachineCodeSet) {
            if (StringUtils.isEmpty(machineCode)) {
                continue;
            }
            MachineScheduleDTO machine = context.getMachineScheduleMap().get(machineCode);
            if (machine == null
                    || StringUtils.isEmpty(machine.getCurrentMaterialCode())
                    || machine.getEstimatedEndTime() == null) {
                continue;
            }
            if (isMachineAssignedContinuousResult(context, machineCode)) {
                log.info("续作释放机台已有续作分配，跳过换活字块接管, machineCode: {}, currentMaterialCode: {}, triggerSource: {}",
                        machineCode, machine.getCurrentMaterialCode(),
                        resolveReleasedTypeBlockTriggerSource(context, machineCode));
                continue;
            }
            releasedMachineList.add(machine);
        }
        return releasedMachineList;
    }

    /**
     * 解析续作释放机台进入换活字块的触发来源。
     *
     * @param context 排程上下文
     * @param machineCode 机台编码
     * @return 触发来源
     */
    private String resolveReleasedTypeBlockTriggerSource(LhScheduleContext context, String machineCode) {
        if (context != null && StringUtils.isNotEmpty(machineCode)
                && !CollectionUtils.isEmpty(context.getTypeBlockReleasedContinuousMachineCodeSet())
                && context.getTypeBlockReleasedContinuousMachineCodeSet().contains(machineCode)) {
            return TYPE_BLOCK_TRIGGER_SMALL_ENDING_SURPLUS_RELEASE;
        }
        return TYPE_BLOCK_TRIGGER_FIRST_DAY_NO_PLAN_RELEASE;
    }

    /**
     * 识别可参与换活字块兜底反查的机台。
     *
     * @param context 排程上下文
     * @return 兜底机台列表
     */
    private List<MachineScheduleDTO> resolveTypeBlockFallbackMachines(LhScheduleContext context) {
        List<MachineScheduleDTO> fallbackMachineList = new ArrayList<>();
        if (context == null
                || CollectionUtils.isEmpty(context.getMachineScheduleMap())
                || CollectionUtils.isEmpty(context.getMachineOnlineInfoMap())) {
            return fallbackMachineList;
        }
        for (MachineScheduleDTO machine : context.getMachineScheduleMap().values()) {
            if (machine == null || machine.isEnding() || machine.getEstimatedEndTime() == null) {
                continue;
            }
            String machineCode = machine.getMachineCode();
            if (StringUtils.isEmpty(machineCode)
                    || !context.getMachineOnlineInfoMap().containsKey(machineCode)
                    || StringUtils.isEmpty(machine.getCurrentMaterialCode())) {
                continue;
            }
            if (isMachineAssignedContinuousResult(context, machineCode)) {
                continue;
            }
            if (!isTypeBlockFallbackEligibleByPreviousDay(context, machine)) {
                continue;
            }
            fallbackMachineList.add(machine);
        }
        return fallbackMachineList;
    }

    /**
     * 判定机台是否已命中续作分配。
     *
     * @param context 排程上下文
     * @param machineCode 机台编码
     * @return true-已命中续作分配
     */
    private boolean isMachineAssignedContinuousResult(LhScheduleContext context, String machineCode) {
        if (context == null
                || StringUtils.isEmpty(machineCode)
                || CollectionUtils.isEmpty(context.getMachineAssignmentMap())) {
            return false;
        }
        List<LhScheduleResult> assignedResults = context.getMachineAssignmentMap().get(machineCode);
        if (CollectionUtils.isEmpty(assignedResults)) {
            return false;
        }
        for (LhScheduleResult assignedResult : assignedResults) {
            if (assignedResult != null
                    && StringUtils.equals(CONTINUOUS_SCHEDULE_TYPE, assignedResult.getScheduleType())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判定兜底机台是否通过 T-1 收尾校验。
     *
     * @param context 排程上下文
     * @param machine 机台
     * @return true-通过校验
     */
    private boolean isTypeBlockFallbackEligibleByPreviousDay(LhScheduleContext context, MachineScheduleDTO machine) {
        if (context == null || machine == null) {
            return false;
        }
        String machineCode = machine.getMachineCode();
        String currentMaterialCode = machine.getCurrentMaterialCode();
        if (StringUtils.isEmpty(machineCode) || StringUtils.isEmpty(currentMaterialCode)) {
            return false;
        }
        LhScheduleResult latestPreviousResult = resolveLatestPreviousResult(context, machineCode, currentMaterialCode);
        if (latestPreviousResult == null || StringUtils.equals(YES_FLAG, latestPreviousResult.getIsEnd())) {
            return true;
        }
        traceTypeBlockFallbackSkip(context, machine, latestPreviousResult, TYPE_BLOCK_SKIP_REASON_T1_NOT_END);
        return false;
    }

    /**
     * 解析 T-1 同机台同SKU的最新一条排程结果。
     *
     * @param context 排程上下文
     * @param machineCode 机台编码
     * @param materialCode 物料编码
     * @return 最新结果
     */
    private LhScheduleResult resolveLatestPreviousResult(LhScheduleContext context,
                                                         String machineCode,
                                                         String materialCode) {
        if (context == null
                || StringUtils.isEmpty(machineCode)
                || StringUtils.isEmpty(materialCode)
                || CollectionUtils.isEmpty(context.getPreviousScheduleResultList())) {
            return null;
        }
        LhScheduleResult latestResult = null;
        Date latestTime = null;
        for (LhScheduleResult previousResult : context.getPreviousScheduleResultList()) {
            if (previousResult == null
                    || !StringUtils.equals(machineCode, previousResult.getLhMachineCode())
                    || !StringUtils.equals(materialCode, previousResult.getMaterialCode())) {
                continue;
            }
            Date currentTime = resolvePreviousResultOrderTime(previousResult);
            if (latestResult == null) {
                latestResult = previousResult;
                latestTime = currentTime;
                continue;
            }
            if (latestTime == null || (currentTime != null && currentTime.after(latestTime))) {
                latestResult = previousResult;
                latestTime = currentTime;
            }
        }
        return latestResult;
    }

    /**
     * 解析 T-1 记录排序时间。
     *
     * @param previousResult T-1排程结果
     * @return 排序时间
     */
    private Date resolvePreviousResultOrderTime(LhScheduleResult previousResult) {
        if (previousResult == null) {
            return null;
        }
        if (previousResult.getSpecEndTime() != null) {
            return previousResult.getSpecEndTime();
        }
        return previousResult.getCreateTime();
    }

    /**
     * 输出兜底机台被跳过的决策日志。
     *
     * @param context 排程上下文
     * @param machine 机台
     * @param latestPreviousResult T-1最新结果
     * @param reason 跳过原因
     */
    private void traceTypeBlockFallbackSkip(LhScheduleContext context,
                                            MachineScheduleDTO machine,
                                            LhScheduleResult latestPreviousResult,
                                            String reason) {
        if (machine == null) {
            return;
        }
        log.info("换活字块兜底机台跳过, 机台: {}, 当前物料: {}, 原因: {}",
                machine.getMachineCode(), machine.getCurrentMaterialCode(), reason);
        if (!PriorityTraceLogHelper.isEnabled(context)) {
            return;
        }
        String title = "收尾机台衔接决策";
        StringBuilder detailBuilder = new StringBuilder(384);
        PriorityTraceLogHelper.appendLine(detailBuilder,
                "机台=" + PriorityTraceLogHelper.safeText(machine.getMachineCode())
                        + ", 当前物料=" + PriorityTraceLogHelper.safeText(machine.getCurrentMaterialCode())
                        + ", 触发来源=" + TYPE_BLOCK_TRIGGER_FALLBACK);
        PriorityTraceLogHelper.appendLine(detailBuilder,
                "衔接结果=未衔接, 原因=" + PriorityTraceLogHelper.safeText(reason)
                        + ", T-1最新isEnd=" + PriorityTraceLogHelper.safeText(
                        latestPreviousResult == null ? null : latestPreviousResult.getIsEnd())
                        + ", T-1最新排序时间=" + PriorityTraceLogHelper.formatDateTime(
                        resolvePreviousResultOrderTime(latestPreviousResult)));
        String detail = detailBuilder.toString().trim();
        PriorityTraceLogHelper.appendProcessLog(context, title, detail);
    }

    /**
     * 构建排程结果，分配各班次计划量。
     *
     * @param context 排程上下文
     * @param machine 机台
     * @param sku SKU
     * @param startTime 开产时间
     * @param switchStartTime 切换开始时间
     * @param shifts 班次
     * @param mouldQty 模台数
     * @param isEnding 是否收尾
     * @return 排程结果
     */
    private LhScheduleResult buildScheduleResult(LhScheduleContext context,
                                                  MachineScheduleDTO machine,
                                                  SkuScheduleDTO sku,
                                                  Date startTime,
                                                  Date switchStartTime,
                                                  List<LhShiftConfigVO> shifts,
                                                  int mouldQty,
                                                  boolean isEnding) {
        LhScheduleResult result = new LhScheduleResult();
        result.setFactoryCode(context.getFactoryCode());
        result.setBatchNo(context.getBatchNo());
        result.setLhMachineCode(machine.getMachineCode());
        result.setLhMachineName(machine.getMachineName());
        result.setLeftRightMould(LeftRightMouldUtil.resolveLeftRightMould(
                result.getLeftRightMould(), machine.getMachineCode()));
        result.setMaterialCode(sku.getMaterialCode());
        result.setMaterialDesc(sku.getMaterialDesc());
        result.setSpecCode(sku.getSpecCode());
        result.setSpecDesc(sku.getSpecDesc());
        result.setEmbryoCode(sku.getEmbryoCode());
        // 落库口径：库存未知(-1)按0落库，但排程过程仍保留-1语义用于跳过库存裁剪。
        result.setEmbryoStock(Math.max(sku.getEmbryoStock(), 0));
        result.setMainMaterialDesc(sku.getMainMaterialDesc());
        result.setStructureName(sku.getStructureName());
        result.setScheduleDate(context.getScheduleTargetDate());
        result.setLhTime(sku.getLhTimeSeconds());
        result.setMouldQty(mouldQty);
        int runtimeShiftCapacity = ShiftCapacityResolverUtil.resolveRuntimeShiftCapacity(
                context, machine, sku.getShiftCapacity());
        result.setSingleMouldShiftQty(SingleMouldShiftQtyUtil.resolveSingleMouldShiftQty(
                context, sku, machine, mouldQty));
        result.setDailyPlanQty(0);
        result.setTotalDailyPlanQty(sku.getMonthPlanQty());
        result.setMouldSurplusQty(sku.getSurplusQty());
        result.setTotalFinishQty(sku.getFinishedQty());
        result.setIsEnd(isEnding ? YES_FLAG : NO_FLAG);
        result.setIsDelivery(sku.isDeliveryLocked() ? YES_FLAG : NO_FLAG);
        result.setIsRelease(NO_FLAG);
        result.setDataSource(AUTO_DATA_SOURCE);
        result.setIsDelete(0);
        result.setScheduleType(ScheduleTypeEnum.TYPE_BLOCK.getCode());
        result.setIsTypeBlock(YES_FLAG);
        result.setConstructionStage(sku.getConstructionStage());
        // 产品状态从月计划获取
        result.setProductStatus(sku.getProductStatus());

        // 通过物料编码+产品状态查询SKU与示方书关系获取文字/硫化/制造示方书号
        String embryoNo = null;
        String textNo = null;
        String lhNo = null;
        String lhType = null;
        MdmSkuConstructionRef constructionRef = context.findSkuConstructionRef(
                sku.getMaterialCode(), sku.getProductStatus());
        if (constructionRef != null) {
            embryoNo = constructionRef.getEmbryoNo();
            textNo = constructionRef.getTextNo();
            lhNo = constructionRef.getLhNo();
            lhType = constructionRef.getLhType();
        }
        // 设置1-8班硫化示方书号和硫化示方书类型
        result.setClass1LhNo(lhNo);
        result.setClass1LhType(lhType);
        result.setClass2LhNo(lhNo);
        result.setClass2LhType(lhType);
        result.setClass3LhNo(lhNo);
        result.setClass3LhType(lhType);
        result.setClass4LhNo(lhNo);
        result.setClass4LhType(lhType);
        result.setClass5LhNo(lhNo);
        result.setClass5LhType(lhType);
        result.setClass6LhNo(lhNo);
        result.setClass6LhType(lhType);
        result.setClass7LhNo(lhNo);
        result.setClass7LhType(lhType);
        result.setClass8LhNo(lhNo);
        result.setClass8LhType(lhType);
        // 文字/硫化/制造示方书号回写：关系查不到时置空，以关系值为准
        result.setLhNo(lhNo);
        result.setChangedTrialStatus(lhType);
        result.setEmbryoNo(embryoNo);
        result.setTextNo(textNo);
        result.setMonthPlanVersion(sku.getMonthPlanVersion());
        result.setProductionVersion(sku.getProductionVersion());
        result.setIsTrial(sku.isTrial() ? YES_FLAG : NO_FLAG);
        result.setMachineOrder(machine.getMachineOrder());
        result.setHasSpecialMaterial(LhSpecialMaterialUtil.resolveHasSpecialMaterial(context, sku));

        // 生成工单号。
        result.setOrderNo(generateOrderNo(context));

        int refinedTargetQty = getTargetScheduleQtyResolver().refineTargetQtyByMachineCapacity(
                context, sku, machine, switchStartTime, startTime, shifts,
                ScheduleTypeEnum.TYPE_BLOCK.getCode());
        List<MachineCleaningWindowDTO> cleaningWindowList = new ArrayList<>(MachineCleaningOverlapUtil.excludeOverlapWindows(
                machine.getCleaningWindowList(), switchStartTime, startTime));
        List<MachineMaintenanceWindowDTO> maintenanceWindowList = resolveMachineMaintenanceWindowList(
                context, machine.getMachineCode());

        // 按班次分配计划量。
        distributeToShifts(context, result, shifts, startTime,
                runtimeShiftCapacity, sku.getLhTimeSeconds(), mouldQty, refinedTargetQty, cleaningWindowList,
                maintenanceWindowList);

        refreshResultSummary(context, result, shifts);
        result.setRealScheduleDate(context.getScheduleDate());
        result.setProductionStatus(NO_FLAG);

        return result;
    }

    /**
     * 向各班次分配计划量。
     *
     * @param context 排程上下文
     * @param result 排程结果
     * @param shifts 班次
     * @param startTime 开产时间
     * @param shiftCapacity 单模班产能
     * @param lhTimeSeconds 硫化时间
     * @param mouldQty 模台数
     * @param remaining 剩余目标量
     * @param cleaningWindowList 清洗窗口
     * @param maintenanceWindowList 保养窗口
     * @return 未排剩余量
     */
    private int distributeToShifts(LhScheduleContext context,
                                   LhScheduleResult result,
                                   List<LhShiftConfigVO> shifts,
                                   Date startTime,
                                   int shiftCapacity,
                                   int lhTimeSeconds,
                                   int mouldQty,
                                   int remaining,
                                   List<MachineCleaningWindowDTO> cleaningWindowList,
                                   List<MachineMaintenanceWindowDTO> maintenanceWindowList) {
        if (lhTimeSeconds <= 0 || mouldQty <= 0 || remaining <= 0) {
            return remaining;
        }
        Map<Integer, ShiftRuntimeState> stateMap = context.getShiftRuntimeStateMap();
        int dryIceLossQty = context.getParamIntValue(
                LhScheduleParamConstant.DRY_ICE_LOSS_QTY, LhScheduleConstant.DRY_ICE_LOSS_QTY);
        int dryIceDurationHours = context.getParamIntValue(
                LhScheduleParamConstant.DRY_ICE_DURATION_HOURS, LhScheduleConstant.DRY_ICE_DURATION_HOURS);
        String configPlusShiftType = ShiftCapacityResolverUtil.resolveOddShiftCapacityPlusShiftType(context);
        Map<Integer, Integer> dailyStandardShiftCapacityMap = calculateDailyStandardShiftCapacityMap(
                context, result, shifts, startTime, shiftCapacity, lhTimeSeconds, mouldQty,
                cleaningWindowList, maintenanceWindowList);

        boolean started = false;
        for (LhShiftConfigVO shift : shifts) {
            if (remaining <= 0) {
                break;
            }
            if (!started) {
                if (startTime != null && !startTime.before(shift.getShiftEndDateTime())
                        && shift != shifts.get(shifts.size() - 1)) {
                    continue;
                }
                started = true;
            }

            ShiftProductionControlDTO control = ShiftProductionControlUtil.resolveEffectiveControl(context, shift, startTime);
            if (control == null || !control.isCanSchedule()) {
                continue;
            }
            Date effectiveStart = control.getEffectiveStartTime();
            Date effectiveEnd = control.getEffectiveEndTime();
            int actualShiftPlanQty = ShiftCapacityResolverUtil.resolveActualShiftPlanQty(
                    shiftCapacity, shift, configPlusShiftType, ScheduleTypeEnum.TYPE_BLOCK.getCode());
            boolean oddShiftAdjustEnabled = ShiftCapacityResolverUtil.isOddShiftCapacityAdjustEnabled(
                    shiftCapacity, shift, configPlusShiftType, ScheduleTypeEnum.TYPE_BLOCK.getCode());
            log.debug("奇数班产修正检查, 当前流程: 换活字块排产, materialCode: {}, machineCode: {}, 参数是否配置: {}, "
                            + "参数值: {}, 配置值是否合法: {}, 是否启用: {}, 未启用原因: {}, 原始班产: {}, "
                            + "班次序号: {}, 当前班别: {}, 当前班次修正后的计划量: {}, 班产落库字段值: {}",
                    result.getMaterialCode(), result.getLhMachineCode(), StringUtils.isNotEmpty(configPlusShiftType),
                    configPlusShiftType,
                    ShiftCapacityResolverUtil.isOddShiftCapacityPlusShiftTypeValid(configPlusShiftType),
                    oddShiftAdjustEnabled,
                    ShiftCapacityResolverUtil.resolveOddShiftCapacityDisabledReason(
                            shiftCapacity, shift, configPlusShiftType, ScheduleTypeEnum.TYPE_BLOCK.getCode()),
                    shiftCapacity, shift.getShiftIndex(), shift.resolveShiftTypeEnum(), actualShiftPlanQty,
                    shiftCapacity);

            int shiftMaxQty = ShiftCapacityResolverUtil.resolveShiftCapacityWithDowntime(
                    context.getDevicePlanShutList(),
                    cleaningWindowList,
                    maintenanceWindowList,
                    result.getLhMachineCode(),
                    effectiveStart,
                    effectiveEnd,
                    shiftCapacity,
                    lhTimeSeconds,
                    mouldQty,
                    ShiftCapacityResolverUtil.resolveShiftDurationSeconds(shift),
                    dryIceLossQty,
                    dryIceDurationHours,
                    shift,
                    configPlusShiftType,
                    ScheduleTypeEnum.TYPE_BLOCK.getCode());
            shiftMaxQty = ShiftProductionControlUtil.deductCapacityByControl(control, shiftMaxQty, mouldQty);
            shiftMaxQty = dailyStandardShiftCapacityMap.getOrDefault(shift.getShiftIndex(), shiftMaxQty);
            if (shiftMaxQty <= 0) {
                continue;
            }
            if (oddShiftAdjustEnabled) {
                log.info("奇数班产修正命中, 当前流程: 换活字块排产, materialCode: {}, machineCode: {}, 参数值: {}, "
                                + "原始班产: {}, 班次序号: {}, 当前班别: {}, 修正后班次计划量: {}, 班产落库字段值: {}",
                        result.getMaterialCode(), result.getLhMachineCode(), configPlusShiftType, shiftCapacity,
                        shift.getShiftIndex(), shift.resolveShiftTypeEnum(), actualShiftPlanQty, shiftCapacity);
            }
            int shiftQty = ShiftCapacityResolverUtil.normalizeAllocatedShiftQty(
                    Math.min(remaining, shiftMaxQty), shiftMaxQty, mouldQty);
            if (shiftQty <= 0) {
                continue;
            }

            Date shiftPlanEndTime = ShiftCapacityResolverUtil.resolveShiftPlanEndTime(
                    context.getDevicePlanShutList(),
                    cleaningWindowList,
                    maintenanceWindowList,
                    result.getLhMachineCode(),
                    effectiveStart,
                    effectiveEnd,
                    shiftQty,
                    shiftMaxQty);
            setShiftPlanQty(result, shift.getShiftIndex(), shiftQty, effectiveStart, shiftPlanEndTime);
            remaining -= shiftQty;
            startTime = null;

            if (!CollectionUtils.isEmpty(stateMap)) {
                ShiftRuntimeState st = stateMap.get(shift.getShiftIndex());
                if (st != null) {
                    st.setRemainingCapacity(Math.max(0, shiftMaxQty - shiftQty));
                }
            }
        }
        return remaining;
    }

    /**
     * 按SKU日标准产量修正换活字块班次最大计划量。
     *
     * @param context 排程上下文
     * @param result 换活字块结果
     * @param shifts 班次列表
     * @param startTime 首个可排开始时间
     * @param shiftCapacity 运行态班产
     * @param lhTimeSeconds 硫化时长
     * @param mouldQty 模台数
     * @param cleaningWindowList 清洗窗口
     * @param maintenanceWindowList 保养窗口
     * @return 修正后的班次最大计划量
     */
    private Map<Integer, Integer> calculateDailyStandardShiftCapacityMap(LhScheduleContext context,
                                                                         LhScheduleResult result,
                                                                         List<LhShiftConfigVO> shifts,
                                                                         Date startTime,
                                                                         int shiftCapacity,
                                                                         int lhTimeSeconds,
                                                                         int mouldQty,
                                                                         List<MachineCleaningWindowDTO> cleaningWindowList,
                                                                         List<MachineMaintenanceWindowDTO> maintenanceWindowList) {
        Map<Integer, Integer> rawShiftCapacityMap = new LinkedHashMap<Integer, Integer>(
                CollectionUtils.isEmpty(shifts) ? 0 : shifts.size());
        if (context == null || result == null || CollectionUtils.isEmpty(shifts)
                || shiftCapacity <= 0 || lhTimeSeconds <= 0 || mouldQty <= 0) {
            return rawShiftCapacityMap;
        }
        int dryIceLossQty = context.getParamIntValue(
                LhScheduleParamConstant.DRY_ICE_LOSS_QTY, LhScheduleConstant.DRY_ICE_LOSS_QTY);
        int dryIceDurationHours = context.getParamIntValue(
                LhScheduleParamConstant.DRY_ICE_DURATION_HOURS, LhScheduleConstant.DRY_ICE_DURATION_HOURS);
        String configPlusShiftType = ShiftCapacityResolverUtil.resolveOddShiftCapacityPlusShiftType(context);
        boolean started = false;
        for (LhShiftConfigVO shift : shifts) {
            if (!started) {
                if (startTime != null && !startTime.before(shift.getShiftEndDateTime())
                        && shift != shifts.get(shifts.size() - 1)) {
                    continue;
                }
                started = true;
            }
            ShiftProductionControlDTO control = ShiftProductionControlUtil.resolveEffectiveControl(context, shift, startTime);
            if (control == null || !control.isCanSchedule()) {
                continue;
            }
            int shiftMaxQty = ShiftCapacityResolverUtil.resolveShiftCapacityWithDowntime(
                    context.getDevicePlanShutList(),
                    cleaningWindowList,
                    maintenanceWindowList,
                    result.getLhMachineCode(),
                    control.getEffectiveStartTime(),
                    control.getEffectiveEndTime(),
                    shiftCapacity,
                    lhTimeSeconds,
                    mouldQty,
                    ShiftCapacityResolverUtil.resolveShiftDurationSeconds(shift),
                    dryIceLossQty,
                    dryIceDurationHours,
                    shift,
                    configPlusShiftType,
                    ScheduleTypeEnum.TYPE_BLOCK.getCode());
            shiftMaxQty = ShiftProductionControlUtil.deductCapacityByControl(control, shiftMaxQty, mouldQty);
            rawShiftCapacityMap.put(shift.getShiftIndex(), Math.max(0, shiftMaxQty));
        }
        int dailyStandardQty = ShiftCapacityResolverUtil.resolveDailyStandardQty(context, result.getMaterialCode());
        String remainShiftType = ShiftCapacityResolverUtil.resolveDailyStandardCapacityRemainShiftType(context);
        boolean singleControlMachine = LhSingleControlMachineUtil.isConfiguredSingleControlMachine(
                context, result.getLhMachineCode());
        Map<Integer, Integer> adjustedMap = ShiftCapacityResolverUtil.adjustShiftPlanQtyMapByDailyStandard(
                shifts, rawShiftCapacityMap, dailyStandardQty, shiftCapacity, remainShiftType,
                singleControlMachine, ScheduleTypeEnum.TYPE_BLOCK.getCode());
        if (!Objects.equals(rawShiftCapacityMap, adjustedMap)) {
            log.info("日标准产量班次计划量修正, 当前流程: 换活字块排产, materialCode: {}, machineCode: {}, "
                            + "是否单控机台: {}, SKU日标准产量: {}, 班产: {}, 日标准产量剩余班次参数值: {}, "
                            + "修正前班次计划量: {}, 修正后班次计划量: {}",
                    result.getMaterialCode(), result.getLhMachineCode(), singleControlMachine,
                    dailyStandardQty, shiftCapacity, remainShiftType, rawShiftCapacityMap, adjustedMap);
        }
        return adjustedMap;
    }

    /**
     * 按班次索引设置计划量和开始结束时间。
     *
     * @param result 排程结果
     * @param shiftIndex 班次索引
     * @param qty 计划量
     * @param startTime 开始时间
     * @param endTime 结束时间
     */
    private void setShiftPlanQty(LhScheduleResult result, int shiftIndex, int qty, Date startTime, Date endTime) {
        ShiftFieldUtil.setShiftPlanQty(result, shiftIndex, qty, startTime, endTime);
    }

    /**
     * 计算规格收尾时间。
     *
     * @param context 排程上下文
     * @param result 排程结果
     * @param shifts 班次
     * @param lhTimeSeconds 硫化时间
     * @param mouldQty 模台数
     * @param isEnding 是否收尾
     * @return 收尾时间
     */
    private Date calcSpecEndTime(LhScheduleContext context,
                                 LhScheduleResult result,
                                 List<LhShiftConfigVO> shifts,
                                 int lhTimeSeconds,
                                 int mouldQty,
                                 boolean isEnding) {
        if (!isEnding) {
            return null;
        }
        // 找到最后一个有计划量的班次，按真实产量推导完工时刻。
        for (int i = shifts.size() - 1; i >= 0; i--) {
            LhShiftConfigVO shift = shifts.get(i);
            Integer shiftPlanQty = ShiftFieldUtil.getShiftPlanQty(result, shift.getShiftIndex());
            Date shiftStartTime = ShiftFieldUtil.getShiftStartTime(result, shift.getShiftIndex());
            Date shiftEndTime = ShiftFieldUtil.getShiftEndTime(result, shift.getShiftIndex());
            if (shiftEndTime == null) {
                shiftEndTime = shift.getShiftEndDateTime();
            }
            if (shiftPlanQty == null || shiftPlanQty <= 0 || shiftStartTime == null) {
                continue;
            }
            if (lhTimeSeconds <= 0 || mouldQty <= 0) {
                return shiftEndTime;
            }
            long secondsNeeded = (long) Math.ceil((double) shiftPlanQty / mouldQty) * lhTimeSeconds;
            List<MachineCleaningWindowDTO> cleaningWindowList = resolveEffectiveCleaningWindowList(
                    context, result, resolveFirstPlannedShiftStartTime(result));
            Date shiftCompletionTime = ShiftCapacityResolverUtil.resolveCompletionTimeWithDowntimes(
                    context.getDevicePlanShutList(),
                    cleaningWindowList,
                    result.getLhMachineCode(),
                    shiftStartTime,
                    secondsNeeded);
            if (shiftCompletionTime != null) {
                return constrainCompletionWithinShift(shiftCompletionTime, shiftEndTime);
            }
            return shiftEndTime;
        }
        return null;
    }

    /**
     * 刷新结果汇总字段。
     *
     * @param context 排程上下文
     * @param result 排程结果
     * @param shifts 班次
     */
    private void refreshResultSummary(LhScheduleContext context, LhScheduleResult result, List<LhShiftConfigVO> shifts) {
        if (result == null) {
            return;
        }
        ShiftFieldUtil.syncDailyPlanQty(result);
        if (result.getDailyPlanQty() == null || result.getDailyPlanQty() <= 0) {
            // 零计划结果不参与完工时刻语义。
            result.setSpecEndTime(null);
            result.setTdaySpecEndTime(null);
            ResultDowntimeSummaryUtil.clearDowntimeSummary(result);
            return;
        }
        int lhTimeSeconds = result.getLhTime() != null ? result.getLhTime() : 0;
        int mouldQty = ShiftCapacityResolverUtil.resolveMachineMouldQty(
                result.getMouldQty() != null ? result.getMouldQty() : 0);
        Date specEndTime = calcSpecEndTime(context, result, shifts, lhTimeSeconds, mouldQty,
                YES_FLAG.equals(result.getIsEnd()));
        if (specEndTime == null) {
            // 非收尾结果也要保留可推导完工时刻，避免后续校验出现 specEndTime 缺失。
            specEndTime = resolveActualCompletionTime(context, result);
        }
        result.setSpecEndTime(specEndTime);
        result.setTdaySpecEndTime(specEndTime);
        syncResultDowntimeSummary(context, result);
    }

    /**
     * 根据排程结果回写机台状态。
     *
     * @param context 排程上下文
     * @param machine 机台
     * @param sku SKU
     * @param result 排程结果
     */
    private void updateMachineState(LhScheduleContext context,
                                    MachineScheduleDTO machine,
                                    SkuScheduleDTO sku,
                                    LhScheduleResult result) {
        machine.setPreviousMaterialCode(machine.getCurrentMaterialCode());
        machine.setPreviousMaterialDesc(machine.getCurrentMaterialDesc());
        machine.setCurrentMaterialCode(sku.getMaterialCode());
        machine.setCurrentMaterialDesc(sku.getMaterialDesc());
        machine.setPreviousSpecCode(sku.getSpecCode());
        machine.setPreviousProSize(sku.getProSize());
        // 机台预计结束时间严格回写为实际完工时间，避免被整班结束时间放大。
        machine.setEstimatedEndTime(resolveActualCompletionTime(context, result));
        machine.setEnding(YES_FLAG.equals(result.getIsEnd()) && result.getSpecEndTime() != null);
    }

    /**
     * 解析排程结果的实际完工时间。
     *
     * @param context 排程上下文
     * @param result 排程结果
     * @return 实际完工时间
     */
    private Date resolveActualCompletionTime(LhScheduleContext context, LhScheduleResult result) {
        if (result == null) {
            return null;
        }
        int lhTimeSeconds = result.getLhTime() != null ? result.getLhTime() : 0;
        int mouldQty = ShiftCapacityResolverUtil.resolveMachineMouldQty(
                result.getMouldQty() != null ? result.getMouldQty() : 0);
        if (lhTimeSeconds > 0 && mouldQty > 0) {
            Date actualCompletionTime = null;
            List<MachineCleaningWindowDTO> cleaningWindowList = resolveEffectiveCleaningWindowList(
                    context, result, resolveFirstPlannedShiftStartTime(result));
            List<MachineMaintenanceWindowDTO> maintenanceWindowList = resolveMachineMaintenanceWindowList(
                    context, result.getLhMachineCode());
            for (int shiftIndex = 1; shiftIndex <= LhScheduleConstant.MAX_SHIFT_SLOT_COUNT; shiftIndex++) {
                Integer shiftPlanQty = ShiftFieldUtil.getShiftPlanQty(result, shiftIndex);
                Date shiftStartTime = ShiftFieldUtil.getShiftStartTime(result, shiftIndex);
                if (shiftPlanQty == null || shiftPlanQty <= 0 || shiftStartTime == null) {
                    continue;
                }
                Date shiftEndTime = ShiftFieldUtil.getShiftEndTime(result, shiftIndex);
                long secondsNeeded = (long) Math.ceil((double) shiftPlanQty / mouldQty) * lhTimeSeconds;
                Date shiftCompletionTime = ShiftCapacityResolverUtil.resolveCompletionTimeWithDowntimes(
                        context.getDevicePlanShutList(),
                        cleaningWindowList,
                        maintenanceWindowList,
                        result.getLhMachineCode(),
                        shiftStartTime,
                        secondsNeeded);
                if (shiftCompletionTime == null) {
                    shiftCompletionTime = shiftEndTime;
                } else {
                    shiftCompletionTime = constrainCompletionWithinShift(shiftCompletionTime, shiftEndTime);
                }
                if (actualCompletionTime == null || shiftCompletionTime.after(actualCompletionTime)) {
                    actualCompletionTime = shiftCompletionTime;
                }
            }
            if (actualCompletionTime != null) {
                return actualCompletionTime;
            }
        }
        return result.getSpecEndTime();
    }

    /**
     * 命中"模具清洗+换活字块"组合场景时，写入班次原因分析。
     *
     * @param context 排程上下文
     * @param result 排程结果
     * @param shifts 班次
     */
    private void applyTypeBlockCleaningAnalysis(LhScheduleContext context,
                                                LhScheduleResult result,
                                                List<LhShiftConfigVO> shifts) {
        if (context == null || result == null || CollectionUtils.isEmpty(shifts)) {
            return;
        }
        Date switchStartTime = result.getMouldChangeStartTime();
        Date productionStartTime = resolveFirstPlannedShiftStartTime(result);
        if (switchStartTime == null || productionStartTime == null) {
            return;
        }
        int firstPlannedShiftIndex = resolveFirstPlannedShiftIndex(result);
        if (firstPlannedShiftIndex <= 0) {
            return;
        }
        List<MachineCleaningWindowDTO> cleaningWindowList =
                resolveMachineCleaningWindowList(context, result.getLhMachineCode());
        if (!MachineCleaningOverlapUtil.hasBlockingOverlap(cleaningWindowList, switchStartTime, productionStartTime)) {
            return;
        }
        ShiftFieldUtil.setShiftAnalysis(result, firstPlannedShiftIndex, TYPE_BLOCK_CLEANING_ANALYSIS);
    }

    /**
     * 获取首个有排产量的班次索引。
     *
     * @param result 排程结果
     * @return 班次索引
     */
    private int resolveFirstPlannedShiftIndex(LhScheduleResult result) {
        if (result == null) {
            return -1;
        }
        for (int shiftIndex = 1; shiftIndex <= LhScheduleConstant.MAX_SHIFT_SLOT_COUNT; shiftIndex++) {
            Integer shiftQty = ShiftFieldUtil.getShiftPlanQty(result, shiftIndex);
            if (shiftQty != null && shiftQty > 0) {
                return shiftIndex;
            }
        }
        return -1;
    }

    /**
     * 获取首个有排产量的班次开始时间。
     *
     * @param result 排程结果
     * @return 开始时间
     */
    private Date resolveFirstPlannedShiftStartTime(LhScheduleResult result) {
        int firstPlannedShiftIndex = resolveFirstPlannedShiftIndex(result);
        if (firstPlannedShiftIndex <= 0) {
            return null;
        }
        return ShiftFieldUtil.getShiftStartTime(result, firstPlannedShiftIndex);
    }

    /**
     * 将完工时间限制在当前班次内。
     *
     * @param completionTime 完工时间
     * @param shiftEndTime 班次结束时间
     * @return 限制后时间
     */
    private Date constrainCompletionWithinShift(Date completionTime, Date shiftEndTime) {
        if (completionTime == null || shiftEndTime == null) {
            return completionTime;
        }
        return completionTime.after(shiftEndTime) ? shiftEndTime : completionTime;
    }

    /**
     * 解析机台清洗窗口。
     *
     * @param context 排程上下文
     * @param machineCode 机台编码
     * @return 清洗窗口
     */
    private List<MachineCleaningWindowDTO> resolveMachineCleaningWindowList(LhScheduleContext context, String machineCode) {
        MachineScheduleDTO machine = context.getMachineScheduleMap().get(machineCode);
        if (machine == null || CollectionUtils.isEmpty(machine.getCleaningWindowList())) {
            return new ArrayList<>();
        }
        return machine.getCleaningWindowList();
    }

    /**
     * 解析机台保养窗口。
     *
     * @param context 排程上下文
     * @param machineCode 机台编码
     * @return 保养窗口
     */
    private List<MachineMaintenanceWindowDTO> resolveMachineMaintenanceWindowList(LhScheduleContext context,
                                                                                  String machineCode) {
        MachineScheduleDTO machine = context.getMachineScheduleMap().get(machineCode);
        if (machine == null || CollectionUtils.isEmpty(machine.getMaintenanceWindowList())) {
            return new ArrayList<>();
        }
        return machine.getMaintenanceWindowList();
    }

    private List<MdmDevicePlanShut> resolveMachineShutdownWindowList(LhScheduleContext context, String machineCode) {
        if (context == null || CollectionUtils.isEmpty(context.getDevicePlanShutList())
                || StringUtils.isEmpty(machineCode)) {
            return new ArrayList<>();
        }
        List<MdmDevicePlanShut> shutdownWindowList = new ArrayList<>(4);
        for (MdmDevicePlanShut planShut : context.getDevicePlanShutList()) {
            if (planShut != null && StringUtils.equals(machineCode, planShut.getMachineCode())) {
                shutdownWindowList.add(planShut);
            }
        }
        return shutdownWindowList;
    }

    private void syncResultDowntimeSummary(LhScheduleContext context, LhScheduleResult result) {
        if (context == null || result == null) {
            return;
        }
        Date firstPlannedShiftStartTime = resolveFirstPlannedShiftStartTime(result);
        if (firstPlannedShiftStartTime == null || result.getSpecEndTime() == null) {
            ResultDowntimeSummaryUtil.clearDowntimeSummary(result);
            return;
        }
        ResultDowntimeSummaryUtil.fillDowntimeSummary(
                result,
                resolveMachineMaintenanceWindowList(context, result.getLhMachineCode()),
                resolveEffectiveCleaningWindowList(context, result, firstPlannedShiftStartTime),
                resolveMachineShutdownWindowList(context, result.getLhMachineCode()));
    }

    /**
     * 解析换活字块结果在排产阶段需要生效的清洗窗口。
     *
     * @param context 排程上下文
     * @param result 排程结果
     * @param firstProductionStartTime 首个有排产量班次开始时间
     * @return 有效清洗窗口
     */
    private List<MachineCleaningWindowDTO> resolveEffectiveCleaningWindowList(LhScheduleContext context,
                                                                              LhScheduleResult result,
                                                                              Date firstProductionStartTime) {
        if (result == null) {
            return new ArrayList<>(0);
        }
        List<MachineCleaningWindowDTO> cleaningWindowList = resolveMachineCleaningWindowList(
                context, result.getLhMachineCode());
        return new ArrayList<>(MachineCleaningOverlapUtil.excludeOverlapWindows(
                cleaningWindowList, result.getMouldChangeStartTime(), firstProductionStartTime));
    }

    /**
     * 解析机台当前物料胎胚编码。
     *
     * @param context 排程上下文
     * @param machine 机台
     * @return 胎胚编码
     */
    private String resolveMachineEmbryoCode(LhScheduleContext context, MachineScheduleDTO machine) {
        MdmMaterialInfo materialInfo = resolveMachineMaterialInfo(context, machine);
        if (materialInfo != null && StringUtils.isNotEmpty(materialInfo.getEmbryoCode())) {
            return materialInfo.getEmbryoCode();
        }
        SkuScheduleDTO currentSku = findSkuByMaterialCode(context.getContinuousSkuList(), machine.getCurrentMaterialCode());
        return currentSku != null ? currentSku.getEmbryoCode() : null;
    }

    /**
     * 解析机台当前物料胎胚描述。
     *
     * @param context 排程上下文
     * @param machine 机台
     * @return 胎胚描述
     */
    private String resolveMachineEmbryoDesc(LhScheduleContext context, MachineScheduleDTO machine) {
        MdmMaterialInfo materialInfo = resolveMachineMaterialInfo(context, machine);
        if (materialInfo != null && StringUtils.isNotEmpty(materialInfo.getEmbryoDesc())) {
            return normalizeCompareToken(materialInfo.getEmbryoDesc());
        }
        SkuScheduleDTO currentSku = findSkuByMaterialCode(context.getContinuousSkuList(), machine.getCurrentMaterialCode());
        return currentSku != null ? normalizeCompareToken(currentSku.getMainMaterialDesc()) : null;
    }

    /**
     * 解析 SKU 胎胚描述。
     *
     * @param context 排程上下文
     * @param sku SKU
     * @return 胎胚描述
     */
    private String resolveSkuEmbryoDesc(LhScheduleContext context, SkuScheduleDTO sku) {
        if (context == null || sku == null) {
            return null;
        }
        MdmMaterialInfo materialInfo = context.getMaterialInfoMap().get(sku.getMaterialCode());
        if (materialInfo != null && StringUtils.isNotEmpty(materialInfo.getEmbryoDesc())) {
            return normalizeCompareToken(materialInfo.getEmbryoDesc());
        }
        return normalizeCompareToken(sku.getMainMaterialDesc());
    }

    /**
     * 解析机台当前物料规格编码。
     *
     * @param context 排程上下文
     * @param machine 机台
     * @return 规格编码
     */
    private String resolveMachineSpecCode(LhScheduleContext context, MachineScheduleDTO machine) {
        if (StringUtils.isNotEmpty(machine.getPreviousSpecCode())) {
            return machine.getPreviousSpecCode();
        }
        MdmMaterialInfo materialInfo = resolveMachineMaterialInfo(context, machine);
        if (materialInfo != null && StringUtils.isNotEmpty(materialInfo.getSpecifications())) {
            return materialInfo.getSpecifications();
        }
        SkuScheduleDTO currentSku = findSkuByMaterialCode(context.getContinuousSkuList(), machine.getCurrentMaterialCode());
        return currentSku != null ? currentSku.getSpecCode() : null;
    }

    /**
     * 解析机台当前物料花纹键。
     *
     * @param context 排程上下文
     * @param machine 机台
     * @return 花纹键
     */
    private String resolveMachinePatternKey(LhScheduleContext context, MachineScheduleDTO machine) {
        MdmMaterialInfo materialInfo = resolveMachineMaterialInfo(context, machine);
        if (materialInfo != null) {
            return resolvePatternKey(materialInfo.getMainPattern(), materialInfo.getPattern());
        }
        SkuScheduleDTO currentSku = findSkuByMaterialCode(context.getContinuousSkuList(), machine.getCurrentMaterialCode());
        if (currentSku == null) {
            return null;
        }
        return resolvePatternKey(currentSku.getMainPattern(), currentSku.getPattern());
    }

    /**
     * 解析机台当前物料主花纹。
     *
     * @param context 排程上下文
     * @param machine 机台
     * @return 主花纹
     */
    private String resolveMachineMainPatternStrict(LhScheduleContext context, MachineScheduleDTO machine) {
        MdmMaterialInfo materialInfo = resolveMachineMaterialInfo(context, machine);
        if (materialInfo != null && StringUtils.isNotEmpty(materialInfo.getMainPattern())) {
            return normalizeCompareToken(materialInfo.getMainPattern());
        }
        SkuScheduleDTO currentSku = findSkuByMaterialCode(context.getContinuousSkuList(), machine.getCurrentMaterialCode());
        return currentSku != null ? normalizeCompareToken(currentSku.getMainPattern()) : null;
    }

    /**
     * 解析 SKU 主花纹。
     *
     * @param context 排程上下文
     * @param sku SKU
     * @return 主花纹
     */
    private String resolveSkuMainPatternStrict(LhScheduleContext context, SkuScheduleDTO sku) {
        if (context == null || sku == null) {
            return null;
        }
        MdmMaterialInfo materialInfo = context.getMaterialInfoMap().get(sku.getMaterialCode());
        if (materialInfo != null && StringUtils.isNotEmpty(materialInfo.getMainPattern())) {
            return normalizeCompareToken(materialInfo.getMainPattern());
        }
        return normalizeCompareToken(sku.getMainPattern());
    }

    /**
     * 解析机台当前物料基础信息。
     *
     * @param context 排程上下文
     * @param machine 机台
     * @return 物料基础信息
     */
    private MdmMaterialInfo resolveMachineMaterialInfo(LhScheduleContext context, MachineScheduleDTO machine) {
        if (context == null || machine == null || StringUtils.isEmpty(machine.getCurrentMaterialCode())) {
            return null;
        }
        return context.getMaterialInfoMap().get(machine.getCurrentMaterialCode());
    }

    /**
     * 按物料编码查找 SKU。
     *
     * @param skuList SKU列表
     * @param materialCode 物料编码
     * @return SKU
     */
    private SkuScheduleDTO findSkuByMaterialCode(List<SkuScheduleDTO> skuList, String materialCode) {
        if (CollectionUtils.isEmpty(skuList) || StringUtils.isEmpty(materialCode)) {
            return null;
        }
        for (SkuScheduleDTO sku : skuList) {
            if (StringUtils.equals(materialCode, sku.getMaterialCode())) {
                return sku;
            }
        }
        return null;
    }

    /**
     * 解析花纹键。
     *
     * @param mainPattern 主花纹
     * @param pattern 花纹
     * @return 花纹键
     */
    private String resolvePatternKey(String mainPattern, String pattern) {
        if (StringUtils.isNotEmpty(mainPattern)) {
            return mainPattern;
        }
        return StringUtils.isNotEmpty(pattern) ? pattern : null;
    }

    /**
     * 规范化比较文本。
     *
     * @param value 原始文本
     * @return 规范化文本
     */
    private String normalizeCompareToken(String value) {
        if (StringUtils.isEmpty(value)) {
            return null;
        }
        String normalizedValue = value.trim();
        return StringUtils.isEmpty(normalizedValue) ? null : normalizedValue;
    }

    /**
     * 解析换活字块结果实际使用的模具号。
     * <p>换活字块不是更换整副模具，因此不释放在机模具，也不按新SKU重新分配模具；
     * 结果字段沿用当前机台硫化在机信息中的实际模具号。</p>
     *
     * @param context 排程上下文
     * @param machine 当前机台
     * @param sku 候选SKU
     * @return 实际使用模具号，多个逗号分隔
     */
    private String resolveTypeBlockActualMouldCode(LhScheduleContext context,
                                                   MachineScheduleDTO machine,
                                                   SkuScheduleDTO sku) {
        if (context == null || machine == null) {
            return null;
        }
        int requiredMouldQty = ShiftCapacityResolverUtil.resolveMachineMouldQty(machine);
        String mouldCode = LhMouldCodeUtil.resolveInMachineMouldCode(context, machine.getMachineCode());
        if (StringUtils.isEmpty(mouldCode)) {
            log.info("换活字块结果在机实际模具号为空, machineCode: {}, currentMaterialCode: {}, materialCode: {}, "
                            + "requiredMouldQty: {}",
                    machine.getMachineCode(), machine.getCurrentMaterialCode(),
                    sku == null ? null : sku.getMaterialCode(), requiredMouldQty);
            return null;
        }
        log.debug("换活字块沿用在机实际模具号, machineCode: {}, currentMaterialCode: {}, materialCode: {}, "
                        + "requiredMouldQty: {}, actualMouldCode: {}",
                machine.getMachineCode(), machine.getCurrentMaterialCode(),
                sku == null ? null : sku.getMaterialCode(), requiredMouldQty, mouldCode);
        return mouldCode;
    }

    /**
     * 解析物料对应的模具编码集合。
     *
     * @param context 排程上下文
     * @param materialCode 物料编码
     * @return 模具编码集合
     */
    private Set<String> resolveMouldCodeSet(LhScheduleContext context, String materialCode) {
        Set<String> mouldCodeSet = new LinkedHashSet<>(4);
        if (context == null
                || StringUtils.isEmpty(materialCode)
                || !context.getSkuMouldRelMap().containsKey(materialCode)) {
            return mouldCodeSet;
        }
        for (MdmSkuMouldRel mouldRel : context.getSkuMouldRelMap().get(materialCode)) {
            String mouldCode = mouldRel == null ? null : normalizeCompareToken(mouldRel.getMouldCode());
            if (StringUtils.isEmpty(mouldCode)) {
                continue;
            }
            mouldCodeSet.add(mouldCode);
        }
        return mouldCodeSet;
    }

    /**
     * 注册机台排程分配记录。
     *
     * @param context 排程上下文
     * @param machineCode 机台编码
     * @param result 排程结果
     */
    private void registerMachineAssignment(LhScheduleContext context, String machineCode, LhScheduleResult result) {
        context.getMachineAssignmentMap()
                .computeIfAbsent(machineCode, k -> new ArrayList<>())
                .add(result);
    }

    /**
     * 生成工单号。
     *
     * @param context 排程上下文
     * @return 工单号
     */
    private String generateOrderNo(LhScheduleContext context) {
        return orderNoGenerator.generateOrderNo(context.getScheduleTargetDate());
    }

    /**
     * 获取目标排产量解析器。
     *
     * @return 目标排产量解析器
     */
    private TargetScheduleQtyResolver getTargetScheduleQtyResolver() {
        return targetScheduleQtyResolver != null
                ? targetScheduleQtyResolver
                : new TargetScheduleQtyResolver();
    }

    /**
     * 获取换模均衡策略。
     *
     * @return 换模均衡策略
     */
    private IMouldChangeBalanceStrategy getMouldChangeBalanceStrategy() {
        return mouldChangeBalanceStrategy;
    }

    /**
     * 获取首检均衡策略。
     *
     * @return 首检均衡策略
     */
    private IFirstInspectionBalanceStrategy getFirstInspectionBalanceStrategy() {
        return firstInspectionBalanceStrategy;
    }

    /**
     * 获取产能计算策略。
     *
     * @return 产能计算策略
     */
    private ICapacityCalculateStrategy getCapacityCalculateStrategy() {
        return capacityCalculateStrategy;
    }

    /**
     * 获取保养排程服务。
     *
     * @return 保养排程服务
     */
    private LhMaintenanceScheduleService getMaintenanceScheduleService() {
        return maintenanceScheduleService != null
                ? maintenanceScheduleService
                : new LhMaintenanceScheduleService();
    }

    /**
     * 换活字块结果按日计划账本回裁。
     * <p>收尾结果严格截断，非收尾超排记录为满班补齐。</p>
     *
     * @param context 排程上下文
     * @param sku SKU排程DTO
     * @param result 排程结果
     * @param shifts 班次列表
     * @return 回裁后的实际排产量
     */
    private int applyTypeBlockToDailyQuota(LhScheduleContext context,
                                           SkuScheduleDTO sku,
                                           LhScheduleResult result,
                                           List<LhShiftConfigVO> shifts) {
        Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap = sku.getDailyPlanQuotaMap();
        if (quotaMap == null || quotaMap.isEmpty()) {
            return result.getDailyPlanQty() != null ? result.getDailyPlanQty() : 0;
        }
        int totalShiftFillOverQty = 0;
        for (LhShiftConfigVO shift : shifts) {
            Integer planQty = ShiftFieldUtil.getShiftPlanQty(result, shift.getShiftIndex());
            if (planQty == null || planQty <= 0) {
                continue;
            }
            Date workDate = shift.getWorkDate();
            if (workDate == null) {
                continue;
            }
            LocalDate productionDate = workDate.toInstant()
                    .atZone(ZoneId.systemDefault()).toLocalDate();
            SkuDailyPlanQuotaDTO quota = quotaMap.get(productionDate);
            if (quota == null) {
                continue;
            }
            LocalDate lookAheadEndDate = resolveLookAheadEndDate(context, quotaMap, productionDate);
            // 先按只读账本计算本班允许落地量，再按模台数收敛，避免双模回裁出奇数计划量。
            int quotaCap = resolveConsumableRollingQuota(quotaMap, productionDate, planQty, lookAheadEndDate);
            int mouldQty = ShiftCapacityResolverUtil.resolveMachineMouldQty(
                    result.getMouldQty() != null ? result.getMouldQty() : 0);
            int allowedPlanQty = Math.min(planQty, quotaCap);
            int normalizedPlanQty = ShiftCapacityResolverUtil.normalizeAllocatedShiftQty(
                    allowedPlanQty, planQty, mouldQty);
            // 按历史欠产、当日计划、受限追补窗口消费同一SKU的日计划账本
            int consumed = normalizedPlanQty > 0
                    ? SkuDailyPlanQuotaUtil.consumeRollingQuota(
                    quotaMap, productionDate, normalizedPlanQty, lookAheadEndDate)
                    : 0;
            int overQty = planQty - consumed;
            if (overQty > 0) {
                boolean endingResult = YES_FLAG.equals(result.getIsEnd());
                // 收尾结果必须严格截断，不再记录满班补齐超排；
                // 试制等严格目标量场景仍需回裁，但保留超排账本用于追踪被截掉的补满量。
                if (endingResult || sku.isStrictTargetQty()) {
                    // 收尾/严格目标量场景不允许把换活字块后的满班补齐量落成有效计划量。
                    trimTypeBlockShiftPlanQty(result, shift.getShiftIndex(), consumed);
                    if (endingResult) {
                        continue;
                    }
                }
                // 无法冲抵的部分记录为满班补齐超排量
                quota.setShiftFillOverQty(quota.getShiftFillOverQty() + overQty);
                totalShiftFillOverQty += overQty;
                log.debug("换活字块班次满班补齐超排, materialCode: {}, 日期: {}, 班次: {}, 排产量: {}, 超排: {}",
                        sku.getMaterialCode(), productionDate, shift.getShiftIndex(), planQty, overQty);
            }
        }
        if (totalShiftFillOverQty > 0) {
            sku.setShiftFillOverQty(sku.getShiftFillOverQty() + totalShiftFillOverQty);
            context.getSkuShiftFillOverQtyMap().merge(sku.getMaterialCode(), totalShiftFillOverQty, Integer::sum);
        }
        refreshResultSummary(context, result, shifts);
        return result.getDailyPlanQty() != null ? result.getDailyPlanQty() : 0;
    }

    /**
     * 只读计算当前班次可消费的滚动日计划额度。
     *
     * @param quotaMap 日计划账本
     * @param productionDate 实际生产日期
     * @param planQty 本班计划量
     * @param lookAheadEndDate 允许借用的最晚日期
     * @return 可消费额度
     */
    private int resolveConsumableRollingQuota(Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap,
                                              LocalDate productionDate,
                                              int planQty,
                                              LocalDate lookAheadEndDate) {
        if (CollectionUtils.isEmpty(quotaMap) || productionDate == null || planQty <= 0) {
            return 0;
        }
        int consumableQty = 0;
        for (Map.Entry<LocalDate, SkuDailyPlanQuotaDTO> entry : quotaMap.entrySet()) {
            if (entry.getKey().isAfter(productionDate)) {
                continue;
            }
            consumableQty += resolveRemainingQuotaQty(entry.getValue(), planQty - consumableQty);
            if (consumableQty >= planQty) {
                return planQty;
            }
        }
        for (Map.Entry<LocalDate, SkuDailyPlanQuotaDTO> entry : quotaMap.entrySet()) {
            if (!entry.getKey().isAfter(productionDate)) {
                continue;
            }
            if (lookAheadEndDate != null && entry.getKey().isAfter(lookAheadEndDate)) {
                continue;
            }
            consumableQty += resolveRemainingQuotaQty(entry.getValue(), planQty - consumableQty);
            if (consumableQty >= planQty) {
                return planQty;
            }
        }
        return Math.max(0, consumableQty);
    }

    /**
     * 解析单日账本剩余额度。
     *
     * @param quota 单日账本
     * @param demandQty 需求量
     * @return 可用额度
     */
    private int resolveRemainingQuotaQty(SkuDailyPlanQuotaDTO quota, int demandQty) {
        if (quota == null || demandQty <= 0) {
            return 0;
        }
        return Math.min(Math.max(0, quota.getRemainingQty()), demandQty);
    }

    /**
     * 解析换活字块实际扣账允许追补的截止日期。
     *
     * @param context 排程上下文
     * @param quotaMap 日计划账本
     * @param productionDate 实际生产日期
     * @return 追补截止日期
     */
    private LocalDate resolveLookAheadEndDate(LhScheduleContext context,
                                              Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap,
                                              LocalDate productionDate) {
        return SkuDailyPlanQuotaUtil.resolveLookAheadEndDate(
                quotaMap, productionDate, resolveShortageLookAheadDays(context),
                resolveScheduleTargetLocalDate(context));
    }

    /**
     * 解析排程目标业务日期。
     *
     * @param context 排程上下文
     * @return 排程目标业务日期
     */
    private LocalDate resolveScheduleTargetLocalDate(LhScheduleContext context) {
        if (context == null) {
            return null;
        }
        if (!CollectionUtils.isEmpty(context.getScheduleWindowShifts())) {
            for (int index = context.getScheduleWindowShifts().size() - 1; index >= 0; index--) {
                LhShiftConfigVO shift = context.getScheduleWindowShifts().get(index);
                if (shift != null && shift.getWorkDate() != null) {
                    return shift.getWorkDate().toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
                }
            }
        }
        if (context.getWindowEndDate() == null) {
            return null;
        }
        return context.getWindowEndDate().toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
    }

    /**
     * 获取追补观察天数。
     *
     * @param context 排程上下文
     * @return 向后观察天数
     */
    private int resolveShortageLookAheadDays(LhScheduleContext context) {
        if (context == null || context.getScheduleConfig() == null) {
            return LhScheduleConstant.NEW_SPEC_SHORTAGE_LOOK_AHEAD_DAYS;
        }
        return context.getScheduleConfig().getNewSpecShortageLookAheadDays();
    }

    /**
     * 回裁换活字块单个班次计划量，并清空失效的结束时刻。
     *
     * @param result 排程结果
     * @param shiftIndex 班次索引
     * @param trimmedQty 回裁后的计划量
     */
    private void trimTypeBlockShiftPlanQty(LhScheduleResult result, int shiftIndex, int trimmedQty) {
        Date shiftStartTime = ShiftFieldUtil.getShiftStartTime(result, shiftIndex);
        if (trimmedQty <= 0) {
            setShiftPlanQty(result, shiftIndex, 0, null, null);
            return;
        }
        setShiftPlanQty(result, shiftIndex, trimmedQty, shiftStartTime, null);
    }
}
