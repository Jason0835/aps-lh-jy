package com.zlt.aps.lh.engine.strategy.support;

import com.zlt.aps.lh.api.constant.LhScheduleConstant;
import com.zlt.aps.lh.api.domain.dto.SkuDailyPlanQuotaDTO;
import com.zlt.aps.lh.api.domain.dto.SkuScheduleDTO;
import com.zlt.aps.lh.context.LhScheduleConfig;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.util.LhScheduleTimeUtil;
import com.zlt.aps.lh.util.SkuDailyPlanQuotaUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.CollectionUtils;

import java.time.LocalDate;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 日计划欠产增机台公共协作器。
 * <p>只负责欠产账本准备和是否继续增机台的公共判断，不直接生成排产结果。</p>
 *
 * @author APS
 */
@Slf4j
public final class DailyMachineExpansionPlanner {

    private DailyMachineExpansionPlanner() {
    }

    /**
     * 准备本月历史欠产账本。
     * <p>只补充本月前日累计欠产差额，已入账部分不重复追加；窗口无计划时同步识别收尾清量或仅补欠产。</p>
     *
     * @param context 排程上下文
     * @param sku SKU
     * @param sceneName 场景名称
     * @return 欠产账本准备结果
     */
    public static DailyMachineShortageQuotaPlan prepareShortageQuota(LhScheduleContext context,
                                                                      SkuScheduleDTO sku,
                                                                      String sceneName) {
        DailyMachineShortageQuotaPlan plan = new DailyMachineShortageQuotaPlan();
        plan.setShortageAddMachineThreshold(resolveShortageAddMachineThreshold(context));
        if (Objects.isNull(context) || Objects.isNull(sku)) {
            return plan;
        }
        sku.setStrictNewSpecShortageOnly(false);
        int windowDayPlanQty = sumDayPlanQty(sku);
        int historyShortageQty = Math.max(0, sku.getMonthlyHistoryShortageQty());
        int effectiveCarryForwardQty = Math.max(0, sku.getEffectiveCarryForwardQty());
        int additionalShortageQty = Math.max(0, historyShortageQty - effectiveCarryForwardQty);
        if (additionalShortageQty > 0) {
            int actualAppendShortageQty = appendShortageToFirstQuota(sku, additionalShortageQty, sceneName);
            if (actualAppendShortageQty > 0) {
                sku.setEffectiveCarryForwardQty(effectiveCarryForwardQty + actualAppendShortageQty);
            }
            syncSharedQuotaEffectiveCarryForwardQty(context, sku, sku.getEffectiveCarryForwardQty());
        }
        boolean noWindowPlan = !CollectionUtils.isEmpty(sku.getDailyPlanQuotaMap()) && windowDayPlanQty <= 0;
        int quotaRemainingQty = SkuDailyPlanQuotaUtil.sumRemainingQty(sku.getDailyPlanQuotaMap());
        plan.setNoWindowPlan(noWindowPlan);
        plan.setWindowDayPlanQty(windowDayPlanQty);
        plan.setHistoryShortageQty(historyShortageQty);
        plan.setAdditionalShortageQty(additionalShortageQty);
        plan.setQuotaRemainingQty(quotaRemainingQty);
        plan.setFutureMonthPlanQtyAfterWindow(Math.max(0, sku.getFutureMonthPlanQtyAfterWindow()));
        if (historyShortageQty <= 0) {
            return plan;
        }
        if (noWindowPlan) {
            int strictTargetQty = Math.min(resolvePositiveDemandQty(sku),
                    Math.max(historyShortageQty, quotaRemainingQty));
            if (strictTargetQty <= 0) {
                strictTargetQty = Math.max(0, sku.resolveTargetScheduleQty());
            }
            plan.setStrictTargetQty(strictTargetQty);
            if (Math.max(0, sku.getFutureMonthPlanQtyAfterWindow()) > 0) {
                sku.setStrictNewSpecShortageOnly(true);
                sku.setTargetScheduleQty(strictTargetQty);
                sku.setWindowPlanQty(strictTargetQty);
                sku.setWindowRemainingPlanQty(Math.max(strictTargetQty, quotaRemainingQty));
                log.info("{}窗口无日计划但月底仍有计划，仅补本月欠产, materialCode: {}, "
                                + "historyShortageQty: {}, futurePlanQtyAfterWindow: {}, strictTargetQty: {}",
                        sceneName, sku.getMaterialCode(), historyShortageQty,
                        sku.getFutureMonthPlanQtyAfterWindow(), strictTargetQty);
                return plan;
            }
            if (strictTargetQty > 0) {
                sku.setTargetScheduleQty(Math.max(sku.resolveTargetScheduleQty(), strictTargetQty));
            }
            plan.setForceEndingByNoFuturePlan(true);
            log.info("{}窗口及月底均无日计划，按收尾清量处理, materialCode: {}, "
                            + "historyShortageQty: {}, targetQty: {}",
                    sceneName, sku.getMaterialCode(), historyShortageQty, sku.resolveTargetScheduleQty());
            return plan;
        }
        int syntheticWindowPlanQty = Math.max(0, windowDayPlanQty) + historyShortageQty;
        sku.setWindowPlanQty(syntheticWindowPlanQty);
        sku.setWindowRemainingPlanQty(quotaRemainingQty > 0 ? quotaRemainingQty : syntheticWindowPlanQty);
        sku.setTargetScheduleQty(Math.max(sku.resolveTargetScheduleQty(), sku.getWindowRemainingPlanQty()));
        log.info("{}欠产增机台判断准备完成, materialCode: {}, scheduleDate: {}, "
                        + "historyShortageQty: {}, threshold: {}, scheduleDayFinishQty: {}, "
                        + "windowPlanQty: {}, windowRemainingQty: {}, futurePlanQtyAfterWindow: {}",
                sceneName, sku.getMaterialCode(), LhScheduleTimeUtil.formatDate(context.getScheduleDate()),
                historyShortageQty, plan.getShortageAddMachineThreshold(), sku.getScheduleDayFinishQty(),
                sku.getWindowPlanQty(), sku.getWindowRemainingPlanQty(), sku.getFutureMonthPlanQtyAfterWindow());
        return plan;
    }

    /**
     * 判断SKU是否需要继续尝试下一台机台。
     * <p>小额历史欠产未超阈值时，如果后续日计划已满足，则不再为了首日历史欠产余额扩机台。</p>
     *
     * @param context 排程上下文
     * @param sku SKU
     * @return true-需要继续增机台；false-当前机台已满足规则
     */
    public static boolean needMoreMachine(LhScheduleContext context, SkuScheduleDTO sku) {
        if (isSmallShortageRollingSatisfied(context, sku)) {
            return false;
        }
        if (Objects.nonNull(sku) && sku.getRemainingScheduleQty() > 0) {
            return true;
        }
        Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap = sku == null ? null : sku.getDailyPlanQuotaMap();
        if (CollectionUtils.isEmpty(quotaMap)) {
            return false;
        }
        for (SkuDailyPlanQuotaDTO quota : quotaMap.values()) {
            if (Objects.nonNull(quota) && quota.getRemainingQty() > 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断小额历史欠产是否已满足后续日计划支撑要求。
     *
     * @param context 排程上下文
     * @param sku SKU
     * @return true-后续日计划已满足，不继续增机台
     */
    public static boolean isSmallShortageRollingSatisfied(LhScheduleContext context, SkuScheduleDTO sku) {
        return shouldAllowSmallShortageRolling(context, sku) && isFutureQuotaSatisfied(sku);
    }

    /**
     * 判断是否属于欠产未超阈值的小欠产滚动场景。
     *
     * @param context 排程上下文
     * @param sku SKU
     * @return true-小欠产后看；false-按普通缺口处理
     */
    public static boolean shouldAllowSmallShortageRolling(LhScheduleContext context, SkuScheduleDTO sku) {
        if (Objects.isNull(sku) || sku.isStrictNewSpecShortageOnly()
                || Math.max(0, sku.getWindowPlanQty()) <= 0) {
            return false;
        }
        int historyShortageQty = Math.max(0, sku.getMonthlyHistoryShortageQty());
        return historyShortageQty > 0 && historyShortageQty <= resolveShortageAddMachineThreshold(context);
    }

    /**
     * 判断除首日以外的后续日计划额度是否已经满足。
     *
     * @param sku SKU
     * @return true-后续日期无剩余额度；false-仍有后续日计划未满足
     */
    public static boolean isFutureQuotaSatisfied(SkuScheduleDTO sku) {
        if (Objects.isNull(sku) || CollectionUtils.isEmpty(sku.getDailyPlanQuotaMap())) {
            return false;
        }
        boolean first = true;
        for (SkuDailyPlanQuotaDTO quota : sku.getDailyPlanQuotaMap().values()) {
            if (Objects.isNull(quota)) {
                continue;
            }
            if (first) {
                first = false;
                continue;
            }
            if (Math.max(0, quota.getRemainingQty()) > 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * 构建 dayN 模拟账本快照。
     *
     * @param quotaMap 原日计划账本
     * @param remainingTargetQty 本轮剩余目标量
     * @return 模拟账本
     */
    public static Map<LocalDate, SkuDailyPlanQuotaDTO> buildSimulationQuotaMap(
            Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap,
            int remainingTargetQty) {
        if (CollectionUtils.isEmpty(quotaMap)) {
            return new LinkedHashMap<LocalDate, SkuDailyPlanQuotaDTO>(0);
        }
        Map<LocalDate, SkuDailyPlanQuotaDTO> simulationQuotaMap =
                new LinkedHashMap<LocalDate, SkuDailyPlanQuotaDTO>(Math.max(4, quotaMap.size() * 2));
        int remainingLimitQty = Math.max(0, remainingTargetQty);
        boolean hasTargetLimit = remainingTargetQty > 0;
        for (Map.Entry<LocalDate, SkuDailyPlanQuotaDTO> entry : quotaMap.entrySet()) {
            SkuDailyPlanQuotaDTO sourceQuota = entry.getValue();
            if (Objects.isNull(sourceQuota)) {
                continue;
            }
            SkuDailyPlanQuotaDTO quota = new SkuDailyPlanQuotaDTO();
            quota.setMaterialCode(sourceQuota.getMaterialCode());
            quota.setProductionDate(sourceQuota.getProductionDate());
            int remainingQty = Math.max(0, sourceQuota.getRemainingQty());
            int dayPlanQty = Math.max(0, sourceQuota.getDayPlanQty());
            if (hasTargetLimit) {
                remainingQty = Math.min(remainingQty, remainingLimitQty);
                dayPlanQty = Math.min(dayPlanQty, remainingQty);
                remainingLimitQty -= remainingQty;
            }
            quota.setDayPlanQty(dayPlanQty);
            quota.setRemainingQty(remainingQty);
            simulationQuotaMap.put(entry.getKey(), quota);
        }
        return simulationQuotaMap;
    }

    /**
     * 获取新增排产欠产增机台阈值。
     *
     * @param context 排程上下文
     * @return 欠产阈值
     */
    public static int resolveShortageAddMachineThreshold(LhScheduleContext context) {
        LhScheduleConfig scheduleConfig = context == null ? null : context.getScheduleConfig();
        if (scheduleConfig == null) {
            return LhScheduleConstant.NEW_SPEC_SHORTAGE_ADD_MACHINE_THRESHOLD;
        }
        return scheduleConfig.getNewSpecShortageAddMachineThreshold();
    }

    /**
     * 将本月历史欠产差额追加到首日日计划账本。
     *
     * @param sku SKU
     * @param shortageQty 欠产差额
     * @param sceneName 场景名称
     */
    private static int appendShortageToFirstQuota(SkuScheduleDTO sku, int shortageQty, String sceneName) {
        if (Objects.isNull(sku) || shortageQty <= 0 || CollectionUtils.isEmpty(sku.getDailyPlanQuotaMap())) {
            return 0;
        }
        SkuDailyPlanQuotaDTO firstQuota = sku.getDailyPlanQuotaMap().values().iterator().next();
        if (Objects.isNull(firstQuota)) {
            return 0;
        }
        firstQuota.setRemainingQty(Math.max(0, firstQuota.getRemainingQty()) + shortageQty);
        SkuDailyPlanQuotaUtil.refreshRollingFields(sku.getDailyPlanQuotaMap());
        log.info("{}本月历史欠产差额追加到首日账本, materialCode: {}, shortageQty: {}, firstDate: {}, "
                        + "windowRemainingQty: {}",
                sceneName, sku.getMaterialCode(), shortageQty, firstQuota.getProductionDate(),
                SkuDailyPlanQuotaUtil.sumRemainingQty(sku.getDailyPlanQuotaMap()));
        return shortageQty;
    }

    /**
     * 同步共享日计划账本的历史欠产入账状态。
     *
     * @param context 排程上下文
     * @param sourceSku 当前SKU
     * @param effectiveCarryForwardQty 已入账历史欠产量
     */
    public static void syncSharedQuotaEffectiveCarryForwardQty(LhScheduleContext context,
                                                               SkuScheduleDTO sourceSku,
                                                               int effectiveCarryForwardQty) {
        if (Objects.isNull(sourceSku) || CollectionUtils.isEmpty(sourceSku.getDailyPlanQuotaMap())) {
            return;
        }
        sourceSku.setEffectiveCarryForwardQty(Math.max(0, effectiveCarryForwardQty));
        if (Objects.isNull(context)) {
            return;
        }
        Set<SkuScheduleDTO> visitedSkuSet = Collections.newSetFromMap(
                new IdentityHashMap<SkuScheduleDTO, Boolean>(8));
        syncSharedQuotaEffectiveCarryForwardQty(context.getNewSpecSkuList(), sourceSku,
                effectiveCarryForwardQty, visitedSkuSet);
        syncSharedQuotaEffectiveCarryForwardQty(context.getContinuousSkuList(), sourceSku,
                effectiveCarryForwardQty, visitedSkuSet);
        if (!CollectionUtils.isEmpty(context.getStructureSkuMap())) {
            for (List<SkuScheduleDTO> skuList : context.getStructureSkuMap().values()) {
                syncSharedQuotaEffectiveCarryForwardQty(skuList, sourceSku,
                        effectiveCarryForwardQty, visitedSkuSet);
            }
        }
    }

    /**
     * 同步同账本SKU的历史欠产入账状态。
     *
     * @param skuList 待检查SKU集合
     * @param sourceSku 当前SKU
     * @param effectiveCarryForwardQty 已入账历史欠产量
     * @param visitedSkuSet 已处理SKU集合
     */
    private static void syncSharedQuotaEffectiveCarryForwardQty(List<SkuScheduleDTO> skuList,
                                                                SkuScheduleDTO sourceSku,
                                                                int effectiveCarryForwardQty,
                                                                Set<SkuScheduleDTO> visitedSkuSet) {
        if (CollectionUtils.isEmpty(skuList) || Objects.isNull(sourceSku) || Objects.isNull(visitedSkuSet)) {
            return;
        }
        for (SkuScheduleDTO sku : skuList) {
            if (Objects.isNull(sku) || visitedSkuSet.contains(sku)
                    || sku.getDailyPlanQuotaMap() != sourceSku.getDailyPlanQuotaMap()) {
                continue;
            }
            visitedSkuSet.add(sku);
            sku.setEffectiveCarryForwardQty(Math.max(0, effectiveCarryForwardQty));
        }
    }

    /**
     * 汇总窗口日计划量。
     *
     * @param sku SKU
     * @return 日计划量合计
     */
    private static int sumDayPlanQty(SkuScheduleDTO sku) {
        if (Objects.isNull(sku) || CollectionUtils.isEmpty(sku.getDailyPlanQuotaMap())) {
            return 0;
        }
        int totalQty = 0;
        for (SkuDailyPlanQuotaDTO quota : sku.getDailyPlanQuotaMap().values()) {
            if (Objects.isNull(quota)) {
                continue;
            }
            totalQty += Math.max(0, quota.getDayPlanQty());
        }
        return Math.max(0, totalQty);
    }

    /**
     * 解析正向需求量。
     *
     * @param sku SKU
     * @return 正向需求量
     */
    private static int resolvePositiveDemandQty(SkuScheduleDTO sku) {
        if (Objects.isNull(sku)) {
            return 0;
        }
        int targetQty = Math.max(0, sku.resolveTargetScheduleQty());
        int surplusQty = Math.max(0, sku.getSurplusQty());
        if (surplusQty > 0) {
            return targetQty > 0 ? Math.min(targetQty, surplusQty) : surplusQty;
        }
        return targetQty;
    }
}
