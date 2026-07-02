package com.zlt.aps.lh.api.constant;

/**
 * 硫化排程参数编码常量
 *
 * @author APS
 *
 * 编码规则：SYS03 + 分组码(2位) + 流水码(3位)
 * 分组码说明：
 *   01 - 班次基础参数
 *   02 - 换模相关参数（含禁止换模时间窗口、模具交替计划）
 *   03 - 首检相关参数
 *   04 - 排程控制参数（含收尾判定、满排、局部搜索、定点机台、优先级日志）
 *   05 - 干冰清洗参数
 *   06 - 喷砂清洗参数
 *   07 - 设备保养参数
 *   08 - 停机与模具清洗参数
 *   09 - 胶囊管理参数
 *   10 - 开停产参数
 *   11 - 试制与小批量参数
 */
public final class LhScheduleParamConstant {

    private LhScheduleParamConstant() {
    }

    /** 夜班开始小时 */
    public static final String NIGHT_START_HOUR = "SYS0301001";
    /** 早班开始小时 */
    public static final String MORNING_START_HOUR = "SYS0301002";
    /** 中班开始小时 */
    public static final String AFTERNOON_START_HOUR = "SYS0301003";
    /** 每班时长（小时） */
    public static final String SHIFT_DURATION_HOURS = "SYS0301004";
    /** 禁止换模开始小时 */
    public static final String NO_MOULD_CHANGE_START_HOUR = "SYS0302001";
    /** 禁止换模结束小时 */
    public static final String NO_MOULD_CHANGE_END_HOUR = "SYS0302002";
    /** 每日换模总上限 */
    public static final String DAILY_MOULD_CHANGE_LIMIT = "SYS0302003";
    /** 早班换模上限 */
    public static final String MORNING_MOULD_CHANGE_LIMIT = "SYS0302004";
    /** 中班换模上限 */
    public static final String AFTERNOON_MOULD_CHANGE_LIMIT = "SYS0302005";
    /** 夜班换模上限 */
    public static final String NIGHT_MOULD_CHANGE_LIMIT = "SYS0302006";
    /** 换模预热时间（小时） */
    public static final String MOULD_CHANGE_PREHEAT_HOURS = "SYS0302007";
    /** 换模其他作业时间（小时） */
    public static final String MOULD_CHANGE_OTHER_HOURS = "SYS0302008";
    /** 换模总耗时（小时） */
    public static final String MOULD_CHANGE_TOTAL_HOURS = "SYS0302009";
    /** 换活字块总耗时（小时） */
    public static final String TYPE_BLOCK_CHANGE_TOTAL_HOURS = "SYS0302010";
    /** 首检时间（小时） */
    public static final String FIRST_INSPECTION_HOURS = "SYS0303001";
    /** 同班次前2台首检计划量 */
    public static final String FIRST_TWO_FIRST_INSPECTION_QTY = "SYS0303002";
    /** 每班最大首检次数 */
    public static final String MAX_FIRST_INSPECTION_PER_SHIFT = "SYS0303099";
    /** 同班次非前2台首检计划量 */
    public static final String FIRST_INSPECTION_QTY = "SYS0303003";
    /** 同班次总计划量上限阈值，单位：条 */
    public static final String CLASS_TOTAL_QTY_UP_LIMIT = "SYS0303004";
    /** 收尾判定天数 */
    public static final String ENDING_DETECT_DAYS = "SYS0304001";
    /** 结构收尾判定天数 */
    public static final String STRUCTURE_ENDING_DAYS = "SYS0304002";
    /** 机台收尾时间容差（分钟） */
    public static final String ENDING_TIME_TOLERANCE_MINUTES = "SYS0304003";
    /** 干冰清洗间隔天数 */
    public static final String DRY_ICE_INTERVAL_DAYS = "SYS0305001";
    /** 干冰清洗预警天数 */
    public static final String DRY_ICE_WARNING_DAYS = "SYS0305002";
    /** 干冰清洗提前天数 */
    public static final String DRY_ICE_ADVANCE_DAYS = "SYS0305003";
    /** 干冰清洗耗时（小时） */
    public static final String DRY_ICE_DURATION_HOURS = "SYS0305004";
    /** 干冰清洗损失数量 */
    public static final String DRY_ICE_LOSS_QTY = "SYS0305005";
    /** 每日干冰清洗上限 */
    public static final String DRY_ICE_DAILY_LIMIT = "SYS0305006";
    /** 干冰清洗早班上限 */
    public static final String DRY_ICE_MORNING_SHIFT_LIMIT = "SYS0305007";
    /** 干冰清洗中班上限 */
    public static final String DRY_ICE_AFTERNOON_SHIFT_LIMIT = "SYS0305008";
    /** 干冰清洗允许开始时间 */
    public static final String DRY_ICE_WORK_START_TIME = "SYS0305009";
    /** 干冰清洗允许结束时间 */
    public static final String DRY_ICE_WORK_END_TIME = "SYS0305010";
    /** 喷砂清洗耗时（小时） */
    public static final String SAND_BLAST_DURATION_HOURS = "SYS0306001";
    /** 喷砂清洗含首检耗时（小时） */
    public static final String SAND_BLAST_WITH_INSPECTION_HOURS = "SYS0306002";
    /** 每日喷砂清洗上限 */
    public static final String SAND_BLAST_DAILY_LIMIT = "SYS0306003";
    /** 喷砂清洗预警天数 */
    public static final String SAND_BLAST_WARNING_DAYS = "SYS0306004";
    /** 喷砂清洗提前天数 */
    public static final String SAND_BLAST_ADVANCE_DAYS = "SYS0306005";
    /** 喷砂是否跳过周日 */
    public static final String SAND_BLAST_SKIP_SUNDAY_ENABLED = "SYS0306006";
    /** 喷砂是否跳过节假日 */
    public static final String SAND_BLAST_SKIP_HOLIDAY_ENABLED = "SYS0306007";
    /** 喷砂机维保日期 */
    public static final String SAND_BLAST_MAINTENANCE_DATES = "SYS0306008";
    /** 喷砂机维保日是否允许安排 */
    public static final String SAND_BLAST_ALLOW_ON_MAINTENANCE_DATE = "SYS0306009";
    /** 是否允许手工周日喷砂 */
    public static final String SAND_BLAST_ALLOW_SUNDAY_MANUAL_ENABLED = "SYS0306010";
    /** 周日允许喷砂的最小交替计划条数阈值 */
    public static final String SAND_BLAST_SUNDAY_MIN_ALTERNATE_PLAN_COUNT = "SYS0306011";
    /** 喷砂保养月中日期 */
    public static final String SAND_BLAST_MAINTENANCE_DAY_MID = "SYS0306012";
    /** 喷砂保养月末日期 */
    public static final String SAND_BLAST_MAINTENANCE_DAY_END = "SYS0306013";
    /** 保养耗时（小时） */
    public static final String MAINTENANCE_DURATION_HOURS = "SYS0307001";
    /** 保养开始小时 */
    public static final String MAINTENANCE_START_HOUR = "SYS0307002";
    /** 保养预警天数 */
    public static final String MAINTENANCE_WARNING_DAYS = "SYS0307003";
    /** 每日最大保养台数 */
    public static final String MAINTENANCE_DAILY_LIMIT = "SYS0307004";
    /** 是否允许周日安排保养 */
    public static final String ALLOW_MAINTENANCE_ON_SUNDAY = "SYS0307005";
    /** 节假日前N天不排保养 */
    public static final String MAINTENANCE_HOLIDAY_BLOCK_DAYS = "SYS0307006";
    /** 长期在机提前检查天数 */
    public static final String MAINTENANCE_FORCE_CHECK_DAYS = "SYS0307007";
    /** 是否允许盘点日安排保养 */
    public static final String ALLOW_MAINTENANCE_ON_INVENTORY_DAY = "SYS0307008";
    /** 胶囊预热时间（小时） */
    public static final String CAPSULE_PREHEAT_HOURS = "SYS0307009";
    /** 维保重叠切换耗时（小时） */
    public static final String MAINTENANCE_OVERLAP_SWITCH_HOURS = "SYS0307010";
    /** 排程天数 */
    public static final String SCHEDULE_DAYS = "SYS0304004";
    /** 是否按产能满排 */
    public static final String ENABLE_FULL_CAPACITY_SCHEDULING = "SYS0304005";
    /** 满排模式是否按余量命中收尾规则2 */
    public static final String ENABLE_ENDING_BY_SURPLUS_IN_FULL_MODE = "SYS0304006";
    /** 是否强制重排（0-否，1-是） */
    public static final String FORCE_RESCHEDULE = "SYS0304007";
    /** MES在机信息往前追溯天数 */
    public static final String MACHINE_ONLINE_LOOKBACK_DAYS = "SYS0304008";
    /** 停机超时阈值（小时） */
    public static final String MACHINE_STOP_TIMEOUT_HOURS = "SYS0308001";
    /** 硫化定点机台规则开关（0-关闭，1-开启） */
    public static final String ENABLE_SPECIFY_MACHINE_RULE = "SYS0304009";
    /** 模具清洗提前天数 */
    public static final String MOULD_CLEANING_ADVANCE_DAYS = "SYS0308002";
    /** 清洗跳过近收尾天数阈值（机台当前物料剩余天数 <= 该值时跳过清洗） */
    public static final String CLEANING_SKIP_ENDING_DAY_THRESHOLD = "SYS0308003";
    /** 胶囊预警次数 */
    public static final String CAPSULE_WARNING_COUNT = "SYS0309001";
    /** 胶囊强制下机次数 */
    public static final String CAPSULE_FORCE_DOWN_COUNT = "SYS0309002";
    /** 胶囊更换损失数量 */
    public static final String CAPSULE_CHANGE_LOSS_QTY = "SYS0309003";
    /** 停产前第3天产能比例 */
    public static final String SHUTDOWN_DAY_MINUS_3_RATE = "SYS0310001";
    /** 停产前第2天产能比例 */
    public static final String SHUTDOWN_DAY_MINUS_2_RATE = "SYS0310002";
    /** 停产前第1天产能比例 */
    public static final String SHUTDOWN_DAY_MINUS_1_RATE = "SYS0310003";
    /** 开产首日产能比例 */
    public static final String STARTUP_FIRST_DAY_RATE = "SYS0310004";
    /** 开停产管控开关（0-关闭，1-开启） */
    public static final String ENABLE_OPEN_STOP_PRODUCTION_CONTROL = "SYS0310005";
    /** 硫化开模时间 */
    public static final String CURING_OPEN_MOLD_TIME = "SYS0310006";
    /** 硫化停锅时间 */
    public static final String CURING_STOP_POT_TIME = "SYS0310007";
    /** 开产欠产阈值比例 */
    public static final String OPEN_PRODUCTION_SHORTAGE_THRESHOLD_RATE = "SYS0310008";
    /** 开产雪地胎关键词 */
    public static final String OPEN_PRODUCTION_WINTER_TIRE_KEYWORDS = "SYS0310009";
    /** 试制量试每日上限 */
    public static final String TRIAL_DAILY_LIMIT = "SYS0311001";
    /** 单控基准机台编码 */
    public static final String SINGLE_CONTROL_MACHINE_CODES = "SYS0311002";
    /** 小批量验证SKU阈值 */
    public static final String SMALL_BATCH_SKU_THRESHOLD = "SYS0311003";
    /** 模具交替计划天数 */
    public static final String MOULD_CHANGE_PLAN_DAYS = "SYS0302011";
    /** 局部搜索开关（0-关闭，1-开启） */
    public static final String ENABLE_LOCAL_SEARCH = "SYS0304010";
    /** 局部搜索候选机台阈值（小于该值时启用） */
    public static final String LOCAL_SEARCH_MACHINE_THRESHOLD = "SYS0304011";
    /** 局部搜索深度（包含当前SKU） */
    public static final String LOCAL_SEARCH_DEPTH = "SYS0304012";
    /** 局部搜索单次耗时预算（毫秒） */
    public static final String LOCAL_SEARCH_TIME_BUDGET_MS = "SYS0304013";
    /** 优先级跟踪日志开关（0-关闭，1-开启） */
    public static final String ENABLE_PRIORITY_TRACE_LOG = "SYS0304014";
    /** 新增排产欠产追补判断天数 */
    public static final String NEW_SPEC_SHORTAGE_LOOK_AHEAD_DAYS = "SYS0304015";
    /** 新增排产欠产增机台阈值 */
    public static final String NEW_SPEC_SHORTAGE_ADD_MACHINE_THRESHOLD = "SYS0304022";
    /** 收尾小余量允许欠产偏差值 */
    public static final String CONTINUOUS_ENDING_SURPLUS_TOLERANCE_QTY = "SYS0304023";
    /** 奇数班产计划量加一班别（1-晚班，2-早班，3-中班；空值不启用） */
    public static final String ODD_SHIFT_CAPACITY_PLUS_SHIFT_TYPE = "SYS0304024";
    /** 当天空闲机台优先开关（0-关闭，1-开启） */
    public static final String ENABLE_TODAY_IDLE_MACHINE_PRIORITY = "SYS0304025";
    /** 日标准产量剩余班次（1-晚班，2-早班，3-中班） */
    public static final String DAILY_STANDARD_CAPACITY_REMAIN_SHIFT_TYPE = "SYS0304026";
    /** 是否开启换模均衡（0-关闭，1-开启） */
    public static final String ENABLE_CHANGEOVER_BALANCE = "SYS0304021";
    /** 续作欠产追补判断天数 */
    public static final String CONTINUOUS_SHORTAGE_LOOK_AHEAD_DAYS = "SYS0304019";
    /** 是否追加本月历史欠产量（0-关闭，1-开启） */
    public static final String ENABLE_CARRY_FORWARD_QTY = "SYS0304020";
    /** 硫化示方历史保护开关（0-关闭，1-开启） */
    public static final String ENABLE_CURE_FORMULA_HISTORY_PROTECT = "SYS0304018";
    /** 全量SKU排序日志月计划起产日(beginDay)筛选阈值；仅输出月计划beginDay<=该值的SKU */
    public static final String FULL_SKU_SORT_LOG_BEGIN_DAY_THRESHOLD = "SYS0304027";
    /** SKU提前生产天数阈值 */
    public static final String EARLY_PRODUCTION_DAYS_THRESHOLD = "SYS0304028";
}
