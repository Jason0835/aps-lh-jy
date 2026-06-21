# 续作恒定日计划降模 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 `3302001075` 在日计划 `46,46,46` 且两台续作机台单机日产能为 46 时，只保留一台纯续作机台。

**Architecture:** 复用 `ContinuousProductionStrategy` 现有按天降模和最小保留机台模拟，只扩大非收尾多机台的降模触发条件。保持收尾、换活字块、新增排产、日标准产量和数据持久化链路不变。

**Tech Stack:** Java 8、Spring Boot 2.7、JUnit 5、Maven、MySQL 8

## Global Constraints

- 仅做最小业务改动，不新增依赖、表、Mapper 或 XML。
- 代码注释、日志和提交说明使用简体中文。
- 生产代码修改前必须先运行新增测试并确认按预期失败。
- 真实验证日期固定为 `2026-06-14`，工厂固定为 `116`。

---

### Task 1: 恒定日计划多机台降模

**Files:**
- Modify: `aps-lh/src/test/java/com/zlt/aps/lh/engine/strategy/impl/SchedulingStrategyRegressionTest.java`
- Modify: `aps-lh/src/main/java/com/zlt/aps/lh/engine/strategy/impl/ContinuousProductionStrategy.java`

**Interfaces:**
- Consumes: `ContinuousProductionStrategy#scheduleReduceMould(LhScheduleContext)`、共享 `dailyPlanQuotaMap`
- Produces: 恒定正日计划也进入现有按天降模模拟，结果只保留最小可行纯续作机台集合

- [x] **Step 1: 写入失败回归测试**

增加 `shouldReduceContinuousMachinesWhenConstantDailyPlanFitsOneMachine`，构造物料 `3302001075`、班产 16、日计划 `46,46,46`、K1406/K1712 两台非收尾续作，断言三个业务日均只有一台正排续作机台且每天总量均为 46。

- [x] **Step 2: 运行测试并确认失败**

Run:

```bash
mvn -o -pl aps-lh -am -Dtest='SchedulingStrategyRegressionTest#shouldReduceContinuousMachinesWhenConstantDailyPlanFitsOneMachine' test
```

Expected: FAIL，当前实现返回两个正排续作机台。

- [x] **Step 3: 实施最小修改**

调整 `shouldReduceContinuationByWorkDate(...)`，当多机台续作窗口存在有效正日计划时进入现有按天降模链路；保留日计划下降日志，并补充“恒定日计划按最小机台数降模”的中文日志。降模需求按当日日计划计算，首日扣除排程日晚班完成量；保留机台满足后续需求时不再让下机机台因不可换模晚班继续占机。不得复制已有分配算法或增加物料特例。

- [x] **Step 4: 运行定向回归测试**

Run:

```bash
mvn -o -pl aps-lh -am -Dtest='SchedulingStrategyRegressionTest#shouldReduceContinuousMachinesWhenConstantDailyPlanFitsOneMachine+shouldReduceContinuousMachinesWhenFutureDayPlanDrops+shouldReduceEndingMarkedContinuousByWorkDateWhenFutureDayPlanDrops+shouldCapMultiMachineEndingContinuousByMaxSurplusAndEmbryoStock' test
```

Expected: 4 个测试全部通过，Failures=0，Errors=0。

- [x] **Step 5: 编译验证**

Run:

```bash
mvn -o -pl aps-lh -am -DskipTests package
```

Expected: `BUILD SUCCESS`。

- [x] **Step 6: 真实排程验证**

启动应用并调用：

```bash
curl --noproxy '*' --max-time 600 -X POST 'http://localhost:9669/lhScheduleResult/execute' \
  -H 'Content-Type: application/json' \
  -d '{"factoryCode":"116","scheduleDate":"2026-06-14"}'
```

核对新批次中 `3302001075` 的纯续作正排机台数为 1、`DAY_N_RANGE=46,46,46`，并检查未排记录、换模计划及关键降模日志。
