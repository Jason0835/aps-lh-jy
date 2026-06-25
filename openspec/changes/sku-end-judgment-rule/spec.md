请基于项目现有硫化排程代码，调整 SKU 收尾判断逻辑。要求优先复用现有逻辑，避免大范围重构；涉及主销产品收尾、奇数余量调整等已有特殊规则时，以项目现有实现为准，不要重写一套新逻辑。

一、核心原则

收尾判断必须以 SKU 为维度，不是机台维度。

机台只是 SKU 的产能来源：
- 单机台场景：SKU 的产能来源只有一台机台；
- 多机台场景：SKU 的产能来源是多台实际可用机台的产能汇总。

不能按“某台机台是否能做完”判断收尾。
应按“当前 SKU 在本次排程窗口内，实际使用机台的总有效产能是否能完成清尾目标量”判断收尾。

二、区分三个收尾标识

1. structureTailFlag：结构收尾标识
- 判断窗口：5 天；
- 只用于 SKU 排序优先级；
- 命中后只表示排序靠前；
- 不能直接决定本次是否按收尾控量排产。

2. expectedTailFlag：预计收尾标识，可选
- 排前基于初始数据预估；
- 仅用于日志、分析、参考；
- 不能控制实际排产。

3. currentWindowTailFlag：当前排程窗口收尾标识
- 在排中判断；
- 判断窗口：当前排程窗口 3 天、连续 8 个班次；
- 用于决定本次 SKU 实际排产是否按收尾规则控量；
- 这是实际排产控制的核心标识。

三、当前排程窗口收尾判断时机

禁止在排前直接给 SKU 打实际收尾标识。

正确时机：

当 SKU 进入实际排产流程后，先按项目现有规则完成：
- 续作机台识别；
- 降模减机台；
- 新增加机台；
- 换活字块；
- 候选机台筛选；
- 模具可用性校验；
- 停机、禁排、日历等约束过滤。

在当前 SKU 的实际机台集合确定后、生成班次排产量之前，判断 currentWindowTailFlag。

伪代码：

availableMachines = selectAvailableMachinesForSku(sku);

availableMachines = applyReduceMachineRuleIfNeeded(sku, availableMachines);

availableMachines = applyAddMachineRuleIfNeeded(sku, availableMachines);

tailTargetQty = calculateTailTargetQty(sku);

effectiveCapacity = calculateSkuEightShiftEffectiveCapacity(sku, availableMachines);

currentWindowTailFlag = effectiveCapacity >= tailTargetQty;

if (currentWindowTailFlag) {
scheduleByTailRule(sku, availableMachines, tailTargetQty);
} else {
scheduleByNonTailRule(sku, availableMachines);
}

四、清尾目标量规则

按项目最新胎胚规则计算清尾目标量：

1. 非共用胎胚 / 单胎胚 SKU：

清尾目标量 = max(硫化余量, 胎胚库存)

2. 共用胎胚 SKU：

清尾目标量 = 硫化余量

补充：
- 共用胎胚 SKU 余量为 0 时，不排产，列入未排；
- 共用胎胚不允许用胎胚库存抬高目标量；
- 排程过程中，如果某个共用胎胚 SKU 已收尾完成，后续 SKU 需要重新识别胎胚共用关系；
- 如果只剩一个 SKU 使用该胎胚，后续按非共用胎胚处理。

五、当前 8 班有效产能计算

currentWindowTailFlag 只能基于当前 3 天、连续 8 个班次判断，不能使用 5 天产能。

判断公式：

SKU 当前 8 班总有效产能 >= 清尾目标量
=> currentWindowTailFlag = true

否则：
=> currentWindowTailFlag = false

单机台场景：

SKU 当前 8 班总有效产能 =
该 SKU 在单台机台连续 8 个班次内的有效产能

多机台场景：

SKU 当前 8 班总有效产能 =
机台1有效产能 + 机台2有效产能 + ... + 机台N有效产能

注意：
- 只能统计该 SKU 本次实际可用、实际选中、满足约束的机台；
- 不能把所有理论可生产该 SKU 的机台都加进来；
- 多机台场景不能因为单台机台做不完，就判定 SKU 非收尾；
- 只要实际使用机台的总有效产能能完成清尾目标量，就判定 SKU 当前窗口收尾。

六、有效产能必须扣除损耗

当前 8 班有效产能不能直接使用 班产 * 8。

必须扣除项目现有约束和损耗，包括但不限于：
- 清洗时间；
- 换模时间；
- 换活字块时间；
- 晚班不可换模；
- 停机时间；
- 日历不可用时间；
- 已占用产能；
- 禁排机台；
- 模具不可用或模具数量不足；
- 首检数量；
- 单控机台产能差异；
- 精度、时间落点等导致的不可用产能；
- 其他项目已有不可排产约束。

七、结构收尾与当前窗口收尾的关系

禁止写成：

if (structureTailFlag) {
按收尾排产;
}

必须改为：

if (currentWindowTailFlag) {
按当前窗口收尾规则排产;
} else {
按非收尾规则排产;
}

规则：
- structureTailFlag = true，只影响 SKU 排序；
- currentWindowTailFlag = true，才决定本次实际排产按收尾逻辑处理；
- 即使结构五天收尾命中，如果当前 8 班不能清尾，也必须按非收尾规则排产；
- 如果结构五天收尾未命中，但当前 8 班实际能清尾，也可以按当前窗口收尾规则处理。

八、收尾排产规则

当 currentWindowTailFlag = true：

默认规则：
- 目标排产量 = 清尾目标量；
- 严格按清尾目标量排产；
- 不允许补满班次；
- 不允许补满窗口；
- 不允许超排；
- 达到清尾目标量后立即停止；
- 允许最后一个班次不足班产。

但以下特殊场景必须复用项目已有规则：

1. 主销产品收尾特殊规则
- SKU 为主销产品时，项目中已有收尾补满规则；
- 不要简单按“严格清尾目标量”覆盖主销产品逻辑；
- 主销产品收尾是否补满中班、晚班，以及结构机台数约束等，按项目已有规则执行。

2. 硫化余量为奇数的调整规则
- 当收尾 SKU 的硫化余量为奇数，需要做向上调整时，按项目已有奇数余量调整逻辑执行；
- 不要因为“收尾严格目标量”而跳过已有奇数修正规则；
- 最终目标量以项目现有奇数调整后的结果为准。

因此收尾目标量计算建议流程：

baseTailTargetQty = calculateTailTargetQtyByGreenTireRule(sku);

tailTargetQty = applyExistingOddRemainQtyAdjustIfNeeded(sku, baseTailTargetQty);

tailTargetQty = applyExistingMainSaleTailRuleIfNeeded(sku, tailTargetQty, context);

九、非收尾排产规则

当 currentWindowTailFlag = false：

- 继续走项目已有非收尾排产规则；
- 正规、量试、小批量、试制按现有非收尾规则处理；
- 新增排产、续作排产、换活字块排产保持原有非收尾策略；
- 不得因为 structureTailFlag 或 expectedTailFlag 命中而改成收尾控量。

十、排后最终收尾标识

SKU 排产完成后，需要根据本次实际排产结果汇总 finalTailFlag。

判断范围：
- 当前排程窗口 3 天、连续 8 个班次；
- 汇总该 SKU 在本次排程中所有机台的实际排产量；
- 不能只看单台机台；
- 不能只看 T 日或保存日期。

判断公式：

本次 SKU 实际总排产量 >= 最终清尾目标量
=> finalTailFlag = true

否则：
=> finalTailFlag = false

finalTailFlag 用于：
- 排程结果落库；
- 日志排查；
- 后续滚动排程衔接；
- 共用胎胚关系动态重算。

十一、每排完一个 SKU 后更新上下文

每个 SKU 排产完成后，应更新：
- SKU 已排量；
- SKU 剩余硫化余量；
- 胎胚库存消耗；
- 共用胎胚关系；
- 机台占用情况；
- 模具占用情况；
- 换模 / 换活字块次数；
- finalTailFlag。

后续 SKU 必须基于最新上下文重新判断 currentWindowTailFlag，不能复用排前旧标识。

十二、适用范围

本规则需覆盖：
- 新增排产；
- 续作排产；
- 换活字块排产；
- 单机台直排 SKU；
- SKU 多机台排产；
- 共用胎胚 SKU；
- 非共用胎胚 SKU；
- 主销产品 SKU 收尾；
- 硫化余量奇数调整；
- 排程窗口内共用胎胚关系动态变化；
- 加机台后再判断收尾；
- 降模减机台后再判断收尾。

十三、日志要求

补充关键日志。

排序阶段日志：
- SKU编码；
- structureTailFlag；
- expectedTailFlag；
- 判断窗口；
- 清尾目标量；
- 是否进入结构收尾排序层级。

排中判断日志：
- SKU编码；
- 当前排程窗口：3天/8班；
- 胎胚是否共用；
- 是否主销产品；
- 是否触发奇数余量调整；
- 硫化余量；
- 胎胚库存；
- 原始清尾目标量；
- 修正后清尾目标量；
- 实际使用机台数；
- 每台机台8班有效产能；
- 8班总有效产能；
- currentWindowTailFlag；
- 实际排产规则：收尾规则 / 非收尾规则。

排后结果日志：
- SKU编码；
- 本次窗口实际总排产量；
- 最终清尾目标量；
- finalTailFlag；
- 是否更新共用胎胚关系。

十四、验收重点

1. 收尾判断主体是 SKU，不是机台。
2. 结构五天收尾只用于排序。
3. 排前标识只能是预计收尾，不能控制实际排产。
4. 实际收尾必须在排中判断：机台增减完成后、生成班次计划量前。
5. 当前三天 8 班总有效产能能完成清尾目标量，才按收尾规则处理。
6. 多机台必须汇总 SKU 实际使用机台的有效产能。
7. 默认收尾严格按清尾目标量排，不补满、不超排。
8. 主销产品收尾特殊规则必须复用项目已有逻辑，不被默认严格目标量覆盖。
9. 硫化余量奇数向上调整必须复用项目已有逻辑，最终目标量以调整后为准。
10. 当前 8 班不能清尾时，即使命中结构收尾，也按非收尾规则排产。
11. 排后需要根据实际排产结果落 finalTailFlag。
12. 每排完一个 SKU 后，必须更新上下文，后续 SKU 重新判断收尾。