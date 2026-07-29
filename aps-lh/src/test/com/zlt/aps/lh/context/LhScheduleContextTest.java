package com.zlt.aps.lh.context;

import com.zlt.aps.lh.api.domain.dto.SkuDailyPlanQuotaDTO;
import com.zlt.aps.lh.api.domain.dto.SkuScheduleDTO;
import com.zlt.aps.lh.engine.strategy.support.EarlyProductionRuntimePlan;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 排程上下文结构分组同步回归测试。
 */
class LhScheduleContextTest {

    @Test
    void removePendingSkuFromStructureMap_shouldRemoveSkuAndCleanEmptyStructure() {
        LhScheduleContext context = new LhScheduleContext();
        SkuScheduleDTO retainedSku = sku("MAT-A", "S1");
        SkuScheduleDTO removedSku = sku("MAT-B", "S1");
        SkuScheduleDTO onlySku = sku("MAT-C", "S2");

        Map<String, List<SkuScheduleDTO>> structureSkuMap = new LinkedHashMap<>();
        structureSkuMap.put("S1", new java.util.ArrayList<>(Arrays.asList(retainedSku, removedSku)));
        structureSkuMap.put("S2", new java.util.ArrayList<>(Arrays.asList(onlySku)));
        context.setStructureSkuMap(structureSkuMap);

        context.removePendingSkuFromStructureMap(removedSku);
        context.removePendingSkuFromStructureMap(onlySku);

        assertEquals(1, context.getStructureSkuMap().size());
        assertEquals(1, context.getStructureSkuMap().get("S1").size());
        assertEquals("MAT-A", context.getStructureSkuMap().get("S1").get(0).getMaterialCode());
        assertTrue(!context.getStructureSkuMap().containsKey("S2"));
    }

    @Test
    void removePendingSkuFromStructureMap_shouldFallbackToMaterialCodeMatch() {
        LhScheduleContext context = new LhScheduleContext();
        SkuScheduleDTO storedSku = sku("MAT-A", "S1");
        storedSku.setProductStatus("X");

        Map<String, List<SkuScheduleDTO>> structureSkuMap = new LinkedHashMap<>();
        structureSkuMap.put("S1", new java.util.ArrayList<>(Arrays.asList(storedSku)));
        context.setStructureSkuMap(structureSkuMap);

        // 模拟调用侧传入了同物料的新实例，仍需能把结构分组中的旧实例同步移除。
        SkuScheduleDTO targetSku = sku("MAT-A", "S1");
        targetSku.setProductStatus("X");
        context.removePendingSkuFromStructureMap(targetSku);

        assertTrue(context.getStructureSkuMap().isEmpty());
    }

    /**
     * 同物料多产品状态并存时，只允许移除物料和产品状态都匹配的SKU。
     */
    @Test
    void removePendingSkuFromStructureMap_shouldKeepOtherProductStatus() {
        LhScheduleContext context = new LhScheduleContext();
        SkuScheduleDTO formalSku = sku("MAT-A", "S1");
        formalSku.setProductStatus("S");
        SkuScheduleDTO trialSku = sku("MAT-A", "S1");
        trialSku.setProductStatus("X");
        Map<String, List<SkuScheduleDTO>> structureSkuMap = new LinkedHashMap<>();
        structureSkuMap.put("S1", new ArrayList<SkuScheduleDTO>(Arrays.asList(formalSku, trialSku)));
        context.setStructureSkuMap(structureSkuMap);

        context.removePendingSkuFromStructureMap(trialSku);

        assertEquals(1, context.getStructureSkuMap().get("S1").size());
        assertEquals("S", context.getStructureSkuMap().get("S1").get(0).getProductStatus());
    }

    @Test
    void rebuildStructureSkuMapFromPending_shouldRebuildByCurrentPendingOrder() {
        LhScheduleContext context = new LhScheduleContext();
        context.setStructureSkuMap(new LinkedHashMap<String, List<SkuScheduleDTO>>());
        SkuScheduleDTO sku1 = sku("MAT-1", "S2");
        SkuScheduleDTO sku2 = sku("MAT-2", "S1");
        SkuScheduleDTO sku3 = sku("MAT-3", "S2");

        context.rebuildStructureSkuMapFromPending(new ArrayList<SkuScheduleDTO>(Arrays.asList(sku1, sku2, sku3)));

        assertEquals(2, context.getStructureSkuMap().size());
        assertEquals("MAT-1", context.getStructureSkuMap().get("S2").get(0).getMaterialCode());
        assertEquals("MAT-3", context.getStructureSkuMap().get("S2").get(1).getMaterialCode());
        assertEquals("MAT-2", context.getStructureSkuMap().get("S1").get(0).getMaterialCode());
    }

    @Test
    void rebuildStructureSkuMapFromPending_shouldClearWhenPendingEmpty() {
        LhScheduleContext context = new LhScheduleContext();
        Map<String, List<SkuScheduleDTO>> structureSkuMap = new LinkedHashMap<>();
        structureSkuMap.put("S1", new ArrayList<SkuScheduleDTO>(Arrays.asList(sku("MAT-A", "S1"))));
        context.setStructureSkuMap(structureSkuMap);

        context.rebuildStructureSkuMapFromPending(Collections.<SkuScheduleDTO>emptyList());

        assertTrue(context.getStructureSkuMap().isEmpty());
    }

    /**
     * 中心化运行视图必须让同一 SKU 的选机、模拟和实际扣账共享同一临时前移账本，
     * 清理后恢复读取原始账本，且全过程不得修改原始日计划对象。
     */
    @Test
    void resolveEffectiveDailyPlanQuotaMap_shouldShareRuntimeViewWithoutMutatingOriginalPlan() {
        LocalDate currentDate = LocalDate.of(2026, 7, 30);
        SkuScheduleDTO sku = sku("MAT-EARLY", "S1");
        Map<LocalDate, SkuDailyPlanQuotaDTO> originalQuotaMap =
                new LinkedHashMap<LocalDate, SkuDailyPlanQuotaDTO>(2);
        originalQuotaMap.put(currentDate, quota(currentDate, 0));
        sku.setDailyPlanQuotaMap(originalQuotaMap);

        Map<LocalDate, SkuDailyPlanQuotaDTO> shiftedQuotaMap =
                new LinkedHashMap<LocalDate, SkuDailyPlanQuotaDTO>(2);
        shiftedQuotaMap.put(currentDate, quota(currentDate, 46));
        EarlyProductionRuntimePlan runtimePlan = new EarlyProductionRuntimePlan();
        runtimePlan.setCurrentDate(currentDate);
        runtimePlan.setActive(true);
        runtimePlan.setShiftedDailyPlanQuotaMap(shiftedQuotaMap);
        LhScheduleContext context = new LhScheduleContext();

        // 调用处注册一次运行视图，后续所有资源链读取同一 Map 实例。
        context.registerEarlyProductionRuntimePlan(sku, runtimePlan);
        Map<LocalDate, SkuDailyPlanQuotaDTO> firstRead =
                context.resolveEffectiveDailyPlanQuotaMap(sku);
        Map<LocalDate, SkuDailyPlanQuotaDTO> secondRead =
                context.resolveEffectiveDailyPlanQuotaMap(sku);

        assertSame(shiftedQuotaMap, firstRead);
        assertSame(firstRead, secondRead);
        assertEquals(46, firstRead.get(currentDate).getDayPlanQty());
        assertEquals(0, originalQuotaMap.get(currentDate).getDayPlanQty(),
                "临时前移账本不得污染 SKU 原始日计划");

        context.clearEarlyProductionRuntimePlans();
        assertSame(originalQuotaMap, context.resolveEffectiveDailyPlanQuotaMap(sku));
    }

    /**
     * 提前生产候选态尚未激活时不得向选机、模拟和扣账链暴露临时账本。
     */
    @Test
    void resolveEffectiveDailyPlanQuotaMap_shouldIgnoreInactiveCandidateView() {
        LocalDate currentDate = LocalDate.of(2026, 7, 29);
        SkuScheduleDTO sku = sku("MAT-CANDIDATE", "S1");
        Map<LocalDate, SkuDailyPlanQuotaDTO> originalQuotaMap =
                new LinkedHashMap<LocalDate, SkuDailyPlanQuotaDTO>(1);
        originalQuotaMap.put(currentDate, quota(currentDate, 0));
        sku.setDailyPlanQuotaMap(originalQuotaMap);
        EarlyProductionRuntimePlan runtimePlan = new EarlyProductionRuntimePlan();
        runtimePlan.setFutureOnlyCandidate(true);
        runtimePlan.setActive(false);
        runtimePlan.getShiftedDailyPlanQuotaMap().put(currentDate, quota(currentDate, 46));
        LhScheduleContext context = new LhScheduleContext();

        // 调用处登记候选态；未激活前必须继续读取原始账本，不能提前参与资源竞争。
        context.registerEarlyProductionRuntimePlan(sku, runtimePlan);

        assertTrue(context.isFutureOnlyEarlyProductionCandidate(sku));
        assertSame(originalQuotaMap, context.resolveEffectiveDailyPlanQuotaMap(sku));
    }

    /**
     * 结构和 SKU 已排机台统计都必须按业务日期及机台编码 Set 去重，
     * 空产品状态按正规 S 读取，避免同机台多班次或同结构多 SKU 重复计数。
     */
    @Test
    void recordScheduledMachine_shouldDeduplicateByBusinessDateAndMachineCode() {
        LocalDate businessDate = LocalDate.of(2026, 7, 30);
        LhScheduleContext context = new LhScheduleContext();

        context.recordScheduledMachine(businessDate, "S1", "MAT-A", null, "K1101");
        context.recordScheduledMachine(businessDate, "S1", "MAT-A", "S", "K1101");
        context.recordScheduledMachine(businessDate, "S1", "MAT-B", "S", "K1101");
        context.recordScheduledMachine(businessDate, "S1", "MAT-A", "S", "K1102");

        assertEquals(2, context.getStructureScheduledMachineCount(businessDate, "S1"),
                "同结构多个 SKU 共用同一机台只能统计一次");
        assertEquals(2, context.getSkuScheduledMachineCount(businessDate, "MAT-A", null),
                "同一 SKU 同一机台多次登记只能统计一次，空状态按正规 S 读取");
        assertEquals(1, context.getSkuScheduledMachineCount(businessDate, "MAT-B", "S"));
    }

    private SkuScheduleDTO sku(String materialCode, String structureName) {
        SkuScheduleDTO sku = new SkuScheduleDTO();
        sku.setMaterialCode(materialCode);
        sku.setStructureName(structureName);
        return sku;
    }

    /**
     * 构建日计划额度。
     *
     * @param productionDate 生产日期
     * @param dayPlanQty 原始日计划量
     * @return 日计划额度
     */
    private SkuDailyPlanQuotaDTO quota(LocalDate productionDate, int dayPlanQty) {
        SkuDailyPlanQuotaDTO quota = new SkuDailyPlanQuotaDTO();
        quota.setProductionDate(productionDate);
        quota.setDayPlanQty(dayPlanQty);
        quota.setRemainingQty(dayPlanQty);
        return quota;
    }
}
