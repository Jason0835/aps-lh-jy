## Context

当前硫化排程已经具备三段可复用主链：

- `ScheduleAdjustHandler` 基于 `machineOnlineInfoMap` 将 SKU 划分为续作和新增，并给续作 SKU 绑定 `continuousMachineCode`。
- `ContinuousProductionStrategy` 负责 S4.4 续作排产，未消费完的续作缺口可通过现有补偿逻辑回流到 S4.5。
- `NewSpecProductionStrategy` 与 `DefaultMachineMatchStrategy` 负责新增排产排序、机台过滤、换模约束和多机台扩机。

当前缺口不是“不会识别 MES 在机机台”，而是“缺少长期停机的统一禁排口径”。现有 `DefaultMachineMatchStrategy#hasPlanStopExceededTimeout(...)` 只处理计划停机窗口，且属于“有替代机台时才降级”的软过滤；本次需求要求的是基于 MES 在机数据的硬禁排，并且要让受影响续作 SKU 直接回到已有新增主链重新排序。

## Goals / Non-Goals

**Goals:**

- 基于 MES 最近在机记录批量识别“停机超过 24 小时”的机台，并在一次排程上下文内复用该结果。
- 将命中机台加入统一不可用机台集合，确保续作和新增选机都不会再使用这些机台。
- 对原本依赖该机台续作的 SKU，不保留续作身份特权，而是直接进入现有新增待排队列并复用原排序入口。
- 保留现有换模、单控、模具、晚班不可换模、same-SKU、多机台扩机等后续规则，不新增平行算法分支。

**Non-Goals:**

- 不重写 `DefaultSkuPriorityStrategy` 的排序规则，不为“原续作 SKU”增加单独优先级层。
- 不改变现有计划停机超时软过滤语义；MES 停机超 24 小时是新增的硬禁排口径。
- 不新增实时数据库循环查询，不在 SKU/机台双重循环中临时计算停机时长。
- 不在本次 proposal 阶段扩展到换活字块主链，除非后续代码阅读证明其选机入口也必须共用同一禁排集合。

## Decisions

### 决策一：在基础数据初始化后预计算“MES 停机超 24 小时机台”并挂到上下文

方案：

- 复用 `LhBaseDataServiceImpl` 已加载的 `machineOnlineInfoMap`，以每台机在追溯窗口内最近一条 `ONLINE_DATE` 作为“最后在机时间”。
- 在 `DataInitHandler` 或紧邻其后的初始化步骤中，按排程基准时间批量计算停机时长，产出统一的 `stoppedOver24hMachineCodeSet` 及日志所需明细映射，并挂到 `LhScheduleContext`。

原因：

- 现有 `machineOnlineInfoMap` 已按 `ONLINE_DATE desc, updateTime desc, dataVersion desc` 去重，天然适合作为一次排程的 MES 最近在机快照。
- 预计算一次即可被续作划分、机台过滤和日志输出复用，满足“避免在循环里反复查库/反复算”的性能要求。

备选方案与放弃原因：

- 备选 A：在 `ContinuousProductionStrategy` 或 `DefaultMachineMatchStrategy` 内按需现算。
  放弃原因：会把同一判断散落到多个主链，且候选机台循环中重复计算，难以保证续作和新增口径一致。
- 备选 B：复用现有计划停机超时过滤。
  放弃原因：该逻辑依赖 `MdmDevicePlanShut`，语义是“计划停机软降级”，与本次基于 MES 最近在机时间的硬禁排不是一回事。

### 决策二：在续作/新增划分阶段直接把受影响 SKU 留在新增队列，而不是先进入 S4.4 再回滚

方案：

- 复用 `ScheduleAdjustHandler` 现有“按 MES 在机匹配续作、其余进入新增”的划分入口。
- 当某台 MES 在机机台命中 `stoppedOver24hMachineCodeSet` 时，不为其调用 `assignContinuousSku(...)`；对应 SKU 最终按“未命中可续作机台”的既有路径进入 `newSpecSkuList`。
- 对于同物料多机台场景，仅允许未停机机台继续消费续作；停机机台不再制造额外续作副本。

原因：

- 这是当前项目最小改动的稳定入口，天然复用“未命中续作则视为新增”的既有语义。
- 受影响 SKU 会自动进入现有新增队列，再由 `DefaultSkuPriorityStrategy` 统一排序，不需要单独插入排序分支。

备选方案与放弃原因：

- 备选 A：先让 SKU 进入续作，再用 `appendContinuousCompensationSkuList(...)` 回流新增。
  放弃原因：该路径更适合“续作已部分排产后仍有缺口”的补偿场景；本次是排产前即确认原机台不可用，直接不进入 S4.4 更简单，也避免无意义的占位和回滚。

### 决策三：在 `DefaultMachineMatchStrategy` 增加硬过滤入口，复用现有候选机台跟踪日志

方案：

- 为 `LhScheduleContext` 增加统一不可用机台集合读取入口，`DefaultMachineMatchStrategy` 在现有 `isNotAllowedMachine(...)` / `resolveMachineAvailabilityReason(...)` 附近优先拦截 MES 停机超 24 小时机台。
- 将该过滤记入现有 `MachineFilterTrace`，输出“禁排原因=MES停机超过24小时”。

原因：

- 新增主链、续作补偿回流主链最终都会经过机台匹配策略；这里加硬过滤可以保证“本次排程全程禁排”。
- 复用现有 trace 和告警日志结构，不需要再造独立候选过滤框架。

备选方案与放弃原因：

- 备选 A：仅在续作阶段过滤，不改新增选机。
  放弃原因：无法满足“该机台也不能作为新增排产候选机台”的强约束。

### 决策四：日志拆成“机台禁排日志”和“续作转新增日志”两类，均在主链关键节点记录

方案：

- 在停机机台识别阶段记录机台禁排日志，字段至少包含机台号、MES 最后在机日期、按排程基准计算的停机时长、禁排原因。
- 在续作/新增划分阶段，当某个原本可由 MES 在机匹配为续作的 SKU 因机台停机超时而未进入续作队列时，记录 SKU、原机台、转入新增原因和“后续按新增规则排序”的说明。

原因：

- 这两类日志发生在不同节点：前者属于基础事实，后者属于业务路由决策。拆开记录更利于过程追踪和数据库检索。

## Risks / Trade-offs

- [风险] `LhMachineOnlineInfo` 目前显式可见的时间字段主要是 `ONLINE_DATE`，若业务需要精确到时分秒，单纯按日期计算可能存在边界误差。
  → 缓解：实现前先核对 `BaseEntity.updateTime` 和实际表字段可用性；若只能拿到日期级字段，则在 spec 中明确“以最近在机日期对应基准时刻计算”并补齐边界测试。

- [风险] 同物料多机台续作场景下，部分机台停机、部分机台可续作，可能改变原先“哪些机台拿到续作副本”的分配结果。
  → 缓解：保持 `assignContinuousSku(...)` 原有消费顺序不变，只过滤停机机台，不改变其他机台的归集与副本策略。

- [风险] 新增硬过滤后，某些 SKU 可能因为候选机台减少而进入未排，导致结果与旧口径不同。
  → 缓解：这是需求预期，应通过过程日志和未排原因明确体现“原续作机台停机超过 24 小时导致转新增后仍无可用机台”。

- [风险] 若把禁排集合设计成仅供新增链路使用，会漏掉续作、换活字块或后续扩机评估链路。
  → 缓解：上下文字段命名保持“统一不可用机台”语义，消费侧优先走公共机台匹配策略。

## Migration Plan

- 该变更属于排程运行时规则调整，无数据库结构迁移。
- 上线步骤以代码发布为主，建议先做定向回归：纯续作命中、续作转新增命中、转新增后无候选未排、同物料多机台部分停机。
- 如需回滚，直接回退代码版本即可；不会产生持久化兼容性问题。

## Open Questions

- “停机超过 24 小时”的基准时刻最终以 `scheduleDate`、`scheduleTargetDate` 还是首班开始时间计算，需要在实现前结合现有 MES 在机数据粒度再确认一次。
- 当前主 spec 文件为空，实施前需要补齐 capability 主 spec 的 Purpose 和 requirement 结构，避免后续 `openspec validate` 持续失败。
