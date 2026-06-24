# Brainstorm Summary

- Change: unify-dayn-schedule-quota
- Date: 2026-06-24

## 确认的技术方案

- 本次目标是统一月计划日计划量 `dayN` 最新口径：`dayN` 只用于排产节奏与资源判断，不作为当天实际排产量硬上限。
- 变更范围覆盖新增排产、续作排产、换活字块排产、加机台、降模减机台、提前生产、窗口无计划但后续有计划等链路。
- OpenSpec delta 已覆盖 `add-machine-rule`、`continue-reduce-machine-rule`、`sku-early-production`、`mould-surplus-calculate`、`daily-standard-shift-plan`。
- 真实复跑业务日期已确认使用 `2026-06-14`；未指定重点 SKU，默认覆盖当天 dayN 相关的续作、新增、提前生产、窗口无计划和换活字块样例。
- 用户已确认采用方案 A：中心语义收口。
- 技术方案为保留日计划账本和 `DailyMachineExpansionPlanner`，继续让 `dayN` 做节奏判断、提前生产准入、加机台/降模判断和对账；同时在 `TargetScheduleQtyResolver`、`ContinuousProductionStrategy`、`NewSpecProductionStrategy`、`TypeBlockProductionStrategy` 等实际排产量落点清理 `dayN` 硬上限残留。

## 备选方案与取舍

- 方案 A：中心语义收口。保留日计划账本和 `DailyMachineExpansionPlanner`，在目标量解析、续作收口、新增/换活字块结果生成处清理 dayN 硬上限。已确认采用。
- 方案 B：各策略局部修补。分别在新增、续作、换活字块中移除明显 dayN 控量点。放弃原因：口径容易再次分裂。
- 方案 C：重建日计划账本语义。大幅改造账本和消费链路。放弃原因：风险和改动面过大，不符合最小改动。

## 关键取舍与风险

- 采用方案 A：以公共语义收口为主，代码仍按现有策略边界做最小改动。
- 必须区分合法的 dayN 节奏判断与禁止的 dayN 实际控量。
- 不能粗暴删除日计划账本消费，否则会破坏提前生产、滚动补欠产和对账日志。
- 风险：非收尾 SKU 放开 dayN 硬控后，实际排产量可能较历史结果增加，需要用 `2026-06-14` 真复跑核对是否符合新口径。

## 测试策略

- focused tests 覆盖非收尾放开硬控、收尾严格目标、加机台节奏、续作降模、提前生产只提前一天、换活字块链路。
- 真实复跑 `2026-06-14`，对账结果表、未排表、换模表和过程日志。

## Spec Patch

- 暂无待回写项；若后续 brainstorming 发现 delta spec 缺场景，再补充。

## 待确认问题

- 无。
