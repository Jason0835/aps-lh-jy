package com.zlt.aps.lh.context;

import com.zlt.aps.lh.api.constant.LhScheduleConstant;
import com.zlt.aps.lh.api.constant.LhScheduleParamConstant;
import com.zlt.aps.lh.api.enums.ScheduleTargetModeEnum;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 硫化排程配置快照
 * <p>统一承载本次排程已解析的配置值，优先级：LhParams > 常量默认值</p>
 *
 * @author APS
 */
@Slf4j
public class LhScheduleConfig {

    /** 已解析的参数快照（值均为字符串，按需转换） */
    private final Map<String, String> resolvedParamMap;

    /**
     * 构造配置快照
     *
     * @param resolvedParamMap 已解析参数
     */
    public LhScheduleConfig(Map<String, String> resolvedParamMap) {
        this.resolvedParamMap = new HashMap<>(resolvedParamMap);
    }

    /**
     * 获取已解析参数快照
     *
     * @return 参数快照
     */
    public Map<String, String> getResolvedParamMap() {
        return Collections.unmodifiableMap(resolvedParamMap);
    }

    /**
     * 获取字符串参数值
     *
     * @param paramCode    参数编码
     * @param defaultValue 默认值
     * @return 参数值
     */
    public String getParamValue(String paramCode, String defaultValue) {
        String value = resolvedParamMap.get(paramCode);
        return StringUtils.isEmpty(value) ? defaultValue : value;
    }

    /**
     * 获取整数参数值
     *
     * @param paramCode    参数编码
     * @param defaultValue 默认值
     * @return 参数值
     */
    public int getParamIntValue(String paramCode, int defaultValue) {
        String value = resolvedParamMap.get(paramCode);
        if (StringUtils.isEmpty(value)) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * 获取浮点参数值
     *
     * @param paramCode    参数编码
     * @param defaultValue 默认值
     * @return 参数值
     */
    public double getParamDoubleValue(String paramCode, double defaultValue) {
        String value = resolvedParamMap.get(paramCode);
        if (StringUtils.isEmpty(value)) {
            return defaultValue;
        }
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * 获取高精度小数参数值。
     *
     * @param paramCode 参数编码
     * @param defaultValue 默认值
     * @return 参数值
     */
    public BigDecimal getParamBigDecimalValue(String paramCode, BigDecimal defaultValue) {
        String value = resolvedParamMap.get(paramCode);
        if (StringUtils.isEmpty(value)) {
            return defaultValue;
        }
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * 以下为常用业务参数的强类型快捷读取，避免调用方重复传参数编码与默认值。
     */
    public int getScheduleDays() {
        return Math.max(1, getParamIntValue(LhScheduleParamConstant.SCHEDULE_DAYS, LhScheduleConstant.SCHEDULE_DAYS));
    }

    public boolean isFullCapacitySchedulingEnabled() {
        return getParamIntValue(LhScheduleParamConstant.ENABLE_FULL_CAPACITY_SCHEDULING,
                LhScheduleConstant.ENABLE_FULL_CAPACITY_SCHEDULING) == 1;
    }

    public boolean isEndingBySurplusInFullModeEnabled() {
        return getParamIntValue(LhScheduleParamConstant.ENABLE_ENDING_BY_SURPLUS_IN_FULL_MODE,
                LhScheduleConstant.ENABLE_ENDING_BY_SURPLUS_IN_FULL_MODE) == 1;
    }

    /**
     * 判断是否启用强制重排。
     *
     * @return true-窗口内全部重排；false-滚动衔接排程
     */
    public boolean isForceRescheduleEnabled() {
        return getParamIntValue(LhScheduleParamConstant.FORCE_RESCHEDULE,
                LhScheduleConstant.FORCE_RESCHEDULE) == LhScheduleConstant.FORCE_RESCHEDULE_ENABLED;
    }

    public ScheduleTargetModeEnum getScheduleTargetMode() {
        return isFullCapacitySchedulingEnabled()
                ? ScheduleTargetModeEnum.CAPACITY_FULL
                : ScheduleTargetModeEnum.DEMAND_DRIVEN;
    }

    public int getNightStartHour() {
        return getParamIntValue(LhScheduleParamConstant.NIGHT_START_HOUR, LhScheduleConstant.NIGHT_SHIFT_START_HOUR);
    }

    public int getMorningStartHour() {
        return getParamIntValue(LhScheduleParamConstant.MORNING_START_HOUR, LhScheduleConstant.MORNING_SHIFT_START_HOUR);
    }

    public int getAfternoonStartHour() {
        return getParamIntValue(LhScheduleParamConstant.AFTERNOON_START_HOUR, LhScheduleConstant.AFTERNOON_SHIFT_START_HOUR);
    }

    public int getShiftDurationHours() {
        return getParamIntValue(LhScheduleParamConstant.SHIFT_DURATION_HOURS, LhScheduleConstant.SHIFT_DURATION_HOURS);
    }

    public int getNoMouldChangeStartHour() {
        return getParamIntValue(LhScheduleParamConstant.NO_MOULD_CHANGE_START_HOUR, LhScheduleConstant.NO_MOULD_CHANGE_START_HOUR);
    }

    public int getNoMouldChangeEndHour() {
        return getParamIntValue(LhScheduleParamConstant.NO_MOULD_CHANGE_END_HOUR, LhScheduleConstant.NO_MOULD_CHANGE_END_HOUR);
    }

    public int getDailyMouldChangeLimit() {
        return getParamIntValue(LhScheduleParamConstant.DAILY_MOULD_CHANGE_LIMIT, LhScheduleConstant.DEFAULT_DAILY_MOULD_CHANGE_LIMIT);
    }

    public int getMorningMouldChangeLimit() {
        return getParamIntValue(LhScheduleParamConstant.MORNING_MOULD_CHANGE_LIMIT, LhScheduleConstant.DEFAULT_MORNING_MOULD_CHANGE_LIMIT);
    }

    public int getAfternoonMouldChangeLimit() {
        return getParamIntValue(LhScheduleParamConstant.AFTERNOON_MOULD_CHANGE_LIMIT, LhScheduleConstant.DEFAULT_AFTERNOON_MOULD_CHANGE_LIMIT);
    }

    public int getMouldChangePreheatHours() {
        return getParamIntValue(LhScheduleParamConstant.MOULD_CHANGE_PREHEAT_HOURS, LhScheduleConstant.MOULD_CHANGE_PREHEAT_HOURS);
    }

    public int getMouldChangeOtherHours() {
        return getParamIntValue(LhScheduleParamConstant.MOULD_CHANGE_OTHER_HOURS, LhScheduleConstant.MOULD_CHANGE_OTHER_HOURS);
    }

    public int getMouldChangeTotalHours() {
        return getParamIntValue(LhScheduleParamConstant.MOULD_CHANGE_TOTAL_HOURS, LhScheduleConstant.MOULD_CHANGE_TOTAL_HOURS);
    }

    public int getTypeBlockChangeTotalHours() {
        return getParamIntValue(LhScheduleParamConstant.TYPE_BLOCK_CHANGE_TOTAL_HOURS, LhScheduleConstant.TYPE_BLOCK_CHANGE_TOTAL_HOURS);
    }

    public int getFirstInspectionHours() {
        return getParamIntValue(LhScheduleParamConstant.FIRST_INSPECTION_HOURS, LhScheduleConstant.FIRST_INSPECTION_HOURS);
    }

    public int getMaxFirstInspectionPerShift() {
        return getParamIntValue(LhScheduleParamConstant.MAX_FIRST_INSPECTION_PER_SHIFT, LhScheduleConstant.MAX_FIRST_INSPECTION_PER_SHIFT);
    }

    public int getFirstTwoFirstInspectionQty() {
        return Math.max(0, getParamIntValue(LhScheduleParamConstant.FIRST_TWO_FIRST_INSPECTION_QTY,
                LhScheduleConstant.FIRST_TWO_FIRST_INSPECTION_QTY));
    }

    public int getFirstInspectionQty() {
        return Math.max(0, getParamIntValue(LhScheduleParamConstant.FIRST_INSPECTION_QTY,
                LhScheduleConstant.FIRST_INSPECTION_QTY));
    }

    public int getEndingDetectDays() {
        return getParamIntValue(LhScheduleParamConstant.ENDING_DETECT_DAYS, LhScheduleConstant.DEFAULT_ENDING_DAYS);
    }

    public int getStructureEndingDays() {
        return getParamIntValue(LhScheduleParamConstant.STRUCTURE_ENDING_DAYS, LhScheduleConstant.DEFAULT_STRUCTURE_ENDING_DAYS);
    }

    public int getEndingTimeToleranceMinutes() {
        return getParamIntValue(LhScheduleParamConstant.ENDING_TIME_TOLERANCE_MINUTES,
                LhScheduleConstant.DEFAULT_ENDING_TIME_TOLERANCE_MINUTES);
    }

    public int getDryIceDurationHours() {
        return getParamIntValue(LhScheduleParamConstant.DRY_ICE_DURATION_HOURS, LhScheduleConstant.DRY_ICE_DURATION_HOURS);
    }

    public int getDryIceWarningDays() {
        return Math.max(0, getParamIntValue(LhScheduleParamConstant.DRY_ICE_WARNING_DAYS,
                LhScheduleConstant.DRY_ICE_WARNING_DAYS));
    }

    public int getDryIceAdvanceDays() {
        return Math.max(0, getParamIntValue(LhScheduleParamConstant.DRY_ICE_ADVANCE_DAYS,
                LhScheduleConstant.DRY_ICE_ADVANCE_DAYS));
    }

    public int getDryIceDailyLimit() {
        return Math.max(1, getParamIntValue(LhScheduleParamConstant.DRY_ICE_DAILY_LIMIT,
                LhScheduleConstant.DRY_ICE_DAILY_LIMIT));
    }

    public int getDryIceMorningShiftLimit() {
        return Math.max(0, getParamIntValue(LhScheduleParamConstant.DRY_ICE_MORNING_SHIFT_LIMIT,
                LhScheduleConstant.DRY_ICE_MORNING_SHIFT_LIMIT));
    }

    public int getDryIceAfternoonShiftLimit() {
        return Math.max(0, getParamIntValue(LhScheduleParamConstant.DRY_ICE_AFTERNOON_SHIFT_LIMIT,
                LhScheduleConstant.DRY_ICE_AFTERNOON_SHIFT_LIMIT));
    }

    public String getDryIceWorkStartTime() {
        return getParamValue(LhScheduleParamConstant.DRY_ICE_WORK_START_TIME, LhScheduleConstant.DRY_ICE_WORK_START_TIME);
    }

    public String getDryIceWorkEndTime() {
        return getParamValue(LhScheduleParamConstant.DRY_ICE_WORK_END_TIME, LhScheduleConstant.DRY_ICE_WORK_END_TIME);
    }

    public int getSandBlastDurationHours() {
        return getParamIntValue(LhScheduleParamConstant.SAND_BLAST_DURATION_HOURS, LhScheduleConstant.SAND_BLAST_DURATION_HOURS);
    }

    public int getSandBlastWarningDays() {
        return Math.max(0, getParamIntValue(LhScheduleParamConstant.SAND_BLAST_WARNING_DAYS,
                LhScheduleConstant.SAND_BLAST_WARNING_DAYS));
    }

    public int getSandBlastAdvanceDays() {
        return Math.max(0, getParamIntValue(LhScheduleParamConstant.SAND_BLAST_ADVANCE_DAYS,
                LhScheduleConstant.SAND_BLAST_ADVANCE_DAYS));
    }

    public int getSandBlastWithInspectionHours() {
        return getParamIntValue(LhScheduleParamConstant.SAND_BLAST_WITH_INSPECTION_HOURS,
                LhScheduleConstant.SAND_BLAST_WITH_INSPECTION_HOURS);
    }

    public int getSandBlastDailyLimit() {
        return Math.max(1, getParamIntValue(LhScheduleParamConstant.SAND_BLAST_DAILY_LIMIT,
                LhScheduleConstant.SAND_BLAST_DAILY_LIMIT));
    }

    public boolean isSandBlastSkipSundayEnabled() {
        return getParamIntValue(LhScheduleParamConstant.SAND_BLAST_SKIP_SUNDAY_ENABLED,
                LhScheduleConstant.SAND_BLAST_SKIP_SUNDAY_ENABLED) == 1;
    }

    public boolean isSandBlastSkipHolidayEnabled() {
        return getParamIntValue(LhScheduleParamConstant.SAND_BLAST_SKIP_HOLIDAY_ENABLED,
                LhScheduleConstant.SAND_BLAST_SKIP_HOLIDAY_ENABLED) == 1;
    }

    public String getSandBlastMaintenanceDates() {
        return getParamValue(LhScheduleParamConstant.SAND_BLAST_MAINTENANCE_DATES,
                LhScheduleConstant.SAND_BLAST_MAINTENANCE_DATES);
    }

    public boolean isSandBlastOnMaintenanceDateAllowed() {
        return getParamIntValue(LhScheduleParamConstant.SAND_BLAST_ALLOW_ON_MAINTENANCE_DATE,
                LhScheduleConstant.SAND_BLAST_ALLOW_ON_MAINTENANCE_DATE) == 1;
    }

    public boolean isSandBlastSundayManualAllowed() {
        return getParamIntValue(LhScheduleParamConstant.SAND_BLAST_ALLOW_SUNDAY_MANUAL_ENABLED,
                LhScheduleConstant.SAND_BLAST_ALLOW_SUNDAY_MANUAL_ENABLED) == 1;
    }

    public int getSandBlastSundayMinAlternatePlanCount() {
        return Math.max(0, getParamIntValue(LhScheduleParamConstant.SAND_BLAST_SUNDAY_MIN_ALTERNATE_PLAN_COUNT,
                LhScheduleConstant.SAND_BLAST_SUNDAY_MIN_ALTERNATE_PLAN_COUNT));
    }

    public int getMaintenanceDurationHours() {
        return getParamIntValue(LhScheduleParamConstant.MAINTENANCE_DURATION_HOURS, LhScheduleConstant.MAINTENANCE_DURATION_HOURS);
    }

    public int getMaintenanceStartHour() {
        return getParamIntValue(LhScheduleParamConstant.MAINTENANCE_START_HOUR, LhScheduleConstant.MAINTENANCE_START_HOUR);
    }

    public int getMaintenanceWarningDays() {
        return getParamIntValue(LhScheduleParamConstant.MAINTENANCE_WARNING_DAYS, LhScheduleConstant.MAINTENANCE_WARNING_DAYS);
    }

    public int getMaintenanceDailyLimit() {
        return Math.max(1, getParamIntValue(LhScheduleParamConstant.MAINTENANCE_DAILY_LIMIT,
                LhScheduleConstant.MAINTENANCE_DAILY_LIMIT));
    }

    public boolean isMaintenanceOnSundayAllowed() {
        return getParamIntValue(LhScheduleParamConstant.ALLOW_MAINTENANCE_ON_SUNDAY,
                LhScheduleConstant.ALLOW_MAINTENANCE_ON_SUNDAY) == 1;
    }

    public int getMaintenanceHolidayBlockDays() {
        return Math.max(0, getParamIntValue(LhScheduleParamConstant.MAINTENANCE_HOLIDAY_BLOCK_DAYS,
                LhScheduleConstant.MAINTENANCE_HOLIDAY_BLOCK_DAYS));
    }

    public int getMaintenanceForceCheckDays() {
        return Math.max(0, getParamIntValue(LhScheduleParamConstant.MAINTENANCE_FORCE_CHECK_DAYS,
                LhScheduleConstant.MAINTENANCE_FORCE_CHECK_DAYS));
    }

    public boolean isMaintenanceOnInventoryDayAllowed() {
        return getParamIntValue(LhScheduleParamConstant.ALLOW_MAINTENANCE_ON_INVENTORY_DAY,
                LhScheduleConstant.ALLOW_MAINTENANCE_ON_INVENTORY_DAY) == 1;
    }

    public double getCapsulePreheatHours() {
        return getParamDoubleValue(LhScheduleParamConstant.CAPSULE_PREHEAT_HOURS,
                LhScheduleConstant.CAPSULE_PREHEAT_HOURS.doubleValue());
    }

    public int getMaintenanceOverlapSwitchHours() {
        return getParamIntValue(LhScheduleParamConstant.MAINTENANCE_OVERLAP_SWITCH_HOURS,
                LhScheduleConstant.MAINTENANCE_OVERLAP_SWITCH_HOURS);
    }

    public int getMachineStopTimeoutHours() {
        return getParamIntValue(LhScheduleParamConstant.MACHINE_STOP_TIMEOUT_HOURS, LhScheduleConstant.MACHINE_STOP_TIMEOUT_HOURS);
    }

    /**
     * 判断是否启用硫化定点机台规则。
     *
     * @return true-启用；false-关闭
     */
    public boolean isSpecifyMachineRuleEnabled() {
        return getParamIntValue(LhScheduleParamConstant.ENABLE_SPECIFY_MACHINE_RULE,
                LhScheduleConstant.ENABLE_SPECIFY_MACHINE_RULE) == 1;
    }

    public int getMouldCleaningAdvanceDays() {
        return Math.max(0, getParamIntValue(LhScheduleParamConstant.MOULD_CLEANING_ADVANCE_DAYS,
                LhScheduleConstant.MOULD_CLEANING_ADVANCE_DAYS));
    }

    /**
     * 获取清洗跳过近收尾天数阈值。
     * <p>机台当前物料剩余排产天数 <= 该阈值时跳过干冰/喷砂清洗，设为 0 关闭此特性。</p>
     *
     * @return 阈值天数（最小 0）
     */
    public int getCleaningSkipEndingDayThreshold() {
        return Math.max(0, getParamIntValue(LhScheduleParamConstant.CLEANING_SKIP_ENDING_DAY_THRESHOLD,
                LhScheduleConstant.CLEANING_SKIP_ENDING_DAY_THRESHOLD));
    }

    public int getShutdownDayMinus3Rate() {
        return getParamIntValue(LhScheduleParamConstant.SHUTDOWN_DAY_MINUS_3_RATE, LhScheduleConstant.SHUTDOWN_DAY_MINUS_3_RATE);
    }

    public int getShutdownDayMinus2Rate() {
        return getParamIntValue(LhScheduleParamConstant.SHUTDOWN_DAY_MINUS_2_RATE, LhScheduleConstant.SHUTDOWN_DAY_MINUS_2_RATE);
    }

    public int getShutdownDayMinus1Rate() {
        return getParamIntValue(LhScheduleParamConstant.SHUTDOWN_DAY_MINUS_1_RATE, LhScheduleConstant.SHUTDOWN_DAY_MINUS_1_RATE);
    }

    public int getStartupFirstDayRate() {
        return getParamIntValue(LhScheduleParamConstant.STARTUP_FIRST_DAY_RATE, LhScheduleConstant.STARTUP_FIRST_DAY_RATE);
    }

    public boolean isOpenStopProductionControlEnabled() {
        return getParamIntValue(LhScheduleParamConstant.ENABLE_OPEN_STOP_PRODUCTION_CONTROL,
                LhScheduleConstant.ENABLE_OPEN_STOP_PRODUCTION_CONTROL) == 1;
    }

    public String getCuringOpenMoldTime() {
        return getParamValue(LhScheduleParamConstant.CURING_OPEN_MOLD_TIME, LhScheduleConstant.CURING_OPEN_MOLD_TIME);
    }

    public String getCuringStopPotTime() {
        return getParamValue(LhScheduleParamConstant.CURING_STOP_POT_TIME, LhScheduleConstant.CURING_STOP_POT_TIME);
    }

    public BigDecimal getOpenProductionShortageThresholdRate() {
        return getParamBigDecimalValue(LhScheduleParamConstant.OPEN_PRODUCTION_SHORTAGE_THRESHOLD_RATE,
                LhScheduleConstant.OPEN_PRODUCTION_SHORTAGE_THRESHOLD_RATE);
    }

    public String getOpenProductionWinterTireKeywords() {
        return getParamValue(LhScheduleParamConstant.OPEN_PRODUCTION_WINTER_TIRE_KEYWORDS,
                LhScheduleConstant.OPEN_PRODUCTION_WINTER_TIRE_KEYWORDS);
    }

    public int getTrialDailyLimit() {
        return getParamIntValue(LhScheduleParamConstant.TRIAL_DAILY_LIMIT, LhScheduleConstant.TRIAL_DAILY_LIMIT);
    }

    /**
     * @deprecated 单控基准机台已废弃，机台已在 T_LH_MACHINE_INFO 表中直接拆分为 L/R 后缀编码。
     *             该方法不再被生产代码使用，仅保留以兼容旧参数配置。
     */
    @Deprecated
    public String getSingleControlMachineCodes() {
        return getParamValue(LhScheduleParamConstant.SINGLE_CONTROL_MACHINE_CODES,
                LhScheduleConstant.SINGLE_CONTROL_MACHINE_CODES);
    }

    public int getSmallBatchSkuThreshold() {
        return Math.max(1, getParamIntValue(LhScheduleParamConstant.SMALL_BATCH_SKU_THRESHOLD,
                LhScheduleConstant.SMALL_BATCH_SKU_THRESHOLD));
    }

    public boolean isLocalSearchEnabled() {
        return getParamIntValue(LhScheduleParamConstant.ENABLE_LOCAL_SEARCH, LhScheduleConstant.ENABLE_LOCAL_SEARCH) == 1;
    }

    public int getLocalSearchMachineThreshold() {
        return Math.max(1, getParamIntValue(LhScheduleParamConstant.LOCAL_SEARCH_MACHINE_THRESHOLD,
                LhScheduleConstant.LOCAL_SEARCH_MACHINE_THRESHOLD));
    }

    public int getLocalSearchDepth() {
        return Math.max(1, getParamIntValue(LhScheduleParamConstant.LOCAL_SEARCH_DEPTH,
                LhScheduleConstant.LOCAL_SEARCH_DEPTH));
    }

    public int getLocalSearchTimeBudgetMs() {
        return Math.max(1, getParamIntValue(LhScheduleParamConstant.LOCAL_SEARCH_TIME_BUDGET_MS,
                LhScheduleConstant.LOCAL_SEARCH_TIME_BUDGET_MS));
    }

    public boolean isPriorityTraceLogEnabled() {
        return getParamIntValue(LhScheduleParamConstant.ENABLE_PRIORITY_TRACE_LOG,
                LhScheduleConstant.ENABLE_PRIORITY_TRACE_LOG) == 1;
    }

    /**
     * 全量SKU排序日志月计划起产日(beginDay)筛选阈值，仅输出月计划beginDay<=该值的SKU。
     *
     * @return 起产日阈值（日序号，缺失时取默认值）
     */
    public int getFullSkuSortLogBeginDayThreshold() {
        return getParamIntValue(LhScheduleParamConstant.FULL_SKU_SORT_LOG_BEGIN_DAY_THRESHOLD,
                LhScheduleConstant.DEFAULT_FULL_SKU_SORT_LOG_BEGIN_DAY);
    }

    public int getNewSpecShortageLookAheadDays() {
        return Math.max(1, getParamIntValue(LhScheduleParamConstant.NEW_SPEC_SHORTAGE_LOOK_AHEAD_DAYS,
                LhScheduleConstant.NEW_SPEC_SHORTAGE_LOOK_AHEAD_DAYS));
    }

    public int getNewSpecShortageAddMachineThreshold() {
        String value = resolvedParamMap.get(LhScheduleParamConstant.NEW_SPEC_SHORTAGE_ADD_MACHINE_THRESHOLD);
        if (StringUtils.isEmpty(value)) {
            log.warn("新增排产/续作补偿欠产增机台阈值缺失, paramCode: {}, 使用默认值: {}",
                    LhScheduleParamConstant.NEW_SPEC_SHORTAGE_ADD_MACHINE_THRESHOLD,
                    LhScheduleConstant.NEW_SPEC_SHORTAGE_ADD_MACHINE_THRESHOLD);
            return LhScheduleConstant.NEW_SPEC_SHORTAGE_ADD_MACHINE_THRESHOLD;
        }
        try {
            int threshold = Integer.parseInt(value.trim());
            if (threshold > 0) {
                return threshold;
            }
            log.warn("新增排产/续作补偿欠产增机台阈值配置异常, paramCode: {}, value: {}, 使用默认值: {}",
                    LhScheduleParamConstant.NEW_SPEC_SHORTAGE_ADD_MACHINE_THRESHOLD, value,
                    LhScheduleConstant.NEW_SPEC_SHORTAGE_ADD_MACHINE_THRESHOLD);
            return LhScheduleConstant.NEW_SPEC_SHORTAGE_ADD_MACHINE_THRESHOLD;
        } catch (NumberFormatException e) {
            log.warn("新增排产/续作补偿欠产增机台阈值解析失败, paramCode: {}, value: {}, 使用默认值: {}",
                    LhScheduleParamConstant.NEW_SPEC_SHORTAGE_ADD_MACHINE_THRESHOLD, value,
                    LhScheduleConstant.NEW_SPEC_SHORTAGE_ADD_MACHINE_THRESHOLD);
            return LhScheduleConstant.NEW_SPEC_SHORTAGE_ADD_MACHINE_THRESHOLD;
        }
    }

    /**
     * 获取收尾小余量允许欠产偏差值。
     *
     * <p>该参数用于 S4.4 续作和 S4.5 新增的收尾小余量场景，
     * 未配置、负数或非数字时按默认值处理。</p>
     *
     * @return 允许不排产的最大收尾余量
     */
    public int getContinuousEndingSurplusToleranceQty() {
        String value = resolvedParamMap.get(LhScheduleParamConstant.CONTINUOUS_ENDING_SURPLUS_TOLERANCE_QTY);
        if (StringUtils.isEmpty(value)) {
            return LhScheduleConstant.CONTINUOUS_ENDING_SURPLUS_TOLERANCE_QTY;
        }
        try {
            int toleranceQty = Integer.parseInt(value.trim());
            if (toleranceQty >= 0) {
                return toleranceQty;
            }
            log.warn("收尾小余量允许欠产偏差值配置异常, paramCode: {}, value: {}, 使用默认值: {}",
                    LhScheduleParamConstant.CONTINUOUS_ENDING_SURPLUS_TOLERANCE_QTY, value,
                    LhScheduleConstant.CONTINUOUS_ENDING_SURPLUS_TOLERANCE_QTY);
            return LhScheduleConstant.CONTINUOUS_ENDING_SURPLUS_TOLERANCE_QTY;
        } catch (NumberFormatException e) {
            log.warn("收尾小余量允许欠产偏差值解析失败, paramCode: {}, value: {}, 使用默认值: {}",
                    LhScheduleParamConstant.CONTINUOUS_ENDING_SURPLUS_TOLERANCE_QTY, value,
                    LhScheduleConstant.CONTINUOUS_ENDING_SURPLUS_TOLERANCE_QTY);
            return LhScheduleConstant.CONTINUOUS_ENDING_SURPLUS_TOLERANCE_QTY;
        }
    }

    /**
     * 获取奇数班产计划量加一班别配置。
     * <p>空值表示不启用；合法性由产能计算入口按 1/2/3 判断，非法值保持原班产口径。</p>
     *
     * @return 班别配置值
     */
    public String getOddShiftCapacityPlusShiftType() {
        return getParamValue(LhScheduleParamConstant.ODD_SHIFT_CAPACITY_PLUS_SHIFT_TYPE,
                LhScheduleConstant.ODD_SHIFT_CAPACITY_PLUS_SHIFT_TYPE);
    }

    /**
     * 获取日标准产量剩余班次配置。
     * <p>合法值：1-晚班，2-早班，3-中班；未配置或非法时默认中班。</p>
     *
     * @return 剩余班次配置值
     */
    public String getDailyStandardCapacityRemainShiftType() {
        int shiftType = getParamIntValue(LhScheduleParamConstant.DAILY_STANDARD_CAPACITY_REMAIN_SHIFT_TYPE,
                LhScheduleConstant.DAILY_STANDARD_CAPACITY_REMAIN_SHIFT_TYPE);
        if (shiftType == 1 || shiftType == 2 || shiftType == 3) {
            return String.valueOf(shiftType);
        }
        return String.valueOf(LhScheduleConstant.DAILY_STANDARD_CAPACITY_REMAIN_SHIFT_TYPE);
    }

    /**
     * 判断新增选机是否启用当天空闲机台优先。
     *
     * @return true-启用；false-关闭
     */
    public boolean isTodayIdleMachinePriorityEnabled() {
        return getParamIntValue(LhScheduleParamConstant.ENABLE_TODAY_IDLE_MACHINE_PRIORITY,
                LhScheduleConstant.ENABLE_TODAY_IDLE_MACHINE_PRIORITY) == 1;
    }

    /**
     * 判断新增排产是否启用换模均衡。
     *
     * @return true-启用；false-关闭
     */
    public boolean isChangeoverBalanceEnabled() {
        return getParamIntValue(LhScheduleParamConstant.ENABLE_CHANGEOVER_BALANCE,
                LhScheduleConstant.ENABLE_CHANGEOVER_BALANCE) == 1;
    }

    public int getContinuousShortageLookAheadDays() {
        return Math.max(0, getParamIntValue(LhScheduleParamConstant.CONTINUOUS_SHORTAGE_LOOK_AHEAD_DAYS,
                LhScheduleConstant.CONTINUOUS_SHORTAGE_LOOK_AHEAD_DAYS));
    }

    /**
     * 判断是否将本月历史欠产追加到当前排程窗口。
     *
     * @return true-追加；false-不追加
     */
    public boolean isCarryForwardQtyEnabled() {
        return getParamIntValue(LhScheduleParamConstant.ENABLE_CARRY_FORWARD_QTY,
                LhScheduleConstant.ENABLE_CARRY_FORWARD_QTY) == 1;
    }

    /**
     * 判断是否启用硫化示方历史保护。
     *
     * @return true-启用；false-关闭
     */
    public boolean isCureFormulaHistoryProtectEnabled() {
        return getParamIntValue(LhScheduleParamConstant.ENABLE_CURE_FORMULA_HISTORY_PROTECT,
                LhScheduleConstant.ENABLE_CURE_FORMULA_HISTORY_PROTECT) == 1;
    }
}
