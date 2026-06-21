# 零日计划续作动态收尾状态设计

## 1. 明确结论

续作 SKU 在 T～T+2 日计划均为 0、月底也无后续计划且存在历史欠产时，`DailyMachineExpansionPlanner` 已明确将其判定为“窗口及月底无计划，按收尾清量处理”。该动态收尾结论必须同步到 SKU 的统一收尾标签，确保目标量、日标准产量、日计划账本扣减和最终收尾标记使用同一业务语义。

本次修复不针对物料 `3302002182` 增加特殊判断，不改变普通收尾、非收尾、试制、量试、新增排产、换模或换活字块的既有规则。

## 2. 改造目标

修复排程日期 `2026-06-14` 中物料 `3302002182` 的续作结果：日计划为 `0,0,0`、班产为 18、硫化余量为 83、窗口产能足以完成收尾时，不得在日计划账本同步阶段按历史欠产 17 回裁为单班 C1=18，而应保留按日标准产量和双模规则形成的完整收尾排产量。

## 3. 现有逻辑影响点

- 入口：`ContinuousProductionHandler#handleInternal(...)` 的续作收尾排产步骤。
- 欠产与动态收尾判定：`DailyMachineExpansionPlanner#prepareShortageQuota(...)`。
- 动态收尾目标量：`ContinuousProductionStrategy#applyContinuousNoFutureEndingStrictTarget(...)`。
- 班次分配：`ContinuousProductionStrategy#buildScheduleResult(...)`、`distributeToShifts(...)`。
- 日标准产量：`calculateDailyStandardShiftCapacityMap(...)`、`applyDailyStandardPlanQtyToContinuousResults(...)`。
- 日计划账本同步：`syncContinuousDailyPlanQuota(...)`、`applyContinuousBlockToDailyQuota(...)`。
- 最终结果：`refreshContinuousEndingFlagByResult(...)`、双模计划量归整及结果保存。
- 数据表：`T_LH_SCHEDULE_RESULT`、`T_LH_UNSCHEDULED_RESULT`、`T_LH_MOULD_CHANGE_PLAN`、`T_LH_SCHEDULE_PROCESS_LOG`，仅用于验证，不修改表结构或 SQL。

## 4. 根因

`prepareShortageQuota(...)` 在“窗口日计划为 0、月底也无后续计划且存在历史欠产”时设置 `forceEndingByNoFuturePlan=true`。续作策略随后把 `isEnding` 设为 true，并把严格目标量设置为硫化余量，但没有把 SKU 的 `skuTag` 同步为 `SkuTagEnum.ENDING`。

`applyContinuousBlockToDailyQuota(...)` 为避免结果行 `isEnd` 被后置刷新误翻转，按 SKU 的统一收尾标签判断是否保留超出 dayN 账本的收尾计划量。动态收尾 SKU 因标签仍为普通状态，被误走“非收尾严格目标量”分支，计划量从 84 回裁到历史欠产 17，最终按双模归整为 18。

标签补齐后，日标准产量后置修正还会把收尾残班从 14 补到 16，使总量从 84 增至 86。现有严格收尾最终收口此前只在多机台分支内调用，单机动态收尾没有在日标准产量修正后再次执行目标量收口。

## 5. 设计思路

复用项目已有统一收尾标签，不修改账本扣减方法的判断依据。在 `applyContinuousNoFutureEndingStrictTarget(...)` 确认动态收尾时：

1. 将 SKU 标签设置为 `SkuTagEnum.ENDING`；
2. 当原收尾剩余天数无效时，将 `endingDaysRemaining` 设置为 1，保持与动态单胎胚收尾的既有语义一致；
3. 严格目标量按硫化余量和机台模数归整，83 在双模机台取 84；
4. 每次日标准产量修正后，统一复用现有严格收尾最终收口，覆盖单机和多机结果；
5. 扩充现有中文日志，记录原标签、更新后标签、收尾剩余天数和严格目标量。

这样日计划账本同步仍沿用现有 `skuTag` 判断，不引入第二套动态收尾识别规则，也不会重新依赖可能被后置刷新改变的结果行 `isEnd`。

## 6. 数据处理说明

1. 日计划账本初始化仍读取 T～T+2 的真实日计划和历史欠产。
2. 窗口及月底均无计划时，严格目标量取硫化余量并按机台模数归整，83 在双模机台取 84。
3. 班次分配继续应用 SKU 日标准产量 52、班产 18 和双模归整规则，形成多个有效班次。
4. 日计划账本只记录零日计划之外的收尾补量为 `shiftFillOverQty`，不得回裁收尾结果。
5. 最终计划量达到收尾比较量后，结果 `IS_END` 应为 1；双模场景允许按既有模数规则由 83 归整为 84。
6. 不新增数据库字段，不修改 Mapper、XML、事务或配置。

## 7. 边界场景

- 窗口和月底均无计划、存在历史欠产且硫化余量可在窗口内完成：动态转收尾并保留完整收尾量。
- 窗口无计划但月底仍有计划：保持“仅补本月历史欠产”，不得标记为收尾。
- 窗口无计划且无历史欠产：保持释放续作机台，不强制排产。
- 普通显式收尾 SKU：继续使用已有 `ENDING` 标签和收尾规则。
- 非收尾试制严格目标量：继续按 dayN 账本回裁。
- 共用胎胚收尾：继续沿用收尾标签下的共用胎胚目标量规则。
- 单模和双模：分别保持现有模数归整口径。

## 8. 风险点

- 动态收尾标签会被后续共享胎胚、机台匹配和结果标记逻辑识别；这属于既有收尾语义的补齐，但必须通过定向回归确认没有把“月底仍有计划”的仅补欠产场景误标收尾。
- 最终排产量可能因双模归整比硫化余量多 1 条，本次保持项目现有双模规则，不改为奇数落库。
- 不能改用结果行 `isEnd` 作为账本判断依据，否则会恢复历史上因后置刷新造成的误判风险。

## 9. 验证建议

1. 先增加失败回归测试，构造日计划 `0,0,0`、历史欠产 17、月底无计划、余量 83、班产 18、双模的单机续作。
2. 修复前断言应失败：账本同步后仅剩历史欠产对应班次量。
3. 修复后断言动态 SKU 标签为 `ENDING`，最终结果为多班次、总量 84、`IS_END=1`。
4. 增加“窗口无计划但月底仍有计划”的保护断言，确认仍只补历史欠产且不标记收尾。
5. 运行续作、日标准产量和目标量定向测试，再执行 `mvn -o -pl aps-lh -am -DskipTests package`。
6. 启动当前工作区应用，调用 `2026-06-14` 排程接口，核对新批次四张业务表和关键日志。
