# 零日计划续作动态收尾状态 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让窗口及月底均无计划的续作动态收尾 SKU 在日计划账本同步阶段保持收尾语义，按硫化余量、日标准产量和模数规则排完，而不是回裁为历史欠产。

**Architecture:** 复用 `SkuTagEnum.ENDING` 作为全链路统一收尾状态，只在现有动态收尾目标量入口补齐标签和收尾剩余天数。账本同步、班次分配和结果收口继续使用现有方法，不新增状态字段或并行判断口径。

**Tech Stack:** Java 8、Spring Boot 2.7、JUnit 5、Maven、MySQL 8

## Global Constraints

- 仅做最小业务改动，不新增依赖、表、Mapper、XML 或配置项。
- 代码注释、日志、文档和提交说明使用简体中文。
- 生产代码修改前必须运行新增测试并确认按预期失败。
- 不使用结果行 `isEnd` 替代 SKU 统一收尾标签。
- 真实验证日期固定为 `2026-06-14`，工厂固定为 `116`。
- 最终工作区不得残留启动日志或其他临时文件。

---

### Task 1: 动态收尾状态回归测试与最小修复

**Files:**
- Modify: `aps-lh/src/test/com/zlt/aps/lh/engine/strategy/impl/ContinuousProductionStrategyTest.java`
- Modify: `aps-lh/src/main/java/com/zlt/aps/lh/engine/strategy/impl/ContinuousProductionStrategy.java`
- Modify: `openspec/specs/daily-standard-shift-plan/spec.md`

**Interfaces:**
- Consumes: `DailyMachineExpansionPlanner#prepareShortageQuota(LhScheduleContext, SkuScheduleDTO, String)`、`ContinuousProductionStrategy#scheduleContinuousEnding(LhScheduleContext)`、`ContinuousProductionStrategy#scheduleReduceMould(LhScheduleContext)`。
- Produces: `applyContinuousNoFutureEndingStrictTarget(SkuScheduleDTO, DailyMachineShortageQuotaPlan)` 完成动态收尾标签、收尾剩余天数和严格目标量的统一同步。

- [ ] **Step 1: 写入失败回归测试**

在 `ContinuousProductionStrategyTest` 增加测试，复用现有 `buildWindowNoPlanContinuousContext()`，构造真实问题口径并执行续作主链：

```java
@Test
public void scheduleReduceMould_shouldKeepDynamicEndingQtyWhenWindowAndFuturePlanEmpty()
        throws Exception {
    ContinuousProductionStrategy strategy = new ContinuousProductionStrategy();
    injectField(strategy, "orderNoGenerator", new OrderNoGenerator());
    injectField(strategy, "targetScheduleQtyResolver", new TargetScheduleQtyResolver());
    injectField(strategy, "endingJudgmentStrategy", new StubEndingJudgmentStrategy());
    LhScheduleContext context = buildWindowNoPlanContinuousContext();
    SkuScheduleDTO sku = context.getContinuousSkuList().get(0);
    sku.setMaterialCode("3302002182");
    sku.setTargetScheduleQty(144);
    sku.setPendingQty(144);
    sku.setSurplusQty(83);
    sku.setEmbryoStock(33);
    sku.setShiftCapacity(18);
    sku.setMonthlyHistoryShortageQty(17);

    strategy.scheduleContinuousEnding(context);
    strategy.scheduleReduceMould(context);

    assertEquals(SkuTagEnum.ENDING.getCode(), sku.getSkuTag(),
            "窗口及月底均无计划的动态收尾必须同步统一收尾标签");
    assertEquals(1, sku.getEndingDaysRemaining(),
            "动态收尾应按当前窗口可完成口径标记剩余一天");
    assertEquals(1, context.getScheduleResultList().size());
    LhScheduleResult result = context.getScheduleResultList().get(0);
    assertEquals(84, ShiftFieldUtil.resolveScheduledQty(result),
            "余量83在双模机台应按既有模数规则排成84，不能回裁为历史欠产17");
    assertEquals("1", result.getIsEnd(), "完整排完动态收尾目标后结果必须标记收尾");
    assertTrue(result.getClass2PlanQty() > 0,
            "动态收尾必须跨多个班次排产，不能只保留C1");
}
```

同时在已有 `scheduleContinuousEnding_shouldStrictlyScheduleShortageWhenWindowNoPlanButFuturePlanExists` 中增加保护断言：

```java
assertTrue(!StringUtils.equals(SkuTagEnum.ENDING.getCode(), sku.getSkuTag()),
        "月底仍有计划的仅补欠产场景不能误标为收尾");
```

- [ ] **Step 2: 运行测试并确认失败**

Run:

```bash
mvn -o -pl aps-lh -am -Dtest='ContinuousProductionStrategyTest#scheduleReduceMould_shouldKeepDynamicEndingQtyWhenWindowAndFuturePlanEmpty+scheduleContinuousEnding_shouldStrictlyScheduleShortageWhenWindowNoPlanButFuturePlanExists' test
```

Expected: 新增测试在 `skuTag` 断言或最终计划量断言处失败；保护测试通过。不得以编译错误作为有效 RED。

- [ ] **Step 3: 实施最小生产代码修复**

在 `applyContinuousNoFutureEndingStrictTarget(...)` 中补齐动态收尾状态，保留现有严格目标量同步：

```java
String originalSkuTag = sku.getSkuTag();
sku.setSkuTag(SkuTagEnum.ENDING.getCode());
if (sku.getEndingDaysRemaining() <= 0) {
    sku.setEndingDaysRemaining(1);
}
int strictTargetQty = ShiftCapacityResolverUtil.roundUpQtyToMouldMultiple(
        Math.max(0, sku.getSurplusQty()), sku.getMouldQty());
sku.setStrictTargetQty(true);
sku.setTargetScheduleQty(strictTargetQty);
sku.setRemainingScheduleQty(strictTargetQty);
sku.setWindowPlanQty(strictTargetQty);
sku.setWindowRemainingPlanQty(strictTargetQty);
log.info("续作窗口及月底均无日计划，按硫化余量严格控量并同步收尾状态, materialCode: {}, "
                + "surplusQty: {}, historyShortageQty: {}, originalSkuTag: {}, endingSkuTag: {}, "
                + "endingDaysRemaining: {}, strictTargetQty: {}",
        sku.getMaterialCode(), Math.max(0, sku.getSurplusQty()),
        Math.max(0, shortageQuotaPlan.getHistoryShortageQty()), originalSkuTag, sku.getSkuTag(),
        sku.getEndingDaysRemaining(), strictTargetQty);
```

不得修改 `applyContinuousBlockToDailyQuota(...)` 的收尾判断依据，不新增物料编码分支。

在两次 `applyDailyStandardPlanQtyToContinuousResults(...)` 后统一调用现有 `capStrictEndingContinuationGroupToTarget(...)`，覆盖单机和多机严格收尾分组，确保日标准产量补足残班后仍回到按模数归整的收尾目标。

- [ ] **Step 4: 更新中文主规格**

在 `openspec/specs/daily-standard-shift-plan/spec.md` 的收尾约束中补充：

```markdown
当续作 SKU 的 T～T+2 日计划均为 0、月底无后续计划且存在历史欠产时，系统 SHALL 将该 SKU 动态标记为收尾，并按硫化余量设置严格目标量。该动态收尾状态 SHALL 贯穿日标准产量修正、日计划账本同步和最终结果标记；日计划账本只能记录收尾补量，不得将结果回裁为历史欠产量。
```

并增加验收场景：日计划 `0,0,0`、历史欠产 17、余量 83、班产 18、双模时，最终应跨多个班次排产 84，不能只排 C1=18；月底仍有计划时仍只补历史欠产且不得标记收尾。

- [ ] **Step 5: 运行定向测试并确认通过**

Run:

```bash
mvn -o -pl aps-lh -am -Dtest='ContinuousProductionStrategyTest#scheduleReduceMould_shouldKeepDynamicEndingQtyWhenWindowAndFuturePlanEmpty+scheduleContinuousEnding_shouldScheduleEndingWhenWindowAndFuturePlanEmptyButHistoryShortageExists+scheduleContinuousEnding_shouldStrictlyScheduleShortageWhenWindowNoPlanButFuturePlanExists,ShiftCapacityResolverUtilTest,TargetScheduleQtyResolverTest' test
```

Expected: `Failures: 0, Errors: 0`，构建成功。

- [ ] **Step 6: 执行编译和静态检查**

Run:

```bash
mvn -o -pl aps-lh -am -DskipTests package
git diff --check
```

Expected: Maven 输出 `BUILD SUCCESS`，`git diff --check` 无输出。

- [ ] **Step 7: 提交代码和规格**

```bash
git add aps-lh/src/test/com/zlt/aps/lh/engine/strategy/impl/ContinuousProductionStrategyTest.java \
  aps-lh/src/main/java/com/zlt/aps/lh/engine/strategy/impl/ContinuousProductionStrategy.java \
  openspec/specs/daily-standard-shift-plan/spec.md
git commit -m "修复：零日计划续作动态收尾被回裁"
```

---

### Task 2: 2026-06-14 真实排程闭环验证

**Files:**
- Verify: `T_LH_SCHEDULE_RESULT`
- Verify: `T_LH_UNSCHEDULED_RESULT`
- Verify: `T_LH_MOULD_CHANGE_PLAN`
- Verify: `T_LH_SCHEDULE_PROCESS_LOG`

**Interfaces:**
- Consumes: `POST /lhScheduleResult/execute`，请求体 `{"factoryCode":"116","scheduleDate":"2026-06-14"}`。
- Produces: 新批次号及物料 `3302002182` 的结果、未排、换模、过程日志证据。

- [ ] **Step 1: 启动当前工作区应用**

```bash
mvn spring-boot:run -pl aps-lh -Dmaven.test.skip=true 2>&1 | tee aps-lh-start.log
```

Expected: `Started ApsLhApplication`，9669 监听进程路径来自当前工作区。

- [ ] **Step 2: 发起指定日期排程**

```bash
curl --noproxy '*' --max-time 600 -sS -X POST \
  'http://localhost:9669/lhScheduleResult/execute' \
  -H 'Content-Type: application/json' \
  -d '{"factoryCode":"116","scheduleDate":"2026-06-14"}'
```

Expected: `success=true` 并返回新批次号。

- [ ] **Step 3: 执行批次级数据库对账**

查询新批次中 `3302002182`：

```sql
SELECT BATCH_NO, LH_MACHINE_CODE, MATERIAL_CODE, SCHEDULE_TYPE, IS_END,
       DAILY_PLAN_QTY, SINGLE_MOULD_SHIFT_QTY, MOULD_SURPLUS_QTY, EMBRYO_STOCK,
       CLASS1_PLAN_QTY, CLASS2_PLAN_QTY, CLASS3_PLAN_QTY, CLASS4_PLAN_QTY,
       CLASS5_PLAN_QTY, CLASS6_PLAN_QTY, CLASS7_PLAN_QTY, CLASS8_PLAN_QTY,
       DAY_N_RANGE, SPEC_END_TIME
FROM T_LH_SCHEDULE_RESULT
WHERE BATCH_NO = ? AND MATERIAL_CODE = '3302002182';
```

Expected: `DAY_N_RANGE=0,0,0`、多个班次为正、总量 84、`IS_END=1`，不再是仅 `C1=18`。

继续核对未排、换模和过程日志，确认没有该 SKU 的异常未排记录，换活字块发生在真实收尾之后，日志包含动态收尾标签同步和完整收尾账本记录。

- [ ] **Step 4: 清理运行环境并检查工作区**

停止当前工作区服务，删除本次生成的 `aps-lh-start.log`，然后运行：

```bash
git status --short
```

Expected: 无临时文件，只有预期提交状态。
