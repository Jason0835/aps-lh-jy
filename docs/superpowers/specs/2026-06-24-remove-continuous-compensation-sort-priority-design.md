# 新增排产去除续作补偿 SKU 排序优先权设计

## 1. 背景

- 2026-06-03 设计文档《续作补偿 SKU 统一排序后优先锁回原续作机台》明确：
  "补偿 SKU 继续进入 `newSpecSkuList`，与其他新增 SKU 一起按现有新增排序规则统一排序，
  只有在补偿 SKU 自己轮到选机时，才优先锁回原续作机台。"
- 实际实现中 `DefaultSkuPriorityStrategy.compareNewSpecSku` 在施工阶段分组之后、
  组内排序之前，额外加入了"续作补偿 SKU 同组内置顶"分支
  （`resolveContinuousCompensationScore`），与上述设计存在偏离，
  会让补偿 SKU 越过定点机台、锁交期、延误天数、结构全收尾、供应链待排量等主排序维度。
- 本变更的目标是恢复设计原意：**补偿 SKU 在新增排产排序中按现有统一规则参与排序，
  不享有任何额外的排序优先权。**

## 2. 改造范围

- 仅修改新增排产 **SKU 排序** 中的补偿优先逻辑；
- 不改动：
  - `SkuScheduleDTO#continuousCompensationSku` 标识字段；
  - `ContinuousProductionStrategy.appendContinuousCompensationSkuList` 补偿 SKU 生成；
  - `NewSpecProductionStrategy.resolvePreferredContinuousCompensationMachine`
    等"选机时优先锁回原续作机台"逻辑；
  - 补偿 SKU 在日志、欠产、收尾、滚动衔接等位置的特殊处理。

## 3. 现有逻辑影响点

| 文件 | 改动概要 |
|------|----------|
| `aps-lh/src/main/java/com/zlt/aps/lh/engine/strategy/impl/DefaultSkuPriorityStrategy.java` | 删除 `compareNewSpecSku` 中续作补偿排序分支及 `resolveContinuousCompensationScore` 方法 |
| `aps-lh/src/test/com/zlt/aps/lh/engine/strategy/impl/DefaultSkuPriorityStrategyTest.java` | 删除/改写两条仅断言"补偿置顶"的用例，新增"统一规则"回归用例 |
| `openspec/specs/shortage-qty-twice‌-online/spec.md` | 增补 Scenario：续作补偿 SKU 在新增排产排序中无优先权 |

## 4. 设计要点

`compareNewSpecSku` 现有层级为：
1. 施工阶段分组分（试制 / 量试 / 正规）
2. ~~续作补偿优先（本次删除）~~
3. 试制/量试组内排序 或 正规组主排序
4. 物料编码字典序兜底

删除第 2 层后，所有续作补偿 SKU 仅按其原本所在的施工阶段组进入第 3 层主排序，
与其它新增 SKU 一致地比较定点机台、锁交期、延误、结构全收尾、供应链待排量等维度。

## 5. 详细实施步骤

1. `DefaultSkuPriorityStrategy.compareNewSpecSku`：
   - 删除 "续作欠产转入新增的补偿 SKU，只在同施工阶段组内提前" 整段比较；
   - 删除 `resolveContinuousCompensationScore` 私有方法和其方法注释。
2. `DefaultSkuPriorityStrategyTest`：
   - 删除 `sortByPriority_shouldKeepConstructionGroupBeforeContinuousCompensationSku`；
   - 改写 `sortByPriority_shouldPreferContinuousCompensationSkuWithinSameNewSpecGroup`
     为 `sortByPriority_continuousCompensationSkuShouldFollowUnifiedRule`，
     断言补偿 SKU 按高优先级数量等主排序进行排序（普通 SKU 排前）。
3. `openspec/specs/shortage-qty-twice‌-online/spec.md`：在
   `Scenario: 续作补偿 SKU 不受影响` 后增补 Scenario：
   - 续作补偿 SKU 在新增排产排序中不得因 `continuousCompensationSku=true` 提升名次；
   - 必须与同施工阶段组内其它新增 SKU 按统一规则比较。
4. 运行相关回归测试。

## 6. 数据处理说明

无新增/扣减/库存口径变化。`continuousCompensationSku` 标识仅影响：
- 选机时是否优先锁回原续作机台；
- 日志中"续作排产/新增排产"标识；
- 滚动衔接、欠产、收尾等非排序业务分支。

## 7. 边界场景

1. 补偿 SKU 与普通 SKU 同属"正规组"，普通 SKU 锁交期或延误更严重 → 普通 SKU 排前。
2. 补偿 SKU 与普通 SKU 同属"正规组"且主排序、供应链待排量、开产靠后分完全一致 →
   按物料编码字典序排序。
3. 补偿 SKU 位于"正规组"，新增链路中存在试制/量试 SKU →
   试制/量试组仍整体排在正规组之前（不受本变更影响）。
4. 补偿 SKU 在选机阶段仍优先锁回原续作机台（`resolvePreferredContinuousCompensationMachine`
   逻辑保持不变）。

## 8. 风险点

- 历史依赖"补偿 SKU 置顶"的排程结果会出现位序变化；
- 补偿 SKU 后移可能导致原续作机台被前序新增 SKU 选走，
  影响"补偿 SKU 优先锁回原续作机台"的实际命中率；
  但这是恢复设计原意后的预期行为，应通过排程优先级和机台候选策略本身处理。

## 9. 验证建议

1. 单元测试：`DefaultSkuPriorityStrategyTest`。
2. 回归测试：`NewSpecProductionStrategyRegressionTest`、
   `SchedulingStrategyRegressionTest`、`ContinuousMachineStateSyncRegressionTest`。
3. 接口验证：执行 `POST /lhScheduleResult/execute`，
   观察排程日志 `[新增排产SKU排序]` 中带 `补偿SKU=1` 的 SKU
   名次不再被强制置顶，且 SortKey 中无"补偿优先"层级。
