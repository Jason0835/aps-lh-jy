/**
 * Copyright (c) 2008, 智立通（厦门）科技有限公司 All rights reserved。
 */
package com.zlt.aps.lh.engine.strategy;

import com.zlt.aps.lh.context.LhScheduleContext;

/**
 * 排产策略接口
 * <p>定义续作排产和新增排产的标准操作</p>
 *
 * @author APS
 */
public interface IProductionStrategy {

    /**
     * 获取策略类型编码
     * <p>用于策略自注册，返回对应的排程类型代码</p>
     *
     * @return 策略类型编码（如"01"表示续作,"02"表示新增），返回null表示不自动注册
     */
    default String getStrategyType() {
        return null;
    }

    /**
     * 获取策略名称
     * <p>用于策略识别和日志记录</p>
 *
     * @return 策略名称
     */
    default String getStrategyName() {
        return this.getClass().getSimpleName();
    }

    /**
     * 续作收尾判定与排产
     *
     * @param context 排程上下文
     */
    void scheduleContinuousEnding(LhScheduleContext context);

    /**
     * 班次计划量分配
     *
     * @param context 排程上下文
     */
    void allocateShiftPlanQty(LhScheduleContext context);

    /**
     * 胎胚库存调整
     *
     * @param context 排程上下文
     */
    void adjustEmbryoStock(LhScheduleContext context);

    /**
     * 降模排产
     *
     * @param context 排程上下文
     */
    void scheduleReduceMould(LhScheduleContext context);

    /**
     * 新增规格排产(新增策略使用)
     *
     * @param context            排程上下文
     * @param machineMatch       机台匹配策略
     * @param mouldChangeBalance 换模均衡策略
     * @param inspectionBalance  首检均衡策略
     * @param capacityCalculate  产能计算策略
     */
    void scheduleNewSpecs(LhScheduleContext context,
                          IMachineMatchStrategy machineMatch,
                          IMouldChangeBalanceStrategy mouldChangeBalance,
                          IFirstInspectionBalanceStrategy inspectionBalance,
                          ICapacityCalculateStrategy capacityCalculate);
}
