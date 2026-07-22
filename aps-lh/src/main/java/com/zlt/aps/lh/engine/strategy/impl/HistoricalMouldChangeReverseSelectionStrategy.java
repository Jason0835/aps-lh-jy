package com.zlt.aps.lh.engine.strategy.impl;

import com.zlt.aps.lh.api.domain.dto.MachineScheduleDTO;
import com.zlt.aps.lh.api.domain.dto.SkuScheduleDTO;
import com.zlt.aps.lh.api.domain.entity.LhMouldChangePlan;
import com.zlt.aps.lh.api.domain.entity.LhScheduleResult;
import com.zlt.aps.lh.api.domain.vo.LhShiftConfigVO;
import com.zlt.aps.lh.api.enums.MouldChangeTypeEnum;
import com.zlt.aps.lh.component.MonthPlanDateResolver;
import com.zlt.aps.lh.component.TargetScheduleQtyResolver;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.engine.strategy.IHistoricalMouldChangeReverseSelectionStrategy;
import com.zlt.aps.lh.engine.strategy.IMachineMatchStrategy;
import com.zlt.aps.lh.engine.strategy.ITypeBlockProductionStrategy;
import com.zlt.aps.lh.engine.strategy.support.HistoricalReverseSelectionDirective;
import com.zlt.aps.lh.engine.strategy.support.SpecifiedMachineMatchResult;
import com.zlt.aps.lh.engine.strategy.support.SpecifiedMachineScheduleResult;
import com.zlt.aps.lh.util.LhScheduleTimeUtil;
import com.zlt.aps.lh.util.PriorityTraceLogHelper;
import com.zlt.aps.lh.util.ShiftFieldUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 前日模具交替计划机台反选SKU策略实现。
 *
 * <p>业务边界：</p>
 * <ul>
 *   <li>只读取前一业务目标日班次4、5的换模/换活字块计划；</li>
 *   <li>固定历史机台和后物料关系，班次映射仅用于识别和首次尝试，不继承历史具体时间和历史交替类型；</li>
 *   <li>当前状态实际满足换活字块时立即委托S4.4既有换活字块主链；</li>
 *   <li>其余指令登记给S4.5既有新增换模主链，在普通候选排序前优先尝试指定机台；</li>
 *   <li>任何失败都不覆盖前序结果、不提前写未排，SKU继续按普通新增规则竞争。</li>
 * </ul>
 */
@Slf4j
@Component
public class HistoricalMouldChangeReverseSelectionStrategy
        implements IHistoricalMouldChangeReverseSelectionStrategy {

    /** 历史窗口首个反选班次 */
    private static final int HISTORICAL_SHIFT_FOUR = 4;

    /** 历史窗口第二个反选班次 */
    private static final int HISTORICAL_SHIFT_FIVE = 5;

    /** 历史班次4映射到当前早班 */
    private static final int CURRENT_SHIFT_ONE = 1;

    /** 历史班次5映射到当前中班 */
    private static final int CURRENT_SHIFT_TWO = 2;

    /** 空产品状态按正规状态归一化 */
    private static final String FORMAL_PRODUCT_STATUS = "S";

    @Resource
    private IMachineMatchStrategy machineMatchStrategy;

    @Resource
    private ITypeBlockProductionStrategy typeBlockProductionStrategy;

    @Resource
    private TargetScheduleQtyResolver targetScheduleQtyResolver;

    /**
     * 执行前日交替计划机台反选。
     *
     * @param context 排程上下文
     */
    @Override
    public void reverseSelect(LhScheduleContext context) {
        List<HistoricalReverseSelectionDirective> directives = buildDirectives(context);
        context.setHistoricalReverseSelectionDirectiveList(directives);
        if (CollectionUtils.isEmpty(directives)) {
            log.info("前日交替计划机台反选结束, factoryCode: {}, scheduleTargetDate: {}, 有效指令数: 0",
                    context.getFactoryCode(),
                    LhScheduleTimeUtil.formatDate(context.getScheduleTargetDate()));
            return;
        }

        Map<String, SkuScheduleDTO> selectedSkuMap =
                new LinkedHashMap<String, SkuScheduleDTO>(directives.size());
        for (HistoricalReverseSelectionDirective directive : directives) {
            processDirective(context, directive, selectedSkuMap);
        }
        reorderPendingSkuByDirective(context, directives, selectedSkuMap);
        int successCount = 0;
        int alreadySatisfiedCount = 0;
        int pendingRegularCount = 0;
        int failureCount = 0;
        for (HistoricalReverseSelectionDirective directive : directives) {
            if (directive.isAlreadySatisfied()) {
                alreadySatisfiedCount++;
            } else if (directive.isSuccess()) {
                successCount++;
            } else if (!directive.isAttempted()) {
                pendingRegularCount++;
            } else {
                failureCount++;
            }
        }
        log.info("前日交替计划机台反选编排完成, factoryCode: {}, scheduleTargetDate: {}, "
                        + "指令数: {}, 换活字块成功: {}, 前序已满足: {}, 待新增指定机台: {}, 失败: {}",
                context.getFactoryCode(), LhScheduleTimeUtil.formatDate(context.getScheduleTargetDate()),
                directives.size(), successCount, alreadySatisfiedCount, pendingRegularCount, failureCount);
    }

    /**
     * 解析、过滤、排序并去重历史计划。
     *
     * @param context 排程上下文
     * @return 反选指令列表
     */
    private List<HistoricalReverseSelectionDirective> buildDirectives(LhScheduleContext context) {
        List<LhMouldChangePlan> historyPlans = context.getHistoricalReverseMouldChangePlanList();
        if (CollectionUtils.isEmpty(historyPlans)) {
            return new ArrayList<HistoricalReverseSelectionDirective>(0);
        }
        Date historicalWindowBase = LhScheduleTimeUtil.addDays(context.getScheduleDate(), -1);
        List<HistoricalReverseSelectionDirective> candidates =
                new ArrayList<HistoricalReverseSelectionDirective>(historyPlans.size());
        for (LhMouldChangePlan plan : historyPlans) {
            if (Objects.isNull(plan) || Objects.isNull(plan.getPlanDate())
                    || StringUtils.isEmpty(plan.getLhMachineCode())
                    || StringUtils.isEmpty(plan.getAfterMaterialCode())) {
                logReverseDecision(context, plan, -1, "失败",
                        "历史计划缺少交替时间、机台编码或后物料，无法可靠还原班次");
                continue;
            }
            int historicalShiftIndex = resolveHistoricalShiftIndex(
                    context, historicalWindowBase, plan.getPlanDate());
            if (historicalShiftIndex != HISTORICAL_SHIFT_FOUR
                    && historicalShiftIndex != HISTORICAL_SHIFT_FIVE) {
                continue;
            }
            HistoricalReverseSelectionDirective directive =
                    new HistoricalReverseSelectionDirective();
            directive.setHistoricalPlan(plan);
            directive.setHistoricalShiftIndex(historicalShiftIndex);
            directive.setMappedShiftIndex(historicalShiftIndex == HISTORICAL_SHIFT_FOUR
                    ? CURRENT_SHIFT_ONE : CURRENT_SHIFT_TWO);
            directive.setMachineCode(plan.getLhMachineCode());
            directive.setMaterialCode(plan.getAfterMaterialCode());
            candidates.add(directive);
        }
        candidates.sort(buildDirectiveComparator());
        return removeDuplicateAndConflictDirectives(context, candidates);
    }

    /**
     * 按历史窗口起点还原计划班次。
     *
     * <p>{@link LhScheduleTimeUtil#getScheduleShifts(LhScheduleContext, Date)} 在当前上下文已加载
     * 班次窗口后会直接返回当前窗口，忽略传入的历史日期。因此优先把当前实际班次窗口整体前移一天，
     * 既保留数据库班次配置，又避免把历史班次4、5识别成当前班次7、8；测试或未加载窗口时再按同一
     * 排程参数构建历史默认模板。</p>
     *
     * @param context 排程上下文
     * @param historicalWindowBase 历史排程窗口起点
     * @param planDate 历史交替时间
     * @return 历史班次序号；不在窗口内返回-1
     */
    private int resolveHistoricalShiftIndex(LhScheduleContext context,
                                            Date historicalWindowBase,
                                            Date planDate) {
        if (Objects.isNull(planDate)) {
            return -1;
        }
        List<LhShiftConfigVO> referenceShifts = context.getScheduleWindowShifts();
        boolean shiftCurrentWindowBackOneDay = !CollectionUtils.isEmpty(referenceShifts);
        if (!shiftCurrentWindowBackOneDay) {
            referenceShifts = LhScheduleTimeUtil.buildDefaultScheduleShifts(
                    context, historicalWindowBase);
        }
        for (LhShiftConfigVO shift : referenceShifts) {
            if (Objects.isNull(shift) || Objects.isNull(shift.getShiftStartDateTime())
                    || Objects.isNull(shift.getShiftEndDateTime())) {
                continue;
            }
            Date historicalShiftStart = shiftCurrentWindowBackOneDay
                    ? LhScheduleTimeUtil.addDays(shift.getShiftStartDateTime(), -1)
                    : shift.getShiftStartDateTime();
            Date historicalShiftEnd = shiftCurrentWindowBackOneDay
                    ? LhScheduleTimeUtil.addDays(shift.getShiftEndDateTime(), -1)
                    : shift.getShiftEndDateTime();
            if (!planDate.before(historicalShiftStart)
                    && planDate.before(historicalShiftEnd)) {
                return shift.getShiftIndex();
            }
        }
        return -1;
    }

    /**
     * 构建历史指令稳定排序器。
     *
     * @return 班次、交替时间、机台、顺位和主键排序器
     */
    private Comparator<HistoricalReverseSelectionDirective> buildDirectiveComparator() {
        return Comparator.comparingInt(HistoricalReverseSelectionDirective::getHistoricalShiftIndex)
                .thenComparing(directive -> directive.getHistoricalPlan().getPlanDate())
                .thenComparing(HistoricalReverseSelectionDirective::getMachineCode,
                        Comparator.nullsLast(String::compareTo))
                .thenComparing(directive -> directive.getHistoricalPlan().getPlanOrder(),
                        Comparator.nullsLast(Integer::compareTo))
                .thenComparing(directive -> directive.getHistoricalPlan().getId(),
                        Comparator.nullsLast(Long::compareTo));
    }

    /**
     * 处理重复和冲突历史计划。
     *
     * @param context 排程上下文
     * @param candidates 已排序指令
     * @return 可执行指令
     */
    private List<HistoricalReverseSelectionDirective> removeDuplicateAndConflictDirectives(
            LhScheduleContext context,
            List<HistoricalReverseSelectionDirective> candidates) {
        List<HistoricalReverseSelectionDirective> result =
                new ArrayList<HistoricalReverseSelectionDirective>(candidates.size());
        Set<String> duplicateKeySet = new LinkedHashSet<String>(candidates.size());
        Map<String, String> sameTimeMachineMaterialMap =
                new LinkedHashMap<String, String>(candidates.size());
        for (HistoricalReverseSelectionDirective directive : candidates) {
            String duplicateKey = buildDirectiveKey(directive);
            if (!duplicateKeySet.add(duplicateKey)) {
                logReverseDecision(context, directive.getHistoricalPlan(),
                        directive.getHistoricalShiftIndex(), "跳过", "重复历史交替计划");
                continue;
            }
            String conflictKey = directive.getHistoricalShiftIndex() + "|"
                    + directive.getMachineCode() + "|"
                    + directive.getHistoricalPlan().getPlanDate().getTime();
            String firstMaterial = sameTimeMachineMaterialMap.get(conflictKey);
            if (StringUtils.isNotEmpty(firstMaterial)
                    && !StringUtils.equals(firstMaterial, directive.getMaterialCode())) {
                directive.setAttempted(true);
                directive.setResultReason("同机台同一交替时刻存在不同后物料冲突，按计划顺位和主键保留第一条");
                logReverseDecision(context, directive.getHistoricalPlan(),
                        directive.getHistoricalShiftIndex(), "失败", directive.getResultReason());
                continue;
            }
            sameTimeMachineMaterialMap.put(conflictKey, directive.getMaterialCode());
            result.add(directive);
        }
        return result;
    }

    /**
     * 处理单条反选指令。
     *
     * @param context 排程上下文
     * @param directive 反选指令
     * @param selectedSkuMap 指令与当前SKU映射
     */
    private void processDirective(LhScheduleContext context,
                                  HistoricalReverseSelectionDirective directive,
                                  Map<String, SkuScheduleDTO> selectedSkuMap) {
        LhScheduleResult satisfiedResult = findSatisfiedResult(context, directive);
        if (Objects.nonNull(satisfiedResult)) {
            directive.setAttempted(true);
            directive.setSuccess(true);
            directive.setAlreadySatisfied(true);
            directive.setActualChangeType(resolveResultChangeType(satisfiedResult));
            directive.setResultReason("续作或换活字块前序排产已满足历史机台与后物料关系");
            context.protectHistoricalReverseResult(satisfiedResult);
            registerSelectedMachine(context, directive, satisfiedResult.getProductStatus());
            repairSatisfiedResultContext(context, satisfiedResult);
            logReverseDecision(context, directive.getHistoricalPlan(),
                    directive.getHistoricalShiftIndex(), "已满足", directive.getResultReason());
            return;
        }

        SkuScheduleDTO sku = selectCurrentSku(context, directive.getMaterialCode());
        if (Objects.isNull(sku)) {
            directive.setAttempted(true);
            directive.setResultReason("后物料不存在可消费待排量或目标量");
            logReverseDecision(context, directive.getHistoricalPlan(),
                    directive.getHistoricalShiftIndex(), "失败", directive.getResultReason());
            return;
        }
        String productStatus = normalizeProductStatus(sku.getProductStatus());
        directive.setProductStatus(productStatus);
        directive.setSkuSortRank(sku.getSortRank());
        selectedSkuMap.put(buildDirectiveKey(directive), sku);

        MachineScheduleDTO historicalMachine =
                context.getMachineScheduleMap().get(directive.getMachineCode());
        if (Objects.isNull(historicalMachine)) {
            directive.setAttempted(true);
            directive.setResultReason("本批次不存在历史指定机台");
            logReverseDecision(context, directive.getHistoricalPlan(),
                    directive.getHistoricalShiftIndex(), "失败", directive.getResultReason());
            return;
        }
        /*
         * 先按当前机台物料关系判断换活字块。换活字块使用的是机台当前在机模具，不能先套用
         * 普通新增“全局已占用模具”过滤，否则会把本机正在使用且可更换活字块的模具误判为冲突。
         */
        SpecifiedMachineScheduleResult typeBlockResult =
                typeBlockProductionStrategy.tryScheduleSpecifiedMachine(
                        context, historicalMachine, sku, directive.getMappedShiftIndex());
        if (!typeBlockResult.isApplicable()) {
            SpecifiedMachineMatchResult machineMatchResult =
                    machineMatchStrategy.matchSpecifiedMachine(context, sku, directive.getMachineCode());
            if (!machineMatchResult.isSuccess()) {
                directive.setAttempted(true);
                directive.setResultReason(machineMatchResult.getFailureReason());
                logReverseDecision(context, directive.getHistoricalPlan(),
                        directive.getHistoricalShiftIndex(), "失败", directive.getResultReason());
                return;
            }
            directive.setEffectiveMachineCode(machineMatchResult.getMachine().getMachineCode());
            directive.setActualChangeType(MouldChangeTypeEnum.REGULAR.getCode());
            directive.setResultReason("当前状态需走新增换模，已登记指定机台并等待S4.5主链执行");
            logReverseDecision(context, directive.getHistoricalPlan(),
                    directive.getHistoricalShiftIndex(), "待新增指定机台", directive.getResultReason());
            return;
        }
        directive.setAttempted(true);
        directive.setActualChangeType(typeBlockResult.getActualChangeType());
        directive.setSuccess(typeBlockResult.isSuccess());
        directive.setResultReason(typeBlockResult.getReason());
        if (typeBlockResult.isSuccess()) {
            context.protectHistoricalReverseResult(typeBlockResult.getScheduleResult());
            registerSelectedMachine(context, directive, productStatus);
        }
        logReverseDecision(context, directive.getHistoricalPlan(), directive.getHistoricalShiftIndex(),
                typeBlockResult.isSuccess() ? "成功" : "失败", directive.getResultReason());
    }

    /**
     * 从当前待排队列中选择同后物料且仍有实际余量的最高优先级状态。
     *
     * @param context 排程上下文
     * @param materialCode 后物料编码
     * @return 当前优先级最高且有余量的SKU；没有返回null
     */
    private SkuScheduleDTO selectCurrentSku(LhScheduleContext context, String materialCode) {
        for (SkuScheduleDTO sku : context.getNewSpecSkuList()) {
            if (Objects.nonNull(sku) && StringUtils.equals(materialCode, sku.getMaterialCode())
                    && targetScheduleQtyResolver.resolveProductionRemainingQty(context, sku) > 0) {
                return sku;
            }
        }
        return null;
    }

    /**
     * 补齐前序已满足结果的机台分配和来源SKU上下文。
     *
     * <p>续作、换活字块正常落地时通常已经完成这些登记；这里做幂等补齐，避免历史继承结果或
     * 特殊前序分支只写结果列表、未写运行态Map。该方法不创建新结果、不消费账本，也不伪造交替计划。</p>
     *
     * @param context 排程上下文
     * @param satisfiedResult 前序已满足结果
     */
    private void repairSatisfiedResultContext(LhScheduleContext context,
                                              LhScheduleResult satisfiedResult) {
        List<LhScheduleResult> machineAssignments = context.getMachineAssignmentMap()
                .computeIfAbsent(satisfiedResult.getLhMachineCode(),
                        key -> new ArrayList<LhScheduleResult>(2));
        boolean assignmentExists = false;
        for (LhScheduleResult assignment : machineAssignments) {
            if (assignment == satisfiedResult) {
                assignmentExists = true;
                break;
            }
        }
        if (!assignmentExists) {
            machineAssignments.add(satisfiedResult);
        }
        if (context.getScheduleResultSourceSkuMap().containsKey(satisfiedResult)) {
            return;
        }
        String skuKey = MonthPlanDateResolver.buildMaterialStatusKey(
                satisfiedResult.getMaterialCode(), satisfiedResult.getProductStatus());
        SkuScheduleDTO sourceSku = context.getAllSkuScheduleDtoMap().get(skuKey);
        if (Objects.isNull(sourceSku)) {
            sourceSku = findSkuByMaterialAndStatus(
                    context.getNewSpecSkuList(), satisfiedResult.getMaterialCode(),
                    satisfiedResult.getProductStatus());
        }
        if (Objects.isNull(sourceSku)) {
            sourceSku = findSkuByMaterialAndStatus(
                    context.getContinuousSkuList(), satisfiedResult.getMaterialCode(),
                    satisfiedResult.getProductStatus());
        }
        if (Objects.nonNull(sourceSku)) {
            context.getScheduleResultSourceSkuMap().put(satisfiedResult, sourceSku);
        }
    }

    /**
     * 在SKU列表中按物料和归一化产品状态查找来源。
     *
     * @param skuList SKU列表
     * @param materialCode 物料编码
     * @param productStatus 产品状态
     * @return 来源SKU；没有返回null
     */
    private SkuScheduleDTO findSkuByMaterialAndStatus(List<SkuScheduleDTO> skuList,
                                                      String materialCode,
                                                      String productStatus) {
        if (CollectionUtils.isEmpty(skuList)) {
            return null;
        }
        for (SkuScheduleDTO sku : skuList) {
            if (Objects.nonNull(sku)
                    && StringUtils.equals(materialCode, sku.getMaterialCode())
                    && StringUtils.equals(normalizeProductStatus(productStatus),
                    normalizeProductStatus(sku.getProductStatus()))) {
                return sku;
            }
        }
        return null;
    }

    /**
     * 查找前序排产已经满足“指定机台+后物料+映射班次”的结果。
     *
     * @param context 排程上下文
     * @param directive 反选指令
     * @return 已满足结果；没有返回null
     */
    private LhScheduleResult findSatisfiedResult(LhScheduleContext context,
                                                 HistoricalReverseSelectionDirective directive) {
        for (LhScheduleResult result : context.getScheduleResultList()) {
            if (Objects.isNull(result)
                    || !StringUtils.equals(directive.getMachineCode(), result.getLhMachineCode())
                    || !StringUtils.equals(directive.getMaterialCode(), result.getMaterialCode())) {
                continue;
            }
            Integer shiftPlanQty = ShiftFieldUtil.getShiftPlanQty(result, directive.getMappedShiftIndex());
            if (Objects.nonNull(shiftPlanQty) && shiftPlanQty > 0) {
                return result;
            }
            if (Objects.nonNull(result.getMouldChangeStartTime())
                    && LhScheduleTimeUtil.getShiftIndex(
                    context, context.getScheduleDate(), result.getMouldChangeStartTime())
                    == directive.getMappedShiftIndex()) {
                return result;
            }
        }
        return null;
    }

    /**
     * 按历史指令顺序把待处理SKU提前，普通SKU之间原有排序保持不变。
     *
     * @param context 排程上下文
     * @param directives 反选指令
     * @param selectedSkuMap 指令与SKU映射
     */
    private void reorderPendingSkuByDirective(
            LhScheduleContext context,
            List<HistoricalReverseSelectionDirective> directives,
            Map<String, SkuScheduleDTO> selectedSkuMap) {
        List<SkuScheduleDTO> original = context.getNewSpecSkuList();
        if (CollectionUtils.isEmpty(original)) {
            return;
        }
        List<SkuScheduleDTO> reordered = new ArrayList<SkuScheduleDTO>(original.size());
        Set<SkuScheduleDTO> added = java.util.Collections.newSetFromMap(
                new IdentityHashMap<SkuScheduleDTO, Boolean>());
        for (HistoricalReverseSelectionDirective directive : directives) {
            if (directive.isAttempted()) {
                continue;
            }
            SkuScheduleDTO sku = selectedSkuMap.get(buildDirectiveKey(directive));
            if (Objects.nonNull(sku) && added.add(sku)) {
                reordered.add(sku);
            }
        }
        for (SkuScheduleDTO sku : original) {
            if (added.add(sku)) {
                reordered.add(sku);
            }
        }
        original.clear();
        original.addAll(reordered);
        context.rebuildStructureSkuMapFromPending(original);
    }

    /**
     * 登记反选成功关系并保护结果。
     *
     * @param context 排程上下文
     * @param directive 反选指令
     * @param productStatus 当前实际产品状态
     */
    private void registerSelectedMachine(LhScheduleContext context,
                                         HistoricalReverseSelectionDirective directive,
                                         String productStatus) {
        context.registerHistoricalReverseSelectedMachine(
                directive.getMaterialCode(), normalizeProductStatus(productStatus),
                directive.getMachineCode());
    }

    /**
     * 解析结果实际交替类型。
     *
     * @param result 排程结果
     * @return 01-换模，02-换活字块
     */
    private String resolveResultChangeType(LhScheduleResult result) {
        return StringUtils.equals("1", result.getIsTypeBlock())
                ? MouldChangeTypeEnum.TYPE_BLOCK.getCode() : MouldChangeTypeEnum.REGULAR.getCode();
    }

    /**
     * 构建反选指令幂等键。
     *
     * @param directive 反选指令
     * @return 历史班次+机台+后物料复合键
     */
    private String buildDirectiveKey(HistoricalReverseSelectionDirective directive) {
        return directive.getHistoricalShiftIndex() + "|"
                + directive.getMachineCode() + "|" + directive.getMaterialCode();
    }

    /**
     * 产品状态归一化。
     *
     * @param productStatus 原产品状态
     * @return 非空产品状态；空值按正规S处理
     */
    private String normalizeProductStatus(String productStatus) {
        return StringUtils.isEmpty(productStatus)
                ? FORMAL_PRODUCT_STATUS : productStatus;
    }

    /**
     * 输出应用日志和排程过程日志。
     *
     * @param context 排程上下文
     * @param plan 历史计划
     * @param historicalShiftIndex 历史班次
     * @param result 结果状态
     * @param reason 结果说明
     */
    private void logReverseDecision(LhScheduleContext context,
                                    LhMouldChangePlan plan,
                                    int historicalShiftIndex,
                                    String result,
                                    String reason) {
        String machineCode = Objects.nonNull(plan) ? plan.getLhMachineCode() : null;
        String materialCode = Objects.nonNull(plan) ? plan.getAfterMaterialCode() : null;
        int mappedShiftIndex = historicalShiftIndex == HISTORICAL_SHIFT_FOUR
                ? CURRENT_SHIFT_ONE
                : historicalShiftIndex == HISTORICAL_SHIFT_FIVE ? CURRENT_SHIFT_TWO : -1;
        String detail = "scheduleTargetDate="
                + LhScheduleTimeUtil.formatDate(context.getScheduleTargetDate())
                + ", historicalShift=" + historicalShiftIndex
                + ", mappedShift=" + mappedShiftIndex
                + ", machineCode=" + StringUtils.defaultString(machineCode, "-")
                + ", afterMaterialCode=" + StringUtils.defaultString(materialCode, "-")
                + ", selectedSkuSortRank=" + (Objects.nonNull(plan)
                ? resolveDirectiveSortRank(context, plan) : "-")
                + ", result=" + result
                + ", reason=" + StringUtils.defaultString(reason, "-");
        log.info("前日交替计划机台反选, {}", detail);
        PriorityTraceLogHelper.appendProcessLog(context, "前日交替计划机台反选", detail);
    }

    /**
     * 获取当前计划最终绑定SKU的排序序号，仅用于反选日志对账。
     *
     * @param context 排程上下文
     * @param plan 历史计划
     * @return 排序序号文本；未绑定返回-
     */
    private String resolveDirectiveSortRank(LhScheduleContext context,
                                            LhMouldChangePlan plan) {
        for (HistoricalReverseSelectionDirective directive
                : context.getHistoricalReverseSelectionDirectiveList()) {
            if (directive.getHistoricalPlan() == plan
                    && Objects.nonNull(directive.getSkuSortRank())) {
                return String.valueOf(directive.getSkuSortRank());
            }
        }
        return "-";
    }
}
