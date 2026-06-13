package com.zlt.aps.lh.util;

import com.zlt.aps.lh.api.constant.LhScheduleConstant;
import com.zlt.aps.lh.api.domain.entity.LhScheduleResult;
import com.zlt.aps.lh.api.domain.vo.LhShiftConfigVO;
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
 * 普通换模首检数量工具。
 *
 * <p>业务口径：普通换模 8 小时已经包含首检，首检数量只影响班次计划量归属和班产占用，
 * 不额外增加换模耗时；换活字块不调用本工具。</p>
 *
 * @author APS
 */
@Slf4j
public final class FirstInspectionQtyUtil {

    private FirstInspectionQtyUtil() {
    }

    /**
     * 获取首检数量。
     *
     * @param context 排程上下文
     * @return 首检数量，未配置时默认 4，负数按 0 处理
     */
    public static int getFirstInspectionQty(LhScheduleContext context) {
        LhScheduleConfig config = context == null ? null : context.getScheduleConfig();
        if (Objects.isNull(config)) {
            return LhScheduleConstant.FIRST_INSPECTION_QTY;
        }
        return Math.max(0, config.getFirstInspectionQty());
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
     * 将普通换模首检数量写入归属班次。
     *
     * <p>首检数量参与排产量和硫化余量消耗，因此写入结果前会按剩余目标量与班产上限收敛。</p>
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
        int firstInspectionQty = resolveEffectiveFirstInspectionQty(
                context, attributionShift, shiftCapacity, remainingQty, scheduleType);
        if (Objects.isNull(result) || Objects.isNull(attributionShift) || firstInspectionQty <= 0) {
            return 0;
        }
        Integer existingQty = ShiftFieldUtil.getShiftPlanQty(result, attributionShift.getShiftIndex());
        int mergedQty = Math.max(0, existingQty == null ? 0 : existingQty) + firstInspectionQty;
        ShiftFieldUtil.setShiftPlanQty(result, attributionShift.getShiftIndex(), mergedQty,
                attributionShift.getShiftStartDateTime(), attributionShift.getShiftEndDateTime());
        log.info("普通换模首检数量归属班次, batchNo: {}, materialCode: {}, machineCode: {}, "
                        + "换模完成: {}, 归属班次: {}, 首检数量: {}, 班产上限: {}, 说明: 换模8小时已包含首检，"
                        + "首检只影响数量归属和班产占用",
                result.getBatchNo(), result.getMaterialCode(), result.getLhMachineCode(),
                LhScheduleTimeUtil.formatDateTime(mouldChangeCompleteTime), attributionShift.getShiftIndex(),
                firstInspectionQty, resolveShiftCapacityCap(context, attributionShift, shiftCapacity, scheduleType));
        return firstInspectionQty;
    }

    /**
     * 按首检数量调整班次产能图。
     *
     * <p>首检落在开产班次之前时，补入该班次首检数量；落在开产班次内时，
     * 首检数量和正常生产量共享该班次班产上限。</p>
     *
     * @param context 排程上下文
     * @param shifts 排程窗口班次
     * @param mouldChangeCompleteTime 换模完成时间
     * @param shiftCapacityMap 正常生产产能图
     * @param shiftCapacity 运行态班产
     * @param remainingQty 当前剩余目标量
     * @param scheduleType 排程类型
     * @return 调整后的产能图
     */
    public static Map<Integer, Integer> applyFirstInspectionQtyToCapacityMap(
            LhScheduleContext context,
            List<LhShiftConfigVO> shifts,
            Date mouldChangeCompleteTime,
            Map<Integer, Integer> shiftCapacityMap,
            int shiftCapacity,
            int remainingQty,
            String scheduleType) {
        Map<Integer, Integer> adjustedMap = new LinkedHashMap<Integer, Integer>(
                CollectionUtils.isEmpty(shifts) ? 0 : shifts.size());
        if (CollectionUtils.isEmpty(shifts)) {
            return adjustedMap;
        }
        LhShiftConfigVO attributionShift = resolveAttributionShift(shifts, mouldChangeCompleteTime);
        int firstInspectionQty = resolveEffectiveFirstInspectionQty(
                context, attributionShift, shiftCapacity, remainingQty, scheduleType);
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
                                                          String scheduleType) {
        if (Objects.isNull(attributionShift) || remainingQty <= 0) {
            return 0;
        }
        int configuredQty = getFirstInspectionQty(context);
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
}
