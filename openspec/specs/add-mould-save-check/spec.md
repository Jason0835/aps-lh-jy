## Purpose

硫化排程结果的 `mouldCode` 必须保存本次排程实际使用的模具号，不能保存 SKU 关联的全部模具号。

新增换模和 SKU 增机台通过模具资源运行态分配具体模具号；换活字块不释放在机模具、不重新选择新模具，结果继续写当前机台在机实际模具号。

## Requirements

### Requirement: 新增换模结果必须写入实际分配模具号

新增排产发生真正换模时，系统 SHALL 将本次分配给当前机台和 SKU 的实际模具号写入 `LhScheduleResult.mouldCode`。

#### Scenario: 双模新增换模写入 2 个实际模具号
- **WHEN** 双模机台为新增 SKU 分配到 2 个可用模具号
- **THEN** 排程结果 `mouldCode` SHALL 只包含这 2 个模具号
- **AND** 不 SHALL 写入该 SKU 关联的其他模具号

#### Scenario: 单模新增换模写入 1 个实际模具号
- **WHEN** 单模机台为新增 SKU 分配到 1 个可用模具号
- **THEN** 排程结果 `mouldCode` SHALL 只包含这 1 个模具号
- **AND** 不 SHALL 写入该 SKU 关联的其他模具号

#### Scenario: 实际模具号不足时不生成错误结果
- **WHEN** 新增 SKU 可用模具数量小于候选机台模数
- **THEN** 当前候选机台 SHALL 被跳过
- **AND** 系统 SHALL 输出 SKU、机台、所需模具数量、可用模具数量、已占用模具号或不可用模具号

### Requirement: 模具分配必须按可用状态、占用状态和共用数量选择

SKU 选择模具时，系统 SHALL 先过滤模具台账不可用模具和本次排程已被其他 SKU 占用的模具，再按“模具关联 SKU 数量升序、模具号升序”选择。

#### Scenario: 优先选择共用 SKU 数量更少的模具
- **WHEN** 当前 SKU 有多个可用模具
- **AND** 这些模具关联的 SKU 数量不同
- **THEN** 系统 SHALL 优先选择关联 SKU 数量更少的模具

#### Scenario: 共用数量相同时按模具号升序
- **WHEN** 当前 SKU 有多个可用模具
- **AND** 这些模具关联的 SKU 数量相同
- **THEN** 系统 SHALL 按模具号升序选择

#### Scenario: 已占用模具不能被其他 SKU 复用
- **WHEN** 某模具号已被本次排程其他 SKU 占用
- **THEN** 当前 SKU 分配模具时 SHALL 排除该模具号

### Requirement: 真正换模必须释放前物料实际模具并绑定新物料模具

新增排产发生真正换模时，系统 SHALL 只释放当前候选机台前物料实际占用的模具号，并在新 SKU 分配成功后绑定新 SKU 实际使用模具号。

#### Scenario: 换模成功释放旧模具并绑定新模具
- **WHEN** 当前候选机台已有前物料实际模具号
- **AND** 新 SKU 模具分配成功
- **THEN** 系统 SHALL 从已占用模具集合释放前物料实际模具号
- **AND** 系统 SHALL 将新 SKU 实际分配模具号加入已占用模具集合

#### Scenario: 候选机台后续失败时回滚模具占用
- **WHEN** 新 SKU 模具分配成功后，候选机台在换模窗口、首检、产能或日计划回裁阶段失败
- **THEN** 系统 SHALL 释放本次新分配模具号
- **AND** 系统 SHALL 恢复该机台前物料实际模具号占用

### Requirement: 换活字块必须沿用在机实际模具号

换活字块不是更换整副模具，系统 SHALL 不释放当前机台在机模具号，不重新分配新 SKU 模具号，排程结果 SHALL 写入当前机台在机实际模具号。

#### Scenario: 换活字块结果不写新 SKU 额外模具
- **WHEN** 候选 SKU 与当前机台在机 SKU 满足换活字块条件
- **AND** 候选 SKU 关联了当前在机模具以外的其他模具号
- **THEN** 换活字块结果 `mouldCode` SHALL 只包含当前机台在机实际模具号
- **AND** 不 SHALL 包含候选 SKU 的其他模具号

#### Scenario: 换活字块不释放在机模具
- **WHEN** 系统生成换活字块结果
- **THEN** 当前机台在机模具号 SHALL 保持占用
- **AND** 后续其他 SKU 不 SHALL 选择这些模具号

### Requirement: 已分配结果的模具占用必须逐个模具号解析

系统 SHALL 按逗号拆分已分配结果中的 `mouldCode`，逐个模具号参与占用判断。

#### Scenario: 多个实际模具号逐个参与冲突判断
- **WHEN** 已分配结果 `mouldCode` 包含多个逗号分隔模具号
- **THEN** 机台匹配和模具资源校验 SHALL 将每个模具号单独视为已占用

### Requirement: 实现不得新增 SQL、XML、表结构或配置项

本规则 SHALL 复用 `skuMouldRelMap`、`modelInfoMap`、`machineScheduleMap` 和现有排程结果写入链路实现。

#### Scenario: 数据来源保持现有上下文
- **WHEN** 系统执行模具分配和结果赋值
- **THEN** 系统 SHALL 从现有排程上下文读取 SKU 模具关系、模具台账和机台模数
- **AND** 不 SHALL 新增表、XML 查询或配置项
