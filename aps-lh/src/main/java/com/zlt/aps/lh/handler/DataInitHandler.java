package com.zlt.aps.lh.handler;

import com.zlt.aps.lh.api.constant.LhScheduleConstant;
import com.zlt.aps.lh.api.constant.LhScheduleParamConstant;
import com.zlt.aps.lh.api.domain.dto.MachineCleaningWindowDTO;
import com.zlt.aps.lh.api.domain.dto.MachineScheduleDTO;
import com.zlt.aps.lh.api.domain.dto.ShiftProductionControlDTO;
import com.zlt.aps.lh.api.domain.dto.ValidationResult;
import com.zlt.aps.lh.api.domain.entity.LhMachineInfo;
import com.zlt.aps.lh.api.domain.entity.LhMouldCleanPlan;
import com.zlt.aps.lh.api.domain.vo.LhShiftConfigVO;
import com.zlt.aps.lh.api.enums.CleaningTypeEnum;
import com.zlt.aps.lh.api.enums.MachineStopTypeEnum;
import com.zlt.aps.lh.api.enums.ScheduleStepEnum;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.engine.chain.DataValidationChain;
import com.zlt.aps.lh.engine.strategy.IProductionShutdownStrategy;
import com.zlt.aps.lh.exception.ScheduleDomainExceptionHelper;
import com.zlt.aps.lh.exception.ScheduleErrorCode;
import com.zlt.aps.lh.service.ILhBaseDataService;
import com.zlt.aps.lh.service.ILhShiftConfigService;
import com.zlt.aps.lh.service.impl.LhMaintenanceScheduleService;
import com.zlt.aps.lh.service.impl.RollingScheduleHandoffService;
import com.zlt.aps.lh.util.LhScheduleTimeUtil;
import com.zlt.aps.mdm.api.domain.entity.MdmDevicePlanShut;
import com.zlt.aps.lh.api.domain.entity.LhMachineOnlineInfo;
import com.zlt.aps.lh.api.domain.entity.LhRepairCapsule;
import com.zlt.aps.mdm.api.domain.entity.MdmMaterialInfo;
import com.zlt.aps.mdm.api.domain.entity.MdmSkuMouldRel;
import com.zlt.aps.mdm.api.domain.entity.MdmWorkCalendar;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * S4.2 基础数据初始化处理器
 * <p>加载所有基础数据并通过责任链校验，封装成排程可用的对象结构</p>
 *
 * @author APS
 */
@Slf4j
@Component
public class DataInitHandler extends AbsScheduleStepHandler {

    /** 手工录入清洗计划 */
    private static final String CLEANING_DATA_SOURCE_MANUAL = "0";
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

    @Resource
    private DataValidationChain dataValidationChain;

    @Resource
    private ILhBaseDataService baseDataService;

    @Resource
    private ILhShiftConfigService lhShiftConfigService;

    @Resource
    private RollingScheduleHandoffService rollingScheduleHandoffService;

    @Resource
    private LhMaintenanceScheduleService maintenanceScheduleService;

    @Resource
    private IProductionShutdownStrategy productionShutdownStrategy;

    @Override
    protected void doHandle(LhScheduleContext context) {
        log.info("基础数据初始化开始, 工厂: {}, 目标日: {}, T日: {}, 月计划版本: {}",
                context.getFactoryCode(),
                LhScheduleTimeUtil.formatDate(context.getScheduleTargetDate()),
                LhScheduleTimeUtil.formatDate(context.getScheduleDate()),
                context.getMonthPlanVersion());
        // S4.2.1 解析班次配置（无表数据则用默认模板），并写入上下文
        try {
            lhShiftConfigService.resolveAndAttachScheduleShifts(context);
            List<LhShiftConfigVO> scheduleWindowShifts = context.getScheduleWindowShifts();
            log.info("班次窗口解析完成, 班次数: {}, 起始班次: {}, 结束班次: {}",
                    scheduleWindowShifts.size(),
                    CollectionUtils.isEmpty(scheduleWindowShifts) ? null : scheduleWindowShifts.get(0).getShiftName(),
                    CollectionUtils.isEmpty(scheduleWindowShifts) ? null
                            : scheduleWindowShifts.get(scheduleWindowShifts.size() - 1).getShiftName());
        } catch (IllegalArgumentException e) {
            log.error("班次配置非法: {}", e.getMessage());
            ScheduleDomainExceptionHelper.interrupt(context, ScheduleStepEnum.S4_2_DATA_INIT,
                    ScheduleErrorCode.DATA_INCOMPLETE, "班次配置非法: " + e.getMessage());
            return;
        }

        // S4.2.2 加载所有基础数据
        loadBaseData(context);
        if (context.isInterrupted()) {
            return;
        }

        // S4.2.3 执行数据校验链（组内聚合模式会收集全部错误后再失败）
        ValidationResult result = dataValidationChain.validateWithResult(context);
        if (result.isFailed()) {
            log.warn("数据校验未通过，共 {} 条错误，明细: {}", result.getErrors().size(), result.getFormattedErrors());
            ScheduleDomainExceptionHelper.interrupt(context, ScheduleStepEnum.S4_2_DATA_INIT,
                    ScheduleErrorCode.DATA_INCOMPLETE, result.getSummaryMessage(), result.getErrors());
            return;
        }

        // S4.2.3.1 校验清洗计划配置约束，仅校验表数据，不生成或回写清洗计划。
        validateCleaningPlanRules(context);
        if (context.isInterrupted()) {
            return;
        }

        // S4.2.3.2 准备工作日历与开停产班次管控，供后续机台状态和产能计算统一使用。
        try {
            productionShutdownStrategy.prepareOpenStopContext(context);
        } catch (IllegalArgumentException e) {
            log.error("开停产参数非法: {}", e.getMessage());
            ScheduleDomainExceptionHelper.interrupt(context, ScheduleStepEnum.S4_2_DATA_INIT,
                    ScheduleErrorCode.DATA_INCOMPLETE, e.getMessage());
            return;
        }

        // S4.2.4 封装标准化数据对象（初始化机台排程状态）
        buildStandardDataObjects(context);

        List<LhShiftConfigVO> windowShifts = context.getScheduleWindowShifts();
        LhScheduleTimeUtil.initShiftRuntimeStateMap(context, windowShifts);
        // 强制重排时保留窗口基础数据，跳过前批次继承，从窗口起点重新计算。
        if (context.getScheduleConfig().isForceRescheduleEnabled()) {
            log.info("启用强制重排模式，跳过滚动排程衔接");
        } else {
            // 滚动排程衔接：将前批次重叠班次继承到本次，推进机台状态
            rollingScheduleHandoffService.apply(context);
            if (context.isInterrupted()) {
                return;
            }
        }
        attachLongOnlineMaintenanceWindows(context);

        log.info("基础数据初始化完成, 机台数量: {}, 月计划SKU数: {}",
                context.getMachineInfoMap().size(), context.getMonthPlanList().size());
    }

    /**
     * 从数据库加载所有排程所需基础数据
     * <p>包括排产版本、月生产计划、工作日历、SKU日硫化产能、设备停机计划、SKU与模具关系、
     * 硫化机台信息、模具清洗计划、月底计划余量、各班次完成量、物料信息、
     * MES硫化在机信息、硫化定点机台、硫化机胶囊已使用次数、设备保养计划、前日硫化排程结果</p>
     *
     * @param context 排程上下文
     */
    private void loadBaseData(LhScheduleContext context) {
        baseDataService.loadAllBaseData(context);
        log.info("基础数据加载完成, 月计划: {}, 机台: {}, SKU产能: {}, SKU模具关系: {}, MES在机: {}, 前批次结果: {}, 停机计划: {}, 清洗计划: {}",
                context.getMonthPlanList().size(), context.getMachineInfoMap().size(),
                context.getSkuLhCapacityMap().size(), context.getSkuMouldRelMap().size(),
                context.getMachineOnlineInfoMap().size(), context.getPreviousScheduleResultList().size(),
                context.getDevicePlanShutList().size(), context.getCleaningPlanList().size());
    }

    /**
     * 将基础数据封装为排程过程中使用的标准化机台状态对象
     * <p>
     * 为每台硫化机台初始化 {@link MachineScheduleDTO}，包含：
     * 机台基本信息、在产规格（从MES在机信息获取）、
     * 设备停机信息、清洗计划、保养/维修计划、胶囊使用次数等
     * </p>
     *
     * @param context 排程上下文
     */
    private void buildStandardDataObjects(LhScheduleContext context) {
        Map<String, MachineScheduleDTO> machineScheduleMap = new LinkedHashMap<>(context.getMachineInfoMap().size());

        for (Map.Entry<String, LhMachineInfo> entry : context.getMachineInfoMap().entrySet()) {
            String machineCode = entry.getKey();
            LhMachineInfo machineInfo = entry.getValue();

            MachineScheduleDTO dto = new MachineScheduleDTO();
            dto.setMachineCode(machineCode);
            dto.setMachineName(machineInfo.getMachineName());
            // 模台数
            dto.setMaxMoldNum(machineInfo.getMaxMoldNum() != null ? machineInfo.getMaxMoldNum() : 1);
            dto.setStatus(machineInfo.getStatus());
            dto.setDimensionMinimum(machineInfo.getDimensionMinimum());
            dto.setDimensionMaximum(machineInfo.getDimensionMaximum());
            dto.setMachineOrder(machineInfo.getMachineOrder() != null ? machineInfo.getMachineOrder() : 0);
            dto.setShellStandard(machineInfo.getShellStandard());
            dto.setSupport195WideBase(machineInfo.getSupport195WideBase());
            dto.setSupport225WideBase(machineInfo.getSupport225WideBase());
            dto.setSupportChipTire(machineInfo.getSupportChipTire());

            // 初始化在产规格（来自MES在机信息）
            dto.setCurrentMaterialCode(null);
            dto.setCurrentMaterialDesc(null);
            dto.setPreviousMaterialCode(null);
            dto.setPreviousMaterialDesc(null);
            if (context.getMachineOnlineInfoMap().containsKey(machineCode)) {
                LhMachineOnlineInfo onlineInfo = context.getMachineOnlineInfoMap().get(machineCode);
                dto.setCurrentMaterialCode(onlineInfo.getMaterialCode());
                dto.setCurrentMaterialDesc(onlineInfo.getSpecDesc());
                log.debug("机台MES在机信息匹配, 机台: {}, materialCode: {}, 规格描述: {}",
                        machineCode, onlineInfo.getMaterialCode(), onlineInfo.getSpecDesc());
                MdmMaterialInfo currentMaterial = context.getMaterialInfoMap().get(onlineInfo.getMaterialCode());
                if (currentMaterial != null) {
                    dto.setCurrentMaterialDesc(currentMaterial.getMaterialDesc());
                    dto.setPreviousSpecCode(currentMaterial.getSpecifications());
                    dto.setPreviousProSize(currentMaterial.getProSize());
                }
            }

            // 初始化设备停机与维修信息（取 beginDate 最早的为准）
            for (MdmDevicePlanShut planShut : context.getDevicePlanShutList()) {
                if (machineCode.equals(planShut.getMachineCode())) {
                    if (dto.getPlanStopStartTime() == null
                            || (planShut.getBeginDate() != null && planShut.getBeginDate().before(dto.getPlanStopStartTime()))) {
                        dto.setPlanStopStartTime(planShut.getBeginDate());
                        dto.setPlanStopEndTime(planShut.getEndDate());
                        dto.setStopType(planShut.getMachineStopType());
                    }
                    MachineStopTypeEnum stopTypeEnum = MachineStopTypeEnum.getByCode(planShut.getMachineStopType());
                    // 计划性维修仅保留停机窗口，避免在初始化阶段直接抬高机台准备就绪时间。
                    if (stopTypeEnum == MachineStopTypeEnum.TEMPORARY_FAULT) {
                        dto.setHasRepairPlan(true);
                        dto.setRepairPlanTime(earlier(dto.getRepairPlanTime(), planShut.getBeginDate()));
                    }
                }
            }

            // 初始化仅保留精度保养计划基础数据，实际保养窗口在排程触发点动态挂载。

            // 初始化清洗计划
            attachCleaningPlanInfo(context, machineCode, dto);

            // 初始化胶囊使用次数
            if (context.getCapsuleUsageMap().containsKey(machineCode)) {
                LhRepairCapsule capsule = context.getCapsuleUsageMap().get(machineCode);
                dto.setCapsuleUsageCount(capsule.getReplaceCapsuleCount() != null ? capsule.getReplaceCapsuleCount() : 0);
                dto.setCapsuleUsageCount2(capsule.getReplaceCapsuleCount2() != null ? capsule.getReplaceCapsuleCount2() : 0);
            }

            // 初始化各班次可用状态（默认全部可用）
            Arrays.fill(dto.getShiftAvailable(), true);
            applyShiftProductionControl(context, dto);
            dto.setEstimatedEndTime(resolveInitialEstimatedEndTime(context, machineCode));

            machineScheduleMap.put(machineCode, dto);
        }

        context.setMachineScheduleMap(machineScheduleMap);
        context.setInitialMachineScheduleMap(copyMachineStateMap(machineScheduleMap));
        log.info("机台排程状态对象封装完成, 机台数量: {}", machineScheduleMap.size());
    }

    /**
     * 挂载长期在机强制下机保养窗口。
     *
     * @param context 排程上下文
     */
    private void attachLongOnlineMaintenanceWindows(LhScheduleContext context) {
        int scheduledCount = 0;
        for (MachineScheduleDTO machine : context.getMachineScheduleMap().values()) {
            if (maintenanceScheduleService.tryAttachLongOnlineMaintenance(context, machine)) {
                scheduledCount++;
            }
        }
        log.info("长期在机保养检查完成, 已安排保养机台数: {}", scheduledCount);
    }

    private Date resolveInitialEstimatedEndTime(LhScheduleContext context, String machineCode) {
        Date latestSpecEndTime = null;
        for (com.zlt.aps.lh.api.domain.entity.LhScheduleResult result : context.getPreviousScheduleResultList()) {
            if (!machineCode.equals(result.getLhMachineCode()) || result.getSpecEndTime() == null) {
                continue;
            }
            if (latestSpecEndTime == null || result.getSpecEndTime().after(latestSpecEndTime)) {
                latestSpecEndTime = result.getSpecEndTime();
            }
        }
        if (latestSpecEndTime != null) {
            log.debug("机台初始结束时间取前批次规格结束时间, 机台: {}, 结束时间: {}",
                    machineCode, LhScheduleTimeUtil.formatDateTime(latestSpecEndTime));
            return latestSpecEndTime;
        }
        if (context.getMachineOnlineInfoMap().containsKey(machineCode)) {
            List<LhShiftConfigVO> shifts = context.getScheduleWindowShifts();
            if (!shifts.isEmpty() && shifts.get(0).getShiftStartDateTime() != null) {
                log.debug("机台初始结束时间取窗口首班开始, 机台: {}, 原因: MES在机无前批次结束时间, 时间: {}",
                        machineCode, LhScheduleTimeUtil.formatDateTime(shifts.get(0).getShiftStartDateTime()));
                return shifts.get(0).getShiftStartDateTime();
            }
        }
        List<LhShiftConfigVO> shifts = context.getScheduleWindowShifts();
        if (!shifts.isEmpty() && shifts.get(0).getShiftStartDateTime() != null) {
            log.debug("机台初始结束时间取窗口首班开始, 机台: {}, 时间: {}",
                    machineCode, LhScheduleTimeUtil.formatDateTime(shifts.get(0).getShiftStartDateTime()));
            return shifts.get(0).getShiftStartDateTime();
        }
        log.warn("机台初始结束时间未匹配班次窗口, 机台: {}, 使用T日: {}",
                machineCode, LhScheduleTimeUtil.formatDate(context.getScheduleDate()));
        return context.getScheduleDate();
    }

    /**
     * 按工作日历和开停产管控更新机台班次可用状态。
     *
     * @param context 排程上下文
     * @param dto 机台状态对象
     * @return void
     */
    private void applyShiftProductionControl(LhScheduleContext context, MachineScheduleDTO dto) {
        if (Objects.isNull(context) || Objects.isNull(dto) || CollectionUtils.isEmpty(context.getShiftProductionControlMap())) {
            return;
        }
        for (Map.Entry<Integer, ShiftProductionControlDTO> entry : context.getShiftProductionControlMap().entrySet()) {
            int shiftIndex = entry.getKey();
            if (shiftIndex <= 0 || shiftIndex >= dto.getShiftAvailable().length) {
                continue;
            }
            dto.getShiftAvailable()[shiftIndex] = entry.getValue().isCanSchedule();
        }
    }

    private Map<String, MachineScheduleDTO> copyMachineStateMap(Map<String, MachineScheduleDTO> sourceMap) {
        Map<String, MachineScheduleDTO> snapshot = new LinkedHashMap<>(sourceMap.size());
        for (Map.Entry<String, MachineScheduleDTO> entry : sourceMap.entrySet()) {
            MachineScheduleDTO source = entry.getValue();
            MachineScheduleDTO copy = new MachineScheduleDTO();
            copy.setMachineCode(source.getMachineCode());
            copy.setMachineName(source.getMachineName());
            copy.setCurrentMaterialCode(source.getCurrentMaterialCode());
            copy.setCurrentMaterialDesc(source.getCurrentMaterialDesc());
            copy.setPreviousMaterialCode(source.getPreviousMaterialCode());
            copy.setPreviousMaterialDesc(source.getPreviousMaterialDesc());
            copy.setPreviousSpecCode(source.getPreviousSpecCode());
            copy.setPreviousProSize(source.getPreviousProSize());
            copy.setEstimatedEndTime(source.getEstimatedEndTime());
            snapshot.put(entry.getKey(), copy);
        }
        return snapshot;
    }

    private Date earlier(Date current, Date candidate) {
        if (candidate == null) {
            return current;
        }
        if (current == null || candidate.before(current)) {
            return candidate;
        }
        return current;
    }

    private Date later(Date current, Date candidate) {
        if (candidate == null) {
            return current;
        }
        if (current == null || candidate.after(current)) {
            return candidate;
        }
        return current;
    }

    /**
     * 挂载机台清洗计划明细，并回填兼容摘要字段。
     *
     * @param context 排程上下文
     * @param machineCode 机台编号
     * @param dto 机台状态对象
     */
    private void attachCleaningPlanInfo(LhScheduleContext context, String machineCode, MachineScheduleDTO dto) {
        List<MachineCleaningWindowDTO> cleaningWindowList = new ArrayList<>();
        for (LhMouldCleanPlan cleaningPlan : context.getCleaningPlanList()) {
            if (!machineCode.equals(cleaningPlan.getLhCode())) {
                continue;
            }
            MachineCleaningWindowDTO cleaningWindow = buildCleaningWindow(context, cleaningPlan);
            if (cleaningWindow == null) {
                continue;
            }
            cleaningWindowList.add(cleaningWindow);
            if (CleaningTypeEnum.DRY_ICE.getCode().equals(cleaningWindow.getCleanType())) {
                dto.setHasDryIceCleaning(true);
            }
            if (CleaningTypeEnum.SAND_BLAST.getCode().equals(cleaningWindow.getCleanType())) {
                dto.setHasSandBlastCleaning(true);
            }
            dto.setCleaningPlanTime(earlier(dto.getCleaningPlanTime(), cleaningWindow.getCleanStartTime()));
        }
        cleaningWindowList.sort(Comparator.comparing(MachineCleaningWindowDTO::getCleanStartTime,
                Comparator.nullsLast(Date::compareTo)));
        dto.setCleaningWindowList(cleaningWindowList);
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
        int cleanDurationHours = 0;
        int readyDurationHours = 0;
        Date originalCleanStartTime = cleaningPlan.getCleanTime();
        if (CleaningTypeEnum.DRY_ICE.getCode().equals(cleanType)) {
            cleanDurationHours = context.getParamIntValue(LhScheduleParamConstant.DRY_ICE_DURATION_HOURS,
                    LhScheduleConstant.DRY_ICE_DURATION_HOURS);
            readyDurationHours = cleanDurationHours;
            originalCleanStartTime = resolveDryIceWindowStartTime(context, cleaningPlan.getCleanTime());
        } else if (CleaningTypeEnum.SAND_BLAST.getCode().equals(cleanType)) {
            cleanDurationHours = context.getParamIntValue(LhScheduleParamConstant.SAND_BLAST_WITH_INSPECTION_HOURS,
                    LhScheduleConstant.SAND_BLAST_WITH_INSPECTION_HOURS);
            // 喷砂总停机口径用于班次扣减；机台再次可开产时间仍沿用喷砂清洗原时长，
            // 后续换模/换活字块时间继续由各自排产链路单独叠加，避免重复计时。
            readyDurationHours = context.getParamIntValue(LhScheduleParamConstant.SAND_BLAST_DURATION_HOURS,
                    LhScheduleConstant.SAND_BLAST_DURATION_HOURS);
        } else {
            return null;
        }

        // 清洗计划与设备计划停机重叠时，清洗必须顺延到停机结束后执行。
        Date adjustedCleanStartTime = resolveAdjustedCleaningStartTime(
                context, cleaningPlan.getLhCode(), originalCleanStartTime, cleanDurationHours);

        MachineCleaningWindowDTO cleaningWindow = new MachineCleaningWindowDTO();
        cleaningWindow.setLhCode(cleaningPlan.getLhCode());
        cleaningWindow.setCleanType(cleanType);
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
     * 校验模具清洗计划约束。
     *
     * @param context 排程上下文
     */
    private void validateCleaningPlanRules(LhScheduleContext context) {
        if (Objects.isNull(context) || CollectionUtils.isEmpty(context.getCleaningPlanList())) {
            return;
        }
        List<String> errorList = new ArrayList<>();
        validateDryIceCleaningLimit(context, errorList);
        validateSandBlastCleaningLimit(context, errorList);
        if (!CollectionUtils.isEmpty(errorList)) {
            ScheduleDomainExceptionHelper.interrupt(context, ScheduleStepEnum.S4_2_DATA_INIT,
                    ScheduleErrorCode.DATA_INCOMPLETE, "模具清洗计划不满足排程约束", errorList);
        }
    }

    /**
     * 校验干冰清洗台数约束。
     *
     * @param context 排程上下文
     * @param errorList 错误列表
     */
    private void validateDryIceCleaningLimit(LhScheduleContext context, List<String> errorList) {
        Map<String, List<LhMouldCleanPlan>> datePlanMap = new HashMap<>(16);
        Map<String, List<LhMouldCleanPlan>> morningPlanMap = new HashMap<>(16);
        Map<String, List<LhMouldCleanPlan>> afternoonPlanMap = new HashMap<>(16);
        for (LhMouldCleanPlan cleaningPlan : context.getCleaningPlanList()) {
            if (!isValidCleaningPlan(cleaningPlan, CleaningTypeEnum.DRY_ICE.getCode())) {
                continue;
            }
            Date windowStartTime = resolveDryIceWindowStartTime(context, cleaningPlan.getCleanTime());
            String dateKey = LhScheduleTimeUtil.formatDate(windowStartTime);
            datePlanMap.computeIfAbsent(dateKey, key -> new ArrayList<>()).add(cleaningPlan);
            if (LhScheduleTimeUtil.isMorningShift(context, windowStartTime)) {
                morningPlanMap.computeIfAbsent(dateKey, key -> new ArrayList<>()).add(cleaningPlan);
            } else if (LhScheduleTimeUtil.isAfternoonShift(context, windowStartTime)) {
                afternoonPlanMap.computeIfAbsent(dateKey, key -> new ArrayList<>()).add(cleaningPlan);
            }
        }
        appendLimitErrors(errorList, datePlanMap,
                context.getParamIntValue(LhScheduleParamConstant.DRY_ICE_DAILY_LIMIT, LhScheduleConstant.DRY_ICE_DAILY_LIMIT),
                "干冰清洗同日数量超过上限");
        appendLimitErrors(errorList, morningPlanMap,
                context.getParamIntValue(LhScheduleParamConstant.DRY_ICE_MORNING_SHIFT_LIMIT,
                        LhScheduleConstant.DRY_ICE_MORNING_SHIFT_LIMIT),
                "干冰清洗早班数量超过上限");
        appendLimitErrors(errorList, afternoonPlanMap,
                context.getParamIntValue(LhScheduleParamConstant.DRY_ICE_AFTERNOON_SHIFT_LIMIT,
                        LhScheduleConstant.DRY_ICE_AFTERNOON_SHIFT_LIMIT),
                "干冰清洗中班数量超过上限");
    }

    /**
     * 校验喷砂清洗约束。
     *
     * @param context 排程上下文
     * @param errorList 错误列表
     */
    private void validateSandBlastCleaningLimit(LhScheduleContext context, List<String> errorList) {
        Map<String, List<LhMouldCleanPlan>> datePlanMap = new HashMap<>(16);
        for (LhMouldCleanPlan cleaningPlan : context.getCleaningPlanList()) {
            if (!isValidCleaningPlan(cleaningPlan, CleaningTypeEnum.SAND_BLAST.getCode())) {
                continue;
            }
            String dateKey = LhScheduleTimeUtil.formatDate(cleaningPlan.getCleanTime());
            datePlanMap.computeIfAbsent(dateKey, key -> new ArrayList<>()).add(cleaningPlan);
            validateSandBlastForbiddenDate(context, cleaningPlan, errorList);
        }
        appendLimitErrors(errorList, datePlanMap,
                context.getParamIntValue(LhScheduleParamConstant.SAND_BLAST_DAILY_LIMIT,
                        LhScheduleConstant.SAND_BLAST_DAILY_LIMIT),
                "喷砂清洗同日数量超过上限");
    }

    /**
     * 校验喷砂清洗禁排日期。
     *
     * @param context 排程上下文
     * @param cleaningPlan 清洗计划
     * @param errorList 错误列表
     */
    private void validateSandBlastForbiddenDate(LhScheduleContext context,
                                                LhMouldCleanPlan cleaningPlan,
                                                List<String> errorList) {
        Date cleanTime = cleaningPlan.getCleanTime();
        boolean manualPlan = StringUtils.equals(CLEANING_DATA_SOURCE_MANUAL, cleaningPlan.getDataSource());
        boolean maintenanceDateAllowed = manualPlan
                && context.getParamIntValue(LhScheduleParamConstant.SAND_BLAST_ALLOW_ON_MAINTENANCE_DATE,
                LhScheduleConstant.SAND_BLAST_ALLOW_ON_MAINTENANCE_DATE) == ENABLED;
        if (isSandBlastMaintenanceDate(context, cleanTime) && !maintenanceDateAllowed) {
            errorList.add(buildCleaningError("喷砂清洗命中喷砂机维保日", cleaningPlan));
        }
        if (isSunday(cleanTime)
                && context.getParamIntValue(LhScheduleParamConstant.SAND_BLAST_SKIP_SUNDAY_ENABLED,
                LhScheduleConstant.SAND_BLAST_SKIP_SUNDAY_ENABLED) == ENABLED
                && !isManualSundaySandBlastAllowed(context, manualPlan, cleanTime)) {
            errorList.add(buildCleaningError("喷砂清洗命中周日", cleaningPlan));
        }
        if (isHoliday(context, cleanTime)
                && context.getParamIntValue(LhScheduleParamConstant.SAND_BLAST_SKIP_HOLIDAY_ENABLED,
                LhScheduleConstant.SAND_BLAST_SKIP_HOLIDAY_ENABLED) == ENABLED) {
            errorList.add(buildCleaningError("喷砂清洗命中节假日", cleaningPlan));
        }
    }

    /**
     * 判断手工周日喷砂是否允许。
     *
     * @param context 排程上下文
     * @param manualPlan 是否手工计划
     * @param cleanTime 清洗时间
     * @return true-允许；false-不允许
     */
    private boolean isManualSundaySandBlastAllowed(LhScheduleContext context, boolean manualPlan, Date cleanTime) {
        if (!manualPlan || context.getParamIntValue(LhScheduleParamConstant.SAND_BLAST_ALLOW_SUNDAY_MANUAL_ENABLED,
                LhScheduleConstant.SAND_BLAST_ALLOW_SUNDAY_MANUAL_ENABLED) != ENABLED) {
            return false;
        }
        // 当前阶段尚未生成本批次交替计划，周日喷砂阈值改在结果校验阶段按真实交替计划数量执行。
        return true;
    }

    /**
     * 追加数量上限错误。
     *
     * @param errorList 错误列表
     * @param datePlanMap 日期计划Map
     * @param limit 上限
     * @param errorPrefix 错误前缀
     */
    private void appendLimitErrors(List<String> errorList,
                                   Map<String, List<LhMouldCleanPlan>> datePlanMap,
                                   int limit,
                                   String errorPrefix) {
        for (Map.Entry<String, List<LhMouldCleanPlan>> entry : datePlanMap.entrySet()) {
            List<LhMouldCleanPlan> planList = entry.getValue();
            if (CollectionUtils.isEmpty(planList) || planList.size() <= limit) {
                continue;
            }
            errorList.add(errorPrefix + ", 日期: " + entry.getKey()
                    + ", 上限: " + limit
                    + ", 机台: " + formatMachineCodes(planList));
        }
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
                && StringUtils.equals(cleanType, cleaningPlan.getCleanType());
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
     * 构建清洗计划错误提示。
     *
     * @param reason 原因
     * @param cleaningPlan 清洗计划
     * @return 错误提示
     */
    private String buildCleaningError(String reason, LhMouldCleanPlan cleaningPlan) {
        return reason + ", 日期: " + LhScheduleTimeUtil.formatDate(cleaningPlan.getCleanTime())
                + ", 机台: " + cleaningPlan.getLhCode();
    }

    /**
     * 格式化机台编码列表。
     *
     * @param planList 清洗计划列表
     * @return 机台编码文本
     */
    private String formatMachineCodes(List<LhMouldCleanPlan> planList) {
        return planList.stream()
                .map(LhMouldCleanPlan::getLhCode)
                .filter(StringUtils::isNotEmpty)
                .collect(Collectors.joining(VALUE_SEPARATOR));
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

    @Override
    protected String getStepName() {
        return ScheduleStepEnum.S4_2_DATA_INIT.getDescription();
    }
}
