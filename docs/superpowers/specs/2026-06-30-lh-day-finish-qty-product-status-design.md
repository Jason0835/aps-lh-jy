# LhDayFinishQty 完成量按产品状态区分设计

## 1. 明确结论

本次推荐采用组合 key 方案：将 `LhDayFinishQty` 来源的月累计完成量和指定日期日完成量，从按物料编码汇总调整为按“物料编码 + 产品状态”汇总。

月计划侧使用 `FactoryMonthPlanProductionFinalResult.productStatus`，完成量侧使用 `LhDayFinishQty.lhType`。同一物料、同一产品状态存在多条完成量记录时继续累加汇总。

`LhScheFinishQty` 来源的 T 日晚班 `class1FinishQty` 不纳入本次变更，继续保持现有按物料编码汇总口径。

## 2. 改造目标

解决同一物料编码存在多个产品状态时，`LhDayFinishQty` 完成量被按物料维度混算的问题。

调整后，硫化余量、历史欠产、日计划账本扣减等依赖 `LhDayFinishQty` 的逻辑，只能读取与当前月计划产品状态一致的完成量，避免正规示方、量试示方、试制示方互相抵扣。

## 3. 现有逻辑影响点

### 入口方法

- `com.zlt.aps.lh.handler.ScheduleAdjustHandler#calculateFinishedQty`

### 数据初始化逻辑

- `com.zlt.aps.lh.service.impl.LhBaseDataServiceImpl#loadDayFinishQty`
- `com.zlt.aps.lh.service.impl.LhBaseDataServiceImpl#loadMaterialMonthFinishedQty`
- `com.zlt.aps.lh.service.impl.LhBaseDataServiceImpl#mergeMaterialMonthFinishedQty`
- `com.zlt.aps.lh.service.impl.LhBaseDataServiceImpl#buildMonthPlanMaterialFinishedQtyMap`
- `com.zlt.aps.lh.service.impl.LhBaseDataServiceImpl#appendMonthFinishedQtyByMonth`

### 核心排程逻辑

- `com.zlt.aps.lh.handler.ScheduleAdjustHandler#resolveMaterialMonthFinishedQty`
- `com.zlt.aps.lh.handler.ScheduleAdjustHandler#resolveMaterialDayFinishedQty`
- `com.zlt.aps.lh.handler.ScheduleAdjustHandler#buildMaterialDayKey`

### 上下文数据

- `LhScheduleContext.materialDayFinishedQtyMap`
- `LhScheduleContext.materialMonthDailyFinishedQtyMap`
- `LhScheduleContext.materialMonthFinishedQtyMap`
- `LhScheduleContext.materialMonthFinishedQtyByMonthMap`

### 表与字段

- `T_LH_DAY_FINISH_QTY.MATERIAL_CODE`
- `T_LH_DAY_FINISH_QTY.LH_TYPE`
- `T_LH_DAY_FINISH_QTY.DAY_FINISH_QTY`
- `FactoryMonthPlanProductionFinalResult.PRODUCT_STATUS`

本次不新增 Mapper，不新增 XML，不调整数据库表结构。

## 4. 设计思路

保留当前“基础数据初始化阶段批量查询，内存 Map 聚合，下游处理器按 key 读取”的结构，只扩展 key 的业务维度。

`LhDayFinishQty` 查询条件仍保持工厂、完成日期范围、删除标识，不在 SQL 层逐个产品状态查询，避免增加查询次数。查询结果进入内存后按 `materialCode + productStatus/lhType` 汇总。

建议新增统一 key 构造方法，避免各处手写字符串拼接：

- 月累计完成量 key：`materialCode + "_" + productStatus`
- 日完成量 key：`materialCode + "_" + productStatus + "_" + yyyy-MM-dd`
- 跨月完成量 key：基于组合后的物料状态 key 再拼年月

产品状态为空时只匹配 `lhType` 为空的完成量，不回退到仅物料维度。否则会继续引入串量问题。

## 5. 详细实施步骤

1. 在 `LhBaseDataServiceImpl` 中新增或调整私有 key 构造方法，支持 `materialCode + lhType + date`、`materialCode + lhType`。
2. 修改 `loadDayFinishQty`，使用 `finishQty.getMaterialCode()` 和 `finishQty.getLhType()` 构造日完成量 key，多条记录通过 `merge` 汇总。
3. 修改 `mergeMaterialMonthFinishedQty`，月累计完成量按 `finishQty.getMaterialCode()` 和 `finishQty.getLhType()` 汇总。
4. 修改 `materialMonthDailyFinishedQtyMap` 的逐日完成量 key，保持与指定日期日完成量相同口径。
5. 修改 `buildMonthPlanMaterialFinishedQtyMap`，按月计划 `materialCode + productStatus` 初始化 0，避免同物料其他状态完成量被误用。
6. 修改 `appendMonthFinishedQtyByMonth`，让跨月 key 保留产品状态维度。
7. 修改 `ScheduleAdjustHandler#resolveMaterialMonthFinishedQty`，使用月计划 `materialCode + productStatus` 读取月累计完成量。
8. 修改 `ScheduleAdjustHandler#calculateFinishedQty` 和 `resolveMaterialDayFinishedQty`，fallback 日完成量读取也使用 `materialCode + productStatus + date`。
9. 保持 `resolveScheDayFinishQty` 和 `materialScheDayFinishQtyMap` 不变，T 日晚班 `class1FinishQty` 仍按物料编码读取。
10. 同步更新相关字段注释和关键日志，使排查时能看到产品状态维度。
11. 补充定向单元测试，覆盖同物料不同产品状态不串量、同状态多条完成量汇总。

## 6. 数据处理说明

月累计完成量仍由 `T_LH_DAY_FINISH_QTY` 在目标月份起始日至截止日范围内的记录汇总。汇总维度从原来的 `MATERIAL_CODE` 改为 `MATERIAL_CODE + LH_TYPE`。

指定日期日完成量仍由 `T_LH_DAY_FINISH_QTY` 在指定日期自然日范围内的记录汇总。汇总维度从原来的 `MATERIAL_CODE + FINISH_DATE` 改为 `MATERIAL_CODE + LH_TYPE + FINISH_DATE`。

排程计算时，月计划 `PRODUCT_STATUS` 与完成量 `LH_TYPE` 精确匹配。未匹配到记录时完成量按 0 处理，不做仅物料维度 fallback。

## 7. 边界场景

- 同一物料存在 `S` 和 `T` 两种完成量，正规计划只读取 `S`，量试计划只读取 `T`。
- 同一物料同一状态有多条完成量，完成量累加。
- 月计划有产品状态，但完成量记录 `LH_TYPE` 为空，不匹配。
- 月计划产品状态为空，但完成量记录 `LH_TYPE` 非空，不匹配。
- 月计划产品状态为空，完成量记录 `LH_TYPE` 也为空，按空状态匹配。
- `LhScheFinishQty.class1FinishQty` 继续按物料编码汇总，不受本次变更影响。

## 8. 风险点

已有历史数据如果没有维护 `LH_TYPE`，改造后对应月计划状态将无法命中完成量，硫化余量可能比旧逻辑更大。这是本次产品状态精确匹配带来的业务口径变化，不建议增加仅物料编码兜底。

部分既有单元测试可能只构造物料编码，不构造产品状态或示方类型，需要按新业务口径补齐测试数据。

## 9. 验证建议

优先执行定向测试：

```bash
mvn -pl aps-lh -Dtest=LhBaseDataServiceImplTest,ScheduleAdjustHandlerTest test
```

如需真实排程复核，再按指定业务日期启动应用并调用：

```bash
curl -X POST 'http://localhost:9669/lhScheduleResult/execute' \
  -H 'Content-Type: application/json' \
  -d '{"factoryCode":"116","scheduleDate":"需填写排程日期"}'
```
