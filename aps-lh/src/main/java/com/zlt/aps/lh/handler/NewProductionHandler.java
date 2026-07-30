package com.zlt.aps.lh.handler;

import com.zlt.aps.lh.api.domain.dto.MachineScheduleDTO;
import com.zlt.aps.lh.api.domain.entity.LhScheduleResult;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.api.enums.ScheduleStepEnum;
import com.zlt.aps.lh.api.enums.ScheduleTypeEnum;
import com.zlt.aps.lh.engine.factory.ScheduleStrategyFactory;
import com.zlt.aps.lh.engine.strategy.ICapacityCalculateStrategy;
import com.zlt.aps.lh.engine.strategy.IFirstInspectionBalanceStrategy;
import com.zlt.aps.lh.engine.strategy.IHistoricalMouldChangeReverseSelectionStrategy;
import com.zlt.aps.lh.engine.strategy.IMachineMatchStrategy;
import com.zlt.aps.lh.engine.strategy.IMouldChangeBalanceStrategy;
import com.zlt.aps.lh.engine.strategy.IProductionStrategy;
import com.zlt.aps.lh.engine.strategy.ISkuPriorityStrategy;
import com.zlt.aps.lh.util.LhScheduleTimeUtil;
import com.zlt.aps.lh.component.StructureMinMachineRetentionService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.Objects;

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
    @Resource
    private IHistoricalMouldChangeReverseSelectionStrategy historicalReverseSelectionStrategy;
    @Resource
    private StructureMinMachineRetentionService structureMinMachineRetentionService =
            new StructureMinMachineRetentionService();

    @Override
    protected void doHandle(LhScheduleContext context) {
        log.info("新增规格排产处理开始, 工厂: {}, 目标日: {}, 待排新增SKU: {}, 当前结果数: {}, 未排产数: {}",
                context.getFactoryCode(), LhScheduleTimeUtil.formatDate(context.getScheduleTargetDate()),
                context.getNewSpecSkuList().size(), context.getScheduleResultList().size(),
                context.getUnscheduledResultList().size());

        /*
         * S4.5 开始前冻结“排程开始时已在机且仍由原物料续作”的结果身份。
         * 共用模具联动置换和特殊材料置换都发生在 S4.5 完成后。若届时仅按机台或 scheduleType
         * 反推，会把本阶段刚生成的新增结果误当成续作候选；因此必须在新增链路写入任何结果之前
         * 先保留这份只读快照。
         */
        captureSubstitutionContinuationSnapshot(context);

        try {
            // 获取 S4.5 新增排产策略；特殊材料置换由本 Handler 完成后的独立 S4.5.1 步骤执行。
            IProductionStrategy strategy = strategyFactory.getProductionStrategy(
                    ScheduleTypeEnum.NEW_SPEC.getCode());

            // S4.5.2 SKU优先级排序
            ISkuPriorityStrategy priorityStrategy = strategyFactory.getSkuPriorityStrategy();
            priorityStrategy.sortByPriority(context);
            log.debug("新增规格SKU优先级排序完成, 待排新增SKU: {}", context.getNewSpecSkuList().size());

            /*
             * S4.5.3 前日交替计划机台反选：
             * 必须在新增SKU业务优先级排序完成后、普通新增选机前执行。反选策略会按历史班次4、5的
             * “机台+后物料”关系登记指定机台指令；目标 SKU 轮到时优先尝试该机台，但绝不改写上一步
             * 已确定的 SKU 业务排序，避免历史计划覆盖当前月计划、产品状态和排序 tie-break 规则。
             */
            historicalReverseSelectionStrategy.reverseSelect(context);
            log.info("前日交替计划机台反选完成, 排程结果数: {}, 待新增SKU: {}, 指令数: {}",
                    context.getScheduleResultList().size(), context.getNewSpecSkuList().size(),
                    context.getHistoricalReverseSelectionDirectiveList().size());

            // S4.5.4 遍历新增SKU, 匹配机台
            IMachineMatchStrategy machineMatchStrategy = strategyFactory.getMachineMatchStrategy();

            // S4.5.5 换模时间分配（开关开启时附带换模均衡）
            IMouldChangeBalanceStrategy mouldChangeStrategy = strategyFactory.getMouldChangeBalanceStrategy();

            // S4.5.6 首检均衡分配
            IFirstInspectionBalanceStrategy inspectionStrategy = strategyFactory.getFirstInspectionBalanceStrategy();

            // S4.5.7 计算开产时间
            ICapacityCalculateStrategy capacityStrategy = strategyFactory.getCapacityCalculateStrategy();

            /*
             * S4.5.8 新增排产按业务日编排：
             * 1. 保持上方已生成的SKU顺序和历史反选指令，不在日循环内重新排序；
             * 2. 将class1～class8按workDate拆为T日2班、T+1/T+2各3班；
             * 3. 每日依次执行“在机延续、当天计划/锁定、加机台、提前生产、日终结转”；
             * 4. 单个候选仍复用现有选机、换模、首检、目标量、单控和排满内核；
             * 5. 临时资源失败只登记延期，T+2 的提前生产阶段完成后再统一写最终未排；
             * 6. 三天全部完成后，才执行下方窗口级胎胚调整和后续特殊材料置换。
             *
             * 该调用仍使用IProductionStrategy原接口，数据库字段、Mapper和保存链路均不改变。
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
            /*
             * S4.5结束后只同步阶段级判断已经命中的结构状态，不重新计算是否触发保机。
             * 同结构SKU若已接管保机机台，此处负责清理旧结果中的纯保机占位、转移剩余保机区间，
             * 并把命中标识同步到本阶段新增的同结构结果。
             */
            structureMinMachineRetentionService.synchronizeRetainedState(context);
            log.info("新增规格排产处理完成, 排程结果数: {}, 未排产数: {}",
                    context.getScheduleResultList().size(), context.getUnscheduledResultList().size());
        } finally {
            /*
             * 提前生产中心视图不仅服务 scheduleNewSpecs，还要支撑后续班次分配、胎胚回裁和
             * isEnd 最终复核。必须等整个 S4.5 完成后再清理；异常退出时同样清理，避免临时
             * 前移日计划或中心目标泄漏到后续特殊材料置换及其他排程步骤。
             */
            context.clearEarlyProductionRuntimePlans();
        }
    }

    /**
     * 冻结全部后置置换允许使用的续作在机结果。
     *
     * <p>同时满足以下条件才进入快照：结果属于续作、机台存在初始在机快照、结果物料与初始在机物料一致。
     * 换活字块结果以及 S4.5 后续新增结果不会进入该集合，从数据来源上保证置换不影响新增排产。</p>
     *
     * @param context 排程上下文
     */
    private void captureSubstitutionContinuationSnapshot(LhScheduleContext context) {
        context.getSpecialMaterialContinuationResultSnapshot().clear();
        for (LhScheduleResult result : context.getScheduleResultList()) {
            if (Objects.isNull(result)
                    || !ScheduleTypeEnum.CONTINUOUS.getCode().equals(result.getScheduleType())
                    || StringUtils.isEmpty(result.getLhMachineCode())
                    || StringUtils.isEmpty(result.getMaterialCode())) {
                continue;
            }
            MachineScheduleDTO initialMachine =
                    context.getInitialMachineScheduleMap().get(result.getLhMachineCode());
            if (Objects.isNull(initialMachine)
                    || !StringUtils.equals(initialMachine.getCurrentMaterialCode(), result.getMaterialCode())) {
                continue;
            }
            context.getSpecialMaterialContinuationResultSnapshot().add(result);
        }
        log.info("后置置换续作在机快照完成, 工厂: {}, 批次: {}, 续作结果数: {}",
                context.getFactoryCode(), context.getBatchNo(),
                context.getSpecialMaterialContinuationResultSnapshot().size());
    }

    @Override
    protected String getStepName() {
        return ScheduleStepEnum.S4_5_NEW_PRODUCTION.getDescription();
    }
}
