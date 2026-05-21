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
import com.zlt.aps.lh.engine.strategy.ICapacityCalculateStrategy;
import com.zlt.aps.lh.engine.strategy.IEndingJudgmentStrategy;
import com.zlt.aps.lh.engine.strategy.IFirstInspectionBalanceStrategy;
import com.zlt.aps.lh.engine.strategy.IMachineMatchStrategy;
import com.zlt.aps.lh.engine.strategy.IMouldChangeBalanceStrategy;
import com.zlt.aps.lh.engine.strategy.IProductionStrategy;
import com.zlt.aps.lh.engine.strategy.support.ProductionQuantityPolicy;
import com.zlt.aps.lh.service.impl.LhMaintenanceScheduleService;
import com.zlt.aps.lh.util.LeftRightMouldUtil;
import com.zlt.aps.lh.util.ShiftFieldUtil;
import com.zlt.aps.lh.util.LhScheduleTimeUtil;
import com.zlt.aps.lh.util.LhSpecialMaterialUtil;
import com.zlt.aps.lh.util.LhMultiMachineDistributionUtil;
import com.zlt.aps.lh.util.LhSpecifyMachineUtil;
import com.zlt.aps.lh.util.MachineCleaningOverlapUtil;
import com.zlt.aps.lh.util.PriorityTraceLogHelper;
import com.zlt.aps.lh.util.ShiftCapacityResolverUtil;
import com.zlt.aps.lh.util.ShiftProductionControlUtil;
import com.zlt.aps.lh.util.SingleMouldShiftQtyUtil;
import com.zlt.aps.lh.util.SkuDailyPlanQuotaUtil;
import com.zlt.aps.lh.component.OrderNoGenerator;
import com.zlt.aps.mdm.api.domain.entity.MdmMaterialInfo;
import com.zlt.aps.mdm.api.domain.entity.MdmDevicePlanShut;
import com.zlt.aps.mdm.api.domain.entity.MdmSkuConstructionRef;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import javax.annotation.Resource;

import java.time.LocalDate;
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
 * 续作排产策略实现
 * <p>处理续作场景下的排产逻辑, 包括收尾判定、班次分配、库存调整、降模等</p>
 *
 * @author APS
 */
@Slf4j
@Component("continuousProductionStrategy")
public class ContinuousProductionStrategy implements IProductionStrategy {

    private static final String CONTINUOUS_SCHEDULE_TYPE = "01";
    private static final String AUTO_DATA_SOURCE = "0";
    private static final String ZERO_PLAN_UNSCHEDULED_REASON = "续作结果裁剪为0";
    private static final int TYPE_BLOCK_SWITCH_MAX_ATTEMPTS = 16;

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

        for (SkuScheduleDTO sku : context.getContinuousSkuList()) {
            String machineCode = sku.getContinuousMachineCode();
            MachineScheduleDTO machine = context.getMachineScheduleMap().get(machineCode);
            if (machine == null) {
                log.warn("续作SKU未匹配到机台状态，跳过续作排产, materialCode: {}, 续作机台: {}, 目标量: {}",
                        sku.getMaterialCode(), machineCode, sku.resolveTargetScheduleQty());
                continue;
            }

            boolean isEnding = endingJudgmentStrategy.isEnding(context, sku);
            sku.setStrictTargetQty(ProductionQuantityPolicy.from(sku, isEnding).isStrictUpperLimit());

            // 滚动衔接时沿用机台继承后的可用时间，避免从重叠窗口首班重复起排。
            Date startTime = resolveContinuousStartTime(context, machine, shifts);
            Date specifySwitchStartTime = !isEnding
                    ? tryReserveSpecifySqueezeSwitchStartTime(context, machine, sku, shifts) : null;
            List<LhShiftConfigVO> effectiveShifts = specifySwitchStartTime == null
                    ? shifts : filterShiftsBeforeSwitchStart(shifts, specifySwitchStartTime);
            int machineMouldQty = ShiftCapacityResolverUtil.resolveMachineMouldQty(machine);
            sku.setMouldQty(machineMouldQty);
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

                // 如果是收尾，更新机台收尾信息
                if (isEnding && result.getSpecEndTime() != null) {
                    Date actualCompletionTime = resolveActualCompletionTime(context, result);
                    machine.setEnding(true);
                    machine.setEstimatedEndTime(actualCompletionTime);
                    traceContinuousEndingUpdate(context, machine, sku, result, actualCompletionTime);
                } else if (specifySwitchStartTime != null && result.getDailyPlanQty() != null
                        && result.getDailyPlanQty() > 0) {
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
     * 解析续作起排时间。
     * <p>滚动衔接场景下从机台继承后的可用时间继续排；普通场景仍从窗口首班开始。</p>
     */
    private Date resolveContinuousStartTime(LhScheduleContext context,
                                            MachineScheduleDTO machine,
                                            List<LhShiftConfigVO> shifts) {
        Date defaultStartTime = CollectionUtils.isEmpty(shifts) ? new Date() : shifts.get(0).getShiftStartDateTime();
        if (context == null || !context.isRollingScheduleHandoff()) {
            return defaultStartTime;
        }
        Date appendStartTime = resolveRollingAppendStartTime(context, shifts);
        if (machine == null || machine.getEstimatedEndTime() == null) {
            return appendStartTime != null ? appendStartTime : defaultStartTime;
        }
        if (appendStartTime == null || machine.getEstimatedEndTime().after(appendStartTime)) {
            return machine.getEstimatedEndTime();
        }
        return appendStartTime;
    }

    /**
     * 解析滚动排程的追加起点。
     * <p>只允许续作从目标日第一班开始继续排，避免回写到重叠继承窗口。</p>
     */
    private Date resolveRollingAppendStartTime(LhScheduleContext context, List<LhShiftConfigVO> shifts) {
        if (context == null
                || context.getScheduleTargetDate() == null
                || CollectionUtils.isEmpty(shifts)) {
            return null;
        }
        Date targetDate = LhScheduleTimeUtil.clearTime(context.getScheduleTargetDate());
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
            if (!isContinuousPhaseResult(result)) {
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
            String groupKey = buildContinuationGroupKey(sourceSku);
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

            log.info("续作同SKU多机台识别, materialCode: {}, 机台列表: {}, 是否多机台: {}",
                    sourceSku.getMaterialCode(), joinMachineCodes(skuResults), true);

            if (shouldReduceContinuationByWorkDate(sourceSku, skuResults)) {
                reduceContinuationMachinesByWorkDate(context, sourceSku, skuResults, shifts);
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
        }
        // 日额度账本必须在最终结果收口后再同步，并以回裁后的结果驱动零计划与机台状态。
        syncContinuousDailyPlanQuota(context, shifts);
        appendContinuousCompensationSkuList(context);
        // S4.4 收口：零计划续作结果语义统一，并按最终结果同步机台状态。
        finalizeZeroPlanContinuousResults(context);
        adjustContinuousSameSkuMultiMachineEndingStagger(context, shifts);
        finalizeZeroPlanContinuousResults(context);
        // 降模或额度回裁会再次改变最终计划量，收口后再统一复核一次收尾标记，确保落库口径一致。
        refreshContinuousEndingFlagByResult(context);
        // 续作最终结果稳定后，再按保留结果分摊多机台胎胚库存，避免零计划结果残留旧口径。
        distributeMultiMachineSurplusAndStock(context);
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
     * @return true-按业务日降模
     */
    private boolean shouldReduceContinuationByWorkDate(SkuScheduleDTO sourceSku,
                                                       List<LhScheduleResult> skuResults) {
        return sourceSku != null
                && !hasEndingResult(skuResults)
                && !CollectionUtils.isEmpty(sourceSku.getDailyPlanQuotaMap())
                && sourceSku.getDailyPlanQuotaMap().size() > 1;
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
        ProductionQuantityPolicy policy = ProductionQuantityPolicy.from(sourceSku, false);
        for (Map.Entry<LocalDate, List<LhShiftConfigVO>> entry : shiftMapByDate.entrySet()) {
            if (CollectionUtils.isEmpty(activeResults)) {
                break;
            }
            LocalDate productionDate = entry.getKey();
            List<LhShiftConfigVO> dayShifts = entry.getValue();
            int demandQty = resolveContinuationDailyDemandByDate(sourceSku, productionDate);
            int effectiveDemandQty = policy.isStrictUpperLimit()
                    ? Math.min(Math.max(0, demandQty), remainingTargetQty)
                    : Math.max(0, demandQty);
            Map<LhScheduleResult, Integer> capacityMap =
                    calculateMachineDailyCapacityMapByDate(context, activeResults, dayShifts);
            int totalCapacity = capacityMap.values().stream().mapToInt(Integer::intValue).sum();
            int totalPlanQty = sumScheduledQtyByShifts(activeResults, dayShifts);
            log.info("续作多机台按天降模判断, materialCode: {}, 日期: {}, dayN保障量: {}, 剩余窗口目标量: {}, 当日生效目标量: {}, 当前在机最大日产能: {}, 当前排产量: {}",
                    sourceSku.getMaterialCode(), productionDate, demandQty, remainingTargetQty,
                    effectiveDemandQty, totalCapacity, totalPlanQty);
            List<LhScheduleResult> keptResults = selectMachinesToKeepForContinuation(
                    context, activeResults, capacityMap, effectiveDemandQty);
            applyContinuationDayAllocation(context, sourceSku, activeResults, keptResults, capacityMap,
                    demandQty, effectiveDemandQty, remainingTargetQty, productionDate, dayShifts, shifts);
            remainingTargetQty = Math.max(0, remainingTargetQty - sumScheduledQtyByShifts(activeResults, dayShifts));
            activeResults = new ArrayList<LhScheduleResult>(keptResults);
        }
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
     * 解析指定业务日的续作 dayN 保障量。
     *
     * @param sourceSku 来源SKU
     * @param productionDate 业务日
     * @return 当日保障量
     */
    private int resolveContinuationDailyDemandByDate(SkuScheduleDTO sourceSku, LocalDate productionDate) {
        if (sourceSku == null || productionDate == null || CollectionUtils.isEmpty(sourceSku.getDailyPlanQuotaMap())) {
            return 0;
        }
        SkuDailyPlanQuotaDTO quota = sourceSku.getDailyPlanQuotaMap().get(productionDate);
        if (quota == null) {
            return 0;
        }
        int demandQty = quota.getRemainingQty() > 0 ? quota.getRemainingQty() : quota.getDayPlanQty();
        log.info("续作多机台dayN保障量解析, materialCode: {}, 日期: {}, dayN: {}, 剩余额度: {}, 保障量: {}",
                sourceSku.getMaterialCode(), productionDate, quota.getDayPlanQty(),
                quota.getRemainingQty(), demandQty);
        return Math.max(0, demandQty);
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
        if (hasEndingResult(skuResults)) {
            int targetQty = sourceSku.resolveTargetScheduleQty();
            if (targetQty > 0) {
                log.info("续作多机台收尾严格目标, materialCode: {}, 目标量: {}, 是否补满: false, 是否超排: false",
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
                dryIceDurationHours);
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
            remainingDemandQty = Math.max(0, remainingDemandQty - allocation);
            log.info("续作多机台保留机台排量, materialCode: {}, machineCode: {}, allocation: {}, "
                            + "machineCapacity: {}, 是否补满班产: {}, 是否收尾: {}",
                    sourceSku.getMaterialCode(), result.getLhMachineCode(), allocation,
                    machineCapacity, fillKeptMachineCapacity, ending);
        }
        List<LhScheduleResult> removedResults = selectMachinesToRemoveForContinuation(context, skuResults, keptResults);
        for (LhScheduleResult result : removedResults) {
            redistributeShiftQty(context, result, shifts, 0);
        }
        log.info("续作多机台降模结果, materialCode: {}, 原始机台: {}, 保留机台: {}, 下机机台: {}, 原始机台明细: {}, "
                        + "保留机台明细: {}, 下机机台明细: {}, 原因: dayN保障量={}，按胶囊使用次数和机台编码排序",
                sourceSku.getMaterialCode(), joinMachineCodes(skuResults), joinMachineCodes(keptResults),
                joinMachineCodes(removedResults), formatContinuationMachineDetails(context, skuResults, capacityMap),
                formatContinuationMachineDetails(context, keptResults, capacityMap),
                formatContinuationMachineDetails(context, removedResults, capacityMap), demandQty);
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
                                                List<LhShiftConfigVO> allShifts) {
        ProductionQuantityPolicy policy = ProductionQuantityPolicy.from(sourceSku, false);
        boolean fillKeptMachineCapacity = !policy.isStrictUpperLimit()
                && !CollectionUtils.isEmpty(sourceSku.getDailyPlanQuotaMap());
        int remainingDemandQty = Math.max(0, effectiveDemandQty);
        for (LhScheduleResult result : keptResults) {
            int machineCapacity = Math.max(0, capacityMap.getOrDefault(result, 0));
            int allocation = fillKeptMachineCapacity ? machineCapacity : Math.min(remainingDemandQty, machineCapacity);
            redistributeShiftQty(context, result, dayShifts, allocation);
            remainingDemandQty = Math.max(0, remainingDemandQty - allocation);
            log.info("续作多机台保留机台排量, materialCode: {}, 日期: {}, machineCode: {}, allocation: {}, "
                            + "machineCapacity: {}, 是否补满班产: {}, 当日生效目标量: {}, 剩余窗口目标量: {}, 是否收尾: {}",
                    sourceSku.getMaterialCode(), productionDate, result.getLhMachineCode(), allocation,
                    machineCapacity, fillKeptMachineCapacity, effectiveDemandQty, remainingTargetQty, false);
        }
        List<LhScheduleResult> removedResults = selectMachinesToRemoveForContinuation(context, activeResults, keptResults);
        for (LhScheduleResult result : removedResults) {
            clearContinuationShiftsFromDate(context, result, allShifts, productionDate);
        }
        log.info("续作多机台降模结果, materialCode: {}, 日期: {}, 原始机台: {}, 保留机台: {}, 下机机台: {}, 原始机台明细: {}, "
                        + "保留机台明细: {}, 下机机台明细: {}, 原因: dayN保障量={}，当日生效目标量={}，剩余窗口目标量={}，按胶囊使用次数和机台编码排序",
                sourceSku.getMaterialCode(), productionDate, joinMachineCodes(activeResults), joinMachineCodes(keptResults),
                joinMachineCodes(removedResults), formatContinuationMachineDetails(context, activeResults, capacityMap),
                formatContinuationMachineDetails(context, keptResults, capacityMap),
                formatContinuationMachineDetails(context, removedResults, capacityMap),
                demandQty, effectiveDemandQty, remainingTargetQty);
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
     * 续作同SKU多机台收尾错峰。
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
                    || !"1".equals(result.getIsEnd())
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
                tryStaggerContinuousSameShiftEnding(context, sourceSku, shifts,
                        shiftEntry.getKey(), shiftEntry.getValue());
            }
        }
    }

    /**
     * 尝试错开续作同SKU同班次收尾。
     *
     * @param context 排程上下文
     * @param sourceSku 来源SKU
     * @param shifts 班次列表
     * @param endingShiftIndex 收尾班次索引
     * @param results 同班次收尾结果
     * @return true-已错开
     */
    private boolean tryStaggerContinuousSameShiftEnding(LhScheduleContext context,
                                                       SkuScheduleDTO sourceSku,
                                                       List<LhShiftConfigVO> shifts,
                                                       int endingShiftIndex,
                                                       List<LhScheduleResult> results) {
        LhShiftConfigVO endingShift = findShiftByIndex(shifts, endingShiftIndex);
        LhShiftConfigVO nextShift = findShiftByIndex(shifts, endingShiftIndex + 1);
        if (endingShift == null || nextShift == null) {
            return false;
        }
        boolean nightShift = StringUtils.equals(ShiftEnum.NIGHT_SHIFT.getCode(), endingShift.getShiftType());
        log.info("续作同SKU多机台收尾错峰判断, materialCode: {}, 收尾班次: {}, 是否晚班: {}, 是否同SKU多机台收尾: true",
                sourceSku != null ? sourceSku.getMaterialCode() : null, endingShiftIndex, nightShift);
        if (nightShift) {
            return false;
        }
        if (!isSameWorkDate(endingShift.getWorkDate(), nextShift.getWorkDate())) {
            return false;
        }
        LhScheduleResult donor = resolveTailDonorResult(results, endingShiftIndex);
        LhScheduleResult receiver = resolveTailReceiverResult(context, results, donor, nextShift);
        if (donor == null || receiver == null) {
            return false;
        }
        Integer donorQty = ShiftFieldUtil.getShiftPlanQty(donor, endingShiftIndex);
        if (donorQty == null || donorQty <= 0) {
            return false;
        }
        int availableQty = resolveAvailableShiftQtyForEndingStagger(context, receiver, nextShift);
        if (donorQty > availableQty) {
            log.info("续作同SKU多机台收尾错峰跳过, materialCode: {}, 承接机台: {}, 需转移: {}, 可用: {}",
                    sourceSku != null ? sourceSku.getMaterialCode() : null,
                    receiver.getLhMachineCode(), donorQty, availableQty);
            return false;
        }
        setShiftPlanQty(donor, endingShiftIndex, 0, null, null);
        Integer receiverExistingQty = ShiftFieldUtil.getShiftPlanQty(receiver, nextShift.getShiftIndex());
        int receiverNextQty = (receiverExistingQty != null ? receiverExistingQty : 0) + donorQty;
        setShiftPlanQty(receiver, nextShift.getShiftIndex(), receiverNextQty,
                nextShift.getShiftStartDateTime(), null);
        refreshResultSummary(context, donor, shifts);
        refreshResultSummary(context, receiver, shifts);
        log.info("续作同SKU多机台收尾尾量错开完成, materialCode: {}, 释放机台: {}, 承接机台: {}, "
                        + "原收尾班次: {}, 承接班次: {}, 转移数量: {}, 换模均衡检查: 交由现有换模均衡与S4.6校验",
                sourceSku != null ? sourceSku.getMaterialCode() : null,
                donor.getLhMachineCode(), receiver.getLhMachineCode(),
                endingShiftIndex, nextShift.getShiftIndex(), donorQty);
        return true;
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
     * 判断两个日期是否属于同一业务日。
     *
     * @param firstDate 第一个日期
     * @param secondDate 第二个日期
     * @return true-同一业务日
     */
    private boolean isSameWorkDate(Date firstDate, Date secondDate) {
        if (firstDate == null || secondDate == null) {
            return false;
        }
        return LhScheduleTimeUtil.clearTime(firstDate).equals(LhScheduleTimeUtil.clearTime(secondDate));
    }

    /**
     * 选择需要释放尾量的续作结果。
     *
     * @param results 同班次收尾结果
     * @param endingShiftIndex 收尾班次序号
     * @return 尾量转出结果
     */
    private LhScheduleResult resolveTailDonorResult(List<LhScheduleResult> results, int endingShiftIndex) {
        LhScheduleResult donor = null;
        int minEndingQty = Integer.MAX_VALUE;
        for (LhScheduleResult result : results) {
            Integer qty = ShiftFieldUtil.getShiftPlanQty(result, endingShiftIndex);
            if (qty == null || qty <= 0) {
                continue;
            }
            if (qty < minEndingQty) {
                donor = result;
                minEndingQty = qty;
            }
        }
        return donor;
    }

    /**
     * 选择承接尾量的续作结果。
     *
     * @param context 排程上下文
     * @param results 同班次收尾结果
     * @param donor 转出结果
     * @param nextShift 承接班次
     * @return 尾量承接结果
     */
    private LhScheduleResult resolveTailReceiverResult(LhScheduleContext context,
                                                       List<LhScheduleResult> results,
                                                       LhScheduleResult donor,
                                                       LhShiftConfigVO nextShift) {
        for (LhScheduleResult result : results) {
            if (result == null || result == donor) {
                continue;
            }
            if (resolveAvailableShiftQtyForEndingStagger(context, result, nextShift) > 0) {
                return result;
            }
        }
        return null;
    }

    /**
     * 解析收尾错峰承接班次的剩余可用量。
     *
     * @param context 排程上下文
     * @param result 承接结果
     * @param nextShift 承接班次
     * @return 剩余可用量
     */
    private int resolveAvailableShiftQtyForEndingStagger(LhScheduleContext context,
                                                         LhScheduleResult result,
                                                         LhShiftConfigVO nextShift) {
        int shiftCapacity = calculateResultShiftCapacity(context, result, nextShift);
        Integer existingQty = ShiftFieldUtil.getShiftPlanQty(result, nextShift.getShiftIndex());
        return Math.max(0, shiftCapacity - (existingQty != null ? existingQty : 0));
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
                    shifts);
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
        Date mouldChangeStartTime = getMouldChangeBalanceStrategy().allocateMouldChange(
                context, machine.getMachineCode(), switchReadyTime);
        if (mouldChangeStartTime == null) {
            log.debug("定点物料新增换模预判不可排, machineCode: {}, materialCode: {}, 原因: 无可用换模窗口",
                    machine.getMachineCode(), specifySku.getMaterialCode());
            return false;
        }
        Date inspectionTime = null;
        try {
            int switchDurationHours = maintenanceOverlapSwitch
                    ? LhScheduleTimeUtil.getMaintenanceOverlapSwitchHours(context)
                    : LhScheduleTimeUtil.getMouldChangeTotalHours(context);
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
                    context, specifySku, machine, mouldChangeStartTime, firstProductionStartTime, shifts);
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
                        + ", 是否收尾=" + PriorityTraceLogHelper.yesNo(machine.isEnding()));
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
        int runtimeShiftCapacity = ShiftCapacityResolverUtil.resolveRuntimeShiftCapacity(
                context, machine, sku.getShiftCapacity());
        result.setSingleMouldShiftQty(SingleMouldShiftQtyUtil.resolveSingleMouldShiftQty(
                context, sku, machine, mouldQty));
        result.setDailyPlanQty(0);
        result.setTotalDailyPlanQty(sku.getMonthPlanQty());
        result.setMouldSurplusQty(sku.getSurplusQty());
        result.setIsEnd(isEnding ? "1" : "0");
        result.setIsDelivery(sku.isDeliveryLocked() ? "1" : "0");
        result.setIsRelease("0");
        result.setDataSource("0");
        result.setIsDelete(0);
        result.setScheduleType(sku.getScheduleType() != null ? sku.getScheduleType() : "01");
        result.setIsTypeBlock("0");
        result.setConstructionStage(sku.getConstructionStage());
        // 设置产品状态（取自SKU与示方书关系的硫化示方书类型）
        MdmSkuConstructionRef constructionRef = context.getSkuConstructionRefMap().get(sku.getMaterialCode());
        result.setTrialStatus(constructionRef != null ? constructionRef.getLhType() : null);
        result.setChangedTrialStatus(constructionRef != null ? constructionRef.getLhType() : null);
        result.setEmbryoNo(sku.getEmbryoNo());
        result.setTextNo(sku.getTextNo());
        result.setLhNo(sku.getLhNo());
        result.setMonthPlanVersion(sku.getMonthPlanVersion());
        result.setProductionVersion(sku.getProductionVersion());
        result.setIsTrial(sku.isTrial() ? "1" : "0");
        result.setMachineOrder(machine.getMachineOrder());
        result.setHasSpecialMaterial(LhSpecialMaterialUtil.resolveHasSpecialMaterial(context, sku));

        // 生成工单号
        String orderNo = generateOrderNo(context);
        result.setOrderNo(orderNo);

        int refinedTargetQty = getTargetScheduleQtyResolver().refineTargetQtyByMachineCapacity(
                context, sku, machine, switchStartTime, startTime, shifts);
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
                    dryIceDurationHours);
            shiftMaxQty = ShiftProductionControlUtil.deductCapacityByControl(control, shiftMaxQty, mouldQty);
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
     * 按班次索引设置计划量和开始/结束时间（Hutool BeanUtil）
     */
    private void setShiftPlanQty(LhScheduleResult result, int shiftIndex, int qty, Date startTime, Date endTime) {
        ShiftFieldUtil.setShiftPlanQty(result, shiftIndex, qty, startTime, endTime);
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
                    dryIceDurationHours);
            shiftMaxQty = ShiftProductionControlUtil.deductCapacityByControl(control, shiftMaxQty, mouldQty);
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
     *
     * @param context 排程上下文
     * @param result 排程结果
     * @return max(硫化余量, 胎胚库存)
     */
    private int resolveEndingDemandQty(LhScheduleContext context, LhScheduleResult result) {
        SkuScheduleDTO sku = resolveResultSourceSku(context, result);
        int surplusQty = sku != null ? Math.max(0, sku.getSurplusQty()) : 0;
        int embryoStock = sku != null ? Math.max(0, sku.getEmbryoStock()) : 0;
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
            String groupKey = resolveContinuationGroupKey(context, result);
            if (sourceSku == null || StringUtils.isEmpty(groupKey) || !processedGroupKeySet.add(groupKey)) {
                continue;
            }
            int unscheduledQty = resolveRemainingUnscheduledQty(context, groupKey, sourceSku);
            if (unscheduledQty > 0) {
                zeroPlanQtyMap.merge(sourceSku.getMaterialCode(), unscheduledQty, Integer::sum);
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
     * 多机台余量和胎胚库存按机台条数均分。
     * <p>对续作阶段结果按来源SKU分组，委托 {@link LhMultiMachineDistributionUtil#distributeForSingleMaterial}
     * 按机台结果条数均分，最后一条补尾差。</p>
     *
     * @param context 排程上下文
     */
    private void distributeMultiMachineSurplusAndStock(LhScheduleContext context) {
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
        // 委托工具类按机台条数均分
        for (String groupKey : groupOrder) {
            SkuScheduleDTO sourceSku = groupSourceSkuMap.get(groupKey);
            List<LhScheduleResult> materialResults = groupResultsMap.get(groupKey);
            if (materialResults.size() <= 1) {
                continue;
            }
            int totalSurplus = Math.max(0, sourceSku.getSurplusQty());
            int totalEmbryoStock = Math.max(0, sourceSku.getEmbryoStock());
            // 仅分摊胎胚库存，余量不按机台均分（各机台结果保留原始全量值）
            LhMultiMachineDistributionUtil.distributeForSingleMaterial(
                    materialResults, totalSurplus, totalEmbryoStock);
            log.debug("多机台续作胎胚库存分摊完成, materialCode: {}, 机台数: {}, 总余量: {}, 总胎胚库存: {}",
                    sourceSku.getMaterialCode(), materialResults.size(), totalSurplus, totalEmbryoStock);
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
                .filter(this::isEffectiveContinuousResult)
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
            if (!isContinuousPhaseResult(result) || StringUtils.isEmpty(result.getMaterialCode())) {
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
            int remainingQty = resolveContinuousCompensationQty(context, sourceSku);
            if (remainingQty <= 0 || hasContinuousCompensationSku(context, sourceSku)) {
                continue;
            }
            SkuScheduleDTO compensationSku = copyContinuousCompensationSku(sourceSku, remainingQty);
            context.getNewSpecSkuList().add(compensationSku);
            log.info("续作目标量未满足，转新增规格链路补量, materialCode: {}, 已排: {}, 补偿量: {}, "
                            + "窗口日计划剩余: {}",
                    sourceSku.getMaterialCode(), resolveScheduledQtyBySourceSku(context, sourceSku),
                    remainingQty, SkuDailyPlanQuotaUtil.sumRemainingQty(sourceSku.getDailyPlanQuotaMap()));
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
        int scheduledQty = resolveScheduledQtyBySourceSku(context, sourceSku);
        int targetRemainingQty = Math.max(0, sourceSku.resolveTargetScheduleQty() - scheduledQty);
        if (targetRemainingQty <= 0) {
            return 0;
        }
        if (!CollectionUtils.isEmpty(sourceSku.getDailyPlanQuotaMap())) {
            int quotaRemainingQty = Math.max(0,
                    SkuDailyPlanQuotaUtil.sumRemainingQty(sourceSku.getDailyPlanQuotaMap()));
            return Math.min(targetRemainingQty, quotaRemainingQty);
        }
        return targetRemainingQty;
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
            boolean endingResult = "1".equals(result.getIsEnd());
            // 续作链路与新增链路保持一致：
            // 收尾结果必须严格截断且不记超排；试制等严格目标量场景回裁后仍保留超排账本。
            if (endingResult || (sku != null && sku.isStrictTargetQty())) {
                trimShiftPlanQty(result, shift.getShiftIndex(), consumedQty);
                if (endingResult) {
                    continue;
                }
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
    private boolean isEffectiveContinuousResult(LhScheduleResult result) {
        return isContinuousPhaseResult(result)
                && result.getDailyPlanQty() != null
                && result.getDailyPlanQty() > 0
                && result.getSpecEndTime() != null
                && StringUtils.isNotEmpty(result.getLhMachineCode());
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
        int targetScheduleQty = sku.resolveTargetScheduleQty();
        int retainedQty = resolveEffectiveContinuousPhaseScheduledQty(context, continuationGroupKey);
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
