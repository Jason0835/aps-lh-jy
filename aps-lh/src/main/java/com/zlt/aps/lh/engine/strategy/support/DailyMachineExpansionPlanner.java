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
     * <p>业务口径：</p>
     * <ul>
     *   <li>仅处理当前排程月份内 T 日之前的正向欠产，超产不抵扣后续日计划；</li>
     *   <li>历史欠产只追加到首日日计划账本一次，共享同一账本的续作/新增/补偿 SKU 不重复入账；</li>
     *   <li>窗口无计划但月底仍有计划时，只补本月欠产并保持非收尾，避免盲目满班超排；</li>
     *   <li>窗口和月底均无计划时，按收尾清量处理，允许结合余量/胎胚库存严格收口。</li>
     * </ul>
     *
     * <p>副作用：可能更新 SKU 的 {@code windowPlanQty}、{@code windowRemainingPlanQty}、
     * {@code targetScheduleQty}、{@code strictNewSpecShortageOnly} 和共享日计划账本。</p>
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
        // additionalShortageQty 表示“本月历史欠产尚未追加到账本的差额”，用于避免同账本多 SKU 重复追补。
        int additionalShortageQty = Math.max(0, historyShortageQty - effectiveCarryForwardQty);
        if (additionalShortageQty > 0) {
            int actualAppendShortageQty = appendShortageToFirstQuota(sku, additionalShortageQty, sceneName);
            if (actualAppendShortageQty > 0) {
                sku.setEffectiveCarryForwardQty(effectiveCarryForwardQty + actualAppendShortageQty);
            }
            syncSharedQuotaEffectiveCarryForwardQty(context, sku, sku.getEffectiveCarryForwardQty());
        }
        boolean noWindowPlan = !CollectionUtils.isEmpty(sku.getDailyPlanQuotaMap()) && windowDayPlanQty <= 0;
        // quotaRemainingQty 是账本维度剩余额度，已包含继承扣减、历史欠产追加和滚动借用后的结果。
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
            /*
             * 窗口无日计划时必须继续看 T+3 到月底计划：
             * 1. 月底仍有计划，说明当前窗口只是临时无 dayN，不能把后续计划提前消耗；
             *    本轮只允许补本月 T 日前已真实发生的历史欠产，目标量必须严格等于 historyShortageQty。
             * 2. 月底也无计划，说明本月后续没有日计划承接，可以交给已有收尾目标量口径清量。
             */
            int strictTargetQty = historyShortageQty;
            plan.setStrictTargetQty(strictTargetQty);
            if (Math.max(0, sku.getFutureMonthPlanQtyAfterWindow()) > 0) {
                sku.setStrictNewSpecShortageOnly(true);
                sku.setTargetScheduleQty(strictTargetQty);
                sku.setWindowPlanQty(strictTargetQty);
                // 仅补欠产不能沿用已被放大的账本剩余额度，否则会提前消耗 T+3 到月底后续计划。
                capDailyQuotaRemainingToTarget(sku, strictTargetQty);
                sku.setWindowRemainingPlanQty(strictTargetQty);
                log.info("{}窗口无日计划但月底仍有计划，仅补本月欠产, materialCode: {}, "
                                + "historyShortageQty: {}, futurePlanQtyAfterWindow: {}, strictTargetQty: {}",
                        sceneName, sku.getMaterialCode(), historyShortageQty,
                        sku.getFutureMonthPlanQtyAfterWindow(), strictTargetQty);
                return plan;
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
     * <p>该方法只回答“是否继续加机台”，不代表当前 SKU 是否还有月计划剩余；
     * 月计划剩余可能继续留到后续窗口滚动处理。</p>
     *
     * @param context 排程上下文
     * @param sku SKU
     * @return true-需要继续增机台；false-当前机台已满足规则
     */
    public static boolean needMoreMachine(LhScheduleContext context, SkuScheduleDTO sku) {
        if (isSmallShortageRollingSatisfied(context, sku)) {
            // 欠产未超过阈值且后续日计划已被当前产能覆盖时，停止继续追加机台。
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
     * <p>用于“不要仅因小额欠产盲目加机台”的公共口径：首日历史欠产可以滚动，
     * 但后续 dayN 计划不能被当前机台产能拖垮。</p>
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
     * <p>历史欠产为 0 时，仅在“本轮目标量高于窗口计划量、且后续日计划仍需承接”的 spillover 场景下沿用该口径，
     * 避免退回窗口需消化量强制补机，同时不影响正式新增的正常扩机。</p>
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
        if (historyShortageQty > 0) {
            return historyShortageQty <= resolveShortageAddMachineThreshold(context);
        }
        return isZeroHistoryWindowSpilloverScenario(sku);
    }

    /**
     * 判断除首日以外的后续日计划额度是否已经满足。
     * <p>首日通常承接历史欠产，不能单独作为是否继续扩机台的决定项；
     * 这里从第二个业务日开始检查，确认后续 dayN 已无剩余额度。</p>
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
     * 判断当前机台数是否已满足欠产未超阈值时的逐日后看规则。
     * <p>该口径用于 S4.4 续作补偿和换活字块回流 S4.5 前置判断：
     * 本月前日累计欠产未超过阈值时，不要求当前窗口小额剩余全部清完，只检查后续日计划是否会被当前机台产能拖垮。</p>
     *
     * @param context 排程上下文
     * @param sku SKU
     * @param activeMachineCount 当前已承接机台数
     * @return true-后续日计划已满足，不需要继续增机台；false-仍需按原缺口规则判断
     */
    public static boolean isDailyLookAheadCapacitySatisfied(LhScheduleContext context,
                                                            SkuScheduleDTO sku,
                                                            int activeMachineCount) {
        if (Objects.isNull(sku) || sku.isStrictNewSpecShortageOnly()
                || CollectionUtils.isEmpty(sku.getDailyPlanQuotaMap())
                || Math.max(0, sku.getWindowPlanQty()) <= 0
                || Math.max(0, activeMachineCount) <= 0
                || Math.max(0, sku.getShiftCapacity()) <= 0) {
            return false;
        }
        int threshold = Math.max(0, resolveShortageAddMachineThreshold(context));
        int historyShortageQty = Math.max(0, sku.getMonthlyHistoryShortageQty());
        if (threshold <= 0 || historyShortageQty > threshold) {
            return false;
        }
        int threeShiftCapacityQty = Math.max(0, activeMachineCount) * Math.max(0, sku.getShiftCapacity()) * 3;
        LocalDate previousDate = null;
        for (LocalDate productionDate : sku.getDailyPlanQuotaMap().keySet()) {
            if (Objects.nonNull(previousDate)) {
                SkuDailyPlanQuotaDTO quota = sku.getDailyPlanQuotaMap().get(productionDate);
                int dayPlanQty = quota == null ? 0 : Math.max(0, quota.getDayPlanQty());
                if (dayPlanQty > threeShiftCapacityQty) {
                    return false;
                }
            }
            previousDate = productionDate;
        }
        return true;
    }

    private static boolean isZeroHistoryWindowSpilloverScenario(SkuScheduleDTO sku) {
        if (Objects.isNull(sku) || CollectionUtils.isEmpty(sku.getDailyPlanQuotaMap())) {
            return false;
        }
        int windowPlanQty = Math.max(0, sku.getWindowPlanQty());
        int currentTargetQty = Math.max(0, sku.resolveTargetScheduleQty());
        int shiftFillOverQty = Math.max(0, sku.getShiftFillOverQty());
        // 新增主循环会临时把 targetScheduleQty 改成本机台计划量，不能据此丢失“窗口计划外补满尾量”的场景标识。
        if (currentTargetQty <= windowPlanQty && shiftFillOverQty <= 0) {
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
            if (Math.max(0, quota.getDayPlanQty()) > 0) {
                return true;
            }
        }
        return false;
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
     * 将窗口日计划账本剩余额度收敛到本月历史欠产目标量。
     * <p>窗口无日计划但月底仍有计划时，账本中只允许保留本月 T 日前欠产；
     * 如果共享账本或历史兼容数据已经带入更大剩余额度，必须在这里按目标量截断，
     * 避免 S4.5 后续扣账时提前消耗 T+3 到月底的未来日计划。</p>
     *
     * @param sku SKU
     * @param targetQty 本月历史欠产补排目标量
     */
    private static void capDailyQuotaRemainingToTarget(SkuScheduleDTO sku, int targetQty) {
        if (Objects.isNull(sku) || CollectionUtils.isEmpty(sku.getDailyPlanQuotaMap())) {
            return;
        }
        int remainingLimitQty = Math.max(0, targetQty);
        for (SkuDailyPlanQuotaDTO quota : sku.getDailyPlanQuotaMap().values()) {
            if (Objects.isNull(quota)) {
                continue;
            }
            int cappedRemainingQty = Math.min(Math.max(0, quota.getRemainingQty()), remainingLimitQty);
            quota.setRemainingQty(cappedRemainingQty);
            remainingLimitQty -= cappedRemainingQty;
        }
        SkuDailyPlanQuotaUtil.refreshRollingFields(sku.getDailyPlanQuotaMap());
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

}
