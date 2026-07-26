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
    /** 数据源维护的到期天数，精度计划触发和排序只使用该字段 */
    private Integer daysToDue;
    /** APS 最终安排的保养日期 */
    private Date planDate;
    /** 保养开始时间 */
    private Date maintenanceStartTime;
    /** 保养结束时间 */
    private Date maintenanceEndTime;
    /** 保养结束并完成胶囊预热后的最早开产时间 */
    private Date productionResumeTime;
    /** 精度执行日前生产、换模、换活字块和首检必须全部完成的截止时间，固定为执行日06:00 */
    private Date productionCutoffTime;
    /** 是否允许在前SKU自然收尾后、生产截止时间前插排完整小余量SKU */
    private boolean preInsertAllowed;
    /** 是否已接受精度前插排SKU，用于禁止同一物理机台重复填充精度前窗口 */
    private boolean preInsertScheduled;
    /** 是否因到期天数不超过强制阈值而需要执行强制下机 */
    private boolean forceDown;
    /** 触发原因 */
    private String triggerReason;
}
