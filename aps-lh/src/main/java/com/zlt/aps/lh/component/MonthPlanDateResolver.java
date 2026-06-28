package com.zlt.aps.lh.component;

import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.util.MonthPlanDayQtyUtil;
import com.zlt.aps.mp.api.domain.entity.FactoryMonthPlanProductionFinalResult;
import org.apache.commons.lang3.StringUtils;
import org.springframework.util.CollectionUtils;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 月计划业务日期解析工具。
 * <p>按业务日期所属年月解析月计划记录和 dayN，避免跨月窗口误用同一个自然月的 DAY 字段。</p>
 */
public final class MonthPlanDateResolver {

    /** 月计划索引 key 分隔符 */
    private static final String KEY_SEPARATOR = "_";

    private MonthPlanDateResolver() {
    }

    /**
     * 解析指定业务日期的月计划记录。
     *
     * @param context 排程上下文
     * @param materialCode 物料编码
     * @param bizDate 业务日期
     * @return 月计划记录
     */
    public static FactoryMonthPlanProductionFinalResult resolvePlan(LhScheduleContext context,
                                                                    String materialCode,
                                                                    LocalDate bizDate) {
        if (Objects.isNull(context) || StringUtils.isEmpty(materialCode) || Objects.isNull(bizDate)) {
            return null;
        }
        String materialMonthKey = buildMaterialMonthKey(materialCode, bizDate.getYear(), bizDate.getMonthValue());
        if (!CollectionUtils.isEmpty(context.getMonthPlanByMaterialMonthMap())) {
            FactoryMonthPlanProductionFinalResult indexedPlan =
                    context.getMonthPlanByMaterialMonthMap().get(materialMonthKey);
            if (Objects.nonNull(indexedPlan)) {
                return indexedPlan;
            }
        }
        List<FactoryMonthPlanProductionFinalResult> planList = resolveLoadedMonthPlanList(context);
        if (CollectionUtils.isEmpty(planList)) {
            return null;
        }
        FactoryMonthPlanProductionFinalResult materialFallbackPlan = null;
        for (FactoryMonthPlanProductionFinalResult plan : planList) {
            if (Objects.isNull(plan) || !StringUtils.equals(materialCode, plan.getMaterialCode())) {
                continue;
            }
            if (Objects.equals(plan.getYear(), bizDate.getYear())
                    && Objects.equals(plan.getMonth(), bizDate.getMonthValue())) {
                return plan;
            }
            if (Objects.isNull(plan.getYear()) || Objects.isNull(plan.getMonth())) {
                materialFallbackPlan = plan;
            }
        }
        return materialFallbackPlan;
    }

    /**
     * 解析指定业务日期的日计划量。
     *
     * @param context 排程上下文
     * @param materialCode 物料编码
     * @param bizDate 业务日期
     * @return 日计划量
     */
    public static int resolveDayQty(LhScheduleContext context, String materialCode, LocalDate bizDate) {
        if (Objects.isNull(bizDate)) {
            return 0;
        }
        FactoryMonthPlanProductionFinalResult plan = resolvePlan(context, materialCode, bizDate);
        return Math.max(0, MonthPlanDayQtyUtil.resolveDayQty(plan, bizDate.getDayOfMonth()));
    }

    /**
     * 汇总指定业务日期窗口内的日计划量。
     *
     * @param context 排程上下文
     * @param materialCode 物料编码
     * @param startDate 窗口开始日期
     * @param endDate 窗口结束日期
     * @return 窗口日计划量
     */
    public static int resolveWindowPlanQty(LhScheduleContext context,
                                           String materialCode,
                                           LocalDate startDate,
                                           LocalDate endDate) {
        if (Objects.isNull(startDate) || Objects.isNull(endDate) || startDate.isAfter(endDate)) {
            return 0;
        }
        int totalQty = 0;
        LocalDate cursor = startDate;
        while (!cursor.isAfter(endDate)) {
            totalQty += resolveDayQty(context, materialCode, cursor);
            cursor = cursor.plusDays(1);
        }
        return Math.max(0, totalQty);
    }

    /**
     * 汇总指定月份 day1 到 endDate 当日的计划量。
     *
     * @param context 排程上下文
     * @param materialCode 物料编码
     * @param endDate 截止业务日期
     * @return 月初至截止日计划量
     */
    public static int sumMonthPlanQtyToDate(LhScheduleContext context, String materialCode, LocalDate endDate) {
        if (Objects.isNull(endDate)) {
            return 0;
        }
        LocalDate cursor = endDate.withDayOfMonth(1);
        int totalQty = 0;
        while (!cursor.isAfter(endDate)) {
            totalQty += resolveDayQty(context, materialCode, cursor);
            cursor = cursor.plusDays(1);
        }
        return Math.max(0, totalQty);
    }

    /**
     * 查找从指定计划日开始向后扫描得到的月计划断点日。
     * <p>断点日为“断开前最后一个有计划量的日期”。如果到月底未断开，则取最后一个有计划量日期。</p>
     *
     * @param context 排程上下文
     * @param materialCode 物料编码
     * @param startDate 开始扫描日期
     * @return 断点日
     */
    public static LocalDate findBreakPointDate(LhScheduleContext context,
                                               String materialCode,
                                               LocalDate startDate) {
        if (Objects.isNull(startDate)) {
            return null;
        }
        LocalDate monthEndDate = startDate.withDayOfMonth(startDate.lengthOfMonth());
        LocalDate lastPlanDate = null;
        LocalDate cursor = startDate;
        while (!cursor.isAfter(monthEndDate)) {
            int dayPlanQty = resolveDayQty(context, materialCode, cursor);
            if (hasPlanQty(dayPlanQty)) {
                lastPlanDate = cursor;
                cursor = cursor.plusDays(1);
                continue;
            }
            if (Objects.nonNull(lastPlanDate)) {
                return lastPlanDate;
            }
            cursor = cursor.plusDays(1);
        }
        return lastPlanDate;
    }

    /**
     * 判断日计划是否有值。
     *
     * @param dayPlanQty 日计划量
     * @return true-有计划；false-无计划
     */
    public static boolean hasPlanQty(int dayPlanQty) {
        return dayPlanQty > 0;
    }

    /**
     * 构建物料月计划索引。
     *
     * @param planList 月计划列表
     * @return key=materialCode_year_month 的月计划索引
     */
    public static Map<String, FactoryMonthPlanProductionFinalResult> buildMaterialMonthPlanMap(
            List<FactoryMonthPlanProductionFinalResult> planList) {
        Map<String, FactoryMonthPlanProductionFinalResult> planMap =
                new LinkedHashMap<String, FactoryMonthPlanProductionFinalResult>(16);
        if (CollectionUtils.isEmpty(planList)) {
            return planMap;
        }
        for (FactoryMonthPlanProductionFinalResult plan : planList) {
            if (Objects.isNull(plan) || StringUtils.isEmpty(plan.getMaterialCode())
                    || Objects.isNull(plan.getYear()) || Objects.isNull(plan.getMonth())) {
                continue;
            }
            planMap.putIfAbsent(buildMaterialMonthKey(
                    plan.getMaterialCode(), plan.getYear(), plan.getMonth()), plan);
        }
        return planMap;
    }

    /**
     * 构建物料年月 key。
     *
     * @param materialCode 物料编码
     * @param year 年份
     * @param month 月份
     * @return 物料年月 key
     */
    public static String buildMaterialMonthKey(String materialCode, int year, int month) {
        return materialCode + KEY_SEPARATOR + year + KEY_SEPARATOR + month;
    }

    /**
     * 构建年月 key。
     *
     * @param year 年份
     * @param month 月份
     * @return 年月 key
     */
    public static String buildYearMonthKey(int year, int month) {
        return year + KEY_SEPARATOR + month;
    }

    private static List<FactoryMonthPlanProductionFinalResult> resolveLoadedMonthPlanList(LhScheduleContext context) {
        if (!CollectionUtils.isEmpty(context.getLoadedMonthPlanList())) {
            return context.getLoadedMonthPlanList();
        }
        return context.getMonthPlanList();
    }
}
