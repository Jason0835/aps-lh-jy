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
     * 规则: 先锁定最早收尾时间后20分钟窗口，再按单控拆分 -> 同胎胚 -> 同规格 ->
     * 同模壳 -> 胶囊共用 -> 同英寸 -> 相近英寸 -> 机台编码逐层选择最优机台。
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

    /**
     * 输出续作排产后全量启用机台排序日志（不依赖具体SKU）。
     * <p>排除续作排满机台、保留续作收尾机台，按"单控优先->收尾时间->普通机台优先->特殊支持能力数"排序。</p>
     *
     * @param context 排程上下文
     */
    void traceEnabledMachineSort(LhScheduleContext context);
}
