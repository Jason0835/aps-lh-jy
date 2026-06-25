## Purpose

SKU 收尾判断用于硫化排程中区分排序参考、实际排产控量和排后落库标识。规则必须以 SKU 为主体，复用现有窗口有效产能、共用胎胚动态识别、清尾目标量上调、主销产品、奇数余量、模台数归整和排后汇总逻辑，避免用单台机台或排前预估直接决定实际排产。

## Requirements

### Requirement: 收尾语义必须拆分为四类标识

系统 SHALL 将 SKU 收尾语义拆分为 `expectedTailFlag`、`currentWindowTailFlag`、`structureTailFlag`、`finalTailFlag` 四类，不得继续用同一个排前判断结果同时控制排序、排中控量和排后落库。

- `expectedTailFlag`：排前预计收尾，仅用于排序描述、过程日志和分析参考。
- `currentWindowTailFlag`：当前排程窗口收尾，用于续作、新增、换活字块实际排产控量、补满班次、释放机台和结果初始 `isEnd`。
- `structureTailFlag`：结构排序收尾，基于结构窗口判断，仅用于 SKU 排序优先级。
- `finalTailFlag`：排后最终收尾，基于本次窗口实际排产总量汇总后落库。

#### Scenario: 结构收尾命中但当前窗口不能清尾

- **Given** SKU 命中结构 5 天收尾排序条件
- **And** 当前 3 天 8 班有效产能小于清尾目标量
- **When** SKU 进入实际排产
- **Then** 系统 MUST 按非收尾规则排产
- **And** 不得仅因 `structureTailFlag=true` 进入收尾控量

#### Scenario: 当前窗口可清尾但结构排序未命中

- **Given** SKU 未命中结构收尾排序条件
- **And** 当前 3 天 8 班有效产能大于等于清尾目标量
- **When** SKU 进入实际排产
- **Then** 系统 MUST 允许按当前窗口收尾规则排产

### Requirement: 当前窗口收尾必须以 SKU 维度汇总有效产能

系统 SHALL 以 SKU 为维度判断 `currentWindowTailFlag`，判断窗口为当前排程窗口 3 天、连续 8 个班次。机台只作为 SKU 的产能来源，多机台场景必须汇总本次实际可用机台的有效产能，不得因单台机台无法清尾而误判 SKU 非收尾。

#### Scenario: 多机台合计产能覆盖清尾目标量

- **Given** SKU 的清尾目标量为 100
- **And** 单台候选机台 8 班有效产能小于 100
- **And** 本次实际使用多台机台合计 8 班有效产能大于等于 100
- **When** 系统判断当前窗口收尾
- **Then** `currentWindowTailFlag` MUST 为 true

#### Scenario: 当前窗口有效产能不足

- **Given** SKU 的清尾目标量为 100
- **And** 本次实际使用机台合计 8 班有效产能为 80
- **When** 系统判断当前窗口收尾
- **Then** `currentWindowTailFlag` MUST 为 false

### Requirement: 清尾目标量必须复用现有胎胚和产能规则

系统 SHALL 复用现有 `TargetScheduleQtyResolver` 及相关工具计算清尾目标量。非共用胎胚 SKU 的基础清尾目标量为 `max(硫化余量, 胎胚库存)`；共用胎胚 SKU 的基础清尾目标量为 `硫化余量`，不得使用胎胚库存抬高共用胎胚 SKU 的清尾目标量。目标量还 MUST 继续复用模台数归整、奇数余量调整、主销产品收尾等既有规则链路。

#### Scenario: 非共用胎胚按余量和库存取大

- **Given** SKU 不是共用胎胚
- **And** 硫化余量为 40
- **And** 胎胚库存为 60
- **When** 系统解析清尾目标量
- **Then** 基础清尾目标量 MUST 为 60

#### Scenario: 共用胎胚只取硫化余量

- **Given** SKU 是当前窗口共用胎胚
- **And** 硫化余量为 40
- **And** 胎胚库存为 60
- **When** 系统解析清尾目标量
- **Then** 基础清尾目标量 MUST 为 40

#### Scenario: 共用胎胚余量为零

- **Given** SKU 是当前窗口共用胎胚
- **And** 硫化余量为 0
- **When** SKU 进入收尾目标量判断
- **Then** 系统 MUST 不使用胎胚库存抬高目标量
- **And** 该 SKU SHOULD 按现有未排逻辑进入未排结果

### Requirement: 有效产能必须扣除现有排程损耗

系统 SHALL 复用现有窗口有效产能计算，不得使用 `班产 * 班次数` 直接替代。有效产能必须扣除清洗、换模、换活字块、晚班不可换模、停机、日历不可用、已占用产能、禁排机台、模具不可用、首检数量、单控机台产能差异、维护保养和项目已有不可排产约束。

#### Scenario: 清洗或换模压缩班次产能

- **Given** SKU 的候选机台在当前 8 班内存在清洗或换模窗口
- **When** 系统计算当前窗口有效产能
- **Then** 系统 MUST 扣除清洗或换模占用时间对应的产能

### Requirement: 实际排产入口必须使用当前窗口收尾

系统 SHALL 在续作、新增、换活字块实际排产控制中使用 `isCurrentWindowEnding(...)` 的结果决定严格控量、补满班次、释放机台、收尾维护挂载和结果初始 `isEnd`。排前预计收尾和结构收尾不得直接控制实际排产。

#### Scenario: 排前预计收尾不能控制实际排产

- **Given** SKU 的 `expectedTailFlag=true`
- **And** `currentWindowTailFlag=false`
- **When** SKU 进入实际排产
- **Then** 系统 MUST 按非收尾规则处理

#### Scenario: 当前窗口收尾控制实际排产

- **Given** SKU 的 `currentWindowTailFlag=true`
- **When** 系统生成续作、新增或换活字块结果
- **Then** 系统 MUST 按现有收尾控量规则处理
- **And** 达到清尾目标量后停止继续分配

### Requirement: 排后最终收尾必须按实际总排产量汇总

系统 SHALL 在排程结果生成后按 SKU 或续作业务分组汇总本次窗口实际总排产量，并调用 `isFinalEnding(...)` 复核 `finalTailFlag`。同一 SKU 或同一续作分组的多机台结果必须统一 `isEnd`，不得只按单台结果量判断。

#### Scenario: 多机台合计排产量达到清尾目标量

- **Given** SKU 的清尾目标量为 100
- **And** 该 SKU 在两台机台分别排产 60 和 40
- **When** 系统刷新排后最终收尾标识
- **Then** 两条结果的 `isEnd` MUST 均为 `1`

#### Scenario: 多机台合计排产量未达到清尾目标量

- **Given** SKU 的清尾目标量为 100
- **And** 该 SKU 本次窗口实际总排产量为 80
- **When** 系统刷新排后最终收尾标识
- **Then** 该 SKU 本次结果的 `isEnd` MUST 为 `0`

### Requirement: 性能和 OOM 风险必须受控

系统 SHALL 避免新增跨全局生命周期的 `SKU × 机台 × 班次` 明细缓存，不得保存全量候选明细或为每个 SKU 重复构建重型快照。同一 SKU 同一阶段的当前窗口收尾结果 SHOULD 只计算一次并用局部变量传递。日志 MUST 使用汇总信息或必要 TOPN，不得常态构造大文本。

#### Scenario: 大批量 SKU 排程

- **Given** 当前批次存在大量 SKU 和候选机台
- **When** 系统执行收尾判断
- **Then** 系统 MUST 只使用短生命周期局部变量和既有候选列表
- **And** 不得额外复制 `scheduleResultList`、`newSpecSkuList` 或候选机台全量明细

### Requirement: 日志必须覆盖关键收尾判断口径

系统 SHALL 在关键收尾判断节点输出简洁中文日志。日志至少包含 SKU 编码、窗口口径、共用胎胚标识、硫化余量、胎胚库存、清尾目标量、窗口有效产能、实际总排产量和判断结果。过程日志或调试日志不得输出无约束的全量候选明细。

#### Scenario: 当前窗口收尾判断日志

- **Given** SKU 进入当前窗口收尾判断
- **When** 判断完成
- **Then** 日志 MUST 包含 SKU 编码、3 天 8 班窗口、共用胎胚标识、硫化余量、胎胚库存、清尾目标量、总有效产能和 `currentWindowTailFlag`

#### Scenario: 排后最终收尾判断日志

- **Given** SKU 排程结果已生成
- **When** 系统刷新最终收尾标识
- **Then** 日志 MUST 包含 SKU 编码、本次实际总排产量、清尾目标量和 `finalTailFlag`

### Requirement: 验证必须覆盖单测和真实排程

系统 SHALL 通过定向单测、模块编译和真实排程验证本规则。真实排程验证日期为 `2026-06-02`，接口参数为 `{"factoryCode":"116","scheduleDate":"2026-06-02"}`，并 SHOULD 对账排程结果、未排结果、换模计划和过程日志。

#### Scenario: 真实排程验证

- **Given** aps-lh 应用已启动
- **When** 调用 `/lhScheduleResult/execute`
- **And** 请求体为 `{"factoryCode":"116","scheduleDate":"2026-06-02"}`
- **Then** 接口 SHOULD 成功返回
- **And** 数据库 SHOULD 能对账当前窗口收尾日志、最终 `isEnd`、未排原因和换模计划
