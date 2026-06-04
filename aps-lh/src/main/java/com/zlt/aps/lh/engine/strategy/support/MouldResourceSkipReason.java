package com.zlt.aps.lh.engine.strategy.support;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 增机台模具资源跳过原因。
 *
 * @author APS
 */
@Getter
@AllArgsConstructor
public enum MouldResourceSkipReason {

    /** SKU无可用模具号 */
    NO_AVAILABLE_MOULD("无可用模具"),
    /** SKU剩余可用模具数小于候选机台模数 */
    MOULD_QTY_NOT_ENOUGH("模具数量不足"),
    /** SKU模具关系存在，但模具台账缺失或禁用 */
    MODEL_INFO_UNAVAILABLE("模具台账不可用");

    /** 原因描述 */
    private final String description;
}
