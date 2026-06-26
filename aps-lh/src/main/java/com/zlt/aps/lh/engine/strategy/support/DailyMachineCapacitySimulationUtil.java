package com.zlt.aps.lh.engine.strategy.support;

import com.zlt.aps.lh.api.domain.dto.SkuDailyPlanQuotaDTO;
import com.zlt.aps.lh.util.SkuDailyPlanQuotaUtil;
import org.springframework.util.CollectionUtils;

import java.time.LocalDate;
import java.util.Map;
import java.util.Objects;

/**
 * dayN 机台产能模拟工具。
 * <p>只模拟 day1/day2/day3 需求与 activeMachines 产能是否满足，不改排程结果、不扣日计划账本。</p>
 */
public final class DailyMachineCapacitySimulationUtil {

    private static final int WINDOW_TOTAL_SHIFT_COUNT = 8;
    private static final int NEXT_DAY_SHIFT_COUNT = 3;
    private static final String MODE_WINDOW_DEMAND = "窗口需消化量";
    private static final String MODE_FORCED_SHORTAGE_WINDOW = "欠产阈值窗口回落";
    private static final String MODE_WINDOW_TOTAL_CAPACITY = "8班窗口总产能";
    private static final String MODE_CURRENT_DAY_PLAN_SATISFIED = "当前日计划已满足";
    private static final String MODE_CURRENT_DAY_CAPACITY = "当前日计划缺口";
    private static final String MODE_NEXT_DAY_CAPACITY = "后一天3班产能";
    private static final String MODE_WINDOW_LAST_DAY = "窗口末日";

    private DailyMachineCapacitySimulationUtil() {
    }

    /**
     * 按最小增量模拟新增机台。
     *
     * <p>模拟目的：</p>
     * <ul>
     *   <li>判断当前 SKU 是否需要从 1 台扩到多台，而不是直接按候选机台数全量铺开；</li>
     *   <li>优先确认 8 班窗口总产能是否覆盖窗口计划，避免真实换模后残班不足导致过度扩机；</li>
     *   <li>本月历史欠产超过阈值时，按窗口后剩余欠产是否回到阈值以内判断是否继续增机台；</li>
     *   <li>欠产未超阈值时保持逐日后看口径，新增排产可在窗口末日继续后看 T+3 日计划；</li>
     *   <li>只读模拟，不修改排程结果、机台状态和 SKU 日计划账本。</li>
     * </ul>
     *
     * @param request 模拟请求
     * @return 模拟结果
     */
    public static DailyMachineCapacitySimulationResult simulateExpansion(
            DailyMachineCapacitySimulationRequest request) {
        DailyMachineCapacitySimulationResult result = new DailyMachineCapacitySimulationResult();
        if (request == null || CollectionUtils.isEmpty(request.getDailyPlanQuotaMap())
                || CollectionUtils.isEmpty(request.getMachineDailyCapacityList())) {
            return result;
        }
        // activeMachines 表示已启用机台数，从当前实际已排/当前候选起步，每次只增加 1 台验证是否足够。
        int activeMachines = Math.max(1, request.getInitialActiveMachines());
        int maxMachineCount = request.getMachineDailyCapacityList().size();
        // carryShortage 是模拟滚动欠产，T 日取账本初始缺口，后续日期沿用上一日模拟后的欠产。
        int carryShortage = resolveInitialCarryShortage(request);
        LocalDate firstProductionDate = resolveFirstProductionDate(request);
        int totalUnmetQty = 0;
        boolean forcedShortageWindowTriggered = false;
        for (Map.Entry<LocalDate, SkuDailyPlanQuotaDTO> entry : request.getDailyPlanQuotaMap().entrySet()) {
            LocalDate productionDate = entry.getKey();
            if (Objects.isNull(productionDate) || isAfterWindowEnd(productionDate, request.getWindowEndDate())) {
                continue;
            }
            LocalDate lookAheadEndDate = resolveSimulationLookAheadEndDate(request, productionDate);
            DailyMachineCapacityDayDecision decision = buildDayDecision(
                    request, productionDate, lookAheadEndDate, activeMachines, carryShortage,
                    firstProductionDate);
            while (decision.getUnmetQty() > 0 && activeMachines < maxMachineCount) {
                // 只有当前启用机台无法覆盖本日决策口径时，才逐台扩展，避免小欠产场景下直接占满候选机台。
                activeMachines++;
                decision.setAddedMachineCount(decision.getAddedMachineCount() + 1);
                decision = refreshDayDecision(request, decision, activeMachines, firstProductionDate);
            }
            decision.setActiveMachineCount(activeMachines);
            decision.setChanged(decision.getAddedMachineCount() > 0);
            decision.setReason(resolveDecisionReason(decision));
            result.getDayDecisionList().add(decision);
            carryShortage = decision.getDayShortageQty();
            totalUnmetQty = Math.max(totalUnmetQty, decision.getUnmetQty());
            forcedShortageWindowTriggered = forcedShortageWindowTriggered || decision.isShortageThresholdExceeded();
        }
        result.setFinalActiveMachines(activeMachines);
        int totalAdded = 0;
        for (DailyMachineCapacityDayDecision decision : result.getDayDecisionList()) {
            totalAdded += decision.getAddedMachineCount();
        }
        result.setTotalAddedMachineCount(totalAdded);
        /*
         * 强制欠产模式下，停止条件已经切换成“窗口后剩余欠产是否回到阈值以内”，
         * 这里返回的未满足量也必须使用“距离阈值仍不足的缺口”口径，
         * 不能再把逐日滚动欠产 carryShortage 混进来，否则会把已回到阈值内的场景误判成仍不足。
         */
        result.setTotalUnmetQty(forcedShortageWindowTriggered
                ? Math.max(0, totalUnmetQty)
                : Math.max(0, carryShortage));
        return result;
    }

    private static DailyMachineCapacityDayDecision buildDayDecision(
            DailyMachineCapacitySimulationRequest request,
            LocalDate productionDate,
            LocalDate lookAheadEndDate,
            int activeMachines,
            int carryShortage,
            LocalDate firstProductionDate) {
        DailyMachineCapacityDayDecision decision = new DailyMachineCapacityDayDecision();
        decision.setProductionDate(productionDate);
        decision.setLookAheadEndDate(lookAheadEndDate);
        decision.setCarryShortageQty(Math.max(0, carryShortage));
        decision.setTodayPlanQty(resolveTodayPlanQty(request.getDailyPlanQuotaMap().get(productionDate)));
        return refreshDayDecision(request, decision, activeMachines, firstProductionDate);
    }

    private static DailyMachineCapacityDayDecision refreshDayDecision(
            DailyMachineCapacitySimulationRequest request,
            DailyMachineCapacityDayDecision decision,
            int activeMachines,
            LocalDate firstProductionDate) {
        int todayCapacityQty = sumCapacityQty(request, decision.getProductionDate(),
                decision.getProductionDate(), activeMachines);
        int currentShortageQty = resolveCurrentThresholdShortage(request, decision, firstProductionDate);
        boolean forcedShortageWindowMode = shouldUseForcedShortageWindowMode(request);
        boolean legacyWindowDemandMode = shouldUseLegacyWindowDemandMode(request);
        // windowTotalCapacityQty 使用“机台数 * 班产 * 8班”的理论窗口产能，专门防止真实换模残班不足导致误加机台。
        int windowPlanQty = sumWindowPlanQty(request.getDailyPlanQuotaMap(),
                decision.getProductionDate(), request.getWindowEndDate());
        int windowTotalCapacityQty = resolveWindowTotalCapacityQty(request, activeMachines);
        int windowMonthPlanQty = resolveWindowMonthPlanQty(request);
        int scheduleDayFinishQty = Math.max(0, request.getScheduleDayFinishQty());
        int currentDayPlanQty = resolveCurrentDayPlanQty(decision, firstProductionDate, scheduleDayFinishQty);
        // todayRequiredQty 是当前日需要承接的量：当前日判断计划量 + 前序模拟滚动下来的欠产量。
        int todayRequiredQty = decision.getCarryShortageQty() + currentDayPlanQty;
        int windowEffectiveCapacityQty = sumCapacityQty(request, firstProductionDate,
                request.getWindowEndDate(), activeMachines);
        int windowRemainingShortageQty = 0;
        int nextDayPlanQty = 0;
        int currentDayThreeShiftCapacityQty = resolveDailyTheoryCapacityQty(
                request, decision.getProductionDate(), activeMachines);
        boolean currentDayPlanSatisfied = currentDayPlanQty <= currentDayThreeShiftCapacityQty;
        int nextDayThreeShiftCapacityQty = 0;
        LocalDate nextProductionDate = resolveNextProductionDate(
                request, decision.getProductionDate(), forcedShortageWindowMode);
        if (Objects.nonNull(nextProductionDate)) {
            nextDayPlanQty = resolveTodayPlanQty(request.getDailyPlanQuotaMap().get(nextProductionDate));
            nextDayThreeShiftCapacityQty = resolveDailyTheoryCapacityQty(
                    request, nextProductionDate, activeMachines);
        }
        int demandQty;
        int capacityQty;
        String decisionMode;
        boolean nextDayLookAheadEntered = false;
        if (forcedShortageWindowMode) {
            /*
             * 本月前日累计欠产超过阈值后，只把阈值作为强制判断入口：
             * 加机台目标不是清空全部欠产，而是让“窗口后剩余欠产”回到阈值以内。
             * 因此这里复用候选机台真实有效产能图逐台累加，不按班产*班次数粗算，
             * 也不因 T+1/T+2 滚动欠产再次触发额外强制增机台。
             */
            demandQty = Math.max(0, Math.max(0, request.getMonthlyHistoryShortageQty())
                    + windowMonthPlanQty - scheduleDayFinishQty);
            capacityQty = windowEffectiveCapacityQty;
            windowRemainingShortageQty = Math.max(0, demandQty - capacityQty);
            decisionMode = MODE_FORCED_SHORTAGE_WINDOW;
        } else if (legacyWindowDemandMode) {
            // 未配置阈值的老调用保持原后看窗口模拟口径，避免影响既有扩机节奏。
            demandQty = currentShortageQty + sumDemandQty(request.getDailyPlanQuotaMap(),
                    decision.getProductionDate(), decision.getLookAheadEndDate());
            capacityQty = sumCapacityQty(request, decision.getProductionDate(),
                    decision.getLookAheadEndDate(), activeMachines);
            decisionMode = MODE_WINDOW_DEMAND;
        } else if (currentDayPlanSatisfied) {
            /*
             * 欠产未超过阈值时，每个业务日先判断当前机台数是否已满足当前日月计划量。
             * 窗口首日按日计划账本口径扣除T日晚班已完成量，避免完成量已覆盖的首日计划继续后看。
             * 当前日已满足则不继续后看下一日，避免因为后续高日计划提前在当前日盲目加机台。
             */
            demandQty = currentDayPlanQty;
            capacityQty = todayCapacityQty;
            decisionMode = MODE_CURRENT_DAY_PLAN_SATISFIED;
        } else if (Objects.nonNull(nextProductionDate)) {
            /*
             * 欠产未超过阈值时，只有当前机台数同时无法满足当前日和后一天计划，才允许增加机台。
             * 当前日不满足后必须直接按后一天计划缺口判断，不能再按当前日缺口提前扩机；
             * T+2 仍通过 nextProductionDate 后看 T+3，但不提前消费 T+3 日计划。
             */
            demandQty = nextDayPlanQty;
            capacityQty = nextDayThreeShiftCapacityQty;
            decisionMode = MODE_NEXT_DAY_CAPACITY;
            nextDayLookAheadEntered = true;
        } else if (isWindowTotalCapacitySatisfied(windowPlanQty, windowTotalCapacityQty)) {
            /*
             * 当前日未满足但没有下一生产日可后看时，才允许用窗口总产能兜底拦截，
             * 避免有下一日计划的场景绕过“当前日未满足再后看下一日”的增机台规则。
             */
            demandQty = windowPlanQty;
            capacityQty = windowTotalCapacityQty;
            decisionMode = MODE_WINDOW_TOTAL_CAPACITY;
        } else if (isWindowTotalCapacityInsufficient(windowPlanQty, windowTotalCapacityQty)
                && currentDayPlanQty > currentDayThreeShiftCapacityQty) {
            // 窗口末日无下一日可后看时，保留当前日缺口触发增机台能力。
            demandQty = todayRequiredQty;
            capacityQty = todayCapacityQty;
            decisionMode = MODE_CURRENT_DAY_CAPACITY;
        } else {
            demandQty = 0;
            capacityQty = 0;
            decisionMode = MODE_WINDOW_LAST_DAY;
        }
        decision.setCurrentDayPlanQty(currentDayPlanQty);
        decision.setTodayRequiredQty(todayRequiredQty);
        decision.setTodayCapacityQty(todayCapacityQty);
        decision.setDayShortageQty(Math.max(0, todayRequiredQty - todayCapacityQty));
        decision.setDemandQty(demandQty);
        decision.setCapacityQty(capacityQty);
        decision.setDecisionMode(decisionMode);
        decision.setWindowTotalCapacityQty(windowTotalCapacityQty);
        decision.setWindowPlanQty(windowPlanQty);
        decision.setWindowMonthPlanQty(windowMonthPlanQty);
        decision.setScheduleDayFinishQty(scheduleDayFinishQty);
        decision.setWindowEffectiveCapacityQty(windowEffectiveCapacityQty);
        decision.setWindowRemainingShortageQty(windowRemainingShortageQty);
        decision.setShortageAddMachineThreshold(Math.max(0, request.getShortageAddMachineThreshold()));
        decision.setShortageThresholdExceeded(forcedShortageWindowMode);
        decision.setNextDayPlanQty(nextDayPlanQty);
        decision.setNextProductionDate(nextProductionDate);
        decision.setNextDayThreeShiftCapacityQty(nextDayThreeShiftCapacityQty);
        decision.setCurrentDayPlanSatisfied(currentDayPlanSatisfied);
        decision.setNextDayLookAheadEntered(nextDayLookAheadEntered);
        if (forcedShortageWindowMode) {
            decision.setUnmetQty(Math.max(0,
                    windowRemainingShortageQty - Math.max(0, request.getShortageAddMachineThreshold())));
        } else {
            decision.setUnmetQty(Math.max(0, demandQty - capacityQty));
        }
        return decision;
    }

    private static boolean isWindowTotalCapacitySatisfied(int windowPlanQty, int windowTotalCapacityQty) {
        return windowPlanQty > 0 && windowTotalCapacityQty >= windowPlanQty;
    }

    private static boolean isWindowTotalCapacityInsufficient(int windowPlanQty, int windowTotalCapacityQty) {
        return windowPlanQty > 0 && windowTotalCapacityQty < windowPlanQty;
    }

    private static int resolveCurrentDayPlanQty(DailyMachineCapacityDayDecision decision,
                                                LocalDate firstProductionDate,
                                                int scheduleDayFinishQty) {
        if (Objects.isNull(decision) || Objects.isNull(decision.getProductionDate())
                || Objects.isNull(firstProductionDate)
                || !decision.getProductionDate().equals(firstProductionDate)) {
            return Math.max(0, Objects.isNull(decision) ? 0 : decision.getTodayPlanQty());
        }
        return Math.max(0, decision.getTodayPlanQty() - Math.max(0, scheduleDayFinishQty));
    }

    /**
     * 判断是否进入欠产阈值强制窗口判断。
     * <p>本月前日累计欠产超过阈值时进入；结构已收尾但SKU余量较大时也可由调用侧显式强制进入。
     * T+1/T+2滚动欠产不能反向触发强制增机台。</p>
     *
     * @param request 模拟请求
     * @return true-按窗口后剩余欠产判断；false-按原小欠产规则判断
     */
    private static boolean shouldUseForcedShortageWindowMode(DailyMachineCapacitySimulationRequest request) {
        return isShortageThresholdEnabled(request)
                && (request.isForceShortageWindowMode()
                || Math.max(0, request.getMonthlyHistoryShortageQty()) > request.getShortageAddMachineThreshold());
    }

    private static boolean shouldUseLegacyWindowDemandMode(DailyMachineCapacitySimulationRequest request) {
        return !isShortageThresholdEnabled(request);
    }

    /**
     * 解析用于阈值判断的当前累计欠产。
     *
     * @param request 模拟请求
     * @param decision 当前业务日决策
     * @param firstProductionDate 窗口首日
     * @return 当前累计欠产
     */
    private static int resolveCurrentThresholdShortage(DailyMachineCapacitySimulationRequest request,
                                                       DailyMachineCapacityDayDecision decision,
                                                       LocalDate firstProductionDate) {
        if (decision == null) {
            return 0;
        }
        int carryShortageQty = Math.max(0, decision.getCarryShortageQty());
        if (Objects.nonNull(firstProductionDate) && firstProductionDate.equals(decision.getProductionDate())) {
            return Math.max(carryShortageQty, Math.max(0, request.getMonthlyHistoryShortageQty()));
        }
        return carryShortageQty;
    }

    private static int sumDemandQty(Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap,
                                    LocalDate productionDate,
                                    LocalDate lookAheadEndDate) {
        int demandQty = 0;
        for (Map.Entry<LocalDate, SkuDailyPlanQuotaDTO> entry : quotaMap.entrySet()) {
            if (entry.getKey().isBefore(productionDate) || entry.getKey().isAfter(lookAheadEndDate)) {
                continue;
            }
            SkuDailyPlanQuotaDTO quota = entry.getValue();
            demandQty += resolveTodayPlanQty(quota);
        }
        return demandQty;
    }

    private static int resolveInitialCarryShortage(DailyMachineCapacitySimulationRequest request) {
        LocalDate firstProductionDate = resolveFirstProductionDate(request);
        if (Objects.isNull(firstProductionDate)) {
            return 0;
        }
        SkuDailyPlanQuotaDTO quota = request.getDailyPlanQuotaMap().get(firstProductionDate);
        if (Objects.isNull(quota)) {
            return 0;
        }
        return Math.max(0, quota.getRemainingQty() - Math.max(0, quota.getDayPlanQty()));
    }

    private static int sumWindowPlanQty(Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap,
                                        LocalDate startProductionDate,
                                        LocalDate windowEndDate) {
        if (CollectionUtils.isEmpty(quotaMap) || Objects.isNull(startProductionDate)) {
            return 0;
        }
        int windowPlanQty = 0;
        for (Map.Entry<LocalDate, SkuDailyPlanQuotaDTO> entry : quotaMap.entrySet()) {
            LocalDate date = entry.getKey();
            if (Objects.isNull(date) || date.isBefore(startProductionDate) || isAfterWindowEnd(date, windowEndDate)) {
                continue;
            }
            windowPlanQty += resolveTodayPlanQty(entry.getValue());
        }
        return Math.max(0, windowPlanQty);
    }

    private static int resolveWindowMonthPlanQty(DailyMachineCapacitySimulationRequest request) {
        if (request == null) {
            return 0;
        }
        if (request.getWindowMonthPlanQty() > 0) {
            return request.getWindowMonthPlanQty();
        }
        LocalDate firstProductionDate = resolveFirstProductionDate(request);
        return sumWindowPlanQty(request.getDailyPlanQuotaMap(), firstProductionDate, request.getWindowEndDate());
    }

    private static int resolveWindowTotalCapacityQty(DailyMachineCapacitySimulationRequest request,
                                                     int activeMachines) {
        if (request == null || request.getShiftCapacity() <= 0) {
            return 0;
        }
        int singleMachineWindowCapacityQty = request.getSingleMachineWindowCapacityQty();
        if (singleMachineWindowCapacityQty > 0) {
            return Math.max(0, activeMachines) * singleMachineWindowCapacityQty;
        }
        return Math.max(0, activeMachines) * Math.max(0, request.getShiftCapacity()) * WINDOW_TOTAL_SHIFT_COUNT;
    }

    private static int resolveDailyTheoryCapacityQty(DailyMachineCapacitySimulationRequest request,
                                                     LocalDate productionDate,
                                                     int activeMachines) {
        if (request == null || request.getShiftCapacity() <= 0) {
            return 0;
        }
        if (!CollectionUtils.isEmpty(request.getSingleMachineDailyCapacityMap())
                && Objects.nonNull(productionDate)) {
            Integer singleMachineDailyCapacityQty = request.getSingleMachineDailyCapacityMap().get(productionDate);
            if (singleMachineDailyCapacityQty != null && singleMachineDailyCapacityQty > 0) {
                return Math.max(0, activeMachines) * singleMachineDailyCapacityQty;
            }
        }
        return Math.max(0, activeMachines) * Math.max(0, request.getShiftCapacity()) * NEXT_DAY_SHIFT_COUNT;
    }

    private static boolean isShortageThresholdEnabled(DailyMachineCapacitySimulationRequest request) {
        return request != null && request.getShortageAddMachineThreshold() > 0;
    }

    /**
     * 解析 dayN 模拟后看截止日。
     * <p>新增排产小欠产场景需要让 T+2 的模拟日志和决策口径看到 T+3；
     * 强制欠产窗口回落仍按 T~T+2 截断，避免改变超阈值增机台口径。</p>
     *
     * @param request 模拟请求
     * @param productionDate 当前生产日
     * @return 后看截止日
     */
    private static LocalDate resolveSimulationLookAheadEndDate(
            DailyMachineCapacitySimulationRequest request,
            LocalDate productionDate) {
        LocalDate lookAheadEndDate = SkuDailyPlanQuotaUtil.resolveLookAheadEndDate(
                request.getDailyPlanQuotaMap(), productionDate,
                request.getShortageLookAheadDays(), request.getWindowEndDate());
        if (shouldAllowWindowLastDayNextPlanLookAhead(request, productionDate,
                Objects.isNull(request.getWindowEndDate()) ? null : request.getWindowEndDate().plusDays(1),
                shouldUseForcedShortageWindowMode(request))) {
            LocalDate nextDate = request.getWindowEndDate().plusDays(1);
            if (request.getDailyPlanQuotaMap().containsKey(nextDate)) {
                return nextDate;
            }
        }
        return lookAheadEndDate;
    }

    private static LocalDate resolveNextProductionDate(DailyMachineCapacitySimulationRequest request,
                                                       LocalDate productionDate,
                                                       boolean forcedShortageWindowMode) {
        Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap =
                Objects.isNull(request) ? null : request.getDailyPlanQuotaMap();
        if (CollectionUtils.isEmpty(quotaMap) || Objects.isNull(productionDate)) {
            return null;
        }
        for (LocalDate date : quotaMap.keySet()) {
            if (Objects.isNull(date) || !date.isAfter(productionDate)) {
                continue;
            }
            if (isAfterWindowEnd(date, request.getWindowEndDate())
                    && !shouldAllowWindowLastDayNextPlanLookAhead(
                    request, productionDate, date, forcedShortageWindowMode)) {
                continue;
            }
            return date;
        }
        return null;
    }

    /**
     * 判断窗口末日是否允许继续后看下一日计划。
     * <p>该开关只服务新增排产小欠产场景：T+2 要看 T+3 是否需要保留/新增机台，
     * 但欠产超过阈值的强制窗口回落仍严格按 T~T+2 计算，避免改变强制增机台语义。</p>
     *
     * @param request 模拟请求
     * @param productionDate 当前生产日
     * @param nextDate 下一账本日期
     * @param forcedShortageWindowMode 是否强制欠产窗口模式
     * @return true-允许窗口末日后看下一日；false-仍按窗口结束日截断
     */
    private static boolean shouldAllowWindowLastDayNextPlanLookAhead(
            DailyMachineCapacitySimulationRequest request,
            LocalDate productionDate,
            LocalDate nextDate,
            boolean forcedShortageWindowMode) {
        if (request == null || forcedShortageWindowMode
                || !request.isWindowLastDayNextPlanLookAheadEnabled()
                || Objects.isNull(request.getWindowEndDate())
                || Objects.isNull(productionDate) || Objects.isNull(nextDate)) {
            return false;
        }
        return productionDate.equals(request.getWindowEndDate())
                && nextDate.equals(request.getWindowEndDate().plusDays(1));
    }

    private static LocalDate resolveFirstProductionDate(DailyMachineCapacitySimulationRequest request) {
        if (request == null || CollectionUtils.isEmpty(request.getDailyPlanQuotaMap())) {
            return null;
        }
        for (LocalDate productionDate : request.getDailyPlanQuotaMap().keySet()) {
            if (Objects.nonNull(productionDate) && !isAfterWindowEnd(productionDate, request.getWindowEndDate())) {
                return productionDate;
            }
        }
        return null;
    }

    private static int resolveTodayPlanQty(SkuDailyPlanQuotaDTO quota) {
        if (Objects.isNull(quota)) {
            return 0;
        }
        return Math.max(0, quota.getDayPlanQty());
    }

    private static int sumCapacityQty(DailyMachineCapacitySimulationRequest request,
                                      LocalDate productionDate,
                                      LocalDate lookAheadEndDate,
                                      int activeMachines) {
        int capacityQty = 0;
        int endIndex = Math.min(activeMachines, request.getMachineDailyCapacityList().size());
        for (int index = 0; index < endIndex; index++) {
            Map<LocalDate, Integer> capacityMap = request.getMachineDailyCapacityList().get(index);
            if (CollectionUtils.isEmpty(capacityMap)) {
                continue;
            }
            for (Map.Entry<LocalDate, Integer> entry : capacityMap.entrySet()) {
                if (entry.getKey().isBefore(productionDate) || entry.getKey().isAfter(lookAheadEndDate)) {
                    continue;
                }
                capacityQty += entry.getValue() == null ? 0 : Math.max(0, entry.getValue());
            }
        }
        return capacityQty;
    }

    private static boolean isAfterWindowEnd(LocalDate productionDate, LocalDate windowEndDate) {
        return Objects.nonNull(windowEndDate) && productionDate.isAfter(windowEndDate);
    }

    private static String resolveDecisionReason(DailyMachineCapacityDayDecision decision) {
        if (decision.getAddedMachineCount() > 0) {
            return decision.getDecisionMode() + "不足，按最小增量新增机台";
        }
        if (decision.getUnmetQty() > 0) {
            return "候选机台耗尽后仍不足";
        }
        return "当前启用机台可满足" + decision.getDecisionMode();
    }
}
