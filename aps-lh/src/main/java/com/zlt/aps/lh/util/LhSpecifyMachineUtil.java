package com.zlt.aps.lh.util;

import com.zlt.aps.lh.api.domain.entity.LhSpecifyMachine;
import com.zlt.aps.lh.api.enums.JobTypeEnum;
import com.zlt.aps.lh.context.LhScheduleContext;
import org.apache.commons.lang3.StringUtils;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 硫化定点机台配置工具。
 *
 * @author APS
 */
public final class LhSpecifyMachineUtil {

    private LhSpecifyMachineUtil() {
    }

    /**
     * 按物料编码查询限制作业定点机台。
     *
     * @param context 排程上下文
     * @param materialCode 物料编码
     * @return 限制作业定点机台列表
     */
    public static List<LhSpecifyMachine> listLimitSpecifyMachinesByMaterialCode(LhScheduleContext context,
                                                                                 String materialCode) {
        return filterSpecifyMachines(context, listSpecifyMachinesByMaterialCode(context, materialCode),
                JobTypeEnum.RESTRICTED.getCode(), null);
    }

    /**
     * 按机台编码查询限制作业定点物料配置。
     *
     * @param context 排程上下文
     * @param machineCode 机台编码
     * @return 限制作业定点物料配置列表
     */
    public static List<LhSpecifyMachine> listLimitSpecifyMachinesByMachineCode(LhScheduleContext context,
                                                                                String machineCode) {
        return filterAllSpecifyMachines(context, JobTypeEnum.RESTRICTED.getCode(), machineCode);
    }

    /**
     * 查询指定机台和物料的限制作业定点配置。
     *
     * @param context 排程上下文
     * @param machineCode 机台编码
     * @param materialCode 物料编码
     * @return 限制作业配置列表
     */
    public static List<LhSpecifyMachine> listLimitSpecifyMachines(LhScheduleContext context,
                                                                  String machineCode,
                                                                  String materialCode) {
        return filterSpecifyMachines(context, listSpecifyMachinesByMaterialCode(context, materialCode),
                JobTypeEnum.RESTRICTED.getCode(), machineCode);
    }

    /**
     * 查询指定机台和物料的不可作业配置。
     *
     * @param context 排程上下文
     * @param machineCode 机台编码
     * @param materialCode 物料编码
     * @return true-不可作业，false-未配置不可作业
     */
    public static boolean isNotAllowedMachine(LhScheduleContext context, String machineCode, String materialCode) {
        return !CollectionUtils.isEmpty(filterSpecifyMachines(context, listSpecifyMachinesByMaterialCode(context, materialCode),
                JobTypeEnum.NOT_ALLOWED.getCode(), machineCode));
    }

    /**
     * 判断机台是否为当前物料的限制作业定点机台。
     *
     * @param context 排程上下文
     * @param machineCode 机台编码
     * @param materialCode 物料编码
     * @return true-限制作业定点机台，false-不是
     */
    public static boolean isLimitSpecifyMachine(LhScheduleContext context, String machineCode, String materialCode) {
        return !CollectionUtils.isEmpty(listLimitSpecifyMachines(context, machineCode, materialCode));
    }

    /**
     * 判断物料是否配置了限制作业定点机台。
     *
     * @param context 排程上下文
     * @param materialCode 物料编码
     * @return true-已配置，false-未配置
     */
    public static boolean hasLimitSpecifyMachine(LhScheduleContext context, String materialCode) {
        return !CollectionUtils.isEmpty(listLimitSpecifyMachinesByMaterialCode(context, materialCode));
    }

    /**
     * 获取物料配置的限制作业机台编码集合。
     *
     * @param context 排程上下文
     * @param materialCode 物料编码
     * @return 机台编码集合
     */
    public static Set<String> resolveLimitSpecifyMachineCodes(LhScheduleContext context, String materialCode) {
        List<LhSpecifyMachine> specifyMachineList = listLimitSpecifyMachinesByMaterialCode(context, materialCode);
        Set<String> machineCodeSet = new HashSet<>(specifyMachineList.size() * 2);
        for (LhSpecifyMachine specifyMachine : specifyMachineList) {
            if (StringUtils.isNotEmpty(specifyMachine.getMachineCode())) {
                machineCodeSet.add(specifyMachine.getMachineCode());
            }
        }
        return machineCodeSet;
    }

    /**
     * 获取物料配置的不可作业机台编码集合。
     *
     * @param context 排程上下文
     * @param materialCode 物料编码
     * @return 机台编码集合
     */
    public static Set<String> resolveNotAllowedMachineCodes(LhScheduleContext context, String materialCode) {
        List<LhSpecifyMachine> specifyMachineList = filterSpecifyMachines(
                context,
                listSpecifyMachinesByMaterialCode(context, materialCode), JobTypeEnum.NOT_ALLOWED.getCode(), null);
        Set<String> machineCodeSet = new HashSet<>(specifyMachineList.size() * 2);
        for (LhSpecifyMachine specifyMachine : specifyMachineList) {
            if (StringUtils.isNotEmpty(specifyMachine.getMachineCode())) {
                machineCodeSet.add(specifyMachine.getMachineCode());
            }
        }
        return machineCodeSet;
    }

    /**
     * 按物料编码获取定点机台配置。
     *
     * @param context 排程上下文
     * @param materialCode 物料编码
     * @return 定点机台配置列表
     */
    private static List<LhSpecifyMachine> listSpecifyMachinesByMaterialCode(LhScheduleContext context,
                                                                            String materialCode) {
        if (!isSpecifyMachineRuleEnabled(context) || Objects.isNull(context) || StringUtils.isEmpty(materialCode)
                || CollectionUtils.isEmpty(context.getSpecifyMachineMap())) {
            return new ArrayList<>(0);
        }
        List<LhSpecifyMachine> specifyMachineList = context.getSpecifyMachineMap().get(materialCode);
        return CollectionUtils.isEmpty(specifyMachineList) ? new ArrayList<LhSpecifyMachine>(0) : specifyMachineList;
    }

    /**
     * 按配置类型和机台编码过滤全部定点机台配置。
     *
     * @param context 排程上下文
     * @param jobType 作业类型
     * @param machineCode 机台编码
     * @return 定点机台配置列表
     */
    private static List<LhSpecifyMachine> filterAllSpecifyMachines(LhScheduleContext context,
                                                                   String jobType,
                                                                   String machineCode) {
        if (!isSpecifyMachineRuleEnabled(context) || Objects.isNull(context)
                || CollectionUtils.isEmpty(context.getSpecifyMachineMap())) {
            return new ArrayList<>(0);
        }
        List<LhSpecifyMachine> resultList = new ArrayList<>(8);
        for (Map.Entry<String, List<LhSpecifyMachine>> entry : context.getSpecifyMachineMap().entrySet()) {
            resultList.addAll(filterSpecifyMachines(context, entry.getValue(), jobType, machineCode));
        }
        return resultList;
    }

    /**
     * 按配置类型和机台编码过滤定点机台配置。
     *
     * @param specifyMachineList 原始定点机台配置列表
     * @param jobType 作业类型
     * @param machineCode 机台编码
     * @return 定点机台配置列表
     */
    private static List<LhSpecifyMachine> filterSpecifyMachines(LhScheduleContext context,
                                                                List<LhSpecifyMachine> specifyMachineList,
                                                                String jobType,
                                                                String machineCode) {
        if (CollectionUtils.isEmpty(specifyMachineList)) {
            return new ArrayList<>(0);
        }
        List<LhSpecifyMachine> resultList = new ArrayList<>(specifyMachineList.size());
        for (LhSpecifyMachine specifyMachine : specifyMachineList) {
            if (Objects.isNull(specifyMachine) || !StringUtils.equals(jobType, specifyMachine.getJobType())) {
                continue;
            }
            if (StringUtils.isNotEmpty(machineCode)
                    && !StringUtils.equals(machineCode, specifyMachine.getMachineCode())) {
                continue;
            }
            resultList.add(specifyMachine);
        }
        return resultList;
    }

    /**
     * 判断硫化定点机台规则是否启用。
     *
     * @param context 排程上下文
     * @return true-启用；false-关闭
     */
    private static boolean isSpecifyMachineRuleEnabled(LhScheduleContext context) {
        return Objects.nonNull(context) && Objects.nonNull(context.getScheduleConfig())
                && context.getScheduleConfig().isSpecifyMachineRuleEnabled();
    }
}
