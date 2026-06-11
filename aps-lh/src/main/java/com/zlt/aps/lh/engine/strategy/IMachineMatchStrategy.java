/**
 * Copyright (c) 2008, 智立通（厦门）科技有限公司 All rights reserved。
 */
package com.zlt.aps.lh.engine.strategy;

import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.api.domain.dto.MachineScheduleDTO;
import com.zlt.aps.lh.api.domain.dto.SkuScheduleDTO;

import java.util.List;
import java.util.Set;

/**
 * 机台匹配策略接口
 * <p>为新上机的SKU匹配最优的可用机台</p>
 *
 * @author APS
 */
public interface IMachineMatchStrategy {

    /**
     * 匹配可用硫化机台
     * <p>
     * 规则: 收尾时间+-20分钟 -> 相同规格优先 -> 相同英寸优先 -> 相近英寸优先 -> 胶囊共用性好的优先
     * 在每个排序层级中, 胎胚共用多的优先
     * </p>
     *
     * @param context 排程上下文
     * @param sku     待排产SKU
     * @return 候选机台列表(按优先级排序)
     */
    List<MachineScheduleDTO> matchMachines(LhScheduleContext context, SkuScheduleDTO sku);

    /**
     * 从候选机台中选择最优机台
     *
     * @param context 排程上下文
     * @param sku 待排产SKU
     * @param candidates 候选机台列表
     * @param excludedMachineCodes 已尝试失败需排除的机台编码集合，只读参数，策略实现不得修改该集合
     * @return 最优机台, 无候选时返回null
     */
    MachineScheduleDTO selectBestMachine(LhScheduleContext context,
                                         SkuScheduleDTO sku,
                                         List<MachineScheduleDTO> candidates,
                                         Set<String> excludedMachineCodes);
}
