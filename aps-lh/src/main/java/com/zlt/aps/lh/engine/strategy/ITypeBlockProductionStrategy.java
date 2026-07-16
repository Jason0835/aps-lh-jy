/**
 * Copyright (c) 2008, 智立通（厦门）科技有限公司 All rights reserved。
 */
package com.zlt.aps.lh.engine.strategy;

import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.api.domain.dto.MachineScheduleDTO;
import com.zlt.aps.lh.api.domain.dto.SkuScheduleDTO;
import com.zlt.aps.lh.engine.strategy.support.SpecifiedMachineScheduleResult;

/**
 * 换活字块排产子策略接口
 *
 * @author APS
 */
public interface ITypeBlockProductionStrategy {

    /**
     * 执行换活字块排产
     *
     * @param context 排程上下文
     */
    void scheduleTypeBlockChange(LhScheduleContext context);

    /**
     * 在指定机台尝试换活字块排产。
     *
     * <p>该入口只为历史交替计划反选提供指定机台作用域，实际候选判断、切换资源、
     * 首检、班次产能、结果和账本更新仍复用正式换活字块主链。</p>
     *
     * @param context 排程上下文
     * @param machine 指定机台
     * @param sku 目标SKU
     * @param mappedShiftIndex 历史班次映射后的当前班次
     * @return 指定机台换活字块执行结果
     */
    default SpecifiedMachineScheduleResult tryScheduleSpecifiedMachine(
            LhScheduleContext context,
            MachineScheduleDTO machine,
            SkuScheduleDTO sku,
            int mappedShiftIndex) {
        return SpecifiedMachineScheduleResult.notApplicable("当前换活字块策略不支持指定机台模式");
    }
}
