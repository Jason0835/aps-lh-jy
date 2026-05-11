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

    /** 当前日期剩余应排计划量，max(0, dayPlanQty - scheduledQty) */
    private int remainingQty;

    /** 因满班补齐导致的超出当日计划量 */
    private int shiftFillOverQty;

    /** 是否已经完成当日计划 */
    private boolean completed;
}
