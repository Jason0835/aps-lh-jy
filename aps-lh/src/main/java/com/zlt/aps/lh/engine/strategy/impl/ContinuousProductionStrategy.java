/**
 * Copyright (c) 2008, 智立通（厦门）科技有限公司 All rights reserved。
 */
package com.zlt.aps.lh.engine.strategy.impl;

import cn.hutool.core.bean.BeanUtil;
import com.zlt.aps.lh.api.constant.LhScheduleConstant;
import com.zlt.aps.lh.api.constant.LhScheduleParamConstant;
import com.zlt.aps.lh.api.domain.dto.MachineCleaningWindowDTO;
import com.zlt.aps.lh.api.domain.dto.MachineMaintenanceWindowDTO;
import com.zlt.aps.lh.api.domain.dto.SkuDailyPlanQuotaDTO;
import com.zlt.aps.lh.component.TargetScheduleQtyResolver;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.api.domain.dto.MachineScheduleDTO;
import com.zlt.aps.lh.api.domain.dto.ShiftProductionControlDTO;
import com.zlt.aps.lh.api.domain.vo.LhShiftConfigVO;
import com.zlt.aps.lh.api.domain.dto.ShiftRuntimeState;
import com.zlt.aps.lh.api.domain.dto.SkuScheduleDTO;
import com.zlt.aps.lh.api.domain.entity.LhScheduleResult;
import com.zlt.aps.lh.api.domain.entity.LhUnscheduledResult;
import com.zlt.aps.lh.api.enums.ScheduleTypeEnum;
import com.zlt.aps.lh.api.enums.ShiftEnum;
import com.zlt.aps.lh.api.enums.SkuTagEnum;
import com.zlt.aps.lh.engine.strategy.ICapacityCalculateStrategy;
import com.zlt.aps.lh.engine.strategy.IEndingJudgmentStrategy;
import com.zlt.aps.lh.engine.strategy.IFirstInspectionBalanceStrategy;
import com.zlt.aps.lh.engine.strategy.IMachineMatchStrategy;
import com.zlt.aps.lh.engine.strategy.IMouldChangeBalanceStrategy;
import com.zlt.aps.lh.engine.strategy.IProductionStrategy;
import com.zlt.aps.lh.engine.strategy.support.DailyMachineExpansionPlanner;
import com.zlt.aps.lh.engine.strategy.support.DailyMachineShortageQuotaPlan;
import com.zlt.aps.lh.engine.strategy.support.ProductionQuantityPolicy;
import com.zlt.aps.lh.engine.strategy.support.SmallEndingSurplusSkipRule;
import com.zlt.aps.lh.service.impl.LhMaintenanceScheduleService;
import com.zlt.aps.lh.util.LeftRightMouldUtil;
import com.zlt.aps.lh.util.ShiftFieldUtil;
import com.zlt.aps.lh.util.LhMouldCodeUtil;
import com.zlt.aps.lh.util.LhScheduleTimeUtil;
import com.zlt.aps.lh.util.LhSingleControlMachineUtil;
import com.zlt.aps.lh.util.LhSpecialMaterialUtil;
import com.zlt.aps.lh.util.LhMultiMachineDistributionUtil;
import com.zlt.aps.lh.util.LhSpecifyMachineUtil;
import com.zlt.aps.lh.util.MachineCleaningOverlapUtil;
import com.zlt.aps.lh.util.MonthPlanDayQtyUtil;
import com.zlt.aps.lh.util.PriorityTraceLogHelper;
import com.zlt.aps.lh.util.ResultDowntimeSummaryUtil;
import com.zlt.aps.lh.util.ShiftCapacityResolverUtil;
import com.zlt.aps.lh.util.ShiftProductionControlUtil;
import com.zlt.aps.lh.util.SingleMouldShiftQtyUtil;
import com.zlt.aps.lh.util.SkuDailyPlanQuotaUtil;
import com.zlt.aps.lh.component.OrderNoGenerator;
import com.zlt.aps.mdm.api.domain.entity.MdmMaterialInfo;
import com.zlt.aps.mdm.api.domain.entity.MdmDevicePlanShut;
import com.zlt.aps.mdm.api.domain.entity.MdmSkuConstructionRef;
import com.zlt.aps.mp.api.domain.entity.FactoryMonthPlanProductionFinalResult;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import javax.annotation.Resource;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 续作排产策略实现。
 *
 * <p>业务定位：</p>
 * <ul>
 *   <li>处理 S4.4 中 MES 在机或滚动继承形成的续作 SKU；</li>
 *   <li>负责续作收尾判断、单机台目标量调整、班次分配、胎胚库存裁剪、日计划账本同步和多机台降模；</li>
 *   <li>在非收尾场景下可触发定点机台挤量，为后续 S4.5 新增换模预留窗口；</li>
 *   <li>生成的结果会进入 S4.6 统一校验、换模计划和持久化流程。</li>
 * </ul>
 *
 * <p>注意：续作路径与换活字块、新增路径共享 {@code LhScheduleContext} 的机台状态和日计划账本。
 * 维护本类时需要同步确认 {@code TypeBlockProductionStrategy}、{@code NewSpecProductionStrategy}
 * 和后置校验的 sourceSku 口径。</p>
 *
 * @author APS
 */
@Slf4j
@Component("continuousProductionStrategy")
public class ContinuousProductionStrategy implements IProductionStrategy {

    private static final String CONTINUOUS_SCHEDULE_TYPE = "01";
    private static final String AUTO_DATA_SOURCE = "0";
    private static final String ZERO_PLAN_UNSCHEDULED_REASON = "续作结果裁剪为0";
    private static final String SHARED_EMBRYO_ZERO_SURPLUS_UNSCHEDULED_REASON =
            "共用胎胚且硫化余量为0";
    private static final String WINDOW_NO_PLAN_UNSCHEDULED_REASON =
            "当前排程窗口内无日计划量，等待后续滚动窗口排产";
    private static final String SMALL_ENDING_SURPLUS_UNSCHEDULED_REASON =
            SmallEndingSurplusSkipRule.UNSCHEDULED_REASON;
    private static final String SINGLE_MACHINE_REDUCED_CONTINUATION_KEY_SUFFIX = "#SINGLE_MACHINE_REDUCED";
    private static final int TYPE_BLOCK_SWITCH_MAX_ATTEMPTS = 16;
    private static final String MAIN_SALE_PRODUCTION_TYPE = "01";
    private static final LocalTime MAIN_SALE_ENDING_FILL_THRESHOLD_TIME = LocalTime.of(20, 0);

    @Resource
    private OrderNoGenerator orderNoGenerator;

    @Resource
    private IEndingJudgmentStrategy endingJudgmentStrategy;
    @Resource
    private TargetScheduleQtyResolver targetScheduleQtyResolver;
    @Resource
    private LhMaintenanceScheduleService maintenanceScheduleService;

    /**
     * 定点物料新增换模预判沿用默认策略 Bean，保证与主流程口径一致。
     */
    @Resource
    private IMouldChangeBalanceStrategy mouldChangeBalanceStrategy;

    @Resource
    private IFirstInspectionBalanceStrategy firstInspectionBalanceStrategy;

    @Resource
    private ICapacityCalculateStrategy capacityCalculateStrategy;

    @Override
    public String getStrategyType() {
        return ScheduleTypeEnum.CONTINUOUS.getCode();
    }

    @Override
    public String getStrategyName() {
        return "continuousProductionStrategy";
    }

    @Override
    public void scheduleContinuousEnding(LhScheduleContext context) {
        log.info("续作排产 - 续作收尾判定, 续作SKU数: {}", context.getContinuousSkuList().size());

        List<LhShiftConfigVO> shifts = LhScheduleTimeUtil.getScheduleShifts(context, context.getScheduleDate());
        // continuationGroupMachineCountMap 用于区分同一物料/账本是否多机台续作，决定单机满排还是交给降模分摊。
        Map<String, Integer> continuationGroupMachineCountMap = buildContinuationGroupMachineCountMap(
                context.getContinuousSkuList());
        // 提前登记释放机台，让 S4.4 中的换活字块预判和 S4.5 新增选机都能使用一致的机台优先级视图。
        preRegisterReleasedContinuousMachines(context, shifts);

        for (SkuScheduleDTO sku : context.getContinuousSkuList()) {
            String machineCode = sku.getContinuousMachineCode();
            MachineScheduleDTO machine = context.getMachineScheduleMap().get(machineCode);
            if (machine == null) {
                log.warn("续作SKU未匹配到机台状态，跳过续作排产, materialCode: {}, 续作机台: {}, 目标量: {}",
                        sku.getMaterialCode(), machineCode, sku.resolveTargetScheduleQty());
                continue;
            }
            // 动态收尾目标量需要先按真实机台模数归整，保证目标量、运行态账本和最终落库口径一致。
            int machineMouldQty = ShiftCapacityResolverUtil.resolveMachineMouldQty(machine);
            sku.setMouldQty(machineMouldQty);
            DailyMachineShortageQuotaPlan shortageQuotaPlan =
                    DailyMachineExpansionPlanner.prepareShortageQuota(context, sku, "续作排产");
            if (shouldReleaseWindowNoPlanContinuousSku(sku, shortageQuotaPlan)) {
                // 当前窗口没有日计划且无本月历史欠产时，不提前消耗远期计划，释放机台给换活字块/新增。
                appendWindowNoPlanContinuousUnscheduledResult(context, sku);
                registerReleasedContinuousMachine(context, machineCode, sku.getMaterialCode(), "窗口内无日计划");
                log.info("续作SKU当前窗口无日计划量，释放续作机台给换模/新增排产, materialCode: {}, "
                                + "machineCode: {}, targetQty: {}, surplusQty: {}, futurePlanQtyAfterWindow: {}",
                        sku.getMaterialCode(), machineCode, sku.resolveTargetScheduleQty(),
                        Math.max(0, sku.getSurplusQty()),
                        Math.max(0, shortageQuotaPlan.getFutureMonthPlanQtyAfterWindow()));
                continue;
            }
            if (shouldReleaseFirstDayNoPlanContinuousSku(sku, shifts, shortageQuotaPlan)) {
                registerReleasedFirstDayNoPlanContinuousMachine(context, machineCode, sku.getMaterialCode());
                log.info("续作SKU当前day1日计划为0，跳过day1续作并释放机台给换活字块/新增排产, "
                                + "materialCode: {}, machineCode: {}, windowPlanQty: {}, quotaRemainingQty: {}, dayPlanSummary: {}",
                        sku.getMaterialCode(), machineCode, sumDailyPlanQty(sku.getDailyPlanQuotaMap()),
                        SkuDailyPlanQuotaUtil.sumRemainingQty(sku.getDailyPlanQuotaMap()),
                        formatDailyPlanQuotaSummary(sku));
                continue;
            }

            // 窗口无计划但仍有续作余量时必须先排完，再沿用既有收尾机台释放链。
            boolean finishWindowNoPlanSurplus =
                    shouldFinishWindowNoPlanContinuousSurplus(sku, shortageQuotaPlan);
            if (finishWindowNoPlanSurplus) {
                applyContinuousWindowNoPlanSurplusStrictTarget(context, sku, shortageQuotaPlan);
            }
            // SKU收尾判定决定是否严格控量：收尾必须按目标量停，非收尾才允许后续补满可用班次。
            boolean isEnding = finishWindowNoPlanSurplus || endingJudgmentStrategy.isEnding(context, sku);
            if (shortageQuotaPlan.isForceEndingByNoFuturePlan()) {
                isEnding = true;
                applyContinuousNoFutureEndingStrictTarget(sku, shortageQuotaPlan);
            } else if (sku.isStrictNewSpecShortageOnly()) {
                isEnding = false;
            }
            if (shouldSkipSmallEndingSurplusContinuous(context, sku, isEnding)) {
                // 收尾小余量 + 前日 T+1 夜班未排满不排产：释放原续作机台给换活字块/新增链路。
                appendSmallEndingSurplusUnscheduledResult(context, sku);
                registerReleasedContinuousMachine(context, machineCode, sku.getMaterialCode(),
                        "收尾小余量且前日T+1夜班未排满不排产");
                registerTypeBlockReleasedContinuousMachine(context, machineCode, sku.getMaterialCode(),
                        "收尾小余量且前日T+1夜班未排满不排产");
                context.removePendingSkuFromStructureMap(sku);
                getTargetScheduleQtyResolver().removeActiveEmbryoSku(
                        context, sku, SMALL_ENDING_SURPLUS_UNSCHEDULED_REASON);
                traceSmallEndingSurplusSkip(context, sku, machineCode,
                        resolveContinuousEndingSurplusToleranceQty(context));
                continue;
            }
            sku.setStrictTargetQty(ProductionQuantityPolicy.from(sku, isEnding).isStrictUpperLimit());
            boolean isSingleMachine = continuationGroupMachineCountMap
                    .getOrDefault(buildContinuationGroupKey(sku), 0) == 1;

            // 滚动衔接时沿用机台继承后的可用时间；非收尾在机续作首日有计划时从窗口首班满排。
            Date startTime = resolveContinuousStartTime(context, sku, machine, shifts, isEnding);
            applySingleMachineContinuousTargetRule(context, sku, machine, startTime, shifts,
                    isEnding, isSingleMachine, shortageQuotaPlan);
            // 非收尾续作可以为定点新增物料挤出后续换模窗口；收尾场景不走挤量预留。
            Date specifySwitchStartTime = !isEnding
                    ? tryReserveSpecifySqueezeSwitchStartTime(context, machine, sku, shifts) : null;
            List<LhShiftConfigVO> effectiveShifts = specifySwitchStartTime == null
                    ? shifts : filterShiftsBeforeSwitchStart(shifts, specifySwitchStartTime);
            // 滚动继承结果可直接追加班次量，避免同一机台同一SKU拆成两条连续结果。
            // 若当前窗口需要为定点新增物料挤出换模时间，只使用切换前的有效班次构造结果。
            LhScheduleResult inheritedResult = findMergeableRollingInheritedResult(context, machineCode, sku.getMaterialCode());
            LhScheduleResult result = inheritedResult != null
                    ? appendScheduleToInheritedResult(context, inheritedResult, machine, sku,
                    startTime, effectiveShifts, machineMouldQty, isEnding)
                    : buildScheduleResult(context, machine, sku, startTime, null, effectiveShifts, machineMouldQty, isEnding);
            if (result != null) {
                result.setScheduleType("01");
                result.setIsChangeMould("0");
                result.setIsTypeBlock("0");
                result.setIsEnd(isEnding ? "1" : "0");
                registerResultSourceSku(context, result, sku);
                if (inheritedResult == null) {
                    context.getScheduleResultList().add(result);
                    registerMachineAssignment(context, machineCode, result);
                }
                // 续作已完成当日排产，不应继续参与后续结构优先级判断。
                context.removePendingSkuFromStructureMap(sku);

                // 如果是收尾，更新机台收尾信息；换活字块策略会基于该时间寻找后续衔接SKU。
                if (isEnding && result.getSpecEndTime() != null) {
                    Date actualCompletionTime = resolveActualCompletionTime(context, result);
                    machine.setEnding(true);
                    machine.setEstimatedEndTime(actualCompletionTime);
                    traceContinuousEndingUpdate(context, machine, sku, result, actualCompletionTime);
                } else if (specifySwitchStartTime != null && result.getDailyPlanQty() != null
                        && result.getDailyPlanQty() > 0) {
                    // 非收尾续作让出指定切换起点，后续换活字块/新增按该时刻识别机台已经可切换。
                    machine.setEnding(true);
                    machine.setEstimatedEndTime(specifySwitchStartTime);
                    context.getSpecifyMachineReservedSwitchStartTimeMap().put(machineCode, specifySwitchStartTime);
                    log.info("触发定点机台挤量, machineCode: {}, currentMaterialCode: {}, reservedMaterialCode: {}, switchStartTime: {}",
                            machineCode, sku.getMaterialCode(),
                            context.getSpecifyMachineReservedMaterialMap().get(machineCode),
                            LhScheduleTimeUtil.formatDateTime(specifySwitchStartTime));
                }
                log.debug("续作SKU排产完成, materialCode: {}, 机台: {}, 开始时间: {}, 日计划量: {}, 是否收尾: {}",
                        sku.getMaterialCode(), machineCode,
                        LhScheduleTimeUtil.formatDateTime(startTime), result.getDailyPlanQty(), isEnding);
            } else {
                log.warn("续作SKU未生成有效排程结果, materialCode: {}, 机台: {}, 开始时间: {}, 目标量: {}",
                        sku.getMaterialCode(), machineCode,
                        LhScheduleTimeUtil.formatDateTime(startTime), sku.resolveTargetScheduleQty());
            }
        }
        log.info("续作收尾判定结束, 续作SKU: {}, 当前排程结果数: {}, 待新增SKU: {}",
                context.getContinuousSkuList().size(), context.getScheduleResultList().size(),
                context.getNewSpecSkuList().size());
    }

    /**
     * 单机台续作目标量决策。
     *
     * <p>规则说明：</p>
     * <ul>
     *   <li>单机台收尾：按收尾上调规则处理，确保尾量和胎胚库存口径一致；</li>
     *   <li>单机台非收尾且满排模式：按当前机台真实窗口产能作为目标量；</li>
     *   <li>多机台：保持原多机台分摊/降模规则，不在此处改写目标量；</li>
     *   <li>按需求模式：沿用 S4.3 计算出的需求目标量。</li>
     * </ul>
     *
     * @param context 排程上下文
     * @param sku SKU
     * @param machine 机台
     * @param startTime 开产时间
     * @param shifts 排程窗口班次
     * @param isEnding 是否收尾
     * @param isSingleMachine 是否单机台
     * @param shortageQuotaPlan 欠产账本准备结果
     */
    private void applySingleMachineContinuousTargetRule(LhScheduleContext context,
                                                        SkuScheduleDTO sku,
                                                        MachineScheduleDTO machine,
                                                        Date startTime,
                                                        List<LhShiftConfigVO> shifts,
                                                        boolean isEnding,
                                                        boolean isSingleMachine,
                                                        DailyMachineShortageQuotaPlan shortageQuotaPlan) {
        if (sku == null || machine == null) {
            return;
        }
        int originalTargetQty = sku.resolveTargetScheduleQty();
        int windowCapacityQty = startTime == null ? 0
                : getTargetScheduleQtyResolver().calcMachineAvailableCapacityByStartTime(
                context, sku, machine, null, startTime, shifts, ScheduleTypeEnum.CONTINUOUS.getCode());
        String appliedRule = "沿用原规则";
        if (isSingleMachine && isEnding
                && shortageQuotaPlan != null && shortageQuotaPlan.isForceEndingByNoFuturePlan()) {
            appliedRule = "窗口及月底无计划收尾严格控量";
        } else if (isSingleMachine && isEnding) {
            getTargetScheduleQtyResolver().upsizeEndingTargetQty(context, sku);
            appliedRule = getTargetScheduleQtyResolver().isSharedEmbryoInWindow(context, sku)
                    ? "单机台收尾共用胎胚仅按余量" : "单机台收尾MAX(余量,胎胚库存)";
        } else if (isSingleMachine && sku.isStrictNewSpecShortageOnly()) {
            appliedRule = "窗口无计划仅补本月欠产";
        } else if (isSingleMachine && getTargetScheduleQtyResolver().isFullCapacityMode(context)) {
            sku.setTargetScheduleQty(windowCapacityQty);
            sku.setRemainingScheduleQty(windowCapacityQty);
            appliedRule = "单机台非收尾满排窗口";
        } else if (!isSingleMachine) {
            appliedRule = "多机台沿用原规则";
        } else if (!getTargetScheduleQtyResolver().isFullCapacityMode(context)) {
            appliedRule = "按需求模式沿用原规则";
        }
        log.info("S4.4续作目标量决策, scene: continuous, materialCode: {}, machineCode: {}, isSingleMachine: {}, "
                        + "isEnding: {}, surplusQty: {}, embryoStock: {}, originalTargetQty: {}, windowCapacityQty: {}, "
                        + "adoptedTargetQty: {}, rule: {}",
                sku.getMaterialCode(), machine.getMachineCode(), isSingleMachine, isEnding,
                Math.max(0, sku.getSurplusQty()), Math.max(0, sku.getEmbryoStock()), originalTargetQty,
                windowCapacityQty, sku.resolveTargetScheduleQty(), appliedRule);
    }

    /**
     * 解析续作起排时间。
     * <p>滚动衔接场景下从机台继承后的可用时间继续排；非收尾在机续作首日有日计划时从窗口首班开始。</p>
     */
    private Date resolveContinuousStartTime(LhScheduleContext context,
                                            SkuScheduleDTO sku,
                                            MachineScheduleDTO machine,
                                            List<LhShiftConfigVO> shifts,
                                            boolean isEnding) {
        Date defaultStartTime = resolveFirstPositiveDailyPlanStartTime(sku, shifts, isEnding);
        if (context == null || !context.isRollingScheduleHandoff()) {
            return defaultStartTime;
        }
        Date appendStartTime = resolveRollingAppendStartTime(context, shifts);
        if (appendStartTime != null && appendStartTime.after(defaultStartTime)) {
            defaultStartTime = appendStartTime;
        }
        if (machine == null || machine.getEstimatedEndTime() == null) {
            return defaultStartTime;
        }
        if (machine.getEstimatedEndTime().after(defaultStartTime)) {
            return machine.getEstimatedEndTime();
        }
        return defaultStartTime;
    }

    /**
     * 解析首个有日计划剩余额度的续作起排班次。
     *
     * @param sku 续作SKU
     * @param shifts 排程窗口班次
     * @return 首个可排班次开始时间；无可识别额度时返回窗口首班开始时间
     */
    private Date resolveFirstPositiveDailyPlanStartTime(SkuScheduleDTO sku,
                                                        List<LhShiftConfigVO> shifts,
                                                        boolean isEnding) {
        Date defaultStartTime = CollectionUtils.isEmpty(shifts) ? new Date() : shifts.get(0).getShiftStartDateTime();
        if (sku == null || CollectionUtils.isEmpty(sku.getDailyPlanQuotaMap()) || CollectionUtils.isEmpty(shifts)) {
            return defaultStartTime;
        }
        if (!isEnding && hasFirstWindowDateDailyPlan(sku, shifts)) {
            return defaultStartTime;
        }
        Set<LocalDate> positivePlanDateSet = resolvePositiveDailyPlanDateSet(sku);
        for (LhShiftConfigVO shift : shifts) {
            LocalDate workDate = resolveShiftWorkDate(shift);
            if (workDate != null && positivePlanDateSet.contains(workDate)) {
                return shift.getShiftStartDateTime();
            }
        }
        return defaultStartTime;
    }

    /**
     * 判断窗口首日是否配置了日计划。
     *
     * @param sku 续作SKU
     * @param shifts 排程窗口班次
     * @return true-首日有日计划
     */
    private boolean hasFirstWindowDateDailyPlan(SkuScheduleDTO sku, List<LhShiftConfigVO> shifts) {
        if (sku == null || CollectionUtils.isEmpty(sku.getDailyPlanQuotaMap()) || CollectionUtils.isEmpty(shifts)) {
            return false;
        }
        LocalDate firstWindowDate = resolveShiftWorkDate(shifts.get(0));
        if (firstWindowDate == null) {
            return false;
        }
        SkuDailyPlanQuotaDTO quota = sku.getDailyPlanQuotaMap().get(firstWindowDate);
        return quota != null && Math.max(0, quota.getDayPlanQty()) > 0;
    }

    /**
     * 判断续作首个有计划日是否晚于排程窗口首日。
     *
     * @param sku 续作SKU
     * @param shifts 排程窗口班次
     * @return true-首日无计划，后续日期才有计划
     */
    private boolean isFirstPositiveDailyPlanLaterThanWindowFirstDate(SkuScheduleDTO sku, List<LhShiftConfigVO> shifts) {
        if (sku == null || CollectionUtils.isEmpty(sku.getDailyPlanQuotaMap()) || CollectionUtils.isEmpty(shifts)) {
            return false;
        }
        LocalDate firstWindowDate = resolveShiftWorkDate(shifts.get(0));
        if (firstWindowDate == null) {
            return false;
        }
        Set<LocalDate> positivePlanDateSet = resolvePositiveDailyPlanDateSet(sku);
        for (LhShiftConfigVO shift : shifts) {
            LocalDate workDate = resolveShiftWorkDate(shift);
            if (workDate != null && positivePlanDateSet.contains(workDate)) {
                return workDate.isAfter(firstWindowDate);
            }
        }
        return false;
    }

    /**
     * 判断首日无计划但后续仍有计划的续作SKU是否应释放原机台。
     * <p>该场景不在当前 day1 继续占用原续作机台，后续计划量交由 S4.5 新增补偿重新选机。</p>
     *
     * @param sku 续作SKU
     * @param shifts 排程窗口班次
     * @param shortageQuotaPlan 欠产账本准备结果
     * @return true-释放原续作机台
     */
    private boolean shouldReleaseFirstDayNoPlanContinuousSku(SkuScheduleDTO sku,
                                                             List<LhShiftConfigVO> shifts,
                                                             DailyMachineShortageQuotaPlan shortageQuotaPlan) {
        if (sku == null || CollectionUtils.isEmpty(shifts)
                || Math.max(0, shortageQuotaPlan == null ? sku.getMonthlyHistoryShortageQty()
                : shortageQuotaPlan.getHistoryShortageQty()) > 0) {
            return false;
        }
        return isFirstWindowDateNoDailyPlan(sku, shifts)
                && isFirstPositiveDailyPlanLaterThanWindowFirstDate(sku, shifts);
    }

    /**
     * 续作窗口及月底均无日计划时，按硫化余量严格控制收尾目标。
     *
     * @param sku 续作SKU
     * @param shortageQuotaPlan 欠产账本准备结果
     */
    private void applyContinuousNoFutureEndingStrictTarget(SkuScheduleDTO sku,
                                                           DailyMachineShortageQuotaPlan shortageQuotaPlan) {
        if (sku == null || shortageQuotaPlan == null || !shortageQuotaPlan.isForceEndingByNoFuturePlan()) {
            return;
        }
        // 窗口及月底均无计划时已明确进入收尾清量，统一收尾标签必须同步到后续日计划扣账链路。
        String originalSkuTag = sku.getSkuTag();
        sku.setSkuTag(SkuTagEnum.ENDING.getCode());
        if (sku.getEndingDaysRemaining() <= 0) {
            sku.setEndingDaysRemaining(1);
        }
        int strictTargetQty = ShiftCapacityResolverUtil.roundUpQtyToMouldMultiple(
                Math.max(0, sku.getSurplusQty()), sku.getMouldQty());
        sku.setStrictTargetQty(true);
        sku.setTargetScheduleQty(strictTargetQty);
        sku.setRemainingScheduleQty(strictTargetQty);
        sku.setWindowPlanQty(strictTargetQty);
        sku.setWindowRemainingPlanQty(strictTargetQty);
        log.info("续作窗口及月底均无日计划，按硫化余量严格控量并同步收尾状态, materialCode: {}, "
                        + "surplusQty: {}, historyShortageQty: {}, originalSkuTag: {}, endingSkuTag: {}, "
                        + "endingDaysRemaining: {}, strictTargetQty: {}",
                sku.getMaterialCode(), Math.max(0, sku.getSurplusQty()),
                Math.max(0, shortageQuotaPlan.getHistoryShortageQty()), originalSkuTag, sku.getSkuTag(),
                sku.getEndingDaysRemaining(), strictTargetQty);
    }

    /**
     * 判断排程窗口首个业务日是否无日计划剩余额度。
     *
     * @param sku 续作SKU
     * @param shifts 排程窗口班次
     * @return true-首日无计划且无剩余额度
     */
    private boolean isFirstWindowDateNoDailyPlan(SkuScheduleDTO sku, List<LhShiftConfigVO> shifts) {
        if (sku == null || CollectionUtils.isEmpty(sku.getDailyPlanQuotaMap()) || CollectionUtils.isEmpty(shifts)) {
            return false;
        }
        LocalDate firstWindowDate = resolveShiftWorkDate(shifts.get(0));
        if (firstWindowDate == null) {
            return false;
        }
        SkuDailyPlanQuotaDTO quota = sku.getDailyPlanQuotaMap().get(firstWindowDate);
        return quota != null && Math.max(0, quota.getDayPlanQty()) <= 0
                && Math.max(0, quota.getRemainingQty()) <= 0;
    }

    /**
     * 解析续作SKU窗口内仍有剩余额度的业务日期集合。
     *
     * @param sku 续作SKU
     * @return 有剩余额度的业务日期集合
     */
    private Set<LocalDate> resolvePositiveDailyPlanDateSet(SkuScheduleDTO sku) {
        Set<LocalDate> positivePlanDateSet = new LinkedHashSet<LocalDate>(4);
        if (sku == null || CollectionUtils.isEmpty(sku.getDailyPlanQuotaMap())) {
            return positivePlanDateSet;
        }
        for (Map.Entry<LocalDate, SkuDailyPlanQuotaDTO> entry : sku.getDailyPlanQuotaMap().entrySet()) {
            SkuDailyPlanQuotaDTO quota = entry.getValue();
            if (quota != null && quota.getRemainingQty() > 0) {
                positivePlanDateSet.add(entry.getKey());
            }
        }
        return positivePlanDateSet;
    }

    /**
     * 解析班次业务日期。
     *
     * @param shift 班次配置
     * @return 业务日期；班次为空时返回null
     */
    private LocalDate resolveShiftWorkDate(LhShiftConfigVO shift) {
        if (shift == null || shift.getWorkDate() == null) {
            return null;
        }
        return shift.getWorkDate().toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
    }

    /**
     * 解析滚动排程的追加起点。
     * <p>只允许续作从目标日第一班开始继续排，避免回写到重叠继承窗口。</p>
     */
    private Date resolveRollingAppendStartTime(LhScheduleContext context, List<LhShiftConfigVO> shifts) {
        if (context == null
                || context.getWindowEndDate() == null
                || CollectionUtils.isEmpty(shifts)) {
            return null;
        }
        Date targetDate = LhScheduleTimeUtil.clearTime(context.getWindowEndDate());
        Date appendStartTime = null;
        for (LhShiftConfigVO shift : shifts) {
            if (shift == null
                    || shift.getWorkDate() == null
                    || shift.getShiftStartDateTime() == null) {
                continue;
            }
            if (!targetDate.equals(LhScheduleTimeUtil.clearTime(shift.getWorkDate()))) {
                continue;
            }
            if (appendStartTime == null || shift.getShiftStartDateTime().before(appendStartTime)) {
                appendStartTime = shift.getShiftStartDateTime();
            }
        }
        return appendStartTime;
    }

    /**
     * 查找可并入的滚动继承续作结果。
     *
     * @param context 排程上下文
     * @param machineCode 机台编号
     * @param materialCode 物料编码
     * @return 可并入结果；未命中返回 null
     */
    private LhScheduleResult findMergeableRollingInheritedResult(LhScheduleContext context,
                                                                 String machineCode,
                                                                 String materialCode) {
        if (context == null
                || StringUtils.isEmpty(machineCode)
                || StringUtils.isEmpty(materialCode)
                || CollectionUtils.isEmpty(context.getMachineAssignmentMap())) {
            return null;
        }
        List<LhScheduleResult> assignedResults = context.getMachineAssignmentMap().get(machineCode);
        if (CollectionUtils.isEmpty(assignedResults)) {
            return null;
        }
        for (int i = assignedResults.size() - 1; i >= 0; i--) {
            LhScheduleResult assignedResult = assignedResults.get(i);
            if (assignedResult == null
                    || !assignedResult.isRollingInherited()
                    || !StringUtils.equals(materialCode, assignedResult.getMaterialCode())) {
                continue;
            }
            return assignedResult;
        }
        return null;
    }

    /**
     * 将滚动衔接后的续作剩余计划并入已继承结果。
     *
     * @param context 排程上下文
     * @param inheritedResult 已继承结果
     * @param machine 机台
     * @param sku SKU
     * @param startTime 起排时间
     * @param shifts 班次列表
     * @param machineMouldQty 机台模台数
     * @param isEnding 是否收尾
     * @return 合并后的继承结果
     */
    private LhScheduleResult appendScheduleToInheritedResult(LhScheduleContext context,
                                                             LhScheduleResult inheritedResult,
                                                             MachineScheduleDTO machine,
                                                             SkuScheduleDTO sku,
                                                             Date startTime,
                                                             List<LhShiftConfigVO> shifts,
                                                             int machineMouldQty,
                                                             boolean isEnding) {
        LhScheduleResult appendedResult = buildScheduleResult(
                context, machine, sku, startTime, null, shifts, machineMouldQty, isEnding);
        if (appendedResult == null
                || appendedResult.getDailyPlanQty() == null
                || appendedResult.getDailyPlanQty() <= 0) {
            return null;
        }
        for (int shiftIndex = 1; shiftIndex <= LhScheduleConstant.MAX_SHIFT_SLOT_COUNT; shiftIndex++) {
            Integer shiftPlanQty = ShiftFieldUtil.getShiftPlanQty(appendedResult, shiftIndex);
            if (shiftPlanQty == null || shiftPlanQty <= 0) {
                continue;
            }
            ShiftFieldUtil.copyShiftPlanFields(appendedResult, shiftIndex, inheritedResult, shiftIndex);
        }
        inheritedResult.setIsEnd(isEnding ? "1" : "0");
        refreshResultSummary(context, inheritedResult, shifts);
        return inheritedResult;
    }

    @Override
    public void allocateShiftPlanQty(LhScheduleContext context) {
        log.info("续作排产 - 班次计划量分配");

        List<LhShiftConfigVO> shifts = LhScheduleTimeUtil.getScheduleShifts(context, context.getScheduleDate());

        for (LhScheduleResult result : context.getScheduleResultList()) {
            if (!CONTINUOUS_SCHEDULE_TYPE.equals(result.getScheduleType())
                    && !"1".equals(result.getIsTypeBlock())) {
                continue;
            }
            // 重新按班次分配（夜->早->中顺序按可用量分配）
            redistributeShiftQty(context, result, shifts);
        }
    }

    @Override
    public void adjustEmbryoStock(LhScheduleContext context) {
        log.info("续作排产 - 胎胚库存调整");
        List<LhShiftConfigVO> shifts = LhScheduleTimeUtil.getScheduleShifts(context, context.getScheduleDate());

        // 按来源SKU汇总多机台排产量，再统一做库存裁剪，避免同物料多条SKU互相串量。
        Map<SkuScheduleDTO, Integer> skuTotalPlanMap = new IdentityHashMap<SkuScheduleDTO, Integer>(16);
        Map<SkuScheduleDTO, List<LhScheduleResult>> skuResultMap = new IdentityHashMap<SkuScheduleDTO, List<LhScheduleResult>>(16);
        List<SkuScheduleDTO> skuOrder = new ArrayList<>(16);
        for (LhScheduleResult result : context.getScheduleResultList()) {
            if (!isPureContinuousResult(result)) {
                continue;
            }
            if (result.getEmbryoCode() == null) {
                continue;
            }
            SkuScheduleDTO sku = resolveResultSourceSku(context, result);
            if (sku == null || sku.getEmbryoStock() < 0) {
                continue;
            }
            int planQty = ShiftFieldUtil.resolveScheduledQty(result);
            if (!skuResultMap.containsKey(sku)) {
                skuResultMap.put(sku, new ArrayList<LhScheduleResult>());
                skuOrder.add(sku);
            }
            skuTotalPlanMap.merge(sku, planQty, Integer::sum);
            skuResultMap.get(sku).add(result);
        }
        // 按汇总计划量统一裁剪同来源SKU的所有结果
        for (SkuScheduleDTO sku : skuOrder) {
            int totalPlan = skuTotalPlanMap.getOrDefault(sku, 0);
            if (totalPlan <= 0 || totalPlan <= sku.getEmbryoStock()) {
                continue;
            }
            List<LhScheduleResult> skuResults = skuResultMap.get(sku);
            if (shouldKeepFormalContinuousFullCapacity(sku, skuResults)) {
                log.info("正式续作跳过胎胚库存后置裁减, materialCode: {}, totalPlan: {}, embryoStock: {}",
                        sku.getMaterialCode(), totalPlan, sku.getEmbryoStock());
                continue;
            }
            // 库存不足时按来源SKU整体裁剪，避免逐条逐班取整导致总量丢失。
            ShiftFieldUtil.scaleGroupedShiftPlanQty(skuResults, shifts, sku.getEmbryoStock());
            for (LhScheduleResult result : skuResults) {
                refreshResultSummary(context, result, shifts);
            }
        }
        refreshContinuousEndingFlagByResult(context);
    }

    /**
     * 正式续作在非试制场景下保留满班补齐结果，不做胎胚库存后置裁减。
     *
     * @param sku 来源SKU
     * @param skuResults 该SKU对应的续作结果
     * @return true-保留满班结果，不做库存裁减
     */
    private boolean shouldKeepFormalContinuousFullCapacity(SkuScheduleDTO sku, List<LhScheduleResult> skuResults) {
        if (sku == null || CollectionUtils.isEmpty(skuResults)) {
            return false;
        }
        boolean endingResult = skuResults.stream().anyMatch(result -> result != null && "1".equals(result.getIsEnd()));
        ProductionQuantityPolicy policy = ProductionQuantityPolicy.from(sku, endingResult);
        if (policy.isStrictUpperLimit() && !policy.isEnding()) {
            return false;
        }
        return true;
    }

    @Override
    public void scheduleReduceMould(LhScheduleContext context) {
        log.info("续作排产 - 降模排产");
        List<LhShiftConfigVO> shifts = LhScheduleTimeUtil.getScheduleShifts(context, context.getScheduleDate());

        // 按来源SKU分组找出同SKU多机台情况，避免同物料多条SKU共享目标量。
        Map<String, List<LhScheduleResult>> skuResultMap = new LinkedHashMap<String, List<LhScheduleResult>>(16);
        Map<String, SkuScheduleDTO> sourceSkuMap = new LinkedHashMap<String, SkuScheduleDTO>(16);
        List<String> skuOrder = new ArrayList<>(16);
        for (LhScheduleResult result : context.getScheduleResultList()) {
            if (!isPureContinuousResult(result)) {
                continue;
            }
            SkuScheduleDTO sourceSku = resolveResultSourceSku(context, result);
            if (sourceSku == null) {
                continue;
            }
            String groupKey = buildReduceMouldGroupKey(result, sourceSku);
            if (!skuResultMap.containsKey(groupKey)) {
                skuResultMap.put(groupKey, new ArrayList<LhScheduleResult>());
                sourceSkuMap.put(groupKey, sourceSku);
                skuOrder.add(groupKey);
            }
            skuResultMap.get(groupKey).add(result);
        }

        for (String groupKey : skuOrder) {
            SkuScheduleDTO sourceSku = sourceSkuMap.get(groupKey);
            List<LhScheduleResult> skuResults = skuResultMap.get(groupKey);
            if (skuResults.size() <= 1) {
                continue;
            }

            applyMultiMachineEndingTargetRule(context, sourceSku, skuResults);
            log.info("续作同SKU多机台识别, materialCode: {}, 机台列表: {}, 是否多机台: {}",
                    sourceSku.getMaterialCode(), joinMachineCodes(skuResults), true);
            if (reduceEndingContinuationToSingleMachineWhenCovered(context, sourceSku, skuResults, shifts)) {
                continue;
            }
            if (shouldUseTargetQtyForContinuationReduction(sourceSku)) {
                log.info("续作多机台跳过dayN降模, materialCode: {}, 目标量: {}, 窗口日计划量: {}, "
                                + "原因: 日计划仅用于准入和增机台判断，不限制当前窗口清尾排产量",
                        sourceSku.getMaterialCode(), sourceSku.resolveTargetScheduleQty(),
                        sumDailyPlanQty(sourceSku.getDailyPlanQuotaMap()));
                capStrictEndingContinuationGroupToTarget(context, sourceSku, skuResults, shifts);
                continue;
            }
            if (capEndingFirstDayOnlyContinuationGroup(context, sourceSku, skuResults, shifts)) {
                capStrictEndingContinuationGroupToTarget(context, sourceSku, skuResults, shifts);
                continue;
            }
            if (shouldReduceContinuationByWorkDate(sourceSku, skuResults, shifts)) {
                reduceContinuationMachinesByWorkDate(context, sourceSku, skuResults, shifts);
                capStrictEndingContinuationGroupToTarget(context, sourceSku, skuResults, shifts);
                continue;
            }
            // 非收尾多机台续作不降模，跳过降模流程保留初始排程结果（8班次全满）
            if (!hasEndingResult(skuResults)
                    && !ProductionQuantityPolicy.from(sourceSku, false).isStrictUpperLimit()
                    && skuResults.size() > 1) {
                log.info("续作多机台非收尾不降模, materialCode: {}, 机台: {}, 原因: 非收尾场景保留全部在机机台全产能排产",
                        sourceSku.getMaterialCode(), joinMachineCodes(skuResults));
                continue;
            }

            int targetQty = resolveContinuationDailyDemand(context, sourceSku, skuResults, shifts);
            int totalPlanQty = skuResults.stream().mapToInt(ShiftFieldUtil::resolveScheduledQty).sum();
            Map<LhScheduleResult, Integer> machineDailyCapacityMap =
                    calculateMachineDailyCapacityMap(context, skuResults, shifts);
            int currentMaxDailyCapacity = machineDailyCapacityMap.values().stream().mapToInt(Integer::intValue).sum();
            log.info("续作多机台降模判断, materialCode: {}, dayN保障量: {}, 当前在机最大日产能: {}, 当前排产量: {}",
                    sourceSku.getMaterialCode(), targetQty, currentMaxDailyCapacity, totalPlanQty);
            if (targetQty <= 0) {
                allocateContinuationQtyForKeptMachines(context, sourceSku, skuResults,
                        new ArrayList<LhScheduleResult>(0), machineDailyCapacityMap, targetQty, shifts);
                capStrictEndingContinuationGroupToTarget(context, sourceSku, skuResults, shifts);
                continue;
            }
            if (hasEndingResult(skuResults) && totalPlanQty <= targetQty) {
                log.info("续作多机台收尾无需降模, materialCode: {}, 原因: 当前尾量未超过收尾目标，交由同SKU收尾错峰判断",
                        sourceSku.getMaterialCode());
                continue;
            }

            List<LhScheduleResult> keptResults = selectMachinesToKeepForContinuation(
                    context, skuResults, machineDailyCapacityMap, targetQty);
            boolean needReduceMachine = keptResults.size() < skuResults.size()
                    && currentMaxDailyCapacity > targetQty;
            if (!needReduceMachine && totalPlanQty <= targetQty) {
                log.info("续作多机台无需降模, materialCode: {}, 原因: 当前产能或排产量未超过dayN保障量",
                        sourceSku.getMaterialCode());
                continue;
            }
            allocateContinuationQtyForKeptMachines(context, sourceSku, skuResults,
                    keptResults, machineDailyCapacityMap, targetQty, shifts);
            capStrictEndingContinuationGroupToTarget(context, sourceSku, skuResults, shifts);
        }
        capEndingFirstDayOnlyContinuationGroups(context, shifts);
        // 降模、补满等后置处理可能再次改变中班计划量，最终扣账前统一按续作日标准公式收敛。
        applyDailyStandardPlanQtyToContinuousResults(context, shifts);
        // 日标准产量公式可能把收尾残班向上补足，扣账前必须复用严格收尾目标再次收口。
        capStrictEndingContinuationGroupsToTarget(
                context, sourceSkuMap, skuResultMap, skuOrder, shifts);
        // 日额度账本必须在最终结果收口后再同步，并以公式修正后的结果驱动零计划与机台状态。
        // 降模、同 SKU 尾量错峰和多机台分摊都会改变最终班次量，不能提前扣账。
        syncContinuousDailyPlanQuota(context, shifts);
        appendContinuousCompensationSkuList(context);
        // S4.4 收口：零计划续作结果语义统一，并按最终结果同步机台状态。
        finalizeZeroPlanContinuousResults(context);
        adjustContinuousSameSkuMultiMachineEndingStagger(context, shifts);
        // 同SKU尾量归集会在机台间重新分配最后班次，归集后必须再次恢复每台续作机台的日标准产量公式结果。
        applyDailyStandardPlanQtyToContinuousResults(context, shifts);
        capStrictEndingContinuationGroupsToTarget(
                context, sourceSkuMap, skuResultMap, skuOrder, shifts);
        finalizeZeroPlanContinuousResults(context);
        removeCoveredZeroPlanContinuousUnscheduledResults(context);
        // 降模或额度回裁会再次改变最终计划量，收口后再统一复核一次收尾标记，确保落库口径一致。
        refreshContinuousEndingFlagByResult(context);
        // 续作最终结果稳定后，统一回写SKU完整胎胚库存，避免同SKU多机台结果残留二次分摊口径。
        retainMultiMachineEmbryoStock(context);
        syncMachineStateAfterContinuousAdjust(context);
        // 续作阶段全部处理完成后，再按剩余新增待排SKU统一收口结构视图，供S4.5排序使用。
        context.rebuildStructureSkuMapFromPending(context.getNewSpecSkuList());
    }

    @Override
    public void scheduleNewSpecs(LhScheduleContext context,
                                 IMachineMatchStrategy machineMatch,
                                 IMouldChangeBalanceStrategy mouldChangeBalance,
                                 IFirstInspectionBalanceStrategy inspectionBalance,
                                 ICapacityCalculateStrategy capacityCalculate) {
        // 续作策略不处理新增规格排产，空实现
    }

    /**
     * 判断当前续作多机台组是否应按业务日逐日执行降模。
     *
     * @param sourceSku 来源SKU
     * @param skuResults 同SKU续作结果
     * @param shifts 排程窗口班次
     * @return true-按业务日降模
     */
    private boolean shouldReduceContinuationByWorkDate(SkuScheduleDTO sourceSku,
                                                       List<LhScheduleResult> skuResults,
                                                       List<LhShiftConfigVO> shifts) {
        if (sourceSku == null
                || CollectionUtils.isEmpty(sourceSku.getDailyPlanQuotaMap())
                || CollectionUtils.isEmpty(shifts)) {
            return false;
        }
        // 多机台续作统一进入按天最小机台数模拟；日计划恒定时也要释放超过 dayN 需求的冗余机台。
        Map<LocalDate, List<LhShiftConfigVO>> shiftMapByDate = groupShiftsByWorkDate(shifts);
        if (hasEndingResult(skuResults) && hasEndingFirstDayPlanOnly(sourceSku, shiftMapByDate)) {
            log.info("续作收尾仅首日有计划，按业务日降模, materialCode: {}", sourceSku.getMaterialCode());
            return true;
        }
        if (sourceSku.getDailyPlanQuotaMap().size() <= 1) {
            return false;
        }
        for (LocalDate productionDate : shiftMapByDate.keySet()) {
            if (hasPositiveDayPlanDropAroundDate(sourceSku, shiftMapByDate, productionDate)) {
                return true;
            }
        }
        boolean hasPositiveDayPlan = shiftMapByDate.keySet().stream()
                .anyMatch(date -> resolveContinuationDayPlanQtyByDate(sourceSku, date) > 0);
        if (hasPositiveDayPlan && skuResults.size() > 1) {
            log.info("续作多机台日计划恒定，进入最小机台数降模模拟, materialCode: {}, 机台: {}",
                    sourceSku.getMaterialCode(), joinMachineCodes(skuResults));
            return true;
        }
        return false;
    }

    /**
     * 收尾SKU仅首日存在日计划时，按首日目标量最终收口多机台续作结果。
     * <p>真实月计划中 T 日有量、后续日无量时，S4.4 需要在 S4.5 新增前释放多余续作机台，
     * 否则后续换模 SKU 会因为机台仍被续作占用而换到其它机台。</p>
     *
     * @param context 排程上下文
     * @param shifts 排程窗口班次
     */
    private void capEndingFirstDayOnlyContinuationGroups(LhScheduleContext context, List<LhShiftConfigVO> shifts) {
        if (context == null || CollectionUtils.isEmpty(context.getScheduleResultList())
                || CollectionUtils.isEmpty(shifts)) {
            return;
        }
        Map<String, List<LhScheduleResult>> groupResultMap = new LinkedHashMap<String, List<LhScheduleResult>>(8);
        Map<String, SkuScheduleDTO> sourceSkuMap = new LinkedHashMap<String, SkuScheduleDTO>(8);
        for (LhScheduleResult result : context.getScheduleResultList()) {
            if (!isPureContinuousResult(result) || !"1".equals(result.getIsEnd())) {
                continue;
            }
            SkuScheduleDTO sourceSku = resolveResultSourceSku(context, result);
            if (sourceSku == null || StringUtils.isEmpty(sourceSku.getMaterialCode())) {
                continue;
            }
            String groupKey = sourceSku.getMaterialCode() + "#ENDING_FIRST_DAY_ONLY";
            groupResultMap.computeIfAbsent(groupKey, key -> new ArrayList<LhScheduleResult>(4)).add(result);
            sourceSkuMap.putIfAbsent(groupKey, sourceSku);
        }
        for (Map.Entry<String, List<LhScheduleResult>> entry : groupResultMap.entrySet()) {
            List<LhScheduleResult> results = entry.getValue();
            if (results.size() <= 1) {
                continue;
            }
            SkuScheduleDTO sourceSku = sourceSkuMap.get(entry.getKey());
            capEndingFirstDayOnlyContinuationGroup(context, sourceSku, results, shifts);
        }
    }

    /**
     * 对单个收尾首日计划分组执行收口。
     *
     * @param context 排程上下文
     * @param sourceSku 来源SKU
     * @param results 同物料续作结果
     * @param shifts 排程窗口班次
     * @return true-已执行收口
     */
    private boolean capEndingFirstDayOnlyContinuationGroup(LhScheduleContext context,
                                                           SkuScheduleDTO sourceSku,
                                                           List<LhScheduleResult> results,
                                                           List<LhShiftConfigVO> shifts) {
        if (context == null || sourceSku == null || CollectionUtils.isEmpty(results)
                || CollectionUtils.isEmpty(shifts)) {
            return false;
        }
        int firstDayPlanQty = resolveEndingFirstDayOnlyPlanQty(context, sourceSku, shifts);
        if (firstDayPlanQty <= 0) {
            return false;
        }
        if (shouldUseTargetQtyForContinuationReduction(sourceSku)) {
            log.info("续作收尾首日计划收口跳过, materialCode: {}, firstDayPlanQty: {}, 目标量: {}, "
                            + "原因: 清尾目标量大于窗口日计划量",
                    sourceSku.getMaterialCode(), firstDayPlanQty, sourceSku.resolveTargetScheduleQty());
            return false;
        }
        LhScheduleResult protectedResult = selectProtectedFirstShiftEndingResult(context, results, shifts);
        int protectedQty = protectedResult == null ? 0 : ShiftFieldUtil.resolveScheduledQty(protectedResult);
        int remainingPlanQty = Math.max(0, firstDayPlanQty - protectedQty);
        List<LhScheduleResult> allocatableResults = new ArrayList<LhScheduleResult>(results.size());
        for (LhScheduleResult result : results) {
            if (result != protectedResult) {
                allocatableResults.add(result);
            }
        }
        Map<LhScheduleResult, Integer> capacityMap = calculateMachineDailyCapacityMap(context, allocatableResults, shifts);
        List<LhScheduleResult> keptResults = remainingPlanQty > 0
                ? selectMachinesToKeepForContinuation(context, allocatableResults, capacityMap, remainingPlanQty)
                : new ArrayList<LhScheduleResult>(0);
        if (keptResults.size() > 1) {
            return false;
        }
        allocateContinuationQtyForKeptMachines(context, sourceSku, allocatableResults, keptResults,
                capacityMap, remainingPlanQty, shifts);
        List<LhScheduleResult> finalKeptResults = new ArrayList<LhScheduleResult>(keptResults.size() + 1);
        if (protectedResult != null && protectedQty > 0) {
            finalKeptResults.add(protectedResult);
        }
        finalKeptResults.addAll(keptResults);
        log.info("续作收尾首日计划最终收口, materialCode: {}, firstDayPlanQty: {}, 保护机台: {}, "
                        + "保护量: {}, 剩余分配量: {}, 原始机台: {}, 保留机台: {}",
                sourceSku.getMaterialCode(), firstDayPlanQty,
                protectedResult == null ? "" : protectedResult.getLhMachineCode(), protectedQty,
                remainingPlanQty, joinMachineCodes(results), joinMachineCodes(finalKeptResults));
        return true;
    }

    /**
     * 选择已在首班生产尾量、需要优先保护并释放给后续换模的结果。
     *
     * @param context 排程上下文
     * @param results 同物料续作结果
     * @param shifts 排程窗口班次
     * @return 保护结果，未命中返回null
     */
    private LhScheduleResult selectProtectedFirstShiftEndingResult(LhScheduleContext context,
                                                                   List<LhScheduleResult> results,
                                                                   List<LhShiftConfigVO> shifts) {
        if (context == null || CollectionUtils.isEmpty(results) || CollectionUtils.isEmpty(shifts)) {
            return null;
        }
        LhShiftConfigVO firstShift = shifts.get(0);
        LhScheduleResult selected = null;
        for (LhScheduleResult result : results) {
            if (result == null || resolveLastPlannedShiftIndex(result) != firstShift.getShiftIndex()) {
                continue;
            }
            int scheduledQty = ShiftFieldUtil.resolveScheduledQty(result);
            int firstShiftCapacity = calculateResultShiftCapacity(context, result, firstShift);
            if (scheduledQty <= 0 || firstShiftCapacity <= 0 || scheduledQty >= firstShiftCapacity) {
                continue;
            }
            if (selected == null || StringUtils.compare(result.getLhMachineCode(), selected.getLhMachineCode()) < 0) {
                selected = result;
            }
        }
        return selected;
    }

    /**
     * 解析收尾SKU是否仅首日存在日计划，命中时返回首日计划量。
     *
     * @param context 排程上下文
     * @param sourceSku 来源SKU
     * @param shifts 排程窗口班次
     * @return 首日计划量，未命中返回0
     */
    private int resolveEndingFirstDayOnlyPlanQty(LhScheduleContext context,
                                                 SkuScheduleDTO sourceSku,
                                                 List<LhShiftConfigVO> shifts) {
        Map<LocalDate, List<LhShiftConfigVO>> shiftMapByDate = groupShiftsByWorkDate(shifts);
        int planQty = resolveEndingFirstDayOnlyPlanQtyByMonthPlan(context, sourceSku, shiftMapByDate);
        if (planQty > 0) {
            return planQty;
        }
        return resolveEndingFirstDayOnlyPlanQtyByQuota(sourceSku, shiftMapByDate);
    }

    /**
     * 从运行态日计划账本解析首日计划量。
     *
     * @param sourceSku 来源SKU
     * @param shiftMapByDate 窗口业务日
     * @return 首日计划量，未命中返回0
     */
    private int resolveEndingFirstDayOnlyPlanQtyByQuota(SkuScheduleDTO sourceSku,
                                                        Map<LocalDate, List<LhShiftConfigVO>> shiftMapByDate) {
        if (sourceSku == null || CollectionUtils.isEmpty(sourceSku.getDailyPlanQuotaMap())
                || CollectionUtils.isEmpty(shiftMapByDate)) {
            return 0;
        }
        int firstDayPlanQty = 0;
        boolean first = true;
        for (LocalDate productionDate : shiftMapByDate.keySet()) {
            SkuDailyPlanQuotaDTO quota = sourceSku.getDailyPlanQuotaMap().get(productionDate);
            int dayPlanQty = quota == null ? 0 : Math.max(0, quota.getDayPlanQty());
            if (first) {
                firstDayPlanQty = dayPlanQty;
                first = false;
                continue;
            }
            if (dayPlanQty > 0) {
                return 0;
            }
        }
        return firstDayPlanQty;
    }

    /**
     * 从月计划解析首日计划量。
     *
     * @param context 排程上下文
     * @param sourceSku 来源SKU
     * @param shiftMapByDate 窗口业务日
     * @return 首日计划量，未命中返回0
     */
    private int resolveEndingFirstDayOnlyPlanQtyByMonthPlan(LhScheduleContext context,
                                                            SkuScheduleDTO sourceSku,
                                                            Map<LocalDate, List<LhShiftConfigVO>> shiftMapByDate) {
        if (context == null || sourceSku == null || CollectionUtils.isEmpty(context.getMonthPlanList())
                || CollectionUtils.isEmpty(shiftMapByDate)) {
            return 0;
        }
        FactoryMonthPlanProductionFinalResult plan = findMonthPlanByMaterial(context, sourceSku.getMaterialCode());
        if (plan == null) {
            return 0;
        }
        int firstDayPlanQty = 0;
        boolean first = true;
        for (LocalDate productionDate : shiftMapByDate.keySet()) {
            int dayPlanQty = Math.max(0, MonthPlanDayQtyUtil.resolveDayQty(plan, productionDate.getDayOfMonth()));
            if (first) {
                firstDayPlanQty = dayPlanQty;
                first = false;
                continue;
            }
            if (dayPlanQty > 0) {
                return 0;
            }
        }
        return firstDayPlanQty;
    }

    /**
     * 按物料编码查找月计划。
     *
     * @param context 排程上下文
     * @param materialCode 物料编码
     * @return 月计划，未找到返回null
     */
    private FactoryMonthPlanProductionFinalResult findMonthPlanByMaterial(LhScheduleContext context, String materialCode) {
        if (context == null || StringUtils.isEmpty(materialCode)
                || CollectionUtils.isEmpty(context.getMonthPlanList())) {
            return null;
        }
        for (FactoryMonthPlanProductionFinalResult plan : context.getMonthPlanList()) {
            if (plan != null && StringUtils.equals(materialCode, plan.getMaterialCode())) {
                return plan;
            }
        }
        return null;
    }

    /**
     * 判断收尾续作是否仅首个业务日存在正日计划。
     * <p>收尾SKU首日一台机台即可覆盖余量时，后续0计划日也应触发按天降模，避免继续保留多台满班续作。</p>
     *
     * @param sourceSku 来源SKU
     * @param shiftMapByDate 业务日班次
     * @return true-仅首日有计划
     */
    private boolean hasEndingFirstDayPlanOnly(SkuScheduleDTO sourceSku,
                                              Map<LocalDate, List<LhShiftConfigVO>> shiftMapByDate) {
        if (sourceSku == null || CollectionUtils.isEmpty(shiftMapByDate)) {
            return false;
        }
        boolean firstDate = true;
        boolean firstDayHasPlan = false;
        for (LocalDate productionDate : shiftMapByDate.keySet()) {
            int dayPlanQty = resolveContinuationDayPlanQtyByDate(sourceSku, productionDate);
            if (firstDate) {
                firstDayHasPlan = dayPlanQty > 0;
                firstDate = false;
                continue;
            }
            if (dayPlanQty > 0) {
                return false;
            }
        }
        return firstDayHasPlan;
    }

    /**
     * 按业务日逐日执行续作多机台降模。
     *
     * @param context 排程上下文
     * @param sourceSku 来源SKU
     * @param skuResults 同SKU续作结果
     * @param shifts 全窗口班次
     */
    private void reduceContinuationMachinesByWorkDate(LhScheduleContext context,
                                                      SkuScheduleDTO sourceSku,
                                                      List<LhScheduleResult> skuResults,
                                                      List<LhShiftConfigVO> shifts) {
        Map<LocalDate, List<LhShiftConfigVO>> shiftMapByDate = groupShiftsByWorkDate(shifts);
        List<LhScheduleResult> activeResults = new ArrayList<LhScheduleResult>(skuResults);
        int remainingTargetQty = Math.max(0, sourceSku.resolveTargetScheduleQty());
        int shortageLookAheadDays = resolveContinuationShortageLookAheadDays(context);
        int rollingDiffQty = 0;
        boolean ending = hasEndingResult(skuResults);
        ProductionQuantityPolicy policy = ProductionQuantityPolicy.from(sourceSku, ending);
        LocalDate firstProductionDate = shiftMapByDate.keySet().iterator().next();
        for (Map.Entry<LocalDate, List<LhShiftConfigVO>> entry : shiftMapByDate.entrySet()) {
            if (CollectionUtils.isEmpty(activeResults)) {
                break;
            }
            LocalDate productionDate = entry.getKey();
            List<LhShiftConfigVO> dayShifts = entry.getValue();
            int dayPlanQty = resolveContinuationDayPlanQtyByDate(sourceSku, productionDate);
            int demandQty = ending
                    ? resolveContinuationDayDemandQtyByDate(sourceSku, productionDate)
                    : resolveContinuationReductionDemandQtyByDate(
                            sourceSku, productionDate, firstProductionDate);
            int todayRequiredQty = rollingDiffQty + demandQty;
            int effectiveDemandQty = policy.isStrictUpperLimit()
                    ? Math.min(Math.max(0, todayRequiredQty), remainingTargetQty)
                    : Math.max(0, todayRequiredQty);
            Map<LhScheduleResult, Integer> capacityMap =
                    calculateMachineDailyCapacityMapByDate(context, activeResults, dayShifts);
            int totalCapacity = capacityMap.values().stream().mapToInt(Integer::intValue).sum();
            int totalPlanQty = sumScheduledQtyByShifts(activeResults, dayShifts);
            List<LhScheduleResult> keptResults = selectMachinesToKeepForContinuationByLookAhead(
                    context, sourceSku, activeResults, shiftMapByDate, productionDate,
                    rollingDiffQty, remainingTargetQty, shortageLookAheadDays, policy);
            if (CollectionUtils.isEmpty(keptResults) && effectiveDemandQty > 0) {
                keptResults = selectMachinesToKeepForContinuation(
                        context, activeResults, capacityMap, effectiveDemandQty);
            }
            int keptTodayCapacity = sumCapacityForResults(capacityMap, keptResults);
            boolean recoverable = canContinuationMachinesMeetLookAhead(
                    context, sourceSku, keptResults, shiftMapByDate, productionDate,
                    rollingDiffQty, remainingTargetQty, shortageLookAheadDays, policy);
            log.info("续作多机台按天降模判断, materialCode: {}, 日期: {}, shortageLookAheadDays: {}, dayN计划量: {}, "
                            + "dayN剩余额度: {}, 前日排后差额: {}, 当日需求量: {}, 剩余窗口目标量: {}, 当日生效目标量: {}, 当前在机最大日产能: {}, "
                            + "保留机台当日产能: {}, 当前排产量: {}, 是否满足dayN欠产追补约束: {}",
                    sourceSku.getMaterialCode(), productionDate, shortageLookAheadDays, dayPlanQty,
                    demandQty, rollingDiffQty, todayRequiredQty, remainingTargetQty, effectiveDemandQty, totalCapacity,
                    keptTodayCapacity, totalPlanQty, recoverable);
            applyContinuationDayAllocation(context, sourceSku, activeResults, keptResults, capacityMap,
                    demandQty, effectiveDemandQty, remainingTargetQty, productionDate, dayShifts, shifts,
                    recoverable);
            int actualTodayQty = sumScheduledQtyByShifts(activeResults, dayShifts);
            rollingDiffQty = effectiveDemandQty - actualTodayQty;
            remainingTargetQty = Math.max(0, remainingTargetQty - sumScheduledQtyByShifts(activeResults, dayShifts));
            activeResults = new ArrayList<LhScheduleResult>(keptResults);
            log.info("续作多机台每日排后差额, materialCode: {}, 日期: {}, actualTodayQty: {}, rollingDiffQty: {}, nextActiveMachines: {}",
                    sourceSku.getMaterialCode(), productionDate, actualTodayQty, rollingDiffQty, joinMachineCodes(activeResults));
        }
    }

    /**
     * 解析非收尾续作降模使用的当日需求量。
     * <p>降模减机台只按当日日计划判断在机数量；首日先扣除排程日晚班已完成量，
     * 历史欠产继续交由既有欠产阈值与续作补偿链路处理，不能阻止冗余续作机台释放。</p>
     *
     * @param sourceSku 来源SKU
     * @param productionDate 当前业务日
     * @param firstProductionDate 窗口首个业务日
     * @return 降模使用的当日需求量
     */
    private int resolveContinuationReductionDemandQtyByDate(SkuScheduleDTO sourceSku,
                                                              LocalDate productionDate,
                                                              LocalDate firstProductionDate) {
        int dayPlanQty = resolveContinuationDayPlanQtyByDate(sourceSku, productionDate);
        int remainingQty = resolveContinuationDayDemandQtyByDate(sourceSku, productionDate);
        int dailyDemandQty = Math.min(dayPlanQty, remainingQty);
        if (productionDate != null && productionDate.equals(firstProductionDate)) {
            dailyDemandQty = Math.min(dailyDemandQty,
                    Math.max(0, dayPlanQty - Math.max(0, sourceSku.getScheduleDayFinishQty())));
        }
        log.debug("续作多机台降模当日需求解析, materialCode: {}, 日期: {}, dayN: {}, 账本剩余: {}, "
                        + "排程日晚班完成量: {}, 降模需求量: {}",
                sourceSku.getMaterialCode(), productionDate, dayPlanQty, remainingQty,
                sourceSku.getScheduleDayFinishQty(), dailyDemandQty);
        return Math.max(0, dailyDemandQty);
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 构建续作多机台分组键。
     * <p>同物料且共享同一份日计划账本的续作副本，才视为同一个业务SKU多机台集合。</p>
     *
     * @param sourceSku 来源SKU
     * @return 分组键
     */
    private String buildContinuationGroupKey(SkuScheduleDTO sourceSku) {
        if (sourceSku == null) {
            return "";
        }
        Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap = sourceSku.getDailyPlanQuotaMap();
        String quotaIdentity = quotaMap != null
                ? String.valueOf(System.identityHashCode(quotaMap))
                : "SKU-" + System.identityHashCode(sourceSku);
        return sourceSku.getMaterialCode() + "#" + quotaIdentity;
    }

    /**
     * 构建续作降模分组键。
     * <p>普通续作仍按共享日计划账本分组；收尾严格控量需要按物料统一收口，
     * 避免单控左右侧因运行态对象不同被拆成两组后各自满排。</p>
     *
     * @param result 续作结果
     * @param sourceSku 来源SKU
     * @return 降模分组键
     */
    private String buildReduceMouldGroupKey(LhScheduleResult result, SkuScheduleDTO sourceSku) {
        if (sourceSku == null) {
            return "";
        }
        boolean ending = result != null && "1".equals(result.getIsEnd());
        ProductionQuantityPolicy policy = ProductionQuantityPolicy.from(sourceSku, ending);
        if (ending && policy.isStrictUpperLimit()) {
            return sourceSku.getMaterialCode() + "#STRICT_ENDING";
        }
        return buildContinuationGroupKey(sourceSku);
    }

    /**
     * 多机台续作收尾目标量决策。
     * <p>同一收尾 SKU 同时在多台机台续作时，必须先统一按中心收尾口径
     * {@code MAX(硫化余量, 胎胚库存)} 收口，再进入降模释放机台。</p>
     *
     * @param context 排程上下文
     * @param sourceSku 来源SKU
     * @param skuResults 同SKU续作结果
     */
    private void applyMultiMachineEndingTargetRule(LhScheduleContext context,
                                                   SkuScheduleDTO sourceSku,
                                                   List<LhScheduleResult> skuResults) {
        if (context == null || sourceSku == null || CollectionUtils.isEmpty(skuResults)
                || !hasEndingResult(skuResults)) {
            return;
        }
        ProductionQuantityPolicy policy = ProductionQuantityPolicy.from(sourceSku, true);
        if (!policy.isStrictUpperLimit()) {
            return;
        }
        int originalTargetQty = sourceSku.resolveTargetScheduleQty();
        int endingTargetQty = resolveMultiMachineEndingTargetQty(context, sourceSku, skuResults);
        if (originalTargetQty != endingTargetQty) {
            endingTargetQty = getTargetScheduleQtyResolver().upsizeEndingTargetQty(context, sourceSku);
        }
        log.info("续作多机台收尾目标量决策, materialCode: {}, 机台列表: {}, 原目标量: {}, "
                        + "收尾目标量: {}, surplusQty: {}, embryoStock: {}, rule: 多机台收尾MAX(余量,胎胚库存)",
                sourceSku.getMaterialCode(), joinMachineCodes(skuResults), originalTargetQty,
                endingTargetQty, Math.max(0, sourceSku.getSurplusQty()),
                Math.max(0, sourceSku.getEmbryoStock()));
    }

    /**
     * 解析多机台续作收尾目标量。
     *
     * @param context 排程上下文
     * @param sourceSku 来源SKU
     * @param skuResults 同SKU续作结果
     * @return 收尾目标量
     */
    private int resolveMultiMachineEndingTargetQty(LhScheduleContext context,
                                                   SkuScheduleDTO sourceSku,
                                                   List<LhScheduleResult> skuResults) {
        int endingDemandQty = !CollectionUtils.isEmpty(skuResults)
                ? resolveEndingDemandQty(context, skuResults.get(0))
                : Math.max(Math.max(0, sourceSku.getSurplusQty()), Math.max(0, sourceSku.getEmbryoStock()));
        return ShiftCapacityResolverUtil.roundUpQtyToMouldMultiple(endingDemandQty, sourceSku.getMouldQty());
    }

    /**
     * 统计续作业务分组对应的机台数。
     *
     * @param continuousSkuList 续作SKU列表
     * @return 分组机台数
     */
    private Map<String, Integer> buildContinuationGroupMachineCountMap(List<SkuScheduleDTO> continuousSkuList) {
        Map<String, Integer> groupMachineCountMap = new LinkedHashMap<String, Integer>(16);
        if (CollectionUtils.isEmpty(continuousSkuList)) {
            return groupMachineCountMap;
        }
        for (SkuScheduleDTO sku : continuousSkuList) {
            if (sku == null) {
                continue;
            }
            String groupKey = buildContinuationGroupKey(sku);
            groupMachineCountMap.merge(groupKey, 1, Integer::sum);
        }
        return groupMachineCountMap;
    }

    /**
     * 判断续作SKU在当前排程窗口内是否完全没有日计划量。
     *
     * @param sku 续作SKU
     * @return true-当前窗口无日计划量
     */
    private boolean isContinuousWindowNoDailyPlan(SkuScheduleDTO sku) {
        if (sku == null || CollectionUtils.isEmpty(sku.getDailyPlanQuotaMap())) {
            return false;
        }
        for (SkuDailyPlanQuotaDTO quota : sku.getDailyPlanQuotaMap().values()) {
            if (quota != null && quota.getDayPlanQty() > 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * 判断窗口无计划续作SKU是否应直接释放机台。
     *
     * @param sku 续作SKU
     * @param shortageQuotaPlan 欠产账本准备结果
     * @return true-无本月历史欠产且不应在当前窗口续作，释放机台；false-继续排历史欠产或清尾余量
     */
    private boolean shouldReleaseWindowNoPlanContinuousSku(
            SkuScheduleDTO sku, DailyMachineShortageQuotaPlan shortageQuotaPlan) {
        return Objects.nonNull(sku)
                && Objects.nonNull(shortageQuotaPlan)
                && shortageQuotaPlan.isNoWindowPlan()
                && Math.max(0, shortageQuotaPlan.getHistoryShortageQty()) <= 0
                && (Math.max(0, sku.getSurplusQty()) <= 0
                || Math.max(0, shortageQuotaPlan.getFutureMonthPlanQtyAfterWindow()) > 0);
    }

    /**
     * 判断窗口无计划续作SKU是否仍需按硫化余量继续排产。
     *
     * @param sku 续作SKU
     * @param shortageQuotaPlan 欠产账本准备结果
     * @return true-仍有硫化余量，继续续作并按收尾释放机台；false-沿用原规则
     */
    private boolean shouldFinishWindowNoPlanContinuousSurplus(
            SkuScheduleDTO sku, DailyMachineShortageQuotaPlan shortageQuotaPlan) {
        return Objects.nonNull(sku)
                && Objects.nonNull(shortageQuotaPlan)
                && shortageQuotaPlan.isNoWindowPlan()
                && Math.max(0, shortageQuotaPlan.getHistoryShortageQty()) <= 0
                && Math.max(0, shortageQuotaPlan.getFutureMonthPlanQtyAfterWindow()) <= 0
                && Math.max(0, sku.getSurplusQty()) > 0;
    }

    /**
     * 将窗口无计划但仍有余量的续作SKU同步为严格收尾目标。
     *
     * @param context 排程上下文
     * @param sku 续作SKU
     * @param shortageQuotaPlan 欠产账本准备结果
     */
    private void applyContinuousWindowNoPlanSurplusStrictTarget(
            LhScheduleContext context, SkuScheduleDTO sku,
            DailyMachineShortageQuotaPlan shortageQuotaPlan) {
        sku.setSkuTag(SkuTagEnum.ENDING.getCode());
        if (sku.getEndingDaysRemaining() <= 0) {
            sku.setEndingDaysRemaining(1);
        }
        // 复用统一收尾目标量和账本同步，避免窗口dayN为0时结果被回裁。
        int strictTargetQty = getTargetScheduleQtyResolver().upsizeEndingTargetQty(context, sku);
        log.info("续作窗口无日计划但仍有硫化余量，继续按余量严格排产, materialCode: {}, "
                        + "machineCode: {}, surplusQty: {}, futureMonthPlanQtyAfterWindow: {}, "
                        + "historyShortageQty: {}, strictTargetQty: {}, result: 继续续作并在排完后释放机台",
                sku.getMaterialCode(), sku.getContinuousMachineCode(), sku.getSurplusQty(),
                sku.getFutureMonthPlanQtyAfterWindow(), shortageQuotaPlan.getHistoryShortageQty(), strictTargetQty);
    }

    /**
     * 判断续作收尾小余量是否允许本次不排产。
     *
     * <p>这是“收尾小余量 + 前日 T+1 夜班未排满”的特殊不排产规则；
     * 非收尾 SKU、余量大于参数值或前日 T+1 夜班已排满时继续沿用原收尾排产规则。</p>
     *
     * @param context 排程上下文
     * @param sku 续作SKU
     * @param isEnding 是否收尾
     * @return true-本次不排产并释放续作机台；false-继续按原规则排产
     */
    private boolean shouldSkipSmallEndingSurplusContinuous(LhScheduleContext context,
                                                           SkuScheduleDTO sku,
                                                           boolean isEnding) {
        if (!isEnding || sku == null) {
            return false;
        }
        int surplusQty = Math.max(0, sku.getSurplusQty());
        int toleranceQty = SmallEndingSurplusSkipRule.resolveToleranceQty(context);
        int previousNightPlanQty = SmallEndingSurplusSkipRule.resolveTargetPreviousT1NightPlanQty(
                context, sku.getMaterialCode());
        boolean previousNightFull = SmallEndingSurplusSkipRule.isTargetPreviousT1NightFull(context, sku);
        boolean skip = SmallEndingSurplusSkipRule.shouldSkip(context, sku, isEnding);
        log.info("续作收尾小余量业务目标日前一日夜班判断, materialCode: {}, machineCode: {}, isEnding: {}, surplusQty: {}, "
                        + "toleranceQty: {}, targetPreviousT1NightPlanQty: {}, shiftCapacity: {}, targetPreviousT1NightFull: {}, "
                        + "skipSchedule: {}",
                sku.getMaterialCode(), sku.getContinuousMachineCode(), isEnding, surplusQty, toleranceQty,
                previousNightPlanQty, sku.getShiftCapacity(), previousNightFull, skip);
        return skip;
    }

    /**
     * 获取收尾小余量允许欠产偏差值。
     *
     * @param context 排程上下文
     * @return 允许不排产的最大收尾余量
     */
    private int resolveContinuousEndingSurplusToleranceQty(LhScheduleContext context) {
        return SmallEndingSurplusSkipRule.resolveToleranceQty(context);
    }

    /**
     * 写入续作收尾小余量未排结果。
     *
     * @param context 排程上下文
     * @param sku 续作SKU
     */
    private void appendSmallEndingSurplusUnscheduledResult(LhScheduleContext context, SkuScheduleDTO sku) {
        if (context == null || sku == null || StringUtils.isEmpty(sku.getMaterialCode())) {
            return;
        }
        LhUnscheduledResult existing = findUnscheduledResultByMaterial(context, sku.getMaterialCode());
        if (existing != null) {
            existing.setUnscheduledReason(SMALL_ENDING_SURPLUS_UNSCHEDULED_REASON);
            existing.setUnscheduledQty(Math.max(0, sku.getSurplusQty()));
            return;
        }
        LhUnscheduledResult unscheduled = new LhUnscheduledResult();
        unscheduled.setFactoryCode(context.getFactoryCode());
        unscheduled.setBatchNo(context.getBatchNo());
        unscheduled.setScheduleDate(context.getScheduleTargetDate());
        unscheduled.setMaterialCode(sku.getMaterialCode());
        unscheduled.setMaterialDesc(sku.getMaterialDesc());
        unscheduled.setStructureName(sku.getStructureName());
        unscheduled.setMainMaterialDesc(sku.getMainMaterialDesc());
        unscheduled.setSpecCode(sku.getSpecCode());
        unscheduled.setEmbryoCode(sku.getEmbryoCode());
        unscheduled.setMouldQty(sku.getMouldQty());
        unscheduled.setUnscheduledQty(Math.max(0, sku.getSurplusQty()));
        unscheduled.setUnscheduledReason(SMALL_ENDING_SURPLUS_UNSCHEDULED_REASON);
        unscheduled.setDataSource(AUTO_DATA_SOURCE);
        unscheduled.setIsDelete(0);
        context.getUnscheduledResultList().add(unscheduled);
    }

    /**
     * 输出续作收尾小余量不排产的应用日志和过程日志。
     *
     * @param context 排程上下文
     * @param sku 续作SKU
     * @param machineCode 释放机台编码
     * @param toleranceQty 允许欠产偏差值
     */
    private void traceSmallEndingSurplusSkip(LhScheduleContext context,
                                             SkuScheduleDTO sku,
                                             String machineCode,
                                             int toleranceQty) {
        if (sku == null) {
            return;
        }
        StringBuilder detail = new StringBuilder(160);
        detail.append("续作收尾小余量且前日T+1夜班未排满不排产, materialCode: ")
                .append(sku.getMaterialCode())
                .append(", machineCode: ")
                .append(machineCode)
                .append(", surplusQty: ")
                .append(Math.max(0, sku.getSurplusQty()))
                .append(", toleranceQty: ")
                .append(toleranceQty)
                .append(", targetPreviousT1NightPlanQty: ")
                .append(SmallEndingSurplusSkipRule.resolveTargetPreviousT1NightPlanQty(context, sku.getMaterialCode()))
                .append(", shiftCapacity: ")
                .append(sku.getShiftCapacity())
                .append(", unscheduledReason: ")
                .append(SMALL_ENDING_SURPLUS_UNSCHEDULED_REASON)
                .append(", flow: 释放机台优先进入换活字块，不满足后进入S4.5换模新增");
        log.info(detail.toString());
        PriorityTraceLogHelper.appendProcessLog(context, "续作收尾小余量不排产", detail.toString());
    }

    /**
     * 写入当前窗口无日计划量的续作未排结果。
     *
     * @param context 排程上下文
     * @param sku 续作SKU
     */
    private void appendWindowNoPlanContinuousUnscheduledResult(LhScheduleContext context, SkuScheduleDTO sku) {
        if (context == null || sku == null || StringUtils.isEmpty(sku.getMaterialCode())) {
            return;
        }
        LhUnscheduledResult existing = findUnscheduledResultByMaterial(context, sku.getMaterialCode());
        if (existing != null) {
            existing.setUnscheduledReason(WINDOW_NO_PLAN_UNSCHEDULED_REASON);
            return;
        }
        LhUnscheduledResult unscheduled = new LhUnscheduledResult();
        unscheduled.setFactoryCode(context.getFactoryCode());
        unscheduled.setBatchNo(context.getBatchNo());
        unscheduled.setScheduleDate(context.getScheduleTargetDate());
        unscheduled.setMaterialCode(sku.getMaterialCode());
        unscheduled.setMaterialDesc(sku.getMaterialDesc());
        unscheduled.setStructureName(sku.getStructureName());
        unscheduled.setMainMaterialDesc(sku.getMainMaterialDesc());
        unscheduled.setSpecCode(sku.getSpecCode());
        unscheduled.setEmbryoCode(sku.getEmbryoCode());
        unscheduled.setMouldQty(sku.getMouldQty());
        unscheduled.setUnscheduledQty(0);
        unscheduled.setUnscheduledReason(WINDOW_NO_PLAN_UNSCHEDULED_REASON);
        unscheduled.setDataSource(AUTO_DATA_SOURCE);
        unscheduled.setIsDelete(0);
        context.getUnscheduledResultList().add(unscheduled);
    }

    /**
     * 解析续作阶段结果所属的业务分组键。
     * <p>续作共享账本分组必须依赖来源SKU映射，缺失时直接报错，避免静默按物料编码串组。</p>
     *
     * @param context 排程上下文
     * @param result 续作阶段结果
     * @return 业务分组键
     */
    private String resolveContinuationGroupKey(LhScheduleContext context, LhScheduleResult result) {
        if (result == null) {
            return "";
        }
        return buildContinuationGroupKey(requireContinuousPhaseSourceSku(context, result));
    }

    /**
     * 拼接续作结果机台编码。
     *
     * @param results 续作结果
     * @return 机台编码列表
     */
    private String joinMachineCodes(List<LhScheduleResult> results) {
        if (CollectionUtils.isEmpty(results)) {
            return "";
        }
        StringBuilder builder = new StringBuilder(results.size() * 8);
        for (LhScheduleResult result : results) {
            if (result == null) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(",");
            }
            builder.append(result.getLhMachineCode());
        }
        return builder.toString();
    }

    /**
     * 格式化续作多机台明细，便于定位保留/下机原因。
     *
     * @param context 排程上下文
     * @param results 续作结果
     * @param capacityMap 机台日产能
     * @return 机台明细字符串
     */
    private String formatContinuationMachineDetails(LhScheduleContext context,
                                                    List<LhScheduleResult> results,
                                                    Map<LhScheduleResult, Integer> capacityMap) {
        if (CollectionUtils.isEmpty(results)) {
            return "";
        }
        StringBuilder builder = new StringBuilder(results.size() * 32);
        for (LhScheduleResult result : results) {
            if (result == null) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(";");
            }
            builder.append(StringUtils.defaultString(result.getLhMachineCode()))
                    .append("(胶囊次数=")
                    .append(resolveCapsuleUsageCount(context, result))
                    .append(",日产能=")
                    .append(Math.max(0, capacityMap.getOrDefault(result, 0)))
                    .append(")");
        }
        return builder.toString();
    }

    /**
     * 按业务日聚合排程窗口班次。
     *
     * @param shifts 全窗口班次
     * @return 业务日到班次的映射
     */
    private Map<LocalDate, List<LhShiftConfigVO>> groupShiftsByWorkDate(List<LhShiftConfigVO> shifts) {
        Map<LocalDate, List<LhShiftConfigVO>> shiftMapByDate = new LinkedHashMap<LocalDate, List<LhShiftConfigVO>>(4);
        if (CollectionUtils.isEmpty(shifts)) {
            return shiftMapByDate;
        }
        for (LhShiftConfigVO shift : shifts) {
            if (shift == null || shift.getWorkDate() == null) {
                continue;
            }
            LocalDate workDate = shift.getWorkDate().toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
            shiftMapByDate.computeIfAbsent(workDate, key -> new ArrayList<LhShiftConfigVO>(4)).add(shift);
        }
        return shiftMapByDate;
    }

    /**
     * 解析续作多机台指定业务日的原始 dayN 计划量。
     *
     * @param sourceSku 来源SKU
     * @param productionDate 业务日
     * @return dayN计划量
     */
    private int resolveContinuationDayPlanQtyByDate(SkuScheduleDTO sourceSku, LocalDate productionDate) {
        if (sourceSku == null || productionDate == null || CollectionUtils.isEmpty(sourceSku.getDailyPlanQuotaMap())) {
            return 0;
        }
        SkuDailyPlanQuotaDTO quota = sourceSku.getDailyPlanQuotaMap().get(productionDate);
        if (quota == null) {
            return 0;
        }
        int dayPlanQty = Math.max(0, quota.getDayPlanQty());
        log.debug("续作多机台dayN计划量解析, materialCode: {}, 日期: {}, dayN: {}, 剩余额度: {}",
                sourceSku.getMaterialCode(), productionDate, dayPlanQty, quota.getRemainingQty());
        return dayPlanQty;
    }

    /**
     * 解析续作多机台指定业务日的实际剩余需求。
     *
     * @param sourceSku 来源SKU
     * @param productionDate 业务日
     * @return dayN剩余额度
     */
    private int resolveContinuationDayDemandQtyByDate(SkuScheduleDTO sourceSku, LocalDate productionDate) {
        if (sourceSku == null || productionDate == null || CollectionUtils.isEmpty(sourceSku.getDailyPlanQuotaMap())) {
            return 0;
        }
        SkuDailyPlanQuotaDTO quota = sourceSku.getDailyPlanQuotaMap().get(productionDate);
        if (quota == null) {
            return 0;
        }
        return Math.max(0, quota.getRemainingQty());
    }

    /**
     * 判断当前业务日前后是否存在正日计划下降。
     * <p>降模只服务于计划下降后的减机台；窗口尾部无计划的0量日期不作为降模触发依据。</p>
     *
     * @param sourceSku 来源SKU
     * @param shiftMapByDate 业务日班次
     * @param productionDate 当前业务日
     * @return true-存在正计划下降
     */
    private boolean hasPositiveDayPlanDropAroundDate(SkuScheduleDTO sourceSku,
                                                     Map<LocalDate, List<LhShiftConfigVO>> shiftMapByDate,
                                                     LocalDate productionDate) {
        if (sourceSku == null || CollectionUtils.isEmpty(shiftMapByDate) || productionDate == null) {
            return false;
        }
        int currentDayPlanQty = resolveContinuationDayPlanQtyByDate(sourceSku, productionDate);
        if (currentDayPlanQty <= 0) {
            return false;
        }
        int previousPositiveDayPlanQty = 0;
        for (LocalDate date : shiftMapByDate.keySet()) {
            if (date.isBefore(productionDate)) {
                int dayPlanQty = resolveContinuationDayPlanQtyByDate(sourceSku, date);
                if (dayPlanQty > 0) {
                    previousPositiveDayPlanQty = dayPlanQty;
                }
                continue;
            }
            if (date.equals(productionDate) && previousPositiveDayPlanQty > currentDayPlanQty) {
                return true;
            }
            if (!date.isAfter(productionDate)) {
                continue;
            }
            int futureDayPlanQty = resolveContinuationDayPlanQtyByDate(sourceSku, date);
            if (futureDayPlanQty > 0 && futureDayPlanQty < currentDayPlanQty) {
                return true;
            }
        }
        return false;
    }

    /**
     * 解析续作降模欠产追补观察天数。
     *
     * @param context 排程上下文
     * @return 欠产追补观察天数
     */
    private int resolveContinuationShortageLookAheadDays(LhScheduleContext context) {
        if (context == null || context.getScheduleConfig() == null) {
            return LhScheduleConstant.CONTINUOUS_SHORTAGE_LOOK_AHEAD_DAYS;
        }
        return context.getScheduleConfig().getContinuousShortageLookAheadDays();
    }

    /**
     * 解析续作降模首日初始欠产。
     * <p>日计划滚动账本会把历史允许追补量并入首日 remainingQty，这里只取 remaining-dayN 的差额，避免重复计入。</p>
     *
     * @param sourceSku 来源SKU
     * @param shiftMapByDate 窗口业务日班次
     * @return 初始欠产量
     */
    private int resolveContinuationInitialCarryShortage(SkuScheduleDTO sourceSku,
                                                        Map<LocalDate, List<LhShiftConfigVO>> shiftMapByDate) {
        if (sourceSku == null || CollectionUtils.isEmpty(sourceSku.getDailyPlanQuotaMap())
                || CollectionUtils.isEmpty(shiftMapByDate)) {
            return 0;
        }
        LocalDate firstDate = shiftMapByDate.keySet().iterator().next();
        SkuDailyPlanQuotaDTO quota = sourceSku.getDailyPlanQuotaMap().get(firstDate);
        if (quota == null) {
            return 0;
        }
        int carryShortage = Math.max(0, quota.getRemainingQty() - quota.getDayPlanQty());
        if (carryShortage > 0) {
            log.info("续作多机台历史允许追补欠产进入首日carryShortage, materialCode: {}, 日期: {}, dayN: {}, 剩余额度: {}, carryShortage: {}",
                    sourceSku.getMaterialCode(), firstDate, quota.getDayPlanQty(), quota.getRemainingQty(), carryShortage);
        }
        return carryShortage;
    }

    /**
     * 解析续作多机台当日需保障量。
     *
     * @param context 排程上下文
     * @param sourceSku 来源SKU
     * @param skuResults 同SKU续作结果
     * @param shifts 班次列表
     * @return 当日需保障量
     */
    private int resolveContinuationDailyDemand(LhScheduleContext context,
                                               SkuScheduleDTO sourceSku,
                                               List<LhScheduleResult> skuResults,
                                               List<LhShiftConfigVO> shifts) {
        if (sourceSku == null) {
            return 0;
        }
        if (shouldUseTargetQtyForContinuationReduction(sourceSku)) {
            int targetQty = sourceSku.resolveTargetScheduleQty();
            log.info("续作多机台目标量保障解析, materialCode: {}, 目标量: {}, 窗口日计划量: {}",
                    sourceSku.getMaterialCode(), targetQty, sumDailyPlanQty(sourceSku.getDailyPlanQuotaMap()));
            return targetQty;
        }
        if (hasEndingResult(skuResults)) {
            int targetQty = sourceSku.resolveTargetScheduleQty();
            if (targetQty > 0) {
                log.info("续作多机台收尾严格目标, materialCode: {}, 目标量: {}, 是否补满: 0, 是否超排: 0",
                        sourceSku.getMaterialCode(), targetQty);
                return targetQty;
            }
            return !CollectionUtils.isEmpty(skuResults)
                    ? resolveEndingDemandQty(context, skuResults.get(0)) : 0;
        }
        LocalDate productionDate = resolveFirstProductionDate(skuResults, shifts);
        Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap = sourceSku.getDailyPlanQuotaMap();
        if (!CollectionUtils.isEmpty(quotaMap) && productionDate != null) {
            SkuDailyPlanQuotaDTO quota = quotaMap.get(productionDate);
            if (quota != null) {
                int demandQty = quota.getRemainingQty() > 0 ? quota.getRemainingQty() : quota.getDayPlanQty();
                log.info("续作多机台dayN保障量解析, materialCode: {}, 日期: {}, dayN: {}, 剩余额度: {}, 保障量: {}",
                        sourceSku.getMaterialCode(), productionDate, quota.getDayPlanQty(),
                        quota.getRemainingQty(), demandQty);
                return Math.max(0, demandQty);
            }
        }
        return Math.max(0, sourceSku.resolveTargetScheduleQty());
    }

    /**
     * 判断续作多机台降模是否应以业务目标量而非dayN日计划量作为收口依据。
     * <p>月计划日计划量只决定当前窗口是否允许排产和是否需要增机台，不限制已有续作清尾量。</p>
     *
     * @param sourceSku 来源SKU
     * @return true-按目标量收口，false-沿用日计划降模
     */
    private boolean shouldUseTargetQtyForContinuationReduction(SkuScheduleDTO sourceSku) {
        if (sourceSku == null || CollectionUtils.isEmpty(sourceSku.getDailyPlanQuotaMap())) {
            return false;
        }
        int targetQty = Math.max(0, sourceSku.resolveTargetScheduleQty());
        if (targetQty <= 0) {
            return false;
        }
        return targetQty > sumDailyPlanQty(sourceSku.getDailyPlanQuotaMap());
    }

    /**
     * 收尾续作多机台降模时，判断是否只需保留一台机台。
     * <p>月计划日计划量只参与是否需要保留额外机台的判断：若胶囊使用次数最高的机台完整窗口产能已覆盖
     * 窗口计划量，且硫化余量足够这台机排满，则按降模规则释放其他机台。</p>
     *
     * @param context 排程上下文
     * @param sourceSku 来源SKU
     * @param skuResults 同SKU续作结果
     * @param shifts 班次列表
     * @return true-已按单机降模处理；false-继续原降模链路
     */
    private boolean reduceEndingContinuationToSingleMachineWhenCovered(LhScheduleContext context,
                                                                       SkuScheduleDTO sourceSku,
                                                                       List<LhScheduleResult> skuResults,
                                                                       List<LhShiftConfigVO> shifts) {
        if (context == null || sourceSku == null || CollectionUtils.isEmpty(skuResults)
                || CollectionUtils.isEmpty(shifts) || skuResults.size() <= 1) {
            return false;
        }
        if (CollectionUtils.isEmpty(sourceSku.getDailyPlanQuotaMap())) {
            return false;
        }
        if (!isSingleMachineReductionEndingCandidate(sourceSku, skuResults)) {
            return false;
        }
        int historyShortageQty = Math.max(0, sourceSku.getMonthlyHistoryShortageQty());
        int threshold = Math.max(0, DailyMachineExpansionPlanner.resolveShortageAddMachineThreshold(context));
        if (historyShortageQty > threshold) {
            return false;
        }
        int firstDayPlanQty = resolveFirstScheduleDayPlanQty(context, sourceSku, shifts);
        if (firstDayPlanQty <= 0) {
            return false;
        }
        List<LhScheduleResult> sortedResults = new ArrayList<LhScheduleResult>(skuResults);
        sortedResults.sort(buildContinuationKeepComparator(context));
        LhScheduleResult keptResult = sortedResults.get(0);
        int keptMachineCapacity = calculateMachineWindowCapacity(context, keptResult, shifts);
        int materialAvailableQty = Math.max(0, resolveEndingDemandQty(context, keptResult));
        if (keptMachineCapacity <= 0 || keptMachineCapacity < firstDayPlanQty
                || materialAvailableQty < keptMachineCapacity) {
            log.info("续作收尾单机降模跳过, materialCode: {}, 首选机台: {}, historyShortageQty: {}, "
                            + "threshold: {}, firstDayPlanQty: {}, keptMachineCapacity: {}, materialAvailableQty: {}, "
                            + "原因: 单机窗口产能不足以满足T日计划量或物理余量不足",
                    sourceSku.getMaterialCode(), keptResult.getLhMachineCode(), historyShortageQty, threshold,
                    firstDayPlanQty, keptMachineCapacity, materialAvailableQty);
            return false;
        }

        redistributeShiftQty(context, keptResult, shifts, keptMachineCapacity);
        List<LhScheduleResult> keptResults = Collections.singletonList(keptResult);
        List<LhScheduleResult> removedResults = selectMachinesToRemoveForContinuation(context, skuResults, keptResults);
        for (LhScheduleResult result : removedResults) {
            redistributeShiftQty(context, result, shifts, 0);
        }
        context.getSingleMachineReducedContinuationGroupKeySet().add(
                buildSingleMachineReducedContinuationKey(sourceSku));
        log.info("续作收尾单机降模完成, materialCode: {}, 保留机台: {}, 下机机台: {}, historyShortageQty: {}, "
                        + "threshold: {}, firstDayPlanQty: {}, keptMachineCapacity: {}, materialAvailableQty: {}, "
                        + "保留规则: 胶囊使用次数多的优先保留，胶囊使用次数少的优先下机",
                sourceSku.getMaterialCode(), keptResult.getLhMachineCode(), joinMachineCodes(removedResults),
                historyShortageQty, threshold, firstDayPlanQty, keptMachineCapacity, materialAvailableQty);
        return true;
    }

    /**
     * 判断是否为单机降模场景下的收尾候选。
     * <p>该判断只服务“续作多机台降模是否可只留一台”，不得作为通用收尾口径复用。
     * 真实排程中结果行收尾标记可能在最终收口时才刷新，因此这里同时参考来源SKU的业务目标口径。</p>
     *
     * @param sourceSku 来源SKU
     * @param skuResults 同SKU续作结果
     * @return true-单机降模收尾候选
     */
    private boolean isSingleMachineReductionEndingCandidate(SkuScheduleDTO sourceSku,
                                                            List<LhScheduleResult> skuResults) {
        if (hasEndingResult(skuResults)) {
            return true;
        }
        if (sourceSku == null || sourceSku.isStrictNewSpecShortageOnly()) {
            return false;
        }
        if (StringUtils.equals(SkuTagEnum.ENDING.getCode(), sourceSku.getSkuTag())) {
            return true;
        }
        return sourceSku.isStrictTargetQty();
    }

    /**
     * 判断续作分组是否已按降模规则只保留单台机台。
     *
     * @param context 排程上下文
     * @param sourceSku 来源SKU
     * @return true-已单机降模
     */
    private boolean isSingleMachineReducedContinuationGroup(LhScheduleContext context, SkuScheduleDTO sourceSku) {
        return context != null
                && sourceSku != null
                && context.getSingleMachineReducedContinuationGroupKeySet()
                .contains(buildSingleMachineReducedContinuationKey(sourceSku));
    }

    /**
     * 构建单机降模运行态标记。
     * <p>同物料多台续作在真实数据中可能拆成多个SKU账本副本，单机降模必须按物料级阻断后置补偿。</p>
     *
     * @param sourceSku 来源SKU
     * @return 单机降模标记
     */
    private String buildSingleMachineReducedContinuationKey(SkuScheduleDTO sourceSku) {
        if (sourceSku == null || StringUtils.isEmpty(sourceSku.getMaterialCode())) {
            return "";
        }
        return sourceSku.getMaterialCode() + SINGLE_MACHINE_REDUCED_CONTINUATION_KEY_SUFFIX;
    }

    /**
     * 解析续作降模判断使用的T日计划量。
     *
     * @param sourceSku 来源SKU
     * @param shifts 排程窗口班次
     * @return T日计划量
     */
    private int resolveFirstScheduleDayPlanQty(LhScheduleContext context,
                                               SkuScheduleDTO sourceSku,
                                               List<LhShiftConfigVO> shifts) {
        Map<LocalDate, List<LhShiftConfigVO>> shiftMapByDate = groupShiftsByWorkDate(shifts);
        if (CollectionUtils.isEmpty(shiftMapByDate)) {
            return 0;
        }
        LocalDate firstProductionDate = shiftMapByDate.keySet().iterator().next();
        int originalMonthPlanQty = resolveOriginalMonthPlanDayQty(context, sourceSku, firstProductionDate);
        if (originalMonthPlanQty > 0) {
            return originalMonthPlanQty;
        }
        return resolveContinuationDayPlanQtyByDate(sourceSku, firstProductionDate);
    }

    /**
     * 解析月计划原始T日计划量。
     * <p>续作降模判断只用原始月计划判断是否需要保留额外机台，不能使用已合入欠产追补的运行态账本。</p>
     *
     * @param context 排程上下文
     * @param sourceSku 来源SKU
     * @param productionDate 生产日期
     * @return 月计划原始日计划量
     */
    private int resolveOriginalMonthPlanDayQty(LhScheduleContext context,
                                               SkuScheduleDTO sourceSku,
                                               LocalDate productionDate) {
        if (context == null || sourceSku == null || productionDate == null
                || StringUtils.isEmpty(sourceSku.getMaterialCode())
                || CollectionUtils.isEmpty(context.getMonthPlanList())) {
            return 0;
        }
        for (FactoryMonthPlanProductionFinalResult plan : context.getMonthPlanList()) {
            if (plan == null || !StringUtils.equals(sourceSku.getMaterialCode(), plan.getMaterialCode())) {
                continue;
            }
            Integer year = plan.getYear();
            Integer month = plan.getMonth();
            if (year != null && month != null
                    && (year.intValue() != productionDate.getYear()
                    || month.intValue() != productionDate.getMonthValue())) {
                continue;
            }
            int dayPlanQty = MonthPlanDayQtyUtil.resolveDayQty(plan, productionDate.getDayOfMonth());
            log.debug("续作单机降模月计划T日量解析, materialCode: {}, 日期: {}, monthPlanDayQty: {}",
                    sourceSku.getMaterialCode(), productionDate, dayPlanQty);
            return Math.max(0, dayPlanQty);
        }
        return 0;
    }

    /**
     * 计算单台续作机台在当前排程窗口内的完整可用产能。
     *
     * @param context 排程上下文
     * @param result 续作结果
     * @param shifts 班次列表
     * @return 窗口可用产能
     */
    private int calculateMachineWindowCapacity(LhScheduleContext context,
                                               LhScheduleResult result,
                                               List<LhShiftConfigVO> shifts) {
        if (context == null || result == null || CollectionUtils.isEmpty(shifts)) {
            return 0;
        }
        Date firstPlannedStartTime = resolveRedistributeStartTime(result, shifts);
        int totalCapacity = 0;
        for (LhShiftConfigVO shift : shifts) {
            if (shift == null || shift.getShiftEndDateTime() == null) {
                continue;
            }
            if (firstPlannedStartTime != null && !shift.getShiftEndDateTime().after(firstPlannedStartTime)) {
                continue;
            }
            totalCapacity += calculateResultShiftCapacity(context, result, shift);
        }
        return totalCapacity;
    }

    /**
     * 判断续作结果组是否存在收尾结果。
     *
     * @param skuResults 同SKU续作结果
     * @return true-存在收尾结果
     */
    private boolean hasEndingResult(List<LhScheduleResult> skuResults) {
        if (CollectionUtils.isEmpty(skuResults)) {
            return false;
        }
        for (LhScheduleResult result : skuResults) {
            if (result != null && "1".equals(result.getIsEnd())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 解析续作结果组的首个生产日期。
     *
     * @param skuResults 同SKU续作结果
     * @param shifts 班次列表
     * @return 首个生产日期
     */
    private LocalDate resolveFirstProductionDate(List<LhScheduleResult> skuResults, List<LhShiftConfigVO> shifts) {
        if (CollectionUtils.isEmpty(skuResults) || CollectionUtils.isEmpty(shifts)) {
            return null;
        }
        Date firstStartTime = null;
        LhShiftConfigVO firstShift = null;
        for (LhScheduleResult result : skuResults) {
            for (LhShiftConfigVO shift : shifts) {
                Integer planQty = ShiftFieldUtil.getShiftPlanQty(result, shift.getShiftIndex());
                Date shiftStartTime = ShiftFieldUtil.getShiftStartTime(result, shift.getShiftIndex());
                if (planQty == null || planQty <= 0 || shiftStartTime == null || shift.getWorkDate() == null) {
                    continue;
                }
                if (firstStartTime == null || shiftStartTime.before(firstStartTime)) {
                    firstStartTime = shiftStartTime;
                    firstShift = shift;
                }
            }
        }
        if (firstShift == null || firstShift.getWorkDate() == null) {
            firstShift = shifts.get(0);
        }
        return firstShift.getWorkDate() == null ? null
                : firstShift.getWorkDate().toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
    }

    /**
     * 计算续作多机台组内每台机当天可用产能。
     *
     * @param context 排程上下文
     * @param skuResults 同SKU续作结果
     * @param shifts 班次列表
     * @return 结果到日产能的映射
     */
    private Map<LhScheduleResult, Integer> calculateMachineDailyCapacityMap(LhScheduleContext context,
                                                                            List<LhScheduleResult> skuResults,
                                                                            List<LhShiftConfigVO> shifts) {
        Map<LhScheduleResult, Integer> capacityMap = new IdentityHashMap<LhScheduleResult, Integer>(16);
        for (LhScheduleResult result : skuResults) {
            int capacity = calculateMachineDailyCapacity(context, result, shifts);
            capacityMap.put(result, capacity);
            MachineScheduleDTO machine = context.getMachineScheduleMap().get(result.getLhMachineCode());
            int capsuleUsageCount = machine != null ? machine.getCapsuleUsageCount() : 0;
            log.info("续作多机台机台产能排序基础, machineCode: {}, capsuleUsedCount: {}, dailyCapacity: {}",
                    result.getLhMachineCode(), capsuleUsageCount, capacity);
        }
        return capacityMap;
    }

    /**
     * 计算续作多机台组在指定业务日内每台机台的可用产能。
     *
     * @param context 排程上下文
     * @param skuResults 同SKU续作结果
     * @param dayShifts 当日班次
     * @return 结果到日产能的映射
     */
    private Map<LhScheduleResult, Integer> calculateMachineDailyCapacityMapByDate(LhScheduleContext context,
                                                                                  List<LhScheduleResult> skuResults,
                                                                                  List<LhShiftConfigVO> dayShifts) {
        Map<LhScheduleResult, Integer> capacityMap = new IdentityHashMap<LhScheduleResult, Integer>(16);
        for (LhScheduleResult result : skuResults) {
            int capacity = calculateMachineDailyCapacityByDate(context, result, dayShifts);
            capacityMap.put(result, capacity);
            MachineScheduleDTO machine = context.getMachineScheduleMap().get(result.getLhMachineCode());
            int capsuleUsageCount = machine != null ? machine.getCapsuleUsageCount() : 0;
            log.info("续作多机台机台产能排序基础, machineCode: {}, capsuleUsedCount: {}, dailyCapacity: {}",
                    result.getLhMachineCode(), capsuleUsageCount, capacity);
        }
        return capacityMap;
    }

    /**
     * 计算单台续作机台当天可用产能。
     *
     * @param context 排程上下文
     * @param result 续作结果
     * @param shifts 班次列表
     * @return 当天可用产能
     */
    private int calculateMachineDailyCapacity(LhScheduleContext context,
                                              LhScheduleResult result,
                                              List<LhShiftConfigVO> shifts) {
        if (result == null || CollectionUtils.isEmpty(shifts)) {
            return result != null ? ShiftFieldUtil.resolveScheduledQty(result) : 0;
        }
        Date firstPlannedStartTime = resolveFirstPlannedShiftStartTime(result);
        if (firstPlannedStartTime == null) {
            return ShiftFieldUtil.resolveScheduledQty(result);
        }
        LocalDate productionDate = firstPlannedStartTime.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
        int totalCapacity = 0;
        for (LhShiftConfigVO shift : shifts) {
            if (shift == null || shift.getShiftStartDateTime() == null || shift.getShiftEndDateTime() == null) {
                continue;
            }
            LocalDate shiftStartDate = shift.getShiftStartDateTime().toInstant()
                    .atZone(ZoneId.systemDefault()).toLocalDate();
            if (!productionDate.equals(shiftStartDate) || !shift.getShiftEndDateTime().after(firstPlannedStartTime)) {
                continue;
            }
            totalCapacity += calculateResultShiftCapacity(context, result, shift);
        }
        return totalCapacity > 0 ? totalCapacity : ShiftFieldUtil.resolveScheduledQty(result);
    }

    /**
     * 计算单台续作机台在指定业务日的可用产能。
     *
     * @param context 排程上下文
     * @param result 续作结果
     * @param dayShifts 当日班次
     * @return 当日可用产能
     */
    private int calculateMachineDailyCapacityByDate(LhScheduleContext context,
                                                    LhScheduleResult result,
                                                    List<LhShiftConfigVO> dayShifts) {
        if (result == null || CollectionUtils.isEmpty(dayShifts)) {
            return result != null ? ShiftFieldUtil.resolveScheduledQty(result) : 0;
        }
        Date firstPlannedStartTime = resolveRedistributeStartTime(result, dayShifts);
        if (firstPlannedStartTime == null) {
            firstPlannedStartTime = dayShifts.get(0).getShiftStartDateTime();
        }
        int totalCapacity = 0;
        for (LhShiftConfigVO shift : dayShifts) {
            if (shift == null || shift.getShiftEndDateTime() == null) {
                continue;
            }
            if (!shift.getShiftEndDateTime().after(firstPlannedStartTime)) {
                continue;
            }
            totalCapacity += calculateResultShiftCapacity(context, result, shift);
        }
        return totalCapacity > 0 ? totalCapacity : sumScheduledQtyByShifts(Collections.singletonList(result), dayShifts);
    }

    /**
     * 计算结果在指定班次的可排产能。
     *
     * @param context 排程上下文
     * @param result 续作结果
     * @param shift 班次
     * @return 班次可排产能
     */
    private int calculateResultShiftCapacity(LhScheduleContext context,
                                             LhScheduleResult result,
                                             LhShiftConfigVO shift) {
        if (context == null || result == null || shift == null
                || result.getLhTime() == null || result.getLhTime() <= 0) {
            return 0;
        }
        int mouldQty = ShiftCapacityResolverUtil.resolveMachineMouldQty(
                result.getMouldQty() != null ? result.getMouldQty() : 0);
        int shiftCapacity = result.getSingleMouldShiftQty() != null ? result.getSingleMouldShiftQty() : 0;
        if (mouldQty <= 0 || shiftCapacity <= 0) {
            return 0;
        }
        ShiftProductionControlDTO control = ShiftProductionControlUtil.resolveEffectiveControl(
                context, shift, shift.getShiftStartDateTime());
        if (control == null || !control.isCanSchedule()) {
            return 0;
        }
        List<MachineCleaningWindowDTO> cleaningWindowList = resolveEffectiveCleaningWindowList(
                context, result, resolveFirstPlannedShiftStartTime(result));
        List<MachineMaintenanceWindowDTO> maintenanceWindowList = resolveMachineMaintenanceWindowList(
                context, result.getLhMachineCode());
        int dryIceLossQty = context.getParamIntValue(
                LhScheduleParamConstant.DRY_ICE_LOSS_QTY, LhScheduleConstant.DRY_ICE_LOSS_QTY);
        int dryIceDurationHours = context.getParamIntValue(
                LhScheduleParamConstant.DRY_ICE_DURATION_HOURS, LhScheduleConstant.DRY_ICE_DURATION_HOURS);
        String configPlusShiftType = ShiftCapacityResolverUtil.resolveOddShiftCapacityPlusShiftType(context);
        int actualShiftPlanQty = ShiftCapacityResolverUtil.resolveActualShiftPlanQty(
                shiftCapacity, shift, configPlusShiftType, ScheduleTypeEnum.CONTINUOUS.getCode());
        boolean oddShiftAdjustEnabled = ShiftCapacityResolverUtil.isOddShiftCapacityAdjustEnabled(
                shiftCapacity, shift, configPlusShiftType, ScheduleTypeEnum.CONTINUOUS.getCode());
        log.debug("奇数班产修正检查, 当前流程: 续作排产, materialCode: {}, machineCode: {}, 参数是否配置: {}, "
                        + "参数值: {}, 配置值是否合法: {}, 是否启用: {}, 未启用原因: {}, 原始班产: {}, "
                        + "班次序号: {}, 当前班别: {}, 当前班次修正后的计划量: {}, 班产落库字段值: {}",
                result.getMaterialCode(), result.getLhMachineCode(), StringUtils.isNotEmpty(configPlusShiftType),
                configPlusShiftType,
                ShiftCapacityResolverUtil.isOddShiftCapacityPlusShiftTypeValid(configPlusShiftType),
                oddShiftAdjustEnabled,
                ShiftCapacityResolverUtil.resolveOddShiftCapacityDisabledReason(
                        shiftCapacity, shift, configPlusShiftType, ScheduleTypeEnum.CONTINUOUS.getCode()),
                shiftCapacity, shift.getShiftIndex(), shift.resolveShiftTypeEnum(), actualShiftPlanQty,
                shiftCapacity);
        int shiftMaxQty = ShiftCapacityResolverUtil.resolveShiftCapacityWithDowntime(
                context.getDevicePlanShutList(),
                cleaningWindowList,
                maintenanceWindowList,
                result.getLhMachineCode(),
                control.getEffectiveStartTime(),
                control.getEffectiveEndTime(),
                shiftCapacity,
                result.getLhTime(),
                mouldQty,
                ShiftCapacityResolverUtil.resolveShiftDurationSeconds(shift),
                dryIceLossQty,
                dryIceDurationHours,
                shift,
                configPlusShiftType,
                ScheduleTypeEnum.CONTINUOUS.getCode());
        if (oddShiftAdjustEnabled) {
            log.info("奇数班产修正命中, 当前流程: 续作排产, materialCode: {}, machineCode: {}, 参数值: {}, "
                            + "原始班产: {}, 班次序号: {}, 当前班别: {}, 修正后班次计划量: {}, 班产落库字段值: {}",
                    result.getMaterialCode(), result.getLhMachineCode(), configPlusShiftType, shiftCapacity,
                    shift.getShiftIndex(), shift.resolveShiftTypeEnum(), actualShiftPlanQty, shiftCapacity);
        }
        return ShiftProductionControlUtil.deductCapacityByControl(control, shiftMaxQty, mouldQty);
    }

    /**
     * 选择续作降模后需要保留的机台。
     *
     * @param context 排程上下文
     * @param skuResults 同SKU续作结果
     * @param capacityMap 机台日产能
     * @param demandQty 当日需保障量
     * @return 保留结果列表
     */
    private List<LhScheduleResult> selectMachinesToKeepForContinuation(LhScheduleContext context,
                                                                       List<LhScheduleResult> skuResults,
                                                                       Map<LhScheduleResult, Integer> capacityMap,
                                                                       int demandQty) {
        List<LhScheduleResult> sortedResults = new ArrayList<LhScheduleResult>(skuResults);
        sortedResults.sort(buildContinuationKeepComparator(context));
        List<LhScheduleResult> keptResults = new ArrayList<LhScheduleResult>(sortedResults.size());
        int accumulatedCapacity = 0;
        for (LhScheduleResult result : sortedResults) {
            if (accumulatedCapacity >= demandQty) {
                break;
            }
            keptResults.add(result);
            accumulatedCapacity += Math.max(0, capacityMap.getOrDefault(result, 0));
        }
        List<LhScheduleResult> removedResults = selectMachinesToRemoveForContinuation(context, skuResults, keptResults);
        log.info("续作多机台降模排序, 保留排序: {}, 下机排序: {}, 保留排序明细: {}, 下机排序明细: {}",
                joinMachineCodes(sortedResults), joinMachineCodes(removedResults),
                formatContinuationMachineDetails(context, sortedResults, capacityMap),
                formatContinuationMachineDetails(context, removedResults, capacityMap));
        return keptResults;
    }

    /**
     * 按追补窗口反向模拟续作降模最小保留机台。
     *
     * @param context 排程上下文
     * @param sourceSku 来源SKU
     * @param skuResults 当前仍在机结果
     * @param shiftMapByDate 业务日班次
     * @param productionDate 当前业务日
     * @param carryShortageQty 前序欠产量
     * @param remainingTargetQty 剩余窗口目标量
     * @param shortageLookAheadDays 欠产追补观察天数
     * @param policy 排产量策略
     * @return 保留结果列表
     */
    private List<LhScheduleResult> selectMachinesToKeepForContinuationByLookAhead(
            LhScheduleContext context,
            SkuScheduleDTO sourceSku,
            List<LhScheduleResult> skuResults,
            Map<LocalDate, List<LhShiftConfigVO>> shiftMapByDate,
            LocalDate productionDate,
            int carryShortageQty,
            int remainingTargetQty,
            int shortageLookAheadDays,
            ProductionQuantityPolicy policy) {
        List<LhScheduleResult> sortedResults = new ArrayList<LhScheduleResult>(skuResults);
        sortedResults.sort(buildContinuationKeepComparator(context));
        int cumulativeRequired = calculateContinuationFutureRequired(
                sourceSku, shiftMapByDate, productionDate, carryShortageQty, remainingTargetQty,
                shortageLookAheadDays, policy);
        if (cumulativeRequired <= 0) {
            log.info("续作多机台降模追补模拟, materialCode: {}, 日期: {}, 后续追补需求: 0, 保留机台为空，释放机台: {}",
                    sourceSku.getMaterialCode(), productionDate, joinMachineCodes(sortedResults));
            return new ArrayList<LhScheduleResult>(0);
        }
        List<LhScheduleResult> keptResults = new ArrayList<LhScheduleResult>(sortedResults.size());
        int cumulativeCapacity = 0;
        for (LhScheduleResult result : sortedResults) {
            keptResults.add(result);
            cumulativeCapacity = calculateContinuationFutureCapacity(
                    context, keptResults, shiftMapByDate, productionDate, shortageLookAheadDays);
            log.debug("续作多机台降模追补模拟, materialCode: {}, 日期: {}, 尝试保留机台: {}, "
                            + "前日排后差额: {}, 后续追补需求: {}, 后续追补产能: {}, 是否满足: {}",
                    sourceSku.getMaterialCode(), productionDate, joinMachineCodes(keptResults),
                    carryShortageQty, cumulativeRequired, cumulativeCapacity, cumulativeCapacity >= cumulativeRequired);
            if (cumulativeCapacity >= cumulativeRequired) {
                break;
            }
        }
        List<LhScheduleResult> removedResults = selectMachinesToRemoveForContinuation(context, skuResults, keptResults);
        log.info("续作多机台降模排序, 保留排序: {}, 下机排序: {}, 后续追补需求: {}, 后续追补产能: {}, 保留排序明细: {}, 下机排序明细: {}",
                joinMachineCodes(sortedResults), joinMachineCodes(removedResults), cumulativeRequired, cumulativeCapacity,
                formatContinuationMachineDetails(context, sortedResults,
                        calculateMachineDailyCapacityMapByDateSilently(context, sortedResults, shiftMapByDate.get(productionDate))),
                formatContinuationMachineDetails(context, removedResults,
                        calculateMachineDailyCapacityMapByDateSilently(context, removedResults, shiftMapByDate.get(productionDate))));
        return keptResults;
    }

    /**
     * 判断保留机台是否满足当前日到追补结束日的累计需求。
     *
     * @param context 排程上下文
     * @param sourceSku 来源SKU
     * @param keptResults 保留机台结果
     * @param shiftMapByDate 业务日班次
     * @param productionDate 当前业务日
     * @param carryShortageQty 前序欠产量
     * @param remainingTargetQty 剩余窗口目标量
     * @param shortageLookAheadDays 欠产追补观察天数
     * @param policy 排产量策略
     * @return true-满足追补约束
     */
    private boolean canContinuationMachinesMeetLookAhead(LhScheduleContext context,
                                                         SkuScheduleDTO sourceSku,
                                                         List<LhScheduleResult> keptResults,
                                                         Map<LocalDate, List<LhShiftConfigVO>> shiftMapByDate,
                                                         LocalDate productionDate,
                                                         int carryShortageQty,
                                                         int remainingTargetQty,
                                                         int shortageLookAheadDays,
                                                         ProductionQuantityPolicy policy) {
        int cumulativeRequired = calculateContinuationFutureRequired(
                sourceSku, shiftMapByDate, productionDate, carryShortageQty, remainingTargetQty,
                shortageLookAheadDays, policy);
        int cumulativeCapacity = calculateContinuationFutureCapacity(
                context, keptResults, shiftMapByDate, productionDate, shortageLookAheadDays);
        return cumulativeCapacity >= cumulativeRequired;
    }

    /**
     * 计算当前日之后追补窗口内的续作累计需求。
     * <p>当前日不足由下机机台只补当天，保留机台只承担后续日期续作能力判断。</p>
     *
     * @param sourceSku 来源SKU
     * @param shiftMapByDate 业务日班次
     * @param productionDate 当前业务日
     * @param rollingDiffQty 前日排后差额
     * @param remainingTargetQty 剩余窗口目标量
     * @param shortageLookAheadDays 欠产追补观察天数
     * @param policy 排产量策略
     * @return 后续追补需求量
     */
    private int calculateContinuationFutureRequired(SkuScheduleDTO sourceSku,
                                                    Map<LocalDate, List<LhShiftConfigVO>> shiftMapByDate,
                                                    LocalDate productionDate,
                                                    int rollingDiffQty,
                                                    int remainingTargetQty,
                                                    int shortageLookAheadDays,
                                                    ProductionQuantityPolicy policy) {
        if (sourceSku == null || CollectionUtils.isEmpty(shiftMapByDate) || productionDate == null) {
            return 0;
        }
        int cumulativeRequired = 0;
        LocalDate lookAheadEndDate = resolveLookAheadEndDate(shiftMapByDate, productionDate, shortageLookAheadDays);
        for (LocalDate date : shiftMapByDate.keySet()) {
            if (!date.isAfter(productionDate) || date.isAfter(lookAheadEndDate)) {
                continue;
            }
            cumulativeRequired += resolveContinuationDayDemandQtyByDate(sourceSku, date);
        }
        if (policy != null && policy.isStrictUpperLimit()) {
            int currentDayDemandQty = resolveContinuationDayDemandQtyByDate(sourceSku, productionDate);
            int currentEffectiveDemandQty = Math.min(Math.max(0, rollingDiffQty + currentDayDemandQty),
                    Math.max(0, remainingTargetQty));
            cumulativeRequired = Math.min(cumulativeRequired,
                    Math.max(0, remainingTargetQty - currentEffectiveDemandQty));
        }
        return Math.max(0, cumulativeRequired);
    }

    /**
     * 计算当前日之后追补窗口内的续作保留机台累计产能。
     *
     * @param context 排程上下文
     * @param keptResults 保留机台结果
     * @param shiftMapByDate 业务日班次
     * @param productionDate 当前业务日
     * @param shortageLookAheadDays 欠产追补观察天数
     * @return 后续追补产能
     */
    private int calculateContinuationFutureCapacity(LhScheduleContext context,
                                                    List<LhScheduleResult> keptResults,
                                                    Map<LocalDate, List<LhShiftConfigVO>> shiftMapByDate,
                                                    LocalDate productionDate,
                                                    int shortageLookAheadDays) {
        if (CollectionUtils.isEmpty(keptResults) || CollectionUtils.isEmpty(shiftMapByDate) || productionDate == null) {
            return 0;
        }
        int cumulativeCapacity = 0;
        LocalDate lookAheadEndDate = resolveLookAheadEndDate(shiftMapByDate, productionDate, shortageLookAheadDays);
        for (Map.Entry<LocalDate, List<LhShiftConfigVO>> entry : shiftMapByDate.entrySet()) {
            LocalDate date = entry.getKey();
            if (!date.isAfter(productionDate) || date.isAfter(lookAheadEndDate)) {
                continue;
            }
            for (LhScheduleResult result : keptResults) {
                cumulativeCapacity += calculateMachineDailyCapacityByDate(context, result, entry.getValue());
            }
        }
        return Math.max(0, cumulativeCapacity);
    }

    /**
     * 计算当前日到追补结束日的续作累计需求。
     *
     * @param sourceSku 来源SKU
     * @param shiftMapByDate 业务日班次
     * @param productionDate 当前业务日
     * @param carryShortageQty 前序欠产量
     * @param remainingTargetQty 剩余窗口目标量
     * @param shortageLookAheadDays 欠产追补观察天数
     * @param policy 排产量策略
     * @return 累计需求量
     */
    private int calculateContinuationLookAheadRequired(SkuScheduleDTO sourceSku,
                                                       Map<LocalDate, List<LhShiftConfigVO>> shiftMapByDate,
                                                       LocalDate productionDate,
                                                       int carryShortageQty,
                                                       int remainingTargetQty,
                                                       int shortageLookAheadDays,
                                                       ProductionQuantityPolicy policy) {
        int cumulativeRequired = Math.max(0, carryShortageQty);
        LocalDate lookAheadEndDate = resolveLookAheadEndDate(shiftMapByDate, productionDate, shortageLookAheadDays);
        for (LocalDate date : shiftMapByDate.keySet()) {
            if (date.isBefore(productionDate) || date.isAfter(lookAheadEndDate)) {
                continue;
            }
            cumulativeRequired += resolveContinuationDayPlanQtyByDate(sourceSku, date);
        }
        if (policy != null && policy.isStrictUpperLimit()) {
            cumulativeRequired = Math.min(cumulativeRequired, Math.max(0, remainingTargetQty));
        }
        return Math.max(0, cumulativeRequired);
    }

    /**
     * 计算当前日到追补结束日的续作保留机台累计产能。
     *
     * @param context 排程上下文
     * @param keptResults 保留机台结果
     * @param shiftMapByDate 业务日班次
     * @param productionDate 当前业务日
     * @param shortageLookAheadDays 欠产追补观察天数
     * @return 累计产能
     */
    private int calculateContinuationLookAheadCapacity(LhScheduleContext context,
                                                       List<LhScheduleResult> keptResults,
                                                       Map<LocalDate, List<LhShiftConfigVO>> shiftMapByDate,
                                                       LocalDate productionDate,
                                                       int shortageLookAheadDays) {
        if (CollectionUtils.isEmpty(keptResults) || CollectionUtils.isEmpty(shiftMapByDate) || productionDate == null) {
            return 0;
        }
        int cumulativeCapacity = 0;
        LocalDate lookAheadEndDate = resolveLookAheadEndDate(shiftMapByDate, productionDate, shortageLookAheadDays);
        for (Map.Entry<LocalDate, List<LhShiftConfigVO>> entry : shiftMapByDate.entrySet()) {
            LocalDate date = entry.getKey();
            if (date.isBefore(productionDate) || date.isAfter(lookAheadEndDate)) {
                continue;
            }
            for (LhScheduleResult result : keptResults) {
                cumulativeCapacity += calculateMachineDailyCapacityByDate(context, result, entry.getValue());
            }
        }
        return Math.max(0, cumulativeCapacity);
    }

    /**
     * 静默计算指定业务日内每台机台的可用产能。
     *
     * @param context 排程上下文
     * @param skuResults 同SKU续作结果
     * @param dayShifts 当日班次
     * @return 结果到日产能的映射
     */
    private Map<LhScheduleResult, Integer> calculateMachineDailyCapacityMapByDateSilently(
            LhScheduleContext context,
            List<LhScheduleResult> skuResults,
            List<LhShiftConfigVO> dayShifts) {
        Map<LhScheduleResult, Integer> capacityMap = new IdentityHashMap<LhScheduleResult, Integer>(16);
        if (CollectionUtils.isEmpty(skuResults)) {
            return capacityMap;
        }
        for (LhScheduleResult result : skuResults) {
            capacityMap.put(result, calculateMachineDailyCapacityByDate(context, result, dayShifts));
        }
        return capacityMap;
    }

    /**
     * 解析当前业务日的追补结束日。
     *
     * @param shiftMapByDate 业务日班次
     * @param productionDate 当前业务日
     * @param shortageLookAheadDays 欠产追补观察天数
     * @return 追补结束日
     */
    private LocalDate resolveLookAheadEndDate(Map<LocalDate, List<LhShiftConfigVO>> shiftMapByDate,
                                              LocalDate productionDate,
                                              int shortageLookAheadDays) {
        LocalDate lookAheadEndDate = productionDate.plusDays(Math.max(0, shortageLookAheadDays));
        LocalDate windowLastDate = productionDate;
        for (LocalDate date : shiftMapByDate.keySet()) {
            windowLastDate = date;
        }
        return lookAheadEndDate.isAfter(windowLastDate) ? windowLastDate : lookAheadEndDate;
    }

    /**
     * 汇总指定结果在当日产能映射中的产能。
     *
     * @param capacityMap 当日产能映射
     * @param results 结果列表
     * @return 产能合计
     */
    private int sumCapacityForResults(Map<LhScheduleResult, Integer> capacityMap, List<LhScheduleResult> results) {
        if (CollectionUtils.isEmpty(capacityMap) || CollectionUtils.isEmpty(results)) {
            return 0;
        }
        int totalCapacity = 0;
        for (LhScheduleResult result : results) {
            totalCapacity += Math.max(0, capacityMap.getOrDefault(result, 0));
        }
        return totalCapacity;
    }

    /**
     * 选择续作降模下机机台。
     *
     * @param skuResults 同SKU续作结果
     * @param keptResults 保留结果
     * @return 下机结果
     */
    private List<LhScheduleResult> selectMachinesToRemoveForContinuation(LhScheduleContext context,
                                                                         List<LhScheduleResult> skuResults,
                                                                         List<LhScheduleResult> keptResults) {
        List<LhScheduleResult> removedResults = new ArrayList<LhScheduleResult>(skuResults.size());
        for (LhScheduleResult result : skuResults) {
            if (!keptResults.contains(result)) {
                removedResults.add(result);
            }
        }
        removedResults.sort(Comparator
                .comparingInt((LhScheduleResult result) -> resolveCapsuleUsageCount(context, result))
                .thenComparing(Comparator.comparing(
                        (LhScheduleResult result) -> StringUtils.defaultString(result.getLhMachineCode())).reversed()));
        return removedResults;
    }

    /**
     * 构建续作降模保留排序。
     *
     * @param context 排程上下文
     * @return 保留排序比较器
     */
    private Comparator<LhScheduleResult> buildContinuationKeepComparator(LhScheduleContext context) {
        return Comparator
                .comparingInt((LhScheduleResult result) -> -resolveCapsuleUsageCount(context, result))
                .thenComparing(result -> StringUtils.defaultString(result.getLhMachineCode()));
    }

    /**
     * 解析结果机台胶囊使用次数。
     *
     * @param context 排程上下文
     * @param result 续作结果
     * @return 胶囊使用次数
     */
    private int resolveCapsuleUsageCount(LhScheduleContext context, LhScheduleResult result) {
        if (context == null || result == null || StringUtils.isEmpty(result.getLhMachineCode())) {
            return 0;
        }
        MachineScheduleDTO machine = context.getMachineScheduleMap().get(result.getLhMachineCode());
        return machine != null ? machine.getCapsuleUsageCount() : 0;
    }

    /**
     * 按保留机台和目标规则重分配续作计划量。
     *
     * @param context 排程上下文
     * @param sourceSku 来源SKU
     * @param skuResults 同SKU续作结果
     * @param keptResults 保留结果
     * @param capacityMap 机台日产能
     * @param demandQty 当日需保障量
     * @param shifts 班次列表
     */
    private void allocateContinuationQtyForKeptMachines(LhScheduleContext context,
                                                        SkuScheduleDTO sourceSku,
                                                        List<LhScheduleResult> skuResults,
                                                        List<LhScheduleResult> keptResults,
                                                        Map<LhScheduleResult, Integer> capacityMap,
                                                        int demandQty,
                                                        List<LhShiftConfigVO> shifts) {
        boolean ending = hasEndingResult(skuResults);
        ProductionQuantityPolicy policy = ProductionQuantityPolicy.from(sourceSku, ending);
        boolean fillKeptMachineCapacity = !ending
                && !policy.isStrictUpperLimit()
                && !CollectionUtils.isEmpty(sourceSku.getDailyPlanQuotaMap());
        int remainingDemandQty = Math.max(0, demandQty);
        for (LhScheduleResult result : keptResults) {
            int machineCapacity = Math.max(0, capacityMap.getOrDefault(result, ShiftFieldUtil.resolveScheduledQty(result)));
            int allocation = fillKeptMachineCapacity ? machineCapacity : Math.min(remainingDemandQty, machineCapacity);
            redistributeShiftQty(context, result, shifts, allocation);
            if (ending && policy.isStrictUpperLimit()) {
                capResultShiftQtyToTarget(context, result, shifts, allocation);
            }
            remainingDemandQty = Math.max(0, remainingDemandQty - allocation);
            log.info("续作多机台保留机台排量, materialCode: {}, machineCode: {}, allocation: {}, "
                            + "machineCapacity: {}, 是否补满班产: {}, 是否收尾: {}",
                    sourceSku.getMaterialCode(), result.getLhMachineCode(), allocation,
                    machineCapacity, fillKeptMachineCapacity, ending);
        }
        List<LhScheduleResult> removedResults = selectMachinesToRemoveForContinuation(context, skuResults, keptResults);
        for (LhScheduleResult result : removedResults) {
            boolean nightShiftProtected = applyNoMouldChangeNightFillBeforeRelease(
                    context, sourceSku, result, shifts, ending);
            if (!nightShiftProtected) {
                redistributeShiftQty(context, result, shifts, 0);
            }
        }
        log.info("续作多机台降模结果, materialCode: {}, 原始机台: {}, 保留机台: {}, 下机机台: {}, 原始机台明细: {}, "
                        + "保留机台明细: {}, 下机机台明细: {}, 原因: dayN保障量={}，按胶囊使用次数和机台编码排序",
                sourceSku.getMaterialCode(), joinMachineCodes(skuResults), joinMachineCodes(keptResults),
                joinMachineCodes(removedResults), formatContinuationMachineDetails(context, skuResults, capacityMap),
                formatContinuationMachineDetails(context, keptResults, capacityMap),
                formatContinuationMachineDetails(context, removedResults, capacityMap), demandQty);
    }

    /**
     * 严格目标量场景按末班回裁计划量。
     * <p>通用分配会按模数归整，收尾/仅补欠产不能因此超出业务目标量。</p>
     *
     * @param context 排程上下文
     * @param result 排程结果
     * @param shifts 班次列表
     * @param targetQty 目标量
     */
    private void capResultShiftQtyToTarget(LhScheduleContext context,
                                           LhScheduleResult result,
                                           List<LhShiftConfigVO> shifts,
                                           int targetQty) {
        int overQty = ShiftFieldUtil.resolveScheduledQty(result) - Math.max(0, targetQty);
        if (overQty <= 0 || CollectionUtils.isEmpty(shifts)) {
            return;
        }
        for (int index = shifts.size() - 1; index >= 0 && overQty > 0; index--) {
            LhShiftConfigVO shift = shifts.get(index);
            Integer shiftPlanQty = ShiftFieldUtil.getShiftPlanQty(result, shift.getShiftIndex());
            if (shiftPlanQty == null || shiftPlanQty <= 0) {
                continue;
            }
            int trimQty = Math.min(overQty, shiftPlanQty);
            trimShiftPlanQty(result, shift.getShiftIndex(), shiftPlanQty - trimQty);
            overQty -= trimQty;
        }
        refreshResultSummary(context, result, shifts);
    }

    /**
     * 严格收尾多机台结果最终总量复核。
     * <p>按天降模过程中可能因下机机台补当前班导致结果仍超过收尾目标，
     * 此处从下机优先级最低的机台开始回裁，保证落库前同组总量不突破业务目标。</p>
     *
     * @param context 排程上下文
     * @param sourceSku 来源SKU
     * @param skuResults 同SKU续作结果
     * @param shifts 全窗口班次
     */
    private void capStrictEndingContinuationGroupToTarget(LhScheduleContext context,
                                                          SkuScheduleDTO sourceSku,
                                                          List<LhScheduleResult> skuResults,
                                                          List<LhShiftConfigVO> shifts) {
        if (context == null || sourceSku == null || CollectionUtils.isEmpty(skuResults)
                || CollectionUtils.isEmpty(shifts) || !hasEndingResult(skuResults)) {
            return;
        }
        ProductionQuantityPolicy policy = ProductionQuantityPolicy.from(sourceSku, true);
        if (!policy.isStrictUpperLimit()) {
            return;
        }
        int targetQty = Math.max(0, sourceSku.resolveTargetScheduleQty());
        int totalPlanQty = skuResults.stream().mapToInt(ShiftFieldUtil::resolveScheduledQty).sum();
        int overQty = totalPlanQty - targetQty;
        if (overQty <= 0) {
            return;
        }
        List<LhScheduleResult> trimOrder = selectMachinesToRemoveForContinuation(
                context, skuResults, Collections.<LhScheduleResult>emptyList());
        for (LhScheduleResult result : trimOrder) {
            if (overQty <= 0) {
                break;
            }
            int currentQty = ShiftFieldUtil.resolveScheduledQty(result);
            if (currentQty <= 0) {
                continue;
            }
            int nextQty = Math.max(0, currentQty - overQty);
            capResultShiftQtyToTarget(context, result, shifts, nextQty);
            overQty -= currentQty - ShiftFieldUtil.resolveScheduledQty(result);
        }
        log.info("续作严格收尾最终收口, materialCode: {}, 目标量: {}, 原总量: {}, "
                        + "收口后总量: {}, 机台列表: {}",
                sourceSku.getMaterialCode(), targetQty, totalPlanQty,
                skuResults.stream().mapToInt(ShiftFieldUtil::resolveScheduledQty).sum(),
                joinMachineCodes(skuResults));
    }

    /**
     * 对全部续作业务分组执行严格收尾目标复核。
     * <p>日标准产量修正既可能回裁，也可能补足剩余班次；收尾结果在每次修正后都必须重新收口，
     * 防止单机和多机场景突破按模数归整后的收尾目标。</p>
     *
     * @param context 排程上下文
     * @param sourceSkuMap 分组来源SKU
     * @param skuResultMap 分组续作结果
     * @param skuOrder 分组顺序
     * @param shifts 全窗口班次
     */
    private void capStrictEndingContinuationGroupsToTarget(
            LhScheduleContext context,
            Map<String, SkuScheduleDTO> sourceSkuMap,
            Map<String, List<LhScheduleResult>> skuResultMap,
            List<String> skuOrder,
            List<LhShiftConfigVO> shifts) {
        if (CollectionUtils.isEmpty(skuOrder)) {
            return;
        }
        for (String groupKey : skuOrder) {
            capStrictEndingContinuationGroupToTarget(
                    context, sourceSkuMap.get(groupKey), skuResultMap.get(groupKey), shifts);
        }
    }

    /**
     * 应用指定业务日的续作多机台降模结果。
     *
     * @param context 排程上下文
     * @param sourceSku 来源SKU
     * @param activeResults 当前仍在机结果
     * @param keptResults 当日保留结果
     * @param capacityMap 当日产能
     * @param demandQty 当日保障量
     * @param productionDate 业务日
     * @param dayShifts 当日班次
     * @param allShifts 全窗口班次
     * @param recoverable 保留机台是否满足后续追补需求
     */
    private void applyContinuationDayAllocation(LhScheduleContext context,
                                                SkuScheduleDTO sourceSku,
                                                List<LhScheduleResult> activeResults,
                                                List<LhScheduleResult> keptResults,
                                                Map<LhScheduleResult, Integer> capacityMap,
                                                int demandQty,
                                                int effectiveDemandQty,
                                                int remainingTargetQty,
                                                LocalDate productionDate,
                                                List<LhShiftConfigVO> dayShifts,
                                                List<LhShiftConfigVO> allShifts,
                                                boolean recoverable) {
        boolean ending = hasEndingResult(activeResults);
        ProductionQuantityPolicy policy = ProductionQuantityPolicy.from(sourceSku, ending);
        boolean fillKeptMachineCapacity = !ending
                && !policy.isStrictUpperLimit()
                && !CollectionUtils.isEmpty(sourceSku.getDailyPlanQuotaMap());
        int remainingDemandQty = Math.max(0, effectiveDemandQty);
        for (LhScheduleResult result : keptResults) {
            int machineCapacity = Math.max(0, capacityMap.getOrDefault(result, 0));
            int allocation = effectiveDemandQty <= 0 ? 0
                    : fillKeptMachineCapacity ? machineCapacity : Math.min(remainingDemandQty, machineCapacity);
            redistributeShiftQty(context, result, dayShifts, allocation);
            remainingDemandQty = Math.max(0, remainingDemandQty - allocation);
            log.info("续作多机台保留机台排量, materialCode: {}, 日期: {}, machineCode: {}, allocation: {}, "
                            + "machineCapacity: {}, 是否补满班产: {}, 当日生效目标量: {}, 剩余窗口目标量: {}, 是否收尾: {}",
                    sourceSku.getMaterialCode(), productionDate, result.getLhMachineCode(), allocation,
                    machineCapacity, fillKeptMachineCapacity, effectiveDemandQty, remainingTargetQty, ending);
        }
        List<LhScheduleResult> supplementResults = policy.isStrictUpperLimit() && remainingDemandQty <= 0
                ? new ArrayList<LhScheduleResult>(0)
                : selectDaySupplementMachines(context, activeResults, keptResults);
        List<LhScheduleResult> removedResults = selectMachinesToRemoveForContinuation(context, activeResults, keptResults);
        for (LhScheduleResult result : supplementResults) {
            int machineCapacity = Math.max(0, capacityMap.getOrDefault(result, 0));
            int allocation = Math.min(remainingDemandQty, machineCapacity);
            redistributeShiftQty(context, result, dayShifts, allocation);
            if (allocation > 0) {
                if (!recoverable) {
                    applyNoMouldChangeNightFillBeforeRelease(context, sourceSku, result, allShifts, false);
                }
                // 保留机台已满足后续需求时直接降模；确有追补缺口时才保留不可换模晚班。
                clearContinuationShiftsAfterDate(
                        context, result, allShifts, productionDate, !recoverable);
            } else {
                // 当日无需补量时立即释放机台，不能因不可换模夜班再次保留原续作计划。
                clearContinuationShiftsFromDate(context, result, allShifts, productionDate);
            }
            remainingDemandQty = Math.max(0, remainingDemandQty - allocation);
            log.info("续作多机台当日补量下机机台排量, materialCode: {}, 日期: {}, machineCode: {}, allocation: {}, "
                            + "machineCapacity: {}, 当日剩余需求: {}",
                    sourceSku.getMaterialCode(), productionDate, result.getLhMachineCode(), allocation,
                    machineCapacity, remainingDemandQty);
        }
        for (LhScheduleResult result : removedResults) {
            if (keptResults.contains(result) || supplementResults.contains(result)) {
                continue;
            }
            redistributeShiftQty(context, result, dayShifts, 0);
            clearContinuationShiftsAfterDate(
                    context, result, allShifts, productionDate, !recoverable);
        }
        log.info("续作多机台降模结果, materialCode: {}, 日期: {}, 原始机台: {}, 保留机台: {}, 当日补量下机机台: {}, 下机机台: {}, 原始机台明细: {}, "
                        + "保留机台明细: {}, 下机机台明细: {}, 原因: dayN保障量={}，当日生效目标量={}，剩余窗口目标量={}，按胶囊使用次数和机台编码排序",
                sourceSku.getMaterialCode(), productionDate, joinMachineCodes(activeResults), joinMachineCodes(keptResults),
                joinMachineCodes(supplementResults), joinMachineCodes(removedResults),
                formatContinuationMachineDetails(context, activeResults, capacityMap),
                formatContinuationMachineDetails(context, keptResults, capacityMap),
                formatContinuationMachineDetails(context, removedResults, capacityMap),
                demandQty, effectiveDemandQty, remainingTargetQty);
    }

    /**
     * 选择当天补量后下机的机台。
     * <p>补量机台按续作保留优先级选择保留机台之后的下一批，确保当天补量仍优先使用胶囊次数更高的机台。</p>
     *
     * @param context 排程上下文
     * @param activeResults 当前仍在机结果
     * @param keptResults 后续保留结果
     * @return 当天补量下机机台
     */
    private List<LhScheduleResult> selectDaySupplementMachines(LhScheduleContext context,
                                                               List<LhScheduleResult> activeResults,
                                                               List<LhScheduleResult> keptResults) {
        List<LhScheduleResult> supplementResults = new ArrayList<LhScheduleResult>(activeResults.size());
        List<LhScheduleResult> sortedResults = new ArrayList<LhScheduleResult>(activeResults);
        sortedResults.sort(buildContinuationKeepComparator(context));
        for (LhScheduleResult result : sortedResults) {
            if (!keptResults.contains(result)) {
                supplementResults.add(result);
            }
        }
        return supplementResults;
    }

    /**
     * 清空结果从指定业务日起后的全部班次计划量。
     *
     * @param context 排程上下文
     * @param result 排程结果
     * @param shifts 全窗口班次
     * @param productionDate 起始业务日
     */
    private void clearContinuationShiftsFromDate(LhScheduleContext context,
                                                 LhScheduleResult result,
                                                 List<LhShiftConfigVO> shifts,
                                                 LocalDate productionDate) {
        List<LhShiftConfigVO> shiftsToClear = new ArrayList<LhShiftConfigVO>(4);
        for (LhShiftConfigVO shift : shifts) {
            if (shift == null || shift.getWorkDate() == null) {
                continue;
            }
            LocalDate shiftDate = shift.getWorkDate().toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
            if (!shiftDate.isBefore(productionDate)) {
                shiftsToClear.add(shift);
            }
        }
        clearShiftPlanQty(result, shiftsToClear);
        refreshResultSummary(context, result, shifts);
    }

    /**
     * 清空结果在指定业务日之后的全部班次计划量。
     *
     * @param context 排程上下文
     * @param result 排程结果
     * @param shifts 全窗口班次
     * @param productionDate 当前业务日
     * @param keepBoundaryNightShift 是否保留当前中班后的不可换模晚班
     */
    private void clearContinuationShiftsAfterDate(LhScheduleContext context,
                                                  LhScheduleResult result,
                                                  List<LhShiftConfigVO> shifts,
                                                  LocalDate productionDate,
                                                  boolean keepBoundaryNightShift) {
        List<LhShiftConfigVO> shiftsToClear = new ArrayList<LhShiftConfigVO>(4);
        for (LhShiftConfigVO shift : shifts) {
            if (shift == null || shift.getWorkDate() == null) {
                continue;
            }
            LocalDate shiftDate = shift.getWorkDate().toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
            if (shiftDate.isAfter(productionDate)) {
                if (keepBoundaryNightShift
                        && isBoundaryNoMouldChangeNightShiftToKeep(
                                context, result, shifts, shift, productionDate)) {
                    continue;
                }
                shiftsToClear.add(shift);
            }
        }
        clearShiftPlanQty(result, shiftsToClear);
        refreshResultSummary(context, result, shifts);
    }

    /**
     * 判断跨业务日夜班是否为当前业务日中班后需要保留的不可换模续作班。
     *
     * @param context 排程上下文
     * @param result 排程结果
     * @param shifts 全窗口班次
     * @param shift 待清理班次
     * @param productionDate 当前业务日
     * @return true-需要保留；false-可按后续业务日清理
     */
    private boolean isBoundaryNoMouldChangeNightShiftToKeep(LhScheduleContext context,
                                                            LhScheduleResult result,
                                                            List<LhShiftConfigVO> shifts,
                                                            LhShiftConfigVO shift,
                                                            LocalDate productionDate) {
        if (context == null || result == null || CollectionUtils.isEmpty(shifts) || shift == null
                || shift.getShiftStartDateTime() == null || shift.getShiftIndex() == null
                || !shift.isNightShift()
                || !LhScheduleTimeUtil.isNoMouldChangeTime(context, shift.getShiftStartDateTime())
                || resolveShiftPlanQty(result, shift.getShiftIndex()) <= 0) {
            return false;
        }
        LhShiftConfigVO previousShift = findShiftByIndex(shifts, shift.getShiftIndex() - 1);
        if (previousShift == null || previousShift.getWorkDate() == null
                || !StringUtils.equals(ShiftEnum.AFTERNOON_SHIFT.getCode(), previousShift.getShiftType())) {
            return false;
        }
        LocalDate previousShiftDate = previousShift.getWorkDate().toInstant()
                .atZone(ZoneId.systemDefault()).toLocalDate();
        return previousShiftDate.isEqual(productionDate);
    }

    /**
     * 续作机台准备下机前，处理中班结束后紧接不可换模晚班的补班。
     * <p>中班结束后如果直接释放机台，后续SKU在晚班无法换模开产，当前SKU已在机可继续无换模生产；
     * 收尾场景仍优先遵守目标量上限，只允许在剩余收尾目标量范围内补晚班。</p>
     *
     * @param context 排程上下文
     * @param sourceSku 来源SKU
     * @param result 准备释放的续作结果
     * @param shifts 全窗口班次
     * @param strictEnding 是否严格收尾
     * @return true-已保留或补充晚班；false-未命中规则
     */
    private boolean applyNoMouldChangeNightFillBeforeRelease(LhScheduleContext context,
                                                             SkuScheduleDTO sourceSku,
                                                             LhScheduleResult result,
                                                             List<LhShiftConfigVO> shifts,
                                                             boolean strictEnding) {
        if (context == null || sourceSku == null || result == null || CollectionUtils.isEmpty(shifts)) {
            return false;
        }
        int lastShiftIndex = resolveLastPlannedShiftIndex(result);
        if (lastShiftIndex <= 0) {
            return false;
        }
        LhShiftConfigVO currentShift = findShiftByIndex(shifts, lastShiftIndex);
        LhShiftConfigVO nextShift = findShiftByIndex(shifts, lastShiftIndex + 1);
        if (!isAfternoonToNoMouldChangeNightShift(context, currentShift, nextShift)) {
            return false;
        }
        if (isMachineShiftOccupiedByOtherSku(context, sourceSku, result, nextShift)) {
            log.info("续作中班下机晚班补满跳过, materialCode: {}, machineCode: {}, nightShift: {}, 原因: 下一晚班已被其他SKU占用",
                    sourceSku.getMaterialCode(), result.getLhMachineCode(), nextShift.getShiftIndex());
            return false;
        }
        int currentShiftBeforeQty = resolveShiftPlanQty(result, currentShift.getShiftIndex());
        int currentShiftAvailableQty = Math.max(0,
                calculateResultShiftCapacity(context, result, currentShift) - currentShiftBeforeQty);
        int nightShiftBeforeQty = resolveShiftPlanQty(result, nextShift.getShiftIndex());
        int nightShiftAvailableQty = Math.max(0,
                calculateResultShiftCapacity(context, result, nextShift) - nightShiftBeforeQty);
        int fillLimitQty = strictEnding
                ? resolveRemainingEndingQtyForContinuationGroup(context, sourceSku)
                : currentShiftAvailableQty + nightShiftAvailableQty;
        int remainingFillLimitQty = Math.max(0, fillLimitQty);
        // 晚班不可换模释放前，当前中班仍可生产的产能先补满，再保留下一晚班续作。
        int currentShiftFillQty = Math.min(currentShiftAvailableQty, remainingFillLimitQty);
        if (currentShiftFillQty > 0) {
            Date currentShiftStartTime = ShiftFieldUtil.getShiftStartTime(result, currentShift.getShiftIndex());
            setShiftPlanQty(result, currentShift.getShiftIndex(), currentShiftBeforeQty + currentShiftFillQty,
                    currentShiftStartTime == null ? currentShift.getShiftStartDateTime() : currentShiftStartTime,
                    currentShift.getShiftEndDateTime());
            remainingFillLimitQty = Math.max(0, remainingFillLimitQty - currentShiftFillQty);
        }
        int fillQty = Math.min(nightShiftAvailableQty, remainingFillLimitQty);
        if (fillQty <= 0 && currentShiftFillQty <= 0) {
            return nightShiftBeforeQty > 0 || currentShiftBeforeQty > 0;
        }
        if (fillQty > 0) {
            setShiftPlanQty(result, nextShift.getShiftIndex(), nightShiftBeforeQty + fillQty,
                    nextShift.getShiftStartDateTime(), null);
        }
        refreshResultSummary(context, result, shifts);
        log.info("续作中班下机晚班补满命中, materialCode: {}, machineCode: {}, 当前班次: {}, 晚班班次: {}, "
                        + "当前班次补前: {}, 当前班次补后: {}, 晚班补前: {}, 晚班补后: {}, "
                        + "补满数量: {}, 是否严格收尾: {}, 原因: 晚班不可换模且当前SKU可无换模续作",
                sourceSku.getMaterialCode(), result.getLhMachineCode(), lastShiftIndex, nextShift.getShiftIndex(),
                currentShiftBeforeQty, currentShiftBeforeQty + currentShiftFillQty,
                nightShiftBeforeQty, nightShiftBeforeQty + fillQty, currentShiftFillQty + fillQty, strictEnding);
        return true;
    }

    /**
     * 判断当前班次是否为中班且下一班次为不可换模晚班。
     *
     * @param context 排程上下文
     * @param currentShift 当前最后有量班次
     * @param nextShift 下一班次
     * @return true-中班后紧接不可换模晚班
     */
    private boolean isAfternoonToNoMouldChangeNightShift(LhScheduleContext context,
                                                         LhShiftConfigVO currentShift,
                                                         LhShiftConfigVO nextShift) {
        return context != null
                && currentShift != null
                && nextShift != null
                && nextShift.getShiftStartDateTime() != null
                && StringUtils.equals(ShiftEnum.AFTERNOON_SHIFT.getCode(), currentShift.getShiftType())
                && nextShift.isNightShift()
                && !nextShift.isAllowMouldChange()
                && LhScheduleTimeUtil.isNoMouldChangeTime(context, nextShift.getShiftStartDateTime());
    }

    /**
     * 计算续作共享账本组剩余收尾目标量。
     *
     * @param context 排程上下文
     * @param sourceSku 来源SKU
     * @return 剩余可补量
     */
    private int resolveRemainingEndingQtyForContinuationGroup(LhScheduleContext context, SkuScheduleDTO sourceSku) {
        // 共用胎胚收尾只按硫化余量，不按胎胚库存
        int endingDemandQty;
        if (getTargetScheduleQtyResolver().isSharedEmbryoInWindow(context, sourceSku)) {
            endingDemandQty = Math.max(0, sourceSku.getSurplusQty());
        } else {
            endingDemandQty = Math.max(Math.max(0, sourceSku.getSurplusQty()), Math.max(0, sourceSku.getEmbryoStock()));
        }
        int scheduledQty = resolveEffectiveContinuousPhaseScheduledQty(context, buildContinuationGroupKey(sourceSku));
        return Math.max(0, endingDemandQty - scheduledQty);
    }

    /**
     * 判断准备补晚班的机台班次是否已被其他SKU占用。
     *
     * @param context 排程上下文
     * @param sourceSku 当前SKU
     * @param currentResult 当前结果
     * @param targetShift 目标晚班
     * @return true-其他SKU已占用
     */
    private boolean isMachineShiftOccupiedByOtherSku(LhScheduleContext context,
                                                     SkuScheduleDTO sourceSku,
                                                     LhScheduleResult currentResult,
                                                     LhShiftConfigVO targetShift) {
        if (context == null || sourceSku == null || currentResult == null || targetShift == null
                || StringUtils.isEmpty(currentResult.getLhMachineCode())
                || targetShift.getShiftIndex() == null
                || CollectionUtils.isEmpty(context.getScheduleResultList())) {
            return false;
        }
        for (LhScheduleResult result : context.getScheduleResultList()) {
            if (result == null || result == currentResult
                    || !StringUtils.equals(currentResult.getLhMachineCode(), result.getLhMachineCode())) {
                continue;
            }
            Integer planQty = ShiftFieldUtil.getShiftPlanQty(result, targetShift.getShiftIndex());
            if (planQty == null || planQty <= 0) {
                continue;
            }
            if (!StringUtils.equals(sourceSku.getMaterialCode(), result.getMaterialCode())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 汇总指定班次集合内的计划量。
     *
     * @param results 结果列表
     * @param shifts 班次列表
     * @return 班次计划量合计
     */
    private int sumScheduledQtyByShifts(List<LhScheduleResult> results, List<LhShiftConfigVO> shifts) {
        if (CollectionUtils.isEmpty(results) || CollectionUtils.isEmpty(shifts)) {
            return 0;
        }
        int totalQty = 0;
        for (LhScheduleResult result : results) {
            for (LhShiftConfigVO shift : shifts) {
                Integer planQty = ShiftFieldUtil.getShiftPlanQty(result, shift.getShiftIndex());
                if (planQty != null && planQty > 0) {
                    totalQty += planQty;
                }
            }
        }
        return totalQty;
    }

    /**
     * 续作同SKU多机台同班次尾量归集。
     *
     * @param context 排程上下文
     * @param shifts 班次列表
     */
    private void adjustContinuousSameSkuMultiMachineEndingStagger(LhScheduleContext context,
                                                                  List<LhShiftConfigVO> shifts) {
        if (context == null || CollectionUtils.isEmpty(context.getScheduleResultList())
                || CollectionUtils.isEmpty(shifts)) {
            return;
        }
        Map<String, List<LhScheduleResult>> groupResultMap = new LinkedHashMap<String, List<LhScheduleResult>>(8);
        Map<String, SkuScheduleDTO> sourceSkuMap = new LinkedHashMap<String, SkuScheduleDTO>(8);
        for (LhScheduleResult result : context.getScheduleResultList()) {
            if (!isPureContinuousResult(result)
                    || ShiftFieldUtil.resolveScheduledQty(result) <= 0) {
                continue;
            }
            SkuScheduleDTO sourceSku = resolveResultSourceSku(context, result);
            if (sourceSku == null) {
                continue;
            }
            String groupKey = buildContinuationGroupKey(sourceSku);
            groupResultMap.computeIfAbsent(groupKey, key -> new ArrayList<LhScheduleResult>(2)).add(result);
            sourceSkuMap.putIfAbsent(groupKey, sourceSku);
        }
        for (Map.Entry<String, List<LhScheduleResult>> entry : groupResultMap.entrySet()) {
            List<LhScheduleResult> results = entry.getValue();
            if (results.size() < 2) {
                continue;
            }
            SkuScheduleDTO sourceSku = sourceSkuMap.get(entry.getKey());
            Map<Integer, List<LhScheduleResult>> shiftResultMap = new LinkedHashMap<Integer, List<LhScheduleResult>>(4);
            for (LhScheduleResult result : results) {
                int lastShiftIndex = resolveLastPlannedShiftIndex(result);
                if (lastShiftIndex <= 0) {
                    continue;
                }
                shiftResultMap.computeIfAbsent(lastShiftIndex, key -> new ArrayList<LhScheduleResult>(2)).add(result);
            }
            for (Map.Entry<Integer, List<LhScheduleResult>> shiftEntry : shiftResultMap.entrySet()) {
                if (shiftEntry.getValue().size() < 2) {
                    continue;
                }
                tryAggregateContinuousSameShiftEnding(context, sourceSku, shifts,
                        shiftEntry.getKey(), shiftEntry.getValue());
            }
        }
    }

    /**
     * 尝试归集续作同SKU同班次尾量。
     *
     * @param context 排程上下文
     * @param sourceSku 来源SKU
     * @param shifts 班次列表
     * @param endingShiftIndex 收尾班次索引
     * @param results 同班次收尾结果
     * @return true-已归集
     */
    private boolean tryAggregateContinuousSameShiftEnding(LhScheduleContext context,
                                                          SkuScheduleDTO sourceSku,
                                                          List<LhShiftConfigVO> shifts,
                                                          int endingShiftIndex,
                                                          List<LhScheduleResult> results) {
        LhShiftConfigVO endingShift = findShiftByIndex(shifts, endingShiftIndex);
        if (endingShift == null) {
            return false;
        }
        List<LhScheduleResult> sortedResults = new ArrayList<LhScheduleResult>(results);
        sortedResults.sort(buildContinuationKeepComparator(context));
        int totalShiftQty = sumScheduledQtyByShifts(results, Collections.singletonList(endingShift));
        if (totalShiftQty <= 0) {
            return false;
        }
        log.info("续作同SKU同班次尾量归集判断, materialCode: {}, 收尾班次: {}, 归集排序: {}, 同班次总量: {}",
                sourceSku != null ? sourceSku.getMaterialCode() : null,
                endingShiftIndex, joinMachineCodes(sortedResults), totalShiftQty);

        int remainingQty = totalShiftQty;
        boolean changed = false;
        for (int index = 0; index < sortedResults.size(); index++) {
            LhScheduleResult result = sortedResults.get(index);
            int existingQty = resolveShiftPlanQty(result, endingShiftIndex);
            int allocatableQty = resolveSameShiftEndingAllocatableQty(context, result, endingShift);
            int targetQty;
            if (index == sortedResults.size() - 1) {
                targetQty = remainingQty;
            } else {
                targetQty = Math.min(remainingQty, allocatableQty);
            }
            if (targetQty != existingQty) {
                changed = true;
            }
            if (targetQty > 0) {
                setShiftPlanQty(result, endingShiftIndex, targetQty,
                        endingShift.getShiftStartDateTime(), endingShift.getShiftEndDateTime());
            } else {
                setShiftPlanQty(result, endingShiftIndex, 0, null, null);
            }
            refreshResultSummary(context, result, shifts);
            remainingQty -= targetQty;
        }
        log.info("续作同SKU同班次尾量归集完成, materialCode: {}, 收尾班次: {}, 归集结果: {}, 同班次总量: {}",
                sourceSku != null ? sourceSku.getMaterialCode() : null,
                endingShiftIndex, joinMachineCodes(sortedResults), totalShiftQty);
        return changed;
    }

    /**
     * 按班次序号查找排程窗口班次。
     *
     * @param shifts 班次列表
     * @param shiftIndex 班次序号
     * @return 班次配置，未找到返回null
     */
    private LhShiftConfigVO findShiftByIndex(List<LhShiftConfigVO> shifts, int shiftIndex) {
        if (CollectionUtils.isEmpty(shifts)) {
            return null;
        }
        for (LhShiftConfigVO shift : shifts) {
            if (shift != null && shift.getShiftIndex() == shiftIndex) {
                return shift;
            }
        }
        return null;
    }

    /**
     * 解析结果行最后一个有计划量的班次序号。
     *
     * @param result 排程结果
     * @return 最后有量班次序号，未找到返回-1
     */
    private int resolveLastPlannedShiftIndex(LhScheduleResult result) {
        if (result == null) {
            return -1;
        }
        for (int shiftIndex = LhScheduleConstant.MAX_SHIFT_SLOT_COUNT; shiftIndex >= 1; shiftIndex--) {
            Integer planQty = ShiftFieldUtil.getShiftPlanQty(result, shiftIndex);
            if (planQty != null && planQty > 0) {
                return shiftIndex;
            }
        }
        return -1;
    }

    /**
     * 解析结果在指定班次的当前计划量。
     *
     * @param result 排程结果
     * @param shiftIndex 班次序号
     * @return 当前计划量
     */
    private int resolveShiftPlanQty(LhScheduleResult result, int shiftIndex) {
        Integer shiftQty = ShiftFieldUtil.getShiftPlanQty(result, shiftIndex);
        return shiftQty != null ? shiftQty : 0;
    }

    /**
     * 解析同班次尾量归集时单台机台可承接的最大计划量。
     *
     * @param context 排程上下文
     * @param result 续作结果
     * @param shift 收尾班次
     * @return 可承接计划量
     */
    private int resolveSameShiftEndingAllocatableQty(LhScheduleContext context,
                                                     LhScheduleResult result,
                                                     LhShiftConfigVO shift) {
        int existingQty = resolveShiftPlanQty(result, shift.getShiftIndex());
        int shiftCapacity = calculateResultShiftCapacity(context, result, shift);
        return Math.max(existingQty, shiftCapacity);
    }

    /**
     * 从同优先级候选SKU中选择首个SKU。
     * <p>续作候选顺序已在上游按月度计划和结构优先级排好，此处不因收尾状态插队。</p>
     *
     * @param context 排程上下文
     * @param candidates 候选SKU
     * @return 首选SKU；候选为空时返回 null
     */
    private SkuScheduleDTO selectPreferredSkuFromCandidates(LhScheduleContext context,
                                                            List<SkuScheduleDTO> candidates) {
        if (CollectionUtils.isEmpty(candidates)) {
            return null;
        }
        return candidates.get(0);
    }

    /**
     * 解析定点机台挤量的切换开始时间。
     *
     * @param context 排程上下文
     * @param machine 当前机台
     * @param currentSku 当前续作SKU
     * @param shifts 排程窗口班次
     * @return 切换开始时间，未触发挤量返回null
     */
    private Date tryReserveSpecifySqueezeSwitchStartTime(LhScheduleContext context,
                                                         MachineScheduleDTO machine,
                                                         SkuScheduleDTO currentSku,
                                                         List<LhShiftConfigVO> shifts) {
        if (context == null || machine == null || currentSku == null || CollectionUtils.isEmpty(shifts)
                || StringUtils.isEmpty(machine.getMachineCode())
                || StringUtils.isEmpty(currentSku.getMaterialCode())) {
            return null;
        }
        String machineCode = machine.getMachineCode();
        if (LhSpecifyMachineUtil.isLimitSpecifyMachine(context, machineCode, currentSku.getMaterialCode())) {
            return null;
        }
        SkuScheduleDTO specifySku = selectLimitSpecifySkuByMachine(context, machine);
        if (specifySku == null) {
            return null;
        }
        Date firstLastWorkDayShiftStartTime = resolveFirstLastWorkDayShiftStartTime(shifts);
        if (firstLastWorkDayShiftStartTime == null) {
            log.debug("定点机台挤量跳过, machineCode: {}, materialCode: {}, 原因: 最后业务日无可排班次",
                    machineCode, specifySku.getMaterialCode());
            return null;
        }
        int switchHours = isTypeBlockCandidate(context, machine, specifySku)
                ? LhScheduleTimeUtil.getTypeBlockChangeTotalHours(context)
                : LhScheduleTimeUtil.getMouldChangeTotalHours(context);
        Date switchStartTime = LhScheduleTimeUtil.addHours(firstLastWorkDayShiftStartTime, -switchHours);
        switchStartTime = resolveLatestAllowedSwitchStartTime(context, switchStartTime);
        List<LhShiftConfigVO> retainedShifts = filterShiftsBeforeSwitchStart(shifts, switchStartTime);
        if (CollectionUtils.isEmpty(retainedShifts)) {
            log.debug("定点机台挤量跳过, machineCode: {}, materialCode: {}, 原因: 当前SKU无可保留班次",
                    machineCode, specifySku.getMaterialCode());
            return null;
        }
        if (!canScheduleSpecifySkuOnMachine(context, machine, specifySku, shifts, switchStartTime)) {
            log.info("定点机台挤量跳过, machineCode: {}, materialCode: {}, 原因: 定点物料无法在预留机台正常排产",
                    machineCode, specifySku.getMaterialCode());
            return null;
        }
        reserveSpecifySqueeze(context, machineCode, specifySku.getMaterialCode(), switchStartTime);
        return switchStartTime;
    }

    /**
     * 回写定点机台挤量预留信息。
     *
     * @param context 排程上下文
     * @param machineCode 机台编码
     * @param materialCode 预留物料编码
     * @param switchStartTime 预留切换开始时间
     */
    private void reserveSpecifySqueeze(LhScheduleContext context,
                                       String machineCode,
                                       String materialCode,
                                       Date switchStartTime) {
        if (context == null || StringUtils.isEmpty(machineCode)
                || StringUtils.isEmpty(materialCode) || switchStartTime == null) {
            return;
        }
        context.getSpecifyMachineReservedMaterialMap().put(machineCode, materialCode);
        context.getSpecifyMachineReservedSwitchStartTimeMap().put(machineCode, switchStartTime);
    }

    /**
     * 过滤切换开始时间之前完整可用的班次。
     *
     * @param shifts 原排程窗口班次
     * @param switchStartTime 切换开始时间
     * @return 保留班次
     */
    private List<LhShiftConfigVO> filterShiftsBeforeSwitchStart(List<LhShiftConfigVO> shifts, Date switchStartTime) {
        if (CollectionUtils.isEmpty(shifts) || switchStartTime == null) {
            return new ArrayList<>(0);
        }
        List<LhShiftConfigVO> retainedShifts = new ArrayList<>(shifts.size());
        for (LhShiftConfigVO shift : shifts) {
            if (shift == null || shift.getShiftEndDateTime() == null) {
                continue;
            }
            if (!shift.getShiftEndDateTime().after(switchStartTime)) {
                retainedShifts.add(shift);
            }
        }
        return retainedShifts;
    }

    /**
     * 选择当前机台配置的限制作业定点SKU。
     *
     * @param context 排程上下文
     * @param machine 当前机台
     * @return 定点SKU，未命中返回null
     */
    private SkuScheduleDTO selectLimitSpecifySkuByMachine(LhScheduleContext context, MachineScheduleDTO machine) {
        if (context == null || machine == null || StringUtils.isEmpty(machine.getMachineCode())
                || CollectionUtils.isEmpty(context.getNewSpecSkuList())) {
            return null;
        }
        String machineCode = machine.getMachineCode();
        for (SkuScheduleDTO sku : context.getNewSpecSkuList()) {
            if (sku == null || StringUtils.isEmpty(sku.getMaterialCode()) || sku.resolveTargetScheduleQty() <= 0) {
                continue;
            }
            if (LhSpecifyMachineUtil.isLimitSpecifyMachine(context, machineCode, sku.getMaterialCode())) {
                return sku;
            }
        }
        return null;
    }

    /**
     * 解析排程窗口最后业务日的首个班次开始时间。
     *
     * @param shifts 排程窗口班次
     * @return 首个班次开始时间
     */
    private Date resolveFirstLastWorkDayShiftStartTime(List<LhShiftConfigVO> shifts) {
        Date lastWorkDate = null;
        for (LhShiftConfigVO shift : shifts) {
            if (shift == null || shift.getWorkDate() == null) {
                continue;
            }
            Date workDate = LhScheduleTimeUtil.clearTime(shift.getWorkDate());
            if (lastWorkDate == null || workDate.after(lastWorkDate)) {
                lastWorkDate = workDate;
            }
        }
        if (lastWorkDate == null) {
            return null;
        }
        Date firstShiftStartTime = null;
        for (LhShiftConfigVO shift : shifts) {
            if (shift == null || shift.getWorkDate() == null || shift.getShiftStartDateTime() == null) {
                continue;
            }
            Date workDate = LhScheduleTimeUtil.clearTime(shift.getWorkDate());
            if (!lastWorkDate.equals(workDate)) {
                continue;
            }
            Date shiftStartTime = shift.getShiftStartDateTime();
            if (firstShiftStartTime == null || shiftStartTime.before(firstShiftStartTime)) {
                firstShiftStartTime = shiftStartTime;
            }
        }
        return firstShiftStartTime;
    }

    /**
     * 反推不晚于候选时间的最晚合法切换开始时间。
     *
     * @param context 排程上下文
     * @param candidateStartTime 候选切换开始时间
     * @return 合法切换开始时间
     */
    private Date resolveLatestAllowedSwitchStartTime(LhScheduleContext context, Date candidateStartTime) {
        if (candidateStartTime == null || !LhScheduleTimeUtil.isNoMouldChangeTime(context, candidateStartTime)) {
            return candidateStartTime;
        }
        java.util.Calendar calendar = java.util.Calendar.getInstance();
        calendar.setTime(candidateStartTime);
        int hour = calendar.get(java.util.Calendar.HOUR_OF_DAY);
        Date baseDate = LhScheduleTimeUtil.clearTime(candidateStartTime);
        if (hour < LhScheduleTimeUtil.getMorningStartHour(context)) {
            baseDate = LhScheduleTimeUtil.addDays(baseDate, -1);
        }
        return LhScheduleTimeUtil.buildTime(baseDate, LhScheduleTimeUtil.getNoMouldChangeStartHour(context), 0, 0);
    }

    /**
     * 判断SKU是否满足换活字块条件：同胎胚、同规格、不同花纹。
     */
    private boolean isTypeBlockCandidate(LhScheduleContext context,
                                         MachineScheduleDTO machine,
                                         SkuScheduleDTO sku) {
        if (sku == null) {
            return false;
        }
        if (!isSameEmbryo(context, machine, sku)) {
            return false;
        }
        String machineSpecCode = resolveMachineSpecCode(context, machine);
        String machinePatternKey = resolveMachinePatternKey(context, machine);
        String skuPatternKey = resolvePatternKey(sku.getMainPattern(), sku.getPattern());
        if (StringUtils.isEmpty(machineSpecCode)
                || StringUtils.isEmpty(machinePatternKey)
                || StringUtils.isEmpty(sku.getSpecCode())
                || StringUtils.isEmpty(skuPatternKey)) {
            return false;
        }
        return StringUtils.equals(machineSpecCode, sku.getSpecCode())
                && !StringUtils.equals(machinePatternKey, skuPatternKey);
    }

    /**
     * 判断机台当前物料与候选SKU是否为相同胎胚。
     */
    private boolean isSameEmbryo(LhScheduleContext context, MachineScheduleDTO machine, SkuScheduleDTO sku) {
        String machineEmbryoCode = resolveMachineEmbryoCode(context, machine);
        return StringUtils.isNotEmpty(machineEmbryoCode)
                && StringUtils.isNotEmpty(sku.getEmbryoCode())
                && StringUtils.equals(machineEmbryoCode, sku.getEmbryoCode());
    }

    /**
     * 基于指定收尾时间计算换活字块开产时间。
     */
    private Date calcTypeBlockStartTime(LhScheduleContext context,
                                        MachineScheduleDTO machine,
                                        Date estimatedEndTime) {
        if (machine == null || estimatedEndTime == null) {
            return null;
        }
        Date switchStartTime = calcTypeBlockSwitchStartTime(context, machine, estimatedEndTime);
        return resolveTypeBlockProductionStartTime(context, machine, estimatedEndTime, switchStartTime);
    }

    /**
     * 基于指定收尾时间计算换活字块开始时间。
     */
    private Date calcTypeBlockSwitchStartTime(LhScheduleContext context,
                                              MachineScheduleDTO machine,
                                              Date estimatedEndTime) {
        if (machine == null || estimatedEndTime == null) {
            return null;
        }
        if (getMaintenanceScheduleService().shouldApplyMaintenanceOverlapSwitchRule(context, machine, estimatedEndTime)) {
            return getMaintenanceScheduleService().resolveMaintenanceEndTime(context, machine);
        }
        Date switchStartTime = resolveAllowedSwitchStartTime(
                context, machine.getMachineCode(), estimatedEndTime);
        switchStartTime = getMaintenanceScheduleService().delaySwitchStartByMaintenance(
                machine, switchStartTime, LhScheduleTimeUtil.getTypeBlockChangeTotalHours(context));
        return switchStartTime;
    }

    /**
     * 基于换活字块开始时间计算开产时间。
     */
    private Date resolveTypeBlockProductionStartTime(LhScheduleContext context,
                                                     MachineScheduleDTO machine,
                                                     Date estimatedEndTime,
                                                     Date switchStartTime) {
        if (switchStartTime == null) {
            return null;
        }
        if (getMaintenanceScheduleService().shouldApplyMaintenanceOverlapSwitchRule(context, machine, estimatedEndTime)) {
            Date inspectionStartTime = LhScheduleTimeUtil.addHours(
                    switchStartTime, LhScheduleTimeUtil.getMaintenanceOverlapSwitchHours(context));
            return LhScheduleTimeUtil.addHours(
                    inspectionStartTime, LhScheduleTimeUtil.getFirstInspectionHours(context));
        }
        return LhScheduleTimeUtil.addHours(
                switchStartTime, LhScheduleTimeUtil.getTypeBlockChangeTotalHours(context));
    }

    /**
     * 解析允许发起切换（换模/换活字块）的开始时间。
     * <p>20:00:00 允许发起切换，20:00:00 之后到次日早班前需顺延到下一个早班开始时间。</p>
     */
    private Date resolveAllowedSwitchStartTime(LhScheduleContext context,
                                               String machineCode,
                                               Date endingTime) {
        if (endingTime == null) {
            return null;
        }
        Date adjustedTime = endingTime;
        for (int attempt = 0; attempt < TYPE_BLOCK_SWITCH_MAX_ATTEMPTS; attempt++) {
            Date downtimeAdjustedTime = resolveDowntimeAdjustedSwitchStartTime(
                    context, machineCode, adjustedTime);
            if (downtimeAdjustedTime.after(adjustedTime)) {
                adjustedTime = downtimeAdjustedTime;
                continue;
            }
            if (!LhScheduleTimeUtil.isNoMouldChangeTime(context, adjustedTime)) {
                return adjustedTime;
            }
            adjustedTime = LhScheduleTimeUtil.resolveNextMorningAfterNoMouldChangeWindow(context, adjustedTime);
        }
        log.warn("换活字块切换起点达到最大尝试次数, 机台: {}, 原始时间: {}",
                machineCode, LhScheduleTimeUtil.formatDateTime(endingTime));
        return adjustedTime;
    }

    /**
     * 根据停机窗口顺延换活字块切换起点。
     * <p>当候选切换窗口与机台停机窗口重叠时，顺延到重叠停机结束时刻。</p>
     */
    private Date resolveDowntimeAdjustedSwitchStartTime(LhScheduleContext context,
                                                        String machineCode,
                                                        Date candidateStartTime) {
        if (context == null
                || StringUtils.isEmpty(machineCode)
                || candidateStartTime == null) {
            return candidateStartTime;
        }
        Date candidateEndTime = LhScheduleTimeUtil.addHours(
                candidateStartTime, LhScheduleTimeUtil.getTypeBlockChangeTotalHours(context));
        Date latestOverlapEndTime = null;
        if (!CollectionUtils.isEmpty(context.getDevicePlanShutList())) {
            for (MdmDevicePlanShut planShut : context.getDevicePlanShutList()) {
                if (planShut == null
                        || !StringUtils.equals(machineCode, planShut.getMachineCode())
                        || planShut.getBeginDate() == null
                        || planShut.getEndDate() == null
                        || !planShut.getBeginDate().before(planShut.getEndDate())) {
                    continue;
                }
                if (!candidateStartTime.before(planShut.getEndDate())
                        || !planShut.getBeginDate().before(candidateEndTime)) {
                    continue;
                }
                if (latestOverlapEndTime == null || planShut.getEndDate().after(latestOverlapEndTime)) {
                    latestOverlapEndTime = planShut.getEndDate();
                }
            }
        }
        return latestOverlapEndTime != null ? latestOverlapEndTime : candidateStartTime;
    }

    /**
     * 判断定点物料在当前机台和窗口内是否可排。
     * <p>这里仅做预判，不落正式结果，也不改变主流程状态。</p>
     *
     * @param context 排程上下文
     * @param machine 当前机台
     * @param specifySku 定点物料
     * @param shifts 排程窗口班次
     * @param endingTime 机台切换起点
     * @return true-可排，false-不可排
     */
    private boolean canScheduleSpecifySkuOnMachine(LhScheduleContext context,
                                                   MachineScheduleDTO machine,
                                                   SkuScheduleDTO specifySku,
                                                   List<LhShiftConfigVO> shifts,
                                                   Date endingTime) {
        if (context == null
                || machine == null
                || specifySku == null
                || endingTime == null
                || CollectionUtils.isEmpty(shifts)
                || StringUtils.isEmpty(machine.getMachineCode())) {
            return false;
        }
        if (isTypeBlockCandidate(context, machine, specifySku)) {
            Date typeBlockSwitchStartTime = calcTypeBlockSwitchStartTime(context, machine, endingTime);
            Date typeBlockStartTime = resolveTypeBlockProductionStartTime(
                    context, machine, endingTime, typeBlockSwitchStartTime);
            if (typeBlockStartTime == null || typeBlockSwitchStartTime == null) {
                return false;
            }
            int refinedTargetQty = getTargetScheduleQtyResolver().refineTargetQtyByMachineCapacity(
                    context,
                    specifySku,
                    machine,
                    typeBlockSwitchStartTime,
                    typeBlockStartTime,
                    shifts,
                    ScheduleTypeEnum.TYPE_BLOCK.getCode());
            if (refinedTargetQty <= 0) {
                log.debug("定点物料换活字块预判不可排, machineCode: {}, materialCode: {}, startTime: {}",
                        machine.getMachineCode(), specifySku.getMaterialCode(),
                        LhScheduleTimeUtil.formatDateTime(typeBlockStartTime));
                return false;
            }
            return true;
        }
        return canScheduleSpecifySkuByNewSpecPath(context, machine, specifySku, shifts, endingTime);
    }

    /**
     * 按新增换模链路预判定点物料是否可排。
     *
     * @param context 排程上下文
     * @param machine 当前机台
     * @param specifySku 定点物料
     * @param shifts 排程窗口班次
     * @param endingTime 机台切换起点
     * @return true-可排，false-不可排
     */
    private boolean canScheduleSpecifySkuByNewSpecPath(LhScheduleContext context,
                                                       MachineScheduleDTO machine,
                                                       SkuScheduleDTO specifySku,
                                                       List<LhShiftConfigVO> shifts,
                                                       Date endingTime) {
        Date machineReadyTime = getCapacityCalculateStrategy().calculateStartTime(
                context, machine.getMachineCode(), endingTime);
        boolean maintenanceOverlapSwitch = getMaintenanceScheduleService()
                .shouldApplyMaintenanceOverlapSwitchRule(context, machine, endingTime);
        Date switchReadyTime = maintenanceOverlapSwitch
                ? getMaintenanceScheduleService().resolveMaintenanceEndTime(context, machine)
                : machineReadyTime;
        switchReadyTime = ShiftProductionControlUtil.resolveEarliestSwitchStartTime(context, switchReadyTime);
        int switchDurationHours = maintenanceOverlapSwitch
                ? LhScheduleTimeUtil.getMaintenanceOverlapSwitchHours(context)
                : LhScheduleTimeUtil.getMouldChangeTotalHours(context);
        Date mouldChangeStartTime = getMouldChangeBalanceStrategy().allocateMouldChange(
                context,
                machine.getMachineCode(),
                switchReadyTime,
                switchDurationHours,
                specifySku,
                IMouldChangeBalanceStrategy.ACTION_NEW_SPEC_MOULD_CHANGE);
        if (mouldChangeStartTime == null) {
            log.debug("定点物料新增换模预判不可排, machineCode: {}, materialCode: {}, 原因: 无可用换模窗口",
                    machine.getMachineCode(), specifySku.getMaterialCode());
            return false;
        }
        Date inspectionTime = null;
        try {
            Date mouldChangeCompleteTime = LhScheduleTimeUtil.addHours(mouldChangeStartTime, switchDurationHours);
            inspectionTime = getFirstInspectionBalanceStrategy().allocateInspection(
                    context, machine.getMachineCode(), mouldChangeCompleteTime);
            if (inspectionTime == null) {
                log.debug("定点物料新增换模预判不可排, machineCode: {}, materialCode: {}, 原因: 首检窗口分配失败",
                        machine.getMachineCode(), specifySku.getMaterialCode());
                return false;
            }
            Date productionStartTime = maintenanceOverlapSwitch
                    ? LhScheduleTimeUtil.addHours(inspectionTime, LhScheduleTimeUtil.getFirstInspectionHours(context))
                    : inspectionTime;
            int machineMouldQty = ShiftCapacityResolverUtil.resolveMachineMouldQty(machine);
            int runtimeShiftCapacity = ShiftCapacityResolverUtil.resolveRuntimeShiftCapacity(
                    context, machine, specifySku.getShiftCapacity());
            Date firstProductionStartTime = ShiftProductionControlUtil.resolveFirstSchedulableStartIgnoringCleaning(
                    context,
                    machine.getMachineCode(),
                    productionStartTime,
                    shifts,
                    runtimeShiftCapacity,
                    specifySku.getLhTimeSeconds(),
                    machineMouldQty);
            if (firstProductionStartTime == null) {
                log.debug("定点物料新增换模预判不可排, machineCode: {}, materialCode: {}, 原因: 窗口内无可开产时间",
                        machine.getMachineCode(), specifySku.getMaterialCode());
                return false;
            }
            int refinedTargetQty = getTargetScheduleQtyResolver().refineTargetQtyByMachineCapacity(
                    context, specifySku, machine, mouldChangeStartTime, firstProductionStartTime,
                    shifts, ScheduleTypeEnum.NEW_SPEC.getCode());
            if (refinedTargetQty <= 0) {
                log.debug("定点物料新增换模预判不可排, machineCode: {}, materialCode: {}, 原因: 收敛后目标量为0",
                        machine.getMachineCode(), specifySku.getMaterialCode());
                return false;
            }
            return true;
        } finally {
            if (inspectionTime != null) {
                getFirstInspectionBalanceStrategy().rollbackInspection(context, inspectionTime);
            }
            getMouldChangeBalanceStrategy().rollbackMouldChange(context, mouldChangeStartTime);
        }
    }

    /**
     * 输出续作收尾时间回写日志。
     *
     * @param context 排程上下文
     * @param machine 机台
     * @param sku 续作SKU
     * @param result 排产结果
     * @param actualCompletionTime 实际完工时间
     */
    private void traceContinuousEndingUpdate(LhScheduleContext context, MachineScheduleDTO machine,
                                             SkuScheduleDTO sku, LhScheduleResult result,
                                             Date actualCompletionTime) {
        if (!PriorityTraceLogHelper.isEnabled(context)) {
            return;
        }
        String title = "续作收尾真实时间回写";
        StringBuilder detailBuilder = new StringBuilder(256);
        PriorityTraceLogHelper.appendLine(detailBuilder,
                "机台=" + PriorityTraceLogHelper.safeText(machine.getMachineCode())
                        + ", SKU=" + PriorityTraceLogHelper.safeText(sku.getMaterialCode())
                        + ", 是否收尾=" + PriorityTraceLogHelper.oneZero(machine.isEnding()));
        PriorityTraceLogHelper.appendLine(detailBuilder,
                "结果specEndTime=" + PriorityTraceLogHelper.formatDateTime(result.getSpecEndTime())
                        + ", 回写estimatedEndTime=" + PriorityTraceLogHelper.formatDateTime(actualCompletionTime));
        String detail = detailBuilder.toString().trim();
        log.info("{}\n{}", title, detail);
        PriorityTraceLogHelper.appendProcessLog(context, title, detail);
    }

    /**
     * 构建排程结果，分配各班次计划量
     */
    private LhScheduleResult buildScheduleResult(LhScheduleContext context,
                                                  MachineScheduleDTO machine,
                                                  SkuScheduleDTO sku,
                                                  Date startTime,
                                                  Date switchStartTime,
                                                  List<LhShiftConfigVO> shifts,
                                                  int mouldQty,
                                                  boolean isEnding) {
        LhScheduleResult result = new LhScheduleResult();
        result.setFactoryCode(context.getFactoryCode());
        result.setBatchNo(context.getBatchNo());
        result.setLhMachineCode(machine.getMachineCode());
        result.setLhMachineName(machine.getMachineName());
        result.setLeftRightMould(LeftRightMouldUtil.resolveLeftRightMould(result.getLeftRightMould(), machine.getMachineCode()));
        result.setMaterialCode(sku.getMaterialCode());
        result.setMaterialDesc(sku.getMaterialDesc());
        result.setSpecCode(sku.getSpecCode());
        result.setSpecDesc(sku.getSpecDesc());
        result.setEmbryoCode(sku.getEmbryoCode());
        // 落库口径：库存未知(-1)按0落库，但排程过程仍保留-1语义用于跳过库存裁剪。
        result.setEmbryoStock(Math.max(sku.getEmbryoStock(), 0));
        result.setMainMaterialDesc(sku.getMainMaterialDesc());
        result.setStructureName(sku.getStructureName());
        result.setScheduleDate(context.getScheduleTargetDate());
        result.setLhTime(sku.getLhTimeSeconds());
        result.setMouldQty(mouldQty);
        result.setMouldCode(resolveContinuousActualMouldCode(context, machine, sku));
        int runtimeShiftCapacity = ShiftCapacityResolverUtil.resolveRuntimeShiftCapacity(
                context, machine, sku.getShiftCapacity());
        result.setSingleMouldShiftQty(SingleMouldShiftQtyUtil.resolveSingleMouldShiftQty(
                context, sku, machine, mouldQty));
        result.setDailyPlanQty(0);
        result.setTotalDailyPlanQty(sku.getMonthPlanQty());
        result.setMouldSurplusQty(sku.getSurplusQty());
        result.setTotalFinishQty(sku.getFinishedQty());
        // 日标准产量：复用上下文 SKU 日硫化产能主数据，无主数据则为 0
        result.setStandardCapacity(ShiftCapacityResolverUtil.resolveDailyStandardQty(
                context, sku.getMaterialCode()));
        // 续作结果不参与提前生产判定，标识固定为 0
        result.setIsEarlyProduction("0");
        result.setIsEnd(isEnding ? "1" : "0");
        result.setIsDelivery(sku.isDeliveryLocked() ? "1" : "0");
        result.setIsRelease("0");
        result.setDataSource("0");
        result.setIsDelete(0);
        result.setScheduleType(sku.getScheduleType() != null ? sku.getScheduleType() : "01");
        result.setIsTypeBlock("0");
        result.setConstructionStage(sku.getConstructionStage());
        // 产品状态从月计划获取
        result.setProductStatus(sku.getProductStatus());

        // 通过物料编码+产品状态查询SKU与示方书关系获取文字/硫化/制造示方书号
        String embryoNo = null;
        String textNo = null;
        String lhNo = null;
        String lhType = null;
        MdmSkuConstructionRef constructionRef = context.findSkuConstructionRef(
                sku.getMaterialCode(), sku.getProductStatus());
        if (constructionRef != null) {
            embryoNo = constructionRef.getEmbryoNo();
            textNo = constructionRef.getTextNo();
            lhNo = constructionRef.getLhNo();
            lhType = constructionRef.getLhType();
        }
        // 设置1-8班硫化示方书号和硫化示方书类型
        result.setClass1LhNo(lhNo);
        result.setClass1LhType(lhType);
        result.setClass2LhNo(lhNo);
        result.setClass2LhType(lhType);
        result.setClass3LhNo(lhNo);
        result.setClass3LhType(lhType);
        result.setClass4LhNo(lhNo);
        result.setClass4LhType(lhType);
        result.setClass5LhNo(lhNo);
        result.setClass5LhType(lhType);
        result.setClass6LhNo(lhNo);
        result.setClass6LhType(lhType);
        result.setClass7LhNo(lhNo);
        result.setClass7LhType(lhType);
        result.setClass8LhNo(lhNo);
        result.setClass8LhType(lhType);
        // 文字/硫化/制造示方书号回写：关系查不到时置空，以关系值为准
        result.setLhNo(lhNo);
        result.setChangedTrialStatus(lhType);
        result.setEmbryoNo(embryoNo);
        result.setTextNo(textNo);
        result.setMonthPlanVersion(sku.getMonthPlanVersion());
        result.setProductionVersion(sku.getProductionVersion());
        result.setIsTrial(sku.isTrial() ? "1" : "0");
        result.setMachineOrder(machine.getMachineOrder());
        result.setHasSpecialMaterial(LhSpecialMaterialUtil.resolveHasSpecialMaterial(context, sku));

        // 生成工单号
        String orderNo = generateOrderNo(context);
        result.setOrderNo(orderNo);

        int refinedTargetQty = getTargetScheduleQtyResolver().refineTargetQtyByMachineCapacity(
                context, sku, machine, switchStartTime, startTime, shifts,
                ScheduleTypeEnum.CONTINUOUS.getCode());
        List<MachineCleaningWindowDTO> cleaningWindowList = new ArrayList<>(MachineCleaningOverlapUtil.excludeOverlapWindows(
                machine.getCleaningWindowList(), switchStartTime, startTime));
        List<MachineMaintenanceWindowDTO> maintenanceWindowList = resolveMachineMaintenanceWindowList(
                context, machine.getMachineCode());

        // 按班次分配计划量
        int remaining = refinedTargetQty;
        distributeToShifts(context, result, shifts, startTime,
                runtimeShiftCapacity, sku.getLhTimeSeconds(), mouldQty, remaining, cleaningWindowList,
                maintenanceWindowList);

        refreshResultSummary(context, result, shifts);
        result.setRealScheduleDate(context.getScheduleDate());
        result.setProductionStatus("0");

        return result;
    }

    /**
     * 解析续作结果实际使用的在机模具号。
     *
     * <p>续作不是重新分配模具，结果必须保存硫化在机信息中的当前机台实际模具号，
     * 不能写入 SKU 关联的全部模具号。</p>
     *
     * @param context 排程上下文
     * @param machine 当前续作机台
     * @param sku 当前续作SKU
     * @return 实际在机模具号，多个英文逗号分隔
     */
    private String resolveContinuousActualMouldCode(LhScheduleContext context,
                                                    MachineScheduleDTO machine,
                                                    SkuScheduleDTO sku) {
        if (Objects.isNull(context) || Objects.isNull(machine)) {
            return null;
        }
        int requiredMouldQty = ShiftCapacityResolverUtil.resolveMachineMouldQty(machine);
        LinkedHashSet<String> mouldCodeSet = LhMouldCodeUtil.resolveInMachineMouldCodeSet(
                context, machine.getMachineCode());
        if (CollectionUtils.isEmpty(mouldCodeSet)) {
            log.info("续作结果在机实际模具号为空, batchNo: {}, machineCode: {}, materialCode: {}, requiredMouldQty: {}",
                    context.getBatchNo(), machine.getMachineCode(),
                    Objects.isNull(sku) ? null : sku.getMaterialCode(), requiredMouldQty);
            return null;
        }
        if (mouldCodeSet.size() < requiredMouldQty) {
            log.info("续作结果在机实际模具数量不足, batchNo: {}, machineCode: {}, materialCode: {}, "
                            + "requiredMouldQty: {}, actualMouldCodes: {}",
                    context.getBatchNo(), machine.getMachineCode(),
                    Objects.isNull(sku) ? null : sku.getMaterialCode(), requiredMouldQty, mouldCodeSet);
        }
        String actualMouldCode = LhMouldCodeUtil.joinMouldCode(mouldCodeSet);
        log.debug("续作结果写入在机实际模具号, batchNo: {}, machineCode: {}, materialCode: {}, "
                        + "requiredMouldQty: {}, actualMouldCode: {}",
                context.getBatchNo(), machine.getMachineCode(),
                Objects.isNull(sku) ? null : sku.getMaterialCode(), requiredMouldQty, actualMouldCode);
        return actualMouldCode;
    }

    /**
     * 向各班次分配计划量（从startTime所在班次开始，按夜->早->中次序填满）
     *
     * @return 未能排产的剩余量
     */
    private int distributeToShifts(LhScheduleContext context,
                                   LhScheduleResult result,
                                   List<LhShiftConfigVO> shifts,
                                   Date startTime,
                                   int shiftCapacity,
                                   int lhTimeSeconds,
                                   int mouldQty,
                                   int remaining,
                                   List<MachineCleaningWindowDTO> cleaningWindowList,
                                   List<MachineMaintenanceWindowDTO> maintenanceWindowList) {
        if (lhTimeSeconds <= 0 || mouldQty <= 0 || remaining <= 0) {
            return remaining;
        }
        Map<Integer, ShiftRuntimeState> stateMap = context.getShiftRuntimeStateMap();
        int dryIceLossQty = context.getParamIntValue(
                LhScheduleParamConstant.DRY_ICE_LOSS_QTY, LhScheduleConstant.DRY_ICE_LOSS_QTY);
        int dryIceDurationHours = context.getParamIntValue(
                LhScheduleParamConstant.DRY_ICE_DURATION_HOURS, LhScheduleConstant.DRY_ICE_DURATION_HOURS);
        String configPlusShiftType = ShiftCapacityResolverUtil.resolveOddShiftCapacityPlusShiftType(context);
        Map<Integer, Integer> dailyStandardShiftCapacityMap = calculateDailyStandardShiftCapacityMap(
                context, result, shifts, startTime, shiftCapacity, lhTimeSeconds, mouldQty,
                cleaningWindowList, maintenanceWindowList, "续作排产");

        boolean started = false;
        for (LhShiftConfigVO shift : shifts) {
            if (remaining <= 0) {
                break;
            }
            if (!started) {
                if (startTime != null && !startTime.before(shift.getShiftEndDateTime()) && shift != shifts.get(shifts.size() - 1)) {
                    continue;
                }
                started = true;
            }

            ShiftProductionControlDTO control = ShiftProductionControlUtil.resolveEffectiveControl(context, shift, startTime);
            if (control == null || !control.isCanSchedule()) {
                continue;
            }
            Date effectiveStart = control.getEffectiveStartTime();
            Date effectiveEnd = control.getEffectiveEndTime();

            int shiftMaxQty = ShiftCapacityResolverUtil.resolveShiftCapacityWithDowntime(
                    context.getDevicePlanShutList(),
                    cleaningWindowList,
                    maintenanceWindowList,
                    result.getLhMachineCode(),
                    effectiveStart,
                    effectiveEnd,
                    shiftCapacity,
                    lhTimeSeconds,
                    mouldQty,
                    ShiftCapacityResolverUtil.resolveShiftDurationSeconds(shift),
                    dryIceLossQty,
                    dryIceDurationHours,
                    shift,
                    configPlusShiftType,
                    ScheduleTypeEnum.CONTINUOUS.getCode());
            shiftMaxQty = ShiftProductionControlUtil.deductCapacityByControl(control, shiftMaxQty, mouldQty);
            shiftMaxQty = dailyStandardShiftCapacityMap.getOrDefault(shift.getShiftIndex(), shiftMaxQty);
            if (shiftMaxQty <= 0) {
                continue;
            }
            int shiftQty = ShiftCapacityResolverUtil.normalizeAllocatedShiftQty(
                    Math.min(remaining, shiftMaxQty), shiftMaxQty, mouldQty);
            if (shiftQty <= 0) {
                continue;
            }

            Date shiftPlanEndTime = ShiftCapacityResolverUtil.resolveShiftPlanEndTime(
                    context.getDevicePlanShutList(),
                    cleaningWindowList,
                    maintenanceWindowList,
                    result.getLhMachineCode(),
                    effectiveStart,
                    effectiveEnd,
                    shiftQty,
                    shiftMaxQty);
            setShiftPlanQty(result, shift.getShiftIndex(), shiftQty, effectiveStart, shiftPlanEndTime);
            remaining -= shiftQty;
            startTime = null;

            if (!CollectionUtils.isEmpty(stateMap)) {
                ShiftRuntimeState st = stateMap.get(shift.getShiftIndex());
                if (st != null) {
                    st.setRemainingCapacity(Math.max(0, shiftMaxQty - shiftQty));
                }
            }
        }
        return remaining;
    }

    /**
     * 按SKU日标准产量修正续作班次最大计划量。
     *
     * @param context 排程上下文
     * @param result 续作结果
     * @param shifts 班次列表
     * @param startTime 首个可排开始时间
     * @param shiftCapacity 运行态班产
     * @param lhTimeSeconds 硫化时长
     * @param mouldQty 模台数
     * @param cleaningWindowList 清洗窗口
     * @param maintenanceWindowList 保养窗口
     * @param processName 当前流程
     * @return 修正后的班次最大计划量
     */
    private Map<Integer, Integer> calculateDailyStandardShiftCapacityMap(LhScheduleContext context,
                                                                         LhScheduleResult result,
                                                                         List<LhShiftConfigVO> shifts,
                                                                         Date startTime,
                                                                         int shiftCapacity,
                                                                         int lhTimeSeconds,
                                                                         int mouldQty,
                                                                         List<MachineCleaningWindowDTO> cleaningWindowList,
                                                                         List<MachineMaintenanceWindowDTO> maintenanceWindowList,
                                                                         String processName) {
        Map<Integer, Integer> rawShiftCapacityMap = new LinkedHashMap<Integer, Integer>(
                CollectionUtils.isEmpty(shifts) ? 0 : shifts.size());
        if (context == null || result == null || CollectionUtils.isEmpty(shifts)
                || shiftCapacity <= 0 || lhTimeSeconds <= 0 || mouldQty <= 0) {
            return rawShiftCapacityMap;
        }
        int dryIceLossQty = context.getParamIntValue(
                LhScheduleParamConstant.DRY_ICE_LOSS_QTY, LhScheduleConstant.DRY_ICE_LOSS_QTY);
        int dryIceDurationHours = context.getParamIntValue(
                LhScheduleParamConstant.DRY_ICE_DURATION_HOURS, LhScheduleConstant.DRY_ICE_DURATION_HOURS);
        String configPlusShiftType = ShiftCapacityResolverUtil.resolveOddShiftCapacityPlusShiftType(context);
        boolean started = false;
        for (LhShiftConfigVO shift : shifts) {
            if (!started) {
                if (startTime != null && !startTime.before(shift.getShiftEndDateTime())
                        && shift != shifts.get(shifts.size() - 1)) {
                    continue;
                }
                started = true;
            }
            ShiftProductionControlDTO control = ShiftProductionControlUtil.resolveEffectiveControl(context, shift, startTime);
            if (control == null || !control.isCanSchedule()) {
                continue;
            }
            int shiftMaxQty = ShiftCapacityResolverUtil.resolveShiftCapacityWithDowntime(
                    context.getDevicePlanShutList(),
                    cleaningWindowList,
                    maintenanceWindowList,
                    result.getLhMachineCode(),
                    control.getEffectiveStartTime(),
                    control.getEffectiveEndTime(),
                    shiftCapacity,
                    lhTimeSeconds,
                    mouldQty,
                    ShiftCapacityResolverUtil.resolveShiftDurationSeconds(shift),
                    dryIceLossQty,
                    dryIceDurationHours,
                    shift,
                    configPlusShiftType,
                    ScheduleTypeEnum.CONTINUOUS.getCode());
            shiftMaxQty = ShiftProductionControlUtil.deductCapacityByControl(control, shiftMaxQty, mouldQty);
            rawShiftCapacityMap.put(shift.getShiftIndex(), Math.max(0, shiftMaxQty));
        }
        int dailyStandardQty = ShiftCapacityResolverUtil.resolveDailyStandardQty(context, result.getMaterialCode());
        String remainShiftType = ShiftCapacityResolverUtil.resolveDailyStandardCapacityRemainShiftType(context);
        boolean singleControlMachine = LhSingleControlMachineUtil.isConfiguredSingleControlMachine(
                context, result.getLhMachineCode());
        Map<Integer, Integer> adjustedMap = ShiftCapacityResolverUtil.adjustShiftPlanQtyMapByDailyStandard(
                shifts, rawShiftCapacityMap, dailyStandardQty, shiftCapacity, remainShiftType,
                singleControlMachine, ScheduleTypeEnum.CONTINUOUS.getCode());
        if (!Objects.equals(rawShiftCapacityMap, adjustedMap)) {
            log.info("日标准产量班次计划量修正, 当前流程: {}, materialCode: {}, machineCode: {}, "
                            + "是否单控机台: {}, SKU日标准产量: {}, 班产: {}, 日标准产量剩余班次参数值: {}, "
                            + "修正前班次计划量: {}, 修正后班次计划量: {}",
                    processName, result.getMaterialCode(), result.getLhMachineCode(), singleControlMachine,
                    dailyStandardQty, shiftCapacity, remainShiftType, rawShiftCapacityMap, adjustedMap);
        }
        return adjustedMap;
    }

    /**
     * 按班次索引设置计划量和开始/结束时间（Hutool BeanUtil）
     */
    private void setShiftPlanQty(LhScheduleResult result, int shiftIndex, int qty, Date startTime, Date endTime) {
        ShiftFieldUtil.setShiftPlanQty(result, shiftIndex, qty, startTime, endTime);
    }

    /**
     * 对续作最终结果再次应用日标准产量规则。
     * <p>续作剩余班次按日标准产量公式取值，统一处理后置补满造成的超量和分配过程形成的残班。</p>
     *
     * @param context 排程上下文
     * @param shifts 排程窗口班次
     */
    private void applyDailyStandardPlanQtyToContinuousResults(LhScheduleContext context,
                                                               List<LhShiftConfigVO> shifts) {
        if (Objects.isNull(context) || CollectionUtils.isEmpty(shifts)
                || CollectionUtils.isEmpty(context.getScheduleResultList())) {
            return;
        }
        for (LhScheduleResult result : context.getScheduleResultList()) {
            if (!isPureContinuousResult(result)) {
                continue;
            }
            int shiftCapacity = Objects.isNull(result.getSingleMouldShiftQty())
                    ? 0 : Math.max(0, result.getSingleMouldShiftQty());
            if (shiftCapacity <= 0) {
                continue;
            }
            Map<Integer, Integer> rawPlanQtyMap = buildResultShiftPlanQtyMap(result, shifts);
            int dailyStandardQty = ShiftCapacityResolverUtil.resolveDailyStandardQty(
                    context, result.getMaterialCode());
            String remainShiftType = ShiftCapacityResolverUtil.resolveDailyStandardCapacityRemainShiftType(context);
            boolean singleControlMachine = LhSingleControlMachineUtil.isConfiguredSingleControlMachine(
                    context, result.getLhMachineCode());
            Map<Integer, Integer> adjustedPlanQtyMap = ShiftCapacityResolverUtil.adjustShiftPlanQtyMapByDailyStandard(
                    shifts, rawPlanQtyMap, dailyStandardQty, shiftCapacity, remainShiftType,
                    singleControlMachine, ScheduleTypeEnum.CONTINUOUS.getCode());
            SkuScheduleDTO sourceSku = resolveResultSourceSku(context, result);
            if (Objects.equals(rawPlanQtyMap, adjustedPlanQtyMap)) {
                applyMainSaleEndingFillIfNecessary(context, result, sourceSku, shifts);
                continue;
            }
            applyDailyStandardShiftPlanQty(context, result, shifts, rawPlanQtyMap, adjustedPlanQtyMap);
            refreshResultSummary(context, result, shifts);
            applyMainSaleEndingFillIfNecessary(context, result, sourceSku, shifts);
            log.info("日标准产量结果计划量收敛, 当前流程: 续作排产, materialCode: {}, machineCode: {}, "
                            + "SKU日标准产量: {}, 班产: {}, 日标准产量剩余班次参数值: {}, "
                            + "修正前班次计划量: {}, 修正后班次计划量: {}",
                    result.getMaterialCode(), result.getLhMachineCode(), dailyStandardQty, shiftCapacity,
                    remainShiftType, rawPlanQtyMap, adjustedPlanQtyMap);
        }
    }

    /**
     * 主销产品收尾特殊补满。
     * <p>仅当主销收尾机台真实收尾时间晚于业务日20:00，且结构已排机台数未达到月计划结构机台数时，
     * 才允许补满当天中班和下一个晚班。</p>
     *
     * @param context 排程上下文
     * @param result 续作结果
     * @param sku 来源SKU
     * @param shifts 排程窗口班次
     */
    private void applyMainSaleEndingFillIfNecessary(LhScheduleContext context,
                                                    LhScheduleResult result,
                                                    SkuScheduleDTO sku,
                                                    List<LhShiftConfigVO> shifts) {
        if (!isMainSaleEndingFillCandidate(context, result, sku, shifts)) {
            return;
        }
        int lastShiftIndex = resolveLastPlannedShiftIndex(result);
        LhShiftConfigVO currentShift = findShiftByIndex(shifts, lastShiftIndex);
        LhShiftConfigVO nextShift = findShiftByIndex(shifts, lastShiftIndex + 1);
        if (!isAfternoonToNightShift(currentShift, nextShift)) {
            return;
        }
        Date endingTime = result.getSpecEndTime();
        if (Objects.isNull(endingTime)) {
            endingTime = ShiftFieldUtil.getShiftEndTime(result, currentShift.getShiftIndex());
        }
        if (!isAfterMainSaleEndingFillThreshold(endingTime)) {
            return;
        }
        LocalDate businessDate = resolveShiftWorkDate(currentShift);
        int planMachineCount = context.getStructurePlanMachineCount(businessDate, sku.getStructureName());
        int scheduledMachineCount = context.getStructureScheduledMachineCount(businessDate, sku.getStructureName());
        if (planMachineCount <= 0 || scheduledMachineCount >= planMachineCount) {
            log.info("主销收尾补满跳过, materialCode: {}, machineCode: {}, businessDate: {}, structureName: {}, "
                            + "planMachineCount: {}, scheduledMachineCount: {}, endingTime: {}",
                    result.getMaterialCode(), result.getLhMachineCode(), businessDate, sku.getStructureName(),
                    planMachineCount, scheduledMachineCount, LhScheduleTimeUtil.formatDateTime(endingTime));
            return;
        }
        if (isMachineShiftOccupiedByOtherSku(context, sku, result, nextShift)) {
            log.info("主销收尾补满跳过, materialCode: {}, machineCode: {}, businessDate: {}, nextShift: {}, "
                            + "原因: 下一晚班已被其他SKU占用",
                    result.getMaterialCode(), result.getLhMachineCode(), businessDate, nextShift.getShiftIndex());
            return;
        }
        if (!fillMainSaleEndingShifts(context, result, currentShift, nextShift)) {
            return;
        }
        context.recordScheduledMachine(businessDate, sku.getStructureName(), sku.getMaterialCode(),
                result.getLhMachineCode());
        refreshResultSummary(context, result, shifts);
        log.info("主销收尾补满完成, materialCode: {}, machineCode: {}, businessDate: {}, structureName: {}, "
                        + "planMachineCount: {}, scheduledMachineCountBefore: {}, scheduledMachineCountAfter: {}, "
                        + "endingTime: {}",
                result.getMaterialCode(), result.getLhMachineCode(), businessDate, sku.getStructureName(),
                planMachineCount, scheduledMachineCount,
                context.getStructureScheduledMachineCount(businessDate, sku.getStructureName()),
                LhScheduleTimeUtil.formatDateTime(endingTime));
    }

    /**
     * 判断续作结果是否进入主销收尾补满候选。
     *
     * @param context 排程上下文
     * @param result 续作结果
     * @param sku 来源SKU
     * @param shifts 排程窗口班次
     * @return true-候选；false-不处理
     */
    private boolean isMainSaleEndingFillCandidate(LhScheduleContext context,
                                                  LhScheduleResult result,
                                                  SkuScheduleDTO sku,
                                                  List<LhShiftConfigVO> shifts) {
        return Objects.nonNull(context)
                && Objects.nonNull(result)
                && Objects.nonNull(sku)
                && !CollectionUtils.isEmpty(shifts)
                && StringUtils.equals(MAIN_SALE_PRODUCTION_TYPE, sku.getProductionType())
                && StringUtils.equals(SkuTagEnum.ENDING.getCode(), sku.getSkuTag())
                && StringUtils.equals(ScheduleTypeEnum.CONTINUOUS.getCode(), result.getScheduleType())
                && StringUtils.isNotEmpty(sku.getStructureName())
                && StringUtils.isNotEmpty(result.getLhMachineCode());
    }

    /**
     * 判断主销收尾时间是否严格晚于20:00。
     *
     * @param endingTime 收尾时间
     * @return true-晚于20:00；false-不满足
     */
    private boolean isAfterMainSaleEndingFillThreshold(Date endingTime) {
        if (Objects.isNull(endingTime)) {
            return false;
        }
        LocalTime endingLocalTime = endingTime.toInstant()
                .atZone(ZoneId.systemDefault()).toLocalTime();
        return endingLocalTime.isAfter(MAIN_SALE_ENDING_FILL_THRESHOLD_TIME);
    }

    /**
     * 判断是否为中班后紧接晚班。
     *
     * @param currentShift 当前最后有量班次
     * @param nextShift 下一班次
     * @return true-中班后接晚班；false-不满足
     */
    private boolean isAfternoonToNightShift(LhShiftConfigVO currentShift, LhShiftConfigVO nextShift) {
        return Objects.nonNull(currentShift)
                && Objects.nonNull(nextShift)
                && StringUtils.equals(ShiftEnum.AFTERNOON_SHIFT.getCode(), currentShift.getShiftType())
                && nextShift.isNightShift();
    }

    /**
     * 补满主销收尾当前中班和下一晚班。
     *
     * @param context 排程上下文
     * @param result 续作结果
     * @param currentShift 当前中班
     * @param nextShift 下一晚班
     * @return true-发生补满；false-无可补产能
     */
    private boolean fillMainSaleEndingShifts(LhScheduleContext context,
                                             LhScheduleResult result,
                                             LhShiftConfigVO currentShift,
                                             LhShiftConfigVO nextShift) {
        int currentBeforeQty = resolveShiftPlanQty(result, currentShift.getShiftIndex());
        int currentShiftCapacity = calculateResultShiftCapacity(context, result, currentShift);
        int nextBeforeQty = resolveShiftPlanQty(result, nextShift.getShiftIndex());
        int nextShiftCapacity = calculateResultShiftCapacity(context, result, nextShift);
        boolean filled = false;
        if (currentShiftCapacity > currentBeforeQty) {
            Date currentStartTime = ShiftFieldUtil.getShiftStartTime(result, currentShift.getShiftIndex());
            setShiftPlanQty(result, currentShift.getShiftIndex(), currentShiftCapacity,
                    Objects.isNull(currentStartTime) ? currentShift.getShiftStartDateTime() : currentStartTime,
                    currentShift.getShiftEndDateTime());
            filled = true;
        }
        if (nextShiftCapacity > nextBeforeQty) {
            setShiftPlanQty(result, nextShift.getShiftIndex(), nextShiftCapacity,
                    nextShift.getShiftStartDateTime(), nextShift.getShiftEndDateTime());
            filled = true;
        }
        log.info("主销收尾补满判断, materialCode: {}, machineCode: {}, currentShift: {}, nextShift: {}, "
                        + "currentBeforeQty: {}, currentCapacity: {}, nextBeforeQty: {}, nextCapacity: {}, filled: {}",
                result.getMaterialCode(), result.getLhMachineCode(), currentShift.getShiftIndex(),
                nextShift.getShiftIndex(), currentBeforeQty, currentShiftCapacity, nextBeforeQty,
                nextShiftCapacity, filled);
        return filled;
    }

    /**
     * 构建结果班次计划量映射。
     *
     * @param result 排程结果
     * @param shifts 排程窗口班次
     * @return 班次计划量映射
     */
    private Map<Integer, Integer> buildResultShiftPlanQtyMap(LhScheduleResult result,
                                                              List<LhShiftConfigVO> shifts) {
        Map<Integer, Integer> planQtyMap = new LinkedHashMap<Integer, Integer>(shifts.size());
        for (LhShiftConfigVO shift : shifts) {
            Integer planQty = ShiftFieldUtil.getShiftPlanQty(result, shift.getShiftIndex());
            planQtyMap.put(shift.getShiftIndex(), Objects.isNull(planQty) ? 0 : Math.max(0, planQty));
        }
        return planQtyMap;
    }

    /**
     * 将日标准产量公式结果应用到续作结果。
     *
     * @param context 排程上下文
     * @param result 排程结果
     * @param shifts 排程窗口班次
     * @param rawPlanQtyMap 修正前计划量
     * @param adjustedPlanQtyMap 修正后计划量
     */
    private void applyDailyStandardShiftPlanQty(LhScheduleContext context,
                                                LhScheduleResult result,
                                                List<LhShiftConfigVO> shifts,
                                                Map<Integer, Integer> rawPlanQtyMap,
                                                Map<Integer, Integer> adjustedPlanQtyMap) {
        int lhTimeSeconds = Objects.isNull(result.getLhTime()) ? 0 : Math.max(0, result.getLhTime());
        int mouldQty = ShiftCapacityResolverUtil.resolveMachineMouldQty(
                Objects.isNull(result.getMouldQty()) ? 0 : result.getMouldQty());
        List<MachineCleaningWindowDTO> cleaningWindowList = resolveEffectiveCleaningWindowList(
                context, result, resolveFirstPlannedShiftStartTime(result));
        List<MachineMaintenanceWindowDTO> maintenanceWindowList = resolveMachineMaintenanceWindowList(
                context, result.getLhMachineCode());
        for (LhShiftConfigVO shift : shifts) {
            int shiftIndex = shift.getShiftIndex();
            int beforeQty = rawPlanQtyMap.getOrDefault(shiftIndex, 0);
            int calculatedQty = adjustedPlanQtyMap.getOrDefault(shiftIndex, beforeQty);
            int afterQty = Math.max(0, calculatedQty);
            if (beforeQty == afterQty) {
                continue;
            }
            Date startTime = ShiftFieldUtil.getShiftStartTime(result, shiftIndex);
            if (Objects.isNull(startTime)) {
                startTime = shift.getShiftStartDateTime();
            }
            Date endTime = null;
            if (afterQty > 0 && lhTimeSeconds > 0) {
                long secondsNeeded = (long) Math.ceil((double) afterQty / mouldQty) * lhTimeSeconds;
                endTime = ShiftCapacityResolverUtil.resolveCompletionTimeWithDowntimes(
                        context.getDevicePlanShutList(), cleaningWindowList, maintenanceWindowList,
                        result.getLhMachineCode(), startTime, secondsNeeded);
            }
            setShiftPlanQty(result, shiftIndex, afterQty, afterQty > 0 ? startTime : null, endTime);
        }
    }

    /**
     * 计算规格收尾时间（最后一个有计划量班次中，完成剩余量所需的时间点）
     */
    private Date calcSpecEndTime(LhScheduleContext context,
                                 LhScheduleResult result,
                                 List<LhShiftConfigVO> shifts,
                                 int lhTimeSeconds,
                                 int mouldQty,
                                 boolean isEnding) {
        if (!isEnding) {
            return null;
        }
        // 找到最后一个有计划量的班次，按真实产量推导完工时刻，避免被班次结束时刻放大。
        for (int i = shifts.size() - 1; i >= 0; i--) {
            LhShiftConfigVO shift = shifts.get(i);
            Integer shiftPlanQty = ShiftFieldUtil.getShiftPlanQty(result, shift.getShiftIndex());
            Date shiftStartTime = ShiftFieldUtil.getShiftStartTime(result, shift.getShiftIndex());
            Date shiftEndTime = ShiftFieldUtil.getShiftEndTime(result, shift.getShiftIndex());
            if (shiftEndTime == null) {
                shiftEndTime = shift.getShiftEndDateTime();
            }
            if (shiftPlanQty == null || shiftPlanQty <= 0 || shiftStartTime == null) {
                continue;
            }
            if (lhTimeSeconds <= 0 || mouldQty <= 0) {
                return shiftEndTime;
            }
            long secondsNeeded = (long) Math.ceil((double) shiftPlanQty / mouldQty) * lhTimeSeconds;
            List<MachineCleaningWindowDTO> cleaningWindowList = resolveEffectiveCleaningWindowList(
                    context, result, resolveFirstPlannedShiftStartTime(result));
            Date shiftCompletionTime = ShiftCapacityResolverUtil.resolveCompletionTimeWithDowntimes(
                    context.getDevicePlanShutList(),
                    cleaningWindowList,
                    result.getLhMachineCode(),
                    shiftStartTime,
                    secondsNeeded);
            if (shiftCompletionTime != null) {
                return constrainCompletionWithinShift(shiftCompletionTime, shiftEndTime);
            }
            return shiftEndTime;
        }
        return null;
    }

    private int calcTotalPlanQty(LhScheduleResult result, List<LhShiftConfigVO> shifts) {
        int total = 0;
        for (LhShiftConfigVO s : shifts) {
            Integer qty = ShiftFieldUtil.getShiftPlanQty(result, s.getShiftIndex());
            total += (qty != null ? qty : 0);
        }
        return total;
    }

    /**
     * 重新在班次间均衡分配计划量（用于allocateShiftPlanQty后续调整）
     */
    private void redistributeShiftQty(LhScheduleContext context, LhScheduleResult result, List<LhShiftConfigVO> shifts) {
        redistributeShiftQty(context, result, shifts, ShiftFieldUtil.resolveScheduledQty(result));
    }

    /**
     * 按指定目标量重新在班次间均衡分配计划量。
     *
     * @param result 排程结果
     * @param shifts 班次列表
     * @param targetQty 目标计划量
     */
    private void redistributeShiftQty(LhScheduleContext context, LhScheduleResult result, List<LhShiftConfigVO> shifts, int targetQty) {
        if (CollectionUtils.isEmpty(shifts)) {
            return;
        }

        if (targetQty <= 0) {
            clearShiftPlanQty(result, shifts);
            refreshResultSummary(context, result, shifts);
            return;
        }

        if (result.getLhTime() == null || result.getLhTime() <= 0) {
            return;
        }

        int mouldQty = ShiftCapacityResolverUtil.resolveMachineMouldQty(
                result.getMouldQty() != null ? result.getMouldQty() : 0);
        int shiftCapacity = result.getSingleMouldShiftQty() != null ? result.getSingleMouldShiftQty() : 0;
        int remaining = targetQty;
        Date cursorStartTime = resolveRedistributeStartTime(result, shifts);
        List<MachineCleaningWindowDTO> cleaningWindowList = resolveEffectiveCleaningWindowList(
                context, result, resolveFirstPlannedShiftStartTime(result));
        List<MachineMaintenanceWindowDTO> maintenanceWindowList = resolveMachineMaintenanceWindowList(
                context, result.getLhMachineCode());
        int dryIceLossQty = context.getParamIntValue(
                LhScheduleParamConstant.DRY_ICE_LOSS_QTY, LhScheduleConstant.DRY_ICE_LOSS_QTY);
        int dryIceDurationHours = context.getParamIntValue(
                LhScheduleParamConstant.DRY_ICE_DURATION_HOURS, LhScheduleConstant.DRY_ICE_DURATION_HOURS);
        String configPlusShiftType = ShiftCapacityResolverUtil.resolveOddShiftCapacityPlusShiftType(context);
        Map<Integer, Integer> dailyStandardShiftCapacityMap = calculateDailyStandardShiftCapacityMap(
                context, result, shifts, cursorStartTime, shiftCapacity, result.getLhTime(), mouldQty,
                cleaningWindowList, maintenanceWindowList, "续作排产");

        for (LhShiftConfigVO shift : shifts) {
            if (remaining <= 0) {
                setShiftPlanQty(result, shift.getShiftIndex(), 0, null, null);
                continue;
            }
            if (cursorStartTime != null
                    && !cursorStartTime.before(shift.getShiftEndDateTime())
                    && shift != shifts.get(shifts.size() - 1)) {
                setShiftPlanQty(result, shift.getShiftIndex(), 0, null, null);
                continue;
            }
            ShiftProductionControlDTO control = ShiftProductionControlUtil.resolveEffectiveControl(context, shift, cursorStartTime);
            if (control == null || !control.isCanSchedule()) {
                setShiftPlanQty(result, shift.getShiftIndex(), 0, null, null);
                continue;
            }
            Date effectiveStartTime = control.getEffectiveStartTime();
            Date effectiveEndTime = control.getEffectiveEndTime();
            int shiftMaxQty = ShiftCapacityResolverUtil.resolveShiftCapacityWithDowntime(
                    context.getDevicePlanShutList(),
                    cleaningWindowList,
                    maintenanceWindowList,
                    result.getLhMachineCode(),
                    effectiveStartTime,
                    effectiveEndTime,
                    shiftCapacity,
                    result.getLhTime(),
                    mouldQty,
                    ShiftCapacityResolverUtil.resolveShiftDurationSeconds(shift),
                    dryIceLossQty,
                    dryIceDurationHours,
                    shift,
                    configPlusShiftType,
                    ScheduleTypeEnum.CONTINUOUS.getCode());
            shiftMaxQty = ShiftProductionControlUtil.deductCapacityByControl(control, shiftMaxQty, mouldQty);
            shiftMaxQty = dailyStandardShiftCapacityMap.getOrDefault(shift.getShiftIndex(), shiftMaxQty);
            if (shiftMaxQty <= 0) {
                setShiftPlanQty(result, shift.getShiftIndex(), 0, null, null);
                continue;
            }
            int shiftQty = ShiftCapacityResolverUtil.normalizeAllocatedShiftQty(
                    Math.min(remaining, shiftMaxQty), shiftMaxQty, mouldQty);
            if (shiftQty <= 0) {
                setShiftPlanQty(result, shift.getShiftIndex(), 0, null, null);
                continue;
            }
            Date shiftPlanEndTime = ShiftCapacityResolverUtil.resolveShiftPlanEndTime(
                    context.getDevicePlanShutList(),
                    cleaningWindowList,
                    maintenanceWindowList,
                    result.getLhMachineCode(),
                    effectiveStartTime,
                    effectiveEndTime,
                    shiftQty,
                    shiftMaxQty);
            setShiftPlanQty(result, shift.getShiftIndex(), shiftQty, effectiveStartTime, shiftPlanEndTime);
            remaining -= shiftQty;
            cursorStartTime = effectiveEndTime;
        }
        refreshResultSummary(context, result, shifts);
    }

    /**
     * 获取结果当前的首个开产时间，供续作班次重分配时保留残班起点。
     *
     * @param result 排程结果
     * @param shifts 班次列表
     * @return 首个有效开产时间
     */
    private Date resolveRedistributeStartTime(LhScheduleResult result, List<LhShiftConfigVO> shifts) {
        for (LhShiftConfigVO shift : shifts) {
            Date shiftStartTime = ShiftFieldUtil.getShiftStartTime(result, shift.getShiftIndex());
            if (shiftStartTime != null) {
                return shiftStartTime;
            }
        }
        return shifts.get(0).getShiftStartDateTime();
    }

    /**
     * 基于最终计划量复核续作结果收尾标记。
     * <p>口径：当日计划量 >= max(硫化余量, 胎胚库存)时记为收尾，否则记为正常。</p>
     *
     * @param context 排程上下文
     */
    /**
     * 基于最终计划量复核续作结果收尾标记（按物料编码汇总多机台排产量后统一判断）。
     *
     * @param context 排程上下文
     */
    private void refreshContinuousEndingFlagByResult(LhScheduleContext context) {
        if (context == null || CollectionUtils.isEmpty(context.getScheduleResultList())) {
            return;
        }
        // 按续作业务分组统一复核，避免同物料但不同共享账本组互相串量。
        Map<String, Integer> groupTotalPlanQtyMap = new LinkedHashMap<>(16);
        Map<String, Integer> groupEndingDemandQtyMap = new LinkedHashMap<>(16);
        for (LhScheduleResult result : context.getScheduleResultList()) {
            if (!isContinuousPhaseResult(result) || StringUtils.isEmpty(result.getMaterialCode())) {
                continue;
            }
            String groupKey = resolveContinuationGroupKey(context, result);
            int planQty = ShiftFieldUtil.resolveScheduledQty(result);
            groupTotalPlanQtyMap.merge(groupKey, planQty, Integer::sum);
            if (!groupEndingDemandQtyMap.containsKey(groupKey)) {
                groupEndingDemandQtyMap.put(groupKey, resolveEndingDemandQty(context, result));
            }
        }
        // 基于分组汇总计划量统一设置同组结果的收尾标记。
        for (LhScheduleResult result : context.getScheduleResultList()) {
            if (!isContinuousPhaseResult(result) || StringUtils.isEmpty(result.getMaterialCode())) {
                continue;
            }
            String groupKey = resolveContinuationGroupKey(context, result);
            int totalPlanQty = groupTotalPlanQtyMap.getOrDefault(groupKey, 0);
            int endingDemandQty = groupEndingDemandQtyMap.getOrDefault(groupKey, 0);
            result.setIsEnd(totalPlanQty >= endingDemandQty && endingDemandQty > 0 ? "1" : "0");
        }
    }

    /**
     * 计算结果行收尾比较量（从SKU DTO取全量值，避免多机台分摊后偏小）。
     * <p>仅收尾SKU才按共用胎胚规则（仅取硫化余量）；非收尾SKU继续按 MAX(余量, 胎胚库存)，
     * 避免共用胎胚导致非收尾SKU的 isEnd 被误翻转为 "1"。</p>
     *
     * @param context 排程上下文
     * @param result 排程结果
     * @return 收尾比较量
     */
    private int resolveEndingDemandQty(LhScheduleContext context, LhScheduleResult result) {
        SkuScheduleDTO sku = resolveResultSourceSku(context, result);
        int surplusQty = sku != null ? Math.max(0, sku.getSurplusQty()) : 0;
        int embryoStock = sku != null ? Math.max(0, sku.getEmbryoStock()) : 0;
        // 仅收尾SKU才按共用胎胚规则（仅取硫化余量），非收尾SKU保持原口径
        if (sku != null
                && SkuTagEnum.ENDING.getCode().equals(sku.getSkuTag())
                && getTargetScheduleQtyResolver().isSharedEmbryoInWindow(context, sku)) {
            return surplusQty;
        }
        return Math.max(surplusQty, embryoStock);
    }

    /**
     * 清空结果行的班次计划量。
     *
     * @param result 排程结果
     * @param shifts 班次列表
     */
    private void clearShiftPlanQty(LhScheduleResult result, List<LhShiftConfigVO> shifts) {
        for (LhShiftConfigVO shift : shifts) {
            setShiftPlanQty(result, shift.getShiftIndex(), 0, null, null);
        }
    }

    /**
     * 刷新结果行的汇总计划量和收尾时间。
     *
     * @param result 排程结果
     * @param shifts 班次列表
     */
    private void refreshResultSummary(LhScheduleContext context, LhScheduleResult result, List<LhShiftConfigVO> shifts) {
        if (result == null) {
            return;
        }
        ShiftFieldUtil.syncDailyPlanQty(result);
        if (result.getDailyPlanQty() == null || result.getDailyPlanQty() <= 0) {
            // 零计划结果不参与完工时刻语义。
            result.setSpecEndTime(null);
            result.setTdaySpecEndTime(null);
            ResultDowntimeSummaryUtil.clearDowntimeSummary(result);
            return;
        }
        int lhTimeSeconds = result.getLhTime() != null ? result.getLhTime() : 0;
        int mouldQty = ShiftCapacityResolverUtil.resolveMachineMouldQty(
                result.getMouldQty() != null ? result.getMouldQty() : 0);
        Date specEndTime = calcSpecEndTime(context, result, shifts, lhTimeSeconds, mouldQty, "1".equals(result.getIsEnd()));
        if (specEndTime == null) {
            // 非收尾结果也要保留可推导完工时刻，避免后续校验出现 specEndTime 缺失。
            specEndTime = resolveActualCompletionTime(context, result);
        }
        result.setSpecEndTime(specEndTime);
        result.setTdaySpecEndTime(specEndTime);
        syncResultDowntimeSummary(context, result);
    }

    /**
     * 统一处理续作阶段零计划结果：
     * 1) 清空完工时刻并从排程结果列表移除；
     * 2) 按物料去重写入/合并未排结果。
     *
     * @param context 排程上下文
     */
    private void finalizeZeroPlanContinuousResults(LhScheduleContext context) {
        if (context == null || CollectionUtils.isEmpty(context.getScheduleResultList())) {
            return;
        }
        Map<String, Integer> zeroPlanQtyMap = new LinkedHashMap<>(8);
        List<LhScheduleResult> zeroPlanResults = new ArrayList<>(8);
        Set<String> processedGroupKeySet = new LinkedHashSet<String>(8);
        for (LhScheduleResult result : context.getScheduleResultList()) {
            if (!isContinuousPhaseResult(result)) {
                continue;
            }
            if (result.getDailyPlanQty() != null && result.getDailyPlanQty() > 0) {
                continue;
            }
            result.setSpecEndTime(null);
            result.setTdaySpecEndTime(null);
            zeroPlanResults.add(result);
            SkuScheduleDTO sourceSku = requireContinuousPhaseSourceSku(context, result);
            registerReleasedContinuousMachine(context, result.getLhMachineCode(), sourceSku.getMaterialCode(),
                    "零计划续作结果移除");
            String groupKey = resolveContinuationGroupKey(context, result);
            if (sourceSku == null || StringUtils.isEmpty(groupKey) || !processedGroupKeySet.add(groupKey)) {
                continue;
            }
            int unscheduledQty = resolveRemainingUnscheduledQty(context, groupKey, sourceSku);
            if (unscheduledQty > 0) {
                zeroPlanQtyMap.merge(sourceSku.getMaterialCode(), unscheduledQty, Integer::sum);
            } else {
                // 共用胎胚余量为0导致收尾目标量为0时，也写入未排记录
                appendSharedEmbryoZeroSurplusUnscheduledIfNecessary(context, sourceSku);
            }
        }
        for (Map.Entry<String, Integer> entry : zeroPlanQtyMap.entrySet()) {
            mergeUnscheduledResultByMaterial(context, entry.getKey(), entry.getValue());
        }
        if (!CollectionUtils.isEmpty(zeroPlanResults)) {
            context.getScheduleResultList().removeAll(zeroPlanResults);
            removeResultsFromMachineAssignments(context, zeroPlanResults);
        }
        normalizeUnscheduledResultsByMaterial(context);
    }

    /**
     * 回写多机台续作结果的SKU完整胎胚库存。
     * <p>同SKU多机台仅拆分排产量，不进入共用胎胚库存分摊。</p>
     *
     * @param context 排程上下文
     */
    private void retainMultiMachineEmbryoStock(LhScheduleContext context) {
        if (context == null || CollectionUtils.isEmpty(context.getScheduleResultList())) {
            return;
        }
        // 按续作业务分组汇总结果，避免共享账本副本各自保留一份完整库存。
        Map<String, List<LhScheduleResult>> groupResultsMap = new LinkedHashMap<String, List<LhScheduleResult>>(16);
        Map<String, SkuScheduleDTO> groupSourceSkuMap = new LinkedHashMap<String, SkuScheduleDTO>(16);
        List<String> groupOrder = new ArrayList<>(16);
        for (LhScheduleResult result : context.getScheduleResultList()) {
            if (!isContinuousPhaseResult(result) || StringUtils.isEmpty(result.getMaterialCode())) {
                continue;
            }
            if (result.getDailyPlanQty() == null || result.getDailyPlanQty() <= 0) {
                continue;
            }
            SkuScheduleDTO sourceSku = requireContinuousPhaseSourceSku(context, result);
            if (sourceSku == null) {
                continue;
            }
            String groupKey = resolveContinuationGroupKey(context, result);
            if (!groupResultsMap.containsKey(groupKey)) {
                groupResultsMap.put(groupKey, new ArrayList<LhScheduleResult>());
                groupSourceSkuMap.put(groupKey, sourceSku);
                groupOrder.add(groupKey);
            }
            groupResultsMap.get(groupKey).add(result);
        }
        // 同一业务SKU的每条机台结果统一保留SKU级胎胚库存。
        for (String groupKey : groupOrder) {
            SkuScheduleDTO sourceSku = groupSourceSkuMap.get(groupKey);
            List<LhScheduleResult> materialResults = groupResultsMap.get(groupKey);
            if (materialResults.size() <= 1) {
                continue;
            }
            int totalEmbryoStock = Math.max(0, sourceSku.getEmbryoStock());
            // 同SKU多机台只拆分排产量，每条结果都保留SKU已分配的完整胎胚库存。
            LhMultiMachineDistributionUtil.retainFullEmbryoStockForSingleMaterial(
                    materialResults, totalEmbryoStock);
            log.debug("多机台续作胎胚库存完整回写完成, materialCode: {}, 机台数: {}, SKU胎胚库存: {}",
                    sourceSku.getMaterialCode(), materialResults.size(), totalEmbryoStock);
        }
    }

    /**
     * S4.4 结束后按最终有效续作结果二次回写机台状态。
     *
     * @param context 排程上下文
     */
    private void syncMachineStateAfterContinuousAdjust(LhScheduleContext context) {
        if (context == null || CollectionUtils.isEmpty(context.getMachineScheduleMap())) {
            return;
        }
        Map<String, List<LhScheduleResult>> machineResultMap = context.getScheduleResultList().stream()
                .filter(result -> isEffectiveContinuousResult(context, result))
                .collect(Collectors.groupingBy(LhScheduleResult::getLhMachineCode));
        for (Map.Entry<String, MachineScheduleDTO> entry : context.getMachineScheduleMap().entrySet()) {
            String machineCode = entry.getKey();
            MachineScheduleDTO machine = entry.getValue();
            List<LhScheduleResult> machineResults = machineResultMap.get(machineCode);
            if (!CollectionUtils.isEmpty(machineResults)) {
                LhScheduleResult latestResult = machineResults.stream()
                        .max(Comparator.comparing(LhScheduleResult::getSpecEndTime))
                        .orElse(null);
                if (latestResult != null) {
                    LhScheduleResult previousResult = resolvePreviousMachineResult(machineResults, latestResult);
                    applyMachineStateFromResult(context, machine, latestResult, previousResult);
                    continue;
                }
            }
            restoreMachineStateFromInitial(context, machineCode, machine);
        }
    }

    /**
     * 按最终续作结果同步日计划额度账本。
     * <p>续作结果会经历班次重分配、库存裁剪和降模处理，必须在收口后按最终班次量一次性扣账。</p>
     *
     * @param context 排程上下文
     * @param shifts 排程窗口班次
     */
    private void syncContinuousDailyPlanQuota(LhScheduleContext context, List<LhShiftConfigVO> shifts) {
        if (context == null || context.isContinuousDailyQuotaSynced()
                || CollectionUtils.isEmpty(context.getScheduleResultList())
                || CollectionUtils.isEmpty(shifts)) {
            return;
        }
        Date rollingAppendStartTime = resolveRollingAppendStartTime(context, shifts);
        for (LhScheduleResult result : context.getScheduleResultList()) {
            if (!isPureContinuousResult(result) || StringUtils.isEmpty(result.getMaterialCode())) {
                continue;
            }
            SkuScheduleDTO sku = resolveResultSourceSku(context, result);
            if (sku == null || CollectionUtils.isEmpty(sku.getDailyPlanQuotaMap())) {
                continue;
            }
            applyContinuousBlockToDailyQuota(context, sku, result, shifts, rollingAppendStartTime);
        }
        context.setContinuousDailyQuotaSynced(true);
    }

    /**
     * 续作机台无法满足窗口目标量时，生成新增规格补偿SKU交给S4.5继续换模补量。
     *
     * <p>补偿只处理同一日计划账本仍有剩余额度的 SKU。它不是新增业务需求，
     * 而是原续作机台产能不足或被释放后，转入 S4.5 重新选机/换模的补量入口。</p>
     *
     * <p>副作用：可能向 {@code context.newSpecSkuList} 追加补偿 SKU，并共享来源续作 SKU 的日计划账本。</p>
     *
     * @param context 排程上下文
     */
    private void appendContinuousCompensationSkuList(LhScheduleContext context) {
        if (context == null || CollectionUtils.isEmpty(context.getContinuousSkuList())) {
            return;
        }
        Set<SkuScheduleDTO> processedSkuSet = java.util.Collections.newSetFromMap(
                new IdentityHashMap<SkuScheduleDTO, Boolean>(8));
        for (SkuScheduleDTO sourceSku : context.getContinuousSkuList()) {
            if (sourceSku == null || !processedSkuSet.add(sourceSku)) {
                continue;
            }
            DailyMachineShortageQuotaPlan shortageQuotaPlan =
                    DailyMachineExpansionPlanner.prepareShortageQuota(context, sourceSku, "续作排产补偿");
            // remainingQty 是 S4.4 结果扣账后仍需由 S4.5 新增链路补齐的缺口。
            int remainingQty = resolveContinuousCompensationQty(context, sourceSku);
            logContinuousExpansionDecision(context, sourceSku, shortageQuotaPlan, remainingQty);
            if (remainingQty <= 0 || hasContinuousCompensationSku(context, sourceSku)) {
                continue;
            }
            if (isContinuousDailyCapacitySatisfied(context, sourceSku)) {
                // 理论 8 班/3 班产能已经满足窗口日计划时，不因真实残班缺口生成额外新增机台补偿。
                log.info("续作补偿增机台跳过，当前续作机台已满足理论日计划增机台规则, materialCode: {}, "
                        + "continuousMachines: {}, shiftCapacity: {}, windowPlanQty: {}",
                        sourceSku.getMaterialCode(), resolveContinuousMachineCodes(context, sourceSku),
                        sourceSku.getShiftCapacity(), sumDailyPlanQty(sourceSku.getDailyPlanQuotaMap()));
                continue;
            }
            SkuScheduleDTO compensationSku = copyContinuousCompensationSku(sourceSku, remainingQty);
            // 补偿 SKU 保留同一日计划账本，S4.5 排到后会继续消费剩余额度，避免重复扩大日计划。
            context.getNewSpecSkuList().add(compensationSku);
            log.info("续作目标量未满足，转新增规格链路补量, materialCode: {}, 原续作机台: {}, "
                            + "已排: {}, 补偿量: {}, 窗口日计划剩余: {}, dayPlanSummary: {}",
                    sourceSku.getMaterialCode(), sourceSku.getContinuousMachineCode(),
                    resolveScheduledQtyBySourceSku(context, sourceSku), remainingQty,
                    SkuDailyPlanQuotaUtil.sumRemainingQty(sourceSku.getDailyPlanQuotaMap()),
                    formatDailyPlanQuotaSummary(sourceSku));
        }
    }

    /**
     * 计算续作转新增补偿量。
     *
     * @param context 排程上下文
     * @param sourceSku 来源续作SKU
     * @return 补偿量
     */
    private int resolveContinuousCompensationQty(LhScheduleContext context, SkuScheduleDTO sourceSku) {
        if (isSingleMachineReducedContinuationGroup(context, sourceSku)) {
            // 降模已确认当前窗口只保留一台续作机台，剩余余量留给后续滚动，不转新增链路补回释放机台。
            return 0;
        }
        int scheduledQty = resolveScheduledQtyBySourceSku(context, sourceSku);
        // targetRemainingQty 是目标量口径缺口；quotaRemainingQty 是 dayN 账本口径缺口，两者都要满足才可补偿。
        int targetRemainingQty = Math.max(0, sourceSku.resolveTargetScheduleQty() - scheduledQty);
        if (!CollectionUtils.isEmpty(sourceSku.getDailyPlanQuotaMap())) {
            int quotaRemainingQty = Math.max(0,
                    SkuDailyPlanQuotaUtil.sumRemainingQty(sourceSku.getDailyPlanQuotaMap()));
            if (quotaRemainingQty <= 0) {
                return 0;
            }
            if (isSmallShortageFuturePlanCoveredByContinuousResults(context, sourceSku)) {
                return 0;
            }
            if (!DailyMachineExpansionPlanner.needMoreMachine(context, sourceSku)) {
                return 0;
            }
            if (targetRemainingQty > 0) {
                return Math.min(targetRemainingQty, quotaRemainingQty);
            }
            // 正式/量试非收尾续作可能已把原续作机台排满，但日计划账本仍有缺口，需要转S4.5新增链路补机台。
            if (shouldCompensateRemainingDailyQuota(sourceSku)) {
                return quotaRemainingQty;
            }
            return 0;
        }
        return targetRemainingQty;
    }

    /**
     * 判断续作最终机台是否已经满足本次增机台理论规则。
     * <p>续作补偿进入 S4.5 前先按同一套 8班窗口总产能、当前日3班、后一天3班和滚动阈值判断，
     * 避免续作机台已足够时仅因真实窗口剩余缺口继续补机台。</p>
     *
     * @param context 排程上下文
     * @param sourceSku 来源续作SKU
     * @return true-当前续作机台已满足，不需要生成补偿SKU；false-仍需进入S4.5补偿
     */
    private boolean isContinuousDailyCapacitySatisfied(LhScheduleContext context, SkuScheduleDTO sourceSku) {
        if (context == null || sourceSku == null || CollectionUtils.isEmpty(sourceSku.getDailyPlanQuotaMap())
                || sourceSku.getShiftCapacity() <= 0) {
            return false;
        }
        int activeMachineCount = resolveContinuousMachineCount(context, sourceSku);
        if (activeMachineCount <= 0) {
            return false;
        }
        if (isContinuousTheoreticalCapacityCoverControlTarget(context, sourceSku, activeMachineCount)) {
            return true;
        }
        if (hasPureContinuousResultReachWindowEnd(context, sourceSku)
                && DailyMachineExpansionPlanner.isDailyLookAheadCapacitySatisfied(
                context, sourceSku, activeMachineCount, ScheduleTypeEnum.CONTINUOUS.getCode())) {
            return true;
        }
        int windowPlanQty = sumDailyPlanQty(sourceSku.getDailyPlanQuotaMap());
        int pureContinuousScheduledQty = resolvePureContinuousScheduledWindowQty(context, sourceSku);
        if (isForcedShortageWindowSatisfied(context, sourceSku, windowPlanQty, pureContinuousScheduledQty)) {
            return true;
        }
        if (pureContinuousScheduledQty < windowPlanQty) {
            return false;
        }
        List<LhShiftConfigVO> windowShifts = LhScheduleTimeUtil.getScheduleShifts(context, context.getScheduleDate());
        String configPlusShiftType = ShiftCapacityResolverUtil.resolveOddShiftCapacityPlusShiftType(context);
        int singleMachineWindowCapacityQty = ShiftCapacityResolverUtil.sumActualShiftPlanQty(
                windowShifts, Math.max(0, sourceSku.getShiftCapacity()), configPlusShiftType,
                ScheduleTypeEnum.CONTINUOUS.getCode());
        Map<LocalDate, Integer> singleMachineDailyCapacityMap =
                ShiftCapacityResolverUtil.sumActualShiftPlanQtyByWorkDate(
                        windowShifts, Math.max(0, sourceSku.getShiftCapacity()), configPlusShiftType,
                        ScheduleTypeEnum.CONTINUOUS.getCode());
        int eightShiftCapacityQty = activeMachineCount * (singleMachineWindowCapacityQty > 0
                ? singleMachineWindowCapacityQty : sourceSku.getShiftCapacity() * 8);
        if (eightShiftCapacityQty >= windowPlanQty) {
            return true;
        }
        int threshold = Math.max(0, DailyMachineExpansionPlanner.resolveShortageAddMachineThreshold(context));
        int carryShortageQty = 0;
        boolean first = true;
        for (Map.Entry<LocalDate, SkuDailyPlanQuotaDTO> entry : sourceSku.getDailyPlanQuotaMap().entrySet()) {
            if (entry == null || entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            int currentShortageQty = first
                    ? Math.max(carryShortageQty, Math.max(0, sourceSku.getMonthlyHistoryShortageQty()))
                    : carryShortageQty;
            if (threshold <= 0 || currentShortageQty > threshold) {
                return false;
            }
            int todayPlanQty = Math.max(0, entry.getValue().getDayPlanQty());
            int todayScheduledQty = resolveContinuousScheduledQtyByProductionDate(context, sourceSku, entry.getKey());
            int threeShiftCapacityQty = activeMachineCount * singleMachineDailyCapacityMap.getOrDefault(
                    entry.getKey(), sourceSku.getShiftCapacity() * 3);
            if (todayPlanQty > threeShiftCapacityQty && todayScheduledQty < todayPlanQty) {
                return false;
            }
            LocalDate nextProductionDate = resolveNextProductionDate(sourceSku.getDailyPlanQuotaMap(), entry.getKey());
            if (nextProductionDate != null) {
                SkuDailyPlanQuotaDTO nextQuota = sourceSku.getDailyPlanQuotaMap().get(nextProductionDate);
                int nextDayPlanQty = nextQuota == null ? 0 : Math.max(0, nextQuota.getDayPlanQty());
                int nextDayThreeShiftCapacityQty = activeMachineCount * singleMachineDailyCapacityMap.getOrDefault(
                        nextProductionDate, sourceSku.getShiftCapacity() * 3);
                if (nextDayPlanQty > nextDayThreeShiftCapacityQty) {
                    return false;
                }
            }
            carryShortageQty = Math.max(0, carryShortageQty + todayPlanQty - todayScheduledQty);
            first = false;
        }
        return true;
    }

    /**
     * 判断当前续作机台理论窗口产能是否已覆盖控量目标。
     * <p>控量目标优先取硫化余量；收尾或仅补欠产场景取严格目标量。
     * 若一台在机机台按 dayN 三班理论产能已足够覆盖目标，不应仅因真实残班缺口再转 S4.5 加机台。</p>
     *
     * @param sourceSku 来源续作SKU
     * @param activeMachineCount 当前续作机台数
     * @return true-当前机台理论产能已覆盖目标
     */
    private boolean isContinuousTheoreticalCapacityCoverControlTarget(LhScheduleContext context,
                                                                      SkuScheduleDTO sourceSku,
                                                                      int activeMachineCount) {
        if (sourceSku == null
                || CollectionUtils.isEmpty(sourceSku.getDailyPlanQuotaMap())
                || activeMachineCount <= 0
                || sourceSku.getShiftCapacity() <= 0) {
            return false;
        }
        int controlTargetQty = resolveContinuousControlTargetQty(sourceSku);
        if (controlTargetQty <= 0) {
            return false;
        }
        List<LhShiftConfigVO> windowShifts = LhScheduleTimeUtil.getScheduleShifts(context, context.getScheduleDate());
        int singleMachineWindowCapacityQty = ShiftCapacityResolverUtil.sumActualShiftPlanQty(
                windowShifts, Math.max(0, sourceSku.getShiftCapacity()),
                ShiftCapacityResolverUtil.resolveOddShiftCapacityPlusShiftType(context),
                ScheduleTypeEnum.CONTINUOUS.getCode());
        int theoreticalWindowCapacity = activeMachineCount * (singleMachineWindowCapacityQty > 0
                ? singleMachineWindowCapacityQty
                : Math.max(0, sourceSku.getShiftCapacity())
                        * sourceSku.getDailyPlanQuotaMap().size()
                        * LhScheduleConstant.DEFAULT_SHIFTS_PER_DAY);
        boolean covered = theoreticalWindowCapacity >= controlTargetQty;
        if (covered) {
            log.info("续作补偿增机台跳过，当前续作机台理论窗口产能已覆盖控量目标, materialCode: {}, "
                            + "activeMachineCount: {}, shiftCapacity: {}, dayCount: {}, theoreticalWindowCapacity: {}, "
                            + "controlTargetQty: {}, surplusQty: {}, strictTargetQty: {}",
                    sourceSku.getMaterialCode(), activeMachineCount, sourceSku.getShiftCapacity(),
                    sourceSku.getDailyPlanQuotaMap().size(), theoreticalWindowCapacity, controlTargetQty,
                    sourceSku.getSurplusQty(), sourceSku.resolveTargetScheduleQty());
        }
        return covered;
    }

    /**
     * 解析续作补偿前的控量目标。
     *
     * @param sourceSku 来源续作SKU
     * @return 控量目标
     */
    private int resolveContinuousControlTargetQty(SkuScheduleDTO sourceSku) {
        if (sourceSku == null) {
            return 0;
        }
        if (sourceSku.isStrictTargetQty() || sourceSku.isStrictNewSpecShortageOnly()) {
            return Math.max(0, sourceSku.resolveTargetScheduleQty());
        }
        int surplusQty = Math.max(0, sourceSku.getSurplusQty());
        if (surplusQty > 0) {
            return surplusQty;
        }
        return 0;
    }

    /**
     * 判断续作机台是否已让窗口后剩余欠产回到阈值以内。
     * <p>本月前日累计欠产超过阈值时，阈值只表示进入强制增机台判断；
     * 是否继续补机台要按“历史欠产 + T~T+2月计划 - T日晚班完成 - 当前续作窗口有效产能”重新计算。
     * 若剩余欠产已小于等于阈值，说明当前续作机台已足够，不能因为仍有欠产就盲目生成补偿SKU。</p>
     *
     * @param context 排程上下文
     * @param sourceSku 来源续作SKU
     * @param windowPlanQty T~T+2窗口月计划量
     * @param pureContinuousScheduledQty 当前纯续作结果在窗口内的有效排产量
     * @return true-已满足阈值回落要求；false-仍需按原补偿链路判断
     */
    private boolean isForcedShortageWindowSatisfied(LhScheduleContext context,
                                                    SkuScheduleDTO sourceSku,
                                                    int windowPlanQty,
                                                    int pureContinuousScheduledQty) {
        int threshold = Math.max(0, DailyMachineExpansionPlanner.resolveShortageAddMachineThreshold(context));
        int historyShortageQty = sourceSku == null ? 0 : Math.max(0, sourceSku.getMonthlyHistoryShortageQty());
        if (threshold <= 0 || historyShortageQty <= threshold) {
            return false;
        }
        int scheduleDayFinishQty = Math.max(0, sourceSku.getScheduleDayFinishQty());
        int demandQty = Math.max(0, historyShortageQty + Math.max(0, windowPlanQty) - scheduleDayFinishQty);
        int windowRemainingShortageQty = Math.max(0, demandQty - Math.max(0, pureContinuousScheduledQty));
        log.info("续作欠产阈值窗口回落判断, materialCode: {}, historyShortageQty: {}, threshold: {}, "
                        + "windowPlanQty: {}, scheduleDayFinishQty: {}, pureContinuousScheduledQty: {}, "
                        + "windowRemainingShortageQty: {}",
                sourceSku.getMaterialCode(), historyShortageQty, threshold, windowPlanQty,
                scheduleDayFinishQty, pureContinuousScheduledQty, windowRemainingShortageQty);
        return windowRemainingShortageQty <= threshold;
    }

    /**
     * 判断纯续作结果是否已在当前窗口末班排产。
     * <p>逐日后看只允许处理“续作机台已吃满窗口、仅剩残班尾量”的场景；
     * 如果续作只排了首日或后续日期只被换活字块覆盖，仍需按原补偿规则转 S4.5。
     * 这里按末班排产量判断，不按 {@code specEndTime} 判断，避免末班未排满到班次结束时误判。</p>
     *
     * @param context 排程上下文
     * @param sourceSku 来源续作SKU
     * @return true-纯续作结果已在窗口末班排产；false-仍存在续作窗口未覆盖风险
     */
    private boolean hasPureContinuousResultReachWindowEnd(LhScheduleContext context, SkuScheduleDTO sourceSku) {
        if (context == null || sourceSku == null || CollectionUtils.isEmpty(context.getScheduleResultList())) {
            return false;
        }
        List<LhShiftConfigVO> shifts = context.getScheduleWindowShifts();
        if (CollectionUtils.isEmpty(shifts)) {
            shifts = LhScheduleTimeUtil.getScheduleShifts(context, context.getScheduleDate());
        }
        if (CollectionUtils.isEmpty(shifts)) {
            return false;
        }
        Integer lastShiftIndex = shifts.get(shifts.size() - 1).getShiftIndex();
        if (lastShiftIndex == null) {
            return false;
        }
        for (LhScheduleResult result : context.getScheduleResultList()) {
            if (!isPureContinuousResult(result)) {
                continue;
            }
            SkuScheduleDTO resultSourceSku = resolveResultSourceSku(context, result);
            if (resultSourceSku == null || resultSourceSku.getDailyPlanQuotaMap() != sourceSku.getDailyPlanQuotaMap()) {
                continue;
            }
            Integer lastShiftPlanQty = ShiftFieldUtil.getShiftPlanQty(result, lastShiftIndex);
            if (lastShiftPlanQty != null && lastShiftPlanQty > 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * 汇总同一共享账本纯续作结果在窗口内的已排产量。
     *
     * @param context 排程上下文
     * @param sourceSku 来源续作SKU
     * @return 纯续作窗口已排量
     */
    private int resolvePureContinuousScheduledWindowQty(LhScheduleContext context, SkuScheduleDTO sourceSku) {
        if (context == null || sourceSku == null || CollectionUtils.isEmpty(context.getScheduleResultList())) {
            return 0;
        }
        int scheduledQty = 0;
        for (LhScheduleResult result : context.getScheduleResultList()) {
            if (!isPureContinuousResult(result)) {
                continue;
            }
            SkuScheduleDTO resultSourceSku = resolveResultSourceSku(context, result);
            if (resultSourceSku == null || resultSourceSku.getDailyPlanQuotaMap() != sourceSku.getDailyPlanQuotaMap()) {
                continue;
            }
            scheduledQty += ShiftFieldUtil.resolveScheduledQty(result);
        }
        return Math.max(0, scheduledQty);
    }

    /**
     * 汇总窗口日计划量。
     *
     * @param quotaMap 日计划账本
     * @return 日计划总量
     */
    private int sumDailyPlanQty(Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap) {
        if (CollectionUtils.isEmpty(quotaMap)) {
            return 0;
        }
        int totalQty = 0;
        for (SkuDailyPlanQuotaDTO quota : quotaMap.values()) {
            if (quota == null) {
                continue;
            }
            totalQty += Math.max(0, quota.getDayPlanQty());
        }
        return totalQty;
    }

    /**
     * 格式化窗口日计划账本摘要。
     *
     * @param sku 续作SKU
     * @return 日计划摘要
     */
    private String formatDailyPlanQuotaSummary(SkuScheduleDTO sku) {
        if (sku == null || CollectionUtils.isEmpty(sku.getDailyPlanQuotaMap())) {
            return "";
        }
        StringBuilder builder = new StringBuilder(sku.getDailyPlanQuotaMap().size() * 24);
        int dayIndex = 1;
        for (Map.Entry<LocalDate, SkuDailyPlanQuotaDTO> entry : sku.getDailyPlanQuotaMap().entrySet()) {
            if (entry == null || entry.getValue() == null) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(",");
            }
            SkuDailyPlanQuotaDTO quota = entry.getValue();
            builder.append("day")
                    .append(dayIndex)
                    .append("=")
                    .append(Math.max(0, quota.getDayPlanQty()))
                    .append("/")
                    .append(Math.max(0, quota.getRemainingQty()));
            dayIndex++;
        }
        return builder.toString();
    }

    /**
     * 统计当前来源SKU的续作结果机台数。
     *
     * @param context 排程上下文
     * @param sourceSku 来源续作SKU
     * @return 续作机台数
     */
    private int resolveContinuousMachineCount(LhScheduleContext context, SkuScheduleDTO sourceSku) {
        if (context == null || sourceSku == null || CollectionUtils.isEmpty(context.getScheduleResultList())) {
            return 0;
        }
        Set<String> machineCodeSet = new LinkedHashSet<String>(4);
        for (LhScheduleResult result : context.getScheduleResultList()) {
            if (!isPureContinuousResult(result)) {
                continue;
            }
            SkuScheduleDTO resultSourceSku = resolveResultSourceSku(context, result);
            if (resultSourceSku == null || resultSourceSku.getDailyPlanQuotaMap() != sourceSku.getDailyPlanQuotaMap()) {
                continue;
            }
            if (StringUtils.isNotEmpty(result.getLhMachineCode())) {
                machineCodeSet.add(result.getLhMachineCode());
            }
        }
        return machineCodeSet.size();
    }

    /**
     * 解析当前业务日后的下一业务日。
     *
     * @param quotaMap 日计划账本
     * @param productionDate 当前业务日
     * @return 下一业务日
     */
    private LocalDate resolveNextProductionDate(Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap,
                                                LocalDate productionDate) {
        if (CollectionUtils.isEmpty(quotaMap) || productionDate == null) {
            return null;
        }
        for (LocalDate date : quotaMap.keySet()) {
            if (date != null && date.isAfter(productionDate)) {
                return date;
            }
        }
        return null;
    }

    /**
     * 判断小额历史欠产场景下，续作最终结果是否已经覆盖后续日计划。
     * <p>滚动账本会优先消费首日历史欠产，不能仅凭后续日期 remainingQty 判断是否需要增机台。</p>
     *
     * @param context 排程上下文
     * @param sourceSku 来源续作SKU
     * @return true-后续日计划已由当前续作机台覆盖；false-仍需按缺口补偿
     */
    private boolean isSmallShortageFuturePlanCoveredByContinuousResults(LhScheduleContext context,
                                                                        SkuScheduleDTO sourceSku) {
        if (!DailyMachineExpansionPlanner.shouldAllowSmallShortageRolling(context, sourceSku)
                || CollectionUtils.isEmpty(sourceSku.getDailyPlanQuotaMap())) {
            return false;
        }
        boolean first = true;
        for (Map.Entry<LocalDate, SkuDailyPlanQuotaDTO> entry : sourceSku.getDailyPlanQuotaMap().entrySet()) {
            if (first) {
                first = false;
                continue;
            }
            SkuDailyPlanQuotaDTO quota = entry.getValue();
            int dayPlanQty = quota == null ? 0 : Math.max(0, quota.getDayPlanQty());
            if (dayPlanQty <= 0) {
                continue;
            }
            int scheduledQty = resolveContinuousScheduledQtyByProductionDate(context, sourceSku, entry.getKey());
            if (scheduledQty < dayPlanQty) {
                return false;
            }
        }
        return true;
    }

    /**
     * 汇总同一共享账本续作阶段在指定业务日的已排产量。
     *
     * @param context 排程上下文
     * @param sourceSku 来源续作SKU
     * @param productionDate 业务日
     * @return 已排产量
     */
    private int resolveContinuousScheduledQtyByProductionDate(LhScheduleContext context,
                                                              SkuScheduleDTO sourceSku,
                                                              LocalDate productionDate) {
        if (context == null || sourceSku == null || productionDate == null
                || CollectionUtils.isEmpty(context.getScheduleResultList())) {
            return 0;
        }
        List<LhShiftConfigVO> shifts = context.getScheduleWindowShifts();
        if (CollectionUtils.isEmpty(shifts)) {
            shifts = LhScheduleTimeUtil.getScheduleShifts(context, context.getScheduleDate());
        }
        int scheduledQty = 0;
        for (LhScheduleResult result : context.getScheduleResultList()) {
            if (!isPureContinuousResult(result)) {
                continue;
            }
            SkuScheduleDTO resultSourceSku = resolveResultSourceSku(context, result);
            if (resultSourceSku == null || resultSourceSku.getDailyPlanQuotaMap() != sourceSku.getDailyPlanQuotaMap()) {
                continue;
            }
            scheduledQty += resolveResultScheduledQtyByProductionDate(result, shifts, productionDate);
        }
        return Math.max(0, scheduledQty);
    }

    /**
     * 汇总单条结果在指定业务日的班次排产量。
     *
     * @param result 排程结果
     * @param shifts 排程窗口班次
     * @param productionDate 业务日
     * @return 已排产量
     */
    private int resolveResultScheduledQtyByProductionDate(LhScheduleResult result,
                                                          List<LhShiftConfigVO> shifts,
                                                          LocalDate productionDate) {
        if (result == null || CollectionUtils.isEmpty(shifts) || productionDate == null) {
            return 0;
        }
        int scheduledQty = 0;
        for (LhShiftConfigVO shift : shifts) {
            if (shift == null || shift.getWorkDate() == null || shift.getShiftIndex() == null) {
                continue;
            }
            LocalDate shiftWorkDate = shift.getWorkDate().toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
            if (!productionDate.equals(shiftWorkDate)) {
                continue;
            }
            Integer shiftPlanQty = ShiftFieldUtil.getShiftPlanQty(result, shift.getShiftIndex());
            scheduledQty += shiftPlanQty == null ? 0 : Math.max(0, shiftPlanQty);
        }
        return Math.max(0, scheduledQty);
    }

    /**
     * 输出续作增机台补偿决策日志。
     *
     * @param context 排程上下文
     * @param sourceSku 来源续作SKU
     * @param shortageQuotaPlan 欠产账本准备结果
     * @param compensationQty 补偿量
     */
    private void logContinuousExpansionDecision(LhScheduleContext context,
                                                SkuScheduleDTO sourceSku,
                                                DailyMachineShortageQuotaPlan shortageQuotaPlan,
                                                int compensationQty) {
        if (context == null || sourceSku == null || shortageQuotaPlan == null) {
            return;
        }
        int scheduledQty = resolveScheduledQtyBySourceSku(context, sourceSku);
        int quotaRemainingQty = SkuDailyPlanQuotaUtil.sumRemainingQty(sourceSku.getDailyPlanQuotaMap());
        boolean needMoreMachine = DailyMachineExpansionPlanner.needMoreMachine(context, sourceSku);
        log.info("续作增机台补偿判断, scheduleDate: {}, materialCode: {}, skuType: {}, continuousMachines: {}, "
                        + "noWindowPlan: {}, forceEndingByNoFuturePlan: {}, strictShortageOnly: {}, "
                        + "historyShortageQty: {}, threshold: {}, windowDayPlanQty: {}, "
                        + "futurePlanQtyAfterWindow: {}, scheduledQty: {}, quotaRemainingQty: {}, "
                        + "needMoreMachine: {}, compensationQty: {}, strictTargetQty: {}, allowFullShift: {}",
                LhScheduleTimeUtil.formatDate(context.getScheduleDate()), sourceSku.getMaterialCode(),
                sourceSku.getConstructionStage(), resolveContinuousMachineCodes(context, sourceSku),
                shortageQuotaPlan.isNoWindowPlan(), shortageQuotaPlan.isForceEndingByNoFuturePlan(),
                sourceSku.isStrictNewSpecShortageOnly(), shortageQuotaPlan.getHistoryShortageQty(),
                shortageQuotaPlan.getShortageAddMachineThreshold(), shortageQuotaPlan.getWindowDayPlanQty(),
                shortageQuotaPlan.getFutureMonthPlanQtyAfterWindow(), scheduledQty, quotaRemainingQty,
                needMoreMachine, compensationQty, sourceSku.isStrictTargetQty(),
                ProductionQuantityPolicy.from(sourceSku, sourceSku.isStrictTargetQty()).isAllowFillStartedShift());
    }

    /**
     * 汇总当前来源SKU的续作机台列表。
     *
     * @param context 排程上下文
     * @param sourceSku 来源续作SKU
     * @return 机台列表
     */
    private String resolveContinuousMachineCodes(LhScheduleContext context, SkuScheduleDTO sourceSku) {
        if (context == null || sourceSku == null || CollectionUtils.isEmpty(context.getContinuousSkuList())) {
            return "";
        }
        List<String> machineCodeList = new ArrayList<String>(4);
        for (SkuScheduleDTO sku : context.getContinuousSkuList()) {
            if (sku == null || sku.getDailyPlanQuotaMap() != sourceSku.getDailyPlanQuotaMap()
                    || StringUtils.isEmpty(sku.getContinuousMachineCode())) {
                continue;
            }
            machineCodeList.add(sku.getContinuousMachineCode());
        }
        return String.join(",", machineCodeList);
    }

    /**
     * 判断是否允许按日计划账本剩余额度生成续作补偿SKU。
     *
     * @param sourceSku 来源续作SKU
     * @return true-允许补偿剩余日计划额度，false-不允许
     */
    private boolean shouldCompensateRemainingDailyQuota(SkuScheduleDTO sourceSku) {
        ProductionQuantityPolicy policy = ProductionQuantityPolicy.from(sourceSku,
                sourceSku != null && sourceSku.isStrictTargetQty());
        return policy.isAllowFillStartedShift() && !policy.isStrictUpperLimit();
    }

    /**
     * 汇总指定来源SKU已生成的续作阶段排产量。
     *
     * @param context 排程上下文
     * @param sourceSku 来源SKU
     * @return 已排量
     */
    private int resolveScheduledQtyBySourceSku(LhScheduleContext context, SkuScheduleDTO sourceSku) {
        if (context == null || sourceSku == null || CollectionUtils.isEmpty(context.getScheduleResultList())) {
            return 0;
        }
        int scheduledQty = 0;
        for (LhScheduleResult result : context.getScheduleResultList()) {
            if (!isContinuousPhaseResult(result)) {
                continue;
            }
            if (resolveResultSourceSku(context, result) != sourceSku) {
                continue;
            }
            scheduledQty += ShiftFieldUtil.resolveScheduledQty(result);
        }
        return Math.max(0, scheduledQty);
    }

    /**
     * 判断是否已存在当前续作SKU的补偿SKU。
     *
     * @param context 排程上下文
     * @param sourceSku 来源SKU
     * @return true-已存在，false-不存在
     */
    private boolean hasContinuousCompensationSku(LhScheduleContext context, SkuScheduleDTO sourceSku) {
        if (CollectionUtils.isEmpty(context.getNewSpecSkuList()) || sourceSku == null) {
            return false;
        }
        for (SkuScheduleDTO newSpecSku : context.getNewSpecSkuList()) {
            if (newSpecSku == null) {
                continue;
            }
            if (StringUtils.equals(newSpecSku.getMaterialCode(), sourceSku.getMaterialCode())
                    && newSpecSku.getDailyPlanQuotaMap() == sourceSku.getDailyPlanQuotaMap()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 复制续作SKU为新增补偿SKU。
     *
     * @param sourceSku 来源续作SKU
     * @param remainingQty 补偿量
     * @return 新增补偿SKU
     */
    private SkuScheduleDTO copyContinuousCompensationSku(SkuScheduleDTO sourceSku, int remainingQty) {
        SkuScheduleDTO compensationSku = new SkuScheduleDTO();
        BeanUtil.copyProperties(sourceSku, compensationSku);
        ProductionQuantityPolicy policy = ProductionQuantityPolicy.from(sourceSku, sourceSku.isStrictTargetQty());
        compensationSku.setScheduleType(ScheduleTypeEnum.NEW_SPEC.getCode());
        compensationSku.setContinuousMachineCode(null);
        compensationSku.setPreferredContinuousMachineCode(sourceSku.getContinuousMachineCode());
        compensationSku.setContinuousCompensationSku(true);
        compensationSku.setTargetScheduleQty(remainingQty);
        compensationSku.setPendingQty(remainingQty);
        compensationSku.setRemainingScheduleQty(remainingQty);
        compensationSku.setStrictTargetQty(policy.isStrictUpperLimit());
        // 复用同一份日计划账本，作为续作补偿SKU与来源续作SKU的共享归属锚点。
        compensationSku.setDailyPlanQuotaMap(sourceSku.getDailyPlanQuotaMap());
        return compensationSku;
    }

    /**
     * 扣减单条续作结果占用的日计划额度。
     *
     * @param context 排程上下文
     * @param sku SKU
     * @param result 续作结果
     * @param shifts 排程窗口班次
     * @param rollingAppendStartTime 滚动追加起点
     */
    private void applyContinuousBlockToDailyQuota(LhScheduleContext context,
                                                  SkuScheduleDTO sku,
                                                  LhScheduleResult result,
                                                  List<LhShiftConfigVO> shifts,
                                                  Date rollingAppendStartTime) {
        Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap = sku.getDailyPlanQuotaMap();
        int totalShiftFillOverQty = 0;
        for (LhShiftConfigVO shift : shifts) {
            if (shouldSkipRollingInheritedShift(result, shift, rollingAppendStartTime)) {
                continue;
            }
            Integer planQty = ShiftFieldUtil.getShiftPlanQty(result, shift.getShiftIndex());
            if (planQty == null || planQty <= 0 || shift.getWorkDate() == null) {
                continue;
            }
            LocalDate productionDate = shift.getWorkDate().toInstant()
                    .atZone(ZoneId.systemDefault()).toLocalDate();
            SkuDailyPlanQuotaDTO quota = quotaMap.get(productionDate);
            if (quota == null) {
                continue;
            }
            int consumedQty = SkuDailyPlanQuotaUtil.consumeRollingQuota(quotaMap, productionDate, planQty);
            int overQty = planQty - consumedQty;
            if (overQty <= 0) {
                continue;
            }
            // 通过 SKU 标记判断收尾，不受 refreshContinuousEndingFlagByResult 翻转 isEnd 影响
            boolean endingSku = sku != null && StringUtils.equals(SkuTagEnum.ENDING.getCode(), sku.getSkuTag());
            if (endingSku) {
                // 收尾SKU的结果保留完整计划量不截断，超排部分记入 shiftFillOverQty 保持账本可追溯
                if (overQty > 0) {
                    quota.setShiftFillOverQty(quota.getShiftFillOverQty() + overQty);
                    totalShiftFillOverQty += overQty;
                }
                continue;
            }
            // 续作清尾余量不能被 dayN 回裁；仅补历史欠产和普通严格目标仍保留目标上限。
            if (sku != null && sku.isStrictTargetQty() && !shouldKeepContinuousSurplusOverDailyQuota(sku)) {
                trimShiftPlanQty(result, shift.getShiftIndex(), consumedQty);
            }
            quota.setShiftFillOverQty(quota.getShiftFillOverQty() + overQty);
            totalShiftFillOverQty += overQty;
            log.debug("续作班次满班补齐超排, materialCode: {}, 日期: {}, 班次: {}, 排产量: {}, 超排: {}",
                    sku.getMaterialCode(), productionDate, shift.getShiftIndex(), planQty, overQty);
        }
        if (totalShiftFillOverQty > 0) {
            sku.setShiftFillOverQty(sku.getShiftFillOverQty() + totalShiftFillOverQty);
            context.getSkuShiftFillOverQtyMap().merge(sku.getMaterialCode(), totalShiftFillOverQty, Integer::sum);
        }
        refreshResultSummary(context, result, shifts);
    }

    /**
     * 回裁单个续作班次计划量，并清空失效的结束时刻，交给收口阶段重新推导真实完工时刻。
     *
     * @param result 排程结果
     * @param shiftIndex 班次索引
     * @param trimmedQty 回裁后的计划量
     */
    private void trimShiftPlanQty(LhScheduleResult result, int shiftIndex, int trimmedQty) {
        Date shiftStartTime = ShiftFieldUtil.getShiftStartTime(result, shiftIndex);
        if (trimmedQty <= 0) {
            setShiftPlanQty(result, shiftIndex, 0, null, null);
            return;
        }
        setShiftPlanQty(result, shiftIndex, trimmedQty, shiftStartTime, null);
    }

    /**
     * 判断续作清尾余量是否应保留超出窗口日计划账本的排产结果。
     *
     * @param sku 续作SKU
     * @return true-保留结果计划量，false-按严格目标回裁
     */
    private boolean shouldKeepContinuousSurplusOverDailyQuota(SkuScheduleDTO sku) {
        if (sku == null || sku.isStrictNewSpecShortageOnly()) {
            return false;
        }
        int surplusQty = Math.max(0, sku.getSurplusQty());
        if (surplusQty <= 0 || Math.max(0, sku.getFutureMonthPlanQtyAfterWindow()) > 0) {
            return false;
        }
        return surplusQty > sumDailyPlanQty(sku.getDailyPlanQuotaMap());
    }

    /**
     * 滚动继承结果中，继承窗口内班次已在 S4.3 扣减，不再重复消费账本。
     *
     * @param result 续作结果
     * @param shift 班次
     * @param rollingAppendStartTime 滚动追加起点
     * @return true-跳过扣减
     */
    private boolean shouldSkipRollingInheritedShift(LhScheduleResult result,
                                                    LhShiftConfigVO shift,
                                                    Date rollingAppendStartTime) {
        if (result == null || shift == null || rollingAppendStartTime == null || !result.isRollingInherited()) {
            return false;
        }
        Date shiftEndTime = ShiftFieldUtil.getShiftEndTime(result, shift.getShiftIndex());
        return shiftEndTime != null && !shiftEndTime.after(rollingAppendStartTime);
    }

    /**
     * 在最终保留结果集中推导上一条有效结果。
     *
     * @param machineResults 机台有效结果列表
     * @param latestResult 最新结果
     * @return 上一条有效结果
     */
    private LhScheduleResult resolvePreviousMachineResult(List<LhScheduleResult> machineResults, LhScheduleResult latestResult) {
        if (CollectionUtils.isEmpty(machineResults) || latestResult == null) {
            return null;
        }
        return machineResults.stream()
                .filter(result -> result != null
                        && result != latestResult
                        && result.getSpecEndTime() != null)
                .max(Comparator.comparing(LhScheduleResult::getSpecEndTime))
                .orElse(null);
    }

    /**
     * 判断结果是否属于可驱动机台终态的有效结果。
     * <p>除续作结果外，S4.4 产生的换活字块结果也需要参与机台终态回写，
     * 否则会在 S4.5 选机时丢失真实收尾时间。</p>
     *
     * @param result 排程结果
     * @return true-有效结果；false-非有效结果
     */
    private boolean isEffectiveContinuousResult(LhScheduleContext context, LhScheduleResult result) {
        return isContinuousPhaseResult(result)
                && result.getDailyPlanQty() != null
                && result.getDailyPlanQty() > 0
                && result.getSpecEndTime() != null
                && !isReleasedFirstDayNoPlanPlaceholderResult(context, result)
                && StringUtils.isNotEmpty(result.getLhMachineCode());
    }

    /**
     * 判断结果是否属于“首日无计划但后续有计划”的释放续作占位结果。
     * <p>这类结果仍需保留在续作结果集里参与账本扣减和后续补偿判断，
     * 但不能在 S4.4 收口后继续把机台运行态锁定在原续作机台上。</p>
     *
     * @param context 排程上下文
     * @param result 排程结果
     * @return true-释放续作占位结果
     */
    private boolean isReleasedFirstDayNoPlanPlaceholderResult(LhScheduleContext context, LhScheduleResult result) {
        if (context == null || result == null || !isPureContinuousResult(result)
                || StringUtils.isEmpty(result.getLhMachineCode())
                || CollectionUtils.isEmpty(context.getFirstDayNoPlanReleasedContinuousMachineCodeSet())) {
            return false;
        }
        return context.getFirstDayNoPlanReleasedContinuousMachineCodeSet().contains(result.getLhMachineCode());
    }

    /**
     * 判断结果是否属于续作阶段结果（含换活字块）。
     *
     * @param result 排程结果
     * @return true-续作阶段结果；false-非续作阶段结果
     */
    private boolean isContinuousPhaseResult(LhScheduleResult result) {
        if (result == null) {
            return false;
        }
        return CONTINUOUS_SCHEDULE_TYPE.equals(result.getScheduleType())
                || "1".equals(result.getIsTypeBlock());
    }

    /**
     * 判断结果是否为纯续作结果。
     * <p>续作多机台降模只处理原在机SKU，不能把换活字块结果混入同SKU多机台降模。</p>
     *
     * @param result 排程结果
     * @return true-纯续作结果
     */
    private boolean isPureContinuousResult(LhScheduleResult result) {
        return result != null
                && CONTINUOUS_SCHEDULE_TYPE.equals(result.getScheduleType())
                && !"1".equals(result.getIsTypeBlock());
    }

    /**
     * 根据最终有效结果回写机台状态。
     *
     * @param context 排程上下文
     * @param machine 机台
     * @param result 最终有效续作结果
     */
    private void applyMachineStateFromResult(LhScheduleContext context,
                                             MachineScheduleDTO machine,
                                             LhScheduleResult result,
                                             LhScheduleResult previousResult) {
        String previousMaterialCode = null;
        String previousMaterialDesc = null;
        if (previousResult != null) {
            previousMaterialCode = previousResult.getMaterialCode();
            previousMaterialDesc = previousResult.getMaterialDesc();
        } else if (machine != null && StringUtils.isNotEmpty(machine.getMachineCode())) {
            MachineScheduleDTO initialMachine = context.getInitialMachineScheduleMap().get(machine.getMachineCode());
            if (initialMachine != null) {
                previousMaterialCode = initialMachine.getCurrentMaterialCode();
                previousMaterialDesc = initialMachine.getCurrentMaterialDesc();
            }
        }
        machine.setCurrentMaterialCode(result.getMaterialCode());
        machine.setCurrentMaterialDesc(result.getMaterialDesc());
        machine.setPreviousMaterialCode(previousMaterialCode);
        machine.setPreviousMaterialDesc(previousMaterialDesc);
        machine.setPreviousSpecCode(result.getSpecCode());
        machine.setPreviousProSize(resolveMaterialProSize(context, result.getMaterialCode()));
        machine.setEstimatedEndTime(result.getSpecEndTime());
        machine.setEnding("1".equals(result.getIsEnd()) && result.getSpecEndTime() != null);
    }

    /**
     * 回退机台状态到初始化快照，避免沿用失效衔接状态。
     *
     * @param context 排程上下文
     * @param machineCode 机台编码
     * @param machine 当前机台对象
     */
    private void restoreMachineStateFromInitial(LhScheduleContext context, String machineCode, MachineScheduleDTO machine) {
        if (context == null || machine == null || StringUtils.isEmpty(machineCode)) {
            return;
        }
        MachineScheduleDTO initialMachine = context.getInitialMachineScheduleMap().get(machineCode);
        if (initialMachine == null) {
            return;
        }
        machine.setCurrentMaterialCode(initialMachine.getCurrentMaterialCode());
        machine.setCurrentMaterialDesc(initialMachine.getCurrentMaterialDesc());
        machine.setPreviousMaterialCode(initialMachine.getPreviousMaterialCode());
        machine.setPreviousMaterialDesc(initialMachine.getPreviousMaterialDesc());
        machine.setPreviousSpecCode(initialMachine.getPreviousSpecCode());
        machine.setPreviousProSize(initialMachine.getPreviousProSize());
        machine.setEstimatedEndTime(initialMachine.getEstimatedEndTime());
        machine.setEnding(initialMachine.isEnding());
    }

    /**
     * 解析物料规格英寸，用于机台前规格回写。
     *
     * @param context 排程上下文
     * @param materialCode 物料编码
     * @return 规格英寸
     */
    private String resolveMaterialProSize(LhScheduleContext context, String materialCode) {
        if (context == null || StringUtils.isEmpty(materialCode)) {
            return null;
        }
        MdmMaterialInfo materialInfo = context.getMaterialInfoMap().get(materialCode);
        if (materialInfo != null && StringUtils.isNotEmpty(materialInfo.getProSize())) {
            return materialInfo.getProSize();
        }
        SkuScheduleDTO sku = findSkuDto(context, materialCode);
        return sku != null ? sku.getProSize() : null;
    }

    /**
     * 计算来源SKU剩余待排数量（续作零计划未排口径）。
     *
     * @param context 排程上下文
     * @param sku 来源SKU
     * @return 剩余待排数量
     */
    private int resolveRemainingUnscheduledQty(LhScheduleContext context,
                                               String continuationGroupKey,
                                               SkuScheduleDTO sku) {
        if (sku == null || StringUtils.isEmpty(continuationGroupKey)) {
            return 0;
        }
        if (isSingleMachineReducedContinuationGroup(context, sku)) {
            // 单机降模释放的零结果不代表本轮未排，剩余余量继续由后续滚动排程承接。
            return 0;
        }
        int targetScheduleQty = resolveZeroPlanControlTargetQty(sku);
        int retainedQty = resolveEffectiveContinuousPhaseScheduledQty(context, continuationGroupKey);
        if (shouldUseTargetQtyForContinuationReduction(sku)) {
            // 同物料多机台清尾可能来自多个运行态SKU副本，零结果未排需按物料最终有效排量对账。
            retainedQty = Math.max(retainedQty,
                    resolveEffectiveScheduledQty(context, sku.getMaterialCode(), CONTINUOUS_SCHEDULE_TYPE));
        }
        return Math.max(targetScheduleQty - retainedQty, 0);
    }

    /**
     * 统计同续作业务分组在续作阶段最终保留的有效计划量（含换活字块）。
     *
     * @param context 排程上下文
     * @param continuationGroupKey 续作业务分组键
     * @return 有效计划量
     */
    private int resolveEffectiveContinuousPhaseScheduledQty(LhScheduleContext context, String continuationGroupKey) {
        if (context == null || StringUtils.isEmpty(continuationGroupKey)
                || CollectionUtils.isEmpty(context.getScheduleResultList())) {
            return 0;
        }
        int totalQty = 0;
        for (LhScheduleResult result : context.getScheduleResultList()) {
            if (result == null
                    || !isContinuousPhaseResult(result)
                    || result.getDailyPlanQty() == null
                    || result.getDailyPlanQty() <= 0) {
                continue;
            }
            if (!StringUtils.equals(resolveContinuationGroupKey(context, result), continuationGroupKey)) {
                continue;
            }
            totalQty += result.getDailyPlanQty();
        }
        return totalQty;
    }

    /**
     * 统计同物料最终仍保留在结果列表中的有效计划量。
     *
     * @param context 排程上下文
     * @param materialCode 物料编码
     * @param scheduleType 排产类型
     * @return 有效计划量
     */
    private int resolveEffectiveScheduledQty(LhScheduleContext context, String materialCode, String scheduleType) {
        if (context == null || StringUtils.isEmpty(materialCode) || CollectionUtils.isEmpty(context.getScheduleResultList())) {
            return 0;
        }
        int totalQty = 0;
        for (LhScheduleResult result : context.getScheduleResultList()) {
            if (result == null
                    || !StringUtils.equals(materialCode, result.getMaterialCode())
                    || !StringUtils.equals(scheduleType, result.getScheduleType())
                    || result.getDailyPlanQty() == null
                    || result.getDailyPlanQty() <= 0) {
                continue;
            }
            totalQty += result.getDailyPlanQty();
        }
        return totalQty;
    }

    /**
     * 按物料维度写入/合并未排结果，保证同物料仅一条记录。
     *
     * @param context 排程上下文
     * @param materialCode 物料编码
     * @param unscheduledQty 未排数量
     */
    private void mergeUnscheduledResultByMaterial(LhScheduleContext context, String materialCode, int unscheduledQty) {
        if (context == null || StringUtils.isEmpty(materialCode)) {
            return;
        }
        if (isZeroPlanUnscheduledCoveredByMaterialResult(context, materialCode)) {
            LhUnscheduledResult existing = findUnscheduledResultByMaterial(context, materialCode);
            if (existing != null && StringUtils.equals(ZERO_PLAN_UNSCHEDULED_REASON, existing.getUnscheduledReason())) {
                context.getUnscheduledResultList().remove(existing);
            }
            log.info("续作零结果未排跳过, materialCode: {}, 原未排量: {}, 原因: 同物料续作有效排量已覆盖清尾目标",
                    materialCode, Math.max(unscheduledQty, 0));
            return;
        }
        LhUnscheduledResult existing = findUnscheduledResultByMaterial(context, materialCode);
        if (existing != null) {
            int existingQty = existing.getUnscheduledQty() != null ? existing.getUnscheduledQty() : 0;
            existing.setUnscheduledQty(existingQty + Math.max(unscheduledQty, 0));
            if (StringUtils.isEmpty(existing.getUnscheduledReason())) {
                existing.setUnscheduledReason(ZERO_PLAN_UNSCHEDULED_REASON);
            }
            return;
        }
        SkuScheduleDTO sku = findSkuDto(context, materialCode);
        LhUnscheduledResult unscheduled = new LhUnscheduledResult();
        unscheduled.setFactoryCode(context.getFactoryCode());
        unscheduled.setBatchNo(context.getBatchNo());
        unscheduled.setScheduleDate(context.getScheduleTargetDate());
        unscheduled.setMaterialCode(materialCode);
        unscheduled.setUnscheduledQty(Math.max(unscheduledQty, 0));
        unscheduled.setUnscheduledReason(ZERO_PLAN_UNSCHEDULED_REASON);
        unscheduled.setDataSource(AUTO_DATA_SOURCE);
        unscheduled.setIsDelete(0);
        if (sku != null) {
            unscheduled.setMaterialDesc(sku.getMaterialDesc());
            unscheduled.setStructureName(sku.getStructureName());
            unscheduled.setMainMaterialDesc(sku.getMainMaterialDesc());
            unscheduled.setSpecCode(sku.getSpecCode());
            unscheduled.setEmbryoCode(sku.getEmbryoCode());
            unscheduled.setMouldQty(sku.getMouldQty());
        }
        context.getUnscheduledResultList().add(unscheduled);
    }

    /**
     * 移除已被同物料续作有效结果覆盖的零结果未排误报。
     *
     * @param context 排程上下文
     */
    private void removeCoveredZeroPlanContinuousUnscheduledResults(LhScheduleContext context) {
        if (context == null || CollectionUtils.isEmpty(context.getUnscheduledResultList())) {
            return;
        }
        Iterator<LhUnscheduledResult> iterator = context.getUnscheduledResultList().iterator();
        while (iterator.hasNext()) {
            LhUnscheduledResult unscheduled = iterator.next();
            if (unscheduled == null
                    || !StringUtils.equals(ZERO_PLAN_UNSCHEDULED_REASON, unscheduled.getUnscheduledReason())
                    || StringUtils.isEmpty(unscheduled.getMaterialCode())) {
                continue;
            }
            if (isZeroPlanUnscheduledCoveredByMaterialResult(context, unscheduled.getMaterialCode())) {
                iterator.remove();
                log.info("续作零结果未排最终清理, materialCode: {}, unscheduledQty: {}, 原因: 同物料续作有效排量已覆盖清尾目标",
                        unscheduled.getMaterialCode(), unscheduled.getUnscheduledQty());
            } else {
                SkuScheduleDTO sku = findSkuDto(context, unscheduled.getMaterialCode());
                int controlTargetQty = resolveZeroPlanControlTargetQty(sku);
                int retainedQty = resolveEffectiveScheduledQty(context, unscheduled.getMaterialCode(), CONTINUOUS_SCHEDULE_TYPE);
                log.info("续作零结果未排保留, materialCode: {}, unscheduledQty: {}, controlTargetQty: {}, retainedQty: {}, "
                                + "targetScheduleQty: {}, surplusQty: {}, embryoStock: {}",
                        unscheduled.getMaterialCode(), unscheduled.getUnscheduledQty(), controlTargetQty, retainedQty,
                        sku != null ? sku.resolveTargetScheduleQty() : null,
                        sku != null ? sku.getSurplusQty() : null,
                        sku != null ? sku.getEmbryoStock() : null);
            }
        }
    }

    /**
     * 判断同物料续作有效结果是否已经覆盖零结果未排量。
     *
     * @param context 排程上下文
     * @param materialCode 物料编码
     * @return true-已覆盖，不需要写入裁剪未排
     */
    private boolean isZeroPlanUnscheduledCoveredByMaterialResult(LhScheduleContext context, String materialCode) {
        SkuScheduleDTO sku = findSkuDto(context, materialCode);
        if (sku == null) {
            return false;
        }
        int controlTargetQty = resolveZeroPlanControlTargetQty(sku);
        if (controlTargetQty <= 0) {
            return false;
        }
        int retainedQty = resolveEffectiveScheduledQty(context, materialCode, CONTINUOUS_SCHEDULE_TYPE);
        return retainedQty >= controlTargetQty;
    }

    /**
     * 解析续作零结果未排的清尾控制量。
     *
     * @param sku SKU
     * @return 清尾控制量
     */
    private int resolveZeroPlanControlTargetQty(SkuScheduleDTO sku) {
        if (sku == null) {
            return 0;
        }
        int targetScheduleQty = Math.max(0, sku.resolveTargetScheduleQty());
        int materialAvailableTargetQty = Math.max(Math.max(0, sku.getSurplusQty()), Math.max(0, sku.getEmbryoStock()));
        if (shouldUseTargetQtyForContinuationReduction(sku)
                && materialAvailableTargetQty > 0
                && targetScheduleQty > materialAvailableTargetQty) {
            // 零结果未排只反映清尾未完成量，不能把窗口日计划目标量当成硫化余量之外的未排缺口。
            return materialAvailableTargetQty;
        }
        return Math.max(targetScheduleQty, materialAvailableTargetQty);
    }

    /**
     * 共用胎胚余量为0导致收尾目标量为0时，写入未排记录。
     *
     * @param context 排程上下文
     * @param sourceSku 来源SKU
     */
    private void appendSharedEmbryoZeroSurplusUnscheduledIfNecessary(LhScheduleContext context,
                                                                     SkuScheduleDTO sourceSku) {
        if (sourceSku == null || StringUtils.isEmpty(sourceSku.getMaterialCode())) {
            return;
        }
        if (sourceSku.getSurplusQty() > 0 || sourceSku.getEmbryoStock() <= 0) {
            return;
        }
        Boolean sharedEmbryo = context.getMaterialSharedEmbryoMap() != null
                ? context.getMaterialSharedEmbryoMap().get(sourceSku.getMaterialCode()) : null;
        if (!Boolean.TRUE.equals(sharedEmbryo)) {
            return;
        }
        if (findUnscheduledResultByMaterial(context, sourceSku.getMaterialCode()) != null) {
            return;
        }
        LhUnscheduledResult unscheduled = new LhUnscheduledResult();
        unscheduled.setFactoryCode(context.getFactoryCode());
        unscheduled.setBatchNo(context.getBatchNo());
        unscheduled.setScheduleDate(context.getScheduleTargetDate());
        unscheduled.setMaterialCode(sourceSku.getMaterialCode());
        unscheduled.setMaterialDesc(sourceSku.getMaterialDesc());
        unscheduled.setStructureName(sourceSku.getStructureName());
        unscheduled.setMainMaterialDesc(sourceSku.getMainMaterialDesc());
        unscheduled.setSpecCode(sourceSku.getSpecCode());
        unscheduled.setEmbryoCode(sourceSku.getEmbryoCode());
        unscheduled.setMouldQty(sourceSku.getMouldQty());
        unscheduled.setUnscheduledQty(0);
        unscheduled.setUnscheduledReason(SHARED_EMBRYO_ZERO_SURPLUS_UNSCHEDULED_REASON);
        unscheduled.setDataSource(AUTO_DATA_SOURCE);
        unscheduled.setIsDelete(0);
        context.getUnscheduledResultList().add(unscheduled);
        getTargetScheduleQtyResolver().removeActiveEmbryoSku(
                context, sourceSku, SHARED_EMBRYO_ZERO_SURPLUS_UNSCHEDULED_REASON);
        log.info("共用胎胚余量为0写入未排记录, materialCode: {}, embryoCode: {}, surplusQty: {}, embryoStock: {}",
                sourceSku.getMaterialCode(), sourceSku.getEmbryoCode(),
                sourceSku.getSurplusQty(), sourceSku.getEmbryoStock());
    }

    /**
     * 根据物料编码查找已存在未排结果。
     *
     * @param context 排程上下文
     * @param materialCode 物料编码
     * @return 未排结果
     */
    private LhUnscheduledResult findUnscheduledResultByMaterial(LhScheduleContext context, String materialCode) {
        if (context == null || CollectionUtils.isEmpty(context.getUnscheduledResultList())) {
            return null;
        }
        for (LhUnscheduledResult unscheduledResult : context.getUnscheduledResultList()) {
            if (StringUtils.equals(materialCode, unscheduledResult.getMaterialCode())) {
                return unscheduledResult;
            }
        }
        return null;
    }

    /**
     * 按物料编码归并未排结果，确保同物料只保留一条记录。
     *
     * @param context 排程上下文
     */
    private void normalizeUnscheduledResultsByMaterial(LhScheduleContext context) {
        if (context == null || CollectionUtils.isEmpty(context.getUnscheduledResultList())) {
            return;
        }
        Map<String, LhUnscheduledResult> mergedMap = new LinkedHashMap<>(context.getUnscheduledResultList().size());
        for (LhUnscheduledResult unscheduledResult : context.getUnscheduledResultList()) {
            if (unscheduledResult == null || StringUtils.isEmpty(unscheduledResult.getMaterialCode())) {
                continue;
            }
            String materialCode = unscheduledResult.getMaterialCode();
            if (!mergedMap.containsKey(materialCode)) {
                mergedMap.put(materialCode, unscheduledResult);
                continue;
            }
            LhUnscheduledResult existing = mergedMap.get(materialCode);
            int existingQty = existing.getUnscheduledQty() != null ? existing.getUnscheduledQty() : 0;
            int currentQty = unscheduledResult.getUnscheduledQty() != null ? unscheduledResult.getUnscheduledQty() : 0;
            existing.setUnscheduledQty(existingQty + currentQty);
            if (StringUtils.isEmpty(existing.getUnscheduledReason())) {
                existing.setUnscheduledReason(unscheduledResult.getUnscheduledReason());
            }
        }
        context.getUnscheduledResultList().clear();
        context.getUnscheduledResultList().addAll(mergedMap.values());
    }

    /**
     * 从机台已分配结果中移除零计划续作结果，避免占用后续选机上下文。
     *
     * @param context 排程上下文
     * @param resultsToRemove 待移除结果列表
     */
    private void removeResultsFromMachineAssignments(LhScheduleContext context, List<LhScheduleResult> resultsToRemove) {
        if (context == null
                || CollectionUtils.isEmpty(resultsToRemove)
                || CollectionUtils.isEmpty(context.getMachineAssignmentMap())) {
            return;
        }
        Iterator<Map.Entry<String, List<LhScheduleResult>>> iterator =
                context.getMachineAssignmentMap().entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, List<LhScheduleResult>> entry = iterator.next();
            List<LhScheduleResult> assignedResults = entry.getValue();
            if (CollectionUtils.isEmpty(assignedResults)) {
                iterator.remove();
                continue;
            }
            assignedResults.removeAll(resultsToRemove);
            if (assignedResults.isEmpty()) {
                iterator.remove();
            }
        }
    }

    private Date resolveActualCompletionTime(LhScheduleContext context, LhScheduleResult result) {
        if (result == null) {
            return null;
        }
        int lhTimeSeconds = result.getLhTime() != null ? result.getLhTime() : 0;
        int mouldQty = ShiftCapacityResolverUtil.resolveMachineMouldQty(
                result.getMouldQty() != null ? result.getMouldQty() : 0);
        if (lhTimeSeconds > 0 && mouldQty > 0) {
            Date actualCompletionTime = null;
            List<MachineCleaningWindowDTO> cleaningWindowList = resolveEffectiveCleaningWindowList(
                    context, result, resolveFirstPlannedShiftStartTime(result));
            List<MachineMaintenanceWindowDTO> maintenanceWindowList = resolveMachineMaintenanceWindowList(
                    context, result.getLhMachineCode());
            for (int shiftIndex = 1; shiftIndex <= 8; shiftIndex++) {
                Integer shiftPlanQty = ShiftFieldUtil.getShiftPlanQty(result, shiftIndex);
                Date shiftStartTime = ShiftFieldUtil.getShiftStartTime(result, shiftIndex);
                if (shiftPlanQty == null || shiftPlanQty <= 0 || shiftStartTime == null) {
                    continue;
                }
                Date shiftEndTime = ShiftFieldUtil.getShiftEndTime(result, shiftIndex);
                long secondsNeeded = (long) Math.ceil((double) shiftPlanQty / mouldQty) * lhTimeSeconds;
                Date shiftCompletionTime = ShiftCapacityResolverUtil.resolveCompletionTimeWithDowntimes(
                        context.getDevicePlanShutList(),
                        cleaningWindowList,
                        maintenanceWindowList,
                        result.getLhMachineCode(),
                        shiftStartTime,
                        secondsNeeded);
                if (shiftCompletionTime == null) {
                    shiftCompletionTime = shiftEndTime;
                } else {
                    shiftCompletionTime = constrainCompletionWithinShift(shiftCompletionTime, shiftEndTime);
                }
                if (actualCompletionTime == null || shiftCompletionTime.after(actualCompletionTime)) {
                    actualCompletionTime = shiftCompletionTime;
                }
            }
            if (actualCompletionTime != null) {
                return actualCompletionTime;
            }
        }
        return result.getSpecEndTime();
    }

    /**
     * 获取首个有排产量的班次索引。
     *
     * @param result 排程结果
     * @return 班次索引；未找到返回 -1
     */
    private int resolveFirstPlannedShiftIndex(LhScheduleResult result) {
        if (result == null) {
            return -1;
        }
        for (int shiftIndex = 1; shiftIndex <= LhScheduleConstant.MAX_SHIFT_SLOT_COUNT; shiftIndex++) {
            Integer shiftPlanQty = ShiftFieldUtil.getShiftPlanQty(result, shiftIndex);
            if (shiftPlanQty != null && shiftPlanQty > 0) {
                return shiftIndex;
            }
        }
        return -1;
    }

    /**
     * 获取首个有排产量班次的开始时间。
     *
     * @param result 排程结果
     * @return 班次开始时间
     */
    private Date resolveFirstPlannedShiftStartTime(LhScheduleResult result) {
        int firstPlannedShiftIndex = resolveFirstPlannedShiftIndex(result);
        return firstPlannedShiftIndex > 0
                ? ShiftFieldUtil.getShiftStartTime(result, firstPlannedShiftIndex) : null;
    }

    /**
     * 约束班次真实完工时刻不晚于该班次结束时刻，避免跨班时刻反向污染收尾判断。
     *
     * @param completionTime 计算出的完工时刻
     * @param shiftEndTime 班次结束时刻
     * @return 约束后的完工时刻
     */
    private Date constrainCompletionWithinShift(Date completionTime, Date shiftEndTime) {
        if (completionTime == null) {
            return shiftEndTime;
        }
        if (shiftEndTime == null) {
            return completionTime;
        }
        return completionTime.after(shiftEndTime) ? shiftEndTime : completionTime;
    }

    private List<MachineCleaningWindowDTO> resolveMachineCleaningWindowList(LhScheduleContext context, String machineCode) {
        MachineScheduleDTO machine = context.getMachineScheduleMap().get(machineCode);
        if (machine == null || CollectionUtils.isEmpty(machine.getCleaningWindowList())) {
            return new ArrayList<>();
        }
        return machine.getCleaningWindowList();
    }

    private List<MachineMaintenanceWindowDTO> resolveMachineMaintenanceWindowList(LhScheduleContext context, String machineCode) {
        MachineScheduleDTO machine = context.getMachineScheduleMap().get(machineCode);
        if (machine == null || CollectionUtils.isEmpty(machine.getMaintenanceWindowList())) {
            return new ArrayList<>();
        }
        return machine.getMaintenanceWindowList();
    }

    private List<MdmDevicePlanShut> resolveMachineShutdownWindowList(LhScheduleContext context, String machineCode) {
        if (context == null || CollectionUtils.isEmpty(context.getDevicePlanShutList())
                || StringUtils.isEmpty(machineCode)) {
            return new ArrayList<>();
        }
        List<MdmDevicePlanShut> shutdownWindowList = new ArrayList<>(4);
        for (MdmDevicePlanShut planShut : context.getDevicePlanShutList()) {
            if (planShut != null && StringUtils.equals(machineCode, planShut.getMachineCode())) {
                shutdownWindowList.add(planShut);
            }
        }
        return shutdownWindowList;
    }

    private void syncResultDowntimeSummary(LhScheduleContext context, LhScheduleResult result) {
        if (context == null || result == null) {
            return;
        }
        Date firstPlannedShiftStartTime = resolveFirstPlannedShiftStartTime(result);
        if (firstPlannedShiftStartTime == null || result.getSpecEndTime() == null) {
            ResultDowntimeSummaryUtil.clearDowntimeSummary(result);
            return;
        }
        ResultDowntimeSummaryUtil.fillDowntimeSummary(
                result,
                resolveMachineMaintenanceWindowList(context, result.getLhMachineCode()),
                resolveEffectiveCleaningWindowList(context, result, firstPlannedShiftStartTime),
                resolveMachineShutdownWindowList(context, result.getLhMachineCode()));
    }

    /**
     * 解析续作/换活字块结果在排产阶段需要生效的清洗窗口。
     *
     * @param context 排程上下文
     * @param result 排程结果
     * @param firstProductionStartTime 首个有排产量班次开始时间
     * @return 有效清洗窗口列表
     */
    private List<MachineCleaningWindowDTO> resolveEffectiveCleaningWindowList(LhScheduleContext context,
                                                                              LhScheduleResult result,
                                                                              Date firstProductionStartTime) {
        if (result == null) {
            return new ArrayList<>(0);
        }
        List<MachineCleaningWindowDTO> cleaningWindowList = resolveMachineCleaningWindowList(
                context, result.getLhMachineCode());
        return new ArrayList<>(MachineCleaningOverlapUtil.excludeOverlapWindows(
                cleaningWindowList, result.getMouldChangeStartTime(), firstProductionStartTime));
    }

    private String resolveMachineEmbryoCode(LhScheduleContext context, MachineScheduleDTO machine) {
        MdmMaterialInfo materialInfo = resolveMachineMaterialInfo(context, machine);
        if (materialInfo != null && StringUtils.isNotEmpty(materialInfo.getEmbryoCode())) {
            return materialInfo.getEmbryoCode();
        }
        SkuScheduleDTO currentSku = findSkuByMaterialCode(context.getContinuousSkuList(), machine.getCurrentMaterialCode());
        return currentSku != null ? currentSku.getEmbryoCode() : null;
    }

    private String resolveMachineSpecCode(LhScheduleContext context, MachineScheduleDTO machine) {
        if (StringUtils.isNotEmpty(machine.getPreviousSpecCode())) {
            return machine.getPreviousSpecCode();
        }
        MdmMaterialInfo materialInfo = resolveMachineMaterialInfo(context, machine);
        if (materialInfo != null && StringUtils.isNotEmpty(materialInfo.getSpecifications())) {
            return materialInfo.getSpecifications();
        }
        SkuScheduleDTO currentSku = findSkuByMaterialCode(context.getContinuousSkuList(), machine.getCurrentMaterialCode());
        return currentSku != null ? currentSku.getSpecCode() : null;
    }

    private String resolveMachinePatternKey(LhScheduleContext context, MachineScheduleDTO machine) {
        MdmMaterialInfo materialInfo = resolveMachineMaterialInfo(context, machine);
        if (materialInfo != null) {
            return resolvePatternKey(materialInfo.getMainPattern(), materialInfo.getPattern());
        }
        SkuScheduleDTO currentSku = findSkuByMaterialCode(context.getContinuousSkuList(), machine.getCurrentMaterialCode());
        if (currentSku == null) {
            return null;
        }
        return resolvePatternKey(currentSku.getMainPattern(), currentSku.getPattern());
    }

    private MdmMaterialInfo resolveMachineMaterialInfo(LhScheduleContext context, MachineScheduleDTO machine) {
        if (context == null || machine == null || StringUtils.isEmpty(machine.getCurrentMaterialCode())) {
            return null;
        }
        return context.getMaterialInfoMap().get(machine.getCurrentMaterialCode());
    }

    private SkuScheduleDTO findSkuByMaterialCode(List<SkuScheduleDTO> skuList, String materialCode) {
        if (CollectionUtils.isEmpty(skuList) || StringUtils.isEmpty(materialCode)) {
            return null;
        }
        for (SkuScheduleDTO sku : skuList) {
            if (StringUtils.equals(materialCode, sku.getMaterialCode())) {
                return sku;
            }
        }
        return null;
    }

    private String resolvePatternKey(String mainPattern, String pattern) {
        if (StringUtils.isNotEmpty(mainPattern)) {
            return mainPattern;
        }
        return StringUtils.isNotEmpty(pattern) ? pattern : null;
    }

    /**
     * 注册机台排程分配记录
     */
    private void registerMachineAssignment(LhScheduleContext context, String machineCode, LhScheduleResult result) {
        context.getMachineAssignmentMap()
                .computeIfAbsent(machineCode, k -> new ArrayList<>())
                .add(result);
    }

    /**
     * 预登记续作释放机台。
     * <p>S4.4 续作主循环中可能先为其他 SKU 做新增换模预判选机，释放机台必须在这些预判前完成降级标记。</p>
     *
     * @param context 排程上下文
     * @param shifts 排程窗口班次
     */
    private void preRegisterReleasedContinuousMachines(LhScheduleContext context, List<LhShiftConfigVO> shifts) {
        if (context == null || CollectionUtils.isEmpty(context.getContinuousSkuList())
                || CollectionUtils.isEmpty(shifts)) {
            return;
        }
        for (SkuScheduleDTO sku : context.getContinuousSkuList()) {
            if (sku == null || StringUtils.isEmpty(sku.getContinuousMachineCode())) {
                continue;
            }
            if (isContinuousWindowNoDailyPlan(sku) && Math.max(0, sku.getMonthlyHistoryShortageQty()) <= 0) {
                registerReleasedContinuousMachine(context, sku.getContinuousMachineCode(),
                        sku.getMaterialCode(), "窗口内无日计划");
                continue;
            }
            if (shouldReleaseFirstDayNoPlanContinuousSku(sku, shifts, null)) {
                registerReleasedFirstDayNoPlanContinuousMachine(context,
                        sku.getContinuousMachineCode(), sku.getMaterialCode());
            }
        }
    }

    /**
     * 登记续作阶段释放的机台，供S4.4换活字块识别和S4.5新增选机降低优先级使用。
     *
     * @param context 排程上下文
     * @param machineCode 机台编码
     * @param materialCode 续作SKU物料编码
     * @param reason 释放原因
     */
    private void registerReleasedContinuousMachine(LhScheduleContext context, String machineCode,
                                                   String materialCode, String reason) {
        if (context == null || StringUtils.isEmpty(machineCode)) {
            return;
        }
        boolean added = context.getReleasedContinuousMachineCodeSet().add(machineCode);
        if (added) {
            log.info("登记续作释放机台, materialCode: {}, machineCode: {}, reason: {}, "
                            + "effect: S4.4换活字块可识别，S4.5新增选机仅降优先级",
                    materialCode, machineCode, reason);
        }
    }

    /**
     * 登记可优先进入换活字块匹配的续作释放机台。
     *
     * @param context 排程上下文
     * @param machineCode 机台编码
     * @param materialCode 续作SKU物料编码
     * @param reason 释放原因
     */
    private void registerTypeBlockReleasedContinuousMachine(LhScheduleContext context,
                                                            String machineCode,
                                                            String materialCode,
                                                            String reason) {
        if (context == null || StringUtils.isEmpty(machineCode)) {
            return;
        }
        boolean added = context.getTypeBlockReleasedContinuousMachineCodeSet().add(machineCode);
        if (added) {
            log.info("登记续作释放机台优先进入换活字块, materialCode: {}, machineCode: {}, reason: {}",
                    materialCode, machineCode, reason);
        }
    }

    /**
     * 登记首日无计划但后续仍有计划的续作释放机台。
     * <p>该标记属于初始化阶段业务事实，后续识别占位结果时不能再依赖会被账本扣减改写的 remainingQty。</p>
     *
     * @param context 排程上下文
     * @param machineCode 机台编码
     * @param materialCode 续作SKU物料编码
     */
    private void registerReleasedFirstDayNoPlanContinuousMachine(LhScheduleContext context,
                                                                 String machineCode,
                                                                 String materialCode) {
        registerReleasedContinuousMachine(context, machineCode, materialCode, "续作首日无计划");
        if (context == null || StringUtils.isEmpty(machineCode)) {
            return;
        }
        context.getFirstDayNoPlanReleasedContinuousMachineCodeSet().add(machineCode);
    }

    /**
     * 注册结果与来源SKU的运行态映射。
     *
     * @param context 排程上下文
     * @param result 排程结果
     * @param sku 来源SKU
     */
    private void registerResultSourceSku(LhScheduleContext context, LhScheduleResult result, SkuScheduleDTO sku) {
        if (context == null || result == null || sku == null) {
            return;
        }
        context.getScheduleResultSourceSkuMap().put(result, sku);
    }

    /**
     * 解析排程结果对应的来源SKU。
     * <p>优先命中运行态映射；未注册时回退到物料编码查找，兼容旧测试夹具。</p>
     *
     * @param context 排程上下文
     * @param result 排程结果
     * @return 来源SKU
     */
    private SkuScheduleDTO resolveResultSourceSku(LhScheduleContext context, LhScheduleResult result) {
        if (context == null || result == null) {
            return null;
        }
        SkuScheduleDTO sourceSku = context.getScheduleResultSourceSkuMap().get(result);
        if (sourceSku != null) {
            return sourceSku;
        }
        return findSkuDto(context, result.getMaterialCode());
    }

    /**
     * 解析续作阶段结果对应的来源SKU映射。
     * <p>共享账本分组链路必须依赖运行态映射，缺失时直接报错，避免静默按物料编码串组。</p>
     *
     * @param context 排程上下文
     * @param result 续作阶段结果
     * @return 来源SKU
     */
    private SkuScheduleDTO requireContinuousPhaseSourceSku(LhScheduleContext context, LhScheduleResult result) {
        if (context == null || result == null) {
            throw new IllegalStateException("续作阶段结果缺少sourceSku映射: context或result为空");
        }
        SkuScheduleDTO sourceSku = context.getScheduleResultSourceSkuMap().get(result);
        if (sourceSku != null) {
            return sourceSku;
        }
        throw new IllegalStateException(String.format(
                "续作阶段结果缺少sourceSku映射: scheduleType=%s, machineCode=%s, materialCode=%s",
                result.getScheduleType(), result.getLhMachineCode(), result.getMaterialCode()));
    }

    /**
     * 在所有SKU列表中查找指定materialCode的SKU
     */
    private SkuScheduleDTO findSkuDto(LhScheduleContext context, String materialCode) {
        if (context == null || StringUtils.isEmpty(materialCode)) {
            return null;
        }
        for (SkuScheduleDTO sku : context.getContinuousSkuList()) {
            if (materialCode.equals(sku.getMaterialCode())) {
                return sku;
            }
        }
        for (SkuScheduleDTO sku : context.getNewSpecSkuList()) {
            if (materialCode.equals(sku.getMaterialCode())) {
                return sku;
            }
        }
        if (!CollectionUtils.isEmpty(context.getStructureSkuMap())) {
            for (List<SkuScheduleDTO> skuList : context.getStructureSkuMap().values()) {
                if (CollectionUtils.isEmpty(skuList)) {
                    continue;
                }
                for (SkuScheduleDTO sku : skuList) {
                    if (materialCode.equals(sku.getMaterialCode())) {
                        return sku;
                    }
                }
            }
        }
        return null;
    }

    /**
     * 生成工单号（使用线程安全的OrderNoGenerator）
     */
    private String generateOrderNo(LhScheduleContext context) {
        return orderNoGenerator.generateOrderNo(context.getScheduleTargetDate());
    }

    /**
     * 获取目标排产量解析器。
     *
     * @return 目标排产量解析器
     */
    private TargetScheduleQtyResolver getTargetScheduleQtyResolver() {
        return targetScheduleQtyResolver != null
                ? targetScheduleQtyResolver
                : new TargetScheduleQtyResolver();
    }

    private IMouldChangeBalanceStrategy getMouldChangeBalanceStrategy() {
        return mouldChangeBalanceStrategy;
    }

    private IFirstInspectionBalanceStrategy getFirstInspectionBalanceStrategy() {
        return firstInspectionBalanceStrategy;
    }

    private ICapacityCalculateStrategy getCapacityCalculateStrategy() {
        return capacityCalculateStrategy;
    }

    private LhMaintenanceScheduleService getMaintenanceScheduleService() {
        return maintenanceScheduleService != null
                ? maintenanceScheduleService
                : new LhMaintenanceScheduleService();
    }
}
