package com.zlt.aps.lh.context;

import com.zlt.aps.lh.api.domain.dto.SkuScheduleDTO;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    private SkuScheduleDTO sku(String materialCode, String structureName) {
        SkuScheduleDTO sku = new SkuScheduleDTO();
        sku.setMaterialCode(materialCode);
        sku.setStructureName(structureName);
        return sku;
    }
}
