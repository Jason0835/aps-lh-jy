# 试制、量试 SKU 日计划准入规则

## Purpose

规范试制、量试 SKU 在进入新增排产前的日计划准入判断，确保系统按排程窗口和可提前生产完整范围读取对应产品状态的月计划日计划量。整个范围无正计划量时直接进入未排，避免通过提前生产、换活字块、特殊置换或空闲产能补排等后置入口上机。

## Requirements

### Requirement: 试制量试新增 SKU 必须先执行日计划准入

系统 SHALL 在完成 MES 和滚动续作识别后、试制或量试 SKU 加入新增排产列表前，通过公共未排规则 `PendingSkuUnscheduledRule.evaluateDailyPlanAdmission` 判断是否存在可排日计划量或前日排程 T+1 交替承接关系。本规则现已由全量非续作 SKU 统一准入规则覆盖。

判断范围 SHALL 为闭区间 `scheduleDate` 至 `windowEndDate + earlyProductionDaysThreshold`。试制、量试类型 SHALL 按 `constructionStage=01/02` 识别，月计划 SHALL 按物料编码和产品状态精确读取。

#### Scenario: 判断范围内日计划量全部为零

- **WHEN** 非续作试制或量试 SKU 从排程窗口首日至窗口结束日后 N 天的日计划量全部小于等于 0
- **AND** 前日排程 T+1 交替计划不存在匹配的后物料
- **THEN** 系统 SHALL 不允许该 SKU 进入新增排产列表
- **AND** 系统 SHALL 写入未排原因“排程窗口及提前生产范围内无日计划量，且无前日排程T+1交替计划”
- **AND** 未排数量 SHALL 为 0

#### Scenario: 窗口内无计划但提前范围内存在计划

- **WHEN** 试制或量试 SKU 在排程窗口内日计划量全部为 0
- **AND** 窗口结束日后 N 天范围内任意一天日计划量大于 0
- **THEN** 系统 SHALL 允许该 SKU 按原顺序进入现有新增排产流程
- **AND** 系统 SHALL NOT 将准入成功解释为必须生成排程结果

#### Scenario: 计划只存在于阈值范围之外

- **WHEN** 试制或量试 SKU 在完整判断范围内日计划量全部为 0
- **AND** `windowEndDate + N + 1` 或更远日期存在日计划量
- **THEN** 系统 SHALL 仍将该 SKU 写入未排

### Requirement: 准入判断必须复用提前生产参数和跨月月计划

系统 SHALL 复用现有提前生产天数阈值解析、提前生产未来计划查找和 `MonthPlanDateResolver` 跨月取数逻辑，不得新增平行参数、重复数据库查询或独立跨月算法。

#### Scenario: 判断范围跨月或跨年

- **WHEN** `windowEndDate + N` 进入下一自然月或下一年度
- **THEN** 系统 SHALL 按业务日期所属实际年月读取对应月计划 `DAY_N`

#### Scenario: 同物料存在多个产品状态

- **WHEN** 同一物料的正规状态存在正日计划量
- **AND** 当前试制或量试产品状态在判断范围内无正日计划量
- **THEN** 系统 SHALL 仅依据当前 SKU 产品状态将其写入未排
- **AND** 系统 SHALL NOT 使用其他产品状态的计划量放行

### Requirement: 命中未排后必须彻底退出排产候选

系统 SHALL 在公共规则命中后将 SKU 目标量和剩余量清零，从结构待排集合和活跃胎胚集合移除，且不得登记到后置全量 SKU 索引。

#### Scenario: 后置排产入口尝试重新获取被拦截 SKU

- **WHEN** 试制或量试 SKU 已因完整判断范围无日计划进入未排
- **THEN** 换活字块、特殊置换、提前生产和空闲产能补排 SHALL NOT 再次获取或排产该 SKU

### Requirement: 续作和其他 SKU 类型保持原有行为

本规则 SHALL 仅处理完成续作识别后仍待进入新增排产的试制、量试 SKU，不得改变续作、正规、小批量 SKU 的准入、排序和排产规则。

#### Scenario: 试制或量试 SKU 已识别为续作

- **WHEN** 试制或量试 SKU 已按物料和产品状态匹配到 MES 或滚动续作机台
- **THEN** 系统 SHALL 跳过本新增 SKU 日计划未排规则
- **AND** 系统 SHALL 继续执行原续作逻辑

#### Scenario: 放行多个新增 SKU

- **WHEN** 多个试制、量试或其他类型 SKU 均未命中本规则
- **THEN** 系统 SHALL 保持原有遍历和排序顺序
