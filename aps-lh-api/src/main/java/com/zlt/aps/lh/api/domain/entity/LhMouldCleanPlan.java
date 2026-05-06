package com.zlt.aps.lh.api.domain.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.ruoyi.common.core.annotation.Excel;
import com.ruoyi.common.core.web.domain.BaseEntity;
import com.zlt.common.annotation.ImportExcelValidated;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.format.annotation.DateTimeFormat;

import java.io.Serializable;
import java.util.Date;

/**
 * APS模具清洗计划
 *
 * @author zlt
 * @since 2026/04/10
 */
@ApiModel(value = "APS模具清洗计划", description = "APS模具清洗计划")
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("T_LH_MOULD_CLEAN_PLAN")
public class LhMouldCleanPlan extends BaseEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    @ApiModelProperty(value = "工厂")
    @Excel(name = "ui.data.column.mouldCleanPlan.factoryCode",dictType = "biz_factory_name")
    @TableField(value = "FACTORY_CODE")
    private String factoryCode;

    @ApiModelProperty(value = "分公司编码")
    @TableField(value = "COMPANY_CODE")
    private String companyCode;

    @ApiModelProperty(value = "硫化机台")
    @Excel(name = "ui.data.column.mouldCleanPlan.lhCode")
    @ImportExcelValidated(required = true)
    @TableField(value = "LH_CODE")
    private String lhCode;

    @ApiModelProperty(value = "清洗类型：01-干冰清洗，02-喷砂清洗")
    @Excel(name = "ui.data.column.mouldCleanPlan.cleanType",dictType = "mould_clean_type")
    @TableField(value = "CLEAN_TYPE")
    private String cleanType;

    @ApiModelProperty(value = "清洗时间")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    @Excel(name = "ui.data.column.mouldCleanPlan.cleanTime", width = 30, dateFormat = "yyyy-MM-dd HH:mm:ss")
    @ImportExcelValidated(required = true)
    @TableField(value = "CLEAN_TIME")
    private Date cleanTime;

    @ApiModelProperty(value = "数据来源：0-手工录入，1-系统生成")
    @TableField(value = "DATA_SOURCE")
    private String dataSource;

    @ApiModelProperty(value = "版本号")
    @TableField(value = "DATA_VERSION")
    private String dataVersion;

    @ApiModelProperty(value = "左右模(L/R/LR)")
    @Excel(name = "ui.data.column.mouldCleanPlan.leftRightMould")
    @TableField(value = "LEFT_RIGHT_MOULD")
    private String leftRightMould;

    @ApiModelProperty(value = "完成状态: 0-未完成 1-已完成")
    @Excel(name = "ui.data.column.mouldCleanPlan.finishStatus")
    @TableField(value = "FINISH_STATUS")
    private String finishStatus;

    @ApiModelProperty(value = "备注")
    @Excel(name = "ui.data.column.mouldCleanPlan.remark")
    @TableField(value = "REMARK")
    private String remark;

    @ApiModelProperty(value = "更新时间")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    @Excel(name = "ui.data.column.mouldCleanPlan.updateTime", width = 30, dateFormat = "yyyy-MM-dd HH:mm:ss")
    @TableField(value = "UPDATE_TIME")
    private Date updateTime;

}
