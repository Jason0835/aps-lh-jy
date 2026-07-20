package com.zlt.aps.lh.service.impl;

import com.zlt.aps.lh.api.constant.LhScheduleParamConstant;
import com.zlt.aps.lh.api.domain.dto.CleaningScheduleDateFillItem;
import com.zlt.aps.lh.api.domain.dto.MachineCleaningWindowDTO;
import com.zlt.aps.lh.api.domain.dto.MachineMaintenanceWindowDTO;
import com.zlt.aps.lh.api.domain.dto.MachineScheduleDTO;
import com.zlt.aps.lh.api.enums.CleaningTypeEnum;
import com.zlt.aps.lh.api.enums.MachineStopTypeEnum;
import com.zlt.aps.lh.context.LhScheduleConfig;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.mapper.MdmDevicePlanShutMapper;
import com.zlt.aps.lh.util.ShiftCapacityResolverUtil;
import com.zlt.aps.mdm.api.domain.entity.MdmDevicePlanShut;
import com.zlt.aps.mp.api.domain.entity.FactoryMonthPlanProductionFinalResult;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * 设备停机计划口径下的清洗排程测试。
 *
 * <p>本测试只验证最终规则：干冰清洗、喷砂清洗从设备停机计划进入排程，不再依赖模具清洗表。</p>
 *
 * @author APS
 */
public class LhCleaningScheduleServiceDeviceStopTest {

    private static final ZoneId ZONE_ID = ZoneId.of("Asia/Shanghai");

    /**
     * 用例说明：设备停机表中的干冰/喷砂记录应转换为清洗窗口，旧模具清洗计划列表为空也要生效。
     * <p>设备停机计划的计划开始/结束时间只用于候选来源，实际清洗时间由排程窗口班次重新安排。</p>
     */
    @Test
    public void shouldBuildCleaningWindowsFromDeviceStopPlans() {
        LhScheduleContext context = buildContext(toDate(2026, 4, 20, 0, 0, 0));
        context.setDevicePlanShutList(Arrays.asList(
                buildDeviceStop("K1301", MachineStopTypeEnum.DRY_ICE_CLEANING.getCode(),
                        toDate(2026, 4, 20, 8, 0, 0), toDate(2026, 4, 20, 11, 0, 0)),
                buildDeviceStop("K1302", MachineStopTypeEnum.SANDBLASTING_CLEANING.getCode(),
                        toDate(2026, 4, 20, 14, 0, 0), toDate(2026, 4, 20, 16, 0, 0))));

        Map<String, List<MachineCleaningWindowDTO>> windowMap =
                new LhCleaningScheduleService().buildScheduledCleaningWindowMap(context);

        Assertions.assertTrue(context.getCleaningPlanList().isEmpty(), "本用例不依赖旧模具清洗计划表");
        Assertions.assertEquals(CleaningTypeEnum.DRY_ICE.getCode(), windowMap.get("K1301").get(0).getCleanType());
        Assertions.assertEquals(CleaningTypeEnum.SAND_BLAST.getCode(), windowMap.get("K1302").get(0).getCleanType());
        Assertions.assertEquals(toDate(2026, 4, 20, 6, 0, 0), windowMap.get("K1301").get(0).getCleanStartTime());
        Assertions.assertEquals(toDate(2026, 4, 20, 9, 0, 0), windowMap.get("K1301").get(0).getCleanEndTime());
        Assertions.assertEquals(toDate(2026, 4, 20, 14, 0, 0), windowMap.get("K1302").get(0).getCleanStartTime());
        Assertions.assertEquals(toDate(2026, 4, 21, 2, 0, 0), windowMap.get("K1302").get(0).getCleanEndTime());
        Assertions.assertEquals(toDate(2026, 4, 20, 14, 0, 0), windowMap.get("K1302").get(0).getSourcePlanStartTime(),
                "来源计划开始时间只用于换模重叠判定，不作为实际清洗开始时间");
        Assertions.assertEquals(toDate(2026, 4, 20, 16, 0, 0), windowMap.get("K1302").get(0).getSourcePlanEndTime(),
                "来源计划结束时间只用于换模重叠判定，不作为实际清洗结束时间");
    }

    /**
     * 用例说明：喷砂清洗计划不在中班时，实际清洗仍按本次窗口内可用中班安排，耗时复用喷砂清洗参数。
     */
    @Test
    public void shouldDelaySandBlastToNextAvailableAfternoonShift() {
        LhScheduleContext context = buildContext(toDate(2026, 4, 20, 0, 0, 0));
        context.setDevicePlanShutList(Collections.singletonList(
                buildDeviceStop("K1301", MachineStopTypeEnum.SANDBLASTING_CLEANING.getCode(),
                        toDate(2026, 4, 20, 9, 0, 0), toDate(2026, 4, 20, 11, 0, 0))));

        MachineCleaningWindowDTO window = new LhCleaningScheduleService()
                .buildScheduledCleaningWindowMap(context).get("K1301").get(0);

        Assertions.assertEquals(toDate(2026, 4, 20, 14, 0, 0), window.getCleanStartTime());
        Assertions.assertEquals(toDate(2026, 4, 21, 2, 0, 0), window.getCleanEndTime());
    }

    /**
     * 用例说明：喷砂实际安排日若命中周日或喷砂机维保日，应继续向后寻找下一天中班。
     * <p>计划开始日期设为顺延后的 04-21（当天安排），确保最晚安排日期规则
     * （实际清洗日 ≤ 计划开始日）满足；顺延逻辑本身不使用计划开始时间。</p>
     */
    @Test
    public void shouldContinueDelaySandBlastWhenSundayAndMaintenanceDateMatched() {
        LhScheduleContext context = buildContext(toDate(2026, 4, 19, 0, 0, 0));
        context.getLhParamsMap().put(LhScheduleParamConstant.SAND_BLAST_MAINTENANCE_DATES, "20");
        context.setDevicePlanShutList(Collections.singletonList(
                buildDeviceStop("K1301", MachineStopTypeEnum.SANDBLASTING_CLEANING.getCode(),
                        toDate(2026, 4, 21, 9, 0, 0), toDate(2026, 4, 21, 11, 0, 0))));

        MachineCleaningWindowDTO window = new LhCleaningScheduleService()
                .buildScheduledCleaningWindowMap(context).get("K1301").get(0);

        Assertions.assertEquals(toDate(2026, 4, 21, 14, 0, 0), window.getCleanStartTime());
        Assertions.assertEquals(toDate(2026, 4, 22, 2, 0, 0), window.getCleanEndTime());
    }

    /**
     * 用例说明：清洗候选可来自 T+3 之后的设备停机计划，但实际清洗必须提前安排到本次排程窗口内。
     * <p>同一计划开始时间下按机台编码升序加载，先加载的候选优先占用早班/中班名额。</p>
     */
    @Test
    public void shouldLoadFutureCleaningCandidatesAndScheduleInsideCurrentWindowByPlanOrder() {
        LhScheduleContext context = buildContext(toDate(2026, 4, 20, 0, 0, 0));
        context.setDevicePlanShutList(Arrays.asList(
                buildDeviceStop("K1303", MachineStopTypeEnum.DRY_ICE_CLEANING.getCode(),
                        toDate(2026, 4, 25, 8, 0, 0), toDate(2026, 4, 25, 11, 0, 0)),
                buildDeviceStop("K1302", MachineStopTypeEnum.DRY_ICE_CLEANING.getCode(),
                        toDate(2026, 4, 23, 8, 0, 0), toDate(2026, 4, 23, 11, 0, 0)),
                buildDeviceStop("K1301", MachineStopTypeEnum.DRY_ICE_CLEANING.getCode(),
                        toDate(2026, 4, 23, 8, 0, 0), toDate(2026, 4, 23, 11, 0, 0))));

        Map<String, List<MachineCleaningWindowDTO>> windowMap =
                new LhCleaningScheduleService().buildScheduledCleaningWindowMap(context);

        Assertions.assertEquals(toDate(2026, 4, 20, 6, 0, 0), windowMap.get("K1301").get(0).getCleanStartTime());
        Assertions.assertEquals(toDate(2026, 4, 20, 6, 0, 0), windowMap.get("K1302").get(0).getCleanStartTime());
        Assertions.assertEquals(toDate(2026, 4, 20, 14, 0, 0), windowMap.get("K1303").get(0).getCleanStartTime());
    }

    /**
     * 用例说明：计划开始时间早于 T 日的干冰、喷砂计划不得进入本次清洗候选。
     */
    @Test
    public void shouldExcludeCleaningPlansBeforeScheduleTDay() {
        LhScheduleContext context = buildContext(toDate(2026, 7, 11, 0, 0, 0));
        context.setDevicePlanShutList(Arrays.asList(
                buildDeviceStop("K1102", MachineStopTypeEnum.DRY_ICE_CLEANING.getCode(),
                        toDate(2026, 7, 10, 0, 0, 0), toDate(2026, 7, 10, 0, 0, 0)),
                buildDeviceStop("K1706", MachineStopTypeEnum.SANDBLASTING_CLEANING.getCode(),
                        toDate(2026, 7, 10, 0, 0, 0), toDate(2026, 7, 10, 0, 0, 0)),
                buildDeviceStop("K1408", MachineStopTypeEnum.DRY_ICE_CLEANING.getCode(),
                        toDate(2026, 7, 12, 0, 0, 0), toDate(2026, 7, 12, 0, 0, 0))));

        Map<String, List<MachineCleaningWindowDTO>> windowMap =
                new LhCleaningScheduleService().buildScheduledCleaningWindowMap(context);

        Assertions.assertFalse(windowMap.containsKey("K1102"), "T-1干冰计划不得再次纳入清洗窗口");
        Assertions.assertFalse(windowMap.containsKey("K1706"), "T-1喷砂计划不得再次纳入清洗窗口");
        Assertions.assertTrue(windowMap.containsKey("K1408"), "T日及之后的清洗计划仍应正常纳入");
    }

    /**
     * 用例说明：历史清洗计划不得挤占三日窗口名额，T 日及之后排序靠前的 9 台干冰均应纳入。
     */
    @Test
    public void shouldNotLetHistoricalPlansConsumeDryIceWindowQuota() {
        LhScheduleContext context = buildContext(toDate(2026, 7, 11, 0, 0, 0));
        context.setDevicePlanShutList(Arrays.asList(
                buildDeviceStop("K1102", MachineStopTypeEnum.DRY_ICE_CLEANING.getCode(),
                        toDate(2026, 7, 10, 0, 0, 0), toDate(2026, 7, 10, 0, 0, 0)),
                buildDeviceStop("K1101", MachineStopTypeEnum.DRY_ICE_CLEANING.getCode(),
                        toDate(2026, 7, 11, 0, 0, 0), toDate(2026, 7, 11, 0, 0, 0)),
                buildDeviceStop("K1102N", MachineStopTypeEnum.DRY_ICE_CLEANING.getCode(),
                        toDate(2026, 7, 11, 0, 0, 0), toDate(2026, 7, 11, 0, 0, 0)),
                buildDeviceStop("K1104", MachineStopTypeEnum.DRY_ICE_CLEANING.getCode(),
                        toDate(2026, 7, 11, 0, 0, 0), toDate(2026, 7, 11, 0, 0, 0)),
                buildDeviceStop("K1103", MachineStopTypeEnum.DRY_ICE_CLEANING.getCode(),
                        toDate(2026, 7, 12, 0, 0, 0), toDate(2026, 7, 12, 0, 0, 0)),
                buildDeviceStop("K1408", MachineStopTypeEnum.DRY_ICE_CLEANING.getCode(),
                        toDate(2026, 7, 12, 0, 0, 0), toDate(2026, 7, 12, 0, 0, 0)),
                buildDeviceStop("K1707", MachineStopTypeEnum.DRY_ICE_CLEANING.getCode(),
                        toDate(2026, 7, 12, 0, 0, 0), toDate(2026, 7, 12, 0, 0, 0)),
                buildDeviceStop("K2010", MachineStopTypeEnum.DRY_ICE_CLEANING.getCode(),
                        toDate(2026, 7, 13, 0, 0, 0), toDate(2026, 7, 13, 0, 0, 0)),
                buildDeviceStop("K1115", MachineStopTypeEnum.DRY_ICE_CLEANING.getCode(),
                        toDate(2026, 7, 13, 0, 0, 0), toDate(2026, 7, 13, 0, 0, 0)),
                buildDeviceStop("K1712", MachineStopTypeEnum.DRY_ICE_CLEANING.getCode(),
                        toDate(2026, 7, 13, 0, 0, 0), toDate(2026, 7, 13, 0, 0, 0))));

        Map<String, List<MachineCleaningWindowDTO>> windowMap =
                new LhCleaningScheduleService().buildScheduledCleaningWindowMap(context);

        long dryIceWindowCount = windowMap.values().stream()
                .flatMap(List::stream)
                .filter(window -> CleaningTypeEnum.DRY_ICE.getCode().equals(window.getCleanType()))
                .count();
        Assertions.assertEquals(9L, dryIceWindowCount, "三日窗口最多应纳入9台干冰清洗");
        Assertions.assertFalse(windowMap.containsKey("K1102"), "历史干冰计划不得占用三日窗口名额");
        Assertions.assertTrue(windowMap.containsKey("K1408"), "K1408应进入本次干冰清洗窗口");
        Assertions.assertTrue(windowMap.containsKey("K2010"), "K2010应进入本次干冰清洗窗口");
    }

    /**
     * 用例说明：清洗加载总量按“每日上限 * 排程窗口天数”控制，未来候选超量时只保留排序靠前的数据。
     */
    @Test
    public void shouldLimitLoadedCleaningCandidatesByDailyLimitAndScheduleDays() {
        LhScheduleContext context = buildContext(toDate(2026, 4, 20, 0, 0, 0));
        context.setDevicePlanShutList(Arrays.asList(
                buildDeviceStop("D01", MachineStopTypeEnum.DRY_ICE_CLEANING.getCode(),
                        toDate(2026, 4, 23, 8, 0, 0), toDate(2026, 4, 23, 11, 0, 0)),
                buildDeviceStop("D02", MachineStopTypeEnum.DRY_ICE_CLEANING.getCode(),
                        toDate(2026, 4, 24, 8, 0, 0), toDate(2026, 4, 24, 11, 0, 0)),
                buildDeviceStop("D03", MachineStopTypeEnum.DRY_ICE_CLEANING.getCode(),
                        toDate(2026, 4, 25, 8, 0, 0), toDate(2026, 4, 25, 11, 0, 0)),
                buildDeviceStop("D04", MachineStopTypeEnum.DRY_ICE_CLEANING.getCode(),
                        toDate(2026, 4, 26, 8, 0, 0), toDate(2026, 4, 26, 11, 0, 0)),
                buildDeviceStop("D05", MachineStopTypeEnum.DRY_ICE_CLEANING.getCode(),
                        toDate(2026, 4, 27, 8, 0, 0), toDate(2026, 4, 27, 11, 0, 0)),
                buildDeviceStop("D06", MachineStopTypeEnum.DRY_ICE_CLEANING.getCode(),
                        toDate(2026, 4, 28, 8, 0, 0), toDate(2026, 4, 28, 11, 0, 0)),
                buildDeviceStop("D07", MachineStopTypeEnum.DRY_ICE_CLEANING.getCode(),
                        toDate(2026, 4, 29, 8, 0, 0), toDate(2026, 4, 29, 11, 0, 0)),
                buildDeviceStop("D08", MachineStopTypeEnum.DRY_ICE_CLEANING.getCode(),
                        toDate(2026, 4, 30, 8, 0, 0), toDate(2026, 4, 30, 11, 0, 0)),
                buildDeviceStop("D09", MachineStopTypeEnum.DRY_ICE_CLEANING.getCode(),
                        toDate(2026, 5, 1, 8, 0, 0), toDate(2026, 5, 1, 11, 0, 0)),
                buildDeviceStop("D10", MachineStopTypeEnum.DRY_ICE_CLEANING.getCode(),
                        toDate(2026, 5, 2, 8, 0, 0), toDate(2026, 5, 2, 11, 0, 0)),
                buildDeviceStop("S01", MachineStopTypeEnum.SANDBLASTING_CLEANING.getCode(),
                        toDate(2026, 4, 23, 8, 0, 0), toDate(2026, 4, 23, 18, 0, 0)),
                buildDeviceStop("S02", MachineStopTypeEnum.SANDBLASTING_CLEANING.getCode(),
                        toDate(2026, 4, 24, 8, 0, 0), toDate(2026, 4, 24, 18, 0, 0)),
                buildDeviceStop("S03", MachineStopTypeEnum.SANDBLASTING_CLEANING.getCode(),
                        toDate(2026, 4, 25, 8, 0, 0), toDate(2026, 4, 25, 18, 0, 0)),
                buildDeviceStop("S04", MachineStopTypeEnum.SANDBLASTING_CLEANING.getCode(),
                        toDate(2026, 4, 26, 8, 0, 0), toDate(2026, 4, 26, 18, 0, 0))));

        Map<String, List<MachineCleaningWindowDTO>> windowMap =
                new LhCleaningScheduleService().buildScheduledCleaningWindowMap(context);

        Assertions.assertEquals(12, windowMap.values().stream().mapToInt(List::size).sum());
        Assertions.assertFalse(windowMap.containsKey("D10"), "干冰本次最多纳入 3 台/天 * 3 天 = 9 台");
        Assertions.assertFalse(windowMap.containsKey("S04"), "喷砂本次最多纳入 1 台/天 * 3 天 = 3 台");
    }

    /**
     * 用例说明：干冰每日上限为 5 时，早班最多 3 台、中班最多 2 台，第 6 台不纳入本次排程。
     */
    @Test
    public void shouldSplitDryIceDailyLimitByCeilRuleAndSkipExceededPlans() {
        LhScheduleContext context = buildContext(toDate(2026, 4, 20, 0, 0, 0));
        context.getLhParamsMap().put(LhScheduleParamConstant.DRY_ICE_DAILY_LIMIT, "5");
        // 本用例只验证单日5台上限，避免第6台顺延到三日窗口的后续日期。
        context.getLhParamsMap().put(LhScheduleParamConstant.SCHEDULE_DAYS, "1");
        context.setScheduleConfig(new LhScheduleConfig(context.getLhParamsMap()));
        context.setDevicePlanShutList(Arrays.asList(
                buildDeviceStop("K1301", MachineStopTypeEnum.DRY_ICE_CLEANING.getCode(),
                        toDate(2026, 4, 20, 7, 30, 0), toDate(2026, 4, 20, 10, 30, 0)),
                buildDeviceStop("K1302", MachineStopTypeEnum.DRY_ICE_CLEANING.getCode(),
                        toDate(2026, 4, 20, 8, 0, 0), toDate(2026, 4, 20, 11, 0, 0)),
                buildDeviceStop("K1303", MachineStopTypeEnum.DRY_ICE_CLEANING.getCode(),
                        toDate(2026, 4, 20, 9, 0, 0), toDate(2026, 4, 20, 12, 0, 0)),
                buildDeviceStop("K1304", MachineStopTypeEnum.DRY_ICE_CLEANING.getCode(),
                        toDate(2026, 4, 20, 14, 0, 0), toDate(2026, 4, 20, 17, 0, 0)),
                buildDeviceStop("K1305", MachineStopTypeEnum.DRY_ICE_CLEANING.getCode(),
                        toDate(2026, 4, 20, 15, 0, 0), toDate(2026, 4, 20, 18, 0, 0)),
                buildDeviceStop("K1306", MachineStopTypeEnum.DRY_ICE_CLEANING.getCode(),
                        toDate(2026, 4, 20, 16, 0, 0), toDate(2026, 4, 20, 19, 0, 0))));

        Map<String, List<MachineCleaningWindowDTO>> windowMap =
                new LhCleaningScheduleService().buildScheduledCleaningWindowMap(context);

        Assertions.assertEquals(5, windowMap.values().stream().mapToInt(List::size).sum());
        Assertions.assertFalse(windowMap.containsKey("K1306"), "超过每日上限的干冰清洗本次不纳入");
    }

    /**
     * 用例说明：机台当前 SKU 从清洗时间点开始 3 天内可收尾时，清洗计划应跳过。
     */
    @Test
    public void shouldSkipCleaningWhenCurrentSkuCanEndWithinThreeDays() {
        LhScheduleContext context = buildContext(toDate(2026, 4, 20, 0, 0, 0));
        context.getMachineOnlineInfoMap().put("K1301",
                buildOnlineInfo("K1301", "MAT-ENDING"));
        context.setMonthPlanList(Collections.singletonList(
                buildMonthPlan("MAT-ENDING", 30, 10)));
        context.setDevicePlanShutList(Collections.singletonList(
                buildDeviceStop("K1301", MachineStopTypeEnum.DRY_ICE_CLEANING.getCode(),
                        toDate(2026, 4, 20, 8, 0, 0), toDate(2026, 4, 20, 11, 0, 0))));

        Map<String, List<MachineCleaningWindowDTO>> windowMap =
                new LhCleaningScheduleService().buildScheduledCleaningWindowMap(context);

        Assertions.assertTrue(windowMap.isEmpty(), "3 天内可收尾的 SKU 不安排干冰或喷砂清洗");
    }

    /**
     * 用例说明：同物料存在正规与试制月计划时，收尾判断必须按产品状态精确匹配，禁止取首条计划串状态。
     */
    @Test
    public void shouldMatchEndingMonthPlanByMaterialAndProductStatus() {
        LhScheduleContext context = buildContext(toDate(2026, 4, 20, 0, 0, 0));
        com.zlt.aps.lh.api.domain.entity.LhMachineOnlineInfo onlineInfo =
                buildOnlineInfo("K1301", "MAT-MULTI-STATUS");
        onlineInfo.setProductStatus("X");
        context.getMachineOnlineInfoMap().put("K1301", onlineInfo);
        FactoryMonthPlanProductionFinalResult formalPlan = buildMonthPlan("MAT-MULTI-STATUS", 20, 10);
        formalPlan.setProductStatus("S");
        FactoryMonthPlanProductionFinalResult trialPlan = buildMonthPlan("MAT-MULTI-STATUS", 100, 10);
        trialPlan.setProductStatus("X");
        context.setMonthPlanList(Arrays.asList(formalPlan, trialPlan));
        context.setDevicePlanShutList(Collections.singletonList(
                buildDeviceStop("K1301", MachineStopTypeEnum.DRY_ICE_CLEANING.getCode(),
                        toDate(2026, 4, 20, 8, 0, 0), toDate(2026, 4, 20, 11, 0, 0))));

        Map<String, List<MachineCleaningWindowDTO>> windowMap =
                new LhCleaningScheduleService().buildScheduledCleaningWindowMap(context);

        Assertions.assertTrue(windowMap.containsKey("K1301"),
                "试制状态剩余 10 天，不得误用正规状态 2 天余量跳过清洗");
    }

    /**
     * 用例说明：单控配对侧窗口继承同一来源计划主键，但设备停机计划只收集一条回填项。
     */
    @Test
    public void shouldShareSourcePlanIdForPairedSingleControlWindow() {
        LhScheduleContext context = buildContext(toDate(2026, 4, 20, 0, 0, 0));
        MachineScheduleDTO leftMachine = new MachineScheduleDTO();
        leftMachine.setMachineCode("K1501L");
        MachineScheduleDTO rightMachine = new MachineScheduleDTO();
        rightMachine.setMachineCode("K1501R");
        context.getMachineScheduleMap().put("K1501L", leftMachine);
        context.getMachineScheduleMap().put("K1501R", rightMachine);
        MdmDevicePlanShut plan = buildDeviceStop("K1501L",
                MachineStopTypeEnum.DRY_ICE_CLEANING.getCode(),
                toDate(2026, 4, 20, 8, 0, 0), toDate(2026, 4, 20, 11, 0, 0));
        plan.setId(5001L);
        context.setDevicePlanShutList(Collections.singletonList(plan));

        Map<String, List<MachineCleaningWindowDTO>> windowMap =
                new LhCleaningScheduleService().buildScheduledCleaningWindowMap(context);

        Assertions.assertEquals(5001L, windowMap.get("K1501L").get(0).getSourcePlanId());
        Assertions.assertEquals(5001L, windowMap.get("K1501R").get(0).getSourcePlanId());
        Assertions.assertEquals(1, context.getCleaningScheduleDateFillList().size(),
                "配对侧窗口不得额外生成设备停机计划回填项");
    }

    /**
     * 用例说明：喷砂与精度保养实际重叠时，班次产能只扣最大占用时间，不按两者耗时相加扣减。
     */
    @Test
    public void shouldDeductMaxOccupationWhenSandBlastOverlapsPrecisionMaintenance() {
        MachineCleaningWindowDTO sandBlastWindow = new MachineCleaningWindowDTO();
        sandBlastWindow.setLhCode("K1301");
        sandBlastWindow.setCleanType(CleaningTypeEnum.SAND_BLAST.getCode());
        sandBlastWindow.setCleanStartTime(toDate(2026, 4, 20, 14, 0, 0));
        sandBlastWindow.setCleanEndTime(toDate(2026, 4, 20, 16, 0, 0));
        MachineMaintenanceWindowDTO precisionWindow = new MachineMaintenanceWindowDTO();
        precisionWindow.setMachineCode("K1301");
        precisionWindow.setMaintenanceStartTime(toDate(2026, 4, 20, 14, 0, 0));
        precisionWindow.setMaintenanceEndTime(toDate(2026, 4, 20, 17, 0, 0));

        int shiftCapacity = ShiftCapacityResolverUtil.resolveShiftCapacityWithDowntime(
                Collections.emptyList(),
                Collections.singletonList(sandBlastWindow),
                Collections.singletonList(precisionWindow),
                "K1301",
                toDate(2026, 4, 20, 14, 0, 0),
                toDate(2026, 4, 20, 22, 0, 0),
                8,
                3600,
                1,
                8 * 3600L,
                0,
                3,
                0);

        Assertions.assertEquals(5, shiftCapacity, "喷砂 2 小时与精度 3 小时重叠时只扣 3 小时产能");
    }

    /**
     * 用例说明：清洗实际安排日期早于计划开始日期（提前安排）时应纳入，并收集实际清洗开始时间回填项。
     */
    @Test
    public void shouldAllowCleaningScheduledBeforePlanBeginDate() {
        LhScheduleContext context = buildContext(toDate(2026, 4, 20, 0, 0, 0));
        MdmDevicePlanShut plan = buildDeviceStop("K1301", MachineStopTypeEnum.DRY_ICE_CLEANING.getCode(),
                toDate(2026, 4, 22, 8, 0, 0), toDate(2026, 4, 22, 11, 0, 0));
        plan.setId(1001L);
        context.setDevicePlanShutList(Collections.singletonList(plan));

        Map<String, List<MachineCleaningWindowDTO>> windowMap =
                new LhCleaningScheduleService().buildScheduledCleaningWindowMap(context);

        // 提前安排：实际清洗落在 T 日（04-20），计划开始日为 04-22，应通过
        Assertions.assertEquals(toDate(2026, 4, 20, 6, 0, 0), windowMap.get("K1301").get(0).getCleanStartTime());
        List<CleaningScheduleDateFillItem> fillList = context.getCleaningScheduleDateFillList();
        Assertions.assertEquals(1, fillList.size(), "清洗成功应收集 1 条回填项");
        CleaningScheduleDateFillItem fillItem = fillList.get(0);
        Assertions.assertEquals(1001L, fillItem.getPlanId());
        Assertions.assertEquals(1001L, windowMap.get("K1301").get(0).getSourcePlanId(),
                "清洗窗口必须保留来源设备停机计划主键");
        Assertions.assertEquals(toDate(2026, 4, 20, 6, 0, 0), fillItem.getScheduleDate());
        Assertions.assertEquals(CleaningTypeEnum.DRY_ICE.getCode(), fillItem.getCleanType());
        Assertions.assertEquals("清洗成功", fillItem.getFillReason());
    }

    /**
     * 用例说明：清洗实际安排日期等于计划开始日期（当天安排）时应纳入，干冰早班、喷砂中班均允许当天。
     */
    @Test
    public void shouldAllowCleaningScheduledOnPlanBeginDate() {
        LhScheduleContext context = buildContext(toDate(2026, 4, 20, 0, 0, 0));
        MdmDevicePlanShut dryIce = buildDeviceStop("K1301", MachineStopTypeEnum.DRY_ICE_CLEANING.getCode(),
                toDate(2026, 4, 20, 8, 0, 0), toDate(2026, 4, 20, 11, 0, 0));
        dryIce.setId(1101L);
        MdmDevicePlanShut sandBlast = buildDeviceStop("K1302", MachineStopTypeEnum.SANDBLASTING_CLEANING.getCode(),
                toDate(2026, 4, 20, 14, 0, 0), toDate(2026, 4, 20, 16, 0, 0));
        sandBlast.setId(1102L);
        context.setDevicePlanShutList(Arrays.asList(dryIce, sandBlast));

        Map<String, List<MachineCleaningWindowDTO>> windowMap =
                new LhCleaningScheduleService().buildScheduledCleaningWindowMap(context);

        // 当天安排：计划开始日 04-20，实际清洗也在 04-20，应通过
        Assertions.assertEquals(toDate(2026, 4, 20, 6, 0, 0), windowMap.get("K1301").get(0).getCleanStartTime());
        Assertions.assertEquals(toDate(2026, 4, 20, 14, 0, 0), windowMap.get("K1302").get(0).getCleanStartTime());
        Assertions.assertEquals(2, context.getCleaningScheduleDateFillList().size(), "干冰+喷砂当天成功应收集 2 条回填项");
    }

    /**
     * 用例说明：清洗实际安排日期晚于计划开始日期（延后）时应跳过，且不占用每日上限名额、不收集回填项。
     */
    @Test
    public void shouldSkipCleaningWhenActualDateLaterThanPlanBeginDate() {
        LhScheduleContext context = buildContext(toDate(2026, 4, 20, 0, 0, 0));
        // 4 条干冰计划 beginDate 均为 T 日（04-20），默认每日上限 3 台，前 3 台占满 T 日，
        // 第 4 台实际清洗顺延到 T+1（04-21），已晚于计划开始日 04-20，应被最晚日期校验跳过。
        MdmDevicePlanShut p1 = buildDeviceStop("K1301", MachineStopTypeEnum.DRY_ICE_CLEANING.getCode(),
                toDate(2026, 4, 20, 8, 0, 0), toDate(2026, 4, 20, 11, 0, 0));
        p1.setId(1201L);
        MdmDevicePlanShut p2 = buildDeviceStop("K1302", MachineStopTypeEnum.DRY_ICE_CLEANING.getCode(),
                toDate(2026, 4, 20, 8, 0, 0), toDate(2026, 4, 20, 11, 0, 0));
        p2.setId(1202L);
        MdmDevicePlanShut p3 = buildDeviceStop("K1303", MachineStopTypeEnum.DRY_ICE_CLEANING.getCode(),
                toDate(2026, 4, 20, 8, 0, 0), toDate(2026, 4, 20, 11, 0, 0));
        p3.setId(1203L);
        MdmDevicePlanShut p4 = buildDeviceStop("K1304", MachineStopTypeEnum.DRY_ICE_CLEANING.getCode(),
                toDate(2026, 4, 20, 8, 0, 0), toDate(2026, 4, 20, 11, 0, 0));
        p4.setId(1204L);
        context.setDevicePlanShutList(Arrays.asList(p1, p2, p3, p4));

        Map<String, List<MachineCleaningWindowDTO>> windowMap =
                new LhCleaningScheduleService().buildScheduledCleaningWindowMap(context);

        // 前 3 台占满 T 日，第 4 台顺延到 T+1 被最晚日期校验跳过
        Assertions.assertEquals(3, windowMap.values().stream().mapToInt(List::size).sum());
        Assertions.assertFalse(windowMap.containsKey("K1304"), "实际清洗晚于计划开始日的清洗应跳过");
        List<CleaningScheduleDateFillItem> fillList = context.getCleaningScheduleDateFillList();
        Assertions.assertEquals(3, fillList.size(), "仅前 3 台成功清洗收集回填项，延后跳过的不收集");
        Assertions.assertFalse(fillList.stream().anyMatch(item -> Long.valueOf(1204L).equals(item.getPlanId())),
                "延后跳过的清洗不应出现在回填项中");
    }

    /**
     * 用例说明：因 SKU 3 天内收尾跳过清洗时，应收集收尾日期回填项（候选清洗开始日 + 剩余天数 N）。
     */
    @Test
    public void shouldCollectEndingDateFillWhenSkippingCleaningForEndingSku() {
        LhScheduleContext context = buildContext(toDate(2026, 4, 20, 0, 0, 0));
        context.getMachineOnlineInfoMap().put("K1301", buildOnlineInfo("K1301", "MAT-ENDING"));
        context.setMonthPlanList(Collections.singletonList(buildMonthPlan("MAT-ENDING", 30, 10)));
        MdmDevicePlanShut plan = buildDeviceStop("K1301", MachineStopTypeEnum.DRY_ICE_CLEANING.getCode(),
                toDate(2026, 4, 20, 8, 0, 0), toDate(2026, 4, 20, 11, 0, 0));
        plan.setId(2001L);
        context.setDevicePlanShutList(Collections.singletonList(plan));

        Map<String, List<MachineCleaningWindowDTO>> windowMap =
                new LhCleaningScheduleService().buildScheduledCleaningWindowMap(context);

        Assertions.assertTrue(windowMap.isEmpty(), "3 天内可收尾的 SKU 不安排清洗");
        // 余量 30 / 日产能 10 = 3 天，收尾日 = 候选清洗开始日 04-20 + 3 = 04-23
        List<CleaningScheduleDateFillItem> fillList = context.getCleaningScheduleDateFillList();
        Assertions.assertEquals(1, fillList.size(), "因收尾跳过应收集 1 条收尾日期回填项");
        CleaningScheduleDateFillItem fillItem = fillList.get(0);
        Assertions.assertEquals(2001L, fillItem.getPlanId());
        Assertions.assertEquals(toDate(2026, 4, 23, 0, 0, 0), fillItem.getScheduleDate());
        Assertions.assertEquals("收尾未安排清洗", fillItem.getFillReason());
    }

    /**
     * 用例说明：干冰(07)与喷砂(08)清洗成功后应按主键 id 和清洗类型分别收集回填项，不串改。
     */
    @Test
    public void shouldCollectFillItemByCleaningTypeAndPlanId() {
        LhScheduleContext context = buildContext(toDate(2026, 4, 20, 0, 0, 0));
        MdmDevicePlanShut dryIce = buildDeviceStop("K1301", MachineStopTypeEnum.DRY_ICE_CLEANING.getCode(),
                toDate(2026, 4, 20, 8, 0, 0), toDate(2026, 4, 20, 11, 0, 0));
        dryIce.setId(3001L);
        MdmDevicePlanShut sandBlast = buildDeviceStop("K1302", MachineStopTypeEnum.SANDBLASTING_CLEANING.getCode(),
                toDate(2026, 4, 20, 14, 0, 0), toDate(2026, 4, 20, 16, 0, 0));
        sandBlast.setId(3002L);
        context.setDevicePlanShutList(Arrays.asList(dryIce, sandBlast));

        new LhCleaningScheduleService().buildScheduledCleaningWindowMap(context);

        List<CleaningScheduleDateFillItem> fillList = context.getCleaningScheduleDateFillList();
        Assertions.assertEquals(2, fillList.size());
        CleaningScheduleDateFillItem dryIceFill = fillList.stream()
                .filter(item -> Long.valueOf(3001L).equals(item.getPlanId())).findFirst().orElse(null);
        CleaningScheduleDateFillItem sandBlastFill = fillList.stream()
                .filter(item -> Long.valueOf(3002L).equals(item.getPlanId())).findFirst().orElse(null);
        Assertions.assertNotNull(dryIceFill, "干冰计划应收集回填项");
        Assertions.assertNotNull(sandBlastFill, "喷砂计划应收集回填项");
        Assertions.assertEquals(CleaningTypeEnum.DRY_ICE.getCode(), dryIceFill.getCleanType());
        Assertions.assertEquals(CleaningTypeEnum.SAND_BLAST.getCode(), sandBlastFill.getCleanType());
        Assertions.assertEquals(toDate(2026, 4, 20, 6, 0, 0), dryIceFill.getScheduleDate());
        Assertions.assertEquals(toDate(2026, 4, 20, 14, 0, 0), sandBlastFill.getScheduleDate());
    }

    /**
     * 用例说明：最晚安排日期校验按自然日比较，允许当天、禁止延后，null 参数容错返回 true。
     */
    @Test
    public void shouldJudgeLatestCleaningDateByNaturalDay() {
        LhDeviceStopPlanScheduleService service = new LhDeviceStopPlanScheduleService();
        // 提前：实际 04-20 < 计划 04-22 -> 允许
        Assertions.assertTrue(service.isCleaningActualDateNotLaterThanPlanBegin(
                toDate(2026, 4, 22, 8, 0, 0), toDate(2026, 4, 20, 6, 0, 0)));
        // 当天：实际 04-20 06:00、计划 04-20 14:00 -> 允许（同自然日）
        Assertions.assertTrue(service.isCleaningActualDateNotLaterThanPlanBegin(
                toDate(2026, 4, 20, 14, 0, 0), toDate(2026, 4, 20, 6, 0, 0)));
        // 当天：实际 04-20 14:00、计划 04-20 08:00 -> 允许（同自然日，不按时刻比较）
        Assertions.assertTrue(service.isCleaningActualDateNotLaterThanPlanBegin(
                toDate(2026, 4, 20, 8, 0, 0), toDate(2026, 4, 20, 14, 0, 0)));
        // 延后：实际 04-21 > 计划 04-20 -> 禁止
        Assertions.assertFalse(service.isCleaningActualDateNotLaterThanPlanBegin(
                toDate(2026, 4, 20, 8, 0, 0), toDate(2026, 4, 21, 6, 0, 0)));
        // null 容错
        Assertions.assertTrue(service.isCleaningActualDateNotLaterThanPlanBegin(null, toDate(2026, 4, 20, 6, 0, 0)));
        Assertions.assertTrue(service.isCleaningActualDateNotLaterThanPlanBegin(toDate(2026, 4, 20, 8, 0, 0), null));
    }

    /**
     * 用例说明：batchFillCleaningScheduleDate 按主键 id 去重，仅对去重后记录调用 updateById 回填排程日期。
     */
    @Test
    public void shouldBatchFillScheduleDateWithDedupAndUpdateById() {
        LhDeviceStopPlanScheduleService service = new LhDeviceStopPlanScheduleService();
        MdmDevicePlanShutMapper mockMapper = mock(MdmDevicePlanShutMapper.class);
        ReflectionTestUtils.setField(service, "mdmDevicePlanShutMapper", mockMapper);

        List<CleaningScheduleDateFillItem> fillList = new ArrayList<>(Arrays.asList(
                buildFillItem(3001L, toDate(2026, 4, 20, 6, 0, 0), CleaningTypeEnum.DRY_ICE.getCode(), "K1301", "清洗成功"),
                // 重复 planId 3001，应被去重，不重复更新
                buildFillItem(3001L, toDate(2026, 4, 21, 6, 0, 0), CleaningTypeEnum.DRY_ICE.getCode(), "K1301", "清洗成功"),
                buildFillItem(3002L, toDate(2026, 4, 20, 14, 0, 0), CleaningTypeEnum.SAND_BLAST.getCode(), "K1302", "清洗成功")));

        int count = service.batchFillCleaningScheduleDate(fillList);

        Assertions.assertEquals(2, count, "去重后应回填 2 条");
        ArgumentCaptor<MdmDevicePlanShut> captor = ArgumentCaptor.forClass(MdmDevicePlanShut.class);
        verify(mockMapper, times(2)).updateById(captor.capture());
        // 去重保留首条，但设备停机计划排程日期统一归零到自然日。
        List<MdmDevicePlanShut> updated = captor.getAllValues();
        Assertions.assertEquals(3001L, updated.get(0).getId());
        Assertions.assertEquals(toDate(2026, 4, 20, 0, 0, 0), updated.get(0).getScheduleDate());
        Assertions.assertEquals(3002L, updated.get(1).getId());
        Assertions.assertEquals(toDate(2026, 4, 20, 0, 0, 0), updated.get(1).getScheduleDate());
    }

    /**
     * 用例说明：batchFillCleaningScheduleDate 空列表或全无效项时不调用 updateById。
     */
    @Test
    public void shouldSkipBatchFillWhenFillListEmptyOrInvalid() {
        LhDeviceStopPlanScheduleService service = new LhDeviceStopPlanScheduleService();
        MdmDevicePlanShutMapper mockMapper = mock(MdmDevicePlanShutMapper.class);
        ReflectionTestUtils.setField(service, "mdmDevicePlanShutMapper", mockMapper);

        Assertions.assertEquals(0, service.batchFillCleaningScheduleDate(Collections.emptyList()));
        Assertions.assertEquals(0, service.batchFillCleaningScheduleDate(Collections.singletonList(
                buildFillItem(null, new Date(), CleaningTypeEnum.DRY_ICE.getCode(), "K1", "x"))));
        verify(mockMapper, never()).updateById(any());
    }

    /**
     * 构建清洗排程日期回填项测试数据。
     */
    private CleaningScheduleDateFillItem buildFillItem(Long planId, Date scheduleDate, String cleanType,
                                                       String machineCode, String fillReason) {
        CleaningScheduleDateFillItem item = new CleaningScheduleDateFillItem();
        item.setPlanId(planId);
        item.setScheduleDate(scheduleDate);
        item.setCleanType(cleanType);
        item.setMachineCode(machineCode);
        item.setFillReason(fillReason);
        return item;
    }

    private LhScheduleContext buildContext(Date scheduleDate) {
        LhScheduleContext context = new LhScheduleContext();
        context.setFactoryCode("116");
        context.setScheduleDate(scheduleDate);
        context.getLhParamsMap().put(LhScheduleParamConstant.SCHEDULE_DAYS, "3");
        context.getLhParamsMap().put(LhScheduleParamConstant.MORNING_START_HOUR, "6");
        context.getLhParamsMap().put(LhScheduleParamConstant.AFTERNOON_START_HOUR, "14");
        context.getLhParamsMap().put(LhScheduleParamConstant.NIGHT_START_HOUR, "22");
        context.getLhParamsMap().put(LhScheduleParamConstant.SAND_BLAST_DAILY_LIMIT, "1");
        return context;
    }

    private MdmDevicePlanShut buildDeviceStop(String machineCode, String stopType, Date beginDate, Date endDate) {
        MdmDevicePlanShut planShut = new MdmDevicePlanShut();
        planShut.setFactoryCode("116");
        planShut.setMachineCode(machineCode);
        planShut.setMachineStopType(stopType);
        planShut.setBeginDate(beginDate);
        planShut.setEndDate(endDate);
        planShut.setRemark(stopType);
        return planShut;
    }

    private com.zlt.aps.lh.api.domain.entity.LhMachineOnlineInfo buildOnlineInfo(String machineCode,
                                                                                  String materialCode) {
        com.zlt.aps.lh.api.domain.entity.LhMachineOnlineInfo onlineInfo =
                new com.zlt.aps.lh.api.domain.entity.LhMachineOnlineInfo();
        onlineInfo.setLhCode(machineCode);
        onlineInfo.setMaterialCode(materialCode);
        return onlineInfo;
    }

    private FactoryMonthPlanProductionFinalResult buildMonthPlan(String materialCode,
                                                                 int differenceQty,
                                                                 int dayVulcanizationQty) {
        FactoryMonthPlanProductionFinalResult monthPlan = new FactoryMonthPlanProductionFinalResult();
        monthPlan.setMaterialCode(materialCode);
        monthPlan.setDifferenceQty(differenceQty);
        monthPlan.setDayVulcanizationQty(dayVulcanizationQty);
        return monthPlan;
    }

    private Date toDate(int year, int month, int day, int hour, int minute, int second) {
        return Date.from(LocalDateTime.of(year, month, day, hour, minute, second)
                .atZone(ZONE_ID)
                .toInstant());
    }
}
