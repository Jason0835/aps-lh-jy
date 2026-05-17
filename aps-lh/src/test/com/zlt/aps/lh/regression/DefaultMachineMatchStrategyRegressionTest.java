package com.zlt.aps.lh.regression;

import com.zlt.aps.lh.api.constant.LhScheduleParamConstant;
import com.zlt.aps.lh.api.domain.dto.MachineScheduleDTO;
import com.zlt.aps.lh.api.domain.dto.SkuScheduleDTO;
import com.zlt.aps.lh.api.domain.entity.LhScheduleProcessLog;
import com.zlt.aps.lh.api.domain.entity.LhSpecifyMachine;
import com.zlt.aps.lh.api.enums.ConstructionStageEnum;
import com.zlt.aps.lh.api.enums.JobTypeEnum;
import com.zlt.aps.lh.context.LhScheduleConfig;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.engine.strategy.impl.DefaultMachineMatchStrategy;
import com.zlt.aps.lh.util.LhScheduleTimeUtil;
import com.zlt.aps.mdm.api.domain.entity.MdmDevicePlanShut;
import com.zlt.aps.mdm.api.domain.entity.MdmMaterialInfo;
import com.zlt.aps.mdm.api.domain.entity.MdmModelInfo;
import com.zlt.aps.mdm.api.domain.entity.MdmSkuMouldRel;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 机台匹配回归：SKU存在多条模具关系时，不应把关系条数误当成待选前的用模数。
 */
class DefaultMachineMatchStrategyRegressionTest {

    @Test
    void matchMachines_shouldUseSpecifySpecCodeAsMaterialCodeForLimitPriority() {
        DefaultMachineMatchStrategy strategy = new DefaultMachineMatchStrategy();
        LhScheduleContext context = buildContext();
        enableSpecifyMachineRule(context);

        MachineScheduleDTO normalMachine = machine("M-NORMAL", dateTime(2026, 4, 21, 8, 0),
                "SPEC-A", "22.5", "MAT-NORMAL");
        MachineScheduleDTO specifyMachine = machine("M-SPECIFY", dateTime(2026, 4, 21, 8, 30),
                "SPEC-X", "22.5", "MAT-SPECIFY");
        // 定点机台即使维护了特殊支持能力，也不能盖过限制作业优先级。
        specifyMachine.setSupport225WideBase("1");
        context.getMachineScheduleMap().put(normalMachine.getMachineCode(), normalMachine);
        context.getMachineScheduleMap().put(specifyMachine.getMachineCode(), specifyMachine);
        context.getSpecifyMachineMap().put("MAT-001", Collections.singletonList(
                specifyMachine("MAT-001", "M-SPECIFY", JobTypeEnum.RESTRICTED.getCode())));

        SkuScheduleDTO sku = sku("MAT-001", "SPEC-A", "22.5");

        List<MachineScheduleDTO> candidates = strategy.matchMachines(context, sku);

        assertEquals(2, candidates.size(), "定点机台只是优先，不应过滤普通候选机台");
        assertEquals("M-SPECIFY", candidates.get(0).getMachineCode(),
                "T_LH_SPECIFY_MACHINE.SPEC_CODE 应按物料编码匹配 SKU.materialCode");
    }

    @Test
    void matchMachines_shouldFallbackToNormalMachineWhenLimitMachineUnavailable() {
        DefaultMachineMatchStrategy strategy = new DefaultMachineMatchStrategy();
        LhScheduleContext context = buildContext();
        enableSpecifyMachineRule(context);

        MachineScheduleDTO disabledSpecifyMachine = machine("M-SPECIFY", dateTime(2026, 4, 21, 7, 0),
                "SPEC-A", "22.5", "MAT-SPECIFY");
        disabledSpecifyMachine.setStatus("0");
        MachineScheduleDTO normalMachine = machine("M-NORMAL", dateTime(2026, 4, 21, 8, 0),
                "SPEC-A", "22.5", "MAT-NORMAL");
        context.getMachineScheduleMap().put(disabledSpecifyMachine.getMachineCode(), disabledSpecifyMachine);
        context.getMachineScheduleMap().put(normalMachine.getMachineCode(), normalMachine);
        context.getSpecifyMachineMap().put("MAT-001", Collections.singletonList(
                specifyMachine("MAT-001", "M-SPECIFY", JobTypeEnum.RESTRICTED.getCode())));

        List<MachineScheduleDTO> candidates = strategy.matchMachines(context, sku("MAT-001", "SPEC-A", "22.5"));

        assertEquals(1, candidates.size(), "定点机台不可排时，应回到普通机台匹配逻辑");
        assertEquals("M-NORMAL", candidates.get(0).getMachineCode());
    }

    @Test
    void matchMachines_shouldExcludeNotAllowedMachineByMaterialCode() {
        DefaultMachineMatchStrategy strategy = new DefaultMachineMatchStrategy();
        LhScheduleContext context = buildContext();
        enableSpecifyMachineRule(context);

        MachineScheduleDTO forbiddenMachine = machine("M-FORBIDDEN", dateTime(2026, 4, 21, 7, 0),
                "SPEC-A", "22.5", "MAT-FORBIDDEN");
        MachineScheduleDTO normalMachine = machine("M-NORMAL", dateTime(2026, 4, 21, 8, 0),
                "SPEC-A", "22.5", "MAT-NORMAL");
        context.getMachineScheduleMap().put(forbiddenMachine.getMachineCode(), forbiddenMachine);
        context.getMachineScheduleMap().put(normalMachine.getMachineCode(), normalMachine);
        context.getSpecifyMachineMap().put("MAT-001", Collections.singletonList(
                specifyMachine("MAT-001", "M-FORBIDDEN", JobTypeEnum.NOT_ALLOWED.getCode())));

        List<MachineScheduleDTO> candidates = strategy.matchMachines(context, sku("MAT-001", "SPEC-A", "22.5"));

        assertEquals(1, candidates.size(), "不可作业机台必须按物料编码定点关系排除");
        assertEquals("M-NORMAL", candidates.get(0).getMachineCode());
    }

    @Test
    void matchMachines_shouldIgnoreLimitSpecifyPriorityWhenRuleDisabled() {
        DefaultMachineMatchStrategy strategy = new DefaultMachineMatchStrategy();
        LhScheduleContext context = buildContext();

        MachineScheduleDTO normalMachine = machine("M-NORMAL", dateTime(2026, 4, 21, 8, 0),
                "SPEC-A", "22.5", "MAT-NORMAL");
        MachineScheduleDTO specifyMachine = machine("M-SPECIFY", dateTime(2026, 4, 21, 8, 30),
                "SPEC-X", "22.5", "MAT-SPECIFY");
        context.getMachineScheduleMap().put(normalMachine.getMachineCode(), normalMachine);
        context.getMachineScheduleMap().put(specifyMachine.getMachineCode(), specifyMachine);
        context.getSpecifyMachineMap().put("MAT-001", Collections.singletonList(
                specifyMachine("MAT-001", "M-SPECIFY", JobTypeEnum.RESTRICTED.getCode())));

        List<MachineScheduleDTO> candidates = strategy.matchMachines(context, sku("MAT-001", "SPEC-A", "22.5"));

        assertEquals(2, candidates.size(), "关闭开关后定点机台不应提升优先级");
        assertEquals("M-NORMAL", candidates.get(0).getMachineCode());
    }

    @Test
    void matchMachines_shouldIgnoreNotAllowedMachineWhenRuleDisabled() {
        DefaultMachineMatchStrategy strategy = new DefaultMachineMatchStrategy();
        LhScheduleContext context = buildContext();

        MachineScheduleDTO forbiddenMachine = machine("M-FORBIDDEN", dateTime(2026, 4, 21, 7, 0),
                "SPEC-A", "22.5", "MAT-FORBIDDEN");
        MachineScheduleDTO normalMachine = machine("M-NORMAL", dateTime(2026, 4, 21, 8, 0),
                "SPEC-A", "22.5", "MAT-NORMAL");
        context.getMachineScheduleMap().put(forbiddenMachine.getMachineCode(), forbiddenMachine);
        context.getMachineScheduleMap().put(normalMachine.getMachineCode(), normalMachine);
        context.getSpecifyMachineMap().put("MAT-001", Collections.singletonList(
                specifyMachine("MAT-001", "M-FORBIDDEN", JobTypeEnum.NOT_ALLOWED.getCode())));

        List<MachineScheduleDTO> candidates = strategy.matchMachines(context, sku("MAT-001", "SPEC-A", "22.5"));

        assertEquals(2, candidates.size(), "关闭开关后不可作业配置不应过滤候选机台");
        assertEquals("M-FORBIDDEN", candidates.get(0).getMachineCode());
    }

    @Test
    void matchMachines_shouldKeepSingleControlPriorityForTrialSkuWhenSingleControlIsEnding() {
        DefaultMachineMatchStrategy strategy = new DefaultMachineMatchStrategy();
        LhScheduleContext context = buildContext();
        enableSingleControlMachines(context);

        MachineScheduleDTO normalMachine = machine("K1401", dateTime(2026, 5, 9, 8, 0),
                "SPEC-A", "22.5", "MAT-NORMAL");
        MachineScheduleDTO singleControlMachine = machine("K1501L", dateTime(2026, 5, 9, 8, 0),
                "SPEC-A", "22.5", "MAT-SINGLE");
        singleControlMachine.setEnding(true);
        context.getMachineScheduleMap().put(normalMachine.getMachineCode(), normalMachine);
        context.getMachineScheduleMap().put(singleControlMachine.getMachineCode(), singleControlMachine);

        SkuScheduleDTO sku = sku("3302001575", "SPEC-A", "22.5");
        sku.setTrial(true);

        List<MachineScheduleDTO> candidates = strategy.matchMachines(context, sku);

        assertEquals(2, candidates.size());
        assertEquals("K1501L", candidates.get(0).getMachineCode(),
                "试制量试 SKU 命中收尾单控机台时，应继续优先等待并占用单控机台");
    }

    @Test
    void matchMachines_shouldPreferSingleControlCandidatesForTrialSkuWhenAvailable() {
        DefaultMachineMatchStrategy strategy = new DefaultMachineMatchStrategy();
        LhScheduleContext context = buildContext();
        enableSingleControlMachines(context);

        MachineScheduleDTO normalMachine = machine("K1111", dateTime(2026, 5, 9, 8, 0),
                "SPEC-A", "22.5", "MAT-NORMAL");
        MachineScheduleDTO singleControlMachine = machine("K1501L", dateTime(2026, 5, 9, 12, 0),
                "SPEC-A", "22.5", "MAT-SINGLE");
        context.getMachineScheduleMap().put(normalMachine.getMachineCode(), normalMachine);
        context.getMachineScheduleMap().put(singleControlMachine.getMachineCode(), singleControlMachine);

        SkuScheduleDTO sku = sku("3302002637", "SPEC-A", "19.5");
        sku.setTrial(true);

        List<MachineScheduleDTO> candidates = strategy.matchMachines(context, sku);

        assertEquals(2, candidates.size(), "有单控且有普通机台时，试制量试应保留单控候选");
        assertEquals("K1501L", candidates.get(0).getMachineCode(),
                "单控机台未收尾但可用时，试制量试仍应优先占用单控机台");
    }

    @Test
    void matchMachines_shouldKeepOnlySingleControlCandidatesForTrialConstructionStage() {
        DefaultMachineMatchStrategy strategy = new DefaultMachineMatchStrategy();
        LhScheduleContext context = buildContext();
        enableSingleControlMachines(context);

        MachineScheduleDTO normalMachine = machine("K1111", dateTime(2026, 5, 9, 8, 0),
                "SPEC-A", "22.5", "MAT-NORMAL");
        MachineScheduleDTO singleControlMachine = machine("K1501R", dateTime(2026, 5, 9, 12, 0),
                "SPEC-A", "22.5", "MAT-SINGLE");
        context.getMachineScheduleMap().put(normalMachine.getMachineCode(), normalMachine);
        context.getMachineScheduleMap().put(singleControlMachine.getMachineCode(), singleControlMachine);

        SkuScheduleDTO sku = sku("3302001575", "SPEC-A", "19.5");
        sku.setConstructionStage(ConstructionStageEnum.TRIAL.getCode());

        List<MachineScheduleDTO> candidates = strategy.matchMachines(context, sku);

        assertEquals(1, candidates.size(), "试制施工阶段命中单控机台时，只能保留单控候选");
        assertEquals("K1501R", candidates.get(0).getMachineCode(),
                "试制施工阶段不应回落普通机台 K1111");
    }

    @Test
    void matchMachines_shouldPreferSingleControlCandidatesForMassTrialWhenAvailable() {
        DefaultMachineMatchStrategy strategy = new DefaultMachineMatchStrategy();
        LhScheduleContext context = buildContext();
        enableSingleControlMachines(context);

        MachineScheduleDTO normalMachine = machine("K1111", dateTime(2026, 5, 9, 8, 0),
                "SPEC-A", "22.5", "MAT-NORMAL");
        MachineScheduleDTO singleControlMachine = machine("K1501R", dateTime(2026, 5, 9, 12, 0),
                "SPEC-A", "22.5", "MAT-SINGLE");
        context.getMachineScheduleMap().put(normalMachine.getMachineCode(), normalMachine);
        context.getMachineScheduleMap().put(singleControlMachine.getMachineCode(), singleControlMachine);

        SkuScheduleDTO sku = sku("3302002637", "SPEC-A", "19.5");
        sku.setConstructionStage("02");

        List<MachineScheduleDTO> candidates = strategy.matchMachines(context, sku);

        assertEquals(2, candidates.size(), "量试施工阶段在有单控机台时，应继续保留单控候选");
        assertEquals("K1501R", candidates.get(0).getMachineCode(),
                "量试施工阶段命中单控机台时，应优先保留单控机台而不是回落普通机台");
    }

    @Test
    void matchMachines_shouldExcludeSingleControlMachineForNormalSku() {
        DefaultMachineMatchStrategy strategy = new DefaultMachineMatchStrategy();
        LhScheduleContext context = buildContext();
        enableSingleControlMachines(context);

        MachineScheduleDTO singleControlMachine = machine("K1501L", dateTime(2026, 5, 9, 8, 0),
                "SPEC-A", "22.5", "MAT-SINGLE");
        MachineScheduleDTO normalMachine = machine("K1401", dateTime(2026, 5, 9, 8, 0),
                "SPEC-A", "22.5", "MAT-NORMAL");
        context.getMachineScheduleMap().put(singleControlMachine.getMachineCode(), singleControlMachine);
        context.getMachineScheduleMap().put(normalMachine.getMachineCode(), normalMachine);

        List<MachineScheduleDTO> candidates = strategy.matchMachines(context, sku("3302001418", "SPEC-A", "22.5"));

        assertEquals(1, candidates.size());
        assertEquals("K1401", candidates.get(0).getMachineCode(),
                "普通新增 SKU 存在其它候选时不应抢占单控拆分机台");
    }

    @Test
    void matchMachines_shouldExcludeSingleControlCandidatesForNormalSku() {
        DefaultMachineMatchStrategy strategy = new DefaultMachineMatchStrategy();
        LhScheduleContext context = buildContext();
        enableSingleControlMachines(context);

        MachineScheduleDTO singleControlMachine = machine("K1501R", dateTime(2026, 5, 9, 8, 0),
                "SPEC-A", "22.5", "MAT-SINGLE");
        MachineScheduleDTO normalMachine = machine("K1111", dateTime(2026, 5, 9, 8, 0),
                "SPEC-A", "22.5", "MAT-NORMAL");
        context.getMachineScheduleMap().put(singleControlMachine.getMachineCode(), singleControlMachine);
        context.getMachineScheduleMap().put(normalMachine.getMachineCode(), normalMachine);

        List<MachineScheduleDTO> candidates = strategy.matchMachines(context, sku("3302001513", "SPEC-A", "22.5"));

        assertEquals(1, candidates.size(), "普通物料不应继续保留单控拆分机台候选，避免最终回落占机");
        assertEquals("K1111", candidates.get(0).getMachineCode());
    }

    @Test
    void matchMachines_shouldKeepSingleControlCandidatesForNormalSkuWhenExplicitlySpecified() {
        DefaultMachineMatchStrategy strategy = new DefaultMachineMatchStrategy();
        LhScheduleContext context = buildContext();
        enableSingleControlAndSpecifyMachineRule(context);

        MachineScheduleDTO singleControlMachine = machine("K1501R", dateTime(2026, 5, 9, 8, 0),
                "SPEC-A", "22.5", "MAT-SINGLE");
        MachineScheduleDTO normalMachine = machine("K1111", dateTime(2026, 5, 9, 8, 0),
                "SPEC-A", "22.5", "MAT-NORMAL");
        context.getMachineScheduleMap().put(singleControlMachine.getMachineCode(), singleControlMachine);
        context.getMachineScheduleMap().put(normalMachine.getMachineCode(), normalMachine);
        context.getSpecifyMachineMap().put("3302001513", Collections.singletonList(
                specifyMachine("3302001513", "K1501", JobTypeEnum.RESTRICTED.getCode())));

        List<MachineScheduleDTO> candidates = strategy.matchMachines(context, sku("3302001513", "SPEC-A", "22.5"));

        assertEquals(2, candidates.size(), "显式定点到单控机台时，仍应保留单控候选");
        assertEquals("K1501R", candidates.get(0).getMachineCode());
    }

    @Test
    void matchMachines_shouldFallbackToSingleControlCandidatesWhenNoNormalCandidates() {
        DefaultMachineMatchStrategy strategy = new DefaultMachineMatchStrategy();
        LhScheduleContext context = buildContext();
        enableSingleControlMachines(context);

        MachineScheduleDTO singleControlMachine = machine("K1501R", dateTime(2026, 5, 9, 8, 0),
                "SPEC-A", "22.5", "MAT-SINGLE");
        context.getMachineScheduleMap().put(singleControlMachine.getMachineCode(), singleControlMachine);

        List<MachineScheduleDTO> candidates = strategy.matchMachines(context, sku("3302001513", "SPEC-A", "22.5"));

        assertEquals(1, candidates.size(), "普通 SKU 无普通机台候选时，应回退保留单控拆分机台");
        assertEquals("K1501R", candidates.get(0).getMachineCode());
    }

    @Test
    void matchMachines_usesMouldQtyInsteadOfRelationCount() {
        DefaultMachineMatchStrategy strategy = new DefaultMachineMatchStrategy();
        LhScheduleContext context = buildContext();

        MachineScheduleDTO machine = new MachineScheduleDTO();
        machine.setMachineCode("M1");
        machine.setStatus("1");
        machine.setMaxMoldNum(1);

        context.setMachineScheduleMap(new LinkedHashMap<>());
        context.getMachineScheduleMap().put(machine.getMachineCode(), machine);
        context.setMachineAssignmentMap(new LinkedHashMap<>());

        SkuScheduleDTO sku = new SkuScheduleDTO();
        sku.setMaterialCode("MAT-1");

        context.getSkuMouldRelMap().put("MAT-1", Arrays.asList(
                mouldRel("MOULD-A"),
                mouldRel("MOULD-B"),
                mouldRel("MOULD-C")
        ));

        List<MachineScheduleDTO> candidates = strategy.matchMachines(context, sku);

        assertEquals(1, candidates.size(), "应按SKU实际用模数判断，不应因模具关系条数多而误过滤机台");
        assertEquals("M1", candidates.get(0).getMachineCode());
    }

    @Test
    void matchMachines_shouldPreferEarlierEndingTimeBeforeSpecWhenBeyondTolerance() {
        DefaultMachineMatchStrategy strategy = new DefaultMachineMatchStrategy();
        LhScheduleContext context = buildContext();

        MachineScheduleDTO earlierMachine = machine("M-EARLY", dateTime(2026, 4, 21, 8, 0),
                "SPEC-X", "22.5", "MAT-EARLY");
        MachineScheduleDTO specMatchedButLateMachine = machine("M-LATE", dateTime(2026, 4, 21, 8, 30),
                "SPEC-A", "22.5", "MAT-LATE");
        context.getMachineScheduleMap().put(earlierMachine.getMachineCode(), earlierMachine);
        context.getMachineScheduleMap().put(specMatchedButLateMachine.getMachineCode(), specMatchedButLateMachine);

        SkuScheduleDTO sku = sku("MAT-1", "SPEC-A", "22.5");

        List<MachineScheduleDTO> candidates = strategy.matchMachines(context, sku);

        assertEquals(2, candidates.size());
        assertEquals("M-EARLY", candidates.get(0).getMachineCode(),
                "收尾时间差超过阈值时，应优先更早收尾的机台，而不是先看规格");
    }

    @Test
    void matchMachines_shouldPreferSpecMatchWhenEndingTimeWithinTolerance() {
        DefaultMachineMatchStrategy strategy = new DefaultMachineMatchStrategy();
        LhScheduleContext context = buildContext();

        MachineScheduleDTO unmatchedSpecMachine = machine("M-UNMATCHED", dateTime(2026, 4, 21, 8, 0),
                "SPEC-X", "22.5", "MAT-A");
        MachineScheduleDTO matchedSpecMachine = machine("M-MATCHED", dateTime(2026, 4, 21, 8, 10),
                "SPEC-A", "20.0", "MAT-B");
        context.getMachineScheduleMap().put(unmatchedSpecMachine.getMachineCode(), unmatchedSpecMachine);
        context.getMachineScheduleMap().put(matchedSpecMachine.getMachineCode(), matchedSpecMachine);

        SkuScheduleDTO sku = sku("MAT-1", "SPEC-A", "22.5");

        List<MachineScheduleDTO> candidates = strategy.matchMachines(context, sku);

        assertEquals(2, candidates.size());
        assertEquals("M-MATCHED", candidates.get(0).getMachineCode(),
                "收尾时间在阈值内时，应进入规格优先比较");
    }

    @Test
    void matchMachines_shouldPreferCapsuleAffinityBeforeEmbryoShareCount() {
        DefaultMachineMatchStrategy strategy = new DefaultMachineMatchStrategy();
        LhScheduleContext context = buildContext();
        context.setCapsuleSpecPeerMap(new HashMap<String, String>() {{
            put("SPEC-A", "SPEC-A,SPEC-B");
            put("SPEC-B", "SPEC-A,SPEC-B");
        }});

        MachineScheduleDTO capsuleMatchedMachine = machine("M-CAPSULE", dateTime(2026, 4, 21, 8, 0),
                "SPEC-B", "20.0", "MAT-CAPSULE");
        MachineScheduleDTO embryoMoreMachine = machine("M-EMBRYO", dateTime(2026, 4, 21, 8, 0),
                "SPEC-C", "20.0", "MAT-EMBRYO");
        context.getMachineScheduleMap().put(capsuleMatchedMachine.getMachineCode(), capsuleMatchedMachine);
        context.getMachineScheduleMap().put(embryoMoreMachine.getMachineCode(), embryoMoreMachine);

        material(context, "MAT-CAPSULE", "胎胚-A");
        material(context, "MAT-EMBRYO", "胎胚-B");
        context.setEmbryoDescMaterialCountMap(new HashMap<String, Integer>() {{
            put("胎胚-A", 3);
            put("胎胚-B", 9);
        }});

        SkuScheduleDTO sku = sku("MAT-1", "SPEC-A", "22.5");

        List<MachineScheduleDTO> candidates = strategy.matchMachines(context, sku);

        assertEquals(2, candidates.size());
        assertEquals("M-CAPSULE", candidates.get(0).getMachineCode(),
                "前序层级完全相同且仅胶囊共用性不同，应先按胶囊共用性排序，再看胎胚共用数量");
    }

    @Test
    void matchMachines_shouldPreferHigherEmbryoShareCountWhenAllPreviousRulesTie() {
        DefaultMachineMatchStrategy strategy = new DefaultMachineMatchStrategy();
        LhScheduleContext context = buildContext();

        MachineScheduleDTO smallerEmbryoShareMachine = machine("M-SMALL", dateTime(2026, 4, 21, 8, 0),
                "SPEC-X", "20.0", "MAT-SMALL");
        MachineScheduleDTO biggerEmbryoShareMachine = machine("M-BIG", dateTime(2026, 4, 21, 8, 0),
                "SPEC-X", "20.0", "MAT-BIG");
        context.getMachineScheduleMap().put(smallerEmbryoShareMachine.getMachineCode(), smallerEmbryoShareMachine);
        context.getMachineScheduleMap().put(biggerEmbryoShareMachine.getMachineCode(), biggerEmbryoShareMachine);

        material(context, "MAT-SMALL", "胎胚-A");
        material(context, "MAT-BIG", "胎胚-B");
        context.setEmbryoDescMaterialCountMap(new HashMap<String, Integer>() {{
            put("胎胚-A", 2);
            put("胎胚-B", 8);
        }});

        SkuScheduleDTO sku = sku("MAT-1", "SPEC-A", "22.5");

        List<MachineScheduleDTO> candidates = strategy.matchMachines(context, sku);

        assertEquals(2, candidates.size());
        assertEquals("M-BIG", candidates.get(0).getMachineCode(),
                "前序规则都相同的情况下，应优先胎胚共用数量更多的机台");
    }

    @Test
    void matchMachines_shouldFallbackToProSizeGroupWhenSpecificationsMissing() {
        DefaultMachineMatchStrategy strategy = new DefaultMachineMatchStrategy();
        LhScheduleContext context = buildContext();
        context.setCapsuleProSizePeerMap(new HashMap<String, String>() {{
            put("22.5", "22.5,23.5");
            put("23.5", "22.5,23.5");
        }});

        MachineScheduleDTO proSizeMatchedMachine = machine("M-PROSIZE", dateTime(2026, 4, 21, 8, 0),
                null, "23.5", "MAT-PROSIZE");
        MachineScheduleDTO noAffinityMachine = machine("M-NONE", dateTime(2026, 4, 21, 8, 0),
                null, "24.5", "MAT-NONE");
        context.getMachineScheduleMap().put(proSizeMatchedMachine.getMachineCode(), proSizeMatchedMachine);
        context.getMachineScheduleMap().put(noAffinityMachine.getMachineCode(), noAffinityMachine);

        material(context, "MAT-PROSIZE", "胎胚-A");
        material(context, "MAT-NONE", "胎胚-B");
        context.setEmbryoDescMaterialCountMap(new HashMap<String, Integer>() {{
            put("胎胚-A", 1);
            put("胎胚-B", 1);
        }});

        SkuScheduleDTO sku = sku("MAT-1", "SPEC-A", "22.5");

        List<MachineScheduleDTO> candidates = strategy.matchMachines(context, sku);

        assertEquals(2, candidates.size());
        assertEquals("M-PROSIZE", candidates.get(0).getMachineCode(),
                "规格组缺失时，应回退按英寸组判断胶囊共用性");
    }

    @Test
    void matchMachines_shouldWritePriorityTraceLogWhenEnabled() {
        DefaultMachineMatchStrategy strategy = new DefaultMachineMatchStrategy();
        LhScheduleContext context = buildTraceContext();

        MachineScheduleDTO enabledMachine = machine("M-TRACE-1", dateTime(2026, 4, 21, 8, 0),
                "SPEC-A", "22.5", "MAT-A");
        enabledMachine.setMachineName("优先机台");
        MachineScheduleDTO fallbackMachine = machine("M-TRACE-2", dateTime(2026, 4, 21, 8, 15),
                "SPEC-X", "22.5", "MAT-B");
        fallbackMachine.setMachineName("兜底机台");
        MachineScheduleDTO disabledMachine = machine("M-DISABLED", dateTime(2026, 4, 21, 7, 0),
                "SPEC-A", "22.5", "MAT-C");
        disabledMachine.setStatus("0");
        context.getMachineScheduleMap().put(enabledMachine.getMachineCode(), enabledMachine);
        context.getMachineScheduleMap().put(fallbackMachine.getMachineCode(), fallbackMachine);
        context.getMachineScheduleMap().put(disabledMachine.getMachineCode(), disabledMachine);

        material(context, "MAT-A", "胎胚-A");
        material(context, "MAT-B", "胎胚-B");
        context.setEmbryoDescMaterialCountMap(Collections.singletonMap("胎胚-A", 2));

        SkuScheduleDTO sku = sku("MAT-TRACE", "SPEC-A", "22.5");

        List<MachineScheduleDTO> candidates = strategy.matchMachines(context, sku);

        assertEquals(2, candidates.size());
        assertEquals(1, context.getScheduleLogList().size());
        LhScheduleProcessLog processLog = context.getScheduleLogList().get(0);
        assertEquals("新增排产候选机台排序明细", processLog.getTitle());
        assertTrue(processLog.getLogDetail().contains("候选过滤概况"));
        assertTrue(processLog.getLogDetail().contains("M-TRACE-1"));
        assertTrue(processLog.getLogDetail().contains("M-TRACE-2"));
        assertTrue(processLog.getLogDetail().contains("M-DISABLED"));
        assertTrue(processLog.getLogDetail().contains("TOP5"));
    }

    @Test
    void matchMachines_shouldExcludeMachineWhenPlanStopExceedsTimeoutHours() {
        DefaultMachineMatchStrategy strategy = new DefaultMachineMatchStrategy();
        LhScheduleContext context = buildContext();
        context.setScheduleConfig(new LhScheduleConfig(Collections.singletonMap(
                LhScheduleParamConstant.MACHINE_STOP_TIMEOUT_HOURS, "24")));

        MachineScheduleDTO timeoutMachine = machine("M-STOP", dateTime(2026, 4, 21, 8, 0),
                "SPEC-A", "22.5", "MAT-A");
        MachineScheduleDTO availableMachine = machine("M-OK", dateTime(2026, 4, 21, 9, 0),
                "SPEC-A", "22.5", "MAT-B");
        context.getMachineScheduleMap().put(timeoutMachine.getMachineCode(), timeoutMachine);
        context.getMachineScheduleMap().put(availableMachine.getMachineCode(), availableMachine);
        context.getDevicePlanShutList().add(devicePlanShut("M-STOP",
                dateTime(2026, 4, 21, 8, 0), dateTime(2026, 4, 22, 10, 0)));

        SkuScheduleDTO sku = sku("MAT-1", "SPEC-A", "22.5");

        List<MachineScheduleDTO> candidates = strategy.matchMachines(context, sku);

        assertEquals(1, candidates.size(), "停机超过阈值的机台应被排除，新增规格继续选择其他可用机台");
        assertEquals("M-OK", candidates.get(0).getMachineCode());
    }

    @Test
    void matchMachines_shouldKeepRecoveredMachineWhenLongStopEndedBeforeReferenceTime() {
        DefaultMachineMatchStrategy strategy = new DefaultMachineMatchStrategy();
        LhScheduleContext context = buildContext();
        context.setScheduleConfig(new LhScheduleConfig(Collections.singletonMap(
                LhScheduleParamConstant.MACHINE_STOP_TIMEOUT_HOURS, "24")));

        MachineScheduleDTO recoveredMachine = machine("M-RECOVERED", dateTime(2026, 4, 22, 12, 0),
                "SPEC-A", "22.5", "MAT-A");
        MachineScheduleDTO availableMachine = machine("M-OK", dateTime(2026, 4, 22, 13, 0),
                "SPEC-A", "22.5", "MAT-B");
        context.getMachineScheduleMap().put(recoveredMachine.getMachineCode(), recoveredMachine);
        context.getMachineScheduleMap().put(availableMachine.getMachineCode(), availableMachine);
        context.getDevicePlanShutList().add(devicePlanShut("M-RECOVERED",
                dateTime(2026, 4, 21, 8, 0), dateTime(2026, 4, 22, 10, 0)));

        List<MachineScheduleDTO> candidates = strategy.matchMachines(context, sku("MAT-1", "SPEC-A", "22.5"));

        assertEquals(2, candidates.size(), "长停机已在待排前恢复时，不应继续排除该机台");
        assertTrue(candidates.stream().anyMatch(machine -> "M-RECOVERED".equals(machine.getMachineCode())));
    }

    @Test
    void matchMachines_shouldKeepStopMachineWhenNoAlternativeExists() {
        DefaultMachineMatchStrategy strategy = new DefaultMachineMatchStrategy();
        LhScheduleContext context = buildContext();
        context.setScheduleConfig(new LhScheduleConfig(Collections.singletonMap(
                LhScheduleParamConstant.MACHINE_STOP_TIMEOUT_HOURS, "24")));

        MachineScheduleDTO onlyMachine = machine("M-ONLY", dateTime(2026, 4, 21, 8, 0),
                "SPEC-A", "22.5", "MAT-A");
        context.getMachineScheduleMap().put(onlyMachine.getMachineCode(), onlyMachine);
        context.getDevicePlanShutList().add(devicePlanShut("M-ONLY",
                dateTime(2026, 4, 21, 7, 0), dateTime(2026, 4, 22, 10, 0)));

        List<MachineScheduleDTO> candidates = strategy.matchMachines(context, sku("MAT-1", "SPEC-A", "22.5"));

        assertEquals(1, candidates.size(), "没有其他可用机台时，不应把唯一候选机台直接排除");
        assertEquals("M-ONLY", candidates.get(0).getMachineCode());
    }

    @Test
    void matchMachines_shouldPreferNormalMachineForNonSpecialSku() {
        DefaultMachineMatchStrategy strategy = new DefaultMachineMatchStrategy();
        LhScheduleContext context = buildContext();

        MachineScheduleDTO specialSupportMachine = machine("M-SPECIAL", dateTime(2026, 4, 21, 8, 0),
                "SPEC-A", "22.5", "MAT-SPECIAL");
        specialSupportMachine.setSupport195WideBase("1");
        MachineScheduleDTO normalMachine = machine("M-NORMAL", dateTime(2026, 4, 21, 9, 0),
                "SPEC-X", "20.0", "MAT-NORMAL");
        context.getMachineScheduleMap().put(specialSupportMachine.getMachineCode(), specialSupportMachine);
        context.getMachineScheduleMap().put(normalMachine.getMachineCode(), normalMachine);

        List<MachineScheduleDTO> candidates = strategy.matchMachines(context, sku("MAT-1", "SPEC-A", "22.5"));

        assertEquals(2, candidates.size());
        assertEquals("M-NORMAL", candidates.get(0).getMachineCode(),
                "非特殊材料应优先使用普通机台，普通机台不足时才使用特殊支持机台");
    }

    @Test
    void matchMachines_shouldFilterSpecialMaterialByMachineSupportCategory() {
        DefaultMachineMatchStrategy strategy = new DefaultMachineMatchStrategy();
        LhScheduleContext context = buildContext();
        context.getSpecialMaterialCategoryByMaterialCode().put("MAT-195", categorySet("01"));
        context.getSpecialMaterialCategoryByMaterialCode().put("MAT-225", categorySet("02"));
        context.getSpecialMaterialCategoryByMaterialCode().put("MAT-CHIP", categorySet("03"));

        MachineScheduleDTO normalMachine = machine("M-NORMAL", dateTime(2026, 4, 21, 8, 0),
                "SPEC-A", "22.5", "MAT-NORMAL");
        MachineScheduleDTO support195Machine = machine("M-195", dateTime(2026, 4, 21, 8, 0),
                "SPEC-A", "22.5", "MAT-195");
        support195Machine.setSupport195WideBase("1");
        MachineScheduleDTO support225Machine = machine("M-225", dateTime(2026, 4, 21, 8, 0),
                "SPEC-A", "22.5", "MAT-225");
        support225Machine.setSupport225WideBase("1");
        MachineScheduleDTO supportChipMachine = machine("M-CHIP", dateTime(2026, 4, 21, 8, 0),
                "SPEC-A", "22.5", "MAT-CHIP");
        supportChipMachine.setSupportChipTire("1");
        context.getMachineScheduleMap().put(normalMachine.getMachineCode(), normalMachine);
        context.getMachineScheduleMap().put(support195Machine.getMachineCode(), support195Machine);
        context.getMachineScheduleMap().put(support225Machine.getMachineCode(), support225Machine);
        context.getMachineScheduleMap().put(supportChipMachine.getMachineCode(), supportChipMachine);

        assertEquals("M-195", strategy.matchMachines(context, sku("MAT-195", "SPEC-A", "22.5")).get(0).getMachineCode());
        assertEquals("M-225", strategy.matchMachines(context, sku("MAT-225", "SPEC-A", "22.5")).get(0).getMachineCode());
        assertEquals("M-CHIP", strategy.matchMachines(context, sku("MAT-CHIP", "SPEC-A", "22.5")).get(0).getMachineCode());
    }

    @Test
    void matchMachines_shouldRequireAllSpecialMaterialCategories() {
        DefaultMachineMatchStrategy strategy = new DefaultMachineMatchStrategy();
        LhScheduleContext context = buildContext();
        context.getSpecialMaterialCategoryByMaterialCode().put("MAT-BOTH", categorySet("01", "03"));

        MachineScheduleDTO support195OnlyMachine = machine("M-195", dateTime(2026, 4, 21, 8, 0),
                "SPEC-A", "22.5", "MAT-195");
        support195OnlyMachine.setSupport195WideBase("1");
        MachineScheduleDTO supportChipOnlyMachine = machine("M-CHIP", dateTime(2026, 4, 21, 8, 0),
                "SPEC-A", "22.5", "MAT-CHIP");
        supportChipOnlyMachine.setSupportChipTire("1");
        MachineScheduleDTO supportBothMachine = machine("M-BOTH", dateTime(2026, 4, 21, 8, 0),
                "SPEC-A", "22.5", "MAT-BOTH");
        supportBothMachine.setSupport195WideBase("1");
        supportBothMachine.setSupportChipTire("1");
        context.getMachineScheduleMap().put(support195OnlyMachine.getMachineCode(), support195OnlyMachine);
        context.getMachineScheduleMap().put(supportChipOnlyMachine.getMachineCode(), supportChipOnlyMachine);
        context.getMachineScheduleMap().put(supportBothMachine.getMachineCode(), supportBothMachine);

        List<MachineScheduleDTO> candidates = strategy.matchMachines(context, sku("MAT-BOTH", "SPEC-A", "22.5"));

        assertEquals(1, candidates.size(), "同一物料命中多个特殊分类时，机台必须同时满足全部分类支持能力");
        assertEquals("M-BOTH", candidates.get(0).getMachineCode());
    }

    @Test
    void matchMachines_shouldMatchMachineMouldSetByTrimmedShellStandard() {
        DefaultMachineMatchStrategy strategy = new DefaultMachineMatchStrategy();
        LhScheduleContext context = buildContext();

        MachineScheduleDTO machine = machine("M-SHELL", dateTime(2026, 4, 21, 8, 0),
                "SPEC-A", "22.5", "MAT-A");
        machine.setShellStandard(" H420 , H450 ");
        context.getMachineScheduleMap().put(machine.getMachineCode(), machine);
        context.getSkuMouldRelMap().put("MAT-1", Collections.singletonList(mouldRel("MOULD-1")));
        context.getModelInfoMap().put("MOULD-1", modelInfo("MOULD-1", "H450"));

        List<MachineScheduleDTO> candidates = strategy.matchMachines(context, sku("MAT-1", "SPEC-A", "22.5"));

        assertEquals(1, candidates.size());
        assertEquals("M-SHELL", candidates.get(0).getMachineCode());
    }

    @Test
    void matchMachines_shouldExcludeMachineWhenMouldSetConfiguredButSkuShellStandardMissing() {
        DefaultMachineMatchStrategy strategy = new DefaultMachineMatchStrategy();
        LhScheduleContext context = buildContext();

        MachineScheduleDTO machine = machine("M-SHELL", dateTime(2026, 4, 21, 8, 0),
                "SPEC-A", "22.5", "MAT-A");
        machine.setShellStandard("H450");
        context.getMachineScheduleMap().put(machine.getMachineCode(), machine);
        context.getSkuMouldRelMap().put("MAT-1", Collections.singletonList(mouldRel("MOULD-1")));
        context.getModelInfoMap().put("MOULD-1", modelInfo("MOULD-1", null));

        List<MachineScheduleDTO> candidates = strategy.matchMachines(context, sku("MAT-1", "SPEC-A", "22.5"));

        assertTrue(candidates.isEmpty(), "机台指定模套型号时，SKU缺少SHELL_STANDARD不应通过模套硬匹配");
    }

    @Test
    void matchMachines_shouldTreatUniversalMouldSetAsMatchAll() {
        DefaultMachineMatchStrategy strategy = new DefaultMachineMatchStrategy();
        LhScheduleContext context = buildContext();

        MachineScheduleDTO machine = machine("M-UNIVERSAL", dateTime(2026, 4, 21, 8, 0),
                "SPEC-A", "22.5", "MAT-A");
        machine.setShellStandard(" 通用 ");
        context.getMachineScheduleMap().put(machine.getMachineCode(), machine);
        context.getSkuMouldRelMap().put("MAT-1", Collections.singletonList(mouldRel("MOULD-1")));
        context.getModelInfoMap().put("MOULD-1", modelInfo("MOULD-1", null));

        List<MachineScheduleDTO> candidates = strategy.matchMachines(context, sku("MAT-1", "SPEC-A", "22.5"));

        assertEquals(1, candidates.size(), "机台模套型号为通用时，应等同空值并适配所有SKU");
        assertEquals("M-UNIVERSAL", candidates.get(0).getMachineCode());
    }

    @Test
    void matchMachines_shouldExcludeMachineWhenFutureLongStopOverlapsScheduleWindow() {
        DefaultMachineMatchStrategy strategy = new DefaultMachineMatchStrategy();
        LhScheduleContext context = buildContext();
        context.setScheduleConfig(new LhScheduleConfig(Collections.singletonMap(
                LhScheduleParamConstant.MACHINE_STOP_TIMEOUT_HOURS, "24")));

        MachineScheduleDTO futureStopMachine = machine("M-FUTURE-STOP", dateTime(2026, 4, 21, 8, 0),
                "SPEC-A", "22.5", "MAT-A");
        MachineScheduleDTO availableMachine = machine("M-OK", dateTime(2026, 4, 21, 8, 30),
                "SPEC-A", "22.5", "MAT-B");
        context.getMachineScheduleMap().put(futureStopMachine.getMachineCode(), futureStopMachine);
        context.getMachineScheduleMap().put(availableMachine.getMachineCode(), availableMachine);
        context.getDevicePlanShutList().add(devicePlanShut("M-FUTURE-STOP",
                dateTime(2026, 4, 21, 9, 0), dateTime(2026, 4, 22, 12, 0)));

        List<MachineScheduleDTO> candidates = strategy.matchMachines(context, sku("MAT-1", "SPEC-A", "22.5"));

        assertEquals(1, candidates.size(), "后续长停机与排程窗口重叠时，应切换到其他可用机台");
        assertEquals("M-OK", candidates.get(0).getMachineCode());
    }

    private MdmSkuMouldRel mouldRel(String mouldCode) {
        MdmSkuMouldRel rel = new MdmSkuMouldRel();
        rel.setMouldCode(mouldCode);
        return rel;
    }

    private MdmModelInfo modelInfo(String mouldCode, String shellStandard) {
        MdmModelInfo modelInfo = new MdmModelInfo();
        modelInfo.setMouldCode(mouldCode);
        modelInfo.setShellStandard(shellStandard);
        return modelInfo;
    }

    private LhSpecifyMachine specifyMachine(String materialCode, String machineCode, String jobType) {
        LhSpecifyMachine specifyMachine = new LhSpecifyMachine();
        specifyMachine.setSpecCode(materialCode);
        specifyMachine.setMachineCode(machineCode);
        specifyMachine.setJobType(jobType);
        return specifyMachine;
    }

    private LhScheduleContext buildContext() {
        LhScheduleContext context = new LhScheduleContext();
        context.setMachineScheduleMap(new LinkedHashMap<String, MachineScheduleDTO>());
        context.setMachineAssignmentMap(new LinkedHashMap<String, List<com.zlt.aps.lh.api.domain.entity.LhScheduleResult>>());
        context.setMaterialInfoMap(new HashMap<String, MdmMaterialInfo>());
        context.setDevicePlanShutList(new java.util.ArrayList<MdmDevicePlanShut>());
        context.setScheduleDate(dateTime(2026, 4, 21, 7, 0));
        context.setScheduleTargetDate(dateTime(2026, 4, 21, 7, 0));
        context.setScheduleWindowShifts(LhScheduleTimeUtil.buildDefaultScheduleShifts(context, context.getScheduleDate()));
        return context;
    }

    private LhScheduleContext buildTraceContext() {
        LhScheduleContext context = buildContext();
        context.setFactoryCode("116");
        context.setBatchNo("TRACE-BATCH");
        context.setScheduleConfig(new LhScheduleConfig(Collections.singletonMap(
                LhScheduleParamConstant.ENABLE_PRIORITY_TRACE_LOG, "1")));
        return context;
    }

    private void enableSpecifyMachineRule(LhScheduleContext context) {
        Map<String, String> paramMap = new HashMap<>(1);
        paramMap.put(LhScheduleParamConstant.ENABLE_SPECIFY_MACHINE_RULE, "1");
        context.setScheduleConfig(new LhScheduleConfig(paramMap));
    }

    private void enableSingleControlMachines(LhScheduleContext context) {
        Map<String, String> paramMap = new HashMap<>(1);
        paramMap.put(LhScheduleParamConstant.SINGLE_CONTROL_MACHINE_CODES, "K1501,K1502");
        context.setScheduleConfig(new LhScheduleConfig(paramMap));
    }

    private void enableSingleControlAndSpecifyMachineRule(LhScheduleContext context) {
        Map<String, String> paramMap = new HashMap<>(2);
        paramMap.put(LhScheduleParamConstant.SINGLE_CONTROL_MACHINE_CODES, "K1501,K1502");
        paramMap.put(LhScheduleParamConstant.ENABLE_SPECIFY_MACHINE_RULE, "1");
        context.setScheduleConfig(new LhScheduleConfig(paramMap));
    }

    private MachineScheduleDTO machine(String machineCode, Date estimatedEndTime, String previousSpecCode,
                                       String previousProSize, String previousMaterialCode) {
        MachineScheduleDTO machine = new MachineScheduleDTO();
        machine.setMachineCode(machineCode);
        machine.setStatus("1");
        machine.setMaxMoldNum(1);
        machine.setMachineOrder(1);
        machine.setEstimatedEndTime(estimatedEndTime);
        machine.setPreviousSpecCode(previousSpecCode);
        machine.setPreviousProSize(previousProSize);
        machine.setPreviousMaterialCode(previousMaterialCode);
        return machine;
    }

    private SkuScheduleDTO sku(String materialCode, String specCode, String proSize) {
        SkuScheduleDTO sku = new SkuScheduleDTO();
        sku.setMaterialCode(materialCode);
        sku.setSpecCode(specCode);
        sku.setProSize(proSize);
        return sku;
    }

    private Set<String> categorySet(String... categories) {
        return new LinkedHashSet<String>(Arrays.asList(categories));
    }

    private void material(LhScheduleContext context, String materialCode, String embryoDesc) {
        MdmMaterialInfo materialInfo = new MdmMaterialInfo();
        materialInfo.setMaterialCode(materialCode);
        materialInfo.setEmbryoDesc(embryoDesc);
        context.getMaterialInfoMap().put(materialCode, materialInfo);
    }

    private MdmDevicePlanShut devicePlanShut(String machineCode, Date beginDate, Date endDate) {
        MdmDevicePlanShut devicePlanShut = new MdmDevicePlanShut();
        devicePlanShut.setMachineCode(machineCode);
        devicePlanShut.setBeginDate(beginDate);
        devicePlanShut.setEndDate(endDate);
        return devicePlanShut;
    }

    private Date dateTime(int year, int month, int day, int hour, int minute) {
        java.util.Calendar calendar = java.util.Calendar.getInstance();
        calendar.clear();
        calendar.set(java.util.Calendar.YEAR, year);
        calendar.set(java.util.Calendar.MONTH, month - 1);
        calendar.set(java.util.Calendar.DAY_OF_MONTH, day);
        calendar.set(java.util.Calendar.HOUR_OF_DAY, hour);
        calendar.set(java.util.Calendar.MINUTE, minute);
        return calendar.getTime();
    }
}
