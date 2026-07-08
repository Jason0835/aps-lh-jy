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
 *   <li>置换不参与前置抢机台，而是作为排程末尾的补偿机制，从已排 SKU 的机台中选择一台进行置换；</li>
 *   <li>置换后特殊材料 SKU 按现有排程规则上机，被置换 SKU 下机并记录未排原因。</li>
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
     *   <li>逐个执行置换：选机 -> 被置换SKU下机 -> 特殊材料SKU上机 -> 生成模具交替计划备注；</li>
     *   <li>置换失败时记录明确未排原因。</li>
     * </ol>
     *
     * @param context 排程上下文
     */
    @Override
    protected void doHandle(LhScheduleContext context) {
        log.info("特殊材料硫化机置换处理开始, 工厂: {}, 目标日: {}, 当前排程结果数: {}, 未排产数: {}",
                context.getFactoryCode(), LhScheduleTimeUtil.formatDate(context.getScheduleTargetDate()),
                context.getScheduleResultList().size(), context.getUnscheduledResultList().size());

        // 执行特殊材料硫化机置换兜底逻辑
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
