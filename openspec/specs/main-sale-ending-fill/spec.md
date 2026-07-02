# 主销/常规产品 SKU 收尾补满规则

## Purpose

规范硫化排程中主销产品和常规产品 SKU 的收尾补满例外规则，使 `productionType` 为 `01` 或 `02` 的收尾 SKU 在满足运行态共用胎胚、胎胚在机、单机台 20:00 后收尾和结构机台数未达标条件时，可以补满当天中班与下一个晚班，同时保持其他产品类型、非运行态共用胎胚 SKU 收尾严格目标量和非收尾排产逻辑不变。

## Requirements

### Requirement: 收尾补满产品范围判断

系统 SHALL 使用月计划 `productionType` 判断 SKU 是否属于收尾补满适用产品范围。

#### Scenario: productionType 为 01

- **WHEN** SKU 月计划 `productionType = 01`
- **THEN** 系统 SHALL 将该 SKU 识别为收尾补满适用产品

#### Scenario: productionType 为 02

- **WHEN** SKU 月计划 `productionType = 02`
- **THEN** 系统 SHALL 将该 SKU 识别为收尾补满适用产品

#### Scenario: productionType 非 01 或 02

- **WHEN** SKU 月计划 `productionType` 不是 `01` 或 `02`
- **THEN** 系统 SHALL NOT 将该 SKU 识别为收尾补满适用产品

### Requirement: 运行态共用胎胚判断

系统 SHALL 使用硫化排程上下文中的运行态有效胎胚 SKU 集合判断 SKU 是否属于共用胎胚，并且只有当前胎胚仍有多个有效 SKU 参与排程时才允许继续判断收尾补满。

#### Scenario: 当前胎胚存在多个有效 SKU

- **WHEN** SKU 胎胚代码为 `embryoCode`
- **AND** `activeEmbryoSkuMap.get(embryoCode)` 中有效物料编码数量大于 `1`
- **THEN** 系统 SHALL 判定该 SKU 满足运行态共用胎胚条件

#### Scenario: 当前胎胚不存在多个有效 SKU

- **WHEN** SKU 胎胚代码为空
- **OR** `activeEmbryoSkuMap` 中不存在该胎胚代码
- **OR** `activeEmbryoSkuMap.get(embryoCode)` 中有效物料编码数量小于等于 `1`
- **THEN** 系统 SHALL NOT 触发 SKU 收尾补满
- **AND** 系统 SHALL 保持原 SKU 收尾目标量规则

### Requirement: 胎胚在机判断

系统 SHALL 从硫化排程上下文的 `embryoEndingFlagMap` 获取胎胚收尾标识，并且只有标识为 `0` 时才允许继续判断收尾补满。

#### Scenario: 胎胚收尾标识为 0

- **WHEN** SKU 胎胚代码为 `embryoCode`
- **AND** `embryoEndingFlagMap.get(embryoCode) = 0`
- **THEN** 系统 SHALL 判定该 SKU 满足胎胚在机条件

#### Scenario: 胎胚收尾标识非 0

- **WHEN** SKU 胎胚代码为 `embryoCode`
- **AND** `embryoEndingFlagMap.get(embryoCode)` 不是 `0`
- **THEN** 系统 SHALL NOT 触发 SKU 收尾补满
- **AND** 系统 SHALL 保持原 SKU 收尾目标量规则

#### Scenario: 胎胚收尾标识缺失

- **WHEN** SKU 胎胚代码为空
- **OR** `embryoEndingFlagMap` 中不存在该胎胚代码
- **THEN** 系统 SHALL NOT 触发 SKU 收尾补满
- **AND** 系统 SHALL 保持原 SKU 收尾目标量规则

### Requirement: 不适用范围保持严格目标量

不满足收尾补满适用范围、运行态共用胎胚、胎胚在机、20:00 后收尾、结构机台数未达标或 SKU 收尾任一条件时，SKU 收尾 SHALL 继续严格按原收尾目标量排产。

#### Scenario: 非适用产品类型 SKU 收尾

- **WHEN** SKU 月计划 `productionType` 不是 `01` 或 `02`
- **AND** SKU 命中收尾场景
- **THEN** 系统 SHALL NOT 因 SKU 收尾补满规则补满中班或晚班
- **AND** 系统 SHALL 保持原收尾目标量规则

#### Scenario: 非运行态共用胎胚 SKU 收尾

- **WHEN** SKU 月计划 `productionType` 为 `01` 或 `02`
- **AND** SKU 命中收尾场景
- **AND** SKU 当前不满足运行态共用胎胚条件
- **THEN** 系统 SHALL NOT 因 SKU 收尾补满规则补满中班或晚班
- **AND** 系统 SHALL 保持原收尾目标量规则

### Requirement: SKU 收尾 20:00 后允许补满

适用产品范围内的 SKU 收尾时，系统 SHALL 以单台机台的真实收尾时间独立判断是否允许补满。

#### Scenario: 收尾时间晚于 20:00 且结构机台数未达标

- **WHEN** SKU 月计划 `productionType` 为 `01` 或 `02`
- **AND** SKU 为收尾场景
- **AND** SKU 满足运行态共用胎胚条件
- **AND** SKU 对应胎胚收尾标识为 `0`
- **AND** 当前机台真实收尾时间晚于业务日 `20:00`
- **AND** 当前业务日该结构已排硫化机台数小于月计划统计结构计划硫化机台数
- **THEN** 系统 SHALL 允许该机台补满当天中班班产
- **AND** 系统 SHALL 允许该机台补满下一个晚班班产
- **AND** 系统 SHALL 同步更新该业务日该结构的已排硫化机台数统计

#### Scenario: 收尾时间等于 20:00

- **WHEN** SKU 月计划 `productionType` 为 `01` 或 `02`
- **AND** SKU 满足运行态共用胎胚条件
- **AND** SKU 对应胎胚收尾标识为 `0`
- **AND** 当前机台真实收尾时间等于业务日 `20:00`
- **THEN** 系统 SHALL NOT 触发 SKU 收尾补满

#### Scenario: 结构机台数已达标

- **WHEN** SKU 月计划 `productionType` 为 `01` 或 `02`
- **AND** SKU 满足运行态共用胎胚条件
- **AND** SKU 对应胎胚收尾标识为 `0`
- **AND** 当前业务日该结构已排硫化机台数大于等于月计划统计结构计划硫化机台数
- **THEN** 系统 SHALL NOT 触发 SKU 收尾补满
- **AND** 系统 SHALL 继续按原 SKU 收尾目标量排产

### Requirement: 多机台独立判断

同一适用产品范围内 SKU 在多个机台同时生产并收尾时，系统 SHALL 对每台机台分别判断胎胚在机、收尾时间和结构机台数限制。

#### Scenario: 多机台部分满足补满条件

- **WHEN** 同一 `productionType` 为 `01` 或 `02` 的 SKU 有多台续作机台
- **AND** SKU 满足运行态共用胎胚条件
- **AND** SKU 对应胎胚收尾标识为 `0`
- **AND** 只有部分机台真实收尾时间晚于业务日 `20:00`
- **AND** 结构已排硫化机台数仍小于结构计划硫化机台数
- **THEN** 系统 SHALL 只补满满足条件的机台
- **AND** 系统 SHALL NOT 补满不满足条件的机台

#### Scenario: 补满后结构机台数达到上限

- **WHEN** 适用产品范围内 SKU 的某台机台触发收尾补满
- **AND** 补满后该业务日该结构已排硫化机台数达到月计划统计结构计划硫化机台数
- **THEN** 后续同结构机台 SHALL NOT 再触发 SKU 收尾补满
