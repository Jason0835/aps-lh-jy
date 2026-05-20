package com.zlt.aps.lh.regression;

import com.zlt.aps.lh.api.domain.dto.SkuDailyPlanQuotaDTO;
import com.zlt.aps.lh.api.domain.entity.LhMouldChangePlan;
import com.zlt.aps.lh.api.domain.entity.LhScheduleProcessLog;
import com.zlt.aps.lh.api.domain.entity.LhScheduleResult;
import com.zlt.aps.lh.api.domain.dto.SkuScheduleDTO;
import com.zlt.aps.lh.api.enums.ConstructionStageEnum;
import com.zlt.aps.lh.api.enums.MouldChangeTypeEnum;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.engine.observer.ScheduleEventPublisher;
import com.zlt.aps.lh.exception.ScheduleException;
import com.zlt.aps.lh.handler.ResultValidationHandler;
import com.zlt.aps.lh.service.impl.SchedulePersistenceService;
import com.zlt.aps.mp.api.domain.entity.FactoryMonthPlanProductionFinalResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 结果强校验：关键字段缺失时必须阻断持久化。
 */
@ExtendWith(MockitoExtension.class)
class ResultValidationBlockingTest {

    @Mock
    private ScheduleEventPublisher scheduleEventPublisher;

    @Mock
    private SchedulePersistenceService schedulePersistenceService;

    @InjectMocks
    private ResultValidationHandler handler;

    @Test
    void handle_throwsWhenSpecEndTimeMissing() {
        LhScheduleContext context = new LhScheduleContext();
        context.setFactoryCode("FC01");
        context.setBatchNo("LHPC20260413001");

        LhScheduleResult result = new LhScheduleResult();
        result.setLhMachineCode("M1");
        result.setMaterialCode("MAT-1");
        result.setScheduleType("02");
        result.setIsChangeMould("1");
        result.setMouldCode("MOULD-1");
        result.setDailyPlanQty(1);
        context.setScheduleResultList(Collections.singletonList(result));

        assertThrows(ScheduleException.class, () -> handler.handle(context));

        verify(schedulePersistenceService, never()).replaceScheduleAtomically(context);
        verify(scheduleEventPublisher, never()).publish(any());
    }

    @Test
    void handle_throwsWhenZeroDailyPlanMissingSpecEndTime() {
        LhScheduleContext context = new LhScheduleContext();
        context.setFactoryCode("FC01");
        context.setBatchNo("LHPC20260413002");

        LhScheduleResult result = new LhScheduleResult();
        result.setLhMachineCode("M1");
        result.setMaterialCode("MAT-1");
        result.setScheduleType("01");
        result.setDailyPlanQty(0);
        result.setIsChangeMould("0");
        context.setScheduleResultList(Collections.singletonList(result));

        assertThrows(ScheduleException.class, () -> handler.handle(context));

        verify(schedulePersistenceService, never()).replaceScheduleAtomically(context);
        verify(scheduleEventPublisher, never()).publish(any());
    }

    @Test
    void handle_throwsWhenMorningMouldChangePlanExceedsLimit() {
        LhScheduleContext context = new LhScheduleContext();
        context.setFactoryCode("FC01");
        context.setBatchNo("LHPC20260413003");
        context.setScheduleTargetDate(date(2026, 4, 13));
        context.setScheduleResultList(new ArrayList<LhScheduleResult>());

        for (int index = 0; index < 9; index++) {
            LhMouldChangePlan plan = new LhMouldChangePlan();
            plan.setLhMachineCode("M" + index);
            plan.setLhMachineName("机台" + index);
            plan.setPlanDate(dateTime(2026, 4, 13, 6 + index / 2, (index % 2) * 30));
            plan.setChangeTime(plan.getPlanDate());
            plan.setChangeMouldType(index == 0
                    ? MouldChangeTypeEnum.TYPE_BLOCK.getCode()
                    : MouldChangeTypeEnum.REGULAR.getCode());
            plan.setIsDelete(0);
            context.getMouldChangePlanList().add(plan);
        }

        assertThrows(ScheduleException.class, () -> handler.handle(context));

        verify(schedulePersistenceService, never()).replaceScheduleAtomically(context);
        verify(scheduleEventPublisher, never()).publish(any());
    }

    @Test
    void handle_throwsWhenSameEmbryoChangeoverInSameShift() {
        LhScheduleContext context = new LhScheduleContext();
        context.setFactoryCode("FC01");
        context.setBatchNo("LHPC20260413004");
        context.setScheduleDate(date(2026, 4, 13));
        context.setScheduleTargetDate(date(2026, 4, 15));

        LhScheduleResult first = buildChangeResult("K1105", "MAT-A", "EMB-A",
                dateTime(2026, 4, 13, 6, 0));
        LhScheduleResult second = buildChangeResult("K1110", "MAT-B", "EMB-A",
                dateTime(2026, 4, 13, 7, 0));
        context.setScheduleResultList(Arrays.asList(first, second));

        assertThrows(ScheduleException.class, () -> handler.handle(context));

        verify(schedulePersistenceService, never()).replaceScheduleAtomically(context);
        verify(scheduleEventPublisher, never()).publish(any());
    }

    @Test
    void handle_allowsSameSkuEmbryoChangeoverInSameShift() {
        LhScheduleContext context = new LhScheduleContext();
        context.setFactoryCode("FC01");
        context.setBatchNo("LHPC20260413004S");
        context.setScheduleDate(date(2026, 4, 13));
        context.setScheduleTargetDate(date(2026, 4, 15));

        LhScheduleResult first = buildChangeResult("K1105", "MAT-A", "EMB-A",
                dateTime(2026, 4, 13, 6, 0));
        LhScheduleResult second = buildChangeResult("K1110", "MAT-A", "EMB-A",
                dateTime(2026, 4, 13, 7, 0));
        context.setScheduleResultList(Arrays.asList(first, second));

        assertDoesNotThrow(() -> handler.handle(context));

        verify(schedulePersistenceService, times(1)).replaceScheduleAtomically(context);
        verify(scheduleEventPublisher, times(1)).publish(any());
    }

    @Test
    void handle_throwsWhenRollingInheritedChangeoverConflictsWithNewResult() {
        LhScheduleContext context = new LhScheduleContext();
        context.setFactoryCode("FC01");
        context.setBatchNo("LHPC20260413004A");
        context.setScheduleDate(date(2026, 4, 13));
        context.setScheduleTargetDate(date(2026, 4, 15));

        LhScheduleResult inherited = buildChangeResult("K1105", "MAT-A", "EMB-A",
                dateTime(2026, 4, 13, 6, 0));
        inherited.setRollingInherited(true);
        LhScheduleResult appended = buildChangeResult("K1110", "MAT-B", "EMB-A",
                dateTime(2026, 4, 13, 7, 0));
        context.setScheduleResultList(Arrays.asList(inherited, appended));
        context.getRollingInheritedScheduleResultList().add(inherited);

        assertThrows(ScheduleException.class, () -> handler.handle(context));

        verify(schedulePersistenceService, never()).replaceScheduleAtomically(context);
        verify(scheduleEventPublisher, never()).publish(any());
    }

    @Test
    void handle_throwsWhenStrictSkuExceedsTargetQty() {
        LhScheduleContext context = new LhScheduleContext();
        context.setFactoryCode("FC01");
        context.setBatchNo("LHPC20260413005");
        context.setScheduleDate(date(2026, 4, 13));
        context.setScheduleTargetDate(date(2026, 4, 15));

        SkuScheduleDTO sku = buildSku("MAT-TRIAL", ConstructionStageEnum.TRIAL.getCode(), 46, 16, true);
        LhScheduleResult result = buildPlanResult("K1105", "MAT-TRIAL", 16, 16, 16);
        context.setScheduleResultList(Collections.singletonList(result));
        context.getScheduleResultSourceSkuMap().put(result, sku);

        assertThrows(ScheduleException.class, () -> handler.handle(context));

        verify(schedulePersistenceService, never()).replaceScheduleAtomically(context);
        verify(scheduleEventPublisher, never()).publish(any());
    }

    @Test
    void handle_throwsWhenFormalSkuOverQtyReachesOneShiftCapacity() {
        LhScheduleContext context = new LhScheduleContext();
        context.setFactoryCode("FC01");
        context.setBatchNo("LHPC20260413006");
        context.setScheduleDate(date(2026, 4, 13));
        context.setScheduleTargetDate(date(2026, 4, 15));

        SkuScheduleDTO sku = buildSku("MAT-FORMAL", ConstructionStageEnum.FORMAL.getCode(), 46, 16, false);
        LhScheduleResult result = buildPlanResult("K1105", "MAT-FORMAL", 16, 16, 16, 16);
        context.setScheduleResultList(Collections.singletonList(result));
        context.getScheduleResultSourceSkuMap().put(result, sku);

        assertThrows(ScheduleException.class, () -> handler.handle(context));

        verify(schedulePersistenceService, never()).replaceScheduleAtomically(context);
        verify(scheduleEventPublisher, never()).publish(any());
    }

    @Test
    void handle_allowsWhenLedgerTargetSatisfiedButOriginalTargetNotMet() {
        LhScheduleContext context = new LhScheduleContext();
        context.setFactoryCode("FC01");
        context.setBatchNo("LHPC20260413006A");
        context.setScheduleDate(date(2026, 4, 13));
        context.setScheduleTargetDate(date(2026, 4, 15));

        SkuScheduleDTO sku = buildSku("MAT-FORMAL", ConstructionStageEnum.FORMAL.getCode(), 158, 16, false);
        sku.setWindowPlanQty(46);
        Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap = new LinkedHashMap<LocalDate, SkuDailyPlanQuotaDTO>(2);
        quotaMap.put(LocalDate.of(2026, 4, 13), buildQuota("MAT-FORMAL", LocalDate.of(2026, 4, 13), 32, 32, 0));
        quotaMap.put(LocalDate.of(2026, 4, 14), buildQuota("MAT-FORMAL", LocalDate.of(2026, 4, 14), 14, 14, 0));
        sku.setDailyPlanQuotaMap(quotaMap);

        LhScheduleResult result = buildPlanResult("K1105", "MAT-FORMAL", 16, 16, 16);
        context.setScheduleResultList(Collections.singletonList(result));
        context.getScheduleResultSourceSkuMap().put(result, sku);

        assertDoesNotThrow(() -> handler.handle(context));

        verify(schedulePersistenceService, times(1)).replaceScheduleAtomically(context);
        verify(scheduleEventPublisher, times(1)).publish(any());
    }

    @Test
    void handle_throwsWhenFormalSkuOverQtyReachesActualShiftCapacityBeforeStandardCapacity() {
        LhScheduleContext context = new LhScheduleContext();
        context.setFactoryCode("FC01");
        context.setBatchNo("LHPC20260413006B");
        context.setScheduleDate(date(2026, 4, 13));
        context.setScheduleTargetDate(date(2026, 4, 15));

        SkuScheduleDTO sku = buildSku("MAT-FORMAL", ConstructionStageEnum.FORMAL.getCode(), 46, 24, false);
        LhScheduleResult result = buildPlanResult("K1105", "MAT-FORMAL", 12, 12, 12, 12);
        result.setClass5PlanQty(10);
        result.setDailyPlanQty(58);
        context.setScheduleResultList(Collections.singletonList(result));
        context.getScheduleResultSourceSkuMap().put(result, sku);

        assertThrows(ScheduleException.class, () -> handler.handle(context));

        verify(schedulePersistenceService, never()).replaceScheduleAtomically(context);
        verify(scheduleEventPublisher, never()).publish(any());
    }

    @Test
    void handle_throwsWhenTailResultShiftCapacityIsSmallerThanEarlierMachine() {
        LhScheduleContext context = new LhScheduleContext();
        context.setFactoryCode("FC01");
        context.setBatchNo("LHPC20260413006C");
        context.setScheduleDate(date(2026, 4, 13));
        context.setScheduleTargetDate(date(2026, 4, 15));

        SkuScheduleDTO sku = buildSku("MAT-FORMAL", ConstructionStageEnum.FORMAL.getCode(), 46, 16, false);
        LhScheduleResult fullRunResult = buildPlanResult("K1105", "MAT-FORMAL", 16, 16, 15);
        fullRunResult.setDailyPlanQty(47);
        LhScheduleResult tailResult = buildPlanResult("K1110", "MAT-FORMAL", 0, 0, 0, 10);
        tailResult.setDailyPlanQty(10);
        tailResult.setSpecEndTime(dateTime(2026, 4, 13, 18, 0));

        context.setScheduleResultList(Arrays.asList(fullRunResult, tailResult));
        context.getScheduleResultSourceSkuMap().put(fullRunResult, sku);
        context.getScheduleResultSourceSkuMap().put(tailResult, sku);

        assertThrows(ScheduleException.class, () -> handler.handle(context));

        verify(schedulePersistenceService, never()).replaceScheduleAtomically(context);
        verify(scheduleEventPublisher, never()).publish(any());
    }

    @Test
    void handle_allowsContinuousAndCompensationResultsWhenSharingQuotaAnchor() {
        LhScheduleContext context = new LhScheduleContext();
        context.setFactoryCode("FC01");
        context.setBatchNo("LHPC20260413007");
        context.setScheduleDate(date(2026, 4, 13));
        context.setScheduleTargetDate(date(2026, 4, 15));

        Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap = new LinkedHashMap<LocalDate, SkuDailyPlanQuotaDTO>(4);
        quotaMap.put(LocalDate.of(2026, 4, 13), buildQuota("MAT-FORMAL", LocalDate.of(2026, 4, 13), 96));
        quotaMap.put(LocalDate.of(2026, 4, 14), buildQuota("MAT-FORMAL", LocalDate.of(2026, 4, 14), 48));
        quotaMap.put(LocalDate.of(2026, 4, 15), buildQuota("MAT-FORMAL", LocalDate.of(2026, 4, 15), 14));

        SkuScheduleDTO continuousSku = buildSku("MAT-FORMAL", ConstructionStageEnum.FORMAL.getCode(), 158, 16, false);
        continuousSku.setDailyPlanQuotaMap(quotaMap);
        SkuScheduleDTO compensationSku = buildSku("MAT-FORMAL", ConstructionStageEnum.FORMAL.getCode(), 46, 16, false);
        compensationSku.setDailyPlanQuotaMap(quotaMap);
        context.setContinuousSkuList(Collections.singletonList(continuousSku));
        context.setNewSpecSkuList(Collections.singletonList(compensationSku));

        LhScheduleResult continuousResult = buildPlanResult("K1105", "MAT-FORMAL", 16, 16, 16, 16);
        continuousResult.setClass5PlanQty(16);
        continuousResult.setClass6PlanQty(16);
        continuousResult.setClass7PlanQty(16);
        continuousResult.setSpecEndTime(dateTime(2026, 4, 15, 14, 0));
        continuousResult.setScheduleType("01");
        continuousResult.setDailyPlanQty(112);

        LhScheduleResult compensationResult = buildPlanResult("K1110", "MAT-FORMAL", 0, 0, 16, 16);
        compensationResult.setClass5PlanQty(16);
        compensationResult.setSpecEndTime(dateTime(2026, 4, 15, 6, 0));
        compensationResult.setScheduleType("02");
        compensationResult.setDailyPlanQty(48);

        context.setScheduleResultList(Arrays.asList(continuousResult, compensationResult));
        context.getScheduleResultSourceSkuMap().put(continuousResult, continuousSku);
        context.getScheduleResultSourceSkuMap().put(compensationResult, compensationSku);

        assertDoesNotThrow(() -> handler.handle(context));

        verify(schedulePersistenceService, times(1)).replaceScheduleAtomically(context);
        verify(scheduleEventPublisher, times(1)).publish(any());
    }

    @Test
    void handle_allowsFormalContinuousMultiMachineReduceMouldResultWhenLedgerTargetExceedsRuntimeTarget() {
        LhScheduleContext context = new LhScheduleContext();
        context.setFactoryCode("FC01");
        context.setBatchNo("LHPC20260413007A");
        context.setScheduleDate(date(2026, 5, 1));
        context.setScheduleTargetDate(date(2026, 5, 3));

        Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap = new LinkedHashMap<LocalDate, SkuDailyPlanQuotaDTO>(4);
        quotaMap.put(LocalDate.of(2026, 5, 1), buildQuota("MAT-FORMAL", LocalDate.of(2026, 5, 1), 64));
        quotaMap.put(LocalDate.of(2026, 5, 2), buildQuota("MAT-FORMAL", LocalDate.of(2026, 5, 2), 48));
        quotaMap.put(LocalDate.of(2026, 5, 3), buildQuota("MAT-FORMAL", LocalDate.of(2026, 5, 3), 48));

        SkuScheduleDTO firstContinuousSku = buildSku("MAT-FORMAL", ConstructionStageEnum.FORMAL.getCode(), 128, 16, false);
        firstContinuousSku.setDailyPlanQuotaMap(quotaMap);
        SkuScheduleDTO secondContinuousSku = buildSku("MAT-FORMAL", ConstructionStageEnum.FORMAL.getCode(), 128, 16, false);
        secondContinuousSku.setDailyPlanQuotaMap(quotaMap);
        context.setContinuousSkuList(Arrays.asList(firstContinuousSku, secondContinuousSku));

        LhScheduleResult retainedResult = buildPlanResult("K1405", "MAT-FORMAL", 16, 16, 16, 16);
        retainedResult.setClass5PlanQty(16);
        retainedResult.setClass6PlanQty(16);
        retainedResult.setClass7PlanQty(16);
        retainedResult.setClass8PlanQty(16);
        retainedResult.setDailyPlanQty(128);
        retainedResult.setScheduleType("01");
        retainedResult.setSpecEndTime(dateTime(2026, 5, 3, 22, 0));

        LhScheduleResult removedResult = buildPlanResult("K1702", "MAT-FORMAL", 16, 16, 0, 0);
        removedResult.setDailyPlanQty(32);
        removedResult.setScheduleType("01");
        removedResult.setSpecEndTime(dateTime(2026, 5, 1, 14, 0));

        context.setScheduleResultList(Arrays.asList(retainedResult, removedResult));
        context.getScheduleResultSourceSkuMap().put(retainedResult, firstContinuousSku);
        context.getScheduleResultSourceSkuMap().put(removedResult, secondContinuousSku);

        assertDoesNotThrow(() -> handler.handle(context));

        verify(schedulePersistenceService, times(1)).replaceScheduleAtomically(context);
        verify(scheduleEventPublisher, times(1)).publish(any());
    }

    @Test
    void handle_shouldWriteRollingQuotaLedgerDetailLog() {
        LhScheduleContext context = new LhScheduleContext();
        context.setFactoryCode("FC01");
        context.setBatchNo("LHPC20260413008");
        context.setScheduleDate(date(2026, 4, 13));
        context.setScheduleTargetDate(date(2026, 4, 15));

        FactoryMonthPlanProductionFinalResult plan = new FactoryMonthPlanProductionFinalResult();
        plan.setMaterialCode("MAT-FORMAL");
        context.setMonthPlanList(Collections.singletonList(plan));

        Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap = new LinkedHashMap<LocalDate, SkuDailyPlanQuotaDTO>(4);
        quotaMap.put(LocalDate.of(2026, 4, 13), buildDetailedQuota("MAT-FORMAL", LocalDate.of(2026, 4, 13),
                96, 32, 64, 32, 0, 32, 0));
        quotaMap.put(LocalDate.of(2026, 4, 14), buildDetailedQuota("MAT-FORMAL", LocalDate.of(2026, 4, 14),
                48, 48, 0, 32, 16, 48, 0));
        quotaMap.put(LocalDate.of(2026, 4, 15), buildDetailedQuota("MAT-FORMAL", LocalDate.of(2026, 4, 15),
                14, 14, 0, 0, 30, 14, 0));

        SkuScheduleDTO sku = buildSku("MAT-FORMAL", ConstructionStageEnum.FORMAL.getCode(), 32, 16, false);
        sku.setDailyPlanQuotaMap(quotaMap);
        sku.setWindowPlanQty(158);

        LhScheduleResult result = buildPlanResult("K1105", "MAT-FORMAL", 16, 16, 0);
        context.setScheduleResultList(Collections.singletonList(result));
        context.getScheduleResultSourceSkuMap().put(result, sku);

        assertDoesNotThrow(() -> handler.handle(context));

        LhScheduleProcessLog ledgerLog = context.getScheduleLogList().stream()
                .filter(log -> log != null && "日计划滚动台账".equals(log.getTitle()))
                .findFirst()
                .orElse(null);

        assertNotNull(ledgerLog, "应输出日计划滚动台账过程日志");
        assertTrue(ledgerLog.getLogDetail().contains("物料=MAT-FORMAL"));
        assertTrue(ledgerLog.getLogDetail().contains("日期=2026-04-13"));
        assertTrue(ledgerLog.getLogDetail().contains("dayPlanQty=96"));
        assertTrue(ledgerLog.getLogDetail().contains("scheduledQty=32"));
        assertTrue(ledgerLog.getLogDetail().contains("remainingQty=64"));
        assertTrue(ledgerLog.getLogDetail().contains("carryLossQty=32"));
        assertTrue(ledgerLog.getLogDetail().contains("futureBorrowQty=16"));
        assertTrue(ledgerLog.getLogDetail().contains("actualQty=48"));
        assertTrue(ledgerLog.getLogDetail().contains("finalLossQty=0"));

        verify(schedulePersistenceService, times(1)).replaceScheduleAtomically(context);
        verify(scheduleEventPublisher, times(1)).publish(any());
    }

    private static java.util.Date date(int year, int month, int day) {
        Calendar calendar = Calendar.getInstance();
        calendar.clear();
        calendar.set(Calendar.YEAR, year);
        calendar.set(Calendar.MONTH, month - 1);
        calendar.set(Calendar.DAY_OF_MONTH, day);
        return calendar.getTime();
    }

    private static java.util.Date dateTime(int year, int month, int day, int hour, int minute) {
        Calendar calendar = Calendar.getInstance();
        calendar.clear();
        calendar.set(Calendar.YEAR, year);
        calendar.set(Calendar.MONTH, month - 1);
        calendar.set(Calendar.DAY_OF_MONTH, day);
        calendar.set(Calendar.HOUR_OF_DAY, hour);
        calendar.set(Calendar.MINUTE, minute);
        return calendar.getTime();
    }

    private static LhScheduleResult buildChangeResult(String machineCode,
                                                      String materialCode,
                                                      String embryoCode,
                                                      java.util.Date mouldChangeStartTime) {
        LhScheduleResult result = new LhScheduleResult();
        result.setLhMachineCode(machineCode);
        result.setMaterialCode(materialCode);
        result.setEmbryoCode(embryoCode);
        result.setScheduleType("02");
        result.setIsChangeMould("1");
        result.setMouldCode("MOULD-" + materialCode);
        result.setDailyPlanQty(16);
        result.setSpecEndTime(dateTime(2026, 4, 13, 14, 0));
        result.setMouldChangeStartTime(mouldChangeStartTime);
        return result;
    }

    private static SkuScheduleDTO buildSku(String materialCode,
                                           String constructionStage,
                                           int targetQty,
                                           int shiftCapacity,
                                           boolean strictTargetQty) {
        SkuScheduleDTO sku = new SkuScheduleDTO();
        sku.setMaterialCode(materialCode);
        sku.setConstructionStage(constructionStage);
        sku.setTargetScheduleQty(targetQty);
        sku.setShiftCapacity(shiftCapacity);
        sku.setStrictTargetQty(strictTargetQty);
        return sku;
    }

    private static LhScheduleResult buildPlanResult(String machineCode,
                                                    String materialCode,
                                                    int class1PlanQty,
                                                    int class2PlanQty,
                                                    int class3PlanQty) {
        return buildPlanResult(machineCode, materialCode, class1PlanQty, class2PlanQty, class3PlanQty, 0);
    }

    private static LhScheduleResult buildPlanResult(String machineCode,
                                                    String materialCode,
                                                    int class1PlanQty,
                                                    int class2PlanQty,
                                                    int class3PlanQty,
                                                    int class4PlanQty) {
        LhScheduleResult result = new LhScheduleResult();
        result.setLhMachineCode(machineCode);
        result.setMaterialCode(materialCode);
        result.setScheduleType("02");
        result.setIsChangeMould("0");
        result.setDailyPlanQty(class1PlanQty + class2PlanQty + class3PlanQty + class4PlanQty);
        result.setSpecEndTime(dateTime(2026, 4, 13, 22, 0));
        result.setClass1PlanQty(class1PlanQty);
        result.setClass2PlanQty(class2PlanQty);
        result.setClass3PlanQty(class3PlanQty);
        result.setClass4PlanQty(class4PlanQty);
        return result;
    }

    private static SkuDailyPlanQuotaDTO buildQuota(String materialCode, LocalDate productionDate, int dayPlanQty) {
        return buildQuota(materialCode, productionDate, dayPlanQty, dayPlanQty, 0);
    }

    private static SkuDailyPlanQuotaDTO buildQuota(String materialCode,
                                                   LocalDate productionDate,
                                                   int dayPlanQty,
                                                   int scheduledQty,
                                                   int remainingQty) {
        SkuDailyPlanQuotaDTO quota = new SkuDailyPlanQuotaDTO();
        quota.setMaterialCode(materialCode);
        quota.setProductionDate(productionDate);
        quota.setDayPlanQty(dayPlanQty);
        quota.setScheduledQty(scheduledQty);
        quota.setRemainingQty(remainingQty);
        return quota;
    }

    private static SkuDailyPlanQuotaDTO buildDetailedQuota(String materialCode,
                                                           LocalDate productionDate,
                                                           int dayPlanQty,
                                                           int scheduledQty,
                                                           int remainingQty,
                                                           int carryLossQty,
                                                           int futureBorrowQty,
                                                           int actualQty,
                                                           int finalLossQty) {
        SkuDailyPlanQuotaDTO quota = buildQuota(materialCode, productionDate, dayPlanQty, scheduledQty, remainingQty);
        quota.setCarryLossQty(carryLossQty);
        quota.setFutureBorrowQty(futureBorrowQty);
        quota.setActualQty(actualQty);
        quota.setFinalLossQty(finalLossQty);
        return quota;
    }
}
