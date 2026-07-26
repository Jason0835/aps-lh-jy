package com.zlt.aps.lh.engine.strategy.support;

import com.zlt.aps.lh.api.domain.dto.SkuDailyPlanQuotaDTO;
import com.zlt.aps.lh.api.domain.dto.SkuScheduleDTO;
import com.zlt.aps.lh.context.EmbryoStockConsumeLedger;
import com.zlt.aps.lh.context.LhScheduleContext;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 新增候选日计划扣账快照测试。
 *
 * @author APS
 */
class DailyQuotaLedgerBaselineTest {

    /**
     * 验证候选在日计划裁零后恢复时，dayN、SKU实际余量、胎胚账本和满班补齐量均回到尝试前状态。
     */
    @Test
    void restore_shouldRecoverAllLedgerStateMutatedByRejectedCandidate() {
        LhScheduleContext context = new LhScheduleContext();
        SkuScheduleDTO sku = new SkuScheduleDTO();
        sku.setMaterialCode("3302000001");
        sku.setProductStatus("S");
        sku.setShiftFillOverQty(3);
        SkuDailyPlanQuotaDTO quota = buildQuota();
        Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap =
                new LinkedHashMap<LocalDate, SkuDailyPlanQuotaDTO>(2);
        quotaMap.put(LocalDate.of(2026, 7, 25), quota);
        sku.setDailyPlanQuotaMap(quotaMap);
        context.getSkuProductionRemainingQtyMap().put("3302000001_S", 30);
        context.getSkuShiftFillOverQtyMap().put("3302000001_S", 3);
        context.getEmbryoStockConsumeLedgerMap().put("EMB_20260725", buildEmbryoLedger());

        DailyQuotaLedgerBaseline baseline = DailyQuotaLedgerBaseline.capture(context, sku);

        quota.setRemainingQty(0);
        quota.setActualQty(20);
        quota.setFutureBorrowQty(12);
        quota.setShiftFillOverQty(8);
        quota.setCompleted(true);
        sku.setShiftFillOverQty(8);
        context.getSkuProductionRemainingQtyMap().put("3302000001_S", 0);
        context.getSkuShiftFillOverQtyMap().put("3302000001_S", 8);
        context.getEmbryoStockConsumeLedgerMap().get("EMB_20260725").setConsumedQty(20);
        context.getEmbryoStockConsumeLedgerMap().get("EMB_20260725").setRemainQty(0);

        baseline.restore(context, sku);

        assertEquals(20, quota.getRemainingQty());
        assertEquals(0, quota.getActualQty());
        assertEquals(0, quota.getFutureBorrowQty());
        assertEquals(1, quota.getShiftFillOverQty());
        assertEquals(false, quota.isCompleted());
        assertEquals(3, sku.getShiftFillOverQty());
        assertEquals(30, context.getSkuProductionRemainingQtyMap().get("3302000001_S"));
        assertEquals(3, context.getSkuShiftFillOverQtyMap().get("3302000001_S"));
        assertEquals(0, context.getEmbryoStockConsumeLedgerMap().get("EMB_20260725").getConsumedQty());
        assertEquals(20, context.getEmbryoStockConsumeLedgerMap().get("EMB_20260725").getRemainQty());
    }

    /**
     * 构建含全部运行态字段的 dayN 账本，覆盖实际扣账和满班补齐恢复。
     *
     * @return 日计划账本
     */
    private SkuDailyPlanQuotaDTO buildQuota() {
        SkuDailyPlanQuotaDTO quota = new SkuDailyPlanQuotaDTO();
        quota.setMaterialCode("3302000001");
        quota.setProductionDate(LocalDate.of(2026, 7, 25));
        quota.setDayPlanQty(20);
        quota.setScheduledQty(0);
        quota.setRemainingQty(20);
        quota.setShiftFillOverQty(1);
        quota.setCarryLossQty(0);
        quota.setFutureBorrowQty(0);
        quota.setActualQty(0);
        quota.setCumulativeQty(0);
        quota.setFinalLossQty(0);
        quota.setCompleted(false);
        return quota;
    }

    /**
     * 构建胎胚库存运行态账本。
     *
     * @return 胎胚账本
     */
    private EmbryoStockConsumeLedger buildEmbryoLedger() {
        EmbryoStockConsumeLedger ledger = new EmbryoStockConsumeLedger();
        ledger.setEmbryoCode("EMB");
        ledger.setScheduleDate(LocalDate.of(2026, 7, 25));
        ledger.setOriginalStockQty(20);
        ledger.setTargetQty(20);
        ledger.setConsumedQty(0);
        ledger.setRemainQty(20);
        return ledger;
    }
}
