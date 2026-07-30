package com.zlt.aps.lh.util;

import com.zlt.aps.lh.api.domain.entity.LhMachineOnlineInfo;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.mdm.api.domain.entity.MdmSkuMouldRel;
import org.apache.commons.lang3.StringUtils;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
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
     * 按 MES 在机记录的新旧顺序归一化模具的唯一在机归属。
     *
     * <p>基础数据会按机台回溯最近一条 MES 在机记录；当模具已经转移到新机台时，旧机台的最近
     * 历史记录仍可能保留同一模具号。此方法按在线日期、更新时间、数据版本依次比较，模具只保留
     * 在最新记录对应的物理机台上，避免续作初始化把一副实体模具同时登记到两台机台。</p>
     *
     * <p>方法会原地更新传入 Map 中的在机模具号；某条记录原本有模具、但全部已被更新记录接管时，
     * 会移除该机台的过期在机记录，使后续续作和强制重排把该机台按无有效 MES 在机处理。</p>
     *
     * @param machineOnlineInfoMap 按机台保留最近记录的 MES 在机信息
     * @return 各机台被最新记录接管而移除的模具号
     */
    public static Map<String, List<String>> normalizeLatestInMachineMouldOwnership(
            Map<String, LhMachineOnlineInfo> machineOnlineInfoMap) {
        Map<String, List<String>> removedMouldCodeMap =
                new LinkedHashMap<String, List<String>>(4);
        if (CollectionUtils.isEmpty(machineOnlineInfoMap)) {
            return removedMouldCodeMap;
        }
        Map<String, String> mouldOwnerMachineMap =
                buildLatestInMachineMouldOwnerMap(machineOnlineInfoMap);
        Iterator<Map.Entry<String, LhMachineOnlineInfo>> iterator =
                machineOnlineInfoMap.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, LhMachineOnlineInfo> entry = iterator.next();
            LhMachineOnlineInfo onlineInfo = entry.getValue();
            if (Objects.isNull(onlineInfo)) {
                continue;
            }
            String machineCode = resolveOnlineMachineCode(entry.getKey(), onlineInfo);
            LinkedHashSet<String> originalMouldCodeSet =
                    splitMouldCode(onlineInfo.getInMachineMouldCode());
            if (CollectionUtils.isEmpty(originalMouldCodeSet)) {
                continue;
            }
            LinkedHashSet<String> retainedMouldCodeSet =
                    new LinkedHashSet<String>(originalMouldCodeSet.size());
            List<String> removedMouldCodeList =
                    new ArrayList<String>(originalMouldCodeSet.size());
            for (String mouldCode : originalMouldCodeSet) {
                String ownerMachineCode = mouldOwnerMachineMap.get(mouldCode);
                if (isSamePhysicalMachine(machineCode, ownerMachineCode)) {
                    retainedMouldCodeSet.add(mouldCode);
                } else {
                    removedMouldCodeList.add(mouldCode);
                }
            }
            if (CollectionUtils.isEmpty(removedMouldCodeList)) {
                continue;
            }
            removedMouldCodeMap.put(machineCode, removedMouldCodeList);
            if (CollectionUtils.isEmpty(retainedMouldCodeSet)) {
                iterator.remove();
                continue;
            }
            onlineInfo.setInMachineMouldCode(joinMouldCode(retainedMouldCodeSet));
        }
        return removedMouldCodeMap;
    }

    /**
     * 构建每副在机模具对应的最新物理机台归属。
     *
     * <p>同一机台已由基础数据加载层收敛为一条最近记录，本方法继续跨机台比较记录版本。
     * 当比较字段完全一致时保留查询结果中的首条记录，保持与基础数据既有排序结果一致。</p>
     *
     * @param machineOnlineInfoMap MES 在机信息
     * @return 模具号到最新归属机台的映射
     */
    public static Map<String, String> buildLatestInMachineMouldOwnerMap(
            Map<String, LhMachineOnlineInfo> machineOnlineInfoMap) {
        Map<String, String> mouldOwnerMachineMap =
                new LinkedHashMap<String, String>(16);
        Map<String, LhMachineOnlineInfo> mouldOwnerInfoMap =
                new HashMap<String, LhMachineOnlineInfo>(16);
        if (CollectionUtils.isEmpty(machineOnlineInfoMap)) {
            return mouldOwnerMachineMap;
        }
        for (Map.Entry<String, LhMachineOnlineInfo> entry : machineOnlineInfoMap.entrySet()) {
            LhMachineOnlineInfo candidateInfo = entry.getValue();
            if (Objects.isNull(candidateInfo)) {
                continue;
            }
            String candidateMachineCode =
                    resolveOnlineMachineCode(entry.getKey(), candidateInfo);
            if (StringUtils.isEmpty(candidateMachineCode)) {
                continue;
            }
            for (String mouldCode : splitMouldCode(candidateInfo.getInMachineMouldCode())) {
                LhMachineOnlineInfo currentOwnerInfo = mouldOwnerInfoMap.get(mouldCode);
                if (Objects.nonNull(currentOwnerInfo)
                        && compareOnlineInfoVersion(candidateInfo, currentOwnerInfo) <= 0) {
                    continue;
                }
                mouldOwnerInfoMap.put(mouldCode, candidateInfo);
                mouldOwnerMachineMap.put(mouldCode, candidateMachineCode);
            }
        }
        return mouldOwnerMachineMap;
    }

    /**
     * 判断历史结果或历史换模计划中的模具是否已被当前 MES 记录转移到其他物理机台。
     *
     * @param mouldOwnerMachineMap 当前模具唯一归属 Map
     * @param machineCode 历史结果或计划的机台编码
     * @param mouldCodeText 历史结果或计划的模具号
     * @return true-至少一副模具当前属于其他物理机台；false-无冲突
     */
    public static boolean hasInMachineMouldOwnershipConflict(
            Map<String, String> mouldOwnerMachineMap,
            String machineCode,
            String mouldCodeText) {
        if (CollectionUtils.isEmpty(mouldOwnerMachineMap)
                || StringUtils.isEmpty(machineCode)
                || StringUtils.isEmpty(mouldCodeText)) {
            return false;
        }
        for (String mouldCode : splitMouldCode(mouldCodeText)) {
            String ownerMachineCode = mouldOwnerMachineMap.get(mouldCode);
            if (StringUtils.isNotEmpty(ownerMachineCode)
                    && !isSamePhysicalMachine(machineCode, ownerMachineCode)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 解析 MES 在机记录对应的机台编码。
     *
     * @param mapMachineCode Map 键中的机台编码
     * @param onlineInfo MES 在机记录
     * @return 优先返回实体机台编码，为空时返回 Map 键
     */
    private static String resolveOnlineMachineCode(
            String mapMachineCode,
            LhMachineOnlineInfo onlineInfo) {
        return Objects.nonNull(onlineInfo) && StringUtils.isNotEmpty(onlineInfo.getLhCode())
                ? onlineInfo.getLhCode() : mapMachineCode;
    }

    /**
     * 比较两条 MES 在机记录的新旧顺序。
     *
     * @param left 待比较记录
     * @param right 当前最新记录
     * @return 正数-left 更新；0-版本相同；负数-right 更新
     */
    private static int compareOnlineInfoVersion(
            LhMachineOnlineInfo left,
            LhMachineOnlineInfo right) {
        int compareResult = compareNullableDate(
                left.getOnlineDate(), right.getOnlineDate());
        if (compareResult != 0) {
            return compareResult;
        }
        compareResult = compareNullableDate(
                left.getUpdateTime(), right.getUpdateTime());
        if (compareResult != 0) {
            return compareResult;
        }
        return StringUtils.defaultString(left.getDataVersion())
                .compareTo(StringUtils.defaultString(right.getDataVersion()));
    }

    /**
     * 比较允许为空的日期，非空日期优先且越晚越新。
     *
     * @param left 左日期
     * @param right 右日期
     * @return 日期比较结果
     */
    private static int compareNullableDate(java.util.Date left, java.util.Date right) {
        if (Objects.equals(left, right)) {
            return 0;
        }
        if (Objects.isNull(left)) {
            return -1;
        }
        if (Objects.isNull(right)) {
            return 1;
        }
        return left.compareTo(right);
    }

    /**
     * 判断两个运行态机台编码是否属于同一物理机台。
     *
     * @param leftMachineCode 左机台编码
     * @param rightMachineCode 右机台编码
     * @return true-同一物理机台；false-不同物理机台
     */
    private static boolean isSamePhysicalMachine(
            String leftMachineCode,
            String rightMachineCode) {
        if (StringUtils.isEmpty(leftMachineCode)
                || StringUtils.isEmpty(rightMachineCode)) {
            return false;
        }
        return StringUtils.equals(
                LhSingleControlMachineUtil.resolvePhysicalMachineCode(leftMachineCode),
                LhSingleControlMachineUtil.resolvePhysicalMachineCode(rightMachineCode));
    }

    /**
     * 统计每个模具号关联的 SKU 数量。
     *
     * @param context 排程上下文
     * @return 模具号到关联 SKU 数量的映射
     */
    public static Map<String, Integer> buildMouldSharedSkuCountMap(LhScheduleContext context) {
        return buildMouldSharedSkuCountMap(context, null);
    }

    /**
     * 按指定 SKU 范围统计每个模具号关联的 SKU 数量。
     *
     * <p>该重载只负责复用既有“模具号归一化、同模具关联 SKU 去重、按模具分别计数”口径，
     * 不负责判断 SKU 是否满足具体业务条件。调用方可先根据月计划等业务数据生成允许计数的
     * SKU 集合，避免把续作降模的未来计划规则扩散到模具资源分配、换活字块等其他链路。</p>
     *
     * @param context 排程上下文
     * @param includedMaterialCodeSet 允许纳入共用性统计的 SKU 编码集合；传入 null 表示不做过滤，
     *                                空集合表示没有 SKU 可以纳入统计
     * @return 模具号到过滤后关联 SKU 数量的映射
     */
    public static Map<String, Integer> buildMouldSharedSkuCountMap(LhScheduleContext context,
                                                                   Set<String> includedMaterialCodeSet) {
        if (Objects.isNull(context) || CollectionUtils.isEmpty(context.getSkuMouldRelMap())) {
            return new HashMap<String, Integer>(0);
        }
        boolean needFilterMaterialCode = Objects.nonNull(includedMaterialCodeSet);
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
                // 续作降模传入未来有计划的关联 SKU 集合时，只统计集合内 SKU；原有调用传 null 保持既有口径。
                if (needFilterMaterialCode && !includedMaterialCodeSet.contains(materialCode)) {
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
