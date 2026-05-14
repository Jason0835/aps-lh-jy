/**
 * Copyright (c) 2008, 智立通（厦门）科技有限公司 All rights reserved。
 */
package com.zlt.aps.lh.engine.strategy;

import com.zlt.aps.lh.context.LhScheduleContext;
import java.util.Date;

/**
 * 模具切换均衡策略接口
 * <p>控制每日模具切换总数和早/中班切换均衡，覆盖正规换模与换活字块</p>
 *
 * @author APS
 */
public interface IMouldChangeBalanceStrategy {

    /**
     * 检查指定日期的换模能力是否充足
     * <p>约束: 每日最多15台, 早班最多8台, 中班最多7台, 夜班不换模</p>
     *
     * @param context    排程上下文
     * @param targetDate 目标日期
     * @return true-有换模能力, false-已满
     */
    boolean hasCapacity(LhScheduleContext context, Date targetDate);

    /**
     * 分配模具切换到均衡的班次。
     *
     * @param context    排程上下文
     * @param machineCode 机台编号
     * @param endingTime 前SKU收尾时间
     * @return 换模分配的班次和时间
     */
    Date allocateMouldChange(LhScheduleContext context, String machineCode, Date endingTime);

    /**
     * 指定切换时长的分配入口。
     *
     * @param context 排程上下文
     * @param machineCode 机台编号
     * @param endingTime 前SKU收尾时间
     * @param switchDurationHours 切换时长（小时）
     * @return 换模分配的班次和时间
     */
    default Date allocateMouldChange(LhScheduleContext context,
                                     String machineCode,
                                     Date endingTime,
                                     int switchDurationHours) {
        return allocateMouldChange(context, machineCode, endingTime);
    }

    /**
     * 兼容旧调用方的默认入口。
     *
     * @param context 排程上下文
     * @param endingTime 前SKU收尾时间
     * @return 换模分配的班次和时间
     */
    default Date allocateMouldChange(LhScheduleContext context, Date endingTime) {
        return allocateMouldChange(context, null, endingTime);
    }

    /**
     * 回滚已占用的换模班次配额。
     *
     * @param context 排程上下文
     * @param allocatedTime 已分配的换模开始时间
     */
    default void rollbackMouldChange(LhScheduleContext context, Date allocatedTime) {
        // 默认无需处理
    }

    /**
     * 获取指定日期剩余换模能力
     *
     * @param context    排程上下文
     * @param targetDate 目标日期
     * @return 剩余换模次数
     */
    int getRemainingCapacity(LhScheduleContext context, Date targetDate);
}
