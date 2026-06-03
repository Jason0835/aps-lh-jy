# 续作补偿 SKU 统一排序后优先锁回原续作机台设计

## 1. 明确结论

本次推荐方案是：

- 续作释放后生成的补偿 SKU 继续进入 `newSpecSkuList`，与其他新增 SKU 一起按现有新增排序规则统一排序。
- 当补偿 SKU 真正轮到自己进入 `S4.5` 选机时，如果原续作机台仍可用，则优先锁回原续作机台。
- 如果原续作机台已经被前序新增 SKU 占走，或者虽然未被占走但窗口内已无可开产时间，则放弃锁回，继续走现有新增排产选机与换模链路。

该方案满足以下业务语义：

- `3302002546` 续作释放后，不立即绑定 `K1105`。
- `3302002546` 必须先参与新增排产 SKU 的统一排序。
- 只有轮到 `3302002546` 自己选机时，才判断是否优先锁回 `K1105`。
- 如果 `K1105` 已被前序 SKU 选走，则 `3302002546` 第二天只能按新增换模逻辑处理。
- 如果 `K1105` 未被前序 SKU 选走，则 `3302002546` 在自己回合优先锁回 `K1105`。

## 2. 改造目标

本次改造只解决一件事：

- 为续作释放后转入新增阶段的补偿 SKU 增加“统一排序后，在自己回合优先锁回原续作机台”的局部规则。

明确不做以下扩展：

- 不修改新增 SKU 的全局排序规则。
- 不修改普通新增 SKU 的选机逻辑。
- 不修改 `releasedContinuousMachineCodeSet` 的既有语义。
- 不新增“强制保留机台直到某个补偿 SKU 到来”的占坑逻辑。
- 不把原续作机台优先规则扩散到全部补偿 SKU 之外的场景。

## 3. 现有逻辑影响点

### 3.1 入口与主链

- `ContinuousProductionStrategy#appendContinuousCompensationSkuList(...)`
- `ContinuousProductionStrategy#copyContinuousCompensationSku(...)`
- `NewSpecProductionStrategy#schedulePendingNewSpecsRound(...)`
- `NewSpecProductionStrategy#selectCandidateMachine(...)` 及其邻近私有方法

### 3.2 现有排序与共享语义

- `DefaultSkuPriorityStrategy#resolveContinuousCompensationScore(...)`
- `SkuScheduleDTO.dailyPlanQuotaMap`
- `LhScheduleContext.releasedContinuousMachineCodeSet`

### 3.3 结果与日志链路

- `ResultValidationHandler` 中补偿 SKU 与来源续作 SKU 的共享账本归属校验
- 新增排产候选日志、排除原因日志、未排原因日志

## 4. 设计思路

### 4.1 补偿 SKU 继续统一进入新增排序

保留现有做法：

- 续作阶段识别剩余缺口。
- 通过 `appendContinuousCompensationSkuList(...)` 生成补偿 SKU。
- 将补偿 SKU 追加到 `newSpecSkuList`。
- 仍由 `DefaultSkuPriorityStrategy` 参与新增 SKU 的统一排序。

这一步不允许绕开新增排序直接回原机台。

### 4.2 为补偿 SKU 单独保留“原续作优先机台”语义

当前 `copyContinuousCompensationSku(...)` 会执行：

- `setScheduleType(NEW_SPEC)`
- `setContinuousMachineCode(null)`

这条逻辑要继续保留，因为补偿 SKU 已经进入 `S4.5`，不能再被误判为普通续作。

但同时需要新增一个仅供 `S4.5` 使用的字段语义，例如：

- 原续作优先机台编码

该字段只记录来源续作 SKU 的原机台，例如 `K1105`，不参与 `S4.4`，不改变 `continuousMachineCode` 的现有业务语义。

### 4.3 只在“轮到当前补偿 SKU 选机”时尝试锁回

在 `NewSpecProductionStrategy` 中，当前 SKU 进入选机时：

1. 先正常生成候选机台列表。
2. 如果当前 SKU 是“续作补偿 SKU”，且存在“原续作优先机台编码”，则先检查该机台是否仍在候选集中。
3. 若该机台在候选集中，再按当前窗口实时状态校验：
   - 是否已被前序结果占用
   - 是否仍有可开产时间
   - 是否还能完成换模/首检/开产链路
4. 若校验通过，则直接优先选中该机台。
5. 若任一步失败，则放弃锁回，回退到现有新增选机逻辑。

这里的“优先锁回”是当前 SKU 自己这一轮的局部首选，不是改全局机台排序。

### 4.4 不允许新增兜底和强占逻辑

以下行为禁止出现：

- 原续作机台被别人占走后，再回滚前序新增结果强行让机。
- 原续作机台不在候选里时仍然硬塞回去。
- 原续作机台窗口已过时仍然忽略换模/首检约束强开产。
- 为了锁回机台而绕开 `S4.5` 的正常换模分配、首检分配和窗口判断。

## 5. 详细实施步骤

1. 在 `SkuScheduleDTO` 中增加“原续作优先机台”字段，只作为补偿 SKU 的 `S4.5` 局部语义。
2. 修改 `ContinuousProductionStrategy#copyContinuousCompensationSku(...)`：
   - 保持 `continuousMachineCode = null`
   - 同时把来源续作机台写入“原续作优先机台”字段
3. 修改 `NewSpecProductionStrategy`：
   - 在当前 SKU 进入选机时，增加一个小型私有方法解析“是否命中原续作优先机台”
   - 命中时仅把该机台作为当前 SKU 的优先候选
   - 未命中时完全走原逻辑
4. 增加关键日志：
   - 补偿 SKU 生成时记录来源机台和优先机台
   - 当前补偿 SKU 轮到选机时记录“尝试锁回原续作机台”
   - 锁回成功或失败时记录原因
5. 增加回归测试，覆盖“未被抢占锁回成功”和“已被抢占回退新增换模”两条主路径。

## 6. 数据处理说明

本次不修改以下数据语义：

- `dailyPlanQuotaMap` 共享账本
- 续作补偿量计算口径
- 新增排产日计划扣减口径
- 换模耗时、首检耗时、晚班不可换模约束

本次新增的数据语义只有一条：

- 补偿 SKU 在进入 `S4.5` 时，额外携带一个“原续作优先机台”字段，用于当前 SKU 自己轮到选机时优先尝试锁回。

这个字段不参与持久化表设计，不影响已有 SQL / XML。

## 7. 边界场景

需要重点验证以下场景：

1. 原续作机台未被前序 SKU 占走：
   - 补偿 SKU 在自己回合优先锁回原机台
2. 原续作机台已被前序 SKU 占走：
   - 补偿 SKU 放弃锁回，继续走新增换模逻辑
3. 原续作机台未被占走，但窗口内无可开产时间：
   - 不允许强锁回，必须按现有窗口约束失败
4. 非补偿新增 SKU：
   - 选机逻辑完全不受影响
5. 同一来源 SKU 共享账本：
   - 不允许因为新增“优先机台”语义破坏已有共享扣减链路

## 8. 风险点

### 风险 1：误把补偿 SKU 当续作继续排

如果直接复用 `continuousMachineCode`，补偿 SKU 可能被重新带回 `S4.4` 或污染现有续作语义。

应对方式：

- 保持 `continuousMachineCode` 清空
- 单独增加“原续作优先机台”字段

### 风险 2：把局部优先锁回扩成全局排序

如果把“原续作优先机台”直接写入机台全局排序，可能误伤普通新增 SKU。

应对方式：

- 只在当前补偿 SKU 自己轮到选机时局部生效
- 不修改普通新增 SKU 的排序与候选比较器语义

### 风险 3：锁回时绕开现有窗口约束

如果锁回逻辑直接指定机台而不复用原有换模/首检/开产判断，会造成错误开产。

应对方式：

- 锁回只影响“首选机台”
- 后续仍必须走现有换模、首检、窗口校验链路

## 9. 验证建议

### 9.1 静态回归

- 增加 `ContinuousProductionStrategy` 定向测试：
  - 补偿 SKU 复制后保留原续作优先机台
- 增加 `NewSpecProductionStrategy` 定向测试：
  - 原机台未被占走时优先锁回成功
  - 原机台已被占走时回退普通新增选机

### 9.2 真实日志核验

优先复核 `2026-06-03 / 3302002546 / K1105` 场景，观察以下日志：

- 补偿 SKU 何时进入 `newSpecSkuList`
- 轮到 `3302002546` 自己时是否打印“尝试锁回原续作机台 K1105”
- `K1105` 若未被占走是否被优先选中
- `K1105` 若已被占走是否明确打印“锁回失败，回退普通新增选机”

### 9.3 结果对账

真实验证时仍按以下链路对账：

- `T_LH_SCHEDULE_RESULT`
- `T_LH_UNSCHEDULED_RESULT`
- `T_LH_MOULD_CHANGE_PLAN`
- `T_LH_SCHEDULE_PROCESS_LOG`

## 10. 自检结论

- 无 `TODO` / `TBD`
- 设计范围仅限“续作补偿 SKU 在新增阶段优先锁回原续作机台”
- 与现有“统一进入新增排序”语义不冲突
- 不包含全局排序扩散和兜底强占逻辑
