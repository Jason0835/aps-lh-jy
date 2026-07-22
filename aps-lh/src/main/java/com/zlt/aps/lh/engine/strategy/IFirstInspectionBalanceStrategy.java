/**
 * Copyright (c) 2008, 智立通（厦门）科技有限公司 All rights reserved。
 */
package com.zlt.aps.lh.engine.strategy;

import com.zlt.aps.lh.context.LhScheduleContext;

import java.util.Date;

/**
 * 首检均衡策略接口
 * <p>将需首检任务分配到可用班次；默认实现中夜班允许首检但不占早/中班上限</p>
 *
 * @author APS
 */
public interface IFirstInspectionBalanceStrategy {

    /**
     * 无副作用预演首检均衡后的实际执行时间。
     * <p>候选机台在正式占用首检资源前使用本方法判断首检班次；实现类不得修改
     * 首检计数、排程结果或其他运行态。默认实现保持传入时间，兼容无需均衡的策略。</p>
     *
     * @param context 排程上下文
     * @param machineCode 机台编号
     * @param mouldChangeTime 换模完成及首检基准时间
     * @return 预演后的首检执行时间；无可用班次时返回null
     */
    default Date previewInspection(LhScheduleContext context,
                                   String machineCode,
                                   Date mouldChangeTime) {
        return mouldChangeTime;
    }

    /**
     * 分配首检到均衡的班次
     * <p>
     * 规则:
     * <ol>
     *   <li>收集待处理任务(换模/喷砂清洗/保养/维修, 排除干冰清洗)</li>
     *   <li>优先分配到操作数更少的班次</li>
     *   <li>中班可安排时间窗口: 14:00-20:00</li>
     *   <li>每个班首检数量可配置，参数 {@code MAX_FIRST_INSPECTION_PER_SHIFT} 为 {@code -1} 时不限制</li>
     *   <li>夜班允许首检，但不占早/中班计数</li>
     *   <li>不足则顺延到次日早班</li>
     * </ol>
     * </p>
     *
     * @param context        排程上下文
     * @param machineCode    机台编号
     * @param mouldChangeTime 换模时间
     * @return 分配后的首检开始时间
     */
    Date allocateInspection(LhScheduleContext context, String machineCode, Date mouldChangeTime);

    /**
     * 回滚已占用的首检班次配额。
     *
     * @param context 排程上下文
     * @param inspectionTime 已分配的首检时间
     */
    default void rollbackInspection(LhScheduleContext context, Date inspectionTime) {
        // 默认无需处理
    }
}
