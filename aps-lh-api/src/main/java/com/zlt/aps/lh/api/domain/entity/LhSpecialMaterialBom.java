package com.zlt.aps.lh.api.domain.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.ruoyi.common.core.annotation.Excel;
import com.ruoyi.common.core.web.domain.BaseEntity;
import com.zlt.common.annotation.ImportExcelValidated;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.io.Serializable;

@Data
@TableName("T_LH_SPECIAL_MATERIAL_BOM")
@ApiModel(value = "特殊物料清单配置")
public class LhSpecialMaterialBom extends BaseEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 分厂编号
     */
    @Excel(name = "ui.data.column.lhSpecialMaterialBom.factoryCode", dictType = "biz_factory_name", sort = 10)
    @ApiModelProperty(value = "分厂编号")
    @TableField(value = "FACTORY_CODE")
    @ImportExcelValidated(required = true)
    private String factoryCode;

    /**
     * 结构名称
     */
    @Excel(name = "ui.data.column.lhSpecialMaterialBom.structureName", sort = 20)
    @ApiModelProperty(value = "结构名称")
    @TableField(value = "STRUCTURE_NAME")
    @ImportExcelValidated(maxLength = 300)
    private String structureName;

    /**
     * 物料编码
     */
    @Excel(name = "ui.data.column.lhSpecialMaterialBom.materialCode", sort = 30)
    @ApiModelProperty(value = "物料编码")
    @TableField(value = "MATERIAL_CODE")
    @ImportExcelValidated(maxLength = 50)
    private String materialCode;

    /**
     * 物料描述
     */
    @Excel(name = "ui.data.column.lhSpecialMaterialBom.materialDesc", sort = 40)
    @ApiModelProperty(value = "物料描述")
    @TableField(value = "MATERIAL_DESC")
    @ImportExcelValidated(maxLength = 300)
    private String materialDesc;

    /**
     * 分类（01-19.5寸宽基、02-22.5寸宽基、03-芯片胎）
     */
    @Excel(name = "ui.data.column.lhSpecialMaterialBom.category", dictType = "lh_special_material_category", sort = 50)
    @ApiModelProperty(value = "分类（19.5寸宽基、22.5寸宽基、芯片胎）")
    @TableField(value = "CATEGORY")
    @ImportExcelValidated(required = true, maxLength = 2)
    private String category;
}
