/**
 * Copyright (c) 2008, 智立通（厦门）科技有限公司 All rights reserved。
 */
package com.zlt.aps.lh.engine.strategy.impl;

import com.zlt.aps.lh.api.constant.LhScheduleConstant;
import com.zlt.aps.lh.api.constant.LhScheduleParamConstant;
import com.zlt.aps.lh.api.domain.dto.SpecialMaterialMatchResult;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.api.domain.dto.MachineScheduleDTO;
import com.zlt.aps.lh.api.domain.dto.SkuScheduleDTO;
import com.zlt.aps.lh.api.domain.entity.LhScheduleResult;
import com.zlt.aps.lh.api.domain.vo.LhShiftConfigVO;
import com.zlt.aps.lh.api.enums.ConstructionStageEnum;
import com.zlt.aps.lh.api.enums.LhSpecialMaterialCategoryEnum;
import com.zlt.aps.lh.engine.strategy.IMachineMatchStrategy;
import com.zlt.aps.lh.util.LhMachineHardMatchUtil;
import com.zlt.aps.lh.util.ShiftProductionControlUtil;
import com.zlt.aps.lh.util.LhSingleControlMachineUtil;
import com.zlt.aps.lh.util.LhSpecialMaterialUtil;
import com.zlt.aps.lh.util.LhSpecifyMachineUtil;
import com.zlt.aps.lh.util.MachineStatusUtil;
import com.zlt.aps.lh.util.LhScheduleTimeUtil;
import com.zlt.aps.lh.util.PriorityTraceLogHelper;
import com.zlt.aps.mdm.api.domain.entity.MdmDevicePlanShut;
import com.zlt.aps.mdm.api.domain.entity.MdmMaterialInfo;
import com.zlt.aps.mdm.api.domain.entity.MdmModelInfo;
import com.zlt.aps.mdm.api.domain.entity.MdmSkuMouldRel;
import com.zlt.aps.lh.api.domain.dto.ShiftProductionControlDTO;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 默认机台匹配策略实现。
 *
 * <p>业务定位：</p>
 * <ul>
 *   <li>为新增排产、局部搜索和目标量评估提供候选硫化机台；</li>
 *   <li>先执行硬性过滤：定点不可作业、机台状态、寸口/模套/特殊材料能力、模具占用和停机窗口；</li>
 *   <li>再执行单控/普通机台类型约束，区分试制、量试、小批量和正规 SKU；</li>
 *   <li>最后按定点机台、特殊机台后置、单控优先级、收尾时间、规格/英寸/胶囊/胎胚亲和度和窗口画像排序。</li>
 * </ul>
 *
 * <p>注意：该策略不直接分配班次排量，只返回候选和排序；真正的换模、首检和产能落地在新增策略中执行。</p>
 *
 * @author APS
 */
@Slf4j
@Component
public class DefaultMachineMatchStrategy implements IMachineMatchStrategy {

    /** 每小时毫秒数 */
    private static final long MILLIS_PER_HOUR = 60L * 60L * 1000L;
    /** 试制SKU单控机台优先得分 */
    private static final int SINGLE_CONTROL_TRIAL_SCORE = 0;
    /** 量试SKU单控机台优先得分 */
    private static final int SINGLE_CONTROL_MASS_TRIAL_SCORE = 1;
    /** 小批量SKU单控机台优先得分 */
    private static final int SINGLE_CONTROL_SMALL_BATCH_SCORE = 2;
    /** 普通机台默认得分 */
    private static final int SINGLE_CONTROL_NORMAL_MACHINE_SCORE = 3;
    /** 正规SKU单控机台靠后得分 */
    private static final int SINGLE_CONTROL_FORMAL_SCORE = 4;

    @Override
    public List<MachineScheduleDTO> matchMachines(LhScheduleContext context, SkuScheduleDTO sku) {
        log.debug("匹配可用硫化机台, SKU: {}", sku.getMaterialCode());

        // 1. 从硫化定点机台获取限制作业优先机台和不可作业机台。
        Set<String> limitSpecifyMachineCodes = LhSpecifyMachineUtil.resolveLimitSpecifyMachineCodes(
                context, sku.getMaterialCode());
        Set<String> notAllowedMachineCodes = LhSpecifyMachineUtil.resolveNotAllowedMachineCodes(
                context, sku.getMaterialCode());

        // 2. 获取SKU的模具号列表
        List<String> skuMouldCodes = getSkuMouldCodes(context, sku.getMaterialCode());

        // 3. 获取已被其他计划占用的模具集合
        Set<String> occupiedMouldCodes = getOccupiedMouldCodes(context);

        // 4. 过滤候选机台：状态启用 + 硬性指标匹配 + 模具未被占用。
        // 这里只保留业务上可承接的机台，不在这里提前决定最终排产量。
        BigDecimal skuInch = parseInch(sku.getProSize());
        SpecialMaterialMatchResult specialMaterialMatchResult =
                LhSpecialMaterialUtil.resolveMatchResult(context, sku);
        log.debug("SKU特殊物料判定, materialCode: {}, special: {}, matchSource: {}, category: {}",
                sku.getMaterialCode(), specialMaterialMatchResult.isSpecial(),
                specialMaterialMatchResult.getMatchSource(), specialMaterialMatchResult.getCategoryDisplayText());
        List<MachineScheduleDTO> candidates = new ArrayList<>();
        List<MachineScheduleDTO> stopTimeoutCandidates = new ArrayList<>();
        MachineFilterTrace trace = new MachineFilterTrace(context.getMachineScheduleMap().size());

        for (MachineScheduleDTO machine : context.getMachineScheduleMap().values()) {
            if (isNotAllowedMachine(notAllowedMachineCodes, machine)) {
                trace.notAllowedMachineFilteredCount++;
                trace.recordFilteredMachine(machine, "定点机台不可作业");
                continue;
            }
            MachineAvailabilityReason availabilityReason = resolveMachineAvailabilityReason(
                    context, sku, skuMouldCodes, occupiedMouldCodes, skuInch,
                    specialMaterialMatchResult, machine);
            if (MachineAvailabilityReason.AVAILABLE != availabilityReason) {
                trace.recordAvailabilityReason(machine, availabilityReason);
                continue;
            }
            // 长时间停机只在存在其他可用机台时才触发换机，避免唯一候选机台被提前误排除。
            if (hasPlanStopExceededTimeout(context, machine)) {
                stopTimeoutCandidates.add(machine);
                continue;
            }
            candidates.add(machine);
        }
        if (CollectionUtils.isEmpty(candidates) && !CollectionUtils.isEmpty(stopTimeoutCandidates)) {
            candidates.addAll(stopTimeoutCandidates);
        } else {
            for (MachineScheduleDTO machine : stopTimeoutCandidates) {
                trace.recordAvailabilityReason(machine, MachineAvailabilityReason.STOP_TIMEOUT);
            }
        }

        // 单控/普通机台约束是类型规则：试制强约束单控，量试/小批量优先单控，正规优先普通。
        // 该规则只处理候选集合，不在此消费机台；最终是否占用仍由 S4.5 换模、首检和产能结果决定。
        candidates = applySingleControlReservationRule(context, sku, candidates, trace);

        // 5. 按多维度排序：排序只改变候选顺序，不改变候选集合中的业务可排性。
        sortCandidates(context, candidates, sku, specialMaterialMatchResult);
        traceMachineCandidates(context, sku, specialMaterialMatchResult, candidates, trace);

        if (CollectionUtils.isEmpty(candidates)) {
            log.warn("SKU候选机台为空, materialCode: {}, SKU类型: {}, 规格: {}, 寸口: {}, 特殊分类: {}, 机台总数: {}, 不可作业过滤: {}, 禁用过滤: {}, 超时停机过滤: {}, 寸口过滤: {}, 模套过滤: {}, 特殊支持过滤: {}, 模具过滤: {}, 单控规则过滤: {}, 限制作业优先机台: {}",
                    sku.getMaterialCode(), resolveSkuTypeDesc(sku), sku.getSpecCode(), sku.getProSize(),
                    specialMaterialMatchResult.getCategoryDisplayText(), trace.totalMachineCount,
                    trace.notAllowedMachineFilteredCount,
                    trace.disabledCount, trace.stopTimeoutCount, trace.inchMismatchCount,
                    trace.mouldSetMismatchCount, trace.resolveSpecialSupportFilteredCount(),
                    trace.mouldConflictCount, trace.singleControlRuleFilteredCount, limitSpecifyMachineCodes);
        }
        log.info("SKU可用机台匹配完成, materialCode: {}, special: {}, category: {}, 候选机台数: {}",
                sku.getMaterialCode(), specialMaterialMatchResult.isSpecial(),
                specialMaterialMatchResult.getCategoryDisplayText(), candidates.size());
        return candidates;
    }

    /**
     * 对单控拆分机台执行SKU类型约束。
     * <p>试制只保留单控候选；量试/小批量优先单控、无单控时回落普通；
     * 正规优先普通，且仅在待排小批量SKU已全部排完后，才允许保留单控候选作为回落机台。</p>
     *
     * <p>业务边界：这里不做新增排序重排，不让后续试制/量试反向抢占当前 SKU 的全局顺序；
     * 只在当前 SKU 已轮到选机时，按类型决定单控和普通候选是否保留。</p>
     *
     * @param context 排程上下文
     * @param sku 待排SKU
     * @param candidates 原候选机台
     * @param trace 过滤跟踪
     * @return 处理后的候选机台
     */
    private List<MachineScheduleDTO> applySingleControlReservationRule(LhScheduleContext context,
                                                                       SkuScheduleDTO sku,
                                                                       List<MachineScheduleDTO> candidates,
                                                                       MachineFilterTrace trace) {
        if (CollectionUtils.isEmpty(candidates) || sku == null) {
            return candidates;
        }
        List<MachineScheduleDTO> singleControlCandidates = new ArrayList<>(2);
        List<MachineScheduleDTO> normalCandidates = new ArrayList<>(candidates.size());
        for (MachineScheduleDTO candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            if (isSingleControlMachine(context, candidate.getMachineCode())) {
                // 单控基准机台已拆成 L/R 运行态机台，候选侧别和班产在后续产能阶段继续按运行态口径处理。
                singleControlCandidates.add(candidate);
                continue;
            }
            normalCandidates.add(candidate);
        }
        List<MachineScheduleDTO> filteredCandidates = resolveCandidatesBySkuType(
                context, sku, singleControlCandidates, normalCandidates);
        markTypeRuleBlocked(context, sku, candidates, filteredCandidates, trace);
        recordSingleControlRuleTrace(trace, candidates, filteredCandidates, context, sku);
        logSingleControlCandidateDecision(context, sku, candidates, filteredCandidates, trace);
        if (filteredCandidates.size() != candidates.size()) {
            log.info("SKU选机台单控约束过滤, materialCode: {}, SKU类型: {}, 初始候选: {}, 过滤后候选: {}, "
                            + "待排试制SKU: {}, 待排量试SKU: {}, 待排小批量SKU: {}, 待排正规SKU: {}",
                    sku.getMaterialCode(), resolveSkuTypeDesc(sku),
                    candidates.size(), filteredCandidates.size(),
                    context.getPendingTrialNewSpecSkuCount(),
                    context.getPendingMassTrialNewSpecSkuCount(),
                    context.getPendingSmallBatchNewSpecSkuCount(),
                    context.getPendingFormalNewSpecSkuCount());
        }
        return filteredCandidates;
    }

    /**
     * 记录本次无候选是否由SKU类型机台约束触发。
     *
     * @param context 排程上下文
     * @param sku SKU
     * @param originalCandidates 原始候选
     * @param filteredCandidates 过滤后候选
     * @param trace 过滤跟踪
     */
    private void markTypeRuleBlocked(LhScheduleContext context,
                                     SkuScheduleDTO sku,
                                     List<MachineScheduleDTO> originalCandidates,
                                     List<MachineScheduleDTO> filteredCandidates,
                                     MachineFilterTrace trace) {
        if (context == null || sku == null) {
            return;
        }
        boolean blocked = !CollectionUtils.isEmpty(originalCandidates)
                && CollectionUtils.isEmpty(filteredCandidates);
        context.getNewSpecTypeRuleBlockedMap().put(sku, blocked);
    }

    /**
     * 根据SKU类型过滤候选机台。
     *
     * @param context 排程上下文
     * @param sku 待排SKU
     * @param singleControlCandidates 单控候选
     * @param normalCandidates 普通候选
     * @return 过滤后的候选
     */
    private List<MachineScheduleDTO> resolveCandidatesBySkuType(LhScheduleContext context,
                                                                SkuScheduleDTO sku,
                                                                List<MachineScheduleDTO> singleControlCandidates,
                                                                List<MachineScheduleDTO> normalCandidates) {
        if (isTrialConstructionStage(sku)) {
            // 试制SKU只能使用单控机台，无单控候选时不回落普通机台。
            return singleControlCandidates;
        }
        if (isMassTrialSku(sku) || isSmallBatchSku(sku)) {
            // 量试/小批量优先单控，但允许普通机台兜住可排性，具体顺序由后续排序控制。
            // 这里保留普通机台是为了单控不足时仍可完成业务需求，不代表普通机台优先。
            List<MachineScheduleDTO> retainedCandidates = new ArrayList<>(
                    singleControlCandidates.size() + normalCandidates.size());
            retainedCandidates.addAll(singleControlCandidates);
            retainedCandidates.addAll(normalCandidates);
            return retainedCandidates;
        }
        if (context != null && context.getPendingSmallBatchNewSpecSkuCount() > 0) {
            // 待排小批量SKU未完成前，正规SKU不得占用单控机台。
            return normalCandidates;
        }
        if (!CollectionUtils.isEmpty(normalCandidates)) {
            // 小批量已全部排完后，正规SKU优先普通机台，但仍可保留单控候选作为回落机台。
            // 单控放在普通机台之后，避免正规 SKU 抢占后续特殊 SKU 可能需要的单控资源。
            List<MachineScheduleDTO> retainedCandidates = new ArrayList<>(
                    singleControlCandidates.size() + normalCandidates.size());
            retainedCandidates.addAll(normalCandidates);
            retainedCandidates.addAll(singleControlCandidates);
            return retainedCandidates;
        }
        // 正规SKU仅剩单控候选时，允许直接使用单控机台。
        return singleControlCandidates;
    }

    /**
     * 记录单控/普通机台类型约束过滤明细。
     *
     * @param trace 过滤跟踪
     * @param originalCandidates 原候选
     * @param filteredCandidates 过滤后候选
     * @param sku 待排SKU
     */
    private void recordSingleControlRuleTrace(MachineFilterTrace trace,
                                              List<MachineScheduleDTO> originalCandidates,
                                              List<MachineScheduleDTO> filteredCandidates,
                                              LhScheduleContext context,
                                              SkuScheduleDTO sku) {
        if (trace == null || CollectionUtils.isEmpty(originalCandidates)) {
            return;
        }
        Set<String> retainedMachineCodes = filteredCandidates.stream()
                .filter(Objects::nonNull)
                .map(MachineScheduleDTO::getMachineCode)
                .collect(Collectors.toSet());
        for (MachineScheduleDTO candidate : originalCandidates) {
            if (candidate == null || retainedMachineCodes.contains(candidate.getMachineCode())) {
                continue;
            }
            trace.singleControlRuleFilteredCount++;
            trace.recordFilteredMachine(candidate, resolveSingleControlFilteredReason(context, sku, candidate));
        }
    }

    /**
     * 解析单控约束过滤原因。
     *
     * @param sku SKU
     * @param machine 机台
     * @return 过滤原因
     */
    private String resolveSingleControlFilteredReason(LhScheduleContext context,
                                                      SkuScheduleDTO sku,
                                                      MachineScheduleDTO machine) {
        boolean singleControlMachine = machine != null
                && LhSingleControlMachineUtil.isSingleMouldMachine(machine.getMachineCode());
        if (isTrialConstructionStage(sku) && !singleControlMachine) {
            return "试制SKU禁止使用普通机台";
        }
        if (isMassTrialSku(sku) && !singleControlMachine) {
            return "量试SKU优先使用单控机台，单控候选不足时允许普通机台";
        }
        if (isSmallBatchSku(sku) && !singleControlMachine) {
            return "小批量SKU优先使用单控机台，单控候选不足时允许普通机台";
        }
        if (isFormalSku(sku) && singleControlMachine) {
            if (context != null && context.getPendingSmallBatchNewSpecSkuCount() > 0) {
                return "待排小批量SKU未完成，正规SKU禁止抢占单控机台";
            }
            return "正规SKU优先使用普通机台";
        }
        return "SKU类型机台约束";
    }

    /**
     * 输出单控机台候选诊断日志，便于排查小批量/正规SKU单控回落链路。
     *
     * @param context 排程上下文
     * @param sku SKU
     * @param originalCandidates 原候选
     * @param filteredCandidates 过滤后候选
     * @param trace 过滤跟踪
     */
    private void logSingleControlCandidateDecision(LhScheduleContext context,
                                                   SkuScheduleDTO sku,
                                                   List<MachineScheduleDTO> originalCandidates,
                                                   List<MachineScheduleDTO> filteredCandidates,
                                                   MachineFilterTrace trace) {
        if (sku == null || CollectionUtils.isEmpty(originalCandidates)) {
            return;
        }
        boolean needLog = sku.isSmallBatchValidation()
                || isMassTrialSku(sku)
                || isTrialConstructionStage(sku)
                || containsTrackedSingleControlMachine(context, originalCandidates)
                || containsTrackedSingleControlMachine(context, filteredCandidates);
        if (!needLog) {
            return;
        }
        log.info("SKU单控候选诊断, materialCode: {}, skuType: {}, surplusQty: {}, smallBatchThreshold: {}, isSmallBatch: {}, "
                        + "待排小批量SKU数: {}, 原候选: {}, 过滤后候选: {}, K1501L入候选: {}, K1501R入候选: {}, K1501L保留: {}, K1501R保留: {}, 过滤明细: {}",
                sku.getMaterialCode(), resolveSkuTypeDesc(sku), sku.getSurplusQty(),
                resolveSmallBatchThreshold(context), sku.isSmallBatchValidation(),
                context == null ? 0 : context.getPendingSmallBatchNewSpecSkuCount(),
                joinMachineCodes(originalCandidates), joinMachineCodes(filteredCandidates),
                containsMachineCode(originalCandidates, "K1501L"),
                containsMachineCode(originalCandidates, "K1501R"),
                containsMachineCode(filteredCandidates, "K1501L"),
                containsMachineCode(filteredCandidates, "K1501R"),
                CollectionUtils.isEmpty(trace.filteredMachineMessages)
                        ? "-" : String.join("; ", trace.filteredMachineMessages));
    }

    private boolean containsTrackedSingleControlMachine(LhScheduleContext context,
                                                        List<MachineScheduleDTO> candidates) {
        return containsMachineCode(candidates, "K1501L")
                || containsMachineCode(candidates, "K1501R")
                || containsSingleControlMachine(context, candidates);
    }

    private boolean containsSingleControlMachine(LhScheduleContext context,
                                                 List<MachineScheduleDTO> candidates) {
        if (CollectionUtils.isEmpty(candidates)) {
            return false;
        }
        for (MachineScheduleDTO candidate : candidates) {
            if (candidate != null && isSingleControlMachine(context, candidate.getMachineCode())) {
                return true;
            }
        }
        return false;
    }

    private boolean containsMachineCode(List<MachineScheduleDTO> candidates, String machineCode) {
        if (CollectionUtils.isEmpty(candidates) || StringUtils.isEmpty(machineCode)) {
            return false;
        }
        for (MachineScheduleDTO candidate : candidates) {
            if (candidate != null && StringUtils.equalsIgnoreCase(machineCode, candidate.getMachineCode())) {
                return true;
            }
        }
        return false;
    }

    private String joinMachineCodes(List<MachineScheduleDTO> candidates) {
        if (CollectionUtils.isEmpty(candidates)) {
            return "-";
        }
        return candidates.stream()
                .filter(Objects::nonNull)
                .map(MachineScheduleDTO::getMachineCode)
                .filter(StringUtils::isNotEmpty)
                .collect(Collectors.joining(","));
    }

    private int resolveSmallBatchThreshold(LhScheduleContext context) {
        if (context != null && Objects.nonNull(context.getScheduleConfig())) {
            return context.getScheduleConfig().getSmallBatchSkuThreshold();
        }
        if (context == null) {
            return LhScheduleConstant.SMALL_BATCH_SKU_THRESHOLD;
        }
        return context.getParamIntValue(LhScheduleParamConstant.SMALL_BATCH_SKU_THRESHOLD,
                LhScheduleConstant.SMALL_BATCH_SKU_THRESHOLD);
    }

    /**
     * 判断当前 SKU 是否需要保留单控机台候选。
     *
     * @param sku 待排SKU
     * @return true-试制保留
     */
    private boolean shouldReserveSingleControlForTrialSku(SkuScheduleDTO sku) {
        if (sku == null) {
            return false;
        }
        if (sku.isSmallBatchValidation()) {
            return true;
        }
        return StringUtils.equals(ConstructionStageEnum.TRIAL.getCode(), sku.getConstructionStage())
                || StringUtils.equals(ConstructionStageEnum.MASS_TRIAL.getCode(), sku.getConstructionStage());
    }

    /**
     * 判断是否为试制施工阶段。
     *
     * @param sku 待排SKU
     * @return true-试制阶段
     */
    private boolean isTrialConstructionStage(SkuScheduleDTO sku) {
        return sku != null && StringUtils.equals(ConstructionStageEnum.TRIAL.getCode(), sku.getConstructionStage());
    }

    /**
     * 判断是否为量试或小批量SKU。
     * <p>isTrial 仅作为试制/量试总标识兼容，不作为试制强约束。</p>
     *
     * @param sku 待排SKU
     * @return true-量试或小批量
     */
    private boolean isMassTrialOrSmallBatchSku(SkuScheduleDTO sku) {
        return isMassTrialSku(sku) || isSmallBatchSku(sku);
    }

    /**
     * 判断是否为量试SKU。
     * <p>isTrial 仅作为试制/量试总标识兼容，不作为试制强约束。</p>
     *
     * @param sku 待排SKU
     * @return true-量试
     */
    private boolean isMassTrialSku(SkuScheduleDTO sku) {
        if (sku == null) {
            return false;
        }
        return StringUtils.equals(ConstructionStageEnum.MASS_TRIAL.getCode(), sku.getConstructionStage());
    }

    /**
     * 判断是否为小批量SKU。
     *
     * @param sku 待排SKU
     * @return true-小批量
     */
    private boolean isSmallBatchSku(SkuScheduleDTO sku) {
        return sku != null && sku.isSmallBatchValidation();
    }

    /**
     * 判断是否为正规SKU。
     *
     * @param sku 待排SKU
     * @return true-正规SKU
     */
    private boolean isFormalSku(SkuScheduleDTO sku) {
        return sku != null && !isTrialConstructionStage(sku) && !isMassTrialOrSmallBatchSku(sku);
    }

    /**
     * 判断是否为当前工厂配置出的单控拆分运行态机台。
     *
     * @param context 排程上下文
     * @param machineCode 机台编码
     * @return true-单控机台
     */
    private boolean isSingleControlMachine(LhScheduleContext context, String machineCode) {
        return LhSingleControlMachineUtil.isConfiguredSingleControlMachine(context, machineCode);
    }

    /**
     * 解析SKU类型描述。
     *
     * @param sku 待排SKU
     * @return 类型描述
     */
    private String resolveSkuTypeDesc(SkuScheduleDTO sku) {
        if (isTrialConstructionStage(sku)) {
            return "试制";
        }
        if (sku != null && sku.isSmallBatchValidation()) {
            return "小批量";
        }
        if (sku != null && StringUtils.equals(ConstructionStageEnum.MASS_TRIAL.getCode(), sku.getConstructionStage())) {
            return "量试";
        }
        return "正规";
    }

    @Override
    public MachineScheduleDTO selectBestMachine(LhScheduleContext context,
                                                SkuScheduleDTO sku,
                                                List<MachineScheduleDTO> candidates,
                                                Set<String> excludedMachineCodes) {
        if (CollectionUtils.isEmpty(candidates)) {
            return null;
        }
        for (MachineScheduleDTO candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            String machineCode = candidate.getMachineCode();
            if (CollectionUtils.isEmpty(excludedMachineCodes) || StringUtils.isEmpty(machineCode)
                    || !excludedMachineCodes.contains(machineCode)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * 获取SKU对应的模具号列表
     */
    private List<String> getSkuMouldCodes(LhScheduleContext context, String materialCode) {
        List<MdmSkuMouldRel> mouldRels = context.getSkuMouldRelMap().get(materialCode);
        if (mouldRels == null || mouldRels.isEmpty()) {
            return new ArrayList<>();
        }
        return mouldRels.stream()
                .map(MdmSkuMouldRel::getMouldCode)
                .collect(Collectors.toList());
    }

    /**
     * 获取当前所有已分配排程中正在使用的模具号集合（共用模保护）
     */
    private Set<String> getOccupiedMouldCodes(LhScheduleContext context) {
        Set<String> occupied = new HashSet<>();
        for (Map.Entry<String, List<LhScheduleResult>> entry : context.getMachineAssignmentMap().entrySet()) {
            for (LhScheduleResult result : entry.getValue()) {
                if (shouldIgnoreReleasedContinuousPlaceholder(context, result)) {
                    continue;
                }
                if (StringUtils.isNotEmpty(result.getMouldCode())) {
                    String[] mouldCodeArray = StringUtils.split(result.getMouldCode(), ",");
                    if (mouldCodeArray == null) {
                        continue;
                    }
                    for (String mouldCode : mouldCodeArray) {
                        String normalizedMouldCode = StringUtils.trim(mouldCode);
                        if (StringUtils.isNotEmpty(normalizedMouldCode)) {
                            occupied.add(normalizedMouldCode);
                        }
                    }
                }
            }
        }
        return occupied;
    }

    /**
     * 判断机台是否为当前SKU的不可作业机台。
     *
     * @param notAllowedMachineCodes 不可作业机台编码集合
     * @param machine 候选机台
     * @return true-不可作业，false-允许继续校验
     */
    private boolean isNotAllowedMachine(Set<String> notAllowedMachineCodes, MachineScheduleDTO machine) {
        return !CollectionUtils.isEmpty(notAllowedMachineCodes)
                && machine != null
                && notAllowedMachineCodes.contains(machine.getMachineCode());
    }

    /**
     * 判断机台是否满足当前排程可用条件。
     *
     * @param context 排程上下文
     * @param sku 待排SKU
     * @param skuMouldCodes SKU模具列表
     * @param occupiedMouldCodes 已占用模具
     * @param skuInch SKU英寸
     * @param machine 候选机台
     * @return true-可用，false-不可用
     */
    private MachineAvailabilityReason resolveMachineAvailabilityReason(LhScheduleContext context, SkuScheduleDTO sku,
                                                                      List<String> skuMouldCodes,
                                                                      Set<String> occupiedMouldCodes,
                                                                      BigDecimal skuInch,
                                                                      SpecialMaterialMatchResult matchResult,
                                                                      MachineScheduleDTO machine) {
        if (!MachineStatusUtil.isEnabled(machine.getStatus())) {
            return MachineAvailabilityReason.DISABLED;
        }
        if (!LhMachineHardMatchUtil.isInchInRange(
                skuInch, machine.getDimensionMinimum(), machine.getDimensionMaximum())) {
            return MachineAvailabilityReason.INCH_MISMATCH;
        }
        if (!LhMachineHardMatchUtil.isMouldSetMatched(context, sku, machine)) {
            return MachineAvailabilityReason.MOULD_SET_MISMATCH;
        }
        MachineAvailabilityReason specialSupportReason =
                resolveSpecialSupportAvailabilityReason(matchResult, machine);
        if (MachineAvailabilityReason.AVAILABLE != specialSupportReason) {
            return specialSupportReason;
        }
        return isMouldCompatible(sku, skuMouldCodes, machine, occupiedMouldCodes)
                ? MachineAvailabilityReason.AVAILABLE
                : MachineAvailabilityReason.MOULD_CONFLICT;
    }

    /**
     * 解析特殊物料支持能力校验结果。
     *
     * @param matchResult 特殊物料命中结果
     * @param machine 候选机台
     * @return 机台可用原因
     */
    private MachineAvailabilityReason resolveSpecialSupportAvailabilityReason(
            SpecialMaterialMatchResult matchResult, MachineScheduleDTO machine) {
        if (Objects.isNull(matchResult) || !matchResult.isSpecial()) {
            return MachineAvailabilityReason.AVAILABLE;
        }
        for (String category : matchResult.getCategories()) {
            LhSpecialMaterialCategoryEnum categoryEnum =
                    LhSpecialMaterialCategoryEnum.getByCode(category);
            if (LhSpecialMaterialCategoryEnum.WIDE_BASE_195 == categoryEnum
                    && !LhMachineHardMatchUtil.isCategorySupported(categoryEnum, machine)) {
                return MachineAvailabilityReason.SPECIAL_195_UNSUPPORTED;
            }
            if (LhSpecialMaterialCategoryEnum.WIDE_BASE_225 == categoryEnum
                    && !LhMachineHardMatchUtil.isCategorySupported(categoryEnum, machine)) {
                return MachineAvailabilityReason.SPECIAL_225_UNSUPPORTED;
            }
            if (LhSpecialMaterialCategoryEnum.CHIP_TIRE == categoryEnum
                    && !LhMachineHardMatchUtil.isCategorySupported(categoryEnum, machine)) {
                return MachineAvailabilityReason.SPECIAL_CHIP_UNSUPPORTED;
            }
            if (Objects.isNull(categoryEnum)) {
                return MachineAvailabilityReason.SPECIAL_CATEGORY_UNSUPPORTED;
            }
        }
        return MachineAvailabilityReason.AVAILABLE;
    }

    /**
     * 判断机台计划停机是否超过自动换机阈值。
     *
     * @param context 排程上下文
     * @param machine 候选机台
     * @return true-超过阈值，false-未超过阈值
     */
    private boolean hasPlanStopExceededTimeout(LhScheduleContext context, MachineScheduleDTO machine) {
        int timeoutHours = context.getParamIntValue(LhScheduleParamConstant.MACHINE_STOP_TIMEOUT_HOURS,
                LhScheduleConstant.MACHINE_STOP_TIMEOUT_HOURS);
        Date candidateReferenceTime = resolveAlignedCandidateReferenceTime(context, machine);
        Date candidateWindowEndTime = resolveCandidateWindowEndTime(context, candidateReferenceTime);
        for (MdmDevicePlanShut planShut : context.getDevicePlanShutList()) {
            if (planShut == null || StringUtils.isEmpty(planShut.getMachineCode())
                    || !StringUtils.equals(machine.getMachineCode(), planShut.getMachineCode())) {
                continue;
            }
            if (planShut.getBeginDate() == null || planShut.getEndDate() == null
                    || !planShut.getEndDate().after(planShut.getBeginDate())) {
                continue;
            }
            long stopMillis = planShut.getEndDate().getTime() - planShut.getBeginDate().getTime();
            if (stopMillis > (long) timeoutHours * MILLIS_PER_HOUR
                    && isPlanStopOverlapCandidateWindow(planShut, candidateReferenceTime, candidateWindowEndTime)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 解析候选机台的待排起点。
     *
     * @param context 排程上下文
     * @param machine 候选机台
     * @return 待排起点
     */
    private Date resolveCandidateReferenceTime(LhScheduleContext context, MachineScheduleDTO machine) {
        Date releasedMachineReferenceTime = resolveReleasedContinuousMachineReferenceTime(context, machine);
        if (releasedMachineReferenceTime != null) {
            return releasedMachineReferenceTime;
        }
        Date occupiedEndTime = resolveMachineOccupiedEndTime(context, machine);
        if (occupiedEndTime != null) {
            return occupiedEndTime;
        }
        if (context.getScheduleDate() != null) {
            return context.getScheduleDate();
        }
        return context.getScheduleTargetDate();
    }

    /**
     * 解析机台已占用结束时间。
     *
     * @param context 排程上下文
     * @param machine 候选机台
     * @return 已占用结束时间
     */
    private Date resolveMachineOccupiedEndTime(LhScheduleContext context, MachineScheduleDTO machine) {
        Date machineEndTime = Objects.nonNull(machine) ? machine.getEstimatedEndTime() : null;
        Date assignedEndTime = Objects.nonNull(machine)
                ? resolveLatestAssignedEndTime(context, machine.getMachineCode()) : null;
        if (Objects.isNull(machineEndTime)) {
            return assignedEndTime;
        }
        if (Objects.isNull(assignedEndTime)) {
            return machineEndTime;
        }
        return machineEndTime.after(assignedEndTime) ? machineEndTime : assignedEndTime;
    }

    /**
     * 获取同一机台已登记有效结果的最新结束时间。
     *
     * @param context 排程上下文
     * @param machineCode 机台编码
     * @return 最新有效结果结束时间
     */
    private Date resolveLatestAssignedEndTime(LhScheduleContext context, String machineCode) {
        if (Objects.isNull(context) || StringUtils.isEmpty(machineCode)) {
            return null;
        }
        List<LhScheduleResult> assignedResults = context.getMachineAssignmentMap().get(machineCode);
        if (CollectionUtils.isEmpty(assignedResults)) {
            return null;
        }
        return assignedResults.stream()
                .filter(result -> Objects.nonNull(result)
                        && Objects.nonNull(result.getDailyPlanQty())
                        && result.getDailyPlanQty() > 0
                        && Objects.nonNull(result.getSpecEndTime()))
                .map(LhScheduleResult::getSpecEndTime)
                .max(Date::compareTo)
                .orElse(null);
    }

    /**
     * 新增选机画像只允许从当前排程窗口首班开始评估换模，不提前借用窗口外空档。
     *
     * @param context 排程上下文
     * @param referenceTime 机台参考结束时间
     * @return 与排程窗口首班对齐后的参考时间
     */
    private Date alignCandidateReferenceTimeToWindowStart(LhScheduleContext context, Date referenceTime) {
        if (referenceTime == null || CollectionUtils.isEmpty(context.getScheduleWindowShifts())) {
            return referenceTime;
        }
        Date windowStartTime = context.getScheduleWindowShifts().get(0).getShiftStartDateTime();
        if (windowStartTime != null && referenceTime.before(windowStartTime)) {
            return windowStartTime;
        }
        return referenceTime;
    }

    /**
     * 解析新增选机场景的对齐后参考时间。
     *
     * @param context 排程上下文
     * @param machine 候选机台
     * @return 与排程窗口首班对齐后的参考时间
     */
    private Date resolveAlignedCandidateReferenceTime(LhScheduleContext context, MachineScheduleDTO machine) {
        return alignCandidateReferenceTimeToWindowStart(context, resolveCandidateReferenceTime(context, machine));
    }

    /**
     * 解析候选机台可排窗口结束时间。
     *
     * @param context 排程上下文
     * @param referenceTime 待排起点
     * @return 可排窗口结束时间
     */
    private Date resolveCandidateWindowEndTime(LhScheduleContext context, Date referenceTime) {
        Date windowEndTime = null;
        for (LhShiftConfigVO shift : context.getScheduleWindowShifts()) {
            if (shift == null || shift.getShiftEndDateTime() == null) {
                continue;
            }
            if (windowEndTime == null || shift.getShiftEndDateTime().after(windowEndTime)) {
                windowEndTime = shift.getShiftEndDateTime();
            }
        }
        if (windowEndTime != null && (referenceTime == null || windowEndTime.after(referenceTime))) {
            return windowEndTime;
        }
        return referenceTime;
    }

    /**
     * 判断长停机是否与机台待排窗口重叠。
     *
     * @param planShut 停机计划
     * @param referenceTime 待排起点
     * @param windowEndTime 待排窗口结束
     * @return true-重叠；false-不重叠
     */
    private boolean isPlanStopOverlapCandidateWindow(MdmDevicePlanShut planShut, Date referenceTime, Date windowEndTime) {
        if (referenceTime == null) {
            return false;
        }
        if (windowEndTime == null || !windowEndTime.after(referenceTime)) {
            return !referenceTime.before(planShut.getBeginDate()) && referenceTime.before(planShut.getEndDate());
        }
        return planShut.getBeginDate().before(windowEndTime) && planShut.getEndDate().after(referenceTime);
    }

    /**
     * 检查模具是否与机台兼容（仅校验模具未被占用）。
     *
     * @param sku 待排SKU
     * @param skuMouldCodes SKU模具列表
     * @param machine 候选机台
     * @param occupiedMouldCodes 已占用模具集合
     * @return true-兼容，false-不兼容
     */
    private boolean isMouldCompatible(SkuScheduleDTO sku, List<String> skuMouldCodes, MachineScheduleDTO machine, Set<String> occupiedMouldCodes) {
        if (skuMouldCodes.isEmpty()) {
            return true;
        }
        // 当前 mouldQty 的业务语义是"选机后的机台模台数"，此处不再拿 SKU 预置模数拦截候选机台。
        for (String mouldCode : skuMouldCodes) {
            if (occupiedMouldCodes.contains(mouldCode)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 判断英寸值是否在机台寸口范围内
     *
     * @param skuInch SKU英寸
     * @param minInch 机台最小寸口
     * @param maxInch 机台最大寸口
     * @return true-命中范围，false-未命中
     */
    /**
     * 从规格寸口字符串中提取英寸数值
     * <p>如 "225/65R17" 提取17.0，"17.5" 直接解析为17.5</p>
     */
    private BigDecimal parseInch(String proSize) {
        return LhMachineHardMatchUtil.parseInch(proSize);
    }

    /**
     * 对候选机台进行多维度排序。
     *
     * @param context 排程上下文
     * @param candidates 候选机台
     * @param sku 待排SKU
     */
    private void sortCandidates(LhScheduleContext context,
                                List<MachineScheduleDTO> candidates,
                                SkuScheduleDTO sku,
                                SpecialMaterialMatchResult matchResult) {
        candidates.sort(buildMachineComparator(context, sku, matchResult));
    }

    /**
     * 构建机台优先级比较器。
     *
     * @param context 排程上下文
     * @param sku 待排SKU
     * @return 比较器
     */
    private Comparator<MachineScheduleDTO> buildMachineComparator(LhScheduleContext context,
                                                                  SkuScheduleDTO sku,
                                                                  SpecialMaterialMatchResult matchResult) {
        Map<String, CandidateWindowProfile> profileCache = new HashMap<>(16);
        return (left, right) -> {
            int compareResult = compareLimitSpecifyPriority(context, sku, left, right);
            if (compareResult != 0) {
                return compareResult;
            }

            compareResult = compareSingleControlPriority(context, sku, left, right);
            if (compareResult != 0) {
                return compareResult;
            }

            CandidateWindowProfile leftProfile = resolveCandidateWindowProfile(context, sku, left, profileCache);
            CandidateWindowProfile rightProfile = resolveCandidateWindowProfile(context, sku, right, profileCache);

            compareResult = compareReleasedContinuousMachinePriority(leftProfile, rightProfile);
            if (compareResult != 0) {
                return compareResult;
            }

            compareResult = compareOtherSkuOccupancy(leftProfile, rightProfile);
            if (compareResult != 0) {
                return compareResult;
            }

            compareResult = compareEarliestProductionShift(leftProfile, rightProfile);
            if (compareResult != 0) {
                return compareResult;
            }

            compareResult = compareContinuousSchedulableShifts(leftProfile, rightProfile);
            if (compareResult != 0) {
                return compareResult;
            }

            compareResult = compareTotalSchedulableShifts(leftProfile, rightProfile);
            if (compareResult != 0) {
                return compareResult;
            }

            compareResult = compareTailFragmentPriority(leftProfile, rightProfile);
            if (compareResult != 0) {
                return compareResult;
            }

            compareResult = compareNormalMachinePriority(matchResult, left, right);
            if (compareResult != 0) {
                return compareResult;
            }

            compareResult = compareSpecialSupportCapabilityCount(matchResult, left, right);
            if (compareResult != 0) {
                return compareResult;
            }

            compareResult = compareEndingTime(context, leftProfile, rightProfile);
            if (compareResult != 0) {
                return compareResult;
            }

            compareResult = compareSpecExactMatch(sku, left, right);
            if (compareResult != 0) {
                return compareResult;
            }

            compareResult = compareProSizeExactMatch(sku, left, right);
            if (compareResult != 0) {
                return compareResult;
            }

            compareResult = compareInchDistance(sku, left, right);
            if (compareResult != 0) {
                return compareResult;
            }

            compareResult = compareCapsuleAffinity(context, sku, left, right);
            if (compareResult != 0) {
                return compareResult;
            }

            compareResult = compareEmbryoShareCount(context, left, right);
            if (compareResult != 0) {
                return compareResult;
            }

            compareResult = Integer.compare(left.getMachineOrder(), right.getMachineOrder());
            if (compareResult != 0) {
                return compareResult;
            }
            return Comparator.nullsLast(String::compareTo).compare(left.getMachineCode(), right.getMachineCode());
        };
    }

    /**
     * 比较非特殊SKU普通机台优先级。
     *
     * @param matchResult 特殊物料命中结果
     * @param left 左机台
     * @param right 右机台
     * @return 比较结果
     */
    private int compareNormalMachinePriority(SpecialMaterialMatchResult matchResult,
                                             MachineScheduleDTO left,
                                             MachineScheduleDTO right) {
        if (Objects.nonNull(matchResult) && matchResult.isSpecial()) {
            return 0;
        }
        return Integer.compare(LhMachineHardMatchUtil.resolveNormalMachinePriority(left),
                LhMachineHardMatchUtil.resolveNormalMachinePriority(right));
    }

    /**
     * 比较特殊支持能力数量，能力越少越优先。
     *
     * @param matchResult 特殊物料命中结果
     * @param left 左机台
     * @param right 右机台
     * @return 比较结果
     */
    private int compareSpecialSupportCapabilityCount(SpecialMaterialMatchResult matchResult,
                                                     MachineScheduleDTO left,
                                                     MachineScheduleDTO right) {
        if (Objects.nonNull(matchResult) && matchResult.isSpecial()) {
            return 0;
        }
        return Integer.compare(resolveSpecialSupportCapabilityCount(left),
                resolveSpecialSupportCapabilityCount(right));
    }

    /**
     * 比较限制作业定点机台优先级。
     *
     * @param context 排程上下文
     * @param sku 待排SKU
     * @param left 左机台
     * @param right 右机台
     * @return 比较结果
     */
    private int compareLimitSpecifyPriority(LhScheduleContext context, SkuScheduleDTO sku,
                                            MachineScheduleDTO left, MachineScheduleDTO right) {
        return Integer.compare(resolveLimitSpecifyScore(context, sku, left),
                resolveLimitSpecifyScore(context, sku, right));
    }

    /**
     * 解析限制作业定点机台得分。
     *
     * @param context 排程上下文
     * @param sku 待排SKU
     * @param machine 候选机台
     * @return 0-定点机台，1-普通机台
     */
    private int resolveLimitSpecifyScore(LhScheduleContext context, SkuScheduleDTO sku, MachineScheduleDTO machine) {
        if (sku == null || machine == null) {
            return 1;
        }
        return LhSpecifyMachineUtil.isLimitSpecifyMachine(context, machine.getMachineCode(), sku.getMaterialCode())
                ? 0 : 1;
    }

    /**
     * 比较单控拆分机台优先级。
     *
     * @param context 排程上下文
     * @param sku 待排SKU
     * @param left 左机台
     * @param right 右机台
     * @return 比较结果
     */
    private int compareSingleControlPriority(LhScheduleContext context, SkuScheduleDTO sku,
                                             MachineScheduleDTO left, MachineScheduleDTO right) {
        return Integer.compare(resolveSingleControlScore(context, sku, left),
                resolveSingleControlScore(context, sku, right));
    }

    /**
     * 解析单控拆分机台得分。
     *
     * @param context 排程上下文
     * @param sku 待排SKU
     * @param machine 候选机台
     * @return 当前SKU对单控/普通机台的偏好分，分值越小越优先
     */
    private int resolveSingleControlScore(LhScheduleContext context, SkuScheduleDTO sku, MachineScheduleDTO machine) {
        if (Objects.isNull(machine)
                || !isSingleControlMachine(context, machine.getMachineCode())) {
            return SINGLE_CONTROL_NORMAL_MACHINE_SCORE;
        }
        if (isTrialConstructionStage(sku)) {
            return SINGLE_CONTROL_TRIAL_SCORE;
        }
        if (isMassTrialSku(sku)) {
            return SINGLE_CONTROL_MASS_TRIAL_SCORE;
        }
        if (isSmallBatchSku(sku)) {
            return SINGLE_CONTROL_SMALL_BATCH_SCORE;
        }
        return SINGLE_CONTROL_FORMAL_SCORE;
    }

    /**
     * 比较收尾时间优先级。
     *
     * @param context 排程上下文
     * @param left 左机台
     * @param right 右机台
     * @return 比较结果
     */
    private int compareEndingTime(LhScheduleContext context,
                                  CandidateWindowProfile leftProfile,
                                  CandidateWindowProfile rightProfile) {
        Date leftEndTime = leftProfile == null ? null : leftProfile.getReferenceTime();
        Date rightEndTime = rightProfile == null ? null : rightProfile.getReferenceTime();
        if (leftEndTime == null && rightEndTime == null) {
            return 0;
        }
        if (leftEndTime == null) {
            return 1;
        }
        if (rightEndTime == null) {
            return -1;
        }

        int toleranceMinutes = LhScheduleTimeUtil.getEndingToleranceMinutes(context);
        if (LhScheduleTimeUtil.withinTolerance(leftEndTime, rightEndTime, toleranceMinutes)) {
            return 0;
        }
        return leftEndTime.compareTo(rightEndTime);
    }

    /**
     * 比较规格完全一致优先级。
     *
     * @param sku 待排SKU
     * @param left 左机台
     * @param right 右机台
     * @return 比较结果
     */
    private int compareSpecExactMatch(SkuScheduleDTO sku, MachineScheduleDTO left, MachineScheduleDTO right) {
        return Integer.compare(resolveSpecMatchScore(sku, left), resolveSpecMatchScore(sku, right));
    }

    /**
     * 比较英寸完全一致优先级。
     *
     * @param sku 待排SKU
     * @param left 左机台
     * @param right 右机台
     * @return 比较结果
     */
    private int compareProSizeExactMatch(SkuScheduleDTO sku, MachineScheduleDTO left, MachineScheduleDTO right) {
        return Integer.compare(resolveProSizeMatchScore(sku, left), resolveProSizeMatchScore(sku, right));
    }

    /**
     * 比较英寸接近度优先级。
     *
     * @param sku 待排SKU
     * @param left 左机台
     * @param right 右机台
     * @return 比较结果
     */
    private int compareInchDistance(SkuScheduleDTO sku, MachineScheduleDTO left, MachineScheduleDTO right) {
        return Double.compare(resolveInchDistance(sku, left), resolveInchDistance(sku, right));
    }

    /**
     * 比较胶囊共用性优先级。
     *
     * @param context 排程上下文
     * @param sku 待排SKU
     * @param left 左机台
     * @param right 右机台
     * @return 比较结果
     */
    private int compareCapsuleAffinity(LhScheduleContext context, SkuScheduleDTO sku,
                                       MachineScheduleDTO left, MachineScheduleDTO right) {
        return Integer.compare(resolveCapsuleAffinityScore(context, sku, left),
                resolveCapsuleAffinityScore(context, sku, right));
    }

    /**
     * 比较胎胚共用数量优先级。
     *
     * @param context 排程上下文
     * @param left 左机台
     * @param right 右机台
     * @return 比较结果
     */
    private int compareEmbryoShareCount(LhScheduleContext context, MachineScheduleDTO left, MachineScheduleDTO right) {
        return Integer.compare(resolveEmbryoShareCount(context, right), resolveEmbryoShareCount(context, left));
    }

    /**
     * 优先选择非续作释放机台；释放机台仍保留候选，只在新增选机排序中靠后。
     */
    private int compareReleasedContinuousMachinePriority(CandidateWindowProfile leftProfile,
                                                         CandidateWindowProfile rightProfile) {
        return Integer.compare(leftProfile.getReleasedContinuousMachineScore(),
                rightProfile.getReleasedContinuousMachineScore());
    }

    /**
     * 优先选择未被其他SKU占用的机台。
     */
    private int compareOtherSkuOccupancy(CandidateWindowProfile leftProfile, CandidateWindowProfile rightProfile) {
        return Integer.compare(leftProfile.getOtherSkuOccupiedScore(), rightProfile.getOtherSkuOccupiedScore());
    }

    /**
     * 优先选择更早进入可开产班次的机台。
     */
    private int compareEarliestProductionShift(CandidateWindowProfile leftProfile, CandidateWindowProfile rightProfile) {
        return Integer.compare(leftProfile.getFirstProductionShiftIndex(), rightProfile.getFirstProductionShiftIndex());
    }

    /**
     * 优先选择连续可生产班次数更多的机台。
     */
    private int compareContinuousSchedulableShifts(CandidateWindowProfile leftProfile,
                                                   CandidateWindowProfile rightProfile) {
        return Integer.compare(rightProfile.getContinuousSchedulableShiftCount(),
                leftProfile.getContinuousSchedulableShiftCount());
    }

    /**
     * 优先选择窗口内总可生产班次数更多的机台。
     */
    private int compareTotalSchedulableShifts(CandidateWindowProfile leftProfile,
                                              CandidateWindowProfile rightProfile) {
        return Integer.compare(rightProfile.getTotalSchedulableShiftCount(),
                leftProfile.getTotalSchedulableShiftCount());
    }

    /**
     * 优先选择不是尾部零散产能的机台。
     */
    private int compareTailFragmentPriority(CandidateWindowProfile leftProfile,
                                            CandidateWindowProfile rightProfile) {
        return Integer.compare(leftProfile.getTailFragmentScore(), rightProfile.getTailFragmentScore());
    }

    /**
     * 解析规格完全一致得分。
     *
     * @param sku 待排SKU
     * @param machine 机台
     * @return 0-一致，1-不一致
     */
    private int resolveSpecMatchScore(SkuScheduleDTO sku, MachineScheduleDTO machine) {
        String skuSpec = normalizeToken(sku.getSpecCode());
        String machineSpec = normalizeToken(machine.getPreviousSpecCode());
        return StringUtils.isNotEmpty(skuSpec) && StringUtils.equals(skuSpec, machineSpec) ? 0 : 1;
    }

    /**
     * 解析英寸完全一致得分。
     *
     * @param sku 待排SKU
     * @param machine 机台
     * @return 0-一致，1-不一致
     */
    private int resolveProSizeMatchScore(SkuScheduleDTO sku, MachineScheduleDTO machine) {
        return isSameInch(parseInch(sku.getProSize()), parseInch(machine.getPreviousProSize())) ? 0 : 1;
    }

    /**
     * 解析英寸接近度。
     *
     * @param sku 待排SKU
     * @param machine 机台
     * @return 差值，越小越优先
     */
    private double resolveInchDistance(SkuScheduleDTO sku, MachineScheduleDTO machine) {
        return calcInchDistance(parseInch(sku.getProSize()), parseInch(machine.getPreviousProSize()));
    }

    /**
     * 解析胶囊共用性得分。
     *
     * @param context 排程上下文
     * @param sku 待排SKU
     * @param machine 机台
     * @return 0-共用性好，1-无优势
     */
    private int resolveCapsuleAffinityScore(LhScheduleContext context, SkuScheduleDTO sku, MachineScheduleDTO machine) {
        return hasCapsuleAffinity(context, sku, machine) ? 0 : 1;
    }

    /**
     * 判断机台与SKU是否存在胶囊共用性。
     *
     * @param context 排程上下文
     * @param sku 待排SKU
     * @param machine 候选机台
     * @return true-共用性好，false-无优势
     */
    private boolean hasCapsuleAffinity(LhScheduleContext context, SkuScheduleDTO sku, MachineScheduleDTO machine) {
        String skuSpec = normalizeToken(sku.getSpecCode());
        String machineSpec = normalizeToken(machine.getPreviousSpecCode());
        if (StringUtils.isNotEmpty(skuSpec) && StringUtils.isNotEmpty(machineSpec)
                && isSameCapsuleGroup(context.getCapsuleSpecPeerMap(), skuSpec, machineSpec)) {
            return true;
        }

        String skuProSize = normalizeToken(sku.getProSize());
        String machineProSize = normalizeToken(machine.getPreviousProSize());
        return StringUtils.isNotEmpty(skuProSize)
                && StringUtils.isNotEmpty(machineProSize)
                && isSameCapsuleGroup(context.getCapsuleProSizePeerMap(), skuProSize, machineProSize);
    }

    /**
     * 判断两个值是否属于同一胶囊分组。
     *
     * @param capsuleGroupMap 胶囊分组Map
     * @param leftValue 左值
     * @param rightValue 右值
     * @return true-同组，false-不同组
     */
    private boolean isSameCapsuleGroup(Map<String, String> capsuleGroupMap, String leftValue, String rightValue) {
        if (CollectionUtils.isEmpty(capsuleGroupMap)) {
            return false;
        }
        String leftGroup = capsuleGroupMap.get(leftValue);
        String rightGroup = capsuleGroupMap.get(rightValue);
        return StringUtils.isNotEmpty(leftGroup) && StringUtils.equals(leftGroup, rightGroup);
    }

    /**
     * 解析胎胚共用数量。
     *
     * @param context 排程上下文
     * @param machine 候选机台
     * @return 共用数量
     */
    private int resolveEmbryoShareCount(LhScheduleContext context, MachineScheduleDTO machine) {
        if (StringUtils.isEmpty(machine.getPreviousMaterialCode())) {
            return 0;
        }
        MdmMaterialInfo materialInfo = context.getMaterialInfoMap().get(machine.getPreviousMaterialCode());
        if (materialInfo == null || StringUtils.isEmpty(materialInfo.getEmbryoDesc())) {
            return 0;
        }
        String embryoDesc = normalizeToken(materialInfo.getEmbryoDesc());
        if (StringUtils.isEmpty(embryoDesc)) {
            return 0;
        }
        return context.getEmbryoDescMaterialCountMap().getOrDefault(embryoDesc, 0);
    }

    /**
     * 解析候选机台的真实生产窗口画像，用于多机台扩机排序与日志。
     */
    private CandidateWindowProfile resolveCandidateWindowProfile(LhScheduleContext context,
                                                                 SkuScheduleDTO sku,
                                                                 MachineScheduleDTO machine,
                                                                 Map<String, CandidateWindowProfile> profileCache) {
        if (machine == null || StringUtils.isEmpty(machine.getMachineCode())) {
            return CandidateWindowProfile.empty();
        }
        CandidateWindowProfile cachedProfile = profileCache.get(machine.getMachineCode());
        if (cachedProfile != null) {
            return cachedProfile;
        }
        CandidateWindowProfile profile = new CandidateWindowProfile();
        Date referenceTime = resolveAlignedCandidateReferenceTime(context, machine);
        profile.setReferenceTime(referenceTime);
        boolean hitNoMouldChange = referenceTime != null
                && LhScheduleTimeUtil.isNoMouldChangeTime(context, referenceTime);
        profile.setHitNoMouldChange(hitNoMouldChange);
        Date switchStartTime = resolveCandidateSwitchStartTime(context, referenceTime);
        profile.setSwitchStartTime(switchStartTime);
        profile.setProductionStartTime(resolveCandidateProductionStartTime(context, switchStartTime));
        profile.setReleasedContinuousMachineScore(resolveReleasedContinuousMachineScore(context, machine));
        profile.setOtherSkuOccupiedScore(resolveOtherSkuOccupiedScore(context, sku, machine));
        fillSchedulableShiftMetrics(context, sku, profile);
        profileCache.put(machine.getMachineCode(), profile);
        return profile;
    }

    /**
     * 解析续作释放机台降级得分。
     *
     * @param context 排程上下文
     * @param machine 候选机台
     * @return 0-普通候选，1-续作释放候选
     */
    private int resolveReleasedContinuousMachineScore(LhScheduleContext context, MachineScheduleDTO machine) {
        if (context == null || machine == null || StringUtils.isEmpty(machine.getMachineCode())
                || CollectionUtils.isEmpty(context.getReleasedContinuousMachineCodeSet())) {
            return 0;
        }
        return context.getReleasedContinuousMachineCodeSet().contains(machine.getMachineCode()) ? 1 : 0;
    }

    /**
     * 解析候选机台可发起换模的最早时间。
     */
    private Date resolveCandidateSwitchStartTime(LhScheduleContext context, Date referenceTime) {
        if (referenceTime == null) {
            return null;
        }
        if (LhScheduleTimeUtil.isNoMouldChangeTime(context, referenceTime)) {
            return LhScheduleTimeUtil.resolveNextMorningAfterNoMouldChangeWindow(context, referenceTime);
        }
        return referenceTime;
    }

    /**
     * 解析候选机台的最早开产时间。
     */
    private Date resolveCandidateProductionStartTime(LhScheduleContext context, Date switchStartTime) {
        if (switchStartTime == null) {
            return null;
        }
        return LhScheduleTimeUtil.addHours(switchStartTime, LhScheduleTimeUtil.getMouldChangeTotalHours(context));
    }

    /**
     * 计算候选机台的可生产班次画像。
     */
    private void fillSchedulableShiftMetrics(LhScheduleContext context,
                                             SkuScheduleDTO sku,
                                             CandidateWindowProfile profile) {
        if (context == null || profile == null || profile.getProductionStartTime() == null
                || CollectionUtils.isEmpty(context.getScheduleWindowShifts())) {
            profile.setFirstProductionShiftIndex(LhScheduleConstant.MAX_SHIFT_SLOT_COUNT + 1);
            return;
        }
        int firstShiftIndex = LhScheduleConstant.MAX_SHIFT_SLOT_COUNT + 1;
        int continuousShiftCount = 0;
        int totalShiftCount = 0;
        Integer previousShiftIndex = null;
        boolean continuousBroken = false;
        for (LhShiftConfigVO shift : context.getScheduleWindowShifts()) {
            if (shift == null || shift.getShiftIndex() == null
                    || shift.getShiftEndDateTime() == null
                    || !profile.getProductionStartTime().before(shift.getShiftEndDateTime())) {
                continue;
            }
            ShiftProductionControlDTO control = ShiftProductionControlUtil.resolveEffectiveControl(
                    context, shift, profile.getProductionStartTime());
            if (control == null || !control.isCanSchedule()) {
                if (previousShiftIndex != null) {
                    continuousBroken = true;
                }
                continue;
            }
            totalShiftCount++;
            if (firstShiftIndex > LhScheduleConstant.MAX_SHIFT_SLOT_COUNT) {
                firstShiftIndex = shift.getShiftIndex();
                continuousShiftCount = 1;
                previousShiftIndex = shift.getShiftIndex();
                continue;
            }
            if (!continuousBroken && previousShiftIndex != null
                    && shift.getShiftIndex() == previousShiftIndex + 1) {
                continuousShiftCount++;
                previousShiftIndex = shift.getShiftIndex();
                continue;
            }
            continuousBroken = true;
            previousShiftIndex = shift.getShiftIndex();
        }
        profile.setFirstProductionShiftIndex(firstShiftIndex);
        profile.setContinuousSchedulableShiftCount(continuousShiftCount);
        profile.setTotalSchedulableShiftCount(totalShiftCount);
        int shiftCapacity = sku != null && sku.getShiftCapacity() > 0 ? sku.getShiftCapacity() : 1;
        profile.setAvailableCapacityQty(totalShiftCount * shiftCapacity);
        profile.setTailFragmentScore(isTailFragmentProfile(profile) ? 1 : 0);
    }

    /**
     * 判断候选机台是否只剩尾部零散产能。
     */
    private boolean isTailFragmentProfile(CandidateWindowProfile profile) {
        if (profile == null || profile.getTotalSchedulableShiftCount() <= 0) {
            return true;
        }
        return profile.getTotalSchedulableShiftCount() <= 2
                || profile.getFirstProductionShiftIndex() >= LhScheduleConstant.MAX_SHIFT_SLOT_COUNT - 2;
    }

    /**
     * 解析机台是否已经被其他SKU占用。
     */
    private int resolveOtherSkuOccupiedScore(LhScheduleContext context,
                                             SkuScheduleDTO sku,
                                             MachineScheduleDTO machine) {
        if (context == null || machine == null || CollectionUtils.isEmpty(context.getMachineAssignmentMap())) {
            return 0;
        }
        List<LhScheduleResult> assignedResults = context.getMachineAssignmentMap().get(machine.getMachineCode());
        if (CollectionUtils.isEmpty(assignedResults)) {
            return 0;
        }
        for (LhScheduleResult assignedResult : assignedResults) {
            if (shouldIgnoreReleasedContinuousPlaceholder(context, assignedResult)) {
                continue;
            }
            if (assignedResult == null) {
                return 1;
            }
            if (sku == null || StringUtils.isEmpty(sku.getMaterialCode())) {
                return 1;
            }
            if (!StringUtils.equals(sku.getMaterialCode(), assignedResult.getMaterialCode())) {
                return 1;
            }
        }
        return 0;
    }

    /**
     * 解析续作释放机台在新增选机画像中的参考时间。
     * <p>首日无计划但后续仍有计划的续作结果会保留在结果集中做账，
     * 新增选机时必须回退到机台初始就绪时刻，避免被这条占位结果误判成已被续作占满。</p>
     *
     * @param context 排程上下文
     * @param machine 候选机台
     * @return 释放机台的初始参考时间；非此场景返回 null
     */
    private Date resolveReleasedContinuousMachineReferenceTime(LhScheduleContext context, MachineScheduleDTO machine) {
        if (context == null || machine == null || StringUtils.isEmpty(machine.getMachineCode())) {
            return null;
        }
        List<LhScheduleResult> assignedResults = context.getMachineAssignmentMap().get(machine.getMachineCode());
        if (CollectionUtils.isEmpty(assignedResults)) {
            return null;
        }
        for (LhScheduleResult assignedResult : assignedResults) {
            if (!shouldIgnoreReleasedContinuousPlaceholder(context, assignedResult)) {
                continue;
            }
            MachineScheduleDTO initialMachine = context.getInitialMachineScheduleMap().get(machine.getMachineCode());
            if (initialMachine != null && initialMachine.getEstimatedEndTime() != null) {
                return initialMachine.getEstimatedEndTime();
            }
            return context.getScheduleDate() != null ? context.getScheduleDate() : context.getScheduleTargetDate();
        }
        return null;
    }

    /**
     * 判断已分配结果是否属于“首日无计划、后续有计划”的释放续作占位结果。
     *
     * @param context 排程上下文
     * @param result 已分配结果
     * @return true-应在新增选机阶段忽略
     */
    private boolean shouldIgnoreReleasedContinuousPlaceholder(LhScheduleContext context, LhScheduleResult result) {
        if (context == null || result == null || StringUtils.isEmpty(result.getLhMachineCode())
                || !"01".equals(result.getScheduleType())
                || CollectionUtils.isEmpty(context.getFirstDayNoPlanReleasedContinuousMachineCodeSet())) {
            return false;
        }
        return context.getFirstDayNoPlanReleasedContinuousMachineCodeSet().contains(result.getLhMachineCode());
    }

    /**
     * 统一清洗文本字段，兼容空格和脏数据。
     *
     * @param value 原始值
     * @return 归一化结果
     */
    private String normalizeToken(String value) {
        if (StringUtils.isEmpty(value)) {
            return null;
        }
        String normalizedValue = value.trim();
        return StringUtils.isEmpty(normalizedValue) ? null : normalizedValue;
    }

    private boolean isSameInch(BigDecimal skuInch, BigDecimal machineInch) {
        if (skuInch == null || machineInch == null) {
            return false;
        }
        return skuInch.compareTo(machineInch) == 0;
    }

    private double calcInchDistance(BigDecimal skuInch, BigDecimal machineInch) {
        if (skuInch == null || machineInch == null) {
            return Double.MAX_VALUE;
        }
        return skuInch.subtract(machineInch).abs().doubleValue();
    }

    /**
     * 格式化英寸差值，MAX_VALUE 时输出 "-" 避免刷屏。
     */
    private static String formatInchDistance(double inchDistance) {
        if (inchDistance >= Double.MAX_VALUE) {
            return "-";
        }
        return String.format("%.1f", inchDistance);
    }

    /**
     * 将英寸差值转为安全的整数得分（用于 HitLevel 比较），MAX_VALUE 时使用 0。
     */
    private static int safeInchDistanceScore(double inchDistance) {
        if (inchDistance >= Double.MAX_VALUE || inchDistance >= Integer.MAX_VALUE / 10.0) {
            return 0;
        }
        return (int) (inchDistance * 10);
    }

    /**
     * 输出候选机台排序跟踪日志（含SortKey、HitLevel、最终选中机台及原因）。
     *
     * @param context 排程上下文
     * @param sku 待排SKU
     * @param matchResult 特殊物料命中结果
     * @param candidates 候选机台
     * @param trace 过滤统计
     */
    private void traceMachineCandidates(LhScheduleContext context,
                                        SkuScheduleDTO sku,
                                        SpecialMaterialMatchResult matchResult,
                                        List<MachineScheduleDTO> candidates, MachineFilterTrace trace) {
        if (!PriorityTraceLogHelper.isEnabled(context)) {
            return;
        }
        Map<String, CandidateWindowProfile> profileCache = new HashMap<>(Math.max(4, PriorityTraceLogHelper.sizeOf(candidates) * 2));
        String title = "机台排序优先级汇总【新增排产选机台】";
        StringBuilder detailBuilder = new StringBuilder(1024);
        PriorityTraceLogHelper.appendTitleHeader(detailBuilder, title);
        PriorityTraceLogHelper.appendLine(detailBuilder,
                PriorityTraceLogHelper.kv("排程日期", PriorityTraceLogHelper.formatDateTime(context.getScheduleDate()))
                        + ", " + PriorityTraceLogHelper.kv("SKU", sku.getMaterialCode())
                        + ", " + PriorityTraceLogHelper.kv("SKU类型", resolveSkuTypeDesc(sku))
                        + ", " + PriorityTraceLogHelper.kv("规格", sku.getSpecCode())
                        + ", " + PriorityTraceLogHelper.kv("寸口", sku.getProSize())
                        + ", " + PriorityTraceLogHelper.kv("特殊物料", PriorityTraceLogHelper.oneZero(matchResult.isSpecial()))
                        + ", " + PriorityTraceLogHelper.kv("特殊分类", matchResult.getCategoryDisplayText()));
        // 过滤统计
        int filteredCount = trace.notAllowedMachineFilteredCount + trace.disabledCount
                + trace.stopTimeoutCount + trace.inchMismatchCount + trace.mouldSetMismatchCount
                + trace.resolveSpecialSupportFilteredCount() + trace.mouldConflictCount
                + trace.singleControlRuleFilteredCount;
        PriorityTraceLogHelper.appendLine(detailBuilder,
                PriorityTraceLogHelper.kv("候选机台总数", trace.totalMachineCount)
                        + ", " + PriorityTraceLogHelper.kv("有效候选数", PriorityTraceLogHelper.sizeOf(candidates))
                        + ", " + PriorityTraceLogHelper.kv("过滤机台数", filteredCount)
                        + ", 过滤原因统计: 不可作业=" + trace.notAllowedMachineFilteredCount
                        + ", 禁用=" + trace.disabledCount
                        + ", 超时停机=" + trace.stopTimeoutCount
                        + ", 寸口不符=" + trace.inchMismatchCount
                        + ", 模套不符=" + trace.mouldSetMismatchCount
                        + ", 特殊不支持=" + trace.resolveSpecialSupportFilteredCount()
                        + ", 模具占用=" + trace.mouldConflictCount
                        + ", 单控规则=" + trace.singleControlRuleFilteredCount);
        if (!CollectionUtils.isEmpty(trace.filteredMachineMessages)) {
            PriorityTraceLogHelper.appendLine(detailBuilder,
                    "过滤明细: " + String.join("; ", trace.filteredMachineMessages));
        }
        if (!matchResult.isSpecial()) {
            PriorityTraceLogHelper.appendLine(detailBuilder,
                    "候选说明: 普通SKU允许使用特殊机台，特殊机台仅后置排序，不做强制保留");
        }
        if (!CollectionUtils.isEmpty(context.getReleasedContinuousMachineCodeSet())) {
            PriorityTraceLogHelper.appendLine(detailBuilder,
                    "候选说明: 续作释放机台仅在新增选机排序中降优先级，不禁止生产");
        }

        // TOP N 候选机台
        int topN = LhScheduleConstant.MACHINE_SORT_TRACE_TOP_N;
        int topCount = Math.min(topN, PriorityTraceLogHelper.sizeOf(candidates));
        PriorityTraceLogHelper.appendLine(detailBuilder, "TOP" + topCount + "候选排序:");
        List<String> levelNames = java.util.Arrays.asList(
                "L1_定点机台", "L2_单控拆分", "L3_续作释放降级", "L4_其他SKU占用", "L5_最早可开产班次",
                "L6_连续可生产班次", "L7_可用总产能", "L8_尾部零散产能", "L9_普通机台优先",
                "L10_特殊支持能力数量", "L11_收尾时间", "L12_同规格", "L13_同英寸", "L14_英寸接近度",
                "L15_胶囊共用", "L16_胎胚共用");
        for (int i = 0; i < topCount; i++) {
            MachineScheduleDTO machine = candidates.get(i);
            CandidateWindowProfile profile = resolveCandidateWindowProfile(context, sku, machine, profileCache);
            int specifyScore = resolveLimitSpecifyScore(context, sku, machine);
            int singleCtrlScore = resolveSingleControlScore(context, sku, machine);
            int normalMachineScore = resolveNormalMachinePriorityValue(matchResult, machine);
            int specialSupportCapabilityCount = resolveSpecialSupportCapabilityCount(machine);
            int specMatchScore = resolveSpecMatchScore(sku, machine);
            int proSizeMatchScore = resolveProSizeMatchScore(sku, machine);
            double inchDistance = resolveInchDistance(sku, machine);
            int capsuleScore = resolveCapsuleAffinityScore(context, sku, machine);
            int embryoShareCount = resolveEmbryoShareCount(context, machine);
            boolean inchMatched = LhMachineHardMatchUtil.isInchInRange(
                    parseInch(sku.getProSize()), machine.getDimensionMinimum(), machine.getDimensionMaximum());
            boolean mouldSetMatched = LhMachineHardMatchUtil.isMouldSetMatched(context, sku, machine);
            boolean specialMatched = LhMachineHardMatchUtil.isSpecialMaterialSupported(matchResult, machine);
            boolean specialSupportMachine = !LhMachineHardMatchUtil.isNormalMachine(machine);
            String skuShellStandard = resolveSkuShellStandardDisplay(context, sku);

            List<String> sortKeyLevels = java.util.Arrays.asList(
                    "L1_定点机台=" + (specifyScore == 0 ? 1 : 0),
                    "L2_单控拆分=" + (isSingleControlMachine(context, machine.getMachineCode()) ? 1 : 0),
                    "L3_续作释放降级=" + profile.getReleasedContinuousMachineScore(),
                    "L4_其他SKU占用=" + profile.getOtherSkuOccupiedScore(),
                    "L5_最早可开产班次=" + profile.getFirstProductionShiftIndex(),
                    "L6_连续可生产班次=" + profile.getContinuousSchedulableShiftCount(),
                    "L7_可用总产能=" + profile.getAvailableCapacityQty(),
                    "L8_尾部零散产能=" + profile.getTailFragmentScore(),
                    "L9_普通机台优先=" + (normalMachineScore == 0 ? 1 : 0),
                    "L10_特殊支持能力数量=" + specialSupportCapabilityCount,
                    "L11_收尾时间=" + PriorityTraceLogHelper.formatDateTime(profile.getReferenceTime()),
                    "L12_同规格=" + (specMatchScore == 0 ? 1 : 0),
                    "L13_同英寸=" + (proSizeMatchScore == 0 ? 1 : 0),
                    "L14_英寸接近度=" + formatInchDistance(inchDistance),
                    "L15_胶囊共用=" + (capsuleScore == 0 ? 1 : 0),
                    "L16_胎胚共用=" + embryoShareCount);
            List<Integer> scores = java.util.Arrays.asList(
                    specifyScore,
                    singleCtrlScore,
                    profile.getReleasedContinuousMachineScore(),
                    profile.getOtherSkuOccupiedScore(),
                    profile.getFirstProductionShiftIndex(),
                    profile.getContinuousSchedulableShiftCount(),
                    profile.getAvailableCapacityQty(),
                    profile.getTailFragmentScore(),
                    normalMachineScore,
                    specialSupportCapabilityCount,
                    resolveEndingTimeScore(machine),
                    specMatchScore,
                    proSizeMatchScore,
                    safeInchDistanceScore(inchDistance),
                    capsuleScore,
                    embryoShareCount);
            List<Integer> defaultScores = java.util.Arrays.asList(1, 1, 0, 0, 0,
                    0, LhScheduleConstant.MAX_SHIFT_SLOT_COUNT + 1, 0, 0, 1, 0, 1, 1, 0, 1, 0);
            String sortKey = PriorityTraceLogHelper.formatSortKey(sortKeyLevels);
            String hitLevel = PriorityTraceLogHelper.resolveHitLevel(levelNames, scores, defaultScores);

            boolean isSingleCtrl = isSingleControlMachine(context, machine.getMachineCode());
            String machineTypeDesc = resolveMachineTypeDesc(machine);
            PriorityTraceLogHelper.appendLine(detailBuilder,
                    (i + 1)
                            + ". " + PriorityTraceLogHelper.kv("机台", machine.getMachineCode())
                            + ", " + PriorityTraceLogHelper.kv("名称", machine.getMachineName())
                            + ", " + PriorityTraceLogHelper.kv("机台类型", machineTypeDesc)
                            + ", " + PriorityTraceLogHelper.kv("状态", machine.getStatus())
                            + ", " + PriorityTraceLogHelper.kv("可用", PriorityTraceLogHelper.oneZero(MachineStatusUtil.isEnabled(machine.getStatus())))
                            + ", " + PriorityTraceLogHelper.kv("单控", PriorityTraceLogHelper.oneZero(isSingleCtrl))
                            + ", " + PriorityTraceLogHelper.kv("普通机台", PriorityTraceLogHelper.oneZero(LhMachineHardMatchUtil.isNormalMachine(machine)))
                            + ", " + PriorityTraceLogHelper.kv("特殊支持机台", PriorityTraceLogHelper.oneZero(specialSupportMachine))
                            + ", " + PriorityTraceLogHelper.kv("特殊支持能力数量", specialSupportCapabilityCount)
                            + ", " + PriorityTraceLogHelper.kv("机台偏好原因", resolveMachinePreferenceReason(context, sku, machine))
                            + ", " + PriorityTraceLogHelper.kv("定点", PriorityTraceLogHelper.oneZero(specifyScore == 0))
                            + ", " + PriorityTraceLogHelper.kv("续作释放机台", PriorityTraceLogHelper.oneZero(profile.getReleasedContinuousMachineScore() > 0))
                            + ", " + PriorityTraceLogHelper.kv("支持SKU", PriorityTraceLogHelper.oneZero(true))
                            + ", " + PriorityTraceLogHelper.kv("英寸匹配", PriorityTraceLogHelper.oneZero(inchMatched))
                            + ", " + PriorityTraceLogHelper.kv("SKU英寸", sku.getProSize())
                            + ", " + PriorityTraceLogHelper.kv("机台英寸下限", machine.getDimensionMinimum())
                            + ", " + PriorityTraceLogHelper.kv("机台英寸上限", machine.getDimensionMaximum())
                            + ", " + PriorityTraceLogHelper.kv("模套匹配", PriorityTraceLogHelper.oneZero(mouldSetMatched))
                            + ", " + PriorityTraceLogHelper.kv("SKU模套型号", skuShellStandard)
                            + ", " + PriorityTraceLogHelper.kv("机台适用模套型号", machine.getShellStandard())
                            + ", " + PriorityTraceLogHelper.kv("特殊材料匹配", PriorityTraceLogHelper.oneZero(specialMatched))
                            + ", " + PriorityTraceLogHelper.kv("当前在机", machine.getPreviousMaterialCode())
                            + ", " + PriorityTraceLogHelper.kv("最早换模时间", PriorityTraceLogHelper.formatDateTime(profile.getSwitchStartTime()))
                            + ", " + PriorityTraceLogHelper.kv("最早可开产时间", PriorityTraceLogHelper.formatDateTime(profile.getProductionStartTime()))
                            + ", " + PriorityTraceLogHelper.kv("最早可开产班次", profile.getFirstProductionShiftIndex())
                            + ", " + PriorityTraceLogHelper.kv("连续可生产班次数", profile.getContinuousSchedulableShiftCount())
                            + ", " + PriorityTraceLogHelper.kv("可用总产能", profile.getAvailableCapacityQty())
                            + ", " + PriorityTraceLogHelper.kv("被其他SKU占用", PriorityTraceLogHelper.oneZero(profile.getOtherSkuOccupiedScore() > 0))
                            + ", " + PriorityTraceLogHelper.kv("尾部零散产能", PriorityTraceLogHelper.oneZero(profile.getTailFragmentScore() > 0))
                            + ", " + PriorityTraceLogHelper.kv("需要换模", "1")
                            + ", " + PriorityTraceLogHelper.kv("命中晚班不能换模", PriorityTraceLogHelper.oneZero(profile.isHitNoMouldChange()))
                            + ", " + PriorityTraceLogHelper.kv("收尾时间", PriorityTraceLogHelper.formatDateTime(machine.getEstimatedEndTime()))
                            + ", " + PriorityTraceLogHelper.kv("同规格", PriorityTraceLogHelper.oneZero(specMatchScore == 0))
                            + ", " + PriorityTraceLogHelper.kv("同英寸", PriorityTraceLogHelper.oneZero(proSizeMatchScore == 0))
                            + ", " + PriorityTraceLogHelper.kv("英寸差", formatInchDistance(inchDistance))
                            + ", " + PriorityTraceLogHelper.kv("胶囊共用", PriorityTraceLogHelper.oneZero(capsuleScore == 0))
                            + ", " + PriorityTraceLogHelper.kv("胎胚共用数", embryoShareCount)
                            + ", " + PriorityTraceLogHelper.kv("机台顺序", machine.getMachineOrder())
                            + ", " + PriorityTraceLogHelper.kv("SortKey", sortKey)
                            + ", " + PriorityTraceLogHelper.kv("HitLevel", hitLevel));
        }
        if (PriorityTraceLogHelper.sizeOf(candidates) > topN) {
            PriorityTraceLogHelper.appendLine(detailBuilder,
                    "... 共" + PriorityTraceLogHelper.sizeOf(candidates) + "台，仅展示前" + topN + "台");
        }
        // 最终选中机台
        if (!CollectionUtils.isEmpty(candidates)) {
            MachineScheduleDTO best = candidates.get(0);
            String selectReason = resolveMachineSelectReason(context, sku, matchResult, best);
            PriorityTraceLogHelper.appendLine(detailBuilder,
                    "最终选中机台: " + best.getMachineCode()
                            + ", 选中原因: " + selectReason);
        }
        PriorityTraceLogHelper.appendTitleFooter(detailBuilder);
        String detail = detailBuilder.toString().trim();
        PriorityTraceLogHelper.logSortSummary(log, context, title, detail);
    }

    /**
     * 解析机台选中原因。
     *
     * @param context 排程上下文
     * @param sku 待排SKU
     * @param matchResult 特殊物料命中结果
     * @param machine 选中机台
     * @return 选中原因
     */
    private String resolveMachineSelectReason(LhScheduleContext context, SkuScheduleDTO sku,
                                              SpecialMaterialMatchResult matchResult,
                                              MachineScheduleDTO machine) {
        CandidateWindowProfile profile = resolveCandidateWindowProfile(
                context, sku, machine, new HashMap<String, CandidateWindowProfile>(4));
        List<String> reasons = new ArrayList<>(4);
        if (resolveLimitSpecifyScore(context, sku, machine) == 0) {
            reasons.add("定点机台优先");
        }
        if (isSingleControlMachine(context, machine.getMachineCode())
                && shouldReserveSingleControlForTrialSku(sku)) {
            reasons.add("单控机台优先");
        }
        if (resolveSpecMatchScore(sku, machine) == 0) {
            reasons.add("规格匹配");
        }
        if (resolveProSizeMatchScore(sku, machine) == 0) {
            reasons.add("英寸匹配");
        }
        if (resolveCapsuleAffinityScore(context, sku, machine) == 0) {
            reasons.add("胶囊共用");
        }
        if (profile.getOtherSkuOccupiedScore() == 0) {
            reasons.add("未被其他SKU占用");
        }
        if (profile.getReleasedContinuousMachineScore() > 0) {
            reasons.add("续作释放机台仅降优先级，不禁止生产");
        }
        if (profile.getContinuousSchedulableShiftCount() > 0) {
            reasons.add("连续可生产班次更多");
        }
        if (profile.getTailFragmentScore() == 0) {
            reasons.add("非尾部零散产能");
        }
        if (profile.isHitNoMouldChange()) {
            reasons.add("规避晚班不能换模窗口");
        }
        if (Objects.nonNull(matchResult) && !matchResult.isSpecial()
                && !LhMachineHardMatchUtil.isNormalMachine(machine)) {
            reasons.add("普通SKU允许使用特殊机台，特殊机台仅后置排序，不做强制保留");
            reasons.add("特殊支持能力更少优先");
        }
        if (machine.getEstimatedEndTime() != null) {
            reasons.add("收尾时间最近");
        }
        if (reasons.isEmpty()) {
            reasons.add("排序首位默认");
        }
        return String.join("，", reasons);
    }

    /**
     * 解析当前SKU对候选机台类型的偏好原因。
     *
     * @param context 排程上下文
     * @param sku 待排SKU
     * @param machine 候选机台
     * @return 偏好原因
     */
    private String resolveMachinePreferenceReason(LhScheduleContext context, SkuScheduleDTO sku,
                                                  MachineScheduleDTO machine) {
        boolean singleControlMachine = isSingleControlMachine(context, machine.getMachineCode());
        if (isTrialConstructionStage(sku)) {
            return singleControlMachine ? "试制SKU只能使用单控机台" : "试制SKU禁止使用普通机台";
        }
        if (isMassTrialSku(sku)) {
            return singleControlMachine ? "量试SKU优先使用单控机台" : "量试SKU单控不足时允许使用普通机台";
        }
        if (isSmallBatchSku(sku)) {
            return singleControlMachine ? "小批量SKU优先使用单控机台" : "小批量SKU单控不足时允许使用普通机台";
        }
        return singleControlMachine ? "正规SKU普通机台不足时允许使用单控机台" : "正规SKU优先使用普通机台";
    }

    /**
     * 解析普通机台优先级数值（仅用于日志输出）。
     *
     * @param matchResult 特殊物料命中结果
     * @param machine 机台
     * @return 优先级数值
     */
    private int resolveNormalMachinePriorityValue(SpecialMaterialMatchResult matchResult,
                                                   MachineScheduleDTO machine) {
        if (Objects.nonNull(matchResult) && matchResult.isSpecial()) {
            return 0;
        }
        return LhMachineHardMatchUtil.resolveNormalMachinePriority(machine);
    }

    /**
     * 统计机台特殊支持能力数量。
     *
     * @param machine 机台
     * @return 能力数量
     */
    private int resolveSpecialSupportCapabilityCount(MachineScheduleDTO machine) {
        int capabilityCount = 0;
        if (LhMachineHardMatchUtil.isSupport195WideBase(machine)) {
            capabilityCount++;
        }
        if (LhMachineHardMatchUtil.isSupport225WideBase(machine)) {
            capabilityCount++;
        }
        if (LhMachineHardMatchUtil.isSupportChipTire(machine)) {
            capabilityCount++;
        }
        return capabilityCount;
    }

    /**
     * 解析机台类型描述。
     *
     * @param machine 机台
     * @return 普通机台/特殊机台
     */
    private String resolveMachineTypeDesc(MachineScheduleDTO machine) {
        return LhMachineHardMatchUtil.isNormalMachine(machine) ? "普通机台" : "特殊机台";
    }

    /**
     * 汇总SKU模套型号，供日志输出。
     *
     * @param context 排程上下文
     * @param sku SKU
     * @return 模套型号文本
     */
    private String resolveSkuShellStandardDisplay(LhScheduleContext context, SkuScheduleDTO sku) {
        if (context == null || sku == null || StringUtils.isEmpty(sku.getMaterialCode())) {
            return null;
        }
        List<MdmSkuMouldRel> mouldRelList = context.getSkuMouldRelMap().get(sku.getMaterialCode());
        if (CollectionUtils.isEmpty(mouldRelList) || CollectionUtils.isEmpty(context.getModelInfoMap())) {
            return null;
        }
        Set<String> shellStandardSet = new java.util.LinkedHashSet<String>(mouldRelList.size());
        for (MdmSkuMouldRel mouldRel : mouldRelList) {
            if (mouldRel == null || StringUtils.isEmpty(mouldRel.getMouldCode())) {
                continue;
            }
            MdmModelInfo modelInfo = context.getModelInfoMap().get(mouldRel.getMouldCode());
            String shellStandard = normalizeToken(modelInfo == null ? null : modelInfo.getShellStandard());
            if (StringUtils.isNotEmpty(shellStandard)) {
                shellStandardSet.add(shellStandard);
            }
        }
        return CollectionUtils.isEmpty(shellStandardSet) ? null : StringUtils.join(shellStandardSet, ",");
    }

    /**
     * 解析收尾时间得分，用于 HitLevel 比较（0=无收尾时间靠后，1=有收尾时间优先）。
     */
    private static int resolveEndingTimeScore(MachineScheduleDTO machine) {
        return machine != null && machine.getEstimatedEndTime() != null ? 1 : 0;
    }

    /**
     * 候选机台生产窗口画像。
     */
    private static class CandidateWindowProfile {
        /** 参考收尾时间 */
        private Date referenceTime;
        /** 最早可换模时间 */
        private Date switchStartTime;
        /** 最早可开产时间 */
        private Date productionStartTime;
        /** 最早可开产班次 */
        private int firstProductionShiftIndex = LhScheduleConstant.MAX_SHIFT_SLOT_COUNT + 1;
        /** 连续可生产班次数 */
        private int continuousSchedulableShiftCount;
        /** 总可生产班次数 */
        private int totalSchedulableShiftCount;
        /** 可用总产能 */
        private int availableCapacityQty;
        /** 续作释放机台得分，0=普通候选，1=续作释放候选 */
        private int releasedContinuousMachineScore;
        /** 被其他SKU占用得分，0=未占用，1=已占用 */
        private int otherSkuOccupiedScore;
        /** 尾部零散产能得分，0=否，1=是 */
        private int tailFragmentScore;
        /** 是否命中晚班不能换模 */
        private boolean hitNoMouldChange;

        private static CandidateWindowProfile empty() {
            return new CandidateWindowProfile();
        }

        private Date getReferenceTime() {
            return referenceTime;
        }

        private void setReferenceTime(Date referenceTime) {
            this.referenceTime = referenceTime;
        }

        private Date getSwitchStartTime() {
            return switchStartTime;
        }

        private void setSwitchStartTime(Date switchStartTime) {
            this.switchStartTime = switchStartTime;
        }

        private Date getProductionStartTime() {
            return productionStartTime;
        }

        private void setProductionStartTime(Date productionStartTime) {
            this.productionStartTime = productionStartTime;
        }

        private int getFirstProductionShiftIndex() {
            return firstProductionShiftIndex;
        }

        private void setFirstProductionShiftIndex(int firstProductionShiftIndex) {
            this.firstProductionShiftIndex = firstProductionShiftIndex;
        }

        private int getContinuousSchedulableShiftCount() {
            return continuousSchedulableShiftCount;
        }

        private void setContinuousSchedulableShiftCount(int continuousSchedulableShiftCount) {
            this.continuousSchedulableShiftCount = continuousSchedulableShiftCount;
        }

        private int getTotalSchedulableShiftCount() {
            return totalSchedulableShiftCount;
        }

        private void setTotalSchedulableShiftCount(int totalSchedulableShiftCount) {
            this.totalSchedulableShiftCount = totalSchedulableShiftCount;
        }

        private int getAvailableCapacityQty() {
            return availableCapacityQty;
        }

        private void setAvailableCapacityQty(int availableCapacityQty) {
            this.availableCapacityQty = availableCapacityQty;
        }

        private int getReleasedContinuousMachineScore() {
            return releasedContinuousMachineScore;
        }

        private void setReleasedContinuousMachineScore(int releasedContinuousMachineScore) {
            this.releasedContinuousMachineScore = releasedContinuousMachineScore;
        }

        private int getOtherSkuOccupiedScore() {
            return otherSkuOccupiedScore;
        }

        private void setOtherSkuOccupiedScore(int otherSkuOccupiedScore) {
            this.otherSkuOccupiedScore = otherSkuOccupiedScore;
        }

        private int getTailFragmentScore() {
            return tailFragmentScore;
        }

        private void setTailFragmentScore(int tailFragmentScore) {
            this.tailFragmentScore = tailFragmentScore;
        }

        private boolean isHitNoMouldChange() {
            return hitNoMouldChange;
        }

        private void setHitNoMouldChange(boolean hitNoMouldChange) {
            this.hitNoMouldChange = hitNoMouldChange;
        }
    }

    /**
     * 机台不可用原因枚举。
     */
    private enum MachineAvailabilityReason {
        AVAILABLE,
        DISABLED,
        STOP_TIMEOUT,
        INCH_MISMATCH,
        MOULD_SET_MISMATCH,
        SPECIAL_195_UNSUPPORTED,
        SPECIAL_225_UNSUPPORTED,
        SPECIAL_CHIP_UNSUPPORTED,
        SPECIAL_CATEGORY_UNSUPPORTED,
        MOULD_CONFLICT
    }

    /**
     * 候选机台过滤统计。
     */
    private static class MachineFilterTrace {
        /** 机台总数 */
        private final int totalMachineCount;
        /** 不可作业过滤数 */
        private int notAllowedMachineFilteredCount;
        /** 禁用过滤数 */
        private int disabledCount;
        /** 超时停机过滤数 */
        private int stopTimeoutCount;
        /** 寸口不符过滤数 */
        private int inchMismatchCount;
        /** 模套型号不符过滤数 */
        private int mouldSetMismatchCount;
        /** 不支持19.5寸宽基过滤数 */
        private int special195UnsupportedCount;
        /** 不支持22.5寸宽基过滤数 */
        private int special225UnsupportedCount;
        /** 不支持芯片胎过滤数 */
        private int specialChipUnsupportedCount;
        /** 特殊分类异常过滤数 */
        private int specialCategoryUnsupportedCount;
        /** 模具冲突过滤数 */
        private int mouldConflictCount;
        /** 单控/普通机台类型约束过滤数 */
        private int singleControlRuleFilteredCount;
        /** 过滤明细 */
        private final List<String> filteredMachineMessages = new ArrayList<>(8);

        private MachineFilterTrace(int totalMachineCount) {
            this.totalMachineCount = totalMachineCount;
        }

        private void recordFilteredMachine(MachineScheduleDTO machine, String reason) {
            filteredMachineMessages.add(buildMachineMessage(machine, reason));
        }

        private void recordAvailabilityReason(MachineScheduleDTO machine, MachineAvailabilityReason reason) {
            if (MachineAvailabilityReason.DISABLED == reason) {
                disabledCount++;
                recordFilteredMachine(machine, "机台禁用");
            } else if (MachineAvailabilityReason.STOP_TIMEOUT == reason) {
                stopTimeoutCount++;
                recordFilteredMachine(machine, "停机超过阈值");
            } else if (MachineAvailabilityReason.INCH_MISMATCH == reason) {
                inchMismatchCount++;
                recordFilteredMachine(machine, "寸口不匹配");
            } else if (MachineAvailabilityReason.MOULD_SET_MISMATCH == reason) {
                mouldSetMismatchCount++;
                recordFilteredMachine(machine, "模套型号不匹配");
            } else if (MachineAvailabilityReason.SPECIAL_195_UNSUPPORTED == reason) {
                special195UnsupportedCount++;
                recordFilteredMachine(machine, "不支持19.5寸宽基");
            } else if (MachineAvailabilityReason.SPECIAL_225_UNSUPPORTED == reason) {
                special225UnsupportedCount++;
                recordFilteredMachine(machine, "不支持22.5寸宽基");
            } else if (MachineAvailabilityReason.SPECIAL_CHIP_UNSUPPORTED == reason) {
                specialChipUnsupportedCount++;
                recordFilteredMachine(machine, "不支持芯片胎");
            } else if (MachineAvailabilityReason.SPECIAL_CATEGORY_UNSUPPORTED == reason) {
                specialCategoryUnsupportedCount++;
                recordFilteredMachine(machine, "特殊分类不支持");
            } else if (MachineAvailabilityReason.MOULD_CONFLICT == reason) {
                mouldConflictCount++;
                recordFilteredMachine(machine, "模具占用");
            }
        }

        private int resolveSpecialSupportFilteredCount() {
            return special195UnsupportedCount
                    + special225UnsupportedCount
                    + specialChipUnsupportedCount
                    + specialCategoryUnsupportedCount;
        }

        private String buildMachineMessage(MachineScheduleDTO machine, String reason) {
            StringBuilder builder = new StringBuilder(64);
            builder.append(PriorityTraceLogHelper.safeText(machine.getMachineCode()))
                    .append('[').append(reason).append(']');
            return builder.toString();
        }
    }
}
