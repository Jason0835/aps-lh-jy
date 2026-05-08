package com.zlt.aps.lh.component;

import com.zlt.aps.lh.api.constant.LhScheduleConstant;
import com.zlt.aps.lh.api.constant.LhScheduleParamConstant;
import com.zlt.aps.lh.api.domain.dto.MachineCleaningWindowDTO;
import com.zlt.aps.lh.api.domain.dto.MachineScheduleDTO;
import com.zlt.aps.lh.api.domain.dto.ShiftProductionControlDTO;
import com.zlt.aps.lh.api.domain.dto.SkuScheduleDTO;
import com.zlt.aps.lh.api.domain.vo.LhShiftConfigVO;
import com.zlt.aps.lh.api.enums.ScheduleTargetModeEnum;
import com.zlt.aps.lh.context.LhScheduleConfig;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.util.LhScheduleTimeUtil;
import com.zlt.aps.lh.util.MachineCleaningOverlapUtil;
import com.zlt.aps.lh.util.ShiftCapacityResolverUtil;
import com.zlt.aps.lh.util.ShiftProductionControlUtil;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Objects;

/**
 * 排产目标量解析器
 * <p>统一承载“按需求排产 / 按产能满排”的目标量口径，避免分散判断。</p>
 *
 * @author APS
 */
@Component
public class TargetScheduleQtyResolver {

    /**
     * 解析 SKU 的初始目标排产量。
     *
     * @param context 排程上下文
     * @param sku SKU
     * @return 初始目标排产量
     */
    public int resolveInitialTargetQty(LhScheduleContext context, SkuScheduleDTO sku) {
        if (Objects.isNull(sku)) {
            return 0;
        }
        int pendingQty = Math.max(0, sku.getPendingQty());
        if (pendingQty <= 0) {
            return 0;
        }
        int upperLimitQty;
        if (isFullCapacityMode(context)) {
            upperLimitQty = resolveTheoreticalWindowCapacity(context, sku);
        } else {
            upperLimitQty = pendingQty;
        }
        return Math.max(0, Math.min(pendingQty, upperLimitQty));
    }

    /**
     * 按机台实际开产时间收敛目标排产量。
     *
     * @param context 排程上下文
     * @param sku SKU
     * @param machine 机台
     * @param productionStartTime 实际开产时间
     * @param shifts 排程窗口班次
     * @return 收敛后的目标排产量
     */
    public int refineTargetQtyByMachineCapacity(LhScheduleContext context,
                                                SkuScheduleDTO sku,
                                                MachineScheduleDTO machine,
                                                Date switchStartTime,
                                                Date productionStartTime,
                                                List<LhShiftConfigVO> shifts) {
        if (Objects.isNull(sku)) {
            return 0;
        }
        int currentTargetQty = sku.resolveTargetScheduleQty();
        if (currentTargetQty <= 0 || !isFullCapacityMode(context)) {
            return Math.max(currentTargetQty, 0);
        }
        int actualCapacityQty = resolveActualWindowCapacity(context, sku, machine, switchStartTime, productionStartTime, shifts);
        if (actualCapacityQty <= 0) {
            return 0;
        }
        return Math.min(currentTargetQty, actualCapacityQty);
    }

    /**
     * 判断当前是否为按产能满排模式。
     *
     * @param context 排程上下文
     * @return true-按产能满排，false-按需求排产
     */
    public boolean isFullCapacityMode(LhScheduleContext context) {
        LhScheduleConfig scheduleConfig = context != null ? context.getScheduleConfig() : null;
        if (Objects.isNull(scheduleConfig)) {
            return LhScheduleConstant.ENABLE_FULL_CAPACITY_SCHEDULING == 1;
        }
        return scheduleConfig.getScheduleTargetMode() == ScheduleTargetModeEnum.CAPACITY_FULL;
    }

    /**
     * 解析理论窗口产能上限。
     *
     * @param context 排程上下文
     * @param sku SKU
     * @return 理论窗口产能上限
     */
    private int resolveTheoreticalWindowCapacity(LhScheduleContext context, SkuScheduleDTO sku) {
        if (Objects.isNull(sku) || sku.getShiftCapacity() <= 0) {
            return Math.max(0, sku != null ? sku.getPendingQty() : 0);
        }
        List<LhShiftConfigVO> shifts = resolveScheduleShifts(context);
        if (CollectionUtils.isEmpty(shifts)) {
            return Math.max(0, sku.getPendingQty());
        }
        int mouldQty = ShiftCapacityResolverUtil.resolveMachineMouldQty(sku.getMouldQty());
        int totalCapacity = 0;
        for (LhShiftConfigVO shift : shifts) {
            ShiftProductionControlDTO control = ShiftProductionControlUtil.resolveEffectiveControl(
                    context, shift, shift.getShiftStartDateTime());
            if (Objects.isNull(control) || !control.isCanSchedule()) {
                continue;
            }
            long availableSeconds = (control.getEffectiveEndTime().getTime() - control.getEffectiveStartTime().getTime()) / 1000L;
            int shiftMaxQty = ShiftCapacityResolverUtil.resolveShiftCapacity(
                    sku.getShiftCapacity(),
                    sku.getLhTimeSeconds(),
                    mouldQty,
                    ShiftCapacityResolverUtil.resolveShiftDurationSeconds(shift),
                    availableSeconds);
            totalCapacity += ShiftProductionControlUtil.deductCapacityByControl(control, shiftMaxQty, mouldQty);
        }
        return Math.max(0, totalCapacity);
    }

    /**
     * 解析机台在剩余窗口内的实际可排产量。
     *
     * @param context 排程上下文
     * @param sku SKU
     * @param machine 机台
     * @param productionStartTime 实际开产时间
     * @param shifts 排程窗口班次
     * @return 实际可排产量
     */
    private int resolveActualWindowCapacity(LhScheduleContext context,
                                            SkuScheduleDTO sku,
                                            MachineScheduleDTO machine,
                                            Date switchStartTime,
                                            Date productionStartTime,
                                            List<LhShiftConfigVO> shifts) {
        if (Objects.isNull(context)
                || Objects.isNull(sku)
                || Objects.isNull(machine)
                || Objects.isNull(productionStartTime)
                || CollectionUtils.isEmpty(shifts)) {
            return 0;
        }
        int shiftCapacity = ShiftCapacityResolverUtil.resolveRuntimeShiftCapacity(
                context, machine, sku.getShiftCapacity());
        int lhTimeSeconds = sku.getLhTimeSeconds();
        if (shiftCapacity <= 0 && lhTimeSeconds <= 0) {
            return 0;
        }
        int mouldQty = ShiftCapacityResolverUtil.resolveMachineMouldQty(machine);
        Date firstProductionStartTime = ShiftProductionControlUtil.resolveFirstSchedulableStartIgnoringCleaning(
                context,
                machine.getMachineCode(),
                productionStartTime,
                shifts,
                shiftCapacity,
                lhTimeSeconds,
                mouldQty);
        if (firstProductionStartTime == null) {
            return 0;
        }
        List<MachineCleaningWindowDTO> cleaningWindowList = resolveEffectiveCleaningWindowList(
                machine, switchStartTime, firstProductionStartTime);
        int dryIceLossQty = context.getParamIntValue(
                LhScheduleParamConstant.DRY_ICE_LOSS_QTY, LhScheduleConstant.DRY_ICE_LOSS_QTY);
        int dryIceDurationHours = context.getParamIntValue(
                LhScheduleParamConstant.DRY_ICE_DURATION_HOURS, LhScheduleConstant.DRY_ICE_DURATION_HOURS);

        Date cursorStartTime = firstProductionStartTime;
        int totalQty = 0;
        boolean started = false;
        for (LhShiftConfigVO shift : shifts) {
            if (!started) {
                if (cursorStartTime.before(shift.getShiftEndDateTime())) {
                    started = true;
                } else {
                    continue;
                }
            }
            ShiftProductionControlDTO control = ShiftProductionControlUtil.resolveEffectiveControl(context, shift, cursorStartTime);
            if (Objects.isNull(control) || !control.isCanSchedule()) {
                continue;
            }
            Date effectiveStartTime = control.getEffectiveStartTime();
            Date effectiveEndTime = control.getEffectiveEndTime();
            int shiftMaxQty = ShiftCapacityResolverUtil.resolveShiftCapacityWithDowntime(
                    context.getDevicePlanShutList(),
                    cleaningWindowList,
                    machine.getMachineCode(),
                    effectiveStartTime,
                    effectiveEndTime,
                    shiftCapacity,
                    lhTimeSeconds,
                    mouldQty,
                    ShiftCapacityResolverUtil.resolveShiftDurationSeconds(shift),
                    dryIceLossQty,
                    dryIceDurationHours);
            shiftMaxQty = ShiftProductionControlUtil.deductCapacityByControl(control, shiftMaxQty, mouldQty);
            if (shiftMaxQty <= 0) {
                continue;
            }
            totalQty += shiftMaxQty;
            cursorStartTime = effectiveEndTime;
        }
        return Math.max(totalQty, 0);
    }

    /**
     * 解析用于排产估算的清洗窗口。
     *
     * @param machine 机台
     * @param switchStartTime 切换开始时间
     * @param firstProductionStartTime 首个可排产开始时间
     * @return 有效清洗窗口列表
     */
    private List<MachineCleaningWindowDTO> resolveEffectiveCleaningWindowList(MachineScheduleDTO machine,
                                                                              Date switchStartTime,
                                                                              Date firstProductionStartTime) {
        if (Objects.isNull(machine) || CollectionUtils.isEmpty(machine.getCleaningWindowList())) {
            return new ArrayList<>(0);
        }
        return new ArrayList<>(MachineCleaningOverlapUtil.excludeOverlapWindows(
                machine.getCleaningWindowList(), switchStartTime, firstProductionStartTime));
    }

    /**
     * 获取排程窗口班次。
     *
     * @param context 排程上下文
     * @return 班次列表
     */
    private List<LhShiftConfigVO> resolveScheduleShifts(LhScheduleContext context) {
        if (Objects.isNull(context)) {
            return new ArrayList<>(0);
        }
        if (!CollectionUtils.isEmpty(context.getScheduleWindowShifts())) {
            return context.getScheduleWindowShifts();
        }
        if (Objects.isNull(context.getScheduleDate())) {
            return new ArrayList<>(0);
        }
        return LhScheduleTimeUtil.buildDefaultScheduleShifts(context, context.getScheduleDate());
    }
}
