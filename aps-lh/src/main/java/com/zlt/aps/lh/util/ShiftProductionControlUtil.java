package com.zlt.aps.lh.util;

import com.zlt.aps.lh.api.domain.dto.ShiftProductionControlDTO;
import com.zlt.aps.lh.api.domain.vo.LhShiftConfigVO;
import com.zlt.aps.lh.context.LhScheduleContext;
import org.springframework.util.CollectionUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Date;
import java.util.List;
import java.util.Objects;

/**
 * 班次排产管控消费工具。
 * <p>统一读取工作日历、开产、停产已经计算好的班次有效窗口。</p>
 *
 * @author APS
 */
public final class ShiftProductionControlUtil {

    /** 请求时间导致班次无可用窗口 */
    private static final String REASON_REQUEST_TIME_NOT_AVAILABLE = "请求开产时间后班次无可排窗口";

    private ShiftProductionControlUtil() {
    }

    /**
     * 解析指定班次在请求开产时间后的有效可排窗口。
     *
     * @param context 排程上下文
     * @param shift 班次
     * @param requestedStartTime 请求开产时间
     * @return 班次排产管控信息
     */
    public static ShiftProductionControlDTO resolveEffectiveControl(LhScheduleContext context,
                                                                    LhShiftConfigVO shift,
                                                                    Date requestedStartTime) {
        if (Objects.isNull(shift)) {
            return null;
        }
        ShiftProductionControlDTO baseControl = null;
        if (Objects.nonNull(context) && !CollectionUtils.isEmpty(context.getShiftProductionControlMap())) {
            baseControl = context.getShiftProductionControlMap().get(shift.getShiftIndex());
        }
        ShiftProductionControlDTO control = copyControl(Objects.nonNull(baseControl) ? baseControl : buildDefaultControl(shift));
        if (!control.isCanSchedule()) {
            return control;
        }
        if (Objects.nonNull(requestedStartTime) && requestedStartTime.after(control.getEffectiveStartTime())) {
            control.setEffectiveStartTime(requestedStartTime);
        }
        if (!control.getEffectiveStartTime().before(control.getEffectiveEndTime())) {
            control.setCanSchedule(false);
            control.setUnavailableReason(REASON_REQUEST_TIME_NOT_AVAILABLE);
        }
        return control;
    }

    /**
     * 解析开产模式下允许发起切换（换模/换活字块）的最早时间。
     * <p>业务口径：开产夜班顺延后，切换动作也不能早于开产班次开始时间，
     * 否则会出现“早班直接有计划量，但换模提前落到开产前”的结果。</p>
     *
     * @param context 排程上下文
     * @param requestedSwitchStartTime 请求切换开始时间
     * @return 收口后的最早切换开始时间
     */
    public static Date resolveEarliestSwitchStartTime(LhScheduleContext context, Date requestedSwitchStartTime) {
        if (Objects.isNull(context) || Objects.isNull(requestedSwitchStartTime) || !context.isOpenProductionMode()) {
            return requestedSwitchStartTime;
        }
        ShiftProductionControlDTO openShift = context.getOpenProductionShift();
        if (Objects.isNull(openShift) || Objects.isNull(openShift.getEffectiveStartTime())) {
            return requestedSwitchStartTime;
        }
        return requestedSwitchStartTime.before(openShift.getEffectiveStartTime())
                ? openShift.getEffectiveStartTime() : requestedSwitchStartTime;
    }

    /**
     * 按班次产能比例扣减产能。
     *
     * @param control 班次排产管控信息
     * @param originalCapacity 原始产能
     * @param mouldQty 模台数
     * @return 扣减后的产能
     */
    public static int deductCapacityByControl(ShiftProductionControlDTO control, int originalCapacity, int mouldQty) {
        if (Objects.isNull(control) || originalCapacity <= 0 || Objects.isNull(control.getCapacityRate())) {
            return Math.max(originalCapacity, 0);
        }
        if (control.getCapacityRate().compareTo(BigDecimal.ONE) >= 0) {
            return originalCapacity;
        }
        int adjusted = control.getCapacityRate()
                .multiply(BigDecimal.valueOf(originalCapacity))
                .setScale(0, RoundingMode.DOWN)
                .intValue();
        return ShiftCapacityResolverUtil.normalizeQtyToMouldMultiple(adjusted, mouldQty, true);
    }

    /**
     * 解析考虑班次管控后的首个可排产开始时间。
     *
     * @param context 排程上下文
     * @param machineCode 机台编号
     * @param productionStartTime 理论开产时间
     * @param shifts 排程班次
     * @param shiftCapacity 班产
     * @param lhTimeSeconds 硫化时长
     * @param mouldQty 模台数
     * @return 首个可排产开始时间
     */
    public static Date resolveFirstSchedulableStartIgnoringCleaning(LhScheduleContext context,
                                                                    String machineCode,
                                                                    Date productionStartTime,
                                                                    List<LhShiftConfigVO> shifts,
                                                                    int shiftCapacity,
                                                                    int lhTimeSeconds,
                                                                    int mouldQty) {
        if (Objects.isNull(context) || Objects.isNull(productionStartTime) || CollectionUtils.isEmpty(shifts)) {
            return null;
        }
        Date cursorStartTime = productionStartTime;
        boolean started = false;
        for (LhShiftConfigVO shift : shifts) {
            if (!started) {
                if (cursorStartTime.before(shift.getShiftEndDateTime())) {
                    started = true;
                } else {
                    continue;
                }
            }
            ShiftProductionControlDTO control = resolveEffectiveControl(context, shift, cursorStartTime);
            if (Objects.isNull(control) || !control.isCanSchedule()) {
                continue;
            }
            long netAvailableSeconds = ShiftCapacityResolverUtil.resolveNetAvailableSeconds(
                    context.getDevicePlanShutList(), machineCode, control.getEffectiveStartTime(), control.getEffectiveEndTime());
            if (netAvailableSeconds <= 0) {
                cursorStartTime = control.getEffectiveEndTime();
                continue;
            }
            int shiftMaxQty = ShiftCapacityResolverUtil.resolveShiftCapacity(
                    shiftCapacity,
                    lhTimeSeconds,
                    mouldQty,
                    ShiftCapacityResolverUtil.resolveShiftDurationSeconds(shift),
                    netAvailableSeconds);
            shiftMaxQty = deductCapacityByControl(control, shiftMaxQty, mouldQty);
            if (shiftMaxQty > 0) {
                return control.getEffectiveStartTime();
            }
            cursorStartTime = control.getEffectiveEndTime();
        }
        return null;
    }

    /**
     * 构建默认班次管控信息。
     *
     * @param shift 班次
     * @return 默认班次管控信息
     */
    private static ShiftProductionControlDTO buildDefaultControl(LhShiftConfigVO shift) {
        ShiftProductionControlDTO control = new ShiftProductionControlDTO();
        control.setShiftIndex(shift.getShiftIndex());
        control.setShiftCode(shift.getShiftType());
        control.setShiftName(shift.getShiftName());
        control.setWorkDate(shift.getWorkDate());
        control.setShiftStartTime(shift.getShiftStartDateTime());
        control.setShiftEndTime(shift.getShiftEndDateTime());
        control.setEffectiveStartTime(shift.getShiftStartDateTime());
        control.setEffectiveEndTime(shift.getShiftEndDateTime());
        control.setCanSchedule(true);
        control.setCapacityRate(BigDecimal.ONE);
        return control;
    }

    /**
     * 复制班次管控信息。
     *
     * @param source 原班次管控信息
     * @return 复制后的班次管控信息
     */
    private static ShiftProductionControlDTO copyControl(ShiftProductionControlDTO source) {
        ShiftProductionControlDTO target = new ShiftProductionControlDTO();
        target.setShiftIndex(source.getShiftIndex());
        target.setShiftCode(source.getShiftCode());
        target.setShiftName(source.getShiftName());
        target.setWorkDate(source.getWorkDate());
        target.setShiftStartTime(source.getShiftStartTime());
        target.setShiftEndTime(source.getShiftEndTime());
        target.setEffectiveStartTime(source.getEffectiveStartTime());
        target.setEffectiveEndTime(source.getEffectiveEndTime());
        target.setCanSchedule(source.isCanSchedule());
        target.setCapacityRate(source.getCapacityRate());
        target.setUnavailableReason(source.getUnavailableReason());
        return target;
    }
}
