package com.zlt.aps.lh.util;

import com.zlt.aps.lh.api.domain.dto.MachineCleaningWindowDTO;
import com.zlt.aps.lh.api.domain.dto.MachineMaintenanceWindowDTO;
import com.zlt.aps.lh.api.domain.dto.MachineScheduleDTO;
import com.zlt.aps.lh.api.domain.vo.LhShiftConfigVO;
import com.zlt.aps.lh.api.constant.LhScheduleConstant;
import com.zlt.aps.lh.api.enums.ScheduleTypeEnum;
import com.zlt.aps.lh.api.enums.ShiftEnum;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.api.enums.CleaningTypeEnum;
import com.zlt.aps.lh.api.enums.MachineStopTypeEnum;
import com.zlt.aps.mdm.api.domain.entity.MdmDevicePlanShut;
import com.zlt.aps.mdm.api.domain.entity.MdmSkuLhCapacity;
import org.apache.commons.lang3.StringUtils;
import org.springframework.util.CollectionUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 班次产能解析工具。
 * <p>统一处理机台模台数、整班/残班产能折算、停机清洗扣减，以及完工时间顺延等时间口径。</p>
 * <p>本类的核心职责是把“时间窗”换算成“可排量”或“完工时刻”，避免各策略类重复实现秒级计算。</p>
 *
 * @author APS
 */
public final class ShiftCapacityResolverUtil {

    /** 默认模台数。主数据缺失时按单模处理，避免直接算出 0 导致整条结果不可排。 */
    private static final int DEFAULT_MOULD_QTY = 1;
    /** 每分钟秒数 */
    private static final int SECONDS_PER_MINUTE = 60;
    /** 每小时秒数 */
    private static final int SECONDS_PER_HOUR = 3600;
    /** 单控拆分机台固定按左右两侧拆分班产 */
    private static final int SINGLE_CONTROL_SPLIT_SIDE_COUNT = 2;
    /** 晚班 +1 配置值 */
    private static final int NIGHT_PLUS_SHIFT_TYPE = 1;
    /** 早班 +1 配置值 */
    private static final int MORNING_PLUS_SHIFT_TYPE = 2;
    /** 中班 +1 配置值 */
    private static final int AFTERNOON_PLUS_SHIFT_TYPE = 3;

    private ShiftCapacityResolverUtil() {
    }

    /**
     * 解析机台模台数。
     *
     * @param machine 机台
     * @return 模台数，缺失时返回默认值 1
     */
    public static int resolveMachineMouldQty(MachineScheduleDTO machine) {
        if (Objects.isNull(machine)) {
            return DEFAULT_MOULD_QTY;
        }
        return resolveMachineMouldQty(machine.getMaxMoldNum());
    }

    /**
     * 解析机台模台数。
     *
     * @param maxMoldNum 机台最大模台数
     * @return 模台数，缺失时返回默认值 1
     */
    public static int resolveMachineMouldQty(int maxMoldNum) {
        return maxMoldNum > 0 ? maxMoldNum : DEFAULT_MOULD_QTY;
    }

    /**
     * 解析运行态机台班产。
     * <p>单控基准机台会在运行态拆成左右两台单模机台，SKU主数据班产仍是整机口径，
     * 因此拆分侧别后需要按左右两侧均分，并向下取整。</p>
     *
     * @param context 排程上下文
     * @param machine 运行态机台
     * @param shiftCapacity 班产
     * @return 运行态可用班产
     */
    public static int resolveRuntimeShiftCapacity(LhScheduleContext context,
                                                  MachineScheduleDTO machine,
                                                  int shiftCapacity) {
        if (shiftCapacity <= 0
                || Objects.isNull(context)
                || Objects.isNull(machine)
                || StringUtils.isEmpty(machine.getMachineCode())
                || !LhSingleControlMachineUtil.isSingleMouldMachine(machine.getMachineCode())) {
            return shiftCapacity;
        }
        return shiftCapacity / SINGLE_CONTROL_SPLIT_SIDE_COUNT;
    }

    /**
     * 解析奇数班产修正后的当前班次计划量。
     * <p>仅新增、续作、换活字块显式传入排程类型时启用；参数未配置、非法、班产为偶数时保持原值。</p>
     *
     * @param baseCapacity 原始班产
     * @param shift 当前班次
     * @param configPlusShiftType 配置的加一班别，1-晚班，2-早班，3-中班
     * @param scheduleType 排程类型
     * @return 当前班次实际计划量
     */
    public static int resolveActualShiftPlanQty(int baseCapacity,
                                                LhShiftConfigVO shift,
                                                String configPlusShiftType,
                                                String scheduleType) {
        if (!isOddShiftCapacityAdjustEnabled(baseCapacity, shift, configPlusShiftType, scheduleType)) {
            return baseCapacity;
        }
        int plusShiftType = Integer.parseInt(configPlusShiftType.trim());
        Integer currentShiftType = resolveShiftTypeValue(shift.resolveShiftTypeEnum());
        if (Objects.isNull(currentShiftType)) {
            return baseCapacity;
        }
        return currentShiftType == plusShiftType ? baseCapacity + 1 : baseCapacity - 1;
    }

    /**
     * 按班次列表汇总修正后的单机窗口理论产能。
     *
     * @param shifts 班次列表
     * @param baseCapacity 原始班产
     * @param configPlusShiftType 配置的加一班别
     * @param scheduleType 排程类型
     * @return 理论产能合计
     */
    public static int sumActualShiftPlanQty(List<LhShiftConfigVO> shifts,
                                            int baseCapacity,
                                            String configPlusShiftType,
                                            String scheduleType) {
        if (CollectionUtils.isEmpty(shifts)) {
            return 0;
        }
        int totalQty = 0;
        for (LhShiftConfigVO shift : shifts) {
            totalQty += Math.max(0, resolveActualShiftPlanQty(
                    baseCapacity, shift, configPlusShiftType, scheduleType));
        }
        return Math.max(0, totalQty);
    }

    /**
     * 按业务日汇总修正后的单机理论产能。
     *
     * @param shifts 班次列表
     * @param baseCapacity 原始班产
     * @param configPlusShiftType 配置的加一班别
     * @param scheduleType 排程类型
     * @return key=业务日，value=当日理论产能
     */
    public static Map<LocalDate, Integer> sumActualShiftPlanQtyByWorkDate(List<LhShiftConfigVO> shifts,
                                                                          int baseCapacity,
                                                                          String configPlusShiftType,
                                                                          String scheduleType) {
        Map<LocalDate, Integer> capacityMap = new LinkedHashMap<LocalDate, Integer>(
                CollectionUtils.isEmpty(shifts) ? 0 : shifts.size());
        if (CollectionUtils.isEmpty(shifts)) {
            return capacityMap;
        }
        for (LhShiftConfigVO shift : shifts) {
            if (Objects.isNull(shift) || Objects.isNull(shift.getWorkDate())) {
                continue;
            }
            LocalDate workDate = shift.getWorkDate().toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
            int shiftPlanQty = resolveActualShiftPlanQty(
                    baseCapacity, shift, configPlusShiftType, scheduleType);
            capacityMap.merge(workDate, Math.max(0, shiftPlanQty), Integer::sum);
        }
        return capacityMap;
    }

    /**
     * 按日标准产量修正当前班次计划量。
     * <p>仅普通机台的新增、续作、换活字块流程启用；单控机台保持原计划量不变。</p>
     *
     * @param dailyStandardQty SKU日标准产量
     * @param classCapacity 原始班产
     * @param currentShiftPlanQty 当前班次原计划量
     * @param currentShift 当前班次
     * @param remainShiftType 剩余班次配置，1-晚班，2-早班，3-中班
     * @param sameDayShiftPlanQtyMap 同一业务日班次计划量，key=班次序号，value=计划量
     * @param singleControlMachine 是否单控机台
     * @param scheduleType 排程类型
     * @return 修正后的当前班次计划量
     */
    public static int calculateShiftPlanQtyByDailyStandard(int dailyStandardQty,
                                                           int classCapacity,
                                                           int currentShiftPlanQty,
                                                           LhShiftConfigVO currentShift,
                                                           String remainShiftType,
                                                           Map<Integer, Integer> sameDayShiftPlanQtyMap,
                                                           boolean singleControlMachine,
                                                           String scheduleType) {
        return calculateShiftPlanQtyByDailyStandard(
                dailyStandardQty, classCapacity, classCapacity, currentShiftPlanQty, currentShift,
                remainShiftType, sameDayShiftPlanQtyMap, singleControlMachine, scheduleType);
    }

    /**
     * 按日标准产量修正当前班次计划量，并使用独立的剩余班次理论上限约束公式结果。
     * <p>日标准产量仍只取 {@link MdmSkuLhCapacity#getStandardCapacity()}；当日标准量高于
     * {@code 班产 × 3} 时，剩余班次可在 APS 日产折算的单班理论上限内补足差额，
     * 非剩余班次和班产落库字段继续保持原班产语义。</p>
     *
     * @param dailyStandardQty SKU日标准产量
     * @param classCapacity 原始班产
     * @param remainShiftCapacityUpperLimit 剩余班次理论上限
     * @param currentShiftPlanQty 当前班次原计划量
     * @param currentShift 当前班次
     * @param remainShiftType 剩余班次配置，1-晚班，2-早班，3-中班
     * @param sameDayShiftPlanQtyMap 同一业务日班次计划量，key=班次序号，value=计划量
     * @param singleControlMachine 是否单控机台
     * @param scheduleType 排程类型
     * @return 修正后的当前班次计划量
     */
    public static int calculateShiftPlanQtyByDailyStandard(int dailyStandardQty,
                                                           int classCapacity,
                                                           int remainShiftCapacityUpperLimit,
                                                           int currentShiftPlanQty,
                                                           LhShiftConfigVO currentShift,
                                                           String remainShiftType,
                                                           Map<Integer, Integer> sameDayShiftPlanQtyMap,
                                                           boolean singleControlMachine,
                                                           String scheduleType) {
        if (singleControlMachine || !isSupportedScheduleType(scheduleType)) {
            return currentShiftPlanQty;
        }
        if (classCapacity <= 0 || currentShiftPlanQty <= 0 || Objects.isNull(currentShift)
                || !isValidPlusShiftType(remainShiftType)) {
            return Math.max(0, Math.min(currentShiftPlanQty, classCapacity));
        }
        int currentPlanQty = Math.min(currentShiftPlanQty, classCapacity);
        Integer currentShiftType = resolveShiftTypeValue(currentShift.resolveShiftTypeEnum());
        if (Objects.isNull(currentShiftType) || currentShiftType != Integer.parseInt(remainShiftType.trim())) {
            return currentPlanQty;
        }
        if (dailyStandardQty <= 0) {
            return currentPlanQty;
        }
        boolean continuousSchedule = StringUtils.equals(ScheduleTypeEnum.CONTINUOUS.getCode(), scheduleType);
        int otherShiftPlanQty = sumOtherShiftPlanQty(
                currentShift, sameDayShiftPlanQtyMap, classCapacity, continuousSchedule);
        if (!continuousSchedule
                && !isOtherShiftPlanQtyFull(currentShift, sameDayShiftPlanQtyMap, classCapacity)) {
            return currentPlanQty;
        }
        int remainderQty = dailyStandardQty - otherShiftPlanQty;
        if (remainderQty <= 0) {
            /*
             * 日标准产量只修正剩余班次的计划量，不作为已上机 SKU 是否可继续生产的硬阻断。
             * 当其他班次计划量已经覆盖或超过日标准量时，当前班次仍有物理产能，不能被裁成 0 后形成中间空班。
             */
            return currentPlanQty;
        }
        int formulaUpperLimit = Math.max(classCapacity, remainShiftCapacityUpperLimit);
        if (remainderQty > formulaUpperLimit) {
            return currentPlanQty;
        }
        if (remainderQty > classCapacity) {
            // 日标准量高于“班产×3”时，仅剩余班次可在独立理论上限内补足差额。
            return remainderQty;
        }
        if (continuousSchedule) {
            // 续作剩余班次按日标准产量公式取值，既允许向下回裁，也允许补足原结果中的残班。
            return remainderQty;
        }
        return Math.min(remainderQty, currentPlanQty);
    }

    /**
     * 按日标准产量批量修正同一窗口内的班次计划量。
     *
     * @param shifts 班次列表
     * @param rawShiftPlanQtyMap 原班次计划量，key=班次序号
     * @param dailyStandardQty SKU日标准产量
     * @param classCapacity 原始班产
     * @param remainShiftType 剩余班次配置
     * @param singleControlMachine 是否单控机台
     * @param scheduleType 排程类型
     * @return 修正后的班次计划量
     */
    public static Map<Integer, Integer> adjustShiftPlanQtyMapByDailyStandard(List<LhShiftConfigVO> shifts,
                                                                             Map<Integer, Integer> rawShiftPlanQtyMap,
                                                                             int dailyStandardQty,
                                                                             int classCapacity,
                                                                             String remainShiftType,
                                                                             boolean singleControlMachine,
                                                                             String scheduleType) {
        return adjustShiftPlanQtyMapByDailyStandard(
                shifts, rawShiftPlanQtyMap, dailyStandardQty, classCapacity, classCapacity,
                remainShiftType, singleControlMachine, scheduleType);
    }

    /**
     * 按日标准产量批量修正同一窗口内的班次计划量，并独立约束剩余班次理论上限。
     *
     * @param shifts 班次列表
     * @param rawShiftPlanQtyMap 原班次计划量，key=班次序号
     * @param dailyStandardQty SKU日标准产量
     * @param classCapacity 原始班产
     * @param remainShiftCapacityUpperLimit 剩余班次理论上限
     * @param remainShiftType 剩余班次配置
     * @param singleControlMachine 是否单控机台
     * @param scheduleType 排程类型
     * @return 修正后的班次计划量
     */
    public static Map<Integer, Integer> adjustShiftPlanQtyMapByDailyStandard(List<LhShiftConfigVO> shifts,
                                                                             Map<Integer, Integer> rawShiftPlanQtyMap,
                                                                             int dailyStandardQty,
                                                                             int classCapacity,
                                                                             int remainShiftCapacityUpperLimit,
                                                                             String remainShiftType,
                                                                             boolean singleControlMachine,
                                                                             String scheduleType) {
        return adjustShiftPlanQtyMapByDailyStandard(
                shifts, rawShiftPlanQtyMap, null, dailyStandardQty, classCapacity,
                remainShiftCapacityUpperLimit, remainShiftType, singleControlMachine, scheduleType);
    }

    /**
     * 按日标准产量批量修正班次计划量，并使用独立的剩余班次可排产能图。
     * <p>最终结果收敛时，原结果量可能只是目标残量而不是物理上限；调用方应传入按停机、
     * 清洗、保养和班次管控重新计算的剩余班次可排产能，避免把真实受限班次误补满。</p>
     *
     * @param shifts 班次列表
     * @param rawShiftPlanQtyMap 原班次计划量，其他班次公式取值以此为准
     * @param remainShiftCapacityMap 剩余班次可排产能图
     * @param dailyStandardQty SKU日标准产量
     * @param classCapacity 原始班产
     * @param remainShiftCapacityUpperLimit 剩余班次理论上限
     * @param remainShiftType 剩余班次配置
     * @param singleControlMachine 是否单控机台
     * @param scheduleType 排程类型
     * @return 修正后的班次计划量
     */
    public static Map<Integer, Integer> adjustShiftPlanQtyMapByDailyStandard(
            List<LhShiftConfigVO> shifts,
            Map<Integer, Integer> rawShiftPlanQtyMap,
            Map<Integer, Integer> remainShiftCapacityMap,
            int dailyStandardQty,
            int classCapacity,
            int remainShiftCapacityUpperLimit,
            String remainShiftType,
            boolean singleControlMachine,
            String scheduleType) {
        Map<Integer, Integer> adjustedMap = new LinkedHashMap<Integer, Integer>(
                CollectionUtils.isEmpty(rawShiftPlanQtyMap) ? 0 : rawShiftPlanQtyMap.size());
        if (CollectionUtils.isEmpty(rawShiftPlanQtyMap)) {
            return adjustedMap;
        }
        if (CollectionUtils.isEmpty(shifts)) {
            adjustedMap.putAll(rawShiftPlanQtyMap);
            return adjustedMap;
        }
        Map<LocalDate, Map<Integer, Integer>> sameDayPlanQtyMap = buildSameDayShiftPlanQtyMap(shifts, rawShiftPlanQtyMap);
        for (LhShiftConfigVO shift : shifts) {
            if (Objects.isNull(shift) || !rawShiftPlanQtyMap.containsKey(shift.getShiftIndex())) {
                continue;
            }
            LocalDate workDate = resolveWorkDate(shift);
            Map<Integer, Integer> currentDayPlanQtyMap = Objects.isNull(workDate)
                    ? rawShiftPlanQtyMap : sameDayPlanQtyMap.get(workDate);
            int currentPlanQty = rawShiftPlanQtyMap.get(shift.getShiftIndex()) == null
                    ? 0 : rawShiftPlanQtyMap.get(shift.getShiftIndex());
            boolean remainShift = isDailyStandardRemainShift(shift, remainShiftType);
            if (dailyStandardQty > 0 && remainShift
                    && !CollectionUtils.isEmpty(remainShiftCapacityMap)
                    && remainShiftCapacityMap.containsKey(shift.getShiftIndex())) {
                Integer remainShiftCapacity = remainShiftCapacityMap.get(shift.getShiftIndex());
                currentPlanQty = Objects.isNull(remainShiftCapacity)
                        ? currentPlanQty : Math.max(0, remainShiftCapacity);
            }
            int adjustedQty = calculateShiftPlanQtyByDailyStandard(
                    dailyStandardQty, classCapacity, remainShiftCapacityUpperLimit,
                    currentPlanQty, shift, remainShiftType,
                    currentDayPlanQtyMap, singleControlMachine, scheduleType);
            if (dailyStandardQty > 0 && remainShift
                    && !CollectionUtils.isEmpty(remainShiftCapacityMap)
                    && remainShiftCapacityMap.containsKey(shift.getShiftIndex())) {
                Integer remainShiftCapacity = remainShiftCapacityMap.get(shift.getShiftIndex());
                if (Objects.nonNull(remainShiftCapacity)) {
                    adjustedQty = Math.min(adjustedQty, Math.max(0, remainShiftCapacity));
                }
            }
            adjustedMap.put(shift.getShiftIndex(), adjustedQty);
        }
        return adjustedMap;
    }

    /**
     * 判断当前班次是否为日标准产量剩余班次。
     *
     * @param shift 当前班次
     * @param remainShiftType 剩余班次配置
     * @return true-当前班次承担日标准产量余量；false-否
     */
    public static boolean isDailyStandardRemainShift(LhShiftConfigVO shift, String remainShiftType) {
        if (Objects.isNull(shift) || !isValidPlusShiftType(remainShiftType)) {
            return false;
        }
        Integer currentShiftType = resolveShiftTypeValue(shift.resolveShiftTypeEnum());
        return Objects.nonNull(currentShiftType)
                && currentShiftType == Integer.parseInt(remainShiftType.trim());
    }

    /**
     * 解析SKU日标准产量。
     * <p>日标准产量修正规则只使用 SKU 日硫化产能主数据的 STANDARD_CAPACITY，不复用 APS 计算日产能。</p>
     *
     * @param context 排程上下文
     * @param materialCode 物料编码
     * @return SKU日标准产量
     */
    public static int resolveDailyStandardQty(LhScheduleContext context, String materialCode) {
        if (Objects.isNull(context) || StringUtils.isEmpty(materialCode)) {
            return 0;
        }
        return resolveDailyStandardQty(context.getSkuLhCapacityMap().get(materialCode));
    }

    /**
     * 解析SKU日标准产量。
     *
     * @param capacity SKU日硫化产能主数据
     * @return SKU日标准产量
     */
    public static int resolveDailyStandardQty(MdmSkuLhCapacity capacity) {
        if (Objects.isNull(capacity) || Objects.isNull(capacity.getStandardCapacity())) {
            return 0;
        }
        return Math.max(0, capacity.getStandardCapacity());
    }

    /**
     * 解析日标准产量剩余班次的理论上限。
     * <p>日标准量负责确定业务日目标，APS 日产只用于约束剩余班次最多可补到多少，
     * 两者不得混用。主数据缺失或 APS 日产不足时保持原班产上限。</p>
     *
     * @param context 排程上下文
     * @param materialCode 物料编码
     * @param classCapacity 原始班产
     * @return 剩余班次理论上限
     */
    public static int resolveDailyStandardRemainShiftCapacityUpperLimit(LhScheduleContext context,
                                                                         String materialCode,
                                                                         int classCapacity) {
        int baseCapacity = Math.max(0, classCapacity);
        if (Objects.isNull(context) || StringUtils.isEmpty(materialCode)) {
            return baseCapacity;
        }
        MdmSkuLhCapacity capacity = context.getSkuLhCapacityMap().get(materialCode);
        if (Objects.isNull(capacity) || Objects.isNull(capacity.getApsCapacity())
                || capacity.getApsCapacity() <= 0) {
            return baseCapacity;
        }
        int apsShiftCapacity = capacity.getApsCapacity() / LhScheduleConstant.DEFAULT_SHIFTS_PER_DAY;
        return Math.max(baseCapacity, apsShiftCapacity);
    }

    /**
     * 获取日标准产量剩余班次参数。
     *
     * @param context 排程上下文
     * @return 参数值，默认中班
     */
    public static String resolveDailyStandardCapacityRemainShiftType(LhScheduleContext context) {
        if (Objects.isNull(context) || Objects.isNull(context.getScheduleConfig())) {
            return String.valueOf(AFTERNOON_PLUS_SHIFT_TYPE);
        }
        return context.getScheduleConfig().getDailyStandardCapacityRemainShiftType();
    }

    /**
     * 获取奇数班产修正参数。
     *
     * @param context 排程上下文
     * @return 参数值，未配置返回空字符串
     */
    public static String resolveOddShiftCapacityPlusShiftType(LhScheduleContext context) {
        if (Objects.isNull(context) || Objects.isNull(context.getScheduleConfig())) {
            return StringUtils.EMPTY;
        }
        return context.getScheduleConfig().getOddShiftCapacityPlusShiftType();
    }

    /**
     * 判断奇数班产修正是否启用。
     *
     * @param baseCapacity 原始班产
     * @param shift 当前班次
     * @param configPlusShiftType 配置值
     * @param scheduleType 排程类型
     * @return true-启用；false-不启用
     */
    public static boolean isOddShiftCapacityAdjustEnabled(int baseCapacity,
                                                          LhShiftConfigVO shift,
                                                          String configPlusShiftType,
                                                          String scheduleType) {
        return isOddShiftCapacityAdjustEnabledInternal(baseCapacity, shift, configPlusShiftType, scheduleType);
    }

    /**
     * 判断奇数班产加一班别配置是否合法。
     *
     * @param configPlusShiftType 配置值
     * @return true-合法
     */
    public static boolean isOddShiftCapacityPlusShiftTypeValid(String configPlusShiftType) {
        return isValidPlusShiftType(configPlusShiftType);
    }

    /**
     * 解析奇数班产修正未启用原因。
     *
     * @param baseCapacity 原始班产
     * @param shift 当前班次
     * @param configPlusShiftType 配置值
     * @param scheduleType 排程类型
     * @return 未启用原因，已启用时返回空字符串
     */
    public static String resolveOddShiftCapacityDisabledReason(int baseCapacity,
                                                               LhShiftConfigVO shift,
                                                               String configPlusShiftType,
                                                               String scheduleType) {
        if (isOddShiftCapacityAdjustEnabled(baseCapacity, shift, configPlusShiftType, scheduleType)) {
            return StringUtils.EMPTY;
        }
        if (!isSupportedScheduleType(scheduleType)) {
            return "流程不在范围";
        }
        if (StringUtils.isEmpty(configPlusShiftType)) {
            return "参数未配置";
        }
        if (!isValidPlusShiftType(configPlusShiftType)) {
            return "参数值非法";
        }
        if (baseCapacity <= 0) {
            return "原始班产小于等于0";
        }
        if (baseCapacity % 2 == 0) {
            return "原始班产为偶数";
        }
        if (Objects.isNull(shift)) {
            return "班次为空";
        }
        if (Objects.isNull(resolveShiftTypeValue(shift.resolveShiftTypeEnum()))) {
            return "班别无法识别";
        }
        return "未知原因";
    }

    /**
     * 按班次和实际开产时间解析班次产能。
     * <p>适用于“已知班次对象 + 已知残班起点”的场景：先算出该班次剩余秒数，再交给统一折算公式处理。</p>
     *
     * @param shift 班次
     * @param effectiveStartTime 实际开产时间
     * @param shiftCapacity 班产
     * @param lhTimeSeconds 硫化时长（秒）
     * @param mouldQty 模台数
     * @return 班次可排计划量
     */
    public static int resolveShiftCapacity(LhShiftConfigVO shift,
                                           Date effectiveStartTime,
                                           int shiftCapacity,
                                           int lhTimeSeconds,
                                           int mouldQty) {
        if (Objects.isNull(shift) || Objects.isNull(effectiveStartTime)) {
            return 0;
        }
        Date shiftEndTime = shift.getShiftEndDateTime();
        if (Objects.isNull(shiftEndTime) || !effectiveStartTime.before(shiftEndTime)) {
            return 0;
        }
        long availableSeconds = (shiftEndTime.getTime() - effectiveStartTime.getTime()) / 1000L;
        if (availableSeconds <= 0) {
            return 0;
        }
        long shiftDurationSeconds = resolveShiftDurationSeconds(shift);
        return resolveShiftCapacity(shiftCapacity, lhTimeSeconds, mouldQty, shiftDurationSeconds, availableSeconds);
    }

    /**
     * 解析忽略清洗扣量时的首个可排产开始时间。
     * <p>这里故意只扣设备停机，不扣清洗。用途是先判断“机台从哪个班开始具备排产可能”，
     * 后续再单独把清洗窗口从可排量里扣掉，避免把“起排判断”和“扣量判断”混在一起。</p>
     *
     * @param devicePlanShutList 设备停机列表
     * @param machineCode 机台编号
     * @param productionStartTime 切换完成后的理论开产时间
     * @param shifts 排程班次窗口
     * @param shiftCapacity 班产
     * @param lhTimeSeconds 硫化时长（秒）
     * @param mouldQty 模台数
     * @return 首个可排产开始时间；不存在可排产班次时返回 null
     */
    public static Date resolveFirstSchedulableStartIgnoringCleaning(List<MdmDevicePlanShut> devicePlanShutList,
                                                                    String machineCode,
                                                                    Date productionStartTime,
                                                                    List<LhShiftConfigVO> shifts,
                                                                    int shiftCapacity,
                                                                    int lhTimeSeconds,
                                                                    int mouldQty) {
        if (Objects.isNull(productionStartTime) || CollectionUtils.isEmpty(shifts)) {
            return null;
        }
        Date cursorStartTime = productionStartTime;
        boolean started = false;
        for (LhShiftConfigVO shift : shifts) {
            if (Objects.isNull(shift)
                    || Objects.isNull(shift.getShiftStartDateTime())
                    || Objects.isNull(shift.getShiftEndDateTime())) {
                continue;
            }
            if (!started) {
                if (cursorStartTime.before(shift.getShiftEndDateTime())) {
                    started = true;
                } else {
                    continue;
                }
            }
            Date effectiveStartTime = cursorStartTime.after(shift.getShiftStartDateTime())
                    ? cursorStartTime : shift.getShiftStartDateTime();
            if (!effectiveStartTime.before(shift.getShiftEndDateTime())) {
                cursorStartTime = shift.getShiftEndDateTime();
                continue;
            }
            long netAvailableSeconds = resolveNetAvailableSeconds(
                    devicePlanShutList, machineCode, effectiveStartTime, shift.getShiftEndDateTime());
            if (netAvailableSeconds <= 0) {
                cursorStartTime = shift.getShiftEndDateTime();
                continue;
            }
            int shiftMaxQty = resolveShiftCapacity(
                    shiftCapacity,
                    lhTimeSeconds,
                    mouldQty,
                    resolveShiftDurationSeconds(shift),
                    netAvailableSeconds);
            if (shiftMaxQty > 0) {
                return effectiveStartTime;
            }
            cursorStartTime = shift.getShiftEndDateTime();
        }
        return null;
    }

    /**
     * 判断当前班次是否启用奇数班产修正。
     *
     * @param baseCapacity 原始班产
     * @param shift 当前班次
     * @param configPlusShiftType 配置值
     * @param scheduleType 排程类型
     * @return true-启用；false-保持原逻辑
     */
    private static boolean isOddShiftCapacityAdjustEnabledInternal(int baseCapacity,
                                                                   LhShiftConfigVO shift,
                                                                   String configPlusShiftType,
                                                                   String scheduleType) {
        return baseCapacity > 0
                && baseCapacity % 2 != 0
                && Objects.nonNull(shift)
                && isSupportedScheduleType(scheduleType)
                && isValidPlusShiftType(configPlusShiftType);
    }

    /**
     * 判断排程类型是否属于本次规则范围。
     *
     * @param scheduleType 排程类型
     * @return true-新增、续作或换活字块
     */
    private static boolean isSupportedScheduleType(String scheduleType) {
        return StringUtils.equals(ScheduleTypeEnum.NEW_SPEC.getCode(), scheduleType)
                || StringUtils.equals(ScheduleTypeEnum.CONTINUOUS.getCode(), scheduleType)
                || StringUtils.equals(ScheduleTypeEnum.TYPE_BLOCK.getCode(), scheduleType);
    }

    /**
     * 判断配置的加一班别是否合法。
     *
     * @param configPlusShiftType 配置值
     * @return true-合法
     */
    private static boolean isValidPlusShiftType(String configPlusShiftType) {
        if (StringUtils.isEmpty(configPlusShiftType)) {
            return false;
        }
        try {
            int plusShiftType = Integer.parseInt(configPlusShiftType.trim());
            return plusShiftType == NIGHT_PLUS_SHIFT_TYPE
                    || plusShiftType == MORNING_PLUS_SHIFT_TYPE
                    || plusShiftType == AFTERNOON_PLUS_SHIFT_TYPE;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * 解析班次枚举对应的参数配置值。
     *
     * @param shiftEnum 班次枚举
     * @return 1-晚班，2-早班，3-中班；无法识别返回 null
     */
    private static Integer resolveShiftTypeValue(ShiftEnum shiftEnum) {
        if (ShiftEnum.NIGHT_SHIFT == shiftEnum) {
            return NIGHT_PLUS_SHIFT_TYPE;
        }
        if (ShiftEnum.MORNING_SHIFT == shiftEnum) {
            return MORNING_PLUS_SHIFT_TYPE;
        }
        if (ShiftEnum.AFTERNOON_SHIFT == shiftEnum) {
            return AFTERNOON_PLUS_SHIFT_TYPE;
        }
        return null;
    }

    /**
     * 汇总同一业务日其他班次计划量。
     *
     * @param currentShift 当前班次
     * @param sameDayShiftPlanQtyMap 同一业务日班次计划量
     * <p>续作窗口首个业务日只有早班和中班，窗口外夜班无法出现在当前班次图中。
     * 续作按完整业务日应用余量公式时，缺失班次按一个完整班产计入；新增和换活字块保持原口径。</p>
     *
     * @param classCapacity 原始班产
     * @param fillMissingShiftCapacity 是否按班产补齐窗口边界缺失班次
     * @return 其他班次计划量合计
     */
    private static int sumOtherShiftPlanQty(LhShiftConfigVO currentShift,
                                            Map<Integer, Integer> sameDayShiftPlanQtyMap,
                                            int classCapacity,
                                            boolean fillMissingShiftCapacity) {
        if (Objects.isNull(currentShift) || CollectionUtils.isEmpty(sameDayShiftPlanQtyMap)) {
            return 0;
        }
        int totalQty = 0;
        int otherShiftCount = 0;
        for (Map.Entry<Integer, Integer> entry : sameDayShiftPlanQtyMap.entrySet()) {
            if (Objects.isNull(entry) || Objects.isNull(entry.getKey())
                    || entry.getKey().intValue() == currentShift.getShiftIndex()) {
                continue;
            }
            otherShiftCount++;
            totalQty += entry.getValue() == null ? 0 : Math.max(0, entry.getValue());
        }
        if (fillMissingShiftCapacity && classCapacity > 0) {
            int expectedOtherShiftCount = LhScheduleConstant.DEFAULT_SHIFTS_PER_DAY - 1;
            int missingShiftCount = Math.max(0, expectedOtherShiftCount - otherShiftCount);
            totalQty += missingShiftCount * classCapacity;
        }
        return totalQty;
    }

    /**
     * 判断同一业务日其他班次是否都达到班产。
     *
     * @param currentShift 当前班次
     * @param sameDayShiftPlanQtyMap 同一业务日班次计划量
     * @param classCapacity 原始班产
     * @return true-其他班次都满足班产
     */
    private static boolean isOtherShiftPlanQtyFull(LhShiftConfigVO currentShift,
                                                   Map<Integer, Integer> sameDayShiftPlanQtyMap,
                                                   int classCapacity) {
        if (Objects.isNull(currentShift) || CollectionUtils.isEmpty(sameDayShiftPlanQtyMap)
                || classCapacity <= 0) {
            return false;
        }
        int otherShiftCount = 0;
        for (Map.Entry<Integer, Integer> entry : sameDayShiftPlanQtyMap.entrySet()) {
            if (Objects.isNull(entry) || Objects.isNull(entry.getKey())
                    || entry.getKey().intValue() == currentShift.getShiftIndex()) {
                continue;
            }
            otherShiftCount++;
            int planQty = entry.getValue() == null ? 0 : Math.max(0, entry.getValue());
            if (planQty < classCapacity) {
                return false;
            }
        }
        return otherShiftCount >= LhScheduleConstant.DEFAULT_SHIFTS_PER_DAY - 1;
    }

    /**
     * 按业务日构建班次计划量映射。
     *
     * @param shifts 班次列表
     * @param rawShiftPlanQtyMap 原班次计划量
     * @return key=业务日，value=该日班次计划量
     */
    private static Map<LocalDate, Map<Integer, Integer>> buildSameDayShiftPlanQtyMap(
            List<LhShiftConfigVO> shifts,
            Map<Integer, Integer> rawShiftPlanQtyMap) {
        Map<LocalDate, Map<Integer, Integer>> sameDayPlanQtyMap = new LinkedHashMap<LocalDate, Map<Integer, Integer>>(
                CollectionUtils.isEmpty(shifts) ? 0 : shifts.size());
        if (CollectionUtils.isEmpty(shifts) || CollectionUtils.isEmpty(rawShiftPlanQtyMap)) {
            return sameDayPlanQtyMap;
        }
        for (LhShiftConfigVO shift : shifts) {
            if (Objects.isNull(shift) || !rawShiftPlanQtyMap.containsKey(shift.getShiftIndex())) {
                continue;
            }
            LocalDate workDate = resolveWorkDate(shift);
            if (Objects.isNull(workDate)) {
                continue;
            }
            Map<Integer, Integer> planQtyMap = sameDayPlanQtyMap.get(workDate);
            if (Objects.isNull(planQtyMap)) {
                planQtyMap = new LinkedHashMap<Integer, Integer>(LhScheduleConstant.DEFAULT_SHIFTS_PER_DAY);
                sameDayPlanQtyMap.put(workDate, planQtyMap);
            }
            planQtyMap.put(shift.getShiftIndex(), rawShiftPlanQtyMap.get(shift.getShiftIndex()));
        }
        return sameDayPlanQtyMap;
    }

    /**
     * 解析班次业务日。
     *
     * @param shift 班次
     * @return 业务日
     */
    private static LocalDate resolveWorkDate(LhShiftConfigVO shift) {
        if (Objects.isNull(shift) || Objects.isNull(shift.getWorkDate())) {
            return null;
        }
        return shift.getWorkDate().toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
    }

    /**
     * 按统一业务口径解析班次产能。
     * <p>当前口径分两类：</p>
     * <p>1. 有班产主数据：按“整班班产 × 可用时间占比”折算，使用 {@link RoundingMode#DOWN} 向下取整；</p>
     * <p>2. 无班产主数据：按“可完成硫化周期数 × 模台数”回退计算。</p>
     *
     * @param shiftCapacity 班产
     * @param lhTimeSeconds 硫化时长（秒）
     * @param mouldQty 模台数
     * @param shiftDurationSeconds 班次总时长（秒）
     * @param availableSeconds 有效可生产时长（秒）
     * @return 班次可排计划量
     */
    public static int resolveShiftCapacity(int shiftCapacity,
                                           int lhTimeSeconds,
                                           int mouldQty,
                                           long shiftDurationSeconds,
                                           long availableSeconds) {
        if (availableSeconds <= 0) {
            return 0;
        }

        long effectiveAvailableSeconds = availableSeconds;
        if (shiftDurationSeconds > 0) {
            effectiveAvailableSeconds = Math.min(availableSeconds, shiftDurationSeconds);
        }

        // 有班产主数据时，按整班班产基准做残班折算。
        // 这里向下取整，表示“只保留已被有效时间完全覆盖的计划量”，不把零头时间提前算成完整产出。
        if (shiftCapacity > 0) {
            if (shiftDurationSeconds <= 0) {
                return shiftCapacity;
            }
            int resolvedQty = BigDecimal.valueOf(shiftCapacity)
                    .multiply(BigDecimal.valueOf(effectiveAvailableSeconds))
                    .divide(BigDecimal.valueOf(shiftDurationSeconds), 0, RoundingMode.DOWN)
                    .intValue();
            return normalizeQtyToMouldMultiple(resolvedQty, mouldQty, effectiveAvailableSeconds < shiftDurationSeconds);
        }

        // 无班产主数据时，按完整硫化周期数回退计算，仍然只统计可完整完成的周期。
        int resolvedMouldQty = resolveMachineMouldQty(mouldQty);
        if (lhTimeSeconds <= 0 || resolvedMouldQty <= 0) {
            return 0;
        }
        return (int) (effectiveAvailableSeconds / lhTimeSeconds) * resolvedMouldQty;
    }

    /**
     * 按统一业务口径解析班次产能，并在指定排程流程内应用奇数班产班别修正。
     *
     * @param shift 当前班次
     * @param shiftCapacity 原始班产
     * @param lhTimeSeconds 硫化时长（秒）
     * @param mouldQty 模台数
     * @param shiftDurationSeconds 班次总时长（秒）
     * @param availableSeconds 有效可生产时长（秒）
     * @param configPlusShiftType 配置的加一班别
     * @param scheduleType 排程类型
     * @return 班次可排计划量
     */
    public static int resolveShiftCapacity(LhShiftConfigVO shift,
                                           int shiftCapacity,
                                           int lhTimeSeconds,
                                           int mouldQty,
                                           long shiftDurationSeconds,
                                           long availableSeconds,
                                           String configPlusShiftType,
                                           String scheduleType) {
        int actualShiftCapacity = resolveActualShiftPlanQty(
                shiftCapacity, shift, configPlusShiftType, scheduleType);
        return resolveShiftCapacity(actualShiftCapacity, lhTimeSeconds, mouldQty,
                shiftDurationSeconds, availableSeconds);
    }

    /**
     * 解析班次总时长（秒）。
     * <p>优先使用班次配置中的时长字段；缺失时再退回到开始/结束时间差，保持对历史配置兼容。</p>
     *
     * @param shift 班次
     * @return 班次总时长（秒）
     */
    public static long resolveShiftDurationSeconds(LhShiftConfigVO shift) {
        if (Objects.isNull(shift)) {
            return 0L;
        }
        if (shift.getDurationMinutes() > 0) {
            return (long) shift.getDurationMinutes() * SECONDS_PER_MINUTE;
        }
        Date shiftStartTime = shift.getShiftStartDateTime();
        Date shiftEndTime = shift.getShiftEndDateTime();
        if (Objects.isNull(shiftStartTime) || Objects.isNull(shiftEndTime)) {
            return 0L;
        }
        long durationSeconds = (shiftEndTime.getTime() - shiftStartTime.getTime()) / 1000L;
        return Math.max(durationSeconds, 0L);
    }

    /**
     * 计算机台在指定时间窗内被计划停机占用的总秒数。
     * <p>同一机台可能存在多条停机记录，本方法会先裁剪到目标时间窗，再做区间合并，避免重复扣秒。</p>
     * <p>所有非清洗类设备停机类型（含精度校验、润滑、巡检点检、预见性维护、预防性维护、
     * 计划性维修、临时性故障、盘点等）均通过本方法统一按时间重叠折算扣减，
     * 其中盘点只扣时间产能，不触发换模、换活字块、预热等逻辑。</p>
     *
     * @param devicePlanShutList 设备计划停机列表（已剥离清洗类停机）
     * @param machineCode 机台编号
     * @param windowStartTime 时间窗开始时间
     * @param windowEndTime 时间窗结束时间
     * @return 停机重叠秒数
     */
    public static long resolvePlannedStopOverlapSeconds(List<MdmDevicePlanShut> devicePlanShutList,
                                                        String machineCode,
                                                        Date windowStartTime,
                                                        Date windowEndTime) {
        if (CollectionUtils.isEmpty(devicePlanShutList)
                || StringUtils.isEmpty(machineCode)
                || Objects.isNull(windowStartTime)
                || Objects.isNull(windowEndTime)
                || !windowStartTime.before(windowEndTime)) {
            return 0L;
        }

        List<Date[]> overlapIntervals = new ArrayList<>();
        for (MdmDevicePlanShut planShut : devicePlanShutList) {
            if (Objects.isNull(planShut)
                    || !StringUtils.equals(machineCode, planShut.getMachineCode())
                    || Objects.isNull(planShut.getBeginDate())
                    || Objects.isNull(planShut.getEndDate())
                    || !planShut.getBeginDate().before(planShut.getEndDate())) {
                continue;
            }
            Date overlapStartTime = planShut.getBeginDate().after(windowStartTime) ? planShut.getBeginDate() : windowStartTime;
            Date overlapEndTime = planShut.getEndDate().before(windowEndTime) ? planShut.getEndDate() : windowEndTime;
            if (overlapStartTime.before(overlapEndTime)) {
                overlapIntervals.add(new Date[]{overlapStartTime, overlapEndTime});
            }
        }
        if (overlapIntervals.isEmpty()) {
            return 0L;
        }

        overlapIntervals.sort((left, right) -> {
            int startCmp = left[0].compareTo(right[0]);
            if (startCmp != 0) {
                return startCmp;
            }
            return left[1].compareTo(right[1]);
        });

        long overlapSeconds = 0L;
        Date mergedStart = overlapIntervals.get(0)[0];
        Date mergedEnd = overlapIntervals.get(0)[1];
        for (int i = 1; i < overlapIntervals.size(); i++) {
            Date currentStart = overlapIntervals.get(i)[0];
            Date currentEnd = overlapIntervals.get(i)[1];
            if (!currentStart.after(mergedEnd)) {
                if (currentEnd.after(mergedEnd)) {
                    mergedEnd = currentEnd;
                }
                continue;
            }
            overlapSeconds += (mergedEnd.getTime() - mergedStart.getTime()) / 1000L;
            mergedStart = currentStart;
            mergedEnd = currentEnd;
        }
        overlapSeconds += (mergedEnd.getTime() - mergedStart.getTime()) / 1000L;
        return Math.max(overlapSeconds, 0L);
    }

    /**
     * 计算机台在指定时间窗内扣减计划停机后的净可用秒数。
     * <p>这里的“可用”只排除设备停机，不排除清洗。常用于先判断某班是否具备基本开产条件。</p>
     *
     * @param devicePlanShutList 设备计划停机列表
     * @param machineCode 机台编号
     * @param windowStartTime 时间窗开始时间
     * @param windowEndTime 时间窗结束时间
     * @return 净可用秒数
     */
    public static long resolveNetAvailableSeconds(List<MdmDevicePlanShut> devicePlanShutList,
                                                  String machineCode,
                                                  Date windowStartTime,
                                                  Date windowEndTime) {
        if (Objects.isNull(windowStartTime) || Objects.isNull(windowEndTime) || !windowStartTime.before(windowEndTime)) {
            return 0L;
        }
        long availableSeconds = (windowEndTime.getTime() - windowStartTime.getTime()) / 1000L;
        if (availableSeconds <= 0) {
            return 0L;
        }
        long overlapSeconds = resolvePlannedStopOverlapSeconds(devicePlanShutList, machineCode, windowStartTime, windowEndTime);
        return Math.max(availableSeconds - overlapSeconds, 0L);
    }

    /**
     * 计算机台在指定时间窗内扣减计划停机和清洗后的净生产秒数。
     * <p>与 {@link #resolveNetAvailableSeconds(List, String, Date, Date)} 的区别是：这里会把清洗窗口也一并视为不可生产时间。</p>
     *
     * @param devicePlanShutList 设备停机计划
     * @param cleaningWindowList 清洗时间窗口
     * @param machineCode 机台编号
     * @param windowStartTime 时间窗开始时间
     * @param windowEndTime 时间窗结束时间
     * @return 净生产秒数
     */
    public static long resolveNetProductiveSeconds(List<MdmDevicePlanShut> devicePlanShutList,
                                                   List<MachineCleaningWindowDTO> cleaningWindowList,
                                                   String machineCode,
                                                   Date windowStartTime,
                                                   Date windowEndTime) {
        return resolveNetProductiveSeconds(devicePlanShutList, cleaningWindowList, null, machineCode,
                windowStartTime, windowEndTime);
    }

    /**
     * 计算机台在指定时间窗内扣减计划停机、清洗和保养后的净生产秒数。
     *
     * @param devicePlanShutList 设备停机计划
     * @param cleaningWindowList 清洗时间窗口
     * @param maintenanceWindowList 保养时间窗口
     * @param machineCode 机台编号
     * @param windowStartTime 时间窗开始时间
     * @param windowEndTime 时间窗结束时间
     * @return 净生产秒数
     */
    public static long resolveNetProductiveSeconds(List<MdmDevicePlanShut> devicePlanShutList,
                                                   List<MachineCleaningWindowDTO> cleaningWindowList,
                                                   List<MachineMaintenanceWindowDTO> maintenanceWindowList,
                                                   String machineCode,
                                                   Date windowStartTime,
                                                   Date windowEndTime) {
        if (Objects.isNull(windowStartTime) || Objects.isNull(windowEndTime) || !windowStartTime.before(windowEndTime)) {
            return 0L;
        }
        long availableSeconds = (windowEndTime.getTime() - windowStartTime.getTime()) / 1000L;
        if (availableSeconds <= 0) {
            return 0L;
        }
        long downtimeSeconds = resolveDowntimeOverlapSeconds(
                devicePlanShutList, cleaningWindowList, maintenanceWindowList, machineCode, windowStartTime, windowEndTime);
        return Math.max(availableSeconds - downtimeSeconds, 0L);
    }

    /**
     * 计算扣减停机与清洗后的班次最大计划量。
     * <p>先按设备停机与干冰清洗扣出“剩余有效生产时间”，再减去喷砂清洗损失量。</p>
     * <p>之所以分两步，是因为当前业务口径不同：干冰按剩余有效时间折算班产，喷砂按清洗重叠时长折算扣量。</p>
     *
     * @param devicePlanShutList 设备停机计划
     * @param cleaningWindowList 清洗时间窗口
     * @param machineCode 机台编号
     * @param windowStartTime 时间窗开始时间
     * @param windowEndTime 时间窗结束时间
     * @param shiftCapacity 班产
     * @param lhTimeSeconds 硫化时长（秒）
     * @param mouldQty 模台数
     * @param shiftDurationSeconds 班次总时长（秒）
     * @param dryIceDurationHours 干冰标准清洗时长（小时）
     * @return 扣减后的班次最大计划量
     */
    public static int resolveShiftCapacityWithDowntime(List<MdmDevicePlanShut> devicePlanShutList,
                                                       List<MachineCleaningWindowDTO> cleaningWindowList,
                                                       String machineCode,
                                                       Date windowStartTime,
                                                       Date windowEndTime,
                                                       int shiftCapacity,
                                                       int lhTimeSeconds,
                                                       int mouldQty,
                                                       long shiftDurationSeconds,
                                                       int dryIceLossQty,
                                                       int dryIceDurationHours,
                                                       int plannedRepairFixedQty) {
        return resolveShiftCapacityWithDowntime(devicePlanShutList, cleaningWindowList, null, machineCode,
                windowStartTime, windowEndTime, shiftCapacity, lhTimeSeconds, mouldQty, shiftDurationSeconds,
                dryIceLossQty, dryIceDurationHours, plannedRepairFixedQty);
    }

    /**
     * 计算扣减停机、清洗后的班次最大计划量，并按指定排程流程应用奇数班产班别修正。
     *
     * @param devicePlanShutList 设备停机计划
     * @param cleaningWindowList 清洗时间窗口
     * @param machineCode 机台编号
     * @param windowStartTime 时间窗开始时间
     * @param windowEndTime 时间窗结束时间
     * @param shiftCapacity 原始班产
     * @param lhTimeSeconds 硫化时长（秒）
     * @param mouldQty 模台数
     * @param shiftDurationSeconds 班次总时长（秒）
     * @param dryIceLossQty 干冰清洗损失数量
     * @param dryIceDurationHours 干冰标准清洗时长（小时）
     * @param shift 当前班次
     * @param configPlusShiftType 配置的加一班别
     * @param scheduleType 排程类型
     * @return 扣减后的班次最大计划量
     */
    public static int resolveShiftCapacityWithDowntime(List<MdmDevicePlanShut> devicePlanShutList,
                                                       List<MachineCleaningWindowDTO> cleaningWindowList,
                                                       String machineCode,
                                                       Date windowStartTime,
                                                       Date windowEndTime,
                                                       int shiftCapacity,
                                                       int lhTimeSeconds,
                                                       int mouldQty,
                                                       long shiftDurationSeconds,
                                                       int dryIceLossQty,
                                                       int dryIceDurationHours,
                                                       LhShiftConfigVO shift,
                                                       String configPlusShiftType,
                                                       String scheduleType,
                                                       int plannedRepairFixedQty) {
        return resolveShiftCapacityWithDowntime(devicePlanShutList, cleaningWindowList, null, machineCode,
                windowStartTime, windowEndTime, shiftCapacity, lhTimeSeconds, mouldQty, shiftDurationSeconds,
                dryIceLossQty, dryIceDurationHours, shift, configPlusShiftType, scheduleType, plannedRepairFixedQty);
    }

    /**
     * 计算扣减停机、清洗与保养后的班次最大计划量。
     *
     * @param devicePlanShutList 设备停机计划
     * @param cleaningWindowList 清洗时间窗口
     * @param maintenanceWindowList 保养时间窗口
     * @param machineCode 机台编号
     * @param windowStartTime 时间窗开始时间
     * @param windowEndTime 时间窗结束时间
     * @param shiftCapacity 班产
     * @param lhTimeSeconds 硫化时长（秒）
     * @param mouldQty 模台数
     * @param shiftDurationSeconds 班次总时长（秒）
     * @param dryIceLossQty 干冰清洗损失数量
     * @param dryIceDurationHours 干冰标准清洗时长（小时）
     * @return 扣减后的班次最大计划量
     */
    public static int resolveShiftCapacityWithDowntime(List<MdmDevicePlanShut> devicePlanShutList,
                                                       List<MachineCleaningWindowDTO> cleaningWindowList,
                                                       List<MachineMaintenanceWindowDTO> maintenanceWindowList,
                                                       String machineCode,
                                                       Date windowStartTime,
                                                       Date windowEndTime,
                                                       int shiftCapacity,
                                                       int lhTimeSeconds,
                                                       int mouldQty,
                                                       long shiftDurationSeconds,
                                                       int dryIceLossQty,
                                                       int dryIceDurationHours,
                                                       int plannedRepairFixedQty) {
        if (Objects.isNull(windowStartTime) || Objects.isNull(windowEndTime) || !windowStartTime.before(windowEndTime)) {
            return 0;
        }
        // 计划性维修(05)固定排产量：维修计划开始时间落在当前班次窗口时，该班次固定只排 plannedRepairFixedQty 条，不按时间折算
        if (isPlannedRepairStartShift(devicePlanShutList, machineCode, windowStartTime, windowEndTime)) {
            return Math.max(0, plannedRepairFixedQty);
        }
        long availableSeconds = Math.max(0L, (windowEndTime.getTime() - windowStartTime.getTime()) / 1000L);
        List<Date[]> stopIntervals = collectMergedPlannedStopIntervals(
                devicePlanShutList, machineCode, windowStartTime, windowEndTime);
        List<Date[]> baseDowntimeIntervals = new ArrayList<>(stopIntervals);
        // 保养/精度与维修类设备停机属于基础不可生产时间，先合并成并集，避免两类停机重叠时重复扣减。
        baseDowntimeIntervals.addAll(collectMergedMaintenanceIntervals(
                maintenanceWindowList, windowStartTime, windowEndTime));
        baseDowntimeIntervals = mergeIntervals(baseDowntimeIntervals);
        long baseAdjustedSeconds = Math.max(availableSeconds - resolveIntervalDurationSeconds(baseDowntimeIntervals), 0L);
        // 干冰清洗只扣基础停机并集之外的占用时间；若与保养/维修重叠，产能扣减自然按最大占用时间计算。
        long dryIceAdjustedSeconds = resolveDryIceAdjustedAvailableSeconds(
                cleaningWindowList, baseDowntimeIntervals, windowStartTime, windowEndTime, baseAdjustedSeconds);
        int stopAdjustedQty = resolveShiftCapacity(
                shiftCapacity, lhTimeSeconds, mouldQty, shiftDurationSeconds, dryIceAdjustedSeconds);
        if (stopAdjustedQty <= 0) {
            return 0;
        }
        List<Date[]> cleaningOccupiedIntervals = new ArrayList<>(baseDowntimeIntervals);
        cleaningOccupiedIntervals.addAll(collectMergedCleaningIntervals(
                cleaningWindowList, CleaningTypeEnum.DRY_ICE.getCode(), windowStartTime, windowEndTime));
        cleaningOccupiedIntervals = mergeIntervals(cleaningOccupiedIntervals);
        // 喷砂按剩余未覆盖时长折算损失量；与精度、维修、干冰清洗重叠时只扣未被前序区间覆盖的部分。
        int cleaningLossQty = resolveCleaningLossQty(
                cleaningWindowList, cleaningOccupiedIntervals, windowStartTime, windowEndTime,
                shiftCapacity, lhTimeSeconds, mouldQty, shiftDurationSeconds);
        int finalQty = Math.max(stopAdjustedQty - cleaningLossQty, 0);
        boolean shouldNormalizeResidual = (shiftDurationSeconds > 0 && dryIceAdjustedSeconds < shiftDurationSeconds)
                || cleaningLossQty > 0;
        return normalizeQtyToMouldMultiple(finalQty, mouldQty, shouldNormalizeResidual);
    }

    /**
     * 计算扣减停机、清洗与保养后的班次最大计划量，并按指定排程流程应用奇数班产班别修正。
     *
     * @param devicePlanShutList 设备停机计划
     * @param cleaningWindowList 清洗时间窗口
     * @param maintenanceWindowList 保养时间窗口
     * @param machineCode 机台编号
     * @param windowStartTime 时间窗开始时间
     * @param windowEndTime 时间窗结束时间
     * @param shiftCapacity 原始班产
     * @param lhTimeSeconds 硫化时长（秒）
     * @param mouldQty 模台数
     * @param shiftDurationSeconds 班次总时长（秒）
     * @param dryIceLossQty 干冰清洗损失数量
     * @param dryIceDurationHours 干冰标准清洗时长（小时）
     * @param shift 当前班次
     * @param configPlusShiftType 配置的加一班别
     * @param scheduleType 排程类型
     * @return 扣减后的班次最大计划量
     */
    public static int resolveShiftCapacityWithDowntime(List<MdmDevicePlanShut> devicePlanShutList,
                                                       List<MachineCleaningWindowDTO> cleaningWindowList,
                                                       List<MachineMaintenanceWindowDTO> maintenanceWindowList,
                                                       String machineCode,
                                                       Date windowStartTime,
                                                       Date windowEndTime,
                                                       int shiftCapacity,
                                                       int lhTimeSeconds,
                                                       int mouldQty,
                                                       long shiftDurationSeconds,
                                                       int dryIceLossQty,
                                                       int dryIceDurationHours,
                                                       LhShiftConfigVO shift,
                                                       String configPlusShiftType,
                                                       String scheduleType,
                                                       int plannedRepairFixedQty) {
        int actualShiftCapacity = resolveActualShiftPlanQty(
                shiftCapacity, shift, configPlusShiftType, scheduleType);
        return resolveShiftCapacityWithDowntime(devicePlanShutList, cleaningWindowList, maintenanceWindowList,
                machineCode, windowStartTime, windowEndTime, actualShiftCapacity, lhTimeSeconds, mouldQty,
                shiftDurationSeconds, dryIceLossQty, dryIceDurationHours, plannedRepairFixedQty);
    }


    /**
     * 判断当前班次是否为计划性维修(05)开始班次。
     * <p>当设备停机计划中存在 05-计划性维修 且其计划开始时间落在当前班次窗口内时，返回 true。
     * 命中的班次固定只排 plannedRepairFixedQty 条（由硫化参数 PLANNED_REPAIR_FIXED_QTY 配置，默认 2 条），
     * 不按时间折算，排完后机台进入维修。</p>
     *
     * @param devicePlanShutList 设备停机计划列表
     * @param machineCode 机台编号
     * @param windowStartTime 班次窗口开始时间
     * @param windowEndTime 班次窗口结束时间
     * @return true-当前班次为计划性维修开始班次；false-否
     */
    private static boolean isPlannedRepairStartShift(List<MdmDevicePlanShut> devicePlanShutList,
                                                      String machineCode,
                                                      Date windowStartTime,
                                                      Date windowEndTime) {
        if (CollectionUtils.isEmpty(devicePlanShutList)) {
            return false;
        }
        for (MdmDevicePlanShut planShut : devicePlanShutList) {
            if (Objects.isNull(planShut) || !StringUtils.equals(machineCode, planShut.getMachineCode())) {
                continue;
            }
            // 只匹配计划性维修(05)停机计划
            if (!StringUtils.equals(MachineStopTypeEnum.PLANNED_REPAIR.getCode(), planShut.getMachineStopType())) {
                continue;
            }
            Date beginDate = planShut.getBeginDate();
            if (Objects.isNull(beginDate)) {
                continue;
            }
            // 计划开始时间落在当前班次窗口 [windowStartTime, windowEndTime) 内时，该班次为维修开始班次
            if (!beginDate.before(windowStartTime) && beginDate.before(windowEndTime)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 双模及多模残班统一向上收敛到模台数整数倍。
     *
     * @param qty 当前计划量
     * @param mouldQty 模台数
     * @param shouldNormalize 是否属于残班/扣量场景
     * @return 收敛后的计划量
     */
    public static int normalizeQtyToMouldMultiple(int qty, int mouldQty, boolean shouldNormalize) {
        if (!shouldNormalize || qty <= 0) {
            return qty;
        }
        int resolvedMouldQty = resolveMachineMouldQty(mouldQty);
        if (resolvedMouldQty <= 1) {
            return qty;
        }
        if (qty < resolvedMouldQty) {
            return 0;
        }
        return ((qty + resolvedMouldQty - 1) / resolvedMouldQty) * resolvedMouldQty;
    }

    /**
     * 按模台数向上收敛目标量。
     * <p>用于收尾目标量等业务目标口径，双模/多模即使尾量小于模台数也需要保留一模，不沿用残班归零语义。</p>
     *
     * @param qty 当前目标量
     * @param mouldQty 模台数
     * @return 向上收敛后的目标量
     */
    public static int roundUpQtyToMouldMultiple(int qty, int mouldQty) {
        if (qty <= 0) {
            return qty;
        }
        int resolvedMouldQty = resolveMachineMouldQty(mouldQty);
        if (resolvedMouldQty <= 1) {
            return qty;
        }
        return ((qty + resolvedMouldQty - 1) / resolvedMouldQty) * resolvedMouldQty;
    }

    /**
     * 统一收敛班次实际落点计划量，避免双模残班落成奇数；奇数向上收敛到模台数整数倍。
     *
     * @param allocationQty 当前拟分配计划量
     * @param shiftMaxQty 当前班次最大可排量
     * @param mouldQty 模台数
     * @return 收敛后的班次计划量
     */
    public static int normalizeAllocatedShiftQty(int allocationQty, int shiftMaxQty, int mouldQty) {
        return normalizeQtyToMouldMultiple(allocationQty, mouldQty, allocationQty < shiftMaxQty);
    }

    /**
     * 计算班次内当前计划量对应的实际结束时间。
     * <p>这里不是简单按“班次内占比”直接推时刻，而是先把计划量换算成所需净生产秒数，再把停机/清洗空档顺延进去。</p>
     *
     * @param devicePlanShutList 设备停机计划
     * @param cleaningWindowList 清洗时间窗口
     * @param machineCode 机台编号
     * @param effectiveStartTime 班次实际开产时间
     * @param shiftEndTime 班次结束时间
     * @param allocationQty 当前班次分配量
     * @param shiftMaxQty 当前班次最大可排量
     * @return 实际结束时间
     */
    public static Date resolveShiftPlanEndTime(List<MdmDevicePlanShut> devicePlanShutList,
                                               List<MachineCleaningWindowDTO> cleaningWindowList,
                                               String machineCode,
                                               Date effectiveStartTime,
                                               Date shiftEndTime,
                                               int allocationQty,
                                               int shiftMaxQty) {
        return resolveShiftPlanEndTime(devicePlanShutList, cleaningWindowList, null, machineCode,
                effectiveStartTime, shiftEndTime, allocationQty, shiftMaxQty);
    }

    /**
     * 计算班次内当前计划量对应的实际结束时间。
     *
     * @param devicePlanShutList 设备停机计划
     * @param cleaningWindowList 清洗时间窗口
     * @param maintenanceWindowList 保养时间窗口
     * @param machineCode 机台编号
     * @param effectiveStartTime 班次实际开产时间
     * @param shiftEndTime 班次结束时间
     * @param allocationQty 当前班次分配量
     * @param shiftMaxQty 当前班次最大可排量
     * @return 实际结束时间
     */
    public static Date resolveShiftPlanEndTime(List<MdmDevicePlanShut> devicePlanShutList,
                                               List<MachineCleaningWindowDTO> cleaningWindowList,
                                               List<MachineMaintenanceWindowDTO> maintenanceWindowList,
                                               String machineCode,
                                               Date effectiveStartTime,
                                               Date shiftEndTime,
                                               int allocationQty,
                                               int shiftMaxQty) {
        if (Objects.isNull(effectiveStartTime) || Objects.isNull(shiftEndTime) || allocationQty <= 0 || shiftMaxQty <= 0) {
            return effectiveStartTime;
        }
        long netProductiveSeconds = resolveNetProductiveSeconds(
                devicePlanShutList, cleaningWindowList, maintenanceWindowList, machineCode, effectiveStartTime, shiftEndTime);
        if (netProductiveSeconds <= 0) {
            return effectiveStartTime;
        }
        long requiredProductiveSeconds = BigDecimal.valueOf(allocationQty)
                .multiply(BigDecimal.valueOf(netProductiveSeconds))
                .divide(BigDecimal.valueOf(shiftMaxQty), 0, RoundingMode.UP)
                .longValue();
        return resolveCompletionTimeWithDowntimes(
                devicePlanShutList, cleaningWindowList, maintenanceWindowList, machineCode, effectiveStartTime,
                requiredProductiveSeconds);
    }

    /**
     * 推导考虑计划停机空档后的完工时间。
     * <p>语义：生产只能在非停机时间推进，遇到停机窗口自动顺延。</p>
     *
     * @param devicePlanShutList 设备计划停机列表
     * @param machineCode 机台编号
     * @param productionStartTime 生产开始时间
     * @param productionSeconds 纯生产所需秒数（不含停机空档）
     * @return 完工时间
     */
    public static Date resolveCompletionTimeWithPlannedStops(List<MdmDevicePlanShut> devicePlanShutList,
                                                             String machineCode,
                                                             Date productionStartTime,
                                                             long productionSeconds) {
        if (Objects.isNull(productionStartTime)) {
            return null;
        }
        if (productionSeconds <= 0) {
            return productionStartTime;
        }
        if (CollectionUtils.isEmpty(devicePlanShutList) || StringUtils.isEmpty(machineCode)) {
            return new Date(productionStartTime.getTime() + productionSeconds * 1000L);
        }

        List<MdmDevicePlanShut> machineStops = new ArrayList<>();
        for (MdmDevicePlanShut planShut : devicePlanShutList) {
            if (Objects.isNull(planShut)
                    || !StringUtils.equals(machineCode, planShut.getMachineCode())
                    || Objects.isNull(planShut.getBeginDate())
                    || Objects.isNull(planShut.getEndDate())
                    || !planShut.getBeginDate().before(planShut.getEndDate())
                    || !planShut.getEndDate().after(productionStartTime)) {
                continue;
            }
            machineStops.add(planShut);
        }
        if (machineStops.isEmpty()) {
            return new Date(productionStartTime.getTime() + productionSeconds * 1000L);
        }
        machineStops.sort(Comparator.comparing(MdmDevicePlanShut::getBeginDate)
                .thenComparing(MdmDevicePlanShut::getEndDate));

        long remainingSeconds = productionSeconds;
        Date cursor = productionStartTime;
        for (MdmDevicePlanShut stop : machineStops) {
            Date stopStartTime = stop.getBeginDate();
            Date stopEndTime = stop.getEndDate();

            if (!cursor.before(stopEndTime)) {
                continue;
            }

            if (stopStartTime.after(cursor)) {
                long productiveSeconds = (stopStartTime.getTime() - cursor.getTime()) / 1000L;
                if (productiveSeconds >= remainingSeconds) {
                    return new Date(cursor.getTime() + remainingSeconds * 1000L);
                }
                if (productiveSeconds > 0) {
                    remainingSeconds -= productiveSeconds;
                    cursor = stopStartTime;
                }
            }

            if (cursor.before(stopEndTime)) {
                cursor = stopEndTime;
            }
        }

        return new Date(cursor.getTime() + remainingSeconds * 1000L);
    }

    /**
     * 推导考虑停机与清洗空档后的完工时间。
     * <p>语义与 {@link #resolveCompletionTimeWithPlannedStops(List, String, Date, long)} 一致，
     * 只是把清洗窗口也当作不可生产时间一起顺延。</p>
     *
     * @param devicePlanShutList 设备停机计划
     * @param cleaningWindowList 清洗时间窗口
     * @param machineCode 机台编号
     * @param productionStartTime 生产开始时间
     * @param productionSeconds 纯生产所需秒数
     * @return 完工时间
     */
    public static Date resolveCompletionTimeWithDowntimes(List<MdmDevicePlanShut> devicePlanShutList,
                                                          List<MachineCleaningWindowDTO> cleaningWindowList,
                                                          String machineCode,
                                                          Date productionStartTime,
                                                          long productionSeconds) {
        return resolveCompletionTimeWithDowntimes(devicePlanShutList, cleaningWindowList, null, machineCode,
                productionStartTime, productionSeconds);
    }

    /**
     * 推导考虑停机、清洗与保养空档后的完工时间。
     *
     * @param devicePlanShutList 设备停机计划
     * @param cleaningWindowList 清洗时间窗口
     * @param maintenanceWindowList 保养时间窗口
     * @param machineCode 机台编号
     * @param productionStartTime 生产开始时间
     * @param productionSeconds 纯生产所需秒数
     * @return 完工时间
     */
    public static Date resolveCompletionTimeWithDowntimes(List<MdmDevicePlanShut> devicePlanShutList,
                                                          List<MachineCleaningWindowDTO> cleaningWindowList,
                                                          List<MachineMaintenanceWindowDTO> maintenanceWindowList,
                                                          String machineCode,
                                                          Date productionStartTime,
                                                          long productionSeconds) {
        if (Objects.isNull(productionStartTime)) {
            return null;
        }
        if (productionSeconds <= 0) {
            return productionStartTime;
        }
        List<Date[]> downtimeIntervals = collectMergedDowntimeIntervals(
                devicePlanShutList, cleaningWindowList, maintenanceWindowList, machineCode, productionStartTime, null);
        if (downtimeIntervals.isEmpty()) {
            return new Date(productionStartTime.getTime() + productionSeconds * 1000L);
        }

        long remainingSeconds = productionSeconds;
        Date cursor = productionStartTime;
        for (Date[] downtimeInterval : downtimeIntervals) {
            Date downtimeStartTime = downtimeInterval[0];
            Date downtimeEndTime = downtimeInterval[1];

            if (!cursor.before(downtimeEndTime)) {
                continue;
            }
            if (downtimeStartTime.after(cursor)) {
                long productiveSeconds = (downtimeStartTime.getTime() - cursor.getTime()) / 1000L;
                if (productiveSeconds >= remainingSeconds) {
                    return new Date(cursor.getTime() + remainingSeconds * 1000L);
                }
                if (productiveSeconds > 0) {
                    remainingSeconds -= productiveSeconds;
                    cursor = downtimeStartTime;
                }
            }
            if (cursor.before(downtimeEndTime)) {
                cursor = downtimeEndTime;
            }
        }
        return new Date(cursor.getTime() + remainingSeconds * 1000L);
    }

    /**
     * 计算指定时间窗内的停机与清洗重叠秒数。
     * <p>返回的是“所有不可生产区间并集”的总秒数，不会因为停机与清洗重叠而重复累计。</p>
     *
     * @param devicePlanShutList 设备停机计划
     * @param cleaningWindowList 清洗时间窗口
     * @param machineCode 机台编号
     * @param windowStartTime 时间窗开始时间
     * @param windowEndTime 时间窗结束时间
     * @return 重叠秒数
     */
    public static long resolveDowntimeOverlapSeconds(List<MdmDevicePlanShut> devicePlanShutList,
                                                     List<MachineCleaningWindowDTO> cleaningWindowList,
                                                     String machineCode,
                                                     Date windowStartTime,
                                                     Date windowEndTime) {
        return resolveDowntimeOverlapSeconds(devicePlanShutList, cleaningWindowList, null, machineCode,
                windowStartTime, windowEndTime);
    }

    /**
     * 计算指定时间窗内的停机、清洗与保养重叠秒数。
     *
     * @param devicePlanShutList 设备停机计划
     * @param cleaningWindowList 清洗时间窗口
     * @param maintenanceWindowList 保养时间窗口
     * @param machineCode 机台编号
     * @param windowStartTime 时间窗开始时间
     * @param windowEndTime 时间窗结束时间
     * @return 重叠秒数
     */
    public static long resolveDowntimeOverlapSeconds(List<MdmDevicePlanShut> devicePlanShutList,
                                                     List<MachineCleaningWindowDTO> cleaningWindowList,
                                                     List<MachineMaintenanceWindowDTO> maintenanceWindowList,
                                                     String machineCode,
                                                     Date windowStartTime,
                                                     Date windowEndTime) {
        List<Date[]> downtimeIntervals = collectMergedDowntimeIntervals(
                devicePlanShutList, cleaningWindowList, maintenanceWindowList, machineCode, windowStartTime, windowEndTime);
        return resolveIntervalDurationSeconds(downtimeIntervals);
    }

    private static int resolveCleaningLossQty(List<MachineCleaningWindowDTO> cleaningWindowList,
                                              List<Date[]> stopIntervals,
                                              Date windowStartTime,
                                              Date windowEndTime,
                                              int shiftCapacity,
                                              int lhTimeSeconds,
                                              int mouldQty,
                                              long shiftDurationSeconds) {
        if (CollectionUtils.isEmpty(cleaningWindowList)
                || Objects.isNull(windowStartTime)
                || Objects.isNull(windowEndTime)
                || !windowStartTime.before(windowEndTime)) {
            return 0;
        }
        int cleaningLossQty = 0;
        cleaningLossQty += resolveSandBlastLossQty(
                cleaningWindowList, stopIntervals, windowStartTime, windowEndTime,
                shiftCapacity, lhTimeSeconds, mouldQty, shiftDurationSeconds);
        return cleaningLossQty;
    }

    private static long resolveDryIceAdjustedAvailableSeconds(List<MachineCleaningWindowDTO> cleaningWindowList,
                                                              List<Date[]> stopIntervals,
                                                              Date windowStartTime,
                                                              Date windowEndTime,
                                                              long availableSeconds) {
        if (availableSeconds <= 0 || CollectionUtils.isEmpty(cleaningWindowList)) {
            return Math.max(availableSeconds, 0L);
        }
        long dryIceOverlapSeconds = 0L;
        List<Date[]> dryIceIntervals = collectMergedCleaningIntervals(
                cleaningWindowList, CleaningTypeEnum.DRY_ICE.getCode(), windowStartTime, windowEndTime);
        for (Date[] dryIceInterval : dryIceIntervals) {
            long effectiveOverlapSeconds = resolveEffectiveCleaningOverlapSeconds(
                    dryIceInterval, stopIntervals, windowStartTime, windowEndTime);
            dryIceOverlapSeconds += Math.max(effectiveOverlapSeconds, 0L);
        }
        return Math.max(availableSeconds - dryIceOverlapSeconds, 0L);
    }

    private static long resolveMaintenanceAdjustedAvailableSeconds(List<MachineMaintenanceWindowDTO> maintenanceWindowList,
                                                                   List<Date[]> stopIntervals,
                                                                   Date windowStartTime,
                                                                   Date windowEndTime,
                                                                   long availableSeconds) {
        if (availableSeconds <= 0 || CollectionUtils.isEmpty(maintenanceWindowList)) {
            return Math.max(availableSeconds, 0L);
        }
        long maintenanceOverlapSeconds = 0L;
        List<Date[]> maintenanceIntervals = collectMergedMaintenanceIntervals(
                maintenanceWindowList, windowStartTime, windowEndTime);
        for (Date[] maintenanceInterval : maintenanceIntervals) {
            long effectiveOverlapSeconds = resolveEffectiveCleaningOverlapSeconds(
                    maintenanceInterval, stopIntervals, windowStartTime, windowEndTime);
            maintenanceOverlapSeconds += Math.max(effectiveOverlapSeconds, 0L);
        }
        return Math.max(availableSeconds - maintenanceOverlapSeconds, 0L);
    }

    private static int resolveSandBlastLossQty(List<MachineCleaningWindowDTO> cleaningWindowList,
                                               List<Date[]> stopIntervals,
                                               Date windowStartTime,
                                               Date windowEndTime,
                                               int shiftCapacity,
                                               int lhTimeSeconds,
                                               int mouldQty,
                                               long shiftDurationSeconds) {
        int totalLossQty = 0;
        // 喷砂按“清洗重叠时长对应能生产多少条”来扣减，因此直接复用统一班产折算公式。
        List<Date[]> sandBlastIntervals = collectMergedCleaningIntervals(
                cleaningWindowList, CleaningTypeEnum.SAND_BLAST.getCode(), windowStartTime, windowEndTime);
        for (Date[] sandBlastInterval : sandBlastIntervals) {
            long effectiveOverlapSeconds = resolveEffectiveCleaningOverlapSeconds(
                    sandBlastInterval, stopIntervals, windowStartTime, windowEndTime);
            if (effectiveOverlapSeconds <= 0) {
                continue;
            }
            totalLossQty += resolveShiftCapacity(
                    shiftCapacity, lhTimeSeconds, mouldQty, shiftDurationSeconds, effectiveOverlapSeconds);
        }
        return totalLossQty;
    }

    private static long resolveEffectiveCleaningOverlapSeconds(Date[] cleaningInterval,
                                                               List<Date[]> stopIntervals,
                                                               Date windowStartTime,
                                                               Date windowEndTime) {
        List<Date[]> scopedCleaningIntervals = new ArrayList<>(1);
        Date overlapStartTime = later(cleaningInterval[0], windowStartTime);
        Date overlapEndTime = earlier(cleaningInterval[1], windowEndTime);
        if (!overlapStartTime.before(overlapEndTime)) {
            return 0L;
        }
        scopedCleaningIntervals.add(new Date[]{overlapStartTime, overlapEndTime});
        long cleaningOverlapSeconds = resolveIntervalDurationSeconds(scopedCleaningIntervals);
        // 清洗与停机重叠的时间已经在停机侧扣过，这里需要剔除，避免再次计入清洗损失。
        long duplicatedStopSeconds = resolveIntervalIntersectionSeconds(scopedCleaningIntervals, stopIntervals);
        return Math.max(cleaningOverlapSeconds - duplicatedStopSeconds, 0L);
    }

    /**
     * 收集并合并“停机 + 清洗”的所有不可生产区间。
     */
    private static List<Date[]> collectMergedDowntimeIntervals(List<MdmDevicePlanShut> devicePlanShutList,
                                                               List<MachineCleaningWindowDTO> cleaningWindowList,
                                                               String machineCode,
                                                               Date windowStartTime,
                                                               Date windowEndTime) {
        return collectMergedDowntimeIntervals(devicePlanShutList, cleaningWindowList, null, machineCode,
                windowStartTime, windowEndTime);
    }

    /**
     * 收集并合并“停机 + 清洗 + 保养”的所有不可生产区间。
     */
    private static List<Date[]> collectMergedDowntimeIntervals(List<MdmDevicePlanShut> devicePlanShutList,
                                                               List<MachineCleaningWindowDTO> cleaningWindowList,
                                                               List<MachineMaintenanceWindowDTO> maintenanceWindowList,
                                                               String machineCode,
                                                               Date windowStartTime,
                                                               Date windowEndTime) {
        List<Date[]> downtimeIntervals = new ArrayList<>();
        downtimeIntervals.addAll(collectMergedPlannedStopIntervals(
                devicePlanShutList, machineCode, windowStartTime, windowEndTime));
        downtimeIntervals.addAll(collectMergedCleaningIntervals(
                cleaningWindowList, null, windowStartTime, windowEndTime));
        downtimeIntervals.addAll(collectMergedMaintenanceIntervals(
                maintenanceWindowList, windowStartTime, windowEndTime));
        return mergeIntervals(downtimeIntervals);
    }

    /**
     * 收集并合并指定时间窗内的停机区间。
     * <p>windowEndTime 允许为 null，表示一直统计到停机记录自身结束时刻。</p>
     * <p>本方法对所有非清洗类设备停机计划统一处理，包括精度校验、润滑、巡检点检、预见性维护、
     * 预防性维护、计划性维修、临时性故障、盘点等。所有停机类型均按 beginDate～endDate 与目标时间窗
     * 取交集后合并区间，统一折算扣减班次可生产秒数。</p>
     * <p>盘点（{@link com.zlt.aps.lh.api.enums.MachineStopTypeEnum#TAKE_STOCK}）与其他停机类型一样
     * 只扣时间产能，不触发换模、换活字块、首检、预热等逻辑；盘点结束后机台可直接进入生产排产。</p>
     */
    private static List<Date[]> collectMergedPlannedStopIntervals(List<MdmDevicePlanShut> devicePlanShutList,
                                                                  String machineCode,
                                                                  Date windowStartTime,
                                                                  Date windowEndTime) {
        List<Date[]> stopIntervals = new ArrayList<>();
        if (CollectionUtils.isEmpty(devicePlanShutList)
                || StringUtils.isEmpty(machineCode)
                || Objects.isNull(windowStartTime)) {
            return stopIntervals;
        }
        for (MdmDevicePlanShut planShut : devicePlanShutList) {
            if (Objects.isNull(planShut)
                    || !StringUtils.equals(machineCode, planShut.getMachineCode())
                    || Objects.isNull(planShut.getBeginDate())
                    || Objects.isNull(planShut.getEndDate())
                    || !planShut.getBeginDate().before(planShut.getEndDate())) {
                continue;
            }
            Date overlapStartTime = later(planShut.getBeginDate(), windowStartTime);
            Date overlapEndTime = windowEndTime == null
                    ? planShut.getEndDate()
                    : earlier(planShut.getEndDate(), windowEndTime);
            if (overlapStartTime.before(overlapEndTime)) {
                stopIntervals.add(new Date[]{overlapStartTime, overlapEndTime});
            }
        }
        return mergeIntervals(stopIntervals);
    }

    /**
     * 收集并合并指定时间窗内的清洗区间。
     * <p>cleanType 为空时表示不过滤清洗类型，统一返回所有清洗窗口。</p>
     */
    private static List<Date[]> collectMergedCleaningIntervals(List<MachineCleaningWindowDTO> cleaningWindowList,
                                                               String cleanType,
                                                               Date windowStartTime,
                                                               Date windowEndTime) {
        List<Date[]> cleaningIntervals = new ArrayList<>();
        if (CollectionUtils.isEmpty(cleaningWindowList) || Objects.isNull(windowStartTime)) {
            return cleaningIntervals;
        }
        for (MachineCleaningWindowDTO cleaningWindow : cleaningWindowList) {
            if (Objects.isNull(cleaningWindow)
                    || Objects.isNull(cleaningWindow.getCleanStartTime())
                    || Objects.isNull(cleaningWindow.getCleanEndTime())
                    || !cleaningWindow.getCleanStartTime().before(cleaningWindow.getCleanEndTime())) {
                continue;
            }
            if (StringUtils.isNotEmpty(cleanType)
                    && !StringUtils.equals(cleanType, cleaningWindow.getCleanType())) {
                continue;
            }
            Date overlapStartTime = later(cleaningWindow.getCleanStartTime(), windowStartTime);
            Date overlapEndTime = windowEndTime == null
                    ? cleaningWindow.getCleanEndTime()
                    : earlier(cleaningWindow.getCleanEndTime(), windowEndTime);
            if (overlapStartTime.before(overlapEndTime)) {
                cleaningIntervals.add(new Date[]{overlapStartTime, overlapEndTime});
            }
        }
        return mergeIntervals(cleaningIntervals);
    }

    /**
     * 收集并合并指定时间窗内的保养区间。
     */
    private static List<Date[]> collectMergedMaintenanceIntervals(List<MachineMaintenanceWindowDTO> maintenanceWindowList,
                                                                  Date windowStartTime,
                                                                  Date windowEndTime) {
        List<Date[]> maintenanceIntervals = new ArrayList<>();
        if (CollectionUtils.isEmpty(maintenanceWindowList) || Objects.isNull(windowStartTime)) {
            return maintenanceIntervals;
        }
        for (MachineMaintenanceWindowDTO maintenanceWindow : maintenanceWindowList) {
            if (Objects.isNull(maintenanceWindow)
                    || Objects.isNull(maintenanceWindow.getMaintenanceStartTime())
                    || Objects.isNull(maintenanceWindow.getMaintenanceEndTime())
                    || !maintenanceWindow.getMaintenanceStartTime().before(maintenanceWindow.getMaintenanceEndTime())) {
                continue;
            }
            Date overlapStartTime = later(maintenanceWindow.getMaintenanceStartTime(), windowStartTime);
            Date overlapEndTime = windowEndTime == null
                    ? maintenanceWindow.getMaintenanceEndTime()
                    : earlier(maintenanceWindow.getMaintenanceEndTime(), windowEndTime);
            if (overlapStartTime.before(overlapEndTime)) {
                maintenanceIntervals.add(new Date[]{overlapStartTime, overlapEndTime});
            }
        }
        return mergeIntervals(maintenanceIntervals);
    }

    /**
     * 合并有交叉或首尾相接的区间，统一输出不重叠区间列表。
     */
    private static List<Date[]> mergeIntervals(List<Date[]> intervals) {
        List<Date[]> mergedIntervals = new ArrayList<>();
        if (CollectionUtils.isEmpty(intervals)) {
            return mergedIntervals;
        }
        intervals.sort((left, right) -> {
            int startCmp = left[0].compareTo(right[0]);
            if (startCmp != 0) {
                return startCmp;
            }
            return left[1].compareTo(right[1]);
        });
        Date mergedStartTime = intervals.get(0)[0];
        Date mergedEndTime = intervals.get(0)[1];
        for (int i = 1; i < intervals.size(); i++) {
            Date currentStartTime = intervals.get(i)[0];
            Date currentEndTime = intervals.get(i)[1];
            if (!currentStartTime.after(mergedEndTime)) {
                if (currentEndTime.after(mergedEndTime)) {
                    mergedEndTime = currentEndTime;
                }
                continue;
            }
            mergedIntervals.add(new Date[]{mergedStartTime, mergedEndTime});
            mergedStartTime = currentStartTime;
            mergedEndTime = currentEndTime;
        }
        mergedIntervals.add(new Date[]{mergedStartTime, mergedEndTime});
        return mergedIntervals;
    }

    /**
     * 统计区间列表总时长（秒）。
     */
    private static long resolveIntervalDurationSeconds(List<Date[]> intervals) {
        long totalSeconds = 0L;
        for (Date[] interval : intervals) {
            totalSeconds += intervalDurationSeconds(interval[0], interval[1]);
        }
        return Math.max(totalSeconds, 0L);
    }

    /**
     * 统计两组区间的交集总时长（秒）。
     * <p>用于识别“清洗已经被停机覆盖”的重叠部分，避免重复扣减。</p>
     */
    private static long resolveIntervalIntersectionSeconds(List<Date[]> leftIntervals, List<Date[]> rightIntervals) {
        if (CollectionUtils.isEmpty(leftIntervals) || CollectionUtils.isEmpty(rightIntervals)) {
            return 0L;
        }
        long overlapSeconds = 0L;
        int leftIndex = 0;
        int rightIndex = 0;
        while (leftIndex < leftIntervals.size() && rightIndex < rightIntervals.size()) {
            Date[] leftInterval = leftIntervals.get(leftIndex);
            Date[] rightInterval = rightIntervals.get(rightIndex);
            Date overlapStartTime = later(leftInterval[0], rightInterval[0]);
            Date overlapEndTime = earlier(leftInterval[1], rightInterval[1]);
            if (overlapStartTime.before(overlapEndTime)) {
                overlapSeconds += intervalDurationSeconds(overlapStartTime, overlapEndTime);
            }
            if (leftInterval[1].before(rightInterval[1])) {
                leftIndex++;
            } else {
                rightIndex++;
            }
        }
        return Math.max(overlapSeconds, 0L);
    }

    /**
     * 计算单个时间区间时长（秒）。
     */
    private static long intervalDurationSeconds(Date startTime, Date endTime) {
        if (Objects.isNull(startTime) || Objects.isNull(endTime) || !startTime.before(endTime)) {
            return 0L;
        }
        return (endTime.getTime() - startTime.getTime()) / 1000L;
    }

    /**
     * 取两个时间中的较晚值。
     */
    private static Date later(Date left, Date right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return left.after(right) ? left : right;
    }

    /**
     * 取两个时间中的较早值。
     */
    private static Date earlier(Date left, Date right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return left.before(right) ? left : right;
    }
}
