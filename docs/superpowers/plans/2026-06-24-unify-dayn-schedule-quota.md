---
change: unify-dayn-schedule-quota
design-doc: docs/superpowers/specs/2026-06-24-unify-dayn-schedule-quota-design.md
base-ref: e622136f6d674201ef4e296d4ea0f307545c90af
---

# 月计划日计划量 dayN 口径统一实施计划

> **给执行 agent：** 必须按任务使用 `superpowers:subagent-driven-development`（推荐）或 `superpowers:executing-plans` 逐项实施。每个步骤使用复选框跟踪，先写失败测试，再做最小实现，再验证，再提交。

**目标：** 统一 `dayN` 运行期语义，让 `dayN` 只参与准入、节奏判断、加机台、降模和对账日志，不再作为非收尾 SKU 实际排产量硬上限。

**架构：** 保留 `ScheduleAdjustHandler` 初始化的日计划账本和 `DailyMachineExpansionPlanner` 的逐日节奏判断能力，实际排产层由 `TargetScheduleQtyResolver`、新增、续作、换活字块策略按收尾目标量、硫化余量、胎胚库存、欠产追补、晚班不可换模和可用产能决定排产量。提前生产使用 `SkuDailyPlanQuotaUtil.buildShiftedEarlyProductionQuotaMap(...)` 构造临时前移视图，仅供当前轮次准入和加机台模拟，不写回原始账本。

**技术栈：** Java 8、Spring Boot、JUnit 5、Mockito、MyBatis-Plus、OpenSpec、Maven。

---

## 文件结构与职责

**计划中允许修改的业务文件：**

- `aps-lh/src/main/java/com/zlt/aps/lh/handler/ScheduleAdjustHandler.java`：确认 `dailyPlanQuotaMap`、`windowPlanQty`、`windowRemainingPlanQty` 初始化仍只作为账本和节奏输入。
- `aps-lh/src/main/java/com/zlt/aps/lh/component/TargetScheduleQtyResolver.java`：收尾目标量、硫化余量和窗口可排目标统一入口；只允许收尾或仅补历史欠产严格目标同步账本。
- `aps-lh/src/main/java/com/zlt/aps/lh/engine/strategy/support/DailyMachineExpansionPlanner.java`：保留 `dayN` 加机台节奏判断，补充可追溯日志，不输出实际排产硬上限。
- `aps-lh/src/main/java/com/zlt/aps/lh/engine/strategy/impl/ContinuousProductionStrategy.java`：续作降模和重分配不因 `dayN` 浅排，补偿 SKU 只因真实目标缺口产生。
- `aps-lh/src/main/java/com/zlt/aps/lh/engine/strategy/impl/NewSpecProductionStrategy.java`：新增排产目标量、拆机剩余量、提前生产前移视图、日计划账本消费分层。
- `aps-lh/src/main/java/com/zlt/aps/lh/engine/strategy/impl/TypeBlockProductionStrategy.java`：换活字块和回流新增链路不按 `dayN` 截断实际产量。
- `aps-lh/src/main/java/com/zlt/aps/lh/engine/strategy/support/EarlyProductionChecker.java`：提前生产只提前一天，日志补齐原始与前移 `dayN`。
- `aps-lh/src/main/java/com/zlt/aps/lh/util/SkuDailyPlanQuotaUtil.java`：前移视图克隆和账本消费保持只读/对账语义。
- `aps-lh/src/main/java/com/zlt/aps/lh/handler/ResultValidationHandler.java`：日计划完成校验只输出对账日志，不后置截断非收尾排产量。

**计划中需要同步的测试文件：**

- `aps-lh/src/test/java/com/zlt/aps/lh/component/TargetScheduleQtyResolverTest.java`
- `aps-lh/src/test/com/zlt/aps/lh/regression/TargetScheduleQtyResolverRegressionTest.java`
- `aps-lh/src/test/com/zlt/aps/lh/engine/strategy/impl/NewSpecProductionStrategyTest.java`
- `aps-lh/src/test/java/com/zlt/aps/lh/engine/strategy/impl/SchedulingStrategyRegressionTest.java`
- `aps-lh/src/test/com/zlt/aps/lh/engine/strategy/impl/ContinuousProductionStrategyTest.java`
- `aps-lh/src/test/com/zlt/aps/lh/regression/TypeBlockResultSourceSkuRegressionTest.java`
- `aps-lh/src/test/com/zlt/aps/lh/engine/strategy/support/EarlyProductionCheckerTest.java`
- `aps-lh/src/test/com/zlt/aps/lh/regression/SkuDailyPlanQuotaUtilRegressionTest.java`
- `aps-lh/src/test/com/zlt/aps/lh/regression/ResultValidationBlockingTest.java`

**计划中需要同步的规格文件：**

- `openspec/specs/add-machine-rule/spec.md`
- `openspec/specs/continue-reduce-machine-rule/spec.md`
- `openspec/specs/sku-early-production/spec.md`
- `openspec/specs/mould-surplus-calculate/spec.md`
- `openspec/specs/daily-standard-shift-plan/spec.md`

## 影响面结论

1. `ScheduleAdjustHandler` 仍负责从月计划 `day1` 到 `day31` 初始化 `dailyPlanQuotaMap`，并扣减 T 日晚班完成量；本次不改表结构、不改 XML、不重写初始化链。
2. `DailyMachineExpansionPlanner` 的 `isDailyLookAheadCapacitySatisfied(...)`、`resolveFirstDailyLookAheadAddMachineDate(...)` 继续使用 `dayN` 判断是否需要加机台；判断结果不得传导为实际排产量上限。
3. `TargetScheduleQtyResolver.upsizeEndingTargetQty(...)` 必须继续保护收尾目标量：非共用胎胚取 `MAX(硫化余量, 胎胚库存)`，共用胎胚只按硫化余量，零余量进入未排。
4. `NewSpecProductionStrategy.resolveSchedulableRemainingQty(...)` 是新增链路最容易把 `dailyPlanQuotaMap` 回裁为硬上限的位置；普通非收尾应返回目标量剩余，严格目标和收尾仍允许账本同步后的严格上限。
5. `ContinuousProductionStrategy` 的降模和补偿回流要区分“节奏缺口”和“目标量缺口”，不能只因账本剩余额度生成补偿 SKU。
6. `TypeBlockProductionStrategy` 既有 `resolveTypeBlockTargetQty(...)` 中的 `windowPlanQty` 返回逻辑需要纳入同一语义，避免换活字块释放后又按 `dayN` 浅排。
7. `ResultValidationHandler` 中涉及 `windowPlanQty` 的校验目标只能用于日志和对账；不得回写或截断非收尾结果。

## Task 1：收尾目标量和普通非收尾目标量分层

**Files:**

- Modify: `aps-lh/src/main/java/com/zlt/aps/lh/component/TargetScheduleQtyResolver.java`
- Modify: `aps-lh/src/main/java/com/zlt/aps/lh/engine/strategy/impl/NewSpecProductionStrategy.java`
- Test: `aps-lh/src/test/java/com/zlt/aps/lh/component/TargetScheduleQtyResolverTest.java`
- Test: `aps-lh/src/test/com/zlt/aps/lh/regression/TargetScheduleQtyResolverRegressionTest.java`
- Test: `aps-lh/src/test/com/zlt/aps/lh/engine/strategy/impl/NewSpecProductionStrategyTest.java`

- [ ] **Step 1：写失败测试，锁定普通非收尾不被账本剩余额度硬截断**

在 `NewSpecProductionStrategyTest` 追加测试方法。该用例与既有 `shouldUseDailyQuotaAsSchedulableRemainingQtyWhenTargetIsLarger` 形成新口径对照：旧用例应同步改名并改断言，普通非收尾不再返回 `windowPlanQty`。

```java
/**
 * 用例说明：普通非收尾新增 SKU 的实际可排剩余量不应被 dayN 或日计划账本剩余额度硬截断。
 *
 * @throws Exception 反射调用异常
 */
@Test
public void shouldUseTargetQtyForNonEndingWhenDailyQuotaIsSmaller() throws Exception {
    NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
    SkuScheduleDTO sku = new SkuScheduleDTO();
    sku.setMaterialCode("3302001724");
    sku.setTargetScheduleQty(320);
    sku.setRemainingScheduleQty(320);
    sku.setWindowPlanQty(46);
    sku.setWindowRemainingPlanQty(46);
    Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap = new LinkedHashMap<>(4);
    quotaMap.put(LocalDate.of(2026, 6, 14), buildQuota(14));
    quotaMap.put(LocalDate.of(2026, 6, 15), buildQuota(16));
    quotaMap.put(LocalDate.of(2026, 6, 16), buildQuota(16));
    sku.setDailyPlanQuotaMap(quotaMap);

    Method method = NewSpecProductionStrategy.class.getDeclaredMethod(
            "resolveSchedulableRemainingQty", SkuScheduleDTO.class);
    method.setAccessible(true);

    Integer remainingQty = (Integer) method.invoke(strategy, sku);

    Assertions.assertEquals(320, remainingQty.intValue(),
            "普通非收尾 SKU 应按目标量和资源约束继续排产，dayN 只保留账本对账能力");
}
```

在 `TargetScheduleQtyResolverTest` 追加共用胎胚收尾零余量测试，复用该类已有的 `buildSku(...)` 和 `refreshActiveEmbryoSkuMap(...)` 写法。

```java
@Test
void upsizeEndingTargetQty_shouldNotUpsizeSharedEmbryoEndingByDayN() {
    LhScheduleContext context = new LhScheduleContext();
    SkuScheduleDTO zeroSurplusSku = buildSku("3302002369", "EMB-01", 0, 120, 96);
    SkuScheduleDTO anotherSku = buildSku("3302002370", "EMB-01", 8, 120, 96);
    zeroSurplusSku.setWindowPlanQty(96);
    zeroSurplusSku.setWindowRemainingPlanQty(96);
    context.setNewSpecSkuList(Arrays.asList(zeroSurplusSku, anotherSku));
    context.getMaterialSharedEmbryoMap().put("3302002369", true);
    context.getMaterialSharedEmbryoMap().put("3302002370", true);
    resolver.refreshActiveEmbryoSkuMap(context);

    int targetQty = resolver.upsizeEndingTargetQty(context, zeroSurplusSku);

    assertEquals(0, targetQty, "共用胎胚收尾目标量只按硫化余量，不能被 dayN 或胎胚库存抬高");
    assertEquals(0, zeroSurplusSku.resolveTargetScheduleQty());
    assertFalse(resolver.isSharedEmbryoInWindow(context, zeroSurplusSku));
    assertEquals(Collections.singletonList("3302002370"),
            context.getActiveEmbryoSkuMap().get("EMB-01"));
}
```

- [ ] **Step 2：运行失败测试**

Run:

```bash
mvn -pl aps-lh -Dtest=NewSpecProductionStrategyTest#shouldUseTargetQtyForNonEndingWhenDailyQuotaIsSmaller,TargetScheduleQtyResolverTest#upsizeEndingTargetQty_shouldNotUpsizeSharedEmbryoEndingByDayN test
```

Expected: `shouldUseTargetQtyForNonEndingWhenDailyQuotaIsSmaller` 失败，实际值仍为 `46` 或窗口剩余额度；共用胎胚测试保持通过或暴露 `dayN` 抬高零余量收尾目标的问题。

- [ ] **Step 3：最小实现**

在 `NewSpecProductionStrategy.resolveSchedulableRemainingQty(SkuScheduleDTO sku)` 内拆分普通非收尾和严格目标场景。实现方向：

```java
private int resolveSchedulableRemainingQty(SkuScheduleDTO sku) {
    if (Objects.isNull(sku)) {
        return 0;
    }
    int targetQty = Math.max(0, sku.resolveTargetScheduleQty());
    if (!shouldUseDailyQuotaAsHardLimit(sku)) {
        return targetQty;
    }
    int remainingQuotaQty = SkuDailyPlanQuotaUtil.sumRemainingQty(sku.getDailyPlanQuotaMap());
    if (remainingQuotaQty > 0) {
        int windowRemainingQty = resolveWindowRemainingQty(sku);
        return Math.min(targetQty, Math.min(remainingQuotaQty, windowRemainingQty));
    }
    return targetQty;
}

private boolean shouldUseDailyQuotaAsHardLimit(SkuScheduleDTO sku) {
    if (Objects.isNull(sku)) {
        return false;
    }
    return sku.isStrictTargetQty()
            || SkuTagEnum.ENDING.getCode().equals(sku.getSkuTag())
            || sku.isStrictNewSpecShortageOnly();
}
```

在 `TargetScheduleQtyResolver.upsizeEndingTargetQty(...)` 保持已有收尾账本同步，仅允许收尾目标量同步到 `windowPlanQty`、`windowRemainingPlanQty` 和首日日计划账本。不要把普通非收尾账本同步为硬上限。

- [ ] **Step 4：运行验证命令**

Run:

```bash
mvn -pl aps-lh -Dtest=NewSpecProductionStrategyTest#shouldUseTargetQtyForNonEndingWhenDailyQuotaIsSmaller,NewSpecProductionStrategyTest#shouldSyncEndingQuotaWhenSingleEmbryoTargetUpsizedByEmbryoStock,TargetScheduleQtyResolverRegressionTest#upsizeEndingTargetQty_shouldIgnoreWindowPlanQtyForEndingSku,TargetScheduleQtyResolverRegressionTest#upsizeEndingTargetQty_shouldNotReduceTargetByWindowCapacity,TargetScheduleQtyResolverTest#upsizeEndingTargetQty_shouldNotUpsizeSharedEmbryoEndingByDayN test
```

Expected: 全部 `PASS`。

- [ ] **Step 5：提交**

```bash
git add aps-lh/src/main/java/com/zlt/aps/lh/component/TargetScheduleQtyResolver.java aps-lh/src/main/java/com/zlt/aps/lh/engine/strategy/impl/NewSpecProductionStrategy.java aps-lh/src/test/java/com/zlt/aps/lh/component/TargetScheduleQtyResolverTest.java aps-lh/src/test/com/zlt/aps/lh/regression/TargetScheduleQtyResolverRegressionTest.java aps-lh/src/test/com/zlt/aps/lh/engine/strategy/impl/NewSpecProductionStrategyTest.java
git commit -m "统一非收尾排产目标量口径"
```

## Task 2：加机台 dayN 只保留节奏判断

**Files:**

- Modify: `aps-lh/src/main/java/com/zlt/aps/lh/engine/strategy/support/DailyMachineExpansionPlanner.java`
- Modify: `aps-lh/src/main/java/com/zlt/aps/lh/engine/strategy/impl/NewSpecProductionStrategy.java`
- Test: `aps-lh/src/test/java/com/zlt/aps/lh/engine/strategy/impl/SchedulingStrategyRegressionTest.java`
- Test: `aps-lh/src/test/com/zlt/aps/lh/engine/strategy/impl/NewSpecProductionStrategyTest.java`

- [ ] **Step 1：写失败测试，当前机台满足 T 日节奏时不继续加机台**

在 `SchedulingStrategyRegressionTest` 追加：

```java
/**
 * 非收尾新增当前机台已满足T日dayN节奏时，不应继续因为T+1/T+2计划提前加机台。
 */
@Test
public void shouldNotAddMachineWhenCurrentMachineSatisfiesCurrentDayPlan() {
    LhScheduleContext context = buildContinuousReduceContext();
    List<LhShiftConfigVO> shifts = context.getScheduleWindowShifts();
    SkuScheduleDTO sku = buildContinuousSku("3302001724", 16, 400,
            buildQuotaMapByShifts(shifts, 32, 160, 160));
    sku.setMonthlyHistoryShortageQty(0);
    sku.setWindowPlanQty(352);

    boolean satisfied = DailyMachineExpansionPlanner.isDailyLookAheadCapacitySatisfied(
            context, sku, 1, ScheduleTypeEnum.NEW.getCode());
    LocalDate addMachineDate = DailyMachineExpansionPlanner.resolveFirstDailyLookAheadAddMachineDate(
            context, sku, 1, ScheduleTypeEnum.NEW.getCode());

    Assertions.assertTrue(satisfied, "一台机台两班产能已覆盖T日dayN时，不应提前后看T+1加机台");
    Assertions.assertNull(addMachineDate);
}
```

在 `NewSpecProductionStrategyTest` 追加：

```java
/**
 * 用例说明：dayN加机台判断通过后，实际新增排产剩余量仍使用非收尾目标量。
 *
 * @throws Exception 反射调用异常
 */
@Test
public void shouldKeepNonEndingTargetAfterDailyLookAheadSatisfied() throws Exception {
    NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
    SkuScheduleDTO sku = new SkuScheduleDTO();
    sku.setMaterialCode("3302001724");
    sku.setTargetScheduleQty(256);
    sku.setRemainingScheduleQty(256);
    sku.setWindowPlanQty(32);
    Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap = new LinkedHashMap<>(4);
    quotaMap.put(LocalDate.of(2026, 6, 14), buildQuota(32));
    quotaMap.put(LocalDate.of(2026, 6, 15), buildQuota(160));
    sku.setDailyPlanQuotaMap(quotaMap);

    Method method = NewSpecProductionStrategy.class.getDeclaredMethod(
            "resolveSchedulableRemainingQty", SkuScheduleDTO.class);
    method.setAccessible(true);

    Integer remainingQty = (Integer) method.invoke(strategy, sku);

    Assertions.assertEquals(256, remainingQty.intValue());
}
```

- [ ] **Step 2：运行失败测试**

Run:

```bash
mvn -pl aps-lh -Dtest=SchedulingStrategyRegressionTest#shouldNotAddMachineWhenCurrentMachineSatisfiesCurrentDayPlan,NewSpecProductionStrategyTest#shouldKeepNonEndingTargetAfterDailyLookAheadSatisfied test
```

Expected: 至少一个测试失败，常见失败为 `addMachineDate` 返回 T+1 或新增剩余量被账本回裁。

- [ ] **Step 3：最小实现**

在 `DailyMachineExpansionPlanner.resolveFirstDailyLookAheadAddMachineDate(...)` 的逐日判断中，先判断当前业务日 `dayN` 是否已由当前机台数覆盖；如果 T 日已覆盖，直接返回 `null`，并输出中文日志：

```java
log.info("dayN加机台节奏判断, factoryCode: {}, materialCode: {}, workDate: {}, activeMachineCount: {}, "
                + "dayPlanQty: {}, availableCapacityQty: {}, satisfied: {}, reason: {}",
        context.getFactoryCode(), sku.getMaterialCode(), currentDate, activeMachineCount,
        dayPlanQty, availableCapacityQty, true, "当前机台已满足T日节奏，不继续提前后看加机台");
```

保持 `NewSpecProductionStrategy` 只读取该判断作为“是否进入新增机台规则”，不得把返回日期或窗口计划量写成实际排产上限。

- [ ] **Step 4：运行验证命令**

Run:

```bash
mvn -pl aps-lh -Dtest=SchedulingStrategyRegressionTest#shouldNotAddMachineWhenCurrentMachineSatisfiesCurrentDayPlan,SchedulingStrategyRegressionTest#shouldStillRequireContinuousCompensationWhenSecondDayPlanNotCovered,NewSpecProductionStrategyTest#shouldKeepNonEndingTargetAfterDailyLookAheadSatisfied test
```

Expected: 全部 `PASS`。

- [ ] **Step 5：提交**

```bash
git add aps-lh/src/main/java/com/zlt/aps/lh/engine/strategy/support/DailyMachineExpansionPlanner.java aps-lh/src/main/java/com/zlt/aps/lh/engine/strategy/impl/NewSpecProductionStrategy.java aps-lh/src/test/java/com/zlt/aps/lh/engine/strategy/impl/SchedulingStrategyRegressionTest.java aps-lh/src/test/com/zlt/aps/lh/engine/strategy/impl/NewSpecProductionStrategyTest.java
git commit -m "保留dayN加机台节奏判断"
```

## Task 3：续作降模和补偿回流区分节奏缺口与目标缺口

**Files:**

- Modify: `aps-lh/src/main/java/com/zlt/aps/lh/engine/strategy/impl/ContinuousProductionStrategy.java`
- Modify: `aps-lh/src/main/java/com/zlt/aps/lh/engine/strategy/support/DailyMachineExpansionPlanner.java`
- Test: `aps-lh/src/test/com/zlt/aps/lh/engine/strategy/impl/ContinuousProductionStrategyTest.java`
- Test: `aps-lh/src/test/java/com/zlt/aps/lh/engine/strategy/impl/SchedulingStrategyRegressionTest.java`

- [ ] **Step 1：写失败测试，保留机台满足 T 日节奏后停止继续减机台**

在 `ContinuousProductionStrategyTest` 追加：

```java
/**
 * 非收尾续作降模后，保留机台满足T日dayN节奏即可停止继续减机台，重分配仍补满保留机台可用产能。
 *
 * @throws Exception 反射调用异常
 */
@Test
public void shouldStopReduceMachineWhenRemainingMachinesSatisfyCurrentDayPlan() throws Exception {
    ContinuousProductionStrategy strategy = new ContinuousProductionStrategy();
    LhScheduleContext context = buildContinuousReduceContext();
    List<LhShiftConfigVO> shifts = context.getScheduleWindowShifts();
    SkuScheduleDTO sku = buildContinuousSku("3302001075", 16, 320,
            buildQuotaMapByShifts(shifts, 32, 32, 32));
    sku.setWindowPlanQty(96);
    sku.setMonthlyHistoryShortageQty(0);

    Method method = ContinuousProductionStrategy.class.getDeclaredMethod(
            "shouldReduceContinuationByWorkDate", SkuScheduleDTO.class, List.class, List.class);
    method.setAccessible(true);
    Boolean shouldReduce = (Boolean) method.invoke(strategy, sku,
            buildTwoMachineContinuousResults(sku, shifts), shifts);

    Assertions.assertTrue(shouldReduce, "两台续作冗余时应进入降模判断");
}
```

在 `SchedulingStrategyRegressionTest` 追加节奏判断日志/结果用例：

```java
@Test
public void shouldKeepContinuousCapacityAfterReduceWhenDayNIsSmall() {
    LhScheduleContext context = buildContinuousReduceContext();
    List<LhShiftConfigVO> shifts = context.getScheduleWindowShifts();
    SkuScheduleDTO sku = buildContinuousSku("3302001075", 16, 320,
            buildQuotaMapByShifts(shifts, 16, 16, 16));
    sku.setWindowPlanQty(48);

    boolean satisfied = DailyMachineExpansionPlanner.isDailyLookAheadCapacitySatisfied(
            context, sku, 1, ScheduleTypeEnum.CONTINUOUS.getCode());

    Assertions.assertTrue(satisfied, "dayN只说明一台机台满足节奏，不代表续作实际排产只能排48");
    Assertions.assertEquals(320, sku.resolveTargetScheduleQty());
}
```

- [ ] **Step 2：运行失败测试**

Run:

```bash
mvn -pl aps-lh -Dtest=ContinuousProductionStrategyTest#shouldStopReduceMachineWhenRemainingMachinesSatisfyCurrentDayPlan,SchedulingStrategyRegressionTest#shouldKeepContinuousCapacityAfterReduceWhenDayNIsSmall test
```

Expected: 若测试辅助方法不存在，先在同一测试类内补私有构造方法；业务断言应暴露降模后仍被 `windowPlanQty` 或账本剩余量浅排的问题。

- [ ] **Step 3：最小实现**

在 `ContinuousProductionStrategy` 中：

1. `shouldReduceContinuationByWorkDate(...)` 保留 `dayN` 只判断“最小保留机台数”；
2. 重分配结果时以 `sourceSku.resolveTargetScheduleQty()`、硫化余量、可用班次产能和晚班不可换模规则作为实际目标；
3. `appendDeferredContinuousCompensationSku(...)` 或同类补偿入口只在实际目标缺口大于 0 时生成补偿 SKU。

关键注释写入降模判断处：

```java
// dayN 仅用于判断保留机台是否满足生产节奏，降模后的实际排产量仍按续作目标量和资源约束重分配。
```

关键日志写入降模停止处：

```java
log.info("续作降模dayN节奏判断, factoryCode: {}, materialCode: {}, currentMachineCount: {}, "
                + "keepMachineCount: {}, currentDayPlanQty: {}, lookAheadPlanQty: {}, reduce: {}, reason: {}",
        context.getFactoryCode(), sourceSku.getMaterialCode(), currentMachineCount,
        keepMachineCount, currentDayPlanQty, lookAheadPlanQty, false,
        "保留机台已满足T日节奏，停止继续降模");
```

- [ ] **Step 4：运行验证命令**

Run:

```bash
mvn -pl aps-lh -Dtest=ContinuousProductionStrategyTest#shouldStopReduceMachineWhenRemainingMachinesSatisfyCurrentDayPlan,ContinuousProductionStrategyTest#shouldStartNonEndingContinuousFromFirstShiftWhenFirstDayHasPlan,SchedulingStrategyRegressionTest#shouldStillRequireContinuousCompensationWhenSecondDayPlanNotCovered,SchedulingStrategyRegressionTest#shouldKeepContinuousCapacityAfterReduceWhenDayNIsSmall test
```

Expected: 全部 `PASS`。

- [ ] **Step 5：提交**

```bash
git add aps-lh/src/main/java/com/zlt/aps/lh/engine/strategy/impl/ContinuousProductionStrategy.java aps-lh/src/main/java/com/zlt/aps/lh/engine/strategy/support/DailyMachineExpansionPlanner.java aps-lh/src/test/com/zlt/aps/lh/engine/strategy/impl/ContinuousProductionStrategyTest.java aps-lh/src/test/java/com/zlt/aps/lh/engine/strategy/impl/SchedulingStrategyRegressionTest.java
git commit -m "修正续作降模dayN节奏口径"
```

## Task 4：提前生产前移视图只用于准入和节奏

**Files:**

- Modify: `aps-lh/src/main/java/com/zlt/aps/lh/engine/strategy/support/EarlyProductionChecker.java`
- Modify: `aps-lh/src/main/java/com/zlt/aps/lh/util/SkuDailyPlanQuotaUtil.java`
- Modify: `aps-lh/src/main/java/com/zlt/aps/lh/engine/strategy/impl/NewSpecProductionStrategy.java`
- Test: `aps-lh/src/test/com/zlt/aps/lh/engine/strategy/support/EarlyProductionCheckerTest.java`
- Test: `aps-lh/src/test/com/zlt/aps/lh/regression/SkuDailyPlanQuotaUtilRegressionTest.java`
- Test: `aps-lh/src/test/com/zlt/aps/lh/engine/strategy/impl/NewSpecProductionStrategyTest.java`

- [ ] **Step 1：写失败测试，T+2 不得提前到 T 日，前移后不截断实际排产量**

`EarlyProductionCheckerTest` 已有 T+2 拒绝用例，追加结果对象来源日断言：

```java
@Test
void checkEarlyProduction_shouldUseOnlyNextDayAsFuturePlanDate() {
    LocalDate day1 = LocalDate.of(2026, 6, 14);
    LocalDate day2 = LocalDate.of(2026, 6, 15);
    LocalDate day3 = LocalDate.of(2026, 6, 16);
    LhScheduleContext context = contextWithStructurePlan(day1, "L1", 2);
    SkuScheduleDTO sku = sku("3302001724", "L1", 100, 40,
            quotaMap(day1, day2, day3, 0, 46, 120));

    EarlyProductionDecision decision = EarlyProductionChecker.checkEarlyProduction(
            context, sku, day1, day1, day3, 200);

    assertTrue(decision.isAllowed());
    assertEquals(day2, decision.getFuturePlanDate(), "提前生产来源日只能是下一业务日");
}
```

`NewSpecProductionStrategyTest` 追加：

```java
@Test
public void shouldNotCapEarlyProductionActualQtyByShiftedDayN() throws Exception {
    NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
    SkuScheduleDTO sku = new SkuScheduleDTO();
    sku.setMaterialCode("3302001889");
    sku.setTargetScheduleQty(192);
    sku.setRemainingScheduleQty(192);
    sku.setWindowPlanQty(0);
    Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap = new LinkedHashMap<>(4);
    quotaMap.put(LocalDate.of(2026, 6, 14), buildQuota(0));
    quotaMap.put(LocalDate.of(2026, 6, 15), buildQuota(46));
    quotaMap.put(LocalDate.of(2026, 6, 16), buildQuota(46));
    quotaMap.put(LocalDate.of(2026, 6, 17), buildQuota(46));
    sku.setDailyPlanQuotaMap(quotaMap);

    Method method = NewSpecProductionStrategy.class.getDeclaredMethod(
            "resolveSchedulableRemainingQty", SkuScheduleDTO.class);
    method.setAccessible(true);
    Integer remainingQty = (Integer) method.invoke(strategy, sku);

    Assertions.assertEquals(192, remainingQty.intValue(),
            "提前生产前移dayN只用于加机台节奏判断，不得截断非收尾实际排产量");
}
```

- [ ] **Step 2：运行失败测试**

Run:

```bash
mvn -pl aps-lh -Dtest=EarlyProductionCheckerTest#checkEarlyProduction_shouldUseOnlyNextDayAsFuturePlanDate,SkuDailyPlanQuotaUtilRegressionTest#buildShiftedEarlyProductionQuotaMap_shouldMoveNextDayPlansWithoutMutatingSource,NewSpecProductionStrategyTest#shouldNotCapEarlyProductionActualQtyByShiftedDayN test
```

Expected: `NewSpecProductionStrategyTest` 在实现前失败或返回前移 `dayN` 合计值。

- [ ] **Step 3：最小实现**

保持 `EarlyProductionChecker.resolveFirstFuturePlanDate(...)` 只读取 `currentDate.plusDays(1)`。在 `NewSpecProductionStrategy.resolveEarlyProductionDecision(...)` 后，只把 `buildShiftedEarlyProductionQuotaMap(...)` 的结果传入加机台模拟或节奏判断，不替换 `sku.getDailyPlanQuotaMap()` 原对象。

允许写入局部变量：

```java
Map<LocalDate, SkuDailyPlanQuotaDTO> simulationQuotaMap =
        earlyProductionDecision.isEarlyProduction()
                ? SkuDailyPlanQuotaUtil.buildShiftedEarlyProductionQuotaMap(
                        sku.getDailyPlanQuotaMap(), productionDate, resolveScheduleTargetLocalDate(context))
                : sku.getDailyPlanQuotaMap();
```

禁止写入：

```java
sku.setDailyPlanQuotaMap(simulationQuotaMap);
```

补充日志：

```java
log.info("提前生产dayN前移判断, factoryCode: {}, materialCode: {}, currentDate: {}, futurePlanDate: {}, "
                + "originCurrentDayQty: {}, shiftedCurrentDayQty: {}, allowed: {}, actualTargetQty: {}",
        context.getFactoryCode(), sku.getMaterialCode(), productionDate,
        decision.getFuturePlanDate(), originCurrentDayQty, shiftedCurrentDayQty,
        decision.isAllowed(), sku.resolveTargetScheduleQty());
```

- [ ] **Step 4：运行验证命令**

Run:

```bash
mvn -pl aps-lh -Dtest=EarlyProductionCheckerTest,SkuDailyPlanQuotaUtilRegressionTest,NewSpecProductionStrategyTest#shouldNotCapEarlyProductionActualQtyByShiftedDayN test
```

Expected: 全部 `PASS`。

- [ ] **Step 5：提交**

```bash
git add aps-lh/src/main/java/com/zlt/aps/lh/engine/strategy/support/EarlyProductionChecker.java aps-lh/src/main/java/com/zlt/aps/lh/util/SkuDailyPlanQuotaUtil.java aps-lh/src/main/java/com/zlt/aps/lh/engine/strategy/impl/NewSpecProductionStrategy.java aps-lh/src/test/com/zlt/aps/lh/engine/strategy/support/EarlyProductionCheckerTest.java aps-lh/src/test/com/zlt/aps/lh/regression/SkuDailyPlanQuotaUtilRegressionTest.java aps-lh/src/test/com/zlt/aps/lh/engine/strategy/impl/NewSpecProductionStrategyTest.java
git commit -m "限制提前生产dayN前移作用域"
```

## Task 5：换活字块实际排产量不按 dayN 截断

**Files:**

- Modify: `aps-lh/src/main/java/com/zlt/aps/lh/engine/strategy/impl/TypeBlockProductionStrategy.java`
- Modify: `aps-lh/src/main/java/com/zlt/aps/lh/engine/strategy/impl/NewSpecProductionStrategy.java`
- Test: `aps-lh/src/test/com/zlt/aps/lh/regression/TypeBlockResultSourceSkuRegressionTest.java`

- [ ] **Step 1：写失败测试，换活字块目标量大于 dayN 时仍按资源约束排**

在 `TypeBlockResultSourceSkuRegressionTest` 追加：

```java
@Test
void scheduleTypeBlockChange_shouldNotCapNonEndingQtyByWindowPlanQty() {
    LhScheduleContext context = newContext();
    context.getMachineScheduleMap().put("M1", buildMachine("M1", "MAT-C1"));
    context.getContinuousSkuList().add(buildContinuousSku(
            "MAT-C1", "M1", "EMB-1", "STRUCT-A", "SPEC-A", "PAT-A", 1));
    SkuScheduleDTO typeBlockSku = buildNewSku("MAT-T1", "EMB-1", "STRUCT-B", "SPEC-A", "PAT-B", 96);
    typeBlockSku.setWindowPlanQty(16);
    typeBlockSku.setWindowRemainingPlanQty(16);
    context.getNewSpecSkuList().add(typeBlockSku);
    putMouldRel(context, "MAT-C1", "MOULD-1");
    putMouldRel(context, "MAT-T1", "MOULD-1");

    when(orderNoGenerator.generateOrderNo(any())).thenReturn("ORD-1", "ORD-2");
    when(endingJudgmentStrategy.isEnding(any(), any())).thenReturn(false);

    continuousProductionStrategy.scheduleContinuousEnding(context);
    typeBlockProductionStrategy.scheduleTypeBlockChange(context);

    int typeBlockQty = context.getScheduleResultList().stream()
            .filter(result -> "1".equals(result.getIsTypeBlock()))
            .filter(result -> "MAT-T1".equals(result.getMaterialCode()))
            .mapToInt(LhScheduleResult::getPlanQty)
            .sum();
    assertEquals(96, typeBlockQty, "换活字块非收尾实际排产量不应被窗口dayN截断为16");
}
```

- [ ] **Step 2：运行失败测试**

Run:

```bash
mvn -pl aps-lh -Dtest=TypeBlockResultSourceSkuRegressionTest#scheduleTypeBlockChange_shouldNotCapNonEndingQtyByWindowPlanQty test
```

Expected: 当前结果小于 `96` 或被 `windowPlanQty` 截断时失败。

- [ ] **Step 3：最小实现**

在 `TypeBlockProductionStrategy` 的目标量解析处，把普通非收尾从 `windowPlanQty` 改为 `sku.resolveTargetScheduleQty()` 和实际可用产能共同收敛。保留收尾严格目标、共用胎胚零余量、模具、活字块、机台和晚班不可换模限制。

将类似逻辑收口为私有方法：

```java
private int resolveTypeBlockActualTargetQty(SkuScheduleDTO sku, boolean ending) {
    if (Objects.isNull(sku)) {
        return 0;
    }
    if (ending || sku.isStrictTargetQty()) {
        return Math.max(0, sku.resolveTargetScheduleQty());
    }
    return Math.max(0, sku.resolveTargetScheduleQty());
}
```

在调用点继续用现有可用班次产能取 `Math.min(actualTargetQty, availableCapacityQty)`，不得新增无业务依据 fallback。

- [ ] **Step 4：运行验证命令**

Run:

```bash
mvn -pl aps-lh -Dtest=TypeBlockResultSourceSkuRegressionTest,NewSpecProductionStrategyTest#shouldUseTargetQtyForNonEndingWhenDailyQuotaIsSmaller test
```

Expected: 全部 `PASS`。

- [ ] **Step 5：提交**

```bash
git add aps-lh/src/main/java/com/zlt/aps/lh/engine/strategy/impl/TypeBlockProductionStrategy.java aps-lh/src/main/java/com/zlt/aps/lh/engine/strategy/impl/NewSpecProductionStrategy.java aps-lh/src/test/com/zlt/aps/lh/regression/TypeBlockResultSourceSkuRegressionTest.java
git commit -m "修正换活字块非收尾排产量口径"
```

## Task 6：ResultValidationHandler 只做对账不做非收尾截断

**Files:**

- Modify: `aps-lh/src/main/java/com/zlt/aps/lh/handler/ResultValidationHandler.java`
- Test: `aps-lh/src/test/com/zlt/aps/lh/regression/ResultValidationBlockingTest.java`

- [ ] **Step 1：写失败测试，日计划完成校验不得改写非收尾结果**

在 `ResultValidationBlockingTest` 追加：

```java
@Test
void validateResult_shouldKeepNonEndingResultWhenActualQtyExceedsDayN() throws Exception {
    LhScheduleContext context = new LhScheduleContext();
    context.setFactoryCode("116");
    context.setBatchNo("LHPC20260614001");
    SkuScheduleDTO sku = new SkuScheduleDTO();
    sku.setMaterialCode("3302001724");
    sku.setWindowPlanQty(46);
    sku.setTargetScheduleQty(160);
    sku.setRemainingScheduleQty(160);
    context.getScheduleSkuMap().put(sku.getMaterialCode(), sku);
    LhScheduleResult result = new LhScheduleResult();
    result.setMaterialCode("3302001724");
    result.setPlanQty(160);
    result.setScheduleType("02");
    context.getScheduleResultList().add(result);

    ResultValidationHandler handler = new ResultValidationHandler();
    invokeValidateScheduleResult(handler, context);

    assertEquals(160, result.getPlanQty(), "日计划完成校验只能对账，不能截断非收尾实际排产量");
}
```

测试类中如已有反射入口，复用现有私有方法；没有时新增：

```java
private void invokeValidateScheduleResult(ResultValidationHandler handler, LhScheduleContext context)
        throws Exception {
    Method method = ResultValidationHandler.class.getDeclaredMethod("validateScheduleResult", LhScheduleContext.class);
    method.setAccessible(true);
    method.invoke(handler, context);
}
```

- [ ] **Step 2：运行失败测试**

Run:

```bash
mvn -pl aps-lh -Dtest=ResultValidationBlockingTest#validateResult_shouldKeepNonEndingResultWhenActualQtyExceedsDayN test
```

Expected: 若当前校验会截断或抛出阻断异常，测试失败。

- [ ] **Step 3：最小实现**

在 `ResultValidationHandler` 中找到使用 `windowPlanQty` 或 `targetQty` 构造硬上限的位置。调整为：

```java
private int resolveDailyPlanCheckQty(SkuScheduleDTO sku, int targetQty) {
    if (Objects.isNull(sku)) {
        return Math.max(0, targetQty);
    }
    int windowPlanQty = Math.max(0, sku.getWindowPlanQty());
    if (windowPlanQty > 0) {
        log.info("日计划完成对账, materialCode: {}, windowPlanQty: {}, targetQty: {}, checkOnly: {}",
                sku.getMaterialCode(), windowPlanQty, targetQty, true);
    }
    return Math.max(0, targetQty);
}
```

不要新增吞异常逻辑；原有阻断类校验如果针对模具、左右模、停机、清洗等工艺约束，保持不变。

- [ ] **Step 4：运行验证命令**

Run:

```bash
mvn -pl aps-lh -Dtest=ResultValidationBlockingTest,ResultValidationCompletedEventRegressionTest,ResultValidationHandlerLeftRightMouldRegressionTest test
```

Expected: 全部 `PASS`。

- [ ] **Step 5：提交**

```bash
git add aps-lh/src/main/java/com/zlt/aps/lh/handler/ResultValidationHandler.java aps-lh/src/test/com/zlt/aps/lh/regression/ResultValidationBlockingTest.java
git commit -m "保持日计划校验仅用于对账"
```

## Task 7：ScheduleAdjustHandler 入口口径和日志核对

**Files:**

- Modify: `aps-lh/src/main/java/com/zlt/aps/lh/handler/ScheduleAdjustHandler.java`
- Test: `aps-lh/src/test/com/zlt/aps/lh/regression/ScheduleAdjustCarryForwardRegressionTest.java`
- Test: `aps-lh/src/test/java/com/zlt/aps/lh/handler/ScheduleAdjustHandlerTest.java`

- [ ] **Step 1：写失败测试，入口账本仍保留 dayN 原值并扣减 T 日晚班完成量**

在 `ScheduleAdjustCarryForwardRegressionTest` 追加日志或状态断言：

```java
@Test
void gatherSku_shouldKeepDailyQuotaAsLedgerAfterScheduleDayFinishDeducted() {
    ScheduleAdjustHandler handler = new ScheduleAdjustHandler();
    LhScheduleContext context = buildContextWithScheduleDayFinishQty("3302001724", 16);
    SkuScheduleDTO sku = buildMonthPlanSku("3302001724", 48, 48, 48);

    invokeGatherSku(handler, context, sku);

    SkuScheduleDTO gatheredSku = context.getScheduleSkuMap().get("3302001724");
    assertEquals(32, gatheredSku.getDailyPlanQuotaMap().values().iterator().next().getRemainingQty(),
            "T日晚班完成量只扣减账本剩余额度，不改变后续非收尾实际排产目标语义");
    assertEquals(144, gatheredSku.getWindowPlanQty());
}
```

- [ ] **Step 2：运行失败测试**

Run:

```bash
mvn -pl aps-lh -Dtest=ScheduleAdjustCarryForwardRegressionTest#gatherSku_shouldKeepDailyQuotaAsLedgerAfterScheduleDayFinishDeducted,ScheduleAdjustHandlerTest test
```

Expected: 如果测试辅助方法不存在，先在测试类内按已有构造方式补齐；业务断言必须证明入口保留账本、扣减和日志口径。

- [ ] **Step 3：最小实现**

`ScheduleAdjustHandler` 原则上不改计算公式；只在已有初始化日志中补充语义字段：

```java
log.info("SKU日计划账本初始化, factoryCode: {}, materialCode: {}, scheduleDate: {}, "
                + "windowPlanQty: {}, windowRemainingPlanQty: {}, scheduleDayFinishQty: {}, usage: {}",
        context.getFactoryCode(), dto.getMaterialCode(), context.getScheduleDate(),
        windowPlanQty, windowRemainingPlanQty, scheDayFinishQty,
        "dayN仅用于节奏判断和滚动对账，不作为非收尾实际排产硬上限");
```

不要修改月计划读取、T 日晚班完成量扣减、上月有效超欠产、历史欠产继承和未排构造逻辑。

- [ ] **Step 4：运行验证命令**

Run:

```bash
mvn -pl aps-lh -Dtest=ScheduleAdjustCarryForwardRegressionTest,ScheduleAdjustHandlerTest,ScheduleAdjustHandlerSharedEmbryoTest test
```

Expected: 全部 `PASS`。

- [ ] **Step 5：提交**

```bash
git add aps-lh/src/main/java/com/zlt/aps/lh/handler/ScheduleAdjustHandler.java aps-lh/src/test/com/zlt/aps/lh/regression/ScheduleAdjustCarryForwardRegressionTest.java aps-lh/src/test/java/com/zlt/aps/lh/handler/ScheduleAdjustHandlerTest.java
git commit -m "补充日计划账本入口语义日志"
```

## Task 8：OpenSpec 主规格同步

**Files:**

- Modify: `openspec/specs/add-machine-rule/spec.md`
- Modify: `openspec/specs/continue-reduce-machine-rule/spec.md`
- Modify: `openspec/specs/sku-early-production/spec.md`
- Modify: `openspec/specs/mould-surplus-calculate/spec.md`
- Modify: `openspec/specs/daily-standard-shift-plan/spec.md`
- Test: `openspec/changes/unify-dayn-schedule-quota/specs/add-machine-rule/spec.md`
- Test: `openspec/changes/unify-dayn-schedule-quota/specs/continue-reduce-machine-rule/spec.md`
- Test: `openspec/changes/unify-dayn-schedule-quota/specs/sku-early-production/spec.md`
- Test: `openspec/changes/unify-dayn-schedule-quota/specs/mould-surplus-calculate/spec.md`
- Test: `openspec/changes/unify-dayn-schedule-quota/specs/daily-standard-shift-plan/spec.md`

- [ ] **Step 1：写规格验收清单**

在每个主规格中合并对应 delta 的中文规则，必须覆盖：

```text
add-machine-rule：dayN 只判断当前机台是否满足当前日或后看日节奏，不作为非收尾实际硬上限；日志包含 SKU、业务日期、当前机台数、dayN、是否加机台。
continue-reduce-machine-rule：dayN 只判断续作降模保留机台是否满足节奏，降模后重分配不按 dayN 浅排；日志包含当前机台数、保留机台数、T 日 dayN、后看 dayN。
sku-early-production：前移视图 shifted[T]=original[T+1] 只用于准入、节奏和加机台模拟；T+2 不得提前到 T；不写回原始账本。
mould-surplus-calculate：dayN 不得覆盖 SkuScheduleDTO.surplusQty、非共用胎胚收尾 MAX(硫化余量, 胎胚库存)、共用胎胚只按硫化余量、零余量未排。
daily-standard-shift-plan：日标准产量修正只修正班次计划量和展示口径，不得通过结果回裁把 dayN 变成非收尾硬上限；ResultValidationHandler 只对账。
```

- [ ] **Step 2：运行规格失败验证**

Run:

```bash
openspec validate unify-dayn-schedule-quota --strict
```

Expected: 在主规格未同步前，人工审阅应判定不完整；如果 CLI 通过，也必须继续完成主规格合并，因为本项目要求主规格未更新则任务不算完成。

- [ ] **Step 3：最小文档实现**

逐个把 delta spec 的 `Requirement` 和 `Scenario` 合并进对应主规格，保持主规格现有 `## Purpose`、`## Requirements`、`#### Scenario` 风格。不要修改 `openspec/changes/unify-dayn-schedule-quota/tasks.md` 和 delta spec。

- [ ] **Step 4：运行验证命令**

Run:

```bash
openspec validate unify-dayn-schedule-quota --strict
git diff --check
```

Expected: OpenSpec 严格校验通过，`git diff --check` 无尾随空白。

- [ ] **Step 5：提交**

```bash
git add openspec/specs/add-machine-rule/spec.md openspec/specs/continue-reduce-machine-rule/spec.md openspec/specs/sku-early-production/spec.md openspec/specs/mould-surplus-calculate/spec.md openspec/specs/daily-standard-shift-plan/spec.md
git commit -m "同步dayN统一口径主规格"
```

## Task 9：整体验证和真实复跑

**Files:**

- Verify: `aps-lh/src/main/java/com/zlt/aps/lh/handler/ScheduleAdjustHandler.java`
- Verify: `aps-lh/src/main/java/com/zlt/aps/lh/engine/strategy/support/DailyMachineExpansionPlanner.java`
- Verify: `aps-lh/src/main/java/com/zlt/aps/lh/component/TargetScheduleQtyResolver.java`
- Verify: `aps-lh/src/main/java/com/zlt/aps/lh/engine/strategy/impl/ContinuousProductionStrategy.java`
- Verify: `aps-lh/src/main/java/com/zlt/aps/lh/engine/strategy/impl/NewSpecProductionStrategy.java`
- Verify: `aps-lh/src/main/java/com/zlt/aps/lh/engine/strategy/impl/TypeBlockProductionStrategy.java`
- Verify: `aps-lh/src/main/java/com/zlt/aps/lh/engine/strategy/support/EarlyProductionChecker.java`
- Verify: `aps-lh/src/main/java/com/zlt/aps/lh/util/SkuDailyPlanQuotaUtil.java`
- Verify: `aps-lh/src/main/java/com/zlt/aps/lh/handler/ResultValidationHandler.java`

- [ ] **Step 1：运行 focused tests**

Run:

```bash
mvn -pl aps-lh -Dtest=TargetScheduleQtyResolverRegressionTest,NewSpecProductionStrategyTest,SchedulingStrategyRegressionTest,ContinuousProductionStrategyTest,TypeBlockResultSourceSkuRegressionTest,EarlyProductionCheckerTest,SkuDailyPlanQuotaUtilRegressionTest,ResultValidationBlockingTest,ScheduleAdjustCarryForwardRegressionTest test
```

Expected: 全部 `PASS`。

- [ ] **Step 2：运行 OpenSpec 和格式验证**

Run:

```bash
openspec validate unify-dayn-schedule-quota --strict
git diff --check
```

Expected: 全部通过。

- [ ] **Step 3：启动应用用于真实复跑**

启动前确认 9669 端口不是旧进程：

```bash
lsof -i :9669
```

启动：

```bash
mvn spring-boot:run -pl aps-lh -Dmaven.test.skip=true 2>&1 | tee aps-lh-start.log | grep -E "(Started|ERROR|Application Lh is running|启动|port:)" | head -1
```

Expected: 输出 `Started` 或 `Application Lh is running`；如果输出 `ERROR`，停止复跑并记录 `aps-lh-start.log` 中第一处异常堆栈。

- [ ] **Step 4：按 2026-06-14 真实复跑**

Run:

```bash
curl --noproxy '*' -X POST 'http://localhost:9669/lhScheduleResult/execute' \
  -H 'Content-Type: application/json' \
  -d '{"factoryCode":"116","scheduleDate":"2026-06-14"}'
```

Expected: 接口返回成功，产生新批次号。

- [ ] **Step 5：数据库对账**

按新批次号查询：

```sql
SELECT MATERIAL_CODE, MACHINE_CODE, SCHEDULE_TYPE, IS_TYPE_BLOCK, SUM(PLAN_QTY) AS PLAN_QTY
FROM T_LH_SCHEDULE_RESULT
WHERE LH_RESULT_BATCH_NO = '新批次号'
  AND IS_DELETE = 0
GROUP BY MATERIAL_CODE, MACHINE_CODE, SCHEDULE_TYPE, IS_TYPE_BLOCK;

SELECT MATERIAL_CODE, UNSCHEDULED_REASON
FROM T_LH_UNSCHEDULED_RESULT
WHERE LH_RESULT_BATCH_NO = '新批次号'
  AND IS_DELETE = 0;

SELECT MACHINE_CODE, MATERIAL_CODE, CHANGE_TYPE, START_TIME, END_TIME
FROM T_LH_MOULD_CHANGE_PLAN
WHERE LH_RESULT_BATCH_NO = '新批次号'
  AND IS_DELETE = 0;

SELECT CONTENT
FROM T_LH_SCHEDULE_PROCESS_LOG
WHERE LH_RESULT_BATCH_NO = '新批次号'
  AND CONTENT LIKE '%dayN%';
```

Expected:

- 非收尾新增、续作、换活字块结果不因 `dayN` 小于可用产能而浅排；
- 收尾 SKU 仍严格按目标量，不被 `dayN` 调低或抬高；
- 共用胎胚零余量仍进入未排；
- 加机台、降模、提前生产日志能看出 `dayN` 是节奏判断，不是实际硬上限；
- 日计划完成日志只用于对账，不触发后置截断。

- [ ] **Step 6：最终提交**

如果复跑只产生日志文件且不需要入库脚本，删除 `aps-lh-start.log` 后确认工作区只剩代码、测试和 spec 改动。

```bash
git status --short
git add aps-lh/src/main/java aps-lh/src/test openspec/specs
git commit -m "完成dayN排产口径统一验证"
```

## 自检清单

- [ ] 覆盖 `ScheduleAdjustHandler`：入口账本初始化、T 日晚班完成量扣减、日志语义。
- [ ] 覆盖 `DailyMachineExpansionPlanner`：当前日节奏满足不加机台，当前日不足继续加机台。
- [ ] 覆盖 `TargetScheduleQtyResolver`：收尾目标量不被 `dayN` 调低或抬高，共用胎胚零余量不因 `dayN` 排产。
- [ ] 覆盖 `ContinuousProductionStrategy`：降模保留机台满足 T 日节奏后停止，非收尾重分配不浅排。
- [ ] 覆盖 `NewSpecProductionStrategy`：普通非收尾新增不按账本剩余额度硬截断，提前生产不按前移 `dayN` 截断。
- [ ] 覆盖 `TypeBlockProductionStrategy`：换活字块实际结果和回流新增链路不按 `dayN` 截断。
- [ ] 覆盖 `EarlyProductionChecker`：只提前一天，T+2 不得提前到 T。
- [ ] 覆盖 `SkuDailyPlanQuotaUtil`：前移视图克隆，不污染原始账本。
- [ ] 覆盖 `ResultValidationHandler`：日计划完成校验只对账，不改写非收尾结果。
- [ ] 覆盖主规格同步：5 个主规格均写入中文最新规则。
