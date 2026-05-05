/**
 * Copyright (c) 2008, 智立通（厦门）科技有限公司 All rights reserved。
 */
package com.zlt.aps.lh.engine.strategy;

import com.zlt.aps.lh.context.LhScheduleContext;

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
}
