/**
 * Copyright (c) 2008, 智立通（厦门）科技有限公司 All rights reserved。
 */
package com.zlt.aps.lh.engine.strategy.impl;

import com.zlt.aps.lh.api.domain.dto.MachineScheduleDTO;
import com.zlt.aps.lh.api.domain.dto.SkuDailyPlanQuotaDTO;
import com.zlt.aps.lh.api.domain.dto.SkuScheduleDTO;
import com.zlt.aps.lh.api.domain.entity.LhScheduleResult;
import com.zlt.aps.lh.api.domain.entity.LhUnscheduledResult;
import com.zlt.aps.lh.api.domain.vo.LhShiftConfigVO;
import com.zlt.aps.lh.api.enums.ShiftEnum;
import com.zlt.aps.lh.api.enums.TrialStatusEnum;
import com.zlt.aps.lh.api.constant.LhScheduleConstant;
import com.zlt.aps.lh.component.MonthPlanDateResolver;
import com.zlt.aps.lh.component.OrderNoGenerator;
import com.zlt.aps.lh.component.TargetScheduleQtyResolver;
import com.zlt.aps.lh.context.EmbryoStockConsumeLedger;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.util.ShiftFieldUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 同物料多产品状态续作切换聚焦测试。
 *
 * <p>大部分用例直接调用S4.4最终账本同步前的专用分配步骤，精确验证状态链时间轴；
 * 同时通过完整S4.4用例覆盖普通后处理、胎胚账本与最终日额度同步的真实调用顺序。</p>
 */
public class ContinuousProductionStrategySameMaterialStatusTest {

    private static final String MATERIAL_CODE = "330200TEST";
    private static final String OTHER_MATERIAL_CODE = "330200OTHER";
    private static final String MACHINE_CODE_1 = "LH01";
    private static final String MACHINE_CODE_2 = "LH02";
    private static final int SHIFT_CAPACITY = 16;
    private static final int LH_TIME_SECONDS = 1800;

    private ContinuousProductionStrategy strategy;
    private LocalDate scheduleDate;

    /**
     * 初始化被测策略的必要协作者。
     */
    @BeforeEach
    public void setUp() {
        this.strategy = new ContinuousProductionStrategy();
        this.scheduleDate = LocalDate.of(2026, 7, 16);
        OrderNoGenerator orderNoGenerator = new OrderNoGenerator();
        // 单元测试不连接Redis，直接使用工单号生成器已有的本地序列模式。
        ReflectionTestUtils.setField(orderNoGenerator, "useRedis", false);
        ReflectionTestUtils.setField(this.strategy, "orderNoGenerator", orderNoGenerator);
        TargetScheduleQtyResolver targetScheduleQtyResolver = new TargetScheduleQtyResolver();
        ReflectionTestUtils.setField(
                this.strategy, "targetScheduleQtyResolver", targetScheduleQtyResolver);
        DefaultEndingJudgmentStrategy endingJudgmentStrategy = new DefaultEndingJudgmentStrategy();
        ReflectionTestUtils.setField(
                endingJudgmentStrategy, "targetScheduleQtyResolver", targetScheduleQtyResolver);
        ReflectionTestUtils.setField(
                this.strategy, "endingJudgmentStrategy", endingJudgmentStrategy);
    }

    /**
     * 验证正规+试制：首个中班固定正规4条，剩余12条由试制一次排完。
     */
    @Test
    public void shouldReserveFourAndScheduleTrialInFirstAfternoonShift() {
        TestFixture fixture = this.buildFixture(false);
        SkuScheduleDTO trialSku = this.buildSpecialSku(
                MATERIAL_CODE, TrialStatusEnum.TRIAL.getCode(), 12, this.scheduleDate);
        fixture.context.getNewSpecSkuList().add(trialSku);

        this.invokeSameMaterialStatusSwitch(fixture);

        LhScheduleResult trialResult = this.findResult(
                fixture.context, MATERIAL_CODE, TrialStatusEnum.TRIAL.getCode());
        assertNotNull(trialResult);
        assertEquals(4, this.resolveShiftQty(fixture.carrierResult, 2));
        assertEquals(12, this.resolveShiftQty(trialResult, 2));
        assertEquals(12, ShiftFieldUtil.resolveScheduledQty(trialResult));
        this.assertNoMouldOrTypeBlockChange(trialResult);
        assertFalse(fixture.context.getNewSpecSkuList().contains(trialSku));
        assertFalse(fixture.managedFormalSkuKeySet.isEmpty());
        assertTrue(ShiftFieldUtil.getShiftAnalysis(trialResult, 2)
                .contains(LhScheduleConstant.SAME_MATERIAL_STATUS_CONTINUATION_ANALYSIS));
    }

    /**
     * 验证正规+量试示例：两台正规续作机台只选择下机顺序首位的LH02承接，
     * 量试60条按12+16+16+16连续排完，随后正规从第6班恢复。
     */
    @Test
    public void shouldScheduleMassTrialSixtyContinuouslyAndRestoreFormalOnSameMachine() {
        TestFixture fixture = this.buildFixture(true);
        SkuScheduleDTO massTrialSku = this.buildSpecialSku(
                MATERIAL_CODE, TrialStatusEnum.MASS_TRIAL.getCode(), 60, this.scheduleDate);
        fixture.context.getNewSpecSkuList().add(massTrialSku);

        this.invokeSameMaterialStatusSwitch(fixture);

        LhScheduleResult massTrialResult = this.findResult(
                fixture.context, MATERIAL_CODE, TrialStatusEnum.MASS_TRIAL.getCode());
        assertNotNull(massTrialResult);
        assertEquals(MACHINE_CODE_2, massTrialResult.getLhMachineCode());
        assertEquals(4, this.resolveShiftQty(fixture.carrierResult, 2));
        assertEquals(12, this.resolveShiftQty(massTrialResult, 2));
        assertEquals(16, this.resolveShiftQty(massTrialResult, 3));
        assertEquals(16, this.resolveShiftQty(massTrialResult, 4));
        assertEquals(16, this.resolveShiftQty(massTrialResult, 5));
        assertEquals(16, this.resolveShiftQty(fixture.carrierResult, 6));
        assertEquals(16, this.resolveShiftQty(fixture.otherFormalResult, 2));
        assertEquals(60, ShiftFieldUtil.resolveScheduledQty(massTrialResult));
        this.assertNoMouldOrTypeBlockChange(massTrialResult);
    }

    /**
     * 验证正规+试制+量试：X组优先于T组，状态交接可连续进入下一班，
     * 正规固定4条只在整条状态链的第一次切换中执行一次。
     */
    @Test
    public void shouldScheduleTrialBeforeMassTrialAndReserveFormalOnlyOnce() {
        TestFixture fixture = this.buildFixture(false);
        SkuScheduleDTO massTrialSku = this.buildSpecialSku(
                MATERIAL_CODE, TrialStatusEnum.MASS_TRIAL.getCode(), 20, this.scheduleDate);
        SkuScheduleDTO trialSku = this.buildSpecialSku(
                MATERIAL_CODE, TrialStatusEnum.TRIAL.getCode(), 12, this.scheduleDate);
        // 故意把T放在X前面，验证状态分组顺序覆盖列表中的跨组顺序，同组排序仍保持不变。
        fixture.context.getNewSpecSkuList().add(massTrialSku);
        fixture.context.getNewSpecSkuList().add(trialSku);

        this.invokeSameMaterialStatusSwitch(fixture);

        List<LhScheduleResult> specialResults = this.findSpecialResults(fixture.context, MATERIAL_CODE);
        assertEquals(2, specialResults.size());
        assertEquals(TrialStatusEnum.TRIAL.getCode(), specialResults.get(0).getProductStatus());
        assertEquals(TrialStatusEnum.MASS_TRIAL.getCode(), specialResults.get(1).getProductStatus());
        assertEquals(4, this.resolveShiftQty(fixture.carrierResult, 2));
        assertEquals(12, this.resolveShiftQty(specialResults.get(0), 2));
        assertEquals(16, this.resolveShiftQty(specialResults.get(1), 3));
        assertEquals(4, this.resolveShiftQty(specialResults.get(1), 4));
        assertEquals(0, this.resolveShiftQty(fixture.carrierResult, 3));
        assertEquals(0, this.resolveShiftQty(fixture.carrierResult, 4));
    }

    /**
     * 验证X/T准入日期错开：X完成后的间隔班次由正规正常续作，
     * T到达自身首个正计划日中班后再切入，且不重复扣正规4条。
     */
    @Test
    public void shouldRestoreFormalBetweenDifferentSpecialEligibilityDates() {
        TestFixture fixture = this.buildFixture(false);
        SkuScheduleDTO trialSku = this.buildSpecialSku(
                MATERIAL_CODE, TrialStatusEnum.TRIAL.getCode(), 12, this.scheduleDate);
        SkuScheduleDTO massTrialSku = this.buildSpecialSku(
                MATERIAL_CODE, TrialStatusEnum.MASS_TRIAL.getCode(), 16, this.scheduleDate.plusDays(1));
        fixture.context.getNewSpecSkuList().add(trialSku);
        fixture.context.getNewSpecSkuList().add(massTrialSku);

        this.invokeSameMaterialStatusSwitch(fixture);

        LhScheduleResult massTrialResult = this.findResult(
                fixture.context, MATERIAL_CODE, TrialStatusEnum.MASS_TRIAL.getCode());
        assertNotNull(massTrialResult);
        assertEquals(16, this.resolveShiftQty(fixture.carrierResult, 3));
        assertEquals(16, this.resolveShiftQty(fixture.carrierResult, 4));
        assertEquals(0, this.resolveShiftQty(fixture.carrierResult, 5));
        assertEquals(16, this.resolveShiftQty(massTrialResult, 5));
        assertEquals(16, this.resolveShiftQty(fixture.carrierResult, 6));
    }

    /**
     * 验证不同物料不会误匹配，普通新增X/T仍留在S4.5候选列表。
     */
    @Test
    public void shouldNotMatchSpecialSkuFromDifferentMaterial() {
        TestFixture fixture = this.buildFixture(false);
        SkuScheduleDTO otherTrialSku = this.buildSpecialSku(
                OTHER_MATERIAL_CODE, TrialStatusEnum.TRIAL.getCode(), 12, this.scheduleDate);
        fixture.context.getNewSpecSkuList().add(otherTrialSku);

        this.invokeSameMaterialStatusSwitch(fixture);

        assertNull(this.findResult(
                fixture.context, OTHER_MATERIAL_CODE, TrialStatusEnum.TRIAL.getCode()));
        assertTrue(fixture.context.getNewSpecSkuList().contains(otherTrialSku));
        assertEquals(16, this.resolveShiftQty(fixture.carrierResult, 2));
    }

    /**
     * 验证前一个特殊状态占满窗口后，后续状态不会越界或回到S4.5重新选机，
     * 两个状态的剩余量分别记录为锁定原机台的跨窗口未排结果。
     */
    @Test
    public void shouldKeepLaterSpecialStatusLockedWhenWindowIsExhausted() {
        TestFixture fixture = this.buildFixture(false);
        SkuScheduleDTO trialSku = this.buildSpecialSku(
                MATERIAL_CODE, TrialStatusEnum.TRIAL.getCode(), 109, this.scheduleDate);
        SkuScheduleDTO massTrialSku = this.buildSpecialSku(
                MATERIAL_CODE, TrialStatusEnum.MASS_TRIAL.getCode(), 16, this.scheduleDate);
        fixture.context.getNewSpecSkuList().add(trialSku);
        fixture.context.getNewSpecSkuList().add(massTrialSku);

        this.invokeSameMaterialStatusSwitch(fixture);

        LhScheduleResult trialResult = this.findResult(
                fixture.context, MATERIAL_CODE, TrialStatusEnum.TRIAL.getCode());
        assertNotNull(trialResult);
        assertEquals(108, ShiftFieldUtil.resolveScheduledQty(trialResult));
        assertNull(this.findResult(
                fixture.context, MATERIAL_CODE, TrialStatusEnum.MASS_TRIAL.getCode()));
        assertFalse(fixture.context.getNewSpecSkuList().contains(trialSku));
        assertFalse(fixture.context.getNewSpecSkuList().contains(massTrialSku));
        LhUnscheduledResult trialUnscheduled = this.findUnscheduledResult(
                fixture.context, TrialStatusEnum.TRIAL.getCode());
        LhUnscheduledResult massTrialUnscheduled = this.findUnscheduledResult(
                fixture.context, TrialStatusEnum.MASS_TRIAL.getCode());
        assertNotNull(trialUnscheduled);
        assertNotNull(massTrialUnscheduled);
        assertEquals(1, trialUnscheduled.getUnscheduledQty());
        assertEquals(16, massTrialUnscheduled.getUnscheduledQty());
        assertEquals("同物料多状态续作跨窗口延续，锁定原机台",
                massTrialUnscheduled.getUnscheduledReason());
    }

    /**
     * 验证同一产品状态存在多个SKU时按原排序依次排产，组级账本不会被后一个SKU覆盖。
     */
    @Test
    public void shouldScheduleMultipleSkusInSameStatusByExistingOrder() {
        TestFixture fixture = this.buildFixture(false);
        SkuScheduleDTO firstTrialSku = this.buildSpecialSku(
                MATERIAL_CODE, TrialStatusEnum.TRIAL.getCode(), 10, this.scheduleDate);
        SkuScheduleDTO secondTrialSku = this.buildSpecialSku(
                MATERIAL_CODE, TrialStatusEnum.TRIAL.getCode(), 6, this.scheduleDate);
        fixture.context.getNewSpecSkuList().add(firstTrialSku);
        fixture.context.getNewSpecSkuList().add(secondTrialSku);

        this.invokeSameMaterialStatusSwitch(fixture);

        List<LhScheduleResult> trialResults = this.findResults(
                fixture.context, MATERIAL_CODE, TrialStatusEnum.TRIAL.getCode());
        assertEquals(2, trialResults.size());
        assertEquals(10, ShiftFieldUtil.resolveScheduledQty(trialResults.get(0)));
        assertEquals(6, ShiftFieldUtil.resolveScheduledQty(trialResults.get(1)));
        assertEquals(16, trialResults.stream()
                .mapToInt(ShiftFieldUtil::resolveScheduledQty).sum());
        assertEquals(16, fixture.context.getSkuProductionRemainingQtyMap().get(
                MonthPlanDateResolver.buildMaterialStatusKey(
                        MATERIAL_CODE, TrialStatusEnum.TRIAL.getCode())));
    }

    /**
     * 验证非模数倍尾量必须按真实剩余量收尾，不能整班跳过形成永久未排。
     */
    @Test
    public void shouldScheduleExactNonMouldMultipleTail() {
        TestFixture fixture = this.buildFixture(false);
        fixture.carrierResult.setMouldQty(2);
        SkuScheduleDTO trialSku = this.buildSpecialSku(
                MATERIAL_CODE, TrialStatusEnum.TRIAL.getCode(), 13, this.scheduleDate);
        trialSku.setMouldQty(2);
        fixture.context.getNewSpecSkuList().add(trialSku);

        this.invokeSameMaterialStatusSwitch(fixture);

        LhScheduleResult trialResult = this.findResult(
                fixture.context, MATERIAL_CODE, TrialStatusEnum.TRIAL.getCode());
        assertNotNull(trialResult);
        assertEquals(12, this.resolveShiftQty(trialResult, 2));
        assertEquals(1, this.resolveShiftQty(trialResult, 3));
        assertEquals(13, ShiftFieldUtil.resolveScheduledQty(trialResult));
        assertNull(this.findUnscheduledResult(
                fixture.context, TrialStatusEnum.TRIAL.getCode()));
    }

    /**
     * 验证普通历史X/T结果不属于专用状态链，首次切换仍必须保留正规4条。
     */
    @Test
    public void shouldNotSkipFirstFourForUnmarkedHistoricalSpecialResult() {
        TestFixture fixture = this.buildFixture(false);
        fixture.context.getPreviousScheduleResultList().add(
                this.buildHistoricalSpecialResult(false));
        SkuScheduleDTO trialSku = this.buildSpecialSku(
                MATERIAL_CODE, TrialStatusEnum.TRIAL.getCode(), 12, this.scheduleDate);
        fixture.context.getNewSpecSkuList().add(trialSku);

        this.invokeSameMaterialStatusSwitch(fixture);

        assertEquals(4, this.resolveShiftQty(fixture.carrierResult, 2));
        assertEquals(12, this.resolveShiftQty(this.findResult(
                fixture.context, MATERIAL_CODE, TrialStatusEnum.TRIAL.getCode()), 2));
    }

    /**
     * 验证带专用标记的同状态链跨窗口延续时从首班无缝续排，且不重复执行正规4条。
     */
    @Test
    public void shouldContinueMarkedHistoricalChainWithoutReservingFourAgain() {
        TestFixture fixture = this.buildFixture(false);
        fixture.context.getPreviousScheduleResultList().add(
                this.buildHistoricalSpecialResult(true));
        SkuScheduleDTO trialSku = this.buildSpecialSku(
                MATERIAL_CODE, TrialStatusEnum.TRIAL.getCode(), 16, this.scheduleDate);
        fixture.context.getNewSpecSkuList().add(trialSku);

        this.invokeSameMaterialStatusSwitch(fixture);

        LhScheduleResult trialResult = this.findResult(
                fixture.context, MATERIAL_CODE, TrialStatusEnum.TRIAL.getCode());
        assertEquals(0, this.resolveShiftQty(fixture.carrierResult, 1));
        assertEquals(16, this.resolveShiftQty(trialResult, 1));
        assertEquals(16, this.resolveShiftQty(fixture.carrierResult, 2));
    }

    /**
     * 验证多台正规续作机台并存时，跨窗口恢复必须优先使用已持久化还原出的原承接机台。
     */
    @Test
    public void shouldPreferLockedCarrierMachineAcrossRollingWindow() {
        TestFixture fixture = this.buildFixture(true);
        SkuScheduleDTO trialSku = this.buildSpecialSku(
                MATERIAL_CODE, TrialStatusEnum.TRIAL.getCode(), 12, this.scheduleDate);
        trialSku.setPreferredContinuousMachineCode(MACHINE_CODE_1);
        fixture.context.getNewSpecSkuList().add(trialSku);

        this.invokeSameMaterialStatusSwitch(fixture);

        LhScheduleResult trialResult = this.findResult(
                fixture.context, MATERIAL_CODE, TrialStatusEnum.TRIAL.getCode());
        assertNotNull(trialResult);
        assertEquals(MACHINE_CODE_1, trialResult.getLhMachineCode());
        assertEquals(4, this.resolveShiftQty(fixture.otherFormalResult, 2));
        assertEquals(16, this.resolveShiftQty(fixture.carrierResult, 2));
    }

    /**
     * 验证多机台普通降模已将某机台后续班次清零时，专用链优先选择仍有正规恢复班次的机台。
     */
    @Test
    public void shouldSelectCarrierWithFormalRecoveryTimeline() {
        TestFixture fixture = this.buildFixture(true);
        for (int shiftIndex = 3;
             shiftIndex <= LhScheduleConstant.MAX_SHIFT_SLOT_COUNT;
             shiftIndex++) {
            ShiftFieldUtil.setShiftPlanQty(fixture.carrierResult, shiftIndex, 0, null, null);
        }
        SkuScheduleDTO trialSku = this.buildSpecialSku(
                MATERIAL_CODE, TrialStatusEnum.TRIAL.getCode(), 4, this.scheduleDate);
        fixture.context.getNewSpecSkuList().add(trialSku);

        this.invokeSameMaterialStatusSwitch(fixture);

        LhScheduleResult trialResult = this.findResult(
                fixture.context, MATERIAL_CODE, TrialStatusEnum.TRIAL.getCode());
        assertNotNull(trialResult);
        assertEquals(MACHINE_CODE_1, trialResult.getLhMachineCode());
        assertEquals(4, this.resolveShiftQty(fixture.otherFormalResult, 2));
        assertEquals(16, this.resolveShiftQty(fixture.otherFormalResult, 3));
        assertEquals(16, this.resolveShiftQty(fixture.carrierResult, 2));
    }

    /**
     * 验证同物料多机台的正规SKU副本共用组级余量，锁机跨窗口未排量只写入一次。
     */
    @Test
    public void shouldAppendLockedFormalUnscheduledQtyOnceForMultipleMachineCopies() {
        TestFixture fixture = this.buildFixture(true);
        SkuScheduleDTO firstFormalSku = fixture.context.getScheduleResultSourceSkuMap()
                .get(fixture.otherFormalResult);
        firstFormalSku.setDailyPlanQuotaMap(this.buildFormalDailyQuotaMap(20));
        SkuScheduleDTO secondFormalSku = this.buildFormalSku();
        secondFormalSku.setContinuousMachineCode(MACHINE_CODE_2);
        secondFormalSku.setDailyPlanQuotaMap(this.buildFormalDailyQuotaMap(20));
        fixture.context.getContinuousSkuList().add(secondFormalSku);
        fixture.context.getScheduleResultSourceSkuMap().put(
                fixture.carrierResult, secondFormalSku);
        Set<String> lockedFormalSkuKeySet = new LinkedHashSet<String>(1);
        lockedFormalSkuKeySet.add(MonthPlanDateResolver.buildMaterialStatusKey(
                MATERIAL_CODE, TrialStatusEnum.FORMAL.getCode()));

        ReflectionTestUtils.invokeMethod(
                this.strategy,
                "appendContinuousCompensationSkuList",
                fixture.context,
                lockedFormalSkuKeySet);

        LhUnscheduledResult unscheduledResult = this.findUnscheduledResult(
                fixture.context, TrialStatusEnum.FORMAL.getCode());
        assertNotNull(unscheduledResult);
        assertEquals(20, unscheduledResult.getUnscheduledQty());
        assertEquals("同物料多状态续作跨窗口延续，锁定原机台",
                unscheduledResult.getUnscheduledReason());
    }

    /**
     * 验证专用状态链复用胎胚硬目标账本，不能在胎胚额度之外新增X/T结果。
     */
    @Test
    public void shouldCapSpecialChainByEmbryoStockLedger() {
        TestFixture fixture = this.buildFixture(false);
        SkuScheduleDTO trialSku = this.buildSpecialSku(
                MATERIAL_CODE, TrialStatusEnum.TRIAL.getCode(), 12, this.scheduleDate);
        trialSku.setEmbryoCode("E-TEST");
        fixture.context.getNewSpecSkuList().add(trialSku);
        String skuKey = MonthPlanDateResolver.buildMaterialStatusKey(
                MATERIAL_CODE, TrialStatusEnum.TRIAL.getCode());
        fixture.context.getEmbryoStockHardTargetMaterialSet().add(skuKey);
        fixture.context.getEmbryoStockSkuQuotaMap().put(skuKey, 8);
        EmbryoStockConsumeLedger ledger = new EmbryoStockConsumeLedger();
        ledger.setEmbryoCode("E-TEST");
        ledger.setScheduleDate(this.scheduleDate);
        ledger.setOriginalStockQty(8);
        ledger.setTargetQty(8);
        ledger.setConsumedQty(0);
        ledger.setRemainQty(8);
        fixture.context.getEmbryoStockConsumeLedgerMap().put(
                "E-TEST_" + this.scheduleDate, ledger);

        this.invokeSameMaterialStatusSwitch(fixture);

        LhScheduleResult trialResult = this.findResult(
                fixture.context, MATERIAL_CODE, TrialStatusEnum.TRIAL.getCode());
        assertNotNull(trialResult);
        assertEquals(8, ShiftFieldUtil.resolveScheduledQty(trialResult));
        assertEquals(4, this.findUnscheduledResult(
                fixture.context, TrialStatusEnum.TRIAL.getCode()).getUnscheduledQty());
    }

    /**
     * 验证最终账本同步按专用时间轴结果一次扣减，不再回裁已经分配的X/T数量。
     */
    @Test
    public void shouldSyncFinalLedgerOnceWithoutChangingSpecialTimeline() {
        TestFixture fixture = this.buildFixture(false);
        SkuScheduleDTO trialSku = this.buildSpecialSku(
                MATERIAL_CODE, TrialStatusEnum.TRIAL.getCode(), 12, this.scheduleDate);
        fixture.context.getNewSpecSkuList().add(trialSku);
        this.invokeSameMaterialStatusSwitch(fixture);
        LhScheduleResult trialResult = this.findResult(
                fixture.context, MATERIAL_CODE, TrialStatusEnum.TRIAL.getCode());

        ReflectionTestUtils.invokeMethod(
                this.strategy, "syncContinuousDailyPlanQuota", fixture.context, fixture.shifts);

        assertEquals(12, ShiftFieldUtil.resolveScheduledQty(trialResult));
        assertEquals(0, fixture.context.getSkuProductionRemainingQtyMap().get(
                MonthPlanDateResolver.buildMaterialStatusKey(
                        MATERIAL_CODE, TrialStatusEnum.TRIAL.getCode())));
        assertEquals(0, trialSku.getDailyPlanQuotaMap().get(this.scheduleDate).getRemainingQty());
        assertTrue(fixture.context.isContinuousDailyQuotaSynced());
    }

    /**
     * 验证S4.4完整降模收口链执行后，专用时间轴仍保持正规4条、X/T剩余产能和最终账本一致。
     */
    @Test
    public void shouldKeepSpecialTimelineThroughFullReduceMouldFlow() {
        TestFixture fixture = this.buildFixture(false);
        SkuScheduleDTO trialSku = this.buildSpecialSku(
                MATERIAL_CODE, TrialStatusEnum.TRIAL.getCode(), 12, this.scheduleDate);
        fixture.context.getNewSpecSkuList().add(trialSku);

        this.strategy.scheduleReduceMould(fixture.context);

        LhScheduleResult trialResult = this.findResult(
                fixture.context, MATERIAL_CODE, TrialStatusEnum.TRIAL.getCode());
        assertNotNull(trialResult);
        assertEquals(4, this.resolveShiftQty(fixture.carrierResult, 2));
        assertEquals(12, this.resolveShiftQty(trialResult, 2));
        assertEquals(0, fixture.context.getSkuProductionRemainingQtyMap().get(
                MonthPlanDateResolver.buildMaterialStatusKey(
                        MATERIAL_CODE, TrialStatusEnum.TRIAL.getCode())));
        assertTrue(fixture.context.isContinuousDailyQuotaSynced());
        assertFalse(fixture.context.getReleasedContinuousMachineCodeSet()
                .contains(MACHINE_CODE_1));
    }

    /**
     * 调用专用分配步骤，并保留保护集合供结果断言。
     *
     * @param fixture 测试夹具
     */
    private void invokeSameMaterialStatusSwitch(TestFixture fixture) {
        Set<String> managedFormalSkuKeySet = new LinkedHashSet<String>(2);
        ReflectionTestUtils.invokeMethod(
                this.strategy,
                "applySameMaterialMultiStatusContinuationSwitch",
                fixture.context,
                fixture.shifts,
                managedFormalSkuKeySet);
        fixture.managedFormalSkuKeySet = managedFormalSkuKeySet;
    }

    /**
     * 构造包含一台或两台正规续作机台的测试上下文。
     *
     * @param twoFormalMachines true-构造两台正规续作机台
     * @return 测试夹具
     */
    private TestFixture buildFixture(boolean twoFormalMachines) {
        TestFixture fixture = new TestFixture();
        fixture.context = new LhScheduleContext();
        fixture.context.setFactoryCode("116");
        fixture.context.setBatchNo("LHPC20260716001");
        fixture.context.setScheduleDate(this.toDate(this.scheduleDate));
        fixture.context.setScheduleTargetDate(this.toDate(this.scheduleDate.plusDays(1)));
        fixture.context.setWindowEndDate(this.toDate(this.scheduleDate.plusDays(2)));
        fixture.shifts = this.buildEightShifts();
        fixture.context.setScheduleWindowShifts(fixture.shifts);

        SkuScheduleDTO formalSku = this.buildFormalSku();
        fixture.context.getContinuousSkuList().add(formalSku);
        MachineScheduleDTO machine1 = this.buildMachine(MACHINE_CODE_1);
        fixture.context.getMachineScheduleMap().put(MACHINE_CODE_1, machine1);
        LhScheduleResult formalResult1 = this.buildFormalResult(
                fixture.context, machine1, formalSku, fixture.shifts);
        fixture.context.getScheduleResultList().add(formalResult1);
        fixture.context.getScheduleResultSourceSkuMap().put(formalResult1, formalSku);
        fixture.context.getMachineAssignmentMap().put(
                MACHINE_CODE_1, new ArrayList<LhScheduleResult>(
                        Collections.singletonList(formalResult1)));

        if (twoFormalMachines) {
            MachineScheduleDTO machine2 = this.buildMachine(MACHINE_CODE_2);
            fixture.context.getMachineScheduleMap().put(MACHINE_CODE_2, machine2);
            LhScheduleResult formalResult2 = this.buildFormalResult(
                    fixture.context, machine2, formalSku, fixture.shifts);
            fixture.context.getScheduleResultList().add(formalResult2);
            fixture.context.getScheduleResultSourceSkuMap().put(formalResult2, formalSku);
            fixture.context.getMachineAssignmentMap().put(
                    MACHINE_CODE_2, new ArrayList<LhScheduleResult>(
                            Collections.singletonList(formalResult2)));
            // 下机比较器在其他条件相同时按机台编码降序，LH02应成为临时承接机台。
            fixture.carrierResult = formalResult2;
            fixture.otherFormalResult = formalResult1;
        } else {
            fixture.carrierResult = formalResult1;
        }
        return fixture;
    }

    /**
     * 构造正规来源SKU。
     *
     * @return 正规SKU
     */
    private SkuScheduleDTO buildFormalSku() {
        SkuScheduleDTO sku = new SkuScheduleDTO();
        sku.setMaterialCode(MATERIAL_CODE);
        sku.setMaterialDesc("测试正规SKU");
        sku.setProductStatus(TrialStatusEnum.FORMAL.getCode());
        sku.setContinuousMachineCode(MACHINE_CODE_1);
        sku.setSurplusQty(1000);
        sku.setMonthPlanQty(1000);
        sku.setPendingQty(1000);
        sku.setTargetScheduleQty(1000);
        sku.setShiftCapacity(SHIFT_CAPACITY);
        sku.setDailyCapacity(SHIFT_CAPACITY * 3);
        sku.setLhTimeSeconds(LH_TIME_SECONDS);
        sku.setMouldQty(1);
        sku.setScheduleType("01");
        return sku;
    }

    /**
     * 构造X/T来源SKU及其首次正计划日账本。
     *
     * @param materialCode 物料编码
     * @param productStatus 产品状态
     * @param surplusQty 实际余量
     * @param firstPlanDate 首次正计划日
     * @return X/T SKU
     */
    private SkuScheduleDTO buildSpecialSku(
            String materialCode,
            String productStatus,
            int surplusQty,
            LocalDate firstPlanDate) {
        SkuScheduleDTO sku = new SkuScheduleDTO();
        sku.setMaterialCode(materialCode);
        sku.setMaterialDesc("测试特殊状态SKU");
        sku.setProductStatus(productStatus);
        sku.setTrial(true);
        sku.setSurplusQty(surplusQty);
        sku.setMonthPlanQty(surplusQty);
        sku.setPendingQty(surplusQty);
        sku.setTargetScheduleQty(surplusQty);
        sku.setShiftCapacity(SHIFT_CAPACITY);
        sku.setDailyCapacity(SHIFT_CAPACITY * 3);
        sku.setLhTimeSeconds(LH_TIME_SECONDS);
        sku.setMouldQty(1);
        sku.setScheduleType("02");
        Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap =
                new LinkedHashMap<LocalDate, SkuDailyPlanQuotaDTO>(3);
        for (int dayOffset = 0; dayOffset < 3; dayOffset++) {
            LocalDate productionDate = this.scheduleDate.plusDays(dayOffset);
            SkuDailyPlanQuotaDTO quota = new SkuDailyPlanQuotaDTO();
            quota.setMaterialCode(materialCode);
            quota.setProductionDate(productionDate);
            int dayPlanQty = productionDate.equals(firstPlanDate) ? surplusQty : 0;
            quota.setDayPlanQty(dayPlanQty);
            quota.setRemainingQty(dayPlanQty);
            quotaMap.put(productionDate, quota);
        }
        sku.setDailyPlanQuotaMap(quotaMap);
        return sku;
    }

    /**
     * 构造正规SKU的组级日计划剩余账本。
     *
     * @param remainingQty 剩余量
     * @return 日计划账本
     */
    private Map<LocalDate, SkuDailyPlanQuotaDTO> buildFormalDailyQuotaMap(int remainingQty) {
        Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap =
                new LinkedHashMap<LocalDate, SkuDailyPlanQuotaDTO>(1);
        SkuDailyPlanQuotaDTO quota = new SkuDailyPlanQuotaDTO();
        quota.setMaterialCode(MATERIAL_CODE);
        quota.setProductionDate(this.scheduleDate);
        quota.setDayPlanQty(remainingQty);
        quota.setRemainingQty(remainingQty);
        quotaMap.put(this.scheduleDate, quota);
        return quotaMap;
    }

    /**
     * 构造运行态机台。
     *
     * @param machineCode 机台编码
     * @return 机台
     */
    private MachineScheduleDTO buildMachine(String machineCode) {
        MachineScheduleDTO machine = new MachineScheduleDTO();
        machine.setMachineCode(machineCode);
        machine.setMachineName("测试机台" + machineCode);
        machine.setCurrentMaterialCode(MATERIAL_CODE);
        machine.setCurrentMaterialDesc("测试正规SKU");
        machine.setMaxMoldNum(1);
        return machine;
    }

    /**
     * 构造8个班次均排满16条的正规续作结果。
     *
     * @param context 排程上下文
     * @param machine 机台
     * @param formalSku 正规SKU
     * @param shifts 8班次
     * @return 正规续作结果
     */
    private LhScheduleResult buildFormalResult(
            LhScheduleContext context,
            MachineScheduleDTO machine,
            SkuScheduleDTO formalSku,
            List<LhShiftConfigVO> shifts) {
        LhScheduleResult result = new LhScheduleResult();
        result.setFactoryCode(context.getFactoryCode());
        result.setBatchNo(context.getBatchNo());
        result.setScheduleDate(context.getScheduleTargetDate());
        result.setRealScheduleDate(context.getScheduleDate());
        result.setLhMachineCode(machine.getMachineCode());
        result.setLhMachineName(machine.getMachineName());
        result.setMaterialCode(formalSku.getMaterialCode());
        result.setMaterialDesc(formalSku.getMaterialDesc());
        result.setProductStatus(formalSku.getProductStatus());
        result.setScheduleType("01");
        result.setIsChangeMould("0");
        result.setIsTypeBlock("0");
        result.setIsEnd("0");
        result.setMouldQty(1);
        result.setMouldCode("M001");
        result.setLhTime(LH_TIME_SECONDS);
        result.setSingleMouldShiftQty(SHIFT_CAPACITY);
        for (LhShiftConfigVO shift : shifts) {
            ShiftFieldUtil.setShiftPlanQty(
                    result, shift.getShiftIndex(), SHIFT_CAPACITY,
                    shift.getShiftStartDateTime(), shift.getShiftEndDateTime());
        }
        ShiftFieldUtil.syncDailyPlanQty(result);
        result.setSpecEndTime(shifts.get(shifts.size() - 1).getShiftEndDateTime());
        result.setTdaySpecEndTime(result.getSpecEndTime());
        return result;
    }

    /**
     * 构造历史X/T结果，用于区分普通续作与专用状态链标记。
     *
     * @param marked true-写入专用状态链标记
     * @return 历史X/T结果
     */
    private LhScheduleResult buildHistoricalSpecialResult(boolean marked) {
        LhScheduleResult result = new LhScheduleResult();
        result.setLhMachineCode(MACHINE_CODE_1);
        result.setMaterialCode(MATERIAL_CODE);
        result.setProductStatus(TrialStatusEnum.TRIAL.getCode());
        result.setScheduleType("01");
        result.setIsChangeMould("0");
        result.setIsTypeBlock("0");
        result.setLhTime(LH_TIME_SECONDS);
        result.setMouldQty(1);
        result.setSingleMouldShiftQty(SHIFT_CAPACITY);
        ShiftFieldUtil.setShiftPlanQty(
                result, 1, 4,
                this.toDate(this.scheduleDate.minusDays(1)),
                this.toDate(this.scheduleDate));
        if (marked) {
            ShiftFieldUtil.appendShiftAnalysis(
                    result, 1,
                    LhScheduleConstant.SAME_MATERIAL_STATUS_CONTINUATION_ANALYSIS);
        }
        ShiftFieldUtil.syncDailyPlanQty(result);
        return result;
    }

    /**
     * 构造T日至T+2共8个业务班次。
     *
     * @return 班次列表
     */
    private List<LhShiftConfigVO> buildEightShifts() {
        List<LhShiftConfigVO> shifts = new ArrayList<LhShiftConfigVO>(8);
        shifts.add(this.buildShift(1, 0, ShiftEnum.MORNING_SHIFT));
        shifts.add(this.buildShift(2, 0, ShiftEnum.AFTERNOON_SHIFT));
        shifts.add(this.buildShift(3, 1, ShiftEnum.NIGHT_SHIFT));
        shifts.add(this.buildShift(4, 1, ShiftEnum.MORNING_SHIFT));
        shifts.add(this.buildShift(5, 1, ShiftEnum.AFTERNOON_SHIFT));
        shifts.add(this.buildShift(6, 2, ShiftEnum.NIGHT_SHIFT));
        shifts.add(this.buildShift(7, 2, ShiftEnum.MORNING_SHIFT));
        shifts.add(this.buildShift(8, 2, ShiftEnum.AFTERNOON_SHIFT));
        return shifts;
    }

    /**
     * 构造单个班次。
     *
     * @param shiftIndex 班次索引
     * @param dateOffset 相对T日偏移
     * @param shiftEnum 班别
     * @return 班次
     */
    private LhShiftConfigVO buildShift(
            int shiftIndex,
            int dateOffset,
            ShiftEnum shiftEnum) {
        LhShiftConfigVO shift = new LhShiftConfigVO();
        shift.setScheduleBaseDate(this.toDate(this.scheduleDate));
        shift.setShiftIndex(shiftIndex);
        shift.setDateOffset(dateOffset);
        shift.setShiftType(shiftEnum.getCode());
        shift.setStartTime(shiftEnum.getStartTime());
        shift.setEndTime(shiftEnum.getEndTime());
        shift.setShiftDuration(8);
        return shift;
    }

    /**
     * 查找指定物料和产品状态结果。
     *
     * @param context 排程上下文
     * @param materialCode 物料编码
     * @param productStatus 产品状态
     * @return 结果；未找到返回null
     */
    private LhScheduleResult findResult(
            LhScheduleContext context,
            String materialCode,
            String productStatus) {
        return context.getScheduleResultList().stream()
                .filter(result -> materialCode.equals(result.getMaterialCode()))
                .filter(result -> productStatus.equals(result.getProductStatus()))
                .findFirst().orElse(null);
    }

    /**
     * 查找指定物料和产品状态的全部结果，并保持生成顺序。
     *
     * @param context 排程上下文
     * @param materialCode 物料编码
     * @param productStatus 产品状态
     * @return 匹配结果列表
     */
    private List<LhScheduleResult> findResults(
            LhScheduleContext context,
            String materialCode,
            String productStatus) {
        List<LhScheduleResult> results = new ArrayList<LhScheduleResult>(2);
        for (LhScheduleResult result : context.getScheduleResultList()) {
            if (materialCode.equals(result.getMaterialCode())
                    && productStatus.equals(result.getProductStatus())) {
                results.add(result);
            }
        }
        return results;
    }

    /**
     * 查找同物料全部X/T结果，并保持生成顺序。
     *
     * @param context 排程上下文
     * @param materialCode 物料编码
     * @return X/T结果
     */
    private List<LhScheduleResult> findSpecialResults(
            LhScheduleContext context,
            String materialCode) {
        List<LhScheduleResult> results = new ArrayList<LhScheduleResult>(2);
        for (LhScheduleResult result : context.getScheduleResultList()) {
            if (!materialCode.equals(result.getMaterialCode())) {
                continue;
            }
            if (TrialStatusEnum.TRIAL.getCode().equals(result.getProductStatus())
                    || TrialStatusEnum.MASS_TRIAL.getCode().equals(result.getProductStatus())) {
                results.add(result);
            }
        }
        return results;
    }

    /**
     * 查找指定产品状态的未排结果。
     *
     * @param context 排程上下文
     * @param productStatus 产品状态
     * @return 未排结果；未找到返回null
     */
    private LhUnscheduledResult findUnscheduledResult(
            LhScheduleContext context,
            String productStatus) {
        return context.getUnscheduledResultList().stream()
                .filter(result -> MATERIAL_CODE.equals(result.getMaterialCode()))
                .filter(result -> productStatus.equals(result.getProductStatus()))
                .findFirst().orElse(null);
    }

    /**
     * 获取结果指定班次计划量。
     *
     * @param result 排程结果
     * @param shiftIndex 班次索引
     * @return 计划量
     */
    private int resolveShiftQty(LhScheduleResult result, int shiftIndex) {
        Integer planQty = ShiftFieldUtil.getShiftPlanQty(result, shiftIndex);
        return planQty != null ? planQty : 0;
    }

    /**
     * 断言X/T结果不执行换模或换活字块。
     *
     * @param result X/T结果
     */
    private void assertNoMouldOrTypeBlockChange(LhScheduleResult result) {
        assertEquals("01", result.getScheduleType());
        assertEquals("0", result.getIsChangeMould());
        assertEquals("0", result.getIsTypeBlock());
        assertNull(result.getMouldChangeStartTime());
    }

    /**
     * LocalDate转换为本地时区Date。
     *
     * @param localDate 日期
     * @return Date
     */
    private Date toDate(LocalDate localDate) {
        return Date.from(localDate.atStartOfDay(ZoneId.systemDefault()).toInstant());
    }

    /**
     * 聚合单个测试场景的上下文、班次和承接结果。
     */
    private static class TestFixture {

        /** 排程上下文 */
        private LhScheduleContext context;
        /** 排程班次 */
        private List<LhShiftConfigVO> shifts;
        /** 被X/T临时占用的正规结果 */
        private LhScheduleResult carrierResult;
        /** 多机台场景中保持不变的另一台正规结果 */
        private LhScheduleResult otherFormalResult;
        /** 专用时间轴接管的正规SKU复合键 */
        private Set<String> managedFormalSkuKeySet;
    }
}
