package com.zlt.aps.lh.engine.strategy.support;

import com.zlt.aps.lh.api.domain.dto.SkuDailyPlanQuotaDTO;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SKU 提前生产中心化运行视图。
 *
 * <p>该对象先以“候选态”保留当前月 {@code TOTAL_QTY=0}、但未来观察范围存在原始
 * 日计划的正规新增 SKU；当未来计划日进入提前生产阈值后，再切换为“激活态”并承载
 * 准入结论、未来计划来源日、动态历史欠产、未来月余量和临时前移日计划账本。
 * 各调用点不得分别重算，避免日期、余量或额度口径不一致。</p>
 *
 * <p>临时账本不替换 {@code SkuScheduleDTO.dailyPlanQuotaMap}，也不回写月计划数据库；
 * 整个新增排产窗口结束后由排程上下文统一清理。</p>
 *
 * @author APS
 */
@Data
public class EarlyProductionRuntimePlan implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 当前业务日期 */
    private LocalDate currentDate;

    /** 候选观察范围内最早存在原始日计划量的未来日期 */
    private LocalDate futurePlanDate;

    /** 当前月 TOTAL_QTY 为0、只能走提前生产流程的候选标识 */
    private boolean futureOnlyCandidate;

    /** 已通过当前业务日提前生产准入并完成临时账本初始化的激活标识 */
    private boolean active;

    /** 当前业务月月计划 TOTAL_QTY；记录缺失按0处理 */
    private int currentMonthTotalQty;

    /** futurePlanDate 所属计划段的月计划量 */
    private int futureMonthPlanTotalQty;

    /** futurePlanDate 所属月份截至排程基线的已完成量 */
    private int futureMonthFinishedQty;

    /** futurePlanDate 所属计划段扣减完成量并叠加有效上月超欠产后的非负余量 */
    private int futureMonthSurplusQty;

    /** 实际提前自然日数 */
    private int earlyDays;

    /** 本次生效的提前生产天数阈值 */
    private int earlyProductionDaysThreshold;

    /** 当前业务日原始日计划量，用于阶段归属审计 */
    private int originalCurrentDayPlanQty;

    /** 未来来源日原始日计划量 */
    private int futureDayPlanQty;

    /** 当前业务日所属月份、截至前一日的累计欠产量 */
    private int historyShortageQty;

    /** 未来计划月剩余需求叠加当前月历史欠产后的运行态目标量 */
    private int effectiveTargetQty;

    /** 提前生产结构化准入结论 */
    private EarlyProductionDecision decision;

    /**
     * 当前 SKU、当前提前生产阶段共享的临时前移日计划账本。
     *
     * <p>同一 SKU 成功增加多台机时必须共同消费本 Map，禁止为每台机重新复制额度。</p>
     */
    private Map<LocalDate, SkuDailyPlanQuotaDTO> shiftedDailyPlanQuotaMap =
            new LinkedHashMap<LocalDate, SkuDailyPlanQuotaDTO>(4);
}
