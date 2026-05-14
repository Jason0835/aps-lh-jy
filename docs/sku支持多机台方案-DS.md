# 支持一个 SKU 多机台排产 — 设计方案与实施计划

## 1. 明确结论

**推荐方案**：在现有排程流程基础上做最小改动，核心改动集中在以下四个点：

1. **收尾判断** (`DefaultEndingJudgmentStrategy.isEnding`)：规则2 从"单 SKU 理论产能"改为"多台可用机台在窗口内的合计产能"
2. **待排产量** (`ScheduleAdjustHandler.buildSkuScheduleDTO`)：`basePendingQty` 从 `max(余量, 胎胚库存)` 改为按收尾/非收尾区分，非收尾不取胎胚库存
3. **新增 SKU 排产** (`NewSpecProductionStrategy.scheduleNewSpecs`)：找到一台机台成功后不再 `break`，继续尝试下一台机台直到待排量排完
4. **新增多机台合计产能计算方法**：用于收尾判断和目标量封顶

不需要新增大而全的算法框架，不需要重写主流程，不需要新增策略类。

---

## 2. 改造目标

1. 当某个 SKU 待排产量较大，一台硫化机台在排程窗口内产能无法排完时，允许同一个 SKU 拆分到多台可用硫化机台上排产
2. 非收尾场景下，不允许因为胎胚库存较多而强行上调排产量
3. 收尾场景下，才允许将待排产量上调到胎胚库存量
4. 收尾判断基于多台可用机台在窗口内的合计产能，而非单机台产能或单SKU理论产能

---

## 3. 现有逻辑影响点

### 3.1 需修改的类和方法

| 序号 | 类/文件 | 方法 | 改动原因 |
|------|---------|------|----------|
| 1 | `DefaultEndingJudgmentStrategy` | `isEnding()` | 规则2 的产能比较需要从 "shiftCapacity × totalScheduleShifts" 改为 "多台可用机台合计产能" |
| 2 | `DefaultEndingJudgmentStrategy` | 新增 `calcSkuTotalAvailableCapacityInWindow()` | 计算 SKU 在窗口内所有可用机台的合计产能 |
| 3 | `ScheduleAdjustHandler` | `buildSkuScheduleDTO()` | `basePendingQty` 从 `max(余量, 胎胚库存)` 改为按收尾/非收尾区分 |
| 4 | `NewSpecProductionStrategy` | `scheduleNewSpecs()` | 一台机台成功后不再 `break`，支持多机台拆量 |
| 5 | `NewSpecProductionStrategy` | 新增 `scheduleOnMultipleMachines()` | 多机台拆量排产的私有方法 |
| 6 | `TargetScheduleQtyResolver` | 新增 `calcSkuTotalAvailableCapacityInWindow()` | 计算 SKU 在多台机台上的合计窗口产能 |
| 7 | `TargetScheduleQtyResolver` | `resolveInitialTargetQty()` | 目标量上限需考虑多机台总产能 |
| 8 | `SkuScheduleDTO` | 新增 `remainingScheduleQty` 字段 | 多机台拆量时追踪剩余待排量 |
| 9 | `ContinuousProductionStrategy` | `refreshContinuousEndingFlagByResult()` | 收尾复核口径需随 `pendingQty` 调整保持一致 |

### 3.2 需检查但不一定修改的类

| 序号 | 类/文件 | 检查内容 |
|------|---------|----------|
| 1 | `ContinuousProductionStrategy.adjustEmbryoStock()` | 胎胚库存扣减逻辑是否能兼容同一SKU多机台结果 |
| 2 | `ContinuousProductionStrategy.scheduleReduceMould()` | 降模逻辑已处理同SKU多机台，但需要验证新场景 |
| 3 | `RollingScheduleHandoffService` | 继承量扣减逻辑是否需要同步调整 |
| 4 | `LhScheduleContext` | `machineAssignmentMap` 能否支持同一SKU在多台机台的分配记录 |
| 5 | `MonthPlanDayQtyUtil` | `resolveWindowPlanQty` 已正确实现窗口汇总，不需修改 |

### 3.3 不需修改的类

| 类 | 原因 |
|----|------|
| `DefaultMachineMatchStrategy` | 机台匹配逻辑本身返回候选机台列表，已支持多机台 |
| `LocalSearchMachineAllocatorStrategy` | 局部搜索选机逻辑不受影响 |
| `MonthPlanDayQtyUtil` | `resolveWindowPlanQty` 已正确实现窗口日计划量汇总 |
| `LhScheduleContext` | 数据结构已支持多机台分配记录 |
| `SchedulePersistenceService` | 持久化逻辑无变化 |
| `ResultValidationHandler` | 校验逻辑无变化 |

---

## 4. 设计思路

### 4.1 核心改造点说明

#### 4.1.1 待排产量计算 (`ScheduleAdjustHandler.buildSkuScheduleDTO`)

**当前逻辑**：
```java
// 待排量以"余量/库存取大"为基线，再叠加滚动继承扣减与欠产传导
int basePendingQty = Math.max(surplus.getSurplusQty(), Math.max(0, dto.getEmbryoStock()));
```

**问题**：非收尾场景下，只要胎胚库存大于余量，就会上调待排量。假设窗口计划量只有100，但胎胚库存有500，余量有500，那么 `basePendingQty = max(500, 500) = 500`，会导致非收尾时也排500。

**改造后**：
```java
// 待排量基线: 非收尾取余量, 收尾取 max(余量, 胎胚库存)
// 收尾判断需在目标量计算之前完成, 所以这里先按保守口径(余量)计算,
// 收尾标记后的上调逻辑放在 TargetScheduleQtyResolver 中处理
int basePendingQty = surplus.getSurplusQty();
if (context.isStopProductionMode()) {
    basePendingQty = resolveStopProductionDemandQty(context, plan, dto.getEmbryoStock());
}
dto.setPendingQty(Math.max(0, basePendingQty - inheritedPlanQty) + carryForwardQty);
```

然后由 `TargetScheduleQtyResolver.resolveInitialTargetQty()` 在计算目标量时，根据收尾判定结果决定是否上调到胎胚库存。

#### 4.1.2 收尾判断优化 (`DefaultEndingJudgmentStrategy.isEnding`)

**当前逻辑（规则2）**：
```java
int totalCapacity = shiftCapacity * totalScheduleShifts;
if (rule2CandidateQty <= totalCapacity) {
    return true;
}
```

**问题**：`shiftCapacity` 是单个 SKU 的班产，`totalScheduleShifts` 是窗口总班次数。这只反映了"理论上"一个 SKU 在无限机台上的产能，并没有基于真实的可用机台列表计算。

**改造后（规则2）**：
```java
// 基于多台可用机台在窗口内实际剩余产能计算合计产能
int totalAvailableCapacity = calcSkuTotalAvailableCapacityInWindow(context, sku);
if (rule2CandidateQty > 0 && totalAvailableCapacity > 0
        && rule2CandidateQty <= totalAvailableCapacity) {
    return true;
}
```

新增 `calcSkuTotalAvailableCapacityInWindow()` 方法：
1. 获取 SKU 的候选机台列表（通过 `DefaultMachineMatchStrategy.matchMachines()`）
2. 对每台候选机台，计算在排程窗口内的剩余可排产能（考虑续作占用、换模换活字块占用、班次管控等）
3. 汇总所有候选机台的可用产能

#### 4.1.3 多机台拆量排产 (`NewSpecProductionStrategy.scheduleNewSpecs`)

**当前逻辑**：
```java
while (true) {
    // ... 尝试一台机台 ...
    if (排产成功) {
        scheduled = true;
        iterator.remove();
        break;  // ← 成功后不再尝试其他机台
    }
}
```

**改造后**：
```java
int remainingQty = sku.resolveTargetScheduleQty();
Set<String> excludedMachineCodes = new HashSet<>();
while (remainingQty > 0) {
    // 尝试一台机台...
    MachineScheduleDTO candidateMachine = ...;
    if (candidateMachine == null) break;

    int machineCapacity = 计算当前机台可排量;
    int scheduleQty = Math.min(remainingQty, machineCapacity);
    if (scheduleQty <= 0) {
        excludedMachineCodes.add(machineCode);
        continue;
    }

    // 生成排程结果(目标量=scheduleQty)
    LhScheduleResult result = ...;
    context.getScheduleResultList().add(result);

    remainingQty -= scheduleQty;
    if (remainingQty <= 0) {
        iterator.remove(); // 全部排完才从待排列表移除
        break;
    }
}
// 未排完的剩余量计入欠产或待后续排程处理
```

关键约束：
- 除第一台机台外，后续机台也需要换模/换活字块（因为机台不同）
- 每台机台的换模配额和首检配额独立检查
- 月计划余量按实际各机台排产量分别扣减
- 同一 SKU 在多台机台上的排程结果需要分别保存

#### 4.1.4 胎胚库存上调逻辑

**改造位置**：`TargetScheduleQtyResolver.resolveInitialTargetQty()` 中的目标量计算

**逻辑**：
```
非收尾: targetQty = min(windowPlanQty, monthRemainQty)
收尾:   targetQty = min(max(windowPlanQty, embryoStock), monthRemainQty)
最终:   targetQty = min(targetQty, totalAvailableCapacity)
```

### 4.2 影响范围图示

```
ScheduleAdjustHandler.buildSkuScheduleDTO  ← 待排量基线调整
        │
        ▼
TargetScheduleQtyResolver.resolveInitialTargetQty  ← 目标量封顶调整
        │
        ▼
DefaultEndingJudgmentStrategy.isEnding  ← 收尾判断（多机台产能）
        │
        ▼
NewSpecProductionStrategy.scheduleNewSpecs  ← 多机台拆量排产
        │
        ▼
ContinuousProductionStrategy.adjustEmbryoStock  ← 多机台结果库存裁剪
```

---

## 5. 详细实施步骤

### 步骤1：新增多机台合计产能计算方法

**文件**：`TargetScheduleQtyResolver.java`

**新增方法**：
```java
/**
 * 计算 SKU 在当前排程窗口内所有可用机台的合计产能。
 * <p>用于收尾判断和多机台排产目标量封顶</p>
 *
 * @param context 排程上下文
 * @param sku SKU排程DTO
 * @return 多台可用机台在窗口内的合计可排产量
 */
public int calcSkuTotalAvailableCapacityInWindow(LhScheduleContext context, SkuScheduleDTO sku)
```

**实现思路**：
- 复用 `DefaultMachineMatchStrategy.matchMachines()` 获取候选机台列表
- 对每台机台，逐班次计算剩余可用产能（考虑已占用班次、换模换活字块占用、保养清洗、班次管控等）
- 汇总所有机台的班次可用产能

### 步骤2：改造收尾判断

**文件**：`DefaultEndingJudgmentStrategy.java`

**改动内容**：
- 新增 `calcSkuTotalAvailableCapacityInWindow()` 方法（委托给 `TargetScheduleQtyResolver`）
- `isEnding()` 规则2 改为：`rule2CandidateQty <= totalAvailableCapacity`
- 规则3 保持不变

### 步骤3：调整待排产量基线

**文件**：`ScheduleAdjustHandler.java` → `buildSkuScheduleDTO()` 方法

**改动内容**：
- `basePendingQty` 从 `max(surplusQty, embryoStock)` 改为 `surplusQty`
- 停产模式逻辑保持不变
- 胎胚库存上调逻辑移到 `TargetScheduleQtyResolver.resolveInitialTargetQty()` 中
- 注：此处调整后需要确保 `pendingQty` 仍然用于下游的目标量计算，只是不再提前取 max

### 步骤4：调整目标排产量计算

**文件**：`TargetScheduleQtyResolver.java` → `resolveInitialTargetQty()` 方法

**改动内容**：
- 在 `resolveInitialTargetQty()` 中集成收尾判定和胎胚库存上调
- 收尾时 `upperLimitQty = max(pendingQty, embryoStock)`，非收尾时 `upperLimitQty = pendingQty`
- 增加多机台总产能封顶：`targetQty = min(targetQty, calcSkuTotalAvailableCapacityInWindow())`

### 步骤5：改造新增 SKU 多机台拆量排产

**文件**：`NewSpecProductionStrategy.java` → `scheduleNewSpecs()` 方法

**改动内容**：
- 将排产成功后的 `break` 改为继续循环
- 新增 `remainingQty` 变量追踪剩余待排量
- 每台机台的 `scheduleQty = min(remainingQty, machineAvailableCapacity)`
- 每台机台排产后：`remainingQty -= scheduleQty`
- 只有当 `remainingQty <= 0` 时才 `iterator.remove()`（SKU 全部排完）
- 如果所有候选机台都尝试完但 `remainingQty > 0`，剩余量计入未排结果
- 确保除第一台机台外，后续机台也正确分配换模/换活字块/首检

### 步骤6：同步调整相关逻辑

**文件**：`ContinuousProductionStrategy.java`

**改动内容**：
- `refreshContinuousEndingFlagByResult()` 中收尾比较量改为按实际排产量与余量/库存比较
- `adjustEmbryoStock()` 中的 `buildMaterialEmbryoStockMap()` 需要确保多机台场景下同 SKU 库存正确扣减

### 步骤7：增加日志

按照文档第九章节要求，在关键位置增加详细日志。

### 步骤8：边界场景验证

按文档第七章节列出的边界场景逐一构造测试数据进行验证。

---

## 6. 数据处理说明

### 6.1 待排产量初始化

- `windowPlanQty`：通过 `MonthPlanDayQtyUtil.resolveWindowPlanQty()` 按窗口起止日动态汇总（已正确实现）
- `surplusQty`：= `max(0, 月计划总量 - 已完成量)`
- `pendingQty`：= `max(0, surplusQty - inheritedPlanQty) + carryForwardQty`
- `targetScheduleQty`：由 `TargetScheduleQtyResolver` 按收尾/非收尾计算

### 6.2 多机台拆量扣减

- 每台机台排产后，`remainingQty` 递减
- 月计划余量按各机台实际排产量分别累计扣减
- 机台产能按班次逐台占用
- SKU 全部排完后从 `newSpecSkuList` 移除

### 6.3 胎胚库存扣减

- 按现有 `buildMaterialEmbryoStockMap()` 逻辑，同 SKU 在不同机台上的结果按物料编码聚合扣减
- 收尾 SKU 优先扣减，普通 SKU 按顺序扣减
- 库存不足时按最终计划量裁剪

---

## 7. 边界场景

| 场景 | 预期行为 |
|------|---------|
| 一台机台可排完 | 只排一台机台，不继续占用其他机台 |
| 一台排不完需多台 | SKU 拆分到多台机台，各台独立计算产能和换模 |
| 多台也排不完 | 剩余量进入欠产/后续排程，不超产 |
| 非收尾，胎胚库存大于窗口计划量 | 目标排产量 = 窗口计划量，不上调到胎胚库存 |
| 收尾，胎胚库存大于窗口计划量 | 目标排产量允许上调到胎胚库存（不超过余量） |
| 收尾判断多机台 | 月计划余量180，单台最多100，两台合计200 → 判断为可收尾 |
| 跨月排程窗口 | 当前暂不支持跨月，抛出异常（已有逻辑） |
| 定点机台+多机台拆量 | 定点机台优先排产，剩余量在其他机台上排 |
| 同SKU多机台的换模配额 | 每台机台独立检查换模配额 |
| 同SKU多机台后降模 | 续作的 `scheduleReduceMould` 已处理同SKU多机台降模，新增排产按目标量裁剪 |

---

## 8. 风险点

| 风险 | 说明 | 缓解措施 |
|------|------|---------|
| 收尾判断误判 | 多机台合计产能计算不准确可能导致收尾误判 | 复用现有机台匹配和生产管控逻辑，确保产能计算口径一致 |
| 待排量变化影响滚动衔接 | `pendingQty` 基线调整可能影响欠产传导和继承扣减 | 保持 `inheritedPlanQty` 和 `carryForwardQty` 的处理逻辑不变 |
| 月计划余量超额扣减 | 多机台拆量后各机台结果分别保存，需要保证合计扣减不超 | 通过 `remainingQty` 变量控制总量，并在最终结果中校验 |
| 换模配额冲突 | 多台机台各自换模可能排在同一班次导致超配额 | 换模均衡策略 `allocateMouldChange()` 已处理配额问题，多机台场景复用 |
| 影响已有续作逻辑 | 待排量计算调整可能影响续作 SKU 的目标量 | 续作 SKU 的 `targetScheduleQty` 在 `buildSkuScheduleDTO` 之后通过 `resolveInitialTargetQty` 计算，需验证一致性 |
| 性能影响 | 多机台合计产能计算需要遍历所有候选机台和班次 | 候选机台列表已有，计算量不大；满排模式已有类似计算 |

---

## 9. 验证建议

### 9.1 单元测试

1. **收尾判断测试**：
   - 单机台产能不足但多机台合计产能足够 → 应判定为收尾
   - 多机台合计产能仍不足 → 不应判定为收尾

2. **待排产量测试**：
   - 非收尾场景，胎胚库存 500，余量 200，窗口计划量 100 → 目标量应为 100（非收尾不上调）
   - 收尾场景，胎胚库存 180，余量 180，窗口计划量 100 → 目标量允许为 180

3. **多机台拆量测试**：
   - SKU 待排 180，机台A 可排 100，机台B 可排 80 → A排100, B排80
   - SKU 待排 300，机台A 可排 100，机台B 可排 80 → A排100, B排80, 剩余120未排

### 9.2 集成测试

使用真实月计划数据触发一次完整排程，检查：
1. 排程结果中同一 SKU 是否出现在多台机台上
2. 各机台的排产量之和是否等于 SKU 目标排产量
3. 各机台的排产量之和是否不超过月计划余量
4. 非收尾 SKU 的目标量是否没有被胎胚库存上调
5. 收尾 SKU 的目标量是否正确上调
6. 换模配额是否在各机台独立检查通过

### 9.3 回归测试

1. 原有"一台机台排完"场景结果与改造前一致
2. 续作排产（含换活字块）结果与改造前一致
3. 滚动排程衔接结果与改造前一致
4. 胎胚库存分摊和裁剪结果与改造前一致
5. 模具交替计划生成结果与改造前一致

---

## 10. 实现顺序建议

按依赖关系，建议按以下顺序实施：

```
步骤1（多机台产能计算）
  → 步骤2（收尾判断改造）
    → 步骤3（待排产量基线调整）
      → 步骤4（目标量计算调整）
        → 步骤5（多机台拆量排产）
          → 步骤6（相关逻辑同步）
            → 步骤7（日志补全）
              → 步骤8（边界场景验证）
```

---

## 11. 涉及文件清单

| 文件 | 改动类型 | 说明 |
|------|---------|------|
| `aps-lh/.../engine/strategy/impl/DefaultEndingJudgmentStrategy.java` | 修改 | 收尾判断规则2改为多机台合计产能 |
| `aps-lh/.../component/TargetScheduleQtyResolver.java` | 修改+新增方法 | 新增多机台合计产能计算，调整目标量计算逻辑 |
| `aps-lh/.../handler/ScheduleAdjustHandler.java` | 修改 | `buildSkuScheduleDTO` 中 `basePendingQty` 调整 |
| `aps-lh/.../engine/strategy/impl/NewSpecProductionStrategy.java` | 修改 | `scheduleNewSpecs` 支持多机台拆量 |
| `aps-lh/.../api/domain/dto/SkuScheduleDTO.java` | 修改 | 新增 `remainingScheduleQty` 字段 |
| `aps-lh/.../engine/strategy/impl/ContinuousProductionStrategy.java` | 修改 | 收尾复核口径同步调整 |
