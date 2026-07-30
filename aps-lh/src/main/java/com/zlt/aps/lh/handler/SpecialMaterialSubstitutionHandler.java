package com.zlt.aps.lh.handler;

import com.zlt.aps.lh.api.enums.ScheduleStepEnum;
import com.zlt.aps.lh.component.StructureMinMachineRetentionService;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.service.impl.SpecialMaterialMachineSubstitutionService;
import com.zlt.aps.lh.service.impl.SharedMouldSubstitutionCoordinator;
import com.zlt.aps.lh.util.LhScheduleTimeUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

/**
 * S4.5.1 后置联动置换处理器。
 *
 * <p>业务定位：</p>
 * <ul>
 *   <li>在 S4.4 续作、S4.5 新增排产全部完成后执行；</li>
 *   <li>先扫描所有无空闲模具 SKU，尝试“B 成功迁移后 A 接管”的共用模具联动置换；</li>
 *   <li>再扫描仍未排上机台的特殊材料 SKU，触发原硫化机置换兜底逻辑；</li>
 *   <li>置换不参与前置抢机台，仅从 S4.5 前冻结的续作在机结果中选择候选，新增排产机台不得参与；</li>
 *   <li>每台候选先执行无副作用预演，确认特殊材料可在指定机台产生正计划量后，再按实际切换时点局部截断续作；</li>
 *   <li>被截断数量恢复到原续作余量和未排结果，候选失败时完整回滚并继续尝试下一台。</li>
 * </ul>
 *
 * <p>注意：该 Handler 不修改续作/换活字块/新增排产的主流程逻辑，仅在后处理阶段执行置换补偿。</p>
 *
 * @author APS
 */
@Slf4j
@Component
public class SpecialMaterialSubstitutionHandler extends AbsScheduleStepHandler {

    @Resource
    private SpecialMaterialMachineSubstitutionService substitutionService;
    /** 所有 SKU 共用模具联动置换协调器，优先于原特殊材料机台置换兜底执行。 */
    @Resource
    private SharedMouldSubstitutionCoordinator sharedMouldSubstitutionCoordinator;
    @Resource
    private StructureMinMachineRetentionService structureMinMachineRetentionService;

    /**
     * 执行共用模具联动置换及特殊材料硫化机兜底置换。
     *
     * <p>流程：</p>
     * <ol>
     *   <li>对全部未排 SKU 执行共用模具硬门槛判断和 A/B 完整预演；</li>
     *   <li>预演确认 B 能携剩余模具迁移后，原子提交 A 接管和 B 重新上机；</li>
     *   <li>识别共用模具置换后仍未排上机台的特殊材料 SKU；</li>
     *   <li>按原优先级执行喷砂、月计划降模、维保、胎胚库存低四层候选置换；</li>
     *   <li>任一候选失败时恢复候选前状态并记录明确失败原因。</li>
     * </ol>
     *
     * @param context 排程上下文
     */
    @Override
    protected void doHandle(LhScheduleContext context) {
        log.info("S4.5.1 后置联动置换处理开始, 工厂: {}, 目标日: {}, 当前排程结果数: {}, 未排产数: {}",
                context.getFactoryCode(), LhScheduleTimeUtil.formatDate(context.getScheduleTargetDate()),
                context.getScheduleResultList().size(), context.getUnscheduledResultList().size());

        /*
         * 先执行“SKU 无空闲模具”的共用模具联动置换。
         * 协调器只使用 S4.5 前冻结的真实续作机台，并在确认 B 能携剩余模具完整迁移后才提交 A 接管；
         * 任一步失败均整体回滚，因此不会污染后续原特殊材料兜底。
         */
        sharedMouldSubstitutionCoordinator.substitute(context);
        /*
         * 共用模具联动结束后，剩余特殊材料继续执行原有喷砂、降模、维保和胎胚库存优先级置换，
         * 保持本需求之外的特殊材料业务语义不变。
         */
        substitutionService.substitute(context);
        // 特殊材料同结构接管成功后，统一清理旧保机占位并把剩余保机区间转移到新结果。
        structureMinMachineRetentionService.synchronizeRetainedState(context);

        log.info("S4.5.1 后置联动置换处理完成, 排程结果数: {}, 未排产数: {}",
                context.getScheduleResultList().size(), context.getUnscheduledResultList().size());
    }

    /**
     * 获取步骤名称。
     *
     * @return 步骤描述
     */
    @Override
    protected String getStepName() {
        return ScheduleStepEnum.S4_5_1_SPECIAL_MATERIAL_SUBSTITUTION.getDescription();
    }
}
