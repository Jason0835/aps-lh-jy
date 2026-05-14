package com.zlt.aps.lh.util;

import com.zlt.aps.lh.api.constant.LhScheduleConstant;
import org.apache.commons.lang3.StringUtils;

import java.util.Locale;

/**
 * 单模机台工具类。
 * <p>机台编码以 L/R 结尾的视为单模拆分机台（左右侧单独控制），
 * 用于校验侧别兼容性及试制SKU优先策略。</p>
 */
public final class LhSingleControlMachineUtil {

    private LhSingleControlMachineUtil() {
    }

    /**
     * 判断是否为单模机台（机台编码以 L 或 R 结尾）。
     *
     * @param machineCode 机台编码
     * @return true-单模机台
     */
    public static boolean isSingleMouldMachine(String machineCode) {
        if (StringUtils.isEmpty(machineCode)) {
            return false;
        }
        String normalized = machineCode.trim().toUpperCase(Locale.ROOT);
        return normalized.endsWith(LhScheduleConstant.LEFT_MOULD)
                || normalized.endsWith(LhScheduleConstant.RIGHT_MOULD);
    }

    /**
     * 判断左右模标识是否与运行态机台侧别兼容。
     *
     * @param machineCode 运行态机台编码
     * @param leftRightMould 左右模标识
     * @return true-兼容
     */
    public static boolean isLeftRightCompatible(String machineCode, String leftRightMould) {
        String splitSide = resolveSplitSide(machineCode);
        if (StringUtils.isEmpty(splitSide)
                || StringUtils.isEmpty(leftRightMould)
                || StringUtils.equalsIgnoreCase(leftRightMould, LhScheduleConstant.LEFT_RIGHT_MOULD)) {
            return true;
        }
        return StringUtils.equalsIgnoreCase(splitSide, leftRightMould);
    }

    /**
     * 解析拆分机台侧别。
     *
     * @param machineCode 机台编码
     * @return L/R；非拆分机台返回null
     */
    public static String resolveSplitSide(String machineCode) {
        if (StringUtils.isEmpty(machineCode)) {
            return null;
        }
        String normalizedMachineCode = machineCode.trim().toUpperCase(Locale.ROOT);
        if (normalizedMachineCode.endsWith(LhScheduleConstant.LEFT_MOULD)) {
            return LhScheduleConstant.LEFT_MOULD;
        }
        if (normalizedMachineCode.endsWith(LhScheduleConstant.RIGHT_MOULD)) {
            return LhScheduleConstant.RIGHT_MOULD;
        }
        return null;
    }
}
