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
        this.skuAvailableMouldCodeMap = skuAvailableMouldCodeMap;
        this.skuUnavailableMouldCodeMap = skuUnavailableMouldCodeMap;
        this.skuUnavailableModelInfoMap = skuUnavailableModelInfoMap;
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
        // 真正换模成功时，先释放当前机台前物料实际模具，再绑定新SKU本次实际分配模具。
        if (!CollectionUtils.isEmpty(releasedMouldCodeList)) {
            occupiedMouldCodeSet.removeAll(releasedMouldCodeList);
        }
        occupiedMouldCodeSet.addAll(allocatedMouldCodeList);
        machineBoundMouldCodeMap.put(machineCode, new LinkedHashSet<String>(allocatedMouldCodeList));
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
        if (Objects.isNull(context) || CollectionUtils.isEmpty(context.getSkuMouldRelMap())) {
            return Collections.emptyMap();
        }
        Map<String, Set<String>> mouldSkuSetMap = new HashMap<String, Set<String>>(16);
        for (Map.Entry<String, List<MdmSkuMouldRel>> entry : context.getSkuMouldRelMap().entrySet()) {
            for (MdmSkuMouldRel rel : entry.getValue()) {
                String mouldCode = Objects.isNull(rel) ? null : StringUtils.trim(rel.getMouldCode());
                if (StringUtils.isEmpty(mouldCode)) {
                    continue;
                }
                mouldSkuSetMap.computeIfAbsent(mouldCode, key -> new LinkedHashSet<String>(4)).add(entry.getKey());
            }
        }
        Map<String, Integer> resultMap = new HashMap<String, Integer>(mouldSkuSetMap.size());
        for (Map.Entry<String, Set<String>> entry : mouldSkuSetMap.entrySet()) {
            resultMap.put(entry.getKey(), entry.getValue().size());
        }
        return resultMap;
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
