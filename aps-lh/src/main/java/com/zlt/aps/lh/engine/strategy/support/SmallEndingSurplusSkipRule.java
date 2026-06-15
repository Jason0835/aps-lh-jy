package com.zlt.aps.lh.engine.strategy.support;

import com.zlt.aps.lh.api.constant.LhScheduleConstant;
import com.zlt.aps.lh.api.domain.dto.SkuScheduleDTO;
import com.zlt.aps.lh.api.domain.entity.LhScheduleResult;
import com.zlt.aps.lh.api.domain.vo.LhShiftConfigVO;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.util.LhScheduleTimeUtil;
import com.zlt.aps.lh.util.ShiftFieldUtil;
import org.apache.commons.lang3.StringUtils;
import org.springframework.util.CollectionUtils;

import java.util.List;
import java.util.Objects;

/**
 * 收尾小余量不排产规则。
 *
     * <p>规则口径：收尾 SKU 的硫化余量小于等于允许欠产偏差值，且业务目标日前一日排程结果中
     * T+1 日夜班未排满时，本次不排产并进入未排结果。该口径不复用强制重排下的窗口 T 日前一日滚动基线。</p>
 *
 * @author APS
 */
public final class SmallEndingSurplusSkipRule {

    /** 收尾小余量且前日 T+1 夜班未排满的统一未排原因 */
    public static final String UNSCHEDULED_REASON =
            "收尾余量小于等于允许欠产偏差值，且前日 T+1 夜班未排满，本次不排产";

    private static final int T1_DATE_OFFSET = 1;

    private SmallEndingSurplusSkipRule() {
    }

    /**
     * 判断 SKU 是否命中收尾小余量不排产规则。
     *
     * @param context 排程上下文
     * @param sku SKU
     * @param isEnding 是否收尾
     * @return true-命中规则，本次不排产；false-继续按原排程逻辑处理
     */
    public static boolean shouldSkip(LhScheduleContext context, SkuScheduleDTO sku, boolean isEnding) {
        if (!isEnding || Objects.isNull(sku)) {
            return false;
        }
        int surplusQty = Math.max(0, sku.getSurplusQty());
        int toleranceQty = resolveToleranceQty(context);
        if (surplusQty > toleranceQty) {
            return false;
        }
        // 前日 T+1 夜班未排满是本规则的必要条件，已排满时仍按原收尾排产规则继续。
        return !isTargetPreviousT1NightFull(context, sku);
    }

    /**
     * 解析收尾小余量允许欠产偏差值。
     *
     * @param context 排程上下文
     * @return 允许不排产的最大收尾余量
     */
    public static int resolveToleranceQty(LhScheduleContext context) {
        if (Objects.isNull(context) || Objects.isNull(context.getScheduleConfig())) {
            return LhScheduleConstant.CONTINUOUS_ENDING_SURPLUS_TOLERANCE_QTY;
        }
        return context.getScheduleConfig().getContinuousEndingSurplusToleranceQty();
    }

    /**
     * 判断业务目标日前一日排程结果中该 SKU 的 T+1 日夜班是否已排满。
     *
     * @param context 排程上下文
     * @param sku SKU
     * @return true-业务目标日前一日 T+1 夜班排产量大于等于班产；false-无业务目标日前一日数据或未排满
     */
    public static boolean isTargetPreviousT1NightFull(LhScheduleContext context, SkuScheduleDTO sku) {
        if (Objects.isNull(sku) || sku.getShiftCapacity() <= 0) {
            return false;
        }
        return resolveTargetPreviousT1NightPlanQty(context, sku.getMaterialCode()) >= sku.getShiftCapacity();
    }

    /**
     * 汇总业务目标日前一日排程结果中指定物料的 T+1 日夜班排产量。
     *
     * @param context 排程上下文
     * @param materialCode 物料编码
     * @return 业务目标日前一日 T+1 夜班排产量，无数据时返回0
     */
    public static int resolveTargetPreviousT1NightPlanQty(LhScheduleContext context, String materialCode) {
        if (Objects.isNull(context) || StringUtils.isEmpty(materialCode)
                || CollectionUtils.isEmpty(context.getTargetPreviousScheduleResultList())) {
            return 0;
        }
        Integer nightShiftIndex = resolveT1NightShiftIndex(context);
        if (Objects.isNull(nightShiftIndex)) {
            return 0;
        }
        int totalQty = 0;
        for (LhScheduleResult result : context.getTargetPreviousScheduleResultList()) {
            if (Objects.isNull(result) || !StringUtils.equals(materialCode, result.getMaterialCode())) {
                continue;
            }
            Integer shiftPlanQty = ShiftFieldUtil.getShiftPlanQty(result, nightShiftIndex);
            totalQty += Objects.isNull(shiftPlanQty) ? 0 : Math.max(0, shiftPlanQty);
        }
        return totalQty;
    }

    /**
     * 解析当前排程模板中 T+1 日夜班对应的班次索引。
     *
     * @param context 排程上下文
     * @return T+1 夜班索引，未配置时返回 null
     */
    private static Integer resolveT1NightShiftIndex(LhScheduleContext context) {
        List<LhShiftConfigVO> shifts = context.getScheduleWindowShifts();
        if (CollectionUtils.isEmpty(shifts)) {
            shifts = LhScheduleTimeUtil.getScheduleShifts(context, context.getScheduleDate());
        }
        return LhScheduleTimeUtil.findFirstNightShiftIndexWithOffset(shifts, T1_DATE_OFFSET);
    }
}
