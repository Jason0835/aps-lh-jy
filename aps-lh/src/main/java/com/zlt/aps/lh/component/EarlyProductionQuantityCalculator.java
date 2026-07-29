package com.zlt.aps.lh.component;

import com.zlt.aps.lh.api.domain.dto.CuringMonthPlanTotalResult;
import com.zlt.aps.lh.api.domain.dto.SkuScheduleDTO;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.engine.strategy.support.EarlyProductionChecker;
import com.zlt.aps.lh.engine.strategy.support.EarlyProductionDecision;
import com.zlt.aps.lh.engine.strategy.support.EarlyProductionRuntimePlan;
import com.zlt.aps.lh.exception.ScheduleErrorCode;
import com.zlt.aps.lh.exception.ScheduleException;
import com.zlt.aps.mp.api.domain.entity.FactoryMonthPlanProductionFinalResult;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.util.CollectionUtils;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * SKU 提前生产数量口径计算器。
 *
 * <p>该类集中处理提前生产新增的计划量、完成量、历史欠产和跨月余量口径，
 * 避免把提前生产的观察范围和跨月规则散落到通用月计划计算器及 S4.3 主处理器中。</p>
 *
 * <p>本类只计算并初始化运行期缓存，不修改数据库月计划、不改写原始 dayN，
 * 也不分配机台、模具或胎胚。</p>
 */
@Slf4j
public final class EarlyProductionQuantityCalculator {

    /** 提前生产跨月未来计划段场景 */
    private static final String SCENE_EARLY_PRODUCTION_FUTURE_MONTH_SEGMENT =
            "EARLY_PRODUCTION_FUTURE_MONTH_SEGMENT";

    /** 月计划最小自然日 */
    private static final int MIN_DAY_OF_MONTH = 1;

    /** 上月超欠产有效标识 */
    private static final String LAST_MONTH_OVERDUE_VALID_FLAG = "1";

    private EarlyProductionQuantityCalculator() {
    }

    /**
     * 计算包含提前生产观察范围的硫化月计划总量。
     *
     * <p>普通窗口内计划继续复用 {@link CuringMonthPlanTotalCalculator}。仅当窗口内无计划、
     * 且提前生产阈值内最早计划日落在下一自然月时，才切换为未来月份真实计划段；
     * 当前业务月历史欠产由运行视图单独追加，不能在这里与未来月计划重复合并。</p>
     *
     * @param context 排程上下文
     * @param plan SKU 归集基础月计划
     * @param scheduleStartDate 排程窗口开始日
     * @param windowEndDate 排程窗口结束日
     * @param actualFinishedQty 基础计划所属月已完成量
     * @param lastMonthOverdueQty 有效上月超欠产量
     * @return 月计划总量计算结果
     */
    public static CuringMonthPlanTotalResult calculateMonthPlanTotal(
            LhScheduleContext context,
            FactoryMonthPlanProductionFinalResult plan,
            LocalDate scheduleStartDate,
            LocalDate windowEndDate,
            int actualFinishedQty,
            int lastMonthOverdueQty) {
        CuringMonthPlanTotalResult normalResult = CuringMonthPlanTotalCalculator.calculate(
                context, plan, scheduleStartDate, windowEndDate,
                actualFinishedQty, lastMonthOverdueQty);
        if (Objects.isNull(context) || Objects.isNull(plan)
                || StringUtils.isEmpty(plan.getMaterialCode())
                || Objects.isNull(scheduleStartDate) || Objects.isNull(windowEndDate)) {
            return normalResult;
        }
        LocalDate earlyRangeEndDate = windowEndDate.plusDays(
                EarlyProductionChecker.resolveEarlyProductionDaysThreshold(context));
        LocalDate futurePlanDate = findFirstPositivePlanDate(
                context, plan, scheduleStartDate.plusDays(1), earlyRangeEndDate);
        if (Objects.isNull(futurePlanDate)
                || !futurePlanDate.isAfter(windowEndDate)
                || isSameMonth(scheduleStartDate, futurePlanDate)) {
            return normalResult;
        }
        return buildFutureMonthPlanResult(
                context, plan.getMaterialCode(), plan.getProductStatus(),
                scheduleStartDate, futurePlanDate);
    }

    /**
     * 解析 SKU 当前业务月月计划原始 TOTAL_QTY。
     *
     * <p>该值只用于正常排产与提前生产的路由判断，不替代通用硫化余量。当前月记录缺失、
     * TOTAL_QTY 为空或小于0时均按0处理；禁止从未来月份计划或运行态余量反推当前月总量。</p>
     *
     * @param context 排程上下文
     * @param sku SKU
     * @param currentDate 当前业务月判断日期
     * @return 当前业务月非负 TOTAL_QTY
     */
    public static int resolveCurrentMonthTotalQty(
            LhScheduleContext context,
            SkuScheduleDTO sku,
            LocalDate currentDate) {
        if (Objects.isNull(context) || Objects.isNull(sku)
                || StringUtils.isEmpty(sku.getMaterialCode()) || Objects.isNull(currentDate)) {
            return 0;
        }
        FactoryMonthPlanProductionFinalResult currentMonthPlan =
                MonthPlanDateResolver.resolvePlan(
                        context, sku.getMaterialCode(), sku.getProductStatus(), currentDate);
        return Objects.isNull(currentMonthPlan) || Objects.isNull(currentMonthPlan.getTotalQty())
                ? 0 : Math.max(0, currentMonthPlan.getTotalQty());
    }

    /**
     * 按当前业务月 TOTAL_QTY 应用正常排产与提前生产路由。
     *
     * <p>当前月 TOTAL_QTY 大于0时不改变 SKU；等于0或当前月记录缺失时，清除正常排产
     * 目标并同步实际消费账本，然后仅对满足正规新增范围且未来有原始日计划的 SKU
     * 注册候选视图。该方法不修改通用 surplusQty、完成量或超欠产计算结果。</p>
     *
     * @param context 排程上下文
     * @param sku SKU
     * @param scheduleStartDate 排程窗口开始日
     * @param targetScheduleQtyResolver 目标量账本解析器
     * @return true-当前月无总计划量，正常排产已被阻断；false-继续正常排产
     */
    public static boolean applyCurrentMonthTotalRoute(
            LhScheduleContext context,
            SkuScheduleDTO sku,
            LocalDate scheduleStartDate,
            TargetScheduleQtyResolver targetScheduleQtyResolver) {
        int currentMonthTotalQty =
                resolveCurrentMonthTotalQty(context, sku, scheduleStartDate);
        if (currentMonthTotalQty > 0) {
            return false;
        }
        int originalTargetQty = sku.resolveTargetScheduleQty();
        sku.setTargetScheduleQty(0);
        sku.setPendingQty(0);
        sku.setRemainingScheduleQty(0);
        if (Objects.nonNull(targetScheduleQtyResolver)) {
            // 调用处同步清零实际消费账本，确保正常新增、续作和收尾分支均无法消费原目标。
            targetScheduleQtyResolver.syncProductionRemainingQtyToTarget(
                    context, sku, 0, "当前业务月TOTAL_QTY为0，禁止进入正常排产");
        }
        registerFutureOnlyCandidateView(context, sku, scheduleStartDate);
        log.info("当前业务月TOTAL_QTY为0，正常排产目标已隔离, materialCode: {}, productStatus: {}, "
                        + "scheduleStartDate: {}, originalTargetQty: {}, surplusQty: {}, "
                        + "historyShortageQty: {}, embryoStock: {}, futureOnlyCandidate: {}",
                sku.getMaterialCode(), sku.getProductStatus(), scheduleStartDate,
                originalTargetQty, sku.getSurplusQty(),
                sku.getMonthlyHistoryShortageQty(), sku.getEmbryoStock(),
                context.isFutureOnlyEarlyProductionCandidate(sku));
        return true;
    }

    /**
     * 解析收尾小余量规则应使用的有效余量。
     *
     * <p>当前业务月 {@code TOTAL_QTY=0} 的 SKU 在候选态仍不具备排产资格，只有中心运行视图
     * 已激活后，才允许使用实际消费账本中的实时剩余目标量。该账本会随每次有效排产立即扣减，
     * 因而能够反映部分排产后的真实剩余量，禁止直接使用运行视图初始化时的
     * {@link EarlyProductionRuntimePlan#getEffectiveTargetQty()} 固定快照。</p>
     *
     * <p>普通新增、续作、换活字块以及尚未激活的提前生产候选仍返回通用
     * {@link SkuScheduleDTO#getSurplusQty()}，确保本次修复不改变正常余量和既有收尾规则口径。</p>
     *
     * @param context 排程上下文
     * @param sku 待判断 SKU
     * @param targetScheduleQtyResolver 目标量实际消费账本解析器
     * @return 收尾小余量规则应使用的非负有效余量
     */
    public static int resolveSmallEndingRuleQty(
            LhScheduleContext context,
            SkuScheduleDTO sku,
            TargetScheduleQtyResolver targetScheduleQtyResolver) {
        int genericSurplusQty = Objects.isNull(sku)
                ? 0 : Math.max(0, sku.getSurplusQty());
        if (!shouldUseRuntimeRemainingQtyForSmallEnding(context, sku)) {
            return genericSurplusQty;
        }
        Objects.requireNonNull(
                targetScheduleQtyResolver, "提前生产收尾小余量规则的目标量账本解析器不能为空");
        int runtimeRemainingQty = Math.max(
                0, targetScheduleQtyResolver.resolveProductionRemainingQty(context, sku));
        log.info("提前生产收尾小余量规则数量解析, materialCode: {}, productStatus: {}, "
                        + "genericSurplusQty: {}, runtimeRemainingQty: {}, quantitySource: {}",
                sku.getMaterialCode(), sku.getProductStatus(), genericSurplusQty,
                runtimeRemainingQty, "EARLY_PRODUCTION_RUNTIME_REMAINING");
        return runtimeRemainingQty;
    }

    /**
     * 判断收尾小余量规则是否应切换到提前生产运行态剩余量。
     *
     * <p>仅当前月无总计划量、且已经通过提前生产准入并完成目标量账本初始化的候选才切换。
     * 普通提前生产 SKU 的通用余量仍然有效，不在本次修复中扩大影响范围。</p>
     *
     * @param context 排程上下文
     * @param sku 待判断 SKU
     * @return true-使用提前生产实际消费账本剩余量；false-使用通用硫化余量
     */
    public static boolean shouldUseRuntimeRemainingQtyForSmallEnding(
            LhScheduleContext context,
            SkuScheduleDTO sku) {
        return isActiveFutureOnlyRuntimeView(context, sku);
    }

    /**
     * 判断当前 SKU 是否已经进入“当前月 TOTAL_QTY=0”的提前生产中心运行视图。
     *
     * <p>候选态仅表示未来观察范围存在原始日计划，尚未取得提前生产资格；只有
     * {@link EarlyProductionRuntimePlan#isActive()} 为 true 时，后续收尾、小余量和最终
     * 完成判断才允许切换到中心运行视图口径。普通新增、续作、换活字块以及尚未激活的
     * future-only 候选均返回 false，确保通用硫化余量逻辑不受影响。</p>
     *
     * @param context 排程上下文
     * @param sku 待判断 SKU
     * @return true-已激活 future-only 中心运行视图；false-沿用通用数量口径
     */
    public static boolean isActiveFutureOnlyRuntimeView(
            LhScheduleContext context,
            SkuScheduleDTO sku) {
        if (Objects.isNull(context) || Objects.isNull(sku)) {
            return false;
        }
        EarlyProductionRuntimePlan runtimePlan =
                context.getEarlyProductionRuntimePlan(sku);
        return Objects.nonNull(runtimePlan)
                && runtimePlan.isFutureOnlyCandidate()
                && runtimePlan.isActive();
    }

    /**
     * 解析普通收尾判断应使用的提前生产中心目标量。
     *
     * <p>当前月 TOTAL_QTY=0 的 SKU 激活提前生产后，通用 surplusQty 仍保持正常排产原口径，
     * 因此普通收尾逻辑不能再用 surplusQty 或胎胚库存覆盖中心目标。这里返回运行视图初始化时
     * 冻结的 {@link EarlyProductionRuntimePlan#getEffectiveTargetQty()}，使排前收尾判断、排后
     * 完成判断和未排量结算都以同一个总目标对账。实际剩余量仍由生产消费账本负责扣减，禁止
     * 使用本方法返回值重置账本。</p>
     *
     * <p>成型胎胚库存收尾属于更高优先级的精确硬目标，由调用方在进入本方法前处理，本方法
     * 只隔离普通收尾和共用胎胚零余量规则。</p>
     *
     * @param context 排程上下文
     * @param sku 待判断 SKU
     * @return 已激活中心运行视图的非负总目标；不适用时返回 null
     */
    public static Integer resolveActiveFutureOnlyEndingTargetQty(
            LhScheduleContext context,
            SkuScheduleDTO sku) {
        if (!isActiveFutureOnlyRuntimeView(context, sku)) {
            return null;
        }
        EarlyProductionRuntimePlan runtimePlan =
                context.getEarlyProductionRuntimePlan(sku);
        int effectiveTargetQty = Math.max(0, runtimePlan.getEffectiveTargetQty());
        log.info("提前生产普通收尾目标切换到中心运行视图, materialCode: {}, productStatus: {}, "
                        + "currentDate: {}, futurePlanDate: {}, genericSurplusQty: {}, "
                        + "effectiveTargetQty: {}, quantitySource: {}",
                sku.getMaterialCode(), sku.getProductStatus(), runtimePlan.getCurrentDate(),
                runtimePlan.getFuturePlanDate(), Math.max(0, sku.getSurplusQty()),
                effectiveTargetQty, "EARLY_PRODUCTION_EFFECTIVE_TARGET");
        return effectiveTargetQty;
    }

    /**
     * 为当前月 TOTAL_QTY=0、未来观察范围存在原始日计划的正规 SKU 注册提前生产候选视图。
     *
     * <p>候选观察范围覆盖“排程窗口开始日下一天～窗口结束日+N”，用于保证尚未进入
     * 当前业务日提前阈值的未来 SKU 仍保留在三天运行队列中。该方法只建立候选态，
     * 不修改 SKU 通用余量、不分配资源，也不代表已经允许提前生产。</p>
     *
     * @param context 排程上下文
     * @param sku SKU
     * @param scheduleStartDate 排程窗口开始业务日
     * @return 候选运行视图；当前月有总计划量、非正规新增范围或未来无计划时返回 null
     */
    public static EarlyProductionRuntimePlan registerFutureOnlyCandidateView(
            LhScheduleContext context,
            SkuScheduleDTO sku,
            LocalDate scheduleStartDate) {
        if (Objects.isNull(context) || Objects.isNull(sku) || Objects.isNull(scheduleStartDate)
                || Objects.isNull(context.getWindowEndDate())
                || StringUtils.isEmpty(sku.getMaterialCode())
                || !EarlyProductionChecker.isEligibleNewProductionSku(sku)) {
            return null;
        }
        int currentMonthTotalQty =
                resolveCurrentMonthTotalQty(context, sku, scheduleStartDate);
        if (currentMonthTotalQty > 0) {
            return null;
        }
        int threshold = EarlyProductionChecker.resolveEarlyProductionDaysThreshold(context);
        LocalDate candidateEndDate = toLocalDate(context.getWindowEndDate()).plusDays(threshold);
        LocalDate futurePlanDate = findFirstPositivePlanDate(
                context, sku.getMaterialCode(), sku.getProductStatus(),
                scheduleStartDate.plusDays(1), candidateEndDate);
        if (Objects.isNull(futurePlanDate)) {
            log.info("当前月TOTAL_QTY为0且未来观察范围无日计划，不进入提前生产候选视图, "
                            + "materialCode: {}, productStatus: {}, currentDate: {}, candidateEndDate: {}",
                    sku.getMaterialCode(), sku.getProductStatus(), scheduleStartDate, candidateEndDate);
            return null;
        }

        EarlyProductionRuntimePlan runtimePlan = new EarlyProductionRuntimePlan();
        runtimePlan.setFutureOnlyCandidate(true);
        runtimePlan.setActive(false);
        runtimePlan.setCurrentDate(scheduleStartDate);
        runtimePlan.setFuturePlanDate(futurePlanDate);
        runtimePlan.setEarlyProductionDaysThreshold(threshold);
        runtimePlan.setCurrentMonthTotalQty(currentMonthTotalQty);
        runtimePlan.setOriginalCurrentDayPlanQty(MonthPlanDateResolver.resolveDayQty(
                context, sku.getMaterialCode(), sku.getProductStatus(), scheduleStartDate));
        runtimePlan.setFutureDayPlanQty(MonthPlanDateResolver.resolveDayQty(
                context, sku.getMaterialCode(), sku.getProductStatus(), futurePlanDate));
        populateFutureMonthQuantityView(context, sku, scheduleStartDate, runtimePlan);
        runtimePlan.setDecision(EarlyProductionDecision.notEarlyProduction(
                false, "提前生产候选态，尚未进入当前业务日准入"));
        // 调用处只登记候选视图；机台、模具、胎胚和目标量均在阶段一冻结后激活。
        context.registerEarlyProductionRuntimePlan(sku, runtimePlan);
        log.info("提前生产候选视图注册完成, materialCode: {}, productStatus: {}, currentDate: {}, "
                        + "futurePlanDate: {}, candidateEndDate: {}, currentMonthTotalQty: {}, "
                        + "futureMonthPlanTotalQty: {}, futureMonthFinishedQty: {}, "
                        + "futureMonthSurplusQty: {}, active: false",
                sku.getMaterialCode(), sku.getProductStatus(), scheduleStartDate,
                futurePlanDate, candidateEndDate, currentMonthTotalQty,
                runtimePlan.getFutureMonthPlanTotalQty(),
                runtimePlan.getFutureMonthFinishedQty(),
                runtimePlan.getFutureMonthSurplusQty());
        return runtimePlan;
    }

    /**
     * 刷新候选 SKU 的未来计划月数量视图。
     *
     * <p>未来月余量只写入提前生产运行视图，计算口径为：
     * Max(未来计划段总量 - 未来月已完成量 + 未来月计划有效上月超欠产量, 0)。
     * 该值不会写回 SKU 通用 surplusQty，也不会影响正常排产余量。</p>
     *
     * @param context 排程上下文
     * @param sku SKU
     * @param scheduleStartDate 排程窗口开始日
     * @param runtimePlan 提前生产运行视图
     */
    public static void populateFutureMonthQuantityView(
            LhScheduleContext context,
            SkuScheduleDTO sku,
            LocalDate scheduleStartDate,
            EarlyProductionRuntimePlan runtimePlan) {
        if (Objects.isNull(context) || Objects.isNull(sku)
                || Objects.isNull(scheduleStartDate) || Objects.isNull(runtimePlan)
                || Objects.isNull(runtimePlan.getFuturePlanDate())) {
            return;
        }
        LocalDate futurePlanDate = runtimePlan.getFuturePlanDate();
        CuringMonthPlanTotalResult futurePlanResult = buildFutureMonthPlanResult(
                context, sku.getMaterialCode(), sku.getProductStatus(),
                scheduleStartDate, futurePlanDate);
        int futureMonthPlanTotalQty = Math.max(0, futurePlanResult.getMonthPlanTotal());
        FactoryMonthPlanProductionFinalResult futureMonthPlan =
                MonthPlanDateResolver.resolvePlan(
                        context, sku.getMaterialCode(), sku.getProductStatus(), futurePlanDate);
        int futureMonthFinishedQty =
                resolveMonthFinishedQty(context, sku, futurePlanDate);
        int lastMonthOverdueQty = resolveEffectiveLastMonthOverdueQty(futureMonthPlan);
        long futureMonthSurplusQtyLong =
                (long) futureMonthPlanTotalQty - futureMonthFinishedQty + lastMonthOverdueQty;
        if (futureMonthSurplusQtyLong > Integer.MAX_VALUE) {
            throw new ScheduleException(
                    ScheduleErrorCode.SURPLUS_CALCULATION_ERROR,
                    new StringBuilder("提前生产未来月余量超出整数范围, materialCode: ")
                            .append(sku.getMaterialCode())
                            .append(", futurePlanDate: ").append(futurePlanDate)
                            .append(", futureMonthPlanTotalQty: ").append(futureMonthPlanTotalQty)
                            .append(", futureMonthFinishedQty: ").append(futureMonthFinishedQty)
                            .append(", lastMonthOverdueQty: ").append(lastMonthOverdueQty)
                            .toString());
        }
        runtimePlan.setFutureMonthPlanTotalQty(futureMonthPlanTotalQty);
        runtimePlan.setFutureMonthFinishedQty(futureMonthFinishedQty);
        runtimePlan.setFutureMonthSurplusQty(
                (int) Math.max(0L, futureMonthSurplusQtyLong));
    }

    /**
     * 按排程窗口内每个业务日初始化提前生产动态历史欠产缓存。
     *
     * <p>每个业务日按其真实所属年月，从月初累计至业务日前一日。日完成量直接读取
     * 基础数据阶段已批量加载的月日完成量缓存，逐日超产不抵扣其他日期欠产；
     * 同物料不同产品状态使用复合键隔离。</p>
     *
     * @param context 排程上下文
     */
    public static void initializeMonthlyHistoryShortageByBusinessDate(
            LhScheduleContext context) {
        Map<LocalDate, Map<String, Integer>> shortageByDateMap =
                new LinkedHashMap<LocalDate, Map<String, Integer>>(4);
        if (Objects.isNull(context)) {
            return;
        }
        if (Objects.isNull(context.getScheduleDate())
                || Objects.isNull(context.getWindowEndDate())
                || CollectionUtils.isEmpty(context.getLoadedMonthPlanList())) {
            context.setMonthlyHistoryShortageQtyMap(shortageByDateMap);
            return;
        }
        LocalDate startDate = toLocalDate(context.getScheduleDate());
        LocalDate endDate = toLocalDate(context.getWindowEndDate());
        for (LocalDate businessDate = startDate; !businessDate.isAfter(endDate);
             businessDate = businessDate.plusDays(1)) {
            Map<String, Integer> skuShortageMap =
                    calculateBusinessDateHistoryShortage(context, businessDate);
            shortageByDateMap.put(businessDate, skuShortageMap);
            log.info("提前生产动态历史欠产缓存初始化, factoryCode: {}, currentDate: {}, "
                            + "historyRange: {}~{}, skuCount: {}",
                    context.getFactoryCode(), businessDate,
                    businessDate.withDayOfMonth(MIN_DAY_OF_MONTH),
                    businessDate.minusDays(1), skuShortageMap.size());
        }
        context.setMonthlyHistoryShortageQtyMap(shortageByDateMap);
    }

    /**
     * 解析结果行和 SKU DTO 应使用的目标月份计划。
     *
     * <p>目标月没有提前生产观察范围内的正日计划、而归集基础计划存在时，说明基础计划
     * 来自真实 futurePlanDate 所属月，施工阶段、结构、版本和产能字段必须保留基础计划；
     * 其他场景继续使用 scheduleTargetDate 所属月计划。</p>
     *
     * @param context 排程上下文
     * @param plan SKU 归集基础月计划
     * @param targetDate 排程目标日期
     * @return 应用于结果行和 SKU DTO 的月计划
     */
    public static FactoryMonthPlanProductionFinalResult resolveTargetMonthPlan(
            LhScheduleContext context,
            FactoryMonthPlanProductionFinalResult plan,
            LocalDate targetDate) {
        if (Objects.isNull(context) || Objects.isNull(plan)
                || StringUtils.isEmpty(plan.getMaterialCode())
                || Objects.isNull(targetDate)) {
            return plan;
        }
        FactoryMonthPlanProductionFinalResult targetMonthPlan =
                MonthPlanDateResolver.resolvePlan(
                        context, plan.getMaterialCode(), plan.getProductStatus(), targetDate);
        if (Objects.nonNull(targetMonthPlan)
                && !hasPositivePlanInEarlyProductionRange(context, targetMonthPlan)
                && hasPositivePlanInEarlyProductionRange(context, plan)) {
            return plan;
        }
        return Objects.nonNull(targetMonthPlan) ? targetMonthPlan : plan;
    }

    /**
     * 计算指定业务日之前的本月累计欠产。
     *
     * @param context 排程上下文
     * @param businessDate 当前业务日
     * @return key 为物料加产品状态，value 为非负历史欠产量
     */
    private static Map<String, Integer> calculateBusinessDateHistoryShortage(
            LhScheduleContext context,
            LocalDate businessDate) {
        Map<String, Integer> skuShortageMap =
                new LinkedHashMap<String, Integer>(128);
        LocalDate monthStartDate = businessDate.withDayOfMonth(MIN_DAY_OF_MONTH);
        LocalDate historyEndDate = businessDate.minusDays(1);
        if (historyEndDate.isBefore(monthStartDate)) {
            return skuShortageMap;
        }
        for (FactoryMonthPlanProductionFinalResult plan : context.getLoadedMonthPlanList()) {
            if (!belongsToBusinessMonth(plan, businessDate)) {
                continue;
            }
            int shortageQty = calculateHistoryShortageQty(
                    context, plan, monthStartDate, historyEndDate);
            String skuKey = MonthPlanDateResolver.buildMaterialStatusKey(
                    plan.getMaterialCode(), plan.getProductStatus());
            skuShortageMap.merge(skuKey, shortageQty, Integer::sum);
        }
        return skuShortageMap;
    }

    /**
     * 按日累计单个 SKU 的历史欠产，超产仅记录在完成量中，不跨日抵扣欠产。
     *
     * @param context 排程上下文
     * @param plan 单月月计划
     * @param monthStartDate 月首日
     * @param historyEndDate 历史截止日
     * @return 非负历史欠产量
     */
    private static int calculateHistoryShortageQty(
            LhScheduleContext context,
            FactoryMonthPlanProductionFinalResult plan,
            LocalDate monthStartDate,
            LocalDate historyEndDate) {
        int shortageQty = 0;
        for (LocalDate productionDate = monthStartDate;
             !productionDate.isAfter(historyEndDate);
             productionDate = productionDate.plusDays(1)) {
            int dayPlanQty = MonthPlanDateResolver.resolveDayQty(
                    context, plan.getMaterialCode(), plan.getProductStatus(), productionDate);
            int finishedQty = resolveMonthDailyFinishedQty(context, plan, productionDate);
            shortageQty += Math.max(dayPlanQty - finishedQty, 0);
        }
        return shortageQty;
    }

    /**
     * 读取单个 SKU 指定自然日的月日完成量。
     *
     * @param context 排程上下文
     * @param plan 单月月计划
     * @param productionDate 生产日期
     * @return 非负完成量
     */
    private static int resolveMonthDailyFinishedQty(
            LhScheduleContext context,
            FactoryMonthPlanProductionFinalResult plan,
            LocalDate productionDate) {
        String materialStatusKey = MonthPlanDateResolver.buildMaterialStatusKey(
                plan.getMaterialCode(), plan.getProductStatus());
        Integer finishedQty = context.getMaterialMonthDailyFinishedQtyMap()
                .get(materialStatusKey + "_" + productionDate);
        return Objects.nonNull(finishedQty) ? Math.max(finishedQty, 0) : 0;
    }

    /**
     * 构建跨月提前生产未来计划段结果。
     *
     * @param context 排程上下文
     * @param materialCode 物料编码
     * @param productStatus 产品状态
     * @param scheduleStartDate 排程窗口开始日
     * @param futurePlanDate 最早未来计划日
     * @return 未来月份计划段结果
     */
    private static CuringMonthPlanTotalResult buildFutureMonthPlanResult(
            LhScheduleContext context,
            String materialCode,
            String productStatus,
            LocalDate scheduleStartDate,
            LocalDate futurePlanDate) {
        LocalDate breakPointDate = MonthPlanDateResolver.findBreakPointDate(
                context, materialCode, productStatus, futurePlanDate);
        int futureMonthPlanTotal = MonthPlanDateResolver.sumMonthPlanQtyToDate(
                context, materialCode, productStatus, breakPointDate);
        CuringMonthPlanTotalResult result = new CuringMonthPlanTotalResult();
        result.setScheduleStartDate(scheduleStartDate);
        result.setLatestPlanDateInWindow(null);
        result.setCrossMonth(true);
        result.setBreakPointDate(breakPointDate);
        result.setCurrentMonthPlanTotal(0);
        result.setCrossMonthPlanTotal(futureMonthPlanTotal);
        result.setMonthPlanTotal(futureMonthPlanTotal);
        result.setCalculateScene(SCENE_EARLY_PRODUCTION_FUTURE_MONTH_SEGMENT);
        return result;
    }

    /**
     * 查找指定范围内最早一个原始日计划量大于 0 的日期。
     *
     * @param context 排程上下文
     * @param plan 月计划
     * @param startDate 查找开始日
     * @param endDate 查找结束日
     * @return 最早计划日；范围内无计划返回 null
     */
    private static LocalDate findFirstPositivePlanDate(
            LhScheduleContext context,
            FactoryMonthPlanProductionFinalResult plan,
            LocalDate startDate,
            LocalDate endDate) {
        return findFirstPositivePlanDate(
                context, plan.getMaterialCode(), plan.getProductStatus(), startDate, endDate);
    }

    /**
     * 按物料、产品状态查找指定范围内最早一个原始日计划量大于0的日期。
     *
     * @param context 排程上下文
     * @param materialCode 物料编码
     * @param productStatus 产品状态
     * @param startDate 查找开始日
     * @param endDate 查找结束日
     * @return 最早计划日；范围内无计划返回 null
     */
    private static LocalDate findFirstPositivePlanDate(
            LhScheduleContext context,
            String materialCode,
            String productStatus,
            LocalDate startDate,
            LocalDate endDate) {
        for (LocalDate productionDate = startDate;
             !productionDate.isAfter(endDate);
             productionDate = productionDate.plusDays(1)) {
            if (MonthPlanDateResolver.resolveDayQty(
                    context, materialCode, productStatus, productionDate) > 0) {
                return productionDate;
            }
        }
        return null;
    }

    /**
     * 读取指定未来月份的月累计完成量。
     *
     * <p>必须使用“物料+产品状态+真实年月”复合键，未加载到该月完成量时按0处理；
     * 禁止回退到当前业务月汇总，避免跨月完成量串月。</p>
     *
     * @param context 排程上下文
     * @param sku SKU
     * @param futurePlanDate 未来计划日期
     * @return 未来计划月非负完成量
     */
    private static int resolveMonthFinishedQty(
            LhScheduleContext context,
            SkuScheduleDTO sku,
            LocalDate futurePlanDate) {
        if (CollectionUtils.isEmpty(context.getMaterialMonthFinishedQtyByMonthMap())) {
            return 0;
        }
        String materialStatusKey = MonthPlanDateResolver.buildMaterialStatusKey(
                sku.getMaterialCode(), sku.getProductStatus());
        String materialMonthKey = MonthPlanDateResolver.buildMaterialMonthKey(
                materialStatusKey, futurePlanDate.getYear(), futurePlanDate.getMonthValue());
        Integer finishedQty =
                context.getMaterialMonthFinishedQtyByMonthMap().get(materialMonthKey);
        return Objects.isNull(finishedQty) ? 0 : Math.max(0, finishedQty);
    }

    /**
     * 解析未来月计划中有效的上月超欠产量。
     *
     * @param plan 未来月份月计划
     * @return 有效超欠产量，正数增加余量、负数扣减余量
     */
    private static int resolveEffectiveLastMonthOverdueQty(
            FactoryMonthPlanProductionFinalResult plan) {
        if (Objects.isNull(plan)
                || !StringUtils.equals(
                LAST_MONTH_OVERDUE_VALID_FLAG, StringUtils.trimToEmpty(plan.getLastMonthValidFlag()))
                || Objects.isNull(plan.getLastMonthOverdueQty())) {
            return 0;
        }
        return plan.getLastMonthOverdueQty();
    }

    /**
     * 判断指定计划在排程窗口至提前生产截止日内是否存在正日计划量。
     *
     * @param context 排程上下文
     * @param plan 月计划
     * @return true-存在正日计划量
     */
    private static boolean hasPositivePlanInEarlyProductionRange(
            LhScheduleContext context,
            FactoryMonthPlanProductionFinalResult plan) {
        if (Objects.isNull(context) || Objects.isNull(plan)
                || Objects.isNull(context.getScheduleDate())
                || Objects.isNull(context.getWindowEndDate())) {
            return false;
        }
        LocalDate startDate = toLocalDate(context.getScheduleDate());
        LocalDate endDate = toLocalDate(context.getWindowEndDate()).plusDays(
                EarlyProductionChecker.resolveEarlyProductionDaysThreshold(context));
        for (LocalDate productionDate = startDate;
             !productionDate.isAfter(endDate);
             productionDate = productionDate.plusDays(1)) {
            if (Objects.nonNull(plan.getYear()) && Objects.nonNull(plan.getMonth())
                    && plan.getYear() == productionDate.getYear()
                    && plan.getMonth() == productionDate.getMonthValue()
                    && MonthPlanDateResolver.resolveDayQty(
                    context, plan.getMaterialCode(), plan.getProductStatus(), productionDate) > 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断月计划是否属于指定业务日月份。
     *
     * @param plan 月计划
     * @param businessDate 业务日期
     * @return true-属于同一自然月
     */
    private static boolean belongsToBusinessMonth(
            FactoryMonthPlanProductionFinalResult plan,
            LocalDate businessDate) {
        return Objects.nonNull(plan)
                && StringUtils.isNotEmpty(plan.getMaterialCode())
                && Objects.nonNull(plan.getYear())
                && Objects.nonNull(plan.getMonth())
                && plan.getYear() == businessDate.getYear()
                && plan.getMonth() == businessDate.getMonthValue();
    }

    /**
     * 判断两个日期是否属于同一自然月。
     *
     * @param firstDate 第一个日期
     * @param secondDate 第二个日期
     * @return true-同年同月
     */
    private static boolean isSameMonth(LocalDate firstDate, LocalDate secondDate) {
        return firstDate.getYear() == secondDate.getYear()
                && firstDate.getMonthValue() == secondDate.getMonthValue();
    }

    /**
     * Date 转 LocalDate。
     *
     * @param date 日期
     * @return 本地日期
     */
    private static LocalDate toLocalDate(Date date) {
        return date.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
    }
}
