package com.zlt.aps.lh.engine.strategy.support;

/**
 * 新增排产按业务日构建候选池时的候选来源。
 *
 * <p>候选来源只用于解释 SKU 为什么能够进入当前业务日，不参与 SKU 优先级比较。
 * 同一 SKU 可以同时具备多个来源，日内实际顺序仍复用 S4.5 已完成的 SKU 排序结果。</p>
 *
 * @author APS
 */
public enum DailyCandidateReason {

    /** 前一业务日已经在机且仍有待排量，需要优先使用原机台连续生产 */
    CONTINUE_ON_MACHINE,
    /** 当前业务日仍有日计划额度 */
    TODAY_PLAN,
    /** 续作补偿或 dayN 节奏判断要求从当前业务日起增加机台 */
    ADD_MACHINE_REQUIREMENT,
    /** 当前日和提前生产阈值内均无计划，但仍有历史欠产或既有收尾目标需要按原新增链处理 */
    HISTORY_SHORTAGE_OR_ENDING,
    /** 当前业务日满足现有提前生产准入规则 */
    EARLY_PRODUCTION,
    /** 前日交替计划已经形成当前 SKU 的指定机台反选指令 */
    ALTERNATE_PLAN_REVERSE_SELECT,
    /** 换活字块、续作释放或其他现有主链明确转入新增排产 */
    TYPE_BLOCK_TRANSFER,
    /** 前一业务日因时间或资源暂时不足而延期 */
    DEFERRED_FROM_PREVIOUS_DAY
}
