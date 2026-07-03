package com.zlt.aps.lh.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zlt.aps.lh.api.constant.LhScheduleConstant;
import com.zlt.aps.lh.api.domain.dto.SkuScheduleDTO;
import com.zlt.aps.lh.api.domain.entity.LhMouldChangePlan;
import com.zlt.aps.lh.api.domain.entity.LhScheduleProcessLog;
import com.zlt.aps.lh.api.domain.entity.LhScheduleResult;
import com.zlt.aps.lh.api.domain.entity.LhUnscheduledResult;
import com.zlt.aps.lh.api.enums.DeleteFlagEnum;
import com.zlt.aps.lh.api.enums.ScheduleStepEnum;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.exception.ScheduleErrorCode;
import com.zlt.aps.lh.exception.ScheduleException;
import com.zlt.aps.lh.mapper.LhMouldChangePlanEntityMapper;
import com.zlt.aps.lh.mapper.LhScheduleProcessLogMapper;
import com.zlt.aps.lh.mapper.LhScheduleResultMapper;
import com.zlt.aps.lh.mapper.LhUnscheduledResultMapper;
import com.zlt.aps.lh.service.ILhScheduleResultService;
import com.zlt.aps.lh.util.LhScheduleTimeUtil;
import com.zlt.aps.lh.component.MonthPlanDateResolver;
import com.zlt.aps.lh.util.ShiftFieldUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import javax.annotation.Resource;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 排程结果原子替换持久化服务。
 *
 * <p>业务职责：</p>
 * <ul>
 *   <li>在事务中完成目标日旧排程结果、未排结果、换模计划和过程日志的删除与新数据写入；</li>
 *   <li>保存前二次校验发布状态，避免覆盖已下发 MES 的目标日结果；</li>
 *   <li>补齐审计字段，并统一计算 class1～class8 班次收尾标记；</li>
 *   <li>保持 S4.6 的持久化边界集中，避免各策略直接写库。</li>
 * </ul>
 *
 * <p>注意：该服务不重新计算排程量，不改变机台选择和排序，只做保存前字段补齐与同目标日原子替换。</p>
 *
 * @author APS
 */
@Slf4j
@Service
public class SchedulePersistenceService {

    private static final String DEFAULT_OPERATOR = "system";

    private static final String YES_FLAG = "1";

    private static final String NO_FLAG = "0";

    /** 胎胚收尾标识：1-收尾 */
    private static final int EMBRYO_ENDING_FLAG_YES = 1;

    /** 胎胚收尾班次原因分析备注 */
    private static final String EMBRYO_ENDING_ANALYSIS = "胎胚收尾";

    @Resource
    private LhScheduleResultMapper scheduleResultMapper;

    @Resource
    private LhUnscheduledResultMapper unscheduledResultMapper;

    @Resource
    private LhMouldChangePlanEntityMapper mouldChangePlanMapper;

    @Resource
    private LhScheduleProcessLogMapper processLogMapper;

    @Resource
    private ILhScheduleResultService scheduleResultService;

    /**
     * 以事务方式原子替换目标日排程结果。
     *
     * <p>处理流程：</p>
     * <ol>
     *   <li>再次校验目标日是否已有已发布结果；</li>
     *   <li>删除同工厂同目标日旧排程结果、未排结果和换模计划；</li>
     *   <li>按旧批次号删除旧过程日志；</li>
     *   <li>补齐新结果审计字段和班次收尾标记；</li>
     *   <li>批量写入新结果、未排结果、换模计划和过程日志。</li>
     * </ol>
     *
     * @param context 排程上下文
     */
    @Transactional(rollbackFor = Exception.class)
    public void replaceScheduleAtomically(LhScheduleContext context) {
        Date targetDate = LhScheduleTimeUtil.clearTime(context.getScheduleTargetDate());
        String factoryCode = context.getFactoryCode();

        int releasedCount = scheduleResultService.countReleasedByDate(targetDate, factoryCode);
        if (releasedCount > 0) {
            throw new ScheduleException(ScheduleStepEnum.S4_6_RESULT_VALIDATION,
                    ScheduleErrorCode.MES_RELEASED,
                    factoryCode, context.getBatchNo(),
                    "目标日已有已发布排程，禁止覆盖。排程日期: " + LhScheduleTimeUtil.getDateStr(targetDate));
        }

        List<LhScheduleResult> oldResults = scheduleResultMapper.selectList(
                new LambdaQueryWrapper<LhScheduleResult>()
                        .eq(LhScheduleResult::getFactoryCode, factoryCode)
                        .eq(LhScheduleResult::getScheduleDate, targetDate)
                        .eq(LhScheduleResult::getIsDelete, DeleteFlagEnum.NORMAL.getCode()));

        Set<String> oldBatchNos = oldResults.stream()
                .map(LhScheduleResult::getBatchNo)
                .filter(StringUtils::isNotBlank)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        int deletedResultCount = scheduleResultMapper.delete(
                new LambdaQueryWrapper<LhScheduleResult>()
                        .eq(LhScheduleResult::getFactoryCode, factoryCode)
                        .eq(LhScheduleResult::getScheduleDate, targetDate)
                        .eq(LhScheduleResult::getIsDelete, DeleteFlagEnum.NORMAL.getCode()));

        int deletedUnscheduledCount = unscheduledResultMapper.delete(
                new LambdaQueryWrapper<LhUnscheduledResult>()
                        .eq(LhUnscheduledResult::getFactoryCode, factoryCode)
                        .eq(LhUnscheduledResult::getScheduleDate, targetDate)
                        .eq(LhUnscheduledResult::getIsDelete, DeleteFlagEnum.NORMAL.getCode()));

        int deletedMouldPlanCount = mouldChangePlanMapper.delete(
                new LambdaQueryWrapper<LhMouldChangePlan>()
                        .eq(LhMouldChangePlan::getFactoryCode, factoryCode)
                        .eq(LhMouldChangePlan::getScheduleDate, targetDate)
                        .eq(LhMouldChangePlan::getIsDelete, DeleteFlagEnum.NORMAL.getCode()));

        int deletedLogCount = 0;
        for (String batchNo : oldBatchNos) {
            deletedLogCount += processLogMapper.delete(new LambdaQueryWrapper<LhScheduleProcessLog>()
                    .eq(LhScheduleProcessLog::getBatchNo, batchNo)
                    .eq(LhScheduleProcessLog::getIsDelete, DeleteFlagEnum.NORMAL.getCode()));
        }

        if (!context.getScheduleResultList().isEmpty()) {
            // 为排程结果补齐审计字段和班次收尾标记；不改变已生成的班次计划量。
            fillScheduleResultAuditInfo(context, context.getScheduleResultList());
            fillClassEndFlags(context, context.getScheduleResultList());
            // 胎胚收尾结果行的最后有计划量班次追加"胎胚收尾"备注
            fillEmbryoEndingAnalysis(context, context.getScheduleResultList());
            fillDayNRange(context, context.getScheduleResultList());
            fillShortageQty(context, context.getScheduleResultList());
            // 回填 SKU 排序名次/描述（来源 sortByPriority 回写到 sourceSku）
            fillSkuSortInfo(context, context.getScheduleResultList());
            // 回填 T/T+1/T+2 三天的结构计划机台数、结构已排机台数、SKU 已排机台数串
            fillMachineCountRange(context, context.getScheduleResultList());
            scheduleResultMapper.insertBatch(context.getScheduleResultList());
        }
        if (!context.getUnscheduledResultList().isEmpty()) {
            unscheduledResultMapper.insertBatch(context.getUnscheduledResultList());
        }
        if (!context.getMouldChangePlanList().isEmpty()) {
            mouldChangePlanMapper.insertBatch(context.getMouldChangePlanList());
        }
        if (!context.getScheduleLogList().isEmpty()) {
            processLogMapper.insertBatch(context.getScheduleLogList());
        }

        log.info("目标日排程原子替换完成, 工厂: {}, 日期: {}, 删除结果: {}, 删除未排: {}, 删除换模: {}, 删除日志: {}, 新结果: {}, 新未排: {}, 新换模: {}, 新日志: {}",
                factoryCode, LhScheduleTimeUtil.formatDate(targetDate),
                deletedResultCount, deletedUnscheduledCount, deletedMouldPlanCount, deletedLogCount,
                context.getScheduleResultList().size(), context.getUnscheduledResultList().size(),
                context.getMouldChangePlanList().size(), context.getScheduleLogList().size());
    }

    /**
     * 为排程结果补齐审计字段。
     *
     * @param context 排程上下文
     * @param scheduleResults 排程结果列表
     */
    private void fillScheduleResultAuditInfo(LhScheduleContext context, List<LhScheduleResult> scheduleResults) {
        Date now = new Date();
        String operator = resolveOperator(context);
        for (LhScheduleResult scheduleResult : scheduleResults) {
            if (Objects.isNull(scheduleResult.getCreateTime())) {
                scheduleResult.setCreateTime(now);
            }
            if (Objects.isNull(scheduleResult.getUpdateTime())) {
                scheduleResult.setUpdateTime(now);
            }
            if (StringUtils.isEmpty(scheduleResult.getCreateBy())) {
                scheduleResult.setCreateBy(operator);
            }
            if (StringUtils.isEmpty(scheduleResult.getUpdateBy())) {
                scheduleResult.setUpdateBy(operator);
            }
        }
    }

    /**
     * 保存前统一计算1-8班收尾标记。
     *
     * <p>每台机在当前分组内最后一个有计划量的班次早于窗口末班时视为机台收尾；
     * 若最后有量班次已经是窗口末班，则仅在 SKU 整体收尾时标记收尾。</p>
     *
     * @param context 排程上下文
     * @param scheduleResults 排程结果列表
     */
    private void fillClassEndFlags(LhScheduleContext context, List<LhScheduleResult> scheduleResults) {
        if (CollectionUtils.isEmpty(scheduleResults)) {
            return;
        }
        List<List<LhScheduleResult>> resultGroups = buildClassEndResultGroups(context, scheduleResults);
        for (List<LhScheduleResult> resultGroup : resultGroups) {
            fillClassEndFlagsForGroup(context, resultGroup);
        }
    }

    /**
     * 构建班次收尾标记分组。
     * <p>优先使用运行态来源SKU映射，保证同物料不同账本结果不串组；缺少来源SKU时按排程类型和物料编码分组。</p>
     *
     * @param context 排程上下文
     * @param scheduleResults 排程结果列表
     * @return 分组后的排程结果
     */
    private List<List<LhScheduleResult>> buildClassEndResultGroups(LhScheduleContext context,
                                                                   List<LhScheduleResult> scheduleResults) {
        List<List<LhScheduleResult>> resultGroups = new ArrayList<>(scheduleResults.size());
        Map<String, IdentityHashMap<SkuScheduleDTO, List<LhScheduleResult>>> sourceSkuGroupMap =
                new LinkedHashMap<>(8);
        Map<String, List<LhScheduleResult>> resultFieldGroupMap = new LinkedHashMap<>(16);
        Map<LhScheduleResult, SkuScheduleDTO> sourceSkuMap = Objects.nonNull(context)
                ? context.getScheduleResultSourceSkuMap() : null;
        for (LhScheduleResult result : scheduleResults) {
            if (Objects.isNull(result)) {
                continue;
            }
            SkuScheduleDTO sourceSku = Objects.nonNull(sourceSkuMap) ? sourceSkuMap.get(result) : null;
            if (Objects.nonNull(sourceSku)) {
                String phaseKey = buildClassEndPhaseKey(result);
                IdentityHashMap<SkuScheduleDTO, List<LhScheduleResult>> phaseGroupMap =
                        sourceSkuGroupMap.computeIfAbsent(phaseKey, key -> new IdentityHashMap<SkuScheduleDTO, List<LhScheduleResult>>(8));
                List<LhScheduleResult> group = phaseGroupMap.get(sourceSku);
                if (Objects.isNull(group)) {
                    group = new ArrayList<>(2);
                    phaseGroupMap.put(sourceSku, group);
                }
                group.add(result);
                continue;
            }
            if (StringUtils.isEmpty(result.getMaterialCode())) {
                List<LhScheduleResult> singleResultGroup = new ArrayList<>(1);
                singleResultGroup.add(result);
                resultGroups.add(singleResultGroup);
                continue;
            }
            String resultFieldKey = buildClassEndPhaseKey(result) + "#" + result.getMaterialCode();
            resultFieldGroupMap.computeIfAbsent(resultFieldKey, key -> new ArrayList<LhScheduleResult>(2))
                    .add(result);
        }
        for (IdentityHashMap<SkuScheduleDTO, List<LhScheduleResult>> phaseGroupMap : sourceSkuGroupMap.values()) {
            resultGroups.addAll(phaseGroupMap.values());
        }
        resultGroups.addAll(resultFieldGroupMap.values());
        return resultGroups;
    }

    /**
     * 按同SKU结果分组计算班次收尾标记。
     *
     * <p>该方法只写 classNIsEnd 字段，不调整 classNPlanQty 或排程结束时间。</p>
     *
     * @param context 排程上下文
     * @param resultGroup 同SKU结果分组
     */
    private void fillClassEndFlagsForGroup(LhScheduleContext context, List<LhScheduleResult> resultGroup) {
        if (CollectionUtils.isEmpty(resultGroup)) {
            return;
        }
        List<LhScheduleResult> plannedResults = resolvePlannedResults(resultGroup);
        boolean multiMachine = countDistinctMachine(plannedResults) > 1;
        boolean skuEnding = containsSkuEnding(plannedResults);
        Map<String, Integer> machineLastPlannedShiftMap = buildMachineLastPlannedShiftMap(plannedResults);
        for (LhScheduleResult result : resultGroup) {
            if (Objects.isNull(result)) {
                continue;
            }
            boolean hasPlanQty = ShiftFieldUtil.resolveScheduledQty(result) > 0;
            int currentLastPlannedShift = ShiftFieldUtil.resolveLastPlannedShiftIndex(result);
            boolean currentMachineLastPlan = hasPlanQty
                    && isMachineLastPlannedResult(result, currentLastPlannedShift, machineLastPlannedShiftMap);
            boolean beforeWindowEndShift = currentLastPlannedShift > 0
                    && currentLastPlannedShift < LhScheduleConstant.MAX_SHIFT_SLOT_COUNT;
            boolean windowEndShift = currentLastPlannedShift == LhScheduleConstant.MAX_SHIFT_SLOT_COUNT;
            boolean endingMachine = currentMachineLastPlan && (beforeWindowEndShift || (windowEndShift && skuEnding));
            int lastPlannedShift = ShiftFieldUtil.applyLastPlannedShiftEndMark(result, endingMachine);
            log.info("排程结果班次收尾标记, SKU: {}, 机台: {}, 是否多机台: {}, SKU是否收尾: {}, "
                            + "是否收尾机台: {}, 是否机台最后计划: {}, 是否窗口末班: {}, 最后有计划量班次: {}, {}",
                    result.getMaterialCode(), result.getLhMachineCode(), oneZero(multiMachine), oneZero(skuEnding),
                    oneZero(endingMachine), oneZero(currentMachineLastPlan), oneZero(windowEndShift),
                    lastPlannedShift > 0 ? lastPlannedShift : 0, ShiftFieldUtil.buildShiftIsEndSummary(result));
        }
    }

    /**
     * 保存前回写胎胚收尾标识，并为胎胚收尾结果行的最后有计划量班次追加"胎胚收尾"备注。
     *
     * <p>判定依据：{@code embryoEndingFlagMap} 中该胎胚代码对应值为 1 表示胎胚收尾。
     * 胎胚收尾时回写 {@code isEmbryoEnding} 为 '1'，并在结果行最后一个有计划量的班次的
     * {@code classNAnalysis} 末尾追加"胎胚收尾"备注，不覆盖已有备注，已有该备注时跳过；
     * 非胎胚收尾、embryoCode 为空或 map 为空时回写为 '0'，保证字段非空落库。</p>
     *
     * @param context 排程上下文
     * @param scheduleResults 排程结果列表
     */
    private void fillEmbryoEndingAnalysis(LhScheduleContext context, List<LhScheduleResult> scheduleResults) {
        if (Objects.isNull(context) || CollectionUtils.isEmpty(scheduleResults)) {
            return;
        }
        Map<String, Integer> embryoEndingFlagMap = context.getEmbryoEndingFlagMap();
        boolean endingFlagMapEmpty = CollectionUtils.isEmpty(embryoEndingFlagMap);
        for (LhScheduleResult result : scheduleResults) {
            if (Objects.isNull(result)) {
                continue;
            }
            // 默认非胎胚收尾
            String embryoEndingFlag = NO_FLAG;
            if (!endingFlagMapEmpty && StringUtils.isNotEmpty(result.getEmbryoCode())) {
                Integer endingFlag = embryoEndingFlagMap.get(result.getEmbryoCode());
                if (Integer.valueOf(EMBRYO_ENDING_FLAG_YES).equals(endingFlag)) {
                    embryoEndingFlag = YES_FLAG;
                    // 胎胚收尾：取最后有计划量的班次，在其原因分析末尾追加胎胚收尾备注
                    int lastPlannedShiftIndex = ShiftFieldUtil.resolveLastPlannedShiftIndex(result);
                    if (lastPlannedShiftIndex > 0) {
                        appendEmbryoEndingAnalysis(result, lastPlannedShiftIndex);
                    }
                }
            }
            // 统一回写胎胚收尾标识，保证落库非空
            result.setIsEmbryoEnding(embryoEndingFlag);
        }
    }

    /**
     * 在指定班次的原因分析末尾追加"胎胚收尾"备注。
     * <p>已有备注为空时直接写入；已有备注且未包含该文案时以"；"分隔追加；已包含时跳过。</p>
     *
     * @param result 排程结果
     * @param shiftIndex 班次索引
     */
    private void appendEmbryoEndingAnalysis(LhScheduleResult result, int shiftIndex) {
        String oldAnalysis = ShiftFieldUtil.getShiftAnalysis(result, shiftIndex);
        if (StringUtils.contains(oldAnalysis, EMBRYO_ENDING_ANALYSIS)) {
            return;
        }
        String newAnalysis;
        if (StringUtils.isEmpty(oldAnalysis)) {
            newAnalysis = EMBRYO_ENDING_ANALYSIS;
        } else {
            newAnalysis = new StringBuilder(oldAnalysis.length() + EMBRYO_ENDING_ANALYSIS.length() + 1)
                    .append(oldAnalysis).append('；').append(EMBRYO_ENDING_ANALYSIS).toString();
        }
        ShiftFieldUtil.setShiftAnalysis(result, shiftIndex, newAnalysis);
        log.info("胎胚收尾班次备注追加, factoryCode: {}, scheduleDate: {}, materialCode: {}, "
                        + "embryoCode: {}, machineCode: {}, shiftIndex: {}, analysis: {}",
                result.getFactoryCode(), result.getScheduleDate(), result.getMaterialCode(),
                result.getEmbryoCode(), result.getLhMachineCode(), shiftIndex, newAnalysis);
    }

    /**
     * 获取有计划量的结果。
     *
     * @param resultGroup 同SKU结果分组
     * @return 有计划量的结果
     */
    private List<LhScheduleResult> resolvePlannedResults(List<LhScheduleResult> resultGroup) {
        List<LhScheduleResult> plannedResults = new ArrayList<>(resultGroup.size());
        for (LhScheduleResult result : resultGroup) {
            if (Objects.nonNull(result) && ShiftFieldUtil.resolveScheduledQty(result) > 0) {
                plannedResults.add(result);
            }
        }
        return plannedResults;
    }

    /**
     * 统计参与排产的机台数量。
     *
     * @param plannedResults 有计划量的结果
     * @return 机台数量
     */
    private int countDistinctMachine(List<LhScheduleResult> plannedResults) {
        if (CollectionUtils.isEmpty(plannedResults)) {
            return 0;
        }
        Set<String> machineCodes = new LinkedHashSet<>(plannedResults.size());
        for (LhScheduleResult result : plannedResults) {
            if (Objects.nonNull(result) && StringUtils.isNotEmpty(result.getLhMachineCode())) {
                machineCodes.add(result.getLhMachineCode());
            }
        }
        return machineCodes.size();
    }

    /**
     * 判断当前SKU是否已按既有逻辑标记收尾。
     *
     * @param plannedResults 有计划量的结果
     * @return true-收尾；false-非收尾
     */
    private boolean containsSkuEnding(List<LhScheduleResult> plannedResults) {
        if (CollectionUtils.isEmpty(plannedResults)) {
            return false;
        }
        for (LhScheduleResult result : plannedResults) {
            if (Objects.nonNull(result) && StringUtils.equals(YES_FLAG, result.getIsEnd())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 构建每台机在同 SKU 分组内的最后有量班次。
     *
     * @param plannedResults 有计划量的结果
     * @return 机台号到最后有量班次的映射
     */
    private Map<String, Integer> buildMachineLastPlannedShiftMap(List<LhScheduleResult> plannedResults) {
        Map<String, Integer> machineLastPlannedShiftMap = new LinkedHashMap<>(plannedResults.size());
        for (LhScheduleResult result : plannedResults) {
            if (Objects.isNull(result) || StringUtils.isEmpty(result.getLhMachineCode())) {
                continue;
            }
            int lastPlannedShift = ShiftFieldUtil.resolveLastPlannedShiftIndex(result);
            if (lastPlannedShift <= 0) {
                continue;
            }
            machineLastPlannedShiftMap.merge(result.getLhMachineCode(), lastPlannedShift, Math::max);
        }
        return machineLastPlannedShiftMap;
    }

    /**
     * 判断当前结果是否为该机台在同 SKU 分组内的最后一次排产。
     *
     * @param result 排程结果
     * @param currentLastPlannedShift 当前结果最后有量班次
     * @param machineLastPlannedShiftMap 机台号到最后有量班次的映射
     * @return true-该结果是机台最后计划；false-不是
     */
    private boolean isMachineLastPlannedResult(LhScheduleResult result,
                                               int currentLastPlannedShift,
                                               Map<String, Integer> machineLastPlannedShiftMap) {
        if (Objects.isNull(result) || StringUtils.isEmpty(result.getLhMachineCode())
                || currentLastPlannedShift <= 0 || CollectionUtils.isEmpty(machineLastPlannedShiftMap)) {
            return false;
        }
        Integer machineLastPlannedShift = machineLastPlannedShiftMap.get(result.getLhMachineCode());
        return Objects.nonNull(machineLastPlannedShift)
                && Objects.equals(machineLastPlannedShift, currentLastPlannedShift);
    }

    /**
     * 构建班次收尾分组的排产阶段键。
     *
     * @param result 排程结果
     * @return 排产阶段键
     */
    private String buildClassEndPhaseKey(LhScheduleResult result) {
        return StringUtils.defaultString(result.getScheduleType()) + "#"
                + StringUtils.defaultString(result.getIsTypeBlock());
    }

    /**
     * 格式化布尔值为业务标识。
     *
     * @param value 布尔值
     * @return 1-是，0-否
     */
    private String oneZero(boolean value) {
        return value ? YES_FLAG : NO_FLAG;
    }

    /**
     * 获取审计字段操作人。
     *
     * @param context 排程上下文
     * @return 操作人
     */
    private String resolveOperator(LhScheduleContext context) {
        if (Objects.nonNull(context) && StringUtils.isNotEmpty(context.getOperator())) {
            return context.getOperator();
        }
        return DEFAULT_OPERATOR;
    }

    /**
     * 为排程结果填充 T~T+2 日计划量（来自月计划 dayN）。
     * <p>按 materialCode + productStatus 匹配月计划，取 scheduleDate 对应日以及后两天的月计划量，逗号分隔。</p>
     *
     * @param context 排程上下文
     * @param scheduleResults 排程结果列表
     */
    private void fillDayNRange(LhScheduleContext context, List<LhScheduleResult> scheduleResults) {
        if (Objects.isNull(context) || CollectionUtils.isEmpty(scheduleResults)) {
            return;
        }
        for (LhScheduleResult result : scheduleResults) {
            if (Objects.isNull(result) || StringUtils.isEmpty(result.getMaterialCode())
                    || Objects.isNull(result.getScheduleDate())) {
                continue;
            }
            // 使用全局窗口起点 T 日计算 DAY_N_RANGE，而非 result 上的目标日
            String dayNRange = resolveDayNRange(
                    context, result.getMaterialCode(), result.getProductStatus(), context.getScheduleDate());
            result.setDayNRange(dayNRange);
        }
    }

    /**
     * 根据排程窗口起点、物料编码和产品状态，返回 T~T+2 的日计划量逗号分隔字符串。
     *
     * @param context 排程上下文
     * @param materialCode 物料编码
     * @param productStatus 产品状态
     * @param scheduleDate 排程日期 T
     * @return 日计划量逗号分隔，如 "100,120,110"
     */
    private String resolveDayNRange(LhScheduleContext context,
                                    String materialCode,
                                    String productStatus,
                                    Date scheduleDate) {
        Calendar cal = Calendar.getInstance();
        cal.setTime(scheduleDate);
        StringBuilder sb = new StringBuilder(16);
        for (int offset = 0; offset < 3; offset++) {
            if (offset > 0) {
                sb.append(",");
            }
            LocalDate productionDate = cal.getTime().toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
            int qty = MonthPlanDateResolver.resolveDayQty(context, materialCode, productStatus, productionDate);
            sb.append(qty);
            cal.add(Calendar.DAY_OF_MONTH, 1);
        }
        return sb.toString();
    }

    /**
     * 为排程结果填充月初至 T-1 日累计欠产量。
     * <p>欠产数据来源于上下文 {@code carryForwardQtyMap}，由 ScheduleAdjustHandler 按物料+产品状态归集。</p>
     *
     * @param context 排程上下文
     * @param scheduleResults 排程结果列表
     */
    private void fillShortageQty(LhScheduleContext context, List<LhScheduleResult> scheduleResults) {
        if (Objects.isNull(context) || CollectionUtils.isEmpty(scheduleResults)) {
            return;
        }
        Map<String, Integer> carryForwardQtyMap = context.getCarryForwardQtyMap();
        if (CollectionUtils.isEmpty(carryForwardQtyMap)) {
            return;
        }
        for (LhScheduleResult result : scheduleResults) {
            if (Objects.isNull(result) || StringUtils.isEmpty(result.getMaterialCode())) {
                continue;
            }
            String materialStatusKey = MonthPlanDateResolver.buildMaterialStatusKey(
                    result.getMaterialCode(), result.getProductStatus());
            Integer shortageQty = carryForwardQtyMap.get(materialStatusKey);
            if (Objects.nonNull(shortageQty)) {
                result.setShortageQty(shortageQty);
            }
        }
    }

    /**
     * 回填 SKU 排序名次（{@code skuSortRank}）和单行描述（{@code skuSortDesc}）。
     * <p>通过 {@code scheduleResultSourceSkuMap} 按对象身份取到来源 SKU 后，
     * 写入 sortRank/sortDesc；与“SKU排序优先级汇总”日志同源。
     * 滚动继承结果与无来源 SKU 的占位结果保持原值不覆盖。</p>
     *
     * @param context 排程上下文
     * @param scheduleResults 排程结果列表
     */
    private void fillSkuSortInfo(LhScheduleContext context, List<LhScheduleResult> scheduleResults) {
        if (Objects.isNull(context) || CollectionUtils.isEmpty(scheduleResults)) {
            return;
        }
        Map<LhScheduleResult, SkuScheduleDTO> sourceSkuMap = context.getScheduleResultSourceSkuMap();
        if (CollectionUtils.isEmpty(sourceSkuMap)) {
            return;
        }
        for (LhScheduleResult result : scheduleResults) {
            if (Objects.isNull(result) || result.isRollingInherited()) {
                continue;
            }
            SkuScheduleDTO sourceSku = sourceSkuMap.get(result);
            if (Objects.isNull(sourceSku)) {
                continue;
            }
            // 排序中存在的 SKU 才有 sortRank/sortDesc；未参与排序的 SKU 保留默认空值。
            if (sourceSku.getSortRank() > 0) {
                result.setSkuSortRank(sourceSku.getSortRank());
            }
            if (StringUtils.isNotEmpty(sourceSku.getSortDesc())) {
                result.setSkuSortDesc(sourceSku.getSortDesc());
            }
        }
    }

    /**
     * 回填结构计划/已排机台数串与 SKU 已排机台数串，格式 {@code T=N,T+1=N,T+2=N}。
     * <p>T 日为 {@code context.scheduleDate}，与 {@code dayNRange} 同窗口；
     * 结构名或物料编码为空时对应字段不写；
     * 滚动继承结果保留原值不覆盖。</p>
     *
     * @param context 排程上下文
     * @param scheduleResults 排程结果列表
     */
    private void fillMachineCountRange(LhScheduleContext context, List<LhScheduleResult> scheduleResults) {
        if (Objects.isNull(context) || CollectionUtils.isEmpty(scheduleResults)
                || Objects.isNull(context.getScheduleDate())) {
            return;
        }
        // 排程窗口 T、T+1、T+2 日 LocalDate，使用系统时区与运行态 Map key 对齐
        LocalDate baseDate = context.getScheduleDate().toInstant()
                .atZone(ZoneId.systemDefault()).toLocalDate();
        LocalDate[] dates = new LocalDate[]{baseDate, baseDate.plusDays(1), baseDate.plusDays(2)};
        for (LhScheduleResult result : scheduleResults) {
            if (Objects.isNull(result) || result.isRollingInherited()) {
                continue;
            }
            if (StringUtils.isNotEmpty(result.getStructureName())) {
                result.setStructurePlanMachineCountRange(
                        buildStructurePlanMachineCountRange(context, dates, result.getStructureName()));
                result.setStructureScheduledMachineCountRange(
                        buildStructureScheduledMachineCountRange(context, dates, result.getStructureName()));
            }
            if (StringUtils.isNotEmpty(result.getMaterialCode())) {
                result.setSkuScheduledMachineCountRange(
                        buildSkuScheduledMachineCountRange(context, dates, result.getMaterialCode()));
            }
        }
    }

    /**
     * 按结构维度拼接结构计划机台数串。
     *
     * @param context 排程上下文
     * @param dates T/T+1/T+2 三天 LocalDate
     * @param structureName 产品结构
     * @return 形如 {@code T=2,T+1=2,T+2=3}
     */
    private String buildStructurePlanMachineCountRange(LhScheduleContext context,
                                                       LocalDate[] dates,
                                                       String structureName) {
        StringBuilder sb = new StringBuilder(24);
        for (int offset = 0; offset < dates.length; offset++) {
            appendDayKey(sb, offset);
            sb.append('=').append(context.getStructurePlanMachineCount(dates[offset], structureName));
        }
        return sb.toString();
    }

    /**
     * 按结构维度拼接结构已排机台数串。
     *
     * @param context 排程上下文
     * @param dates T/T+1/T+2 三天 LocalDate
     * @param structureName 产品结构
     * @return 形如 {@code T=2,T+1=2,T+2=3}
     */
    private String buildStructureScheduledMachineCountRange(LhScheduleContext context,
                                                            LocalDate[] dates,
                                                            String structureName) {
        StringBuilder sb = new StringBuilder(24);
        for (int offset = 0; offset < dates.length; offset++) {
            appendDayKey(sb, offset);
            sb.append('=').append(context.getStructureScheduledMachineCount(dates[offset], structureName));
        }
        return sb.toString();
    }

    /**
     * 按 SKU 维度拼接 SKU 已排机台数串。
     *
     * @param context 排程上下文
     * @param dates T/T+1/T+2 三天 LocalDate
     * @param materialCode 物料编码
     * @return 形如 {@code T=2,T+1=2,T+2=3}
     */
    private String buildSkuScheduledMachineCountRange(LhScheduleContext context,
                                                      LocalDate[] dates,
                                                      String materialCode) {
        StringBuilder sb = new StringBuilder(24);
        for (int offset = 0; offset < dates.length; offset++) {
            appendDayKey(sb, offset);
            sb.append('=').append(context.getSkuScheduledMachineCount(dates[offset], materialCode));
        }
        return sb.toString();
    }

    /**
     * 拼接 T / T+1 / T+2 等键名（首键无逗号前缀）。
     *
     * @param sb 目标 StringBuilder
     * @param offset 0=T, 1=T+1, 2=T+2
     */
    private void appendDayKey(StringBuilder sb, int offset) {
        if (sb.length() > 0) {
            sb.append(',');
        }
        if (offset == 0) {
            sb.append('T');
        } else {
            sb.append("T+").append(offset);
        }
    }
}
