## ADDED Requirements

### Requirement: dayN 仅作为续作降模冗余判断依据

系统 MUST 在续作多机台降模减机台时仅使用 `dayN` 判断保留机台数是否满足当前日或后看日计划节奏，不得用 `dayN` 覆盖续作实际目标量、收尾目标量、硫化余量、胎胚库存、欠产追补或晚班不可换模补满规则。

#### Scenario: 保留机台刚好满足 T 日节奏

- **WHEN** 续作 SKU 当前存在多台续作机台
- **AND** 尝试减少机台后，保留机台数刚好可以满足 T 日当前日计划节奏
- **THEN** 系统 MUST 停止继续减少机台
- **AND** 系统 MUST NOT 继续减少到无法满足 T 日节奏的机台数

#### Scenario: 非收尾续作降模后仍补满可用产能

- **WHEN** 非收尾续作 SKU 完成降模减机台
- **THEN** 系统 SHALL 按续作规则重新分配到保留机台
- **AND** 系统 MUST NOT 仅因 `dayN` 较小而强制保留机台浅排
- **AND** 晚班不可换模且规则允许时，系统 SHALL 尽量避免晚班产能浪费

#### Scenario: 收尾续作不被 dayN 覆盖

- **WHEN** 续作 SKU 为收尾 SKU
- **THEN** 系统 MUST 严格按收尾目标量排产
- **AND** 系统 MUST NOT 使用 `dayN` 调低或抬高收尾目标量

### Requirement: 续作降模 dayN 判断必须可追溯

系统 MUST 在续作降模减机台判断中输出 SKU、当前续作机台数、尝试保留机台数、T 日 `dayN`、后看日 `dayN`、是否降模、降模后目标量和实际排产量。

#### Scenario: 输出降模停止日志

- **WHEN** 系统因保留机台数已是满足 T 日当前日计划节奏的最小机台数而停止继续降模
- **THEN** 日志 MUST 包含 SKU、T 日 `dayN`、保留机台数和停止继续降模原因
