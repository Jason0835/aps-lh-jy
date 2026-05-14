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
import com.zlt.aps.lh.engine.strategy.IMachineMatchStrategy;
import com.zlt.aps.lh.util.LhScheduleTimeUtil;
import com.zlt.aps.lh.util.MachineCleaningOverlapUtil;
import com.zlt.aps.lh.util.ShiftCapacityResolverUtil;
import com.zlt.aps.lh.util.ShiftProductionControlUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Objects;

/**
 * 排产目标量解析器
 * <p>统一承载"按需求排产 / 按产能满排"的目标量口径，避免分散判断。</p>
 *
 * @author APS
 */
@Slf4j
@Component
public class TargetScheduleQtyResolver {

    @Resource
    private IMachineMatchStrategy machineMatchStrategy;

    /**
     * 解析 SKU 的初始目标排产量。
     * <p>非满排模式（按需求排产）：目标量 = 待排量；窗口总量封顶交由日计划账本消费链路约束。</p>
     * <p>满排模式（按产能满排）：目标量 = min(待排量, 理论窗口产能上限)。</p>
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
        // 试制SKU严格按日计划排产，不允许超出dayN补满班次，忽略全局满排模式
        if (sku.isStrictTargetQty()) {
            int windowRemainingPlanQty = Math.max(0, sku.getWindowRemainingPlanQty());
            if (windowRemainingPlanQty > 0) {
                int surplusQty = Math.max(0, sku.getSurplusQty());
                upperLimitQty = Math.min(windowRemainingPlanQty, surplusQty);
            } else {
                upperLimitQty = pendingQty;
            }
        } else if (isFullCapacityMode(context)) {
            // 正式/量试SKU允许超出dayN补满班次，按理论窗口产能封顶
            upperLimitQty = resolveTheoreticalWindowCapacity(context, sku);
            // 满排模式下目标量直接取窗口理论满产产能，不因 dayN 计划量较小而被钳制
            return Math.max(0, upperLimitQty);
        } else {
            // 按需求排产只保留”需求口径”，不在此阶段按窗口额度压缩目标量。
            // 欠产滚动、未来预占、窗口总量封顶统一交由日计划账本消费链路处理，
            // 避免 DTO 初始化后再次把需求量压回 dayN 额度。
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
        // 试制/收尾SKU严格限制目标量，不允许为了凑满班次而超排
        if (currentTargetQty <= 0 || !isFullCapacityMode(context) || sku.isStrictTargetQty()) {
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

    /**
     * 计算 SKU 在当前排程窗口内所有可用机台的合计产能。
     * <p>用于收尾判断规则2和多机台排产目标量封顶。</p>
     * <p>对每台候选机台，按机台预计可用时间起算窗口内各班次可排量并汇总。</p>
     *
     * @param context 排程上下文
     * @param sku SKU排程DTO
     * @return 多台可用机台在窗口内的合计可排产量
     */
    public int calcSkuTotalAvailableCapacityInWindow(LhScheduleContext context, SkuScheduleDTO sku) {
        if (Objects.isNull(context) || Objects.isNull(sku)) {
            return 0;
        }
        IMachineMatchStrategy matchStrategy = getMachineMatchStrategy();
        if (Objects.isNull(matchStrategy)) {
            log.warn("机台匹配策略未注入，无法计算多机台合计产能, materialCode: {}", sku.getMaterialCode());
            return 0;
        }
        List<MachineScheduleDTO> candidates = matchStrategy.matchMachines(context, sku);
        if (CollectionUtils.isEmpty(candidates)) {
            log.debug("SKU无候选机台，多机台合计产能为0, materialCode: {}", sku.getMaterialCode());
            return 0;
        }
        List<LhShiftConfigVO> shifts = resolveScheduleShifts(context);
        if (CollectionUtils.isEmpty(shifts)) {
            return 0;
        }
        int totalCapacity = 0;
        for (MachineScheduleDTO machine : candidates) {
            int machineCapacity = calculateMachineAvailableCapacityInWindow(context, sku, machine, shifts);
            totalCapacity += machineCapacity;
        }
        log.debug("SKU多机台合计产能计算完成, materialCode: {}, 候选机台数: {}, 合计产能: {}",
                sku.getMaterialCode(), candidates.size(), totalCapacity);
        return totalCapacity;
    }

    /**
     * 计算单台机台在排程窗口内的可用产能。
     *
     * @param context 排程上下文
     * @param sku SKU
     * @param machine 机台
     * @return 机台窗口可排量
     */
    public int calcMachineAvailableCapacityInWindow(LhScheduleContext context,
                                                    SkuScheduleDTO sku,
                                                    MachineScheduleDTO machine) {
        if (Objects.isNull(context) || Objects.isNull(sku) || Objects.isNull(machine)) {
            return 0;
        }
        List<LhShiftConfigVO> shifts = resolveScheduleShifts(context);
        if (CollectionUtils.isEmpty(shifts)) {
            return 0;
        }
        return calculateMachineAvailableCapacityInWindow(context, sku, machine, shifts);
    }

    /**
     * 计算单台机台在排程窗口内的可用产能。
     * <p>从机台预计可用时间起，逐班次累加该机台可排量。</p>
     *
     * @param context 排程上下文
     * @param sku SKU排程DTO
     * @param machine 机台
     * @param shifts 排程窗口班次
     * @return 该机台在窗口内的可排产量
     */
    private int calculateMachineAvailableCapacityInWindow(LhScheduleContext context,
                                                          SkuScheduleDTO sku,
                                                          MachineScheduleDTO machine,
                                                          List<LhShiftConfigVO> shifts) {
        if (Objects.isNull(machine) || Objects.isNull(sku)) {
            return 0;
        }
        int shiftCapacity = ShiftCapacityResolverUtil.resolveRuntimeShiftCapacity(
                context, machine, sku.getShiftCapacity());
        int lhTimeSeconds = sku.getLhTimeSeconds();
        if (shiftCapacity <= 0 && lhTimeSeconds <= 0) {
            return 0;
        }
        int mouldQty = ShiftCapacityResolverUtil.resolveMachineMouldQty(machine);
        // 机台可用起点：取预计完工时间和窗口首班开始时间的较晚者
        Date machineAvailableTime = machine.getEstimatedEndTime();
        Date windowStartTime = shifts.get(0).getShiftStartDateTime();
        Date cursorStartTime;
        if (machineAvailableTime != null && machineAvailableTime.after(windowStartTime)) {
            cursorStartTime = machineAvailableTime;
        } else {
            cursorStartTime = windowStartTime;
        }
        int dryIceLossQty = context.getParamIntValue(
                LhScheduleParamConstant.DRY_ICE_LOSS_QTY, LhScheduleConstant.DRY_ICE_LOSS_QTY);
        int dryIceDurationHours = context.getParamIntValue(
                LhScheduleParamConstant.DRY_ICE_DURATION_HOURS, LhScheduleConstant.DRY_ICE_DURATION_HOURS);

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
            ShiftProductionControlDTO control = ShiftProductionControlUtil.resolveEffectiveControl(
                    context, shift, cursorStartTime);
            if (Objects.isNull(control) || !control.isCanSchedule()) {
                continue;
            }
            Date effectiveStartTime = control.getEffectiveStartTime();
            Date effectiveEndTime = control.getEffectiveEndTime();
            int shiftMaxQty = ShiftCapacityResolverUtil.resolveShiftCapacityWithDowntime(
                    context.getDevicePlanShutList(),
                    new ArrayList<>(0),
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
     * 收尾场景下调整目标排产量。
     * <p>仅在收尾判定完成后调用，非收尾SKU不应调用此方法。</p>
     * <p>公式：endingTargetQty = max(embryoStock, surplusQty)。</p>
     * <p>收尾SKU的目标量不再受窗口 dayN 总量限制，需按硫化余量/胎胚库存较大值排满；
     * 仅保留多机台合计产能封顶，避免超出当前窗口真实可排能力。</p>
     *
     * @param context 排程上下文
     * @param sku SKU排程DTO
     * @return 调整后的目标排产量
     */
    public int upsizeEndingTargetQty(LhScheduleContext context, SkuScheduleDTO sku) {
        if (Objects.isNull(sku)) {
            return 0;
        }
        // 收尾场景下严格限制目标量，禁止补满班次超排
        sku.setStrictTargetQty(true);

        int currentTargetQty = Math.max(0, sku.resolveTargetScheduleQty());
        int embryoStock = Math.max(0, sku.getEmbryoStock());
        int surplusQty = Math.max(0, sku.getSurplusQty());
        int windowPlanQty = Math.max(0, sku.getWindowPlanQty());
        int endingBaseQty = Math.max(embryoStock, surplusQty);
        int endingTargetQty = endingBaseQty;
        // 多机台合计产能封顶
        int totalAvailableCapacity = calcSkuTotalAvailableCapacityInWindow(context, sku);
        if (totalAvailableCapacity > 0) {
            endingTargetQty = Math.min(endingTargetQty, totalAvailableCapacity);
        }
        if (endingTargetQty != currentTargetQty) {
            String direction = endingTargetQty > currentTargetQty ? "上调" : "下调";
            int windowRemainingPlanQty = Math.max(0, sku.getWindowRemainingPlanQty());
            log.info("收尾SKU目标量{}, materialCode: {}, 原目标量: {}, 调整后: {}, "
                            + "窗口日计划总量: {}, 窗口日计划剩余: {}, 胎胚库存: {}, 月计划余量: {}, 多机台合计产能: {}",
                    direction, sku.getMaterialCode(), currentTargetQty, endingTargetQty,
                    windowPlanQty, windowRemainingPlanQty, embryoStock, surplusQty, totalAvailableCapacity);
            sku.setTargetScheduleQty(endingTargetQty);
            sku.setRemainingScheduleQty(endingTargetQty);
            return endingTargetQty;
        }
        return currentTargetQty;
    }

    /**
     * 获取机台匹配策略（带空安全回退）。
     *
     * @return 机台匹配策略
     */
    private IMachineMatchStrategy getMachineMatchStrategy() {
        return machineMatchStrategy;
    }
}
