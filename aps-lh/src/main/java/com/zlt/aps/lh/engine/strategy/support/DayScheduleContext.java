package com.zlt.aps.lh.engine.strategy.support;

import com.zlt.aps.lh.api.domain.vo.LhShiftConfigVO;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Objects;

/**
 * 新增排产当前业务日的编排上下文。
 *
 * <p>该对象只保存当前日班次切片、日窗口边界和执行阶段，不复制机台、模具、胎胚、
 * 胶囊、日计划等全局资源。跨日循环始终复用同一个排程上下文，避免每日重新初始化资源。</p>
 *
 * @author APS
 */
public class DayScheduleContext {

    /** 当前正在编排的业务日期 */
    private final LocalDate scheduleDate;
    /** 当前业务日允许写入的班次切片 */
    private final List<LhShiftConfigVO> dayShifts;
    /** 当前业务日第一个班次的绝对开始时间 */
    private final Date dayStartTime;
    /** 当前业务日最后一个班次的绝对结束时间，日窗口采用半开区间 */
    private final Date dayEndTime;
    /** 当前业务日是否为排程窗口首日 */
    private final boolean firstScheduleDay;
    /** 当前业务日是否为排程窗口最后一日 */
    private final boolean lastScheduleDay;
    /** 当前正在执行的日内阶段 */
    private DailySchedulePhase currentPhase;

    /**
     * 创建业务日编排上下文。
     *
     * @param scheduleDate 当前业务日期
     * @param dayShifts 当前业务日班次切片
     * @param firstScheduleDay 是否为窗口首日
     * @param lastScheduleDay 是否为窗口最后一日
     */
    public DayScheduleContext(LocalDate scheduleDate,
                              List<LhShiftConfigVO> dayShifts,
                              boolean firstScheduleDay,
                              boolean lastScheduleDay) {
        this.scheduleDate = Objects.requireNonNull(scheduleDate, "当前业务日期不能为空");
        if (dayShifts == null || dayShifts.isEmpty()) {
            throw new IllegalArgumentException("当前业务日班次不能为空");
        }
        this.dayShifts = Collections.unmodifiableList(new ArrayList<LhShiftConfigVO>(dayShifts));
        this.dayStartTime = this.dayShifts.get(0).getShiftStartDateTime();
        this.dayEndTime = this.dayShifts.get(this.dayShifts.size() - 1).getShiftEndDateTime();
        this.firstScheduleDay = firstScheduleDay;
        this.lastScheduleDay = lastScheduleDay;
    }

    /**
     * 判断给定时刻是否落在当前业务日可写结果窗口内。
     *
     * @param time 待判断时刻
     * @return true-落在半开区间[日首班开始, 日末班结束)内
     */
    public boolean contains(Date time) {
        return Objects.nonNull(time)
                && Objects.nonNull(dayStartTime)
                && Objects.nonNull(dayEndTime)
                && !time.before(dayStartTime)
                && time.before(dayEndTime);
    }

    /**
     * 判断任务完成时刻是否已经越过当前业务日。
     *
     * @param time 换模、首检或开产完成时刻
     * @return true-已到达或超过当前业务日日终
     */
    public boolean reachesOrPassesDayEnd(Date time) {
        return Objects.nonNull(time)
                && Objects.nonNull(dayEndTime)
                && !time.before(dayEndTime);
    }

    public LocalDate getScheduleDate() {
        return scheduleDate;
    }

    public List<LhShiftConfigVO> getDayShifts() {
        return dayShifts;
    }

    public Date getDayStartTime() {
        return dayStartTime;
    }

    public Date getDayEndTime() {
        return dayEndTime;
    }

    public boolean isFirstScheduleDay() {
        return firstScheduleDay;
    }

    public boolean isLastScheduleDay() {
        return lastScheduleDay;
    }

    public DailySchedulePhase getCurrentPhase() {
        return currentPhase;
    }

    public void setCurrentPhase(DailySchedulePhase currentPhase) {
        this.currentPhase = currentPhase;
    }
}
