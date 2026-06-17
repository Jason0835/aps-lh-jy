package com.zlt.aps.lh.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zlt.aps.lh.domain.entity.MpMonthPlanStatistics;
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
     *
     * @param row 月计划结构统计行
     * @param dayOfMonth 月内日期序号
     * @return dayN JSON字符串
     */
    private static String resolveDayJson(MpMonthPlanStatistics row, int dayOfMonth) {
        switch (dayOfMonth) {
            case 1:
                return row.getDay1();
            case 2:
                return row.getDay2();
            case 3:
                return row.getDay3();
            case 4:
                return row.getDay4();
            case 5:
                return row.getDay5();
            case 6:
                return row.getDay6();
            case 7:
                return row.getDay7();
            case 8:
                return row.getDay8();
            case 9:
                return row.getDay9();
            case 10:
                return row.getDay10();
            case 11:
                return row.getDay11();
            case 12:
                return row.getDay12();
            case 13:
                return row.getDay13();
            case 14:
                return row.getDay14();
            case 15:
                return row.getDay15();
            case 16:
                return row.getDay16();
            case 17:
                return row.getDay17();
            case 18:
                return row.getDay18();
            case 19:
                return row.getDay19();
            case 20:
                return row.getDay20();
            case 21:
                return row.getDay21();
            case 22:
                return row.getDay22();
            case 23:
                return row.getDay23();
            case 24:
                return row.getDay24();
            case 25:
                return row.getDay25();
            case 26:
                return row.getDay26();
            case 27:
                return row.getDay27();
            case 28:
                return row.getDay28();
            case 29:
                return row.getDay29();
            case 30:
                return row.getDay30();
            case 31:
                return row.getDay31();
            default:
                return null;
        }
    }
}
