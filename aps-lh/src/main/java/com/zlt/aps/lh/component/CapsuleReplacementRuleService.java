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
import com.zlt.aps.lh.util.ShiftCapacityResolverUtil;
import com.zlt.aps.lh.util.ShiftFieldUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.IdentityHashMap;
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
 *   <li>本批初始次数取左右模次数最大值，普通双模按单侧生产循环数累计，其他机台沿用现有累计口径；</li>
 *   <li>只有当前次数加扣减前胶囊次数增量严格大于上限时才首次换胶囊，刚好达到上限不触发；</li>
 *   <li>本批首次跨限从候选计划量固定扣减配置值，后续继续累计但不重置、不重复扣减；</li>
 *   <li>L/R整机结果按左右实际量合计一次，结果复制和同班多个结果不得重复累计；</li>
 *   <li>候选预演、选机模拟和产能模拟不得调用本类，避免污染正式运行态。</li>
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

    /** 运行态复合键分隔符 */
    private static final String KEY_SEPARATOR = "::";

    /** 普通双模结果的模台数 */
    private static final int DOUBLE_MOULD_QTY = 2;

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
     * @param scene 调用场景，用于对账日志
     * @return 换胶囊规则收口后的实际排产量
     */
    public int resolveActualPlanQty(LhScheduleContext context,
                                    LhScheduleResult result,
                                    LhShiftConfigVO shift,
                                    int candidateQty,
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
        int beforeUsage = getMachineRuntimeUsage(context, physicalMachineCode);
        // 使用统一胶囊增量口径判断是否跨限，普通双模按单侧生产循环数计算。
        int candidateUsageIncrement = resolveCapsuleUsageIncrement(
                result, normalizedCandidateQty, wholeSingleControlPair);
        boolean thresholdHandled = context.getCapsuleThresholdHandledMachineSet()
                .contains(physicalMachineCode);
        boolean firstThresholdCrossing = !shiftAlreadyReplaced
                && !thresholdHandled
                && beforeUsage + candidateUsageIncrement > usageUpperLimit;
        int actualPlanQty = firstThresholdCrossing
                ? Math.max(0, normalizedCandidateQty - effectiveLossQty)
                : normalizedCandidateQty;

        if (firstThresholdCrossing) {
            // 首次严格跨限后登记物理机台，本批后续班次只累计次数，不再重复扣量或备注。
            context.getCapsuleThresholdHandledMachineSet().add(physicalMachineCode);
            context.getCapsuleReplacementShiftKeySet().add(shiftKey);
            int existingPlanQty = resolveShiftPlanQty(result, shift.getShiftIndex());
            context.getCapsuleReplacementShiftCapacityLimitMap().put(
                    capacityLimitKey, existingPlanQty + actualPlanQty);
            ShiftFieldUtil.appendShiftAnalysis(
                    result, shift.getShiftIndex(), CAPSULE_REPLACEMENT_ANALYSIS);
        }
        // 按扣量后的实际计划量重新计算增量，避免把换胶囊损失计入使用次数。
        int actualUsageIncrement = resolveCapsuleUsageIncrement(
                result, actualPlanQty, wholeSingleControlPair);
        applyActualUsageIncrement(context, physicalMachineCode, actualUsageIncrement);

        if (firstThresholdCrossing) {
            log.info("换胶囊班次计划量收口, batchNo: {}, scheduleDate: {}, scene: {}, materialCode: {}, "
                            + "machineCode: {}, physicalMachineCode: {}, shiftIndex: {}, 当前机台胶囊次数: {}, "
                            + "胶囊上限: {}, 扣减前结果可排量: {}, 候选胶囊次数增量: {}, 配置扣减量: {}, "
                            + "本结果有效扣减量: {}, 实际结果量: {}, 实际胶囊次数增量: {}, "
                            + "本批首次严格跨限: {}, 累计后机台胶囊次数: {}",
                    context.getBatchNo(), LhScheduleTimeUtil.formatDate(context.getScheduleDate()), scene,
                    result.getMaterialCode(), result.getLhMachineCode(), physicalMachineCode,
                    shift.getShiftIndex(), beforeUsage,
                    usageUpperLimit, normalizedCandidateQty, candidateUsageIncrement,
                    configuredLossQty, effectiveLossQty, actualPlanQty, actualUsageIncrement,
                    true, getMachineRuntimeUsage(context, physicalMachineCode));
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
     * <p>重建以基础胶囊次数为起点，按班次、物理机台和结果开始时间顺序累计实际胶囊次数增量。
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
        context.getCapsuleRuntimeUsageMap().clear();
        context.getCapsuleReplacementShiftKeySet().clear();
        context.getCapsuleThresholdHandledMachineSet().clear();

        List<LhScheduleResult> resultList = collectCurrentResults(context, currentResult);
        List<LhShiftConfigVO> shifts = resolveScheduleShifts(context);
        Set<String> validReplacementShiftKeySet = new LinkedHashSet<String>();

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
                if (replacementMarked) {
                    // 结果备注是本批已执行首次跨限扣量的事实，重建时恢复机台级处理状态。
                    context.getCapsuleThresholdHandledMachineSet().add(physicalMachineCode);
                    if (context.getCapsuleReplacementShiftKeySet().add(shiftKey)) {
                        validReplacementShiftKeySet.add(shiftKey);
                    }
                }
                // 重建与正式落班复用同一胶囊增量口径，防止后置缩量后运行态漂移。
                int actualUsageIncrement = resolveCapsuleUsageIncrement(
                        result, actualQty, wholeSingleControlPair);
                applyActualUsageIncrement(context, physicalMachineCode, actualUsageIncrement);
            }
        }
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
     * 清理同一物理机台在本批排程中的重复“换胶囊”备注。
     *
     * <p>首次严格跨限只允许扣减和备注一次。该核对只规范备注，不修改班次计划量；按班次、
     * 结果开始时间顺序保留第一条备注，清理L/R复制、结果合并或后置处理产生的重复备注。</p>
     *
     * @param context 排程上下文
     * @return 清理的重复备注数量
     */
    private int removeDuplicateReplacementAnalysis(LhScheduleContext context) {
        if (Objects.isNull(context) || CollectionUtils.isEmpty(context.getScheduleResultList())) {
            return 0;
        }
        Set<String> markedPhysicalMachineSet = new LinkedHashSet<String>();
        int removedCount = 0;
        for (LhShiftConfigVO shift : resolveScheduleShifts(context)) {
            if (Objects.isNull(shift) || Objects.isNull(shift.getShiftIndex())) {
                continue;
            }
            List<LhScheduleResult> orderedResults = resolveOrderedShiftResults(
                    context.getScheduleResultList(), shift.getShiftIndex());
            for (LhScheduleResult result : orderedResults) {
                if (Objects.isNull(result) || StringUtils.isEmpty(result.getLhMachineCode())
                        || !containsReplacementAnalysis(result, shift.getShiftIndex())) {
                    continue;
                }
                String physicalMachineCode = LhSingleControlMachineUtil.resolvePhysicalMachineCode(
                        result.getLhMachineCode());
                if (markedPhysicalMachineSet.add(physicalMachineCode)) {
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
     * 获取指定物理机台的当前胶囊运行态次数，供单元测试和诊断使用。
     *
     * @param context 排程上下文
     * @param machineCode 运行态或物理机台编码
     * @return 当前物理机台胶囊使用次数
     */
    public int getMachineRuntimeUsage(LhScheduleContext context, String machineCode) {
        if (Objects.isNull(context) || StringUtils.isEmpty(machineCode)) {
            return 0;
        }
        String physicalMachineCode = LhSingleControlMachineUtil.resolvePhysicalMachineCode(machineCode);
        return context.getCapsuleRuntimeUsageMap().getOrDefault(physicalMachineCode, 0);
    }

    /**
     * 按当前结果对应的胶囊次数增量累计，首次跨限后也不减去上限。
     *
     * @param context 排程上下文
     * @param physicalMachineCode 物理机台编码
     * @param actualUsageIncrement 本次实际胶囊次数增量
     */
    private void applyActualUsageIncrement(LhScheduleContext context,
                                           String physicalMachineCode,
                                           int actualUsageIncrement) {
        int normalizedIncrement = Math.max(0, actualUsageIncrement);
        context.getCapsuleRuntimeUsageMap().merge(
                physicalMachineCode, normalizedIncrement, Integer::sum);
    }

    /**
     * 将结果计划量转换为胶囊使用次数增量。
     * <p>普通双模一个生产循环同时生产两条，单个胶囊只累计其中一侧对应的循环次数；
     * 奇数计划量复用现有模台数向上收敛规则后再折半，等价于取左右侧实际次数的较大值。
     * 普通单模直接按结果量累计；L/R整机配对结果按单侧保存，需要乘以2还原既有物理机台
     * 累计口径；L/R独立排产仍由两侧结果分别进入规则并自然累加。</p>
     *
     * @param result 当前排程结果
     * @param resultPlanQty 当前结果计划量
     * @param wholeSingleControlPair 是否为L/R整机配对结果
     * @return 本次胶囊使用次数增量
     */
    private int resolveCapsuleUsageIncrement(LhScheduleResult result,
                                             int resultPlanQty,
                                             boolean wholeSingleControlPair) {
        int normalizedPlanQty = Math.max(0, resultPlanQty);
        if (wholeSingleControlPair) {
            return normalizedPlanQty * DOUBLE_MOULD_QTY;
        }
        if (isOrdinaryDoubleMouldResult(result)) {
            int normalizedDoubleMouldQty = ShiftCapacityResolverUtil.roundUpQtyToMouldMultiple(
                    normalizedPlanQty, DOUBLE_MOULD_QTY);
            return normalizedDoubleMouldQty / DOUBLE_MOULD_QTY;
        }
        return normalizedPlanQty;
    }

    /**
     * 判断当前结果是否为普通双模机台结果。
     * <p>单控运行态机台以L/R结尾，必须继续沿用单控累计口径；只有非单控且结果模台数
     * 明确为2时，才应用普通双模单侧循环次数规则，不扩大到普通单模或多模结果。</p>
     *
     * @param result 当前排程结果
     * @return true-普通双模结果；false-其他机台结果
     */
    private boolean isOrdinaryDoubleMouldResult(LhScheduleResult result) {
        return Objects.nonNull(result)
                && !LhSingleControlMachineUtil.isSingleMouldMachine(result.getLhMachineCode())
                && Objects.nonNull(result.getMouldQty())
                && result.getMouldQty() == DOUBLE_MOULD_QTY;
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
        if (context.getCapsuleRuntimeUsageMap().containsKey(physicalMachineCode)) {
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
        int machineUsage = Math.max(positionOneUsage, positionTwoUsage);
        context.getCapsuleRuntimeUsageMap().put(physicalMachineCode, machineUsage);
        if (machineUsage >= resolveUsageUpperLimit(context)) {
            /*
             * 初始快照已经达到或超过上限，说明本批开始前已越过阈值。
             * 按确认口径仅继续累计，不在本批首个生产班次补扣换胶囊产能。
             */
            context.getCapsuleThresholdHandledMachineSet().add(physicalMachineCode);
        }
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

}
