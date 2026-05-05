package com.zlt.aps.lh.api.domain.dto;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;

/**
 * 班次排产管控信息。
 * <p>承载工作日历、开产、停产共同计算后的班次可排窗口。</p>
 *
 * @author APS
 */
@Data
public class ShiftProductionControlDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 班次索引 */
    private int shiftIndex;
    /** 班次编码 */
    private String shiftCode;
    /** 班次名称 */
    private String shiftName;
    /** 工作日期 */
    private Date workDate;
    /** 班次开始时间 */
    private Date shiftStartTime;
    /** 班次结束时间 */
    private Date shiftEndTime;
    /** 有效排产开始时间 */
    private Date effectiveStartTime;
    /** 有效排产结束时间 */
    private Date effectiveEndTime;
    /** 是否可排产 */
    private boolean canSchedule;
    /** 产能比例 */
    private BigDecimal capacityRate;
    /** 不可排原因 */
    private String unavailableReason;
}
