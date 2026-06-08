package com.zlt.aps.lh.util;

import com.zlt.aps.lh.api.domain.entity.LhMachineOnlineInfo;
import com.zlt.aps.lh.context.LhScheduleContext;
import org.apache.commons.lang3.StringUtils;
import org.springframework.util.CollectionUtils;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Objects;

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
}
