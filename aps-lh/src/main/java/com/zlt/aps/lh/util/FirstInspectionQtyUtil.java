package com.zlt.aps.lh.util;

import com.zlt.aps.lh.api.constant.LhScheduleConstant;
import com.zlt.aps.lh.api.constant.LhScheduleParamConstant;
import com.zlt.aps.lh.api.domain.entity.LhScheduleResult;
import com.zlt.aps.lh.api.domain.vo.LhShiftConfigVO;
import com.zlt.aps.lh.api.enums.ScheduleTypeEnum;
import com.zlt.aps.lh.context.LhScheduleConfig;
import com.zlt.aps.lh.context.LhScheduleContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.CollectionUtils;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 换模与换活字块首检数量工具。
 *
 * <p>业务口径：换模和换活字块都需要首检，切换耗时已经包含首检，
 * 首检数量只影响班次计划量归属和班产占用，不额外增加切换耗时。</p>
 *
 * @author APS
 */
@Slf4j
public final class FirstInspectionQtyUtil {

    /** 单控机台首检数量折算系数，与单控班产 /2 口径一致 */
    private static final int SINGLE_CONTROL_FIRST_INSPECTION_DIVISOR = 2;

    /** 同班次前2台使用前2台首检参数 */
    private static final int FIRST_TWO_INSPECTION_LIMIT = 2;

    /** 班次首检计数键分隔符 */
    private static final String SHIFT_COUNTER_KEY_SEPARATOR = "#";

    private FirstInspectionQtyUtil() {
    }

    /**
     * 获取同班次非前2台普通机台首检数量。
     *
     * <p>单控机台（机台编码以 L/R 结尾）首检数量需折半，请使用
     * {@link #getFirstInspectionQty(LhScheduleContext, String)}。</p>
     *
     * @param context 排程上下文
     * @return 首检数量，未配置时默认 2，负数按 0 处理
     */
    public static int getFirstInspectionQty(LhScheduleContext context) {
        LhScheduleConfig config = Objects.isNull(context) ? null : context.getScheduleConfig();
        if (Objects.isNull(config)) {
            return LhScheduleConstant.FIRST_INSPECTION_QTY;
        }
        return Math.max(0, config.getFirstInspectionQty());
    }

    /**
     * 获取同班次前2台普通机台首检数量。
     *
     * @param context 排程上下文
     * @return 首检数量，未配置时默认 4，负数按 0 处理
     */
    public static int getFirstTwoFirstInspectionQty(LhScheduleContext context) {
        LhScheduleConfig config = Objects.isNull(context) ? null : context.getScheduleConfig();
        if (Objects.isNull(config)) {
            return LhScheduleConstant.FIRST_TWO_FIRST_INSPECTION_QTY;
        }
        return Math.max(0, config.getFirstTwoFirstInspectionQty());
    }

    /**
     * 获取按机台类型折算后的首检数量。
     *
     * <p>业务口径：普通机台首检数量 = 硫化参数 SYS0303003（默认 2）；
     * 单控机台（机台编码以 L/R 结尾，例如 K1501L、K1501R）首检数量 = 参数值 / 2，
     * 向下取整，与 {@link ShiftCapacityResolverUtil#resolveRuntimeShiftCapacity} 的单控班产折半口径一致。
     * 单控首检数量同样计入硫化余量和排产量。</p>
     *
     * @param context     排程上下文
     * @param machineCode 运行态机台编码
     * @return 折算后的首检数量，单控机台按参数折半向下取整
     */
    public static int getFirstInspectionQty(LhScheduleContext context, String machineCode) {
        int configuredQty = getFirstInspectionQty(context);
        if (!LhSingleControlMachineUtil.isSingleMouldMachine(machineCode)) {
            return configuredQty;
        }
        // 单控机台首检数量折半，向下取整，与单控班产 /2 口径对齐
        return configuredQty / SINGLE_CONTROL_FIRST_INSPECTION_DIVISOR;
    }

    /**
     * 根据换模完成时间解析首检数量归属班次。
     *
     * <p>命中规则采用完成时间小于等于班次结束时间；当完成时间正好等于两个班次边界时，
     * 归入前一个刚结束班次。</p>
     *
     * @param shifts 排程窗口班次
     * @param mouldChangeCompleteTime 换模完成时间
     * @return 首检归属班次，未命中返回 null
     */
    public static LhShiftConfigVO resolveAttributionShift(List<LhShiftConfigVO> shifts,
                                                          Date mouldChangeCompleteTime) {
        if (CollectionUtils.isEmpty(shifts) || Objects.isNull(mouldChangeCompleteTime)) {
            return null;
        }
        for (LhShiftConfigVO shift : shifts) {
            if (Objects.isNull(shift)
                    || Objects.isNull(shift.getShiftStartDateTime())
                    || Objects.isNull(shift.getShiftEndDateTime())) {
                continue;
            }
            if (!mouldChangeCompleteTime.before(shift.getShiftStartDateTime())
                    && !mouldChangeCompleteTime.after(shift.getShiftEndDateTime())) {
                return shift;
            }
        }
        return null;
    }

    /**
     * 预读当前班次下一台首检的顺序，不写入计数器。
     *
     * @param context 排程上下文
     * @param attributionShift 首检归属班次
     * @return 下一台首检顺序，从1开始
     */
    public static int resolveNextFirstInspectionSequence(LhScheduleContext context,
                                                         LhShiftConfigVO attributionShift) {
        String counterKey = buildShiftCounterKey(attributionShift);
        if (Objects.isNull(context) || Objects.isNull(counterKey)) {
            return 1;
        }
        Integer currentCount = context.getShiftFirstInspectionCountMap().get(counterKey);
        return Math.max(0, Objects.isNull(currentCount) ? 0 : currentCount) + 1;
    }

    /**
     * 登记当前班次一台首检，换模和换活字块共用同一个计数器。
     *
     * @param context 排程上下文
     * @param attributionShift 首检归属班次
     * @return 登记后的首检顺序，从1开始
     */
    public static int recordFirstInspectionSequence(LhScheduleContext context,
                                                    LhShiftConfigVO attributionShift) {
        String counterKey = buildShiftCounterKey(attributionShift);
        if (Objects.isNull(context) || Objects.isNull(counterKey)) {
            return 1;
        }
        int sequence = resolveNextFirstInspectionSequence(context, attributionShift);
        context.getShiftFirstInspectionCountMap().put(counterKey, sequence);
        return sequence;
    }

    /**
     * 回滚当前班次一台首检登记。
     *
     * @param context 排程上下文
     * @param attributionShift 首检归属班次
     */
    public static void rollbackFirstInspectionSequence(LhScheduleContext context,
                                                       LhShiftConfigVO attributionShift) {
        String counterKey = buildShiftCounterKey(attributionShift);
        if (Objects.isNull(context) || Objects.isNull(counterKey)) {
            return;
        }
        Integer currentCount = context.getShiftFirstInspectionCountMap().get(counterKey);
        if (Objects.isNull(currentCount) || currentCount <= 0) {
            return;
        }
        if (currentCount == 1) {
            context.getShiftFirstInspectionCountMap().remove(counterKey);
            return;
        }
        context.getShiftFirstInspectionCountMap().put(counterKey, currentCount - 1);
    }

    /**
     * 解析当前首检顺序使用的参数编码。
     *
     * @param sequence 当前班次首检顺序
     * @return 参数编码
     */
    public static String resolveFirstInspectionParamCode(int sequence) {
        return sequence <= FIRST_TWO_INSPECTION_LIMIT
                ? LhScheduleParamConstant.FIRST_TWO_FIRST_INSPECTION_QTY
                : LhScheduleParamConstant.FIRST_INSPECTION_QTY;
    }

    /**
     * 解析当前首检顺序使用的参数原始数量。
     *
     * @param context 排程上下文
     * @param sequence 当前班次首检顺序
     * @return 参数原始首检数量
     */
    public static int resolveRawFirstInspectionQty(LhScheduleContext context, int sequence) {
        if (sequence <= FIRST_TWO_INSPECTION_LIMIT) {
            return getFirstTwoFirstInspectionQty(context);
        }
        return getFirstInspectionQty(context);
    }

    /**
     * 解析当前首检顺序和机台类型折算后的首检数量。
     *
     * @param context 排程上下文
     * @param sequence 当前班次首检顺序
     * @param machineCode 机台编码
     * @param logOddSingleControlParam 是否记录单控奇数参数提示
     * @return 折算后的首检数量
     */
    public static int resolveAdjustedFirstInspectionQty(LhScheduleContext context,
                                                        int sequence,
                                                        String machineCode,
                                                        boolean logOddSingleControlParam) {
        int rawQty = resolveRawFirstInspectionQty(context, sequence);
        if (!LhSingleControlMachineUtil.isSingleMouldMachine(machineCode)) {
            return rawQty;
        }
        if (logOddSingleControlParam && rawQty % SINGLE_CONTROL_FIRST_INSPECTION_DIVISOR != 0) {
            log.warn("单控机台首检参数为奇数，按项目既有单控班产折半口径向下取整, machineCode: {}, "
                            + "首检顺序: {}, 参数编码: {}, 参数值: {}, 折算后: {}",
                    machineCode, sequence, resolveFirstInspectionParamCode(sequence), rawQty,
                    rawQty / SINGLE_CONTROL_FIRST_INSPECTION_DIVISOR);
        }
        return rawQty / SINGLE_CONTROL_FIRST_INSPECTION_DIVISOR;
    }

    /**
     * 预读当前班次下一台首检的有效数量，不写入计数器。
     *
     * @param context 排程上下文
     * @param attributionShift 首检归属班次
     * @param shiftCapacity 运行态班产
     * @param remainingQty 当前剩余目标量
     * @param scheduleType 排程类型
     * @param machineCode 机台编码
     * @return 受班产和目标量截断后的首检数量
     */
    public static int resolvePreviewFirstInspectionQty(LhScheduleContext context,
                                                       LhShiftConfigVO attributionShift,
                                                       int shiftCapacity,
                                                       int remainingQty,
                                                       String scheduleType,
                                                       String machineCode) {
        int sequence = resolveNextFirstInspectionSequence(context, attributionShift);
        return resolveEffectiveFirstInspectionQty(
                context, attributionShift, shiftCapacity, remainingQty, scheduleType, machineCode, sequence, false);
    }

    /**
     * 将普通换模首检数量写入归属班次。
     *
     * <p>首检数量参与排产量和硫化余量消耗，因此写入结果前会按剩余目标量与班产上限收敛。
     * 单控机台（L/R）首检数量按参数折半向下取整。</p>
     *
     * @param context 排程上下文
     * @param result 排程结果
     * @param shifts 排程窗口班次
     * @param mouldChangeCompleteTime 换模完成时间
     * @param shiftCapacity 运行态班产
     * @param remainingQty 当前结果剩余目标量
     * @param scheduleType 排程类型
     * @return 实际写入的首检数量
     */
    public static int addFirstInspectionQtyToResult(LhScheduleContext context,
                                                    LhScheduleResult result,
                                                    List<LhShiftConfigVO> shifts,
                                                    Date mouldChangeCompleteTime,
                                                    int shiftCapacity,
                                                    int remainingQty,
                                                    String scheduleType) {
        LhShiftConfigVO attributionShift = resolveAttributionShift(shifts, mouldChangeCompleteTime);
        int firstInspectionSequence = resolveNextFirstInspectionSequence(context, attributionShift);
        int firstInspectionQty = resolveEffectiveFirstInspectionQty(
                context, attributionShift, shiftCapacity, remainingQty, scheduleType,
                Objects.isNull(result) ? null : result.getLhMachineCode(), firstInspectionSequence, true);
        if (Objects.isNull(result) || Objects.isNull(attributionShift) || firstInspectionQty <= 0) {
            return 0;
        }
        Integer existingQty = ShiftFieldUtil.getShiftPlanQty(result, attributionShift.getShiftIndex());
        int basePlanQty = Math.max(0, Objects.isNull(existingQty) ? 0 : existingQty);
        int mergedQty = basePlanQty + firstInspectionQty;
        ShiftFieldUtil.setShiftPlanQty(result, attributionShift.getShiftIndex(), mergedQty,
                attributionShift.getShiftStartDateTime(), attributionShift.getShiftEndDateTime());
        recordFirstInspectionSequence(context, attributionShift);
        boolean singleControl = LhSingleControlMachineUtil.isSingleMouldMachine(result.getLhMachineCode());
        int rawFirstInspectionQty = resolveRawFirstInspectionQty(context, firstInspectionSequence);
        log.info("首检数量归属班次, scene: {}, batchNo: {}, materialCode: {}, machineCode: {}, "
                        + "是否单控: {}, 切换完成: {}, 归属日期: {}, 归属班次: {}, 当班首检顺序: {}, "
                        + "参数编码: {}, 参数原始首检数量: {}, 单控折半后首检数量: {}, "
                        + "扣除切换后的可生产量: {}, 加首检后的最终班次计划量: {}, 班产校验上限: {}, "
                        + "剩余目标量校验上限: {}, 说明: 切换耗时已包含首检，首检只影响数量归属和班产占用",
                resolveSceneName(scheduleType),
                result.getBatchNo(), result.getMaterialCode(), result.getLhMachineCode(),
                singleControl, LhScheduleTimeUtil.formatDateTime(mouldChangeCompleteTime),
                LhScheduleTimeUtil.formatDate(attributionShift.getWorkDate()), attributionShift.getShiftIndex(),
                firstInspectionSequence, resolveFirstInspectionParamCode(firstInspectionSequence),
                rawFirstInspectionQty, firstInspectionQty, basePlanQty, mergedQty,
                resolveShiftCapacityCap(context, attributionShift, shiftCapacity, scheduleType), remainingQty);
        return firstInspectionQty;
    }

    /**
     * 按首检数量调整班次产能图。
     *
     * <p>首检落在开产班次之前时，补入该班次首检数量；落在开产班次内时，
     * 首检数量和正常生产量共享该班次班产上限。
     * 单控机台（L/R）首检数量按参数折半向下取整。</p>
     *
     * @param context 排程上下文
     * @param shifts 排程窗口班次
     * @param mouldChangeCompleteTime 换模完成时间
     * @param shiftCapacityMap 正常生产产能图
     * @param shiftCapacity 运行态班产
     * @param remainingQty 当前剩余目标量
     * @param scheduleType 排程类型
     * @param machineCode 运行态机台编码，用于单控折半
     * @return 调整后的产能图
     */
    public static Map<Integer, Integer> applyFirstInspectionQtyToCapacityMap(
            LhScheduleContext context,
            List<LhShiftConfigVO> shifts,
            Date mouldChangeCompleteTime,
            Map<Integer, Integer> shiftCapacityMap,
            int shiftCapacity,
            int remainingQty,
            String scheduleType,
            String machineCode) {
        Map<Integer, Integer> adjustedMap = new LinkedHashMap<Integer, Integer>(
                CollectionUtils.isEmpty(shifts) ? 0 : shifts.size());
        if (CollectionUtils.isEmpty(shifts)) {
            return adjustedMap;
        }
        LhShiftConfigVO attributionShift = resolveAttributionShift(shifts, mouldChangeCompleteTime);
        int firstInspectionQty = resolvePreviewFirstInspectionQty(
                context, attributionShift, shiftCapacity, remainingQty, scheduleType, machineCode);
        for (LhShiftConfigVO shift : shifts) {
            if (Objects.isNull(shift) || Objects.isNull(shift.getShiftIndex())) {
                continue;
            }
            Integer originalCapacity = CollectionUtils.isEmpty(shiftCapacityMap)
                    ? null : shiftCapacityMap.get(shift.getShiftIndex());
            if (Objects.nonNull(attributionShift)
                    && Objects.equals(shift.getShiftIndex(), attributionShift.getShiftIndex())
                    && firstInspectionQty > 0) {
                int normalCapacity = Math.max(0, originalCapacity == null ? 0 : originalCapacity);
                int cap = resolveShiftCapacityCap(context, shift, shiftCapacity, scheduleType);
                adjustedMap.put(shift.getShiftIndex(),
                        firstInspectionQty + Math.min(normalCapacity, Math.max(0, cap - firstInspectionQty)));
                continue;
            }
            if (originalCapacity != null) {
                adjustedMap.put(shift.getShiftIndex(), Math.max(0, originalCapacity));
            }
        }
        return adjustedMap;
    }

    /**
     * 解析当前班次扣除首检后的正常生产上限。
     *
     * @param context 排程上下文
     * @param shift 当前班次
     * @param shiftMaxQty 当前班次正常生产上限
     * @param firstInspectionShiftIndex 首检归属班次
     * @param firstInspectionQty 首检数量
     * @param shiftCapacity 运行态班产
     * @param scheduleType 排程类型
     * @return 扣除首检占用后的正常生产上限
     */
    public static int resolveNormalCapacityAfterFirstInspection(
            LhScheduleContext context,
            LhShiftConfigVO shift,
            int shiftMaxQty,
            int firstInspectionShiftIndex,
            int firstInspectionQty,
            int shiftCapacity,
            String scheduleType) {
        if (Objects.isNull(shift) || !Objects.equals(shift.getShiftIndex(), firstInspectionShiftIndex)
                || firstInspectionQty <= 0) {
            return Math.max(0, shiftMaxQty);
        }
        int cap = resolveShiftCapacityCap(context, shift, shiftCapacity, scheduleType);
        return Math.min(Math.max(0, shiftMaxQty), Math.max(0, cap - firstInspectionQty));
    }

    /**
     * 解析首检归属班次索引。
     *
     * @param shifts 排程窗口班次
     * @param mouldChangeCompleteTime 换模完成时间
     * @return 班次索引，未命中返回 -1
     */
    public static int resolveAttributionShiftIndex(List<LhShiftConfigVO> shifts, Date mouldChangeCompleteTime) {
        LhShiftConfigVO shift = resolveAttributionShift(shifts, mouldChangeCompleteTime);
        return shift == null || shift.getShiftIndex() == null ? -1 : shift.getShiftIndex();
    }

    private static int resolveEffectiveFirstInspectionQty(LhScheduleContext context,
                                                          LhShiftConfigVO attributionShift,
                                                          int shiftCapacity,
                                                          int remainingQty,
                                                          String scheduleType,
                                                          String machineCode,
                                                          int sequence,
                                                          boolean logOddSingleControlParam) {
        if (Objects.isNull(attributionShift) || remainingQty <= 0) {
            return 0;
        }
        // 按机台类型折算首检数量：单控机台（L/R）按参数折半向下取整
        int configuredQty = resolveAdjustedFirstInspectionQty(
                context, sequence, machineCode, logOddSingleControlParam);
        if (configuredQty <= 0) {
            return 0;
        }
        int cap = resolveShiftCapacityCap(context, attributionShift, shiftCapacity, scheduleType);
        return Math.max(0, Math.min(Math.min(configuredQty, remainingQty), cap));
    }

    private static int resolveShiftCapacityCap(LhScheduleContext context,
                                               LhShiftConfigVO shift,
                                               int shiftCapacity,
                                               String scheduleType) {
        if (Objects.isNull(shift) || shiftCapacity <= 0) {
            return 0;
        }
        String configPlusShiftType = ShiftCapacityResolverUtil.resolveOddShiftCapacityPlusShiftType(context);
        return Math.max(0, ShiftCapacityResolverUtil.resolveActualShiftPlanQty(
                shiftCapacity, shift, configPlusShiftType, scheduleType));
    }

    private static String buildShiftCounterKey(LhShiftConfigVO shift) {
        if (Objects.isNull(shift) || Objects.isNull(shift.getShiftIndex())
                || Objects.isNull(shift.getWorkDate())) {
            return null;
        }
        return LhScheduleTimeUtil.formatDate(shift.getWorkDate())
                + SHIFT_COUNTER_KEY_SEPARATOR + shift.getShiftIndex();
    }

    private static String resolveSceneName(String scheduleType) {
        if (Objects.equals(ScheduleTypeEnum.TYPE_BLOCK.getCode(), scheduleType)) {
            return "换活字块";
        }
        return "换模";
    }
}
