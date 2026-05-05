package com.zlt.aps.lh.engine.strategy.impl;

import cn.hutool.core.date.DatePattern;
import cn.hutool.core.date.DateUtil;
import com.zlt.aps.lh.api.constant.LhScheduleConstant;
import com.zlt.aps.lh.api.constant.LhScheduleParamConstant;
import com.zlt.aps.lh.api.domain.dto.ShiftProductionControlDTO;
import com.zlt.aps.lh.api.domain.dto.SkuScheduleDTO;
import com.zlt.aps.lh.api.domain.vo.LhShiftConfigVO;
import com.zlt.aps.lh.api.enums.ShiftEnum;
import com.zlt.aps.lh.context.LhScheduleConfig;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.engine.strategy.IProductionShutdownStrategy;
import com.zlt.aps.lh.util.LhScheduleTimeUtil;
import com.zlt.aps.lh.util.ShiftCapacityResolverUtil;
import com.zlt.aps.mdm.api.domain.entity.MdmWorkCalendar;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 默认开停产处理策略实现。
 * <p>根据工作日历、开模时间、停锅时间计算班次可排窗口和产能比例。</p>
 *
 * @author APS
 */
@Slf4j
@Component
public class DefaultProductionShutdownStrategy implements IProductionShutdownStrategy {

    /** 工作日历停产标记 */
    private static final String STOP_FLAG = "0";
    /** 工作日历满产比例 */
    private static final int FULL_RATE = 100;
    /** 百分比基准 */
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    /** 比例小数位 */
    private static final int RATE_SCALE = 2;
    /** 日期时间格式提示 */
    private static final String DATE_TIME_PATTERN_TEXT = "yyyy-MM-dd HH:mm:ss";
    /** 班次时间缺失 */
    private static final String REASON_SHIFT_TIME_MISSING = "班次时间缺失";
    /** 工作日历日期停产或节假日 */
    private static final String REASON_CALENDAR_DAY_STOP = "工作日历日期停产/节假日";
    /** 工作日历班次停产 */
    private static final String REASON_CALENDAR_SHIFT_STOP = "工作日历班次停产";
    /** 工作日历产能比例为0 */
    private static final String REASON_CALENDAR_RATE_ZERO = "工作日历产能比例为0";
    /** 开产班次未命中 */
    private static final String REASON_OPEN_SHIFT_NOT_FOUND = "开产班次未命中";
    /** 开产前不可排产 */
    private static final String REASON_BEFORE_OPEN_TIME = "开产时间前不可排产";
    /** 停锅时间后不可排产 */
    private static final String REASON_AFTER_STOP_TIME = "停锅时间后不可排产";

    @Override
    public void prepareOpenStopContext(LhScheduleContext context) {
        if (Objects.isNull(context)) {
            return;
        }
        LhScheduleConfig scheduleConfig = context.getScheduleConfig();
        boolean enableOpenStopControl = Objects.nonNull(scheduleConfig)
                && scheduleConfig.isOpenStopProductionControlEnabled();
        context.setEnableOpenStopProductionControl(enableOpenStopControl);
        context.setOpenProductionShortageThresholdRate(Objects.nonNull(scheduleConfig)
                ? scheduleConfig.getOpenProductionShortageThresholdRate()
                : LhScheduleConstant.OPEN_PRODUCTION_SHORTAGE_THRESHOLD_RATE);

        Date openMoldTime = enableOpenStopControl
                ? parseControlTime(scheduleConfig.getCuringOpenMoldTime(), "硫化开模时间") : null;
        Date stopPotTime = enableOpenStopControl
                ? parseControlTime(scheduleConfig.getCuringStopPotTime(), "硫化停锅时间") : null;
        context.setCuringOpenMoldTime(openMoldTime);
        context.setCuringStopPotTime(stopPotTime);
        context.setOpenProductionMode(Objects.nonNull(openMoldTime));
        context.setStopProductionMode(Objects.nonNull(stopPotTime));

        ShiftProductionControlDTO openShift = Objects.nonNull(openMoldTime)
                ? resolveOpenProductionShift(context, openMoldTime, LhScheduleConstant.PROC_CODE_LH) : null;
        ShiftProductionControlDTO stopShift = Objects.nonNull(stopPotTime)
                ? resolveStopProductionShift(context, stopPotTime, LhScheduleConstant.PROC_CODE_LH) : null;
        context.setOpenProductionShift(openShift);
        context.setStopProductionShift(stopShift);

        Map<Integer, ShiftProductionControlDTO> controlMap = buildShiftControlMap(context);
        context.setShiftProductionControlMap(controlMap);
        log.info("硫化开停产参数读取完成, enable={}, openMoldTime={}, stopPotTime={}, openShift={}, stopShift={}, shiftControls={}",
                enableOpenStopControl, LhScheduleTimeUtil.formatDateTime(openMoldTime),
                LhScheduleTimeUtil.formatDateTime(stopPotTime),
                Objects.nonNull(openShift) ? openShift.getShiftIndex() : null,
                Objects.nonNull(stopShift) ? stopShift.getShiftIndex() : null,
                controlMap.size());
    }

    @Override
    public ShiftProductionControlDTO resolveOpenProductionShift(LhScheduleContext context,
                                                                Date curingOpenMoldTime,
                                                                String processCode) {
        if (Objects.isNull(context) || Objects.isNull(curingOpenMoldTime)
                || CollectionUtils.isEmpty(context.getScheduleWindowShifts())) {
            return null;
        }
        for (LhShiftConfigVO shift : context.getScheduleWindowShifts()) {
            if (!isShiftAfterOrContains(shift, curingOpenMoldTime)) {
                continue;
            }
            if (shift.isNightShift()) {
                log.info("硫化开模时间落在夜班或夜班候选, openMoldTime={}, shiftIndex={}, 顺延",
                        LhScheduleTimeUtil.formatDateTime(curingOpenMoldTime), shift.getShiftIndex());
                continue;
            }
            ShiftProductionControlDTO control = buildCalendarControl(context, shift, processCode);
            if (!control.isCanSchedule()) {
                log.info("硫化开产班次候选不可排, openMoldTime={}, shiftIndex={}, reason={}",
                        LhScheduleTimeUtil.formatDateTime(curingOpenMoldTime),
                        shift.getShiftIndex(), control.getUnavailableReason());
                continue;
            }
            Date effectiveStartTime = curingOpenMoldTime.after(control.getShiftStartTime())
                    ? curingOpenMoldTime : control.getShiftStartTime();
            control.setEffectiveStartTime(effectiveStartTime);
            if (!effectiveStartTime.before(control.getEffectiveEndTime())) {
                control.setCanSchedule(false);
                control.setUnavailableReason(REASON_BEFORE_OPEN_TIME);
                continue;
            }
            log.info("硫化开产班次推算完成, openMoldTime={}, shiftDate={}, shiftCode={}, canSchedule={}",
                    LhScheduleTimeUtil.formatDateTime(curingOpenMoldTime),
                    LhScheduleTimeUtil.formatDate(control.getWorkDate()),
                    control.getShiftCode(), control.isCanSchedule());
            return control;
        }
        log.info("硫化开产班次未命中排程窗口, openMoldTime={}",
                LhScheduleTimeUtil.formatDateTime(curingOpenMoldTime));
        return null;
    }

    @Override
    public ShiftProductionControlDTO resolveStopProductionShift(LhScheduleContext context,
                                                                Date curingStopPotTime,
                                                                String processCode) {
        if (Objects.isNull(context) || Objects.isNull(curingStopPotTime)
                || CollectionUtils.isEmpty(context.getScheduleWindowShifts())) {
            return null;
        }
        for (LhShiftConfigVO shift : context.getScheduleWindowShifts()) {
            Date shiftStartTime = shift.getShiftStartDateTime();
            Date shiftEndTime = shift.getShiftEndDateTime();
            if (Objects.isNull(shiftStartTime) || Objects.isNull(shiftEndTime)
                    || curingStopPotTime.before(shiftStartTime)
                    || !curingStopPotTime.before(shiftEndTime)) {
                continue;
            }
            ShiftProductionControlDTO control = buildCalendarControl(context, shift, processCode);
            control.setEffectiveEndTime(curingStopPotTime);
            log.info("硫化停产班次推算完成, stopPotTime={}, shiftDate={}, shiftCode={}, cutoffTime={}",
                    LhScheduleTimeUtil.formatDateTime(curingStopPotTime),
                    LhScheduleTimeUtil.formatDate(control.getWorkDate()),
                    control.getShiftCode(), LhScheduleTimeUtil.formatDateTime(curingStopPotTime));
            return control;
        }
        log.info("硫化停产班次未命中排程窗口, stopPotTime={}",
                LhScheduleTimeUtil.formatDateTime(curingStopPotTime));
        return null;
    }

    @Override
    public ShiftProductionControlDTO resolveEffectiveShiftControl(LhScheduleContext context,
                                                                  LhShiftConfigVO shift,
                                                                  Date requestedStartTime) {
        if (Objects.isNull(context) || Objects.isNull(shift)) {
            return null;
        }
        ShiftProductionControlDTO baseControl = null;
        if (!CollectionUtils.isEmpty(context.getShiftProductionControlMap())) {
            baseControl = context.getShiftProductionControlMap().get(shift.getShiftIndex());
        }
        ShiftProductionControlDTO control = copyControl(Objects.nonNull(baseControl)
                ? baseControl : buildCalendarControl(context, shift, LhScheduleConstant.PROC_CODE_LH));
        if (!control.isCanSchedule()) {
            return control;
        }
        if (Objects.nonNull(requestedStartTime) && requestedStartTime.after(control.getEffectiveStartTime())) {
            control.setEffectiveStartTime(requestedStartTime);
        }
        if (!control.getEffectiveStartTime().before(control.getEffectiveEndTime())) {
            control.setCanSchedule(false);
            control.setUnavailableReason(REASON_BEFORE_OPEN_TIME);
        }
        return control;
    }

    @Override
    public int deductCapacityByOpenStopRule(ShiftProductionControlDTO control, int originalCapacity, int mouldQty) {
        if (Objects.isNull(control) || originalCapacity <= 0 || Objects.isNull(control.getCapacityRate())) {
            return Math.max(originalCapacity, 0);
        }
        if (control.getCapacityRate().compareTo(BigDecimal.ONE) >= 0) {
            return originalCapacity;
        }
        int adjusted = control.getCapacityRate()
                .multiply(BigDecimal.valueOf(originalCapacity))
                .setScale(0, RoundingMode.DOWN)
                .intValue();
        return ShiftCapacityResolverUtil.normalizeQtyToMouldMultiple(adjusted, mouldQty, true);
    }

    @Override
    public BigDecimal calculateShutdownRate(LhScheduleContext context, String machineCode, Date targetDate) {
        MdmWorkCalendar calendar = findWorkCalendar(context, targetDate);
        if (Objects.isNull(calendar)) {
            return BigDecimal.ONE;
        }

        // 若工作日历有配置比例，直接使用。
        BigDecimal calendarRate = resolveCalendarRate(calendar);
        if (calendarRate.compareTo(BigDecimal.ONE) < 0) {
            return calendarRate;
        }

        // 停产日返回0。
        if (isShutdownByCalendar(calendar)) {
            return BigDecimal.ZERO;
        }

        // 仅依据上下文已加载的排程窗口日历，向后查找停产递减比例。
        int maxAheadDays = Math.max(0, LhScheduleTimeUtil.getScheduleDays(context) - 1);
        for (int offset = 1; offset <= maxAheadDays; offset++) {
            Date futureDate = LhScheduleTimeUtil.addDays(targetDate, offset);
            MdmWorkCalendar futureCalendar = findWorkCalendar(context, futureDate);
            if (Objects.nonNull(futureCalendar) && isShutdownByCalendar(futureCalendar)) {
                switch (offset) {
                    case 1:
                        return BigDecimal.valueOf(context.getParamIntValue(LhScheduleParamConstant.SHUTDOWN_DAY_MINUS_1_RATE,
                                        LhScheduleConstant.SHUTDOWN_DAY_MINUS_1_RATE))
                                .divide(HUNDRED, RATE_SCALE, RoundingMode.HALF_UP);
                    case 2:
                        return BigDecimal.valueOf(context.getParamIntValue(LhScheduleParamConstant.SHUTDOWN_DAY_MINUS_2_RATE,
                                        LhScheduleConstant.SHUTDOWN_DAY_MINUS_2_RATE))
                                .divide(HUNDRED, RATE_SCALE, RoundingMode.HALF_UP);
                    default:
                        break;
                }
            }
        }

        return BigDecimal.ONE;
    }

    @Override
    public boolean isShutdownDay(LhScheduleContext context, String machineCode, Date targetDate) {
        MdmWorkCalendar calendar = findWorkCalendar(context, targetDate);
        return Objects.nonNull(calendar) && isShutdownByCalendar(calendar);
    }

    @Override
    public boolean isStartupDay(LhScheduleContext context, String machineCode, Date targetDate) {
        // 当天是开产，前一天是停产，则为开产首日。
        if (isShutdownDay(context, machineCode, targetDate)) {
            return false;
        }
        Date previousDay = LhScheduleTimeUtil.addDays(targetDate, -1);
        return isShutdownDay(context, machineCode, previousDay);
    }

    @Override
    public int adjustCapacityForShutdown(LhScheduleContext context, SkuScheduleDTO skuDto, int originalCapacity) {
        if (originalCapacity <= 0) {
            return 0;
        }
        Date targetDate = context.getScheduleDate();
        String machineCode = skuDto.getContinuousMachineCode();

        // 开产首日约50%产能。
        if (isStartupDay(context, machineCode, targetDate)) {
            int startupRate = context.getParamIntValue(LhScheduleParamConstant.STARTUP_FIRST_DAY_RATE,
                    LhScheduleConstant.STARTUP_FIRST_DAY_RATE);
            int adjusted = (int) (originalCapacity * startupRate / 100.0);
            log.debug("开产首日产能调整, 机台: {}, 原始: {}, 调整后: {}", machineCode, originalCapacity, adjusted);
            return adjusted;
        }

        // 按停产递减比例调整。
        BigDecimal rate = calculateShutdownRate(context, machineCode, targetDate);
        if (rate.compareTo(BigDecimal.ONE) < 0) {
            int adjusted = rate.multiply(BigDecimal.valueOf(originalCapacity)).setScale(0, RoundingMode.DOWN).intValue();
            log.debug("停产递减产能调整, 机台: {}, 比例: {}, 原始: {}, 调整后: {}", machineCode, rate, originalCapacity, adjusted);
            return adjusted;
        }

        return originalCapacity;
    }

    /**
     * 构建班次管控 Map。
     *
     * @param context 排程上下文
     * @return 班次管控 Map
     */
    private Map<Integer, ShiftProductionControlDTO> buildShiftControlMap(LhScheduleContext context) {
        List<LhShiftConfigVO> shifts = context.getScheduleWindowShifts();
        Map<Integer, ShiftProductionControlDTO> controlMap = new LinkedHashMap<>(
                CollectionUtils.isEmpty(shifts) ? 1 : shifts.size());
        if (CollectionUtils.isEmpty(shifts)) {
            return controlMap;
        }
        for (LhShiftConfigVO shift : shifts) {
            ShiftProductionControlDTO control = buildCalendarControl(context, shift, LhScheduleConstant.PROC_CODE_LH);
            applyOpenStopControl(context, control);
            controlMap.put(control.getShiftIndex(), control);
            log.debug("班次可排判断, shiftIndex={}, shiftCode={}, canSchedule={}, reason={}, effectiveStart={}, effectiveEnd={}, rate={}",
                    control.getShiftIndex(), control.getShiftCode(), control.isCanSchedule(),
                    control.getUnavailableReason(), LhScheduleTimeUtil.formatDateTime(control.getEffectiveStartTime()),
                    LhScheduleTimeUtil.formatDateTime(control.getEffectiveEndTime()), control.getCapacityRate());
        }
        return controlMap;
    }

    /**
     * 构建工作日历维度的班次管控信息。
     *
     * @param context 排程上下文
     * @param shift 班次
     * @param processCode 工序编码
     * @return 班次管控信息
     */
    private ShiftProductionControlDTO buildCalendarControl(LhScheduleContext context, LhShiftConfigVO shift, String processCode) {
        ShiftProductionControlDTO control = new ShiftProductionControlDTO();
        control.setShiftIndex(shift.getShiftIndex());
        control.setShiftCode(shift.getShiftType());
        control.setShiftName(shift.getShiftName());
        control.setWorkDate(shift.getWorkDate());
        control.setShiftStartTime(shift.getShiftStartDateTime());
        control.setShiftEndTime(shift.getShiftEndDateTime());
        control.setEffectiveStartTime(shift.getShiftStartDateTime());
        control.setEffectiveEndTime(shift.getShiftEndDateTime());
        control.setCanSchedule(true);
        control.setCapacityRate(BigDecimal.ONE);
        if (Objects.isNull(control.getShiftStartTime()) || Objects.isNull(control.getShiftEndTime())) {
            markUnavailable(control, REASON_SHIFT_TIME_MISSING);
            return control;
        }
        MdmWorkCalendar calendar = findWorkCalendar(context, shift.getWorkDate(), processCode);
        if (Objects.isNull(calendar)) {
            return control;
        }
        control.setCapacityRate(resolveCalendarRate(calendar));
        if (isShutdownByCalendar(calendar)) {
            markUnavailable(control, REASON_CALENDAR_DAY_STOP);
            return control;
        }
        if (isShiftStoppedByCalendar(calendar, shift)) {
            markUnavailable(control, REASON_CALENDAR_SHIFT_STOP);
            return control;
        }
        if (control.getCapacityRate().compareTo(BigDecimal.ZERO) <= 0) {
            markUnavailable(control, REASON_CALENDAR_RATE_ZERO);
        }
        return control;
    }

    /**
     * 应用开产与停产时间管控。
     *
     * @param context 排程上下文
     * @param control 班次管控信息
     * @return void
     */
    private void applyOpenStopControl(LhScheduleContext context, ShiftProductionControlDTO control) {
        if (!control.isCanSchedule()) {
            return;
        }
        applyOpenProductionControl(context, control);
        if (!control.isCanSchedule()) {
            return;
        }
        applyStopProductionControl(context, control);
    }

    /**
     * 应用开产管控。
     *
     * @param context 排程上下文
     * @param control 班次管控信息
     * @return void
     */
    private void applyOpenProductionControl(LhScheduleContext context, ShiftProductionControlDTO control) {
        if (!context.isOpenProductionMode()) {
            return;
        }
        ShiftProductionControlDTO openShift = context.getOpenProductionShift();
        if (Objects.isNull(openShift)) {
            markUnavailable(control, REASON_OPEN_SHIFT_NOT_FOUND);
            return;
        }
        Date openStartTime = openShift.getEffectiveStartTime();
        if (!control.getEffectiveEndTime().after(openStartTime)) {
            markUnavailable(control, REASON_BEFORE_OPEN_TIME);
            return;
        }
        if (control.getEffectiveStartTime().before(openStartTime)) {
            control.setEffectiveStartTime(openStartTime);
        }
        if (!control.getEffectiveStartTime().before(control.getEffectiveEndTime())) {
            markUnavailable(control, REASON_BEFORE_OPEN_TIME);
        }
    }

    /**
     * 应用停产管控。
     *
     * @param context 排程上下文
     * @param control 班次管控信息
     * @return void
     */
    private void applyStopProductionControl(LhScheduleContext context, ShiftProductionControlDTO control) {
        if (!context.isStopProductionMode()) {
            return;
        }
        Date stopPotTime = context.getCuringStopPotTime();
        if (Objects.isNull(stopPotTime)) {
            return;
        }
        if (!control.getEffectiveStartTime().before(stopPotTime)) {
            markUnavailable(control, REASON_AFTER_STOP_TIME);
            return;
        }
        if (control.getEffectiveEndTime().after(stopPotTime)) {
            control.setEffectiveEndTime(stopPotTime);
            log.info("停锅班次产能截断, shiftIndex={}, shiftCode={}, cutoffTime={}",
                    control.getShiftIndex(), control.getShiftCode(), LhScheduleTimeUtil.formatDateTime(stopPotTime));
        }
        if (!control.getEffectiveStartTime().before(control.getEffectiveEndTime())) {
            markUnavailable(control, REASON_AFTER_STOP_TIME);
        }
    }

    /**
     * 判断班次是否包含或晚于指定时间。
     *
     * @param shift 班次
     * @param dateTime 时间
     * @return true-包含或晚于，false-早于
     */
    private boolean isShiftAfterOrContains(LhShiftConfigVO shift, Date dateTime) {
        return Objects.nonNull(shift)
                && Objects.nonNull(shift.getShiftEndDateTime())
                && dateTime.before(shift.getShiftEndDateTime());
    }

    /**
     * 复制班次管控信息。
     *
     * @param source 原班次管控信息
     * @return 复制后的班次管控信息
     */
    private ShiftProductionControlDTO copyControl(ShiftProductionControlDTO source) {
        ShiftProductionControlDTO target = new ShiftProductionControlDTO();
        target.setShiftIndex(source.getShiftIndex());
        target.setShiftCode(source.getShiftCode());
        target.setShiftName(source.getShiftName());
        target.setWorkDate(source.getWorkDate());
        target.setShiftStartTime(source.getShiftStartTime());
        target.setShiftEndTime(source.getShiftEndTime());
        target.setEffectiveStartTime(source.getEffectiveStartTime());
        target.setEffectiveEndTime(source.getEffectiveEndTime());
        target.setCanSchedule(source.isCanSchedule());
        target.setCapacityRate(source.getCapacityRate());
        target.setUnavailableReason(source.getUnavailableReason());
        return target;
    }

    /**
     * 标记班次不可排。
     *
     * @param control 班次管控信息
     * @param reason 不可排原因
     * @return void
     */
    private void markUnavailable(ShiftProductionControlDTO control, String reason) {
        control.setCanSchedule(false);
        control.setUnavailableReason(reason);
        control.setEffectiveStartTime(control.getShiftStartTime());
        control.setEffectiveEndTime(control.getShiftStartTime());
    }

    /**
     * 解析管控时间。
     *
     * @param value 参数值
     * @param paramName 参数名称
     * @return 日期时间
     */
    private Date parseControlTime(String value, String paramName) {
        if (StringUtils.isEmpty(value)) {
            return null;
        }
        try {
            return DateUtil.parse(value.trim(), DatePattern.NORM_DATETIME_PATTERN);
        } catch (Exception e) {
            throw new IllegalArgumentException(paramName + "格式必须为" + DATE_TIME_PATTERN_TEXT + ", 当前值: " + value, e);
        }
    }

    /**
     * 从工作日历列表中查找指定日期的记录。
     *
     * @param context 排程上下文
     * @param targetDate 目标日期
     * @return 工作日历
     */
    private MdmWorkCalendar findWorkCalendar(LhScheduleContext context, Date targetDate) {
        return findWorkCalendar(context, targetDate, LhScheduleConstant.PROC_CODE_LH);
    }

    /**
     * 从工作日历列表中查找指定工序和日期的记录。
     *
     * @param context 排程上下文
     * @param targetDate 目标日期
     * @param processCode 工序编码
     * @return 工作日历
     */
    private MdmWorkCalendar findWorkCalendar(LhScheduleContext context, Date targetDate, String processCode) {
        if (Objects.isNull(context) || Objects.isNull(targetDate) || CollectionUtils.isEmpty(context.getWorkCalendarList())) {
            return null;
        }
        Calendar cal = Calendar.getInstance();
        cal.setTime(targetDate);
        int year = cal.get(Calendar.YEAR);
        int month = cal.get(Calendar.MONTH) + 1;
        int day = cal.get(Calendar.DAY_OF_MONTH);

        for (MdmWorkCalendar wc : context.getWorkCalendarList()) {
            if (StringUtils.equals(processCode, wc.getProcCode())
                    && year == (Objects.nonNull(wc.getYear()) ? wc.getYear() : 0)
                    && month == (Objects.nonNull(wc.getMonth()) ? wc.getMonth() : 0)
                    && day == (Objects.nonNull(wc.getDay()) ? wc.getDay() : 0)) {
                return wc;
            }
        }
        return null;
    }

    /**
     * 根据工作日历判断是否为停产日。
     * <p>dayFlag=0，或三班均为停产标志。</p>
     *
     * @param calendar 工作日历
     * @return true-停产，false-开产
     */
    private boolean isShutdownByCalendar(MdmWorkCalendar calendar) {
        if (StringUtils.equals(STOP_FLAG, calendar.getDayFlag())) {
            return true;
        }
        // 三班全部停产也视为停产日。
        boolean shift1Stop = StringUtils.equals(STOP_FLAG, calendar.getOneShiftFlag());
        boolean shift2Stop = StringUtils.equals(STOP_FLAG, calendar.getTwoShiftFlag());
        boolean shift3Stop = StringUtils.equals(STOP_FLAG, calendar.getThreeShiftFlag());
        return shift1Stop && shift2Stop && shift3Stop;
    }

    /**
     * 判断指定班次是否被工作日历停产。
     *
     * @param calendar 工作日历
     * @param shift 班次
     * @return true-停产，false-开产
     */
    private boolean isShiftStoppedByCalendar(MdmWorkCalendar calendar, LhShiftConfigVO shift) {
        ShiftEnum shiftEnum = shift.resolveShiftTypeEnum();
        if (ShiftEnum.NIGHT_SHIFT == shiftEnum) {
            return StringUtils.equals(STOP_FLAG, calendar.getOneShiftFlag());
        }
        if (ShiftEnum.MORNING_SHIFT == shiftEnum) {
            return StringUtils.equals(STOP_FLAG, calendar.getTwoShiftFlag());
        }
        if (ShiftEnum.AFTERNOON_SHIFT == shiftEnum) {
            return StringUtils.equals(STOP_FLAG, calendar.getThreeShiftFlag());
        }
        return false;
    }

    /**
     * 解析工作日历产能比例。
     *
     * @param calendar 工作日历
     * @return 产能比例
     */
    private BigDecimal resolveCalendarRate(MdmWorkCalendar calendar) {
        Integer rate = calendar.getRate();
        if (Objects.isNull(rate) || rate >= FULL_RATE) {
            return BigDecimal.ONE;
        }
        if (rate <= 0) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(rate).divide(HUNDRED, RATE_SCALE, RoundingMode.HALF_UP);
    }
}
