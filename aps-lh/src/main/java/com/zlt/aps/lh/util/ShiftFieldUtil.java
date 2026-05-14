package com.zlt.aps.lh.util;

import cn.hutool.core.bean.BeanUtil;
import com.zlt.aps.lh.api.constant.LhScheduleConstant;
import com.zlt.aps.lh.api.domain.entity.LhScheduleResult;
import com.zlt.aps.lh.api.domain.vo.LhShiftConfigVO;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Objects;

/**
 * 通过 Hutool BeanUtil 统一读写 {@link LhScheduleResult} 的 class1～class8 班次字段（与 shiftIndex 对应）。
 *
 * @author APS
 */
@Slf4j
public final class ShiftFieldUtil {

    private ShiftFieldUtil() {
    }

    /**
     * 设置班次计划量及起止时间
     *
     * @param result     排程结果
     * @param shiftIndex 班次索引 1～8
     * @param qty        计划量
     * @param startTime  开始时间
     * @param endTime    结束时间
     */
    public static void setShiftPlanQty(LhScheduleResult result, int shiftIndex, Integer qty,
            Date startTime, Date endTime) {
        if (!isValidIndex(shiftIndex)) {
            log.warn("未知班次索引: {}", shiftIndex);
            return;
        }
        String prefix = propertyPrefix(shiftIndex);
        BeanUtil.setProperty(result, prefix + "PlanQty", qty);
        BeanUtil.setProperty(result, prefix + "StartTime", startTime);
        BeanUtil.setProperty(result, prefix + "EndTime", endTime);
    }

    /**
     * 清空全部班次计划字段。
     *
     * @param result 排程结果
     */
    public static void clearShiftPlanFields(LhScheduleResult result) {
        if (Objects.isNull(result)) {
            return;
        }
        for (int shiftIndex = 1; shiftIndex <= LhScheduleConstant.MAX_SHIFT_SLOT_COUNT; shiftIndex++) {
            setShiftPlanQty(result, shiftIndex, null, null, null);
            setShiftAnalysis(result, shiftIndex, null);
        }
    }

    /**
     * 复制指定班次计划字段。
     *
     * @param source 源排程结果
     * @param sourceShiftIndex 源班次索引
     * @param target 目标排程结果
     * @param targetShiftIndex 目标班次索引
     */
    public static void copyShiftPlanFields(LhScheduleResult source, int sourceShiftIndex,
            LhScheduleResult target, int targetShiftIndex) {
        if (Objects.isNull(source) || Objects.isNull(target)
                || !isValidIndex(sourceShiftIndex) || !isValidIndex(targetShiftIndex)) {
            return;
        }
        setShiftPlanQty(target, targetShiftIndex,
                getShiftPlanQty(source, sourceShiftIndex),
                getShiftStartTime(source, sourceShiftIndex),
                getShiftEndTime(source, sourceShiftIndex));
        setShiftAnalysis(target, targetShiftIndex, getShiftAnalysis(source, sourceShiftIndex));
    }

    /**
     * 获取班次计划量
     *
     * @param result     排程结果
     * @param shiftIndex 班次索引
     * @return 计划量，越界返回 null
     */
    public static Integer getShiftPlanQty(LhScheduleResult result, int shiftIndex) {
        if (!isValidIndex(shiftIndex)) {
            return null;
        }
        return toInteger(BeanUtil.getProperty(result, propertyPrefix(shiftIndex) + "PlanQty"));
    }

    /**
     * 获取班次计划开始时间
     *
     * @param result     排程结果
     * @param shiftIndex 班次索引
     * @return 开始时间，越界返回 null
     */
    public static Date getShiftStartTime(LhScheduleResult result, int shiftIndex) {
        if (!isValidIndex(shiftIndex)) {
            return null;
        }
        Object v = BeanUtil.getProperty(result, propertyPrefix(shiftIndex) + "StartTime");
        return v instanceof Date ? (Date) v : null;
    }

    /**
     * 获取班次计划结束时间
     *
     * @param result     排程结果
     * @param shiftIndex 班次索引
     * @return 结束时间，越界返回 null
     */
    public static Date getShiftEndTime(LhScheduleResult result, int shiftIndex) {
        if (!isValidIndex(shiftIndex)) {
            return null;
        }
        Object v = BeanUtil.getProperty(result, propertyPrefix(shiftIndex) + "EndTime");
        return v instanceof Date ? (Date) v : null;
    }

    /**
     * 获取班次完成量
     *
     * @param result     排程结果
     * @param shiftIndex 班次索引
     * @return 完成量，越界返回 null
     */
    public static Integer getShiftFinishQty(LhScheduleResult result, int shiftIndex) {
        if (!isValidIndex(shiftIndex)) {
            return null;
        }
        return toInteger(BeanUtil.getProperty(result, propertyPrefix(shiftIndex) + "FinishQty"));
    }

    /**
     * 设置班次原因分析。
     *
     * @param result 排程结果
     * @param shiftIndex 班次索引
     * @param analysis 原因分析
     */
    public static void setShiftAnalysis(LhScheduleResult result, int shiftIndex, String analysis) {
        if (!isValidIndex(shiftIndex)) {
            log.warn("未知班次索引: {}", shiftIndex);
            return;
        }
        BeanUtil.setProperty(result, propertyPrefix(shiftIndex) + "Analysis", analysis);
    }

    /**
     * 获取班次原因分析。
     *
     * @param result 排程结果
     * @param shiftIndex 班次索引
     * @return 班次原因分析
     */
    public static String getShiftAnalysis(LhScheduleResult result, int shiftIndex) {
        if (!isValidIndex(shiftIndex)) {
            return null;
        }
        Object value = BeanUtil.getProperty(result, propertyPrefix(shiftIndex) + "Analysis");
        return value == null ? null : String.valueOf(value);
    }

    private static Integer toInteger(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Integer) {
            return (Integer) v;
        }
        if (v instanceof Number) {
            return ((Number) v).intValue();
        }
        return null;
    }

    /**
     * 汇总 1～maxIndex 班次的计划量之和
     *
     * @param result   排程结果
     * @param maxIndex 最大班次索引（含）
     * @return 合计
     */
    public static int sumPlanQty(LhScheduleResult result, int maxIndex) {
        int total = 0;
        int cap = Math.min(maxIndex, LhScheduleConstant.MAX_SHIFT_SLOT_COUNT);
        for (int i = 1; i <= cap; i++) {
            Integer q = getShiftPlanQty(result, i);
            total += (q != null ? q : 0);
        }
        return total;
    }

    /**
     * 汇总当前结果行 8 个班次的计划量。
     *
     * @param result 排程结果
     * @return 当前结果行实际排产量
     */
    public static int resolveScheduledQty(LhScheduleResult result) {
        return sumPlanQty(result, LhScheduleConstant.MAX_SHIFT_SLOT_COUNT);
    }

    /**
     * 同步结果行的日计划量字段。
     * <p>当前业务口径下，{@code DAILY_PLAN_QTY} 表示 8 班班次计划量之和。</p>
     *
     * @param result 排程结果
     */
    public static void syncDailyPlanQty(LhScheduleResult result) {
        if (result == null) {
            return;
        }
        result.setDailyPlanQty(resolveScheduledQty(result));
    }

    /**
     * 按比例缩放一组结果的班次计划量，并保证组内总量与结果内总量都不丢尾差。
     *
     * @param results 结果列表
     * @param shifts 班次列表
     * @param targetTotal 裁减后的目标总量
     */
    public static void scaleGroupedShiftPlanQty(List<LhScheduleResult> results,
                                                List<LhShiftConfigVO> shifts,
                                                int targetTotal) {
        if (results == null || results.isEmpty() || shifts == null || shifts.isEmpty()) {
            return;
        }
        List<LhScheduleResult> effectiveResults = new ArrayList<>(results.size());
        List<Integer> resultWeights = new ArrayList<>(results.size());
        int originalTotal = 0;
        for (LhScheduleResult result : results) {
            int scheduledQty = resolveScheduledQty(result);
            if (scheduledQty <= 0) {
                continue;
            }
            effectiveResults.add(result);
            resultWeights.add(scheduledQty);
            originalTotal += scheduledQty;
        }
        if (originalTotal <= 0 || targetTotal >= originalTotal) {
            return;
        }
        List<Integer> resultTargets = distributeProportionally(resultWeights, Math.max(targetTotal, 0));
        for (int i = 0; i < effectiveResults.size(); i++) {
            scaleSingleResultShiftPlanQty(effectiveResults.get(i), shifts, resultTargets.get(i));
        }
    }

    /**
     * 按班次原始计划量占比缩放单条结果，并把尾差补回到余数最大的班次。
     *
     * @param result 单条结果
     * @param shifts 班次列表
     * @param targetTotal 该结果应保留的总量
     */
    public static void scaleSingleResultShiftPlanQty(LhScheduleResult result,
                                                     List<LhShiftConfigVO> shifts,
                                                     int targetTotal) {
        if (result == null || shifts == null || shifts.isEmpty()) {
            return;
        }
        List<Integer> shiftWeights = new ArrayList<>(shifts.size());
        for (LhShiftConfigVO shift : shifts) {
            Integer qty = getShiftPlanQty(result, shift.getShiftIndex());
            shiftWeights.add(qty != null && qty > 0 ? qty : 0);
        }
        List<Integer> shiftTargets = distributeProportionally(shiftWeights, Math.max(targetTotal, 0));
        for (int i = 0; i < shifts.size(); i++) {
            LhShiftConfigVO shift = shifts.get(i);
            int shiftIndex = shift.getShiftIndex();
            setShiftPlanQty(result, shiftIndex, shiftTargets.get(i),
                    getShiftStartTime(result, shiftIndex),
                    getShiftEndTime(result, shiftIndex));
        }
    }

    /**
     * 按权重比例分配目标总量，先取整再按最大余数补尾差，保证合计严格等于目标值。
     *
     * @param weights 权重列表
     * @param targetTotal 目标总量
     * @return 分配结果
     */
    private static List<Integer> distributeProportionally(List<Integer> weights, int targetTotal) {
        List<Integer> allocations = new ArrayList<>(weights == null ? 0 : weights.size());
        if (weights == null || weights.isEmpty()) {
            return allocations;
        }
        int weightSum = 0;
        for (Integer weight : weights) {
            int normalizedWeight = weight != null && weight > 0 ? weight : 0;
            allocations.add(0);
            weightSum += normalizedWeight;
        }
        if (weightSum <= 0 || targetTotal <= 0) {
            return allocations;
        }
        if (targetTotal >= weightSum) {
            for (int i = 0; i < weights.size(); i++) {
                Integer weight = weights.get(i);
                allocations.set(i, weight != null && weight > 0 ? weight : 0);
            }
            return allocations;
        }
        List<Integer> positiveIndexes = new ArrayList<>(weights.size());
        double[] remainders = new double[weights.size()];
        int allocatedTotal = 0;
        for (int i = 0; i < weights.size(); i++) {
            int weight = weights.get(i) != null && weights.get(i) > 0 ? weights.get(i) : 0;
            if (weight <= 0) {
                continue;
            }
            double scaledQty = (double) targetTotal * weight / weightSum;
            int baseQty = (int) Math.floor(scaledQty);
            allocations.set(i, baseQty);
            remainders[i] = scaledQty - baseQty;
            allocatedTotal += baseQty;
            positiveIndexes.add(i);
        }
        int remainder = targetTotal - allocatedTotal;
        positiveIndexes.sort(Comparator.comparingDouble((Integer index) -> remainders[index])
                .reversed()
                .thenComparingInt(Integer::intValue));
        for (int i = 0; i < remainder && i < positiveIndexes.size(); i++) {
            int index = positiveIndexes.get(i);
            allocations.set(index, allocations.get(index) + 1);
        }
        return allocations;
    }

    private static boolean isValidIndex(int shiftIndex) {
        return shiftIndex >= 1 && shiftIndex <= LhScheduleConstant.MAX_SHIFT_SLOT_COUNT;
    }

    private static String propertyPrefix(int shiftIndex) {
        return "class" + shiftIndex;
    }
}
