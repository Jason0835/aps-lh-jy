package com.zlt.aps.lh.context;

import com.zlt.aps.lh.api.domain.dto.MachineScheduleDTO;
import com.zlt.aps.lh.api.domain.dto.ShiftRuntimeState;
import com.zlt.aps.lh.api.domain.dto.SkuScheduleDTO;
import com.zlt.aps.lh.api.domain.entity.LhMachineInfo;
import com.zlt.aps.lh.api.domain.entity.LhMouldChangePlan;
import com.zlt.aps.lh.api.domain.entity.LhMouldCleanPlan;
import com.zlt.aps.lh.api.domain.entity.LhPrecisionPlan;
import com.zlt.aps.lh.api.domain.entity.LhScheduleProcessLog;
import com.zlt.aps.lh.api.domain.entity.LhScheduleResult;
import com.zlt.aps.lh.api.domain.entity.LhSpecifyMachine;
import com.zlt.aps.lh.api.domain.entity.LhUnscheduledResult;
import com.zlt.aps.lh.api.domain.vo.LhShiftConfigVO;
import com.zlt.aps.mdm.api.domain.entity.MdmDevicePlanShut;
import com.zlt.aps.lh.api.domain.entity.LhMachineOnlineInfo;
import com.zlt.aps.lh.api.domain.entity.LhRepairCapsule;
import com.zlt.aps.mdm.api.domain.entity.MdmMaterialInfo;
import com.zlt.aps.mdm.api.domain.entity.MdmModelInfo;
import com.zlt.aps.mdm.api.domain.entity.MdmMonthSurplus;
import com.zlt.aps.mdm.api.domain.entity.MdmSkuLhCapacity;
import com.zlt.aps.mdm.api.domain.entity.MdmSkuMouldRel;
import com.zlt.aps.mdm.api.domain.entity.MdmWorkCalendar;
import com.zlt.aps.mp.api.domain.entity.FactoryMonthPlanProductionFinalResult;
import com.zlt.aps.mp.api.domain.entity.MpAdjustResult;
import lombok.Data;
import org.apache.commons.lang3.StringUtils;
import org.springframework.util.CollectionUtils;

import java.util.*;
import java.util.Objects;

/**
 * 硫化排程上下文
 * <p>贯穿整个排程流程的数据总线，持有排程过程中所有需要的基础数据和中间结果</p>
 *
 * @author APS
 */
@Data
public class LhScheduleContext {

    // ========== 排程基本参数 ==========

    /** 分厂编号 */
    private String factoryCode;
    /** 分厂名称 */
    private String factoryName;
    /** 排程目标日（与请求体日期一致，如业务上的 T+2） */
    private Date scheduleTargetDate;
    /**
     * 排程窗口起点 T 日：由 {@link #scheduleTargetDate} 减去 (排程天数 - 1) 得到，
     * 排程天数来自硫化参数 {@code SCHEDULE_DAYS}（默认见 {@link com.zlt.aps.lh.api.constant.LhScheduleConstant#SCHEDULE_DAYS}），
     * 供班次计算、基础数据加载等引擎时间轴使用
     */
    private Date scheduleDate;
    /** 批次号 */
    private String batchNo;
    /** 月计划需求版本 */
    private String monthPlanVersion;
    /** 月计划排产版本 */
    private String productionVersion;
    /** 操作人 */
    private String operator;
    /** 本次排程配置快照 */
    private LhScheduleConfig scheduleConfig;

    // ========== 硫化参数(从LhParams加载) ==========

    /** 硫化参数Map, key=paramCode, value=paramValue */
    private Map<String, String> lhParamsMap = new HashMap<>();

    // ========== 基础数据(S4.2加载) ==========

    /** 月生产计划列表 */
    private List<FactoryMonthPlanProductionFinalResult> monthPlanList = new ArrayList<>();
    /** 周程滚动调整结果Map, key=materialCode */
    private Map<String, List<MpAdjustResult>> mpAdjustResultMap = new HashMap<>();
    /** 工作日历列表 */
    private List<MdmWorkCalendar> workCalendarList = new ArrayList<>();
    /** SKU日硫化产能Map, key=materialCode */
    private Map<String, MdmSkuLhCapacity> skuLhCapacityMap = new HashMap<>();
    /** 设备停机计划列表 */
    private List<MdmDevicePlanShut> devicePlanShutList = new ArrayList<>();
    /** SKU与模具关系Map, key=materialCode */
    private Map<String, List<MdmSkuMouldRel>> skuMouldRelMap = new HashMap<>();
    /** 模具台账Map, key=mouldCode */
    private Map<String, MdmModelInfo> modelInfoMap = new HashMap<>();
    /** 硫化机台信息Map, key=machineCode */
    private Map<String, LhMachineInfo> machineInfoMap = new LinkedHashMap<>();
    /** 模具清洗计划列表 */
    private List<LhMouldCleanPlan> cleaningPlanList = new ArrayList<>();
    /** 月底计划余量Map, key=materialCode */
    private Map<String, MdmMonthSurplus> monthSurplusMap = new HashMap<>();
    /** 胎胚实时库存Map, key=embryoCode */
    private Map<String, Integer> embryoRealtimeStockMap = new HashMap<>();
    /** 日完成量Map（按物料+完成日期聚合）, key=materialCode_finishDate(yyyy-MM-dd) */
    private Map<String, Integer> materialDayFinishedQtyMap = new HashMap<>();
    /** 月累计完成量Map（截至目标排产日期含当天）, key=materialCode */
    private Map<String, Integer> materialMonthFinishedQtyMap = new HashMap<>();
    /** 物料信息Map, key=materialCode */
    private Map<String, MdmMaterialInfo> materialInfoMap = new HashMap<>();
    /** 胶囊规格分组Map, key=规格, value=归一化后的分组编码 */
    private Map<String, String> capsuleSpecPeerMap = new HashMap<>();
    /** 胶囊英寸分组Map, key=英寸, value=归一化后的分组编码 */
    private Map<String, String> capsuleProSizePeerMap = new HashMap<>();
    /** 胎胚描述对应物料数量Map, key=胎胚描述 */
    private Map<String, Integer> embryoDescMaterialCountMap = new HashMap<>();
    /** MES硫化在机信息Map, key=machineCode */
    private Map<String, LhMachineOnlineInfo> machineOnlineInfoMap = new HashMap<>();
    /** 硫化定点机台Map, key=materialCode（表字段SPEC_CODE实际维护物料编码） */
    private Map<String, List<LhSpecifyMachine>> specifyMachineMap = new HashMap<>();
    /** 硫化机胶囊已使用次数Map, key=machineCode */
    private Map<String, LhRepairCapsule> capsuleUsageMap = new HashMap<>();
    /** 硫化精度保养计划Map, key=machineCode */
    private Map<String, LhPrecisionPlan> maintenancePlanMap = new HashMap<>();

    // ========== 中间计算结果(S4.3) ==========

    /** 前日排程结果列表(修正后) */
    private List<LhScheduleResult> previousScheduleResultList = new ArrayList<>();
    /** 前日模具交替计划列表，供滚动衔接继承到本批次 */
    private List<LhMouldChangePlan> previousMouldChangePlanList = new ArrayList<>();
    /** 滚动排程继承结果列表，仅存放本批次继承的排程结果 */
    private List<LhScheduleResult> rollingInheritedScheduleResultList = new ArrayList<>();
    /** 滚动排程继承计划量Map，key=materialCode；ScheduleAdjustHandler据此从待排量中扣减 */
    private Map<String, Integer> inheritedPlanQtyMap = new HashMap<>();
    /** 是否已执行滚动排程衔接，影响结转口径和前日日期解析 */
    private boolean rollingScheduleHandoff;
    /** SKU按结构归集, key=structureName, value=SKU排程DTO列表 */
    private Map<String, List<SkuScheduleDTO>> structureSkuMap = new LinkedHashMap<>();
    /** 续作SKU列表 */
    private List<SkuScheduleDTO> continuousSkuList = new ArrayList<>();
    /** 新增SKU列表 */
    private List<SkuScheduleDTO> newSpecSkuList = new ArrayList<>();
    /** 前一日欠产/超产向当日传导的净值，key=materialCode */
    private Map<String, Integer> carryForwardQtyMap = new HashMap<>();

    // ========== 机台分配状态 ==========

    /** 机台排程DTO Map, key=machineCode */
    private Map<String, MachineScheduleDTO> machineScheduleMap = new LinkedHashMap<>();
    /** 机台初始状态快照，供换模计划和回归校验使用 */
    private Map<String, MachineScheduleDTO> initialMachineScheduleMap = new LinkedHashMap<>();
    /** 机台剩余产能Map, key=machineCode, value=各班次剩余产能 */
    private Map<String, int[]> machineShiftCapacityMap = new LinkedHashMap<>();
    /** 班次运行态，key=班次索引 1～N（N≤8） */
    private Map<Integer, ShiftRuntimeState> shiftRuntimeStateMap = new LinkedHashMap<>(8);
    /** 本次排程解析后的班次窗口 */
    private List<LhShiftConfigVO> scheduleWindowShifts = new ArrayList<>();
    /** 机台已分配SKU Map, key=machineCode, value=已分配的排程结果 */
    private Map<String, List<LhScheduleResult>> machineAssignmentMap = new LinkedHashMap<>();
    /** 定点机台挤量预留切换开始时间, key=machineCode */
    private Map<String, Date> specifyMachineReservedSwitchStartTimeMap = new LinkedHashMap<>();
    /** 定点机台挤量预留物料编码, key=machineCode */
    private Map<String, String> specifyMachineReservedMaterialMap = new LinkedHashMap<>();
    /** 每日换模计数, key=dateString, value=[早班换模数, 中班换模数] */
    private Map<String, int[]> dailyMouldChangeCountMap = new LinkedHashMap<>();
    /** 每日首检计数, key=dateString, value=[早班首检数, 中班首检数] */
    private Map<String, int[]> dailyFirstInspectionCountMap = new LinkedHashMap<>();
    /** 每日精度保养计数, key=dateString, value=已安排保养机台数 */
    private Map<String, Integer> dailyMaintenanceCountMap = new LinkedHashMap<>();

    // ========== 排程输出结果 ==========

    /** 硫化排程结果列表 */
    private List<LhScheduleResult> scheduleResultList = new ArrayList<>();
    /** 硫化未排结果列表 */
    private List<LhUnscheduledResult> unscheduledResultList = new ArrayList<>();
    /** 模具交替计划列表 */
    private List<LhMouldChangePlan> mouldChangePlanList = new ArrayList<>();
    /** 排程日志列表 */
    private List<LhScheduleProcessLog> scheduleLogList = new ArrayList<>();

    // ========== 流程控制 ==========

    /** 是否中断排程 */
    private boolean interrupted = false;
    /** 中断原因 */
    private String interruptReason;
    /** 当前执行步骤 */
    private String currentStep;
    /** 校验错误信息集合 */
    private List<String> validationErrorList = new ArrayList<>();
    /** 优先级跟踪日志静默深度（局部搜索模拟分支时递增） */
    private int priorityTraceMuteDepth = 0;

    /**
     * 追加一条校验错误信息（空串或 null 将被忽略）
     *
     * @param message 错误描述
     */
    public void addValidationError(String message) {
        if (StringUtils.isEmpty(message)) {
            return;
        }
        this.validationErrorList.add(message);
    }

    /**
     * 获取硫化参数值
     *
     * @param paramCode    参数代码
     * @param defaultValue 默认值
     * @return 参数值
     */
    public String getParamValue(String paramCode, String defaultValue) {
        if (Objects.nonNull(scheduleConfig)) {
            return scheduleConfig.getParamValue(paramCode, defaultValue);
        }
        return lhParamsMap.getOrDefault(paramCode, defaultValue);
    }

    /**
     * 获取硫化参数值(整数)
     *
     * @param paramCode    参数代码
     * @param defaultValue 默认值
     * @return 参数值(整数)
     */
    public int getParamIntValue(String paramCode, int defaultValue) {
        if (Objects.nonNull(scheduleConfig)) {
            return scheduleConfig.getParamIntValue(paramCode, defaultValue);
        }
        String value = lhParamsMap.get(paramCode);
        if (StringUtils.isEmpty(value)) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * 中断排程流程
     *
     * @param reason 中断原因
     */
    public void interruptSchedule(String reason) {
        this.interrupted = true;
        this.interruptReason = reason;
    }

    /**
     * 进入优先级跟踪日志静默区间。
     * <p>用于局部搜索等模拟分支，避免输出非最终决策日志。</p>
     */
    public void enterPriorityTraceMuteScope() {
        priorityTraceMuteDepth++;
    }

    /**
     * 退出优先级跟踪日志静默区间。
     */
    public void exitPriorityTraceMuteScope() {
        if (priorityTraceMuteDepth > 0) {
            priorityTraceMuteDepth--;
        }
    }

    /**
     * 当前是否处于优先级跟踪日志静默区间。
     *
     * @return true-静默，false-正常输出
     */
    public boolean isPriorityTraceMuted() {
        return priorityTraceMuteDepth > 0;
    }

    /**
     * 将已移出待排队列的SKU同步从结构分组中剔除。
     * <p>structureSkuMap 在 S4.4 / S4.5 期间既用于顺序3结构收尾判断，也作为 SKU 兜底查询来源，
     * 因此需要与当前待排视图保持一致，避免已消费SKU继续影响后续排序与查询。</p>
     *
     * @param sku 已移出待排队列的SKU
     */
    public void removePendingSkuFromStructureMap(SkuScheduleDTO sku) {
        if (Objects.isNull(sku)
                || CollectionUtils.isEmpty(structureSkuMap)
                || StringUtils.isEmpty(sku.getStructureName())) {
            return;
        }
        List<SkuScheduleDTO> structureSkuList = structureSkuMap.get(sku.getStructureName());
        if (CollectionUtils.isEmpty(structureSkuList)) {
            structureSkuMap.remove(sku.getStructureName());
            return;
        }
        List<SkuScheduleDTO> mutableStructureSkuList = new ArrayList<>(structureSkuList);
        Iterator<SkuScheduleDTO> iterator = mutableStructureSkuList.iterator();
        while (iterator.hasNext()) {
            SkuScheduleDTO currentSku = iterator.next();
            if (isSameStructureSku(currentSku, sku)) {
                iterator.remove();
                break;
            }
        }
        if (CollectionUtils.isEmpty(mutableStructureSkuList)) {
            structureSkuMap.remove(sku.getStructureName());
            return;
        }
        structureSkuMap.put(sku.getStructureName(), mutableStructureSkuList);
    }

    /**
     * 判断结构分组中的SKU是否与目标SKU一致。
     *
     * @param currentSku 结构分组中的SKU
     * @param targetSku  目标SKU
     * @return true-同一SKU，false-不同SKU
     */
    private boolean isSameStructureSku(SkuScheduleDTO currentSku, SkuScheduleDTO targetSku) {
        if (currentSku == targetSku) {
            return true;
        }
        if (Objects.isNull(currentSku) || Objects.isNull(targetSku)) {
            return false;
        }
        return StringUtils.equals(currentSku.getMaterialCode(), targetSku.getMaterialCode());
    }

    /**
     * 基于当前待排SKU列表重建结构分组。
     * <p>用于阶段性收口结构视图，避免已消费SKU继续影响后续优先级判断。</p>
     *
     * @param pendingSkuList 当前待排SKU列表
     */
    public void rebuildStructureSkuMapFromPending(List<SkuScheduleDTO> pendingSkuList) {
        if (CollectionUtils.isEmpty(pendingSkuList)) {
            structureSkuMap = new LinkedHashMap<>();
            return;
        }
        Map<String, List<SkuScheduleDTO>> rebuiltStructureSkuMap = new LinkedHashMap<>(16);
        for (SkuScheduleDTO sku : pendingSkuList) {
            if (Objects.isNull(sku) || StringUtils.isEmpty(sku.getStructureName())) {
                continue;
            }
            rebuiltStructureSkuMap.computeIfAbsent(sku.getStructureName(), key -> new ArrayList<>()).add(sku);
        }
        structureSkuMap = rebuiltStructureSkuMap;
    }

    /**
     * 获取工厂展示名称
     * <p>优先使用工厂名称，未设置时回退工厂编号。</p>
     *
     * @return 工厂展示名称
     */
    public String getFactoryDisplayName() {
        if (StringUtils.isNotEmpty(factoryName)) {
            return factoryName;
        }
        return factoryCode;
    }

}
