package com.zlt.aps.lh.engine.strategy.support;

import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 特殊材料硫化机置换成功记录。
 *
 * <p>该记录只在单次排程上下文中使用，不落库。S4.5.1 在特殊材料 SKU 成功接管续作机台后，
 * 按最终实际换模结果写入本记录；S4.6 生成模具交替计划时再按机台、接管 SKU、产品状态和
 * 实际换模开始时间精确匹配，避免同一机台存在多条换模计划时把置换备注追加到错误记录。</p>
 *
 * @author APS
 */
@Data
public class SpecialMaterialSubstitutionRecord implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 被置换续作机台编码 */
    private String machineCode;
    /** 被提前下机的原续作 SKU 物料编码 */
    private String replacedMaterialCode;
    /** 接管机台的特殊材料 SKU 物料编码 */
    private String specialMaterialCode;
    /** 特殊材料 SKU 产品状态，用于同物料多状态精确隔离 */
    private String specialProductStatus;
    /** 置换命中类型 */
    private String substitutionType;
    /** 最终置换备注，格式为“{命中类型}+置换 {机台编码}” */
    private String remark;
    /** 原续作 SKU 最终实际下机时间 */
    private Date actualOfflineTime;
    /** 特殊材料 SKU 最终实际换模或换活字块开始时间 */
    private Date actualChangeStartTime;
    /** 特殊材料 SKU 最终实际换模或换活字块完成时间 */
    private Date actualChangeEndTime;
}
