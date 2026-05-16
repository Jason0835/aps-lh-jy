# SKU 多机台动态分配计划量方案

## 1. 明确结论

本次采用“新增规格 S4.5 主链动态拆机 + 续作 S4.4 不足转新增规格补偿”的最小改动方案。主流程仍保持 SKU 连续排 8 班，不新建并行排程流程，不改 SQL、XML 和表结构。

核心结论：

- 正式 / 量试非收尾：按窗口总计划量作为最低目标，允许最后一台机台最后一个已开班班次补满导致小幅超排。
- 试制非收尾：按窗口总计划量严格截断，禁止补满班次导致超排。
- 收尾：按 `max(余量, 胎胚库存)` 严格截断，禁止补满班次导致超排。
- 同胎胚换模按 `embryoCode + 班次索引` 错开，`embryoCode` 为空时不退化为 `materialCode`。
- 续作先走原 S4.4 链路，续作机台排不足时把剩余目标量复制为新增规格补偿 SKU，继续交给 S4.5 找换模机台补量。

## 2. 改造目标

本次解决的问题是：一个 SKU 在窗口目标量超过单台机台可排能力时，不能只选择单台机台排产，也不能因为补满班次破坏试制和收尾的严格目标量约束。

改造后的目标能力：

- 支持正式 / 量试非收尾 SKU 在多台机台上动态补量。
- 非最后机台一旦开产，排满到窗口结束，保持该机台生产段连续。
- 最后一台机台只排到满足窗口目标量；正式 / 量试补满最后已开班班次，试制 / 收尾严格截断。
- 支持同胎胚换模班次错开，避免多台机台同班次抢同胎胚换模。
- 支持 day1 / day2 / day3 欠产在窗口内滚动追补，最终以窗口累计目标量为硬目标。
- 结果保存前做数量策略和同胎胚换模冲突校验，避免异常结果落库。

## 3. 现有逻辑影响点

### 入口与主流程

- `NewSpecProductionStrategy`：新增规格 S4.5 主排产入口，本次多机台动态补量、机台角色判断、同胎胚换模错开均接在这里。
- `ContinuousProductionStrategy`：续作 S4.4 主排产入口，本次在续作日计划账本同步后追加新增规格补偿 SKU。
- `ResultValidationHandler`：S4.6 结果后置校验入口，本次增加数量策略校验和同胎胚换模班次冲突校验。

### 目标量与日计划账本

- `TargetScheduleQtyResolver`：收尾目标量解析改为保留 `max(余量, 胎胚库存)`，不再被窗口可用产能反向压低。
- `SkuDailyPlanQuotaDTO` / `SkuDailyPlanQuotaUtil`：继续复用现有日计划额度账本，不新增 `DayQuotaTracker`。

### 排程上下文

- `LhScheduleContext`：新增 `greenTireChangeoverShiftMap`，记录同胎胚已占用换模班次。
- `scheduleResultSourceSkuMap`：作为结果行与来源 SKU 的运行态映射，支撑 S4.6 按 SKU 汇总校验。

### 未改动点

- 不修改 Mapper / XML / 表结构。
- 不修改月计划分配和月计划扣减表结构。
- 不全局修改 `sku.isTrial()` 语义，避免影响试制 / 量试机台优先级等既有逻辑。

## 4. 设计思路

### 4.1 数量策略对象

新增 `ProductionQuantityPolicy`，统一封装数量口径：

- `constructionStage = 01`：试制，严格上限，不允许补满班次，不启用非最后机台满排。
- `constructionStage = 02`：量试，按正式数量口径处理，允许最后已开班班次补满。
- 正式 / 非试制：按正式数量口径处理。
- 收尾：严格上限，不允许补满班次。

### 4.2 机台角色

新增 `MachineScheduleRole`：

- `FULL_RUN_MACHINE`：`已排量 + 当前机台开产到窗口末最大可排量 < 窗口目标量`。
- `TAIL_MACHINE`：当前机台足以补齐窗口目标量。

新增 `MachineProductionSegment` 保存机台排产段信息，包括机台、物料、胎胚、换模班次、开产班次、最大可排量、班产和角色。

### 4.3 新增规格多机台动态补量

S4.5 对每个候选机台先计算从开产时间到窗口结束的最大可排量：

- 如果是 `FULL_RUN_MACHINE`：本机台计划量取最大可排量，开产后排满窗口后续可用班次。
- 如果是 `TAIL_MACHINE`：本机台计划量取剩余目标量；正式 / 量试向上取整到班产，试制 / 收尾按剩余目标量严格截断。

每台机台排产后累加已排量，未达到目标继续找下一台机台。达到目标后停止，不再开启后续机台或后续班次。

### 4.4 续作不足补偿

S4.4 保持原续作链路先排当前续作机台。日计划账本同步后按来源 SKU 汇总续作已排量：

- 如果续作已排量不足窗口目标量，且日计划账本仍有剩余额度，则复制一个新增规格补偿 SKU。
- 补偿 SKU 共用原 SKU 的日计划账本，`scheduleType` 改为新增规格，`continuousMachineCode` 清空。
- 后续由 S4.5 按新增规格换模逻辑继续找机台补量。

### 4.5 同胎胚换模错开

同胎胚换模使用 `embryoCode` 作为分组 key：

- 分配换模后，按换模开始时间解析班次索引。
- 如果 `greenTireChangeoverShiftMap` 中同 `embryoCode` 已占用该班次，则回滚本次换模占用。
- 探测时间顺延到冲突班次结束时间，再重新分配。
- 探测次数限制为 `MAX_SHIFT_SLOT_COUNT * 2`，避免死循环。

### 4.6 结果校验

S4.6 保存前增加两类校验：

- 同胎胚换模校验：同一 `embryoCode` 同一班次只能有一次换模。
- 数量策略校验：
  - 严格上限 SKU：实际排产量不得超过目标量。
  - 正式 / 量试非收尾：超排量必须小于一个班产。
  - 正式 / 量试非收尾：实际排产量低于目标量且没有未排记录时阻断保存。

## 5. 详细实施步骤

1. 新增 `ProductionQuantityPolicy`、`MachineScheduleRole`、`MachineProductionSegment`。
2. 在 `LhScheduleContext` 增加 `greenTireChangeoverShiftMap`。
3. 调整 `TargetScheduleQtyResolver` 收尾目标量口径，保留 `max(余量, 胎胚库存)`。
4. 在 `NewSpecProductionStrategy` 中接入数量策略、动态目标量、机台角色判断和多机台循环补量。
5. 在 `NewSpecProductionStrategy` 中增加同胎胚换模错开和回滚逻辑。
6. 在 `NewSpecProductionStrategy` 中登记结果来源 SKU，用于最终结果校验。
7. 在 `ContinuousProductionStrategy` 中保留原续作排产逻辑，日计划账本同步后追加新增规格补偿 SKU。
8. 在 `ContinuousProductionStrategy` 中按数量策略调整正式 / 量试和试制 / 收尾的回裁逻辑。
9. 在 `ResultValidationHandler` 中增加同胎胚换模冲突校验和数量策略校验。
10. 更新新增规格、续作、目标量解析、结果校验相关单元测试。

## 6. 数据处理说明

- 非收尾目标量来自 `day1 + day2 + day3`，即 `windowPlanQty` 和日计划账本累计量。
- 收尾目标量来自 `max(surplusQty, embryoStock)`，不再被窗口可用产能压低。
- 多机台拆量过程中，SKU 原始目标量保持不变，每台机台只临时写入本机台计划量，排完后恢复。
- 日计划账本继续由 `SkuDailyPlanQuotaUtil` 扣减，允许 day1 欠产滚动到 day2 / day3 追补。
- 正式 / 量试的满班补齐超排记录到 `skuShiftFillOverQtyMap`，供最终日志汇总。
- 续作补偿 SKU 与来源 SKU 共用 `dailyPlanQuotaMap`，避免 S4.4 与 S4.5 重复消耗同一日计划额度。

## 7. 边界场景

- 正式非收尾单机台足够：直接作为 `TAIL_MACHINE`，按目标量向上补满最后已开班班次，后续班次不再开产。
- 正式非收尾第一台不足：第一台作为 `FULL_RUN_MACHINE` 排满窗口后续班次，再找下一台补量。
- 试制非收尾：严格按目标量截断，最后班次不补满。
- 收尾胎胚库存大于余量：目标量取胎胚库存，严格截断。
- 收尾胎胚库存小于等于余量：目标量取余量，严格截断。
- 同胎胚换模冲突：后分配机台顺延换模班次，无法顺延时记录失败原因并换候选机台。
- 续作机台不足：续作结果先保留，再生成新增规格补偿 SKU 找换模机台补量。

## 8. 风险点

- 正式 / 量试非收尾由“优先找单机台满足剩余量”改为“候选排序后动态判断满排或尾排”，同一 SKU 的机台分布会发生变化。
- 同胎胚换模错开会顺延后续机台开产时间，可能让部分原本可排班次变为空班。
- 结果校验依赖 `scheduleResultSourceSkuMap`，本次已在新增规格和续作链路登记来源 SKU；其它历史结果若没有来源 SKU，数量策略校验会跳过，避免误拦截非本链路结果。
- 正式 / 量试低于目标量但存在未排记录时允许保存，避免把真实产能不足误判为算法异常。

## 9. 验证建议

建议继续按三层验证：

1. 单元测试验证策略口径、动态拆机、续作补偿、收尾目标量和结果校验。
2. `test-compile` 验证主模块编译面。
3. 本地启动 `aps-lh` 后请求 `POST /lhScheduleResult/execute`，使用 `{"factoryCode":"116","scheduleDate":"2026-05-03"}`，查询 `T_LH_SCHEDULE_RESULT` 中 `MATERIAL_CODE='3302001724'` 的机台、班次计划量、`DAILY_PLAN_QTY` 和 `SPEC_END_TIME`。

## 10. 本次验证结果

已完成验证：

```bash
/Users/Jason/Software/apache-maven-3.9.1/bin/mvn -pl aps-lh -am -Dtest=NewSpecProductionStrategyTest,ContinuousProductionStrategyTest,ProductionQuantityPolicyTest,TargetScheduleQtyResolverRegressionTest,ResultValidationBlockingTest,SkuDailyPlanQuotaUtilRegressionTest -DfailIfNoTests=false test
```

结果：通过，`Tests run: 38, Failures: 0, Errors: 0, Skipped: 0`。

```bash
/Users/Jason/Software/apache-maven-3.9.1/bin/mvn -pl aps-lh -am test-compile
```

结果：通过。

```bash
git diff --check
```

结果：通过。

真实接口验证：

```bash
curl --noproxy '*' -sS -X POST 'http://127.0.0.1:9669/lhScheduleResult/execute' \
  -H 'Content-Type: application/json' \
  -d '{"factoryCode":"116","scheduleDate":"2026-05-03"}'
```

结果：接口已启动并请求成功到达服务端，但当前本地数据库在 S4.2 初始化阶段中断，原因是 `T_LH_MOULD_CHANGE_PLAN` 缺少既有实体字段 `END_TYPE`：

```text
Unknown column 'END_TYPE' in 'field list'
SQL: SELECT ... END_TYPE ... FROM T_LH_MOULD_CHANGE_PLAN
```

因此本次真实接口未能进入最终落库验收。该问题属于本地数据库结构与当前实体不一致，不是本次多机台动态分配改动引入的 SQL / XML 变化；代码级验证已覆盖 7 个验收场景对应的核心规则。
