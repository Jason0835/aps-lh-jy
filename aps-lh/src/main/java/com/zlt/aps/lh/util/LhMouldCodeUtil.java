package com.zlt.aps.lh.util;

import com.zlt.aps.lh.api.domain.entity.LhMachineOnlineInfo;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.mdm.api.domain.entity.MdmSkuMouldRel;
import org.apache.commons.lang3.StringUtils;
import org.springframework.util.CollectionUtils;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 硫化模具号解析工具。
 *
 * <p>统一处理在机模具号和结果模具号的逗号拆分、去重和规范化，避免续作、换活字块、
 * 新增模具资源占用各自使用不同口径。</p>
 *
 * @author APS
 */
public final class LhMouldCodeUtil {

    private static final String MOULD_CODE_SEPARATOR = ",";

    private LhMouldCodeUtil() {
    }

    /**
     * 解析机台硫化在机实际模具号集合。
     *
     * @param context 排程上下文
     * @param machineCode 机台编码
     * @return 在机实际模具号集合
     */
    public static LinkedHashSet<String> resolveInMachineMouldCodeSet(LhScheduleContext context, String machineCode) {
        if (Objects.isNull(context)
                || StringUtils.isEmpty(machineCode)
                || CollectionUtils.isEmpty(context.getMachineOnlineInfoMap())) {
            return new LinkedHashSet<String>(0);
        }
        LhMachineOnlineInfo onlineInfo = context.getMachineOnlineInfoMap().get(machineCode);
        if (Objects.isNull(onlineInfo)) {
            return new LinkedHashSet<String>(0);
        }
        return splitMouldCode(onlineInfo.getInMachineMouldCode());
    }

    /**
     * 解析机台硫化在机实际模具号文本。
     *
     * @param context 排程上下文
     * @param machineCode 机台编码
     * @return 规范化后的在机模具号，多个英文逗号分隔
     */
    public static String resolveInMachineMouldCode(LhScheduleContext context, String machineCode) {
        return joinMouldCode(resolveInMachineMouldCodeSet(context, machineCode));
    }

    /**
     * 拆分模具号文本。
     *
     * @param mouldCodeText 模具号文本，多个英文逗号分隔
     * @return 去空格、去空值、去重后的模具号集合
     */
    public static LinkedHashSet<String> splitMouldCode(String mouldCodeText) {
        LinkedHashSet<String> mouldCodeSet = new LinkedHashSet<String>(4);
        if (StringUtils.isEmpty(mouldCodeText)) {
            return mouldCodeSet;
        }
        String[] mouldCodeArray = StringUtils.split(mouldCodeText, MOULD_CODE_SEPARATOR);
        if (Objects.isNull(mouldCodeArray)) {
            return mouldCodeSet;
        }
        for (String mouldCode : mouldCodeArray) {
            String normalizedMouldCode = StringUtils.trim(mouldCode);
            if (StringUtils.isNotEmpty(normalizedMouldCode)) {
                mouldCodeSet.add(normalizedMouldCode);
            }
        }
        return mouldCodeSet;
    }

    /**
     * 拼接模具号集合。
     *
     * @param mouldCodeCollection 模具号集合
     * @return 英文逗号分隔的模具号文本
     */
    public static String joinMouldCode(Collection<String> mouldCodeCollection) {
        if (CollectionUtils.isEmpty(mouldCodeCollection)) {
            return null;
        }
        return StringUtils.join(mouldCodeCollection, MOULD_CODE_SEPARATOR);
    }

    /**
     * 统计每个模具号关联的 SKU 数量。
     *
     * @param context 排程上下文
     * @return 模具号到关联 SKU 数量的映射
     */
    public static Map<String, Integer> buildMouldSharedSkuCountMap(LhScheduleContext context) {
        if (Objects.isNull(context) || CollectionUtils.isEmpty(context.getSkuMouldRelMap())) {
            return new HashMap<String, Integer>(0);
        }
        Map<String, Set<String>> mouldSkuSetMap = new HashMap<String, Set<String>>(16);
        for (Map.Entry<String, List<MdmSkuMouldRel>> entry : context.getSkuMouldRelMap().entrySet()) {
            if (CollectionUtils.isEmpty(entry.getValue())) {
                continue;
            }
            for (MdmSkuMouldRel rel : entry.getValue()) {
                String mouldCode = Objects.isNull(rel) ? null : StringUtils.trim(rel.getMouldCode());
                if (StringUtils.isEmpty(mouldCode)) {
                    continue;
                }
                String materialCode = StringUtils.isNotEmpty(entry.getKey()) ? entry.getKey() : rel.getMaterialCode();
                if (StringUtils.isEmpty(materialCode)) {
                    continue;
                }
                mouldSkuSetMap.computeIfAbsent(mouldCode, key -> new LinkedHashSet<String>(4)).add(materialCode);
            }
        }
        Map<String, Integer> resultMap = new HashMap<String, Integer>(mouldSkuSetMap.size());
        for (Map.Entry<String, Set<String>> entry : mouldSkuSetMap.entrySet()) {
            resultMap.put(entry.getKey(), entry.getValue().size());
        }
        return resultMap;
    }

    /**
     * 解析机台在机模具的共用性数量。
     *
     * @param context 排程上下文
     * @param machineCode 机台编码
     * @param mouldSharedSkuCountMap 模具号到关联 SKU 数量的映射
     * @return 机台在机模具共用性数量
     */
    public static int resolveMachineMouldSharedSkuCount(LhScheduleContext context,
                                                        String machineCode,
                                                        Map<String, Integer> mouldSharedSkuCountMap) {
        LinkedHashSet<String> mouldCodeSet = resolveInMachineMouldCodeSet(context, machineCode);
        if (CollectionUtils.isEmpty(mouldCodeSet) || CollectionUtils.isEmpty(mouldSharedSkuCountMap)) {
            return 0;
        }
        int sharedSkuCount = 0;
        for (String mouldCode : mouldCodeSet) {
            sharedSkuCount += Math.max(0, mouldSharedSkuCountMap.getOrDefault(mouldCode, 0));
        }
        return sharedSkuCount;
    }
}
