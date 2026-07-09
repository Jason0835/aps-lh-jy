# 同班次总计划量上限起排班次限制规则

**状态：已实现** | **实现日期：2026-07-09** | **适用链路：新增排产**

## Purpose

规范硫化排程中新增排产环节的同班次总计划量上限（`SYS0303004`）限制逻辑，确保该参数仅控制新增 SKU 的起排班次（上机班次），SKU 一旦上机后后续班次不再受限，避免中间班次空量导致生产不连续。

本规则不适用于续作排产，续作排产保持原有逻辑不变。

## Requirements

### Requirement: SYS0303004 仅限制新增 SKU 的起排班次

系统 SHALL 仅在新增排产（`NewSpecProductionStrategy.distributeToShifts`）中，对 SKU 尚未上机的班次判断 `SYS0303004` 同班次总计划量上限。

`SYS0303004` 是"起排门槛"，不是"每个班次都拦截"的限制。正确理解：

1. SKU 尚未上机时，需要判断当前班次是否超过 `SYS0303004`；
2. SKU 一旦成功上机，后续剩余班次不再受 `SYS0303004` 限制。

SKU 上机的判定条件包括以下任一：

1. 当前班次正常排入计划量；
2. 当前班次完成换模/换活字块并排入首检条数。

#### Scenario: 起排班次完整班产未超限

- **Given** `SYS0303004 = 100`
- **And** 当前班次已排总量 = 80
- **And** 当前 SKU 当前班次拟排量 = 16
- **When** 系统判断当前班次是否允许作为起排班次
- **Then** 80 + 16 = 96 <= 100，系统 MUST 允许当前班次作为起排班次
- **And** SKU 正常上机排产
- **And** 后续班次不再受 `SYS0303004` 限制

#### Scenario: 起排班次完整班产超限且无首检

- **Given** `SYS0303004 = 100`
- **And** 当前班次已排总量 = 95
- **And** 当前 SKU 当前班次拟排量 = 16
- **And** 当前 SKU 无首检排入
- **When** 系统判断当前班次是否允许作为起排班次
- **Then** 95 + 16 = 111 > 100，系统 MUST NOT 允许当前班次作为起排班次
- **And** 系统 MUST 顺延到下一个班次继续判断
- **And** 顺延后仍需遵守换模、换活字块、首检、晚班禁换模、机台可用时间、模具、余量等原有约束

#### Scenario: 起排班次完整班产超限但首检不超限

- **Given** `SYS0303004 = 100`
- **And** 当前班次已排总量 = 96
- **And** 当前 SKU 当前班次正常拟排量 = 16
- **And** 当前 SKU 首检条数 = 4
- **And** 首检已在循环前排入当前结果
- **When** 系统判断当前班次是否允许排入常规产量
- **Then** 96 + 16 = 112 > 100，完整班产超过限制
- **And** 96 + 4 = 100 <= 100，首检条数不超过限制
- **And** 系统 MUST 允许当前班次保留首检计划量，不排常规产量
- **And** SKU MUST 视为已经上机
- **And** 后续班次 MUST 继续连续排产，不再受 `SYS0303004` 限制

#### Scenario: 首检归属班次在起排班次之前时当前班次不再受限

- **Given** SKU 首检已排入 C2 班次（首检归属班次 = C2）
- **And** 排产循环从 C3 班次开始（起排班次 = C3）
- **And** C3 已排总量 + SKU C3 拟排量 > SYS0303004
- **When** 系统判断 C3 是否允许排产
- **Then** 系统 MUST 识别首检已排入更早班次（C2 < C3），SKU 已经上机
- **And** 系统 MUST 跳过 SYS0303004 限制，直接在 C3 排常规产量
- **And** 系统 MUST NOT 在 C3 留空导致中间班次计划量为 0

#### Scenario: 首检归属班次等于起排班次时仅保留首检

- **Given** SKU 首检已排入 C3 班次（首检归属班次 = C3）
- **And** 排产循环从 C3 班次开始（起排班次 = C3）
- **And** C3 已排总量 + SKU C3 完整班产 > SYS0303004
- **And** C3 已排总量 + SKU 首检条数 <= SYS0303004
- **When** 系统判断 C3 是否允许排常规产量
- **Then** 系统 MUST 识别首检归属班次 = 当前班次
- **And** 系统 MUST 仅保留首检计划量，不排常规产量
- **And** SKU MUST 视为已经上机
- **And** 后续班次 MUST 继续连续排产

#### Scenario: SKU 上机后后续班次超限也要继续排

- **Given** SKU-A 已在 C1 成功上机
- **And** C2 已排总量 = 100
- **And** SKU-A C2 拟排量 = 16
- **When** 系统处理 C2 班次
- **Then** C2 MUST 继续排 SKU-A
- **And** 系统 MUST NOT 因 100 + 16 > `SYS0303004` 跳过 C2

### Requirement: 不做剩余容量部分填充

系统 SHALL NOT 在起排班次做剩余容量收敛（部分填充）。完整班产超限时，系统 MUST 整体顺延或仅保留首检，不得将班次计划量裁剪为剩余容量部分填充。

旧逻辑中 `resolveClassTotalRemainingCapacity` 收敛 `shiftMaxQty` 为剩余容量的做法已废弃，改为通过 `canIncreaseShiftQtyByClassTotalLimit` 对完整拟排量做整体判断。

#### Scenario: 起排班次剩余容量不足以排满班产时整体顺延

- **Given** `SYS0303004 = 100`
- **And** 当前班次已排总量 = 90
- **And** 当前 SKU 当前班次拟排量 = 16
- **And** 当前 SKU 无首检排入
- **When** 系统判断当前班次是否允许作为起排班次
- **Then** 90 + 16 = 106 > 100，系统 MUST 顺延到下一个班次
- **And** 系统 MUST NOT 将拟排量裁剪为 10（剩余容量）部分填充当前班次

### Requirement: 续作排产不受 SYS0303004 限制

系统 SHALL NOT 在续作排产中判断 `SYS0303004`。续作排产保持原有逻辑不变。

#### Scenario: 续作排产不受班次总量上限控制

- **Given** 续作排产场景
- **And** 当前班次已排总量接近 `SYS0303004`
- **When** 系统执行续作排产
- **Then** 系统 MUST NOT 因 `SYS0303004` 跳过或裁剪续作班次计划量

### Requirement: 已排总量统计口径

系统 SHALL 通过当前排程上下文中的已排结果列表（`context.getScheduleResultList()`）统计同班次已排总量。

统计口径：

```text
当前班次已排总量 = 已排结果 List 中当前班次所有 SKU 已排计划量之和
```

系统 MUST NOT 新增重复状态源，避免统计结果和实际排程结果不一致。未持久化到上下文的当前结果需叠加自身已有量避免重复计算。

#### Scenario: 统计包含当前班次所有 SKU

- **Given** 排程上下文中已有 3 个排程结果
- **And** 结果 A 在 C1 排产 30 条
- **And** 结果 B 在 C1 排产 40 条
- **And** 结果 C 在 C1 排产 20 条
- **When** 系统统计 C1 已排总量
- **Then** 系统 MUST 返回 30 + 40 + 20 = 90

### Requirement: 起排班次顺延日志

系统 SHALL 在因 `SYS0303004` 超限导致起排班次顺延时输出日志。日志至少包含：

1. 批次号；
2. 物料编码；
3. 机台号；
4. 班次索引；
5. 当前班次已排总量；
6. 拟排量；
7. 预计总量；
8. 上限值；
9. 顺延原因（首检已排入仅保留首检 / 起排班次顺延）。

#### Scenario: 日志能区分首检特殊规则和普通顺延

- **Given** 起排班次完整班产超过 `SYS0303004`
- **When** 首检已排入时
- **Then** 日志 MUST 包含"首检已排入，起排班次仅保留首检"
- **When** 无首检排入时
- **Then** 日志 MUST 包含"起排班次顺延"

### Requirement: 后置重分配保持原有 SYS0303004 检查

系统 SHALL 在新增排产的后置重分配方法中（增机台回填、收尾错峰、晚班不可换模补满等）保持原有 `SYS0303004` 检查逻辑不变。后置重分配不属于"起排班次"范畴，仍需遵守班次总量上限。

#### Scenario: 增机台回填仍检查班次总量上限

- **Given** 新增 SKU 增机台失败后原机台回填
- **When** 系统在回填班次分配量时
- **Then** 系统 MUST 仍通过 `canIncreaseShiftQtyByClassTotalLimit` 检查班次总量上限

## Parameters

| 参数编码 | 参数名称 | 默认值 | 单位 | 使用场景 |
| --- | --- | --- | --- | --- |
| `SYS0303004` | 同班次总计划量上限阈值 | 2800 | 条 | 新增排产起排班次判断 |

参数为空或 <= 0 时，系统 SHALL 按不限制处理，复用项目现有硫化参数读取和默认值处理逻辑。

## Implementation

### 核心实现

| 类 | 方法 | 说明 |
| --- | --- | --- |
| `NewSpecProductionStrategy` | `distributeToShifts` | 班次循环中增加 `skuStartedOnMachine` 标识，仅起排班次判断 `SYS0303004` |
| `NewSpecProductionStrategy` | `canIncreaseShiftQtyByClassTotalLimit` | 复用，判断完整拟排量是否超限 |
| `NewSpecProductionStrategy` | `resolveClassTotalQtyLimit` | 复用，解析 `SYS0303004` 配置 |
| `NewSpecProductionStrategy` | `resolveClassShiftScheduledQty` | 复用，统计班次已排总量 |
| `NewSpecProductionStrategy` | `resolveClassTotalRemainingCapacity` | 复用，被 `canIncreaseShiftQtyByClassTotalLimit` 内部调用 |
| `NewSpecProductionStrategy` | `logClassTotalQtyLimitSkip` | 复用，超限跳过日志 |

### 改动要点

1. 移除 `distributeToShifts` 班次循环中的 `resolveClassTotalRemainingCapacity` 收敛逻辑（不再部分填充）；
2. 增加 `skuStartedOnMachine` 标识，控制仅起排班次做 `SYS0303004` 判断；
3. 完整班产超限时：
   - 首检已排入（`firstInspectionQty > 0`）-> 当前班次仅保留首检，`skuStartedOnMachine = true`；
   - 无首检 -> 顺延到下一个班次继续判断；
4. 常规排产写入后 `skuStartedOnMachine = true`，后续班次不再判断 `SYS0303004`。

## 不变规则

本规格 SHALL NOT 改变以下既有业务语义：

1. 续作排产逻辑；
2. 换模、换活字块、首检归属班次判断；
3. 机台匹配、模具分配、胎胚库存分摊；
4. 日标准产量修正班次计划量；
5. 试制非收尾日计划额度限制；
6. 后置重分配方法（增机台回填、收尾错峰、晚班补满等）的 `SYS0303004` 检查；
7. 班次管控、停机、清洗、保养等可排产能扣减逻辑。
