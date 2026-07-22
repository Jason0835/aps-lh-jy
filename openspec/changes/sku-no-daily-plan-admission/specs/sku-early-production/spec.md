## MODIFIED Requirements

### Requirement: 非续作 SKU 必须先检查完整可提前生产范围及交替承接关系

系统 SHALL 在完成 MES 和滚动续作识别后、所有非续作 SKU 加入新增排产列表前，通过公共未排规则检查从 `scheduleDate` 至 `windowEndDate + earlyProductionDaysThreshold` 的日计划量。完整范围无正日计划量时，系统 SHALL 再检查前日排程 T+1 模具交替计划的后物料是否匹配当前 SKU。

日计划量 SHALL 按物料编码和产品状态精确读取，并在跨月或跨年时按业务日期所属实际年月读取对应月计划 `DAY_N`。提前生产天数 SHALL 读取现有硫化参数 `SYS0304028`。交替计划不包含产品状态，承接关系 SHALL 仅按后物料编码匹配。

#### Scenario: 窗口或提前生产范围内存在日计划量

- **WHEN** 非续作 SKU 在完整判断范围内任意一天日计划量大于 0
- **THEN** 系统 SHALL 保留该 SKU 并按原顺序进入现有排产主链

#### Scenario: 完整范围无日计划但存在交替承接

- **WHEN** 非续作 SKU 在完整判断范围内日计划量全部小于等于 0
- **AND** 前日排程 T+1 交替计划的后物料匹配当前 SKU 物料编码
- **THEN** 系统 SHALL 保留该 SKU 并继续执行现有历史反选及普通新增流程

#### Scenario: 完整范围无日计划且无交替承接

- **WHEN** 非续作 SKU 在完整判断范围内日计划量全部小于等于 0
- **AND** 前日排程 T+1 交替计划不存在匹配的后物料
- **THEN** 系统 SHALL 将该 SKU 写入未排结果
- **AND** 未排原因 SHALL 为“排程窗口及提前生产范围内无日计划量，且无前日排程T+1交替计划”
- **AND** 系统 SHALL 将其从新增列表、结构待排集合、活跃胎胚集合和后置全量 SKU 索引移除

#### Scenario: 同物料其他产品状态存在计划

- **WHEN** 当前 SKU 产品状态在完整判断范围内无正日计划量
- **AND** 同物料其他产品状态存在正日计划量
- **THEN** 系统 SHALL NOT 使用其他产品状态的计划量放行当前 SKU

#### Scenario: 已识别为续作的 SKU

- **WHEN** SKU 已匹配到 MES 或滚动续作机台
- **THEN** 系统 SHALL 跳过本非续作准入规则
- **AND** 施工阶段为 `01/02` 时 SHALL 继续执行续作试制量试专用日计划准入
