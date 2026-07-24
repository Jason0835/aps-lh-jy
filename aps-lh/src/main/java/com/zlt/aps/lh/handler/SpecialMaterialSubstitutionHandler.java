package com.zlt.aps.lh.handler;

import com.zlt.aps.lh.api.enums.ScheduleStepEnum;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.service.impl.SpecialMaterialMachineSubstitutionService;
import com.zlt.aps.lh.util.LhScheduleTimeUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

/**
 * S4.5.1 特殊材料硫化机置换处理器。
 *
 * <p>业务定位：</p>
 * <ul>
 *   <li>在 S4.4 续作、S4.5 新增排产全部完成后执行；</li>
 *   <li>扫描未排结果中仍未排上机台的特殊材料 SKU，触发硫化机置换兜底逻辑；</li>
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

    /**
     * 执行特殊材料硫化机置换。
     *
     * <p>流程：</p>
     * <ol>
     *   <li>识别未排结果中仍未排上机台的特殊材料 SKU；</li>
     *   <li>排除已在排程结果中排上机台的特殊材料 SKU；</li>
     *   <li>按优先级排序待置换特殊材料 SKU；</li>
     *   <li>按加机台规则计算需求台数，并按喷砂、月计划降模、维保、胎胚库存低四层选择续作候选；</li>
     *   <li>逐台执行指定机台预演，成功后局部截断续作、恢复截断量并提交特殊材料结果；</li>
     *   <li>置换失败时恢复候选前状态并记录明确未排原因。</li>
     * </ol>
     *
     * @param context 排程上下文
     */
    @Override
    protected void doHandle(LhScheduleContext context) {
        log.info("特殊材料硫化机置换处理开始, 工厂: {}, 目标日: {}, 当前排程结果数: {}, 未排产数: {}",
                context.getFactoryCode(), LhScheduleTimeUtil.formatDate(context.getScheduleTargetDate()),
                context.getScheduleResultList().size(), context.getUnscheduledResultList().size());

        // 执行候选级预演与原子提交；服务内部保证失败候选不会污染既有续作和新增排产结果。
        substitutionService.substitute(context);

        log.info("特殊材料硫化机置换处理完成, 排程结果数: {}, 未排产数: {}",
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
