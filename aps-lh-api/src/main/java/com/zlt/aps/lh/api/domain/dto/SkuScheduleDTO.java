package com.zlt.aps.lh.api.domain.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * SKU排程数据传输对象。
 *
 * <p>该对象在 S4.3 由月计划、产能、胎胚库存和历史完成量组装生成，随后在 S4.4/S4.5
 * 作为排序、选机、收尾、班次分配和dayN节奏账本扣减的核心入参。</p>
 *
 * <p>注意：多数数量字段会在排程过程中动态变化，例如 {@link #pendingQty}、
 * {@link #remainingScheduleQty}、{@link #dailyPlanQuotaMap}。维护时不要把它当成只读月计划快照。</p>
 *
 * @author APS
 */
@Data
public class SkuScheduleDTO {

    /** 物料编码(SKU唯一标识) */
    private String materialCode;
    /** 物料描述 */
    private String materialDesc;
    /** 产品结构 */
    private String structureName;
    /** 胎胚代码 */
    private String embryoCode;
    /** 主物料(胎胚描述) */
    private String mainMaterialDesc;
    /** 规格代码 */
    private String specCode;
    /** 规格描述 */
    private String specDesc;
    /** 英寸 */
    private String proSize;
    /** 花纹 */
    private String pattern;
    /** 主花纹 */
    private String mainPattern;
    /** 品牌 */
    private String brand;

    // ========== 计划量信息 ==========
    /** 月度计划总量 */
    private int monthPlanQty;
    /** 已完成合格量 */
    private int finishedQty;
    /** 硫化余量 = 月度计划量 - 已完成合格量 */
    private int surplusQty;
    /** 排程窗口计划量（窗口内各日计划量之和） */
    private int windowPlanQty;
    /** T日计划量 */
    private int dailyPlanQty;
    /** 待排产量(排程过程中动态递减) */
    private int pendingQty;
    /** 排产目标量（由调度侧按模式计算后写入：按需求或按产能满排） */
    private Integer targetScheduleQty;

    // ========== 产能信息 ==========
    /** 硫化时间(秒) */
    private int lhTimeSeconds;
    /** 硫化班产(标准) */
    private int shiftCapacity;
    /** 日硫化量 */
    private int dailyCapacity;
    /** 使用模数 */
    private int mouldQty;
    /** 月计划模具使用变化信息，格式如 4-2-2；S4.5 窗口无日计划历史欠产补排时取第一段判断计划使用模数 */
    private String mouldChangeInfo;

    // ========== 状态标记 ==========
    /** SKU标记: 01-常规, 02-收尾；由收尾判断策略写入，影响严格目标量和结果收尾标识 */
    private String skuTag;
    /** 排程类型: 01-续作, 02-新增, 03-换活字块 */
    private String scheduleType;
    /** 是否续作欠产转入S4.5的补偿SKU，仅用于新增SKU组内排序识别，不落库 */
    private boolean continuousCompensationSku;
    /** 是否试制量试 */
    private boolean trial;
    /** 施工阶段 */
    private String constructionStage;
    /** 试制量试需求量 */
    private int trialDemandQty;
    /** 是否小批量验证SKU；正规 SKU 余量低于参数阈值时置为 true，主要影响单控/普通机台选择规则 */
    private boolean smallBatchValidation;
    /** 月计划结构起产日 */
    private Integer beginDay;
    /** 月计划结构结束日 */
    private Integer endDay;

    // ========== 优先级信息 ==========
    /** 排产优先级代码 */
    private String priorityCode;
    /** 排产顺序；由 SKU 排序策略回写，最终用于排程结果 scheduleOrder */
    private int scheduleOrder;
    /** SKU 排序名次（续作/新增列表内 1~N，与“SKU排序优先级汇总”日志 rank 字段一致），落库到排程结果 skuSortRank */
    private int sortRank;
    /** SKU 排序描述；由 SKU 排序策略统一生成，与“SKU排序优先级汇总”单行日志同源，落库到排程结果 skuSortDesc */
    private String sortDesc;
    /** 是否有发货要求(锁定交期) */
    private boolean deliveryLocked;
    /** 延误天数，月计划开始日距T日的天数差（beginDate - scheduleDate），负数=延误，null=未知 */
    private Integer delayDays;
    /** 供应链优先级 */
    private String supplyChainPriority;
    /** 排产分类，来自月计划 PRODUCTION_TYPE；01-主销产品，其他-普通产品 */
    private String productionType;
    /** 高优先级待排量 */
    private int highPriorityPendingQty;
    /** 周期排产待排量 */
    private int cycleProductionPendingQty;
    /** 中优先级待排量 */
    private int midPriorityPendingQty;
    /** 常规储备待排量 */
    private int conventionProductionPendingQty;

    // ========== 机台信息(续作时使用) ==========
    /** 续作机台编号；来源于 MES 在机/前批次结果，S4.4 按该机台继续排产 */
    private String continuousMachineCode;
    /** 续作释放后转新增补偿时保留的原续作优先机台，仅供 S4.5 轮到该 SKU 选机时优先锁回 */
    @EqualsAndHashCode.Exclude
    private String preferredContinuousMachineCode;
    /** 续作机台上的模具号列表 */
    private List<String> mouldCodeList;
    /** 预计收尾时间 */
    private Date estimatedEndTime;
    /** 收尾日(距离当前天数) */
    private int endingDaysRemaining;

    // ========== 胎胚相关 ==========
    /** 胎胚库存；-1 表示算法内部未知库存，结果落库时会按展示口径转换，避免未知库存直接误导排产裁剪 */
    private int embryoStock = -1;
    /** 胎胚可供硫化时长(小时) */
    private double embryoSupplyHours;

    // ========== 多机台排产相关 ==========
    /** 多机台拆量剩余排产量（运行态由SKU实际消费账本解析后写入，后续每台机台排产后递减） */
    private int remainingScheduleQty;

    /** 窗口内dayN节奏账本，key = productionDate；由月计划 dayN 映射到排程窗口日期后生成 */
    private Map<LocalDate, SkuDailyPlanQuotaDTO> dailyPlanQuotaMap;

    /** 窗口内dayN节奏剩余量汇总（已扣减继承量、锁定量的每日剩余计划量之和） */
    private int windowRemainingPlanQty;

    /** 满班补齐导致的窗口内超排量（因班次需排满而超出日计划的累计量） */
    private int shiftFillOverQty;

    /** 当前排程月份内、早于T日的历史欠产量，仅正向欠产进入新增排产判断 */
    private int monthlyHistoryShortageQty;

    /** 有效上月超欠产量；正数为欠产，负数为超产，仅用于本轮排产准入判断 */
    private int effectiveLastMonthOverdueQty;

    /** 已在初始化阶段实际追加到账本的本月历史欠产量 */
    private int effectiveCarryForwardQty;

    /** T日排程晚班完成量，用于新增排产首日需求扣减 */
    private int scheduleDayFinishQty;

    /** T+3到月底仍存在的月计划日计划量 */
    private int futureMonthPlanQtyAfterWindow;

    /** 窗口结束后第一天的月计划日计划量，用于新增排产 T+2 后看 T+3 判断 */
    private int nextDayPlanQtyAfterWindow;

    // ========== 目标量控制字段 ==========
    /** 是否严格限制目标量（试制/收尾=true，正式/量试=false）。为true时禁止超出dayN补满班次 */
    private boolean strictTargetQty;

    /** 是否仅补本月欠产量，非收尾但必须严格目标量、不允许满班超排 */
    private boolean strictNewSpecShortageOnly;

    // ========== 月计划版本信息 ==========
    /** 月计划需求版本 */
    private String monthPlanVersion;
    /** 月计划排产版本 */
    private String productionVersion;
    /** 制造示方书号 */
    private String embryoNo;
    /** 文字示方书号 */
    private String textNo;
    /** 硫化示方书号 */
    private String lhNo;

    /** 产品状态（来自月计划），后续用于匹配硫化示方类型和结果字段 PRODUCT_STATUS */
    private String productStatus;

    /**
     * 解析本轮排产目标量。
     * <p>主流程优先使用显式写入的新口径，旧测试/旧构造场景未赋值时回退到待排量口径。</p>
     *
     * @return 排产目标量
     */
    public int resolveTargetScheduleQty() {
        if (targetScheduleQty != null) {
            return Math.max(targetScheduleQty, 0);
        }
        if (pendingQty > 0) {
            return pendingQty;
        }
        return Math.max(windowPlanQty, 0);
    }
}
