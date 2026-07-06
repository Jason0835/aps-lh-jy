package com.zlt.aps.lh.handler;

import com.zlt.aps.lh.api.constant.LhScheduleConstant;
import com.zlt.aps.lh.api.constant.LhScheduleParamConstant;
import com.zlt.aps.lh.api.domain.dto.MachineCleaningWindowDTO;
import com.zlt.aps.lh.api.domain.dto.MachineScheduleDTO;
import com.zlt.aps.lh.api.domain.dto.ShiftProductionControlDTO;
import com.zlt.aps.lh.api.domain.dto.ValidationResult;
import com.zlt.aps.lh.api.domain.entity.LhMachineInfo;
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
import com.zlt.aps.lh.service.impl.LhCleaningScheduleService;
import com.zlt.aps.lh.service.impl.LhMaintenanceScheduleService;
import com.zlt.aps.lh.service.impl.RollingScheduleHandoffService;
import com.zlt.aps.lh.util.LhSingleControlMachineUtil;
import com.zlt.aps.lh.util.LhScheduleTimeUtil;
import com.zlt.aps.lh.api.domain.entity.LhMachineOnlineInfo;
import com.zlt.aps.lh.api.domain.entity.LhRepairCapsule;
import com.zlt.aps.mdm.api.domain.entity.MdmDevicePlanShut;
import com.zlt.aps.mdm.api.domain.entity.MdmMaterialInfo;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * S4.2 基础数据初始化处理器。
 *
 * <p>业务职责：</p>
 * <ul>
 *   <li>解析本次排程窗口内的班次配置，确定 class1～class8 的运行时含义；</li>
 *   <li>加载月计划、机台、模具、示方、MES在机、胎胚库存、工作日历、停机、保养和清洗等基础数据；</li>
 *   <li>通过数据校验链确认关键基础数据完整；</li>
 *   <li>初始化 {@link MachineScheduleDTO} 机台运行态，供续作、新增、换模、换活字块共享。</li>
 * </ul>
 *
 * @author APS
 */
@Slf4j
@Component
public class DataInitHandler extends AbsScheduleStepHandler {

    @Resource
    private DataValidationChain dataValidationChain;

    @Resource
    private ILhBaseDataService baseDataService;

    @Resource
    private ILhShiftConfigService lhShiftConfigService;

    @Resource
    private RollingScheduleHandoffService rollingScheduleHandoffService;

    @Resource
    private LhCleaningScheduleService cleaningScheduleService;

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

        // S4.2.3.1 准备工作日历与开停产班次管控，供后续机台状态和产能计算统一使用。
        try {
            productionShutdownStrategy.prepareOpenStopContext(context);
        } catch (IllegalArgumentException e) {
            log.error("开停产参数非法: {}", e.getMessage());
            ScheduleDomainExceptionHelper.interrupt(context, ScheduleStepEnum.S4_2_DATA_INIT,
                    ScheduleErrorCode.DATA_INCOMPLETE, e.getMessage());
            return;
        }

        // S4.2.4 封装标准化数据对象（初始化机台排程状态）。
        // 机台运行态会在后续策略中持续修改，因此这里保留 initialMachineScheduleMap 作为基线快照。
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
     * 硫化机台信息、月底计划余量、各班次完成量、物料信息、
     * MES硫化在机信息、硫化定点机台、硫化机胶囊已使用次数、设备保养计划、前日硫化排程结果</p>
     *
     * @param context 排程上下文
     */
    private void loadBaseData(LhScheduleContext context) {
        baseDataService.loadAllBaseData(context);
        log.info("基础数据加载完成, 月计划: {}, 机台: {}, SKU产能: {}, SKU模具关系: {}, MES在机: {}, 前批次结果: {}, 停机计划: {}",
                context.getMonthPlanList().size(), context.getMachineInfoMap().size(),
                context.getSkuLhCapacityMap().size(), context.getSkuMouldRelMap().size(),
                context.getMachineOnlineInfoMap().size(), context.getPreviousScheduleResultList().size(),
                context.getDevicePlanShutList().size());
    }

    /**
     * 将基础数据封装为排程过程中使用的标准化机台状态对象
     * <p>
     * 为每台硫化机台初始化 {@link MachineScheduleDTO}，包含：
     * 机台基本信息、在产规格（从MES在机信息获取）、
         * 设备停机信息、设备停机来源清洗窗口、保养/维修计划、胶囊使用次数等
     * </p>
     *
     * @param context 排程上下文
     */
    private void buildStandardDataObjects(LhScheduleContext context) {
        Map<String, MachineScheduleDTO> machineScheduleMap = new LinkedHashMap<>(context.getMachineInfoMap().size());
        // 先从设备停机计划中过滤干冰/喷砂清洗并生成运行态清洗窗口；
        // 方法内部会把清洗类停机从普通停机列表剥离，避免未纳入清洗上限的记录仍按维修停机扣产能。
        Map<String, List<MachineCleaningWindowDTO>> scheduledCleaningWindowMap =
                getCleaningScheduleService().buildScheduledCleaningWindowMap(context);

        for (Map.Entry<String, LhMachineInfo> entry : context.getMachineInfoMap().entrySet()) {
            String machineCode = entry.getKey();
            LhMachineInfo machineInfo = entry.getValue();
            MachineScheduleDTO dto = new MachineScheduleDTO();
            dto.setMachineCode(machineCode);
            dto.setMachineName(machineInfo.getMachineName());
            // 模台数
            dto.setMaxMoldNum(resolveRuntimeMaxMoldNum(context, machineCode, machineInfo));
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

            // 挂载设备停机计划转换后的清洗窗口，后续班次产能扣减和重叠备注都基于该运行态窗口判断。
            attachCleaningPlanInfo(context, machineCode, dto, scheduledCleaningWindowMap.get(machineCode));

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
     * 解析运行态模台数。
     *
     * @param context 排程上下文
     * @param machineCode 机台编码
     * @param machineInfo 机台信息
     * @return 模台数
     */
    private int resolveRuntimeMaxMoldNum(LhScheduleContext context, String machineCode, LhMachineInfo machineInfo) {
        if (LhSingleControlMachineUtil.isSingleMouldMachine(machineCode)) {
            return 1;
        }
        return machineInfo.getMaxMoldNum() != null ? machineInfo.getMaxMoldNum() : 1;
    }

    /**
     * 填充机台运行态扩展信息。
     *
     * @param context 排程上下文
     * @param machineCode 运行态机台编码
     * @param dto 机台运行态
     * @param scheduledCleaningWindowMap 已排清洗窗口Map
     */
    private void fillMachineRuntimeState(LhScheduleContext context,
                                         String machineCode,
                                         MachineScheduleDTO dto,
                                         Map<String, List<MachineCleaningWindowDTO>> scheduledCleaningWindowMap) {
        dto.setCurrentMaterialCode(null);
        dto.setCurrentMaterialDesc(null);
        dto.setPreviousMaterialCode(null);
        dto.setPreviousMaterialDesc(null);
        LhMachineOnlineInfo onlineInfo = resolveRuntimeOnlineInfo(context, machineCode);
        if (Objects.nonNull(onlineInfo)) {
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

        for (MdmDevicePlanShut planShut : context.getDevicePlanShutList()) {
            if (StringUtils.equals(machineCode, planShut.getMachineCode())) {
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

        attachCleaningPlanInfo(context, machineCode, dto, scheduledCleaningWindowMap.get(machineCode));

        if (context.getCapsuleUsageMap().containsKey(machineCode)) {
            LhRepairCapsule capsule = context.getCapsuleUsageMap().get(machineCode);
            dto.setCapsuleUsageCount(capsule.getReplaceCapsuleCount() != null ? capsule.getReplaceCapsuleCount() : 0);
            dto.setCapsuleUsageCount2(capsule.getReplaceCapsuleCount2() != null ? capsule.getReplaceCapsuleCount2() : 0);
        }

        Arrays.fill(dto.getShiftAvailable(), true);
        applyShiftProductionControl(context, dto);
        dto.setEstimatedEndTime(resolveInitialEstimatedEndTime(context, machineCode));
    }

    /**
     * 挂载长期在机强制下机保养窗口。
     *
     * @param context 排程上下文
     */
    private void attachLongOnlineMaintenanceWindows(LhScheduleContext context) {
        int scheduledCount = 0;
        for (MachineScheduleDTO machine : context.getMachineScheduleMap().values()) {
            if (getMaintenanceScheduleService().tryAttachLongOnlineMaintenance(context, machine)) {
                scheduledCount++;
            }
        }
        log.info("长期在机保养检查完成, 已安排保养机台数: {}", scheduledCount);
    }

    private LhCleaningScheduleService getCleaningScheduleService() {
        return cleaningScheduleService != null
                ? cleaningScheduleService
                : new LhCleaningScheduleService();
    }

    private LhMaintenanceScheduleService getMaintenanceScheduleService() {
        return maintenanceScheduleService != null
                ? maintenanceScheduleService
                : new LhMaintenanceScheduleService();
    }

    private Date resolveInitialEstimatedEndTime(LhScheduleContext context, String machineCode) {
        Date latestSpecEndTime = null;
        LhMachineOnlineInfo onlineInfo = resolveRuntimeOnlineInfo(context, machineCode);
        for (com.zlt.aps.lh.api.domain.entity.LhScheduleResult result : context.getPreviousScheduleResultList()) {
            if (!StringUtils.equals(machineCode, result.getLhMachineCode())
                    || isDifferentOnlineMaterial(onlineInfo, result)
                    || !LhSingleControlMachineUtil.isLeftRightCompatible(machineCode, result.getLeftRightMould())
                    || result.getSpecEndTime() == null) {
                continue;
            }
            if (latestSpecEndTime == null || result.getSpecEndTime().after(latestSpecEndTime)) {
                latestSpecEndTime = result.getSpecEndTime();
            }
        }
        if (latestSpecEndTime != null) {
            Date alignedEndTime = alignForceRescheduleEndTimeToWindowStart(
                    context, machineCode, latestSpecEndTime);
            log.debug("机台初始结束时间取前批次规格结束时间, 机台: {}, 结束时间: {}",
                    machineCode, LhScheduleTimeUtil.formatDateTime(alignedEndTime));
            return alignedEndTime;
        }
        if (Objects.nonNull(onlineInfo)) {
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
     * 强制重排时将窗口外的前批次结束时间对齐到本次排程窗口首班。
     *
     * @param context 排程上下文
     * @param machineCode 机台编码
     * @param previousEndTime 前批次规格结束时间
     * @return 对齐后的机台初始结束时间
     */
    private Date alignForceRescheduleEndTimeToWindowStart(LhScheduleContext context,
                                                          String machineCode,
                                                          Date previousEndTime) {
        if (context.getParamIntValue(LhScheduleParamConstant.FORCE_RESCHEDULE,
                LhScheduleConstant.FORCE_RESCHEDULE) != LhScheduleConstant.FORCE_RESCHEDULE_ENABLED
                || CollectionUtils.isEmpty(context.getScheduleWindowShifts())) {
            return previousEndTime;
        }
        Date windowStartTime = context.getScheduleWindowShifts().stream()
                .map(LhShiftConfigVO::getShiftStartDateTime)
                .filter(Objects::nonNull)
                .min(Date::compareTo)
                .orElse(null);
        if (Objects.isNull(windowStartTime) || !previousEndTime.before(windowStartTime)) {
            return previousEndTime;
        }
        log.info("强制重排机台结束时间早于窗口起点，按首班开始时间归一化, 工厂: {}, 机台: {}, "
                        + "原结束时间: {}, 窗口起点: {}",
                context.getFactoryCode(), machineCode,
                LhScheduleTimeUtil.formatDateTime(previousEndTime),
                LhScheduleTimeUtil.formatDateTime(windowStartTime));
        return windowStartTime;
    }

    /**
     * 判断前批次结果是否与当前 MES 在机物料不一致。
     *
     * @param onlineInfo MES 在机信息
     * @param result 前批次结果
     * @return true-物料不一致
     */
    private boolean isDifferentOnlineMaterial(LhMachineOnlineInfo onlineInfo,
                                              com.zlt.aps.lh.api.domain.entity.LhScheduleResult result) {
        return Objects.nonNull(onlineInfo)
                && StringUtils.isNotEmpty(onlineInfo.getMaterialCode())
                && Objects.nonNull(result)
                && !StringUtils.equals(onlineInfo.getMaterialCode(), result.getMaterialCode());
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

    /**
     * 挂载设备停机来源清洗窗口明细，并回填兼容摘要字段。
     *
     * @param context 排程上下文
     * @param machineCode 机台编号
     * @param dto 机台状态对象
     */
    private void attachCleaningPlanInfo(LhScheduleContext context,
                                        String machineCode,
                                        MachineScheduleDTO dto,
                                        List<MachineCleaningWindowDTO> scheduledCleaningWindowList) {
        List<MachineCleaningWindowDTO> cleaningWindowList = new ArrayList<>();
        if (!CollectionUtils.isEmpty(scheduledCleaningWindowList)) {
            for (MachineCleaningWindowDTO cleaningWindow : scheduledCleaningWindowList) {
                if (cleaningWindow == null
                        || !StringUtils.equals(machineCode, cleaningWindow.getLhCode())
                        || !LhSingleControlMachineUtil.isLeftRightCompatible(machineCode, cleaningWindow.getLeftRightMould())) {
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
        }
        cleaningWindowList.sort(Comparator.comparing(MachineCleaningWindowDTO::getCleanStartTime,
                Comparator.nullsLast(Date::compareTo)));
        dto.setCleaningWindowList(cleaningWindowList);
    }

    private LhMachineOnlineInfo resolveRuntimeOnlineInfo(LhScheduleContext context, String machineCode) {
        if (!context.getMachineOnlineInfoMap().containsKey(machineCode)) {
            return null;
        }
        LhMachineOnlineInfo onlineInfo = context.getMachineOnlineInfoMap().get(machineCode);
        if (!LhSingleControlMachineUtil.isLeftRightCompatible(machineCode, onlineInfo.getLrMolds())) {
            return null;
        }
        return onlineInfo;
    }

    @Override
    protected String getStepName() {
        return ScheduleStepEnum.S4_2_DATA_INIT.getDescription();
    }
}
