/**
 * Copyright (c) 2008, 智立通（厦门）科技有限公司 All rights reserved。
 */
package com.zlt.aps.lh.api.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 特殊材料硫化机置换类型枚举。
 *
 * <p>用于特殊材料 SKU 置换机台时，按以下优先级逐层匹配被置换机台：</p>
 * <ol>
 *   <li>喷砂清洗：排程日期起3天内存在喷砂清洗计划的机台优先被置换，喷砂时间越近越优先；</li>
 *   <li>月计划降模：2天内存在月计划降模需求的机台优先被置换，降模时间越近越优先；</li>
 *   <li>精度计划：30天内存在精度保养计划的机台优先被置换，精度计划时间越近越优先；</li>
 *   <li>胎胚库存低：以上均未命中时，按被置换 SKU 对应胎胚库存判断，库存越低越优先被置换。</li>
 * </ol>
 *
 * @author APS
 */
@Getter
@AllArgsConstructor
public enum SubstitutionTypeEnum {

    /** 喷砂清洗置换：3天内有喷砂清洗计划的机台优先被置换 */
    SAND_BLAST_SUBSTITUTION("喷砂+置换", "喷砂清洗置换"),
    /** 月计划降模置换：2天内有月计划降模需求的机台优先被置换 */
    MONTH_PLAN_REDUCE_SUBSTITUTION("月计划降模+置换", "月计划降模置换"),
    /** 精度计划置换：30天内有精度保养计划的机台优先被置换 */
    PRECISION_PLAN_SUBSTITUTION("精度计划+置换", "精度计划置换"),
    /** 胎胚库存低置换：胎胚库存最低的机台优先被置换 */
    LOW_EMBRYO_STOCK_SUBSTITUTION("胎胚库存低+置换", "胎胚库存低置换");

    /** 备注前缀，用于模具交替计划备注 */
    private final String remarkPrefix;
    /** 置换类型描述 */
    private final String description;

    /**
     * 构建模具交替计划备注。
     *
     * <p>备注格式：{置换类型前缀} {机台编码}，被置换SKU：{物料编码}</p>
     *
     * @param machineCode 被置换机台编码
     * @param replacedMaterialCode 被置换SKU物料编码
     * @return 完整备注文本
     */
    public String buildRemark(String machineCode, String replacedMaterialCode) {
        return remarkPrefix + " " + machineCode + "，被置换SKU：" + replacedMaterialCode;
    }
}
