package com.zlt.aps.lh.util;

import com.zlt.aps.lh.api.enums.TrialStatusEnum;
import com.zlt.aps.mdm.api.domain.entity.MdmSkuConstructionRef;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * SKU与示方书关系降级匹配工具测试。
 *
 * @author APS
 */
public class SkuConstructionRefResolverUtilTest {

    private static final String MATERIAL_CODE = "3302001513";

    // ========== resolveCuringRecipeRef 测试 ==========

    @Test
    void resolveCuringRecipeRef_formalExactMatch_returnsDirect() {
        Map<String, MdmSkuConstructionRef> map = buildMap(MATERIAL_CODE, TrialStatusEnum.FORMAL.getCode(), "LH-S");

        MdmSkuConstructionRef ref = SkuConstructionRefResolverUtil.resolveCuringRecipeRef(
                MATERIAL_CODE, TrialStatusEnum.FORMAL.getCode(), map);

        assertNotNull(ref);
        assertEquals("LH-S", ref.getLhNo());
    }

    @Test
    void resolveCuringRecipeRef_formalFallbackToMassTrial() {
        // map中无S有T，查S应降级到T
        Map<String, MdmSkuConstructionRef> map = buildMap(MATERIAL_CODE, TrialStatusEnum.MASS_TRIAL.getCode(), "LH-T");

        MdmSkuConstructionRef ref = SkuConstructionRefResolverUtil.resolveCuringRecipeRef(
                MATERIAL_CODE, TrialStatusEnum.FORMAL.getCode(), map);

        assertNotNull(ref);
        assertEquals("LH-T", ref.getLhNo());
    }

    @Test
    void resolveCuringRecipeRef_formalFallbackToTrial() {
        // map中无S/T有X，查S应降级到X
        Map<String, MdmSkuConstructionRef> map = buildMap(MATERIAL_CODE, TrialStatusEnum.TRIAL.getCode(), "LH-X");

        MdmSkuConstructionRef ref = SkuConstructionRefResolverUtil.resolveCuringRecipeRef(
                MATERIAL_CODE, TrialStatusEnum.FORMAL.getCode(), map);

        assertNotNull(ref);
        assertEquals("LH-X", ref.getLhNo());
    }

    @Test
    void resolveCuringRecipeRef_formalNoMatch() {
        // map中无任何记录，查S应返回null
        Map<String, MdmSkuConstructionRef> map = new HashMap<>(2);

        MdmSkuConstructionRef ref = SkuConstructionRefResolverUtil.resolveCuringRecipeRef(
                MATERIAL_CODE, TrialStatusEnum.FORMAL.getCode(), map);

        assertNull(ref);
    }

    @Test
    void resolveCuringRecipeRef_formalLhNoEmpty_fallbackToMassTrial() {
        // map有S但lhNo为空，有T且lhNo非空，查S应降级到T
        Map<String, MdmSkuConstructionRef> map = new HashMap<>(4);
        MdmSkuConstructionRef sRef = buildRef(MATERIAL_CODE, TrialStatusEnum.FORMAL.getCode(), "");
        MdmSkuConstructionRef tRef = buildRef(MATERIAL_CODE, TrialStatusEnum.MASS_TRIAL.getCode(), "LH-T");
        map.put(MATERIAL_CODE + "::" + TrialStatusEnum.FORMAL.getCode(), sRef);
        map.put(MATERIAL_CODE + "::" + TrialStatusEnum.MASS_TRIAL.getCode(), tRef);

        MdmSkuConstructionRef ref = SkuConstructionRefResolverUtil.resolveCuringRecipeRef(
                MATERIAL_CODE, TrialStatusEnum.FORMAL.getCode(), map);

        assertNotNull(ref);
        assertEquals("LH-T", ref.getLhNo());
    }

    @Test
    void resolveCuringRecipeRef_massTrialExactMatch() {
        Map<String, MdmSkuConstructionRef> map = buildMap(MATERIAL_CODE, TrialStatusEnum.MASS_TRIAL.getCode(), "LH-T");

        MdmSkuConstructionRef ref = SkuConstructionRefResolverUtil.resolveCuringRecipeRef(
                MATERIAL_CODE, TrialStatusEnum.MASS_TRIAL.getCode(), map);

        assertNotNull(ref);
        assertEquals("LH-T", ref.getLhNo());
    }

    @Test
    void resolveCuringRecipeRef_massTrialFallbackToTrial() {
        // map中无T有X，查T应降级到X
        Map<String, MdmSkuConstructionRef> map = buildMap(MATERIAL_CODE, TrialStatusEnum.TRIAL.getCode(), "LH-X");

        MdmSkuConstructionRef ref = SkuConstructionRefResolverUtil.resolveCuringRecipeRef(
                MATERIAL_CODE, TrialStatusEnum.MASS_TRIAL.getCode(), map);

        assertNotNull(ref);
        assertEquals("LH-X", ref.getLhNo());
    }

    @Test
    void resolveCuringRecipeRef_massTrialNoFallbackToFormal() {
        // map中仅有S，查T应返回null（不允许反向匹配正规）
        Map<String, MdmSkuConstructionRef> map = buildMap(MATERIAL_CODE, TrialStatusEnum.FORMAL.getCode(), "LH-S");

        MdmSkuConstructionRef ref = SkuConstructionRefResolverUtil.resolveCuringRecipeRef(
                MATERIAL_CODE, TrialStatusEnum.MASS_TRIAL.getCode(), map);

        assertNull(ref);
    }

    @Test
    void resolveCuringRecipeRef_trialExactMatch() {
        Map<String, MdmSkuConstructionRef> map = buildMap(MATERIAL_CODE, TrialStatusEnum.TRIAL.getCode(), "LH-X");

        MdmSkuConstructionRef ref = SkuConstructionRefResolverUtil.resolveCuringRecipeRef(
                MATERIAL_CODE, TrialStatusEnum.TRIAL.getCode(), map);

        assertNotNull(ref);
        assertEquals("LH-X", ref.getLhNo());
    }

    @Test
    void resolveCuringRecipeRef_trialNoFallback() {
        // map中有T/S，查X应返回null（试制不降级、不反向）
        Map<String, MdmSkuConstructionRef> map = new HashMap<>(4);
        map.put(MATERIAL_CODE + "::" + TrialStatusEnum.FORMAL.getCode(),
                buildRef(MATERIAL_CODE, TrialStatusEnum.FORMAL.getCode(), "LH-S"));
        map.put(MATERIAL_CODE + "::" + TrialStatusEnum.MASS_TRIAL.getCode(),
                buildRef(MATERIAL_CODE, TrialStatusEnum.MASS_TRIAL.getCode(), "LH-T"));

        MdmSkuConstructionRef ref = SkuConstructionRefResolverUtil.resolveCuringRecipeRef(
                MATERIAL_CODE, TrialStatusEnum.TRIAL.getCode(), map);

        assertNull(ref);
    }

    @Test
    void resolveCuringRecipeRef_nullMaterialCode() {
        Map<String, MdmSkuConstructionRef> map = buildMap(MATERIAL_CODE, TrialStatusEnum.FORMAL.getCode(), "LH-S");

        MdmSkuConstructionRef ref = SkuConstructionRefResolverUtil.resolveCuringRecipeRef(
                null, TrialStatusEnum.FORMAL.getCode(), map);

        assertNull(ref);
    }

    @Test
    void resolveCuringRecipeRef_nullProductStatus() {
        Map<String, MdmSkuConstructionRef> map = buildMap(MATERIAL_CODE, TrialStatusEnum.FORMAL.getCode(), "LH-S");

        MdmSkuConstructionRef ref = SkuConstructionRefResolverUtil.resolveCuringRecipeRef(
                MATERIAL_CODE, null, map);

        assertNull(ref);
    }

    @Test
    void resolveCuringRecipeRef_emptyMap() {
        MdmSkuConstructionRef ref = SkuConstructionRefResolverUtil.resolveCuringRecipeRef(
                MATERIAL_CODE, TrialStatusEnum.FORMAL.getCode(), Collections.emptyMap());

        assertNull(ref);
    }

    @Test
    void resolveCuringRecipeRef_unknownStatus() {
        // 未知状态，无降级链，应返回null
        Map<String, MdmSkuConstructionRef> map = buildMap(MATERIAL_CODE, "Z", "LH-Z");

        MdmSkuConstructionRef ref = SkuConstructionRefResolverUtil.resolveCuringRecipeRef(
                MATERIAL_CODE, "Z", map);

        // 未知状态精确匹配仍命中
        assertNotNull(ref);
        assertEquals("LH-Z", ref.getLhNo());
    }

    @Test
    void resolveCuringRecipeRef_unknownStatusNoExact() {
        // 未知状态且无精确匹配，无降级链，应返回null
        Map<String, MdmSkuConstructionRef> map = buildMap(MATERIAL_CODE, TrialStatusEnum.FORMAL.getCode(), "LH-S");

        MdmSkuConstructionRef ref = SkuConstructionRefResolverUtil.resolveCuringRecipeRef(
                MATERIAL_CODE, "Z", map);

        assertNull(ref);
    }

    // ========== resolveProductStatusDesc 测试 ==========

    @Test
    void resolveProductStatusDesc_formal() {
        assertEquals("正规", SkuConstructionRefResolverUtil.resolveProductStatusDesc(TrialStatusEnum.FORMAL.getCode()));
    }

    @Test
    void resolveProductStatusDesc_massTrial() {
        assertEquals("量试", SkuConstructionRefResolverUtil.resolveProductStatusDesc(TrialStatusEnum.MASS_TRIAL.getCode()));
    }

    @Test
    void resolveProductStatusDesc_trial() {
        assertEquals("试制", SkuConstructionRefResolverUtil.resolveProductStatusDesc(TrialStatusEnum.TRIAL.getCode()));
    }

    @Test
    void resolveProductStatusDesc_unknown() {
        assertEquals("Z", SkuConstructionRefResolverUtil.resolveProductStatusDesc("Z"));
    }

    // ========== 辅助方法 ==========

    private Map<String, MdmSkuConstructionRef> buildMap(String materialCode, String status, String lhNo) {
        Map<String, MdmSkuConstructionRef> map = new HashMap<>(4);
        MdmSkuConstructionRef ref = buildRef(materialCode, status, lhNo);
        map.put(materialCode + "::" + status, ref);
        return map;
    }

    private MdmSkuConstructionRef buildRef(String materialCode, String status, String lhNo) {
        MdmSkuConstructionRef ref = new MdmSkuConstructionRef();
        ref.setMaterialCode(materialCode);
        ref.setTrialStatus(status);
        ref.setLhNo(lhNo);
        ref.setLhType("TYPE-" + status);
        return ref;
    }
}
