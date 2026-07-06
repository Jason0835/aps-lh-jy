package com.zlt.aps.lh.component;

import com.zlt.aps.lh.api.constant.LhScheduleConstant;
import com.zlt.aps.lh.api.constant.LhScheduleParamConstant;
import com.zlt.aps.lh.api.domain.dto.MachineCleaningWindowDTO;
import com.zlt.aps.lh.api.domain.dto.MachineScheduleDTO;
import com.zlt.aps.lh.api.domain.dto.ShiftProductionControlDTO;
import com.zlt.aps.lh.api.domain.dto.SkuDailyPlanQuotaDTO;
import com.zlt.aps.lh.api.domain.dto.SkuScheduleDTO;
import com.zlt.aps.lh.api.domain.entity.LhScheduleResult;
import com.zlt.aps.lh.api.domain.vo.LhShiftConfigVO;
import com.zlt.aps.lh.api.enums.ShiftEnum;
import com.zlt.aps.lh.api.enums.ScheduleTypeEnum;
import com.zlt.aps.lh.api.enums.ScheduleTargetModeEnum;
import com.zlt.aps.lh.api.enums.ConstructionStageEnum;
import com.zlt.aps.lh.api.enums.SkuTagEnum;
import com.zlt.aps.lh.context.EmbryoStockConsumeLedger;
import com.zlt.aps.lh.context.LhScheduleConfig;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.engine.strategy.IMachineMatchStrategy;
import com.zlt.aps.lh.engine.strategy.IMouldChangeBalanceStrategy;
import com.zlt.aps.lh.util.LhScheduleTimeUtil;
import com.zlt.aps.lh.util.FirstInspectionQtyUtil;
import com.zlt.aps.lh.util.MachineCleaningOverlapUtil;
import com.zlt.aps.lh.util.ShiftCapacityResolverUtil;
import com.zlt.aps.lh.util.ShiftFieldUtil;
import com.zlt.aps.lh.util.ShiftProductionControlUtil;
import com.zlt.aps.lh.util.SkuDailyPlanQuotaUtil;
import com.zlt.aps.mdm.api.domain.entity.MdmSkuLhCapacity;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import javax.annotation.Resource;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 排产目标量解析器。
 *
 * <p>业务定位：</p>
 * <ul>
 *   <li>统一承载“按需求排产”和“按产能满排”的目标量口径；</li>
 *   <li>根据 SKU 待排量、日计划账本、严格目标量、窗口理论产能和机台实际可用窗口收敛目标量；</li>
 *   <li>为结构五天内收尾、单机台满排、收尾上调和多机台产能评估提供同一套容量计算入口。</li>
 * </ul>
 *
 * <p>注意：该组件只计算目标量或产能上限，不直接修改排程结果；调用方负责把目标量写回 SKU 并消费账本。</p>
 *
 * @author APS
 */
@Slf4j
@Component
public class TargetScheduleQtyResolver {

    /** 成型胎胚库存收尾标识：是 */
    private static final int EMBRYO_STOCK_ENDING_YES = 1;
    /** T日收尾剩余天数 */
    private static final int T_DAY_ENDING_DAYS = 1;
    /** 胎胚库存账本key分隔符 */
    private static final String EMBRYO_STOCK_LEDGER_KEY_SEPARATOR = "_";
    /** 未命中胎胚库存账本时的无限制标记 */
    private static final int NO_EMBRYO_STOCK_LEDGER_LIMIT = -1;

    @Resource
    private IMachineMatchStrategy machineMatchStrategy;

    @Resource
    private IMouldChangeBalanceStrategy mouldChangeBalanceStrategy;

    /**
     * 解析 SKU 的初始目标排产量。
     * <p>非满排模式（按需求排产）：目标量 = 待排量；窗口总量封顶交由日计划账本消费链路约束。</p>
     * <p>满排模式（按产能满排）：目标量 = min(待排量, 理论窗口产能上限)。</p>
     *
     * @param context 排程上下文
     * @param sku SKU
     * @return 初始目标排产量
     */
    public int resolveInitialTargetQty(LhScheduleContext context, SkuScheduleDTO sku) {
        if (Objects.isNull(sku)) {
            return 0;
        }
        int pendingQty = Math.max(0, sku.getPendingQty());
        if (pendingQty <= 0) {
            return 0;
        }
        int upperLimitQty;
        // 试制/收尾等严格目标量 SKU 按 dayN 与余量控制，不允许为了补满班次突破目标量。
        if (sku.isStrictTargetQty()) {
            int windowRemainingPlanQty = Math.max(0, sku.getWindowRemainingPlanQty());
            if (windowRemainingPlanQty > 0) {
                int surplusQty = Math.max(0, sku.getSurplusQty());
                upperLimitQty = Math.min(windowRemainingPlanQty, surplusQty);
            } else {
                upperLimitQty = pendingQty;
            }
        } else if (isFullCapacityMode(context)) {
            // 正式/量试SKU允许超出dayN补满班次，按理论窗口产能封顶。
            upperLimitQty = resolveTheoreticalWindowCapacity(context, sku);
            // 满排模式下目标量直接取窗口理论满产产能，不因 dayN 计划量较小而被钳制
            return Math.max(0, upperLimitQty);
        } else {
            // 按需求排产只保留“需求口径”，不在此阶段按窗口额度压缩目标量。
            // 欠产滚动、未来预占、窗口总量封顶统一交由日计划账本消费链路处理，
            // 避免 DTO 初始化后再次把需求量压回 dayN 额度。
            upperLimitQty = pendingQty;
        }
        return Math.max(0, Math.min(pendingQty, upperLimitQty));
    }

    /**
     * 初始化 SKU 实际排产剩余账本。
     * <p>dayN 只用于节奏判断，非收尾 SKU 的实际消费账本优先取硫化余量；严格目标量场景取业务目标量。</p>
     *
     * @param context 排程上下文
     * @param sku SKU
     * @param targetQty 当前业务目标量
     * @param reason 初始化原因
     * @return 初始化后的剩余量
     */
    public int initializeProductionRemainingQty(LhScheduleContext context,
                                                SkuScheduleDTO sku,
                                                int targetQty,
                                                String reason) {
        int initialQty = resolveInitialProductionRemainingQty(sku, targetQty);
        if (Objects.isNull(context) || Objects.isNull(sku) || StringUtils.isEmpty(sku.getMaterialCode())) {
            return initialQty;
        }
        Map<String, Integer> ledgerMap = context.getSkuProductionRemainingQtyMap();
        Integer oldQty = ledgerMap.get(sku.getMaterialCode());
        if (Objects.nonNull(oldQty)) {
            return Math.max(0, oldQty);
        }
        ledgerMap.put(sku.getMaterialCode(), initialQty);
        log.info("SKU实际消费账本初始化, materialCode: {}, reason: {}, surplusQty: {}, targetQty: {}, strictTargetQty: {}, ledgerQty: {}",
                sku.getMaterialCode(), reason, Math.max(0, sku.getSurplusQty()), Math.max(0, targetQty),
                sku.isStrictTargetQty(), initialQty);
        return initialQty;
    }

    /**
     * 解析 SKU 实际排产剩余量。
     *
     * @param context 排程上下文
     * @param sku SKU
     * @return 当前 SKU 实际排产剩余量
     */
    public int resolveProductionRemainingQty(LhScheduleContext context, SkuScheduleDTO sku) {
        if (Objects.isNull(sku)) {
            return 0;
        }
        if (Objects.isNull(context) || StringUtils.isEmpty(sku.getMaterialCode())) {
            return resolveInitialProductionRemainingQty(sku, sku.resolveTargetScheduleQty());
        }
        Map<String, Integer> ledgerMap = context.getSkuProductionRemainingQtyMap();
        Integer remainingQty = ledgerMap.get(sku.getMaterialCode());
        if (Objects.isNull(remainingQty)) {
            return initializeProductionRemainingQty(context, sku, sku.resolveTargetScheduleQty(), "懒加载初始化");
        }
        return Math.max(0, remainingQty);
    }

    /**
     * 将 SKU 实际消费账本同步到指定目标量。
     * <p>收尾目标量上调后必须覆盖实际消费账本，避免后续仍按初始化硫化余量扣减。</p>
     *
     * @param context 排程上下文
     * @param sku SKU
     * @param targetQty 目标量
     * @param reason 同步原因
     * @return 同步后的剩余量
     */
    public int syncProductionRemainingQtyToTarget(LhScheduleContext context,
                                                  SkuScheduleDTO sku,
                                                  int targetQty,
                                                  String reason) {
        int resolvedTargetQty = Math.max(0, targetQty);
        if (Objects.isNull(context) || Objects.isNull(sku) || StringUtils.isEmpty(sku.getMaterialCode())) {
            return resolvedTargetQty;
        }
        Integer oldQty = context.getSkuProductionRemainingQtyMap().put(sku.getMaterialCode(), resolvedTargetQty);
        log.info("SKU实际消费账本同步, materialCode: {}, reason: {}, 原账本剩余: {}, 同步后剩余: {}",
                sku.getMaterialCode(), reason, oldQty, resolvedTargetQty);
        return resolvedTargetQty;
    }

    /**
     * 判断当前SKU是否命中成型胎胚库存收尾。
     *
     * @param context 排程上下文
     * @param sku SKU
     * @return true-按胎胚库存严格排产；false-沿用现有目标量逻辑
     */
    public boolean isEmbryoStockEnding(LhScheduleContext context, SkuScheduleDTO sku) {
        if (Objects.isNull(context) || Objects.isNull(sku)) {
            return false;
        }
        return isEmbryoStockEndingFlagYes(context, sku.getEmbryoCode()) && isTDayEndingSku(sku);
    }

    /**
     * 判断当前排程结果是否命中成型胎胚库存收尾。
     *
     * @param context 排程上下文
     * @param result 排程结果
     * @return true-结果按胎胚库存严格数量落地；false-沿用现有模台数量口径
     */
    public boolean isEmbryoStockEnding(LhScheduleContext context, LhScheduleResult result) {
        if (Objects.isNull(context) || Objects.isNull(result)
                || StringUtils.isEmpty(result.getMaterialCode())) {
            return false;
        }
        return context.getEmbryoStockHardTargetMaterialSet().contains(result.getMaterialCode());
    }

    /**
     * 命中成型胎胚库存T日收尾时，直接按胎胚库存内部额度设置排产目标量。
     * <p>单胎胚额度等于原始胎胚库存；共用胎胚额度来自同胎胚组SKU级分摊和可行性修正。</p>
     * <p>该规则不取MAX，不做模台数/奇偶修正；最终排产仍由SKU额度和组级胎胚库存账本共同硬控。</p>
     *
     * @param context 排程上下文
     * @param sku SKU
     * @param scene 调用场景
     * @return true-已应用成型胎胚库存收尾规则；false-未命中
     */
    public boolean applyEmbryoStockEndingTargetQtyIfNecessary(LhScheduleContext context,
                                                              SkuScheduleDTO sku,
                                                              String scene) {
        if (!isEmbryoStockEnding(context, sku)) {
            return false;
        }
        int currentTargetQty = Math.max(0, sku.resolveTargetScheduleQty());
        int originalEmbryoStock = resolveOriginalEmbryoStock(context, sku);
        EmbryoStockConsumeLedger ledger = getOrCreateEmbryoStockLedger(context, sku.getEmbryoCode(), originalEmbryoStock);
        int targetQty = resolveEmbryoStockSkuQuota(context, sku, originalEmbryoStock, scene);
        int surplusQty = Math.max(0, sku.getSurplusQty());
        int windowRemainingPlanQty = Math.max(0, sku.getWindowRemainingPlanQty());
        sku.setEmbryoStock(originalEmbryoStock);
        sku.setStrictTargetQty(true);
        sku.setTargetScheduleQty(targetQty);
        syncEndingDailyQuotaToTargetQty(sku, targetQty, windowRemainingPlanQty);
        int remainingQty = syncEmbryoStockEndingProductionRemainingQty(context, sku, targetQty);
        int ledgerRemainQty = Objects.isNull(ledger) ? NO_EMBRYO_STOCK_LEDGER_LIMIT : Math.max(0, ledger.getRemainQty());
        sku.setRemainingScheduleQty(ledgerRemainQty < 0 ? remainingQty : Math.min(remainingQty, ledgerRemainQty));
        String direction = targetQty > currentTargetQty ? "上调" : targetQty < currentTargetQty ? "下调" : "保持";
        log.info("成型胎胚库存T日收尾目标量{}, scene: {}, materialCode: {}, 胎胚编码: {}, 原目标量: {}, "
                        + "原始胎胚库存: {}, SKU内部额度: {}, 当前可排剩余: {}, 组级账本剩余: {}, "
                        + "月计划余量: {}, 窗口日计划剩余: {}, rule: 胎胚库存账本硬控且不做奇偶修正",
                direction, scene, sku.getMaterialCode(), sku.getEmbryoCode(), currentTargetQty,
                originalEmbryoStock, targetQty, sku.getRemainingScheduleQty(), ledgerRemainQty,
                surplusQty, windowRemainingPlanQty);
        return true;
    }

    /**
     * 同步成型胎胚库存T日收尾的SKU实际消费账本。
     * <p>首次命中按SKU内部分摊额度初始化；若前序入口已消费过账本，则只保留已扣减后的剩余额度，避免跨入口重复放大。</p>
     *
     * @param context 排程上下文
     * @param sku SKU
     * @param quotaQty SKU内部分摊额度
     * @return 当前实际可排剩余量
     */
    private int syncEmbryoStockEndingProductionRemainingQty(LhScheduleContext context,
                                                            SkuScheduleDTO sku,
                                                            int quotaQty) {
        int targetQty = Math.max(0, quotaQty);
        if (Objects.isNull(context) || Objects.isNull(sku) || StringUtils.isEmpty(sku.getMaterialCode())) {
            return targetQty;
        }
        Map<String, Integer> remainingQtyMap = context.getSkuProductionRemainingQtyMap();
        Integer oldQty = remainingQtyMap.get(sku.getMaterialCode());
        EmbryoStockConsumeLedger ledger = resolveEmbryoStockLedgerByEmbryoCode(context, sku.getEmbryoCode());
        boolean ledgerConsumed = Objects.nonNull(ledger)
                && Objects.nonNull(ledger.getConsumedQty()) && ledger.getConsumedQty() > 0;
        int remainingQty = Objects.isNull(oldQty) || !ledgerConsumed
                ? targetQty : Math.min(Math.max(0, oldQty), targetQty);
        remainingQtyMap.put(sku.getMaterialCode(), remainingQty);
        context.getEmbryoStockHardTargetMaterialSet().add(sku.getMaterialCode());
        context.getEmbryoStockSkuQuotaMap().put(sku.getMaterialCode(), targetQty);
        log.info("成型胎胚库存T日收尾SKU实际消费账本同步, materialCode: {}, SKU内部额度: {}, 原账本剩余: {}, "
                        + "组级账本已消费: {}, 同步后剩余: {}",
                sku.getMaterialCode(), targetQty, oldQty, ledgerConsumed, remainingQty);
        return remainingQty;
    }

    /**
     * 判断胎胚是否配置为胎胚库存收尾。
     *
     * @param context 排程上下文
     * @param embryoCode 胎胚代码
     * @return true-胎胚库存收尾；false-普通胎胚
     */
    private boolean isEmbryoStockEndingFlagYes(LhScheduleContext context, String embryoCode) {
        if (Objects.isNull(context) || StringUtils.isEmpty(embryoCode)
                || CollectionUtils.isEmpty(context.getEmbryoEndingFlagMap())) {
            return false;
        }
        return EMBRYO_STOCK_ENDING_YES == context.getEmbryoEndingFlagMap().getOrDefault(embryoCode, 0);
    }

    /**
     * 判断SKU是否为T日收尾。
     *
     * @param sku SKU
     * @return true-T日收尾；false-非T日收尾
     */
    private boolean isTDayEndingSku(SkuScheduleDTO sku) {
        if (Objects.isNull(sku)) {
            return false;
        }
        return StringUtils.equals(SkuTagEnum.ENDING.getCode(), sku.getSkuTag())
                && sku.getEndingDaysRemaining() == T_DAY_ENDING_DAYS;
    }

    /**
     * 基于实际硫化余量重新计算收尾剩余天数。
     * <p>markEndingSkus阶段以窗口计划量计算endingDaysRemaining，
     * 但实际硫化余量可能远小于窗口计划量，导致T日收尾判断偏差。
     * 共用胎胚T日收尾分摊前需基于实际余量刷新。</p>
     *
     * @param sku 当前SKU
     */
    private void refreshEndingDaysRemainingBySurplus(SkuScheduleDTO sku) {
        if (Objects.isNull(sku)) {
            return;
        }
        int shiftCapacity = sku.getShiftCapacity();
        // 班产缺失时保持原值，不覆盖
        if (shiftCapacity <= 0) {
            return;
        }
        int surplusQty = Math.max(0, sku.getSurplusQty());
        int shifts = surplusQty <= 0 ? 0 : (int) Math.ceil((double) surplusQty / shiftCapacity);
        int endingDays = shifts <= 0 ? 0 : (int) Math.ceil((double) shifts / LhScheduleConstant.DEFAULT_SHIFTS_PER_DAY);
        if (endingDays < 0) {
            endingDays = 1;
        }
        sku.setEndingDaysRemaining(endingDays);
    }

    /**
     * 解析原始胎胚库存。
     * <p>优先取上下文实时库存，确保共用胎胚分摊不会污染结果落库库存口径。</p>
     *
     * @param context 排程上下文
     * @param sku SKU
     * @return 原始胎胚库存
     */
    private int resolveOriginalEmbryoStock(LhScheduleContext context, SkuScheduleDTO sku) {
        if (Objects.isNull(sku)) {
            return 0;
        }
        if (Objects.nonNull(context) && StringUtils.isNotEmpty(sku.getEmbryoCode())
                && !CollectionUtils.isEmpty(context.getEmbryoRealtimeStockMap())
                && context.getEmbryoRealtimeStockMap().containsKey(sku.getEmbryoCode())) {
            Integer stockQty = context.getEmbryoRealtimeStockMap().get(sku.getEmbryoCode());
            return Math.max(0, Objects.isNull(stockQty) ? 0 : stockQty);
        }
        return Math.max(0, sku.getEmbryoStock());
    }

    /**
     * 解析T日业务日期。
     *
     * @param context 排程上下文
     * @return T日业务日期
     */
    private LocalDate resolveScheduleLocalDate(LhScheduleContext context) {
        if (Objects.isNull(context) || Objects.isNull(context.getScheduleDate())) {
            return null;
        }
        return toLocalDate(context.getScheduleDate());
    }

    /**
     * 创建胎胚库存账本key。
     *
     * @param embryoCode 胎胚代码
     * @param scheduleDate T日业务日期
     * @return 账本key
     */
    private String buildEmbryoStockLedgerKey(String embryoCode, LocalDate scheduleDate) {
        return embryoCode + EMBRYO_STOCK_LEDGER_KEY_SEPARATOR + scheduleDate;
    }

    /**
     * 获取或创建胎胚库存消费账本。
     *
     * @param context 排程上下文
     * @param embryoCode 胎胚代码
     * @param originalStockQty 原始胎胚库存
     * @return 胎胚库存消费账本
     */
    private EmbryoStockConsumeLedger getOrCreateEmbryoStockLedger(LhScheduleContext context,
                                                                  String embryoCode,
                                                                  int originalStockQty) {
        if (Objects.isNull(context) || StringUtils.isEmpty(embryoCode)) {
            return null;
        }
        LocalDate scheduleDate = resolveScheduleLocalDate(context);
        if (Objects.isNull(scheduleDate)) {
            return null;
        }
        String ledgerKey = buildEmbryoStockLedgerKey(embryoCode, scheduleDate);
        EmbryoStockConsumeLedger oldLedger = context.getEmbryoStockConsumeLedgerMap().get(ledgerKey);
        if (Objects.nonNull(oldLedger)) {
            return oldLedger;
        }
        int targetQty = Math.max(0, originalStockQty);
        EmbryoStockConsumeLedger ledger = new EmbryoStockConsumeLedger();
        ledger.setEmbryoCode(embryoCode);
        ledger.setScheduleDate(scheduleDate);
        ledger.setOriginalStockQty(targetQty);
        ledger.setTargetQty(targetQty);
        ledger.setConsumedQty(0);
        ledger.setRemainQty(targetQty);
        context.getEmbryoStockConsumeLedgerMap().put(ledgerKey, ledger);
        log.info("胎胚库存消费账本创建, ledgerKey: {}, embryoCode: {}, scheduleDate: {}, 原始库存: {}, 目标量: {}",
                ledgerKey, embryoCode, scheduleDate, targetQty, targetQty);
        return ledger;
    }

    /**
     * 解析当前SKU在胎胚库存账本中的内部额度。
     *
     * @param context 排程上下文
     * @param sku SKU
     * @param originalEmbryoStock 原始胎胚库存
     * @param scene 调用场景
     * @return SKU内部额度
     */
    private int resolveEmbryoStockSkuQuota(LhScheduleContext context,
                                           SkuScheduleDTO sku,
                                           int originalEmbryoStock,
                                           String scene) {
        if (Objects.isNull(context) || Objects.isNull(sku) || StringUtils.isEmpty(sku.getMaterialCode())) {
            return Math.max(0, originalEmbryoStock);
        }
        Integer existingQuota = context.getEmbryoStockSkuQuotaMap().get(sku.getMaterialCode());
        if (Objects.nonNull(existingQuota)) {
            return Math.max(0, existingQuota);
        }
        ensureActiveEmbryoSkuMap(context, sku);
        List<SkuScheduleDTO> activeSkuList = collectActiveSkusByEmbryo(context, sku.getEmbryoCode());
        if (!CollectionUtils.isEmpty(activeSkuList) && activeSkuList.size() > 1
                && Objects.nonNull(resolveSameEndingDay(context, activeSkuList))) {
            allocateSharedEmbryoStockByCapacity(
                    context, sku.getEmbryoCode(), originalEmbryoStock, activeSkuList,
                    T_DAY_ENDING_DAYS, scene);
            Integer allocatedQuota = context.getEmbryoStockSkuQuotaMap().get(sku.getMaterialCode());
            return Math.max(0, Objects.isNull(allocatedQuota) ? 0 : allocatedQuota);
        }
        applyEmbryoStockSkuQuota(context, sku, originalEmbryoStock, originalEmbryoStock, "单胎胚T日收尾");
        return Math.max(0, originalEmbryoStock);
    }

    /**
     * 应用胎胚库存SKU内部额度。
     *
     * @param context 排程上下文
     * @param sku SKU
     * @param originalEmbryoStock 原始胎胚库存
     * @param quotaQty SKU内部额度
     * @param reason 应用原因
     */
    private void applyEmbryoStockSkuQuota(LhScheduleContext context,
                                          SkuScheduleDTO sku,
                                          int originalEmbryoStock,
                                          int quotaQty,
                                          String reason) {
        if (Objects.isNull(context) || Objects.isNull(sku) || StringUtils.isEmpty(sku.getMaterialCode())) {
            return;
        }
        int resolvedOriginalStock = Math.max(0, originalEmbryoStock);
        int resolvedQuotaQty = Math.max(0, quotaQty);
        getOrCreateEmbryoStockLedger(context, sku.getEmbryoCode(), resolvedOriginalStock);
        sku.setEmbryoStock(resolvedOriginalStock);
        sku.setStrictTargetQty(true);
        sku.setTargetScheduleQty(resolvedQuotaQty);
        syncEndingDailyQuotaToTargetQty(sku, resolvedQuotaQty, Math.max(0, sku.getWindowRemainingPlanQty()));
        int remainingQty = syncEmbryoStockEndingProductionRemainingQty(context, sku, resolvedQuotaQty);
        int ledgerRemainQty = resolveEmbryoStockLedgerRemainingQty(context, sku);
        sku.setRemainingScheduleQty(ledgerRemainQty < 0 ? remainingQty : Math.min(remainingQty, ledgerRemainQty));
        log.info("胎胚库存SKU内部额度应用, materialCode: {}, embryoCode: {}, 原始胎胚库存: {}, SKU额度: {}, "
                        + "SKU剩余额度: {}, 胎胚账本剩余: {}, reason: {}",
                sku.getMaterialCode(), sku.getEmbryoCode(), resolvedOriginalStock, resolvedQuotaQty,
                sku.getRemainingScheduleQty(), ledgerRemainQty, reason);
    }

    /**
     * 清理非T日同日收尾场景残留的胎胚库存SKU额度。
     *
     * @param context 排程上下文
     * @param skuList SKU列表
     */
    private void clearEmbryoStockSkuQuota(LhScheduleContext context, List<SkuScheduleDTO> skuList) {
        if (Objects.isNull(context) || CollectionUtils.isEmpty(skuList)) {
            return;
        }
        for (SkuScheduleDTO sku : skuList) {
            if (Objects.nonNull(sku) && StringUtils.isNotEmpty(sku.getMaterialCode())) {
                context.getEmbryoStockSkuQuotaMap().remove(sku.getMaterialCode());
                context.getEmbryoStockHardTargetMaterialSet().remove(sku.getMaterialCode());
            }
        }
    }

    /**
     * 解析SKU有效排产剩余额度。
     *
     * @param context 排程上下文
     * @param sku SKU
     * @return SKU账本与胎胚组级账本共同约束后的剩余额度
     */
    private int resolveEffectiveProductionRemainingQty(LhScheduleContext context, SkuScheduleDTO sku) {
        int skuRemainingQty = resolveProductionRemainingQty(context, sku);
        int ledgerRemainingQty = resolveEmbryoStockLedgerRemainingQty(context, sku);
        return ledgerRemainingQty < 0 ? skuRemainingQty : Math.min(skuRemainingQty, ledgerRemainingQty);
    }

    /**
     * 解析胎胚库存组级账本剩余量。
     *
     * @param context 排程上下文
     * @param sku SKU
     * @return 剩余量；-1表示当前SKU不受胎胚库存组级账本约束
     */
    private int resolveEmbryoStockLedgerRemainingQty(LhScheduleContext context, SkuScheduleDTO sku) {
        EmbryoStockConsumeLedger ledger = resolveEmbryoStockLedger(context, sku);
        if (Objects.isNull(ledger)) {
            return NO_EMBRYO_STOCK_LEDGER_LIMIT;
        }
        return Math.max(0, ledger.getRemainQty() == null ? 0 : ledger.getRemainQty());
    }

    /**
     * 解析SKU级胎胚库存额度上限。
     *
     * @param context 排程上下文
     * @param sku SKU
     * @return SKU额度上限；-1表示无限制
     */
    private int resolveEmbryoStockSkuQuotaLimit(LhScheduleContext context, SkuScheduleDTO sku) {
        if (Objects.isNull(context) || Objects.isNull(sku) || StringUtils.isEmpty(sku.getMaterialCode())
                || !context.getEmbryoStockHardTargetMaterialSet().contains(sku.getMaterialCode())) {
            return NO_EMBRYO_STOCK_LEDGER_LIMIT;
        }
        Integer quotaQty = context.getEmbryoStockSkuQuotaMap().get(sku.getMaterialCode());
        return Math.max(0, Objects.isNull(quotaQty) ? 0 : quotaQty);
    }

    /**
     * 解析胎胚库存组级账本。
     *
     * @param context 排程上下文
     * @param sku SKU
     * @return 胎胚库存消费账本
     */
    private EmbryoStockConsumeLedger resolveEmbryoStockLedger(LhScheduleContext context, SkuScheduleDTO sku) {
        if (Objects.isNull(context) || Objects.isNull(sku) || StringUtils.isEmpty(sku.getEmbryoCode())
                || StringUtils.isEmpty(sku.getMaterialCode())
                || !context.getEmbryoStockHardTargetMaterialSet().contains(sku.getMaterialCode())) {
            return null;
        }
        return resolveEmbryoStockLedgerByEmbryoCode(context, sku.getEmbryoCode());
    }

    /**
     * 按胎胚代码解析T日胎胚库存组级账本。
     *
     * @param context 排程上下文
     * @param embryoCode 胎胚代码
     * @return 胎胚库存消费账本
     */
    private EmbryoStockConsumeLedger resolveEmbryoStockLedgerByEmbryoCode(LhScheduleContext context,
                                                                          String embryoCode) {
        if (Objects.isNull(context) || StringUtils.isEmpty(embryoCode)) {
            return null;
        }
        LocalDate scheduleDate = resolveScheduleLocalDate(context);
        if (Objects.isNull(scheduleDate)) {
            return null;
        }
        return context.getEmbryoStockConsumeLedgerMap().get(
                buildEmbryoStockLedgerKey(embryoCode, scheduleDate));
    }

    /**
     * 扣减胎胚库存组级账本。
     *
     * @param context 排程上下文
     * @param sku SKU
     * @param deductedQty 扣减量
     * @param scene 排产场景
     * @param machineCode 机台编码
     */
    private void deductEmbryoStockLedger(LhScheduleContext context,
                                         SkuScheduleDTO sku,
                                         int deductedQty,
                                         String scene,
                                         String machineCode) {
        if (deductedQty <= 0) {
            return;
        }
        EmbryoStockConsumeLedger ledger = resolveEmbryoStockLedger(context, sku);
        if (Objects.isNull(ledger)) {
            return;
        }
        int beforeRemainQty = Math.max(0, ledger.getRemainQty() == null ? 0 : ledger.getRemainQty());
        int actualDeductedQty = ledger.consume(deductedQty);
        log.info("胎胚库存组级账本扣减, scene: {}, materialCode: {}, machineCode: {}, embryoCode: {}, "
                        + "本次计划量: {}, 扣减前剩余: {}, 实际扣减: {}, 扣减后剩余: {}, 已消费: {}",
                scene, sku.getMaterialCode(), machineCode, sku.getEmbryoCode(), deductedQty,
                beforeRemainQty, actualDeductedQty, ledger.getRemainQty(), ledger.getConsumedQty());
    }

    /**
     * 恢复胎胚库存组级账本。
     *
     * @param context 排程上下文
     * @param sku SKU
     * @param restoredQty 恢复量
     * @param reason 恢复原因
     * @param machineCode 机台编码
     */
    private void restoreEmbryoStockLedger(LhScheduleContext context,
                                          SkuScheduleDTO sku,
                                          int restoredQty,
                                          String reason,
                                          String machineCode) {
        if (restoredQty <= 0) {
            return;
        }
        EmbryoStockConsumeLedger ledger = resolveEmbryoStockLedger(context, sku);
        if (Objects.isNull(ledger)) {
            return;
        }
        int beforeRemainQty = Math.max(0, ledger.getRemainQty() == null ? 0 : ledger.getRemainQty());
        int afterRemainQty = ledger.restore(restoredQty);
        log.info("胎胚库存组级账本恢复, materialCode: {}, machineCode: {}, embryoCode: {}, reason: {}, "
                        + "恢复量: {}, 恢复前剩余: {}, 恢复后剩余: {}, 已消费: {}",
                sku.getMaterialCode(), machineCode, sku.getEmbryoCode(), reason,
                restoredQty, beforeRemainQty, afterRemainQty, ledger.getConsumedQty());
    }

    /**
     * 扣减 SKU 实际排产剩余账本。
     *
     * @param context 排程上下文
     * @param sku SKU
     * @param scheduledQty 本次实际排产量
     * @param scene 排产场景
     * @param machineCode 机台编码
     * @return 实际扣减量
     */
    public int deductProductionRemainingQty(LhScheduleContext context,
                                            SkuScheduleDTO sku,
                                            int scheduledQty,
                                            String scene,
                                            String machineCode) {
        if (scheduledQty <= 0 || Objects.isNull(sku)) {
            return 0;
        }
        if (!hasProductionLedgerBasis(context, sku)) {
            return 0;
        }
        int currentRemainingQty = resolveProductionRemainingQty(context, sku);
        int ledgerRemainingQty = resolveEmbryoStockLedgerRemainingQty(context, sku);
        int effectiveRemainingQty = ledgerRemainingQty < 0
                ? currentRemainingQty : Math.min(currentRemainingQty, ledgerRemainingQty);
        int deductedQty = Math.min(effectiveRemainingQty, scheduledQty);
        if (Objects.nonNull(context) && StringUtils.isNotEmpty(sku.getMaterialCode())) {
            context.getSkuProductionRemainingQtyMap().put(
                    sku.getMaterialCode(), Math.max(0, currentRemainingQty - deductedQty));
        }
        deductEmbryoStockLedger(context, sku, deductedQty, scene, machineCode);
        log.info("SKU实际消费账本扣减, scene: {}, materialCode: {}, machineCode: {}, 本次排产量: {}, "
                        + "SKU扣减前剩余: {}, 胎胚账本剩余: {}, 实际扣减: {}, SKU扣减后剩余: {}",
                scene, sku.getMaterialCode(), machineCode, scheduledQty,
                currentRemainingQty, ledgerRemainingQty, deductedQty, Math.max(0, currentRemainingQty - deductedQty));
        return deductedQty;
    }

    /**
     * 按 SKU 实际消费账本裁剪结果行计划量。
     * <p>dayN 只做节奏判断，最终落地数量仍不能突破同 SKU 共享的实际排产剩余量。</p>
     *
     * @param context 排程上下文
     * @param sku SKU
     * @param result 排程结果
     * @param shifts 班次列表
     * @param scene 排产场景
     * @return 裁剪后的结果计划量
     */
    public int capResultByProductionRemainingQty(LhScheduleContext context,
                                                 SkuScheduleDTO sku,
                                                 LhScheduleResult result,
                                                 List<LhShiftConfigVO> shifts,
                                                 String scene) {
        int resultQty = ShiftFieldUtil.resolveScheduledQty(result);
        if (resultQty <= 0 || Objects.isNull(sku)) {
            return 0;
        }
        if (!hasProductionLedgerBasis(context, sku)) {
            return resultQty;
        }
        int remainingQty = resolveEffectiveProductionRemainingQty(context, sku);
        int allowedOverQty = resolveEndingAllowedOverQty(context, result);
        int retainedLimitQty = remainingQty + allowedOverQty;
        if (resultQty <= retainedLimitQty) {
            return resultQty;
        }
        if (CollectionUtils.isEmpty(shifts)) {
            log.warn("SKU实际消费账本裁剪缺少班次窗口，跳过结果裁剪, scene: {}, materialCode: {}, machineCode: {}, "
                            + "结果量: {}, 账本剩余: {}, 收尾规则允许超量: {}",
                    scene, sku.getMaterialCode(), result.getLhMachineCode(), resultQty, remainingQty, allowedOverQty);
            return resultQty;
        }
        int retainedQty = Math.max(0, retainedLimitQty);
        int remainingRetainQty = retainedQty;
        int actualRetainedQty = 0;
        int mouldQty = Objects.nonNull(result.getMouldQty()) ? result.getMouldQty() : 0;
        for (LhShiftConfigVO shift : shifts) {
            if (Objects.isNull(shift) || Objects.isNull(shift.getShiftIndex())) {
                continue;
            }
            Integer planQty = ShiftFieldUtil.getShiftPlanQty(result, shift.getShiftIndex());
            if (Objects.isNull(planQty) || planQty <= 0) {
                continue;
            }
            int currentShiftQty = resolveRetainedShiftQty(
                    context, sku, Math.min(planQty, remainingRetainQty), mouldQty);
            Date shiftStartTime = ShiftFieldUtil.getShiftStartTime(result, shift.getShiftIndex());
            if (currentShiftQty <= 0) {
                ShiftFieldUtil.setShiftPlanQty(result, shift.getShiftIndex(), 0, null, null);
            } else {
                ShiftFieldUtil.setShiftPlanQty(result, shift.getShiftIndex(), currentShiftQty, shiftStartTime, null);
            }
            remainingRetainQty -= currentShiftQty;
            actualRetainedQty += currentShiftQty;
        }
        ShiftFieldUtil.syncDailyPlanQty(result);
        log.info("SKU实际消费账本裁剪结果, scene: {}, materialCode: {}, machineCode: {}, 原结果量: {}, "
                        + "有效账本剩余: {}, 收尾规则允许超量: {}, 裁剪后结果量: {}",
                scene, sku.getMaterialCode(), result.getLhMachineCode(), resultQty, remainingQty,
                allowedOverQty, actualRetainedQty);
        return actualRetainedQty;
    }

    /**
     * 解析收尾规则允许超量。
     * <p>共用胎胚错峰后延和主销/常规收尾补满都有明确业务标记，不能被 SKU 实际消费账本当作普通超排回裁。</p>
     *
     * @param context 排程上下文
     * @param result 排程结果
     * @return 允许保留的收尾规则补量
     */
    private int resolveEndingAllowedOverQty(LhScheduleContext context, LhScheduleResult result) {
        if (Objects.isNull(context) || Objects.isNull(result)) {
            return 0;
        }
        int allowedOverQty = 0;
        if (!CollectionUtils.isEmpty(context.getSharedEmbryoEndingStaggerAllowedOverQtyMap())) {
            Integer staggerQty = context.getSharedEmbryoEndingStaggerAllowedOverQtyMap().get(result);
            if (Objects.nonNull(staggerQty) && staggerQty > 0) {
                allowedOverQty += staggerQty;
            }
        }
        if (!CollectionUtils.isEmpty(context.getEndingFillAllowedOverQtyMap())) {
            Integer endingFillQty = context.getEndingFillAllowedOverQtyMap().get(result);
            if (Objects.nonNull(endingFillQty) && endingFillQty > 0) {
                allowedOverQty += endingFillQty;
            }
        }
        return allowedOverQty;
    }

    /**
     * 恢复 SKU 实际排产剩余账本。
     * <p>用于已生成结果被回滚或占位结果被抢占后，把对应排产量加回 SKU 实际消费账本。</p>
     *
     * @param context 排程上下文
     * @param sku SKU
     * @param restoredQty 恢复量
     * @param reason 恢复原因
     * @param machineCode 机台编码
     * @return 恢复后的剩余量
     */
    public int restoreProductionRemainingQty(LhScheduleContext context,
                                             SkuScheduleDTO sku,
                                             int restoredQty,
                                             String reason,
                                             String machineCode) {
        if (restoredQty <= 0 || Objects.isNull(sku)) {
            return resolveProductionRemainingQty(context, sku);
        }
        int currentRemainingQty = resolveProductionRemainingQty(context, sku);
        int restoredRemainingQty = currentRemainingQty + restoredQty;
        int quotaLimitQty = resolveEmbryoStockSkuQuotaLimit(context, sku);
        if (quotaLimitQty >= 0) {
            restoredRemainingQty = Math.min(restoredRemainingQty, quotaLimitQty);
        }
        if (Objects.nonNull(context) && StringUtils.isNotEmpty(sku.getMaterialCode())) {
            context.getSkuProductionRemainingQtyMap().put(sku.getMaterialCode(), restoredRemainingQty);
        }
        restoreEmbryoStockLedger(context, sku, restoredQty, reason, machineCode);
        log.info("SKU实际消费账本恢复, materialCode: {}, machineCode: {}, reason: {}, 恢复量: {}, "
                        + "恢复前剩余: {}, SKU额度上限: {}, 恢复后剩余: {}",
                sku.getMaterialCode(), machineCode, reason, restoredQty, currentRemainingQty,
                quotaLimitQty, restoredRemainingQty);
        return restoredRemainingQty;
    }

    private int resolveInitialProductionRemainingQty(SkuScheduleDTO sku, int targetQty) {
        if (Objects.isNull(sku)) {
            return 0;
        }
        if (shouldUseTargetQtyAsProductionLedger(sku)) {
            return Math.max(0, targetQty);
        }
        int surplusQty = Math.max(0, sku.getSurplusQty());
        return surplusQty > 0 ? surplusQty : Math.max(0, targetQty);
    }

    private boolean hasProductionLedgerBasis(LhScheduleContext context, SkuScheduleDTO sku) {
        if (Objects.isNull(sku) || StringUtils.isEmpty(sku.getMaterialCode())) {
            return false;
        }
        if (Objects.nonNull(context)
                && context.getSkuProductionRemainingQtyMap().containsKey(sku.getMaterialCode())) {
            return true;
        }
        return sku.getSurplusQty() > 0 || sku.resolveTargetScheduleQty() > 0 || sku.getPendingQty() > 0;
    }

    private boolean shouldUseTargetQtyAsProductionLedger(SkuScheduleDTO sku) {
        if (Objects.isNull(sku)) {
            return false;
        }
        return StringUtils.equals(SkuTagEnum.ENDING.getCode(), sku.getSkuTag())
                || StringUtils.equals(ConstructionStageEnum.TRIAL.getCode(), sku.getConstructionStage())
                || sku.isStrictNewSpecShortageOnly();
    }

    /**
     * 解析班次分配量。
     * <p>成型胎胚库存收尾必须严格按胎胚库存落地，不做模台数向上修正；其余场景沿用既有模台数规则。</p>
     *
     * @param context 排程上下文
     * @param sku SKU
     * @param allocationQty 原始分配量
     * @param shiftMaxQty 班次最大可排量
     * @param mouldQty 模台数
     * @return 实际班次分配量
     */
    public int resolveAllocatedShiftQty(LhScheduleContext context,
                                        SkuScheduleDTO sku,
                                        int allocationQty,
                                        int shiftMaxQty,
                                        int mouldQty) {
        if (isEmbryoStockEnding(context, sku)) {
            return Math.max(0, allocationQty);
        }
        return ShiftCapacityResolverUtil.normalizeAllocatedShiftQty(allocationQty, shiftMaxQty, mouldQty);
    }

    /**
     * 解析排程结果班次分配量。
     *
     * @param context 排程上下文
     * @param result 排程结果
     * @param allocationQty 原始分配量
     * @param shiftMaxQty 班次最大可排量
     * @param mouldQty 模台数
     * @return 实际班次分配量
     */
    public int resolveAllocatedShiftQty(LhScheduleContext context,
                                        LhScheduleResult result,
                                        int allocationQty,
                                        int shiftMaxQty,
                                        int mouldQty) {
        if (isEmbryoStockEnding(context, result)) {
            return Math.max(0, allocationQty);
        }
        return ShiftCapacityResolverUtil.normalizeAllocatedShiftQty(allocationQty, shiftMaxQty, mouldQty);
    }

    /**
     * 解析账本裁剪后的班次保留量。
     * <p>成型胎胚库存收尾必须严格保留剩余胎胚库存数量，不按模台数向下裁成偶数。</p>
     *
     * @param context 排程上下文
     * @param sku SKU
     * @param retainedQty 原始保留量
     * @param mouldQty 模台数
     * @return 实际保留量
     */
    private int resolveRetainedShiftQty(LhScheduleContext context,
                                        SkuScheduleDTO sku,
                                        int retainedQty,
                                        int mouldQty) {
        if (isEmbryoStockEnding(context, sku)) {
            return Math.max(0, retainedQty);
        }
        return normalizeRetainedShiftQty(retainedQty, mouldQty);
    }

    /**
     * 按模台数向下规整实际保留量。
     * <p>结果行被账本回裁时，双模/多模班次不能保留奇数或非模数倍计划量。</p>
     *
     * @param retainedQty 当前保留量
     * @param mouldQty 模台数
     * @return 规整后的保留量
     */
    private int normalizeRetainedShiftQty(int retainedQty, int mouldQty) {
        if (retainedQty <= 0) {
            return 0;
        }
        int resolvedMouldQty = ShiftCapacityResolverUtil.resolveMachineMouldQty(mouldQty);
        if (resolvedMouldQty <= 1) {
            return retainedQty;
        }
        return retainedQty / resolvedMouldQty * resolvedMouldQty;
    }

    /**
     * 按机台实际开产时间收敛目标排产量。
     *
     * @param context 排程上下文
     * @param sku SKU
     * @param machine 机台
     * @param productionStartTime 实际开产时间
     * @param shifts 排程窗口班次
     * @return 收敛后的目标排产量
     */
    public int refineTargetQtyByMachineCapacity(LhScheduleContext context,
                                                SkuScheduleDTO sku,
                                                MachineScheduleDTO machine,
                                                Date switchStartTime,
                                                Date productionStartTime,
                                                List<LhShiftConfigVO> shifts) {
        return refineTargetQtyByMachineCapacity(context, sku, machine, switchStartTime,
                productionStartTime, shifts, null);
    }

    /**
     * 按机台实际开产时间收敛目标排产量。
     *
     * @param context 排程上下文
     * @param sku SKU
     * @param machine 机台
     * @param switchStartTime 切换开始时间
     * @param productionStartTime 实际开产时间
     * @param shifts 排程窗口班次
     * @param scheduleType 排程类型
     * @return 收敛后的目标排产量
     */
    public int refineTargetQtyByMachineCapacity(LhScheduleContext context,
                                                SkuScheduleDTO sku,
                                                MachineScheduleDTO machine,
                                                Date switchStartTime,
                                                Date productionStartTime,
                                                List<LhShiftConfigVO> shifts,
                                                String scheduleType) {
        if (Objects.isNull(sku)) {
            return 0;
        }
        int currentTargetQty = sku.resolveTargetScheduleQty();
        // 试制/收尾SKU严格限制目标量，不允许为了凑满班次而超排。
        // 满排模式下的正式/量试SKU才会根据真实机台窗口再次收敛目标量。
        if (currentTargetQty <= 0 || !isFullCapacityMode(context) || sku.isStrictTargetQty()) {
            return Math.max(currentTargetQty, 0);
        }
        int actualCapacityQty = resolveActualWindowCapacity(
                context, sku, machine, switchStartTime, productionStartTime, shifts, scheduleType);
        if (actualCapacityQty <= 0) {
            return 0;
        }
        return Math.min(currentTargetQty, actualCapacityQty);
    }

    /**
     * 判断当前是否为按产能满排模式。
     *
     * @param context 排程上下文
     * @return true-按产能满排，false-按需求排产
     */
    public boolean isFullCapacityMode(LhScheduleContext context) {
        LhScheduleConfig scheduleConfig = context != null ? context.getScheduleConfig() : null;
        if (Objects.isNull(scheduleConfig)) {
            return LhScheduleConstant.ENABLE_FULL_CAPACITY_SCHEDULING == 1;
        }
        return scheduleConfig.getScheduleTargetMode() == ScheduleTargetModeEnum.CAPACITY_FULL;
    }

    /**
     * 解析理论窗口产能上限。
     *
     * @param context 排程上下文
     * @param sku SKU
     * @return 理论窗口产能上限
     */
    private int resolveTheoreticalWindowCapacity(LhScheduleContext context, SkuScheduleDTO sku) {
        if (Objects.isNull(sku) || sku.getShiftCapacity() <= 0) {
            return Math.max(0, sku != null ? sku.getPendingQty() : 0);
        }
        List<LhShiftConfigVO> shifts = resolveScheduleShifts(context);
        if (CollectionUtils.isEmpty(shifts)) {
            return Math.max(0, sku.getPendingQty());
        }
        int mouldQty = ShiftCapacityResolverUtil.resolveMachineMouldQty(sku.getMouldQty());
        int totalCapacity = 0;
        for (LhShiftConfigVO shift : shifts) {
            ShiftProductionControlDTO control = ShiftProductionControlUtil.resolveEffectiveControl(
                    context, shift, shift.getShiftStartDateTime());
            if (Objects.isNull(control) || !control.isCanSchedule()) {
                continue;
            }
            long availableSeconds = (control.getEffectiveEndTime().getTime() - control.getEffectiveStartTime().getTime()) / 1000L;
            int shiftMaxQty = ShiftCapacityResolverUtil.resolveShiftCapacity(
                    sku.getShiftCapacity(),
                    sku.getLhTimeSeconds(),
                    mouldQty,
                    ShiftCapacityResolverUtil.resolveShiftDurationSeconds(shift),
                    availableSeconds);
            totalCapacity += ShiftProductionControlUtil.deductCapacityByControl(control, shiftMaxQty, mouldQty);
        }
        return Math.max(0, totalCapacity);
    }

    /**
     * 解析机台在剩余窗口内的实际可排产量。
     *
     * @param context 排程上下文
     * @param sku SKU
     * @param machine 机台
     * @param productionStartTime 实际开产时间
     * @param shifts 排程窗口班次
     * @return 实际可排产量
     */
    private int resolveActualWindowCapacity(LhScheduleContext context,
                                            SkuScheduleDTO sku,
                                            MachineScheduleDTO machine,
                                            Date switchStartTime,
                                            Date productionStartTime,
                                            List<LhShiftConfigVO> shifts) {
        return resolveActualWindowCapacity(context, sku, machine, switchStartTime,
                productionStartTime, shifts, null);
    }

    /**
     * 解析机台在剩余窗口内的实际可排产量。
     *
     * @param context 排程上下文
     * @param sku SKU
     * @param machine 机台
     * @param switchStartTime 切换开始时间
     * @param productionStartTime 实际开产时间
     * @param shifts 排程窗口班次
     * @param scheduleType 排程类型
     * @return 实际可排产量
     */
    private int resolveActualWindowCapacity(LhScheduleContext context,
                                            SkuScheduleDTO sku,
                                            MachineScheduleDTO machine,
                                            Date switchStartTime,
                                            Date productionStartTime,
                                            List<LhShiftConfigVO> shifts,
                                            String scheduleType) {
        if (Objects.isNull(context)
                || Objects.isNull(sku)
                || Objects.isNull(machine)
                || Objects.isNull(productionStartTime)
                || CollectionUtils.isEmpty(shifts)) {
            return 0;
        }
        int shiftCapacity = ShiftCapacityResolverUtil.resolveRuntimeShiftCapacity(
                context, machine, sku.getShiftCapacity());
        int lhTimeSeconds = sku.getLhTimeSeconds();
        if (shiftCapacity <= 0 && lhTimeSeconds <= 0) {
            return 0;
        }
        int mouldQty = ShiftCapacityResolverUtil.resolveMachineMouldQty(machine);
        Date mouldChangeCompleteTime = resolveMouldChangeCompleteTime(context, switchStartTime, scheduleType);
        productionStartTime = FirstInspectionQtyUtil.resolveTrialProductionStartTime(
                context, sku, shifts, mouldChangeCompleteTime, productionStartTime, scheduleType);
        if (Objects.isNull(productionStartTime)) {
            return 0;
        }
        Date firstProductionStartTime = ShiftProductionControlUtil.resolveFirstSchedulableStartIgnoringCleaning(
                context,
                machine.getMachineCode(),
                productionStartTime,
                shifts,
                shiftCapacity,
                lhTimeSeconds,
                mouldQty);
        if (firstProductionStartTime == null) {
            return 0;
        }
        List<MachineCleaningWindowDTO> cleaningWindowList = resolveEffectiveCleaningWindowList(
                machine, switchStartTime, firstProductionStartTime);
        int dryIceLossQty = context.getParamIntValue(
                LhScheduleParamConstant.DRY_ICE_LOSS_QTY, LhScheduleConstant.DRY_ICE_LOSS_QTY);
        int dryIceDurationHours = context.getParamIntValue(
                LhScheduleParamConstant.DRY_ICE_DURATION_HOURS, LhScheduleConstant.DRY_ICE_DURATION_HOURS);
        String configPlusShiftType = ShiftCapacityResolverUtil.resolveOddShiftCapacityPlusShiftType(context);

        Date cursorStartTime = firstProductionStartTime;
        int totalQty = 0;
        LhShiftConfigVO firstInspectionShift = FirstInspectionQtyUtil.resolveFirstInspectionAttributionShift(
                context, sku, shifts, mouldChangeCompleteTime, scheduleType);
        int firstInspectionShiftIndex = Objects.isNull(firstInspectionShift)
                || Objects.isNull(firstInspectionShift.getShiftIndex()) ? -1 : firstInspectionShift.getShiftIndex();
        int firstInspectionQty = FirstInspectionQtyUtil.resolvePreviewFirstInspectionQty(
                context, firstInspectionShift, shiftCapacity, Math.max(sku.resolveTargetScheduleQty(), shiftCapacity),
                scheduleType, machine.getMachineCode());
        boolean started = false;
        for (LhShiftConfigVO shift : shifts) {
            if (!started) {
                if (cursorStartTime.before(shift.getShiftEndDateTime())) {
                    started = true;
                } else {
                    continue;
                }
            }
            ShiftProductionControlDTO control = ShiftProductionControlUtil.resolveEffectiveControl(context, shift, cursorStartTime);
            if (Objects.isNull(control) || !control.isCanSchedule()) {
                continue;
            }
            Date effectiveStartTime = control.getEffectiveStartTime();
            Date effectiveEndTime = control.getEffectiveEndTime();
            int shiftMaxQty = ShiftCapacityResolverUtil.resolveShiftCapacityWithDowntime(
                    context.getDevicePlanShutList(),
                    cleaningWindowList,
                    machine.getMachineCode(),
                    effectiveStartTime,
                    effectiveEndTime,
                    shiftCapacity,
                    lhTimeSeconds,
                    mouldQty,
                    ShiftCapacityResolverUtil.resolveShiftDurationSeconds(shift),
                    dryIceLossQty,
                    dryIceDurationHours,
                    shift,
                    configPlusShiftType,
                    scheduleType);
            shiftMaxQty = ShiftProductionControlUtil.deductCapacityByControl(control, shiftMaxQty, mouldQty);
            shiftMaxQty = FirstInspectionQtyUtil.resolveNormalCapacityAfterFirstInspection(
                    context, shift, shiftMaxQty, firstInspectionShiftIndex, firstInspectionQty,
                    shiftCapacity, scheduleType);
            if (shiftMaxQty <= 0) {
                continue;
            }
            totalQty += shiftMaxQty;
            cursorStartTime = effectiveEndTime;
        }
            totalQty += resolveFirstInspectionCapacityOutsideProductionWindow(
                context, shifts, firstInspectionShift, firstProductionStartTime,
                shiftCapacity, totalQty, scheduleType, machine.getMachineCode());
        return Math.max(totalQty, 0);
    }

    private Date resolveMouldChangeCompleteTime(LhScheduleContext context, Date switchStartTime, String scheduleType) {
        if (Objects.isNull(switchStartTime)) {
            return null;
        }
        if (StringUtils.equals(ScheduleTypeEnum.NEW_SPEC.getCode(), scheduleType)) {
            return LhScheduleTimeUtil.addHours(switchStartTime, LhScheduleTimeUtil.getMouldChangeTotalHours(context));
        }
        if (StringUtils.equals(ScheduleTypeEnum.TYPE_BLOCK.getCode(), scheduleType)) {
            return LhScheduleTimeUtil.addHours(switchStartTime, LhScheduleTimeUtil.getTypeBlockChangeTotalHours(context));
        }
        return null;
    }

    private int resolveFirstInspectionCapacityOutsideProductionWindow(LhScheduleContext context,
                                                                       List<LhShiftConfigVO> shifts,
                                                                       LhShiftConfigVO attributionShift,
                                                                       Date firstProductionStartTime,
                                                                       int shiftCapacity,
                                                                       int remainingQty,
                                                                       String scheduleType,
                                                                       String machineCode) {
        if (Objects.isNull(attributionShift) || Objects.isNull(firstProductionStartTime)
                || firstProductionStartTime.before(attributionShift.getShiftEndDateTime())) {
            return 0;
        }
        Map<Integer, Integer> firstInspectionCapacityMap = FirstInspectionQtyUtil.applyFirstInspectionQtyToCapacityMap(
                context, shifts, attributionShift, new LinkedHashMap<Integer, Integer>(0),
                shiftCapacity, Math.max(remainingQty, shiftCapacity),
                scheduleType, machineCode);
        Integer firstInspectionQty = firstInspectionCapacityMap.get(attributionShift.getShiftIndex());
        return Math.max(0, Objects.isNull(firstInspectionQty) ? 0 : firstInspectionQty);
    }

    /**
     * 解析用于排产估算的清洗窗口。
     *
     * @param machine 机台
     * @param switchStartTime 切换开始时间
     * @param firstProductionStartTime 首个可排产开始时间
     * @return 有效清洗窗口列表
     */
    private List<MachineCleaningWindowDTO> resolveEffectiveCleaningWindowList(MachineScheduleDTO machine,
                                                                              Date switchStartTime,
                                                                              Date firstProductionStartTime) {
        if (Objects.isNull(machine) || CollectionUtils.isEmpty(machine.getCleaningWindowList())) {
            return new ArrayList<>(0);
        }
        return new ArrayList<>(MachineCleaningOverlapUtil.excludeOverlapWindows(
                machine.getCleaningWindowList(), switchStartTime, firstProductionStartTime));
    }

    /**
     * 获取排程窗口班次。
     *
     * @param context 排程上下文
     * @return 班次列表
     */
    private List<LhShiftConfigVO> resolveScheduleShifts(LhScheduleContext context) {
        if (Objects.isNull(context)) {
            return new ArrayList<>(0);
        }
        if (!CollectionUtils.isEmpty(context.getScheduleWindowShifts())) {
            return context.getScheduleWindowShifts();
        }
        if (Objects.isNull(context.getScheduleDate())) {
            return new ArrayList<>(0);
        }
        return LhScheduleTimeUtil.buildDefaultScheduleShifts(context, context.getScheduleDate());
    }

    /**
     * 计算 SKU 在当前排程窗口内所有可用机台的合计产能。
     * <p>用于收尾判断规则2和多机台排产目标量封顶。</p>
     * <p>对每台候选机台，按机台预计可用时间起算窗口内各班次可排量并汇总。</p>
     *
     * @param context 排程上下文
     * @param sku SKU排程DTO
     * @return 多台可用机台在窗口内的合计可排产量
     */
    public int calcSkuTotalAvailableCapacityInWindow(LhScheduleContext context, SkuScheduleDTO sku) {
        if (Objects.isNull(context) || Objects.isNull(sku)) {
            return 0;
        }
        IMachineMatchStrategy matchStrategy = getMachineMatchStrategy();
        if (Objects.isNull(matchStrategy)) {
            log.warn("机台匹配策略未注入，无法计算多机台合计产能, materialCode: {}", sku.getMaterialCode());
            return 0;
        }
        List<MachineScheduleDTO> candidates = matchStrategy.matchMachines(context, sku);
        if (CollectionUtils.isEmpty(candidates)) {
            log.debug("SKU无候选机台，多机台合计产能为0, materialCode: {}", sku.getMaterialCode());
            return 0;
        }
        List<LhShiftConfigVO> shifts = resolveScheduleShifts(context);
        if (CollectionUtils.isEmpty(shifts)) {
            return 0;
        }
        int totalCapacity = 0;
        for (MachineScheduleDTO machine : candidates) {
            int machineCapacity = calculateMachineAvailableCapacityInWindow(context, sku, machine, shifts);
            totalCapacity += machineCapacity;
        }
        log.debug("SKU多机台合计产能计算完成, materialCode: {}, 候选机台数: {}, 合计产能: {}",
                sku.getMaterialCode(), candidates.size(), totalCapacity);
        return totalCapacity;
    }

    /**
     * 判断 SKU 硫化余量是否可在当前排程窗口内完成。
     * <p>该口径复用候选机台真实窗口产能，产能计算已按班次控制、换模时间和设备停机时段扣减。</p>
     *
     * @param context 排程上下文
     * @param sku SKU
     * @return true-窗口真实产能可覆盖硫化余量；false-无法覆盖或无有效余量
     */
    public boolean canFinishSurplusInActualWindow(LhScheduleContext context, SkuScheduleDTO sku) {
        if (Objects.isNull(context) || Objects.isNull(sku)) {
            return false;
        }
        int surplusQty = Math.max(0, sku.getSurplusQty());
        if (surplusQty <= 0) {
            return false;
        }
        int totalAvailableCapacity = calcSkuTotalAvailableCapacityInWindow(context, sku);
        boolean canFinish = totalAvailableCapacity >= surplusQty;
        log.info("SKU硫化余量窗口完成能力判断, materialCode: {}, surplusQty: {}, actualWindowCapacity: {}, canFinish: {}",
                sku.getMaterialCode(), surplusQty, totalAvailableCapacity, canFinish);
        return canFinish;
    }

    /**
     * 按候选机台真实窗口产能计算结构排序使用的收尾天数。
     * <p>逐日汇总所有候选机台的实际可排量，目标量首次被覆盖的当天即为最晚收尾日。</p>
     *
     * @param context 排程上下文
     * @param sku SKU
     * @return 收尾天数；0-无目标量；-1-窗口内无法收敛
     */
    public int calcSkuActualEndingDaysInWindow(LhScheduleContext context, SkuScheduleDTO sku) {
        if (Objects.isNull(context) || Objects.isNull(sku)) {
            return -1;
        }
        int targetScheduleQty = Math.max(0, sku.resolveTargetScheduleQty());
        if (targetScheduleQty <= 0) {
            return 0;
        }
        IMachineMatchStrategy matchStrategy = getMachineMatchStrategy();
        if (Objects.isNull(matchStrategy)) {
            log.warn("机台匹配策略未注入，无法计算真实收尾天数, materialCode: {}", sku.getMaterialCode());
            return -1;
        }
        List<MachineScheduleDTO> candidates = matchStrategy.matchMachines(context, sku);
        if (CollectionUtils.isEmpty(candidates)) {
            return -1;
        }
        List<LhShiftConfigVO> shifts = resolveScheduleShifts(context);
        if (CollectionUtils.isEmpty(shifts)) {
            return -1;
        }
        LinkedHashMap<LocalDate, Integer> totalCapacityByDate = initCapacityByDate(shifts);
        for (MachineScheduleDTO machine : candidates) {
            Map<LocalDate, Integer> machineCapacityByDate = calculateMachineAvailableCapacityByDateInWindow(
                    context, sku, machine, shifts);
            for (Map.Entry<LocalDate, Integer> entry : machineCapacityByDate.entrySet()) {
                totalCapacityByDate.merge(entry.getKey(), entry.getValue(), Integer::sum);
            }
        }
        int cumulativeCapacity = 0;
        int endingDays = 0;
        for (Integer capacityQty : totalCapacityByDate.values()) {
            endingDays++;
            cumulativeCapacity += Math.max(0, capacityQty == null ? 0 : capacityQty);
            if (cumulativeCapacity >= targetScheduleQty) {
                return endingDays;
            }
        }
        return -1;
    }

    /**
     * 评估结构收尾排序专用的未来 N 天有效产能。
     * <p>口径固定为“未来结构收尾判定天数内，首选机台在真实换模/续作条件下的有效产能是否覆盖硫化余量”。</p>
     *
     * @param context 排程上下文
     * @param sku SKU
     * @return 结构收尾评估快照
     */
    public StructureEndingCapacitySnapshot evaluateStructureEndingCapacity(LhScheduleContext context, SkuScheduleDTO sku) {
        int structureEndingDays = resolveStructureEndingDays(context);
        StructureEndingCapacitySnapshot emptySnapshot = StructureEndingCapacitySnapshot.empty(structureEndingDays);
        if (Objects.isNull(context) || Objects.isNull(sku)) {
            return emptySnapshot;
        }
        IMachineMatchStrategy matchStrategy = getMachineMatchStrategy();
        if (Objects.isNull(matchStrategy)) {
            log.warn("机台匹配策略未注入，无法评估结构收尾有效产能, materialCode: {}", sku.getMaterialCode());
            return emptySnapshot;
        }
        List<MachineScheduleDTO> candidates = matchStrategy.matchMachines(context, sku);
        if (CollectionUtils.isEmpty(candidates) || Objects.isNull(candidates.get(0))) {
            log.info("结构收尾有效产能评估跳过, materialCode: {}, reason: 无候选机台", sku.getMaterialCode());
            return emptySnapshot;
        }
        MachineScheduleDTO machine = candidates.get(0);
        List<LhShiftConfigVO> structureShifts = resolveStructurePriorityShifts(context, structureEndingDays);
        if (CollectionUtils.isEmpty(structureShifts)) {
            log.info("结构收尾有效产能评估跳过, materialCode: {}, reason: 无结构收尾班次窗口", sku.getMaterialCode());
            return emptySnapshot;
        }
        LinkedHashMap<Integer, Integer> theoreticalShiftCapacityMap = calculateMachineAvailableCapacityByShiftInWindow(
                context, sku, machine, structureShifts, false);
        LinkedHashMap<Integer, Integer> effectiveShiftCapacityMap = calculateMachineAvailableCapacityByShiftInWindow(
                context, sku, machine, structureShifts, true);
        int demandQty = resolveStructureEndingDemandQty(sku);
        int theoreticalShiftCount = countEffectiveShiftCount(theoreticalShiftCapacityMap);
        int effectiveShiftCount = countEffectiveShiftCount(effectiveShiftCapacityMap);
        int deductedChangeoverShiftCount = Math.max(0, theoreticalShiftCount - effectiveShiftCount);
        int effectiveCapacityQty = sumShiftCapacity(effectiveShiftCapacityMap);
        boolean hitStructureEnding = demandQty > 0 && effectiveCapacityQty >= demandQty;
        int endingDaysWithinStructureWindow = resolveStructureEndingDaysWithinWindow(
                structureShifts, effectiveShiftCapacityMap, demandQty, structureEndingDays);
        StructureEndingCapacitySnapshot snapshot = new StructureEndingCapacitySnapshot(
                sku.getMaterialCode(),
                machine.getMachineCode(),
                structureEndingDays,
                demandQty,
                Math.max(0, sku.getShiftCapacity()),
                theoreticalShiftCount,
                deductedChangeoverShiftCount,
                effectiveShiftCount,
                effectiveCapacityQty,
                endingDaysWithinStructureWindow,
                hitStructureEnding);
        log.info("结构五天内收尾评估, materialCode: {}, machineCode: {}, surplusQty: {}, shiftCapacity: {}, "
                        + "theoreticalShiftCount: {}, deductedChangeoverShiftCount: {}, effectiveShiftCount: {}, "
                        + "effectiveCapacityQty: {}, hitStructureEnding: {}",
                snapshot.getMaterialCode(), snapshot.getMachineCode(), snapshot.getDemandQty(),
                snapshot.getShiftCapacity(), snapshot.getTheoreticalShiftCount(),
                snapshot.getDeductedChangeoverShiftCount(), snapshot.getEffectiveShiftCount(),
                snapshot.getEffectiveCapacityQty(), snapshot.isHitStructureEnding());
        return snapshot;
    }

    /**
     * 计算单台机台在排程窗口内的可用产能。
     *
     * @param context 排程上下文
     * @param sku SKU
     * @param machine 机台
     * @return 机台窗口可排量
     */
    public int calcMachineAvailableCapacityInWindow(LhScheduleContext context,
                                                    SkuScheduleDTO sku,
                                                    MachineScheduleDTO machine) {
        if (Objects.isNull(context) || Objects.isNull(sku) || Objects.isNull(machine)) {
            return 0;
        }
        List<LhShiftConfigVO> shifts = resolveScheduleShifts(context);
        if (CollectionUtils.isEmpty(shifts)) {
            return 0;
        }
        return calculateMachineAvailableCapacityInWindow(context, sku, machine, shifts);
    }

    /**
     * 按指定开产时刻计算机台在剩余窗口内的实际可排产量。
     * <p>供 S4.4 单机台续作 / 换活字块场景复用，保证与结果构建阶段产能口径一致。</p>
     *
     * @param context 排程上下文
     * @param sku SKU
     * @param machine 机台
     * @param switchStartTime 切换开始时间
     * @param productionStartTime 实际开产时间
     * @param shifts 排程窗口班次
     * @return 机台在剩余窗口内的实际可排产量
     */
    public int calcMachineAvailableCapacityByStartTime(LhScheduleContext context,
                                                       SkuScheduleDTO sku,
                                                       MachineScheduleDTO machine,
                                                       Date switchStartTime,
                                                       Date productionStartTime,
                                                       List<LhShiftConfigVO> shifts) {
        return resolveActualWindowCapacity(context, sku, machine, switchStartTime, productionStartTime, shifts);
    }

    /**
     * 按指定开产时刻计算机台在剩余窗口内的实际可排产量。
     *
     * @param context 排程上下文
     * @param sku SKU
     * @param machine 机台
     * @param switchStartTime 切换开始时间
     * @param productionStartTime 实际开产时间
     * @param shifts 排程窗口班次
     * @param scheduleType 排程类型
     * @return 机台在剩余窗口内的实际可排产量
     */
    public int calcMachineAvailableCapacityByStartTime(LhScheduleContext context,
                                                       SkuScheduleDTO sku,
                                                       MachineScheduleDTO machine,
                                                       Date switchStartTime,
                                                       Date productionStartTime,
                                                       List<LhShiftConfigVO> shifts,
                                                       String scheduleType) {
        return resolveActualWindowCapacity(context, sku, machine, switchStartTime,
                productionStartTime, shifts, scheduleType);
    }

    /**
     * 计算单台机台在排程窗口内的可用产能。
     * <p>从机台预计可用时间起，逐班次累加该机台可排量。</p>
     *
     * @param context 排程上下文
     * @param sku SKU排程DTO
     * @param machine 机台
     * @param shifts 排程窗口班次
     * @return 该机台在窗口内的可排产量
     */
    private int calculateMachineAvailableCapacityInWindow(LhScheduleContext context,
                                                          SkuScheduleDTO sku,
                                                          MachineScheduleDTO machine,
                                                          List<LhShiftConfigVO> shifts) {
        return sumMachineCapacityByDate(calculateMachineAvailableCapacityByDateInWindow(
                context, sku, machine, shifts));
    }

    /**
     * 计算单台机台在窗口内按业务日拆分的可排产量。
     *
     * @param context 排程上下文
     * @param sku SKU排程DTO
     * @param machine 机台
     * @param shifts 排程窗口班次
     * @return key=业务日，value=当日可排量
     */
    private LinkedHashMap<LocalDate, Integer> calculateMachineAvailableCapacityByDateInWindow(LhScheduleContext context,
                                                                                               SkuScheduleDTO sku,
                                                                                               MachineScheduleDTO machine,
                                                                                               List<LhShiftConfigVO> shifts) {
        LinkedHashMap<LocalDate, Integer> capacityByDate = initCapacityByDate(shifts);
        LinkedHashMap<Integer, Integer> capacityByShift = calculateMachineAvailableCapacityByShiftInWindow(
                context, sku, machine, shifts, true);
        for (LhShiftConfigVO shift : shifts) {
            if (shift == null || shift.getShiftIndex() == null) {
                continue;
            }
            Integer shiftMaxQty = capacityByShift.get(shift.getShiftIndex());
            if (shiftMaxQty == null || shiftMaxQty <= 0) {
                continue;
            }
            LocalDate workDate = toLocalDate(shift.getWorkDate());
            if (workDate != null) {
                capacityByDate.merge(workDate, shiftMaxQty, Integer::sum);
            }
        }
        return capacityByDate;
    }

    /**
     * 计算单台机台在窗口内按班次拆分的可排产量。
     *
     * @param context 排程上下文
     * @param sku SKU
     * @param machine 机台
     * @param shifts 班次窗口
     * @param deductChangeover 是否扣减首次换模/换活字块时间
     * @return key=班次索引，value=班次可排量
     */
    private LinkedHashMap<Integer, Integer> calculateMachineAvailableCapacityByShiftInWindow(LhScheduleContext context,
                                                                                              SkuScheduleDTO sku,
                                                                                              MachineScheduleDTO machine,
                                                                                              List<LhShiftConfigVO> shifts,
                                                                                              boolean deductChangeover) {
        LinkedHashMap<Integer, Integer> capacityByShift = new LinkedHashMap<>(Math.max(16, shifts.size()));
        if (Objects.isNull(machine) || Objects.isNull(sku) || CollectionUtils.isEmpty(shifts)) {
            return capacityByShift;
        }
        int shiftCapacity = ShiftCapacityResolverUtil.resolveRuntimeShiftCapacity(
                context, machine, sku.getShiftCapacity());
        int lhTimeSeconds = sku.getLhTimeSeconds();
        if (shiftCapacity <= 0 && lhTimeSeconds <= 0) {
            return capacityByShift;
        }
        int mouldQty = ShiftCapacityResolverUtil.resolveMachineMouldQty(machine);
        Date machineAvailableTime = machine.getEstimatedEndTime();
        Date windowStartTime = shifts.get(0).getShiftStartDateTime();
        Date cursorStartTime = machineAvailableTime != null && machineAvailableTime.after(windowStartTime)
                ? machineAvailableTime : windowStartTime;
        if (deductChangeover && !StringUtils.equals(machine.getPreviousMaterialCode(), sku.getMaterialCode())) {
            int mouldChangeHours = LhScheduleTimeUtil.getMouldChangeTotalHours(context);
            if (mouldChangeHours > 0) {
                cursorStartTime = LhScheduleTimeUtil.addHours(cursorStartTime, mouldChangeHours);
            }
        }
        int dryIceLossQty = context.getParamIntValue(
                LhScheduleParamConstant.DRY_ICE_LOSS_QTY, LhScheduleConstant.DRY_ICE_LOSS_QTY);
        int dryIceDurationHours = context.getParamIntValue(
                LhScheduleParamConstant.DRY_ICE_DURATION_HOURS, LhScheduleConstant.DRY_ICE_DURATION_HOURS);
        boolean started = false;
        for (LhShiftConfigVO shift : shifts) {
            if (shift == null || shift.getShiftIndex() == null) {
                continue;
            }
            if (!started) {
                if (cursorStartTime.before(shift.getShiftEndDateTime())) {
                    started = true;
                } else {
                    continue;
                }
            }
            ShiftProductionControlDTO control = ShiftProductionControlUtil.resolveEffectiveControl(
                    context, shift, cursorStartTime);
            if (Objects.isNull(control) || !control.isCanSchedule()) {
                continue;
            }
            Date effectiveStartTime = control.getEffectiveStartTime();
            Date effectiveEndTime = control.getEffectiveEndTime();
            int shiftMaxQty = ShiftCapacityResolverUtil.resolveShiftCapacityWithDowntime(
                    context.getDevicePlanShutList(),
                    machine.getCleaningWindowList(),
                    machine.getMaintenanceWindowList(),
                    machine.getMachineCode(),
                    effectiveStartTime,
                    effectiveEndTime,
                    shiftCapacity,
                    lhTimeSeconds,
                    mouldQty,
                    ShiftCapacityResolverUtil.resolveShiftDurationSeconds(shift),
                    dryIceLossQty,
                    dryIceDurationHours);
            shiftMaxQty = ShiftProductionControlUtil.deductCapacityByControl(control, shiftMaxQty, mouldQty);
            if (shiftMaxQty <= 0) {
                continue;
            }
            capacityByShift.put(shift.getShiftIndex(), shiftMaxQty);
            cursorStartTime = effectiveEndTime;
        }
        return capacityByShift;
    }

    /**
     * 收尾场景下调整目标排产量。
     * <p>仅在收尾判定完成后调用，非收尾SKU不应调用此方法。</p>
     * <p>单胎胚/非共用胎胚：endingTargetQty = max(embryoStock, surplusQty)。</p>
     * <p>共用胎胚：endingTargetQty = surplusQty，不按胎胚库存抬高排产目标，
     * 避免共用胎胚库存被单一SKU过量消耗。</p>
     * <p>收尾SKU的目标量不再受窗口 dayN 总量限制，也不被窗口产能压低；
     * 产能不足时由排产结果和未排结果体现剩余缺口。</p>
     *
     * @param context 排程上下文
     * @param sku SKU排程DTO
     * @return 调整后的目标排产量
     */
    public int upsizeEndingTargetQty(LhScheduleContext context, SkuScheduleDTO sku) {
        if (Objects.isNull(sku)) {
            return 0;
        }
        if (applyEmbryoStockEndingTargetQtyIfNecessary(context, sku, "收尾目标量")) {
            return sku.resolveTargetScheduleQty();
        }
        // 收尾场景下严格限制目标量，禁止补满班次超排
        sku.setStrictTargetQty(true);

        ensureActiveEmbryoSkuMap(context, sku);
        int currentTargetQty = Math.max(0, sku.resolveTargetScheduleQty());
        int embryoStock = Math.max(0, sku.getEmbryoStock());
        int surplusQty = Math.max(0, sku.getSurplusQty());
        int windowPlanQty = Math.max(0, sku.getWindowPlanQty());

        // 共用胎胚收尾只按硫化余量排，不按胎胚库存排；单胎胚仍按 MAX(余量, 胎胚库存)
        int originalSkuCount = countOriginalSkusSharingEmbryo(context, sku);
        int activeSkuCount = countActiveSkusSharingEmbryo(context, sku);
        boolean sharedEmbryo = activeSkuCount > 1;
        int endingBaseQty;
        String qtySource;
        String unscheduledReason = "";
        if (sharedEmbryo) {
            endingBaseQty = surplusQty;
            qtySource = "共用胎胚-仅按硫化余量";
            if (surplusQty <= 0) {
                unscheduledReason = "共用胎胚且硫化余量为0";
            }
        } else {
            endingBaseQty = Math.max(embryoStock, surplusQty);
            qtySource = embryoStock > surplusQty ? "单胎胚-取胎胚库存" : "单胎胚-取硫化余量";
        }
        int endingTargetQty = ShiftCapacityResolverUtil.roundUpQtyToMouldMultiple(endingBaseQty, sku.getMouldQty());
        String direction = endingTargetQty > currentTargetQty ? "上调"
                : endingTargetQty < currentTargetQty ? "下调" : "保持";
        int windowRemainingPlanQty = Math.max(0, sku.getWindowRemainingPlanQty());
        log.info("收尾SKU目标量{}, materialCode: {}, 胎胚编码: {}, 原始共用SKU数: {}, "
                        + "有效共用SKU数: {}, 是否动态共用胎胚: {}, 目标量取值来源: {}, "
                        + "原目标量: {}, 基础目标量: {}, 模台数: {}, 调整后: {}, 窗口日计划总量: {}, 窗口日计划剩余: {}, "
                        + "胎胚库存: {}, 月计划余量: {}, 未排原因: {}",
                direction, sku.getMaterialCode(), sku.getEmbryoCode(), originalSkuCount,
                activeSkuCount, sharedEmbryo, qtySource,
                currentTargetQty, endingBaseQty, sku.getMouldQty(), endingTargetQty,
                windowPlanQty, windowRemainingPlanQty, embryoStock, surplusQty, unscheduledReason);
        sku.setTargetScheduleQty(endingTargetQty);
        sku.setRemainingScheduleQty(endingTargetQty);
        syncEndingDailyQuotaToTargetQty(sku, endingTargetQty, windowRemainingPlanQty);
        syncProductionRemainingQtyToTarget(context, sku, endingTargetQty, "收尾目标量同步");
        if (endingTargetQty <= 0) {
            removeActiveEmbryoSku(context, sku, unscheduledReason);
        }
        return endingTargetQty;
    }

    /**
     * 将收尾目标量同步到运行态日计划账本。
     * <p>收尾 SKU 的业务目标不受窗口 dayN 限制；若只上调 targetScheduleQty，
     * 后续新增排产或换活字块按账本消费时仍会被原 dayN 剩余额度回裁。</p>
     *
     * @param sku 当前收尾 SKU
     * @param endingTargetQty 收尾目标量
     * @param originalWindowRemainingQty 原窗口剩余额度
     */
    private void syncEndingDailyQuotaToTargetQty(SkuScheduleDTO sku,
                                                 int endingTargetQty,
                                                 int originalWindowRemainingQty) {
        if (Objects.isNull(sku) || endingTargetQty <= 0) {
            return;
        }
        sku.setWindowPlanQty(Math.max(Math.max(0, sku.getWindowPlanQty()), endingTargetQty));
        sku.setWindowRemainingPlanQty(Math.max(Math.max(0, sku.getWindowRemainingPlanQty()), endingTargetQty));
        Map<LocalDate, SkuDailyPlanQuotaDTO> quotaMap = sku.getDailyPlanQuotaMap();
        if (CollectionUtils.isEmpty(quotaMap)) {
            return;
        }
        int currentRemainingQty = SkuDailyPlanQuotaUtil.sumRemainingQty(quotaMap);
        int appendQty = endingTargetQty - currentRemainingQty;
        if (appendQty <= 0) {
            return;
        }
        Map.Entry<LocalDate, SkuDailyPlanQuotaDTO> firstEntry = null;
        for (Map.Entry<LocalDate, SkuDailyPlanQuotaDTO> entry : quotaMap.entrySet()) {
            if (Objects.nonNull(entry) && Objects.nonNull(entry.getValue())) {
                firstEntry = entry;
                break;
            }
        }
        if (Objects.isNull(firstEntry)) {
            return;
        }
        SkuDailyPlanQuotaDTO quota = firstEntry.getValue();
        quota.setProductionDate(firstEntry.getKey());
        // 收尾清量允许突破原 dayN，把差额补入首日运行态账本，后续统一由账本消费链路扣减。
        quota.setDayPlanQty(Math.max(0, quota.getDayPlanQty()) + appendQty);
        quota.setRemainingQty(Math.max(0, quota.getRemainingQty()) + appendQty);
        quota.setCompleted(false);
        SkuDailyPlanQuotaUtil.refreshRollingFields(quotaMap);
        log.info("收尾SKU日计划账本同步, materialCode: {}, targetQty: {}, 原窗口剩余: {}, "
                        + "原账本剩余: {}, 补齐量: {}, 同步日期: {}",
                sku.getMaterialCode(), endingTargetQty, originalWindowRemainingQty,
                currentRemainingQty, appendQty, firstEntry.getKey());
    }

    /**
     * 动态判断当前SKU的胎胚在排程窗口内是否仍被多个未完成SKU共用。
     * <p>已收尾完成的SKU不参与共用胎胚判断，只统计当前仍在排程列表中的活跃SKU。</p>
     *
     * @param context 排程上下文
     * @param sku 当前SKU
     * @return true-共用胎胚（活跃SKU数 > 1）；false-单胎胚
     */
    public boolean isSharedEmbryoInWindow(LhScheduleContext context, SkuScheduleDTO sku) {
        return countActiveSkusSharingEmbryo(context, sku) > 1;
    }

    /**
     * 判断当前收尾SKU是否命中共用胎胚零余量未排规则。
     *
     * @param context 排程上下文
     * @param sku 当前SKU
     * @return true-共用胎胚收尾且硫化余量小于等于0；false-不命中
     */
    public boolean isSharedEmbryoZeroSurplusEnding(LhScheduleContext context, SkuScheduleDTO sku) {
        if (Objects.isNull(sku) || sku.getSurplusQty() > 0) {
            return false;
        }
        ensureActiveEmbryoSkuMap(context, sku);
        return isSharedEmbryoInWindow(context, sku);
    }

    /**
     * 刷新胎胚有效待排SKU集合。
     * <p>动态共用胎胚必须以本次排程仍有效参与排产的SKU为准，不再只依赖静态SKU-胎胚关系。</p>
     *
     * @param context 排程上下文
     */
    public void refreshActiveEmbryoSkuMap(LhScheduleContext context) {
        refreshActiveEmbryoSkuMap(context, null);
    }

    private void refreshActiveEmbryoSkuMap(LhScheduleContext context, SkuScheduleDTO currentSku) {
        if (Objects.isNull(context)) {
            return;
        }
        Map<String, List<String>> activeEmbryoSkuMap = new LinkedHashMap<>(8);
        List<SkuScheduleDTO> candidateSkuList = collectCandidateSkus(context);
        Set<String> unscheduledMaterialSet = collectUnscheduledMaterialSet(context);
        Set<String> completedEndingMaterialSet = collectCompletedEndingMaterialSet(context);
        for (SkuScheduleDTO candidateSku : candidateSkuList) {
            if (!isActiveEmbryoSku(candidateSku, currentSku, unscheduledMaterialSet, completedEndingMaterialSet)) {
                continue;
            }
            List<String> activeSkuList = activeEmbryoSkuMap.computeIfAbsent(
                    candidateSku.getEmbryoCode(), key -> new ArrayList<String>(4));
            if (!activeSkuList.contains(candidateSku.getMaterialCode())) {
                activeSkuList.add(candidateSku.getMaterialCode());
            }
        }
        context.setActiveEmbryoSkuMap(activeEmbryoSkuMap);
    }

    /**
     * 确保胎胚有效SKU集合可用。
     * <p>运行过程中 activeEmbryoSkuMap 会按未排、目标量满足等动作增量维护；
     * 因此收尾目标量计算前只在集合为空时重建，避免把当前仍需按初始有效集合判断的共用关系提前刷掉。</p>
     *
     * @param context 排程上下文
     * @param currentSku 当前SKU
     */
    private void ensureActiveEmbryoSkuMap(LhScheduleContext context, SkuScheduleDTO currentSku) {
        if (Objects.isNull(context) || !CollectionUtils.isEmpty(context.getActiveEmbryoSkuMap())) {
            return;
        }
        refreshActiveEmbryoSkuMap(context, currentSku);
    }

    /**
     * 统计当前排程窗口内与指定SKU共用同一胎胚的未完成SKU数量。
     * <p>从续作列表和新增列表中收集同胎胚编码的SKU，已收尾完成的SKU不会被计入。</p>
     *
     * @param context 排程上下文
     * @param sku 当前SKU
     * @return 共用该胎胚的未完成SKU数（含自身）
     */
    private int countActiveSkusSharingEmbryo(LhScheduleContext context, SkuScheduleDTO sku) {
        if (Objects.isNull(context) || Objects.isNull(sku) || StringUtils.isEmpty(sku.getEmbryoCode())) {
            return 0;
        }
        if (CollectionUtils.isEmpty(context.getActiveEmbryoSkuMap())) {
            refreshActiveEmbryoSkuMap(context);
        }
        List<String> activeSkuList = context.getActiveEmbryoSkuMap().get(sku.getEmbryoCode());
        return CollectionUtils.isEmpty(activeSkuList) ? 0 : activeSkuList.size();
    }

    /**
     * 统计同胎胚原始SKU数量。
     * <p>用于日志呈现静态关系下的共用数量，动态判断仍以 activeEmbryoSkuMap 为准。</p>
     *
     * @param context 排程上下文
     * @param sku 当前SKU
     * @return 原始同胎胚SKU数
     */
    private int countOriginalSkusSharingEmbryo(LhScheduleContext context, SkuScheduleDTO sku) {
        if (Objects.isNull(context) || Objects.isNull(sku) || StringUtils.isEmpty(sku.getEmbryoCode())) {
            return 0;
        }
        Set<String> materialSet = new HashSet<>(8);
        for (SkuScheduleDTO candidateSku : collectCandidateSkus(context)) {
            if (candidateSku != null && StringUtils.equals(sku.getEmbryoCode(), candidateSku.getEmbryoCode())
                    && StringUtils.isNotEmpty(candidateSku.getMaterialCode())) {
                materialSet.add(candidateSku.getMaterialCode());
            }
        }
        return materialSet.size();
    }

    /**
     * 从胎胚有效集合中移除当前SKU。
     * <p>收尾目标量为0、进入未排或已满足目标量后，该SKU不再参与后续SKU的共用胎胚判断。</p>
     *
     * @param context 排程上下文
     * @param sku 当前SKU
     * @param reason 移除原因
     */
    public void removeActiveEmbryoSku(LhScheduleContext context, SkuScheduleDTO sku, String reason) {
        if (Objects.isNull(context) || Objects.isNull(sku)
                || StringUtils.isEmpty(sku.getEmbryoCode()) || StringUtils.isEmpty(sku.getMaterialCode())) {
            return;
        }
        if (CollectionUtils.isEmpty(context.getActiveEmbryoSkuMap())) {
            refreshActiveEmbryoSkuMap(context);
        }
        List<String> activeSkuList = context.getActiveEmbryoSkuMap().get(sku.getEmbryoCode());
        if (CollectionUtils.isEmpty(activeSkuList)) {
            return;
        }
        // SKU进入未排或已完成后，不再占用胎胚库存内部分摊额度，剩余额度立即回流给同胎胚有效SKU。
        context.getEmbryoStockSkuQuotaMap().remove(sku.getMaterialCode());
        context.getEmbryoStockHardTargetMaterialSet().remove(sku.getMaterialCode());
        activeSkuList.remove(sku.getMaterialCode());
        if (activeSkuList.isEmpty()) {
            context.getActiveEmbryoSkuMap().remove(sku.getEmbryoCode());
        }
        log.info("刷新胎胚有效SKU集合, materialCode: {}, embryoCode: {}, 剩余有效SKU数: {}, 原因: {}",
                sku.getMaterialCode(), sku.getEmbryoCode(),
                CollectionUtils.isEmpty(activeSkuList) ? 0 : activeSkuList.size(), reason);
        refreshSharedEmbryoStockAllocation(context, sku.getEmbryoCode(), reason);
    }

    /**
     * 刷新全部活跃共用胎胚库存内部分摊。
     * <p>只在不同SKU共用同一胎胚且T日同日收尾时写入SKU内部额度；
     * 其它场景只恢复SKU原始胎胚库存展示口径。</p>
     *
     * @param context 排程上下文
     * @param reason 刷新原因
     */
    public void refreshAllSharedEmbryoStockAllocations(LhScheduleContext context, String reason) {
        if (Objects.isNull(context)) {
            return;
        }
        if (CollectionUtils.isEmpty(context.getActiveEmbryoSkuMap())) {
            refreshActiveEmbryoSkuMap(context);
        }
        if (CollectionUtils.isEmpty(context.getActiveEmbryoSkuMap())) {
            return;
        }
        Set<String> embryoCodeSet = new HashSet<String>(context.getActiveEmbryoSkuMap().keySet());
        for (String embryoCode : embryoCodeSet) {
            refreshSharedEmbryoStockAllocation(context, embryoCode, reason);
        }
    }

    /**
     * 按当前活跃SKU集合刷新单个胎胚库存内部分摊。
     *
     * @param context 排程上下文
     * @param embryoCode 胎胚编码
     * @param reason 刷新原因
     */
    private void refreshSharedEmbryoStockAllocation(LhScheduleContext context, String embryoCode, String reason) {
        if (Objects.isNull(context) || StringUtils.isEmpty(embryoCode)
                || !context.getEmbryoRealtimeStockMap().containsKey(embryoCode)) {
            return;
        }
        Integer rawEmbryoStock = context.getEmbryoRealtimeStockMap().get(embryoCode);
        if (Objects.isNull(rawEmbryoStock)) {
            return;
        }
        List<SkuScheduleDTO> activeSkuList = collectActiveSkusByEmbryo(context, embryoCode);
        if (CollectionUtils.isEmpty(activeSkuList)) {
            return;
        }
        // 基于实际硫化余量刷新T日收尾天数，确保分摊判断基于真实余量而非窗口计划量
        for (SkuScheduleDTO activeSku : activeSkuList) {
            refreshEndingDaysRemainingBySurplus(activeSku);
        }
        if (activeSkuList.size() == 1) {
            SkuScheduleDTO sku = activeSkuList.get(0);
            sku.setEmbryoStock(rawEmbryoStock);
            if (isEmbryoStockEnding(context, sku)) {
                applyEmbryoStockSkuQuota(context, sku, rawEmbryoStock, rawEmbryoStock, "动态单胎胚");
            }
            log.info("共用胎胚动态转单胎胚库存口径, embryoCode: {}, embryoDesc: {}, 剩余SKU: {}, "
                            + "胎胚库存: {}, 原因: {}",
                    embryoCode, sku.getMainMaterialDesc(), sku.getMaterialCode(), rawEmbryoStock, reason);
            return;
        }
        Integer endingDay = resolveSameEndingDay(context, activeSkuList);
        if (Objects.isNull(endingDay)) {
            resetFullEmbryoStock(activeSkuList, rawEmbryoStock);
            clearEmbryoStockSkuQuota(context, activeSkuList);
            log.info("共用胎胚库存不分摊, embryoCode: {}, embryoDesc: {}, 当前SKU列表: {}, "
                            + "是否满足不同SKU T 日同日收尾: false, 胎胚库存: {}, 原因: {}",
                    embryoCode, resolveEmbryoDesc(activeSkuList), collectMaterialCodes(activeSkuList),
                    rawEmbryoStock, reason);
            return;
        }
        allocateSharedEmbryoStockByCapacity(context, embryoCode, rawEmbryoStock, activeSkuList, endingDay, reason);
    }

    private List<SkuScheduleDTO> collectActiveSkusByEmbryo(LhScheduleContext context, String embryoCode) {
        List<String> activeMaterialList = context.getActiveEmbryoSkuMap().get(embryoCode);
        if (CollectionUtils.isEmpty(activeMaterialList)) {
            return new ArrayList<SkuScheduleDTO>(0);
        }
        Set<String> activeMaterialSet = new HashSet<String>(activeMaterialList);
        List<SkuScheduleDTO> activeSkuList = new ArrayList<SkuScheduleDTO>(activeMaterialList.size());
        for (SkuScheduleDTO sku : collectCandidateSkus(context)) {
            if (Objects.nonNull(sku) && activeMaterialSet.contains(sku.getMaterialCode())
                    && StringUtils.equals(embryoCode, sku.getEmbryoCode())) {
                activeSkuList.add(sku);
            }
        }
        return activeSkuList;
    }

    private Integer resolveSameEndingDay(LhScheduleContext context, List<SkuScheduleDTO> skuList) {
        Integer endingDay = null;
        for (SkuScheduleDTO sku : skuList) {
            if (!isEmbryoStockEnding(context, sku)) {
                return null;
            }
            if (Objects.isNull(endingDay)) {
                endingDay = sku.getEndingDaysRemaining();
                continue;
            }
            if (!endingDay.equals(sku.getEndingDaysRemaining())) {
                return null;
            }
        }
        return endingDay;
    }

    private void allocateSharedEmbryoStockByCapacity(LhScheduleContext context,
                                                     String embryoCode,
                                                     int rawEmbryoStock,
                                                     List<SkuScheduleDTO> skuList,
                                                     int endingDay,
                                                     String reason) {
        if (rawEmbryoStock <= 0) {
            for (SkuScheduleDTO sku : skuList) {
                sku.setEmbryoStock(0);
                applyEmbryoStockSkuQuota(context, sku, 0, 0, "共用胎胚库存为0");
            }
            log.info("共用胎胚库存为0，分摊结果全部为0, embryoCode: {}, 当前SKU列表: {}, 原因: {}",
                    embryoCode, collectMaterialCodes(skuList), reason);
            return;
        }
        Map<String, Integer> weightMap = buildAllocationWeightMap(context, skuList);
        int totalWeight = sumAllocationWeight(weightMap);
        if (totalWeight <= 0) {
            resetFullEmbryoStock(skuList, rawEmbryoStock);
            clearEmbryoStockSkuQuota(context, skuList);
            log.warn("共用胎胚库存分摊权重异常，按完整库存口径保留, embryoCode: {}, 当前SKU列表: {}, "
                            + "weightMap: {}, 胎胚库存: {}, 原因: {}",
                    embryoCode, collectMaterialCodes(skuList), weightMap, rawEmbryoStock, reason);
            return;
        }
        int allocatedSum = 0;
        Map<String, Integer> allocatedMap = new LinkedHashMap<String, Integer>(skuList.size());
        for (int index = 0; index < skuList.size(); index++) {
            SkuScheduleDTO sku = skuList.get(index);
            int allocatedStock;
            if (index == skuList.size() - 1) {
                allocatedStock = rawEmbryoStock - allocatedSum;
            } else {
                int weight = Math.max(0, weightMap.getOrDefault(sku.getMaterialCode(), 0));
                allocatedStock = (int) (rawEmbryoStock * (long) weight / totalWeight);
                allocatedSum += allocatedStock;
            }
            sku.setEmbryoStock(rawEmbryoStock);
            allocatedMap.put(sku.getMaterialCode(), Math.max(0, allocatedStock));
        }
        Map<String, Integer> adjustedMap = adjustSharedEmbryoQuotaByFeasibility(
                context, embryoCode, rawEmbryoStock, skuList, allocatedMap, reason);
        for (SkuScheduleDTO sku : skuList) {
            int quotaQty = Math.max(0, adjustedMap.getOrDefault(sku.getMaterialCode(), 0));
            applyEmbryoStockSkuQuota(context, sku, rawEmbryoStock, quotaQty, "共用胎胚T日同日收尾");
        }
        log.info("共用胎胚库存按标准产能分摊完成, embryoCode: {}, embryoDesc: {}, 当前SKU列表: {}, "
                        + "是否满足不同SKU同一天收尾: true, 收尾天数: {}, 标准产能权重: {}, 总权重: {}, "
                        + "胎胚库存: {}, 初始分摊结果: {}, 可行性修正结果: {}, 修正后汇总: {}, 原因: {}",
                embryoCode, resolveEmbryoDesc(skuList), collectMaterialCodes(skuList), endingDay,
                weightMap, totalWeight, rawEmbryoStock, allocatedMap, adjustedMap,
                sumAllocationWeight(adjustedMap), reason);
    }

    /**
     * 按换模可行性修正共用胎胚SKU内部额度。
     * <p>该方法只读评估，不生成排程结果；不可合理消化的额度会转给同组更可行的SKU。</p>
     *
     * @param context 排程上下文
     * @param embryoCode 胎胚代码
     * @param rawEmbryoStock 原始胎胚库存
     * @param skuList 同胎胚T日收尾SKU列表
     * @param initialQuotaMap 初始分摊额度
     * @param reason 调用原因
     * @return 修正后的SKU额度
     */
    private Map<String, Integer> adjustSharedEmbryoQuotaByFeasibility(LhScheduleContext context,
                                                                      String embryoCode,
                                                                      int rawEmbryoStock,
                                                                      List<SkuScheduleDTO> skuList,
                                                                      Map<String, Integer> initialQuotaMap,
                                                                      String reason) {
        Map<String, Integer> adjustedQuotaMap = new LinkedHashMap<String, Integer>(initialQuotaMap);
        if (Objects.isNull(context) || CollectionUtils.isEmpty(skuList) || CollectionUtils.isEmpty(initialQuotaMap)) {
            return adjustedQuotaMap;
        }
        Map<String, Integer> feasibleQtyMap = new LinkedHashMap<String, Integer>(skuList.size());
        int transferQty = 0;
        for (SkuScheduleDTO sku : skuList) {
            int initialQuota = Math.max(0, initialQuotaMap.getOrDefault(sku.getMaterialCode(), 0));
            int feasibleQty = resolveSharedEmbryoTDayFeasibleQty(context, sku, rawEmbryoStock);
            feasibleQtyMap.put(sku.getMaterialCode(), feasibleQty);
            int cappedQuota = Math.min(initialQuota, feasibleQty);
            adjustedQuotaMap.put(sku.getMaterialCode(), cappedQuota);
            transferQty += Math.max(0, initialQuota - cappedQuota);
        }
        if (transferQty <= 0) {
            log.info("共用胎胚SKU额度可行性修正无需调整, embryoCode: {}, 初始额度: {}, 可行额度: {}, reason: {}",
                    embryoCode, initialQuotaMap, feasibleQtyMap, reason);
            return adjustedQuotaMap;
        }
        int remainingTransferQty = transferQty;
        for (SkuScheduleDTO sku : skuList) {
            if (remainingTransferQty <= 0) {
                break;
            }
            int currentQuota = Math.max(0, adjustedQuotaMap.getOrDefault(sku.getMaterialCode(), 0));
            int feasibleQty = Math.max(0, feasibleQtyMap.getOrDefault(sku.getMaterialCode(), 0));
            int extraCapacityQty = Math.max(0, feasibleQty - currentQuota);
            if (extraCapacityQty <= 0) {
                continue;
            }
            int acceptedQty = Math.min(remainingTransferQty, extraCapacityQty);
            adjustedQuotaMap.put(sku.getMaterialCode(), currentQuota + acceptedQty);
            remainingTransferQty -= acceptedQty;
        }
        if (remainingTransferQty > 0) {
            log.info("共用胎胚SKU额度存在未消化库存, embryoCode: {}, 未消化量: {}, 初始额度: {}, "
                            + "可行额度: {}, 修正额度: {}, reason: {}",
                    embryoCode, remainingTransferQty, initialQuotaMap, feasibleQtyMap, adjustedQuotaMap, reason);
        } else {
            log.info("共用胎胚SKU额度按换模可行性完成转移, embryoCode: {}, 转移量: {}, 初始额度: {}, "
                            + "可行额度: {}, 修正额度: {}, reason: {}",
                    embryoCode, transferQty, initialQuotaMap, feasibleQtyMap, adjustedQuotaMap, reason);
        }
        return adjustedQuotaMap;
    }

    /**
     * 评估SKU在T日早班/中班可合理消化的胎胚库存额度。
     *
     * @param context 排程上下文
     * @param sku SKU
     * @param rawEmbryoStock 原始胎胚库存
     * @return T日可合理消化量
     */
    private int resolveSharedEmbryoTDayFeasibleQty(LhScheduleContext context,
                                                   SkuScheduleDTO sku,
                                                   int rawEmbryoStock) {
        int upperLimitQty = Math.max(0, rawEmbryoStock);
        if (upperLimitQty <= 0 || Objects.isNull(context) || Objects.isNull(sku)) {
            return 0;
        }
        IMachineMatchStrategy matchStrategy = getMachineMatchStrategy();
        if (Objects.isNull(matchStrategy)) {
            log.info("共用胎胚可行性修正跳过机台预评估, materialCode: {}, reason: 机台匹配策略未注入",
                    sku.getMaterialCode());
            return upperLimitQty;
        }
        List<LhShiftConfigVO> tDayChangeoverShifts = resolveTDayChangeoverFeasibleShifts(context);
        if (CollectionUtils.isEmpty(tDayChangeoverShifts)) {
            log.info("共用胎胚可行性修正跳过班次预评估, materialCode: {}, reason: 缺少T日早中班窗口",
                    sku.getMaterialCode());
            return upperLimitQty;
        }
        List<MachineScheduleDTO> candidates = matchStrategy.matchMachines(context, sku);
        if (CollectionUtils.isEmpty(candidates)) {
            log.info("共用胎胚可行性修正判定不可消化, materialCode: {}, embryoCode: {}, reason: 无候选机台",
                    sku.getMaterialCode(), sku.getEmbryoCode());
            return 0;
        }
        if (!hasTDayMouldChangeCapacity(context) && !hasSameMaterialCandidate(candidates, sku)) {
            log.info("共用胎胚可行性修正判定不可消化, materialCode: {}, embryoCode: {}, reason: T日换模次数已达上限",
                    sku.getMaterialCode(), sku.getEmbryoCode());
            return 0;
        }
        int feasibleQty = 0;
        for (MachineScheduleDTO machine : candidates) {
            Date previewStartTime = resolveFeasibilityPreviewStartTime(machine, tDayChangeoverShifts);
            int machineCapacity = calcMachineAvailableCapacityByStartTime(
                    context, sku, machine, previewStartTime, previewStartTime,
                    tDayChangeoverShifts, ScheduleTypeEnum.NEW_SPEC.getCode());
            feasibleQty += Math.max(0, machineCapacity);
            if (feasibleQty >= upperLimitQty) {
                break;
            }
        }
        int resolvedFeasibleQty = Math.min(upperLimitQty, Math.max(0, feasibleQty));
        log.info("共用胎胚SKU换模可行性预评估, materialCode: {}, embryoCode: {}, 候选机台数: {}, "
                        + "T日早中班可消化量: {}, 原始胎胚库存: {}",
                sku.getMaterialCode(), sku.getEmbryoCode(), candidates.size(), resolvedFeasibleQty, upperLimitQty);
        return resolvedFeasibleQty;
    }

    /**
     * 解析T日允许换模/换活字块后继续生产的早班和中班。
     *
     * @param context 排程上下文
     * @return T日早班、中班列表
     */
    private List<LhShiftConfigVO> resolveTDayChangeoverFeasibleShifts(LhScheduleContext context) {
        List<LhShiftConfigVO> shifts = resolveScheduleShifts(context);
        if (CollectionUtils.isEmpty(shifts)) {
            return new ArrayList<LhShiftConfigVO>(0);
        }
        LocalDate scheduleDate = resolveScheduleLocalDate(context);
        List<LhShiftConfigVO> tDayShifts = new ArrayList<LhShiftConfigVO>(2);
        for (LhShiftConfigVO shift : shifts) {
            if (Objects.isNull(shift)) {
                continue;
            }
            LocalDate workDate = toLocalDate(shift.getWorkDate());
            ShiftEnum shiftType = shift.resolveShiftTypeEnum();
            if (Objects.nonNull(scheduleDate) && !scheduleDate.equals(workDate)) {
                continue;
            }
            if (shiftType == ShiftEnum.MORNING_SHIFT || shiftType == ShiftEnum.AFTERNOON_SHIFT) {
                tDayShifts.add(shift);
            }
        }
        return tDayShifts;
    }

    /**
     * 判断T日是否仍有换模/换活字块承接容量。
     *
     * @param context 排程上下文
     * @return true-仍有容量或策略未注入；false-换模次数已达上限
     */
    private boolean hasTDayMouldChangeCapacity(LhScheduleContext context) {
        if (Objects.isNull(mouldChangeBalanceStrategy) || Objects.isNull(context)
                || Objects.isNull(context.getScheduleDate())) {
            return true;
        }
        return mouldChangeBalanceStrategy.getRemainingCapacity(context, context.getScheduleDate()) > 0;
    }

    /**
     * 判断候选机台是否已有同物料续作承接。
     *
     * @param candidates 候选机台
     * @param sku SKU
     * @return true-存在同物料候选机台；false-需要换模或换活字块承接
     */
    private boolean hasSameMaterialCandidate(List<MachineScheduleDTO> candidates, SkuScheduleDTO sku) {
        if (CollectionUtils.isEmpty(candidates) || Objects.isNull(sku)
                || StringUtils.isEmpty(sku.getMaterialCode())) {
            return false;
        }
        for (MachineScheduleDTO machine : candidates) {
            if (Objects.nonNull(machine)
                    && StringUtils.equals(sku.getMaterialCode(), machine.getPreviousMaterialCode())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 解析可行性预评估起算时间。
     *
     * @param machine 机台
     * @param shifts T日早中班
     * @return 预评估起算时间
     */
    private Date resolveFeasibilityPreviewStartTime(MachineScheduleDTO machine, List<LhShiftConfigVO> shifts) {
        Date firstShiftStartTime = shifts.get(0).getShiftStartDateTime();
        if (Objects.isNull(machine) || Objects.isNull(machine.getEstimatedEndTime())) {
            return firstShiftStartTime;
        }
        return machine.getEstimatedEndTime().after(firstShiftStartTime)
                ? machine.getEstimatedEndTime() : firstShiftStartTime;
    }

    private Map<String, Integer> buildAllocationWeightMap(LhScheduleContext context, List<SkuScheduleDTO> skuList) {
        Map<String, Integer> weightMap = new LinkedHashMap<String, Integer>(skuList.size());
        for (SkuScheduleDTO sku : skuList) {
            weightMap.put(sku.getMaterialCode(), resolveAllocationWeight(context, sku));
        }
        return weightMap;
    }

    private int resolveAllocationWeight(LhScheduleContext context, SkuScheduleDTO sku) {
        if (Objects.isNull(context) || Objects.isNull(sku) || StringUtils.isEmpty(sku.getMaterialCode())) {
            return 0;
        }
        MdmSkuLhCapacity capacity = context.getSkuLhCapacityMap().get(sku.getMaterialCode());
        if (Objects.nonNull(capacity) && Objects.nonNull(capacity.getStandardCapacity())
                && capacity.getStandardCapacity() > 0) {
            return capacity.getStandardCapacity();
        }
        return Math.max(0, sku.getDailyCapacity());
    }

    private int sumAllocationWeight(Map<String, Integer> weightMap) {
        int totalWeight = 0;
        if (CollectionUtils.isEmpty(weightMap)) {
            return totalWeight;
        }
        for (Integer weight : weightMap.values()) {
            totalWeight += Math.max(0, Objects.isNull(weight) ? 0 : weight);
        }
        return totalWeight;
    }

    private void resetFullEmbryoStock(List<SkuScheduleDTO> skuList, int rawEmbryoStock) {
        for (SkuScheduleDTO sku : skuList) {
            if (Objects.nonNull(sku)) {
                sku.setEmbryoStock(rawEmbryoStock);
            }
        }
    }

    private List<String> collectMaterialCodes(List<SkuScheduleDTO> skuList) {
        List<String> materialCodeList = new ArrayList<String>(skuList.size());
        for (SkuScheduleDTO sku : skuList) {
            if (Objects.nonNull(sku) && StringUtils.isNotEmpty(sku.getMaterialCode())) {
                materialCodeList.add(sku.getMaterialCode());
            }
        }
        return materialCodeList;
    }

    private String resolveEmbryoDesc(List<SkuScheduleDTO> skuList) {
        if (CollectionUtils.isEmpty(skuList)) {
            return "";
        }
        for (SkuScheduleDTO sku : skuList) {
            if (Objects.nonNull(sku) && StringUtils.isNotEmpty(sku.getMainMaterialDesc())) {
                return sku.getMainMaterialDesc();
            }
        }
        return "";
    }

    private List<SkuScheduleDTO> collectCandidateSkus(LhScheduleContext context) {
        List<SkuScheduleDTO> candidateSkuList = new ArrayList<>(16);
        if (!CollectionUtils.isEmpty(context.getContinuousSkuList())) {
            candidateSkuList.addAll(context.getContinuousSkuList());
        }
        if (!CollectionUtils.isEmpty(context.getNewSpecSkuList())) {
            candidateSkuList.addAll(context.getNewSpecSkuList());
        }
        if (candidateSkuList.isEmpty() && !CollectionUtils.isEmpty(context.getStructureSkuMap())) {
            for (List<SkuScheduleDTO> skuList : context.getStructureSkuMap().values()) {
                if (!CollectionUtils.isEmpty(skuList)) {
                    candidateSkuList.addAll(skuList);
                }
            }
        }
        return candidateSkuList;
    }

    private boolean isActiveEmbryoSku(SkuScheduleDTO sku,
                                      SkuScheduleDTO currentSku,
                                      Set<String> unscheduledMaterialSet,
                                      Set<String> completedEndingMaterialSet) {
        if (sku == null || StringUtils.isEmpty(sku.getMaterialCode())
                || StringUtils.isEmpty(sku.getEmbryoCode())) {
            return false;
        }
        if (Objects.nonNull(currentSku) && sku != currentSku && sku.resolveTargetScheduleQty() <= 0) {
            return false;
        }
        if (unscheduledMaterialSet.contains(sku.getMaterialCode())) {
            return false;
        }
        return !completedEndingMaterialSet.contains(sku.getMaterialCode());
    }

    private Set<String> collectUnscheduledMaterialSet(LhScheduleContext context) {
        Set<String> materialSet = new HashSet<>(8);
        if (CollectionUtils.isEmpty(context.getUnscheduledResultList())) {
            return materialSet;
        }
        context.getUnscheduledResultList().forEach(result -> {
            if (result != null && StringUtils.isNotEmpty(result.getMaterialCode())) {
                materialSet.add(result.getMaterialCode());
            }
        });
        return materialSet;
    }

    private Set<String> collectCompletedEndingMaterialSet(LhScheduleContext context) {
        Set<String> materialSet = new HashSet<>(8);
        if (CollectionUtils.isEmpty(context.getScheduleResultList())) {
            return materialSet;
        }
        for (LhScheduleResult result : context.getScheduleResultList()) {
            if (result != null && "1".equals(result.getIsEnd())
                    && StringUtils.isNotEmpty(result.getMaterialCode())) {
                materialSet.add(result.getMaterialCode());
            }
        }
        return materialSet;
    }

    /**
     * 获取机台匹配策略（带空安全回退）。
     *
     * @return 机台匹配策略
     */
    private IMachineMatchStrategy getMachineMatchStrategy() {
        return machineMatchStrategy;
    }

    private LinkedHashMap<LocalDate, Integer> initCapacityByDate(List<LhShiftConfigVO> shifts) {
        LinkedHashMap<LocalDate, Integer> capacityByDate = new LinkedHashMap<LocalDate, Integer>(8);
        if (CollectionUtils.isEmpty(shifts)) {
            return capacityByDate;
        }
        for (LhShiftConfigVO shift : shifts) {
            LocalDate workDate = toLocalDate(shift.getWorkDate());
            if (workDate != null && !capacityByDate.containsKey(workDate)) {
                capacityByDate.put(workDate, 0);
            }
        }
        return capacityByDate;
    }

    private int sumMachineCapacityByDate(Map<LocalDate, Integer> capacityByDate) {
        int totalQty = 0;
        if (capacityByDate == null || capacityByDate.isEmpty()) {
            return totalQty;
        }
        for (Integer capacityQty : capacityByDate.values()) {
            totalQty += Math.max(0, capacityQty == null ? 0 : capacityQty);
        }
        return Math.max(totalQty, 0);
    }

    private int sumShiftCapacity(Map<Integer, Integer> capacityByShift) {
        int totalQty = 0;
        if (capacityByShift == null || capacityByShift.isEmpty()) {
            return totalQty;
        }
        for (Integer capacityQty : capacityByShift.values()) {
            totalQty += Math.max(0, capacityQty == null ? 0 : capacityQty);
        }
        return Math.max(totalQty, 0);
    }

    private int countEffectiveShiftCount(Map<Integer, Integer> capacityByShift) {
        int shiftCount = 0;
        if (capacityByShift == null || capacityByShift.isEmpty()) {
            return shiftCount;
        }
        for (Integer capacityQty : capacityByShift.values()) {
            if (capacityQty != null && capacityQty > 0) {
                shiftCount++;
            }
        }
        return shiftCount;
    }

    private int resolveStructureEndingDays(LhScheduleContext context) {
        if (context == null || context.getScheduleConfig() == null) {
            return LhScheduleConstant.DEFAULT_STRUCTURE_ENDING_DAYS;
        }
        return Math.max(1, context.getScheduleConfig().getStructureEndingDays());
    }

    private int resolveStructureEndingDemandQty(SkuScheduleDTO sku) {
        if (sku == null) {
            return 0;
        }
        return Math.max(0, sku.getSurplusQty());
    }

    private List<LhShiftConfigVO> resolveStructurePriorityShifts(LhScheduleContext context, int structureEndingDays) {
        List<LhShiftConfigVO> baseShifts = resolveScheduleShifts(context);
        if (CollectionUtils.isEmpty(baseShifts)) {
            return new ArrayList<>(0);
        }
        int targetDays = Math.max(1, structureEndingDays);
        int currentDays = resolveCoveredDays(baseShifts);
        if (currentDays >= targetDays) {
            return new ArrayList<>(baseShifts);
        }
        List<LhShiftConfigVO> extendedShifts = new ArrayList<>(baseShifts.size() + (targetDays - currentDays) * 3);
        extendedShifts.addAll(baseShifts);
        List<LhShiftConfigVO> templateDayShifts = collectShiftsByOffset(baseShifts, currentDays - 1);
        if (CollectionUtils.isEmpty(templateDayShifts)) {
            return extendedShifts;
        }
        int nextShiftIndex = baseShifts.get(baseShifts.size() - 1).getShiftIndex() == null
                ? baseShifts.size() + 1 : baseShifts.get(baseShifts.size() - 1).getShiftIndex() + 1;
        for (int offset = currentDays; offset < targetDays; offset++) {
            for (LhShiftConfigVO templateShift : templateDayShifts) {
                extendedShifts.add(cloneShiftForOffset(templateShift, offset, nextShiftIndex++));
            }
        }
        return extendedShifts;
    }

    private int resolveCoveredDays(List<LhShiftConfigVO> shifts) {
        int maxOffset = -1;
        for (LhShiftConfigVO shift : shifts) {
            if (shift != null && shift.getDateOffset() != null) {
                maxOffset = Math.max(maxOffset, shift.getDateOffset());
            }
        }
        return maxOffset + 1;
    }

    private List<LhShiftConfigVO> collectShiftsByOffset(List<LhShiftConfigVO> shifts, int dateOffset) {
        List<LhShiftConfigVO> templateDayShifts = new ArrayList<>(4);
        for (LhShiftConfigVO shift : shifts) {
            if (shift != null && shift.getDateOffset() != null && shift.getDateOffset() == dateOffset) {
                templateDayShifts.add(shift);
            }
        }
        return templateDayShifts;
    }

    private LhShiftConfigVO cloneShiftForOffset(LhShiftConfigVO templateShift, int dateOffset, int shiftIndex) {
        LhShiftConfigVO clonedShift = new LhShiftConfigVO();
        clonedShift.setScheduleBaseDate(templateShift.getScheduleBaseDate());
        clonedShift.setShiftType(templateShift.getShiftType());
        clonedShift.setShiftCode(templateShift.getShiftCode());
        clonedShift.setStartTime(templateShift.getStartTime());
        clonedShift.setEndTime(templateShift.getEndTime());
        clonedShift.setShiftDuration(templateShift.getShiftDuration());
        clonedShift.setDateOffset(dateOffset);
        clonedShift.setShiftIndex(shiftIndex);
        ShiftEnum shiftType = templateShift.resolveShiftTypeEnum();
        if (shiftType != null) {
            clonedShift.setShiftName(buildStructureShiftName(dateOffset, shiftType));
        } else {
            clonedShift.setShiftName(templateShift.getShiftName());
        }
        return clonedShift;
    }

    private String buildStructureShiftName(int dateOffset, ShiftEnum shiftType) {
        String prefix = dateOffset == 0 ? "T日" : "T+" + dateOffset + "日";
        return prefix + shiftType.getDescription();
    }

    private int resolveStructureEndingDaysWithinWindow(List<LhShiftConfigVO> shifts,
                                                       Map<Integer, Integer> effectiveShiftCapacityMap,
                                                       int demandQty,
                                                       int structureEndingDays) {
        if (demandQty <= 0) {
            return 0;
        }
        LinkedHashMap<LocalDate, Integer> capacityByDate = new LinkedHashMap<>(16);
        for (LhShiftConfigVO shift : shifts) {
            if (shift == null || shift.getShiftIndex() == null) {
                continue;
            }
            Integer shiftQty = effectiveShiftCapacityMap.get(shift.getShiftIndex());
            if (shiftQty == null || shiftQty <= 0) {
                continue;
            }
            LocalDate workDate = toLocalDate(shift.getWorkDate());
            if (workDate != null) {
                capacityByDate.merge(workDate, shiftQty, Integer::sum);
            }
        }
        int cumulativeCapacity = 0;
        int endingDays = 0;
        for (Integer dayQty : capacityByDate.values()) {
            endingDays++;
            cumulativeCapacity += Math.max(0, dayQty == null ? 0 : dayQty);
            if (cumulativeCapacity >= demandQty) {
                return endingDays;
            }
        }
        return Math.max(1, structureEndingDays) + 1;
    }

    private LocalDate toLocalDate(Date workDate) {
        if (workDate == null) {
            return null;
        }
        return workDate.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
    }

    /**
     * 结构收尾有效产能评估快照。
     */
    public static final class StructureEndingCapacitySnapshot {

        private final String materialCode;
        private final String machineCode;
        private final int structureEndingDays;
        private final int demandQty;
        private final int shiftCapacity;
        private final int theoreticalShiftCount;
        private final int deductedChangeoverShiftCount;
        private final int effectiveShiftCount;
        private final int effectiveCapacityQty;
        private final int endingDaysWithinStructureWindow;
        private final boolean hitStructureEnding;

        private StructureEndingCapacitySnapshot(String materialCode,
                                               String machineCode,
                                               int structureEndingDays,
                                               int demandQty,
                                               int shiftCapacity,
                                               int theoreticalShiftCount,
                                               int deductedChangeoverShiftCount,
                                               int effectiveShiftCount,
                                               int effectiveCapacityQty,
                                               int endingDaysWithinStructureWindow,
                                               boolean hitStructureEnding) {
            this.materialCode = materialCode;
            this.machineCode = machineCode;
            this.structureEndingDays = structureEndingDays;
            this.demandQty = demandQty;
            this.shiftCapacity = shiftCapacity;
            this.theoreticalShiftCount = theoreticalShiftCount;
            this.deductedChangeoverShiftCount = deductedChangeoverShiftCount;
            this.effectiveShiftCount = effectiveShiftCount;
            this.effectiveCapacityQty = effectiveCapacityQty;
            this.endingDaysWithinStructureWindow = endingDaysWithinStructureWindow;
            this.hitStructureEnding = hitStructureEnding;
        }

        public static StructureEndingCapacitySnapshot empty(int structureEndingDays) {
            return new StructureEndingCapacitySnapshot(
                    null, null, structureEndingDays, 0, 0, 0, 0, 0, 0, structureEndingDays + 1, false);
        }

        public String getMaterialCode() {
            return materialCode;
        }

        public String getMachineCode() {
            return machineCode;
        }

        public int getStructureEndingDays() {
            return structureEndingDays;
        }

        public int getDemandQty() {
            return demandQty;
        }

        public int getShiftCapacity() {
            return shiftCapacity;
        }

        public int getTheoreticalShiftCount() {
            return theoreticalShiftCount;
        }

        public int getDeductedChangeoverShiftCount() {
            return deductedChangeoverShiftCount;
        }

        public int getEffectiveShiftCount() {
            return effectiveShiftCount;
        }

        public int getEffectiveCapacityQty() {
            return effectiveCapacityQty;
        }

        public int getEndingDaysWithinStructureWindow() {
            return endingDaysWithinStructureWindow;
        }

        public boolean isHitStructureEnding() {
            return hitStructureEnding;
        }
    }
}
