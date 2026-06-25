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
        int rule2CandidateQty = resolveRule2CandidateQty(context, sku, targetScheduleQty, fullCapacityMode,
                endingBySurplusInFullModeEnabled);
        boolean skipRule2ByWindowRemainingPlanQty = rule2CandidateQty > 0
                && shouldSkipRule2ByWindowRemainingPlanQty(sku, fullCapacityMode,
                endingBySurplusInFullModeEnabled, rule2CandidateQty);
        if (rule2CandidateQty > 0 && !skipRule2ByWindowRemainingPlanQty) {
            int totalAvailableCapacity = getTargetScheduleQtyResolver()
                    .calcSkuTotalAvailableCapacityInWindow(context, sku);
            if (totalAvailableCapacity > 0 && rule2CandidateQty <= totalAvailableCapacity) {
                log.debug("SKU[{}]判定为收尾(规则2): 比较量{} <= 多机台合计产能{} (满排模式:{}, 满排余量开关:{})",
                        sku.getMaterialCode(), rule2CandidateQty, totalAvailableCapacity, fullCapacityMode,
                        endingBySurplusInFullModeEnabled);
                return true;
            }
        }
        if (skipRule2ByWindowRemainingPlanQty
                && getTargetScheduleQtyResolver().canFinishSurplusInActualWindow(context, sku)) {
            log.debug("SKU[{}]判定为收尾(满排余量窗口产能): 硫化余量可在当前排程窗口内完成",
                    sku.getMaterialCode());
            return true;
        }

        // 规则3：待排量 < 日产能。
        // 满排模式且启用"按余量判收尾"时，优先按实际收尾需求量（max(余量,胎胚库存)）判断，
        // 避免全月待排量过大导致收尾漏判。
        int dailyCapacity = sku.getDailyCapacity();
        int rule3CandidateQty = (fullCapacityMode && endingBySurplusInFullModeEnabled)
                ? resolveMaxDemandQty(context, sku)
                : targetScheduleQty;
        if (dailyCapacity > 0 && rule3CandidateQty < dailyCapacity && rule3CandidateQty > 0) {
            log.debug("SKU[{}]判定为收尾(规则3): 比较量{} < 日产能{} (满排模式:{}, 满排余量开关:{})",
                    sku.getMaterialCode(), rule3CandidateQty, dailyCapacity,
                    fullCapacityMode, endingBySurplusInFullModeEnabled);
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
    private int resolveRule2CandidateQty(LhScheduleContext context,
                                         SkuScheduleDTO sku,
                                         int targetScheduleQty,
                                         boolean fullCapacityMode,
                                         boolean endingBySurplusInFullModeEnabled) {
        if (!fullCapacityMode) {
            return targetScheduleQty;
        }
        if (!endingBySurplusInFullModeEnabled) {
            return 0;
        }
        return resolveMaxDemandQty(context, sku);
    }

    /**
     * 判断规则2是否应被窗口剩余额度拦截。
     * <p>仅在满排模式且启用"按余量判收尾"时生效，避免窗口 dayN 额度明显不足时提前命中收尾。</p>
     *
     * @param sku SKU
     * @param fullCapacityMode 是否满排模式
     * @param endingBySurplusInFullModeEnabled 满排按余量判收尾开关
     * @param rule2CandidateQty 规则2比较量
     * @return true-跳过规则2，false-保留原判定
     */
    private boolean shouldSkipRule2ByWindowRemainingPlanQty(SkuScheduleDTO sku,
                                                            boolean fullCapacityMode,
                                                            boolean endingBySurplusInFullModeEnabled,
                                                            int rule2CandidateQty) {
        if (sku == null || !fullCapacityMode || !endingBySurplusInFullModeEnabled || rule2CandidateQty <= 0) {
            return false;
        }
        int windowRemainingPlanQty = Math.max(0, sku.getWindowRemainingPlanQty());
        if (windowRemainingPlanQty <= 0 || rule2CandidateQty <= windowRemainingPlanQty) {
            return false;
        }
        log.debug("SKU[{}]跳过收尾规则2: 比较量{} > 窗口剩余额度{}",
                sku.getMaterialCode(), rule2CandidateQty, windowRemainingPlanQty);
        return true;
    }

    /**
     * 计算收尾比较量。
     * <p>共用胎胚收尾只按硫化余量判定，不按胎胚库存；单胎胚仍按 MAX(余量, 库存)。</p>
     *
     * @param context 排程上下文
     * @param sku SKU排程DTO
     * @return 收尾比较量
     */
    private int resolveMaxDemandQty(LhScheduleContext context, SkuScheduleDTO sku) {
        int surplusQty = Math.max(0, sku.getSurplusQty());
        int embryoStock = Math.max(0, sku.getEmbryoStock());
        // 共用胎胚收尾只按硫化余量，不按胎胚库存
        if (getTargetScheduleQtyResolver().isSharedEmbryoInWindow(context, sku)) {
            if (embryoStock > surplusQty) {
                log.debug("共用胎胚收尾判定比较量下调, materialCode: {}, 胎胚编码: {}, "
                                + "原口径MAX(余量,库存): {}, 新口径仅余量: {}, 下调幅度: {}",
                        sku.getMaterialCode(), sku.getEmbryoCode(),
                        Math.max(surplusQty, embryoStock), surplusQty,
                        Math.max(surplusQty, embryoStock) - surplusQty);
            }
            return surplusQty;
        }
        return Math.max(surplusQty, embryoStock);
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
