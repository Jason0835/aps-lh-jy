## Purpose

规范硫化排程中月计划硫化余量的统一计算口径，确保 T 日排程晚班完成量和有效上月超欠产量参与计算后，收尾判断、排产目标量、共用胎胚余量为 0 不排、续作、换模、换活字块等链路使用同一个硫化余量结果。

## Requirements

### Requirement: 统一计算硫化余量

系统 SHALL 在 S4.3 SKU 归集阶段统一计算硫化余量，并将结果写入 `SkuScheduleDTO.surplusQty`。

#### Scenario: 按统一公式计算硫化余量

- **WHEN** 系统归集月计划 SKU
- **THEN** 硫化余量 SHALL 按 `MAX(月计划总量 - 已完成量 + 有效上月超欠产量, 0)` 计算
- **AND** 月计划总量 SHALL 取月计划定稿表 `T_MP_MONTH_PLAN_PROD_FINAL.TOTAL_QTY`
- **AND** 已完成量 SHALL 等于月累计完成量（截至 T-1 日）加 T 日排程晚班完成量

#### Scenario: T 日排程晚班完成量参与已完成量

- **WHEN** 系统计算某物料已完成量
- **THEN** 系统 SHALL 使用月计划所属月份截至 T-1 日的月累计完成量
- **AND** 系统 SHALL 加上 `LhScheFinishQty.class1FinishQty` 按物料汇总后的 T 日排程晚班完成量
- **AND** T 日排程晚班完成量 SHALL 同步扣减日计划额度账本，避免首日重复排产

#### Scenario: 有效上月超欠产量参与计算

- **WHEN** 月计划 `LAST_MONTH_VALID_FLAG` 等于 `1`
- **THEN** 系统 SHALL 将 `LAST_MONTH_OVERDUE_QTY` 作为有效上月超欠产量参与硫化余量计算

#### Scenario: 无效上月超欠产量不参与计算

- **WHEN** 月计划 `LAST_MONTH_VALID_FLAG` 等于 `0`、为空或其他无效值
- **THEN** 系统 SHALL 将有效上月超欠产量按 `0` 处理

#### Scenario: 硫化余量不得为负数

- **WHEN** `月计划总量 - 已完成量 + 有效上月超欠产量` 小于 `0`
- **THEN** 系统 SHALL 将硫化余量按 `0` 处理

### Requirement: 后续排程链路复用统一硫化余量

系统 SHALL 让后续所有使用硫化余量的排程链路复用 `SkuScheduleDTO.surplusQty`，不允许另起一套月计划余量计算口径。

#### Scenario: 收尾和目标量复用统一余量

- **WHEN** 系统执行收尾判断或排产目标量计算
- **THEN** 系统 SHALL 使用 S4.3 写入的 `SkuScheduleDTO.surplusQty`
- **AND** 系统 SHALL 保持共用胎胚收尾仅按硫化余量、非共用胎胚收尾按 `MAX(硫化余量, 胎胚库存)` 的既有业务语义

#### Scenario: 共用胎胚零余量复用统一余量

- **WHEN** 共用胎胚 SKU 的 `SkuScheduleDTO.surplusQty` 小于等于 `0`
- **THEN** 系统 SHALL 沿用共用胎胚零余量预剔除或未排规则
- **AND** 后续续作、换模、换活字块和新增排产 SHALL 不再重新计算该 SKU 的月计划余量

### Requirement: 余量计算诊断日志

系统 SHALL 在硫化余量关键计算点输出可追溯日志，便于核对余量来源。

#### Scenario: 输出硫化余量拆解日志

- **WHEN** 有效上月超欠产量大于 `0` 或 T 日排程晚班完成量大于 `0`
- **THEN** 系统 SHALL 输出物料编码、月计划总量、已完成量、T 日排程晚班完成量、上月超欠产有效标志、有效上月超欠产量和最终硫化余量
