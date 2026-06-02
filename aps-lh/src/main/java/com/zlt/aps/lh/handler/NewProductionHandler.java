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
 * S4.5 新增规格排产处理器。
 *
 * <p>业务定位：</p>
 * <ul>
 *   <li>处理 S4.4 未消费的新增 SKU，按排序结果逐个尝试上机；</li>
     *   <li>串联 SKU 排序、机台匹配、基础换模时间分配、可选换模均衡、首检均衡、开产时间计算和新增策略落地；</li>
 *   <li>新增排产会同时写入排程结果、机台运行态、未排原因、日计划账本和同胎胚换模占用。</li>
 * </ul>
 *
 * <p>注意：试制、量试、小批量、正规 SKU 的差异主要在排序 tie-break、单控机台约束、
 * 严格目标量和班次补满策略中体现，不应在本 Handler 中新增并行业务分支。</p>
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

        // S4.5.4 换模时间分配（开关开启时附带换模均衡）
        IMouldChangeBalanceStrategy mouldChangeStrategy = strategyFactory.getMouldChangeBalanceStrategy();

        // S4.5.5 首检均衡分配
        IFirstInspectionBalanceStrategy inspectionStrategy = strategyFactory.getFirstInspectionBalanceStrategy();

        // S4.5.6 计算开产时间
        ICapacityCalculateStrategy capacityStrategy = strategyFactory.getCapacityCalculateStrategy();

        /** 对每个新增SKU执行以下流程:
         * 1. 按优先级排序
         * 2. 匹配可用硫化机台
         * 3. 基础换模时间分配（可选换模均衡：早<=8, 中<=7, 总<=15）
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
