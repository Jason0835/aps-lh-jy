# 代码审核报告：按日标准量排产结构清单门控

## 审核范围
分支 `codex/fix-20260815`，未提交改动 16 个文件（+437/-42）。

## 改动概述
引入新硫化参数 `SYS0304031`（按日标准量排产结构清单），将"日标准量班次计划量修正"从全局生效改为按 SKU 结构精确门控。未命中结构的 SKU 三班均保持原始班产，不再使用日标准量补差，也不再使用 `APS日产/3` 抬高剩余班次理论上限。

## 审核结论
**通过，无 Blocker。** 门控设计内聚、覆盖完整、测试充分、spec 同步。

## 问题清单

| 级别 | 位置 | 问题 |
|------|------|------|
| 🟡 建议 | ContinuousProductionStrategy:8222 | 未命中结构时仍计算 remainShiftCapacityMap，存在冗余开销 |
| 💭 提示 | NewSpecProductionStrategy:5749 | 首次分配 currentShiftCapacity 门控缺专门单测 |
| 💭 提示 | 三处策略日志 | 日志字段顺序略有差异，可统一 |
| 💭 提示 | rule_engine_init.sql | 已有环境需手动插入 SYS0304031 参数 |
| 💭 提示 | 数据依赖 | 门控生效前提是 SKU 主数据 structureName 已维护 |

## 门控完整性核验
- `adjustShiftPlanQtyMapByDailyStandard` 4 处调用：全部门控 ✓
- `resolveDailyStandardRemainShiftCapacityUpperLimit` 5 处调用：值未被未命中路径使用 ✓
- `isDailyStandardRemainShift` 3 处 currentShiftCapacity：均加 `dailyStandardStructureMatched &&` 短路 ✓
