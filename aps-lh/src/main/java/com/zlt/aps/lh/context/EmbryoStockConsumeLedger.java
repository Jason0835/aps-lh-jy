package com.zlt.aps.lh.context;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDate;

/**
 * 胎胚库存消费账本。
 *
 * <p>用于“胎胚收尾 + T日收尾”场景的内部排产额度控制。
 * 账本只在一次排程上下文中使用，不落库；排程结果中的胎胚库存仍保存原始库存。</p>
 *
 * @author APS
 */
@Data
public class EmbryoStockConsumeLedger implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 胎胚代码 */
    private String embryoCode;

    /** T日业务日期，只保存日期，不保存具体时间 */
    private LocalDate scheduleDate;

    /** 原始胎胚库存 */
    private Integer originalStockQty;

    /** 本次组级目标量，固定等于原始胎胚库存 */
    private Integer targetQty;

    /** 已消费计划量 */
    private Integer consumedQty;

    /** 剩余可消费量，等于 targetQty - consumedQty */
    private Integer remainQty;

    /**
     * 扣减胎胚库存消费账本。
     *
     * @param planQty 本次确认排产量
     * @return 实际扣减量
     */
    public int consume(int planQty) {
        int effectivePlanQty = Math.max(0, planQty);
        if (effectivePlanQty <= 0) {
            return 0;
        }
        int currentRemainQty = Math.max(0, remainQty == null ? 0 : remainQty);
        int consumed = Math.min(currentRemainQty, effectivePlanQty);
        int currentConsumedQty = Math.max(0, consumedQty == null ? 0 : consumedQty);
        consumedQty = currentConsumedQty + consumed;
        remainQty = Math.max(0, currentRemainQty - consumed);
        return consumed;
    }

    /**
     * 恢复胎胚库存消费账本。
     *
     * @param restoredQty 本次恢复量
     * @return 恢复后的剩余可消费量
     */
    public int restore(int restoredQty) {
        int effectiveRestoredQty = Math.max(0, restoredQty);
        if (effectiveRestoredQty <= 0) {
            return Math.max(0, remainQty == null ? 0 : remainQty);
        }
        int currentTargetQty = Math.max(0, targetQty == null ? 0 : targetQty);
        int currentConsumedQty = Math.max(0, consumedQty == null ? 0 : consumedQty);
        consumedQty = Math.max(0, currentConsumedQty - effectiveRestoredQty);
        remainQty = Math.max(0, currentTargetQty - consumedQty);
        return remainQty;
    }
}
