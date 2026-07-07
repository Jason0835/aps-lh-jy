package com.zlt.aps.lh.util;

import com.zlt.aps.lh.api.constant.LhScheduleConstant;
import com.zlt.aps.lh.api.constant.LhScheduleParamConstant;
import com.zlt.aps.lh.api.domain.dto.MachineScheduleDTO;
import com.zlt.aps.lh.api.domain.dto.SkuScheduleDTO;
import com.zlt.aps.lh.api.enums.ConstructionStageEnum;
import com.zlt.aps.lh.context.LhScheduleContext;
import org.apache.commons.lang3.StringUtils;
import org.springframework.util.CollectionUtils;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;

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
        if (Objects.isNull(context) || !isSingleMouldMachine(machineCode)) {
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
     * 解析单控运行态机台所属的物理机台编码。
     * <p>业务示例：K1501L、K1501R 都归属物理机台 K1501；非 L/R 拆分机台返回自身。
     * 正规 SKU 使用单控机台时必须以该物理机台为整机粒度判断左右侧是否齐备。</p>
     *
     * @param machineCode 运行态机台编码
     * @return 物理机台编码；入参为空时返回 null
     */
    public static String resolvePhysicalMachineCode(String machineCode) {
        if (StringUtils.isEmpty(machineCode)) {
            return null;
        }
        String normalizedMachineCode = machineCode.trim().toUpperCase(Locale.ROOT);
        if (!isSingleMouldMachine(normalizedMachineCode)) {
            return normalizedMachineCode;
        }
        return normalizedMachineCode.substring(0, normalizedMachineCode.length() - 1);
    }

    /**
     * 解析单控机台配对侧运行态编码。
     * <p>业务示例：K1501L 的配对侧为 K1501R，K1501R 的配对侧为 K1501L。
     * 该方法只负责编码推导，是否配置生效和是否存在运行态机台由调用方继续校验。</p>
     *
     * @param machineCode 运行态机台编码
     * @return 配对侧机台编码；非 L/R 运行态返回 null
     */
    public static String resolvePairMachineCode(String machineCode) {
        String side = resolveSplitSide(machineCode);
        String physicalMachineCode = resolvePhysicalMachineCode(machineCode);
        if (StringUtils.isEmpty(side) || StringUtils.isEmpty(physicalMachineCode)) {
            return null;
        }
        return physicalMachineCode + (StringUtils.equals(side, LhScheduleConstant.LEFT_MOULD)
                ? LhScheduleConstant.RIGHT_MOULD : LhScheduleConstant.LEFT_MOULD);
    }

    /**
     * 解析单控物理机台左侧运行态编码。
     * <p>正规 SKU 整机候选统一使用左侧作为代表机台，避免候选集合中 L/R 两边重复参与排序和排产。</p>
     *
     * @param machineCode 任一侧运行态机台编码
     * @return 左侧运行态编码；无法解析物理机台时返回 null
     */
    public static String resolveLeftMachineCode(String machineCode) {
        String physicalMachineCode = resolvePhysicalMachineCode(machineCode);
        return StringUtils.isEmpty(physicalMachineCode) ? null : physicalMachineCode + LhScheduleConstant.LEFT_MOULD;
    }

    /**
     * 解析单控物理机台右侧运行态编码。
     *
     * @param machineCode 任一侧运行态机台编码
     * @return 右侧运行态编码；无法解析物理机台时返回 null
     */
    public static String resolveRightMachineCode(String machineCode) {
        String physicalMachineCode = resolvePhysicalMachineCode(machineCode);
        return StringUtils.isEmpty(physicalMachineCode) ? null : physicalMachineCode + LhScheduleConstant.RIGHT_MOULD;
    }

    /**
     * 从上下文运行态机台中查找配对侧。
     * <p>正规 SKU 需要左右侧同时存在并通过既有硬约束；缺少配对侧时不能把单边当作可用整机。</p>
     *
     * @param context 排程上下文
     * @param machineCode 当前侧机台编码
     * @return 配对侧机台；不存在时返回 null
     */
    public static MachineScheduleDTO resolvePairMachine(LhScheduleContext context, String machineCode) {
        if (Objects.isNull(context) || CollectionUtils.isEmpty(context.getMachineScheduleMap())) {
            return null;
        }
        String pairMachineCode = resolvePairMachineCode(machineCode);
        return StringUtils.isEmpty(pairMachineCode) ? null : context.getMachineScheduleMap().get(pairMachineCode);
    }

    /**
     * 判断是否为单控物理机台左侧运行态。
     *
     * @param machineCode 机台编码
     * @return true-左侧运行态
     */
    public static boolean isLeftSide(String machineCode) {
        return StringUtils.equals(resolveSplitSide(machineCode), LhScheduleConstant.LEFT_MOULD);
    }

    /**
     * 判断 SKU 是否按单控单边粒度使用机台。
     * <p>试制、量试和小批量 SKU 可以独立占用 K1501L/K1501R 等单侧机台，
     * 左右侧允许排不同 SKU，也允许排相同 SKU。</p>
     *
     * @param sku SKU排程DTO
     * @return true-按单边粒度排产
     */
    public static boolean isSingleSideGranularitySku(SkuScheduleDTO sku) {
        if (Objects.isNull(sku)) {
            return false;
        }
        return StringUtils.equals(ConstructionStageEnum.TRIAL.getCode(), sku.getConstructionStage())
                || StringUtils.equals(ConstructionStageEnum.MASS_TRIAL.getCode(), sku.getConstructionStage())
                || sku.isSmallBatchValidation();
    }

    /**
     * 判断 SKU 是否按单控整机粒度使用机台。
     * <p>非试制、非量试、非小批量的正规 SKU 使用单控机台时必须同时占用 L/R 两边。</p>
     *
     * @param sku SKU排程DTO
     * @return true-按整机粒度排产
     */
    public static boolean isWholeMachineGranularitySku(SkuScheduleDTO sku) {
        return Objects.nonNull(sku) && !isSingleSideGranularitySku(sku);
    }

    /**
     * 判断当前物理单控机台是否在运行态中同时具备 L/R 两边。
     *
     * @param context 排程上下文
     * @param machineCode 任一侧运行态机台编码
     * @return true-L/R 两侧都存在
     */
    public static boolean hasPairMachine(LhScheduleContext context, String machineCode) {
        if (Objects.isNull(context) || CollectionUtils.isEmpty(context.getMachineScheduleMap())) {
            return false;
        }
        String leftMachineCode = resolveLeftMachineCode(machineCode);
        String rightMachineCode = resolveRightMachineCode(machineCode);
        Map<String, MachineScheduleDTO> machineScheduleMap = context.getMachineScheduleMap();
        return StringUtils.isNotEmpty(leftMachineCode)
                && StringUtils.isNotEmpty(rightMachineCode)
                && machineScheduleMap.containsKey(leftMachineCode)
                && machineScheduleMap.containsKey(rightMachineCode);
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
