## Why

历史版本中部分硫化排程逻辑仍将月计划日计划量 `dayN` 当作当天实际排产量上限或非收尾 SKU 的硬排产目标，导致 `dayN` 较小时续作、新增或换活字块结果被压薄，浪费可用班次产能，也容易覆盖收尾目标量、硫化余量、胎胚库存、欠产追补和晚班不可换模等更高优先级规则。

本次变更需要统一最新口径：`dayN` 只用于排产节奏与资源判断，不直接决定当天实际排产量。

## What Changes

- 清理新增排产、续作排产、换活字块排产中直接以 `dayN` 控制当天实际排产量或作为非收尾硬目标量的旧逻辑。
- 调整加机台判断：`dayN` 仅用于判断当前机台数是否满足当前日或后看日计划节奏，已满足时不得继续加机台。
- 调整续作降模减机台判断：`dayN` 仅用于判断机台冗余，保留机台数刚好满足 T 日当前日计划节奏时停止继续减少。
- 保持收尾 SKU 严格按收尾目标量排产，不允许 `dayN` 覆盖收尾目标量、硫化余量、胎胚库存和共用胎胚零余量规则。
- 保持非收尾 SKU 不再按 `dayN` 硬控量，实际排产量由硫化余量、可用产能、班产、晚班不可换模、机台/模具约束、续作/新增/换活字块场景等现有规则共同决定。
- 保持提前生产只允许提前一天；提前生产发生后，被前移的计划节奏只参与加机台判断，不写回原始月计划。
- 增加必要中文日志，输出 SKU、`dayN`、是否可开始排、是否提前生产、是否加机台、是否降模、最终目标量和实际排产量，便于排查历史口径残留。

## Capabilities

### New Capabilities

无。

### Modified Capabilities

- `add-machine-rule`: 明确 `dayN` 只参与新增/续作加机台节奏判断，不得作为实际排产量上限或非收尾硬目标量。
- `continue-reduce-machine-rule`: 明确续作降模中 `dayN` 只用于冗余机台判断和 T 日保留机台下限，不改变续作实际目标量和非收尾补满规则。
- `sku-early-production`: 明确提前生产前移的 `dayN` 只用于准入、节奏和加机台判断，不得把 T+2 或更远计划提前到 T 日，也不得写回原始日计划。
- `mould-surplus-calculate`: 补充 `dayN` 不得覆盖统一硫化余量、收尾目标量和共用胎胚零余量未排规则。
- `daily-standard-shift-plan`: 明确日标准产量/班次计划量修正不得退化为按 `dayN` 硬控非收尾实际排产量。

## Impact

- 代码影响：`DailyMachineExpansionPlanner`、`NewSpecProductionStrategy`、`ContinuousProductionStrategy`、`TypeBlockProductionStrategy`、`EarlyProductionChecker`、`MonthPlanStatisticsDayUtil`、结果目标量/班次分配/未排记录相关 helper。
- 数据影响：不新增表字段，不改变月计划表、日计划字段和排程结果表结构；只调整运行期使用口径和日志。
- SQL/XML 影响：预计不新增 XML；如排查发现已有复杂查询需补字段，必须沿用现有 Mapper/XML 风格并单独说明。
- 规格影响：需要同步更新上述主规格，确保 `dayN` 最新口径进入可归档文档。
