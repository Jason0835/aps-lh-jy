package com.zlt.aps.lh.api.constant;

import java.math.BigDecimal;

/**
 * 硫化排程常量类。
 *
 * <p>集中维护硫化排程默认参数值。运行时优先读取 {@code T_LH_PARAMS} 中的参数，
 * 未配置或解析失败时由配置解析器回退到本类默认值。</p>
 *
 * <p>注意：新增参数时需要同步检查 {@code LhScheduleParamConstant}、参数解析器、
 * 初始化 SQL 和实际策略消费点，避免只有常量没有生效链路。</p>
 *
 * @author zlt
 */
public final class LhScheduleConstant {

    private LhScheduleConstant() {
    }

    // ======================== 批次号前缀 ========================

    /** 排程批次号前缀 */
    public static final String BATCH_NO_PREFIX = "LHPC";

    /** 工单号前缀 */
    public static final String ORDER_NO_PREFIX = "LHGD";

    /** 换模工单前缀 */
    public static final String MOULD_CHANGE_ORDER_PREFIX = "CHG";

    // ======================== 班次时间相关（小时） ========================

    /** 每班时长（小时） */
    public static final int SHIFT_DURATION_HOURS = 8;

    /** 夜班开始小时 */
    public static final int NIGHT_SHIFT_START_HOUR = 22;

    /** 早班开始小时 */
    public static final int MORNING_SHIFT_START_HOUR = 6;

    /** 中班开始小时 */
    public static final int AFTERNOON_SHIFT_START_HOUR = 14;

    /** 禁止换模开始小时 */
    public static final int NO_MOULD_CHANGE_START_HOUR = 20;

    /** 禁止换模结束小时 */
    public static final int NO_MOULD_CHANGE_END_HOUR = 6;

    // ======================== 换模相关 ========================

    /** 每日默认换模上限 */
    public static final int DEFAULT_DAILY_MOULD_CHANGE_LIMIT = 15;

    /** 早班默认换模上限 */
    public static final int DEFAULT_MORNING_MOULD_CHANGE_LIMIT = 8;

    /** 中班默认换模上限 */
    public static final int DEFAULT_AFTERNOON_MOULD_CHANGE_LIMIT = 7;

    /** 夜班默认换模上限 */
    public static final int DEFAULT_NIGHT_MOULD_CHANGE_LIMIT = 0;

    /** 换模预热时间（小时） */
    public static final int MOULD_CHANGE_PREHEAT_HOURS = 4;

    /** 换模其他作业时间（小时） */
    public static final int MOULD_CHANGE_OTHER_HOURS = 4;

    /** 换模总耗时（小时） */
    public static final int MOULD_CHANGE_TOTAL_HOURS = 8;

    /** 换活字块总耗时（小时） */
    public static final int TYPE_BLOCK_CHANGE_TOTAL_HOURS = 8;

    // ======================== 首检相关 ========================

    /** 首检时间（小时） */
    public static final int FIRST_INSPECTION_HOURS = 1;

    /** 同班次前2台首检计划量 */
    public static final int FIRST_TWO_FIRST_INSPECTION_QTY = 4;

    /** 同班次非前2台首检计划量 */
    public static final int FIRST_INSPECTION_QTY = 2;

    /** 同班次总计划量上限默认值，<=0 表示不限制 */
    public static final int CLASS_TOTAL_QTY_UP_LIMIT = 2800;

    /** 每班最大首检次数（-1 表示不限制） */
    public static final int MAX_FIRST_INSPECTION_PER_SHIFT = -1;

    // ======================== 收尾判定 ========================

    /** 收尾判定天数 */
    public static final int DEFAULT_ENDING_DAYS = 3;

    /** 标准每日班次数（早/中/夜），用于由班产推算日产能及收尾天数折算 */
    public static final int DEFAULT_SHIFTS_PER_DAY = 3;

    /** 降模排产：结构收尾判定天数 */
    public static final int DEFAULT_STRUCTURE_ENDING_DAYS = 5;

    /** 机台收尾时间容差（分钟） */
    public static final int DEFAULT_ENDING_TIME_TOLERANCE_MINUTES = 20;

    // ======================== 干冰清洗相关 ========================

    /** 干冰清洗间隔天数 */
    public static final int DRY_ICE_INTERVAL_DAYS = 25;

    /** 干冰清洗预警天数 */
    public static final int DRY_ICE_WARNING_DAYS = 7;

    /** 干冰清洗提前天数 */
    public static final int DRY_ICE_ADVANCE_DAYS = 2;

    /** 干冰清洗耗时（小时） */
    public static final int DRY_ICE_DURATION_HOURS = 3;

    /** 干冰清洗损失数量 */
    public static final int DRY_ICE_LOSS_QTY = 6;

    /** 每日干冰清洗上限 */
    public static final int DRY_ICE_DAILY_LIMIT = 3;

    /** 干冰清洗早班上限 */
    public static final int DRY_ICE_MORNING_SHIFT_LIMIT = 2;

    /** 干冰清洗中班上限 */
    public static final int DRY_ICE_AFTERNOON_SHIFT_LIMIT = 1;

    /** 干冰清洗允许开始时间 */
    public static final String DRY_ICE_WORK_START_TIME = "07:30";

    /** 干冰清洗允许结束时间 */
    public static final String DRY_ICE_WORK_END_TIME = "17:00";

    // ======================== 喷砂清洗相关 ========================

    /** 喷砂清洗耗时（小时） */
    public static final int SAND_BLAST_DURATION_HOURS = 10;

    /** 喷砂清洗含首检耗时（小时） */
    public static final int SAND_BLAST_WITH_INSPECTION_HOURS = 12;

    /** 每日喷砂清洗上限 */
    public static final int SAND_BLAST_DAILY_LIMIT = 1;

    /** 喷砂清洗预警天数 */
    public static final int SAND_BLAST_WARNING_DAYS = 25;

    /** 喷砂清洗提前天数 */
    public static final int SAND_BLAST_ADVANCE_DAYS = 2;

    /** 喷砂是否跳过周日（1-跳过） */
    public static final int SAND_BLAST_SKIP_SUNDAY_ENABLED = 1;

    /** 喷砂是否跳过节假日（1-跳过） */
    public static final int SAND_BLAST_SKIP_HOLIDAY_ENABLED = 1;

    /** 喷砂机维保日期 */
    public static final String SAND_BLAST_MAINTENANCE_DATES = "15,28";

    /** 喷砂机维保日是否允许安排（1-允许） */
    public static final int SAND_BLAST_ALLOW_ON_MAINTENANCE_DATE = 0;

    /** 是否允许手工周日喷砂（1-允许） */
    public static final int SAND_BLAST_ALLOW_SUNDAY_MANUAL_ENABLED = 0;

    /** 周日允许喷砂的最小交替计划条数阈值 */
    public static final int SAND_BLAST_SUNDAY_MIN_ALTERNATE_PLAN_COUNT = 2;

    /** 喷砂保养日-月中 */
    public static final int SAND_BLAST_MAINTENANCE_DAY_MID = 15;

    /** 喷砂保养日-月末 */
    public static final int SAND_BLAST_MAINTENANCE_DAY_END = 28;

    // ======================== 设备保养相关 ========================

    /** 保养耗时（小时） */
    public static final int MAINTENANCE_DURATION_HOURS = 7;

    /** 保养开始小时 */
    public static final int MAINTENANCE_START_HOUR = 8;

    /** 保养预警天数 */
    public static final int MAINTENANCE_WARNING_DAYS = 30;

    /** 每日最大保养台数 */
    public static final int MAINTENANCE_DAILY_LIMIT = 1;

    /** 是否允许周日安排保养 */
    public static final int ALLOW_MAINTENANCE_ON_SUNDAY = 0;

    /** 节假日前N天不排保养 */
    public static final int MAINTENANCE_HOLIDAY_BLOCK_DAYS = 2;

    /** 长期在机提前检查天数 */
    public static final int MAINTENANCE_FORCE_CHECK_DAYS = 3;

    /** 是否允许盘点日安排保养 */
    public static final int ALLOW_MAINTENANCE_ON_INVENTORY_DAY = 0;

    /** 胶囊预热时间（小时） */
    public static final BigDecimal CAPSULE_PREHEAT_HOURS = new BigDecimal("2.5");

    /** 维保重叠切换耗时（小时） */
    public static final int MAINTENANCE_OVERLAP_SWITCH_HOURS = 4;

    // ======================== 停机超时阈值 ========================

    /** 停机超时阈值（小时） */
    public static final int MACHINE_STOP_TIMEOUT_HOURS = 24;

    /** 硫化定点机台规则开关默认值（0-关闭，1-开启） */
    public static final int ENABLE_SPECIFY_MACHINE_RULE = 0;

    /** 模具清洗提前天数 */
    public static final int MOULD_CLEANING_ADVANCE_DAYS = 2;

    /** 清洗跳过近收尾天数阈值（机台当前物料剩余天数 <= 该值时跳过清洗） */
    public static final int CLEANING_SKIP_ENDING_DAY_THRESHOLD = 2;

    /** 计划性维修固定排产量（条），维修开始班次固定只排该数量，不按时间折算 */
    public static final int PLANNED_REPAIR_FIXED_QTY = 2;

    // ======================== 胶囊相关 ========================

    /** 胶囊预警次数 */
    public static final int CAPSULE_WARNING_COUNT = 430;

    /** 胶囊强制下机次数 */
    public static final int CAPSULE_FORCE_DOWN_COUNT = 450;

    /** 胶囊更换损失数量 */
    public static final int CAPSULE_CHANGE_LOSS_QTY = 2;

    // ======================== 工作日历工序代码 ========================

    /** 硫化工序代码 */
    public static final String PROC_CODE_LH = "02";

    // ======================== 开停产比例 ========================

    /** 停产前第3天产能比例(%) */
    public static final int SHUTDOWN_DAY_MINUS_3_RATE = 90;

    /** 停产前第2天产能比例(%) */
    public static final int SHUTDOWN_DAY_MINUS_2_RATE = 80;

    /** 停产前第1天产能比例(%) */
    public static final int SHUTDOWN_DAY_MINUS_1_RATE = 70;

    /** 开产首日产能比例(%) */
    public static final int STARTUP_FIRST_DAY_RATE = 50;

    /** 开停产管控默认关闭 */
    public static final int ENABLE_OPEN_STOP_PRODUCTION_CONTROL = 0;

    /** 硫化开模时间默认值 */
    public static final String CURING_OPEN_MOLD_TIME = "";

    /** 硫化停锅时间默认值 */
    public static final String CURING_STOP_POT_TIME = "";

    /** 开产欠产阈值比例默认值 */
    public static final BigDecimal OPEN_PRODUCTION_SHORTAGE_THRESHOLD_RATE = new BigDecimal("0.5");

    /** 开产雪地胎关键词默认值 */
    public static final String OPEN_PRODUCTION_WINTER_TIRE_KEYWORDS = "";

    /** 开产雪地胎靠后分 */
    public static final int OPEN_PRODUCTION_WINTER_TIRE_PENALTY = 1;

    /** 开产不同英寸靠后分 */
    public static final int OPEN_PRODUCTION_DIFFERENT_INCH_PENALTY = 1;

    /**
     * 开产特殊材料靠后分
     *
     * @deprecated 特殊材料开产靠后分已移除，排序不再因特殊材料属性惩罚
     */
    @Deprecated
    public static final int OPEN_PRODUCTION_SPECIAL_MATERIAL_PENALTY = 1;

    // ======================== 试制量试 ========================

    /** 试制量试每日上限 */
    public static final int TRIAL_DAILY_LIMIT = 2;

    /** @deprecated 单控基准机台已废弃：机台已在 T_LH_MACHINE_INFO 表中直接拆分为 L/R 后缀编码 */
    @Deprecated
    public static final String SINGLE_CONTROL_MACHINE_CODES = "";

    /** 小批量验证SKU默认阈值 */
    public static final int SMALL_BATCH_SKU_THRESHOLD = 100;

    // ======================== 模具交替计划天数 ========================

    /** 模具交替计划天数 */
    public static final int MOULD_CHANGE_PLAN_DAYS = 2;

    // ======================== 排程天数 ========================

    /** 排程天数（默认值；运行期以硫化参数 SCHEDULE_DAYS 为准） */
    public static final int SCHEDULE_DAYS = 3;
    /** 是否按产能满排默认值（0-按需求排产，1-按产能满排） */
    public static final int ENABLE_FULL_CAPACITY_SCHEDULING = 1;
    /** 满排模式是否按余量命中收尾规则2默认值（0-关闭，1-开启） */
    public static final int ENABLE_ENDING_BY_SURPLUS_IN_FULL_MODE = 1;
    /** 是否强制重排默认值（0-否，1-是，默认启用强制重排） */
    public static final int FORCE_RESCHEDULE = 1;
    /** 是否强制重排启用值（1-是） */
    public static final int FORCE_RESCHEDULE_ENABLED = 1;
    /** MES在机信息往前追溯天数默认值（运行期以硫化参数 MACHINE_ONLINE_LOOKBACK_DAYS 为准） */
    public static final int MACHINE_ONLINE_LOOKBACK_DAYS = 90;

    // ======================== 局部搜索选机 ========================

    /** 局部搜索开关默认值（0-关闭，1-开启） */
    public static final int ENABLE_LOCAL_SEARCH = 1;

    /** 局部搜索候选机台阈值默认值 */
    public static final int LOCAL_SEARCH_MACHINE_THRESHOLD = 10;

    /** 局部搜索深度默认值（包含当前SKU） */
    public static final int LOCAL_SEARCH_DEPTH = 3;

    /** 局部搜索单次耗时预算默认值（毫秒） */
    public static final int LOCAL_SEARCH_TIME_BUDGET_MS = 50;

    /** 优先级跟踪日志开关默认值（0-关闭，1-开启） */
    public static final int ENABLE_PRIORITY_TRACE_LOG = 1;

    /** 新增排产欠产追补判断天数默认值（当前天发生欠产后，额外向后观察2天） */
    public static final int NEW_SPEC_SHORTAGE_LOOK_AHEAD_DAYS = 2;

    /** 新增排产欠产增机台阈值默认值 */
    public static final int NEW_SPEC_SHORTAGE_ADD_MACHINE_THRESHOLD = 200;

    /** SKU提前生产天数阈值默认值 */
    public static final int DEFAULT_EARLY_PRODUCTION_DAYS_THRESHOLD = 2;

    /** SKU提前生产最多允许提前31个自然日 */
    public static final int MAX_EARLY_PRODUCTION_DAYS_THRESHOLD = 31;

    /** 收尾小余量允许欠产偏差值默认值 */
    public static final int CONTINUOUS_ENDING_SURPLUS_TOLERANCE_QTY = 2;

    /** 奇数班产计划量加一班别默认值，空值表示不启用 */
    public static final String ODD_SHIFT_CAPACITY_PLUS_SHIFT_TYPE = "";

    /** 日标准产量剩余班次默认值，3-中班 */
    public static final int DAILY_STANDARD_CAPACITY_REMAIN_SHIFT_TYPE = 3;

    /** 当天空闲机台优先开关默认值（0-关闭，1-开启） */
    public static final int ENABLE_TODAY_IDLE_MACHINE_PRIORITY = 1;

    /** 新增排产换模均衡开关默认值（0-关闭，1-开启） */
    public static final int ENABLE_CHANGEOVER_BALANCE = 1;

    /** 续作欠产追补判断天数默认值（当前天发生欠产后，额外向后观察1天） */
    public static final int CONTINUOUS_SHORTAGE_LOOK_AHEAD_DAYS = 1;

    /** 是否追加本月历史欠产量默认值（0-关闭，1-开启） */
    public static final int ENABLE_CARRY_FORWARD_QTY = 1;

    /** 硫化示方历史保护开关默认值（0-关闭，1-开启） */
    public static final int ENABLE_CURE_FORMULA_HISTORY_PROTECT = 0;

    // ======================== 排序跟踪日志输出控制 ========================

    /** SKU排序汇总日志默认输出前N名 */
    public static final int SKU_SORT_TRACE_TOP_N = 200;

    /** 机台排序汇总日志默认输出前N名 */
    public static final int MACHINE_SORT_TRACE_TOP_N = 10;

    /** SKU选机台候选列表默认输出前N名 */
    public static final int SKU_MACHINE_CANDIDATE_TOP_N = 10;

    /** 换活字块反选SKU候选列表默认输出前N名 */
    public static final int TYPE_BLOCK_SKU_CANDIDATE_TOP_N = 5;

    /** 全量SKU排序日志月计划起产日筛选阈值默认值；默认31表示不过滤，输出全部有起产日的SKU */
    public static final int DEFAULT_FULL_SKU_SORT_LOG_BEGIN_DAY = 31;

    /** 排程结果实体班次槽位上限（class1～class8） */
    public static final int MAX_SHIFT_SLOT_COUNT = 8;

    /**
     * @deprecated 易与「窗口内实际班次数」混淆；请使用 {@link #MAX_SHIFT_SLOT_COUNT} 或当次 {@code List<com.zlt.aps.lh.api.domain.vo.LhShiftConfigVO>} 长度
     */
    @Deprecated
    public static final int TOTAL_SHIFTS = 9;

    // ======================== 左右模 ========================

    /** 左模标识 */
    public static final String LEFT_MOULD = "L";

    /** 右模标识 */
    public static final String RIGHT_MOULD = "R";

    /** 左右模标识 */
    public static final String LEFT_RIGHT_MOULD = "LR";
}
