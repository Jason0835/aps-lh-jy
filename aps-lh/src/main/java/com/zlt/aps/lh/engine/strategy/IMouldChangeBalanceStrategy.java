/**
 * Copyright (c) 2008, 智立通（厦门）科技有限公司 All rights reserved。
 */
package com.zlt.aps.lh.engine.strategy;

import com.zlt.aps.lh.api.domain.dto.SkuScheduleDTO;
import com.zlt.aps.lh.context.LhScheduleContext;
import java.util.Date;

/**
 * 模具切换均衡策略接口
 * <p>控制每日模具切换总数和早/中班切换均衡，覆盖正规换模与换活字块</p>
 *
 * @author APS
 */
public interface IMouldChangeBalanceStrategy {

    /** 正规新增换模动作 */
    String ACTION_NEW_SPEC_MOULD_CHANGE = "新增换模";

    /** 提前生产首台新增换模动作 */
    String ACTION_EARLY_PRODUCTION_NEW_SPEC_MOULD_CHANGE = "提前生产新增换模";

    /** 换活字块动作 */
    String ACTION_TYPE_BLOCK_CHANGE = "换活字块";

    /** 未区分动作类型的换模/换活字块动作 */
    String ACTION_CHANGEOVER = "换模/换活字块";

    /**
     * 检查指定日期的换模能力是否充足
     * <p>启用换模均衡后每日总次数是硬限制，早班/中班次数只作为均衡参考；夜班不换模。</p>
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
     * 指定SKU和动作类型的分配入口。
     * <p>用于判断当前物料是否属于本月共用胎胚，并在日志与未排原因中区分正规换模/换活字块。</p>
     *
     * @param context 排程上下文
     * @param machineCode 机台编号
     * @param endingTime 前SKU收尾时间
     * @param switchDurationHours 切换时长（小时）
     * @param sku 当前待排SKU
     * @param actionType 切换动作类型
     * @return 换模分配的班次和时间
     */
    default Date allocateMouldChange(LhScheduleContext context,
                                     String machineCode,
                                     Date endingTime,
                                     int switchDurationHours,
                                     SkuScheduleDTO sku,
                                     String actionType) {
        return allocateMouldChange(context, machineCode, endingTime, switchDurationHours);
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
