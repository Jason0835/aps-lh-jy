## ADDED Requirements

### Requirement: dayN 不得覆盖硫化余量和收尾目标量

系统 MUST 保持统一硫化余量和收尾目标量优先级，`dayN` 不得覆盖 `SkuScheduleDTO.surplusQty`、胎胚库存、共用胎胚零余量未排和收尾 SKU 严格目标量。

#### Scenario: 非共用胎胚收尾目标不受 dayN 限制

- **WHEN** 收尾 SKU 为单胎胚或非共用胎胚
- **THEN** 收尾目标量 MUST 等于 `MAX(硫化余量, 胎胚库存)`
- **AND** 系统 MUST NOT 因当前日 `dayN` 小于收尾目标量而调低目标量

#### Scenario: 共用胎胚收尾目标不受 dayN 抬高

- **WHEN** 收尾 SKU 为共用胎胚 SKU
- **THEN** 收尾目标量 MUST 只按硫化余量计算
- **AND** 系统 MUST NOT 因当前日或后看日 `dayN` 大于硫化余量而抬高目标量

#### Scenario: 共用胎胚零余量不因 dayN 排产

- **WHEN** 共用胎胚 SKU 的硫化余量小于等于 0
- **THEN** 系统 MUST 不排产并列入未排结果
- **AND** 系统 MUST NOT 因当前日或后续日存在 `dayN` 日计划量而安排生产
