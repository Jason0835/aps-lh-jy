package com.zlt.aps.lh.component;

import com.zlt.aps.lh.api.domain.dto.SkuScheduleDTO;
import com.zlt.aps.lh.api.enums.ScheduleStepEnum;
import com.zlt.aps.lh.api.enums.SingleControlMachineModeEnum;
import com.zlt.aps.lh.api.enums.TrialStatusEnum;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.engine.strategy.IMachineMatchStrategy;
import com.zlt.aps.lh.exception.ScheduleErrorCode;
import com.zlt.aps.lh.exception.ScheduleException;
import com.zlt.aps.lh.util.LhSingleControlMachineUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 单控机台模式快照初始化器。
 *
 * <p>在 S4.3 完成续作/新增分类后执行一次，冻结 SKU 进入本次排程时的目标量和单模/双模模式。
 * 后续排产会持续修改待排量、目标量和待排列表，因此所有策略只能读取该快照，不能重新统计。</p>
 *
 * @author APS
 */
@Slf4j
@Component
public class SingleControlModeSnapshotInitializer {

    /** 试验SKU强制单模的不同SKU数量阈值 */
    private static final int TRIAL_SINGLE_SIDE_THRESHOLD = 3;
    /** 通用单模最大初始目标量 */
    private static final int SINGLE_SIDE_MAX_TARGET_QTY = 4;

    @Resource
    private IMachineMatchStrategy machineMatchStrategy;

    /**
     * 初始化本次排程的单控模式快照。
     *
     * @param context 排程上下文
     */
    public void initialize(LhScheduleContext context) {
        if (Objects.isNull(context) || context.isSingleControlModeSnapshotInitialized()) {
            return;
        }
        List<SkuScheduleDTO> scheduleSkuList = collectScheduleSkus(context);
        Map<String, SkuScheduleDTO> uniqueSkuMap = new LinkedHashMap<String, SkuScheduleDTO>(scheduleSkuList.size());
        Map<String, Integer> initialTargetQtyMap = context.getSingleControlInitialTargetQtyMap();
        for (SkuScheduleDTO sku : scheduleSkuList) {
            if (Objects.isNull(sku) || StringUtils.isEmpty(sku.getMaterialCode())) {
                continue;
            }
            String skuKey = LhSingleControlMachineUtil.buildSkuModeKey(sku);
            uniqueSkuMap.putIfAbsent(skuKey, sku);
            // 满排模式下 targetScheduleQty 可能是窗口理论产能，并不等于本轮真实可消费量。
            // 单控模式必须冻结 SKU 进入排产链路时已经初始化的实际消费账本；只有严格目标量、
            // 停产收尾这类明确业务目标才继续采用 targetScheduleQty，避免 3 条正规 SKU 被理论产能误判为双模。
            initialTargetQtyMap.putIfAbsent(skuKey, resolveInitialTargetQty(context, sku));
        }

        Set<String> eligibleTrialSkuKeySet = new LinkedHashSet<String>(4);
        for (Map.Entry<String, SkuScheduleDTO> entry : uniqueSkuMap.entrySet()) {
            SkuScheduleDTO sku = entry.getValue();
            int initialTargetQty = initialTargetQtyMap.getOrDefault(entry.getKey(), 0);
            if (initialTargetQty > 0
                    && StringUtils.equals(TrialStatusEnum.TRIAL.getCode(), sku.getProductStatus())
                    && machineMatchStrategy.hasEligibleSingleControlSide(context, sku)) {
                eligibleTrialSkuKeySet.add(entry.getKey());
            }
        }
        context.getSingleControlEligibleTrialSkuKeySet().addAll(eligibleTrialSkuKeySet);

        boolean forceTrialSingleSide = eligibleTrialSkuKeySet.size() >= TRIAL_SINGLE_SIDE_THRESHOLD;
        for (Map.Entry<String, SkuScheduleDTO> entry : uniqueSkuMap.entrySet()) {
            SkuScheduleDTO sku = entry.getValue();
            int initialTargetQty = initialTargetQtyMap.getOrDefault(entry.getKey(), 0);
            boolean trialSingleSide = forceTrialSingleSide
                    && StringUtils.equals(TrialStatusEnum.TRIAL.getCode(), sku.getProductStatus());
            SingleControlMachineModeEnum mode = trialSingleSide || initialTargetQty <= SINGLE_SIDE_MAX_TARGET_QTY
                    ? SingleControlMachineModeEnum.SINGLE_SIDE
                    : SingleControlMachineModeEnum.WHOLE_PAIR;
            context.getSingleControlModeSnapshotMap().put(entry.getKey(), mode);
            log.info("单控机台模式冻结, factoryCode: {}, batchNo: {}, materialCode: {}, productStatus: {}, "
                            + "initialTargetQty: {}, eligibleTrialSkuCount: {}, mode: {}, rule: {}",
                    context.getFactoryCode(), context.getBatchNo(), sku.getMaterialCode(), sku.getProductStatus(),
                    initialTargetQty, eligibleTrialSkuKeySet.size(), mode.getDescription(),
                    trialSingleSide ? "不同试验SKU数量不少于3" : "初始目标量4条边界");
        }
        context.setSingleControlModeSnapshotInitialized(true);
        log.info("单控机台模式快照初始化完成, factoryCode: {}, batchNo: {}, skuCount: {}, eligibleTrialSkuCount: {}",
                context.getFactoryCode(), context.getBatchNo(), uniqueSkuMap.size(), eligibleTrialSkuKeySet.size());
    }

    /**
     * 汇总续作和新增SKU，保持原列表顺序并允许同物料多个续作侧共享同一模式键。
     *
     * @param context 排程上下文
     * @return 本次排程待处理SKU列表
     */
    private List<SkuScheduleDTO> collectScheduleSkus(LhScheduleContext context) {
        int initialCapacity = context.getContinuousSkuList().size() + context.getNewSpecSkuList().size();
        List<SkuScheduleDTO> result = new ArrayList<SkuScheduleDTO>(Math.max(initialCapacity, 4));
        if (!CollectionUtils.isEmpty(context.getContinuousSkuList())) {
            result.addAll(context.getContinuousSkuList());
        }
        if (!CollectionUtils.isEmpty(context.getNewSpecSkuList())) {
            result.addAll(context.getNewSpecSkuList());
        }
        return result;
    }

    /**
     * 解析单控模式冻结使用的本轮初始待排量。
     * <p>优先使用进入排产链路时的实际硫化余量（surplusQty），不使用代表窗口理论产能的
     * targetScheduleQty。试制 SKU 的 strictTargetQty=true 会使实际消费账本被初始化为
     * targetScheduleQty（理论窗口产能），但模式冻结只关心实际待排量，不关心满排理论产能。
     * 只有在 surplusQty 为 0 的特殊场景（停产收尾、胎胚库存收尾等）才回退到严格目标量。</p>
     *
     * @param context 排程上下文
     * @param sku 待冻结SKU
     * @return 实际硫化余量或特殊场景下的严格业务目标量
     */
    private int resolveInitialTargetQty(LhScheduleContext context, SkuScheduleDTO sku) {
        // 优先使用实际硫化余量，避免满排模式下理论窗口产能覆盖实际待排量
        int surplusQty = Math.max(0, sku.getSurplusQty());
        if (surplusQty > 0) {
            return surplusQty;
        }
        // surplusQty 为 0 时，可能是停产收尾或胎胚库存收尾等特殊场景，
        // 此时 targetScheduleQty 已经被上游设置为明确业务目标量，可以使用。
        if (sku.isStrictTargetQty() || context.isStopProductionMode()) {
            return Math.max(0, sku.resolveTargetScheduleQty());
        }
        Integer productionRemainingQty = context.getSkuProductionRemainingQtyMap().get(sku.getMaterialCode());
        if (Objects.nonNull(productionRemainingQty)) {
            return Math.max(0, productionRemainingQty);
        }
        // S4.3 正式主链必须在快照初始化前建立实际消费账本。账本缺失时无法可靠判断模式，
        // 必须阻断本批排程，禁止退回理论目标量或0条继续排产，否则仍可能生成错误的单模/双模结果。
        throw new ScheduleException(ScheduleStepEnum.S4_3_ADJUST_AND_GATHER,
                ScheduleErrorCode.SURPLUS_CALCULATION_ERROR,
                context.getFactoryCode(), context.getBatchNo(),
                "单控模式冻结失败，SKU实际消费账本缺失，materialCode=" + sku.getMaterialCode());
    }
}
