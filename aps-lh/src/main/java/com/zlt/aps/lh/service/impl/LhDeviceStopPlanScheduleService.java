package com.zlt.aps.lh.service.impl;

import com.zlt.aps.lh.api.constant.LhScheduleConstant;
import com.zlt.aps.lh.api.constant.LhScheduleParamConstant;
import com.zlt.aps.lh.api.domain.dto.CleaningScheduleDateFillItem;
import com.zlt.aps.lh.api.enums.CleaningTypeEnum;
import com.zlt.aps.lh.api.enums.MachineStopTypeEnum;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.mapper.MdmDevicePlanShutMapper;
import com.zlt.aps.lh.util.LhScheduleTimeUtil;
import com.zlt.aps.lh.util.ShiftCapacityResolverUtil;
import com.zlt.aps.mdm.api.domain.entity.MdmDevicePlanShut;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import javax.annotation.Resource;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 设备停机计划排程公共服务。
 *
 * <p>业务定位：统一识别设备停机计划中的清洗、维修、精度等停机类型，并提供清洗场景需要的
 * 班次归属、喷砂顺延、每日上限日期归属和普通停机列表剥离能力。清洗计划从设备停机表读取后，
 * 后续产能扣减必须只使用已纳入排程的清洗窗口，不能让未纳入的清洗停机记录继续作为普通停机扣产能。</p>
 *
 * @author APS
 */
@Slf4j
@Component
public class LhDeviceStopPlanScheduleService {

    /** 启用配置值 */
    private static final int ENABLED = 1;
    /** 多值参数分隔符 */
    private static final String VALUE_SEPARATOR = ",";

    /** 设备停机计划 Mapper：用于回填清洗实际排程日期到 T_MDM_DEVICE_PLAN_SHUT.SCHEDULE_DATE */
    @Resource
    private MdmDevicePlanShutMapper mdmDevicePlanShutMapper;

    /**
     * 从设备停机计划中过滤干冰/喷砂清洗候选，并按计划开始时间、机台编码升序返回。
     * <p>清洗计划加载口径与普通设备停机不同：只加载计划开始时间不早于 T 日的候选，
     * 但不要求计划开始时间落在 T～T+2 排程窗口内；T 日及之后的候选按计划时间排序后，
     * 实际清洗开始/结束时间由清洗排程服务按 T～T+2 窗口班次重新安排。</p>
     *
     * @param context 排程上下文
     * @return T 日及之后的清洗类设备停机候选（按计划开始时间、机台编码升序）
     */
    public List<MdmDevicePlanShut> queryCleaningStopPlans(LhScheduleContext context) {
        if (Objects.isNull(context) || CollectionUtils.isEmpty(context.getDevicePlanShutList())) {
            return Collections.emptyList();
        }
        List<MdmDevicePlanShut> cleaningStopPlans = new ArrayList<>(context.getDevicePlanShutList().size());
        for (MdmDevicePlanShut planShut : context.getDevicePlanShutList()) {
            // 清洗类停机计划的实际时长由配置参数决定（干冰3h/喷砂含首检12h），不依赖计划 begin/end 时长，
            // 因此允许 begin=end 的清洗计划纳入候选。
            if (Objects.isNull(planShut) || !isCleaningStopType(planShut.getMachineStopType())) {
                continue;
            }
            if (!isValidCleaningStopPlan(planShut)) {
                continue;
            }
            // 服务层再次执行 T 日边界校验，防止其他初始化入口误把历史清洗计划放入上下文并占用本次名额。
            if (!isPlanBeginOnOrAfterScheduleDate(context, planShut.getBeginDate())) {
                log.info("清洗计划早于T日，本次不纳入候选, T日: {}, 机台: {}, 停机类型: {}, 计划开始: {}",
                        LhScheduleTimeUtil.formatDate(context.getScheduleDate()),
                        planShut.getMachineCode(), planShut.getMachineStopType(),
                        LhScheduleTimeUtil.formatDateTime(planShut.getBeginDate()));
                continue;
            }
            cleaningStopPlans.add(planShut);
        }
        cleaningStopPlans.sort(Comparator
                .comparing(MdmDevicePlanShut::getBeginDate, Comparator.nullsLast(Date::compareTo))
                .thenComparing(MdmDevicePlanShut::getMachineCode, Comparator.nullsLast(String::compareTo)));
        return cleaningStopPlans;
    }

    /**
     * 过滤出非清洗类设备停机计划。
     *
     * <p>清洗类停机计划会先被转换成运行态清洗窗口；转换完成后必须从普通停机列表中剥离，
     * 否则超过每日上限或 3 天内收尾被跳过的清洗仍会按普通停机扣减产能。</p>
     *
     * <p>非清洗类停机（含精度校验、润滑、巡检点检、预见性维护、预防性维护、计划性维修、
     * 临时性故障、盘点等）保留在普通停机列表中，后续统一由 {@link ShiftCapacityResolverUtil}
     * 按时间重叠折算扣减班次可生产秒数。其中盘点（{@link MachineStopTypeEnum#TAKE_STOCK}）
     * 只扣时间产能，不触发换模、换活字块、预热等逻辑。</p>
     *
     * @param devicePlanShutList 原始设备停机计划
     * @return 非清洗类设备停机计划
     */
    public List<MdmDevicePlanShut> filterNonCleaningStopPlans(List<MdmDevicePlanShut> devicePlanShutList) {
        if (CollectionUtils.isEmpty(devicePlanShutList)) {
            return Collections.emptyList();
        }
        List<MdmDevicePlanShut> normalStopPlans = new ArrayList<>(devicePlanShutList.size());
        for (MdmDevicePlanShut planShut : devicePlanShutList) {
            if (Objects.isNull(planShut) || isCleaningStopType(planShut.getMachineStopType())) {
                continue;
            }
            normalStopPlans.add(planShut);
        }
        return normalStopPlans;
    }

    /**
     * 判断停机类型是否为干冰/喷砂清洗。
     *
     * @param machineStopType 停机类型编码
     * @return true-清洗类停机；false-普通设备停机
     */
    public boolean isCleaningStopType(String machineStopType) {
        return isDryIceCleaning(machineStopType) || isSandBlastCleaning(machineStopType);
    }

    /**
     * 判断停机类型是否为干冰清洗。
     *
     * @param machineStopType 停机类型编码
     * @return true-干冰清洗
     */
    public boolean isDryIceCleaning(String machineStopType) {
        return StringUtils.equals(MachineStopTypeEnum.DRY_ICE_CLEANING.getCode(), machineStopType);
    }

    /**
     * 判断停机类型是否为喷砂清洗。
     *
     * @param machineStopType 停机类型编码
     * @return true-喷砂清洗
     */
    public boolean isSandBlastCleaning(String machineStopType) {
        return StringUtils.equals(MachineStopTypeEnum.SANDBLASTING_CLEANING.getCode(), machineStopType);
    }

    /**
     * 判断停机类型是否为盘点。
     * <p>盘点停机只扣除对应时间段的可生产计划量，不触发换模、换活字块、首检、预热等逻辑；
     * 盘点期间机台视为已完成预热，盘点结束后可直接进入生产排产。</p>
     *
     * @param machineStopType 停机类型编码
     * @return true-盘点停机；false-非盘点停机
     */
    public boolean isInventoryStopType(String machineStopType) {
        return StringUtils.equals(MachineStopTypeEnum.TAKE_STOCK.getCode(), machineStopType);
    }

    /**
     * 将设备停机类型转换为清洗类型。
     *
     * @param planShut 设备停机计划
     * @return 清洗类型编码；非清洗类型返回 null
     */
    public String resolveCleaningType(MdmDevicePlanShut planShut) {
        if (Objects.isNull(planShut)) {
            return null;
        }
        if (isDryIceCleaning(planShut.getMachineStopType())) {
            return CleaningTypeEnum.DRY_ICE.getCode();
        }
        if (isSandBlastCleaning(planShut.getMachineStopType())) {
            return CleaningTypeEnum.SAND_BLAST.getCode();
        }
        return null;
    }

    /**
     * 解析设备停机计划自身维护的持续时长。
     *
     * @param planShut 设备停机计划
     * @return 持续时长毫秒；计划非法时返回 0
     */
    public long resolvePlanDurationMillis(MdmDevicePlanShut planShut) {
        if (!isValidStopPlan(planShut)) {
            return 0L;
        }
        return planShut.getEndDate().getTime() - planShut.getBeginDate().getTime();
    }

    /**
     * 解析喷砂清洗实际执行开始时间。
     *
     * <p>喷砂固定中班执行：计划不在中班时向后归一到后续第一个中班；若该中班命中喷砂机维保日或周日，
     * 继续向后顺延到下一个满足条件的中班。该方法只处理喷砂自身日期/班次约束，每日 1 台上限由清洗纳入服务判断。</p>
     *
     * @param context 排程上下文
     * @param planBeginTime 计划开始时间
     * @return 实际喷砂中班开始时间
     */
    public Date resolveSandBlastExecutableStartTime(LhScheduleContext context, Date planBeginTime) {
        if (Objects.isNull(planBeginTime)) {
            return null;
        }
        Date candidateStartTime = normalizeToAfternoonShift(context, planBeginTime);
        for (int attempt = 0; attempt < LhScheduleConstant.MAX_SHIFT_SLOT_COUNT * 30; attempt++) {
            if (!isSandBlastForbiddenDate(context, candidateStartTime)) {
                return candidateStartTime;
            }
            log.info("喷砂清洗日期不满足约束，顺延到下一天中班, 原日期: {}, 原因: {}",
                    LhScheduleTimeUtil.formatDate(candidateStartTime),
                    resolveSandBlastForbiddenReason(context, candidateStartTime));
            candidateStartTime = LhScheduleTimeUtil.getAfternoonShiftStart(
                    context, LhScheduleTimeUtil.addDays(candidateStartTime, 1));
        }
        return candidateStartTime;
    }

    /**
     * 解析干冰清洗实际执行开始时间。
     *
     * <p>干冰只能落在早班或中班。计划在早班/中班内时保持原计划时间；计划在早班前归一到当天早班；
     * 计划在夜班或中班后归一到次日早班。</p>
     *
     * @param context 排程上下文
     * @param planBeginTime 计划开始时间
     * @return 实际干冰清洗开始时间
     */
    public Date resolveDryIceExecutableStartTime(LhScheduleContext context, Date planBeginTime) {
        if (Objects.isNull(planBeginTime)) {
            return null;
        }
        if (LhScheduleTimeUtil.isMorningShift(context, planBeginTime)
                || LhScheduleTimeUtil.isAfternoonShift(context, planBeginTime)) {
            return planBeginTime;
        }
        Date morningStart = LhScheduleTimeUtil.getMorningShiftStart(context, planBeginTime);
        if (planBeginTime.before(morningStart)) {
            return morningStart;
        }
        return LhScheduleTimeUtil.getMorningShiftStart(context, LhScheduleTimeUtil.addDays(planBeginTime, 1));
    }

    /**
     * 判断清洗实际开始时间是否仍在排程窗口内。
     *
     * @param context 排程上下文
     * @param cleanStartTime 清洗实际开始时间
     * @return true-在窗口内；false-窗口外
     */
    public boolean isInScheduleWindow(LhScheduleContext context, Date cleanStartTime) {
        if (Objects.isNull(context) || Objects.isNull(context.getScheduleDate()) || Objects.isNull(cleanStartTime)) {
            return true;
        }
        Date windowStart = LhScheduleTimeUtil.clearTime(context.getScheduleDate());
        Date windowEnd = LhScheduleTimeUtil.addDays(windowStart, LhScheduleTimeUtil.getScheduleDays(context));
        return !cleanStartTime.before(windowStart) && cleanStartTime.before(windowEnd);
    }

    /**
     * 判断指定时间是否归属于早班。
     *
     * @param context 排程上下文
     * @param startTime 开始时间
     * @return true-早班
     */
    public boolean isMorningShift(LhScheduleContext context, Date startTime) {
        return Objects.nonNull(startTime) && LhScheduleTimeUtil.isMorningShift(context, startTime);
    }

    /**
     * 判断指定时间是否归属于中班。
     *
     * @param context 排程上下文
     * @param startTime 开始时间
     * @return true-中班
     */
    public boolean isAfternoonShift(LhScheduleContext context, Date startTime) {
        return Objects.nonNull(startTime) && LhScheduleTimeUtil.isAfternoonShift(context, startTime);
    }

    /**
     * 判断设备停机计划开始时间是否属于本次清洗候选范围。
     *
     * @param context 排程上下文
     * @param planBeginTime 设备停机计划开始时间
     * @return true-计划开始时间不早于 T 日零点
     */
    private boolean isPlanBeginOnOrAfterScheduleDate(LhScheduleContext context, Date planBeginTime) {
        if (Objects.isNull(context) || Objects.isNull(context.getScheduleDate()) || Objects.isNull(planBeginTime)) {
            return true;
        }
        Date scheduleStartTime = LhScheduleTimeUtil.clearTime(context.getScheduleDate());
        return !planBeginTime.before(scheduleStartTime);
    }

    /**
     * 校验清洗实际安排日期是否不晚于计划开始日期。
     *
     * <p>业务规则：实际清洗日期 ≤ 计划开始日期所在自然日。允许提前安排、允许安排在计划开始日当天，
     * 禁止晚于计划开始日期安排；比较按自然日（归零时分秒）进行，与"允许当天"口径一致。</p>
     *
     * <p>任一参数为 null 时返回 true，保持与 {@link #isPlanBeginOnOrAfterScheduleDate} 一致的容错口径，
     * 由调用方保证传入有效时间，不在本方法内新增兜底默认值。</p>
     *
     * @param planBeginDate        设备停机清洗计划的计划开始时间（beginDate）
     * @param actualCleanStartTime 实际清洗开始时间
     * @return true-实际清洗日不晚于计划开始日（含当天），可继续安排；false-已晚于计划开始日，应跳过
     */
    public boolean isCleaningActualDateNotLaterThanPlanBegin(Date planBeginDate, Date actualCleanStartTime) {
        if (Objects.isNull(planBeginDate) || Objects.isNull(actualCleanStartTime)) {
            return true;
        }
        // 归零到自然日后比较：实际清洗日 <= 计划开始日（含当天）
        Date planBeginDay = LhScheduleTimeUtil.clearTime(planBeginDate);
        Date actualCleanDay = LhScheduleTimeUtil.clearTime(actualCleanStartTime);
        return !actualCleanDay.after(planBeginDay);
    }

    /**
     * 批量回填清洗计划排程日期到设备停机计划。
     *
     * <p>在排程结果落库事务内调用：将清洗成功排程的实际清洗日期、或因 SKU 收尾未安排清洗时的收尾日期，
     * 回填到对应设备停机计划（{@code T_MDM_DEVICE_PLAN_SHUT}）的 {@code SCHEDULE_DATE} 字段。
     * 按设备停机计划主键 id 去重（同一计划只回填一次，保留首条），避免重复更新；仅更新非空字段。</p>
     *
     * @param fillList 清洗排程日期回填项列表
     * @return 实际更新记录数（去重后条数）
     */
    public int batchFillCleaningScheduleDate(List<CleaningScheduleDateFillItem> fillList) {
        if (CollectionUtils.isEmpty(fillList)) {
            log.info("清洗排程日期回填跳过，待回填列表为空");
            return 0;
        }
        // 按设备停机计划主键 id 去重，同一计划只回填一次（保留首次出现的回填项）
        Map<Long, CleaningScheduleDateFillItem> dedupMap = new LinkedHashMap<>(fillList.size());
        for (CleaningScheduleDateFillItem fillItem : fillList) {
            if (Objects.isNull(fillItem) || Objects.isNull(fillItem.getPlanId())
                    || Objects.isNull(fillItem.getScheduleDate())) {
                continue;
            }
            dedupMap.putIfAbsent(fillItem.getPlanId(), fillItem);
        }
        if (dedupMap.isEmpty()) {
            log.info("清洗排程日期回填跳过，去重后无可回填记录，原始条数: {}", fillList.size());
            return 0;
        }
        log.info("清洗排程日期回填开始，原始条数: {}, 去重后待回填条数: {}", fillList.size(), dedupMap.size());
        int updatedCount = 0;
        for (CleaningScheduleDateFillItem fillItem : dedupMap.values()) {
            // 仅按主键更新 SCHEDULE_DATE，MyBatis-Plus updateById 默认不覆盖 null 字段
            MdmDevicePlanShut updateEntity = new MdmDevicePlanShut();
            updateEntity.setId(fillItem.getPlanId());
            updateEntity.setScheduleDate(fillItem.getScheduleDate());
            mdmDevicePlanShutMapper.updateById(updateEntity);
            updatedCount++;
            log.info("清洗排程日期回填, 设备停机计划ID: {}, 机台: {}, 清洗类型: {}, 回填日期: {}, 原因: {}",
                    fillItem.getPlanId(), fillItem.getMachineCode(), fillItem.getCleanType(),
                    LhScheduleTimeUtil.formatDateTime(fillItem.getScheduleDate()), fillItem.getFillReason());
        }
        log.info("清洗排程日期回填完成，实际更新记录数: {}", updatedCount);
        return updatedCount;
    }

    /**
     * 校验清洗类设备停机计划是否有效。
     * <p>清洗类停机计划的实际清洗时长由配置参数决定（干冰3h/喷砂含首检12h），
     * 不依赖计划 begin/end 时长，因此允许 begin=end（零时长计划）纳入候选。</p>
     *
     * @param planShut 设备停机计划
     * @return true-有效清洗候选；false-无效
     */
    private boolean isValidCleaningStopPlan(MdmDevicePlanShut planShut) {
        return Objects.nonNull(planShut)
                && StringUtils.isNotEmpty(planShut.getMachineCode())
                && Objects.nonNull(planShut.getBeginDate())
                && Objects.nonNull(planShut.getEndDate());
    }

    private boolean isValidStopPlan(MdmDevicePlanShut planShut) {
        return Objects.nonNull(planShut)
                && StringUtils.isNotEmpty(planShut.getMachineCode())
                && Objects.nonNull(planShut.getBeginDate())
                && Objects.nonNull(planShut.getEndDate())
                && planShut.getBeginDate().before(planShut.getEndDate());
    }

    private Date normalizeToAfternoonShift(LhScheduleContext context, Date planBeginTime) {
        Date afternoonStart = LhScheduleTimeUtil.getAfternoonShiftStart(context, planBeginTime);
        Date nightStart = LhScheduleTimeUtil.getNightShiftStart(context, planBeginTime);
        if (LhScheduleTimeUtil.isAfternoonShift(context, planBeginTime)) {
            return planBeginTime;
        }
        if (planBeginTime.before(afternoonStart)) {
            return afternoonStart;
        }
        if (!planBeginTime.before(nightStart)) {
            return LhScheduleTimeUtil.getAfternoonShiftStart(context, LhScheduleTimeUtil.addDays(planBeginTime, 1));
        }
        return afternoonStart;
    }

    private boolean isSandBlastForbiddenDate(LhScheduleContext context, Date cleanTime) {
        return isSandBlastMaintenanceDate(context, cleanTime) || isSunday(cleanTime);
    }

    private String resolveSandBlastForbiddenReason(LhScheduleContext context, Date cleanTime) {
        if (isSandBlastMaintenanceDate(context, cleanTime)) {
            return "喷砂机维保日";
        }
        if (isSunday(cleanTime)) {
            return "周日";
        }
        return "未知原因";
    }

    private boolean isSandBlastMaintenanceDate(LhScheduleContext context, Date cleanTime) {
        if (Objects.isNull(context) || Objects.isNull(cleanTime)) {
            return false;
        }
        String maintenanceDates = context.getParamValue(LhScheduleParamConstant.SAND_BLAST_MAINTENANCE_DATES,
                LhScheduleConstant.SAND_BLAST_MAINTENANCE_DATES);
        if (StringUtils.isEmpty(maintenanceDates)) {
            return false;
        }
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(cleanTime);
        String dayOfMonth = String.valueOf(calendar.get(Calendar.DAY_OF_MONTH));
        for (String maintenanceDate : maintenanceDates.split(VALUE_SEPARATOR)) {
            if (StringUtils.equals(dayOfMonth, StringUtils.trim(maintenanceDate))) {
                return true;
            }
        }
        return false;
    }

    private boolean isSunday(Date cleanTime) {
        if (Objects.isNull(cleanTime)) {
            return false;
        }
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(cleanTime);
        return calendar.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY;
    }
}
