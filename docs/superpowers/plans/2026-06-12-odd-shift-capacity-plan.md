# 奇数班产按班别修正班次计划量 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在新增排程、续作排产、换活字块排产中统一按硫化参数修正奇数班产的班次计划量，并保持班产落库字段为原始值。

**Architecture:** 参数链路接入 `LhScheduleConfig`，产能口径集中增强 `ShiftCapacityResolverUtil` 的显式重载。三条排程主链只在已有班次产能计算、窗口模拟、结果分配入口传入排程类型，避免影响无关流程。

**Tech Stack:** Java 8、JUnit 5、MyBatis-Plus、现有硫化排程策略类。

---

### Task 1: 参数与公共班次产能口径

**Files:**
- Modify: `aps-lh-api/src/main/java/com/zlt/aps/lh/api/constant/LhScheduleParamConstant.java`
- Modify: `aps-lh-api/src/main/java/com/zlt/aps/lh/api/constant/LhScheduleConstant.java`
- Modify: `aps-lh/src/main/java/com/zlt/aps/lh/component/LhScheduleConfigResolver.java`
- Modify: `aps-lh/src/main/java/com/zlt/aps/lh/context/LhScheduleConfig.java`
- Modify: `aps-lh/src/main/java/com/zlt/aps/lh/util/ShiftCapacityResolverUtil.java`
- Test: `aps-lh/src/test/com/zlt/aps/lh/util/ShiftCapacityResolverUtilTest.java`

- [ ] 写失败测试：未配置、非法配置、偶数班产均保持原值；配置 1/2/3 且奇数班产时按班别返回 +1/-1。
- [ ] 增加参数常量与配置快照读取方法，默认空字符串表示不配置。
- [ ] 在 `ShiftCapacityResolverUtil` 新增显式重载，按 `LhShiftConfigVO.resolveShiftTypeEnum()` 判断早/中/晚班。
- [ ] 运行 `ShiftCapacityResolverUtilTest`，确认红绿通过。

### Task 2: 新增排程接入

**Files:**
- Modify: `aps-lh/src/main/java/com/zlt/aps/lh/engine/strategy/impl/NewSpecProductionStrategy.java`
- Modify: `aps-lh/src/main/java/com/zlt/aps/lh/engine/strategy/support/DailyMachineCapacitySimulationRequest.java`
- Modify: `aps-lh/src/main/java/com/zlt/aps/lh/engine/strategy/support/DailyMachineCapacitySimulationUtil.java`

- [ ] 将 `calculateShiftCapacityMap` 和候选机台 dayN 模拟改为修正后班次产能。
- [ ] dayN 理论 8 班、后一天 3 班判断改为逐班按修正后能力累加。
- [ ] 保持 `singleMouldShiftQty` 等班产落库字段不变。

### Task 3: 续作与换活字块接入

**Files:**
- Modify: `aps-lh/src/main/java/com/zlt/aps/lh/engine/strategy/impl/ContinuousProductionStrategy.java`
- Modify: `aps-lh/src/main/java/com/zlt/aps/lh/engine/strategy/impl/TypeBlockProductionStrategy.java`
- Modify: `aps-lh/src/main/java/com/zlt/aps/lh/component/TargetScheduleQtyResolver.java`

- [ ] 续作产能判断、降模减机台、晚班补满、重新分配班次均使用修正后班次产能。
- [ ] 换活字块目标量窗口产能、结果班次分配、回流新增前判断均使用修正后班次产能。
- [ ] 收尾严格目标量仍按目标量截断，非收尾补满上限使用修正后班次计划量。

### Task 4: 验证

**Files:**
- Modify targeted regression tests as needed.

- [ ] 运行公共工具测试。
- [ ] 运行新增、续作、换活字块相关定向回归。
- [ ] 运行 `mvn -pl aps-lh -am -DskipTests package` 或等价编译验证。
