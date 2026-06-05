package com.zlt.aps.lh.engine.strategy.support;

import com.zlt.aps.lh.api.domain.dto.SkuDailyPlanQuotaDTO;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * dayN 机台产能模拟请求。
 */
@Data
public class DailyMachineCapacitySimulationRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** SKU物料编码 */
    private String materialCode;

    /** 日计划额度账本 */
    private Map<LocalDate, SkuDailyPlanQuotaDTO> dailyPlanQuotaMap;

    /** 候选机台逐日产能列表，顺序即启用优先级 */
    private List<Map<LocalDate, Integer>> machineDailyCapacityList;

    /** 初始启用机台数 */
    private int initialActiveMachines;

    /** 单台机台班产，仅用于8班窗口总产能和后一天3班理论产能判断 */
    private int shiftCapacity;

    /** 向后观察天数，不含当天 */
    private int shortageLookAheadDays;

    /** 新增排产欠产增机台阈值 */
    private int shortageAddMachineThreshold;

    /** 当前排程月份内、早于T日的历史欠产量 */
    private int monthlyHistoryShortageQty;

    /** T日晚班已完成量，已经在日计划账本初始化时扣减 */
    private int scheduleDayFinishQty;

    /** T~T+2窗口内月计划dayN汇总，不包含历史欠产追加量 */
    private int windowMonthPlanQty;

    /** 排程窗口结束日期 */
    private LocalDate windowEndDate;

    /** 场景类型 */
    private String sceneType;
}
