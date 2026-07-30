package com.zlt.aps.lh.engine.strategy.support;

import com.zlt.aps.lh.api.domain.dto.MachineScheduleDTO;
import com.zlt.aps.lh.api.domain.entity.LhScheduleResult;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.util.LhMouldCodeUtil;
import com.zlt.aps.lh.util.MouldStatusUtil;
import com.zlt.aps.lh.util.ShiftCapacityResolverUtil;
import com.zlt.aps.mdm.api.domain.entity.MdmModelInfo;
import com.zlt.aps.mdm.api.domain.entity.MdmSkuMouldRel;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 新增链路模具资源运行态上下文。
 *
 * <p>增机台不能只判断机台是否可用，还必须按候选机台模数扣减SKU可用模具。
 * 本上下文只维护S4.5新增链路运行期占用，不反向裁剪S4.4既有续作结果。</p>
 *
 * @author APS
 */
@Slf4j
public class MouldResourceContext {

    /** SKU可用模具号列表，key=materialCode */
    private final Map<String, List<String>> skuAvailableMouldCodeMap;
    /** SKU台账缺失或禁用模具号列表，key=materialCode */
    private final Map<String, List<String>> skuUnavailableMouldCodeMap;
    /** SKU模具关系中存在台账缺失或禁用的标识，key=materialCode */
    private final Map<String, Boolean> skuUnavailableModelInfoMap;
    /** 机台模数，key=machineCode */
    private final Map<String, Integer> machineMouldQtyMap;
    /** 机台当前绑定的实际模具号，key=machineCode */
    private final Map<String, LinkedHashSet<String>> machineBoundMouldCodeMap;
    /** 本次排程已被SKU实际占用的模具号集合 */
    private final LinkedHashSet<String> occupiedMouldCodeSet;

    private MouldResourceContext(Map<String, List<String>> skuAvailableMouldCodeMap,
                                 Map<String, List<String>> skuUnavailableMouldCodeMap,
                                 Map<String, Boolean> skuUnavailableModelInfoMap,
                                 Map<String, Integer> machineMouldQtyMap,
                                 Map<String, LinkedHashSet<String>> machineBoundMouldCodeMap,
                                 LinkedHashSet<String> occupiedMouldCodeSet) {
        /*
         * 三个可用性视图会随日驱动编排的 currentScheduleDate 刷新，因此必须复制为可变 Map。
         * 机台已绑定模具和全局占用集合则保持同一运行态，刷新日期时绝不能重新构建它们。
         */
        this.skuAvailableMouldCodeMap =
                new LinkedHashMap<String, List<String>>(skuAvailableMouldCodeMap);
        this.skuUnavailableMouldCodeMap =
                new LinkedHashMap<String, List<String>>(skuUnavailableMouldCodeMap);
        this.skuUnavailableModelInfoMap =
                new LinkedHashMap<String, Boolean>(skuUnavailableModelInfoMap);
        this.machineMouldQtyMap = machineMouldQtyMap;
        this.machineBoundMouldCodeMap = machineBoundMouldCodeMap;
        this.occupiedMouldCodeSet = occupiedMouldCodeSet;
    }

    /**
     * 从排程上下文构建模具资源上下文。
     *
     * @param context 排程上下文
     * @return 模具资源上下文
     */
    public static MouldResourceContext from(LhScheduleContext context) {
        Map<String, Integer> mouldSharedSkuCountMap = buildMouldSharedSkuCountMap(context);
        Map<String, List<String>> skuAvailableMouldCodeMap =
                buildSkuAvailableMouldCodeMap(context, mouldSharedSkuCountMap);
        Map<String, List<String>> skuUnavailableMouldCodeMap = buildSkuUnavailableMouldCodeMap(context);
        Map<String, Boolean> skuUnavailableModelInfoMap = buildSkuUnavailableModelInfoMap(context);
        Map<String, Integer> machineMouldQtyMap = buildMachineMouldQtyMap(context);
        Map<String, LinkedHashSet<String>> machineBoundMouldCodeMap = buildMachineBoundMouldCodeMap(context);
        LinkedHashSet<String> occupiedMouldCodeSet = buildOccupiedMouldCodeSet(machineBoundMouldCodeMap);
        return new MouldResourceContext(skuAvailableMouldCodeMap, skuUnavailableMouldCodeMap,
                skuUnavailableModelInfoMap, machineMouldQtyMap, machineBoundMouldCodeMap, occupiedMouldCodeSet);
    }

    /**
     * 按当前业务日刷新模具到货可用性。
     *
     * <p>无台账模具是否可用依赖 {@link LhScheduleContext#getCurrentScheduleDate()} 与到货日期比较。
     * 日驱动排产从 T 推进到 T+1、T+2 时，已到货模具必须进入候选集合；但刷新只能更新静态
     * 可用性视图，不能重建 {@code occupiedMouldCodeSet} 或 {@code machineBoundMouldCodeMap}，否则
     * 已经成功上机的模具会被错误释放并允许重复分配。</p>
     *
     * @param context 排程上下文，当前业务日必须已写入 currentScheduleDate
     */
    public synchronized void refreshAvailability(LhScheduleContext context) {
        if (Objects.isNull(context)) {
            return;
        }
        Map<String, Integer> mouldSharedSkuCountMap = buildMouldSharedSkuCountMap(context);
        Map<String, List<String>> refreshedAvailableMap =
                buildSkuAvailableMouldCodeMap(context, mouldSharedSkuCountMap);
        Map<String, List<String>> refreshedUnavailableMap =
                buildSkuUnavailableMouldCodeMap(context);
        Map<String, Boolean> refreshedUnavailableModelInfoMap =
                buildSkuUnavailableModelInfoMap(context);
        skuAvailableMouldCodeMap.clear();
        skuAvailableMouldCodeMap.putAll(refreshedAvailableMap);
        skuUnavailableMouldCodeMap.clear();
        skuUnavailableMouldCodeMap.putAll(refreshedUnavailableMap);
        skuUnavailableModelInfoMap.clear();
        skuUnavailableModelInfoMap.putAll(refreshedUnavailableModelInfoMap);
        log.debug("模具资源可用性按业务日刷新, scheduleDate: {}, availableSkuCount: {}, "
                        + "occupiedMouldQty: {}, boundMachineCount: {}",
                context.getCurrentScheduleDate(), skuAvailableMouldCodeMap.size(),
                occupiedMouldCodeSet.size(), machineBoundMouldCodeMap.size());
    }

    /**
     * 尝试为SKU新增候选机台分配模具。
     *
     * @param materialCode SKU编码
     * @param machineCode 机台编码
     * @return 分配结果
     */
    public synchronized MouldResourceAllocationResult tryAllocate(String materialCode, String machineCode) {
        MouldResourceAllocationResult allocationResult = resolveAllocation(materialCode, machineCode);
        if (!allocationResult.isAllowed()) {
            return allocationResult;
        }
        // 正式候选成功时才改变运行态：先释放当前机台旧模具，再绑定新 SKU 模具。
        if (!CollectionUtils.isEmpty(allocationResult.getReleasedMouldCodeList())) {
            occupiedMouldCodeSet.removeAll(allocationResult.getReleasedMouldCodeList());
        }
        occupiedMouldCodeSet.addAll(allocationResult.getAllocatedMouldCodeList());
        machineBoundMouldCodeMap.put(machineCode,
                new LinkedHashSet<String>(allocationResult.getAllocatedMouldCodeList()));
        log.info("模具运行态绑定更新, materialCode: {}, machineCode: {}, releasedMouldCodes: {}, "
                        + "allocatedMouldCodes: {}",
                materialCode, machineCode, allocationResult.getReleasedMouldCodeList(),
                allocationResult.getAllocatedMouldCodeList());
        return allocationResult;
    }

    /**
     * 无副作用预检 SKU 在候选机台上的模具分配结果。
     * <p>预检和正式分配共用同一套当前绑定、可释放旧模具、全局占用和机台模数计算，
     * 但不修改已占用模具或机台绑定，确保候选失败不会产生脏状态。</p>
     *
     * @param materialCode SKU 编码
     * @param machineCode 候选机台编码
     * @return 与正式分配同口径的模具资源结果
     */
    public synchronized MouldResourceAllocationResult previewAllocate(String materialCode, String machineCode) {
        return resolveAllocation(materialCode, machineCode);
    }

    /**
     * 无副作用读取 SKU 当前日期下的有效空闲模具。
     *
     * <p>返回结果只包含已经通过现有启用状态、台账及到货日期校验，且当前未被任何机台占用或
     * 预占的模具。调用方可继续传入本次需要排除的转交模具，确保 B 的剩余模具不会包含
     * 已转给 A 的整套共用模具。</p>
     *
     * @param materialCode SKU 编码
     * @param excludedMouldCodeSet 本次额外排除的模具号
     * @return 有效且空闲的模具号，返回副本
     */
    public synchronized List<String> resolveFreeValidMouldCodes(
            String materialCode,
            Set<String> excludedMouldCodeSet) {
        List<String> availableMouldCodeList = skuAvailableMouldCodeMap.get(materialCode);
        if (CollectionUtils.isEmpty(availableMouldCodeList)) {
            return new ArrayList<String>(0);
        }
        List<String> resultList = new ArrayList<String>(availableMouldCodeList.size());
        for (String mouldCode : availableMouldCodeList) {
            if (occupiedMouldCodeSet.contains(mouldCode)
                    || (!CollectionUtils.isEmpty(excludedMouldCodeSet)
                    && excludedMouldCodeSet.contains(mouldCode))) {
                continue;
            }
            resultList.add(mouldCode);
        }
        return resultList;
    }

    /**
     * 读取机台当前实际绑定模具号。
     *
     * @param machineCode 运行态机台编码
     * @return 当前绑定模具号副本；无实际绑定时返回空集合
     */
    public synchronized LinkedHashSet<String> resolveMachineBoundMouldCodes(String machineCode) {
        Set<String> mouldCodeSet = machineBoundMouldCodeMap.get(machineCode);
        return CollectionUtils.isEmpty(mouldCodeSet)
                ? new LinkedHashSet<String>(0) : new LinkedHashSet<String>(mouldCodeSet);
    }

    /**
     * 判断给定模具是否全部属于 SKU 当前日期下的有效模具关系。
     *
     * @param materialCode SKU 编码
     * @param mouldCodeSet 待校验模具号
     * @return true-全部有效；false-至少一个模具无有效关系或状态不可用
     */
    public synchronized boolean areAllMouldCodesValidForSku(
            String materialCode,
            Set<String> mouldCodeSet) {
        List<String> availableMouldCodeList = skuAvailableMouldCodeMap.get(materialCode);
        return !CollectionUtils.isEmpty(mouldCodeSet)
                && !CollectionUtils.isEmpty(availableMouldCodeList)
                && availableMouldCodeList.containsAll(mouldCodeSet);
    }

    /**
     * 按预演确认的精确模具号正式更新机台绑定。
     *
     * <p>A 接管时，强制模具就是当前机台可释放的原在机模具；B 正式迁移时，强制模具来自
     * 预演成功结果。该入口仍校验机台模数、SKU 有效关系和实时占用，防止预演到提交之间
     * 出现同一模具被两个机台重复占用。</p>
     *
     * @param materialCode SKU 编码
     * @param machineCode 目标机台编码
     * @param forcedMouldCodeList 预演确认的精确模具号
     * @return 正式分配结果
     */
    public synchronized MouldResourceAllocationResult tryAllocateExact(
            String materialCode,
            String machineCode,
            List<String> forcedMouldCodeList) {
        MouldResourceAllocationResult allocationResult = resolveExactAllocation(
                materialCode, machineCode, forcedMouldCodeList);
        if (!allocationResult.isAllowed()) {
            return allocationResult;
        }
        if (!CollectionUtils.isEmpty(allocationResult.getReleasedMouldCodeList())) {
            occupiedMouldCodeSet.removeAll(allocationResult.getReleasedMouldCodeList());
        }
        occupiedMouldCodeSet.addAll(allocationResult.getAllocatedMouldCodeList());
        machineBoundMouldCodeMap.put(machineCode,
                new LinkedHashSet<String>(allocationResult.getAllocatedMouldCodeList()));
        log.info("模具运行态按置换预演精确绑定, materialCode: {}, machineCode: {}, "
                        + "releasedMouldCodes: {}, allocatedMouldCodes: {}",
                materialCode, machineCode, allocationResult.getReleasedMouldCodeList(),
                allocationResult.getAllocatedMouldCodeList());
        return allocationResult;
    }

    /**
     * B 迁移预演时仅从协调器确认的空闲剩余模具中分配。
     *
     * <p>与普通分配不同，本入口不会把候选新机台当前绑定模具视为 B 的可用模具；这些模具在
     * 预演开始时属于已占用资源，不满足“B 必须携带除转交模具外的剩余可用模具”条件。</p>
     *
     * @param materialCode B 物料编码
     * @param machineCode 候选新机台
     * @param allowedMouldCodeList 已完成全部排除的空闲剩余模具
     * @return 正式分配结果
     */
    public synchronized MouldResourceAllocationResult tryAllocateFromAllowed(
            String materialCode,
            String machineCode,
            List<String> allowedMouldCodeList) {
        int requiredMouldQty = resolveRequiredMouldQty(machineCode);
        List<String> availableMouldCodeList = skuAvailableMouldCodeMap.get(materialCode);
        List<String> allocatedMouldCodeList = new ArrayList<String>(requiredMouldQty);
        if (!CollectionUtils.isEmpty(allowedMouldCodeList)
                && !CollectionUtils.isEmpty(availableMouldCodeList)) {
            for (String mouldCode : allowedMouldCodeList) {
                if (!availableMouldCodeList.contains(mouldCode)
                        || occupiedMouldCodeSet.contains(mouldCode)
                        || allocatedMouldCodeList.contains(mouldCode)) {
                    continue;
                }
                allocatedMouldCodeList.add(mouldCode);
                if (allocatedMouldCodeList.size() >= requiredMouldQty) {
                    break;
                }
            }
        }
        if (allocatedMouldCodeList.size() < requiredMouldQty) {
            MouldResourceAllocationResult rejectedResult = MouldResourceAllocationResult.rejected(
                    requiredMouldQty,
                    CollectionUtils.isEmpty(availableMouldCodeList) ? 0 : availableMouldCodeList.size(),
                    0, allocatedMouldCodeList.size(),
                    Collections.<String>emptyList(),
                    skuUnavailableMouldCodeMap.get(materialCode),
                    resolveInsufficientReason(materialCode));
            rejectedResult.setMachineCode(machineCode);
            return rejectedResult;
        }
        Set<String> releasableMouldCodeSet = machineBoundMouldCodeMap.get(machineCode);
        List<String> releasedMouldCodeList = CollectionUtils.isEmpty(releasableMouldCodeSet)
                ? Collections.<String>emptyList()
                : new ArrayList<String>(releasableMouldCodeSet);
        MouldResourceAllocationResult allowedResult = MouldResourceAllocationResult.allowed(
                requiredMouldQty,
                availableMouldCodeList.size(),
                0,
                Math.max(0, allowedMouldCodeList.size() - allocatedMouldCodeList.size()),
                allocatedMouldCodeList,
                releasedMouldCodeList);
        allowedResult.setMachineCode(machineCode);
        if (!CollectionUtils.isEmpty(releasedMouldCodeList)) {
            occupiedMouldCodeSet.removeAll(releasedMouldCodeList);
        }
        occupiedMouldCodeSet.addAll(allocatedMouldCodeList);
        machineBoundMouldCodeMap.put(
                machineCode, new LinkedHashSet<String>(allocatedMouldCodeList));
        log.info("B 迁移预演仅从空闲剩余模具分配, materialCode: {}, machineCode: {}, "
                        + "releasedMouldCodes: {}, allocatedMouldCodes: {}",
                materialCode, machineCode, releasedMouldCodeList, allocatedMouldCodeList);
        return allowedResult;
    }

    /**
     * 计算指定机台使用精确模具号的分配结果。
     *
     * @param materialCode SKU 编码
     * @param machineCode 目标机台编码
     * @param forcedMouldCodeList 指定模具号
     * @return 无副作用分配结果
     */
    private MouldResourceAllocationResult resolveExactAllocation(
            String materialCode,
            String machineCode,
            List<String> forcedMouldCodeList) {
        int requiredMouldQty = resolveRequiredMouldQty(machineCode);
        List<String> availableMouldCodeList = skuAvailableMouldCodeMap.get(materialCode);
        LinkedHashSet<String> forcedMouldCodeSet = CollectionUtils.isEmpty(forcedMouldCodeList)
                ? new LinkedHashSet<String>(0)
                : new LinkedHashSet<String>(forcedMouldCodeList);
        LinkedHashSet<String> releasableMouldCodeSet = machineBoundMouldCodeMap.get(machineCode);
        List<String> occupiedForcedMouldCodeList = resolveOccupiedSkuMouldCodeList(
                new ArrayList<String>(forcedMouldCodeSet), releasableMouldCodeSet);
        int availableMouldQty = CollectionUtils.isEmpty(availableMouldCodeList)
                ? 0 : availableMouldCodeList.size();
        if (forcedMouldCodeSet.size() != requiredMouldQty
                || CollectionUtils.isEmpty(availableMouldCodeList)
                || !availableMouldCodeList.containsAll(forcedMouldCodeSet)
                || !CollectionUtils.isEmpty(occupiedForcedMouldCodeList)) {
            MouldResourceAllocationResult rejectedResult = MouldResourceAllocationResult.rejected(
                    requiredMouldQty, availableMouldQty, occupiedForcedMouldCodeList.size(),
                    resolveFreeValidMouldCodes(materialCode, Collections.<String>emptySet()).size(),
                    occupiedForcedMouldCodeList, skuUnavailableMouldCodeMap.get(materialCode),
                    resolveInsufficientReason(materialCode));
            rejectedResult.setMachineCode(machineCode);
            return rejectedResult;
        }
        List<String> releasedMouldCodeList = CollectionUtils.isEmpty(releasableMouldCodeSet)
                ? Collections.<String>emptyList() : new ArrayList<String>(releasableMouldCodeSet);
        MouldResourceAllocationResult allowedResult = MouldResourceAllocationResult.allowed(
                requiredMouldQty, availableMouldQty, 0,
                Math.max(0, availableMouldQty - forcedMouldCodeSet.size()),
                new ArrayList<String>(forcedMouldCodeSet), releasedMouldCodeList);
        allowedResult.setMachineCode(machineCode);
        return allowedResult;
    }

    /**
     * 判断 SKU 是否存在需要进入模具运行态预检的模具关系定义。
     * <p>没有任何模具关系的 SKU 保持原候选筛选语义，由后续正式分配链路暴露基础数据问题；
     * 只要存在关系（包括台账缺失或禁用关系），候选筛选就必须执行实时模具预检。</p>
     *
     * @param materialCode SKU 编码
     * @return true-存在模具关系定义，false-完全没有模具关系
     */
    public boolean hasMouldResourceDefinition(String materialCode) {
        return skuAvailableMouldCodeMap.containsKey(materialCode)
                || skuUnavailableMouldCodeMap.containsKey(materialCode);
    }

    /**
     * 按当前模具运行态计算候选机台的分配结果。
     *
     * @param materialCode SKU 编码
     * @param machineCode 候选机台编码
     * @return 模具资源分配结果，本方法不修改运行态
     */
    private MouldResourceAllocationResult resolveAllocation(String materialCode, String machineCode) {
        int requiredMouldQty = resolveRequiredMouldQty(machineCode);
        List<String> availableMouldCodeList = skuAvailableMouldCodeMap.get(materialCode);
        int availableMouldQty = CollectionUtils.isEmpty(availableMouldCodeList) ? 0 : availableMouldCodeList.size();
        LinkedHashSet<String> releasableMouldCodeSet = machineBoundMouldCodeMap.get(machineCode);
        List<String> occupiedSkuMouldCodeList = resolveOccupiedSkuMouldCodeList(availableMouldCodeList, releasableMouldCodeSet);
        int occupiedMouldQty = occupiedSkuMouldCodeList.size();
        int remainingAvailableMouldQty = Math.max(0, availableMouldQty - occupiedMouldQty);
        if (CollectionUtils.isEmpty(availableMouldCodeList)) {
            MouldResourceAllocationResult rejectedResult = MouldResourceAllocationResult.rejected(
                    requiredMouldQty, availableMouldQty, occupiedMouldQty, remainingAvailableMouldQty,
                    occupiedSkuMouldCodeList, skuUnavailableMouldCodeMap.get(materialCode),
                    resolveNoAvailableReason(materialCode));
            rejectedResult.setMachineCode(machineCode);
            return rejectedResult;
        }
        if (remainingAvailableMouldQty < requiredMouldQty) {
            MouldResourceAllocationResult rejectedResult = MouldResourceAllocationResult.rejected(
                    requiredMouldQty, availableMouldQty, occupiedMouldQty, remainingAvailableMouldQty,
                    occupiedSkuMouldCodeList, skuUnavailableMouldCodeMap.get(materialCode),
                    resolveInsufficientReason(materialCode));
            rejectedResult.setMachineCode(machineCode);
            return rejectedResult;
        }
        List<String> allocatedMouldCodeList = new ArrayList<String>(requiredMouldQty);
        for (String mouldCode : availableMouldCodeList) {
            if (occupiedMouldCodeSet.contains(mouldCode)
                    && (CollectionUtils.isEmpty(releasableMouldCodeSet) || !releasableMouldCodeSet.contains(mouldCode))) {
                continue;
            }
            allocatedMouldCodeList.add(mouldCode);
            if (allocatedMouldCodeList.size() >= requiredMouldQty) {
                break;
            }
        }
        if (allocatedMouldCodeList.size() < requiredMouldQty) {
            MouldResourceAllocationResult rejectedResult = MouldResourceAllocationResult.rejected(
                    requiredMouldQty, availableMouldQty, occupiedMouldQty, remainingAvailableMouldQty,
                    occupiedSkuMouldCodeList, skuUnavailableMouldCodeMap.get(materialCode),
                    resolveInsufficientReason(materialCode));
            rejectedResult.setMachineCode(machineCode);
            return rejectedResult;
        }
        List<String> releasedMouldCodeList = CollectionUtils.isEmpty(releasableMouldCodeSet)
                ? Collections.<String>emptyList() : new ArrayList<String>(releasableMouldCodeSet);
        MouldResourceAllocationResult allowedResult = MouldResourceAllocationResult.allowed(
                requiredMouldQty,
                availableMouldQty,
                occupiedMouldQty,
                Math.max(0, availableMouldQty - occupiedMouldQty - allocatedMouldCodeList.size()),
                allocatedMouldCodeList,
                releasedMouldCodeList);
        allowedResult.setMachineCode(machineCode);
        return allowedResult;
    }

    /**
     * 释放本次候选机台预占模具。
     *
     * @param materialCode SKU编码
     * @param allocationResult 分配结果
     */
    public synchronized void release(String materialCode, MouldResourceAllocationResult allocationResult) {
        if (StringUtils.isEmpty(materialCode)
                || Objects.isNull(allocationResult)
                || !allocationResult.isAllowed()
                || CollectionUtils.isEmpty(allocationResult.getAllocatedMouldCodeList())) {
            return;
        }
        occupiedMouldCodeSet.removeAll(allocationResult.getAllocatedMouldCodeList());
        if (!CollectionUtils.isEmpty(allocationResult.getReleasedMouldCodeList())) {
            occupiedMouldCodeSet.addAll(allocationResult.getReleasedMouldCodeList());
            machineBoundMouldCodeMap.put(allocationResult.getMachineCode(),
                    new LinkedHashSet<String>(allocationResult.getReleasedMouldCodeList()));
        } else if (StringUtils.isNotEmpty(allocationResult.getMachineCode())) {
            machineBoundMouldCodeMap.remove(allocationResult.getMachineCode());
        }
    }

    private int resolveRequiredMouldQty(String machineCode) {
        Integer machineMouldQty = machineMouldQtyMap.get(machineCode);
        return ShiftCapacityResolverUtil.resolveMachineMouldQty(machineMouldQty == null ? 0 : machineMouldQty);
    }

    private MouldResourceSkipReason resolveNoAvailableReason(String materialCode) {
        return Boolean.TRUE.equals(skuUnavailableModelInfoMap.get(materialCode))
                ? MouldResourceSkipReason.MODEL_INFO_UNAVAILABLE
                : MouldResourceSkipReason.NO_AVAILABLE_MOULD;
    }

    private MouldResourceSkipReason resolveInsufficientReason(String materialCode) {
        return Boolean.TRUE.equals(skuUnavailableModelInfoMap.get(materialCode))
                ? MouldResourceSkipReason.MODEL_INFO_UNAVAILABLE
                : MouldResourceSkipReason.MOULD_QTY_NOT_ENOUGH;
    }

    private List<String> resolveOccupiedSkuMouldCodeList(List<String> availableMouldCodeList,
                                                         Set<String> releasableMouldCodeSet) {
        if (CollectionUtils.isEmpty(availableMouldCodeList) || CollectionUtils.isEmpty(occupiedMouldCodeSet)) {
            return Collections.emptyList();
        }
        List<String> resultList = new ArrayList<String>(availableMouldCodeList.size());
        for (String mouldCode : availableMouldCodeList) {
            if (occupiedMouldCodeSet.contains(mouldCode)
                    && (CollectionUtils.isEmpty(releasableMouldCodeSet) || !releasableMouldCodeSet.contains(mouldCode))) {
                resultList.add(mouldCode);
            }
        }
        return resultList;
    }

    private static Map<String, List<String>> buildSkuAvailableMouldCodeMap(LhScheduleContext context,
                                                                            Map<String, Integer> mouldSharedSkuCountMap) {
        if (Objects.isNull(context) || CollectionUtils.isEmpty(context.getSkuMouldRelMap())) {
            return Collections.emptyMap();
        }
        Map<String, List<String>> resultMap = new LinkedHashMap<String, List<String>>(context.getSkuMouldRelMap().size());
        Map<String, MdmModelInfo> modelInfoMap = context.getModelInfoMap();
        for (Map.Entry<String, List<MdmSkuMouldRel>> entry : context.getSkuMouldRelMap().entrySet()) {
            LinkedHashSet<String> mouldCodeSet = new LinkedHashSet<String>(4);
            for (MdmSkuMouldRel rel : entry.getValue()) {
                String mouldCode = Objects.isNull(rel) ? null : StringUtils.trim(rel.getMouldCode());
                if (StringUtils.isEmpty(mouldCode) || mouldCodeSet.contains(mouldCode)) {
                    continue;
                }
                MdmModelInfo modelInfo = CollectionUtils.isEmpty(modelInfoMap) ? null : modelInfoMap.get(mouldCode);
                if (Objects.nonNull(modelInfo) && MouldStatusUtil.isEnabled(modelInfo.getMouldStatus())) {
                    mouldCodeSet.add(mouldCode);
                }
                // 无台账模具仅在到货日期不晚于当前业务日时可分配，避免未来到货模具提前占用。
                if (modelInfo == null && rel.getBoardingDate() != null && context.getCurrentScheduleDate() != null &&
                        rel.getBoardingDate().compareTo(context.getCurrentScheduleDate()) <= 0) {
                    mouldCodeSet.add(mouldCode);
                }
            }
            List<String> mouldCodeList = new ArrayList<String>(mouldCodeSet);
            sortMouldCodesBySharedSkuCount(mouldCodeList, mouldSharedSkuCountMap);
            resultMap.put(entry.getKey(), mouldCodeList);
        }
        return resultMap;
    }

    private static Map<String, List<String>> buildSkuUnavailableMouldCodeMap(LhScheduleContext context) {
        if (Objects.isNull(context) || CollectionUtils.isEmpty(context.getSkuMouldRelMap())) {
            return Collections.emptyMap();
        }
        Map<String, List<String>> resultMap = new HashMap<String, List<String>>(context.getSkuMouldRelMap().size());
        Map<String, MdmModelInfo> modelInfoMap = context.getModelInfoMap();
        for (Map.Entry<String, List<MdmSkuMouldRel>> entry : context.getSkuMouldRelMap().entrySet()) {
            Set<String> checkedMouldCodeSet = new LinkedHashSet<String>(4);
            List<String> unavailableMouldCodeList = new ArrayList<String>(4);
            for (MdmSkuMouldRel rel : entry.getValue()) {
                String mouldCode = Objects.isNull(rel) ? null : StringUtils.trim(rel.getMouldCode());
                if (StringUtils.isEmpty(mouldCode) || !checkedMouldCodeSet.add(mouldCode)) {
                    continue;
                }
                MdmModelInfo modelInfo = CollectionUtils.isEmpty(modelInfoMap) ? null : modelInfoMap.get(mouldCode);
                // 无台账模具到货后不属于基础资料缺失，不能继续计入不可用模具明细。
                if (Objects.isNull(modelInfo) && rel.getBoardingDate() != null && context.getCurrentScheduleDate() != null &&
                        rel.getBoardingDate().compareTo(context.getCurrentScheduleDate()) <= 0) {
                    continue;
                }
                if (Objects.isNull(modelInfo) || !MouldStatusUtil.isEnabled(modelInfo.getMouldStatus())) {
                    unavailableMouldCodeList.add(mouldCode);
                }
            }
            resultMap.put(entry.getKey(), unavailableMouldCodeList);
        }
        return resultMap;
    }

    private static Map<String, Boolean> buildSkuUnavailableModelInfoMap(LhScheduleContext context) {
        if (Objects.isNull(context) || CollectionUtils.isEmpty(context.getSkuMouldRelMap())) {
            return Collections.emptyMap();
        }
        Map<String, Boolean> resultMap = new HashMap<String, Boolean>(context.getSkuMouldRelMap().size());
        Map<String, MdmModelInfo> modelInfoMap = context.getModelInfoMap();
        for (Map.Entry<String, List<MdmSkuMouldRel>> entry : context.getSkuMouldRelMap().entrySet()) {
            boolean hasUnavailableModelInfo = false;
            Set<String> checkedMouldCodeSet = new LinkedHashSet<String>(4);
            for (MdmSkuMouldRel rel : entry.getValue()) {
                String mouldCode = Objects.isNull(rel) ? null : StringUtils.trim(rel.getMouldCode());
                if (StringUtils.isEmpty(mouldCode) || !checkedMouldCodeSet.add(mouldCode)) {
                    continue;
                }
                MdmModelInfo modelInfo = CollectionUtils.isEmpty(modelInfoMap) ? null : modelInfoMap.get(mouldCode);
                // 无台账模具到货后不应触发“模具基础资料不可用”的硬性失败原因。
                if (Objects.isNull(modelInfo) && rel.getBoardingDate() != null && context.getCurrentScheduleDate() != null &&
                        rel.getBoardingDate().compareTo(context.getCurrentScheduleDate()) <= 0) {
                    continue;
                }
                if (Objects.isNull(modelInfo) || !MouldStatusUtil.isEnabled(modelInfo.getMouldStatus())) {
                    hasUnavailableModelInfo = true;
                    break;
                }
            }
            resultMap.put(entry.getKey(), hasUnavailableModelInfo);
        }
        return resultMap;
    }

    private static Map<String, Integer> buildMachineMouldQtyMap(LhScheduleContext context) {
        if (Objects.isNull(context) || CollectionUtils.isEmpty(context.getMachineScheduleMap())) {
            return Collections.emptyMap();
        }
        Map<String, Integer> machineMouldQtyMap = new HashMap<String, Integer>(context.getMachineScheduleMap().size());
        for (Map.Entry<String, MachineScheduleDTO> entry : context.getMachineScheduleMap().entrySet()) {
            MachineScheduleDTO machine = entry.getValue();
            if (Objects.isNull(machine) || StringUtils.isEmpty(machine.getMachineCode())) {
                continue;
            }
            machineMouldQtyMap.put(machine.getMachineCode(), ShiftCapacityResolverUtil.resolveMachineMouldQty(machine));
        }
        return machineMouldQtyMap;
    }

    private static Map<String, Integer> buildMouldSharedSkuCountMap(LhScheduleContext context) {
        return LhMouldCodeUtil.buildMouldSharedSkuCountMap(context);
    }

    private static void sortMouldCodesBySharedSkuCount(List<String> mouldCodeList,
                                                        Map<String, Integer> mouldSharedSkuCountMap) {
        if (CollectionUtils.isEmpty(mouldCodeList)) {
            return;
        }
        mouldCodeList.sort(Comparator
                .comparing((String mouldCode) -> mouldSharedSkuCountMap.getOrDefault(mouldCode, 1))
                .thenComparing(Comparator.naturalOrder()));
    }

    private static Map<String, LinkedHashSet<String>> buildMachineBoundMouldCodeMap(LhScheduleContext context) {
        Map<String, LinkedHashSet<String>> resultMap = new HashMap<String, LinkedHashSet<String>>(16);
        if (Objects.isNull(context)) {
            return resultMap;
        }
        appendMachineBoundMouldCodeFromCurrentMaterial(resultMap, context);
        appendMachineBoundMouldCodeFromResults(resultMap, context.getScheduleResultList());
        if (!CollectionUtils.isEmpty(context.getMachineAssignmentMap())) {
            for (List<LhScheduleResult> resultList : context.getMachineAssignmentMap().values()) {
                appendMachineBoundMouldCodeFromResults(resultMap, resultList);
            }
        }
        return resultMap;
    }

    private static void appendMachineBoundMouldCodeFromCurrentMaterial(
            Map<String, LinkedHashSet<String>> resultMap,
            LhScheduleContext context) {
        if (CollectionUtils.isEmpty(context.getMachineScheduleMap())) {
            return;
        }
        for (MachineScheduleDTO machine : context.getMachineScheduleMap().values()) {
            if (Objects.isNull(machine)
                    || StringUtils.isEmpty(machine.getMachineCode())) {
                continue;
            }
            LinkedHashSet<String> inMachineMouldCodeSet = LhMouldCodeUtil.resolveInMachineMouldCodeSet(
                    context, machine.getMachineCode());
            if (!CollectionUtils.isEmpty(inMachineMouldCodeSet)) {
                // 续作在机模具号是机台真实占用，必须优先进入已使用模具列表，避免后续新增重复分配。
                resultMap.put(machine.getMachineCode(), inMachineMouldCodeSet);
                log.debug("模具资源初始化占用在机模具号, machineCode: {}, currentMaterialCode: {}, mouldCodes: {}",
                        machine.getMachineCode(), machine.getCurrentMaterialCode(), inMachineMouldCodeSet);
                continue;
            }
            if (StringUtils.isEmpty(machine.getCurrentMaterialCode())) {
                continue;
            }
            // 在机模具缺失时只能暴露基础数据问题，不能用SKU模具关系猜测实际占用模具。
            log.info("模具资源初始化未找到在机实际模具号，跳过猜测占用, batchNo: {}, machineCode: {}, "
                            + "currentMaterialCode: {}, requiredMouldQty: {}",
                    context.getBatchNo(), machine.getMachineCode(), machine.getCurrentMaterialCode(),
                    ShiftCapacityResolverUtil.resolveMachineMouldQty(machine));
        }
    }

    private static void appendMachineBoundMouldCodeFromResults(Map<String, LinkedHashSet<String>> resultMap,
                                                               List<LhScheduleResult> resultList) {
        if (CollectionUtils.isEmpty(resultList)) {
            return;
        }
        for (LhScheduleResult result : resultList) {
            if (Objects.isNull(result) || StringUtils.isEmpty(result.getLhMachineCode())) {
                continue;
            }
            LinkedHashSet<String> mouldCodeSet = LhMouldCodeUtil.splitMouldCode(result.getMouldCode());
            if (!CollectionUtils.isEmpty(mouldCodeSet)) {
                resultMap.put(result.getLhMachineCode(), mouldCodeSet);
            }
        }
    }

    private static LinkedHashSet<String> buildOccupiedMouldCodeSet(
            Map<String, LinkedHashSet<String>> machineBoundMouldCodeMap) {
        LinkedHashSet<String> occupiedMouldCodeSet = new LinkedHashSet<String>(16);
        if (CollectionUtils.isEmpty(machineBoundMouldCodeMap)) {
            return occupiedMouldCodeSet;
        }
        for (Set<String> mouldCodeSet : machineBoundMouldCodeMap.values()) {
            if (!CollectionUtils.isEmpty(mouldCodeSet)) {
                occupiedMouldCodeSet.addAll(mouldCodeSet);
            }
        }
        return occupiedMouldCodeSet;
    }

}
