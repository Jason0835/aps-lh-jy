package com.zlt.aps.mp.api.domain.entity;

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

@ApiModel(value = "模具到货计划对象", description = "模具到货计划对象")
@Data
@TableName(value = "T_MDM_MOULD_DELIVERY_PLAN")
public class MpMouldDeliveryPlan extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /**
     * 工厂
     */
    @ImportExcelValidated(required = true)
    @Excel(name = "ui.data.column.mpMouldDeliveryPlan.factoryCode", dictType = "biz_factory_name")
    @ApiModelProperty(value = "工厂", name = "factoryCode")
    @TableField(value = "FACTORY_CODE")
    private String factoryCode;

    /**
     * 型腔模号
     */
    @ImportExcelValidated(required = true, maxLength = 32)
    @Excel(name = "ui.data.column.mpMouldDeliveryPlan.mouldCode")
    @ApiModelProperty(value = "型腔模号", name = "mouldCode")
    @TableField(value = "MOULD_CODE")
    private String mouldCode;

    /**
     * 主花纹
     */
    @ImportExcelValidated(required = true, maxLength = 64)
    @Excel(name = "ui.data.column.mdmMaterialInfo.mainPattern")
    @ApiModelProperty(value = "主花纹", name = "mainPattern")
    @TableField(value = "MAIN_PATTERN")
    private String mainPattern;

    /**
     * 物料编码
     */
    @ImportExcelValidated(required = true, maxLength = 32)
    @Excel(name = "ui.data.column.mpMouldDeliveryPlan.materialCode")
    @ApiModelProperty(value = "物料编码", name = "materialCode")
    @TableField(value = "MATERIAL_CODE")
    private String materialCode;

    /**
     * MES物料编码
     */
//    @ImportExcelValidated(required = true, maxLength = 32)
//    @Excel(name = "ui.data.column.mpMouldDeliveryPlan.mesMaterialCode")
    @ApiModelProperty(value = "MES物料编码", name = "mesMaterialCode")
    @TableField(value = "MES_MATERIAL_CODE")
    private String mesMaterialCode;

    /**
     * 物料描述
     */
    @ImportExcelValidated(maxLength = 64)
    @Excel(name = "ui.data.column.mpMouldDeliveryPlan.materialDesc")
    @ApiModelProperty(value = "物料描述", name = "materialDesc")
    @TableField(value = "MATERIAL_DESC")
    private String materialDesc;

    /**
     * 计划发货日期
     */
    @ImportExcelValidated(required = true, date = true)
    @JsonFormat(pattern = "yyyy-MM-dd")
    @Excel(name = "ui.data.column.mpMouldDeliveryPlan.shipmentDate", width = 30, dateFormat = "yyyy-MM-dd")
    @ApiModelProperty(value = "计划发货日期", name = "shipmentDate")
    @TableField(value = "SHIPMENT_DATE")
    private Date shipmentDate;

    /**
     * 模具可用日期
     */
//    @ImportExcelValidated(required = true, date = true)
    @JsonFormat(pattern = "yyyy-MM-dd")
    @ApiModelProperty(value = "模具可用日期", name = "boardingDate")
    @TableField(value = "BOARDING_DATE")
    private Date boardingDate;

    /**
     * 计划发货日期-开始时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd")
    @ApiModelProperty(value = "计划发货日期-开始时间", name = "shipmentDateStartTime")
    @TableField(exist = false)
    private Date shipmentDateStartTime;

    /**
     * 计划发货日期-结束时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd")
    @ApiModelProperty(value = "计划发货日期-结束时间", name = "shipmentDateEndTime")
    @TableField(exist = false)
    private Date shipmentDateEndTime;

    /**
     * 计划上机日期-开始时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd")
    @ApiModelProperty(value = "计划上机日期-开始时间", name = "boardingDateStartTime")
    @TableField(exist = false)
    private Date boardingDateStartTime;

    /**
     * 计划上机日期-结束时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd")
    @ApiModelProperty(value = "计划上机日期-结束时间", name = "boardingDateEndTime")
    @TableField(exist = false)
    private Date boardingDateEndTime;

    /**
     * 备注
     */
    @ImportExcelValidated(maxLength = 500)
    @Excel(name = "ui.common.column.remark")
    @ApiModelProperty(value = "备注", name = "remark")
    @TableField(value = "REMARK")
    private String remark;
}
