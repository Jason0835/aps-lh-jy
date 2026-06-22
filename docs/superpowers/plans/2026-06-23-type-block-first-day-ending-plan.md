# 换活字块仅处理 T 日收尾机台实施计划

> **执行要求：** 必须使用 `superpowers:executing-plans` 按任务执行；实现过程严格遵循测试驱动，先确认新增测试因缺少规则而失败，再编写最小生产代码。

**目标：** 仅允许最新预计收尾时间落在排程窗口第一天 T 日的机台参与 S4.4 换活字块，非 T 日机台完整保留给 S4.5 新增排产。

**架构：** 在 `TypeBlockProductionStrategy` 内新增私有日期准入方法，对收尾、续作释放、兜底三类候选汇总后统一过滤，并在每轮匹配前按最新 `estimatedEndTime` 再次复核。过滤只读取 `context.scheduleDate` 和机台预计收尾时间，不修改机台状态、待排 SKU、模具、胎胚或日计划账本。

**技术栈：** Java 8、Spring、JUnit 5、Mockito、Maven。

## 全局约束

- 使用 `context.scheduleDate` 作为第一天 T 日，不得使用 `scheduleTargetDate`。
- 只增加收尾日期前置判断，不修改同胎胚、同模具、机台硬性准入、定点机台、模具占用、胎胚库存及规格匹配规则。
- 不新增配置项、SQL、Mapper、XML、第三方依赖或无业务依据的兜底分支。
- 关键注释和日志使用简体中文，日志不得吞异常。
- 不修改与本需求无关的代码，不做大范围格式化。

---

## 文件结构

- 修改 `aps-lh/src/main/java/com/zlt/aps/lh/engine/strategy/impl/TypeBlockProductionStrategy.java`
  - 负责判断机台最新预计收尾时间是否属于 T 日，并在初始候选与每轮活动候选两个节点执行准入。
- 修改 `aps-lh/src/test/com/zlt/aps/lh/regression/ContinuousProductionTypeBlockRegressionTest.java`
  - 覆盖 T 日边界、T+1/T+2 排除、续作释放/兜底来源排除及同机台跨日连续换活字块阻断。

### Task 1：以回归测试锁定 T 日准入边界

**文件：**

- 修改：`aps-lh/src/test/com/zlt/aps/lh/regression/ContinuousProductionTypeBlockRegressionTest.java`
- 修改：`aps-lh/src/main/java/com/zlt/aps/lh/engine/strategy/impl/TypeBlockProductionStrategy.java`

**接口：**

- 输入：`LhScheduleContext.scheduleDate`、`MachineScheduleDTO.estimatedEndTime`、现有三类换活字块候选机台。
- 输出：私有方法 `isFirstDayEndingMachine(LhScheduleContext, MachineScheduleDTO)`，返回当前机台最新收尾时间是否属于 T 日。
- 输出：私有方法 `filterFirstDayEndingMachines(LhScheduleContext, List<MachineScheduleDTO>)`，返回只包含 T 日收尾机台的新列表。
- 保持：`scheduleTypeBlockChange(LhScheduleContext)` 公共接口不变。

- [ ] **Step 1：新增 T 日、T+1、T+2 初始候选行为测试**

在 `ContinuousProductionTypeBlockRegressionTest` 中加入以下测试。三个机台分别在 T 日最后一秒、T+1 零点和 T+2 收尾，只有 T 日机台允许生成换活字块结果，其余两个 SKU 必须留在新增待排列表：

```java
@Test
void scheduleTypeBlockChange_shouldOnlyUseMachinesEndingOnFirstDay() {
    LhScheduleContext context = newContext();
    MachineScheduleDTO firstDayMachine = buildMachine("M1", "MAT-C1");
    firstDayMachine.setEnding(true);
    firstDayMachine.setEstimatedEndTime(dateTime(2026, 4, 18, 23, 59, 59));
    MachineScheduleDTO secondDayMachine = buildMachine("M2", "MAT-C2");
    secondDayMachine.setEnding(true);
    secondDayMachine.setEstimatedEndTime(dateTime(2026, 4, 19, 0, 0, 0));
    MachineScheduleDTO thirdDayMachine = buildMachine("M3", "MAT-C3");
    thirdDayMachine.setEnding(true);
    thirdDayMachine.setEstimatedEndTime(dateTime(2026, 4, 20, 6, 0, 0));
    context.getMachineScheduleMap().put("M1", firstDayMachine);
    context.getMachineScheduleMap().put("M2", secondDayMachine);
    context.getMachineScheduleMap().put("M3", thirdDayMachine);

    context.getNewSpecSkuList().add(buildNewSku("MAT-T1", "EMB-1", "STRUCT-1", "SPEC-1", "PAT-1", 1));
    context.getNewSpecSkuList().add(buildNewSku("MAT-T2", "EMB-2", "STRUCT-2", "SPEC-2", "PAT-2", 1));
    context.getNewSpecSkuList().add(buildNewSku("MAT-T3", "EMB-3", "STRUCT-3", "SPEC-3", "PAT-3", 1));
    putMaterialInfo(context, "MAT-C1", "胎胚-1", "SPEC-C1", "PAT-C1", "PAT-C1");
    putMaterialInfo(context, "MAT-C2", "胎胚-2", "SPEC-C2", "PAT-C2", "PAT-C2");
    putMaterialInfo(context, "MAT-C3", "胎胚-3", "SPEC-C3", "PAT-C3", "PAT-C3");
    putMaterialInfo(context, "MAT-T1", "胎胚-1", "SPEC-1", "PAT-1", "PAT-1");
    putMaterialInfo(context, "MAT-T2", "胎胚-2", "SPEC-2", "PAT-2", "PAT-2");
    putMaterialInfo(context, "MAT-T3", "胎胚-3", "SPEC-3", "PAT-3", "PAT-3");
    putMouldRel(context, "MAT-C1", "MOULD-1");
    putMouldRel(context, "MAT-C2", "MOULD-2");
    putMouldRel(context, "MAT-C3", "MOULD-3");
    putMouldRel(context, "MAT-T1", "MOULD-1");
    putMouldRel(context, "MAT-T2", "MOULD-2");
    putMouldRel(context, "MAT-T3", "MOULD-3");

    when(orderNoGenerator.generateOrderNo(any())).thenReturn("ORD-1");
    when(endingJudgmentStrategy.isEnding(any(), any())).thenReturn(false);

    typeBlockProductionStrategy.scheduleTypeBlockChange(context);

    assertEquals(1, context.getScheduleResultList().size());
    assertEquals("MAT-T1", context.getScheduleResultList().get(0).getMaterialCode());
    assertEquals(Arrays.asList("MAT-T2", "MAT-T3"),
            Arrays.asList(
                    context.getNewSpecSkuList().get(0).getMaterialCode(),
                    context.getNewSpecSkuList().get(1).getMaterialCode()));
}
```

- [ ] **Step 2：新增续作释放与兜底来源非 T 日排除测试**

继续加入以下测试，验证统一前置判断覆盖两类非主收尾来源，并且不生成未排结果：

```java
@Test
void scheduleTypeBlockChange_shouldSkipReleasedAndFallbackMachinesEndingAfterFirstDay() {
    LhScheduleContext context = newContext();
    MachineScheduleDTO releasedMachine = buildMachine("M1", "MAT-C1");
    releasedMachine.setEstimatedEndTime(dateTime(2026, 4, 19, 6, 0, 0));
    MachineScheduleDTO fallbackMachine = buildMachine("M2", "MAT-C2");
    fallbackMachine.setEstimatedEndTime(dateTime(2026, 4, 20, 6, 0, 0));
    context.getMachineScheduleMap().put("M1", releasedMachine);
    context.getMachineScheduleMap().put("M2", fallbackMachine);
    context.getTypeBlockReleasedContinuousMachineCodeSet().add("M1");
    putMachineOnlineInfo(context, "M2", "MAT-C2");
    context.getPreviousScheduleResultList().add(
            buildPreviousScheduleResult("M2", "MAT-C2", "1",
                    dateTime(2026, 4, 17, 8, 0, 0), dateTime(2026, 4, 17, 8, 5, 0)));

    context.getNewSpecSkuList().add(buildNewSku("MAT-T1", "EMB-1", "STRUCT-1", "SPEC-1", "PAT-1", 1));
    context.getNewSpecSkuList().add(buildNewSku("MAT-T2", "EMB-2", "STRUCT-2", "SPEC-2", "PAT-2", 1));
    putMaterialInfo(context, "MAT-C1", "胎胚-1", "SPEC-C1", "PAT-C1", "PAT-C1");
    putMaterialInfo(context, "MAT-C2", "胎胚-2", "SPEC-C2", "PAT-C2", "PAT-C2");
    putMaterialInfo(context, "MAT-T1", "胎胚-1", "SPEC-1", "PAT-1", "PAT-1");
    putMaterialInfo(context, "MAT-T2", "胎胚-2", "SPEC-2", "PAT-2", "PAT-2");
    putMouldRel(context, "MAT-C1", "MOULD-1");
    putMouldRel(context, "MAT-C2", "MOULD-2");
    putMouldRel(context, "MAT-T1", "MOULD-1");
    putMouldRel(context, "MAT-T2", "MOULD-2");

    typeBlockProductionStrategy.scheduleTypeBlockChange(context);

    assertEquals(0, context.getScheduleResultList().size());
    assertEquals(0, context.getUnscheduledResultList().size());
    assertEquals(2, context.getNewSpecSkuList().size());
}
```

- [ ] **Step 3：新增机台最新收尾时间跨入 T+1 后停止连续换活字块测试**

继续加入以下测试，验证每轮匹配前会重新读取机台最新 `estimatedEndTime`：

```java
@Test
void scheduleTypeBlockChange_shouldStopChainingWhenLatestEndTimeMovesToNextDay() {
    LhScheduleContext context = newContext();
    MachineScheduleDTO machine = buildMachine("M1", "MAT-C1");
    machine.setEnding(true);
    machine.setEstimatedEndTime(dateTime(2026, 4, 18, 20, 0, 0));
    context.getMachineScheduleMap().put("M1", machine);
    context.getNewSpecSkuList().add(buildNewSku("MAT-T1", "EMB-1", "STRUCT-1", "SPEC-1", "PAT-1", 1));
    context.getNewSpecSkuList().add(buildNewSku("MAT-T2", "EMB-1", "STRUCT-2", "SPEC-2", "PAT-2", 1));
    putMaterialInfo(context, "MAT-C1", "胎胚-1", "SPEC-C1", "PAT-C1", "PAT-C1");
    putMaterialInfo(context, "MAT-T1", "胎胚-1", "SPEC-1", "PAT-1", "PAT-1");
    putMaterialInfo(context, "MAT-T2", "胎胚-1", "SPEC-2", "PAT-2", "PAT-2");
    putMouldRel(context, "MAT-C1", "MOULD-1");
    putMouldRel(context, "MAT-T1", "MOULD-1");
    putMouldRel(context, "MAT-T2", "MOULD-1");

    when(orderNoGenerator.generateOrderNo(any())).thenReturn("ORD-1", "ORD-2");
    when(endingJudgmentStrategy.isEnding(any(), any())).thenAnswer(invocation -> {
        SkuScheduleDTO sku = invocation.getArgument(1);
        return sku != null && "MAT-T1".equals(sku.getMaterialCode());
    });

    typeBlockProductionStrategy.scheduleTypeBlockChange(context);

    assertEquals(1, context.getScheduleResultList().size());
    assertEquals("MAT-T1", context.getScheduleResultList().get(0).getMaterialCode());
    assertEquals(1, context.getNewSpecSkuList().size());
    assertEquals("MAT-T2", context.getNewSpecSkuList().get(0).getMaterialCode());
}
```

- [ ] **Step 4：运行新增测试并确认按预期失败**

执行：

```bash
mvn -pl aps-lh -am -Dtest=ContinuousProductionTypeBlockRegressionTest \
    -Dsurefire.failIfNoSpecifiedTests=false test
```

预期：新增的 T+1/T+2、释放/兜底或跨日连续换活字块断言至少一项失败；失败原因是现有实现尚未限制收尾日期，而不是编译错误、测试数据缺失或空指针。

- [ ] **Step 5：实现 T 日收尾日期判断与初始候选过滤**

在 `TypeBlockProductionStrategy` 中复用现有 `LocalDate`、`ZoneId` 导入，增加以下私有方法：

```java
/**
 * 过滤只允许在排程窗口第一天 T 日收尾的换活字块机台。
 *
 * @param context 排程上下文
 * @param candidateMachines 原候选机台
 * @return T 日收尾候选机台
 */
private List<MachineScheduleDTO> filterFirstDayEndingMachines(
        LhScheduleContext context, List<MachineScheduleDTO> candidateMachines) {
    if (CollectionUtils.isEmpty(candidateMachines)) {
        return new ArrayList<MachineScheduleDTO>(0);
    }
    List<MachineScheduleDTO> firstDayMachines = new ArrayList<>(candidateMachines.size());
    List<String> skippedMachineSummaries = new ArrayList<>(candidateMachines.size());
    for (MachineScheduleDTO machine : candidateMachines) {
        if (isFirstDayEndingMachine(context, machine)) {
            firstDayMachines.add(machine);
            continue;
        }
        skippedMachineSummaries.add(String.format("%s@%s",
                machine == null ? "-" : machine.getMachineCode(),
                machine == null ? "-" : LhScheduleTimeUtil.formatDateTime(machine.getEstimatedEndTime())));
    }
    if (!CollectionUtils.isEmpty(skippedMachineSummaries)) {
        log.info("非T日收尾机台跳过换活字块, T日: {}, 过滤前候选: {}, 跳过机台数: {}, 跳过明细: {}",
                LhScheduleTimeUtil.formatDate(context.getScheduleDate()), candidateMachines.size(),
                skippedMachineSummaries.size(), String.join(",", skippedMachineSummaries));
    }
    return firstDayMachines;
}

/**
 * 判断机台最新预计收尾时间是否落在排程窗口第一天 T 日。
 *
 * @param context 排程上下文
 * @param machine 机台
 * @return true-T 日收尾，false-非 T 日收尾
 */
private boolean isFirstDayEndingMachine(LhScheduleContext context, MachineScheduleDTO machine) {
    if (Objects.isNull(context)
            || Objects.isNull(context.getScheduleDate())
            || Objects.isNull(machine)
            || Objects.isNull(machine.getEstimatedEndTime())) {
        return false;
    }
    LocalDate firstDay = context.getScheduleDate().toInstant()
            .atZone(ZoneId.systemDefault()).toLocalDate();
    LocalDate endingDay = machine.getEstimatedEndTime().toInstant()
            .atZone(ZoneId.systemDefault()).toLocalDate();
    return firstDay.equals(endingDay);
}
```

在三类候选汇总完成后、`traceEndingMachineOrder(...)` 之前加入：

```java
// 换活字块仅处理最新收尾时间落在第一天 T 日的机台，其他机台保留给 S4.5 新增排产。
candidateMachines = filterFirstDayEndingMachines(context, candidateMachines);
```

- [ ] **Step 6：在每轮匹配前复核机台最新收尾日期**

将 `buildActiveMachineList(...)` 增加 `LhScheduleContext context` 参数，并在现有完成状态判断之后、触发来源判断之前加入最新日期准入：

```java
private List<MachineScheduleDTO> buildActiveMachineList(LhScheduleContext context,
                                                        List<MachineScheduleDTO> candidateMachines,
                                                        Map<String, String> machineTriggerSourceMap,
                                                        Map<String, Boolean> completedMachineMap) {
    List<MachineScheduleDTO> activeMachines = new ArrayList<>(candidateMachines.size());
    for (MachineScheduleDTO machine : candidateMachines) {
        if (machine == null || StringUtils.isEmpty(machine.getMachineCode())) {
            continue;
        }
        String machineCode = machine.getMachineCode();
        if (Boolean.TRUE.equals(completedMachineMap.get(machineCode))) {
            continue;
        }
        if (!isFirstDayEndingMachine(context, machine)) {
            completedMachineMap.put(machineCode, true);
            log.info("机台最新收尾时间已不在T日，停止换活字块并交由新增排产, machineCode: {}, estimatedEndTime: {}, T日: {}",
                    machineCode, LhScheduleTimeUtil.formatDateTime(machine.getEstimatedEndTime()),
                    LhScheduleTimeUtil.formatDate(context.getScheduleDate()));
            continue;
        }
        String triggerSource = machineTriggerSourceMap.get(machineCode);
        if (StringUtils.equals(TYPE_BLOCK_TRIGGER_ENDING, triggerSource) && !machine.isEnding()) {
            completedMachineMap.put(machineCode, true);
            continue;
        }
        activeMachines.add(machine);
    }
    return activeMachines;
}
```

同步修改唯一调用点：

```java
List<MachineScheduleDTO> activeMachines = buildActiveMachineList(
        context, candidateMachines, machineTriggerSourceMap, completedMachineMap);
```

- [ ] **Step 7：运行定向测试并确认通过**

执行：

```bash
mvn -pl aps-lh -am -Dtest=ContinuousProductionTypeBlockRegressionTest \
    -Dsurefire.failIfNoSpecifiedTests=false test
```

预期：`ContinuousProductionTypeBlockRegressionTest` 全部通过，新增测试确认 T 日边界、非 T 日三类来源排除及跨日连续换活字块停止。

- [ ] **Step 8：执行编译与差异检查**

执行：

```bash
mvn -pl aps-lh -am -DskipTests compile
git diff --check
git status --short
```

预期：编译成功；`git diff --check` 无输出；工作区只包含本需求的生产代码、回归测试及已确认的设计/计划文档差异。

- [ ] **Step 9：提交实现**

```bash
git add \
    aps-lh/src/main/java/com/zlt/aps/lh/engine/strategy/impl/TypeBlockProductionStrategy.java \
    aps-lh/src/test/com/zlt/aps/lh/regression/ContinuousProductionTypeBlockRegressionTest.java \
    docs/superpowers/specs/2026-06-23-type-block-first-day-ending-design.md \
    docs/superpowers/plans/2026-06-23-type-block-first-day-ending-plan.md
git commit -m "修复：换活字块仅处理T日收尾机台"
```

预期：提交成功，提交内容不包含 SQL、XML、配置或无关文件。
