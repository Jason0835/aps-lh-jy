package com.zlt.aps.lh.api.domain.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.ruoyi.common.core.annotation.Excel;
import com.ruoyi.common.core.web.domain.BaseEntity;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;

/**
 * 硫化排程结果实体类
 *
 * @author 自动生成
 * @date 2026-03-23
 */
@ApiModel(value = "硫化排程结果对象", description = "硫化排程结果表实体对象")
@Data
@TableName(value = "T_LH_SCHEDULE_RESULT")
public class LhScheduleResult extends BaseEntity implements Serializable {

    private static final long serialVersionUID = 1L;


    /**
     * 分厂编号
     */
    @Excel(name = "ui.data.column.lhScheduleResult.factoryCode")
    @ApiModelProperty(value = "分厂编号", name = "factoryCode")
    @TableField(value = "FACTORY_CODE")
    private String factoryCode;

    /**
     * 批次号（规则：LHPC+年月日+3位流水号）
     */
    @Excel(name = "ui.data.column.lhScheduleResult.batchNo")
    @ApiModelProperty(value = "批次号", name = "batchNo")
    @TableField(value = "BATCH_NO")
    private String batchNo;

    /**
     * 唯一工单号（规则：LHGD+年月日+3位流水号）
     */
    @Excel(name = "ui.data.column.lhScheduleResult.orderNo")
    @ApiModelProperty(value = "唯一工单号", name = "orderNo")
    @TableField(value = "ORDER_NO")
    private String orderNo;

    /**
     * 硫化机台编号
     */
    @Excel(name = "ui.data.column.lhScheduleResult.lhMachineCode")
    @ApiModelProperty(value = "硫化机台编号", name = "lhMachineCode")
    @TableField(value = "LH_MACHINE_CODE")
    private String lhMachineCode;

    /**
     * 左右模（L:左模；R:右模；LR:双模）
     */
    @Excel(name = "ui.data.column.lhScheduleResult.leftRightMould")
    @ApiModelProperty(value = "左右模", name = "leftRightMould")
    @TableField(value = "LEFT_RIGHT_MOULD")
    private String leftRightMould;

    /**
     * 硫化机台名称
     */
    @Excel(name = "ui.data.column.lhScheduleResult.lhMachineName")
    @ApiModelProperty(value = "硫化机台名称", name = "lhMachineName")
    @TableField(value = "LH_MACHINE_NAME")
    private String lhMachineName;

    /**
     * 物料编号
     */
    @Excel(name = "ui.data.column.lhScheduleResult.materialCode")
    @ApiModelProperty(value = "物料编号", name = "materialCode")
    @TableField(value = "MATERIAL_CODE")
    private String materialCode;

    /**
     * 规格代码
     */
    @Excel(name = "ui.data.column.lhScheduleResult.specCode")
    @ApiModelProperty(value = "规格代码", name = "specCode")
    @TableField(value = "SPEC_CODE")
    private String specCode;

    /**
     * 胎胚代码
     */
    @Excel(name = "ui.data.column.lhScheduleResult.embryoCode")
    @ApiModelProperty(value = "胎胚代码", name = "embryoCode")
    @TableField(value = "EMBRYO_CODE")
    private String embryoCode;

    /**
     * 产品结构
     */
    @Excel(name = "ui.data.column.lhScheduleResult.structureName")
    @ApiModelProperty(value = "产品结构", name = "structureName")
    @TableField(value = "STRUCTURE_NAME")
    private String structureName;

    /**
     * 物料描述
     */
    @Excel(name = "ui.data.column.lhScheduleResult.materialDesc")
    @ApiModelProperty(value = "物料描述", name = "materialDesc")
    @TableField(value = "MATERIAL_DESC")
    private String materialDesc;

    /**
     * 主物料(胎胚描述)
     */
    @Excel(name = "ui.data.column.lhScheduleResult.mainMaterialDesc")
    @ApiModelProperty(value = "主物料(胎胚描述)", name = "mainMaterialDesc")
    @TableField(value = "MAIN_MATERIAL_DESC")
    private String mainMaterialDesc;

    /**
     * 胎胚库存
     */
    @Excel(name = "ui.data.column.lhScheduleResult.embryoStock")
    @ApiModelProperty(value = "胎胚库存", name = "embryoStock")
    @TableField(value = "EMBRYO_STOCK")
    private Integer embryoStock;

    /**
     * 规格描述信息
     */
    @Excel(name = "ui.data.column.lhScheduleResult.specDesc")
    @ApiModelProperty(value = "规格描述信息", name = "specDesc")
    @TableField(value = "SPEC_DESC")
    private String specDesc;

    /**
     * 硫化时长 单位：秒
     */
    @Excel(name = "ui.data.column.lhScheduleResult.lhTime")
    @ApiModelProperty(value = "硫化时长（秒）", name = "lhTime")
    @TableField(value = "LH_TIME")
    private Integer lhTime;

    /**
     * 日计划数量
     */
    @Excel(name = "ui.data.column.lhScheduleResult.dailyPlanQty")
    @ApiModelProperty(value = "日计划数量", name = "dailyPlanQty")
    @TableField(value = "DAILY_PLAN_QTY")
    private Integer dailyPlanQty;

    /**
     * 排程日期
     */
    @Excel(name = "ui.data.column.lhScheduleResult.scheduleDate")
    @JsonFormat(pattern = "yyyy-MM-dd")
    @ApiModelProperty(value = "排程日期", name = "scheduleDate")
    @TableField(value = "SCHEDULE_DATE")
    private Date scheduleDate;

    /**
     * 规格结束时间
     */
    @Excel(name = "ui.data.column.lhScheduleResult.specEndTime")
    @ApiModelProperty(value = "规格结束时间", name = "specEndTime")
    @TableField(value = "SPEC_END_TIME")
    private Date specEndTime;

    /**
     * 保养开始时间
     */
    @Excel(name = "ui.data.column.lhScheduleResult.maintenanceStartTime")
    @ApiModelProperty(value = "保养开始时间", name = "maintenanceStartTime")
    @TableField(value = "MAINTENANCE_START_TIME")
    private Date maintenanceStartTime;

    /**
     * 保养结束时间
     */
    @Excel(name = "ui.data.column.lhScheduleResult.maintenanceEndTime")
    @ApiModelProperty(value = "保养结束时间", name = "maintenanceEndTime")
    @TableField(value = "MAINTENANCE_END_TIME")
    private Date maintenanceEndTime;

    /**
     * 设备停机开始时间
     */
    @Excel(name = "ui.data.column.lhScheduleResult.shutdownStartTime")
    @ApiModelProperty(value = "设备停机开始时间", name = "shutdownStartTime")
    @TableField(value = "SHUTDOWN_START_TIME")
    private Date shutdownStartTime;

    /**
     * 设备停机结束时间
     */
    @Excel(name = "ui.data.column.lhScheduleResult.shutdownEndTime")
    @ApiModelProperty(value = "设备停机结束时间", name = "shutdownEndTime")
    @TableField(value = "SHUTDOWN_END_TIME")
    private Date shutdownEndTime;

    /**
     * 清洗开始时间
     */
    @Excel(name = "ui.data.column.lhScheduleResult.cleaningStartTime")
    @ApiModelProperty(value = "清洗开始时间", name = "cleaningStartTime")
    @TableField(value = "CLEANING_START_TIME")
    private Date cleaningStartTime;

    /**
     * 清洗结束时间
     */
    @Excel(name = "ui.data.column.lhScheduleResult.cleaningEndTime")
    @ApiModelProperty(value = "清洗结束时间", name = "cleaningEndTime")
    @TableField(value = "CLEANING_END_TIME")
    private Date cleaningEndTime;

    /**
     * 生产状态:0-未生产；1-生产中；2-生产完成
     */
    @Excel(name = "ui.data.column.lhScheduleResult.productionStatus")
    @ApiModelProperty(value = "生产状态:0-未生产；1-生产中；2-生产完成", name = "productionStatus")
    @TableField(value = "PRODUCTION_STATUS")
    private String productionStatus;

    /**
     * 1班计划量
     */
    @Excel(name = "ui.data.column.lhScheduleResult.class1PlanQty")
    @ApiModelProperty(value = "1班计划量", name = "class1PlanQty")
    @TableField(value = "CLASS1_PLAN_QTY")
    private Integer class1PlanQty;

    /**
     * 1班计划开始时间
     */
    @Excel(name = "ui.data.column.lhScheduleResult.class1StartTime")
    @ApiModelProperty(value = "1班计划开始时间", name = "class1StartTime")
    @TableField(value = "CLASS1_START_TIME")
    private Date class1StartTime;

    /**
     * 1班计划结束时间
     */
    @Excel(name = "ui.data.column.lhScheduleResult.class1EndTime")
    @ApiModelProperty(value = "1班计划结束时间", name = "class1EndTime")
    @TableField(value = "CLASS1_END_TIME")
    private Date class1EndTime;

    /**
     * 1班原因分析
     */
    @Excel(name = "ui.data.column.lhScheduleResult.class1Analysis")
    @ApiModelProperty(value = "1班原因分析", name = "class1Analysis")
    @TableField(value = "CLASS1_ANALYSIS")
    private String class1Analysis;

    /**
     * 1班完成量
     */
    @Excel(name = "ui.data.column.lhScheduleResult.class1FinishQty")
    @ApiModelProperty(value = "1班完成量", name = "class1FinishQty")
    @TableField(value = "CLASS1_FINISH_QTY")
    private Integer class1FinishQty;

    /**
     * 2班计划量
     */
    @Excel(name = "ui.data.column.lhScheduleResult.class2PlanQty")
    @ApiModelProperty(value = "2班计划量", name = "class2PlanQty")
    @TableField(value = "CLASS2_PLAN_QTY")
    private Integer class2PlanQty;

    /**
     * 2班计划开始时间
     */
    @Excel(name = "ui.data.column.lhScheduleResult.class2StartTime")
    @ApiModelProperty(value = "2班计划开始时间", name = "class2StartTime")
    @TableField(value = "CLASS2_START_TIME")
    private Date class2StartTime;

    /**
     * 2班计划结束时间
     */
    @Excel(name = "ui.data.column.lhScheduleResult.class2EndTime")
    @ApiModelProperty(value = "2班计划结束时间", name = "class2EndTime")
    @TableField(value = "CLASS2_END_TIME")
    private Date class2EndTime;

    /**
     * 2班原因分析
     */
    @Excel(name = "ui.data.column.lhScheduleResult.class2Analysis")
    @ApiModelProperty(value = "2班原因分析", name = "class2Analysis")
    @TableField(value = "CLASS2_ANALYSIS")
    private String class2Analysis;

    /**
     * 2班完成量
     */
    @Excel(name = "ui.data.column.lhScheduleResult.class2FinishQty")
    @ApiModelProperty(value = "2班完成量", name = "class2FinishQty")
    @TableField(value = "CLASS2_FINISH_QTY")
    private Integer class2FinishQty;

    /**
     * 3班计划量
     */
    @Excel(name = "ui.data.column.lhScheduleResult.class3PlanQty")
    @ApiModelProperty(value = "3班计划量", name = "class3PlanQty")
    @TableField(value = "CLASS3_PLAN_QTY")
    private Integer class3PlanQty;

    /**
     * 3班计划开始时间
     */
    @Excel(name = "ui.data.column.lhScheduleResult.class3StartTime")
    @ApiModelProperty(value = "3班计划开始时间", name = "class3StartTime")
    @TableField(value = "CLASS3_START_TIME")
    private Date class3StartTime;

    /**
     * 3班计划结束时间
     */
    @Excel(name = "ui.data.column.lhScheduleResult.class3EndTime")
    @ApiModelProperty(value = "3班计划结束时间", name = "class3EndTime")
    @TableField(value = "CLASS3_END_TIME")
    private Date class3EndTime;

    /**
     * 3班原因分析
     */
    @Excel(name = "ui.data.column.lhScheduleResult.class3Analysis")
    @ApiModelProperty(value = "3班原因分析", name = "class3Analysis")
    @TableField(value = "CLASS3_ANALYSIS")
    private String class3Analysis;

    /**
     * 3班完成量
     */
    @Excel(name = "ui.data.column.lhScheduleResult.class3FinishQty")
    @ApiModelProperty(value = "3班完成量", name = "class3FinishQty")
    @TableField(value = "CLASS3_FINISH_QTY")
    private Integer class3FinishQty;

    /**
     * 4班计划量
     */
    @Excel(name = "ui.data.column.lhScheduleResult.class4PlanQty")
    @ApiModelProperty(value = "4班计划量", name = "class4PlanQty")
    @TableField(value = "CLASS4_PLAN_QTY")
    private Integer class4PlanQty;

    /**
     * 4班计划开始时间
     */
    @Excel(name = "ui.data.column.lhScheduleResult.class4StartTime")
    @ApiModelProperty(value = "4班计划开始时间", name = "class4StartTime")
    @TableField(value = "CLASS4_START_TIME")
    private Date class4StartTime;

    /**
     * 4班计划结束时间
     */
    @Excel(name = "ui.data.column.lhScheduleResult.class4EndTime")
    @ApiModelProperty(value = "4班计划结束时间", name = "class4EndTime")
    @TableField(value = "CLASS4_END_TIME")
    private Date class4EndTime;

    /**
     * 4班原因分析
     */
    @Excel(name = "ui.data.column.lhScheduleResult.class4Analysis")
    @ApiModelProperty(value = "4班原因分析", name = "class4Analysis")
    @TableField(value = "CLASS4_ANALYSIS")
    private String class4Analysis;

    /**
     * 4班完成量
     */
    @Excel(name = "ui.data.column.lhScheduleResult.class4FinishQty")
    @ApiModelProperty(value = "4班完成量", name = "class4FinishQty")
    @TableField(value = "CLASS4_FINISH_QTY")
    private Integer class4FinishQty;

    /**
     * 5班计划量
     */
    @Excel(name = "ui.data.column.lhScheduleResult.class5PlanQty")
    @ApiModelProperty(value = "5班计划量", name = "class5PlanQty")
    @TableField(value = "CLASS5_PLAN_QTY")
    private Integer class5PlanQty;

    /**
     * 5班计划开始时间
     */
    @Excel(name = "ui.data.column.lhScheduleResult.class5StartTime")
    @ApiModelProperty(value = "5班计划开始时间", name = "class5StartTime")
    @TableField(value = "CLASS5_START_TIME")
    private Date class5StartTime;

    /**
     * 5班计划结束时间
     */
    @Excel(name = "ui.data.column.lhScheduleResult.class5EndTime")
    @ApiModelProperty(value = "5班计划结束时间", name = "class5EndTime")
    @TableField(value = "CLASS5_END_TIME")
    private Date class5EndTime;

    /**
     * 5班原因分析
     */
    @Excel(name = "ui.data.column.lhScheduleResult.class5Analysis")
    @ApiModelProperty(value = "5班原因分析", name = "class5Analysis")
    @TableField(value = "CLASS5_ANALYSIS")
    private String class5Analysis;

    /**
     * 5班完成量
     */
    @Excel(name = "ui.data.column.lhScheduleResult.class5FinishQty")
    @ApiModelProperty(value = "5班完成量", name = "class5FinishQty")
    @TableField(value = "CLASS5_FINISH_QTY")
    private Integer class5FinishQty;

    /**
     * 6班计划量
     */
    @Excel(name = "ui.data.column.lhScheduleResult.class6PlanQty")
    @ApiModelProperty(value = "6班计划量", name = "class6PlanQty")
    @TableField(value = "CLASS6_PLAN_QTY")
    private Integer class6PlanQty;

    /**
     * 6班计划开始时间
     */
    @Excel(name = "ui.data.column.lhScheduleResult.class6StartTime")
    @ApiModelProperty(value = "6班计划开始时间", name = "class6StartTime")
    @TableField(value = "CLASS6_START_TIME")
    private Date class6StartTime;

    /**
     * 6班计划结束时间
     */
    @Excel(name = "ui.data.column.lhScheduleResult.class6EndTime")
    @ApiModelProperty(value = "6班计划结束时间", name = "class6EndTime")
    @TableField(value = "CLASS6_END_TIME")
    private Date class6EndTime;

    /**
     * 6班原因分析
     */
    @Excel(name = "ui.data.column.lhScheduleResult.class6Analysis")
    @ApiModelProperty(value = "6班原因分析", name = "class6Analysis")
    @TableField(value = "CLASS6_ANALYSIS")
    private String class6Analysis;

    /**
     * 6班完成量
     */
    @Excel(name = "ui.data.column.lhScheduleResult.class6FinishQty")
    @ApiModelProperty(value = "6班完成量", name = "class6FinishQty")
    @TableField(value = "CLASS6_FINISH_QTY")
    private Integer class6FinishQty;

    /**
     * 7班计划量
     */
    @Excel(name = "ui.data.column.lhScheduleResult.class7PlanQty")
    @ApiModelProperty(value = "7班计划量", name = "class7PlanQty")
    @TableField(value = "CLASS7_PLAN_QTY")
    private Integer class7PlanQty;

    /**
     * 7班计划开始时间
     */
    @Excel(name = "ui.data.column.lhScheduleResult.class7StartTime")
    @ApiModelProperty(value = "7班计划开始时间", name = "class7StartTime")
    @TableField(value = "CLASS7_START_TIME")
    private Date class7StartTime;

    /**
     * 7班计划结束时间
     */
    @Excel(name = "ui.data.column.lhScheduleResult.class7EndTime")
    @ApiModelProperty(value = "7班计划结束时间", name = "class7EndTime")
    @TableField(value = "CLASS7_END_TIME")
    private Date class7EndTime;

    /**
     * 7班原因分析
     */
    @Excel(name = "ui.data.column.lhScheduleResult.class7Analysis")
    @ApiModelProperty(value = "7班原因分析", name = "class7Analysis")
    @TableField(value = "CLASS7_ANALYSIS")
    private String class7Analysis;

    /**
     * 7班完成量
     */
    @Excel(name = "ui.data.column.lhScheduleResult.class7FinishQty")
    @ApiModelProperty(value = "7班完成量", name = "class7FinishQty")
    @TableField(value = "CLASS7_FINISH_QTY")
    private Integer class7FinishQty;

    /**
     * 8班计划量
     */
    @Excel(name = "ui.data.column.lhScheduleResult.class8PlanQty")
    @ApiModelProperty(value = "8班计划量", name = "class8PlanQty")
    @TableField(value = "CLASS8_PLAN_QTY")
    private Integer class8PlanQty;

    /**
     * 8班计划开始时间
     */
    @Excel(name = "ui.data.column.lhScheduleResult.class8StartTime")
    @ApiModelProperty(value = "8班计划开始时间", name = "class8StartTime")
    @TableField(value = "CLASS8_START_TIME")
    private Date class8StartTime;

    /**
     * 8班计划结束时间
     */
    @Excel(name = "ui.data.column.lhScheduleResult.class8EndTime")
    @ApiModelProperty(value = "8班计划结束时间", name = "class8EndTime")
    @TableField(value = "CLASS8_END_TIME")
    private Date class8EndTime;

    /**
     * 8班原因分析
     */
    @Excel(name = "ui.data.column.lhScheduleResult.class8Analysis")
    @ApiModelProperty(value = "8班原因分析", name = "class8Analysis")
    @TableField(value = "CLASS8_ANALYSIS")
    private String class8Analysis;

    /**
     * 8班完成量
     */
    @Excel(name = "ui.data.column.lhScheduleResult.class8FinishQty")
    @ApiModelProperty(value = "8班完成量", name = "class8FinishQty")
    @TableField(value = "CLASS8_FINISH_QTY")
    private Integer class8FinishQty;

    /**
     * 是否交期，0--否，1--是
     */
    @Excel(name = "ui.data.column.lhScheduleResult.isDelivery")
    @ApiModelProperty(value = "是否交期，0--否，1--是", name = "isDelivery")
    @TableField(value = "IS_DELIVERY")
    private String isDelivery;

    /**
     * 是否发布，0-未发布，1-已发布 2-发布失败 3-超时发布 4-待发布
     */
    @Excel(name = "ui.data.column.lhScheduleResult.isRelease")
    @ApiModelProperty(value = "是否发布，0-未发布，1-已发布 2-发布失败 3-超时发布 4-待发布", name = "isRelease")
    @TableField(value = "IS_RELEASE")
    private String isRelease;

    /**
     * 发布成功计数器
     */
    @Excel(name = "ui.data.column.lhScheduleResult.publishSuccessCount")
    @ApiModelProperty(value = "发布成功计数器", name = "publishSuccessCount")
    @TableField(value = "PUBLISH_SUCCESS_COUNT")
    private BigDecimal publishSuccessCount;

    /**
     * 保留最新的一次发布成功时间
     */
    @Excel(name = "ui.data.column.lhScheduleResult.newestPublishTime")
    @ApiModelProperty(value = "最新发布成功时间", name = "newestPublishTime")
    @TableField(value = "NEWEST_PUBLISH_TIME")
    private Date newestPublishTime;

    /**
     * 数据来源：0 自动排程；1 插单；2 导入
     */
    @Excel(name = "ui.data.column.lhScheduleResult.dataSource")
    @ApiModelProperty(value = "数据来源：0 自动排程；1 插单；2 导入", name = "dataSource")
    @TableField(value = "DATA_SOURCE")
    private String dataSource;

    /**
     * 使用模数
     */
    @Excel(name = "ui.data.column.lhScheduleResult.mouldQty")
    @ApiModelProperty(value = "使用模数", name = "mouldQty")
    @TableField(value = "MOULD_QTY")
    private Integer mouldQty;

    /**
     * 单班硫化量
     */
    @Excel(name = "ui.data.column.lhScheduleResult.singleMouldShiftQty")
    @ApiModelProperty(value = "单班硫化量", name = "singleMouldShiftQty")
    @TableField(value = "SINGLE_MOULD_SHIFT_QTY")
    private Integer singleMouldShiftQty;

    /**
     * 模具信息 JSON字符串
     */
    @Excel(name = "ui.data.column.lhScheduleResult.mouldInfo")
    @ApiModelProperty(value = "模具信息 JSON字符串", name = "mouldInfo")
    @TableField(value = "MOULD_INFO")
    private String mouldInfo;

    /**
     * 硫化方式
     */
    @Excel(name = "ui.data.column.lhScheduleResult.mouldMethod")
    @ApiModelProperty(value = "硫化方式", name = "mouldMethod")
    @TableField(value = "MOULD_METHOD")
    private String mouldMethod;

    /**
     * 施工阶段 00 无工艺 01 试制 02 量试 03 正式
     */
    @Excel(name = "ui.data.column.lhScheduleResult.constructionStage", dictType = "biz_construction_stage")
    @ApiModelProperty(value = "施工阶段 00 无工艺 01 试制 02 量试 03 正式", name = "constructionStage")
    @TableField(value = "CONSTRUCTION_STAGE")
    private String constructionStage;

    /**
     * 制造示方书号
     */
    @Excel(name = "ui.data.column.lhScheduleResult.embryoNo")
    @ApiModelProperty(value = "制造示方书号", name = "embryoNo")
    @TableField(value = "EMBRYO_NO")
    private String embryoNo;

    /**
     * 文字示方书号
     */
    @Excel(name = "ui.data.column.lhScheduleResult.textNo")
    @ApiModelProperty(value = "文字示方书号", name = "textNo")
    @TableField(value = "TEXT_NO")
    private String textNo;

    /**
     * 硫化示方书号
     */
    @Excel(name = "ui.data.column.lhScheduleResult.lhNo")
    @ApiModelProperty(value = "硫化示方书号", name = "lhNo")
    @TableField(value = "LH_NO")
    private String lhNo;

    /**
     * 月计划需求版本
     */
    @Excel(name = "ui.data.column.lhScheduleResult.monthPlanVersion")
    @ApiModelProperty(value = "月计划需求版本", name = "monthPlanVersion")
    @TableField(value = "MONTH_PLAN_VERSION")
    private String monthPlanVersion;

    /**
     * 机台排序号
     */
    @Excel(name = "ui.data.column.lhScheduleResult.machineOrder")
    @ApiModelProperty(value = "机台排序号", name = "machineOrder")
    @TableField(value = "MACHINE_ORDER")
    private Integer machineOrder;

    /**
     * 是否试制量试
     */
    @Excel(name = "ui.data.column.lhScheduleResult.isTrial")
    @ApiModelProperty(value = "是否试制量试", name = "isTrial")
    @TableField(value = "IS_TRIAL")
    private String isTrial;

    /**
     * 实际排程日期
     */
    @Excel(name = "ui.data.column.lhScheduleResult.realScheduleDate")
    @ApiModelProperty(value = "实际排程日期", name = "realScheduleDate")
    @TableField(value = "REAL_SCHEDULE_DATE")
    private Date realScheduleDate;

    /**
     * T日规格结束时间
     */
    @Excel(name = "ui.data.column.lhScheduleResult.tdaySpecEndTime")
    @ApiModelProperty(value = "T日规格结束时间", name = "tdaySpecEndTime")
    @TableField(value = "TDAY_SPEC_END_TIME")
    private Date tdaySpecEndTime;

    /**
     * 是否首排
     */
    @Excel(name = "ui.data.column.lhScheduleResult.isFirst")
    @ApiModelProperty(value = "是否首排", name = "isFirst")
    @TableField(value = "IS_FIRST")
    private String isFirst;

    /**
     * 硫化余量
     */
    @Excel(name = "ui.data.column.lhScheduleResult.mouldSurplusQty")
    @ApiModelProperty(value = "硫化余量", name = "mouldSurplusQty")
    @TableField(value = "MOULD_SURPLUS_QTY")
    private Integer mouldSurplusQty;

    /**
     * 是否收尾
     */
    @Excel(name = "ui.data.column.lhScheduleResult.isEnd", dictType = "biz_yes_no")
    @ApiModelProperty(value = "是否收尾", name = "isEnd")
    @TableField(value = "IS_END")
    private String isEnd;

    /**
     * 月计划排产版本
     */
    @Excel(name = "ui.data.column.lhScheduleResult.productionVersion")
    @ApiModelProperty(value = "月计划排产版本", name = "productionVersion")
    @TableField(value = "PRODUCTION_VERSION")
    private String productionVersion;

    /**
     * 模具号 多个以逗号分隔
     */
    @Excel(name = "ui.data.column.lhScheduleResult.mouldCode")
    @ApiModelProperty(value = "模具号 多个以逗号分隔", name = "mouldCode")
    @TableField(value = "MOULD_CODE")
    private String mouldCode;

    /**
     * 是否含特殊材料
     */
    @Excel(name = "ui.data.column.lhScheduleResult.hasSpecialMaterial", dictType = "biz_yes_no")
    @ApiModelProperty(value = "是否含特殊材料", name = "hasSpecialMaterial")
    @TableField(value = "HAS_SPECIAL_MATERIAL")
    private String hasSpecialMaterial;

    /**
     * 是否拆分
     */
    @Excel(name = "ui.data.column.lhScheduleResult.isSplit")
    @ApiModelProperty(value = "是否拆分", name = "isSplit")
    @TableField(value = "IS_SPLIT")
    private String isSplit;

    /**
     * 排程顺序
     */
    @Excel(name = "ui.data.column.lhScheduleResult.scheduleOrder")
    @ApiModelProperty(value = "排程顺序", name = "scheduleOrder")
    @TableField(value = "SCHEDULE_ORDER")
    private String scheduleOrder;

    /**
     * 排程类型 01-续作 02-新增 03-换活字块
     */
    @Excel(name = "ui.data.column.lhScheduleResult.scheduleType")
    @ApiModelProperty(value = "排程类型 01-续作 02-新增 03-换活字块", name = "scheduleType")
    @TableField(value = "SCHEDULE_TYPE")
    private String scheduleType;

    /**
     * 是否换模
     */
    @Excel(name = "ui.data.column.lhScheduleResult.isChangeMould")
    @ApiModelProperty(value = "是否换模", name = "isChangeMould")
    @TableField(value = "IS_CHANGE_MOULD")
    private String isChangeMould;

    /**
     * 总计划数量
     */
    @Excel(name = "ui.data.column.lhScheduleResult.totalDailyPlanQty")
    @ApiModelProperty(value = "总计划数量", name = "totalDailyPlanQty")
    @TableField(value = "TOTAL_DAILY_PLAN_QTY")
    private Integer totalDailyPlanQty;


    /**
     * 是否换活字块
     */
    @Excel(name = "ui.data.column.lhScheduleResult.isTypeBlock")
    @ApiModelProperty(value = "是否换活字块", name = "isTypeBlock")
    @TableField(value = "IS_TYPE_BLOCK")
    private String isTypeBlock;


    /**
     * 产品状态（来自月计划）
     */
    @Excel(name = "ui.data.column.lhScheduleResult.productStatus", dictType = "trial_status")
    @ApiModelProperty(value = "产品状态", name = "productStatus")
    @TableField(value = "PRODUCT_STATUS")
    private String productStatus;

    /**
     * 产品状态
     */
    @ApiModelProperty(value = "产品状态 X 试验示方 T 量试示方 S 正规示方", name = "changedTrialStatus")
    @TableField(value = "CHANGED_TRIAL_STATUS")
    private String changedTrialStatus;

    /**
     * 1班硫化示方书号
     */
    @ApiModelProperty(value = "1班硫化示方书号", name = "class1LhNo")
    @TableField(value = "CLASS1_LH_NO")
    private String class1LhNo;

    /**
     * 1班硫化示方书类型
     */
    @ApiModelProperty(value = "1班硫化示方书类型", name = "class1LhType")
    @TableField(value = "CLASS1_LH_TYPE")
    private String class1LhType;

    /**
     * 2班硫化示方书号
     */
    @ApiModelProperty(value = "2班硫化示方书号", name = "class2LhNo")
    @TableField(value = "CLASS2_LH_NO")
    private String class2LhNo;

    /**
     * 2班硫化示方书类型
     */
    @ApiModelProperty(value = "2班硫化示方书类型", name = "class2LhType")
    @TableField(value = "CLASS2_LH_TYPE")
    private String class2LhType;

    /**
     * 3班硫化示方书号
     */
    @ApiModelProperty(value = "3班硫化示方书号", name = "class3LhNo")
    @TableField(value = "CLASS3_LH_NO")
    private String class3LhNo;

    /**
     * 3班硫化示方书类型
     */
    @ApiModelProperty(value = "3班硫化示方书类型", name = "class3LhType")
    @TableField(value = "CLASS3_LH_TYPE")
    private String class3LhType;

    /**
     * 4班硫化示方书号
     */
    @ApiModelProperty(value = "4班硫化示方书号", name = "class4LhNo")
    @TableField(value = "CLASS4_LH_NO")
    private String class4LhNo;

    /**
     * 4班硫化示方书类型
     */
    @ApiModelProperty(value = "4班硫化示方书类型", name = "class4LhType")
    @TableField(value = "CLASS4_LH_TYPE")
    private String class4LhType;

    /**
     * 5班硫化示方书号
     */
    @ApiModelProperty(value = "5班硫化示方书号", name = "class5LhNo")
    @TableField(value = "CLASS5_LH_NO")
    private String class5LhNo;

    /**
     * 5班硫化示方书类型
     */
    @ApiModelProperty(value = "5班硫化示方书类型", name = "class5LhType")
    @TableField(value = "CLASS5_LH_TYPE")
    private String class5LhType;

    /**
     * 6班硫化示方书号
     */
    @ApiModelProperty(value = "6班硫化示方书号", name = "class6LhNo")
    @TableField(value = "CLASS6_LH_NO")
    private String class6LhNo;

    /**
     * 6班硫化示方书类型
     */
    @ApiModelProperty(value = "6班硫化示方书类型", name = "class6LhType")
    @TableField(value = "CLASS6_LH_TYPE")
    private String class6LhType;

    /**
     * 7班硫化示方书号
     */
    @ApiModelProperty(value = "7班硫化示方书号", name = "class7LhNo")
    @TableField(value = "CLASS7_LH_NO")
    private String class7LhNo;

    /**
     * 7班硫化示方书类型
     */
    @ApiModelProperty(value = "7班硫化示方书类型", name = "class7LhType")
    @TableField(value = "CLASS7_LH_TYPE")
    private String class7LhType;

    /**
     * 8班硫化示方书号
     */
    @ApiModelProperty(value = "8班硫化示方书号", name = "class8LhNo")
    @TableField(value = "CLASS8_LH_NO")
    private String class8LhNo;

    /**
     * 8班硫化示方书类型
     */
    @ApiModelProperty(value = "8班硫化示方书类型", name = "class8LhType")
    @TableField(value = "CLASS8_LH_TYPE")
    private String class8LhType;

    /**
     * 删除标识（0未删除；1已删除）
     */
    @Excel(name = "ui.data.column.lhScheduleResult.isDelete")
    @ApiModelProperty(value = "删除标识（0未删除；1已删除）", name = "isDelete")
    @TableField(value = "IS_DELETE")
    private Integer isDelete;

    /**
     * 真实换模开始时间，用于本次排程运行期生成换模计划
     */
    @Excel(name = "ui.data.column.lhScheduleResult.mouldChangeStartTime")
    @ApiModelProperty(value = "换模开始时间", name = "mouldChangeStartTime")
    @TableField(value = "MOULD_CHANGE_START_TIME")
    private Date mouldChangeStartTime;

    /**
     * 是否为滚动排程继承结果，仅用于本次排程运行期识别，不落库。
     */
    @TableField(exist = false)
    private boolean rollingInherited;
}
