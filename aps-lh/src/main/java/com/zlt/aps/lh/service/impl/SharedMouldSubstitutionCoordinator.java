package com.zlt.aps.lh.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.zlt.aps.lh.api.domain.dto.MachineScheduleDTO;
import com.zlt.aps.lh.api.domain.dto.SkuScheduleDTO;
import com.zlt.aps.lh.api.domain.entity.LhScheduleResult;
import com.zlt.aps.lh.api.domain.entity.LhUnscheduledResult;
import com.zlt.aps.lh.api.domain.vo.LhShiftConfigVO;
import com.zlt.aps.lh.api.enums.ScheduleTypeEnum;
import com.zlt.aps.lh.api.enums.SkuScheduleSourceTypeEnum;
import com.zlt.aps.lh.component.MonthPlanDateResolver;
import com.zlt.aps.lh.component.TargetScheduleQtyResolver;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.engine.strategy.support.ContinuationCutoverResult;
import com.zlt.aps.lh.engine.strategy.support.EarlyProductionChecker;
import com.zlt.aps.lh.engine.strategy.support.MouldResourceContext;
import com.zlt.aps.lh.engine.strategy.support.ScheduleSubstitutionDirective;
import com.zlt.aps.lh.engine.strategy.support.SharedMouldSubstitutionPlan;
import com.zlt.aps.lh.engine.strategy.support.SharedMouldSubstitutionRecord;
import com.zlt.aps.lh.util.LhMouldCodeUtil;
import com.zlt.aps.lh.util.LhScheduleTimeUtil;
import com.zlt.aps.lh.util.LhSingleControlMachineUtil;
import com.zlt.aps.lh.util.PriorityTraceLogHelper;
import com.zlt.aps.lh.util.ShiftFieldUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import javax.annotation.Resource;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * SKU 无空闲模具时的共用模具联动置换协调器。
 *
 * <p>核心原则：先证明 B 能携带剩余空闲模具在另一台机台完整承接被截断尾量，
 * 再允许 A 接管 B 的原续作机台和整套共用模具。</p>
 *
 * <p>协调器不重写现有选机和换模算法。每个候选先在通用快照内执行一次完整预演，
 * 预演通过后恢复全部运行态，再锁定预演确认的新机台和模具执行正式提交。
 * 任一步失败均整体恢复，并继续尝试下一台真实续作候选。</p>
 *
 * @author APS
 */
@Slf4j
@Service
public class SharedMouldSubstitutionCoordinator {

    /** 没有满足 A 三个硬门槛的候选。 */
    private static final String NO_ELIGIBLE_CANDIDATE_REASON =
            "未找到同时满足共用模具关系、续作在机实际占用和 B 剩余模具条件的候选";
    /** A 当前仍存在其他空闲有效模具，不允许触发抢占续作机台。 */
    private static final String TARGET_HAS_FREE_MOULD_REASON =
            "A 当前仍存在其他空闲有效模具，不满足共用模具置换前提";
    /** B 迁移必须由一个新物理机台完整承接。 */
    private static final String RELOCATION_NOT_FULL_REASON =
            "B 未能在单个新物理机台完整承接被截断尾量";

    @Resource
    private TargetScheduleQtyResolver targetScheduleQtyResolver;
    @Resource
    private ContinuationCutoverService continuationCutoverService;
    @Resource
    private SpecifiedNewSpecSchedulingService specifiedNewSpecSchedulingService;

    /**
     * 执行全部待排 SKU 的共用模具联动置换。
     *
     * @param context 排程上下文
     */
    public void substitute(LhScheduleContext context) {
        if (Objects.isNull(context)
                || CollectionUtils.isEmpty(context.getUnscheduledResultList())
                || CollectionUtils.isEmpty(
                context.getSpecialMaterialContinuationResultSnapshot())) {
            return;
        }
        List<LhUnscheduledResult> pendingList =
                new ArrayList<LhUnscheduledResult>(context.getUnscheduledResultList());
        int successGroupCount = 0;
        for (LhUnscheduledResult unscheduled : pendingList) {
            if (Objects.isNull(unscheduled)
                    || Objects.isNull(unscheduled.getUnscheduledQty())
                    || unscheduled.getUnscheduledQty() <= 0) {
                continue;
            }
            SkuScheduleDTO targetSku = resolveSku(
                    context, unscheduled.getMaterialCode(),
                    unscheduled.getProductStatus());
            if (Objects.isNull(targetSku)) {
                continue;
            }
            successGroupCount += substituteTargetSku(
                    context, targetSku, unscheduled);
        }
        log.info("共用模具联动置换处理完成, factoryCode: {}, batchNo: {}, successGroupCount: {}",
                context.getFactoryCode(), context.getBatchNo(), successGroupCount);
    }

    /**
     * 对单个物料 A 重复执行候选组置换。
     *
     * @param context 排程上下文
     * @param targetSku 物料 A
     * @param originalUnscheduled A 原未排记录
     * @return 成功置换组数
     */
    private int substituteTargetSku(
            LhScheduleContext context,
            SkuScheduleDTO targetSku,
            LhUnscheduledResult originalUnscheduled) {
        int successGroupCount = 0;
        Set<String> attemptedPhysicalMachineCodeSet =
                new LinkedHashSet<String>(8);
        String finalFailureReason = NO_ELIGIBLE_CANDIDATE_REASON;
        while (targetScheduleQtyResolver.resolveProductionRemainingQty(
                context, targetSku) > 0) {
            SharedMouldPlanDateResolution dateResolution =
                    resolvePlanDate(context, targetSku);
            if (Objects.isNull(dateResolution)) {
                finalFailureReason = "A 在排程窗口及允许提前生产范围内没有正日计划量";
                break;
            }
            Date originalCurrentScheduleDate = context.getCurrentScheduleDate();
            try {
                context.setCurrentScheduleDate(toDate(dateResolution.getTakeoverDate()));
                MouldResourceContext mouldResourceContext =
                        resolveMouldResourceContext(context);
                mouldResourceContext.refreshAvailability(context);
                /*
                 * A 的硬门槛必须在目标日期刷新模具到货状态后判断。
                 * 只要存在一个有效且空闲的模具，就继续执行原新增排产逻辑，禁止抢占 B 的续作机台。
                 */
                if (!CollectionUtils.isEmpty(
                        mouldResourceContext.resolveFreeValidMouldCodes(
                                targetSku.getMaterialCode(),
                                Collections.<String>emptySet()))) {
                    finalFailureReason = TARGET_HAS_FREE_MOULD_REASON;
                    break;
                }
                List<SharedMouldSubstitutionPlan> baseCandidateList =
                        collectBaseCandidates(
                                context, targetSku, dateResolution,
                                attemptedPhysicalMachineCodeSet,
                                mouldResourceContext);
                if (CollectionUtils.isEmpty(baseCandidateList)) {
                    break;
                }
                List<SharedMouldSubstitutionPlan> feasiblePlanList =
                        previewCandidates(context, baseCandidateList);
                if (CollectionUtils.isEmpty(feasiblePlanList)) {
                    finalFailureReason = resolveCandidateFailureReason(baseCandidateList);
                    attemptedPhysicalMachineCodeSet.addAll(
                            collectPhysicalMachineCodes(baseCandidateList));
                    break;
                }
                sortFeasiblePlans(feasiblePlanList);
                boolean committed = false;
                for (SharedMouldSubstitutionPlan plan : feasiblePlanList) {
                    attemptedPhysicalMachineCodeSet.add(
                            plan.getOriginalPhysicalMachineCode());
                    if (commitPlan(
                            context, plan,
                            originalUnscheduled)) {
                        committed = true;
                        successGroupCount++;
                        break;
                    }
                    finalFailureReason = StringUtils.isNotEmpty(plan.getFailureReason())
                            ? plan.getFailureReason() : "正式提交与预演不一致";
                }
                if (!committed) {
                    break;
                }
            } finally {
                context.setCurrentScheduleDate(originalCurrentScheduleDate);
                if (Objects.nonNull(context.getMouldResourceContext())) {
                    context.getMouldResourceContext().refreshAvailability(context);
                }
            }
        }
        if (targetScheduleQtyResolver.resolveProductionRemainingQty(
                context, targetSku) > 0) {
            appendFailureProcessLog(context, targetSku, finalFailureReason);
        }
        return successGroupCount;
    }

    /**
     * 收集满足 A/B 模具关系硬门槛的真实续作候选。
     *
     * @param context 排程上下文
     * @param targetSku 物料 A
     * @param dateResolution 计划日与接管日期
     * @param attemptedPhysicalMachineCodeSet 已尝试物理机台
     * @param mouldResourceContext 模具资源运行态
     * @return 基础候选
     */
    private List<SharedMouldSubstitutionPlan> collectBaseCandidates(
            LhScheduleContext context,
            SkuScheduleDTO targetSku,
            SharedMouldPlanDateResolution dateResolution,
            Set<String> attemptedPhysicalMachineCodeSet,
            MouldResourceContext mouldResourceContext) {
        Map<String, LhScheduleResult> physicalCandidateMap =
                new LinkedHashMap<String, LhScheduleResult>(16);
        for (LhScheduleResult result :
                context.getSpecialMaterialContinuationResultSnapshot()) {
            if (!isCurrentContinuationResult(context, result)) {
                continue;
            }
            String physicalMachineCode =
                    LhSingleControlMachineUtil.resolvePhysicalMachineCode(
                            result.getLhMachineCode());
            if (attemptedPhysicalMachineCodeSet.contains(physicalMachineCode)
                    || physicalCandidateMap.containsKey(physicalMachineCode)
                    || !hasProductionOnOrAfter(
                    context, result, dateResolution.getTakeoverTargetTime())
                    || hasProtectedNonContinuationResult(
                    context, physicalMachineCode,
                    dateResolution.getTakeoverDate())) {
                continue;
            }
            physicalCandidateMap.put(physicalMachineCode, result);
        }
        List<SharedMouldSubstitutionPlan> candidateList =
                new ArrayList<SharedMouldSubstitutionPlan>(
                        physicalCandidateMap.size());
        for (Map.Entry<String, LhScheduleResult> entry :
                physicalCandidateMap.entrySet()) {
            SharedMouldSubstitutionPlan plan = buildBaseCandidate(
                    context, targetSku, dateResolution, entry.getKey(),
                    entry.getValue(), mouldResourceContext);
            if (Objects.nonNull(plan)) {
                candidateList.add(plan);
            }
        }
        return candidateList;
    }

    /**
     * 校验单台冻结续作机台上的 B、完整物理机台模具集合和剩余模具，并构造无副作用基础计划。
     *
     * @param context 排程上下文
     * @param targetSku 物料 A
     * @param dateResolution A 的计划来源日及目标接管时间
     * @param physicalMachineCode B 原物理机台
     * @param seedResult 用于解析 B 的冻结续作结果
     * @param mouldResourceContext 当前模具资源运行态
     * @return 满足 A/B 硬门槛的基础计划；不满足时返回 null
     */
    private SharedMouldSubstitutionPlan buildBaseCandidate(
            LhScheduleContext context,
            SkuScheduleDTO targetSku,
            SharedMouldPlanDateResolution dateResolution,
            String physicalMachineCode,
            LhScheduleResult seedResult,
            MouldResourceContext mouldResourceContext) {
        SkuScheduleDTO continuationSku = resolveSourceSku(
                context, seedResult);
        if (Objects.isNull(continuationSku)
                || StringUtils.equals(
                MonthPlanDateResolver.buildMaterialStatusKey(
                        targetSku.getMaterialCode(), targetSku.getProductStatus()),
                MonthPlanDateResolver.buildMaterialStatusKey(
                        continuationSku.getMaterialCode(),
                        continuationSku.getProductStatus()))) {
            return null;
        }
        List<LhScheduleResult> physicalResultList;
        try {
            physicalResultList = continuationCutoverService
                    .resolveContinuationResults(
                            context, continuationSku, physicalMachineCode);
        } catch (IllegalStateException ex) {
            log.info("共用模具置换候选跳过, targetMaterialCode: {}, physicalMachineCode: {}, reason: {}",
                    targetSku.getMaterialCode(), physicalMachineCode, ex.getMessage());
            return null;
        }
        if (CollectionUtils.isEmpty(physicalResultList)
                || !isWholePhysicalMachineComplete(
                context, physicalResultList)) {
            return null;
        }
        Map<String, List<String>> transferredMouldCodeMap =
                new LinkedHashMap<String, List<String>>(2);
        Set<String> transferredMouldCodeSet =
                new LinkedHashSet<String>(4);
        List<String> machineCodeList = new ArrayList<String>(2);
        for (LhScheduleResult result : physicalResultList) {
            String machineCode = result.getLhMachineCode();
            Set<String> boundMouldCodeSet =
                    mouldResourceContext.resolveMachineBoundMouldCodes(
                            machineCode);
            /*
             * “有效共用模具关系”按精确模具号校验，不允许使用同模套、同模壳或同规格推断。
             * 全物理机台继承要求每一侧当前实际绑定模具都同时属于 A、B 的有效关系。
             */
            if (CollectionUtils.isEmpty(boundMouldCodeSet)
                    || !mouldResourceContext.areAllMouldCodesValidForSku(
                    targetSku.getMaterialCode(), boundMouldCodeSet)
                    || !mouldResourceContext.areAllMouldCodesValidForSku(
                    continuationSku.getMaterialCode(), boundMouldCodeSet)) {
                return null;
            }
            machineCodeList.add(machineCode);
            transferredMouldCodeSet.addAll(boundMouldCodeSet);
            transferredMouldCodeMap.put(
                    machineCode,
                    new ArrayList<String>(boundMouldCodeSet));
        }
        List<String> remainingMouldCodeList =
                mouldResourceContext.resolveFreeValidMouldCodes(
                        continuationSku.getMaterialCode(),
                        transferredMouldCodeSet);
        if (CollectionUtils.isEmpty(remainingMouldCodeList)) {
            log.info("共用模具置换候选跳过，B 无剩余空闲有效模具, targetMaterialCode: {}, "
                            + "continuationMaterialCode: {}, physicalMachineCode: {}, transferredMouldCodes: {}",
                    targetSku.getMaterialCode(),
                    continuationSku.getMaterialCode(),
                    physicalMachineCode, transferredMouldCodeSet);
            /*
             * 该分支发生在通用快照预演之前，不能只写应用日志：若所有候选都因 B 无剩余模具失败，
             * 最终泛化失败日志无法还原具体的 B、共用模具和原续作机台。这里追加候选级过程日志，
             * 供 S4.6 与本批其他日志一起原子持久化；它只记录只读候选事实，不修改模具占用、
             * 机台状态或数量账本，因此不会破坏“先预演、后提交”的原子边界。
             */
            appendCandidateFailureProcessLog(
                    context, targetSku, continuationSku,
                    physicalMachineCode, transferredMouldCodeSet,
                    dateResolution.getTakeoverTargetTime(),
                    "B 无剩余空闲有效模具");
            return null;
        }
        SharedMouldSubstitutionPlan plan =
                new SharedMouldSubstitutionPlan();
        plan.setTargetSku(targetSku);
        plan.setContinuationSku(continuationSku);
        plan.setFirstPositivePlanDate(
                dateResolution.getFirstPositivePlanDate());
        plan.setTakeoverTargetTime(
                dateResolution.getTakeoverTargetTime());
        plan.setContinuationOfflineTime(
                dateResolution.getTakeoverTargetTime());
        plan.setOriginalPhysicalMachineCode(physicalMachineCode);
        plan.setOriginalMachineCodeList(machineCodeList);
        plan.setTakeoverMachineCode(
                resolvePrimaryMachineCode(machineCodeList));
        plan.setTransferredMouldCodeMap(transferredMouldCodeMap);
        return plan;
    }

    /**
     * 对每个基础候选执行完整 A 接管及 B 迁移预演。
     *
     * @param context 排程上下文
     * @param candidateList 基础候选
     * @return 同时满足 A、B 的可提交计划
     */
    private List<SharedMouldSubstitutionPlan> previewCandidates(
            LhScheduleContext context,
            List<SharedMouldSubstitutionPlan> candidateList) {
        List<SharedMouldSubstitutionPlan> feasiblePlanList =
                new ArrayList<SharedMouldSubstitutionPlan>(
                        candidateList.size());
        for (SharedMouldSubstitutionPlan plan : candidateList) {
            ScheduleSubstitutionAttemptSnapshot snapshot =
                    ScheduleSubstitutionAttemptSnapshot.capture(
                            context, Arrays.asList(
                                    plan.getTargetSku(),
                                    plan.getContinuationSku()));
            try {
                executePlan(context, plan, false);
                feasiblePlanList.add(plan);
            } catch (IllegalStateException ex) {
                plan.setFailureReason(ex.getMessage());
                log.info("共用模具置换候选预演失败，继续下一续作候选, targetMaterialCode: {}, "
                                + "continuationMaterialCode: {}, physicalMachineCode: {}, reason: {}",
                        plan.getTargetSku().getMaterialCode(),
                        plan.getContinuationSku().getMaterialCode(),
                        plan.getOriginalPhysicalMachineCode(), ex.getMessage());
            } catch (RuntimeException ex) {
                plan.setFailureReason("候选预演异常: " + ex.getMessage());
                log.error("共用模具置换候选预演异常，候选状态将完整恢复, targetMaterialCode: {}, "
                                + "continuationMaterialCode: {}, physicalMachineCode: {}",
                        plan.getTargetSku().getMaterialCode(),
                        plan.getContinuationSku().getMaterialCode(),
                        plan.getOriginalPhysicalMachineCode(), ex);
            } finally {
                // 预演不得保留任何结果、日志、模具、机台、换模/首检次数或 A/B 数量账本。
                snapshot.restore(context);
            }
        }
        return feasiblePlanList;
    }

    /**
     * 正式提交预演成功计划。
     *
     * @param context 排程上下文
     * @param plan 预演计划
     * @param originalUnscheduled A 原未排记录模板
     * @return true-提交成功；false-已完整回滚
     */
    private boolean commitPlan(
            LhScheduleContext context,
            SharedMouldSubstitutionPlan plan,
            LhUnscheduledResult originalUnscheduled) {
        ScheduleSubstitutionAttemptSnapshot snapshot =
                ScheduleSubstitutionAttemptSnapshot.capture(
                        context, Arrays.asList(
                                plan.getTargetSku(),
                                plan.getContinuationSku()));
        try {
            executePlan(context, plan, true);
            /*
             * 未排结果属于联动提交的一部分，必须在同一快照保护范围内更新；
             * 若后续成功记录或日志写入异常，A/B 结果和未排清单会一起恢复。
             */
            reconcileTargetUnscheduled(
                    context, plan.getTargetSku(),
                    originalUnscheduled);
            recordSuccess(context, plan);
            return true;
        } catch (RuntimeException ex) {
            snapshot.restore(context);
            plan.setFailureReason("正式提交失败并已整体回滚: " + ex.getMessage());
            log.error("共用模具置换正式提交失败，A/B 联动状态已完整恢复, targetMaterialCode: {}, "
                            + "continuationMaterialCode: {}, originalPhysicalMachineCode: {}, "
                            + "relocationMachineCode: {}",
                    plan.getTargetSku().getMaterialCode(),
                    plan.getContinuationSku().getMaterialCode(),
                    plan.getOriginalPhysicalMachineCode(),
                    plan.getRelocationMachineCode(), ex);
            return false;
        } finally {
            context.clearScheduleSubstitutionDirective();
        }
    }

    /**
     * 在当前快照作用域内执行一次 A/B 联动。
     *
     * @param context 排程上下文
     * @param plan 基础或预演计划
     * @param commit true-锁定预演机台和模具正式提交；false-允许 B 正常选机预演
     */
    private void executePlan(
            LhScheduleContext context,
            SharedMouldSubstitutionPlan plan,
            boolean commit) {
        ContinuationCutoverResult cutoverResult =
                continuationCutoverService.cutover(
                        context, plan.getContinuationSku(),
                        plan.getOriginalPhysicalMachineCode(),
                        plan.getContinuationOfflineTime());
        if (cutoverResult.getRemovedQty() <= 0) {
            throw new IllegalStateException("B 在 A 目标接管时间后没有可迁移续作尾量");
        }
        if (commit && cutoverResult.getRemovedQty()
                != plan.getRelocatedQty()) {
            throw new IllegalStateException(
                    "B 正式截断尾量与预演不一致");
        }
        if (!commit) {
            /*
             * 首次预演必须在调用 B 主链前写入精确迁移量，使临时迁移副本与运行指令使用同一上限。
             * 该值只存在于候选计划对象，排程运行态仍由外层通用快照负责恢复。
             */
            plan.setRelocatedQty(cutoverResult.getRemovedQty());
        }
        updateSourceMachineCutoverState(context, plan);
        List<LhScheduleResult> targetResultList =
                specifiedNewSpecSchedulingService.schedule(
                        context, plan.getTargetSku(),
                        buildTargetDirective(plan));
        Date actualTakeoverTime = validateTargetTakeover(
                plan, targetResultList);
        if (commit && !Objects.equals(
                plan.getTargetTakeoverTime(), actualTakeoverTime)) {
            throw new IllegalStateException(
                    "A 正式接管时间与预演不一致");
        }
        SkuScheduleDTO relocationSku = buildRelocationSku(
                plan.getContinuationSku(),
                cutoverResult.getRemovedQty(),
                plan.getContinuationOfflineTime());
        ScheduleSubstitutionDirective relocationDirective =
                buildRelocationDirective(context, plan, commit);
        List<LhScheduleResult> relocationResultList =
                specifiedNewSpecSchedulingService.schedule(
                        context, relocationSku, relocationDirective);
        validateRelocation(
                plan, relocationResultList,
                cutoverResult.getRemovedQty(), commit);
        if (!commit) {
            plan.setTargetTakeoverTime(actualTakeoverTime);
        }
    }

    /**
     * 构造 A 原机台原模具无换模接管指令。
     *
     * @param plan 已通过基础校验的置换计划
     * @return A 的隔离排产指令
     */
    private ScheduleSubstitutionDirective buildTargetDirective(
            SharedMouldSubstitutionPlan plan) {
        ScheduleSubstitutionDirective directive =
                new ScheduleSubstitutionDirective();
        directive.setSkuKey(MonthPlanDateResolver.buildMaterialStatusKey(
                plan.getTargetSku().getMaterialCode(),
                plan.getTargetSku().getProductStatus()));
        directive.setSpecifiedMachineCode(
                plan.getTakeoverMachineCode());
        directive.setEarliestSwitchTime(
                plan.getContinuationOfflineTime());
        directive.setTakeoverWithoutMouldChange(true);
        directive.setForcedMouldCodeMap(
                copyMouldCodeMap(plan.getTransferredMouldCodeMap()));
        return directive;
    }

    /**
     * 构造 B 携剩余模具重新选机指令。
     *
     * <p>预演阶段不指定新机台，但只允许使用已过滤的剩余模具；正式提交阶段锁定预演确认的
     * 新机台和精确模具，避免二次执行产生不同结果。</p>
     *
     * @param context 排程上下文
     * @param plan 置换计划
     * @param commit true-正式提交；false-候选预演
     * @return B 的隔离排产指令
     */
    private ScheduleSubstitutionDirective buildRelocationDirective(
            LhScheduleContext context,
            SharedMouldSubstitutionPlan plan,
            boolean commit) {
        ScheduleSubstitutionDirective directive =
                new ScheduleSubstitutionDirective();
        directive.setSkuKey(MonthPlanDateResolver.buildMaterialStatusKey(
                plan.getContinuationSku().getMaterialCode(),
                plan.getContinuationSku().getProductStatus()));
        directive.setContinuationRelocation(true);
        directive.setExactScheduleQty(
                plan.getRelocatedQty());
        directive.setEarliestSwitchTime(
                plan.getContinuationOfflineTime());
        directive.setExcludedMachineCodeSet(
                new LinkedHashSet<String>(
                        plan.getOriginalMachineCodeList()));
        if (commit) {
            directive.setSpecifiedMachineCode(
                    plan.getRelocationMachineCode());
            directive.setForcedMouldCodeMap(
                    copyMouldCodeMap(
                            plan.getRelocationMouldCodeMap()));
        } else {
            Set<String> transferredMouldCodeSet =
                    flattenMouldCodes(
                            plan.getTransferredMouldCodeMap());
            directive.setAllowedRelocationMouldCodeList(
                    resolveMouldResourceContext(context)
                            .resolveFreeValidMouldCodes(
                                    plan.getContinuationSku()
                                            .getMaterialCode(),
                                    transferredMouldCodeSet));
        }
        return directive;
    }

    /**
     * 校验 A 是否完整继承原物理机台和共用模具，且没有生成换模、换活字块或伪换模时间。
     *
     * @param plan 置换计划
     * @param targetResultList A 本次新生成的排程结果
     * @return A 实际最早开产时间
     */
    private Date validateTargetTakeover(
            SharedMouldSubstitutionPlan plan,
            List<LhScheduleResult> targetResultList) {
        if (CollectionUtils.isEmpty(targetResultList)) {
            throw new IllegalStateException("A 无法在原续作机台形成正计划量");
        }
        Set<String> actualMachineCodeSet =
                new LinkedHashSet<String>(2);
        Map<String, List<String>> actualMouldCodeMap =
                new LinkedHashMap<String, List<String>>(2);
        Date actualTakeoverTime = null;
        for (LhScheduleResult result : targetResultList) {
            actualMachineCodeSet.add(result.getLhMachineCode());
            actualMouldCodeMap.put(
                    result.getLhMachineCode(),
                    new ArrayList<String>(
                            LhMouldCodeUtil.splitMouldCode(
                                    result.getMouldCode())));
            if (!ScheduleTypeEnum.NEW_SPEC.getCode().equals(
                    result.getScheduleType())
                    || !StringUtils.equals(
                    "0", result.getIsChangeMould())
                    || !StringUtils.equals(
                    "0", result.getIsTypeBlock())
                    || Objects.nonNull(
                    result.getMouldChangeStartTime())) {
                throw new IllegalStateException(
                        "A 接管结果未保持新增来源、无换模和无换活字块");
            }
            Date resultStartTime = resolveFirstProductionTime(result);
            if (Objects.isNull(actualTakeoverTime)
                    || (Objects.nonNull(resultStartTime)
                    && resultStartTime.before(actualTakeoverTime))) {
                actualTakeoverTime = resultStartTime;
            }
        }
        if (!actualMachineCodeSet.equals(
                new LinkedHashSet<String>(
                        plan.getOriginalMachineCodeList()))
                || !areMouldCodeMapsEqual(
                plan.getTransferredMouldCodeMap(),
                actualMouldCodeMap)) {
            throw new IllegalStateException(
                    "A 未完整继承原物理机台及整套共用模具");
        }
        if (Objects.isNull(actualTakeoverTime)
                || actualTakeoverTime.before(
                plan.getContinuationOfflineTime())) {
            throw new IllegalStateException(
                    "A 开产时间早于 B 下机时间");
        }
        return actualTakeoverTime;
    }

    /**
     * 校验 B 是否由一个新物理机台精确承接全部尾量，并核对正规换模、时间下限和模具互斥。
     *
     * @param plan 置换计划
     * @param resultList B 本次新生成的排程结果
     * @param requiredQty B 必须精确承接的截断尾量
     * @param commit true-同时核对正式结果与预演锁定值；false-把预演结果写入计划
     */
    private void validateRelocation(
            SharedMouldSubstitutionPlan plan,
            List<LhScheduleResult> resultList,
            int requiredQty,
            boolean commit) {
        if (CollectionUtils.isEmpty(resultList)) {
            throw new IllegalStateException("B 有剩余模具但未找到满足约束的新机台");
        }
        Set<String> physicalMachineCodeSet =
                new LinkedHashSet<String>(2);
        List<String> relocationMachineCodeList =
                new ArrayList<String>(2);
        Map<String, List<String>> relocationMouldCodeMap =
                new LinkedHashMap<String, List<String>>(2);
        Set<String> relocationMouldCodeSet =
                new LinkedHashSet<String>(4);
        int scheduledQty = 0;
        Date earliestMouldChangeTime = null;
        Date earliestProductionTime = null;
        for (LhScheduleResult result : resultList) {
            scheduledQty += ShiftFieldUtil.resolveScheduledQty(result);
            String machineCode = result.getLhMachineCode();
            relocationMachineCodeList.add(machineCode);
            physicalMachineCodeSet.add(
                    LhSingleControlMachineUtil
                            .resolvePhysicalMachineCode(machineCode));
            List<String> mouldCodeList =
                    new ArrayList<String>(
                            LhMouldCodeUtil.splitMouldCode(
                                    result.getMouldCode()));
            relocationMouldCodeMap.put(machineCode, mouldCodeList);
            relocationMouldCodeSet.addAll(mouldCodeList);
            earliestMouldChangeTime = minDate(
                    earliestMouldChangeTime,
                    result.getMouldChangeStartTime());
            earliestProductionTime = minDate(
                    earliestProductionTime,
                    resolveFirstProductionTime(result));
            if (!ScheduleTypeEnum.NEW_SPEC.getCode().equals(
                    result.getScheduleType())
                    || !StringUtils.equals(
                    "1", result.getIsChangeMould())
                    || !StringUtils.equals(
                    "0", result.getIsTypeBlock())) {
                throw new IllegalStateException(
                        "B 新机台结果未按新增来源执行正规换模");
            }
        }
        if (physicalMachineCodeSet.size() != 1
                || scheduledQty != requiredQty) {
            throw new IllegalStateException(
                    RELOCATION_NOT_FULL_REASON
                            + "，requiredQty=" + requiredQty
                            + "，actualQty=" + scheduledQty);
        }
        String physicalMachineCode =
                physicalMachineCodeSet.iterator().next();
        if (StringUtils.equals(
                physicalMachineCode,
                plan.getOriginalPhysicalMachineCode())
                || Objects.isNull(earliestMouldChangeTime)
                || earliestMouldChangeTime.before(
                plan.getContinuationOfflineTime())
                || Objects.isNull(earliestProductionTime)
                || earliestProductionTime.before(
                plan.getContinuationOfflineTime())) {
            throw new IllegalStateException(
                    "B 新机台或换模/开产时间不满足下机后迁移约束");
        }
        Set<String> transferredMouldCodeSet =
                flattenMouldCodes(
                        plan.getTransferredMouldCodeMap());
        if (!Collections.disjoint(
                transferredMouldCodeSet,
                relocationMouldCodeSet)) {
            throw new IllegalStateException(
                    "A、B 在同一时间重复占用转交模具");
        }
        String primaryRelocationMachineCode =
                resolvePrimaryMachineCode(
                        relocationMachineCodeList);
        if (commit) {
            if (!StringUtils.equals(
                    plan.getRelocationMachineCode(),
                    primaryRelocationMachineCode)
                    || !areMouldCodeMapsEqual(
                    plan.getRelocationMouldCodeMap(),
                    relocationMouldCodeMap)
                    || !Objects.equals(
                    plan.getRelocationMouldChangeTime(),
                    earliestMouldChangeTime)
                    || !Objects.equals(
                    plan.getRelocationProductionStartTime(),
                    earliestProductionTime)) {
                throw new IllegalStateException(
                        "B 正式迁移机台、模具或时间轴与预演不一致");
            }
            return;
        }
        plan.setRelocationMachineCode(
                primaryRelocationMachineCode);
        plan.setRelocationMachineCodeList(
                relocationMachineCodeList);
        plan.setRelocationMouldCodeMap(
                relocationMouldCodeMap);
        plan.setRelocationMouldChangeTime(
                earliestMouldChangeTime);
        plan.setRelocationProductionStartTime(
                earliestProductionTime);
    }

    /**
     * 基于来源 B 构造续作增机迁移副本。
     *
     * <p>副本复用 B 的业务属性和日计划账本，但目标量严格限定为本组截断尾量，
     * 不改变原 B 对象的续作属性。</p>
     *
     * @param sourceSku 原续作物料 B
     * @param relocatedQty 精确迁移量
     * @param offlineTime B 原机台下机时间
     * @return 仅用于本次隔离主链的 B 迁移副本
     */
    private SkuScheduleDTO buildRelocationSku(
            SkuScheduleDTO sourceSku,
            int relocatedQty,
            Date offlineTime) {
        SkuScheduleDTO relocationSku = new SkuScheduleDTO();
        BeanUtil.copyProperties(sourceSku, relocationSku);
        relocationSku.setScheduleType(
                ScheduleTypeEnum.NEW_SPEC.getCode());
        relocationSku.setSourceType(
                SkuScheduleSourceTypeEnum.CONTINUATION_ADD_MACHINE.getCode());
        relocationSku.setContinuousMachineCode(null);
        relocationSku.setPreferredContinuousMachineCode(null);
        relocationSku.setContinuousCompensationSku(true);
        relocationSku.setTargetScheduleQty(relocatedQty);
        relocationSku.setPendingQty(relocatedQty);
        relocationSku.setRemainingScheduleQty(relocatedQty);
        relocationSku.setStrictTargetQty(true);
        relocationSku.setFirstAddMachineProductionDate(
                toLocalDate(offlineTime));
        relocationSku.setContinuationActiveMachineCount(0);
        relocationSku.setContinuationRequiredMachineCount(1);
        relocationSku.setContinuationShortageMachineCount(1);
        relocationSku.setContinuationAddMachineDayPlanQty(relocatedQty);
        // B 迁移副本与来源续作 SKU 必须共享同一日计划账本，精确消费刚恢复的截断尾量。
        relocationSku.setDailyPlanQuotaMap(
                sourceSku.getDailyPlanQuotaMap());
        return relocationSku;
    }

    /**
     * 把 B 原机台运行态收口到下机时间，并声明下一物料为 A。
     *
     * @param context 排程上下文
     * @param plan 置换计划
     */
    private void updateSourceMachineCutoverState(
            LhScheduleContext context,
            SharedMouldSubstitutionPlan plan) {
        for (String machineCode :
                plan.getOriginalMachineCodeList()) {
            MachineScheduleDTO machine =
                    context.getMachineScheduleMap().get(machineCode);
            if (Objects.isNull(machine)) {
                throw new IllegalStateException(
                        "原续作机台运行态不存在: " + machineCode);
            }
            machine.setEstimatedEndTime(
                    plan.getContinuationOfflineTime());
            machine.setNextMaterialCode(
                    plan.getTargetSku().getMaterialCode());
        }
    }

    /**
     * 记录成功组的 A/B 完整关联信息和可持久化过程日志。
     *
     * @param context 排程上下文
     * @param plan 已正式提交且复核通过的置换计划
     */
    private void recordSuccess(
            LhScheduleContext context,
            SharedMouldSubstitutionPlan plan) {
        SharedMouldSubstitutionRecord record =
                new SharedMouldSubstitutionRecord();
        record.setTargetMaterialCode(
                plan.getTargetSku().getMaterialCode());
        record.setTargetProductStatus(
                plan.getTargetSku().getProductStatus());
        record.setContinuationMaterialCode(
                plan.getContinuationSku().getMaterialCode());
        record.setContinuationProductStatus(
                plan.getContinuationSku().getProductStatus());
        record.setFirstPositivePlanDate(
                plan.getFirstPositivePlanDate());
        record.setTransferredMouldCodeList(
                new ArrayList<String>(flattenMouldCodes(
                        plan.getTransferredMouldCodeMap())));
        record.setRelocationMouldCodeList(
                new ArrayList<String>(flattenMouldCodes(
                        plan.getRelocationMouldCodeMap())));
        record.setOriginalPhysicalMachineCode(
                plan.getOriginalPhysicalMachineCode());
        record.setTakeoverMachineCode(
                plan.getTakeoverMachineCode());
        record.setRelocationMachineCode(
                plan.getRelocationMachineCode());
        record.setContinuationOfflineTime(
                plan.getContinuationOfflineTime());
        record.setTargetTakeoverTime(
                plan.getTargetTakeoverTime());
        record.setRelocationMouldChangeTime(
                plan.getRelocationMouldChangeTime());
        record.setRelocationProductionStartTime(
                plan.getRelocationProductionStartTime());
        record.setRelocatedQty(plan.getRelocatedQty());
        context.getSharedMouldSubstitutionRecordList().add(record);
        String detail = new StringBuilder(512)
                .append("A=").append(record.getTargetMaterialCode())
                .append("，B=").append(record.getContinuationMaterialCode())
                .append("，共用模具=").append(record.getTransferredMouldCodeList())
                .append("，B剩余模具=").append(record.getRelocationMouldCodeList())
                .append("，原机台=").append(record.getOriginalPhysicalMachineCode())
                .append("，新机台=").append(record.getRelocationMachineCode())
                .append("，B下机=").append(LhScheduleTimeUtil.formatDateTime(
                        record.getContinuationOfflineTime()))
                .append("，A接管=").append(LhScheduleTimeUtil.formatDateTime(
                        record.getTargetTakeoverTime()))
                .append("，B换模=").append(LhScheduleTimeUtil.formatDateTime(
                        record.getRelocationMouldChangeTime()))
                .append("，B重新开产=").append(LhScheduleTimeUtil.formatDateTime(
                        record.getRelocationProductionStartTime()))
                .append("，B迁移数量=").append(record.getRelocatedQty())
                .toString();
        PriorityTraceLogHelper.appendProcessLog(
                context, "SKU 共用模具联动置换成功", detail);
        log.info("SKU 共用模具联动置换成功, {}", detail);
    }

    /**
     * 记录 A 全部候选失败或部分置换后仍有待排量的最终原因。
     *
     * @param context 排程上下文
     * @param targetSku 物料 A
     * @param failureReason 最终失败原因
     */
    private void appendFailureProcessLog(
            LhScheduleContext context,
            SkuScheduleDTO targetSku,
            String failureReason) {
        String detail = new StringBuilder(256)
                .append("A=").append(targetSku.getMaterialCode())
                .append("，产品状态=").append(targetSku.getProductStatus())
                .append("，失败原因=").append(failureReason)
                .toString();
        PriorityTraceLogHelper.appendProcessLog(
                context, "SKU 共用模具联动置换失败", detail);
        log.info("SKU 共用模具联动置换失败, {}", detail);
    }

    /**
     * 记录单个续作候选在进入联动预演前失败的完整审计信息。
     *
     * <p>此方法当前用于 B 无剩余模具这一硬门槛。此时尚未生成 B 的新机台、换模和重新开产结果，
     * 因而这些字段明确写为“未生成”，避免把空值误解为已经完成了部分置换。B 下机和 A 接管仍
     * 记录本候选的目标时点，便于对账该续作机台为何被评估、又为何没有进入原子提交。</p>
     *
     * @param context 排程上下文
     * @param targetSku 物料 A
     * @param continuationSku 物料 B
     * @param originalPhysicalMachineCode B 的原续作物理机台
     * @param transferredMouldCodeSet 准备转给 A 的精确共用模具
     * @param takeoverTargetTime 本候选的 B 下机/A 接管目标时点
     * @param failureReason 候选失败原因
     */
    private void appendCandidateFailureProcessLog(
            LhScheduleContext context,
            SkuScheduleDTO targetSku,
            SkuScheduleDTO continuationSku,
            String originalPhysicalMachineCode,
            Set<String> transferredMouldCodeSet,
            Date takeoverTargetTime,
            String failureReason) {
        String detail = new StringBuilder(384)
                .append("A=").append(targetSku.getMaterialCode())
                .append("，B=").append(continuationSku.getMaterialCode())
                .append("，共用模具=").append(transferredMouldCodeSet)
                .append("，B剩余模具=[]")
                .append("，原机台=").append(originalPhysicalMachineCode)
                .append("，新机台=未生成")
                .append("，B下机=").append(LhScheduleTimeUtil.formatDateTime(
                        takeoverTargetTime))
                .append("，A接管=").append(LhScheduleTimeUtil.formatDateTime(
                        takeoverTargetTime))
                .append("，B换模=未生成")
                .append("，B重新开产=未生成")
                .append("，失败原因=").append(failureReason)
                .toString();
        PriorityTraceLogHelper.appendProcessLog(
                context, "SKU 共用模具联动置换候选失败", detail);
        log.info("SKU 共用模具联动置换候选失败, {}", detail);
    }

    /**
     * 按 A 的最新真实余量重建未排记录，避免保留置换前的过期未排数量。
     *
     * @param context 排程上下文
     * @param targetSku 物料 A
     * @param originalUnscheduled A 原未排记录模板
     */
    private void reconcileTargetUnscheduled(
            LhScheduleContext context,
            SkuScheduleDTO targetSku,
            LhUnscheduledResult originalUnscheduled) {
        context.getUnscheduledResultList().removeIf(
                result -> isSameSku(
                        targetSku.getMaterialCode(),
                        targetSku.getProductStatus(), result));
        int remainingQty =
                targetScheduleQtyResolver.resolveProductionRemainingQty(
                        context, targetSku);
        if (remainingQty <= 0) {
            return;
        }
        LhUnscheduledResult retainedUnscheduled =
                new LhUnscheduledResult();
        BeanUtil.copyProperties(
                originalUnscheduled, retainedUnscheduled);
        retainedUnscheduled.setUnscheduledQty(remainingQty);
        retainedUnscheduled.setUnscheduledReason(
                "共用模具联动置换后仍有待排量");
        context.getUnscheduledResultList().add(
                retainedUnscheduled);
    }

    /**
     * 按“B 尾量、B 最早重启时间、原物理机台”稳定排序全部可行计划。
     *
     * @param planList 已通过完整预演的计划
     */
    private void sortFeasiblePlans(
            List<SharedMouldSubstitutionPlan> planList) {
        planList.sort(Comparator
                .comparingInt(
                        SharedMouldSubstitutionPlan::getRelocatedQty)
                .thenComparing(
                        SharedMouldSubstitutionPlan
                                ::getRelocationProductionStartTime,
                        Comparator.nullsLast(Date::compareTo))
                .thenComparing(
                        SharedMouldSubstitutionPlan
                                ::getOriginalPhysicalMachineCode,
                        Comparator.nullsLast(String::compareTo)));
    }

    /**
     * 解析 A 的最早正计划来源日和窗口内实际接管目标时间。
     *
     * @param context 排程上下文
     * @param sku 物料 A
     * @return 日期解析结果；窗口及提前生产阈值内均无正计划时返回 null
     */
    private SharedMouldPlanDateResolution resolvePlanDate(
            LhScheduleContext context,
            SkuScheduleDTO sku) {
        LocalDate windowStartDate =
                toLocalDate(context.getScheduleDate());
        LocalDate windowEndDate =
                resolveWindowEndDate(context);
        if (Objects.isNull(windowStartDate)
                || Objects.isNull(windowEndDate)) {
            return null;
        }
        LocalDate firstPositivePlanDate = null;
        for (LocalDate cursor = windowStartDate;
             !cursor.isAfter(windowEndDate);
             cursor = cursor.plusDays(1)) {
            if (MonthPlanDateResolver.resolveDayQty(
                    context, sku.getMaterialCode(),
                    sku.getProductStatus(), cursor) > 0) {
                firstPositivePlanDate = cursor;
                break;
            }
        }
        if (Objects.isNull(firstPositivePlanDate)) {
            firstPositivePlanDate =
                    EarlyProductionChecker.resolveFirstFuturePlanDate(
                            context, sku, windowEndDate);
        }
        if (Objects.isNull(firstPositivePlanDate)) {
            return null;
        }
        /*
         * 未来计划日只作为提前生产来源日保留；实际接管仍必须落在现有 T～T+2 排程窗口，
         * 是否允许提前由新增主链的 EarlyProductionChecker 再次按原规则判断。
         */
        LocalDate takeoverDate =
                firstPositivePlanDate.isAfter(windowEndDate)
                        ? windowStartDate : firstPositivePlanDate;
        Date takeoverTargetTime =
                resolveEarliestShiftStartTime(
                        context, takeoverDate);
        if (Objects.isNull(takeoverTargetTime)) {
            return null;
        }
        return new SharedMouldPlanDateResolution(
                firstPositivePlanDate,
                takeoverDate,
                takeoverTargetTime);
    }

    private Date resolveEarliestShiftStartTime(
            LhScheduleContext context,
            LocalDate targetDate) {
        Date earliestTime = null;
        for (LhShiftConfigVO shift :
                context.getScheduleWindowShifts()) {
            if (Objects.isNull(shift)
                    || Objects.isNull(shift.getWorkDate())
                    || Objects.isNull(
                    shift.getShiftStartDateTime())
                    || !Objects.equals(
                    targetDate,
                    toLocalDate(shift.getWorkDate()))) {
                continue;
            }
            if (Objects.isNull(earliestTime)
                    || shift.getShiftStartDateTime()
                    .before(earliestTime)) {
                earliestTime =
                        shift.getShiftStartDateTime();
            }
        }
        return earliestTime;
    }

    private LocalDate resolveWindowEndDate(
            LhScheduleContext context) {
        LocalDate windowEndDate = null;
        for (LhShiftConfigVO shift :
                context.getScheduleWindowShifts()) {
            LocalDate workDate = Objects.isNull(shift)
                    ? null : toLocalDate(shift.getWorkDate());
            if (Objects.nonNull(workDate)
                    && (Objects.isNull(windowEndDate)
                    || workDate.isAfter(windowEndDate))) {
                windowEndDate = workDate;
            }
        }
        if (Objects.nonNull(windowEndDate)) {
            return windowEndDate;
        }
        return toLocalDate(context.getWindowEndDate());
    }

    private boolean isCurrentContinuationResult(
            LhScheduleContext context,
            LhScheduleResult result) {
        if (Objects.isNull(result)
                || !context.getScheduleResultList().contains(result)
                || !ScheduleTypeEnum.CONTINUOUS.getCode()
                .equals(result.getScheduleType())
                || StringUtils.isEmpty(result.getLhMachineCode())) {
            return false;
        }
        MachineScheduleDTO initialMachine =
                context.getInitialMachineScheduleMap()
                        .get(result.getLhMachineCode());
        return Objects.nonNull(initialMachine)
                && StringUtils.equals(
                initialMachine.getCurrentMaterialCode(),
                result.getMaterialCode());
    }

    private boolean hasProductionOnOrAfter(
            LhScheduleContext context,
            LhScheduleResult result,
            Date targetTime) {
        for (LhShiftConfigVO shift :
                context.getScheduleWindowShifts()) {
            if (Objects.isNull(shift)
                    || Objects.isNull(shift.getShiftIndex())) {
                continue;
            }
            Integer qty = ShiftFieldUtil.getShiftPlanQty(
                    result, shift.getShiftIndex());
            Date endTime = ShiftFieldUtil.getShiftEndTime(
                    result, shift.getShiftIndex());
            if (Objects.nonNull(qty) && qty > 0
                    && Objects.nonNull(endTime)
                    && endTime.after(targetTime)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasProtectedNonContinuationResult(
            LhScheduleContext context,
            String physicalMachineCode,
            LocalDate targetDate) {
        for (LhScheduleResult result :
                context.getScheduleResultList()) {
            if (context.getSpecialMaterialContinuationResultSnapshot()
                    .contains(result)
                    || !StringUtils.equals(
                    physicalMachineCode,
                    LhSingleControlMachineUtil
                            .resolvePhysicalMachineCode(
                                    result.getLhMachineCode()))) {
                continue;
            }
            for (LhShiftConfigVO shift :
                    context.getScheduleWindowShifts()) {
                if (Objects.isNull(shift)
                        || Objects.isNull(shift.getShiftIndex())
                        || Objects.isNull(shift.getWorkDate())
                        || toLocalDate(shift.getWorkDate())
                        .isBefore(targetDate)) {
                    continue;
                }
                Integer qty = ShiftFieldUtil.getShiftPlanQty(
                        result, shift.getShiftIndex());
                if (Objects.nonNull(qty) && qty > 0) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isWholePhysicalMachineComplete(
            LhScheduleContext context,
            List<LhScheduleResult> resultList) {
        if (CollectionUtils.isEmpty(resultList)) {
            return false;
        }
        String firstMachineCode =
                resultList.get(0).getLhMachineCode();
        if (!LhSingleControlMachineUtil.isSingleMouldMachine(
                firstMachineCode)) {
            return true;
        }
        MachineScheduleDTO pairMachine =
                LhSingleControlMachineUtil.resolvePairMachine(
                        context, firstMachineCode);
        if (Objects.isNull(pairMachine)) {
            return false;
        }
        for (LhScheduleResult result : resultList) {
            if (StringUtils.equals(
                    pairMachine.getMachineCode(),
                    result.getLhMachineCode())) {
                return true;
            }
        }
        return false;
    }

    private SkuScheduleDTO resolveSourceSku(
            LhScheduleContext context,
            LhScheduleResult result) {
        SkuScheduleDTO sourceSku =
                context.getScheduleResultSourceSkuMap().get(result);
        return Objects.nonNull(sourceSku)
                ? sourceSku : resolveSku(
                context, result.getMaterialCode(),
                result.getProductStatus());
    }

    private SkuScheduleDTO resolveSku(
            LhScheduleContext context,
            String materialCode,
            String productStatus) {
        String skuKey =
                MonthPlanDateResolver.buildMaterialStatusKey(
                        materialCode, productStatus);
        SkuScheduleDTO sku =
                context.getAllSkuScheduleDtoMap().get(skuKey);
        if (Objects.nonNull(sku)) {
            return sku;
        }
        for (SkuScheduleDTO candidate :
                context.getContinuousSkuList()) {
            if (isSameSku(
                    materialCode, productStatus, candidate)) {
                return candidate;
            }
        }
        for (SkuScheduleDTO candidate :
                context.getNewSpecSkuList()) {
            if (isSameSku(
                    materialCode, productStatus, candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private MouldResourceContext resolveMouldResourceContext(
            LhScheduleContext context) {
        if (Objects.isNull(context.getMouldResourceContext())) {
            context.setMouldResourceContext(
                    MouldResourceContext.from(context));
        }
        return context.getMouldResourceContext();
    }

    private String resolveCandidateFailureReason(
            List<SharedMouldSubstitutionPlan> planList) {
        for (SharedMouldSubstitutionPlan plan : planList) {
            if (StringUtils.isNotEmpty(
                    plan.getFailureReason())) {
                return new StringBuilder(192)
                        .append("B=")
                        .append(plan.getContinuationSku()
                                .getMaterialCode())
                        .append("，原机台=")
                        .append(plan.getOriginalPhysicalMachineCode())
                        .append("，")
                        .append(plan.getFailureReason())
                        .toString();
            }
        }
        return NO_ELIGIBLE_CANDIDATE_REASON;
    }

    private Set<String> collectPhysicalMachineCodes(
            List<SharedMouldSubstitutionPlan> planList) {
        Set<String> machineCodeSet =
                new LinkedHashSet<String>(planList.size());
        for (SharedMouldSubstitutionPlan plan : planList) {
            machineCodeSet.add(
                    plan.getOriginalPhysicalMachineCode());
        }
        return machineCodeSet;
    }

    private String resolvePrimaryMachineCode(
            List<String> machineCodeList) {
        if (CollectionUtils.isEmpty(machineCodeList)) {
            return null;
        }
        List<String> sortedMachineCodeList =
                new ArrayList<String>(machineCodeList);
        sortedMachineCodeList.sort(
                Comparator.nullsLast(String::compareTo));
        return sortedMachineCodeList.get(0);
    }

    private Date resolveFirstProductionTime(
            LhScheduleResult result) {
        Date firstStartTime = null;
        for (int shiftIndex = 1;
             shiftIndex <= 8;
             shiftIndex++) {
            Integer qty = ShiftFieldUtil.getShiftPlanQty(
                    result, shiftIndex);
            Date startTime = ShiftFieldUtil.getShiftStartTime(
                    result, shiftIndex);
            if (Objects.nonNull(qty) && qty > 0
                    && Objects.nonNull(startTime)
                    && (Objects.isNull(firstStartTime)
                    || startTime.before(firstStartTime))) {
                firstStartTime = startTime;
            }
        }
        return firstStartTime;
    }

    private Map<String, List<String>> copyMouldCodeMap(
            Map<String, List<String>> sourceMap) {
        Map<String, List<String>> targetMap =
                new LinkedHashMap<String, List<String>>(
                        Math.max(2, sourceMap.size() * 2));
        for (Map.Entry<String, List<String>> entry :
                sourceMap.entrySet()) {
            targetMap.put(entry.getKey(),
                    CollectionUtils.isEmpty(entry.getValue())
                            ? new ArrayList<String>(0)
                            : new ArrayList<String>(
                            entry.getValue()));
        }
        return targetMap;
    }

    private Set<String> flattenMouldCodes(
            Map<String, List<String>> mouldCodeMap) {
        Set<String> mouldCodeSet =
                new LinkedHashSet<String>(4);
        if (!CollectionUtils.isEmpty(mouldCodeMap)) {
            for (List<String> mouldCodeList :
                    mouldCodeMap.values()) {
                if (!CollectionUtils.isEmpty(
                        mouldCodeList)) {
                    mouldCodeSet.addAll(mouldCodeList);
                }
            }
        }
        return mouldCodeSet;
    }

    private boolean areMouldCodeMapsEqual(
            Map<String, List<String>> expectedMap,
            Map<String, List<String>> actualMap) {
        if (!expectedMap.keySet().equals(actualMap.keySet())) {
            return false;
        }
        for (String machineCode : expectedMap.keySet()) {
            if (!new LinkedHashSet<String>(
                    expectedMap.get(machineCode)).equals(
                    new LinkedHashSet<String>(
                            actualMap.get(machineCode)))) {
                return false;
            }
        }
        return true;
    }

    private Date minDate(Date first, Date second) {
        if (Objects.isNull(first)) {
            return second;
        }
        if (Objects.isNull(second)) {
            return first;
        }
        return first.before(second) ? first : second;
    }

    private boolean isSameSku(
            String materialCode,
            String productStatus,
            LhUnscheduledResult result) {
        return Objects.nonNull(result)
                && StringUtils.equals(
                materialCode, result.getMaterialCode())
                && StringUtils.equals(
                StringUtils.trimToEmpty(productStatus),
                StringUtils.trimToEmpty(
                        result.getProductStatus()));
    }

    private boolean isSameSku(
            String materialCode,
            String productStatus,
            SkuScheduleDTO sku) {
        return Objects.nonNull(sku)
                && StringUtils.equals(
                materialCode, sku.getMaterialCode())
                && StringUtils.equals(
                StringUtils.trimToEmpty(productStatus),
                StringUtils.trimToEmpty(
                        sku.getProductStatus()));
    }

    private LocalDate toLocalDate(Date date) {
        return Objects.isNull(date)
                ? null : date.toInstant()
                .atZone(ZoneId.systemDefault())
                .toLocalDate();
    }

    private Date toDate(LocalDate date) {
        return Objects.isNull(date)
                ? null : Date.from(date.atStartOfDay(
                ZoneId.systemDefault()).toInstant());
    }

}
