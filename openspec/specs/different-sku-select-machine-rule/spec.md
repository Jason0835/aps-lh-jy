# 不同类型 SKU 选机规则

## Purpose

规范硫化排程中试制、量试、小批量和正规 SKU 的候选机台选择优先级，确保 SKU 排序仍复用项目现有逻辑，只在轮到具体 SKU 后按 SKU 类型执行机台类型硬约束、单控粒度和候选排序规则。

## 实现说明

### 试制 SKU 换模早班豁免机制

试制 SKU 的换模/换活字块属于开产前的准备动作，必须在早班完成，使首检计划量归入中班、
生产从中班开始。系统通过以下两层豁免保障该规则：

1. **开产模式豁免**：`ShiftProductionControlUtil.resolveEarliestSwitchStartTime` 新增接收
   `SkuScheduleDTO` 参数的重载方法，试制 SKU（`ConstructionStageEnum.TRIAL`）直接返回请求时间，
   不推迟到开产班次开始时间。非试制 SKU 或 `sku` 为 `null` 时退回原有逻辑。

2. **维保重叠重新评估**：当 `shouldApplyMaintenanceOverlapSwitchRule` 返回 `true` 时，
   `LhMaintenanceScheduleService.isNormalSwitchOverlapMaintenance` 进一步检查正常换模窗口
   （机台就绪时间 + 正常换模时长）是否与维保窗口物理重叠。若无重叠，使用正常换模；
   若有重叠，清除维保窗口使换模在早班完成，维保在后续排程迭代中重新安排。

### 影响的调用点

| 文件 | 方法 | 说明 |
|------|------|------|
| `NewSpecProductionStrategy` | 新增排产主路径换模就绪时间计算 | 传入 `sku`，增加试制维保重评 |
| `NewSpecProductionStrategy` | 局部搜索预览路径换模就绪时间计算 | 传入 `sku` |
| `TypeBlockProductionStrategy` | `resolveTypeBlockSwitchReadyTime` | 新增 `sku` 参数 |
| `TypeBlockProductionStrategy` | `canScheduleSpecifySkuByNewSpecPath` | 传入 `specifySku` |
| `LocalSearchMachineAllocatorStrategy` | 局部搜索换模开始时间 | 传入 `sku` |
| `ShiftProductionControlUtil` | `resolveEarliestSwitchStartTime` 重载 | 新增 `sku` 参数 |
| `LhMaintenanceScheduleService` | `isNormalSwitchOverlapMaintenance` | 新增方法 |
| `LhMaintenanceScheduleService` | `clearMaintenanceWindows` | 新增方法 |

## Requirements

### Requirement: SKU 排序不得因机台类型规则改变

系统 MUST 先按项目现有 SKU 排序规则确定 SKU 顺序，再在轮到具体 SKU 后执行对应选机规则：

1. SKU 排序优先级保持为试制组、量试组、正规组；
2. 小批量 SKU 归入正规组；
3. 小批量 SKU 与正规 SKU 按正规组统一排序规则排序；
4. 不得额外增加“小批量优先于正规使用单控机台”的特殊排序约束。

#### Scenario: 小批量和正规 SKU 竞争单控机台时不改变 SKU 排序

- **WHEN** 小批量 SKU 与正规 SKU 同时参与新增排产
- **AND** 两者都存在可用单控候选
- **THEN** 系统 MUST 先使用现有 SKU 排序结果确定先后顺序
- **AND** 系统 MUST NOT 因小批量可使用单边单控机台而额外抢占排序优先级

### Requirement: 不同 SKU 类型必须执行对应机台类型规则

系统 MUST 在轮到具体 SKU 后按 SKU 类型选择候选机台：

1. 试制 SKU 只能选择单控机台，并且只能安排在中班开始生产，首检计划量也归入中班；
2. 量试 SKU 优先选择单控机台，单控不可用、产能不足或不满足约束时，可以选择普通机台；
3. 小批量 SKU 优先选择单控机台，单控不可用、产能不足或不满足约束时，可以选择普通机台；
4. 正规 SKU 优先选择普通机台，普通机台中条件少、约束少的机台优先；
5. 正规 SKU 只有在普通机台不可用、产能不足或不满足约束时，才可以选择单控整机候选；
6. 正规 SKU 不允许选择单控单边候选，单控候选必须由同一物理机台 L/R 两侧成组生成。

#### Scenario: 试制 SKU 只能选择单控中班

- **WHEN** 试制 SKU 进入候选机台选择
- **THEN** 系统 MUST 只保留满足约束的单控机台候选
- **AND** 系统 MUST 将生产开班和首检计划量归入中班
- **AND** 系统 MUST NOT 选择普通机台候选

#### Scenario: 试制 SKU 换模必须在早班完成

- **WHEN** 试制 SKU 进入新增排产或换活字块排产
- **AND** 机台处于开产模式（开产班次为中班）
- **THEN** 系统 MUST NOT 将换模/换活字块开始时间推迟到开产班次开始时间
- **AND** 系统 MUST 允许换模在早班开始，使换模在早班内完成（正常8小时换模）
- **AND** 系统 MUST 使换模完成时间落在早班，从而触发试制首检归中班规则
- **AND** 系统 MUST 使中班计划量等于硫化余量（含首检条数）

#### Scenario: 试制 SKU 换模与维保窗口重叠时优先换模

- **WHEN** 试制 SKU 进入新增排产
- **AND** 机台已被安排维保窗口（如"首个规格收尾后保养"）
- **AND** 维保窗口触发维保重叠规则（shouldApplyMaintenanceOverlapSwitchRule=true）
- **THEN** 系统 MUST 检查正常换模窗口（机台就绪时间 + 正常换模时长）是否与维保窗口物理重叠
- **AND** 若无实际重叠（换模可在维保开始前完成），系统 MUST 使用正常换模，不等待维保结束
- **AND** 若有实际重叠，系统 MUST 清除维保窗口使试制换模在早班完成，维保在后续排程迭代中重新安排
- **AND** 系统 MUST NOT 对试制 SKU 使用维保重叠短时长换模（4小时），必须使用正常换模时长（8小时）

#### Scenario: 量试和小批量 SKU 单控不可用时可以回落普通机台

- **WHEN** 量试或小批量 SKU 进入候选机台选择
- **AND** 单控机台不可用、产能不足或不满足既有资源约束
- **THEN** 系统 MAY 选择满足条件的普通机台候选

#### Scenario: 正规 SKU 优先普通机台再回落单控整机

- **WHEN** 正规 SKU 的候选机台列表同时包含普通机台和单控物理机台
- **AND** 单控物理机台 L/R 两侧均满足该 SKU 的既有约束
- **THEN** 系统 MUST 将普通机台排在单控整机候选之前
- **AND** 系统 MUST NOT 生成该正规 SKU 的单控单边候选

### Requirement: 单控机台粒度必须由 SKU 类型决定

系统 MUST 按 SKU 类型决定单控机台占用粒度：

1. 试制、量试、小批量 SKU 使用单控机台时按单边粒度处理；
2. 正规 SKU 使用单控机台时必须按 L/R 整机粒度处理；
3. 单边粒度 SKU 可独立使用 `K1501L`、`K1501R`、`K1502L`、`K1502R`；
4. 整机粒度 SKU 必须同步占用、同步写入和同步释放同一物理机台的 L/R 两侧。

#### Scenario: 正规 SKU 不得保留单边单控候选

- **WHEN** 正规 SKU 只有 `K1501L` 或 `K1501R` 单侧通过候选机台硬过滤
- **THEN** 系统 MUST NOT 保留该单侧候选
- **AND** 系统 MUST 继续尝试其他普通机台或满足 L/R 成组条件的单控整机候选
