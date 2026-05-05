/**
 * Copyright (c) 2008, 智立通（厦门）科技有限公司 All rights reserved。
 */
package com.zlt.aps.lh.engine.strategy.impl;

import com.zlt.aps.lh.api.constant.LhScheduleConstant;
import com.zlt.aps.lh.api.constant.LhScheduleParamConstant;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.api.domain.dto.MachineScheduleDTO;
import com.zlt.aps.lh.engine.strategy.ICapacityCalculateStrategy;
import com.zlt.aps.lh.util.LhScheduleTimeUtil;
import com.zlt.aps.mdm.api.domain.entity.MdmDevicePlanShut;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Date;

/**
 * 默认产能计算策略实现
 * <p>基于硫化时间和模数计算班产、日产、首班产量和开产时间，
 * 并综合考虑保养、维修等计划停机情况</p>
 *
 * @author APS
 */
@Slf4j
@Component
public class DefaultCapacityCalculateStrategy implements ICapacityCalculateStrategy {

    @Override
    public int calculateShiftCapacity(LhScheduleContext context, int lhTimeSeconds, int mouldQty) {
        if (lhTimeSeconds <= 0 || mouldQty <= 0) {
            return 0;
        }
        // 计算公式：(班次时间秒数 / 硫化时间秒数) 向下取整 * 模数
        int shiftSeconds = LhScheduleTimeUtil.getShiftDurationHours(context) * 3600;
        return (shiftSeconds / lhTimeSeconds) * mouldQty;
    }

    @Override
    public Date calculateStartTime(LhScheduleContext context, String machineCode, Date endingTime) {
        if (endingTime == null) {
            return new Date();
        }

        // 语义调整为“机台准备就绪时间”，仅表达机台从何时开始可以继续安排换模/首检/生产
        Date baseReadyTime = endingTime;

        // 判断机台是否有保养/维修计划，若有则取最晚时间。
        // 清洗与换模/换活字块的重叠判定已下沉到后续切换链路，不在这里提前固化 readyTime。
        Date maintenanceStartTime = calculateMaintenanceStartTime(context, machineCode);
        Date repairStartTime = calculateRepairStartTime(context, machineCode);

        // 取三者最大值：基础可用时间、保养后可用时间、维修后可用时间
        Date maxStartTime = baseReadyTime;
        if (maintenanceStartTime != null && maintenanceStartTime.after(maxStartTime)) {
            maxStartTime = maintenanceStartTime;
        }
        if (repairStartTime != null && repairStartTime.after(maxStartTime)) {
            maxStartTime = repairStartTime;
        }

        log.debug("计算机台准备就绪时间, 机台: {}, 收尾时间: {}, 就绪时间: {}",
                machineCode, LhScheduleTimeUtil.formatDateTime(endingTime), LhScheduleTimeUtil.formatDateTime(maxStartTime));
        return maxStartTime;
    }

    @Override
    public int calculateFirstShiftQty(Date startTime, Date shiftEndTime, int lhTimeSeconds, int mouldQty) {
        if (startTime == null || shiftEndTime == null || lhTimeSeconds <= 0 || mouldQty <= 0) {
            return 0;
        }
        // 首班可用时间（秒）= 首班次结束时间 - 上机时间
        long availableSeconds = (shiftEndTime.getTime() - startTime.getTime()) / 1000L;
        if (availableSeconds <= 0) {
            return 0;
        }
        // 首班计划量 = (可用时间 / 硫化时间) 向下取整 * 模数
        return (int) (availableSeconds / lhTimeSeconds) * mouldQty;
    }

    @Override
    public int calculateDailyCapacity(int lhTimeSeconds, int mouldQty) {
        if (lhTimeSeconds <= 0 || mouldQty <= 0) {
            return 0;
        }
        // 日硫化量 = (24 * 3600 / 硫化时间) * 模数
        return (24 * 3600 / lhTimeSeconds) * mouldQty;
    }

    /**
     * 计算设备保养后的开产时间
     * <p>
     * 保养规则：<br/>
     * 开产时间 = MAX(保养开始时间(固定8:00), 前SKU收尾时间) + 保养时间(7h) + 换模含预热(4h) + 首检(1h) = 17:30<br/>
     * 注：若前SKU收尾时间在保养时间之前，也从保养开始时间起算
     * </p>
     *
     * @param context     排程上下文
     * @param machineCode 机台编号
     * @return 保养后的开产时间，null表示无保养计划
     */
    private Date calculateMaintenanceStartTime(LhScheduleContext context, String machineCode) {
        MachineScheduleDTO machineDTO = context.getMachineScheduleMap().get(machineCode);
        if (machineDTO == null || !machineDTO.isHasMaintenancePlan()) {
            return null;
        }

        // 保养固定从8:00开始
        Date maintenancePlanDate = machineDTO.getMaintenancePlanTime();
        if (maintenancePlanDate == null) {
            return null;
        }

        int maintenanceStartHour = context.getParamIntValue(LhScheduleParamConstant.MAINTENANCE_START_HOUR,
                LhScheduleConstant.MAINTENANCE_START_HOUR);
        Date maintenanceStart = LhScheduleTimeUtil.buildTime(maintenancePlanDate, maintenanceStartHour, 0, 0);

        // 保养时间7小时
        int maintenanceDurationHours = context.getParamIntValue(LhScheduleParamConstant.MAINTENANCE_DURATION_HOURS,
                LhScheduleConstant.MAINTENANCE_DURATION_HOURS);
        Date maintenanceEnd = LhScheduleTimeUtil.addHours(maintenanceStart, maintenanceDurationHours);

        // 语义为机台保养完成后的就绪时间
        return maintenanceEnd;
    }

    /**
     * 计算设备维修后的开产时间
     * <p>
     * 维修规则：<br/>
     * 若有换模：开产时间 = 维修开始时间(8:00) + 维修时间 + 换模含预热(4h) + 首检(1h)<br/>
     * 若无换模：开产时间 = 维修开始时间(8:00) + 维修时间 + 胶囊预热时间(2.5h)
     * </p>
     *
     * @param context     排程上下文
     * @param machineCode 机台编号
     * @return 维修后的开产时间，null表示无维修计划
     */
    private Date calculateRepairStartTime(LhScheduleContext context, String machineCode) {
        MachineScheduleDTO machineDTO = context.getMachineScheduleMap().get(machineCode);
        if (machineDTO == null || !machineDTO.isHasRepairPlan()) {
            return null;
        }

        Date repairPlanTime = machineDTO.getRepairPlanTime();
        if (repairPlanTime == null) {
            return null;
        }

        // 维修固定从8:00开始
        int maintenanceStartHour = context.getParamIntValue(LhScheduleParamConstant.MAINTENANCE_START_HOUR,
                LhScheduleConstant.MAINTENANCE_START_HOUR);
        Date repairStart = LhScheduleTimeUtil.buildTime(repairPlanTime, maintenanceStartHour, 0, 0);

        // 从设备停机计划中获取维修时长（暂用8小时作为默认）
        int repairDurationHours = 8;
        for (MdmDevicePlanShut planShut : context.getDevicePlanShutList()) {
            if (machineCode.equals(planShut.getMachineCode()) && planShut.getBeginDate() != null) {
                if (planShut.getEndDate() != null) {
                    long durationHours = LhScheduleTimeUtil.diffHours(planShut.getBeginDate(), planShut.getEndDate());
                    repairDurationHours = (int) Math.max(1, durationHours);
                }
                break;
            }
        }

        Date repairEnd = LhScheduleTimeUtil.addHours(repairStart, repairDurationHours);

        // 语义为机台维修完成后的就绪时间
        return repairEnd;
    }
}
