package com.zlt.aps.lh.component;

import com.zlt.aps.lh.api.constant.LhScheduleConstant;
import com.zlt.aps.lh.api.constant.LhScheduleParamConstant;
import com.zlt.aps.lh.api.domain.dto.MachineScheduleDTO;
import com.zlt.aps.lh.api.domain.entity.LhRepairCapsule;
import com.zlt.aps.lh.api.domain.entity.LhScheduleResult;
import com.zlt.aps.lh.api.domain.vo.LhShiftConfigVO;
import com.zlt.aps.lh.api.enums.SingleControlMachineModeEnum;
import com.zlt.aps.lh.context.LhScheduleConfig;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.util.LhScheduleTimeUtil;
import com.zlt.aps.lh.util.LhSingleControlMachineUtil;
import com.zlt.aps.lh.util.ShiftFieldUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

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
 * 胶囊使用次数与换胶囊班次扣减公共规则。
 *
 * <p>业务口径：</p>
 * <ul>
 *   <li>判断基数必须是停机、清洗、保养、首检、日计划额度、余量和收尾目标等既有规则
 *   收口后的“扣减前实际可排量”；</li>
 *   <li>只有当前次数加扣减前实际可排量严格大于上限时才换胶囊，刚好达到上限不触发；</li>
 *   <li>触发后从当前候选计划量固定扣减配置值，余量账本只消费扣减后的实际排产量；</li>
 *   <li>同一物理机台同一班次只扣减一次，L/R两侧或同班多个结果行不得重复扣减；</li>
 *   <li>胶囊次数只按结果真实计划量累计，候选预演、选机模拟和产能模拟不得调用本类。</li>
 * </ul>
 *
 * <p>本组件不保存批次状态。所有可变状态均放在 {@link LhScheduleContext}，并且每次正式分配前
 * 都可根据当前结果重建，因此胎胚裁剪、降模、特殊材料置换等删除或回滚结果后不会残留旧贡献。</p>
 *
 * @author APS
 */
@Slf4j
@Component
public class CapsuleReplacementRuleService {

    /** 排程结果班次备注 */
    public static final String CAPSULE_REPLACEMENT_ANALYSIS = "换胶囊";

    /** 胶囊位置1，普通单模和普通双模第一侧均使用该位置 */
    private static final String CAPSULE_POSITION_ONE = "1";

    /** 胶囊位置2，普通双模第二侧及单控R侧使用该位置 */
    private static final String CAPSULE_POSITION_TWO = "2";

    /** 运行态复合键分隔符 */
    private static final String KEY_SEPARATOR = "::";

    /**
     * 对正式落班候选量执行换胶囊判断、固定扣减和次数累计。
     *
     * <p>调用位置必须在现有数量约束全部收口之后、班次计划量写入和SKU余量扣账之前。
     * 返回值才是允许写入结果并消费账本的实际排产量。</p>
     *
     * @param context 排程上下文
     * @param result 当前排程结果
     * @param shift 当前班次
     * @param candidateQty 扣减前实际可排量
     * @param mouldQty 当前结果模台数
     * @param scene 调用场景，用于对账日志
     * @return 换胶囊规则收口后的实际排产量
     */
    public int resolveActualPlanQty(LhScheduleContext context,
                                    LhScheduleResult result,
                                    LhShiftConfigVO shift,
                                    int candidateQty,
                                    int mouldQty,
                                    String scene) {
        int normalizedCandidateQty = Math.max(0, candidateQty);
        if (Objects.isNull(context) || Objects.isNull(result) || Objects.isNull(shift)
                || Objects.isNull(shift.getShiftIndex()) || normalizedCandidateQty <= 0
                || StringUtils.isEmpty(result.getLhMachineCode())) {
            return normalizedCandidateQty;
        }

        // 每次正式增量落班前按当前结果重建胶囊运行态，确保后置缩量或置换回滚不会污染本次判断。
        rebuildRuntimeState(context, result);

        String physicalMachineCode = LhSingleControlMachineUtil.resolvePhysicalMachineCode(
                result.getLhMachineCode());
        String shiftKey = buildShiftKey(physicalMachineCode, shift);
        if (StringUtils.isEmpty(shiftKey)) {
            return normalizedCandidateQty;
        }
        initializePhysicalMachineUsage(context, result.getLhMachineCode(), physicalMachineCode);

        int usageUpperLimit = resolveUsageUpperLimit(context);
        int configuredLossQty = resolveChangeLossQty(context);
        boolean wholeSingleControlPair = isWholeSingleControlPairResult(context, result);
        boolean shiftAlreadyReplaced = context.getCapsuleReplacementShiftKeySet().contains(shiftKey);
        String capacityLimitKey = buildCapacityLimitKey(shiftKey, result);
        Integer recordedCapacityLimit = context.getCapsuleReplacementShiftCapacityLimitMap().get(capacityLimitKey);
        if (shiftAlreadyReplaced && Objects.nonNull(recordedCapacityLimit)) {
            /*
             * 同一结果班次首次换胶囊后，后续补量只能使用已扣减上限内的剩余空间。
             * 例如16条候选已收口为14条时，重复补量不得把2条重新补回，也不得再次扣2条。
             */
            int existingPlanQty = resolveShiftPlanQty(result, shift.getShiftIndex());
            normalizedCandidateQty = Math.min(normalizedCandidateQty,
                    Math.max(0, recordedCapacityLimit - existingPlanQty));
            if (normalizedCandidateQty <= 0) {
                return 0;
            }
        }
        int effectiveLossQty = resolveEffectiveLossQty(
                configuredLossQty, wholeSingleControlPair, result.getLhMachineCode());
        Map<String, Integer> candidateIncrementMap = resolveUsageIncrementMap(
                result, normalizedCandidateQty, mouldQty, wholeSingleControlPair);
        String beforeUsageText = formatPhysicalUsage(context, physicalMachineCode);

        Set<String> crossingPositionSet = resolveCrossingPositionSet(
                context, shiftKey, physicalMachineCode, candidateIncrementMap, usageUpperLimit);
        boolean firstReplacementInShift = !shiftAlreadyReplaced && !crossingPositionSet.isEmpty();
        int actualPlanQty = firstReplacementInShift
                ? Math.max(0, normalizedCandidateQty - effectiveLossQty)
                : normalizedCandidateQty;

        if (firstReplacementInShift) {
            context.getCapsuleReplacementShiftKeySet().add(shiftKey);
            int existingPlanQty = resolveShiftPlanQty(result, shift.getShiftIndex());
            context.getCapsuleReplacementShiftCapacityLimitMap().put(
                    capacityLimitKey, existingPlanQty + actualPlanQty);
            ShiftFieldUtil.appendShiftAnalysis(
                    result, shift.getShiftIndex(), CAPSULE_REPLACEMENT_ANALYSIS);
        }
        for (String position : crossingPositionSet) {
            context.getCapsuleReplacementPositionShiftKeySet().add(
                    buildReplacementPositionShiftKey(shiftKey, position));
        }

        Map<String, Integer> actualIncrementMap = resolveUsageIncrementMap(
                result, actualPlanQty, mouldQty, wholeSingleControlPair);
        applyActualUsageIncrement(context, physicalMachineCode,
                actualIncrementMap, usageUpperLimit, crossingPositionSet);

        if (firstReplacementInShift || !crossingPositionSet.isEmpty()) {
            log.info("换胶囊班次计划量收口, batchNo: {}, scheduleDate: {}, scene: {}, materialCode: {}, "
                            + "machineCode: {}, physicalMachineCode: {}, shiftIndex: {}, 当前胶囊次数: {}, "
                            + "胶囊上限: {}, 扣减前实际可排量: {}, 配置扣减量: {}, 本结果有效扣减量: {}, "
                            + "实际排产量: {}, 触发胶囊位置: {}, 是否本班首次扣减: {}, 扣减后胶囊次数: {}",
                    context.getBatchNo(), LhScheduleTimeUtil.formatDate(context.getScheduleDate()), scene,
                    result.getMaterialCode(), result.getLhMachineCode(), physicalMachineCode,
                    shift.getShiftIndex(), beforeUsageText,
                    usageUpperLimit, normalizedCandidateQty, configuredLossQty, effectiveLossQty,
                    actualPlanQty, crossingPositionSet, firstReplacementInShift,
                    formatPhysicalUsage(context, physicalMachineCode));
        }
        return actualPlanQty;
    }

    /**
     * 获取已换胶囊班次在后置处理阶段允许使用的最大产能。
     *
     * <p>续作日标准收敛、班次重分配和收尾补量会根据停机、清洗、保养等规则重新计算
     * “扣除换胶囊前”的班次物理产能。若当前物理机台班次已经触发换胶囊，这些后置入口
     * 必须继续保留首次扣除的固定产能，不能把已扣的2条重新补回。该方法只返回产能上限，
     * 不再次累计胶囊次数、不再次追加备注，也不消费SKU余量账本。</p>
     *
     * <p>调用方传入的 {@code capacityBeforeReplacement} 必须是不含换胶囊损失的理论产能。
     * 普通机台按配置值扣减；L/R整机的结果量按单侧保存，因此沿用正式落班规则折算为
     * 单侧扣减量，确保物理整机仍只损失配置的总产能。</p>
     *
     * @param context 排程上下文
     * @param result 当前排程结果
     * @param shift 当前班次
     * @param capacityBeforeReplacement 扣除换胶囊前的班次理论产能
     * @return 保留换胶囊固定损失后的班次产能上限；未触发换胶囊时原值返回
     */
    public int resolveReplacementShiftCapacityUpperLimit(LhScheduleContext context,
                                                          LhScheduleResult result,
                                                          LhShiftConfigVO shift,
                                                          int capacityBeforeReplacement) {
        int normalizedCapacity = Math.max(0, capacityBeforeReplacement);
        if (Objects.isNull(context) || Objects.isNull(result) || Objects.isNull(shift)
                || Objects.isNull(shift.getShiftIndex())
                || StringUtils.isEmpty(result.getLhMachineCode())) {
            return normalizedCapacity;
        }

        String physicalMachineCode = LhSingleControlMachineUtil.resolvePhysicalMachineCode(
                result.getLhMachineCode());
        String shiftKey = buildShiftKey(physicalMachineCode, shift);
        if (StringUtils.isEmpty(shiftKey)
                || !containsReplacementAnalysisForPhysicalShift(
                        context, result, physicalMachineCode, shift.getShiftIndex())) {
            return normalizedCapacity;
        }

        int effectiveLossQty = resolveEffectiveLossQty(
                resolveChangeLossQty(context), isWholeSingleControlPairResult(context, result),
                result.getLhMachineCode());
        Integer recordedCapacityLimit = context.getCapsuleReplacementShiftCapacityLimitMap().get(
                buildCapacityLimitKey(shiftKey, result));
        if (Objects.nonNull(recordedCapacityLimit)) {
            // 优先复用首次落班记录的精确上限，既不补回损失，也不会把已扣量重复再扣一次。
            return Math.min(normalizedCapacity, Math.max(0, recordedCapacityLimit));
        }
        return Math.max(0, normalizedCapacity - effectiveLossQty);
    }

    /**
     * 使用首次换胶囊时记录的精确上限收口当前结果班次量。
     *
     * <p>该方法只用于所有普通数量修改器执行完成后的最终落班收敛。存在精确记录时，
     * 当前量只能缩小到记录上限；没有精确记录或当前物理班次已无“换胶囊”备注时原值返回。
     * 与理论产能查询不同，本方法不会使用配置值再次扣减，因此不会产生二次扣量。</p>
     *
     * @param context 排程上下文
     * @param result 当前排程结果
     * @param shift 当前班次
     * @param currentPlanQty 当前班次计划量
     * @return 精确上限收口后的班次计划量
     */
    public int limitByRecordedReplacementCapacity(LhScheduleContext context,
                                                   LhScheduleResult result,
                                                   LhShiftConfigVO shift,
                                                   int currentPlanQty) {
        int normalizedPlanQty = Math.max(0, currentPlanQty);
        if (Objects.isNull(context) || Objects.isNull(result) || Objects.isNull(shift)
                || Objects.isNull(shift.getShiftIndex())
                || StringUtils.isEmpty(result.getLhMachineCode())) {
            return normalizedPlanQty;
        }
        String physicalMachineCode = LhSingleControlMachineUtil.resolvePhysicalMachineCode(
                result.getLhMachineCode());
        String shiftKey = buildShiftKey(physicalMachineCode, shift);
        if (StringUtils.isEmpty(shiftKey)
                || !containsReplacementAnalysisForPhysicalShift(
                        context, result, physicalMachineCode, shift.getShiftIndex())) {
            return normalizedPlanQty;
        }
        Integer recordedCapacityLimit = context.getCapsuleReplacementShiftCapacityLimitMap().get(
                buildCapacityLimitKey(shiftKey, result));
        return Objects.isNull(recordedCapacityLimit)
                ? normalizedPlanQty : Math.min(normalizedPlanQty, Math.max(0, recordedCapacityLimit));
    }

    /**
     * 按最终结果备注判断物理机台班次是否已经换胶囊。
     *
     * <p>产能查询会在日标准、降模和补量计算中高频调用，不能在每次查询时重放整个结果集。
     * “换胶囊”备注是班次已执行更换的最终事实标识，因此只需检查当前结果和同物理机台结果；
     * 这样也能自然排除已被删除结果留下的旧运行态键。</p>
     *
     * @param context 排程上下文
     * @param currentResult 当前结果
     * @param physicalMachineCode 物理机台编码
     * @param shiftIndex 班次索引
     * @return true-该物理机台班次已换胶囊；false-未换胶囊
     */
    private boolean containsReplacementAnalysisForPhysicalShift(LhScheduleContext context,
                                                                  LhScheduleResult currentResult,
                                                                  String physicalMachineCode,
                                                                  int shiftIndex) {
        if (containsReplacementAnalysis(currentResult, shiftIndex)) {
            return true;
        }
        if (CollectionUtils.isEmpty(context.getScheduleResultList())) {
            return false;
        }
        for (LhScheduleResult scheduledResult : context.getScheduleResultList()) {
            if (Objects.isNull(scheduledResult) || scheduledResult == currentResult
                    || StringUtils.isEmpty(scheduledResult.getLhMachineCode())) {
                continue;
            }
            String scheduledPhysicalMachineCode = LhSingleControlMachineUtil.resolvePhysicalMachineCode(
                    scheduledResult.getLhMachineCode());
            if (StringUtils.equals(physicalMachineCode, scheduledPhysicalMachineCode)
                    && containsReplacementAnalysis(scheduledResult, shiftIndex)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 根据当前排程结果重建胶囊运行态。
     *
     * <p>重建以基础胶囊次数为起点，按班次、物理机台和结果开始时间顺序累计实际计划量。
     * 结果中的“换胶囊”备注是该班已执行更换的事实标识；同一物理机台同班存在多个结果时
     * 只识别第一条备注。L/R整机结果按一个物理排产组处理，避免左右结果重复累计。</p>
     *
     * @param context 排程上下文
     * @param currentResult 当前尚未加入结果列表但正在分配的结果，可为空
     */
    public void rebuildRuntimeState(LhScheduleContext context, LhScheduleResult currentResult) {
        if (Objects.isNull(context)) {
            return;
        }
        Set<String> previousReplacementPositionSet = new LinkedHashSet<String>(
                context.getCapsuleReplacementPositionShiftKeySet());
        context.getCapsuleRuntimeUsageMap().clear();
        context.getCapsuleReplacementShiftKeySet().clear();

        List<LhScheduleResult> resultList = collectCurrentResults(context, currentResult);
        List<LhShiftConfigVO> shifts = resolveScheduleShifts(context);
        Set<String> validReplacementShiftKeySet = new LinkedHashSet<String>();
        Set<String> rebuiltReplacementPositionSet = new LinkedHashSet<String>();
        Map<String, Set<String>> replacementPositionByShiftKeyMap =
                new LinkedHashMap<String, Set<String>>(8);
        Set<String> appliedReplacementPositionShiftKeySet = new LinkedHashSet<String>();

        for (LhShiftConfigVO shift : shifts) {
            if (Objects.isNull(shift) || Objects.isNull(shift.getShiftIndex())) {
                continue;
            }
            List<LhScheduleResult> orderedShiftResults = resolveOrderedShiftResults(resultList, shift.getShiftIndex());
            Set<String> processedWholePairGroupSet = new LinkedHashSet<String>();
            for (LhScheduleResult result : orderedShiftResults) {
                if (Objects.isNull(result) || StringUtils.isEmpty(result.getLhMachineCode())) {
                    continue;
                }
                int actualQty = resolveShiftPlanQty(result, shift.getShiftIndex());
                boolean replacementMarked = containsReplacementAnalysis(result, shift.getShiftIndex());
                if (actualQty <= 0 && !replacementMarked) {
                    continue;
                }
                String physicalMachineCode = LhSingleControlMachineUtil.resolvePhysicalMachineCode(
                        result.getLhMachineCode());
                boolean wholeSingleControlPair = isWholeSingleControlPairResult(context, result);
                if (wholeSingleControlPair) {
                    String wholePairGroupKey = buildWholePairGroupKey(
                            result, physicalMachineCode, shift.getShiftIndex(), actualQty);
                    if (!processedWholePairGroupSet.add(wholePairGroupKey)) {
                        continue;
                    }
                }
                initializePhysicalMachineUsage(context, result.getLhMachineCode(), physicalMachineCode);
                String shiftKey = buildShiftKey(physicalMachineCode, shift);
                if (StringUtils.isEmpty(shiftKey)) {
                    continue;
                }
                int usageUpperLimit = resolveUsageUpperLimit(context);
                int mouldQty = resolveResultMouldQty(result);
                Set<String> currentReplacementPositionSet = new LinkedHashSet<String>(2);
                Set<String> replacementPositionSet = replacementPositionByShiftKeyMap.get(shiftKey);
                if (replacementMarked && !context.getCapsuleReplacementShiftKeySet().contains(shiftKey)) {
                    context.getCapsuleReplacementShiftKeySet().add(shiftKey);
                    validReplacementShiftKeySet.add(shiftKey);
                    replacementPositionSet = resolvePersistedReplacementPositionSet(
                            previousReplacementPositionSet, shiftKey);
                    if (replacementPositionSet.isEmpty()) {
                        int effectiveLossQty = resolveEffectiveLossQty(
                                resolveChangeLossQty(context), wholeSingleControlPair,
                                result.getLhMachineCode());
                        Map<String, Integer> originalCandidateIncrementMap = resolveUsageIncrementMap(
                                result, actualQty + effectiveLossQty, mouldQty, wholeSingleControlPair);
                        replacementPositionSet.addAll(resolveCrossingPositionSet(
                                context, shiftKey, physicalMachineCode,
                                originalCandidateIncrementMap, usageUpperLimit));
                    }
                    replacementPositionByShiftKeyMap.put(shiftKey, replacementPositionSet);
                    for (String position : replacementPositionSet) {
                        rebuiltReplacementPositionSet.add(
                                buildReplacementPositionShiftKey(shiftKey, position));
                    }
                }
                Map<String, Integer> actualIncrementMap = resolveUsageIncrementMap(
                        result, actualQty, mouldQty, wholeSingleControlPair);
                /*
                 * 同一物理机台同班可能先由L侧触发一次产能扣减，随后R侧才跨越上限。
                 * 备注只能保留一处，因此重建时不能在备注结果上直接重置所有位置；必须等对应侧
                 * 的实际结果出现后再重置，避免把另一侧后续产量继续累计到旧胶囊上。
                 */
                if (!CollectionUtils.isEmpty(replacementPositionSet)) {
                    Map<String, Integer> replayCandidateIncrementMap =
                            new LinkedHashMap<String, Integer>(actualIncrementMap);
                    if (replacementMarked) {
                        int effectiveLossQty = resolveEffectiveLossQty(
                                resolveChangeLossQty(context), wholeSingleControlPair,
                                result.getLhMachineCode());
                        mergeUsageIncrement(replayCandidateIncrementMap, resolveUsageIncrementMap(
                                result, effectiveLossQty, mouldQty, wholeSingleControlPair));
                    }
                    for (String position : replacementPositionSet) {
                        String positionShiftKey = buildReplacementPositionShiftKey(shiftKey, position);
                        if (appliedReplacementPositionShiftKeySet.contains(positionShiftKey)
                                || !actualIncrementMap.containsKey(position)) {
                            continue;
                        }
                        int currentUsage = context.getCapsuleRuntimeUsageMap().getOrDefault(
                                buildUsageKey(physicalMachineCode, position), 0);
                        int replayCandidateIncrement = replayCandidateIncrementMap.getOrDefault(position, 0);
                        if (currentUsage + replayCandidateIncrement > usageUpperLimit) {
                            currentReplacementPositionSet.add(position);
                            appliedReplacementPositionShiftKeySet.add(positionShiftKey);
                        }
                    }
                }
                applyActualUsageIncrement(context, physicalMachineCode,
                        actualIncrementMap, usageUpperLimit, currentReplacementPositionSet);
            }
        }
        context.getCapsuleReplacementPositionShiftKeySet().clear();
        context.getCapsuleReplacementPositionShiftKeySet().addAll(rebuiltReplacementPositionSet);
        context.getCapsuleReplacementShiftKeySet().retainAll(validReplacementShiftKeySet);
    }

    /**
     * 保存前按最终结果重建并输出胶囊规则一致性日志。
     *
     * <p>该方法只核对和重建运行态，不再次扣减班次计划量，避免S4.6重复扣量后余量无法续排。</p>
     *
     * @param context 排程上下文
     */
    public void verifyFinalState(LhScheduleContext context) {
        int duplicateAnalysisCount = removeDuplicateReplacementAnalysis(context);
        rebuildRuntimeState(context, null);
        if (Objects.isNull(context)) {
            return;
        }
        log.info("换胶囊规则最终核对完成, batchNo: {}, scheduleDate: {}, 换胶囊班次数: {}, "
                        + "清理重复备注数: {}, 胶囊运行态: {}",
                context.getBatchNo(), LhScheduleTimeUtil.formatDate(context.getScheduleDate()),
                context.getCapsuleReplacementShiftKeySet().size(), duplicateAnalysisCount,
                context.getCapsuleRuntimeUsageMap());
    }

    /**
     * 清理同一物理机台同一班次的重复“换胶囊”备注。
     *
     * <p>该核对只规范备注，不修改任何班次计划量。正常落班入口本身已经保证幂等；这里用于处理
     * L/R结果复制、结果合并等后置动作可能带来的重复备注，保留遇到的第一条结果备注。</p>
     *
     * @param context 排程上下文
     * @return 清理的重复备注数量
     */
    private int removeDuplicateReplacementAnalysis(LhScheduleContext context) {
        if (Objects.isNull(context) || CollectionUtils.isEmpty(context.getScheduleResultList())) {
            return 0;
        }
        Set<String> markedShiftKeySet = new LinkedHashSet<String>();
        int removedCount = 0;
        for (LhShiftConfigVO shift : resolveScheduleShifts(context)) {
            if (Objects.isNull(shift) || Objects.isNull(shift.getShiftIndex())) {
                continue;
            }
            for (LhScheduleResult result : context.getScheduleResultList()) {
                if (Objects.isNull(result) || StringUtils.isEmpty(result.getLhMachineCode())
                        || !containsReplacementAnalysis(result, shift.getShiftIndex())) {
                    continue;
                }
                String physicalMachineCode = LhSingleControlMachineUtil.resolvePhysicalMachineCode(
                        result.getLhMachineCode());
                String shiftKey = buildShiftKey(physicalMachineCode, shift);
                if (StringUtils.isEmpty(shiftKey) || markedShiftKeySet.add(shiftKey)) {
                    continue;
                }
                ShiftFieldUtil.removeShiftAnalysis(
                        result, shift.getShiftIndex(), CAPSULE_REPLACEMENT_ANALYSIS);
                removedCount++;
            }
        }
        return removedCount;
    }

    /**
     * 获取指定机台胶囊位置的当前运行态次数，供单元测试和诊断使用。
     *
     * @param context 排程上下文
     * @param machineCode 运行态或物理机台编码
     * @param position 胶囊位置，1或2
     * @return 当前运行态使用次数
     */
    public int getRuntimeUsage(LhScheduleContext context, String machineCode, int position) {
        if (Objects.isNull(context) || StringUtils.isEmpty(machineCode)) {
            return 0;
        }
        String physicalMachineCode = LhSingleControlMachineUtil.resolvePhysicalMachineCode(machineCode);
        return context.getCapsuleRuntimeUsageMap().getOrDefault(
                buildUsageKey(physicalMachineCode, String.valueOf(position)), 0);
    }

    private void applyActualUsageIncrement(LhScheduleContext context,
                                           String physicalMachineCode,
                                           Map<String, Integer> actualIncrementMap,
                                           int usageUpperLimit,
                                           Set<String> currentReplacementPositionSet) {
        for (Map.Entry<String, Integer> entry : actualIncrementMap.entrySet()) {
            String position = entry.getKey();
            int actualIncrement = Math.max(0, entry.getValue());
            String usageKey = buildUsageKey(physicalMachineCode, position);
            int currentUsage = context.getCapsuleRuntimeUsageMap().getOrDefault(usageKey, 0);
            boolean replaced = currentReplacementPositionSet.contains(position);
            int newUsage = replaced
                    ? Math.max(0, currentUsage + actualIncrement - usageUpperLimit)
                    : currentUsage + actualIncrement;
            context.getCapsuleRuntimeUsageMap().put(usageKey, newUsage);
        }
    }

    private Set<String> resolveCrossingPositionSet(LhScheduleContext context,
                                                    String shiftKey,
                                                    String physicalMachineCode,
                                                    Map<String, Integer> candidateIncrementMap,
                                                    int usageUpperLimit) {
        Set<String> crossingPositionSet = new LinkedHashSet<String>();
        for (Map.Entry<String, Integer> entry : candidateIncrementMap.entrySet()) {
            String position = entry.getKey();
            String replacementPositionKey = buildReplacementPositionShiftKey(shiftKey, position);
            if (context.getCapsuleReplacementPositionShiftKeySet().contains(replacementPositionKey)) {
                continue;
            }
            int currentUsage = context.getCapsuleRuntimeUsageMap().getOrDefault(
                    buildUsageKey(physicalMachineCode, position), 0);
            if (currentUsage + Math.max(0, entry.getValue()) > usageUpperLimit) {
                crossingPositionSet.add(position);
            }
        }
        return crossingPositionSet;
    }

    private Map<String, Integer> resolveUsageIncrementMap(LhScheduleResult result,
                                                           int planQty,
                                                           int mouldQty,
                                                           boolean wholeSingleControlPair) {
        Map<String, Integer> incrementMap = new LinkedHashMap<String, Integer>(2);
        int normalizedPlanQty = Math.max(0, planQty);
        String machineCode = result.getLhMachineCode();
        if (wholeSingleControlPair && LhSingleControlMachineUtil.isSingleMouldMachine(machineCode)) {
            // L/R整机主结果记录的是单侧数量，配对侧随后复制相同数量；此处一次性累计左右两侧。
            incrementMap.put(CAPSULE_POSITION_ONE, normalizedPlanQty);
            incrementMap.put(CAPSULE_POSITION_TWO, normalizedPlanQty);
            return incrementMap;
        }
        if (LhSingleControlMachineUtil.isSingleMouldMachine(machineCode)) {
            incrementMap.put(LhSingleControlMachineUtil.isLeftSide(machineCode)
                    ? CAPSULE_POSITION_ONE : CAPSULE_POSITION_TWO, normalizedPlanQty);
            return incrementMap;
        }
        if (mouldQty > 1) {
            // 普通双模按两侧实际产出拆分，不能把整机总计划量同时累计到两个胶囊。
            incrementMap.put(CAPSULE_POSITION_ONE, (normalizedPlanQty + 1) / 2);
            incrementMap.put(CAPSULE_POSITION_TWO, normalizedPlanQty / 2);
            return incrementMap;
        }
        incrementMap.put(CAPSULE_POSITION_ONE, normalizedPlanQty);
        return incrementMap;
    }

    /**
     * 将胶囊位置增量合并到重建候选增量中。
     *
     * @param targetIncrementMap 待更新的胶囊位置增量
     * @param addedIncrementMap 需要追加的胶囊位置增量
     */
    private void mergeUsageIncrement(Map<String, Integer> targetIncrementMap,
                                     Map<String, Integer> addedIncrementMap) {
        for (Map.Entry<String, Integer> entry : addedIncrementMap.entrySet()) {
            targetIncrementMap.merge(entry.getKey(), Math.max(0, entry.getValue()), Integer::sum);
        }
    }

    private int resolveEffectiveLossQty(int configuredLossQty,
                                        boolean wholeSingleControlPair,
                                        String machineCode) {
        int normalizedLossQty = Math.max(0, configuredLossQty);
        if (!wholeSingleControlPair || !LhSingleControlMachineUtil.isSingleMouldMachine(machineCode)) {
            return normalizedLossQty;
        }
        /*
         * L/R整机分配循环处理的是单侧数量，配置值是物理整机总扣减量。
         * 为保持两侧等量，奇数配置按整机模数向上归整后再折半；默认2条对应单侧扣1条。
         */
        return (normalizedLossQty + 1) / 2;
    }

    private void initializePhysicalMachineUsage(LhScheduleContext context,
                                                String machineCode,
                                                String physicalMachineCode) {
        String positionOneKey = buildUsageKey(physicalMachineCode, CAPSULE_POSITION_ONE);
        String positionTwoKey = buildUsageKey(physicalMachineCode, CAPSULE_POSITION_TWO);
        if (context.getCapsuleRuntimeUsageMap().containsKey(positionOneKey)
                && context.getCapsuleRuntimeUsageMap().containsKey(positionTwoKey)) {
            return;
        }

        int positionOneUsage = 0;
        int positionTwoUsage = 0;
        Map<String, LhRepairCapsule> capsuleUsageMap = context.getCapsuleUsageMap();
        if (!CollectionUtils.isEmpty(capsuleUsageMap)) {
            LhRepairCapsule physicalCapsule = capsuleUsageMap.get(physicalMachineCode);
            if (LhSingleControlMachineUtil.isSingleMouldMachine(machineCode)) {
                LhRepairCapsule leftCapsule = capsuleUsageMap.get(
                        LhSingleControlMachineUtil.resolveLeftMachineCode(machineCode));
                LhRepairCapsule rightCapsule = capsuleUsageMap.get(
                        LhSingleControlMachineUtil.resolveRightMachineCode(machineCode));
                positionOneUsage = Objects.nonNull(leftCapsule)
                        ? resolveCapsuleCount(leftCapsule, false)
                        : resolveCapsuleCount(physicalCapsule, false);
                positionTwoUsage = Objects.nonNull(rightCapsule)
                        ? resolveCapsuleCount(rightCapsule, false)
                        : resolveCapsuleCount(physicalCapsule, true);
            } else {
                positionOneUsage = resolveCapsuleCount(physicalCapsule, false);
                positionTwoUsage = resolveCapsuleCount(physicalCapsule, true);
            }
        }

        if (positionOneUsage == 0 && positionTwoUsage == 0
                && !CollectionUtils.isEmpty(context.getMachineScheduleMap())) {
            MachineScheduleDTO machine = context.getMachineScheduleMap().get(machineCode);
            if (Objects.isNull(machine)) {
                machine = context.getMachineScheduleMap().get(physicalMachineCode);
            }
            if (Objects.nonNull(machine)) {
                positionOneUsage = Math.max(0, machine.getCapsuleUsageCount());
                positionTwoUsage = Math.max(0, machine.getCapsuleUsageCount2());
            }
        }
        context.getCapsuleRuntimeUsageMap().put(positionOneKey, positionOneUsage);
        context.getCapsuleRuntimeUsageMap().put(positionTwoKey, positionTwoUsage);
    }

    private int resolveCapsuleCount(LhRepairCapsule capsule, boolean secondPosition) {
        if (Objects.isNull(capsule)) {
            return 0;
        }
        Integer usageCount = secondPosition
                ? capsule.getReplaceCapsuleCount2() : capsule.getReplaceCapsuleCount();
        return Objects.isNull(usageCount) ? 0 : Math.max(0, usageCount);
    }

    private int resolveUsageUpperLimit(LhScheduleContext context) {
        LhScheduleConfig scheduleConfig = context.getScheduleConfig();
        return Objects.nonNull(scheduleConfig)
                ? scheduleConfig.getCapsuleUsageUpperLimit()
                : Math.max(1, context.getParamIntValue(
                LhScheduleParamConstant.CAPSULE_FORCE_DOWN_COUNT,
                LhScheduleConstant.CAPSULE_FORCE_DOWN_COUNT));
    }

    private int resolveChangeLossQty(LhScheduleContext context) {
        LhScheduleConfig scheduleConfig = context.getScheduleConfig();
        return Objects.nonNull(scheduleConfig)
                ? scheduleConfig.getCapsuleChangeLossQty()
                : Math.max(0, context.getParamIntValue(
                LhScheduleParamConstant.CAPSULE_CHANGE_LOSS_QTY,
                LhScheduleConstant.CAPSULE_CHANGE_LOSS_QTY));
    }

    private boolean isWholeSingleControlPairResult(LhScheduleContext context, LhScheduleResult result) {
        if (Objects.isNull(context) || Objects.isNull(result)
                || !LhSingleControlMachineUtil.isSingleMouldMachine(result.getLhMachineCode())) {
            return false;
        }
        String skuKey = MonthPlanDateResolver.buildMaterialStatusKey(
                result.getMaterialCode(), result.getProductStatus());
        return SingleControlMachineModeEnum.WHOLE_PAIR
                == context.getSingleControlModeSnapshotMap().get(skuKey);
    }

    private List<LhScheduleResult> collectCurrentResults(LhScheduleContext context,
                                                         LhScheduleResult currentResult) {
        List<LhScheduleResult> resultList = new ArrayList<LhScheduleResult>(
                context.getScheduleResultList().size() + 1);
        Set<LhScheduleResult> identitySet = java.util.Collections.newSetFromMap(
                new IdentityHashMap<LhScheduleResult, Boolean>());
        for (LhScheduleResult result : context.getScheduleResultList()) {
            if (Objects.nonNull(result) && identitySet.add(result)) {
                resultList.add(result);
            }
        }
        if (Objects.nonNull(currentResult) && identitySet.add(currentResult)) {
            resultList.add(currentResult);
        }
        return resultList;
    }

    private List<LhShiftConfigVO> resolveScheduleShifts(LhScheduleContext context) {
        if (!CollectionUtils.isEmpty(context.getScheduleWindowShifts())) {
            return context.getScheduleWindowShifts();
        }
        if (Objects.isNull(context.getScheduleDate())) {
            return new ArrayList<LhShiftConfigVO>(0);
        }
        return LhScheduleTimeUtil.getScheduleShifts(context, context.getScheduleDate());
    }

    private List<LhScheduleResult> resolveOrderedShiftResults(List<LhScheduleResult> resultList,
                                                              int shiftIndex) {
        List<LhScheduleResult> orderedResultList = new ArrayList<LhScheduleResult>(resultList.size());
        for (LhScheduleResult result : resultList) {
            if (Objects.isNull(result)) {
                continue;
            }
            int planQty = resolveShiftPlanQty(result, shiftIndex);
            if (planQty > 0 || containsReplacementAnalysis(result, shiftIndex)) {
                orderedResultList.add(result);
            }
        }
        orderedResultList.sort(Comparator
                .comparing((LhScheduleResult result) -> StringUtils.defaultString(
                        LhSingleControlMachineUtil.resolvePhysicalMachineCode(result.getLhMachineCode())))
                .thenComparing(result -> ShiftFieldUtil.getShiftStartTime(result, shiftIndex),
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(result -> containsReplacementAnalysis(result, shiftIndex) ? 0 : 1)
                .thenComparing(result -> StringUtils.defaultString(result.getLhMachineCode())));
        return orderedResultList;
    }

    private Set<String> resolvePersistedReplacementPositionSet(Set<String> previousPositionSet,
                                                               String shiftKey) {
        Set<String> positionSet = new LinkedHashSet<String>(2);
        String prefix = shiftKey + KEY_SEPARATOR;
        for (String positionShiftKey : previousPositionSet) {
            if (!StringUtils.startsWith(positionShiftKey, prefix)) {
                continue;
            }
            positionSet.add(positionShiftKey.substring(prefix.length()));
        }
        return positionSet;
    }

    private int resolveResultMouldQty(LhScheduleResult result) {
        if (Objects.isNull(result.getMouldQty()) || result.getMouldQty() <= 0) {
            return 1;
        }
        return result.getMouldQty();
    }

    private int resolveShiftPlanQty(LhScheduleResult result, int shiftIndex) {
        Integer planQty = ShiftFieldUtil.getShiftPlanQty(result, shiftIndex);
        return Objects.isNull(planQty) ? 0 : Math.max(0, planQty);
    }

    private boolean containsReplacementAnalysis(LhScheduleResult result, int shiftIndex) {
        String analysis = ShiftFieldUtil.getShiftAnalysis(result, shiftIndex);
        if (StringUtils.isEmpty(analysis)) {
            return false;
        }
        String[] analysisArray = analysis.split(",");
        for (String currentAnalysis : analysisArray) {
            if (StringUtils.equals(StringUtils.trim(currentAnalysis), CAPSULE_REPLACEMENT_ANALYSIS)) {
                return true;
            }
        }
        return false;
    }

    private String buildWholePairGroupKey(LhScheduleResult result,
                                          String physicalMachineCode,
                                          int shiftIndex,
                                          int actualQty) {
        Date startTime = ShiftFieldUtil.getShiftStartTime(result, shiftIndex);
        return physicalMachineCode + KEY_SEPARATOR
                + MonthPlanDateResolver.buildMaterialStatusKey(
                result.getMaterialCode(), result.getProductStatus()) + KEY_SEPARATOR
                + shiftIndex + KEY_SEPARATOR
                + (Objects.isNull(startTime) ? 0L : startTime.getTime()) + KEY_SEPARATOR
                + actualQty;
    }

    private String buildShiftKey(String physicalMachineCode, LhShiftConfigVO shift) {
        if (StringUtils.isEmpty(physicalMachineCode) || Objects.isNull(shift)
                || Objects.isNull(shift.getShiftIndex())) {
            return null;
        }
        Date workDate = shift.getWorkDate();
        String dateText = Objects.isNull(workDate) ? "UNKNOWN" : LhScheduleTimeUtil.formatDate(workDate);
        return physicalMachineCode + KEY_SEPARATOR + dateText + KEY_SEPARATOR + shift.getShiftIndex();
    }

    private String buildUsageKey(String physicalMachineCode, String position) {
        return physicalMachineCode + KEY_SEPARATOR + position;
    }

    private String buildReplacementPositionShiftKey(String shiftKey, String position) {
        return shiftKey + KEY_SEPARATOR + position;
    }

    /**
     * 构建换胶囊结果班次精确产能上限键。
     *
     * @param shiftKey 物理机台班次键
     * @param result 排程结果
     * @return 产能上限业务键
     */
    private String buildCapacityLimitKey(String shiftKey, LhScheduleResult result) {
        return shiftKey + KEY_SEPARATOR
                + StringUtils.defaultString(result.getLhMachineCode()) + KEY_SEPARATOR
                + MonthPlanDateResolver.buildMaterialStatusKey(
                result.getMaterialCode(), result.getProductStatus());
    }

    private String formatPhysicalUsage(LhScheduleContext context, String physicalMachineCode) {
        Map<String, Integer> usageMap = new LinkedHashMap<String, Integer>(2);
        usageMap.put(CAPSULE_POSITION_ONE, context.getCapsuleRuntimeUsageMap().getOrDefault(
                buildUsageKey(physicalMachineCode, CAPSULE_POSITION_ONE), 0));
        usageMap.put(CAPSULE_POSITION_TWO, context.getCapsuleRuntimeUsageMap().getOrDefault(
                buildUsageKey(physicalMachineCode, CAPSULE_POSITION_TWO), 0));
        return usageMap.toString();
    }
}
