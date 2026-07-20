package com.zlt.aps.lh.handler;

import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.api.enums.ScheduleStepEnum;
import com.zlt.aps.lh.api.enums.ScheduleTypeEnum;
import com.zlt.aps.lh.engine.factory.ScheduleStrategyFactory;
import com.zlt.aps.lh.engine.strategy.IProductionStrategy;
import com.zlt.aps.lh.engine.strategy.IHistoricalMouldChangeReverseSelectionStrategy;
import com.zlt.aps.lh.engine.strategy.ISkuPriorityStrategy;
import com.zlt.aps.lh.engine.strategy.ITypeBlockProductionStrategy;
import com.zlt.aps.lh.util.LhScheduleTimeUtil;
import com.zlt.aps.lh.component.StructureMinMachineRetentionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

/**
 * S4.4 续作规格排产处理器。
 *
 * <p>业务定位：</p>
 * <ul>
 *   <li>承接 S4.3 分类出的续作 SKU，优先处理 MES 在机/滚动继承规格；</li>
 *   <li>组织续作收尾、换活字块衔接、班次计划量分配、胎胚库存调整和降模排产；</li>
 *   <li>执行顺序早于 S4.5 新增排产，避免新增规格抢占本应续作收尾的机台窗口。</li>
 * </ul>
 *
 * <p>该 Handler 只负责步骤编排，具体续作目标量、换活字块匹配、同 SKU 多机台降模等规则
 * 分别下沉到 {@code ContinuousProductionStrategy} 和 {@code TypeBlockProductionStrategy}。</p>
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
    @Resource
    private IHistoricalMouldChangeReverseSelectionStrategy historicalReverseSelectionStrategy;
    @Resource
    private StructureMinMachineRetentionService structureMinMachineRetentionService =
            new StructureMinMachineRetentionService();

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

        // S4.4.1 MES在机原物料延续生产与续作收尾：先处理原机台可持续生产的规格。
        strategy.scheduleContinuousEnding(context);
        log.info("续作收尾排产完成, 排程结果数: {}, 待新增SKU: {}",
                context.getScheduleResultList().size(), context.getNewSpecSkuList().size());

        // S4.4.2 班次计划量分配：续作策略中部分结果已携带班次量，此处保留统一策略入口。
        strategy.allocateShiftPlanQty(context);
        log.debug("续作班次计划量分配完成, 排程结果数: {}", context.getScheduleResultList().size());

        // S4.4.3 胎胚库存调整：按 SKU 维度库存裁剪，避免同胎胚多个 SKU 共享库存导致超排。
        strategy.adjustEmbryoStock(context);
        log.info("续作胎胚库存调整完成, 排程结果数: {}, 未排产数: {}",
                context.getScheduleResultList().size(), context.getUnscheduledResultList().size());

        // S4.4.4 降模排产：对同 SKU 多机台续作按 dayN 保障量和收尾规则释放冗余机台。
        strategy.scheduleReduceMould(context);
        log.info("续作降模排产完成, 排程结果数: {}, 未排产数: {}",
                context.getScheduleResultList().size(), context.getUnscheduledResultList().size());

        // 续作数量和降模结果稳定后先执行结构机台保护，禁止提前收尾机台进入换活字块候选。
        structureMinMachineRetentionService.refreshRetention(context);

        // S4.4.5 收尾后换活字块衔接排产：必须放在降模之后，读取续作最终收口后的真实机台可用时间。
        typeBlockProductionStrategy.scheduleTypeBlockChange(context);
        // 换活字块可能移出或回写待新增SKU，需重新构建结构视图供 S4.5 新增排序使用。
        context.rebuildStructureSkuMapFromPending(context.getNewSpecSkuList());
        // 换活字块落地后再次按最终待排结构视图刷新，已完成结构立即补0并统一延迟释放时间。
        structureMinMachineRetentionService.refreshRetention(context);
        log.info("换活字块衔接排产完成, 排程结果数: {}, 待新增SKU: {}",
                context.getScheduleResultList().size(), context.getNewSpecSkuList().size());

        /*
         * S4.4.6 前日交替计划机台反选：
         * 必须在续作和普通换活字块已经落地后执行，先识别“前序已满足”；同时必须早于S4.5普通新增选机，
         * 使历史班次4、5的“机台+后物料”关系拥有一次不参与普通候选排序的指定机台机会。
         */
        historicalReverseSelectionStrategy.reverseSelect(context);
        // 反选可能完成换活字块或调整待新增SKU先后顺序，再次刷新结构视图供S4.5读取。
        context.rebuildStructureSkuMapFromPending(context.getNewSpecSkuList());
        // 历史反选同样可能消费最后一个结构SKU，进入普通新增选机前必须刷新机台保护状态。
        structureMinMachineRetentionService.refreshRetention(context);
        log.info("前日交替计划机台反选完成, 排程结果数: {}, 待新增SKU: {}, 指令数: {}",
                context.getScheduleResultList().size(), context.getNewSpecSkuList().size(),
                context.getHistoricalReverseSelectionDirectiveList().size());

        // S4.4.7 续作后全量启用机台排序日志：排除续作排满机台、保留续作收尾机台，不依赖具体SKU。
        strategyFactory.getMachineMatchStrategy().traceEnabledMachineSort(context);
    }

    @Override
    protected String getStepName() {
        return ScheduleStepEnum.S4_4_CONTINUOUS_PRODUCTION.getDescription();
    }
}
