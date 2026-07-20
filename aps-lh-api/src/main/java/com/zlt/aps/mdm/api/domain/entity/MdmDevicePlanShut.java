package com.zlt.aps.mdm.api.domain.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.ruoyi.common.core.annotation.Excel;
import com.ruoyi.common.core.web.domain.BaseEntity;
import com.zlt.common.annotation.ImportExcelValidated;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.util.Date;


@ApiModel(value = "0106基础数据_设备计划停机对象", description = "0106基础数据_设备计划停机对象")
@Data
@TableName(value = "T_MDM_DEVICE_PLAN_SHUT")
public class MdmDevicePlanShut extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /**
     * 工厂编号，字典：biz_factory_name
     */
    @ImportExcelValidated(required = true)
    @Excel(name = "ui.data.column.mdmDevicePlanShut.factoryCode", dictType = "biz_factory_name")
    @ApiModelProperty(value = "工厂编号", name = "factoryCode")
    @TableField(value = "FACTORY_CODE")
    private String factoryCode;

//    /**
//     * 工序，字典：work_calendar_proc；01-月计划,02-硫化,03-成型,04-胎面,05-胎侧,06--内衬,07-垫胶,08-胎圈,09-钢丝圈,10-斜裁,11-直裁,12-压延,15-零度,16-密炼
//     */
//    @ImportExcelValidated(required = true)
//    @Excel(name = "ui.data.column.mdmDevicePlanShut.procCode", dictType = "work_calendar_proc")
//    @ApiModelProperty(value = "工序，字典：work_calendar_proc；01-月计划,02-硫化,03-成型,04-胎面,05-胎侧,06--内衬,07-垫胶,08-胎圈,09-钢丝圈,10-斜裁,11-直裁,12-压延,15-零度,16-密炼", name = "procCode")
//    @TableField(value = "PROC_CODE")
//    private String procCode;

    /**
     * 机台类型，字典：device_shut_machine_type；硫化、成型、压出、裁断、压延、密炼；
     */
    @ImportExcelValidated(required = true)
    @Excel(name = "ui.data.column.mdmDevicePlanShut.machineType", dictType = "device_shut_machine_type")
    @ApiModelProperty(value = "机台类型，字典：machine_type；硫化、成型、压出、裁断、压延、密炼；", name = "machineType")
    @TableField(value = "MACHINE_TYPE")
    private String machineType;

    /**
     * 机台编号
     */
    @ImportExcelValidated(required = true, maxLength = 50)
    @Excel(name = "ui.data.column.mdmDevicePlanShut.machineCode")
    @ApiModelProperty(value = "机台编号", name = "machineCode")
    @TableField(value = "MACHINE_CODE")
    private String machineCode;

    /**
     * 停机类型，字典：machine_stop_type；
     * 00-精度校验、01-润滑、02-巡检点检、03-预见性维护、04-预防性维护、05-计划性维修、06-临时性故障、
     * 07-干冰清洗、08-喷砂清洗、09-盘点
     */
    @ImportExcelValidated(required = true)
    @Excel(name = "ui.data.column.mdmDevicePlanShut.machineStopType", dictType = "machine_stop_type")
    @ApiModelProperty(value = "停机类型，字典：machine_stop_type；00-精度校验、01-润滑、02-巡检点检、03-预见性维护、04-预防性维护、05-计划性维修、06-临时性故障、07-干冰清洗、08-喷砂清洗、09-盘点", name = "machineStopType")
    @TableField(value = "MACHINE_STOP_TYPE")
    private String machineStopType;

    /**
     * 开始日期:yyyy-MM-DD HH
     */
    @ImportExcelValidated(required = true, date = true)
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @Excel(name = "ui.data.column.mdmDevicePlanShut.beginDate", width = 30, dateFormat = "yyyy-MM-dd HH:mm:ss")
    @ApiModelProperty(value = "开始日期:yyyy-MM-dd HH:mm:ss", name = "beginDate")
    @TableField(value = "BEGIN_DATE")
    private Date beginDate;

    /**
     * 结束日期:yyyy-MM-DD HH
     */
    @ImportExcelValidated(required = true, date = true)
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @Excel(name = "ui.data.column.mdmDevicePlanShut.endDate", width = 30, dateFormat = "yyyy-MM-dd HH:mm:ss")
    @ApiModelProperty(value = "结束日期:yyyy-MM-dd HH:mm:ss", name = "endDate")
    @TableField(value = "END_DATE")
    private Date endDate;

    /**
     * 排程日期（硫化排程回填给精度计划/清洗计划）
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @Excel(name = "ui.data.column.mdmDevicePlanShut.scheduleDate", width = 30, dateFormat = "yyyy-MM-dd HH:mm:ss")
    @ApiModelProperty(value = "排程日期（硫化排程回填给精度计划/清洗计划）", name = "scheduleDate")
    @TableField(value = "SCHEDULE_DATE")
    private Date scheduleDate;

    @ImportExcelValidated(maxLength = 500)
    @Excel(name = "ui.data.column.mdmMaterialInfo.remark")
    @ApiModelProperty("备注")
    @TableField("REMARK")
    private String remark;
}
