package com.zlt.aps.lh.component;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zlt.aps.lh.api.constant.LhScheduleConstant;
import com.zlt.aps.lh.api.constant.LhScheduleParamConstant;
import com.zlt.aps.lh.api.domain.entity.LhParams;
import com.zlt.aps.lh.api.enums.DeleteFlagEnum;
import com.zlt.aps.lh.context.LhScheduleConfig;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.mapper.LhParamsMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 硫化排程配置解析器
 * <p>统一将 LhParams 与常量默认值解析为排程配置快照</p>
 *
 * @author APS
 */
@Slf4j
@Component
public class LhScheduleConfigResolver {

    /** 参数 Map 预估容量，减少扩容开销 */
    private static final int DEFAULT_PARAM_CAPACITY = 64;

    @Resource
    private LhParamsMapper lhParamsMapper;

    /**
     * 解析并挂载排程配置
     *
     * @param context 排程上下文
     */
    public void resolveAndAttach(LhScheduleContext context) {
        Map<String, String> lhParamsMap = loadLhParams(context.getFactoryCode());
        context.setLhParamsMap(new HashMap<>(lhParamsMap));
        context.setScheduleConfig(buildConfig(lhParamsMap));
    }

    /**
     * 按工厂加载原始硫化参数
     *
     * @param factoryCode 工厂编码
     * @return 原始参数 Map
     */
    private Map<String, String> loadLhParams(String factoryCode) {
        Map<String, String> lhParamsMap = new HashMap<>(DEFAULT_PARAM_CAPACITY);
        List<LhParams> paramsList = lhParamsMapper.selectList(
                new LambdaQueryWrapper<LhParams>()
                        .eq(LhParams::getFactoryCode, factoryCode)
                        .eq(LhParams::getIsDelete, DeleteFlagEnum.NORMAL.getCode()));
        if (paramsList != null) {
            for (LhParams param : paramsList) {
                if (StringUtils.isNotEmpty(param.getParamCode()) && StringUtils.isNotEmpty(param.getParamValue())) {
                    lhParamsMap.put(param.getParamCode(), param.getParamValue());
                }
            }
        }
        log.info("硫化参数加载完成, 工厂: {}, 参数数量: {}", factoryCode, lhParamsMap.size());
        return lhParamsMap;
    }

    /**
     * 生成“已解析配置快照”
     * <p>每个参数都按“LhParams -> 常量默认值”落地为最终可用值</p>
     *
     * @param lhParamsMap 原始参数
     * @return 已解析配置
     */
    private LhScheduleConfig buildConfig(Map<String, String> lhParamsMap) {
        Map<String, String> resolvedParamMap = new HashMap<>(DEFAULT_PARAM_CAPACITY);

        // 班次与时间窗口参数
        putIntValue(resolvedParamMap, lhParamsMap, LhScheduleParamConstant.NIGHT_START_HOUR, LhScheduleConstant.NIGHT_SHIFT_START_HOUR);
        putIntValue(resolvedParamMap, lhParamsMap, LhScheduleParamConstant.MORNING_START_HOUR, LhScheduleConstant.MORNING_SHIFT_START_HOUR);
        putIntValue(resolvedParamMap, lhParamsMap, LhScheduleParamConstant.AFTERNOON_START_HOUR, LhScheduleConstant.AFTERNOON_SHIFT_START_HOUR);
        putIntValue(resolvedParamMap, lhParamsMap, LhScheduleParamConstant.SHIFT_DURATION_HOURS, LhScheduleConstant.SHIFT_DURATION_HOURS);
        putIntValue(resolvedParamMap, lhParamsMap, LhScheduleParamConstant.NO_MOULD_CHANGE_START_HOUR,
                LhScheduleConstant.NO_MOULD_CHANGE_START_HOUR);
        putIntValue(resolvedParamMap, lhParamsMap, LhScheduleParamConstant.NO_MOULD_CHANGE_END_HOUR,
                LhScheduleConstant.NO_MOULD_CHANGE_END_HOUR);

        // 换模与首检参数
        putIntValue(resolvedParamMap, lhParamsMap, LhScheduleParamConstant.DAILY_MOULD_CHANGE_LIMIT,
                LhScheduleConstant.DEFAULT_DAILY_MOULD_CHANGE_LIMIT);
        putIntValue(resolvedParamMap, lhParamsMap, LhScheduleParamConstant.MORNING_MOULD_CHANGE_LIMIT,
                LhScheduleConstant.DEFAULT_MORNING_MOULD_CHANGE_LIMIT);
        putIntValue(resolvedParamMap, lhParamsMap, LhScheduleParamConstant.AFTERNOON_MOULD_CHANGE_LIMIT,
                LhScheduleConstant.DEFAULT_AFTERNOON_MOULD_CHANGE_LIMIT);
        putIntValue(resolvedParamMap, lhParamsMap, LhScheduleParamConstant.MOULD_CHANGE_PREHEAT_HOURS,
                LhScheduleConstant.MOULD_CHANGE_PREHEAT_HOURS);
        putIntValue(resolvedParamMap, lhParamsMap, LhScheduleParamConstant.MOULD_CHANGE_OTHER_HOURS,
                LhScheduleConstant.MOULD_CHANGE_OTHER_HOURS);
        putIntValue(resolvedParamMap, lhParamsMap, LhScheduleParamConstant.MOULD_CHANGE_TOTAL_HOURS,
                LhScheduleConstant.MOULD_CHANGE_TOTAL_HOURS);
        putIntValue(resolvedParamMap, lhParamsMap, LhScheduleParamConstant.TYPE_BLOCK_CHANGE_TOTAL_HOURS,
                LhScheduleConstant.TYPE_BLOCK_CHANGE_TOTAL_HOURS);
        putIntValue(resolvedParamMap, lhParamsMap, LhScheduleParamConstant.FIRST_INSPECTION_HOURS,
                LhScheduleConstant.FIRST_INSPECTION_HOURS);
        putIntValue(resolvedParamMap, lhParamsMap, LhScheduleParamConstant.MAX_FIRST_INSPECTION_PER_SHIFT,
                LhScheduleConstant.MAX_FIRST_INSPECTION_PER_SHIFT);
        putIntValue(resolvedParamMap, lhParamsMap, LhScheduleParamConstant.FIRST_INSPECTION_QTY,
                LhScheduleConstant.FIRST_INSPECTION_QTY, 0);

        // 收尾判定参数
        putIntValue(resolvedParamMap, lhParamsMap, LhScheduleParamConstant.ENDING_DETECT_DAYS, LhScheduleConstant.DEFAULT_ENDING_DAYS);
        putIntValue(resolvedParamMap, lhParamsMap, LhScheduleParamConstant.STRUCTURE_ENDING_DAYS,
                LhScheduleConstant.DEFAULT_STRUCTURE_ENDING_DAYS);
        putIntValue(resolvedParamMap, lhParamsMap, LhScheduleParamConstant.ENDING_TIME_TOLERANCE_MINUTES,
                LhScheduleConstant.DEFAULT_ENDING_TIME_TOLERANCE_MINUTES);

        // 清洗与保养参数
        putIntValue(resolvedParamMap, lhParamsMap, LhScheduleParamConstant.DRY_ICE_WARNING_DAYS,
                LhScheduleConstant.DRY_ICE_WARNING_DAYS, 0);
        putIntValue(resolvedParamMap, lhParamsMap, LhScheduleParamConstant.DRY_ICE_ADVANCE_DAYS,
                LhScheduleConstant.DRY_ICE_ADVANCE_DAYS, 0);
        putIntValue(resolvedParamMap, lhParamsMap, LhScheduleParamConstant.DRY_ICE_DURATION_HOURS, LhScheduleConstant.DRY_ICE_DURATION_HOURS);
        putIntValue(resolvedParamMap, lhParamsMap, LhScheduleParamConstant.DRY_ICE_DAILY_LIMIT,
                LhScheduleConstant.DRY_ICE_DAILY_LIMIT, 1);
        putIntValue(resolvedParamMap, lhParamsMap, LhScheduleParamConstant.DRY_ICE_MORNING_SHIFT_LIMIT,
                LhScheduleConstant.DRY_ICE_MORNING_SHIFT_LIMIT, 0);
        putIntValue(resolvedParamMap, lhParamsMap, LhScheduleParamConstant.DRY_ICE_AFTERNOON_SHIFT_LIMIT,
                LhScheduleConstant.DRY_ICE_AFTERNOON_SHIFT_LIMIT, 0);
        putStringValue(resolvedParamMap, lhParamsMap, LhScheduleParamConstant.DRY_ICE_WORK_START_TIME,
                LhScheduleConstant.DRY_ICE_WORK_START_TIME);
        putStringValue(resolvedParamMap, lhParamsMap, LhScheduleParamConstant.DRY_ICE_WORK_END_TIME,
                LhScheduleConstant.DRY_ICE_WORK_END_TIME);
        putIntValue(resolvedParamMap, lhParamsMap, LhScheduleParamConstant.SAND_BLAST_DURATION_HOURS,
                LhScheduleConstant.SAND_BLAST_DURATION_HOURS);
        putIntValue(resolvedParamMap, lhParamsMap, LhScheduleParamConstant.SAND_BLAST_WITH_INSPECTION_HOURS,
                LhScheduleConstant.SAND_BLAST_WITH_INSPECTION_HOURS);
        putIntValue(resolvedParamMap, lhParamsMap, LhScheduleParamConstant.SAND_BLAST_DAILY_LIMIT,
                LhScheduleConstant.SAND_BLAST_DAILY_LIMIT, 1);
        putIntValue(resolvedParamMap, lhParamsMap, LhScheduleParamConstant.SAND_BLAST_WARNING_DAYS,
                LhScheduleConstant.SAND_BLAST_WARNING_DAYS, 0);
        putIntValue(resolvedParamMap, lhParamsMap, LhScheduleParamConstant.SAND_BLAST_ADVANCE_DAYS,
                LhScheduleConstant.SAND_BLAST_ADVANCE_DAYS, 0);
        putIntValue(resolvedParamMap, lhParamsMap, LhScheduleParamConstant.SAND_BLAST_SKIP_SUNDAY_ENABLED,
                LhScheduleConstant.SAND_BLAST_SKIP_SUNDAY_ENABLED);
        putIntValue(resolvedParamMap, lhParamsMap, LhScheduleParamConstant.SAND_BLAST_SKIP_HOLIDAY_ENABLED,
                LhScheduleConstant.SAND_BLAST_SKIP_HOLIDAY_ENABLED);
        putStringValue(resolvedParamMap, lhParamsMap, LhScheduleParamConstant.SAND_BLAST_MAINTENANCE_DATES,
                LhScheduleConstant.SAND_BLAST_MAINTENANCE_DATES);
        putIntValue(resolvedParamMap, lhParamsMap, LhScheduleParamConstant.SAND_BLAST_ALLOW_ON_MAINTENANCE_DATE,
                LhScheduleConstant.SAND_BLAST_ALLOW_ON_MAINTENANCE_DATE);
        putIntValue(resolvedParamMap, lhParamsMap, LhScheduleParamConstant.SAND_BLAST_ALLOW_SUNDAY_MANUAL_ENABLED,
                LhScheduleConstant.SAND_BLAST_ALLOW_SUNDAY_MANUAL_ENABLED);
        putIntValue(resolvedParamMap, lhParamsMap, LhScheduleParamConstant.SAND_BLAST_SUNDAY_MIN_ALTERNATE_PLAN_COUNT,
                LhScheduleConstant.SAND_BLAST_SUNDAY_MIN_ALTERNATE_PLAN_COUNT, 0);
        putIntValue(resolvedParamMap, lhParamsMap, LhScheduleParamConstant.MAINTENANCE_DURATION_HOURS,
                LhScheduleConstant.MAINTENANCE_DURATION_HOURS);
        putIntValue(resolvedParamMap, lhParamsMap, LhScheduleParamConstant.MAINTENANCE_START_HOUR,
                LhScheduleConstant.MAINTENANCE_START_HOUR);
        putIntValue(resolvedParamMap, lhParamsMap, LhScheduleParamConstant.MAINTENANCE_WARNING_DAYS,
                LhScheduleConstant.MAINTENANCE_WARNING_DAYS);
        putIntValue(resolvedParamMap, lhParamsMap, LhScheduleParamConstant.MAINTENANCE_DAILY_LIMIT,
                LhScheduleConstant.MAINTENANCE_DAILY_LIMIT, 1);
        putIntValue(resolvedParamMap, lhParamsMap, LhScheduleParamConstant.ALLOW_MAINTENANCE_ON_SUNDAY,
                LhScheduleConstant.ALLOW_MAINTENANCE_ON_SUNDAY);
        putIntValue(resolvedParamMap, lhParamsMap, LhScheduleParamConstant.MAINTENANCE_HOLIDAY_BLOCK_DAYS,
                LhScheduleConstant.MAINTENANCE_HOLIDAY_BLOCK_DAYS, 0);
        putIntValue(resolvedParamMap, lhParamsMap, LhScheduleParamConstant.MAINTENANCE_FORCE_CHECK_DAYS,
                LhScheduleConstant.MAINTENANCE_FORCE_CHECK_DAYS, 0);
        putIntValue(resolvedParamMap, lhParamsMap, LhScheduleParamConstant.ALLOW_MAINTENANCE_ON_INVENTORY_DAY,
                LhScheduleConstant.ALLOW_MAINTENANCE_ON_INVENTORY_DAY);
        putDoubleValue(resolvedParamMap, lhParamsMap, LhScheduleParamConstant.CAPSULE_PREHEAT_HOURS,
                LhScheduleConstant.CAPSULE_PREHEAT_HOURS.doubleValue());
        putIntValue(resolvedParamMap, lhParamsMap, LhScheduleParamConstant.MAINTENANCE_OVERLAP_SWITCH_HOURS,
                LhScheduleConstant.MAINTENANCE_OVERLAP_SWITCH_HOURS);

        // 排程窗口与设备约束参数
        putIntValue(resolvedParamMap, lhParamsMap, LhScheduleParamConstant.SCHEDULE_DAYS, LhScheduleConstant.SCHEDULE_DAYS, 1);
        putIntValue(resolvedParamMap, lhParamsMap, LhScheduleParamConstant.ENABLE_FULL_CAPACITY_SCHEDULING,
                LhScheduleConstant.ENABLE_FULL_CAPACITY_SCHEDULING);
        putIntValue(resolvedParamMap, lhParamsMap, LhScheduleParamConstant.ENABLE_ENDING_BY_SURPLUS_IN_FULL_MODE,
                LhScheduleConstant.ENABLE_ENDING_BY_SURPLUS_IN_FULL_MODE);
        putIntValue(resolvedParamMap, lhParamsMap, LhScheduleParamConstant.FORCE_RESCHEDULE,
                LhScheduleConstant.FORCE_RESCHEDULE);
        putIntValue(resolvedParamMap, lhParamsMap, LhScheduleParamConstant.MACHINE_ONLINE_LOOKBACK_DAYS,
                LhScheduleConstant.MACHINE_ONLINE_LOOKBACK_DAYS, 1);
        putIntValue(resolvedParamMap, lhParamsMap, LhScheduleParamConstant.MACHINE_STOP_TIMEOUT_HOURS,
                LhScheduleConstant.MACHINE_STOP_TIMEOUT_HOURS);
        putIntValue(resolvedParamMap, lhParamsMap, LhScheduleParamConstant.ENABLE_SPECIFY_MACHINE_RULE,
                LhScheduleConstant.ENABLE_SPECIFY_MACHINE_RULE);
        putIntValue(resolvedParamMap, lhParamsMap, LhScheduleParamConstant.MOULD_CLEANING_ADVANCE_DAYS,
                LhScheduleConstant.MOULD_CLEANING_ADVANCE_DAYS, 0);
        putIntValue(resolvedParamMap, lhParamsMap, LhScheduleParamConstant.CLEANING_SKIP_ENDING_DAY_THRESHOLD,
                LhScheduleConstant.CLEANING_SKIP_ENDING_DAY_THRESHOLD, 2);
        putIntValue(resolvedParamMap, lhParamsMap, LhScheduleParamConstant.ENABLE_LOCAL_SEARCH,
                LhScheduleConstant.ENABLE_LOCAL_SEARCH);
        putIntValue(resolvedParamMap, lhParamsMap, LhScheduleParamConstant.LOCAL_SEARCH_MACHINE_THRESHOLD,
                LhScheduleConstant.LOCAL_SEARCH_MACHINE_THRESHOLD, 1);
        putIntValue(resolvedParamMap, lhParamsMap, LhScheduleParamConstant.LOCAL_SEARCH_DEPTH,
                LhScheduleConstant.LOCAL_SEARCH_DEPTH, 1);
        putIntValue(resolvedParamMap, lhParamsMap, LhScheduleParamConstant.LOCAL_SEARCH_TIME_BUDGET_MS,
                LhScheduleConstant.LOCAL_SEARCH_TIME_BUDGET_MS, 1);
        putIntValue(resolvedParamMap, lhParamsMap, LhScheduleParamConstant.ENABLE_PRIORITY_TRACE_LOG,
                LhScheduleConstant.ENABLE_PRIORITY_TRACE_LOG);
        putIntValue(resolvedParamMap, lhParamsMap, LhScheduleParamConstant.NEW_SPEC_SHORTAGE_LOOK_AHEAD_DAYS,
                LhScheduleConstant.NEW_SPEC_SHORTAGE_LOOK_AHEAD_DAYS, 1);
        putIntValue(resolvedParamMap, lhParamsMap, LhScheduleParamConstant.NEW_SPEC_SHORTAGE_ADD_MACHINE_THRESHOLD,
                LhScheduleConstant.NEW_SPEC_SHORTAGE_ADD_MACHINE_THRESHOLD, 0);
        putStringValue(resolvedParamMap, lhParamsMap, LhScheduleParamConstant.ODD_SHIFT_CAPACITY_PLUS_SHIFT_TYPE,
                LhScheduleConstant.ODD_SHIFT_CAPACITY_PLUS_SHIFT_TYPE);
        putIntValue(resolvedParamMap, lhParamsMap, LhScheduleParamConstant.ENABLE_CHANGEOVER_BALANCE,
                LhScheduleConstant.ENABLE_CHANGEOVER_BALANCE);
        putIntValue(resolvedParamMap, lhParamsMap, LhScheduleParamConstant.CONTINUOUS_SHORTAGE_LOOK_AHEAD_DAYS,
                LhScheduleConstant.CONTINUOUS_SHORTAGE_LOOK_AHEAD_DAYS, 0);
        putIntValue(resolvedParamMap, lhParamsMap, LhScheduleParamConstant.ENABLE_CARRY_FORWARD_QTY,
                LhScheduleConstant.ENABLE_CARRY_FORWARD_QTY);
        putIntValue(resolvedParamMap, lhParamsMap, LhScheduleParamConstant.ENABLE_CURE_FORMULA_HISTORY_PROTECT,
                LhScheduleConstant.ENABLE_CURE_FORMULA_HISTORY_PROTECT);

        // 开停产与试制策略参数
        putIntValue(resolvedParamMap, lhParamsMap, LhScheduleParamConstant.SHUTDOWN_DAY_MINUS_3_RATE,
                LhScheduleConstant.SHUTDOWN_DAY_MINUS_3_RATE);
        putIntValue(resolvedParamMap, lhParamsMap, LhScheduleParamConstant.SHUTDOWN_DAY_MINUS_2_RATE,
                LhScheduleConstant.SHUTDOWN_DAY_MINUS_2_RATE);
        putIntValue(resolvedParamMap, lhParamsMap, LhScheduleParamConstant.SHUTDOWN_DAY_MINUS_1_RATE,
                LhScheduleConstant.SHUTDOWN_DAY_MINUS_1_RATE);
        putIntValue(resolvedParamMap, lhParamsMap, LhScheduleParamConstant.STARTUP_FIRST_DAY_RATE,
                LhScheduleConstant.STARTUP_FIRST_DAY_RATE);
        putIntValue(resolvedParamMap, lhParamsMap, LhScheduleParamConstant.ENABLE_OPEN_STOP_PRODUCTION_CONTROL,
                LhScheduleConstant.ENABLE_OPEN_STOP_PRODUCTION_CONTROL);
        putStringValue(resolvedParamMap, lhParamsMap, LhScheduleParamConstant.CURING_OPEN_MOLD_TIME,
                LhScheduleConstant.CURING_OPEN_MOLD_TIME);
        putStringValue(resolvedParamMap, lhParamsMap, LhScheduleParamConstant.CURING_STOP_POT_TIME,
                LhScheduleConstant.CURING_STOP_POT_TIME);
        putDoubleValue(resolvedParamMap, lhParamsMap, LhScheduleParamConstant.OPEN_PRODUCTION_SHORTAGE_THRESHOLD_RATE,
                LhScheduleConstant.OPEN_PRODUCTION_SHORTAGE_THRESHOLD_RATE.doubleValue());
        putStringValue(resolvedParamMap, lhParamsMap, LhScheduleParamConstant.OPEN_PRODUCTION_WINTER_TIRE_KEYWORDS,
                LhScheduleConstant.OPEN_PRODUCTION_WINTER_TIRE_KEYWORDS);
        putIntValue(resolvedParamMap, lhParamsMap, LhScheduleParamConstant.TRIAL_DAILY_LIMIT, LhScheduleConstant.TRIAL_DAILY_LIMIT);
        // @deprecated 单控基准机台已废弃，机台已在 T_LH_MACHINE_INFO 表中直接拆分为 L/R 后缀编码
        putStringValue(resolvedParamMap, lhParamsMap, LhScheduleParamConstant.SINGLE_CONTROL_MACHINE_CODES,
                LhScheduleConstant.SINGLE_CONTROL_MACHINE_CODES);
        putIntValue(resolvedParamMap, lhParamsMap, LhScheduleParamConstant.SMALL_BATCH_SKU_THRESHOLD,
                LhScheduleConstant.SMALL_BATCH_SKU_THRESHOLD, 1);

        return new LhScheduleConfig(resolvedParamMap);
    }

    /**
     * 解析并写入整型参数
     *
     * @param resolvedParamMap 解析后参数
     * @param lhParamsMap      原始参数
     * @param paramCode        参数编码
     * @param defaultValue     默认值
     */
    private void putIntValue(Map<String, String> resolvedParamMap, Map<String, String> lhParamsMap, String paramCode, int defaultValue) {
        putIntValue(resolvedParamMap, lhParamsMap, paramCode, defaultValue, null);
    }

    /**
     * 解析并写入整型参数（可选最小值保护）
     *
     * @param resolvedParamMap 解析后参数
     * @param lhParamsMap      原始参数
     * @param paramCode        参数编码
     * @param defaultValue     默认值
     * @param minValue         最小值（null 表示不限制）
     */
    private void putIntValue(Map<String, String> resolvedParamMap, Map<String, String> lhParamsMap,
            String paramCode, int defaultValue, Integer minValue) {
        int resolvedValue = defaultValue;
        String value = lhParamsMap.get(paramCode);
        if (StringUtils.isNotEmpty(value)) {
            try {
                resolvedValue = Integer.parseInt(value.trim());
            } catch (NumberFormatException e) {
                log.warn("硫化参数解析失败, paramCode={}, value={}, 使用默认值: {}", paramCode, value, defaultValue);
                resolvedValue = defaultValue;
            }
        }
        if (minValue != null && resolvedValue < minValue) {
            resolvedValue = minValue;
        }
        resolvedParamMap.put(paramCode, String.valueOf(resolvedValue));
    }

    /**
     * 解析并写入浮点参数
     *
     * @param resolvedParamMap 解析后参数
     * @param lhParamsMap      原始参数
     * @param paramCode        参数编码
     * @param defaultValue     默认值
     */
    private void putDoubleValue(Map<String, String> resolvedParamMap, Map<String, String> lhParamsMap,
            String paramCode, double defaultValue) {
        double resolvedValue = defaultValue;
        String value = lhParamsMap.get(paramCode);
        if (StringUtils.isNotEmpty(value)) {
            try {
                resolvedValue = Double.parseDouble(value.trim());
            } catch (NumberFormatException e) {
                log.warn("硫化参数解析失败, paramCode={}, value={}, 使用默认值: {}", paramCode, value, defaultValue);
                resolvedValue = defaultValue;
            }
        }
        resolvedParamMap.put(paramCode, BigDecimal.valueOf(resolvedValue).stripTrailingZeros().toPlainString());
    }

    /**
     * 解析并写入字符串参数。
     *
     * @param resolvedParamMap 解析后参数
     * @param lhParamsMap      原始参数
     * @param paramCode        参数编码
     * @param defaultValue     默认值
     */
    private void putStringValue(Map<String, String> resolvedParamMap, Map<String, String> lhParamsMap,
            String paramCode, String defaultValue) {
        String value = lhParamsMap.get(paramCode);
        resolvedParamMap.put(paramCode, StringUtils.isNotEmpty(value) ? value.trim() : defaultValue);
    }
}
