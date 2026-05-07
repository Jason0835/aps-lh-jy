package com.zlt.aps.lh.service.impl;

import com.zlt.aps.lh.api.constant.LhScheduleConstant;
import com.zlt.aps.lh.api.constant.LhScheduleParamConstant;
import com.zlt.aps.lh.api.domain.dto.MachineCleaningWindowDTO;
import com.zlt.aps.lh.api.domain.entity.LhMachineOnlineInfo;
import com.zlt.aps.lh.api.domain.entity.LhMouldCleanPlan;
import com.zlt.aps.lh.api.enums.CleaningTypeEnum;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.util.LhScheduleTimeUtil;
import com.zlt.aps.mdm.api.domain.entity.MdmDevicePlanShut;
import com.zlt.aps.mdm.api.domain.entity.MdmSkuMouldRel;
import com.zlt.aps.mdm.api.domain.entity.MdmWorkCalendar;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 模具清洗计划运行态排程服务。
 *
 * @author APS
 */
@Slf4j
@Component
public class LhCleaningScheduleService {

    /** 工作日历停产标记 */
    private static final String WORK_CALENDAR_STOP_FLAG = "0";
    /** 启用配置值 */
    private static final int ENABLED = 1;
    /** 小时分钟分隔数量 */
    private static final int TIME_PART_COUNT = 2;
    /** 每小时分钟数 */
    private static final int MINUTES_PER_HOUR = 60;
    /** 时间分隔符 */
    private static final String TIME_SEPARATOR = ":";
    /** 多值分隔符 */
    private static final String VALUE_SEPARATOR = ",";
    /** 清洗可用日期最大搜索天数 */
    private static final int CLEANING_SEARCH_MAX_DAYS = 60;

    /**
     * 按清洗约束生成本次排程实际生效的清洗窗口。
     *
     * @param context 排程上下文
     * @return 机台清洗窗口Map
     */
    public Map<String, List<MachineCleaningWindowDTO>> buildScheduledCleaningWindowMap(LhScheduleContext context) {
        Map<String, List<MachineCleaningWindowDTO>> cleaningWindowMap = new HashMap<>(16);
        if (Objects.isNull(context) || CollectionUtils.isEmpty(context.getCleaningPlanList())) {
            return cleaningWindowMap;
        }
        log.info("清洗计划约束采用运行态调度处理, 原始清洗计划数: {}", context.getCleaningPlanList().size());
        List<LhMouldCleanPlan> cleaningPlanList = new ArrayList<>(context.getCleaningPlanList());
        cleaningPlanList.sort(Comparator
                .comparing(this::resolveCleaningPlanSortTime, Comparator.nullsLast(Date::compareTo))
                .thenComparing(LhMouldCleanPlan::getLhCode, Comparator.nullsLast(String::compareTo)));
        Map<String, Integer> totalDailyCountMap = new HashMap<>(16);
        Map<String, Integer> dryIceDailyCountMap = new HashMap<>(16);
        Map<String, Integer> dryIceMorningCountMap = new HashMap<>(16);
        Map<String, Integer> dryIceAfternoonCountMap = new HashMap<>(16);
        Map<String, Integer> sandBlastDailyCountMap = new HashMap<>(16);
        for (LhMouldCleanPlan cleaningPlan : cleaningPlanList) {
            MachineCleaningWindowDTO cleaningWindow = scheduleCleaningWindow(context, cleaningPlan,
                    totalDailyCountMap, dryIceDailyCountMap, dryIceMorningCountMap,
                    dryIceAfternoonCountMap, sandBlastDailyCountMap);
            if (Objects.isNull(cleaningWindow)) {
                continue;
            }
            cleaningWindowMap.computeIfAbsent(cleaningWindow.getLhCode(), key -> new ArrayList<>()).add(cleaningWindow);
        }
        return cleaningWindowMap;
    }

    private Date resolveCleaningPlanSortTime(LhMouldCleanPlan cleaningPlan) {
        return cleaningPlan != null ? cleaningPlan.getCleanTime() : null;
    }

    private MachineCleaningWindowDTO scheduleCleaningWindow(LhScheduleContext context,
                                                            LhMouldCleanPlan cleaningPlan,
                                                            Map<String, Integer> totalDailyCountMap,
                                                            Map<String, Integer> dryIceDailyCountMap,
                                                            Map<String, Integer> dryIceMorningCountMap,
                                                            Map<String, Integer> dryIceAfternoonCountMap,
                                                            Map<String, Integer> sandBlastDailyCountMap) {
        if (!isValidCleaningPlan(cleaningPlan, cleaningPlan != null ? cleaningPlan.getCleanType() : null)) {
            return null;
        }
        String cleanType = cleaningPlan.getCleanType();
        Date candidateStartTime = resolvePreferredCleaningStartTime(context, cleaningPlan);
        int cleanDurationHours = resolveCleanDurationHours(context, cleanType);
        int readyDurationHours = resolveReadyDurationHours(context, cleanType, cleanDurationHours);
        if (Objects.isNull(candidateStartTime) || cleanDurationHours <= 0) {
            return null;
        }
        for (int attempt = 0; attempt < CLEANING_SEARCH_MAX_DAYS; attempt++) {
            candidateStartTime = normalizeCleaningStartTime(context, cleanType, candidateStartTime);
            Date adjustedStartTime = resolveAdjustedCleaningStartTime(
                    context, cleaningPlan.getLhCode(), candidateStartTime, cleanDurationHours);
            if (CleaningTypeEnum.SAND_BLAST.getCode().equals(cleanType)
                    && isSandBlastForbiddenDate(context, adjustedStartTime)) {
                candidateStartTime = resolvePreviousSandBlastCandidate(context, candidateStartTime);
                continue;
            }
            if (canScheduleCleaning(context, totalDailyCountMap, dryIceDailyCountMap, dryIceMorningCountMap,
                    dryIceAfternoonCountMap, sandBlastDailyCountMap, cleanType, adjustedStartTime)) {
                if (!isInScheduleWindow(context, adjustedStartTime)) {
                    log.info("清洗窗口调整到排程窗口外，本次不计入清洗占用, 机台: {}, 类型: {}, 原时间: {}, 调整后: {}",
                            cleaningPlan.getLhCode(), cleanType,
                            LhScheduleTimeUtil.formatDateTime(cleaningPlan.getCleanTime()),
                            LhScheduleTimeUtil.formatDateTime(adjustedStartTime));
                    return null;
                }
                increaseCleaningCapacityUsage(context, totalDailyCountMap, dryIceDailyCountMap, dryIceMorningCountMap,
                        dryIceAfternoonCountMap, sandBlastDailyCountMap, cleanType, adjustedStartTime);
                return buildCleaningWindow(context, cleaningPlan, adjustedStartTime, cleanDurationHours, readyDurationHours);
            }
            candidateStartTime = resolveNextCleaningCandidateTime(context, cleanType, adjustedStartTime);
        }
        log.warn("清洗窗口在搜索范围内未找到可用时段，本次不计入清洗占用, 机台: {}, 类型: {}, 原时间: {}",
                cleaningPlan.getLhCode(), cleanType, LhScheduleTimeUtil.formatDateTime(cleaningPlan.getCleanTime()));
        return null;
    }

    /**
     * 构建机台清洗时间窗口。
     *
     * @param context 排程上下文
     * @param cleaningPlan 模具清洗计划
     * @return 清洗时间窗口
     */
    private MachineCleaningWindowDTO buildCleaningWindow(LhScheduleContext context, LhMouldCleanPlan cleaningPlan) {
        if (cleaningPlan == null || cleaningPlan.getCleanTime() == null) {
            return null;
        }
        String cleanType = cleaningPlan.getCleanType();
        int cleanDurationHours;
        int readyDurationHours;
        Date originalCleanStartTime = cleaningPlan.getCleanTime();
        if (CleaningTypeEnum.DRY_ICE.getCode().equals(cleanType)) {
            cleanDurationHours = resolveCleanDurationHours(context, cleanType);
            readyDurationHours = cleanDurationHours;
            originalCleanStartTime = resolveDryIceWindowStartTime(context, cleaningPlan.getCleanTime());
        } else if (CleaningTypeEnum.SAND_BLAST.getCode().equals(cleanType)) {
            cleanDurationHours = resolveCleanDurationHours(context, cleanType);
            // 喷砂总停机口径用于班次扣减；机台再次可开产时间仍沿用喷砂清洗原时长，
            // 后续换模/换活字块时间继续由各自排产链路单独叠加，避免重复计时。
            readyDurationHours = resolveReadyDurationHours(context, cleanType, cleanDurationHours);
        } else {
            return null;
        }

        // 清洗计划与设备计划停机重叠时，清洗必须顺延到停机结束后执行。
        Date adjustedCleanStartTime = resolveAdjustedCleaningStartTime(
                context, cleaningPlan.getLhCode(), originalCleanStartTime, cleanDurationHours);

        return buildCleaningWindow(context, cleaningPlan, adjustedCleanStartTime, cleanDurationHours, readyDurationHours);
    }

    private MachineCleaningWindowDTO buildCleaningWindow(LhScheduleContext context,
                                                         LhMouldCleanPlan cleaningPlan,
                                                         Date adjustedCleanStartTime,
                                                         int cleanDurationHours,
                                                         int readyDurationHours) {
        MachineCleaningWindowDTO cleaningWindow = new MachineCleaningWindowDTO();
        cleaningWindow.setLhCode(cleaningPlan.getLhCode());
        cleaningWindow.setCleanType(cleaningPlan.getCleanType());
        cleaningWindow.setLeftRightMould(cleaningPlan.getLeftRightMould());
        cleaningWindow.setMouldCode(resolveCleaningMouldCode(context, cleaningPlan.getLhCode()));
        cleaningWindow.setCleanStartTime(adjustedCleanStartTime);
        cleaningWindow.setCleanEndTime(LhScheduleTimeUtil.addHours(adjustedCleanStartTime, cleanDurationHours));
        cleaningWindow.setReadyTime(LhScheduleTimeUtil.addHours(adjustedCleanStartTime, readyDurationHours));
        cleaningWindow.setDataSource(cleaningPlan.getDataSource());
        cleaningWindow.setRemark(cleaningPlan.getRemark());
        return cleaningWindow;
    }

    /**
     * 判断清洗计划是否为指定类型。
     *
     * @param cleaningPlan 清洗计划
     * @param cleanType 清洗类型
     * @return true-有效；false-无效
     */
    private boolean isValidCleaningPlan(LhMouldCleanPlan cleaningPlan, String cleanType) {
        return Objects.nonNull(cleaningPlan)
                && Objects.nonNull(cleaningPlan.getCleanTime())
                && StringUtils.isNotEmpty(cleaningPlan.getLhCode())
                && (CleaningTypeEnum.DRY_ICE.getCode().equals(cleaningPlan.getCleanType())
                || CleaningTypeEnum.SAND_BLAST.getCode().equals(cleaningPlan.getCleanType()))
                && StringUtils.equals(cleanType, cleaningPlan.getCleanType());
    }

    private Date resolvePreferredCleaningStartTime(LhScheduleContext context, LhMouldCleanPlan cleaningPlan) {
        if (Objects.isNull(cleaningPlan) || Objects.isNull(cleaningPlan.getCleanTime())) {
            return null;
        }
        if (CleaningTypeEnum.DRY_ICE.getCode().equals(cleaningPlan.getCleanType())) {
            return resolveDryIceWindowStartTime(context, cleaningPlan.getCleanTime());
        }
        if (CleaningTypeEnum.SAND_BLAST.getCode().equals(cleaningPlan.getCleanType())) {
            return resolvePreviousAvailableSandBlastStartTime(context, cleaningPlan.getCleanTime());
        }
        return cleaningPlan.getCleanTime();
    }

    private Date normalizeCleaningStartTime(LhScheduleContext context, String cleanType, Date candidateStartTime) {
        if (CleaningTypeEnum.DRY_ICE.getCode().equals(cleanType)) {
            return resolveDryIceWindowStartTime(context, candidateStartTime);
        }
        return candidateStartTime;
    }

    private int resolveCleanDurationHours(LhScheduleContext context, String cleanType) {
        if (CleaningTypeEnum.DRY_ICE.getCode().equals(cleanType)) {
            return context.getParamIntValue(LhScheduleParamConstant.DRY_ICE_DURATION_HOURS,
                    LhScheduleConstant.DRY_ICE_DURATION_HOURS);
        }
        if (CleaningTypeEnum.SAND_BLAST.getCode().equals(cleanType)) {
            return context.getParamIntValue(LhScheduleParamConstant.SAND_BLAST_WITH_INSPECTION_HOURS,
                    LhScheduleConstant.SAND_BLAST_WITH_INSPECTION_HOURS);
        }
        return 0;
    }

    private int resolveReadyDurationHours(LhScheduleContext context, String cleanType, int cleanDurationHours) {
        if (CleaningTypeEnum.SAND_BLAST.getCode().equals(cleanType)) {
            return context.getParamIntValue(LhScheduleParamConstant.SAND_BLAST_DURATION_HOURS,
                    LhScheduleConstant.SAND_BLAST_DURATION_HOURS);
        }
        return cleanDurationHours;
    }

    private boolean canScheduleCleaning(LhScheduleContext context,
                                        Map<String, Integer> totalDailyCountMap,
                                        Map<String, Integer> dryIceDailyCountMap,
                                        Map<String, Integer> dryIceMorningCountMap,
                                        Map<String, Integer> dryIceAfternoonCountMap,
                                        Map<String, Integer> sandBlastDailyCountMap,
                                        String cleanType,
                                        Date cleanStartTime) {
        String dateKey = LhScheduleTimeUtil.formatDate(cleanStartTime);
        int dryIceDailyLimit = context.getParamIntValue(
                LhScheduleParamConstant.DRY_ICE_DAILY_LIMIT, LhScheduleConstant.DRY_ICE_DAILY_LIMIT);
        int sandBlastDailyLimit = context.getParamIntValue(
                LhScheduleParamConstant.SAND_BLAST_DAILY_LIMIT, LhScheduleConstant.SAND_BLAST_DAILY_LIMIT);
        int totalDailyLimit = dryIceDailyLimit + sandBlastDailyLimit;
        if (totalDailyCountMap.getOrDefault(dateKey, 0) >= totalDailyLimit) {
            return false;
        }
        if (CleaningTypeEnum.SAND_BLAST.getCode().equals(cleanType)) {
            return sandBlastDailyCountMap.getOrDefault(dateKey, 0) < sandBlastDailyLimit;
        }
        if (!CleaningTypeEnum.DRY_ICE.getCode().equals(cleanType)
                || dryIceDailyCountMap.getOrDefault(dateKey, 0) >= dryIceDailyLimit) {
            return false;
        }
        if (LhScheduleTimeUtil.isMorningShift(context, cleanStartTime)) {
            int morningLimit = context.getParamIntValue(LhScheduleParamConstant.DRY_ICE_MORNING_SHIFT_LIMIT,
                    LhScheduleConstant.DRY_ICE_MORNING_SHIFT_LIMIT);
            return dryIceMorningCountMap.getOrDefault(dateKey, 0) < morningLimit;
        }
        if (LhScheduleTimeUtil.isAfternoonShift(context, cleanStartTime)) {
            int afternoonLimit = context.getParamIntValue(LhScheduleParamConstant.DRY_ICE_AFTERNOON_SHIFT_LIMIT,
                    LhScheduleConstant.DRY_ICE_AFTERNOON_SHIFT_LIMIT);
            return dryIceAfternoonCountMap.getOrDefault(dateKey, 0) < afternoonLimit;
        }
        return false;
    }

    private void increaseCleaningCapacityUsage(LhScheduleContext context,
                                               Map<String, Integer> totalDailyCountMap,
                                               Map<String, Integer> dryIceDailyCountMap,
                                               Map<String, Integer> dryIceMorningCountMap,
                                               Map<String, Integer> dryIceAfternoonCountMap,
                                               Map<String, Integer> sandBlastDailyCountMap,
                                               String cleanType,
                                               Date cleanStartTime) {
        String dateKey = LhScheduleTimeUtil.formatDate(cleanStartTime);
        increaseCount(totalDailyCountMap, dateKey);
        if (CleaningTypeEnum.SAND_BLAST.getCode().equals(cleanType)) {
            increaseCount(sandBlastDailyCountMap, dateKey);
            return;
        }
        if (CleaningTypeEnum.DRY_ICE.getCode().equals(cleanType)) {
            increaseCount(dryIceDailyCountMap, dateKey);
            if (LhScheduleTimeUtil.isMorningShift(context, cleanStartTime)) {
                increaseCount(dryIceMorningCountMap, dateKey);
            } else if (LhScheduleTimeUtil.isAfternoonShift(context, cleanStartTime)) {
                increaseCount(dryIceAfternoonCountMap, dateKey);
            }
        }
    }

    private void increaseCount(Map<String, Integer> countMap, String dateKey) {
        countMap.put(dateKey, countMap.getOrDefault(dateKey, 0) + 1);
    }

    private Date resolveNextCleaningCandidateTime(LhScheduleContext context, String cleanType, Date currentStartTime) {
        if (CleaningTypeEnum.DRY_ICE.getCode().equals(cleanType)) {
            if (LhScheduleTimeUtil.isMorningShift(context, currentStartTime)) {
                return LhScheduleTimeUtil.getAfternoonShiftStart(context, currentStartTime);
            }
            return LhScheduleTimeUtil.getMorningShiftStart(context, LhScheduleTimeUtil.addDays(currentStartTime, 1));
        }
        return resolveNextAvailableSandBlastCandidate(context, LhScheduleTimeUtil.addDays(currentStartTime, 1));
    }

    private boolean isInScheduleWindow(LhScheduleContext context, Date cleanStartTime) {
        if (Objects.isNull(context) || Objects.isNull(context.getScheduleDate()) || Objects.isNull(cleanStartTime)) {
            return true;
        }
        Date windowStart = LhScheduleTimeUtil.clearTime(context.getScheduleDate());
        Date windowEnd = LhScheduleTimeUtil.addDays(windowStart, LhScheduleTimeUtil.getScheduleDays(context));
        return !cleanStartTime.before(windowStart) && cleanStartTime.before(windowEnd);
    }

    private Date resolvePreviousSandBlastCandidate(LhScheduleContext context, Date candidateStartTime) {
        if (Objects.isNull(candidateStartTime)) {
            return null;
        }
        return resolvePreviousAvailableSandBlastStartTime(context, LhScheduleTimeUtil.addDays(candidateStartTime, -1));
    }

    private Date resolvePreviousAvailableSandBlastStartTime(LhScheduleContext context, Date candidateStartTime) {
        Date cursorTime = candidateStartTime;
        for (int attempt = 0; attempt < CLEANING_SEARCH_MAX_DAYS && Objects.nonNull(cursorTime); attempt++) {
            if (!isSandBlastForbiddenDate(context, cursorTime)) {
                return cursorTime;
            }
            cursorTime = LhScheduleTimeUtil.addDays(cursorTime, -1);
        }
        return cursorTime;
    }

    private Date resolveNextAvailableSandBlastCandidate(LhScheduleContext context, Date candidateStartTime) {
        Date cursorTime = candidateStartTime;
        for (int attempt = 0; attempt < CLEANING_SEARCH_MAX_DAYS && Objects.nonNull(cursorTime); attempt++) {
            if (!isSandBlastForbiddenDate(context, cursorTime)) {
                return cursorTime;
            }
            cursorTime = LhScheduleTimeUtil.addDays(cursorTime, 1);
        }
        return cursorTime;
    }

    private boolean isSandBlastForbiddenDate(LhScheduleContext context, Date cleanTime) {
        if (Objects.isNull(cleanTime)) {
            return false;
        }
        if (isSandBlastMaintenanceDate(context, cleanTime)) {
            return true;
        }
        if (isSunday(cleanTime)
                && context.getParamIntValue(LhScheduleParamConstant.SAND_BLAST_SKIP_SUNDAY_ENABLED,
                LhScheduleConstant.SAND_BLAST_SKIP_SUNDAY_ENABLED) == ENABLED) {
            return true;
        }
        return isHoliday(context, cleanTime)
                && context.getParamIntValue(LhScheduleParamConstant.SAND_BLAST_SKIP_HOLIDAY_ENABLED,
                LhScheduleConstant.SAND_BLAST_SKIP_HOLIDAY_ENABLED) == ENABLED;
    }

    /**
     * 生成干冰清洗窗口开始时间。
     *
     * @param context 排程上下文
     * @param cleanTime 清洗计划时间
     * @return 清洗窗口开始时间
     */
    private Date resolveDryIceWindowStartTime(LhScheduleContext context, Date cleanTime) {
        int startMinutes = parseTimeMinutes(context.getParamValue(LhScheduleParamConstant.DRY_ICE_WORK_START_TIME,
                LhScheduleConstant.DRY_ICE_WORK_START_TIME));
        int endMinutes = parseTimeMinutes(context.getParamValue(LhScheduleParamConstant.DRY_ICE_WORK_END_TIME,
                LhScheduleConstant.DRY_ICE_WORK_END_TIME));
        int currentMinutes = resolveDayMinutes(cleanTime);
        if (currentMinutes < startMinutes) {
            return buildTimeByMinutes(cleanTime, startMinutes);
        }
        if (currentMinutes > endMinutes) {
            return buildTimeByMinutes(LhScheduleTimeUtil.addDays(cleanTime, 1), startMinutes);
        }
        return cleanTime;
    }

    /**
     * 解析机台当前在机模具号。
     *
     * @param context 排程上下文
     * @param machineCode 机台编号
     * @return 模具号
     */
    private String resolveCleaningMouldCode(LhScheduleContext context, String machineCode) {
        if (Objects.isNull(context) || StringUtils.isEmpty(machineCode)) {
            return null;
        }
        LhMachineOnlineInfo onlineInfo = context.getMachineOnlineInfoMap().get(machineCode);
        if (Objects.isNull(onlineInfo) || StringUtils.isEmpty(onlineInfo.getMaterialCode())) {
            return null;
        }
        List<MdmSkuMouldRel> mouldRelList = context.getSkuMouldRelMap().get(onlineInfo.getMaterialCode());
        if (CollectionUtils.isEmpty(mouldRelList)) {
            return null;
        }
        return mouldRelList.stream()
                .map(MdmSkuMouldRel::getMouldCode)
                .filter(StringUtils::isNotEmpty)
                .distinct()
                .collect(Collectors.joining(VALUE_SEPARATOR));
    }

    /**
     * 判断是否为喷砂机维保日。
     *
     * @param context 排程上下文
     * @param cleanTime 清洗时间
     * @return true-维保日；false-非维保日
     */
    private boolean isSandBlastMaintenanceDate(LhScheduleContext context, Date cleanTime) {
        String maintenanceDates = context.getParamValue(LhScheduleParamConstant.SAND_BLAST_MAINTENANCE_DATES,
                LhScheduleConstant.SAND_BLAST_MAINTENANCE_DATES);
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(cleanTime);
        String dayOfMonth = String.valueOf(calendar.get(Calendar.DAY_OF_MONTH));
        for (String maintenanceDate : maintenanceDates.split(VALUE_SEPARATOR)) {
            if (StringUtils.equals(dayOfMonth, maintenanceDate.trim())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断是否为周日。
     *
     * @param cleanTime 清洗时间
     * @return true-周日；false-非周日
     */
    private boolean isSunday(Date cleanTime) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(cleanTime);
        return calendar.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY;
    }

    /**
     * 判断是否为节假日。
     *
     * @param context 排程上下文
     * @param cleanTime 清洗时间
     * @return true-节假日；false-非节假日
     */
    private boolean isHoliday(LhScheduleContext context, Date cleanTime) {
        if (CollectionUtils.isEmpty(context.getWorkCalendarList())) {
            return false;
        }
        String cleanDate = LhScheduleTimeUtil.formatDate(cleanTime);
        for (MdmWorkCalendar workCalendar : context.getWorkCalendarList()) {
            if (Objects.isNull(workCalendar)
                    || Objects.isNull(workCalendar.getProductionDate())
                    || !StringUtils.equals(cleanDate, LhScheduleTimeUtil.formatDate(workCalendar.getProductionDate()))) {
                continue;
            }
            return StringUtils.equals(WORK_CALENDAR_STOP_FLAG, workCalendar.getDayFlag());
        }
        return false;
    }

    /**
     * 解析 HH:mm 为当天分钟数。
     *
     * @param timeText 时间文本
     * @return 当天分钟数
     */
    private int parseTimeMinutes(String timeText) {
        if (StringUtils.isEmpty(timeText)) {
            throw new IllegalArgumentException("干冰清洗时间范围配置为空");
        }
        String[] parts = timeText.trim().split(TIME_SEPARATOR);
        if (parts.length != TIME_PART_COUNT) {
            throw new IllegalArgumentException("干冰清洗时间范围配置格式错误: " + timeText);
        }
        return Integer.parseInt(parts[0]) * MINUTES_PER_HOUR + Integer.parseInt(parts[1]);
    }

    /**
     * 获取指定时间的当天分钟数。
     *
     * @param date 时间
     * @return 当天分钟数
     */
    private int resolveDayMinutes(Date date) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(date);
        return calendar.get(Calendar.HOUR_OF_DAY) * MINUTES_PER_HOUR + calendar.get(Calendar.MINUTE);
    }

    /**
     * 按当天分钟数构建时间。
     *
     * @param baseDate 基准日期
     * @param minutes 当天分钟数
     * @return 时间
     */
    private Date buildTimeByMinutes(Date baseDate, int minutes) {
        return LhScheduleTimeUtil.buildTime(
                baseDate, minutes / MINUTES_PER_HOUR, minutes % MINUTES_PER_HOUR, 0);
    }

    /**
     * 解析清洗窗口的有效开始时刻。
     * <p>若清洗窗口与机台计划停机窗口重叠，则将清洗开始顺延到命中停机窗口的结束时刻。</p>
     *
     * @param context 排程上下文
     * @param machineCode 机台编码
     * @param originalStartTime 原始清洗开始时刻
     * @param cleanDurationHours 清洗时长（小时）
     * @return 顺延后的清洗开始时刻
     */
    private Date resolveAdjustedCleaningStartTime(LhScheduleContext context,
                                                  String machineCode,
                                                  Date originalStartTime,
                                                  int cleanDurationHours) {
        if (context == null
                || originalStartTime == null
                || cleanDurationHours <= 0
                || StringUtils.isEmpty(machineCode)
                || context.getDevicePlanShutList() == null
                || context.getDevicePlanShutList().isEmpty()) {
            return originalStartTime;
        }
        Date adjustedStartTime = originalStartTime;
        while (true) {
            Date adjustedEndTime = LhScheduleTimeUtil.addHours(adjustedStartTime, cleanDurationHours);
            Date latestOverlapStopEndTime = null;
            for (MdmDevicePlanShut planShut : context.getDevicePlanShutList()) {
                if (planShut == null
                        || !StringUtils.equals(machineCode, planShut.getMachineCode())
                        || planShut.getBeginDate() == null
                        || planShut.getEndDate() == null
                        || !planShut.getBeginDate().before(planShut.getEndDate())) {
                    continue;
                }
                // 命中重叠：停机开始早于清洗结束，且停机结束晚于清洗开始。
                if (planShut.getBeginDate().before(adjustedEndTime)
                        && planShut.getEndDate().after(adjustedStartTime)) {
                    latestOverlapStopEndTime = later(latestOverlapStopEndTime, planShut.getEndDate());
                }
            }
            if (latestOverlapStopEndTime == null || !latestOverlapStopEndTime.after(adjustedStartTime)) {
                return adjustedStartTime;
            }
            // 顺延到重叠停机窗口结束后，继续判断是否命中后续停机窗口。
            adjustedStartTime = latestOverlapStopEndTime;
        }
    }

    private Date later(Date current, Date candidate) {
        if (current == null) {
            return candidate;
        }
        if (candidate == null) {
            return current;
        }
        return candidate.after(current) ? candidate : current;
    }
}
