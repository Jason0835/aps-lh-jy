/**
 * Copyright (c) 2008, 智立通（厦门）科技有限公司 All rights reserved。
 */
package com.zlt.aps.lh.engine.factory;

import com.zlt.aps.lh.engine.strategy.ICapacityCalculateStrategy;
import com.zlt.aps.lh.engine.strategy.IEndingJudgmentStrategy;
import com.zlt.aps.lh.engine.strategy.IFirstInspectionBalanceStrategy;
import com.zlt.aps.lh.engine.strategy.IInsertOrderStrategy;
import com.zlt.aps.lh.engine.strategy.IMachineMatchStrategy;
import com.zlt.aps.lh.engine.strategy.IMouldChangeBalanceStrategy;
import com.zlt.aps.lh.engine.strategy.IProductionShutdownStrategy;
import com.zlt.aps.lh.engine.strategy.IProductionStrategy;
import com.zlt.aps.lh.engine.strategy.ISkuPriorityStrategy;
import com.zlt.aps.lh.engine.strategy.ITrialProductionStrategy;
import com.zlt.aps.lh.exception.ScheduleErrorCode;
import com.zlt.aps.lh.exception.ScheduleException;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 硫化排程策略工厂。
 *
 * <p>业务定位：</p>
 * <ul>
 *   <li>集中管理续作、新增、机台匹配、SKU排序、换模均衡、首检均衡、产能计算等策略 Bean；</li>
 *   <li>排产策略按 {@code scheduleType} 自注册，S4.4/S4.5 Handler 通过工厂获取对应策略；</li>
 *   <li>单例策略通过明确 getter 提供给 Handler，避免业务入口直接依赖具体实现类。</li>
 * </ul>
 *
 * <p>注意：该类只负责策略获取和注册，不承载排程规则。新增策略时必须保证策略类型与
 * {@code ScheduleTypeEnum} 或调用方约定一致。</p>
 *
 * @author APS
 */
@Slf4j
@Component
public class ScheduleStrategyFactory {

    /** 排产策略Map（Spring自动注入所有实现） */
    @Resource
    private List<IProductionStrategy> productionStrategies;

    /** 策略缓存：按策略类型编码索引 */
    private final Map<String, IProductionStrategy> productionStrategyCache = new ConcurrentHashMap<>();

    /** 策略缓存：按策略Bean名称索引 */
    private final Map<String, IProductionStrategy> productionStrategyByName = new ConcurrentHashMap<>();

    // ==================== 单例策略（每种类型只有一个默认实现） ====================

    @Resource
    private IMachineMatchStrategy machineMatchStrategy;

    @Resource
    private ISkuPriorityStrategy skuPriorityStrategy;

    @Resource
    private IFirstInspectionBalanceStrategy firstInspectionBalanceStrategy;

    @Resource
    private IMouldChangeBalanceStrategy mouldChangeBalanceStrategy;

    @Resource
    private ICapacityCalculateStrategy capacityCalculateStrategy;

    @Resource
    private IProductionShutdownStrategy productionShutdownStrategy;

    @Resource
    private ITrialProductionStrategy trialProductionStrategy;

    @Resource
    private IInsertOrderStrategy insertOrderStrategy;

    @Resource
    private IEndingJudgmentStrategy endingJudgmentStrategy;


    /**
     * 初始化：自动注册所有排产策略
     * <p>
     * 改进：使用策略自注册机制，无需硬编码策略名称
     * </p>
     */
    @PostConstruct
    public void init() {
        // 自动注册排产策略
        if (productionStrategies != null) {
            for (IProductionStrategy strategy : productionStrategies) {
                registerProductionStrategy(strategy);
            }
        }

        log.info("排程策略工厂初始化完成, 排产策略数: {}, 策略列表: {}",
                productionStrategyCache.size(), productionStrategyCache.keySet());
    }

    /**
     * 注册排产策略
     *
     * @param strategy 策略实例
     */
    private void registerProductionStrategy(IProductionStrategy strategy) {
        String strategyType = strategy.getStrategyType();
        String strategyName = strategy.getStrategyName();

        if (strategyType != null && !strategyType.isEmpty()) {
            productionStrategyCache.put(strategyType, strategy);
            log.debug("注册排产策略: type={}, name={}", strategyType, strategyName);
        }

        if (strategyName != null && !strategyName.isEmpty()) {
            productionStrategyByName.put(strategyName, strategy);
        }
    }

    // ==================== 排产策略获取 ====================

    /**
     * 根据排程类型获取排产策略
     *
     * @param scheduleType 排程类型代码(01-续作, 02-新增)
     * @return 对应的排产策略实现
     * @throws ScheduleException 未找到对应策略时抛出
     */
    public IProductionStrategy getProductionStrategy(String scheduleType) {
        if (StringUtils.isEmpty(scheduleType)) {
            throw new ScheduleException(ScheduleErrorCode.PRODUCTION_STRATEGY_NOT_REGISTERED, "排程类型不能为空");
        }
        IProductionStrategy strategy = productionStrategyCache.get(scheduleType);
        if (strategy == null) {
            throw new ScheduleException(ScheduleErrorCode.PRODUCTION_STRATEGY_NOT_REGISTERED,
                    "未找到排程类型[" + scheduleType + "]对应的排产策略，已注册策略: " + productionStrategyCache.keySet());
        }
        return strategy;
    }

    /**
     * 根据策略名称获取排产策略
     * <p>用于获取特定命名的策略实现</p>
     *
     * @param strategyName 策略Bean名称
     * @return 排产策略实现
     * @throws ScheduleException 名称为空或未注册对应 Bean 时抛出
     */
    public IProductionStrategy getProductionStrategyByName(String strategyName) {
        if (StringUtils.isEmpty(strategyName)) {
            throw new ScheduleException(ScheduleErrorCode.PRODUCTION_STRATEGY_NOT_REGISTERED, "排产策略 Bean 名称不能为空");
        }
        IProductionStrategy strategy = productionStrategyByName.get(strategyName);
        if (strategy == null) {
            throw new ScheduleException(ScheduleErrorCode.PRODUCTION_STRATEGY_NOT_REGISTERED,
                    "未找到 Bean 名称[" + strategyName + "]对应的排产策略，已注册: " + productionStrategyByName.keySet());
        }
        return strategy;
    }

    // ==================== 其他策略获取 ====================

    public IMachineMatchStrategy getMachineMatchStrategy() {
        return machineMatchStrategy;
    }

    public ISkuPriorityStrategy getSkuPriorityStrategy() {
        return skuPriorityStrategy;
    }

    public IFirstInspectionBalanceStrategy getFirstInspectionBalanceStrategy() {
        return firstInspectionBalanceStrategy;
    }

    public IMouldChangeBalanceStrategy getMouldChangeBalanceStrategy() {
        return mouldChangeBalanceStrategy;
    }

    public ICapacityCalculateStrategy getCapacityCalculateStrategy() {
        return capacityCalculateStrategy;
    }

    public IProductionShutdownStrategy getProductionShutdownStrategy() {
        return productionShutdownStrategy;
    }

    public ITrialProductionStrategy getTrialProductionStrategy() {
        return trialProductionStrategy;
    }

    public IInsertOrderStrategy getInsertOrderStrategy() {
        return insertOrderStrategy;
    }

    public IEndingJudgmentStrategy getEndingJudgmentStrategy() {
        return endingJudgmentStrategy;
    }



}
