package com.zlt.aps.lh.engine.strategy.support;

import com.zlt.aps.lh.api.domain.dto.MachineScheduleDTO;
import com.zlt.aps.lh.api.domain.entity.LhMachineOnlineInfo;
import com.zlt.aps.lh.api.domain.entity.LhScheduleResult;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.mdm.api.domain.entity.MdmModelInfo;
import com.zlt.aps.mdm.api.domain.entity.MdmSkuMouldRel;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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

    /**
     * 用例说明：在机模具号缺失时，不能用当前物料的SKU模具关系猜测占用模具。
     */
    @Test
    public void shouldNotGuessOccupiedMouldFromCurrentMaterialWhenOnlineMouldMissing() {
        LhScheduleContext context = buildContext(
                Arrays.asList(
                        buildRel("SKU-CURRENT", "M001"),
                        buildRel("SKU-NEW", "M001"),
                        buildRel("SKU-NEW", "M003"),
                        buildRel("SKU-OTHER-1", "M003"),
                        buildRel("SKU-OTHER-2", "M003")),
                Arrays.asList(buildModel("M001", 1), buildModel("M003", 1)),
                Arrays.asList(buildMachineWithCurrentMaterial("K1105", 1, "SKU-CURRENT"),
                        buildMachine("K1110", 1)));
        MouldResourceContext resourceContext = MouldResourceContext.from(context);

        MouldResourceAllocationResult result = resourceContext.tryAllocate("SKU-NEW", "K1110");

        Assertions.assertTrue(result.isAllowed());
        Assertions.assertEquals(Collections.singletonList("M001"), result.getAllocatedMouldCodeList());
    }

    /**
     * 用例说明：机台换成新模具后，历史结果中的旧共用模具必须释放，候选预检不得修改运行态。
     */
    @Test
    public void shouldPreviewWithLatestMachineBindingAndKeepPreviewSideEffectFree() {
        LhScheduleContext context = buildContext(
                Arrays.asList(
                        buildRel("SKU-OLD", "M001"), buildRel("SKU-OLD", "M002"),
                        buildRel("SKU-NEW", "M101"), buildRel("SKU-NEW", "M102"),
                        buildRel("SKU-TARGET", "M001"), buildRel("SKU-TARGET", "M002")),
                Arrays.asList(
                        buildModel("M001", 1), buildModel("M002", 1),
                        buildModel("M101", 1), buildModel("M102", 1)),
                Arrays.asList(
                        buildMachine("K1511", 2), buildMachine("K2201", 2), buildMachine("K2202", 2)));
        context.getScheduleResultList().add(buildResult("K1511", "SKU-OLD", "M001,M002"));
        context.getScheduleResultList().add(buildResult("K1511", "SKU-NEW", "M101,M102"));
        MouldResourceContext resourceContext = MouldResourceContext.from(context);

        MouldResourceAllocationResult firstPreview = resourceContext.previewAllocate("SKU-TARGET", "K2201");
        MouldResourceAllocationResult secondPreview = resourceContext.previewAllocate("SKU-TARGET", "K2201");
        MouldResourceAllocationResult allocated = resourceContext.tryAllocate("SKU-TARGET", "K2201");
        MouldResourceAllocationResult occupiedPreview = resourceContext.previewAllocate("SKU-TARGET", "K2202");

        Assertions.assertTrue(firstPreview.isAllowed(), "K1511已换下的M001/M002应可被后续SKU复用");
        Assertions.assertTrue(secondPreview.isAllowed(), "连续预检不得预占模具");
        Assertions.assertEquals(Arrays.asList("M001", "M002"), allocated.getAllocatedMouldCodeList());
        Assertions.assertFalse(occupiedPreview.isAllowed(), "正式分配后仍在机的模具必须继续互斥");
    }

    /**
     * 用例说明：无台账到货模具在 T 日不可用，推进到 T+1 后必须刷新可用性视图，
     * 同时不能重建已占用模具运行态。
     */
    @Test
    public void shouldRefreshBoardingMouldAvailabilityWhenBusinessDayAdvances() {
        MdmSkuMouldRel boardingMould = buildRel("SKU-BOARDING", "M901");
        boardingMould.setBoardingDate(toDate(LocalDate.of(2026, 7, 26)));
        LhScheduleContext context = buildContext(
                Collections.singletonList(boardingMould),
                Collections.<MdmModelInfo>emptyList(),
                Collections.singletonList(buildMachine("K1105", 1)));
        context.setCurrentScheduleDate(toDate(LocalDate.of(2026, 7, 25)));
        MouldResourceContext resourceContext = MouldResourceContext.from(context);

        MouldResourceAllocationResult beforeBoarding =
                resourceContext.previewAllocate("SKU-BOARDING", "K1105");

        context.setCurrentScheduleDate(toDate(LocalDate.of(2026, 7, 26)));
        resourceContext.refreshAvailability(context);
        MouldResourceAllocationResult afterBoarding =
                resourceContext.previewAllocate("SKU-BOARDING", "K1105");

        Assertions.assertFalse(beforeBoarding.isAllowed(), "到货日前不得预占无台账模具");
        Assertions.assertTrue(afterBoarding.isAllowed(), "到货业务日必须刷新为可用模具");
        Assertions.assertEquals(Collections.singletonList("M901"), afterBoarding.getAllocatedMouldCodeList());
    }

    /**
     * 用例说明：A 当前只有正在 B 续作机台上使用的共用模具时，空闲有效模具数量必须为零；
     * B 的剩余模具只能保留未占用、未禁用且未被本次转交的精确模具号。
     */
    @Test
    public void shouldResolveTargetNoFreeMouldAndFilterContinuationRemainingMoulds() {
        LhScheduleContext context = buildContext(
                Arrays.asList(
                        buildRel("SKU-A", "M-SHARED"),
                        buildRel("SKU-B", "M-SHARED"),
                        buildRel("SKU-B", "M-FREE"),
                        buildRel("SKU-B", "M-OCCUPIED"),
                        buildRel("SKU-B", "M-DISABLED")),
                Arrays.asList(
                        buildModel("M-SHARED", 1),
                        buildModel("M-FREE", 1),
                        buildModel("M-OCCUPIED", 1),
                        buildModel("M-DISABLED", 0)),
                Arrays.asList(
                        buildMachineWithCurrentMaterial("K1201", 1, "SKU-B"),
                        buildMachineWithCurrentMaterial("K1202", 1, "SKU-OTHER"),
                        buildMachine("K1203", 1)));
        context.getScheduleResultList().add(
                buildResult("K1201", "SKU-B", "M-SHARED"));
        context.getScheduleResultList().add(
                buildResult("K1202", "SKU-OTHER", "M-OCCUPIED"));
        MouldResourceContext resourceContext = MouldResourceContext.from(context);

        List<String> targetFreeMouldCodeList =
                resourceContext.resolveFreeValidMouldCodes(
                        "SKU-A", Collections.<String>emptySet());
        List<String> continuationRemainingMouldCodeList =
                resourceContext.resolveFreeValidMouldCodes(
                        "SKU-B",
                        new LinkedHashSet<String>(
                                Collections.singletonList("M-SHARED")));

        Assertions.assertTrue(targetFreeMouldCodeList.isEmpty(),
                "A 的共用模具正在 B 续作机台占用时，不得误判为其他可用模具");
        Assertions.assertEquals(
                Collections.singletonList("M-FREE"),
                continuationRemainingMouldCodeList,
                "B 剩余模具必须排除在机占用、禁用和本次转交模具");
    }

    /**
     * 用例说明：B 迁移预演只能使用协调器确认的空闲剩余模具，不能把候选新机台当前绑定、
     * 可释放的旧模具当作 B 的“剩余可用模具”。
     */
    @Test
    public void shouldAllocateContinuationRelocationOnlyFromAllowedFreeMoulds() {
        LhScheduleContext context = buildContext(
                Arrays.asList(
                        buildRel("SKU-B", "M-FREE"),
                        buildRel("SKU-B", "M-BOUND")),
                Arrays.asList(
                        buildModel("M-FREE", 1),
                        buildModel("M-BOUND", 1)),
                Collections.singletonList(
                        buildMachineWithCurrentMaterial(
                                "K1301", 1, "SKU-OTHER")));
        context.getScheduleResultList().add(
                buildResult("K1301", "SKU-OTHER", "M-BOUND"));
        MouldResourceContext resourceContext = MouldResourceContext.from(context);

        MouldResourceAllocationResult allocationResult =
                resourceContext.tryAllocateFromAllowed(
                        "SKU-B", "K1301",
                        Collections.singletonList("M-FREE"));

        Assertions.assertTrue(allocationResult.isAllowed());
        Assertions.assertEquals(
                Collections.singletonList("M-FREE"),
                allocationResult.getAllocatedMouldCodeList());
        Assertions.assertEquals(
                Collections.singletonList("M-BOUND"),
                allocationResult.getReleasedMouldCodeList());
    }

    /**
     * 用例说明：B 除转交模具外没有任何空闲有效模具时，迁移模具分配必须失败，
     * 不能复用转交模具，也不能复用其他机台已占用模具。
     */
    @Test
    public void shouldRejectContinuationRelocationWhenNoRemainingMouldExists() {
        LhScheduleContext context = buildContext(
                Arrays.asList(
                        buildRel("SKU-B", "M-SHARED"),
                        buildRel("SKU-B", "M-OCCUPIED")),
                Arrays.asList(
                        buildModel("M-SHARED", 1),
                        buildModel("M-OCCUPIED", 1)),
                Arrays.asList(
                        buildMachineWithCurrentMaterial("K1401", 1, "SKU-B"),
                        buildMachineWithCurrentMaterial("K1402", 1, "SKU-OTHER"),
                        buildMachine("K1403", 1)));
        context.getScheduleResultList().add(
                buildResult("K1401", "SKU-B", "M-SHARED"));
        context.getScheduleResultList().add(
                buildResult("K1402", "SKU-OTHER", "M-OCCUPIED"));
        MouldResourceContext resourceContext = MouldResourceContext.from(context);
        List<String> remainingMouldCodeList =
                resourceContext.resolveFreeValidMouldCodes(
                        "SKU-B",
                        Collections.singleton("M-SHARED"));

        MouldResourceAllocationResult allocationResult =
                resourceContext.tryAllocateFromAllowed(
                        "SKU-B", "K1403", remainingMouldCodeList);

        Assertions.assertTrue(remainingMouldCodeList.isEmpty());
        Assertions.assertFalse(allocationResult.isAllowed());
        Assertions.assertEquals(
                MouldResourceSkipReason.MOULD_QTY_NOT_ENOUGH,
                allocationResult.getSkipReason());
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

    /**
     * 按系统默认时区构造业务日零点。
     *
     * @param localDate 业务日期
     * @return 对应零点时间
     */
    private Date toDate(LocalDate localDate) {
        return Date.from(localDate.atStartOfDay(ZoneId.systemDefault()).toInstant());
    }

    private MdmModelInfo buildModel(String mouldCode, Integer status) {
        MdmModelInfo modelInfo = new MdmModelInfo();
        modelInfo.setMouldCode(mouldCode);
        modelInfo.setMouldStatus(status);
        return modelInfo;
    }

    private LhScheduleResult buildResult(String machineCode, String materialCode, String mouldCode) {
        LhScheduleResult result = new LhScheduleResult();
        result.setLhMachineCode(machineCode);
        result.setMaterialCode(materialCode);
        result.setMouldCode(mouldCode);
        return result;
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
