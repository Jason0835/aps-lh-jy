## Why

2026-06-02 硫化排程批次 `LHPC20260602024` 复现两个提前生产异常：新增收尾 SKU `3302002481` 已提前到 2026-06-01 排产但 `IS_EARLY_PRODUCTION=0`，续作转入新增的补偿 SKU `3302002546` 因不能按提前生产留在首日，最终进入未排。

根因是 `NewSpecProductionStrategy` 在生成提前生产判定时排除了 `isEnding` 和 `continuousCompensationSku`，导致收尾新增结果无法回写提前生产标识，续作补偿 SKU 也无法复用 T+1 日计划量进入当前业务日新增机台判断。

## What Changes

- 修正新增排产提前生产判定范围：新增链路内的收尾 SKU、续作补偿 SKU 在满足 `currentDate + 1` 日计划和结构机台约束时，也应得到同一份 `EarlyProductionDecision`。
- 续作补偿 SKU 首台换模和首个开产时间在提前生产准入通过时保留当前业务日，不再一律顺延到后续有日计划日期。
- 保持候选机台、模具、胎胚、换模、首检、单控保护和日计划回裁等既有约束不变。

## Capabilities

### New Capabilities

- 无。

### Modified Capabilities

- `sku-early-production`: 补充新增排产收尾 SKU 和续作补偿 SKU 的提前生产准入与标识回写规则。

## Impact

- 影响代码：`aps-lh/src/main/java/com/zlt/aps/lh/engine/strategy/impl/NewSpecProductionStrategy.java`
- 影响测试：新增 `NewSpecProductionStrategyRegressionTest` 聚焦回归用例。
- 不涉及接口、表结构、Mapper XML 或配置项变更。
