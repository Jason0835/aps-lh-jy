## ADDED Requirements

### Requirement: 提前生产前移 dayN 仅用于节奏判断

系统 MUST 将提前生产构造的临时日计划量视图仅用于准入、日计划节奏判断和新增机台产能模拟，不得将前移后的 `dayN` 作为当天实际排产量硬上限，也不得写回原始月计划或原始 `dailyPlanQuotaMap`。

#### Scenario: 提前生产通过后不硬控实际排产量

- **WHEN** SKU 当前业务日无日计划且阈值范围内的 `futurePlanDate` 计划允许提前生产
- **THEN** 系统 SHALL 使用前移后的 `dayN` 参与新增机台节奏判断
- **AND** 系统 MUST NOT 仅按前移后的当前日 `dayN` 截断实际排产量
- **AND** 实际排产量 SHALL 继续按非收尾或收尾场景目标量及资源约束计算

#### Scenario: 阈值外计划不得提前到当前日

- **WHEN** SKU 当前业务日和 `currentDate + 1` 到 `currentDate + N` 均无有效 `dayN` 日计划量
- **AND** `currentDate + N + 1` 或更远日期存在有效 `dayN` 日计划量
- **THEN** 系统 MUST NOT 允许该 SKU 在当前业务日通过提前生产准入
- **AND** 系统 MUST NOT 将阈值外计划前移到当前业务日参与新增机台判断

#### Scenario: 前移视图仅影响当前轮次

- **WHEN** 系统为提前生产 SKU 构造临时日计划量视图
- **THEN** 原始日计划账本 MUST 保持原始业务日期和原始 `dayN`
- **AND** 排程结束后系统 MUST NOT 回写临时前移计划到月计划表

### Requirement: 提前生产 dayN 决策必须可追溯

系统 MUST 对提前生产准入和前移节奏判断输出可追溯日志，日志包含 SKU、当前业务日、提前生产阈值、后续计划日、实际提前天数、原始 `dayN`、前移后 `dayN`、是否允许提前生产、是否进入加机台和实际排产量。

#### Scenario: 输出提前生产准入日志

- **WHEN** SKU 命中提前生产判断
- **THEN** 日志 MUST 包含当前业务日、`futurePlanDate`、原始当前日 `dayN`、原始 `futurePlanDate` 的 `dayN`、准入结果和原因
