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
import com.zlt.aps.lh.util.LhSingleControlMachineUtil;
import com.zlt.aps.lh.util.LhSpecialMaterialUtil;
import com.zlt.aps.lh.util.LhSpecifyMachineUtil;
import com.zlt.aps.lh.util.MachineStatusUtil;
import com.zlt.aps.lh.util.LhScheduleTimeUtil;
import com.zlt.aps.lh.util.PriorityTraceLogHelper;
import com.zlt.aps.mdm.api.domain.entity.MdmDevicePlanShut;
import com.zlt.aps.mdm.api.domain.entity.MdmMaterialInfo;
import com.zlt.aps.mdm.api.domain.entity.MdmSkuMouldRel;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 默认机台匹配策略实现
 * <p>基于收尾时间、规格、英寸、胶囊共用性和胎胚共用性进行多层级匹配排序</p>
 *
 * @author APS
 */
@Slf4j
@Component
public class DefaultMachineMatchStrategy implements IMachineMatchStrategy {

    /** 每小时毫秒数 */
    private static final long MILLIS_PER_HOUR = 60L * 60L * 1000L;

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

        // 4. 过滤候选机台：状态启用 + 硬性指标匹配 + 模具未被占用
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

        candidates = applySingleControlReservationRule(context, sku, candidates);

        // 5. 按多维度排序
        sortCandidates(context, candidates, sku, specialMaterialMatchResult);
        traceMachineCandidates(context, sku, specialMaterialMatchResult, candidates, trace);

        if (CollectionUtils.isEmpty(candidates)) {
            log.warn("SKU候选机台为空, materialCode: {}, 规格: {}, 寸口: {}, 特殊分类: {}, 机台总数: {}, 不可作业过滤: {}, 禁用过滤: {}, 超时停机过滤: {}, 寸口过滤: {}, 模套过滤: {}, 特殊支持过滤: {}, 模具过滤: {}, 限制作业优先机台: {}",
                    sku.getMaterialCode(), sku.getSpecCode(), sku.getProSize(),
                    specialMaterialMatchResult.getCategoryDisplayText(), trace.totalMachineCount,
                    trace.notAllowedMachineFilteredCount,
                    trace.disabledCount, trace.stopTimeoutCount, trace.inchMismatchCount,
                    trace.mouldSetMismatchCount, trace.resolveSpecialSupportFilteredCount(),
                    trace.mouldConflictCount, limitSpecifyMachineCodes);
        }
        log.info("SKU可用机台匹配完成, materialCode: {}, special: {}, category: {}, 候选机台数: {}",
                sku.getMaterialCode(), specialMaterialMatchResult.isSpecial(),
                specialMaterialMatchResult.getCategoryDisplayText(), candidates.size());
        return candidates;
    }

    /**
     * 对单控拆分机台执行试制保留规则。
     * <p>试制/量试、小批量 SKU 只要命中单控候选，就应继续保留单控机台，
     * 由后续排序优先选择单控；这样在单控空闲时可直接占用，在单控被占用时也会优先等待单控收尾。
     * 普通 SKU 默认剔除单控候选，避免在普通机台失败后回落占用试制保留机台。
     * 若普通 SKU 存在限制作业定点配置，则仍保留原候选。</p>
     *
     * @param context 排程上下文
     * @param sku 待排SKU
     * @param candidates 原候选机台
     * @return 处理后的候选机台
     */
    private List<MachineScheduleDTO> applySingleControlReservationRule(LhScheduleContext context,
                                                                       SkuScheduleDTO sku,
                                                                       List<MachineScheduleDTO> candidates) {
        if (CollectionUtils.isEmpty(candidates) || sku == null) {
            return candidates;
        }
        List<MachineScheduleDTO> singleControlCandidates = new ArrayList<>(2);
        List<MachineScheduleDTO> normalCandidates = new ArrayList<>(candidates.size());
        for (MachineScheduleDTO candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            if (LhSingleControlMachineUtil.isSingleMouldMachine(candidate.getMachineCode())) {
                singleControlCandidates.add(candidate);
                continue;
            }
            normalCandidates.add(candidate);
        }
        if (CollectionUtils.isEmpty(singleControlCandidates)) {
            return candidates;
        }
        if (shouldReserveSingleControlForTrialSku(sku)) {
            return candidates;
        }
        if (LhSpecifyMachineUtil.hasLimitSpecifyMachine(context, sku.getMaterialCode())
                || CollectionUtils.isEmpty(normalCandidates)) {
            return candidates;
        }
        return normalCandidates;
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
        if (sku.isTrial() || sku.isSmallBatchValidation()) {
            return true;
        }
        return StringUtils.equals(ConstructionStageEnum.TRIAL.getCode(), sku.getConstructionStage())
                || StringUtils.equals(ConstructionStageEnum.MASS_TRIAL.getCode(), sku.getConstructionStage());
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
                if (result.getMouldCode() != null) {
                    occupied.add(result.getMouldCode());
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
        Date candidateReferenceTime = resolveCandidateReferenceTime(context, machine);
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
        if (machine.getEstimatedEndTime() != null) {
            return machine.getEstimatedEndTime();
        }
        if (context.getScheduleDate() != null) {
            return context.getScheduleDate();
        }
        return context.getScheduleTargetDate();
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
        return (left, right) -> {
            int compareResult = compareLimitSpecifyPriority(context, sku, left, right);
            if (compareResult != 0) {
                return compareResult;
            }

            compareResult = compareSingleControlPriority(context, sku, left, right);
            if (compareResult != 0) {
                return compareResult;
            }

            compareResult = compareNormalMachinePriority(matchResult, left, right);
            if (compareResult != 0) {
                return compareResult;
            }

            compareResult = compareEndingTime(context, left, right);
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
     * @return 试制/小批量单控优先，普通SKU单控靠后
     */
    private int resolveSingleControlScore(LhScheduleContext context, SkuScheduleDTO sku, MachineScheduleDTO machine) {
        if (Objects.isNull(machine)
                || !LhSingleControlMachineUtil.isSingleMouldMachine(machine.getMachineCode())) {
            return 1;
        }
        if (shouldReserveSingleControlForTrialSku(sku)) {
            return 0;
        }
        return 2;
    }

    /**
     * 比较收尾时间优先级。
     *
     * @param context 排程上下文
     * @param left 左机台
     * @param right 右机台
     * @return 比较结果
     */
    private int compareEndingTime(LhScheduleContext context, MachineScheduleDTO left, MachineScheduleDTO right) {
        Date leftEndTime = left.getEstimatedEndTime();
        Date rightEndTime = right.getEstimatedEndTime();
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
        String title = "机台排序优先级汇总【新增排产选机台】";
        StringBuilder detailBuilder = new StringBuilder(1024);
        PriorityTraceLogHelper.appendTitleHeader(detailBuilder, title);
        PriorityTraceLogHelper.appendLine(detailBuilder,
                PriorityTraceLogHelper.kv("排程日期", PriorityTraceLogHelper.formatDateTime(context.getScheduleDate()))
                        + ", " + PriorityTraceLogHelper.kv("SKU", sku.getMaterialCode())
                        + ", " + PriorityTraceLogHelper.kv("规格", sku.getSpecCode())
                        + ", " + PriorityTraceLogHelper.kv("寸口", sku.getProSize())
                        + ", " + PriorityTraceLogHelper.kv("特殊物料", PriorityTraceLogHelper.yesNo(matchResult.isSpecial()))
                        + ", " + PriorityTraceLogHelper.kv("特殊分类", matchResult.getCategoryDisplayText()));
        // 过滤统计
        int filteredCount = trace.notAllowedMachineFilteredCount + trace.disabledCount
                + trace.stopTimeoutCount + trace.inchMismatchCount + trace.mouldSetMismatchCount
                + trace.resolveSpecialSupportFilteredCount() + trace.mouldConflictCount;
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
                        + ", 模具占用=" + trace.mouldConflictCount);
        if (!CollectionUtils.isEmpty(trace.filteredMachineMessages)) {
            PriorityTraceLogHelper.appendLine(detailBuilder,
                    "过滤明细: " + String.join("; ", trace.filteredMachineMessages));
        }

        // TOP N 候选机台
        int topN = LhScheduleConstant.MACHINE_SORT_TRACE_TOP_N;
        int topCount = Math.min(topN, PriorityTraceLogHelper.sizeOf(candidates));
        PriorityTraceLogHelper.appendLine(detailBuilder, "TOP" + topCount + "候选排序:");
        List<String> levelNames = java.util.Arrays.asList(
                "L1_定点机台", "L2_单控拆分", "L3_普通机台优先", "L4_收尾时间",
                "L5_同规格", "L6_同英寸", "L7_英寸接近度", "L8_胶囊共用", "L9_胎胚共用");
        for (int i = 0; i < topCount; i++) {
            MachineScheduleDTO machine = candidates.get(i);
            int specifyScore = resolveLimitSpecifyScore(context, sku, machine);
            int singleCtrlScore = resolveSingleControlScore(context, sku, machine);
            int normalMachineScore = resolveNormalMachinePriorityValue(matchResult, machine);
            int specMatchScore = resolveSpecMatchScore(sku, machine);
            int proSizeMatchScore = resolveProSizeMatchScore(sku, machine);
            double inchDistance = resolveInchDistance(sku, machine);
            int capsuleScore = resolveCapsuleAffinityScore(context, sku, machine);
            int embryoShareCount = resolveEmbryoShareCount(context, machine);

            List<String> sortKeyLevels = java.util.Arrays.asList(
                    "L1_定点机台=" + specifyScore,
                    "L2_单控拆分=" + singleCtrlScore,
                    "L3_普通机台优先=" + normalMachineScore,
                    "L4_收尾时间=" + PriorityTraceLogHelper.formatDateTime(machine.getEstimatedEndTime()),
                    "L5_同规格=" + specMatchScore,
                    "L6_同英寸=" + proSizeMatchScore,
                    "L7_英寸接近度=" + formatInchDistance(inchDistance),
                    "L8_胶囊共用=" + capsuleScore,
                    "L9_胎胚共用=" + embryoShareCount);
            List<Integer> scores = java.util.Arrays.asList(
                    specifyScore,
                    singleCtrlScore,
                    normalMachineScore,
                    resolveEndingTimeScore(machine),
                    specMatchScore,
                    proSizeMatchScore,
                    safeInchDistanceScore(inchDistance),
                    capsuleScore,
                    embryoShareCount);
            List<Integer> defaultScores = java.util.Arrays.asList(1, 1, 0, 0, 1, 1, 0, 1, 0);
            String sortKey = PriorityTraceLogHelper.formatSortKey(sortKeyLevels);
            String hitLevel = PriorityTraceLogHelper.resolveHitLevel(levelNames, scores, defaultScores);

            boolean isSingleCtrl = LhSingleControlMachineUtil.isSingleMouldMachine(machine.getMachineCode());
            PriorityTraceLogHelper.appendLine(detailBuilder,
                    (i + 1)
                            + ". " + PriorityTraceLogHelper.kv("机台", machine.getMachineCode())
                            + ", " + PriorityTraceLogHelper.kv("名称", machine.getMachineName())
                            + ", " + PriorityTraceLogHelper.kv("状态", machine.getStatus())
                            + ", " + PriorityTraceLogHelper.kv("可用", PriorityTraceLogHelper.yesNo(MachineStatusUtil.isEnabled(machine.getStatus())))
                            + ", " + PriorityTraceLogHelper.kv("单控", PriorityTraceLogHelper.yesNo(isSingleCtrl))
                            + ", " + PriorityTraceLogHelper.kv("定点", PriorityTraceLogHelper.yesNo(specifyScore == 0))
                            + ", " + PriorityTraceLogHelper.kv("支持SKU", PriorityTraceLogHelper.yesNo(true))
                            + ", " + PriorityTraceLogHelper.kv("当前在机", machine.getPreviousMaterialCode())
                            + ", " + PriorityTraceLogHelper.kv("收尾时间", PriorityTraceLogHelper.formatDateTime(machine.getEstimatedEndTime()))
                            + ", " + PriorityTraceLogHelper.kv("同规格", PriorityTraceLogHelper.yesNo(specMatchScore == 0))
                            + ", " + PriorityTraceLogHelper.kv("同英寸", PriorityTraceLogHelper.yesNo(proSizeMatchScore == 0))
                            + ", " + PriorityTraceLogHelper.kv("英寸差", formatInchDistance(inchDistance))
                            + ", " + PriorityTraceLogHelper.kv("胶囊共用", PriorityTraceLogHelper.yesNo(capsuleScore == 0))
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
        List<String> reasons = new ArrayList<>(4);
        if (resolveLimitSpecifyScore(context, sku, machine) == 0) {
            reasons.add("定点机台优先");
        }
        if (LhSingleControlMachineUtil.isSingleMouldMachine(machine.getMachineCode())
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
        if (machine.getEstimatedEndTime() != null) {
            reasons.add("收尾时间最近");
        }
        if (reasons.isEmpty()) {
            reasons.add("排序首位默认");
        }
        return String.join("，", reasons);
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
     * 解析收尾时间得分，用于 HitLevel 比较（0=无收尾时间靠后，1=有收尾时间优先）。
     */
    private static int resolveEndingTimeScore(MachineScheduleDTO machine) {
        return machine != null && machine.getEstimatedEndTime() != null ? 1 : 0;
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
