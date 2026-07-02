package com.zlt.aps.mdm.api.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.ruoyi.common.core.annotation.Excel;
import com.ruoyi.common.core.web.domain.BaseEntity;
import com.zlt.common.annotation.ImportExcelValidated;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.util.Date;


@ApiModel(value = "SKU与模具关系对象", description = "SKU与模具关系对象")
@Data
@TableName(value = "T_MDM_SKU_MOULD_REL")
public class MdmSkuMouldRel extends BaseEntity {

    private static final long serialVersionUID = 1L;

    @TableId(value = "ID", type = IdType.AUTO)
    private Long id;

    /**
     * 分厂编码
     */
    @ImportExcelValidated(required = true)
    @Excel(name = "ui.data.column.mdmMaterialInfo.factoryCode", dictType = "biz_factory_name")
    @ApiModelProperty(value = "分厂编码", name = "factoryCode")
    @TableField(value = "FACTORY_CODE")
    private String factoryCode;

    /**
     * 物料编号
     */
    @ImportExcelValidated(required = true, maxLength = 20)
    @Excel(name = "ui.data.column.relation.materialCode")
    @ApiModelProperty(value = "物料编号", name = "materialCode")
    @TableField(value = "MATERIAL_CODE")
    private String materialCode;

    /**
     * MES物料编号
     */
//    @ImportExcelValidated(required = true, maxLength = 20)
//    @Excel(name = "ui.data.column.mdmMaterialInfo.mesProductCode")
    @ApiModelProperty(value = "物料编号", name = "mesMaterialCode")
    @TableField(value = "MES_MATERIAL_CODE")
    private String mesMaterialCode;

    /**
     * 规格描述
     */
    @ImportExcelValidated(maxLength = 256)
    @Excel(name = "ui.data.column.relation.materialDesc")
    @ApiModelProperty(value = "规格描述", name = "materialDesc")
    @TableField(value = "MATERIAL_DESC")
    private String materialDesc;

    /**
     * 规格代号
     */
//    @ImportExcelValidated(required = true, maxLength = 20)
//    @Excel(name = "ui.data.column.relation.specCode")
    @ApiModelProperty(value = "规格代号", name = "specCode")
    @TableField(value = "SPEC_CODE")
    private String specCode;

    /**
     * 型腔模号
     */
    @ImportExcelValidated(required = true, maxLength = 40)
    @Excel(name = "ui.data.column.relation.mouldCode")
    @ApiModelProperty(value = "型腔模号", name = "mouldCode")
    @TableField(value = "MOULD_CODE")
    private String mouldCode;

    /**
     * 规格
     */
//    @Excel(name = "ui.data.column.relation.specifications")
    @ApiModelProperty(value = "规格", name = "specifications")
    @TableField(value = "SPECIFICATIONS")
    private String specifications;

    /**
     * 花纹
     */
//    @Excel(name = "ui.data.column.relation.pattern")
    @ApiModelProperty(value = "花纹", name = "pattern")
    @TableField(value = "PATTERN")
    private String pattern;

    /**
     * 主花纹
     */
    @ImportExcelValidated(required = true, maxLength = 64)
    @Excel(name = "ui.data.column.mdmMaterialInfo.mainPattern")
    @ApiModelProperty(value = "主花纹", name = "mainPattern")
    @TableField(value = "MAIN_PATTERN")
    private String mainPattern;

    /**
     * 是否共用花纹/侧板块，字典：biz_yes_no，0否1是
     */
    @ImportExcelValidated(required = true)
    @Excel(name = "ui.data.column.relation.isSamePatternPanel", width = 20, dictType = "biz_yes_no")
    @ApiModelProperty(value = "是否共用花纹/侧板块，字典：biz_yes_no，0否1是", name = "isSamePatterPanel")
    @TableField(value = "IS_SAME_PATTER_PANEL")
    private String isSamePatterPanel;

    /**
     * 共用模具号
     */
    @Excel(name = "ui.data.column.relation.shareMouldCode")
    @ApiModelProperty(value = "共用模具号", name = "shareMouldCode")
    @TableField(value = "SHARE_MOULD_CODE")
    private String shareMouldCode;

    /**
     * 品牌
     */
//    @Excel(name = "ui.data.column.relation.brand", dictType = "biz_brand_type")
    @ApiModelProperty(value = "品牌", name = "brand")
    @TableField(value = "BRAND")
    private String brand;

    /**
     * 模具大类
     */
//    @Excel(name = "ui.data.column.relation.mouldCategory")
    @ApiModelProperty(value = "模具大类", name = "mouldCategory")
    @TableField(value = "MOULD_CATEGORY")
    private String mouldCategory;

    @ImportExcelValidated(maxLength = 500)
    @Excel(name = "ui.data.column.mdmMaterialInfo.remark")
    @ApiModelProperty("备注")
    @TableField("REMARK")
    private String remark;

    @TableField(exist = false)
    private Integer index;

    @TableField(exist = false)
    private Integer shareNum;

    public String getUnikey() {
        return getMaterialCode() + getMouldCode();
    }

    /**
     * 更新重复Key
     * SAP代码，规格代号，模具号
     *
     * @return
     */
    public String getUpdateGroupKey() {
        String duplicateKeyFormat = "%s|*|%s|*|%s";
        return String.format(duplicateKeyFormat, getMaterialCode(), getSpecCode(), getMouldCode());
    }

    /**
     * 模具
     */
    @ApiModelProperty(value = "模具", name = "mouldNo")
    @TableField(exist = false)
    private String mouldNo;

    /**
     * 成型法
     */
    @ApiModelProperty(value = "成型法", name = "mouldMethod")
    @TableField(exist = false)
    private String mouldMethod;

    /**
     * 胎胚代码
     */
    @ApiModelProperty(value = "胎胚代码", name = "embryoCode")
    @TableField(exist = false)
    private String embryoCode;

    /**
     * 寸口
     */
    @ApiModelProperty(value = "寸口", name = "proSize")
    @TableField(exist = false)
    private String proSize;

    /**
     * 数据版本
     */
    @ApiModelProperty(value = "数据版本", name = "dataVersion")
    @TableField(exist = false)
    private String dataVersion;

    /**
     * 模具类型，4-型腔，5-侧板
     */
    @ApiModelProperty(value = "模具类型，4-型腔，5-侧板", name = "mouldType")
    @TableField(exist = false)
    private String mouldType;

    /**
     * 模具可用日期
     */
    @JsonFormat(pattern = "yyyy-MM-dd")
    @ApiModelProperty(value = "模具可用日期", name = "boardingDate")
    @TableField(exist = false)
    private Date boardingDate;

}
