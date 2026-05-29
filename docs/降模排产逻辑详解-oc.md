# 硫化排程系统 - 续作场景降模排产逻辑详解

## 一、降模排产的触发时机和适用范围

### 1.1 入口位置与步骤编排

降模排产在 `ContinuousProductionHandler` 第73行，步骤编号 **S4.4.5**：

```java
// S4.4.5 降模排产：对同 SKU 多机台续作按 dayN 保障量和收尾规则释放冗余机台。
strategy.scheduleReduceMould(context);
```

S4.4 续作排产的完整执行顺序：

```
S4.4.1 scheduleContinuousEnding       → 续作收尾判定与排产
S4.4.2 scheduleTypeBlockChange        → 换活字块衔接排产
S4.4.3 allocateShiftPlanQty            → 班次计划量分配
S4.4.4 adjustEmbryoStock               → 胎胚库存调整
S4.4.5 scheduleReduceMould             → 降模排产（释放冗余机台）
```

### 1.2 适用范围

- **仅限续作排产场景**（`ContinuousProductionStrategy`），新增策略 `NewSpecProductionStrategy.scheduleReduceMould` 是空实现
- **纯续作结果**：`isPureContinuousResult` 过滤条件为 `scheduleType="01"` 且 `isTypeBlock != "1"`（排除换活字块）
- **多机台**：同一来源SKU（按 `buildContinuationGroupKey` 分组）在 2 台及以上机台上续作
- 单机台续作（`skuResults.size() <= 1`）直接跳过

### 1.3 触发条件

```java
boolean needReduceMachine = keptResults.size() < skuResults.size()
        && currentMaxDailyCapacity > targetQty;
```

三个条件同时满足才执行降模：
1. 保留机台数 < 原始机台数（有可释放的机台）
2. 当前多机台总产能 > dayN 保障量（产能有富余）
3. 当前排产量 > dayN 保障量（非收尾场景）

---

## 二、核心算法逻辑

### 2.1 两条降模路径的判断

`scheduleReduceMould` 根据 `shouldReduceContinuationByWorkDate` 判断走哪条路径：

| 路径 | 方法 | 条件 |
|------|------|------|
| **路径一：按业务日逐日降模** | `reduceContinuationMachinesByWorkDate` | 非收尾 + 有日计划账本 + 账本天数 > 1 |
| **路径二：整体降模** | 聚合判断链 | 不满足路径一条件 |

**路径一的判断条件**（`shouldReduceContinuationByWorkDate`）：

```java
return sourceSku != null
        && !hasEndingResult(skuResults)                          // 非收尾
        && !CollectionUtils.isEmpty(sourceSku.getDailyPlanQuotaMap())
        && sourceSku.getDailyPlanQuotaMap().size() > 1;          // 多日额度
```

### 2.2 保留机台选择策略

#### 排序比较器 `buildContinuationKeepComparator`

```java
Comparator
    .comparingInt((LhScheduleResult result) -> -resolveCapsuleUsageCount(context, result))
    .thenComparing(result -> StringUtils.defaultString(result.getLhMachineCode()));
```

| 优先级 | 规则 | 业务含义 |
|--------|------|----------|
| 1 | 胶囊使用次数**降序**（次数多的优先保留） | 胶囊接近寿命终点，优先用完再换；释放新胶囊机台去服务其他SKU |
| 2 | 机台编码**升序**（编码小的优先保留） | 稳定 tie-breaker，保证结果可重复 |

#### 路径二：整体降模选择逻辑 `selectMachinesToKeepForContinuation`

1. 按排序比较器对机台降序排序
2. 从列表头逐台累加日产能
3. 直到累加产能 ≥ dayN 保障量（`demandQty`）
4. 已累加的机台即为保留机台

```java
for (LhScheduleResult result : sortedResults) {
    if (accumulatedCapacity >= demandQty) {
        break;
    }
    keptResults.add(result);
    accumulatedCapacity += Math.max(0, capacityMap.getOrDefault(result, 0));
}
```

#### 路径一：逐日降模选择逻辑 `selectMachinesToKeepForContinuationByLookAhead`

与整体降模的区别：

| 维度 | 整体降模 | 逐日降模（LookAhead） |
|------|----------|----------------------|
| 产能目标 | dayN 单日保障量 | 追补窗口内**累计需求** |
| 约束检查 | 仅当前日产能 | 当前日到追补结束日**累计产能** |
| 追补机制 | 无 | `carryShortageQty` + `shortageLookAheadDays` |

计算累计需求：

```java
cumulativeRequired = carryShortage
    + 当前日 dayN 计划量
    + 当前日之后直到 lookAheadEndDate 的各日 dayN 计划量
```

逐台加入保留机台，每加一台重新计算累计产能：

```java
for (LhScheduleResult result : sortedResults) {
    keptResults.add(result);
    cumulativeCapacity = calculateContinuationLookAheadCapacity(
            context, keptResults, ..., shortageLookAheadDays);
    if (cumulativeCapacity >= cumulativeRequired) {
        break;
    }
}
```

若 `cumulativeRequired <= 0`，释放全部机台（返回空列表）。

### 2.3 降模后的产能重新分配机制

#### 分配策略由 `ProductionQuantityPolicy` 决定

```java
ProductionQuantityPolicy policy = ProductionQuantityPolicy.from(sourceSku, ending);
boolean fillKeptMachineCapacity = !ending
        && !policy.isStrictUpperLimit()
        && !CollectionUtils.isEmpty(sourceSku.getDailyPlanQuotaMap());
```

| 场景 | strictUpperLimit | fillKeptMachineCapacity | 分配方式 |
|------|-----------------|------------------------|----------|
| 正式 + 非收尾 + 有多日额度 | false | true | 保留机台**补满班产** |
| 量试 + 非收尾 + 有多日额度 | false | true | 保留机台**补满班产** |
| 试制（任何场景） | true | false | 严格按 dayN 保障量分配，不超排 |
| 收尾（任何场景） | true | false | 严格按目标量上限分配，不超排 |

#### 整体路径分配 `allocateContinuationQtyForKeptMachines`

```java
for (LhScheduleResult result : keptResults) {
    int machineCapacity = Math.max(0, capacityMap.getOrDefault(result, ...));
    int allocation = fillKeptMachineCapacity
            ? machineCapacity                               // 补满班产
            : Math.min(remainingDemandQty, machineCapacity); // 严格按需
    redistributeShiftQty(context, result, shifts, allocation);
    remainingDemandQty = Math.max(0, remainingDemandQty - allocation);
}
// 被移除机台：计划量置零
for (LhScheduleResult result : removedResults) {
    redistributeShiftQty(context, result, shifts, 0);
}
```

#### 逐日路径分配 `applyContinuationDayAllocation`

逻辑相同，但只操作当天班次。被移除机台调用 `clearContinuationShiftsFromDate` 从当天起清空计划量。

### 2.4 降模约束条件

#### 约束一：dayN 保障量

`resolveContinuationDailyDemand` 计算逻辑：

```
收尾场景    → targetQty = sourceSku.resolveTargetScheduleQty()
非收尾场景  → targetQty = dailyPlanQuotaMap.get(首日).remainingQty（优先）或 dayPlanQty
均无账本    → targetQty = sourceSku.resolveTargetScheduleQty()
```

#### 约束二：欠产追补规则（逐日降模路径）

`resolveContinuationInitialCarryShortage` 计算首日初始欠产：

```java
carryShortage = Math.max(0, quota.getRemainingQty() - quota.getDayPlanQty());
// 即：首日累计剩余 - 首日本身计划量 = 历史允许追补欠产
```

`resolveLookAheadEndDate` 确定追补结束日：

```java
lookAheadEndDate = min(productionDate + shortageLookAheadDays, 窗口最后一天)
```

`canContinuationMachinesMeetLookAhead` 验证保留机台能否在追补窗口内补回欠产：

```java
cumulativeRequired >= cumulativeCapacity  → 满足约束
cumulativeRequired <  cumulativeCapacity  → 不满足，需保留更多机台
```

#### 约束三：窗口总目标量

逐日降模路径中，`remainingTargetQty` 逐日递减：

```java
remainingTargetQty = Math.max(0, remainingTargetQty - actualTodayQty);
```

严格的试制/收尾场景，当日有效目标量受限制：

```java
int effectiveDemandQty = policy.isStrictUpperLimit()
        ? Math.min(Math.max(0, todayRequiredQty), remainingTargetQty)
        : Math.max(0, todayRequiredQty);
```

#### 约束四：试制/收尾严格目标量约束

`ProductionQuantityPolicy.from(sku, ending)`：

- `ending=true` 或 `trialProduction=true` → `strictUpperLimit=true`
- 禁止补满班产，严格按目标量上限分配

---

## 三、具体实现步骤

### 3.1 scheduleReduceMould 完整流程

```
步骤1：获取全窗口班次 shifts
步骤2：遍历排程结果，按来源SKU分组（buildContinuationGroupKey）
       筛选条件：isPureContinuousResult（排除换活字块和新增结果）
步骤3：遍历每个同SKU多机台组：

  ├── 若机台数 ≤ 1 → 跳过

  ├── 若 shouldReduceContinuationByWorkDate（多日额度+非收尾）
  │   └── reduceContinuationMachinesByWorkDate（见3.2）
  │
  └── 否则（整体降模路径）：
      │
      ├── 3.1 计算 dayN 保障量 targetQty
      ├── 3.2 计算所有机台日产能映射
      ├── 3.3 targetQty ≤ 0 → 全部清空(allocateQtyForKeptMachines传空列表)
      ├── 3.4 收尾 + 总排产量 ≤ targetQty → 跳过（交由收尾错峰）
      ├── 3.5 selectMachinesToKeepForContinuation → 选保留机台
      ├── 3.6 needReduceMachine 判断（kept < 原始 + 总产能 > target + 排产 > target）
      │   ├── false → 跳过（日志："无需降模"）
      │   └── true → 执行降模
      └── 3.7 allocateContinuationQtyForKeptMachines → 重分配计划量

步骤4：统一后处理收口
  ├── 4.1 syncContinuousDailyPlanQuota       → 日额度账本同步（一次性扣账）
  ├── 4.2 appendContinuousCompensationSkuList → 续作不足→生成补偿SKU给S4.5
  ├── 4.3 finalizeZeroPlanContinuousResults  → 零计划结果收口清理
  ├── 4.4 adjustContinuousSameSkuMultiMachineEndingStagger → 同班次收尾量聚合
  ├── 4.5 finalizeZeroPlanContinuousResults  → 再次收口（收尾调整后）
  ├── 4.6 refreshContinuousEndingFlagByResult → 复核收尾标记
  ├── 4.7 distributeMultiMachineSurplusAndStock → 多机台胎胚库存分摊
  ├── 4.8 syncMachineStateAfterContinuousAdjust → 机台状态同步
  └── 4.9 rebuildStructureSkuMapFromPending   → 重建结构视图供S4.5使用
```

### 3.2 逐日降模路径 `reduceContinuationMachinesByWorkDate` 详细流程

```
输入：activeResults（当前活跃机台）、remainingTargetQty、shortageLookAheadDays、carryShortageQty

按业务日逐日处理：

  day1:
    demandQty = 当天日计划量
    todayRequired = carryShortage + demandQty
    effectiveDemandQty = policy约束后的生效目标量
    判断 hasPositiveDayPlanDropAroundDate(当前日)
      → true 或 effectiveDemandQty ≤ 0
         → selectMachinesToKeepForContinuationByLookAhead（使用追补窗口选择最小保留机台）
      → false → 保留全部机台（不降模）
    核算 recoverable = canContinuationMachinesMeetLookAhead
    执行 applyContinuationDayAllocation
    更新：carryShortage = max(0, effectiveDemandQty - actualTodayQty)
    更新：remainingTargetQty -= actualTodayQty
    更新：activeResults = keptResults

  day2:
    同上，但此时 activeResults 可能是降模后的子集

  day3:
    同上，窗口最后一天

关键判断：hasPositiveDayPlanDropAroundDate

  仅当"当前日有正计划量"且"前后存在更小的正计划量"时才返回 true。
  注释明确："降模只服务于计划下降后的减机台；窗口尾部无计划的0量日期不作为降模触发依据。"
```

### 3.3 后处理详解

#### syncContinuousDailyPlanQuota

```java
// 注释原文：
// 续作结果会经历班次重分配、库存裁剪和降模处理，必须在收口后按最终班次量一次性扣账。
```

- 遍历所有纯续作结果
- 按最终班次量统一扣减日计划账本
- 单次扣账（`context.setContinuousDailyQuotaSynced(true)`），避免重复扣账

#### appendContinuousCompensationSkuList

- 遍历所有续作SKU，计算 `remainingQty = targetScheduleQty - scheduledQty`
- 若 `remainingQty > 0` 且账本有剩余额度，生成补偿SKU：
  - 复制原SKU的日计划账本（共享额度）
  - 清除 `continuousMachineCode`（交由新增换模链路重新选机）
  - 加入 `context.getNewSpecSkuList()` 供 S4.5 新增排产使用

#### finalizeZeroPlanContinuousResults

- 识别 `dailyPlanQty ≤ 0` 的续作结果
- 清理 `specEndTime` / `tdaySpecEndTime`
- 从未排SKU中汇总未排量 → `mergeUnscheduledResultByMaterial`
- 从 `scheduleResultList` 和 `machineAssignmentMap` 中移除零计划结果

#### distributeMultiMachineSurplusAndStock

- 按续作业务分组汇总同SKU多机台结果
- 委托 `LhMultiMachineDistributionUtil.distributeForSingleMaterial` 按机台条数均分
- 仅分摊胎胚库存，余量保留原始全量值

---

## 四、降模排产与其他排产策略的区别和边界

### 4.1 策略对比

| 维度 | 降模排产（S4.4.5） | 新增排产（S4.5） | 换活字块（S4.4.2） |
|------|-------------------|-----------------|-------------------|
| **实现类** | `ContinuousProductionStrategy` | `NewSpecProductionStrategy` | `TypeBlockProductionStrategy` |
| **降模实现** | 完整实现（3762行主类） | 空实现 `// 新增策略不处理降模` | 不参与（`isPureContinuousResult`过滤） |
| **业务定位** | 同SKU多机台产能过剩时释放冗余 | 月计划有余量需新开模上机 | 续作收尾后同模具换活字块衔接 |
| **机台变化** | **减少**机台数 | **增加**机台数 | 机台不变，SKU变化 |
| **目标量来源** | dayN保障量 + 日额度账本 | 月计划余量 + 胎胚库存 | 换活字块后的新SKU目标量 |
| **执行顺序** | S4.4 最后一步 | S4.5（续作之后） | S4.4.2（降模之前） |

### 4.2 边界保护

```java
// 第3141-3145行：isPureContinuousResult 明确过滤换活字块
private boolean isPureContinuousResult(LhScheduleResult result) {
    return result != null
            && CONTINUOUS_SCHEDULE_TYPE.equals(result.getScheduleType())
            && !"1".equals(result.getIsTypeBlock());
}
```

```
降模结果中不含换活字块 → 两者互不干扰
降模释放的机台窗口 → 后续被 S4.5 新增排产利用
降模产生的补偿SKU → 进入 S4.5 新增换模排产路径
换活字块在 S4.4.2 已处理完 → 降模时不再处理
```

### 4.3 全链路数据流

```
S4.4.1 续作收尾
    ↓ (排程结果 + 机台分配)
S4.4.2 换活字块
    ↓ (纯续作结果 + 换活字块结果)
S4.4.3 班次分配
    ↓ (调整后班次量)
S4.4.4 胎胚库存调整
    ↓ (库存裁减后结果 + 余量)
S4.4.5 降模排产
    ├── 释放冗余机台（下机机台计划量清零）
    ├── 保留机台重分配
    ├── 生成补偿SKU → S4.5 新增排产
    ├── 同步日额度账本
    └── 分摊胎胚库存、同步机台状态
        ↓ (最终续作结果)
S4.5 新增排产（含补偿SKU换模上机）
```

---

## 五、输出结果和相关日志记录

### 5.1 对 `LhScheduleContext` 的直接影响

| 变更项 | 说明 | 实现方法 |
|--------|------|----------|
| `scheduleResultList` | 下机机台计划量置零/移除，保留机台重新分配 | `allocateContinuationQtyForKeptMachines` |
| `newSpecSkuList` | 续作产能不足时追加补偿SKU | `appendContinuousCompensationSkuList` |
| `machineScheduleMap` | 机台状态按最终结果回写 | `syncMachineStateAfterContinuousAdjust` |
| 日计划账本 | 降模结果一次性扣账 | `syncContinuousDailyPlanQuota` |
| 胎胚库存/Surplus | 按保留机台条数均分 | `distributeMultiMachineSurplusAndStock` |
| 收尾标记(isEnd) | 降模后复核更新 | `refreshContinuousEndingFlagByResult` |
| 机台分配映射 | 零计划机台移除 | `removeResultsFromMachineAssignments` |

### 5.2 关键日志节点（`log.info` 级别）

#### 入口日志

```
续作排产 - 降模排产
```

#### 分组识别

```
续作同SKU多机台识别, materialCode: {}, 机台列表: {}, 是否多机台: true
```

#### 降模判断（整体路径）

```
续作多机台降模判断, materialCode: {}, dayN保障量: {}, 当前在机最大日产能: {}, 当前排产量: {}
```

#### 无需降模日志

```
续作多机台收尾无需降模, materialCode: {}, 原因: 当前尾量未超过收尾目标，交由同SKU收尾错峰判断
续作多机台无需降模, materialCode: {}, 原因: 当前产能或排产量未超过dayN保障量
```

#### 排序结果日志

明细格式：`机台编码(胶囊次数=X,日产能=Y)`

```
续作多机台降模排序, 保留排序: K1105(胶囊次数=20,日产能=48),K1110(胶囊次数=12,日产能=48),
  下机排序: ..., 保留排序明细: ..., 下机排序明细: ...
```

#### 逐日降模判断日志（含追补信息）

```
续作多机台按天降模判断, materialCode: {}, 日期: {}, shortageLookAheadDays: {}, dayN计划量: {},
  carryShortage: {}, 当日需求量: {}, 剩余窗口目标量: {}, 当日生效目标量: {},
  未来正计划是否下降: {}, 当前在机最大日产能: {}, 保留机台当日产能: {},
  当前排产量: {}, 是否满足dayN欠产追补约束: {}
```

#### 追补模拟日志

```
续作多机台降模追补模拟, materialCode: {}, 日期: {}, 累计需求: 0, 保留机台为空，释放机台: {}
续作多机台降模追补模拟, materialCode: {}, 日期: {}, 尝试保留机台: {},
  carryShortage: {}, 累计需求: {}, 累计产能: {}, 是否满足: {}
```

#### 保留机台排量日志

```
续作多机台保留机台排量, materialCode: {}, machineCode: {}, allocation: {},
  machineCapacity: {}, 是否补满班产: {}, 是否收尾: {}
```

#### 最终降模结果日志

```
续作多机台降模结果, materialCode: {}, 原始机台: {}, 保留机台: {}, 下机机台: {},
  原始机台明细: {}, 保留机台明细: {}, 下机机台明细: {},
  原因: dayN保障量={}，按胶囊使用次数和机台编码排序
```

#### 逐日降模结果日志

```
续作多机台降模结果, materialCode: {}, 日期: {}, 原始机台: {}, 保留机台: {},
  下机机台: {}, 原始机台明细: {}, 保留机台明细: {}, 下机机台明细: {},
  原因: dayN保障量={}，当日生效目标量={}，剩余窗口目标量={}，按胶囊使用次数和机台编码排序
```

#### 补偿SKU日志

```
续作目标量未满足，转新增规格链路补量, materialCode: {}, 已排: {}, 补偿量: {}, 窗口日计划剩余: {}
```

---

## 六、测试用例覆盖

### 6.1 核心测试方法（`ContinuousProductionStrategyTest`）

| 测试方法 | 行号 | 验证场景 |
|---------|------|----------|
| `scheduleReduceMould_shouldAppendCompensationWhenContinuousSkuHasNoResult` | 312 | 续作SKU完全无结果时生成补偿SKU |
| `scheduleReduceMould_shouldAppendNewSpecCompensationWhenContinuousTargetNotMet` | 355 | 续作产能不足时生成补偿SKU |
| `scheduleReduceMould_shouldNotAppendCompensationWhenSharedQuotaExhausted` | 417 | 共享账本剩余0时不生成补偿SKU |

### 6.2 回归测试

| 测试类 | 测试用例 |
|--------|----------|
| `ContinuousProductionResultQtyRegressionTest` | `scheduleReduceMould_shouldKeepGlobalTargetWhenSingleMachineResultIsRefined` (257) |
| `ContinuousProductionResultQtyRegressionTest` | `scheduleReduceMould_shouldAggregateSameShiftEndingWithinContinuationGroup` (327) |
| `ContinuousMachineStateSyncRegressionTest` | `scheduleReduceMould_shouldSyncMachineStateFromTypeBlockResult` (66) |
| `ContinuousMachineStateSyncRegressionTest` | `scheduleReduceMould_thenScheduleNewSpecs_shouldAvoidOverlapWithTypeBlockWindow` (82) |
| `ContinuousMachineStateSyncRegressionTest` | `scheduleReduceMould_shouldKeepContinuousResultSyncBehavior` (131) |

### 6.3 测试文件位置

- 单元测试：`aps-lh/src/test/com/zlt/aps/lh/engine/strategy/impl/ContinuousProductionStrategyTest.java`
- Handler编排测试：
  - `aps-lh/src/test/com/zlt/aps/lh/handler/ContinuousProductionHandlerTest.java`（第52行验证降模调用顺序）
  - `aps-lh/src/test/com/zlt/aps/lh/handler/NewProductionHandlerTest.java`（第72行验证新增阶段也调用降模）
- 回归测试：
  - `aps-lh/src/test/com/zlt/aps/lh/regression/ContinuousProductionResultQtyRegressionTest.java`
  - `aps-lh/src/test/com/zlt/aps/lh/regression/ContinuousMachineStateSyncRegressionTest.java`
  - `aps-lh/src/test/com/zlt/aps/lh/regression/ContinuousFollowUpResultValidationRegressionTest.java`

---

## 七、关键类和方法索引

| 类/方法 | 文件行号 | 职责 |
|---------|----------|------|
| `IProductionStrategy.scheduleReduceMould` | `IProductionStrategy.java:62` | 降模排产接口定义 |
| `ContinuousProductionStrategy.scheduleReduceMould` | `ContinuousProductionStrategy.java:474` | **降模排产主入口** |
| `shouldReduceContinuationByWorkDate` | 第576行 | 判断是否走逐日降模路径 |
| `reduceContinuationMachinesByWorkDate` | 第592行 | 按业务日逐日执行降模 |
| `hasPositiveDayPlanDropAroundDate` | 第807行 | 判断未来计划是否下降（触发降模的关键信号） |
| `resolveContinuationShortageLookAheadDays` | 第846行 | 解析欠产追补观察天数 |
| `resolveContinuationInitialCarryShortage` | 第861行 | 解析首日初始欠产 |
| `resolveContinuationDailyDemand` | 第889行 | 解析dayN保障量 |
| `calculateMachineDailyCapacityMap` | 第980行 | 计算机台日产能映射 |
| `calculateMachineDailyCapacity` | 第1026行 | 计算单台机台当天可用产能 |
| `calculateResultShiftCapacity` | 第1091行 | 计算结果在指定班次的可排产能 |
| `selectMachinesToKeepForContinuation` | 第1142行 | 整体降模选择保留机台 |
| `selectMachinesToKeepForContinuationByLookAhead` | 第1179行 | 追补窗口选择最小保留机台 |
| `canContinuationMachinesMeetLookAhead` | 第1237行 | 保留机台追补约束校验 |
| `calculateContinuationLookAheadRequired` | 第1266行 | 计算追补窗口累计需求 |
| `calculateContinuationLookAheadCapacity` | 第1297行 | 计算追补窗口累计产能 |
| `resolveLookAheadEndDate` | 第1349行 | 解析追补结束日 |
| `selectMachinesToRemoveForContinuation` | 第1385行 | 选择下机机台 |
| `buildContinuationKeepComparator` | 第1407行 | 构建保留排序比较器 |
| `resolveCapsuleUsageCount` | 第1420行 | 解析机台胶囊使用次数 |
| `allocateContinuationQtyForKeptMachines` | 第1439行 | 整体降模保留机台计划量重分配 |
| `applyContinuationDayAllocation` | 第1487行 | 逐日降模计划量分配 |
| `finalizeZeroPlanContinuousResults` | 第2732行 | 零计划续作结果收口 |
| `distributeMultiMachineSurplusAndStock` | 第2776行 | 多机台胎胚库存分摊 |
| `syncMachineStateAfterContinuousAdjust` | 第2825行 | 机台状态同步 |
| `syncContinuousDailyPlanQuota` | 第2857行 | 日计划额度账本同步 |
| `appendContinuousCompensationSkuList` | 第2882行 | 续作补偿（生成补偿SKU） |
| `isPureContinuousResult` | 第3141行 | 纯续作结果过滤 |
| `ContinuousProductionHandler.doHandle` | `ContinuousProductionHandler.java:41` | S4.4 步骤编排入口 |
| `NewProductionHandler.doHandle` | `NewProductionHandler.java:42` | S4.5 步骤编排入口 |
| `NewSpecProductionStrategy.scheduleReduceMould` | `NewSpecProductionStrategy.java:219` | 新增策略空实现 |
| `ProductionQuantityPolicy` | 独立类 | 排产数量策略（严格上限/补满班产） |
| `LhScheduleConstant.DEFAULT_STRUCTURE_ENDING_DAYS` | `LhScheduleConstant.java:97` | 降模排产收尾判定天数（默认5天） |

---

## 八、设计要点总结

### 8.1 核心设计思想

降模排产的实质是**对续作场景下同SKU多机台的冗余产能释放**。核心问题等价于：

> "在满足所有业务约束的前提下，最少需要保留几台机台？"

### 8.2 两条路径的适用场景

| 路径 | 适用场景 | 核心判断依据 |
|------|----------|-------------|
| 逐日降模 | 多日日计划 + 非收尾，计划波动大 | `hasPositiveDayPlanDropAroundDate` + 追补模拟 |
| 整体降模 | 单日额度 / 收尾场景 | dayN保障量与机台总产能的简单比较 |

### 8.3 "计划下降才触发降模"的含义

`hasPositiveDayPlanDropAroundDate` 确保：

- 只有**正计划量下降**时才触发降模（如 day1=48, day2=24）
- 窗口尾部无计划的 0 量日期**不作为**降模触发依据
- 注释原文："降模只服务于计划下降后的减机台；窗口尾部无计划的0量日期不作为降模触发依据。"

### 8.4 胶囊使用次数的业务逻辑

- **胶囊使用次数多的优先保留**：胶囊有使用寿命，接近寿命终点的机台优先用完其剩余寿命，避免中途更换造成浪费
- **胶囊使用次数少的优先释放**：释放后可以服务其他SKU，延长总使用寿命
- **排序反转**：下机机台的排序正好与保留排序相反（按胶囊次数升序 + 机台编码降序）
