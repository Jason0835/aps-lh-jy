package com.zlt.aps.cx.api.domain.entity;

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


@ApiModel(value = "成型库存信息对象", description = "成型库存信息对象 ")
@Data
@TableName(value = "T_CX_STOCK")
public class CxStock extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** 分厂编号 */
    @Excel(name = "ui.data.column.cxStock.factoryCode", dictType = "biz_factory_name")
    @ImportExcelValidated(required = true,maxLength = 30)
    @ApiModelProperty(value = "分厂编号", name = "factoryCode")
    @TableField(value = "FACTORY_CODE")
    private String factoryCode;

    /** 库存日期，格式：yyyy-MM-dd */
    @JsonFormat(pattern = "yyyy-MM-dd")
    @Excel(name = "ui.data.column.cxStock.stockDate", width = 30, dateFormat = "yyyy-MM-dd")
    @ImportExcelValidated(required = true)
    @ApiModelProperty(value = "库存日期，格式：yyyy-MM-dd", name = "stockDate")
    @TableField(value = "STOCK_DATE")
    private Date stockDate;

    /** 胎胚代码 */
    @Excel(name = "ui.data.column.cxStock.embryoCode")
    @ImportExcelValidated(required = true)
    @ApiModelProperty(value = "胎胚代码", name = "embryoCode")
    @TableField(value = "EMBRYO_CODE")
    private String embryoCode;

    /** 胎胚描述（关联查询） */
    @ApiModelProperty(value = "胎胚描述", name = "embryoDesc")
    @TableField(exist = false)
    @Excel(name = "ui.data.column.cxStock.embryoDesc",width = 60)
    private String embryoDesc;

    /** 库存量 */
    @Excel(name = "ui.data.column.cxStock.stockNum")
    @ImportExcelValidated(required = true,digits = true,max = 9999999)
    @ApiModelProperty(value = "库存量", name = "stockNum")
    @TableField(value = "STOCK_NUM")
    private Integer stockNum;

    /** 超期库存 */
    // @Excel(name = "ui.data.column.cxStock.overTimeStock")
    @ApiModelProperty(value = "超期库存", name = "overTimeStock")
    @TableField(value = "OVER_TIME_STOCK")
    private Integer overTimeStock;

    /** 修正数量 */
    //   @Excel(name = "ui.data.column.cxStock.modifyNum")
    @ApiModelProperty(value = "修正数量", name = "modifyNum")
    @TableField(value = "MODIFY_NUM")
    private Integer modifyNum;

    /** 不良数量 */
//    @Excel(name = "ui.data.column.cxStock.badNum")
    @ApiModelProperty(value = "不良数量", name = "badNum")
    @TableField(value = "BAD_NUM")
    private Integer badNum;


    /** 是否收尾SKU：0-否，1-是 */
//    @Excel(name = "ui.data.column.cxStock.isEndingSku", dictType = "biz_yes_no")
    @ApiModelProperty(value = "是否收尾SKU：0-否，1-是", name = "isEndingSku")
    @TableField(value = "IS_ENDING_SKU")
    private String isEndingSku;


}
