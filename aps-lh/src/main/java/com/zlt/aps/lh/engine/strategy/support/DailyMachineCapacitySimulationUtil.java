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

    private DailyMachineCapacitySimulationUtil() {
    }

    /**
     * 按最小增量模拟新增机台。
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
        int activeMachines = Math.max(1, request.getInitialActiveMachines());
        int maxMachineCount = request.getMachineDailyCapacityList().size();
        int carryShortage = resolveInitialCarryShortage(request);
        LocalDate firstProductionDate = resolveFirstProductionDate(request);
        for (Map.Entry<LocalDate, SkuDailyPlanQuotaDTO> entry : request.getDailyPlanQuotaMap().entrySet()) {
            LocalDate productionDate = entry.getKey();
            if (Objects.isNull(productionDate) || isAfterWindowEnd(productionDate, request.getWindowEndDate())) {
                continue;
            }
            LocalDate lookAheadEndDate = SkuDailyPlanQuotaUtil.resolveLookAheadEndDate(
                    request.getDailyPlanQuotaMap(), productionDate,
                    request.getShortageLookAheadDays(), request.getWindowEndDate());
            DailyMachineCapacityDayDecision decision = buildDayDecision(
                    request, productionDate, lookAheadEndDate, activeMachines, carryShortage);
            while (decision.getUnmetQty() > 0 && activeMachines < maxMachineCount) {
                activeMachines++;
                decision.setAddedMachineCount(decision.getAddedMachineCount() + 1);
                decision = refreshDayDecision(request, decision, activeMachines);
            }
            decision.setActiveMachineCount(activeMachines);
            decision.setChanged(decision.getAddedMachineCount() > 0);
            decision.setReason(resolveDecisionReason(decision));
            result.getDayDecisionList().add(decision);
            carryShortage = decision.getDayShortageQty();
        }
        result.setFinalActiveMachines(activeMachines);
        int totalAdded = 0;
        for (DailyMachineCapacityDayDecision decision : result.getDayDecisionList()) {
            totalAdded += decision.getAddedMachineCount();
        }
        result.setTotalAddedMachineCount(totalAdded);
        result.setTotalUnmetQty(Math.max(0, carryShortage));
        return result;
    }

    private static DailyMachineCapacityDayDecision buildDayDecision(
            DailyMachineCapacitySimulationRequest request,
            LocalDate productionDate,
            LocalDate lookAheadEndDate,
            int activeMachines,
            int carryShortage) {
        DailyMachineCapacityDayDecision decision = new DailyMachineCapacityDayDecision();
        decision.setProductionDate(productionDate);
        decision.setLookAheadEndDate(lookAheadEndDate);
        decision.setCarryShortageQty(Math.max(0, carryShortage));
        decision.setTodayPlanQty(resolveTodayPlanQty(request.getDailyPlanQuotaMap().get(productionDate)));
        return refreshDayDecision(request, decision, activeMachines);
    }

    private static DailyMachineCapacityDayDecision refreshDayDecision(
            DailyMachineCapacitySimulationRequest request,
            DailyMachineCapacityDayDecision decision,
            int activeMachines) {
        int todayRequiredQty = decision.getCarryShortageQty() + decision.getTodayPlanQty();
        int todayCapacityQty = sumCapacityQty(request, decision.getProductionDate(),
                decision.getProductionDate(), activeMachines);
        int demandQty = resolveDemandQty(request, decision, todayRequiredQty);
        int capacityQty = resolveCapacityQty(request, decision, activeMachines);
        decision.setTodayRequiredQty(todayRequiredQty);
        decision.setTodayCapacityQty(todayCapacityQty);
        decision.setDayShortageQty(Math.max(0, todayRequiredQty - todayCapacityQty));
        decision.setDemandQty(demandQty);
        decision.setCapacityQty(capacityQty);
        decision.setUnmetQty(Math.max(0, demandQty - capacityQty));
        return decision;
    }

    /**
     * 按新增排产欠产阈值解析当前判断需求量。
     *
     * @param request 模拟请求
     * @param decision 当前业务日决策
     * @param todayRequiredQty 当日计划量与历史欠产合计
     * @return 当前判断需求量
     */
    private static int resolveDemandQty(DailyMachineCapacitySimulationRequest request,
                                        DailyMachineCapacityDayDecision decision,
                                        int todayRequiredQty) {
        if (shouldUseWindowDemandMode(request)) {
            return todayRequiredQty + sumFutureDemandQty(request.getDailyPlanQuotaMap(),
                    decision.getProductionDate(), decision.getLookAheadEndDate());
        }
        LocalDate nextProductionDate = resolveNextProductionDate(
                request.getDailyPlanQuotaMap(), decision.getProductionDate(), request.getWindowEndDate());
        if (Objects.isNull(nextProductionDate)) {
            return 0;
        }
        return resolveTodayPlanQty(request.getDailyPlanQuotaMap().get(nextProductionDate));
    }

    /**
     * 按新增排产欠产阈值解析当前判断产能。
     *
     * @param request 模拟请求
     * @param decision 当前业务日决策
     * @param activeMachines 当前启用机台数
     * @return 当前判断产能
     */
    private static int resolveCapacityQty(DailyMachineCapacitySimulationRequest request,
                                          DailyMachineCapacityDayDecision decision,
                                          int activeMachines) {
        if (shouldUseWindowDemandMode(request)) {
            return sumCapacityQty(request, decision.getProductionDate(),
                    decision.getLookAheadEndDate(), activeMachines);
        }
        LocalDate nextProductionDate = resolveNextProductionDate(
                request.getDailyPlanQuotaMap(), decision.getProductionDate(), request.getWindowEndDate());
        if (Objects.isNull(nextProductionDate)) {
            return 0;
        }
        return sumCapacityQty(request, nextProductionDate, nextProductionDate, activeMachines);
    }

    private static int sumFutureDemandQty(Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap,
                                          LocalDate productionDate,
                                          LocalDate lookAheadEndDate) {
        int demandQty = 0;
        for (Map.Entry<LocalDate, SkuDailyPlanQuotaDTO> entry : quotaMap.entrySet()) {
            if (!entry.getKey().isAfter(productionDate) || entry.getKey().isAfter(lookAheadEndDate)) {
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

    private static boolean isShortageThresholdEnabled(DailyMachineCapacitySimulationRequest request) {
        return request != null && request.getShortageAddMachineThreshold() > 0;
    }

    /**
     * 判断当前是否使用“窗口需消化量”扩机口径。
     * <p>阈值判断必须固定使用 T 日之前的历史欠产量，不能随着 dayN 滚动欠产放大。</p>
     *
     * @param request 模拟请求
     * @return true-按窗口需消化量判断；false-按小欠产逐日后看判断
     */
    private static boolean shouldUseWindowDemandMode(DailyMachineCapacitySimulationRequest request) {
        return !isShortageThresholdEnabled(request)
                || Math.max(0, request.getMonthlyHistoryShortageQty()) > request.getShortageAddMachineThreshold();
    }

    private static LocalDate resolveNextProductionDate(Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap,
                                                       LocalDate productionDate,
                                                       LocalDate windowEndDate) {
        if (CollectionUtils.isEmpty(quotaMap) || Objects.isNull(productionDate)) {
            return null;
        }
        for (LocalDate date : quotaMap.keySet()) {
            if (Objects.isNull(date) || !date.isAfter(productionDate) || isAfterWindowEnd(date, windowEndDate)) {
                continue;
            }
            return date;
        }
        return null;
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
            return "追补窗口产能不足，按最小增量新增机台";
        }
        if (decision.getUnmetQty() > 0) {
            return "候选机台耗尽后仍不足";
        }
        return "当前启用机台可满足追补窗口";
    }
}
