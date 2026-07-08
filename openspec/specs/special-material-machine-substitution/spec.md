# 特殊材料硫化机置换规则

## Purpose

硫化排程完成续作、换活字块、新增排产后，检查特殊材料清单中的物料。若特殊材料 SKU 仍有需排量，且在已排硫化排程结果中未匹配到任何机台，则触发硫化机置换逻辑。置换作为排程末尾的兜底补偿机制，从已排结果中寻找其他 SKU 的机台作为置换候选，确保特殊材料 SKU 尽量排上机台。

## Requirements

### Requirement: 置换步骤独立于排产主流程

系统 MUST 在 S4.5 新增规格排产完成后、S4.6 结果校验保存前，新增独立步骤 S4.5.1 执行特殊材料硫化机置换。置换逻辑不侵入续作、换活字块、新增排产主流程。

**Implementation:**
- `ScheduleStepEnum.S4_5_1_SPECIAL_MATERIAL_SUBSTITUTION`：新增排程步骤枚举
- `AbsLhScheduleTemplate.execute()`：在 S4.5 和 S4.6 之间插入 `doSpecialMaterialSubstitution()` 调用
- `SpecialMaterialSubstitutionHandler`：置换步骤处理器
- `SpecialMaterialMachineSubstitutionService`：置换核心服务

#### Scenario: 排程主流程完成后触发置换

- **WHEN** S4.4 续作和 S4.5 新增排产全部完成
- **THEN** 系统执行 S4.5.1 特殊材料硫化机置换步骤
- **AND** 置换完成后进入 S4.6 结果校验与保存

### Requirement: 置换触发条件

系统 MUST 仅对同时满足以下条件的特殊材料 SKU 触发置换：
1. SKU 命中特殊材料清单（物料编码或结构名称匹配）
2. SKU 仍有硫化余量或本次窗口需排量（未排数量 > 0）
3. SKU 在当前已排硫化排程结果中未匹配到任何机台

#### Scenario: 特殊材料 SKU 未排上且有需排量

- **WHEN** 特殊材料 SKU 在未排结果中
- **AND** 未排数量大于 0
- **AND** 该 SKU 不在排程结果列表中
- **THEN** 系统对该 SKU 触发置换逻辑

#### Scenario: 特殊材料 SKU 已排上机台

- **WHEN** 特殊材料 SKU 已在排程结果列表中
- **THEN** 系统不对该 SKU 触发置换逻辑

### Requirement: 候选机台筛选规则

系统 MUST 从当前已排硫化排程结果列表中筛选候选被置换机台，筛选条件：
1. 被置换 SKU 不能是特殊材料 SKU（避免特殊材料之间互相置换）
2. 机台硬匹配特殊材料 SKU（英寸范围、模具集合、特殊材料支持，复用 `LhMachineHardMatchUtil.isMachineHardMatched`）
3. 优先非续作 SKU 机台（`scheduleType` 不为 01），找不到再从续作 SKU 机台中选择

#### Scenario: 候选机台硬匹配通过

- **WHEN** 候选机台的英寸范围、模具集合和特殊材料支持均匹配特殊材料 SKU
- **THEN** 该机台进入候选列表

#### Scenario: 候选机台上的 SKU 为特殊材料

- **WHEN** 候选机台上的 SKU 命中特殊材料清单
- **THEN** 该机台被排除出候选列表

### Requirement: 被置换机台4级优先级选择

系统 MUST 按以下优先级逐层匹配被置换机台，命中上层则不再比较下层：

| 优先级 | 置换类型 | 触发条件 | 多命中时排序 | 备注前缀 |
|-------|---------|---------|------------|---------|
| 1 | 喷砂清洗置换 | 排程日期起3天内有喷砂清洗计划（`CleaningTypeEnum.SAND_BLAST`） | 喷砂时间越近越优先 | 喷砂+置换 |
| 2 | 月计划降模置换 | 2天内存在月计划降模需求（多机台SKU的日计划量可由更少机台覆盖） | 降模时间越近越优先 | 月计划降模+置换 |
| 3 | 精度计划置换 | 30天内有精度保养计划（`maintenancePlanMap`） | 精度计划时间越近越优先 | 精度计划+置换 |
| 4 | 胎胚库存低置换 | 以上均未命中 | 胎胚库存低->SKU剩余可排量少->机台编码 | 胎胚库存低+置换 |

**Implementation:**
- `SubstitutionTypeEnum`：4级置换类型枚举，含 `buildRemark(machineCode, replacedMaterialCode)` 方法
- `SpecialMaterialMachineSubstitutionService.selectByPriority()`：逐层匹配选择

#### Scenario: 3天内喷砂清洗命中

- **WHEN** 候选机台在排程日期起3天内存在喷砂清洗计划
- **THEN** 该机台被选为被置换机台
- **AND** 置换类型为 `SAND_BLAST_SUBSTITUTION`
- **AND** 多个机台命中时选择喷砂清洗计划时间最近的

#### Scenario: 2天内月计划降模命中

- **WHEN** 候选机台无3天内喷砂清洗
- **AND** 该机台SKU有多台机台且2天内月计划日计划量可由更少机台覆盖
- **THEN** 该机台被选为被置换机台
- **AND** 置换类型为 `MONTH_PLAN_REDUCE_SUBSTITUTION`

#### Scenario: 30天内精度计划命中

- **WHEN** 候选机台无3天内喷砂清洗和2天内月计划降模
- **AND** 该机台在30天内有精度保养计划
- **THEN** 该机台被选为被置换机台
- **AND** 置换类型为 `PRECISION_PLAN_SUBSTITUTION`

#### Scenario: 胎胚库存低命中

- **WHEN** 候选机台未命中前三级
- **THEN** 按胎胚库存升序选择
- **AND** 置换类型为 `LOW_EMBRYO_STOCK_SUBSTITUTION`

### Requirement: 被置换 SKU 下机与状态回滚

系统 MUST 将被置换 SKU 从目标机台下机，并同步回滚以下状态：

| 状态 | 回滚方式 |
|------|---------|
| `scheduleResultList` | 移除该机台所有排程结果 |
| `scheduleResultSourceSkuMap` | 移除对应 entry |
| `machineAssignmentMap` | 移除该机台 entry |
| `machineScheduleMap` | 从 `initialMachineScheduleMap` 恢复初始快照 |
| `dailyMouldChangeCountMap` | 调用 `IMouldChangeBalanceStrategy.rollbackMouldChange()` 按换模时间递减 |
| `dailyFirstInspectionCountMap` | 调用 `IFirstInspectionBalanceStrategy.rollbackInspection()` 按首检时间递减 |
| `mouldResourceContext` | 调用 `MouldResourceContext.release()` 释放被置换结果占用的模具号 |

被置换 SKU 若仍有未排余量，须记录未排原因"被特殊材料SKU置换下机，机台 {机台编码}"。

**Implementation:**
- `SpecialMaterialMachineSubstitutionService.removeReplacedSkuFromMachine()`：移除结果并回滚状态
- `SpecialMaterialMachineSubstitutionService.restoreMachineState()`：从初始快照恢复机台状态
- `SpecialMaterialMachineSubstitutionService.releaseMouldResources()`：释放模具资源

#### Scenario: 被置换 SKU 下机并回滚

- **WHEN** 特殊材料 SKU 成功置换到目标机台
- **THEN** 被置换 SKU 从该机台下机
- **AND** 机台状态恢复到初始快照
- **AND** 换模计数和首检计数递减
- **AND** 模具资源释放
- **AND** 被置换 SKU 记录未排原因

### Requirement: 特殊材料 SKU 上机复用现有排产逻辑

系统 MUST 在被置换 SKU 下机后，将特殊材料 SKU 加回新增待排列表，重新调用 `IProductionStrategy.scheduleNewSpecs()` 排产策略，让特殊材料 SKU 按现有规则（SKU排序、机台匹配、换模均衡、首检均衡、产能计算、班次分配、胎胚库存调整）上机。

**Implementation:**
- `SpecialMaterialMachineSubstitutionService.reScheduleSpecialMaterialSku()`：复用 S4.5 排产策略链

#### Scenario: 特殊材料 SKU 成功上机

- **WHEN** 被置换机台已释放
- **AND** 特殊材料 SKU 通过现有排产逻辑匹配到该机台
- **THEN** 特殊材料 SKU 排产结果写入 `scheduleResultList`
- **AND** 机台状态更新为特殊材料 SKU

#### Scenario: 特殊材料 SKU 上机失败

- **WHEN** 被置换机台已释放
- **AND** 特殊材料 SKU 因换模约束、模具不足等原因未能上机
- **THEN** 特殊材料 SKU 记录未排原因
- **AND** 被置换 SKU 也记录未排原因

### Requirement: 模具交替计划备注规则

系统 MUST 在特殊材料 SKU 置换成功后，将置换备注记录到 `LhScheduleContext.substitutionRemarkMap`（key=被置换机台编码，value=置换备注）。S4.6 生成模具交替计划时，调用 `ResultValidationHandler.appendSubstitutionRemark()` 将备注追加到对应机台的模具交替计划 `remark` 字段。

备注格式：`{置换类型前缀} {机台编码}，被置换SKU：{物料编码}`

示例：
- `喷砂+置换 K1201，被置换SKU：3302001318`
- `月计划降模+置换 K1506，被置换SKU：3302001573`
- `精度计划+置换 K1201，被置换SKU：3302001318`
- `胎胚库存低+置换 K1201，被置换SKU：3302001318`

**Implementation:**
- `SubstitutionTypeEnum.buildRemark()`：构建备注文本
- `LhScheduleContext.substitutionRemarkMap`：存储置换备注
- `ResultValidationHandler.appendSubstitutionRemark()`：追加备注到交替计划

#### Scenario: 置换备注写入交替计划

- **WHEN** 特殊材料 SKU 置换成功
- **AND** S4.6 生成模具交替计划
- **THEN** 对应机台的交替计划备注包含置换类型和被置换机台编码

### Requirement: 置换机台数按加机台规则计算

系统 MUST 按现有加机台规则计算特殊材料 SKU 的应需机台数，需要几台就尝试置换几台。每台置换独立校验并执行换模/换活字块、首检、晚班禁换模、换模次数上限、模具数量、单控机台等约束。

**Implementation:**
- `SpecialMaterialMachineSubstitutionService.calculateRequiredMachineCount()`：计算需置换机台数

#### Scenario: 需置换2台但仅成功1台

- **WHEN** 按加机台规则需要2台
- **AND** 最终只有1台满足置换和上机约束
- **THEN** 系统只置换成功的1台
- **AND** 剩余1台记录未排原因"特殊材料SKU置换不足：按加机台规则需要 2 台，实际仅成功置换 1 台"

### Requirement: 置换失败输出明确未排原因

系统 MUST 在置换失败时记录明确未排原因：

| 失败场景 | 未排原因 |
|---------|---------|
| 无可置换机台 | 特殊材料SKU未匹配到可置换机台 |
| 置换后仍未能排上 | 特殊材料SKU置换后仍未能排上机台 |

#### Scenario: 无可置换机台

- **WHEN** 没有任何候选机台硬匹配特殊材料 SKU
- **THEN** 特殊材料 SKU 记录未排原因"特殊材料SKU未匹配到可置换机台"

### Requirement: 多个特殊材料 SKU 排序规则

系统 MUST 对多个待置换特殊材料 SKU 按以下规则排序：未排数量大的优先 -> 物料编码兜底排序。

#### Scenario: 多个特殊材料 SKU 同时未排

- **WHEN** 多个特殊材料 SKU 同时未排上机台
- **THEN** 系统按未排数量降序排序后逐个执行置换

## Implementation Files

| 文件 | 类型 | 说明 |
|------|------|------|
| `ScheduleStepEnum.java` | 修改 | 新增 `S4_5_1_SPECIAL_MATERIAL_SUBSTITUTION` 枚举 |
| `SubstitutionTypeEnum.java` | 新建 | 4级置换类型枚举，含备注构建方法 |
| `AbsLhScheduleTemplate.java` | 修改 | 新增 `doSpecialMaterialSubstitution()` 抽象方法和调用 |
| `LhScheduleTemplateImpl.java` | 修改 | 绑定 `SpecialMaterialSubstitutionHandler` |
| `SpecialMaterialSubstitutionHandler.java` | 新建 | S4.5.1 置换步骤处理器 |
| `SpecialMaterialMachineSubstitutionService.java` | 新建 | 置换核心服务 |
| `LhScheduleContext.java` | 修改 | 新增 `substitutionRemarkMap` 字段 |
| `ResultValidationHandler.java` | 修改 | 新增 `appendSubstitutionRemark()` 追加置换备注到交替计划 |
