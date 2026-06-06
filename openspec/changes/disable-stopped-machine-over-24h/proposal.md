## Why

当前硫化排程会基于 MES 在机信息继续执行续作排产，但缺少“续作机台已停机超过 24 小时”的统一禁排口径。该缺口会导致实际已长期停机的机台仍参与续作或新增选机，排程结果与现场可执行性不一致，也会让原本依赖续作机台的 SKU 错过已有新增排产主链的重新排序与选机机会。

## What Changes

- 基于 MES 在机数据补充“停机超过 24 小时”的批量识别能力，并将命中机台加入本批次统一不可用机台集合。
- 调整续作主链：当续作 SKU 的原 MES 在机机台停机超过 24 小时时，不再留在原机台续作，而是按现有补偿/回流思路转入新增排产主链。
- 调整新增选机主链：统一复用现有机台过滤逻辑，确保停机超过 24 小时的机台不会参与新增排产候选。
- 增补过程日志，记录机台禁排原因和续作 SKU 转入新增排产的关键字段，便于排程结果追溯。

## Capabilities

### New Capabilities
- `disable-stopped-machine-over-24h`: 基于 MES 在机停机时长统一禁排机台，并将受影响续作 SKU 回流至现有新增排产流程。

### Modified Capabilities

## Impact

- 影响代码范围预计集中在 `DataInitHandler`、`LhScheduleContext`、`ContinuousProductionStrategy`、`NewSpecProductionStrategy`、`DefaultMachineMatchStrategy` 以及 MES 在机数据相关 DTO / Mapper。
- 不新增独立排序分支，继续复用 `DefaultSkuPriorityStrategy`、现有新增待排队列和机台匹配规则。
- 不引入新依赖，不改变接口协议，主要影响本批次排程过程日志与候选机台过滤结果。
