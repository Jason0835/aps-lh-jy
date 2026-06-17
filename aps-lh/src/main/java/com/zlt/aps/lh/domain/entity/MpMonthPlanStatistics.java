package com.zlt.aps.lh.domain.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.ruoyi.common.core.web.domain.BaseEntity;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

/**
 * 月计划结构统计表。
 *
 * <p>用于硫化提前生产规则读取结构维度 dayN.lhMachines 计划机台数。</p>
 */
@Data
@TableName(value = "T_MP_MONTH_PLAN_STATISTICS")
@ApiModel(value = "月计划结构统计对象", description = "月计划结构统计对象")
public class MpMonthPlanStatistics extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** 工厂编号 */
    @ApiModelProperty(value = "工厂编号", name = "factoryCode")
    @TableField(value = "FACTORY_CODE")
    private String factoryCode;

    /** 年份 */
    @ApiModelProperty(value = "年份", name = "year")
    @TableField(value = "YEAR")
    private Integer year;

    /** 月份 */
    @ApiModelProperty(value = "月份", name = "month")
    @TableField(value = "MONTH")
    private Integer month;

    /** 排产版本 */
    @ApiModelProperty(value = "排产版本", name = "productionVersion")
    @TableField(value = "PRODUCTION_VERSION")
    private String productionVersion;

    /** 产品结构 */
    @ApiModelProperty(value = "产品结构", name = "structureName")
    @TableField(value = "STRUCTURE_NAME")
    private String structureName;

    /** 临时标识 */
    @ApiModelProperty(value = "临时标识", name = "tempFlag")
    @TableField(value = "TEMP_FLAG")
    private String tempFlag;

    @TableField(value = "DAY_1")
    private String day1;
    @TableField(value = "DAY_2")
    private String day2;
    @TableField(value = "DAY_3")
    private String day3;
    @TableField(value = "DAY_4")
    private String day4;
    @TableField(value = "DAY_5")
    private String day5;
    @TableField(value = "DAY_6")
    private String day6;
    @TableField(value = "DAY_7")
    private String day7;
    @TableField(value = "DAY_8")
    private String day8;
    @TableField(value = "DAY_9")
    private String day9;
    @TableField(value = "DAY_10")
    private String day10;
    @TableField(value = "DAY_11")
    private String day11;
    @TableField(value = "DAY_12")
    private String day12;
    @TableField(value = "DAY_13")
    private String day13;
    @TableField(value = "DAY_14")
    private String day14;
    @TableField(value = "DAY_15")
    private String day15;
    @TableField(value = "DAY_16")
    private String day16;
    @TableField(value = "DAY_17")
    private String day17;
    @TableField(value = "DAY_18")
    private String day18;
    @TableField(value = "DAY_19")
    private String day19;
    @TableField(value = "DAY_20")
    private String day20;
    @TableField(value = "DAY_21")
    private String day21;
    @TableField(value = "DAY_22")
    private String day22;
    @TableField(value = "DAY_23")
    private String day23;
    @TableField(value = "DAY_24")
    private String day24;
    @TableField(value = "DAY_25")
    private String day25;
    @TableField(value = "DAY_26")
    private String day26;
    @TableField(value = "DAY_27")
    private String day27;
    @TableField(value = "DAY_28")
    private String day28;
    @TableField(value = "DAY_29")
    private String day29;
    @TableField(value = "DAY_30")
    private String day30;
    @TableField(value = "DAY_31")
    private String day31;
}
