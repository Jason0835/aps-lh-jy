package com.zlt.aps.lh.util;

import com.zlt.aps.lh.api.constant.LhScheduleConstant;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.api.domain.dto.ShiftRuntimeState;
import com.zlt.aps.lh.api.domain.vo.LhShiftConfigVO;
import com.zlt.aps.lh.api.domain.entity.LhScheduleResult;
import com.zlt.aps.lh.api.util.ShiftBoundaryUtil;
import com.zlt.aps.lh.api.enums.ShiftEnum;

import cn.hutool.core.date.DatePattern;
import cn.hutool.core.date.DateUtil;

import java.time.ZoneId;
import org.springframework.util.CollectionUtils;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 硫化排程时间工具类
 * <p>提供班次时间计算、排程日期推算等通用时间处理方法</p>
 *
 * @author APS
 */
public final class LhScheduleTimeUtil {

    private static final ZoneId DEFAULT_ZONE = ZoneId.systemDefault();

    private LhScheduleTimeUtil() {
    }

    /**
     * 获取排程天数（优先硫化参数 SCHEDULE_DAYS，默认 {@link LhScheduleConstant#SCHEDULE_DAYS}）
     *
     * @param context 排程上下文，可为 null（返回默认值）
     * @return 天数，至少为 1
     */
    public static int getScheduleDays(LhScheduleContext context) {
        if (Objects.isNull(context) || Objects.isNull(context.getScheduleConfig())) {
            return Math.max(1, LhScheduleConstant.SCHEDULE_DAYS);
        }
        return context.getScheduleConfig().getScheduleDays();
    }

    /**
     * 填充班次跨自然日/月/年标记（默认系统时区）
     *
     * @param start    开始时间
     * @param end      结束时间
     * @param outDay   长度 1，输出是否跨自然日
     * @param outMonth 长度 1，输出是否跨自然月
     * @param outYear  长度 1，输出是否跨自然年
     */
    public static void fillCrossFlagsForShift(Date start, Date end,
            boolean[] outDay, boolean[] outMonth, boolean[] outYear) {
        ShiftBoundaryUtil.fillCrossFlags(start, end, outDay, outMonth, outYear);
    }

    /**
     * 模具计划等业务使用的「首班开始时间」：优先当前窗口第一班，否则回退 class1/目标日
     *
     * @param context 排程上下文
     * @param result  排程结果
     * @return 计划日时间
     */
    public static Date resolveFirstShiftStartForPlan(LhScheduleContext context, LhScheduleResult result) {
        if (context != null && !CollectionUtils.isEmpty(context.getScheduleWindowShifts())) {
            Date t = context.getScheduleWindowShifts().get(0).getShiftStartDateTime();
            if (t != null) {
                return t;
            }
        }
        if (result != null && result.getClass1StartTime() != null) {
            return result.getClass1StartTime();
        }
        return context != null ? context.getScheduleTargetDate() : null;
    }

    /**
     * 查找指定日期偏移下第一个早班的班次索引
     *
     * @param shifts     班次列表
     * @param dateOffset 相对 T 日偏移
     * @return 班次索引，未找到返回 null
     */
    public static Integer findFirstMorningShiftIndexWithOffset(List<LhShiftConfigVO> shifts, int dateOffset) {
        if (CollectionUtils.isEmpty(shifts)) {
            return null;
        }
        for (LhShiftConfigVO s : shifts) {
            if (s.isMorningShift() && s.getDateOffset() != null && s.getDateOffset() == dateOffset) {
                return s.getShiftIndex();
            }
        }
        return null;
    }

    /**
     * 查找指定日期偏移下第一个夜班的班次索引
     *
     * @param shifts     班次列表
     * @param dateOffset 相对 T 日偏移（如现行模板下「T+1 日夜班」为 1）
     * @return 班次索引，未找到返回 null
     */
    public static Integer findFirstNightShiftIndexWithOffset(List<LhShiftConfigVO> shifts, int dateOffset) {
        if (CollectionUtils.isEmpty(shifts)) {
            return null;
        }
        for (LhShiftConfigVO s : shifts) {
            if (s.isNightShift() && s.getDateOffset() != null && s.getDateOffset() == dateOffset) {
                return s.getShiftIndex();
            }
        }
        return null;
    }

    /**
     * 将绝对时刻格式化为 HH:mm:ss（{@link DateUtil#format}，与 JVM 默认时区一致）
     *
     * @param instant 时间
     * @return 字符串
     */
    private static String formatInstantToHms(Date instant) {
        return DateUtil.format(instant, DatePattern.NORM_TIME_PATTERN);
    }

    /**
     * 构建班次展示名称：T 日 / T+n 日 + 班次类型描述
     *
     * @param dateOffset 相对 T 日偏移
     * @param type       班次类型
     * @return 展示名称
     */
    private static String buildShiftName(int dateOffset, ShiftEnum type) {
        String prefix;
        if (dateOffset == 0) {
            prefix = "T日";
        } else {
            prefix = "T+" + dateOffset + "日";
        }
        return prefix + type.getDescription();
    }

    /**
     * 构造默认模板中的一条班次 VO（排程 T 日锚点 + 绝对时刻转 HH:mm:ss）
     *
     * @param context     排程上下文
     * @param scheduleT   排程 T 日
     * @param shiftIndex  班次索引
     * @param shiftType   班次类型
     * @param dateOffset  相对 T 日偏移
     * @param startTime   合成后的开始时刻
     * @param endTime     合成后的结束时刻
     * @return 班次 VO
     */
    private static LhShiftConfigVO buildDefaultShiftVo(LhScheduleContext context, Date scheduleT,
            int shiftIndex, ShiftEnum shiftType, int dateOffset, Date startTime, Date endTime) {
        LhShiftConfigVO vo = new LhShiftConfigVO();
        vo.setScheduleBaseDate(scheduleT);
        vo.setShiftIndex(shiftIndex);
        vo.setShiftType(shiftType.getCode());
        vo.setShiftCode(shiftType.getCode());
        vo.setDateOffset(dateOffset);
        vo.setStartTime(formatInstantToHms(startTime));
        vo.setEndTime(formatInstantToHms(endTime));
        vo.setShiftName(buildShiftName(dateOffset, shiftType));
        vo.setShiftDuration(getShiftDurationHours(context));
        return vo;
    }

    /**
     * 获取夜班开始小时（从参数或默认值）
     *
     * @param context 排程上下文
     * @return 夜班开始小时
     */
    public static int getNightStartHour(LhScheduleContext context) {
        if (Objects.isNull(context) || Objects.isNull(context.getScheduleConfig())) {
            return LhScheduleConstant.NIGHT_SHIFT_START_HOUR;
        }
        return context.getScheduleConfig().getNightStartHour();
    }

    /**
     * 获取早班开始小时（从参数或默认值）
     *
     * @param context 排程上下文
     * @return 早班开始小时
     */
    public static int getMorningStartHour(LhScheduleContext context) {
        if (Objects.isNull(context) || Objects.isNull(context.getScheduleConfig())) {
            return LhScheduleConstant.MORNING_SHIFT_START_HOUR;
        }
        return context.getScheduleConfig().getMorningStartHour();
    }

    /**
     * 获取中班开始小时（从参数或默认值）
     *
     * @param context 排程上下文
     * @return 中班开始小时
     */
    public static int getAfternoonStartHour(LhScheduleContext context) {
        if (Objects.isNull(context) || Objects.isNull(context.getScheduleConfig())) {
            return LhScheduleConstant.AFTERNOON_SHIFT_START_HOUR;
        }
        return context.getScheduleConfig().getAfternoonStartHour();
    }

    /**
     * 获取每班时长（从参数或默认值）
     *
     * @param context 排程上下文
     * @return 每班时长（小时）
     */
    public static int getShiftDurationHours(LhScheduleContext context) {
        if (Objects.isNull(context) || Objects.isNull(context.getScheduleConfig())) {
            return LhScheduleConstant.SHIFT_DURATION_HOURS;
        }
        return context.getScheduleConfig().getShiftDurationHours();
    }

    /**
     * 获取换模含预热总时长（小时）
     *
     * @param context 排程上下文
     * @return 换模总时长（小时）
     */
    public static int getMouldChangeTotalHours(LhScheduleContext context) {
        if (Objects.isNull(context) || Objects.isNull(context.getScheduleConfig())) {
            return LhScheduleConstant.MOULD_CHANGE_TOTAL_HOURS;
        }
        return context.getScheduleConfig().getMouldChangeTotalHours();
    }

    /**
     * 获取换活字块总耗时（小时）
     *
     * @param context 排程上下文
     * @return 换活字块总耗时（小时）
     */
    public static int getTypeBlockChangeTotalHours(LhScheduleContext context) {
        if (Objects.isNull(context) || Objects.isNull(context.getScheduleConfig())) {
            return LhScheduleConstant.TYPE_BLOCK_CHANGE_TOTAL_HOURS;
        }
        return context.getScheduleConfig().getTypeBlockChangeTotalHours();
    }

    /**
     * 获取首检时间（小时）
     *
     * @param context 排程上下文
     * @return 首检时间（小时）
     */
    public static int getFirstInspectionHours(LhScheduleContext context) {
        if (Objects.isNull(context) || Objects.isNull(context.getScheduleConfig())) {
            return LhScheduleConstant.FIRST_INSPECTION_HOURS;
        }
        return context.getScheduleConfig().getFirstInspectionHours();
    }

    /**
     * 获取禁止换模开始小时
     *
     * @param context 排程上下文
     * @return 禁止换模开始小时（默认20点）
     */
    public static int getNoMouldChangeStartHour(LhScheduleContext context) {
        if (Objects.isNull(context) || Objects.isNull(context.getScheduleConfig())) {
            return LhScheduleConstant.NO_MOULD_CHANGE_START_HOUR;
        }
        return context.getScheduleConfig().getNoMouldChangeStartHour();
    }

    /**
     * 获取每日换模总上限
     *
     * @param context 排程上下文
     * @return 每日换模总上限（默认15台）
     */
    public static int getDailyMouldChangeLimit(LhScheduleContext context) {
        if (Objects.isNull(context) || Objects.isNull(context.getScheduleConfig())) {
            return LhScheduleConstant.DEFAULT_DAILY_MOULD_CHANGE_LIMIT;
        }
        return context.getScheduleConfig().getDailyMouldChangeLimit();
    }

    /**
     * 获取早班换模上限
     *
     * @param context 排程上下文
     * @return 早班换模上限（默认8台）
     */
    public static int getMorningMouldChangeLimit(LhScheduleContext context) {
        if (Objects.isNull(context) || Objects.isNull(context.getScheduleConfig())) {
            return LhScheduleConstant.DEFAULT_MORNING_MOULD_CHANGE_LIMIT;
        }
        return context.getScheduleConfig().getMorningMouldChangeLimit();
    }

    /**
     * 获取中班换模上限
     *
     * @param context 排程上下文
     * @return 中班换模上限（默认7台）
     */
    public static int getAfternoonMouldChangeLimit(LhScheduleContext context) {
        if (Objects.isNull(context) || Objects.isNull(context.getScheduleConfig())) {
            return LhScheduleConstant.DEFAULT_AFTERNOON_MOULD_CHANGE_LIMIT;
        }
        return context.getScheduleConfig().getAfternoonMouldChangeLimit();
    }

    /**
     * 获取收尾判定天数（可配置，默认3天）
     *
     * @param context 排程上下文
     * @return 收尾判定天数
     */
    public static int getEndingDetectDays(LhScheduleContext context) {
        if (Objects.isNull(context) || Objects.isNull(context.getScheduleConfig())) {
            return LhScheduleConstant.DEFAULT_ENDING_DAYS;
        }
        return context.getScheduleConfig().getEndingDetectDays();
    }

    /**
     * 获取机台收尾时间容差（分钟）
     *
     * @param context 排程上下文
     * @return 收尾时间容差（分钟，默认20分钟）
     */
    public static int getEndingToleranceMinutes(LhScheduleContext context) {
        if (Objects.isNull(context) || Objects.isNull(context.getScheduleConfig())) {
            return LhScheduleConstant.DEFAULT_ENDING_TIME_TOLERANCE_MINUTES;
        }
        return context.getScheduleConfig().getEndingTimeToleranceMinutes();
    }

    /**
     * 根据排程日期（T日）获取排程班次信息列表
     * <p>
     * 若上下文已解析 {@link LhScheduleContext#getScheduleWindowShifts()} 则直接返回其副本；
     * 否则使用与现网一致的默认 8 班模板（三班两两 + 跨日夜班）。
     * </p>
     *
     * @param context      排程上下文
     * @param scheduleDate 排程日期（T日）
     * @return 班次列表（1～N，N≤8）
     */
    public static List<LhShiftConfigVO> getScheduleShifts(LhScheduleContext context, Date scheduleDate) {
        if (context != null && !CollectionUtils.isEmpty(context.getScheduleWindowShifts())) {
            return new ArrayList<>(context.getScheduleWindowShifts());
        }
        return buildDefaultScheduleShifts(context, scheduleDate);
    }

    /**
     * 构建默认 8 班模板（与历史硬编码逻辑一致，受硫化参数中小时与班时长影响）
     *
     * @param context      排程上下文
     * @param scheduleDate T 日
     * @return 8 个班次
     */
    public static List<LhShiftConfigVO> buildDefaultScheduleShifts(LhScheduleContext context, Date scheduleDate) {
        int morningHour = getMorningStartHour(context);
        int afternoonHour = getAfternoonStartHour(context);
        int nightHour = getNightStartHour(context);
        int shiftDuration = getShiftDurationHours(context);

        List<LhShiftConfigVO> shifts = new ArrayList<>(LhScheduleConstant.MAX_SHIFT_SLOT_COUNT);

        Date tPlus1Day = addDays(scheduleDate, 1);
        Date tPlus2Day = addDays(scheduleDate, 2);

        Date tDayMorningStart = buildTime(scheduleDate, morningHour, 0, 0);
        Date tDayMorningEnd = addHours(tDayMorningStart, shiftDuration);
        shifts.add(buildDefaultShiftVo(context, scheduleDate, 1, ShiftEnum.MORNING_SHIFT, 0, tDayMorningStart, tDayMorningEnd));

        Date tDayAfternoonStart = buildTime(scheduleDate, afternoonHour, 0, 0);
        Date tDayAfternoonEnd = addHours(tDayAfternoonStart, shiftDuration);
        shifts.add(buildDefaultShiftVo(context, scheduleDate, 2, ShiftEnum.AFTERNOON_SHIFT, 0, tDayAfternoonStart, tDayAfternoonEnd));

        Date tPlus1NightStart = buildTime(scheduleDate, nightHour, 0, 0);
        Date tPlus1NightEnd = buildTime(tPlus1Day, morningHour, 0, 0);
        shifts.add(buildDefaultShiftVo(context, scheduleDate, 3, ShiftEnum.NIGHT_SHIFT, 1, tPlus1NightStart, tPlus1NightEnd));

        Date tPlus1MorningStart = buildTime(tPlus1Day, morningHour, 0, 0);
        Date tPlus1MorningEnd = addHours(tPlus1MorningStart, shiftDuration);
        shifts.add(buildDefaultShiftVo(context, scheduleDate, 4, ShiftEnum.MORNING_SHIFT, 1, tPlus1MorningStart, tPlus1MorningEnd));

        Date tPlus1AfternoonStart = buildTime(tPlus1Day, afternoonHour, 0, 0);
        Date tPlus1AfternoonEnd = addHours(tPlus1AfternoonStart, shiftDuration);
        shifts.add(buildDefaultShiftVo(context, scheduleDate, 5, ShiftEnum.AFTERNOON_SHIFT, 1, tPlus1AfternoonStart, tPlus1AfternoonEnd));

        Date tPlus2NightStart = buildTime(tPlus1Day, nightHour, 0, 0);
        Date tPlus2NightEnd = buildTime(tPlus2Day, morningHour, 0, 0);
        shifts.add(buildDefaultShiftVo(context, scheduleDate, 6, ShiftEnum.NIGHT_SHIFT, 2, tPlus2NightStart, tPlus2NightEnd));

        Date tPlus2MorningStart = buildTime(tPlus2Day, morningHour, 0, 0);
        Date tPlus2MorningEnd = addHours(tPlus2MorningStart, shiftDuration);
        shifts.add(buildDefaultShiftVo(context, scheduleDate, 7, ShiftEnum.MORNING_SHIFT, 2, tPlus2MorningStart, tPlus2MorningEnd));

        Date tPlus2AfternoonStart = buildTime(tPlus2Day, afternoonHour, 0, 0);
        Date tPlus2AfternoonEnd = addHours(tPlus2AfternoonStart, shiftDuration);
        shifts.add(buildDefaultShiftVo(context, scheduleDate, 8, ShiftEnum.AFTERNOON_SHIFT, 2, tPlus2AfternoonStart, tPlus2AfternoonEnd));

        return shifts;
    }

    /**
     * 根据时间点判断所在班次索引（1-8）
     *
     * @param context      排程上下文
     * @param scheduleDate 排程日期（T日）
     * @param time         时间点
     * @return 班次索引（1-8），若不在任意班次内返回-1
     */
    public static int getShiftIndex(LhScheduleContext context, Date scheduleDate, Date time) {
        List<LhShiftConfigVO> shifts = getScheduleShifts(context, scheduleDate);
        for (LhShiftConfigVO shift : shifts) {
            Date st = shift.getShiftStartDateTime();
            Date en = shift.getShiftEndDateTime();
            if (st != null && en != null && !time.before(st) && time.before(en)) {
                return shift.getShiftIndex();
            }
        }
        return -1;
    }

    /**
     * 根据班次索引获取班次信息
     *
     * @param context      排程上下文
     * @param scheduleDate 排程日期（T日）
     * @param shiftIndex   班次索引（1-8）
     * @return 班次信息，未找到返回null
     */
    public static LhShiftConfigVO getShiftByIndex(LhScheduleContext context, Date scheduleDate, int shiftIndex) {
        List<LhShiftConfigVO> shifts = getScheduleShifts(context, scheduleDate);
        for (LhShiftConfigVO shift : shifts) {
            if (shift.getShiftIndex() != null && shift.getShiftIndex() == shiftIndex) {
                return shift;
            }
        }
        return null;
    }

    /**
     * 判断指定时间是否在禁止换模时段（20:00 - 次日6:00）
     *
     * @param context 排程上下文
     * @param time    时间点
     * @return true-禁止换模，false-可以换模
     */
    public static boolean isNoMouldChangeTime(LhScheduleContext context, Date time) {
        int noChangeStart = getNoMouldChangeStartHour(context);
        int morningHour = getMorningStartHour(context);
        Calendar cal = Calendar.getInstance();
        cal.setTime(time);
        int hour = cal.get(Calendar.HOUR_OF_DAY);
        int minute = cal.get(Calendar.MINUTE);
        int second = cal.get(Calendar.SECOND);
        int millisecond = cal.get(Calendar.MILLISECOND);
        // 禁止的是20:00之后到次日06:00之前，两个整点本身允许开始换模。
        boolean afterNoChangeStart = hour > noChangeStart
                || (hour == noChangeStart && (minute > 0 || second > 0 || millisecond > 0));
        return afterNoChangeStart || hour < morningHour;
    }

    /**
     * 禁止换模时段结束后，第一个可发起换模/切换的早班开始时刻。
     * <p>与跨日夜班口径一致：晚间段（≥禁止换模起始小时，默认 20:00）顺延到<strong>次日</strong>早班；
     * 凌晨段（&lt;早班起始小时，默认 6:00）属于同一跨日夜班的后半段，顺延到<strong>当日</strong>早班。</p>
     *
     * @param context  排程上下文
     * @param baseTime 当前处于禁止换模时段内的时间点
     * @return 下一个早班开始时间；context 或 baseTime 为 null 时返回 null
     */
    public static Date resolveNextMorningAfterNoMouldChangeWindow(LhScheduleContext context, Date baseTime) {
        if (context == null || baseTime == null) {
            return null;
        }
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(baseTime);
        int hour = calendar.get(Calendar.HOUR_OF_DAY);
        Date morningBaseDate = clearTime(baseTime);
        if (hour >= getNoMouldChangeStartHour(context)) {
            morningBaseDate = addDays(morningBaseDate, 1);
        }
        return buildTime(morningBaseDate, getMorningStartHour(context), 0, 0);
    }

    /**
     * 判断指定时间是否在早班时段
     *
     * @param context 排程上下文
     * @param time    时间点
     * @return true-早班时段
     */
    public static boolean isMorningShift(LhScheduleContext context, Date time) {
        int morningHour = getMorningStartHour(context);
        int afternoonHour = getAfternoonStartHour(context);
        Calendar cal = Calendar.getInstance();
        cal.setTime(time);
        int hour = cal.get(Calendar.HOUR_OF_DAY);
        return hour >= morningHour && hour < afternoonHour;
    }

    /**
     * 判断指定时间是否在中班时段
     *
     * @param context 排程上下文
     * @param time    时间点
     * @return true-中班时段
     */
    public static boolean isAfternoonShift(LhScheduleContext context, Date time) {
        int afternoonHour = getAfternoonStartHour(context);
        int nightHour = getNightStartHour(context);
        Calendar cal = Calendar.getInstance();
        cal.setTime(time);
        int hour = cal.get(Calendar.HOUR_OF_DAY);
        return hour >= afternoonHour && hour < nightHour;
    }

    /**
     * 判断指定时间是否在夜班时段
     *
     * @param context 排程上下文
     * @param time    时间点
     * @return true-夜班时段
     */
    public static boolean isNightShift(LhScheduleContext context, Date time) {
        int nightHour = getNightStartHour(context);
        int morningHour = getMorningStartHour(context);
        Calendar cal = Calendar.getInstance();
        cal.setTime(time);
        int hour = cal.get(Calendar.HOUR_OF_DAY);
        return hour >= nightHour || hour < morningHour;
    }

    /**
     * 获取某天早班开始时间
     *
     * @param context 排程上下文
     * @param date    日期
     * @return 早班开始时间
     */
    public static Date getMorningShiftStart(LhScheduleContext context, Date date) {
        return buildTime(date, getMorningStartHour(context), 0, 0);
    }

    /**
     * 获取某天中班开始时间
     *
     * @param context 排程上下文
     * @param date    日期
     * @return 中班开始时间
     */
    public static Date getAfternoonShiftStart(LhScheduleContext context, Date date) {
        return buildTime(date, getAfternoonStartHour(context), 0, 0);
    }

    /**
     * 获取某天夜班开始时间（当天22:00）
     *
     * @param context 排程上下文
     * @param date    日期
     * @return 夜班开始时间
     */
    public static Date getNightShiftStart(LhScheduleContext context, Date date) {
        return buildTime(date, getNightStartHour(context), 0, 0);
    }

    /**
     * 判断两个时间是否在同一天
     *
     * @param date1 日期1
     * @param date2 日期2
     * @return true-同一天
     */
    public static boolean isSameDay(Date date1, Date date2) {
        Calendar cal1 = Calendar.getInstance();
        cal1.setTime(date1);
        Calendar cal2 = Calendar.getInstance();
        cal2.setTime(date2);
        return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR)
                && cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR);
    }

    /**
     * 清除时间中的时分秒，只保留日期部分
     *
     * @param date 日期时间
     * @return 仅日期（00:00:00）
     */
    public static Date clearTime(Date date) {
        Calendar cal = Calendar.getInstance();
        cal.setTime(date);
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return cal.getTime();
    }

    /**
     * 在指定日期基础上加N天
     *
     * @param date 基础日期
     * @param days 天数
     * @return 加天后的日期
     */
    public static Date addDays(Date date, int days) {
        Calendar cal = Calendar.getInstance();
        cal.setTime(date);
        cal.add(Calendar.DAY_OF_MONTH, days);
        return cal.getTime();
    }

    /**
     * 在指定时间基础上加N小时
     *
     * @param time  基础时间
     * @param hours 小时数
     * @return 加小时后的时间
     */
    public static Date addHours(Date time, int hours) {
        Calendar cal = Calendar.getInstance();
        cal.setTime(time);
        cal.add(Calendar.HOUR_OF_DAY, hours);
        return cal.getTime();
    }

    /**
     * 在指定时间基础上加N分钟
     *
     * @param time    基础时间
     * @param minutes 分钟数
     * @return 加分钟后的时间
     */
    public static Date addMinutes(Date time, int minutes) {
        Calendar cal = Calendar.getInstance();
        cal.setTime(time);
        cal.add(Calendar.MINUTE, minutes);
        return cal.getTime();
    }

    /**
     * 构建指定日期+时:分:秒的时间对象
     *
     * @param date   日期（年月日取此参数）
     * @param hour   小时
     * @param minute 分钟
     * @param second 秒
     * @return 组合后的时间
     */
    public static Date buildTime(Date date, int hour, int minute, int second) {
        Calendar cal = Calendar.getInstance();
        cal.setTime(date);
        cal.set(Calendar.HOUR_OF_DAY, hour);
        cal.set(Calendar.MINUTE, minute);
        cal.set(Calendar.SECOND, second);
        cal.set(Calendar.MILLISECOND, 0);
        return cal.getTime();
    }

    /**
     * 按常见格式解析时间字符串。
     *
     * @param value 时间字符串
     * @return 解析结果，无法识别时返回 null
     */
    public static Date parseFlexibleDateTime(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        String text = value.trim();
        String[] patterns = new String[]{
                "yyyy-MM-dd HH:mm:ss",
                "yyyy-MM-dd HH:mm",
                "yyyy-MM-dd"
        };
        for (String pattern : patterns) {
            try {
                SimpleDateFormat format = new SimpleDateFormat(pattern);
                format.setLenient(false);
                return format.parse(text);
            } catch (ParseException ignored) {
                // 尝试下一个格式
            }
        }
        return null;
    }

    /**
     * 计算两个时间之间的秒数差
     *
     * @param start 开始时间
     * @param end   结束时间
     * @return 秒数差（可负）
     */
    public static long diffSeconds(Date start, Date end) {
        return (end.getTime() - start.getTime()) / 1000L;
    }

    /**
     * 计算两个时间之间的小时数差（向下取整）
     *
     * @param start 开始时间
     * @param end   结束时间
     * @return 小时数差（可负）
     */
    public static long diffHours(Date start, Date end) {
        return diffSeconds(start, end) / 3600L;
    }

    /**
     * 判断两个时间之差是否在指定分钟容差范围内（|time1 - time2| <= toleranceMinutes）
     *
     * @param time1             时间1
     * @param time2             时间2
     * @param toleranceMinutes  容差分钟数
     * @return true-在容差范围内
     */
    public static boolean withinTolerance(Date time1, Date time2, int toleranceMinutes) {
        long diffMs = Math.abs(time1.getTime() - time2.getTime());
        return diffMs <= (long) toleranceMinutes * 60 * 1000L;
    }

    /**
     * 获取日期字符串（yyyyMMdd格式）
     *
     * @param date 日期
     * @return 日期字符串，如"20260327"
     */
    public static String getDateStr(Date date) {
        Calendar cal = Calendar.getInstance();
        cal.setTime(date);
        int year = cal.get(Calendar.YEAR);
        int month = cal.get(Calendar.MONTH) + 1;
        int day = cal.get(Calendar.DAY_OF_MONTH);
        return String.format("%04d%02d%02d", year, month, day);
    }

    /**
     * 格式化日期（yyyy-MM-dd）。
     *
     * @param date 日期
     * @return 格式化字符串，入参为null时返回null
     */
    public static String formatDate(Date date) {
        if (Objects.isNull(date)) {
            return null;
        }
        return DateUtil.format(date, DatePattern.NORM_DATE_PATTERN);
    }

    /**
     * 格式化日期时间（yyyy-MM-dd HH:mm:ss）。
     *
     * @param date 日期时间
     * @return 格式化字符串，入参为null时返回null
     */
    public static String formatDateTime(Date date) {
        if (Objects.isNull(date)) {
            return null;
        }
        return DateUtil.format(date, DatePattern.NORM_DATETIME_PATTERN);
    }

    /**
     * 按当前班次列表初始化上下文中的班次运行态 Map（key=班次索引，顺序与班次列表一致）
     *
     * @param context 排程上下文
     * @param shifts  班次列表（通常为 8 班）
     */
    public static void initShiftRuntimeStateMap(LhScheduleContext context, List<LhShiftConfigVO> shifts) {
        if (context == null || shifts == null) {
            return;
        }
        int cap = shifts.size();
        Map<Integer, ShiftRuntimeState> map = new LinkedHashMap<>(Math.max(1, cap));
        for (LhShiftConfigVO shift : shifts) {
            ShiftRuntimeState s = new ShiftRuntimeState();
            s.setShiftIndex(shift.getShiftIndex());
            s.setAvailable(true);
            s.setRemainingCapacity(0);
            s.setUnavailableReason(null);
            map.put(shift.getShiftIndex(), s);
        }
        context.setShiftRuntimeStateMap(map);
    }
}
