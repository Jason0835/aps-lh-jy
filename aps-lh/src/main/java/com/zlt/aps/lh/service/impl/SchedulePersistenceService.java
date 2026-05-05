package com.zlt.aps.lh.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zlt.aps.lh.api.domain.entity.LhMouldChangePlan;
import com.zlt.aps.lh.api.domain.entity.LhScheduleProcessLog;
import com.zlt.aps.lh.api.domain.entity.LhScheduleResult;
import com.zlt.aps.lh.api.domain.entity.LhUnscheduledResult;
import com.zlt.aps.lh.api.enums.DeleteFlagEnum;
import com.zlt.aps.lh.api.enums.ScheduleStepEnum;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.exception.ScheduleErrorCode;
import com.zlt.aps.lh.exception.ScheduleException;
import com.zlt.aps.lh.mapper.LhMouldChangePlanEntityMapper;
import com.zlt.aps.lh.mapper.LhScheduleProcessLogMapper;
import com.zlt.aps.lh.mapper.LhScheduleResultMapper;
import com.zlt.aps.lh.mapper.LhUnscheduledResultMapper;
import com.zlt.aps.lh.service.ILhScheduleResultService;
import com.zlt.aps.lh.util.LhScheduleTimeUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 排程结果原子替换持久化服务。
 *
 * <p>统一负责二次发布校验、删除旧数据并写入新批次结果，确保同一目标日只出现一套完整数据。</p>
 *
 * @author APS
 */
@Slf4j
@Service
public class SchedulePersistenceService {

    private static final String DEFAULT_OPERATOR = "system";

    @Resource
    private LhScheduleResultMapper scheduleResultMapper;

    @Resource
    private LhUnscheduledResultMapper unscheduledResultMapper;

    @Resource
    private LhMouldChangePlanEntityMapper mouldChangePlanMapper;

    @Resource
    private LhScheduleProcessLogMapper processLogMapper;

    @Resource
    private ILhScheduleResultService scheduleResultService;

    /**
     * 以事务方式原子替换目标日排程结果。
     *
     * @param context 排程上下文
     */
    @Transactional(rollbackFor = Exception.class)
    public void replaceScheduleAtomically(LhScheduleContext context) {
        Date targetDate = LhScheduleTimeUtil.clearTime(context.getScheduleTargetDate());
        String factoryCode = context.getFactoryCode();

        int releasedCount = scheduleResultService.countReleasedByDate(targetDate, factoryCode);
        if (releasedCount > 0) {
            throw new ScheduleException(ScheduleStepEnum.S4_6_RESULT_VALIDATION,
                    ScheduleErrorCode.MES_RELEASED,
                    factoryCode, context.getBatchNo(),
                    "目标日已有已发布排程，禁止覆盖。排程日期: " + LhScheduleTimeUtil.getDateStr(targetDate));
        }

        List<LhScheduleResult> oldResults = scheduleResultMapper.selectList(
                new LambdaQueryWrapper<LhScheduleResult>()
                        .eq(LhScheduleResult::getFactoryCode, factoryCode)
                        .eq(LhScheduleResult::getScheduleDate, targetDate)
                        .eq(LhScheduleResult::getIsDelete, DeleteFlagEnum.NORMAL.getCode()));

        Set<String> oldBatchNos = oldResults.stream()
                .map(LhScheduleResult::getBatchNo)
                .filter(StringUtils::isNotBlank)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        int deletedResultCount = scheduleResultMapper.delete(
                new LambdaQueryWrapper<LhScheduleResult>()
                        .eq(LhScheduleResult::getFactoryCode, factoryCode)
                        .eq(LhScheduleResult::getScheduleDate, targetDate)
                        .eq(LhScheduleResult::getIsDelete, DeleteFlagEnum.NORMAL.getCode()));

        int deletedUnscheduledCount = unscheduledResultMapper.delete(
                new LambdaQueryWrapper<LhUnscheduledResult>()
                        .eq(LhUnscheduledResult::getFactoryCode, factoryCode)
                        .eq(LhUnscheduledResult::getScheduleDate, targetDate)
                        .eq(LhUnscheduledResult::getIsDelete, DeleteFlagEnum.NORMAL.getCode()));

        int deletedMouldPlanCount = mouldChangePlanMapper.delete(
                new LambdaQueryWrapper<LhMouldChangePlan>()
                        .eq(LhMouldChangePlan::getFactoryCode, factoryCode)
                        .eq(LhMouldChangePlan::getScheduleDate, targetDate)
                        .eq(LhMouldChangePlan::getIsDelete, DeleteFlagEnum.NORMAL.getCode()));

        int deletedLogCount = 0;
        for (String batchNo : oldBatchNos) {
            deletedLogCount += processLogMapper.delete(new LambdaQueryWrapper<LhScheduleProcessLog>()
                    .eq(LhScheduleProcessLog::getBatchNo, batchNo)
                    .eq(LhScheduleProcessLog::getIsDelete, DeleteFlagEnum.NORMAL.getCode()));
        }

        if (!context.getScheduleResultList().isEmpty()) {
            // 为排程结果补齐审计字段
            fillScheduleResultAuditInfo(context, context.getScheduleResultList());
            scheduleResultMapper.insertBatch(context.getScheduleResultList());
        }
        if (!context.getUnscheduledResultList().isEmpty()) {
            unscheduledResultMapper.insertBatch(context.getUnscheduledResultList());
        }
        if (!context.getMouldChangePlanList().isEmpty()) {
            mouldChangePlanMapper.insertBatch(context.getMouldChangePlanList());
        }
        if (!context.getScheduleLogList().isEmpty()) {
            processLogMapper.insertBatch(context.getScheduleLogList());
        }

        log.info("目标日排程原子替换完成, 工厂: {}, 日期: {}, 删除结果: {}, 删除未排: {}, 删除换模: {}, 删除日志: {}, 新结果: {}, 新未排: {}, 新换模: {}, 新日志: {}",
                factoryCode, LhScheduleTimeUtil.formatDate(targetDate),
                deletedResultCount, deletedUnscheduledCount, deletedMouldPlanCount, deletedLogCount,
                context.getScheduleResultList().size(), context.getUnscheduledResultList().size(),
                context.getMouldChangePlanList().size(), context.getScheduleLogList().size());
    }

    /**
     * 为排程结果补齐审计字段。
     *
     * @param context 排程上下文
     * @param scheduleResults 排程结果列表
     */
    private void fillScheduleResultAuditInfo(LhScheduleContext context, List<LhScheduleResult> scheduleResults) {
        Date now = new Date();
        String operator = resolveOperator(context);
        for (LhScheduleResult scheduleResult : scheduleResults) {
            if (Objects.isNull(scheduleResult.getCreateTime())) {
                scheduleResult.setCreateTime(now);
            }
            if (Objects.isNull(scheduleResult.getUpdateTime())) {
                scheduleResult.setUpdateTime(now);
            }
            if (StringUtils.isEmpty(scheduleResult.getCreateBy())) {
                scheduleResult.setCreateBy(operator);
            }
            if (StringUtils.isEmpty(scheduleResult.getUpdateBy())) {
                scheduleResult.setUpdateBy(operator);
            }
        }
    }

    /**
     * 获取审计字段操作人。
     *
     * @param context 排程上下文
     * @return 操作人
     */
    private String resolveOperator(LhScheduleContext context) {
        if (Objects.nonNull(context) && StringUtils.isNotEmpty(context.getOperator())) {
            return context.getOperator();
        }
        return DEFAULT_OPERATOR;
    }
}
