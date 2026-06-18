# SKU 提前生产结果备注 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 命中 SKU 提前生产并实际生成新增排产结果时，将所属结构 T～T+2 的计划硫化机台数及结构切换/结构收尾场景追加到结果备注。

**Architecture:** 新增独立的 `EarlyProductionDecision` 承载准入结果、场景和固定三日机台数；`EarlyProductionChecker` 统一生成该结果并保留原布尔入口；`NewSpecProductionStrategy` 在候选机台确定首个可排时间后只判定一次，并在结果计划量确认有效后追加备注。所有机台数继续读取上下文缓存，不新增查库、SQL 或 XML。

**Tech Stack:** Java 8、Spring、JUnit 5、Maven、MyBatis-Plus、现有硫化排程上下文。

---

### Task 1: 结构化提前生产判定

**Files:**
- Create: `aps-lh/src/main/java/com/zlt/aps/lh/engine/strategy/support/EarlyProductionDecision.java`
- Modify: `aps-lh/src/main/java/com/zlt/aps/lh/engine/strategy/support/EarlyProductionChecker.java`
- Test: `aps-lh/src/test/com/zlt/aps/lh/engine/strategy/support/EarlyProductionCheckerTest.java`

- [ ] **Step 1: 写入结构化判定失败测试**

新增测试分别断言普通、结构切换、结构收尾、欠产超阈值和当前日有计划场景：

```java
EarlyProductionDecision decision = EarlyProductionChecker.checkEarlyProduction(
        context, sku, day1, day1, day3, 200);

assertTrue(decision.isAllowed());
assertTrue(decision.isEarlyProduction());
assertEquals(EarlyProductionDecision.SCENE_STRUCTURE_SWITCH, decision.getSceneType());
assertEquals(Arrays.asList(0, 2, 4), decision.getStructurePlanMachineCounts());
assertEquals("[结构切换] 结构计划硫化机台数：0,2,4", decision.buildRemark());
```

当前日有计划时断言 `isEarlyProduction()` 为 `false`、`buildRemark()` 为空；欠产超阈值时断言场景为普通提前生产，不误标结构切换或结构收尾。

- [ ] **Step 2: 运行测试确认 RED**

Run:

```bash
mvn -pl aps-lh -am -Dtest=EarlyProductionCheckerTest test
```

Expected: FAIL，原因是 `EarlyProductionDecision` 或 `checkEarlyProduction(...)` 尚不存在。

- [ ] **Step 3: 实现最小结构化判定**

`EarlyProductionDecision` 使用 Java 8 可序列化对象，定义普通、结构切换、结构收尾常量，并用 `StringBuilder` 生成固定格式：

```java
public String buildRemark() {
    if (!earlyProduction || !allowed || CollectionUtils.isEmpty(structurePlanMachineCounts)) {
        return StringUtils.EMPTY;
    }
    StringBuilder remark = new StringBuilder();
    if (SCENE_STRUCTURE_SWITCH.equals(sceneType)) {
        remark.append("[结构切换] ");
    } else if (SCENE_STRUCTURE_ENDING.equals(sceneType)) {
        remark.append("[结构收尾] ");
    }
    remark.append("结构计划硫化机台数：");
    for (int index = 0; index < structurePlanMachineCounts.size(); index++) {
        if (index > 0) {
            remark.append(',');
        }
        remark.append(structurePlanMachineCounts.get(index));
    }
    return remark.toString();
}
```

`EarlyProductionChecker.checkEarlyProduction(...)` 接收显式 `windowStartDate` 和 `windowEndDate`，固定读取 `windowStartDate`、`+1`、`+2` 三日结构机台数；原 `canEnterEarlyProductionCheck(...)` 委托新方法并返回 `isAllowed()`，保持兼容。

- [ ] **Step 4: 运行测试确认 GREEN**

Run:

```bash
mvn -pl aps-lh -am -Dtest=EarlyProductionCheckerTest test
```

Expected: PASS，所有结构化场景和原布尔测试通过。

- [ ] **Step 5: 提交结构化判定**

```bash
git add aps-lh/src/main/java/com/zlt/aps/lh/engine/strategy/support/EarlyProductionDecision.java \
        aps-lh/src/main/java/com/zlt/aps/lh/engine/strategy/support/EarlyProductionChecker.java \
        aps-lh/src/test/com/zlt/aps/lh/engine/strategy/support/EarlyProductionCheckerTest.java
git commit -m "feat: 增加提前生产结构化判定"
```

### Task 2: 新增排产结果追加备注

**Files:**
- Modify: `aps-lh/src/main/java/com/zlt/aps/lh/engine/strategy/impl/NewSpecProductionStrategy.java`
- Test: `aps-lh/src/test/com/zlt/aps/lh/regression/NewSpecProductionStrategyRegressionTest.java`

- [ ] **Step 1: 写入真实新增排产失败测试**

基于现有 `scheduleNewSpecs_shouldSkipFirstDayWhenFormalNonEndingHasNoFirstDayPlan` 场景新增三条回归测试：

```java
context.addStructurePlanMachineCount(day1, sku.getStructureName(), 1);
context.addStructurePlanMachineCount(day2, sku.getStructureName(), 2);
context.addStructurePlanMachineCount(day3, sku.getStructureName(), 3);

strategy.scheduleNewSpecs(context, singletonMachineMatch(machine),
        defaultMouldChangeBalance(), defaultInspectionBalance(), skuCapacityCalculate());

assertEquals("结构计划硫化机台数：1,2,3",
        context.getScheduleResultList().get(0).getRemark());
```

另覆盖结构切换格式和已有备注追加/相同片段不重复追加。追加方法通过 `ReflectionTestUtils.invokeMethod(...)` 独立验证已有备注保护，真实排程测试验证只对成功结果落备注。

- [ ] **Step 2: 运行测试确认 RED**

Run:

```bash
mvn -pl aps-lh -am \
  -Dtest=NewSpecProductionStrategyRegressionTest#scheduleNewSpecs_shouldAppendEarlyProductionStructurePlanRemark+scheduleNewSpecs_shouldAppendStructureSwitchRemark+appendEarlyProductionRemark_shouldPreserveExistingRemarkAndAvoidDuplicate test
```

Expected: FAIL，实际 `remark` 为空或追加方法不存在。

- [ ] **Step 3: 在现有新增排产链路接入判定结果**

在候选机台首个可排时间确定后构造一次 `EarlyProductionDecision`，传给 `alignFirstProductionStartTimeByDailyPlan(...)`；准入通过时保持当前开产时间，失败时仍按原逻辑顺延。

在 `applyBlockToDailyQuota(...)` 返回正数后、加入 `scheduleResultList` 前调用：

```java
appendEarlyProductionRemark(result, earlyProductionDecision);
```

追加方法遵循：原备注空则直接写入；非空则以中文分号追加；已包含完全相同片段则跳过。日志包含工厂、业务日期、SKU、结构、机台、场景和最终备注。

- [ ] **Step 4: 运行新增排产回归测试确认 GREEN**

Run:

```bash
mvn -pl aps-lh -am \
  -Dtest=EarlyProductionCheckerTest,NewSpecProductionStrategyRegressionTest#scheduleNewSpecs_shouldAppendEarlyProductionStructurePlanRemark+scheduleNewSpecs_shouldAppendStructureSwitchRemark+appendEarlyProductionRemark_shouldPreserveExistingRemarkAndAvoidDuplicate test
```

Expected: PASS，实际成功结果包含约定格式，原备注不被覆盖且不重复。

- [ ] **Step 5: 提交结果备注接入**

```bash
git add aps-lh/src/main/java/com/zlt/aps/lh/engine/strategy/impl/NewSpecProductionStrategy.java \
        aps-lh/src/test/com/zlt/aps/lh/regression/NewSpecProductionStrategyRegressionTest.java
git commit -m "feat: 保存提前生产结构机台备注"
```

### Task 3: 主规格同步和完整验证

**Files:**
- Modify: `openspec/specs/sku-early-production/spec.md`

- [ ] **Step 1: 更新主规格**

在主规格补充结果备注要求：仅实际生成的新增排产结果写入；T～T+2 三日机台数固定逗号分隔；普通、结构切换、结构收尾格式；已有备注中文分号追加；欠产超阈值只标普通提前生产。

- [ ] **Step 2: 编译并运行相关测试**

Run:

```bash
mvn -pl aps-lh -am -DskipTests compile
mvn -pl aps-lh -am \
  -Dtest=EarlyProductionCheckerTest,MonthPlanStatisticsDayUtilTest,LhBaseDataServiceImplTest,NewSpecProductionStrategyTest,NewSpecProductionStrategyRegressionTest test
```

Expected: BUILD SUCCESS，新旧相关测试全部通过。

- [ ] **Step 3: 启动服务并执行真实排程**

确认 `9669` 无旧进程后启动：

```bash
mvn spring-boot:run -pl aps-lh -Dmaven.test.skip=true
curl --noproxy '*' -X POST 'http://localhost:9669/lhScheduleResult/execute' \
  -H 'Content-Type: application/json' \
  -d '{"factoryCode":"116","scheduleDate":"2026-06-14"}'
```

Expected: 接口返回 `success=true` 和新批次号。

- [ ] **Step 4: 核对结果表和日志**

按新批次查询 `T_LH_SCHEDULE_RESULT`，确认命中结果的 `REMARK` 符合格式，非命中结果不受影响；同时核对结果数、未排数、换模计划数、过程日志及“提前生产结果备注追加”日志。

- [ ] **Step 5: 提交规格与验证收尾**

```bash
git add openspec/specs/sku-early-production/spec.md
git commit -m "docs: 更新提前生产结果备注规则"
```

最终执行 `git status --short`，确认无启动日志、PID、临时 SQL 或其他死文件残留。
