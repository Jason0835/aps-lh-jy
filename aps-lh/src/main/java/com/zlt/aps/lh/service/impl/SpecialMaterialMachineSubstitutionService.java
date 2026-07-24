package com.zlt.aps.lh.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.zlt.aps.lh.api.constant.LhScheduleConstant;
import com.zlt.aps.lh.api.domain.dto.MachineScheduleDTO;
import com.zlt.aps.lh.api.domain.dto.SkuDailyPlanQuotaDTO;
import com.zlt.aps.lh.api.domain.dto.SkuScheduleDTO;
import com.zlt.aps.lh.api.domain.dto.SpecialMaterialMatchResult;
import com.zlt.aps.lh.api.domain.entity.LhPrecisionPlan;
import com.zlt.aps.lh.api.domain.entity.LhScheduleResult;
import com.zlt.aps.lh.api.domain.entity.LhUnscheduledResult;
import com.zlt.aps.lh.api.domain.vo.LhShiftConfigVO;
import com.zlt.aps.lh.api.enums.MachineStopTypeEnum;
import com.zlt.aps.lh.api.enums.ScheduleTypeEnum;
import com.zlt.aps.lh.api.enums.SubstitutionTypeEnum;
import com.zlt.aps.lh.component.MonthPlanDateResolver;
import com.zlt.aps.lh.component.TargetScheduleQtyResolver;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.engine.factory.ScheduleStrategyFactory;
import com.zlt.aps.lh.engine.strategy.ICapacityCalculateStrategy;
import com.zlt.aps.lh.engine.strategy.IFirstInspectionBalanceStrategy;
import com.zlt.aps.lh.engine.strategy.IMachineMatchStrategy;
import com.zlt.aps.lh.engine.strategy.IMouldChangeBalanceStrategy;
import com.zlt.aps.lh.engine.strategy.IProductionStrategy;
import com.zlt.aps.lh.engine.strategy.ITypeBlockProductionStrategy;
import com.zlt.aps.lh.engine.strategy.support.DailyMachineExpansionPlanner;
import com.zlt.aps.lh.engine.strategy.support.MouldResourceAllocationResult;
import com.zlt.aps.lh.engine.strategy.support.MouldResourceContext;
import com.zlt.aps.lh.engine.strategy.support.SpecialMaterialSubstitutionRecord;
import com.zlt.aps.lh.engine.strategy.support.SpecifiedMachineScheduleResult;
import com.zlt.aps.lh.util.LhMachineHardMatchUtil;
import com.zlt.aps.lh.util.LhScheduleTimeUtil;
import com.zlt.aps.lh.util.LhSingleControlMachineUtil;
import com.zlt.aps.lh.util.LhSpecialMaterialUtil;
import com.zlt.aps.lh.util.ShiftCapacityResolverUtil;
import com.zlt.aps.lh.util.ShiftFieldUtil;
import com.zlt.aps.lh.util.SkuDailyPlanQuotaUtil;
import com.zlt.aps.mdm.api.domain.entity.MdmDevicePlanShut;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import javax.annotation.Resource;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 特殊材料 SKU 硫化机置换服务。
 *
 * <p>本服务在续作、换活字块和新增排产全部完成后执行。置换只允许使用 S4.5 开始前冻结的
 * 原始续作在机结果，通过“无副作用预演 -> 局部截断续作尾量 -> 指定机台复用新增主链 ->
 * 成功后恢复截断数量”的顺序完成，禁止删除机台整个排程窗口，也禁止回落到其他新增机台。</p>
 *
 * <p>候选优先级：目标日起三天内喷砂、两天内月计划降模、三十天内精度/保养计划、
 * 胎胚库存低。精度/保养只读取 {@code maintenancePlanMap}，设备停机类型05计划性维修
 * 不属于本层候选来源。</p>
 *
 * @author APS
 */
@Slf4j
@Service
public class SpecialMaterialMachineSubstitutionService {

    /** 喷砂候选窗口天数，包含目标日 */
    private static final int SAND_BLAST_WITHIN_DAYS = 3;
    /** 月计划降模候选窗口天数，包含目标日 */
    private static final int MONTH_PLAN_REDUCE_WITHIN_DAYS = 2;
    /** 精度/保养候选窗口天数，包含目标日 */
    private static final int MAINTENANCE_PLAN_WITHIN_DAYS = 30;
    /** 未排原因：窗口内没有正向月计划日计划量 */
    private static final String UNSCHEDULED_REASON_NO_PLAN_DATE =
            "特殊材料SKU排程窗口内无月计划日计划量";
    /** 未排原因：没有可置换的续作在机机台 */
    private static final String UNSCHEDULED_REASON_NO_CANDIDATE =
            "特殊材料SKU未匹配到可置换的续作在机机台";
    /** 未排原因：候选均无法完成实际上机 */
    private static final String UNSCHEDULED_REASON_STILL_UNSCHEDULED =
            "特殊材料SKU置换后仍未能排上机台";
    /** 被置换续作 SKU 未排原因前缀 */
    private static final String UNSCHEDULED_REASON_REPLACED =
            "被特殊材料SKU置换提前下机";

    @Resource
    private ScheduleStrategyFactory strategyFactory;

    @Resource
    private TargetScheduleQtyResolver targetScheduleQtyResolver;

    /** 换活字块指定机台入口，置换时复用现有同胎胚、同模具判断和切换主链 */
    @Resource
    private ITypeBlockProductionStrategy typeBlockProductionStrategy;

    /**
     * 执行特殊材料硫化机置换。
     *
     * @param context 排程上下文
     */
    public void substitute(LhScheduleContext context) {
        if (Objects.isNull(context) || CollectionUtils.isEmpty(context.getSpecialMaterialBomList())) {
            return;
        }
        List<LhUnscheduledResult> pendingList = identifyUnscheduledSpecialMaterialSkus(context);
        if (CollectionUtils.isEmpty(pendingList)) {
            log.info("特殊材料硫化机置换跳过，未发现仍有待排量且未匹配机台的特殊材料SKU");
            return;
        }
        sortUnscheduledSpecialMaterialList(pendingList);
        int successSkuCount = 0;
        for (LhUnscheduledResult unscheduled : pendingList) {
            if (substituteOneSku(context, unscheduled)) {
                successSkuCount++;
            }
        }
        log.info("特殊材料硫化机置换完成, 工厂: {}, 批次: {}, 待置换SKU数: {}, 成功SKU数: {}, 失败SKU数: {}",
                context.getFactoryCode(), context.getBatchNo(), pendingList.size(),
                successSkuCount, pendingList.size() - successSkuCount);
    }

    /**
     * 识别仍需置换的特殊材料 SKU。
     *
     * @param context 排程上下文
     * @return 待置换未排结果
     */
    private List<LhUnscheduledResult> identifyUnscheduledSpecialMaterialSkus(LhScheduleContext context) {
        List<LhUnscheduledResult> resultList = new ArrayList<LhUnscheduledResult>(8);
        Set<String> scheduledSkuKeySet = collectScheduledSkuKeys(context);
        for (LhUnscheduledResult unscheduled : context.getUnscheduledResultList()) {
            if (Objects.isNull(unscheduled) || Objects.isNull(unscheduled.getUnscheduledQty())
                    || unscheduled.getUnscheduledQty() <= 0) {
                continue;
            }
            String skuKey = MonthPlanDateResolver.buildMaterialStatusKey(
                    unscheduled.getMaterialCode(), unscheduled.getProductStatus());
            if (scheduledSkuKeySet.contains(skuKey)
                    || !isSpecialMaterial(context, unscheduled.getMaterialCode(),
                    unscheduled.getStructureName())) {
                continue;
            }
            resultList.add(unscheduled);
        }
        return resultList;
    }

    /**
     * 收集已经排上机台的业务 SKU 复合键。
     *
     * @param context 排程上下文
     * @return 物料和产品状态复合键集合
     */
    private Set<String> collectScheduledSkuKeys(LhScheduleContext context) {
        Set<String> skuKeySet = new HashSet<String>(64);
        for (LhScheduleResult result : context.getScheduleResultList()) {
            if (Objects.nonNull(result) && StringUtils.isNotEmpty(result.getMaterialCode())
                    && ShiftFieldUtil.resolveScheduledQty(result) > 0) {
                skuKeySet.add(MonthPlanDateResolver.buildMaterialStatusKey(
                        result.getMaterialCode(), result.getProductStatus()));
            }
        }
        return skuKeySet;
    }

    /**
     * 判断物料是否命中特殊材料清单。
     *
     * @param context 排程上下文
     * @param materialCode 物料编码
     * @param structureName 结构名称
     * @return true-特殊材料；false-普通物料
     */
    private boolean isSpecialMaterial(LhScheduleContext context,
                                      String materialCode,
                                      String structureName) {
        SkuScheduleDTO probeSku = new SkuScheduleDTO();
        probeSku.setMaterialCode(materialCode);
        probeSku.setStructureName(structureName);
        SpecialMaterialMatchResult matchResult =
                LhSpecialMaterialUtil.resolveMatchResult(context, probeSku);
        return matchResult.isSpecial();
    }

    /**
     * 按未排数量降序、物料编码和产品状态稳定排序。
     *
     * @param unscheduledList 待置换未排结果
     */
    private void sortUnscheduledSpecialMaterialList(List<LhUnscheduledResult> unscheduledList) {
        unscheduledList.sort(Comparator
                .comparing(LhUnscheduledResult::getUnscheduledQty,
                        Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(LhUnscheduledResult::getMaterialCode,
                        Comparator.nullsLast(String::compareTo))
                .thenComparing(LhUnscheduledResult::getProductStatus,
                        Comparator.nullsLast(String::compareTo)));
    }

    /**
     * 对单个特殊材料 SKU 执行多台置换。
     *
     * @param context 排程上下文
     * @param unscheduled 特殊材料未排结果
     * @return true-至少成功置换一台；false-全部候选失败
     */
    private boolean substituteOneSku(LhScheduleContext context, LhUnscheduledResult unscheduled) {
        SkuScheduleDTO sku = resolveSkuScheduleDto(
                context, unscheduled.getMaterialCode(), unscheduled.getProductStatus());
        if (Objects.isNull(sku)) {
            log.warn("特殊材料置换跳过，未找到SKU排程信息, materialCode: {}, productStatus: {}",
                    unscheduled.getMaterialCode(), unscheduled.getProductStatus());
            return false;
        }
        int remainingTargetQty = targetScheduleQtyResolver.resolveProductionRemainingQty(context, sku);
        if (remainingTargetQty <= 0) {
            log.info("特殊材料置换跳过，SKU运行态剩余量已为0, materialCode: {}, productStatus: {}",
                    sku.getMaterialCode(), sku.getProductStatus());
            return false;
        }

        LocalDate firstPlanDate = resolveFirstPositivePlanDate(context, sku);
        if (Objects.isNull(firstPlanDate)) {
            updateUnscheduledReason(unscheduled, UNSCHEDULED_REASON_NO_PLAN_DATE);
            log.info("特殊材料置换失败，排程窗口内无月计划日计划量, materialCode: {}, productStatus: {}",
                    sku.getMaterialCode(), sku.getProductStatus());
            return false;
        }

        List<LhScheduleResult> allCandidates =
                collectContinuationCandidates(context, sku, firstPlanDate, Collections.<String>emptySet());
        int requiredMachineCount = DailyMachineExpansionPlanner.resolveSpecialMaterialRequiredMachineCount(
                context, sku, firstPlanDate, remainingTargetQty, allCandidates.size());
        if (requiredMachineCount <= 0) {
            updateUnscheduledReason(unscheduled, UNSCHEDULED_REASON_NO_CANDIDATE);
            return false;
        }

        log.info("特殊材料置换开始, materialCode: {}, productStatus: {}, 首个有量日期: {}, "
                        + "待排量: {}, 续作候选数: {}, 需置换机台数: {}",
                sku.getMaterialCode(), sku.getProductStatus(), firstPlanDate,
                remainingTargetQty, allCandidates.size(), requiredMachineCount);

        Set<String> attemptedMachineCodeSet = new LinkedHashSet<String>(allCandidates.size());
        int successMachineCount = 0;
        while (successMachineCount < requiredMachineCount
                && targetScheduleQtyResolver.resolveProductionRemainingQty(context, sku) > 0) {
            LhScheduleResult candidate = selectBestCandidateMachine(
                    context, sku, firstPlanDate, attemptedMachineCodeSet);
            if (Objects.isNull(candidate)) {
                break;
            }
            attemptedMachineCodeSet.add(candidate.getLhMachineCode());
            SubstitutionTypeEnum substitutionType =
                    resolveSubstitutionType(context, candidate, firstPlanDate);
            Date previewChangeStartTime = previewSubstitution(
                    context, sku, candidate, firstPlanDate);
            if (Objects.isNull(previewChangeStartTime)) {
                log.info("特殊材料置换候选预演失败，继续下一候选, materialCode: {}, productStatus: {}, "
                                + "machineCode: {}, hitType: {}",
                        sku.getMaterialCode(), sku.getProductStatus(), candidate.getLhMachineCode(),
                        substitutionType.getDescription());
                continue;
            }
            if (executeSubstitution(context, sku, candidate, firstPlanDate,
                    previewChangeStartTime, substitutionType)) {
                successMachineCount++;
            }
        }

        int finalRemainingQty = targetScheduleQtyResolver.resolveProductionRemainingQty(context, sku);
        if (successMachineCount <= 0) {
            updateUnscheduledReason(unscheduled,
                    attemptedMachineCodeSet.isEmpty()
                            ? UNSCHEDULED_REASON_NO_CANDIDATE : UNSCHEDULED_REASON_STILL_UNSCHEDULED);
            return false;
        }

        removeUnscheduledResult(context, unscheduled.getMaterialCode(), unscheduled.getProductStatus());
        if (finalRemainingQty > 0) {
            String reason = String.format(
                    "特殊材料SKU置换不足：按加机台规则需要 %d 台，实际成功置换 %d 台",
                    requiredMachineCount, successMachineCount);
            addOrMergeSpecialMaterialUnscheduledResult(context, sku, finalRemainingQty, reason);
        }
        log.info("特殊材料置换SKU处理完成, materialCode: {}, productStatus: {}, "
                        + "成功机台数: {}/{}, 最终剩余量: {}",
                sku.getMaterialCode(), sku.getProductStatus(), successMachineCount,
                requiredMachineCount, finalRemainingQty);
        return true;
    }

    /**
     * 查找排程窗口内首个有正向月计划日计划量的日期。
     *
     * @param context 排程上下文
     * @param sku 特殊材料 SKU
     * @return 首个有量日期；窗口内无量返回null
     */
    private LocalDate resolveFirstPositivePlanDate(LhScheduleContext context, SkuScheduleDTO sku) {
        LocalDate windowStartDate = toLocalDate(context.getScheduleDate());
        LocalDate windowEndDate = resolveWindowEndDate(context);
        if (Objects.isNull(windowStartDate) || Objects.isNull(windowEndDate)) {
            return null;
        }
        LocalDate cursorDate = windowStartDate;
        while (!cursorDate.isAfter(windowEndDate)) {
            int dayPlanQty = MonthPlanDateResolver.resolveDayQty(
                    context, sku.getMaterialCode(), sku.getProductStatus(), cursorDate);
            if (dayPlanQty > 0) {
                return cursorDate;
            }
            cursorDate = cursorDate.plusDays(1);
        }
        return null;
    }

    /**
     * 解析排程窗口最后一个业务日。
     *
     * @param context 排程上下文
     * @return 窗口结束日期
     */
    private LocalDate resolveWindowEndDate(LhScheduleContext context) {
        LocalDate windowEndDate = null;
        for (LhShiftConfigVO shift : context.getScheduleWindowShifts()) {
            if (Objects.isNull(shift) || Objects.isNull(shift.getWorkDate())) {
                continue;
            }
            LocalDate workDate = toLocalDate(shift.getWorkDate());
            if (Objects.isNull(windowEndDate) || workDate.isAfter(windowEndDate)) {
                windowEndDate = workDate;
            }
        }
        if (Objects.nonNull(windowEndDate)) {
            return windowEndDate;
        }
        /*
         * 正常排程上下文以班次窗口为准；单元预演或初始化早期班次尚未装载时，
         * 继续使用已解析的窗口结束时间，禁止退化为只扫描 T 日。
         */
        return Objects.nonNull(context.getWindowEndDate())
                ? toLocalDate(context.getWindowEndDate())
                : toLocalDate(context.getScheduleDate());
    }

    /**
     * 收集允许置换的续作在机结果。
     *
     * @param context 排程上下文
     * @param sku 特殊材料 SKU
     * @param targetDate 特殊材料首个有量日期
     * @param excludedMachineCodeSet 已尝试机台集合
     * @return 通过基础预检的续作候选
     */
    private List<LhScheduleResult> collectContinuationCandidates(
            LhScheduleContext context,
            SkuScheduleDTO sku,
            LocalDate targetDate,
            Set<String> excludedMachineCodeSet) {
        List<LhScheduleResult> candidateList = new ArrayList<LhScheduleResult>(16);
        Set<String> collectedMachineCodeSet = new LinkedHashSet<String>(16);
        for (LhScheduleResult result : context.getSpecialMaterialContinuationResultSnapshot()) {
            if (!isCurrentContinuationCandidate(
                    context, sku, result, targetDate, excludedMachineCodeSet)) {
                continue;
            }
            if (collectedMachineCodeSet.add(result.getLhMachineCode())) {
                candidateList.add(result);
            }
        }
        return candidateList;
    }

    /**
     * 校验冻结的续作结果当前是否仍可作为置换候选。
     *
     * @param context 排程上下文
     * @param specialSku 特殊材料 SKU
     * @param result 冻结的续作结果
     * @param targetDate 特殊材料首个有量日期
     * @param excludedMachineCodeSet 已尝试机台
     * @return true-可进入候选；false-必须跳过
     */
    private boolean isCurrentContinuationCandidate(
            LhScheduleContext context,
            SkuScheduleDTO specialSku,
            LhScheduleResult result,
            LocalDate targetDate,
            Set<String> excludedMachineCodeSet) {
        if (Objects.isNull(result)
                || !context.getScheduleResultList().contains(result)
                || !ScheduleTypeEnum.CONTINUOUS.getCode().equals(result.getScheduleType())
                || StringUtils.isEmpty(result.getLhMachineCode())
                || excludedMachineCodeSet.contains(result.getLhMachineCode())
                || context.isStructureMinMachineRetained(result.getLhMachineCode())
                || isSpecialMaterial(context, result.getMaterialCode(), result.getStructureName())
                || !hasPositivePlanQtyOnDate(context, result, targetDate)
                || hasNonContinuationResultOnOrAfterTargetDate(context, result.getLhMachineCode(), targetDate)) {
            return false;
        }
        MachineScheduleDTO machine = context.getMachineScheduleMap().get(result.getLhMachineCode());
        return Objects.nonNull(machine)
                && LhMachineHardMatchUtil.isMachineHardMatched(context, specialSku, machine)
                && isMouldAvailable(context, specialSku, result.getLhMachineCode());
    }

    /**
     * 判断结果在指定业务日是否仍有正计划量。
     *
     * @param context 排程上下文
     * @param result 排程结果
     * @param targetDate 业务日期
     * @return true-该日仍在生产；false-该日无量
     */
    private boolean hasPositivePlanQtyOnDate(
            LhScheduleContext context,
            LhScheduleResult result,
            LocalDate targetDate) {
        for (LhShiftConfigVO shift : context.getScheduleWindowShifts()) {
            if (Objects.isNull(shift) || Objects.isNull(shift.getShiftIndex())
                    || Objects.isNull(shift.getWorkDate())
                    || !targetDate.equals(toLocalDate(shift.getWorkDate()))) {
                continue;
            }
            Integer planQty = ShiftFieldUtil.getShiftPlanQty(result, shift.getShiftIndex());
            if (Objects.nonNull(planQty) && planQty > 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断目标机台从特殊材料目标日起是否已经承接换活字块或新增结果。
     *
     * <p>只要存在冻结续作结果之外的正计划量，就跳过整台机台，避免置换影响已经完成的
     * S4.4 换活字块或 S4.5 新增排产。</p>
     *
     * @param context 排程上下文
     * @param machineCode 机台编码
     * @param targetDate 特殊材料首个有量日期
     * @return true-存在不得影响的后续结果；false-只有原续作结果
     */
    private boolean hasNonContinuationResultOnOrAfterTargetDate(
            LhScheduleContext context,
            String machineCode,
            LocalDate targetDate) {
        for (LhScheduleResult result : context.getScheduleResultList()) {
            if (!StringUtils.equals(machineCode, result.getLhMachineCode())
                    || context.getSpecialMaterialContinuationResultSnapshot().contains(result)) {
                continue;
            }
            for (LhShiftConfigVO shift : context.getScheduleWindowShifts()) {
                if (Objects.isNull(shift) || Objects.isNull(shift.getShiftIndex())
                        || Objects.isNull(shift.getWorkDate())
                        || toLocalDate(shift.getWorkDate()).isBefore(targetDate)) {
                    continue;
                }
                Integer planQty = ShiftFieldUtil.getShiftPlanQty(result, shift.getShiftIndex());
                if (Objects.nonNull(planQty) && planQty > 0) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 无副作用预检特殊材料模具资源。
     *
     * @param context 排程上下文
     * @param sku 特殊材料 SKU
     * @param machineCode 候选机台
     * @return true-模具可用；false-模具不足或状态不可用
     */
    private boolean isMouldAvailable(LhScheduleContext context,
                                     SkuScheduleDTO sku,
                                     String machineCode) {
        MouldResourceContext mouldResourceContext = context.getMouldResourceContext();
        if (Objects.isNull(mouldResourceContext)) {
            return true;
        }
        MouldResourceAllocationResult allocationResult =
                mouldResourceContext.previewAllocate(sku.getMaterialCode(), machineCode);
        if (allocationResult.isAllowed()) {
            return true;
        }
        log.info("特殊材料置换模具预检失败, materialCode: {}, productStatus: {}, machineCode: {}, reason: {}",
                sku.getMaterialCode(), sku.getProductStatus(), machineCode,
                allocationResult.getSkipReason());
        return false;
    }

    /**
     * 按四层优先级选择下一台候选机台。
     *
     * @param context 排程上下文
     * @param sku 特殊材料 SKU
     * @param targetDate 特殊材料首个有量日期
     * @param attemptedMachineCodeSet 已尝试机台
     * @return 最佳候选；没有候选返回null
     */
    private LhScheduleResult selectBestCandidateMachine(
            LhScheduleContext context,
            SkuScheduleDTO sku,
            LocalDate targetDate,
            Set<String> attemptedMachineCodeSet) {
        List<LhScheduleResult> candidates =
                collectContinuationCandidates(context, sku, targetDate, attemptedMachineCodeSet);
        if (CollectionUtils.isEmpty(candidates)) {
            return null;
        }
        LhScheduleResult candidate = selectSandBlastCandidate(context, candidates, targetDate);
        if (Objects.nonNull(candidate)) {
            return candidate;
        }
        candidate = selectMonthPlanReduceCandidate(context, candidates, targetDate);
        if (Objects.nonNull(candidate)) {
            return candidate;
        }
        candidate = selectMaintenanceCandidate(context, candidates, targetDate);
        if (Objects.nonNull(candidate)) {
            return candidate;
        }
        return candidates.stream()
                .min(Comparator
                        .comparing(LhScheduleResult::getEmbryoStock,
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(LhScheduleResult::getLhMachineCode,
                                Comparator.nullsLast(String::compareTo)))
                .orElse(null);
    }

    /**
     * 选择目标日起三天内最早命中喷砂计划的机台。
     *
     * @param context 排程上下文
     * @param candidates 候选结果
     * @param targetDate 特殊材料首个有量日期
     * @return 喷砂层最佳候选
     */
    private LhScheduleResult selectSandBlastCandidate(
            LhScheduleContext context,
            List<LhScheduleResult> candidates,
            LocalDate targetDate) {
        Map<LhScheduleResult, Date> hitTimeMap =
                new IdentityHashMap<LhScheduleResult, Date>(candidates.size());
        LocalDate deadline = resolveInclusiveDeadline(
                targetDate, SAND_BLAST_WITHIN_DAYS);
        for (LhScheduleResult candidate : candidates) {
            Date hitTime = findSandBlastTime(
                    context, candidate.getLhMachineCode(), targetDate, deadline);
            if (Objects.nonNull(hitTime)) {
                hitTimeMap.put(candidate, hitTime);
            }
        }
        return hitTimeMap.keySet().stream()
                .min(Comparator.<LhScheduleResult, Date>comparing(hitTimeMap::get)
                        .thenComparing(LhScheduleResult::getLhMachineCode,
                                Comparator.nullsLast(String::compareTo)))
                .orElse(null);
    }

    /**
     * 从当前设备停机计划中查找喷砂计划时间。
     *
     * @param context 排程上下文
     * @param machineCode 运行态机台编码
     * @param startDate 开始日期
     * @param endDate 结束日期
     * @return 最近喷砂开始时间；未命中返回null
     */
    private Date findSandBlastTime(
            LhScheduleContext context,
            String machineCode,
            LocalDate startDate,
            LocalDate endDate) {
        String physicalMachineCode = LhSingleControlMachineUtil.resolvePhysicalMachineCode(machineCode);
        Date nearestTime = null;
        for (MdmDevicePlanShut plan : context.getDevicePlanShutList()) {
            if (Objects.isNull(plan)
                    || !MachineStopTypeEnum.SANDBLASTING_CLEANING.getCode()
                    .equals(plan.getMachineStopType())
                    || Objects.isNull(plan.getBeginDate())
                    || !StringUtils.equals(physicalMachineCode,
                    LhSingleControlMachineUtil.resolvePhysicalMachineCode(plan.getMachineCode()))) {
                continue;
            }
            LocalDate planDate = toLocalDate(plan.getBeginDate());
            if (planDate.isBefore(startDate) || planDate.isAfter(endDate)) {
                continue;
            }
            if (Objects.isNull(nearestTime) || plan.getBeginDate().before(nearestTime)) {
                nearestTime = plan.getBeginDate();
            }
        }
        return nearestTime;
    }

    /**
     * 选择目标日起两天内最早满足月计划降模的机台。
     *
     * @param context 排程上下文
     * @param candidates 候选结果
     * @param targetDate 特殊材料首个有量日期
     * @return 月计划降模层最佳候选
     */
    private LhScheduleResult selectMonthPlanReduceCandidate(
            LhScheduleContext context,
            List<LhScheduleResult> candidates,
            LocalDate targetDate) {
        Map<LhScheduleResult, Integer> reduceDayMap =
                new IdentityHashMap<LhScheduleResult, Integer>(candidates.size());
        for (LhScheduleResult candidate : candidates) {
            int reduceDay = resolveMonthPlanReduceDay(context, candidate, targetDate);
            if (reduceDay >= 0 && reduceDay < MONTH_PLAN_REDUCE_WITHIN_DAYS) {
                reduceDayMap.put(candidate, reduceDay);
            }
        }
        return reduceDayMap.keySet().stream()
                .min(Comparator.<LhScheduleResult, Integer>comparing(reduceDayMap::get)
                        .thenComparing(LhScheduleResult::getLhMachineCode,
                                Comparator.nullsLast(String::compareTo)))
                .orElse(null);
    }

    /**
     * 解析候选续作 SKU 最近月计划降模日期偏移。
     *
     * @param context 排程上下文
     * @param result 候选续作结果
     * @param targetDate 特殊材料首个有量日期
     * @return 0-目标日，1-次日，2-后日，-1-不满足降模
     */
    private int resolveMonthPlanReduceDay(
            LhScheduleContext context,
            LhScheduleResult result,
            LocalDate targetDate) {
        Set<String> activeMachineCodeSet = new LinkedHashSet<String>(4);
        for (LhScheduleResult scheduledResult
                : context.getSpecialMaterialContinuationResultSnapshot()) {
            if (context.getScheduleResultList().contains(scheduledResult)
                    && isSameSku(result.getMaterialCode(), result.getProductStatus(), scheduledResult)
                    && hasPositivePlanQtyOnDate(context, scheduledResult, targetDate)) {
                activeMachineCodeSet.add(scheduledResult.getLhMachineCode());
            }
        }
        if (activeMachineCodeSet.size() <= 1) {
            return -1;
        }
        int dailyCapacity = Objects.isNull(result.getStandardCapacity())
                ? 0 : result.getStandardCapacity();
        if (dailyCapacity <= 0) {
            return -1;
        }
        for (int dayOffset = 0; dayOffset <= MONTH_PLAN_REDUCE_WITHIN_DAYS; dayOffset++) {
            LocalDate businessDate = targetDate.plusDays(dayOffset);
            int dayPlanQty = MonthPlanDateResolver.resolveDayQty(
                    context, result.getMaterialCode(), result.getProductStatus(), businessDate);
            int requiredMachineCount = dayPlanQty <= 0
                    ? 0 : (dayPlanQty + dailyCapacity - 1) / dailyCapacity;
            if (requiredMachineCount < activeMachineCodeSet.size()) {
                return dayOffset;
            }
        }
        return -1;
    }

    /**
     * 选择目标日起三十天内最早命中精度/保养计划的机台。
     *
     * <p>只读取 {@code maintenancePlanMap}，不读取设备停机计划，因此05计划性维修不会进入本层。</p>
     *
     * @param context 排程上下文
     * @param candidates 候选结果
     * @param targetDate 特殊材料首个有量日期
     * @return 精度/保养层最佳候选
     */
    private LhScheduleResult selectMaintenanceCandidate(
            LhScheduleContext context,
            List<LhScheduleResult> candidates,
            LocalDate targetDate) {
        Map<LhScheduleResult, LocalDate> planDateMap =
                new IdentityHashMap<LhScheduleResult, LocalDate>(candidates.size());
        LocalDate deadline = resolveInclusiveDeadline(
                targetDate, MAINTENANCE_PLAN_WITHIN_DAYS);
        for (LhScheduleResult candidate : candidates) {
            LhPrecisionPlan plan = resolveMaintenancePlan(
                    context, candidate.getLhMachineCode());
            if (Objects.isNull(plan) || Objects.isNull(plan.getPlanDate())) {
                continue;
            }
            LocalDate planDate = toLocalDate(plan.getPlanDate());
            if (!planDate.isBefore(targetDate) && !planDate.isAfter(deadline)) {
                planDateMap.put(candidate, planDate);
            }
        }
        return planDateMap.keySet().stream()
                .min(Comparator.<LhScheduleResult, LocalDate>comparing(planDateMap::get)
                        .thenComparing(LhScheduleResult::getLhMachineCode,
                                Comparator.nullsLast(String::compareTo)))
                .orElse(null);
    }

    /**
     * 按运行态机台和物理机台解析精度/保养计划。
     *
     * @param context 排程上下文
     * @param machineCode 运行态机台编码
     * @return 精度/保养计划
     */
    private LhPrecisionPlan resolveMaintenancePlan(
            LhScheduleContext context,
            String machineCode) {
        LhPrecisionPlan plan = context.getMaintenancePlanMap().get(machineCode);
        if (Objects.nonNull(plan)) {
            return plan;
        }
        return context.getMaintenancePlanMap().get(
                LhSingleControlMachineUtil.resolvePhysicalMachineCode(machineCode));
    }

    /**
     * 解析候选命中的最高置换类型。
     *
     * @param context 排程上下文
     * @param result 候选续作结果
     * @param targetDate 特殊材料首个有量日期
     * @return 置换类型
     */
    private SubstitutionTypeEnum resolveSubstitutionType(
            LhScheduleContext context,
            LhScheduleResult result,
            LocalDate targetDate) {
        if (Objects.nonNull(findSandBlastTime(
                context, result.getLhMachineCode(), targetDate,
                resolveInclusiveDeadline(targetDate, SAND_BLAST_WITHIN_DAYS)))) {
            return SubstitutionTypeEnum.SAND_BLAST_SUBSTITUTION;
        }
        int reduceDay = resolveMonthPlanReduceDay(context, result, targetDate);
        if (reduceDay >= 0 && reduceDay < MONTH_PLAN_REDUCE_WITHIN_DAYS) {
            return SubstitutionTypeEnum.MONTH_PLAN_REDUCE_SUBSTITUTION;
        }
        LhPrecisionPlan plan = resolveMaintenancePlan(context, result.getLhMachineCode());
        if (Objects.nonNull(plan) && Objects.nonNull(plan.getPlanDate())) {
            LocalDate planDate = toLocalDate(plan.getPlanDate());
            if (!planDate.isBefore(targetDate)
                    && !planDate.isAfter(resolveInclusiveDeadline(
                    targetDate, MAINTENANCE_PLAN_WITHIN_DAYS))) {
                return SubstitutionTypeEnum.PRECISION_PLAN_SUBSTITUTION;
            }
        }
        return SubstitutionTypeEnum.LOW_EMBRYO_STOCK_SUBSTITUTION;
    }

    /**
     * 计算“从目标日起 N 天内”的包含式截止日。
     *
     * <p>目标日计作第1天，因此3天内为 T～T+2，2天内为 T～T+1。</p>
     *
     * @param targetDate 目标日期
     * @param inclusiveDayCount 包含目标日的天数
     * @return 包含式截止日期
     */
    private LocalDate resolveInclusiveDeadline(
            LocalDate targetDate,
            int inclusiveDayCount) {
        return targetDate.plusDays(Math.max(0, inclusiveDayCount - 1L));
    }

    /**
     * 无副作用预演候选机台的实际换模落点。
     *
     * @param context 排程上下文
     * @param sku 特殊材料 SKU
     * @param candidate 候选续作结果
     * @param targetDate 特殊材料首个有量日期
     * @return 预演换模开始时间；候选不可行返回null
     */
    private Date previewSubstitution(
            LhScheduleContext context,
            SkuScheduleDTO sku,
            LhScheduleResult candidate,
            LocalDate targetDate) {
        Date earliestSwitchTime = resolveEarliestShiftStartTime(context, targetDate);
        if (Objects.isNull(earliestSwitchTime)) {
            return null;
        }
        IMouldChangeBalanceStrategy mouldChangeStrategy =
                strategyFactory.getMouldChangeBalanceStrategy();
        Map<String, int[]> simulatedCountMap =
                copyMouldChangeCountMap(context.getDailyMouldChangeCountMap());
        MachineScheduleDTO candidateMachine =
                context.getMachineScheduleMap().get(candidate.getLhMachineCode());
        int switchDurationHours = resolvePreviewSwitchDurationHours(
                context, candidateMachine, sku);
        Date previewChangeStartTime = mouldChangeStrategy.previewEndingStaggerMouldChange(
                context, candidate.getLhMachineCode(), earliestSwitchTime,
                switchDurationHours, sku, simulatedCountMap);
        if (Objects.isNull(previewChangeStartTime)
                || previewChangeStartTime.before(earliestSwitchTime)
                || !isWholeChangeExecutionTimeAllowed(
                context, previewChangeStartTime, switchDurationHours)
                || !hasPositiveSpecialMaterialCapacityAfterChange(
                context, sku, candidate.getLhMachineCode(), previewChangeStartTime,
                switchDurationHours)) {
            return null;
        }
        log.info("特殊材料置换预演成功, materialCode: {}, productStatus: {}, machineCode: {}, "
                        + "目标日期: {}, 实际预演换模开始: {}, 换模时长小时: {}",
                sku.getMaterialCode(), sku.getProductStatus(), candidate.getLhMachineCode(),
                targetDate, LhScheduleTimeUtil.formatDateTime(previewChangeStartTime),
                switchDurationHours);
        return previewChangeStartTime;
    }

    /**
     * 按现有换活字块关系选择候选预演时长。
     *
     * <p>同胎胚、同模具等条件满足时使用换活字块时长，否则使用正规换模时长。
     * 判断入口只读取运行态，不登记换模次数，也不写入排程结果。</p>
     *
     * @param context 排程上下文
     * @param machine 候选续作机台
     * @param sku 特殊材料 SKU
     * @return 本次候选应使用的切换时长，单位小时
     */
    private int resolvePreviewSwitchDurationHours(
            LhScheduleContext context,
            MachineScheduleDTO machine,
            SkuScheduleDTO sku) {
        boolean typeBlockApplicable = Objects.nonNull(machine)
                && typeBlockProductionStrategy.isSpecialMaterialSubstitutionTypeBlockApplicable(
                context, machine, sku);
        return typeBlockApplicable
                ? LhScheduleTimeUtil.getTypeBlockChangeTotalHours(context)
                : LhScheduleTimeUtil.getMouldChangeTotalHours(context);
    }

    /**
     * 校验整个换模或换活字块执行区间均未进入20:00后的禁换模时段。
     *
     * <p>现有时间工具负责判断开始时点，本方法额外校验执行结束边界，避免19:00开始、
     * 21:00结束这类“开始合法但执行过程跨入20:00以后”的置换。</p>
     *
     * @param context 排程上下文
     * @param changeStartTime 实际或预演切换开始时间
     * @param switchDurationHours 切换时长，单位小时
     * @return true-整个执行区间合法；false-必须顺延或更换候选
     */
    private boolean isWholeChangeExecutionTimeAllowed(
            LhScheduleContext context,
            Date changeStartTime,
            int switchDurationHours) {
        if (Objects.isNull(changeStartTime)
                || LhScheduleTimeUtil.isNoMouldChangeTime(context, changeStartTime)) {
            return false;
        }
        LocalDateTime startDateTime = changeStartTime.toInstant()
                .atZone(ZoneId.systemDefault()).toLocalDateTime();
        LocalDateTime endDateTime = startDateTime.plusHours(Math.max(0, switchDurationHours));
        LocalDateTime noChangeBoundary = startDateTime.toLocalDate()
                .atTime(LhScheduleTimeUtil.getNoMouldChangeStartHour(context), 0);
        return !endDateTime.isAfter(noChangeBoundary);
    }

    /**
     * 解析指定业务日最早班次开始时间。
     *
     * @param context 排程上下文
     * @param targetDate 业务日期
     * @return 最早班次开始时间
     */
    private Date resolveEarliestShiftStartTime(
            LhScheduleContext context,
            LocalDate targetDate) {
        Date earliestTime = null;
        for (LhShiftConfigVO shift : context.getScheduleWindowShifts()) {
            if (Objects.isNull(shift) || Objects.isNull(shift.getWorkDate())
                    || Objects.isNull(shift.getShiftStartDateTime())
                    || !targetDate.equals(toLocalDate(shift.getWorkDate()))) {
                continue;
            }
            if (Objects.isNull(earliestTime) || shift.getShiftStartDateTime().before(earliestTime)) {
                earliestTime = shift.getShiftStartDateTime();
            }
        }
        return earliestTime;
    }

    /**
     * 校验换模完成后窗口内是否至少能产生一个模次的有效计划量。
     *
     * @param context 排程上下文
     * @param sku 特殊材料 SKU
     * @param machineCode 候选机台
     * @param changeStartTime 换模开始时间
     * @param switchDurationHours 换模时长
     * @return true-至少存在正产能；false-无法产生有效计划量
     */
    private boolean hasPositiveSpecialMaterialCapacityAfterChange(
            LhScheduleContext context,
            SkuScheduleDTO sku,
            String machineCode,
            Date changeStartTime,
            int switchDurationHours) {
        if (sku.getLhTimeSeconds() <= 0) {
            return false;
        }
        Date productionReadyTime = new Date(changeStartTime.getTime()
                + switchDurationHours * 60L * 60L * 1000L);
        MachineScheduleDTO machine = context.getMachineScheduleMap().get(machineCode);
        int machineMouldQty = ShiftCapacityResolverUtil.resolveMachineMouldQty(machine);
        ICapacityCalculateStrategy capacityStrategy = strategyFactory.getCapacityCalculateStrategy();
        for (LhShiftConfigVO shift : context.getScheduleWindowShifts()) {
            if (Objects.isNull(shift) || Objects.isNull(shift.getShiftEndDateTime())
                    || !shift.getShiftEndDateTime().after(productionReadyTime)) {
                continue;
            }
            Date effectiveStartTime = Objects.nonNull(shift.getShiftStartDateTime())
                    && shift.getShiftStartDateTime().after(productionReadyTime)
                    ? shift.getShiftStartDateTime() : productionReadyTime;
            int firstShiftQty = capacityStrategy.calculateFirstShiftQty(
                    effectiveStartTime, shift.getShiftEndDateTime(),
                    sku.getLhTimeSeconds(), machineMouldQty);
            if (firstShiftQty > 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * 执行单台候选的局部截断和指定机台排产。
     *
     * @param context 排程上下文
     * @param specialSku 特殊材料 SKU
     * @param candidate 候选续作结果
     * @param firstPlanDate 特殊材料首个有量日期
     * @param previewChangeStartTime 预演换模开始时间
     * @param substitutionType 置换命中类型
     * @return true-置换成功；false-已完整回滚
     */
    private boolean executeSubstitution(
            LhScheduleContext context,
            SkuScheduleDTO specialSku,
            LhScheduleResult candidate,
            LocalDate firstPlanDate,
            Date previewChangeStartTime,
            SubstitutionTypeEnum substitutionType) {
        String machineCode = candidate.getLhMachineCode();
        SpecialMaterialSubstitutionAttemptSnapshot attemptSnapshot =
                SpecialMaterialSubstitutionAttemptSnapshot.capture(context, specialSku);
        List<LhScheduleResult> continuationResults =
                resolveContinuationResultsOnMachine(context, machineCode);
        Map<LhScheduleResult, LhScheduleResult> originalResultStateMap =
                copyResultStateMap(continuationResults);
        Map<LhScheduleResult, SkuScheduleDTO> originalSourceSkuMap =
                resolveSourceSkuMap(context, continuationResults);
        Map<Integer, Integer> previewRemovedQtyByShift = new LinkedHashMap<Integer, Integer>(8);

        try {
            // 先按预演落点临时截断续作尾量并释放对应班次产能，供指定机台新增主链做真实校验。
            truncateContinuationResults(context, continuationResults, originalSourceSkuMap,
                    previewChangeStartTime, previewRemovedQtyByShift, null, true);
            MachineScheduleDTO machine = context.getMachineScheduleMap().get(machineCode);
            if (Objects.isNull(machine)) {
                attemptSnapshot.restore(context, specialSku);
                return false;
            }
            machine.setEstimatedEndTime(previewChangeStartTime);
            machine.setNextMaterialCode(specialSku.getMaterialCode());

            /*
             * 调用现有新增排产主链时写入临时指定机台和最早换模时点。
             * NewSpecProductionStrategy 会再次执行定点、单控、模具、首检、停机和实际产能校验，
             * 但不会回落其他机台。
             */
            LhScheduleResult specialResult = scheduleSpecialMaterialOnSpecifiedMachine(
                    context, specialSku, machineCode, previewChangeStartTime);
            if (Objects.isNull(specialResult)
                    || !isActualChangeTimeValid(context, specialResult, firstPlanDate)) {
                attemptSnapshot.restore(context, specialSku);
                log.info("特殊材料置换正式排产失败，候选状态已完整恢复, materialCode: {}, "
                                + "productStatus: {}, machineCode: {}",
                        specialSku.getMaterialCode(), specialSku.getProductStatus(), machineCode);
                return false;
            }

            Date actualChangeStartTime = specialResult.getMouldChangeStartTime();
            /*
             * 正式主链可能因同胎胚错峰、首检或停机把换模继续后移。此时必须恢复原续作结果原貌，
             * 再按最终实际换模时间重算保留量，不能沿用较早的预演截断点。
             */
            restoreOriginalContinuationResultStates(
                    context, originalResultStateMap, originalSourceSkuMap);
            Map<Integer, Integer> finalRemovedQtyByShift = new LinkedHashMap<Integer, Integer>(8);
            Map<SkuScheduleDTO, Map<LocalDate, Integer>> removedQtyBySkuDate =
                    new IdentityHashMap<SkuScheduleDTO, Map<LocalDate, Integer>>(4);
            Map<SkuScheduleDTO, Integer> removedQtyBySku =
                    truncateContinuationResults(context, continuationResults, originalSourceSkuMap,
                            actualChangeStartTime, finalRemovedQtyByShift,
                            removedQtyBySkuDate, false);
            adjustReleasedMachineCapacityDelta(
                    context, machineCode, previewRemovedQtyByShift, finalRemovedQtyByShift);
            synchronizeContinuationScheduledMachineMaps(
                    context, originalResultStateMap.values(), continuationResults);
            restoreTruncatedSkuQtyAndWriteUnscheduled(
                    context, machineCode, removedQtyBySku, removedQtyBySkuDate);

            recordSubstitutionInfo(context, candidate, specialResult,
                    substitutionType, actualChangeStartTime);
            log.info("特殊材料置换成功, materialCode: {}, productStatus: {}, machineCode: {}, "
                            + "被置换SKU: {}, 命中类型: {}, 实际下机/换模开始: {}, 特殊材料收尾时间: {}",
                    specialSku.getMaterialCode(), specialSku.getProductStatus(), machineCode,
                    candidate.getMaterialCode(), substitutionType.getDescription(),
                    LhScheduleTimeUtil.formatDateTime(actualChangeStartTime),
                    LhScheduleTimeUtil.formatDateTime(specialResult.getSpecEndTime()));
            return true;
        } catch (Exception ex) {
            attemptSnapshot.restore(context, specialSku);
            log.error("特殊材料置换候选执行异常，已恢复候选前全部运行态, materialCode: {}, "
                            + "productStatus: {}, machineCode: {}",
                    specialSku.getMaterialCode(), specialSku.getProductStatus(), machineCode, ex);
            return false;
        } finally {
            context.clearSpecialMaterialSpecifiedMachineDirective();
        }
    }

    /**
     * 查找指定机台上的冻结续作结果。
     *
     * @param context 排程上下文
     * @param machineCode 机台编码
     * @return 仍存在于当前结果列表的冻结续作结果
     */
    private List<LhScheduleResult> resolveContinuationResultsOnMachine(
            LhScheduleContext context,
            String machineCode) {
        List<LhScheduleResult> resultList = new ArrayList<LhScheduleResult>(2);
        for (LhScheduleResult result : context.getSpecialMaterialContinuationResultSnapshot()) {
            if (context.getScheduleResultList().contains(result)
                    && StringUtils.equals(machineCode, result.getLhMachineCode())) {
                resultList.add(result);
            }
        }
        return resultList;
    }

    /**
     * 深拷贝续作结果字段，供正式换模时间后移时重新计算截断量。
     *
     * @param resultList 续作结果
     * @return 对象身份到字段快照的映射
     */
    private Map<LhScheduleResult, LhScheduleResult> copyResultStateMap(
            List<LhScheduleResult> resultList) {
        Map<LhScheduleResult, LhScheduleResult> stateMap =
                new IdentityHashMap<LhScheduleResult, LhScheduleResult>(
                        Math.max(4, resultList.size() * 2));
        for (LhScheduleResult result : resultList) {
            LhScheduleResult copy = new LhScheduleResult();
            BeanUtil.copyProperties(result, copy);
            stateMap.put(result, copy);
        }
        return stateMap;
    }

    /**
     * 解析续作结果对应的来源 SKU。
     *
     * @param context 排程上下文
     * @param resultList 续作结果
     * @return 结果对象身份到来源 SKU 的映射
     */
    private Map<LhScheduleResult, SkuScheduleDTO> resolveSourceSkuMap(
            LhScheduleContext context,
            List<LhScheduleResult> resultList) {
        Map<LhScheduleResult, SkuScheduleDTO> sourceSkuMap =
                new IdentityHashMap<LhScheduleResult, SkuScheduleDTO>(
                        Math.max(4, resultList.size() * 2));
        for (LhScheduleResult result : resultList) {
            SkuScheduleDTO sourceSku = context.getScheduleResultSourceSkuMap().get(result);
            if (Objects.isNull(sourceSku)) {
                sourceSku = resolveSkuScheduleDto(
                        context, result.getMaterialCode(), result.getProductStatus());
            }
            if (Objects.nonNull(sourceSku)) {
                sourceSkuMap.put(result, sourceSku);
            }
        }
        return sourceSkuMap;
    }

    /**
     * 截断续作结果在实际下机时点之后的计划量。
     *
     * @param context 排程上下文
     * @param resultList 目标机台续作结果
     * @param sourceSkuMap 结果来源 SKU
     * @param offlineTime 实际下机时间
     * @param removedQtyByShift 各班次被截断数量输出
     * @param removedQtyBySkuDate 被截断 SKU 按业务日数量；预演阶段可为空
     * @param releaseMachineCapacity 是否把截断量释放回机台运行态
     * @return 各来源 SKU 被截断总量
     */
    private Map<SkuScheduleDTO, Integer> truncateContinuationResults(
            LhScheduleContext context,
            List<LhScheduleResult> resultList,
            Map<LhScheduleResult, SkuScheduleDTO> sourceSkuMap,
            Date offlineTime,
            Map<Integer, Integer> removedQtyByShift,
            Map<SkuScheduleDTO, Map<LocalDate, Integer>> removedQtyBySkuDate,
            boolean releaseMachineCapacity) {
        Map<SkuScheduleDTO, Integer> removedQtyBySku =
                new IdentityHashMap<SkuScheduleDTO, Integer>(4);
        for (LhScheduleResult result : resultList) {
            SkuScheduleDTO sourceSku = sourceSkuMap.get(result);
            if (Objects.isNull(sourceSku)) {
                throw new IllegalStateException("被置换续作结果缺少来源SKU: " + result.getMaterialCode());
            }
            int resultRemovedQty = 0;
            for (int shiftIndex = 1;
                 shiftIndex <= LhScheduleConstant.MAX_SHIFT_SLOT_COUNT;
                 shiftIndex++) {
                int originalQty = Math.max(0, Objects.isNull(
                        ShiftFieldUtil.getShiftPlanQty(result, shiftIndex))
                        ? 0 : ShiftFieldUtil.getShiftPlanQty(result, shiftIndex));
                if (originalQty <= 0) {
                    continue;
                }
                int retainedQty = resolveRetainedShiftQty(
                        result, sourceSku, shiftIndex, originalQty, offlineTime);
                int removedQty = Math.max(0, originalQty - retainedQty);
                if (removedQty <= 0) {
                    continue;
                }
                Date originalStartTime = ShiftFieldUtil.getShiftStartTime(result, shiftIndex);
                Date retainedEndTime = resolveRetainedShiftEndTime(
                        sourceSku, result, originalStartTime, retainedQty);
                ShiftFieldUtil.setShiftPlanQty(result, shiftIndex, retainedQty,
                        retainedQty > 0 ? originalStartTime : null,
                        retainedQty > 0 ? retainedEndTime : null);
                resultRemovedQty += removedQty;
                removedQtyByShift.merge(shiftIndex, removedQty, Integer::sum);
                if (Objects.nonNull(removedQtyBySkuDate)) {
                    LocalDate productionDate =
                            resolveShiftBusinessDate(context, shiftIndex);
                    if (Objects.nonNull(productionDate)) {
                        removedQtyBySkuDate
                                .computeIfAbsent(sourceSku,
                                        key -> new LinkedHashMap<LocalDate, Integer>(4))
                                .merge(productionDate, removedQty, Integer::sum);
                    }
                }
                if (releaseMachineCapacity) {
                    releaseMachineShiftCapacity(
                            context, result.getLhMachineCode(), shiftIndex, removedQty);
                }
            }
            refreshTruncatedContinuationResult(result, resultRemovedQty);
            if (resultRemovedQty > 0) {
                removedQtyBySku.merge(sourceSku, resultRemovedQty, Integer::sum);
            }
        }
        return removedQtyBySku;
    }

    /**
     * 解析排程窗口班次所属业务日。
     *
     * @param context 排程上下文
     * @param shiftIndex 班次索引
     * @return 班次业务日；未命中返回null
     */
    private LocalDate resolveShiftBusinessDate(LhScheduleContext context, int shiftIndex) {
        for (LhShiftConfigVO shift : context.getScheduleWindowShifts()) {
            if (Objects.nonNull(shift) && Objects.nonNull(shift.getShiftIndex())
                    && shift.getShiftIndex() == shiftIndex
                    && Objects.nonNull(shift.getWorkDate())) {
                return toLocalDate(shift.getWorkDate());
            }
        }
        return null;
    }

    /**
     * 计算一个班次在下机时点前可保留的计划量。
     *
     * @param result 续作结果
     * @param sourceSku 来源 SKU
     * @param shiftIndex 班次索引
     * @param originalQty 原班次计划量
     * @param offlineTime 实际下机时间
     * @return 可保留计划量
     */
    private int resolveRetainedShiftQty(
            LhScheduleResult result,
            SkuScheduleDTO sourceSku,
            int shiftIndex,
            int originalQty,
            Date offlineTime) {
        Date shiftStartTime = ShiftFieldUtil.getShiftStartTime(result, shiftIndex);
        Date shiftEndTime = ShiftFieldUtil.getShiftEndTime(result, shiftIndex);
        if (Objects.isNull(shiftStartTime) || Objects.isNull(shiftEndTime)) {
            throw new IllegalStateException("被置换续作班次缺少起止时间: "
                    + result.getLhMachineCode() + "#" + shiftIndex);
        }
        if (!shiftEndTime.after(offlineTime)) {
            return originalQty;
        }
        if (!shiftStartTime.before(offlineTime)) {
            return 0;
        }
        int lhTimeSeconds = sourceSku.getLhTimeSeconds();
        int mouldQty = resolveResultMouldQty(result, sourceSku);
        if (lhTimeSeconds <= 0 || mouldQty <= 0) {
            throw new IllegalStateException("被置换续作SKU缺少硫化时间或模数: "
                    + sourceSku.getMaterialCode());
        }
        long availableSeconds = Math.max(0L,
                (offlineTime.getTime() - shiftStartTime.getTime()) / 1000L);
        long completeCureCount = availableSeconds / lhTimeSeconds;
        long retainedQty = completeCureCount * mouldQty;
        return (int) Math.min(originalQty, Math.min(Integer.MAX_VALUE, retainedQty));
    }

    /**
     * 计算截断后班次实际结束时间。
     *
     * @param sourceSku 来源 SKU
     * @param result 续作结果
     * @param shiftStartTime 班次开始时间
     * @param retainedQty 保留计划量
     * @return 保留量对应的实际结束时间
     */
    private Date resolveRetainedShiftEndTime(
            SkuScheduleDTO sourceSku,
            LhScheduleResult result,
            Date shiftStartTime,
            int retainedQty) {
        if (retainedQty <= 0 || Objects.isNull(shiftStartTime)) {
            return null;
        }
        int mouldQty = resolveResultMouldQty(result, sourceSku);
        int cureCount = (retainedQty + mouldQty - 1) / mouldQty;
        long durationMillis = (long) cureCount * sourceSku.getLhTimeSeconds() * 1000L;
        return new Date(shiftStartTime.getTime() + durationMillis);
    }

    /**
     * 解析续作结果实际模数。
     *
     * @param result 续作结果
     * @param sourceSku 来源 SKU
     * @return 正模数
     */
    private int resolveResultMouldQty(LhScheduleResult result, SkuScheduleDTO sourceSku) {
        int mouldQty = Objects.isNull(result.getMouldQty()) ? 0 : result.getMouldQty();
        return mouldQty > 0 ? mouldQty
                : ShiftCapacityResolverUtil.resolveMachineMouldQty(sourceSku.getMouldQty());
    }

    /**
     * 刷新局部截断后的续作结果汇总字段和收尾时间。
     *
     * @param result 续作结果
     * @param removedQty 被截断数量
     */
    private void refreshTruncatedContinuationResult(
            LhScheduleResult result,
            int removedQty) {
        ShiftFieldUtil.syncDailyPlanQty(result);
        result.setTotalDailyPlanQty(result.getDailyPlanQty());
        ShiftFieldUtil.clearUnplannedShiftCureFormulaFields(result);
        int lastShiftIndex = ShiftFieldUtil.applyLastPlannedShiftEndMark(result, true);
        Date lastEndTime = lastShiftIndex > 0
                ? ShiftFieldUtil.getShiftEndTime(result, lastShiftIndex) : null;
        result.setSpecEndTime(lastEndTime);
        if (Objects.nonNull(result.getTdaySpecEndTime())
                && (Objects.isNull(lastEndTime) || result.getTdaySpecEndTime().after(lastEndTime))) {
            result.setTdaySpecEndTime(lastEndTime);
        }
        if (removedQty > 0) {
            int originalSurplusQty = Objects.isNull(result.getMouldSurplusQty())
                    ? 0 : result.getMouldSurplusQty();
            result.setMouldSurplusQty(originalSurplusQty + removedQty);
        }
    }

    /**
     * 将临时截断量释放回候选机台班次剩余产能。
     *
     * @param context 排程上下文
     * @param machineCode 机台编码
     * @param shiftIndex 班次索引
     * @param releasedQty 释放数量
     */
    private void releaseMachineShiftCapacity(
            LhScheduleContext context,
            String machineCode,
            int shiftIndex,
            int releasedQty) {
        MachineScheduleDTO machine = context.getMachineScheduleMap().get(machineCode);
        int[] machineCapacity = Objects.isNull(machine)
                ? null : machine.getShiftRemainingCapacity();
        int[] contextCapacity = context.getMachineShiftCapacityMap().get(machineCode);
        if (Objects.nonNull(machineCapacity) && shiftIndex < machineCapacity.length) {
            machineCapacity[shiftIndex] += releasedQty;
        }
        if (Objects.nonNull(contextCapacity) && contextCapacity != machineCapacity
                && shiftIndex < contextCapacity.length) {
            contextCapacity[shiftIndex] += releasedQty;
        }
    }

    /**
     * 恢复续作原结果字段，准备按正式换模时间重新截断。
     *
     * @param context 排程上下文
     * @param originalStateMap 原结果字段快照
     * @param sourceSkuMap 结果来源 SKU
     */
    private void restoreOriginalContinuationResultStates(
            LhScheduleContext context,
            Map<LhScheduleResult, LhScheduleResult> originalStateMap,
            Map<LhScheduleResult, SkuScheduleDTO> sourceSkuMap) {
        for (Map.Entry<LhScheduleResult, LhScheduleResult> entry : originalStateMap.entrySet()) {
            BeanUtil.copyProperties(entry.getValue(), entry.getKey());
            if (!context.getScheduleResultList().contains(entry.getKey())) {
                context.getScheduleResultList().add(entry.getKey());
            }
            SkuScheduleDTO sourceSku = sourceSkuMap.get(entry.getKey());
            if (Objects.nonNull(sourceSku)) {
                context.getScheduleResultSourceSkuMap().put(entry.getKey(), sourceSku);
            }
            List<LhScheduleResult> assignmentList = context.getMachineAssignmentMap()
                    .computeIfAbsent(entry.getKey().getLhMachineCode(),
                            key -> new ArrayList<LhScheduleResult>(2));
            if (!assignmentList.contains(entry.getKey())) {
                assignmentList.add(0, entry.getKey());
            }
        }
    }

    /**
     * 按“正式截断量-预演截断量”修正机台剩余产能。
     *
     * @param context 排程上下文
     * @param machineCode 机台编码
     * @param previewRemovedQtyByShift 预演截断量
     * @param finalRemovedQtyByShift 正式截断量
     */
    private void adjustReleasedMachineCapacityDelta(
            LhScheduleContext context,
            String machineCode,
            Map<Integer, Integer> previewRemovedQtyByShift,
            Map<Integer, Integer> finalRemovedQtyByShift) {
        for (int shiftIndex = 1;
             shiftIndex <= LhScheduleConstant.MAX_SHIFT_SLOT_COUNT;
             shiftIndex++) {
            int previewQty = previewRemovedQtyByShift.getOrDefault(shiftIndex, 0);
            int finalQty = finalRemovedQtyByShift.getOrDefault(shiftIndex, 0);
            int deltaQty = finalQty - previewQty;
            if (deltaQty == 0) {
                continue;
            }
            adjustMachineShiftCapacity(context, machineCode, shiftIndex, deltaQty);
        }
    }

    /**
     * 调整机台指定班次剩余产能，负数表示正式下机时间后移后收回预释放产能。
     *
     * @param context 排程上下文
     * @param machineCode 机台编码
     * @param shiftIndex 班次索引
     * @param deltaQty 产能变化量
     */
    private void adjustMachineShiftCapacity(
            LhScheduleContext context,
            String machineCode,
            int shiftIndex,
            int deltaQty) {
        MachineScheduleDTO machine = context.getMachineScheduleMap().get(machineCode);
        int[] machineCapacity = Objects.isNull(machine)
                ? null : machine.getShiftRemainingCapacity();
        int[] contextCapacity = context.getMachineShiftCapacityMap().get(machineCode);
        if (Objects.nonNull(machineCapacity) && shiftIndex < machineCapacity.length) {
            machineCapacity[shiftIndex] = Math.max(0, machineCapacity[shiftIndex] + deltaQty);
        }
        if (Objects.nonNull(contextCapacity) && contextCapacity != machineCapacity
                && shiftIndex < contextCapacity.length) {
            contextCapacity[shiftIndex] = Math.max(0, contextCapacity[shiftIndex] + deltaQty);
        }
    }

    /**
     * 复用新增主链把特殊材料排到唯一指定机台。
     *
     * @param context 排程上下文
     * @param sku 特殊材料 SKU
     * @param machineCode 指定续作机台
     * @param earliestSwitchTime 预演确认的最早换模时间
     * @return 指定机台上的特殊材料结果；失败返回null
     */
    private LhScheduleResult scheduleSpecialMaterialOnSpecifiedMachine(
            LhScheduleContext context,
            SkuScheduleDTO sku,
            String machineCode,
            Date earliestSwitchTime) {
        List<SkuScheduleDTO> originalPendingSkuList =
                new ArrayList<SkuScheduleDTO>(context.getNewSpecSkuList());
        List<SkuScheduleDTO> isolatedSkuList = new ArrayList<SkuScheduleDTO>(1);
        isolatedSkuList.add(sku);
        context.setNewSpecSkuList(isolatedSkuList);
        context.setSpecialMaterialSpecifiedMachineCode(machineCode);
        context.setSpecialMaterialSpecifiedSkuKey(
                MonthPlanDateResolver.buildMaterialStatusKey(
                        sku.getMaterialCode(), sku.getProductStatus()));
        context.setSpecialMaterialEarliestSwitchTime(earliestSwitchTime);
        try {
            MachineScheduleDTO machine = context.getMachineScheduleMap().get(machineCode);
            /*
             * 置换后的实际交替类型必须先走现有换活字块判断。同胎胚、同模具等条件满足时，
             * 由换活字块主链完成切换、首检、产能和账本更新；明确不适用时才进入正规换模主链。
             */
            SpecifiedMachineScheduleResult typeBlockResult =
                    typeBlockProductionStrategy.tryScheduleSpecialMaterialSubstitution(
                            context, machine, sku, earliestSwitchTime);
            if (typeBlockResult.isApplicable()) {
                log.info("特殊材料置换按换活字块主链执行, materialCode: {}, productStatus: {}, "
                                + "machineCode: {}, success: {}, reason: {}",
                        sku.getMaterialCode(), sku.getProductStatus(), machineCode,
                        typeBlockResult.isSuccess(), typeBlockResult.getReason());
                return typeBlockResult.isSuccess()
                        ? typeBlockResult.getScheduleResult() : null;
            }

            IProductionStrategy strategy = strategyFactory.getProductionStrategy(
                    ScheduleTypeEnum.NEW_SPEC.getCode());
            IMachineMatchStrategy machineMatchStrategy = strategyFactory.getMachineMatchStrategy();
            IMouldChangeBalanceStrategy mouldChangeStrategy =
                    strategyFactory.getMouldChangeBalanceStrategy();
            IFirstInspectionBalanceStrategy inspectionStrategy =
                    strategyFactory.getFirstInspectionBalanceStrategy();
            ICapacityCalculateStrategy capacityStrategy =
                    strategyFactory.getCapacityCalculateStrategy();
            // 单个特殊材料 SKU 直接进入现有新增主链，避免重新排序影响其他已完成新增结果。
            strategy.scheduleNewSpecs(context, machineMatchStrategy,
                    mouldChangeStrategy, inspectionStrategy, capacityStrategy);
            strategy.allocateShiftPlanQty(context);
            /*
             * scheduleNewSpecs 内已经完成班次分配、余量和胎胚账本扣减。这里禁止再次执行
             * 面向全量新增结果的 adjustEmbryoStock，避免置换阶段回裁或重写 S4.5 已完成结果。
             */
            return findScheduledSpecialMaterialResult(
                    context, sku.getMaterialCode(), sku.getProductStatus(), machineCode);
        } finally {
            context.setNewSpecSkuList(originalPendingSkuList);
            context.clearSpecialMaterialSpecifiedMachineDirective();
        }
    }

    /**
     * 查找特殊材料在指定机台生成的正计划量结果。
     *
     * @param context 排程上下文
     * @param materialCode 特殊材料编码
     * @param productStatus 产品状态
     * @param machineCode 指定机台
     * @return 特殊材料结果
     */
    private LhScheduleResult findScheduledSpecialMaterialResult(
            LhScheduleContext context,
            String materialCode,
            String productStatus,
            String machineCode) {
        for (LhScheduleResult result : context.getScheduleResultList()) {
            if (StringUtils.equals(materialCode, result.getMaterialCode())
                    && StringUtils.equals(StringUtils.trimToEmpty(productStatus),
                    StringUtils.trimToEmpty(result.getProductStatus()))
                    && StringUtils.equals(machineCode, result.getLhMachineCode())
                    && ShiftFieldUtil.resolveScheduledQty(result) > 0) {
                return result;
            }
        }
        return null;
    }

    /**
     * 校验正式换模时间未早于月计划准入且未落入禁换模时段。
     *
     * @param context 排程上下文
     * @param specialResult 特殊材料结果
     * @param firstPlanDate 首个有量日期
     * @return true-时间合法；false-必须回滚
     */
    private boolean isActualChangeTimeValid(
            LhScheduleContext context,
            LhScheduleResult specialResult,
            LocalDate firstPlanDate) {
        Date actualChangeStartTime = specialResult.getMouldChangeStartTime();
        Date earliestTargetTime = resolveEarliestShiftStartTime(context, firstPlanDate);
        int switchDurationHours = StringUtils.equals(
                ScheduleTypeEnum.TYPE_BLOCK.getCode(), specialResult.getScheduleType())
                || StringUtils.equals("1", specialResult.getIsTypeBlock())
                ? LhScheduleTimeUtil.getTypeBlockChangeTotalHours(context)
                : LhScheduleTimeUtil.getMouldChangeTotalHours(context);
        return Objects.nonNull(actualChangeStartTime)
                && Objects.nonNull(earliestTargetTime)
                && !actualChangeStartTime.before(earliestTargetTime)
                && isWholeChangeExecutionTimeAllowed(
                context, actualChangeStartTime, switchDurationHours);
    }

    /**
     * 同步局部截断前后的结构/SKU已排机台统计。
     *
     * @param context 排程上下文
     * @param originalResultList 截断前续作结果字段快照
     * @param retainedResultList 截断后续作结果
     */
    private void synchronizeContinuationScheduledMachineMaps(
            LhScheduleContext context,
            Iterable<LhScheduleResult> originalResultList,
            List<LhScheduleResult> retainedResultList) {
        /*
         * 必须使用字段快照移除截断前全部业务日登记。如果直接使用已经截断的原对象，
         * 只能移除保留日期，后段被截断日期仍会残留“已排机台”，导致后续加机台数判断失真。
         */
        for (LhScheduleResult originalResult : originalResultList) {
            removeScheduledMachineRegistration(context, originalResult);
        }
        for (LhScheduleResult retainedResult : retainedResultList) {
            if (ShiftFieldUtil.resolveScheduledQty(retainedResult) <= 0) {
                removeZeroQtyContinuationResult(context, retainedResult);
                continue;
            }
            for (LhShiftConfigVO shift : context.getScheduleWindowShifts()) {
                if (Objects.isNull(shift) || Objects.isNull(shift.getShiftIndex())
                        || Objects.isNull(shift.getWorkDate())) {
                    continue;
                }
                Integer planQty = ShiftFieldUtil.getShiftPlanQty(
                        retainedResult, shift.getShiftIndex());
                if (Objects.nonNull(planQty) && planQty > 0) {
                    context.recordScheduledMachine(
                            toLocalDate(shift.getWorkDate()),
                            retainedResult.getStructureName(),
                            retainedResult.getMaterialCode(),
                            retainedResult.getProductStatus(),
                            retainedResult.getLhMachineCode());
                }
            }
        }
    }

    /**
     * 移除结果原有业务日机台登记。
     *
     * @param context 排程上下文
     * @param result 截断前结果
     */
    private void removeScheduledMachineRegistration(
            LhScheduleContext context,
            LhScheduleResult result) {
        for (LhShiftConfigVO shift : context.getScheduleWindowShifts()) {
            if (Objects.isNull(shift) || Objects.isNull(shift.getShiftIndex())
                    || Objects.isNull(shift.getWorkDate())) {
                continue;
            }
            Integer planQty = ShiftFieldUtil.getShiftPlanQty(result, shift.getShiftIndex());
            if (Objects.isNull(planQty) || planQty <= 0) {
                continue;
            }
            LocalDate businessDate = toLocalDate(shift.getWorkDate());
            String skuKey = MonthPlanDateResolver.buildMaterialStatusKey(
                    result.getMaterialCode(), result.getProductStatus());
            removeMachineCode(context.getSkuScheduledMachineCodeMap(),
                    businessDate, skuKey, result.getLhMachineCode());
            removeMachineCode(context.getStructureScheduledMachineCodeMap(),
                    businessDate, result.getStructureName(), result.getLhMachineCode());
        }
    }

    /**
     * 从指定业务日维度移除机台编码。
     *
     * @param targetMap 已排机台统计
     * @param businessDate 业务日
     * @param key SKU复合键或结构名称
     * @param machineCode 机台编码
     */
    private void removeMachineCode(
            Map<LocalDate, Map<String, Set<String>>> targetMap,
            LocalDate businessDate,
            String key,
            String machineCode) {
        if (Objects.isNull(businessDate) || StringUtils.isEmpty(key)
                || CollectionUtils.isEmpty(targetMap)) {
            return;
        }
        Map<String, Set<String>> valueMap = targetMap.get(businessDate);
        if (CollectionUtils.isEmpty(valueMap)) {
            return;
        }
        Set<String> machineCodeSet = valueMap.get(key);
        if (CollectionUtils.isEmpty(machineCodeSet)) {
            return;
        }
        machineCodeSet.remove(machineCode);
        if (machineCodeSet.isEmpty()) {
            valueMap.remove(key);
        }
        if (valueMap.isEmpty()) {
            targetMap.remove(businessDate);
        }
    }

    /**
     * 删除已被完全截断的续作结果。
     *
     * @param context 排程上下文
     * @param result 零计划量续作结果
     */
    private void removeZeroQtyContinuationResult(
            LhScheduleContext context,
            LhScheduleResult result) {
        context.getScheduleResultList().remove(result);
        context.getScheduleResultSourceSkuMap().remove(result);
        List<LhScheduleResult> assignmentList =
                context.getMachineAssignmentMap().get(result.getLhMachineCode());
        if (Objects.nonNull(assignmentList)) {
            assignmentList.remove(result);
            if (assignmentList.isEmpty()) {
                context.getMachineAssignmentMap().remove(result.getLhMachineCode());
            }
        }
    }

    /**
     * 恢复被截断续作数量并写入精确未排结果。
     *
     * @param context 排程上下文
     * @param machineCode 被置换机台
     * @param removedQtyBySku 各续作 SKU 被截断数量
     * @param removedQtyBySkuDate 各续作 SKU 按业务日被截断数量
     */
    private void restoreTruncatedSkuQtyAndWriteUnscheduled(
            LhScheduleContext context,
            String machineCode,
            Map<SkuScheduleDTO, Integer> removedQtyBySku,
            Map<SkuScheduleDTO, Map<LocalDate, Integer>> removedQtyBySkuDate) {
        for (Map.Entry<SkuScheduleDTO, Integer> entry : removedQtyBySku.entrySet()) {
            SkuScheduleDTO replacedSku = entry.getKey();
            int removedQty = Math.max(0, entry.getValue());
            if (removedQty <= 0) {
                continue;
            }
            targetScheduleQtyResolver.restoreProductionRemainingQty(
                    context, replacedSku, removedQty,
                    "特殊材料置换局部截断恢复", machineCode);
            restoreTruncatedDailyQuota(
                    context, replacedSku, removedQtyBySkuDate.get(replacedSku));
            addTruncatedSkuUnscheduledResult(
                    context, replacedSku, machineCode, removedQty);
        }
    }

    /**
     * 将被截断续作量恢复到 dayN 日计划节奏账本。
     *
     * <p>续作原排产按“生产日及之前欠量优先、再借用未来日计划”的顺序消费账本。
     * 尾量截断按生产日期倒序恢复，并在每个生产日内从较晚计划日向前回退，
     * 同步减少实际量、未来借用量和满班补齐超排量。</p>
     *
     * @param context 排程上下文
     * @param sku 被提前下机的续作 SKU
     * @param removedQtyByDate 按实际生产业务日汇总的截断数量
     */
    private void restoreTruncatedDailyQuota(
            LhScheduleContext context,
            SkuScheduleDTO sku,
            Map<LocalDate, Integer> removedQtyByDate) {
        Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap = sku.getDailyPlanQuotaMap();
        if (CollectionUtils.isEmpty(quotaMap) || CollectionUtils.isEmpty(removedQtyByDate)) {
            return;
        }
        List<Map.Entry<LocalDate, Integer>> removedDateEntryList =
                new ArrayList<Map.Entry<LocalDate, Integer>>(removedQtyByDate.entrySet());
        removedDateEntryList.sort(Map.Entry.<LocalDate, Integer>comparingByKey().reversed());
        for (Map.Entry<LocalDate, Integer> removedDateEntry : removedDateEntryList) {
            LocalDate productionDate = removedDateEntry.getKey();
            int pendingRestoreQty = Math.max(0, removedDateEntry.getValue());
            if (pendingRestoreQty <= 0) {
                continue;
            }
            List<Map.Entry<LocalDate, SkuDailyPlanQuotaDTO>> quotaEntryList =
                    new ArrayList<Map.Entry<LocalDate, SkuDailyPlanQuotaDTO>>(quotaMap.entrySet());
            Collections.reverse(quotaEntryList);
            int restoredQuotaQty = 0;
            int restoredFutureBorrowQty = 0;
            for (Map.Entry<LocalDate, SkuDailyPlanQuotaDTO> quotaEntry : quotaEntryList) {
                if (restoredQuotaQty >= pendingRestoreQty
                        || Objects.isNull(quotaEntry.getValue())) {
                    break;
                }
                SkuDailyPlanQuotaDTO quota = quotaEntry.getValue();
                int restoredQty = Math.min(
                        Math.max(0, quota.getScheduledQty()),
                        pendingRestoreQty - restoredQuotaQty);
                if (restoredQty <= 0) {
                    continue;
                }
                quota.setScheduledQty(quota.getScheduledQty() - restoredQty);
                quota.setRemainingQty(quota.getRemainingQty() + restoredQty);
                restoredQuotaQty += restoredQty;
                if (quotaEntry.getKey().isAfter(productionDate)) {
                    restoredFutureBorrowQty += restoredQty;
                }
            }
            SkuDailyPlanQuotaDTO productionQuota = quotaMap.get(productionDate);
            if (Objects.nonNull(productionQuota)) {
                productionQuota.setActualQty(Math.max(
                        0, productionQuota.getActualQty() - restoredQuotaQty));
                productionQuota.setFutureBorrowQty(Math.max(
                        0, productionQuota.getFutureBorrowQty() - restoredFutureBorrowQty));
            }
            int restoredShiftFillOverQty =
                    Math.max(0, pendingRestoreQty - restoredQuotaQty);
            restoreShiftFillOverQty(
                    context, sku, productionQuota, restoredShiftFillOverQty);
        }
        SkuDailyPlanQuotaUtil.refreshRollingFields(quotaMap);
    }

    /**
     * 回退被截断班次中未消费 dayN 额度的满班补齐超排量。
     *
     * @param context 排程上下文
     * @param sku 被提前下机的续作 SKU
     * @param productionQuota 实际生产日账本
     * @param restoredShiftFillOverQty 需要回退的超排数量
     */
    private void restoreShiftFillOverQty(
            LhScheduleContext context,
            SkuScheduleDTO sku,
            SkuDailyPlanQuotaDTO productionQuota,
            int restoredShiftFillOverQty) {
        if (restoredShiftFillOverQty <= 0) {
            return;
        }
        if (Objects.nonNull(productionQuota)) {
            productionQuota.setShiftFillOverQty(Math.max(
                    0, productionQuota.getShiftFillOverQty() - restoredShiftFillOverQty));
        }
        sku.setShiftFillOverQty(Math.max(
                0, sku.getShiftFillOverQty() - restoredShiftFillOverQty));
        String skuKey = MonthPlanDateResolver.buildMaterialStatusKey(
                sku.getMaterialCode(), sku.getProductStatus());
        int accumulatedQty = context.getSkuShiftFillOverQtyMap().getOrDefault(skuKey, 0);
        context.getSkuShiftFillOverQtyMap().put(
                skuKey, Math.max(0, accumulatedQty - restoredShiftFillOverQty));
    }

    /**
     * 写入被截断续作 SKU 的精确未排数量。
     *
     * @param context 排程上下文
     * @param sku 被截断续作 SKU
     * @param machineCode 被置换机台
     * @param removedQty 被截断数量
     */
    private void addTruncatedSkuUnscheduledResult(
            LhScheduleContext context,
            SkuScheduleDTO sku,
            String machineCode,
            int removedQty) {
        String reason = UNSCHEDULED_REASON_REPLACED + "，机台 " + machineCode;
        for (LhUnscheduledResult result : context.getUnscheduledResultList()) {
            if (isSameSku(sku.getMaterialCode(), sku.getProductStatus(), result)
                    && StringUtils.equals(reason, result.getUnscheduledReason())) {
                int originalQty = Objects.isNull(result.getUnscheduledQty())
                        ? 0 : result.getUnscheduledQty();
                result.setUnscheduledQty(originalQty + removedQty);
                return;
            }
        }
        LhUnscheduledResult result = buildUnscheduledResult(context, sku, removedQty, reason);
        context.getUnscheduledResultList().add(result);
    }

    /**
     * 记录成功置换的精确结果，供S4.6生成交替计划备注。
     *
     * @param context 排程上下文
     * @param replacedResult 被截断续作结果
     * @param specialResult 特殊材料结果
     * @param substitutionType 置换类型
     * @param actualChangeStartTime 实际换模开始时间
     */
    private void recordSubstitutionInfo(
            LhScheduleContext context,
            LhScheduleResult replacedResult,
            LhScheduleResult specialResult,
            SubstitutionTypeEnum substitutionType,
            Date actualChangeStartTime) {
        specialResult.setIsChangeMould("1");
        SpecialMaterialSubstitutionRecord record = new SpecialMaterialSubstitutionRecord();
        record.setMachineCode(specialResult.getLhMachineCode());
        record.setReplacedMaterialCode(replacedResult.getMaterialCode());
        record.setSpecialMaterialCode(specialResult.getMaterialCode());
        record.setSpecialProductStatus(specialResult.getProductStatus());
        record.setSubstitutionType(substitutionType.name());
        record.setRemark(substitutionType.buildRemark(specialResult.getLhMachineCode()));
        record.setActualOfflineTime(actualChangeStartTime);
        record.setActualChangeStartTime(actualChangeStartTime);
        int switchDurationHours = StringUtils.equals(
                ScheduleTypeEnum.TYPE_BLOCK.getCode(), specialResult.getScheduleType())
                || StringUtils.equals("1", specialResult.getIsTypeBlock())
                ? LhScheduleTimeUtil.getTypeBlockChangeTotalHours(context)
                : LhScheduleTimeUtil.getMouldChangeTotalHours(context);
        record.setActualChangeEndTime(new Date(actualChangeStartTime.getTime()
                + switchDurationHours * 60L * 60L * 1000L));
        context.getSpecialMaterialSubstitutionRecordList().add(record);
    }

    /**
     * 复制真实换模计数供无副作用预演使用。
     *
     * @param sourceMap 真实每日换模计数
     * @return 深拷贝模拟计数
     */
    private Map<String, int[]> copyMouldChangeCountMap(Map<String, int[]> sourceMap) {
        Map<String, int[]> targetMap =
                new LinkedHashMap<String, int[]>(Math.max(8, sourceMap.size() * 2));
        for (Map.Entry<String, int[]> entry : sourceMap.entrySet()) {
            targetMap.put(entry.getKey(), Objects.isNull(entry.getValue())
                    ? new int[]{0, 0} : entry.getValue().clone());
        }
        return targetMap;
    }

    /**
     * 按物料和产品状态移除特殊材料原未排结果。
     *
     * @param context 排程上下文
     * @param materialCode 物料编码
     * @param productStatus 产品状态
     */
    private void removeUnscheduledResult(
            LhScheduleContext context,
            String materialCode,
            String productStatus) {
        context.getUnscheduledResultList().removeIf(result ->
                isSameSku(materialCode, productStatus, result));
    }

    /**
     * 写入特殊材料置换后仍剩余的未排数量。
     *
     * @param context 排程上下文
     * @param sku 特殊材料 SKU
     * @param remainingQty 剩余待排量
     * @param reason 未排原因
     */
    private void addOrMergeSpecialMaterialUnscheduledResult(
            LhScheduleContext context,
            SkuScheduleDTO sku,
            int remainingQty,
            String reason) {
        for (LhUnscheduledResult result : context.getUnscheduledResultList()) {
            if (isSameSku(sku.getMaterialCode(), sku.getProductStatus(), result)) {
                result.setUnscheduledQty(remainingQty);
                result.setUnscheduledReason(reason);
                return;
            }
        }
        context.getUnscheduledResultList().add(
                buildUnscheduledResult(context, sku, remainingQty, reason));
    }

    /**
     * 构建置换相关未排结果。
     *
     * @param context 排程上下文
     * @param sku SKU
     * @param unscheduledQty 未排数量
     * @param reason 未排原因
     * @return 未排结果
     */
    private LhUnscheduledResult buildUnscheduledResult(
            LhScheduleContext context,
            SkuScheduleDTO sku,
            int unscheduledQty,
            String reason) {
        LhUnscheduledResult result = new LhUnscheduledResult();
        result.setFactoryCode(context.getFactoryCode());
        result.setBatchNo(context.getBatchNo());
        result.setMaterialCode(sku.getMaterialCode());
        result.setProductStatus(sku.getProductStatus());
        result.setMaterialDesc(sku.getMaterialDesc());
        result.setStructureName(sku.getStructureName());
        result.setSpecCode(sku.getSpecCode());
        result.setMainMaterialDesc(sku.getMainMaterialDesc());
        result.setEmbryoCode(sku.getEmbryoCode());
        result.setMouldQty(sku.getMouldQty());
        result.setScheduleDate(context.getScheduleTargetDate());
        result.setUnscheduledQty(unscheduledQty);
        result.setUnscheduledReason(reason);
        result.setMonthPlanVersion(sku.getMonthPlanVersion());
        result.setProductionVersion(sku.getProductionVersion());
        return result;
    }

    /**
     * 更新已有特殊材料未排原因。
     *
     * @param unscheduled 未排结果
     * @param reason 未排原因
     */
    private void updateUnscheduledReason(
            LhUnscheduledResult unscheduled,
            String reason) {
        unscheduled.setUnscheduledReason(reason);
    }

    /**
     * 查找物料和产品状态对应的 SKU 运行态。
     *
     * @param context 排程上下文
     * @param materialCode 物料编码
     * @param productStatus 产品状态
     * @return SKU运行态；未找到返回null
     */
    private SkuScheduleDTO resolveSkuScheduleDto(
            LhScheduleContext context,
            String materialCode,
            String productStatus) {
        if (StringUtils.isEmpty(materialCode)) {
            return null;
        }
        String skuKey = MonthPlanDateResolver.buildMaterialStatusKey(materialCode, productStatus);
        SkuScheduleDTO indexedSku = context.getAllSkuScheduleDtoMap().get(skuKey);
        if (Objects.nonNull(indexedSku)) {
            return indexedSku;
        }
        for (SkuScheduleDTO sku : context.getNewSpecSkuList()) {
            if (isSameSku(materialCode, productStatus, sku)) {
                return sku;
            }
        }
        for (SkuScheduleDTO sku : context.getContinuousSkuList()) {
            if (isSameSku(materialCode, productStatus, sku)) {
                return sku;
            }
        }
        for (SkuScheduleDTO sku : context.getScheduleResultSourceSkuMap().values()) {
            if (isSameSku(materialCode, productStatus, sku)) {
                return sku;
            }
        }
        for (List<SkuScheduleDTO> skuList : context.getStructureSkuMap().values()) {
            for (SkuScheduleDTO sku : skuList) {
                if (isSameSku(materialCode, productStatus, sku)) {
                    return sku;
                }
            }
        }
        return null;
    }

    /**
     * 判断排程结果是否与指定业务 SKU 一致。
     *
     * @param materialCode 物料编码
     * @param productStatus 产品状态
     * @param result 排程结果
     * @return true-物料和产品状态均一致
     */
    private boolean isSameSku(
            String materialCode,
            String productStatus,
            LhScheduleResult result) {
        return Objects.nonNull(result)
                && StringUtils.equals(materialCode, result.getMaterialCode())
                && StringUtils.equals(StringUtils.trimToEmpty(productStatus),
                StringUtils.trimToEmpty(result.getProductStatus()));
    }

    /**
     * 判断未排结果是否与指定业务 SKU 一致。
     *
     * @param materialCode 物料编码
     * @param productStatus 产品状态
     * @param result 未排结果
     * @return true-物料和产品状态均一致
     */
    private boolean isSameSku(
            String materialCode,
            String productStatus,
            LhUnscheduledResult result) {
        return Objects.nonNull(result)
                && StringUtils.equals(materialCode, result.getMaterialCode())
                && StringUtils.equals(StringUtils.trimToEmpty(productStatus),
                StringUtils.trimToEmpty(result.getProductStatus()));
    }

    /**
     * 判断 SKU DTO 是否与指定业务 SKU 一致。
     *
     * @param materialCode 物料编码
     * @param productStatus 产品状态
     * @param sku SKU DTO
     * @return true-物料和产品状态均一致
     */
    private boolean isSameSku(
            String materialCode,
            String productStatus,
            SkuScheduleDTO sku) {
        return Objects.nonNull(sku)
                && StringUtils.equals(materialCode, sku.getMaterialCode())
                && StringUtils.equals(StringUtils.trimToEmpty(productStatus),
                StringUtils.trimToEmpty(sku.getProductStatus()));
    }

    /**
     * 将 Date 转换为系统时区 LocalDate。
     *
     * @param date 日期
     * @return LocalDate；date为空返回null
     */
    private LocalDate toLocalDate(Date date) {
        return Objects.isNull(date) ? null
                : date.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
    }
}
