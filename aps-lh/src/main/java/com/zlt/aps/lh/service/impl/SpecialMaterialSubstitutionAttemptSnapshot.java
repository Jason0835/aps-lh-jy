package com.zlt.aps.lh.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.zlt.aps.lh.api.domain.dto.CleaningScheduleDateFillItem;
import com.zlt.aps.lh.api.domain.dto.MachineScheduleDTO;
import com.zlt.aps.lh.api.domain.dto.SkuDailyPlanQuotaDTO;
import com.zlt.aps.lh.api.domain.dto.SkuScheduleDTO;
import com.zlt.aps.lh.api.domain.entity.LhScheduleResult;
import com.zlt.aps.lh.api.domain.entity.LhUnscheduledResult;
import com.zlt.aps.lh.context.EmbryoStockConsumeLedger;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.engine.strategy.support.MouldResourceContext;
import com.zlt.aps.lh.engine.strategy.support.SpecialMaterialSubstitutionRecord;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Date;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 特殊材料单候选置换的内存状态快照。
 *
 * <p>S4.5.1 需要复用现有新增排产主链，而该主链会同时修改排程结果、机台运行态、模具资源、
 * 换模/首检次数、日计划账本和未排结果。候选失败时仅恢复结果列表无法保证无副作用，因此本快照
 * 在真正截断续作结果前保存本次调用可能触达的全部运行态，并在失败时一次性恢复。</p>
 *
 * <p>该对象只在单个候选尝试期间存活，不落库，也不改变最终 S4.6 的数据库事务边界。</p>
 *
 * @author APS
 */
final class SpecialMaterialSubstitutionAttemptSnapshot {

    /** 置换前排程结果对象顺序 */
    private List<LhScheduleResult> scheduleResultList;
    /** 置换前每个排程结果的字段快照，使用对象身份作为键 */
    private Map<LhScheduleResult, LhScheduleResult> scheduleResultStateMap;
    /** 置换前结果来源 SKU 映射 */
    private Map<LhScheduleResult, SkuScheduleDTO> scheduleResultSourceSkuMap;
    /** 置换前机台结果分配 */
    private Map<String, List<LhScheduleResult>> machineAssignmentMap;
    /** 置换前全部机台运行态 */
    private Map<String, MachineScheduleDTO> machineScheduleStateMap;
    /** 置换前机台班次剩余产能 */
    private Map<String, int[]> machineShiftCapacityMap;
    /** 置换前未排结果对象顺序 */
    private List<LhUnscheduledResult> unscheduledResultList;
    /** 置换前每条未排结果字段快照 */
    private Map<LhUnscheduledResult, LhUnscheduledResult> unscheduledResultStateMap;
    /** 置换前 SKU 剩余量账本 */
    private Map<String, Integer> skuProductionRemainingQtyMap;
    /** 置换前胎胚库存消费账本 */
    private Map<String, EmbryoStockConsumeLedger> embryoStockConsumeLedgerMap;
    /** 置换前各 SKU 胎胚库存配额 */
    private Map<String, Integer> embryoStockSkuQuotaMap;
    /** 置换前按胎胚库存硬收口的 SKU 集合 */
    private Set<String> embryoStockHardTargetMaterialSet;
    /** 置换前满足降模条件的 SKU 集合 */
    private Set<String> skuDecrementKeySet;
    /** 置换前已执行降模处理的 SKU 集合 */
    private Set<String> decrementHandledSkuKeySet;
    /** 置换前早中班换模计数 */
    private Map<String, int[]> dailyMouldChangeCountMap;
    /** 置换前早中班首检计数 */
    private Map<String, int[]> dailyFirstInspectionCountMap;
    /** 置换前班次首检顺序计数 */
    private Map<String, Integer> shiftFirstInspectionCountMap;
    /** 置换前同胎胚换模班次占用 */
    private Map<String, Set<Integer>> greenTireChangeoverShiftMap;
    /** 置换前结构已排机台运行态 */
    private Map<LocalDate, Map<String, Set<String>>> structureScheduledMachineCodeMap;
    /** 置换前 SKU 已排机台运行态 */
    private Map<LocalDate, Map<String, Set<String>>> skuScheduledMachineCodeMap;
    /** 置换前每日保养计数 */
    private Map<String, Integer> dailyMaintenanceCountMap;
    /** 置换前每日保养物理机集合 */
    private Map<String, Set<String>> dailyMaintenancePhysicalMachineSetMap;
    /** 置换前保养顺延日志去重键 */
    private Set<String> maintenanceResumeDelayLogKeySet;
    /** 置换前清洗安排日期回填清单 */
    private List<CleaningScheduleDateFillItem> cleaningScheduleDateFillList;
    /** 置换前换模上限阻塞原因 */
    private Map<String, String> mouldChangeLimitBlockedReasonMap;
    /** 置换前新增选机日志序号 */
    private Map<String, Integer> newSpecMachineSelectionCountMap;
    /** 置换前新增待排类型计数 */
    private int pendingFormalNewSpecSkuCount;
    private int pendingTrialNewSpecSkuCount;
    private int pendingMassTrialNewSpecSkuCount;
    private int pendingSmallBatchNewSpecSkuCount;
    /** 置换前新增选机临时判定，均使用 SKU 对象身份作为键 */
    private Map<SkuScheduleDTO, Boolean> newSpecTypeRuleBlockedMap;
    private Map<SkuScheduleDTO, Boolean> newSpecEarlyProductionAllowedMap;
    private Map<SkuScheduleDTO, Boolean> newSpecSingleControlStructureEndingLayerMap;
    /** 置换前满班补齐超排累计账本 */
    private Map<String, Integer> skuShiftFillOverQtyMap;
    /** 置换前定点机台挤量预留信息 */
    private Map<String, String> specifyMachineReservedMaterialMap;
    private Map<String, Date> specifyMachineReservedSwitchStartTimeMap;
    /** 置换前胶囊运行态及换胶囊班次资源 */
    private Map<String, Integer> capsuleRuntimeUsageMap;
    private Set<String> capsuleReplacementShiftKeySet;
    private Set<String> capsuleThresholdHandledMachineSet;
    private Map<String, Integer> capsuleReplacementShiftCapacityLimitMap;
    /** 置换前运行态有效胎胚 SKU */
    private Map<String, List<String>> activeEmbryoSkuMap;
    /** 置换前动态单胎胚收尾集合 */
    private Set<String> dynamicSingleEmbryoEndingMaterialSet;
    /** 置换前精确置换成功记录 */
    private List<SpecialMaterialSubstitutionRecord> substitutionRecordList;
    /** 置换前当前排程日期 */
    private Date currentScheduleDate;
    /** 置换前待排特殊材料 SKU 字段快照 */
    private SkuScheduleDTO specialSkuState;
    /** 置换前待排特殊材料 SKU 日计划账本深拷贝 */
    private Map<LocalDate, SkuDailyPlanQuotaDTO> specialSkuDailyQuotaMap;

    private SpecialMaterialSubstitutionAttemptSnapshot() {
    }

    /**
     * 捕获单候选置换前状态。
     *
     * @param context 排程上下文
     * @param specialSku 待排特殊材料 SKU
     * @return 可用于失败恢复的状态快照
     */
    static SpecialMaterialSubstitutionAttemptSnapshot capture(
            LhScheduleContext context,
            SkuScheduleDTO specialSku) {
        SpecialMaterialSubstitutionAttemptSnapshot snapshot =
                new SpecialMaterialSubstitutionAttemptSnapshot();
        snapshot.scheduleResultList = new ArrayList<LhScheduleResult>(context.getScheduleResultList());
        snapshot.scheduleResultStateMap = new IdentityHashMap<LhScheduleResult, LhScheduleResult>(
                Math.max(16, context.getScheduleResultList().size() * 2));
        for (LhScheduleResult result : context.getScheduleResultList()) {
            snapshot.scheduleResultStateMap.put(result, copyBean(result, LhScheduleResult.class));
        }
        snapshot.scheduleResultSourceSkuMap =
                new IdentityHashMap<LhScheduleResult, SkuScheduleDTO>(context.getScheduleResultSourceSkuMap());
        snapshot.machineAssignmentMap = copyResultListMap(context.getMachineAssignmentMap());
        snapshot.machineScheduleStateMap = copyMachineScheduleMap(context.getMachineScheduleMap());
        snapshot.machineShiftCapacityMap = copyIntArrayMap(context.getMachineShiftCapacityMap());

        snapshot.unscheduledResultList =
                new ArrayList<LhUnscheduledResult>(context.getUnscheduledResultList());
        snapshot.unscheduledResultStateMap =
                new IdentityHashMap<LhUnscheduledResult, LhUnscheduledResult>(
                        Math.max(16, context.getUnscheduledResultList().size() * 2));
        for (LhUnscheduledResult result : context.getUnscheduledResultList()) {
            snapshot.unscheduledResultStateMap.put(result, copyBean(result, LhUnscheduledResult.class));
        }

        snapshot.skuProductionRemainingQtyMap =
                new LinkedHashMap<String, Integer>(context.getSkuProductionRemainingQtyMap());
        snapshot.embryoStockConsumeLedgerMap =
                copyEmbryoLedgerMap(context.getEmbryoStockConsumeLedgerMap());
        /*
         * 特殊材料候选试排会经过目标量、胎胚配额和降模判断，必须同步快照这些数量判定状态。
         * 否则失败候选可能残留硬目标或降模处理标记，影响下一候选以及后续普通排程。
         */
        snapshot.embryoStockSkuQuotaMap =
                new LinkedHashMap<String, Integer>(context.getEmbryoStockSkuQuotaMap());
        snapshot.embryoStockHardTargetMaterialSet =
                new LinkedHashSet<String>(context.getEmbryoStockHardTargetMaterialSet());
        snapshot.skuDecrementKeySet =
                new LinkedHashSet<String>(context.getSkuDecrementKeySet());
        snapshot.decrementHandledSkuKeySet =
                new LinkedHashSet<String>(context.getDecrementHandledSkuKeySet());
        snapshot.dailyMouldChangeCountMap = copyIntArrayMap(context.getDailyMouldChangeCountMap());
        snapshot.dailyFirstInspectionCountMap = copyIntArrayMap(context.getDailyFirstInspectionCountMap());
        snapshot.shiftFirstInspectionCountMap =
                new LinkedHashMap<String, Integer>(context.getShiftFirstInspectionCountMap());
        snapshot.greenTireChangeoverShiftMap = copyIntegerSetMap(context.getGreenTireChangeoverShiftMap());
        snapshot.structureScheduledMachineCodeMap =
                copyScheduledMachineMap(context.getStructureScheduledMachineCodeMap());
        snapshot.skuScheduledMachineCodeMap =
                copyScheduledMachineMap(context.getSkuScheduledMachineCodeMap());
        snapshot.dailyMaintenanceCountMap =
                new LinkedHashMap<String, Integer>(context.getDailyMaintenanceCountMap());
        snapshot.dailyMaintenancePhysicalMachineSetMap =
                copyStringSetMap(context.getDailyMaintenancePhysicalMachineSetMap());
        snapshot.maintenanceResumeDelayLogKeySet =
                new LinkedHashSet<String>(context.getMaintenanceResumeDelayLogKeySet());
        snapshot.cleaningScheduleDateFillList =
                new ArrayList<CleaningScheduleDateFillItem>(context.getCleaningScheduleDateFillList());
        snapshot.mouldChangeLimitBlockedReasonMap =
                new LinkedHashMap<String, String>(context.getMouldChangeLimitBlockedReasonMap());
        snapshot.newSpecMachineSelectionCountMap =
                new LinkedHashMap<String, Integer>(context.getNewSpecMachineSelectionCountMap());
        snapshot.pendingFormalNewSpecSkuCount = context.getPendingFormalNewSpecSkuCount();
        snapshot.pendingTrialNewSpecSkuCount = context.getPendingTrialNewSpecSkuCount();
        snapshot.pendingMassTrialNewSpecSkuCount = context.getPendingMassTrialNewSpecSkuCount();
        snapshot.pendingSmallBatchNewSpecSkuCount = context.getPendingSmallBatchNewSpecSkuCount();
        snapshot.newSpecTypeRuleBlockedMap =
                new IdentityHashMap<SkuScheduleDTO, Boolean>(context.getNewSpecTypeRuleBlockedMap());
        snapshot.newSpecEarlyProductionAllowedMap =
                new IdentityHashMap<SkuScheduleDTO, Boolean>(
                        context.getNewSpecEarlyProductionAllowedMap());
        snapshot.newSpecSingleControlStructureEndingLayerMap =
                new IdentityHashMap<SkuScheduleDTO, Boolean>(
                        context.getNewSpecSingleControlStructureEndingLayerMap());
        snapshot.skuShiftFillOverQtyMap =
                new LinkedHashMap<String, Integer>(context.getSkuShiftFillOverQtyMap());
        snapshot.specifyMachineReservedMaterialMap =
                new LinkedHashMap<String, String>(context.getSpecifyMachineReservedMaterialMap());
        snapshot.specifyMachineReservedSwitchStartTimeMap =
                new LinkedHashMap<String, Date>(
                        context.getSpecifyMachineReservedSwitchStartTimeMap());
        snapshot.capsuleRuntimeUsageMap =
                new LinkedHashMap<String, Integer>(context.getCapsuleRuntimeUsageMap());
        snapshot.capsuleReplacementShiftKeySet =
                new LinkedHashSet<String>(context.getCapsuleReplacementShiftKeySet());
        snapshot.capsuleThresholdHandledMachineSet =
                new LinkedHashSet<String>(context.getCapsuleThresholdHandledMachineSet());
        snapshot.capsuleReplacementShiftCapacityLimitMap =
                new LinkedHashMap<String, Integer>(
                        context.getCapsuleReplacementShiftCapacityLimitMap());
        snapshot.activeEmbryoSkuMap = copyStringListMap(context.getActiveEmbryoSkuMap());
        snapshot.dynamicSingleEmbryoEndingMaterialSet =
                new LinkedHashSet<String>(context.getDynamicSingleEmbryoEndingMaterialSet());
        snapshot.substitutionRecordList =
                new ArrayList<SpecialMaterialSubstitutionRecord>(
                        context.getSpecialMaterialSubstitutionRecordList());
        snapshot.currentScheduleDate = context.getCurrentScheduleDate();
        snapshot.specialSkuState = copyBean(specialSku, SkuScheduleDTO.class);
        snapshot.specialSkuDailyQuotaMap = copyDailyQuotaMap(specialSku.getDailyPlanQuotaMap());
        return snapshot;
    }

    /**
     * 恢复候选置换前状态。
     *
     * @param context 排程上下文
     * @param specialSku 待排特殊材料 SKU
     */
    void restore(LhScheduleContext context, SkuScheduleDTO specialSku) {
        for (Map.Entry<LhScheduleResult, LhScheduleResult> entry : scheduleResultStateMap.entrySet()) {
            BeanUtil.copyProperties(entry.getValue(), entry.getKey());
        }
        context.setScheduleResultList(new ArrayList<LhScheduleResult>(scheduleResultList));
        context.setScheduleResultSourceSkuMap(
                new IdentityHashMap<LhScheduleResult, SkuScheduleDTO>(scheduleResultSourceSkuMap));
        context.setMachineAssignmentMap(copyResultListMap(machineAssignmentMap));
        restoreMachineScheduleMap(context);
        context.setMachineShiftCapacityMap(copyIntArrayMap(machineShiftCapacityMap));

        for (Map.Entry<LhUnscheduledResult, LhUnscheduledResult> entry
                : unscheduledResultStateMap.entrySet()) {
            BeanUtil.copyProperties(entry.getValue(), entry.getKey());
        }
        context.setUnscheduledResultList(new ArrayList<LhUnscheduledResult>(unscheduledResultList));
        context.setSkuProductionRemainingQtyMap(
                new LinkedHashMap<String, Integer>(skuProductionRemainingQtyMap));
        context.setEmbryoStockConsumeLedgerMap(copyEmbryoLedgerMap(embryoStockConsumeLedgerMap));
        // 恢复候选试排前的胎胚配额和降模处理标记，确保失败候选对主排程上下文完全无副作用。
        context.setEmbryoStockSkuQuotaMap(
                new LinkedHashMap<String, Integer>(embryoStockSkuQuotaMap));
        context.setEmbryoStockHardTargetMaterialSet(
                new LinkedHashSet<String>(embryoStockHardTargetMaterialSet));
        context.setSkuDecrementKeySet(new LinkedHashSet<String>(skuDecrementKeySet));
        context.setDecrementHandledSkuKeySet(
                new LinkedHashSet<String>(decrementHandledSkuKeySet));
        context.setDailyMouldChangeCountMap(copyIntArrayMap(dailyMouldChangeCountMap));
        context.setDailyFirstInspectionCountMap(copyIntArrayMap(dailyFirstInspectionCountMap));
        context.setShiftFirstInspectionCountMap(
                new LinkedHashMap<String, Integer>(shiftFirstInspectionCountMap));
        context.setGreenTireChangeoverShiftMap(copyIntegerSetMap(greenTireChangeoverShiftMap));
        context.setStructureScheduledMachineCodeMap(
                copyScheduledMachineMap(structureScheduledMachineCodeMap));
        context.setSkuScheduledMachineCodeMap(copyScheduledMachineMap(skuScheduledMachineCodeMap));
        context.setDailyMaintenanceCountMap(
                new LinkedHashMap<String, Integer>(dailyMaintenanceCountMap));
        context.setDailyMaintenancePhysicalMachineSetMap(
                copyStringSetMap(dailyMaintenancePhysicalMachineSetMap));
        context.setMaintenanceResumeDelayLogKeySet(
                new LinkedHashSet<String>(maintenanceResumeDelayLogKeySet));
        context.setCleaningScheduleDateFillList(
                new ArrayList<CleaningScheduleDateFillItem>(cleaningScheduleDateFillList));
        context.setMouldChangeLimitBlockedReasonMap(
                new LinkedHashMap<String, String>(mouldChangeLimitBlockedReasonMap));
        context.setNewSpecMachineSelectionCountMap(
                new LinkedHashMap<String, Integer>(newSpecMachineSelectionCountMap));
        context.setPendingFormalNewSpecSkuCount(pendingFormalNewSpecSkuCount);
        context.setPendingTrialNewSpecSkuCount(pendingTrialNewSpecSkuCount);
        context.setPendingMassTrialNewSpecSkuCount(pendingMassTrialNewSpecSkuCount);
        context.setPendingSmallBatchNewSpecSkuCount(pendingSmallBatchNewSpecSkuCount);
        context.setNewSpecTypeRuleBlockedMap(
                new IdentityHashMap<SkuScheduleDTO, Boolean>(newSpecTypeRuleBlockedMap));
        context.setNewSpecEarlyProductionAllowedMap(
                new IdentityHashMap<SkuScheduleDTO, Boolean>(newSpecEarlyProductionAllowedMap));
        context.setNewSpecSingleControlStructureEndingLayerMap(
                new IdentityHashMap<SkuScheduleDTO, Boolean>(
                        newSpecSingleControlStructureEndingLayerMap));
        context.setSkuShiftFillOverQtyMap(
                new LinkedHashMap<String, Integer>(skuShiftFillOverQtyMap));
        context.setSpecifyMachineReservedMaterialMap(
                new LinkedHashMap<String, String>(specifyMachineReservedMaterialMap));
        context.setSpecifyMachineReservedSwitchStartTimeMap(
                new LinkedHashMap<String, Date>(specifyMachineReservedSwitchStartTimeMap));
        context.setCapsuleRuntimeUsageMap(
                new LinkedHashMap<String, Integer>(capsuleRuntimeUsageMap));
        context.setCapsuleReplacementShiftKeySet(
                new LinkedHashSet<String>(capsuleReplacementShiftKeySet));
        context.setCapsuleThresholdHandledMachineSet(
                new LinkedHashSet<String>(capsuleThresholdHandledMachineSet));
        context.setCapsuleReplacementShiftCapacityLimitMap(
                new LinkedHashMap<String, Integer>(capsuleReplacementShiftCapacityLimitMap));
        context.setActiveEmbryoSkuMap(copyStringListMap(activeEmbryoSkuMap));
        context.setDynamicSingleEmbryoEndingMaterialSet(
                new LinkedHashSet<String>(dynamicSingleEmbryoEndingMaterialSet));
        context.setSpecialMaterialSubstitutionRecordList(
                new ArrayList<SpecialMaterialSubstitutionRecord>(substitutionRecordList));
        context.setCurrentScheduleDate(currentScheduleDate);

        BeanUtil.copyProperties(specialSkuState, specialSku);
        specialSku.setDailyPlanQuotaMap(copyDailyQuotaMap(specialSkuDailyQuotaMap));

        /*
         * 模具资源上下文没有暴露可变 Map，恢复排程结果和机台状态后按相同基础数据重新构建，
         * 可确保机台旧模具绑定、特殊材料预占模具和全局占用集合同时回到候选前口径。
         */
        context.setMouldResourceContext(MouldResourceContext.from(context));
        context.clearSpecialMaterialSpecifiedMachineDirective();
    }

    private void restoreMachineScheduleMap(LhScheduleContext context) {
        Map<String, MachineScheduleDTO> currentMap = context.getMachineScheduleMap();
        currentMap.clear();
        for (Map.Entry<String, MachineScheduleDTO> entry : machineScheduleStateMap.entrySet()) {
            currentMap.put(entry.getKey(), copyMachine(entry.getValue()));
        }
    }

    private static Map<String, MachineScheduleDTO> copyMachineScheduleMap(
            Map<String, MachineScheduleDTO> sourceMap) {
        Map<String, MachineScheduleDTO> targetMap =
                new LinkedHashMap<String, MachineScheduleDTO>(Math.max(16, sourceMap.size() * 2));
        for (Map.Entry<String, MachineScheduleDTO> entry : sourceMap.entrySet()) {
            targetMap.put(entry.getKey(), copyMachine(entry.getValue()));
        }
        return targetMap;
    }

    private static MachineScheduleDTO copyMachine(MachineScheduleDTO source) {
        MachineScheduleDTO target = copyBean(source, MachineScheduleDTO.class);
        target.setShiftRemainingCapacity(source.getShiftRemainingCapacity() == null
                ? null : source.getShiftRemainingCapacity().clone());
        target.setShiftAvailable(source.getShiftAvailable() == null
                ? null : source.getShiftAvailable().clone());
        target.setCleaningWindowList(source.getCleaningWindowList() == null
                ? new ArrayList<>(0) : new ArrayList<>(source.getCleaningWindowList()));
        target.setMaintenanceWindowList(source.getMaintenanceWindowList() == null
                ? new ArrayList<>(0) : new ArrayList<>(source.getMaintenanceWindowList()));
        target.setMouldChangeTasks(source.getMouldChangeTasks() == null
                ? new ArrayList<>(0) : new ArrayList<>(source.getMouldChangeTasks()));
        return target;
    }

    private static Map<String, List<LhScheduleResult>> copyResultListMap(
            Map<String, List<LhScheduleResult>> sourceMap) {
        Map<String, List<LhScheduleResult>> targetMap =
                new LinkedHashMap<String, List<LhScheduleResult>>(Math.max(16, sourceMap.size() * 2));
        for (Map.Entry<String, List<LhScheduleResult>> entry : sourceMap.entrySet()) {
            targetMap.put(entry.getKey(), entry.getValue() == null
                    ? new ArrayList<LhScheduleResult>(0)
                    : new ArrayList<LhScheduleResult>(entry.getValue()));
        }
        return targetMap;
    }

    private static Map<String, int[]> copyIntArrayMap(Map<String, int[]> sourceMap) {
        Map<String, int[]> targetMap =
                new LinkedHashMap<String, int[]>(Math.max(16, sourceMap.size() * 2));
        for (Map.Entry<String, int[]> entry : sourceMap.entrySet()) {
            targetMap.put(entry.getKey(), entry.getValue() == null ? null : entry.getValue().clone());
        }
        return targetMap;
    }

    private static Map<String, EmbryoStockConsumeLedger> copyEmbryoLedgerMap(
            Map<String, EmbryoStockConsumeLedger> sourceMap) {
        Map<String, EmbryoStockConsumeLedger> targetMap =
                new LinkedHashMap<String, EmbryoStockConsumeLedger>(
                        Math.max(16, sourceMap.size() * 2));
        for (Map.Entry<String, EmbryoStockConsumeLedger> entry : sourceMap.entrySet()) {
            targetMap.put(entry.getKey(), copyBean(entry.getValue(), EmbryoStockConsumeLedger.class));
        }
        return targetMap;
    }

    private static Map<String, Set<Integer>> copyIntegerSetMap(Map<String, Set<Integer>> sourceMap) {
        Map<String, Set<Integer>> targetMap =
                new LinkedHashMap<String, Set<Integer>>(Math.max(16, sourceMap.size() * 2));
        for (Map.Entry<String, Set<Integer>> entry : sourceMap.entrySet()) {
            targetMap.put(entry.getKey(), entry.getValue() == null
                    ? new LinkedHashSet<Integer>(0) : new LinkedHashSet<Integer>(entry.getValue()));
        }
        return targetMap;
    }

    private static Map<String, Set<String>> copyStringSetMap(Map<String, Set<String>> sourceMap) {
        Map<String, Set<String>> targetMap =
                new LinkedHashMap<String, Set<String>>(Math.max(16, sourceMap.size() * 2));
        for (Map.Entry<String, Set<String>> entry : sourceMap.entrySet()) {
            targetMap.put(entry.getKey(), entry.getValue() == null
                    ? new LinkedHashSet<String>(0) : new LinkedHashSet<String>(entry.getValue()));
        }
        return targetMap;
    }

    private static Map<String, List<String>> copyStringListMap(Map<String, List<String>> sourceMap) {
        Map<String, List<String>> targetMap =
                new LinkedHashMap<String, List<String>>(Math.max(16, sourceMap.size() * 2));
        for (Map.Entry<String, List<String>> entry : sourceMap.entrySet()) {
            targetMap.put(entry.getKey(), entry.getValue() == null
                    ? new ArrayList<String>(0) : new ArrayList<String>(entry.getValue()));
        }
        return targetMap;
    }

    private static Map<LocalDate, Map<String, Set<String>>> copyScheduledMachineMap(
            Map<LocalDate, Map<String, Set<String>>> sourceMap) {
        Map<LocalDate, Map<String, Set<String>>> targetMap =
                new LinkedHashMap<LocalDate, Map<String, Set<String>>>(
                        Math.max(8, sourceMap.size() * 2));
        for (Map.Entry<LocalDate, Map<String, Set<String>>> dateEntry : sourceMap.entrySet()) {
            targetMap.put(dateEntry.getKey(), copyStringSetMap(dateEntry.getValue()));
        }
        return targetMap;
    }

    private static Map<LocalDate, SkuDailyPlanQuotaDTO> copyDailyQuotaMap(
            Map<LocalDate, SkuDailyPlanQuotaDTO> sourceMap) {
        if (sourceMap == null) {
            return new LinkedHashMap<LocalDate, SkuDailyPlanQuotaDTO>(0);
        }
        Map<LocalDate, SkuDailyPlanQuotaDTO> targetMap =
                new LinkedHashMap<LocalDate, SkuDailyPlanQuotaDTO>(Math.max(8, sourceMap.size() * 2));
        for (Map.Entry<LocalDate, SkuDailyPlanQuotaDTO> entry : sourceMap.entrySet()) {
            targetMap.put(entry.getKey(), copyBean(entry.getValue(), SkuDailyPlanQuotaDTO.class));
        }
        return targetMap;
    }

    private static <T> T copyBean(T source, Class<T> targetClass) {
        if (source == null) {
            return null;
        }
        T target;
        try {
            target = targetClass.newInstance();
        } catch (InstantiationException | IllegalAccessException ex) {
            throw new IllegalStateException("特殊材料置换状态快照创建失败: " + targetClass.getSimpleName(), ex);
        }
        BeanUtil.copyProperties(source, target);
        return target;
    }
}
