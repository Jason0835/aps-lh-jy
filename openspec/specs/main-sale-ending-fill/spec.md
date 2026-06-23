# 主销产品 SKU 收尾补满规则

## Purpose

规范硫化排程中主销产品 SKU 的收尾补满例外规则，使主销产品在满足单机台 20:00 后收尾和结构机台数未达标条件时，可以补满当天中班与下一个晚班，同时保持普通 SKU 收尾严格目标量和非收尾排产逻辑不变。

## Requirements

### Requirement: 主销产品判断

系统 SHALL 使用月计划 `productionType` 判断 SKU 是否为主销产品。

#### Scenario: productionType 为 01

- **WHEN** SKU 月计划 `productionType = 01`
- **THEN** 系统 SHALL 将该 SKU 识别为主销产品

#### Scenario: productionType 非 01

- **WHEN** SKU 月计划 `productionType` 不是 `01`
- **THEN** 系统 SHALL 将该 SKU 识别为普通产品

### Requirement: 普通收尾规则保持严格目标量

非主销产品 SKU 收尾 SHALL 继续严格按原收尾目标量排产。

#### Scenario: 普通 SKU 收尾

- **WHEN** SKU 不是主销产品
- **AND** SKU 命中收尾场景
- **THEN** 系统 SHALL NOT 因主销补满规则补满中班或晚班
- **AND** 系统 SHALL 保持原收尾目标量规则

### Requirement: 主销收尾 20:00 后允许补满

主销产品 SKU 收尾时，系统 SHALL 以单台机台的真实收尾时间独立判断是否允许补满。

#### Scenario: 收尾时间晚于 20:00 且结构机台数未达标

- **WHEN** SKU 为主销产品
- **AND** SKU 为收尾场景
- **AND** 当前机台真实收尾时间晚于业务日 `20:00`
- **AND** 当前业务日该结构已排硫化机台数小于月计划统计结构计划硫化机台数
- **THEN** 系统 SHALL 允许该机台补满当天中班班产
- **AND** 系统 SHALL 允许该机台补满下一个晚班班产
- **AND** 系统 SHALL 同步更新该业务日该结构的已排硫化机台数统计

#### Scenario: 收尾时间等于 20:00

- **WHEN** SKU 为主销产品
- **AND** 当前机台真实收尾时间等于业务日 `20:00`
- **THEN** 系统 SHALL NOT 触发主销收尾补满

#### Scenario: 结构机台数已达标

- **WHEN** SKU 为主销产品
- **AND** 当前业务日该结构已排硫化机台数大于等于月计划统计结构计划硫化机台数
- **THEN** 系统 SHALL NOT 触发主销收尾补满
- **AND** 系统 SHALL 继续按原 SKU 收尾目标量排产

### Requirement: 多机台独立判断

同一主销 SKU 在多个机台同时生产并收尾时，系统 SHALL 对每台机台分别判断收尾时间和结构机台数限制。

#### Scenario: 多机台部分满足补满条件

- **WHEN** 同一主销 SKU 有多台续作机台
- **AND** 只有部分机台真实收尾时间晚于业务日 `20:00`
- **AND** 结构已排硫化机台数仍小于结构计划硫化机台数
- **THEN** 系统 SHALL 只补满满足条件的机台
- **AND** 系统 SHALL NOT 补满不满足条件的机台

#### Scenario: 补满后结构机台数达到上限

- **WHEN** 主销 SKU 的某台机台触发收尾补满
- **AND** 补满后该业务日该结构已排硫化机台数达到月计划统计结构计划硫化机台数
- **THEN** 后续同结构机台 SHALL NOT 再触发主销收尾补满
