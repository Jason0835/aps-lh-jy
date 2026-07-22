package com.zlt.aps.lh.service.impl;

import com.zlt.aps.lh.api.domain.dto.MachineScheduleDTO;
import com.zlt.aps.lh.api.domain.dto.SkuScheduleDTO;
import com.zlt.aps.lh.api.domain.dto.SpecialMaterialMatchResult;
import com.zlt.aps.lh.api.domain.entity.LhMouldCleanPlan;
import com.zlt.aps.lh.api.domain.entity.LhPrecisionPlan;
import com.zlt.aps.lh.api.domain.entity.LhScheduleResult;
import com.zlt.aps.lh.api.domain.entity.LhUnscheduledResult;
import com.zlt.aps.lh.api.domain.vo.LhShiftConfigVO;
import com.zlt.aps.lh.api.enums.CleaningTypeEnum;
import com.zlt.aps.lh.api.enums.ScheduleTypeEnum;
import com.zlt.aps.lh.api.enums.SubstitutionTypeEnum;
import com.zlt.aps.lh.component.MonthPlanDateResolver;
import com.zlt.aps.lh.component.TargetScheduleQtyResolver;
import com.zlt.aps.lh.component.MonthPlanDateResolver;
import com.zlt.aps.lh.util.ShiftFieldUtil;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.engine.factory.ScheduleStrategyFactory;
import com.zlt.aps.lh.engine.strategy.ICapacityCalculateStrategy;
import com.zlt.aps.lh.engine.strategy.IFirstInspectionBalanceStrategy;
import com.zlt.aps.lh.engine.strategy.IMachineMatchStrategy;
import com.zlt.aps.lh.engine.strategy.IMouldChangeBalanceStrategy;
import com.zlt.aps.lh.engine.strategy.IProductionStrategy;
import com.zlt.aps.lh.engine.strategy.ISkuPriorityStrategy;
import com.zlt.aps.lh.engine.strategy.support.MouldResourceAllocationResult;
import com.zlt.aps.lh.engine.strategy.support.MouldResourceContext;
import com.zlt.aps.lh.util.LhMachineHardMatchUtil;
import com.zlt.aps.lh.util.LhScheduleTimeUtil;
import com.zlt.aps.lh.util.LhSpecialMaterialUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import javax.annotation.Resource;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 特殊材料硫化机置换服务。
 *
 * <p>业务定位：</p>
 * <ul>
 *   <li>在 S4.4 续作、换活字块和 S4.5 新增排产全部完成后执行；</li>
 *   <li>扫描未排结果中仍未排上机台的特殊材料 SKU，触发硫化机置换兜底逻辑；</li>
 *   <li>从已排硫化排程结果中选择候选机台，按4级优先级确定被置换机台；</li>
 *   <li>被置换 SKU 下机后回滚状态，特殊材料 SKU 按现有逻辑重新排产上机；</li>
 *   <li>生成模具交替计划并备注命中的置换类型和被置换机台编码。</li>
 * </ul>
 *
 * <p>4级被置换机台选择优先级（逐层匹配，命中上层则不再比较下层）：</p>
 * <ol>
 *   <li>3天内喷砂清洗计划，喷砂时间越近越优先；</li>
 *   <li>2天内月计划降模需求，降模时间越近越优先；</li>
 *   <li>30天内精度保养计划，精度计划时间越近越优先；</li>
 *   <li>胎胚库存低，库存越低越优先。</li>
 * </ol>
 *
 * @author APS
 */
@Slf4j
@Service
public class SpecialMaterialMachineSubstitutionService {

    /** 喷砂清洗置换：排程日期起3天内 */
    private static final int SAND_BLAST_WITHIN_DAYS = 3;
    /** 月计划降模置换：2天内 */
    private static final int MONTH_PLAN_REDUCE_WITHIN_DAYS = 2;
    /** 精度计划置换：30天内 */
    private static final int PRECISION_PLAN_WITHIN_DAYS = 30;

    /** 未排原因：未匹配到可置换机台 */
    private static final String UNSCHEDULED_REASON_NO_CANDIDATE = "特殊材料SKU未匹配到可置换机台";
    /** 未排原因：模具不足 */
    private static final String UNSCHEDULED_REASON_MOULD_INSUFFICIENT = "特殊材料SKU置换失败：模具不足";
    /** 未排原因：晚班禁止换模 */
    private static final String UNSCHEDULED_REASON_NIGHT_NO_CHANGE = "特殊材料SKU置换失败：晚班禁止换模";
    /** 未排原因：换模次数超限 */
    private static final String UNSCHEDULED_REASON_CHANGE_EXCEEDED = "特殊材料SKU置换失败：换模次数超限";
    /** 未排原因：单控机台约束不满足 */
    private static final String UNSCHEDULED_REASON_SINGLE_CONTROL = "特殊材料SKU置换失败：单控机台约束不满足";
    /** 未排原因：置换后仍未能排上机台 */
    private static final String UNSCHEDULED_REASON_STILL_UNSCHEDULED = "特殊材料SKU置换后仍未能排上机台";
    /** 未排原因：被特殊材料SKU置换下机 */
    private static final String UNSCHEDULED_REASON_REPLACED = "被特殊材料SKU置换下机";

    @Resource
    private ScheduleStrategyFactory strategyFactory;

    @Resource
    private TargetScheduleQtyResolver targetScheduleQtyResolver;

    /**
     * 执行特殊材料硫化机置换兜底逻辑。
     *
     * <p>流程：</p>
     * <ol>
     *   <li>识别未排结果中仍未排上机台的特殊材料 SKU；</li>
     *   <li>排除已在排程结果中排上机台的特殊材料 SKU；</li>
     *   <li>按优先级排序待置换特殊材料 SKU；</li>
     *   <li>逐个执行置换。</li>
     * </ol>
     *
     * @param context 排程上下文
     */
    public void substitute(LhScheduleContext context) {
        if (Objects.isNull(context) || CollectionUtils.isEmpty(context.getSpecialMaterialBomList())) {
            return;
        }

        // 识别未排结果中仍未排上机台的特殊材料 SKU
        List<LhUnscheduledResult> unscheduledSpecialMaterialList = identifyUnscheduledSpecialMaterialSkus(context);
        if (CollectionUtils.isEmpty(unscheduledSpecialMaterialList)) {
            log.info("特殊材料硫化机置换：未发现需置换的特殊材料SKU");
            return;
        }

        // 按优先级排序待置换特殊材料 SKU
        sortUnscheduledSpecialMaterialList(context, unscheduledSpecialMaterialList);
        log.info("特殊材料硫化机置换：待置换特殊材料SKU数: {}", unscheduledSpecialMaterialList.size());

        // 逐个执行置换
        int successCount = 0;
        for (LhUnscheduledResult unscheduled : unscheduledSpecialMaterialList) {
            boolean success = substituteOneSku(context, unscheduled);
            if (success) {
                successCount++;
            }
        }
        log.info("特殊材料硫化机置换完成, 成功置换: {}, 失败: {}", successCount,
                unscheduledSpecialMaterialList.size() - successCount);
    }

    /**
     * 识别未排结果中仍未排上机台的特殊材料 SKU。
     *
     * <p>筛选条件：</p>
     * <ul>
     *   <li>未排结果中的 SKU 命中特殊材料清单；</li>
     *   <li>该 SKU 在当前排程结果中未排上任何机台（避免重复置换）；</li>
     *   <li>该 SKU 仍有需排量（未排数量 > 0）。</li>
     * </ul>
     *
     * @param context 排程上下文
     * @return 待置换的未排结果列表
     */
    private List<LhUnscheduledResult> identifyUnscheduledSpecialMaterialSkus(LhScheduleContext context) {
        List<LhUnscheduledResult> result = new ArrayList<LhUnscheduledResult>();
        if (CollectionUtils.isEmpty(context.getUnscheduledResultList())) {
            return result;
        }

        // 收集当前已排上机台的物料状态复合键，用于仅排除同一业务SKU。
        Set<String> scheduledSkuKeySet = collectScheduledSkuKeys(context);

        for (LhUnscheduledResult unscheduled : context.getUnscheduledResultList()) {
            // 未排数量不大于0的跳过
            if (Objects.isNull(unscheduled.getUnscheduledQty()) || unscheduled.getUnscheduledQty() <= 0) {
                continue;
            }
            // 已排上机台的特殊材料SKU不再参与置换
            String skuKey = MonthPlanDateResolver.buildMaterialStatusKey(
                    unscheduled.getMaterialCode(), unscheduled.getProductStatus());
            if (scheduledSkuKeySet.contains(skuKey)) {
                continue;
            }
            // 判断是否命中特殊材料清单
            if (isSpecialMaterial(context, unscheduled.getMaterialCode(), unscheduled.getStructureName())) {
                result.add(unscheduled);
            }
        }
        return result;
    }

    /**
     * 收集当前排程结果中已排上机台的物料编码集合。
     *
     * @param context 排程上下文
     * @return 已排物料编码集合
     */
    private Set<String> collectScheduledSkuKeys(LhScheduleContext context) {
        Set<String> skuKeySet = new HashSet<String>(64);
        for (LhScheduleResult result : context.getScheduleResultList()) {
            if (StringUtils.isNotEmpty(result.getMaterialCode())) {
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
     * @return true-命中特殊材料清单，false-未命中
     */
    private boolean isSpecialMaterial(LhScheduleContext context, String materialCode, String structureName) {
        SkuScheduleDTO tempSku = new SkuScheduleDTO();
        tempSku.setMaterialCode(materialCode);
        tempSku.setStructureName(structureName);
        SpecialMaterialMatchResult matchResult = LhSpecialMaterialUtil.resolveMatchResult(context, tempSku);
        return matchResult.isSpecial();
    }

    /**
     * 按优先级排序待置换特殊材料 SKU。
     *
     * <p>排序规则：特殊材料清单优先级 -> 锁交期/锁上机 -> 延误天数 -> 硫化余量大 -> 物料编码。
     * 当前复用未排结果中的字段进行排序。</p>
     *
     * @param context 排程上下文
     * @param unscheduledList 待置换未排结果列表
     */
    private void sortUnscheduledSpecialMaterialList(LhScheduleContext context,
                                                     List<LhUnscheduledResult> unscheduledList) {
        unscheduledList.sort(Comparator
                // 未排数量大的优先
                .comparing(LhUnscheduledResult::getUnscheduledQty,
                        Comparator.nullsLast(Comparator.reverseOrder()))
                // 物料编码兜底排序
                .thenComparing(LhUnscheduledResult::getMaterialCode,
                        Comparator.nullsLast(String::compareTo)));
    }

    /**
     * 对单个特殊材料 SKU 执行置换。
     *
     * <p>流程：</p>
     * <ol>
     *   <li>计算需置换机台数；</li>
     *   <li>查找候选机台并按4级优先级选择；</li>
     *   <li>逐台执行置换：被置换SKU下机 -> 特殊材料SKU上机 -> 生成模具交替计划备注；</li>
     *   <li>置换不足或失败时记录未排原因。</li>
     * </ol>
     *
     * @param context 排程上下文
     * @param unscheduled 未排结果
     * @return true-至少成功置换一台，false-全部失败
     */
    private boolean substituteOneSku(LhScheduleContext context, LhUnscheduledResult unscheduled) {
        // 构建 SKU DTO 用于机台匹配和排产
        SkuScheduleDTO sku = resolveSkuScheduleDto(
                context, unscheduled.getMaterialCode(), unscheduled.getProductStatus());
        if (Objects.isNull(sku)) {
            log.warn("特殊材料置换：未找到SKU排程信息, materialCode: {}", unscheduled.getMaterialCode());
            return false;
        }

        // 特殊材料SKU必须有余量才触发置换
        int targetQty = sku.resolveTargetScheduleQty();
        if (targetQty <= 0) {
            log.info("特殊材料置换：SKU无余量, 跳过置换, materialCode: {}", unscheduled.getMaterialCode());
            return false;
        }

        // 计算需置换机台数
        int requiredMachineCount = calculateRequiredMachineCount(context, sku);
        log.info("特殊材料置换开始, materialCode: {}, 目标量: {}, 需置换机台数: {}",
                unscheduled.getMaterialCode(), targetQty, requiredMachineCount);

        int successCount = 0;

        for (int i = 0; i < requiredMachineCount; i++) {
            // 查找候选机台并按4级优先级选择
            LhScheduleResult targetResult = selectBestCandidateMachine(context, sku);
            if (Objects.isNull(targetResult)) {
                log.info("特殊材料置换：无可置换机台, materialCode: {}, 已成功: {}/{}",
                        unscheduled.getMaterialCode(), successCount, requiredMachineCount);
                break;
            }

            // 记录被置换信息用于备注
            String replacedMachineCode = targetResult.getLhMachineCode();
            String replacedMaterialCode = targetResult.getMaterialCode();
            SubstitutionTypeEnum substitutionType = resolveSubstitutionType(context, targetResult);

            // 执行置换：被置换SKU下机 -> 特殊材料SKU上机
            boolean success = executeSubstitution(context, sku, targetResult);
            if (success) {
                successCount++;
                // 每台置换成功后立即记录备注，避免多台置换时只记录最后一台
                recordSubstitutionInfo(context, unscheduled.getMaterialCode(),
                        substitutionType, replacedMachineCode, replacedMaterialCode);
                log.info("特殊材料置换成功, materialCode: {}, 机台: {}, 置换类型: {}, 被置换SKU: {}",
                        unscheduled.getMaterialCode(), replacedMachineCode,
                        substitutionType.getDescription(), replacedMaterialCode);
            } else {
                log.info("特殊材料置换失败, materialCode: {}, 目标机台: {}, 被置换SKU: {}",
                        unscheduled.getMaterialCode(), replacedMachineCode, replacedMaterialCode);
            }
        }

        // 处理置换结果
        if (successCount > 0) {
            // 检查特殊材料SKU是否真正排上机台
            if (isSpecialMaterialSkuScheduled(
                    context, unscheduled.getMaterialCode(), unscheduled.getProductStatus())) {
                // 置换成功后从未排结果中移除该特殊材料SKU
                removeUnscheduledResult(context, unscheduled);
                // 置换不足时记录未排原因
                if (successCount < requiredMachineCount) {
                    String reason = String.format("特殊材料SKU置换不足：按加机台规则需要 %d 台，实际仅成功置换 %d 台",
                            requiredMachineCount, successCount);
                    addSubstitutionUnscheduledResult(context, sku, reason);
                }
                return true;
            } else {
                // 置换后仍未能排上机台
                updateUnscheduledReason(context, unscheduled, UNSCHEDULED_REASON_STILL_UNSCHEDULED);
                return false;
            }
        } else {
            // 全部置换失败，记录未排原因
            String reason = resolveSubstitutionFailureReason(context, sku);
            updateUnscheduledReason(context, unscheduled, reason);
            return false;
        }
    }

    /**
     * 查找并选择最佳候选被置换机台。
     *
     * <p>候选机台筛选条件：</p>
     * <ul>
     *   <li>来自当前已排硫化排程结果列表；</li>
     *   <li>被置换SKU不能是特殊材料SKU（避免特殊材料之间互相置换）；</li>
     *   <li>机台硬匹配特殊材料SKU（英寸范围、模具集合、特殊材料支持）；</li>
     *   <li>优先非续作SKU机台，再续作SKU机台。</li>
     * </ul>
     *
     * <p>4级优先级选择：3天喷砂清洗 -> 2天月计划降模 -> 30天精度计划 -> 胎胚库存低。</p>
     *
     * @param context 排程上下文
     * @param sku 特殊材料SKU
     * @return 最佳候选机台的排程结果，无候选返回null
     */
    private LhScheduleResult selectBestCandidateMachine(LhScheduleContext context, SkuScheduleDTO sku) {
        // 收集候选机台，按续作/非续作分组
        List<LhScheduleResult> nonContinuousCandidates = new ArrayList<LhScheduleResult>();
        List<LhScheduleResult> continuousCandidates = new ArrayList<LhScheduleResult>();

        for (LhScheduleResult result : context.getScheduleResultList()) {
            // 结构保留机台在统一释放时间前不得被特殊材料置换链撤销或改排。
            if (context.isStructureMinMachineRetained(result.getLhMachineCode())) {
                continue;
            }
            // 排除特殊材料SKU的机台
            if (isSpecialMaterial(context, result.getMaterialCode(), result.getStructureName())) {
                continue;
            }
            // 硬匹配检查
            MachineScheduleDTO machine = context.getMachineScheduleMap().get(result.getLhMachineCode());
            if (!LhMachineHardMatchUtil.isMachineHardMatched(context, sku, machine)) {
                continue;
            }
            // 模具可用性预检（dry-run）：检查特殊材料SKU在该机台上的模具是否可用，不可用则跳过
            if (!isMouldAvailable(context, sku, result.getLhMachineCode())) {
                continue;
            }
            // 按续作/非续作分组
            if (ScheduleTypeEnum.CONTINUOUS.getCode().equals(result.getScheduleType())) {
                continuousCandidates.add(result);
            } else {
                nonContinuousCandidates.add(result);
            }
        }

        // 优先从非续作候选中选择，找不到再从续作候选中选择
        LhScheduleResult candidate = selectByPriority(context, nonContinuousCandidates);
        if (Objects.nonNull(candidate)) {
            return candidate;
        }
        return selectByPriority(context, continuousCandidates);
    }

    /**
     * 模具可用性预检（dry-run）。
     *
     * <p>通过 MouldResourceContext.tryAllocate 检查特殊材料SKU在目标机台上的模具是否可用，
     * 预检后立即调用 release 回滚预分配，不影响后续正式分配。</p>
     *
     * @param context 排程上下文
     * @param sku 特殊材料SKU
     * @param machineCode 目标机台编码
     * @return true-模具可用，false-模具不可用
     */
    private boolean isMouldAvailable(LhScheduleContext context, SkuScheduleDTO sku, String machineCode) {
        MouldResourceContext mouldResourceContext = context.getMouldResourceContext();
        if (Objects.isNull(mouldResourceContext)) {
            return true;
        }
        // dry-run：尝试分配模具
        MouldResourceAllocationResult allocateResult = mouldResourceContext.tryAllocate(
                sku.getMaterialCode(), machineCode);
        if (!allocateResult.isAllowed()) {
            log.info("模具预检不可用, materialCode: {}, machineCode: {}, 原因: {}",
                    sku.getMaterialCode(), machineCode, allocateResult.getSkipReason());
            return false;
        }
        // 立即释放预分配，保持状态不变
        mouldResourceContext.release(sku.getMaterialCode(), allocateResult);
        return true;
    }

    /**
     * 按4级优先级从候选列表中选择最佳被置换机台。
     *
     * <p>优先级（逐层匹配，命中上层则不再比较下层）：</p>
     * <ol>
     *   <li>3天内喷砂清洗计划，喷砂时间越近越优先；</li>
     *   <li>2天内月计划降模需求，降模时间越近越优先；</li>
     *   <li>30天内精度保养计划，精度计划时间越近越优先；</li>
     *   <li>胎胚库存低，库存越低越优先。</li>
     * </ol>
     *
     * @param context 排程上下文
     * @param candidates 候选机台排程结果列表
     * @return 最佳候选，无候选返回null
     */
    private LhScheduleResult selectByPriority(LhScheduleContext context, List<LhScheduleResult> candidates) {
        if (CollectionUtils.isEmpty(candidates)) {
            return null;
        }

        // 第1级：3天内喷砂清洗计划
        LhScheduleResult sandBlastCandidate = selectSandBlastCandidate(context, candidates);
        if (Objects.nonNull(sandBlastCandidate)) {
            return sandBlastCandidate;
        }

        // 第2级：2天内月计划降模需求
        LhScheduleResult monthPlanReduceCandidate = selectMonthPlanReduceCandidate(context, candidates);
        if (Objects.nonNull(monthPlanReduceCandidate)) {
            return monthPlanReduceCandidate;
        }

        // 第3级：30天内精度计划
        LhScheduleResult precisionPlanCandidate = selectPrecisionPlanCandidate(context, candidates);
        if (Objects.nonNull(precisionPlanCandidate)) {
            return precisionPlanCandidate;
        }

        // 第4级：胎胚库存低
        return selectLowEmbryoStockCandidate(context, candidates);
    }

    /**
     * 选择3天内有喷砂清洗计划的候选机台，喷砂时间越近越优先。
     *
     * @param context 排程上下文
     * @param candidates 候选机台列表
     * @return 最佳候选，无命中返回null
     */
    private LhScheduleResult selectSandBlastCandidate(LhScheduleContext context,
                                                       List<LhScheduleResult> candidates) {
        LocalDate scheduleDate = toLocalDate(context.getScheduleDate());
        LocalDate deadline = scheduleDate.plusDays(SAND_BLAST_WITHIN_DAYS);

        LhScheduleResult bestCandidate = null;
        Date nearestCleanTime = null;
        for (LhScheduleResult candidate : candidates) {
            // 查找该机台3天内的喷砂清洗计划
            Date cleanTime = findSandBlastCleanTime(context, candidate.getLhMachineCode(), scheduleDate, deadline);
            if (Objects.isNull(cleanTime)) {
                continue;
            }
            // 喷砂清洗计划时间越近越优先
            if (Objects.isNull(nearestCleanTime) || cleanTime.before(nearestCleanTime)) {
                nearestCleanTime = cleanTime;
                bestCandidate = candidate;
            }
        }
        return bestCandidate;
    }

    /**
     * 查找机台在指定日期范围内的喷砂清洗计划时间。
     *
     * @param context 排程上下文
     * @param machineCode 机台编码
     * @param startDate 开始日期
     * @param endDate 结束日期
     * @return 喷砂清洗时间，无命中返回null
     */
    private Date findSandBlastCleanTime(LhScheduleContext context, String machineCode,
                                        LocalDate startDate, LocalDate endDate) {
        if (CollectionUtils.isEmpty(context.getCleaningPlanList())) {
            return null;
        }
        for (LhMouldCleanPlan plan : context.getCleaningPlanList()) {
            // 只匹配喷砂清洗（cleanType=02）
            if (!CleaningTypeEnum.SAND_BLAST.getCode().equals(plan.getCleanType())) {
                continue;
            }
            if (!StringUtils.equals(machineCode, plan.getLhCode())) {
                continue;
            }
            if (Objects.isNull(plan.getCleanTime())) {
                continue;
            }
            LocalDate cleanDate = toLocalDate(plan.getCleanTime());
            // 清洗时间在排程日期起3天内
            if (!cleanDate.isBefore(startDate) && !cleanDate.isAfter(endDate)) {
                return plan.getCleanTime();
            }
        }
        return null;
    }

    /**
     * 选择2天内有月计划降模需求的候选机台，降模时间越近越优先。
     *
     * <p>月计划降模判断：该机台SKU的多机台数量超过月计划日计划节奏所需机台数，
     * 且在未来2天内月计划日计划量可由更少机台覆盖时，该机台可作为降模候选。</p>
     *
     * @param context 排程上下文
     * @param candidates 候选机台列表
     * @return 最佳候选，无命中返回null
     */
    private LhScheduleResult selectMonthPlanReduceCandidate(LhScheduleContext context,
                                                             List<LhScheduleResult> candidates) {
        LocalDate scheduleDate = toLocalDate(context.getScheduleDate());

        LhScheduleResult bestCandidate = null;
        int nearestReduceDay = Integer.MAX_VALUE;
        for (LhScheduleResult candidate : candidates) {
            // 判断该机台SKU在未来2天内是否有月计划降模需求
            int reduceDay = resolveMonthPlanReduceDay(context, candidate, scheduleDate);
            if (reduceDay < 0 || reduceDay > MONTH_PLAN_REDUCE_WITHIN_DAYS) {
                continue;
            }
            // 降模时间越近越优先
            if (reduceDay < nearestReduceDay) {
                nearestReduceDay = reduceDay;
                bestCandidate = candidate;
            }
        }
        return bestCandidate;
    }

    /**
     * 解析机台SKU的月计划降模需求日。
     *
     * <p>判断逻辑：统计该SKU当前已排机台数，对比月计划日计划量所需机台数，
     * 若当前机台数大于所需机台数，则存在降模需求，返回最近降模日（0=当天，1=次日，2=后天）。</p>
     *
     * @param context 排程上下文
     * @param result 候选机台排程结果
     * @param scheduleDate 排程日期
     * @return 最近降模日（0-based），无降模需求返回-1
     */
    private int resolveMonthPlanReduceDay(LhScheduleContext context, LhScheduleResult result,
                                          LocalDate scheduleDate) {
        String materialCode = result.getMaterialCode();
        if (StringUtils.isEmpty(materialCode)) {
            return -1;
        }

        // 统计该SKU当前已排机台数
        long currentMachineCount = context.getScheduleResultList().stream()
                .filter(r -> StringUtils.equals(materialCode, r.getMaterialCode()))
                .map(LhScheduleResult::getLhMachineCode)
                .distinct()
                .count();
        if (currentMachineCount <= 1) {
            // 只有1台机台不降模
            return -1;
        }

        // 逐日判断未来2天内月计划日计划量是否可由更少机台覆盖
        int dailyCapacity = Objects.nonNull(result.getStandardCapacity()) ? result.getStandardCapacity() : 0;
        if (dailyCapacity <= 0) {
            return -1;
        }
        for (int dayOffset = 0; dayOffset <= MONTH_PLAN_REDUCE_WITHIN_DAYS; dayOffset++) {
            LocalDate bizDate = scheduleDate.plusDays(dayOffset);
            int dayPlanQty = MonthPlanDateResolver.resolveDayQty(
                    context, materialCode, result.getProductStatus(), bizDate);
            if (dayPlanQty <= 0) {
                // 无日计划量的日期可降模
                return dayOffset;
            }
            // 所需机台数 = ceil(日计划量 / 日标准产能)
            int requiredMachineCount = (dayPlanQty + dailyCapacity - 1) / dailyCapacity;
            if (requiredMachineCount < currentMachineCount) {
                return dayOffset;
            }
        }
        return -1;
    }

    /**
     * 选择30天内有精度保养计划的候选机台，精度计划时间越近越优先。
     *
     * @param context 排程上下文
     * @param candidates 候选机台列表
     * @return 最佳候选，无命中返回null
     */
    private LhScheduleResult selectPrecisionPlanCandidate(LhScheduleContext context,
                                                           List<LhScheduleResult> candidates) {
        LocalDate scheduleDate = toLocalDate(context.getScheduleDate());
        LocalDate deadline = scheduleDate.plusDays(PRECISION_PLAN_WITHIN_DAYS);

        LhScheduleResult bestCandidate = null;
        LocalDate nearestPlanDate = null;
        for (LhScheduleResult candidate : candidates) {
            LhPrecisionPlan plan = context.getMaintenancePlanMap().get(candidate.getLhMachineCode());
            if (Objects.isNull(plan) || Objects.isNull(plan.getPlanDate())) {
                continue;
            }
            LocalDate planDate = toLocalDate(plan.getPlanDate());
            // 精度计划在排程日期起30天内
            if (planDate.isBefore(scheduleDate) || planDate.isAfter(deadline)) {
                continue;
            }
            // 精度计划时间越近越优先
            if (Objects.isNull(nearestPlanDate) || planDate.isBefore(nearestPlanDate)) {
                nearestPlanDate = planDate;
                bestCandidate = candidate;
            }
        }
        return bestCandidate;
    }

    /**
     * 选择胎胚库存最低的候选机台。
     *
     * <p>排序：胎胚库存低 -> SKU剩余可排量少 -> 机台编码兜底排序。</p>
     *
     * @param context 排程上下文
     * @param candidates 候选机台列表
     * @return 最佳候选，无候选返回null
     */
    private LhScheduleResult selectLowEmbryoStockCandidate(LhScheduleContext context,
                                                            List<LhScheduleResult> candidates) {
        if (CollectionUtils.isEmpty(candidates)) {
            return null;
        }
        return candidates.stream()
                .min(Comparator
                        // 胎胚库存低的优先
                        .comparing(this::resolveResultEmbryoStock,
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        // SKU剩余可排量少的优先
                        .thenComparing(this::resolveResultRemainingQty,
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        // 机台编码兜底排序
                        .thenComparing(LhScheduleResult::getLhMachineCode,
                                Comparator.nullsLast(String::compareTo)))
                .orElse(null);
    }

    /**
     * 获取排程结果对应胎胚库存。
     *
     * @param result 排程结果
     * @return 胎胚库存，未知返回null
     */
    private Integer resolveResultEmbryoStock(LhScheduleResult result) {
        return result.getEmbryoStock();
    }

    /**
     * 获取排程结果对应SKU剩余可排量。
     *
     * @param result 排程结果
     * @return 剩余可排量，未知返回null
     */
    private Integer resolveResultRemainingQty(LhScheduleResult result) {
        return result.getMouldSurplusQty();
    }

    /**
     * 解析被置换机台的置换类型。
     *
     * @param context 排程上下文
     * @param result 被置换机台排程结果
     * @return 置换类型枚举
     */
    private SubstitutionTypeEnum resolveSubstitutionType(LhScheduleContext context, LhScheduleResult result) {
        LocalDate scheduleDate = toLocalDate(context.getScheduleDate());

        // 第1级：3天内喷砂清洗
        Date cleanTime = findSandBlastCleanTime(context, result.getLhMachineCode(),
                scheduleDate, scheduleDate.plusDays(SAND_BLAST_WITHIN_DAYS));
        if (Objects.nonNull(cleanTime)) {
            return SubstitutionTypeEnum.SAND_BLAST_SUBSTITUTION;
        }

        // 第2级：2天内月计划降模
        int reduceDay = resolveMonthPlanReduceDay(context, result, scheduleDate);
        if (reduceDay >= 0 && reduceDay <= MONTH_PLAN_REDUCE_WITHIN_DAYS) {
            return SubstitutionTypeEnum.MONTH_PLAN_REDUCE_SUBSTITUTION;
        }

        // 第3级：30天内精度计划
        LhPrecisionPlan plan = context.getMaintenancePlanMap().get(result.getLhMachineCode());
        if (Objects.nonNull(plan) && Objects.nonNull(plan.getPlanDate())) {
            LocalDate planDate = toLocalDate(plan.getPlanDate());
            if (!planDate.isBefore(scheduleDate)
                    && !planDate.isAfter(scheduleDate.plusDays(PRECISION_PLAN_WITHIN_DAYS))) {
                return SubstitutionTypeEnum.PRECISION_PLAN_SUBSTITUTION;
            }
        }

        // 第4级：胎胚库存低
        return SubstitutionTypeEnum.LOW_EMBRYO_STOCK_SUBSTITUTION;
    }

    /**
     * 执行单台置换：被置换SKU下机 -> 特殊材料SKU上机。
     *
     * <p>流程：</p>
     * <ol>
     *   <li>移除被置换SKU在该机台上的排程结果，回滚机台状态、模具资源、换模计数和首检计数；</li>
     *   <li>将被置换SKU记录为未排（原因：被特殊材料SKU置换下机）；</li>
     *   <li>将特殊材料SKU加回新增待排列表；</li>
     *   <li>重新调用新增排产策略，让特殊材料SKU按现有规则上机。</li>
     * </ol>
     *
     * @param context 排程上下文
     * @param sku 特殊材料SKU
     * @param targetResult 被置换机台排程结果
     * @return true-置换成功，false-置换失败
     */
    private boolean executeSubstitution(LhScheduleContext context, SkuScheduleDTO sku,
                                        LhScheduleResult targetResult) {
        String machineCode = targetResult.getLhMachineCode();

        // 记录置换前特殊材料SKU的排程结果数，用于判断本次置换是否真正新增了结果
        int beforeResultCount = countSkuResults(
                context, sku.getMaterialCode(), sku.getProductStatus());

        // 0. 置换前快照：保存被置换机台的排程结果、来源SKU映射、机台分配列表和机台状态，用于置换失败时恢复
        List<LhScheduleResult> snapshotResults = new ArrayList<LhScheduleResult>();
        Map<LhScheduleResult, SkuScheduleDTO> snapshotSourceSkuMap = new LinkedHashMap<LhScheduleResult, SkuScheduleDTO>();
        List<LhScheduleResult> snapshotMachineAssignment = new ArrayList<LhScheduleResult>();
        MachineScheduleDTO snapshotMachine = null;
        for (LhScheduleResult machineResult : context.getScheduleResultList()) {
            if (StringUtils.equals(machineCode, machineResult.getLhMachineCode())) {
                snapshotResults.add(machineResult);
                SkuScheduleDTO sourceSku = context.getScheduleResultSourceSkuMap().get(machineResult);
                if (Objects.nonNull(sourceSku)) {
                    snapshotSourceSkuMap.put(machineResult, sourceSku);
                }
            }
        }
        List<LhScheduleResult> existingAssignment = context.getMachineAssignmentMap().get(machineCode);
        if (Objects.nonNull(existingAssignment)) {
            snapshotMachineAssignment = new ArrayList<LhScheduleResult>(existingAssignment);
        }
        MachineScheduleDTO currentMachine = context.getMachineScheduleMap().get(machineCode);
        if (Objects.nonNull(currentMachine)) {
            snapshotMachine = new MachineScheduleDTO();
            snapshotMachine.setMachineCode(currentMachine.getMachineCode());
            snapshotMachine.setMachineName(currentMachine.getMachineName());
            snapshotMachine.setCurrentMaterialCode(currentMachine.getCurrentMaterialCode());
            snapshotMachine.setCurrentMaterialDesc(currentMachine.getCurrentMaterialDesc());
            snapshotMachine.setPreviousMaterialCode(currentMachine.getPreviousMaterialCode());
            snapshotMachine.setPreviousMaterialDesc(currentMachine.getPreviousMaterialDesc());
            snapshotMachine.setPreviousSpecCode(currentMachine.getPreviousSpecCode());
            snapshotMachine.setPreviousProSize(currentMachine.getPreviousProSize());
            snapshotMachine.setEstimatedEndTime(currentMachine.getEstimatedEndTime());
        }

        // 1. 收集被置换机台上所有结果的物料编码，用于下机后逐个记录未排原因
        Set<String> replacedSkuKeySet = new LinkedHashSet<String>(4);
        for (LhScheduleResult machineResult : snapshotResults) {
            if (StringUtils.isNotEmpty(machineResult.getMaterialCode())) {
                replacedSkuKeySet.add(MonthPlanDateResolver.buildMaterialStatusKey(
                        machineResult.getMaterialCode(), machineResult.getProductStatus()));
            }
        }

        // 2. 移除被置换SKU在该机台上的排程结果，回滚状态
        removeReplacedSkuFromMachine(context, machineCode);

        // 3. 逐个被置换SKU记录未排原因
        for (String replacedSkuKey : replacedSkuKeySet) {
            SkuScheduleDTO replacedSku = context.getAllSkuScheduleDtoMap().get(replacedSkuKey);
            if (Objects.nonNull(replacedSku)) {
                String reason = UNSCHEDULED_REASON_REPLACED + "，机台 " + machineCode;
                addSubstitutionUnscheduledResult(context, replacedSku, reason);
            }
        }

        // 4. 将特殊材料SKU加回新增待排列表
        if (!context.getNewSpecSkuList().contains(sku)) {
            context.getNewSpecSkuList().add(sku);
        }

        // 5. 重新调用新增排产策略，让特殊材料SKU按现有规则上机
        reScheduleSpecialMaterialSku(context);

        // 6. 通过对比置换前后的结果数判断是否真正新增了排产结果
        int afterResultCount = countSkuResults(
                context, sku.getMaterialCode(), sku.getProductStatus());
        boolean scheduled = afterResultCount > beforeResultCount;

        // 7. 从待排列表中移除特殊材料SKU
        context.getNewSpecSkuList().remove(sku);

        // 8. 置换失败时恢复被置换SKU的排程结果，避免净损失
        if (!scheduled) {
            restoreReplacedSkuResults(context, machineCode, snapshotResults, snapshotSourceSkuMap,
                    snapshotMachineAssignment, snapshotMachine);
        }

        return scheduled;
    }

    /**
     * 置换失败时恢复被置换SKU的排程结果和相关状态。
     *
     * <p>恢复范围：</p>
     * <ul>
     *   <li>将快照中的排程结果重新加回 scheduleResultList、scheduleResultSourceSkuMap、machineAssignmentMap；</li>
     *   <li>从快照恢复机台状态（当前物料、前物料等）；</li>
     *   <li>重新分配模具资源、重新登记已排机台、重新扣减排产剩余量；</li>
     *   <li>删除被置换SKU的未排记录。</li>
     * </ul>
     *
     * @param context 排程上下文
     * @param machineCode 机台编码
     * @param snapshotResults 快照中的排程结果列表
     * @param snapshotSourceSkuMap 快照中的结果到SKU映射
     * @param snapshotMachineAssignment 快照中的机台分配列表
     * @param snapshotMachine 快照中的机台状态
     */
    private void restoreReplacedSkuResults(LhScheduleContext context, String machineCode,
                                           List<LhScheduleResult> snapshotResults,
                                           Map<LhScheduleResult, SkuScheduleDTO> snapshotSourceSkuMap,
                                           List<LhScheduleResult> snapshotMachineAssignment,
                                           MachineScheduleDTO snapshotMachine) {
        if (CollectionUtils.isEmpty(snapshotResults)) {
            return;
        }
        log.info("置换失败，开始恢复被置换SKU结果, 机台: {}, 恢复结果数: {}",
                machineCode, snapshotResults.size());

        // 1. 重新加回排程结果到 scheduleResultList
        for (LhScheduleResult result : snapshotResults) {
            if (!context.getScheduleResultList().contains(result)) {
                context.getScheduleResultList().add(result);
            }
        }

        // 2. 重新加回结果到SKU映射
        context.getScheduleResultSourceSkuMap().putAll(snapshotSourceSkuMap);

        // 3. 重新加回机台分配映射
        if (!CollectionUtils.isEmpty(snapshotMachineAssignment)) {
            context.getMachineAssignmentMap().put(machineCode, new ArrayList<LhScheduleResult>(snapshotMachineAssignment));
        }

        // 4. 从快照恢复机台状态
        MachineScheduleDTO currentMachine = context.getMachineScheduleMap().get(machineCode);
        if (Objects.nonNull(currentMachine) && Objects.nonNull(snapshotMachine)) {
            currentMachine.setCurrentMaterialCode(snapshotMachine.getCurrentMaterialCode());
            currentMachine.setCurrentMaterialDesc(snapshotMachine.getCurrentMaterialDesc());
            currentMachine.setPreviousMaterialCode(snapshotMachine.getPreviousMaterialCode());
            currentMachine.setPreviousMaterialDesc(snapshotMachine.getPreviousMaterialDesc());
            currentMachine.setPreviousSpecCode(snapshotMachine.getPreviousSpecCode());
            currentMachine.setPreviousProSize(snapshotMachine.getPreviousProSize());
            currentMachine.setEstimatedEndTime(snapshotMachine.getEstimatedEndTime());
        }

        // 5. 重新分配模具资源和重新登记已排机台
        List<LhShiftConfigVO> shifts = LhScheduleTimeUtil.getScheduleShifts(context, context.getScheduleDate());
        IMouldChangeBalanceStrategy mouldChangeStrategy = strategyFactory.getMouldChangeBalanceStrategy();
        IFirstInspectionBalanceStrategy inspectionStrategy = strategyFactory.getFirstInspectionBalanceStrategy();
        for (LhScheduleResult result : snapshotResults) {
            // 重新分配模具资源
            SkuScheduleDTO sourceSku = snapshotSourceSkuMap.get(result);
            if (Objects.nonNull(sourceSku)) {
                MouldResourceContext mouldResourceContext = context.getMouldResourceContext();
                if (Objects.nonNull(mouldResourceContext) && StringUtils.isNotEmpty(sourceSku.getMaterialCode())) {
                    mouldResourceContext.tryAllocate(sourceSku.getMaterialCode(), machineCode);
                }
                // 重新扣减排产剩余量
                int scheduledQty = ShiftFieldUtil.resolveScheduledQty(result);
                if (scheduledQty > 0) {
                    targetScheduleQtyResolver.deductProductionRemainingQty(
                            context, sourceSku, scheduledQty, "置换失败恢复", machineCode);
                }
            }
            // 重新登记已排机台
            if (Objects.nonNull(shifts) && !shifts.isEmpty()) {
                recordScheduledMachineForRestoredResult(context, result, shifts);
            }
            // 重新增加换模计数和首检计数
            if ("1".equals(result.getIsChangeMould()) && Objects.nonNull(result.getMouldChangeStartTime())) {
                mouldChangeStrategy.allocateMouldChange(context, machineCode, result.getMouldChangeStartTime());
                inspectionStrategy.allocateInspection(context, machineCode, result.getMouldChangeStartTime());
            }
        }

        // 6. 删除被置换SKU的未排记录
        removeReplacedUnscheduledResults(context, machineCode);

        log.info("置换失败恢复完成, 机台: {}, 恢复结果数: {}", machineCode, snapshotResults.size());
    }

    /**
     * 为恢复的排程结果重新登记已排机台。
     *
     * @param context 排程上下文
     * @param result 排程结果
     * @param shifts 排程窗口班次
     */
    private void recordScheduledMachineForRestoredResult(LhScheduleContext context, LhScheduleResult result,
                                                         List<LhShiftConfigVO> shifts) {
        if (Objects.isNull(result) || CollectionUtils.isEmpty(shifts)
                || StringUtils.isEmpty(result.getLhMachineCode())) {
            return;
        }
        Set<LocalDate> recordedDateSet = new HashSet<LocalDate>(3);
        for (LhShiftConfigVO shift : shifts) {
            if (Objects.isNull(shift) || Objects.isNull(shift.getShiftIndex())) {
                continue;
            }
            Integer planQty = ShiftFieldUtil.getShiftPlanQty(result, shift.getShiftIndex());
            if (Objects.isNull(planQty) || planQty <= 0) {
                continue;
            }
            if (Objects.nonNull(shift.getWorkDate())) {
                recordedDateSet.add(toLocalDate(shift.getWorkDate()));
            }
        }
        for (LocalDate businessDate : recordedDateSet) {
            context.recordScheduledMachine(businessDate, result.getStructureName(),
                    result.getMaterialCode(), result.getProductStatus(), result.getLhMachineCode());
        }
    }

    /**
     * 删除指定机台上被置换SKU的未排记录。
     *
     * @param context 排程上下文
     * @param machineCode 机台编码
     */
    private void removeReplacedUnscheduledResults(LhScheduleContext context, String machineCode) {
        String reasonPrefix = UNSCHEDULED_REASON_REPLACED + "，机台 " + machineCode;
        context.getUnscheduledResultList().removeIf(u -> StringUtils.isNotEmpty(u.getUnscheduledReason())
                && u.getUnscheduledReason().startsWith(UNSCHEDULED_REASON_REPLACED)
                && u.getUnscheduledReason().contains(machineCode));
    }

    /**
     * 统计指定物料编码在当前排程结果列表中的结果数。
     *
     * @param context 排程上下文
     * @param materialCode 物料编码
     * @param productStatus 产品状态
     * @return 结果数
     */
    private int countSkuResults(LhScheduleContext context,
                                String materialCode,
                                String productStatus) {
        int count = 0;
        for (LhScheduleResult result : context.getScheduleResultList()) {
            if (StringUtils.equals(materialCode, result.getMaterialCode())
                    && StringUtils.equals(StringUtils.trimToEmpty(productStatus),
                    StringUtils.trimToEmpty(result.getProductStatus()))) {
                count++;
            }
        }
        return count;
    }

    /**
     * 移除被置换SKU在指定机台上的全部排程结果，并回滚相关状态。
     *
     * <p>回滚范围：</p>
     * <ul>
     *   <li>scheduleResultList：移除该机台所有结果；</li>
     *   <li>scheduleResultSourceSkuMap：移除对应entry；</li>
     *   <li>machineAssignmentMap：移除该机台entry；</li>
     *   <li>machineScheduleMap：从initialMachineScheduleMap恢复初始快照；</li>
     *   <li>dailyMouldChangeCountMap：按换模时间递减早/中班计数；</li>
     *   <li>dailyFirstInspectionCountMap：按首检时间递减计数；</li>
     *   <li>mouldResourceContext：释放被置换结果占用的模具号。</li>
     * </ul>
     *
     * @param context 排程上下文
     * @param machineCode 被置换机台编码
     */
    private void removeReplacedSkuFromMachine(LhScheduleContext context, String machineCode) {
        // 收集该机台的所有排程结果
        List<LhScheduleResult> machineResults = context.getScheduleResultList().stream()
                .filter(r -> StringUtils.equals(machineCode, r.getLhMachineCode()))
                .collect(Collectors.toList());
        if (CollectionUtils.isEmpty(machineResults)) {
            return;
        }

        // 获取换模均衡策略和首检均衡策略用于回滚计数
        IMouldChangeBalanceStrategy mouldChangeStrategy = strategyFactory.getMouldChangeBalanceStrategy();
        IFirstInspectionBalanceStrategy inspectionStrategy = strategyFactory.getFirstInspectionBalanceStrategy();

        // 逐个结果回滚状态
        for (LhScheduleResult result : machineResults) {
            // 回滚换模计数：按换模开始时间递减对应日期早/中班计数
            if ("1".equals(result.getIsChangeMould()) && Objects.nonNull(result.getMouldChangeStartTime())) {
                mouldChangeStrategy.rollbackMouldChange(context, result.getMouldChangeStartTime());
            }
            // 回滚首检计数：按换模开始时间递减对应日期早/中班首检计数
            if ("1".equals(result.getIsChangeMould()) && Objects.nonNull(result.getMouldChangeStartTime())) {
                inspectionStrategy.rollbackInspection(context, result.getMouldChangeStartTime());
            }
            // 释放模具资源
            releaseMouldResources(context, result);
            // 移除结果到SKU的映射
            context.getScheduleResultSourceSkuMap().remove(result);
        }

        // 从排程结果列表中移除该机台的所有结果
        context.getScheduleResultList().removeAll(machineResults);

        // 移除机台分配映射
        context.getMachineAssignmentMap().remove(machineCode);

        // 回滚已排机台统计Map（skuScheduledMachineCodeMap 和 structureScheduledMachineCodeMap）
        List<LhShiftConfigVO> shifts = LhScheduleTimeUtil.getScheduleShifts(context, context.getScheduleDate());
        for (LhScheduleResult result : machineResults) {
            removeScheduledMachineFromMaps(context, result, shifts);
        }

        // 回滚被置换SKU的排产剩余量和胎胚库存账本
        for (LhScheduleResult result : machineResults) {
            restoreReplacedSkuProductionLedger(context, result);
        }

        // 恢复机台状态到初始快照
        restoreMachineState(context, machineCode);

        log.info("被置换SKU下机完成, 机台: {}, 移除结果数: {}, 被置换物料: {}",
                machineCode, machineResults.size(),
                machineResults.isEmpty() ? "" : machineResults.get(0).getMaterialCode());
    }

    /**
     * 释放被置换结果占用的模具资源。
     *
     * @param context 排程上下文
     * @param result 被移除的排程结果
     */
    private void releaseMouldResources(LhScheduleContext context, LhScheduleResult result) {
        MouldResourceContext mouldResourceContext = context.getMouldResourceContext();
        if (Objects.isNull(mouldResourceContext) || StringUtils.isEmpty(result.getMouldCode())) {
            return;
        }
        // 模具号可能是逗号分隔的多个模具号，需全部释放
        String[] mouldCodeArray = result.getMouldCode().split(",");
        List<String> mouldCodeList = new ArrayList<String>(mouldCodeArray.length);
        for (String code : mouldCodeArray) {
            String trimmedCode = code.trim();
            if (StringUtils.isNotEmpty(trimmedCode)) {
                mouldCodeList.add(trimmedCode);
            }
        }
        if (CollectionUtils.isEmpty(mouldCodeList)) {
            return;
        }
        // 构建释放用的分配结果，释放后机台绑定模具会被清除
        MouldResourceAllocationResult releaseResult = new MouldResourceAllocationResult();
        releaseResult.setAllowed(true);
        releaseResult.setMachineCode(result.getLhMachineCode());
        releaseResult.setAllocatedMouldCodeList(mouldCodeList);
        mouldResourceContext.release(result.getMaterialCode(), releaseResult);
    }

    /**
     * 从已排机台统计Map中移除指定结果的机台登记。
     *
     * <p>遍历排程窗口班次，对有计划量的业务日，
     * 从 skuScheduledMachineCodeMap 和 structureScheduledMachineCodeMap 中移除该机台编码。</p>
     *
     * @param context 排程上下文
     * @param result 被移除的排程结果
     * @param shifts 排程窗口班次列表
     */
    private void removeScheduledMachineFromMaps(LhScheduleContext context, LhScheduleResult result,
                                                List<LhShiftConfigVO> shifts) {
        if (Objects.isNull(context) || Objects.isNull(result) || CollectionUtils.isEmpty(shifts)
                || StringUtils.isEmpty(result.getLhMachineCode())) {
            return;
        }
        // 遍历班次，找出有计划量的业务日
        Set<LocalDate> recordedDateSet = new HashSet<LocalDate>(3);
        for (LhShiftConfigVO shift : shifts) {
            if (Objects.isNull(shift) || Objects.isNull(shift.getShiftIndex())) {
                continue;
            }
            Integer planQty = ShiftFieldUtil.getShiftPlanQty(result, shift.getShiftIndex());
            if (Objects.isNull(planQty) || planQty <= 0) {
                continue;
            }
            if (Objects.nonNull(shift.getWorkDate())) {
                recordedDateSet.add(toLocalDate(shift.getWorkDate()));
            }
        }
        // 从各业务日的机台集合中移除该机台
        for (LocalDate businessDate : recordedDateSet) {
            String skuKey = MonthPlanDateResolver.buildMaterialStatusKey(
                    result.getMaterialCode(), result.getProductStatus());
            removeMachineFromScheduledMap(context.getSkuScheduledMachineCodeMap(),
                    businessDate, skuKey, result.getLhMachineCode());
            removeMachineFromScheduledMap(context.getStructureScheduledMachineCodeMap(),
                    businessDate, result.getStructureName(), result.getLhMachineCode());
        }
    }

    /**
     * 从指定维度的已排机台Map中移除机台编码。
     *
     * @param targetMap 目标Map（skuScheduledMachineCodeMap 或 structureScheduledMachineCodeMap）
     * @param businessDate 业务日
     * @param key 物料编码或结构名称
     * @param machineCode 机台编码
     */
    private void removeMachineFromScheduledMap(Map<LocalDate, Map<String, Set<String>>> targetMap,
                                               LocalDate businessDate, String key, String machineCode) {
        if (Objects.isNull(targetMap) || Objects.isNull(businessDate) || StringUtils.isEmpty(key)) {
            return;
        }
        Map<String, Set<String>> keyMap = targetMap.get(businessDate);
        if (Objects.isNull(keyMap)) {
            return;
        }
        Set<String> machineCodeSet = keyMap.get(key);
        if (Objects.isNull(machineCodeSet)) {
            return;
        }
        machineCodeSet.remove(machineCode);
        if (machineCodeSet.isEmpty()) {
            keyMap.remove(key);
        }
        if (keyMap.isEmpty()) {
            targetMap.remove(businessDate);
        }
    }

    /**
     * 恢复被置换SKU的排产剩余量和胎胚库存账本。
     *
     * <p>复用 TargetScheduleQtyResolver.restoreProductionRemainingQty 一次性回滚
     * skuProductionRemainingQtyMap 和 embryoStockConsumeLedgerMap。</p>
     *
     * @param context 排程上下文
     * @param result 被移除的排程结果
     */
    private void restoreReplacedSkuProductionLedger(LhScheduleContext context, LhScheduleResult result) {
        if (Objects.isNull(result) || StringUtils.isEmpty(result.getMaterialCode())) {
            return;
        }
        SkuScheduleDTO replacedSku = resolveSkuScheduleDto(
                context, result.getMaterialCode(), result.getProductStatus());
        if (Objects.isNull(replacedSku)) {
            return;
        }
        // 获取结果的实际排产量（8班次计划量之和）
        int scheduledQty = ShiftFieldUtil.resolveScheduledQty(result);
        if (scheduledQty <= 0) {
            return;
        }
        // 复用现有方法恢复排产剩余量和胎胚库存账本
        targetScheduleQtyResolver.restoreProductionRemainingQty(
                context, replacedSku, scheduledQty, "特殊材料置换下机回滚",
                result.getLhMachineCode());
    }

    /**
     * 恢复机台状态到初始快照。
     *
     * @param context 排程上下文
     * @param machineCode 机台编码
     */
    private void restoreMachineState(LhScheduleContext context, String machineCode) {
        MachineScheduleDTO initialSnapshot = context.getInitialMachineScheduleMap().get(machineCode);
        MachineScheduleDTO currentMachine = context.getMachineScheduleMap().get(machineCode);
        if (Objects.isNull(currentMachine)) {
            return;
        }
        if (Objects.nonNull(initialSnapshot)) {
            // 从初始快照恢复机台状态
            currentMachine.setCurrentMaterialCode(initialSnapshot.getCurrentMaterialCode());
            currentMachine.setCurrentMaterialDesc(initialSnapshot.getCurrentMaterialDesc());
            currentMachine.setPreviousMaterialCode(initialSnapshot.getPreviousMaterialCode());
            currentMachine.setPreviousMaterialDesc(initialSnapshot.getPreviousMaterialDesc());
            currentMachine.setPreviousSpecCode(initialSnapshot.getPreviousSpecCode());
            currentMachine.setPreviousProSize(initialSnapshot.getPreviousProSize());
            currentMachine.setEstimatedEndTime(initialSnapshot.getEstimatedEndTime());
        } else {
            // 无初始快照时清空当前物料状态
            currentMachine.setCurrentMaterialCode(null);
            currentMachine.setCurrentMaterialDesc(null);
            currentMachine.setPreviousMaterialCode(null);
            currentMachine.setPreviousMaterialDesc(null);
            currentMachine.setEstimatedEndTime(null);
        }
    }

    /**
     * 重新调用新增排产策略，让特殊材料SKU按现有规则上机。
     *
     * <p>复用 S4.5 的排产策略链：SKU排序 -> 机台匹配 -> 换模均衡 -> 首检均衡 -> 产能计算。
     * 此时被置换机台已释放，特殊材料SKU应能匹配到该机台。</p>
     *
     * @param context 排程上下文
     */
    private void reScheduleSpecialMaterialSku(LhScheduleContext context) {
        IProductionStrategy strategy = strategyFactory.getProductionStrategy(
                ScheduleTypeEnum.NEW_SPEC.getCode());
        ISkuPriorityStrategy priorityStrategy = strategyFactory.getSkuPriorityStrategy();
        IMachineMatchStrategy machineMatchStrategy = strategyFactory.getMachineMatchStrategy();
        IMouldChangeBalanceStrategy mouldChangeStrategy = strategyFactory.getMouldChangeBalanceStrategy();
        IFirstInspectionBalanceStrategy inspectionStrategy = strategyFactory.getFirstInspectionBalanceStrategy();
        ICapacityCalculateStrategy capacityStrategy = strategyFactory.getCapacityCalculateStrategy();

        // SKU优先级排序
        priorityStrategy.sortByPriority(context);
        // 执行新增排产（此时待排列表中只有特殊材料SKU）
        strategy.scheduleNewSpecs(context, machineMatchStrategy,
                mouldChangeStrategy, inspectionStrategy, capacityStrategy);
        // 班次计划量分配
        strategy.allocateShiftPlanQty(context);
        // 胎胚库存调整
        strategy.adjustEmbryoStock(context);
    }

    /**
     * 计算特殊材料SKU需置换机台数。
     *
     * <p>按现有加机台规则简化计算：需置换机台数 = max(1, ceil(目标量 / (日标准产能 * 窗口工作日数)))。</p>
     *
     * @param context 排程上下文
     * @param sku 特殊材料SKU
     * @return 需置换机台数
     */
    private int calculateRequiredMachineCount(LhScheduleContext context, SkuScheduleDTO sku) {
        int targetQty = sku.resolveTargetScheduleQty();
        if (targetQty <= 0) {
            return 0;
        }
        int dailyCapacity = sku.getDailyCapacity();
        if (dailyCapacity <= 0) {
            return 1;
        }
        // 计算窗口工作日数
        int windowWorkDays = resolveWindowWorkDayCount(context);
        if (windowWorkDays <= 0) {
            windowWorkDays = 1;
        }
        // 需置换机台数 = ceil(目标量 / (日标准产能 * 窗口工作日数))
        int totalWindowCapacity = dailyCapacity * windowWorkDays;
        int requiredCount = (targetQty + totalWindowCapacity - 1) / totalWindowCapacity;
        return Math.max(1, requiredCount);
    }

    /**
     * 计算排程窗口内的工作日数。
     *
     * @param context 排程上下文
     * @return 工作日数
     */
    private int resolveWindowWorkDayCount(LhScheduleContext context) {
        List<LhShiftConfigVO> shifts = LhScheduleTimeUtil.getScheduleShifts(context, context.getScheduleDate());
        if (CollectionUtils.isEmpty(shifts)) {
            return 1;
        }
        // 按工作日期去重统计
        Set<LocalDate> workDateSet = new HashSet<LocalDate>(8);
        for (LhShiftConfigVO shift : shifts) {
            if (Objects.nonNull(shift.getWorkDate())) {
                workDateSet.add(toLocalDate(shift.getWorkDate()));
            }
        }
        return Math.max(1, workDateSet.size());
    }

    /**
     * 判断特殊材料SKU是否已在排程结果中排上机台。
     *
     * @param context 排程上下文
     * @param materialCode 物料编码
     * @param productStatus 产品状态
     * @return true-已排上机台，false-未排上
     */
    private boolean isSpecialMaterialSkuScheduled(LhScheduleContext context,
                                                  String materialCode,
                                                  String productStatus) {
        return context.getScheduleResultList().stream()
                .anyMatch(r -> StringUtils.equals(materialCode, r.getMaterialCode())
                        && StringUtils.equals(StringUtils.trimToEmpty(productStatus),
                        StringUtils.trimToEmpty(r.getProductStatus())));
    }

    /**
     * 判断物料是否已排上指定机台。
     *
     * @param context 排程上下文
     * @param materialCode 物料编码
     * @param machineCode 机台编码
     * @return true-已排上指定机台，false-未排上
     */
    private boolean isMaterialScheduledOnMachine(LhScheduleContext context, String materialCode,
                                                  String machineCode) {
        return context.getScheduleResultList().stream()
                .anyMatch(r -> StringUtils.equals(materialCode, r.getMaterialCode())
                        && StringUtils.equals(machineCode, r.getLhMachineCode()));
    }

    /**
     * 记录置换信息到上下文，供S4.6生成模具交替计划时使用。
     *
     * <p>置换信息以机台编码为key存储，S4.6生成模具交替计划时可据此补充备注。</p>
     *
     * @param context 排程上下文
     * @param materialCode 特殊材料SKU物料编码
     * @param substitutionType 置换类型
     * @param replacedMachineCode 被置换机台编码
     * @param replacedMaterialCode 被置换SKU物料编码
     */
    private void recordSubstitutionInfo(LhScheduleContext context, String materialCode,
                                        SubstitutionTypeEnum substitutionType,
                                        String replacedMachineCode, String replacedMaterialCode) {
        if (Objects.isNull(substitutionType) || StringUtils.isEmpty(replacedMachineCode)) {
            return;
        }
        // 构建模具交替计划备注
        String remark = substitutionType.buildRemark(replacedMachineCode, replacedMaterialCode);
        // 在排程结果中找到特殊材料SKU在该机台上的结果，设置换模标识确保生成交替计划
        for (LhScheduleResult result : context.getScheduleResultList()) {
            if (StringUtils.equals(materialCode, result.getMaterialCode())
                    && StringUtils.equals(replacedMachineCode, result.getLhMachineCode())) {
                // 确保换模标识为1，S4.6会据此生成模具交替计划
                result.setIsChangeMould("1");
                break;
            }
        }
        // 记录置换备注到上下文，供S4.6生成交替计划时追加备注
        Map<String, String> substitutionRemarkMap = context.getSubstitutionRemarkMap();
        if (Objects.isNull(substitutionRemarkMap)) {
            substitutionRemarkMap = new LinkedHashMap<String, String>(8);
            context.setSubstitutionRemarkMap(substitutionRemarkMap);
        }
        substitutionRemarkMap.put(replacedMachineCode, remark);
        log.info("特殊材料置换备注已记录, 机台: {}, 备注: {}", replacedMachineCode, remark);
    }

    /**
     * 解析置换失败原因。
     *
     * @param context 排程上下文
     * @param sku 特殊材料SKU
     * @return 失败原因
     */
    private String resolveSubstitutionFailureReason(LhScheduleContext context, SkuScheduleDTO sku) {
        // 检查是否有候选机台
        boolean hasCandidate = context.getScheduleResultList().stream()
                .anyMatch(r -> !isSpecialMaterial(context, r.getMaterialCode(), r.getStructureName())
                        && LhMachineHardMatchUtil.isMachineHardMatched(context, sku,
                                context.getMachineScheduleMap().get(r.getLhMachineCode())));
        if (!hasCandidate) {
            return UNSCHEDULED_REASON_NO_CANDIDATE;
        }
        // 有候选但置换失败，按约束类型判断
        // 简化判断：统一返回约束不满足
        return UNSCHEDULED_REASON_STILL_UNSCHEDULED;
    }

    /**
     * 从未排结果中移除指定业务SKU的全部记录。
     * <p>特殊物料重新调用新增策略时可能生成新的未排记录；置换成功后必须按
     * “物料+产品状态”统一清理，避免同一状态同时存在已排结果和未排结果。</p>
     *
     * @param context 排程上下文
     * @param unscheduled 待移除的未排结果
     */
    private void removeUnscheduledResult(LhScheduleContext context, LhUnscheduledResult unscheduled) {
        context.getUnscheduledResultList().removeIf(result -> StringUtils.equals(
                unscheduled.getMaterialCode(), result.getMaterialCode())
                && StringUtils.equals(StringUtils.trimToEmpty(unscheduled.getProductStatus()),
                StringUtils.trimToEmpty(result.getProductStatus())));
    }

    /**
     * 更新未排结果的未排原因。
     *
     * @param context 排程上下文
     * @param unscheduled 未排结果
     * @param reason 未排原因
     */
    private void updateUnscheduledReason(LhScheduleContext context, LhUnscheduledResult unscheduled,
                                          String reason) {
        unscheduled.setUnscheduledReason(reason);
    }

    /**
     * 添加置换相关的未排结果。
     *
     * @param context 排程上下文
     * @param sku SKU排程信息
     * @param reason 未排原因
     */
    private void addSubstitutionUnscheduledResult(LhScheduleContext context, SkuScheduleDTO sku,
                                                   String reason) {
        LhUnscheduledResult unscheduled = new LhUnscheduledResult();
        unscheduled.setFactoryCode(context.getFactoryCode());
        unscheduled.setBatchNo(context.getBatchNo());
        unscheduled.setMaterialCode(sku.getMaterialCode());
        unscheduled.setProductStatus(sku.getProductStatus());
        unscheduled.setMaterialDesc(sku.getMaterialDesc());
        unscheduled.setStructureName(sku.getStructureName());
        unscheduled.setSpecCode(sku.getSpecCode());
        unscheduled.setMainMaterialDesc(sku.getMainMaterialDesc());
        unscheduled.setEmbryoCode(sku.getEmbryoCode());
        unscheduled.setScheduleDate(context.getScheduleTargetDate());
        unscheduled.setUnscheduledQty(sku.resolveTargetScheduleQty());
        unscheduled.setUnscheduledReason(reason);
        unscheduled.setMonthPlanVersion(sku.getMonthPlanVersion());
        unscheduled.setProductionVersion(sku.getProductionVersion());
        context.getUnscheduledResultList().add(unscheduled);
    }

    /**
     * 从上下文中查找指定物料编码的SKU排程信息。
     *
     * @param context 排程上下文
     * @param materialCode 物料编码
     * @return SKU排程信息，未找到返回null
     */
    private SkuScheduleDTO resolveSkuScheduleDto(LhScheduleContext context,
                                                 String materialCode,
                                                 String productStatus) {
        if (StringUtils.isEmpty(materialCode)) {
            return null;
        }
        // 优先从全量SKU索引Map查找（S4.3填充，永不清空，包含被阻塞的SKU）
        String skuKey = MonthPlanDateResolver.buildMaterialStatusKey(materialCode, productStatus);
        SkuScheduleDTO indexedSku = context.getAllSkuScheduleDtoMap().get(skuKey);
        if (Objects.nonNull(indexedSku)) {
            return indexedSku;
        }
        // 优先从新增待排列表查找
        for (SkuScheduleDTO sku : context.getNewSpecSkuList()) {
            if (isSameSku(materialCode, productStatus, sku)) {
                return sku;
            }
        }
        // 从续作列表查找
        for (SkuScheduleDTO sku : context.getContinuousSkuList()) {
            if (isSameSku(materialCode, productStatus, sku)) {
                return sku;
            }
        }
        // 从排程结果来源SKU映射中查找
        for (SkuScheduleDTO sku : context.getScheduleResultSourceSkuMap().values()) {
            if (isSameSku(materialCode, productStatus, sku)) {
                return sku;
            }
        }
        // 从结构分组兑底查询来源查找（S4.5处理后SKU已从待排列表移除，但仍保留在结构分组中）
        if (!CollectionUtils.isEmpty(context.getStructureSkuMap())) {
            for (List<SkuScheduleDTO> skuList : context.getStructureSkuMap().values()) {
                if (CollectionUtils.isEmpty(skuList)) {
                    continue;
                }
                for (SkuScheduleDTO sku : skuList) {
                    if (isSameSku(materialCode, productStatus, sku)) {
                        return sku;
                    }
                }
            }
        }
        return null;
    }

    /**
     * 判断SKU是否与指定物料状态一致。
     *
     * @param materialCode 物料编码
     * @param productStatus 产品状态
     * @param sku 待比较SKU
     * @return true-物料和产品状态均一致
     */
    private boolean isSameSku(String materialCode, String productStatus, SkuScheduleDTO sku) {
        return Objects.nonNull(sku)
                && StringUtils.equals(materialCode, sku.getMaterialCode())
                && StringUtils.equals(StringUtils.trimToEmpty(productStatus),
                StringUtils.trimToEmpty(sku.getProductStatus()));
    }

    /**
     * 将Date转换为LocalDate。
     *
     * @param date 日期
     * @return LocalDate
     */
    private LocalDate toLocalDate(Date date) {
        if (Objects.isNull(date)) {
            return null;
        }
        return date.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
    }
}
