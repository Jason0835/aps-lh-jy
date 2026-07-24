package com.zlt.aps.lh.regression;

import com.zlt.aps.lh.util.LeftRightMouldUtil;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 左右模字段回归测试。
 */
class LeftRightMouldUtilRegressionTest {

    @Test
    void resolveLeftRightMould_shouldDefaultToLrWhenMachineCodeIsNormal() {
        assertEquals("LR", LeftRightMouldUtil.resolveLeftRightMould(null, "K1501"));
    }

    @Test
    void resolveLeftRightMould_shouldResolveLWhenMachineCodeEndsWithL() {
        assertEquals("L", LeftRightMouldUtil.resolveLeftRightMould(null, "K1501L"));
    }

    @Test
    void resolveLeftRightMould_shouldResolveRWhenMachineCodeEndsWithR() {
        assertEquals("R", LeftRightMouldUtil.resolveLeftRightMould(null, "K1502R"));
    }

    @Test
    void resolveLeftRightMould_shouldOverrideCurrentValueBySuffixForSingleMouldMachine() {
        // 单模机台编码以 L 结尾，已有值 LR 必须被机台后缀 L 覆盖
        assertEquals("L", LeftRightMouldUtil.resolveLeftRightMould("LR", "K1501L"));
    }

    @Test
    void resolveLeftRightMould_shouldOverrideCopiedOppositeSideForSingleMouldMachine() {
        // 单控整机配对侧从主侧复制到 L，右模机台必须按后缀矫正为 R
        assertEquals("R", LeftRightMouldUtil.resolveLeftRightMould("L", "K1501R"));
    }

    @Test
    void resolveLeftRightMould_shouldKeepCurrentValueForDualMouldMachine() {
        // 双模机台（编码不以 L/R 结尾）保留已有值 R
        assertEquals("R", LeftRightMouldUtil.resolveLeftRightMould("R", "K1501"));
    }

    @Test
    void resolveCleaningLeftRightMould_shouldReturnLrForDualMouldMachine() {
        // 双模机台（编码不以 L/R 结尾）赋值 LR
        assertEquals("LR", LeftRightMouldUtil.resolveCleaningLeftRightMould("K1501"));
    }

    @Test
    void resolveCleaningLeftRightMould_shouldReturnLForSingleMouldMachineLeft() {
        // 单模机台编码以 L 结尾赋值 L
        assertEquals("L", LeftRightMouldUtil.resolveCleaningLeftRightMould("K1501L"));
    }

    @Test
    void resolveCleaningLeftRightMould_shouldReturnRForSingleMouldMachineRight() {
        // 单模机台编码以 R 结尾赋值 R
        assertEquals("R", LeftRightMouldUtil.resolveCleaningLeftRightMould("K1502R"));
    }

    @Test
    void resolveCleaningLeftRightMould_shouldReturnLrForNullMachineCode() {
        // 机台编码为空默认 LR
        assertEquals("LR", LeftRightMouldUtil.resolveCleaningLeftRightMould(null));
    }
}
