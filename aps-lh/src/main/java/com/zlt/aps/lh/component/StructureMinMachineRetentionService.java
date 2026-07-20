package com.zlt.aps.lh.component;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zlt.aps.lh.api.constant.LhScheduleParamConstant;
import com.zlt.aps.lh.api.domain.dto.MachineScheduleDTO;
import com.zlt.aps.lh.api.domain.dto.SkuScheduleDTO;
import com.zlt.aps.lh.api.domain.entity.LhScheduleResult;
import com.zlt.aps.lh.api.domain.vo.LhShiftConfigVO;
import com.zlt.aps.lh.api.enums.DeleteFlagEnum;
import com.zlt.aps.lh.api.enums.ScheduleStepEnum;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.engine.strategy.IEndingJudgmentStrategy;
import com.zlt.aps.lh.exception.ScheduleErrorCode;
import com.zlt.aps.lh.exception.ScheduleException;
import com.zlt.aps.lh.mapper.FactoryParamMapper;
import com.zlt.aps.lh.mapper.MdmMonCycleSchStruConfMapper;
import com.zlt.aps.lh.util.LhScheduleTimeUtil;
import com.zlt.aps.lh.util.LhSingleControlMachineUtil;
import com.zlt.aps.lh.util.ShiftFieldUtil;
import com.zlt.aps.mdm.api.domain.entity.MdmMonCycleSchStruConf;
import com.zlt.aps.mp.api.domain.entity.FactoryParam;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 结构收尾最低机台数保留服务。
 *
 * <p>本服务统一负责三个阶段：</p>
 * <ol>
 *   <li>S4.3复用现有3天、8班SKU收尾判断，冻结可在当前窗口全部收尾的结构及最低机台配置；</li>
 *   <li>S4.4/S4.5结构尚有待排SKU且尚不能确认最终不命中时，临时保护该结构已经占用的机台，
 *       阻止后续SKU提前选走；</li>
 *   <li>结构全部SKU处理完成后，只按计划量大于0的班次统计最晚班次和物理机台数，命中时复用
 *       原结果行补计划量0、班次起止时间和占位备注，并把机台释放时间统一推迟到最晚班次结束。</li>
 * </ol>
 *
 * <p>计划量0只表达机台占用。本服务不调用日计划账本、SKU剩余量、胎胚库存或完成量扣减方法，
 * 也不新增排程结果行，因此不会改变任何产量统计口径。</p>
 *
 * @author APS
 */
@Slf4j
@Component
public class StructureMinMachineRetentionService {

    /** 周期结构 */
    private static final String CYCLE_STRUCTURE_TYPE = "01";
    /** 常规结构 */
    private static final String REGULAR_STRUCTURE_TYPE = "02";
    /** 周期结构配置来源类型：01-正常计划 */
    private static final String CYCLE_SOURCE_TYPE = "01";
    /** 规则未命中标识 */
    private static final String NOT_RETAINED_FLAG = "0";
    /** 规则命中标识 */
    private static final String RETAINED_FLAG = "1";
    /** 计划量0占位班次原因 */
    public static final String RETENTION_ANALYSIS = "结构最低机台数保留";

    @Resource
    private IEndingJudgmentStrategy endingJudgmentStrategy;
    @Resource
    private MdmMonCycleSchStruConfMapper cycleStructureConfigMapper;
    @Resource
    private FactoryParamMapper factoryParamMapper;

    /**
     * 初始化当前窗口可全部收尾的结构及最低机台数。
     *
     * <p>结构维度直接复用S4.3现有{@code structureSkuMap}；结构内每个SKU均通过
     * {@link IEndingJudgmentStrategy#isCurrentWindowEnding(LhScheduleContext, SkuScheduleDTO)}
     * 才纳入规则，避免错误复用仅用于排序的可配置结构收尾天数。</p>
     *
     * @param context 排程上下文
     */
    public void initializeEligibleStructures(LhScheduleContext context) {
        Map<String, List<SkuScheduleDTO>> eligibleStructureMap = new LinkedHashMap<String, List<SkuScheduleDTO>>(16);
        Map<String, Integer> minimumMachineMap = new LinkedHashMap<String, Integer>(16);
        if (Objects.isNull(context)) {
            return;
        }
        if (CollectionUtils.isEmpty(context.getStructureSkuMap())) {
            context.setCurrentWindowEndingStructureSkuMap(eligibleStructureMap);
            context.setStructureMinVulcanizingMachineMap(minimumMachineMap);
            return;
        }
        for (Map.Entry<String, List<SkuScheduleDTO>> entry : context.getStructureSkuMap().entrySet()) {
            String structureName = entry.getKey();
            List<SkuScheduleDTO> structureSkuList = entry.getValue();
            if (StringUtils.isEmpty(structureName) || CollectionUtils.isEmpty(structureSkuList)
                    || !isAllSkuCurrentWindowEnding(context, structureSkuList)) {
                continue;
            }
            List<SkuScheduleDTO> structureSnapshot = new ArrayList<SkuScheduleDTO>(structureSkuList);
            int minimumMachineCount = resolveMinimumMachineCount(context, structureName, structureSnapshot);
            eligibleStructureMap.put(structureName, structureSnapshot);
            minimumMachineMap.put(structureName, minimumMachineCount);
        }
        context.setCurrentWindowEndingStructureSkuMap(eligibleStructureMap);
        context.setStructureMinVulcanizingMachineMap(minimumMachineMap);
        log.info("结构最低机台数规则初始化完成, factoryCode: {}, scheduleDate: {}, eligibleStructureCount: {}, config: {}",
                context.getFactoryCode(), LhScheduleTimeUtil.formatDate(context.getScheduleDate()),
                eligibleStructureMap.size(), minimumMachineMap);
    }

    /**
     * 按当前最终结果刷新结构机台保护或计划量0占位。
     *
     * <p>该方法允许在续作、换活字块、新增排产阶段重复调用。结构未完成且最终命中状态尚不确定时
     * 才登记临时保护；若当前最晚有量班次已经是窗口末班，且该班物理机台数已达到最低值，则可提前
     * 确认不命中并释放临时保护。结构完成后仍执行一次最终统计。已经补过的班次只校验并补齐同一
     * 原结果行，不会新增或复制结果行。</p>
     *
     * @param context 排程上下文
     */
    public void refreshRetention(LhScheduleContext context) {
        if (Objects.isNull(context) || CollectionUtils.isEmpty(context.getCurrentWindowEndingStructureSkuMap())) {
            return;
        }
        for (String structureName : context.getCurrentWindowEndingStructureSkuMap().keySet()) {
            List<LhScheduleResult> structureResults = collectStructureResults(context, structureName);
            Set<String> occupiedMachineCodes = collectOccupiedMachineCodes(structureResults);
            if (CollectionUtils.isEmpty(occupiedMachineCodes)) {
                clearTemporaryProtection(context, structureName);
                continue;
            }
            if (isStructurePending(context, structureName)) {
                /*
                 * 当前最晚班次若已到窗口最后一班且生产机台数已达标，后续SKU不可能把最晚班次继续
                 * 后移，也不会减少已经稳定落地的末班结果。此时提前确认最终不命中，避免把本应按
                 * 原逻辑释放的机台错误挡在换活字块、历史反选或新增候选之外。
                 */
                if (context.getStructureMinMachineConfirmedNonRetainedStructureSet().contains(structureName)
                        || confirmNonRetentionAtWindowLastShift(
                        context, structureName, structureResults)) {
                    clearTemporaryProtection(context, structureName);
                    markStructureResults(structureResults, NOT_RETAINED_FLAG);
                    continue;
                }
                protectOccupiedMachines(context, structureName, occupiedMachineCodes);
                continue;
            }
            clearTemporaryProtection(context, structureName);
            applyFinalRetention(context, structureName, structureResults, occupiedMachineCodes);
        }
    }

    /**
     * 解析结构最低硫化机台数。
     *
     * @param context 排程上下文
     * @param structureName 结构名称
     * @param structureSkuList 结构SKU快照
     * @return 配置的最低硫化机台数
     */
    public int resolveMinimumMachineCount(LhScheduleContext context,
                                          String structureName,
                                          List<SkuScheduleDTO> structureSkuList) {
        SkuScheduleDTO firstSku = structureSkuList.get(0);
        validateConsistentStructureConfig(context, structureName, structureSkuList, firstSku);
        if (StringUtils.equals(CYCLE_STRUCTURE_TYPE, firstSku.getStructureType())) {
            return resolveCycleStructureMinimum(context, structureName, firstSku);
        }
        if (StringUtils.equals(REGULAR_STRUCTURE_TYPE, firstSku.getStructureType())) {
            return resolveRegularStructureMinimum(context, structureName);
        }
        throwConfigException(context, structureName,
                "月计划STRUCTURE_TYPE为空或不支持，实际值=" + firstSku.getStructureType());
        return 0;
    }

    /**
     * 判断结构内全部SKU是否均可在当前3天、8班窗口内收尾。
     *
     * @param context 排程上下文
     * @param structureSkuList 结构SKU列表
     * @return true-全部SKU当前窗口可收尾；false-至少一个SKU不可收尾
     */
    private boolean isAllSkuCurrentWindowEnding(LhScheduleContext context,
                                                List<SkuScheduleDTO> structureSkuList) {
        for (SkuScheduleDTO sku : structureSkuList) {
            if (Objects.isNull(sku) || !endingJudgmentStrategy.isCurrentWindowEnding(context, sku)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 校验同结构SKU的结构类型及年月一致，避免同一结构混用不同配置。
     *
     * @param context 排程上下文
     * @param structureName 结构名称
     * @param structureSkuList 结构SKU列表
     * @param firstSku 首个SKU
     */
    private void validateConsistentStructureConfig(LhScheduleContext context,
                                                   String structureName,
                                                   List<SkuScheduleDTO> structureSkuList,
                                                   SkuScheduleDTO firstSku) {
        if (Objects.isNull(firstSku)) {
            throwConfigException(context, structureName, "结构SKU为空，无法解析最低机台数");
        }
        for (SkuScheduleDTO sku : structureSkuList) {
            if (Objects.isNull(sku)
                    || !StringUtils.equals(firstSku.getStructureType(), sku.getStructureType())
                    || !Objects.equals(firstSku.getMonthPlanYear(), sku.getMonthPlanYear())
                    || !Objects.equals(firstSku.getMonthPlanMonth(), sku.getMonthPlanMonth())) {
                throwConfigException(context, structureName, "同结构SKU的STRUCTURE_TYPE或月计划年月不一致");
            }
        }
    }

    /**
     * 查询周期结构最低硫化机台数。
     *
     * @param context 排程上下文
     * @param structureName 结构名称
     * @param sku 结构SKU
     * @return 周期结构最低硫化机台数
     */
    private int resolveCycleStructureMinimum(LhScheduleContext context,
                                             String structureName,
                                             SkuScheduleDTO sku) {
        if (Objects.isNull(sku.getMonthPlanYear()) || Objects.isNull(sku.getMonthPlanMonth())) {
            throwConfigException(context, structureName, "周期结构月计划年份或月份为空");
        }
        List<MdmMonCycleSchStruConf> configs = cycleStructureConfigMapper.selectList(
                new LambdaQueryWrapper<MdmMonCycleSchStruConf>()
                        .eq(MdmMonCycleSchStruConf::getFactoryCode, context.getFactoryCode())
                        .eq(MdmMonCycleSchStruConf::getYear, sku.getMonthPlanYear())
                        .eq(MdmMonCycleSchStruConf::getMonth, sku.getMonthPlanMonth())
                        .eq(MdmMonCycleSchStruConf::getStructureName, structureName)
                        .eq(MdmMonCycleSchStruConf::getSourceType, CYCLE_SOURCE_TYPE)
                        .eq(MdmMonCycleSchStruConf::getIsDelete, DeleteFlagEnum.NORMAL.getCode()));
        if (CollectionUtils.isEmpty(configs) || configs.size() != 1) {
            throwConfigException(context, structureName,
                    "周期结构最低机台配置应唯一，实际记录数=" + (CollectionUtils.isEmpty(configs) ? 0 : configs.size()));
        }
        Integer minimumMachineCount = configs.get(0).getMinVulcanizingMachine();
        return validateMinimumMachineCount(context, structureName, minimumMachineCount,
                "T_DP_MONTH_CYCLE_STRUCT_CONFIG.MIN_VULCANIZING_MACHINE");
    }

    /**
     * 查询并解析常规结构最低硫化机台数。
     *
     * @param context 排程上下文
     * @param structureName 结构名称
     * @return 常规结构最低硫化机台数
     */
    private int resolveRegularStructureMinimum(LhScheduleContext context, String structureName) {
        List<FactoryParam> params = factoryParamMapper.selectList(
                new LambdaQueryWrapper<FactoryParam>()
                        .eq(FactoryParam::getFactoryCode, context.getFactoryCode())
                        .eq(FactoryParam::getParamCode,
                                LhScheduleParamConstant.REGULAR_STRUCTURE_MIN_VULCANIZING_MACHINE)
                        .eq(FactoryParam::getIsDelete, DeleteFlagEnum.NORMAL.getCode()));
        if (CollectionUtils.isEmpty(params) || params.size() != 1) {
            throwConfigException(context, structureName,
                    "工厂参数SYS0204012应唯一，实际记录数=" + (CollectionUtils.isEmpty(params) ? 0 : params.size()));
        }
        String paramValue = StringUtils.trim(params.get(0).getParamValue());
        if (StringUtils.isEmpty(paramValue)) {
            throwConfigException(context, structureName, "工厂参数SYS0204012的PARAM_VALUE为空");
        }
        try {
            return validateMinimumMachineCount(context, structureName, Integer.valueOf(paramValue),
                    "T_MP_FACTORY_PARAM.SYS0204012");
        } catch (NumberFormatException e) {
            throw new ScheduleException(ScheduleStepEnum.S4_3_ADJUST_AND_GATHER,
                    ScheduleErrorCode.DATA_INCOMPLETE, context.getFactoryCode(), context.getBatchNo(),
                    "结构[" + structureName + "]工厂参数SYS0204012格式错误，paramValue=" + paramValue, e);
        }
    }

    /**
     * 校验最低机台数配置。
     *
     * @param context 排程上下文
     * @param structureName 结构名称
     * @param minimumMachineCount 配置值
     * @param configSource 配置来源
     * @return 合法配置值
     */
    private int validateMinimumMachineCount(LhScheduleContext context,
                                            String structureName,
                                            Integer minimumMachineCount,
                                            String configSource) {
        if (Objects.isNull(minimumMachineCount) || minimumMachineCount < 0) {
            throwConfigException(context, structureName,
                    configSource + "缺失或小于0，实际值=" + minimumMachineCount);
        }
        return minimumMachineCount;
    }

    /**
     * 对结构最终结果执行最低机台数判断并补占位班次。
     *
     * @param context 排程上下文
     * @param structureName 结构名称
     * @param structureResults 结构结果列表
     * @param occupiedMachineCodes 结构已占用运行态机台编码
     */
    private void applyFinalRetention(LhScheduleContext context,
                                     String structureName,
                                     List<LhScheduleResult> structureResults,
                                     Set<String> occupiedMachineCodes) {
        int latestPositiveShiftIndex = resolveLatestPositiveShiftIndex(structureResults);
        if (latestPositiveShiftIndex < 1) {
            return;
        }
        Integer configuredMinimumMachineCount = context.getStructureMinVulcanizingMachineMap().get(structureName);
        if (Objects.isNull(configuredMinimumMachineCount)) {
            throwConfigException(context, structureName, "已纳入规则但未初始化最低机台数");
        }
        int minimumMachineCount = configuredMinimumMachineCount;
        int latestShiftMachineCount = countLatestShiftPhysicalMachines(
                structureResults, latestPositiveShiftIndex);
        if (latestShiftMachineCount >= minimumMachineCount) {
            markStructureResults(structureResults, NOT_RETAINED_FLAG);
            log.info("结构最低机台数保留未命中, structureName: {}, latestShift: {}, latestShiftMachineCount: {}, minimumMachineCount: {}",
                    structureName, latestPositiveShiftIndex, latestShiftMachineCount, minimumMachineCount);
            return;
        }
        LhShiftConfigVO latestShift = resolveShift(context, latestPositiveShiftIndex);
        if (Objects.isNull(latestShift) || Objects.isNull(latestShift.getShiftEndDateTime())) {
            throwConfigException(context, structureName,
                    "最晚有量班次缺少结束时间，shiftIndex=" + latestPositiveShiftIndex);
        }
        for (String machineCode : occupiedMachineCodes) {
            fillMachinePlaceholderShifts(context, structureName, structureResults,
                    machineCode, latestPositiveShiftIndex);
            delayMachineRelease(context, machineCode, latestShift.getShiftEndDateTime());
        }
        markStructureResults(structureResults, RETAINED_FLAG);
        context.getStructureMinMachineRetainedStructureSet().add(structureName);
        log.info("结构最低机台数保留命中, factoryCode: {}, scheduleDate: {}, structureName: {}, latestShift: {}, "
                        + "latestShiftMachineCount: {}, minimumMachineCount: {}, occupiedMachines: {}, releaseTime: {}",
                context.getFactoryCode(), LhScheduleTimeUtil.formatDate(context.getScheduleDate()), structureName,
                latestPositiveShiftIndex, latestShiftMachineCount, minimumMachineCount, occupiedMachineCodes,
                LhScheduleTimeUtil.formatDateTime(latestShift.getShiftEndDateTime()));
    }

    /**
     * 在提前收尾机台原结果行补计划量0、完整班次时间和占位备注。
     *
     * @param context 排程上下文
     * @param structureName 结构名称
     * @param structureResults 结构结果列表
     * @param machineCode 运行态机台编码
     * @param latestPositiveShiftIndex 结构最晚有量班次
     */
    private void fillMachinePlaceholderShifts(LhScheduleContext context,
                                              String structureName,
                                              List<LhScheduleResult> structureResults,
                                              String machineCode,
                                              int latestPositiveShiftIndex) {
        LhScheduleResult carrierResult = resolveMachineCarrierResult(structureResults, machineCode);
        if (Objects.isNull(carrierResult)) {
            return;
        }
        int machineLastPositiveShiftIndex = resolveMachineLastPositiveShiftIndex(structureResults, machineCode);
        if (machineLastPositiveShiftIndex < 1 || machineLastPositiveShiftIndex >= latestPositiveShiftIndex) {
            return;
        }
        for (int shiftIndex = machineLastPositiveShiftIndex + 1;
             shiftIndex <= latestPositiveShiftIndex; shiftIndex++) {
            if (hasPositiveMachinePlan(context, machineCode, shiftIndex)) {
                throw new ScheduleException(ScheduleStepEnum.S4_4_CONTINUOUS_PRODUCTION,
                        ScheduleErrorCode.RESULT_VALIDATION_FAILED,
                        context.getFactoryCode(), context.getBatchNo(),
                        "结构[" + structureName + "]保留机台[" + machineCode + "]班次[" + shiftIndex
                                + "]已被其它有效排程占用，无法补计划量0占位");
            }
            LhShiftConfigVO shift = resolveShift(context, shiftIndex);
            if (Objects.isNull(shift) || Objects.isNull(shift.getShiftStartDateTime())
                    || Objects.isNull(shift.getShiftEndDateTime())) {
                throwConfigException(context, structureName, "占位班次缺少起止时间，shiftIndex=" + shiftIndex);
            }
            Integer existingPlanQty = ShiftFieldUtil.getShiftPlanQty(carrierResult, shiftIndex);
            if (Objects.isNull(existingPlanQty)) {
                ShiftFieldUtil.setShiftPlanQty(carrierResult, shiftIndex, 0,
                        shift.getShiftStartDateTime(), shift.getShiftEndDateTime());
            } else if (existingPlanQty == 0) {
                // 重复执行时仍校正班次时间，保证历史空班或不完整占位不会留下有量字段之外的脏时间。
                ShiftFieldUtil.setShiftPlanQty(carrierResult, shiftIndex, 0,
                        shift.getShiftStartDateTime(), shift.getShiftEndDateTime());
            }
            ShiftFieldUtil.appendShiftAnalysis(carrierResult, shiftIndex, RETENTION_ANALYSIS);
        }
    }

    /**
     * 将机台释放时间延迟至结构最晚班次结束。
     *
     * @param context 排程上下文
     * @param machineCode 运行态机台编码
     * @param retentionEndTime 统一释放时间
     */
    private void delayMachineRelease(LhScheduleContext context,
                                     String machineCode,
                                     Date retentionEndTime) {
        context.getStructureMinMachineRetentionEndTimeMap().put(machineCode, retentionEndTime);
        MachineScheduleDTO machine = context.getMachineScheduleMap().get(machineCode);
        if (Objects.nonNull(machine) && (Objects.isNull(machine.getEstimatedEndTime())
                || machine.getEstimatedEndTime().before(retentionEndTime))) {
            machine.setEstimatedEndTime(retentionEndTime);
            machine.setEnding(true);
        }
    }

    /**
     * 收集指定结构的全部排程结果。
     *
     * @param context 排程上下文
     * @param structureName 结构名称
     * @return 结构结果列表
     */
    private List<LhScheduleResult> collectStructureResults(LhScheduleContext context, String structureName) {
        List<LhScheduleResult> structureResults = new ArrayList<LhScheduleResult>(8);
        if (CollectionUtils.isEmpty(context.getScheduleResultList())) {
            return structureResults;
        }
        for (LhScheduleResult result : context.getScheduleResultList()) {
            if (Objects.nonNull(result) && StringUtils.equals(structureName, result.getStructureName())) {
                structureResults.add(result);
            }
        }
        return structureResults;
    }

    /**
     * 收集结构有实际计划量的运行态机台编码。
     *
     * @param structureResults 结构结果列表
     * @return 运行态机台编码集合
     */
    private Set<String> collectOccupiedMachineCodes(List<LhScheduleResult> structureResults) {
        Set<String> occupiedMachineCodes = new LinkedHashSet<String>(8);
        for (LhScheduleResult result : structureResults) {
            if (Objects.nonNull(result) && StringUtils.isNotEmpty(result.getLhMachineCode())
                    && ShiftFieldUtil.resolveScheduledQty(result) > 0) {
                occupiedMachineCodes.add(result.getLhMachineCode());
            }
        }
        return occupiedMachineCodes;
    }

    /**
     * 解析结构最晚有计划量班次。
     *
     * @param structureResults 结构结果列表
     * @return 最晚有量班次；不存在返回-1
     */
    private int resolveLatestPositiveShiftIndex(List<LhScheduleResult> structureResults) {
        int latestShiftIndex = -1;
        for (LhScheduleResult result : structureResults) {
            latestShiftIndex = Math.max(latestShiftIndex, ShiftFieldUtil.resolveLastPlannedShiftIndex(result));
        }
        return latestShiftIndex;
    }

    /**
     * 统计结构最晚班次实际生产的去重物理机台数。
     *
     * @param structureResults 结构结果列表
     * @param latestShiftIndex 最晚有量班次
     * @return 去重物理机台数
     */
    private int countLatestShiftPhysicalMachines(List<LhScheduleResult> structureResults,
                                                 int latestShiftIndex) {
        Set<String> physicalMachineCodes = new LinkedHashSet<String>(8);
        for (LhScheduleResult result : structureResults) {
            Integer planQty = ShiftFieldUtil.getShiftPlanQty(result, latestShiftIndex);
            if (Objects.nonNull(planQty) && planQty > 0 && StringUtils.isNotEmpty(result.getLhMachineCode())) {
                physicalMachineCodes.add(
                        LhSingleControlMachineUtil.resolvePhysicalMachineCode(result.getLhMachineCode()));
            }
        }
        return physicalMachineCodes.size();
    }

    /**
     * 判断未完成结构是否已可在窗口末班提前确认不命中保留规则。
     *
     * <p>该提前结论只使用单调不变的条件：当前最晚有量班次必须等于本次窗口最大班次，且该班
     * 已稳定落地的去重物理机台数达到最低配置。后续待排SKU最多继续增加末班生产机台，不会出现
     * 更晚班次，因此无需继续保护该结构的提前收尾机台。其他情况仍保持原临时保护逻辑。</p>
     *
     * @param context 排程上下文
     * @param structureName 结构名称
     * @param structureResults 当前结构已落地结果
     * @return true-已提前确认不命中；false-最终状态仍不确定
     */
    private boolean confirmNonRetentionAtWindowLastShift(LhScheduleContext context,
                                                          String structureName,
                                                          List<LhScheduleResult> structureResults) {
        int latestPositiveShiftIndex = resolveLatestPositiveShiftIndex(structureResults);
        int maximumShiftIndex = resolveMaximumShiftIndex(context);
        if (latestPositiveShiftIndex < 1 || maximumShiftIndex < 1
                || latestPositiveShiftIndex != maximumShiftIndex) {
            return false;
        }
        Integer configuredMinimumMachineCount =
                context.getStructureMinVulcanizingMachineMap().get(structureName);
        if (Objects.isNull(configuredMinimumMachineCount)) {
            throwConfigException(context, structureName, "已纳入规则但未初始化最低机台数");
        }
        int latestShiftMachineCount = countLatestShiftPhysicalMachines(
                structureResults, latestPositiveShiftIndex);
        if (latestShiftMachineCount < configuredMinimumMachineCount) {
            return false;
        }
        context.getStructureMinMachineConfirmedNonRetainedStructureSet().add(structureName);
        log.info("结构最低机台数保留提前确认未命中, factoryCode: {}, scheduleDate: {}, structureName: {}, "
                        + "latestShift: {}, latestShiftMachineCount: {}, minimumMachineCount: {}",
                context.getFactoryCode(), LhScheduleTimeUtil.formatDate(context.getScheduleDate()), structureName,
                latestPositiveShiftIndex, latestShiftMachineCount, configuredMinimumMachineCount);
        return true;
    }

    /**
     * 解析当前排程窗口最大班次索引，不写死3天8班的末班编号。
     *
     * @param context 排程上下文
     * @return 最大班次索引；窗口为空时返回-1
     */
    private int resolveMaximumShiftIndex(LhScheduleContext context) {
        if (Objects.isNull(context) || CollectionUtils.isEmpty(context.getScheduleWindowShifts())) {
            return -1;
        }
        int maximumShiftIndex = -1;
        for (LhShiftConfigVO shift : context.getScheduleWindowShifts()) {
            if (Objects.nonNull(shift) && Objects.nonNull(shift.getShiftIndex())) {
                maximumShiftIndex = Math.max(maximumShiftIndex, shift.getShiftIndex());
            }
        }
        return maximumShiftIndex;
    }

    /**
     * 解析机台最后一个实际生产班次。
     *
     * @param structureResults 结构结果列表
     * @param machineCode 运行态机台编码
     * @return 最后有量班次；不存在返回-1
     */
    private int resolveMachineLastPositiveShiftIndex(List<LhScheduleResult> structureResults,
                                                     String machineCode) {
        int latestShiftIndex = -1;
        for (LhScheduleResult result : structureResults) {
            if (Objects.nonNull(result) && StringUtils.equals(machineCode, result.getLhMachineCode())) {
                latestShiftIndex = Math.max(latestShiftIndex,
                        ShiftFieldUtil.resolveLastPlannedShiftIndex(result));
            }
        }
        return latestShiftIndex;
    }

    /**
     * 选择机台最后实际生产的原结果行作为计划量0占位载体。
     *
     * @param structureResults 结构结果列表
     * @param machineCode 运行态机台编码
     * @return 原结果行；不存在返回null
     */
    private LhScheduleResult resolveMachineCarrierResult(List<LhScheduleResult> structureResults,
                                                         String machineCode) {
        LhScheduleResult carrierResult = null;
        int carrierLastShiftIndex = -1;
        for (LhScheduleResult result : structureResults) {
            if (Objects.isNull(result) || !StringUtils.equals(machineCode, result.getLhMachineCode())) {
                continue;
            }
            int lastShiftIndex = ShiftFieldUtil.resolveLastPlannedShiftIndex(result);
            if (lastShiftIndex > carrierLastShiftIndex) {
                carrierResult = result;
                carrierLastShiftIndex = lastShiftIndex;
            }
        }
        return carrierResult;
    }

    /**
     * 判断机台指定班次是否已存在实际计划量，计划量0不算占用冲突。
     *
     * @param context 排程上下文
     * @param machineCode 运行态机台编码
     * @param shiftIndex 班次索引
     * @return true-存在计划量大于0的结果
     */
    private boolean hasPositiveMachinePlan(LhScheduleContext context,
                                           String machineCode,
                                           int shiftIndex) {
        for (LhScheduleResult result : context.getScheduleResultList()) {
            if (Objects.nonNull(result) && StringUtils.equals(machineCode, result.getLhMachineCode())) {
                Integer planQty = ShiftFieldUtil.getShiftPlanQty(result, shiftIndex);
                if (Objects.nonNull(planQty) && planQty > 0) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 按班次索引读取当前窗口班次。
     *
     * @param context 排程上下文
     * @param shiftIndex 班次索引
     * @return 班次配置；不存在返回null
     */
    private LhShiftConfigVO resolveShift(LhScheduleContext context, int shiftIndex) {
        if (CollectionUtils.isEmpty(context.getScheduleWindowShifts())) {
            return null;
        }
        for (LhShiftConfigVO shift : context.getScheduleWindowShifts()) {
            if (Objects.nonNull(shift) && Objects.equals(shift.getShiftIndex(), shiftIndex)) {
                return shift;
            }
        }
        return null;
    }

    /**
     * 判断结构是否仍有待排SKU。
     *
     * @param context 排程上下文
     * @param structureName 结构名称
     * @return true-仍有待排SKU；false-结构已完成本轮处理
     */
    private boolean isStructurePending(LhScheduleContext context, String structureName) {
        return !CollectionUtils.isEmpty(context.getStructureSkuMap())
                && !CollectionUtils.isEmpty(context.getStructureSkuMap().get(structureName));
    }

    /**
     * 临时保护结构已经实际占用的机台，阻止后续SKU提前释放、换模或换活字块。
     *
     * @param context 排程上下文
     * @param structureName 结构名称
     * @param occupiedMachineCodes 结构已占用机台
     */
    private void protectOccupiedMachines(LhScheduleContext context,
                                          String structureName,
                                          Set<String> occupiedMachineCodes) {
        clearTemporaryProtection(context, structureName);
        for (String machineCode : occupiedMachineCodes) {
            context.getEndingStructureProtectedMachineMap().put(machineCode, structureName);
        }
    }

    /**
     * 清理指定结构的临时保护，最终是否保留由最低机台数判断决定。
     *
     * @param context 排程上下文
     * @param structureName 结构名称
     */
    private void clearTemporaryProtection(LhScheduleContext context, String structureName) {
        if (CollectionUtils.isEmpty(context.getEndingStructureProtectedMachineMap())) {
            return;
        }
        List<String> removableMachineCodes = new ArrayList<String>(8);
        for (Map.Entry<String, String> entry : context.getEndingStructureProtectedMachineMap().entrySet()) {
            if (StringUtils.equals(structureName, entry.getValue())) {
                removableMachineCodes.add(entry.getKey());
            }
        }
        for (String machineCode : removableMachineCodes) {
            context.getEndingStructureProtectedMachineMap().remove(machineCode);
        }
    }

    /**
     * 标记结构本窗口的全部结果。
     *
     * @param structureResults 结构结果列表
     * @param retainedFlag 0-未命中，1-命中
     */
    private void markStructureResults(List<LhScheduleResult> structureResults, String retainedFlag) {
        for (LhScheduleResult result : structureResults) {
            result.setIsStructureMinMachineRetained(retainedFlag);
        }
    }

    /**
     * 抛出结构最低机台数配置异常，不提供静默默认值。
     *
     * @param context 排程上下文
     * @param structureName 结构名称
     * @param reason 异常原因
     */
    private void throwConfigException(LhScheduleContext context, String structureName, String reason) {
        throw new ScheduleException(ScheduleStepEnum.S4_3_ADJUST_AND_GATHER,
                ScheduleErrorCode.DATA_INCOMPLETE, context.getFactoryCode(), context.getBatchNo(),
                "结构[" + structureName + "]最低硫化机台数配置异常：" + reason);
    }
}
