package com.zlt.aps.lh.engine.strategy.support;

import com.zlt.aps.lh.api.domain.entity.LhScheduleResult;
import com.zlt.aps.lh.api.domain.vo.LhShiftConfigVO;
import com.zlt.aps.lh.util.ShiftFieldUtil;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 跨日追加排程结果前的班次基线。
 *
 * <p>同一结果会在 T、T+1、T+2 逐日追加班次量，账本只能消费本次新增量。
 * 本对象保存追加前班次数量、时间和业务分析备注，既用于计算增量，也用于合并异常时恢复原结果。
 *
 * @author APS
 */
public class ScheduleResultBaseline {

    /** 基线班次数量 */
    private final Map<Integer, Integer> shiftQtyMap = new LinkedHashMap<Integer, Integer>(8);
    /** 基线班次开始时间 */
    private final Map<Integer, Date> shiftStartTimeMap = new LinkedHashMap<Integer, Date>(8);
    /** 基线班次结束时间 */
    private final Map<Integer, Date> shiftEndTimeMap = new LinkedHashMap<Integer, Date>(8);
    /** 基线班次业务分析备注，例如首检、换胶囊等不可丢失的运行态事实 */
    private final Map<Integer, String> shiftAnalysisMap = new LinkedHashMap<Integer, String>(8);
    /** 基线日计划总量 */
    private final Integer dailyPlanQty;
    /** 基线规格结束时间 */
    private final Date specEndTime;

    private ScheduleResultBaseline(LhScheduleResult result, List<LhShiftConfigVO> shifts) {
        for (LhShiftConfigVO shift : shifts) {
            Integer shiftIndex = shift.getShiftIndex();
            shiftQtyMap.put(shiftIndex, ShiftFieldUtil.getShiftPlanQty(result, shiftIndex));
            shiftStartTimeMap.put(shiftIndex, ShiftFieldUtil.getShiftStartTime(result, shiftIndex));
            shiftEndTimeMap.put(shiftIndex, ShiftFieldUtil.getShiftEndTime(result, shiftIndex));
            shiftAnalysisMap.put(shiftIndex, ShiftFieldUtil.getShiftAnalysis(result, shiftIndex));
        }
        this.dailyPlanQty = result.getDailyPlanQty();
        this.specEndTime = result.getSpecEndTime();
    }

    /**
     * 捕获结果当前基线。
     *
     * @param result 待跨日追加的结果
     * @param shifts 完整排程窗口班次
     * @return 结果基线
     */
    public static ScheduleResultBaseline capture(LhScheduleResult result,
                                                 List<LhShiftConfigVO> shifts) {
        Objects.requireNonNull(result, "排程结果不能为空");
        Objects.requireNonNull(shifts, "排程班次不能为空");
        return new ScheduleResultBaseline(result, shifts);
    }

    /**
     * 计算结果相对基线的正向新增量。
     *
     * @param result 已追加当前日班次量的结果
     * @param shifts 当前业务日班次切片
     * @return 当前日新增量
     */
    public int calculatePositiveDelta(LhScheduleResult result, List<LhShiftConfigVO> shifts) {
        int deltaQty = 0;
        for (LhShiftConfigVO shift : shifts) {
            Integer shiftIndex = shift.getShiftIndex();
            int beforeQty = Math.max(0, resolveQty(shiftQtyMap.get(shiftIndex)));
            int currentQty = Math.max(0, resolveQty(ShiftFieldUtil.getShiftPlanQty(result, shiftIndex)));
            deltaQty += Math.max(0, currentQty - beforeQty);
        }
        return deltaQty;
    }

    /**
     * 恢复捕获基线，供当前日追加失败时完整回滚结果字段。
     *
     * @param result 待恢复结果
     */
    public void restore(LhScheduleResult result) {
        for (Map.Entry<Integer, Integer> entry : shiftQtyMap.entrySet()) {
            Integer shiftIndex = entry.getKey();
            ShiftFieldUtil.setShiftPlanQty(result, shiftIndex, entry.getValue(),
                    shiftStartTimeMap.get(shiftIndex), shiftEndTimeMap.get(shiftIndex));
            // 数量校验失败时必须同时撤销本次尝试新增的首检、换胶囊等班次事实。
            ShiftFieldUtil.setShiftAnalysis(result, shiftIndex, shiftAnalysisMap.get(shiftIndex));
        }
        result.setDailyPlanQty(dailyPlanQty);
        result.setSpecEndTime(specEndTime);
    }

    private int resolveQty(Integer qty) {
        return Objects.isNull(qty) ? 0 : qty;
    }
}
