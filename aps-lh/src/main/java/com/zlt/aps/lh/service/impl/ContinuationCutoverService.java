package com.zlt.aps.lh.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.zlt.aps.lh.api.constant.LhScheduleConstant;
import com.zlt.aps.lh.api.domain.dto.MachineScheduleDTO;
import com.zlt.aps.lh.api.domain.dto.SkuDailyPlanQuotaDTO;
import com.zlt.aps.lh.api.domain.dto.SkuScheduleDTO;
import com.zlt.aps.lh.api.domain.entity.LhScheduleResult;
import com.zlt.aps.lh.api.domain.vo.LhShiftConfigVO;
import com.zlt.aps.lh.component.MonthPlanDateResolver;
import com.zlt.aps.lh.component.TargetScheduleQtyResolver;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.engine.strategy.support.ContinuationCutoverResult;
import com.zlt.aps.lh.util.LhSingleControlMachineUtil;
import com.zlt.aps.lh.util.ShiftCapacityResolverUtil;
import com.zlt.aps.lh.util.ShiftFieldUtil;
import com.zlt.aps.lh.util.SkuDailyPlanQuotaUtil;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import javax.annotation.Resource;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 续作机台下机截断与数量账本恢复公共服务。
 *
 * <p>特殊材料置换和共用模具联动置换都需要在一个精确时间点截断续作尾量。该服务统一负责：</p>
 * <ol>
 *     <li>只从 S4.5 前冻结的真实续作结果中查找指定物理机台；</li>
 *     <li>按完整硫化周期保留下机时点前的产量，释放后段机台班次产能；</li>
 *     <li>同步结构/SKU 已排机台登记，删除被完全截断的零量结果；</li>
 *     <li>把截断量恢复到 B 的生产余量、dayN 日计划和满班补齐账本。</li>
 * </ol>
 *
 * <p>本服务不选择 A/B 机台、不分配模具，也不自行捕获快照。调用方必须在调用前建立
 * {@link ScheduleSubstitutionAttemptSnapshot}，任一步失败时由协调器整体恢复。</p>
 *
 * @author APS
 */
@Service
public class ContinuationCutoverService {

    @Resource
    private TargetScheduleQtyResolver targetScheduleQtyResolver;

    /**
     * 按物理机台和来源 SKU 执行续作下机截断。
     *
     * @param context 排程上下文
     * @param sourceSku 被迁移续作物料 B
     * @param physicalMachineCode 原物理机台编码，单控 L/R 使用去侧后的物理编码
     * @param offlineTime B 下机时间
     * @return 截断结果
     */
    public ContinuationCutoverResult cutover(
            LhScheduleContext context,
            SkuScheduleDTO sourceSku,
            String physicalMachineCode,
            Date offlineTime) {
        List<LhScheduleResult> continuationResultList =
                resolveContinuationResults(context, sourceSku, physicalMachineCode);
        if (CollectionUtils.isEmpty(continuationResultList)) {
            throw new IllegalStateException("原物理机台不存在可截断的冻结续作结果: " + physicalMachineCode);
        }
        Map<LhScheduleResult, LhScheduleResult> originalStateMap =
                copyResultStateMap(continuationResultList);
        ContinuationCutoverResult cutoverResult = new ContinuationCutoverResult();
        cutoverResult.setRetainedResultList(continuationResultList);
        int totalRemovedQty = 0;
        for (LhScheduleResult result : continuationResultList) {
            totalRemovedQty += truncateResult(
                    context, sourceSku, result, offlineTime,
                    cutoverResult.getRemovedQtyByDate());
        }
        cutoverResult.setRemovedQty(totalRemovedQty);
        synchronizeScheduledMachineMaps(
                context, originalStateMap.values(), continuationResultList);
        if (totalRemovedQty > 0) {
            // 调用处明确记录本次联动来源，便于生产余量账本日志按 A/B 置换检索。
            targetScheduleQtyResolver.restoreProductionRemainingQty(
                    context, sourceSku, totalRemovedQty,
                    "共用模具置换恢复 B 截断尾量", physicalMachineCode);
            restoreTruncatedDailyQuota(
                    context, sourceSku, cutoverResult.getRemovedQtyByDate());
        }
        return cutoverResult;
    }

    /**
     * 查找物理机台上的冻结续作结果。
     *
     * @param context 排程上下文
     * @param sourceSku 来源 SKU
     * @param physicalMachineCode 物理机台编码
     * @return 同一 B 的冻结续作结果
     */
    public List<LhScheduleResult> resolveContinuationResults(
            LhScheduleContext context,
            SkuScheduleDTO sourceSku,
            String physicalMachineCode) {
        List<LhScheduleResult> resultList = new ArrayList<LhScheduleResult>(2);
        String sourceSkuKey = MonthPlanDateResolver.buildMaterialStatusKey(
                sourceSku.getMaterialCode(), sourceSku.getProductStatus());
        for (LhScheduleResult result : context.getSpecialMaterialContinuationResultSnapshot()) {
            if (Objects.isNull(result)
                    || !context.getScheduleResultList().contains(result)
                    || !StringUtils.equals(physicalMachineCode,
                    LhSingleControlMachineUtil.resolvePhysicalMachineCode(
                            result.getLhMachineCode()))) {
                continue;
            }
            String resultSkuKey = MonthPlanDateResolver.buildMaterialStatusKey(
                    result.getMaterialCode(), result.getProductStatus());
            if (!StringUtils.equals(sourceSkuKey, resultSkuKey)) {
                throw new IllegalStateException(
                        "单控物理机台两侧不是同一续作 SKU，禁止整套模具置换: " + physicalMachineCode);
            }
            resultList.add(result);
        }
        return resultList;
    }

    /**
     * 按下机时间截断单条续作结果，只保留下机前已经完成的完整硫化循环。
     *
     * @param context 排程上下文
     * @param sourceSku 被迁移物料 B
     * @param result 待截断续作结果
     * @param offlineTime B 下机时间
     * @param removedQtyByDate 按业务日累计的截断量
     * @return 本结果实际截断量
     */
    private int truncateResult(
            LhScheduleContext context,
            SkuScheduleDTO sourceSku,
            LhScheduleResult result,
            Date offlineTime,
            Map<LocalDate, Integer> removedQtyByDate) {
        int resultRemovedQty = 0;
        for (int shiftIndex = 1;
             shiftIndex <= LhScheduleConstant.MAX_SHIFT_SLOT_COUNT;
             shiftIndex++) {
            Integer planQty = ShiftFieldUtil.getShiftPlanQty(result, shiftIndex);
            int originalQty = Math.max(0, Objects.isNull(planQty) ? 0 : planQty);
            if (originalQty <= 0) {
                continue;
            }
            int retainedQty = resolveRetainedShiftQty(
                    result, sourceSku, shiftIndex, originalQty, offlineTime);
            int removedQty = Math.max(0, originalQty - retainedQty);
            if (removedQty <= 0) {
                continue;
            }
            Date originalStartTime = ShiftFieldUtil.getShiftStartTime(result, shiftIndex);
            Date retainedEndTime = resolveRetainedShiftEndTime(
                    sourceSku, result, originalStartTime, retainedQty);
            ShiftFieldUtil.setShiftPlanQty(result, shiftIndex, retainedQty,
                    retainedQty > 0 ? originalStartTime : null,
                    retainedQty > 0 ? retainedEndTime : null);
            LocalDate productionDate = resolveShiftBusinessDate(context, shiftIndex);
            if (Objects.nonNull(productionDate)) {
                removedQtyByDate.merge(productionDate, removedQty, Integer::sum);
            }
            releaseMachineShiftCapacity(
                    context, result.getLhMachineCode(), shiftIndex, removedQty);
            resultRemovedQty += removedQty;
        }
        refreshTruncatedContinuationResult(result, resultRemovedQty);
        return resultRemovedQty;
    }

    private int resolveRetainedShiftQty(
            LhScheduleResult result,
            SkuScheduleDTO sourceSku,
            int shiftIndex,
            int originalQty,
            Date offlineTime) {
        Date shiftStartTime = ShiftFieldUtil.getShiftStartTime(result, shiftIndex);
        Date shiftEndTime = ShiftFieldUtil.getShiftEndTime(result, shiftIndex);
        if (Objects.isNull(shiftStartTime) || Objects.isNull(shiftEndTime)) {
            throw new IllegalStateException("被置换续作班次缺少起止时间: "
                    + result.getLhMachineCode() + "#" + shiftIndex);
        }
        if (!shiftEndTime.after(offlineTime)) {
            return originalQty;
        }
        if (!shiftStartTime.before(offlineTime)) {
            return 0;
        }
        int lhTimeSeconds = sourceSku.getLhTimeSeconds();
        int mouldQty = resolveResultMouldQty(result, sourceSku);
        if (lhTimeSeconds <= 0 || mouldQty <= 0) {
            throw new IllegalStateException("被置换续作 SKU 缺少硫化时间或模数: "
                    + sourceSku.getMaterialCode());
        }
        long availableSeconds = Math.max(
                0L, (offlineTime.getTime() - shiftStartTime.getTime()) / 1000L);
        long retainedQty = availableSeconds / lhTimeSeconds * mouldQty;
        return (int) Math.min(originalQty, Math.min(Integer.MAX_VALUE, retainedQty));
    }

    private Date resolveRetainedShiftEndTime(
            SkuScheduleDTO sourceSku,
            LhScheduleResult result,
            Date shiftStartTime,
            int retainedQty) {
        if (retainedQty <= 0 || Objects.isNull(shiftStartTime)) {
            return null;
        }
        int mouldQty = resolveResultMouldQty(result, sourceSku);
        int cureCount = (retainedQty + mouldQty - 1) / mouldQty;
        return new Date(shiftStartTime.getTime()
                + (long) cureCount * sourceSku.getLhTimeSeconds() * 1000L);
    }

    private int resolveResultMouldQty(
            LhScheduleResult result,
            SkuScheduleDTO sourceSku) {
        int mouldQty = Objects.isNull(result.getMouldQty()) ? 0 : result.getMouldQty();
        return mouldQty > 0 ? mouldQty
                : ShiftCapacityResolverUtil.resolveMachineMouldQty(sourceSku.getMouldQty());
    }

    private void refreshTruncatedContinuationResult(
            LhScheduleResult result,
            int removedQty) {
        ShiftFieldUtil.syncDailyPlanQty(result);
        result.setTotalDailyPlanQty(result.getDailyPlanQty());
        ShiftFieldUtil.clearUnplannedShiftCureFormulaFields(result);
        int lastShiftIndex = ShiftFieldUtil.applyLastPlannedShiftEndMark(result, true);
        Date lastEndTime = lastShiftIndex > 0
                ? ShiftFieldUtil.getShiftEndTime(result, lastShiftIndex) : null;
        result.setSpecEndTime(lastEndTime);
        if (Objects.nonNull(result.getTdaySpecEndTime())
                && (Objects.isNull(lastEndTime)
                || result.getTdaySpecEndTime().after(lastEndTime))) {
            result.setTdaySpecEndTime(lastEndTime);
        }
        if (removedQty > 0) {
            int originalSurplusQty = Objects.isNull(result.getMouldSurplusQty())
                    ? 0 : result.getMouldSurplusQty();
            result.setMouldSurplusQty(originalSurplusQty + removedQty);
        }
    }

    private void releaseMachineShiftCapacity(
            LhScheduleContext context,
            String machineCode,
            int shiftIndex,
            int releasedQty) {
        MachineScheduleDTO machine = context.getMachineScheduleMap().get(machineCode);
        int[] machineCapacity = Objects.isNull(machine)
                ? null : machine.getShiftRemainingCapacity();
        int[] contextCapacity = context.getMachineShiftCapacityMap().get(machineCode);
        if (Objects.nonNull(machineCapacity) && shiftIndex < machineCapacity.length) {
            machineCapacity[shiftIndex] += releasedQty;
        }
        if (Objects.nonNull(contextCapacity) && contextCapacity != machineCapacity
                && shiftIndex < contextCapacity.length) {
            contextCapacity[shiftIndex] += releasedQty;
        }
    }

    private void synchronizeScheduledMachineMaps(
            LhScheduleContext context,
            Iterable<LhScheduleResult> originalResultList,
            List<LhScheduleResult> retainedResultList) {
        for (LhScheduleResult originalResult : originalResultList) {
            removeScheduledMachineRegistration(context, originalResult);
        }
        for (LhScheduleResult retainedResult : retainedResultList) {
            if (ShiftFieldUtil.resolveScheduledQty(retainedResult) <= 0) {
                removeZeroQtyContinuationResult(context, retainedResult);
                continue;
            }
            for (LhShiftConfigVO shift : context.getScheduleWindowShifts()) {
                if (Objects.isNull(shift) || Objects.isNull(shift.getShiftIndex())
                        || Objects.isNull(shift.getWorkDate())) {
                    continue;
                }
                Integer planQty = ShiftFieldUtil.getShiftPlanQty(
                        retainedResult, shift.getShiftIndex());
                if (Objects.nonNull(planQty) && planQty > 0) {
                    context.recordScheduledMachine(
                            toLocalDate(shift.getWorkDate()),
                            retainedResult.getStructureName(),
                            retainedResult.getMaterialCode(),
                            retainedResult.getProductStatus(),
                            retainedResult.getLhMachineCode());
                }
            }
        }
    }

    private void removeScheduledMachineRegistration(
            LhScheduleContext context,
            LhScheduleResult result) {
        for (LhShiftConfigVO shift : context.getScheduleWindowShifts()) {
            if (Objects.isNull(shift) || Objects.isNull(shift.getShiftIndex())
                    || Objects.isNull(shift.getWorkDate())) {
                continue;
            }
            Integer planQty = ShiftFieldUtil.getShiftPlanQty(result, shift.getShiftIndex());
            if (Objects.isNull(planQty) || planQty <= 0) {
                continue;
            }
            LocalDate businessDate = toLocalDate(shift.getWorkDate());
            String skuKey = MonthPlanDateResolver.buildMaterialStatusKey(
                    result.getMaterialCode(), result.getProductStatus());
            removeMachineCode(context.getSkuScheduledMachineCodeMap(),
                    businessDate, skuKey, result.getLhMachineCode());
            removeMachineCode(context.getStructureScheduledMachineCodeMap(),
                    businessDate, result.getStructureName(), result.getLhMachineCode());
        }
    }

    private void removeMachineCode(
            Map<LocalDate, Map<String, Set<String>>> targetMap,
            LocalDate businessDate,
            String key,
            String machineCode) {
        if (Objects.isNull(businessDate) || StringUtils.isEmpty(key)
                || CollectionUtils.isEmpty(targetMap)) {
            return;
        }
        Map<String, Set<String>> valueMap = targetMap.get(businessDate);
        if (CollectionUtils.isEmpty(valueMap)) {
            return;
        }
        Set<String> machineCodeSet = valueMap.get(key);
        if (CollectionUtils.isEmpty(machineCodeSet)) {
            return;
        }
        machineCodeSet.remove(machineCode);
        if (machineCodeSet.isEmpty()) {
            valueMap.remove(key);
        }
        if (valueMap.isEmpty()) {
            targetMap.remove(businessDate);
        }
    }

    private void removeZeroQtyContinuationResult(
            LhScheduleContext context,
            LhScheduleResult result) {
        context.getScheduleResultList().remove(result);
        context.getScheduleResultSourceSkuMap().remove(result);
        List<LhScheduleResult> assignmentList =
                context.getMachineAssignmentMap().get(result.getLhMachineCode());
        if (Objects.nonNull(assignmentList)) {
            assignmentList.remove(result);
            if (assignmentList.isEmpty()) {
                context.getMachineAssignmentMap().remove(result.getLhMachineCode());
            }
        }
    }

    /**
     * 把续作截断量恢复到 B 的 dayN 日计划账本并刷新滚动字段。
     *
     * @param context 排程上下文
     * @param sku 物料 B
     * @param removedQtyByDate 按原生产业务日归集的截断量
     */
    private void restoreTruncatedDailyQuota(
            LhScheduleContext context,
            SkuScheduleDTO sku,
            Map<LocalDate, Integer> removedQtyByDate) {
        Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap = sku.getDailyPlanQuotaMap();
        if (CollectionUtils.isEmpty(quotaMap)
                || CollectionUtils.isEmpty(removedQtyByDate)) {
            return;
        }
        List<Map.Entry<LocalDate, Integer>> removedDateEntryList =
                new ArrayList<Map.Entry<LocalDate, Integer>>(removedQtyByDate.entrySet());
        removedDateEntryList.sort(Map.Entry.<LocalDate, Integer>comparingByKey().reversed());
        for (Map.Entry<LocalDate, Integer> removedDateEntry : removedDateEntryList) {
            restoreDateQuota(
                    context, sku, quotaMap,
                    removedDateEntry.getKey(),
                    Math.max(0, removedDateEntry.getValue()));
        }
        SkuDailyPlanQuotaUtil.refreshRollingFields(quotaMap);
    }

    /**
     * 恢复单个生产日对应的已消费额度、实际量、未来借量和满班补齐量。
     *
     * @param context 排程上下文
     * @param sku 物料 B
     * @param quotaMap B 的日计划账本
     * @param productionDate 原生产业务日
     * @param pendingRestoreQty 待恢复量
     */
    private void restoreDateQuota(
            LhScheduleContext context,
            SkuScheduleDTO sku,
            Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap,
            LocalDate productionDate,
            int pendingRestoreQty) {
        if (pendingRestoreQty <= 0) {
            return;
        }
        List<Map.Entry<LocalDate, SkuDailyPlanQuotaDTO>> quotaEntryList =
                new ArrayList<Map.Entry<LocalDate, SkuDailyPlanQuotaDTO>>(quotaMap.entrySet());
        Collections.reverse(quotaEntryList);
        int restoredQuotaQty = 0;
        int restoredFutureBorrowQty = 0;
        for (Map.Entry<LocalDate, SkuDailyPlanQuotaDTO> quotaEntry : quotaEntryList) {
            if (restoredQuotaQty >= pendingRestoreQty) {
                break;
            }
            SkuDailyPlanQuotaDTO quota = quotaEntry.getValue();
            if (Objects.isNull(quota)) {
                continue;
            }
            int restoredQty = Math.min(
                    Math.max(0, quota.getScheduledQty()),
                    pendingRestoreQty - restoredQuotaQty);
            if (restoredQty <= 0) {
                continue;
            }
            quota.setScheduledQty(quota.getScheduledQty() - restoredQty);
            quota.setRemainingQty(quota.getRemainingQty() + restoredQty);
            restoredQuotaQty += restoredQty;
            if (quotaEntry.getKey().isAfter(productionDate)) {
                restoredFutureBorrowQty += restoredQty;
            }
        }
        SkuDailyPlanQuotaDTO productionQuota = quotaMap.get(productionDate);
        if (Objects.nonNull(productionQuota)) {
            productionQuota.setActualQty(Math.max(
                    0, productionQuota.getActualQty() - restoredQuotaQty));
            productionQuota.setFutureBorrowQty(Math.max(
                    0, productionQuota.getFutureBorrowQty() - restoredFutureBorrowQty));
        }
        restoreShiftFillOverQty(
                context, sku, productionQuota,
                Math.max(0, pendingRestoreQty - restoredQuotaQty));
    }

    private void restoreShiftFillOverQty(
            LhScheduleContext context,
            SkuScheduleDTO sku,
            SkuDailyPlanQuotaDTO productionQuota,
            int restoredShiftFillOverQty) {
        if (restoredShiftFillOverQty <= 0) {
            return;
        }
        if (Objects.nonNull(productionQuota)) {
            productionQuota.setShiftFillOverQty(Math.max(
                    0, productionQuota.getShiftFillOverQty()
                            - restoredShiftFillOverQty));
        }
        sku.setShiftFillOverQty(Math.max(
                0, sku.getShiftFillOverQty() - restoredShiftFillOverQty));
        String skuKey = MonthPlanDateResolver.buildMaterialStatusKey(
                sku.getMaterialCode(), sku.getProductStatus());
        int accumulatedQty = context.getSkuShiftFillOverQtyMap()
                .getOrDefault(skuKey, 0);
        context.getSkuShiftFillOverQtyMap().put(
                skuKey, Math.max(0, accumulatedQty - restoredShiftFillOverQty));
    }

    private Map<LhScheduleResult, LhScheduleResult> copyResultStateMap(
            List<LhScheduleResult> resultList) {
        Map<LhScheduleResult, LhScheduleResult> stateMap =
                new IdentityHashMap<LhScheduleResult, LhScheduleResult>(
                        Math.max(4, resultList.size() * 2));
        for (LhScheduleResult result : resultList) {
            LhScheduleResult copy = new LhScheduleResult();
            BeanUtil.copyProperties(result, copy);
            stateMap.put(result, copy);
        }
        return stateMap;
    }

    private LocalDate resolveShiftBusinessDate(
            LhScheduleContext context,
            int shiftIndex) {
        for (LhShiftConfigVO shift : context.getScheduleWindowShifts()) {
            if (Objects.nonNull(shift) && Objects.nonNull(shift.getShiftIndex())
                    && shift.getShiftIndex() == shiftIndex
                    && Objects.nonNull(shift.getWorkDate())) {
                return toLocalDate(shift.getWorkDate());
            }
        }
        return null;
    }

    private LocalDate toLocalDate(Date date) {
        return Objects.isNull(date)
                ? null : date.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
    }
}
