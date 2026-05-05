package com.zlt.aps.lh.handler;

import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.api.enums.ScheduleStepEnum;
import com.zlt.aps.lh.api.enums.ScheduleTypeEnum;
import com.zlt.aps.lh.engine.factory.ScheduleStrategyFactory;
import com.zlt.aps.lh.engine.strategy.IProductionStrategy;
import com.zlt.aps.lh.engine.strategy.ISkuPriorityStrategy;
import com.zlt.aps.lh.engine.strategy.ITypeBlockProductionStrategy;
import com.zlt.aps.lh.util.LhScheduleTimeUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

/**
 * S4.4 续作规格排产处理器
 * <p>对前日延续的SKU进行产能分配、胎胚库存匹配与降模处理</p>
 *
 * @author APS
 */
@Slf4j
@Component
public class ContinuousProductionHandler extends AbsScheduleStepHandler {

    @Resource
    private ScheduleStrategyFactory strategyFactory;
    @Resource
    private ITypeBlockProductionStrategy typeBlockProductionStrategy;

    @Override
    protected void doHandle(LhScheduleContext context) {
        log.info("续作排产处理开始, 工厂: {}, 目标日: {}, 续作SKU: {}, 新增SKU: {}, 当前结果数: {}",
                context.getFactoryCode(), LhScheduleTimeUtil.formatDate(context.getScheduleTargetDate()),
                context.getContinuousSkuList().size(), context.getNewSpecSkuList().size(),
                context.getScheduleResultList().size());
        ISkuPriorityStrategy priorityStrategy = strategyFactory.getSkuPriorityStrategy();
        priorityStrategy.sortByPriority(context);
        log.debug("续作排产优先级排序完成, 续作SKU: {}, 新增SKU: {}",
                context.getContinuousSkuList().size(), context.getNewSpecSkuList().size());

        IProductionStrategy strategy = strategyFactory.getProductionStrategy(
                ScheduleTypeEnum.CONTINUOUS.getCode());

        // S4.4.1 MES在机原物料延续生产与续作收尾
        strategy.scheduleContinuousEnding(context);
        log.info("续作收尾排产完成, 排程结果数: {}, 待新增SKU: {}",
                context.getScheduleResultList().size(), context.getNewSpecSkuList().size());

        // S4.4.2 收尾后换活字块衔接排产
        typeBlockProductionStrategy.scheduleTypeBlockChange(context);
        log.info("换活字块衔接排产完成, 排程结果数: {}, 待新增SKU: {}",
                context.getScheduleResultList().size(), context.getNewSpecSkuList().size());

        // S4.4.3 班次计划量分配
        strategy.allocateShiftPlanQty(context);
        log.debug("续作班次计划量分配完成, 排程结果数: {}", context.getScheduleResultList().size());

        // S4.4.4 胎胚库存调整
        strategy.adjustEmbryoStock(context);
        log.info("续作胎胚库存调整完成, 排程结果数: {}, 未排产数: {}",
                context.getScheduleResultList().size(), context.getUnscheduledResultList().size());

        // S4.4.5 降模排产
        strategy.scheduleReduceMould(context);
        log.info("续作降模排产完成, 排程结果数: {}, 未排产数: {}",
                context.getScheduleResultList().size(), context.getUnscheduledResultList().size());
    }

    @Override
    protected String getStepName() {
        return ScheduleStepEnum.S4_4_CONTINUOUS_PRODUCTION.getDescription();
    }
}
