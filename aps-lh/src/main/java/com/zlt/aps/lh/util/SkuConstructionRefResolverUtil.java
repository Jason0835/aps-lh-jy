package com.zlt.aps.lh.util;

import com.zlt.aps.lh.api.enums.TrialStatusEnum;
import com.zlt.aps.mdm.api.domain.entity.MdmSkuConstructionRef;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.util.CollectionUtils;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * SKU与示方书关系降级匹配工具。
 * <p>按产品状态提供硫化示方书号降级查找能力，供校验器和排程结果赋值统一调用，
 * 保证两侧使用同一套匹配规则。</p>
 *
 * <p>降级规则：</p>
 * <ul>
 *   <li>正规(S) → 量试(T) → 试制(X)</li>
 *   <li>量试(T) → 试制(X)，不允许反向匹配正规</li>
 *   <li>试制(X) 只匹配试制，不允许降级或反向匹配</li>
 * </ul>
 *
 * @author APS
 */
@Slf4j
public final class SkuConstructionRefResolverUtil {

    private SkuConstructionRefResolverUtil() {
    }

    /**
     * 降级匹配SKU与示方书关系。
     * <p>精确匹配优先，找到且 lhNo 非空则直接返回；
     * lhNo 为空或找不到时按产品状态降级链继续查找。</p>
     *
     * @param materialCode    物料编码
     * @param productStatus   产品状态（S-正规、T-量试、X-试制）
     * @param compositeKeyMap SKU与示方书关系复合key Map，key=materialCode::trialStatus
     * @return 匹配到的关系对象，未匹配返回 null
     */
    public static MdmSkuConstructionRef resolveCuringRecipeRef(
            String materialCode,
            String productStatus,
            Map<String, MdmSkuConstructionRef> compositeKeyMap) {
        if (StringUtils.isEmpty(materialCode) || StringUtils.isEmpty(productStatus)
                || CollectionUtils.isEmpty(compositeKeyMap)) {
            return null;
        }
        // 精确匹配
        MdmSkuConstructionRef exactRef = compositeKeyMap.get(materialCode + "::" + productStatus);
        if (exactRef != null && StringUtils.isNotEmpty(exactRef.getLhNo())) {
            log.debug("硫化示方书精确匹配, 物料编码: {}, 产品状态: {}",
                    materialCode, resolveProductStatusDesc(productStatus));
            return exactRef;
        }
        // 按产品状态获取降级链
        List<String> fallbackStatuses = getFallbackStatuses(productStatus);
        for (String fallbackStatus : fallbackStatuses) {
            MdmSkuConstructionRef fallbackRef = compositeKeyMap.get(materialCode + "::" + fallbackStatus);
            if (fallbackRef != null && StringUtils.isNotEmpty(fallbackRef.getLhNo())) {
                log.info("硫化示方书降级匹配, 物料编码: {}, 原始产品状态: {}, 降级到: {}",
                        materialCode, resolveProductStatusDesc(productStatus),
                        resolveProductStatusDesc(fallbackStatus));
                return fallbackRef;
            }
        }
        log.info("硫化示方书未匹配, 物料编码: {}, 产品状态: {}",
                materialCode, resolveProductStatusDesc(productStatus));
        return null;
    }

    /**
     * 将产品状态编码转换为中文描述。
     *
     * @param productStatus 产品状态编码（S/T/X）
     * @return 中文描述，未知状态返回原值
     */
    public static String resolveProductStatusDesc(String productStatus) {
        if (StringUtils.isEmpty(productStatus)) {
            return productStatus;
        }
        TrialStatusEnum status = TrialStatusEnum.getByCode(productStatus);
        if (status == null) {
            return productStatus;
        }
        switch (status) {
            case FORMAL:
                return "正规";
            case MASS_TRIAL:
                return "量试";
            case TRIAL:
                return "试制";
            default:
                return productStatus;
        }
    }

    /**
     * 按产品状态获取降级状态链。
     *
     * @param productStatus 产品状态编码
     * @return 降级状态列表，无降级时返回空列表
     */
    private static List<String> getFallbackStatuses(String productStatus) {
        TrialStatusEnum status = TrialStatusEnum.getByCode(productStatus);
        if (status == null) {
            return Collections.emptyList();
        }
        switch (status) {
            // 正规 → 量试 → 试制
            case FORMAL:
                return java.util.Arrays.asList(
                        TrialStatusEnum.MASS_TRIAL.getCode(),
                        TrialStatusEnum.TRIAL.getCode());
            // 量试 → 试制，不允许反向匹配正规
            case MASS_TRIAL:
                return Collections.singletonList(TrialStatusEnum.TRIAL.getCode());
            // 试制：无降级，不允许反向匹配
            case TRIAL:
            default:
                return Collections.emptyList();
        }
    }
}
