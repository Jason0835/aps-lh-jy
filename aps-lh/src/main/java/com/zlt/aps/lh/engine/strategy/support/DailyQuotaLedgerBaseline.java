package com.zlt.aps.lh.engine.strategy.support;

import cn.hutool.core.bean.BeanUtil;
import com.zlt.aps.lh.api.domain.dto.SkuDailyPlanQuotaDTO;
import com.zlt.aps.lh.api.domain.dto.SkuScheduleDTO;
import com.zlt.aps.lh.context.EmbryoStockConsumeLedger;
import com.zlt.aps.lh.context.LhScheduleContext;
import org.springframework.util.CollectionUtils;

import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 新增排产单次日计划扣账尝试的运行态快照。
 *
 * <p>新增候选在换模、首检和班次结果已经生成后，仍可能因生产余量或 dayN 额度裁剪为零而失败。
 * 此时不能只释放机台和模具资源，还必须恢复本次尝试已经触碰的 dayN、SKU 实际余量、胎胚库存和
 * 满班补齐累计量；否则下一台候选会读取到失败尝试留下的脏账本。本快照只覆盖扣账方法会写入的
 * 运行态，不承担结果、机台、换模或首检状态的恢复职责。</p>
 *
 * @author APS
 */
public final class DailyQuotaLedgerBaseline {

    /** 使用对象身份保存共享 dayN 账本，避免同一额度对象被多个 SKU 引用时重复还原。 */
    private final Map<SkuDailyPlanQuotaDTO, SkuDailyPlanQuotaDTO> quotaStateMap;
    /** SKU 实际生产余量账本快照。 */
    private final Map<String, Integer> productionRemainingQtyMap;
    /** SKU 满班补齐超排量汇总快照。 */
    private final Map<String, Integer> shiftFillOverQtyMap;
    /** 胎胚组级消费账本快照。 */
    private final Map<String, EmbryoStockConsumeLedger> embryoLedgerMap;
    /** 当前 SKU 自身的满班补齐超排量。 */
    private final int skuShiftFillOverQty;

    private DailyQuotaLedgerBaseline(
            Map<SkuDailyPlanQuotaDTO, SkuDailyPlanQuotaDTO> quotaStateMap,
            Map<String, Integer> productionRemainingQtyMap,
            Map<String, Integer> shiftFillOverQtyMap,
            Map<String, EmbryoStockConsumeLedger> embryoLedgerMap,
            int skuShiftFillOverQty) {
        this.quotaStateMap = quotaStateMap;
        this.productionRemainingQtyMap = productionRemainingQtyMap;
        this.shiftFillOverQtyMap = shiftFillOverQtyMap;
        this.embryoLedgerMap = embryoLedgerMap;
        this.skuShiftFillOverQty = skuShiftFillOverQty;
    }

    /**
     * 捕获单次候选扣账前的运行态。
     *
     * @param context 排程上下文
     * @param sku 当前候选 SKU
     * @return 可用于失败恢复的账本快照
     */
    public static DailyQuotaLedgerBaseline capture(LhScheduleContext context, SkuScheduleDTO sku) {
        Map<SkuDailyPlanQuotaDTO, SkuDailyPlanQuotaDTO> quotaStateMap =
                new IdentityHashMap<SkuDailyPlanQuotaDTO, SkuDailyPlanQuotaDTO>();
        if (Objects.nonNull(sku) && !CollectionUtils.isEmpty(sku.getDailyPlanQuotaMap())) {
            for (SkuDailyPlanQuotaDTO quota : sku.getDailyPlanQuotaMap().values()) {
                if (Objects.nonNull(quota) && !quotaStateMap.containsKey(quota)) {
                    quotaStateMap.put(quota, copyQuota(quota));
                }
            }
        }
        if (Objects.isNull(context)) {
            return new DailyQuotaLedgerBaseline(
                    quotaStateMap,
                    new LinkedHashMap<String, Integer>(0),
                    new LinkedHashMap<String, Integer>(0),
                    new LinkedHashMap<String, EmbryoStockConsumeLedger>(0),
                    Objects.isNull(sku) ? 0 : sku.getShiftFillOverQty());
        }
        return new DailyQuotaLedgerBaseline(
                quotaStateMap,
                new LinkedHashMap<String, Integer>(context.getSkuProductionRemainingQtyMap()),
                new LinkedHashMap<String, Integer>(context.getSkuShiftFillOverQtyMap()),
                copyEmbryoLedgerMap(context.getEmbryoStockConsumeLedgerMap()),
                Objects.isNull(sku) ? 0 : sku.getShiftFillOverQty());
    }

    /**
     * 恢复本次扣账尝试产生的运行态变更。
     *
     * @param context 排程上下文
     * @param sku 当前候选 SKU
     */
    public void restore(LhScheduleContext context, SkuScheduleDTO sku) {
        for (Map.Entry<SkuDailyPlanQuotaDTO, SkuDailyPlanQuotaDTO> entry : quotaStateMap.entrySet()) {
            BeanUtil.copyProperties(entry.getValue(), entry.getKey());
        }
        if (Objects.nonNull(sku)) {
            sku.setShiftFillOverQty(skuShiftFillOverQty);
        }
        if (Objects.isNull(context)) {
            return;
        }
        context.setSkuProductionRemainingQtyMap(
                new LinkedHashMap<String, Integer>(productionRemainingQtyMap));
        context.setSkuShiftFillOverQtyMap(
                new LinkedHashMap<String, Integer>(shiftFillOverQtyMap));
        context.setEmbryoStockConsumeLedgerMap(copyEmbryoLedgerMap(embryoLedgerMap));
    }

    private static SkuDailyPlanQuotaDTO copyQuota(SkuDailyPlanQuotaDTO source) {
        SkuDailyPlanQuotaDTO target = new SkuDailyPlanQuotaDTO();
        BeanUtil.copyProperties(source, target);
        return target;
    }

    private static Map<String, EmbryoStockConsumeLedger> copyEmbryoLedgerMap(
            Map<String, EmbryoStockConsumeLedger> sourceMap) {
        Map<String, EmbryoStockConsumeLedger> targetMap =
                new LinkedHashMap<String, EmbryoStockConsumeLedger>(
                        Math.max(16, CollectionUtils.isEmpty(sourceMap) ? 0 : sourceMap.size() * 2));
        if (CollectionUtils.isEmpty(sourceMap)) {
            return targetMap;
        }
        for (Map.Entry<String, EmbryoStockConsumeLedger> entry : sourceMap.entrySet()) {
            if (Objects.isNull(entry.getValue())) {
                targetMap.put(entry.getKey(), null);
                continue;
            }
            EmbryoStockConsumeLedger copiedLedger = new EmbryoStockConsumeLedger();
            BeanUtil.copyProperties(entry.getValue(), copiedLedger);
            targetMap.put(entry.getKey(), copiedLedger);
        }
        return targetMap;
    }
}
