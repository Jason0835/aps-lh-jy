/**
 * Copyright (c) 2008, 智立通（厦门）科技有限公司 All rights reserved。
 */
package com.zlt.aps.lh.engine.strategy.impl;

import com.zlt.aps.lh.api.constant.LhScheduleConstant;
import com.zlt.aps.lh.api.constant.LhScheduleParamConstant;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.api.domain.dto.MachineScheduleDTO;
import com.zlt.aps.lh.api.domain.dto.SkuScheduleDTO;
import com.zlt.aps.lh.api.domain.entity.LhScheduleResult;
import com.zlt.aps.lh.api.domain.entity.LhSpecifyMachine;
import com.zlt.aps.lh.api.domain.vo.LhShiftConfigVO;
import com.zlt.aps.lh.engine.strategy.IMachineMatchStrategy;
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

    /** 定点机台不可作业标记 */
    private static final String JOB_TYPE_FORBIDDEN = "1";
    /** 每小时毫秒数 */
    private static final long MILLIS_PER_HOUR = 60L * 60L * 1000L;

    @Override
    public List<MachineScheduleDTO> matchMachines(LhScheduleContext context, SkuScheduleDTO sku) {
        log.debug("匹配可用硫化机台, SKU: {}", sku.getMaterialCode());

        // 1. 从硫化定点机台获取SKU可用机台编号集合（排除不可作业的）
        Set<String> allowedMachineCodes = getAllowedMachineCodes(context, sku);

        // 2. 获取SKU的模具号列表
        List<String> skuMouldCodes = getSkuMouldCodes(context, sku.getMaterialCode());

        // 3. 获取已被其他计划占用的模具集合
        Set<String> occupiedMouldCodes = getOccupiedMouldCodes(context);

        // 4. 过滤候选机台：状态启用 + 寸口范围匹配 + 模具兼容 + 模具未被占用
        BigDecimal skuInch = parseInch(sku.getProSize());
        List<MachineScheduleDTO> candidates = new ArrayList<>();
        List<MachineScheduleDTO> stopTimeoutCandidates = new ArrayList<>();
        MachineFilterTrace trace = new MachineFilterTrace(context.getMachineScheduleMap().size());

        for (MachineScheduleDTO machine : context.getMachineScheduleMap().values()) {
            if (!canProduceSku(allowedMachineCodes, machine)) {
                trace.allowedMachineFilteredCount++;
                trace.recordFilteredMachine(machine, "定点机台限制");
                continue;
            }
            MachineAvailabilityReason availabilityReason = resolveMachineAvailabilityReason(
                    context, sku, skuMouldCodes, occupiedMouldCodes, skuInch, machine);
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

        // 5. 按多维度排序
        sortCandidates(context, candidates, sku);
        traceMachineCandidates(context, sku, candidates, trace);

        if (CollectionUtils.isEmpty(candidates)) {
            log.warn("SKU候选机台为空, materialCode: {}, 规格: {}, 寸口: {}, 机台总数: {}, 定点过滤: {}, 禁用过滤: {}, 超时停机过滤: {}, 寸口过滤: {}, 模具过滤: {}",
                    sku.getMaterialCode(), sku.getSpecCode(), sku.getProSize(), trace.totalMachineCount,
                    trace.allowedMachineFilteredCount, trace.disabledCount, trace.stopTimeoutCount,
                    trace.inchMismatchCount, trace.mouldConflictCount);
        }
        log.debug("SKU: {} 匹配到 {} 台候选机台", sku.getMaterialCode(), candidates.size());
        return candidates;
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
     * 从硫化定点机台Map中获取该SKU可用的机台编号集合
     * <p>若specifyMachineMap中无该规格记录，则不限制机台（返回空集合表示不限制）</p>
     */
    private Set<String> getAllowedMachineCodes(LhScheduleContext context, SkuScheduleDTO sku) {
        Set<String> allowed = new HashSet<>();
        List<LhSpecifyMachine> specifyList = context.getSpecifyMachineMap().get(sku.getSpecCode());
        if (specifyList == null || specifyList.isEmpty()) {
            return allowed;
        }
        for (LhSpecifyMachine specify : specifyList) {
            // 排除"不可作业"的机台
            if (!JOB_TYPE_FORBIDDEN.equals(specify.getJobType())) {
                allowed.add(specify.getMachineCode());
            }
        }
        return allowed;
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
     * 判断机台是否可生产当前SKU。
     *
     * @param allowedMachineCodes 定点机台白名单
     * @param machine 候选机台
     * @return true-可生产，false-不可生产
     */
    private boolean canProduceSku(Set<String> allowedMachineCodes, MachineScheduleDTO machine) {
        return CollectionUtils.isEmpty(allowedMachineCodes) || allowedMachineCodes.contains(machine.getMachineCode());
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
    private MachineAvailabilityReason resolveMachineAvailabilityReason(LhScheduleContext context, SkuScheduleDTO sku, List<String> skuMouldCodes,
                                                                      Set<String> occupiedMouldCodes, BigDecimal skuInch,
                                                                      MachineScheduleDTO machine) {
        if (!MachineStatusUtil.isEnabled(machine.getStatus())) {
            return MachineAvailabilityReason.DISABLED;
        }
        if (!isInchInRange(skuInch, machine.getDimensionMinimum(), machine.getDimensionMaximum())) {
            return MachineAvailabilityReason.INCH_MISMATCH;
        }
        return isMouldCompatible(sku, skuMouldCodes, machine, occupiedMouldCodes)
                ? MachineAvailabilityReason.AVAILABLE
                : MachineAvailabilityReason.MOULD_CONFLICT;
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
                    || !StringUtils.equals(planShut.getMachineCode(), machine.getMachineCode())) {
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
        // 当前 mouldQty 的业务语义是“选机后的机台模台数”，此处不再拿 SKU 预置模数拦截候选机台。
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
    private boolean isInchInRange(BigDecimal skuInch, BigDecimal minInch, BigDecimal maxInch) {
        if (skuInch == null || minInch == null || maxInch == null) {
            return true;
        }
        return skuInch.compareTo(minInch) >= 0 && skuInch.compareTo(maxInch) <= 0;
    }

    /**
     * 从规格寸口字符串中提取英寸数值
     * <p>如 "225/65R17" 提取17.0，"17.5" 直接解析为17.5</p>
     */
    private BigDecimal parseInch(String proSize) {
        if (proSize == null || proSize.trim().isEmpty()) {
            return null;
        }
        try {
            // 尝试直接解析为数字
            return new BigDecimal(proSize.trim());
        } catch (NumberFormatException e) {
            // 从轮胎规格字符串中提取英寸（如"225/65R17"中的"17"）
            String upper = proSize.toUpperCase();
            int rIdx = upper.lastIndexOf('R');
            if (rIdx >= 0 && rIdx < upper.length() - 1) {
                String inchStr = upper.substring(rIdx + 1).replaceAll("[^0-9.]", "");
                if (!inchStr.isEmpty()) {
                    try {
                        return new BigDecimal(inchStr);
                    } catch (NumberFormatException ignored) {
                        // fall through
                    }
                }
            }
        }
        return null;
    }

    /**
     * 对候选机台进行多维度排序。
     *
     * @param context 排程上下文
     * @param candidates 候选机台
     * @param sku 待排SKU
     */
    private void sortCandidates(LhScheduleContext context, List<MachineScheduleDTO> candidates, SkuScheduleDTO sku) {
        candidates.sort(buildMachineComparator(context, sku));
    }

    /**
     * 构建机台优先级比较器。
     *
     * @param context 排程上下文
     * @param sku 待排SKU
     * @return 比较器
     */
    private Comparator<MachineScheduleDTO> buildMachineComparator(LhScheduleContext context, SkuScheduleDTO sku) {
        return (left, right) -> {
            int compareResult = compareEndingTime(context, left, right);
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
     * 输出候选机台排序跟踪日志。
     *
     * @param context 排程上下文
     * @param sku 待排SKU
     * @param candidates 候选机台
     * @param trace 过滤统计
     */
    private void traceMachineCandidates(LhScheduleContext context, SkuScheduleDTO sku,
                                        List<MachineScheduleDTO> candidates, MachineFilterTrace trace) {
        if (!PriorityTraceLogHelper.isEnabled(context)) {
            return;
        }
        String title = "新增排产候选机台排序明细";
        StringBuilder detailBuilder = new StringBuilder(1024);
        PriorityTraceLogHelper.appendLine(detailBuilder,
                "SKU=" + PriorityTraceLogHelper.safeText(sku.getMaterialCode())
                        + ", 规格=" + PriorityTraceLogHelper.safeText(sku.getSpecCode())
                        + ", 寸口=" + PriorityTraceLogHelper.safeText(sku.getProSize()));
        PriorityTraceLogHelper.appendLine(detailBuilder,
                "候选过滤概况: 机台总数=" + trace.totalMachineCount
                        + ", 定点限制过滤=" + trace.allowedMachineFilteredCount
                        + ", 机台禁用过滤=" + trace.disabledCount
                        + ", 超时停机过滤=" + trace.stopTimeoutCount
                        + ", 寸口不符过滤=" + trace.inchMismatchCount
                        + ", 模具占用过滤=" + trace.mouldConflictCount
                        + ", 候选数=" + PriorityTraceLogHelper.sizeOf(candidates));
        if (!CollectionUtils.isEmpty(trace.filteredMachineMessages)) {
            PriorityTraceLogHelper.appendLine(detailBuilder,
                    "过滤明细: " + String.join("; ", trace.filteredMachineMessages));
        }
        PriorityTraceLogHelper.appendLine(detailBuilder, "TOP5候选排序:");
        int topCount = Math.min(PriorityTraceLogHelper.MACHINE_TRACE_TOP_N, PriorityTraceLogHelper.sizeOf(candidates));
        for (int i = 0; i < topCount; i++) {
            MachineScheduleDTO machine = candidates.get(i);
            PriorityTraceLogHelper.appendLine(detailBuilder,
                    (i + 1)
                            + ". 机台=" + PriorityTraceLogHelper.safeText(machine.getMachineCode())
                            + ", 名称=" + PriorityTraceLogHelper.safeText(machine.getMachineName())
                            + ", 收尾时间=" + PriorityTraceLogHelper.formatDateTime(machine.getEstimatedEndTime())
                            + ", 同规格=" + PriorityTraceLogHelper.yesNo(resolveSpecMatchScore(sku, machine) == 0)
                            + ", 同英寸=" + PriorityTraceLogHelper.yesNo(resolveProSizeMatchScore(sku, machine) == 0)
                            + ", 英寸差=" + resolveInchDistance(sku, machine)
                            + ", 胶囊共用=" + PriorityTraceLogHelper.yesNo(resolveCapsuleAffinityScore(context, sku, machine) == 0)
                            + ", 胎胚共用数=" + resolveEmbryoShareCount(context, machine)
                            + ", 机台顺序号=" + machine.getMachineOrder());
        }
        String detail = detailBuilder.toString().trim();
        log.info("{}\n{}", title, detail);
        PriorityTraceLogHelper.appendProcessLog(context, title, detail);
    }

    /**
     * 机台不可用原因枚举。
     */
    private enum MachineAvailabilityReason {
        AVAILABLE,
        DISABLED,
        STOP_TIMEOUT,
        INCH_MISMATCH,
        MOULD_CONFLICT
    }

    /**
     * 候选机台过滤统计。
     */
    private static class MachineFilterTrace {
        /** 机台总数 */
        private final int totalMachineCount;
        /** 定点限制过滤数 */
        private int allowedMachineFilteredCount;
        /** 禁用过滤数 */
        private int disabledCount;
        /** 超时停机过滤数 */
        private int stopTimeoutCount;
        /** 寸口不符过滤数 */
        private int inchMismatchCount;
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
            } else if (MachineAvailabilityReason.MOULD_CONFLICT == reason) {
                mouldConflictCount++;
                recordFilteredMachine(machine, "模具占用");
            }
        }

        private String buildMachineMessage(MachineScheduleDTO machine, String reason) {
            StringBuilder builder = new StringBuilder(64);
            builder.append(PriorityTraceLogHelper.safeText(machine.getMachineCode()))
                    .append('[').append(reason).append(']');
            return builder.toString();
        }
    }
}
