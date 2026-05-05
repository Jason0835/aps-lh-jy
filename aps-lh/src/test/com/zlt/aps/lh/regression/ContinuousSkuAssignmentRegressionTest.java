package com.zlt.aps.lh.regression;

import com.zlt.aps.lh.api.domain.dto.MachineScheduleDTO;
import com.zlt.aps.lh.api.domain.dto.SkuScheduleDTO;
import com.zlt.aps.lh.api.domain.entity.LhMachineOnlineInfo;
import com.zlt.aps.lh.api.domain.entity.LhScheduleResult;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.handler.ScheduleAdjustHandler;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 续作归类回归：停用或不可排机台的 MES 在机记录不能抢占续作 SKU。
 */
class ContinuousSkuAssignmentRegressionTest {

    private final ScheduleAdjustHandler handler = new ScheduleAdjustHandler();

    @Test
    void classifyContinuousAndNewSkus_shouldSkipMesMachineThatIsNotSchedulable() {
        LhScheduleContext context = new LhScheduleContext();

        SkuScheduleDTO sku = new SkuScheduleDTO();
        sku.setMaterialCode("3302001316");
        sku.setMaterialDesc("测试物料");
        sku.setStructureName("STRUCT-1");

        Map<String, java.util.List<SkuScheduleDTO>> structureSkuMap = new LinkedHashMap<>();
        structureSkuMap.put("STRUCT-1", new ArrayList<SkuScheduleDTO>());
        structureSkuMap.get("STRUCT-1").add(sku);
        context.setStructureSkuMap(structureSkuMap);

        Map<String, LhMachineOnlineInfo> machineOnlineInfoMap = new LinkedHashMap<>();
        machineOnlineInfoMap.put("K1110", buildOnlineInfo("K1110", "3302001316"));
        machineOnlineInfoMap.put("K1113", buildOnlineInfo("K1113", "3302001316"));
        context.setMachineOnlineInfoMap(machineOnlineInfoMap);

        Map<String, MachineScheduleDTO> machineScheduleMap = new LinkedHashMap<>();
        machineScheduleMap.put("K1113", buildMachine("K1113", "1"));
        context.setMachineScheduleMap(machineScheduleMap);

        ReflectionTestUtils.invokeMethod(handler, "classifyContinuousAndNewSkus", context);

        assertEquals(1, context.getContinuousSkuList().size());
        assertEquals("K1113", context.getContinuousSkuList().get(0).getContinuousMachineCode());
        assertEquals(0, context.getNewSpecSkuList().size());
    }

    @Test
    void classifyContinuousAndNewSkus_shouldPreferRollingInheritedMaterialOverMesSnapshot() {
        LhScheduleContext context = new LhScheduleContext();
        context.setRollingScheduleHandoff(true);

        Map<String, java.util.List<SkuScheduleDTO>> structureSkuMap = new LinkedHashMap<>();
        structureSkuMap.put("STRUCT-1", new ArrayList<SkuScheduleDTO>());
        structureSkuMap.get("STRUCT-1").add(buildSku("3302001585", "STRUCT-1"));
        structureSkuMap.get("STRUCT-1").add(buildSku("3302001270", "STRUCT-1"));
        context.setStructureSkuMap(structureSkuMap);

        Map<String, LhMachineOnlineInfo> machineOnlineInfoMap = new LinkedHashMap<>();
        machineOnlineInfoMap.put("K1105", buildOnlineInfo("K1105", "3302001270"));
        context.setMachineOnlineInfoMap(machineOnlineInfoMap);

        Map<String, MachineScheduleDTO> machineScheduleMap = new LinkedHashMap<>();
        machineScheduleMap.put("K1105", buildMachine("K1105", "1"));
        machineScheduleMap.get("K1105").setCurrentMaterialCode("3302001585");
        context.setMachineScheduleMap(machineScheduleMap);
        context.getRollingInheritedScheduleResultList().add(buildInheritedResult("K1105", "3302001585", "0"));

        ReflectionTestUtils.invokeMethod(handler, "classifyContinuousAndNewSkus", context);

        assertEquals(1, context.getContinuousSkuList().size());
        assertEquals("3302001585", context.getContinuousSkuList().get(0).getMaterialCode());
        assertEquals("K1105", context.getContinuousSkuList().get(0).getContinuousMachineCode());
        assertEquals(1, context.getNewSpecSkuList().size());
        assertEquals("3302001270", context.getNewSpecSkuList().get(0).getMaterialCode());
    }

    @Test
    void classifyContinuousAndNewSkus_shouldAssignRollingInheritedMaterialWithoutMesSnapshot() {
        LhScheduleContext context = new LhScheduleContext();
        context.setRollingScheduleHandoff(true);

        Map<String, java.util.List<SkuScheduleDTO>> structureSkuMap = new LinkedHashMap<>();
        structureSkuMap.put("STRUCT-1", new ArrayList<SkuScheduleDTO>());
        structureSkuMap.get("STRUCT-1").add(buildSku("3302001002", "STRUCT-1"));
        context.setStructureSkuMap(structureSkuMap);

        Map<String, MachineScheduleDTO> machineScheduleMap = new LinkedHashMap<>();
        machineScheduleMap.put("K2003", buildMachine("K2003", "1"));
        machineScheduleMap.get("K2003").setCurrentMaterialCode("3302001002");
        context.setMachineScheduleMap(machineScheduleMap);
        context.getRollingInheritedScheduleResultList().add(buildInheritedResult("K2003", "3302001002", "0"));

        ReflectionTestUtils.invokeMethod(handler, "classifyContinuousAndNewSkus", context);

        assertEquals(1, context.getContinuousSkuList().size());
        assertEquals("3302001002", context.getContinuousSkuList().get(0).getMaterialCode());
        assertEquals("K2003", context.getContinuousSkuList().get(0).getContinuousMachineCode());
        assertEquals(0, context.getNewSpecSkuList().size());
    }

    private LhMachineOnlineInfo buildOnlineInfo(String machineCode, String materialCode) {
        LhMachineOnlineInfo onlineInfo = new LhMachineOnlineInfo();
        onlineInfo.setLhCode(machineCode);
        onlineInfo.setMaterialCode(materialCode);
        return onlineInfo;
    }

    private SkuScheduleDTO buildSku(String materialCode, String structureName) {
        SkuScheduleDTO sku = new SkuScheduleDTO();
        sku.setMaterialCode(materialCode);
        sku.setMaterialDesc("测试物料-" + materialCode);
        sku.setStructureName(structureName);
        return sku;
    }

    private MachineScheduleDTO buildMachine(String machineCode, String status) {
        MachineScheduleDTO machine = new MachineScheduleDTO();
        machine.setMachineCode(machineCode);
        machine.setStatus(status);
        return machine;
    }

    private LhScheduleResult buildInheritedResult(String machineCode, String materialCode, String isEnd) {
        LhScheduleResult result = new LhScheduleResult();
        result.setLhMachineCode(machineCode);
        result.setMaterialCode(materialCode);
        result.setIsEnd(isEnd);
        result.setSpecEndTime(new Date());
        result.setRollingInherited(true);
        return result;
    }
}
