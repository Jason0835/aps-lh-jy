package com.zlt.aps.lh.handler;

import com.zlt.aps.lh.api.constant.LhScheduleConstant;
import com.zlt.aps.lh.api.constant.LhScheduleParamConstant;
import com.zlt.aps.lh.api.domain.dto.MachineCleaningWindowDTO;
import com.zlt.aps.lh.api.domain.dto.MachineScheduleDTO;
import com.zlt.aps.lh.api.domain.entity.LhMouldChangePlan;
import com.zlt.aps.lh.api.domain.entity.LhScheduleProcessLog;
import com.zlt.aps.lh.api.domain.entity.LhScheduleResult;
import com.zlt.aps.lh.api.domain.vo.LhShiftConfigVO;
import com.zlt.aps.lh.api.enums.CleaningTypeEnum;
import com.zlt.aps.lh.api.enums.MouldChangeTypeEnum;
import com.zlt.aps.lh.api.enums.ShiftEnum;
import com.zlt.aps.lh.api.enums.ScheduleStepEnum;
import com.zlt.aps.lh.component.IncrSerialGenerator;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.engine.observer.ScheduleEvent;
import com.zlt.aps.mp.api.domain.entity.FactoryMonthPlanProductionFinalResult;
import com.zlt.aps.lh.engine.observer.ScheduleEventPublisher;
import com.zlt.aps.lh.exception.ScheduleErrorCode;
import com.zlt.aps.lh.exception.ScheduleException;
import com.zlt.aps.lh.service.impl.SchedulePersistenceService;
import com.zlt.aps.lh.util.LhScheduleTimeUtil;
import com.zlt.aps.lh.util.LeftRightMouldUtil;
import com.zlt.aps.lh.util.MonthPlanDayQtyUtil;
import com.zlt.aps.lh.util.ShiftFieldUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import javax.annotation.Resource;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * S4.6 结果校验与发布保存处理器
 * <p>最终校验排程结果，生成模具交替计划，保存数据</p>
 *
 * @author APS
 */
@Slf4j
@Component
public class ResultValidationHandler extends AbsScheduleStepHandler {

    @Resource
    private ScheduleEventPublisher scheduleEventPublisher;

    @Resource
    private SchedulePersistenceService schedulePersistenceService;

    private static final AtomicInteger ORDER_SEQ = new AtomicInteger(0);
    private static final AtomicInteger CHG_SEQ = new AtomicInteger(0);
    private static final int ENABLED = 1;
    private static final String CLEANING_DATA_SOURCE_MANUAL = "0";

    @Override
    protected void doHandle(LhScheduleContext context) {
        String scheduleOrderBusinessKey = buildScheduleOrderBusinessKey(context);
        try {
            // S4.6.1 排程后置校验
            postValidation(context);

            // S4.6.2 生成模具交替计划
            generateMouldChangePlan(context);
            validateMouldChangePlanQuota(context);
            validateManualSundaySandBlastThreshold(context);

            // S4.6.3 补全工单号和发布状态
            assignOrderNumbers(context);

            // S4.6.4 赋值排程顺序
            assignScheduleOrder(context, scheduleOrderBusinessKey);

            // S4.6.5 添加排程汇总日志
            addSummaryLog(context);

            // S4.6.5.1 按SKU+日期汇总校验日计划完成情况
            addDailyPlanSummaryLog(context);

            // S4.6.6 保存排程结果到数据库
            schedulePersistenceService.replaceScheduleAtomically(context);

            // S4.6.7 发布排程完成事件（观察者模式）
            scheduleEventPublisher.publish(ScheduleEvent.completed(context));
        } finally {
            clearScheduleOrderCounter(scheduleOrderBusinessKey);
        }
    }

    /**
     * 排程后置校验：检查结果完整性
     */
    private void postValidation(LhScheduleContext context) {
        log.info("执行排程后置校验, 排程结果数: {}, 未排产数: {}",
                context.getScheduleResultList().size(), context.getUnscheduledResultList().size());

        // 校验1：排程结果不能为空（允许全部未排的情况，但记录警告）
        if (context.getScheduleResultList().isEmpty()) {
            log.warn("排程结果为空，可能所有SKU均未成功排产");
        }

        if (StringUtils.isBlank(context.getBatchNo()) || StringUtils.isBlank(context.getFactoryCode())) {
            throw new ScheduleException(ScheduleStepEnum.S4_6_RESULT_VALIDATION,
                    ScheduleErrorCode.RESULT_VALIDATION_FAILED,
                    context.getFactoryCode(), context.getBatchNo(),
                    "批次号或工厂编码为空，无法执行结果保存");
        }

        // 校验2：检查每个排程结果必填字段
        for (LhScheduleResult result : context.getScheduleResultList()) {
            if (result.getBatchNo() == null) {
                result.setBatchNo(context.getBatchNo());
            }
            if (result.getFactoryCode() == null) {
                result.setFactoryCode(context.getFactoryCode());
            }
            if (result.getScheduleDate() == null) {
                result.setScheduleDate(context.getScheduleTargetDate());
            }
            if (result.getIsRelease() == null) {
                result.setIsRelease("0");
            }
            requireField(result.getBatchNo(), "batchNo", context, result);
            requireField(result.getFactoryCode(), "factoryCode", context, result);
            requireField(result.getLhMachineCode(), "lhMachineCode", context, result);
            result.setLeftRightMould(LeftRightMouldUtil.resolveLeftRightMould(
                    result.getLeftRightMould(), result.getLhMachineCode()));
            requireField(result.getMaterialCode(), "materialCode", context, result);
            requireField(result.getScheduleType(), "scheduleType", context, result);
            if (result.getSpecEndTime() == null) {
                throwValidationFailure(context, result, "specEndTime 缺失");
            }
            if ("1".equals(result.getIsChangeMould()) && StringUtils.isBlank(result.getMouldCode())) {
                throwValidationFailure(context, result, "换模结果 mouldCode 缺失");
            }
        }

        log.info("排程后置校验完成");
    }

    /**
     * 生成模具交替计划
     * <p>
     * 收集排程结果中换模的机台，生成对应的模具交替计划记录。<br/>
     * 计划天数为2天（T日和T+1日），均衡早中班换模次数。
     * </p>
     */
    private void generateMouldChangePlan(LhScheduleContext context) {
        List<LhScheduleResult> changeResults = context.getScheduleResultList().stream()
                .filter(r -> "1".equals(r.getIsChangeMould())
                        // 继承结果的换模信息已在滚动衔接中处理，跳过避免重复生成
                        && !r.isRollingInherited()
                        && r.getDailyPlanQty() != null
                        && r.getDailyPlanQty() > 0)
                .sorted(Comparator.comparing(LhScheduleResult::getLhMachineCode, Comparator.nullsLast(String::compareTo))
                        .thenComparing(this::resolvePlannedMouldChangeStartTime, Comparator.nullsLast(Date::compareTo))
                        .thenComparing(LhScheduleResult::getSpecEndTime, Comparator.nullsLast(Date::compareTo)))
                .collect(Collectors.toList());
        log.info("生成模具交替计划, 换模排程结果数: {}", changeResults.size());

        List<LhMouldChangePlan> plans = context.getMouldChangePlanList();
        // 不清空列表，保留滚动衔接中已继承的换模计划，新计划从尾部追加
        Map<String, RollingMachineState> rollingStateMap = new HashMap<>();
        int planOrder = plans.size() + 1;

        for (LhScheduleResult result : changeResults) {
            RollingMachineState state = rollingStateMap.computeIfAbsent(result.getLhMachineCode(),
                    machineCode -> buildInitialState(context, machineCode));
            LhMouldChangePlan plan = new LhMouldChangePlan();
            plan.setFactoryCode(context.getFactoryCode());
            plan.setLhResultBatchNo(context.getBatchNo());
            plan.setOrderNo(generateChangePlanOrderNo(context));
            plan.setScheduleDate(context.getScheduleTargetDate());
            // 换模计划优先对齐结果里的真实换模开始时间；没有时再回退旧口径。
            Date plannedMouldChangeStartTime = resolvePlannedMouldChangeStartTime(result);
            plan.setPlanDate(plannedMouldChangeStartTime);
            plan.setPlanOrder(planOrder++);
            plan.setClassIndex(resolvePlanShiftCode(context, plannedMouldChangeStartTime));
            plan.setLhMachineCode(result.getLhMachineCode());
            plan.setLhMachineName(result.getLhMachineName());
            plan.setLeftRightMould(LeftRightMouldUtil.resolveLeftRightMould(
                    result.getLeftRightMould(), result.getLhMachineCode()));
            // 前规格取换模前机台当前在产规格，后规格取本次换模上机规格。
            plan.setBeforeMaterialCode(state.getCurrentMaterialCode());
            plan.setBeforeMaterialDesc(state.getCurrentMaterialDesc());
            plan.setAfterMaterialCode(result.getMaterialCode());
            plan.setAfterMaterialDesc(result.getMaterialDesc());
            plan.setMouldCode(result.getMouldCode());
            plan.setIsRelease("0");
            plan.setMouldStatus("0");
            plan.setIsDelete(0);
            plan.setChangeTime(resolvePlanChangeTime(result, state));

            // 判断交替类型
            plan.setChangeMouldType(determineChangeMouldType(result));
            plans.add(plan);

            updateRollingState(state, result);
        }

        planOrder = appendCleaningMouldChangePlans(context, plans, planOrder, changeResults);
        log.info("生成模具交替计划完成, 共 {} 条", plans.size());
    }

    /**
     * 对最终换模计划执行早中班配额校验，避免超限结果落库。
     *
     * @param context 排程上下文
     */
    private void validateMouldChangePlanQuota(LhScheduleContext context) {
        if (context == null || CollectionUtils.isEmpty(context.getMouldChangePlanList())) {
            return;
        }
        Map<String, List<String>> morningMachineMap = new LinkedHashMap<>();
        Map<String, List<String>> afternoonMachineMap = new LinkedHashMap<>();
        for (LhMouldChangePlan plan : context.getMouldChangePlanList()) {
            if (!shouldCountMouldChangePlan(plan) || plan.getPlanDate() == null) {
                continue;
            }
            String dateKey = LhScheduleTimeUtil.formatDate(plan.getPlanDate());
            if (LhScheduleTimeUtil.isMorningShift(context, plan.getPlanDate())) {
                morningMachineMap.computeIfAbsent(dateKey, key -> new ArrayList<>()).add(plan.getLhMachineCode());
                continue;
            }
            if (LhScheduleTimeUtil.isAfternoonShift(context, plan.getPlanDate())) {
                afternoonMachineMap.computeIfAbsent(dateKey, key -> new ArrayList<>()).add(plan.getLhMachineCode());
            }
        }
        validateMouldChangeShiftLimit(context, morningMachineMap,
                LhScheduleTimeUtil.getMorningMouldChangeLimit(context), "早班");
        validateMouldChangeShiftLimit(context, afternoonMachineMap,
                LhScheduleTimeUtil.getAfternoonMouldChangeLimit(context), "中班");
    }

    private void validateMouldChangeShiftLimit(LhScheduleContext context,
                                               Map<String, List<String>> machineMap,
                                               int limit,
                                               String shiftName) {
        for (Map.Entry<String, List<String>> entry : machineMap.entrySet()) {
            if (CollectionUtils.isEmpty(entry.getValue()) || entry.getValue().size() <= limit) {
                continue;
            }
            throw new ScheduleException(ScheduleStepEnum.S4_6_RESULT_VALIDATION,
                    ScheduleErrorCode.RESULT_VALIDATION_FAILED,
                    context.getFactoryCode(), context.getBatchNo(),
                    String.format("模具交替计划超限：日期[%s]班次[%s]数量[%d]超出上限[%d]，机台=%s",
                            entry.getKey(), shiftName, entry.getValue().size(), limit,
                            String.join(",", entry.getValue())));
        }
    }

    private boolean shouldCountMouldChangePlan(LhMouldChangePlan plan) {
        if (plan == null || !Objects.equals(plan.getIsDelete(), 0)) {
            return false;
        }
        return StringUtils.equals(MouldChangeTypeEnum.REGULAR.getCode(), plan.getChangeMouldType())
                || StringUtils.equals(MouldChangeTypeEnum.TYPE_BLOCK.getCode(), plan.getChangeMouldType());
    }

    /**
     * 基于清洗窗口追加模具清洗交替计划。
     *
     * @param context 排程上下文
     * @param plans 模具交替计划列表
     * @param planOrder 当前计划顺序
     * @return 下一个计划顺序
     */
    private int appendCleaningMouldChangePlans(LhScheduleContext context,
                                               List<LhMouldChangePlan> plans,
                                               int planOrder,
                                               List<LhScheduleResult> changeResults) {
        List<Map.Entry<MachineScheduleDTO, MachineCleaningWindowDTO>> cleaningPlanItems = collectCleaningPlanItems(context);
        for (Map.Entry<MachineScheduleDTO, MachineCleaningWindowDTO> item : cleaningPlanItems) {
            MachineScheduleDTO machine = item.getKey();
            MachineCleaningWindowDTO cleaningWindow = item.getValue();
            String machineCode = resolveCleaningMachineCode(machine, cleaningWindow);
            RollingMachineState cleaningState = resolveCleaningMaterialState(context, changeResults,
                    machineCode, cleaningWindow.getCleanStartTime());
            LhMouldChangePlan plan = new LhMouldChangePlan();
            plan.setFactoryCode(context.getFactoryCode());
            plan.setLhResultBatchNo(context.getBatchNo());
            plan.setOrderNo(generateChangePlanOrderNo(context));
            plan.setScheduleDate(context.getScheduleTargetDate());
            plan.setPlanDate(cleaningWindow.getCleanStartTime());
            plan.setPlanOrder(planOrder++);
            plan.setClassIndex(resolvePlanShiftCode(context, cleaningWindow.getCleanStartTime()));
            plan.setLhMachineCode(machineCode);
            plan.setLhMachineName(machine != null ? machine.getMachineName() : null);
            plan.setLeftRightMould(LeftRightMouldUtil.resolveLeftRightMould(
                    cleaningWindow.getLeftRightMould(), machineCode));
            plan.setBeforeMaterialCode(cleaningState.getCurrentMaterialCode());
            plan.setBeforeMaterialDesc(cleaningState.getCurrentMaterialDesc());
            plan.setAfterMaterialCode(cleaningState.getCurrentMaterialCode());
            plan.setAfterMaterialDesc(cleaningState.getCurrentMaterialDesc());
            plan.setChangeMouldType(resolveCleaningMouldChangeType(cleaningWindow));
            plan.setChangeTime(cleaningWindow.getCleanStartTime());
            plan.setMouldCode(cleaningWindow.getMouldCode());
            plan.setIsRelease("0");
            plan.setMouldStatus("0");
            plan.setRemark(cleaningWindow.getRemark());
            plan.setIsDelete(0);
            plans.add(plan);
        }
        return planOrder;
    }

    /**
     * 按清洗发生时点回放机台物料状态。
     *
     * @param context 排程上下文
     * @param changeResults 换模结果
     * @param machineCode 机台编码
     * @param cleaningStartTime 清洗开始时间
     * @return 清洗发生时的机台状态
     */
    private RollingMachineState resolveCleaningMaterialState(LhScheduleContext context,
                                                             List<LhScheduleResult> changeResults,
                                                             String machineCode,
                                                             Date cleaningStartTime) {
        RollingMachineState state = buildInitialState(context, machineCode);
        if (StringUtils.isEmpty(machineCode) || cleaningStartTime == null || CollectionUtils.isEmpty(changeResults)) {
            return state;
        }
        for (LhScheduleResult result : changeResults) {
            if (!StringUtils.equals(machineCode, result.getLhMachineCode())) {
                continue;
            }
            Date plannedMouldChangeStartTime = resolvePlannedMouldChangeStartTime(result);
            if (plannedMouldChangeStartTime == null || !plannedMouldChangeStartTime.before(cleaningStartTime)) {
                continue;
            }
            updateRollingState(state, result);
        }
        return state;
    }

    /**
     * 诊断周日手工喷砂是否满足交替计划条数阈值。
     *
     * @param context 排程上下文
     */
    private void validateManualSundaySandBlastThreshold(LhScheduleContext context) {
        if (context.getParamIntValue(LhScheduleParamConstant.SAND_BLAST_SKIP_SUNDAY_ENABLED,
                LhScheduleConstant.SAND_BLAST_SKIP_SUNDAY_ENABLED) != ENABLED
                || context.getParamIntValue(LhScheduleParamConstant.SAND_BLAST_ALLOW_SUNDAY_MANUAL_ENABLED,
                LhScheduleConstant.SAND_BLAST_ALLOW_SUNDAY_MANUAL_ENABLED) != ENABLED) {
            return;
        }
        int threshold = context.getParamIntValue(LhScheduleParamConstant.SAND_BLAST_SUNDAY_MIN_ALTERNATE_PLAN_COUNT,
                LhScheduleConstant.SAND_BLAST_SUNDAY_MIN_ALTERNATE_PLAN_COUNT);
        for (Map.Entry<MachineScheduleDTO, MachineCleaningWindowDTO> item : collectCleaningPlanItems(context)) {
            MachineCleaningWindowDTO cleaningWindow = item.getValue();
            if (cleaningWindow == null
                    || !StringUtils.equals(CleaningTypeEnum.SAND_BLAST.getCode(), cleaningWindow.getCleanType())
                    || !StringUtils.equals(CLEANING_DATA_SOURCE_MANUAL, cleaningWindow.getDataSource())
                    || cleaningWindow.getCleanStartTime() == null
                    || !isSunday(cleaningWindow.getCleanStartTime())) {
                continue;
            }
            String dateKey = LhScheduleTimeUtil.formatDate(cleaningWindow.getCleanStartTime());
            long alternatePlanCount = context.getMouldChangePlanList().stream()
                    .filter(plan -> Objects.nonNull(plan.getPlanDate())
                            && StringUtils.equals(dateKey, LhScheduleTimeUtil.formatDate(plan.getPlanDate()))
                            && !isCleaningMouldChangePlan(plan))
                    .count();
            if (alternatePlanCount >= threshold) {
                log.warn("周日手工喷砂交替计划数量达到诊断阈值, 日期: {}, 机台: {}, 阈值: {}, 实际条数: {}",
                        dateKey, resolveCleaningMachineCode(item.getKey(), cleaningWindow),
                        threshold, alternatePlanCount);
            }
        }
    }

    /**
     * 判断是否为清洗类交替计划。
     *
     * @param plan 模具交替计划
     * @return true-清洗类；false-非清洗类
     */
    private boolean isCleaningMouldChangePlan(LhMouldChangePlan plan) {
        return Objects.nonNull(plan)
                && (StringUtils.equals(MouldChangeTypeEnum.SAND_BLAST.getCode(), plan.getChangeMouldType())
                || StringUtils.equals(MouldChangeTypeEnum.DRY_ICE.getCode(), plan.getChangeMouldType()));
    }

    /**
     * 判断指定日期是否为周日。
     *
     * @param date 日期
     * @return true-周日；false-非周日
     */
    private boolean isSunday(Date date) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(date);
        return calendar.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY;
    }

    /**
     * 收集清洗计划项。
     *
     * @param context 排程上下文
     * @return 清洗计划项
     */
    private List<Map.Entry<MachineScheduleDTO, MachineCleaningWindowDTO>> collectCleaningPlanItems(LhScheduleContext context) {
        List<Map.Entry<MachineScheduleDTO, MachineCleaningWindowDTO>> itemList = new ArrayList<>();
        for (MachineScheduleDTO machine : context.getMachineScheduleMap().values()) {
            if (machine == null || CollectionUtils.isEmpty(machine.getCleaningWindowList())) {
                continue;
            }
            for (MachineCleaningWindowDTO cleaningWindow : machine.getCleaningWindowList()) {
                if (cleaningWindow == null
                        || cleaningWindow.getCleanStartTime() == null
                        || StringUtils.isEmpty(resolveCleaningMouldChangeType(cleaningWindow))) {
                    continue;
                }
                itemList.add(new java.util.AbstractMap.SimpleEntry<>(machine, cleaningWindow));
            }
        }
        itemList.sort(Comparator
                .comparing((Map.Entry<MachineScheduleDTO, MachineCleaningWindowDTO> item) -> item.getValue().getCleanStartTime(),
                        Comparator.nullsLast(Date::compareTo))
                .thenComparing(item -> resolveCleaningMachineCode(item.getKey(), item.getValue()),
                        Comparator.nullsLast(String::compareTo)));
        return itemList;
    }

    /**
     * 解析清洗计划对应机台。
     *
     * @param machine 机台
     * @param cleaningWindow 清洗窗口
     * @return 机台编码
     */
    private String resolveCleaningMachineCode(MachineScheduleDTO machine, MachineCleaningWindowDTO cleaningWindow) {
        if (cleaningWindow != null && StringUtils.isNotEmpty(cleaningWindow.getLhCode())) {
            return cleaningWindow.getLhCode();
        }
        return machine != null ? machine.getMachineCode() : null;
    }

    /**
     * 解析清洗交替类型。
     *
     * @param cleaningWindow 清洗窗口
     * @return 模具交替类型
     */
    private String resolveCleaningMouldChangeType(MachineCleaningWindowDTO cleaningWindow) {
        if (Objects.isNull(cleaningWindow)) {
            return null;
        }
        if (CleaningTypeEnum.SAND_BLAST.getCode().equals(cleaningWindow.getCleanType())) {
            return MouldChangeTypeEnum.SAND_BLAST.getCode();
        }
        if (CleaningTypeEnum.DRY_ICE.getCode().equals(cleaningWindow.getCleanType())) {
            return MouldChangeTypeEnum.DRY_ICE.getCode();
        }
        return null;
    }

    /**
     * 确定模具交替类型
     * <p>01-正规换模, 02-更换活字块, 03-模具喷砂清洗, 04-模具干冰清洗</p>
     */
    private String determineChangeMouldType(LhScheduleResult result) {
        // 换活字块：通过 isTypeBlock 精确识别
        if ("1".equals(result.getIsTypeBlock())) {
            return "02";
        }
        // 新增排产（换模）
        if ("02".equals(result.getScheduleType())) {
            return "01";
        }
        return "01";
    }

    /**
     * 为排程结果补全工单号（确保每条记录都有工单号）
     */
    private void assignOrderNumbers(LhScheduleContext context) {
        log.info("补全工单号, 排程结果数: {}", context.getScheduleResultList().size());
        String dateStr = new SimpleDateFormat("yyyyMMdd").format(context.getScheduleTargetDate());

        for (LhScheduleResult result : context.getScheduleResultList()) {
            if (result.getOrderNo() == null || result.getOrderNo().isEmpty()) {
                int seq = ORDER_SEQ.incrementAndGet() % 1000;
                result.setOrderNo(String.format("%s%s%03d", LhScheduleConstant.ORDER_NO_PREFIX, dateStr, seq));
            }
            // 确保发布状态已设置
            if (result.getIsRelease() == null) {
                result.setIsRelease("0");
            }
        }

        // 为模具交替计划补全工单号
        for (LhMouldChangePlan plan : context.getMouldChangePlanList()) {
            if (plan.getOrderNo() == null || plan.getOrderNo().isEmpty()) {
                int seq = CHG_SEQ.incrementAndGet() % 1000;
                plan.setOrderNo(String.format("%s%s%03d", LhScheduleConstant.MOULD_CHANGE_ORDER_PREFIX, dateStr, seq));
            }
        }
    }

    /**
     * 添加排程汇总日志
     */
    private void addSummaryLog(LhScheduleContext context) {
        LhScheduleProcessLog summaryLog = new LhScheduleProcessLog();
        summaryLog.setBatchNo(context.getBatchNo());
        summaryLog.setTitle(ScheduleStepEnum.S4_6_RESULT_VALIDATION.getDescription());
        summaryLog.setBusiCode(context.getFactoryCode());
        summaryLog.setLogDetail(String.format(
                "排程完成: 排程结果%d条, 未排产%d条, 换模计划%d条",
                context.getScheduleResultList().size(),
                context.getUnscheduledResultList().size(),
                context.getMouldChangePlanList().size()
        ));
        summaryLog.setIsDelete(0);
        context.getScheduleLogList().add(summaryLog);
    }

    /**
     * 按SKU+日期汇总排产量，对比月计划dayN，输出日计划完成情况日志。
     * <p>汇总口径：遍历所有排程结果，按班次归属日期聚合各SKU的实际排产量，
     * 与月计划对应 dayN 的计划量做对比，识别超排/欠产/满班补齐超排等异常。</p>
     *
     * @param context 排程上下文
     */
    private void addDailyPlanSummaryLog(LhScheduleContext context) {
        List<LhShiftConfigVO> shifts = context.getScheduleWindowShifts();
        if (CollectionUtils.isEmpty(shifts)) {
            shifts = LhScheduleTimeUtil.getScheduleShifts(context, context.getScheduleDate());
        }
        if (CollectionUtils.isEmpty(shifts)) {
            return;
        }

        // 构建月计划物料->记录映射，用于快速查找日计划量
        Map<String, FactoryMonthPlanProductionFinalResult> monthPlanMap = new LinkedHashMap<>();
        if (!CollectionUtils.isEmpty(context.getMonthPlanList())) {
            for (FactoryMonthPlanProductionFinalResult plan : context.getMonthPlanList()) {
                if (StringUtils.isNotEmpty(plan.getMaterialCode())) {
                    monthPlanMap.put(plan.getMaterialCode(), plan);
                }
            }
        }

        // 按 materialCode + productionDate 汇总实际排产量
        Map<String, Map<LocalDate, Integer>> materialDayScheduledMap = new LinkedHashMap<>();
        for (LhScheduleResult result : context.getScheduleResultList()) {
            if (result == null || StringUtils.isEmpty(result.getMaterialCode())) {
                continue;
            }
            String materialCode = result.getMaterialCode();
            for (LhShiftConfigVO shift : shifts) {
                Integer planQty = ShiftFieldUtil.getShiftPlanQty(result, shift.getShiftIndex());
                if (planQty == null || planQty <= 0) {
                    continue;
                }
                Date workDate = shift.getWorkDate();
                if (workDate == null) {
                    continue;
                }
                LocalDate productionDate = workDate.toInstant()
                        .atZone(ZoneId.systemDefault()).toLocalDate();
                materialDayScheduledMap
                        .computeIfAbsent(materialCode, k -> new LinkedHashMap<>())
                        .merge(productionDate, planQty, Integer::sum);
            }
        }

        // 收集SKU的满班超排量信息（从上下文累加器读取，覆盖已移除和仍在待排列表的所有SKU）
        Map<String, Integer> skuShiftFillOverMap = context.getSkuShiftFillOverQtyMap();

        // 汇总并输出每个SKU每日的日计划完成情况
        int totalOverPlanCount = 0;
        int totalShortageCount = 0;
        int totalShiftFillOverQty = 0;
        for (Map.Entry<String, Map<LocalDate, Integer>> materialEntry : materialDayScheduledMap.entrySet()) {
            String materialCode = materialEntry.getKey();
            FactoryMonthPlanProductionFinalResult plan = monthPlanMap.get(materialCode);
            for (Map.Entry<LocalDate, Integer> dayEntry : materialEntry.getValue().entrySet()) {
                LocalDate productionDate = dayEntry.getKey();
                int actualQty = dayEntry.getValue();
                int dayOfMonth = productionDate.getDayOfMonth();
                int dayPlanQty = plan != null ? MonthPlanDayQtyUtil.resolveDayQty(plan, dayOfMonth) : 0;
                int diffQty = actualQty - dayPlanQty;
                if (diffQty > 0) {
                    totalOverPlanCount++;
                    log.warn("日计划超排, 物料: {}, 日期: {}, 日计划量: {}, 实际排产: {}, 超出: {}",
                            materialCode, productionDate, dayPlanQty, actualQty, diffQty);
                } else if (diffQty < 0) {
                    totalShortageCount++;
                    log.info("日计划欠产, 物料: {}, 日期: {}, 日计划量: {}, 实际排产: {}, 欠产: {}",
                            materialCode, productionDate, dayPlanQty, actualQty, -diffQty);
                }
            }
        }

        // 输出满班补齐超排汇总
        for (Map.Entry<String, Integer> entry : skuShiftFillOverMap.entrySet()) {
            totalShiftFillOverQty += entry.getValue();
            log.info("满班补齐超排汇总, 物料: {}, 超排量: {}", entry.getKey(), entry.getValue());
        }

        LhScheduleProcessLog dailyPlanLog = new LhScheduleProcessLog();
        dailyPlanLog.setBatchNo(context.getBatchNo());
        dailyPlanLog.setTitle("日计划完成校验");
        dailyPlanLog.setBusiCode(context.getFactoryCode());
        dailyPlanLog.setLogDetail(String.format(
                "日计划校验完成: 超排日期数%d, 欠产日期数%d, 满班补齐超排SKU数%d, 满班超排总量%d",
                totalOverPlanCount, totalShortageCount, skuShiftFillOverMap.size(), totalShiftFillOverQty));
        dailyPlanLog.setIsDelete(0);
        context.getScheduleLogList().add(dailyPlanLog);
    }

    /**
     * 为排程结果赋值排程顺序。
     *
     * @param context 排程上下文
     * @param businessKey 自增序列业务键
     */
    private void assignScheduleOrder(LhScheduleContext context, String businessKey) {
        if (StringUtils.isEmpty(businessKey)) {
            log.warn("排程顺序业务键为空，跳过排程顺序赋值");
            return;
        }
        if (CollectionUtils.isEmpty(context.getScheduleResultList())) {
            return;
        }
        // 按实际排产结果列表顺序依次生成排程顺序，保证落库顺序与业务执行顺序一致。
        for (LhScheduleResult result : context.getScheduleResultList()) {
            result.setScheduleOrder(IncrSerialGenerator.generateSerial(businessKey));
        }
    }

    /**
     * 构建排程顺序自增序列业务键（工厂编码_目标日yyyyMMdd）。
     *
     * @param context 排程上下文
     * @return 业务键
     */
    private String buildScheduleOrderBusinessKey(LhScheduleContext context) {
        if (context == null || StringUtils.isEmpty(context.getFactoryCode()) || context.getScheduleTargetDate() == null) {
            return null;
        }
        return context.getFactoryCode() + "_" + LhScheduleTimeUtil.getDateStr(context.getScheduleTargetDate());
    }

    /**
     * 清理排程顺序业务计数器。
     *
     * @param businessKey 自增序列业务键
     */
    private void clearScheduleOrderCounter(String businessKey) {
        if (StringUtils.isNotEmpty(businessKey)) {
            IncrSerialGenerator.clearBusinessCounter(businessKey);
        }
    }

    /**
     * 生成模具交替计划工单号：CHG+yyyyMMdd+3位流水号
     */
    private String generateChangePlanOrderNo(LhScheduleContext context) {
        String dateStr = new SimpleDateFormat("yyyyMMdd").format(context.getScheduleTargetDate());
        int seq = CHG_SEQ.incrementAndGet() % 1000;
        return String.format("%s%s%03d", LhScheduleConstant.MOULD_CHANGE_ORDER_PREFIX, dateStr, seq);
    }

    @Override
    protected String getStepName() {
        return ScheduleStepEnum.S4_6_RESULT_VALIDATION.getDescription();
    }

    @Override
    protected boolean shouldPropagateException() {
        return true;
    }

    private void requireField(String value, String fieldName, LhScheduleContext context, LhScheduleResult result) {
        if (StringUtils.isBlank(value)) {
            throwValidationFailure(context, result, fieldName + " 缺失");
        }
    }

    private void throwValidationFailure(LhScheduleContext context, LhScheduleResult result, String detail) {
        throw new ScheduleException(ScheduleStepEnum.S4_6_RESULT_VALIDATION,
                ScheduleErrorCode.RESULT_VALIDATION_FAILED,
                context.getFactoryCode(), context.getBatchNo(),
                String.format("排程结果校验失败，机台[%s] 物料[%s]：%s",
                        result.getLhMachineCode(), result.getMaterialCode(), detail));
    }

    private Date resolveProductionStartTime(LhScheduleResult result) {
        List<Date> startTimes = new ArrayList<>();
        if (result.getClass1StartTime() != null) {
            startTimes.add(result.getClass1StartTime());
        }
        if (result.getClass2StartTime() != null) {
            startTimes.add(result.getClass2StartTime());
        }
        if (result.getClass3StartTime() != null) {
            startTimes.add(result.getClass3StartTime());
        }
        if (result.getClass4StartTime() != null) {
            startTimes.add(result.getClass4StartTime());
        }
        if (result.getClass5StartTime() != null) {
            startTimes.add(result.getClass5StartTime());
        }
        if (result.getClass6StartTime() != null) {
            startTimes.add(result.getClass6StartTime());
        }
        if (result.getClass7StartTime() != null) {
            startTimes.add(result.getClass7StartTime());
        }
        if (result.getClass8StartTime() != null) {
            startTimes.add(result.getClass8StartTime());
        }
        if (startTimes.isEmpty()) {
            return result.getSpecEndTime();
        }
        return startTimes.stream().min(Date::compareTo).orElse(result.getSpecEndTime());
    }

    private Date resolvePlannedMouldChangeStartTime(LhScheduleResult result) {
        if (result == null) {
            return null;
        }
        if (result.getMouldChangeStartTime() != null) {
            return result.getMouldChangeStartTime();
        }
        return resolveProductionStartTime(result);
    }

    private Date resolvePlanChangeTime(LhScheduleResult result, RollingMachineState state) {
        if (result != null && result.getMouldChangeStartTime() != null) {
            return result.getMouldChangeStartTime();
        }
        return state != null ? state.getEstimatedEndTime() : null;
    }

    /**
     * 根据模具交替开始时间解析模具交替计划班别编码。
     *
     * @param context 排程上下文
     * @param plannedMouldChangeStartTime 模具交替开始时间
     * @return 班别编码，未命中班次时返回null
     */
    private String resolvePlanShiftCode(LhScheduleContext context, Date plannedMouldChangeStartTime) {
        if (context == null || plannedMouldChangeStartTime == null || context.getScheduleTargetDate() == null) {
            return null;
        }
        int shiftIndex = LhScheduleTimeUtil.getShiftIndex(
                context, context.getScheduleTargetDate(), plannedMouldChangeStartTime);
        if (shiftIndex <= 0) {
            return null;
        }
        LhShiftConfigVO shift = LhScheduleTimeUtil.getShiftByIndex(
                context, context.getScheduleTargetDate(), shiftIndex);
        if (shift == null) {
            return null;
        }
        ShiftEnum shiftEnum = shift.resolveShiftTypeEnum();
        return shiftEnum != null ? shiftEnum.getCode() : null;
    }

    private RollingMachineState buildInitialState(LhScheduleContext context, String machineCode) {
        MachineScheduleDTO machine = context.getInitialMachineScheduleMap().get(machineCode);
        if (machine == null) {
            machine = context.getMachineScheduleMap().get(machineCode);
        }
        RollingMachineState state = new RollingMachineState();
        if (machine != null) {
            state.setCurrentMaterialCode(machine.getCurrentMaterialCode());
            state.setCurrentMaterialDesc(machine.getCurrentMaterialDesc());
            state.setPreviousMaterialCode(machine.getPreviousMaterialCode());
            state.setPreviousMaterialDesc(machine.getPreviousMaterialDesc());
            state.setEstimatedEndTime(machine.getEstimatedEndTime());
        }
        return state;
    }

    private void updateRollingState(RollingMachineState state, LhScheduleResult result) {
        state.setPreviousMaterialCode(state.getCurrentMaterialCode());
        state.setPreviousMaterialDesc(state.getCurrentMaterialDesc());
        state.setCurrentMaterialCode(result.getMaterialCode());
        state.setCurrentMaterialDesc(result.getMaterialDesc());
        state.setEstimatedEndTime(result.getSpecEndTime());
    }

    /**
     * 换模计划滚动前规格状态。
     */
    private static class RollingMachineState {

        private String currentMaterialCode;
        private String currentMaterialDesc;
        private String previousMaterialCode;
        private String previousMaterialDesc;
        private Date estimatedEndTime;

        public String getCurrentMaterialCode() {
            return currentMaterialCode;
        }

        public void setCurrentMaterialCode(String currentMaterialCode) {
            this.currentMaterialCode = currentMaterialCode;
        }

        public String getCurrentMaterialDesc() {
            return currentMaterialDesc;
        }

        public void setCurrentMaterialDesc(String currentMaterialDesc) {
            this.currentMaterialDesc = currentMaterialDesc;
        }

        public String getPreviousMaterialCode() {
            return previousMaterialCode;
        }

        public void setPreviousMaterialCode(String previousMaterialCode) {
            this.previousMaterialCode = previousMaterialCode;
        }

        public String getPreviousMaterialDesc() {
            return previousMaterialDesc;
        }

        public void setPreviousMaterialDesc(String previousMaterialDesc) {
            this.previousMaterialDesc = previousMaterialDesc;
        }

        public Date getEstimatedEndTime() {
            return estimatedEndTime;
        }

        public void setEstimatedEndTime(Date estimatedEndTime) {
            this.estimatedEndTime = estimatedEndTime;
        }
    }
}
