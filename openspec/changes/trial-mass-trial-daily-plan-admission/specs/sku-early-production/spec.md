## ADDED Requirements

### Requirement: 试制量试新增 SKU 必须先检查完整可提前生产范围

系统 SHALL 在完成 MES 和滚动续作识别后、试制或量试 SKU 加入新增排产列表前，通过全量非续作 SKU 公共未排规则检查从排程窗口首日至 `windowEndDate + earlyProductionDaysThreshold` 的日计划量及前日排程 T+1 交替承接关系。

试制、量试类型 SHALL 按 `constructionStage=01/02` 识别；日计划量 SHALL 按物料编码和产品状态精确读取，并在跨月或跨年时按业务日期所属实际年月读取对应月计划 `DAY_N`。

#### Scenario: 完整判断范围内无日计划量

- **WHEN** 非续作试制或量试 SKU 在排程窗口及窗口结束日后 N 天内的日计划量全部小于等于 0
- **AND** 前日排程 T+1 交替计划不存在匹配的后物料
- **THEN** 系统 SHALL 将该 SKU 写入未排结果
- **AND** 未排原因 SHALL 为“排程窗口及提前生产范围内无日计划量，且无前日排程T+1交替计划”
- **AND** 系统 SHALL 将其从新增列表、结构待排集合、活跃胎胚集合和后置全量 SKU 索引移除

#### Scenario: 提前生产范围内任意一天有计划

- **WHEN** 试制或量试 SKU 在窗口内无日计划量
- **AND** `windowEndDate + 1` 至 `windowEndDate + N` 任意一天日计划量大于 0
- **THEN** 系统 SHALL 允许该 SKU 按原顺序继续进入现有新增排产流程
- **AND** 系统 SHALL 继续执行原有提前生产、选机、模具、胎胚、换模、换活字块、首检和班次约束

#### Scenario: 同物料其他产品状态存在计划

- **WHEN** 当前试制或量试产品状态在完整判断范围内无日计划量
- **AND** 同物料其他产品状态存在日计划量
- **THEN** 系统 SHALL 仍将当前产品状态 SKU 写入未排

#### Scenario: 已识别为续作的试制量试 SKU

- **WHEN** 试制或量试 SKU 已匹配到 MES 或滚动续作机台
- **THEN** 系统 SHALL 跳过本新增 SKU 日计划未排规则
- **AND** 系统 SHALL 保持原续作排产逻辑
