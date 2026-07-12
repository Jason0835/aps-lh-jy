/**
 * Copyright (c) 2008, 智立通（厦门）科技有限公司 All rights reserved。
 */
package com.zlt.aps.lh.engine.strategy.impl;

import com.zlt.aps.lh.api.constant.LhScheduleConstant;
import com.zlt.aps.lh.api.constant.LhScheduleParamConstant;
import com.zlt.aps.lh.api.domain.dto.SpecialMaterialMatchResult;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.api.domain.dto.MachineScheduleDTO;
import com.zlt.aps.lh.api.domain.dto.SkuDailyPlanQuotaDTO;
import com.zlt.aps.lh.api.domain.dto.SkuScheduleDTO;
import com.zlt.aps.lh.api.domain.entity.LhScheduleResult;
import com.zlt.aps.lh.api.domain.vo.LhShiftConfigVO;
import com.zlt.aps.lh.api.enums.ConstructionStageEnum;
import com.zlt.aps.lh.api.enums.LhSpecialMaterialCategoryEnum;
import com.zlt.aps.lh.api.enums.SkuTagEnum;
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
import java.time.ZoneId;
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
 *   <li>先执行硬性过滤：定点不可作业、机台状态、寸口、特殊材料能力、模具占用和停机窗口；</li>
 *   <li>再执行单控/普通机台类型约束，区分试制、量试、小批量和正规 SKU；</li>
 *   <li>最后先锁定最早收尾后20分钟窗口，再按单控拆分/胎胚/模壳/规格/胶囊/英寸/机台编码排序。</li>
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
    /** 每分钟毫秒数 */
    private static final long MILLIS_PER_MINUTE = 60L * 1000L;
    /**
     * 新增选机收尾窗口分钟数（已废弃）。
     * <p>原"最早收尾时间后20分钟窗口"已改为"最早参考收尾时间所在班次同班次筛选"，
     * 该常量仅保留用于历史日志对比，不再参与窗口筛选判定。</p>
     */
    private static final int ENDING_WINDOW_MINUTES = 20;
    /** 试制SKU单控机台优先得分 */
    private static final int SINGLE_CONTROL_TRIAL_SCORE = 0;
    /** 量试SKU单控机台优先得分 */
    private static final int SINGLE_CONTROL_MASS_TRIAL_SCORE = 1;
    /** 小批量SKU单控机台优先得分 */
    private static final int SINGLE_CONTROL_SMALL_BATCH_SCORE = 2;
    /** 普通机台默认得分 */
    private static final int SINGLE_CONTROL_NORMAL_MACHINE_SCORE = 3;
    /** 正规SKU选择单控机台时的既有排序靠后得分；不参与单模/双模判定 */
    private static final int SINGLE_CONTROL_FORMAL_SCORE = 4;

    /**
     * 判断冻结时 SKU 是否至少存在一个满足静态准入的单控侧。
     * <p>这里复用正式选机的定点、机台状态、寸口、模套、特殊物料和模具硬约束，
     * 但不调用尚未生成的单模/双模快照，也不应用本轮后续动态预留。</p>
     *
     * @param context 排程上下文
     * @param sku 待冻结模式的SKU
     * @return true-至少一个单控侧可在本次窗口参与排产
     */
    @Override
    public boolean hasEligibleSingleControlSide(LhScheduleContext context, SkuScheduleDTO sku) {
        if (Objects.isNull(context) || Objects.isNull(sku)) {
            return false;
        }
        for (MachineScheduleDTO machine : context.getMachineScheduleMap().values()) {
            if (Objects.nonNull(machine)
                    && isEligibleSingleControlSide(context, sku, machine.getMachineCode())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 校验指定单控侧是否满足冻结统计或双模配对的静态硬约束。
     *
     * @param context 排程上下文
     * @param sku 待排SKU
     * @param machineCode 单控侧机台编码
     * @return true-满足约束且在本次窗口内存在可接续时间
     */
    @Override
    public boolean isEligibleSingleControlSide(LhScheduleContext context,
                                               SkuScheduleDTO sku,
                                               String machineCode) {
        if (Objects.isNull(context) || Objects.isNull(sku) || StringUtils.isEmpty(machineCode)) {
            return false;
        }
        MachineScheduleDTO machine = context.getMachineScheduleMap().get(machineCode);
        if (Objects.isNull(machine) || !isSingleControlMachine(context, machineCode)) {
            return false;
        }
        Set<String> notAllowedMachineCodes = LhSpecifyMachineUtil.resolveNotAllowedMachineCodes(
                context, sku.getMaterialCode());
        List<String> skuMouldCodes = getSkuMouldCodes(context, sku.getMaterialCode());
        Set<String> occupiedMouldCodes = getOccupiedMouldCodes(context);
        BigDecimal skuInch = parseInch(sku.getProSize());
        SpecialMaterialMatchResult matchResult = LhSpecialMaterialUtil.resolveMatchResult(context, sku);
        Date windowEndTime = resolveScheduleWindowEndTime(context);
        if (isNotAllowedMachine(notAllowedMachineCodes, machine)) {
            return false;
        }
        MachineAvailabilityReason availabilityReason = resolveMachineAvailabilityReason(
                context, sku, skuMouldCodes, occupiedMouldCodes, skuInch, matchResult, machine);
        Date referenceTime = resolveAlignedCandidateReferenceTime(context, machine);
        return MachineAvailabilityReason.AVAILABLE == availabilityReason
                && (Objects.isNull(windowEndTime) || Objects.isNull(referenceTime)
                || referenceTime.before(windowEndTime));
    }

    /**
     * 解析当前排程窗口的最终结束时间。
     *
     * @param context 排程上下文
     * @return 最后一个有效班次的结束时间
     */
    private Date resolveScheduleWindowEndTime(LhScheduleContext context) {
        Date windowEndTime = null;
        for (LhShiftConfigVO shift : context.getScheduleWindowShifts()) {
            if (Objects.nonNull(shift) && Objects.nonNull(shift.getShiftEndDateTime())
                    && (Objects.isNull(windowEndTime) || shift.getShiftEndDateTime().after(windowEndTime))) {
                windowEndTime = shift.getShiftEndDateTime();
            }
        }
        return windowEndTime;
    }

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

        // 模具匹配日志：记录SKU模具总数、已占用模具号，便于排查模具冲突问题
        if (!skuMouldCodes.isEmpty() && !CollectionUtils.isEmpty(occupiedMouldCodes)) {
            List<String> conflictedMouldCodes = new ArrayList<>();
            for (String mouldCode : skuMouldCodes) {
                if (occupiedMouldCodes.contains(mouldCode)) {
                    conflictedMouldCodes.add(mouldCode);
                }
            }
            int freeMouldCount = skuMouldCodes.size() - conflictedMouldCodes.size();
            log.info("SKU模具占用检查, materialCode: {}, scheduleDate: {}, SKU模具总数: {}, 全局已占用模具号: {}, "
                            + "冲突模具数: {}, 空闲模具数: {}, 冲突模具号: {}",
                    sku.getMaterialCode(), LhScheduleTimeUtil.formatDate(context.getScheduleDate()),
                    skuMouldCodes.size(), occupiedMouldCodes,
                    conflictedMouldCodes.size(), freeMouldCount, conflictedMouldCodes);
        }
        // 4. 过滤候选机台：状态启用 + 硬性指标匹配 + 模具未被占用。
        // 模套型号匹配作为硬过滤（到货模具不降级），同模壳仍参与后续最早收尾窗口内排序降级。
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

        // 单控/普通机台约束是类型规则：试制强约束单控，量试/小批量优先单边单控，正规单控必须L/R成组。
        // 该规则只处理候选集合，不在此消费机台；最终是否占用仍由 S4.5 换模、首检和产能结果决定。
        candidates = applySingleControlReservationRule(context, sku, candidates, trace);

        // 5. 先按最早参考收尾时间同班次筛选，再按单控、胎胚、模壳、规格、胶囊、英寸和机台编码排序。
        EndingWindowContext endingWindowContext = sortCandidates(context, candidates, sku);
        traceMachineCandidates(context, sku, specialMaterialMatchResult, candidates, trace, endingWindowContext);

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
     * <p>试制只保留单边单控候选；量试/小批量优先单边单控、无单控时回落普通；
     * 正规优先普通，单控候选必须先收敛成L/R整机候选后才能作为普通机台后的回落。</p>
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
        if (CollectionUtils.isEmpty(candidates) || Objects.isNull(sku)) {
            return candidates;
        }
        List<MachineScheduleDTO> singleControlCandidates = new ArrayList<>(2);
        List<MachineScheduleDTO> normalCandidates = new ArrayList<>(candidates.size());
        for (MachineScheduleDTO candidate : candidates) {
            if (Objects.isNull(candidate)) {
                continue;
            }
            if (isSingleControlMachine(context, candidate.getMachineCode())) {
                // 单控基准机台已拆成 L/R 运行态机台，候选侧别和班产在后续产能阶段继续按运行态口径处理。
                singleControlCandidates.add(candidate);
                continue;
            }
            normalCandidates.add(candidate);
        }
        // 没有单控候选时无需读取模式快照，普通机台选机链保持原有行为。
        List<MachineScheduleDTO> effectiveSingleControlCandidates =
                !CollectionUtils.isEmpty(singleControlCandidates)
                        && LhSingleControlMachineUtil.isWholeMachineGranularitySku(context, sku)
                ? resolveWholeSingleControlCandidates(context, sku, singleControlCandidates)
                : singleControlCandidates;
        List<MachineScheduleDTO> filteredCandidates = resolveCandidatesBySkuType(
                context, sku, effectiveSingleControlCandidates, normalCandidates);
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
     * 将正规 SKU 的单控 L/R 候选收敛成物理整机候选。
     * <p>正规 SKU 不能单边上机，因此只有左右两侧都已经通过前置硬过滤、且当前排程运行态没有被其它 SKU
     * 占用时，才保留一个左侧代表候选。代表候选只用于排序和后续入口传递，真正结果写入仍需同步生成 L/R 两条。</p>
     *
     * @param context 排程上下文
     * @param sku 待排正规 SKU
     * @param singleControlCandidates 单边候选集合
     * @return 整机候选集合，每个物理单控机台最多返回一个左侧代表
     */
    private List<MachineScheduleDTO> resolveWholeSingleControlCandidates(LhScheduleContext context,
                                                                         SkuScheduleDTO sku,
                                                                         List<MachineScheduleDTO> singleControlCandidates) {
        if (CollectionUtils.isEmpty(singleControlCandidates)) {
            return singleControlCandidates;
        }
        Map<String, MachineScheduleDTO> candidateMap = new HashMap<>(singleControlCandidates.size());
        for (MachineScheduleDTO candidate : singleControlCandidates) {
            if (Objects.nonNull(candidate) && StringUtils.isNotEmpty(candidate.getMachineCode())) {
                candidateMap.put(candidate.getMachineCode(), candidate);
            }
        }
        List<MachineScheduleDTO> wholeMachineCandidates = new ArrayList<>(singleControlCandidates.size());
        Set<String> processedPhysicalMachineSet = new HashSet<>(singleControlCandidates.size());
        for (MachineScheduleDTO candidate : singleControlCandidates) {
            if (Objects.isNull(candidate) || StringUtils.isEmpty(candidate.getMachineCode())) {
                continue;
            }
            String physicalMachineCode = LhSingleControlMachineUtil.resolvePhysicalMachineCode(candidate.getMachineCode());
            if (StringUtils.isEmpty(physicalMachineCode) || processedPhysicalMachineSet.contains(physicalMachineCode)) {
                continue;
            }
            processedPhysicalMachineSet.add(physicalMachineCode);
            String leftMachineCode = LhSingleControlMachineUtil.resolveLeftMachineCode(candidate.getMachineCode());
            String rightMachineCode = LhSingleControlMachineUtil.resolveRightMachineCode(candidate.getMachineCode());
            MachineScheduleDTO leftMachine = candidateMap.get(leftMachineCode);
            MachineScheduleDTO rightMachine = candidateMap.get(rightMachineCode);
            if (Objects.isNull(leftMachine) || Objects.isNull(rightMachine)) {
                log.debug("双模SKU单控整机候选过滤, materialCode: {}, physicalMachine: {}, leftExists: {}, rightExists: {}, reason: {}",
                        sku.getMaterialCode(), physicalMachineCode, Objects.nonNull(leftMachine), Objects.nonNull(rightMachine),
                        "L/R两侧未同时通过硬性机台约束");
                continue;
            }
            if (hasOtherSkuAssignment(context, sku, leftMachineCode) || hasOtherSkuAssignment(context, sku, rightMachineCode)) {
                log.info("双模SKU单控整机候选过滤, materialCode: {}, physicalMachine: {}, leftMachine: {}, rightMachine: {}, reason: {}",
                        sku.getMaterialCode(), physicalMachineCode, leftMachineCode, rightMachineCode,
                        "L/R任一侧已被其它SKU占用");
                continue;
            }
            wholeMachineCandidates.add(leftMachine);
        }
        return wholeMachineCandidates;
    }

    /**
     * 判断单控某一侧是否已被其它 SKU 的当前排程结果占用。
     * <p>正规 SKU 整机候选要求左右两侧同进同出；若任一侧已登记其它物料结果，
     * 当前物理单控机台不能再作为正规 SKU 候选，避免左右模混排。</p>
     *
     * @param context 排程上下文
     * @param sku 当前待排 SKU
     * @param machineCode 机台编码
     * @return true-已被其它 SKU 占用
     */
    private boolean hasOtherSkuAssignment(LhScheduleContext context, SkuScheduleDTO sku, String machineCode) {
        if (Objects.isNull(context) || Objects.isNull(sku) || StringUtils.isEmpty(machineCode)
                || CollectionUtils.isEmpty(context.getMachineAssignmentMap())) {
            return false;
        }
        List<LhScheduleResult> assignedResults = context.getMachineAssignmentMap().get(machineCode);
        if (CollectionUtils.isEmpty(assignedResults)) {
            return false;
        }
        for (LhScheduleResult assignedResult : assignedResults) {
            if (shouldIgnoreReleasedContinuousPlaceholder(context, assignedResult)) {
                continue;
            }
            if (Objects.isNull(assignedResult) || StringUtils.isEmpty(assignedResult.getMaterialCode())) {
                return true;
            }
            if (!StringUtils.equals(sku.getMaterialCode(), assignedResult.getMaterialCode())) {
                return true;
            }
        }
        return false;
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
        if (!CollectionUtils.isEmpty(normalCandidates)) {
            // 正规SKU优先普通机台；若要使用单控机台，候选必须已经收敛为L/R整机代表。
            // 单控整机放在普通机台之后，避免正规 SKU 抢占后续特殊 SKU 可能需要的单边单控资源。
            return retainNormalThenSingleCandidates(singleControlCandidates, normalCandidates);
        }
        // 正规SKU仅剩单控候选时，也只能使用已经成组的单控整机候选。
        return singleControlCandidates;
    }

    /**
     * 正规SKU候选顺序：普通机台优先，单控机台作为回落。
     *
     * @param singleControlCandidates 单控候选
     * @param normalCandidates 普通候选
     * @return 普通在前、单控在后的候选列表
     */
    private List<MachineScheduleDTO> retainNormalThenSingleCandidates(List<MachineScheduleDTO> singleControlCandidates,
                                                                      List<MachineScheduleDTO> normalCandidates) {
        List<MachineScheduleDTO> retainedCandidates = new ArrayList<>(
                singleControlCandidates.size() + normalCandidates.size());
        retainedCandidates.addAll(normalCandidates);
        retainedCandidates.addAll(singleControlCandidates);
        return retainedCandidates;
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
            return "正规SKU使用单控机台必须L/R整机同步，单边候选已过滤";
        }
        return "SKU类型机台约束";
    }

    /**
     * 输出单控机台候选诊断日志，便于排查不同类型SKU的单控/普通机台回落链路。
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
        log.info("SKU单控候选诊断, materialCode: {}, skuType: {}, surplusQty: {}, smallBatchTotalQtyThreshold: {}, isSmallBatch: {}, "
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
        return LhScheduleConstant.SMALL_BATCH_SKU_THRESHOLD;
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
        // 模套型号硬过滤：普通模具模壳必须命中机台模套型号，仅到货模具时不降级。
        if (!LhMachineHardMatchUtil.isMouldSetPriorityMatched(context, sku, machine)) {
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
     * 解析指定SKU新增选机场景的对齐后参考时间。
     *
     * @param context 排程上下文
     * @param sku 待排SKU
     * @param machine 候选机台
     * @return 与排程窗口首班对齐后的参考时间
     */
    private Date resolveAlignedCandidateReferenceTime(LhScheduleContext context,
                                                      SkuScheduleDTO sku,
                                                      MachineScheduleDTO machine) {
        // 普通机台不消费单控模式快照，只有实际进入单控候选时才校验单模/双模。
        if (machine != null
                && isSingleControlMachine(context, machine.getMachineCode())
                && LhSingleControlMachineUtil.isWholeMachineGranularitySku(context, sku)) {
            MachineScheduleDTO pairMachine = LhSingleControlMachineUtil.resolvePairMachine(context, machine.getMachineCode());
            Date currentSideReferenceTime = resolveAlignedCandidateReferenceTime(context, machine);
            Date pairSideReferenceTime = resolveAlignedCandidateReferenceTime(context, pairMachine);
            // 正规SKU按整机占用单控机台，最早换模起点必须等L/R两边都释放。
            return resolveLaterTime(currentSideReferenceTime, pairSideReferenceTime);
        }
        return resolveAlignedCandidateReferenceTime(context, machine);
    }

    /**
     * 获取两个时间中较晚的一个。
     *
     * @param first 第一个时间
     * @param second 第二个时间
     * @return 较晚时间
     */
    private Date resolveLaterTime(Date first, Date second) {
        if (Objects.isNull(first)) {
            return second;
        }
        if (Objects.isNull(second)) {
            return first;
        }
        return first.after(second) ? first : second;
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
     * 检查SKU模具是否与已占用模具兼容。
     *
     * <p>只要SKU至少有一个模具未被全局占用，就允许候选机台通过初筛；
     * 精确的模具数量分配由 MouldResourceContext.tryAllocate 在后续逐台试算中控制。
     * 只有当SKU所有模具均被占用时才返回false。</p>
     *
     * @param sku 待排SKU
     * @param skuMouldCodes SKU模具列表
     * @param machine 候选机台
     * @param occupiedMouldCodes 已占用模具集合
     * @return true-兼容（至少有一个空闲模具），false-不兼容（所有模具已被占用）
     */
    private boolean isMouldCompatible(SkuScheduleDTO sku, List<String> skuMouldCodes, MachineScheduleDTO machine, Set<String> occupiedMouldCodes) {
        if (skuMouldCodes.isEmpty()) {
            return true;
        }
        // 只要至少有一个模具未被全局占用，就允许候选机台通过；
        // 精确的模数扣减和分配由 MouldResourceContext.tryAllocate 在增机台环节控制。
        int freeMouldCount = 0;
        List<String> occupiedOverlapMouldList = new ArrayList<>(4);
        for (String mouldCode : skuMouldCodes) {
            if (occupiedMouldCodes.contains(mouldCode)) {
                occupiedOverlapMouldList.add(mouldCode);
            } else {
                freeMouldCount++;
            }
        }
        boolean compatible = freeMouldCount > 0;
        if (!compatible) {
            log.info("SKU模具全部被占用, materialCode: {}, SKU模具总数: {}, 全部被占用模具号: {}",
                    sku.getMaterialCode(), skuMouldCodes.size(), skuMouldCodes);
        } else if (!occupiedOverlapMouldList.isEmpty()) {
            log.debug("SKU模具部分占用, materialCode: {}, SKU模具总数: {}, 空闲模具数: {}, 占用重叠模具号: {}",
                    sku.getMaterialCode(), skuMouldCodes.size(), freeMouldCount, occupiedOverlapMouldList);
        }
        return compatible;
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
     * @return 收尾窗口上下文
     */
    private EndingWindowContext sortCandidates(LhScheduleContext context,
                                               List<MachineScheduleDTO> candidates,
                                               SkuScheduleDTO sku) {
        Map<String, CandidateWindowProfile> profileCache = new HashMap<>(16);
        EndingWindowContext endingWindowContext = filterByEarliestEndingWindow(context, candidates, sku, profileCache);
        candidates.sort(buildMachineComparator(context, sku));
        return endingWindowContext;
    }

    /**
     * 构建机台优先级比较器。
     * <p>硬性过滤和同班次窗口筛选已在外层完成，本比较器只保留业务指定的排序层级。</p>
     *
     * @param context 排程上下文
     * @param sku 待排SKU
     * @return 比较器
     */
    private Comparator<MachineScheduleDTO> buildMachineComparator(LhScheduleContext context,
                                                                  SkuScheduleDTO sku) {
        return (left, right) -> {
            int compareResult = compareSingleControlPriority(context, sku, left, right);
            if (compareResult != 0) {
                return compareResult;
            }

            compareResult = compareEmbryoExactMatch(context, sku, left, right);
            if (compareResult != 0) {
                return compareResult;
            }

            // 同模壳优先级高于同规格，避免同一窗口内规格命中机台抢占更匹配模壳能力的机台。
            compareResult = compareMouldShellMatch(context, sku, left, right);
            if (compareResult != 0) {
                return compareResult;
            }

            compareResult = compareSpecExactMatch(sku, left, right);
            if (compareResult != 0) {
                return compareResult;
            }

            compareResult = compareCapsuleAffinity(context, sku, left, right);
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

            return Comparator.nullsLast(String::compareTo).compare(left.getMachineCode(), right.getMachineCode());
        };
    }

    /**
     * 按最早参考收尾时间所在班次生成同班次候选窗口，并移除班次外机台。
     * <p>基准班次 = 所有候选机台中最早的参考收尾时间(referenceTime)所命中的班次；
     * 保留机台收尾时间(referenceTime)落在该班次区间 [班次开始, 班次结束) 内的候选。</p>
     * <p>注意：基准必须取参考收尾时间而非可开产时间(switchStartTime + 换模总时长)。可开产时间会因
     * 晚班不能换模推迟到次日、再叠加换模总时长(默认8h)而跨到与收尾时间不同的班次，若用其定基准再按
     * 收尾时间过滤，会把包括基准机台在内的全部候选过滤为0，导致新增排产SKU数为0。</p>
     *
     * @param context 排程上下文
     * @param candidates 候选机台
     * @param sku 待排SKU
     * @param profileCache 候选机台窗口画像缓存
     * @return 收尾窗口上下文
     */
    private EndingWindowContext filterByEarliestEndingWindow(LhScheduleContext context,
                                                             List<MachineScheduleDTO> candidates,
                                                             SkuScheduleDTO sku,
                                                             Map<String, CandidateWindowProfile> profileCache) {
        int originalCount = PriorityTraceLogHelper.sizeOf(candidates);
        if (CollectionUtils.isEmpty(candidates)) {
            return EndingWindowContext.empty(originalCount);
        }
        // 基准 = 最早参考收尾时间(referenceTime)所在班次；与"收尾时间同班次"语义及选机规则spec保持一致
        Date earliestReferenceTime = resolveEarliestCandidateReferenceTime(context, candidates, sku, profileCache);
        LhShiftConfigVO baseShift = resolveBaseShiftByTime(context, earliestReferenceTime);
        if (Objects.isNull(baseShift) || baseShift.getShiftIndex() == null
                || baseShift.getShiftStartDateTime() == null || baseShift.getShiftEndDateTime() == null) {
            // 最早参考收尾时间未命中任何班次，无法确定同班次基准，保持原有兜底：不筛
            return EndingWindowContext.empty(originalCount);
        }
        // 同班次窗口区间 = 基准班次的 [开始时间, 结束时间)
        Date windowStartTime = baseShift.getShiftStartDateTime();
        Date windowEndTime = baseShift.getShiftEndDateTime();
        int baseShiftIndex = baseShift.getShiftIndex();
        List<MachineScheduleDTO> windowCandidates = new ArrayList<>(candidates.size());
        // 窗口外过滤的单控候选,用于单边粒度SKU候选不足时回补
        List<MachineScheduleDTO> filteredSingleControlCandidates = new ArrayList<>(2);
        for (MachineScheduleDTO candidate : candidates) {
            CandidateWindowProfile profile = resolveCandidateWindowProfile(context, sku, candidate, profileCache);
            // 同班次判定：机台收尾时间落在基准班次区间内
            if (isInEndingWindow(profile.getReferenceTime(), windowStartTime, windowEndTime)) {
                windowCandidates.add(candidate);
            } else if (isSingleControlMachine(context, candidate.getMachineCode())
                    && LhSingleControlMachineUtil.isSingleSideGranularitySku(context, sku)) {
                // 单边粒度SKU的单控候选被窗口过滤,暂存用于候选不足时回补
                filteredSingleControlCandidates.add(candidate);
            }
        }
        // 冻结为单模的SKU使用单控机台时，窗口过滤后若单控候选不足，沿用既有规则回补被过滤的单控候选。
        if (!CollectionUtils.isEmpty(filteredSingleControlCandidates)
                && LhSingleControlMachineUtil.isSingleSideGranularitySku(context, sku)) {
            long windowSingleControlCount = windowCandidates.stream()
                    .filter(machine -> isSingleControlMachine(context, machine.getMachineCode()))
                    .count();
            if (windowSingleControlCount <= 1) {
                windowCandidates.addAll(filteredSingleControlCandidates);
            }
        }
        candidates.clear();
        candidates.addAll(windowCandidates);
        return new EndingWindowContext(windowStartTime, windowEndTime, baseShiftIndex, originalCount,
                Math.max(0, originalCount - windowCandidates.size()));
    }

    /**
     * 解析候选机台中最早的参考收尾时间，作为同班次筛选的基准。
     * <p>参考收尾时间 = 机台已占用结束时间(或释放时间)对齐排程窗口首班后的值；
     * 取所有候选机台中的最小值。基准与过滤口径必须同为收尾时间，避免换模耗时跨班次导致全量过滤。</p>
     *
     * @param context 排程上下文
     * @param candidates 候选机台
     * @param sku 待排SKU
     * @param profileCache 候选机台窗口画像缓存
     * @return 最早参考收尾时间；无任何参考收尾时间时返回 null
     */
    private Date resolveEarliestCandidateReferenceTime(LhScheduleContext context,
                                                      List<MachineScheduleDTO> candidates,
                                                      SkuScheduleDTO sku,
                                                      Map<String, CandidateWindowProfile> profileCache) {
        Date earliest = null;
        for (MachineScheduleDTO candidate : candidates) {
            CandidateWindowProfile profile = resolveCandidateWindowProfile(context, sku, candidate, profileCache);
            Date referenceTime = profile.getReferenceTime();
            if (Objects.isNull(referenceTime)) {
                continue;
            }
            if (Objects.isNull(earliest) || referenceTime.before(earliest)) {
                earliest = referenceTime;
            }
        }
        return earliest;
    }

    /**
     * 根据基准时间定位其所在班次，返回该班次配置。
     * <p>班次命中区间为半开区间 [班次开始, 班次结束)，与 resolveShiftIndex 判定保持一致。</p>
     *
     * @param context 排程上下文
     * @param baseTime 基准时间(最早参考收尾时间)
     * @return 基准班次配置；未命中任何班次时返回 null
     */
    private LhShiftConfigVO resolveBaseShiftByTime(LhScheduleContext context, Date baseTime) {
        if (context == null || baseTime == null
                || CollectionUtils.isEmpty(context.getScheduleWindowShifts())) {
            return null;
        }
        for (LhShiftConfigVO shift : context.getScheduleWindowShifts()) {
            if (shift == null || shift.getShiftIndex() == null
                    || shift.getShiftStartDateTime() == null || shift.getShiftEndDateTime() == null) {
                continue;
            }
            // 半开区间 [start, end)，与 resolveShiftIndex 判定保持一致
            if (!baseTime.before(shift.getShiftStartDateTime())
                    && baseTime.before(shift.getShiftEndDateTime())) {
                return shift;
            }
        }
        return null;
    }

    /**
     * 判断候选机台参考时间是否落在本轮同班次窗口内。
     * <p>窗口区间为半开区间 [班次开始, 班次结束)，与班次命中判定保持一致。</p>
     *
     * @param referenceTime 候选机台参考收尾时间
     * @param windowStartTime 班次开始时间
     * @param windowEndTime 班次结束时间
     * @return true-在窗口内，false-窗口外
     */
    private boolean isInEndingWindow(Date referenceTime, Date windowStartTime, Date windowEndTime) {
        if (Objects.isNull(referenceTime) || Objects.isNull(windowStartTime) || Objects.isNull(windowEndTime)) {
            return false;
        }
        // 半开区间 [班次开始, 班次结束)，班次结束时刻归属下一班次
        return !referenceTime.before(windowStartTime) && referenceTime.before(windowEndTime);
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
     * 比较当天空闲机台优先级。
     *
     * @param leftProfile 左机台画像
     * @param rightProfile 右机台画像
     * @return 比较结果
     */
    private int compareTodayIdlePriority(CandidateWindowProfile leftProfile, CandidateWindowProfile rightProfile) {
        return Integer.compare(leftProfile.getTodayIdleScore(), rightProfile.getTodayIdleScore());
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
        return compareEndingTimeValue(context, leftEndTime, rightEndTime);
    }

    /**
     * 比较两个收尾时间：升序，null 排最后，带容差。
     *
     * @param context 排程上下文
     * @param leftEndTime 左收尾时间
     * @param rightEndTime 右收尾时间
     * @return 比较结果
     */
    private int compareEndingTimeValue(LhScheduleContext context, Date leftEndTime, Date rightEndTime) {
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
     * 比较同胎胚优先级。
     *
     * @param context 排程上下文
     * @param sku 待排SKU
     * @param left 左机台
     * @param right 右机台
     * @return 比较结果
     */
    private int compareEmbryoExactMatch(LhScheduleContext context, SkuScheduleDTO sku,
                                        MachineScheduleDTO left, MachineScheduleDTO right) {
        return Integer.compare(resolveEmbryoMatchScore(context, sku, left),
                resolveEmbryoMatchScore(context, sku, right));
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
     * 比较模壳匹配优先级。
     *
     * @param context 排程上下文
     * @param sku 待排SKU
     * @param left 左机台
     * @param right 右机台
     * @return 比较结果
     */
    private int compareMouldShellMatch(LhScheduleContext context, SkuScheduleDTO sku,
                                       MachineScheduleDTO left, MachineScheduleDTO right) {
        return Integer.compare(resolveMouldShellMatchScore(context, sku, left),
                resolveMouldShellMatchScore(context, sku, right));
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
     * 解析同胎胚得分。
     *
     * @param context 排程上下文
     * @param sku 待排SKU
     * @param machine 候选机台
     * @return 0-同胎胚，1-不同胎胚
     */
    private int resolveEmbryoMatchScore(LhScheduleContext context, SkuScheduleDTO sku, MachineScheduleDTO machine) {
        if (Objects.isNull(context) || Objects.isNull(sku) || Objects.isNull(machine)
                || StringUtils.isEmpty(machine.getPreviousMaterialCode())) {
            return 1;
        }
        MdmMaterialInfo machineMaterialInfo = context.getMaterialInfoMap().get(machine.getPreviousMaterialCode());
        String skuEmbryoCode = normalizeToken(sku.getEmbryoCode());
        String machineEmbryoCode = normalizeToken(Objects.isNull(machineMaterialInfo)
                ? null : machineMaterialInfo.getEmbryoCode());
        if (StringUtils.isNotEmpty(skuEmbryoCode) && StringUtils.equals(skuEmbryoCode, machineEmbryoCode)) {
            return 0;
        }
        String skuEmbryoDesc = normalizeToken(sku.getMainMaterialDesc());
        String machineEmbryoDesc = normalizeToken(Objects.isNull(machineMaterialInfo)
                ? null : machineMaterialInfo.getEmbryoDesc());
        return StringUtils.isNotEmpty(skuEmbryoDesc) && StringUtils.equals(skuEmbryoDesc, machineEmbryoDesc) ? 0 : 1;
    }

    /**
     * 解析模壳匹配得分。
     *
     * @param context 排程上下文
     * @param sku 待排SKU
     * @param machine 候选机台
     * @return 0-模壳匹配或不受模壳限制，1-模壳不匹配
     */
    private int resolveMouldShellMatchScore(LhScheduleContext context, SkuScheduleDTO sku, MachineScheduleDTO machine) {
        return LhMachineHardMatchUtil.isMouldSetPriorityMatched(context, sku, machine) ? 0 : 1;
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
        Date referenceTime = resolveAlignedCandidateReferenceTime(context, sku, machine);
        profile.setReferenceTime(referenceTime);
        boolean hitNoMouldChange = referenceTime != null
                && LhScheduleTimeUtil.isNoMouldChangeTime(context, referenceTime);
        profile.setHitNoMouldChange(hitNoMouldChange);
        Date switchStartTime = resolveCandidateSwitchStartTime(context, referenceTime);
        profile.setSwitchStartTime(switchStartTime);
        profile.setFirstSwitchShiftIndex(resolveShiftIndex(context, switchStartTime));
        profile.setProductionStartTime(resolveCandidateProductionStartTime(context, switchStartTime));
        profile.setReleasedContinuousMachineScore(resolveReleasedContinuousMachineScore(context, sku, machine, referenceTime));
        profile.setOtherSkuOccupiedScore(resolveOtherSkuOccupiedScore(context, sku, machine));
        fillSchedulableShiftMetrics(context, sku, profile);
        profile.setTodayIdleScore(resolveTodayIdleScore(context, sku, machine, profile));
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
    private int resolveReleasedContinuousMachineScore(LhScheduleContext context,
                                                      SkuScheduleDTO sku,
                                                      MachineScheduleDTO machine,
                                                      Date referenceTime) {
        if (context == null || machine == null || StringUtils.isEmpty(machine.getMachineCode())
                || CollectionUtils.isEmpty(context.getReleasedContinuousMachineCodeSet())) {
            return 0;
        }
        if (!context.getReleasedContinuousMachineCodeSet().contains(machine.getMachineCode())) {
            return 0;
        }
        // T日释放机台优先承接该业务日有正日计划且单段模数明确的SKU，避免其它SKU提前抢占释放窗口。
        return hasPositivePlanOnReferenceDate(sku, referenceTime)
                && StringUtils.isNotEmpty(resolveSingleMouldChangeSegment(sku.getMouldChangeInfo())) ? 0 : 1;
    }

    /**
     * 判断SKU在候选释放机台参考业务日是否存在正日计划。
     *
     * @param sku 待排SKU
     * @param referenceTime 候选机台参考时间
     * @return true-参考日有正日计划
     */
    private boolean hasPositivePlanOnReferenceDate(SkuScheduleDTO sku, Date referenceTime) {
        if (sku == null || referenceTime == null || CollectionUtils.isEmpty(sku.getDailyPlanQuotaMap())) {
            return false;
        }
        LocalDate productionDate = referenceTime.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
        SkuDailyPlanQuotaDTO quota = sku.getDailyPlanQuotaMap().get(productionDate);
        return quota != null && quota.getDayPlanQty() > 0;
    }

    /**
     * 解析单段模具变化信息。
     *
     * @param mouldChangeInfo 模具变化信息
     * @return 单段模数，多段或空值返回空
     */
    private String resolveSingleMouldChangeSegment(String mouldChangeInfo) {
        if (StringUtils.isEmpty(mouldChangeInfo)) {
            return null;
        }
        String[] segments = mouldChangeInfo.split("-");
        if (segments.length != 1) {
            return null;
        }
        return StringUtils.trim(segments[0]);
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
        List<LhScheduleResult> assignedResults = CollectionUtils.isEmpty(context.getMachineAssignmentMap())
                ? null : context.getMachineAssignmentMap().get(machine.getMachineCode());
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
     * 解析当天空闲机台优先得分。
     * <p>仅在当前 SKU 首日确实需要排产时生效；当天无有效占用且可首班承接的机台优先。</p>
     *
     * @param context 排程上下文
     * @param sku 待排 SKU
     * @param machine 候选机台
     * @param profile 候选机台窗口画像
     * @return 0-当天空闲且可首班承接，1-非当天空闲
     */
    private int resolveTodayIdleScore(LhScheduleContext context,
                                      SkuScheduleDTO sku,
                                      MachineScheduleDTO machine,
                                      CandidateWindowProfile profile) {
        if (!isTodayIdleMachinePriorityEnabled(context)
                || !isSkuNeedScheduleOnFirstDay(context, sku)
                || context == null || machine == null || profile == null
                || StringUtils.isEmpty(machine.getMachineCode())) {
            return 1;
        }
        List<LhScheduleResult> assignedResults = CollectionUtils.isEmpty(context.getMachineAssignmentMap())
                ? null : context.getMachineAssignmentMap().get(machine.getMachineCode());
        if (!CollectionUtils.isEmpty(assignedResults)) {
            for (LhScheduleResult assignedResult : assignedResults) {
                if (!shouldIgnoreReleasedContinuousPlaceholder(context, assignedResult)) {
                    return 1;
                }
            }
        }
        Date referenceTime = profile.getReferenceTime();
        if (referenceTime == null || CollectionUtils.isEmpty(context.getScheduleWindowShifts())) {
            return 1;
        }
        Date windowStartTime = context.getScheduleWindowShifts().get(0).getShiftStartDateTime();
        if (windowStartTime == null || referenceTime.after(windowStartTime)) {
            return 1;
        }
        return 0;
    }

    /**
     * 判断当天空闲机台优先规则是否启用。
     *
     * @param context 排程上下文
     * @return true-启用
     */
    private boolean isTodayIdleMachinePriorityEnabled(LhScheduleContext context) {
        return context != null && context.getParamIntValue(
                LhScheduleParamConstant.ENABLE_TODAY_IDLE_MACHINE_PRIORITY,
                LhScheduleConstant.ENABLE_TODAY_IDLE_MACHINE_PRIORITY) == 1;
    }

    /**
     * 判断 SKU 是否需要在窗口首日排产。
     *
     * @param context 排程上下文
     * @param sku 待排 SKU
     * @return true-首日需要排产
     */
    private boolean isSkuNeedScheduleOnFirstDay(LhScheduleContext context, SkuScheduleDTO sku) {
        if (context == null || sku == null) {
            return false;
        }
        LocalDate firstShiftDate = resolveFirstShiftDate(context);
        if (firstShiftDate != null && !CollectionUtils.isEmpty(sku.getDailyPlanQuotaMap())) {
            SkuDailyPlanQuotaDTO quota = sku.getDailyPlanQuotaMap().get(firstShiftDate);
            if (quota != null && (quota.getDayPlanQty() > 0 || quota.getRemainingQty() > 0)) {
                return true;
            }
        }
        if (sku.getDailyPlanQty() > 0) {
            return true;
        }
        if (sku.isContinuousCompensationSku()
                && Boolean.TRUE.equals(context.getNewSpecEarlyProductionAllowedMap().get(sku))) {
            // 续作补偿提前生产准入通过时，选机画像按窗口首日排产处理，但不改变SKU队列顺序。
            return true;
        }
        if (sku.getEffectiveCarryForwardQty() > 0 || sku.getMonthlyHistoryShortageQty() > 0) {
            return true;
        }
        int targetQty = sku.getRemainingScheduleQty() > 0
                ? sku.getRemainingScheduleQty() : sku.resolveTargetScheduleQty();
        return targetQty > 0 && StringUtils.equals(SkuTagEnum.ENDING.getCode(), sku.getSkuTag());
    }

    /**
     * 解析排程窗口首班业务日期。
     *
     * @param context 排程上下文
     * @return 首班业务日期
     */
    private LocalDate resolveFirstShiftDate(LhScheduleContext context) {
        if (context == null || CollectionUtils.isEmpty(context.getScheduleWindowShifts())) {
            return null;
        }
        LhShiftConfigVO firstShift = context.getScheduleWindowShifts().get(0);
        if (firstShift == null || firstShift.getWorkDate() == null) {
            return null;
        }
        return firstShift.getWorkDate().toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
    }

    /**
     * 解析指定时间命中的班次序号。
     *
     * @param context 排程上下文
     * @param date 时间
     * @return 班次序号，未命中时返回窗口外默认值
     */
    private int resolveShiftIndex(LhScheduleContext context, Date date) {
        if (context == null || date == null || CollectionUtils.isEmpty(context.getScheduleWindowShifts())) {
            return LhScheduleConstant.MAX_SHIFT_SLOT_COUNT + 1;
        }
        for (LhShiftConfigVO shift : context.getScheduleWindowShifts()) {
            if (shift == null || shift.getShiftIndex() == null
                    || shift.getShiftStartDateTime() == null || shift.getShiftEndDateTime() == null) {
                continue;
            }
            if (!date.before(shift.getShiftStartDateTime()) && date.before(shift.getShiftEndDateTime())) {
                return shift.getShiftIndex();
            }
        }
        return LhScheduleConstant.MAX_SHIFT_SLOT_COUNT + 1;
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
                                        List<MachineScheduleDTO> candidates,
                                        MachineFilterTrace trace,
                                        EndingWindowContext endingWindowContext) {
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
        PriorityTraceLogHelper.appendLine(detailBuilder,
                PriorityTraceLogHelper.kv("同班次基准班次序号", endingWindowContext.getBaseShiftIndex())
                        + ", " + PriorityTraceLogHelper.kv("同班次窗口起点(班次开始)", PriorityTraceLogHelper.formatDateTime(
                        endingWindowContext.getWindowStartTime()))
                        + ", " + PriorityTraceLogHelper.kv("同班次窗口截止(班次结束)", PriorityTraceLogHelper.formatDateTime(
                        endingWindowContext.getWindowEndTime()))
                        + ", " + PriorityTraceLogHelper.kv("窗口筛选前候选数", endingWindowContext.getOriginalCount())
                        + ", " + PriorityTraceLogHelper.kv("窗口外过滤数", endingWindowContext.getFilteredCount()));
        if (!CollectionUtils.isEmpty(trace.filteredMachineMessages)) {
            PriorityTraceLogHelper.appendLine(detailBuilder,
                    "过滤明细: " + String.join("; ", trace.filteredMachineMessages));
        }
        if (!matchResult.isSpecial()) {
            PriorityTraceLogHelper.appendLine(detailBuilder,
                    "候选说明: 普通SKU允许使用特殊机台，特殊机台仅保留候选诊断，不作为新增选机排序层级");
        }
        if (!CollectionUtils.isEmpty(context.getReleasedContinuousMachineCodeSet())) {
            PriorityTraceLogHelper.appendLine(detailBuilder,
                    "候选说明: 续作释放机台保留候选诊断，不作为新增选机排序层级");
        }

        // TOP N 候选机台
        int topN = LhScheduleConstant.MACHINE_SORT_TRACE_TOP_N;
        int topCount = Math.min(topN, PriorityTraceLogHelper.sizeOf(candidates));
        PriorityTraceLogHelper.appendLine(detailBuilder, "TOP" + topCount + "候选排序:");
        List<String> levelNames = java.util.Arrays.asList(
                "L1_单控拆分", "L2_同胎胚", "L3_同模壳", "L4_同规格",
                "L5_胶囊共用", "L6_同英寸", "L7_相近英寸", "L8_机台编码");
        for (int i = 0; i < topCount; i++) {
            MachineScheduleDTO machine = candidates.get(i);
            CandidateWindowProfile profile = resolveCandidateWindowProfile(context, sku, machine, profileCache);
            int specifyScore = resolveLimitSpecifyScore(context, sku, machine);
            int singleCtrlScore = resolveSingleControlScore(context, sku, machine);
            int normalMachineScore = resolveNormalMachinePriorityValue(matchResult, machine);
            int specialSupportCapabilityCount = resolveSpecialSupportCapabilityCount(machine);
            boolean inEndingWindow = isInEndingWindow(profile.getReferenceTime(),
                    endingWindowContext.getWindowStartTime(), endingWindowContext.getWindowEndTime());
            int embryoMatchScore = resolveEmbryoMatchScore(context, sku, machine);
            int specMatchScore = resolveSpecMatchScore(sku, machine);
            int mouldShellMatchScore = resolveMouldShellMatchScore(context, sku, machine);
            int proSizeMatchScore = resolveProSizeMatchScore(sku, machine);
            double inchDistance = resolveInchDistance(sku, machine);
            int capsuleScore = resolveCapsuleAffinityScore(context, sku, machine);
            int embryoShareCount = resolveEmbryoShareCount(context, machine);
            int machineCodeScore = StringUtils.isEmpty(machine.getMachineCode()) ? 1 : 0;
            boolean inchMatched = LhMachineHardMatchUtil.isInchInRange(
                    parseInch(sku.getProSize()), machine.getDimensionMinimum(), machine.getDimensionMaximum());
            boolean mouldSetMatched = mouldShellMatchScore == 0;
            boolean specialMatched = LhMachineHardMatchUtil.isSpecialMaterialSupported(matchResult, machine);
            boolean specialSupportMachine = !LhMachineHardMatchUtil.isNormalMachine(machine);
            String skuShellStandard = resolveSkuShellStandardDisplay(context, sku);
            boolean skuNeedScheduleOnFirstDay = isSkuNeedScheduleOnFirstDay(context, sku);

            List<String> sortKeyLevels = java.util.Arrays.asList(
                    "L1_单控拆分=" + singleCtrlScore,
                    "L2_同胎胚=" + embryoMatchScore,
                    "L3_同模壳=" + mouldShellMatchScore,
                    "L4_同规格=" + specMatchScore,
                    "L5_胶囊共用=" + capsuleScore,
                    "L6_同英寸=" + proSizeMatchScore,
                    "L7_相近英寸=" + formatInchDistance(inchDistance),
                    "L8_机台编码=" + machine.getMachineCode());
            List<Integer> scores = java.util.Arrays.asList(
                    singleCtrlScore,
                    embryoMatchScore,
                    mouldShellMatchScore,
                    specMatchScore,
                    capsuleScore,
                    proSizeMatchScore,
                    safeInchDistanceScore(inchDistance),
                    machineCodeScore);
            List<Integer> defaultScores = java.util.Arrays.asList(
                    SINGLE_CONTROL_NORMAL_MACHINE_SCORE, 1, 1, 1, 1, 1, 0, 0);
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
                            + ", " + PriorityTraceLogHelper.kv("当天需排产", PriorityTraceLogHelper.oneZero(skuNeedScheduleOnFirstDay))
                            + ", " + PriorityTraceLogHelper.kv("入收尾窗口", PriorityTraceLogHelper.oneZero(inEndingWindow))
                            + ", " + PriorityTraceLogHelper.kv("当天空闲", PriorityTraceLogHelper.oneZero(profile.getTodayIdleScore() == 0))
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
                            + ", " + PriorityTraceLogHelper.kv("最早可换模班次", profile.getFirstSwitchShiftIndex())
                            + ", " + PriorityTraceLogHelper.kv("最早可开产时间", PriorityTraceLogHelper.formatDateTime(profile.getProductionStartTime()))
                            + ", " + PriorityTraceLogHelper.kv("最早可开产班次", profile.getFirstProductionShiftIndex())
                            + ", " + PriorityTraceLogHelper.kv("连续可生产班次数", profile.getContinuousSchedulableShiftCount())
                            + ", " + PriorityTraceLogHelper.kv("可用总产能", profile.getAvailableCapacityQty())
                            + ", " + PriorityTraceLogHelper.kv("被其他SKU占用", PriorityTraceLogHelper.oneZero(profile.getOtherSkuOccupiedScore() > 0))
                            + ", " + PriorityTraceLogHelper.kv("尾部零散产能", PriorityTraceLogHelper.oneZero(profile.getTailFragmentScore() > 0))
                            + ", " + PriorityTraceLogHelper.kv("需要换模", "1")
                            + ", " + PriorityTraceLogHelper.kv("命中晚班不能换模", PriorityTraceLogHelper.oneZero(profile.isHitNoMouldChange()))
                            + ", " + PriorityTraceLogHelper.kv("收尾时间", PriorityTraceLogHelper.formatDateTime(machine.getEstimatedEndTime()))
                            + ", " + PriorityTraceLogHelper.kv("同胎胚", PriorityTraceLogHelper.oneZero(embryoMatchScore == 0))
                            + ", " + PriorityTraceLogHelper.kv("同规格", PriorityTraceLogHelper.oneZero(specMatchScore == 0))
                            + ", " + PriorityTraceLogHelper.kv("同模壳", PriorityTraceLogHelper.oneZero(mouldShellMatchScore == 0))
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
            String selectReason = resolveMachineSelectReason(context, sku, best);
            PriorityTraceLogHelper.appendLine(detailBuilder,
                    "最终选中机台: " + best.getMachineCode()
                            + ", 选中原因: " + selectReason);
        }
        PriorityTraceLogHelper.appendTitleFooter(detailBuilder);
        String detail = detailBuilder.toString().trim();
        PriorityTraceLogHelper.logSortSummary(log, context, title, detail);
    }

    /**
     * 输出续作排产后全量启用机台排序日志（不依赖具体SKU）。
     * <p>排除续作排满机台（续作机台且非收尾且未释放），保留续作收尾机台、续作释放机台和非续作机台；
     * 排序规则：L1单控优先 -> L2收尾时间(升序,null末位) -> L3普通机台优先 -> L4特殊支持能力数(升序)。</p>
     *
     * @param context 排程上下文
     */
    @Override
    public void traceEnabledMachineSort(LhScheduleContext context) {
        if (!PriorityTraceLogHelper.isEnabled(context)) {
            return;
        }
        Map<String, MachineScheduleDTO> machineScheduleMap = context.getMachineScheduleMap();
        if (CollectionUtils.isEmpty(machineScheduleMap)) {
            return;
        }
        // 续作机台集合：按续作SKU的续作机台编码构建，用于识别续作排满/收尾机台。
        Set<String> continuousMachineCodeSet = resolveContinuousMachineCodeSet(context);
        // 已释放续作机台集合：窗口无计划/收尾小余量释放后视为可用机台，不计入续作排满。
        Set<String> releasedMachineCodeSet = resolveReleasedContinuousMachineCodeSet(context);

        int enabledCount = 0;
        int excludedFullContinuousCount = 0;
        List<MachineScheduleDTO> sortMachines = new ArrayList<>(machineScheduleMap.size());
        for (MachineScheduleDTO machine : machineScheduleMap.values()) {
            if (machine == null || !MachineStatusUtil.isEnabled(machine.getStatus())) {
                continue;
            }
            enabledCount++;
            // 续作排满：续作机台且非收尾且未释放，窗口已被续作占满，排除出排序。
            if (continuousMachineCodeSet.contains(machine.getMachineCode())
                    && !machine.isEnding()
                    && !releasedMachineCodeSet.contains(machine.getMachineCode())) {
                excludedFullContinuousCount++;
                continue;
            }
            sortMachines.add(machine);
        }
        sortMachines.sort(buildStandaloneMachineComparator(context));

        String title = "机台排序优先级汇总【续作后全量启用机台】";
        StringBuilder detailBuilder = new StringBuilder(1024);
        PriorityTraceLogHelper.appendTitleHeader(detailBuilder, title);
        PriorityTraceLogHelper.appendLine(detailBuilder,
                PriorityTraceLogHelper.kv("排程日期", PriorityTraceLogHelper.formatDateTime(context.getScheduleDate()))
                        + ", " + PriorityTraceLogHelper.kv("工厂", context.getFactoryCode())
                        + ", " + PriorityTraceLogHelper.kv("启用机台总数", enabledCount)
                        + ", " + PriorityTraceLogHelper.kv("排除续作排满数", excludedFullContinuousCount)
                        + ", " + PriorityTraceLogHelper.kv("参与排序数", sortMachines.size()));
        PriorityTraceLogHelper.appendLine(detailBuilder,
                "排序规则: L1单控优先 -> L2收尾时间(升序,null末位) -> L3普通机台优先 -> L4特殊支持能力数(升序)");
        List<String> levelNames = java.util.Arrays.asList(
                "L1_单控优先", "L2_收尾时间", "L3_普通机台优先", "L4_特殊支持能力数量");
        for (int i = 0; i < sortMachines.size(); i++) {
            MachineScheduleDTO machine = sortMachines.get(i);
            boolean isSingleCtrl = isSingleControlMachine(context, machine.getMachineCode());
            int normalMachinePriority = LhMachineHardMatchUtil.resolveNormalMachinePriority(machine);
            int specialSupportCapabilityCount = resolveSpecialSupportCapabilityCount(machine);
            List<String> sortKeyLevels = java.util.Arrays.asList(
                    "L1_单控优先=" + (isSingleCtrl ? 1 : 0),
                    "L2_收尾时间=" + PriorityTraceLogHelper.formatDateTime(machine.getEstimatedEndTime()),
                    "L3_普通机台优先=" + (normalMachinePriority == 0 ? 1 : 0),
                    "L4_特殊支持能力数量=" + specialSupportCapabilityCount);
            List<Integer> scores = java.util.Arrays.asList(
                    isSingleCtrl ? 0 : 1,
                    resolveEndingTimeScore(machine),
                    normalMachinePriority,
                    specialSupportCapabilityCount);
            List<Integer> defaultScores = java.util.Arrays.asList(1, 0, 0, 0);
            String sortKey = PriorityTraceLogHelper.formatSortKey(sortKeyLevels);
            String hitLevel = PriorityTraceLogHelper.resolveHitLevel(levelNames, scores, defaultScores);
            PriorityTraceLogHelper.appendLine(detailBuilder,
                    (i + 1)
                            + ". " + PriorityTraceLogHelper.kv("机台", machine.getMachineCode())
                            + ", " + PriorityTraceLogHelper.kv("名称", machine.getMachineName())
                            + ", " + PriorityTraceLogHelper.kv("单控", PriorityTraceLogHelper.oneZero(isSingleCtrl))
                            + ", " + PriorityTraceLogHelper.kv("普通机台", PriorityTraceLogHelper.oneZero(normalMachinePriority == 0))
                            + ", " + PriorityTraceLogHelper.kv("特殊支持能力数量", specialSupportCapabilityCount)
                            + ", " + PriorityTraceLogHelper.kv("收尾时间", PriorityTraceLogHelper.formatDateTime(machine.getEstimatedEndTime()))
                            + ", " + PriorityTraceLogHelper.kv("续作状态", resolveContinuousStateDesc(machine, continuousMachineCodeSet, releasedMachineCodeSet))
                            + ", " + PriorityTraceLogHelper.kv("机台顺序", machine.getMachineOrder())
                            + ", " + PriorityTraceLogHelper.kv("SortKey", sortKey)
                            + ", " + PriorityTraceLogHelper.kv("HitLevel", hitLevel));
        }
        PriorityTraceLogHelper.appendTitleFooter(detailBuilder);
        String detail = detailBuilder.toString().trim();
        PriorityTraceLogHelper.logSortSummary(log, context, title, detail);
    }

    /**
     * 构建全量启用机台排序比较器（不依赖SKU）。
     *
     * @param context 排程上下文
     * @return 机台比较器
     */
    private Comparator<MachineScheduleDTO> buildStandaloneMachineComparator(LhScheduleContext context) {
        return (left, right) -> {
            // L1 单控优先：单控机台排前
            int compareResult = Integer.compare(resolveStandaloneSingleControlScore(context, left),
                    resolveStandaloneSingleControlScore(context, right));
            if (compareResult != 0) {
                return compareResult;
            }
            // L2 收尾时间升序，null排最后
            compareResult = compareEndingTimeValue(context, left.getEstimatedEndTime(), right.getEstimatedEndTime());
            if (compareResult != 0) {
                return compareResult;
            }
            // L3 普通机台优先：普通=0，特殊支持=1
            compareResult = Integer.compare(LhMachineHardMatchUtil.resolveNormalMachinePriority(left),
                    LhMachineHardMatchUtil.resolveNormalMachinePriority(right));
            if (compareResult != 0) {
                return compareResult;
            }
            // L4 特殊支持能力数升序（越少越优先）
            compareResult = Integer.compare(resolveSpecialSupportCapabilityCount(left),
                    resolveSpecialSupportCapabilityCount(right));
            if (compareResult != 0) {
                return compareResult;
            }
            // 兜底：机台顺序 -> 机台编码
            compareResult = Integer.compare(left.getMachineOrder(), right.getMachineOrder());
            if (compareResult != 0) {
                return compareResult;
            }
            return Comparator.nullsLast(String::compareTo).compare(left.getMachineCode(), right.getMachineCode());
        };
    }

    /**
     * 解析全量排序场景下单控机台得分：单控=0，普通=1。
     *
     * @param context 排程上下文
     * @param machine 机台
     * @return 单控得分
     */
    private int resolveStandaloneSingleControlScore(LhScheduleContext context, MachineScheduleDTO machine) {
        return isSingleControlMachine(context, machine.getMachineCode()) ? 0 : 1;
    }

    /**
     * 构建续作机台编码集合。
     *
     * @param context 排程上下文
     * @return 续作机台编码集合
     */
    private Set<String> resolveContinuousMachineCodeSet(LhScheduleContext context) {
        List<SkuScheduleDTO> continuousSkuList = context.getContinuousSkuList();
        if (CollectionUtils.isEmpty(continuousSkuList)) {
            return java.util.Collections.emptySet();
        }
        Set<String> machineCodeSet = new HashSet<>(continuousSkuList.size());
        for (SkuScheduleDTO sku : continuousSkuList) {
            if (sku != null && StringUtils.isNotEmpty(sku.getContinuousMachineCode())) {
                machineCodeSet.add(sku.getContinuousMachineCode());
            }
        }
        return machineCodeSet;
    }

    /**
     * 构建已释放续作机台编码集合（窗口无计划/首日无计划/换活字块释放）。
     *
     * @param context 排程上下文
     * @return 已释放续作机台编码集合
     */
    private Set<String> resolveReleasedContinuousMachineCodeSet(LhScheduleContext context) {
        Set<String> releasedSet = new HashSet<>(16);
        if (!CollectionUtils.isEmpty(context.getReleasedContinuousMachineCodeSet())) {
            releasedSet.addAll(context.getReleasedContinuousMachineCodeSet());
        }
        if (!CollectionUtils.isEmpty(context.getFirstDayNoPlanReleasedContinuousMachineCodeSet())) {
            releasedSet.addAll(context.getFirstDayNoPlanReleasedContinuousMachineCodeSet());
        }
        if (!CollectionUtils.isEmpty(context.getTypeBlockReleasedContinuousMachineCodeSet())) {
            releasedSet.addAll(context.getTypeBlockReleasedContinuousMachineCodeSet());
        }
        return releasedSet;
    }

    /**
     * 解析机台续作状态描述，供日志展示。
     *
     * @param machine 机台
     * @param continuousMachineCodeSet 续作机台编码集合
     * @param releasedMachineCodeSet 已释放续作机台编码集合
     * @return 续作状态描述
     */
    private String resolveContinuousStateDesc(MachineScheduleDTO machine, Set<String> continuousMachineCodeSet,
                                              Set<String> releasedMachineCodeSet) {
        if (!continuousMachineCodeSet.contains(machine.getMachineCode())) {
            return "非续作";
        }
        if (releasedMachineCodeSet.contains(machine.getMachineCode())) {
            return "续作释放";
        }
        // 续作排满机台已在筛选阶段剔除，此处保留的续作机台即续作收尾。
        return machine.isEnding() ? "续作收尾" : "续作在产";
    }

    /**
     * 解析机台选中原因。
     *
     * @param context 排程上下文
     * @param sku 待排SKU
     * @param machine 选中机台
     * @return 选中原因
     */
    private String resolveMachineSelectReason(LhScheduleContext context, SkuScheduleDTO sku,
                                              MachineScheduleDTO machine) {
        List<String> reasons = new ArrayList<>(4);
        if (resolveSingleControlScore(context, sku, machine) < SINGLE_CONTROL_NORMAL_MACHINE_SCORE) {
            reasons.add("单控拆分优先");
        }
        if (resolveEmbryoMatchScore(context, sku, machine) == 0) {
            reasons.add("同胎胚");
        }
        if (resolveMouldShellMatchScore(context, sku, machine) == 0) {
            reasons.add("同模壳");
        }
        if (resolveSpecMatchScore(sku, machine) == 0) {
            reasons.add("同规格");
        }
        if (resolveCapsuleAffinityScore(context, sku, machine) == 0) {
            reasons.add("胶囊共用");
        }
        if (resolveProSizeMatchScore(sku, machine) == 0) {
            reasons.add("同英寸");
        } else if (resolveInchDistance(sku, machine) < Double.MAX_VALUE) {
            reasons.add("相近英寸");
        }
        if (reasons.isEmpty()) {
            reasons.add("机台编码兜底");
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
     * 新增选机同班次窗口上下文。
     * <p>窗口区间即基准班次的 [班次开始, 班次结束)，windowStartTime/windowEndTime 分别对应班次起止时间。</p>
     */
    private static class EndingWindowContext {
        /** 同班次筛选基准班次序号 */
        private final int baseShiftIndex;
        /** 班次开始时间(窗口起点) */
        private final Date windowStartTime;
        /** 班次结束时间(窗口截止) */
        private final Date windowEndTime;
        /** 窗口筛选前候选数 */
        private final int originalCount;
        /** 窗口外过滤数 */
        private final int filteredCount;

        private EndingWindowContext(Date windowStartTime, Date windowEndTime, int baseShiftIndex,
                                    int originalCount, int filteredCount) {
            this.windowStartTime = windowStartTime;
            this.windowEndTime = windowEndTime;
            this.baseShiftIndex = baseShiftIndex;
            this.originalCount = originalCount;
            this.filteredCount = filteredCount;
        }

        private static EndingWindowContext empty(int originalCount) {
            return new EndingWindowContext(null, null, LhScheduleConstant.MAX_SHIFT_SLOT_COUNT + 1, originalCount, 0);
        }

        private int getBaseShiftIndex() {
            return baseShiftIndex;
        }

        private Date getWindowStartTime() {
            return windowStartTime;
        }

        private Date getWindowEndTime() {
            return windowEndTime;
        }

        private int getOriginalCount() {
            return originalCount;
        }

        private int getFilteredCount() {
            return filteredCount;
        }
    }

    /**
     * 候选机台生产窗口画像。
     */
    private static class CandidateWindowProfile {
        /** 参考收尾时间 */
        private Date referenceTime;
        /** 最早可换模时间 */
        private Date switchStartTime;
        /** 最早可换模班次 */
        private int firstSwitchShiftIndex = LhScheduleConstant.MAX_SHIFT_SLOT_COUNT + 1;
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
        /** 当天空闲且可首班承接得分，0=当天空闲可首班承接，1=非当天空闲机台 */
        private int todayIdleScore = 1;
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

        private int getFirstSwitchShiftIndex() {
            return firstSwitchShiftIndex;
        }

        private void setFirstSwitchShiftIndex(int firstSwitchShiftIndex) {
            this.firstSwitchShiftIndex = firstSwitchShiftIndex;
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

        private int getTodayIdleScore() {
            return todayIdleScore;
        }

        private void setTodayIdleScore(int todayIdleScore) {
            this.todayIdleScore = todayIdleScore;
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
