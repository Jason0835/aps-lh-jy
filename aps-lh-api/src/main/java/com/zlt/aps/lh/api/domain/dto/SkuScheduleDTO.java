package com.zlt.aps.lh.api.domain.dto;

import lombok.Data;

import java.time.LocalDate;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * SKU排程数据传输对象
 * <p>在排程过程中携带SKU的计算中间数据</p>
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

    // ========== 状态标记 ==========
    /** SKU标记: 01-常规, 02-收尾 */
    private String skuTag;
    /** 排程类型: 01-续作, 02-新增, 03-换活字块 */
    private String scheduleType;
    /** 是否试制量试 */
    private boolean trial;
    /** 施工阶段 */
    private String constructionStage;
    /** 试制量试需求量 */
    private int trialDemandQty;
    /** 是否小批量验证SKU */
    private boolean smallBatchValidation;
    /** 月计划结构起产日 */
    private Integer beginDay;

    // ========== 优先级信息 ==========
    /** 排产优先级代码 */
    private String priorityCode;
    /** 排产顺序 */
    private int scheduleOrder;
    /** 是否有发货要求(锁定交期) */
    private boolean deliveryLocked;
    /** 延误天数 */
    private int delayDays = -1;
    /** 供应链优先级 */
    private String supplyChainPriority;
    /** 高优先级待排量 */
    private int highPriorityPendingQty;
    /** 周期排产待排量 */
    private int cycleProductionPendingQty;
    /** 中优先级待排量 */
    private int midPriorityPendingQty;
    /** 常规储备待排量 */
    private int conventionProductionPendingQty;

    // ========== 机台信息(续作时使用) ==========
    /** 续作机台编号 */
    private String continuousMachineCode;
    /** 续作机台上的模具号列表 */
    private List<String> mouldCodeList;
    /** 预计收尾时间 */
    private Date estimatedEndTime;
    /** 收尾日(距离当前天数) */
    private int endingDaysRemaining;

    // ========== 胎胚相关 ==========
    /** 胎胚库存 */
    private int embryoStock = -1;
    /** 胎胚可供硫化时长(小时) */
    private double embryoSupplyHours;

    // ========== 多机台排产相关 ==========
    /** 多机台拆量剩余排产量（收尾上调后由TargetScheduleQtyResolver写入，后续每台机台排产后递减） */
    private int remainingScheduleQty;

    /** 窗口内每日计划额度，key = productionDate */
    private Map<LocalDate, SkuDailyPlanQuotaDTO> dailyPlanQuotaMap;

    /** 窗口内日计划剩余量汇总（已扣减继承量、锁定量的每日剩余计划量之和） */
    private int windowRemainingPlanQty;

    /** 满班补齐导致的窗口内超排量（因班次需排满而超出日计划的累计量） */
    private int shiftFillOverQty;

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
