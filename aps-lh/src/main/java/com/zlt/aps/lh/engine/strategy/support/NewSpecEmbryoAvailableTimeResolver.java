package com.zlt.aps.lh.engine.strategy.support;

import com.zlt.aps.lh.api.domain.dto.SkuScheduleDTO;
import com.zlt.aps.lh.api.domain.vo.LhShiftConfigVO;
import com.zlt.aps.lh.context.LhScheduleContext;
import org.apache.commons.lang3.StringUtils;
import org.springframework.util.CollectionUtils;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * S4.5 新增排产胎胚最早可供硫化时间解析器。
 *
 * <p>本解析器只提供新增排产需要的时间下限和半开班次定位能力，不修改换模、
 * 换活字块、续作或公共排程接口。调用方只有在结构命中有效配置时才启用新口径，
 * 未命中配置时必须继续执行原有逻辑。</p>
 *
 * @author APS
 */
public final class NewSpecEmbryoAvailableTimeResolver {

    /** 胎胚时间超出三天排程窗口时的统一未排原因 */
    public static final String OUT_OF_SCHEDULE_WINDOW_REASON = "胎胚最早可供硫化时间超出排程窗口";

    /** 当前业务日尚未到达胎胚时间时的延期原因 */
    public static final String NOT_AVAILABLE_IN_CURRENT_DAY_REASON = "胎胚最早可供硫化时间尚未到达当前业务日";

    private NewSpecEmbryoAvailableTimeResolver() {
    }

    /**
     * 按 SKU 结构名称获取胎胚最早可供硫化时间。
     *
     * <p>结构名称直接作为 Map 精确键，不做 trim 或大小写转换，保证与配置表
     * STRUCTURE_NAME 的大小写敏感匹配口径一致。</p>
     *
     * @param context 排程上下文
     * @param sku 待排新增 SKU
     * @return 命中有效配置时返回最早可供时间，否则返回 null
     */
    public static Date resolveEarliestAvailableTime(LhScheduleContext context, SkuScheduleDTO sku) {
        if (Objects.isNull(context) || Objects.isNull(sku)
                || StringUtils.isEmpty(sku.getStructureName())) {
            return null;
        }
        Map<String, Date> earliestTimeMap = context.getStructureEarliestLhTimeMap();
        if (CollectionUtils.isEmpty(earliestTimeMap)) {
            return null;
        }
        return earliestTimeMap.get(sku.getStructureName());
    }

    /**
     * 判断当前 SKU 是否命中有效胎胚时间配置。
     *
     * @param context 排程上下文
     * @param sku 待排新增 SKU
     * @return true-命中有效时间配置；false-保持原排程行为
     */
    public static boolean isConstrained(LhScheduleContext context, SkuScheduleDTO sku) {
        return Objects.nonNull(resolveEarliestAvailableTime(context, sku));
    }

    /**
     * 计算胎胚约束后的生产开始时间。
     *
     * @param theoreticalProductionStartTime 现有规则计算出的理论可开产时间
     * @param earliestAvailableTime 胎胚最早可供硫化时间
     * @return 两个时间中的较晚值；任一为空时返回另一有效时间
     */
    public static Date resolveActualProductionStartTime(Date theoreticalProductionStartTime,
                                                        Date earliestAvailableTime) {
        if (Objects.isNull(theoreticalProductionStartTime)) {
            return earliestAvailableTime;
        }
        if (Objects.isNull(earliestAvailableTime)
                || theoreticalProductionStartTime.after(earliestAvailableTime)) {
            return theoreticalProductionStartTime;
        }
        return earliestAvailableTime;
    }

    /**
     * 计算命中胎胚约束后当前班次真正允许生产的开始时间。
     *
     * <p>班次管控窗口只说明该班次允许生产的边界，不能覆盖实际生产时间下限。
     * 因此，命中胎胚约束时必须在班次管控开始时间和实际生产开始时间之间取较晚值；
     * 若较晚值已到达班次管控结束时间，则该班次没有任何可用于首检或正式生产的时间。</p>
     *
     * @param controlStartTime 班次管控后的可生产开始时间
     * @param controlEndTime 班次管控后的可生产结束时间
     * @param actualProductionStartTime 胎胚约束及既有规则共同确定的实际生产开始时间
     * @return 当前班次有效生产开始时间；当前班次无有效窗口时返回 null
     */
    public static Date resolveEffectiveProductionWindowStart(Date controlStartTime,
                                                              Date controlEndTime,
                                                              Date actualProductionStartTime) {
        if (Objects.isNull(controlStartTime) || Objects.isNull(controlEndTime)
                || !controlStartTime.before(controlEndTime)) {
            return null;
        }
        Date effectiveStartTime = Objects.nonNull(actualProductionStartTime)
                && actualProductionStartTime.after(controlStartTime)
                ? actualProductionStartTime : controlStartTime;
        return effectiveStartTime.before(controlEndTime) ? effectiveStartTime : null;
    }

    /**
     * 计算有效生产时间窗的秒数。
     *
     * <p>部分班次产能折算必须使用实际参与生产的时间窗长度，而不是完整班次长度；
     * 该方法仅为命中胎胚约束的 S4.5 调用方提供统一口径。</p>
     *
     * @param windowStartTime 有效生产开始时间
     * @param windowEndTime 有效生产结束时间
     * @return 有效生产秒数；窗口无效时返回 0
     */
    public static long resolveProductionWindowSeconds(Date windowStartTime, Date windowEndTime) {
        if (Objects.isNull(windowStartTime) || Objects.isNull(windowEndTime)
                || !windowStartTime.before(windowEndTime)) {
            return 0L;
        }
        return Math.max(0L, (windowEndTime.getTime() - windowStartTime.getTime()) / 1000L);
    }

    /**
     * 按左闭右开区间定位实际生产班次。
     *
     * <p>时间等于班次开始时归当前班；时间等于班次结束时不归当前班，
     * 由下一个班次继续匹配，避免在胎胚可供前一班生成首检或计划量。</p>
     *
     * @param shifts 待定位的有序班次列表
     * @param productionStartTime 实际生产开始时间
     * @return 命中的生产班次；未命中返回 null
     */
    public static LhShiftConfigVO resolveProductionShift(List<LhShiftConfigVO> shifts,
                                                         Date productionStartTime) {
        if (CollectionUtils.isEmpty(shifts) || Objects.isNull(productionStartTime)) {
            return null;
        }
        for (LhShiftConfigVO shift : shifts) {
            if (Objects.isNull(shift)
                    || Objects.isNull(shift.getShiftStartDateTime())
                    || Objects.isNull(shift.getShiftEndDateTime())) {
                continue;
            }
            if (!productionStartTime.before(shift.getShiftStartDateTime())
                    && productionStartTime.before(shift.getShiftEndDateTime())) {
                return shift;
            }
        }
        return null;
    }

    /**
     * 判断胎胚最早可供时间是否已经到达或越过指定业务日结束时间。
     *
     * @param earliestAvailableTime 胎胚最早可供硫化时间
     * @param dayEndTime 当前业务日结束时间
     * @return true-当前业务日不可生产；false-当前业务日内可继续试排
     */
    public static boolean reachesOrPassesDayEnd(Date earliestAvailableTime, Date dayEndTime) {
        return Objects.nonNull(earliestAvailableTime)
                && Objects.nonNull(dayEndTime)
                && !earliestAvailableTime.before(dayEndTime);
    }
}
