package com.zlt.aps.lh.api.domain.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.ruoyi.common.core.web.domain.BaseEntity;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 硫化在机信息表
 *
 * @author APS Team
 * @since 2026/04/09
 */
@ApiModel(value = "硫化在机信息表", description = "硫化在机信息表")
@Data
@TableName(value = "T_LH_MACHINE_ONLINE_INFO")
public class LhMachineOnlineInfo extends BaseEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    @ApiModelProperty(value = "日期", name = "onlineDate")
    @TableField(value = "ONLINE_DATE")
    private Date onlineDate;

    @ApiModelProperty(value = "硫化机台", name = "lhCode")
    @TableField(value = "LH_CODE")
    private String lhCode;

    @ApiModelProperty(value = "在机物料编码（NC）", name = "materialCode")
    @TableField(value = "MATERIAL_CODE")
    private String materialCode;

    @ApiModelProperty(value = "在机物料编码（MES）", name = "mesMaterialCode")
    @TableField(value = "MES_MATERIAL_CODE")
    private String mesMaterialCode;

    @ApiModelProperty(value = "产品状态", name = "productStatus")
    @TableField(value = "PRODUCT_STATUS")
    private String productStatus;

    @ApiModelProperty(value = "在机物料描述", name = "specDesc")
    @TableField(value = "SPEC_DESC")
    private String specDesc;

    @ApiModelProperty(value = "左右模", name = "lrMolds")
    @TableField(value = "LR_MOLDS")
    private String lrMolds;

    @ApiModelProperty(value = "在机模号，多个英文逗号分隔", name = "inMachineMouldCode")
    @TableField(value = "IN_MACHINE_MOULD_CODE")
    private String inMachineMouldCode;

    @ApiModelProperty(value = "版本号", name = "dataVersion")
    @TableField(value = "DATA_VERSION")
    private String dataVersion;

    @ApiModelProperty(value = "分公司编码", name = "companyCode")
    @TableField(value = "COMPANY_CODE")
    private String companyCode;

    @ApiModelProperty(value = "厂别", name = "factoryCode")
    @TableField(value = "FACTORY_CODE")
    private String factoryCode;

}
