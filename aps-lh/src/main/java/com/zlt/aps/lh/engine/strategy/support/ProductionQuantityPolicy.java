package com.zlt.aps.lh.engine.strategy.support;

import com.zlt.aps.lh.api.domain.dto.SkuScheduleDTO;
import com.zlt.aps.lh.api.enums.ConstructionStageEnum;
import lombok.Data;
import org.apache.commons.lang3.StringUtils;

import java.io.Serializable;

/**
 * SKU排产数量策略。
 * <p>统一判断正式、量试、试制、收尾场景是否允许补满已开班次及是否启用非最后机台满排。</p>
 *
 * @author APS
 */
@Data
public class ProductionQuantityPolicy implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 是否收尾 */
    private boolean ending;
    /** 是否试制 */
    private boolean trialProduction;
    /** 是否量试 */
    private boolean trialRun;
    /** 是否正式或按正式计划量口径处理 */
    private boolean normalProduction;
    /** 是否允许补满最后一个已开班班次 */
    private boolean allowFillStartedShift;
    /** 是否严格禁止超排 */
    private boolean strictUpperLimit;
    /** 是否启用非最后机台满排到窗口结束 */
    private boolean fullRunForNonTailMachine;

    /**
     * 根据SKU和收尾状态构建排产数量策略。
     *
     * @param sku SKU排程DTO
     * @param ending 是否收尾
     * @return 排产数量策略
     */
    public static ProductionQuantityPolicy from(SkuScheduleDTO sku, boolean ending) {
        ProductionQuantityPolicy policy = new ProductionQuantityPolicy();
        policy.setEnding(ending);
        String constructionStage = sku == null ? null : sku.getConstructionStage();
        boolean trialProduction = StringUtils.equals(ConstructionStageEnum.TRIAL.getCode(), constructionStage);
        boolean trialRun = StringUtils.equals(ConstructionStageEnum.MASS_TRIAL.getCode(), constructionStage);
        policy.setTrialProduction(trialProduction);
        policy.setTrialRun(trialRun);
        policy.setNormalProduction(!trialProduction);
        if (sku != null && sku.isStrictNewSpecShortageOnly() && !ending) {
            policy.setAllowFillStartedShift(false);
            policy.setStrictUpperLimit(true);
            policy.setFullRunForNonTailMachine(false);
            return policy;
        }
        if (ending || trialProduction) {
            policy.setAllowFillStartedShift(false);
            policy.setStrictUpperLimit(true);
            policy.setFullRunForNonTailMachine(false);
            return policy;
        }
        policy.setAllowFillStartedShift(true);
        policy.setStrictUpperLimit(false);
        policy.setFullRunForNonTailMachine(true);
        return policy;
    }
}
