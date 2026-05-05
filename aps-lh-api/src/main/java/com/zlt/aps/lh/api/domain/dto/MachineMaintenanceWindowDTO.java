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

    /** 机台编号 */
    private String machineCode;
    /** 保养计划日期 */
    private Date planDate;
    /** 保养开始时间 */
    private Date maintenanceStartTime;
    /** 保养结束时间 */
    private Date maintenanceEndTime;
    /** 是否长期在机强制下机 */
    private boolean forceDown;
    /** 触发原因 */
    private String triggerReason;
}
