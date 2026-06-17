# SKU 提前生产规则

## 1. 规则目标

在硫化排程 T～T+2、共 3 天 8 个班次窗口不变的前提下，允许后续日期有 dayN 日计划量的 SKU 在满足准入条件时提前进入当前业务日新增机台判断。

提前生产只表示“允许进入当前日新增机台判断”，不直接分配机台，不搬运或改写 `dailyPlanQuotaMap`，不绕过候选机台、模具、胎胚、换模、换活字块、首检、晚班不可换模和班次产能等既有约束。

## 2. 适用范围

- 适用于 S4.5 新增排产 `NewSpecProductionStrategy`。
- T 日可判断 T+1/T+2 有 dayN 日计划量的 SKU。
- T+1 日可判断 T+2 有 dayN 日计划量的 SKU。
- 当前业务日本身有 dayN 日计划量的 SKU 不属于提前生产场景，继续走原逻辑。
- 欠产超过阈值时不受结构机台数限制，继续复用既有强制加机台逻辑。

## 3. 准入规则

### 3.1 后续日计划识别

当 SKU 当前业务日 `dayN <= 0`，且当前业务日之后、窗口结束日以内存在第一个 `dayN > 0` 的业务日时，才进入提前生产准入判断。

### 3.2 欠产阈值

使用项目已有新增排产欠产增机台阈值，默认值沿用现有参数口径。

当：

```text
本月前日累计欠产 > 欠产阈值
```

直接进入现有强制加机台逻辑，不执行结构机台数限制。

当：

```text
本月前日累计欠产 <= 欠产阈值
```

按业务日期 + 产品结构判断结构机台数。

### 3.3 结构机台数判断

判断公式：

```text
当前业务日该结构已排硫化机台数 < 该结构计划硫化机台数
```

成立时，允许该 SKU 提前进入当前业务日新增机台判断。

不成立时，不提前生产，保持顺延到后续计划日的逻辑。

### 3.4 结构切换

如果当前业务日该结构计划硫化机台数为 0，则改取该 SKU 后续第一个有 dayN 日计划量日期的结构计划硫化机台数。

例如 T 日判断 T+1 SKU 时，若该结构 T 日 `lhMachines = 0`，则使用 T+1 同结构 `lhMachines` 与 T 日该结构已排机台数比较。

### 3.5 结构已收尾但 SKU 余量较大

当当前业务日结构计划硫化机台数为 0，且后续计划日也无法提供有效结构计划机台数时，判断：

```text
本月前日累计欠产 > SKU 当前业务日已排硫化机台数 * SKU 日硫化量
```

成立时，允许进入现有强制加机台判断。

该条件与“本月前日累计欠产 > 欠产阈值”是 OR 关系。

## 4. 数据来源

结构计划硫化机台数来源于：

```text
T_MP_MONTH_PLAN_STATISTICS
```

查询条件：

```text
factoryCode
year
month
productionVersion
tempFlag = 0 OR tempFlag IS NULL OR tempFlag = ''
```

字段：

```text
DAY_1 ～ DAY_31
```

字段内容为标准 JSON 字符串，只读取 key：

```text
lhMachines
```

口径：

- 结构 key 使用 `structureName`。
- 同一业务日、同一 `structureName` 多条统计记录按 `lhMachines` SUM 聚合。
- `dayN` 为空或缺失 `lhMachines` 按 0 处理。
- `dayN` 非法 JSON 必须按基础数据异常中断排程，不允许静默按 0。

## 5. 上下文运行态

排程上下文维护以下运行态：

```java
Map<LocalDate, Map<String, Integer>> structurePlanMachineCountMap;
Map<LocalDate, Map<String, Set<String>>> structureScheduledMachineCodeMap;
Map<LocalDate, Map<String, Set<String>>> skuScheduledMachineCodeMap;
```

已排机台数统计规则：

- 结构维度：业务日期 + `structureName` + 机台编码 Set 去重。
- SKU 维度：业务日期 + `materialCode` + 机台编码 Set 去重。
- 同一结构多个 SKU 共用同一机台时，结构已排机台数只算 1 台。
- 同一 SKU 在同一机台多个班次生产时，SKU 已排机台数只算 1 台。
- 新增机台结果落入 `scheduleResultList` 后必须同步登记。
- 如果后续释放辅助机台、结果调整或清零，必须重建结构/SKU 已排机台统计。

## 6. 主流程要求

- 月计划统计表在基础数据初始化阶段批量查询并缓存，禁止在 SKU × 日期 × 结构循环中重复查库。
- 提前生产准入接入 `alignFirstProductionStartTimeByDailyPlan(...)`，在当前日无 dayN 且后续日有 dayN 时判断是否保留当前业务日开产。
- 准入失败时保持顺延到后续计划日。
- 准入成功后继续复用现有新增排产链路：候选机台、模具、胎胚、换模、换活字块、首检、晚班不可换模和班次产能。
- 结构已收尾但 SKU 余量较大时，只通过显式强制模式进入 `DailyMachineCapacitySimulationUtil` 的欠产阈值窗口回落判断，不修改欠产阈值本身。

## 7. 验证要求

必须覆盖以下场景：

- 当前日有 dayN 日计划量时走原逻辑。
- 当前日无计划、后续日有计划、结构已排机台数小于计划机台数时允许提前生产。
- 当前日无计划、后续日有计划、结构已排机台数达到计划机台数时不提前。
- 当前日结构 `lhMachines = 0` 时使用后续第一个计划日结构 `lhMachines` 判断。
- 当前日与后续日结构计划均为 0 时，仅结构收尾且 SKU 余量较大才允许进入强制加机台判断。
- 欠产超过阈值时不受结构机台数限制。
- dayN JSON 正常解析、缺失 `lhMachines`、非法 JSON 中断。
- 提前生产 SKU 仍受原有资源约束控制。
