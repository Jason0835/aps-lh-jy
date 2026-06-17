package com.zlt.aps.mp.api.domain.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.ruoyi.common.core.annotation.Excel;
import com.ruoyi.common.core.web.domain.BaseEntity;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;


@ApiModel(value = "最终排产计划统计对象", description = "最终排产计划统计对象 ")
@Data
@TableName(value = "T_MP_MONTH_PLAN_STATISTICS")
public class MpMonthPlanStatistics extends BaseEntity {

    private static final long serialVersionUID = 1L;

     /** 工厂编号 */
    @Excel(name = "ui.data.column.mpMonthPlanStatistics.factoryCode")
    @ApiModelProperty(value = "工厂编号", name = "factoryCode")
    @TableField(value = "FACTORY_CODE")
    private String factoryCode;

    /** 年份 */
    @Excel(name = "ui.data.column.mpMonthPlanStatistics.year")
    @ApiModelProperty(value = "年份", name = "year")
    @TableField(value = "YEAR")
    private Integer year;

    /** 月份 */
    @Excel(name = "ui.data.column.mpMonthPlanStatistics.month")
    @ApiModelProperty(value = "月份", name = "month")
    @TableField(value = "MONTH")
    private Integer month;

    /** 年月:YYYYMM */
    @Excel(name = "ui.data.column.mpMonthPlanStatistics.yearMonth")
    @ApiModelProperty(value = "年月:YYYYMM", name = "yearMonth")
    @TableField(value = "`YEAR_MONTH`")
    private Integer yearMonth;

    /** 需求计划版本 */
    @Excel(name = "ui.data.column.mpMonthPlanStatistics.monthPlanVersion")
    @ApiModelProperty(value = "需求计划版本", name = "monthPlanVersion")
    @TableField(value = "MONTH_PLAN_VERSION")
    private String monthPlanVersion;

    /** 最新需求计划版本(每次调整后变化) */
    @Excel(name = "ui.data.column.mpMonthPlanStatistics.lastMonthPlanVersion")
    @ApiModelProperty(value = "最新需求计划版本(每次调整后变化)", name = "lastMonthPlanVersion")
    @TableField(value = "LAST_MONTH_PLAN_VERSION")
    private String lastMonthPlanVersion;

    /** 排产版本号 */
    @Excel(name = "ui.data.column.mpMonthPlanStatistics.productionVersion")
    @ApiModelProperty(value = "排产版本号", name = "productionVersion")
    @TableField(value = "PRODUCTION_VERSION")
    private String productionVersion;

    /** 产品品类 数据字典：biz_product_type  全钢 PCR 半钢 */
    @Excel(name = "ui.data.column.mpMonthPlanStatistics.productTypeCode")
    @ApiModelProperty(value = "产品品类 数据字典：biz_product_type  全钢 PCR 半钢", name = "productTypeCode")
    @TableField(value = "PRODUCT_TYPE_CODE")
    private String productTypeCode;

    /** 产品结构 */
    @Excel(name = "ui.data.column.mpMonthPlanStatistics.structureName")
    @ApiModelProperty(value = "产品结构", name = "structureName")
    @TableField(value = "STRUCTURE_NAME")
    private String structureName;

    /** 英寸 */
    @Excel(name = "ui.data.column.mpMonthPlanStatistics.proSize")
    @ApiModelProperty(value = "英寸", name = "proSize")
    @TableField(value = "PRO_SIZE")
    private String proSize;

    /** 自动调整  tempFlag = 1， 确认调整 tempFlag = 0，默认0*/
    @ApiModelProperty(value = "临时标识", name = "tempFlag")
    @TableField(value = "TEMP_FLAG")
    private String tempFlag;

    /** 结构类型 01 周期结构 02 常规结构 */
    @Excel(name = "ui.data.column.mpMonthPlanStatistics.structureType")
    @ApiModelProperty(value = "结构类型 01 周期结构 02 常规结构", name = "structureType")
    @TableField(value = "STRUCTURE_TYPE")
    private String structureType;

    /** DAY_1(EmbryoCount:X,LhMachines:X,ChangeMould:X) */
    @Excel(name = "ui.data.column.mpMonthPlanStatistics.day1")
    @ApiModelProperty(value = "DAY_1(EmbryoCount:X,LhMachines:X,ChangeMould:X)", name = "day1")
    @TableField(value = "DAY_1")
    private String day1;

    /** DAY_2 */
    @Excel(name = "ui.data.column.mpMonthPlanStatistics.day2")
    @ApiModelProperty(value = "DAY_2", name = "day2")
    @TableField(value = "DAY_2")
    private String day2;

    /** DAY_3 */
    @Excel(name = "ui.data.column.mpMonthPlanStatistics.day3")
    @ApiModelProperty(value = "DAY_3", name = "day3")
    @TableField(value = "DAY_3")
    private String day3;

    /** DAY_4 */
    @Excel(name = "ui.data.column.mpMonthPlanStatistics.day4")
    @ApiModelProperty(value = "DAY_4", name = "day4")
    @TableField(value = "DAY_4")
    private String day4;

    /** DAY_5 */
    @Excel(name = "ui.data.column.mpMonthPlanStatistics.day5")
    @ApiModelProperty(value = "DAY_5", name = "day5")
    @TableField(value = "DAY_5")
    private String day5;

    /** DAY_6 */
    @Excel(name = "ui.data.column.mpMonthPlanStatistics.day6")
    @ApiModelProperty(value = "DAY_6", name = "day6")
    @TableField(value = "DAY_6")
    private String day6;

    /** DAY_7 */
    @Excel(name = "ui.data.column.mpMonthPlanStatistics.day7")
    @ApiModelProperty(value = "DAY_7", name = "day7")
    @TableField(value = "DAY_7")
    private String day7;

    /** DAY_8 */
    @Excel(name = "ui.data.column.mpMonthPlanStatistics.day8")
    @ApiModelProperty(value = "DAY_8", name = "day8")
    @TableField(value = "DAY_8")
    private String day8;

    /** DAY_9 */
    @Excel(name = "ui.data.column.mpMonthPlanStatistics.day9")
    @ApiModelProperty(value = "DAY_9", name = "day9")
    @TableField(value = "DAY_9")
    private String day9;

    /** DAY_10 */
    @Excel(name = "ui.data.column.mpMonthPlanStatistics.day10")
    @ApiModelProperty(value = "DAY_10", name = "day10")
    @TableField(value = "DAY_10")
    private String day10;

    /** DAY_11 */
    @Excel(name = "ui.data.column.mpMonthPlanStatistics.day11")
    @ApiModelProperty(value = "DAY_11", name = "day11")
    @TableField(value = "DAY_11")
    private String day11;

    /** DAY_12 */
    @Excel(name = "ui.data.column.mpMonthPlanStatistics.day12")
    @ApiModelProperty(value = "DAY_12", name = "day12")
    @TableField(value = "DAY_12")
    private String day12;

    /** DAY_13 */
    @Excel(name = "ui.data.column.mpMonthPlanStatistics.day13")
    @ApiModelProperty(value = "DAY_13", name = "day13")
    @TableField(value = "DAY_13")
    private String day13;

    /** DAY_14 */
    @Excel(name = "ui.data.column.mpMonthPlanStatistics.day14")
    @ApiModelProperty(value = "DAY_14", name = "day14")
    @TableField(value = "DAY_14")
    private String day14;

    /** DAY_15 */
    @Excel(name = "ui.data.column.mpMonthPlanStatistics.day15")
    @ApiModelProperty(value = "DAY_15", name = "day15")
    @TableField(value = "DAY_15")
    private String day15;

    /** DAY_16 */
    @Excel(name = "ui.data.column.mpMonthPlanStatistics.day16")
    @ApiModelProperty(value = "DAY_16", name = "day16")
    @TableField(value = "DAY_16")
    private String day16;

    /** DAY_17 */
    @Excel(name = "ui.data.column.mpMonthPlanStatistics.day17")
    @ApiModelProperty(value = "DAY_17", name = "day17")
    @TableField(value = "DAY_17")
    private String day17;

    /** DAY_18 */
    @Excel(name = "ui.data.column.mpMonthPlanStatistics.day18")
    @ApiModelProperty(value = "DAY_18", name = "day18")
    @TableField(value = "DAY_18")
    private String day18;

    /** DAY_19 */
    @Excel(name = "ui.data.column.mpMonthPlanStatistics.day19")
    @ApiModelProperty(value = "DAY_19", name = "day19")
    @TableField(value = "DAY_19")
    private String day19;

    /** DAY_20 */
    @Excel(name = "ui.data.column.mpMonthPlanStatistics.day20")
    @ApiModelProperty(value = "DAY_20", name = "day20")
    @TableField(value = "DAY_20")
    private String day20;

    /** DAY_21 */
    @Excel(name = "ui.data.column.mpMonthPlanStatistics.day21")
    @ApiModelProperty(value = "DAY_21", name = "day21")
    @TableField(value = "DAY_21")
    private String day21;

    /** DAY_22 */
    @Excel(name = "ui.data.column.mpMonthPlanStatistics.day22")
    @ApiModelProperty(value = "DAY_22", name = "day22")
    @TableField(value = "DAY_22")
    private String day22;

    /** DAY_23 */
    @Excel(name = "ui.data.column.mpMonthPlanStatistics.day23")
    @ApiModelProperty(value = "DAY_23", name = "day23")
    @TableField(value = "DAY_23")
    private String day23;

    /** DAY_24 */
    @Excel(name = "ui.data.column.mpMonthPlanStatistics.day24")
    @ApiModelProperty(value = "DAY_24", name = "day24")
    @TableField(value = "DAY_24")
    private String day24;

    /** DAY_25 */
    @Excel(name = "ui.data.column.mpMonthPlanStatistics.day25")
    @ApiModelProperty(value = "DAY_25", name = "day25")
    @TableField(value = "DAY_25")
    private String day25;

    /** DAY_26 */
    @Excel(name = "ui.data.column.mpMonthPlanStatistics.day26")
    @ApiModelProperty(value = "DAY_26", name = "day26")
    @TableField(value = "DAY_26")
    private String day26;

    /** DAY_27 */
    @Excel(name = "ui.data.column.mpMonthPlanStatistics.day27")
    @ApiModelProperty(value = "DAY_27", name = "day27")
    @TableField(value = "DAY_27")
    private String day27;

    /** DAY_28 */
    @Excel(name = "ui.data.column.mpMonthPlanStatistics.day28")
    @ApiModelProperty(value = "DAY_28", name = "day28")
    @TableField(value = "DAY_28")
    private String day28;

    /** DAY_29 */
    @Excel(name = "ui.data.column.mpMonthPlanStatistics.day29")
    @ApiModelProperty(value = "DAY_29", name = "day29")
    @TableField(value = "DAY_29")
    private String day29;

    /** DAY_30 */
    @Excel(name = "ui.data.column.mpMonthPlanStatistics.day30")
    @ApiModelProperty(value = "DAY_30", name = "day30")
    @TableField(value = "DAY_30")
    private String day30;

    /** DAY_31 */
    @Excel(name = "ui.data.column.mpMonthPlanStatistics.day31")
    @ApiModelProperty(value = "DAY_31", name = "day31")
    @TableField(value = "DAY_31")
    private String day31;
    /**
     * 是否定稿调整使用 0 不是 1 是
     */
    @ApiModelProperty(value = "是否定稿调整使用", name = "isAdjust")
    @TableField(exist = false)
    private Integer isFinalAdjust;

}