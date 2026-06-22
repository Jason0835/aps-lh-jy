# 窗口无计划新增拦截与续作余量排完 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 阻止无 T～T+2 计划且无欠产来源的新增 SKU 提前消耗窗口外计划，同时保证有硫化余量的续作 SKU 沿用现有逻辑排完并释放机台。

**Architecture:** 保留 S4.3 完整 SKU 构建和 MES 续作匹配，在续作/新增分类完成后只拦截未命中续作的新增 SKU；续作侧将“窗口无计划直接释放”收紧为“窗口无计划且无余量才释放”，有余量时同步严格收尾目标并复用现有续作增机、降模和释放链。新增一个 DTO 运行态字段承接有效上月超欠产，不新增表、SQL、XML或参数。

**Tech Stack:** Java 8、Spring Boot 2.7、JUnit 5、Spring `ReflectionTestUtils`、Maven、MyBatis-Plus。

---

## 文件结构

- 修改 `aps-lh-api/src/main/java/com/zlt/aps/lh/api/domain/dto/SkuScheduleDTO.java`：保存有效上月超欠产运行态值。
- 修改 `aps-lh/src/main/java/com/zlt/aps/lh/handler/ScheduleAdjustHandler.java`：DTO 映射、分类后新增准入、未排落库及运行态剔除。
- 修改 `aps-lh/src/main/java/com/zlt/aps/lh/engine/strategy/impl/ContinuousProductionStrategy.java`：窗口无计划续作按余量继续排产，余量为 0 才释放。
- 修改 `aps-lh/src/test/com/zlt/aps/lh/regression/ContinuousSkuAssignmentRegressionTest.java`：新增分类准入和 DTO 复制回归。
- 修改 `aps-lh/src/test/com/zlt/aps/lh/engine/strategy/impl/ContinuousProductionStrategyTest.java`：续作有余量继续排、无余量释放及释放后承接回归。

### Task 1: 增加有效上月超欠产运行态字段

**Files:**
- Modify: `aps-lh-api/src/main/java/com/zlt/aps/lh/api/domain/dto/SkuScheduleDTO.java`
- Modify: `aps-lh/src/main/java/com/zlt/aps/lh/handler/ScheduleAdjustHandler.java`
- Test: `aps-lh/src/test/com/zlt/aps/lh/regression/ContinuousSkuAssignmentRegressionTest.java`

- [ ] **Step 1: 编写 DTO 复制失败测试**

在 `ContinuousSkuAssignmentRegressionTest` 增加：

```java
@Test
void copySkuForContinuousMachine_shouldKeepEffectiveLastMonthOverdueQty() {
    SkuScheduleDTO source = buildSku("3302002637", "285/70R19.5");
    source.setEffectiveLastMonthOverdueQty(18);

    SkuScheduleDTO copy = ReflectionTestUtils.invokeMethod(
            handler, "copySkuForContinuousMachine", source, "K2010");

    assertEquals(18, copy.getEffectiveLastMonthOverdueQty());
}
```

- [ ] **Step 2: 运行测试并确认 RED**

Run:

```bash
mvn -pl aps-lh -am -Dtest=ContinuousSkuAssignmentRegressionTest#copySkuForContinuousMachine_shouldKeepEffectiveLastMonthOverdueQty test
```

Expected: 编译失败，提示 `SkuScheduleDTO` 尚无 `effectiveLastMonthOverdueQty`。

- [ ] **Step 3: 最小实现 DTO 字段及映射**

在 `SkuScheduleDTO` 的历史计划量字段附近增加：

```java
/** 有效上月超欠产量；正数为欠产，负数为超产，仅用于本轮排产准入判断 */
private int effectiveLastMonthOverdueQty;
```

在 `ScheduleAdjustHandler.buildSkuScheduleDTO(...)` 设置：

```java
dto.setEffectiveLastMonthOverdueQty(surplus.getLastMonthOverdueQty());
```

在 `copySkuForContinuousMachine(...)` 同步复制：

```java
copy.setEffectiveLastMonthOverdueQty(source.getEffectiveLastMonthOverdueQty());
```

- [ ] **Step 4: 运行测试并确认 GREEN**

Run:

```bash
mvn -pl aps-lh -am -Dtest=ContinuousSkuAssignmentRegressionTest#copySkuForContinuousMachine_shouldKeepEffectiveLastMonthOverdueQty test
```

Expected: PASS，1 test，0 failures。

- [ ] **Step 5: 中文提交**

```bash
git add aps-lh-api/src/main/java/com/zlt/aps/lh/api/domain/dto/SkuScheduleDTO.java \
  aps-lh/src/main/java/com/zlt/aps/lh/handler/ScheduleAdjustHandler.java \
  aps-lh/src/test/com/zlt/aps/lh/regression/ContinuousSkuAssignmentRegressionTest.java
git commit -m "修复：补充上月超欠产排程准入字段"
```

### Task 2: 分类后拦截无窗口计划的新增 SKU

**Files:**
- Modify: `aps-lh/src/main/java/com/zlt/aps/lh/handler/ScheduleAdjustHandler.java`
- Test: `aps-lh/src/test/com/zlt/aps/lh/regression/ContinuousSkuAssignmentRegressionTest.java`

- [ ] **Step 1: 编写新增 SKU 拦截失败测试**

增加测试，构造工厂、批次、业务日期和单个未匹配 MES 在机的 SKU：

```java
@Test
void classifyContinuousAndNewSkus_shouldWriteUnscheduledWhenOnlyFuturePlanExists() {
    LhScheduleContext context = buildClassificationContext();
    SkuScheduleDTO sku = buildSku("3302002637", "285/70R19.5");
    sku.setWindowPlanQty(0);
    sku.setFutureMonthPlanQtyAfterWindow(62);
    sku.setMonthlyHistoryShortageQty(0);
    sku.setEffectiveLastMonthOverdueQty(0);
    sku.setSurplusQty(276);
    sku.setTargetScheduleQty(144);
    context.getStructureSkuMap().put(sku.getStructureName(),
            new ArrayList<SkuScheduleDTO>(Arrays.asList(sku)));

    ReflectionTestUtils.invokeMethod(handler, "classifyContinuousAndNewSkus", context);

    assertEquals(0, context.getNewSpecSkuList().size());
    assertEquals(1, context.getUnscheduledResultList().size());
    assertEquals(0, context.getUnscheduledResultList().get(0).getUnscheduledQty().intValue());
    assertTrue(context.getUnscheduledResultList().get(0).getUnscheduledReason()
            .contains("T～T+2窗口无日计划且无欠产"));
    assertTrue(context.getStructureSkuMap().isEmpty());
}
```

补充以下边界测试，每个测试只改变一个条件并断言仍进入 `newSpecSkuList`：

```java
@Test
void classifyContinuousAndNewSkus_shouldKeepNewSkuWhenCurrentMonthShortageExists() {
    assertWindowNoPlanSkuKeptAsNew(10, 0, 62, 0);
}

@Test
void classifyContinuousAndNewSkus_shouldKeepNewSkuWhenLastMonthShortageExists() {
    assertWindowNoPlanSkuKeptAsNew(0, 10, 62, 0);
}

@Test
void classifyContinuousAndNewSkus_shouldKeepNewSkuWhenWindowPlanExists() {
    assertWindowNoPlanSkuKeptAsNew(0, 0, 62, 18);
}

@Test
void classifyContinuousAndNewSkus_shouldKeepOverallEndingSkuWhenNoFuturePlanExists() {
    assertWindowNoPlanSkuKeptAsNew(0, 0, 0, 0);
}

private void assertWindowNoPlanSkuKeptAsNew(int historyShortageQty,
                                             int lastMonthOverdueQty,
                                             int futurePlanQty,
                                             int windowPlanQty) {
    LhScheduleContext context = buildClassificationContext();
    SkuScheduleDTO sku = buildSku("MAT-BOUNDARY", "STRUCT-BOUNDARY");
    sku.setWindowPlanQty(windowPlanQty);
    sku.setFutureMonthPlanQtyAfterWindow(futurePlanQty);
    sku.setMonthlyHistoryShortageQty(historyShortageQty);
    sku.setEffectiveLastMonthOverdueQty(lastMonthOverdueQty);
    sku.setSurplusQty(100);
    sku.setTargetScheduleQty(100);
    context.getStructureSkuMap().put(sku.getStructureName(),
            new ArrayList<SkuScheduleDTO>(Arrays.asList(sku)));

    ReflectionTestUtils.invokeMethod(handler, "classifyContinuousAndNewSkus", context);

    assertEquals(1, context.getNewSpecSkuList().size());
    assertEquals(0, context.getUnscheduledResultList().size());
}

private LhScheduleContext buildClassificationContext() {
    LhScheduleContext context = new LhScheduleContext();
    context.setFactoryCode("116");
    context.setBatchNo("LHPC-CLASSIFY-TEST");
    context.setScheduleTargetDate(new Date());
    context.setStructureSkuMap(new LinkedHashMap<String, java.util.List<SkuScheduleDTO>>());
    context.setMachineOnlineInfoMap(new LinkedHashMap<String, LhMachineOnlineInfo>());
    context.setMachineScheduleMap(new LinkedHashMap<String, MachineScheduleDTO>());
    return context;
}
```

为测试文件补充导入：

```java
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertTrue;
```

- [ ] **Step 2: 运行测试并确认 RED**

Run:

```bash
mvn -pl aps-lh -am -Dtest=ContinuousSkuAssignmentRegressionTest test
```

Expected: 新增拦截测试失败，实际 `newSpecSkuList` 仍包含 `3302002637`。

- [ ] **Step 3: 实现分类后新增准入**

在 `ScheduleAdjustHandler` 增加常量：

```java
private static final String WINDOW_NO_PLAN_NO_SHORTAGE_UNSCHEDULED_REASON =
        "T～T+2窗口无日计划且无欠产，窗口后仍有月计划，本次不排产";
```

增加判定方法：

```java
private boolean shouldSkipWindowNoPlanNewSku(SkuScheduleDTO sku) {
    return Objects.nonNull(sku)
            && sku.getWindowPlanQty() <= 0
            && sku.getFutureMonthPlanQtyAfterWindow() > 0
            && sku.getMonthlyHistoryShortageQty() <= 0
            && sku.getEffectiveLastMonthOverdueQty() <= 0;
}
```

在 `classifyContinuousAndNewSkus(...)` 完成 MES/滚动续作分配后，对尚未标记续作的 SKU 判断：

```java
List<SkuScheduleDTO> blockedNewSkuList = new ArrayList<SkuScheduleDTO>(8);
// 遍历结构 SKU 时
if (shouldSkipWindowNoPlanNewSku(sku)) {
    appendWindowNoPlanNewSkuUnscheduledResult(context, sku);
    blockedNewSkuList.add(sku);
    continue;
}
sku.setScheduleType(ScheduleTypeEnum.NEW_SPEC.getCode());
sku.setContinuousMachineCode(null);
newSpecSkuList.add(sku);
```

遍历结束后统一清理，避免遍历结构集合时并发修改：

```java
for (SkuScheduleDTO blockedSku : blockedNewSkuList) {
    blockedSku.setTargetScheduleQty(0);
    blockedSku.setRemainingScheduleQty(0);
    context.removePendingSkuFromStructureMap(blockedSku);
    getTargetScheduleQtyResolver().removeActiveEmbryoSku(
            context, blockedSku, WINDOW_NO_PLAN_NO_SHORTAGE_UNSCHEDULED_REASON);
}
```

未排方法复用 `buildBaseUnscheduledResult(...)`：

```java
private void appendWindowNoPlanNewSkuUnscheduledResult(LhScheduleContext context,
                                                        SkuScheduleDTO sku) {
    LhUnscheduledResult unscheduled = buildBaseUnscheduledResult(context, sku);
    unscheduled.setUnscheduledQty(0);
    unscheduled.setUnscheduledReason(WINDOW_NO_PLAN_NO_SHORTAGE_UNSCHEDULED_REASON);
    context.getUnscheduledResultList().add(unscheduled);
    String detail = String.format(
            "窗口无计划新增SKU不排产, factoryCode: %s, materialCode: %s, windowPlanQty: %d, "
                    + "futureMonthPlanQtyAfterWindow: %d, monthlyHistoryShortageQty: %d, "
                    + "effectiveLastMonthOverdueQty: %d, surplusQty: %d, reason: %s",
            context.getFactoryCode(), sku.getMaterialCode(), sku.getWindowPlanQty(),
            sku.getFutureMonthPlanQtyAfterWindow(), sku.getMonthlyHistoryShortageQty(),
            sku.getEffectiveLastMonthOverdueQty(), sku.getSurplusQty(),
            WINDOW_NO_PLAN_NO_SHORTAGE_UNSCHEDULED_REASON);
    log.info(detail);
    PriorityTraceLogHelper.appendProcessLog(context, "窗口无计划新增SKU不排产", detail);
}
```

添加 `PriorityTraceLogHelper` import。不得修改 `TargetScheduleQtyResolver.resolveInitialTargetQty(...)`，避免扩大到续作和整体收尾。

- [ ] **Step 4: 运行测试并确认 GREEN**

Run:

```bash
mvn -pl aps-lh -am -Dtest=ContinuousSkuAssignmentRegressionTest test
```

Expected: 全部 PASS。

- [ ] **Step 5: 中文提交**

```bash
git add aps-lh/src/main/java/com/zlt/aps/lh/handler/ScheduleAdjustHandler.java \
  aps-lh/src/test/com/zlt/aps/lh/regression/ContinuousSkuAssignmentRegressionTest.java
git commit -m "修复：拦截窗口无计划且无欠产新增SKU"
```

### Task 3: 续作有余量时继续排完并沿用释放链

**Files:**
- Modify: `aps-lh/src/main/java/com/zlt/aps/lh/engine/strategy/impl/ContinuousProductionStrategy.java`
- Test: `aps-lh/src/test/com/zlt/aps/lh/engine/strategy/impl/ContinuousProductionStrategyTest.java`

- [ ] **Step 1: 调整既有释放测试为“零余量释放”**

在 `scheduleContinuousEnding_shouldReleaseMachineWhenWindowHasNoDailyPlan` 中明确设置：

```java
SkuScheduleDTO sku = context.getContinuousSkuList().get(0);
sku.setSurplusQty(0);
sku.setPendingQty(0);
sku.setTargetScheduleQty(0);
sku.setFutureMonthPlanQtyAfterWindow(62);
```

保留断言：无续作结果、写未排、`K1712` 进入释放集合。

- [ ] **Step 2: 编写“有余量继续续作”失败测试**

```java
@Test
void scheduleContinuousEnding_shouldContinueWhenWindowNoPlanButSurplusExists() throws Exception {
    ContinuousProductionStrategy strategy = new ContinuousProductionStrategy();
    injectField(strategy, "orderNoGenerator", new OrderNoGenerator());
    injectField(strategy, "targetScheduleQtyResolver", new TargetScheduleQtyResolver());
    injectField(strategy, "endingJudgmentStrategy", new StubEndingJudgmentStrategy());
    LhScheduleContext context = buildWindowNoPlanContinuousContext();
    SkuScheduleDTO sku = context.getContinuousSkuList().get(0);
    sku.setSurplusQty(80);
    sku.setPendingQty(80);
    sku.setTargetScheduleQty(80);
    sku.setFutureMonthPlanQtyAfterWindow(62);

    strategy.scheduleContinuousEnding(context);
    strategy.scheduleReduceMould(context);

    assertFalse(context.getScheduleResultList().isEmpty());
    assertTrue(context.getScheduleResultList().stream()
            .anyMatch(result -> "3302001077".equals(result.getMaterialCode())
                    && ShiftFieldUtil.resolveScheduledQty(result) > 0));
    assertEquals(0, context.getUnscheduledResultList().size());
}
```

再增加一个释放状态测试：续作余量小于原机台窗口产能，执行续作和降模后，断言原机台已经标记收尾且预计结束时间等于续作结果完工时间；现有 `ContinuousProductionTypeBlockRegressionTest` 继续验证收尾机台可被换活字块/其他 SKU 承接。测试名称及核心断言：

```java
@Test
void scheduleContinuousEnding_shouldExposeMachineAfterSurplusFinished() throws Exception {
    ContinuousProductionStrategy strategy = new ContinuousProductionStrategy();
    injectField(strategy, "orderNoGenerator", new OrderNoGenerator());
    injectField(strategy, "targetScheduleQtyResolver", new TargetScheduleQtyResolver());
    injectField(strategy, "endingJudgmentStrategy", new StubEndingJudgmentStrategy());
    LhScheduleContext context = buildWindowNoPlanContinuousContext();
    SkuScheduleDTO sku = context.getContinuousSkuList().get(0);
    sku.setSurplusQty(20);
    sku.setPendingQty(20);
    sku.setTargetScheduleQty(20);
    sku.setFutureMonthPlanQtyAfterWindow(62);

    strategy.scheduleContinuousEnding(context);
    strategy.scheduleReduceMould(context);

    LhScheduleResult result = context.getScheduleResultList().get(0);
    MachineScheduleDTO machine = context.getMachineScheduleMap().get("K1712");
    assertEquals("1", result.getIsEnd());
    assertTrue(machine.isEnding());
    assertEquals(result.getSpecEndTime(), machine.getEstimatedEndTime());
}
```

- [ ] **Step 3: 运行测试并确认 RED**

Run:

```bash
mvn -pl aps-lh -am -Dtest=ContinuousProductionStrategyTest#scheduleContinuousEnding_shouldContinueWhenWindowNoPlanButSurplusExists+scheduleContinuousEnding_shouldExposeMachineAfterSurplusFinished test
```

Expected: 第一个测试实际无续作结果；第二个测试中其他 SKU 无法承接释放机台。

- [ ] **Step 4: 最小修改续作窗口无计划规则**

将释放判断改为同时读取 SKU：

```java
private boolean shouldReleaseWindowNoPlanContinuousSku(
        SkuScheduleDTO sku, DailyMachineShortageQuotaPlan shortageQuotaPlan) {
    return Objects.nonNull(sku)
            && Objects.nonNull(shortageQuotaPlan)
            && shortageQuotaPlan.isNoWindowPlan()
            && Math.max(0, shortageQuotaPlan.getHistoryShortageQty()) <= 0
            && Math.max(0, sku.getSurplusQty()) <= 0;
}
```

调用点改为：

```java
if (shouldReleaseWindowNoPlanContinuousSku(sku, shortageQuotaPlan)) {
```

增加有余量强制续作收尾判定：

```java
private boolean shouldFinishWindowNoPlanContinuousSurplus(
        SkuScheduleDTO sku, DailyMachineShortageQuotaPlan shortageQuotaPlan) {
    return Objects.nonNull(sku)
            && Objects.nonNull(shortageQuotaPlan)
            && shortageQuotaPlan.isNoWindowPlan()
            && Math.max(0, shortageQuotaPlan.getHistoryShortageQty()) <= 0
            && Math.max(0, sku.getSurplusQty()) > 0;
}
```

在普通收尾判断前应用：

```java
boolean finishWindowNoPlanSurplus =
        shouldFinishWindowNoPlanContinuousSurplus(sku, shortageQuotaPlan);
if (finishWindowNoPlanSurplus) {
    applyContinuousWindowNoPlanSurplusStrictTarget(context, sku, shortageQuotaPlan);
}
boolean isEnding = finishWindowNoPlanSurplus || endingJudgmentStrategy.isEnding(context, sku);
```

新增 `applyContinuousWindowNoPlanSurplusStrictTarget(...)`，复用现有严格收尾字段和模数归整：

```java
private void applyContinuousWindowNoPlanSurplusStrictTarget(
        LhScheduleContext context, SkuScheduleDTO sku,
        DailyMachineShortageQuotaPlan shortageQuotaPlan) {
    sku.setSkuTag(SkuTagEnum.ENDING.getCode());
    if (sku.getEndingDaysRemaining() <= 0) {
        sku.setEndingDaysRemaining(1);
    }
    // 复用统一收尾目标量和账本同步，避免窗口 dayN 为 0 时结果被回裁。
    int strictTargetQty = getTargetScheduleQtyResolver().upsizeEndingTargetQty(context, sku);
    log.info("续作窗口无日计划但仍有硫化余量，继续按余量严格排产, materialCode: {}, "
                    + "machineCode: {}, surplusQty: {}, futureMonthPlanQtyAfterWindow: {}, "
                    + "strictTargetQty: {}, result: 继续续作并在排完后释放机台",
            sku.getMaterialCode(), sku.getContinuousMachineCode(), sku.getSurplusQty(),
            sku.getFutureMonthPlanQtyAfterWindow(), strictTargetQty);
}
```

不得修改续作增机、补偿和降模方法；这些方法继续消费统一后的严格目标量。

- [ ] **Step 5: 运行 focused tests 并确认 GREEN**

Run:

```bash
mvn -pl aps-lh -am -Dtest=ContinuousProductionStrategyTest test
```

Expected: 全部 PASS，包括零余量释放、历史欠产、动态收尾、增机和新增承接既有测试。

- [ ] **Step 6: 中文提交**

```bash
git add aps-lh/src/main/java/com/zlt/aps/lh/engine/strategy/impl/ContinuousProductionStrategy.java \
  aps-lh/src/test/com/zlt/aps/lh/engine/strategy/impl/ContinuousProductionStrategyTest.java
git commit -m "修复：窗口无计划续作按余量排完后释放机台"
```

### Task 4: 完整回归与静态校验

**Files:**
- Verify only

- [ ] **Step 1: 编译 Java 8 模块**

```bash
mvn -pl aps-lh -am -DskipTests compile
```

Expected: `BUILD SUCCESS`。

- [ ] **Step 2: 运行定向回归**

```bash
mvn -pl aps-lh -am \
  -Dtest=ContinuousSkuAssignmentRegressionTest,ContinuousProductionStrategyTest,ContinuousProductionTypeBlockRegressionTest,NewSpecProductionStrategyRegressionTest,ScheduleAdjustCarryForwardRegressionTest \
  test
```

Expected: 0 failures、0 errors。

- [ ] **Step 3: 检查 diff**

```bash
git diff --check
git status --short
```

Expected: `git diff --check` 无输出；仅包含本需求文件。

### Task 5: 2026-06-14 真实排程验证

**Files:**
- Verify only

- [ ] **Step 1: 确认 9669 无旧进程并启动当前代码**

```bash
lsof -nP -iTCP:9669 -sTCP:LISTEN
mvn spring-boot:run -pl aps-lh -Dmaven.test.skip=true
```

Expected: 当前工作区应用启动，日志出现 `Started ApsLhApplication`。

- [ ] **Step 2: 调用指定排程日期**

```bash
curl --noproxy '*' --max-time 600 -sS -X POST \
  'http://localhost:9669/lhScheduleResult/execute' \
  -H 'Content-Type: application/json' \
  -d '{"factoryCode":"116","scheduleDate":"2026-06-14"}'
```

Expected: `success=true`，记录新批次号。

- [ ] **Step 3: 四表闭环核对 `3302002637`**

先在 MySQL 会话中取本次最新有效批次，再执行核对：

```sql
SET @verify_batch_no = (
    SELECT BATCH_NO
    FROM T_LH_SCHEDULE_RESULT
    WHERE FACTORY_CODE = '116'
      AND SCHEDULE_DATE = '2026-06-14'
      AND IS_DELETE = 0
    ORDER BY CREATE_TIME DESC, ID DESC
    LIMIT 1
);

SELECT COUNT(*)
FROM T_LH_SCHEDULE_RESULT
WHERE BATCH_NO = @verify_batch_no
  AND MATERIAL_CODE = '3302002637'
  AND IS_DELETE = 0;

SELECT MATERIAL_CODE, UNSCHEDULED_QTY, UNSCHEDULED_REASON
FROM T_LH_UNSCHEDULED_RESULT
WHERE BATCH_NO = @verify_batch_no
  AND MATERIAL_CODE = '3302002637'
  AND IS_DELETE = 0;

SELECT COUNT(*)
FROM T_LH_MOULD_CHANGE_PLAN
WHERE LH_RESULT_BATCH_NO = @verify_batch_no
  AND AFTER_MATERIAL_CODE = '3302002637'
  AND IS_DELETE = 0;

SELECT TITLE, LOG_DETAIL
FROM T_LH_SCHEDULE_PROCESS_LOG
WHERE BATCH_NO = @verify_batch_no
  AND LOG_DETAIL LIKE '%3302002637%'
  AND IS_DELETE = 0;
```

Expected:

- 结果表 0 条；
- 未排表 1 条，数量 0，原因包含 `T～T+2窗口无日计划且无欠产`；
- 换模表 0 条；
- 过程日志存在新增准入拦截记录；
- 日计划滚动台账不再出现 `3302002637` 的 `2026-06-13 dayPlanQty=276`。

- [ ] **Step 4: 停止服务并确认工作区**

```bash
lsof -nP -iTCP:9669 -sTCP:LISTEN
git status --short --branch
```

Expected: 验证服务停止；工作区无临时文件，仅保留已提交需求改动。

### Task 6: 最终中文提交

**Files:**
- All modified production and test files

- [ ] **Step 1: 确认提交历史和工作区**

```bash
git log -5 --oneline
git status --short --branch
```

- [ ] **Step 2: 若 Task 4 后有必要的测试或注释修正，统一提交**

```bash
git add aps-lh-api/src/main/java/com/zlt/aps/lh/api/domain/dto/SkuScheduleDTO.java \
  aps-lh/src/main/java/com/zlt/aps/lh/handler/ScheduleAdjustHandler.java \
  aps-lh/src/main/java/com/zlt/aps/lh/engine/strategy/impl/ContinuousProductionStrategy.java \
  aps-lh/src/test/com/zlt/aps/lh/regression/ContinuousSkuAssignmentRegressionTest.java \
  aps-lh/src/test/com/zlt/aps/lh/engine/strategy/impl/ContinuousProductionStrategyTest.java
git commit -m "测试：补充窗口无计划新增与续作回归验证"
```

仅当存在未提交的本需求修正时执行该提交；禁止生成空提交。
