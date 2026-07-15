package com.zlt.aps.lh.api.domain.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.ruoyi.common.core.annotation.Excel;
import com.ruoyi.common.core.web.domain.BaseEntity;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 硫化未排结果实体类
 *
 * @author 自动生成
 * @date 2026-03-23
 */
@ApiModel(value = "硫化未排结果对象", description = "硫化未排结果表实体对象")
@Data
@TableName(value = "T_LH_UNSCHEDULED_RESULT")
public class LhUnscheduledResult extends BaseEntity implements Serializable {

    private static final long serialVersionUID = 1L;


    /**
     * 分厂编号
     */
    @Excel(name = "ui.data.column.lhUnscheduledResult.factoryCode")
    @ApiModelProperty(value = "分厂编号", name = "factoryCode")
    @TableField(value = "FACTORY_CODE")
    private String factoryCode;

    /**
     * 自动排程批次号
     */
    @Excel(name = "ui.data.column.lhUnscheduledResult.batchNo")
    @ApiModelProperty(value = "自动排程批次号", name = "batchNo")
    @TableField(value = "BATCH_NO")
    private String batchNo;

    /**
     * 工单号
     */
    @Excel(name = "ui.data.column.lhUnscheduledResult.orderNo")
    @ApiModelProperty(value = "工单号", name = "orderNo")
    @TableField(value = "ORDER_NO")
    private String orderNo;

    /**
     * 排程日期
     */
    @Excel(name = "ui.data.column.lhUnscheduledResult.scheduleDate")
    @ApiModelProperty(value = "排程日期", name = "scheduleDate")
    @TableField(value = "SCHEDULE_DATE")
    private Date scheduleDate;

    /**
     * 月计划需求版本
     */
    @Excel(name = "ui.data.column.lhUnscheduledResult.monthPlanVersion")
    @ApiModelProperty(value = "月计划需求版本", name = "monthPlanVersion")
    @TableField(value = "MONTH_PLAN_VERSION")
    private String monthPlanVersion;

    /**
     * 月计划排产版本
     */
    @Excel(name = "ui.data.column.lhUnscheduledResult.productionVersion")
    @ApiModelProperty(value = "月计划排产版本", name = "productionVersion")
    @TableField(value = "PRODUCTION_VERSION")
    private String productionVersion;

    /**
     * 物料编号
     */
    @Excel(name = "ui.data.column.lhUnscheduledResult.materialCode")
    @ApiModelProperty(value = "物料编号", name = "materialCode")
    @TableField(value = "MATERIAL_CODE")
    private String materialCode;

    /**
     * 产品状态：X-试验示方，T-量试示方，S-正规示方
     */
    @Excel(name = "ui.data.column.lhUnscheduledResult.productStatus")
    @ApiModelProperty(value = "产品状态：X-试验示方，T-量试示方，S-正规示方", name = "productStatus")
    @TableField(value = "PRODUCT_STATUS")
    private String productStatus;

    /**
     * 产品结构
     */
    @Excel(name = "ui.data.column.lhUnscheduledResult.structureName")
    @ApiModelProperty(value = "产品结构", name = "structureName")
    @TableField(value = "STRUCTURE_NAME")
    private String structureName;

    /**
     * 物料描述
     */
    @Excel(name = "ui.data.column.lhUnscheduledResult.materialDesc")
    @ApiModelProperty(value = "物料描述", name = "materialDesc")
    @TableField(value = "MATERIAL_DESC")
    private String materialDesc;

    /**
     * 主物料(胎胚描述)
     */
    @Excel(name = "ui.data.column.lhUnscheduledResult.mainMaterialDesc")
    @ApiModelProperty(value = "主物料(胎胚描述)", name = "mainMaterialDesc")
    @TableField(value = "MAIN_MATERIAL_DESC")
    private String mainMaterialDesc;

    /**
     * 规格代码
     */
    @Excel(name = "ui.data.column.lhUnscheduledResult.specCode")
    @ApiModelProperty(value = "规格代码", name = "specCode")
    @TableField(value = "SPEC_CODE")
    private String specCode;

    /**
     * 胎胚代码
     */
    @Excel(name = "ui.data.column.lhUnscheduledResult.embryoCode")
    @ApiModelProperty(value = "胎胚代码", name = "embryoCode")
    @TableField(value = "EMBRYO_CODE")
    private String embryoCode;

    /**
     * 规格描述信息
     */
    @Excel(name = "ui.data.column.lhUnscheduledResult.specDesc")
    @ApiModelProperty(value = "规格描述信息", name = "specDesc")
    @TableField(value = "SPEC_DESC")
    private String specDesc;

    /**
     * 未排数量
     */
    @Excel(name = "ui.data.column.lhUnscheduledResult.unscheduledQty")
    @ApiModelProperty(value = "未排数量", name = "unscheduledQty")
    @TableField(value = "UNSCHEDULED_QTY")
    private Integer unscheduledQty;

    /**
     * 未排原因
     */
    @Excel(name = "ui.data.column.lhUnscheduledResult.unscheduledReason")
    @ApiModelProperty(value = "未排原因", name = "unscheduledReason")
    @TableField(value = "UNSCHEDULED_REASON")
    private String unscheduledReason;

    /**
     * 使用模数
     */
    @Excel(name = "ui.data.column.lhUnscheduledResult.mouldQty")
    @ApiModelProperty(value = "使用模数", name = "mouldQty")
    @TableField(value = "MOULD_QTY")
    private Integer mouldQty;

    /**
     * 数据来源：0-自动排程；1-插单；2-导入
     */
    @Excel(name = "ui.data.column.lhUnscheduledResult.dataSource")
    @ApiModelProperty(value = "数据来源：0-自动排程；1-插单；2-导入", name = "dataSource")
    @TableField(value = "DATA_SOURCE")
    private String dataSource;

    /**
     * 处理时间
     */
    @Excel(name = "ui.data.column.lhUnscheduledResult.processedTime")
    @ApiModelProperty(value = "处理时间", name = "processedTime")
    @TableField(value = "PROCESSED_TIME")
    private Date processedTime;

    /**
     * 删除标识（0未删除；1已删除）
     */
    @Excel(name = "ui.data.column.lhUnscheduledResult.isDelete")
    @ApiModelProperty(value = "删除标识（0未删除；1已删除）", name = "isDelete")
    @TableField(value = "IS_DELETE")
    private Integer isDelete;
}
