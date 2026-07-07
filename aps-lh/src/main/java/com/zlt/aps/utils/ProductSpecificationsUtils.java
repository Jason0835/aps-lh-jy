package com.zlt.aps.utils;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 物料规格解析工具类
 *
 * @author ZLT
 * @date 20250917
 */
@Slf4j
public class ProductSpecificationsUtils {
    /**
     * 标准公制规格（如225/60R18 或 225/50ZR17）
     */
    private static final Pattern metricPattern = Pattern.compile("(\\d+)/(\\d+)([A-Z]?)R.*");
    /**
     * 简化公制规格（如185R14LT 或 175R14LT 8PR）
     */
    private static final Pattern simplifiedPattern = Pattern.compile("(\\d+)R\\d+([A-Z]*)");
    /**
     * 英寸制规格（如31×10.50R15 或 31*10.50R15LT）
     */
    private static final Pattern inchPattern = Pattern.compile("(\\d+\\.?\\d+)[×x](\\d+\\.?\\d+)R.*");
    /**
     * 特殊规格（如带ZR的225/50ZR17）
     */
    private static final Pattern zrPattern = Pattern.compile("(\\d+)/(\\d+)ZR.*");
    /**
     * 英寸换算毫米
     */
    private static final BigDecimal inchMm = BigDecimal.valueOf(25.4);
    /**
     * 简化公制规格（如185R14LT，默认扁平比为80%
     */
    private static final int defaultSimplifiedRate = 80;

    /**
     * 通过规格，解析对应的断面宽和扁平比
     * 第一个数值为断面宽--单位毫米
     * 第二个数值为扁平比
     *
     * @param specifications 规格信息
     * @return
     */
    public static List<Integer> parseSectionWidthAndAspectRatio(String specifications) {
        log.info("解析规格数据：" + specifications);
        if (StringUtils.isBlank(specifications)) {
            return Collections.emptyList();
        }
        // 统一替换特殊字符（如*和×都视为乘号）
        String normalizedSpec = specifications.replace('*', '×').trim();
        List<Integer> result = new ArrayList<>(2);
        // 情况1：标准公制规格（如225/60R18 或 225/50ZR17）
        Matcher metricMatcher = metricPattern.matcher(normalizedSpec);
        if (metricMatcher.find()) {
            result.add(Integer.valueOf(metricMatcher.group(1)));
            result.add(Integer.valueOf(metricMatcher.group(2)));
            return result;
        }
        Matcher simplifiedMatcher = simplifiedPattern.matcher(normalizedSpec.split(" ")[0]);
        // 情况2：简化公制规格（如185R14LT 或 175R14LT 8PR）
        if (simplifiedMatcher.find()) {
            result.add(Integer.valueOf(simplifiedMatcher.group(1)));
            // 简化公制默认80%扁平比
            result.add(defaultSimplifiedRate);
            return result;
        }
        // 情况3：英寸制规格（如31×10.50R15 或 31*10.50R15LT）
        Matcher inchMatcher = inchPattern.matcher(normalizedSpec);
        if (inchMatcher.find()) {
            try {
                String widthValue = inchMatcher.group(2);
                double outerDiameter = Double.parseDouble(inchMatcher.group(1));
                double width = Double.parseDouble(widthValue);
                Integer widthInt = inchMm.multiply(BigDecimal.valueOf(width)).setScale(0, RoundingMode.UP).intValue();
                // 提取轮毂直径（如R15后的15）
                String rimPart = normalizedSpec.split("R")[1];
                int rimDiameter = Integer.parseInt(rimPart.replaceAll("\\D", ""));

                double sidewallHeight = (outerDiameter - rimDiameter) / 2.0;
                double calculatedRatio = (sidewallHeight / width) * 100;
                result.add(widthInt);
                result.add(Integer.valueOf(String.valueOf(calculatedRatio)));
                return result;
            } catch (Exception e) {
                log.warn("英寸制规格解析失败: {}", specifications, e);
            }
        }
        // 情况4：特殊规格（如带ZR的225/50ZR17）
        if (zrPattern.matcher(normalizedSpec).find()) {
            String effectiveValue = normalizedSpec.split("/")[1].replaceAll("ZR.*", "");
            String widthValue = normalizedSpec.split("/")[0];
            result.add(Integer.valueOf(widthValue));
            result.add(Integer.valueOf(effectiveValue));
            return result;
        }
        return Collections.emptyList();
    }

    private ProductSpecificationsUtils() {

    }
}
