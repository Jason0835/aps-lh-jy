package com.zlt.aps.mdm.api.domain.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.ruoyi.common.core.annotation.Excel;
import com.ruoyi.common.core.web.domain.BaseEntity;
import com.zlt.common.annotation.ImportExcelValidated;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.math.BigDecimal;

/**
 * Copyright (c) 2022, All rights reserved。
 * 文件名称：MdmSkuConstructionRef.java
 * 描    述：SKU与施工（示方书）关系对象 t_mdm_sku_construction_ref
 *@author zlt
 *@date 2025-12-06
 *@version 1.0
 *
 *  修改记录：
 *     修改时间：...
 *     修 改 人：zlt
 *     修改内容：...
 */

@ApiModel(value = "SKU与施工（示方书）关系对象", description = "SKU与施工（示方书）关系对象 ")
@Data
@TableName(value = "T_MDM_SKU_CONSTRUCTION_REF")
public class MdmSkuConstructionRef extends BaseEntity {

    private static final long serialVersionUID = 1L;

     /** 工厂编号 */
    @Excel(name = "ui.data.column.mdmSkuConstructionRef.factoryCode", dictType = "biz_factory_name", sort = 1)
    @ApiModelProperty(value = "工厂编号", name = "factoryCode")
    @ImportExcelValidated(required = true)
    @TableField(value = "FACTORY_CODE")
    private String factoryCode;

    /** 物料编码 */
    @ImportExcelValidated(required = true)
    @Excel(name = "ui.data.column.mdmSkuConstructionRef.materialCode", sort = 15)
    @ApiModelProperty(value = "物料编码", name = "materialCode")
    @TableField(value = "MATERIAL_CODE")
    private String materialCode;

    /**
     * 物料描述
     */
    @Excel(name = "ui.data.column.mdmSkuConstructionRef.materialDesc", width = 30, sort = 16)
    @ApiModelProperty(value = "物料描述", name = "materialDesc")
    @TableField(value = "MATERIAL_DESC")
    private String materialDesc;

    /** MES物料编码 */
//    @Excel(name = "ui.data.column.mdmSkuConstructionRef.mesMaterialCode")
    @ApiModelProperty(value = "MES物料编码", name = "mesMaterialCode")
    @TableField(value = "MES_MATERIAL_CODE")
    private String mesMaterialCode;

    /**
     * 产品状态
     */
    @Excel(name = "ui.data.column.mdmSkuConstructionRef.trialStatus", dictType = "trial_status", sort = 20)
    @ApiModelProperty(value = "产品状态", name = "trialStatus")
    @TableField(value = "TRIAL_STATUS")
    private String trialStatus;

    /** 规格代号 */
//    @Excel(name = "ui.data.column.mdmSkuConstructionRef.specCode")
    @ApiModelProperty(value = "规格代号", name = "specCode")
    @TableField(value = "SPEC_CODE")
    private String specCode;

    /** 施工代号 */
    @Excel(name = "ui.data.column.mdmSkuConstructionRef.constructionCode", sort = 30)
    @ApiModelProperty(value = "施工代号", name = "constructionCode")
    @TableField(value = "CONSTRUCTION_CODE")
    private String constructionCode;

    /** 胎胚号 */
    @Excel(name = "ui.data.column.mdmSkuConstructionRef.embryoCode", sort = 45)
    @ApiModelProperty(value = "胎胚号", name = "embryoCode")
    @TableField(value = "EMBRYO_CODE")
    private String embryoCode;

    /** 生产版本 */
//    @Excel(name = "ui.data.column.mdmSkuConstructionRef.productionVersion")
    @ApiModelProperty(value = "生产版本", name = "productionVersion")
    @TableField(value = "PRODUCTION_VERSION")
    private String productionVersion;

    /** 成型法 */
//    @Excel(name = "ui.data.column.mdmSkuConstructionRef.mouldMethod", dictType = "molding_method")
    @ApiModelProperty(value = "成型法", name = "mouldMethod")
    @TableField(value = "MOULD_METHOD")
    private String mouldMethod;

    /** BOM版本 */
//    @Excel(name = "ui.data.column.mdmSkuConstructionRef.bomVersion", sort = 10)
    @ApiModelProperty(value = "BOM版本", name = "bomVersion")
    @TableField(value = "BOM_VERSION")
    private String bomVersion;

    /** 合模压力 */
//    @Excel(name = "ui.data.column.mdmSkuConstructionRef.mouldClampingPressure")
    @ApiModelProperty(value = "合模压力", name = "mouldClampingPressure")
    @TableField(value = "MOULD_CLAMPING_PRESSURE")
    private BigDecimal mouldClampingPressure;

    /** 模具型腔 */
//    @Excel(name = "ui.data.column.mdmSkuConstructionRef.mouldCavity", sort = 20)
    @ApiModelProperty(value = "模具型腔", name = "mouldCavity")
    @TableField(value = "MOULD_CAVITY")
    private String mouldCavity;

    /** 是否零度材料 */
    @Excel(name = "ui.data.column.mdmSkuConstructionRef.isZeroRack", dictType = "biz_yes_no", sort = 25)
    @ApiModelProperty(value = "是否零度材料", name = "isZeroRack")
    @TableField(value = "IS_ZERO_RACK")
    private String isZeroRack;

    /** 夏季机械硫化时间(分) */
//    @ImportExcelValidated(digits = true)
//    @Excel(name = "ui.data.column.mdmSkuConstructionRef.curingTime", width = 25, sort = 35)
    @ApiModelProperty(value = "夏季机械硫化时间(分)", name = "curingTime")
    @TableField(value = "CURING_TIME")
    private Integer curingTime;

    /** 夏季液压硫化时间(分) */
    @ImportExcelValidated(digits = true)
//    @Excel(name = "ui.data.column.mdmSkuConstructionRef.hydraulicPressureCuringTime", width = 25, sort = 50)
    @ApiModelProperty(value = "夏季液压硫化时间(分)", name = "hydraulicPressureCuringTime")
    @TableField(value = "HY_PRESSURE_CURING_TIME")
    private Integer hydraulicPressureCuringTime;

    /** 冬季机械硫化时间(分) */
//    @ImportExcelValidated(digits = true)
//    @Excel(name = "ui.data.column.mdmSkuConstructionRef.curingTime2", width = 25, sort = 40)
    @ApiModelProperty(value = "冬季机械硫化时间(分)", name = "curingTime2")
    @TableField(value = "CURING_TIME2")
    private Integer curingTime2;

    /** 冬季液压硫化时间(分) */
    @ImportExcelValidated(digits = true)
//    @Excel(name = "ui.data.column.mdmSkuConstructionRef.hydraulicPressureCuringTime2", width = 25, sort = 55)
    @ApiModelProperty(value = "冬季液压硫化时间(分)", name = "hydraulicPressureCuringTime2")
    @TableField(value = "HY_PRESSURE_CURING_TIME2")
    private Integer hydraulicPressureCuringTime2;

    /**
     * 制造示方书号
     */
    @Excel(name = "ui.data.column.mdmSkuConstructionRef.embryoNo", sort = 60)
    @ApiModelProperty(value = "制造示方书号", name = "embryoNo")
    @TableField(value = "EMBRYO_NO")
    private String embryoNo;

    /**
     * 制造示方书类型
     */
    @Excel(name = "ui.data.column.mdmSkuConstructionRef.embryoType", dictType = "trial_status", sort = 65)
    @ApiModelProperty(value = "制造示方书类型", name = "embryoType")
    @TableField(value = "EMBRYO_TYPE")
    private String embryoType;

    /**
     * 制造示方书发行时间
     */
    @Excel(name = "ui.data.column.mdmSkuConstructionRef.embryoReleaseDate", width = 30, sort = 70)
    @ApiModelProperty(value = "制造示方书发行时间", name = "embryoReleaseDate")
    @TableField(value = "EMBRYO_RELEASE_DATE")
    private String embryoReleaseDate;

    /**
     * 文字示方书号
     */
    @Excel(name = "ui.data.column.mdmSkuConstructionRef.textNo", sort = 75)
    @ApiModelProperty(value = "文字示方书号", name = "textNo")
    @TableField(value = "TEXT_NO")
    private String textNo;

    /**
     * 文字示方书类型
     */
    @Excel(name = "ui.data.column.mdmSkuConstructionRef.textType", dictType = "trial_status", sort = 80)
    @ApiModelProperty(value = "文字示方书类型", name = "textType")
    @TableField(value = "TEXT_TYPE")
    private String textType;

    /**
     * 文字示方书发行时间
     */
    @Excel(name = "ui.data.column.mdmSkuConstructionRef.textReleaseDate", width = 30, sort = 85)
    @ApiModelProperty(value = "文字示方书发行时间", name = "textReleaseDate")
    @TableField(value = "TEXT_RELEASE_DATE")
    private String textReleaseDate;

    /**
     * 硫化示方书号
     */
    @Excel(name = "ui.data.column.mdmSkuConstructionRef.lhNo", sort = 90)
    @ApiModelProperty(value = "硫化示方书号", name = "lhNo")
    @TableField(value = "LH_NO")
    private String lhNo;

    /**
     * 硫化示方书类型
     */
    @Excel(name = "ui.data.column.mdmSkuConstructionRef.lhType", dictType = "trial_status", sort = 95)
    @ApiModelProperty(value = "硫化示方书类型", name = "lhType")
    @TableField(value = "LH_TYPE")
    private String lhType;

    /**
     * 硫化示方书发行时间
     */
    @Excel(name = "ui.data.column.mdmSkuConstructionRef.lhReleaseDate", width = 30, sort = 100)
    @ApiModelProperty(value = "硫化示方书发行时间", name = "lhReleaseDate")
    @TableField(value = "LH_RELEASE_DATE")
    private String lhReleaseDate;

    /**
     * 主物料(胎胚号)
     */
    @ImportExcelValidated(required = true)
    @Excel(name = "ui.data.column.mdmSkuConstructionRef.mainMaterialDesc", width = 30, sort = 46)
    @ApiModelProperty(value = "主物料(胎胚号)", name = "mainMaterialDesc")
    @TableField(value = "MAIN_MATERIAL_DESC")
    private String mainMaterialDesc;

}
