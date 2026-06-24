# Comet Design Handoff

- Change: unify-dayn-schedule-quota
- Phase: design
- Mode: compact
- Context hash: 217dd15d5211b6b360d18d3f5a7d6c2a6e2fe1e42206662acda9499181557a06

Generated-by: comet-handoff.sh

OpenSpec remains the canonical capability spec. This handoff is a deterministic, source-traceable context pack, not an agent-authored summary.

## openspec/changes/unify-dayn-schedule-quota/proposal.md

- Source: openspec/changes/unify-dayn-schedule-quota/proposal.md
- Lines: 1-36
- SHA256: d91c3969f0d27470e4b1b20d57766c73114ef2c9801480c358b992a453bb12ee

```md
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
```

## openspec/changes/unify-dayn-schedule-quota/design.md

- Source: openspec/changes/unify-dayn-schedule-quota/design.md
- Lines: 1-105
- SHA256: cf7efc2b8e86f9c06e1bc74dd66763cc7881809faffad6f39fabaecd5f7b1324

[TRUNCATED]

```md
## Context

本次变更围绕硫化排程月计划日计划量 `dayN` 的使用口径统一。当前系统已经存在日计划额度账本、提前生产临时前移视图、欠产增机台协作器、续作降模、日标准产量修正、硫化余量和收尾目标量等多条链路。历史修复中已经部分明确“`dayN` 只用于准入和节奏判断”，但代码中仍可能存在旧口径残留：把窗口日计划量、账本剩余额度或 `day1 + day2 + day3` 反向当成非收尾实际排产上限。

需要重点区分两类用法：

- 合法用法：`dayN` 用于判断 SKU 是否可开始排、是否允许提前一天生产、当前机台数是否满足当前日/后看计划节奏、续作是否存在冗余机台、日计划账本如何记录和对账。
- 禁止用法：`dayN` 直接限制当天实际排产量、作为非收尾硬目标量、覆盖收尾目标量/硫化余量/胎胚库存/欠产追补/晚班不可换模补满，或简单用 `day1 + day2 + day3` 作为所有非收尾 SKU 的硬目标量。

当前已识别的核心落点：

- `ScheduleAdjustHandler` 初始化 `dailyPlanQuotaMap`、`windowPlanQty` 和历史欠产账本，是 `dayN` 进入运行态的入口。
- `DailyMachineExpansionPlanner` 负责小欠产、后看日计划和加机台节奏判断，允许继续保留 `dayN` 节奏判断。
- `EarlyProductionChecker` 与 `SkuDailyPlanQuotaUtil.buildShiftedQuotaMap` 负责提前生产只提前一天和临时前移视图。
- `TargetScheduleQtyResolver` 负责目标排产量收敛，是排查 `windowPlanQty` 是否误当硬上限的关键点。
- `ContinuousProductionStrategy` 负责续作排产、降模、日标准产量修正、续作账本消费、补偿 SKU 回流和共用胎胚未排。
- `NewSpecProductionStrategy` 与 `TypeBlockProductionStrategy` 负责新增/换活字块实际排产量、候选机台和账本消费。
- `ResultValidationHandler` 负责日计划完成校验和滚动账本日志，应保留对账能力，但日志不能被理解为硬控量依据。

## Goals / Non-Goals

**Goals:**

- 统一新增、续作、换活字块、加机台、降模和提前生产中的 `dayN` 语义。
- 保留 `dayN` 的节奏判断价值，清理实际排产量硬控残留。
- 保持收尾 SKU 严格目标量优先：非共用胎胚取 `MAX(硫化余量, 胎胚库存)`，共用胎胚只取硫化余量，余量为 0 不排并进入未排。
- 保持非收尾 SKU 由硫化余量、可用产能、班产、晚班不可换模、机台/模具约束、欠产追补和具体策略场景决定实际排产量。
- 增加关键日志，使 `dayN`、目标量、节奏判断、加机台/降模和实际排产量可对账。
- 同步 OpenSpec 主规格，避免后续再次按旧口径开发。

**Non-Goals:**

- 不重写排程主流程，不改变 S4.3/S4.4/S4.5/S4.6 阶段划分。
- 不新增数据库表、字段或第三方依赖。
- 不改变月计划原始数据，不把提前生产临时视图写回月计划。
- 不删除日计划账本消费、日计划完成校验和滚动台账日志；这些仍用于节奏、追踪和对账。
- 不新增无业务依据的兜底分支或吞异常逻辑。

## Decisions

### 决策一：保留日计划账本，但把它限定为节奏与对账载体

`dailyPlanQuotaMap` 仍由 `ScheduleAdjustHandler` 初始化，继续承担日计划节奏、滚动补欠产、提前生产前移视图、结果对账和日志职责。

实际实现时不直接删除 `SkuDailyPlanQuotaUtil.consumeRollingQuota`、`refreshRollingFields` 等公共能力，而是排查调用方是否把账本剩余量用于截断非收尾实际排产量。收尾和仅补历史欠产的严格目标仍允许同步到账本，因为它们本身是业务目标量，不是普通 `dayN` 硬上限。

备选方案是废弃运行态日计划账本，只保留原始 `dayN`。该方案会破坏提前生产、欠产滚动、过程日志和结果校验，改动面过大，不采用。

### 决策二：加机台判断集中复用 `DailyMachineExpansionPlanner`

新增、续作补偿和换活字块回流到新增链路时，都应复用 `DailyMachineExpansionPlanner` 的当前日/后看日计划节奏判断。

加机台逻辑只回答“当前机台数是否满足当前日或后看日计划节奏”，不回答“本次实际排多少”。若当前机台数已满足当前日节奏，应停止继续加机台；若不满足，再进入现有候选筛选、产能模拟、模具、单控、晚班不可换模等规则。

备选方案是在每个策略里各自判断 dayN。该方案会制造新增、续作、换活字块三套口径，不采用。

### 决策三：实际排产目标按场景解析，禁止非收尾直接取 dayN 上限

收尾 SKU 的实际目标量继续由收尾目标量决定，严格不超排。非收尾 SKU 的实际目标量不得简单取 `dayN`、窗口日计划量或账本剩余额度作为硬上限；应结合硫化余量、可用产能、班产、欠产追补、晚班不可换模、机台/模具约束和策略场景计算。

实现阶段重点排查 `TargetScheduleQtyResolver`、`ContinuousProductionStrategy` 续作结果回裁、`NewSpecProductionStrategy` 新增结果收敛、`TypeBlockProductionStrategy` 换活字块结果写入和补偿 SKU 生成逻辑。

备选方案是只在新增排产入口放开 dayN 控量。该方案无法覆盖续作和换活字块旧逻辑，不采用。

### 决策四：续作降模只用 dayN 判断冗余，不改变续作目标量

多机台续作降模时，`dayN` 只用于判断保留机台数是否足够覆盖 T 日当前日计划节奏以及 T～T+2 后看节奏。减少机台后若保留机台数刚好满足 T 日当前日计划量，本轮不得继续减少。

减机台后的重分配仍需满足收尾目标量、硫化余量、欠产追补、晚班不可换模、非收尾补满可用产能、试制严格日计划等约束。

### 决策五：提前生产只前移一天的节奏视图，不前移实际生产硬目标

当前日无计划但下一业务日有计划的 SKU，只有满足提前生产准入时才可进入当前日新增机台判断；T+2 或更远计划不得提前到 T 日。

提前生产通过后，临时前移视图只用于新增机台判断、日计划节奏判断和产能模拟，不写回原始 `dailyPlanQuotaMap` 或月计划表，也不得成为实际排产硬上限。

### 决策六：日志按“判断链路 + 实际结果”补齐

新增日志应围绕已有关键节点补充，不在循环里无意义刷屏。推荐日志字段包括：工厂、业务日期、SKU、物料编码、机台、排程类型、`dayN` 摘要、是否可开始排、是否提前生产、当前机台数、节奏是否满足、是否加机台、是否降模、目标量、实际排产量、收尾标识、共用胎胚标识。

```

Full source: openspec/changes/unify-dayn-schedule-quota/design.md

## openspec/changes/unify-dayn-schedule-quota/tasks.md

- Source: openspec/changes/unify-dayn-schedule-quota/tasks.md
- Lines: 1-38
- SHA256: b2667d5016d5a43d0e689db892681db17dc27b0f737d489e1385d3c6583f7494

```md
## 1. 只读定位与影响面确认

- [ ] 1.1 排查 `ScheduleAdjustHandler` 中 `dayN`、`dailyPlanQuotaMap`、`windowPlanQty`、历史欠产和 T 日晚班完成量初始化口径，确认哪些字段仅用于节奏/账本。
- [ ] 1.2 排查 `DailyMachineExpansionPlanner` 的当前日/后看日计划判断、窗口无计划、仅补历史欠产和收尾清量逻辑，标记允许保留的 `dayN` 节奏判断。
- [ ] 1.3 排查 `TargetScheduleQtyResolver`、`NewSpecProductionStrategy`、`ContinuousProductionStrategy`、`TypeBlockProductionStrategy` 中所有以 `dayN`、窗口日计划量、账本剩余额度回裁实际排产量的代码点。
- [ ] 1.4 排查 `EarlyProductionChecker` 与 `SkuDailyPlanQuotaUtil.buildShiftedQuotaMap`，确认提前生产只提前一天且临时视图不污染原始账本。
- [ ] 1.5 排查 `ResultValidationHandler` 和日计划滚动账本日志，确认其仅用于对账，不作为后置硬控量依据。

## 2. 回归测试先行

- [ ] 2.1 增加非收尾 SKU `dayN` 小于可用班次产能时仍按可用产能排产的 focused test。
- [ ] 2.2 增加收尾 SKU 不被 `dayN` 调低或抬高目标量的 focused test，覆盖非共用胎胚 `MAX(硫化余量, 胎胚库存)` 和共用胎胚只按硫化余量。
- [ ] 2.3 增加加机台判断测试：当前机台数满足当前日 `dayN` 节奏时不继续加机台，不满足时进入既有新增机台规则。
- [ ] 2.4 增加续作降模测试：保留机台数刚好满足 T 日当前日计划节奏时停止继续减机台，非收尾降模后不因 `dayN` 浅排。
- [ ] 2.5 增加提前生产测试：仅允许 T+1 计划提前到 T 日，T+2 或更远计划不得提前到 T 日，前移视图不作为实际排产硬上限。
- [ ] 2.6 增加换活字块或换活字块回流新增场景测试，确认不再按 `dayN` 硬截断实际排产量。

## 3. 最小代码实现

- [ ] 3.1 调整新增排产目标量与结果回裁逻辑，清理非收尾 SKU 直接用 `dayN`、窗口日计划量或账本剩余额度作为实际硬上限的旧逻辑。
- [ ] 3.2 调整续作排产、续作降模和续作补偿逻辑，保留 `dayN` 节奏判断，实际目标量改由收尾/非收尾目标量、硫化余量、欠产追补、可用产能和晚班不可换模规则决定。
- [ ] 3.3 调整换活字块排产及其回流新增链路，确保实际排产量不被 `dayN` 硬控，并继续复用现有模具、活字块和机台约束。
- [ ] 3.4 调整提前生产链路，确保前移 `dayN` 只用于准入、节奏判断和加机台模拟，不写回原始账本，不截断实际排产量。
- [ ] 3.5 保留并校正日计划账本消费逻辑，使账本继续用于滚动对账、提前生产和结果日志，但不得反向覆盖非收尾实际排产目标。
- [ ] 3.6 保持收尾、共用胎胚零余量未排、晚班不可换模、模具校验、单控机台和欠产追补规则不被破坏。

## 4. 日志、注释与规格同步

- [ ] 4.1 在加机台、降模、提前生产和实际结果收口关键节点补充中文日志，输出 SKU、业务日期、`dayN`、节奏判断、是否加机台、是否降模、目标量和实际排产量。
- [ ] 4.2 为关键口径代码补充简洁中文注释，明确 `dayN` 只用于节奏判断，不作为非收尾实际排产硬上限。
- [ ] 4.3 实现完成后同步更新 `openspec/specs` 下相关主规格，确保 `add-machine-rule`、`continue-reduce-machine-rule`、`sku-early-production`、`mould-surplus-calculate`、`daily-standard-shift-plan` 写入最新中文规则。

## 5. 验证与复跑

- [ ] 5.1 运行涉及新增、续作、换活字块、提前生产、日计划账本的定向单元测试或回归测试。
- [ ] 5.2 运行 `openspec validate unify-dayn-schedule-quota --strict`，确保 proposal、design、delta specs 和 tasks 合法。
- [ ] 5.3 运行 `git diff --check`，确保无格式和尾随空白问题。
- [ ] 5.4 如用户指定业务日期，按指定日期启动或调用 `/lhScheduleResult/execute` 真实复跑；如未指定，优先使用历史 dayN 问题高发日期做真实复跑并对账结果表、未排表、换模表和过程日志。
```

## openspec/changes/unify-dayn-schedule-quota/specs/add-machine-rule/spec.md

- Source: openspec/changes/unify-dayn-schedule-quota/specs/add-machine-rule/spec.md
- Lines: 1-38
- SHA256: 6c8d881958c494f5b0d89a9953702d17ee0aabf2c7797ae1f65eaf45ab5dd103

```md
## ADDED Requirements

### Requirement: dayN 仅作为加机台节奏判断依据

系统 MUST 将月计划日计划量 `dayN` 仅用于判断当前机台数是否满足当前日或后看日计划节奏，不得将 `dayN`、窗口日计划量或 `day1 + day2 + day3` 作为非收尾 SKU 的实际排产量硬上限。

#### Scenario: 当前机台满足当前日节奏

- **WHEN** 非收尾 SKU 当前已承接机台数的有效产能已满足当前业务日 `dayN` 日计划节奏
- **THEN** 系统 MUST 判定当前日不需要继续加机台
- **AND** 系统 MUST NOT 因后续日计划继续提前增加机台
- **AND** 当前已承接机台的实际排产量 SHALL 继续按可用班次产能、硫化余量、欠产追补、晚班不可换模和机台约束计算

#### Scenario: 当前机台不满足当前日节奏

- **WHEN** 非收尾 SKU 当前已承接机台数无法满足当前业务日 `dayN` 日计划节奏
- **THEN** 系统 MUST 继续进入既有新增机台规则
- **AND** 系统 MUST 复用现有候选机台、模具、单控、晚班不可换模、产能模拟和低效短排校验

#### Scenario: 窗口日计划不得作为实际硬目标

- **WHEN** 非收尾 SKU 的 `day1 + day2 + day3` 小于当前可用班次产能
- **THEN** 系统 MUST NOT 仅按 `day1 + day2 + day3` 截断实际排产量
- **AND** 系统 SHALL 在规则允许且有可用产能时尽量排满可用班次产能

### Requirement: dayN 节奏判断必须可追溯

系统 MUST 在新增机台节奏判断中输出可追溯日志，日志包含 SKU、业务日期、当前日 `dayN`、后看日 `dayN`、当前机台数、节奏是否满足、是否进入加机台、最终目标量和实际排产量。

#### Scenario: 输出不加机台日志

- **WHEN** 系统因当前机台数已满足当前日或后看日计划节奏而不加机台
- **THEN** 日志 MUST 包含 SKU、业务日期、当前机台数、当前日 `dayN`、判断结果和不加机台原因

#### Scenario: 输出加机台日志

- **WHEN** 系统判定当前机台数不满足 `dayN` 节奏并进入新增机台规则
- **THEN** 日志 MUST 包含 SKU、业务日期、当前机台数、目标节奏日、目标日 `dayN` 和进入加机台原因
```

## openspec/changes/unify-dayn-schedule-quota/specs/continue-reduce-machine-rule/spec.md

- Source: openspec/changes/unify-dayn-schedule-quota/specs/continue-reduce-machine-rule/spec.md
- Lines: 1-34
- SHA256: 5e068d37f297d14639a34e3affdd35ef0193cdbe51dd469a419ff7d87c67338c

```md
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
```

## openspec/changes/unify-dayn-schedule-quota/specs/daily-standard-shift-plan/spec.md

- Source: openspec/changes/unify-dayn-schedule-quota/specs/daily-standard-shift-plan/spec.md
- Lines: 1-23
- SHA256: 86828943328aef79c4c8a599520b902f7c34fe6855eb6dc0d6e20ab4c98b85f8

```md
## ADDED Requirements

### Requirement: 日标准产量修正不得退化为 dayN 硬控量

系统 MUST 将 SKU 日标准产量和班次计划量修正限定为班次分配与结果展示口径，不得借由日标准产量修正、班次计划量收敛或结果回裁，把 `dayN` 重新作为非收尾 SKU 的实际排产硬上限。

#### Scenario: 非收尾续作日标准修正后仍保留可用产能

- **WHEN** 非收尾续作 SKU 的日标准产量修正完成
- **THEN** 系统 SHALL 保持非收尾续作排满可用产能规则
- **AND** 系统 MUST NOT 仅因当前日 `dayN` 较小而回裁已允许生产的可用班次产量

#### Scenario: 收尾日标准修正不突破严格目标

- **WHEN** 收尾 SKU 进入日标准产量或班次计划量修正
- **THEN** 系统 MUST 保持收尾目标量严格不超排
- **AND** 系统 MUST NOT 因 `dayN` 或日标准产量修正抬高收尾目标量

#### Scenario: 日计划完成校验仅用于对账

- **WHEN** 系统输出日计划超排、欠产或滚动账本日志
- **THEN** 日志 SHALL 用于对账和排查
- **AND** 系统 MUST NOT 将该校验结果作为后置截断非收尾实际排产量的依据
```

## openspec/changes/unify-dayn-schedule-quota/specs/mould-surplus-calculate/spec.md

- Source: openspec/changes/unify-dayn-schedule-quota/specs/mould-surplus-calculate/spec.md
- Lines: 1-23
- SHA256: 089334cb29dc102891e224051e0aec1c3187b15ecfa70892a23f027223839c4b

```md
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
```

## openspec/changes/unify-dayn-schedule-quota/specs/sku-early-production/spec.md

- Source: openspec/changes/unify-dayn-schedule-quota/specs/sku-early-production/spec.md
- Lines: 1-34
- SHA256: 48c73b05b93d12596bfc247c439544fbb8fb38274d7bd14e906b7ff046237f10

```md
## ADDED Requirements

### Requirement: 提前生产前移 dayN 仅用于节奏判断

系统 MUST 将提前生产构造的临时日计划量视图仅用于准入、日计划节奏判断和新增机台产能模拟，不得将前移后的 `dayN` 作为当天实际排产量硬上限，也不得写回原始月计划或原始 `dailyPlanQuotaMap`。

#### Scenario: 提前生产通过后不硬控实际排产量

- **WHEN** SKU 当前业务日无日计划且下一业务日计划允许提前一天生产
- **THEN** 系统 SHALL 使用前移后的 `dayN` 参与新增机台节奏判断
- **AND** 系统 MUST NOT 仅按前移后的当前日 `dayN` 截断实际排产量
- **AND** 实际排产量 SHALL 继续按非收尾或收尾场景目标量及资源约束计算

#### Scenario: T+2 计划不得提前到 T 日

- **WHEN** SKU 当前业务日和下一业务日均无有效 `dayN` 日计划量
- **AND** T+2 或更远日期存在有效 `dayN` 日计划量
- **THEN** 系统 MUST NOT 允许该 SKU 在 T 日通过提前生产准入
- **AND** 系统 MUST NOT 将 T+2 或更远日期计划前移到 T 日参与新增机台判断

#### Scenario: 前移视图仅影响当前轮次

- **WHEN** 系统为提前生产 SKU 构造临时日计划量视图
- **THEN** 原始日计划账本 MUST 保持原始业务日期和原始 `dayN`
- **AND** 排程结束后系统 MUST NOT 回写临时前移计划到月计划表

### Requirement: 提前生产 dayN 决策必须可追溯

系统 MUST 对提前生产准入和前移节奏判断输出可追溯日志，日志包含 SKU、当前业务日、后续计划日、原始 `dayN`、前移后 `dayN`、是否允许提前生产、是否进入加机台和实际排产量。

#### Scenario: 输出提前生产准入日志

- **WHEN** SKU 命中提前生产判断
- **THEN** 日志 MUST 包含当前业务日、下一业务日、原始当前日 `dayN`、原始下一日 `dayN`、准入结果和原因
```

