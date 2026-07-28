# aps-lh-parent 项目长期记忆

## 项目概况
- 硫化排程（APS）系统，多模块 Maven 工程。
- 技术栈：Java 8 + Spring Boot + MyBatis-Plus + Hutool。
- 主模块 `aps-lh`，API 模块 `aps-lh-api`。
- 启动类入口位于 `aps-lh` 模块，端口默认 9669。

## 角色与规范
- 在本项目需同时承担：硫化排程业务专家、排程算法专家、资深 Java 后端。
- 完整编码规范、业务改造要求、SQL 规范见根目录 `AGENTS.md`（以该文件为权威依据，本笔记仅补充实践中确认的关键点）。

## 常用命令
- 启动：`mvn spring-boot:run -pl aps-lh -Dmaven.test.skip=true`
- 排程执行验证：`POST /lhScheduleResult/execute`，body `{"factoryCode":"116","scheduleDate":"..."}`
- 接口示例：`curl -X POST 'http://localhost:9669/lhScheduleResult/execute' -H 'Content-Type: application/json' -d '{"factoryCode":"116","scheduleDate":"排程日期"}'`

## 排程核心概念（速查）
月计划 / 日计划 / 机台 / 模具 / 胎胚 / SKU / 续作 / 换模 / 换活字块 / 滚动排程 / 满产排程 / 欠产 / 开停产。
修改排程算法须同步检查相关入口，禁止单点改动导致前后逻辑不一致。

---
## 历史故障根因速查（详见 故障根因速查.md）
- 完成量异常：月完成量 key 用 日LH_TYPE 拼 月PRODUCT_STATUS（语义不同），不一致则月累计=0。查 calculateSurplusQty 日志 monthFinishedAndScheDayQty；重启应用刷新 context 验证。
- 试制量试未排：根因 evaluateDailyPlanAdmission 试制分支保留 hasPreviousT1MouldChangePlan 放行口；修复 PendingSkuUnscheduledRule 跳过T+1 + NewSpecProductionStrategy 保留零量未排记录。应用固定端口 9669。
- 超排：真实根因为 applySharedEmbryoEndingStaggerPostpone（共用胎胚收尾错峰后延）末班追加产量；关 ENDING_AUTO_FILL_ENABLED 即可。须日志实证，勿误判 roundUpQtyToMouldMultiple。
- 首检班次失败(S4505)：候选机台换模完成时间超窗→首检无归属班次→回滚。默认 MAX_FIRST_INSPECTION_PER_SHIFT=-1 不限量。
- 加机台时机：续作机台<CEIL(dayQty/班产) 应生成补偿SKU进S4.5新增换模；推迟常因续作补偿未生成而走换活字块抢占。
- 续作空班：停产保机语义为当日全部班次=0（ContinuousProductionStrategy:6420 触发）；ResultDowntimeSummaryUtil 未覆盖停产保机备注，空班原因需统一可追溯。
- 按天驱动改造：distributeToShifts 按 shiftIndex 增量合并，跨天续写安全。
- 日标准量修正：参数 SYS0304031 按结构门控；未命中结构时跳过 calculateDailyStandardShiftCapacityMap 冗余计算。

（后续实践确认的内容追加在下方）
