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
 * 模具交替计划实体类
 *
 * @author 自动生成
 * @date 2026-03-23
 */
@ApiModel(value = "模具交替计划对象", description = "模具交替计划表实体对象")
@Data
@TableName(value = "T_LH_MOULD_CHANGE_PLAN")
public class LhMouldChangePlan extends BaseEntity implements Serializable {

    private static final long serialVersionUID = 1L;


    /**
     * 分厂编号
     */
    @Excel(name = "ui.data.column.lhMouldChangePlan.factoryCode")
    @ApiModelProperty(value = "分厂编号", name = "factoryCode", required = true)
    @TableField(value = "FACTORY_CODE")
    private String factoryCode;

    /**
     * 硫化结果批次号
     */
    @Excel(name = "ui.data.column.lhMouldChangePlan.lhResultBatchNo")
    @ApiModelProperty(value = "硫化结果批次号", name = "lhResultBatchNo")
    @TableField(value = "LH_RESULT_BATCH_NO")
    private String lhResultBatchNo;

    /**
     * 工单号（规则：CHG+yyyymmdd+3位流水号）
     */
    @Excel(name = "ui.data.column.lhMouldChangePlan.orderNo")
    @ApiModelProperty(value = "工单号", name = "orderNo", notes = "规则：CHG+yyyymmdd+3位流水号")
    @TableField(value = "ORDER_NO")
    private String orderNo;

    /**
     * 计划日期（格式：YYYY-MM-DD）
     */
    @Excel(name = "ui.data.column.lhMouldChangePlan.planDate")
    @ApiModelProperty(value = "计划日期", name = "planDate", notes = "格式：YYYY-MM-DD")
    @TableField(value = "PLAN_DATE")
    private Date planDate;

    /**
     * 计划顺位
     */
    @Excel(name = "ui.data.column.lhMouldChangePlan.planOrder")
    @ApiModelProperty(value = "计划顺位", name = "planOrder")
    @TableField(value = "PLAN_ORDER")
    private Integer planOrder;

    /**
     * 班别编码（01-夜班；02-早班；03-中班）
     */
    @ApiModelProperty(value = "班别编码（01-夜班；02-早班；03-中班）")
    @Excel(name = "ui.data.column.lhMouldChangePlan.classIndex", dictType = "CLASS_NUM")
    @TableField(value = "CLASS_INDEX")
    private String classIndex;

    /**
     * 排程日期
     */
    @Excel(name = "ui.data.column.lhMouldChangePlan.scheduleDate")
    @ApiModelProperty(value = "排程日期", name = "scheduleDate")
    @TableField(value = "SCHEDULE_DATE")
    private Date scheduleDate;

    /**
     * 左右模（L-左模；R-右模；LR-左右模）
     */
    @Excel(name = "ui.data.column.lhMouldChangePlan.leftRightMould")
    @ApiModelProperty(value = "左右模", name = "leftRightMould", notes = "L-左模；R-右模；LR-左右模")
    @TableField(value = "LEFT_RIGHT_MOULD")
    private String leftRightMould;

    /**
     * 硫化机台编号
     */
    @Excel(name = "ui.data.column.lhMouldChangePlan.lhMachineCode")
    @ApiModelProperty(value = "硫化机台编号", name = "lhMachineCode")
    @TableField(value = "LH_MACHINE_CODE")
    private String lhMachineCode;

    /**
     * 硫化机台名称
     */
    @Excel(name = "ui.data.column.lhMouldChangePlan.lhMachineName")
    @ApiModelProperty(value = "硫化机台名称", name = "lhMachineName")
    @TableField(value = "LH_MACHINE_NAME")
    private String lhMachineName;

    /**
     * 前规格物料编码
     */
    @Excel(name = "ui.data.column.lhMouldChangePlan.beforeMaterialCode")
    @ApiModelProperty(value = "前规格物料编码", name = "beforeMaterialCode")
    @TableField(value = "BEFORE_MATERIAL_CODE")
    private String beforeMaterialCode;

    /**
     * 前规格物料描述
     */
    @Excel(name = "ui.data.column.lhMouldChangePlan.beforeMaterialDesc")
    @ApiModelProperty(value = "前规格物料描述", name = "beforeMaterialDesc")
    @TableField(value = "BEFORE_MATERIAL_DESC")
    private String beforeMaterialDesc;

    /**
     * 交替类型（数据字典：CHANGE_MOULD_TYPE；01-正规 02-更换活字块 03-模具喷砂清洗 04-模具干冰清洗）
     */
    @Excel(name = "ui.data.column.lhMouldChangePlan.changeMouldType")
    @ApiModelProperty(value = "交替类型", name = "changeMouldType", notes = "数据字典：CHANGE_MOULD_TYPE；01-正规 02-更换活字块 03-模具喷砂清洗 04-模具干冰清洗")
    @TableField(value = "CHANGE_MOULD_TYPE")
    private String changeMouldType;

    /**
     * 后规格物料编码
     */
    @Excel(name = "ui.data.column.lhMouldChangePlan.afterMaterialCode")
    @ApiModelProperty(value = "后规格物料编码", name = "afterMaterialCode")
    @TableField(value = "AFTER_MATERIAL_CODE")
    private String afterMaterialCode;

    /**
     * 后规格物料描述
     */
    @Excel(name = "ui.data.column.lhMouldChangePlan.afterMaterialDesc")
    @ApiModelProperty(value = "后规格物料描述", name = "afterMaterialDesc")
    @TableField(value = "AFTER_MATERIAL_DESC")
    private String afterMaterialDesc;

    /**
     * 更换时间
     */
    @Excel(name = "ui.data.column.lhMouldChangePlan.changeTime")
    @ApiModelProperty(value = "更换时间", name = "changeTime")
    @TableField(value = "CHANGE_TIME")
    private Date changeTime;

    /**
     * 模具号
     */
    @Excel(name = "ui.data.column.lhMouldChangePlan.mouldCode")
    @ApiModelProperty(value = "模具号", name = "mouldCode")
    @TableField(value = "MOULD_CODE")
    private String mouldCode;

    /**
     * 是否发布（0-未发布，1-已发布 2-发布失败 3-超时发布 4-待发布。对应数据字典：IS_RELEASE）
     */
    @Excel(name = "ui.data.column.lhMouldChangePlan.isRelease")
    @ApiModelProperty(value = "是否发布", name = "isRelease", notes = "0-未发布，1-已发布 2-发布失败 3-超时发布 4-待发布。对应数据字典：IS_RELEASE")
    @TableField(value = "IS_RELEASE")
    private String isRelease;

    /**
     * 模具交替完成状态（0-未完成；1-已完成）
     */
    @Excel(name = "ui.data.column.lhMouldChangePlan.mouldStatus")
    @ApiModelProperty(value = "模具交替完成状态", name = "mouldStatus", notes = "0-未完成；1-已完成")
    @TableField(value = "MOULD_STATUS")
    private String mouldStatus;

    /**
     * 删除标识（0未删除；1已删除）
     */
    @Excel(name = "ui.data.column.lhMouldChangePlan.isDelete")
    @ApiModelProperty(value = "删除标识（0未删除；1已删除）", name = "isDelete")
    @TableField(value = "IS_DELETE")
    private Integer isDelete;
}
