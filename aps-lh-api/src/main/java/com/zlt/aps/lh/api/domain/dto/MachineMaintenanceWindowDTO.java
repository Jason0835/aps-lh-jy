package com.zlt.aps.lh.api.domain.dto;

import lombok.Data;

import java.util.Date;

/**
 * 机台精度保养时间窗口。
 *
 * @author APS
 */
@Data
public class MachineMaintenanceWindowDTO {

    /** 精度保养计划主键，用于排程完成后精确回填计划安排日期 */
    private Long precisionPlanId;
    /** 机台编号 */
    private String machineCode;
    /** 精度/保养类型 */
    private String maintenanceType;
    /** 来源计划日期，由 MES 或设备计划维护 */
    private Date sourcePlanDate;
    /** 计划到期日期；为空时以来源计划日期作为到期日期 */
    private Date dueDate;
    /** 相对本次排程日实时计算的到期天数 */
    private Integer daysToDue;
    /** APS 最终安排的保养日期 */
    private Date planDate;
    /** 保养开始时间 */
    private Date maintenanceStartTime;
    /** 保养结束时间 */
    private Date maintenanceEndTime;
    /** 保养结束并完成胶囊预热后的最早开产时间 */
    private Date productionResumeTime;
    /** 是否长期在机强制下机 */
    private boolean forceDown;
    /** 触发原因 */
    private String triggerReason;
}
