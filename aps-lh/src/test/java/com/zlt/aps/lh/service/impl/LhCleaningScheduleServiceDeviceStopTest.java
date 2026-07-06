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
        Assertions.assertEquals(toDate(2026, 4, 20, 8, 0, 0), windowMap.get("K1301").get(0).getCleanStartTime());
        Assertions.assertEquals(toDate(2026, 4, 20, 11, 0, 0), windowMap.get("K1301").get(0).getCleanEndTime());
    }

    /**
     * 用例说明：喷砂清洗计划不在中班时，应向后顺延到后续第一个可用中班，且保持原计划时长。
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
        Assertions.assertEquals(toDate(2026, 4, 20, 16, 0, 0), window.getCleanEndTime());
    }

    /**
     * 用例说明：喷砂顺延后的中班若命中周日或喷砂机维保日，应继续向后寻找下一天中班。
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
        Assertions.assertEquals(toDate(2026, 4, 21, 16, 0, 0), window.getCleanEndTime());
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
