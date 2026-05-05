package com.zlt.aps.lh.util;

import com.zlt.aps.mp.api.domain.entity.FactoryMonthPlanProductionFinalResult;

import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * 月计划日计划量工具。
 * <p>统一处理月计划表 {@code DAY_1～DAY_31} 的读取口径，避免业务代码分散拼日字段。</p>
 * <p>当前项目的窗口计划量语义是：按排程窗口覆盖到的自然日，逐日累加对应 {@code DAY_n}。</p>
 *
 * @author APS
 */
public final class MonthPlanDayQtyUtil {

    /** 月最小日序，月计划 DAY_n 从 1 开始。 */
    private static final int MIN_DAY_OF_MONTH = 1;
    /** 月最大日序，对应月计划表的 DAY_31。 */
    private static final int MAX_DAY_OF_MONTH = 31;
    /** 跨月排产提示文案。当前工具仅支持单月窗口聚合，不做跨月拆分。 */
    public static final String CROSS_MONTH_UNSUPPORTED_MESSAGE = "当前暂未开放跨月排产能力，技术团队正在紧急处理";
    /**
     * 日计划量读取器。
     * <p>按索引顺序映射 {@code DAY_1 -> DAY_31}，供 {@link #resolveDayQty(FactoryMonthPlanProductionFinalResult, int)}
     * 统一按日序读取，避免写大段 switch/if。</p>
     */
    private static final List<Function<FactoryMonthPlanProductionFinalResult, Integer>> DAY_QTY_GETTERS = Arrays.asList(
            FactoryMonthPlanProductionFinalResult::getDay1,
            FactoryMonthPlanProductionFinalResult::getDay2,
            FactoryMonthPlanProductionFinalResult::getDay3,
            FactoryMonthPlanProductionFinalResult::getDay4,
            FactoryMonthPlanProductionFinalResult::getDay5,
            FactoryMonthPlanProductionFinalResult::getDay6,
            FactoryMonthPlanProductionFinalResult::getDay7,
            FactoryMonthPlanProductionFinalResult::getDay8,
            FactoryMonthPlanProductionFinalResult::getDay9,
            FactoryMonthPlanProductionFinalResult::getDay10,
            FactoryMonthPlanProductionFinalResult::getDay11,
            FactoryMonthPlanProductionFinalResult::getDay12,
            FactoryMonthPlanProductionFinalResult::getDay13,
            FactoryMonthPlanProductionFinalResult::getDay14,
            FactoryMonthPlanProductionFinalResult::getDay15,
            FactoryMonthPlanProductionFinalResult::getDay16,
            FactoryMonthPlanProductionFinalResult::getDay17,
            FactoryMonthPlanProductionFinalResult::getDay18,
            FactoryMonthPlanProductionFinalResult::getDay19,
            FactoryMonthPlanProductionFinalResult::getDay20,
            FactoryMonthPlanProductionFinalResult::getDay21,
            FactoryMonthPlanProductionFinalResult::getDay22,
            FactoryMonthPlanProductionFinalResult::getDay23,
            FactoryMonthPlanProductionFinalResult::getDay24,
            FactoryMonthPlanProductionFinalResult::getDay25,
            FactoryMonthPlanProductionFinalResult::getDay26,
            FactoryMonthPlanProductionFinalResult::getDay27,
            FactoryMonthPlanProductionFinalResult::getDay28,
            FactoryMonthPlanProductionFinalResult::getDay29,
            FactoryMonthPlanProductionFinalResult::getDay30,
            FactoryMonthPlanProductionFinalResult::getDay31
    );

    private MonthPlanDayQtyUtil() {
    }

    /**
     * 判断排程窗口是否跨月。
     * <p>当前项目的月计划日量字段按自然月展开，因此窗口一旦跨月，就不能直接在同一条月计划记录里连续取值。</p>
     *
     * @param scheduleDate 排程窗口起点
     * @param scheduleTargetDate 排程窗口终点
     * @return true-跨月，false-同月
     */
    public static boolean isCrossMonthWindow(Date scheduleDate, Date scheduleTargetDate) {
        if (Objects.isNull(scheduleDate) || Objects.isNull(scheduleTargetDate)) {
            return false;
        }
        Calendar startCalendar = Calendar.getInstance();
        startCalendar.setTime(scheduleDate);
        Calendar targetCalendar = Calendar.getInstance();
        targetCalendar.setTime(scheduleTargetDate);
        return startCalendar.get(Calendar.YEAR) != targetCalendar.get(Calendar.YEAR)
                || startCalendar.get(Calendar.MONTH) != targetCalendar.get(Calendar.MONTH);
    }

    /**
     * 汇总排程窗口内的日计划量。
     * <p>口径：将窗口起止日期清零到自然日 0 点后，按自然日逐日读取对应 {@code DAY_n} 并累加。</p>
     * <p>例如窗口为 2026-04-21 ~ 2026-04-23，则返回 {@code DAY_21 + DAY_22 + DAY_23}。</p>
     *
     * @param plan 月计划记录
     * @param scheduleDate 排程窗口起点
     * @param scheduleTargetDate 排程窗口终点
     * @return 窗口内日计划量之和
     */
    public static int resolveWindowPlanQty(FactoryMonthPlanProductionFinalResult plan,
                                           Date scheduleDate,
                                           Date scheduleTargetDate) {
        if (Objects.isNull(plan) || Objects.isNull(scheduleDate) || Objects.isNull(scheduleTargetDate)) {
            return 0;
        }
        if (isCrossMonthWindow(scheduleDate, scheduleTargetDate)) {
            // 跨月时不能继续沿用单月 DAY_n 映射，否则会把下月日量错误映射到本月字段。
            throw new IllegalArgumentException(CROSS_MONTH_UNSUPPORTED_MESSAGE);
        }
        Date startDate = LhScheduleTimeUtil.clearTime(scheduleDate);
        Date endDate = LhScheduleTimeUtil.clearTime(scheduleTargetDate);
        if (startDate.after(endDate)) {
            return 0;
        }

        int totalQty = 0;
        // 按自然日顺序累加窗口内的 DAY_n。
        // 例如 T=2026-04-21、target=2026-04-23，则累计 DAY_21 + DAY_22 + DAY_23。
        Calendar cursor = Calendar.getInstance();
        cursor.setTime(startDate);
        Calendar endCalendar = Calendar.getInstance();
        endCalendar.setTime(endDate);
        while (!cursor.after(endCalendar)) {
            totalQty += resolveDayQty(plan, cursor.get(Calendar.DAY_OF_MONTH));
            cursor.add(Calendar.DAY_OF_MONTH, 1);
        }
        return totalQty;
    }

    /**
     * 获取指定自然日的计划量。
     * <p>仅按“几号”读取月计划中的 {@code DAY_n} 字段，不关心星期、班次等其他维度。</p>
     *
     * @param plan 月计划记录
     * @param dayOfMonth 日序（1～31）
     * @return 计划量，缺失按 0
     */
    public static int resolveDayQty(FactoryMonthPlanProductionFinalResult plan, int dayOfMonth) {
        if (Objects.isNull(plan) || dayOfMonth < MIN_DAY_OF_MONTH || dayOfMonth > MAX_DAY_OF_MONTH) {
            return 0;
        }
        // DAY_1 对应下标 0，因此这里统一做 dayOfMonth - 1 的映射。
        Integer qty = DAY_QTY_GETTERS.get(dayOfMonth - 1).apply(plan);
        return qty != null ? qty : 0;
    }

    /**
     * 获取月计划中最早存在计划量的自然日。
     * <p>常用于判断该物料在本月最早从哪一天开始进入排程视野，供窗口起点或前置校验复用。</p>
     *
     * @param plan 月计划记录
     * @return 最早有量日；不存在时返回 -1
     */
    public static int resolveFirstPlannedDay(FactoryMonthPlanProductionFinalResult plan) {
        if (Objects.isNull(plan)) {
            return -1;
        }
        // 从 DAY_1 顺序扫描到 DAY_31，命中首个正数计划量即返回。
        for (int dayOfMonth = MIN_DAY_OF_MONTH; dayOfMonth <= MAX_DAY_OF_MONTH; dayOfMonth++) {
            if (resolveDayQty(plan, dayOfMonth) > 0) {
                return dayOfMonth;
            }
        }
        return -1;
    }
}
