package com.zlt.aps.lh.api.domain.dto;

import lombok.Data;

import java.util.Date;

/**
 * 机台清洗时间窗口。
 *
 * @author APS
 */
@Data
public class MachineCleaningWindowDTO {

    /** 机台编码 */
    private String lhCode;
    /** 清洗类型 */
    private String cleanType;
    /** 左右模标识 */
    private String leftRightMould;
    /** 模具号 */
    private String mouldCode;
    /** 清洗开始时间 */
    private Date cleanStartTime;
    /** 清洗结束时间 */
    private Date cleanEndTime;
    /** 清洗结束后机台再次可开产时间 */
    private Date readyTime;
    /** 数据来源 */
    private String dataSource;
    /** 清洗备注 */
    private String remark;
}
