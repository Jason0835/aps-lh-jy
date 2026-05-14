package com.zlt.aps.lh.util;

import com.zlt.aps.lh.api.constant.LhScheduleParamConstant;
import com.zlt.aps.lh.api.domain.entity.LhSpecifyMachine;
import com.zlt.aps.lh.api.enums.JobTypeEnum;
import com.zlt.aps.lh.context.LhScheduleConfig;
import com.zlt.aps.lh.context.LhScheduleContext;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 硫化定点机台工具测试。
 *
 * @author APS
 */
public class LhSpecifyMachineUtilTest {

    /**
     * 用例说明：定点机台规则默认关闭时，配置不参与排程判断。
     */
    @Test
    public void shouldIgnoreSpecifyMachineWhenRuleDisabledByDefault() {
        LhScheduleContext context = buildContext(false);

        List<LhSpecifyMachine> specifyMachineList =
                LhSpecifyMachineUtil.listLimitSpecifyMachinesByMaterialCode(context, "MAT-001");

        Assertions.assertTrue(specifyMachineList.isEmpty());
        Assertions.assertFalse(LhSpecifyMachineUtil.isLimitSpecifyMachine(context, "M1", "MAT-001"));
        Assertions.assertFalse(LhSpecifyMachineUtil.isNotAllowedMachine(context, "M2", "MAT-001"));
    }

    /**
     * 用例说明：定点机台规则开启时，配置按原有口径参与排程判断。
     */
    @Test
    public void shouldUseSpecifyMachineWhenRuleEnabled() {
        LhScheduleContext context = buildContext(true);

        List<LhSpecifyMachine> specifyMachineList =
                LhSpecifyMachineUtil.listLimitSpecifyMachinesByMaterialCode(context, "MAT-001");

        Assertions.assertEquals(1, specifyMachineList.size());
        Assertions.assertTrue(LhSpecifyMachineUtil.isLimitSpecifyMachine(context, "M1", "MAT-001"));
        Assertions.assertTrue(LhSpecifyMachineUtil.isNotAllowedMachine(context, "M2", "MAT-001"));
    }

    /**
     * 用例说明：单控拆分机台启用后，基准机台上的定点配置仍应对运行态拆分机台生效。
     */
    @Test
    public void shouldMatchSplitMachineCodeWhenSingleControlEnabled() {
        LhScheduleContext context = buildContext(true);
        Map<String, String> paramMap = new HashMap<>(2);
        paramMap.put(LhScheduleParamConstant.ENABLE_SPECIFY_MACHINE_RULE, "1");
        paramMap.put(LhScheduleParamConstant.SINGLE_CONTROL_MACHINE_CODES, "K1501");
        context.setScheduleConfig(new LhScheduleConfig(paramMap));
        context.getSpecifyMachineMap().put("MAT-SINGLE", java.util.Arrays.asList(
                specifyMachine("MAT-SINGLE", "K1501", JobTypeEnum.RESTRICTED.getCode()),
                specifyMachine("MAT-SINGLE", "K1501", JobTypeEnum.NOT_ALLOWED.getCode())));

        Assertions.assertTrue(LhSpecifyMachineUtil.isLimitSpecifyMachine(context, "K1501L", "MAT-SINGLE"));
        Assertions.assertTrue(LhSpecifyMachineUtil.isLimitSpecifyMachine(context, "K1501R", "MAT-SINGLE"));
        Assertions.assertTrue(LhSpecifyMachineUtil.isNotAllowedMachine(context, "K1501L", "MAT-SINGLE"));
        Assertions.assertTrue(LhSpecifyMachineUtil.isNotAllowedMachine(context, "K1501R", "MAT-SINGLE"));
    }

    private LhScheduleContext buildContext(boolean enabled) {
        LhScheduleContext context = new LhScheduleContext();
        if (enabled) {
            Map<String, String> paramMap = new HashMap<>(1);
            paramMap.put(LhScheduleParamConstant.ENABLE_SPECIFY_MACHINE_RULE, "1");
            context.setScheduleConfig(new LhScheduleConfig(paramMap));
        }
        context.getSpecifyMachineMap().put("MAT-001", java.util.Arrays.asList(
                specifyMachine("MAT-001", "M1", JobTypeEnum.RESTRICTED.getCode()),
                specifyMachine("MAT-001", "M2", JobTypeEnum.NOT_ALLOWED.getCode())));
        return context;
    }

    private LhSpecifyMachine specifyMachine(String materialCode, String machineCode, String jobType) {
        LhSpecifyMachine specifyMachine = new LhSpecifyMachine();
        specifyMachine.setSpecCode(materialCode);
        specifyMachine.setMachineCode(machineCode);
        specifyMachine.setJobType(jobType);
        return specifyMachine;
    }
}
