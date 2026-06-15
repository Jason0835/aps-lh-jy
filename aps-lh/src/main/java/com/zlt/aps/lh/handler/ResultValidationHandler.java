package com.zlt.aps.lh.handler;

import cn.hutool.core.date.DateUtil;
import com.zlt.aps.lh.api.constant.LhScheduleConstant;
import com.zlt.aps.lh.api.constant.LhScheduleParamConstant;
import com.zlt.aps.lh.api.domain.dto.MachineCleaningWindowDTO;
import com.zlt.aps.lh.api.domain.dto.MachineScheduleDTO;
import com.zlt.aps.lh.api.domain.dto.SkuDailyPlanQuotaDTO;
import com.zlt.aps.lh.api.domain.dto.SkuScheduleDTO;
import com.zlt.aps.lh.api.domain.entity.LhMouldChangePlan;
import com.zlt.aps.lh.api.domain.entity.LhScheduleProcessLog;
import com.zlt.aps.lh.api.domain.entity.LhScheduleResult;
import com.zlt.aps.lh.api.domain.entity.LhUnscheduledResult;
import com.zlt.aps.lh.api.domain.vo.LhShiftConfigVO;
import com.zlt.aps.lh.api.enums.CleaningTypeEnum;
import com.zlt.aps.lh.api.enums.MouldChangeTypeEnum;
import com.zlt.aps.lh.api.enums.ShiftEnum;
import com.zlt.aps.lh.api.enums.ScheduleStepEnum;
import com.zlt.aps.lh.component.IncrSerialGenerator;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.engine.observer.ScheduleEvent;
import com.zlt.aps.mp.api.domain.entity.FactoryMonthPlanProductionFinalResult;
import com.zlt.aps.lh.engine.observer.ScheduleEventPublisher;
import com.zlt.aps.lh.engine.strategy.support.ProductionQuantityPolicy;
import com.zlt.aps.lh.exception.ScheduleErrorCode;
import com.zlt.aps.lh.exception.ScheduleException;
import com.zlt.aps.lh.service.impl.SchedulePersistenceService;
import com.zlt.aps.lh.util.LhScheduleTimeUtil;
import com.zlt.aps.lh.util.LeftRightMouldUtil;
import com.zlt.aps.lh.util.MonthPlanDayQtyUtil;
import com.zlt.aps.lh.util.ShiftCapacityResolverUtil;
import com.zlt.aps.lh.util.ShiftFieldUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import javax.annotation.Resource;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * S4.6 结果校验与发布保存处理器。
 *
 * <p>主要职责：</p>
 * <ul>
 *   <li>对 S4.4/S4.5 生成的排程结果做必填字段、数量口径和换模约束校验；</li>
 *   <li>根据换模结果、滚动继承状态和清洗计划生成模具交替计划；</li>
 *   <li>补全工单号、排程顺序、汇总日志和日计划滚动账本日志；</li>
 *   <li>执行硫化示方历史班次保护，防止已执行班次被重排结果覆盖；</li>
 *   <li>委托持久化服务以事务方式替换目标日结果，并发布排程完成事件。</li>
 * </ul>
 *
 * <p>注意：该 Handler 处于保存前最后一道业务防线。新增结果字段时需同时确认后置校验、
 * 换模计划生成、历史保护和 Mapper 落库口径。</p>
 *
 * @author APS
 */
@Slf4j
@Component
public class ResultValidationHandler extends AbsScheduleStepHandler {

    @Resource
    private ScheduleEventPublisher scheduleEventPublisher;

    @Resource
    private SchedulePersistenceService schedulePersistenceService;

    private static final AtomicInteger ORDER_SEQ = new AtomicInteger(0);
    private static final AtomicInteger CHG_SEQ = new AtomicInteger(0);
    private static final int ENABLED = 1;
    private static final String CLEANING_DATA_SOURCE_MANUAL = "0";

    @Override
    protected void doHandle(LhScheduleContext context) {
        String scheduleOrderBusinessKey = buildScheduleOrderBusinessKey(context);
        try {
            // S4.6.1 排程后置校验：保存前校验结果必填字段和关键数量约束。
            postValidation(context);

            // S4.6.2 生成模具交替计划：基于结果真实换模开始时间和机台滚动状态生成前后规格。
            generateMouldChangePlan(context);
//            validateMouldChangePlanQuota(context);
            validateManualSundaySandBlastThreshold(context);

            // S4.6.3 补全工单号和发布状态
            assignOrderNumbers(context);

            // S4.6.4 赋值排程顺序
            assignScheduleOrder(context, scheduleOrderBusinessKey);

            // S4.6.5 添加排程汇总日志
            addSummaryLog(context);

            // S4.6.5.1 按SKU+日期汇总校验日计划完成情况
            addDailyPlanSummaryLog(context);

            // S4.6.5.2 硫化示方历史保护：逐班次判断是否保留历史值，避免运行中班次被覆盖。
            applyCureFormulaHistoryProtection(context);

            // S4.6.5.3 无计划量班次不展示硫化示方号和类型，避免空班次携带示方信息。
            clearUnplannedShiftCureFormulaFields(context);

            // S4.6.6 保存排程结果到数据库：由持久化服务统一做目标日原子替换。
            schedulePersistenceService.replaceScheduleAtomically(context);

            // S4.6.7 发布排程完成事件（观察者模式）
            scheduleEventPublisher.publish(ScheduleEvent.completed(context));
        } finally {
            clearScheduleOrderCounter(scheduleOrderBusinessKey);
        }
    }

    /**
     * 清理无计划量班次的硫化示方号和类型。
     *
     * @param context 排程上下文
     */
    private void clearUnplannedShiftCureFormulaFields(LhScheduleContext context) {
        if (CollectionUtils.isEmpty(context.getScheduleResultList())) {
            return;
        }
        for (LhScheduleResult result : context.getScheduleResultList()) {
            ShiftFieldUtil.clearUnplannedShiftCureFormulaFields(result);
        }
    }

    /**
     * 排程后置校验：检查结果完整性。
     *
     * <p>该方法会补齐部分保存所需的默认字段，例如批次号、工厂、目标日和发布状态；
     * 但不会改变机台、班次计划量、排序结果、换模判断和收尾判断。</p>
     *
     * @param context 排程上下文
     */
    private void postValidation(LhScheduleContext context) {
        log.info("执行排程后置校验, 排程结果数: {}, 未排产数: {}",
                context.getScheduleResultList().size(), context.getUnscheduledResultList().size());

        // 校验1：排程结果不能为空（允许全部未排的情况，但记录警告）
        if (context.getScheduleResultList().isEmpty()) {
            log.warn("排程结果为空，可能所有SKU均未成功排产");
        }

        if (StringUtils.isBlank(context.getBatchNo()) || StringUtils.isBlank(context.getFactoryCode())) {
            throw new ScheduleException(ScheduleStepEnum.S4_6_RESULT_VALIDATION,
                    ScheduleErrorCode.RESULT_VALIDATION_FAILED,
                    context.getFactoryCode(), context.getBatchNo(),
                    "批次号或工厂编码为空，无法执行结果保存");
        }

        // 校验2：检查每个排程结果必填字段，字段缺失直接阻断保存，避免脏结果落库。
        for (LhScheduleResult result : context.getScheduleResultList()) {
            if (result.getBatchNo() == null) {
                result.setBatchNo(context.getBatchNo());
            }
            if (result.getFactoryCode() == null) {
                result.setFactoryCode(context.getFactoryCode());
            }
            if (result.getScheduleDate() == null) {
                result.setScheduleDate(context.getScheduleTargetDate());
            }
            if (result.getIsRelease() == null) {
                result.setIsRelease("0");
            }
            normalizeMouldMultiplePlanQty(context, result);
            requireField(result.getBatchNo(), "batchNo", context, result);
            requireField(result.getFactoryCode(), "factoryCode", context, result);
            requireField(result.getLhMachineCode(), "lhMachineCode", context, result);
            result.setLeftRightMould(LeftRightMouldUtil.resolveLeftRightMould(
                    result.getLeftRightMould(), result.getLhMachineCode()));
            requireField(result.getMaterialCode(), "materialCode", context, result);
            requireField(result.getScheduleType(), "scheduleType", context, result);
            if (result.getSpecEndTime() == null) {
                throwValidationFailure(context, result, "specEndTime 缺失");
            }
            if ("1".equals(result.getIsChangeMould()) && StringUtils.isBlank(result.getMouldCode())) {
                throwValidationFailure(context, result, "换模结果 mouldCode 缺失");
            }
        }

//        TODO 这两个校验当前保持历史关闭状态。后续如需打开，应先用真实批次验证同胎胚换模和多机台补满结果。
//        validateGreenTireChangeoverShift(context);
//        validateProductionQuantityPolicy(context);

        log.info("排程后置校验完成");
    }

    /**
     * 保存前统一收敛双模/多模班次计划量。
     * <p>最终结果落库前不允许双模机台出现奇数计划量；目标尾量为奇数时按模台数向上取整。</p>
     *
     * @param context 排程上下文
     * @param result 排程结果
     */
    private void normalizeMouldMultiplePlanQty(LhScheduleContext context, LhScheduleResult result) {
        if (Objects.isNull(result)) {
            return;
        }
        Integer mouldQtyValue = result.getMouldQty();
        int mouldQty = Objects.isNull(mouldQtyValue) ? 0 : mouldQtyValue;
        if (mouldQty <= 1) {
            return;
        }
        boolean adjusted = false;
        for (int shiftIndex = 1; shiftIndex <= LhScheduleConstant.MAX_SHIFT_SLOT_COUNT; shiftIndex++) {
            Integer planQty = ShiftFieldUtil.getShiftPlanQty(result, shiftIndex);
            if (Objects.isNull(planQty) || planQty <= 0) {
                continue;
            }
            int normalizedQty = ShiftCapacityResolverUtil.roundUpQtyToMouldMultiple(planQty, mouldQty);
            if (normalizedQty == planQty) {
                continue;
            }
            ShiftFieldUtil.setShiftPlanQty(result, shiftIndex, normalizedQty,
                    ShiftFieldUtil.getShiftStartTime(result, shiftIndex),
                    ShiftFieldUtil.getShiftEndTime(result, shiftIndex));
            adjusted = true;
            log.info("双模计划量保存前收敛, batchNo: {}, materialCode: {}, machineCode: {}, "
                            + "shiftIndex: {}, mouldQty: {}, 原计划量: {}, 收敛后: {}",
                    context.getBatchNo(), result.getMaterialCode(), result.getLhMachineCode(),
                    shiftIndex, mouldQty, planQty, normalizedQty);
        }
        if (adjusted) {
            ShiftFieldUtil.syncDailyPlanQty(result);
        }
    }

    /**
     * 校验SKU计划量口径是否满足策略约束。
     *
     * @param context 排程上下文
     */
    private void validateProductionQuantityPolicy(LhScheduleContext context) {
        if (CollectionUtils.isEmpty(context.getScheduleResultList())
                || CollectionUtils.isEmpty(context.getScheduleResultSourceSkuMap())) {
            return;
        }
        Map<SkuScheduleDTO, Integer> scheduledQtyMap = new IdentityHashMap<>();
        Map<SkuScheduleDTO, Integer> shiftCapacityMap = new IdentityHashMap<>();
        for (LhScheduleResult result : context.getScheduleResultList()) {
            SkuScheduleDTO sourceSku = context.getScheduleResultSourceSkuMap().get(result);
            if (Objects.isNull(sourceSku)) {
                continue;
            }
            SkuScheduleDTO validationSku = resolveValidationSourceSku(context, sourceSku);
            if (Objects.isNull(validationSku)) {
                continue;
            }
            int planQty = resolveResultPlanQty(result);
            if (planQty <= 0) {
                continue;
            }
            scheduledQtyMap.merge(validationSku, planQty, Integer::sum);
            shiftCapacityMap.put(validationSku, resolveValidationShiftCapacity(validationSku, result));
        }
        for (Map.Entry<SkuScheduleDTO, Integer> entry : scheduledQtyMap.entrySet()) {
            SkuScheduleDTO sku = entry.getKey();
            int scheduledQty = entry.getValue();
            int targetQty = resolveValidationTargetQty(context, sku);
            if (targetQty <= 0) {
                continue;
            }
            ProductionQuantityPolicy policy = ProductionQuantityPolicy.from(sku, sku.isStrictTargetQty());
            if (policy.isStrictUpperLimit()) {
                validateStrictUpperLimit(context, sku, scheduledQty, targetQty);
                continue;
            }
            validateFormalQuantityPolicy(context, sku, scheduledQty, targetQty, shiftCapacityMap.get(sku));
        }
    }

    /**
     * 校验试制/收尾严格目标量。
     *
     * @param context 排程上下文
     * @param sku SKU
     * @param scheduledQty 已排量
     * @param targetQty 目标量
     */
    private void validateStrictUpperLimit(LhScheduleContext context,
                                          SkuScheduleDTO sku,
                                          int scheduledQty,
                                          int targetQty) {
        if (scheduledQty <= targetQty) {
            return;
        }
        String message = String.format("严格目标量SKU超排：物料[%s] 目标量[%d] 实际排产[%d]",
                sku.getMaterialCode(), targetQty, scheduledQty);
        log.error("排程结果校验失败, {}", message);
        throw new ScheduleException(ScheduleStepEnum.S4_6_RESULT_VALIDATION,
                ScheduleErrorCode.RESULT_VALIDATION_FAILED,
                context.getFactoryCode(), context.getBatchNo(), message);
    }

    /**
     * 校验正式/量试非收尾目标量。
     *
     * @param context 排程上下文
     * @param sku SKU
     * @param scheduledQty 已排量
     * @param targetQty 目标量
     * @param shiftCapacity 班产
     */
    private void validateFormalQuantityPolicy(LhScheduleContext context,
                                              SkuScheduleDTO sku,
                                              int scheduledQty,
                                              int targetQty,
                                              Integer shiftCapacity) {
        int overQty = scheduledQty - targetQty;
        int validationShiftCapacity = shiftCapacity != null ? shiftCapacity : 0;
        int allowedOverQty = Math.max(validationShiftCapacity,
                sku != null ? Math.max(0, sku.getShiftFillOverQty()) : 0);
        if (allowedOverQty > 0 && overQty > allowedOverQty) {
            String message = String.format("正式/量试SKU超排超过最后已开班补满范围：物料[%s] 目标量[%d] 实际排产[%d] 超排[%d] 班产[%d]",
                    sku.getMaterialCode(), targetQty, scheduledQty, overQty, validationShiftCapacity);
            log.error("排程结果校验失败, {}", message);
            throw new ScheduleException(ScheduleStepEnum.S4_6_RESULT_VALIDATION,
                    ScheduleErrorCode.RESULT_VALIDATION_FAILED,
                    context.getFactoryCode(), context.getBatchNo(), message);
        }
        if (scheduledQty < targetQty && !hasUnscheduledResult(context, sku)) {
            String message = String.format("正式/量试SKU未满足窗口目标量且无未排记录：物料[%s] 目标量[%d] 实际排产[%d]",
                    sku.getMaterialCode(), targetQty, scheduledQty);
            log.error("排程结果校验失败, {}", message);
            throw new ScheduleException(ScheduleStepEnum.S4_6_RESULT_VALIDATION,
                    ScheduleErrorCode.RESULT_VALIDATION_FAILED,
                    context.getFactoryCode(), context.getBatchNo(), message);
        }
    }

    /**
     * 解析排程结果计划量。
     *
     * @param result 排程结果
     * @return 计划量
     */
    private int resolveResultPlanQty(LhScheduleResult result) {
        int planQty = ShiftFieldUtil.sumPlanQty(result, LhScheduleConstant.MAX_SHIFT_SLOT_COUNT);
        if (planQty <= 0 && Objects.nonNull(result.getDailyPlanQty())) {
            return Math.max(0, result.getDailyPlanQty());
        }
        return Math.max(0, planQty);
    }

    /**
     * 解析结果校验用班产。
     *
     * @param sku SKU
     * @param result 排程结果
     * @return 班产
     */
    private int resolveValidationShiftCapacity(SkuScheduleDTO sku, LhScheduleResult result) {
        int maxShiftPlanQty = 0;
        for (int shiftIndex = 1; shiftIndex <= LhScheduleConstant.MAX_SHIFT_SLOT_COUNT; shiftIndex++) {
            Integer shiftPlanQty = ShiftFieldUtil.getShiftPlanQty(result, shiftIndex);
            if (Objects.nonNull(shiftPlanQty) && shiftPlanQty > maxShiftPlanQty) {
                maxShiftPlanQty = shiftPlanQty;
            }
        }
        if (maxShiftPlanQty > 0) {
            return maxShiftPlanQty;
        }
        return sku.getShiftCapacity() > 0 ? sku.getShiftCapacity() : 0;
    }

    /**
     * 解析结果校验目标量。
     * <p>正式/量试非收尾优先按账本有效目标量校验，避免新增规格链路恢复原始需求量后，
     * S4.6 仍按原始目标量误判“已满足账本目标”的结果。</p>
     *
     * @param sku SKU
     * @return 校验目标量
     */
    private int resolveValidationTargetQty(LhScheduleContext context, SkuScheduleDTO sku) {
        if (Objects.isNull(sku)) {
            return 0;
        }
        int targetQty = Math.max(0, sku.resolveTargetScheduleQty());
        if (sku.isStrictTargetQty()) {
            return targetQty;
        }
        int ledgerTargetQty = resolveLedgerTargetQty(sku);
        if (shouldUseLedgerTargetQtyForContinuousMultiMachine(context, sku, ledgerTargetQty)) {
            return ledgerTargetQty;
        }
        if (ledgerTargetQty > 0) {
            return targetQty > 0 ? Math.min(targetQty, ledgerTargetQty) : ledgerTargetQty;
        }
        int windowPlanQty = Math.max(0, sku.getWindowPlanQty());
        if (windowPlanQty > 0) {
            return targetQty > 0 ? Math.min(targetQty, windowPlanQty) : windowPlanQty;
        }
        return targetQty;
    }

    /**
     * 汇总账本有效目标量。
     *
     * @param sku SKU
     * @return 账本有效目标量
     */
    private int resolveLedgerTargetQty(SkuScheduleDTO sku) {
        if (Objects.isNull(sku) || CollectionUtils.isEmpty(sku.getDailyPlanQuotaMap())) {
            return 0;
        }
        int ledgerTargetQty = 0;
        for (SkuDailyPlanQuotaDTO quota : sku.getDailyPlanQuotaMap().values()) {
            if (Objects.isNull(quota)) {
                continue;
            }
            ledgerTargetQty += Math.max(0, quota.getScheduledQty()) + Math.max(0, quota.getRemainingQty());
        }
        return Math.max(0, ledgerTargetQty);
    }

    /**
     * 判断续作同SKU多机台降模结果是否应按共享账本有效目标量校验。
     * <p>正规/量试非收尾续作在文档案例下允许保留机台按剩余班次补满班产，
     * 运行时 targetQty 可能小于共享账本覆盖的窗口总量，此时应以账本有效目标量校验。</p>
     *
     * @param context 排程上下文
     * @param sku SKU
     * @param ledgerTargetQty 账本有效目标量
     * @return true-按账本有效目标量校验
     */
    private boolean shouldUseLedgerTargetQtyForContinuousMultiMachine(LhScheduleContext context,
                                                                      SkuScheduleDTO sku,
                                                                      int ledgerTargetQty) {
        if (Objects.isNull(context) || Objects.isNull(sku) || ledgerTargetQty <= 0
                || CollectionUtils.isEmpty(context.getContinuousSkuList())
                || CollectionUtils.isEmpty(sku.getDailyPlanQuotaMap())) {
            return false;
        }
        ProductionQuantityPolicy policy = ProductionQuantityPolicy.from(sku, sku.isStrictTargetQty());
        if (policy.isStrictUpperLimit()) {
            return false;
        }
        int sameQuotaContinuousCount = 0;
        for (SkuScheduleDTO continuousSku : context.getContinuousSkuList()) {
            if (continuousSku == null) {
                continue;
            }
            if (StringUtils.equals(sku.getMaterialCode(), continuousSku.getMaterialCode())
                    && continuousSku.getDailyPlanQuotaMap() == sku.getDailyPlanQuotaMap()) {
                sameQuotaContinuousCount++;
                if (sameQuotaContinuousCount > 1) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 解析结果校验时的逻辑来源SKU。
     * <p>续作补偿SKU与来源续作SKU共享同一份日计划账本时，应按同一个逻辑目标量聚合校验。</p>
     *
     * @param context 排程上下文
     * @param sourceSku 来源SKU
     * @return 逻辑来源SKU
     */
    private SkuScheduleDTO resolveValidationSourceSku(LhScheduleContext context, SkuScheduleDTO sourceSku) {
        if (context == null || sourceSku == null || sourceSku.getDailyPlanQuotaMap() == null
                || sourceSku.getDailyPlanQuotaMap().isEmpty()) {
            return sourceSku;
        }
        SkuScheduleDTO continuousSku = findValidationSourceSku(
                context.getContinuousSkuList(), sourceSku.getMaterialCode(), sourceSku.getDailyPlanQuotaMap());
        if (continuousSku != null) {
            return continuousSku;
        }
        SkuScheduleDTO newSpecSku = findValidationSourceSku(
                context.getNewSpecSkuList(), sourceSku.getMaterialCode(), sourceSku.getDailyPlanQuotaMap());
        return newSpecSku != null ? newSpecSku : sourceSku;
    }

    /**
     * 按共享日计划账本锚点查找逻辑来源SKU。
     *
     * @param skuList SKU列表
     * @param materialCode 物料编码
     * @param quotaMap 共享日计划账本
     * @return 逻辑来源SKU
     */
    private SkuScheduleDTO findValidationSourceSku(List<SkuScheduleDTO> skuList,
                                                   String materialCode,
                                                   Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap) {
        if (CollectionUtils.isEmpty(skuList) || StringUtils.isEmpty(materialCode) || quotaMap == null) {
            return null;
        }
        for (SkuScheduleDTO sku : skuList) {
            if (sku == null) {
                continue;
            }
            if (StringUtils.equals(materialCode, sku.getMaterialCode())
                    && sku.getDailyPlanQuotaMap() == quotaMap) {
                return sku;
            }
        }
        return null;
    }

    /**
     * 判断SKU是否已有未排记录。
     *
     * @param context 排程上下文
     * @param sku SKU
     * @return true-已有未排记录；false-没有未排记录
     */
    private boolean hasUnscheduledResult(LhScheduleContext context, SkuScheduleDTO sku) {
        if (CollectionUtils.isEmpty(context.getUnscheduledResultList())) {
            return false;
        }
        for (LhUnscheduledResult unscheduledResult : context.getUnscheduledResultList()) {
            if (StringUtils.equals(sku.getMaterialCode(), unscheduledResult.getMaterialCode())
                    && Objects.nonNull(unscheduledResult.getUnscheduledQty())
                    && unscheduledResult.getUnscheduledQty() > 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * 校验同胎胚换模班次是否冲突。
     *
     * @param context 排程上下文
     */
    private void validateGreenTireChangeoverShift(LhScheduleContext context) {
        if (CollectionUtils.isEmpty(context.getScheduleResultList())) {
            return;
        }
        Date scheduleBaseDate = resolveScheduleBaseDate(context);
        if (scheduleBaseDate == null) {
            return;
        }
        Map<String, LhScheduleResult> occupiedMap = new LinkedHashMap<>();
        for (LhScheduleResult result : context.getScheduleResultList()) {
            if (!shouldCheckGreenTireChangeover(result)) {
                continue;
            }
            Date mouldChangeStartTime = resolvePlannedMouldChangeStartTime(result);
            if (mouldChangeStartTime == null) {
                continue;
            }
            int shiftIndex = LhScheduleTimeUtil.getShiftIndex(context, scheduleBaseDate, mouldChangeStartTime);
            if (shiftIndex <= 0) {
                continue;
            }
            String key = result.getEmbryoCode() + "#" + shiftIndex;
            LhScheduleResult occupiedResult = occupiedMap.get(key);
            if (Objects.isNull(occupiedResult)) {
                occupiedMap.put(key, result);
                continue;
            }
            if (isSameMaterialGreenTireChangeover(occupiedResult, result)) {
                continue;
            }
            String message = String.format("同胎胚换模班次冲突：胎胚[%s] 班次[%s] 机台[%s]与机台[%s]同时换模",
                    result.getEmbryoCode(), shiftIndex,
                    occupiedResult.getLhMachineCode(), result.getLhMachineCode());
            log.error("排程结果校验失败, {}", message);
            throw new ScheduleException(ScheduleStepEnum.S4_6_RESULT_VALIDATION,
                    ScheduleErrorCode.RESULT_VALIDATION_FAILED,
                    context.getFactoryCode(), context.getBatchNo(), message);
        }
    }

    /**
     * 判断是否需要参与同胎胚换模冲突校验。
     *
     * @param result 排程结果
     * @return true-需要校验；false-跳过
     */
    private boolean shouldCheckGreenTireChangeover(LhScheduleResult result) {
        return Objects.nonNull(result)
                && "1".equals(result.getIsChangeMould())
                && StringUtils.isNotEmpty(result.getEmbryoCode())
                && resolveResultPlanQty(result) > 0;
    }

    /**
     * 判断同胎胚同班次换模是否属于同SKU并行场景。
     *
     * @param occupiedResult 已占用结果
     * @param currentResult 当前结果
     * @return true-同SKU并行；false-不是
     */
    private boolean isSameMaterialGreenTireChangeover(LhScheduleResult occupiedResult, LhScheduleResult currentResult) {
        return Objects.nonNull(occupiedResult)
                && Objects.nonNull(currentResult)
                && StringUtils.equals(occupiedResult.getMaterialCode(), currentResult.getMaterialCode());
    }

    /**
     * 解析排程窗口基准日期。
     *
     * @param context 排程上下文
     * @return 排程窗口基准日期
     */
    private Date resolveScheduleBaseDate(LhScheduleContext context) {
        if (Objects.nonNull(context.getScheduleDate())) {
            return context.getScheduleDate();
        }
        return context.getScheduleTargetDate();
    }

    /**
     * 生成模具交替计划。
     * <p>
     * 收集排程结果中换模的机台，生成对应的模具交替计划记录。<br/>
     * 计划顺序按机台和真实换模开始时间稳定排序，滚动继承结果不重复生成换模计划。
     * </p>
     *
     * @param context 排程上下文
     */
    private void generateMouldChangePlan(LhScheduleContext context) {
        List<LhScheduleResult> changeResults = context.getScheduleResultList().stream()
                .filter(r -> "1".equals(r.getIsChangeMould())
                        // 继承结果的换模信息已在滚动衔接中处理，跳过避免重复生成
                        && !r.isRollingInherited()
                        && r.getDailyPlanQty() != null
                        && r.getDailyPlanQty() > 0)
                .sorted(Comparator.comparing(LhScheduleResult::getLhMachineCode, Comparator.nullsLast(String::compareTo))
                        .thenComparing(this::resolvePlannedMouldChangeStartTime, Comparator.nullsLast(Date::compareTo))
                        .thenComparing(LhScheduleResult::getSpecEndTime, Comparator.nullsLast(Date::compareTo)))
                .collect(Collectors.toList());
        log.info("生成模具交替计划, 换模排程结果数: {}", changeResults.size());

        List<LhMouldChangePlan> plans = context.getMouldChangePlanList();
        // 不清空列表，保留滚动衔接中已继承的换模计划，新计划从尾部追加。
        // rollingStateMap 用于在同一机台连续换模时逐条推进前规格。
        Map<String, RollingMachineState> rollingStateMap = new HashMap<>();
        int planOrder = plans.size() + 1;

        for (LhScheduleResult result : changeResults) {
            RollingMachineState state = rollingStateMap.computeIfAbsent(result.getLhMachineCode(),
                    machineCode -> buildInitialState(context, machineCode));
            LhMouldChangePlan plan = new LhMouldChangePlan();
            plan.setFactoryCode(context.getFactoryCode());
            plan.setLhResultBatchNo(context.getBatchNo());
            plan.setOrderNo(generateChangePlanOrderNo(context));
            plan.setScheduleDate(context.getScheduleTargetDate());
            // 换模计划优先对齐结果里的真实换模开始时间；没有时再回退旧口径。
            Date plannedMouldChangeStartTime = resolvePlannedMouldChangeStartTime(result);
            plan.setPlanDate(plannedMouldChangeStartTime);
            plan.setPlanOrder(planOrder++);
            plan.setClassIndex(resolvePlanShiftCode(context, plannedMouldChangeStartTime));
            plan.setLhMachineCode(result.getLhMachineCode());
            plan.setLhMachineName(result.getLhMachineName());
            plan.setLeftRightMould(LeftRightMouldUtil.resolveLeftRightMould(
                    result.getLeftRightMould(), result.getLhMachineCode()));
            // 前规格取换模前机台当前在产规格，后规格取本次换模上机规格。
            plan.setBeforeMaterialCode(state.getCurrentMaterialCode());
            plan.setBeforeMaterialDesc(state.getCurrentMaterialDesc());
            plan.setAfterMaterialCode(result.getMaterialCode());
            plan.setAfterMaterialDesc(result.getMaterialDesc());
            plan.setMouldCode(result.getMouldCode());
            plan.setIsRelease("0");
            plan.setMouldStatus("0");
            plan.setIsDelete(0);
            plan.setEndType("1".equals(result.getIsEnd()) ? "0" : "1");
            plan.setChangeTime(resolvePlanChangeTime(result, state));

            // 判断交替类型：普通换模、换活字块、干冰清洗、喷砂清洗在这里统一落数据字典值。
            plan.setChangeMouldType(determineChangeMouldType(result));
            plans.add(plan);

            updateRollingState(state, result);
        }

        planOrder = appendCleaningMouldChangePlans(context, plans, planOrder, changeResults);
        log.info("生成模具交替计划完成, 共 {} 条", plans.size());
    }

    /**
     * 对最终换模计划执行早中班配额校验，避免超限结果落库。
     *
     * @param context 排程上下文
     */
    private void validateMouldChangePlanQuota(LhScheduleContext context) {
        if (context == null || CollectionUtils.isEmpty(context.getMouldChangePlanList())) {
            return;
        }
        Map<String, List<String>> morningMachineMap = new LinkedHashMap<>();
        Map<String, List<String>> afternoonMachineMap = new LinkedHashMap<>();
        for (LhMouldChangePlan plan : context.getMouldChangePlanList()) {
            if (!shouldCountMouldChangePlan(plan) || plan.getPlanDate() == null) {
                continue;
            }
            String dateKey = LhScheduleTimeUtil.formatDate(plan.getPlanDate());
            if (LhScheduleTimeUtil.isMorningShift(context, plan.getPlanDate())) {
                morningMachineMap.computeIfAbsent(dateKey, key -> new ArrayList<>()).add(plan.getLhMachineCode());
                continue;
            }
            if (LhScheduleTimeUtil.isAfternoonShift(context, plan.getPlanDate())) {
                afternoonMachineMap.computeIfAbsent(dateKey, key -> new ArrayList<>()).add(plan.getLhMachineCode());
            }
        }
        validateMouldChangeShiftLimit(context, morningMachineMap,
                LhScheduleTimeUtil.getMorningMouldChangeLimit(context), "早班");
        validateMouldChangeShiftLimit(context, afternoonMachineMap,
                LhScheduleTimeUtil.getAfternoonMouldChangeLimit(context), "中班");
    }

    private void validateMouldChangeShiftLimit(LhScheduleContext context,
                                               Map<String, List<String>> machineMap,
                                               int limit,
                                               String shiftName) {
        for (Map.Entry<String, List<String>> entry : machineMap.entrySet()) {
            if (CollectionUtils.isEmpty(entry.getValue()) || entry.getValue().size() <= limit) {
                continue;
            }
            throw new ScheduleException(ScheduleStepEnum.S4_6_RESULT_VALIDATION,
                    ScheduleErrorCode.RESULT_VALIDATION_FAILED,
                    context.getFactoryCode(), context.getBatchNo(),
                    String.format("模具交替计划超限：日期[%s]班次[%s]数量[%d]超出上限[%d]，机台=%s",
                            entry.getKey(), shiftName, entry.getValue().size(), limit,
                            String.join(",", entry.getValue())));
        }
    }

    private boolean shouldCountMouldChangePlan(LhMouldChangePlan plan) {
        if (plan == null || !Objects.equals(plan.getIsDelete(), 0)) {
            return false;
        }
        return StringUtils.equals(MouldChangeTypeEnum.REGULAR.getCode(), plan.getChangeMouldType())
                || StringUtils.equals(MouldChangeTypeEnum.TYPE_BLOCK.getCode(), plan.getChangeMouldType());
    }

    /**
     * 基于清洗窗口追加模具清洗交替计划。
     *
     * @param context 排程上下文
     * @param plans 模具交替计划列表
     * @param planOrder 当前计划顺序
     * @return 下一个计划顺序
     */
    private int appendCleaningMouldChangePlans(LhScheduleContext context,
                                               List<LhMouldChangePlan> plans,
                                               int planOrder,
                                               List<LhScheduleResult> changeResults) {
        List<Map.Entry<MachineScheduleDTO, MachineCleaningWindowDTO>> cleaningPlanItems = collectCleaningPlanItems(context);
        for (Map.Entry<MachineScheduleDTO, MachineCleaningWindowDTO> item : cleaningPlanItems) {
            MachineScheduleDTO machine = item.getKey();
            MachineCleaningWindowDTO cleaningWindow = item.getValue();
            String machineCode = resolveCleaningMachineCode(machine, cleaningWindow);
            RollingMachineState cleaningState = resolveCleaningMaterialState(context, changeResults,
                    machineCode, cleaningWindow.getCleanStartTime());
            LhMouldChangePlan plan = new LhMouldChangePlan();
            plan.setFactoryCode(context.getFactoryCode());
            plan.setLhResultBatchNo(context.getBatchNo());
            plan.setOrderNo(generateChangePlanOrderNo(context));
            plan.setScheduleDate(context.getScheduleTargetDate());
            plan.setPlanDate(cleaningWindow.getCleanStartTime());
            plan.setPlanOrder(planOrder++);
            plan.setClassIndex(resolvePlanShiftCode(context, cleaningWindow.getCleanStartTime()));
            plan.setLhMachineCode(machineCode);
            plan.setLhMachineName(machine != null ? machine.getMachineName() : null);
            // 清洗场景：双模机台赋值 LR，单模机台按编码后缀赋值 L/R
            plan.setLeftRightMould(LeftRightMouldUtil.resolveCleaningLeftRightMould(machineCode));
            plan.setBeforeMaterialCode(cleaningState.getCurrentMaterialCode());
            plan.setBeforeMaterialDesc(cleaningState.getCurrentMaterialDesc());
            plan.setAfterMaterialCode(cleaningState.getCurrentMaterialCode());
            plan.setAfterMaterialDesc(cleaningState.getCurrentMaterialDesc());
            plan.setChangeMouldType(resolveCleaningMouldChangeType(cleaningWindow));
            plan.setChangeTime(cleaningWindow.getCleanStartTime());
            plan.setMouldCode(cleaningWindow.getMouldCode());
            plan.setIsRelease("0");
            plan.setMouldStatus("0");
            plan.setRemark(cleaningWindow.getRemark());
            plan.setIsDelete(0);
            plan.setEndType(machine.isEnding() ? "0" : "1");
            plans.add(plan);
        }
        return planOrder;
    }

    /**
     * 按清洗发生时点回放机台物料状态。
     *
     * @param context 排程上下文
     * @param changeResults 换模结果
     * @param machineCode 机台编码
     * @param cleaningStartTime 清洗开始时间
     * @return 清洗发生时的机台状态
     */
    private RollingMachineState resolveCleaningMaterialState(LhScheduleContext context,
                                                             List<LhScheduleResult> changeResults,
                                                             String machineCode,
                                                             Date cleaningStartTime) {
        RollingMachineState state = buildInitialState(context, machineCode);
        if (StringUtils.isEmpty(machineCode) || cleaningStartTime == null || CollectionUtils.isEmpty(changeResults)) {
            return state;
        }
        for (LhScheduleResult result : changeResults) {
            if (!StringUtils.equals(machineCode, result.getLhMachineCode())) {
                continue;
            }
            Date plannedMouldChangeStartTime = resolvePlannedMouldChangeStartTime(result);
            if (plannedMouldChangeStartTime == null || !plannedMouldChangeStartTime.before(cleaningStartTime)) {
                continue;
            }
            updateRollingState(state, result);
        }
        return state;
    }

    /**
     * 诊断周日手工喷砂是否满足交替计划条数阈值。
     *
     * @param context 排程上下文
     */
    private void validateManualSundaySandBlastThreshold(LhScheduleContext context) {
        if (context.getParamIntValue(LhScheduleParamConstant.SAND_BLAST_SKIP_SUNDAY_ENABLED,
                LhScheduleConstant.SAND_BLAST_SKIP_SUNDAY_ENABLED) != ENABLED
                || context.getParamIntValue(LhScheduleParamConstant.SAND_BLAST_ALLOW_SUNDAY_MANUAL_ENABLED,
                LhScheduleConstant.SAND_BLAST_ALLOW_SUNDAY_MANUAL_ENABLED) != ENABLED) {
            return;
        }
        int threshold = context.getParamIntValue(LhScheduleParamConstant.SAND_BLAST_SUNDAY_MIN_ALTERNATE_PLAN_COUNT,
                LhScheduleConstant.SAND_BLAST_SUNDAY_MIN_ALTERNATE_PLAN_COUNT);
        for (Map.Entry<MachineScheduleDTO, MachineCleaningWindowDTO> item : collectCleaningPlanItems(context)) {
            MachineCleaningWindowDTO cleaningWindow = item.getValue();
            if (cleaningWindow == null
                    || !StringUtils.equals(CleaningTypeEnum.SAND_BLAST.getCode(), cleaningWindow.getCleanType())
                    || !StringUtils.equals(CLEANING_DATA_SOURCE_MANUAL, cleaningWindow.getDataSource())
                    || cleaningWindow.getCleanStartTime() == null
                    || !isSunday(cleaningWindow.getCleanStartTime())) {
                continue;
            }
            String dateKey = LhScheduleTimeUtil.formatDate(cleaningWindow.getCleanStartTime());
            long alternatePlanCount = context.getMouldChangePlanList().stream()
                    .filter(plan -> Objects.nonNull(plan.getPlanDate())
                            && StringUtils.equals(dateKey, LhScheduleTimeUtil.formatDate(plan.getPlanDate()))
                            && !isCleaningMouldChangePlan(plan))
                    .count();
            if (alternatePlanCount >= threshold) {
                log.warn("周日手工喷砂交替计划数量达到诊断阈值, 日期: {}, 机台: {}, 阈值: {}, 实际条数: {}",
                        dateKey, resolveCleaningMachineCode(item.getKey(), cleaningWindow),
                        threshold, alternatePlanCount);
            }
        }
    }

    /**
     * 判断是否为清洗类交替计划。
     *
     * @param plan 模具交替计划
     * @return true-清洗类；false-非清洗类
     */
    private boolean isCleaningMouldChangePlan(LhMouldChangePlan plan) {
        return Objects.nonNull(plan)
                && (StringUtils.equals(MouldChangeTypeEnum.SAND_BLAST.getCode(), plan.getChangeMouldType())
                || StringUtils.equals(MouldChangeTypeEnum.DRY_ICE.getCode(), plan.getChangeMouldType()));
    }

    /**
     * 判断指定日期是否为周日。
     *
     * @param date 日期
     * @return true-周日；false-非周日
     */
    private boolean isSunday(Date date) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(date);
        return calendar.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY;
    }

    /**
     * 收集清洗计划项。
     *
     * @param context 排程上下文
     * @return 清洗计划项
     */
    private List<Map.Entry<MachineScheduleDTO, MachineCleaningWindowDTO>> collectCleaningPlanItems(LhScheduleContext context) {
        List<Map.Entry<MachineScheduleDTO, MachineCleaningWindowDTO>> itemList = new ArrayList<>();
        for (MachineScheduleDTO machine : context.getMachineScheduleMap().values()) {
            if (machine == null || CollectionUtils.isEmpty(machine.getCleaningWindowList())) {
                continue;
            }
            for (MachineCleaningWindowDTO cleaningWindow : machine.getCleaningWindowList()) {
                if (cleaningWindow == null
                        || cleaningWindow.getCleanStartTime() == null
                        || StringUtils.isEmpty(resolveCleaningMouldChangeType(cleaningWindow))) {
                    continue;
                }
                itemList.add(new java.util.AbstractMap.SimpleEntry<>(machine, cleaningWindow));
            }
        }
        itemList.sort(Comparator
                .comparing((Map.Entry<MachineScheduleDTO, MachineCleaningWindowDTO> item) -> item.getValue().getCleanStartTime(),
                        Comparator.nullsLast(Date::compareTo))
                .thenComparing(item -> resolveCleaningMachineCode(item.getKey(), item.getValue()),
                        Comparator.nullsLast(String::compareTo)));
        return itemList;
    }

    /**
     * 解析清洗计划对应机台。
     *
     * @param machine 机台
     * @param cleaningWindow 清洗窗口
     * @return 机台编码
     */
    private String resolveCleaningMachineCode(MachineScheduleDTO machine, MachineCleaningWindowDTO cleaningWindow) {
        if (cleaningWindow != null && StringUtils.isNotEmpty(cleaningWindow.getLhCode())) {
            return cleaningWindow.getLhCode();
        }
        return machine != null ? machine.getMachineCode() : null;
    }

    /**
     * 解析清洗交替类型。
     *
     * @param cleaningWindow 清洗窗口
     * @return 模具交替类型
     */
    private String resolveCleaningMouldChangeType(MachineCleaningWindowDTO cleaningWindow) {
        if (Objects.isNull(cleaningWindow)) {
            return null;
        }
        if (CleaningTypeEnum.SAND_BLAST.getCode().equals(cleaningWindow.getCleanType())) {
            return MouldChangeTypeEnum.SAND_BLAST.getCode();
        }
        if (CleaningTypeEnum.DRY_ICE.getCode().equals(cleaningWindow.getCleanType())) {
            return MouldChangeTypeEnum.DRY_ICE.getCode();
        }
        return null;
    }

    /**
     * 确定模具交替类型
     * <p>01-正规换模, 02-更换活字块, 03-模具喷砂清洗, 04-模具干冰清洗</p>
     */
    private String determineChangeMouldType(LhScheduleResult result) {
        // 换活字块：通过 isTypeBlock 精确识别
        if ("1".equals(result.getIsTypeBlock())) {
            return "02";
        }
        // 新增排产（换模）
        if ("02".equals(result.getScheduleType())) {
            return "01";
        }
        return "01";
    }

    /**
     * 为排程结果补全工单号（确保每条记录都有工单号）
     */
    private void assignOrderNumbers(LhScheduleContext context) {
        log.info("补全工单号, 排程结果数: {}", context.getScheduleResultList().size());
        String dateStr = DateUtil.format(context.getScheduleTargetDate(), "yyyyMMdd");

        for (LhScheduleResult result : context.getScheduleResultList()) {
            if (result.getOrderNo() == null || result.getOrderNo().isEmpty()) {
                int seq = ORDER_SEQ.incrementAndGet() % 1000;
                result.setOrderNo(String.format("%s%s%03d", LhScheduleConstant.ORDER_NO_PREFIX, dateStr, seq));
            }
            // 确保发布状态已设置
            if (result.getIsRelease() == null) {
                result.setIsRelease("0");
            }
        }

        // 为模具交替计划补全工单号
        for (LhMouldChangePlan plan : context.getMouldChangePlanList()) {
            if (plan.getOrderNo() == null || plan.getOrderNo().isEmpty()) {
                int seq = CHG_SEQ.incrementAndGet() % 1000;
                plan.setOrderNo(String.format("%s%s%03d", LhScheduleConstant.MOULD_CHANGE_ORDER_PREFIX, dateStr, seq));
            }
        }
    }

    /**
     * 添加排程汇总日志
     */
    private void addSummaryLog(LhScheduleContext context) {
        LhScheduleProcessLog summaryLog = new LhScheduleProcessLog();
        summaryLog.setBatchNo(context.getBatchNo());
        summaryLog.setTitle(ScheduleStepEnum.S4_6_RESULT_VALIDATION.getDescription());
        summaryLog.setBusiCode(context.getFactoryCode());
        summaryLog.setLogDetail(String.format(
                "排程完成: 排程结果%d条, 未排产%d条, 换模计划%d条",
                context.getScheduleResultList().size(),
                context.getUnscheduledResultList().size(),
                context.getMouldChangePlanList().size()
        ));
        summaryLog.setIsDelete(0);
        context.getScheduleLogList().add(summaryLog);
    }

    /**
     * 按SKU+日期汇总排产量，对比月计划dayN，输出日计划完成情况日志。
     * <p>汇总口径：遍历所有排程结果，按班次归属日期聚合各SKU的实际排产量，
     * 与月计划对应 dayN 的计划量做对比，识别超排/欠产/满班补齐超排等异常。</p>
     *
     * @param context 排程上下文
     */
    private void addDailyPlanSummaryLog(LhScheduleContext context) {
        List<LhShiftConfigVO> shifts = context.getScheduleWindowShifts();
        if (CollectionUtils.isEmpty(shifts)) {
            shifts = LhScheduleTimeUtil.getScheduleShifts(context, context.getScheduleDate());
        }
        if (CollectionUtils.isEmpty(shifts)) {
            return;
        }

        // 构建月计划物料->记录映射，用于快速查找日计划量
        Map<String, FactoryMonthPlanProductionFinalResult> monthPlanMap = new LinkedHashMap<>();
        if (!CollectionUtils.isEmpty(context.getMonthPlanList())) {
            for (FactoryMonthPlanProductionFinalResult plan : context.getMonthPlanList()) {
                if (StringUtils.isNotEmpty(plan.getMaterialCode())) {
                    monthPlanMap.put(plan.getMaterialCode(), plan);
                }
            }
        }

        // 按 materialCode + productionDate 汇总实际排产量
        Map<String, Map<LocalDate, Integer>> materialDayScheduledMap = new LinkedHashMap<>();
        for (LhScheduleResult result : context.getScheduleResultList()) {
            if (result == null || StringUtils.isEmpty(result.getMaterialCode())) {
                continue;
            }
            String materialCode = result.getMaterialCode();
            for (LhShiftConfigVO shift : shifts) {
                Integer planQty = ShiftFieldUtil.getShiftPlanQty(result, shift.getShiftIndex());
                if (planQty == null || planQty <= 0) {
                    continue;
                }
                Date workDate = shift.getWorkDate();
                if (workDate == null) {
                    continue;
                }
                LocalDate productionDate = workDate.toInstant()
                        .atZone(ZoneId.systemDefault()).toLocalDate();
                materialDayScheduledMap
                        .computeIfAbsent(materialCode, k -> new LinkedHashMap<>())
                        .merge(productionDate, planQty, Integer::sum);
            }
        }

        // 收集SKU的满班超排量信息（从上下文累加器读取，覆盖已移除和仍在待排列表的所有SKU）
        Map<String, Integer> skuShiftFillOverMap = context.getSkuShiftFillOverQtyMap();

        // 汇总并输出每个SKU每日的日计划完成情况
        int totalOverPlanCount = 0;
        int totalShortageCount = 0;
        int totalShiftFillOverQty = 0;
        for (Map.Entry<String, Map<LocalDate, Integer>> materialEntry : materialDayScheduledMap.entrySet()) {
            String materialCode = materialEntry.getKey();
            FactoryMonthPlanProductionFinalResult plan = monthPlanMap.get(materialCode);
            for (Map.Entry<LocalDate, Integer> dayEntry : materialEntry.getValue().entrySet()) {
                LocalDate productionDate = dayEntry.getKey();
                int actualQty = dayEntry.getValue();
                int dayOfMonth = productionDate.getDayOfMonth();
                int dayPlanQty = plan != null ? MonthPlanDayQtyUtil.resolveDayQty(plan, dayOfMonth) : 0;
                int diffQty = actualQty - dayPlanQty;
                if (diffQty > 0) {
                    totalOverPlanCount++;
                    log.warn("日计划超排, 物料: {}, 日期: {}, 日计划量: {}, 实际排产: {}, 超出: {}",
                            materialCode, productionDate, dayPlanQty, actualQty, diffQty);
                } else if (diffQty < 0) {
                    totalShortageCount++;
                    log.info("日计划欠产, 物料: {}, 日期: {}, 日计划量: {}, 实际排产: {}, 欠产: {}",
                            materialCode, productionDate, dayPlanQty, actualQty, -diffQty);
                }
            }
        }

        // 输出满班补齐超排汇总
        for (Map.Entry<String, Integer> entry : skuShiftFillOverMap.entrySet()) {
            totalShiftFillOverQty += entry.getValue();
            log.info("满班补齐超排汇总, 物料: {}, 超排量: {}", entry.getKey(), entry.getValue());
        }

        LhScheduleProcessLog dailyPlanLog = new LhScheduleProcessLog();
        dailyPlanLog.setBatchNo(context.getBatchNo());
        dailyPlanLog.setTitle("日计划完成校验");
        dailyPlanLog.setBusiCode(context.getFactoryCode());
        dailyPlanLog.setLogDetail(String.format(
                "日计划校验完成: 超排日期数%d, 欠产日期数%d, 满班补齐超排SKU数%d, 满班超排总量%d",
                totalOverPlanCount, totalShortageCount, skuShiftFillOverMap.size(), totalShiftFillOverQty));
        dailyPlanLog.setIsDelete(0);
        context.getScheduleLogList().add(dailyPlanLog);

        addDailyQuotaLedgerLog(context);
    }

    /**
     * 输出 SKU 日计划滚动账本明细，便于核对滚动补欠产、未来借用和最终欠产。
     *
     * @param context 排程上下文
     */
    private void addDailyQuotaLedgerLog(LhScheduleContext context) {
        List<SkuScheduleDTO> ledgerSkuList = collectDailyQuotaLedgerSkuList(context);
        if (CollectionUtils.isEmpty(ledgerSkuList)) {
            return;
        }
        StringBuilder detailBuilder = new StringBuilder(1024);
        int lineCount = 0;
        for (SkuScheduleDTO sku : ledgerSkuList) {
            if (sku == null || CollectionUtils.isEmpty(sku.getDailyPlanQuotaMap())) {
                continue;
            }
            for (Map.Entry<LocalDate, SkuDailyPlanQuotaDTO> entry : sku.getDailyPlanQuotaMap().entrySet()) {
                SkuDailyPlanQuotaDTO quota = entry.getValue();
                if (quota == null) {
                    continue;
                }
                if (detailBuilder.length() > 0) {
                    detailBuilder.append('\n');
                }
                detailBuilder.append(String.format(
                        "物料=%s, 日期=%s, dayPlanQty=%d, scheduledQty=%d, remainingQty=%d, "
                                + "carryLossQty=%d, futureBorrowQty=%d, actualQty=%d, cumulativeQty=%d, "
                                + "shiftFillOverQty=%d, finalLossQty=%d, completed=%s",
                        sku.getMaterialCode(),
                        entry.getKey(),
                        Math.max(0, quota.getDayPlanQty()),
                        Math.max(0, quota.getScheduledQty()),
                        Math.max(0, quota.getRemainingQty()),
                        Math.max(0, quota.getCarryLossQty()),
                        Math.max(0, quota.getFutureBorrowQty()),
                        Math.max(0, quota.getActualQty()),
                        Math.max(0, quota.getCumulativeQty()),
                        Math.max(0, quota.getShiftFillOverQty()),
                        Math.max(0, quota.getFinalLossQty()),
                        quota.isCompleted() ? "Y" : "N"));
                lineCount++;
            }
        }
        if (detailBuilder.length() <= 0) {
            return;
        }
        log.info("日计划滚动台账明细\n{}", detailBuilder);
        LhScheduleProcessLog ledgerLog = new LhScheduleProcessLog();
        ledgerLog.setBatchNo(context.getBatchNo());
        ledgerLog.setTitle("日计划滚动台账");
        ledgerLog.setBusiCode(context.getFactoryCode());
        ledgerLog.setLogDetail(detailBuilder.toString());
        ledgerLog.setIsDelete(0);
        context.getScheduleLogList().add(ledgerLog);
        log.info("日计划滚动台账输出完成, 明细条数: {}", lineCount);
    }

    /**
     * 汇总需要输出日计划滚动账本的 SKU，按共享账本去重。
     *
     * @param context 排程上下文
     * @return 账本归属 SKU 列表
     */
    private List<SkuScheduleDTO> collectDailyQuotaLedgerSkuList(LhScheduleContext context) {
        LinkedHashMap<String, SkuScheduleDTO> ledgerSkuMap = new LinkedHashMap<>();
        if (!CollectionUtils.isEmpty(context.getScheduleResultSourceSkuMap())) {
            for (SkuScheduleDTO sku : context.getScheduleResultSourceSkuMap().values()) {
                appendDailyQuotaLedgerSku(ledgerSkuMap, sku);
            }
        }
        if (!CollectionUtils.isEmpty(context.getContinuousSkuList())) {
            for (SkuScheduleDTO sku : context.getContinuousSkuList()) {
                appendDailyQuotaLedgerSku(ledgerSkuMap, sku);
            }
        }
        if (!CollectionUtils.isEmpty(context.getNewSpecSkuList())) {
            for (SkuScheduleDTO sku : context.getNewSpecSkuList()) {
                appendDailyQuotaLedgerSku(ledgerSkuMap, sku);
            }
        }
        return new ArrayList<>(ledgerSkuMap.values());
    }

    /**
     * 追加日计划滚动账本归属 SKU，按“物料编码 + 账本对象身份”去重，避免补偿 SKU 重复输出。
     *
     * @param ledgerSkuMap 去重后的账本归属 SKU Map
     * @param sku 候选 SKU
     */
    private void appendDailyQuotaLedgerSku(Map<String, SkuScheduleDTO> ledgerSkuMap, SkuScheduleDTO sku) {
        if (sku == null || CollectionUtils.isEmpty(sku.getDailyPlanQuotaMap())
                || StringUtils.isEmpty(sku.getMaterialCode())) {
            return;
        }
        String key = sku.getMaterialCode() + "#" + System.identityHashCode(sku.getDailyPlanQuotaMap());
        ledgerSkuMap.putIfAbsent(key, sku);
    }

    /**
     * 为排程结果赋值排程顺序。
     *
     * @param context 排程上下文
     * @param businessKey 自增序列业务键
     */
    private void assignScheduleOrder(LhScheduleContext context, String businessKey) {
        if (StringUtils.isEmpty(businessKey)) {
            log.warn("排程顺序业务键为空，跳过排程顺序赋值");
            return;
        }
        if (CollectionUtils.isEmpty(context.getScheduleResultList())) {
            return;
        }
        // 按实际排产结果列表顺序依次生成排程顺序，保证落库顺序与业务执行顺序一致。
        for (LhScheduleResult result : context.getScheduleResultList()) {
            result.setScheduleOrder(IncrSerialGenerator.generateSerial(businessKey));
        }
    }

    /**
     * 构建排程顺序自增序列业务键（工厂编码_目标日yyyyMMdd）。
     *
     * @param context 排程上下文
     * @return 业务键
     */
    private String buildScheduleOrderBusinessKey(LhScheduleContext context) {
        if (context == null || StringUtils.isEmpty(context.getFactoryCode()) || context.getScheduleTargetDate() == null) {
            return null;
        }
        return context.getFactoryCode() + "_" + LhScheduleTimeUtil.getDateStr(context.getScheduleTargetDate());
    }

    /**
     * 清理排程顺序业务计数器。
     *
     * @param businessKey 自增序列业务键
     */
    private void clearScheduleOrderCounter(String businessKey) {
        if (StringUtils.isNotEmpty(businessKey)) {
            IncrSerialGenerator.clearBusinessCounter(businessKey);
        }
    }

    /**
     * 生成模具交替计划工单号：CHG+yyyyMMdd+3位流水号
     */
    private String generateChangePlanOrderNo(LhScheduleContext context) {
        String dateStr = DateUtil.format(context.getScheduleTargetDate(), "yyyyMMdd");
        int seq = CHG_SEQ.incrementAndGet() % 1000;
        return String.format("%s%s%03d", LhScheduleConstant.MOULD_CHANGE_ORDER_PREFIX, dateStr, seq);
    }

    @Override
    protected String getStepName() {
        return ScheduleStepEnum.S4_6_RESULT_VALIDATION.getDescription();
    }

    @Override
    protected boolean shouldPropagateException() {
        return true;
    }

    private void requireField(String value, String fieldName, LhScheduleContext context, LhScheduleResult result) {
        if (StringUtils.isBlank(value)) {
            throwValidationFailure(context, result, fieldName + " 缺失");
        }
    }

    private void throwValidationFailure(LhScheduleContext context, LhScheduleResult result, String detail) {
        throw new ScheduleException(ScheduleStepEnum.S4_6_RESULT_VALIDATION,
                ScheduleErrorCode.RESULT_VALIDATION_FAILED,
                context.getFactoryCode(), context.getBatchNo(),
                String.format("排程结果校验失败，机台[%s] 物料[%s]：%s",
                        result.getLhMachineCode(), result.getMaterialCode(), detail));
    }

    private Date resolveProductionStartTime(LhScheduleResult result) {
        List<Date> startTimes = new ArrayList<>();
        if (result.getClass1StartTime() != null) {
            startTimes.add(result.getClass1StartTime());
        }
        if (result.getClass2StartTime() != null) {
            startTimes.add(result.getClass2StartTime());
        }
        if (result.getClass3StartTime() != null) {
            startTimes.add(result.getClass3StartTime());
        }
        if (result.getClass4StartTime() != null) {
            startTimes.add(result.getClass4StartTime());
        }
        if (result.getClass5StartTime() != null) {
            startTimes.add(result.getClass5StartTime());
        }
        if (result.getClass6StartTime() != null) {
            startTimes.add(result.getClass6StartTime());
        }
        if (result.getClass7StartTime() != null) {
            startTimes.add(result.getClass7StartTime());
        }
        if (result.getClass8StartTime() != null) {
            startTimes.add(result.getClass8StartTime());
        }
        if (startTimes.isEmpty()) {
            return result.getSpecEndTime();
        }
        return startTimes.stream().min(Date::compareTo).orElse(result.getSpecEndTime());
    }

    private Date resolvePlannedMouldChangeStartTime(LhScheduleResult result) {
        if (result == null) {
            return null;
        }
        if (result.getMouldChangeStartTime() != null) {
            return result.getMouldChangeStartTime();
        }
        if (result.isRollingInherited()) {
            return null;
        }
        return resolveProductionStartTime(result);
    }

    private Date resolvePlanChangeTime(LhScheduleResult result, RollingMachineState state) {
        if (result != null && result.getMouldChangeStartTime() != null) {
            return result.getMouldChangeStartTime();
        }
        return state != null ? state.getEstimatedEndTime() : null;
    }

    /**
     * 根据模具交替开始时间解析模具交替计划班别编码。
     *
     * @param context 排程上下文
     * @param plannedMouldChangeStartTime 模具交替开始时间
     * @return 班别编码，未命中班次时返回null
     */
    private String resolvePlanShiftCode(LhScheduleContext context, Date plannedMouldChangeStartTime) {
        if (context == null || plannedMouldChangeStartTime == null || context.getWindowEndDate() == null) {
            return null;
        }
        int shiftIndex = LhScheduleTimeUtil.getShiftIndex(
                context, context.getWindowEndDate(), plannedMouldChangeStartTime);
        if (shiftIndex <= 0) {
            return null;
        }
        LhShiftConfigVO shift = LhScheduleTimeUtil.getShiftByIndex(
                context, context.getWindowEndDate(), shiftIndex);
        if (shift == null) {
            return null;
        }
        ShiftEnum shiftEnum = shift.resolveShiftTypeEnum();
        return shiftEnum != null ? shiftEnum.getCode() : null;
    }

    private RollingMachineState buildInitialState(LhScheduleContext context, String machineCode) {
        MachineScheduleDTO machine = context.getInitialMachineScheduleMap().get(machineCode);
        if (machine == null) {
            machine = context.getMachineScheduleMap().get(machineCode);
        }
        RollingMachineState state = new RollingMachineState();
        if (machine != null) {
            state.setCurrentMaterialCode(machine.getCurrentMaterialCode());
            state.setCurrentMaterialDesc(machine.getCurrentMaterialDesc());
            state.setPreviousMaterialCode(machine.getPreviousMaterialCode());
            state.setPreviousMaterialDesc(machine.getPreviousMaterialDesc());
            state.setEstimatedEndTime(machine.getEstimatedEndTime());
        }
        return state;
    }

    private void updateRollingState(RollingMachineState state, LhScheduleResult result) {
        state.setPreviousMaterialCode(state.getCurrentMaterialCode());
        state.setPreviousMaterialDesc(state.getCurrentMaterialDesc());
        state.setCurrentMaterialCode(result.getMaterialCode());
        state.setCurrentMaterialDesc(result.getMaterialDesc());
        state.setEstimatedEndTime(result.getSpecEndTime());
    }

    /**
     * 换模计划滚动前规格状态。
     */
    private static class RollingMachineState {

        private String currentMaterialCode;
        private String currentMaterialDesc;
        private String previousMaterialCode;
        private String previousMaterialDesc;
        private Date estimatedEndTime;

        public String getCurrentMaterialCode() {
            return currentMaterialCode;
        }

        public void setCurrentMaterialCode(String currentMaterialCode) {
            this.currentMaterialCode = currentMaterialCode;
        }

        public String getCurrentMaterialDesc() {
            return currentMaterialDesc;
        }

        public void setCurrentMaterialDesc(String currentMaterialDesc) {
            this.currentMaterialDesc = currentMaterialDesc;
        }

        public String getPreviousMaterialCode() {
            return previousMaterialCode;
        }

        public void setPreviousMaterialCode(String previousMaterialCode) {
            this.previousMaterialCode = previousMaterialCode;
        }

        public String getPreviousMaterialDesc() {
            return previousMaterialDesc;
        }

        public void setPreviousMaterialDesc(String previousMaterialDesc) {
            this.previousMaterialDesc = previousMaterialDesc;
        }

        public Date getEstimatedEndTime() {
            return estimatedEndTime;
        }

        public void setEstimatedEndTime(Date estimatedEndTime) {
            this.estimatedEndTime = estimatedEndTime;
        }
    }

    /**
     * 硫化示方历史保护：对 1-8 班硫化示方号、硫化示方类型共 16 个字段，
     * 按班次逐班判断是否属于历史班次，属于历史班次则保留历史排程结果的值。
     * <p>核心逻辑：</p>
     * <ol>
     *   <li>检查 ENABLE_CURE_FORMULA_HISTORY_PROTECT 开关</li>
     *   <li>从 context 读取 S4.2 已加载的上一轮排程结果</li>
     *   <li>反推窗口开始日期 T = windowEndDate - 2 天</li>
     *   <li>获取当前精确时间 LocalDateTime.now()，判断当前所属班次 currentWindowShiftNo</li>
     *   <li>逐机台逐班次判断是否历史班次：班次日期 &lt; 当前日期，或等于当前日期且班次编号 &lt; 当前班次</li>
     *   <li>历史班次从历史结果复制 16 个字段，非历史班次保留本次排程值</li>
     * </ol>
     *
     * @param context 排程上下文
     */
    private void applyCureFormulaHistoryProtection(LhScheduleContext context) {
        // ===== 1. 检查开关：ENABLE_CURE_FORMULA_HISTORY_PROTECT = 1 时才启用 =====
        if (!context.getScheduleConfig().isCureFormulaHistoryProtectEnabled()) {
            return;
        }

        // ===== 2. 获取历史结果：S4.2 阶段已按 factoryCode + scheduleTargetDate 查询并放入 context =====
        List<LhScheduleResult> historyList = context.getPreviousCureFormulaResultList();
        if (CollectionUtils.isEmpty(historyList)) {
            log.info("硫化示方历史保护: 不存在历史排程结果, 全部使用本次值");
            return;
        }

        // ===== 3. 按机台编码建立历史结果 Map，用于后续快速匹配 =====
        // key = lhMachineCode，同一目标日期下每个机台最多一条记录
        Map<String, LhScheduleResult> historyMap = new HashMap<>();
        for (LhScheduleResult hr : historyList) {
            historyMap.put(hr.getLhMachineCode(), hr);
        }

        // ===== 4. 反推窗口开始日期 T = windowEndDate - 2 天 =====
        // 窗口结束日期 windowEndDate 是 T+2
        // 窗口开始日期 T = windowEndDate - 2 天
        // 1-8 班次与日期映射：1-2班 -> T，3-5班 -> T+1，6-8班 -> T+2
        Date targetDate = context.getWindowEndDate();
        LocalDate scheduleLocalDate = targetDate.toInstant()
                .atZone(ZoneId.systemDefault()).toLocalDate();
        LocalDate windowStartDate = scheduleLocalDate.minusDays(2);

        // ===== 5. 获取当前精确时间，精确到年月日时分秒 =====
        // 必须使用 LocalDateTime.now()，不能只取 LocalDate，否则无法判断当前落班次
        LocalDateTime currentDateTime = LocalDateTime.now();
        LocalDate currentDate = currentDateTime.toLocalDate();
        Date currentTimeDate = Date.from(currentDateTime.atZone(ZoneId.systemDefault()).toInstant());

        // ===== 6. 基于 T 日构建 1-8 班次列表 =====
        // 通过 LhScheduleTimeUtil.getScheduleShifts 获取班次信息，每个班次包含 workDate（业务日）
        Date windowStartDateTime = Date.from(
                windowStartDate.atStartOfDay(ZoneId.systemDefault()).toInstant());
        List<LhShiftConfigVO> shifts = LhScheduleTimeUtil.getScheduleShifts(context, windowStartDateTime);

        // ===== 7. 判断当前时间所属的窗口班次编号（1-8） =====
        // 用于后续"班次日期 = 当前日期"时比较班次大小
        // 返回值 = -1 表示当前时间不在任意班次内
        int currentWindowShiftNo = LhScheduleTimeUtil.getShiftIndex(
                context, windowStartDateTime, currentTimeDate);

        // ===== 8. 日志记录关键参数用于排查 =====
        log.info("硫化示方历史保护: scheduleDate={}, windowStartDate={}, currentDateTime={}, "
                        + "currentWindowShiftNo={}, historyResultCount={}",
                scheduleLocalDate, windowStartDate, currentDateTime,
                currentWindowShiftNo, historyList.size());

        // 记录哪些班次命中了历史保护
        List<Integer> protectedShifts = new ArrayList<>();

        // ===== 9. 逐排程结果逐班次判断是否属于历史班次 =====
        for (LhScheduleResult currentResult : context.getScheduleResultList()) {
            String machineCode = currentResult.getLhMachineCode();
            LhScheduleResult historyResult = historyMap.get(machineCode);
            // 当前机台在历史结果中不存在，跳过保护
            if (historyResult == null) {
                continue;
            }

            // 逐班次处理 1-8 班
            for (int shift = 1; shift <= 8; shift++) {
                // 获取班次配置（含 workDate 等）
                LhShiftConfigVO shiftConfig = findShiftByIndex(shifts, shift);
                if (shiftConfig == null) {
                    continue;
                }

                // 获取班次对应的实际生产日期
                Date workDate = shiftConfig.getWorkDate();
                if (workDate == null) {
                    continue;
                }

                // 将班次日期转为 LocalDate 用于比较
                LocalDate shiftDate = workDate.toInstant()
                        .atZone(ZoneId.systemDefault()).toLocalDate();

                // ===== 历史班次判断规则 =====
                // 规则 1：shiftDate < currentDate → 历史班次
                // 规则 2：shiftDate = currentDate 且 shift < currentWindowShiftNo → 历史班次
                // 规则 3：shiftDate = currentDate 且 shift >= currentWindowShiftNo → 非历史（当前班次本身用本次值）
                // 规则 4：shiftDate > currentDate → 非历史
                boolean historyShift;
                if (shiftDate.isBefore(currentDate)) {
                    historyShift = true;
                } else if (shiftDate.isEqual(currentDate)) {
                    historyShift = currentWindowShiftNo > 0 && shift < currentWindowShiftNo;
                } else {
                    historyShift = false;
                }

                // ===== 10. 属于历史班次则复制硫化示方号 + 硫化示方类型 =====
                if (historyShift) {
                    protectedShifts.add(shift);
                    copyCureFormulaFields(currentResult, historyResult, shift);
                }
                // 非历史班次保留本次排程值（不做任何修改）
            }
        }

        // ===== 11. 日志输出保护结果 =====
        log.info("硫化示方历史保护完成, 保留历史值班次: {}, 使用本次值班次: 除去保留班次的其余班次",
                protectedShifts);
    }

    /**
     * 将指定班次的硫化示方号、硫化示方类型从历史结果复制到当前结果。
     *
     * <p>历史值为空时也保留为空，因为这里的目标是“保持历史班次原样”，不是重新补示方。</p>
     *
     * @param target 当前排程结果
     * @param source 历史排程结果
     * @param shift  班次索引（1-8）
     */
    private void copyCureFormulaFields(LhScheduleResult target, LhScheduleResult source, int shift) {
        switch (shift) {
            case 1:
                target.setClass1LhNo(source.getClass1LhNo());
                target.setClass1LhType(source.getClass1LhType());
                break;
            case 2:
                target.setClass2LhNo(source.getClass2LhNo());
                target.setClass2LhType(source.getClass2LhType());
                break;
            case 3:
                target.setClass3LhNo(source.getClass3LhNo());
                target.setClass3LhType(source.getClass3LhType());
                break;
            case 4:
                target.setClass4LhNo(source.getClass4LhNo());
                target.setClass4LhType(source.getClass4LhType());
                break;
            case 5:
                target.setClass5LhNo(source.getClass5LhNo());
                target.setClass5LhType(source.getClass5LhType());
                break;
            case 6:
                target.setClass6LhNo(source.getClass6LhNo());
                target.setClass6LhType(source.getClass6LhType());
                break;
            case 7:
                target.setClass7LhNo(source.getClass7LhNo());
                target.setClass7LhType(source.getClass7LhType());
                break;
            case 8:
                target.setClass8LhNo(source.getClass8LhNo());
                target.setClass8LhType(source.getClass8LhType());
                break;
            default:
                break;
        }
    }

    /**
     * 按班次索引从班次列表中查找对应班次。
     *
     * @param shifts     班次列表
     * @param shiftIndex 班次索引（1-8）
     * @return 班次视图，未找到返回 null
     */
    private LhShiftConfigVO findShiftByIndex(List<LhShiftConfigVO> shifts, int shiftIndex) {
        for (LhShiftConfigVO shift : shifts) {
            if (shift.getShiftIndex() != null && shift.getShiftIndex() == shiftIndex) {
                return shift;
            }
        }
        return null;
    }
}
