package com.zlt.aps.lh.api.domain.dto;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDate;

/**
 * SKU日计划额度账本DTO
 * <p>维护某个SKU在某一天的计划额度，用于多机台排产时按日控制计划量消耗。</p>
 * <p>同一SKU在多台机台上排产时，所有机台共享同一个日计划额度账本，
 * 避免重复消费同一天额度。</p>
 *
 * @author APS
 */
@Data
public class SkuDailyPlanQuotaDTO implements Serializable {

    /** 物料编码 */
    private String materialCode;

    /** 生产日期，例如 2026-05-07 */
    private LocalDate productionDate;

    /** 月计划中 dayN 对应的日计划量 */
    private int dayPlanQty;

    /** 滚动衔接、已锁定结果、已排结果中已经占用的数量 */
    private int scheduledQty;

    /** 当前日期在窗口账本中的剩余额度（已扣减继承量、滚动封顶和后续借用） */
    private int remainingQty;

    /** 因满班补齐导致的超出当日计划量 */
    private int shiftFillOverQty;

    /** 滚动到当前日期仍待补的累计额度 */
    private int carryLossQty;

    /** 当前日期提前消耗后续 dayN 的计划量 */
    private int futureBorrowQty;

    /** 当前日期实际排产量 */
    private int actualQty;

    /** 截至当前日期累计排产量 */
    private int cumulativeQty;

    /** 窗口最后一天仍未完成的最终欠产量 */
    private int finalLossQty;

    /** 是否已经消费完当前日期可用额度 */
    private boolean completed;
}
