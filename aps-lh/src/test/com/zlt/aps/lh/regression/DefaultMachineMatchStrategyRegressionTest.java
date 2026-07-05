package com.zlt.aps.lh.regression;

import com.zlt.aps.lh.api.constant.LhScheduleParamConstant;
import com.zlt.aps.lh.api.domain.dto.MachineScheduleDTO;
import com.zlt.aps.lh.api.domain.dto.SkuScheduleDTO;
import com.zlt.aps.lh.api.domain.entity.LhScheduleProcessLog;
import com.zlt.aps.lh.api.domain.vo.LhShiftConfigVO;
import com.zlt.aps.lh.api.domain.entity.LhSpecifyMachine;
import com.zlt.aps.lh.api.enums.ConstructionStageEnum;
import com.zlt.aps.lh.api.enums.JobTypeEnum;
import com.zlt.aps.lh.context.LhScheduleConfig;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.engine.strategy.impl.DefaultMachineMatchStrategy;
import com.zlt.aps.lh.util.LhScheduleTimeUtil;
import com.zlt.aps.mdm.api.domain.entity.MdmDevicePlanShut;
import com.zlt.aps.mdm.api.domain.entity.MdmMaterialInfo;
import com.zlt.aps.mdm.api.domain.entity.MdmModelInfo;
import com.zlt.aps.mdm.api.domain.entity.MdmSkuMouldRel;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 机台匹配回归：SKU存在多条模具关系时，不应把关系条数误当成待选前的用模数。
 */
class DefaultMachineMatchStrategyRegressionTest {

    @Test
    void matchMachines_shouldKeepRestrictedSpecifyMachineButUseMachineCodeWhenListedLevelsTie() {
        DefaultMachineMatchStrategy strategy = new DefaultMachineMatchStrategy();
        LhScheduleContext context = buildContext();
        enableSpecifyMachineRule(context);

        MachineScheduleDTO normalMachine = machine("M-NORMAL", dateTime(2026, 4, 21, 8, 0),
                "SPEC-A", "22.5", "MAT-NORMAL");
        MachineScheduleDTO specifyMachine = machine("M-SPECIFY", dateTime(2026, 4, 21, 8, 10),
                "SPEC-A", "22.5", "MAT-SPECIFY");
        // 定点配置仍参与候选保留和诊断，但不再作为新增选机排序层级。
        specifyMachine.setSupport225WideBase("1");
        context.getMachineScheduleMap().put(normalMachine.getMachineCode(), normalMachine);
        context.getMachineScheduleMap().put(specifyMachine.getMachineCode(), specifyMachine);
        context.getSpecifyMachineMap().put("MAT-001", Collections.singletonList(
                specifyMachine("MAT-001", "M-SPECIFY", JobTypeEnum.RESTRICTED.getCode())));

        SkuScheduleDTO sku = sku("MAT-001", "SPEC-A", "22.5");

        List<MachineScheduleDTO> candidates = strategy.matchMachines(context, sku);

        assertEquals(2, candidates.size(), "限制作业机台不应过滤普通候选机台");
        assertEquals("M-NORMAL", candidates.get(0).getMachineCode(),
                "定点优先已不在新增选机排序调用链内，同层候选应按机台编码兜底");
    }

    @Test
    void matchMachines_shouldFallbackToNormalMachineWhenLimitMachineUnavailable() {
        DefaultMachineMatchStrategy strategy = new DefaultMachineMatchStrategy();
        LhScheduleContext context = buildContext();
        enableSpecifyMachineRule(context);

        MachineScheduleDTO disabledSpecifyMachine = machine("M-SPECIFY", dateTime(2026, 4, 21, 7, 0),
                "SPEC-A", "22.5", "MAT-SPECIFY");
        disabledSpecifyMachine.setStatus("0");
        MachineScheduleDTO normalMachine = machine("M-NORMAL", dateTime(2026, 4, 21, 8, 0),
                "SPEC-A", "22.5", "MAT-NORMAL");
        context.getMachineScheduleMap().put(disabledSpecifyMachine.getMachineCode(), disabledSpecifyMachine);
        context.getMachineScheduleMap().put(normalMachine.getMachineCode(), normalMachine);
        context.getSpecifyMachineMap().put("MAT-001", Collections.singletonList(
                specifyMachine("MAT-001", "M-SPECIFY", JobTypeEnum.RESTRICTED.getCode())));

        List<MachineScheduleDTO> candidates = strategy.matchMachines(context, sku("MAT-001", "SPEC-A", "22.5"));

        assertEquals(1, candidates.size(), "定点机台不可排时，应回到普通机台匹配逻辑");
        assertEquals("M-NORMAL", candidates.get(0).getMachineCode());
    }

    @Test
    void matchMachines_shouldExcludeNotAllowedMachineByMaterialCode() {
        DefaultMachineMatchStrategy strategy = new DefaultMachineMatchStrategy();
        LhScheduleContext context = buildContext();
        enableSpecifyMachineRule(context);

        MachineScheduleDTO forbiddenMachine = machine("M-FORBIDDEN", dateTime(2026, 4, 21, 7, 0),
                "SPEC-A", "22.5", "MAT-FORBIDDEN");
        MachineScheduleDTO normalMachine = machine("M-NORMAL", dateTime(2026, 4, 21, 8, 0),
                "SPEC-A", "22.5", "MAT-NORMAL");
        context.getMachineScheduleMap().put(forbiddenMachine.getMachineCode(), forbiddenMachine);
        context.getMachineScheduleMap().put(normalMachine.getMachineCode(), normalMachine);
        context.getSpecifyMachineMap().put("MAT-001", Collections.singletonList(
                specifyMachine("MAT-001", "M-FORBIDDEN", JobTypeEnum.NOT_ALLOWED.getCode())));

        List<MachineScheduleDTO> candidates = strategy.matchMachines(context, sku("MAT-001", "SPEC-A", "22.5"));

        assertEquals(1, candidates.size(), "不可作业机台必须按物料编码定点关系排除");
        assertEquals("M-NORMAL", candidates.get(0).getMachineCode());
    }

    @Test
    void matchMachines_shouldIgnoreLimitSpecifyPriorityWhenRuleDisabled() {
        DefaultMachineMatchStrategy strategy = new DefaultMachineMatchStrategy();
        LhScheduleContext context = buildContext();

        MachineScheduleDTO normalMachine = machine("M-NORMAL", dateTime(2026, 4, 21, 8, 0),
                "SPEC-A", "22.5", "MAT-NORMAL");
        MachineScheduleDTO specifyMachine = machine("M-SPECIFY", dateTime(2026, 4, 21, 8, 10),
                "SPEC-A", "22.5", "MAT-SPECIFY");
        context.getMachineScheduleMap().put(normalMachine.getMachineCode(), normalMachine);
        context.getMachineScheduleMap().put(specifyMachine.getMachineCode(), specifyMachine);
        context.getSpecifyMachineMap().put("MAT-001", Collections.singletonList(
                specifyMachine("MAT-001", "M-SPECIFY", JobTypeEnum.RESTRICTED.getCode())));

        List<MachineScheduleDTO> candidates = strategy.matchMachines(context, sku("MAT-001", "SPEC-A", "22.5"));

        assertEquals(2, candidates.size(), "关闭开关后定点机台不应提升优先级");
        assertEquals("M-NORMAL", candidates.get(0).getMachineCode());
    }

    @Test
    void matchMachines_shouldIgnoreNotAllowedMachineWhenRuleDisabled() {
        DefaultMachineMatchStrategy strategy = new DefaultMachineMatchStrategy();
        LhScheduleContext context = buildContext();

        MachineScheduleDTO forbiddenMachine = machine("M-FORBIDDEN", dateTime(2026, 4, 21, 7, 0),
                "SPEC-A", "22.5", "MAT-FORBIDDEN");
        MachineScheduleDTO normalMachine = machine("M-NORMAL", dateTime(2026, 4, 21, 7, 10),
                "SPEC-A", "22.5", "MAT-NORMAL");
        context.getMachineScheduleMap().put(forbiddenMachine.getMachineCode(), forbiddenMachine);
        context.getMachineScheduleMap().put(normalMachine.getMachineCode(), normalMachine);
        context.getSpecifyMachineMap().put("MAT-001", Collections.singletonList(
                specifyMachine("MAT-001", "M-FORBIDDEN", JobTypeEnum.NOT_ALLOWED.getCode())));

        List<MachineScheduleDTO> candidates = strategy.matchMachines(context, sku("MAT-001", "SPEC-A", "22.5"));

        assertEquals(2, candidates.size(), "关闭开关后不可作业配置不应过滤候选机台");
        assertEquals("M-FORBIDDEN", candidates.get(0).getMachineCode());
    }

    @Test
    void matchMachines_shouldKeepReleasedContinuousMachineAndUseMachineCodeWhenListedLevelsTie() {
        DefaultMachineMatchStrategy strategy = new DefaultMachineMatchStrategy();
        LhScheduleContext context = buildContext();
        context.getReleasedContinuousMachineCodeSet().add("A-RELEASED");
        context.getFirstDayNoPlanReleasedContinuousMachineCodeSet().add("A-RELEASED");

        MachineScheduleDTO releasedMachine = machine("A-RELEASED", dateTime(2026, 4, 21, 7, 0),
                "SPEC-A", "22.5", "MAT-OLD");
        MachineScheduleDTO normalMachine = machine("Z-NORMAL", dateTime(2026, 4, 21, 7, 10),
                "SPEC-A", "22.5", "MAT-NORMAL");
        context.getMachineScheduleMap().put(releasedMachine.getMachineCode(), releasedMachine);
        context.getMachineScheduleMap().put(normalMachine.getMachineCode(), normalMachine);

        List<MachineScheduleDTO> candidates = strategy.matchMachines(context, sku("MAT-001", "SPEC-A", "22.5"));

        assertEquals(2, candidates.size(), "续作释放机台不应从候选中移除");
        assertEquals("A-RELEASED", candidates.get(0).getMachineCode(),
                "续作释放降级已不在新增选机排序调用链内，同层候选应按机台编码兜底");
        assertEquals("Z-NORMAL", candidates.get(1).getMachineCode());
    }

    @Test
    void matchMachines_shouldKeepReleasedContinuousMachineWhenOnlyCandidate() {
        DefaultMachineMatchStrategy strategy = new DefaultMachineMatchStrategy();
        LhScheduleContext context = buildContext();
        context.getReleasedContinuousMachineCodeSet().add("M-ONLY");

        MachineScheduleDTO releasedMachine = machine("M-ONLY", dateTime(2026, 4, 21, 7, 0),
                "SPEC-A", "22.5", "MAT-OLD");
        context.getMachineScheduleMap().put(releasedMachine.getMachineCode(), releasedMachine);

        List<MachineScheduleDTO> candidates = strategy.matchMachines(context, sku("MAT-001", "SPEC-A", "22.5"));

        assertEquals(1, candidates.size(), "续作释放机台是唯一候选时仍允许承接新增SKU");
        assertEquals("M-ONLY", candidates.get(0).getMachineCode());
    }

    @Test
    void matchMachines_shouldTraceTodayIdleAsDiagnosticWhenMachineCodeFallbackWins() {
        DefaultMachineMatchStrategy strategy = new DefaultMachineMatchStrategy();
        LhScheduleContext context = buildTraceContext();

        MachineScheduleDTO idleMachine = machine("M-IDLE", dateTime(2026, 4, 20, 8, 0),
                "SPEC-X", "22.5", "MAT-IDLE");
        MachineScheduleDTO occupiedMachine = machine("M-OCCUPIED", dateTime(2026, 4, 21, 6, 10),
                "SPEC-X", "22.5", "MAT-OLD");
        context.getMachineScheduleMap().put(occupiedMachine.getMachineCode(), occupiedMachine);
        context.getMachineScheduleMap().put(idleMachine.getMachineCode(), idleMachine);
        context.getMachineAssignmentMap().put("M-OCCUPIED", Collections.singletonList(
                assignedResult("M-OCCUPIED", "MAT-OLD", dateTime(2026, 4, 21, 6, 10))));

        SkuScheduleDTO sku = sku("MAT-001", "SPEC-A", "22.5");
        LhShiftConfigVO firstShift = context.getScheduleWindowShifts().get(0);
        Map<LocalDate, com.zlt.aps.lh.api.domain.dto.SkuDailyPlanQuotaDTO> quotaMap = new LinkedHashMap<>();
        quotaMap.put(toLocalDate(firstShift), quota("MAT-001", toLocalDate(firstShift), 32));
        sku.setDailyPlanQuotaMap(quotaMap);
        sku.setDailyPlanQty(32);
        sku.setRemainingScheduleQty(32);

        List<MachineScheduleDTO> candidates = strategy.matchMachines(context, sku);

        assertEquals("M-IDLE", candidates.get(0).getMachineCode(), "排序层级相同时应按机台编码兜底");
        assertEquals(1, context.getScheduleLogList().size());
        String logDetail = context.getScheduleLogList().get(0).getLogDetail();
        assertTrue(logDetail.contains("L8_机台编码"), "排序日志必须暴露机台编码兜底层级");
        assertTrue(!logDetail.contains("L10_当天空闲优先"), "当天空闲不再作为新增选机排序层级");
        assertTrue(logDetail.contains("当天空闲=1"), "候选明细必须输出当天空闲标识");
        assertTrue(logDetail.contains("当天需排产=1"), "候选明细必须输出当天需排产标识");
    }

    @Test
    void isSkuNeedScheduleOnFirstDay_shouldUseCompensationEarlyProductionAdmission() {
        DefaultMachineMatchStrategy strategy = new DefaultMachineMatchStrategy();
        LhScheduleContext context = buildContext();
        LhShiftConfigVO firstShift = context.getScheduleWindowShifts().get(0);
        LhShiftConfigVO nextDayShift = context.getScheduleWindowShifts().get(2);

        SkuScheduleDTO sku = sku("3302002546", "SPEC-A", "22.5");
        sku.setContinuousCompensationSku(true);
        sku.setDailyPlanQty(0);
        sku.setRemainingScheduleQty(82);
        Map<LocalDate, com.zlt.aps.lh.api.domain.dto.SkuDailyPlanQuotaDTO> quotaMap = new LinkedHashMap<>();
        quotaMap.put(toLocalDate(firstShift), quota("3302002546", toLocalDate(firstShift), 0));
        quotaMap.put(toLocalDate(nextDayShift), quota("3302002546", toLocalDate(nextDayShift), 32));
        sku.setDailyPlanQuotaMap(quotaMap);
        context.getNewSpecEarlyProductionAllowedMap().put(sku, Boolean.TRUE);

        Boolean needScheduleOnFirstDay = ReflectionTestUtils.invokeMethod(
                strategy, "isSkuNeedScheduleOnFirstDay", context, sku);

        assertTrue(Boolean.TRUE.equals(needScheduleOnFirstDay),
                "补偿SKU提前生产准入通过时，选机画像应按窗口首日排产识别");
    }

    @Test
    void resolveCandidateReferenceTime_shouldIgnoreTentativeContinuousEndTimeForReleasedMachine() {
        DefaultMachineMatchStrategy strategy = new DefaultMachineMatchStrategy();
        LhScheduleContext context = buildContext();
        context.getReleasedContinuousMachineCodeSet().add("M-RELEASED");
        context.getFirstDayNoPlanReleasedContinuousMachineCodeSet().add("M-RELEASED");

        MachineScheduleDTO initialMachine = machine("M-RELEASED", dateTime(2026, 4, 21, 6, 0),
                "SPEC-A", "22.5", "3302002546");
        MachineScheduleDTO runtimeMachine = machine("M-RELEASED", dateTime(2026, 4, 23, 21, 12),
                "SPEC-A", "22.5", "3302002546");
        context.getInitialMachineScheduleMap().put(initialMachine.getMachineCode(), initialMachine);
        context.getMachineScheduleMap().put(runtimeMachine.getMachineCode(), runtimeMachine);

        LhShiftConfigVO morningShift = context.getScheduleWindowShifts().get(0);
        LhShiftConfigVO nextDayShift = context.getScheduleWindowShifts().get(2);
        Map<LocalDate, com.zlt.aps.lh.api.domain.dto.SkuDailyPlanQuotaDTO> quotaMap = new LinkedHashMap<>();
        quotaMap.put(toLocalDate(morningShift), quota("3302002546", toLocalDate(morningShift), 0));
        quotaMap.put(toLocalDate(nextDayShift), quota("3302002546", toLocalDate(nextDayShift), 32));
        SkuScheduleDTO sourceSku = sku("3302002546", "SPEC-A", "22.5");
        sourceSku.setDailyPlanQuotaMap(quotaMap);

        com.zlt.aps.lh.api.domain.entity.LhScheduleResult assignedResult =
                new com.zlt.aps.lh.api.domain.entity.LhScheduleResult();
        assignedResult.setLhMachineCode("M-RELEASED");
        assignedResult.setMaterialCode("3302002546");
        assignedResult.setScheduleType("01");
        context.getMachineAssignmentMap().put("M-RELEASED", Collections.singletonList(assignedResult));
        context.getScheduleResultSourceSkuMap().put(assignedResult, sourceSku);

        Date referenceTime = ReflectionTestUtils.invokeMethod(
                strategy, "resolveCandidateReferenceTime", context, runtimeMachine);

        assertEquals(initialMachine.getEstimatedEndTime(), referenceTime,
                "首日无计划释放的续作机台在新增选机画像中应回退到初始机台就绪时刻，不应沿用续作结果的晚收尾时间");
    }

    @Test
    void resolveOtherSkuOccupiedScore_shouldIgnoreTentativeContinuousAssignmentForReleasedMachine() {
        DefaultMachineMatchStrategy strategy = new DefaultMachineMatchStrategy();
        LhScheduleContext context = buildContext();
        context.getReleasedContinuousMachineCodeSet().add("M-RELEASED");
        context.getFirstDayNoPlanReleasedContinuousMachineCodeSet().add("M-RELEASED");

        MachineScheduleDTO machine = machine("M-RELEASED", dateTime(2026, 4, 23, 21, 12),
                "SPEC-A", "22.5", "3302002546");
        context.getMachineScheduleMap().put(machine.getMachineCode(), machine);

        LhShiftConfigVO morningShift = context.getScheduleWindowShifts().get(0);
        LhShiftConfigVO nextDayShift = context.getScheduleWindowShifts().get(2);
        Map<LocalDate, com.zlt.aps.lh.api.domain.dto.SkuDailyPlanQuotaDTO> quotaMap = new LinkedHashMap<>();
        quotaMap.put(toLocalDate(morningShift), quota("3302002546", toLocalDate(morningShift), 0));
        quotaMap.put(toLocalDate(nextDayShift), quota("3302002546", toLocalDate(nextDayShift), 32));
        SkuScheduleDTO sourceSku = sku("3302002546", "SPEC-A", "22.5");
        sourceSku.setDailyPlanQuotaMap(quotaMap);

        com.zlt.aps.lh.api.domain.entity.LhScheduleResult assignedResult =
                new com.zlt.aps.lh.api.domain.entity.LhScheduleResult();
        assignedResult.setLhMachineCode("M-RELEASED");
        assignedResult.setMaterialCode("3302002546");
        assignedResult.setScheduleType("01");
        context.getMachineAssignmentMap().put("M-RELEASED", Collections.singletonList(assignedResult));
        context.getScheduleResultSourceSkuMap().put(assignedResult, sourceSku);

        Integer occupiedScore = ReflectionTestUtils.invokeMethod(
                strategy, "resolveOtherSkuOccupiedScore", context, sku("MAT-001", "SPEC-A", "22.5"), machine);

        assertEquals(Integer.valueOf(0), occupiedScore,
                "首日无计划释放的续作占位结果不应再把机台标记成被其他SKU占用");
    }

    @Test
    void resolveCandidateReferenceTime_shouldIgnoreReleasedPlaceholderAfterQuotaConsumed() {
        DefaultMachineMatchStrategy strategy = new DefaultMachineMatchStrategy();
        LhScheduleContext context = buildContext();
        context.getReleasedContinuousMachineCodeSet().add("M-RELEASED");
        context.getFirstDayNoPlanReleasedContinuousMachineCodeSet().add("M-RELEASED");

        MachineScheduleDTO initialMachine = machine("M-RELEASED", dateTime(2026, 4, 21, 6, 0),
                "SPEC-A", "22.5", "3302002546");
        MachineScheduleDTO runtimeMachine = machine("M-RELEASED", dateTime(2026, 4, 23, 21, 12),
                "SPEC-A", "22.5", "3302002546");
        context.getInitialMachineScheduleMap().put(initialMachine.getMachineCode(), initialMachine);
        context.getMachineScheduleMap().put(runtimeMachine.getMachineCode(), runtimeMachine);

        LhShiftConfigVO morningShift = context.getScheduleWindowShifts().get(0);
        LhShiftConfigVO nextDayShift = context.getScheduleWindowShifts().get(2);
        Map<LocalDate, com.zlt.aps.lh.api.domain.dto.SkuDailyPlanQuotaDTO> quotaMap = new LinkedHashMap<>();
        quotaMap.put(toLocalDate(morningShift), quota("3302002546", toLocalDate(morningShift), 0));
        com.zlt.aps.lh.api.domain.dto.SkuDailyPlanQuotaDTO nextDayQuota =
                quota("3302002546", toLocalDate(nextDayShift), 32);
        nextDayQuota.setRemainingQty(0);
        quotaMap.put(toLocalDate(nextDayShift), nextDayQuota);
        SkuScheduleDTO sourceSku = sku("3302002546", "SPEC-A", "22.5");
        sourceSku.setDailyPlanQuotaMap(quotaMap);

        com.zlt.aps.lh.api.domain.entity.LhScheduleResult assignedResult =
                new com.zlt.aps.lh.api.domain.entity.LhScheduleResult();
        assignedResult.setLhMachineCode("M-RELEASED");
        assignedResult.setMaterialCode("3302002546");
        assignedResult.setScheduleType("01");
        context.getMachineAssignmentMap().put("M-RELEASED", Collections.singletonList(assignedResult));
        context.getScheduleResultSourceSkuMap().put(assignedResult, sourceSku);

        Date referenceTime = ReflectionTestUtils.invokeMethod(
                strategy, "resolveCandidateReferenceTime", context, runtimeMachine);

        assertEquals(initialMachine.getEstimatedEndTime(), referenceTime,
                "即使后续日计划额度已被这条占位结果扣成0，释放续作机台的选机参考时间仍应回退到初始机台时刻");
    }

    @Test
    void resolveCandidateReferenceTime_shouldUseAssignedEndTimeWhenMachineEndTimeMissing() {
        DefaultMachineMatchStrategy strategy = new DefaultMachineMatchStrategy();
        LhScheduleContext context = buildContext();
        MachineScheduleDTO runtimeMachine = machine("M-OCCUPIED", null, "SPEC-A", "22.5", "MAT-A");
        context.getMachineScheduleMap().put(runtimeMachine.getMachineCode(), runtimeMachine);

        com.zlt.aps.lh.api.domain.entity.LhScheduleResult assignedResult =
                new com.zlt.aps.lh.api.domain.entity.LhScheduleResult();
        assignedResult.setLhMachineCode("M-OCCUPIED");
        assignedResult.setMaterialCode("MAT-A");
        assignedResult.setDailyPlanQty(2);
        assignedResult.setSpecEndTime(dateTime(2026, 4, 22, 6, 0));
        context.getMachineAssignmentMap().put("M-OCCUPIED", Collections.singletonList(assignedResult));

        Date referenceTime = ReflectionTestUtils.invokeMethod(
                strategy, "resolveCandidateReferenceTime", context, runtimeMachine);

        assertEquals(assignedResult.getSpecEndTime(), referenceTime,
                "机台运行态结束时间缺失时，新增选机画像应使用同机台已登记有效结果的结束时间");
    }

    @Test
    void resolveOtherSkuOccupiedScore_shouldIgnoreReleasedPlaceholderAfterQuotaConsumed() {
        DefaultMachineMatchStrategy strategy = new DefaultMachineMatchStrategy();
        LhScheduleContext context = buildContext();
        context.getReleasedContinuousMachineCodeSet().add("M-RELEASED");
        context.getFirstDayNoPlanReleasedContinuousMachineCodeSet().add("M-RELEASED");

        MachineScheduleDTO machine = machine("M-RELEASED", dateTime(2026, 4, 23, 21, 12),
                "SPEC-A", "22.5", "3302002546");
        context.getMachineScheduleMap().put(machine.getMachineCode(), machine);

        LhShiftConfigVO morningShift = context.getScheduleWindowShifts().get(0);
        LhShiftConfigVO nextDayShift = context.getScheduleWindowShifts().get(2);
        Map<LocalDate, com.zlt.aps.lh.api.domain.dto.SkuDailyPlanQuotaDTO> quotaMap = new LinkedHashMap<>();
        quotaMap.put(toLocalDate(morningShift), quota("3302002546", toLocalDate(morningShift), 0));
        com.zlt.aps.lh.api.domain.dto.SkuDailyPlanQuotaDTO nextDayQuota =
                quota("3302002546", toLocalDate(nextDayShift), 32);
        nextDayQuota.setRemainingQty(0);
        quotaMap.put(toLocalDate(nextDayShift), nextDayQuota);
        SkuScheduleDTO sourceSku = sku("3302002546", "SPEC-A", "22.5");
        sourceSku.setDailyPlanQuotaMap(quotaMap);

        com.zlt.aps.lh.api.domain.entity.LhScheduleResult assignedResult =
                new com.zlt.aps.lh.api.domain.entity.LhScheduleResult();
        assignedResult.setLhMachineCode("M-RELEASED");
        assignedResult.setMaterialCode("3302002546");
        assignedResult.setScheduleType("01");
        context.getMachineAssignmentMap().put("M-RELEASED", Collections.singletonList(assignedResult));
        context.getScheduleResultSourceSkuMap().put(assignedResult, sourceSku);

        Integer occupiedScore = ReflectionTestUtils.invokeMethod(
                strategy, "resolveOtherSkuOccupiedScore", context, sku("MAT-001", "SPEC-A", "22.5"), machine);

        assertEquals(Integer.valueOf(0), occupiedScore,
                "即使后续日计划额度已被占位结果扣完，释放续作机台也不应重新被判成已被其他SKU占用");
    }

    @Test
    void matchMachines_shouldKeepSingleControlPriorityForMassTrialSkuWhenSingleControlIsEnding() {
        DefaultMachineMatchStrategy strategy = new DefaultMachineMatchStrategy();
        LhScheduleContext context = buildContext();
        enableSingleControlMachines(context);

        MachineScheduleDTO normalMachine = machine("K1401", dateTime(2026, 5, 9, 8, 0),
                "SPEC-A", "22.5", "MAT-NORMAL");
        MachineScheduleDTO singleControlMachine = machine("K1501L", dateTime(2026, 5, 9, 8, 0),
                "SPEC-A", "22.5", "MAT-SINGLE");
        singleControlMachine.setEnding(true);
        context.getMachineScheduleMap().put(normalMachine.getMachineCode(), normalMachine);
        context.getMachineScheduleMap().put(singleControlMachine.getMachineCode(), singleControlMachine);

        SkuScheduleDTO sku = sku("3302001575", "SPEC-A", "22.5");
        sku.setConstructionStage(ConstructionStageEnum.MASS_TRIAL.getCode());

        List<MachineScheduleDTO> candidates = strategy.matchMachines(context, sku);

        assertEquals(2, candidates.size(), "isTrial总标识不等同于试制施工阶段，应保留普通候选作为单控不足后的回落机台");
        assertEquals("K1501L", candidates.get(0).getMachineCode(),
                "试制量试 SKU 命中收尾单控机台时，应优先选择单控机台");
    }

    @Test
    void matchMachines_shouldPreferSingleControlCandidatesForMassTrialSkuWhenAvailable() {
        DefaultMachineMatchStrategy strategy = new DefaultMachineMatchStrategy();
        LhScheduleContext context = buildContext();
        enableSingleControlMachines(context);

        MachineScheduleDTO normalMachine = machine("K1111", dateTime(2026, 5, 9, 8, 0),
                "SPEC-A", "22.5", "MAT-NORMAL");
        MachineScheduleDTO singleControlMachine = machine("K1501L", dateTime(2026, 5, 9, 8, 10),
                "SPEC-A", "22.5", "MAT-SINGLE");
        context.getMachineScheduleMap().put(normalMachine.getMachineCode(), normalMachine);
        context.getMachineScheduleMap().put(singleControlMachine.getMachineCode(), singleControlMachine);

        SkuScheduleDTO sku = sku("3302002637", "SPEC-A", "19.5");
        sku.setConstructionStage(ConstructionStageEnum.MASS_TRIAL.getCode());

        List<MachineScheduleDTO> candidates = strategy.matchMachines(context, sku);

        assertEquals(2, candidates.size(), "有单控且有普通机台时，量试应保留普通候选作为单控不足后的回落机台");
        assertEquals("K1501L", candidates.get(0).getMachineCode(),
                "量试命中单控机台时，单控候选必须排在普通机台前");
    }

    @Test
    void matchMachines_shouldPreferSingleControlBeforeSameEmbryoInsideEndingWindow() {
        DefaultMachineMatchStrategy strategy = new DefaultMachineMatchStrategy();
        LhScheduleContext context = buildContext();
        enableSingleControlMachines(context);

        MachineScheduleDTO sameEmbryoMachine = machine("Z-SAME-EMBRYO", dateTime(2026, 5, 9, 8, 0),
                "SPEC-X", "22.5", "MAT-EMBRYO");
        MachineScheduleDTO singleControlMachine = machine("K1501L", dateTime(2026, 5, 9, 8, 10),
                "SPEC-X", "22.5", "MAT-SINGLE");
        context.getMachineScheduleMap().put(sameEmbryoMachine.getMachineCode(), sameEmbryoMachine);
        context.getMachineScheduleMap().put(singleControlMachine.getMachineCode(), singleControlMachine);
        material(context, "MAT-EMBRYO", "EMB-A", "胎胚-A");
        material(context, "MAT-SINGLE", "EMB-B", "胎胚-B");
        SkuScheduleDTO sku = sku("3302002637", "SPEC-A", "22.5");
        sku.setConstructionStage(ConstructionStageEnum.MASS_TRIAL.getCode());
        sku.setEmbryoCode("EMB-A");
        sku.setMainMaterialDesc("胎胚-A");

        List<MachineScheduleDTO> candidates = strategy.matchMachines(context, sku);

        assertEquals(2, candidates.size(), "20分钟窗口内应保留单控机台和同胎胚普通机台共同排序");
        assertEquals("K1501L", candidates.get(0).getMachineCode(),
                "单控拆分优先级必须高于同胎胚，量试SKU应先选单控机台");
    }

    @Test
    void matchMachines_shouldKeepOnlySingleControlCandidatesForTrialConstructionStage() {
        DefaultMachineMatchStrategy strategy = new DefaultMachineMatchStrategy();
        LhScheduleContext context = buildContext();
        enableSingleControlMachines(context);

        MachineScheduleDTO normalMachine = machine("K1111", dateTime(2026, 5, 9, 8, 0),
                "SPEC-A", "22.5", "MAT-NORMAL");
        MachineScheduleDTO singleControlMachine = machine("K1501R", dateTime(2026, 5, 9, 8, 10),
                "SPEC-A", "22.5", "MAT-SINGLE");
        context.getMachineScheduleMap().put(normalMachine.getMachineCode(), normalMachine);
        context.getMachineScheduleMap().put(singleControlMachine.getMachineCode(), singleControlMachine);

        SkuScheduleDTO sku = sku("3302001575", "SPEC-A", "19.5");
        sku.setConstructionStage(ConstructionStageEnum.TRIAL.getCode());

        List<MachineScheduleDTO> candidates = strategy.matchMachines(context, sku);

        assertEquals(1, candidates.size(), "试制施工阶段命中单控机台时，只能保留单控候选");
        assertEquals("K1501R", candidates.get(0).getMachineCode(),
                "试制施工阶段不应回落普通机台 K1111");
    }

    @Test
    void matchMachines_shouldTreatSplitMachineAsSingleControlWhenParamMissing() {
        DefaultMachineMatchStrategy strategy = new DefaultMachineMatchStrategy();
        LhScheduleContext context = buildContext();

        MachineScheduleDTO normalMachine = machine("K1111", dateTime(2026, 5, 9, 8, 0),
                "SPEC-A", "22.5", "MAT-NORMAL");
        MachineScheduleDTO singleControlMachine = machine("K1501R", dateTime(2026, 5, 9, 8, 10),
                "SPEC-A", "22.5", "MAT-SINGLE");
        context.getMachineScheduleMap().put(normalMachine.getMachineCode(), normalMachine);
        context.getMachineScheduleMap().put(singleControlMachine.getMachineCode(), singleControlMachine);

        SkuScheduleDTO sku = sku("3302001575", "SPEC-A", "19.5");
        sku.setConstructionStage(ConstructionStageEnum.TRIAL.getCode());

        List<MachineScheduleDTO> candidates = strategy.matchMachines(context, sku);

        assertEquals(1, candidates.size(), "缺少单控参数时，L/R 运行态机台仍应按单控机台处理");
        assertEquals("K1501R", candidates.get(0).getMachineCode(),
                "试制施工阶段命中 L/R 机台时，不应因缺少参数而回落普通机台 K1111");
    }

    @Test
    void matchMachines_shouldRejectTrialConstructionStageWhenNoSingleControlCandidates() {
        DefaultMachineMatchStrategy strategy = new DefaultMachineMatchStrategy();
        LhScheduleContext context = buildContext();
        enableSingleControlMachines(context);

        MachineScheduleDTO normalMachine = machine("K1111", dateTime(2026, 5, 9, 8, 0),
                "SPEC-A", "22.5", "MAT-NORMAL");
        context.getMachineScheduleMap().put(normalMachine.getMachineCode(), normalMachine);

        SkuScheduleDTO sku = sku("3302001575", "SPEC-A", "19.5");
        sku.setConstructionStage(ConstructionStageEnum.TRIAL.getCode());

        List<MachineScheduleDTO> candidates = strategy.matchMachines(context, sku);

        assertTrue(candidates.isEmpty(), "试制SKU无单控候选时不能回落普通机台");
    }

    @Test
    void matchMachines_shouldPreferSingleControlCandidatesForMassTrialWhenAvailable() {
        DefaultMachineMatchStrategy strategy = new DefaultMachineMatchStrategy();
        LhScheduleContext context = buildContext();
        enableSingleControlMachines(context);

        MachineScheduleDTO normalMachine = machine("K1111", dateTime(2026, 5, 9, 8, 0),
                "SPEC-A", "22.5", "MAT-NORMAL");
        MachineScheduleDTO singleControlMachine = machine("K1501R", dateTime(2026, 5, 9, 8, 10),
                "SPEC-A", "22.5", "MAT-SINGLE");
        context.getMachineScheduleMap().put(normalMachine.getMachineCode(), normalMachine);
        context.getMachineScheduleMap().put(singleControlMachine.getMachineCode(), singleControlMachine);

        SkuScheduleDTO sku = sku("3302002637", "SPEC-A", "19.5");
        sku.setConstructionStage("02");

        List<MachineScheduleDTO> candidates = strategy.matchMachines(context, sku);

        assertEquals(2, candidates.size(), "量试施工阶段在有单控机台时，应保留普通候选作为单控不足后的回落机台");
        assertEquals("K1501R", candidates.get(0).getMachineCode(),
                "量试施工阶段命中单控机台时，单控候选必须排在普通机台前");
    }

    @Test
    void matchMachines_shouldAllowMassTrialNormalMachineWhenOnlyNormalCandidate() {
        DefaultMachineMatchStrategy strategy = new DefaultMachineMatchStrategy();
        LhScheduleContext context = buildContext();
        enableSingleControlMachines(context);
        context.setPendingFormalNewSpecSkuCount(1);

        MachineScheduleDTO normalMachine = machine("K1111", dateTime(2026, 5, 9, 8, 0),
                "SPEC-A", "22.5", "MAT-NORMAL");
        context.getMachineScheduleMap().put(normalMachine.getMachineCode(), normalMachine);

        SkuScheduleDTO sku = sku("3302002637", "SPEC-A", "19.5");
        sku.setConstructionStage(ConstructionStageEnum.MASS_TRIAL.getCode());

        List<MachineScheduleDTO> candidates = strategy.matchMachines(context, sku);

        assertEquals(1, candidates.size(), "量试SKU无单控候选时，应继续使用普通机台，不因正规SKU待排让位");
        assertEquals("K1111", candidates.get(0).getMachineCode());
    }

    @Test
    void matchMachines_shouldAllowMassTrialNormalMachineWhenNoFormalSkuPending() {
        DefaultMachineMatchStrategy strategy = new DefaultMachineMatchStrategy();
        LhScheduleContext context = buildContext();
        enableSingleControlMachines(context);
        context.setPendingFormalNewSpecSkuCount(0);

        MachineScheduleDTO normalMachine = machine("K1111", dateTime(2026, 5, 9, 8, 0),
                "SPEC-A", "22.5", "MAT-NORMAL");
        context.getMachineScheduleMap().put(normalMachine.getMachineCode(), normalMachine);

        SkuScheduleDTO sku = sku("3302002637", "SPEC-A", "19.5");
        sku.setConstructionStage(ConstructionStageEnum.MASS_TRIAL.getCode());

        List<MachineScheduleDTO> candidates = strategy.matchMachines(context, sku);

        assertEquals(1, candidates.size(), "量试SKU无正规SKU待排时，可以使用普通机台");
        assertEquals("K1111", candidates.get(0).getMachineCode());
    }

    @Test
    void matchMachines_shouldAllowSmallBatchNormalMachineWhenOnlyNormalCandidate() {
        DefaultMachineMatchStrategy strategy = new DefaultMachineMatchStrategy();
        LhScheduleContext context = buildContext();
        enableSingleControlMachines(context);
        context.setPendingFormalNewSpecSkuCount(1);

        MachineScheduleDTO normalMachine = machine("K1111", dateTime(2026, 5, 9, 8, 0),
                "SPEC-A", "22.5", "MAT-NORMAL");
        context.getMachineScheduleMap().put(normalMachine.getMachineCode(), normalMachine);

        SkuScheduleDTO sku = sku("3302002638", "SPEC-A", "19.5");
        sku.setSmallBatchValidation(true);

        List<MachineScheduleDTO> candidates = strategy.matchMachines(context, sku);

        assertEquals(1, candidates.size(), "小批量SKU无单控候选时，应继续使用普通机台，不因正规SKU待排让位");
        assertEquals("K1111", candidates.get(0).getMachineCode());
    }

    @Test
    void matchMachines_shouldKeepMassTrialSingleControlCandidateWhenTrialPending() {
        DefaultMachineMatchStrategy strategy = new DefaultMachineMatchStrategy();
        LhScheduleContext context = buildContext();
        enableSingleControlMachines(context);
        context.setPendingTrialNewSpecSkuCount(1);

        MachineScheduleDTO singleControlMachine = machine("K1501R", dateTime(2026, 5, 9, 8, 0),
                "SPEC-A", "22.5", "MAT-SINGLE");
        context.getMachineScheduleMap().put(singleControlMachine.getMachineCode(), singleControlMachine);

        SkuScheduleDTO sku = sku("3302002637", "SPEC-A", "19.5");
        sku.setConstructionStage(ConstructionStageEnum.MASS_TRIAL.getCode());

        List<MachineScheduleDTO> candidates = strategy.matchMachines(context, sku);

        assertEquals(1, candidates.size(), "量试SKU全局排序在前时，不应因后续试制待排让出单控机台");
        assertEquals("K1501R", candidates.get(0).getMachineCode());
    }

    @Test
    void matchMachines_shouldKeepSmallBatchSingleControlCandidateWhenMassTrialPending() {
        DefaultMachineMatchStrategy strategy = new DefaultMachineMatchStrategy();
        LhScheduleContext context = buildContext();
        enableSingleControlMachines(context);
        context.setPendingMassTrialNewSpecSkuCount(1);

        MachineScheduleDTO singleControlMachine = machine("K1501R", dateTime(2026, 5, 9, 8, 0),
                "SPEC-A", "22.5", "MAT-SINGLE");
        context.getMachineScheduleMap().put(singleControlMachine.getMachineCode(), singleControlMachine);

        SkuScheduleDTO sku = sku("3302002638", "SPEC-A", "19.5");
        sku.setSmallBatchValidation(true);

        List<MachineScheduleDTO> candidates = strategy.matchMachines(context, sku);

        assertEquals(1, candidates.size(), "小批量SKU全局排序在前时，不应因后续量试待排让出单控机台");
        assertEquals("K1501R", candidates.get(0).getMachineCode());
    }

    @Test
    void matchMachines_shouldKeepSingleControlMachineForNormalSkuBehindNormalCandidate() {
        DefaultMachineMatchStrategy strategy = new DefaultMachineMatchStrategy();
        LhScheduleContext context = buildContext();
        enableSingleControlMachines(context);
        context.setPendingSmallBatchNewSpecSkuCount(1);

        MachineScheduleDTO singleControlMachine = machine("K1501L", dateTime(2026, 5, 9, 8, 0),
                "SPEC-A", "22.5", "MAT-SINGLE");
        MachineScheduleDTO normalMachine = machine("K1401", dateTime(2026, 5, 9, 8, 0),
                "SPEC-A", "22.5", "MAT-NORMAL");
        context.getMachineScheduleMap().put(singleControlMachine.getMachineCode(), singleControlMachine);
        context.getMachineScheduleMap().put(normalMachine.getMachineCode(), normalMachine);

        List<MachineScheduleDTO> candidates = strategy.matchMachines(context, sku("3302001418", "SPEC-A", "22.5"));

        assertEquals(2, candidates.size(), "正规SKU应保留单控候选作为普通机台不足时的回落");
        assertEquals("K1401", candidates.get(0).getMachineCode(),
                "正规SKU应优先选择非单控机台");
        assertEquals("K1501L", candidates.get(1).getMachineCode(),
                "单控机台应排在正规SKU普通候选之后");
    }

    @Test
    void matchMachines_shouldKeepSingleControlCandidatesForNormalSkuAsFallback() {
        DefaultMachineMatchStrategy strategy = new DefaultMachineMatchStrategy();
        LhScheduleContext context = buildContext();
        enableSingleControlMachines(context);
        context.setPendingSmallBatchNewSpecSkuCount(0);

        MachineScheduleDTO singleControlMachine = machine("K1501R", dateTime(2026, 5, 9, 8, 0),
                "SPEC-A", "22.5", "MAT-SINGLE");
        MachineScheduleDTO normalMachine = machine("K1111", dateTime(2026, 5, 9, 8, 0),
                "SPEC-A", "22.5", "MAT-NORMAL");
        context.getMachineScheduleMap().put(singleControlMachine.getMachineCode(), singleControlMachine);
        context.getMachineScheduleMap().put(normalMachine.getMachineCode(), normalMachine);

        List<MachineScheduleDTO> candidates = strategy.matchMachines(context, sku("3302001513", "SPEC-A", "22.5"));

        assertEquals(2, candidates.size(), "正规物料存在普通候选时，也应保留单控候选作为后续回落机台");
        assertEquals("K1111", candidates.get(0).getMachineCode());
        assertEquals("K1501R", candidates.get(1).getMachineCode(),
                "单控机台应作为正规 SKU 的回落候选，而不是在选机阶段提前剔除");
    }

    @Test
    void matchMachines_shouldFallbackToSingleControlForFormalSkuWhenOnlySingleControlCandidate() {
        DefaultMachineMatchStrategy strategy = new DefaultMachineMatchStrategy();
        LhScheduleContext context = buildContext();
        enableSingleControlMachines(context);
        context.setPendingSmallBatchNewSpecSkuCount(2);

        MachineScheduleDTO singleControlMachine = machine("K1501R", dateTime(2026, 5, 9, 8, 0),
                "SPEC-A", "22.5", "MAT-SINGLE");
        context.getMachineScheduleMap().put(singleControlMachine.getMachineCode(), singleControlMachine);

        List<MachineScheduleDTO> candidates = strategy.matchMachines(context, sku("3302001513", "SPEC-A", "22.5"));

        assertEquals(1, candidates.size(), "正规SKU没有非单控候选时，应允许回落单控机台");
        assertEquals("K1501R", candidates.get(0).getMachineCode());
    }

    @Test
    void matchMachines_shouldKeepSpecifiedSingleControlCandidatesButPreferNormalForFormalSku() {
        DefaultMachineMatchStrategy strategy = new DefaultMachineMatchStrategy();
        LhScheduleContext context = buildContext();
        enableSingleControlAndSpecifyMachineRule(context);

        MachineScheduleDTO singleControlMachine = machine("K1501R", dateTime(2026, 5, 9, 8, 0),
                "SPEC-A", "22.5", "MAT-SINGLE");
        MachineScheduleDTO normalMachine = machine("K1111", dateTime(2026, 5, 9, 8, 0),
                "SPEC-A", "22.5", "MAT-NORMAL");
        context.getMachineScheduleMap().put(singleControlMachine.getMachineCode(), singleControlMachine);
        context.getMachineScheduleMap().put(normalMachine.getMachineCode(), normalMachine);
        context.getSpecifyMachineMap().put("3302001513", Collections.singletonList(
                specifyMachine("3302001513", "K1501", JobTypeEnum.RESTRICTED.getCode())));

        List<MachineScheduleDTO> candidates = strategy.matchMachines(context, sku("3302001513", "SPEC-A", "22.5"));

        assertEquals(2, candidates.size(), "显式定点到单控机台时，应保留定点单控候选");
        assertEquals("K1111", candidates.get(0).getMachineCode(),
                "定点优先已不在新增选机排序调用链内，正规SKU应按单控拆分得分先选普通机台");
    }

    @Test
    void matchMachines_shouldNotTreatUnconfiguredSplitMachineAsSingleControl() {
        DefaultMachineMatchStrategy strategy = new DefaultMachineMatchStrategy();
        LhScheduleContext context = buildContext();
        enableSingleControlMachines(context);

        MachineScheduleDTO unconfiguredSplitMachine = machine("K1601R", dateTime(2026, 5, 9, 8, 0),
                "SPEC-A", "22.5", "MAT-SINGLE");
        MachineScheduleDTO normalMachine = machine("K1111", dateTime(2026, 5, 9, 8, 0),
                "SPEC-A", "22.5", "MAT-NORMAL");
        context.getMachineScheduleMap().put(unconfiguredSplitMachine.getMachineCode(), unconfiguredSplitMachine);
        context.getMachineScheduleMap().put(normalMachine.getMachineCode(), normalMachine);

        SkuScheduleDTO sku = sku("3302002637", "SPEC-A", "19.5");
        sku.setConstructionStage(ConstructionStageEnum.MASS_TRIAL.getCode());

        List<MachineScheduleDTO> candidates = strategy.matchMachines(context, sku);

        assertEquals(2, candidates.size(), "未配置的 K1601R 不应被当成单控独占候选");
        assertEquals("K1111", candidates.get(0).getMachineCode());
    }

    @Test
    void matchMachines_shouldFallbackToSingleControlCandidatesWhenNoNormalCandidates() {
        DefaultMachineMatchStrategy strategy = new DefaultMachineMatchStrategy();
        LhScheduleContext context = buildContext();
        enableSingleControlMachines(context);

        MachineScheduleDTO singleControlMachine = machine("K1501R", dateTime(2026, 5, 9, 8, 0),
                "SPEC-A", "22.5", "MAT-SINGLE");
        context.getMachineScheduleMap().put(singleControlMachine.getMachineCode(), singleControlMachine);

        List<MachineScheduleDTO> candidates = strategy.matchMachines(context, sku("3302001513", "SPEC-A", "22.5"));

        assertEquals(1, candidates.size(), "普通 SKU 无普通机台候选时，应回退保留单控拆分机台");
        assertEquals("K1501R", candidates.get(0).getMachineCode());
    }

    @Test
    void matchMachines_shouldFallbackToSingleControlForFormalSkuEvenWhenHigherTypesPending() {
        DefaultMachineMatchStrategy strategy = new DefaultMachineMatchStrategy();
        LhScheduleContext context = buildContext();
        enableSingleControlMachines(context);
        context.setPendingTrialNewSpecSkuCount(1);

        MachineScheduleDTO singleControlMachine = machine("K1501R", dateTime(2026, 5, 9, 8, 0),
                "SPEC-A", "22.5", "MAT-SINGLE");
        context.getMachineScheduleMap().put(singleControlMachine.getMachineCode(), singleControlMachine);

        List<MachineScheduleDTO> candidates = strategy.matchMachines(context, sku("3302001513", "SPEC-A", "22.5"));

        assertEquals(1, candidates.size(), "正规SKU仅无普通机台时，应允许回退保留单控机台");
        assertEquals("K1501R", candidates.get(0).getMachineCode());
    }

    @Test
    void matchMachines_usesMouldQtyInsteadOfRelationCount() {
        DefaultMachineMatchStrategy strategy = new DefaultMachineMatchStrategy();
        LhScheduleContext context = buildContext();

        MachineScheduleDTO machine = new MachineScheduleDTO();
        machine.setMachineCode("M1");
        machine.setStatus("1");
        machine.setMaxMoldNum(1);

        context.setMachineScheduleMap(new LinkedHashMap<>());
        context.getMachineScheduleMap().put(machine.getMachineCode(), machine);
        context.setMachineAssignmentMap(new LinkedHashMap<>());

        SkuScheduleDTO sku = new SkuScheduleDTO();
        sku.setMaterialCode("MAT-1");

        context.getSkuMouldRelMap().put("MAT-1", Arrays.asList(
                mouldRel("MOULD-A"),
                mouldRel("MOULD-B"),
                mouldRel("MOULD-C")
        ));

        List<MachineScheduleDTO> candidates = strategy.matchMachines(context, sku);

        assertEquals(1, candidates.size(), "应按SKU实际用模数判断，不应因模具关系条数多而误过滤机台");
        assertEquals("M1", candidates.get(0).getMachineCode());
    }

    @Test
    void matchMachines_shouldPreferEarlierEndingTimeBeforeSpecWhenBeyondTolerance() {
        DefaultMachineMatchStrategy strategy = new DefaultMachineMatchStrategy();
        LhScheduleContext context = buildContext();

        MachineScheduleDTO earlierMachine = machine("M-EARLY", dateTime(2026, 4, 21, 8, 0),
                "SPEC-X", "22.5", "MAT-EARLY");
        MachineScheduleDTO specMatchedButLateMachine = machine("M-LATE", dateTime(2026, 4, 21, 8, 30),
                "SPEC-A", "22.5", "MAT-LATE");
        context.getMachineScheduleMap().put(earlierMachine.getMachineCode(), earlierMachine);
        context.getMachineScheduleMap().put(specMatchedButLateMachine.getMachineCode(), specMatchedButLateMachine);

        SkuScheduleDTO sku = sku("MAT-1", "SPEC-A", "22.5");

        List<MachineScheduleDTO> candidates = strategy.matchMachines(context, sku);

        assertEquals(1, candidates.size(), "收尾时间超过20分钟窗口时，窗口外规格匹配机台不参与本轮排序");
        assertEquals("M-EARLY", candidates.get(0).getMachineCode(),
                "窗口外机台即使规格更匹配，也不能越过最早收尾窗口内的机台");
    }

    @Test
    void matchMachines_shouldFallbackToMachineCodeWhenProductionWindowPriorityRemoved() {
        DefaultMachineMatchStrategy strategy = new DefaultMachineMatchStrategy();
        LhScheduleContext context = buildContext();

        MachineScheduleDTO nextDayMachine = machine("A-NEXT-DAY", dateTime(2026, 4, 21, 20, 5),
                "SPEC-A", "22.5", "MAT-NEXT");
        MachineScheduleDTO sameDayMachine = machine("Z-SAME-DAY", dateTime(2026, 4, 21, 19, 50),
                "SPEC-A", "22.5", "MAT-SAME");
        context.getMachineScheduleMap().put(nextDayMachine.getMachineCode(), nextDayMachine);
        context.getMachineScheduleMap().put(sameDayMachine.getMachineCode(), sameDayMachine);

        List<MachineScheduleDTO> candidates = strategy.matchMachines(context, sku("MAT-1", "SPEC-A", "22.5"));

        assertEquals(2, candidates.size());
        assertEquals("A-NEXT-DAY", candidates.get(0).getMachineCode(),
                "最早可开产和连续班次已不在新增选机排序调用链内，同层候选应按机台编码兜底");
    }

    @Test
    void matchMachines_shouldFallbackToMachineCodeWhenOtherSkuOccupancyPriorityRemoved() {
        DefaultMachineMatchStrategy strategy = new DefaultMachineMatchStrategy();
        LhScheduleContext context = buildContext();

        MachineScheduleDTO occupiedMachine = machine("A-OCCUPIED", dateTime(2026, 4, 21, 8, 0),
                "SPEC-A", "22.5", "MAT-OCC");
        MachineScheduleDTO cleanMachine = machine("Z-CLEAN", dateTime(2026, 4, 21, 8, 0),
                "SPEC-A", "22.5", "MAT-CLEAN");
        context.getMachineScheduleMap().put(occupiedMachine.getMachineCode(), occupiedMachine);
        context.getMachineScheduleMap().put(cleanMachine.getMachineCode(), cleanMachine);
        context.getMachineAssignmentMap().put("A-OCCUPIED", Collections.singletonList(
                new com.zlt.aps.lh.api.domain.entity.LhScheduleResult()));

        List<MachineScheduleDTO> candidates = strategy.matchMachines(context, sku("MAT-1", "SPEC-A", "22.5"));

        assertEquals(2, candidates.size());
        assertEquals("A-OCCUPIED", candidates.get(0).getMachineCode(),
                "其他SKU占用已不在新增选机排序调用链内，同层候选应按机台编码兜底");
    }

    @Test
    void matchMachines_shouldFallbackToMachineCodeWhenOccupancyAndSpecialPriorityRemoved() {
        DefaultMachineMatchStrategy strategy = new DefaultMachineMatchStrategy();
        LhScheduleContext context = buildContext();

        MachineScheduleDTO occupiedTailNormalMachine = machine("A-NORMAL-TAIL", dateTime(2026, 4, 21, 8, 10),
                "SPEC-A", "22.5", "MAT-OCC");
        MachineScheduleDTO cleanEarlySpecialMachine = machine("Z-SPECIAL-EARLY", dateTime(2026, 4, 21, 8, 0),
                "SPEC-A", "22.5", "MAT-CLEAN");
        cleanEarlySpecialMachine.setSupport195WideBase("1");
        context.getMachineScheduleMap().put(occupiedTailNormalMachine.getMachineCode(), occupiedTailNormalMachine);
        context.getMachineScheduleMap().put(cleanEarlySpecialMachine.getMachineCode(), cleanEarlySpecialMachine);
        context.getMachineAssignmentMap().put("A-NORMAL-TAIL", Collections.singletonList(
                new com.zlt.aps.lh.api.domain.entity.LhScheduleResult()));

        List<MachineScheduleDTO> candidates = strategy.matchMachines(context, sku("MAT-1", "SPEC-A", "22.5"));

        assertEquals(2, candidates.size());
        assertEquals("A-NORMAL-TAIL", candidates.get(0).getMachineCode(),
                "占用、尾部产能和特殊机台后置已不在新增选机排序调用链内，同层候选应按机台编码兜底");
    }

    @Test
    void matchMachines_shouldPreferSpecMatchWhenEndingTimeWithinTolerance() {
        DefaultMachineMatchStrategy strategy = new DefaultMachineMatchStrategy();
        LhScheduleContext context = buildContext();

        MachineScheduleDTO unmatchedSpecMachine = machine("M-UNMATCHED", dateTime(2026, 4, 21, 8, 0),
                "SPEC-X", "22.5", "MAT-A");
        MachineScheduleDTO matchedSpecMachine = machine("M-MATCHED", dateTime(2026, 4, 21, 8, 10),
                "SPEC-A", "20.0", "MAT-B");
        context.getMachineScheduleMap().put(unmatchedSpecMachine.getMachineCode(), unmatchedSpecMachine);
        context.getMachineScheduleMap().put(matchedSpecMachine.getMachineCode(), matchedSpecMachine);

        SkuScheduleDTO sku = sku("MAT-1", "SPEC-A", "22.5");

        List<MachineScheduleDTO> candidates = strategy.matchMachines(context, sku);

        assertEquals(2, candidates.size());
        assertEquals("M-MATCHED", candidates.get(0).getMachineCode(),
                "收尾时间在阈值内时，应进入规格优先比较");
    }

    @Test
    void matchMachines_shouldFilterCandidatesByEarliestEndingTwentyMinuteWindow() {
        DefaultMachineMatchStrategy strategy = new DefaultMachineMatchStrategy();
        LhScheduleContext context = buildContext();

        MachineScheduleDTO earliestMachine = machine("M-EARLY", dateTime(2026, 4, 21, 8, 0),
                "SPEC-X", "20.0", "MAT-EARLY");
        MachineScheduleDTO betterButOutsideWindowMachine = machine("M-BETTER-LATE", dateTime(2026, 4, 21, 8, 30),
                "SPEC-A", "22.5", "MAT-LATE");
        context.getMachineScheduleMap().put(earliestMachine.getMachineCode(), earliestMachine);
        context.getMachineScheduleMap().put(betterButOutsideWindowMachine.getMachineCode(), betterButOutsideWindowMachine);

        List<MachineScheduleDTO> candidates = strategy.matchMachines(context, sku("MAT-1", "SPEC-A", "22.5"));

        assertEquals(1, candidates.size(), "新增选机必须先锁定最早收尾后20分钟窗口，窗口外机台不再参与本轮排序");
        assertEquals("M-EARLY", candidates.get(0).getMachineCode(),
                "窗口外机台即使规格和英寸更匹配，也不能越过最早收尾窗口内的机台");
    }

    @Test
    void matchMachines_shouldPreferSameEmbryoBeforeSameSpecInsideEndingWindow() {
        DefaultMachineMatchStrategy strategy = new DefaultMachineMatchStrategy();
        LhScheduleContext context = buildContext();

        MachineScheduleDTO sameEmbryoMachine = machine("M-SAME-EMBRYO", dateTime(2026, 4, 21, 8, 0),
                "SPEC-X", "20.0", "MAT-EMBRYO");
        MachineScheduleDTO sameSpecMachine = machine("M-SAME-SPEC", dateTime(2026, 4, 21, 8, 10),
                "SPEC-A", "20.0", "MAT-SPEC");
        context.getMachineScheduleMap().put(sameSpecMachine.getMachineCode(), sameSpecMachine);
        context.getMachineScheduleMap().put(sameEmbryoMachine.getMachineCode(), sameEmbryoMachine);
        material(context, "MAT-EMBRYO", "EMB-A", "胎胚-A");
        material(context, "MAT-SPEC", "EMB-B", "胎胚-B");
        SkuScheduleDTO sku = sku("MAT-1", "SPEC-A", "22.5");
        sku.setEmbryoCode("EMB-A");
        sku.setMainMaterialDesc("胎胚-A");

        List<MachineScheduleDTO> candidates = strategy.matchMachines(context, sku);

        assertEquals(2, candidates.size());
        assertEquals("M-SAME-EMBRYO", candidates.get(0).getMachineCode(),
                "20分钟窗口内必须先比较同胎胚，同胎胚优先级高于同规格");
    }

    @Test
    void matchMachines_shouldPreferSameShellBeforeSameSpecInsideEndingWindow() {
        DefaultMachineMatchStrategy strategy = new DefaultMachineMatchStrategy();
        LhScheduleContext context = buildContext();

        MachineScheduleDTO sameShellMachine = machine("M-SAME-SHELL", dateTime(2026, 4, 21, 8, 0),
                "SPEC-X", "22.5", "MAT-SHELL");
        sameShellMachine.setShellStandard("SHELL-A");
        MachineScheduleDTO sameSpecMachine = machine("M-SAME-SPEC", dateTime(2026, 4, 21, 8, 10),
                "SPEC-A", "22.5", "MAT-SPEC");
        sameSpecMachine.setShellStandard("SHELL-B");
        context.getMachineScheduleMap().put(sameSpecMachine.getMachineCode(), sameSpecMachine);
        context.getMachineScheduleMap().put(sameShellMachine.getMachineCode(), sameShellMachine);
        context.getSkuMouldRelMap().put("MAT-1", Collections.singletonList(mouldRel("MOULD-A")));
        context.getModelInfoMap().put("MOULD-A", modelInfo("MOULD-A", "SHELL-A"));

        List<MachineScheduleDTO> candidates = strategy.matchMachines(context, sku("MAT-1", "SPEC-A", "22.5"));

        assertEquals(2, candidates.size());
        assertEquals("M-SAME-SHELL", candidates.get(0).getMachineCode(),
                "20分钟窗口内同胎胚打平后，同模壳优先级必须高于同规格");
    }

    @Test
    void matchMachines_shouldPreferSameShellBeforeCapsuleInsideEndingWindow() {
        DefaultMachineMatchStrategy strategy = new DefaultMachineMatchStrategy();
        LhScheduleContext context = buildContext();
        context.setCapsuleSpecPeerMap(new HashMap<String, String>() {{
            put("SPEC-A", "GROUP-1");
            put("SPEC-B", "GROUP-1");
        }});

        MachineScheduleDTO sameShellMachine = machine("M-SAME-SHELL", dateTime(2026, 4, 21, 8, 0),
                "SPEC-X", "20.0", "MAT-SHELL");
        sameShellMachine.setShellStandard("SHELL-A");
        MachineScheduleDTO capsuleMachine = machine("M-CAPSULE", dateTime(2026, 4, 21, 8, 10),
                "SPEC-B", "20.0", "MAT-CAPSULE");
        capsuleMachine.setShellStandard("SHELL-B");
        context.getMachineScheduleMap().put(capsuleMachine.getMachineCode(), capsuleMachine);
        context.getMachineScheduleMap().put(sameShellMachine.getMachineCode(), sameShellMachine);
        context.getSkuMouldRelMap().put("MAT-1", Collections.singletonList(mouldRel("MOULD-A")));
        context.getModelInfoMap().put("MOULD-A", modelInfo("MOULD-A", "SHELL-A"));

        List<MachineScheduleDTO> candidates = strategy.matchMachines(context, sku("MAT-1", "SPEC-A", "22.5"));

        assertEquals(2, candidates.size(), "模壳不匹配不应在候选初筛阶段直接剔除，应作为窗口内排序层级处理");
        assertEquals("M-SAME-SHELL", candidates.get(0).getMachineCode(),
                "20分钟窗口内必须先比较同模壳，同模壳优先级高于胶囊共用性");
    }

    @Test
    void matchMachines_shouldTreatDeliveryMouldAsShellNeutral() {
        DefaultMachineMatchStrategy strategy = new DefaultMachineMatchStrategy();
        LhScheduleContext context = buildContext();

        MachineScheduleDTO machine = machine("M-DELIVERY", dateTime(2026, 4, 21, 8, 0),
                "SPEC-A", "22.5", "MAT-A");
        machine.setShellStandard("SHELL-A");
        context.getMachineScheduleMap().put(machine.getMachineCode(), machine);
        context.getSkuMouldRelMap().put("MAT-DELIVERY", Collections.singletonList(
                deliveryMouldRel("MOULD-DELIVERY", dateTime(2026, 4, 21, 0, 0))));

        List<MachineScheduleDTO> candidates = strategy.matchMachines(context, sku("MAT-DELIVERY", "SPEC-A", "22.5"));

        assertEquals(1, candidates.size(), "模具到货关系不参与模壳判断，不能因缺少台账模壳而过滤或降级候选机台");
        assertEquals("M-DELIVERY", candidates.get(0).getMachineCode());
    }

    @Test
    void matchMachines_shouldTreatBlankMachineShellAsMatchAll() {
        DefaultMachineMatchStrategy strategy = new DefaultMachineMatchStrategy();
        LhScheduleContext context = buildContext();

        MachineScheduleDTO blankShellMachine = machine("M-BLANK-SHELL", dateTime(2026, 4, 21, 8, 0),
                "SPEC-A", "22.5", "MAT-A");
        blankShellMachine.setShellStandard(" ");
        context.getMachineScheduleMap().put(blankShellMachine.getMachineCode(), blankShellMachine);
        context.getSkuMouldRelMap().put("MAT-1", Collections.singletonList(mouldRel("MOULD-A")));
        context.getModelInfoMap().put("MOULD-A", modelInfo("MOULD-A", "SHELL-A"));

        List<MachineScheduleDTO> candidates = strategy.matchMachines(context, sku("MAT-1", "SPEC-A", "22.5"));

        assertEquals(1, candidates.size(), "机台模套型号为空时表示模壳不限，应默认适配所有SKU模壳");
        assertEquals("M-BLANK-SHELL", candidates.get(0).getMachineCode());
    }

    @Test
    void matchMachines_shouldTraceEndingWindowAndNewHitLevels() {
        DefaultMachineMatchStrategy strategy = new DefaultMachineMatchStrategy();
        LhScheduleContext context = buildTraceContext();

        MachineScheduleDTO sameEmbryoMachine = machine("M-TRACE-EMBRYO", dateTime(2026, 4, 21, 8, 0),
                "SPEC-X", "20.0", "MAT-EMBRYO");
        sameEmbryoMachine.setMachineName("同胎胚机台");
        MachineScheduleDTO sameSpecMachine = machine("M-TRACE-SPEC", dateTime(2026, 4, 21, 8, 10),
                "SPEC-A", "20.0", "MAT-SPEC");
        sameSpecMachine.setMachineName("同规格机台");
        context.getMachineScheduleMap().put(sameSpecMachine.getMachineCode(), sameSpecMachine);
        context.getMachineScheduleMap().put(sameEmbryoMachine.getMachineCode(), sameEmbryoMachine);
        material(context, "MAT-EMBRYO", "EMB-A", "胎胚-A");
        material(context, "MAT-SPEC", "EMB-B", "胎胚-B");
        SkuScheduleDTO sku = sku("MAT-TRACE", "SPEC-A", "22.5");
        sku.setEmbryoCode("EMB-A");
        sku.setMainMaterialDesc("胎胚-A");

        List<MachineScheduleDTO> candidates = strategy.matchMachines(context, sku);

        assertEquals(2, candidates.size());
        assertEquals(1, context.getScheduleLogList().size());
        String logDetail = context.getScheduleLogList().get(0).getLogDetail();
        assertTrue(logDetail.contains("收尾窗口起点"), "排序日志必须输出本轮选机收尾窗口起点");
        assertTrue(logDetail.contains("收尾窗口截止"), "排序日志必须输出本轮选机收尾窗口截止");
        assertTrue(logDetail.contains("入收尾窗口=1"), "候选明细必须输出是否进入收尾窗口");
        assertTrue(logDetail.contains("L1_单控拆分"), "排序日志必须暴露单控拆分层级");
        assertTrue(logDetail.contains("L2_同胎胚"), "排序日志必须暴露同胎胚层级");
        assertTrue(logDetail.contains("L3_同模壳"), "排序日志必须暴露同模壳层级");
        assertTrue(logDetail.contains("L4_同规格"), "排序日志必须暴露同规格层级");
        assertTrue(logDetail.contains("L8_机台编码"), "排序日志必须暴露机台编码兜底层级");
        assertTrue(logDetail.indexOf("L3_同模壳") < logDetail.indexOf("L4_同规格"),
                "排序日志中同模壳层级必须展示在同规格之前");
        assertTrue(logDetail.contains("同胎胚=1"), "候选明细必须输出同胎胚标识");
        assertTrue(logDetail.contains("同模壳"), "候选明细必须输出同模壳标识");
    }

    @Test
    void matchMachines_shouldIgnorePreWindowEndingGapWhenBothMachinesAreAlreadyIdle() {
        DefaultMachineMatchStrategy strategy = new DefaultMachineMatchStrategy();
        LhScheduleContext context = buildContext();

        MachineScheduleDTO earlierIdleMachine = machine("M-EARLIER-IDLE", dateTime(2026, 4, 20, 8, 0),
                "SPEC-X", "22.5", "MAT-A");
        MachineScheduleDTO specMatchedMachine = machine("M-SPEC-MATCH", dateTime(2026, 4, 20, 20, 0),
                "SPEC-A", "22.5", "MAT-B");
        context.getMachineScheduleMap().put(earlierIdleMachine.getMachineCode(), earlierIdleMachine);
        context.getMachineScheduleMap().put(specMatchedMachine.getMachineCode(), specMatchedMachine);

        List<MachineScheduleDTO> candidates = strategy.matchMachines(context, sku("MAT-1", "SPEC-A", "22.5"));

        assertEquals(2, candidates.size());
        assertEquals("M-SPEC-MATCH", candidates.get(0).getMachineCode(),
                "两台机台都在窗口首班前已空机时，不应再用窗口外更早收尾时间压过后续规格优先级");
    }

    @Test
    void matchMachines_shouldPreferCapsuleAffinityBeforeEmbryoShareCount() {
        DefaultMachineMatchStrategy strategy = new DefaultMachineMatchStrategy();
        LhScheduleContext context = buildContext();
        context.setCapsuleSpecPeerMap(new HashMap<String, String>() {{
            put("SPEC-A", "SPEC-A,SPEC-B");
            put("SPEC-B", "SPEC-A,SPEC-B");
        }});

        MachineScheduleDTO capsuleMatchedMachine = machine("M-CAPSULE", dateTime(2026, 4, 21, 8, 0),
                "SPEC-B", "20.0", "MAT-CAPSULE");
        MachineScheduleDTO embryoMoreMachine = machine("M-EMBRYO", dateTime(2026, 4, 21, 8, 0),
                "SPEC-C", "20.0", "MAT-EMBRYO");
        context.getMachineScheduleMap().put(capsuleMatchedMachine.getMachineCode(), capsuleMatchedMachine);
        context.getMachineScheduleMap().put(embryoMoreMachine.getMachineCode(), embryoMoreMachine);

        material(context, "MAT-CAPSULE", "胎胚-A");
        material(context, "MAT-EMBRYO", "胎胚-B");
        context.setEmbryoDescMaterialCountMap(new HashMap<String, Integer>() {{
            put("胎胚-A", 3);
            put("胎胚-B", 9);
        }});

        SkuScheduleDTO sku = sku("MAT-1", "SPEC-A", "22.5");

        List<MachineScheduleDTO> candidates = strategy.matchMachines(context, sku);

        assertEquals(2, candidates.size());
        assertEquals("M-CAPSULE", candidates.get(0).getMachineCode(),
                "前序层级完全相同且仅胶囊共用性不同，应先按胶囊共用性排序，再看胎胚共用数量");
    }

    @Test
    void matchMachines_shouldPreferHigherEmbryoShareCountWhenAllPreviousRulesTie() {
        DefaultMachineMatchStrategy strategy = new DefaultMachineMatchStrategy();
        LhScheduleContext context = buildContext();

        MachineScheduleDTO smallerEmbryoShareMachine = machine("M-SMALL", dateTime(2026, 4, 21, 8, 0),
                "SPEC-X", "20.0", "MAT-SMALL");
        MachineScheduleDTO biggerEmbryoShareMachine = machine("M-BIG", dateTime(2026, 4, 21, 8, 0),
                "SPEC-X", "20.0", "MAT-BIG");
        context.getMachineScheduleMap().put(smallerEmbryoShareMachine.getMachineCode(), smallerEmbryoShareMachine);
        context.getMachineScheduleMap().put(biggerEmbryoShareMachine.getMachineCode(), biggerEmbryoShareMachine);

        material(context, "MAT-SMALL", "胎胚-A");
        material(context, "MAT-BIG", "胎胚-B");
        context.setEmbryoDescMaterialCountMap(new HashMap<String, Integer>() {{
            put("胎胚-A", 2);
            put("胎胚-B", 8);
        }});

        SkuScheduleDTO sku = sku("MAT-1", "SPEC-A", "22.5");

        List<MachineScheduleDTO> candidates = strategy.matchMachines(context, sku);

        assertEquals(2, candidates.size());
        assertEquals("M-BIG", candidates.get(0).getMachineCode(),
                "前序规则都相同的情况下，应优先胎胚共用数量更多的机台");
    }

    @Test
    void matchMachines_shouldFallbackToProSizeGroupWhenSpecificationsMissing() {
        DefaultMachineMatchStrategy strategy = new DefaultMachineMatchStrategy();
        LhScheduleContext context = buildContext();
        context.setCapsuleProSizePeerMap(new HashMap<String, String>() {{
            put("22.5", "22.5,23.5");
            put("23.5", "22.5,23.5");
        }});

        MachineScheduleDTO proSizeMatchedMachine = machine("M-PROSIZE", dateTime(2026, 4, 21, 8, 0),
                null, "23.5", "MAT-PROSIZE");
        MachineScheduleDTO noAffinityMachine = machine("M-NONE", dateTime(2026, 4, 21, 8, 0),
                null, "24.5", "MAT-NONE");
        context.getMachineScheduleMap().put(proSizeMatchedMachine.getMachineCode(), proSizeMatchedMachine);
        context.getMachineScheduleMap().put(noAffinityMachine.getMachineCode(), noAffinityMachine);

        material(context, "MAT-PROSIZE", "胎胚-A");
        material(context, "MAT-NONE", "胎胚-B");
        context.setEmbryoDescMaterialCountMap(new HashMap<String, Integer>() {{
            put("胎胚-A", 1);
            put("胎胚-B", 1);
        }});

        SkuScheduleDTO sku = sku("MAT-1", "SPEC-A", "22.5");

        List<MachineScheduleDTO> candidates = strategy.matchMachines(context, sku);

        assertEquals(2, candidates.size());
        assertEquals("M-PROSIZE", candidates.get(0).getMachineCode(),
                "规格组缺失时，应回退按英寸组判断胶囊共用性");
    }

    @Test
    void matchMachines_shouldWritePriorityTraceLogWhenEnabled() {
        DefaultMachineMatchStrategy strategy = new DefaultMachineMatchStrategy();
        LhScheduleContext context = buildTraceContext();

        MachineScheduleDTO enabledMachine = machine("M-TRACE-1", dateTime(2026, 4, 21, 8, 0),
                "SPEC-A", "22.5", "MAT-A");
        enabledMachine.setMachineName("优先机台");
        MachineScheduleDTO fallbackMachine = machine("M-TRACE-2", dateTime(2026, 4, 21, 8, 15),
                "SPEC-X", "22.5", "MAT-B");
        fallbackMachine.setMachineName("兜底机台");
        MachineScheduleDTO disabledMachine = machine("M-DISABLED", dateTime(2026, 4, 21, 7, 0),
                "SPEC-A", "22.5", "MAT-C");
        disabledMachine.setStatus("0");
        context.getMachineScheduleMap().put(enabledMachine.getMachineCode(), enabledMachine);
        context.getMachineScheduleMap().put(fallbackMachine.getMachineCode(), fallbackMachine);
        context.getMachineScheduleMap().put(disabledMachine.getMachineCode(), disabledMachine);

        material(context, "MAT-A", "胎胚-A");
        material(context, "MAT-B", "胎胚-B");
        context.setEmbryoDescMaterialCountMap(Collections.singletonMap("胎胚-A", 2));

        SkuScheduleDTO sku = sku("MAT-TRACE", "SPEC-A", "22.5");

        List<MachineScheduleDTO> candidates = strategy.matchMachines(context, sku);

        assertEquals(2, candidates.size());
        assertEquals(1, context.getScheduleLogList().size());
        LhScheduleProcessLog processLog = context.getScheduleLogList().get(0);
        assertEquals("机台排序优先级汇总【新增排产选机台】", processLog.getTitle());
        assertTrue(processLog.getLogDetail().contains("候选机台总数"));
        assertTrue(processLog.getLogDetail().contains("M-TRACE-1"));
        assertTrue(processLog.getLogDetail().contains("M-TRACE-2"));
        assertTrue(processLog.getLogDetail().contains("M-DISABLED"));
        assertTrue(processLog.getLogDetail().contains("TOP2"));
    }

    @Test
    void matchMachines_shouldExcludeMachineWhenPlanStopExceedsTimeoutHours() {
        DefaultMachineMatchStrategy strategy = new DefaultMachineMatchStrategy();
        LhScheduleContext context = buildContext();
        context.setScheduleConfig(new LhScheduleConfig(Collections.singletonMap(
                LhScheduleParamConstant.MACHINE_STOP_TIMEOUT_HOURS, "24")));

        MachineScheduleDTO timeoutMachine = machine("M-STOP", dateTime(2026, 4, 21, 8, 0),
                "SPEC-A", "22.5", "MAT-A");
        MachineScheduleDTO availableMachine = machine("M-OK", dateTime(2026, 4, 21, 9, 0),
                "SPEC-A", "22.5", "MAT-B");
        context.getMachineScheduleMap().put(timeoutMachine.getMachineCode(), timeoutMachine);
        context.getMachineScheduleMap().put(availableMachine.getMachineCode(), availableMachine);
        context.getDevicePlanShutList().add(devicePlanShut("M-STOP",
                dateTime(2026, 4, 21, 8, 0), dateTime(2026, 4, 22, 10, 0)));

        SkuScheduleDTO sku = sku("MAT-1", "SPEC-A", "22.5");

        List<MachineScheduleDTO> candidates = strategy.matchMachines(context, sku);

        assertEquals(1, candidates.size(), "停机超过阈值的机台应被排除，新增规格继续选择其他可用机台");
        assertEquals("M-OK", candidates.get(0).getMachineCode());
    }

    @Test
    void matchMachines_shouldKeepRecoveredMachineWhenLongStopEndedBeforeReferenceTime() {
        DefaultMachineMatchStrategy strategy = new DefaultMachineMatchStrategy();
        LhScheduleContext context = buildContext();
        context.setScheduleConfig(new LhScheduleConfig(Collections.singletonMap(
                LhScheduleParamConstant.MACHINE_STOP_TIMEOUT_HOURS, "24")));

        MachineScheduleDTO recoveredMachine = machine("M-RECOVERED", dateTime(2026, 4, 22, 12, 0),
                "SPEC-A", "22.5", "MAT-A");
        MachineScheduleDTO availableMachine = machine("M-OK", dateTime(2026, 4, 22, 12, 10),
                "SPEC-A", "22.5", "MAT-B");
        context.getMachineScheduleMap().put(recoveredMachine.getMachineCode(), recoveredMachine);
        context.getMachineScheduleMap().put(availableMachine.getMachineCode(), availableMachine);
        context.getDevicePlanShutList().add(devicePlanShut("M-RECOVERED",
                dateTime(2026, 4, 21, 8, 0), dateTime(2026, 4, 22, 10, 0)));

        List<MachineScheduleDTO> candidates = strategy.matchMachines(context, sku("MAT-1", "SPEC-A", "22.5"));

        assertEquals(2, candidates.size(), "长停机已在待排前恢复时，不应继续排除该机台");
        assertTrue(candidates.stream().anyMatch(machine -> "M-RECOVERED".equals(machine.getMachineCode())));
    }

    @Test
    void matchMachines_shouldKeepRecoveredMachineWhenLongStopEndsBeforeWindowStart() {
        DefaultMachineMatchStrategy strategy = new DefaultMachineMatchStrategy();
        LhScheduleContext context = buildContext();
        context.setScheduleConfig(new LhScheduleConfig(Collections.singletonMap(
                LhScheduleParamConstant.MACHINE_STOP_TIMEOUT_HOURS, "24")));

        MachineScheduleDTO recoveredBeforeWindowMachine = machine("M-WINDOW-RECOVERED", dateTime(2026, 4, 20, 14, 0),
                "SPEC-A", "22.5", "MAT-A");
        MachineScheduleDTO availableMachine = machine("M-OK", dateTime(2026, 4, 21, 6, 10),
                "SPEC-A", "22.5", "MAT-B");
        context.getMachineScheduleMap().put(recoveredBeforeWindowMachine.getMachineCode(), recoveredBeforeWindowMachine);
        context.getMachineScheduleMap().put(availableMachine.getMachineCode(), availableMachine);
        context.getDevicePlanShutList().add(devicePlanShut("M-WINDOW-RECOVERED",
                dateTime(2026, 4, 20, 1, 0), dateTime(2026, 4, 21, 3, 0)));

        List<MachineScheduleDTO> candidates = strategy.matchMachines(context, sku("MAT-1", "SPEC-A", "22.5"));

        assertEquals(2, candidates.size(), "长停机已在窗口首班前恢复时，不应继续按窗口外旧参考时间排除机台");
        assertTrue(candidates.stream().anyMatch(machine -> "M-WINDOW-RECOVERED".equals(machine.getMachineCode())));
    }

    @Test
    void matchMachines_shouldKeepStopMachineWhenNoAlternativeExists() {
        DefaultMachineMatchStrategy strategy = new DefaultMachineMatchStrategy();
        LhScheduleContext context = buildContext();
        context.setScheduleConfig(new LhScheduleConfig(Collections.singletonMap(
                LhScheduleParamConstant.MACHINE_STOP_TIMEOUT_HOURS, "24")));

        MachineScheduleDTO onlyMachine = machine("M-ONLY", dateTime(2026, 4, 21, 8, 0),
                "SPEC-A", "22.5", "MAT-A");
        context.getMachineScheduleMap().put(onlyMachine.getMachineCode(), onlyMachine);
        context.getDevicePlanShutList().add(devicePlanShut("M-ONLY",
                dateTime(2026, 4, 21, 7, 0), dateTime(2026, 4, 22, 10, 0)));

        List<MachineScheduleDTO> candidates = strategy.matchMachines(context, sku("MAT-1", "SPEC-A", "22.5"));

        assertEquals(1, candidates.size(), "没有其他可用机台时，不应把唯一候选机台直接排除");
        assertEquals("M-ONLY", candidates.get(0).getMachineCode());
    }

    @Test
    void matchMachines_shouldAllowNormalSkuToUseSpecialMachineWithoutForcedReservation() {
        DefaultMachineMatchStrategy strategy = new DefaultMachineMatchStrategy();
        LhScheduleContext context = buildContext();

        MachineScheduleDTO specialSupportMachine = machine("M-SPECIAL", dateTime(2026, 4, 21, 8, 0),
                "SPEC-A", "22.5", "MAT-SPECIAL");
        specialSupportMachine.setSupport195WideBase("1");
        context.getMachineScheduleMap().put(specialSupportMachine.getMachineCode(), specialSupportMachine);

        List<MachineScheduleDTO> candidates = strategy.matchMachines(context, sku("MAT-1", "SPEC-A", "22.5"));

        assertEquals(1, candidates.size(), "普通SKU没有普通机台可选时，应允许直接使用特殊机台");
        assertEquals("M-SPECIAL", candidates.get(0).getMachineCode());
    }

    @Test
    void matchMachines_shouldFallbackToMachineCodeWhenNormalMachinePriorityRemoved() {
        DefaultMachineMatchStrategy strategy = new DefaultMachineMatchStrategy();
        LhScheduleContext context = buildContext();

        MachineScheduleDTO specialSupportMachine = machine("A-SPECIAL", dateTime(2026, 4, 21, 8, 0),
                "SPEC-A", "22.5", "MAT-SPECIAL");
        specialSupportMachine.setSupport195WideBase("1");
        MachineScheduleDTO normalMachine = machine("Z-NORMAL", dateTime(2026, 4, 21, 8, 10),
                "SPEC-A", "22.5", "MAT-NORMAL");
        context.getMachineScheduleMap().put(specialSupportMachine.getMachineCode(), specialSupportMachine);
        context.getMachineScheduleMap().put(normalMachine.getMachineCode(), normalMachine);

        List<MachineScheduleDTO> candidates = strategy.matchMachines(context, sku("MAT-1", "SPEC-A", "22.5"));

        assertEquals(2, candidates.size());
        assertEquals("A-SPECIAL", candidates.get(0).getMachineCode(),
                "普通机台优先已不在新增选机排序调用链内，同层候选应按机台编码兜底");
    }

    @Test
    void matchMachines_shouldFallbackToMachineCodeWhenSpecialSupportCountPriorityRemoved() {
        DefaultMachineMatchStrategy strategy = new DefaultMachineMatchStrategy();
        LhScheduleContext context = buildContext();

        MachineScheduleDTO supportTwoCapabilitiesMachine = machine("A-SPECIAL-2", dateTime(2026, 4, 21, 8, 0),
                "SPEC-A", "22.5", "MAT-SPECIAL-2");
        supportTwoCapabilitiesMachine.setSupport195WideBase("1");
        supportTwoCapabilitiesMachine.setSupportChipTire("1");
        MachineScheduleDTO supportOneCapabilityMachine = machine("Z-SPECIAL-1", dateTime(2026, 4, 21, 8, 0),
                "SPEC-A", "22.5", "MAT-SPECIAL-1");
        supportOneCapabilityMachine.setSupport195WideBase("1");
        context.getMachineScheduleMap().put(supportTwoCapabilitiesMachine.getMachineCode(), supportTwoCapabilitiesMachine);
        context.getMachineScheduleMap().put(supportOneCapabilityMachine.getMachineCode(), supportOneCapabilityMachine);

        List<MachineScheduleDTO> candidates = strategy.matchMachines(context, sku("MAT-1", "SPEC-A", "22.5"));

        assertEquals(2, candidates.size());
        assertEquals("A-SPECIAL-2", candidates.get(0).getMachineCode(),
                "特殊支持能力数量已不在新增选机排序调用链内，同层候选应按机台编码兜底");
    }

    @Test
    void matchMachines_shouldTraceMachineTypeDiagnosticAndSpecialMachineFallbackReason() {
        DefaultMachineMatchStrategy strategy = new DefaultMachineMatchStrategy();
        LhScheduleContext context = buildTraceContext();

        MachineScheduleDTO specialSupportMachine = machine("M-SPECIAL", dateTime(2026, 4, 21, 8, 0),
                "SPEC-A", "22.5", "MAT-SPECIAL");
        specialSupportMachine.setMachineName("特殊机台");
        specialSupportMachine.setSupport195WideBase("1");
        context.getMachineScheduleMap().put(specialSupportMachine.getMachineCode(), specialSupportMachine);

        List<MachineScheduleDTO> candidates = strategy.matchMachines(context, sku("MAT-1", "SPEC-A", "22.5"));

        assertEquals(1, candidates.size());
        assertEquals(1, context.getScheduleLogList().size());
        LhScheduleProcessLog processLog = context.getScheduleLogList().get(0);
        assertTrue(processLog.getLogDetail().contains("机台类型=特殊机台"));
        assertTrue(processLog.getLogDetail().contains("普通机台=0"));
        assertTrue(processLog.getLogDetail().contains("特殊支持机台=1"));
        assertTrue(processLog.getLogDetail().contains("普通SKU允许使用特殊机台"));
        assertTrue(processLog.getLogDetail().contains("不作为新增选机排序层级"));
        assertTrue(processLog.getLogDetail().contains("特殊支持能力数量"));
    }

    @Test
    void matchMachines_shouldNotTreatLegacyTrialFlagAsMassTrialWhenConstructionStageIsFormal() {
        DefaultMachineMatchStrategy strategy = new DefaultMachineMatchStrategy();
        LhScheduleContext context = buildContext();
        enableSingleControlMachines(context);

        MachineScheduleDTO singleControlMachine = machine("K1501L", dateTime(2026, 4, 21, 8, 0),
                "SPEC-A", "22.5", "MAT-SINGLE");
        MachineScheduleDTO normalMachine = machine("K1111", dateTime(2026, 4, 21, 8, 0),
                "SPEC-A", "22.5", "MAT-NORMAL");
        context.getMachineScheduleMap().put(singleControlMachine.getMachineCode(), singleControlMachine);
        context.getMachineScheduleMap().put(normalMachine.getMachineCode(), normalMachine);

        SkuScheduleDTO formalSku = sku("MAT-LEGACY", "SPEC-A", "22.5");
        formalSku.setConstructionStage(ConstructionStageEnum.FORMAL.getCode());
        formalSku.setTrial(true);

        List<MachineScheduleDTO> candidates = strategy.matchMachines(context, formalSku);

        assertTrue(candidates.size() >= 1);
        assertEquals("K1111", candidates.get(0).getMachineCode(),
                "施工阶段为正规时，即使残留isTrial=true，也不应被当成量试SKU而改成单控优先");
    }

    @Test
    void matchMachines_shouldFilterSpecialMaterialByMachineSupportCategory() {
        DefaultMachineMatchStrategy strategy = new DefaultMachineMatchStrategy();
        LhScheduleContext context = buildContext();
        context.getSpecialMaterialCategoryByMaterialCode().put("MAT-195", categorySet("01"));
        context.getSpecialMaterialCategoryByMaterialCode().put("MAT-225", categorySet("02"));
        context.getSpecialMaterialCategoryByMaterialCode().put("MAT-CHIP", categorySet("03"));

        MachineScheduleDTO normalMachine = machine("M-NORMAL", dateTime(2026, 4, 21, 8, 0),
                "SPEC-A", "22.5", "MAT-NORMAL");
        MachineScheduleDTO support195Machine = machine("M-195", dateTime(2026, 4, 21, 8, 0),
                "SPEC-A", "22.5", "MAT-195");
        support195Machine.setSupport195WideBase("1");
        MachineScheduleDTO support225Machine = machine("M-225", dateTime(2026, 4, 21, 8, 0),
                "SPEC-A", "22.5", "MAT-225");
        support225Machine.setSupport225WideBase("1");
        MachineScheduleDTO supportChipMachine = machine("M-CHIP", dateTime(2026, 4, 21, 8, 0),
                "SPEC-A", "22.5", "MAT-CHIP");
        supportChipMachine.setSupportChipTire("1");
        context.getMachineScheduleMap().put(normalMachine.getMachineCode(), normalMachine);
        context.getMachineScheduleMap().put(support195Machine.getMachineCode(), support195Machine);
        context.getMachineScheduleMap().put(support225Machine.getMachineCode(), support225Machine);
        context.getMachineScheduleMap().put(supportChipMachine.getMachineCode(), supportChipMachine);

        assertEquals("M-195", strategy.matchMachines(context, sku("MAT-195", "SPEC-A", "22.5")).get(0).getMachineCode());
        assertEquals("M-225", strategy.matchMachines(context, sku("MAT-225", "SPEC-A", "22.5")).get(0).getMachineCode());
        assertEquals("M-CHIP", strategy.matchMachines(context, sku("MAT-CHIP", "SPEC-A", "22.5")).get(0).getMachineCode());
    }

    @Test
    void matchMachines_shouldRequireAllSpecialMaterialCategories() {
        DefaultMachineMatchStrategy strategy = new DefaultMachineMatchStrategy();
        LhScheduleContext context = buildContext();
        context.getSpecialMaterialCategoryByMaterialCode().put("MAT-BOTH", categorySet("01", "03"));

        MachineScheduleDTO support195OnlyMachine = machine("M-195", dateTime(2026, 4, 21, 8, 0),
                "SPEC-A", "22.5", "MAT-195");
        support195OnlyMachine.setSupport195WideBase("1");
        MachineScheduleDTO supportChipOnlyMachine = machine("M-CHIP", dateTime(2026, 4, 21, 8, 0),
                "SPEC-A", "22.5", "MAT-CHIP");
        supportChipOnlyMachine.setSupportChipTire("1");
        MachineScheduleDTO supportBothMachine = machine("M-BOTH", dateTime(2026, 4, 21, 8, 0),
                "SPEC-A", "22.5", "MAT-BOTH");
        supportBothMachine.setSupport195WideBase("1");
        supportBothMachine.setSupportChipTire("1");
        context.getMachineScheduleMap().put(support195OnlyMachine.getMachineCode(), support195OnlyMachine);
        context.getMachineScheduleMap().put(supportChipOnlyMachine.getMachineCode(), supportChipOnlyMachine);
        context.getMachineScheduleMap().put(supportBothMachine.getMachineCode(), supportBothMachine);

        List<MachineScheduleDTO> candidates = strategy.matchMachines(context, sku("MAT-BOTH", "SPEC-A", "22.5"));

        assertEquals(1, candidates.size(), "同一物料命中多个特殊分类时，机台必须同时满足全部分类支持能力");
        assertEquals("M-BOTH", candidates.get(0).getMachineCode());
    }

    @Test
    void matchMachines_shouldMatchMachineMouldSetByTrimmedShellStandard() {
        DefaultMachineMatchStrategy strategy = new DefaultMachineMatchStrategy();
        LhScheduleContext context = buildContext();

        MachineScheduleDTO machine = machine("M-SHELL", dateTime(2026, 4, 21, 8, 0),
                "SPEC-A", "22.5", "MAT-A");
        machine.setShellStandard(" H420 , H450 ");
        context.getMachineScheduleMap().put(machine.getMachineCode(), machine);
        context.getSkuMouldRelMap().put("MAT-1", Collections.singletonList(mouldRel("MOULD-1")));
        context.getModelInfoMap().put("MOULD-1", modelInfo("MOULD-1", "H450"));

        List<MachineScheduleDTO> candidates = strategy.matchMachines(context, sku("MAT-1", "SPEC-A", "22.5"));

        assertEquals(1, candidates.size());
        assertEquals("M-SHELL", candidates.get(0).getMachineCode());
    }

    @Test
    void matchMachines_shouldKeepMachineWhenMouldSetConfiguredButSkuShellStandardMissing() {
        DefaultMachineMatchStrategy strategy = new DefaultMachineMatchStrategy();
        LhScheduleContext context = buildContext();

        MachineScheduleDTO machine = machine("M-SHELL", dateTime(2026, 4, 21, 8, 0),
                "SPEC-A", "22.5", "MAT-A");
        machine.setShellStandard("H450");
        context.getMachineScheduleMap().put(machine.getMachineCode(), machine);
        context.getSkuMouldRelMap().put("MAT-1", Collections.singletonList(mouldRel("MOULD-1")));
        context.getModelInfoMap().put("MOULD-1", modelInfo("MOULD-1", null));

        List<MachineScheduleDTO> candidates = strategy.matchMachines(context, sku("MAT-1", "SPEC-A", "22.5"));

        assertEquals(1, candidates.size(), "同模壳已改为窗口内排序层级，SKU缺少SHELL_STANDARD时不应被硬过滤");
        assertEquals("M-SHELL", candidates.get(0).getMachineCode());
    }

    @Test
    void matchMachines_shouldTreatUniversalMouldSetAsMatchAll() {
        DefaultMachineMatchStrategy strategy = new DefaultMachineMatchStrategy();
        LhScheduleContext context = buildContext();

        MachineScheduleDTO machine = machine("M-UNIVERSAL", dateTime(2026, 4, 21, 8, 0),
                "SPEC-A", "22.5", "MAT-A");
        machine.setShellStandard(" 通用 ");
        context.getMachineScheduleMap().put(machine.getMachineCode(), machine);
        context.getSkuMouldRelMap().put("MAT-1", Collections.singletonList(mouldRel("MOULD-1")));
        context.getModelInfoMap().put("MOULD-1", modelInfo("MOULD-1", null));

        List<MachineScheduleDTO> candidates = strategy.matchMachines(context, sku("MAT-1", "SPEC-A", "22.5"));

        assertEquals(1, candidates.size(), "机台模套型号为通用时，应等同空值并适配所有SKU");
        assertEquals("M-UNIVERSAL", candidates.get(0).getMachineCode());
    }

    @Test
    void matchMachines_shouldExcludeMachineWhenFutureLongStopOverlapsScheduleWindow() {
        DefaultMachineMatchStrategy strategy = new DefaultMachineMatchStrategy();
        LhScheduleContext context = buildContext();
        context.setScheduleConfig(new LhScheduleConfig(Collections.singletonMap(
                LhScheduleParamConstant.MACHINE_STOP_TIMEOUT_HOURS, "24")));

        MachineScheduleDTO futureStopMachine = machine("M-FUTURE-STOP", dateTime(2026, 4, 21, 8, 0),
                "SPEC-A", "22.5", "MAT-A");
        MachineScheduleDTO availableMachine = machine("M-OK", dateTime(2026, 4, 21, 8, 30),
                "SPEC-A", "22.5", "MAT-B");
        context.getMachineScheduleMap().put(futureStopMachine.getMachineCode(), futureStopMachine);
        context.getMachineScheduleMap().put(availableMachine.getMachineCode(), availableMachine);
        context.getDevicePlanShutList().add(devicePlanShut("M-FUTURE-STOP",
                dateTime(2026, 4, 21, 9, 0), dateTime(2026, 4, 22, 12, 0)));

        List<MachineScheduleDTO> candidates = strategy.matchMachines(context, sku("MAT-1", "SPEC-A", "22.5"));

        assertEquals(1, candidates.size(), "后续长停机与排程窗口重叠时，应切换到其他可用机台");
        assertEquals("M-OK", candidates.get(0).getMachineCode());
    }

    private MdmSkuMouldRel mouldRel(String mouldCode) {
        MdmSkuMouldRel rel = new MdmSkuMouldRel();
        rel.setMouldCode(mouldCode);
        return rel;
    }

    private MdmSkuMouldRel deliveryMouldRel(String mouldCode, Date boardingDate) {
        MdmSkuMouldRel rel = mouldRel(mouldCode);
        rel.setBoardingDate(boardingDate);
        return rel;
    }

    private MdmModelInfo modelInfo(String mouldCode, String shellStandard) {
        MdmModelInfo modelInfo = new MdmModelInfo();
        modelInfo.setMouldCode(mouldCode);
        modelInfo.setShellStandard(shellStandard);
        return modelInfo;
    }

    private LhSpecifyMachine specifyMachine(String materialCode, String machineCode, String jobType) {
        LhSpecifyMachine specifyMachine = new LhSpecifyMachine();
        specifyMachine.setSpecCode(materialCode);
        specifyMachine.setMachineCode(machineCode);
        specifyMachine.setJobType(jobType);
        return specifyMachine;
    }

    private LhScheduleContext buildContext() {
        LhScheduleContext context = new LhScheduleContext();
        context.setMachineScheduleMap(new LinkedHashMap<String, MachineScheduleDTO>());
        context.setMachineAssignmentMap(new LinkedHashMap<String, List<com.zlt.aps.lh.api.domain.entity.LhScheduleResult>>());
        context.setMaterialInfoMap(new HashMap<String, MdmMaterialInfo>());
        context.setDevicePlanShutList(new java.util.ArrayList<MdmDevicePlanShut>());
        context.setScheduleDate(dateTime(2026, 4, 21, 7, 0));
        context.setScheduleTargetDate(dateTime(2026, 4, 21, 7, 0));
        context.setScheduleWindowShifts(LhScheduleTimeUtil.buildDefaultScheduleShifts(context, context.getScheduleDate()));
        return context;
    }

    private LhScheduleContext buildTraceContext() {
        LhScheduleContext context = buildContext();
        context.setFactoryCode("116");
        context.setBatchNo("TRACE-BATCH");
        context.setScheduleConfig(new LhScheduleConfig(Collections.singletonMap(
                LhScheduleParamConstant.ENABLE_PRIORITY_TRACE_LOG, "1")));
        return context;
    }

    private void enableSpecifyMachineRule(LhScheduleContext context) {
        Map<String, String> paramMap = new HashMap<>(1);
        paramMap.put(LhScheduleParamConstant.ENABLE_SPECIFY_MACHINE_RULE, "1");
        context.setScheduleConfig(new LhScheduleConfig(paramMap));
    }

    private void enableSingleControlMachines(LhScheduleContext context) {
        Map<String, String> paramMap = new HashMap<>(1);
        paramMap.put(LhScheduleParamConstant.SINGLE_CONTROL_MACHINE_CODES, "K1501,K1502");
        context.setScheduleConfig(new LhScheduleConfig(paramMap));
    }

    private void enableSingleControlAndSpecifyMachineRule(LhScheduleContext context) {
        Map<String, String> paramMap = new HashMap<>(2);
        paramMap.put(LhScheduleParamConstant.SINGLE_CONTROL_MACHINE_CODES, "K1501,K1502");
        paramMap.put(LhScheduleParamConstant.ENABLE_SPECIFY_MACHINE_RULE, "1");
        context.setScheduleConfig(new LhScheduleConfig(paramMap));
    }

    private MachineScheduleDTO machine(String machineCode, Date estimatedEndTime, String previousSpecCode,
                                       String previousProSize, String previousMaterialCode) {
        MachineScheduleDTO machine = new MachineScheduleDTO();
        machine.setMachineCode(machineCode);
        machine.setStatus("1");
        machine.setMaxMoldNum(1);
        machine.setMachineOrder(1);
        machine.setEstimatedEndTime(estimatedEndTime);
        machine.setPreviousSpecCode(previousSpecCode);
        machine.setPreviousProSize(previousProSize);
        machine.setPreviousMaterialCode(previousMaterialCode);
        return machine;
    }

    private SkuScheduleDTO sku(String materialCode, String specCode, String proSize) {
        SkuScheduleDTO sku = new SkuScheduleDTO();
        sku.setMaterialCode(materialCode);
        sku.setSpecCode(specCode);
        sku.setProSize(proSize);
        return sku;
    }

    private com.zlt.aps.lh.api.domain.dto.SkuDailyPlanQuotaDTO quota(String materialCode,
                                                                      LocalDate productionDate,
                                                                      int dayPlanQty) {
        com.zlt.aps.lh.api.domain.dto.SkuDailyPlanQuotaDTO quota =
                new com.zlt.aps.lh.api.domain.dto.SkuDailyPlanQuotaDTO();
        quota.setMaterialCode(materialCode);
        quota.setProductionDate(productionDate);
        quota.setDayPlanQty(dayPlanQty);
        quota.setRemainingQty(dayPlanQty);
        return quota;
    }

    private com.zlt.aps.lh.api.domain.entity.LhScheduleResult assignedResult(String machineCode,
                                                                              String materialCode,
                                                                              Date specEndTime) {
        com.zlt.aps.lh.api.domain.entity.LhScheduleResult result =
                new com.zlt.aps.lh.api.domain.entity.LhScheduleResult();
        result.setLhMachineCode(machineCode);
        result.setMaterialCode(materialCode);
        result.setScheduleType("02");
        result.setDailyPlanQty(16);
        result.setSpecEndTime(specEndTime);
        return result;
    }

    private LocalDate toLocalDate(LhShiftConfigVO shift) {
        return shift.getWorkDate().toInstant().atZone(java.time.ZoneId.of("Asia/Shanghai")).toLocalDate();
    }

    private Set<String> categorySet(String... categories) {
        return new LinkedHashSet<String>(Arrays.asList(categories));
    }

    private void material(LhScheduleContext context, String materialCode, String embryoDesc) {
        MdmMaterialInfo materialInfo = new MdmMaterialInfo();
        materialInfo.setMaterialCode(materialCode);
        materialInfo.setEmbryoDesc(embryoDesc);
        context.getMaterialInfoMap().put(materialCode, materialInfo);
    }

    private void material(LhScheduleContext context, String materialCode, String embryoCode, String embryoDesc) {
        MdmMaterialInfo materialInfo = new MdmMaterialInfo();
        materialInfo.setMaterialCode(materialCode);
        materialInfo.setEmbryoCode(embryoCode);
        materialInfo.setEmbryoDesc(embryoDesc);
        context.getMaterialInfoMap().put(materialCode, materialInfo);
    }

    private MdmDevicePlanShut devicePlanShut(String machineCode, Date beginDate, Date endDate) {
        MdmDevicePlanShut devicePlanShut = new MdmDevicePlanShut();
        devicePlanShut.setMachineCode(machineCode);
        devicePlanShut.setBeginDate(beginDate);
        devicePlanShut.setEndDate(endDate);
        return devicePlanShut;
    }

    private Date dateTime(int year, int month, int day, int hour, int minute) {
        java.util.Calendar calendar = java.util.Calendar.getInstance();
        calendar.clear();
        calendar.set(java.util.Calendar.YEAR, year);
        calendar.set(java.util.Calendar.MONTH, month - 1);
        calendar.set(java.util.Calendar.DAY_OF_MONTH, day);
        calendar.set(java.util.Calendar.HOUR_OF_DAY, hour);
        calendar.set(java.util.Calendar.MINUTE, minute);
        return calendar.getTime();
    }
}
