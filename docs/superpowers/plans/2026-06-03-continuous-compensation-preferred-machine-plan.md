# 续作补偿 SKU 优先锁回原续作机台 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让续作释放后转入新增阶段的补偿 SKU 先参与新增统一排序，再在轮到自己选机时优先锁回原续作机台。

**Architecture:** 在 `ContinuousProductionStrategy` 生成补偿 SKU 时保留“原续作优先机台”语义，但不复用 `continuousMachineCode`。在 `NewSpecProductionStrategy` 当前 SKU 选机回合增加局部优先逻辑，命中时优先尝试原续作机台，失败再回退现有新增选机链路。

**Tech Stack:** Java 8, Spring Test, JUnit 5, 现有排程策略与回归测试基座

---

### Task 1: 为补偿 SKU 保留原续作优先机台语义

**Files:**
- Modify: `aps-lh-api/src/main/java/com/zlt/aps/lh/api/domain/dto/SkuScheduleDTO.java`
- Modify: `aps-lh/src/main/java/com/zlt/aps/lh/engine/strategy/impl/ContinuousProductionStrategy.java`
- Test: `aps-lh/src/test/com/zlt/aps/lh/engine/strategy/impl/ContinuousProductionStrategyTest.java`

- [ ] Step 1: 先写失败测试，证明补偿 SKU 复制后会保留原续作优先机台，但 `continuousMachineCode` 仍为空
- [ ] Step 2: 运行 `ContinuousProductionStrategyTest` 定向用例，确认新增断言先失败
- [ ] Step 3: 在 `SkuScheduleDTO` 增加局部字段，并在 `copyContinuousCompensationSku(...)` 中写入来源续作机台
- [ ] Step 4: 再次运行定向测试，确认通过

### Task 2: 在新增排产当前 SKU 回合优先锁回原续作机台

**Files:**
- Modify: `aps-lh/src/main/java/com/zlt/aps/lh/engine/strategy/impl/NewSpecProductionStrategy.java`
- Test: `aps-lh/src/test/com/zlt/aps/lh/engine/strategy/impl/NewSpecProductionStrategyTest.java`
- Test: `aps-lh/src/test/com/zlt/aps/lh/regression/NewSpecProductionStrategyRegressionTest.java`

- [ ] Step 1: 先写失败测试，覆盖“补偿 SKU 轮到自己时优先选原续作机台”
- [ ] Step 2: 再写失败测试，覆盖“原续作机台不在候选或不可用时回退现有逻辑”
- [ ] Step 3: 运行 `NewSpecProductionStrategyTest` / `NewSpecProductionStrategyRegressionTest` 定向用例，确认至少一条先失败
- [ ] Step 4: 在 `NewSpecProductionStrategy` 增加小型私有方法，只在当前补偿 SKU 自己回合解析原续作优先机台
- [ ] Step 5: 重新运行定向测试，确认通过

### Task 3: 回归验证与影响核对

**Files:**
- Verify only: `aps-lh/src/test/com/zlt/aps/lh/engine/strategy/impl/ContinuousProductionStrategyTest.java`
- Verify only: `aps-lh/src/test/com/zlt/aps/lh/engine/strategy/impl/NewSpecProductionStrategyTest.java`
- Verify only: `aps-lh/src/test/com/zlt/aps/lh/regression/NewSpecProductionStrategyRegressionTest.java`

- [ ] Step 1: 运行本次新增的定向测试集合
- [ ] Step 2: 运行受影响的补偿 SKU / 释放机台 / 新增选机回归用例
- [ ] Step 3: 核对未引入 SQL/XML/配置变更，确认影响仅在 Java 主链
- [ ] Step 4: 输出验证结果与剩余风险
