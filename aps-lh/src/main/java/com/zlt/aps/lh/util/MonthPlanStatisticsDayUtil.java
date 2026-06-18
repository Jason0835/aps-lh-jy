package com.zlt.aps.lh.util;

import cn.hutool.core.bean.BeanUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zlt.aps.mp.api.domain.entity.MpMonthPlanStatistics;
import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.time.LocalDate;
import java.util.Objects;

/**
 * 月计划统计 dayN JSON 解析工具。
 */
public final class MonthPlanStatisticsDayUtil {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final String LH_MACHINES_KEY = "lhMachines";

    /** dayN 字段名前缀，对应实体 day1~day31 */
    private static final String DAY_FIELD_PREFIX = "day";

    /** 月内日期最小序号 */
    private static final int MIN_DAY_OF_MONTH = 1;

    /** 月内日期最大序号 */
    private static final int MAX_DAY_OF_MONTH = 31;

    private MonthPlanStatisticsDayUtil() {
    }

    /**
     * 解析指定业务日期的结构计划硫化机台数。
     *
     * @param row 月计划结构统计行
     * @param productionDate 业务日期
     * @return 计划硫化机台数
     */
    public static int resolveLhMachines(MpMonthPlanStatistics row, LocalDate productionDate) {
        if (Objects.isNull(row) || Objects.isNull(productionDate)) {
            return 0;
        }
        String dayJson = resolveDayJson(row, productionDate.getDayOfMonth());
        return resolveLhMachines(dayJson, row.getStructureName(), productionDate);
    }

    /**
     * 从 dayN JSON 字符串解析 lhMachines。
     *
     * @param dayJson dayN JSON字符串
     * @param structureName 产品结构
     * @param productionDate 业务日期
     * @return 计划硫化机台数
     */
    public static int resolveLhMachines(String dayJson, String structureName, LocalDate productionDate) {
        if (StringUtils.isEmpty(dayJson)) {
            return 0;
        }
        try {
            JsonNode rootNode = OBJECT_MAPPER.readTree(dayJson);
            JsonNode lhMachinesNode = rootNode.get(LH_MACHINES_KEY);
            if (Objects.isNull(lhMachinesNode) || lhMachinesNode.isNull()) {
                return 0;
            }
            if (lhMachinesNode.isNumber()) {
                return Math.max(0, lhMachinesNode.asInt());
            }
            if (lhMachinesNode.isTextual()) {
                String textValue = lhMachinesNode.asText();
                return StringUtils.isEmpty(textValue) ? 0 : Math.max(0, Integer.parseInt(textValue.trim()));
            }
            return 0;
        } catch (IOException | RuntimeException e) {
            throw new IllegalArgumentException(String.format(
                    "月计划结构统计dayN不是合法JSON，结构=%s，日期=%s，dayN=%s",
                    structureName, productionDate, dayJson), e);
        }
    }

    /**
     * 读取指定 dayN 字段。
     * 通过 Hutool BeanUtil 反射读取 day1~day31 属性，避免 31 分支 switch。
     *
     * @param row 月计划结构统计行
     * @param dayOfMonth 月内日期序号
     * @return dayN JSON字符串
     */
    private static String resolveDayJson(MpMonthPlanStatistics row, int dayOfMonth) {
        // 仅处理合法的月内日期序号，越界返回 null 与原语义一致
        if (dayOfMonth < MIN_DAY_OF_MONTH || dayOfMonth > MAX_DAY_OF_MONTH) {
            return null;
        }
        // 拼接属性名 day1~day31，利用 BeanUtil 调用对应 getter
        Object dayValue = BeanUtil.getProperty(row, DAY_FIELD_PREFIX + dayOfMonth);
        return Objects.isNull(dayValue) ? null : dayValue.toString();
    }
}
