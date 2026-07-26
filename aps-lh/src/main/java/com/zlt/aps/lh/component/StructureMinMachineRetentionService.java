package com.zlt.aps.lh.component;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zlt.aps.lh.api.constant.LhScheduleParamConstant;
import com.zlt.aps.lh.api.domain.dto.MachineScheduleDTO;
import com.zlt.aps.lh.api.domain.dto.SkuScheduleDTO;
import com.zlt.aps.lh.api.domain.entity.LhScheduleResult;
import com.zlt.aps.lh.api.domain.vo.LhShiftConfigVO;
import com.zlt.aps.lh.api.enums.DeleteFlagEnum;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.util.LhScheduleTimeUtil;
import com.zlt.aps.lh.util.LhSingleControlMachineUtil;
import com.zlt.aps.lh.util.ShiftCapacityResolverUtil;
import com.zlt.aps.lh.util.ShiftFieldUtil;
import com.zlt.aps.maindata.mapper.FactoryParamMapper;
import com.zlt.aps.maindata.mapper.MdmMonCycleSchStruConfEntityMapper;
import com.zlt.aps.mp.api.domain.entity.FactoryParam;
import com.zlt.aps.mp.api.domain.entity.MdmMonCycleSchStruConf;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import javax.annotation.Resource;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 结构最低硫化机台数保留服务。
 *
 * <p>本服务在S4.4续作与换活字块全部完成后、S4.5新增排产开始前执行一次结构级判断。
 * 判断不再使用“结构3天内收尾”条件，只读取当前真实排程结果、机台物料关系、业务停机窗口和
 * 结构最低机台配置，避免续作逐台下机顺序改变同一结构的最终判断口径。</p>
 *
 * <p>命中规则后继续复用原结果行补计划量0、顺延结果和机台结束时间，并登记机台占用结束时间。
 * 计划量0只表达资源占用，本服务不修改SKU余量、日计划额度、胎胚库存或完成量账本。</p>
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
    /** 未生产状态，用于全零保机结果通过结果完整性校验 */
    private static final String NOT_PRODUCED_STATUS = "0";
    /** 非收尾结果标识，用于表达机台仍被当前结构占用 */
    private static final String NOT_END_FLAG = "0";
    /** 计划量0占位班次原因 */
    public static final String RETENTION_ANALYSIS = "结构最低机台数保留";
    /** 最低机台数解析失败（配置或数据异常）时的跳过哨兵值 */
    private static final int SKIP_MIN_MACHINE_COUNT = -1;

    @Resource
    private MdmMonCycleSchStruConfEntityMapper cycleStructureConfigMapper;
    @Resource
    private FactoryParamMapper factoryParamMapper;

    /**
     * 初始化全部有效结构的SKU快照及最低硫化机台数。
     *
     * <p>调用方必须在续作、新增分类前执行，避免后续SKU出队导致结构配置丢失。这里只校验结构配置
     * 是否完整一致，不再调用结构收尾判断；配置异常仍沿用现有行为，记录告警并跳过该结构。</p>
     *
     * @param context 排程上下文
     */
    public void initializeStructureMinimumMachineConfigs(LhScheduleContext context) {
        Map<String, List<SkuScheduleDTO>> structureSnapshotMap =
                new LinkedHashMap<String, List<SkuScheduleDTO>>(16);
        Map<String, Integer> minimumMachineMap = new LinkedHashMap<String, Integer>(16);
        if (Objects.isNull(context)) {
            return;
        }
        if (CollectionUtils.isEmpty(context.getStructureSkuMap())) {
            context.setStructureMinMachineSkuSnapshotMap(structureSnapshotMap);
            context.setStructureMinVulcanizingMachineMap(minimumMachineMap);
            return;
        }
        for (Map.Entry<String, List<SkuScheduleDTO>> entry : context.getStructureSkuMap().entrySet()) {
            String structureName = entry.getKey();
            List<SkuScheduleDTO> structureSkuList = entry.getValue();
            if (StringUtils.isEmpty(structureName) || CollectionUtils.isEmpty(structureSkuList)) {
                continue;
            }
            List<SkuScheduleDTO> structureSnapshot = new ArrayList<SkuScheduleDTO>(structureSkuList);
            int minimumMachineCount = resolveMinimumMachineCount(context, structureName, structureSnapshot);
            // 配置或结构数据异常时沿用现有安全跳过行为，不改变本批其他结构的排程。
            if (minimumMachineCount < 0) {
                continue;
            }
            structureSnapshotMap.put(structureName, structureSnapshot);
            minimumMachineMap.put(structureName, minimumMachineCount);
        }
        context.setStructureMinMachineSkuSnapshotMap(structureSnapshotMap);
        context.setStructureMinVulcanizingMachineMap(minimumMachineMap);
        log.info("结构最低机台数规则初始化完成, factoryCode: {}, scheduleDate: {}, structureCount: {}, config: {}",
                context.getFactoryCode(), LhScheduleTimeUtil.formatDate(context.getScheduleDate()),
                structureSnapshotMap.size(), minimumMachineMap);
    }

    /**
     * 收集指定结构在目标班次仍保持在机关系的物理机台编码。
     *
     * <p>统计优先使用实际排程结果：班次计划量大于0直接计入；计划量为0或空时，只有机台仍归属
     * 当前结构，且清洗、精度保养、计划性维修等业务停机与班次重叠，或者处于停产保机/结构保机
     * 占用期内才计入。真实释放边界已早于目标班次或后物料已接管的机台会被排除。</p>
     *
     * <p>返回值统一转换为物理机台编码，因此单控L/R、重复结果行和同机台同班次天然去重。</p>
     *
     * @param context 排程上下文
     * @param structureName 结构名称
     * @param shiftIndex 目标班次索引
     * @return 目标班次仍在机的去重物理机台编码集合
     */
    public Set<String> collectStructureInMachinePhysicalCodes(LhScheduleContext context,
                                                               String structureName,
                                                               int shiftIndex) {
        return collectStructureInMachinePhysicalCodes(
                context, structureName, shiftIndex, null);
    }

    /**
     * 收集指定结构在目标班次仍保持在机关系的物理机台编码。
     *
     * <p>阶段判断重置旧运行态前，会把当前批次已经落在结果行上的结构保机零量占位快照传入。
     * 该快照只参与本次在机统计，不能作为新增阶段的保机运行态继续沿用。</p>
     *
     * @param context 排程上下文
     * @param structureName 结构名称
     * @param shiftIndex 目标班次索引
     * @param existingRetentionMachineShiftKeySet 重置前有效的结构保机零量占位键集合
     * @return 目标班次仍在机的去重物理机台编码集合
     */
    private Set<String> collectStructureInMachinePhysicalCodes(
            LhScheduleContext context,
            String structureName,
            int shiftIndex,
            Set<String> existingRetentionMachineShiftKeySet) {
        Set<String> physicalMachineCodes = new LinkedHashSet<String>(8);
        if (Objects.isNull(context) || StringUtils.isEmpty(structureName) || shiftIndex < 1
                || Objects.isNull(resolveShift(context, shiftIndex))) {
            return physicalMachineCodes;
        }
        for (String machineCode : collectStructureRuntimeMachineCodes(context, structureName)) {
            if (isRuntimeMachineInStructureAtShift(context, structureName, machineCode, shiftIndex,
                    existingRetentionMachineShiftKeySet)) {
                physicalMachineCodes.add(
                        LhSingleControlMachineUtil.resolvePhysicalMachineCode(machineCode));
            }
        }
        return physicalMachineCodes;
    }

    /**
     * 在续作和换活字块排产完成后，按结构统一执行停产保机判断。
     *
     * <p>结构最晚生产班次只允许由计划量大于0的班次确定。清洗、精度、计划性维修及既有保机
     * 零量班次只参与该最晚班次的“是否仍在机”统计，不得把结构最晚生产班次向后推迟。</p>
     *
     * <p>命中后会复用各机台原结构结果行补零，并冻结前物料、前物料结构和最后实际生产时间。
     * 新增排产必须通过这些快照判断同结构放行或不同结构拦截，禁止读取已变化的机台当前物料。</p>
     *
     * @param context 排程上下文
     */
    public void applyRetentionAfterContinuousAndTypeBlock(LhScheduleContext context) {
        if (Objects.isNull(context)) {
            return;
        }
        /*
         * 滚动排程可能带入上一批的结构保机零量结果。重置旧运行态是为了避免其继续限制新增选机，
         * 但这些已落在当前结果行上的零量占位在结构最晚班次前仍代表真实在机，必须先冻结统计快照。
         */
        Map<String, Set<String>> existingRetentionMachineShiftKeyMap =
                captureExistingRetentionMachineShiftKeyMap(context);
        resetPhaseRetentionState(context);
        if (CollectionUtils.isEmpty(context.getStructureMinVulcanizingMachineMap())) {
            return;
        }
        for (Map.Entry<String, Integer> entry
                : context.getStructureMinVulcanizingMachineMap().entrySet()) {
            String structureName = entry.getKey();
            Integer minimumMachineCount = entry.getValue();
            List<LhScheduleResult> structureResults =
                    collectStructureResults(context, structureName);
            int latestPositiveShiftIndex =
                    resolveStructureLastPositiveShiftIndex(structureResults);
            String structureType = resolveStructureType(context, structureName);
            if (latestPositiveShiftIndex < 1) {
                log.info("结构停产保机阶段判断跳过, factoryCode: {}, batchNo: {}, structureName: {}, "
                                + "structureType: {}, minimumMachineCount: {}, reason: 本窗口无实际计划量",
                        context.getFactoryCode(), context.getBatchNo(), structureName,
                        structureType, minimumMachineCount);
                continue;
            }
            Set<String> inMachinePhysicalCodes = collectStructureInMachinePhysicalCodes(
                    context, structureName, latestPositiveShiftIndex,
                    existingRetentionMachineShiftKeyMap.get(structureName));
            boolean retained = Objects.nonNull(minimumMachineCount)
                    && minimumMachineCount > 0
                    && inMachinePhysicalCodes.size() < minimumMachineCount;
            log.info("结构停产保机阶段判断, factoryCode: {}, batchNo: {}, structureName: {}, "
                            + "structureType: {}, minimumMachineCount: {}, latestPositiveShift: {}, "
                            + "inMachineCount: {}, inMachinePhysicalCodes: {}, retained: {}",
                    context.getFactoryCode(), context.getBatchNo(), structureName, structureType,
                    minimumMachineCount, latestPositiveShiftIndex, inMachinePhysicalCodes.size(),
                    inMachinePhysicalCodes, retained);
            if (!retained) {
                continue;
            }
            retainStructureMachines(context, structureName, structureResults,
                    latestPositiveShiftIndex);
        }
    }

    /**
     * 判断目标SKU在指定时间前是否被结构停产保机限制。
     *
     * <p>同结构SKU始终放行；不同结构SKU仅在统一保机结束时间之前拦截。结构比较固定使用阶段判断
     * 时冻结的前物料结构，不使用候选机台后续物料或当前可变物料。</p>
     *
     * @param context 排程上下文
     * @param sku 当前待排SKU
     * @param machineCode 候选机台编码
     * @param attemptTime 当前候选允许尝试的时间上界
     * @return true-不同结构且仍处于保机期；false-同结构放行或保机已结束
     */
    public boolean isDifferentStructureRetentionBlocked(LhScheduleContext context,
                                                        SkuScheduleDTO sku,
                                                        String machineCode,
                                                        Date attemptTime) {
        if (Objects.isNull(context) || Objects.isNull(sku)
                || StringUtils.isEmpty(machineCode)) {
            return false;
        }
        Date retentionEndTime =
                context.getStructureMinMachineRetentionEndTimeMap().get(machineCode);
        String previousStructure =
                context.getStructureMinMachineRetentionPreStructureMap().get(machineCode);
        if (Objects.isNull(retentionEndTime) || StringUtils.isEmpty(previousStructure)
                || StringUtils.equals(previousStructure, sku.getStructureName())) {
            if (Objects.nonNull(retentionEndTime)
                    && StringUtils.equals(previousStructure, sku.getStructureName())) {
                log.info("结构停产保机机台同结构放行, batchNo: {}, machineCode: {}, targetMaterial: {}, "
                                + "targetStructure: {}, previousMaterial: {}, previousStructure: {}, retentionEndTime: {}",
                        context.getBatchNo(), machineCode, sku.getMaterialCode(), sku.getStructureName(),
                        context.getStructureMinMachineRetentionPreMaterialMap().get(machineCode),
                        previousStructure, retentionEndTime);
            }
            return false;
        }
        boolean blocked = Objects.isNull(attemptTime) || !attemptTime.after(retentionEndTime);
        if (blocked) {
            log.info("结构停产保机机台不同结构排除, batchNo: {}, machineCode: {}, targetMaterial: {}, "
                            + "targetStructure: {}, previousMaterial: {}, previousStructure: {}, retentionEndTime: {}",
                    context.getBatchNo(), machineCode, sku.getMaterialCode(), sku.getStructureName(),
                    context.getStructureMinMachineRetentionPreMaterialMap().get(machineCode),
                    previousStructure, retentionEndTime);
        }
        return blocked;
    }

    /**
     * 解析考虑结构停产保机后的机台最早可接管时间。
     *
     * @param context 排程上下文
     * @param sku 当前待排SKU
     * @param machineCode 候选机台编码
     * @param normalEndTime 原机台匹配逻辑解析出的结束时间
     * @return 同结构返回前物料最后实际生产时间；不同结构返回不早于统一保机结束时间的时间
     */
    public Date resolveRetentionAwareOccupationEndTime(LhScheduleContext context,
                                                       SkuScheduleDTO sku,
                                                       String machineCode,
                                                       Date normalEndTime) {
        if (Objects.isNull(context) || Objects.isNull(sku)
                || StringUtils.isEmpty(machineCode)) {
            return normalEndTime;
        }
        Date retentionEndTime =
                context.getStructureMinMachineRetentionEndTimeMap().get(machineCode);
        String previousStructure =
                context.getStructureMinMachineRetentionPreStructureMap().get(machineCode);
        if (Objects.isNull(retentionEndTime) || StringUtils.isEmpty(previousStructure)) {
            return normalEndTime;
        }
        if (StringUtils.equals(previousStructure, sku.getStructureName())) {
            Date actualEndTime =
                    context.getStructureMinMachineRetentionActualEndTimeMap().get(machineCode);
            /*
             * 同结构只允许越过“纯保机补零”形成的统一结束时间，不能越过后物料正量排产、清洗或维修
             * 等原有可用性约束。normalEndTime 晚于统一保机结束时间时，说明机台已被后续真实占用，
             * 必须保留该较晚时间，避免原结构SKU回头与后物料重叠。
             */
            if (Objects.nonNull(normalEndTime) && normalEndTime.after(retentionEndTime)) {
                return normalEndTime;
            }
            return Objects.nonNull(actualEndTime) ? actualEndTime : normalEndTime;
        }
        return Objects.isNull(normalEndTime) || normalEndTime.before(retentionEndTime)
                ? retentionEndTime : normalEndTime;
    }

    /**
     * 清理上一轮阶段判断状态，并把当前结果标识恢复为默认未命中。
     *
     * <p>滚动排程可能带入上一批次结果，阶段判断必须以本次续作和换活字块完成后的结果重新计算，
     * 不能让旧标识、旧释放时间或旧前物料快照污染本次选机。</p>
     *
     * @param context 排程上下文
     */
    private void resetPhaseRetentionState(LhScheduleContext context) {
        context.getStructureMinMachineRetainedStructureSet().clear();
        context.getStructureMinMachineRetentionEndTimeMap().clear();
        context.getStructureMinMachineRetentionPreMaterialMap().clear();
        context.getStructureMinMachineRetentionPreStructureMap().clear();
        context.getStructureMinMachineRetentionActualEndTimeMap().clear();
        for (LhScheduleResult result : context.getScheduleResultList()) {
            if (Objects.nonNull(result)) {
                result.setIsStructureMinMachineRetained(NOT_RETAINED_FLAG);
            }
        }
    }

    /**
     * 冻结阶段判断前已经写入当前结果行的结构保机零量占位。
     *
     * <p>仅识别带“结构最低机台数保留”班次备注的零量或空量班次，避免把历史结果中仅有标识的
     * 正量生产结果误当成占位。结构归属同时兼容结果结构名称和S4.3冻结SKU快照。</p>
     *
     * @param context 排程上下文
     * @return key为结构名称，value为“机台+班次”的有效零量保机占位集合
     */
    private Map<String, Set<String>> captureExistingRetentionMachineShiftKeyMap(
            LhScheduleContext context) {
        Map<String, Set<String>> structureMachineShiftKeyMap =
                new LinkedHashMap<String, Set<String>>(8);
        for (LhScheduleResult result : context.getScheduleResultList()) {
            if (Objects.isNull(result) || StringUtils.isEmpty(result.getLhMachineCode())) {
                continue;
            }
            for (int shiftIndex = 1; shiftIndex <= 8; shiftIndex++) {
                Integer planQty = ShiftFieldUtil.getShiftPlanQty(result, shiftIndex);
                String analysis = ShiftFieldUtil.getShiftAnalysis(result, shiftIndex);
                if ((Objects.nonNull(planQty) && planQty != 0)
                        || !StringUtils.contains(analysis, RETENTION_ANALYSIS)) {
                    continue;
                }
                registerExistingRetentionMachineShiftKey(
                        structureMachineShiftKeyMap, result.getStructureName(),
                        result.getMaterialCode(), result.getLhMachineCode(), shiftIndex, context);
            }
        }
        return structureMachineShiftKeyMap;
    }

    /**
     * 按结果结构名称及冻结SKU快照登记既有保机占位，避免结果结构名称为空时丢失在机关系。
     *
     * @param structureMachineShiftKeyMap 结构保机占位快照
     * @param resultStructureName 结果结构名称
     * @param materialCode 结果物料编码
     * @param machineCode 机台编码
     * @param shiftIndex 班次索引
     * @param context 排程上下文
     */
    private void registerExistingRetentionMachineShiftKey(
            Map<String, Set<String>> structureMachineShiftKeyMap,
            String resultStructureName,
            String materialCode,
            String machineCode,
            int shiftIndex,
            LhScheduleContext context) {
        if (StringUtils.isNotEmpty(resultStructureName)) {
            addExistingRetentionMachineShiftKey(
                    structureMachineShiftKeyMap, resultStructureName, machineCode, shiftIndex);
        }
        for (String structureName : context.getStructureMinMachineSkuSnapshotMap().keySet()) {
            if (isSnapshotStructureMaterial(context, structureName, materialCode)) {
                addExistingRetentionMachineShiftKey(
                        structureMachineShiftKeyMap, structureName, machineCode, shiftIndex);
            }
        }
    }

    /**
     * 登记单个结构、机台、班次的既有保机占位键。
     *
     * @param structureMachineShiftKeyMap 结构保机占位快照
     * @param structureName 结构名称
     * @param machineCode 机台编码
     * @param shiftIndex 班次索引
     */
    private void addExistingRetentionMachineShiftKey(
            Map<String, Set<String>> structureMachineShiftKeyMap,
            String structureName,
            String machineCode,
            int shiftIndex) {
        if (StringUtils.isEmpty(structureName)) {
            return;
        }
        Set<String> machineShiftKeySet = structureMachineShiftKeyMap.get(structureName);
        if (Objects.isNull(machineShiftKeySet)) {
            machineShiftKeySet = new LinkedHashSet<String>(8);
            structureMachineShiftKeyMap.put(structureName, machineShiftKeySet);
        }
        machineShiftKeySet.add(buildMachineShiftKey(machineCode, shiftIndex));
    }

    /**
     * 对已触发结构的全部相关机台执行统一保留。
     *
     * @param context 排程上下文
     * @param structureName 结构名称
     * @param structureResults 结构当前排程结果
     * @param latestPositiveShiftIndex 结构最晚实际生产班次
     */
    private void retainStructureMachines(LhScheduleContext context,
                                         String structureName,
                                         List<LhScheduleResult> structureResults,
                                         int latestPositiveShiftIndex) {
        LhShiftConfigVO retentionLastShift =
                resolveShift(context, latestPositiveShiftIndex);
        if (Objects.isNull(retentionLastShift)
                || Objects.isNull(retentionLastShift.getShiftEndDateTime())) {
            warnConfigSkip(context, structureName,
                    "结构最晚实际生产班次缺少结束时间，shiftIndex=" + latestPositiveShiftIndex);
            return;
        }
        Map<String, LhScheduleResult> carrierResultMap =
                resolveLatestCarrierResultMap(structureResults, latestPositiveShiftIndex);
        for (Map.Entry<String, LhScheduleResult> carrierEntry : carrierResultMap.entrySet()) {
            String machineCode = carrierEntry.getKey();
            LhScheduleResult carrierResult = carrierEntry.getValue();
            int lastPositiveShiftIndex =
                    resolveLastPositiveShiftIndex(carrierResult, latestPositiveShiftIndex);
            if (lastPositiveShiftIndex < 1
                    || hasPositiveOtherStructurePlanBetween(context, structureName, machineCode,
                    lastPositiveShiftIndex + 1, latestPositiveShiftIndex)) {
                continue;
            }
            int placeholderStartShiftIndex = lastPositiveShiftIndex + 1;
            if (placeholderStartShiftIndex <= latestPositiveShiftIndex) {
                fillMachinePlaceholderShifts(context, structureName, carrierResult,
                        placeholderStartShiftIndex, latestPositiveShiftIndex);
            }
            Date actualEndTime = resolveActualProductionEndTime(
                    context, carrierResult, lastPositiveShiftIndex);
            registerRetainedMachine(context, structureName, carrierResult,
                    actualEndTime, retentionLastShift.getShiftEndDateTime(),
                    placeholderStartShiftIndex, latestPositiveShiftIndex);
        }
        markStructureResults(structureResults, RETAINED_FLAG);
        context.getStructureMinMachineRetainedStructureSet().add(structureName);
    }

    /**
     * 登记单台结构保机机台，并撤销续作阶段提前写入的释放状态。
     *
     * @param context 排程上下文
     * @param structureName 结构名称
     * @param carrierResult 前物料结果
     * @param actualEndTime 前物料最后实际生产结束时间
     * @param retentionEndTime 结构统一释放时间
     * @param placeholderStartShiftIndex 补零起始班次
     * @param retentionLastShiftIndex 补零结束班次
     */
    private void registerRetainedMachine(LhScheduleContext context,
                                         String structureName,
                                         LhScheduleResult carrierResult,
                                         Date actualEndTime,
                                         Date retentionEndTime,
                                         int placeholderStartShiftIndex,
                                         int retentionLastShiftIndex) {
        String machineCode = carrierResult.getLhMachineCode();
        context.getReleasedContinuousMachineCodeSet().remove(machineCode);
        context.getTypeBlockReleasedContinuousMachineCodeSet().remove(machineCode);
        context.getFirstDayNoPlanReleasedContinuousMachineCodeSet().remove(machineCode);
        context.getContinuousReducedMachineReleaseBoundaryShiftIndexMap().remove(machineCode);
        context.getStructureMinMachineRetentionPreMaterialMap()
                .put(machineCode, carrierResult.getMaterialCode());
        context.getStructureMinMachineRetentionPreStructureMap()
                .put(machineCode, structureName);
        if (Objects.nonNull(actualEndTime)) {
            context.getStructureMinMachineRetentionActualEndTimeMap()
                    .put(machineCode, actualEndTime);
        }
        delayMachineRelease(context, machineCode, retentionEndTime);
        MachineScheduleDTO retainedMachine = context.getMachineScheduleMap().get(machineCode);
        if (Objects.nonNull(retainedMachine)) {
            // 阶段判断命中后恢复前物料占用，新增排产只有通过同结构放行或保机到期才能接管。
            retainedMachine.setCurrentMaterialCode(carrierResult.getMaterialCode());
            retainedMachine.setCurrentMaterialDesc(carrierResult.getMaterialDesc());
        }
        delayResultRelease(carrierResult, retentionEndTime);
        String placeholderShiftRange = placeholderStartShiftIndex <= retentionLastShiftIndex
                ? placeholderStartShiftIndex + "-" + retentionLastShiftIndex : "无新增补零班次";
        log.info("结构停产保机补零占位, factoryCode: {}, batchNo: {}, structureName: {}, "
                        + "materialCode: {}, machineCode: {}, placeholderShiftRange: {}, "
                        + "actualEndTime: {}, retentionEndTime: {}",
                context.getFactoryCode(), context.getBatchNo(), structureName,
                carrierResult.getMaterialCode(), machineCode, placeholderShiftRange,
                actualEndTime, retentionEndTime);
    }

    /**
     * 按机台选取结构最晚实际生产结果，作为零量占位承载行和前物料来源。
     *
     * @param structureResults 结构结果
     * @param latestPositiveShiftIndex 结构最晚实际生产班次
     * @return key为运行态机台编码，value为该机台最后实际生产结果
     */
    private Map<String, LhScheduleResult> resolveLatestCarrierResultMap(
            List<LhScheduleResult> structureResults,
            int latestPositiveShiftIndex) {
        Map<String, LhScheduleResult> carrierResultMap =
                new LinkedHashMap<String, LhScheduleResult>(8);
        Map<String, Integer> carrierLastShiftMap =
                new LinkedHashMap<String, Integer>(8);
        for (LhScheduleResult result : structureResults) {
            if (Objects.isNull(result) || StringUtils.isEmpty(result.getLhMachineCode())) {
                continue;
            }
            int resultLastShiftIndex =
                    resolveLastPositiveShiftIndex(result, latestPositiveShiftIndex);
            Integer currentLastShiftIndex =
                    carrierLastShiftMap.get(result.getLhMachineCode());
            if (resultLastShiftIndex > 0 && (Objects.isNull(currentLastShiftIndex)
                    || resultLastShiftIndex >= currentLastShiftIndex)) {
                carrierResultMap.put(result.getLhMachineCode(), result);
                carrierLastShiftMap.put(result.getLhMachineCode(), resultLastShiftIndex);
            }
        }
        return carrierResultMap;
    }

    /**
     * 解析结构最晚实际生产班次，只统计计划量大于0的班次。
     *
     * @param structureResults 结构结果
     * @return 最晚正量班次；不存在返回-1
     */
    private int resolveStructureLastPositiveShiftIndex(
            List<LhScheduleResult> structureResults) {
        int latestShiftIndex = -1;
        for (LhScheduleResult result : structureResults) {
            latestShiftIndex = Math.max(latestShiftIndex,
                    resolveLastPositiveShiftIndex(result, Integer.MAX_VALUE));
        }
        return latestShiftIndex;
    }

    /**
     * 解析结果在指定上界内最后一个正量班次。
     *
     * @param result 排程结果
     * @param maximumShiftIndex 最大班次索引
     * @return 最后正量班次；不存在返回-1
     */
    private int resolveLastPositiveShiftIndex(LhScheduleResult result,
                                              int maximumShiftIndex) {
        int latestShiftIndex = -1;
        if (Objects.isNull(result)) {
            return latestShiftIndex;
        }
        int upperBound = Math.min(8, maximumShiftIndex);
        for (int shiftIndex = 1; shiftIndex <= upperBound; shiftIndex++) {
            Integer planQty = ShiftFieldUtil.getShiftPlanQty(result, shiftIndex);
            if (Objects.nonNull(planQty) && planQty > 0) {
                latestShiftIndex = shiftIndex;
            }
        }
        return latestShiftIndex;
    }

    /**
     * 解析前物料最后实际生产结束时间。
     *
     * @param context 排程上下文
     * @param result 前物料结果
     * @param lastPositiveShiftIndex 最后正量班次
     * @return 实际生产结束时间
     */
    private Date resolveActualProductionEndTime(LhScheduleContext context,
                                                LhScheduleResult result,
                                                int lastPositiveShiftIndex) {
        Date resultShiftEndTime =
                ShiftFieldUtil.getShiftEndTime(result, lastPositiveShiftIndex);
        if (Objects.nonNull(resultShiftEndTime)) {
            return resultShiftEndTime;
        }
        LhShiftConfigVO shift = resolveShift(context, lastPositiveShiftIndex);
        return Objects.isNull(shift) ? null : shift.getShiftEndDateTime();
    }

    /**
     * 解析结果首个实际生产班次的开始时间，用于确认不同结构是否已在统一释放后正式接管。
     *
     * @param context 排程上下文
     * @param result 排程结果
     * @param firstPositiveShiftIndex 首个正量班次
     * @return 首个实际生产开始时间；无法解析返回null
     */
    private Date resolveActualProductionStartTime(LhScheduleContext context,
                                                  LhScheduleResult result,
                                                  int firstPositiveShiftIndex) {
        if (firstPositiveShiftIndex < 1) {
            return null;
        }
        Date resultShiftStartTime =
                ShiftFieldUtil.getShiftStartTime(result, firstPositiveShiftIndex);
        if (Objects.nonNull(resultShiftStartTime)) {
            return resultShiftStartTime;
        }
        LhShiftConfigVO shift = resolveShift(context, firstPositiveShiftIndex);
        return Objects.isNull(shift) ? null : shift.getShiftStartDateTime();
    }

    /**
     * 判断机台在补零区间内是否已被其他结构正量结果接管。
     *
     * @param context 排程上下文
     * @param structureName 当前结构
     * @param machineCode 机台编码
     * @param startShiftIndex 起始班次
     * @param endShiftIndex 结束班次
     * @return true-存在其他结构正量接管；false-不存在
     */
    private boolean hasPositiveOtherStructurePlanBetween(LhScheduleContext context,
                                                         String structureName,
                                                         String machineCode,
                                                         int startShiftIndex,
                                                         int endShiftIndex) {
        for (int shiftIndex = Math.max(1, startShiftIndex);
             shiftIndex <= endShiftIndex; shiftIndex++) {
            if (hasPositiveOtherStructurePlan(
                    context, structureName, machineCode, shiftIndex)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 获取结构类型，用于阶段判断日志。
     *
     * @param context 排程上下文
     * @param structureName 结构名称
     * @return 月计划结构类型
     */
    private String resolveStructureType(LhScheduleContext context,
                                        String structureName) {
        List<SkuScheduleDTO> skuList =
                context.getStructureMinMachineSkuSnapshotMap().get(structureName);
        return CollectionUtils.isEmpty(skuList) || Objects.isNull(skuList.get(0))
                ? null : skuList.get(0).getStructureType();
    }

    /**
     * 在新增阶段结束后幂等校正已命中结构的结果标识和被保留机台结束时间。
     *
     * <p>本方法不重新统计机台数，也不产生新的保机决策；它只处理首次命中后同结构又新增结果的
     * 标识一致性，并再次确保普通后置状态同步没有把被保留机台的结果或运行态结束时间提前。</p>
     *
     * @param context 排程上下文
     */
    public void synchronizeRetainedState(LhScheduleContext context) {
        if (Objects.isNull(context)
                || CollectionUtils.isEmpty(context.getStructureMinMachineRetainedStructureSet())) {
            return;
        }
        // 不同结构在统一释放后完成正式接管时，保留历史零量结果，但解除机台运行态保机限制。
        releaseRetentionRuntimeStateAfterDifferentStructureHandoff(context);
        // 新增、历史反选或特殊材料可能已由同结构SKU接管保机机台，先清理旧占位并转移剩余保机区间。
        synchronizeSameStructureHandoffs(context);
        for (LhScheduleResult result : context.getScheduleResultList()) {
            if (Objects.isNull(result) || !context.getStructureMinMachineRetainedStructureSet()
                    .contains(result.getStructureName())) {
                continue;
            }
            result.setIsStructureMinMachineRetained(RETAINED_FLAG);
            Date retentionEndTime = context.getStructureMinMachineRetentionEndTimeMap()
                    .get(result.getLhMachineCode());
            String retainedMaterial = context.getStructureMinMachineRetentionPreMaterialMap()
                    .get(result.getLhMachineCode());
            if (Objects.nonNull(retentionEndTime)
                    && StringUtils.equals(retainedMaterial, result.getMaterialCode())) {
                delayResultRelease(result, retentionEndTime);
                delayMachineRelease(context, result.getLhMachineCode(), retentionEndTime);
            }
        }
    }

    /**
     * 同步同结构SKU对保机机台的接管结果。
     *
     * <p>旧结果只移除接管班次及之后的结构保机原因；清洗、精度、维修等其他原因保持不变。
     * 新结果若在结构统一释放时间前再次结束，则从其最后正量班次下一班继续承载零量占位。</p>
     *
     * @param context 排程上下文
     */
    private void synchronizeSameStructureHandoffs(LhScheduleContext context) {
        List<String> retainedMachineCodes = new ArrayList<String>(
                context.getStructureMinMachineRetentionPreMaterialMap().keySet());
        for (String machineCode : retainedMachineCodes) {
            String previousMaterial = context.getStructureMinMachineRetentionPreMaterialMap()
                    .get(machineCode);
            String previousStructure = context.getStructureMinMachineRetentionPreStructureMap()
                    .get(machineCode);
            int previousOwnerLastPositiveShiftIndex =
                    resolveRetentionOwnerLastPositiveShiftIndex(
                            context, machineCode, previousMaterial);
            LhScheduleResult handoffResult = resolveSameStructureHandoffResult(
                    context, machineCode, previousMaterial, previousStructure,
                    previousOwnerLastPositiveShiftIndex);
            if (Objects.isNull(handoffResult)) {
                continue;
            }
            int handoffFirstShiftIndex =
                    resolveFirstPositiveShiftIndex(handoffResult);
            int handoffLastShiftIndex =
                    resolveLastPositiveShiftIndex(handoffResult, Integer.MAX_VALUE);
            Date retentionEndTime = context.getStructureMinMachineRetentionEndTimeMap()
                    .get(machineCode);
            int retentionLastShiftIndex =
                    resolveShiftIndexCoveredByEndTime(context, retentionEndTime);
            clearPreviousRetentionPlaceholders(context, machineCode, previousMaterial,
                    handoffFirstShiftIndex, retentionLastShiftIndex);
            if (handoffLastShiftIndex > 0
                    && handoffLastShiftIndex < retentionLastShiftIndex) {
                fillMachinePlaceholderShifts(context, previousStructure, handoffResult,
                        handoffLastShiftIndex + 1, retentionLastShiftIndex);
            }
            Date actualEndTime = resolveActualProductionEndTime(
                    context, handoffResult, handoffLastShiftIndex);
            context.getStructureMinMachineRetentionPreMaterialMap()
                    .put(machineCode, handoffResult.getMaterialCode());
            context.getStructureMinMachineRetentionActualEndTimeMap()
                    .put(machineCode, actualEndTime);
            log.info("结构停产保机同结构接管完成, batchNo: {}, machineCode: {}, previousMaterial: {}, "
                            + "targetMaterial: {}, structureName: {}, handoffFirstShift: {}, "
                            + "targetLastPositiveShift: {}, retentionLastShift: {}",
                    context.getBatchNo(), machineCode, previousMaterial,
                    handoffResult.getMaterialCode(), previousStructure, handoffFirstShiftIndex,
                    handoffLastShiftIndex, retentionLastShiftIndex);
        }
    }

    /**
     * 查找保机前物料之后在同机台产生正量的同结构结果。
     *
     * @param context 排程上下文
     * @param machineCode 机台编码
     * @param previousMaterial 保机前物料
     * @param previousStructure 保机前物料结构
     * @return 同结构接管结果；不存在返回null
     */
    private LhScheduleResult resolveSameStructureHandoffResult(
            LhScheduleContext context,
            String machineCode,
            String previousMaterial,
            String previousStructure,
            int previousOwnerLastPositiveShiftIndex) {
        LhScheduleResult handoffResult = null;
        int earliestShiftIndex = Integer.MAX_VALUE;
        for (LhScheduleResult result : context.getScheduleResultList()) {
            if (Objects.isNull(result)
                    || !StringUtils.equals(machineCode, result.getLhMachineCode())
                    || StringUtils.equals(previousMaterial, result.getMaterialCode())
                    || (!StringUtils.equals(previousStructure, result.getStructureName())
                    && !isSnapshotStructureMaterial(
                    context, previousStructure, result.getMaterialCode()))) {
                continue;
            }
            int firstPositiveShiftIndex = resolveFirstPositiveShiftIndex(result);
            if (firstPositiveShiftIndex > previousOwnerLastPositiveShiftIndex
                    && firstPositiveShiftIndex < earliestShiftIndex) {
                handoffResult = result;
                earliestShiftIndex = firstPositiveShiftIndex;
            }
        }
        return handoffResult;
    }

    /**
     * 解析当前保机前物料在机台上的最后实际生产班次，用于限制接管只能向时间轴后方推进。
     *
     * @param context 排程上下文
     * @param machineCode 保机机台编码
     * @param materialCode 当前保机前物料
     * @return 当前前物料最后正量班次；不存在返回-1
     */
    private int resolveRetentionOwnerLastPositiveShiftIndex(LhScheduleContext context,
                                                             String machineCode,
                                                             String materialCode) {
        int lastPositiveShiftIndex = -1;
        for (LhScheduleResult result : context.getScheduleResultList()) {
            if (Objects.nonNull(result)
                    && StringUtils.equals(machineCode, result.getLhMachineCode())
                    && StringUtils.equals(materialCode, result.getMaterialCode())) {
                lastPositiveShiftIndex = Math.max(lastPositiveShiftIndex,
                        resolveLastPositiveShiftIndex(result, Integer.MAX_VALUE));
            }
        }
        return lastPositiveShiftIndex;
    }

    /**
     * 不同结构在统一释放时间后正式接管机台时，清除结构保机运行态映射。
     *
     * <p>历史前物料结果和标识仍保留，用于审计已发生的零量占位；仅解除后续选机对该机台的保机
     * 限制，避免原结构SKU错误使用过期前物料结束时间覆盖后物料真实占用时间。</p>
     *
     * @param context 排程上下文
     */
    private void releaseRetentionRuntimeStateAfterDifferentStructureHandoff(
            LhScheduleContext context) {
        List<String> retainedMachineCodes = new ArrayList<String>(
                context.getStructureMinMachineRetentionPreStructureMap().keySet());
        for (String machineCode : retainedMachineCodes) {
            String previousStructure = context.getStructureMinMachineRetentionPreStructureMap()
                    .get(machineCode);
            Date retentionEndTime = context.getStructureMinMachineRetentionEndTimeMap()
                    .get(machineCode);
            LhScheduleResult differentStructureHandoffResult =
                    resolveDifferentStructureHandoffResult(
                            context, machineCode, previousStructure, retentionEndTime);
            if (Objects.isNull(differentStructureHandoffResult)) {
                continue;
            }
            String previousMaterial = context.getStructureMinMachineRetentionPreMaterialMap()
                    .get(machineCode);
            context.getStructureMinMachineRetentionEndTimeMap().remove(machineCode);
            context.getStructureMinMachineRetentionPreMaterialMap().remove(machineCode);
            context.getStructureMinMachineRetentionPreStructureMap().remove(machineCode);
            context.getStructureMinMachineRetentionActualEndTimeMap().remove(machineCode);
            log.info("结构停产保机不同结构到期接管，解除运行态限制, batchNo: {}, machineCode: {}, "
                            + "previousMaterial: {}, previousStructure: {}, targetMaterial: {}, "
                            + "targetStructure: {}, retentionEndTime: {}",
                    context.getBatchNo(), machineCode, previousMaterial, previousStructure,
                    differentStructureHandoffResult.getMaterialCode(),
                    differentStructureHandoffResult.getStructureName(), retentionEndTime);
        }
    }

    /**
     * 查找统一释放时间后由不同结构产生的正式正量接管结果。
     *
     * @param context 排程上下文
     * @param machineCode 保机机台编码
     * @param previousStructure 保机前物料结构
     * @param retentionEndTime 结构统一释放时间
     * @return 不同结构正式接管结果；不存在返回null
     */
    private LhScheduleResult resolveDifferentStructureHandoffResult(
            LhScheduleContext context,
            String machineCode,
            String previousStructure,
            Date retentionEndTime) {
        if (StringUtils.isEmpty(previousStructure) || Objects.isNull(retentionEndTime)) {
            return null;
        }
        LhScheduleResult handoffResult = null;
        Date earliestStartTime = null;
        for (LhScheduleResult result : context.getScheduleResultList()) {
            if (Objects.isNull(result)
                    || !StringUtils.equals(machineCode, result.getLhMachineCode())
                    || StringUtils.equals(previousStructure, result.getStructureName())
                    || isSnapshotStructureMaterial(
                    context, previousStructure, result.getMaterialCode())) {
                continue;
            }
            int firstPositiveShiftIndex = resolveFirstPositiveShiftIndex(result);
            Date firstPositiveStartTime = resolveActualProductionStartTime(
                    context, result, firstPositiveShiftIndex);
            if (Objects.isNull(firstPositiveStartTime)
                    || firstPositiveStartTime.before(retentionEndTime)
                    || (Objects.nonNull(earliestStartTime)
                    && !firstPositiveStartTime.before(earliestStartTime))) {
                continue;
            }
            handoffResult = result;
            earliestStartTime = firstPositiveStartTime;
        }
        return handoffResult;
    }

    /**
     * 清理旧结果中已被同结构后物料接管的纯保机占位。
     *
     * @param context 排程上下文
     * @param machineCode 机台编码
     * @param previousMaterial 保机前物料
     * @param startShiftIndex 接管起始班次
     * @param endShiftIndex 保机结束班次
     */
    private void clearPreviousRetentionPlaceholders(LhScheduleContext context,
                                                    String machineCode,
                                                    String previousMaterial,
                                                    int startShiftIndex,
                                                    int endShiftIndex) {
        for (LhScheduleResult result : context.getScheduleResultList()) {
            if (Objects.isNull(result)
                    || !StringUtils.equals(machineCode, result.getLhMachineCode())
                    || !StringUtils.equals(previousMaterial, result.getMaterialCode())) {
                continue;
            }
            for (int shiftIndex = startShiftIndex;
                 shiftIndex <= endShiftIndex; shiftIndex++) {
                Integer planQty = ShiftFieldUtil.getShiftPlanQty(result, shiftIndex);
                if (Objects.isNull(planQty) || planQty != 0) {
                    continue;
                }
                ShiftFieldUtil.removeShiftAnalysis(
                        result, shiftIndex, RETENTION_ANALYSIS);
                if (StringUtils.isEmpty(
                        ShiftFieldUtil.getShiftAnalysis(result, shiftIndex))) {
                    ShiftFieldUtil.setShiftPlanQty(
                            result, shiftIndex, null, null, null);
                }
            }
            Date actualEndTime = context.getStructureMinMachineRetentionActualEndTimeMap()
                    .get(machineCode);
            if (Objects.nonNull(actualEndTime)) {
                result.setSpecEndTime(actualEndTime);
                result.setTdaySpecEndTime(actualEndTime);
            }
        }
    }

    /**
     * 解析结果首个正量班次。
     *
     * @param result 排程结果
     * @return 首个正量班次；不存在返回-1
     */
    private int resolveFirstPositiveShiftIndex(LhScheduleResult result) {
        if (Objects.isNull(result)) {
            return -1;
        }
        for (int shiftIndex = 1; shiftIndex <= 8; shiftIndex++) {
            Integer planQty = ShiftFieldUtil.getShiftPlanQty(result, shiftIndex);
            if (Objects.nonNull(planQty) && planQty > 0) {
                return shiftIndex;
            }
        }
        return -1;
    }

    /**
     * 解析结构最低硫化机台数。
     *
     * @param context 排程上下文
     * @param structureName 结构名称
     * @param structureSkuList 结构SKU快照
     * @return 配置的最低硫化机台数；配置或数据异常时返回跳过哨兵值
     */
    public int resolveMinimumMachineCount(LhScheduleContext context,
                                          String structureName,
                                          List<SkuScheduleDTO> structureSkuList) {
        if (CollectionUtils.isEmpty(structureSkuList)) {
            warnConfigSkip(context, structureName, "结构SKU为空，无法解析最低机台数");
            return SKIP_MIN_MACHINE_COUNT;
        }
        SkuScheduleDTO firstSku = structureSkuList.get(0);
        if (!isConsistentStructureConfig(context, structureName, structureSkuList, firstSku)) {
            return SKIP_MIN_MACHINE_COUNT;
        }
        if (StringUtils.equals(CYCLE_STRUCTURE_TYPE, firstSku.getStructureType())) {
            return resolveCycleStructureMinimum(context, structureName, firstSku);
        }
        if (StringUtils.equals(REGULAR_STRUCTURE_TYPE, firstSku.getStructureType())) {
            return resolveRegularStructureMinimum(context, structureName);
        }
        warnConfigSkip(context, structureName,
                "月计划STRUCTURE_TYPE为空或不支持，实际值=" + firstSku.getStructureType());
        return SKIP_MIN_MACHINE_COUNT;
    }

    /**
     * 在被保留机台的原结果行补齐下机班次至结构最后占用班次的零量占位。
     *
     * @param context 排程上下文
     * @param structureName 结构名称
     * @param carrierResult 原结果行
     * @param offlineShiftIndex 下机班次
     * @param retentionLastShiftIndex 保机结束班次
     */
    private void fillMachinePlaceholderShifts(LhScheduleContext context,
                                              String structureName,
                                              LhScheduleResult carrierResult,
                                              int offlineShiftIndex,
                                              int retentionLastShiftIndex) {
        for (int shiftIndex = offlineShiftIndex;
             shiftIndex <= retentionLastShiftIndex; shiftIndex++) {
            if (hasPositiveOtherMaterialPlan(context, carrierResult, shiftIndex)) {
                log.warn("结构[{}]保留机台[{}]班次[{}]已被后物料接管，跳过零量占位：factoryCode={}, batchNo={}",
                        structureName, carrierResult.getLhMachineCode(), shiftIndex,
                        context.getFactoryCode(), context.getBatchNo());
                continue;
            }
            LhShiftConfigVO shift = resolveShift(context, shiftIndex);
            if (Objects.isNull(shift) || Objects.isNull(shift.getShiftStartDateTime())
                    || Objects.isNull(shift.getShiftEndDateTime())) {
                warnConfigSkip(context, structureName, "占位班次缺少起止时间，shiftIndex=" + shiftIndex);
                continue;
            }
            Integer existingPlanQty = ShiftFieldUtil.getShiftPlanQty(carrierResult, shiftIndex);
            if (Objects.isNull(existingPlanQty) || existingPlanQty == 0) {
                ShiftFieldUtil.setShiftPlanQty(carrierResult, shiftIndex, 0,
                        shift.getShiftStartDateTime(), shift.getShiftEndDateTime());
            }
            ShiftFieldUtil.appendShiftAnalysis(carrierResult, shiftIndex, RETENTION_ANALYSIS);
        }
    }

    /**
     * 顺延机台可用时间并保持当前占用状态。
     *
     * @param context 排程上下文
     * @param machineCode 运行态机台编码
     * @param retentionEndTime 保机结束时间
     */
    private void delayMachineRelease(LhScheduleContext context,
                                     String machineCode,
                                     Date retentionEndTime) {
        Date existingRetentionEndTime = context.getStructureMinMachineRetentionEndTimeMap().get(machineCode);
        if (Objects.isNull(existingRetentionEndTime) || existingRetentionEndTime.before(retentionEndTime)) {
            context.getStructureMinMachineRetentionEndTimeMap().put(machineCode, retentionEndTime);
        }
        MachineScheduleDTO machine = context.getMachineScheduleMap().get(machineCode);
        if (Objects.nonNull(machine) && (Objects.isNull(machine.getEstimatedEndTime())
                || machine.getEstimatedEndTime().before(retentionEndTime))) {
            machine.setEstimatedEndTime(retentionEndTime);
        }
        if (Objects.nonNull(machine)) {
            machine.setEnding(true);
        }
    }

    /**
     * 顺延结果收尾时间；全零结果继续作为合法的未生产占位结果保留。
     *
     * @param result 被保留机台结果
     * @param retentionEndTime 保机结束时间
     */
    private void delayResultRelease(LhScheduleResult result, Date retentionEndTime) {
        if (Objects.isNull(result.getSpecEndTime()) || result.getSpecEndTime().before(retentionEndTime)) {
            result.setSpecEndTime(retentionEndTime);
        }
        if (Objects.isNull(result.getTdaySpecEndTime()) || result.getTdaySpecEndTime().before(retentionEndTime)) {
            result.setTdaySpecEndTime(retentionEndTime);
        }
        if (ShiftFieldUtil.resolveScheduledQty(result) <= 0) {
            result.setDailyPlanQty(0);
            result.setProductionStatus(NOT_PRODUCED_STATUS);
            result.setIsEnd(NOT_END_FLAG);
        }
        result.setIsStructureMinMachineRetained(RETAINED_FLAG);
    }

    /**
     * 判断指定运行态机台在目标班次是否仍属于结构在机机台。
     *
     * @param context 排程上下文
     * @param structureName 结构名称
     * @param machineCode 运行态机台编码
     * @param shiftIndex 班次索引
     * @return true-仍在机；false-已释放或被后物料接管
     */
    private boolean isRuntimeMachineInStructureAtShift(LhScheduleContext context,
                                                        String structureName,
                                                        String machineCode,
                                                        int shiftIndex,
                                                        Set<String> existingRetentionMachineShiftKeySet) {
        if (hasPositiveStructurePlan(context, structureName, machineCode, shiftIndex)) {
            return true;
        }
        if (hasPositiveOtherStructurePlan(context, structureName, machineCode, shiftIndex)
                || !isStructureRelationshipIntact(context, structureName, machineCode, shiftIndex)) {
            return false;
        }
        LhShiftConfigVO shift = resolveShift(context, shiftIndex);
        if (isStructureRetentionCoveringShift(context, machineCode, shift)) {
            return true;
        }
        Integer releaseBoundary = context.getContinuousReducedMachineReleaseBoundaryShiftIndex(machineCode);
        if (Objects.nonNull(releaseBoundary) && releaseBoundary < shiftIndex) {
            return false;
        }
        if (isExistingRetentionPlaceholderAtShift(
                existingRetentionMachineShiftKeySet, machineCode, shiftIndex)) {
            return true;
        }
        if (isContinuousStopHoldAtShift(context, machineCode, shift)) {
            return true;
        }
        return hasBusinessDowntimeAtShift(context, machineCode, shift);
    }

    /**
     * 判断阶段重置前已存在的结构保机零量占位是否覆盖指定机台班次。
     *
     * @param existingRetentionMachineShiftKeySet 既有保机占位键集合
     * @param machineCode 机台编码
     * @param shiftIndex 班次索引
     * @return true-当前班次存在有效既有保机占位；false-不存在
     */
    private boolean isExistingRetentionPlaceholderAtShift(
            Set<String> existingRetentionMachineShiftKeySet,
            String machineCode,
            int shiftIndex) {
        return !CollectionUtils.isEmpty(existingRetentionMachineShiftKeySet)
                && existingRetentionMachineShiftKeySet.contains(
                buildMachineShiftKey(machineCode, shiftIndex));
    }

    /**
     * 判断零量/空量班次下机台与结构的物料关系是否仍未解除。
     *
     * @param context 排程上下文
     * @param structureName 结构名称
     * @param machineCode 机台编码
     * @param shiftIndex 班次索引
     * @return true-仍属于当前结构；false-已无结构占用关系
     */
    private boolean isStructureRelationshipIntact(LhScheduleContext context,
                                                   String structureName,
                                                   String machineCode,
                                                   int shiftIndex) {
        MachineScheduleDTO machine = context.getMachineScheduleMap().get(machineCode);
        if (Objects.nonNull(machine) && StringUtils.isNotEmpty(machine.getCurrentMaterialCode())) {
            return isStructureMaterial(context, structureName, machine.getCurrentMaterialCode());
        }
        LhScheduleResult latestOwner = resolveLatestOwnerResult(context, machineCode, shiftIndex);
        if (Objects.nonNull(latestOwner)) {
            return StringUtils.equals(structureName, latestOwner.getStructureName());
        }
        for (LhScheduleResult result : collectStructureResults(context, structureName)) {
            if (StringUtils.equals(machineCode, result.getLhMachineCode())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 解析目标班次前最后一个实际生产结果，作为机台物料关系来源。
     *
     * @param context 排程上下文
     * @param machineCode 机台编码
     * @param shiftIndex 目标班次
     * @return 最后生产结果；不存在返回null
     */
    private LhScheduleResult resolveLatestOwnerResult(LhScheduleContext context,
                                                      String machineCode,
                                                      int shiftIndex) {
        LhScheduleResult latestOwner = null;
        int latestOwnerShiftIndex = -1;
        for (LhScheduleResult result : context.getScheduleResultList()) {
            if (Objects.isNull(result) || !StringUtils.equals(machineCode, result.getLhMachineCode())) {
                continue;
            }
            for (int currentShiftIndex = 1; currentShiftIndex <= shiftIndex; currentShiftIndex++) {
                Integer planQty = ShiftFieldUtil.getShiftPlanQty(result, currentShiftIndex);
                if (Objects.nonNull(planQty) && planQty > 0 && currentShiftIndex >= latestOwnerShiftIndex) {
                    latestOwner = result;
                    latestOwnerShiftIndex = currentShiftIndex;
                }
            }
        }
        return latestOwner;
    }

    /**
     * 判断业务停机是否与目标班次重叠。
     *
     * @param context 排程上下文
     * @param machineCode 机台编码
     * @param shift 班次配置
     * @return true-清洗、精度保养或设备停机与班次重叠；false-无重叠
     */
    private boolean hasBusinessDowntimeAtShift(LhScheduleContext context,
                                               String machineCode,
                                               LhShiftConfigVO shift) {
        if (Objects.isNull(shift) || Objects.isNull(shift.getShiftStartDateTime())
                || Objects.isNull(shift.getShiftEndDateTime())) {
            return false;
        }
        MachineScheduleDTO machine = context.getMachineScheduleMap().get(machineCode);
        List<com.zlt.aps.lh.api.domain.dto.MachineCleaningWindowDTO> cleaningWindowList =
                Objects.isNull(machine) ? null : machine.getCleaningWindowList();
        List<com.zlt.aps.lh.api.domain.dto.MachineMaintenanceWindowDTO> maintenanceWindowList =
                Objects.isNull(machine) ? null : machine.getMaintenanceWindowList();
        long overlapSeconds = ShiftCapacityResolverUtil.resolveDowntimeOverlapSeconds(
                context.getDevicePlanShutList(), cleaningWindowList, maintenanceWindowList, machineCode,
                shift.getShiftStartDateTime(), shift.getShiftEndDateTime());
        if (overlapSeconds > 0) {
            return true;
        }
        String physicalMachineCode = LhSingleControlMachineUtil.resolvePhysicalMachineCode(machineCode);
        return !StringUtils.equals(machineCode, physicalMachineCode)
                && ShiftCapacityResolverUtil.resolveDowntimeOverlapSeconds(
                context.getDevicePlanShutList(), cleaningWindowList, maintenanceWindowList, physicalMachineCode,
                shift.getShiftStartDateTime(), shift.getShiftEndDateTime()) > 0;
    }

    /**
     * 判断停产保机状态是否覆盖目标班次。
     *
     * @param context 排程上下文
     * @param machineCode 机台编码
     * @param shift 班次配置
     * @return true-目标业务日登记停产保机；false-未登记
     */
    private boolean isContinuousStopHoldAtShift(LhScheduleContext context,
                                                String machineCode,
                                                LhShiftConfigVO shift) {
        if (Objects.isNull(shift) || Objects.isNull(shift.getShiftStartDateTime())) {
            return context.isContinuousStopHoldMachine(machineCode);
        }
        LocalDate productionDate = shift.getShiftStartDateTime().toInstant()
                .atZone(ZoneId.systemDefault()).toLocalDate();
        return context.isContinuousStopHoldDate(machineCode, productionDate)
                || context.isContinuousStopHoldMachine(machineCode);
    }

    /**
     * 判断结构最低机台保留时间是否覆盖目标班次。
     *
     * @param context 排程上下文
     * @param machineCode 机台编码
     * @param shift 班次配置
     * @return true-保留结束时间不早于班次结束；false-未覆盖
     */
    private boolean isStructureRetentionCoveringShift(LhScheduleContext context,
                                                       String machineCode,
                                                       LhShiftConfigVO shift) {
        Date retentionEndTime = context.getStructureMinMachineRetentionEndTimeMap().get(machineCode);
        return Objects.nonNull(retentionEndTime) && Objects.nonNull(shift)
                && Objects.nonNull(shift.getShiftEndDateTime())
                && !retentionEndTime.before(shift.getShiftEndDateTime());
    }

    /**
     * 收集结构相关的运行态机台编码。
     *
     * @param context 排程上下文
     * @param structureName 结构名称
     * @return 运行态机台编码集合
     */
    private Set<String> collectStructureRuntimeMachineCodes(LhScheduleContext context,
                                                            String structureName) {
        Set<String> machineCodes = new LinkedHashSet<String>(8);
        for (LhScheduleResult result : collectStructureResults(context, structureName)) {
            if (StringUtils.isNotEmpty(result.getLhMachineCode())) {
                machineCodes.add(result.getLhMachineCode());
            }
        }
        for (MachineScheduleDTO machine : context.getMachineScheduleMap().values()) {
            if (Objects.nonNull(machine) && StringUtils.isNotEmpty(machine.getMachineCode())
                    && isStructureMaterial(context, structureName, machine.getCurrentMaterialCode())) {
                machineCodes.add(machine.getMachineCode());
            }
        }
        return machineCodes;
    }

    /**
     * 判断物料是否属于指定结构。
     *
     * @param context 排程上下文
     * @param structureName 结构名称
     * @param materialCode 物料编码
     * @return true-属于结构；false-不属于或物料为空
     */
    private boolean isStructureMaterial(LhScheduleContext context,
                                        String structureName,
                                        String materialCode) {
        if (StringUtils.isEmpty(materialCode)) {
            return false;
        }
        if (isSnapshotStructureMaterial(context, structureName, materialCode)) {
            return true;
        }
        for (LhScheduleResult result : collectStructureResults(context, structureName)) {
            if (StringUtils.equals(materialCode, result.getMaterialCode())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 仅按S4.3冻结的SKU快照判断物料结构归属。
     *
     * @param context 排程上下文
     * @param structureName 结构名称
     * @param materialCode 物料编码
     * @return true-快照中存在该物料；false-不存在
     */
    private boolean isSnapshotStructureMaterial(LhScheduleContext context,
                                                String structureName,
                                                String materialCode) {
        List<SkuScheduleDTO> snapshotList =
                context.getStructureMinMachineSkuSnapshotMap().get(structureName);
        if (CollectionUtils.isEmpty(snapshotList)) {
            return false;
        }
        for (SkuScheduleDTO sku : snapshotList) {
            if (Objects.nonNull(sku)
                    && StringUtils.equals(materialCode, sku.getMaterialCode())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断结构在指定机台班次是否有正量计划。
     */
    private boolean hasPositiveStructurePlan(LhScheduleContext context,
                                             String structureName,
                                             String machineCode,
                                             int shiftIndex) {
        for (LhScheduleResult result : context.getScheduleResultList()) {
            if (Objects.nonNull(result) && StringUtils.equals(machineCode, result.getLhMachineCode())
                    && (StringUtils.equals(structureName, result.getStructureName())
                    || isSnapshotStructureMaterial(
                    context, structureName, result.getMaterialCode()))) {
                Integer planQty = ShiftFieldUtil.getShiftPlanQty(result, shiftIndex);
                if (Objects.nonNull(planQty) && planQty > 0) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 判断其他结构是否已在指定机台班次接管生产。
     */
    private boolean hasPositiveOtherStructurePlan(LhScheduleContext context,
                                                  String structureName,
                                                  String machineCode,
                                                  int shiftIndex) {
        for (LhScheduleResult result : context.getScheduleResultList()) {
            if (Objects.nonNull(result) && StringUtils.equals(machineCode, result.getLhMachineCode())
                    && !StringUtils.equals(structureName, result.getStructureName())
                    && !isSnapshotStructureMaterial(
                    context, structureName, result.getMaterialCode())) {
                Integer planQty = ShiftFieldUtil.getShiftPlanQty(result, shiftIndex);
                if (Objects.nonNull(planQty) && planQty > 0) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 判断同一机台班次是否存在与占位结果不同物料的正量计划。
     */
    private boolean hasPositiveOtherMaterialPlan(LhScheduleContext context,
                                                 LhScheduleResult carrierResult,
                                                 int shiftIndex) {
        for (LhScheduleResult result : context.getScheduleResultList()) {
            if (Objects.nonNull(result)
                    && StringUtils.equals(carrierResult.getLhMachineCode(), result.getLhMachineCode())
                    && !StringUtils.equals(carrierResult.getMaterialCode(), result.getMaterialCode())) {
                Integer planQty = ShiftFieldUtil.getShiftPlanQty(result, shiftIndex);
                if (Objects.nonNull(planQty) && planQty > 0) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 构建机台班次唯一键，用于阶段判断前后传递零量结构保机占位状态。
     *
     * @param machineCode 机台编码
     * @param shiftIndex 班次索引
     * @return 机台班次唯一键
     */
    private String buildMachineShiftKey(String machineCode, int shiftIndex) {
        return machineCode + "#" + shiftIndex;
    }

    /**
     * 按结束时间反查其覆盖的最后班次索引。
     */
    private int resolveShiftIndexCoveredByEndTime(LhScheduleContext context, Date endTime) {
        int latestShiftIndex = -1;
        for (LhShiftConfigVO shift : context.getScheduleWindowShifts()) {
            if (Objects.nonNull(shift) && Objects.nonNull(shift.getShiftIndex())
                    && Objects.nonNull(shift.getShiftEndDateTime())
                    && !shift.getShiftEndDateTime().after(endTime)) {
                latestShiftIndex = Math.max(latestShiftIndex, shift.getShiftIndex());
            }
        }
        return latestShiftIndex;
    }

    /**
     * 收集指定结构的全部排程结果。
     */
    private List<LhScheduleResult> collectStructureResults(LhScheduleContext context,
                                                           String structureName) {
        List<LhScheduleResult> structureResults = new ArrayList<LhScheduleResult>(8);
        if (Objects.isNull(context) || CollectionUtils.isEmpty(context.getScheduleResultList())) {
            return structureResults;
        }
        for (LhScheduleResult result : context.getScheduleResultList()) {
            if (Objects.nonNull(result)
                    && (StringUtils.equals(structureName, result.getStructureName())
                    || isSnapshotStructureMaterial(
                    context, structureName, result.getMaterialCode()))) {
                structureResults.add(result);
            }
        }
        return structureResults;
    }

    /**
     * 按班次索引读取当前窗口班次。
     */
    private LhShiftConfigVO resolveShift(LhScheduleContext context, int shiftIndex) {
        if (Objects.isNull(context) || CollectionUtils.isEmpty(context.getScheduleWindowShifts())) {
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
     * 标记结构本窗口全部结果的保机状态。
     */
    private void markStructureResults(List<LhScheduleResult> structureResults, String retainedFlag) {
        for (LhScheduleResult result : structureResults) {
            result.setIsStructureMinMachineRetained(retainedFlag);
        }
    }

    /**
     * 校验同结构SKU的结构类型及年月一致。
     */
    private boolean isConsistentStructureConfig(LhScheduleContext context,
                                                String structureName,
                                                List<SkuScheduleDTO> structureSkuList,
                                                SkuScheduleDTO firstSku) {
        if (Objects.isNull(firstSku)) {
            warnConfigSkip(context, structureName, "结构SKU为空，无法解析最低机台数");
            return false;
        }
        for (SkuScheduleDTO sku : structureSkuList) {
            if (Objects.isNull(sku)
                    || !StringUtils.equals(firstSku.getStructureType(), sku.getStructureType())
                    || !Objects.equals(firstSku.getMonthPlanYear(), sku.getMonthPlanYear())
                    || !Objects.equals(firstSku.getMonthPlanMonth(), sku.getMonthPlanMonth())) {
                warnConfigSkip(context, structureName, "同结构SKU的STRUCTURE_TYPE或月计划年月不一致");
                return false;
            }
        }
        return true;
    }

    /**
     * 查询周期结构最低硫化机台数。
     */
    private int resolveCycleStructureMinimum(LhScheduleContext context,
                                             String structureName,
                                             SkuScheduleDTO sku) {
        if (Objects.isNull(sku.getMonthPlanYear()) || Objects.isNull(sku.getMonthPlanMonth())) {
            warnConfigSkip(context, structureName, "周期结构月计划年份或月份为空");
            return SKIP_MIN_MACHINE_COUNT;
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
            warnConfigSkip(context, structureName,
                    "周期结构最低机台配置应唯一，实际记录数="
                            + (CollectionUtils.isEmpty(configs) ? 0 : configs.size()));
            return SKIP_MIN_MACHINE_COUNT;
        }
        return validateMinimumMachineCount(context, structureName,
                configs.get(0).getMinVulcanizingMachine(),
                "T_DP_MONTH_CYCLE_STRUCT_CONFIG.MIN_VULCANIZING_MACHINE");
    }

    /**
     * 查询常规结构最低硫化机台数。
     */
    private int resolveRegularStructureMinimum(LhScheduleContext context, String structureName) {
        List<FactoryParam> params = factoryParamMapper.selectList(
                new LambdaQueryWrapper<FactoryParam>()
                        .eq(FactoryParam::getFactoryCode, context.getFactoryCode())
                        .eq(FactoryParam::getParamCode,
                                LhScheduleParamConstant.REGULAR_STRUCTURE_MIN_VULCANIZING_MACHINE)
                        .eq(FactoryParam::getIsDelete, DeleteFlagEnum.NORMAL.getCode()));
        if (CollectionUtils.isEmpty(params) || params.size() != 1) {
            warnConfigSkip(context, structureName,
                    "工厂参数SYS0204012应唯一，实际记录数="
                            + (CollectionUtils.isEmpty(params) ? 0 : params.size()));
            return SKIP_MIN_MACHINE_COUNT;
        }
        String paramValue = StringUtils.trim(params.get(0).getParamValue());
        if (StringUtils.isEmpty(paramValue)) {
            warnConfigSkip(context, structureName, "工厂参数SYS0204012的PARAM_VALUE为空");
            return SKIP_MIN_MACHINE_COUNT;
        }
        try {
            return validateMinimumMachineCount(context, structureName, Integer.valueOf(paramValue),
                    "T_MP_FACTORY_PARAM.SYS0204012");
        } catch (NumberFormatException e) {
            warnConfigSkip(context, structureName,
                    "工厂参数SYS0204012格式错误，paramValue=" + paramValue);
            return SKIP_MIN_MACHINE_COUNT;
        }
    }

    /**
     * 校验最低机台数配置。
     */
    private int validateMinimumMachineCount(LhScheduleContext context,
                                            String structureName,
                                            Integer minimumMachineCount,
                                            String configSource) {
        if (Objects.isNull(minimumMachineCount) || minimumMachineCount < 0) {
            warnConfigSkip(context, structureName,
                    configSource + "缺失或小于0，实际值=" + minimumMachineCount);
            return SKIP_MIN_MACHINE_COUNT;
        }
        return minimumMachineCount;
    }

    /**
     * 记录结构最低机台数配置异常并安全跳过，不抛异常中断整体排程。
     */
    private void warnConfigSkip(LhScheduleContext context, String structureName, String reason) {
        log.warn("结构[{}]最低硫化机台数配置异常，安全跳过最低机台保留（等价规则未命中）：factoryCode={}, batchNo={}, reason={}",
                structureName, Objects.isNull(context) ? null : context.getFactoryCode(),
                Objects.isNull(context) ? null : context.getBatchNo(), reason);
    }
}
