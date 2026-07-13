# 共用胎胚 SKU 收尾错峰后延规则

## Purpose

规范硫化排程 S4.4 续作降模收口后的共用胎胚 SKU 收尾错峰后延规则，避免同一班次大量满足条件的 SKU 收尾或降模下机机台同时释放，降低后续换模、换活字块和新增排产的集中换模压力。

## Requirements

### Requirement: 收尾自动补量开关

系统 SHALL 使用硫化参数 `SYS0304029` 控制共用胎胚 SKU 收尾错峰后延补量是否生效。该开关必须在保存降模释放快照、收集错峰候选和修改班次前完成判断。

#### Scenario: 参数开启

- **WHEN** `SYS0304029 = 1`
- **AND** 续作收尾机台满足本规则全部错峰后延条件
- **THEN** 系统 SHALL 继续按候选数量、排序和班次产能规则延后并补满下一班次

#### Scenario: 参数关闭

- **WHEN** `SYS0304029 = 0`
- **AND** 续作收尾机台满足产品类型、运行态共用胎胚、胎胚在机和非最后班次等条件
- **THEN** 系统 SHALL NOT 为该机台保存错峰专用降模释放快照
- **AND** 系统 SHALL NOT 恢复已按正常降模逻辑释放的原收尾班次计划量
- **AND** 系统 SHALL NOT 增加下一班次计划量或登记错峰允许超目标量
- **AND** 系统 SHALL NOT 仅修改 `specEndTime`、`tdaySpecEndTime` 或机台 `estimatedEndTime`
- **AND** 系统 SHALL 保持原续作收尾、降模释放和后续换模逻辑

#### Scenario: 参数未配置、为空或非法

- **WHEN** `SYS0304029` 未配置、参数值为空或参数值不是 `0/1`
- **THEN** 系统 SHALL 按默认值 `1` 处理
- **AND** 系统 SHALL 记录包含参数编码和默认值的告警日志
- **AND** 非法值场景的告警日志 SHALL 同时记录原参数值

### Requirement: 适用前提

系统 SHALL 仅对同时满足以下条件的续作收尾机台执行共用胎胚 SKU 收尾错峰后延：

- SKU 月计划 `productionType` 为 `01` 或 `02`；
- SKU 满足运行态共用胎胚条件，即 `activeEmbryoSkuMap[embryoCode]` 中有效 SKU 数量大于 `1`；
- 胎胚收尾标识 `embryoEndingFlagMap[embryoCode] = 0`，表示胎胚在机；
- 当前机台属于 SKU 收尾导致的收尾机台，或续作降模减机台导致的下机机台；
- 当前收尾班次不是 8 班窗口中的最后一个班次；
- 下一班次未被其他 SKU 占用且存在可排产能。

#### Scenario: 主销或常规共用胎胚 SKU 满足前提

- **WHEN** 续作 SKU 的 `productionType` 为 `01` 或 `02`
- **AND** 当前胎胚运行态有效 SKU 数量大于 `1`
- **AND** `embryoEndingFlagMap[embryoCode] = 0`
- **AND** 当前机台在非最后班次收尾或被降模释放
- **THEN** 系统 SHALL 将该机台纳入当前班次收尾错峰候选统计

#### Scenario: 不满足任一前提

- **WHEN** SKU 非 `productionType in ('01','02')`
- **OR** 当前胎胚不是运行态共用胎胚
- **OR** 胎胚收尾标识缺失或不等于 `0`
- **OR** 当前收尾班次为最后一个班次
- **OR** 下一班次已被其他 SKU 占用
- **OR** 下一班次无可排产能
- **THEN** 系统 SHALL NOT 对该机台执行错峰后延
- **AND** 系统 SHALL 保持原续作收尾、降模释放和换模逻辑

### Requirement: 按班次统计并计算后延数量

系统 SHALL 按排程窗口 8 个班次分别统计满足前提的收尾机台数量，记为 `totalEndingMachineCount`。

系统 SHALL 对每个非最后班次按以下规则计算当前班次保留数量和后延到下一班次数量：

- 当 `totalEndingMachineCount` 为偶数时，当前班次保留 `totalEndingMachineCount / 2` 台，后延 `totalEndingMachineCount / 2` 台；
- 当 `totalEndingMachineCount` 为奇数时，当前班次保留向上取整数量，后延向下取整数量；
- 奇数多出的 1 台 SHALL 优先留在原收尾班次。

#### Scenario: 5 台同班次收尾

- **WHEN** 当前班次满足前提的收尾机台数为 `5`
- **THEN** 系统 SHALL 保留 `3` 台在当前班次收尾
- **AND** 系统 SHALL 后延 `2` 台到下一班次补满

#### Scenario: 6 台同班次收尾

- **WHEN** 当前班次满足前提的收尾机台数为 `6`
- **THEN** 系统 SHALL 保留 `3` 台在当前班次收尾
- **AND** 系统 SHALL 后延 `3` 台到下一班次补满

### Requirement: 后延机台排序

系统 SHALL 在需要从当前班次选择后延机台时，按以下优先级排序：

1. 当前机台在机模具号对应的 SKU-模具关系关联 SKU 数量升序；
2. 胶囊使用次数升序；
3. 机台编码升序。

模具关联 SKU 数量越少 SHALL 表示模具共用性越差，越优先后延。

#### Scenario: 模具共用性不同

- **WHEN** 两台候选机台的当前在机模具关联 SKU 数量不同
- **THEN** 系统 SHALL 优先后延关联 SKU 数量更少的机台

#### Scenario: 模具共用性相同

- **WHEN** 两台候选机台的当前在机模具关联 SKU 数量相同
- **THEN** 系统 SHALL 优先后延胶囊使用次数更少的机台

#### Scenario: 模具共用性和胶囊使用次数均相同

- **WHEN** 两台候选机台的当前在机模具关联 SKU 数量相同
- **AND** 胶囊使用次数相同
- **THEN** 系统 SHALL 按机台编码升序稳定排序

### Requirement: 后延补量与运行态更新

系统 SHALL 对被选中的后延机台执行以下处理：

- 不在原收尾班次释放机台；
- 对续作降模已清零的下机机台，先恢复原收尾班次计划量；
- 将下一班次计划量补到该机台在下一班次的可排产能；
- 刷新结果 `dailyPlanQty`、`specEndTime`、`tdaySpecEndTime` 和停机摘要；
- 同步刷新机台运行态 `estimatedEndTime`；
- 后续换模、换活字块、新增排产和续作降模 SHALL 基于更新后的机台收尾时间继续计算。

#### Scenario: 后延机台补满下一班次

- **WHEN** 某候选机台被选中后延
- **AND** 下一班次可排产能为 `16`
- **THEN** 系统 SHALL 将该机台下一班次计划量设置为 `16`
- **AND** 系统 SHALL 将该机台收尾时间更新到下一班次结束时间

#### Scenario: 降模释放候选被选中后延

- **WHEN** 某候选机台在续作降模阶段已被清零释放
- **AND** 该机台被错峰规则选中后延
- **THEN** 系统 SHALL 恢复该机台原收尾班次计划量
- **AND** 系统 SHALL 补满下一班次计划量

### Requirement: 错峰后延允许超目标量

系统 SHALL 将共用胎胚 SKU 收尾错峰后延补量标记为独立例外，允许被选中机台的后延补量超过原 SKU 收尾目标量。

该例外 SHALL 仅用于共用胎胚 SKU 收尾错峰后延场景，不得放宽普通收尾、非收尾、试制、量试、小批量或新增排产的目标量约束。

#### Scenario: 后延补量超过原收尾目标量

- **WHEN** 被选中后延的机台补满下一班次后，结果计划量超过原 SKU 收尾目标量
- **THEN** 系统 SHALL 通过上下文记录错峰后延允许超量
- **AND** 严格目标量收口 SHALL 识别该允许超量并保留后延补量
- **AND** SKU 实际消费账本裁剪 SHALL 识别该允许超量并保留后延补量
- **AND** 结果校验启用严格目标量校验时 SHALL 识别该允许超量

### Requirement: 过程日志

系统 SHALL 为共用胎胚 SKU 收尾错峰后延输出过程日志和业务日志。

日志 SHALL 至少包含排程日期、原收尾班次、后延班次、SKU、机台、胎胚、产品类型、模具共用性数量、胶囊使用次数、后延补量和新收尾时间。

#### Scenario: 后延成功

- **WHEN** 某机台完成共用胎胚 SKU 收尾错峰后延
- **THEN** 系统 SHALL 记录标题为 `共用胎胚收尾错峰后延` 的过程日志
- **AND** 日志明细 SHALL 包含后延补量和新收尾时间
