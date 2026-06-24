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
