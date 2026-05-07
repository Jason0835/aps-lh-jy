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
import com.zlt.aps.lh.api.domain.dto.SkuScheduleDTO;
import com.zlt.aps.lh.api.domain.entity.LhScheduleResult;
import com.zlt.aps.lh.api.domain.vo.LhShiftConfigVO;
import com.zlt.aps.lh.api.enums.ScheduleTypeEnum;
import com.zlt.aps.lh.component.OrderNoGenerator;
import com.zlt.aps.lh.component.TargetScheduleQtyResolver;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.engine.strategy.ICapacityCalculateStrategy;
import com.zlt.aps.lh.engine.strategy.IEndingJudgmentStrategy;
import com.zlt.aps.lh.engine.strategy.IFirstInspectionBalanceStrategy;
import com.zlt.aps.lh.engine.strategy.IMouldChangeBalanceStrategy;
import com.zlt.aps.lh.engine.strategy.ITypeBlockProductionStrategy;
import com.zlt.aps.lh.service.impl.LhMaintenanceScheduleService;
import com.zlt.aps.lh.util.LeftRightMouldUtil;
import com.zlt.aps.lh.util.LhMachineHardMatchUtil;
import com.zlt.aps.lh.util.LhScheduleTimeUtil;
import com.zlt.aps.lh.util.LhSpecialMaterialUtil;
import com.zlt.aps.lh.util.LhSpecifyMachineUtil;
import com.zlt.aps.lh.util.MachineCleaningOverlapUtil;
import com.zlt.aps.lh.util.PriorityTraceLogHelper;
import com.zlt.aps.lh.util.ShiftCapacityResolverUtil;
import com.zlt.aps.lh.util.ShiftFieldUtil;
import com.zlt.aps.lh.util.ShiftProductionControlUtil;
import com.zlt.aps.lh.util.SingleMouldShiftQtyUtil;
import com.zlt.aps.mdm.api.domain.entity.MdmDevicePlanShut;
import com.zlt.aps.mdm.api.domain.entity.MdmMaterialInfo;
import com.zlt.aps.mdm.api.domain.entity.MdmSkuMouldRel;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 换活字块排产子策略
 * <p>负责 S4.4 收尾后的换活字块衔接排产，与续作收尾策略分开维护。</p>
 *
 * @author APS
 */
@Slf4j
@Component("typeBlockProductionStrategy")
public class TypeBlockProductionStrategy implements ITypeBlockProductionStrategy {

    private static final String CONTINUOUS_SCHEDULE_TYPE = ScheduleTypeEnum.CONTINUOUS.getCode();
    private static final String TYPE_BLOCK_CLEANING_ANALYSIS = "模具清洗+换活字块";
    private static final String TYPE_BLOCK_TRIGGER_ENDING = "收尾触发";
    private static final String TYPE_BLOCK_TRIGGER_FALLBACK = "在机前规格兜底触发";
    private static final String TYPE_BLOCK_SKIP_REASON_T1_NOT_END =
            "T-1 最新记录未收尾，跳过兜底反查";
    private static final String TYPE_BLOCK_SKIP_REASON_LIMIT_SPECIFY_RESERVED =
            "机台存在需走新增换模链路的定点物料，当前阶段预留给S4.5";
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
        List<MachineScheduleDTO> endingMachines = context.getMachineScheduleMap().values().stream()
                .filter(m -> m.isEnding() && m.getEstimatedEndTime() != null)
                .collect(Collectors.toList());
        endingMachines.sort(Comparator.comparing(MachineScheduleDTO::getEstimatedEndTime));
        Map<String, String> machineTriggerSourceMap = new HashMap<>(Math.max(16, endingMachines.size() * 2));
        List<MachineScheduleDTO> candidateMachines = new ArrayList<>(endingMachines);
        for (MachineScheduleDTO endingMachine : endingMachines) {
            machineTriggerSourceMap.put(endingMachine.getMachineCode(), TYPE_BLOCK_TRIGGER_ENDING);
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
        traceEndingMachineOrder(context, candidateMachines);
        log.info("换活字块候选机台准备完成, 收尾机台: {}, 兜底机台: {}, 候选机台: {}, 待排新增SKU: {}",
                endingMachines.size(), fallbackMachines.size(), candidateMachines.size(),
                context.getNewSpecSkuList().size());

        Map<String, Boolean> strictPriorityOneMachineMap = new HashMap<>(Math.max(16, candidateMachines.size() * 2));
        Map<String, Boolean> completedMachineMap = new HashMap<>(Math.max(16, candidateMachines.size() * 2));
        int typeBlockScheduledCount = 0;
        while (!CollectionUtils.isEmpty(context.getNewSpecSkuList())) {
            List<MachineScheduleDTO> activeMachines = buildActiveMachineList(
                    candidateMachines, machineTriggerSourceMap, completedMachineMap);
            if (CollectionUtils.isEmpty(activeMachines)) {
                log.warn("换活字块无可继续尝试机台, 待排新增SKU: {}, 已完成机台: {}",
                        context.getNewSpecSkuList().size(), completedMachineMap.size());
                break;
            }
            activeMachines.sort((leftMachine, rightMachine) -> compareTypeBlockMachine(
                    leftMachine, rightMachine, machineTriggerSourceMap));

            boolean scheduledInCurrentRound = false;
            for (MachineScheduleDTO machine : activeMachines) {
                String machineCode = machine.getMachineCode();
                boolean strictPriorityOneAfterFirstHit = Boolean.TRUE.equals(strictPriorityOneMachineMap.get(machineCode));
                SkuScheduleDTO limitSpecifySku = selectLimitSpecifySkuByMachine(context, machine);
                if (shouldReserveMachineForSpecifyNewSpec(context, machine, limitSpecifySku, shifts)) {
                    completedMachineMap.put(machineCode, true);
                    log.info("收尾机台预留给定点物料新增换模链路, machineCode: {}, materialCode: {}",
                            machineCode, limitSpecifySku.getMaterialCode());
                    continue;
                }
                SkuScheduleDTO specifySku = isTypeBlockCandidate(context, machine, limitSpecifySku)
                        ? limitSpecifySku : null;
                if (specifySku != null && appendSpecifyTypeBlockResult(
                        context, machine, specifySku, shifts, completedMachineMap)) {
                    clearSpecifyReservation(context, machineCode, specifySku.getMaterialCode());
                    scheduledInCurrentRound = true;
                    typeBlockScheduledCount++;
                    break;
                }

                List<SkuScheduleDTO> priorityOneCandidates =
                        filterSameEmbryoDescAndMainPatternCandidates(context, machine);
                List<SkuScheduleDTO> priorityTwoCandidates =
                        !strictPriorityOneAfterFirstHit && CollectionUtils.isEmpty(priorityOneCandidates)
                                ? filterSameSpecCandidates(context, machine) : new ArrayList<SkuScheduleDTO>(0);
                SkuScheduleDTO typeBlockSku = !CollectionUtils.isEmpty(priorityOneCandidates)
                        ? selectPreferredSkuFromCandidates(priorityOneCandidates)
                        : selectPreferredSkuFromCandidates(priorityTwoCandidates);
                String matchedLayer = !CollectionUtils.isEmpty(priorityOneCandidates) ? "第一层"
                        : (!CollectionUtils.isEmpty(priorityTwoCandidates) ? "第二层" : "未命中");
                if (typeBlockSku == null) {
                    log.debug("换活字块未匹配到SKU, 机台: {}, 触发来源: {}, 第一层候选: {}, 第二层候选: {}",
                            machineCode, machineTriggerSourceMap.get(machineCode),
                            priorityOneCandidates.size(), priorityTwoCandidates.size());
                    traceTypeBlockDecision(context, machine, priorityOneCandidates, priorityTwoCandidates,
                            null, matchedLayer, false, null, null, machineTriggerSourceMap.get(machineCode));
                    completedMachineMap.put(machineCode, true);
                    continue;
                }
                getMaintenanceScheduleService().tryAttachMaintenanceAfterFirstEnding(
                        context, machine, machine.getEstimatedEndTime());
                Date typeBlockSwitchStartTime = calcTypeBlockSwitchStartTime(context, machine);
                Date typeBlockStartTime = resolveTypeBlockProductionStartTime(
                        context, machine, machine.getEstimatedEndTime(), typeBlockSwitchStartTime);
                boolean success = appendFollowUpResult(
                        context, machine, typeBlockSku, typeBlockStartTime, typeBlockSwitchStartTime, shifts);
                traceTypeBlockDecision(context, machine, priorityOneCandidates, priorityTwoCandidates,
                        typeBlockSku, matchedLayer, success, typeBlockSwitchStartTime, typeBlockStartTime,
                        machineTriggerSourceMap.get(machineCode));
                if (!success) {
                    log.warn("换活字块排产失败, 机台: {}, materialCode: {}, 结构: {}, 开始时间: {}, 匹配层级: {}",
                            machineCode, typeBlockSku.getMaterialCode(), typeBlockSku.getStructureName(),
                            LhScheduleTimeUtil.formatDateTime(typeBlockStartTime), matchedLayer);
                    completedMachineMap.put(machineCode, true);
                    continue;
                }
                scheduledInCurrentRound = true;
                typeBlockScheduledCount++;
                if (CollectionUtils.isEmpty(priorityOneCandidates)) {
                    completedMachineMap.put(machineCode, true);
                } else {
                    strictPriorityOneMachineMap.put(machineCode, true);
                }
                if (!machine.isEnding()) {
                    completedMachineMap.put(machineCode, true);
                }
                // 每轮仅落一条结果，随后按更新后的机台收尾时间重新排序。
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
     * 构建本轮可尝试的机台列表。
     *
     * @param candidateMachines 候选机台
     * @param machineTriggerSourceMap 机台触发来源
     * @param completedMachineMap 已完成机台
     * @return 可尝试机台
     */
    private List<MachineScheduleDTO> buildActiveMachineList(List<MachineScheduleDTO> candidateMachines,
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
    private int compareTypeBlockMachine(MachineScheduleDTO leftMachine,
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
        Date leftEndTime = leftMachine.getEstimatedEndTime();
        Date rightEndTime = rightMachine.getEstimatedEndTime();
        if (leftEndTime == null && rightEndTime == null) {
            return 0;
        }
        if (leftEndTime == null) {
            return 1;
        }
        if (rightEndTime == null) {
            return -1;
        }
        return leftEndTime.compareTo(rightEndTime);
    }

    /**
     * 追加定点物料换活字块结果。
     *
     * @param context 排程上下文
     * @param machine 机台
     * @param specifySku 定点物料
     * @param shifts 班次
     * @param completedMachineMap 已完成机台
     * @return true-追加成功
     */
    private boolean appendSpecifyTypeBlockResult(LhScheduleContext context,
                                                 MachineScheduleDTO machine,
                                                 SkuScheduleDTO specifySku,
                                                 List<LhShiftConfigVO> shifts,
                                                 Map<String, Boolean> completedMachineMap) {
        Date typeBlockSwitchStartTime = calcTypeBlockSwitchStartTime(context, machine);
        Date typeBlockStartTime = resolveTypeBlockProductionStartTime(
                context, machine, machine.getEstimatedEndTime(), typeBlockSwitchStartTime);
        boolean success = appendFollowUpResult(
                context, machine, specifySku, typeBlockStartTime, typeBlockSwitchStartTime, shifts);
        if (success) {
            completedMachineMap.put(machine.getMachineCode(), true);
            log.info("收尾机台命中定点物料衔接, machineCode: {}, materialCode: {}, startTime: {}",
                    machine.getMachineCode(), specifySku.getMaterialCode(),
                    LhScheduleTimeUtil.formatDateTime(typeBlockStartTime));
            return true;
        }
        log.debug("定点物料衔接失败，继续原衔接匹配, machineCode: {}, materialCode: {}",
                machine.getMachineCode(), specifySku.getMaterialCode());
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
        if (StringUtils.equals(TYPE_BLOCK_TRIGGER_FALLBACK, triggerSource)) {
            return 1;
        }
        return 2;
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
        if (specifySku == null || isTypeBlockCandidate(context, machine, specifySku)) {
            return false;
        }
        boolean schedulable = canScheduleSpecifySkuOnMachine(
                context, machine, specifySku, shifts, machine.getEstimatedEndTime());
        if (!schedulable) {
            return false;
        }
        log.debug("机台命中需走新增换模链路的定点物料预留, machineCode: {}, materialCode: {}, reason: {}",
                machine.getMachineCode(), specifySku.getMaterialCode(), TYPE_BLOCK_SKIP_REASON_LIMIT_SPECIFY_RESERVED);
        return true;
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
     * 过滤同胎胚描述且同主花纹的候选SKU。
     *
     * @param context 排程上下文
     * @param machine 机台
     * @return 候选SKU
     */
    private List<SkuScheduleDTO> filterSameEmbryoDescAndMainPatternCandidates(LhScheduleContext context,
                                                                              MachineScheduleDTO machine) {
        List<SkuScheduleDTO> candidateList = new ArrayList<>(context.getNewSpecSkuList().size());
        for (SkuScheduleDTO sku : context.getNewSpecSkuList()) {
            // 优先级1：同时命中胎胚描述和主花纹，才进入本层候选集。
            if (isMachineHardMatched(context, machine, sku)
                    && isSameEmbryoDesc(context, machine, sku)
                    && isSameMainPatternStrict(context, machine, sku)) {
                candidateList.add(sku);
            }
        }
        return candidateList;
    }

    /**
     * 过滤同规格的候选SKU。
     *
     * @param context 排程上下文
     * @param machine 机台
     * @return 候选SKU
     */
    private List<SkuScheduleDTO> filterSameSpecCandidates(LhScheduleContext context, MachineScheduleDTO machine) {
        List<SkuScheduleDTO> candidateList = new ArrayList<>(context.getNewSpecSkuList().size());
        for (SkuScheduleDTO sku : context.getNewSpecSkuList()) {
            if (isMachineHardMatched(context, machine, sku) && isSameSpec(context, machine, sku)) {
                candidateList.add(sku);
            }
        }
        return candidateList;
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
     * 判断SKU是否满足换活字块条件：同胎胚、同规格、不同花纹。
     *
     * @param context 排程上下文
     * @param machine 机台
     * @param sku SKU
     * @return true-满足条件
     */
    private boolean isTypeBlockCandidate(LhScheduleContext context,
                                         MachineScheduleDTO machine,
                                         SkuScheduleDTO sku) {
        if (sku == null) {
            return false;
        }
        if (!isMachineHardMatched(context, machine, sku)) {
            log.debug("换活字块候选SKU未通过机台硬性准入, machineCode: {}, materialCode: {}",
                    machine == null ? null : machine.getMachineCode(), sku.getMaterialCode());
            return false;
        }
        if (!isSameEmbryo(context, machine, sku)) {
            return false;
        }
        String machineSpecCode = resolveMachineSpecCode(context, machine);
        String machinePatternKey = resolveMachinePatternKey(context, machine);
        String skuPatternKey = resolvePatternKey(sku.getMainPattern(), sku.getPattern());
        if (StringUtils.isEmpty(machineSpecCode)
                || StringUtils.isEmpty(machinePatternKey)
                || StringUtils.isEmpty(sku.getSpecCode())
                || StringUtils.isEmpty(skuPatternKey)) {
            return false;
        }
        return StringUtils.equals(machineSpecCode, sku.getSpecCode())
                && !StringUtils.equals(machinePatternKey, skuPatternKey);
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
        String machineEmbryoCode = resolveMachineEmbryoCode(context, machine);
        return StringUtils.isNotEmpty(machineEmbryoCode)
                && StringUtils.isNotEmpty(sku.getEmbryoCode())
                && StringUtils.equals(machineEmbryoCode, sku.getEmbryoCode());
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
        String machineEmbryoDesc = resolveMachineEmbryoDesc(context, machine);
        String skuEmbryoDesc = resolveSkuEmbryoDesc(context, sku);
        return StringUtils.isNotEmpty(machineEmbryoDesc)
                && StringUtils.equals(machineEmbryoDesc, skuEmbryoDesc);
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
        if (getMaintenanceScheduleService().shouldApplyMaintenanceOverlapSwitchRule(context, machine, estimatedEndTime)) {
            return getMaintenanceScheduleService().resolveMaintenanceEndTime(context, machine);
        }
        Date switchStartTime = resolveAllowedSwitchStartTime(
                context, machine.getMachineCode(), estimatedEndTime);
        switchStartTime = getMaintenanceScheduleService().delaySwitchStartByMaintenance(
                machine, switchStartTime, LhScheduleTimeUtil.getTypeBlockChangeTotalHours(context));
        return switchStartTime;
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
        if (getMaintenanceScheduleService().shouldApplyMaintenanceOverlapSwitchRule(context, machine, estimatedEndTime)) {
            Date inspectionStartTime = LhScheduleTimeUtil.addHours(
                    switchStartTime, LhScheduleTimeUtil.getMaintenanceOverlapSwitchHours(context));
            return LhScheduleTimeUtil.addHours(
                    inspectionStartTime, LhScheduleTimeUtil.getFirstInspectionHours(context));
        }
        return LhScheduleTimeUtil.addHours(switchStartTime,
                LhScheduleTimeUtil.getTypeBlockChangeTotalHours(context));
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
                                         List<LhShiftConfigVO> shifts) {
        if (startTime == null) {
            return false;
        }
        Integer originalTargetScheduleQty = sku.getTargetScheduleQty();
        boolean isEnding = endingJudgmentStrategy.isEnding(context, sku);
        int machineMouldQty = ShiftCapacityResolverUtil.resolveMachineMouldQty(machine);
        sku.setMouldQty(machineMouldQty);
        LhScheduleResult result = buildScheduleResult(
                context, machine, sku, startTime, switchStartTime, shifts, machineMouldQty, isEnding);
        if (result == null || result.getDailyPlanQty() == null || result.getDailyPlanQty() <= 0) {
            sku.setTargetScheduleQty(originalTargetScheduleQty);
            return false;
        }
        result.setScheduleType(ScheduleTypeEnum.TYPE_BLOCK.getCode());
        result.setIsChangeMould(YES_FLAG);
        result.setIsTypeBlock(YES_FLAG);
        result.setMouldCode(resolveMouldCode(context, sku.getMaterialCode(), machine.getCurrentMaterialCode()));
        // 换活字块虽然不是新增规格换模，但下游换模计划仍按真实切换开始时间生成。
        result.setMouldChangeStartTime(switchStartTime);
        result.setIsEnd(isEnding ? YES_FLAG : NO_FLAG);

        // 换活字块结果即便非收尾，也必须补齐可计算完工时刻，避免结果校验失败。
        Date actualCompletionTime = resolveActualCompletionTime(context, result);
        if (actualCompletionTime == null) {
            return false;
        }
        result.setSpecEndTime(actualCompletionTime);
        result.setTdaySpecEndTime(actualCompletionTime);
        applyTypeBlockCleaningAnalysis(context, result, shifts);

        context.getScheduleResultList().add(result);
        registerMachineAssignment(context, machine.getMachineCode(), result);
        updateMachineState(context, machine, sku, result);
        context.getNewSpecSkuList().remove(sku);
        log.debug("换活字块排产完成, 机台: {}, SKU: {}", machine.getMachineCode(), sku.getMaterialCode());
        return true;
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
            Date typeBlockSwitchStartTime = calcTypeBlockSwitchStartTime(context, machine, endingTime);
            Date typeBlockStartTime = resolveTypeBlockProductionStartTime(
                    context, machine, endingTime, typeBlockSwitchStartTime);
            if (typeBlockStartTime == null || typeBlockSwitchStartTime == null) {
                return false;
            }
            int refinedTargetQty = getTargetScheduleQtyResolver().refineTargetQtyByMachineCapacity(
                    context,
                    specifySku,
                    machine,
                    typeBlockSwitchStartTime,
                    typeBlockStartTime,
                    shifts);
            if (refinedTargetQty <= 0) {
                log.debug("定点物料换活字块预判不可排, machineCode: {}, materialCode: {}, startTime: {}",
                        machine.getMachineCode(), specifySku.getMaterialCode(),
                        LhScheduleTimeUtil.formatDateTime(typeBlockStartTime));
                return false;
            }
            return true;
        }
        return canScheduleSpecifySkuByNewSpecPath(context, machine, specifySku, shifts, endingTime);
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
        Date mouldChangeStartTime = getMouldChangeBalanceStrategy().allocateMouldChange(
                context, machine.getMachineCode(), switchReadyTime);
        if (mouldChangeStartTime == null) {
            log.debug("定点物料新增换模预判不可排, machineCode: {}, materialCode: {}, 原因: 无可用换模窗口",
                    machine.getMachineCode(), specifySku.getMaterialCode());
            return false;
        }
        Date inspectionTime = null;
        try {
            int switchDurationHours = maintenanceOverlapSwitch
                    ? LhScheduleTimeUtil.getMaintenanceOverlapSwitchHours(context)
                    : LhScheduleTimeUtil.getMouldChangeTotalHours(context);
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
            Date firstProductionStartTime = ShiftProductionControlUtil.resolveFirstSchedulableStartIgnoringCleaning(
                    context,
                    machine.getMachineCode(),
                    productionStartTime,
                    shifts,
                    specifySku.getShiftCapacity(),
                    specifySku.getLhTimeSeconds(),
                    machineMouldQty);
            if (firstProductionStartTime == null) {
                log.debug("定点物料新增换模预判不可排, machineCode: {}, materialCode: {}, 原因: 窗口内无可开产时间",
                        machine.getMachineCode(), specifySku.getMaterialCode());
                return false;
            }
            int refinedTargetQty = getTargetScheduleQtyResolver().refineTargetQtyByMachineCapacity(
                    context, specifySku, machine, mouldChangeStartTime, firstProductionStartTime, shifts);
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
     * 输出衔接机台排序总览日志。
     *
     * @param context 排程上下文
     * @param endingMachines 衔接机台列表
     */
    private void traceEndingMachineOrder(LhScheduleContext context, List<MachineScheduleDTO> endingMachines) {
        if (!PriorityTraceLogHelper.isEnabled(context)) {
            return;
        }
        String title = "衔接机台排序总览";
        StringBuilder detailBuilder = new StringBuilder(512);
        PriorityTraceLogHelper.appendLine(detailBuilder,
                "候选机台数=" + PriorityTraceLogHelper.sizeOf(endingMachines));
        int index = 1;
        for (MachineScheduleDTO machine : endingMachines) {
            Date estimatedEndTime = machine.getEstimatedEndTime();
            PriorityTraceLogHelper.appendLine(detailBuilder,
                    index++
                            + ". 机台=" + PriorityTraceLogHelper.safeText(machine.getMachineCode())
                            + ", 当前物料=" + PriorityTraceLogHelper.safeText(machine.getCurrentMaterialCode())
                            + ", 基准时间=" + PriorityTraceLogHelper.formatDateTime(estimatedEndTime)
                            + ", 实际切换起点=" + PriorityTraceLogHelper.formatDateTime(
                            resolveAllowedSwitchStartTime(context, machine.getMachineCode(), estimatedEndTime)));
        }
        String detail = detailBuilder.toString().trim();
        log.info("{}\n{}", title, detail);
        PriorityTraceLogHelper.appendProcessLog(context, title, detail);
    }

    /**
     * 输出单机台换活字块决策日志。
     *
     * @param context 排程上下文
     * @param machine 收尾机台
     * @param priorityOneCandidates 第一层候选
     * @param priorityTwoCandidates 第二层候选
     * @param selectedSku 选中SKU
     * @param matchedLayer 命中层级
     * @param success 是否成功
     * @param switchStartTime 换活字块开始时间
     * @param startTime 开产时间
     * @param triggerSource 触发来源
     */
    private void traceTypeBlockDecision(LhScheduleContext context, MachineScheduleDTO machine,
                                        List<SkuScheduleDTO> priorityOneCandidates,
                                        List<SkuScheduleDTO> priorityTwoCandidates,
                                        SkuScheduleDTO selectedSku,
                                        String matchedLayer,
                                        boolean success,
                                        Date switchStartTime,
                                        Date startTime,
                                        String triggerSource) {
        if (!PriorityTraceLogHelper.isEnabled(context)) {
            return;
        }
        String title = "收尾机台衔接决策";
        StringBuilder detailBuilder = new StringBuilder(512);
        PriorityTraceLogHelper.appendLine(detailBuilder,
                "机台=" + PriorityTraceLogHelper.safeText(machine.getMachineCode())
                        + ", 当前物料=" + PriorityTraceLogHelper.safeText(machine.getCurrentMaterialCode())
                        + ", 真实收尾时间=" + PriorityTraceLogHelper.formatDateTime(machine.getEstimatedEndTime()));
        PriorityTraceLogHelper.appendLine(detailBuilder,
                "第一层候选(同胎胚描述+同主花纹)=" + buildSkuCodeSummary(priorityOneCandidates));
        PriorityTraceLogHelper.appendLine(detailBuilder,
                "第二层候选(同规格)=" + buildSkuCodeSummary(priorityTwoCandidates));
        PriorityTraceLogHelper.appendLine(detailBuilder,
                "命中层级=" + PriorityTraceLogHelper.safeText(matchedLayer)
                        + ", 选中SKU=" + PriorityTraceLogHelper.safeText(
                        selectedSku == null ? null : selectedSku.getMaterialCode())
                        + ", 是否换活字块=" + PriorityTraceLogHelper.yesNo(selectedSku != null)
                        + ", 触发来源=" + PriorityTraceLogHelper.safeText(triggerSource));
        PriorityTraceLogHelper.appendLine(detailBuilder,
                "衔接结果=" + (success ? "成功" : "未衔接")
                        + ", 换活字块开始时间=" + PriorityTraceLogHelper.formatDateTime(
                        switchStartTime)
                        + ", 开产时间=" + PriorityTraceLogHelper.formatDateTime(startTime));
        String detail = detailBuilder.toString().trim();
        log.info("{}\n{}", title, detail);
        PriorityTraceLogHelper.appendProcessLog(context, title, detail);
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
        result.setSingleMouldShiftQty(SingleMouldShiftQtyUtil.resolveSingleMouldShiftQty(context, sku, mouldQty));
        result.setDailyPlanQty(0);
        result.setTotalDailyPlanQty(sku.getMonthPlanQty());
        result.setMouldSurplusQty(sku.getSurplusQty());
        result.setIsEnd(isEnding ? YES_FLAG : NO_FLAG);
        result.setIsDelivery(sku.isDeliveryLocked() ? YES_FLAG : NO_FLAG);
        result.setIsRelease(NO_FLAG);
        result.setDataSource(AUTO_DATA_SOURCE);
        result.setIsDelete(0);
        result.setScheduleType(ScheduleTypeEnum.TYPE_BLOCK.getCode());
        result.setIsTypeBlock(YES_FLAG);
        result.setConstructionStage(sku.getConstructionStage());
        result.setEmbryoNo(sku.getEmbryoNo());
        result.setTextNo(sku.getTextNo());
        result.setLhNo(sku.getLhNo());
        result.setMonthPlanVersion(sku.getMonthPlanVersion());
        result.setProductionVersion(sku.getProductionVersion());
        result.setIsTrial(sku.isTrial() ? YES_FLAG : NO_FLAG);
        result.setMachineOrder(machine.getMachineOrder());
        result.setHasSpecialMaterial(LhSpecialMaterialUtil.resolveHasSpecialMaterial(context, sku));

        // 生成工单号。
        result.setOrderNo(generateOrderNo(context));

        int refinedTargetQty = getTargetScheduleQtyResolver().refineTargetQtyByMachineCapacity(
                context, sku, machine, switchStartTime, startTime, shifts);
        List<MachineCleaningWindowDTO> cleaningWindowList = new ArrayList<>(MachineCleaningOverlapUtil.excludeOverlapWindows(
                machine.getCleaningWindowList(), switchStartTime, startTime));
        List<MachineMaintenanceWindowDTO> maintenanceWindowList = resolveMachineMaintenanceWindowList(
                context, machine.getMachineCode());

        // 按班次分配计划量。
        distributeToShifts(context, result, shifts, startTime,
                sku.getShiftCapacity(), sku.getLhTimeSeconds(), mouldQty, refinedTargetQty, cleaningWindowList,
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
                    dryIceDurationHours);
            shiftMaxQty = ShiftProductionControlUtil.deductCapacityByControl(control, shiftMaxQty, mouldQty);
            if (shiftMaxQty <= 0) {
                continue;
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
     * 命中“模具清洗+换活字块”组合场景时，写入班次原因分析。
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
     * 解析模具编码。
     *
     * @param context 排程上下文
     * @param materialCodes 物料编码
     * @return 模具编码
     */
    private String resolveMouldCode(LhScheduleContext context, String... materialCodes) {
        if (context == null || materialCodes == null) {
            return null;
        }
        for (String materialCode : materialCodes) {
            if (StringUtils.isEmpty(materialCode) || !context.getSkuMouldRelMap().containsKey(materialCode)) {
                continue;
            }
            String mouldCode = context.getSkuMouldRelMap().get(materialCode).stream()
                    .map(MdmSkuMouldRel::getMouldCode)
                    .filter(StringUtils::isNotEmpty)
                    .distinct()
                    .collect(Collectors.joining(","));
            if (StringUtils.isNotEmpty(mouldCode)) {
                return mouldCode;
            }
        }
        return null;
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
}
