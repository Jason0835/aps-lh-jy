package com.zlt.aps.lh.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.zlt.aps.lh.api.constant.LhScheduleConstant;
import com.zlt.aps.lh.api.domain.dto.MachineScheduleDTO;
import com.zlt.aps.lh.api.domain.entity.LhMouldChangePlan;
import com.zlt.aps.lh.api.domain.entity.LhScheduleResult;
import com.zlt.aps.lh.api.domain.vo.LhShiftConfigVO;
import com.zlt.aps.lh.api.enums.ScheduleStepEnum;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.exception.ScheduleDomainExceptionHelper;
import com.zlt.aps.lh.exception.ScheduleErrorCode;
import com.zlt.aps.lh.util.LhScheduleTimeUtil;
import com.zlt.aps.lh.util.ShiftFieldUtil;
import com.zlt.aps.mdm.api.domain.entity.MdmMaterialInfo;
import com.zlt.aps.mp.api.domain.entity.FactoryMonthPlanProductionFinalResult;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 滚动排程窗口衔接服务。
 * <p>负责将上一批次与本批次重叠的预排班次继承到本批次，并把机台状态推进到追加排程起点。</p>
 *
 * @author APS
 */
@Slf4j
@Service
public class RollingScheduleHandoffService {

    /** 未发布状态 */
    private static final String UNRELEASED_STATUS = "0";
    /** 正常数据标识 */
    private static final int NORMAL_DELETE_FLAG = 0;
    /** 无重叠时长 */
    private static final long NO_OVERLAP_MILLIS = 0L;

    /**
     * 执行滚动排程衔接。
     *
     * @param context 排程上下文
     */
    public void apply(LhScheduleContext context) {
        if (Objects.isNull(context) || CollectionUtils.isEmpty(context.getPreviousScheduleResultList())) {
            return;
        }
        List<LhShiftConfigVO> currentShifts = context.getScheduleWindowShifts();
        if (CollectionUtils.isEmpty(currentShifts)) {
            interrupt(context, "滚动排程衔接失败：当前排程窗口班次为空");
            return;
        }

        // Step1: 解析追加排程起点——目标日最早班次开始时间，继承窗口到此截止
        Date appendStartTime = resolveAppendStartTime(context, currentShifts);
        if (context.isInterrupted()) {
            return;
        }
        // Step2: 解析继承窗口范围 [inheritStartTime, appendStartTime)
        Date inheritStartTime = resolveInheritStartTime(context);
        // Step3: 筛选当前窗口中完全在追加起点之前的班次，作为继承映射候选
        List<LhShiftConfigVO> currentInheritedShifts = buildCurrentInheritedShifts(currentShifts, appendStartTime);
        if (CollectionUtils.isEmpty(currentInheritedShifts)) {
            interrupt(context, "滚动排程衔接失败：当前排程窗口未找到可继承班次");
            return;
        }
        log.info("滚动排程衔接窗口, 继承起点: {}, 追加起点: {}, 继承班次: {}",
                LhScheduleTimeUtil.formatDateTime(inheritStartTime),
                LhScheduleTimeUtil.formatDateTime(appendStartTime),
                formatInheritedShifts(currentInheritedShifts));

        // Step4: 逐条继承前批排程结果，校验物料/版本一致性，重映射班次索引
        Map<String, FactoryMonthPlanProductionFinalResult> currentMaterialPlanMap = buildCurrentMaterialPlanMap(context);
        List<LhScheduleResult> inheritedResults = new ArrayList<>(context.getPreviousScheduleResultList().size());
        for (LhScheduleResult previousResult : context.getPreviousScheduleResultList()) {
            LhScheduleResult inheritedResult = buildInheritedResult(context, previousResult,
                    currentInheritedShifts, inheritStartTime, appendStartTime, currentMaterialPlanMap);
            if (context.isInterrupted()) {
                return;
            }
            if (Objects.isNull(inheritedResult)) {
                continue;
            }
            inheritedResults.add(inheritedResult);
            context.getScheduleResultList().add(inheritedResult);
            context.getRollingInheritedScheduleResultList().add(inheritedResult);
            // 累计继承量，后续 ScheduleAdjustHandler 用此扣减待排量
            context.getInheritedPlanQtyMap().merge(
                    inheritedResult.getMaterialCode(), inheritedResult.getDailyPlanQty(), Integer::sum);
            registerMachineAssignment(context, inheritedResult);
        }

        // Step5: 继承重叠窗口内的模具交替计划
        inheritMouldChangePlans(context, appendStartTime);
        // Step6: 回写机台继承终态，空闲机台推进到追加起点
        syncMachineState(context, inheritedResults, appendStartTime);
        context.setRollingScheduleHandoff(true);
        log.info("滚动排程衔接完成, 继承结果: {}, 继承换模计划: {}, 追加起点: {}, 继承计划量汇总: {}",
                inheritedResults.size(), context.getMouldChangePlanList().size(),
                LhScheduleTimeUtil.formatDateTime(appendStartTime), context.getInheritedPlanQtyMap());
    }

    /**
     * 构建继承后的排程结果。
     *
     * @param context 排程上下文
     * @param previousResult 上一批排程结果
     * @param currentInheritedShifts 当前窗口可继承班次
     * @param inheritStartTime 继承起点
     * @param appendStartTime 追加起点
     * @param currentMaterialPlanMap 当前月计划物料Map
     * @return 继承结果，无可继承班次时返回null
     */
    private LhScheduleResult buildInheritedResult(LhScheduleContext context,
                                                  LhScheduleResult previousResult,
                                                  List<LhShiftConfigVO> currentInheritedShifts,
                                                  Date inheritStartTime,
                                                  Date appendStartTime,
                                                  Map<String, FactoryMonthPlanProductionFinalResult> currentMaterialPlanMap) {
        if (Objects.isNull(previousResult)) {
            return null;
        }
        LhScheduleResult inheritedResult = new LhScheduleResult();
        BeanUtil.copyProperties(previousResult, inheritedResult);
        // 先清空所有班次字段，再逐条按映射关系重新填入
        ShiftFieldUtil.clearShiftPlanFields(inheritedResult);

        int totalQty = 0;
        Date latestEndTime = null;
        List<String> shiftMappings = new ArrayList<>();
        for (int sourceShiftIndex = 1; sourceShiftIndex <= LhScheduleConstant.MAX_SHIFT_SLOT_COUNT; sourceShiftIndex++) {
            Integer planQty = ShiftFieldUtil.getShiftPlanQty(previousResult, sourceShiftIndex);
            if (Objects.isNull(planQty) || planQty <= 0) {
                continue;
            }
            Date startTime = ShiftFieldUtil.getShiftStartTime(previousResult, sourceShiftIndex);
            Date endTime = ShiftFieldUtil.getShiftEndTime(previousResult, sourceShiftIndex);
            // 校验班次时间完整性：有计划量但时间缺失或倒挂，直接中断
            if (Objects.isNull(startTime) || Objects.isNull(endTime) || !endTime.after(startTime)) {
                interrupt(context, "滚动排程衔接失败：上一批次物料[" + previousResult.getMaterialCode()
                        + "]存在班次时间不完整的计划量");
                return null;
            }
            // 跳过不在继承窗口内的班次
            if (!isShiftOverlappingInheritedWindow(startTime, endTime, inheritStartTime, appendStartTime)) {
                continue;
            }
            // 班次穿越继承窗口边界说明数据异常（部分在继承窗口、部分在追加区间），中断而非截断
            if (!isShiftFullyInInheritedWindow(startTime, endTime, inheritStartTime, appendStartTime)) {
                interrupt(context, "滚动排程衔接失败：上一批次物料[" + previousResult.getMaterialCode()
                        + "]班次时间跨越继承窗口边界");
                return null;
            }
            // 按最大重叠度匹配目标班次索引；歧义时返回null触发中断，避免错误映射
            Integer targetShiftIndex = resolveTargetShiftIndex(currentInheritedShifts, startTime, endTime);
            if (Objects.isNull(targetShiftIndex)) {
                interrupt(context, "滚动排程衔接失败：上一批次物料[" + previousResult.getMaterialCode()
                        + "]班次时间无法匹配本次重叠窗口");
                return null;
            }
            ShiftFieldUtil.copyShiftPlanFields(previousResult, sourceShiftIndex, inheritedResult, targetShiftIndex);
            totalQty += planQty;
            latestEndTime = endTime;
            shiftMappings.add(sourceShiftIndex + "->" + targetShiftIndex
                    + "(qty=" + planQty
                    + ", start=" + LhScheduleTimeUtil.formatDateTime(startTime)
                    + ", end=" + LhScheduleTimeUtil.formatDateTime(endTime) + ")");
        }
        if (totalQty <= 0) {
            return null;
        }

        // 校验物料在本次月计划中存在、且月计划/排产版本一致
        FactoryMonthPlanProductionFinalResult currentPlan = currentMaterialPlanMap.get(previousResult.getMaterialCode());
        validateInheritedResult(context, previousResult, currentPlan);
        if (context.isInterrupted()) {
            return null;
        }
        // 重写继承结果的批次/日期/版本字段，使其归属本次排程
        inheritedResult.setBatchNo(context.getBatchNo());
        inheritedResult.setOrderNo(null);
        inheritedResult.setScheduleDate(context.getScheduleTargetDate());
        inheritedResult.setRealScheduleDate(context.getScheduleDate());
        inheritedResult.setDailyPlanQty(totalQty);
        inheritedResult.setSpecEndTime(latestEndTime);
        inheritedResult.setTdaySpecEndTime(latestEndTime);
        inheritedResult.setMonthPlanVersion(resolveCurrentMonthPlanVersion(context, currentPlan));
        inheritedResult.setProductionVersion(resolveCurrentProductionVersion(context, currentPlan));
        inheritedResult.setIsRelease(UNRELEASED_STATUS);
        inheritedResult.setIsDelete(NORMAL_DELETE_FLAG);
        inheritedResult.setCreateBy(null);
        inheritedResult.setCreateTime(null);
        inheritedResult.setUpdateBy(null);
        inheritedResult.setUpdateTime(null);
        inheritedResult.setMouldChangeStartTime(null);
        inheritedResult.setRollingInherited(true);
        log.info("滚动衔接结果明细, 机台: {}, 物料: {}, 班次映射: {}, 继承量: {}, 继承后结束: {}",
                inheritedResult.getLhMachineCode(), inheritedResult.getMaterialCode(),
                shiftMappings, totalQty, LhScheduleTimeUtil.formatDateTime(latestEndTime));
        return inheritedResult;
    }

    /**
     * 校验继承结果与本次月计划、版本一致。
     *
     * @param context 排程上下文
     * @param previousResult 上一批排程结果
     * @param currentPlan 本次月计划记录
     */
    private void validateInheritedResult(LhScheduleContext context,
                                         LhScheduleResult previousResult,
                                         FactoryMonthPlanProductionFinalResult currentPlan) {
        if (StringUtils.isEmpty(previousResult.getMaterialCode())) {
            interrupt(context, "滚动排程衔接失败：上一批次存在物料编码为空的排程结果");
            return;
        }
        if (Objects.isNull(currentPlan)) {
            interrupt(context, "滚动排程衔接失败：上一批次物料[" + previousResult.getMaterialCode()
                    + "]不在本次月计划SKU中");
            return;
        }
        String currentMonthPlanVersion = resolveCurrentMonthPlanVersion(context, currentPlan);
        String currentProductionVersion = resolveCurrentProductionVersion(context, currentPlan);
        if (StringUtils.isNotEmpty(currentMonthPlanVersion)
                && StringUtils.isNotEmpty(previousResult.getMonthPlanVersion())
                && !StringUtils.equals(currentMonthPlanVersion, previousResult.getMonthPlanVersion())) {
            interrupt(context, "滚动排程衔接失败：上一批次物料[" + previousResult.getMaterialCode()
                    + "]月计划版本与本次不一致");
            return;
        }
        if (StringUtils.isNotEmpty(currentProductionVersion)
                && StringUtils.isNotEmpty(previousResult.getProductionVersion())
                && !StringUtils.equals(currentProductionVersion, previousResult.getProductionVersion())) {
            interrupt(context, "滚动排程衔接失败：上一批次物料[" + previousResult.getMaterialCode()
                    + "]排产版本与本次不一致");
        }
    }

    /**
     * 继承重叠窗口内的模具交替计划。
     *
     * @param context 排程上下文
     * @param appendStartTime 追加排程起点
     */
    private void inheritMouldChangePlans(LhScheduleContext context, Date appendStartTime) {
        if (CollectionUtils.isEmpty(context.getPreviousMouldChangePlanList())) {
            return;
        }
        Date inheritStartTime = resolveInheritStartTime(context);
        int planOrder = context.getMouldChangePlanList().size() + 1;
        for (LhMouldChangePlan previousPlan : context.getPreviousMouldChangePlanList()) {
            if (!isInInheritedWindow(previousPlan.getPlanDate(), inheritStartTime, appendStartTime)) {
                continue;
            }
            LhMouldChangePlan inheritedPlan = new LhMouldChangePlan();
            BeanUtil.copyProperties(previousPlan, inheritedPlan);
            inheritedPlan.setLhResultBatchNo(context.getBatchNo());
            inheritedPlan.setOrderNo(null);
            inheritedPlan.setScheduleDate(context.getScheduleTargetDate());
            inheritedPlan.setPlanOrder(planOrder++);
            inheritedPlan.setIsDelete(NORMAL_DELETE_FLAG);
            inheritedPlan.setCreateBy(null);
            inheritedPlan.setCreateTime(null);
            inheritedPlan.setUpdateBy(null);
            inheritedPlan.setUpdateTime(null);
            context.getMouldChangePlanList().add(inheritedPlan);
        }
    }

    /**
     * 回写继承后的机台状态，并将未继承机台推进到追加排程起点。
     *
     * @param context 排程上下文
     * @param inheritedResults 继承结果
     * @param appendStartTime 追加排程起点
     */
    private void syncMachineState(LhScheduleContext context,
                                  List<LhScheduleResult> inheritedResults,
                                  Date appendStartTime) {
        // 同一机台取规格结束时间最晚的继承结果，作为该机台的继承终态
        Map<String, LhScheduleResult> latestResultMap = inheritedResults.stream()
                .filter(result -> StringUtils.isNotEmpty(result.getLhMachineCode()))
                .collect(Collectors.toMap(
                        LhScheduleResult::getLhMachineCode,
                        result -> result,
                        this::selectLatestResult,
                        LinkedHashMap::new));
        for (Map.Entry<String, MachineScheduleDTO> entry : context.getMachineScheduleMap().entrySet()) {
            MachineScheduleDTO machine = entry.getValue();
            LhScheduleResult latestResult = latestResultMap.get(entry.getKey());
            if (Objects.nonNull(latestResult)) {
                // 有继承结果：回写当前物料/规格/英寸等终态
                applyMachineState(context, machine, latestResult);
            }
            // 无论是否有继承结果，若机台预计结束时间早于追加起点，推进到追加起点
            if (Objects.nonNull(appendStartTime)
                    && (Objects.isNull(machine.getEstimatedEndTime())
                    || machine.getEstimatedEndTime().before(appendStartTime))) {
                machine.setEstimatedEndTime(appendStartTime);
            }
            // 同步初始快照，供后续换模计划识别继承后的前规格/前物料
            syncInitialMachineState(context, entry.getKey(), machine);
        }
    }

    /**
     * 应用机台继承终态。
     *
     * @param machine 机台状态
     * @param result 最新继承结果
     */
    private void applyMachineState(LhScheduleContext context, MachineScheduleDTO machine, LhScheduleResult result) {
        machine.setCurrentMaterialCode(result.getMaterialCode());
        machine.setCurrentMaterialDesc(result.getMaterialDesc());
        machine.setPreviousSpecCode(result.getSpecCode());
        machine.setPreviousProSize(resolveMaterialProSize(context, result.getMaterialCode()));
        machine.setEstimatedEndTime(result.getSpecEndTime());
        machine.setEnding("1".equals(result.getIsEnd()) && Objects.nonNull(result.getSpecEndTime()));
    }

    /**
     * 同步机台初始快照，供后续换模计划识别继承后的前规格。
     *
     * @param context 排程上下文
     * @param machineCode 机台编号
     * @param machine 机台状态
     */
    private void syncInitialMachineState(LhScheduleContext context, String machineCode, MachineScheduleDTO machine) {
        MachineScheduleDTO snapshot = context.getInitialMachineScheduleMap().get(machineCode);
        if (Objects.isNull(snapshot)) {
            snapshot = new MachineScheduleDTO();
            snapshot.setMachineCode(machineCode);
            context.getInitialMachineScheduleMap().put(machineCode, snapshot);
        }
        snapshot.setMachineName(machine.getMachineName());
        snapshot.setCurrentMaterialCode(machine.getCurrentMaterialCode());
        snapshot.setCurrentMaterialDesc(machine.getCurrentMaterialDesc());
        snapshot.setPreviousMaterialCode(machine.getPreviousMaterialCode());
        snapshot.setPreviousMaterialDesc(machine.getPreviousMaterialDesc());
        snapshot.setPreviousSpecCode(machine.getPreviousSpecCode());
        snapshot.setPreviousProSize(machine.getPreviousProSize());
        snapshot.setEstimatedEndTime(machine.getEstimatedEndTime());
    }

    /**
     * 解析追加排程起点。
     *
     * @param context 排程上下文
     * @return 追加排程起点
     */
    private Date resolveAppendStartTime(LhScheduleContext context, List<LhShiftConfigVO> currentShifts) {
        if (Objects.isNull(context.getScheduleTargetDate())) {
            interrupt(context, "滚动排程衔接失败：排程目标日为空，无法解析追加起点");
            return null;
        }
        // 从班次配置中查找目标日最早班次开始时间，作为追加排程起点
        Date targetDate = LhScheduleTimeUtil.clearTime(context.getScheduleTargetDate());
        Date appendStartTime = null;
        for (LhShiftConfigVO shift : currentShifts) {
            if (Objects.isNull(shift) || Objects.isNull(shift.getWorkDate())
                    || Objects.isNull(shift.getShiftStartDateTime())) {
                continue;
            }
            Date workDate = LhScheduleTimeUtil.clearTime(shift.getWorkDate());
            if (!targetDate.equals(workDate)) {
                continue;
            }
            Date shiftStartTime = shift.getShiftStartDateTime();
            if (Objects.isNull(appendStartTime) || shiftStartTime.before(appendStartTime)) {
                appendStartTime = shiftStartTime;
            }
        }
        if (Objects.isNull(appendStartTime)) {
            interrupt(context, "滚动排程衔接失败：当前排程窗口未找到目标日追加起点班次");
            return null;
        }
        return appendStartTime;
    }

    /**
     * 构建当前窗口可继承班次。
     *
     * @param currentShifts 当前窗口班次
     * @param appendStartTime 追加排程起点
     * @return 当前窗口可继承班次
     */
    private List<LhShiftConfigVO> buildCurrentInheritedShifts(List<LhShiftConfigVO> currentShifts, Date appendStartTime) {
        List<LhShiftConfigVO> shifts = new ArrayList<>(currentShifts.size());
        // 只保留完全在追加起点之前结束的班次（起止均在继承窗口内）
        for (LhShiftConfigVO shift : currentShifts) {
            if (Objects.isNull(shift.getShiftStartDateTime())
                    || Objects.isNull(shift.getShiftEndDateTime())
                    || Objects.isNull(appendStartTime)
                    || !shift.getShiftStartDateTime().before(appendStartTime)
                    || shift.getShiftEndDateTime().after(appendStartTime)) {
                continue;
            }
            shifts.add(shift);
        }
        return shifts;
    }

    /**
     * 格式化当前窗口可继承班次，便于日志定位继承边界。
     *
     * @param currentInheritedShifts 当前窗口可继承班次
     * @return 班次摘要
     */
    private String formatInheritedShifts(List<LhShiftConfigVO> currentInheritedShifts) {
        if (CollectionUtils.isEmpty(currentInheritedShifts)) {
            return "[]";
        }
        return currentInheritedShifts.stream()
                .map(shift -> shift.getShiftIndex() + "("
                        + LhScheduleTimeUtil.formatDateTime(shift.getShiftStartDateTime())
                        + "~"
                        + LhScheduleTimeUtil.formatDateTime(shift.getShiftEndDateTime()) + ")")
                .collect(Collectors.joining(", ", "[", "]"));
    }

    /**
     * 按时间重叠度解析继承结果目标班次索引。
     *
     * @param currentInheritedShifts 当前窗口可继承班次
     * @param startTime 上一批班次开始时间
     * @param endTime 上一批班次结束时间
     * @return 当前窗口班次索引
     */
    private Integer resolveTargetShiftIndex(List<LhShiftConfigVO> currentInheritedShifts, Date startTime, Date endTime) {
        if (Objects.isNull(startTime) || Objects.isNull(endTime)) {
            return null;
        }
        // 按重叠毫秒数最大匹配；两个班次重叠度相等时标记歧义，返回null触发中断
        long maxOverlapMillis = NO_OVERLAP_MILLIS;
        Integer targetShiftIndex = null;
        boolean ambiguous = false;
        for (LhShiftConfigVO shift : currentInheritedShifts) {
            long overlapMillis = resolveOverlapMillis(startTime, endTime,
                    shift.getShiftStartDateTime(), shift.getShiftEndDateTime());
            if (overlapMillis <= NO_OVERLAP_MILLIS) {
                continue;
            }
            if (overlapMillis > maxOverlapMillis) {
                maxOverlapMillis = overlapMillis;
                targetShiftIndex = shift.getShiftIndex();
                ambiguous = false;
                continue;
            }
            if (overlapMillis == maxOverlapMillis) {
                ambiguous = true;
            }
        }
        return ambiguous ? null : targetShiftIndex;
    }

    /**
     * 解析两个时间段的重叠毫秒数。
     *
     * @param leftStart 左区间开始
     * @param leftEnd 左区间结束
     * @param rightStart 右区间开始
     * @param rightEnd 右区间结束
     * @return 重叠毫秒数
     */
    private long resolveOverlapMillis(Date leftStart, Date leftEnd, Date rightStart, Date rightEnd) {
        if (Objects.isNull(leftStart) || Objects.isNull(leftEnd)
                || Objects.isNull(rightStart) || Objects.isNull(rightEnd)) {
            return NO_OVERLAP_MILLIS;
        }
        long overlapStart = Math.max(leftStart.getTime(), rightStart.getTime());
        long overlapEnd = Math.min(leftEnd.getTime(), rightEnd.getTime());
        return Math.max(NO_OVERLAP_MILLIS, overlapEnd - overlapStart);
    }

    /**
     * 获取继承窗口起点。
     *
     * @param context 排程上下文
     * @return 继承窗口起点
     */
    private Date resolveInheritStartTime(LhScheduleContext context) {
        return context.getScheduleWindowShifts().stream()
                .map(LhShiftConfigVO::getShiftStartDateTime)
                .filter(Objects::nonNull)
                .min(Date::compareTo)
                .orElse(context.getScheduleDate());
    }

    /**
     * 判断时间是否位于继承窗口。
     *
     * @param time 时间
     * @param inheritStartTime 继承起点
     * @param appendStartTime 追加起点
     * @return true-在继承窗口内
     */
    private boolean isInInheritedWindow(Date time, Date inheritStartTime, Date appendStartTime) {
        return Objects.nonNull(time)
                && Objects.nonNull(inheritStartTime)
                && Objects.nonNull(appendStartTime)
                && !time.before(inheritStartTime)
                && time.before(appendStartTime);
    }

    /**
     * 判断班次时间段是否与继承窗口重叠。
     *
     * @param startTime 班次开始时间
     * @param endTime 班次结束时间
     * @param inheritStartTime 继承起点
     * @param appendStartTime 追加起点
     * @return true-与继承窗口重叠
     */
    private boolean isShiftOverlappingInheritedWindow(Date startTime, Date endTime,
                                                      Date inheritStartTime, Date appendStartTime) {
        return Objects.nonNull(startTime)
                && Objects.nonNull(endTime)
                && Objects.nonNull(inheritStartTime)
                && Objects.nonNull(appendStartTime)
                && startTime.before(appendStartTime)
                && endTime.after(inheritStartTime);
    }

    /**
     * 判断班次时间段是否完整位于继承窗口。
     *
     * @param startTime 班次开始时间
     * @param endTime 班次结束时间
     * @param inheritStartTime 继承起点
     * @param appendStartTime 追加起点
     * @return true-完整位于继承窗口
     */
    private boolean isShiftFullyInInheritedWindow(Date startTime, Date endTime,
                                                  Date inheritStartTime, Date appendStartTime) {
        return Objects.nonNull(startTime)
                && Objects.nonNull(endTime)
                && Objects.nonNull(inheritStartTime)
                && Objects.nonNull(appendStartTime)
                && !startTime.before(inheritStartTime)
                && !endTime.after(appendStartTime);
    }

    /**
     * 构建当前月计划物料Map。
     *
     * @param context 排程上下文
     * @return 物料编码 -> 月计划记录
     */
    private Map<String, FactoryMonthPlanProductionFinalResult> buildCurrentMaterialPlanMap(LhScheduleContext context) {
        if (CollectionUtils.isEmpty(context.getMonthPlanList())) {
            return new HashMap<>(0);
        }
        Map<String, FactoryMonthPlanProductionFinalResult> map = new HashMap<>(context.getMonthPlanList().size());
        for (FactoryMonthPlanProductionFinalResult plan : context.getMonthPlanList()) {
            if (Objects.isNull(plan) || StringUtils.isEmpty(plan.getMaterialCode())) {
                continue;
            }
            map.putIfAbsent(plan.getMaterialCode(), plan);
        }
        return map;
    }

    /**
     * 解析本次月计划版本。
     *
     * @param context 排程上下文
     * @param currentPlan 本次月计划记录
     * @return 本次月计划版本
     */
    private String resolveCurrentMonthPlanVersion(LhScheduleContext context,
                                                  FactoryMonthPlanProductionFinalResult currentPlan) {
        if (Objects.nonNull(currentPlan) && StringUtils.isNotEmpty(currentPlan.getMonthPlanVersion())) {
            return currentPlan.getMonthPlanVersion();
        }
        return context.getMonthPlanVersion();
    }

    /**
     * 解析本次排产版本。
     *
     * @param context 排程上下文
     * @param currentPlan 本次月计划记录
     * @return 本次排产版本
     */
    private String resolveCurrentProductionVersion(LhScheduleContext context,
                                                   FactoryMonthPlanProductionFinalResult currentPlan) {
        if (Objects.nonNull(currentPlan) && StringUtils.isNotEmpty(currentPlan.getProductionVersion())) {
            return currentPlan.getProductionVersion();
        }
        return context.getProductionVersion();
    }

    /**
     * 解析物料英寸。
     *
     * @param context 排程上下文
     * @param materialCode 物料编码
     * @return 物料英寸
     */
    private String resolveMaterialProSize(LhScheduleContext context, String materialCode) {
        if (Objects.isNull(context) || StringUtils.isEmpty(materialCode)) {
            return null;
        }
        MdmMaterialInfo materialInfo = context.getMaterialInfoMap().get(materialCode);
        return Objects.nonNull(materialInfo) ? materialInfo.getProSize() : null;
    }

    /**
     * 选择规格结束时间更晚的结果。
     *
     * @param left 左结果
     * @param right 右结果
     * @return 最新结果
     */
    private LhScheduleResult selectLatestResult(LhScheduleResult left, LhScheduleResult right) {
        Date leftEndTime = left.getSpecEndTime();
        Date rightEndTime = right.getSpecEndTime();
        if (Objects.isNull(leftEndTime)) {
            return right;
        }
        if (Objects.isNull(rightEndTime)) {
            return left;
        }
        return leftEndTime.after(rightEndTime) ? left : right;
    }

    /**
     * 注册继承结果到机台分配。
     *
     * @param context 排程上下文
     * @param result 继承结果
     */
    private void registerMachineAssignment(LhScheduleContext context, LhScheduleResult result) {
        if (StringUtils.isEmpty(result.getLhMachineCode())) {
            return;
        }
        context.getMachineAssignmentMap()
                .computeIfAbsent(result.getLhMachineCode(), key -> new ArrayList<>())
                .add(result);
    }

    /**
     * 中断排程。
     *
     * @param context 排程上下文
     * @param message 中断说明
     */
    private void interrupt(LhScheduleContext context, String message) {
        ScheduleDomainExceptionHelper.interrupt(context, ScheduleStepEnum.S4_2_DATA_INIT,
                ScheduleErrorCode.DATA_INCOMPLETE, message);
    }
}
