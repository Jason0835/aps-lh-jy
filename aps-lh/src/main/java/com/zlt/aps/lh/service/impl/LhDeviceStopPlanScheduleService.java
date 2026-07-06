package com.zlt.aps.lh.service.impl;

import com.zlt.aps.lh.api.constant.LhScheduleConstant;
import com.zlt.aps.lh.api.constant.LhScheduleParamConstant;
import com.zlt.aps.lh.api.enums.CleaningTypeEnum;
import com.zlt.aps.lh.api.enums.MachineStopTypeEnum;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.util.LhScheduleTimeUtil;
import com.zlt.aps.mdm.api.domain.entity.MdmDevicePlanShut;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Objects;

/**
 * 设备停机计划排程公共服务。
 *
 * <p>业务定位：统一识别设备停机计划中的清洗、维修、精度等停机类型，并提供清洗场景需要的
 * 班次归属、喷砂顺延、每日上限日期归属和普通停机列表剥离能力。清洗计划从设备停机表读取后，
 * 后续产能扣减必须只使用已纳入排程的清洗窗口，不能让未纳入的清洗停机记录继续作为普通停机扣产能。</p>
 *
 * @author APS
 */
@Slf4j
@Component
public class LhDeviceStopPlanScheduleService {

    /** 启用配置值 */
    private static final int ENABLED = 1;
    /** 多值参数分隔符 */
    private static final String VALUE_SEPARATOR = ",";

    /**
     * 从排程窗口内的设备停机计划中过滤干冰/喷砂清洗记录，并按计划开始时间升序返回。
     *
     * @param context 排程上下文
     * @return 排程窗口内清洗类设备停机计划
     */
    public List<MdmDevicePlanShut> queryCleaningStopPlans(LhScheduleContext context) {
        if (Objects.isNull(context) || CollectionUtils.isEmpty(context.getDevicePlanShutList())) {
            return Collections.emptyList();
        }
        List<MdmDevicePlanShut> cleaningStopPlans = new ArrayList<>(context.getDevicePlanShutList().size());
        for (MdmDevicePlanShut planShut : context.getDevicePlanShutList()) {
            if (!isValidStopPlan(planShut) || !isCleaningStopType(planShut.getMachineStopType())) {
                continue;
            }
            if (!isPlanBeginInScheduleWindow(context, planShut.getBeginDate())) {
                continue;
            }
            cleaningStopPlans.add(planShut);
        }
        cleaningStopPlans.sort(Comparator
                .comparing(MdmDevicePlanShut::getBeginDate, Comparator.nullsLast(Date::compareTo))
                .thenComparing(MdmDevicePlanShut::getMachineCode, Comparator.nullsLast(String::compareTo)));
        return cleaningStopPlans;
    }

    /**
     * 过滤出非清洗类设备停机计划。
     *
     * <p>清洗类停机计划会先被转换成运行态清洗窗口；转换完成后必须从普通停机列表中剥离，
     * 否则超过每日上限或 3 天内收尾被跳过的清洗仍会按普通停机扣减产能。</p>
     *
     * @param devicePlanShutList 原始设备停机计划
     * @return 非清洗类设备停机计划
     */
    public List<MdmDevicePlanShut> filterNonCleaningStopPlans(List<MdmDevicePlanShut> devicePlanShutList) {
        if (CollectionUtils.isEmpty(devicePlanShutList)) {
            return Collections.emptyList();
        }
        List<MdmDevicePlanShut> normalStopPlans = new ArrayList<>(devicePlanShutList.size());
        for (MdmDevicePlanShut planShut : devicePlanShutList) {
            if (Objects.isNull(planShut) || isCleaningStopType(planShut.getMachineStopType())) {
                continue;
            }
            normalStopPlans.add(planShut);
        }
        return normalStopPlans;
    }

    /**
     * 判断停机类型是否为干冰/喷砂清洗。
     *
     * @param machineStopType 停机类型编码
     * @return true-清洗类停机；false-普通设备停机
     */
    public boolean isCleaningStopType(String machineStopType) {
        return isDryIceCleaning(machineStopType) || isSandBlastCleaning(machineStopType);
    }

    /**
     * 判断停机类型是否为干冰清洗。
     *
     * @param machineStopType 停机类型编码
     * @return true-干冰清洗
     */
    public boolean isDryIceCleaning(String machineStopType) {
        return StringUtils.equals(MachineStopTypeEnum.DRY_ICE_CLEANING.getCode(), machineStopType);
    }

    /**
     * 判断停机类型是否为喷砂清洗。
     *
     * @param machineStopType 停机类型编码
     * @return true-喷砂清洗
     */
    public boolean isSandBlastCleaning(String machineStopType) {
        return StringUtils.equals(MachineStopTypeEnum.SANDBLASTING_CLEANING.getCode(), machineStopType);
    }

    /**
     * 将设备停机类型转换为清洗类型。
     *
     * @param planShut 设备停机计划
     * @return 清洗类型编码；非清洗类型返回 null
     */
    public String resolveCleaningType(MdmDevicePlanShut planShut) {
        if (Objects.isNull(planShut)) {
            return null;
        }
        if (isDryIceCleaning(planShut.getMachineStopType())) {
            return CleaningTypeEnum.DRY_ICE.getCode();
        }
        if (isSandBlastCleaning(planShut.getMachineStopType())) {
            return CleaningTypeEnum.SAND_BLAST.getCode();
        }
        return null;
    }

    /**
     * 解析设备停机计划自身维护的持续时长。
     *
     * @param planShut 设备停机计划
     * @return 持续时长毫秒；计划非法时返回 0
     */
    public long resolvePlanDurationMillis(MdmDevicePlanShut planShut) {
        if (!isValidStopPlan(planShut)) {
            return 0L;
        }
        return planShut.getEndDate().getTime() - planShut.getBeginDate().getTime();
    }

    /**
     * 解析喷砂清洗实际执行开始时间。
     *
     * <p>喷砂固定中班执行：计划不在中班时向后归一到后续第一个中班；若该中班命中喷砂机维保日或周日，
     * 继续向后顺延到下一个满足条件的中班。该方法只处理喷砂自身日期/班次约束，每日 1 台上限由清洗纳入服务判断。</p>
     *
     * @param context 排程上下文
     * @param planBeginTime 计划开始时间
     * @return 实际喷砂中班开始时间
     */
    public Date resolveSandBlastExecutableStartTime(LhScheduleContext context, Date planBeginTime) {
        if (Objects.isNull(planBeginTime)) {
            return null;
        }
        Date candidateStartTime = normalizeToAfternoonShift(context, planBeginTime);
        for (int attempt = 0; attempt < LhScheduleConstant.MAX_SHIFT_SLOT_COUNT * 30; attempt++) {
            if (!isSandBlastForbiddenDate(context, candidateStartTime)) {
                return candidateStartTime;
            }
            log.info("喷砂清洗日期不满足约束，顺延到下一天中班, 原日期: {}, 原因: {}",
                    LhScheduleTimeUtil.formatDate(candidateStartTime),
                    resolveSandBlastForbiddenReason(context, candidateStartTime));
            candidateStartTime = LhScheduleTimeUtil.getAfternoonShiftStart(
                    context, LhScheduleTimeUtil.addDays(candidateStartTime, 1));
        }
        return candidateStartTime;
    }

    /**
     * 解析干冰清洗实际执行开始时间。
     *
     * <p>干冰只能落在早班或中班。计划在早班/中班内时保持原计划时间；计划在早班前归一到当天早班；
     * 计划在夜班或中班后归一到次日早班。</p>
     *
     * @param context 排程上下文
     * @param planBeginTime 计划开始时间
     * @return 实际干冰清洗开始时间
     */
    public Date resolveDryIceExecutableStartTime(LhScheduleContext context, Date planBeginTime) {
        if (Objects.isNull(planBeginTime)) {
            return null;
        }
        if (LhScheduleTimeUtil.isMorningShift(context, planBeginTime)
                || LhScheduleTimeUtil.isAfternoonShift(context, planBeginTime)) {
            return planBeginTime;
        }
        Date morningStart = LhScheduleTimeUtil.getMorningShiftStart(context, planBeginTime);
        if (planBeginTime.before(morningStart)) {
            return morningStart;
        }
        return LhScheduleTimeUtil.getMorningShiftStart(context, LhScheduleTimeUtil.addDays(planBeginTime, 1));
    }

    /**
     * 判断清洗实际开始时间是否仍在排程窗口内。
     *
     * @param context 排程上下文
     * @param cleanStartTime 清洗实际开始时间
     * @return true-在窗口内；false-窗口外
     */
    public boolean isInScheduleWindow(LhScheduleContext context, Date cleanStartTime) {
        if (Objects.isNull(context) || Objects.isNull(context.getScheduleDate()) || Objects.isNull(cleanStartTime)) {
            return true;
        }
        Date windowStart = LhScheduleTimeUtil.clearTime(context.getScheduleDate());
        Date windowEnd = LhScheduleTimeUtil.addDays(windowStart, LhScheduleTimeUtil.getScheduleDays(context));
        return !cleanStartTime.before(windowStart) && cleanStartTime.before(windowEnd);
    }

    /**
     * 判断指定时间是否归属于早班。
     *
     * @param context 排程上下文
     * @param startTime 开始时间
     * @return true-早班
     */
    public boolean isMorningShift(LhScheduleContext context, Date startTime) {
        return Objects.nonNull(startTime) && LhScheduleTimeUtil.isMorningShift(context, startTime);
    }

    /**
     * 判断指定时间是否归属于中班。
     *
     * @param context 排程上下文
     * @param startTime 开始时间
     * @return true-中班
     */
    public boolean isAfternoonShift(LhScheduleContext context, Date startTime) {
        return Objects.nonNull(startTime) && LhScheduleTimeUtil.isAfternoonShift(context, startTime);
    }

    private boolean isPlanBeginInScheduleWindow(LhScheduleContext context, Date planBeginTime) {
        return isInScheduleWindow(context, planBeginTime);
    }

    private boolean isValidStopPlan(MdmDevicePlanShut planShut) {
        return Objects.nonNull(planShut)
                && StringUtils.isNotEmpty(planShut.getMachineCode())
                && Objects.nonNull(planShut.getBeginDate())
                && Objects.nonNull(planShut.getEndDate())
                && planShut.getBeginDate().before(planShut.getEndDate());
    }

    private Date normalizeToAfternoonShift(LhScheduleContext context, Date planBeginTime) {
        Date afternoonStart = LhScheduleTimeUtil.getAfternoonShiftStart(context, planBeginTime);
        Date nightStart = LhScheduleTimeUtil.getNightShiftStart(context, planBeginTime);
        if (LhScheduleTimeUtil.isAfternoonShift(context, planBeginTime)) {
            return planBeginTime;
        }
        if (planBeginTime.before(afternoonStart)) {
            return afternoonStart;
        }
        if (!planBeginTime.before(nightStart)) {
            return LhScheduleTimeUtil.getAfternoonShiftStart(context, LhScheduleTimeUtil.addDays(planBeginTime, 1));
        }
        return afternoonStart;
    }

    private boolean isSandBlastForbiddenDate(LhScheduleContext context, Date cleanTime) {
        return isSandBlastMaintenanceDate(context, cleanTime) || isSunday(cleanTime);
    }

    private String resolveSandBlastForbiddenReason(LhScheduleContext context, Date cleanTime) {
        if (isSandBlastMaintenanceDate(context, cleanTime)) {
            return "喷砂机维保日";
        }
        if (isSunday(cleanTime)) {
            return "周日";
        }
        return "未知原因";
    }

    private boolean isSandBlastMaintenanceDate(LhScheduleContext context, Date cleanTime) {
        if (Objects.isNull(context) || Objects.isNull(cleanTime)) {
            return false;
        }
        String maintenanceDates = context.getParamValue(LhScheduleParamConstant.SAND_BLAST_MAINTENANCE_DATES,
                LhScheduleConstant.SAND_BLAST_MAINTENANCE_DATES);
        if (StringUtils.isEmpty(maintenanceDates)) {
            return false;
        }
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(cleanTime);
        String dayOfMonth = String.valueOf(calendar.get(Calendar.DAY_OF_MONTH));
        for (String maintenanceDate : maintenanceDates.split(VALUE_SEPARATOR)) {
            if (StringUtils.equals(dayOfMonth, StringUtils.trim(maintenanceDate))) {
                return true;
            }
        }
        return false;
    }

    private boolean isSunday(Date cleanTime) {
        if (Objects.isNull(cleanTime)) {
            return false;
        }
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(cleanTime);
        return calendar.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY;
    }
}
