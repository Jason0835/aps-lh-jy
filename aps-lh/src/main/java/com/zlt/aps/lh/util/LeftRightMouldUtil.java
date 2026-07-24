package com.zlt.aps.lh.util;

import com.zlt.aps.lh.api.constant.LhScheduleConstant;
import org.apache.commons.lang3.StringUtils;

/**
 * 左右模字段计算工具。
 *
 * @author APS
 */
public final class LeftRightMouldUtil {

    private LeftRightMouldUtil() {
    }

    /**
     * 解析左右模标识。
     * <p>规则：单模机台（编码以 L/R 结尾）按机台后缀取 L/R，优先于已有值；
     * 双模机台（编码不以 L/R 结尾）保留已有值，无值时默认 LR。</p>
     *
     * @param currentValue 当前左右模值
     * @param machineCode  机台编码
     * @return 左右模标识（L/R/LR）
     */
    public static String resolveLeftRightMould(String currentValue, String machineCode) {
        // 单模机台左右模由机台后缀唯一确定，必须优先于已有值，
        // 防止单控整机配对侧复制主侧结果时把对侧的 L/R 带过来。
        String splitSide = LhSingleControlMachineUtil.resolveSplitSide(machineCode);
        if (StringUtils.isNotEmpty(splitSide)) {
            return splitSide;
        }
        // 双模机台：保留已有值（历史单边示方等场景），无值时默认 LR。
        if (StringUtils.isNotEmpty(currentValue)) {
            return currentValue;
        }
        return LhScheduleConstant.LEFT_RIGHT_MOULD;
    }

    /**
     * 清洗计划场景下解析左右模标识。
     * <p>规则：双模机台赋值 LR，单模机台按机台编码后缀赋值 L 或 R，忽略清洗计划原始值。</p>
     *
     * @param machineCode 机台编码
     * @return 左右模标识（L/R/LR）
     */
    public static String resolveCleaningLeftRightMould(String machineCode) {
        if (StringUtils.isEmpty(machineCode)) {
            return LhScheduleConstant.LEFT_RIGHT_MOULD;
        }
        // 单模机台按机台编码后缀确定左/右模
        if (LhSingleControlMachineUtil.isSingleMouldMachine(machineCode)) {
            return LhSingleControlMachineUtil.resolveSplitSide(machineCode);
        }
        // 双模机台统一赋值 LR
        return LhScheduleConstant.LEFT_RIGHT_MOULD;
    }
}
