-- ============================================
-- 硫化排程可配置规则引擎 - 数据库初始化脚本
-- ============================================
-- 说明：
-- 1. 所有参数按工厂编码隔离，支持不同工厂不同的规则配置
-- 2. 参数值为字符串类型，支持数字、小数
-- 3. 若表中无配置，则使用 LhScheduleConstant 中的默认值
-- 4. 规则引擎缓存时间为 5 分钟，修改后最多 5 分钟生效
-- ============================================

-- 工厂 116 的规则配置示例
INSERT INTO T_LH_PARAMS (FACTORY_CODE, PARAM_CODE, PARAM_VALUE, PARAM_NAME, REMARK) VALUES
-- ======================== 班次基础规则 ========================
('116', 'NIGHT_START_HOUR', '22', '夜班开始小时', '默认夜班开始时间'),
('116', 'MORNING_START_HOUR', '6', '早班开始小时', '默认早班开始时间'),
('116', 'AFTERNOON_START_HOUR', '14', '中班开始小时', '默认中班开始时间'),
('116', 'SHIFT_DURATION_HOURS', '8', '班次时长', '单位：小时'),

-- ======================== 换模相关规则 ========================
('116', 'DAILY_MOULD_CHANGE_LIMIT', '15', '每日换模上限', '每日最大换模次数'),
('116', 'MORNING_MOULD_CHANGE_LIMIT', '8', '早班换模上限', '早班 (6:00-14:00) 最大换模次数'),
('116', 'AFTERNOON_MOULD_CHANGE_LIMIT', '7', '中班换模上限', '中班 (14:00-22:00) 最大换模次数'),
('116', 'NIGHT_MOULD_CHANGE_LIMIT', '0', '夜班换模上限', '夜班默认不允许换模'),
('116', 'MOULD_CHANGE_PREHEAT_HOURS', '4', '换模预热时间', '单位：小时'),
('116', 'MOULD_CHANGE_OTHER_HOURS', '4', '换模其他作业时间', '单位：小时'),
('116', 'MOULD_CHANGE_TOTAL_HOURS', '8', '换模总耗时', '单位：小时'),
('116', 'TYPE_BLOCK_CHANGE_TOTAL_HOURS', '8', '换活字块总耗时', '单位：小时'),

-- ======================== 首检相关规则 ========================
('116', 'FIRST_INSPECTION_HOURS', '1', '首检时间', '单位：小时'),
('116', 'MAX_FIRST_INSPECTION_PER_SHIFT', '-1', '每班最大首检次数', '每个班次最大首检次数，-1 表示不限制'),

-- ======================== 时间窗口规则 ========================
('116', 'NO_MOULD_CHANGE_START_HOUR', '20', '禁止换模开始小时', '20:00 后禁止换模'),
('116', 'NO_MOULD_CHANGE_END_HOUR', '6', '禁止换模结束小时', '6:00 前禁止换模'),

-- ======================== 排程参数 ========================
('116', 'SCHEDULE_DAYS', '3', '排程天数', '排程计划覆盖天数'),
('116', 'ENABLE_FULL_CAPACITY_SCHEDULING', '1', '是否按产能满排', '0-按需求排产，1-按产能满排'),
('116', 'ENABLE_ENDING_BY_SURPLUS_IN_FULL_MODE', '1', '满排按余量触发收尾规则2', '0-关闭，1-开启；开启后满排模式按余量与窗口产能比较判收尾'),
('116', 'FORCE_RESCHEDULE', '1', '是否强制重排', '0-否，走滚动衔接；1-是，窗口内全部重排'),
('116', 'MACHINE_ONLINE_LOOKBACK_DAYS', '90', '往前追溯天数', 'MES在机信息从T-1开始向前回溯的最大天数'),
('116', 'ENDING_DETECT_DAYS', '3', '收尾判定天数', '触发收尾判定的天数'),
('116', 'ENDING_TIME_TOLERANCE_MINUTES', '20', '机台收尾时间容差', '单位：分钟'),
('116', 'STRUCTURE_ENDING_DAYS', '5', '结构收尾判定天数', '降模排产的收尾判定天数'),

-- ======================== 干冰清洗规则 ========================
('116', 'DRY_ICE_INTERVAL_DAYS', '25', '干冰清洗间隔天数', '每 25 天清洗一次'),
('116', 'DRY_ICE_WARNING_DAYS', '7', '干冰清洗预警天数', '提前 7 天预警'),
('116', 'DRY_ICE_ADVANCE_DAYS', '2', '干冰清洗提前天数', '最多提前 2 天安排'),
('116', 'DRY_ICE_DURATION_HOURS', '3', '干冰清洗耗时', '单位：小时'),
('116', 'DRY_ICE_LOSS_QTY', '6', '干冰清洗损失数量', '清洗过程损失的产品数量'),
('116', 'DRY_ICE_DAILY_LIMIT', '3', '每日干冰清洗上限', '每天最多 3 台'),
('116', 'DRY_ICE_MORNING_SHIFT_LIMIT', '2', '干冰早班清洗上限', '早班最多 2 台'),
('116', 'DRY_ICE_AFTERNOON_SHIFT_LIMIT', '1', '干冰中班清洗上限', '中班最多 1 台'),
('116', 'DRY_ICE_WORK_START_TIME', '07:30', '干冰清洗允许开始时间', 'HH:mm'),
('116', 'DRY_ICE_WORK_END_TIME', '17:00', '干冰清洗允许结束时间', 'HH:mm'),

-- ======================== 喷砂清洗规则 ========================
('116', 'SAND_BLAST_DURATION_HOURS', '10', '喷砂清洗耗时', '单位：小时'),
('116', 'SAND_BLAST_WITH_INSPECTION_HOURS', '12', '喷砂清洗含首检耗时', '单位：小时'),
('116', 'SAND_BLAST_DAILY_LIMIT', '1', '每日喷砂清洗上限', '每天最多 1 台'),
('116', 'SAND_BLAST_WARNING_DAYS', '25', '喷砂清洗预警天数', '提前 25 天预警'),
('116', 'SAND_BLAST_ADVANCE_DAYS', '2', '喷砂清洗提前天数', '最多提前 2 天安排'),
('116', 'SAND_BLAST_SKIP_SUNDAY_ENABLED', '1', '喷砂是否跳过周日', '1-跳过，0-不跳过'),
('116', 'SAND_BLAST_SKIP_HOLIDAY_ENABLED', '1', '喷砂是否跳过节假日', '1-跳过，0-不跳过'),
('116', 'SAND_BLAST_MAINTENANCE_DATES', '15,28', '喷砂机维保日期', '逗号分隔的每月日期'),
('116', 'SAND_BLAST_ALLOW_ON_MAINTENANCE_DATE', '0', '维保日是否允许喷砂', '1-允许，0-不允许'),
('116', 'SAND_BLAST_ALLOW_SUNDAY_MANUAL_ENABLED', '0', '是否允许手工周日喷砂', '1-允许，0-不允许'),
('116', 'SAND_BLAST_SUNDAY_MIN_ALTERNATE_PLAN_COUNT', '2', '周日喷砂交替计划阈值', '低于该条数时允许手工周日喷砂'),
('116', 'SAND_BLAST_MAINTENANCE_DAY_MID', '15', '喷砂保养日 -月中', '每月 15 日'),
('116', 'SAND_BLAST_MAINTENANCE_DAY_END', '28', '喷砂保养日 -月末', '每月 28 日'),

-- ======================== 设备保养规则 ========================
('116', 'MAINTENANCE_DURATION_HOURS', '7', '保养耗时', '单位：小时'),
('116', 'MAINTENANCE_START_HOUR', '8', '保养开始小时', '固定从 8:00 开始'),
('116', 'MAINTENANCE_WARNING_DAYS', '30', '保养预警天数', '提前 30 天预警'),
('116', 'MAINTENANCE_DAILY_LIMIT', '1', '每日最大保养台数', '每天最多安排 1 台硫化机保养'),
('116', 'ALLOW_MAINTENANCE_ON_SUNDAY', '0', '是否允许周日保养', '0-不允许，1-允许'),
('116', 'MAINTENANCE_HOLIDAY_BLOCK_DAYS', '2', '节假日前禁排保养天数', '节假日前 N 天不排保养'),
('116', 'MAINTENANCE_FORCE_CHECK_DAYS', '3', '长期在机提前检查天数', '到期前 N 天检查长期在机'),
('116', 'ALLOW_MAINTENANCE_ON_INVENTORY_DAY', '0', '是否允许盘点日保养', '0-不允许，1-允许'),
('116', 'CAPSULE_PREHEAT_HOURS', '2.5', '胶囊预热时间', '单位：小时'),

-- ======================== 停机超时阈值 ========================
('116', 'MACHINE_STOP_TIMEOUT_HOURS', '24', '停机超时阈值', '单位：小时'),
('116', 'MOULD_CLEANING_ADVANCE_DAYS', '2', '模具清洗提前天数', '最多提前 2 天'),

-- ======================== 胶囊相关规则 ========================
('116', 'CAPSULE_WARNING_COUNT', '430', '胶囊预警次数', '达到 430 次预警'),
('116', 'CAPSULE_FORCE_DOWN_COUNT', '450', '胶囊强制下机次数', '达到 450 次强制下机'),
('116', 'CAPSULE_CHANGE_LOSS_QTY', '2', '胶囊更换损失数量', '更换过程损失的产品数量'),

-- ======================== 开停产比例 ========================
('116', 'SHUTDOWN_DAY_MINUS_3_RATE', '90', '停产前第 3 天产能比例', '单位：%'),
('116', 'SHUTDOWN_DAY_MINUS_2_RATE', '80', '停产前第 2 天产能比例', '单位：%'),
('116', 'SHUTDOWN_DAY_MINUS_1_RATE', '70', '停产前第 1 天产能比例', '单位：%'),
('116', 'STARTUP_FIRST_DAY_RATE', '50', '开产首日产能比例', '单位：%'),
('116', 'ENABLE_OPEN_STOP_PRODUCTION_CONTROL', '0', '硫化开停产管控开关', '0-关闭，1-开启'),
('116', 'CURING_OPEN_MOLD_TIME', '', '硫化开模时间', '格式：yyyy-MM-dd HH:mm:ss'),
('116', 'CURING_STOP_POT_TIME', '', '硫化停锅时间', '格式：yyyy-MM-dd HH:mm:ss'),
('116', 'OPEN_PRODUCTION_SHORTAGE_THRESHOLD_RATE', '0.5', '开产欠产阈值比例', '达到该比例时记录开产缺口'),
('116', 'OPEN_PRODUCTION_WINTER_TIRE_KEYWORDS', '', '开产雪地胎关键词', '多个关键词用逗号分隔'),

-- ======================== 试制量试规则 ========================
('116', 'TRIAL_DAILY_LIMIT', '2', '试制量试每日上限', '每天最多 2 个试制'),

-- ======================== 模具交替计划规则 ========================
('116', 'MOULD_CHANGE_PLAN_DAYS', '2', '模具交替计划天数', '提前 2 天计划'),

-- ======================== 局部搜索选机规则 ========================
('116', 'ENABLE_LOCAL_SEARCH', '1', '局部搜索开关', '0-关闭，1-开启'),
('116', 'LOCAL_SEARCH_MACHINE_THRESHOLD', '10', '局部搜索候选机台阈值', '候选机台数小于该值时启用'),
('116', 'LOCAL_SEARCH_DEPTH', '3', '局部搜索深度', '包含当前 SKU 的回看深度'),
('116', 'LOCAL_SEARCH_TIME_BUDGET_MS', '50', '局部搜索耗时预算', '单位：毫秒'),

-- ======================== 硫化定点机台规则 ========================
('116', 'ENABLE_SPECIFY_MACHINE_RULE', '0', '硫化定点机台规则开关', '0-关闭，1-开启；关闭后限制作业、不可作业和定点预留均不参与排程'),

-- ======================== 优先级跟踪日志规则 ========================
('116', 'ENABLE_PRIORITY_TRACE_LOG', '0', '优先级跟踪日志开关', '0-关闭，1-开启')
;

-- 工厂 F002 的规则配置示例（如有需要）
-- INSERT INTO T_LH_PARAMS (FACTORY_CODE, PARAM_CODE, PARAM_VALUE, PARAM_NAME, REMARK) VALUES
-- ('F002', 'DAILY_MOULD_CHANGE_LIMIT', '12', '每日换模上限', 'F002 工厂换模能力较低')
-- ;

-- ============================================
-- 查询示例
-- ============================================
-- 查询某工厂的所有规则配置
-- SELECT * FROM T_LH_PARAMS WHERE FACTORY_CODE = '116' AND IS_DELETE = 0 ORDER BY PARAM_CODE;

-- 更新某规则值
-- UPDATE T_LH_PARAMS SET PARAM_VALUE = '16' WHERE FACTORY_CODE = '116' AND PARAM_CODE = 'DAILY_MOULD_CHANGE_LIMIT';

-- 删除某规则配置（逻辑删除）
-- UPDATE T_LH_PARAMS SET IS_DELETE = 1 WHERE FACTORY_CODE = '116' AND PARAM_CODE = 'XXX';

-- ============================================
-- 使用说明
-- ============================================
-- 1. 新增规则：直接在表中插入记录即可，无需修改代码
-- 2. 修改规则：更新 PARAM_VALUE 字段，5 分钟内自动生效
-- 3. 删除规则：将 IS_DELETE 设为 1，系统会自动使用默认值
-- 4. 查看当前生效规则：查询 T_LH_PARAMS 表即可
-- ============================================
