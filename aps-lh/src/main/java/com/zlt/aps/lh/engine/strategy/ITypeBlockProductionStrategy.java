/**
 * Copyright (c) 2008, 智立通（厦门）科技有限公司 All rights reserved。
 */
package com.zlt.aps.lh.engine.strategy;

import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.api.domain.dto.MachineScheduleDTO;
import com.zlt.aps.lh.api.domain.dto.SkuScheduleDTO;
import com.zlt.aps.lh.engine.strategy.support.SpecifiedMachineScheduleResult;

import java.util.Date;

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

    /**
     * 无副作用判断特殊材料在指定续作机台是否适用换活字块。
     *
     * <p>置换预演需要先确定切换时长，只有命中同胎胚、同模具等现有换活字块条件时，
     * 才能使用换活字块时长；实现不得在本方法内写入排程结果或资源账本。</p>
     *
     * @param context 排程上下文
     * @param machine 特殊材料准备接管的续作机台
     * @param sku 特殊材料 SKU
     * @return true-适用换活字块时长；false-按正规换模时长预演
     */
    default boolean isSpecialMaterialSubstitutionTypeBlockApplicable(
            LhScheduleContext context,
            MachineScheduleDTO machine,
            SkuScheduleDTO sku) {
        return false;
    }

    /**
     * 在特殊材料置换选定的续作机台上尝试换活字块排产。
     *
     * <p>历史反选必须锁定历史班次；特殊材料置换只限制“不得早于最终预演时间”，
     * 实际切换仍可按换模均衡、停机和禁换模规则继续顺延。</p>
     *
     * @param context 排程上下文
     * @param machine 特殊材料准备接管的续作机台
     * @param sku 特殊材料 SKU
     * @param earliestSwitchTime 置换预演得出的最早允许切换时间
     * @return 指定机台换活字块执行结果；不满足换活字块关系时返回不适用
     */
    default SpecifiedMachineScheduleResult tryScheduleSpecialMaterialSubstitution(
            LhScheduleContext context,
            MachineScheduleDTO machine,
            SkuScheduleDTO sku,
            Date earliestSwitchTime) {
        return SpecifiedMachineScheduleResult.notApplicable("当前换活字块策略不支持特殊材料置换模式");
    }
}
