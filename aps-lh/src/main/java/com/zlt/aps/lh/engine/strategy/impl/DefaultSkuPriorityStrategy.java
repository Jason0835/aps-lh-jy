/**
 * Copyright (c) 2008, 智立通（厦门）科技有限公司 All rights reserved。
 */
package com.zlt.aps.lh.engine.strategy.impl;

import com.zlt.aps.lh.api.constant.LhScheduleConstant;
import com.zlt.aps.lh.api.constant.LhScheduleParamConstant;
import com.zlt.aps.lh.api.domain.dto.MachineScheduleDTO;
import com.zlt.aps.lh.api.domain.dto.SkuScheduleDTO;
import com.zlt.aps.lh.api.enums.ConstructionStageEnum;
import com.zlt.aps.lh.api.enums.ScheduleStepEnum;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.engine.strategy.IEndingJudgmentStrategy;
import com.zlt.aps.lh.util.LhMachineHardMatchUtil;
import com.zlt.aps.lh.engine.strategy.ISkuPriorityStrategy;
import com.zlt.aps.lh.util.MachineStatusUtil;
import com.zlt.aps.lh.util.LhSpecialMaterialUtil;
import com.zlt.aps.lh.util.LhSpecifyMachineUtil;
import com.zlt.aps.lh.util.PriorityTraceLogHelper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import javax.annotation.Resource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 默认SKU排产优先级策略实现
 * <p>基于发货要求、延误天数、结构全收尾优先级和供应链优先级进行多维度排序</p>
 *
 * @author APS
 */
@Slf4j
@Component
public class DefaultSkuPriorityStrategy implements ISkuPriorityStrategy {

    /** 特殊材料标识 */
    private static final String SPECIAL_MATERIAL_YES_FLAG = "1";
    /** 雪地胎关键词分隔正则 */
    private static final String WINTER_TIRE_KEYWORD_SEPARATOR_REGEX = "[,，]";

    @Resource
    private IEndingJudgmentStrategy endingJudgmentStrategy;

    @Override
    public void sortByPriority(LhScheduleContext context) {
        log.info("执行SKU优先级排序, 续作SKU数: {}, 新增SKU数: {}",
                context.getContinuousSkuList().size(), context.getNewSpecSkuList().size());

        Map<String, StructurePriorityMeta> structurePriorityMap = buildStructurePriorityMap(context);
        Map<String, Boolean> singleCandidateSpecialPriorityMap = buildSingleCandidateSpecialPriorityMap(context);
        Comparator<SkuScheduleDTO> priorityComparator = buildPriorityComparator(structurePriorityMap);
        Comparator<SkuScheduleDTO> tailComparator = buildTailComparator(context);
        Comparator<SkuScheduleDTO> comparator = priorityComparator.thenComparing(tailComparator);
        Comparator<SkuScheduleDTO> newSpecComparator = buildNewSpecComparator(
                context, priorityComparator, tailComparator, singleCandidateSpecialPriorityMap);
        sortSkuList(context.getContinuousSkuList(), comparator);
        sortSkuList(context.getNewSpecSkuList(), newSpecComparator);

        // 同时对每个结构下的SKU列表排序，保证结构内顺序与主排序一致。
        for (Map.Entry<String, List<SkuScheduleDTO>> entry : context.getStructureSkuMap().entrySet()) {
            entry.getValue().sort(comparator);
        }

        // 按统一优先级回写顺序号，供后续结果对象复用。
        List<SkuScheduleDTO> orderedSkuList = buildOrderedSkuList(context, newSpecComparator);
        int order = 1;
        for (SkuScheduleDTO sku : orderedSkuList) {
            sku.setScheduleOrder(order++);
        }

        traceOpenProductionLateScore(context, orderedSkuList);
        traceSortedSkuList(context, structurePriorityMap, singleCandidateSpecialPriorityMap);
        log.debug("SKU优先级排序完成, 排序后第一位: {}",
                CollectionUtils.isEmpty(orderedSkuList) ? "空" : orderedSkuList.get(0).getMaterialCode());
    }

    /**
     * 构建SKU多维度比较器
     * <p>
     * 排序规则（优先级从高到低）：
     * <ol>
     *   <li>有发货要求优先（deliveryLocked=true 排前）</li>
     *   <li>延误天数越多越优先（delayDays 降序）</li>
     *   <li>未来结构全收尾优先：未来N天内（N可配置）结构下全部SKU均收尾时，该结构内收尾日越晚（endingDaysRemaining 越大）的越优先上机</li>
     *   <li>供应链优先级：高优先级(04) → 周期排产(05) → 中优先级(06) → 搭配排产(07)</li>
     * </ol>
     * </p>
     *
     * @return SKU比较器
     */
    private Comparator<SkuScheduleDTO> buildPriorityComparator(Map<String, StructurePriorityMeta> structurePriorityMap) {
        return Comparator
                // 顺序1：锁定上机日期的优先。
                .comparingInt((SkuScheduleDTO s) -> s.isDeliveryLocked() ? 0 : 1)
                // 顺序2：延迟上机越久越优先，未知值排后。
                .thenComparingInt((SkuScheduleDTO s) -> s.getDelayDays() >= 0 ? 0 : 1)
                .thenComparingInt((SkuScheduleDTO s) -> s.getDelayDays() >= 0 ? -s.getDelayDays() : 0)
                // 顺序3：未来结构全收尾优先，命中结构内按最晚收尾优先。
                .thenComparingInt((SkuScheduleDTO s) -> isStructureAllEndingPriority(structurePriorityMap, s) ? 0 : 1)
                .thenComparingInt((SkuScheduleDTO s) -> isStructureAllEndingPriority(structurePriorityMap, s)
                        && hasKnownEndingDays(s) ? 0 : 1)
                .thenComparingInt((SkuScheduleDTO s) -> isStructureAllEndingPriority(structurePriorityMap, s)
                        && hasKnownEndingDays(s) ? -s.getEndingDaysRemaining() : 0);
    }

    /**
     * 构建结构优先级后的尾部比较器。
     *
     * @param context 排程上下文
     * @return 尾部比较器
     */
    private Comparator<SkuScheduleDTO> buildTailComparator(LhScheduleContext context) {
        return Comparator
                // 顺序4：供应链优先按四类待排量逐级比较。
                .comparingInt((SkuScheduleDTO s) -> -s.getHighPriorityPendingQty())
                .thenComparingInt((SkuScheduleDTO s) -> -s.getCycleProductionPendingQty())
                .thenComparingInt((SkuScheduleDTO s) -> -s.getMidPriorityPendingQty())
                .thenComparingInt((SkuScheduleDTO s) -> -s.getConventionProductionPendingQty())
                // 顺序5：开产模式下雪地胎、不同英寸、特殊材料仅在同等条件下靠后。
                .thenComparingInt((SkuScheduleDTO s) -> resolveOpenProductionLateScore(context, s))
                .thenComparing(SkuScheduleDTO::getMaterialCode, Comparator.nullsLast(String::compareTo));
    }

    /**
     * 构建新增SKU比较器，定点物料、试制量试、小批量验证SKU优先进入新增排产。
     *
     * @param context 排程上下文
     * @param priorityComparator 锁交期/延期/结构优先比较器
     * @param tailComparator 供应链及尾部比较器
     * @return 新增SKU比较器
     */
    private Comparator<SkuScheduleDTO> buildNewSpecComparator(LhScheduleContext context,
                                                              Comparator<SkuScheduleDTO> priorityComparator,
                                                              Comparator<SkuScheduleDTO> tailComparator,
                                                              Map<String, Boolean> singleCandidateSpecialPriorityMap) {
        return Comparator
                .comparingInt((SkuScheduleDTO sku) -> LhSpecifyMachineUtil.hasLimitSpecifyMachine(
                        context, sku.getMaterialCode()) ? 0 : 1)
                .thenComparingInt(sku -> isSingleCandidateSpecialPriority(singleCandidateSpecialPriorityMap, sku) ? 0 : 1)
                .thenComparing(priorityComparator)
                .thenComparingInt(sku -> sku.isTrial() ? 0 : 1)
                .thenComparingInt(sku -> sku.isSmallBatchValidation() ? 0 : 1)
                .thenComparing(tailComparator);
    }

    /**
     * 构建"特殊材料且唯一候选机台"优先级快照。
     *
     * @param context 排程上下文
     * @return 物料编码 -> 是否命中唯一候选特殊材料优先
     */
    private Map<String, Boolean> buildSingleCandidateSpecialPriorityMap(LhScheduleContext context) {
        Map<String, Boolean> priorityMap = new LinkedHashMap<>(16);
        if (Objects.isNull(context)
                || CollectionUtils.isEmpty(context.getNewSpecSkuList())
                || CollectionUtils.isEmpty(context.getMachineScheduleMap())) {
            return priorityMap;
        }
        for (SkuScheduleDTO sku : context.getNewSpecSkuList()) {
            if (sku == null || StringUtils.isEmpty(sku.getMaterialCode()) || !isSpecialMaterial(context, sku)) {
                continue;
            }
            if (countAvailableCandidateMachines(context, sku) == 1) {
                priorityMap.put(sku.getMaterialCode(), true);
            }
        }
        return priorityMap;
    }

    /**
     * 判断 SKU 是否命中"特殊材料且唯一候选机台"优先级。
     *
     * @param priorityMap 优先级快照
     * @param sku SKU
     * @return true-命中
     */
    private boolean isSingleCandidateSpecialPriority(Map<String, Boolean> priorityMap, SkuScheduleDTO sku) {
        return sku != null
                && StringUtils.isNotEmpty(sku.getMaterialCode())
                && Boolean.TRUE.equals(priorityMap.get(sku.getMaterialCode()));
    }

    /**
     * 统计 SKU 当前可用的硬匹配候选机台数。
     * <p>只用于"唯一候选特殊材料前置"轻量优先级快照，不改主匹配流程。</p>
     *
     * @param context 排程上下文
     * @param sku SKU
     * @return 候选机台数
     */
    private int countAvailableCandidateMachines(LhScheduleContext context, SkuScheduleDTO sku) {
        int candidateCount = 0;
        for (MachineScheduleDTO machine : context.getMachineScheduleMap().values()) {
            if (machine == null
                    || !MachineStatusUtil.isEnabled(machine.getStatus())
                    || !LhMachineHardMatchUtil.isMachineHardMatched(context, sku, machine)
                    || LhSpecifyMachineUtil.isNotAllowedMachine(context, machine.getMachineCode(), sku.getMaterialCode())) {
                continue;
            }
            candidateCount++;
            if (candidateCount > 1) {
                return candidateCount;
            }
        }
        return candidateCount;
    }

    /**
     * 构建结构全收尾优先级快照，避免比较器中重复扫描结构列表。
     */
    private Map<String, StructurePriorityMeta> buildStructurePriorityMap(LhScheduleContext context) {
        Map<String, StructurePriorityMeta> structurePriorityMap = new LinkedHashMap<>(16);
        if (CollectionUtils.isEmpty(context.getStructureSkuMap())) {
            return structurePriorityMap;
        }
        int structureEndingDays = context.getScheduleConfig() != null
                ? context.getScheduleConfig().getStructureEndingDays()
                : LhScheduleConstant.DEFAULT_STRUCTURE_ENDING_DAYS;
        for (Map.Entry<String, List<SkuScheduleDTO>> entry : context.getStructureSkuMap().entrySet()) {
            if (StringUtils.isEmpty(entry.getKey()) || CollectionUtils.isEmpty(entry.getValue())) {
                continue;
            }
            int totalSkuCount = 0;
            int endingSkuCount = 0;
            int latestEndingDays = -1;
            for (SkuScheduleDTO sku : entry.getValue()) {
                if (sku == null) {
                    continue;
                }
                totalSkuCount++;
                if (!endingJudgmentStrategy.isEnding(context, sku)) {
                    continue;
                }
                endingSkuCount++;
                if (hasKnownEndingDays(sku)) {
                    latestEndingDays = Math.max(latestEndingDays, sku.getEndingDaysRemaining());
                }
            }
            boolean allSkusEnding = totalSkuCount > 0 && endingSkuCount == totalSkuCount;
            StructurePriorityMeta meta = new StructurePriorityMeta();
            meta.setTotalSkuCount(totalSkuCount);
            meta.setEndingSkuCount(endingSkuCount);
            meta.setAllSkusEnding(allSkusEnding);
            meta.setLatestEndingDays(latestEndingDays);
            meta.setAllSkusEndingPriority(allSkusEnding
                    && latestEndingDays >= 0
                    && latestEndingDays <= structureEndingDays);
            structurePriorityMap.put(entry.getKey(), meta);
        }
        return structurePriorityMap;
    }

    /**
     * 判断SKU所属结构是否进入"未来结构全收尾"优先级。
     */
    private boolean isStructureAllEndingPriority(Map<String, StructurePriorityMeta> structurePriorityMap, SkuScheduleDTO sku) {
        if (sku == null || StringUtils.isEmpty(sku.getStructureName())) {
            return false;
        }
        StructurePriorityMeta meta = structurePriorityMap.get(sku.getStructureName());
        return meta != null && meta.isAllSkusEndingPriority();
    }

    /**
     * 判断SKU是否具备可比较的收尾天数。
     */
    private boolean hasKnownEndingDays(SkuScheduleDTO sku) {
        return sku != null && sku.getEndingDaysRemaining() >= 0;
    }

    /**
     * 解析开产模式 SKU 靠后分。
     *
     * @param context 排程上下文
     * @param sku SKU
     * @return 靠后分
     */
    private int resolveOpenProductionLateScore(LhScheduleContext context, SkuScheduleDTO sku) {
        if (Objects.isNull(context) || Objects.isNull(sku) || !context.isOpenProductionMode()) {
            return 0;
        }
        int score = 0;
        if (isWinterTire(context, sku)) {
            score += LhScheduleConstant.OPEN_PRODUCTION_WINTER_TIRE_PENALTY;
        }
        if (isDifferentInch(context, sku)) {
            score += LhScheduleConstant.OPEN_PRODUCTION_DIFFERENT_INCH_PENALTY;
        }
        if (isSpecialMaterial(context, sku)) {
            score += LhScheduleConstant.OPEN_PRODUCTION_SPECIAL_MATERIAL_PENALTY;
        }
        return score;
    }

    /**
     * 判断是否为雪地胎。
     *
     * @param context 排程上下文
     * @param sku SKU
     * @return true-雪地胎，false-非雪地胎
     */
    private boolean isWinterTire(LhScheduleContext context, SkuScheduleDTO sku) {
        String keywords = Objects.nonNull(context.getScheduleConfig())
                ? context.getScheduleConfig().getOpenProductionWinterTireKeywords()
                : context.getParamValue(LhScheduleParamConstant.OPEN_PRODUCTION_WINTER_TIRE_KEYWORDS,
                LhScheduleConstant.OPEN_PRODUCTION_WINTER_TIRE_KEYWORDS);
        if (StringUtils.isEmpty(keywords)) {
            return false;
        }
        String[] keywordArray = keywords.split(WINTER_TIRE_KEYWORD_SEPARATOR_REGEX);
        for (String keyword : keywordArray) {
            if (StringUtils.isEmpty(keyword)) {
                continue;
            }
            String trimmedKeyword = keyword.trim();
            if (StringUtils.containsIgnoreCase(sku.getMaterialDesc(), trimmedKeyword)
                    || StringUtils.containsIgnoreCase(sku.getSpecDesc(), trimmedKeyword)
                    || StringUtils.containsIgnoreCase(sku.getPattern(), trimmedKeyword)
                    || StringUtils.containsIgnoreCase(sku.getMainPattern(), trimmedKeyword)
                    || StringUtils.containsIgnoreCase(sku.getBrand(), trimmedKeyword)
                    || StringUtils.containsIgnoreCase(sku.getMainMaterialDesc(), trimmedKeyword)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断 SKU 是否与当前在机或续作英寸不同。
     *
     * @param context 排程上下文
     * @param sku SKU
     * @return true-不同英寸，false-相同或无比较基准
     */
    private boolean isDifferentInch(LhScheduleContext context, SkuScheduleDTO sku) {
        if (StringUtils.isEmpty(sku.getProSize())) {
            return false;
        }
        boolean hasReference = false;
        if (!CollectionUtils.isEmpty(context.getMachineScheduleMap())) {
            for (MachineScheduleDTO machine : context.getMachineScheduleMap().values()) {
                if (Objects.isNull(machine) || StringUtils.isEmpty(machine.getPreviousProSize())) {
                    continue;
                }
                hasReference = true;
                if (StringUtils.equals(sku.getProSize(), machine.getPreviousProSize())) {
                    return false;
                }
            }
        }
        if (!CollectionUtils.isEmpty(context.getContinuousSkuList())) {
            for (SkuScheduleDTO continuousSku : context.getContinuousSkuList()) {
                if (Objects.isNull(continuousSku) || StringUtils.isEmpty(continuousSku.getProSize())) {
                    continue;
                }
                hasReference = true;
                if (StringUtils.equals(sku.getProSize(), continuousSku.getProSize())) {
                    return false;
                }
            }
        }
        return hasReference;
    }

    /**
     * 判断是否为特殊材料。
     *
     * @param context 排程上下文
     * @param sku SKU
     * @return true-特殊材料，false-非特殊材料
     */
    private boolean isSpecialMaterial(LhScheduleContext context, SkuScheduleDTO sku) {
        return StringUtils.equals(SPECIAL_MATERIAL_YES_FLAG, LhSpecialMaterialUtil.resolveHasSpecialMaterial(context, sku));
    }

    /**
     * 排序列表，为空时直接跳过。
     */
    private void sortSkuList(List<SkuScheduleDTO> skuList, Comparator<SkuScheduleDTO> comparator) {
        if (CollectionUtils.isEmpty(skuList)) {
            return;
        }
        skuList.sort(comparator);
    }

    /**
     * 汇总所有SKU并按统一优先级排序，用于回写顺序号。
     */
    private List<SkuScheduleDTO> buildOrderedSkuList(LhScheduleContext context, Comparator<SkuScheduleDTO> comparator) {
        List<SkuScheduleDTO> orderedSkus = new ArrayList<>(
                context.getContinuousSkuList().size() + context.getNewSpecSkuList().size());
        orderedSkus.addAll(context.getContinuousSkuList());
        orderedSkus.addAll(context.getNewSpecSkuList());
        orderedSkus.sort(comparator);
        return orderedSkus;
    }

    /**
     * 输出开产模式 SKU 靠后排序原因。
     *
     * @param context 排程上下文
     * @param orderedSkuList 排序后 SKU 列表
     * @return void
     */
    private void traceOpenProductionLateScore(LhScheduleContext context, List<SkuScheduleDTO> orderedSkuList) {
        if (Objects.isNull(context) || !context.isOpenProductionMode()
                || context.isPriorityTraceMuted() || CollectionUtils.isEmpty(orderedSkuList)) {
            return;
        }
        StringBuilder detailBuilder = new StringBuilder(256);
        for (SkuScheduleDTO sku : orderedSkuList) {
            int score = resolveOpenProductionLateScore(context, sku);
            if (score <= 0) {
                continue;
            }
            detailBuilder.append("materialCode=").append(sku.getMaterialCode())
                    .append(", score=").append(score)
                    .append(", winterTire=").append(isWinterTire(context, sku))
                    .append(", differentInch=").append(isDifferentInch(context, sku))
                    .append(", specialMaterial=").append(isSpecialMaterial(context, sku))
                    .append('\n');
        }
        if (detailBuilder.length() > 0) {
            log.info("开产SKU靠后排序原因\n{}", detailBuilder.toString().trim());
        }
    }

    /**
     * 输出排序后的SKU优先级跟踪日志（含汇总标题、TOP N、SortKey、HitLevel）。
     *
     * @param context 排程上下文
     * @param structurePriorityMap 结构收尾优先级快照
     * @param singleCandidateSpecialPriorityMap 唯一候选特殊材料优先级快照
     */
    private void traceSortedSkuList(LhScheduleContext context,
                                    Map<String, StructurePriorityMeta> structurePriorityMap,
                                    Map<String, Boolean> singleCandidateSpecialPriorityMap) {
        if (!PriorityTraceLogHelper.isEnabled(context)) {
            return;
        }
        String currentStep = context.getCurrentStep();
        String title;
        List<SkuScheduleDTO> traceSkuList;
        boolean isNewSpec;
        if (StringUtils.equals(ScheduleStepEnum.S4_4_CONTINUOUS_PRODUCTION.getCode(), currentStep)) {
            title = "SKU排序优先级汇总【续作】";
            traceSkuList = context.getContinuousSkuList();
            isNewSpec = false;
        } else if (StringUtils.equals(ScheduleStepEnum.S4_5_NEW_PRODUCTION.getCode(), currentStep)) {
            title = "SKU排序优先级汇总【新增】";
            traceSkuList = context.getNewSpecSkuList();
            isNewSpec = true;
        } else {
            return;
        }

        int topN = LhScheduleConstant.SKU_SORT_TRACE_TOP_N;
        int skuCount = PriorityTraceLogHelper.sizeOf(traceSkuList);
        int outputCount = Math.min(topN, skuCount);

        StringBuilder detailBuilder = new StringBuilder(1024);
        PriorityTraceLogHelper.appendTitleHeader(detailBuilder, title);
        PriorityTraceLogHelper.appendLine(detailBuilder,
                PriorityTraceLogHelper.kv("排程日期", PriorityTraceLogHelper.formatDateTime(context.getScheduleDate()))
                        + ", " + PriorityTraceLogHelper.kv("步骤", currentStep)
                        + ", " + PriorityTraceLogHelper.kv("排序场景", isNewSpec ? "新增SKU排序" : "续作SKU排序")
                        + ", " + PriorityTraceLogHelper.kv("SKU数量", skuCount)
                        + ", " + PriorityTraceLogHelper.kv("输出范围", "TOP" + outputCount));

        if (CollectionUtils.isEmpty(traceSkuList)) {
            PriorityTraceLogHelper.appendLine(detailBuilder, "无可输出的SKU排序结果");
        } else {
            // 层级名称，用于 HitLevel 推导
            List<String> levelNames;
            if (isNewSpec) {
                levelNames = Arrays.asList(
                        "L1_定点机台", "L2_唯一候选特殊材料", "L3_锁交期", "L4_延误天数",
                        "L5_结构全收尾", "L6_最晚收尾日", "L7_试制", "L8_小批量",
                        "L9_高优待排", "L10_周期待排", "L11_中优待排", "L12_常规待排",
                        "L13_开产靠后分");
            } else {
                levelNames = Arrays.asList(
                        "L1_锁交期", "L2_延误天数", "L3_结构全收尾", "L4_最晚收尾日",
                        "L5_高优待排", "L6_周期待排", "L7_中优待排", "L8_常规待排",
                        "L9_开产靠后分");
            }

            for (int i = 0; i < outputCount; i++) {
                SkuScheduleDTO sku = traceSkuList.get(i);
                boolean structureAllEndingPriority = isStructureAllEndingPriority(structurePriorityMap, sku);
                boolean ending = endingJudgmentStrategy.isEnding(context, sku);
                boolean isSpecifyMachine = LhSpecifyMachineUtil.hasLimitSpecifyMachine(context, sku.getMaterialCode());
                boolean isSingleCandidate = isSingleCandidateSpecialPriority(singleCandidateSpecialPriorityMap, sku);
                boolean isSpecial = isSpecialMaterial(context, sku);
                String constructionStageDesc = resolveConstructionStageDesc(sku);

                // 计算各层级得分
                List<String> sortKeyLevels;
                List<Integer> scores;
                List<Integer> defaultScores;
                if (isNewSpec) {
                    sortKeyLevels = Arrays.asList(
                            "L1_定点机台=" + (isSpecifyMachine ? 0 : 1),
                            "L2_唯一候选特殊材料=" + (isSingleCandidate ? 0 : 1),
                            "L3_锁交期=" + (sku.isDeliveryLocked() ? 0 : 1),
                            "L4_延误天数=" + (sku.getDelayDays() >= 0 ? -sku.getDelayDays() : 0),
                            "L5_结构全收尾=" + (structureAllEndingPriority ? 0 : 1),
                            "L6_最晚收尾日=" + (structureAllEndingPriority && hasKnownEndingDays(sku) ? -sku.getEndingDaysRemaining() : 0),
                            "L7_试制=" + (sku.isTrial() ? 0 : 1),
                            "L8_小批量=" + (sku.isSmallBatchValidation() ? 0 : 1),
                            "L9_高优待排=" + (-sku.getHighPriorityPendingQty()),
                            "L10_周期待排=" + (-sku.getCycleProductionPendingQty()),
                            "L11_中优待排=" + (-sku.getMidPriorityPendingQty()),
                            "L12_常规待排=" + (-sku.getConventionProductionPendingQty()),
                            "L13_开产靠后分=" + resolveOpenProductionLateScore(context, sku));
                    scores = Arrays.asList(
                            isSpecifyMachine ? 0 : 1,
                            isSingleCandidate ? 0 : 1,
                            sku.isDeliveryLocked() ? 0 : 1,
                            sku.getDelayDays() >= 0 ? -sku.getDelayDays() : 0,
                            structureAllEndingPriority ? 0 : 1,
                            structureAllEndingPriority && hasKnownEndingDays(sku) ? -sku.getEndingDaysRemaining() : 0,
                            sku.isTrial() ? 0 : 1,
                            sku.isSmallBatchValidation() ? 0 : 1,
                            -sku.getHighPriorityPendingQty(),
                            -sku.getCycleProductionPendingQty(),
                            -sku.getMidPriorityPendingQty(),
                            -sku.getConventionProductionPendingQty(),
                            resolveOpenProductionLateScore(context, sku));
                    defaultScores = Arrays.asList(1, 1, 1, 0, 1, 0, 1, 1, 0, 0, 0, 0, 0);
                } else {
                    sortKeyLevels = Arrays.asList(
                            "L1_锁交期=" + (sku.isDeliveryLocked() ? 0 : 1),
                            "L2_延误天数=" + (sku.getDelayDays() >= 0 ? 0 : 1) + "/" + (sku.getDelayDays() >= 0 ? -sku.getDelayDays() : 0),
                            "L3_结构全收尾=" + (structureAllEndingPriority ? 0 : 1),
                            "L4_最晚收尾日=" + (structureAllEndingPriority && hasKnownEndingDays(sku) ? -sku.getEndingDaysRemaining() : 0),
                            "L5_高优待排=" + (-sku.getHighPriorityPendingQty()),
                            "L6_周期待排=" + (-sku.getCycleProductionPendingQty()),
                            "L7_中优待排=" + (-sku.getMidPriorityPendingQty()),
                            "L8_常规待排=" + (-sku.getConventionProductionPendingQty()),
                            "L9_开产靠后分=" + resolveOpenProductionLateScore(context, sku));
                    scores = Arrays.asList(
                            sku.isDeliveryLocked() ? 0 : 1,
                            sku.getDelayDays() >= 0 ? 0 : 1,
                            structureAllEndingPriority ? 0 : 1,
                            structureAllEndingPriority && hasKnownEndingDays(sku) ? -sku.getEndingDaysRemaining() : 0,
                            -sku.getHighPriorityPendingQty(),
                            -sku.getCycleProductionPendingQty(),
                            -sku.getMidPriorityPendingQty(),
                            -sku.getConventionProductionPendingQty(),
                            resolveOpenProductionLateScore(context, sku));
                    defaultScores = Arrays.asList(1, 1, 1, 0, 0, 0, 0, 0, 0);
                }
                String sortKey = PriorityTraceLogHelper.formatSortKey(sortKeyLevels);
                String hitLevel = PriorityTraceLogHelper.resolveHitLevel(levelNames, scores, defaultScores);

                PriorityTraceLogHelper.appendLine(detailBuilder,
                        (i + 1)
                                + ". " + PriorityTraceLogHelper.kv("物料编码", sku.getMaterialCode())
                                + ", " + PriorityTraceLogHelper.kv("描述", sku.getMaterialDesc())
                                + ", " + PriorityTraceLogHelper.kv("排产类型", sku.getScheduleType())
                                + ", " + PriorityTraceLogHelper.kv("续作", yesNoFromScheduleType(sku.getScheduleType()))
                                + ", " + PriorityTraceLogHelper.kv("收尾", PriorityTraceLogHelper.yesNo(ending))
                                + ", " + PriorityTraceLogHelper.kv("阶段", constructionStageDesc)
                                + ", " + PriorityTraceLogHelper.kv("试制", PriorityTraceLogHelper.yesNo(sku.isTrial()))
                                + ", " + PriorityTraceLogHelper.kv("特殊材料", PriorityTraceLogHelper.yesNo(isSpecial))
                                + ", " + PriorityTraceLogHelper.kv("定点机台", PriorityTraceLogHelper.yesNo(isSpecifyMachine))
                                + ", " + PriorityTraceLogHelper.kv("月计划量", sku.getMonthPlanQty())
                                + ", " + PriorityTraceLogHelper.kv("余量", sku.getSurplusQty())
                                + ", " + PriorityTraceLogHelper.kv("胎胚库存", sku.getEmbryoStock())
                                + ", " + PriorityTraceLogHelper.kv("班产", sku.getShiftCapacity())
                                + ", " + PriorityTraceLogHelper.kv("规格", sku.getSpecCode())
                                + ", " + PriorityTraceLogHelper.kv("花纹", sku.getMainPattern())
                                + ", " + PriorityTraceLogHelper.kv("胎胚描述", sku.getMainMaterialDesc())
                                + ", " + PriorityTraceLogHelper.kv("SortKey", sortKey)
                                + ", " + PriorityTraceLogHelper.kv("HitLevel", hitLevel));
            }
            if (skuCount > topN) {
                PriorityTraceLogHelper.appendLine(detailBuilder,
                        "... 共" + skuCount + "条，仅展示前" + topN + "条");
            }
        }
        PriorityTraceLogHelper.appendTitleFooter(detailBuilder);
        String detail = detailBuilder.toString().trim();
        PriorityTraceLogHelper.logSortSummary(log, context, title, detail);
    }

    /**
     * 根据排产类型判断是否续作。
     *
     * @param scheduleType 排产类型编码
     * @return 是/否
     */
    private static String yesNoFromScheduleType(String scheduleType) {
        return "01".equals(scheduleType) ? "是" : "否";
    }

    /**
     * 解析SKU施工阶段描述。
     *
     * @param sku SKU
     * @return 阶段描述
     */
    private static String resolveConstructionStageDesc(SkuScheduleDTO sku) {
        if (sku == null) {
            return "-";
        }
        if (sku.isTrial()) {
            return "试制";
        }
        if (sku.isSmallBatchValidation()) {
            return "小批量";
        }
        ConstructionStageEnum stage = ConstructionStageEnum.getByCode(sku.getConstructionStage());
        return stage != null ? stage.getDescription() : "-";
    }

    /**
     * 结构收尾排序元数据。
     */
    @lombok.Data
    private static class StructurePriorityMeta {
        /** 结构内SKU总数 */
        private int totalSkuCount;
        /** 结构内收尾SKU数量 */
        private int endingSkuCount;
        /** 结构内是否全部SKU收尾 */
        private boolean allSkusEnding;
        /** 结构是否进入未来全收尾优先级 */
        private boolean allSkusEndingPriority;
        /** 结构内最晚收尾天数 */
        private int latestEndingDays;
    }
}
