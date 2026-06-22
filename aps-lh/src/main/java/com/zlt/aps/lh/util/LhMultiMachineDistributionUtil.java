package com.zlt.aps.lh.util;

import com.zlt.aps.lh.api.domain.entity.LhScheduleResult;
import org.springframework.util.CollectionUtils;

import java.util.List;

/**
 * 多机台排产库存回写工具类。
 * <p>同一SKU在多台机台生产只属于结果拆量，不属于共用胎胚库存分摊场景。</p>
 *
 * @author APS
 */
public class LhMultiMachineDistributionUtil {

    private LhMultiMachineDistributionUtil() {
    }

    /**
     * 对同一物料在多个机台上的排程结果，回写SKU已分配的完整胎胚库存。
     * <p>硫化余量和胎胚库存都不按机台数二次分摊。</p>
     * <p>会直接修改每个结果的 {@code embryoStock} 字段。</p>
     * <p>调用方需保证传入的结果列表已按物料分组且排产量已最终确定。</p>
     *
     * @param materialResults  同一物料在多个机台上的排程结果列表（至少2条）
     * @param embryoStock      该SKU经过共用胎胚规则分配后的完整胎胚库存
     */
    public static void retainFullEmbryoStockForSingleMaterial(List<LhScheduleResult> materialResults,
                                                               int embryoStock) {
        if (CollectionUtils.isEmpty(materialResults) || materialResults.size() <= 1) {
            return;
        }
        int effectiveEmbryoStock = Math.max(0, embryoStock);
        for (LhScheduleResult result : materialResults) {
            if (result != null) {
                result.setEmbryoStock(effectiveEmbryoStock);
            }
        }
    }
}
