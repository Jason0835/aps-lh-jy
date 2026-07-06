package com.zlt.aps.lh.service.impl;

import com.zlt.aps.lh.api.constant.LhScheduleParamConstant;
import com.zlt.aps.lh.api.domain.dto.MachineCleaningWindowDTO;
import com.zlt.aps.lh.api.domain.dto.MachineMaintenanceWindowDTO;
import com.zlt.aps.lh.api.enums.CleaningTypeEnum;
import com.zlt.aps.lh.api.enums.MachineStopTypeEnum;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.util.ShiftCapacityResolverUtil;
import com.zlt.aps.mdm.api.domain.entity.MdmDevicePlanShut;
import com.zlt.aps.mp.api.domain.entity.FactoryMonthPlanProductionFinalResult;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneId;
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
        Assertions.assertEquals(toDate(2026, 4, 21, 0, 0, 0), windowMap.get("K1302").get(0).getCleanEndTime());
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
        Assertions.assertEquals(toDate(2026, 4, 21, 0, 0, 0), window.getCleanEndTime());
    }

    /**
     * 用例说明：喷砂实际安排日若命中周日或喷砂机维保日，应继续向后寻找下一天中班。
     */
    @Test
    public void shouldContinueDelaySandBlastWhenSundayAndMaintenanceDateMatched() {
        LhScheduleContext context = buildContext(toDate(2026, 4, 19, 0, 0, 0));
        context.getLhParamsMap().put(LhScheduleParamConstant.SAND_BLAST_MAINTENANCE_DATES, "20");
        context.setDevicePlanShutList(Collections.singletonList(
                buildDeviceStop("K1301", MachineStopTypeEnum.SANDBLASTING_CLEANING.getCode(),
                        toDate(2026, 4, 19, 9, 0, 0), toDate(2026, 4, 19, 11, 0, 0))));

        MachineCleaningWindowDTO window = new LhCleaningScheduleService()
                .buildScheduledCleaningWindowMap(context).get("K1301").get(0);

        Assertions.assertEquals(toDate(2026, 4, 21, 14, 0, 0), window.getCleanStartTime());
        Assertions.assertEquals(toDate(2026, 4, 22, 0, 0, 0), window.getCleanEndTime());
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
                3);

        Assertions.assertEquals(5, shiftCapacity, "喷砂 2 小时与精度 3 小时重叠时只扣 3 小时产能");
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
