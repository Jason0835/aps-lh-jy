
package com.zlt.aps.cx.entity.config;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.ruoyi.common.core.annotation.Excel;
import com.ruoyi.common.core.web.domain.BaseEntity;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.util.Date;

/**
 * 胎胚最早可供硫化时间实体（结构切换时）
 * <p>
 * 定义各结构切换时胎胚最早可供硫化的时间，用于排程计算
 *
 * @author APS Team
 */
@Data
@TableName("T_CX_EMBRYO_LH_TIME")
@ApiModel(value = "胎胚最早可供硫化时间")
public class CxEmbryoLhTime extends BaseEntity {

    private static final long serialVersionUID = 1L;

    @ApiModelProperty(value = "分厂编号")
    @TableField(value = "FACTORY_CODE")
    private String factoryCode;

    @ApiModelProperty(value = "排程日期")
    @JsonFormat(pattern = "yyyy-MM-dd", timezone = "GMT+8")
    @TableField("SCHEDULE_DATE")
    private Date scheduleDate;

    @ApiModelProperty(value = "成型机台编码")
    @TableField("CX_MACHINE_CODE")
    private String cxMachineCode;

    @Excel(name = "ui.data.column.cxEmbryoLhTime.structureName")
    @ApiModelProperty(value = "结构")
    @TableField("STRUCTURE_NAME")
    private String structureName;

    @Excel(name = "ui.data.column.cxEmbryoLhTime.earliestLhTime", dateFormat = "yyyy-MM-dd HH:mm:ss")
    @ApiModelProperty(value = "最早可供硫化时间")
    @TableField("EARLIEST_LH_TIME")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date earliestLhTime;

    @ApiModelProperty(value = "收尾时间（前结构结束时间，不含切换耗时）")
    @TableField("ENDING_TIME")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date endingTime;

    @ApiModelProperty(value = "结构排程开始前的成型余量")
    @TableField("STRUCTURE_CHANGE_REMAINING")
    private Integer structureChangeRemaining;

    @ApiModelProperty(value = "后结构（切换后的新结构）")
    @TableField("NEXT_STRUCTURE_NAME")
    private String nextStructureName;

    @ApiModelProperty(value = "行数")
    @TableField(exist = false)
    private Integer rowNo;
}
