package com.zlt.aps.lh.util;

import com.zlt.aps.lh.api.domain.entity.LhScheduleResult;
import org.apache.commons.lang3.StringUtils;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 多机台排产分摊工具类
 * <p>同一物料在多台机台生产时，按实际排产量占比分摊余量和胎胚库存，最大余数法处理尾差。
 * 分摊后各机台分摊量之和严格等于原始总量，不丢不超。</p>
 *
 * @author APS
 */
public class LhMultiMachineDistributionUtil {

    private LhMultiMachineDistributionUtil() {
    }

    /**
     * 按实际排产量占比分摊总量，最大余数法处理尾差。
     * <p>计算每台机台的理论分摊量（总量 × 本机台排产量 / 总排产量），
     * 先向下取整，剩余尾差按小数部分从大到小分配。
     * 若小数部分相同，按排产量倒序分配；若排产量也相同，按机台编码字典序分配。</p>
     *
     * @param totalValue        待分摊总量（余量或胎胚库存）
     * @param machinePlanQtyMap 机台编码 → 该机台实际排产量
     * @return 机台编码 → 分摊量，保证各值之和等于 totalValue
     */
    public static Map<String, Integer> distributeByProductionRatio(
            int totalValue, Map<String, Integer> machinePlanQtyMap) {
        Map<String, Integer> resultMap = new LinkedHashMap<>(machinePlanQtyMap.size());
        if (CollectionUtils.isEmpty(machinePlanQtyMap)) {
            return resultMap;
        }
        if (totalValue <= 0) {
            for (String machineCode : machinePlanQtyMap.keySet()) {
                resultMap.put(machineCode, 0);
            }
            return resultMap;
        }

        int totalPlanQty = machinePlanQtyMap.values().stream().mapToInt(Integer::intValue).sum();
        if (totalPlanQty <= 0) {
            // 所有机台排产量均为0时均摊
            int base = totalValue / machinePlanQtyMap.size();
            int remainder = totalValue % machinePlanQtyMap.size();
            int idx = 0;
            for (String machineCode : machinePlanQtyMap.keySet()) {
                resultMap.put(machineCode, base + (idx < remainder ? 1 : 0));
                idx++;
            }
            return resultMap;
        }

        // 按比例计算每台机台的理论分摊量
        Map<String, Double> theoreticalMap = new LinkedHashMap<>(machinePlanQtyMap.size());
        for (Map.Entry<String, Integer> entry : machinePlanQtyMap.entrySet()) {
            double ratio = (double) entry.getValue() / totalPlanQty;
            theoreticalMap.put(entry.getKey(), totalValue * ratio);
        }

        // 向下取整
        int allocatedSum = 0;
        for (Map.Entry<String, Double> entry : theoreticalMap.entrySet()) {
            int allocated = entry.getValue().intValue();
            resultMap.put(entry.getKey(), allocated);
            allocatedSum += allocated;
        }

        // 最大余数法分配尾差：按小数部分降序，小数相同时按排产量降序，再按机台编码字典序
        int remainder = totalValue - allocatedSum;
        if (remainder > 0) {
            List<Map.Entry<String, Double>> sortedEntries = new ArrayList<>(theoreticalMap.entrySet());
            sortedEntries.sort(Comparator
                    .comparingDouble((Map.Entry<String, Double> e) -> e.getValue() - Math.floor(e.getValue()))
                    .reversed()
                    .thenComparing((a, b) -> {
                        int qtyA = machinePlanQtyMap.getOrDefault(a.getKey(), 0);
                        int qtyB = machinePlanQtyMap.getOrDefault(b.getKey(), 0);
                        return Integer.compare(qtyB, qtyA);
                    })
                    .thenComparing(Map.Entry::getKey));

            for (int i = 0; i < remainder && i < sortedEntries.size(); i++) {
                String machineCode = sortedEntries.get(i).getKey();
                resultMap.merge(machineCode, 1, Integer::sum);
            }
        }

        return resultMap;
    }

    /**
     * 对同一物料在多个机台上的排程结果，按实际排产量占比分摊余量和胎胚库存。
     * <p>会直接修改每个结果的 {@code mouldSurplusQty} 和 {@code embryoStock} 字段。
     * 调用方需保证传入的结果列表已按物料分组且排产量已最终确定。</p>
     *
     * @param materialResults  同一物料在多个机台上的排程结果列表（至少2条）
     * @param totalSurplus     该物料的总硫化余量（从SKU DTO取全量）
     * @param totalEmbryoStock 该物料的总胎胚库存（从SKU DTO取全量），≤0时跳过分摊
     */
    public static void distributeForSingleMaterial(List<LhScheduleResult> materialResults,
                                                    int totalSurplus,
                                                    int totalEmbryoStock) {
        if (CollectionUtils.isEmpty(materialResults) || materialResults.size() <= 1) {
            return;
        }
        // 构建机台 → 排产量映射
        Map<String, Integer> machinePlanQtyMap = new LinkedHashMap<>(materialResults.size());
        for (LhScheduleResult result : materialResults) {
            int planQty = ShiftFieldUtil.resolveScheduledQty(result);
            String machineCode = result.getLhMachineCode();
            if (StringUtils.isNotEmpty(machineCode)) {
                machinePlanQtyMap.put(machineCode, planQty);
            }
        }
        // 分摊余量
        if (totalSurplus > 0) {
            Map<String, Integer> surplusDistribution = distributeByProductionRatio(totalSurplus, machinePlanQtyMap);
            for (LhScheduleResult result : materialResults) {
                String machineCode = result.getLhMachineCode();
                if (StringUtils.isNotEmpty(machineCode) && surplusDistribution.containsKey(machineCode)) {
                    result.setMouldSurplusQty(surplusDistribution.get(machineCode));
                }
            }
        }
        // 分摊胎胚库存
        if (totalEmbryoStock > 0) {
            Map<String, Integer> stockDistribution = distributeByProductionRatio(totalEmbryoStock, machinePlanQtyMap);
            for (LhScheduleResult result : materialResults) {
                String machineCode = result.getLhMachineCode();
                if (StringUtils.isNotEmpty(machineCode) && stockDistribution.containsKey(machineCode)) {
                    result.setEmbryoStock(stockDistribution.get(machineCode));
                }
            }
        }
    }
}
