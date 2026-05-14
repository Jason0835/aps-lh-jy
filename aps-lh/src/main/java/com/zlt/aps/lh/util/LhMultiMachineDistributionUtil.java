package com.zlt.aps.lh.util;

import com.zlt.aps.lh.api.domain.entity.LhScheduleResult;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 多机台排产分摊工具类
 * <p>同一物料在多台机台生产时，按机台结果条数均分余量和胎胚库存，最后一条补尾差。
 * 分摊后各机台分摊量之和严格等于原始总量，不丢不超。</p>
 *
 * @author APS
 */
public class LhMultiMachineDistributionUtil {

    private LhMultiMachineDistributionUtil() {
    }

    /**
     * 对同一物料在多个机台上的排程结果，按机台条数均分胎胚库存。
     * <p>硫化余量不做分摊，各机台结果保留原始全量值，由下游按需汇总。</p>
     * <p>会直接修改每个结果的 {@code embryoStock} 字段。</p>
     * <p>调用方需保证传入的结果列表已按物料分组且排产量已最终确定。</p>
     *
     * @param materialResults  同一物料在多个机台上的排程结果列表（至少2条）
     * @param totalSurplus     该物料的总硫化余量（已废弃，不再分摊）
     * @param totalEmbryoStock 该物料的总胎胚库存（从SKU DTO取全量），≤0时跳过分摊
     */
    public static void distributeForSingleMaterial(List<LhScheduleResult> materialResults,
                                                    int totalSurplus,
                                                    int totalEmbryoStock) {
        if (CollectionUtils.isEmpty(materialResults) || materialResults.size() <= 1) {
            return;
        }
        // 硫化余量不按机台分摊，各机台结果保留原始全量值
        // 分摊胎胚库存
        if (totalEmbryoStock > 0) {
            List<Integer> stockDistribution = distributeEvenly(totalEmbryoStock, materialResults.size());
            for (int i = 0; i < materialResults.size(); i++) {
                materialResults.get(i).setEmbryoStock(stockDistribution.get(i));
            }
        }
    }

    /**
     * 按结果条数均分总量，最后一条补尾差。
     *
     * @param totalValue 总量
     * @param resultSize 结果条数
     * @return 分摊结果
     */
    private static List<Integer> distributeEvenly(int totalValue, int resultSize) {
        List<Integer> distribution = new ArrayList<>(resultSize);
        if (resultSize <= 0) {
            return distribution;
        }
        if (totalValue <= 0) {
            for (int i = 0; i < resultSize; i++) {
                distribution.add(0);
            }
            return distribution;
        }
        int baseQty = totalValue / resultSize;
        int allocatedQty = 0;
        for (int i = 0; i < resultSize; i++) {
            int distributedQty = i == resultSize - 1
                    ? totalValue - allocatedQty
                    : baseQty;
            distribution.add(distributedQty);
            allocatedQty += distributedQty;
        }
        return distribution;
    }
}
