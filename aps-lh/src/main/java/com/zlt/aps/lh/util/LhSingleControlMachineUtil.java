package com.zlt.aps.lh.util;

import com.zlt.aps.lh.api.constant.LhScheduleConstant;
import com.zlt.aps.lh.api.constant.LhScheduleParamConstant;
import com.zlt.aps.lh.context.LhScheduleContext;
import org.apache.commons.lang3.StringUtils;

import java.util.Locale;

/**
 * 单模机台工具类。
 * <p>机台编码以 L/R 结尾的视为单模拆分机台（左右侧单独控制），
 * 用于校验侧别兼容性及试制SKU优先策略。</p>
 */
public final class LhSingleControlMachineUtil {

    /** 单控机台配置分隔符 */
    private static final String SINGLE_CONTROL_MACHINE_SEPARATOR_REGEX = "[,，]";
    /** 硫化运行态机台编码前缀 */
    private static final String LH_MACHINE_CODE_PREFIX = "K";

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
     * 判断是否为当前工厂配置生效的单控运行态机台。
     *
     * @param context 排程上下文
     * @param machineCode 机台编码
     * @return true-单控机台
     */
    public static boolean isConfiguredSingleControlMachine(LhScheduleContext context, String machineCode) {
        if (context == null || !isSingleMouldMachine(machineCode)) {
            return false;
        }
        String configuredMachineCodes = context.getParamValue(
                LhScheduleParamConstant.SINGLE_CONTROL_MACHINE_CODES, StringUtils.EMPTY);
        if (StringUtils.isEmpty(configuredMachineCodes)) {
            return isSplitRuntimeLhMachineCode(machineCode);
        }
        String baseMachineCode = machineCode.substring(0, machineCode.length() - 1);
        String[] configuredArray = configuredMachineCodes.split(SINGLE_CONTROL_MACHINE_SEPARATOR_REGEX);
        for (String configuredCode : configuredArray) {
            if (StringUtils.isEmpty(configuredCode)) {
                continue;
            }
            String trimmedCode = configuredCode.trim();
            if (StringUtils.equalsIgnoreCase(trimmedCode, baseMachineCode)
                    || StringUtils.equalsIgnoreCase(trimmedCode, machineCode)) {
                return true;
            }
        }
        return false;
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

    /**
     * 判断是否为已拆分的硫化运行态机台编码。
     *
     * @param machineCode 机台编码
     * @return true-硫化运行态拆分机台
     */
    private static boolean isSplitRuntimeLhMachineCode(String machineCode) {
        if (StringUtils.isEmpty(machineCode)) {
            return false;
        }
        String normalized = machineCode.trim().toUpperCase(Locale.ROOT);
        if (!normalized.startsWith(LH_MACHINE_CODE_PREFIX) || normalized.length() <= 2) {
            return false;
        }
        for (int i = 1; i < normalized.length() - 1; i++) {
            if (!Character.isDigit(normalized.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
