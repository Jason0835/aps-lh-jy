package com.zlt.aps.lh.component;

import com.zlt.aps.lh.api.domain.dto.CuringMonthPlanTotalResult;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.mp.api.domain.entity.FactoryMonthPlanProductionFinalResult;
import org.apache.commons.lang3.StringUtils;

import java.time.LocalDate;
import java.util.Objects;

/**
 * 硫化月计划总量计算器。
 * <p>只负责按月计划断点和跨月规则计算“月计划总量”，不下沉已完成量和上月超欠产公式。</p>
 */
public final class CuringMonthPlanTotalCalculator {

    private static final String SCENE_WINDOW_HAS_PLAN = "WINDOW_HAS_PLAN";

    private static final String SCENE_WINDOW_HAS_PLAN_CROSS_MONTH = "WINDOW_HAS_PLAN_CROSS_MONTH";

    private static final String SCENE_NO_WINDOW_PLAN_WITH_HISTORY_SURPLUS = "NO_WINDOW_PLAN_WITH_HISTORY_SURPLUS";

    private static final String SCENE_NO_WINDOW_PLAN_FUTURE_SEGMENT = "NO_WINDOW_PLAN_FUTURE_SEGMENT";

    private CuringMonthPlanTotalCalculator() {
    }

    /**
     * 计算硫化月计划总量。
     *
     * @param context 排程上下文
     * @param plan 月计划
     * @param scheduleStartDate 排程窗口T日
     * @param windowEndDate 排程窗口结束日
     * @param actualFinishedQty 已完成量，仅用于无窗口计划场景判断历史余量
     * @param lastMonthOverdueQty 上月超欠产，仅用于无窗口计划场景判断历史余量
     * @return 月计划总量计算结果
     */
    public static CuringMonthPlanTotalResult calculate(LhScheduleContext context,
                                                       FactoryMonthPlanProductionFinalResult plan,
                                                       LocalDate scheduleStartDate,
                                                       LocalDate windowEndDate,
                                                       int actualFinishedQty,
                                                       int lastMonthOverdueQty) {
        CuringMonthPlanTotalResult result = new CuringMonthPlanTotalResult();
        result.setScheduleStartDate(scheduleStartDate);
        if (Objects.isNull(context) || Objects.isNull(plan) || StringUtils.isEmpty(plan.getMaterialCode())
                || Objects.isNull(scheduleStartDate) || Objects.isNull(windowEndDate)
                || scheduleStartDate.isAfter(windowEndDate)) {
            return result;
        }
        String materialCode = plan.getMaterialCode();
        String productStatus = plan.getProductStatus();
        LocalDate latestPlanDateInWindow = findLatestPlanDateInWindow(context, materialCode, productStatus,
                scheduleStartDate, windowEndDate);
        result.setLatestPlanDateInWindow(latestPlanDateInWindow);
        if (Objects.nonNull(latestPlanDateInWindow)) {
            fillWindowPlanResult(context, result, materialCode, productStatus, scheduleStartDate, latestPlanDateInWindow);
            return result;
        }
        fillNoWindowPlanResult(context, result, materialCode, productStatus, scheduleStartDate,
                actualFinishedQty, lastMonthOverdueQty);
        return result;
    }

    private static void fillWindowPlanResult(LhScheduleContext context,
                                             CuringMonthPlanTotalResult result,
                                             String materialCode,
                                             String productStatus,
                                             LocalDate scheduleStartDate,
                                             LocalDate latestPlanDateInWindow) {
        boolean crossMonth = scheduleStartDate.getYear() != latestPlanDateInWindow.getYear()
                || scheduleStartDate.getMonthValue() != latestPlanDateInWindow.getMonthValue();
        result.setCrossMonth(crossMonth);
        if (crossMonth) {
            LocalDate latestCurrentMonthPlanDate = findLatestPlanDateInCurrentMonthWindow(
                    context, materialCode, productStatus, scheduleStartDate);
            int currentMonthPlanTotal = calculateSegmentTotal(
                    context, materialCode, productStatus, latestCurrentMonthPlanDate);
            LocalDate crossBreakPointDate = MonthPlanDateResolver.findBreakPointDate(
                    context, materialCode, productStatus, latestPlanDateInWindow);
            int crossMonthPlanTotal = MonthPlanDateResolver.sumMonthPlanQtyToDate(
                    context, materialCode, productStatus, crossBreakPointDate);
            result.setBreakPointDate(crossBreakPointDate);
            result.setCurrentMonthPlanTotal(currentMonthPlanTotal);
            result.setCrossMonthPlanTotal(crossMonthPlanTotal);
            result.setMonthPlanTotal(currentMonthPlanTotal + crossMonthPlanTotal);
            result.setCalculateScene(SCENE_WINDOW_HAS_PLAN_CROSS_MONTH);
            return;
        }
        LocalDate breakPointDate = MonthPlanDateResolver.findBreakPointDate(
                context, materialCode, productStatus, latestPlanDateInWindow);
        int monthPlanTotal = MonthPlanDateResolver.sumMonthPlanQtyToDate(
                context, materialCode, productStatus, breakPointDate);
        result.setBreakPointDate(breakPointDate);
        result.setCurrentMonthPlanTotal(monthPlanTotal);
        result.setMonthPlanTotal(monthPlanTotal);
        result.setCalculateScene(SCENE_WINDOW_HAS_PLAN);
    }

    private static void fillNoWindowPlanResult(LhScheduleContext context,
                                               CuringMonthPlanTotalResult result,
                                               String materialCode,
                                               String productStatus,
                                               LocalDate scheduleStartDate,
                                               int actualFinishedQty,
                                               int lastMonthOverdueQty) {
        int previousPlanTotal = MonthPlanDateResolver.sumMonthPlanQtyToDate(
                context, materialCode, productStatus, scheduleStartDate);
        int previousSurplusQty = previousPlanTotal - Math.max(0, actualFinishedQty) + lastMonthOverdueQty;
        if (previousSurplusQty > 0) {
            result.setBreakPointDate(scheduleStartDate);
            result.setCurrentMonthPlanTotal(previousPlanTotal);
            result.setMonthPlanTotal(previousPlanTotal);
            result.setCalculateScene(SCENE_NO_WINDOW_PLAN_WITH_HISTORY_SURPLUS);
            return;
        }
        LocalDate futurePlanDate = findFirstFuturePlanDate(context, materialCode, productStatus, scheduleStartDate);
        LocalDate breakPointDate = MonthPlanDateResolver.findBreakPointDate(
                context, materialCode, productStatus, futurePlanDate);
        int monthPlanTotal = MonthPlanDateResolver.sumMonthPlanQtyToDate(
                context, materialCode, productStatus, breakPointDate);
        result.setCrossMonth(Objects.nonNull(futurePlanDate)
                && (scheduleStartDate.getYear() != futurePlanDate.getYear()
                || scheduleStartDate.getMonthValue() != futurePlanDate.getMonthValue()));
        result.setBreakPointDate(breakPointDate);
        result.setCurrentMonthPlanTotal(result.isCrossMonth() ? 0 : monthPlanTotal);
        result.setCrossMonthPlanTotal(result.isCrossMonth() ? monthPlanTotal : 0);
        result.setMonthPlanTotal(monthPlanTotal);
        result.setCalculateScene(SCENE_NO_WINDOW_PLAN_FUTURE_SEGMENT);
    }

    private static LocalDate findLatestPlanDateInWindow(LhScheduleContext context,
                                                       String materialCode,
                                                       String productStatus,
                                                       LocalDate startDate,
                                                       LocalDate endDate) {
        LocalDate latestPlanDate = null;
        LocalDate cursor = startDate;
        while (!cursor.isAfter(endDate)) {
            if (MonthPlanDateResolver.hasPlanQty(
                    MonthPlanDateResolver.resolveDayQty(context, materialCode, productStatus, cursor))) {
                latestPlanDate = cursor;
            }
            cursor = cursor.plusDays(1);
        }
        return latestPlanDate;
    }

    private static LocalDate findLatestPlanDateInCurrentMonthWindow(LhScheduleContext context,
                                                                    String materialCode,
                                                                    String productStatus,
                                                                    LocalDate scheduleStartDate) {
        LocalDate monthEndDate = scheduleStartDate.withDayOfMonth(scheduleStartDate.lengthOfMonth());
        LocalDate latestPlanDate = null;
        LocalDate cursor = scheduleStartDate;
        while (!cursor.isAfter(monthEndDate)) {
            if (MonthPlanDateResolver.hasPlanQty(
                    MonthPlanDateResolver.resolveDayQty(context, materialCode, productStatus, cursor))) {
                latestPlanDate = cursor;
            }
            cursor = cursor.plusDays(1);
        }
        return latestPlanDate;
    }

    private static LocalDate findFirstFuturePlanDate(LhScheduleContext context,
                                                     String materialCode,
                                                     String productStatus,
                                                     LocalDate scheduleStartDate) {
        LocalDate scanEndDate = scheduleStartDate.withDayOfMonth(scheduleStartDate.lengthOfMonth());
        LocalDate cursor = scheduleStartDate;
        while (!cursor.isAfter(scanEndDate)) {
            if (MonthPlanDateResolver.hasPlanQty(
                    MonthPlanDateResolver.resolveDayQty(context, materialCode, productStatus, cursor))) {
                return cursor;
            }
            cursor = cursor.plusDays(1);
        }
        return null;
    }

    private static int calculateSegmentTotal(LhScheduleContext context,
                                             String materialCode,
                                             String productStatus,
                                             LocalDate latestPlanDate) {
        if (Objects.isNull(latestPlanDate)) {
            return 0;
        }
        LocalDate breakPointDate = MonthPlanDateResolver.findBreakPointDate(
                context, materialCode, productStatus, latestPlanDate);
        return MonthPlanDateResolver.sumMonthPlanQtyToDate(context, materialCode, productStatus, breakPointDate);
    }
}
