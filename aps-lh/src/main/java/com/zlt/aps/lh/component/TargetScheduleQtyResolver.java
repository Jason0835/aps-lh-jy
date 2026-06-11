package com.zlt.aps.lh.component;

import com.zlt.aps.lh.api.constant.LhScheduleConstant;
import com.zlt.aps.lh.api.constant.LhScheduleParamConstant;
import com.zlt.aps.lh.api.domain.dto.MachineCleaningWindowDTO;
import com.zlt.aps.lh.api.domain.dto.MachineScheduleDTO;
import com.zlt.aps.lh.api.domain.dto.ShiftProductionControlDTO;
import com.zlt.aps.lh.api.domain.dto.SkuScheduleDTO;
import com.zlt.aps.lh.api.domain.entity.LhScheduleResult;
import com.zlt.aps.lh.api.domain.vo.LhShiftConfigVO;
import com.zlt.aps.lh.api.enums.ShiftEnum;
import com.zlt.aps.lh.api.enums.ScheduleTargetModeEnum;
import com.zlt.aps.lh.context.LhScheduleConfig;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.engine.strategy.IMachineMatchStrategy;
import com.zlt.aps.lh.util.LhScheduleTimeUtil;
import com.zlt.aps.lh.util.MachineCleaningOverlapUtil;
import com.zlt.aps.lh.util.ShiftCapacityResolverUtil;
import com.zlt.aps.lh.util.ShiftProductionControlUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import javax.annotation.Resource;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 排产目标量解析器。
 *
 * <p>业务定位：</p>
 * <ul>
 *   <li>统一承载“按需求排产”和“按产能满排”的目标量口径；</li>
 *   <li>根据 SKU 待排量、日计划账本、严格目标量、窗口理论产能和机台实际可用窗口收敛目标量；</li>
 *   <li>为结构五天内收尾、单机台满排、收尾上调和多机台产能评估提供同一套容量计算入口。</li>
 * </ul>
 *
 * <p>注意：该组件只计算目标量或产能上限，不直接修改排程结果；调用方负责把目标量写回 SKU 并消费账本。</p>
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
        // 试制/收尾等严格目标量 SKU 按 dayN 与余量控制，不允许为了补满班次突破目标量。
        if (sku.isStrictTargetQty()) {
            int windowRemainingPlanQty = Math.max(0, sku.getWindowRemainingPlanQty());
            if (windowRemainingPlanQty > 0) {
                int surplusQty = Math.max(0, sku.getSurplusQty());
                upperLimitQty = Math.min(windowRemainingPlanQty, surplusQty);
            } else {
                upperLimitQty = pendingQty;
            }
        } else if (isFullCapacityMode(context)) {
            // 正式/量试SKU允许超出dayN补满班次，按理论窗口产能封顶。
            upperLimitQty = resolveTheoreticalWindowCapacity(context, sku);
            // 满排模式下目标量直接取窗口理论满产产能，不因 dayN 计划量较小而被钳制
            return Math.max(0, upperLimitQty);
        } else {
            // 按需求排产只保留“需求口径”，不在此阶段按窗口额度压缩目标量。
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
        // 试制/收尾SKU严格限制目标量，不允许为了凑满班次而超排。
        // 满排模式下的正式/量试SKU才会根据真实机台窗口再次收敛目标量。
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
     * 按候选机台真实窗口产能计算结构排序使用的收尾天数。
     * <p>逐日汇总所有候选机台的实际可排量，目标量首次被覆盖的当天即为最晚收尾日。</p>
     *
     * @param context 排程上下文
     * @param sku SKU
     * @return 收尾天数；0-无目标量；-1-窗口内无法收敛
     */
    public int calcSkuActualEndingDaysInWindow(LhScheduleContext context, SkuScheduleDTO sku) {
        if (Objects.isNull(context) || Objects.isNull(sku)) {
            return -1;
        }
        int targetScheduleQty = Math.max(0, sku.resolveTargetScheduleQty());
        if (targetScheduleQty <= 0) {
            return 0;
        }
        IMachineMatchStrategy matchStrategy = getMachineMatchStrategy();
        if (Objects.isNull(matchStrategy)) {
            log.warn("机台匹配策略未注入，无法计算真实收尾天数, materialCode: {}", sku.getMaterialCode());
            return -1;
        }
        List<MachineScheduleDTO> candidates = matchStrategy.matchMachines(context, sku);
        if (CollectionUtils.isEmpty(candidates)) {
            return -1;
        }
        List<LhShiftConfigVO> shifts = resolveScheduleShifts(context);
        if (CollectionUtils.isEmpty(shifts)) {
            return -1;
        }
        LinkedHashMap<LocalDate, Integer> totalCapacityByDate = initCapacityByDate(shifts);
        for (MachineScheduleDTO machine : candidates) {
            Map<LocalDate, Integer> machineCapacityByDate = calculateMachineAvailableCapacityByDateInWindow(
                    context, sku, machine, shifts);
            for (Map.Entry<LocalDate, Integer> entry : machineCapacityByDate.entrySet()) {
                totalCapacityByDate.merge(entry.getKey(), entry.getValue(), Integer::sum);
            }
        }
        int cumulativeCapacity = 0;
        int endingDays = 0;
        for (Integer capacityQty : totalCapacityByDate.values()) {
            endingDays++;
            cumulativeCapacity += Math.max(0, capacityQty == null ? 0 : capacityQty);
            if (cumulativeCapacity >= targetScheduleQty) {
                return endingDays;
            }
        }
        return -1;
    }

    /**
     * 评估结构收尾排序专用的未来 N 天有效产能。
     * <p>口径固定为“未来结构收尾判定天数内，首选机台在真实换模/续作条件下的有效产能是否覆盖硫化余量”。</p>
     *
     * @param context 排程上下文
     * @param sku SKU
     * @return 结构收尾评估快照
     */
    public StructureEndingCapacitySnapshot evaluateStructureEndingCapacity(LhScheduleContext context, SkuScheduleDTO sku) {
        int structureEndingDays = resolveStructureEndingDays(context);
        StructureEndingCapacitySnapshot emptySnapshot = StructureEndingCapacitySnapshot.empty(structureEndingDays);
        if (Objects.isNull(context) || Objects.isNull(sku)) {
            return emptySnapshot;
        }
        IMachineMatchStrategy matchStrategy = getMachineMatchStrategy();
        if (Objects.isNull(matchStrategy)) {
            log.warn("机台匹配策略未注入，无法评估结构收尾有效产能, materialCode: {}", sku.getMaterialCode());
            return emptySnapshot;
        }
        List<MachineScheduleDTO> candidates = matchStrategy.matchMachines(context, sku);
        if (CollectionUtils.isEmpty(candidates) || Objects.isNull(candidates.get(0))) {
            log.info("结构收尾有效产能评估跳过, materialCode: {}, reason: 无候选机台", sku.getMaterialCode());
            return emptySnapshot;
        }
        MachineScheduleDTO machine = candidates.get(0);
        List<LhShiftConfigVO> structureShifts = resolveStructurePriorityShifts(context, structureEndingDays);
        if (CollectionUtils.isEmpty(structureShifts)) {
            log.info("结构收尾有效产能评估跳过, materialCode: {}, reason: 无结构收尾班次窗口", sku.getMaterialCode());
            return emptySnapshot;
        }
        LinkedHashMap<Integer, Integer> theoreticalShiftCapacityMap = calculateMachineAvailableCapacityByShiftInWindow(
                context, sku, machine, structureShifts, false);
        LinkedHashMap<Integer, Integer> effectiveShiftCapacityMap = calculateMachineAvailableCapacityByShiftInWindow(
                context, sku, machine, structureShifts, true);
        int demandQty = resolveStructureEndingDemandQty(sku);
        int theoreticalShiftCount = countEffectiveShiftCount(theoreticalShiftCapacityMap);
        int effectiveShiftCount = countEffectiveShiftCount(effectiveShiftCapacityMap);
        int deductedChangeoverShiftCount = Math.max(0, theoreticalShiftCount - effectiveShiftCount);
        int effectiveCapacityQty = sumShiftCapacity(effectiveShiftCapacityMap);
        boolean hitStructureEnding = demandQty > 0 && effectiveCapacityQty >= demandQty;
        int endingDaysWithinStructureWindow = resolveStructureEndingDaysWithinWindow(
                structureShifts, effectiveShiftCapacityMap, demandQty, structureEndingDays);
        StructureEndingCapacitySnapshot snapshot = new StructureEndingCapacitySnapshot(
                sku.getMaterialCode(),
                machine.getMachineCode(),
                structureEndingDays,
                demandQty,
                Math.max(0, sku.getShiftCapacity()),
                theoreticalShiftCount,
                deductedChangeoverShiftCount,
                effectiveShiftCount,
                effectiveCapacityQty,
                endingDaysWithinStructureWindow,
                hitStructureEnding);
        log.info("结构五天内收尾评估, materialCode: {}, machineCode: {}, surplusQty: {}, shiftCapacity: {}, "
                        + "theoreticalShiftCount: {}, deductedChangeoverShiftCount: {}, effectiveShiftCount: {}, "
                        + "effectiveCapacityQty: {}, hitStructureEnding: {}",
                snapshot.getMaterialCode(), snapshot.getMachineCode(), snapshot.getDemandQty(),
                snapshot.getShiftCapacity(), snapshot.getTheoreticalShiftCount(),
                snapshot.getDeductedChangeoverShiftCount(), snapshot.getEffectiveShiftCount(),
                snapshot.getEffectiveCapacityQty(), snapshot.isHitStructureEnding());
        return snapshot;
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
     * 按指定开产时刻计算机台在剩余窗口内的实际可排产量。
     * <p>供 S4.4 单机台续作 / 换活字块场景复用，保证与结果构建阶段产能口径一致。</p>
     *
     * @param context 排程上下文
     * @param sku SKU
     * @param machine 机台
     * @param switchStartTime 切换开始时间
     * @param productionStartTime 实际开产时间
     * @param shifts 排程窗口班次
     * @return 机台在剩余窗口内的实际可排产量
     */
    public int calcMachineAvailableCapacityByStartTime(LhScheduleContext context,
                                                       SkuScheduleDTO sku,
                                                       MachineScheduleDTO machine,
                                                       Date switchStartTime,
                                                       Date productionStartTime,
                                                       List<LhShiftConfigVO> shifts) {
        return resolveActualWindowCapacity(context, sku, machine, switchStartTime, productionStartTime, shifts);
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
        return sumMachineCapacityByDate(calculateMachineAvailableCapacityByDateInWindow(
                context, sku, machine, shifts));
    }

    /**
     * 计算单台机台在窗口内按业务日拆分的可排产量。
     *
     * @param context 排程上下文
     * @param sku SKU排程DTO
     * @param machine 机台
     * @param shifts 排程窗口班次
     * @return key=业务日，value=当日可排量
     */
    private LinkedHashMap<LocalDate, Integer> calculateMachineAvailableCapacityByDateInWindow(LhScheduleContext context,
                                                                                               SkuScheduleDTO sku,
                                                                                               MachineScheduleDTO machine,
                                                                                               List<LhShiftConfigVO> shifts) {
        LinkedHashMap<LocalDate, Integer> capacityByDate = initCapacityByDate(shifts);
        LinkedHashMap<Integer, Integer> capacityByShift = calculateMachineAvailableCapacityByShiftInWindow(
                context, sku, machine, shifts, true);
        for (LhShiftConfigVO shift : shifts) {
            if (shift == null || shift.getShiftIndex() == null) {
                continue;
            }
            Integer shiftMaxQty = capacityByShift.get(shift.getShiftIndex());
            if (shiftMaxQty == null || shiftMaxQty <= 0) {
                continue;
            }
            LocalDate workDate = toLocalDate(shift.getWorkDate());
            if (workDate != null) {
                capacityByDate.merge(workDate, shiftMaxQty, Integer::sum);
            }
        }
        return capacityByDate;
    }

    /**
     * 计算单台机台在窗口内按班次拆分的可排产量。
     *
     * @param context 排程上下文
     * @param sku SKU
     * @param machine 机台
     * @param shifts 班次窗口
     * @param deductChangeover 是否扣减首次换模/换活字块时间
     * @return key=班次索引，value=班次可排量
     */
    private LinkedHashMap<Integer, Integer> calculateMachineAvailableCapacityByShiftInWindow(LhScheduleContext context,
                                                                                              SkuScheduleDTO sku,
                                                                                              MachineScheduleDTO machine,
                                                                                              List<LhShiftConfigVO> shifts,
                                                                                              boolean deductChangeover) {
        LinkedHashMap<Integer, Integer> capacityByShift = new LinkedHashMap<>(Math.max(16, shifts.size()));
        if (Objects.isNull(machine) || Objects.isNull(sku) || CollectionUtils.isEmpty(shifts)) {
            return capacityByShift;
        }
        int shiftCapacity = ShiftCapacityResolverUtil.resolveRuntimeShiftCapacity(
                context, machine, sku.getShiftCapacity());
        int lhTimeSeconds = sku.getLhTimeSeconds();
        if (shiftCapacity <= 0 && lhTimeSeconds <= 0) {
            return capacityByShift;
        }
        int mouldQty = ShiftCapacityResolverUtil.resolveMachineMouldQty(machine);
        Date machineAvailableTime = machine.getEstimatedEndTime();
        Date windowStartTime = shifts.get(0).getShiftStartDateTime();
        Date cursorStartTime = machineAvailableTime != null && machineAvailableTime.after(windowStartTime)
                ? machineAvailableTime : windowStartTime;
        if (deductChangeover && !StringUtils.equals(machine.getPreviousMaterialCode(), sku.getMaterialCode())) {
            int mouldChangeHours = LhScheduleTimeUtil.getMouldChangeTotalHours(context);
            if (mouldChangeHours > 0) {
                cursorStartTime = LhScheduleTimeUtil.addHours(cursorStartTime, mouldChangeHours);
            }
        }
        int dryIceLossQty = context.getParamIntValue(
                LhScheduleParamConstant.DRY_ICE_LOSS_QTY, LhScheduleConstant.DRY_ICE_LOSS_QTY);
        int dryIceDurationHours = context.getParamIntValue(
                LhScheduleParamConstant.DRY_ICE_DURATION_HOURS, LhScheduleConstant.DRY_ICE_DURATION_HOURS);
        boolean started = false;
        for (LhShiftConfigVO shift : shifts) {
            if (shift == null || shift.getShiftIndex() == null) {
                continue;
            }
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
            capacityByShift.put(shift.getShiftIndex(), shiftMaxQty);
            cursorStartTime = effectiveEndTime;
        }
        return capacityByShift;
    }

    /**
     * 收尾场景下调整目标排产量。
     * <p>仅在收尾判定完成后调用，非收尾SKU不应调用此方法。</p>
     * <p>单胎胚/非共用胎胚：endingTargetQty = max(embryoStock, surplusQty)。</p>
     * <p>共用胎胚：endingTargetQty = surplusQty，不按胎胚库存抬高排产目标，
     * 避免共用胎胚库存被单一SKU过量消耗。</p>
     * <p>收尾SKU的目标量不再受窗口 dayN 总量限制，也不被窗口产能压低；
     * 产能不足时由排产结果和未排结果体现剩余缺口。</p>
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

        ensureActiveEmbryoSkuMap(context, sku);
        int currentTargetQty = Math.max(0, sku.resolveTargetScheduleQty());
        int embryoStock = Math.max(0, sku.getEmbryoStock());
        int surplusQty = Math.max(0, sku.getSurplusQty());
        int windowPlanQty = Math.max(0, sku.getWindowPlanQty());

        // 共用胎胚收尾只按硫化余量排，不按胎胚库存排；单胎胚仍按 MAX(余量, 胎胚库存)
        int originalSkuCount = countOriginalSkusSharingEmbryo(context, sku);
        int activeSkuCount = countActiveSkusSharingEmbryo(context, sku);
        boolean sharedEmbryo = activeSkuCount > 1;
        int endingBaseQty;
        String qtySource;
        String unscheduledReason = "";
        if (sharedEmbryo) {
            endingBaseQty = surplusQty;
            qtySource = "共用胎胚-仅按硫化余量";
            if (surplusQty <= 0) {
                unscheduledReason = "共用胎胚收尾仅按硫化余量，余量小于等于0";
            }
        } else {
            endingBaseQty = Math.max(embryoStock, surplusQty);
            qtySource = embryoStock > surplusQty ? "单胎胚-取胎胚库存" : "单胎胚-取硫化余量";
        }
        int endingTargetQty = endingBaseQty;
        String direction = endingTargetQty > currentTargetQty ? "上调"
                : endingTargetQty < currentTargetQty ? "下调" : "保持";
        int windowRemainingPlanQty = Math.max(0, sku.getWindowRemainingPlanQty());
        log.info("收尾SKU目标量{}, materialCode: {}, 胎胚编码: {}, 原始共用SKU数: {}, "
                        + "有效共用SKU数: {}, 是否动态共用胎胚: {}, 目标量取值来源: {}, "
                        + "原目标量: {}, 调整后: {}, 窗口日计划总量: {}, 窗口日计划剩余: {}, "
                        + "胎胚库存: {}, 月计划余量: {}, 未排原因: {}",
                direction, sku.getMaterialCode(), sku.getEmbryoCode(), originalSkuCount,
                activeSkuCount, sharedEmbryo, qtySource,
                currentTargetQty, endingTargetQty,
                windowPlanQty, windowRemainingPlanQty, embryoStock, surplusQty, unscheduledReason);
        sku.setTargetScheduleQty(endingTargetQty);
        sku.setRemainingScheduleQty(endingTargetQty);
        if (endingTargetQty <= 0) {
            removeActiveEmbryoSku(context, sku, unscheduledReason);
        }
        return endingTargetQty;
    }

    /**
     * 动态判断当前SKU的胎胚在排程窗口内是否仍被多个未完成SKU共用。
     * <p>已收尾完成的SKU不参与共用胎胚判断，只统计当前仍在排程列表中的活跃SKU。</p>
     *
     * @param context 排程上下文
     * @param sku 当前SKU
     * @return true-共用胎胚（活跃SKU数 > 1）；false-单胎胚
     */
    public boolean isSharedEmbryoInWindow(LhScheduleContext context, SkuScheduleDTO sku) {
        return countActiveSkusSharingEmbryo(context, sku) > 1;
    }

    /**
     * 判断当前收尾SKU是否命中共用胎胚零余量未排规则。
     *
     * @param context 排程上下文
     * @param sku 当前SKU
     * @return true-共用胎胚收尾且硫化余量小于等于0；false-不命中
     */
    public boolean isSharedEmbryoZeroSurplusEnding(LhScheduleContext context, SkuScheduleDTO sku) {
        if (Objects.isNull(sku) || sku.getSurplusQty() > 0) {
            return false;
        }
        ensureActiveEmbryoSkuMap(context, sku);
        return isSharedEmbryoInWindow(context, sku);
    }

    /**
     * 刷新胎胚有效待排SKU集合。
     * <p>动态共用胎胚必须以本次排程仍有效参与排产的SKU为准，不再只依赖静态SKU-胎胚关系。</p>
     *
     * @param context 排程上下文
     */
    public void refreshActiveEmbryoSkuMap(LhScheduleContext context) {
        refreshActiveEmbryoSkuMap(context, null);
    }

    private void refreshActiveEmbryoSkuMap(LhScheduleContext context, SkuScheduleDTO currentSku) {
        if (Objects.isNull(context)) {
            return;
        }
        Map<String, List<String>> activeEmbryoSkuMap = new LinkedHashMap<>(8);
        List<SkuScheduleDTO> candidateSkuList = collectCandidateSkus(context);
        Set<String> unscheduledMaterialSet = collectUnscheduledMaterialSet(context);
        Set<String> completedEndingMaterialSet = collectCompletedEndingMaterialSet(context);
        for (SkuScheduleDTO candidateSku : candidateSkuList) {
            if (!isActiveEmbryoSku(candidateSku, currentSku, unscheduledMaterialSet, completedEndingMaterialSet)) {
                continue;
            }
            List<String> activeSkuList = activeEmbryoSkuMap.computeIfAbsent(
                    candidateSku.getEmbryoCode(), key -> new ArrayList<String>(4));
            if (!activeSkuList.contains(candidateSku.getMaterialCode())) {
                activeSkuList.add(candidateSku.getMaterialCode());
            }
        }
        context.setActiveEmbryoSkuMap(activeEmbryoSkuMap);
    }

    /**
     * 确保胎胚有效SKU集合可用。
     * <p>运行过程中 activeEmbryoSkuMap 会按未排、目标量满足等动作增量维护；
     * 因此收尾目标量计算前只在集合为空时重建，避免把当前仍需按初始有效集合判断的共用关系提前刷掉。</p>
     *
     * @param context 排程上下文
     * @param currentSku 当前SKU
     */
    private void ensureActiveEmbryoSkuMap(LhScheduleContext context, SkuScheduleDTO currentSku) {
        if (Objects.isNull(context) || !CollectionUtils.isEmpty(context.getActiveEmbryoSkuMap())) {
            return;
        }
        refreshActiveEmbryoSkuMap(context, currentSku);
    }

    /**
     * 统计当前排程窗口内与指定SKU共用同一胎胚的未完成SKU数量。
     * <p>从续作列表和新增列表中收集同胎胚编码的SKU，已收尾完成的SKU不会被计入。</p>
     *
     * @param context 排程上下文
     * @param sku 当前SKU
     * @return 共用该胎胚的未完成SKU数（含自身）
     */
    private int countActiveSkusSharingEmbryo(LhScheduleContext context, SkuScheduleDTO sku) {
        if (Objects.isNull(context) || Objects.isNull(sku) || StringUtils.isEmpty(sku.getEmbryoCode())) {
            return 0;
        }
        if (CollectionUtils.isEmpty(context.getActiveEmbryoSkuMap())) {
            refreshActiveEmbryoSkuMap(context);
        }
        List<String> activeSkuList = context.getActiveEmbryoSkuMap().get(sku.getEmbryoCode());
        return CollectionUtils.isEmpty(activeSkuList) ? 0 : activeSkuList.size();
    }

    /**
     * 统计同胎胚原始SKU数量。
     * <p>用于日志呈现静态关系下的共用数量，动态判断仍以 activeEmbryoSkuMap 为准。</p>
     *
     * @param context 排程上下文
     * @param sku 当前SKU
     * @return 原始同胎胚SKU数
     */
    private int countOriginalSkusSharingEmbryo(LhScheduleContext context, SkuScheduleDTO sku) {
        if (Objects.isNull(context) || Objects.isNull(sku) || StringUtils.isEmpty(sku.getEmbryoCode())) {
            return 0;
        }
        Set<String> materialSet = new HashSet<>(8);
        for (SkuScheduleDTO candidateSku : collectCandidateSkus(context)) {
            if (candidateSku != null && StringUtils.equals(sku.getEmbryoCode(), candidateSku.getEmbryoCode())
                    && StringUtils.isNotEmpty(candidateSku.getMaterialCode())) {
                materialSet.add(candidateSku.getMaterialCode());
            }
        }
        return materialSet.size();
    }

    /**
     * 从胎胚有效集合中移除当前SKU。
     * <p>收尾目标量为0、进入未排或已满足目标量后，该SKU不再参与后续SKU的共用胎胚判断。</p>
     *
     * @param context 排程上下文
     * @param sku 当前SKU
     * @param reason 移除原因
     */
    public void removeActiveEmbryoSku(LhScheduleContext context, SkuScheduleDTO sku, String reason) {
        if (Objects.isNull(context) || Objects.isNull(sku)
                || StringUtils.isEmpty(sku.getEmbryoCode()) || StringUtils.isEmpty(sku.getMaterialCode())) {
            return;
        }
        if (CollectionUtils.isEmpty(context.getActiveEmbryoSkuMap())) {
            refreshActiveEmbryoSkuMap(context);
        }
        List<String> activeSkuList = context.getActiveEmbryoSkuMap().get(sku.getEmbryoCode());
        if (CollectionUtils.isEmpty(activeSkuList)) {
            return;
        }
        activeSkuList.remove(sku.getMaterialCode());
        if (activeSkuList.isEmpty()) {
            context.getActiveEmbryoSkuMap().remove(sku.getEmbryoCode());
        }
        log.info("刷新胎胚有效SKU集合, materialCode: {}, embryoCode: {}, 剩余有效SKU数: {}, 原因: {}",
                sku.getMaterialCode(), sku.getEmbryoCode(),
                CollectionUtils.isEmpty(activeSkuList) ? 0 : activeSkuList.size(), reason);
    }

    private List<SkuScheduleDTO> collectCandidateSkus(LhScheduleContext context) {
        List<SkuScheduleDTO> candidateSkuList = new ArrayList<>(16);
        if (!CollectionUtils.isEmpty(context.getContinuousSkuList())) {
            candidateSkuList.addAll(context.getContinuousSkuList());
        }
        if (!CollectionUtils.isEmpty(context.getNewSpecSkuList())) {
            candidateSkuList.addAll(context.getNewSpecSkuList());
        }
        if (candidateSkuList.isEmpty() && !CollectionUtils.isEmpty(context.getStructureSkuMap())) {
            for (List<SkuScheduleDTO> skuList : context.getStructureSkuMap().values()) {
                if (!CollectionUtils.isEmpty(skuList)) {
                    candidateSkuList.addAll(skuList);
                }
            }
        }
        return candidateSkuList;
    }

    private boolean isActiveEmbryoSku(SkuScheduleDTO sku,
                                      SkuScheduleDTO currentSku,
                                      Set<String> unscheduledMaterialSet,
                                      Set<String> completedEndingMaterialSet) {
        if (sku == null || StringUtils.isEmpty(sku.getMaterialCode())
                || StringUtils.isEmpty(sku.getEmbryoCode())) {
            return false;
        }
        if (Objects.nonNull(currentSku) && sku != currentSku && sku.resolveTargetScheduleQty() <= 0) {
            return false;
        }
        if (unscheduledMaterialSet.contains(sku.getMaterialCode())) {
            return false;
        }
        return !completedEndingMaterialSet.contains(sku.getMaterialCode());
    }

    private Set<String> collectUnscheduledMaterialSet(LhScheduleContext context) {
        Set<String> materialSet = new HashSet<>(8);
        if (CollectionUtils.isEmpty(context.getUnscheduledResultList())) {
            return materialSet;
        }
        context.getUnscheduledResultList().forEach(result -> {
            if (result != null && StringUtils.isNotEmpty(result.getMaterialCode())) {
                materialSet.add(result.getMaterialCode());
            }
        });
        return materialSet;
    }

    private Set<String> collectCompletedEndingMaterialSet(LhScheduleContext context) {
        Set<String> materialSet = new HashSet<>(8);
        if (CollectionUtils.isEmpty(context.getScheduleResultList())) {
            return materialSet;
        }
        for (LhScheduleResult result : context.getScheduleResultList()) {
            if (result != null && "1".equals(result.getIsEnd())
                    && StringUtils.isNotEmpty(result.getMaterialCode())) {
                materialSet.add(result.getMaterialCode());
            }
        }
        return materialSet;
    }

    /**
     * 获取机台匹配策略（带空安全回退）。
     *
     * @return 机台匹配策略
     */
    private IMachineMatchStrategy getMachineMatchStrategy() {
        return machineMatchStrategy;
    }

    private LinkedHashMap<LocalDate, Integer> initCapacityByDate(List<LhShiftConfigVO> shifts) {
        LinkedHashMap<LocalDate, Integer> capacityByDate = new LinkedHashMap<LocalDate, Integer>(8);
        if (CollectionUtils.isEmpty(shifts)) {
            return capacityByDate;
        }
        for (LhShiftConfigVO shift : shifts) {
            LocalDate workDate = toLocalDate(shift.getWorkDate());
            if (workDate != null && !capacityByDate.containsKey(workDate)) {
                capacityByDate.put(workDate, 0);
            }
        }
        return capacityByDate;
    }

    private int sumMachineCapacityByDate(Map<LocalDate, Integer> capacityByDate) {
        int totalQty = 0;
        if (capacityByDate == null || capacityByDate.isEmpty()) {
            return totalQty;
        }
        for (Integer capacityQty : capacityByDate.values()) {
            totalQty += Math.max(0, capacityQty == null ? 0 : capacityQty);
        }
        return Math.max(totalQty, 0);
    }

    private int sumShiftCapacity(Map<Integer, Integer> capacityByShift) {
        int totalQty = 0;
        if (capacityByShift == null || capacityByShift.isEmpty()) {
            return totalQty;
        }
        for (Integer capacityQty : capacityByShift.values()) {
            totalQty += Math.max(0, capacityQty == null ? 0 : capacityQty);
        }
        return Math.max(totalQty, 0);
    }

    private int countEffectiveShiftCount(Map<Integer, Integer> capacityByShift) {
        int shiftCount = 0;
        if (capacityByShift == null || capacityByShift.isEmpty()) {
            return shiftCount;
        }
        for (Integer capacityQty : capacityByShift.values()) {
            if (capacityQty != null && capacityQty > 0) {
                shiftCount++;
            }
        }
        return shiftCount;
    }

    private int resolveStructureEndingDays(LhScheduleContext context) {
        if (context == null || context.getScheduleConfig() == null) {
            return LhScheduleConstant.DEFAULT_STRUCTURE_ENDING_DAYS;
        }
        return Math.max(1, context.getScheduleConfig().getStructureEndingDays());
    }

    private int resolveStructureEndingDemandQty(SkuScheduleDTO sku) {
        if (sku == null) {
            return 0;
        }
        return Math.max(0, sku.getSurplusQty());
    }

    private List<LhShiftConfigVO> resolveStructurePriorityShifts(LhScheduleContext context, int structureEndingDays) {
        List<LhShiftConfigVO> baseShifts = resolveScheduleShifts(context);
        if (CollectionUtils.isEmpty(baseShifts)) {
            return new ArrayList<>(0);
        }
        int targetDays = Math.max(1, structureEndingDays);
        int currentDays = resolveCoveredDays(baseShifts);
        if (currentDays >= targetDays) {
            return new ArrayList<>(baseShifts);
        }
        List<LhShiftConfigVO> extendedShifts = new ArrayList<>(baseShifts.size() + (targetDays - currentDays) * 3);
        extendedShifts.addAll(baseShifts);
        List<LhShiftConfigVO> templateDayShifts = collectShiftsByOffset(baseShifts, currentDays - 1);
        if (CollectionUtils.isEmpty(templateDayShifts)) {
            return extendedShifts;
        }
        int nextShiftIndex = baseShifts.get(baseShifts.size() - 1).getShiftIndex() == null
                ? baseShifts.size() + 1 : baseShifts.get(baseShifts.size() - 1).getShiftIndex() + 1;
        for (int offset = currentDays; offset < targetDays; offset++) {
            for (LhShiftConfigVO templateShift : templateDayShifts) {
                extendedShifts.add(cloneShiftForOffset(templateShift, offset, nextShiftIndex++));
            }
        }
        return extendedShifts;
    }

    private int resolveCoveredDays(List<LhShiftConfigVO> shifts) {
        int maxOffset = -1;
        for (LhShiftConfigVO shift : shifts) {
            if (shift != null && shift.getDateOffset() != null) {
                maxOffset = Math.max(maxOffset, shift.getDateOffset());
            }
        }
        return maxOffset + 1;
    }

    private List<LhShiftConfigVO> collectShiftsByOffset(List<LhShiftConfigVO> shifts, int dateOffset) {
        List<LhShiftConfigVO> templateDayShifts = new ArrayList<>(4);
        for (LhShiftConfigVO shift : shifts) {
            if (shift != null && shift.getDateOffset() != null && shift.getDateOffset() == dateOffset) {
                templateDayShifts.add(shift);
            }
        }
        return templateDayShifts;
    }

    private LhShiftConfigVO cloneShiftForOffset(LhShiftConfigVO templateShift, int dateOffset, int shiftIndex) {
        LhShiftConfigVO clonedShift = new LhShiftConfigVO();
        clonedShift.setScheduleBaseDate(templateShift.getScheduleBaseDate());
        clonedShift.setShiftType(templateShift.getShiftType());
        clonedShift.setShiftCode(templateShift.getShiftCode());
        clonedShift.setStartTime(templateShift.getStartTime());
        clonedShift.setEndTime(templateShift.getEndTime());
        clonedShift.setShiftDuration(templateShift.getShiftDuration());
        clonedShift.setDateOffset(dateOffset);
        clonedShift.setShiftIndex(shiftIndex);
        ShiftEnum shiftType = templateShift.resolveShiftTypeEnum();
        if (shiftType != null) {
            clonedShift.setShiftName(buildStructureShiftName(dateOffset, shiftType));
        } else {
            clonedShift.setShiftName(templateShift.getShiftName());
        }
        return clonedShift;
    }

    private String buildStructureShiftName(int dateOffset, ShiftEnum shiftType) {
        String prefix = dateOffset == 0 ? "T日" : "T+" + dateOffset + "日";
        return prefix + shiftType.getDescription();
    }

    private int resolveStructureEndingDaysWithinWindow(List<LhShiftConfigVO> shifts,
                                                       Map<Integer, Integer> effectiveShiftCapacityMap,
                                                       int demandQty,
                                                       int structureEndingDays) {
        if (demandQty <= 0) {
            return 0;
        }
        LinkedHashMap<LocalDate, Integer> capacityByDate = new LinkedHashMap<>(16);
        for (LhShiftConfigVO shift : shifts) {
            if (shift == null || shift.getShiftIndex() == null) {
                continue;
            }
            Integer shiftQty = effectiveShiftCapacityMap.get(shift.getShiftIndex());
            if (shiftQty == null || shiftQty <= 0) {
                continue;
            }
            LocalDate workDate = toLocalDate(shift.getWorkDate());
            if (workDate != null) {
                capacityByDate.merge(workDate, shiftQty, Integer::sum);
            }
        }
        int cumulativeCapacity = 0;
        int endingDays = 0;
        for (Integer dayQty : capacityByDate.values()) {
            endingDays++;
            cumulativeCapacity += Math.max(0, dayQty == null ? 0 : dayQty);
            if (cumulativeCapacity >= demandQty) {
                return endingDays;
            }
        }
        return Math.max(1, structureEndingDays) + 1;
    }

    private LocalDate toLocalDate(Date workDate) {
        if (workDate == null) {
            return null;
        }
        return workDate.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
    }

    /**
     * 结构收尾有效产能评估快照。
     */
    public static final class StructureEndingCapacitySnapshot {

        private final String materialCode;
        private final String machineCode;
        private final int structureEndingDays;
        private final int demandQty;
        private final int shiftCapacity;
        private final int theoreticalShiftCount;
        private final int deductedChangeoverShiftCount;
        private final int effectiveShiftCount;
        private final int effectiveCapacityQty;
        private final int endingDaysWithinStructureWindow;
        private final boolean hitStructureEnding;

        private StructureEndingCapacitySnapshot(String materialCode,
                                               String machineCode,
                                               int structureEndingDays,
                                               int demandQty,
                                               int shiftCapacity,
                                               int theoreticalShiftCount,
                                               int deductedChangeoverShiftCount,
                                               int effectiveShiftCount,
                                               int effectiveCapacityQty,
                                               int endingDaysWithinStructureWindow,
                                               boolean hitStructureEnding) {
            this.materialCode = materialCode;
            this.machineCode = machineCode;
            this.structureEndingDays = structureEndingDays;
            this.demandQty = demandQty;
            this.shiftCapacity = shiftCapacity;
            this.theoreticalShiftCount = theoreticalShiftCount;
            this.deductedChangeoverShiftCount = deductedChangeoverShiftCount;
            this.effectiveShiftCount = effectiveShiftCount;
            this.effectiveCapacityQty = effectiveCapacityQty;
            this.endingDaysWithinStructureWindow = endingDaysWithinStructureWindow;
            this.hitStructureEnding = hitStructureEnding;
        }

        public static StructureEndingCapacitySnapshot empty(int structureEndingDays) {
            return new StructureEndingCapacitySnapshot(
                    null, null, structureEndingDays, 0, 0, 0, 0, 0, 0, structureEndingDays + 1, false);
        }

        public String getMaterialCode() {
            return materialCode;
        }

        public String getMachineCode() {
            return machineCode;
        }

        public int getStructureEndingDays() {
            return structureEndingDays;
        }

        public int getDemandQty() {
            return demandQty;
        }

        public int getShiftCapacity() {
            return shiftCapacity;
        }

        public int getTheoreticalShiftCount() {
            return theoreticalShiftCount;
        }

        public int getDeductedChangeoverShiftCount() {
            return deductedChangeoverShiftCount;
        }

        public int getEffectiveShiftCount() {
            return effectiveShiftCount;
        }

        public int getEffectiveCapacityQty() {
            return effectiveCapacityQty;
        }

        public int getEndingDaysWithinStructureWindow() {
            return endingDaysWithinStructureWindow;
        }

        public boolean isHitStructureEnding() {
            return hitStructureEnding;
        }
    }
}
