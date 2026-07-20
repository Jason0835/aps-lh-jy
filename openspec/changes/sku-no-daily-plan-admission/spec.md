# SKU 无日计划量准入规则

## Purpose

规范非续作待排 SKU 在进入换活字块、历史交替反选和普通新增选机前的统一准入判断，避免完整排程及提前生产范围无日计划、且不存在前日交替承接关系的 SKU 消耗机台、模具和胎胚资源。

## Requirements

### Requirement: 非续作 SKU 必须执行统一日计划准入

系统 SHALL 在完成 MES 与滚动续作识别后、非续作 SKU 加入新增排产列表前，通过 `PendingSkuUnscheduledRule.evaluateDailyPlanAdmission` 执行统一准入。判断闭区间 SHALL 为 `scheduleDate` 至 `windowEndDate + earlyProductionDaysThreshold`。

提前生产天数 SHALL 读取现有硫化参数 `SYS0304028`。日计划 SHALL 按“物料编码 + 产品状态 + 业务年月”精确读取，跨月、跨年时 SHALL 使用实际年月对应月计划的 `DAY_N`。

#### Scenario: 窗口内存在日计划量

- **WHEN** 非续作 SKU 在排程窗口任意一天日计划量大于 0
- **THEN** 系统 SHALL 保留该 SKU 并按原顺序进入现有排产主链

#### Scenario: 提前生产范围内存在日计划量

- **WHEN** 排程窗口日计划量全部为 0
- **AND** `windowEndDate + 1` 至 `windowEndDate + N` 任意一天日计划量大于 0
- **THEN** 系统 SHALL 保留该 SKU
- **AND** 系统 SHALL NOT 因准入放行而绕过现有提前生产、选机、模具、胎胚或班次约束

#### Scenario: 同物料其他产品状态存在计划

- **WHEN** 当前产品状态在完整范围内无正日计划量
- **AND** 同物料其他产品状态存在正日计划量
- **THEN** 系统 SHALL NOT 使用其他产品状态的计划量放行当前 SKU

### Requirement: 无日计划时必须检查前日排程 T+1 交替计划

系统 SHALL 在完整判断范围无正日计划量时，检查当前业务目标日前一日排程生成的 T+1 模具交替计划。系统 SHALL 仅以交替计划的 `AFTER_MATERIAL_CODE` 与当前 SKU 物料编码匹配；交替计划不包含产品状态，因此该承接关系 SHALL 保持物料级语义。

#### Scenario: 后物料存在 T+1 交替承接

- **WHEN** 完整判断范围日计划量全部为 0
- **AND** 前日排程 T+1 交替计划的后物料等于当前 SKU 物料编码
- **THEN** 系统 SHALL 保留该 SKU
- **AND** 后续历史反选失败时 SHALL 继续允许该 SKU 进入普通新增选机

#### Scenario: 仅前物料匹配或计划日期不符

- **WHEN** 仅交替计划前物料等于当前 SKU
- **OR** 后物料匹配但交替日期不是前批次 T+1 日
- **THEN** 系统 SHALL 视为无有效交替承接关系

### Requirement: 两项条件均不满足时必须进入未排

系统 SHALL 在完整判断范围日计划量全部为 0、且不存在有效 T+1 后物料交替承接关系时，将 SKU 写入未排结果并彻底移出后续排产候选。

#### Scenario: 无日计划且无交替计划

- **WHEN** 非续作 SKU 同时满足两个拦截条件
- **THEN** 未排原因 SHALL 为“排程窗口及提前生产范围内无日计划量，且无前日排程T+1交替计划”
- **AND** 未排数量 SHALL 为 0
- **AND** 系统 SHALL 清零目标量与剩余量
- **AND** 系统 SHALL 从结构待排集合、活跃胎胚集合和后置全量 SKU 索引移除该 SKU

#### Scenario: SKU 存在历史欠产或有效结转量

- **WHEN** 完整范围无日计划且无有效交替承接
- **AND** SKU 存在本月历史欠产或上月有效超欠产量
- **THEN** 系统 SHALL 仍将该 SKU 写入未排

### Requirement: 续作和既有排产规则保持不变

本规则 SHALL NOT 处理已经识别为续作的 SKU，SHALL NOT 改变 SKU 遍历顺序、优先级、候选机台排序、目标量计算、日计划账本以及模具、胎胚、换模、换活字块和班次约束。

#### Scenario: 已识别为续作

- **WHEN** SKU 已匹配 MES 或滚动续作机台
- **THEN** 系统 SHALL 跳过本规则并继续执行现有续作逻辑
