package com.zlt.aps.lh.component;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zlt.aps.lh.api.domain.dto.SkuScheduleDTO;
import com.zlt.aps.lh.api.domain.entity.LhSkuDecrement;
import com.zlt.aps.lh.api.domain.entity.LhUnscheduledResult;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.mapper.LhSkuDecrementMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import javax.annotation.Resource;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * SKU减量清单过滤公共组件。
 *
 * <p>业务职责：</p>
 * <ul>
 *   <li>S4.2 批量加载排程工厂的 SKU 减量清单，按「年+月+物料编码+产品状态」四维构建索引；</li>
 *   <li>S4.3 SKU 归集完成后统一前置过滤，命中减量清单的 SKU 不进入任何排产入口；</li>
 *   <li>命中 SKU 写入未排结果（数量=月计划余量 surplusQty，备注固定文案），同一 SKU 全程只写一次；</li>
 *   <li>供续作、换活字块、新增排产等流程统一调用，禁止在多个流程重复实现不同口径。</li>
 * </ul>
 *
 * <p>匹配口径：年、月取自 SKU 所属月计划（FactoryMonthPlanProductionFinalResult.year/month），
 * 跨月排程时各 SKU 按各自月计划年月匹配，不固定使用排程 T 日所属年月。
 * 四维同时匹配视为命中，任一维度缺失或不匹配则继续现有排产流程。</p>
 *
 * @author APS
 */
@Slf4j
@Component
public class SkuDecrementChecker {

    /** 减量清单索引 key 分隔符（独立于月计划索引，避免混淆） */
    private static final String KEY_SEPARATOR = "|";

    /** 命中减量清单的未排备注，统一文案 */
    private static final String SKU_DECREMENT_UNSCHEDULED_REASON = "命中SKU减量清单，不进行排产";

    /** 自动排程数据来源（与现有未排结果口径一致：0-自动排程） */
    private static final String DATA_SOURCE_AUTO = "0";

    /** 未删除标识（与现有未排结果口径一致：0-未删除） */
    private static final int DELETE_FLAG_NORMAL = 0;

    @Resource
    private LhSkuDecrementMapper skuDecrementMapper;

    @Resource
    private TargetScheduleQtyResolver targetScheduleQtyResolver;

    /**
     * 批量加载排程工厂的 SKU 减量清单并构建索引写入上下文。
     *
     * <p>按工厂编号查询全部减量清单（单表查询，复用 MyBatis-Plus BaseMapper，不写 XML），
     * 内存按四维归一化构建 Set 索引。索引 key 含 year+month，SKU 按各自月计划年月匹配，
     * 不会误命中其他月份，因此无需按排程窗口年月裁剪加载范围。</p>
     *
     * @param context 排程上下文
     */
    public void loadAndAttachDecrementIndex(LhScheduleContext context) {
        if (Objects.isNull(context) || StringUtils.isEmpty(context.getFactoryCode())) {
            return;
        }
        // 按工厂编号查全部减量清单，避免逐个 SKU 查询数据库
        LambdaQueryWrapper<LhSkuDecrement> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(LhSkuDecrement::getFactoryCode, context.getFactoryCode());
        List<LhSkuDecrement> decrementList = skuDecrementMapper.selectList(queryWrapper);

        Set<String> indexSet = new HashSet<>(decrementList.size() * 2);
        for (LhSkuDecrement decrement : decrementList) {
            if (Objects.isNull(decrement)
                    || Objects.isNull(decrement.getYear()) || Objects.isNull(decrement.getMonth())
                    || StringUtils.isEmpty(decrement.getMaterialCode())
                    || StringUtils.isEmpty(decrement.getProductStatus())) {
                // 四维任一缺失为无效记录，跳过索引构建并告警，便于数据治理
                log.warn("SKU减量清单存在无效记录，跳过索引构建, 工厂: {}, 年: {}, 月: {}, 物料: {}, 产品状态: {}",
                        context.getFactoryCode(),
                        Objects.isNull(decrement) ? null : decrement.getYear(),
                        Objects.isNull(decrement) ? null : decrement.getMonth(),
                        Objects.isNull(decrement) ? null : decrement.getMaterialCode(),
                        Objects.isNull(decrement) ? null : decrement.getProductStatus());
                continue;
            }
            indexSet.add(buildKey(decrement.getYear(), decrement.getMonth(),
                    decrement.getMaterialCode(), decrement.getProductStatus()));
        }
        context.setSkuDecrementKeySet(indexSet);
        log.info("SKU减量清单批量加载完成, 工厂: {}, 原始记录数: {}, 有效索引数: {}",
                context.getFactoryCode(), decrementList.size(), indexSet.size());
    }

    /**
     * 判断 SKU 是否命中减量清单。
     *
     * <p>用 SKU 所属月计划年月 + 物料编码 + 产品状态拼 key 查索引。
     * SKU 月计划年月或匹配维度缺失时视为未命中，继续现有排产流程。</p>
     *
     * @param context 排程上下文
     * @param sku     SKU排程DTO
     * @return true-命中（不排产），false-未命中（继续现有排产流程）
     */
    public boolean isDecrementHit(LhScheduleContext context, SkuScheduleDTO sku) {
        if (Objects.isNull(context) || Objects.isNull(sku)
                || CollectionUtils.isEmpty(context.getSkuDecrementKeySet())
                || Objects.isNull(sku.getMonthPlanYear()) || Objects.isNull(sku.getMonthPlanMonth())
                || StringUtils.isEmpty(sku.getMaterialCode())
                || StringUtils.isEmpty(sku.getProductStatus())) {
            return false;
        }
        String key = buildKey(sku.getMonthPlanYear(), sku.getMonthPlanMonth(),
                sku.getMaterialCode(), sku.getProductStatus());
        return context.getSkuDecrementKeySet().contains(key);
    }

    /**
     * S4.3 归集完成后统一前置过滤续作/新增SKU列表。
     *
     * <p>续作SKU和新增SKU均流经 continuousSkuList/newSpecSkuList 两个列表，统一前置过滤可覆盖
     * 续作排产、新增排产、换活字块候选、提前生产、续作加机台补偿等全部入口。
     * 命中减量清单的 SKU 写未排结果、从排产列表移除，并清理结构索引和胎胚活跃集合，
     * 确保不占用机台、模具、胶囊、换模次数、首检数量及班次产能。</p>
     *
     * @param context 排程上下文
     */
    public void filterDecrementSkus(LhScheduleContext context) {
        if (Objects.isNull(context) || CollectionUtils.isEmpty(context.getSkuDecrementKeySet())) {
            return;
        }
        // 续作列表与新增列表分别过滤，命中SKU统一写未排并移出排产集合
        int continuousHitCount = filterSkuList(context, context.getContinuousSkuList());
        int newSpecHitCount = filterSkuList(context, context.getNewSpecSkuList());
        if (continuousHitCount > 0 || newSpecHitCount > 0) {
            log.info("SKU减量清单前置过滤完成, 工厂: {}, 批次号: {}, 命中续作SKU: {}, 命中新增SKU: {}",
                    context.getFactoryCode(), context.getBatchNo(), continuousHitCount, newSpecHitCount);
        }
    }

    /**
     * 处理命中减量清单的SKU：写未排结果（去重），返回是否本次实际写入。
     *
     * <p>去重 key 为「物料编码+产品状态+月计划年月」，保证同一 SKU 在多个流程被识别时
     * 未排结果只生成一次。供 S4.3 前置过滤和 S4.5 新增主循环兜底统一调用。</p>
     *
     * @param context 排程上下文
     * @param sku     SKU排程DTO
     * @return true-本次实际写入未排结果，false-已去重跳过
     */
    public boolean handleDecrementHit(LhScheduleContext context, SkuScheduleDTO sku) {
        if (Objects.isNull(context) || Objects.isNull(sku)
                || StringUtils.isEmpty(sku.getMaterialCode())) {
            return false;
        }
        // 去重：同一SKU（物料+产品状态+月计划年月）已处理过则不再写入未排结果
        String handledKey = buildKey(sku.getMonthPlanYear(), sku.getMonthPlanMonth(),
                sku.getMaterialCode(), sku.getProductStatus());
        if (!context.getDecrementHandledSkuKeySet().add(handledKey)) {
            return false;
        }
        LhUnscheduledResult unscheduled = new LhUnscheduledResult();
        unscheduled.setFactoryCode(context.getFactoryCode());
        unscheduled.setBatchNo(context.getBatchNo());
        unscheduled.setScheduleDate(context.getScheduleTargetDate());
        unscheduled.setMonthPlanVersion(sku.getMonthPlanVersion());
        unscheduled.setProductionVersion(sku.getProductionVersion());
        unscheduled.setMaterialCode(sku.getMaterialCode());
        unscheduled.setProductStatus(sku.getProductStatus());
        unscheduled.setMaterialDesc(sku.getMaterialDesc());
        unscheduled.setStructureName(sku.getStructureName());
        unscheduled.setMainMaterialDesc(sku.getMainMaterialDesc());
        unscheduled.setSpecCode(sku.getSpecCode());
        unscheduled.setEmbryoCode(sku.getEmbryoCode());
        unscheduled.setMouldQty(sku.getMouldQty());
        // 未排数量取月计划余量，体现该SKU本月目标量全部未排
        unscheduled.setUnscheduledQty(Math.max(0, sku.getSurplusQty()));
        unscheduled.setUnscheduledReason(SKU_DECREMENT_UNSCHEDULED_REASON);
        unscheduled.setDataSource(DATA_SOURCE_AUTO);
        unscheduled.setIsDelete(DELETE_FLAG_NORMAL);
        context.getUnscheduledResultList().add(unscheduled);
        return true;
    }

    /**
     * 过滤单个SKU列表，命中减量清单的SKU写未排、移除并清理后置索引。
     *
     * @param context 排程上下文
     * @param skuList SKU列表（续作或新增）
     * @return 本次命中并实际写入未排结果的SKU数量
     */
    private int filterSkuList(LhScheduleContext context, List<SkuScheduleDTO> skuList) {
        if (CollectionUtils.isEmpty(skuList)) {
            return 0;
        }
        int hitCount = 0;
        Iterator<SkuScheduleDTO> iterator = skuList.iterator();
        while (iterator.hasNext()) {
            SkuScheduleDTO sku = iterator.next();
            if (Objects.isNull(sku) || !isDecrementHit(context, sku)) {
                continue;
            }
            // 命中减量清单：写未排结果（去重）、从排产列表移除
            boolean written = handleDecrementHit(context, sku);
            if (written) {
                hitCount++;
            }
            iterator.remove();
            // 从结构SKU集合和全量索引移除，避免换活字块、置换等后置阶段重新选入
            context.removePendingSkuFromStructureMap(sku);
            context.getAllSkuScheduleDtoMap().remove(
                    MonthPlanDateResolver.buildMaterialStatusKey(
                            sku.getMaterialCode(), sku.getProductStatus()));
            // 命中后不再占用胎胚库存内部分摊额度，剩余额度回流给同胎胚有效SKU
            targetScheduleQtyResolver.removeActiveEmbryoSku(context, sku, SKU_DECREMENT_UNSCHEDULED_REASON);
            log.info("SKU命中减量清单，排产准入拦截, 工厂: {}, 批次号: {}, 物料: {}, 产品状态: {}, 月计划年月: {}-{}, 未排数量: {}",
                    context.getFactoryCode(), context.getBatchNo(), sku.getMaterialCode(),
                    sku.getProductStatus(), sku.getMonthPlanYear(), sku.getMonthPlanMonth(),
                    Math.max(0, sku.getSurplusQty()));
        }
        return hitCount;
    }

    /**
     * 构建减量清单四维索引 key（归一化：trim 后转小写）。
     *
     * @param year          年
     * @param month         月
     * @param materialCode  物料编码
     * @param productStatus 产品状态
     * @return 归一化后的索引 key
     */
    private String buildKey(Object year, Object month, Object materialCode, Object productStatus) {
        return normalize(year) + KEY_SEPARATOR + normalize(month) + KEY_SEPARATOR
                + normalize(materialCode) + KEY_SEPARATOR + normalize(productStatus);
    }

    /**
     * 归一化取值：null 转空串，其余 trim 后转小写，消除前后空格和大小写差异。
     *
     * @param value 原始值
     * @return 归一化字符串
     */
    private String normalize(Object value) {
        if (Objects.isNull(value)) {
            return "";
        }
        return value.toString().trim().toLowerCase();
    }
}
