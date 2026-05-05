package com.zlt.aps.lh.handler;

import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.api.enums.ScheduleStepEnum;
import com.zlt.aps.lh.api.enums.ScheduleTypeEnum;
import com.zlt.aps.lh.engine.factory.ScheduleStrategyFactory;
import com.zlt.aps.lh.engine.strategy.ICapacityCalculateStrategy;
import com.zlt.aps.lh.engine.strategy.IFirstInspectionBalanceStrategy;
import com.zlt.aps.lh.engine.strategy.IMachineMatchStrategy;
import com.zlt.aps.lh.engine.strategy.IMouldChangeBalanceStrategy;
import com.zlt.aps.lh.engine.strategy.IProductionStrategy;
import com.zlt.aps.lh.engine.strategy.ISkuPriorityStrategy;
import com.zlt.aps.lh.util.LhScheduleTimeUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

/**
 * S4.5 新增规格排产处理器
 * <p>对新SKU进行优先级排序、机台匹配、换模均衡、首检均衡与开产时间计算</p>
 *
 * @author APS
 */
@Slf4j
@Component
public class NewProductionHandler extends AbsScheduleStepHandler {

    @Resource
    private ScheduleStrategyFactory strategyFactory;

    @Override
    protected void doHandle(LhScheduleContext context) {
        log.info("新增规格排产处理开始, 工厂: {}, 目标日: {}, 待排新增SKU: {}, 当前结果数: {}, 未排产数: {}",
                context.getFactoryCode(), LhScheduleTimeUtil.formatDate(context.getScheduleTargetDate()),
                context.getNewSpecSkuList().size(), context.getScheduleResultList().size(),
                context.getUnscheduledResultList().size());
        // S4.5.1 获取排产策略
        IProductionStrategy strategy = strategyFactory.getProductionStrategy(
                ScheduleTypeEnum.NEW_SPEC.getCode());

        // S4.5.2 SKU优先级排序
        ISkuPriorityStrategy priorityStrategy = strategyFactory.getSkuPriorityStrategy();
        priorityStrategy.sortByPriority(context);
        log.debug("新增规格SKU优先级排序完成, 待排新增SKU: {}", context.getNewSpecSkuList().size());

        // S4.5.3 遍历新增SKU, 匹配机台
        IMachineMatchStrategy machineMatchStrategy = strategyFactory.getMachineMatchStrategy();

        // S4.5.4 换模均衡校验
        IMouldChangeBalanceStrategy mouldChangeStrategy = strategyFactory.getMouldChangeBalanceStrategy();

        // S4.5.5 首检均衡分配
        IFirstInspectionBalanceStrategy inspectionStrategy = strategyFactory.getFirstInspectionBalanceStrategy();

        // S4.5.6 计算开产时间
        ICapacityCalculateStrategy capacityStrategy = strategyFactory.getCapacityCalculateStrategy();

        /** 对每个新增SKU执行以下流程:
         * 1. 按优先级排序
         * 2. 匹配可用硫化机台
         * 3. 换模次数均衡校验(早<=8, 中<=7, 总<=15)
         * 4. 首检均衡分配(早/中班操作数均衡)
         * 5. 计算开产时间(考虑保养/维修/清洗重叠)
         * 6. 分配班次计划量
         * 7. 胎胚库存校正与零计划收口
         * 8. 新增排产降模处理
         * 9. 标记未排产SKU和原因
         */
        strategy.scheduleNewSpecs(context, machineMatchStrategy,
                mouldChangeStrategy, inspectionStrategy, capacityStrategy);
        log.info("新增规格选机排产完成, 排程结果数: {}, 剩余新增SKU: {}, 未排产数: {}",
                context.getScheduleResultList().size(), context.getNewSpecSkuList().size(),
                context.getUnscheduledResultList().size());
        strategy.allocateShiftPlanQty(context);
        strategy.adjustEmbryoStock(context);
        log.info("新增规格胎胚库存调整完成, 排程结果数: {}, 剩余新增SKU: {}, 未排产数: {}",
                context.getScheduleResultList().size(), context.getNewSpecSkuList().size(),
                context.getUnscheduledResultList().size());
        strategy.scheduleReduceMould(context);
        log.info("新增规格排产处理完成, 排程结果数: {}, 未排产数: {}",
                context.getScheduleResultList().size(), context.getUnscheduledResultList().size());
    }

    @Override
    protected String getStepName() {
        return ScheduleStepEnum.S4_5_NEW_PRODUCTION.getDescription();
    }
}
