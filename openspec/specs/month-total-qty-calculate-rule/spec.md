
```text
本次改造的是“硫化月计划总量计算逻辑”，不是“硫化余量公式”。硫化余量仍然等于：硫化月计划总量 - 已完成量 + 上月超欠产。
````

最新口径应调整为：

```text
硫化余量 = 硫化月计划总量 - 已完成量 + 上月超欠产
```

其中：

```text
已完成量、上月超欠产：保持项目现有逻辑不变
硫化月计划总量：按月计划断点 + 跨月规则重新计算
```


````md
# 硫化排程跨月 + 月计划断开场景下的硫化月计划总量计算改造

## 背景

硫化排程项目需要支持跨月排产，同时需要兼容月计划中间断开的场景。

注意：本次需求不是修改硫化余量公式，而是修改“硫化月计划总量”的计算逻辑。

硫化余量公式保持不变：

```text
硫化余量 = 硫化月计划总量 - 已完成量 + 上月超欠产
````

其中：

* 已完成量：沿用项目现有逻辑，不允许改动；
* 上月超欠产：沿用项目现有逻辑，不允许改动；
* 硫化月计划总量：本次需要按“月计划断点 + 跨月场景”重新计算。

本次改造目标：

1. 抽出公共的“硫化月计划总量计算器”；
2. 所有需要计算硫化余量的地方，仍使用原公式；
3. 原公式中的“月计划总量”改为调用新的公共计算逻辑；
4. 不改续作、换活字块、新增排产等实际排程主流程。

---

## 一、核心公式

整体公式保持：

```text
硫化余量 = 硫化月计划总量 - 已完成量 + 上月超欠产
```

本次只调整：

```text
硫化月计划总量
```

不要把以下逻辑写成新的余量公式：

```text
断点日前计划量 - 已完成量 + 上月超欠产
```

正确做法是：

```text
硫化月计划总量 = 按断点规则计算出来的计划量合计

硫化余量 = 硫化月计划总量 - 已完成量 + 上月超欠产
```

---

## 二、月计划断点定义

月计划断开的定义：

```text
月计划前日有值，当日没有值，视为断开。
断点日 = 断开前最后一个有值的日期。
```

示例：

```text
6.5 = 48
6.6 = 空
6.7 = 8
```

则：

```text
断点日 = 6.5
```

再比如：

```text
6.10 = 32
6.11 = 18
6.12 = 空
```

则：

```text
断点日 = 6.11
```

注意：断点日不是空值那天，而是空值前一天最后一个有计划量的日期。

---

## 三、建议新增公共类

请新增或重构一个公共类，专门计算“硫化月计划总量”。

建议命名：

```java
LhCuringMonthPlanTotalCalculator
```

或按项目风格命名：

```java
CuringMonthPlanTotalCalculator
VulcanizationMonthPlanTotalService
MonthPlanSegmentTotalCalculator
```

核心职责：

```text
根据 SKU、排程窗口 T～T+2、月计划断点、跨月计划，计算本次用于硫化余量公式的“硫化月计划总量”。
```

建议方法：

```java
BigDecimal calculateMonthPlanTotal(CuringMonthPlanTotalRequest request);
```

建议返回计算明细：

```java
class CuringMonthPlanTotalResult {
    BigDecimal monthPlanTotal;

    LocalDate scheduleStartDate;
    List<LocalDate> scheduleWindowDates;

    boolean windowHasPlan;
    boolean crossMonth;
    LocalDate latestPlanDateInWindow;
    LocalDate breakPointDate;

    BigDecimal currentMonthPlanTotal;
    BigDecimal crossMonthPlanTotal;
    String calculateScene;
}
```

如果项目不方便新增 Result，也至少要在日志中输出这些字段。

---

## 四、月计划有值判断

需要统一封装 dayN 是否“有值”的判断，例如：

```java
boolean hasPlanQty(BigDecimal dayQty);
```

具体规则以项目现有数据口径为准：

* 如果项目中 `null / 空 / 0` 都代表无计划，则统一视为无值；
* 如果 `0` 有特殊业务意义，则只把 `null / 空` 视为无值；
* 不允许各个策略类里重复写判断。

断点判断、窗口内是否有计划、后续计划段扫描，都必须统一调用该方法。

---

## 五、硫化月计划总量计算规则

### 场景 1：硫化排产 3 天内有出现月计划量

排程窗口为 T～T+2。

如果 3 天内存在月计划量：

1. 找到 3 天内最晚出现月计划量的日期；
2. 从该日期往后，在对应月份月计划中查找断点；
3. 断点日为“后一天无计划量之前的最后一个有计划量日期”；
4. 硫化月计划总量 = 断点日含当天之前的计划量合计。

公式：

```text
硫化月计划总量 = 断点日（含）之前的计划量合计
```

其中：

```text
断点日（含）之前的计划量合计 = 当前月 day1 ～ 断点日 dayN 的计划量合计
```

示例：

```text
排程日期：6.9
T 日：6.8

6.8  = 48
6.9  = 空
6.10 = 32
6.11 = 18
6.12 = 空
```

窗口：

```text
6.8、6.9、6.10
```

3 天内最晚有计划量：

```text
6.10 = 32
```

从 6.10 往后找断点：

```text
6.10 = 32
6.11 = 18
6.12 = 空
```

断点日：

```text
6.11
```

则：

```text
硫化月计划总量 = 6 月 day1 ～ day11 计划量合计
```

如果 day1～day11 合计为 98：

```text
硫化月计划总量 = 98

硫化余量 = 98 - 已完成量 + 上月超欠产
```

---

### 场景 1.1：3 天内最晚计划日跨月

如果 T～T+2 三天内最晚出现月计划量的日期属于跨月月份，则：

```text
硫化月计划总量 = 当月硫化月计划总量 + 跨月断点日（含）之前的计划量合计
```

其中：

```text
当月硫化月计划总量 = T 日所属月份按断点规则计算出的计划量合计
跨月断点日（含）之前的计划量合计 = 跨月月份 day1 ～ 跨月断点日 dayN 的计划量合计
```

示例：

```text
排程日期：6.30
T 日：6.29

6.29 = 48
6.30 = 空
7.1  = 32
7.2  = 18
7.3  = 空
```

窗口：

```text
6.29、6.30、7.1
```

假设：

```text
当月硫化月计划总量 = 48
```

3 天内最晚计划日：

```text
7.1 = 32
```

从 7.1 往后在 7 月月计划中找断点：

```text
7.1 = 32
7.2 = 18
7.3 = 空
```

断点日：

```text
7.2
```

跨月计划量合计：

```text
7 月 day1 ～ day2 = 32 + 18 = 50
```

则：

```text
硫化月计划总量 = 48 + 50 = 98

硫化余量 = 98 - 已完成量 + 上月超欠产
```

注意：

* 跨月部分只累计跨月月份 day1～断点日；
* 不要错误读取 6 月 day31；
* 不要把 7 月整月计划总量加进来；
* 不要在“月计划总量计算器”中扣已完成量或加上月超欠产。

---

### 场景 2：硫化排产 3 天内没有出现月计划量

如果 T～T+2 三天内没有任何月计划量，需要继续区分。

---

### 场景 2.1：当日之前还有余量

这里的“还有余量”判断仍然可以用完整公式判断：

```text
当日之前计划量合计 - 已完成量 + 上月超欠产 > 0
```

其中：

```text
当日之前计划量合计 = 当前月 day1 ～ T 日 dayN 的计划量合计
```

如果结果大于 0，表示当日之前仍有未消耗的计划余量。

此时：

```text
硫化月计划总量 = 当前月 day1 ～ T 日 dayN 的计划量合计
```

最终：

```text
硫化余量 = 硫化月计划总量 - 已完成量 + 上月超欠产
```

---

### 场景 2.2：当日之前没有余量

如果 T～T+2 三天内没有计划量，并且当日之前没有余量，则从 T 日往后在当月月计划中查找后续计划段和断点。

处理步骤：

1. 从 T 日往后扫描当前月月计划；
2. 找到后续第一个有计划量的日期；
3. 从该计划日期继续往后找到断点；
4. 硫化月计划总量 = 当前月 day1 ～ 断点日 dayN 的计划量合计。

公式：

```text
硫化月计划总量 = 断点日（含）之前的计划量合计
```

示例：

```text
T 日：6.8
窗口 6.8、6.9、6.10 均无计划
当日之前没有余量

后续计划：
6.12 = 32
6.13 = 18
6.14 = 空
```

断点日：

```text
6.13
```

则：

```text
硫化月计划总量 = 6 月 day1 ～ day13 计划量合计

硫化余量 = 硫化月计划总量 - 已完成量 + 上月超欠产
```

---

## 六、跨月读取要求

所有 dayN 读取必须基于真实业务日期 `LocalDate`。

```text
year = bizDate.getYear()
month = bizDate.getMonthValue()
dayN = bizDate.getDayOfMonth()
```

示例：

```text
2026-06-29 -> 2026 年 6 月 day29
2026-06-30 -> 2026 年 6 月 day30
2026-07-01 -> 2026 年 7 月 day1
2026-07-02 -> 2026 年 7 月 day2
```

禁止：

```text
用排程月份 + offset 计算 dayN
用 6 月 day31 表示 7 月 1 日
因为 6 月没有 day31 就把 7 月计划当 0
```

建议统一提供方法：

```java
BigDecimal getDailyPlanQty(String skuCode, LocalDate bizDate);
```

内部按：

```text
业务日期所属 year/month/day
物料编码
排产版本
需求版本
其他项目必要维度
```

读取对应月计划。

---

## 七、硫化余量计算调用方式

所有地方仍然使用统一公式：

```java
remainQty = monthPlanTotal.subtract(completedQty).add(validLastMonthOverdueQty);
```

其中：

```java
monthPlanTotal = curingMonthPlanTotalCalculator.calculateMonthPlanTotal(request);
completedQty = 现有完成量逻辑;
validLastMonthOverdueQty = 现有上月超欠产逻辑;
```

不要把 completedQty 和 lastMonthOverdueQty 的逻辑下沉到月计划总量计算器里。

---

## 八、需要替换的旧逻辑

全局搜索类似以下代码：

```java
monthPlan.getMonthPlanQty()
monthPlan.getTotalQty()
monthPlanTotalQty
sum(day1...day31)
```

如果这些代码是用于计算硫化余量中的“月计划总量”，则需要替换为：

```java
curingMonthPlanTotalCalculator.calculateMonthPlanTotal(...)
```

重点排查：

* 续作 SKU 初始化硫化余量；
* 换活字块 SKU 初始化硫化余量；
* 新增排产 SKU 初始化硫化余量；
* SKU 收尾判断；
* 机台收尾判断；
* 小余量不排判断；
* 共用胎胚收尾目标量判断；
* 主销产品收尾补满判断；
* 加机台前余量判断；
* 未排结果原因判断；
* 日计划账本 / 消费链路里重新计算余量的地方。

注意：替换的是“月计划总量来源”，不是改排程策略。

---

## 九、不得影响的逻辑

本次不得修改：

1. 已完成量计算逻辑；
2. 上月超欠产计算逻辑；
3. 续作排产主流程；
4. 换活字块排产主流程；
5. 新增排产主流程；
6. 加机台规则；
7. 降模减机台规则；
8. 提前生产规则；
9. 共用胎胚规则；
10. 主销产品收尾补满规则；
11. 模具占用规则；
12. 晚班不可换模规则；
13. 首检规则。

这些逻辑只是在需要用到“硫化余量”时，继续按原公式计算，只是公式里的“月计划总量”改为新的公共计算结果。

---

## 十、日志要求

新增日志，便于排查月计划总量计算。

建议日志字段：

```text
skuCode
scheduleStartDate
windowDates
windowPlanQty
windowHasPlan
latestPlanDateInWindow
crossMonth
breakPointDate
currentMonthPlanTotal
crossMonthPlanTotal
finalMonthPlanTotal
calculateScene
```

示例：

```text
[CuringMonthPlanTotal] sku=330200xxxx,
T=2026-06-29,
window=[2026-06-29,2026-06-30,2026-07-01],
latestPlanDateInWindow=2026-07-01,
crossMonth=true,
breakPointDate=2026-07-02,
currentMonthPlanTotal=48,
crossMonthPlanTotal=50,
finalMonthPlanTotal=98,
scene=WINDOW_HAS_PLAN_CROSS_MONTH
```

硫化余量计算处建议保留日志：

```text
[CuringRemainQty] sku=330200xxxx,
monthPlanTotal=98,
completedQty=0,
validLastMonthOverdueQty=0,
remainQty=98
```

这样可以清晰区分：

```text
月计划总量怎么算
余量公式怎么算
```

---

## 十一、验收用例

### 用例 1：非跨月，窗口内有计划

```text
T = 6.8
窗口 = 6.8、6.9、6.10

6.8  = 48
6.9  = 空
6.10 = 32
6.11 = 18
6.12 = 空
```

期望：

```text
最晚计划日 = 6.10
断点日 = 6.11
硫化月计划总量 = 6 月 day1～day11 合计
```

如果 day1～day11 合计为 98：

```text
硫化月计划总量 = 98
硫化余量 = 98 - 已完成量 + 上月超欠产
```

---

### 用例 2：跨月，窗口内最晚计划日跨月

```text
T = 6.29
窗口 = 6.29、6.30、7.1

6.29 = 48
6.30 = 空
7.1  = 32
7.2  = 18
7.3  = 空
```

期望：

```text
当月硫化月计划总量 = 48
跨月断点日 = 7.2
跨月计划量 = 7 月 day1～day2 = 50
硫化月计划总量 = 48 + 50 = 98
硫化余量 = 98 - 已完成量 + 上月超欠产
```

---

### 用例 3：窗口内无计划，当日之前还有余量

```text
T = 6.8
窗口 6.8、6.9、6.10 均无计划
```

如果：

```text
6 月 day1～day8 计划量合计 - 已完成量 + 上月超欠产 > 0
```

期望：

```text
硫化月计划总量 = 6 月 day1～day8 计划量合计
硫化余量 = 硫化月计划总量 - 已完成量 + 上月超欠产
```

---

### 用例 4：窗口内无计划，当日之前没有余量

```text
T = 6.8
窗口 6.8、6.9、6.10 均无计划
当日之前没有余量

6.12 = 32
6.13 = 18
6.14 = 空
```

期望：

```text
断点日 = 6.13
硫化月计划总量 = 6 月 day1～day13 合计
硫化余量 = 硫化月计划总量 - 已完成量 + 上月超欠产
```

---

### 用例 5：跨年

```text
窗口：
2026-12-30
2026-12-31
2027-01-01
```

期望：

```text
2026-12-30 -> 2026 年 12 月 day30
2026-12-31 -> 2026 年 12 月 day31
2027-01-01 -> 2027 年 1 月 day1
```

如果最晚计划日在 2027-01-01，则断点必须从 2027 年 1 月月计划中查找。

---

## 十二、最终交付要求

请完成：

1. 新增公共“硫化月计划总量计算器”；
2. 新增统一 `hasPlanQty` 方法；
3. 新增按 `LocalDate` 获取月计划 dayN 的方法；
4. 支持跨月、跨年读取月计划；
5. 实现断点查找逻辑；
6. 替换所有用于硫化余量公式的旧月计划总量取值；
7. 保持硫化余量公式不变；
8. 保持已完成量逻辑不变；
9. 保持上月超欠产逻辑不变；
10. 保持续作、换活字块、新增排产等主流程不变；
11. 补充日志；
12. 补充单元测试 / 集成测试；
13. 保证非跨月场景除“月计划总量新口径”外，不产生额外排程行为变化。

核心目标：

```text
不是改硫化余量公式；
而是把公式中的“月计划总量”从整月总量口径，改为按排程窗口、月计划断点、跨月计划段动态计算的口径。
```

## 十三、已落地实现口径（方案 1）

本次新增 `com.zlt.aps.lh.component.CuringMonthPlanTotalCalculator` 和 `com.zlt.aps.lh.api.domain.dto.CuringMonthPlanTotalResult`，作为硫化月计划总量的公共计算入口。该计算器只负责计算“硫化月计划总量”，不修改已完成量和上月超欠产逻辑。

### 1. 调用边界

S4.3 初始化 SKU 余量时，继续沿用原公式：

```text
硫化余量 = 硫化月计划总量 - 已完成量 + 上月超欠产
```

其中：

* `已完成量`：仍按项目现有“月累计完成量 + T 日排程晚班完成量”口径；
* `上月超欠产`：仍按项目现有 `lastMonthValidFlag` 口径；
* `硫化月计划总量`：改为调用 `com.zlt.aps.lh.component.CuringMonthPlanTotalCalculator`，按窗口、断点和跨月段动态计算。

### 2. 断点与跨月计算

计算器统一通过 `com.zlt.aps.lh.component.MonthPlanDateResolver` 读取真实业务日期所属年月的 dayN，并复用统一 `hasPlanQty` 判断。

窗口内有计划时：

* 先找 T～T+2 内最晚有计划日期；
* 从该日期所属月份继续向后找断点；
* 非跨月时汇总当月 day1～断点日；
* 最晚计划日跨月时，汇总 T 日所属月份断点前计划量 + 跨月月份 day1～跨月断点日计划量。

窗口内无计划时：

* 先用“当前月 day1～T 日计划量 - 已完成量 + 上月超欠产”判断历史余量；
* 仍有余量时，月计划总量取当前月 day1～T 日；
* 无余量时，仅在 T 日所属自然月内向后扫描后续计划段并按断点汇总；
* 不允许把下月计划段计入本月“窗口无计划”场景的硫化月计划总量。

### 3. 日志与排查

余量计算日志需要同时输出：

* `monthPlanTotal`；
* `actualFinishedQty`；
* `lastMonthOverdueQty`；
* `remainQty`；
* `crossMonth`；
* `breakPointDate`；
* `currentMonthPlanTotal`；
* `crossMonthPlanTotal`；
* `calculateScene`。

日志必须能区分“月计划总量怎么算”和“余量公式怎么算”。
