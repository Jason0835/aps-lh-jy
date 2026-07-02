## ADDED Requirements

### Requirement: 新增链路收尾和续作补偿 SKU 参与提前生产判定

系统 SHALL 对已经进入 `NewSpecProductionStrategy` 的新增收尾 SKU 和续作补偿 SKU 复用 SKU 提前生产准入规则；当当前业务日无日计划量且提前生产阈值范围内存在有效日计划量时，系统 SHALL 使用同一份 `EarlyProductionDecision` 判断是否允许保留当前业务日换模和开产，并在实际生成结果后回写提前生产备注与 `IS_EARLY_PRODUCTION`。

#### Scenario: 新增收尾 SKU 提前排产时回写提前生产标识

- **WHEN** 新增收尾 SKU 当前业务日无日计划量
- **AND** 提前生产阈值范围内存在有效 `futurePlanDate` 日计划量
- **AND** SKU 通过提前生产准入并生成新增排产结果
- **THEN** 系统 SHALL 将结果 `IS_EARLY_PRODUCTION` 写为 `1`
- **AND** 系统 SHALL 按提前生产备注规则追加结构计划机台数

#### Scenario: 续作补偿 SKU 通过提前生产准入时不强制顺延

- **WHEN** 续作补偿 SKU 已转入 S4.5 新增排产
- **AND** 当前业务日无日计划量
- **AND** 提前生产阈值范围内存在有效 `futurePlanDate` 日计划量
- **AND** SKU 通过提前生产准入
- **THEN** 系统 SHALL 保留当前业务日首台换模和首个可排时间
- **AND** 系统 SHALL 继续执行候选机台、模具、胎胚、换模、首检、单控保护和日计划回裁等既有校验

#### Scenario: 续作补偿 SKU 通过提前生产准入时保持原新增队列顺序

- **WHEN** 续作补偿 SKU 已转入 S4.5 新增排产
- **AND** 该 SKU 通过提前生产准入
- **THEN** 系统 SHALL 保持 S4.5 已有新增 SKU 排序顺序
- **AND** 系统 SHALL NOT 因提前生产准入将补偿 SKU 前移到普通新增 SKU 前

#### Scenario: 续作补偿 SKU 单控候选必须真实落在当前业务日开产

- **WHEN** 续作补偿 SKU 通过提前生产准入并按既有选机顺序试算到单控候选
- **AND** 某个单控候选经过换模和首检试算后的开产业务日不是当前业务日
- **THEN** 系统 SHALL 回滚该候选的换模和模具预占
- **AND** 系统 SHALL 记录该候选排除原因为提前生产开产未落在当前业务日
- **AND** 系统 SHALL 继续尝试后续候选机台

#### Scenario: 正规 SKU 单控候选作为普通机台后的回落候选

- **WHEN** 正规 SKU 的候选机台列表中同时存在非单控机台和单控机台
- **THEN** 系统 SHALL 保留单控机台候选
- **AND** 系统 SHALL 将非单控机台排在单控机台之前

#### Scenario: 非新增链路续作结果仍不重新判定提前生产

- **WHEN** 结果直接由 `ContinuousProductionStrategy` 生成
- **THEN** 系统 SHALL 保持 `IS_EARLY_PRODUCTION` 为 `0`
- **AND** 系统 SHALL NOT 调用新增排产提前生产判定
