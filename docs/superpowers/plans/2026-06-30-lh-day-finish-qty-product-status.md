# LhDayFinishQty Product Status Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 `LhDayFinishQty` 来源的月累计完成量和日完成量从物料维度调整为“物料编码 + 产品状态/示方类型”维度，避免同物料不同产品状态串量。

**Architecture:** 保持现有“基础数据初始化批量查询、内存 Map 聚合、S4.3 处理器按 key 读取”的结构。只扩展 `LhDayFinishQty` 相关 Map key 维度，`LhScheFinishQty` 的 T 日晚班 `class1FinishQty` 继续按物料编码汇总。

**Tech Stack:** Java 8、Spring、MyBatis-Plus、JUnit 5、Mockito、Hutool/现有 `LhScheduleTimeUtil` 日期工具。

## Global Constraints

- 始终使用简体中文回复，代码注释、提交说明、方案说明也必须使用简体中文。
- 优先基于现有代码结构改造，禁止大范围重构。
- 修改代码前必须先搜索并阅读相关类和方法。
- 不允许为了规避问题而新增 fallback、兜底逻辑、吞异常逻辑。
- 本次不新增 Mapper，不新增 XML，不调整数据库表结构。
- 本次只调整 `LhDayFinishQty` 来源的月累计完成量和指定日期日完成量。
- `LhScheFinishQty` 来源的 T 日晚班 `class1FinishQty` 不纳入本次变更，继续保持现有按物料编码汇总口径。
- `LhDayFinishQty` 完成量侧字段使用 `lhType`，月计划侧字段使用 `FactoryMonthPlanProductionFinalResult.productStatus`。
- 同一物料、同一产品状态存在多条完成量记录时继续累加汇总。
- 产品状态为空时只匹配 `lhType` 为空的完成量，不回退到仅物料维度。
- 当前工作区已有未提交的 `aps-lh-api/src/main/java/com/zlt/aps/lh/api/domain/entity/LhDayFinishQty.java` 改动，实施时必须读取并保留，不得覆盖用户改动。

---

## File Structure

- Modify: `aps-lh/src/test/com/zlt/aps/lh/service/impl/LhBaseDataServiceImplTest.java`
  - 增加基础数据初始化 RED 测试，验证 `LhDayFinishQty` 按 `materialCode + lhType` 汇总。
  - 调整已有期望 key，使历史测试符合新组合 key。
- Modify: `aps-lh/src/main/java/com/zlt/aps/lh/service/impl/LhBaseDataServiceImpl.java`
  - 增加统一组合 key 私有方法。
  - 修改 `loadDayFinishQty`、`mergeMaterialMonthFinishedQty`、`buildMonthPlanMaterialFinishedQtyMap`、`appendMonthFinishedQtyByMonth`。
  - 更新相关中文注释与日志字段。
- Modify: `aps-lh/src/test/java/com/zlt/aps/lh/handler/ScheduleAdjustHandlerTest.java`
  - 增加 S4.3 RED 测试，验证月累计、历史逐日、前日 fallback 使用产品状态 key，T 日晚班仍按物料 key。
  - 调整已有测试 Map key。
- Modify: `aps-lh/src/main/java/com/zlt/aps/lh/handler/ScheduleAdjustHandler.java`
  - 修改 `calculateFinishedQty`、`resolveMaterialMonthFinishedQty`、`resolveMaterialDayFinishedQty`、`resolveMonthDailyFinishedQty` 和 key 构造。
  - `resolveScheDayFinishQty` 不改。
- Modify: `aps-lh/src/main/java/com/zlt/aps/lh/context/LhScheduleContext.java`
  - 同步完成量 Map 注释，明确新 key 维度。
- Read-only dependency: `aps-lh-api/src/main/java/com/zlt/aps/lh/api/domain/entity/LhDayFinishQty.java`
  - 依赖已有 `lhType` 字段映射。若实施时该字段不存在，先暂停并说明实体字段缺失。

---

### Task 1: 基础数据初始化按产品状态聚合完成量

**Files:**
- Modify: `aps-lh/src/test/com/zlt/aps/lh/service/impl/LhBaseDataServiceImplTest.java`
- Modify: `aps-lh/src/main/java/com/zlt/aps/lh/service/impl/LhBaseDataServiceImpl.java`
- Modify: `aps-lh/src/main/java/com/zlt/aps/lh/context/LhScheduleContext.java`

**Interfaces:**
- Consumes: `LhDayFinishQty#getMaterialCode()`、`LhDayFinishQty#getLhType()`、`LhDayFinishQty#getDayFinishQty()`。
- Produces:
  - `LhScheduleContext.materialDayFinishedQtyMap` key = `materialCode + "_" + lhType + "_" + yyyy-MM-dd`
  - `LhScheduleContext.materialMonthDailyFinishedQtyMap` key = `materialCode + "_" + lhType + "_" + yyyy-MM-dd`
  - `LhScheduleContext.materialMonthFinishedQtyMap` key = `materialCode + "_" + productStatus`
  - `LhScheduleContext.materialMonthFinishedQtyByMonthMap` key = `materialCode + "_" + productStatus + "_" + year + "_" + month`

- [ ] **Step 1: 写基础数据 RED 测试**

在 `LhBaseDataServiceImplTest` 的 `loadAllBaseDataShouldExcludeNullAndKeepZeroMonthDailyFinishedQty` 后新增测试方法：

```java
    /**
     * 用例说明：LhDayFinishQty 来源的日完成量和月累计完成量必须按物料编码+示方类型汇总，避免同物料不同产品状态串量。
     *
     * @throws Exception 反射注入异常
     */
    @Test
    public void loadAllBaseDataShouldAggregateDayFinishQtyByMaterialAndLhType() throws Exception {
        LhBaseDataServiceImpl service = buildServiceWithDefaultMocks();
        injectField(service, "lhDataInitExecutor", (Executor) Runnable::run);
        LhScheduleContext context = buildContext();
        context.setScheduleDate(buildDate(2026, 6, 14));
        context.setScheduleTargetDate(buildDate(2026, 6, 14));

        FactoryMonthPlanProductionFinalResult formalPlan = new FactoryMonthPlanProductionFinalResult();
        formalPlan.setMaterialCode("3302001139");
        formalPlan.setProductStatus("S");
        FactoryMonthPlanProductionFinalResult trialPlan = new FactoryMonthPlanProductionFinalResult();
        trialPlan.setMaterialCode("3302001139");
        trialPlan.setProductStatus("T");
        FactoryMonthPlanProductionFinalResultMapper monthPlanMapper =
                mockMapper(FactoryMonthPlanProductionFinalResultMapper.class);
        Mockito.when(monthPlanMapper.selectList(ArgumentMatchers.any()))
                .thenReturn(Arrays.asList(formalPlan, trialPlan));
        injectField(service, "monthPlanMapper", monthPlanMapper);

        LhDayFinishQty formalPreviousDayQty = buildDayFinishQty(
                "3302001139", "S", 2026, 6, 13, BigDecimal.valueOf(9));
        LhDayFinishQty trialPreviousDayQty = buildDayFinishQty(
                "3302001139", "T", 2026, 6, 13, BigDecimal.valueOf(7));
        LhDayFinishQty formalMonthQtyA = buildDayFinishQty(
                "3302001139", "S", 2026, 6, 11, BigDecimal.valueOf(10));
        LhDayFinishQty formalMonthQtyB = buildDayFinishQty(
                "3302001139", "S", 2026, 6, 12, BigDecimal.valueOf(20));
        LhDayFinishQty trialMonthQty = buildDayFinishQty(
                "3302001139", "T", 2026, 6, 12, BigDecimal.valueOf(5));
        LhDayFinishQtyMapper dayFinishQtyMapper = mockMapper(LhDayFinishQtyMapper.class);
        Mockito.when(dayFinishQtyMapper.selectList(ArgumentMatchers.any())).thenReturn(
                Arrays.asList(formalPreviousDayQty, trialPreviousDayQty),
                Arrays.asList(formalMonthQtyA, formalMonthQtyB, trialMonthQty));
        injectField(service, "lhDayFinishQtyMapper", dayFinishQtyMapper);

        service.loadAllBaseData(context);

        Assertions.assertEquals(Integer.valueOf(9),
                context.getMaterialDayFinishedQtyMap().get("3302001139_S_2026-06-13"));
        Assertions.assertEquals(Integer.valueOf(7),
                context.getMaterialDayFinishedQtyMap().get("3302001139_T_2026-06-13"));
        Assertions.assertFalse(context.getMaterialDayFinishedQtyMap().containsKey("3302001139_2026-06-13"),
                "日完成量不能再按物料+日期旧key聚合");
        Assertions.assertEquals(Integer.valueOf(30),
                context.getMaterialMonthFinishedQtyMap().get("3302001139_S"));
        Assertions.assertEquals(Integer.valueOf(5),
                context.getMaterialMonthFinishedQtyMap().get("3302001139_T"));
        Assertions.assertEquals(Integer.valueOf(10),
                context.getMaterialMonthDailyFinishedQtyMap().get("3302001139_S_2026-06-11"));
        Assertions.assertEquals(Integer.valueOf(20),
                context.getMaterialMonthDailyFinishedQtyMap().get("3302001139_S_2026-06-12"));
        Assertions.assertEquals(Integer.valueOf(5),
                context.getMaterialMonthDailyFinishedQtyMap().get("3302001139_T_2026-06-12"));
        Assertions.assertEquals(Integer.valueOf(30),
                context.getMaterialMonthFinishedQtyByMonthMap().get("3302001139_S_2026_6"));
        Assertions.assertEquals(Integer.valueOf(5),
                context.getMaterialMonthFinishedQtyByMonthMap().get("3302001139_T_2026_6"));
    }
```

同时在测试类中把已有 helper 改成重载，保留旧签名兼容已有用例：

```java
    private LhDayFinishQty buildDayFinishQty(String materialCode, int year, int month,
                                             int day, BigDecimal finishedQty) {
        return buildDayFinishQty(materialCode, null, year, month, day, finishedQty);
    }

    private LhDayFinishQty buildDayFinishQty(String materialCode, String lhType, int year, int month,
                                             int day, BigDecimal finishedQty) {
        LhDayFinishQty result = new LhDayFinishQty();
        result.setMaterialCode(materialCode);
        result.setLhType(lhType);
        result.setFinishDate(buildDate(year, month, day));
        result.setDayFinishQty(finishedQty);
        return result;
    }
```

- [ ] **Step 2: 运行测试确认失败**

Run:

```bash
mvn -pl aps-lh -Dtest=LhBaseDataServiceImplTest#loadAllBaseDataShouldAggregateDayFinishQtyByMaterialAndLhType test
```

Expected: FAIL。失败原因应是旧代码仍写入 `3302001139_2026-06-13`、`3302001139`、`3302001139_2026_6` 等物料维度 key，新组合 key 取不到值。

- [ ] **Step 3: 实现基础数据组合 key 聚合**

在 `LhBaseDataServiceImpl` 增加组合 key 方法，并替换旧聚合点：

```java
    /**
     * 构建"物料+产品状态"聚合Key。
     *
     * @param materialCode 物料编码
     * @param productStatus 产品状态或示方类型
     * @return 聚合Key
     */
    private String buildMaterialStatusKey(String materialCode, String productStatus) {
        return materialCode + "_" + StringUtils.trimToEmpty(productStatus);
    }

    /**
     * 生成"物料+产品状态+完成日期"聚合Key。
     *
     * @param materialCode 物料编码
     * @param productStatus 产品状态或示方类型
     * @param finishDate 完成日期
     * @return 聚合Key
     */
    private String buildMaterialDayKey(String materialCode, String productStatus, Date finishDate) {
        return buildMaterialStatusKey(materialCode, productStatus) + "_"
                + LhScheduleTimeUtil.formatDate(LhScheduleTimeUtil.clearTime(finishDate));
    }
```

把 `loadDayFinishQty` 中的聚合 key 改为：

```java
                String key = buildMaterialDayKey(finishQty.getMaterialCode(), finishQty.getLhType(), dayStart);
                materialDayFinishedQtyMap.merge(key, resolveDayFinishedQty(finishQty), Integer::sum);
```

把 `mergeMaterialMonthFinishedQty` 中月累计和逐日聚合改为：

```java
                String materialStatusKey = buildMaterialStatusKey(
                        finishQty.getMaterialCode(), finishQty.getLhType());
                monthFinishedQtyMap.merge(
                        materialStatusKey,
                        resolveDayFinishedQty(finishQty),
                        Integer::sum);
                // 逐日Map仅保留完成量非空的日期；0是有效数据，供“最近一次完成量”判断停止回溯。
                if (Objects.nonNull(finishQty.getDayFinishQty())) {
                    materialMonthDailyFinishedQtyMap.merge(
                            buildMaterialDayKey(finishQty.getMaterialCode(), finishQty.getLhType(),
                                    LhScheduleTimeUtil.clearTime(finishQty.getFinishDate())),
                            resolveDayFinishedQty(finishQty),
                            Integer::sum);
                }
```

把两个 `buildMonthPlanMaterialFinishedQtyMap` 中的初始化 key 改为：

```java
            materialMonthFinishedQtyMap.putIfAbsent(
                    buildMaterialStatusKey(plan.getMaterialCode(), plan.getProductStatus()), 0);
```

把 `appendMonthFinishedQtyByMonth` 保持入参 key，不再把它当纯物料编码：

```java
            materialMonthFinishedQtyByMonthMap.put(
                    MonthPlanDateResolver.buildMaterialMonthKey(entry.getKey(), year, month),
                    Math.max(0, Objects.isNull(entry.getValue()) ? 0 : entry.getValue()));
```

更新 `LhScheduleContext` 注释：

```java
    /** 日完成量Map（按物料+产品状态+完成日期聚合）, key=materialCode_productStatus_finishDate(yyyy-MM-dd) */
    private Map<String, Integer> materialDayFinishedQtyMap = new HashMap<>();
    /** 本月日完成量Map（按物料+产品状态+完成日期聚合）, key=materialCode_productStatus_finishDate(yyyy-MM-dd)，仅覆盖当前排程月份截至T-1 */
    private Map<String, Integer> materialMonthDailyFinishedQtyMap = new HashMap<>();
    /** 月累计完成量Map（按月计划所属月份统计，截至排程窗口T日前一日）, key=materialCode_productStatus */
    private Map<String, Integer> materialMonthFinishedQtyMap = new HashMap<>();
    /** 物料+产品状态+年月 -> 月累计完成量，避免同一物料不同产品状态或跨月时完成量串量 */
    private Map<String, Integer> materialMonthFinishedQtyByMonthMap = new HashMap<>();
```

- [ ] **Step 4: 运行基础数据测试确认通过**

Run:

```bash
mvn -pl aps-lh -Dtest=LhBaseDataServiceImplTest#loadAllBaseDataShouldAggregateDayFinishQtyByMaterialAndLhType test
```

Expected: PASS。

- [ ] **Step 5: 更新已有基础数据测试期望并回归**

把同类旧 key 期望改为组合 key。至少调整：

```java
context.getMaterialDayFinishedQtyMap().get("3302001513__2026-05-31")
context.getMaterialMonthFinishedQtyMap().get("3302001513_")
context.getMaterialMonthDailyFinishedQtyMap().containsKey("3302001139__2026-06-11")
context.getMaterialMonthDailyFinishedQtyMap().get("3302001139__2026-06-12")
context.getMaterialMonthDailyFinishedQtyMap().get("3302001139__2026-06-13")
```

Run:

```bash
mvn -pl aps-lh -Dtest=LhBaseDataServiceImplTest test
```

Expected: PASS。

---

### Task 2: S4.3 处理器按产品状态读取 LhDayFinishQty 完成量

**Files:**
- Modify: `aps-lh/src/test/java/com/zlt/aps/lh/handler/ScheduleAdjustHandlerTest.java`
- Modify: `aps-lh/src/main/java/com/zlt/aps/lh/handler/ScheduleAdjustHandler.java`

**Interfaces:**
- Consumes:
  - `LhScheduleContext.materialMonthFinishedQtyMap` key = `materialCode_productStatus`
  - `LhScheduleContext.materialMonthFinishedQtyByMonthMap` key = `materialCode_productStatus_year_month`
  - `LhScheduleContext.materialMonthDailyFinishedQtyMap` key = `materialCode_productStatus_yyyy-MM-dd`
  - `LhScheduleContext.materialDayFinishedQtyMap` key = `materialCode_productStatus_yyyy-MM-dd`
- Produces: `SkuScheduleDTO.finishedQty`、`SkuScheduleDTO.surplusQty`、`carryForwardQtyMap` 均按当前月计划产品状态读取 `LhDayFinishQty` 来源完成量。
- Non-goal: `resolveScheDayFinishQty(context, materialCode)` 继续使用 `materialScheDayFinishQtyMap` 的物料 key。

- [ ] **Step 1: 写 S4.3 月累计完成量 RED 测试**

在 `ScheduleAdjustHandlerTest` 的 `shouldIncludeValidLastMonthOverdueQtyWhenCalculatingSurplus` 后新增：

```java
    /**
     * 用例说明：LhDayFinishQty 来源的月累计完成量必须按月计划产品状态读取，T日晚班完成量仍按物料编码读取。
     *
     * @throws Exception 反射调用异常
     */
    @Test
    public void shouldReadMonthFinishedQtyByMaterialAndProductStatus() throws Exception {
        ScheduleAdjustHandler handler = new ScheduleAdjustHandler();
        LhScheduleContext context = new LhScheduleContext();
        context.setScheduleDate(toDate(LocalDate.of(2026, 6, 18)));
        context.setScheduleTargetDate(toDate(LocalDate.of(2026, 6, 18)));

        FactoryMonthPlanProductionFinalResult plan = buildSchedulePlan("3302001575", "结构A", 100, 60, 0, 0);
        plan.setProductStatus("S");
        context.setMonthPlanList(Collections.singletonList(plan));
        context.getMaterialMonthFinishedQtyMap().put("3302001575_S", 70);
        context.getMaterialMonthFinishedQtyMap().put("3302001575_T", 15);
        context.getMaterialScheDayFinishQtyMap().put("3302001575", 20);

        invokeGatherSkuByStructure(handler, context);

        SkuScheduleDTO sku = getFirstGatheredSku(context);
        Assertions.assertEquals(90, sku.getFinishedQty(), "月累计完成量应取S状态70，再叠加T日晚班20");
        Assertions.assertEquals(20, sku.getScheduleDayFinishQty(), "T日晚班完成量仍按物料编码读取");
        Assertions.assertEquals(10, sku.getSurplusQty());
        Assertions.assertEquals(10, sku.getPendingQty());
    }
```

- [ ] **Step 2: 写历史逐日完成量 RED 测试**

在 `shouldAccumulateCurrentMonthHistoricalShortage` 后新增：

```java
    /**
     * 用例说明：本月历史欠产统计必须按月计划产品状态读取逐日完成量，不能混用同物料其他状态。
     *
     * @throws Exception 反射调用异常
     */
    @Test
    public void shouldReadMonthDailyFinishedQtyByMaterialAndProductStatus() throws Exception {
        ScheduleAdjustHandler handler = new ScheduleAdjustHandler();
        LhScheduleContext context = new LhScheduleContext();
        context.setScheduleDate(toDate(LocalDate.of(2026, 5, 3)));
        context.setScheduleTargetDate(toDate(LocalDate.of(2026, 5, 3)));
        enableCarryForwardQty(context);

        FactoryMonthPlanProductionFinalResult plan = buildSchedulePlan("3302001575", "结构A", 300, 100, 100, 100);
        plan.setProductStatus("S");
        context.setMonthPlanList(Collections.singletonList(plan));
        context.getMaterialMonthFinishedQtyMap().put("3302001575_S", 150);
        context.getMaterialMonthDailyFinishedQtyMap().put("3302001575_S_2026-05-01", 70);
        context.getMaterialMonthDailyFinishedQtyMap().put("3302001575_S_2026-05-02", 80);
        context.getMaterialMonthDailyFinishedQtyMap().put("3302001575_T_2026-05-01", 100);
        context.getMaterialMonthDailyFinishedQtyMap().put("3302001575_T_2026-05-02", 100);

        invokeAdjustPreviousSchedule(handler, context);

        Assertions.assertEquals(50, context.getCarryForwardQtyMap().get("3302001575"));
    }
```

- [ ] **Step 3: 运行 S4.3 测试确认失败**

Run:

```bash
mvn -pl aps-lh -Dtest=ScheduleAdjustHandlerTest#shouldReadMonthFinishedQtyByMaterialAndProductStatus,ScheduleAdjustHandlerTest#shouldReadMonthDailyFinishedQtyByMaterialAndProductStatus test
```

Expected: FAIL。旧代码按 `3302001575`、`3302001575_yyyy-MM-dd` 取值，新组合 key 无法命中。

- [ ] **Step 4: 实现处理器组合 key 读取**

在 `ScheduleAdjustHandler` 增加组合 key 方法：

```java
    /**
     * 构建"物料+产品状态"聚合Key。
     *
     * @param materialCode 物料编码
     * @param productStatus 产品状态
     * @return 聚合Key
     */
    private String buildMaterialStatusKey(String materialCode, String productStatus) {
        return materialCode + "_" + StringUtils.trimToEmpty(productStatus);
    }

    /**
     * 构建"物料+产品状态+日期"聚合Key。
     *
     * @param materialCode 物料编码
     * @param productStatus 产品状态
     * @param date 日期
     * @return 聚合Key
     */
    private String buildMaterialDayKey(String materialCode, String productStatus, Date date) {
        return buildMaterialStatusKey(materialCode, productStatus) + "_"
                + LhScheduleTimeUtil.formatDate(LhScheduleTimeUtil.clearTime(date));
    }
```

修改 `calculateFinishedQty`：

```java
        String materialCode = plan.getMaterialCode();
        String productStatus = plan.getProductStatus();
        if (StringUtils.isNotEmpty(materialCode)) {
            Integer monthFinishedQty = resolveMaterialMonthFinishedQty(context, plan);
            if (Objects.nonNull(monthFinishedQty)) {
                return Math.max(monthFinishedQty, 0) + resolveScheDayFinishQty(context, materialCode);
            }
            if (canFallbackToPreviousFinishedQty(context)) {
                Integer dayFinishedQty = context.getMaterialDayFinishedQtyMap().get(
                        buildMaterialDayKey(materialCode, productStatus, resolvePreviousScheduleDate(context)));
```

修改 `resolveMaterialMonthFinishedQty`：

```java
        String materialStatusKey = buildMaterialStatusKey(plan.getMaterialCode(), plan.getProductStatus());
        if (Objects.nonNull(plan.getYear()) && Objects.nonNull(plan.getMonth())
                && !CollectionUtils.isEmpty(context.getMaterialMonthFinishedQtyByMonthMap())) {
            String materialMonthKey = MonthPlanDateResolver.buildMaterialMonthKey(
                    materialStatusKey, plan.getYear(), plan.getMonth());
            Integer monthFinishedQty = context.getMaterialMonthFinishedQtyByMonthMap().get(materialMonthKey);
            if (Objects.nonNull(monthFinishedQty)) {
                return monthFinishedQty;
            }
        }
        return context.getMaterialMonthFinishedQtyMap().get(materialStatusKey);
```

修改日完成量读取方法签名和调用：

```java
    private int resolveMaterialDayFinishedQty(LhScheduleContext context,
                                              FactoryMonthPlanProductionFinalResult plan,
                                              Date finishDate) {
        if (Objects.isNull(plan) || StringUtils.isEmpty(plan.getMaterialCode()) || Objects.isNull(finishDate)) {
            return 0;
        }
        String key = buildMaterialDayKey(plan.getMaterialCode(), plan.getProductStatus(), finishDate);
        Integer dayFinishedQty = context.getMaterialDayFinishedQtyMap().get(key);
        return Objects.nonNull(dayFinishedQty) ? Math.max(dayFinishedQty, 0) : 0;
    }
```

修改历史逐日读取方法签名和调用：

```java
    private int resolveMonthDailyFinishedQty(LhScheduleContext context,
                                             FactoryMonthPlanProductionFinalResult plan,
                                             LocalDate productionDate) {
        if (Objects.isNull(context) || Objects.isNull(plan) || StringUtils.isEmpty(plan.getMaterialCode())
                || Objects.isNull(productionDate)) {
            return 0;
        }
        Integer finishedQty = context.getMaterialMonthDailyFinishedQtyMap()
                .get(buildMaterialStatusKey(plan.getMaterialCode(), plan.getProductStatus()) + "_" + productionDate);
        return Objects.nonNull(finishedQty) ? Math.max(finishedQty, 0) : 0;
    }
```

将 `calculateCurrentMonthShortageSummary` 内调用改为：

```java
            int finishedQty = resolveMonthDailyFinishedQty(context, plan, cursor);
```

保留 `resolveScheDayFinishQty(LhScheduleContext context, String materialCode)` 原样。

- [ ] **Step 5: 运行 S4.3 新增测试确认通过**

Run:

```bash
mvn -pl aps-lh -Dtest=ScheduleAdjustHandlerTest#shouldReadMonthFinishedQtyByMaterialAndProductStatus,ScheduleAdjustHandlerTest#shouldReadMonthDailyFinishedQtyByMaterialAndProductStatus test
```

Expected: PASS。

- [ ] **Step 6: 更新已有处理器测试 key 并回归**

把已有测试中直接写入 `materialMonthFinishedQtyMap` 和 `materialMonthDailyFinishedQtyMap` 的 key 调整为组合 key。现有测试构造的月计划大多没有 `productStatus`，因此空状态 key 使用双下划线形式：

```java
context.getMaterialMonthFinishedQtyMap().put("3302001575_", 230);
context.getMaterialMonthFinishedQtyMap().put("3302001575_", 70);
result.put("3302001575_" + date, qty);
```

其中 `buildMonthFinishedQtyMap` 应改为：

```java
    private Map<String, Integer> buildMonthFinishedQtyMap(Object... items) {
        Map<String, Integer> result = new LinkedHashMap<String, Integer>(8);
        for (int index = 0; index < items.length; index += 2) {
            LocalDate date = (LocalDate) items[index];
            Integer qty = (Integer) items[index + 1];
            result.put("3302001575__" + date, qty);
        }
        return result;
    }
```

Run:

```bash
mvn -pl aps-lh -Dtest=ScheduleAdjustHandlerTest test
```

Expected: PASS。

---

### Task 3: 全量定向验证与提交

**Files:**
- Verify: `aps-lh/src/test/com/zlt/aps/lh/service/impl/LhBaseDataServiceImplTest.java`
- Verify: `aps-lh/src/test/java/com/zlt/aps/lh/handler/ScheduleAdjustHandlerTest.java`
- Verify: `aps-lh/src/main/java/com/zlt/aps/lh/service/impl/LhBaseDataServiceImpl.java`
- Verify: `aps-lh/src/main/java/com/zlt/aps/lh/handler/ScheduleAdjustHandler.java`
- Verify: `aps-lh/src/main/java/com/zlt/aps/lh/context/LhScheduleContext.java`

**Interfaces:**
- Consumes: Task 1 和 Task 2 的组合 key 实现。
- Produces: 可提交的最小改动集，不包含无关文件。

- [ ] **Step 1: 运行定向测试**

Run:

```bash
mvn -pl aps-lh -Dtest=LhBaseDataServiceImplTest,ScheduleAdjustHandlerTest test
```

Expected: PASS。

- [ ] **Step 2: 检查差异范围**

Run:

```bash
git diff -- aps-lh/src/test/com/zlt/aps/lh/service/impl/LhBaseDataServiceImplTest.java \
  aps-lh/src/main/java/com/zlt/aps/lh/service/impl/LhBaseDataServiceImpl.java \
  aps-lh/src/test/java/com/zlt/aps/lh/handler/ScheduleAdjustHandlerTest.java \
  aps-lh/src/main/java/com/zlt/aps/lh/handler/ScheduleAdjustHandler.java \
  aps-lh/src/main/java/com/zlt/aps/lh/context/LhScheduleContext.java \
  aps-lh-api/src/main/java/com/zlt/aps/lh/api/domain/entity/LhDayFinishQty.java
```

Expected:
- 只包含 `LhDayFinishQty` 完成量产品状态口径相关修改。
- 不包含 XML、Mapper 或无关格式化。
- `LhScheFinishQty` 相关聚合逻辑未被改成产品状态维度。

- [ ] **Step 3: 查看工作区状态**

Run:

```bash
git status --short
```

Expected:
- 只出现本次实现相关文件。
- 如果 `LhDayFinishQty.java` 仍为用户已有改动，提交前确认是否纳入本次提交；该字段是实现依赖，通常应纳入本次改造提交，但不能覆盖已有内容。

- [ ] **Step 4: 提交**

Run:

```bash
git add aps-lh/src/test/com/zlt/aps/lh/service/impl/LhBaseDataServiceImplTest.java \
  aps-lh/src/main/java/com/zlt/aps/lh/service/impl/LhBaseDataServiceImpl.java \
  aps-lh/src/test/java/com/zlt/aps/lh/handler/ScheduleAdjustHandlerTest.java \
  aps-lh/src/main/java/com/zlt/aps/lh/handler/ScheduleAdjustHandler.java \
  aps-lh/src/main/java/com/zlt/aps/lh/context/LhScheduleContext.java \
  aps-lh-api/src/main/java/com/zlt/aps/lh/api/domain/entity/LhDayFinishQty.java
git commit -m "按产品状态区分日完成量"
```

Expected: commit succeeds。

---

## Self-Review

- Spec coverage: Task 1 覆盖 `LhDayFinishQty` 数据初始化聚合；Task 2 覆盖 `ScheduleAdjustHandler#calculateFinishedQty`、历史逐日完成量和前日 fallback 的读取口径；Task 3 覆盖定向验证和提交。
- Boundary coverage: 计划明确 `LhScheFinishQty` 不调整，`resolveScheDayFinishQty` 保持物料维度。
- Placeholder scan: 本计划没有未决占位、未定义方法或模糊步骤。
- Type consistency: `productStatus`、`lhType`、`materialDayFinishedQtyMap`、`materialMonthDailyFinishedQtyMap`、`materialMonthFinishedQtyMap`、`materialMonthFinishedQtyByMonthMap` 与当前代码命名一致。
