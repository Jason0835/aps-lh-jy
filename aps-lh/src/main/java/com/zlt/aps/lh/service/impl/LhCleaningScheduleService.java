package com.zlt.aps.lh.service.impl;

import com.zlt.aps.lh.api.constant.LhScheduleConstant;
import com.zlt.aps.lh.api.constant.LhScheduleParamConstant;
import com.zlt.aps.lh.api.domain.dto.MachineCleaningWindowDTO;
import com.zlt.aps.lh.api.domain.entity.LhMachineOnlineInfo;
import com.zlt.aps.lh.api.domain.entity.LhMouldCleanPlan;
import com.zlt.aps.lh.api.enums.CleaningTypeEnum;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.util.LeftRightMouldUtil;
import com.zlt.aps.lh.util.LhSingleControlMachineUtil;
import com.zlt.aps.lh.util.LhScheduleTimeUtil;
import com.zlt.aps.mdm.api.domain.entity.MdmDevicePlanShut;
import com.zlt.aps.mdm.api.domain.entity.MdmSkuMouldRel;
import com.zlt.aps.mdm.api.domain.entity.MdmWorkCalendar;
import com.zlt.aps.mp.api.domain.entity.FactoryMonthPlanProductionFinalResult;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 设备停机来源清洗计划运行态排程服务。
 *
 * @author APS
 */
@Slf4j
@Component
public class LhCleaningScheduleService {

    /** 设备停机计划数据来源标识 */
    private static final String DEVICE_STOP_PLAN_DATA_SOURCE = "DEVICE_STOP_PLAN";
    /** SKU 从清洗时间点开始 3 天内可收尾时跳过清洗 */
    private static final int CLEANING_SKIP_ENDING_DAYS = 3;
    /** 喷砂清洗每日最多安排 1 台 */
    private static final int SAND_BLAST_DAILY_LIMIT = 1;
    /** 干冰清洗早班额度 */
    private static final String DRY_ICE_SHIFT_MORNING = "MORNING";
    /** 干冰清洗中班额度 */
    private static final String DRY_ICE_SHIFT_AFTERNOON = "AFTERNOON";

    /** 工作日历停产标记 */
    private static final String WORK_CALENDAR_STOP_FLAG = "0";
    /** 启用配置值 */
    private static final int ENABLED = 1;
    /** 小时分钟分隔数量 */
    private static final int TIME_PART_COUNT = 2;
    /** 每小时分钟数 */
    private static final int MINUTES_PER_HOUR = 60;
    /** 时间分隔符 */
    private static final String TIME_SEPARATOR = ":";
    /** 多值分隔符 */
    private static final String VALUE_SEPARATOR = ",";
    /** 清洗可用日期最大搜索天数 */
    private static final int CLEANING_SEARCH_MAX_DAYS = 60;

    @Resource
    private LhDeviceStopPlanScheduleService deviceStopPlanScheduleService;

    /**
     * 按清洗约束生成本次排程实际生效的清洗窗口。
     * <p>最终口径：干冰清洗、喷砂清洗统一从设备停机计划读取，旧模具清洗计划列表不再作为排程来源。</p>
     *
     * @param context 排程上下文
     * @return 机台清洗窗口Map
     */
    public Map<String, List<MachineCleaningWindowDTO>> buildScheduledCleaningWindowMap(LhScheduleContext context) {
        Map<String, List<MachineCleaningWindowDTO>> cleaningWindowMap = new HashMap<>(16);
        if (Objects.isNull(context)) {
            return cleaningWindowMap;
        }
        List<MdmDevicePlanShut> cleaningPlanList = getDeviceStopPlanScheduleService().queryCleaningStopPlans(context);
        log.info("清洗计划约束采用设备停机计划运行态调度处理, 原始清洗停机计划数: {}", cleaningPlanList.size());
        Map<String, Integer> dryIceDailyCountMap = new HashMap<>(16);
        Map<String, Integer> dryIceMorningCountMap = new HashMap<>(16);
        Map<String, Integer> dryIceAfternoonCountMap = new HashMap<>(16);
        Map<String, Integer> sandBlastDailyCountMap = new HashMap<>(16);
        for (MdmDevicePlanShut cleaningPlan : cleaningPlanList) {
            // 调用设备停机清洗专用调度：计划开始时间只决定候选顺序，实际执行时间按本次窗口班次重新分配。
            MachineCleaningWindowDTO cleaningWindow = scheduleDeviceStopCleaningWindow(context, cleaningPlan,
                    dryIceDailyCountMap, dryIceMorningCountMap, dryIceAfternoonCountMap, sandBlastDailyCountMap);
            if (Objects.isNull(cleaningWindow)) {
                continue;
            }
            cleaningWindowMap.computeIfAbsent(cleaningWindow.getLhCode(), key -> new ArrayList<>()).add(cleaningWindow);
            // 单控机台（如 K1501L/K1501R）左右模生产同物料时需一起清洗：
            // 为配对侧机台创建相同时间、相同类型的清洗窗口，不额外消耗每日上限名额。
            MachineCleaningWindowDTO pairedWindow = createPairedSingleControlCleaningWindow(context, cleaningWindow);
            if (Objects.nonNull(pairedWindow)) {
                cleaningWindowMap.computeIfAbsent(pairedWindow.getLhCode(), key -> new ArrayList<>()).add(pairedWindow);
            }
        }
        // 清洗类停机已转换为运行态清洗窗口；剥离后普通停机产能扣减只处理维修、精度等真实设备停机。
        context.setDevicePlanShutList(getDeviceStopPlanScheduleService()
                .filterNonCleaningStopPlans(context.getDevicePlanShutList()));
        return cleaningWindowMap;
    }

    /**
     * 为单控机台配对侧创建清洗窗口。
     * <p>单控机台（如 K1501L/K1501R）左右模需一起清洗，当一侧机台生成了清洗窗口时，
     * 为配对侧机台创建相同时间、相同类型的清洗窗口，左右模标识按配对侧机台编码确定。
     * 配对窗口不额外消耗每日上限名额（与原窗口共享同一次清洗占用）。</p>
     *
     * @param context 排程上下文
     * @param cleaningWindow 原始清洗窗口
     * @return 配对侧清洗窗口；非单控机台或无配对侧时返回 null
     */
    private MachineCleaningWindowDTO createPairedSingleControlCleaningWindow(LhScheduleContext context,
                                                                             MachineCleaningWindowDTO cleaningWindow) {
        if (Objects.isNull(cleaningWindow) || Objects.isNull(context)
                || StringUtils.isEmpty(cleaningWindow.getLhCode())) {
            return null;
        }
        String machineCode = cleaningWindow.getLhCode();
        // 非单控机台无需配对
        if (!LhSingleControlMachineUtil.isSingleMouldMachine(machineCode)) {
            return null;
        }
        String pairMachineCode = LhSingleControlMachineUtil.resolvePairMachineCode(machineCode);
        if (StringUtils.isEmpty(pairMachineCode)) {
            return null;
        }
        // 配对侧机台需在排程机台中存在
        Map<String, ?> machineScheduleMap = context.getMachineScheduleMap();
        if (Objects.isNull(machineScheduleMap) || !machineScheduleMap.containsKey(pairMachineCode)) {
            return null;
        }
        // 创建配对侧清洗窗口，时间、类型与原窗口一致，左右模按配对侧机台编码确定
        MachineCleaningWindowDTO pairedWindow = new MachineCleaningWindowDTO();
        pairedWindow.setLhCode(pairMachineCode);
        pairedWindow.setCleanType(cleaningWindow.getCleanType());
        pairedWindow.setLeftRightMould(LeftRightMouldUtil.resolveCleaningLeftRightMould(pairMachineCode));
        pairedWindow.setMouldCode(cleaningWindow.getMouldCode());
        pairedWindow.setCleanStartTime(cleaningWindow.getCleanStartTime());
        pairedWindow.setCleanEndTime(cleaningWindow.getCleanEndTime());
        pairedWindow.setReadyTime(cleaningWindow.getReadyTime());
        pairedWindow.setSourcePlanStartTime(cleaningWindow.getSourcePlanStartTime());
        pairedWindow.setSourcePlanEndTime(cleaningWindow.getSourcePlanEndTime());
        pairedWindow.setDataSource(cleaningWindow.getDataSource());
        pairedWindow.setRemark(cleaningWindow.getRemark());
        log.info("单控机台配对侧清洗窗口创建, 原机台: {}, 配对机台: {}, 清洗类型: {}, 清洗开始: {}",
                machineCode, pairMachineCode, cleaningWindow.getCleanType(),
                LhScheduleTimeUtil.formatDateTime(cleaningWindow.getCleanStartTime()));
        return pairedWindow;
    }

    /**
     * 调度单条设备停机清洗计划。
     *
     * @param context 排程上下文
     * @param cleaningPlan 设备停机清洗计划
     * @param dryIceDailyCountMap 干冰每日已安排台数
     * @param dryIceMorningCountMap 干冰早班已安排台数
     * @param dryIceAfternoonCountMap 干冰中班已安排台数
     * @param sandBlastDailyCountMap 喷砂每日已安排台数
     * @return 生效清洗窗口；不满足规则时返回 null
     */
    private MachineCleaningWindowDTO scheduleDeviceStopCleaningWindow(LhScheduleContext context,
                                                                      MdmDevicePlanShut cleaningPlan,
                                                                      Map<String, Integer> dryIceDailyCountMap,
                                                                      Map<String, Integer> dryIceMorningCountMap,
                                                                      Map<String, Integer> dryIceAfternoonCountMap,
                                                                      Map<String, Integer> sandBlastDailyCountMap) {
        String cleanType = getDeviceStopPlanScheduleService().resolveCleaningType(cleaningPlan);
        if (StringUtils.isEmpty(cleanType)) {
            return null;
        }
        int cleanDurationHours = resolveCleanDurationHours(context, cleanType);
        if (cleanDurationHours <= 0) {
            log.warn("设备停机清洗时长配置非法，跳过清洗, 机台: {}, 清洗类型: {}, 计划开始: {}",
                    cleaningPlan.getMachineCode(), cleanType,
                    LhScheduleTimeUtil.formatDateTime(cleaningPlan.getBeginDate()));
            return null;
        }
        Date cleanStartTime = resolveNextDeviceStopCleaningStartTime(context, cleanType,
                dryIceDailyCountMap, dryIceMorningCountMap, dryIceAfternoonCountMap, sandBlastDailyCountMap);
        if (Objects.isNull(cleanStartTime)) {
            log.info("清洗计划超过本次窗口可安排上限，本次不纳入, 机台: {}, 类型: {}, 计划开始: {}",
                    cleaningPlan.getMachineCode(), cleanType, LhScheduleTimeUtil.formatDateTime(cleaningPlan.getBeginDate()));
            return null;
        }
        String machineMaterial = resolveMachineMaterial(context, cleaningPlan.getMachineCode());
        // 清洗执行前先判断当前 SKU 是否 3 天内可收尾；命中时不再占用干冰/喷砂清洗名额。
        if (isMachineEndingWithinThreeDays(context, cleaningPlan.getMachineCode(), cleanStartTime, machineMaterial)) {
            log.info("机台当前物料3天内可收尾，跳过清洗, 机台: {}, 物料: {}, 清洗类型: {}, 清洗时间: {}",
                    cleaningPlan.getMachineCode(),
                    StringUtils.isEmpty(machineMaterial) ? "N/A" : machineMaterial,
                    cleanType, LhScheduleTimeUtil.formatDateTime(cleanStartTime));
            return null;
        }
        increaseDeviceStopCleaningUsage(context, dryIceDailyCountMap, dryIceMorningCountMap,
                dryIceAfternoonCountMap, sandBlastDailyCountMap, cleanType, cleanStartTime);
        log.info("设备停机清洗纳入本次窗口, 机台: {}, 类型: {}, 计划开始: {}, 实际清洗开始: {}, 清洗时长小时: {}",
                cleaningPlan.getMachineCode(), cleanType, LhScheduleTimeUtil.formatDateTime(cleaningPlan.getBeginDate()),
                LhScheduleTimeUtil.formatDateTime(cleanStartTime), cleanDurationHours);
        return buildCleaningWindow(context, cleaningPlan, cleanType, cleanStartTime, cleanDurationHours);
    }

    /**
     * 解析下一条设备停机来源清洗在本次排程窗口内的实际清洗开始时间。
     * <p>该方法是清洗专用逻辑：不使用设备停机计划的计划开始/结束作为实际执行时间，
     * 只根据当前已占用的每日/班次名额，从 T～T+2 窗口内寻找可安排班次。</p>
     *
     * @param context 排程上下文
     * @param cleanType 清洗类型
     * @param dryIceDailyCountMap 干冰每日已安排台数
     * @param dryIceMorningCountMap 干冰早班已安排台数
     * @param dryIceAfternoonCountMap 干冰中班已安排台数
     * @param sandBlastDailyCountMap 喷砂每日已安排台数
     * @return 本次窗口内实际清洗开始时间；窗口额度耗尽时返回 null
     */
    private Date resolveNextDeviceStopCleaningStartTime(LhScheduleContext context,
                                                        String cleanType,
                                                        Map<String, Integer> dryIceDailyCountMap,
                                                        Map<String, Integer> dryIceMorningCountMap,
                                                        Map<String, Integer> dryIceAfternoonCountMap,
                                                        Map<String, Integer> sandBlastDailyCountMap) {
        if (CleaningTypeEnum.SAND_BLAST.getCode().equals(cleanType)) {
            return resolveNextDeviceStopSandBlastStartTime(context, sandBlastDailyCountMap);
        }
        if (CleaningTypeEnum.DRY_ICE.getCode().equals(cleanType)) {
            return resolveNextDeviceStopDryIceStartTime(context, dryIceDailyCountMap,
                    dryIceMorningCountMap, dryIceAfternoonCountMap);
        }
        return null;
    }

    /**
     * 解析设备停机来源干冰清洗在本次窗口内的下一个早班/中班开始时间。
     *
     * @param context 排程上下文
     * @param dryIceDailyCountMap 干冰每日已安排台数
     * @param dryIceMorningCountMap 干冰早班已安排台数
     * @param dryIceAfternoonCountMap 干冰中班已安排台数
     * @return 实际干冰清洗开始时间；无可用班次时返回 null
     */
    private Date resolveNextDeviceStopDryIceStartTime(LhScheduleContext context,
                                                      Map<String, Integer> dryIceDailyCountMap,
                                                      Map<String, Integer> dryIceMorningCountMap,
                                                      Map<String, Integer> dryIceAfternoonCountMap) {
        Date windowStartTime = LhScheduleTimeUtil.clearTime(context.getScheduleDate());
        int scheduleDays = LhScheduleTimeUtil.getScheduleDays(context);
        int dryIceDailyLimit = context.getParamIntValue(
                LhScheduleParamConstant.DRY_ICE_DAILY_LIMIT, LhScheduleConstant.DRY_ICE_DAILY_LIMIT);
        int morningLimit = (dryIceDailyLimit + 1) / 2;
        int afternoonLimit = dryIceDailyLimit - morningLimit;
        for (int dayIndex = 0; dayIndex < scheduleDays; dayIndex++) {
            Date currentDate = LhScheduleTimeUtil.addDays(windowStartTime, dayIndex);
            String dateKey = LhScheduleTimeUtil.formatDate(currentDate);
            if (dryIceDailyCountMap.getOrDefault(dateKey, 0) >= dryIceDailyLimit) {
                continue;
            }
            if (dryIceMorningCountMap.getOrDefault(dateKey, 0) < morningLimit) {
                return LhScheduleTimeUtil.getMorningShiftStart(context, currentDate);
            }
            if (dryIceAfternoonCountMap.getOrDefault(dateKey, 0) < afternoonLimit) {
                return LhScheduleTimeUtil.getAfternoonShiftStart(context, currentDate);
            }
        }
        return null;
    }

    /**
     * 解析设备停机来源喷砂清洗在本次窗口内的下一个中班开始时间。
     *
     * @param context 排程上下文
     * @param sandBlastDailyCountMap 喷砂每日已安排台数
     * @return 实际喷砂清洗开始时间；无可用中班时返回 null
     */
    private Date resolveNextDeviceStopSandBlastStartTime(LhScheduleContext context,
                                                         Map<String, Integer> sandBlastDailyCountMap) {
        Date windowStartTime = LhScheduleTimeUtil.clearTime(context.getScheduleDate());
        int scheduleDays = LhScheduleTimeUtil.getScheduleDays(context);
        for (int dayIndex = 0; dayIndex < scheduleDays; dayIndex++) {
            Date currentDate = LhScheduleTimeUtil.addDays(windowStartTime, dayIndex);
            Date afternoonStartTime = LhScheduleTimeUtil.getAfternoonShiftStart(context, currentDate);
            String dateKey = LhScheduleTimeUtil.formatDate(afternoonStartTime);
            // 喷砂只允许中班执行，且命中周日、维保日、不可排日期时整日跳过。
            if (sandBlastDailyCountMap.getOrDefault(dateKey, 0) < SAND_BLAST_DAILY_LIMIT
                    && !isSandBlastForbiddenDate(context, afternoonStartTime)) {
                return afternoonStartTime;
            }
        }
        return null;
    }

    /**
     * 构建设备停机来源的清洗窗口。
     *
     * @param context 排程上下文
     * @param cleaningPlan 设备停机清洗计划
     * @param cleanType 清洗类型
     * @param cleanStartTime 实际清洗开始时间
     * @param cleanDurationHours 清洗持续小时数
     * @return 清洗窗口
     */
    private MachineCleaningWindowDTO buildCleaningWindow(LhScheduleContext context,
                                                         MdmDevicePlanShut cleaningPlan,
                                                         String cleanType,
                                                         Date cleanStartTime,
                                                         int cleanDurationHours) {
        MachineCleaningWindowDTO cleaningWindow = new MachineCleaningWindowDTO();
        cleaningWindow.setLhCode(cleaningPlan.getMachineCode());
        cleaningWindow.setCleanType(cleanType);
        // 单控机台按机台编码后缀确定左/右模，双模机台统一 LR
        cleaningWindow.setLeftRightMould(LeftRightMouldUtil.resolveCleaningLeftRightMould(cleaningPlan.getMachineCode()));
        cleaningWindow.setMouldCode(resolveCleaningMouldCode(context, cleaningPlan.getMachineCode()));
        cleaningWindow.setCleanStartTime(cleanStartTime);
        // 喷砂清洗含首检耗时（12h）用于班次产能扣减和机台可开产时间；干冰清洗时长不变
        int readyDurationHours = resolveReadyDurationHours(context, cleanType, cleanDurationHours);
        Date cleanEndTime = LhScheduleTimeUtil.addHours(cleanStartTime, readyDurationHours);
        cleaningWindow.setCleanEndTime(cleanEndTime);
        cleaningWindow.setReadyTime(cleanEndTime);
        // 保留来源设备停机计划窗口，后续换模重叠判断用计划窗口识别“清洗+换模”，但不作为实际清洗时间。
        cleaningWindow.setSourcePlanStartTime(cleaningPlan.getBeginDate());
        cleaningWindow.setSourcePlanEndTime(cleaningPlan.getEndDate());
        cleaningWindow.setDataSource(DEVICE_STOP_PLAN_DATA_SOURCE);
        cleaningWindow.setRemark(cleaningPlan.getRemark());
        return cleaningWindow;
    }

    /**
     * 登记设备停机来源清洗占用的每日/班次名额。
     *
     * @param context 排程上下文
     * @param dryIceDailyCountMap 干冰每日已安排台数
     * @param dryIceMorningCountMap 干冰早班已安排台数
     * @param dryIceAfternoonCountMap 干冰中班已安排台数
     * @param sandBlastDailyCountMap 喷砂每日已安排台数
     * @param cleanType 清洗类型
     * @param cleanStartTime 实际清洗开始时间
     */
    private void increaseDeviceStopCleaningUsage(LhScheduleContext context,
                                                 Map<String, Integer> dryIceDailyCountMap,
                                                 Map<String, Integer> dryIceMorningCountMap,
                                                 Map<String, Integer> dryIceAfternoonCountMap,
                                                 Map<String, Integer> sandBlastDailyCountMap,
                                                 String cleanType,
                                                 Date cleanStartTime) {
        String dateKey = LhScheduleTimeUtil.formatDate(cleanStartTime);
        if (CleaningTypeEnum.SAND_BLAST.getCode().equals(cleanType)) {
            increaseCount(sandBlastDailyCountMap, dateKey);
            return;
        }
        if (CleaningTypeEnum.DRY_ICE.getCode().equals(cleanType)) {
            increaseCount(dryIceDailyCountMap, dateKey);
            if (getDeviceStopPlanScheduleService().isMorningShift(context, cleanStartTime)) {
                increaseCount(dryIceMorningCountMap, dateKey);
            } else if (getDeviceStopPlanScheduleService().isAfternoonShift(context, cleanStartTime)) {
                increaseCount(dryIceAfternoonCountMap, dateKey);
            }
        }
    }

    /**
     * 判断机台当前在机物料是否 3 天内可收尾。
     *
     * @param context 排程上下文
     * @param machineCode 机台编码
     * @param cleanStartTime 清洗开始时间
     * @param machineMaterial 机台当前在机物料
     * @return true-应跳过清洗；false-允许继续判断清洗纳入
     */
    private boolean isMachineEndingWithinThreeDays(LhScheduleContext context,
                                                   String machineCode,
                                                   Date cleanStartTime,
                                                   String machineMaterial) {
        if (Objects.isNull(context) || StringUtils.isEmpty(machineCode)
                || Objects.isNull(cleanStartTime) || StringUtils.isEmpty(machineMaterial)) {
            return false;
        }
        int remainingDays = resolveEndingRemainingDays(context, machineMaterial);
        return remainingDays >= 0 && remainingDays <= CLEANING_SKIP_ENDING_DAYS;
    }

    private LhDeviceStopPlanScheduleService getDeviceStopPlanScheduleService() {
        return Objects.nonNull(deviceStopPlanScheduleService)
                ? deviceStopPlanScheduleService : new LhDeviceStopPlanScheduleService();
    }

    private Date resolveCleaningPlanSortTime(LhMouldCleanPlan cleaningPlan) {
        return cleaningPlan != null ? cleaningPlan.getCleanTime() : null;
    }

    private MachineCleaningWindowDTO scheduleCleaningWindow(LhScheduleContext context,
                                                            LhMouldCleanPlan cleaningPlan,
                                                            Map<String, Integer> totalDailyCountMap,
                                                            Map<String, Integer> dryIceDailyCountMap,
                                                            Map<String, Integer> dryIceMorningCountMap,
                                                            Map<String, Integer> dryIceAfternoonCountMap,
                                                            Map<String, Integer> sandBlastDailyCountMap) {
        if (!isValidCleaningPlan(cleaningPlan, cleaningPlan != null ? cleaningPlan.getCleanType() : null)) {
            return null;
        }
        // 机台当前在机物料近收尾（剩余天数 <= 阈值），跳过清洗避免无效停机
        String machineMaterial = resolveMachineMaterial(context, cleaningPlan.getLhCode());
        if (isMachineEndingSoon(context, cleaningPlan, machineMaterial)) {
            log.info("机台当前物料近收尾，跳过清洗, 机台: {}, 物料: {}, 清洗类型: {}, 清洗时间: {}",
                    cleaningPlan.getLhCode(),
                    StringUtils.isEmpty(machineMaterial) ? "N/A" : machineMaterial,
                    cleaningPlan.getCleanType(),
                    LhScheduleTimeUtil.formatDateTime(cleaningPlan.getCleanTime()));
            return null;
        }
        String cleanType = cleaningPlan.getCleanType();
        Date candidateStartTime = resolvePreferredCleaningStartTime(context, cleaningPlan);
        int cleanDurationHours = resolveCleanDurationHours(context, cleanType);
        int readyDurationHours = resolveReadyDurationHours(context, cleanType, cleanDurationHours);
        if (Objects.isNull(candidateStartTime) || cleanDurationHours <= 0) {
            return null;
        }
        for (int attempt = 0; attempt < CLEANING_SEARCH_MAX_DAYS; attempt++) {
            candidateStartTime = normalizeCleaningStartTime(context, cleanType, candidateStartTime);
            Date adjustedStartTime = resolveAdjustedCleaningStartTime(
                    context, cleaningPlan.getLhCode(), candidateStartTime, cleanDurationHours);
            if (CleaningTypeEnum.SAND_BLAST.getCode().equals(cleanType)
                    && isSandBlastForbiddenDate(context, adjustedStartTime)) {
                candidateStartTime = resolvePreviousSandBlastCandidate(context, candidateStartTime);
                continue;
            }
            if (canScheduleCleaning(context, totalDailyCountMap, dryIceDailyCountMap, dryIceMorningCountMap,
                    dryIceAfternoonCountMap, sandBlastDailyCountMap, cleanType, adjustedStartTime)) {
                if (!isInScheduleWindow(context, adjustedStartTime)) {
                    log.info("清洗窗口调整到排程窗口外，本次不计入清洗占用, 机台: {}, 类型: {}, 原时间: {}, 调整后: {}",
                            cleaningPlan.getLhCode(), cleanType,
                            LhScheduleTimeUtil.formatDateTime(cleaningPlan.getCleanTime()),
                            LhScheduleTimeUtil.formatDateTime(adjustedStartTime));
                    return null;
                }
                increaseCleaningCapacityUsage(context, totalDailyCountMap, dryIceDailyCountMap, dryIceMorningCountMap,
                        dryIceAfternoonCountMap, sandBlastDailyCountMap, cleanType, adjustedStartTime);
                return buildCleaningWindow(context, cleaningPlan, adjustedStartTime, cleanDurationHours, readyDurationHours);
            }
            candidateStartTime = resolveNextCleaningCandidateTime(context, cleanType, adjustedStartTime);
        }
        log.warn("清洗窗口在搜索范围内未找到可用时段，本次不计入清洗占用, 机台: {}, 类型: {}, 原时间: {}",
                cleaningPlan.getLhCode(), cleanType, LhScheduleTimeUtil.formatDateTime(cleaningPlan.getCleanTime()));
        return null;
    }

    /**
     * 构建机台清洗时间窗口。
     *
     * @param context 排程上下文
     * @param cleaningPlan 模具清洗计划
     * @return 清洗时间窗口
     */
    private MachineCleaningWindowDTO buildCleaningWindow(LhScheduleContext context, LhMouldCleanPlan cleaningPlan) {
        if (cleaningPlan == null || cleaningPlan.getCleanTime() == null) {
            return null;
        }
        String cleanType = cleaningPlan.getCleanType();
        int cleanDurationHours;
        int readyDurationHours;
        Date originalCleanStartTime = cleaningPlan.getCleanTime();
        if (CleaningTypeEnum.DRY_ICE.getCode().equals(cleanType)) {
            cleanDurationHours = resolveCleanDurationHours(context, cleanType);
            readyDurationHours = cleanDurationHours;
            originalCleanStartTime = resolveDryIceWindowStartTime(context, cleaningPlan.getCleanTime());
        } else if (CleaningTypeEnum.SAND_BLAST.getCode().equals(cleanType)) {
            cleanDurationHours = resolveCleanDurationHours(context, cleanType);
            // 喷砂总停机口径用于班次扣减；机台再次可开产时间仍沿用喷砂清洗原时长，
            // 后续换模/换活字块时间继续由各自排产链路单独叠加，避免重复计时。
            readyDurationHours = resolveReadyDurationHours(context, cleanType, cleanDurationHours);
        } else {
            return null;
        }

        // 清洗计划与设备计划停机重叠时，清洗必须顺延到停机结束后执行。
        Date adjustedCleanStartTime = resolveAdjustedCleaningStartTime(
                context, cleaningPlan.getLhCode(), originalCleanStartTime, cleanDurationHours);

        return buildCleaningWindow(context, cleaningPlan, adjustedCleanStartTime, cleanDurationHours, readyDurationHours);
    }

    private MachineCleaningWindowDTO buildCleaningWindow(LhScheduleContext context,
                                                         LhMouldCleanPlan cleaningPlan,
                                                         Date adjustedCleanStartTime,
                                                         int cleanDurationHours,
                                                         int readyDurationHours) {
        MachineCleaningWindowDTO cleaningWindow = new MachineCleaningWindowDTO();
        cleaningWindow.setLhCode(cleaningPlan.getLhCode());
        cleaningWindow.setCleanType(cleaningPlan.getCleanType());
        cleaningWindow.setLeftRightMould(cleaningPlan.getLeftRightMould());
        cleaningWindow.setMouldCode(resolveCleaningMouldCode(context, cleaningPlan.getLhCode()));
        cleaningWindow.setCleanStartTime(adjustedCleanStartTime);
        cleaningWindow.setCleanEndTime(LhScheduleTimeUtil.addHours(adjustedCleanStartTime, cleanDurationHours));
        cleaningWindow.setReadyTime(LhScheduleTimeUtil.addHours(adjustedCleanStartTime, readyDurationHours));
        cleaningWindow.setDataSource(cleaningPlan.getDataSource());
        cleaningWindow.setRemark(cleaningPlan.getRemark());
        return cleaningWindow;
    }

    /**
     * 判断清洗计划是否为指定类型。
     *
     * @param cleaningPlan 清洗计划
     * @param cleanType 清洗类型
     * @return true-有效；false-无效
     */
    private boolean isValidCleaningPlan(LhMouldCleanPlan cleaningPlan, String cleanType) {
        return Objects.nonNull(cleaningPlan)
                && Objects.nonNull(cleaningPlan.getCleanTime())
                && StringUtils.isNotEmpty(cleaningPlan.getLhCode())
                && (CleaningTypeEnum.DRY_ICE.getCode().equals(cleaningPlan.getCleanType())
                || CleaningTypeEnum.SAND_BLAST.getCode().equals(cleaningPlan.getCleanType()))
                && StringUtils.equals(cleanType, cleaningPlan.getCleanType());
    }

    private Date resolvePreferredCleaningStartTime(LhScheduleContext context, LhMouldCleanPlan cleaningPlan) {
        if (Objects.isNull(cleaningPlan) || Objects.isNull(cleaningPlan.getCleanTime())) {
            return null;
        }
        if (CleaningTypeEnum.DRY_ICE.getCode().equals(cleaningPlan.getCleanType())) {
            return resolveDryIceWindowStartTime(context, cleaningPlan.getCleanTime());
        }
        if (CleaningTypeEnum.SAND_BLAST.getCode().equals(cleaningPlan.getCleanType())) {
            return resolvePreviousAvailableSandBlastStartTime(context, cleaningPlan.getCleanTime());
        }
        return cleaningPlan.getCleanTime();
    }

    private Date normalizeCleaningStartTime(LhScheduleContext context, String cleanType, Date candidateStartTime) {
        if (CleaningTypeEnum.DRY_ICE.getCode().equals(cleanType)) {
            return resolveDryIceWindowStartTime(context, candidateStartTime);
        }
        return candidateStartTime;
    }

    private int resolveCleanDurationHours(LhScheduleContext context, String cleanType) {
        if (CleaningTypeEnum.DRY_ICE.getCode().equals(cleanType)) {
            return context.getParamIntValue(LhScheduleParamConstant.DRY_ICE_DURATION_HOURS,
                    LhScheduleConstant.DRY_ICE_DURATION_HOURS);
        }
        if (CleaningTypeEnum.SAND_BLAST.getCode().equals(cleanType)) {
            return context.getParamIntValue(LhScheduleParamConstant.SAND_BLAST_DURATION_HOURS,
                    LhScheduleConstant.SAND_BLAST_DURATION_HOURS);
        }
        return 0;
    }

    private int resolveReadyDurationHours(LhScheduleContext context, String cleanType, int cleanDurationHours) {
        // 喷砂清洗含首检耗时（默认12h = 10h清洗 + 2h首检），用于产能扣减和机台可开产时间
        if (CleaningTypeEnum.SAND_BLAST.getCode().equals(cleanType)) {
            return context.getParamIntValue(LhScheduleParamConstant.SAND_BLAST_WITH_INSPECTION_HOURS,
                    LhScheduleConstant.SAND_BLAST_WITH_INSPECTION_HOURS);
        }
        return cleanDurationHours;
    }

    private boolean canScheduleCleaning(LhScheduleContext context,
                                        Map<String, Integer> totalDailyCountMap,
                                        Map<String, Integer> dryIceDailyCountMap,
                                        Map<String, Integer> dryIceMorningCountMap,
                                        Map<String, Integer> dryIceAfternoonCountMap,
                                        Map<String, Integer> sandBlastDailyCountMap,
                                        String cleanType,
                                        Date cleanStartTime) {
        String dateKey = LhScheduleTimeUtil.formatDate(cleanStartTime);
        int dryIceDailyLimit = context.getParamIntValue(
                LhScheduleParamConstant.DRY_ICE_DAILY_LIMIT, LhScheduleConstant.DRY_ICE_DAILY_LIMIT);
        int sandBlastDailyLimit = context.getParamIntValue(
                LhScheduleParamConstant.SAND_BLAST_DAILY_LIMIT, LhScheduleConstant.SAND_BLAST_DAILY_LIMIT);
        int totalDailyLimit = dryIceDailyLimit + sandBlastDailyLimit;
        if (totalDailyCountMap.getOrDefault(dateKey, 0) >= totalDailyLimit) {
            return false;
        }
        if (CleaningTypeEnum.SAND_BLAST.getCode().equals(cleanType)) {
            return sandBlastDailyCountMap.getOrDefault(dateKey, 0) < sandBlastDailyLimit;
        }
        if (!CleaningTypeEnum.DRY_ICE.getCode().equals(cleanType)
                || dryIceDailyCountMap.getOrDefault(dateKey, 0) >= dryIceDailyLimit) {
            return false;
        }
        String dryIceShiftBucket = resolveDryIceShiftBucket(
                context, cleanStartTime, resolveCleanDurationHours(context, cleanType));
        if (StringUtils.equals(DRY_ICE_SHIFT_MORNING, dryIceShiftBucket)) {
            int morningLimit = context.getParamIntValue(LhScheduleParamConstant.DRY_ICE_MORNING_SHIFT_LIMIT,
                    LhScheduleConstant.DRY_ICE_MORNING_SHIFT_LIMIT);
            return dryIceMorningCountMap.getOrDefault(dateKey, 0) < morningLimit;
        }
        if (StringUtils.equals(DRY_ICE_SHIFT_AFTERNOON, dryIceShiftBucket)) {
            int afternoonLimit = context.getParamIntValue(LhScheduleParamConstant.DRY_ICE_AFTERNOON_SHIFT_LIMIT,
                    LhScheduleConstant.DRY_ICE_AFTERNOON_SHIFT_LIMIT);
            return dryIceAfternoonCountMap.getOrDefault(dateKey, 0) < afternoonLimit;
        }
        return false;
    }

    private void increaseCleaningCapacityUsage(LhScheduleContext context,
                                               Map<String, Integer> totalDailyCountMap,
                                               Map<String, Integer> dryIceDailyCountMap,
                                               Map<String, Integer> dryIceMorningCountMap,
                                               Map<String, Integer> dryIceAfternoonCountMap,
                                               Map<String, Integer> sandBlastDailyCountMap,
                                               String cleanType,
                                               Date cleanStartTime) {
        String dateKey = LhScheduleTimeUtil.formatDate(cleanStartTime);
        increaseCount(totalDailyCountMap, dateKey);
        if (CleaningTypeEnum.SAND_BLAST.getCode().equals(cleanType)) {
            increaseCount(sandBlastDailyCountMap, dateKey);
            return;
        }
        if (CleaningTypeEnum.DRY_ICE.getCode().equals(cleanType)) {
            increaseCount(dryIceDailyCountMap, dateKey);
            String dryIceShiftBucket = resolveDryIceShiftBucket(
                    context, cleanStartTime, resolveCleanDurationHours(context, cleanType));
            if (StringUtils.equals(DRY_ICE_SHIFT_MORNING, dryIceShiftBucket)) {
                increaseCount(dryIceMorningCountMap, dateKey);
            } else if (StringUtils.equals(DRY_ICE_SHIFT_AFTERNOON, dryIceShiftBucket)) {
                increaseCount(dryIceAfternoonCountMap, dateKey);
            }
        }
    }

    /**
     * 解析干冰清洗应占用的班次额度。
     * <p>若顺延后的干冰窗口跨入中班，则中班额度应优先被占用，避免同日中班实际排入多台。</p>
     *
     * @param context 排程上下文
     * @param cleanStartTime 清洗开始时间
     * @param cleanDurationHours 清洗时长（小时）
     * @return MORNING/AFTERNOON；无法归属时返回 null
     */
    private String resolveDryIceShiftBucket(LhScheduleContext context, Date cleanStartTime, int cleanDurationHours) {
        if (Objects.isNull(context) || Objects.isNull(cleanStartTime) || cleanDurationHours <= 0) {
            return null;
        }
        Date afternoonShiftStart = LhScheduleTimeUtil.getAfternoonShiftStart(context, cleanStartTime);
        Date cleanEndTime = LhScheduleTimeUtil.addHours(cleanStartTime, cleanDurationHours);
        if (cleanStartTime.before(afternoonShiftStart) && cleanEndTime.after(afternoonShiftStart)) {
            return DRY_ICE_SHIFT_AFTERNOON;
        }
        if (LhScheduleTimeUtil.isMorningShift(context, cleanStartTime)) {
            return DRY_ICE_SHIFT_MORNING;
        }
        if (LhScheduleTimeUtil.isAfternoonShift(context, cleanStartTime)) {
            return DRY_ICE_SHIFT_AFTERNOON;
        }
        return null;
    }

    private void increaseCount(Map<String, Integer> countMap, String dateKey) {
        countMap.put(dateKey, countMap.getOrDefault(dateKey, 0) + 1);
    }

    private Date resolveNextCleaningCandidateTime(LhScheduleContext context, String cleanType, Date currentStartTime) {
        if (CleaningTypeEnum.DRY_ICE.getCode().equals(cleanType)) {
            if (LhScheduleTimeUtil.isMorningShift(context, currentStartTime)) {
                return LhScheduleTimeUtil.getAfternoonShiftStart(context, currentStartTime);
            }
            return LhScheduleTimeUtil.getMorningShiftStart(context, LhScheduleTimeUtil.addDays(currentStartTime, 1));
        }
        return resolveNextAvailableSandBlastCandidate(context, LhScheduleTimeUtil.addDays(currentStartTime, 1));
    }

    private boolean isInScheduleWindow(LhScheduleContext context, Date cleanStartTime) {
        if (Objects.isNull(context) || Objects.isNull(context.getScheduleDate()) || Objects.isNull(cleanStartTime)) {
            return true;
        }
        Date windowStart = LhScheduleTimeUtil.clearTime(context.getScheduleDate());
        Date windowEnd = LhScheduleTimeUtil.addDays(windowStart, LhScheduleTimeUtil.getScheduleDays(context));
        return !cleanStartTime.before(windowStart) && cleanStartTime.before(windowEnd);
    }

    private Date resolvePreviousSandBlastCandidate(LhScheduleContext context, Date candidateStartTime) {
        if (Objects.isNull(candidateStartTime)) {
            return null;
        }
        return resolvePreviousAvailableSandBlastStartTime(context, LhScheduleTimeUtil.addDays(candidateStartTime, -1));
    }

    private Date resolvePreviousAvailableSandBlastStartTime(LhScheduleContext context, Date candidateStartTime) {
        Date cursorTime = candidateStartTime;
        for (int attempt = 0; attempt < CLEANING_SEARCH_MAX_DAYS && Objects.nonNull(cursorTime); attempt++) {
            if (!isSandBlastForbiddenDate(context, cursorTime)) {
                return cursorTime;
            }
            cursorTime = LhScheduleTimeUtil.addDays(cursorTime, -1);
        }
        return cursorTime;
    }

    private Date resolveNextAvailableSandBlastCandidate(LhScheduleContext context, Date candidateStartTime) {
        Date cursorTime = candidateStartTime;
        for (int attempt = 0; attempt < CLEANING_SEARCH_MAX_DAYS && Objects.nonNull(cursorTime); attempt++) {
            if (!isSandBlastForbiddenDate(context, cursorTime)) {
                return cursorTime;
            }
            cursorTime = LhScheduleTimeUtil.addDays(cursorTime, 1);
        }
        return cursorTime;
    }

    private boolean isSandBlastForbiddenDate(LhScheduleContext context, Date cleanTime) {
        if (Objects.isNull(cleanTime)) {
            return false;
        }
        if (isSandBlastMaintenanceDate(context, cleanTime)) {
            return true;
        }
        if (isSunday(cleanTime)
                && context.getParamIntValue(LhScheduleParamConstant.SAND_BLAST_SKIP_SUNDAY_ENABLED,
                LhScheduleConstant.SAND_BLAST_SKIP_SUNDAY_ENABLED) == ENABLED) {
            return true;
        }
        return isHoliday(context, cleanTime)
                && context.getParamIntValue(LhScheduleParamConstant.SAND_BLAST_SKIP_HOLIDAY_ENABLED,
                LhScheduleConstant.SAND_BLAST_SKIP_HOLIDAY_ENABLED) == ENABLED;
    }

    /**
     * 生成干冰清洗窗口开始时间。
     *
     * @param context 排程上下文
     * @param cleanTime 清洗计划时间
     * @return 清洗窗口开始时间
     */
    private Date resolveDryIceWindowStartTime(LhScheduleContext context, Date cleanTime) {
        int startMinutes = parseTimeMinutes(context.getParamValue(LhScheduleParamConstant.DRY_ICE_WORK_START_TIME,
                LhScheduleConstant.DRY_ICE_WORK_START_TIME));
        int endMinutes = parseTimeMinutes(context.getParamValue(LhScheduleParamConstant.DRY_ICE_WORK_END_TIME,
                LhScheduleConstant.DRY_ICE_WORK_END_TIME));
        int currentMinutes = resolveDayMinutes(cleanTime);
        if (currentMinutes < startMinutes) {
            return buildTimeByMinutes(cleanTime, startMinutes);
        }
        if (currentMinutes > endMinutes) {
            return buildTimeByMinutes(LhScheduleTimeUtil.addDays(cleanTime, 1), startMinutes);
        }
        return cleanTime;
    }

    /**
     * 解析机台当前在机模具号。
     *
     * @param context 排程上下文
     * @param machineCode 机台编号
     * @return 模具号
     */
    private String resolveCleaningMouldCode(LhScheduleContext context, String machineCode) {
        if (Objects.isNull(context) || StringUtils.isEmpty(machineCode)) {
            return null;
        }
        LhMachineOnlineInfo onlineInfo = context.getMachineOnlineInfoMap().get(machineCode);
        if (Objects.isNull(onlineInfo) || StringUtils.isEmpty(onlineInfo.getMaterialCode())) {
            return null;
        }
        List<MdmSkuMouldRel> mouldRelList = context.getSkuMouldRelMap().get(onlineInfo.getMaterialCode());
        if (CollectionUtils.isEmpty(mouldRelList)) {
            return null;
        }
        return mouldRelList.stream()
                .map(MdmSkuMouldRel::getMouldCode)
                .filter(StringUtils::isNotEmpty)
                .distinct()
                .collect(Collectors.joining(VALUE_SEPARATOR));
    }

    /**
     * 判断是否为喷砂机维保日。
     *
     * @param context 排程上下文
     * @param cleanTime 清洗时间
     * @return true-维保日；false-非维保日
     */
    private boolean isSandBlastMaintenanceDate(LhScheduleContext context, Date cleanTime) {
        String maintenanceDates = context.getParamValue(LhScheduleParamConstant.SAND_BLAST_MAINTENANCE_DATES,
                LhScheduleConstant.SAND_BLAST_MAINTENANCE_DATES);
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(cleanTime);
        String dayOfMonth = String.valueOf(calendar.get(Calendar.DAY_OF_MONTH));
        for (String maintenanceDate : maintenanceDates.split(VALUE_SEPARATOR)) {
            if (StringUtils.equals(dayOfMonth, maintenanceDate.trim())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断是否为周日。
     *
     * @param cleanTime 清洗时间
     * @return true-周日；false-非周日
     */
    private boolean isSunday(Date cleanTime) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(cleanTime);
        return calendar.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY;
    }

    /**
     * 判断是否为节假日。
     *
     * @param context 排程上下文
     * @param cleanTime 清洗时间
     * @return true-节假日；false-非节假日
     */
    private boolean isHoliday(LhScheduleContext context, Date cleanTime) {
        if (CollectionUtils.isEmpty(context.getWorkCalendarList())) {
            return false;
        }
        String cleanDate = LhScheduleTimeUtil.formatDate(cleanTime);
        for (MdmWorkCalendar workCalendar : context.getWorkCalendarList()) {
            if (Objects.isNull(workCalendar)
                    || Objects.isNull(workCalendar.getProductionDate())
                    || !StringUtils.equals(cleanDate, LhScheduleTimeUtil.formatDate(workCalendar.getProductionDate()))) {
                continue;
            }
            return StringUtils.equals(WORK_CALENDAR_STOP_FLAG, workCalendar.getDayFlag());
        }
        return false;
    }

    /**
     * 解析 HH:mm 为当天分钟数。
     *
     * @param timeText 时间文本
     * @return 当天分钟数
     */
    private int parseTimeMinutes(String timeText) {
        if (StringUtils.isEmpty(timeText)) {
            throw new IllegalArgumentException("干冰清洗时间范围配置为空");
        }
        String[] parts = timeText.trim().split(TIME_SEPARATOR);
        if (parts.length != TIME_PART_COUNT) {
            throw new IllegalArgumentException("干冰清洗时间范围配置格式错误: " + timeText);
        }
        return Integer.parseInt(parts[0]) * MINUTES_PER_HOUR + Integer.parseInt(parts[1]);
    }

    /**
     * 获取指定时间的当天分钟数。
     *
     * @param date 时间
     * @return 当天分钟数
     */
    private int resolveDayMinutes(Date date) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(date);
        return calendar.get(Calendar.HOUR_OF_DAY) * MINUTES_PER_HOUR + calendar.get(Calendar.MINUTE);
    }

    /**
     * 按当天分钟数构建时间。
     *
     * @param baseDate 基准日期
     * @param minutes 当天分钟数
     * @return 时间
     */
    private Date buildTimeByMinutes(Date baseDate, int minutes) {
        return LhScheduleTimeUtil.buildTime(
                baseDate, minutes / MINUTES_PER_HOUR, minutes % MINUTES_PER_HOUR, 0);
    }

    /**
     * 解析清洗窗口的有效开始时刻。
     * <p>若清洗窗口与机台计划停机窗口重叠，则将清洗开始顺延到命中停机窗口的结束时刻。</p>
     *
     * @param context 排程上下文
     * @param machineCode 机台编码
     * @param originalStartTime 原始清洗开始时刻
     * @param cleanDurationHours 清洗时长（小时）
     * @return 顺延后的清洗开始时刻
     */
    private Date resolveAdjustedCleaningStartTime(LhScheduleContext context,
                                                  String machineCode,
                                                  Date originalStartTime,
                                                  int cleanDurationHours) {
        if (context == null
                || originalStartTime == null
                || cleanDurationHours <= 0
                || StringUtils.isEmpty(machineCode)
                || context.getDevicePlanShutList() == null
                || context.getDevicePlanShutList().isEmpty()) {
            return originalStartTime;
        }
        Date adjustedStartTime = originalStartTime;
        while (true) {
            Date adjustedEndTime = LhScheduleTimeUtil.addHours(adjustedStartTime, cleanDurationHours);
            Date latestOverlapStopEndTime = null;
            for (MdmDevicePlanShut planShut : context.getDevicePlanShutList()) {
                if (planShut == null
                        || !StringUtils.equals(machineCode, planShut.getMachineCode())
                        || planShut.getBeginDate() == null
                        || planShut.getEndDate() == null
                        || !planShut.getBeginDate().before(planShut.getEndDate())) {
                    continue;
                }
                // 命中重叠：停机开始早于清洗结束，且停机结束晚于清洗开始。
                if (planShut.getBeginDate().before(adjustedEndTime)
                        && planShut.getEndDate().after(adjustedStartTime)) {
                    latestOverlapStopEndTime = later(latestOverlapStopEndTime, planShut.getEndDate());
                }
            }
            if (latestOverlapStopEndTime == null || !latestOverlapStopEndTime.after(adjustedStartTime)) {
                return adjustedStartTime;
            }
            // 顺延到重叠停机窗口结束后，继续判断是否命中后续停机窗口。
            adjustedStartTime = latestOverlapStopEndTime;
        }
    }

    private Date later(Date current, Date candidate) {
        if (current == null) {
            return candidate;
        }
        if (candidate == null) {
            return current;
        }
        return candidate.after(current) ? candidate : current;
    }

    /**
     * 判断机台当前在机物料是否近收尾，近收尾时跳过清洗避免无效停机。
     * <p>计算口径与收尾判定策略一致：剩余排产天数 = ceil(月计划余量 / 日硫化量)</p>
     *
     * @param context        排程上下文
     * @param cleaningPlan   清洗计划
     * @param machineMaterial 机台当前在机物料编码（由调用方提前查询，避免重复遍历 onlineInfoMap）
     * @return true-近收尾应跳过清洗；false-允许清洗
     * @author APS
     * @since 2026-05-09
     */
    private boolean isMachineEndingSoon(LhScheduleContext context, LhMouldCleanPlan cleaningPlan,
                                        String machineMaterial) {
        if (context == null || cleaningPlan == null || StringUtils.isEmpty(cleaningPlan.getLhCode())) {
            return false;
        }
        int threshold = context.getParamIntValue(LhScheduleParamConstant.CLEANING_SKIP_ENDING_DAY_THRESHOLD,
                LhScheduleConstant.CLEANING_SKIP_ENDING_DAY_THRESHOLD);
        // 阈值为 0 时关闭此特性
        if (threshold <= 0) {
            return false;
        }
        if (StringUtils.isEmpty(machineMaterial)) {
            return false;
        }
        int remainingDays = resolveEndingRemainingDays(context, machineMaterial);
        return remainingDays >= 0 && remainingDays <= threshold;
    }

    /**
     * 计算机台当前在机物料的剩余排产天数。
     * <p>按 月计划余量 / 日硫化量 向上取整，与收尾判定策略规则2口径一致。</p>
     * <p>余量取值：优先使用 differenceQty（月计划定稿中"差异量/未排产数量"），
     * 该字段由月计划流程写入，与 ScheduleAdjustHandler 中 surplusQty = monthPlanQty - finishedQty
     * 语义等价；differenceQty 缺失时回退到 totalQty 作为保守估计。</p>
     *
     * @param context      排程上下文
     * @param materialCode 物料编码
     * @return 剩余排产天数；-1 表示无法判定（无月计划/日产能为0等）
     * @author APS
     * @since 2026-05-09
     */
    private int resolveEndingRemainingDays(LhScheduleContext context, String materialCode) {
        if (context == null || StringUtils.isEmpty(materialCode)) {
            return -1;
        }
        List<FactoryMonthPlanProductionFinalResult> monthPlanList = context.getMonthPlanList();
        if (CollectionUtils.isEmpty(monthPlanList)) {
            return -1;
        }
        FactoryMonthPlanProductionFinalResult matchedPlan = null;
        for (FactoryMonthPlanProductionFinalResult plan : monthPlanList) {
            if (plan != null && StringUtils.equals(materialCode, plan.getMaterialCode())) {
                matchedPlan = plan;
                break;
            }
        }
        if (matchedPlan == null) {
            return -1;
        }
        // 余量优先使用 differenceQty（未排产数量），与 ScheduleAdjustHandler 中 surplusQty 语义等价；
        // differenceQty 缺失时回退到 totalQty 作为保守估计
        int remainingQty = matchedPlan.getDifferenceQty() != null
                ? matchedPlan.getDifferenceQty() : 0;
        if (remainingQty <= 0 && matchedPlan.getTotalQty() != null) {
            remainingQty = Math.max(matchedPlan.getTotalQty(), 0);
        }
        if (remainingQty <= 0) {
            return 0;
        }
        int dailyCapacity = matchedPlan.getDayVulcanizationQty() != null
                ? matchedPlan.getDayVulcanizationQty() : 0;
        if (dailyCapacity <= 0) {
            return -1;
        }
        return (int) Math.ceil((double) remainingQty / dailyCapacity);
    }

    /**
     * 从 MES 在机信息中获取机台当前物料编码。
     *
     * @param context     排程上下文
     * @param machineCode 机台编码
     * @return 物料编码；无在机物料时返回 null
     * @author APS
     * @since 2026-05-09
     */
    private String resolveMachineMaterial(LhScheduleContext context, String machineCode) {
        if (context == null || StringUtils.isEmpty(machineCode)) {
            return null;
        }
        Map<String, LhMachineOnlineInfo> onlineInfoMap = context.getMachineOnlineInfoMap();
        if (CollectionUtils.isEmpty(onlineInfoMap)) {
            return null;
        }
        LhMachineOnlineInfo onlineInfo = onlineInfoMap.get(machineCode);
        if (onlineInfo == null || StringUtils.isEmpty(onlineInfo.getMaterialCode())) {
            return null;
        }
        return onlineInfo.getMaterialCode();
    }
}
