package com.zlt.aps.lh.engine.strategy.support;

import com.zlt.aps.lh.api.domain.dto.SkuScheduleDTO;
import com.zlt.aps.lh.api.domain.vo.LhShiftConfigVO;
import com.zlt.aps.lh.api.enums.ConstructionStageEnum;
import com.zlt.aps.lh.api.enums.ScheduleTypeEnum;
import com.zlt.aps.lh.api.enums.ShiftEnum;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.util.FirstInspectionQtyUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;

/**
 * S4.5 胎胚最早可供硫化时间中心解析器聚焦测试。
 *
 * <p>覆盖时间取较晚值、班次半开边界、部分班次首检容量和试制固定2小时扣减。
 * 换模时间不属于解析器输入，确保该能力只限制首检与正式生产。</p>
 *
 * @author APS
 */
public class NewSpecEmbryoAvailableTimeResolverTest {

    /**
     * 换模已提前完成时，实际生产必须等待胎胚可供时间。
     */
    @Test
    public void shouldWaitForEmbryoWhenExistingStartIsEarlier() {
        Date existingStart = toDateTime(LocalDate.of(2026, 7, 18), 9, 0);
        Date embryoAvailableTime = toDateTime(LocalDate.of(2026, 7, 18), 10, 0);

        Date actualStart = NewSpecEmbryoAvailableTimeResolver.resolveActualProductionStartTime(
                existingStart, embryoAvailableTime);

        Assertions.assertEquals(embryoAvailableTime, actualStart);
    }

    /**
     * 现有换模、维修和班次规则计算出的开产时间较晚时，不得被胎胚时间提前。
     */
    @Test
    public void shouldKeepExistingStartWhenItIsLaterThanEmbryoTime() {
        Date existingStart = toDateTime(LocalDate.of(2026, 7, 18), 11, 30);
        Date embryoAvailableTime = toDateTime(LocalDate.of(2026, 7, 18), 10, 0);

        Date actualStart = NewSpecEmbryoAvailableTimeResolver.resolveActualProductionStartTime(
                existingStart, embryoAvailableTime);

        Assertions.assertEquals(existingStart, actualStart);
    }

    /**
     * 胎胚时间位于班次中间时必须归当前班次。
     */
    @Test
    public void shouldResolveMiddleTimeToCurrentShift() {
        List<LhShiftConfigVO> shifts = buildThreeShifts();

        LhShiftConfigVO resolved = NewSpecEmbryoAvailableTimeResolver.resolveProductionShift(
                shifts, toDateTime(LocalDate.of(2026, 7, 18), 10, 0));

        Assertions.assertNotNull(resolved);
        Assertions.assertEquals(1, resolved.getShiftIndex());
    }

    /**
     * 胎胚时间位于班次中间时，部分班次折算窗口必须从实际生产开始时间起算，
     * 不得继续把06:00～10:00的完整班产计入候选或最终计划量。
     */
    @Test
    public void shouldClipPartialShiftCapacityToActualProductionStart() {
        LocalDate workDate = LocalDate.of(2026, 7, 18);
        Date shiftStart = toDateTime(workDate, 6, 0);
        Date shiftEnd = toDateTime(workDate, 14, 0);
        Date actualProductionStart = toDateTime(workDate, 10, 0);

        Date effectiveStart = NewSpecEmbryoAvailableTimeResolver.resolveEffectiveProductionWindowStart(
                shiftStart, shiftEnd, actualProductionStart);

        Assertions.assertEquals(actualProductionStart, effectiveStart);
        Assertions.assertEquals(4 * 60 * 60,
                NewSpecEmbryoAvailableTimeResolver.resolveProductionWindowSeconds(
                        effectiveStart, shiftEnd));
        Assertions.assertNull(NewSpecEmbryoAvailableTimeResolver.resolveEffectiveProductionWindowStart(
                shiftStart, shiftEnd, shiftEnd), "时间等于班次结束时当前班次不能再产生计划量");
    }

    /**
     * 班次采用左闭右开区间：等于开始归当前班，等于结束归下一班。
     */
    @Test
    public void shouldUseHalfOpenShiftBoundary() {
        List<LhShiftConfigVO> shifts = buildThreeShifts();

        LhShiftConfigVO atStart = NewSpecEmbryoAvailableTimeResolver.resolveProductionShift(
                shifts, toDateTime(LocalDate.of(2026, 7, 18), 6, 0));
        LhShiftConfigVO atEnd = NewSpecEmbryoAvailableTimeResolver.resolveProductionShift(
                shifts, toDateTime(LocalDate.of(2026, 7, 18), 14, 0));

        Assertions.assertEquals(1, atStart.getShiftIndex());
        Assertions.assertEquals(2, atEnd.getShiftIndex());
    }

    /**
     * 普通SKU部分班次总产能不足完整首检时当前班必须归零；
     * 可容纳首检时，正常生产量还需从部分班次总产能中扣除首检条数。
     */
    @Test
    public void shouldMoveWholeFirstInspectionWhenPartialCapacityIsInsufficient() {
        LhScheduleContext context = new LhScheduleContext();
        SkuScheduleDTO sku = new SkuScheduleDTO();
        LhShiftConfigVO shift = buildThreeShifts().get(0);

        int insufficientCapacity = FirstInspectionQtyUtil.resolveEmbryoAvailableShiftCapacity(
                context, sku, shift, 3, 4, 24,
                ScheduleTypeEnum.NEW_SPEC.getCode(), "K1001");
        int normalCapacity = FirstInspectionQtyUtil.resolveNormalCapacityAfterFirstInspection(
                context, sku, shift, 10, shift.getShiftIndex(), 4, 24,
                ScheduleTypeEnum.NEW_SPEC.getCode(), "K1001", true);

        Assertions.assertEquals(0, insufficientCapacity);
        Assertions.assertEquals(6, normalCapacity,
                "部分班次10条总产能包含4条首检，正常生产只能使用剩余6条");
        Assertions.assertEquals(10, 4 + normalCapacity,
                "首检已写入后，首检量与同班正常生产量之和不得突破部分班次总产能");
    }

    /**
     * 试制SKU在胎胚可供部分班次继续固定扣减2小时；
     * 仅剩2小时对应产能时不得形成正产量。
     */
    @Test
    public void shouldDeductTwoHoursForTrialPartialShift() {
        LhScheduleContext context = new LhScheduleContext();
        SkuScheduleDTO sku = new SkuScheduleDTO();
        sku.setConstructionStage(ConstructionStageEnum.TRIAL.getCode());
        LhShiftConfigVO afternoonShift = buildShift(
                2, ShiftEnum.AFTERNOON_SHIFT, "14:00", "22:00");

        int noCapacity = FirstInspectionQtyUtil.resolveEmbryoAvailableShiftCapacity(
                context, sku, afternoonShift, 20, 0, 80,
                ScheduleTypeEnum.NEW_SPEC.getCode(), "K1001");
        int remainingCapacity = FirstInspectionQtyUtil.resolveEmbryoAvailableShiftCapacity(
                context, sku, afternoonShift, 40, 0, 80,
                ScheduleTypeEnum.NEW_SPEC.getCode(), "K1001");

        Assertions.assertEquals(0, noCapacity);
        Assertions.assertEquals(20, remainingCapacity);
    }

    /**
     * 胎胚时间晚于当前业务日结束时必须延期；缺少结构配置时不启用限制。
     */
    @Test
    public void shouldDeferAfterDayEndAndKeepNoConfigUnconstrained() {
        LocalDate workDate = LocalDate.of(2026, 7, 18);
        Date dayEndTime = toDateTime(workDate, 22, 0);
        Assertions.assertTrue(NewSpecEmbryoAvailableTimeResolver.reachesOrPassesDayEnd(
                toDateTime(workDate.plusDays(1), 6, 0), dayEndTime));

        LhScheduleContext context = new LhScheduleContext();
        context.setStructureEarliestLhTimeMap(new HashMap<String, Date>(4));
        SkuScheduleDTO sku = new SkuScheduleDTO();
        sku.setStructureName("NO-CONFIG");
        Assertions.assertFalse(NewSpecEmbryoAvailableTimeResolver.isConstrained(context, sku));
    }

    /**
     * 按结构名称精确匹配，不允许大小写归一化造成误命中。
     */
    @Test
    public void shouldMatchStructureNameCaseSensitively() {
        LhScheduleContext context = new LhScheduleContext();
        Date configuredTime = toDateTime(LocalDate.of(2026, 7, 18), 10, 0);
        context.setStructureEarliestLhTimeMap(
                new HashMap<String, Date>(Collections.singletonMap("Structure-A", configuredTime)));
        SkuScheduleDTO exactSku = new SkuScheduleDTO();
        exactSku.setStructureName("Structure-A");
        SkuScheduleDTO differentCaseSku = new SkuScheduleDTO();
        differentCaseSku.setStructureName("structure-a");

        Assertions.assertEquals(configuredTime,
                NewSpecEmbryoAvailableTimeResolver.resolveEarliestAvailableTime(context, exactSku));
        Assertions.assertNull(
                NewSpecEmbryoAvailableTimeResolver.resolveEarliestAvailableTime(context, differentCaseSku));
    }

    /**
     * 构建06:00～14:00、14:00～22:00、22:00～次日06:00三个连续班次。
     *
     * @return 测试班次
     */
    private List<LhShiftConfigVO> buildThreeShifts() {
        return Arrays.asList(
                buildShift(1, ShiftEnum.MORNING_SHIFT, "06:00", "14:00"),
                buildShift(2, ShiftEnum.AFTERNOON_SHIFT, "14:00", "22:00"),
                buildShift(3, ShiftEnum.NIGHT_SHIFT, "22:00", "06:00"));
    }

    /**
     * 构建指定类型班次。
     *
     * @param shiftIndex 班次索引
     * @param shiftType 班次类型
     * @param startTime 开始时间
     * @param endTime 结束时间
     * @return 班次配置
     */
    private LhShiftConfigVO buildShift(
            int shiftIndex, ShiftEnum shiftType, String startTime, String endTime) {
        LhShiftConfigVO shift = new LhShiftConfigVO();
        shift.setShiftIndex(shiftIndex);
        shift.setScheduleBaseDate(toDateTime(LocalDate.of(2026, 7, 18), 0, 0));
        shift.setDateOffset(0);
        shift.setShiftType(shiftType.getCode());
        shift.setStartTime(startTime);
        shift.setEndTime(endTime);
        return shift;
    }

    /**
     * 构造本地时区测试时间。
     *
     * @param date 日期
     * @param hour 小时
     * @param minute 分钟
     * @return Date 时间
     */
    private static Date toDateTime(LocalDate date, int hour, int minute) {
        return Date.from(date.atTime(hour, minute)
                .atZone(ZoneId.systemDefault()).toInstant());
    }
}
