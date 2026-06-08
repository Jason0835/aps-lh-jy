package com.zlt.aps.lh.engine.strategy.support;

import com.zlt.aps.lh.api.domain.dto.MachineScheduleDTO;
import com.zlt.aps.lh.api.domain.entity.LhMachineOnlineInfo;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.mdm.api.domain.entity.MdmModelInfo;
import com.zlt.aps.mdm.api.domain.entity.MdmSkuMouldRel;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模具资源运行态上下文测试。
 *
 * @author APS
 */
public class MouldResourceContextTest {

    /**
     * 用例说明：同一SKU新增多台机台时，必须按机台模数逐台扣减模具数量。
     */
    @Test
    public void shouldAllocateByMachineMouldQtyAndRejectWhenRemainingNotEnough() {
        LhScheduleContext context = buildContext(
                Arrays.asList(buildRel("SKU-001", "M001"), buildRel("SKU-001", "M002"), buildRel("SKU-001", "M003")),
                Arrays.asList(buildModel("M001", 1), buildModel("M002", 1), buildModel("M003", 1)),
                Arrays.asList(buildMachine("K1105", 2), buildMachine("K1110", 2), buildMachine("K1113", 1)));
        MouldResourceContext resourceContext = MouldResourceContext.from(context);

        MouldResourceAllocationResult first = resourceContext.tryAllocate("SKU-001", "K1105");
        MouldResourceAllocationResult second = resourceContext.tryAllocate("SKU-001", "K1110");
        MouldResourceAllocationResult third = resourceContext.tryAllocate("SKU-001", "K1113");

        Assertions.assertTrue(first.isAllowed());
        Assertions.assertEquals(2, first.getAllocatedMouldCodeList().size());
        Assertions.assertFalse(second.isAllowed());
        Assertions.assertEquals(MouldResourceSkipReason.MOULD_QTY_NOT_ENOUGH, second.getSkipReason());
        Assertions.assertTrue(third.isAllowed());
        Assertions.assertEquals(1, third.getAllocatedMouldCodeList().size());
        Assertions.assertEquals(0, third.getRemainingAvailableMouldQty());
    }

    /**
     * 用例说明：候选机台后续换模或产能失败时，应释放本次预占模具，后续机台可继续使用。
     */
    @Test
    public void shouldReleaseAllocatedMouldWhenCandidateFailsLater() {
        LhScheduleContext context = buildContext(
                Collections.singletonList(buildRel("SKU-001", "M001")),
                Collections.singletonList(buildModel("M001", 1)),
                Arrays.asList(buildMachine("K1105", 1), buildMachine("K1110", 1)));
        MouldResourceContext resourceContext = MouldResourceContext.from(context);

        MouldResourceAllocationResult first = resourceContext.tryAllocate("SKU-001", "K1105");
        resourceContext.release("SKU-001", first);
        MouldResourceAllocationResult second = resourceContext.tryAllocate("SKU-001", "K1110");

        Assertions.assertTrue(first.isAllowed());
        Assertions.assertTrue(second.isAllowed());
        Assertions.assertEquals(Collections.singletonList("M001"), second.getAllocatedMouldCodeList());
    }

    /**
     * 用例说明：缺失台账或禁用台账的模具不能计入SKU可用模具数量。
     */
    @Test
    public void shouldOnlyCountEnabledModelInfoAsAvailableMould() {
        LhScheduleContext context = buildContext(
                Arrays.asList(buildRel("SKU-001", "M001"), buildRel("SKU-001", "M002"), buildRel("SKU-001", "M003")),
                Arrays.asList(buildModel("M001", 1), buildModel("M002", 0)),
                Collections.singletonList(buildMachine("K1105", 2)));
        MouldResourceContext resourceContext = MouldResourceContext.from(context);

        MouldResourceAllocationResult result = resourceContext.tryAllocate("SKU-001", "K1105");

        Assertions.assertFalse(result.isAllowed());
        Assertions.assertEquals(MouldResourceSkipReason.MODEL_INFO_UNAVAILABLE, result.getSkipReason());
        Assertions.assertEquals(1, result.getAvailableMouldQty());
    }

    /**
     * 用例说明：SKU选择模具时，必须排除其他SKU已占用模具，再按共用SKU数量和模具号排序。
     */
    @Test
    public void shouldAllocateLeastSharedMouldAcrossSkusAndSkipOccupiedMould() {
        LhScheduleContext context = buildContext(
                Arrays.asList(
                        buildRel("SKU-001", "M003"),
                        buildRel("SKU-001", "M001"),
                        buildRel("SKU-001", "M002"),
                        buildRel("SKU-002", "M001"),
                        buildRel("SKU-002", "M004"),
                        buildRel("SKU-003", "M001"),
                        buildRel("SKU-003", "M002")),
                Arrays.asList(
                        buildModel("M001", 1),
                        buildModel("M002", 1),
                        buildModel("M003", 1),
                        buildModel("M004", 1)),
                Arrays.asList(buildMachine("K1105", 2), buildMachine("K1110", 1)));
        MouldResourceContext resourceContext = MouldResourceContext.from(context);

        MouldResourceAllocationResult first = resourceContext.tryAllocate("SKU-001", "K1105");
        MouldResourceAllocationResult second = resourceContext.tryAllocate("SKU-002", "K1110");

        Assertions.assertTrue(first.isAllowed());
        Assertions.assertEquals(Arrays.asList("M003", "M002"), first.getAllocatedMouldCodeList());
        Assertions.assertTrue(second.isAllowed());
        Assertions.assertEquals(Collections.singletonList("M004"), second.getAllocatedMouldCodeList());
    }

    /**
     * 用例说明：续作在机模具号必须作为本次排程已占用模具，后续新增分配不能重复选择。
     */
    @Test
    public void shouldTreatOnlineInMachineMouldCodeAsOccupiedMould() {
        LhScheduleContext context = buildContext(
                Arrays.asList(
                        buildRel("SKU-CURRENT", "M001"),
                        buildRel("SKU-NEW", "M102"),
                        buildRel("SKU-NEW", "M103")),
                Arrays.asList(buildModel("M001", 1), buildModel("M102", 1), buildModel("M103", 1)),
                Arrays.asList(buildMachineWithCurrentMaterial("K1105", 2, "SKU-CURRENT"),
                        buildMachine("K1110", 1)));
        LhMachineOnlineInfo onlineInfo = new LhMachineOnlineInfo();
        onlineInfo.setLhCode("K1105");
        onlineInfo.setInMachineMouldCode("M102");
        context.getMachineOnlineInfoMap().put("K1105", onlineInfo);
        MouldResourceContext resourceContext = MouldResourceContext.from(context);

        MouldResourceAllocationResult result = resourceContext.tryAllocate("SKU-NEW", "K1110");

        Assertions.assertTrue(result.isAllowed());
        Assertions.assertEquals(Collections.singletonList("M103"), result.getAllocatedMouldCodeList());
    }

    private LhScheduleContext buildContext(List<MdmSkuMouldRel> relList,
                                           List<MdmModelInfo> modelList,
                                           List<MachineScheduleDTO> machineList) {
        LhScheduleContext context = new LhScheduleContext();
        Map<String, List<MdmSkuMouldRel>> skuMouldRelMap = new LinkedHashMap<>(4);
        for (MdmSkuMouldRel rel : relList) {
            skuMouldRelMap.computeIfAbsent(rel.getMaterialCode(), key -> new java.util.ArrayList<MdmSkuMouldRel>(4))
                    .add(rel);
        }
        Map<String, MdmModelInfo> modelInfoMap = new LinkedHashMap<>(4);
        for (MdmModelInfo modelInfo : modelList) {
            modelInfoMap.put(modelInfo.getMouldCode(), modelInfo);
        }
        Map<String, MachineScheduleDTO> machineScheduleMap = new LinkedHashMap<>(4);
        for (MachineScheduleDTO machine : machineList) {
            machineScheduleMap.put(machine.getMachineCode(), machine);
        }
        context.setSkuMouldRelMap(skuMouldRelMap);
        context.setModelInfoMap(modelInfoMap);
        context.setMachineScheduleMap(machineScheduleMap);
        return context;
    }

    private MdmSkuMouldRel buildRel(String materialCode, String mouldCode) {
        MdmSkuMouldRel rel = new MdmSkuMouldRel();
        rel.setMaterialCode(materialCode);
        rel.setMouldCode(mouldCode);
        return rel;
    }

    private MdmModelInfo buildModel(String mouldCode, Integer status) {
        MdmModelInfo modelInfo = new MdmModelInfo();
        modelInfo.setMouldCode(mouldCode);
        modelInfo.setMouldStatus(status);
        return modelInfo;
    }

    private MachineScheduleDTO buildMachine(String machineCode, int maxMouldNum) {
        MachineScheduleDTO machine = new MachineScheduleDTO();
        machine.setMachineCode(machineCode);
        machine.setMaxMoldNum(maxMouldNum);
        return machine;
    }

    private MachineScheduleDTO buildMachineWithCurrentMaterial(String machineCode,
                                                               int maxMouldNum,
                                                               String currentMaterialCode) {
        MachineScheduleDTO machine = buildMachine(machineCode, maxMouldNum);
        machine.setCurrentMaterialCode(currentMaterialCode);
        return machine;
    }
}
