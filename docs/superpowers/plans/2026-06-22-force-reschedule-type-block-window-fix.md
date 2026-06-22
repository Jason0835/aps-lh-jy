# 强制重排换活字块窗口修复实施计划

> **执行要求：** 按 Superpowers TDD 流程逐项完成，先验证失败用例，再修改生产代码。

**目标：** 修复强制重排时机台沿用窗口外旧结束时间的问题，并在模具交替计划生成后仅记录窗口外计划日志，不中断排程。

**架构：** 在机台运行态初始化源头将强制重排模式下早于窗口首班的旧结束时间归一到窗口起点，后续续作、换活字块、换模和机台匹配继续复用现有时间链路。模具交替计划生成完成后增加只读日志扫描，保留全部计划，不抛异常、不删除数据。

**技术栈：** Java 8、Spring Boot 2.7、JUnit 5、MyBatis-Plus。

## 全局约束

- 不新增 SKU、机台或日期特例。
- 不修改 SQL、XML、配置项和事务边界。
- 模具交替计划窗口外检查只打印日志，不中断排程。
- 关键代码和日志使用简体中文。

---

### 任务一：强制重排机台结束时间归一化

**文件：**
- 修改：`aps-lh/src/test/java/com/zlt/aps/lh/handler/DataInitHandlerTest.java`
- 修改：`aps-lh/src/main/java/com/zlt/aps/lh/handler/DataInitHandler.java`

- [x] 新增失败用例：MES 在机物料与前批次一致，但旧结束时间早于窗口首班时，强制重排必须返回窗口首班开始时间。
- [x] 运行 `mvn -pl aps-lh -Dtest=DataInitHandlerTest test`，确认用例因仍返回旧结束时间而失败。
- [x] 在 `resolveInitialEstimatedEndTime` 中仅对强制重排的窗口外旧时间做归一化，并记录原始时间和归一化时间。
- [x] 再次运行定向测试，确认通过。

### 任务二：模具交替计划窗口外日志

**文件：**
- 修改：`aps-lh/src/test/com/zlt/aps/lh/regression/ResultValidationHandlerLeftRightMouldRegressionTest.java`
- 修改：`aps-lh/src/main/java/com/zlt/aps/lh/handler/ResultValidationHandler.java`

- [x] 新增失败用例：窗口外计划仍保留，且生成后打印包含批次、机台、物料、计划时间和窗口边界的警告日志。
- [x] 运行定向日志用例，确认因缺少日志而失败。
- [x] 在 `generateMouldChangePlan` 完成后扫描计划时间，仅打印警告日志，不抛异常、不移除计划。
- [x] 再次运行定向测试，确认通过。

### 任务三：回归与真实复跑

**文件：** 不新增文件。

- [x] 运行两个定向测试类及相关强制重排回归测试。
- [x] 执行 `git diff --check` 和 `git status --short`，确认无格式问题和临时文件。
- [x] 启动应用并复跑 `2026-06-21`。
- [x] 对账 K1401/3302002182：C1 为 0、C2 开始生产，模具交替计划仅落在 2026-06-20 至 2026-06-22。
