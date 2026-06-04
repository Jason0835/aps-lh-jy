package com.zlt.aps.lh.engine.strategy.support;

import com.zlt.aps.lh.api.domain.dto.MachineScheduleDTO;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.util.MouldStatusUtil;
import com.zlt.aps.lh.util.ShiftCapacityResolverUtil;
import com.zlt.aps.mdm.api.domain.entity.MdmModelInfo;
import com.zlt.aps.mdm.api.domain.entity.MdmSkuMouldRel;
import org.apache.commons.lang3.StringUtils;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.Collections;
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
public class MouldResourceContext {

    /** SKU可用模具号列表，key=materialCode */
    private final Map<String, List<String>> skuAvailableMouldCodeMap;
    /** SKU模具关系中存在台账缺失或禁用的标识，key=materialCode */
    private final Map<String, Boolean> skuUnavailableModelInfoMap;
    /** 机台模数，key=machineCode */
    private final Map<String, Integer> machineMouldQtyMap;
    /** SKU已被新增链路占用的模具号集合，key=materialCode */
    private final Map<String, LinkedHashSet<String>> skuOccupiedMouldCodeMap = new HashMap<String, LinkedHashSet<String>>(16);

    private MouldResourceContext(Map<String, List<String>> skuAvailableMouldCodeMap,
                                 Map<String, Boolean> skuUnavailableModelInfoMap,
                                 Map<String, Integer> machineMouldQtyMap) {
        this.skuAvailableMouldCodeMap = skuAvailableMouldCodeMap;
        this.skuUnavailableModelInfoMap = skuUnavailableModelInfoMap;
        this.machineMouldQtyMap = machineMouldQtyMap;
    }

    /**
     * 从排程上下文构建模具资源上下文。
     *
     * @param context 排程上下文
     * @return 模具资源上下文
     */
    public static MouldResourceContext from(LhScheduleContext context) {
        Map<String, List<String>> skuAvailableMouldCodeMap = buildSkuAvailableMouldCodeMap(context);
        Map<String, Boolean> skuUnavailableModelInfoMap = buildSkuUnavailableModelInfoMap(context);
        Map<String, Integer> machineMouldQtyMap = buildMachineMouldQtyMap(context);
        return new MouldResourceContext(skuAvailableMouldCodeMap, skuUnavailableModelInfoMap, machineMouldQtyMap);
    }

    /**
     * 尝试为SKU新增候选机台分配模具。
     *
     * @param materialCode SKU编码
     * @param machineCode 机台编码
     * @return 分配结果
     */
    public synchronized MouldResourceAllocationResult tryAllocate(String materialCode, String machineCode) {
        int requiredMouldQty = resolveRequiredMouldQty(machineCode);
        List<String> availableMouldCodeList = skuAvailableMouldCodeMap.get(materialCode);
        int availableMouldQty = CollectionUtils.isEmpty(availableMouldCodeList) ? 0 : availableMouldCodeList.size();
        LinkedHashSet<String> occupiedMouldCodeSet = skuOccupiedMouldCodeMap.get(materialCode);
        int occupiedMouldQty = CollectionUtils.isEmpty(occupiedMouldCodeSet) ? 0 : occupiedMouldCodeSet.size();
        int remainingAvailableMouldQty = Math.max(0, availableMouldQty - occupiedMouldQty);
        if (CollectionUtils.isEmpty(availableMouldCodeList)) {
            return MouldResourceAllocationResult.rejected(
                    requiredMouldQty, availableMouldQty, occupiedMouldQty, remainingAvailableMouldQty,
                    resolveNoAvailableReason(materialCode));
        }
        if (remainingAvailableMouldQty < requiredMouldQty) {
            return MouldResourceAllocationResult.rejected(
                    requiredMouldQty, availableMouldQty, occupiedMouldQty, remainingAvailableMouldQty,
                    resolveInsufficientReason(materialCode));
        }
        List<String> allocatedMouldCodeList = new ArrayList<String>(requiredMouldQty);
        LinkedHashSet<String> mutableOccupiedSet = skuOccupiedMouldCodeMap.computeIfAbsent(
                materialCode, key -> new LinkedHashSet<String>(availableMouldCodeList.size()));
        for (String mouldCode : availableMouldCodeList) {
            if (mutableOccupiedSet.contains(mouldCode)) {
                continue;
            }
            mutableOccupiedSet.add(mouldCode);
            allocatedMouldCodeList.add(mouldCode);
            if (allocatedMouldCodeList.size() >= requiredMouldQty) {
                break;
            }
        }
        return MouldResourceAllocationResult.allowed(
                requiredMouldQty,
                availableMouldQty,
                occupiedMouldQty,
                Math.max(0, availableMouldQty - mutableOccupiedSet.size()),
                allocatedMouldCodeList);
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
        LinkedHashSet<String> occupiedMouldCodeSet = skuOccupiedMouldCodeMap.get(materialCode);
        if (CollectionUtils.isEmpty(occupiedMouldCodeSet)) {
            return;
        }
        occupiedMouldCodeSet.removeAll(allocationResult.getAllocatedMouldCodeList());
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

    private static Map<String, List<String>> buildSkuAvailableMouldCodeMap(LhScheduleContext context) {
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
            }
            resultMap.put(entry.getKey(), new ArrayList<String>(mouldCodeSet));
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
}
