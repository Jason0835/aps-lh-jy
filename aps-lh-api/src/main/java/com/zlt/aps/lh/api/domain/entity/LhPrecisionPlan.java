package com.zlt.aps.lh.api.domain.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.ruoyi.common.core.annotation.Excel;
import com.ruoyi.common.core.web.domain.BaseEntity;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;

/**
 * 硫化精度计划实体
 *
 * 业务规则：
 * 1. 硫化机台每年都要维保1次
 * 2. 维保计划7个小时（8:00-15:00）
 * 3. 第1次数据源于MES，后面APS自动按自然年度自动推算（前年的实际时间）
 * 4. 需要提前30天预警
 *
 * @author APS Team
 */
@Data
@TableName("T_LH_PRECISION_PLAN")
@ApiModel(value = "硫化精度计划", description = "硫化机台精度保养计划")
public class LhPrecisionPlan extends BaseEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    @Excel(name = "ui.data.column.lhPrecisionPlan.factoryCode")
    @ApiModelProperty(value = "分厂编码")
    @TableField("FACTORY_CODE")
    private String factoryCode;

    @Excel(name = "ui.data.column.lhPrecisionPlan.year")
    @ApiModelProperty(value = "计划年度")
    @TableField("YEAR")
    private BigDecimal year;

    @Excel(name = "ui.data.column.lhPrecisionPlan.machineCode")
    @ApiModelProperty(value = "机台编号")
    @TableField("MACHINE_CODE")
    private String machineCode;

    @Excel(name = "ui.data.column.lhPrecisionPlan.precisionType")
    @ApiModelProperty(value = "精度类型")
    @TableField("PRECISION_TYPE")
    private String precisionType;

    @Excel(name = "ui.data.column.lhPrecisionPlan.planDate", dateFormat = "yyyy-MM-dd")
    @ApiModelProperty(value = "计划日期")
    @JsonFormat(pattern = "yyyy-MM-dd")
    @TableField("PLAN_DATE")
    private Date planDate;

    @Excel(name = "ui.data.column.lhPrecisionPlan.actualDate", dateFormat = "yyyy-MM-dd")
    @ApiModelProperty(value = "实际执行日期")
    @JsonFormat(pattern = "yyyy-MM-dd")
    @TableField("ACTUAL_DATE")
    private Date actualDate;

    @Excel(name = "ui.data.column.lhPrecisionPlan.daysToDue")
    @ApiModelProperty(value = "距离到期日剩余天数")
    @TableField("DAYS_TO_DUE")
    private Integer daysToDue;

    @ApiModelProperty(value = "到期日期")
    @JsonFormat(pattern = "yyyy-MM-dd")
    @TableField("DUE_DATE")
    private Date dueDate;

    @ApiModelProperty(value = "上次保养日期")
    @JsonFormat(pattern = "yyyy-MM-dd")
    @TableField("LAST_MAINTENANCE_DATE")
    private Date lastMaintenanceDate;

    @ApiModelProperty(value = "完成情况：0-未完成，1-已完成")
    @TableField("COMPLETION_STATUS")
    private String completionStatus;

    @ApiModelProperty(value = "预警状态：0-未预警，1-已预警")
    @TableField("WARNING_STATUS")
    private String warningStatus;

    @ApiModelProperty(value = "预警触发日期")
    @JsonFormat(pattern = "yyyy-MM-dd")
    @TableField("WARNING_DATE")
    private Date warningDate;

    @ApiModelProperty(value = "是否已发送预警：0-未发送，1-已发送")
    @TableField("IS_WARNING_SENT")
    private String isWarningSent;

    @ApiModelProperty(value = "数据来源：0-同步，1-自动生成")
    @TableField("DATA_SOURCE")
    private String dataSource;

    @Excel(name = "ui.data.column.remark")
    @ApiModelProperty(value = "备注")
    @TableField("REMARK")
    private String remark;

    @ApiModelProperty(value = "MES来源ID")
    @TableField("MES_SOURCE_ID")
    private Long mesSourceId;

    @ApiModelProperty(value = "分公司编码")
    @TableField("COMPANY_CODE")
    private String companyCode;

}
