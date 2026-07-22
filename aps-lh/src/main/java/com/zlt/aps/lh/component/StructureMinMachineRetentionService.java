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
 * <p>本服务只在“已有排程结果的机台准备下机”时做实时判断，不再使用结构3天内收尾条件，
 * 也不在续作或新增阶段结束后按聚合快照二次推测。每次判断都会重新读取当前结果、机台物料关系、
 * 真实降模边界、停产保机状态和业务停机窗口，因此连续下机动作能够使用上一次处理后的最新状态。</p>
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
        Set<String> physicalMachineCodes = new LinkedHashSet<String>(8);
        if (Objects.isNull(context) || StringUtils.isEmpty(structureName) || shiftIndex < 1
                || Objects.isNull(resolveShift(context, shiftIndex))) {
            return physicalMachineCodes;
        }
        for (String machineCode : collectStructureRuntimeMachineCodes(context, structureName)) {
            if (isRuntimeMachineInStructureAtShift(context, structureName, machineCode, shiftIndex)) {
                physicalMachineCodes.add(
                        LhSingleControlMachineUtil.resolvePhysicalMachineCode(machineCode));
            }
        }
        return physicalMachineCodes;
    }

    /**
     * 在已有排程结果的机台准备下机前，实时判断并执行结构最低机台数保留。
     *
     * <p>调用方应先完成本次下机对应的计划量清零，再在登记真实释放边界、解除当前物料关系或把机台
     * 交给后物料之前调用本方法。方法会把待下机物理机台补入“下机前”集合，再模拟移除该物理机台；
     * 单控另一侧仍保持同结构关系时，移除一侧不会减少物理机台数。</p>
     *
     * <p>本方法只处理已有结果行。窗口首班前已直接释放且本批没有结果行的场景不在本规则范围，
     * 不会为了保机新增零量结果。</p>
     *
     * @param context 排程上下文
     * @param sourceSku 准备下机的SKU
     * @param result 准备下机的现有排程结果行
     * @param offlineShiftIndex 准备下机的班次索引
     * @param lastPositiveShiftIndex 清零前该机台最后一个有量班次索引
     * @param offlineReason 下机原因，用于审计日志
     * @return true-下机后低于最低值，已执行保机；false-允许按原逻辑正常下机
     */
    public boolean retainMachineBeforeOffline(LhScheduleContext context,
                                              SkuScheduleDTO sourceSku,
                                              LhScheduleResult result,
                                              int offlineShiftIndex,
                                              int lastPositiveShiftIndex,
                                              String offlineReason) {
        if (Objects.isNull(context) || Objects.isNull(result)
                || StringUtils.isEmpty(result.getLhMachineCode())
                || offlineShiftIndex < 1 || lastPositiveShiftIndex < 1) {
            return false;
        }
        String structureName = resolveStructureName(sourceSku, result);
        String materialCode = resolveMaterialCode(sourceSku, result);
        Integer minimumMachineCount = context.getStructureMinVulcanizingMachineMap().get(structureName);
        if (StringUtils.isEmpty(structureName) || Objects.isNull(minimumMachineCount)
                || minimumMachineCount <= 0) {
            result.setIsStructureMinMachineRetained(NOT_RETAINED_FLAG);
            return false;
        }

        Set<String> beforePhysicalCodes = collectStructureInMachinePhysicalCodes(
                context, structureName, offlineShiftIndex);
        String machineCode = result.getLhMachineCode();
        String offlinePhysicalMachineCode =
                LhSingleControlMachineUtil.resolvePhysicalMachineCode(machineCode);
        // 调用点已把待下机班次清零，但物料关系尚未解除，因此显式补回待下机物理机台形成真实下机前口径。
        beforePhysicalCodes.add(offlinePhysicalMachineCode);
        Set<String> afterPhysicalCodes = new LinkedHashSet<String>(beforePhysicalCodes);
        if (!hasOtherActiveRuntimeSide(context, structureName, machineCode, offlineShiftIndex)) {
            afterPhysicalCodes.remove(offlinePhysicalMachineCode);
        }

        boolean retained = afterPhysicalCodes.size() < minimumMachineCount;
        if (retained) {
            int retentionLastShiftIndex = Math.max(offlineShiftIndex,
                    resolveStructureLastOccupiedShiftIndex(context, structureName));
            applyRetention(context, structureName, result, offlineShiftIndex, retentionLastShiftIndex);
        } else if (!context.getStructureMinMachineRetainedStructureSet().contains(structureName)) {
            result.setIsStructureMinMachineRetained(NOT_RETAINED_FLAG);
        }
        log.info("结构最低机台数下机判断, factoryCode: {}, batchNo: {}, structureName: {}, materialCode: {}, "
                        + "offlineReason: {}, offlineShift: {}, minimumMachineCount: {}, beforeMachineCount: {}, "
                        + "afterMachineCount: {}, retained: {}, retainedMachine: {}",
                context.getFactoryCode(), context.getBatchNo(), structureName, materialCode,
                StringUtils.defaultString(offlineReason), offlineShiftIndex, minimumMachineCount,
                beforePhysicalCodes.size(), afterPhysicalCodes.size(), retained,
                retained ? machineCode : "-");
        return retained;
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
        for (LhScheduleResult result : context.getScheduleResultList()) {
            if (Objects.isNull(result) || !context.getStructureMinMachineRetainedStructureSet()
                    .contains(result.getStructureName())) {
                continue;
            }
            result.setIsStructureMinMachineRetained(RETAINED_FLAG);
            Date retentionEndTime = context.getStructureMinMachineRetentionEndTimeMap()
                    .get(result.getLhMachineCode());
            if (Objects.nonNull(retentionEndTime)) {
                delayResultRelease(result, retentionEndTime);
                delayMachineRelease(context, result.getLhMachineCode(), retentionEndTime);
            }
        }
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
     * 执行命中后的零量占位、结果顺延、机台占用顺延和结果标识。
     *
     * @param context 排程上下文
     * @param structureName 结构名称
     * @param result 被保留机台的结果行
     * @param offlineShiftIndex 下机班次
     * @param retentionLastShiftIndex 结构本窗口最后占用班次
     */
    private void applyRetention(LhScheduleContext context,
                                String structureName,
                                LhScheduleResult result,
                                int offlineShiftIndex,
                                int retentionLastShiftIndex) {
        LhShiftConfigVO retentionLastShift = resolveShift(context, retentionLastShiftIndex);
        if (Objects.isNull(retentionLastShift) || Objects.isNull(retentionLastShift.getShiftEndDateTime())) {
            warnConfigSkip(context, structureName,
                    "保机结束班次缺少结束时间，shiftIndex=" + retentionLastShiftIndex);
            return;
        }
        fillMachinePlaceholderShifts(context, structureName, result,
                offlineShiftIndex, retentionLastShiftIndex);
        Date retentionEndTime = retentionLastShift.getShiftEndDateTime();
        delayMachineRelease(context, result.getLhMachineCode(), retentionEndTime);
        MachineScheduleDTO retainedMachine = context.getMachineScheduleMap().get(result.getLhMachineCode());
        if (Objects.nonNull(retainedMachine)) {
            // 普通下机状态同步可能已回退机台物料；命中后必须恢复当前SKU占用，明确机台未实际释放。
            retainedMachine.setCurrentMaterialCode(result.getMaterialCode());
            retainedMachine.setCurrentMaterialDesc(result.getMaterialDesc());
        }
        delayResultRelease(result, retentionEndTime);
        markStructureResults(collectStructureResults(context, structureName), RETAINED_FLAG);
        context.getStructureMinMachineRetainedStructureSet().add(structureName);
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
                                                        int shiftIndex) {
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
        if (isContinuousStopHoldAtShift(context, machineCode, shift)) {
            return true;
        }
        return hasBusinessDowntimeAtShift(context, machineCode, shift);
    }

    /**
     * 判断同一物理机台是否还有另一运行态侧保持当前结构关系。
     *
     * @param context 排程上下文
     * @param structureName 结构名称
     * @param offlineMachineCode 当前下机运行态机台编码
     * @param shiftIndex 下机班次
     * @return true-另一侧仍在机，物理机台数不能减少；false-物理机台随本次下机减少
     */
    private boolean hasOtherActiveRuntimeSide(LhScheduleContext context,
                                              String structureName,
                                              String offlineMachineCode,
                                              int shiftIndex) {
        String physicalMachineCode =
                LhSingleControlMachineUtil.resolvePhysicalMachineCode(offlineMachineCode);
        for (String machineCode : collectStructureRuntimeMachineCodes(context, structureName)) {
            if (!StringUtils.equals(machineCode, offlineMachineCode)
                    && StringUtils.equals(physicalMachineCode,
                    LhSingleControlMachineUtil.resolvePhysicalMachineCode(machineCode))
                    && isRuntimeMachineInStructureAtShift(context, structureName, machineCode, shiftIndex)) {
                return true;
            }
        }
        return false;
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
        List<SkuScheduleDTO> snapshotList =
                context.getStructureMinMachineSkuSnapshotMap().get(structureName);
        if (!CollectionUtils.isEmpty(snapshotList)) {
            for (SkuScheduleDTO sku : snapshotList) {
                if (Objects.nonNull(sku) && StringUtils.equals(materialCode, sku.getMaterialCode())) {
                    return true;
                }
            }
        }
        for (LhScheduleResult result : collectStructureResults(context, structureName)) {
            if (StringUtils.equals(materialCode, result.getMaterialCode())) {
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
                    && StringUtils.equals(structureName, result.getStructureName())) {
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
                    && !StringUtils.equals(structureName, result.getStructureName())) {
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
     * 解析结构当前结果和已登记保机占用的最后班次。
     */
    private int resolveStructureLastOccupiedShiftIndex(LhScheduleContext context,
                                                       String structureName) {
        int latestShiftIndex = -1;
        for (LhScheduleResult result : collectStructureResults(context, structureName)) {
            latestShiftIndex = Math.max(latestShiftIndex,
                    ShiftFieldUtil.resolveLastPlannedShiftIndex(result));
            Date retentionEndTime = context.getStructureMinMachineRetentionEndTimeMap()
                    .get(result.getLhMachineCode());
            if (Objects.nonNull(retentionEndTime)) {
                latestShiftIndex = Math.max(latestShiftIndex,
                        resolveShiftIndexCoveredByEndTime(context, retentionEndTime));
            }
        }
        return latestShiftIndex;
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
            if (Objects.nonNull(result) && StringUtils.equals(structureName, result.getStructureName())) {
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
     * 解析下机SKU的结构名称。
     */
    private String resolveStructureName(SkuScheduleDTO sourceSku, LhScheduleResult result) {
        return Objects.nonNull(sourceSku) && StringUtils.isNotEmpty(sourceSku.getStructureName())
                ? sourceSku.getStructureName() : result.getStructureName();
    }

    /**
     * 解析下机SKU物料编码。
     */
    private String resolveMaterialCode(SkuScheduleDTO sourceSku, LhScheduleResult result) {
        return Objects.nonNull(sourceSku) && StringUtils.isNotEmpty(sourceSku.getMaterialCode())
                ? sourceSku.getMaterialCode() : result.getMaterialCode();
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
