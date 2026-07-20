# SKU 提前生产规则

## Purpose

规范硫化排程 S4.5 新增排产中的 SKU 提前生产准入规则，使参数阈值范围内未来业务日有日计划量的 SKU 在满足欠产量和结构计划机台约束时，可以提前进入当前业务日新增机台判断，同时保持候选机台、模具、胎胚、换模、换活字块、首检、班次产能和日计划扣账等既有业务语义不变。

## Requirements

### Requirement: 提前生产只放宽新增排产准入

系统 SHALL 只将提前生产结果用于判断 SKU 是否允许进入当前业务日新增机台链路，不得直接分配机台，不得改写原始 `dailyPlanQuotaMap` 或月计划表，也不得绕过现有资源和工艺约束。

#### Scenario: 提前生产准入成功

- **WHEN** 后续计划日 SKU 满足提前生产准入条件
- **THEN** 系统 SHALL 允许该 SKU 进入当前业务日新增机台判断
- **AND** 系统 SHALL 继续执行候选机台、模具、胎胚、换模、换活字块、首检、晚班不可换模和班次产能等既有校验

#### Scenario: 续作补偿 SKU 单控候选必须真实落在当前业务日开产

- **WHEN** 续作补偿 SKU 已转入 S4.5 新增排产
- **AND** 该 SKU 通过提前生产准入并按既有选机顺序试算到单控候选
- **AND** 某个单控候选经过换模和首检试算后的开产业务日不是当前业务日
- **THEN** 系统 SHALL 回滚该候选的换模和模具预占
- **AND** 系统 SHALL 记录该候选排除原因为提前生产开产未落在当前业务日
- **AND** 系统 SHALL 继续尝试后续候选机台

#### Scenario: 续作补偿 SKU 通过提前生产准入时保持原新增队列顺序

- **WHEN** 续作补偿 SKU 已转入 S4.5 新增排产
- **AND** 该 SKU 通过提前生产准入
- **THEN** 系统 SHALL 保持 S4.5 已有新增 SKU 排序顺序
- **AND** 系统 SHALL NOT 因提前生产准入将补偿 SKU 前移到普通新增 SKU 前

#### Scenario: 正规 SKU 单控整机候选作为普通机台后的回落候选

- **WHEN** 正规 SKU 的候选机台列表中同时存在非单控机台和单控物理机台
- **AND** 单控物理机台 L/R 两侧均满足该 SKU 的既有机台、模具、换模、首检和产能约束
- **THEN** 系统 SHALL 保留该单控物理机台的整机候选
- **AND** 系统 SHALL 将非单控机台排在单控整机候选之前
- **AND** 系统 SHALL NOT 保留正规 SKU 的单控单边候选

#### Scenario: 当前业务日已有日计划量

- **WHEN** SKU 当前业务日 `dayN` 日计划量大于 0
- **THEN** 系统 SHALL 按原新增排产逻辑处理，不进入提前生产限制

### Requirement: 提前生产按参数阈值向后查找未来计划

系统 SHALL 从硫化参数读取 SKU 提前生产天数阈值 `earlyProductionDaysThreshold`；参数缺失、为空、格式非法或小于等于 0 时 SHALL 使用默认值 `2` 并记录中文日志；参数大于 `31` 时 SHALL 按 `31` 生效并记录中文日志。

系统 SHALL 在 SKU 当前业务日 `dayN` 日计划量小于等于 0 时，从 `currentDate + 1` 到 `currentDate + N` 按自然日顺序查找最早存在 `dayN` 日计划量的 `futurePlanDate`，其中 `N = min(配置值, 31)`。排程窗口仍保持 T～T+2 共 3 天、8 个班次，不得因提前生产扩大实际可排窗口。

#### Scenario: 默认参数可查两天

- **WHEN** SKU 当前业务日无日计划量
- **AND** 未配置 SKU 提前生产天数阈值
- **AND** `currentDate + 2` 的 `dayN` 日计划量大于 0
- **THEN** 系统 SHALL 使用 `currentDate + 2` 作为提前生产来源日进入准入判断

#### Scenario: 参数为三天时可查三天

- **WHEN** SKU 当前业务日无日计划量
- **AND** SKU 提前生产天数阈值配置为 `3`
- **AND** `currentDate + 3` 的 `dayN` 日计划量大于 0
- **THEN** 系统 SHALL 使用 `currentDate + 3` 作为提前生产来源日进入准入判断

#### Scenario: 超过一个月上限

- **WHEN** SKU 提前生产天数阈值配置为 `40`
- **THEN** 系统 SHALL 按 `31` 个自然日作为实际提前生产阈值

#### Scenario: 阈值内不存在未来计划

- **WHEN** SKU 当前业务日无日计划量
- **AND** `currentDate + 1` 到 `currentDate + N` 均无有效 `dayN` 日计划量
- **THEN** 系统 SHALL 不允许该 SKU 通过提前生产准入

#### Scenario: 当前业务日已有计划量

- **WHEN** SKU 当前业务日 `dayN` 日计划量大于 0
- **THEN** 系统 SHALL 按原新增排产逻辑处理
- **AND** 系统 SHALL NOT 将该 SKU 判定为提前生产

### Requirement: 提前生产临时日计划量同步前移

系统 SHALL 在 SKU 提前生产准入通过后，为当前 SKU 当前轮次新增机台判断构造临时日计划量视图，临时视图 SHALL 仅用于新增机台判断、日计划量判断和产能模拟，不得写回月计划表，不得污染其他 SKU 或后续正常业务日期的原始 `dailyPlanQuotaMap`。

临时前移规则 SHALL 按实际提前天数 `shiftDays = futurePlanDate - currentDate` 计算：`shifted[D] = original[D + shiftDays]`。当原始来源日期无计划量时，对应 `shifted[D]` 按无计划量口径处理。

#### Scenario: 提前生产后加机台判断使用前移计划

- **WHEN** 原始月计划日计划量为 `T=0, T+1=0, T+2=0, T+3=46, T+4=46, T+5=46`
- **AND** 该 SKU 允许 T+3 计划提前到 T 日生产
- **THEN** 新增机台判断和 dayN 产能模拟 SHALL 使用临时日计划量 `T=46, T+1=46, T+2=46`
- **AND** 系统 SHALL NOT 继续按 `T=0, T+1=0, T+2=0` 判断是否需要加机台

#### Scenario: 前移视图不污染原始日计划

- **WHEN** 系统为提前生产 SKU 构造临时日计划量视图
- **THEN** 原始 `dailyPlanQuotaMap` SHALL 保持原始业务日期和日计划量
- **AND** 排程结束后系统 SHALL NOT 将临时前移日计划量回写月计划表

### Requirement: 欠产超过阈值时复用原强制扩机语义

系统 SHALL 复用项目已有的新增排产欠产增机台阈值；当本月前日累计欠产严格大于阈值时，直接进入现有强制加机台逻辑，不执行结构计划机台数限制。

#### Scenario: 历史欠产超过阈值

- **WHEN** SKU 本月前日累计欠产严格大于欠产增机台阈值
- **THEN** 系统 SHALL 允许该 SKU 继续进入现有强制加机台逻辑

#### Scenario: 历史欠产未超过阈值

- **WHEN** SKU 本月前日累计欠产小于等于欠产增机台阈值
- **THEN** 系统 SHALL 按业务日期和产品结构执行结构计划机台数判断

### Requirement: 当前日和未来计划日统一校验结构计划机台数

系统 SHALL 先读取当前业务日该结构的计划硫化机台数；当前业务日计划机台数为 0 时，SHALL 改取本次提前生产命中的 `futurePlanDate` 同结构计划硫化机台数。提前生产准入和新增机台产能模拟 SHALL 复用同一判断规则。

#### Scenario: 当前日结构计划机台数大于零且未排满

- **WHEN** 当前业务日该结构计划硫化机台数大于 0
- **AND** 当前业务日该结构已排机台数小于计划机台数
- **THEN** 系统 SHALL 允许该 SKU 提前进入新增机台判断

#### Scenario: 当前日结构计划机台数大于零且已排满

- **WHEN** 当前业务日该结构计划硫化机台数大于 0
- **AND** 当前业务日该结构已排机台数大于等于计划机台数
- **THEN** 系统 SHALL 不允许该 SKU 提前生产

#### Scenario: 当前日结构计划为零但未来计划日大于零

- **WHEN** 当前业务日该结构计划硫化机台数为 0
- **AND** `futurePlanDate` 同结构计划硫化机台数大于 0
- **THEN** 系统 SHALL 使用 `futurePlanDate` 的结构计划机台数判断
- **AND** 系统 SHALL 不得将该结构误判为已收尾结构

#### Scenario: 当前日和未来计划日结构计划均为零

- **WHEN** 当前业务日该结构计划硫化机台数为 0
- **AND** `futurePlanDate` 同结构计划硫化机台数为 0
- **THEN** 系统 SHALL 按结构收尾大余量规则继续判断

#### Scenario: 跨自然日夜班使用班次业务日

- **WHEN** 新增 SKU 的首个生产时刻落在跨自然日夜班
- **THEN** 系统 SHALL 使用该班次的 `workDate` 作为当前业务日
- **AND** 提前生产准入和新增机台产能模拟 SHALL 使用同一业务日期

### Requirement: 结构确已收尾时按 SKU 大余量判断强制扩机

系统 SHALL 仅在当前业务日和 `futurePlanDate` 的结构计划硫化机台数均为 0 时，判断本月前日累计欠产是否严格大于当前业务日该 SKU 已排机台数乘以 SKU 日硫化量；计算乘积时 SHALL 使用 `long` 精度，避免整数溢出改变判断结果。

#### Scenario: 结构收尾且 SKU 余量大于已排机台日产能

- **WHEN** 当前业务日和 `futurePlanDate` 的结构计划硫化机台数均为 0
- **AND** 本月前日累计欠产严格大于当前业务日该 SKU 已排机台数乘以 SKU 日硫化量
- **THEN** 系统 SHALL 通过显式强制模式进入现有欠产阈值窗口回落模拟

#### Scenario: 结构收尾但 SKU 余量不足

- **WHEN** 当前业务日和 `futurePlanDate` 的结构计划硫化机台数均为 0
- **AND** 本月前日累计欠产小于等于当前业务日该 SKU 已排机台数乘以 SKU 日硫化量
- **THEN** 系统 SHALL 不启用结构收尾大余量强制扩机模式

#### Scenario: 已排机台日产能乘积超过整数范围

- **WHEN** 当前业务日该 SKU 已排机台数乘以 SKU 日硫化量超过 `int` 最大值
- **THEN** 系统 SHALL 使用 `long` 结果完成余量比较，不得因整数溢出误判为大余量

### Requirement: 月计划结构机台统计加载异常不得阻断排程

系统 SHALL 在基础数据初始化阶段按工厂、年月、定稿排产版本以及 `tempFlag = 0 OR tempFlag IS NULL OR tempFlag = ''` 条件批量查询 `T_MP_MONTH_PLAN_STATISTICS`，读取 `DAY_1`～`DAY_31` 中的 `lhMachines`，并按业务日期和 `structureName` 聚合后缓存到排程上下文。

基础数据初始化 SHALL 将月计划和月计划结构机台统计的加载视野扩展到 `windowEndDate + N`，其中 `N = min(SKU提前生产天数阈值, 31)`，并 SHALL 按日期所属真实年月批量加载，避免跨月、跨年提前生产误用当前月 dayN 或循环查库。

月计划结构机台统计仅用于提前生产的结构机台数判断；查询无数据、过滤后无有效结构或 `dayN` 非法 JSON 时，系统 SHALL 记录告警并按空缓存或计划机台数 0 继续排程，不得中断硫化排程主流程。

#### Scenario: 提前生产视野跨月

- **WHEN** 排程窗口结束日加提前生产天数阈值覆盖多个自然月
- **THEN** 系统 SHALL 批量加载覆盖范围内所有自然月的月计划和结构机台统计
- **AND** 系统 SHALL 按 `futurePlanDate` 所属年月读取对应 `DAY_N`

#### Scenario: 同日期同结构存在多条统计记录

- **WHEN** 同一业务日期和同一 `structureName` 存在多条有效月计划统计记录
- **THEN** 系统 SHALL 对各记录的 `lhMachines` 求和并缓存

#### Scenario: 日统计为空或缺少机台数字段

- **WHEN** 有效结构统计记录的 `dayN` 为空或合法 JSON 中缺少 `lhMachines`
- **THEN** 系统 SHALL 将该记录当日计划硫化机台数按 0 处理

#### Scenario: 日统计 JSON 非法

- **WHEN** 月计划统计记录的 `dayN` 不是合法 JSON
- **THEN** 系统 SHALL 记录包含工厂、排产版本、结构和业务日期的告警
- **AND** 系统 SHALL 将该结构当日计划硫化机台数按 0 处理并继续排程

#### Scenario: 月计划结构机台统计查询为空

- **WHEN** 当前工厂、年月和定稿排产版本未查询到月计划结构机台统计记录
- **THEN** 系统 SHALL 记录包含工厂、年月和排产版本的告警
- **AND** 系统 SHALL 保留空结构机台缓存并继续排程

#### Scenario: 统计记录无有效结构

- **WHEN** 查询到月计划结构机台统计记录但过滤后没有有效 `structureName`
- **THEN** 系统 SHALL 记录包含工厂、年月、排产版本和记录数的告警
- **AND** 系统 SHALL 保留空结构机台缓存并继续排程

### Requirement: 运行态已排机台数按机台编码去重

系统 SHALL 在排程上下文维护结构维度和 SKU 维度的已排机台编码集合，并在新增结果落地、辅助机台释放、结果调整或清零后同步登记或重建统计。

#### Scenario: 同一机台多个班次生产同一 SKU

- **WHEN** 同一 SKU 在同一业务日同一机台的多个班次生产
- **THEN** SKU 已排机台数 SHALL 只计算一台

#### Scenario: 同一结构多个 SKU 共用机台

- **WHEN** 同一结构的多个 SKU 在同一业务日共用同一机台
- **THEN** 结构已排机台数 SHALL 只计算一台

### Requirement: 关键提前生产决策必须可对账

系统 SHALL 对提前生产准入、结构计划日切换、结构收尾大余量强制扩机和月计划结构统计缺失输出简洁中文日志，日志 SHALL 包含可获得的工厂、业务日期、提前生产天数阈值、后续计划日、实际提前天数、未来计划日计划量、SKU、结构、计划机台数、已排机台数、欠产量和判断结果。

#### Scenario: 未来计划日结构机台数阻止强制扩机

- **WHEN** 扩机模拟发现 `futurePlanDate` 同结构仍有计划硫化机台数
- **THEN** 系统 SHALL 记录当前业务日、后续计划日、结构和两日计划机台数
- **AND** 系统 SHALL 不启用结构收尾大余量强制扩机模式

### Requirement: 保存提前生产结果备注

后续日 SKU 命中提前生产规则，并通过既有资源约束和日计划回裁后实际生成新增排产结果时，系统 SHALL 将该 SKU 所属结构在排程窗口 T～T+2 的计划硫化机台数写入 `T_LH_SCHEDULE_RESULT.REMARK`。

机台数 SHALL 严格按 T、T+1、T+2 顺序读取，以英文逗号分隔；没有结构计划的日期 SHALL 保留 `0`。普通提前生产格式为 `结构计划硫化机台数：2,3,4`，结构切换格式为 `[结构切换] 结构计划硫化机台数：0,3,4`，结构收尾格式为 `[结构收尾] 结构计划硫化机台数：0,0,0`。

#### Scenario: 普通提前生产结果

- **WHEN** 后续日 SKU 通过普通提前生产准入并实际生成新增排产结果
- **THEN** 系统 SHALL 写入 `结构计划硫化机台数：T机台数,T+1机台数,T+2机台数`

#### Scenario: 结构切换结果

- **WHEN** 后续日 SKU 通过结构切换准入并实际生成新增排产结果
- **THEN** 系统 SHALL 写入 `[结构切换] 结构计划硫化机台数：T机台数,T+1机台数,T+2机台数`

#### Scenario: 结构收尾结果

- **WHEN** 后续日 SKU 通过结构收尾大余量准入并实际生成新增排产结果
- **THEN** 系统 SHALL 写入 `[结构收尾] 结构计划硫化机台数：T机台数,T+1机台数,T+2机台数`

#### Scenario: 欠产超过阈值

- **WHEN** 后续日 SKU 因历史欠产超过阈值直接进入强制加机台逻辑
- **THEN** 系统 SHALL 按普通提前生产格式写入备注
- **AND** 系统 SHALL NOT 标记结构切换或结构收尾

#### Scenario: 已有结果备注

- **WHEN** 实际生成的硫化排程结果已有备注
- **THEN** 系统 SHALL 使用中文分号追加提前生产备注片段
- **AND** 系统 SHALL NOT 覆盖原备注
- **AND** 系统 SHALL NOT 重复追加完全相同的备注片段

#### Scenario: 未实际生成有效结果

- **WHEN** 提前生产准入失败、候选机台失败或日计划回裁后计划量为 0
- **THEN** 系统 SHALL NOT 写入提前生产备注

#### Scenario: 同一 SKU 成功新增多台机

- **WHEN** 同一提前生产 SKU 实际生成多条新增机台结果
- **THEN** 系统 SHALL 在每条结果中写入相同场景和三日结构计划机台数

### Requirement: 硫化排程结果回写提前生产标识

系统 SHALL 在 `T_LH_SCHEDULE_RESULT.IS_EARLY_PRODUCTION` 字段回写本次结果是否属于 SKU 提前生产，与提前生产备注同源；该字段 SHALL 与备注片段在同一判定结果（`EarlyProductionDecision`）下产生，避免出现“有标识无备注”或“有备注无标识”的不一致。

#### Scenario: 新增结果命中提前生产并准入通过

- **WHEN** `NewSpecProductionStrategy` 产生新增结果且对应 `EarlyProductionDecision.earlyProduction` 为真、`allowed` 为真
- **THEN** 系统 SHALL 将 `IS_EARLY_PRODUCTION` 写为 `1`
- **AND** 系统 SHALL 同时按既有规则写入提前生产备注片段

#### Scenario: 非提前生产或未准入的新增结果

- **WHEN** 新增结果未命中提前生产场景，或命中但 `allowed` 为假
- **THEN** 系统 SHALL 将 `IS_EARLY_PRODUCTION` 写为 `0`
- **AND** 系统 SHALL NOT 写入提前生产备注片段

#### Scenario: 续作/换活字块结果

- **WHEN** 结果由 `ContinuousProductionStrategy` 或 `TypeBlockProductionStrategy` 生成
- **THEN** 系统 SHALL 将 `IS_EARLY_PRODUCTION` 固定写为 `0`
- **AND** 系统 SHALL NOT 调用提前生产判定

#### Scenario: 滚动继承结果

- **WHEN** 滚动排程通过 `RollingScheduleHandoffService` 从上一批次继承生成结果
- **THEN** 系统 SHALL 直接沿用上一批次结果的 `IS_EARLY_PRODUCTION` 取值
- **AND** 系统 SHALL NOT 重新执行提前生产判定

### Requirement: 硫化排程结果回写日标准产量

系统 SHALL 在 `T_LH_SCHEDULE_RESULT.STANDARD_CAPACITY` 字段写入当前 SKU 的日标准产量，取值口径与运行期日标准产量修正逻辑保持一致，避免结果展示与排程计算口径分裂。

#### Scenario: SKU 存在标准产能主数据

- **WHEN** 当前 SKU 在 `T_MDM_SKU_LH_CAPACITY` 中存在记录且 `STANDARD_CAPACITY` 非空
- **THEN** 系统 SHALL 通过 `ShiftCapacityResolverUtil#resolveDailyStandardQty` 读取 `LhScheduleContext.skuLhCapacityMap` 中该 SKU 的 `STANDARD_CAPACITY`
- **AND** 系统 SHALL 将该值写入结果 `STANDARD_CAPACITY` 字段

#### Scenario: SKU 缺失标准产能主数据

- **WHEN** 当前 SKU 在 `LhScheduleContext.skuLhCapacityMap` 不存在，或对应 `STANDARD_CAPACITY` 为空或负数
- **THEN** 系统 SHALL 将结果 `STANDARD_CAPACITY` 写为 `0`
- **AND** 系统 SHALL NOT 引入额外兜底默认值

#### Scenario: 滚动继承结果

- **WHEN** 滚动排程通过 `RollingScheduleHandoffService` 从上一批次继承生成结果
- **THEN** 系统 SHALL 直接沿用上一批次结果的 `STANDARD_CAPACITY` 取值
- **AND** 系统 SHALL NOT 重新读取主数据

### Requirement: 非续作 SKU 必须先检查完整可提前生产范围及交替承接关系

系统 SHALL 在完成 MES 和滚动续作识别后、所有非续作 SKU 加入新增排产列表前，复用公共未排规则检查从排程窗口首日至 `windowEndDate + earlyProductionDaysThreshold` 的日计划量。完整范围无正日计划量时，系统 SHALL 再检查前日排程 T+1 模具交替计划的后物料是否匹配当前 SKU。

日计划量 SHALL 按物料编码和产品状态精确读取，并在跨月或跨年时按业务日期所属实际年月读取对应月计划 `DAY_N`；交替计划不包含产品状态，承接关系 SHALL 仅按后物料编码匹配。

#### Scenario: 完整判断范围内无日计划量

- **WHEN** 非续作 SKU 在排程窗口及窗口结束日后 N 天内的日计划量全部小于等于 0
- **AND** 前日排程 T+1 交替计划不存在匹配的后物料
- **THEN** 系统 SHALL 将该 SKU 写入未排结果
- **AND** 未排原因 SHALL 为“排程窗口及提前生产范围内无日计划量，且无前日排程T+1交替计划”
- **AND** 系统 SHALL 将其从新增列表、结构待排集合、活跃胎胚集合和后置全量 SKU 索引移除

#### Scenario: 完整判断范围无日计划但存在交替承接

- **WHEN** 非续作 SKU 在完整判断范围内日计划量全部小于等于 0
- **AND** 前日排程 T+1 交替计划的后物料匹配当前 SKU 物料编码
- **THEN** 系统 SHALL 保留该 SKU 并继续执行现有历史反选及普通新增流程

#### Scenario: 提前生产范围内任意一天有计划

- **WHEN** 非续作 SKU 在窗口内无日计划量
- **AND** `windowEndDate + 1` 至 `windowEndDate + N` 任意一天日计划量大于 0
- **THEN** 系统 SHALL 允许该 SKU 按原顺序继续进入现有新增排产流程
- **AND** 系统 SHALL 继续执行原有提前生产、选机、模具、胎胚、换模、换活字块、首检和班次约束

#### Scenario: 同物料其他产品状态存在计划

- **WHEN** 当前试制或量试产品状态在完整判断范围内无日计划量
- **AND** 同物料其他产品状态存在日计划量
- **THEN** 系统 SHALL 仍将当前产品状态 SKU 写入未排

#### Scenario: 已识别为续作的 SKU

- **WHEN** SKU 已匹配到 MES 或滚动续作机台
- **THEN** 系统 SHALL 跳过本新增 SKU 日计划未排规则
- **AND** 系统 SHALL 保持原续作排产逻辑
