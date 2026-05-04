/**
 * Copyright (c) 2008, 智立通（厦门）科技有限公司 All rights reserved。
 */
package com.zlt.aps.lh.engine.strategy.impl;

import com.zlt.aps.lh.api.constant.LhScheduleConstant;
import com.zlt.aps.lh.api.domain.dto.SkuScheduleDTO;
import com.zlt.aps.lh.api.enums.ScheduleStepEnum;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.engine.strategy.IEndingJudgmentStrategy;
import com.zlt.aps.lh.engine.strategy.ISkuPriorityStrategy;
import com.zlt.aps.lh.util.LhSpecifyMachineUtil;
import com.zlt.aps.lh.util.PriorityTraceLogHelper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import javax.annotation.Resource;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 默认SKU排产优先级策略实现
 * <p>基于发货要求、延误天数、结构全收尾优先级和供应链优先级进行多维度排序</p>
 *
 * @author APS
 */
@Slf4j
@Component
public class DefaultSkuPriorityStrategy implements ISkuPriorityStrategy {

    @Resource
    private IEndingJudgmentStrategy endingJudgmentStrategy;

    @Override
    public void sortByPriority(LhScheduleContext context) {
        log.info("执行SKU优先级排序, 续作SKU数: {}, 新增SKU数: {}",
                context.getContinuousSkuList().size(), context.getNewSpecSkuList().size());

        Map<String, StructurePriorityMeta> structurePriorityMap = buildStructurePriorityMap(context);
        Comparator<SkuScheduleDTO> comparator = buildSkuComparator(context, structurePriorityMap);
        Comparator<SkuScheduleDTO> newSpecComparator = buildNewSpecComparator(context, comparator);
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

        traceSortedSkuList(context, structurePriorityMap);
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
     * @param context 排程上下文
     * @return SKU比较器
     */
    private Comparator<SkuScheduleDTO> buildSkuComparator(LhScheduleContext context,
                                                          Map<String, StructurePriorityMeta> structurePriorityMap) {
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
                        && hasKnownEndingDays(s) ? -s.getEndingDaysRemaining() : 0)
                // 顺序4：供应链优先按四类待排量逐级比较。
                .thenComparingInt((SkuScheduleDTO s) -> -s.getHighPriorityPendingQty())
                .thenComparingInt((SkuScheduleDTO s) -> -s.getCycleProductionPendingQty())
                .thenComparingInt((SkuScheduleDTO s) -> -s.getMidPriorityPendingQty())
                .thenComparingInt((SkuScheduleDTO s) -> -s.getConventionProductionPendingQty())
                .thenComparing(SkuScheduleDTO::getMaterialCode, Comparator.nullsLast(String::compareTo));
    }

    /**
     * 构建新增SKU比较器，定点物料优先进入新增排产。
     *
     * @param context 排程上下文
     * @param baseComparator 原有SKU比较器
     * @return 新增SKU比较器
     */
    private Comparator<SkuScheduleDTO> buildNewSpecComparator(LhScheduleContext context,
                                                              Comparator<SkuScheduleDTO> baseComparator) {
        return Comparator
                .comparingInt((SkuScheduleDTO sku) -> LhSpecifyMachineUtil.hasLimitSpecifyMachine(
                        context, sku.getMaterialCode()) ? 0 : 1)
                .thenComparing(baseComparator);
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
     * 判断SKU所属结构是否进入“未来结构全收尾”优先级。
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
     * 输出排序后的SKU优先级跟踪日志。
     *
     * @param context 排程上下文
     * @param structurePriorityMap 结构收尾优先级快照
     */
    private void traceSortedSkuList(LhScheduleContext context, Map<String, StructurePriorityMeta> structurePriorityMap) {
        if (!PriorityTraceLogHelper.isEnabled(context)) {
            return;
        }
        String currentStep = context.getCurrentStep();
        String title;
        List<SkuScheduleDTO> traceSkuList;
        if (StringUtils.equals(ScheduleStepEnum.S4_4_CONTINUOUS_PRODUCTION.getCode(), currentStep)) {
            title = "续作SKU排序明细";
            traceSkuList = context.getContinuousSkuList();
        } else if (StringUtils.equals(ScheduleStepEnum.S4_5_NEW_PRODUCTION.getCode(), currentStep)) {
            title = "新增SKU排序明细";
            traceSkuList = context.getNewSpecSkuList();
        } else {
            return;
        }
        StringBuilder detailBuilder = new StringBuilder(512);
        PriorityTraceLogHelper.appendLine(detailBuilder,
                "步骤=" + PriorityTraceLogHelper.safeText(currentStep)
                        + ", SKU数=" + PriorityTraceLogHelper.sizeOf(traceSkuList));
        if (CollectionUtils.isEmpty(traceSkuList)) {
            PriorityTraceLogHelper.appendLine(detailBuilder, "无可输出的SKU排序结果");
        } else {
            int index = 1;
            for (SkuScheduleDTO sku : traceSkuList) {
                boolean structureAllEndingPriority = isStructureAllEndingPriority(structurePriorityMap, sku);
                boolean ending = endingJudgmentStrategy.isEnding(context, sku);
                PriorityTraceLogHelper.appendLine(detailBuilder,
                        index++
                                + ". materialCode=" + PriorityTraceLogHelper.safeText(sku.getMaterialCode())
                                + ", 排产类型=" + PriorityTraceLogHelper.safeText(sku.getScheduleType())
                                + ", 结构=" + PriorityTraceLogHelper.safeText(sku.getStructureName())
                                + ", 锁交期=" + PriorityTraceLogHelper.yesNo(sku.isDeliveryLocked())
                                + ", delayDays=" + sku.getDelayDays()
                                + ", 命中结构全收尾优先=" + PriorityTraceLogHelper.yesNo(structureAllEndingPriority)
                                + ", 收尾SKU=" + PriorityTraceLogHelper.yesNo(ending)
                                + ", endingDaysRemaining=" + sku.getEndingDaysRemaining()
                                + ", 高优待排=" + sku.getHighPriorityPendingQty()
                                + ", 周期待排=" + sku.getCycleProductionPendingQty()
                                + ", 中优待排=" + sku.getMidPriorityPendingQty()
                                + ", 常规待排=" + sku.getConventionProductionPendingQty()
                                + ", scheduleOrder=" + sku.getScheduleOrder());
            }
        }
        String detail = detailBuilder.toString().trim();
        log.info("{}\n{}", title, detail);
        PriorityTraceLogHelper.appendProcessLog(context, title, detail);
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
