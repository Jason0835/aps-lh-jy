package com.zlt.aps.lh.engine.strategy.support;

import com.zlt.aps.lh.api.domain.dto.SkuDailyPlanQuotaDTO;
import com.zlt.aps.lh.api.domain.dto.SkuScheduleDTO;
import com.zlt.aps.lh.context.LhScheduleContext;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.util.CollectionUtils;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * SKU提前生产准入判断器。
 * <p>只判断后续日有计划量的SKU是否允许进入当前日新增机台判断，不执行选机、换模、胎胚扣减或日计划扣账。</p>
 */
@Slf4j
public final class EarlyProductionChecker {

    private EarlyProductionChecker() {
    }

    /**
     * 判断 SKU 是否允许进入当前业务日新增机台判断。
     *
     * @param context 排程上下文
     * @param sku SKU
     * @param currentDate 当前业务日期
     * @param windowEndDate 排程窗口结束日期
     * @param shortageThreshold 欠产增机台阈值
     * @return true-允许继续进入新增机台判断；false-不提前生产，保持原顺延逻辑
     */
    public static boolean canEnterEarlyProductionCheck(LhScheduleContext context,
                                                       SkuScheduleDTO sku,
                                                       LocalDate currentDate,
                                                       LocalDate windowEndDate,
                                                       int shortageThreshold) {
        return checkEarlyProduction(context, sku, currentDate, currentDate,
                windowEndDate, shortageThreshold).isAllowed();
    }

    /**
     * 判断 SKU 是否属于提前生产，并返回准入场景及 T～T+2 结构计划机台数。
     *
     * @param context 排程上下文
     * @param sku SKU
     * @param currentDate 当前业务日期
     * @param windowStartDate 排程窗口 T 日
     * @param windowEndDate 排程窗口结束日期
     * @param shortageThreshold 欠产增机台阈值
     * @return 提前生产结构化判定结果
     */
    public static EarlyProductionDecision checkEarlyProduction(LhScheduleContext context,
                                                               SkuScheduleDTO sku,
                                                               LocalDate currentDate,
                                                               LocalDate windowStartDate,
                                                               LocalDate windowEndDate,
                                                               int shortageThreshold) {
        if (Objects.isNull(context) || Objects.isNull(sku) || Objects.isNull(currentDate)
                || CollectionUtils.isEmpty(sku.getDailyPlanQuotaMap())) {
            return EarlyProductionDecision.notEarlyProduction(true, "非提前生产判定范围");
        }
        if (hasCurrentDayPlan(sku, currentDate)) {
            return EarlyProductionDecision.notEarlyProduction(true, "当前业务日已有日计划量");
        }
        LocalDate firstFuturePlanDate = resolveFirstFuturePlanDate(sku, currentDate, windowEndDate);
        if (Objects.isNull(firstFuturePlanDate)) {
            logEarlyProductionDecision(context, sku, currentDate, null, 0,
                    context.getStructureScheduledMachineCount(currentDate, sku.getStructureName()),
                    context.getSkuScheduledMachineCount(currentDate, sku.getMaterialCode()),
                    shortageThreshold, false, "下一业务日无日计划量");
            return EarlyProductionDecision.notEarlyProduction(false, "下一业务日无日计划量");
        }
        List<Integer> structurePlanMachineCounts = resolveWindowStructurePlanMachineCounts(
                context, sku.getStructureName(), windowStartDate);
        int historyShortageQty = Math.max(0, sku.getMonthlyHistoryShortageQty());
        int threshold = Math.max(0, shortageThreshold);
        if (historyShortageQty > threshold) {
            logEarlyProductionDecision(context, sku, currentDate, firstFuturePlanDate, 0,
                    context.getStructureScheduledMachineCount(currentDate, sku.getStructureName()),
                    context.getSkuScheduledMachineCount(currentDate, sku.getMaterialCode()),
                    threshold, true, "本月前日累计欠产超过阈值，复用原强制加机台逻辑");
            return EarlyProductionDecision.earlyProduction(true, EarlyProductionDecision.SCENE_NORMAL,
                    firstFuturePlanDate, structurePlanMachineCounts,
                    "本月前日累计欠产超过阈值，复用原强制加机台逻辑");
        }
        int currentPlanMachineCount = context.getStructurePlanMachineCount(
                currentDate, sku.getStructureName());
        int planMachineCount = resolveEffectiveStructurePlanMachineCount(
                context, sku, currentDate, firstFuturePlanDate);
        int scheduledStructureCount = context.getStructureScheduledMachineCount(
                currentDate, sku.getStructureName());
        int scheduledSkuCount = context.getSkuScheduledMachineCount(currentDate, sku.getMaterialCode());
        if (planMachineCount > 0) {
            boolean allowed = scheduledStructureCount < planMachineCount;
            logEarlyProductionDecision(context, sku, currentDate, firstFuturePlanDate, planMachineCount,
                    scheduledStructureCount, scheduledSkuCount, threshold, allowed,
                    allowed ? "结构已排机台数未达到计划机台数" : "结构已排机台数已达到计划机台数");
            String sceneType = currentPlanMachineCount > 0
                    ? EarlyProductionDecision.SCENE_NORMAL : EarlyProductionDecision.SCENE_STRUCTURE_SWITCH;
            return EarlyProductionDecision.earlyProduction(allowed, sceneType, firstFuturePlanDate,
                    structurePlanMachineCounts,
                    allowed ? "结构已排机台数未达到计划机台数" : "结构已排机台数已达到计划机台数");
        }
        boolean allowedByEndingSurplus = isEndingStructureLargeSurplus(
                context, sku, currentDate, firstFuturePlanDate);
        logEarlyProductionDecision(context, sku, currentDate, firstFuturePlanDate, planMachineCount,
                scheduledStructureCount, scheduledSkuCount, threshold, allowedByEndingSurplus,
                allowedByEndingSurplus ? "结构已收尾且SKU余量大于已排机台日硫化量" : "结构无有效计划且SKU余量不足");
        return EarlyProductionDecision.earlyProduction(allowedByEndingSurplus,
                EarlyProductionDecision.SCENE_STRUCTURE_ENDING, firstFuturePlanDate,
                structurePlanMachineCounts,
                allowedByEndingSurplus ? "结构已收尾且SKU余量大于已排机台日硫化量" : "结构无有效计划且SKU余量不足");
    }

    /**
     * 获取排程窗口 T～T+2 的结构计划硫化机台数。
     *
     * @param context 排程上下文
     * @param structureName 产品结构
     * @param windowStartDate 排程窗口 T 日
     * @return 固定三个业务日的结构计划机台数
     */
    private static List<Integer> resolveWindowStructurePlanMachineCounts(LhScheduleContext context,
                                                                         String structureName,
                                                                         LocalDate windowStartDate) {
        List<Integer> machineCounts = new ArrayList<Integer>(3);
        if (Objects.isNull(context) || Objects.isNull(windowStartDate)
                || StringUtils.isEmpty(structureName)) {
            return machineCounts;
        }
        for (int dayOffset = 0; dayOffset < 3; dayOffset++) {
            machineCounts.add(context.getStructurePlanMachineCount(
                    windowStartDate.plusDays(dayOffset), structureName));
        }
        return machineCounts;
    }

    /**
     * 判断是否命中结构已收尾但 SKU 余量较大的强制加机台条件。
     *
     * @param context 排程上下文
     * @param sku SKU
     * @param currentDate 当前业务日期
     * @param firstFuturePlanDate 下一业务日计划日
     * @return true-命中结构收尾大余量；false-未命中
     */
    public static boolean isEndingStructureLargeSurplus(LhScheduleContext context,
                                                        SkuScheduleDTO sku,
                                                        LocalDate currentDate,
                                                        LocalDate firstFuturePlanDate) {
        if (Objects.isNull(context) || Objects.isNull(sku) || Objects.isNull(currentDate)
                || StringUtils.isEmpty(sku.getStructureName())) {
            return false;
        }
        int effectivePlanMachineCount = resolveEffectiveStructurePlanMachineCount(
                context, sku, currentDate, firstFuturePlanDate);
        if (effectivePlanMachineCount > 0) {
            return false;
        }
        int historyShortageQty = Math.max(0, sku.getMonthlyHistoryShortageQty());
        int scheduledSkuCount = context.getSkuScheduledMachineCount(currentDate, sku.getMaterialCode());
        int dailyCapacity = Math.max(0, sku.getDailyCapacity());
        // 结构已收尾时，用本月前日累计欠产与当前已排SKU机台的日硫化量对比，判断是否仍需进入强制扩机。
        long scheduledDailyCapacity = (long) scheduledSkuCount * dailyCapacity;
        return dailyCapacity > 0 && (long) historyShortageQty > scheduledDailyCapacity;
    }

    /**
     * 解析当前日后一日是否有 dayN 日计划量。
     * <p>SKU提前生产只允许提前一天，当前业务日只能判断下一业务日的计划量，
     * 不再向 T+2 或更后日期扫描。</p>
     *
     * @param sku SKU
     * @param currentDate 当前业务日期
     * @param windowEndDate 排程窗口结束日期
     * @return 下一业务日；无下一日计划返回 null
     */
    public static LocalDate resolveFirstFuturePlanDate(SkuScheduleDTO sku,
                                                       LocalDate currentDate,
                                                       LocalDate windowEndDate) {
        if (Objects.isNull(sku) || CollectionUtils.isEmpty(sku.getDailyPlanQuotaMap())
                || Objects.isNull(currentDate)) {
            return null;
        }
        LocalDate nextDate = currentDate.plusDays(1);
        if (Objects.nonNull(windowEndDate) && nextDate.isAfter(windowEndDate)) {
            return null;
        }
        if (hasDayPlan(sku.getDailyPlanQuotaMap().get(nextDate))) {
            return nextDate;
        }
        return null;
    }

    /**
     * 判断当前日是否已有 dayN 日计划量。
     *
     * @param sku SKU
     * @param currentDate 当前业务日期
     * @return true-当前日有计划；false-当前日无计划
     */
    private static boolean hasCurrentDayPlan(SkuScheduleDTO sku, LocalDate currentDate) {
        if (Objects.isNull(sku) || CollectionUtils.isEmpty(sku.getDailyPlanQuotaMap())
                || Objects.isNull(currentDate)) {
            return false;
        }
        return hasDayPlan(sku.getDailyPlanQuotaMap().get(currentDate));
    }

    /**
     * 判断日计划额度是否有原始 dayN 计划量。
     *
     * @param quota 日计划额度
     * @return true-有 dayN 计划量；false-无 dayN 计划量
     */
    private static boolean hasDayPlan(SkuDailyPlanQuotaDTO quota) {
        return Objects.nonNull(quota) && Math.max(0, quota.getDayPlanQty()) > 0;
    }

    /**
     * 获取结构计划机台数；当前日为0时，按结构切换规则只改取下一业务日。
     *
     * @param context 排程上下文
     * @param sku SKU
     * @param currentDate 当前业务日期
     * @param firstFuturePlanDate 下一业务日计划日
     * @return 用于准入判断的结构计划机台数
     */
    private static int resolveEffectiveStructurePlanMachineCount(LhScheduleContext context,
                                                                 SkuScheduleDTO sku,
                                                                 LocalDate currentDate,
                                                                 LocalDate firstFuturePlanDate) {
        if (Objects.isNull(context) || Objects.isNull(sku) || StringUtils.isEmpty(sku.getStructureName())) {
            return 0;
        }
        int currentPlanMachineCount = context.getStructurePlanMachineCount(currentDate, sku.getStructureName());
        if (currentPlanMachineCount > 0) {
            return currentPlanMachineCount;
        }
        int futurePlanMachineCount = context.getStructurePlanMachineCount(
                firstFuturePlanDate, sku.getStructureName());
        if (futurePlanMachineCount > 0) {
            log.info("提前生产结构切换判断, factoryCode: {}, currentDate: {}, futurePlanDate: {}, "
                            + "structureName: {}, currentPlanMachineCount: {}, futurePlanMachineCount: {}",
                    context.getFactoryCode(), currentDate, firstFuturePlanDate, sku.getStructureName(),
                    currentPlanMachineCount, futurePlanMachineCount);
        }
        return futurePlanMachineCount;
    }

    /**
     * 输出提前生产准入判断日志。
     *
     * @param context 排程上下文
     * @param sku SKU
     * @param currentDate 当前业务日期
     * @param futurePlanDate 后续计划日
     * @param planMachineCount 计划机台数
     * @param scheduledStructureCount 结构已排机台数
     * @param scheduledSkuCount SKU已排机台数
     * @param threshold 欠产阈值
     * @param allowed 是否允许进入新增判断
     * @param reason 原因
     */
    private static void logEarlyProductionDecision(LhScheduleContext context,
                                                   SkuScheduleDTO sku,
                                                   LocalDate currentDate,
                                                   LocalDate futurePlanDate,
                                                   int planMachineCount,
                                                   int scheduledStructureCount,
                                                   int scheduledSkuCount,
                                                   int threshold,
                                                   boolean allowed,
                                                   String reason) {
        log.info("提前生产准入判断, factoryCode: {}, currentDate: {}, futurePlanDate: {}, materialCode: {}, "
                        + "structureName: {}, historyShortageQty: {}, threshold: {}, planMachineCount: {}, "
                        + "scheduledStructureCount: {}, scheduledSkuCount: {}, dailyQty: {}, result: {}, reason: {}",
                context.getFactoryCode(), currentDate, futurePlanDate, sku.getMaterialCode(),
                sku.getStructureName(), Math.max(0, sku.getMonthlyHistoryShortageQty()), threshold,
                planMachineCount, scheduledStructureCount, scheduledSkuCount,
                Math.max(0, sku.getDailyCapacity()), allowed, reason);
    }
}
