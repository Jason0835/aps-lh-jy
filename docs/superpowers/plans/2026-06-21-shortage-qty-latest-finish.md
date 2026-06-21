# 仅历史欠产 SKU 最近一次完成量跳过规则实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (\`- [ ]\`) syntax for tracking.

**Goal:** 将仅历史欠产且后续无计划 SKU 的跳过依据，从固定 T-1 日完成量改为当前月月初至 T-1 最近一次非空日完成量。

**Architecture:** 复用基础数据初始化阶段已有的 materialMonthDailyFinishedQtyMap，不新增数据库查询。在 S4.5 新增排产前从 T-1 倒序查至月初，首个非空完成量即为判断依据；命中后复用现有未排写入和队列移除链路。

**Tech Stack:** Java 8、Spring Boot、MyBatis-Plus、JUnit 5、Mockito、OpenSpec。

## Global Constraints

- 始终使用简体中文注释、日志、规格和提交说明。
- 优先复用现有类、方法、上下文 Map 和未排落库链路，禁止大范围重构。
- 不新增 fallback、兜底逻辑、第三方依赖、Mapper XML 或特殊业务分支。
- 最近一次完成量范围严格限定为当前月月初至 T-1。
- DAY_FINISH_QTY=0 是有效最近数据；DAY_FINISH_QTY=NULL 不属于非空完成量。
- 不影响续作、换模、换活字块、胎胚库存、日计划扣减和机台匹配逻辑。
- 实现完成后必须同步 change spec 和主 spec。

---

### Task 1: 以失败测试锁定最近一次非空完成量行为

**Files:**
- Modify: aps-lh/src/test/com/zlt/aps/lh/regression/NewSpecProductionStrategyRegressionTest.java
- Modify: aps-lh/src/main/java/com/zlt/aps/lh/engine/strategy/impl/NewSpecProductionStrategy.java

**Interfaces:**
- Consumes: LhScheduleContext#getMaterialMonthDailyFinishedQtyMap()，键为 materialCode_yyyy-MM-dd。
- Produces: private Integer resolveLatestPreviousFinishedQty(LhScheduleContext context, String materialCode)。

- [ ] **Step 1: 写入 T-1 无记录、T-2 大于 0 时跳过的失败测试**

在现有历史欠产跳过用例附近新增：

~~~java
@Test
void scheduleNewSpecs_shouldSkipHistoryShortageOnlySkuWhenLatestPreviousFinishedQtyIsPositive()
        throws Exception {
    NewSpecProductionStrategy strategy = new NewSpecProductionStrategy();
    injectDependencies(strategy, false);
    LhScheduleContext context = buildContext();
    Date scheduleDate = dateTime(2026, 6, 14, 0, 0);
    context.setScheduleDate(scheduleDate);
    context.setScheduleTargetDate(scheduleDate);
    context.setScheduleWindowShifts(
            LhScheduleTimeUtil.buildDefaultScheduleShifts(context, scheduleDate));
    SkuScheduleDTO sku = buildHistoryShortageOnlySku(context, "3302001139", 64);
    context.getNewSpecSkuList().add(sku);
    context.getMaterialMonthDailyFinishedQtyMap()
            .put("3302001139_2026-06-12", 32);

    strategy.scheduleNewSpecs(context,
            singletonMachineMatch(buildMachine("K1105", dateTime(2026, 6, 14, 6, 0))),
            defaultMouldChangeBalance(), defaultInspectionBalance(),
            defaultCapacityCalculate());

    assertTrue(context.getScheduleResultList().isEmpty());
    assertEquals(1, context.getUnscheduledResultList().size());
    assertEquals("仅历史欠产、后续无月计划，且最近一次（前一次）已有完成量，本次跳过不排",
            context.getUnscheduledResultList().get(0).getUnscheduledReason());
}
~~~

测试辅助方法复用现有历史欠产 SKU 构造字段并抽成同测试类私有方法，不新建测试类。

- [ ] **Step 2: 运行测试并确认失败**

Run: mvn -pl aps-lh -Dtest=com.zlt.aps.lh.regression.NewSpecProductionStrategyRegressionTest#scheduleNewSpecs_shouldSkipHistoryShortageOnlySkuWhenLatestPreviousFinishedQtyIsPositive test

Expected: FAIL，SKU 仍进入排程结果或未产生指定未排原因。

- [ ] **Step 3: 增加两个失败用例**

新增 scheduleNewSpecs_shouldUseLatestZeroFinishedQtyInsteadOfEarlierPositiveQty：2026-06-13=0、2026-06-12=32，应以最近一次 0 为准并继续原排产逻辑。

新增 scheduleNewSpecs_shouldNotUsePreviousMonthFinishedQty：T=2026-06-01，仅设置 2026-05-31=32，不得跨月命中规则。

- [ ] **Step 4: 实现最小倒序解析**

~~~java
private Integer resolveLatestPreviousFinishedQty(LhScheduleContext context,
                                                  String materialCode) {
    if (Objects.isNull(context) || Objects.isNull(context.getScheduleDate())
            || StringUtils.isEmpty(materialCode)
            || CollectionUtils.isEmpty(context.getMaterialMonthDailyFinishedQtyMap())) {
        return null;
    }
    LocalDate scheduleDate = context.getScheduleDate().toInstant()
            .atZone(ZoneId.systemDefault()).toLocalDate();
    LocalDate monthStart = scheduleDate.withDayOfMonth(1);
    for (LocalDate date = scheduleDate.minusDays(1);
         !date.isBefore(monthStart); date = date.minusDays(1)) {
        Integer finishedQty = context.getMaterialMonthDailyFinishedQtyMap()
                .get(materialCode + "_" + date);
        if (Objects.nonNull(finishedQty)) {
            return Math.max(finishedQty, 0);
        }
    }
    return null;
}
~~~

调用处仅在返回值非空且大于 0 时跳过；同步更新未排原因、中文注释和 info 日志字段，不增加排程结果 fallback。

- [ ] **Step 5: 运行三个定向测试**

Run: mvn -pl aps-lh -Dtest=com.zlt.aps.lh.regression.NewSpecProductionStrategyRegressionTest#scheduleNewSpecs_shouldSkipHistoryShortageOnlySkuWhenLatestPreviousFinishedQtyIsPositive+scheduleNewSpecs_shouldUseLatestZeroFinishedQtyInsteadOfEarlierPositiveQty+scheduleNewSpecs_shouldNotUsePreviousMonthFinishedQty test

Expected: 3 tests run，0 failures，0 errors。

- [ ] **Step 6: 中文提交**

提交说明：修复：按最近一次完成量跳过历史欠产排产

### Task 2: 保证基础数据 Map 只记录非空完成量

**Files:**
- Modify: aps-lh/src/test/com/zlt/aps/lh/service/impl/LhBaseDataServiceImplTest.java
- Modify: aps-lh/src/main/java/com/zlt/aps/lh/service/impl/LhBaseDataServiceImpl.java

**Interfaces:**
- Consumes: LhDayFinishQty#getDayFinishQty()。
- Produces: materialMonthDailyFinishedQtyMap 中保留 0，排除 NULL；月累计完成量语义不变。

- [ ] **Step 1: 写入空值排除、0 值保留的失败测试**

模拟三条本月数据：6月11日 NULL、6月12日 0、6月13日 32，调用 loadAllBaseData 后断言：

~~~java
Assertions.assertFalse(context.getMaterialMonthDailyFinishedQtyMap()
        .containsKey("3302001139_2026-06-11"));
Assertions.assertEquals(Integer.valueOf(0),
        context.getMaterialMonthDailyFinishedQtyMap()
                .get("3302001139_2026-06-12"));
Assertions.assertEquals(Integer.valueOf(32),
        context.getMaterialMonthDailyFinishedQtyMap()
                .get("3302001139_2026-06-13"));
~~~

- [ ] **Step 2: 运行测试并确认失败**

Run: mvn -pl aps-lh -Dtest=com.zlt.aps.lh.service.impl.LhBaseDataServiceImplTest#loadAllBaseDataShouldExcludeNullAndKeepZeroMonthDailyFinishedQty test

Expected: FAIL，6月11日空完成量当前被转换为 0 并写入 Map。

- [ ] **Step 3: 最小修改逐日 Map 聚合**

保持 materialMonthFinishedQtyMap.merge 原逻辑不变，仅在完成量字段非空时写入逐日 Map：

~~~java
if (Objects.nonNull(finishQty.getDayFinishQty())) {
    materialMonthDailyFinishedQtyMap.merge(
            buildMaterialDayKey(finishQty.getMaterialCode(),
                    LhScheduleTimeUtil.clearTime(finishQty.getFinishDate())),
            resolveDayFinishedQty(finishQty),
            Integer::sum);
}
~~~

- [ ] **Step 4: 运行基础数据与历史欠产统计测试**

Run: mvn -pl aps-lh -Dtest=com.zlt.aps.lh.service.impl.LhBaseDataServiceImplTest#loadAllBaseDataShouldExcludeNullAndKeepZeroMonthDailyFinishedQty,com.zlt.aps.lh.handler.ScheduleAdjustHandlerTest test

Expected: 全部通过，月累计和历史欠产计算无回归。

- [ ] **Step 5: 中文提交**

提交说明：修复：区分日完成量空值与零值

### Task 3: 同步 OpenSpec 并完成验证

**Files:**
- Modify: openspec/changes/shortage-qty-twice‌-online/spec.md
- Modify: openspec/specs/shortage-qty-twice‌-online/spec.md

**Interfaces:**
- Consumes: Task 1、Task 2 已实现的最终业务口径。
- Produces: 中文最新 change spec 和主 spec。

- [ ] **Step 1: 更新两份规格**

将固定“前日日完成量”统一改成“当前月月初至 T-1 最近一次非空日完成量”，明确 0 是有效最近数据、空值继续回溯、不跨月、命中后写未排、续作补偿不受影响，并更新未排原因。

- [ ] **Step 2: 执行完整定向回归**

Run: mvn -pl aps-lh -Dtest=com.zlt.aps.lh.regression.NewSpecProductionStrategyRegressionTest,com.zlt.aps.lh.service.impl.LhBaseDataServiceImplTest,com.zlt.aps.lh.handler.ScheduleAdjustHandlerTest test

Expected: 0 failures，0 errors。

- [ ] **Step 3: 执行编译、规格和差异校验**

Run: mvn -pl aps-lh -am -DskipTests compile

Run: openspec validate shortage-qty-twice-online --strict

Run: git diff --check

Expected: 编译通过；OpenSpec 若因目录零宽字符拒绝变更名，记录工具限制并人工核对两份 spec；git diff --check 无输出。

- [ ] **Step 4: 真实复跑并查库闭环**

启动服务后调用：

~~~bash
curl --noproxy '*' -X POST 'http://localhost:9669/lhScheduleResult/execute' \
  -H 'Content-Type: application/json' \
  -d '{"factoryCode":"116","scheduleDate":"2026-06-14"}'
~~~

按最新批次核对：T_LH_SCHEDULE_RESULT 中物料 3302001139 为 0 条；T_LH_UNSCHEDULED_RESULT 中为 1 条且原因正确；过程日志包含 SKU、欠产量、排程日期、最近一次日完成量和跳过原因。

- [ ] **Step 5: 中文提交**

提交说明：文档：更新二次上机最近完成量规则
