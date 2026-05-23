package com.zlt.aps.lh.engine.strategy.impl;

import com.zlt.aps.lh.api.constant.LhScheduleConstant;
import com.zlt.aps.lh.component.TargetScheduleQtyResolver;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.api.domain.dto.SkuScheduleDTO;
import com.zlt.aps.lh.api.enums.SkuTagEnum;
import com.zlt.aps.lh.engine.strategy.IEndingJudgmentStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

/**
 * 默认收尾判定策略实现
 * <p>统一收尾判定逻辑，确保一致性</p>
 *
 * @author APS
 */
@Slf4j
@Component
public class DefaultEndingJudgmentStrategy implements IEndingJudgmentStrategy {

    @Resource
    private TargetScheduleQtyResolver targetScheduleQtyResolver;

    @Override
    public boolean isEnding(LhScheduleContext context, SkuScheduleDTO sku) {
        // 规则1：已明确标记为收尾
        if (SkuTagEnum.ENDING.getCode().equals(sku.getSkuTag())) {
            return true;
        }

        int targetScheduleQty = sku.resolveTargetScheduleQty();
        boolean fullCapacityMode = isFullCapacityMode(context);
        boolean endingBySurplusInFullModeEnabled = isEndingBySurplusInFullModeEnabled(context);

        // 规则2：排产目标量 <= 多台可用机台在排程窗口内的合计产能。
        // 不再使用单SKU理论产能（shiftCapacity * totalScheduleShifts），改为基于实际候选机台计算。
        // 满排模式下可选"按余量判定"开关，避免目标量封顶导致误判。
        int rule2CandidateQty = resolveRule2CandidateQty(sku, targetScheduleQty, fullCapacityMode,
                endingBySurplusInFullModeEnabled);
        if (rule2CandidateQty > 0) {
            int totalAvailableCapacity = getTargetScheduleQtyResolver()
                    .calcSkuTotalAvailableCapacityInWindow(context, sku);
            if (totalAvailableCapacity > 0 && rule2CandidateQty <= totalAvailableCapacity) {
                log.debug("SKU[{}]判定为收尾(规则2): 比较量{} <= 多机台合计产能{} (满排模式:{}, 满排余量开关:{})",
                        sku.getMaterialCode(), rule2CandidateQty, totalAvailableCapacity, fullCapacityMode,
                        endingBySurplusInFullModeEnabled);
                return true;
            }
        }

        // 规则3：待排量 < 日产能（非满产运行）
        int dailyCapacity = sku.getDailyCapacity();
        if (dailyCapacity > 0 && targetScheduleQty < dailyCapacity && targetScheduleQty > 0) {
            log.debug("SKU[{}]判定为收尾(规则3): 目标量{} < 日产能{}",
                    sku.getMaterialCode(), targetScheduleQty, dailyCapacity);
            return true;
        }

        return false;
    }

    @Override
    public int calculateEndingShifts(LhScheduleContext context, SkuScheduleDTO sku) {
        int shiftCapacity = sku.getShiftCapacity();
        if (shiftCapacity <= 0) {
            return -1;
        }

        int targetScheduleQty = sku.resolveTargetScheduleQty();
        if (targetScheduleQty <= 0) {
            return 0;
        }

        // 向上取整计算所需班次
        return (int) Math.ceil((double) targetScheduleQty / shiftCapacity);
    }

    @Override
    public int calculateEndingDays(LhScheduleContext context, SkuScheduleDTO sku) {
        int shifts = calculateEndingShifts(context, sku);
        if (shifts < 0) {
            return -1;
        }
        if (shifts == 0) {
            return 0;
        }
        return (int) Math.ceil((double) shifts / LhScheduleConstant.DEFAULT_SHIFTS_PER_DAY);
    }

    @Override
    public int calculateEndingDaysForStructurePriority(LhScheduleContext context, SkuScheduleDTO sku) {
        if (context == null || sku == null) {
            return -1;
        }
        TargetScheduleQtyResolver.StructureEndingCapacitySnapshot snapshot =
                getTargetScheduleQtyResolver().evaluateStructureEndingCapacity(context, sku);
        if (snapshot != null) {
            return snapshot.getEndingDaysWithinStructureWindow();
        }
        return calculateEndingDays(context, sku);
    }

    /**
     * 判断当前是否为按产能满排模式。
     *
     * @param context 排程上下文
     * @return true-按产能满排，false-按需求排产
     */
    private boolean isFullCapacityMode(LhScheduleContext context) {
        return context != null
                && context.getScheduleConfig() != null
                && context.getScheduleConfig().isFullCapacitySchedulingEnabled();
    }

    /**
     * 满排模式下是否启用"按余量判定规则2"。
     *
     * @param context 排程上下文
     * @return true-启用，false-关闭
     */
    private boolean isEndingBySurplusInFullModeEnabled(LhScheduleContext context) {
        if (context != null && context.getScheduleConfig() != null) {
            return context.getScheduleConfig().isEndingBySurplusInFullModeEnabled();
        }
        return LhScheduleConstant.ENABLE_ENDING_BY_SURPLUS_IN_FULL_MODE == 1;
    }

    /**
     * 解析规则2的比较量。
     *
     * @param sku SKU
     * @param targetScheduleQty 目标排产量
     * @param fullCapacityMode 是否满排模式
     * @param endingBySurplusInFullModeEnabled 满排按余量判收尾开关
     * @return 规则2比较量，<=0 表示本轮不执行规则2
     */
    private int resolveRule2CandidateQty(SkuScheduleDTO sku,
                                         int targetScheduleQty,
                                         boolean fullCapacityMode,
                                         boolean endingBySurplusInFullModeEnabled) {
        if (!fullCapacityMode) {
            return targetScheduleQty;
        }
        if (!endingBySurplusInFullModeEnabled) {
            return 0;
        }
        return resolveMaxDemandQty(sku.getSurplusQty(), sku.getEmbryoStock());
    }

    /**
     * 计算收尾比较量。
     *
     * @param surplusQty 月计划余量
     * @param embryoStock 胎胚库存
     * @return max(余量, 库存)
     */
    private int resolveMaxDemandQty(int surplusQty, int embryoStock) {
        return Math.max(Math.max(surplusQty, 0), Math.max(embryoStock, 0));
    }

    /**
     * 获取目标排产量解析器（带空安全回退）。
     *
     * @return 目标排产量解析器
     */
    private TargetScheduleQtyResolver getTargetScheduleQtyResolver() {
        return targetScheduleQtyResolver != null
                ? targetScheduleQtyResolver
                : new TargetScheduleQtyResolver();
    }
}
