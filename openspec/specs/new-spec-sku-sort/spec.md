## Purpose

规范新增排产（S4.5）阶段对 `newSpecSkuList` 进行 SKU 排序时的优先级层级，保证补偿 SKU、试制/量试 SKU、正规 SKU 等不同来源的 SKU 在排序阶段遵循统一规则。

## Requirements

### Requirement: 新增排产 SKU 排序层级

系统 MUST 在 S4.5 新增排产对 `newSpecSkuList` 进行排序时，依次按以下层级比较：

1. 施工阶段分组（试制 → 量试 → 小批量/正规）；
2. 组内排序：
   - 试制/量试组：延误天数 → 排产量 → 物料编码；
   - 正规组：定点机台 → 锁交期 → 延误天数 → 结构全收尾 → 供应链待排量（高优 → 周期 → 中优 → 常规）→ 开产靠后分 → 排产量 → 物料编码。

#### Scenario: 试制/量试组先于正规组

- **GIVEN** 新增 SKU 列表内同时包含试制/量试 SKU 与正规 SKU
- **WHEN** 系统执行新增排产 SKU 排序
- **THEN** 试制/量试 SKU 必须整体排在正规 SKU 之前

### Requirement: 续作补偿 SKU 排序无优先权

系统 MUST 在新增排产 SKU 排序中将续作补偿 SKU（`continuousCompensationSku=true`）与同施工阶段组的其它新增 SKU 按统一规则进行比较，不得仅因补偿标识为其提升排序名次。

#### Scenario: 补偿 SKU 与普通新增 SKU 同正规组

- **GIVEN** 续作补偿 SKU 与普通新增 SKU 同属正规组
- **AND** 普通 SKU 的高优先级待排量更大
- **WHEN** 系统执行新增排产 SKU 排序
- **THEN** 普通 SKU 必须排在补偿 SKU 之前
- **AND** 系统不得因 `continuousCompensationSku=true` 把补偿 SKU 提升到组内置顶

#### Scenario: 补偿 SKU 不越过试制/量试组

- **GIVEN** 续作补偿 SKU 位于正规组
- **AND** 新增 SKU 列表内同时包含试制/量试 SKU
- **WHEN** 系统执行新增排产 SKU 排序
- **THEN** 试制/量试 SKU 必须排在补偿 SKU 之前

### Requirement: 排序约束影响边界

本排序规则 MUST 仅作用于 SKU 排序顺序，不得改变：

- 续作补偿 SKU 的生成逻辑；
- 补偿 SKU 在 S4.5 选机时优先锁回原续作机台的局部规则；
- 日计划、胎胚库存、欠产、收尾、滚动衔接等补偿相关业务分支。

#### Scenario: 补偿 SKU 选机仍优先原续作机台

- **GIVEN** 续作补偿 SKU 已按统一规则参与新增排产排序
- **AND** 该 SKU 自己轮到选机
- **AND** 原续作机台仍在候选集中
- **WHEN** 系统执行机台匹配
- **THEN** 系统继续按既有规则优先锁回原续作机台
