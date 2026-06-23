# Main Sale Ending Fill Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 新增主销产品 SKU 收尾补满规则，仅在主销产品收尾、单机台收尾时间晚于业务日 20:00、结构已排机台数未达到月计划结构计划机台数时，允许该机台补满当天中班和下一个晚班。

**Architecture:** 保持普通收尾目标量私域不变，不在 `TargetScheduleQtyResolver#upsizeEndingTargetQty` 直接抬高 SKU 级目标量。新增主销判断字段与续作收尾后置补满 helper，在每条续作结果上独立判断并补满，同时复用 `LhScheduleContext` 的结构计划机台数和已排机台数统计。OpenSpec 新增主规格，并同步修订日标准产量主规格中的收尾例外。

**Tech Stack:** Java 8、Spring Boot、JUnit 5、MyBatis-Plus、OpenSpec Markdown。

## Global Constraints

- 始终使用简体中文回复、注释、日志、提交说明。
- 修改代码前必须先搜索并阅读相关类和方法。
- 不影响非主销 SKU 收尾逻辑。
- 不影响非收尾 SKU 排产逻辑。
- 不影响原有收尾目标量计算规则。
- 仅在 `主销产品 + SKU 收尾 + 机台 20:00 后收尾 + 结构机台数未达标` 时触发补满。
- 补满后需同步更新该业务日对应结构的已排硫化机台数统计。
- Java 代码保持 Java 8 语法，判空使用项目既有工具类。
- 不新增无业务依据的 fallback，不吞异常，不做无关重构。

---

## File Structure

- `aps-lh-api/src/main/java/com/zlt/aps/lh/api/domain/dto/SkuScheduleDTO.java`
  - 新增 `productionType` 字段，承载月计划 `PRODUCTION_TYPE`。
- `aps-lh/src/main/java/com/zlt/aps/lh/handler/ScheduleAdjustHandler.java`
  - 初始化和复制 SKU DTO 时同步 `productionType`。
- `aps-lh/src/main/java/com/zlt/aps/lh/engine/strategy/impl/ContinuousProductionStrategy.java`
  - 新增主销收尾补满判断与执行 helper，并在续作最终收口链路调用。
- `aps-lh/src/test/com/zlt/aps/lh/regression/ContinuousProductionResultQtyRegressionTest.java`
  - 增加 focused regression，覆盖主销触发、普通 SKU 不触发、20:00 边界和结构机台数达标阻断。
- `openspec/specs/main-sale-ending-fill/spec.md`
  - 新增主销产品收尾补满主规格。
- `openspec/specs/daily-standard-shift-plan/spec.md`
  - 补充“主销产品收尾补满是收尾严格目标量的显式例外”。

---

### Task 1: 写 RED 测试锁定主销收尾补满私域

**Files:**
- Modify: `aps-lh/src/test/com/zlt/aps/lh/regression/ContinuousProductionResultQtyRegressionTest.java`

**Interfaces:**
- Consumes: private method `applyDailyStandardPlanQtyToContinuousResults(LhScheduleContext, List<LhShiftConfigVO>)` via `ReflectionTestUtils.invokeMethod`
- Produces: failing tests that prove production code lacks `productionType` and main sale ending fill behavior

- [ ] **Step 1: Write the failing tests**

Add four tests to `ContinuousProductionResultQtyRegressionTest`:

```java
@Test
void applyDailyStandardPlanQtyToContinuousResults_shouldFillMainSaleEndingAfterTwentyWhenStructureNotFull() {
    LhScheduleContext context = newContext();
    context.addStructurePlanMachineCount(LocalDate.of(2026, 6, 23), "PCR-01", 2);
    SkuScheduleDTO sku = buildMainSaleEndingSku("330200MAIN", "PCR-01");
    context.getScheduleResultSourceSkuMap().put(buildMainSaleEndingResult(context, "K1901", "330200MAIN", 8), sku);

    LhScheduleResult result = context.getScheduleResultList().get(0);
    ReflectionTestUtils.invokeMethod(strategy,
            "applyDailyStandardPlanQtyToContinuousResults", context, context.getScheduleWindowShifts());

    assertEquals(18, result.getClass8PlanQty().intValue(), "主销收尾20点后应补满当天中班");
    assertEquals(18, result.getClass1PlanQty().intValue(), "主销收尾20点后应补满下一个晚班");
    assertEquals(1, context.getStructureScheduledMachineCount(LocalDate.of(2026, 6, 23), "PCR-01"),
            "补满后必须回写结构已排机台统计");
}

@Test
void applyDailyStandardPlanQtyToContinuousResults_shouldKeepNormalEndingStrictTarget() {
    LhScheduleContext context = newContext();
    context.addStructurePlanMachineCount(LocalDate.of(2026, 6, 23), "PCR-01", 2);
    SkuScheduleDTO sku = buildMainSaleEndingSku("330200NORMAL", "PCR-01");
    sku.setProductionType("02");
    context.getScheduleResultSourceSkuMap().put(buildMainSaleEndingResult(context, "K1902", "330200NORMAL", 8), sku);

    LhScheduleResult result = context.getScheduleResultList().get(0);
    ReflectionTestUtils.invokeMethod(strategy,
            "applyDailyStandardPlanQtyToContinuousResults", context, context.getScheduleWindowShifts());

    assertEquals(8, result.getClass8PlanQty().intValue(), "普通收尾SKU不得补满中班");
    assertNull(result.getClass1PlanQty(), "普通收尾SKU不得补满下一个晚班");
}

@Test
void applyDailyStandardPlanQtyToContinuousResults_shouldNotFillAtExactTwenty() {
    LhScheduleContext context = newContext();
    context.addStructurePlanMachineCount(LocalDate.of(2026, 6, 23), "PCR-01", 2);
    SkuScheduleDTO sku = buildMainSaleEndingSku("330200MAIN", "PCR-01");
    context.getScheduleResultSourceSkuMap().put(buildMainSaleEndingResult(context, "K1903", "330200MAIN", 0), sku);

    LhScheduleResult result = context.getScheduleResultList().get(0);
    ReflectionTestUtils.invokeMethod(strategy,
            "applyDailyStandardPlanQtyToContinuousResults", context, context.getScheduleWindowShifts());

    assertEquals(8, result.getClass8PlanQty().intValue(), "正好20:00不属于20:00之后");
    assertNull(result.getClass1PlanQty(), "正好20:00不得补满下一个晚班");
}

@Test
void applyDailyStandardPlanQtyToContinuousResults_shouldNotFillWhenStructureMachineCountReached() {
    LhScheduleContext context = newContext();
    context.addStructurePlanMachineCount(LocalDate.of(2026, 6, 23), "PCR-01", 1);
    context.recordScheduledMachine(LocalDate.of(2026, 6, 23), "PCR-01", "330200OTHER", "K1801");
    SkuScheduleDTO sku = buildMainSaleEndingSku("330200MAIN", "PCR-01");
    context.getScheduleResultSourceSkuMap().put(buildMainSaleEndingResult(context, "K1904", "330200MAIN", 8), sku);

    LhScheduleResult result = context.getScheduleResultList().get(0);
    ReflectionTestUtils.invokeMethod(strategy,
            "applyDailyStandardPlanQtyToContinuousResults", context, context.getScheduleWindowShifts());

    assertEquals(8, result.getClass8PlanQty().intValue(), "结构机台数已达标时不得补满中班");
    assertNull(result.getClass1PlanQty(), "结构机台数已达标时不得补满下一个晚班");
}
```

Also add local test builders in the same test class:

```java
private SkuScheduleDTO buildMainSaleEndingSku(String materialCode, String structureName) {
    SkuScheduleDTO sku = new SkuScheduleDTO();
    sku.setMaterialCode(materialCode);
    sku.setStructureName(structureName);
    sku.setSkuTag(SkuTagEnum.ENDING.getCode());
    sku.setProductionType("01");
    sku.setStrictTargetQty(true);
    sku.setSurplusQty(8);
    sku.setEmbryoStock(8);
    sku.setMouldQty(2);
    return sku;
}

private LhScheduleResult buildMainSaleEndingResult(LhScheduleContext context,
                                                   String machineCode,
                                                   String materialCode,
                                                   int minutesAfterTwenty) {
    LhScheduleResult result = new LhScheduleResult();
    result.setMaterialCode(materialCode);
    result.setStructureName("PCR-01");
    result.setLhMachineCode(machineCode);
    result.setScheduleType(ScheduleTypeEnum.CONTINUOUS.getCode());
    result.setSingleMouldShiftQty(18);
    result.setMouldQty(2);
    result.setLhTime(1800);
    result.setIsEnd("1");
    result.setClass8PlanQty(8);
    result.setClass8StartTime(Date.from(LocalDateTime.of(2026, 6, 23, 18, 0)
            .atZone(ZoneId.systemDefault()).toInstant()));
    result.setClass8EndTime(Date.from(LocalDateTime.of(2026, 6, 23, 20, minutesAfterTwenty)
            .atZone(ZoneId.systemDefault()).toInstant()));
    context.getScheduleResultList().add(result);
    return result;
}
```

- [ ] **Step 2: Run test to verify RED**

Run:

```bash
mvn -pl aps-lh -Dtest=ContinuousProductionResultQtyRegressionTest#applyDailyStandardPlanQtyToContinuousResults_shouldFillMainSaleEndingAfterTwentyWhenStructureNotFull test
```

Expected: compile failure or assertion failure because `SkuScheduleDTO#setProductionType` and main sale ending fill behavior do not exist yet.

---

### Task 2: 增加月计划 productionType 传递

**Files:**
- Modify: `aps-lh-api/src/main/java/com/zlt/aps/lh/api/domain/dto/SkuScheduleDTO.java`
- Modify: `aps-lh/src/main/java/com/zlt/aps/lh/handler/ScheduleAdjustHandler.java`

**Interfaces:**
- Produces: `SkuScheduleDTO#getProductionType()` and `setProductionType(String productionType)`
- Consumes: `FactoryMonthPlanProductionFinalResult#getProductionType()`

- [ ] **Step 1: Add DTO field**

In `SkuScheduleDTO`, add near priority/status fields:

```java
/** 排产分类，来自月计划 PRODUCTION_TYPE；01-主销产品，其他-普通产品 */
private String productionType;
```

- [ ] **Step 2: Fill field from month plan**

In `ScheduleAdjustHandler#convertToSkuScheduleDTO`, keep existing `supplyChainPriority` behavior and add:

```java
dto.setProductionType(plan.getProductionType());
```

- [ ] **Step 3: Copy field when duplicating SKU**

In the existing copy method around `copy.setSupplyChainPriority(source.getSupplyChainPriority());`, add:

```java
copy.setProductionType(source.getProductionType());
```

- [ ] **Step 4: Run RED test again**

Run:

```bash
mvn -pl aps-lh -Dtest=ContinuousProductionResultQtyRegressionTest#applyDailyStandardPlanQtyToContinuousResults_shouldFillMainSaleEndingAfterTwentyWhenStructureNotFull test
```

Expected: test compiles but fails on expected fill quantities because implementation is still missing.

---

### Task 3: 实现主销收尾补满 helper

**Files:**
- Modify: `aps-lh/src/main/java/com/zlt/aps/lh/engine/strategy/impl/ContinuousProductionStrategy.java`

**Interfaces:**
- Consumes: `SkuScheduleDTO#getProductionType()`
- Consumes: `LhScheduleContext#getStructurePlanMachineCount(LocalDate, String)`
- Consumes: `LhScheduleContext#getStructureScheduledMachineCount(LocalDate, String)`
- Consumes: `LhScheduleContext#recordScheduledMachine(LocalDate, String, String, String)`
- Produces: private method `applyMainSaleEndingFillIfNecessary(LhScheduleContext, LhScheduleResult, SkuScheduleDTO, List<LhShiftConfigVO>)`

- [ ] **Step 1: Add constants**

Add to `ContinuousProductionStrategy` constants section:

```java
private static final String MAIN_SALE_PRODUCTION_TYPE = "01";
private static final LocalTime MAIN_SALE_ENDING_FILL_THRESHOLD_TIME = LocalTime.of(20, 0);
```

- [ ] **Step 2: Call helper in final continuous result pass**

In `applyDailyStandardPlanQtyToContinuousResults`, after daily standard adjustment for each pure continuous result and before final loop continues, resolve source SKU and call:

```java
SkuScheduleDTO sourceSku = resolveResultSourceSku(context, result);
applyMainSaleEndingFillIfNecessary(context, result, sourceSku, shifts);
```

- [ ] **Step 3: Implement guard and fill helper**

Add private methods:

```java
/**
 * 主销产品收尾特殊补满。
 * <p>仅当主销收尾机台真实收尾时间晚于业务日20:00，且结构已排机台数未达到月计划结构机台数时，
 * 才允许补满当天中班和下一个晚班。</p>
 *
 * @param context 排程上下文
 * @param result 续作结果
 * @param sku 来源SKU
 * @param shifts 排程窗口班次
 */
private void applyMainSaleEndingFillIfNecessary(LhScheduleContext context,
                                                LhScheduleResult result,
                                                SkuScheduleDTO sku,
                                                List<LhShiftConfigVO> shifts) {
    if (!isMainSaleEndingFillCandidate(context, result, sku, shifts)) {
        return;
    }
    Date endingTime = result.getSpecEndTime();
    if (Objects.isNull(endingTime)) {
        endingTime = resolveLastShiftEndTime(result, shifts);
    }
    LocalDate businessDate = endingTime.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
    LocalTime endingLocalTime = endingTime.toInstant().atZone(ZoneId.systemDefault()).toLocalTime();
    if (!endingLocalTime.isAfter(MAIN_SALE_ENDING_FILL_THRESHOLD_TIME)) {
        return;
    }
    int planMachineCount = context.getStructurePlanMachineCount(businessDate, sku.getStructureName());
    int scheduledMachineCount = context.getStructureScheduledMachineCount(businessDate, sku.getStructureName());
    if (planMachineCount <= 0 || scheduledMachineCount >= planMachineCount) {
        log.info("主销收尾补满跳过, materialCode: {}, machineCode: {}, businessDate: {}, structureName: {}, "
                        + "planMachineCount: {}, scheduledMachineCount: {}, endingTime: {}",
                result.getMaterialCode(), result.getLhMachineCode(), businessDate, sku.getStructureName(),
                planMachineCount, scheduledMachineCount, LhScheduleTimeUtil.formatDateTime(endingTime));
        return;
    }
    boolean filled = fillMainSaleEndingMiddleAndNextNight(context, result, shifts, businessDate);
    if (filled) {
        context.recordScheduledMachine(businessDate, sku.getStructureName(), sku.getMaterialCode(),
                result.getLhMachineCode());
        refreshResultSummary(context, result, shifts);
        log.info("主销收尾补满完成, materialCode: {}, machineCode: {}, businessDate: {}, structureName: {}, "
                        + "planMachineCount: {}, scheduledMachineCountBefore: {}, scheduledMachineCountAfter: {}, endingTime: {}",
                result.getMaterialCode(), result.getLhMachineCode(), businessDate, sku.getStructureName(),
                planMachineCount, scheduledMachineCount,
                context.getStructureScheduledMachineCount(businessDate, sku.getStructureName()),
                LhScheduleTimeUtil.formatDateTime(endingTime));
    }
}
```

- [ ] **Step 4: Add small helpers for candidate, shift selection, and fill**

Implement helpers with Java 8 loops:

```java
private boolean isMainSaleEndingFillCandidate(LhScheduleContext context,
                                              LhScheduleResult result,
                                              SkuScheduleDTO sku,
                                              List<LhShiftConfigVO> shifts) {
    return Objects.nonNull(context)
            && Objects.nonNull(result)
            && Objects.nonNull(sku)
            && !CollectionUtils.isEmpty(shifts)
            && StringUtils.equals(MAIN_SALE_PRODUCTION_TYPE, sku.getProductionType())
            && StringUtils.equals(SkuTagEnum.ENDING.getCode(), sku.getSkuTag())
            && StringUtils.equals(ScheduleTypeEnum.CONTINUOUS.getCode(), result.getScheduleType())
            && StringUtils.isNotEmpty(sku.getStructureName())
            && StringUtils.isNotEmpty(result.getLhMachineCode());
}
```

Use existing `ShiftEnum`/shift utilities if available; otherwise identify middle and next night by shift business date and shift type from `LhShiftConfigVO`, not hard-coded class index.

- [ ] **Step 5: Run focused tests**

Run:

```bash
mvn -pl aps-lh -Dtest=ContinuousProductionResultQtyRegressionTest#applyDailyStandardPlanQtyToContinuousResults_shouldFillMainSaleEndingAfterTwentyWhenStructureNotFull test
```

Expected: PASS.

---

### Task 4: 扩展 regression 覆盖边界并跑 GREEN

**Files:**
- Modify: `aps-lh/src/test/com/zlt/aps/lh/regression/ContinuousProductionResultQtyRegressionTest.java`

**Interfaces:**
- Consumes: Task 3 helper behavior
- Produces: regression coverage for non-main-sale, exact 20:00, and structure count reached

- [ ] **Step 1: Run all four focused tests**

Run:

```bash
mvn -pl aps-lh -Dtest=ContinuousProductionResultQtyRegressionTest#applyDailyStandardPlanQtyToContinuousResults_shouldFillMainSaleEndingAfterTwentyWhenStructureNotFull,ContinuousProductionResultQtyRegressionTest#applyDailyStandardPlanQtyToContinuousResults_shouldKeepNormalEndingStrictTarget,ContinuousProductionResultQtyRegressionTest#applyDailyStandardPlanQtyToContinuousResults_shouldNotFillAtExactTwenty,ContinuousProductionResultQtyRegressionTest#applyDailyStandardPlanQtyToContinuousResults_shouldNotFillWhenStructureMachineCountReached test
```

Expected: PASS.

- [ ] **Step 2: Run adjacent tests**

Run:

```bash
mvn -pl aps-lh -Dtest=ContinuousProductionResultQtyRegressionTest,TargetScheduleQtyResolverTest test
```

Expected: PASS.

---

### Task 5: 更新 OpenSpec 主规格

**Files:**
- Create: `openspec/specs/main-sale-ending-fill/spec.md`
- Modify: `openspec/specs/daily-standard-shift-plan/spec.md`

**Interfaces:**
- Produces: OpenSpec main spec for the new rule
- Consumes: existing strict ending and daily standard wording from `daily-standard-shift-plan/spec.md`

- [ ] **Step 1: Add new spec**

Create `openspec/specs/main-sale-ending-fill/spec.md` with:

```markdown
# 主销产品 SKU 收尾补满规则

## Purpose

规范硫化排程中主销产品 SKU 的收尾补满例外规则，使主销产品在满足单机台 20:00 后收尾和结构机台数未达标条件时，可以补满当天中班与下一个晚班，同时保持普通 SKU 收尾严格目标量和非收尾排产逻辑不变。

## Requirements

### Requirement: 主销产品判断

系统 SHALL 使用月计划 `productionType` 判断 SKU 是否为主销产品。

#### Scenario: productionType 为 01

- **WHEN** SKU 月计划 `productionType = 01`
- **THEN** 系统 SHALL 将该 SKU 识别为主销产品

#### Scenario: productionType 非 01

- **WHEN** SKU 月计划 `productionType` 不是 `01`
- **THEN** 系统 SHALL 将该 SKU 识别为普通产品

### Requirement: 普通收尾规则保持严格目标量

非主销产品 SKU 收尾 SHALL 继续严格按原收尾目标量排产。

#### Scenario: 普通 SKU 收尾

- **WHEN** SKU 不是主销产品
- **AND** SKU 命中收尾场景
- **THEN** 系统 SHALL NOT 因主销补满规则补满中班或晚班
- **AND** 系统 SHALL 保持原收尾目标量规则

### Requirement: 主销收尾 20:00 后允许补满

主销产品 SKU 收尾时，系统 SHALL 以单台机台的真实收尾时间独立判断是否允许补满。

#### Scenario: 收尾时间晚于 20:00 且结构机台数未达标

- **WHEN** SKU 为主销产品
- **AND** SKU 为收尾场景
- **AND** 当前机台真实收尾时间晚于业务日 `20:00`
- **AND** 当前业务日该结构已排硫化机台数小于月计划统计结构计划硫化机台数
- **THEN** 系统 SHALL 允许该机台补满当天中班班产
- **AND** 系统 SHALL 允许该机台补满下一个晚班班产
- **AND** 系统 SHALL 同步更新该业务日该结构的已排硫化机台数统计

#### Scenario: 收尾时间等于 20:00

- **WHEN** SKU 为主销产品
- **AND** 当前机台真实收尾时间等于业务日 `20:00`
- **THEN** 系统 SHALL NOT 触发主销收尾补满

#### Scenario: 结构机台数已达标

- **WHEN** SKU 为主销产品
- **AND** 当前业务日该结构已排硫化机台数大于等于月计划统计结构计划硫化机台数
- **THEN** 系统 SHALL NOT 触发主销收尾补满
- **AND** 系统 SHALL 继续按原 SKU 收尾目标量排产

### Requirement: 多机台独立判断

同一主销 SKU 在多个机台同时生产并收尾时，系统 SHALL 对每台机台分别判断收尾时间和结构机台数限制。

#### Scenario: 多机台部分满足补满条件

- **WHEN** 同一主销 SKU 有多台续作机台
- **AND** 只有部分机台真实收尾时间晚于业务日 `20:00`
- **AND** 结构已排硫化机台数仍小于结构计划硫化机台数
- **THEN** 系统 SHALL 只补满满足条件的机台
- **AND** 系统 SHALL NOT 补满不满足条件的机台
```

- [ ] **Step 2: Amend daily standard spec**

In `openspec/specs/daily-standard-shift-plan/spec.md` section `6.1 收尾场景`, add:

```markdown
主销产品 SKU 收尾补满是收尾严格目标量的显式例外。仅当主销产品、SKU 收尾、单机台真实收尾时间晚于业务日 `20:00`、且该业务日结构已排硫化机台数小于月计划统计结构计划硫化机台数时，系统 SHALL 允许该机台补满当天中班和下一个晚班；除此之外，收尾或其他严格目标量场景仍 SHALL 以剩余目标量为补量上限。
```

- [ ] **Step 3: Run spec format check**

Run:

```bash
git diff --check
```

Expected: no whitespace errors.

---

### Task 6: Final verification

**Files:**
- All modified files from Tasks 1-5

**Interfaces:**
- Consumes: all implementation and specs
- Produces: verified working tree

- [ ] **Step 1: Run focused tests**

Run:

```bash
mvn -pl aps-lh -Dtest=ContinuousProductionResultQtyRegressionTest,TargetScheduleQtyResolverTest test
```

Expected: PASS.

- [ ] **Step 2: Run diff check**

Run:

```bash
git diff --check
```

Expected: no output.

- [ ] **Step 3: Inspect final diff**

Run:

```bash
git diff -- aps-lh-api/src/main/java/com/zlt/aps/lh/api/domain/dto/SkuScheduleDTO.java aps-lh/src/main/java/com/zlt/aps/lh/handler/ScheduleAdjustHandler.java aps-lh/src/main/java/com/zlt/aps/lh/engine/strategy/impl/ContinuousProductionStrategy.java aps-lh/src/test/com/zlt/aps/lh/regression/ContinuousProductionResultQtyRegressionTest.java openspec/specs/main-sale-ending-fill/spec.md openspec/specs/daily-standard-shift-plan/spec.md
```

Expected: only the main sale ending fill rule, tests, and specs changed.

---

## Self-Review

- Spec coverage: 主销判断、普通 SKU 不变、20:00 后补满、多机台独立判断、结构机台数限制、统计回写、OpenSpec 文档均有任务覆盖。
- Placeholder scan: 无 `TBD`、`TODO`、`implement later`。
- Type consistency: 新字段统一为 `SkuScheduleDTO.productionType`，主销值统一为 `"01"`，20:00 阈值统一为 `LocalTime.of(20, 0)`。
