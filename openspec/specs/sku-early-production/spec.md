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

#### Scenario: 只允许正规新增排产 SKU

- **WHEN** SKU 属于 S4.5 普通新增排产
- **AND** `constructionStage` 不是 `01` 或 `02`
- **AND** SKU 不是续作补偿、续作加机台或换活字块回流来源
- **THEN** 系统 SHALL 允许其继续执行提前生产准入判断
- **AND** 系统 SHALL NOT 额外要求 `productStatus` 必须等于 `S`

#### Scenario: 续作、换活字块、试制和量试不得提前生产

- **WHEN** SKU 属于续作补偿、续作加机台、换活字块回流、试制或量试任一场景
- **THEN** 系统 SHALL NOT 对该 SKU 执行提前生产
- **AND** 系统 SHALL 保持其原计划日期、原顺延及原入口约束
- **AND** 换活字块 SHALL NOT 主动拉取未来 SKU

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

系统 SHALL 在 S4.3 先为“当前业务月 `TOTAL_QTY=0` 且未来观察范围存在原始日计划”的 SKU 构造候选态 `EarlyProductionRuntimePlan`；在某业务日通过提前生产准入后再切换为激活态。运行视图统一承载候选/激活状态、当前月 `TOTAL_QTY`、`currentDate`、`futurePlanDate`、`earlyDays`、阈值、未来月计划量、未来月完成量、未来月余量、动态历史欠产、有效目标量、`EarlyProductionDecision` 和 `shiftedDailyPlanQuotaMap`。

候选态 SHALL NOT 生成临时前移账本、不得取得运行目标量、不得参与正常阶段或提前生产阶段的资源竞争。激活后，选机、加机台、逐日后看、产能模拟、日计划扣账和同一 SKU 多机台 SHALL 读取同一份临时账本实例；运行视图 SHALL NOT 替换 SKU 原始 `dailyPlanQuotaMap`，不得写回月计划表，不得污染其他 SKU。已提前上机 SKU 在窗口内跨业务日延续时 SHALL 继续共享该运行视图。运行视图 SHALL 覆盖 `scheduleNewSpecs`、班次计划量分配、胎胚库存调整、排后 `isEnd` 复核和降模后处理，整个 S4.5 全部完成后统一清理；S4.5 任一步骤异常退出时也 SHALL 清理。独立复用新增主链的特殊材料置换 SHALL 在自身调用结束后清理本轮临时视图。

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

#### Scenario: 同一 SKU 多台机共享临时账本

- **WHEN** 同一提前生产 SKU 在剩余资源中成功新增多台机
- **THEN** 所有机台 SHALL 共同消费同一个 `shiftedDailyPlanQuotaMap`
- **AND** 系统 SHALL NOT 为每台机复制或重建一份独立额度

#### Scenario: 原始日计划分组不受临时前移影响

- **WHEN** SKU 当前业务日原始 `dayN` 为 0
- **AND** 临时前移或历史欠产追加后当前日临时 `remainingQty` 大于 0
- **THEN** 系统 SHALL 仍将该 SKU 归入提前生产组或历史欠产遗留组
- **AND** 系统 SHALL NOT 将其放入当日正常计划组

### Requirement: 提前生产数量口径集中计算

系统 SHALL 由 `EarlyProductionQuantityCalculator` 集中处理当前业务月 `TOTAL_QTY` 路由、候选态注册、跨月计划段、未来月完成量/余量、目标月份计划选择和按业务日历史欠产缓存。该类 SHALL 只读取已批量加载的月计划、月完成量、日完成量和提前生产阈值，不得修改数据库月计划、原始 `dayN` 或全局日计划账本。

`CuringMonthPlanTotalCalculator` SHALL 保持通用硫化月计划总量职责，不得直接感知提前生产天数阈值或扩展未来月份观察范围。正常硫化余量仍 SHALL 复用该通用计算器，提前生产未来月计划量、完成量和余量 SHALL 只保存在 `EarlyProductionRuntimePlan` 中，不得回写或替换 `SkuScheduleDTO.surplusQty`。

`ScheduleAdjustHandler` SHALL 仅在 SKU 归集前初始化提前生产历史欠产缓存，并调用 `EarlyProductionQuantityCalculator` 应用当前月 `TOTAL_QTY` 路由及注册候选视图；不得在处理器内重复实现提前生产的余量、完成量或超欠产扫描。

提前生产中心运行视图进入激活态后，收尾小余量规则 SHALL 通过 `EarlyProductionQuantityCalculator` 读取 SKU 实际消费账本的实时剩余目标量，不得继续使用为保护通用正常余量而保持不变的 `SkuScheduleDTO.surplusQty`。候选态、普通新增、续作和换活字块 SHALL 继续使用通用硫化余量。该规则只切换数量来源，不得整体绕过收尾小余量判断。

提前生产中心运行视图进入激活态后，普通收尾预判、普通收尾目标调整和排后最终 `isEnd` 判断 SHALL 使用运行视图初始化时冻结的 `effectiveTargetQty` 作为 SKU 总目标，不得使用通用 `surplusQty`、局部胎胚库存或二者的模台数归整结果覆盖该中心总目标。普通收尾处理 SHALL 保留实际消费账本已经扣减的剩余量，不得把账本重新同步为 `effectiveTargetQty`。成型胎胚库存收尾的精确硬目标 SHALL 保持现有更高优先级；普通新增、续作、换活字块以及尚未激活的候选态 SHALL 继续使用通用收尾口径。

#### Scenario: 激活态提前生产使用实时剩余量判断收尾小余量

- **WHEN** 当前月 `TOTAL_QTY=0` 的正规新增 SKU 已通过提前生产准入并激活中心运行视图
- **AND** 通用 `surplusQty=0`
- **AND** 实际消费账本剩余目标量大于允许欠产偏差值
- **THEN** 系统 SHALL 使用实际消费账本剩余目标量执行收尾小余量判断
- **AND** 系统 SHALL NOT 因通用 `surplusQty=0` 将该 SKU 错误写入收尾小余量未排
- **AND** 系统 SHALL 保持通用 `surplusQty` 原值不变

#### Scenario: 部分提前生产后使用最新剩余量

- **WHEN** 提前生产 SKU 已形成部分有效排程结果
- **THEN** 收尾小余量规则 SHALL 使用实际消费账本扣减后的最新剩余量
- **AND** 系统 SHALL NOT 使用中心运行视图初始化时的 `effectiveTargetQty` 固定快照

#### Scenario: 提前生产实时剩余量已经进入小余量范围

- **WHEN** 激活态提前生产 SKU 的实际消费账本剩余量小于等于允许欠产偏差值
- **THEN** 系统 SHALL 继续执行既有前日 T+1 夜班是否排满判断
- **AND** 命中规则时未排数量 SHALL 使用该实时剩余量
- **AND** 系统 SHALL NOT 因提前生产身份无条件绕过收尾小余量规则

#### Scenario: 通用余量为0且胎胚库存小于中心总目标

- **WHEN** 当前月 `TOTAL_QTY=0` 的正规新增 SKU 已激活提前生产中心运行视图
- **AND** 中心 `effectiveTargetQty=102`
- **AND** 通用 `surplusQty=0`、局部胎胚库存为29
- **AND** SKU 未命中成型胎胚库存收尾精确硬目标
- **THEN** 普通收尾目标、排前收尾比较量和排后最终 `isEnd` 比较量 SHALL 保持为102
- **AND** 系统 SHALL NOT 将中心目标下调为29或按模台数归整后的数量
- **AND** 系统 SHALL NOT 因通用 `surplusQty=0` 命中共用胎胚零余量未排

#### Scenario: 部分提前生产后普通收尾不得重置消费账本

- **WHEN** 中心 `effectiveTargetQty=102` 的提前生产 SKU 已有效排产28条
- **AND** 实际消费账本剩余74条
- **THEN** 普通收尾和最终 `isEnd` 判断 SHALL 继续以102作为总目标
- **AND** 后续可排数量 SHALL 读取实际消费账本剩余74条
- **AND** 普通收尾处理 SHALL NOT 把实际消费账本重新同步为102

### Requirement: 当前业务月 TOTAL_QTY 决定正常排产路由

系统 SHALL 使用排程窗口开始日真实所属年月的月计划原始 `TOTAL_QTY` 判断 SKU 是否允许进入正常排产。当前月记录缺失、`TOTAL_QTY` 为空或 `TOTAL_QTY <= 0` SHALL 统一按0处理。

当前月 `TOTAL_QTY=0` 时，不论通用硫化余量、历史欠产、上月超欠产、胎胚库存或收尾判断结果为何，系统 SHALL 清除该 SKU 的正常运行目标并禁止其进入当天计划、正常加机台、历史欠产/收尾遗留和续作排产。该门禁 SHALL NOT 修改通用正常余量公式或 `SkuScheduleDTO.surplusQty`。

#### Scenario: 当前月 TOTAL_QTY 大于0

- **WHEN** SKU 在排程窗口开始日所属月份的原始 `TOTAL_QTY > 0`
- **THEN** 系统 SHALL 保持现有正常余量、正常排产和提前生产准入逻辑
- **AND** 系统 SHALL NOT 将该 SKU 标记为 `futureOnlyCandidate`

#### Scenario: 当前月 TOTAL_QTY 为0但通用余量大于0

- **WHEN** SKU 当前月原始 `TOTAL_QTY=0`
- **AND** 通用余量、历史欠产、胎胚库存或收尾目标任一大于0
- **THEN** 系统 SHALL NOT 允许该 SKU 进入任何正常排产阶段
- **AND** 系统 SHALL 保持通用 `surplusQty` 原值不变

#### Scenario: 当前月记录缺失与 TOTAL_QTY 为0同口径

- **WHEN** SKU 当前月月计划记录不存在
- **THEN** 系统 SHALL 按当前月 `TOTAL_QTY=0` 执行相同路由
- **AND** 系统 SHALL NOT 从未来月份 `TOTAL_QTY` 反推当前月正常排产资格

### Requirement: 当前月无总计划量的未来 SKU 先候选后激活

当前月 `TOTAL_QTY=0` 且从“排程窗口开始日+1”到“排程窗口结束日+N”存在原始日计划时，系统 SHALL 保留该 SKU 为 `futureOnlyCandidate`。该候选观察范围只用于保证 SKU 不被 S4.3 的零目标量过滤；实际提前生产仍 SHALL 对每个 `currentDate` 严格查找 `[currentDate+1, currentDate+N]`。

#### Scenario: 未来计划尚未进入当前业务日阈值

- **WHEN** 候选 SKU 的 `futurePlanDate` 位于候选观察范围内
- **AND** `futurePlanDate` 尚未进入 `[currentDate+1, currentDate+N]`
- **THEN** 系统 SHALL 保持候选态并推进到下一业务日重新判断
- **AND** 系统 SHALL NOT 创建前移账本、运行目标量或占用任何资源

#### Scenario: 未来计划进入阈值并通过准入

- **WHEN** 候选 SKU 的 `futurePlanDate` 进入 `[currentDate+1, currentDate+N]`
- **AND** 结构计划机台数及剩余资源准入全部通过
- **THEN** 系统 SHALL 将候选视图切换为激活态
- **AND** 系统 SHALL 使用未来计划月余量加当前业务月历史欠产建立运行目标
- **AND** 系统 SHALL 构造并共享 `shiftedDailyPlanQuotaMap`

#### Scenario: 当前月无总计划量且候选观察范围无未来计划

- **WHEN** SKU 当前月 `TOTAL_QTY=0`
- **AND** 候选观察范围内不存在原始日计划
- **THEN** 系统 SHALL 不保留提前生产候选
- **AND** 系统 SHALL 不允许该 SKU 进入正常排产

### Requirement: 提前生产跨月数量与历史欠产按真实业务日期隔离

系统 SHALL 使用 `futurePlanDate` 真实所属年月计算未来计划段、完成量及余量，并按每个 `currentDate` 真实所属月份计算截至前一日的历史欠产；跨月、跨年时不得复用其他月份的完成量或构造虚假 `dayN`。

#### Scenario: 通用月计划计算不感知提前生产

- **WHEN** 非提前生产逻辑调用 `CuringMonthPlanTotalCalculator`
- **THEN** 系统 SHALL 继续按原排程窗口和原月计划断点计算月计划总量
- **AND** 系统 SHALL NOT 因提前生产阈值扩大通用计算器的未来查找范围

#### Scenario: 提前生产跨月数量统一计算

- **WHEN** 提前生产阈值内最早 `futurePlanDate` 属于未来月份或未来年份
- **THEN** `EarlyProductionQuantityCalculator` SHALL 使用该日期真实所属年月的计划段和完成量口径
- **AND** `ScheduleAdjustHandler` SHALL NOT 自行再次扫描未来月份或重新计算跨月余量

#### Scenario: 按业务日计算历史欠产

- **WHEN** 排程窗口包含多个业务日或跨月、跨年
- **THEN** `EarlyProductionQuantityCalculator` SHALL 分别从各业务日所属月月初累计至该业务日前一日
- **AND** 单日超产 SHALL NOT 抵扣其他日期欠产
- **AND** 计算结果 SHALL 按物料编码和产品状态写入 `monthlyHistoryShortageQtyMap`

### Requirement: 欠产超过阈值时复用原强制扩机语义

系统 SHALL 复用项目已有的新增排产欠产增机台阈值；当本月前日累计欠产严格大于阈值时，直接进入现有强制加机台逻辑，不执行结构计划机台数限制。历史欠产 SHALL 按正在处理的 `currentDate` 真实所属年月，从月初累计至 `currentDate - 1`，不得固定沿用窗口 T 日快照。

#### Scenario: 历史欠产超过阈值

- **WHEN** SKU 本月前日累计欠产严格大于欠产增机台阈值
- **THEN** 系统 SHALL 允许该 SKU 继续进入现有强制加机台逻辑

#### Scenario: 历史欠产未超过阈值

- **WHEN** SKU 本月前日累计欠产小于等于欠产增机台阈值
- **THEN** 系统 SHALL 按业务日期和产品结构执行结构计划机台数判断

#### Scenario: 提前生产跨月目标量

- **WHEN** `futurePlanDate` 属于未来月份或未来年份
- **THEN** 系统 SHALL 使用 `futurePlanDate` 所属年月的月计划、完成量、余量和版本
- **AND** 系统 SHALL 将 `currentDate` 所属月截至前一日的历史欠产追加到临时运行目标
- **AND** 即使未来月计划携带有效上月超欠产，系统 SHALL 仍按上述口径完整追加当前业务月历史欠产

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

### Requirement: 月计划结构机台统计按真实年月加载并严格校验

系统 SHALL 在基础数据初始化阶段按工厂、年月、定稿排产版本以及 `tempFlag = 0 OR tempFlag IS NULL OR tempFlag = ''` 条件批量查询 `T_MP_MONTH_PLAN_STATISTICS`，读取 `DAY_1`～`DAY_31` 中的 `lhMachines`，并按业务日期和 `structureName` 聚合后缓存到排程上下文。

基础数据初始化 SHALL 将月计划和月计划结构机台统计的加载视野扩展到 `windowEndDate + N`，其中 `N = min(SKU提前生产天数阈值, 31)`，并 SHALL 按日期所属真实年月批量加载，避免跨月、跨年提前生产误用当前月 dayN 或循环查库。

月计划结构机台统计查询无数据或过滤后无有效结构时，系统 SHALL 记录告警并保留空缓存。`dayN` 为空或合法 JSON 缺少 `lhMachines` 时按 0 处理；`dayN` 为非法 JSON 时属于 S4.2 排程基础数据异常，系统 SHALL 抛出异常并中断本次排程，禁止静默按 0 继续。

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
- **THEN** 系统 SHALL 抛出包含工厂、需求版本、排产版本、结构和真实业务日期的 S4.2 基础数据异常
- **AND** 系统 SHALL 中断本次硫化排程
- **AND** 系统 SHALL NOT 将非法 JSON 静默转换为 0

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
- **AND** 施工阶段为 `01/02` 时 SHALL 继续执行续作试制量试专用日计划准入

### Requirement: 续作试制量试 SKU 必须执行完整范围日计划准入

系统 SHALL 在完成 MES 和滚动续作识别后、进入续作排产策略前，对 `constructionStage=01/02` 的续作 SKU 检查 `scheduleDate` 至 `windowEndDate + earlyProductionDaysThreshold` 的月计划原始日计划量。

日计划 SHALL 按物料编码、产品状态和业务日期所属实际年月读取，窗口后范围 SHALL 复用提前生产参数 `SYS0304028`。续作准入 SHALL NOT 使用历史欠产、硫化余量、胎胚库存或扣减后额度代替原始月计划 `DAY_N`。

#### Scenario: 续作试制 SKU 完整范围全零

- **WHEN** 已识别为续作的试制 SKU 在完整判断范围日计划量全部小于等于 0
- **THEN** 系统 SHALL 将该 SKU 从续作列表、结构待排集合、活跃胎胚集合和后置索引移除
- **AND** 系统 SHALL 写入未排原因“试制、量试月计划排产量全部为0，跳过排产”
- **AND** 系统 SHALL NOT 生成该 SKU 的续作结果或加减机台需求

#### Scenario: 续作量试 SKU 完整范围全零

- **WHEN** 已识别为续作的量试 SKU 在完整判断范围日计划量全部小于等于 0
- **THEN** 系统 SHALL 执行与续作试制 SKU 相同的过滤、去重和未排处理

#### Scenario: 完整范围任意一天有量

- **WHEN** 续作试制或量试 SKU 在完整判断范围任意一天日计划量大于 0
- **THEN** 系统 SHALL 保持原续作列表顺序、机台、加减机台、余量和账本逻辑

#### Scenario: 正规续作完整范围全零

- **WHEN** 施工阶段非 `01/02` 的续作 SKU 完整判断范围日计划量全零
- **THEN** 系统 SHALL NOT 应用本专用规则
- **AND** 系统 SHALL 继续执行原有续作窗口无计划、余量收尾和机台释放逻辑

### Requirement: 提前生产必须在每日正常任务之后执行

S4.5 SHALL 按业务日期和原始日计划量分成两个复合阶段。正常复合阶段内部继续执行在机延续、当天计划/锁定、正常加机台以及无未来计划的历史欠产/收尾遗留任务；提前生产复合阶段必须在正常复合阶段全部完成后执行。两个复合阶段内部均保持 S4.5 既有 SKU 排序，正常复合阶段整体优先于提前生产复合阶段。

进入提前生产阶段前，系统 SHALL 基于最新排程结果重建结构和 SKU 已排机台 Set 统计，并将正常阶段已经生成的结果、机台、模具、胎胚、换模、首检和班次容量占用视为已提交资源。提前生产只能复用现有选机和资源分配主链使用真实剩余资源，不得释放、替换、减少或延后正常 SKU 结果。

#### Scenario: 提前生产不得抢占当天计划资源

- **WHEN** 当前业务日同时存在正常日计划 SKU 和满足提前生产资格的 SKU
- **THEN** 系统 SHALL 先完成正常日计划及加机台阶段
- **AND** 提前生产 SKU SHALL 只使用剩余资源

#### Scenario: 正常阶段只按原始日计划分组

- **WHEN** SKU 当前业务日原始 `dayN` 大于 0
- **THEN** 系统 SHALL 将其归入正常复合阶段
- **AND** 系统 SHALL 在所有提前生产 SKU 之前完成其选机、换模、首检和产能提交

#### Scenario: 正常阶段后只有部分班次产能

- **WHEN** 正常阶段完成后某机台仅剩部分班次可用
- **THEN** 提前生产 SKU SHALL 只能使用这些真实剩余班次
- **AND** 系统 SHALL NOT 延后正常 SKU 开产时间或减少正常 SKU 计划量

#### Scenario: 正常阶段后无剩余资源

- **WHEN** 正常阶段完成后没有满足机台、模具、胎胚、换模、首检和班次限制的剩余资源
- **THEN** 系统 SHALL 跳过当前业务日提前生产
- **AND** 系统 SHALL 保持 futurePlanDate 或原顺延逻辑
- **AND** 欠产超过阈值或结构收尾强制扩机 SHALL NOT 绕过该资源优先级

#### Scenario: 提前生产使用同日尾部剩余资源

- **WHEN** 正常阶段完成后当前业务日仍存在满足全部约束的机台尾部产能
- **THEN** 提前生产 SKU SHALL 允许从当前业务日真实剩余时刻开始排产
- **AND** 系统 SHALL NOT 被固定顺延到 T+1

#### Scenario: 排程结果窗口不扩大

- **WHEN** `futurePlanDate` 位于排程窗口结束日之后
- **THEN** 系统 SHALL 只将该日期计划用于提前生产准入和临时节奏视图
- **AND** 所有实际结果 SHALL 仍只写入 T～T+2 共 3 天、8 个班次

### Requirement: 换活字块实际开产必须具备当日原始计划

换活字块策略 SHALL 在结果写入和资源扣减前，根据实际开产时刻命中的班次 `workDate` 读取月计划原始 `dayN`。只有该业务日原始日计划量大于 0 时才允许继续复用换活字块结果构造主链，不得使用历史欠产、剩余目标量或提前生产临时账本替代。

#### Scenario: 换活字块当前日无原始计划

- **WHEN** 换活字块目标 SKU 的实际开产业务日原始 `dayN` 为 0
- **AND** 未来业务日原始 `dayN` 大于 0
- **THEN** 系统 SHALL 拒绝在当前业务日生成换活字块结果
- **AND** 系统 SHALL 回滚本轮换模和模具预占
- **AND** 系统 SHALL 记录“换活字块实际开产业务日原始日计划量为0”的原因

#### Scenario: 到达原计划业务日

- **WHEN** 换活字块目标 SKU 的实际开产业务日原始 `dayN` 大于 0
- **THEN** 系统 SHALL 继续执行既有换活字块机台、模具、胎胚、换模、首检和产能约束

### Requirement: 提前生产失败按窗口统一收口

提前生产 SKU 在单个业务日准入失败或资源不足时 SHALL 只记录当日过程日志和最后硬阻断原因，不得每天重复写最终未排。T～T+2 全部业务日结束后仍未形成有效结果时，系统 SHALL 按物料和产品状态生成一条最终未排记录，数量使用窗口收口时真实剩余目标量。

#### Scenario: 多日均无剩余资源

- **WHEN** 同一提前生产 SKU 在多个业务日均因资源不足未生成有效结果
- **THEN** 系统 SHALL 在窗口结束时只生成一条最终未排记录
- **AND** 未排原因 SHALL 使用最后一次有效硬阻断原因
- **AND** 未排数量 SHALL 使用最终剩余目标量

#### Scenario: 中途成功排产

- **WHEN** 提前生产 SKU 前一业务日失败但后续业务日使用剩余资源成功生成结果
- **THEN** 系统 SHALL 不保留数量为 0 的提前生产未排记录
- **AND** 结构和 SKU 已排机台 Set SHALL 在结果落地后立即更新或在阶段结束后重建

### Requirement: 废弃旧提前生产口径

系统 SHALL 废弃“只允许提前 1 天”“提前 SKU 与正常 SKU 同轮竞争资源”和“续作补偿可通过提前生产主动拉取未来计划”的旧口径。提前生产天数只由 `SYS0304028` 及其默认值、上限决定，资源竞争只允许发生在正常复合阶段已经完成并冻结之后。

#### Scenario: 参数化提前天数与正常阶段资源优先级同时生效

- **WHEN** 正规新增 SKU 的最早未来计划日在参数化提前生产阈值范围内
- **AND** 当前业务日正常复合阶段尚未完成
- **THEN** 系统 SHALL 等待正常复合阶段完成并冻结资源后再判断提前生产
- **AND** 系统 SHALL NOT 将提前天数固定为1天
- **AND** 系统 SHALL NOT 将提前生产 SKU 与正常 SKU 放入同一轮资源竞争
- **AND** 系统 SHALL NOT 通过续作或换活字块入口主动拉取未来计划
