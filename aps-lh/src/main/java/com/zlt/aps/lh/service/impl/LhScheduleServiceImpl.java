package com.zlt.aps.lh.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.zlt.aps.lh.api.domain.dto.LhScheduleRequestDTO;
import com.zlt.aps.lh.api.domain.dto.LhScheduleResponseDTO;
import com.zlt.aps.lh.api.domain.entity.LhScheduleResult;
import com.zlt.aps.lh.api.enums.DeleteFlagEnum;
import com.zlt.aps.lh.api.enums.FactoryCodeEnum;
import com.zlt.aps.lh.api.enums.ReleaseStatusEnum;
import com.zlt.aps.lh.component.LhScheduleConfigResolver;
import com.zlt.aps.lh.component.ScheduleExecutionGuard;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.engine.decorator.IScheduleExecutor;
import com.zlt.aps.lh.engine.observer.ScheduleEvent;
import com.zlt.aps.lh.engine.observer.ScheduleEventPublisher;
import com.zlt.aps.lh.exception.ScheduleException;
import com.zlt.aps.lh.mapper.LhScheduleResultMapper;
import com.zlt.aps.lh.service.ILhScheduleService;
import com.zlt.aps.lh.util.LhScheduleTimeUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Date;
import java.util.List;

/**
 * 硫化排程主服务实现
 * <p>排程入口，负责构建上下文并委托给排程执行器</p>
 *
 * @author APS
 */
@Slf4j
@Service
public class LhScheduleServiceImpl implements ILhScheduleService {

    @Resource
    private IScheduleExecutor scheduleExecutor;

    @Resource
    private LhScheduleConfigResolver scheduleConfigResolver;

    @Resource
    private LhScheduleResultMapper scheduleResultMapper;

    @Resource
    private ScheduleEventPublisher scheduleEventPublisher;

    @Resource
    private ScheduleExecutionGuard scheduleExecutionGuard;

    @Override
    public LhScheduleResponseDTO executeSchedule(LhScheduleRequestDTO request) {
        log.info("接收排程请求, 工厂: {}, 日期: {}, 月计划版本: {}, 生产版本: {}",
                request.getFactoryCode(), LhScheduleTimeUtil.formatDate(request.getScheduleDate()),
                request.getMonthPlanVersion(), request.getProductionVersion());
        LhScheduleContext context = buildContext(request);
        String lockToken = null;
        try {
            log.info("准备获取排程执行锁, 工厂: {}, 目标日: {}, T日: {}, 排程天数: {}",
                    context.getFactoryCode(),
                    LhScheduleTimeUtil.formatDate(context.getScheduleTargetDate()),
                    LhScheduleTimeUtil.formatDate(context.getScheduleDate()),
                    context.getScheduleConfig().getScheduleDays());
            lockToken = scheduleExecutionGuard.acquire(context.getFactoryCode(), context.getScheduleTargetDate());
            LhScheduleResponseDTO response = scheduleExecutor.execute(context);
            log.info("排程服务执行完成, 工厂: {}, 批次号: {}, 成功: {}, 排程结果数: {}, 未排产数: {}, 模具计划数: {}",
                    context.getFactoryCode(), response.getBatchNo(), response.isSuccess(),
                    response.getScheduleResultCount(), response.getUnscheduledCount(), response.getMouldChangePlanCount());
            return response;
        } catch (ScheduleException e) {
            log.warn("排程请求被拒绝, 工厂: {}, 日期: {}, 原因: {}",
                    context.getFactoryCode(), LhScheduleTimeUtil.formatDate(context.getScheduleTargetDate()), e.getMessage());
            return LhScheduleResponseDTO.fail(context.getBatchNo(), e.getMessage());
        } catch (Exception e) {
            log.error("排程服务入口异常, 工厂: {}, 日期: {}",
                    context.getFactoryCode(), LhScheduleTimeUtil.formatDate(context.getScheduleTargetDate()), e);
            return LhScheduleResponseDTO.fail(context.getBatchNo(), "排程执行异常: " + e.getMessage());
        } finally {
            scheduleExecutionGuard.release(context.getFactoryCode(), context.getScheduleTargetDate(), lockToken);
            log.debug("排程执行锁释放完成, 工厂: {}, 目标日: {}, 锁令牌是否存在: {}",
                    context.getFactoryCode(), LhScheduleTimeUtil.formatDate(context.getScheduleTargetDate()),
                    StringUtils.isNotEmpty(lockToken));
        }
    }

    @Override
    public LhScheduleResponseDTO publishSchedule(String batchNo) {
        log.info("发布排程结果, 批次号: {}", batchNo);
        try {
            // 1. 查询批次号对应的排程结果
            List<LhScheduleResult> results = scheduleResultMapper.selectList(new LambdaQueryWrapper<LhScheduleResult>()
                    .eq(LhScheduleResult::getBatchNo, batchNo)
                    .eq(LhScheduleResult::getIsDelete, DeleteFlagEnum.NORMAL.getCode()));
            if (results == null || results.isEmpty()) {
                log.warn("发布排程结果失败, 未查询到有效排程结果, 批次号: {}", batchNo);
                return LhScheduleResponseDTO.fail(batchNo, "批次号[" + batchNo + "]对应的排程结果不存在");
            }

            // 2. 更新发布状态为"已发布"（1）
            for (LhScheduleResult result : results) {
                result.setIsRelease(ReleaseStatusEnum.RELEASED.getCode());
            }
            scheduleResultMapper.update(null, new LambdaUpdateWrapper<LhScheduleResult>()
                    .set(LhScheduleResult::getIsRelease, ReleaseStatusEnum.RELEASED.getCode())
                    .eq(LhScheduleResult::getBatchNo, batchNo)
                    .eq(LhScheduleResult::getIsDelete, DeleteFlagEnum.NORMAL.getCode()));

            // 3. 发布排程结果发布事件（通知MES系统）
            LhScheduleContext publishContext = new LhScheduleContext();
            publishContext.setBatchNo(batchNo);
            publishContext.setScheduleResultList(results);
            scheduleEventPublisher.publish(ScheduleEvent.published(publishContext));

            log.info("排程结果发布成功, 批次号: {}, 发布记录数: {}", batchNo, results.size());
            return LhScheduleResponseDTO.success(batchNo, "发布成功，共发布" + results.size() + "条记录");

        } catch (Exception e) {
            log.error("发布排程结果异常, 批次号: {}", batchNo, e);
            return LhScheduleResponseDTO.fail(batchNo, "发布失败: " + e.getMessage());
        }
    }

    /**
     * 构建排程上下文
     * <p>先解析本次排程配置快照，再按 scheduleDays 计算窗口起点 T 日</p>
     *
     * @param request 排程请求
     * @return 排程上下文
     */
    private LhScheduleContext buildContext(LhScheduleRequestDTO request) {
        LhScheduleContext context = new LhScheduleContext();
        String factoryCode = request.getFactoryCode();
        context.setFactoryCode(factoryCode);
        context.setMonthPlanVersion(StringUtils.isNotEmpty(request.getMonthPlanVersion())
                ? request.getMonthPlanVersion().trim() : request.getMonthPlanVersion());
        context.setOperator(StringUtils.isNotEmpty(request.getOperator()) ? request.getOperator().trim() : request.getOperator());
        // 工厂名称来源于工厂枚举：116=越南，117=泰国
        context.setFactoryName(FactoryCodeEnum.getFactoryNameByCode(factoryCode));
        // 请求日期为排程目标日
        Date target = LhScheduleTimeUtil.clearTime(
                request.getScheduleDate() != null ? request.getScheduleDate() : new Date());
        context.setScheduleTargetDate(target);
        scheduleConfigResolver.resolveAndAttach(context);
        int scheduleDays = context.getScheduleConfig().getScheduleDays();
        int offsetDays = Math.max(0, scheduleDays - 1);
        // 引擎使用 T 日 = 目标日 − (连续排程日历跨度 − 1)
        context.setScheduleDate(LhScheduleTimeUtil.addDays(target, -offsetDays));
        log.info("排程上下文构建完成, 工厂: {}, 工厂名称: {}, 目标日: {}, T日: {}, 排程天数: {}, 强制重排: {}, 局部搜索: {}, 定点机台规则: {}",
                context.getFactoryCode(), context.getFactoryDisplayName(),
                LhScheduleTimeUtil.formatDate(context.getScheduleTargetDate()),
                LhScheduleTimeUtil.formatDate(context.getScheduleDate()), scheduleDays,
                context.getScheduleConfig().isForceRescheduleEnabled(),
                context.getScheduleConfig().isLocalSearchEnabled(),
                context.getScheduleConfig().isSpecifyMachineRuleEnabled());
        return context;
    }
}
