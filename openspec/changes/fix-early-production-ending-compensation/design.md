## Context

`sku-early-production` 主规格要求提前生产只放宽 S4.5 新增排产准入，并且结果的 `IS_EARLY_PRODUCTION` 与提前生产备注同源。当前实现中，提前生产判定由 `NewSpecProductionStrategy#resolveEarlyProductionDecision` 生成，再传给首个开产时间对齐和结果备注/标识回写。

真实批次 `LHPC20260602024` 显示：

- `3302002481` 为新增收尾 SKU，月计划日计划量为 `0,40,0`，结果提前落在 2026-06-01，但因 `isEnding` 被提前生产判定入口排除，结果没有提前生产备注，`IS_EARLY_PRODUCTION=0`。
- `3302002546` 为续作补偿转入新增，月计划日计划量为 `0,32,50`。补偿标识使其跳过提前生产判定，并按后续日计划顺延首台换模/开产时间；同时正规 SKU 在小批量待排未完成时被单控保护过滤 K1501R，最终只尝试普通机台且全部超出窗口，进入未排。

## Goals / Non-Goals

**Goals:**

- 让新增链路内的收尾 SKU、续作补偿 SKU 复用同一套 `EarlyProductionChecker` 准入口径。
- 提前生产准入通过时，补偿 SKU 不再因为首日无 dayN 额度而强制顺延到 T+1/T+2。
- 标识和备注继续由同一份 `EarlyProductionDecision` 回写，避免结果审计字段不一致。

**Non-Goals:**

- 不改变提前生产只允许提前一天的规则。
- 不放宽单控保护、模具、胎胚、换模、首检、晚班不可换模、停机窗口等资源约束。
- 不修改月计划、日计划原始账本或数据库结构。

## Decisions

1. 在 `resolveEarlyProductionDecision` 中移除 `isEnding` 和 `continuousCompensationSku` 的提前判定排除条件。
   - 理由：两者已进入 S4.5 新增排产，是否提前生产应由当前日/下一日 dayN 和结构机台数决定，而不是由来源标签直接否决。
   - 替代方案：在结果回写处单独判断收尾或补偿。该方案会制造第二套提前生产口径，容易出现备注和标识不一致。

2. 在首台换模和首个开产时间对齐处优先识别 `EarlyProductionDecision`。
   - 理由：续作补偿 SKU 当前代码会在首日无 dayN 额度时顺延；若已经通过提前生产准入，应保留当前业务日进入新增链路。
   - 替代方案：改动补偿 SKU 的 dayN 账本。该方案会污染共享账本，不符合主规格。

3. 用聚焦回归测试覆盖私有判定和时间对齐行为。
   - 理由：问题根因集中在 `NewSpecProductionStrategy` 内部判定与对齐方法，最小测试能稳定锁住 bug，不依赖外部数据库。

## Risks / Trade-offs

- [Risk] 收尾 SKU 命中提前生产后可能触发“保留机台到窗口结束”的判断分支。→ 仍由 `applyBlockToDailyQuota` 和收尾严格目标量回裁最终结果，不放大落库量。
- [Risk] 补偿 SKU 提前进入首日后仍可能被单控保护过滤。→ 本次不绕过单控保护；若当前 SKU 不具备小批量/试制等单控资格，仍按既有机台匹配规则执行。
