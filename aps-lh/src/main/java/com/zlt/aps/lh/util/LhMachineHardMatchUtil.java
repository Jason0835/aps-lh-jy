package com.zlt.aps.lh.util;

import com.zlt.aps.lh.api.domain.dto.MachineScheduleDTO;
import com.zlt.aps.lh.api.domain.dto.SpecialMaterialMatchResult;
import com.zlt.aps.lh.api.domain.dto.SkuScheduleDTO;
import com.zlt.aps.lh.api.enums.LhSpecialMaterialCategoryEnum;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.mdm.api.domain.entity.MdmModelInfo;
import com.zlt.aps.mdm.api.domain.entity.MdmSkuMouldRel;
import org.apache.commons.lang3.StringUtils;
import org.springframework.util.CollectionUtils;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * 硫化机台硬性匹配工具。
 *
 * @author APS
 */
public final class LhMachineHardMatchUtil {

    /** 是标记 */
    private static final String YES_FLAG = "1";
    /** 多值分隔符 */
    private static final String VALUE_SEPARATOR = ",";
    /** 通用模套型号（等同空值，适配所有） */
    private static final String UNIVERSAL_MOULD_SET_CODE = "通用";

    private LhMachineHardMatchUtil() {
    }

    /**
     * 判断机台是否满足SKU硬性匹配条件。
     *
     * @param context 排程上下文
     * @param sku SKU排程信息
     * @param machine 候选机台
     * @return true-满足，false-不满足
     */
    public static boolean isMachineHardMatched(LhScheduleContext context,
                                               SkuScheduleDTO sku,
                                               MachineScheduleDTO machine) {
        if (Objects.isNull(machine)) {
            return false;
        }
        BigDecimal skuInch = parseInch(Objects.isNull(sku) ? null : sku.getProSize());
        if (!isInchInRange(skuInch, machine.getDimensionMinimum(), machine.getDimensionMaximum())) {
            return false;
        }
        if (!isMouldSetMatched(context, sku, machine)) {
            return false;
        }
        SpecialMaterialMatchResult matchResult = LhSpecialMaterialUtil.resolveMatchResult(context, sku);
        return isSpecialMaterialSupported(matchResult, machine);
    }

    /**
     * 判断英寸是否落在机台范围内。
     *
     * @param skuInch SKU英寸
     * @param minInch 机台英寸下限
     * @param maxInch 机台英寸上限
     * @return true-匹配，false-不匹配
     */
    public static boolean isInchInRange(BigDecimal skuInch, BigDecimal minInch, BigDecimal maxInch) {
        if (Objects.isNull(skuInch) || Objects.isNull(minInch) || Objects.isNull(maxInch)) {
            return true;
        }
        return skuInch.compareTo(minInch) >= 0 && skuInch.compareTo(maxInch) <= 0;
    }

    /**
     * 判断SKU模套型号是否匹配机台配置。
     *
     * @param context 排程上下文
     * @param sku SKU排程信息
     * @param machine 候选机台
     * @return true-匹配，false-不匹配
     */
    public static boolean isMouldSetMatched(LhScheduleContext context,
                                            SkuScheduleDTO sku,
                                            MachineScheduleDTO machine) {
        String machineMouldSetCode = normalizeToken(Objects.isNull(machine) ? null : machine.getShellStandard());
        if (StringUtils.isEmpty(machineMouldSetCode)
                || StringUtils.equals(machineMouldSetCode, UNIVERSAL_MOULD_SET_CODE)) {
            return true;
        }
        Set<String> machineMouldSetSet = parseMachineMouldSetSet(machineMouldSetCode);
        if (machineMouldSetSet.contains(UNIVERSAL_MOULD_SET_CODE)) {
            return true;
        }
        if (CollectionUtils.isEmpty(machineMouldSetSet)) {
            return false;
        }
        Set<String> skuShellStandardSet = resolveSkuShellStandardSet(context, sku);
        if (CollectionUtils.isEmpty(skuShellStandardSet)) {
            return false;
        }
        for (String shellStandard : skuShellStandardSet) {
            if (machineMouldSetSet.contains(shellStandard)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断SKU模壳是否命中机台模套型号。
     * <p>该方法同时用于选机硬过滤与同模壳排序优先级：普通模具模壳必须命中机台模套型号，
     * 否则视为不匹配；模具到货关系存在模具可用日期，只代表新增模具可承接，不参与模壳降级；
     * 机台模套为空或通用时默认适配。</p>
     *
     * @param context 排程上下文
     * @param sku SKU排程信息
     * @param machine 候选机台
     * @return true-模壳匹配或无需降级，false-普通模具模壳未命中
     */
    public static boolean isMouldSetPriorityMatched(LhScheduleContext context,
                                                    SkuScheduleDTO sku,
                                                    MachineScheduleDTO machine) {
        String machineMouldSetCode = normalizeToken(Objects.isNull(machine) ? null : machine.getShellStandard());
        if (StringUtils.isEmpty(machineMouldSetCode)
                || StringUtils.equals(machineMouldSetCode, UNIVERSAL_MOULD_SET_CODE)) {
            return true;
        }
        Set<String> machineMouldSetSet = parseMachineMouldSetSet(machineMouldSetCode);
        if (machineMouldSetSet.contains(UNIVERSAL_MOULD_SET_CODE)) {
            return true;
        }
        if (CollectionUtils.isEmpty(machineMouldSetSet)) {
            return false;
        }
        SkuShellStandardMatchResult matchResult = resolveSkuShellStandardMatchResult(context, sku);
        if (CollectionUtils.isEmpty(matchResult.getShellStandardSet())) {
            // 只有模具到货关系时不参与模壳降级；普通模具缺少模壳仍按未命中处理。
            return matchResult.hasDeliveryMould() && !matchResult.hasOrdinaryMould();
        }
        for (String shellStandard : matchResult.getShellStandardSet()) {
            if (machineMouldSetSet.contains(shellStandard)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断特殊物料分类是否被机台支持。
     *
     * @param matchResult 特殊物料命中结果
     * @param machine 候选机台
     * @return true-支持，false-不支持
     */
    public static boolean isSpecialMaterialSupported(SpecialMaterialMatchResult matchResult,
                                                     MachineScheduleDTO machine) {
        if (Objects.isNull(matchResult) || !matchResult.isSpecial()) {
            return true;
        }
        if (Objects.isNull(machine) || CollectionUtils.isEmpty(matchResult.getCategories())) {
            return false;
        }
        for (String category : new LinkedHashSet<String>(matchResult.getCategories())) {
            LhSpecialMaterialCategoryEnum categoryEnum = LhSpecialMaterialCategoryEnum.getByCode(category);
            if (Objects.isNull(categoryEnum) || !isCategorySupported(categoryEnum, machine)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 判断机台是否支持指定特殊材料分类。
     *
     * @param categoryEnum 特殊材料分类
     * @param machine 候选机台
     * @return true-支持，false-不支持
     */
    public static boolean isCategorySupported(LhSpecialMaterialCategoryEnum categoryEnum,
                                              MachineScheduleDTO machine) {
        if (Objects.isNull(categoryEnum) || Objects.isNull(machine)) {
            return false;
        }
        if (LhSpecialMaterialCategoryEnum.WIDE_BASE_195 == categoryEnum) {
            return isSupport195WideBase(machine);
        }
        if (LhSpecialMaterialCategoryEnum.WIDE_BASE_225 == categoryEnum) {
            return isSupport225WideBase(machine);
        }
        return isSupportChipTire(machine);
    }

    /**
     * 判断机台是否为普通机台。
     *
     * @param machine 候选机台
     * @return true-普通机台，false-特殊支持机台
     */
    public static boolean isNormalMachine(MachineScheduleDTO machine) {
        return !isSupport195WideBase(machine)
                && !isSupport225WideBase(machine)
                && !isSupportChipTire(machine);
    }

    /**
     * 解析非特殊SKU机台分层优先级。
     *
     * @param machine 候选机台
     * @return 0-普通机台，1-特殊支持机台
     */
    public static int resolveNormalMachinePriority(MachineScheduleDTO machine) {
        return isNormalMachine(machine) ? 0 : 1;
    }

    /**
     * 判断机台是否支持19.5寸宽基。
     *
     * @param machine 候选机台
     * @return true-支持，false-不支持
     */
    public static boolean isSupport195WideBase(MachineScheduleDTO machine) {
        return isYes(Objects.isNull(machine) ? null : machine.getSupport195WideBase());
    }

    /**
     * 判断机台是否支持22.5寸宽基。
     *
     * @param machine 候选机台
     * @return true-支持，false-不支持
     */
    public static boolean isSupport225WideBase(MachineScheduleDTO machine) {
        return isYes(Objects.isNull(machine) ? null : machine.getSupport225WideBase());
    }

    /**
     * 判断机台是否支持芯片胎。
     *
     * @param machine 候选机台
     * @return true-支持，false-不支持
     */
    public static boolean isSupportChipTire(MachineScheduleDTO machine) {
        return isYes(Objects.isNull(machine) ? null : machine.getSupportChipTire());
    }

    /**
     * 从规格寸口字符串中提取英寸数值。
     *
     * @param proSize SKU英寸规格
     * @return 英寸数值，无法解析返回null
     */
    public static BigDecimal parseInch(String proSize) {
        String normalizedProSize = normalizeToken(proSize);
        if (StringUtils.isEmpty(normalizedProSize)) {
            return null;
        }
        try {
            return new BigDecimal(normalizedProSize);
        } catch (NumberFormatException e) {
            String upperValue = normalizedProSize.toUpperCase();
            int rIndex = upperValue.lastIndexOf('R');
            if (rIndex >= 0 && rIndex < upperValue.length() - 1) {
                String inchText = upperValue.substring(rIndex + 1).replaceAll("[^0-9.]", "");
                if (StringUtils.isNotEmpty(inchText)) {
                    try {
                        return new BigDecimal(inchText);
                    } catch (NumberFormatException ignored) {
                        return null;
                    }
                }
            }
        }
        return null;
    }

    /**
     * 解析SKU模壳标准集合。
     *
     * @param context 排程上下文
     * @param sku SKU排程信息
     * @return 模壳标准集合
     */
    private static Set<String> resolveSkuShellStandardSet(LhScheduleContext context, SkuScheduleDTO sku) {
        return resolveSkuShellStandardMatchResult(context, sku).getShellStandardSet();
    }

    /**
     * 解析SKU模壳匹配数据。
     *
     * @param context 排程上下文
     * @param sku SKU排程信息
     * @return 模壳集合和模具到货标识
     */
    private static SkuShellStandardMatchResult resolveSkuShellStandardMatchResult(LhScheduleContext context,
                                                                                  SkuScheduleDTO sku) {
        SkuShellStandardMatchResult result = new SkuShellStandardMatchResult();
        Set<String> shellStandardSet = new HashSet<>(4);
        String materialCode = normalizeToken(Objects.isNull(sku) ? null : sku.getMaterialCode());
        if (Objects.isNull(context) || StringUtils.isEmpty(materialCode)) {
            result.setShellStandardSet(shellStandardSet);
            return result;
        }
        Map<String, List<MdmSkuMouldRel>> skuMouldRelMap = context.getSkuMouldRelMap();
        if (CollectionUtils.isEmpty(skuMouldRelMap)) {
            result.setShellStandardSet(shellStandardSet);
            return result;
        }
        List<MdmSkuMouldRel> mouldRelList = skuMouldRelMap.get(materialCode);
        if (CollectionUtils.isEmpty(mouldRelList) || CollectionUtils.isEmpty(context.getModelInfoMap())) {
            if (!CollectionUtils.isEmpty(mouldRelList)) {
                for (MdmSkuMouldRel mouldRel : mouldRelList) {
                    if (Objects.nonNull(mouldRel) && Objects.nonNull(mouldRel.getBoardingDate())) {
                        result.setDeliveryMould(true);
                    } else if (Objects.nonNull(mouldRel) && StringUtils.isNotEmpty(mouldRel.getMouldCode())) {
                        result.setOrdinaryMould(true);
                    }
                }
            }
            result.setShellStandardSet(shellStandardSet);
            return result;
        }
        for (MdmSkuMouldRel mouldRel : mouldRelList) {
            if (Objects.isNull(mouldRel) || StringUtils.isEmpty(mouldRel.getMouldCode())) {
                continue;
            }
            if (Objects.nonNull(mouldRel.getBoardingDate())) {
                result.setDeliveryMould(true);
                continue;
            }
            result.setOrdinaryMould(true);
            MdmModelInfo modelInfo = context.getModelInfoMap().get(mouldRel.getMouldCode());
            String shellStandard = normalizeToken(Objects.isNull(modelInfo) ? null : modelInfo.getShellStandard());
            if (StringUtils.isNotEmpty(shellStandard)) {
                shellStandardSet.add(shellStandard);
            }
        }
        result.setShellStandardSet(shellStandardSet);
        return result;
    }

    /**
     * 解析机台模套型号集合。
     *
     * @param machineMouldSetCode 机台模套型号配置
     * @return 模套型号集合
     */
    private static Set<String> parseMachineMouldSetSet(String machineMouldSetCode) {
        Set<String> mouldSetCodeSet = new HashSet<>(4);
        String[] tokenArray = machineMouldSetCode.split(VALUE_SEPARATOR);
        for (String token : tokenArray) {
            String normalizedToken = normalizeToken(token);
            if (StringUtils.isNotEmpty(normalizedToken)) {
                mouldSetCodeSet.add(normalizedToken);
            }
        }
        return mouldSetCodeSet;
    }

    /**
     * 判断标记是否为是。
     *
     * @param value 标记值
     * @return true-是，false-否
     */
    private static boolean isYes(String value) {
        return StringUtils.equals(YES_FLAG, normalizeToken(value));
    }

    /**
     * 统一清洗匹配字段。
     *
     * @param value 原始值
     * @return 清洗后字段
     */
    private static String normalizeToken(String value) {
        if (StringUtils.isEmpty(value)) {
            return null;
        }
        String trimValue = value.trim();
        return StringUtils.isEmpty(trimValue) ? null : trimValue;
    }

    /**
     * SKU模壳匹配数据。
     */
    private static class SkuShellStandardMatchResult {
        /** 普通模具模壳型号集合 */
        private Set<String> shellStandardSet = new HashSet<>(4);
        /** 是否存在模具到货关系 */
        private boolean deliveryMould;
        /** 是否存在普通模具关系 */
        private boolean ordinaryMould;

        private Set<String> getShellStandardSet() {
            return shellStandardSet;
        }

        private void setShellStandardSet(Set<String> shellStandardSet) {
            this.shellStandardSet = shellStandardSet;
        }

        private boolean hasDeliveryMould() {
            return deliveryMould;
        }

        private void setDeliveryMould(boolean deliveryMould) {
            this.deliveryMould = deliveryMould;
        }

        private boolean hasOrdinaryMould() {
            return ordinaryMould;
        }

        private void setOrdinaryMould(boolean ordinaryMould) {
            this.ordinaryMould = ordinaryMould;
        }
    }
}
