package com.zlt.aps.mp.api.domain.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.ruoyi.common.core.annotation.Excel;
import com.ruoyi.common.core.web.domain.BaseEntity;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.math.BigDecimal;

@Data
@TableName(value = "T_MP_MONTH_PLAN_PROD_FINAL")
@ApiModel(value = "工厂月生产计划-最终排产计划定稿对象", description = "工厂月生产计划-最终排产计划定稿对象")
public class FactoryMonthPlanProductionFinalResult extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /**
     * 工单号(MP两位年两位月两位日两位批次号5位流水)
     */
    @Excel(name = "ui.data.column.FactoryMonthPlanFinalResult.productionNo")
    @ApiModelProperty(value = "工单号(MP两位年两位月两位日两位批次号5位流水)", name = "productionNo")
    @TableField(value = "PRODUCTION_NO")
    private String productionNo;

    /**
     * 工厂编码
     */
    @Excel(name = "ui.data.column.FactoryMonthPlanFinalResult.factoryCode", dictType = "biz_factory_name")
    @ApiModelProperty(value = "工厂编码", name = "factoryCode")
    @TableField(value = "FACTORY_CODE")
    private String factoryCode;

    /**
     * 年份
     */
    @Excel(name = "ui.data.column.FactoryMonthPlanFinalResult.year")
    @ApiModelProperty(value = "年份", name = "year")
    @TableField(value = "YEAR")
    private Integer year;

    /**
     * 月份
     */
    @Excel(name = "ui.data.column.FactoryMonthPlanFinalResult.month")
    @ApiModelProperty(value = "月份", name = "month")
    @TableField(value = "MONTH")
    private Integer month;

    /**
     * 年月:YYYYMM
     */
    @ApiModelProperty(value = "年月:YYYYMM", name = "yearMonth")
    @TableField(value = "`YEAR_MONTH`")
    private Integer yearMonth;

    /**
     * 销售生产需求计划版本
     */
    @Excel(name = "ui.data.column.FactoryMonthPlanFinalResult.monthPlanVersion")
    @ApiModelProperty(value = "需求计划版本", name = "monthPlanVersion")
    @TableField(value = "MONTH_PLAN_VERSION")
    private String monthPlanVersion;

    /**
     * 最新需求计划版本
     */
    @Excel(name = "ui.data.column.FactoryMonthPlanFinalResult.lastMonthPlanVersion")
    @ApiModelProperty(value = "最新需求计划版本", name = "lastMonthPlanVersion")
    @TableField(value = "LAST_MONTH_PLAN_VERSION")
    private String lastMonthPlanVersion;

    /**
     * 上月超欠产是否有效，1-有效，其他-无效
     */
    @ApiModelProperty(value = "上月超欠产是否有效，1-有效，其他-无效", name = "lastMonthValidFlag")
    @TableField(value = "LAST_MONTH_VALID_FLAG")
    private String lastMonthValidFlag;

    /**
     * 上月超欠产数量，仅在上月超欠产有效时参与硫化余量计算
     */
    @ApiModelProperty(value = "上月超欠产数量", name = "lastMonthOverdueQty")
    @TableField(value = "LAST_MONTH_OVERDUE_QTY")
    private Integer lastMonthOverdueQty;

    /**
     * 排产计划版本
     */
    @Excel(name = "ui.data.column.FactoryMonthPlanFinalResult.productionVersion")
    @ApiModelProperty(value = "月度生产计划版本", name = "productionVersion")
    @TableField(value = "PRODUCTION_VERSION")
    private String productionVersion;

    /**
     * 产品品类 数据字典：biz_product_type TBR 全钢 PCR 半钢
     */
    @Excel(name = "ui.data.column.FactoryMonthPlanFinalResult.productTypeCode", dictType = "biz_product_type")
    @ApiModelProperty(value = "产品品类 数据字典：biz_product_type TBR 全钢 PCR 半钢", name = "productTypeCode")
    @TableField(value = "PRODUCT_TYPE_CODE")
    private String productTypeCode;

    /**
     * 物料编码
     */
    @Excel(name = "ui.data.column.FactoryMonthPlanFinalResult.materialCode")
    @ApiModelProperty(value = "物料编码", name = "materialCode")
    @TableField(value = "MATERIAL_CODE")
    private String materialCode;

    /**
     * 物料描述
     */
    @Excel(name = "ui.data.column.FactoryMonthPlanFinalResult.materialDesc")
    @ApiModelProperty(value = "物料描述", name = "materialDesc")
    @TableField(value = "MATERIAL_DESC")
    private String materialDesc;

    /**
     * MES物料编码
     */
    @Excel(name = "ui.data.column.FactoryMonthPlanFinalResult.mesMaterialCode")
    @ApiModelProperty(value = "MES物料编码", name = "mesMaterialCode")
    @TableField(value = "MES_MATERIAL_CODE")
    private String mesMaterialCode;

    /**
     * 产品结构
     */
    @Excel(name = "ui.data.column.FactoryMonthPlanFinalResult.structureName")
    @ApiModelProperty(value = "产品结构", name = "structureName")
    @TableField(value = "STRUCTURE_NAME")
    private String structureName;

    /**
     * 英寸
     */
    @Excel(name = "ui.data.column.FactoryMonthPlanFinalResult.proSize")
    @ApiModelProperty(value = "英寸", name = "proSize")
    @TableField(value = "PRO_SIZE")
    private String proSize;

    /**
     * 产品分类
     */
    @Excel(name = "ui.data.column.FactoryMonthPlanFinalResult.productCategory", dictType = "product_category")
    @ApiModelProperty(value = "产品分类", name = "productCategory")
    @TableField(value = "PRODUCT_CATEGORY")
    private String productCategory;
    /**
     * 产品状态
     */
    @Excel(name = "ui.data.column.FactoryMonthPlanFinalResult.productStatus", dictType = "trial_status")
    @ApiModelProperty(value = "产品状态", name = "productStatus")
    @TableField(value = "PRODUCT_STATUS")
    private String productStatus;

    /**
     * 结构类型 01 周期结构 02 常规结构
     */
    @ApiModelProperty(value = "结构类型", name = "structureType")
    @TableField(value = "STRUCTURE_TYPE")
    private String structureType;
    /**
     * 排产分类
     */
    @Excel(name = "ui.data.column.FactoryMonthPlanFinalResult.productionType", dictType = "biz_schedule_type")
    @ApiModelProperty(value = "排产分类", name = "productionType")
    @TableField(value = "PRODUCTION_TYPE")
    private String productionType;

    /**
     * 生胎代码
     */
    @Excel(name = "ui.data.column.FactoryMonthPlanFinalResult.embryoCode")
    @ApiModelProperty(value = "生胎代码", name = "embryoCode")
    @TableField(value = "EMBRYO_CODE")
    private String embryoCode;

    /**
     * 主物料(胎胚号)
     */
    @Excel(name = "ui.data.column.FactoryMonthPlanFinalResult.mainMaterialDesc")
    @ApiModelProperty(value = "主物料(胎胚号)", name = "mainMaterialDesc")
    @TableField(value = "MAIN_MATERIAL_DESC")
    private String mainMaterialDesc;


    /**
     * 施工阶段 00 无工艺 01 试制 02 量试 03 正式
     */
    @Excel(name = "ui.data.column.FactoryMonthPlanFinalResult.schedulingType", dictType = "biz_construction_stage")
    @ApiModelProperty(value = "施工阶段 00 无工艺 01 试制 02 量试 03 正式", name = "constructionStage")
    @TableField(value = "CONSTRUCTION_STAGE")
    private String constructionStage;
    /**
     * 是否零度材料
     */
    @Excel(name = "ui.data.column.FactoryMonthPlanFinalResult.isZeroRack", dictType = "biz_yes_no")
    @ApiModelProperty(value = "是否零度材料", name = "isZeroRack")
    @TableField(value = "IS_ZERO_RACK")
    private String isZeroRack;

    /**
     * 制造示方书号
     */
    @Excel(name = "ui.data.column.FactoryMonthPlanFinalResult.embryoNo")
    @ApiModelProperty(value = "制造示方书号", name = "embryoNo")
    @TableField(value = "EMBRYO_NO")
    private String embryoNo;

    /**
     * 文字示方书号
     */
    @Excel(name = "ui.data.column.FactoryMonthPlanFinalResult.textNo")
    @ApiModelProperty(value = "文字示方书号", name = "textNo")
    @TableField(value = "TEXT_NO")
    private String textNo;

    /**
     * 硫化示方书号
     */
    @Excel(name = "ui.data.column.FactoryMonthPlanFinalResult.lhNo")
    @ApiModelProperty(value = "硫化示方书号", name = "lhNo")
    @TableField(value = "LH_NO")
    private String lhNo;

    /**
     * 品牌
     */
    @Excel(name = "ui.data.column.FactoryMonthPlanFinalResult.brand", dictType = "biz_brand_type")
    @ApiModelProperty(value = "品牌", name = "brand")
    @TableField(value = "BRAND")
    private String brand;

    /**
     * 规格
     */
    @Excel(name = "ui.data.column.FactoryMonthPlanFinalResult.specifications")
    @ApiModelProperty(value = "规格", name = "specifications")
    @TableField(value = "SPECIFICATIONS")
    private String specifications;

    /**
     * 主花纹
     */
    @Excel(name = "ui.data.column.FactoryMonthPlanFinalResult.mainPattern")
    @ApiModelProperty(value = "主花纹", name = "mainPattern")
    @TableField(value = "MAIN_PATTERN")
    private String mainPattern;

    /**
     * 花纹
     */
    @Excel(name = "ui.data.column.FactoryMonthPlanFinalResult.pattern")
    @ApiModelProperty(value = "花纹", name = "pattern")
    @TableField(value = "PATTERN")
    private String pattern;

    /**
     * 型腔数量(同主花纹的模具数量)
     */
    @Excel(name = "ui.data.column.FactoryMonthPlanFinalResult.mouldCavityQty")
    @ApiModelProperty(value = "型腔数量(同主花纹的模具数量)", name = "mouldCavityQty")
    @TableField(value = "MOULD_CAVITY_QTY")
    private Integer mouldCavityQty;

    /**
     * 活块数量(同主花纹的物料模具数量)
     */
    @Excel(name = "ui.data.column.FactoryMonthPlanFinalResult.typeBlockQty")
    @ApiModelProperty(value = "活块数量(同主花纹的物料模具数量)", name = "typeBlockQty")
    @TableField(value = "TYPE_BLOCK_QTY")
    private Integer typeBlockQty;

    /**
     * 高优先级数量
     */
    @Excel(name = "ui.data.column.FactoryMonthPlanFinalResult.heightQty")
    @ApiModelProperty(value = "高优先级数量", name = "heightQty")
    @TableField(value = "HEIGHT_QTY")
    private Integer heightQty;

    /**
     * 月均销量
     */
//    @Excel(name = "ui.data.column.FactoryMonthPlanFinalResult.averageSaleQty")
    @ApiModelProperty(value = "月均销量", name = "averageSaleQty")
    @TableField(value = "AVERAGE_SALE_QTY")
    private Integer averageSaleQty;

    /**
     * 库销比
     */
//    @Excel(name = "ui.data.column.FactoryMonthPlanFinalResult.inventorySalesRatio")
    @ApiModelProperty(value = "库销比", name = "inventorySalesRatio")
    @TableField(value = "INVENTORY_SALES_RATIO")
    private BigDecimal inventorySalesRatio;



    /**
     * 日硫化量
     */
    @Excel(name = "ui.data.column.FactoryMonthPlanFinalResult.dayVulcanizationQty")
    @ApiModelProperty(value = "日硫化量", name = "dayVulcanizationQty")
    @TableField(value = "DAY_VULCANIZATION_QTY")
    private Integer dayVulcanizationQty;

    /**
     * 成型机台信息 多个以，分隔
     */
    @Excel(name = "ui.data.column.FactoryMonthPlanFinalResult.cxMachineCode")
    @ApiModelProperty(value = "成型机台信息", name = "cxMachineCode")
    @TableField(value = "CX_MACHINE_CODE")
    private String cxMachineCode;

    /**
     * 模具使用变化信息如2-4-2,或是2-4或是2
     */
    @Excel(name = "ui.data.column.FactoryMonthPlanFinalResult.mouldChangeInfo")
    @ApiModelProperty(value = "模具使用变化信息", name = "mouldChangeInfo")
    @TableField(value = "MOULD_CHANGE_INFO")
    private String mouldChangeInfo;

    /**
     * 动平衡数量
     */
    @ApiModelProperty(value = "动平衡数量", name = "dynamicBalanceQty")
    @TableField(value = "DYNAMIC_BALANCE_QTY")
    private String dynamicBalanceQty;

    /**
     * 均匀性数量
     */
    @ApiModelProperty(value = "均匀性数量", name = "uniformityQty")
    @TableField(value = "UNIFORMITY_QTY")
    private Integer uniformityQty;

    /**
     * 是否EXCEL导入（0：默认不是，1：是）
     */
//    @Excel(name = "ui.data.column.FactoryMonthPlanFinalResult.isImport", dictType = "biz_yes_no")
    @ApiModelProperty(value = "是否EXCEL导入", name = "isImport")
    @TableField(value = "IS_IMPORT")
    private String isImport;

    /**
     * 排产顺序
     */
//    @Excel(name = "ui.data.column.FactoryMonthPlanFinalResult.productionSequence")
    @ApiModelProperty(value = "排产顺序", name = "productionSequence")
    @TableField(value = "PRODUCTION_SEQUENCE")
    private Long productionSequence;

    /**
     * 单条硫化时间(包含增加间隔)-调整时使用
     */
    @Excel(name = "ui.data.column.FactoryMonthPlanFinalResult.curingTime")
    @ApiModelProperty(value = "单条硫化时间(包含增加间隔)-调整时使用", name = "curingTime")
    @TableField(value = "CURING_TIME")
    private Integer curingTime;

    /**
     * 生产需求计划
     */
    @Excel(name = "ui.data.column.FactoryMonthPlanFinalResult.prodReqPlan")
    @ApiModelProperty(value = "净需求", name = "prodReqPlan")
    @TableField(value = "PROD_REQ_PLAN")
    private Integer prodReqPlan;

    /**
     * 试制量试计划需求量
     */
    @Excel(name = "ui.data.column.FactoryMonthPlanFinalResult.trialQty")
    @ApiModelProperty(value = "试制量试计划需求量", name = "trialQty")
    @TableField(value = "TRIAL_QTY")
    private Integer trialQty;

    /**
     * 高优先级排产数量
     */
    @Excel(name = "ui.data.column.FactoryMonthPlanFinalResult.heightProductionQty")
    @ApiModelProperty(value = "高优先级排产数量", name = "heightProductionQty")
    @TableField(value = "HEIGHT_PRODUCTION_QTY")
    private Integer heightProductionQty;

    /**
     * 实际生产需求(含损耗)
     */
    @Excel(name = "ui.data.column.FactoryMonthPlanFinalResult.factProdReqQty")
    @ApiModelProperty(value = "实际生产需求(含损耗)", name = "factProdReqQty")
    @TableField(value = "FACT_PROD_REQ_QTY")
    private Integer factProdReqQty;

    /**
     * 生产实际排产量
     */
    @Excel(name = "ui.data.column.FactoryMonthPlanFinalResult.totalQty")
    @ApiModelProperty(value = "生产实际排产量", name = "totalQty")
    @TableField(value = "TOTAL_QTY")
    private Integer totalQty;

    /**
     * 中优先级排产数量
     */
    @Excel(name = "ui.data.column.FactoryMonthPlanFinalResult.midProductionQty")
    @ApiModelProperty(value = "中优先级排产数量", name = "midProductionQty")
    @TableField(value = "MID_PRODUCTION_QTY")
    private Integer midProductionQty;

    /**
     * 周期排产储备排产数量
     */
    @Excel(name = "ui.data.column.FactoryMonthPlanFinalResult.cycleProductionQty")
    @ApiModelProperty(value = "周期排产储备排产数量", name = "cycleProductionQty")
    @TableField(value = "CYCLE_PRODUCTION_QTY")
    private Integer cycleProductionQty;

    /**
     * 常规储备排产数量
     */
    @Excel(name = "ui.data.column.FactoryMonthPlanFinalResult.conventionProductionQty")
    @ApiModelProperty(value = "常规储备排产数量", name = "conventionProductionQty")
    @TableField(value = "CONVENTION_PRODUCTION_QTY")
    private Integer conventionProductionQty;

    /**
     * 暂缓订单排产数量
     */
    @Excel(name = "ui.data.column.FactoryMonthPlanFinalResult.postponeProductionQty")
    @ApiModelProperty(value = "暂缓订单排产数量", name = "postponeProductionQty")
    @TableField(value = "POSTPONE_PRODUCTION_QTY")
    private Integer postponeProductionQty;

    /**
     * 试制量试排产量
     */
    @Excel(name = "ui.data.column.FactoryMonthPlanFinalResult.trialProductionQty")
    @ApiModelProperty(value = "试制量试排产量", name = "trialProductionQty")
    @TableField(value = "TRIAL_PRODUCTION_QTY")
    private Integer trialProductionQty;

    /**
     * 差异量(未排产数量)
     */
    @Excel(name = "ui.data.column.FactoryMonthPlanFinalResult.differenceQty")
    @ApiModelProperty(value = "差异量(未排产数量)", name = "differenceQty")
    @TableField(value = "DIFFERENCE_QTY")
    private Integer differenceQty;

    /** 第1周调整量 */
    @ApiModelProperty(value = "第1周调整量", name = "adjustQty1")
    @TableField(value = "ADJUST_QTY1")
    private Integer adjustQty1;

    /** 第2周调整量 */
    @ApiModelProperty(value = "第2周调整量", name = "adjustQty2")
    @TableField(value = "ADJUST_QTY2")
    private Integer adjustQty2;

    /** 第3周调整量 */
    @ApiModelProperty(value = "第3周调整量", name = "adjustQty3")
    @TableField(value = "ADJUST_QTY3")
    private Integer adjustQty3;

    /** 第4周调整量 */
    @ApiModelProperty(value = "第4周调整量", name = "adjustQty4")
    @TableField(value = "ADJUST_QTY4")
    private Integer adjustQty4;

    /**
     * 未排产原因
     */
    @Excel(name = "ui.data.column.FactoryMonthPlanFinalResult.reason")
    @ApiModelProperty(value = "未排产原因", name = "reason")
    @TableField(value = "REASON")
    private String reason;

    /**
     * 开始日期
     */
    @Excel(name = "ui.data.column.FactoryMonthPlanFinalResult.beginDay")
    @ApiModelProperty(value = "开始日期", name = "beginDay")
    @TableField(value = "BEGIN_DAY")
    private Integer beginDay;

    /**
     * 结束日期
     */
    @Excel(name = "ui.data.column.FactoryMonthPlanFinalResult.endDay")
    @ApiModelProperty(value = "结束日期", name = "endDay")
    @TableField(value = "END_DAY")
    private Integer endDay;

    /**
     * DAY_1
     */
    @Excel(name = "ui.data.column.FactoryMonthPlanFinalResult.day1")
    @ApiModelProperty(value = "DAY_1", name = "day1")
    @TableField(value = "DAY_1")
    private Integer day1;

    /**
     * DAY_2
     */
    @Excel(name = "ui.data.column.FactoryMonthPlanFinalResult.day2")
    @ApiModelProperty(value = "DAY_2", name = "day2")
    @TableField(value = "DAY_2")
    private Integer day2;

    /**
     * DAY_3
     */
    @Excel(name = "ui.data.column.FactoryMonthPlanFinalResult.day3")
    @ApiModelProperty(value = "DAY_3", name = "day3")
    @TableField(value = "DAY_3")
    private Integer day3;

    /**
     * DAY_4
     */
    @Excel(name = "ui.data.column.FactoryMonthPlanFinalResult.day4")
    @ApiModelProperty(value = "DAY_4", name = "day4")
    @TableField(value = "DAY_4")
    private Integer day4;

    /**
     * DAY_5
     */
    @Excel(name = "ui.data.column.FactoryMonthPlanFinalResult.day5")
    @ApiModelProperty(value = "DAY_5", name = "day5")
    @TableField(value = "DAY_5")
    private Integer day5;

    /**
     * DAY_6
     */
    @Excel(name = "ui.data.column.FactoryMonthPlanFinalResult.day6")
    @ApiModelProperty(value = "DAY_6", name = "day6")
    @TableField(value = "DAY_6")
    private Integer day6;

    /**
     * DAY_7
     */
    @Excel(name = "ui.data.column.FactoryMonthPlanFinalResult.day7")
    @ApiModelProperty(value = "DAY_7", name = "day7")
    @TableField(value = "DAY_7")
    private Integer day7;

    /**
     * DAY_8
     */
    @Excel(name = "ui.data.column.FactoryMonthPlanFinalResult.day8")
    @ApiModelProperty(value = "DAY_8", name = "day8")
    @TableField(value = "DAY_8")
    private Integer day8;

    /**
     * DAY_9
     */
    @Excel(name = "ui.data.column.FactoryMonthPlanFinalResult.day9")
    @ApiModelProperty(value = "DAY_9", name = "day9")
    @TableField(value = "DAY_9")
    private Integer day9;

    /**
     * DAY_10
     */
    @Excel(name = "ui.data.column.FactoryMonthPlanFinalResult.day10")
    @ApiModelProperty(value = "DAY_10", name = "day10")
    @TableField(value = "DAY_10")
    private Integer day10;

    /**
     * DAY_11
     */
    @Excel(name = "ui.data.column.FactoryMonthPlanFinalResult.day11")
    @ApiModelProperty(value = "DAY_11", name = "day11")
    @TableField(value = "DAY_11")
    private Integer day11;

    /**
     * DAY_12
     */
    @Excel(name = "ui.data.column.FactoryMonthPlanFinalResult.day12")
    @ApiModelProperty(value = "DAY_12", name = "day12")
    @TableField(value = "DAY_12")
    private Integer day12;

    /**
     * DAY_13
     */
    @Excel(name = "ui.data.column.FactoryMonthPlanFinalResult.day13")
    @ApiModelProperty(value = "DAY_13", name = "day13")
    @TableField(value = "DAY_13")
    private Integer day13;

    /**
     * DAY_14
     */
    @Excel(name = "ui.data.column.FactoryMonthPlanFinalResult.day14")
    @ApiModelProperty(value = "DAY_14", name = "day14")
    @TableField(value = "DAY_14")
    private Integer day14;

    /**
     * DAY_15
     */
    @Excel(name = "ui.data.column.FactoryMonthPlanFinalResult.day15")
    @ApiModelProperty(value = "DAY_15", name = "day15")
    @TableField(value = "DAY_15")
    private Integer day15;

    /**
     * DAY_16
     */
    @Excel(name = "ui.data.column.FactoryMonthPlanFinalResult.day16")
    @ApiModelProperty(value = "DAY_16", name = "day16")
    @TableField(value = "DAY_16")
    private Integer day16;

    /**
     * DAY_17
     */
    @Excel(name = "ui.data.column.FactoryMonthPlanFinalResult.day17")
    @ApiModelProperty(value = "DAY_17", name = "day17")
    @TableField(value = "DAY_17")
    private Integer day17;

    /**
     * DAY_18
     */
    @Excel(name = "ui.data.column.FactoryMonthPlanFinalResult.day18")
    @ApiModelProperty(value = "DAY_18", name = "day18")
    @TableField(value = "DAY_18")
    private Integer day18;

    /**
     * DAY_19
     */
    @Excel(name = "ui.data.column.FactoryMonthPlanFinalResult.day19")
    @ApiModelProperty(value = "DAY_19", name = "day19")
    @TableField(value = "DAY_19")
    private Integer day19;

    /**
     * DAY_20
     */
    @Excel(name = "ui.data.column.FactoryMonthPlanFinalResult.day20")
    @ApiModelProperty(value = "DAY_20", name = "day20")
    @TableField(value = "DAY_20")
    private Integer day20;

    /**
     * DAY_21
     */
    @Excel(name = "ui.data.column.FactoryMonthPlanFinalResult.day21")
    @ApiModelProperty(value = "DAY_21", name = "day21")
    @TableField(value = "DAY_21")
    private Integer day21;

    /**
     * DAY_22
     */
    @Excel(name = "ui.data.column.FactoryMonthPlanFinalResult.day22")
    @ApiModelProperty(value = "DAY_22", name = "day22")
    @TableField(value = "DAY_22")
    private Integer day22;

    /**
     * DAY_23
     */
    @Excel(name = "ui.data.column.FactoryMonthPlanFinalResult.day23")
    @ApiModelProperty(value = "DAY_23", name = "day23")
    @TableField(value = "DAY_23")
    private Integer day23;

    /**
     * DAY_24
     */
    @Excel(name = "ui.data.column.FactoryMonthPlanFinalResult.day24")
    @ApiModelProperty(value = "DAY_24", name = "day24")
    @TableField(value = "DAY_24")
    private Integer day24;

    /**
     * DAY_25
     */
    @Excel(name = "ui.data.column.FactoryMonthPlanFinalResult.day25")
    @ApiModelProperty(value = "DAY_25", name = "day25")
    @TableField(value = "DAY_25")
    private Integer day25;

    /**
     * DAY_26
     */
    @Excel(name = "ui.data.column.FactoryMonthPlanFinalResult.day26")
    @ApiModelProperty(value = "DAY_26", name = "day26")
    @TableField(value = "DAY_26")
    private Integer day26;

    /**
     * DAY_27
     */
    @Excel(name = "ui.data.column.FactoryMonthPlanFinalResult.day27")
    @ApiModelProperty(value = "DAY_27", name = "day27")
    @TableField(value = "DAY_27")
    private Integer day27;

    /**
     * DAY_28
     */
    @Excel(name = "ui.data.column.FactoryMonthPlanFinalResult.day28")
    @ApiModelProperty(value = "DAY_28", name = "day28")
    @TableField(value = "DAY_28")
    private Integer day28;

    /**
     * DAY_29
     */
    @Excel(name = "ui.data.column.FactoryMonthPlanFinalResult.day29")
    @ApiModelProperty(value = "DAY_29", name = "day29")
    @TableField(value = "DAY_29")
    private Integer day29;

    /**
     * DAY_30
     */
    @Excel(name = "ui.data.column.FactoryMonthPlanFinalResult.day30")
    @ApiModelProperty(value = "DAY_30", name = "day30")
    @TableField(value = "DAY_30")
    private Integer day30;

    /**
     * DAY_31
     */
    @Excel(name = "ui.data.column.FactoryMonthPlanFinalResult.day31")
    @ApiModelProperty(value = "DAY_31", name = "day31")
    @TableField(value = "DAY_31")
    private Integer day31;

    /**
     * 硫化总工时
     */
    @Excel(name = "ui.data.column.FactoryMonthPlanFinalResult.totalVulcanizationMinutes")
    @ApiModelProperty(value = "硫化总工时", name = "totalVulcanizationMinutes")
    @TableField(value = "TOTAL_VULCANIZATION_MINUTES")
    private BigDecimal totalVulcanizationMinutes;

    /**
     * 显示顺序
     */
//    @Excel(name = "ui.data.column.FactoryMonthPlanFinalResult.displaySeq")
    @ApiModelProperty(value = "显示顺序", name = "displaySeq")
    @TableField(value = "DISPLAY_SEQ")
    private Integer displaySeq;

    /**
     * 发布状态，0--未发布，1--已发布，2-发布失败，3-发布中，4-超时失败，5-待发布。对应数据字典为：IS_RELEASE
     */
    @Excel(name = "ui.data.column.FactoryMonthPlanFinalResult.isRelease", dictType = "IS_RELEASE")
    @ApiModelProperty(value = "发布状态，0--未发布，1--已发布，2-发布失败，3-发布中，4-超时失败，5-待发布。对应数据字典为：IS_RELEASE", name = "isRelease")
    @TableField(value = "IS_RELEASE")
    private String isRelease;

    public Integer getDayLhQty() {
        if(null == this.dayVulcanizationQty) {
            return 0;
        }
        return dayVulcanizationQty * 2;
    }

    /**
     *  月底计划剩余量key
     */
    public String getGroupKey() {
        String keyFormat = "%s|*|%s";
        return String.format(keyFormat, factoryCode, materialDesc);
    }

}
