package com.zlt.aps.lh.engine.strategy.support;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.util.CollectionUtils;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * SKU 提前生产判定结果。
 * <p>用于在首个可排时间对齐和硫化排程结果备注之间传递同一次判定，避免重复计算导致口径不一致。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EarlyProductionDecision implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 普通提前生产 */
    public static final String SCENE_NORMAL = "NORMAL";
    /** 产品结构切换 */
    public static final String SCENE_STRUCTURE_SWITCH = "STRUCTURE_SWITCH";
    /** 产品结构收尾 */
    public static final String SCENE_STRUCTURE_ENDING = "STRUCTURE_ENDING";

    /** 是否属于后续日 SKU 提前生产场景 */
    private boolean earlyProduction;
    /** 是否允许进入当前业务日新增机台判断 */
    private boolean allowed;
    /** 提前生产场景 */
    private String sceneType;
    /** 后续首个日计划日期 */
    private LocalDate futurePlanDate;
    /** 排程窗口 T～T+2 的结构计划硫化机台数 */
    private List<Integer> structurePlanMachineCounts = new ArrayList<Integer>(3);
    /** 判定原因 */
    private String reason;

    /**
     * 构建非提前生产判定结果。
     *
     * @param allowed 是否保持当前逻辑继续处理
     * @param reason 判定原因
     * @return 非提前生产判定结果
     */
    public static EarlyProductionDecision notEarlyProduction(boolean allowed, String reason) {
        return new EarlyProductionDecision(false, allowed, StringUtils.EMPTY, null,
                new ArrayList<Integer>(3), reason);
    }

    /**
     * 构建提前生产判定结果。
     *
     * @param allowed 是否允许进入当前业务日新增机台判断
     * @param sceneType 提前生产场景
     * @param futurePlanDate 后续首个日计划日期
     * @param structurePlanMachineCounts T～T+2 结构计划硫化机台数
     * @param reason 判定原因
     * @return 提前生产判定结果
     */
    public static EarlyProductionDecision earlyProduction(boolean allowed,
                                                          String sceneType,
                                                          LocalDate futurePlanDate,
                                                          List<Integer> structurePlanMachineCounts,
                                                          String reason) {
        List<Integer> machineCounts = CollectionUtils.isEmpty(structurePlanMachineCounts)
                ? new ArrayList<Integer>(3) : new ArrayList<Integer>(structurePlanMachineCounts);
        return new EarlyProductionDecision(true, allowed, sceneType, futurePlanDate, machineCounts, reason);
    }

    /**
     * 按约定格式生成硫化排程结果备注片段。
     *
     * @return 允许提前生产时返回备注片段；非提前生产或准入失败时返回空字符串
     */
    public String buildRemark() {
        if (!earlyProduction || !allowed || CollectionUtils.isEmpty(structurePlanMachineCounts)) {
            return StringUtils.EMPTY;
        }
        StringBuilder remark = new StringBuilder(48);
        if (SCENE_STRUCTURE_SWITCH.equals(sceneType)) {
            remark.append("[结构切换] ");
        } else if (SCENE_STRUCTURE_ENDING.equals(sceneType)) {
            remark.append("[结构收尾] ");
        }
        remark.append("结构计划硫化机台数：");
        for (int index = 0; index < structurePlanMachineCounts.size(); index++) {
            if (index > 0) {
                remark.append(',');
            }
            remark.append(structurePlanMachineCounts.get(index));
        }
        return remark.toString();
    }
}
