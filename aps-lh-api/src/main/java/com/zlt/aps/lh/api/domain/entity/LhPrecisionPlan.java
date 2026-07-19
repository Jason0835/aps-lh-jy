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
 * 1. 每台启用硫化机每个自然年度应维护1条精准计划；计划缺失或重复时APS只告警，不自动生成。
 * 2. APS仅加载当年未完成且实际保养日期为空的计划参与排程，实际完成状态仍由MES/设备侧维护。
 * 3. 保养默认固定在候选日08:00开始、持续7小时，保养后还需计入胶囊预热时间。
 * 4. APS成功排程后只回填SCHEDULE_DATE，表示最新建议安排时间，不代表保养实际完成。
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

    /**
     * APS 最终安排的保养开始时间。
     * <p>该字段只表示排程建议时间，不代表设备已经实际完成保养；实际执行日期和完成状态仍由
     * MES/设备业务侧回写 {@link #actualDate}、{@link #completionStatus}。</p>
     */
    @Excel(name = "ui.data.column.lhPrecisionPlan.scheduleDate", dateFormat = "yyyy-MM-dd HH:mm:ss")
    @ApiModelProperty(value = "APS安排的保养开始时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @TableField("SCHEDULE_DATE")
    private Date scheduleDate;

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
