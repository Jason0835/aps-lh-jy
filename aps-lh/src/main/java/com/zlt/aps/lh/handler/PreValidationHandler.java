package com.zlt.aps.lh.handler;

import com.zlt.aps.lh.api.enums.ScheduleStepEnum;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.exception.ScheduleErrorCode;
import com.zlt.aps.lh.exception.ScheduleException;
import com.zlt.aps.lh.service.ILhScheduleResultService;
import com.zlt.aps.lh.util.LhScheduleTimeUtil;
import com.zlt.aps.lh.util.MonthPlanDayQtyUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.Date;

/**
 * S4.1 前置校验处理器
 * <p>校验 MES 下发状态并生成批次号；危险的数据替换动作统一后移到最终原子持久化阶段。</p>
 *
 * @author APS
 */
@Slf4j
@Component
public class PreValidationHandler extends AbsScheduleStepHandler {

    @Resource
    private ILhScheduleResultService scheduleResultService;

    @Override
    protected void doHandle(LhScheduleContext context) {
        // S4.1.1 校验MES下发状态
        checkMesReleaseStatus(context);

        // S4.1.2 记录排程窗口跨月状态
        checkCrossMonthWindow(context);

        // S4.1.3 生成批次号
        generateBatchNo(context);
    }

    /**
     * 校验MES下发状态
     * <p>依据 {@code LhScheduleResult.isRelease}：仅当为 {@code 1}（已发布）时视为已下发 MES，
     * 此时禁止重新排程，需先撤销发布再排程（统计逻辑见 {@link ILhScheduleResultService#countReleasedByDate}）。</p>
     *
     * @param context 排程上下文
     */
    private void checkMesReleaseStatus(LhScheduleContext context) {
        Date targetDate = context.getScheduleTargetDate();
        String factoryCode = context.getFactoryCode();
        int releasedCount = scheduleResultService.countReleasedByDate(targetDate, factoryCode);
        if (releasedCount > 0) {
            log.warn("排程被拒绝: 日期[{}]已有已发布排程, 数量: {}", LhScheduleTimeUtil.formatDate(targetDate), releasedCount);
            throw new ScheduleException(ScheduleStepEnum.S4_1_PRE_VALIDATION, ScheduleErrorCode.MES_RELEASED,
                    factoryCode, context.getBatchNo(),
                    "该日期排程已下发MES，请先撤销发布后再重新排程。排程日期: "
                            + LhScheduleTimeUtil.getDateStr(targetDate));
        }
    }

    /**
     * 记录排程窗口是否跨月。
     *
     * @param context 排程上下文
     */
    private void checkCrossMonthWindow(LhScheduleContext context) {
        if (!MonthPlanDayQtyUtil.isCrossMonthWindow(context.getScheduleDate(), context.getWindowEndDate())) {
            return;
        }
        log.info("排程窗口跨月，启用跨月月计划解析, 工厂[{}], T日: {}, 窗口结束日: {}",
                context.getFactoryCode(),
                LhScheduleTimeUtil.formatDate(context.getScheduleDate()),
                LhScheduleTimeUtil.formatDate(context.getWindowEndDate()));
    }

    /**
     * 生成排程批次号（规则见 {@link ILhScheduleResultService#generateNextBatchNo}，
     * 批次号格式保持不变，流水按目标排程日由 Redis 自增全局分配）
     *
     * @param context 排程上下文
     */
    private void generateBatchNo(LhScheduleContext context) {
        String batchNo = scheduleResultService.generateNextBatchNo(context.getScheduleTargetDate(), context.getFactoryCode());
        context.setBatchNo(batchNo);
        log.info("生成排程批次号: {}", batchNo);
    }

    @Override
    protected String getStepName() {
        return ScheduleStepEnum.S4_1_PRE_VALIDATION.getDescription();
    }
}
