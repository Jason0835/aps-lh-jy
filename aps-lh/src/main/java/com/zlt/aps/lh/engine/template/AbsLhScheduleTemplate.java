package com.zlt.aps.lh.engine.template;

import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.api.domain.dto.LhScheduleResponseDTO;
import com.zlt.aps.lh.api.enums.ScheduleStepEnum;
import com.zlt.aps.lh.exception.ScheduleException;
import com.zlt.aps.lh.util.LhScheduleTimeUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 硫化排程模板方法抽象类
 * <p>定义排程的标准六步流程骨架，子类实现各步骤的具体逻辑</p>
 *
 * <pre>
 * 流程: S4.1前置校验 -> S4.2数据初始化 -> S4.3排程调整与SKU归集
 *       -> S4.4续作规格排产 -> S4.5新增规格排产 -> S4.6结果校验与发布保存
 * </pre>
 *
 * @author APS
 */
@Slf4j
public abstract class AbsLhScheduleTemplate {

    /**
     * 执行排程(模板方法) - 定义不可变的算法骨架
     *
     * @param context 排程上下文
     * @return 排程响应结果
     */
    public final LhScheduleResponseDTO execute(LhScheduleContext context) {
        long startTime = System.currentTimeMillis();
        log.info("========== 硫化排程开始, 工厂:{}, 目标日:{}, T日:{} ==========",
                context.getFactoryCode(),
                LhScheduleTimeUtil.formatDate(context.getScheduleTargetDate()),
                LhScheduleTimeUtil.formatDate(context.getScheduleDate()));
        try {
            // S4.1 前置校验与数据清理
            context.setCurrentStep(ScheduleStepEnum.S4_1_PRE_VALIDATION.getCode());
            log.info(">>> 步骤 S4.1: {}", ScheduleStepEnum.S4_1_PRE_VALIDATION.getDescription());
            doPreValidation(context);
            logStepSnapshot(context, ScheduleStepEnum.S4_1_PRE_VALIDATION);
            if (context.isInterrupted()) {
                return buildInterruptResponse(context);
            }

            // S4.2 基础数据初始化
            context.setCurrentStep(ScheduleStepEnum.S4_2_DATA_INIT.getCode());
            log.info(">>> 步骤 S4.2: {}", ScheduleStepEnum.S4_2_DATA_INIT.getDescription());
            doDataInitialization(context);
            logStepSnapshot(context, ScheduleStepEnum.S4_2_DATA_INIT);
            if (context.isInterrupted()) {
                return buildInterruptResponse(context);
            }

            // S4.3 排程调整与SKU归集
            context.setCurrentStep(ScheduleStepEnum.S4_3_ADJUST_AND_GATHER.getCode());
            log.info(">>> 步骤 S4.3: {}", ScheduleStepEnum.S4_3_ADJUST_AND_GATHER.getDescription());
            doAdjustAndGather(context);
            logStepSnapshot(context, ScheduleStepEnum.S4_3_ADJUST_AND_GATHER);
            if (context.isInterrupted()) {
                return buildInterruptResponse(context);
            }

            // S4.4 续作规格排产
            context.setCurrentStep(ScheduleStepEnum.S4_4_CONTINUOUS_PRODUCTION.getCode());
            log.info(">>> 步骤 S4.4: {}", ScheduleStepEnum.S4_4_CONTINUOUS_PRODUCTION.getDescription());
            doContinuousProduction(context);
            logStepSnapshot(context, ScheduleStepEnum.S4_4_CONTINUOUS_PRODUCTION);
            if (context.isInterrupted()) {
                return buildInterruptResponse(context);
            }

            // S4.5 新增规格排产
            context.setCurrentStep(ScheduleStepEnum.S4_5_NEW_PRODUCTION.getCode());
            log.info(">>> 步骤 S4.5: {}", ScheduleStepEnum.S4_5_NEW_PRODUCTION.getDescription());
            doNewSpecProduction(context);
            logStepSnapshot(context, ScheduleStepEnum.S4_5_NEW_PRODUCTION);
            if (context.isInterrupted()) {
                return buildInterruptResponse(context);
            }

            // S4.6 结果校验与发布保存
            context.setCurrentStep(ScheduleStepEnum.S4_6_RESULT_VALIDATION.getCode());
            log.info(">>> 步骤 S4.6: {}", ScheduleStepEnum.S4_6_RESULT_VALIDATION.getDescription());
            doResultValidationAndSave(context);
            logStepSnapshot(context, ScheduleStepEnum.S4_6_RESULT_VALIDATION);

            return buildSuccessResponse(context);
        } catch (ScheduleException e) {
            log.error("硫化排程领域异常, 当前步骤:{}", context.getCurrentStep(), e);
            String batchNo = context.getBatchNo();
            if (StringUtils.isEmpty(batchNo)) {
                batchNo = e.getBatchNo();
            }
            return LhScheduleResponseDTO.fail(batchNo, e.getMessage());
        } catch (Exception e) {
            log.error("硫化排程执行异常, 当前步骤:{}", context.getCurrentStep(), e);
            return LhScheduleResponseDTO.fail(context.getBatchNo(), "排程执行异常: " + e.getMessage());
        } finally {
            long elapsed = System.currentTimeMillis() - startTime;
            log.info("========== 硫化排程结束, 批次号:{}, 排程结果:{}, 未排:{}, 模具计划:{}, 耗时:{}ms ==========",
                    context.getBatchNo(),
                    context.getScheduleResultList().size(),
                    context.getUnscheduledResultList().size(),
                    context.getMouldChangePlanList().size(),
                    elapsed);
        }
    }

    /** S4.1 前置校验与数据清理 */
    protected abstract void doPreValidation(LhScheduleContext context);

    /** S4.2 基础数据初始化 */
    protected abstract void doDataInitialization(LhScheduleContext context);

    /** S4.3 排程调整与SKU归集 */
    protected abstract void doAdjustAndGather(LhScheduleContext context);

    /** S4.4 续作规格排产 */
    protected abstract void doContinuousProduction(LhScheduleContext context);

    /** S4.5 新增规格排产 */
    protected abstract void doNewSpecProduction(LhScheduleContext context);

    /** S4.6 结果校验与发布保存 */
    protected abstract void doResultValidationAndSave(LhScheduleContext context);

    /**
     * 构建中断响应
     *
     * @param context 排程上下文
     * @return 中断响应DTO
     */
    private LhScheduleResponseDTO buildInterruptResponse(LhScheduleContext context) {
        log.warn("排程在步骤[{}]被中断, 原因: {}", context.getCurrentStep(), context.getInterruptReason());
        String message = "排程中断[" + context.getCurrentStep() + "]: " + context.getInterruptReason();
        LhScheduleResponseDTO response = LhScheduleResponseDTO.fail(context.getBatchNo(), message);
        List<String> validationErrors = context.getValidationErrorList();
        if (validationErrors != null && !validationErrors.isEmpty()) {
            response.setValidationErrors(new ArrayList<>(validationErrors));
        }
        return response;
    }

    /**
     * 构建成功响应
     *
     * @param context 排程上下文
     * @return 成功响应DTO
     */
    private LhScheduleResponseDTO buildSuccessResponse(LhScheduleContext context) {
        LhScheduleResponseDTO response = LhScheduleResponseDTO.success(context.getBatchNo(), "排程完成");
        response.setScheduleResultCount(context.getScheduleResultList().size());
        response.setUnscheduledCount(context.getUnscheduledResultList().size());
        response.setMouldChangePlanCount(context.getMouldChangePlanList().size());
        return response;
    }

    /**
     * 输出步骤执行后的关键上下文快照。
     *
     * @param context 排程上下文
     * @param stepEnum 当前步骤
     * @return void
     */
    private void logStepSnapshot(LhScheduleContext context, ScheduleStepEnum stepEnum) {
        log.info("<<< 步骤 {} 完成, 工厂: {}, 目标日: {}, 批次号: {}, 机台数: {}, 续作SKU: {}, 新增SKU: {}, 排程结果: {}, 未排: {}",
                stepEnum.getCode(), context.getFactoryCode(),
                LhScheduleTimeUtil.formatDate(context.getScheduleTargetDate()),
                context.getBatchNo(), context.getMachineScheduleMap().size(),
                context.getContinuousSkuList().size(), context.getNewSpecSkuList().size(),
                context.getScheduleResultList().size(), context.getUnscheduledResultList().size());
    }
}
