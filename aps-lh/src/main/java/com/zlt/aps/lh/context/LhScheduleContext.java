package com.zlt.aps.lh.context;

import com.zlt.aps.lh.api.domain.dto.CleaningScheduleDateFillItem;
import com.zlt.aps.lh.api.domain.dto.MachineScheduleDTO;
import com.zlt.aps.lh.api.domain.dto.ShiftProductionControlDTO;
import com.zlt.aps.lh.api.domain.dto.ShiftRuntimeState;
import com.zlt.aps.lh.api.domain.dto.SkuScheduleDTO;
import com.zlt.aps.lh.api.enums.SingleControlMachineModeEnum;
import com.zlt.aps.lh.api.domain.entity.LhMachineInfo;
import com.zlt.aps.lh.api.domain.entity.LhMouldChangePlan;
import com.zlt.aps.lh.api.domain.entity.LhMouldCleanPlan;
import com.zlt.aps.lh.api.domain.entity.LhPrecisionPlan;
import com.zlt.aps.lh.api.domain.entity.LhScheduleProcessLog;
import com.zlt.aps.lh.api.domain.entity.LhScheduleResult;
import com.zlt.aps.lh.api.domain.entity.LhSpecifyMachine;
import com.zlt.aps.lh.api.domain.entity.LhSpecialMaterialBom;
import com.zlt.aps.lh.api.domain.entity.LhUnscheduledResult;
import com.zlt.aps.lh.api.domain.vo.LhShiftConfigVO;
import com.zlt.aps.lh.engine.strategy.support.MouldResourceContext;
import com.zlt.aps.lh.engine.strategy.support.HistoricalReverseSelectionDirective;
import com.zlt.aps.lh.component.MonthPlanDateResolver;
import com.zlt.aps.lh.util.SkuConstructionRefResolverUtil;
import com.zlt.aps.mdm.api.domain.entity.MdmDevicePlanShut;
import com.zlt.aps.lh.api.domain.entity.LhMachineOnlineInfo;
import com.zlt.aps.lh.api.domain.entity.LhRepairCapsule;
import com.zlt.aps.mdm.api.domain.entity.MdmMaterialInfo;
import com.zlt.aps.mdm.api.domain.entity.MdmModelInfo;
import com.zlt.aps.mdm.api.domain.entity.MdmSkuConstructionRef;
import com.zlt.aps.mdm.api.domain.entity.MdmSkuLhCapacity;
import com.zlt.aps.mdm.api.domain.entity.MdmSkuMouldRel;
import com.zlt.aps.mdm.api.domain.entity.MdmWorkCalendar;
import com.zlt.aps.mp.api.domain.entity.FactoryMonthPlanProductionFinalResult;
import lombok.Data;
import org.apache.commons.lang3.StringUtils;
import org.springframework.util.CollectionUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.Objects;

/**
 * 硫化排程上下文。
 *
 * <p>业务定位：</p>
 * <ul>
 *   <li>贯穿一次硫化排程从 S4.1 到 S4.6 的可变数据总线；</li>
 *   <li>承载月计划、日计划额度、机台、模具、胎胚库存、MES在机、工作日历、保养/清洗等基础数据；</li>
 *   <li>承载 SKU 归集结果、续作列表、新增列表、机台分配状态、结果列表、未排列表和模具交替计划；</li>
 *   <li>为排序、选机、收尾、换模、换活字块、班次分配和结果校验策略共享同一运行态。</li>
 * </ul>
 *
 * <p>注意：该对象会被多个 Handler/Strategy 原地修改。新增字段时必须同时确认初始化入口、消费策略、
 * 结果落库和回归测试，避免上下文字段只有写入没有消费，或只有消费没有初始化。</p>
 *
 * @author APS
 */
@Data
public class LhScheduleContext {

    /** 历史交替计划缺少产品状态时统一使用的正规状态 */
    private static final String FORMAL_PRODUCT_STATUS = "S";

    // ========== 排程基本参数 ==========

    /** 分厂编号 */
    private String factoryCode;
    /** 分厂名称 */
    private String factoryName;
    /** 排程目标日/业务保存日期（与请求体日期一致，业务口径为 T+1），仅用于结果保存、查询、日志等业务归属 */
    private Date scheduleTargetDate;
    /**
     * 排程窗口起点 T 日：由 {@link #scheduleTargetDate} 减去 (排程天数 - 2) 得到，
     * 排程天数来自硫化参数 {@code SCHEDULE_DAYS}（默认见 {@link com.zlt.aps.lh.api.constant.LhScheduleConstant#SCHEDULE_DAYS}），
     * 供班次计算、基础数据加载等引擎时间轴使用
     */
    private Date scheduleDate;
    /**
     * 当前正在排程的业务日期：由具体排程策略在处理日维度业务前刷新，
     * 不等同于排程窗口起点 {@link #scheduleDate}，也不等同于业务保存目标日 {@link #scheduleTargetDate}。
     */
    private Date currentScheduleDate;
    /**
     * 排程窗口结束日期 T+2 日：由 {@link #scheduleDate} + 2 得到，
     * 用于 day1/day2/day3 月计划映射、产能计算、加机台、收尾、欠产追补、换模日上限、
     * 跨月检测、滚动续作追加起点、班次日期反推等排程核心逻辑，
     * 与仅用于业务保存/查询的 {@link #scheduleTargetDate}（T+1）分离。
     */
    private Date windowEndDate;
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
    /** 硫化开模时间 */
    private Date curingOpenMoldTime;
    /** 硫化停锅时间 */
    private Date curingStopPotTime;
    /** 开产班次 */
    private ShiftProductionControlDTO openProductionShift;
    /** 停产班次 */
    private ShiftProductionControlDTO stopProductionShift;
    /** 是否启用开停产管控 */
    private boolean enableOpenStopProductionControl;
    /** 是否处于开产模式 */
    private boolean openProductionMode;
    /** 是否处于停产模式 */
    private boolean stopProductionMode;
    /** 开产欠产阈值比例 */
    private BigDecimal openProductionShortageThresholdRate;

    // ========== 硫化参数(从LhParams加载) ==========

    /** 硫化参数Map, key=paramCode, value=paramValue */
    private Map<String, String> lhParamsMap = new HashMap<>();

    // ========== 基础数据(S4.2加载) ==========

    /** 月生产计划列表，来源于月计划最终结果表，是 SKU 归集和 day1/day2/day3 窗口计划量的主数据来源 */
    private List<FactoryMonthPlanProductionFinalResult> monthPlanList = new ArrayList<>();
    /** 本次排程加载的全部月计划列表，跨月时包含多个自然月；供按业务日期解析 dayN 使用 */
    private List<FactoryMonthPlanProductionFinalResult> loadedMonthPlanList = new ArrayList<>();
    /** 物料+产品状态+年月 -> 月计划记录索引，跨月或同物料多产品状态时避免误取其他计划 */
    private Map<String, FactoryMonthPlanProductionFinalResult> monthPlanByMaterialMonthMap = new LinkedHashMap<>();
    /** 年月 -> 定稿需求版本，跨月加载月计划和周程调整时按自然月取版本 */
    private Map<String, String> monthPlanVersionByYearMonthMap = new LinkedHashMap<>();
    /** 年月 -> 定稿排产版本，跨月加载月计划和结构机台统计时按自然月取版本 */
    private Map<String, String> productionVersionByYearMonthMap = new LinkedHashMap<>();
    /** 物料+产品状态+年月 -> 月累计完成量，避免同一物料不同产品状态或跨月时完成量串月 */
    private Map<String, Integer> materialMonthFinishedQtyByMonthMap = new HashMap<>();
    /** 工作日历列表 */
    private List<MdmWorkCalendar> workCalendarList = new ArrayList<>();
    /** SKU日硫化产能Map, key=materialCode */
    private Map<String, MdmSkuLhCapacity> skuLhCapacityMap = new HashMap<>();
    /** 设备停机计划列表 */
    private List<MdmDevicePlanShut> devicePlanShutList = new ArrayList<>();
    /**
     * 本次排程已加载的清洗类设备停机候选列表。
     * <p>仅保存计划开始时间不早于排程日期 T 日、尚未实际完成且未删除的干冰/喷砂清洗候选。
     * 清洗候选转换为运行态清洗窗口后会从 {@link #devicePlanShutList} 剥离，但该只读快照仍供续作降模
     * 判断“机台是否存在有效清洗计划”使用，不参与普通停机产能扣减，也不改变清洗每日上限和班次安排。</p>
     */
    private List<MdmDevicePlanShut> loadedCleaningPlanShutList = new ArrayList<>();
    /**
     * 清洗计划排程日期回填项列表。
     * <p>清洗实际安排成功时记录实际清洗开始时间；因 SKU 3 天内收尾跳过清洗时记录收尾日期。
     * 该列表在排程结果落库事务（{@code replaceScheduleAtomically}）内统一回填到
     * {@code T_MDM_DEVICE_PLAN_SHUT.SCHEDULE_DATE}，按设备停机计划主键 id 去重更新。
     * 配对侧派生窗口、超窗口上限、配置非法、最晚日期超限等未安排场景不收集回填项。</p>
     */
    private List<CleaningScheduleDateFillItem> cleaningScheduleDateFillList = new ArrayList<>();
    /** SKU与模具关系Map, key=materialCode */
    private Map<String, List<MdmSkuMouldRel>> skuMouldRelMap = new HashMap<>();
    /** 模具台账Map, key=mouldCode */
    private Map<String, MdmModelInfo> modelInfoMap = new HashMap<>();
    /** 硫化机台信息Map, key=machineCode */
    private Map<String, LhMachineInfo> machineInfoMap = new LinkedHashMap<>();
    /** 旧模具清洗计划兼容列表；干冰/喷砂清洗排程不再使用该列表作为来源 */
    private List<LhMouldCleanPlan> cleaningPlanList = new ArrayList<>();
    /** 胎胚实时库存Map, key=embryoCode；始终保存原始库存，内部排产额度分摊不得回写到该原始库存口径 */
    private Map<String, Integer> embryoRealtimeStockMap = new HashMap<>();
    /** 胎胚收尾标识Map, key=embryoCode, value=1-收尾/0-非收尾；以胎胚维度合并硫化余量后按主销参与情况判定 */
    private Map<String, Integer> embryoEndingFlagMap = new HashMap<>();
    /** 日完成量Map（按物料+产品状态+完成日期聚合）, key=materialCode_productStatus_finishDate(yyyy-MM-dd) */
    private Map<String, Integer> materialDayFinishedQtyMap = new HashMap<>();
    /** 本月日完成量Map（按物料+产品状态+完成日期聚合）, key=materialCode_productStatus_finishDate(yyyy-MM-dd)，仅覆盖当前排程月份截至T-1 */
    private Map<String, Integer> materialMonthDailyFinishedQtyMap = new HashMap<>();
    /** 月累计完成量Map（按月计划所属月份统计，截至排程窗口T日前一日）, key=materialCode_productStatus */
    private Map<String, Integer> materialMonthFinishedQtyMap = new HashMap<>();
    /** T日排程班次完成量Map, key=materialCode_productStatus, value=T日class1FinishQty按业务SKU汇总值 */
    private Map<String, Integer> materialScheDayFinishQtyMap = new HashMap<>();
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
    /**
     * 本批排程胶囊运行态使用次数，key=物理机台编码。
     * <p>初值取 {@link #capsuleUsageMap} 左右模次数最大值，后续按物理机台实际总产量累计；
     * 本批首次严格跨限后不重置，不回写胶囊维修表，下一批仍重新接受MES快照。</p>
     */
    private Map<String, Integer> capsuleRuntimeUsageMap = new LinkedHashMap<String, Integer>();
    /**
     * 已执行换胶囊的物理机台班次键集合，key=物理机台编码 + 工作日期 + 班次索引。
     * <p>用于定位本批首次严格跨限实际发生的班次，并为后置产能防回扩提供事实依据。</p>
     */
    private Set<String> capsuleReplacementShiftKeySet = new LinkedHashSet<String>();
    /**
     * 本批已处理胶囊上限的物理机台集合，key=物理机台编码。
     * <p>初始左右最大值已达到上限，或本批已执行首次严格跨限扣量时登记；集合内机台
     * 后续只累计物理总产量，不再重复扣量或备注。</p>
     */
    private Set<String> capsuleThresholdHandledMachineSet = new LinkedHashSet<String>();
    /**
     * 已换胶囊结果班次允许写入的最大计划量，key=物理机台班次键 + 结果业务键。
     * <p>首次换胶囊时记录扣减后的精确上限，供日标准收敛、班次重分配和补量复用，
     * 防止后置逻辑按理论班产重新补回固定损失，也避免重复查询时再次扣减。</p>
     */
    private Map<String, Integer> capsuleReplacementShiftCapacityLimitMap =
            new LinkedHashMap<String, Integer>();
    /** 硫化精度保养计划Map, key=machineCode */
    private Map<String, LhPrecisionPlan> maintenancePlanMap = new HashMap<>();
    /**
     * 排程年度精准计划条数：machineCode -> 当年有效记录数。
     * <p>包含已完成与未完成计划，仅用于“一机一年一条”完整性告警；实际排程仍只读取
     * maintenancePlanMap 中未完成且实际完成时间为空的计划。</p>
     */
    private Map<String, Integer> annualMaintenancePlanCountMap = new HashMap<>();
    /** 特殊物料清单配置列表 */
    private List<LhSpecialMaterialBom> specialMaterialBomList = new ArrayList<>();
    /** 特殊物料分类Map, key=物料编码, value=分类集合 */
    private Map<String, Set<String>> specialMaterialCategoryByMaterialCode = new HashMap<>();
    /** 特殊物料分类Map, key=结构名称, value=分类集合 */
    private Map<String, Set<String>> specialMaterialCategoryByStructureName = new HashMap<>();
    /** SKU与示方书关系Map, key=materialCode */
    private Map<String, MdmSkuConstructionRef> skuConstructionRefMap = new HashMap<>();
    /** SKU与示方书关系Map(复合key), key=materialCode + "::" + trialStatus */
    private Map<String, MdmSkuConstructionRef> skuConstructionRefCompositeKeyMap = new HashMap<>();

    // ========== 中间计算结果(S4.3) ==========

    /** 前日排程结果列表(修正后) */
    private List<LhScheduleResult> previousScheduleResultList = new ArrayList<>();
    /** 业务目标日前一日排程结果列表，仅供新增历史欠产跳过规则兜底判断 */
    private List<LhScheduleResult> targetPreviousScheduleResultList = new ArrayList<>();
    /** 当前排程目标日上一轮排程结果（用于硫化示方历史保护） */
    private List<LhScheduleResult> previousCureFormulaResultList = new ArrayList<>();
    /** 前日模具交替计划列表，供滚动衔接继承到本批次 */
    private List<LhMouldChangePlan> previousMouldChangePlanList = new ArrayList<>();
    /**
     * 业务目标日前一日模具交替计划列表，仅供“前日交替计划机台反选SKU”使用。
     * <p>该列表固定按 {@link #scheduleTargetDate} 前一日查询，不复用滚动排程的
     * {@link #previousMouldChangePlanList}，避免强制重排时两种历史日期口径互相污染。</p>
     */
    private List<LhMouldChangePlan> historicalReverseMouldChangePlanList = new ArrayList<>();
    /**
     * 解析、去重并绑定当前产品状态后的反选指令。
     * <p>换活字块指令在S4.4内立即委托既有换活字块主链；其余指令由S4.5新增主链在普通候选
     * 排序前优先尝试指定机台。失败指令不会删除SKU，后续仍按普通新增候选继续排产。</p>
     */
    private List<HistoricalReverseSelectionDirective> historicalReverseSelectionDirectiveList =
            new ArrayList<HistoricalReverseSelectionDirective>();
    /** 滚动排程继承结果列表，仅存放本批次继承的排程结果 */
    private List<LhScheduleResult> rollingInheritedScheduleResultList = new ArrayList<>();
    /** 滚动排程继承计划量Map，key=materialCode_productStatus；ScheduleAdjustHandler据此从同状态待排量中扣减 */
    private Map<String, Integer> inheritedPlanQtyMap = new HashMap<>();
    /** 是否已执行滚动排程衔接，影响结转口径和前日日期解析 */
    private boolean rollingScheduleHandoff;
    /** SKU按结构归集, key=structureName, value=SKU排程DTO列表 */
    private Map<String, List<SkuScheduleDTO>> structureSkuMap = new LinkedHashMap<>();
    /**
     * 当前3天、8班窗口内可全部收尾的结构SKU快照。
     * <p>该快照在S4.3按现有结构分组和{@code isCurrentWindowEnding}口径一次性冻结，
     * 不随后续待排结构视图出队而删除，供S4.4/S4.5判断结构是否已完成及配置归属。</p>
     */
    private Map<String, List<SkuScheduleDTO>> currentWindowEndingStructureSkuMap = new LinkedHashMap<>();
    /** 结构最低硫化机台数，key=结构名称，value=周期结构配置或常规结构工厂参数解析值 */
    private Map<String, Integer> structureMinVulcanizingMachineMap = new LinkedHashMap<>();
    /**
     * 尚未完成全部SKU排产的收尾结构临时保护机台，key=运行态机台编码，value=结构名称。
     * <p>临时保护阻止后续SKU提前选走已占用机台；结构完成后按最终判断清除或转为统一释放时间，
     * 窗口末班机台数已达到最低值时可提前确认不命中并清除。</p>
     */
    private Map<String, String> endingStructureProtectedMachineMap = new LinkedHashMap<>();
    /**
     * 已可提前确认不命中最低机台保留规则的结构集合。
     *
     * <p>仅当结构当前已有量的最晚班次已经是排程窗口最后一班，且该班有量物理机台数
     * 已达到最低配置时登记。后续待排SKU只能增加窗口末班机台数，不能把最晚班次继续后移，
     * 因此这些结构无需继续临时锁住提前收尾机台，避免影响正常换活字块、历史反选和新增选机。</p>
     */
    private Set<String> structureMinMachineConfirmedNonRetainedStructureSet = new LinkedHashSet<>();
    /** 命中结构最低机台数保留规则的结构名称集合 */
    private Set<String> structureMinMachineRetainedStructureSet = new LinkedHashSet<>();
    /** 命中规则后的机台统一释放时间，key=运行态机台编码，value=结构最晚有量班次结束时间 */
    private Map<String, Date> structureMinMachineRetentionEndTimeMap = new LinkedHashMap<>();
    /** 业务日期 -> 产品结构 -> 计划硫化机台数，来源于月计划统计表 dayN.lhMachines */
    private Map<LocalDate, Map<String, Integer>> structurePlanMachineCountMap =
            new LinkedHashMap<LocalDate, Map<String, Integer>>(4);
    /** 业务日期 -> 产品结构 -> 已排硫化机台编码集合，按 Set 去重后用于提前生产准入判断 */
    private Map<LocalDate, Map<String, Set<String>>> structureScheduledMachineCodeMap =
            new LinkedHashMap<LocalDate, Map<String, Set<String>>>(4);
    /** 业务日期 -> 物料状态复合键 -> 已排硫化机台编码集合，用于SKU级机台数判断 */
    private Map<LocalDate, Map<String, Set<String>>> skuScheduledMachineCodeMap =
            new LinkedHashMap<LocalDate, Map<String, Set<String>>>(4);
    /** 续作SKU列表，来源于 MES 在机/前批次状态，S4.4 优先排产 */
    private List<SkuScheduleDTO> continuousSkuList = new ArrayList<>();
    /** 新增SKU列表，续作和换活字块未消费完的 SKU 会继续保留到 S4.5 新增链路 */
    private List<SkuScheduleDTO> newSpecSkuList = new ArrayList<>();
    /** 本月历史欠产向当前排程窗口传导的数量，key=materialCode_productStatus */
    private Map<String, Integer> carryForwardQtyMap = new HashMap<>();
    /** 满班补齐超排量累加器，key=materialCode_productStatus，供最终汇总日志使用 */
    private Map<String, Integer> skuShiftFillOverQtyMap = new LinkedHashMap<>();
    /** SKU实际排产剩余账本，key=materialCode_productStatus；不同产品状态独立扣减 */
    private Map<String, Integer> skuProductionRemainingQtyMap = new LinkedHashMap<>();
    /** 胎胚库存消费账本，key=embryoCode + "_" + T日业务日期；用于胎胚收尾T日硬目标扣减 */
    private Map<String, EmbryoStockConsumeLedger> embryoStockConsumeLedgerMap = new LinkedHashMap<>();
    /** 胎胚库存SKU级内部分摊额度，key=materialCode_productStatus；组级总量仍按胎胚账本控制 */
    private Map<String, Integer> embryoStockSkuQuotaMap = new LinkedHashMap<>();
    /** 命中胎胚库存T日硬目标的物料状态复合键集合，用于结果班次量按库存账本奇偶原样裁剪 */
    private Set<String> embryoStockHardTargetMaterialSet = new LinkedHashSet<>();
    /** 共用胎胚收尾错峰降模释放候选原收尾班次快照，使用对象身份避免结果行字段被清零后丢失释放来源 */
    private Map<LhScheduleResult, Integer> sharedEmbryoEndingStaggerReleaseShiftIndexMap =
            new IdentityHashMap<LhScheduleResult, Integer>();
    /** 共用胎胚收尾错峰降模释放候选原班次计划量快照，用于选中后延时恢复原班次收尾产量 */
    private Map<LhScheduleResult, Integer> sharedEmbryoEndingStaggerReleaseShiftQtyMap =
            new IdentityHashMap<LhScheduleResult, Integer>();
    /** 共用胎胚收尾错峰后延允许超目标量，供严格收口、账本裁剪和校验识别“错峰补量”例外 */
    private Map<LhScheduleResult, Integer> sharedEmbryoEndingStaggerAllowedOverQtyMap =
            new IdentityHashMap<LhScheduleResult, Integer>();
    /** 主销/常规SKU收尾补满允许超目标量，供严格收口、账本裁剪和校验识别“补满夜班”例外 */
    private Map<LhScheduleResult, Integer> endingFillAllowedOverQtyMap =
            new IdentityHashMap<LhScheduleResult, Integer>();
    /** S4.5当前待排正规新增SKU数量，供选机阶段判断普通机台让位规则 */
    private int pendingFormalNewSpecSkuCount;
    /** S4.5当前待排试制新增SKU数量，供单控机台内部资源竞争判断 */
    private int pendingTrialNewSpecSkuCount;
    /** S4.5当前待排量试新增SKU数量，供单控机台内部资源竞争判断 */
    private int pendingMassTrialNewSpecSkuCount;
    /** S4.5当前待排小批量新增SKU数量，供单控机台内部资源竞争判断 */
    private int pendingSmallBatchNewSpecSkuCount;
    /** 新增SKU最近一次选机是否被单控/普通机台让位规则清空候选，使用对象身份避免同物料编码互相覆盖 */
    private Map<SkuScheduleDTO, Boolean> newSpecTypeRuleBlockedMap = new IdentityHashMap<>();
    /** 新增SKU提前生产准入结果，供选机和首日排产判断识别提前生产场景，使用对象身份避免同物料编码互相覆盖 */
    private Map<SkuScheduleDTO, Boolean> newSpecEarlyProductionAllowedMap = new IdentityHashMap<>();
    /** 新增SKU进入S4.5时是否命中结构五天内收尾层级快照，使用对象身份避免SKU出队后判定漂移 */
    private Map<SkuScheduleDTO, Boolean> newSpecSingleControlStructureEndingLayerMap = new IdentityHashMap<>();
    /** 单控模式初始目标量快照，key=materialCode_productStatus；S4.3结束时冻结，后续禁止随剩余量变化 */
    private Map<String, Integer> singleControlInitialTargetQtyMap = new LinkedHashMap<>();
    /** 单控模式快照，key=materialCode_productStatus；统一供新增、续作、换活字块、降模和校验消费 */
    private Map<String, SingleControlMachineModeEnum> singleControlModeSnapshotMap = new LinkedHashMap<>();
    /** 冻结时满足单控静态准入且初始待排量大于0的不同试制SKU键集合 */
    private Set<String> singleControlEligibleTrialSkuKeySet = new LinkedHashSet<>();
    /** 单控模式快照是否已完成初始化；完成后禁止再次按动态运行态覆盖 */
    private boolean singleControlModeSnapshotInitialized;
    /** 续作结果日额度账本是否已完成最终同步，防止同一上下文重复扣账 */
    private boolean continuousDailyQuotaSynced;
    /** 续作首日/窗口无计划释放的机台集合，仅用于S4.5选机降优先级，不代表禁止生产 */
    private Set<String> releasedContinuousMachineCodeSet = new LinkedHashSet<>();
    /** 已按降模规则释放过续作机台的物料集合，避免后续补偿链路把降模机台重新补回 */
    private Set<String> reducedContinuationGroupKeySet = new LinkedHashSet<>();
    /**
     * 续作降模下机机台对应的前物料 SKU 快照。
     * <p>第一层 key=机台编码，第二层 key=降模前物料编码，value=实际触发降模的来源 SKU。
     * 该快照只记录续作降模规则实际选出的下机机台，不包含窗口无计划、首日无计划、收尾小余量跳过等
     * 其他释放原因。S4.6 使用来源 SKU 精确读取“物料+产品状态”的本次排程剩余账本，判断前物料能否在
     * 本次排程中收尾；该快照本身不参与选机、排量或余量扣减。</p>
     */
    private Map<String, Map<String, SkuScheduleDTO>> reducedContinuationMachineBeforeSkuMap =
            new LinkedHashMap<String, Map<String, SkuScheduleDTO>>(8);
    /** 已按降模规则只保留单台续作机台的分组集合，避免后续补偿链路把已释放机台重新补回 */
    private Set<String> singleMachineReducedContinuationGroupKeySet = new LinkedHashSet<>();
    /** 续作收尾小余量释放后可优先进入换活字块匹配的机台集合 */
    private Set<String> typeBlockReleasedContinuousMachineCodeSet = new LinkedHashSet<>();
    /** 首日无计划但后续有计划的续作释放机台集合，供S4.4/S4.5稳定识别占位结果，不受后续账本扣减影响 */
    private Set<String> firstDayNoPlanReleasedContinuousMachineCodeSet = new LinkedHashSet<>();
    /**
     * 续作停产保机日期，key=机台编码，value=该机台计划量必须为0但仍保持原SKU和模具占用的业务日集合。
     * <p>该状态只属于本次排程运行态，不代表机台释放，也不参与续作降模END_TYPE判断。</p>
     */
    private Map<String, Set<LocalDate>> continuousStopHoldDateMap =
            new LinkedHashMap<String, Set<LocalDate>>(8);
    /**
     * 当前仍处于停产保机占用的机台集合。
     * <p>历史保机日期保留在continuousStopHoldDateMap；计划恢复生产或后续真正降模后会从本集合移除，
     * 使候选过滤只约束当前仍被原SKU占用的机台。</p>
     */
    private Set<String> activeContinuousStopHoldMachineCodeSet = new LinkedHashSet<String>(8);
    /**
     * 曾停产保机、但后续业务日重新判断后已真正降模的机台集合。
     * <p>历史保机日期仍需保留用于班次清零，但这些机台在真实释放边界后可以重新进入后续资源候选。</p>
     */
    private Set<String> releasedContinuousStopHoldMachineCodeSet = new LinkedHashSet<String>(8);
    /**
     * 真正降模机台的最后允许生产班次，key=机台编码，value=本窗口最后允许保留正计划量的班次序号。
     * <p>该边界来自真实降模决策结果，供日标准收敛、收尾补量和尾量归集后统一清理释放边界后的误补量。</p>
     */
    private Map<String, Integer> continuousReducedMachineReleaseBoundaryShiftIndexMap =
            new LinkedHashMap<String, Integer>(8);
    /** 运行态结果来源SKU映射，使用对象身份避免结果行可变字段影响Map命中，供后置校验回到原始日计划账本 */
    private Map<LhScheduleResult, SkuScheduleDTO> scheduleResultSourceSkuMap = new IdentityHashMap<>();
    /**
     * 前日交替计划反选成功的机台集合。
     * <p>key=物料+产品状态复合键，value=本阶段已经成功占用的机台编码。
     * 后续普通新增排产只排除同一SKU重复选择这些机台，不会永久锁定机台给其他SKU。</p>
     */
    private Map<String, Set<String>> historicalReverseSelectedMachineCodeMap =
            new LinkedHashMap<String, Set<String>>(8);
    /**
     * 前日交替计划反选成功且必须保留的结果集合。
     * <p>使用对象身份保存，防止同SKU多机台尾量收口再次搬空或删除反选结果，
     * 同时不改变其他普通新增结果的收口规则。</p>
     */
    private Set<LhScheduleResult> historicalReverseProtectedResultSet =
            Collections.newSetFromMap(new IdentityHashMap<LhScheduleResult, Boolean>());
    /** S4.5新增链路模具资源运行态，只限制新增机台数量，不反向裁剪S4.4续作结果 */
    private MouldResourceContext mouldResourceContext;

    // ========== 机台分配状态 ==========

    /** 机台排程DTO Map, key=machineCode */
    private Map<String, MachineScheduleDTO> machineScheduleMap = new LinkedHashMap<>();
    /** 机台初始状态快照，供换模计划和回归校验使用 */
    private Map<String, MachineScheduleDTO> initialMachineScheduleMap = new LinkedHashMap<>();
    /** 机台剩余产能Map, key=machineCode, value=各班次剩余产能 */
    private Map<String, int[]> machineShiftCapacityMap = new LinkedHashMap<>();
    /** 班次运行态，key=班次索引 1～N（N≤8），承载开停产、工作日历和历史班次保护后的可排状态 */
    private Map<Integer, ShiftRuntimeState> shiftRuntimeStateMap = new LinkedHashMap<>(8);
    /** 本次排程解析后的班次窗口 */
    private List<LhShiftConfigVO> scheduleWindowShifts = new ArrayList<>();
    /** 班次排产管控，key=班次索引 */
    private Map<Integer, ShiftProductionControlDTO> shiftProductionControlMap = new LinkedHashMap<>(8);
    /** 机台已分配SKU Map, key=machineCode, value=已分配的排程结果 */
    private Map<String, List<LhScheduleResult>> machineAssignmentMap = new LinkedHashMap<>();
    /** 定点机台挤量预留切换开始时间, key=machineCode；用于续作非收尾给后续定点新增留出换模窗口 */
    private Map<String, Date> specifyMachineReservedSwitchStartTimeMap = new LinkedHashMap<>();
    /** 定点机台挤量预留物料编码, key=machineCode */
    private Map<String, String> specifyMachineReservedMaterialMap = new LinkedHashMap<>();
    /** 每日模具切换计数, key=dateString, value=[早班切换数, 中班切换数] */
    private Map<String, int[]> dailyMouldChangeCountMap = new LinkedHashMap<>();
    /** 同胎胚换模班次占用, key=胎胚编码, value=已安排换模班次索引集合 */
    private Map<String, Set<Integer>> greenTireChangeoverShiftMap = new LinkedHashMap<>();
    /** 本月待排物料胎胚共用关系, key=materialCode, value=true表示与其他待排物料共用胎胚 */
    private Map<String, Boolean> materialSharedEmbryoMap = new LinkedHashMap<>();
    /** 当前仍有效参与排产的胎胚SKU集合, key=embryoCode, value=有效物料状态复合键列表 */
    private Map<String, List<String>> activeEmbryoSkuMap = new LinkedHashMap<>();
    /** 共用胎胚剔除零余量SKU后动态转为单胎胚收尾的物料编码集合 */
    private Set<String> dynamicSingleEmbryoEndingMaterialSet = new LinkedHashSet<>();
    /** 换模/换活字块日上限阻塞原因, key=materialCode, value=未排原因 */
    private Map<String, String> mouldChangeLimitBlockedReasonMap = new LinkedHashMap<>();
    /** 每日首检计数, key=dateString, value=[早班首检数, 中班首检数] */
    private Map<String, int[]> dailyFirstInspectionCountMap = new LinkedHashMap<>();
    /** 班次首检数量顺序计数, key=业务日期#班次索引, value=已计入首检数量的机台数 */
    private Map<String, Integer> shiftFirstInspectionCountMap = new LinkedHashMap<>(8);
    /** 每日精度保养计数, key=dateString, value=已安排保养机台数 */
    private Map<String, Integer> dailyMaintenanceCountMap = new LinkedHashMap<>();
    /**
     * 每日已占用保养额度的物理机台集合，key=dateString，value=物理机台编码集合。
     * <p>单控 L/R 两侧属于同一物理机台，只能在集合中登记一次；清除运行时保养窗口时也通过
     * 该集合释放额度，避免仅递减数字导致重复占用或误释放其他机台额度。</p>
     */
    private Map<String, Set<String>> dailyMaintenancePhysicalMachineSetMap = new LinkedHashMap<>();
    /**
     * 已记录的精度保养就绪时间顺延日志键集合。
     * <p>键由“机台 + 原就绪时间 + 最早开产时间”组成。同一候选在选机、产能预演和最终排产阶段
     * 可能被重复计算，本集合只抑制完全相同的过程日志，不改变任何机台时间和排程判断。</p>
     */
    private Set<String> maintenanceResumeDelayLogKeySet = new LinkedHashSet<>();

    // ========== 排程输出结果 ==========

    /** 硫化排程结果列表 */
    private List<LhScheduleResult> scheduleResultList = new ArrayList<>();
    /** 硫化未排结果列表 */
    private List<LhUnscheduledResult> unscheduledResultList = new ArrayList<>();
    /** 模具交替计划列表 */
    private List<LhMouldChangePlan> mouldChangePlanList = new ArrayList<>();
    /** 排程日志列表 */
    private List<LhScheduleProcessLog> scheduleLogList = new ArrayList<>();
    /** 特殊材料硫化机置换备注Map，key=被置换机台编码，value=置换备注（供S4.6生成模具交替计划时追加备注） */
    private Map<String, String> substitutionRemarkMap;
    /** 全量SKU排程信息索引Map，key=materialCode_productStatus，供后置阶段精确查找来源SKU */
    private Map<String, SkuScheduleDTO> allSkuScheduleDtoMap = new LinkedHashMap<>();
    /** SKU减量清单索引集合，key=year+SEP+month+SEP+materialCode+SEP+productStatus（归一化）。S4.2批量加载，S4.3归集后统一过滤命中SKU */
    private Set<String> skuDecrementKeySet = new HashSet<>();
    /** 已处理减量命中SKU去重集合，key=materialCode+SEP+productStatus+SEP+yearMonth，保证同一SKU多入口只写一次未排结果 */
    private Set<String> decrementHandledSkuKeySet = new HashSet<>();

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
     * 累加结构计划硫化机台数。
     *
     * @param productionDate 业务日期
     * @param structureName 产品结构
     * @param machineCount 计划硫化机台数
     */
    public void addStructurePlanMachineCount(LocalDate productionDate, String structureName, int machineCount) {
        if (Objects.isNull(productionDate) || StringUtils.isEmpty(structureName)) {
            return;
        }
        Map<String, Integer> structureMap = structurePlanMachineCountMap.computeIfAbsent(
                productionDate, key -> new LinkedHashMap<String, Integer>(8));
        Integer oldCount = structureMap.get(structureName);
        structureMap.put(structureName, Math.max(0, Objects.isNull(oldCount) ? 0 : oldCount)
                + Math.max(0, machineCount));
    }

    /**
     * 获取指定业务日、指定结构的计划硫化机台数。
     *
     * @param productionDate 业务日期
     * @param structureName 产品结构
     * @return 计划硫化机台数
     */
    public int getStructurePlanMachineCount(LocalDate productionDate, String structureName) {
        if (Objects.isNull(productionDate) || StringUtils.isEmpty(structureName)
                || CollectionUtils.isEmpty(structurePlanMachineCountMap)) {
            return 0;
        }
        Map<String, Integer> structureMap = structurePlanMachineCountMap.get(productionDate);
        if (CollectionUtils.isEmpty(structureMap)) {
            return 0;
        }
        Integer machineCount = structureMap.get(structureName);
        return Objects.isNull(machineCount) ? 0 : Math.max(0, machineCount);
    }

    /**
     * 清空结构/SKU已排机台运行态。
     */
    public void clearScheduledMachineCountMaps() {
        structureScheduledMachineCodeMap.clear();
        skuScheduledMachineCodeMap.clear();
    }

    /**
     * 登记已排硫化机台。
     * <p>结构与 SKU 均按“业务日 + 机台编码”去重，避免同一机台多个班次重复计数。</p>
     *
     * @param productionDate 业务日期
     * @param structureName 产品结构
     * @param materialCode SKU物料编码
     * @param productStatus 产品状态
     * @param machineCode 机台编码
     */
    public void recordScheduledMachine(LocalDate productionDate,
                                       String structureName,
                                       String materialCode,
                                       String productStatus,
                                       String machineCode) {
        if (Objects.isNull(productionDate) || StringUtils.isEmpty(machineCode)) {
            return;
        }
        if (StringUtils.isNotEmpty(structureName)) {
            recordMachine(structureScheduledMachineCodeMap, productionDate, structureName, machineCode);
        }
        if (StringUtils.isNotEmpty(materialCode)) {
            String skuKey = MonthPlanDateResolver.buildMaterialStatusKey(materialCode, productStatus);
            recordMachine(skuScheduledMachineCodeMap, productionDate, skuKey, machineCode);
        }
    }

    /**
     * 获取指定业务日、指定结构的已排机台数。
     *
     * @param productionDate 业务日期
     * @param structureName 产品结构
     * @return 已排机台数
     */
    public int getStructureScheduledMachineCount(LocalDate productionDate, String structureName) {
        return getScheduledMachineCount(structureScheduledMachineCodeMap, productionDate, structureName);
    }

    /**
     * 获取指定业务日、指定 SKU 的已排机台数。
     *
     * @param productionDate 业务日期
     * @param materialCode SKU物料编码
     * @param productStatus 产品状态
     * @return 已排机台数
     */
    public int getSkuScheduledMachineCount(LocalDate productionDate,
                                           String materialCode,
                                           String productStatus) {
        // 历史交替计划没有产品状态，项目统一口径要求空状态按正规S归一化。
        String normalizedProductStatus = StringUtils.isEmpty(productStatus)
                ? FORMAL_PRODUCT_STATUS : productStatus;
        String skuKey = MonthPlanDateResolver.buildMaterialStatusKey(
                materialCode, normalizedProductStatus);
        return getScheduledMachineCount(skuScheduledMachineCodeMap, productionDate, skuKey);
    }

    /**
     * 登记前日交替计划反选成功的机台。
     *
     * @param materialCode 物料编码
     * @param productStatus 产品状态
     * @param machineCode 机台编码
     */
    public void registerHistoricalReverseSelectedMachine(String materialCode,
                                                         String productStatus,
                                                         String machineCode) {
        if (StringUtils.isEmpty(materialCode) || StringUtils.isEmpty(machineCode)) {
            return;
        }
        String normalizedProductStatus = StringUtils.isEmpty(productStatus)
                ? FORMAL_PRODUCT_STATUS : productStatus;
        String skuKey = MonthPlanDateResolver.buildMaterialStatusKey(
                materialCode, normalizedProductStatus);
        historicalReverseSelectedMachineCodeMap
                .computeIfAbsent(skuKey, key -> new LinkedHashSet<String>(2))
                .add(machineCode);
    }

    /**
     * 获取前日交替计划已为指定SKU反选成功的机台。
     *
     * @param materialCode 物料编码
     * @param productStatus 产品状态
     * @return 已成功机台编码集合；没有时返回空集合
     */
    public Set<String> getHistoricalReverseSelectedMachineCodes(String materialCode,
                                                                String productStatus) {
        if (StringUtils.isEmpty(materialCode)) {
            return Collections.emptySet();
        }
        String skuKey = MonthPlanDateResolver.buildMaterialStatusKey(materialCode, productStatus);
        Set<String> machineCodeSet = historicalReverseSelectedMachineCodeMap.get(skuKey);
        return CollectionUtils.isEmpty(machineCodeSet)
                ? Collections.<String>emptySet() : machineCodeSet;
    }

    /**
     * 撤销后置资源裁剪后已经失效的反选机台登记。
     *
     * @param materialCode 物料编码
     * @param productStatus 产品状态
     * @param machineCode 历史指定机台编码
     */
    public void unregisterHistoricalReverseSelectedMachine(String materialCode,
                                                           String productStatus,
                                                           String machineCode) {
        if (StringUtils.isEmpty(materialCode) || StringUtils.isEmpty(machineCode)) {
            return;
        }
        String normalizedProductStatus = StringUtils.isEmpty(productStatus)
                ? FORMAL_PRODUCT_STATUS : productStatus;
        String skuKey = MonthPlanDateResolver.buildMaterialStatusKey(
                materialCode, normalizedProductStatus);
        Set<String> machineCodeSet = historicalReverseSelectedMachineCodeMap.get(skuKey);
        if (CollectionUtils.isEmpty(machineCodeSet)) {
            return;
        }
        machineCodeSet.remove(machineCode);
        if (machineCodeSet.isEmpty()) {
            historicalReverseSelectedMachineCodeMap.remove(skuKey);
        }
    }

    /**
     * 保护前日交替计划反选成功的排程结果。
     *
     * @param result 反选成功结果
     */
    public void protectHistoricalReverseResult(LhScheduleResult result) {
        if (Objects.nonNull(result)) {
            historicalReverseProtectedResultSet.add(result);
        }
    }

    /**
     * 判断结果是否为前日交替计划反选成功结果。
     *
     * @param result 排程结果
     * @return true-需要保持原机台关系；false-普通结果
     */
    public boolean isHistoricalReverseProtectedResult(LhScheduleResult result) {
        return Objects.nonNull(result) && historicalReverseProtectedResultSet.contains(result);
    }

    /**
     * 取消后置资源裁剪后已经失效结果的反选保护。
     *
     * @param result 已失效排程结果
     */
    public void unprotectHistoricalReverseResult(LhScheduleResult result) {
        if (Objects.nonNull(result)) {
            historicalReverseProtectedResultSet.remove(result);
        }
    }

    /**
     * 登记指定维度的机台编码。
     *
     * @param targetMap 目标统计Map
     * @param productionDate 业务日期
     * @param dimensionKey 结构或SKU编码
     * @param machineCode 机台编码
     */
    private void recordMachine(Map<LocalDate, Map<String, Set<String>>> targetMap,
                               LocalDate productionDate,
                               String dimensionKey,
                               String machineCode) {
        Map<String, Set<String>> dateMap = targetMap.computeIfAbsent(
                productionDate, key -> new LinkedHashMap<String, Set<String>>(8));
        Set<String> machineCodeSet = dateMap.computeIfAbsent(
                dimensionKey, key -> new LinkedHashSet<String>(4));
        machineCodeSet.add(machineCode);
    }

    /**
     * 获取指定维度已排机台数。
     *
     * @param sourceMap 来源统计Map
     * @param productionDate 业务日期
     * @param dimensionKey 结构或SKU编码
     * @return 已排机台数
     */
    private int getScheduledMachineCount(Map<LocalDate, Map<String, Set<String>>> sourceMap,
                                         LocalDate productionDate,
                                         String dimensionKey) {
        if (Objects.isNull(productionDate) || StringUtils.isEmpty(dimensionKey)
                || CollectionUtils.isEmpty(sourceMap)) {
            return 0;
        }
        Map<String, Set<String>> dateMap = sourceMap.get(productionDate);
        if (CollectionUtils.isEmpty(dateMap)) {
            return 0;
        }
        Set<String> machineCodeSet = dateMap.get(dimensionKey);
        return CollectionUtils.isEmpty(machineCodeSet) ? 0 : machineCodeSet.size();
    }

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
     * 登记续作停产保机业务日。
     *
     * @param machineCode 机台编码
     * @param productionDate 业务日期
     */
    public void registerContinuousStopHoldDate(String machineCode, LocalDate productionDate) {
        if (StringUtils.isEmpty(machineCode) || Objects.isNull(productionDate)) {
            return;
        }
        continuousStopHoldDateMap
                .computeIfAbsent(machineCode, key -> new LinkedHashSet<LocalDate>(4))
                .add(productionDate);
        activeContinuousStopHoldMachineCodeSet.add(machineCode);
        releasedContinuousStopHoldMachineCodeSet.remove(machineCode);
    }

    /**
     * 判断机台在指定业务日是否处于停产保机状态。
     *
     * @param machineCode 机台编码
     * @param productionDate 业务日期
     * @return true-该日停产保机；false-不是
     */
    public boolean isContinuousStopHoldDate(String machineCode, LocalDate productionDate) {
        if (StringUtils.isEmpty(machineCode) || Objects.isNull(productionDate)
                || CollectionUtils.isEmpty(continuousStopHoldDateMap)) {
            return false;
        }
        Set<LocalDate> holdDateSet = continuousStopHoldDateMap.get(machineCode);
        return !CollectionUtils.isEmpty(holdDateSet) && holdDateSet.contains(productionDate);
    }

    /**
     * 判断机台在本次排程窗口内是否存在停产保机占用。
     *
     * @param machineCode 机台编码
     * @return true-存在停产保机日期；false-不存在
     */
    public boolean isContinuousStopHoldMachine(String machineCode) {
        if (StringUtils.isEmpty(machineCode)
                || CollectionUtils.isEmpty(activeContinuousStopHoldMachineCodeSet)) {
            return false;
        }
        return activeContinuousStopHoldMachineCodeSet.contains(machineCode);
    }

    /**
     * 标记停产保机机台已按原续作SKU恢复生产。
     *
     * @param machineCode 机台编码
     */
    public void markContinuousStopHoldMachineProductionResumed(String machineCode) {
        if (StringUtils.isEmpty(machineCode)) {
            return;
        }
        activeContinuousStopHoldMachineCodeSet.remove(machineCode);
    }

    /**
     * 标记曾停产保机的机台已在后续业务日真正降模释放。
     *
     * @param machineCode 机台编码
     */
    public void markContinuousStopHoldMachineReleased(String machineCode) {
        if (StringUtils.isEmpty(machineCode)
                || CollectionUtils.isEmpty(continuousStopHoldDateMap.get(machineCode))) {
            return;
        }
        activeContinuousStopHoldMachineCodeSet.remove(machineCode);
        releasedContinuousStopHoldMachineCodeSet.add(machineCode);
    }

    /**
     * 登记真正降模机台最后允许生产的班次序号。
     *
     * @param machineCode 机台编码
     * @param shiftIndex 最后允许生产班次序号；0表示本窗口全部班次均已释放
     */
    public void registerContinuousReducedMachineReleaseBoundary(String machineCode, int shiftIndex) {
        if (StringUtils.isEmpty(machineCode)) {
            return;
        }
        continuousReducedMachineReleaseBoundaryShiftIndexMap.put(machineCode, Math.max(0, shiftIndex));
    }

    /**
     * 获取真正降模机台最后允许生产的班次序号。
     *
     * @param machineCode 机台编码
     * @return 最后允许生产班次序号；未登记真正降模边界时返回null
     */
    public Integer getContinuousReducedMachineReleaseBoundaryShiftIndex(String machineCode) {
        if (StringUtils.isEmpty(machineCode)
                || CollectionUtils.isEmpty(continuousReducedMachineReleaseBoundaryShiftIndexMap)) {
            return null;
        }
        return continuousReducedMachineReleaseBoundaryShiftIndexMap.get(machineCode);
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
     * 判断机台是否正被尚未完成排产的三天内收尾结构临时保护。
     * <p>一旦进入临时保护，任何后续SKU均不得提前选择该机台；待结构全部SKU处理完成后，
     * 再由最终最低机台数判断决定清除保护或延迟至结构最晚班次结束。若窗口末班生产机台数
     * 已达到最低值，则提前确认不命中并清除保护，保持原机台释放逻辑。</p>
     *
     * @param machineCode 运行态机台编码
     * @return true-机台处于临时保护，当前SKU不得选择；false-不受临时保护限制
     */
    public boolean isEndingStructureProtectedMachine(String machineCode) {
        if (StringUtils.isEmpty(machineCode) || CollectionUtils.isEmpty(endingStructureProtectedMachineMap)) {
            return false;
        }
        return StringUtils.isNotEmpty(endingStructureProtectedMachineMap.get(machineCode));
    }

    /**
     * 判断机台是否已命中结构最低机台数保留规则。
     *
     * @param machineCode 运行态机台编码
     * @return true-命中规则并已登记统一释放时间；false-未命中
     */
    public boolean isStructureMinMachineRetained(String machineCode) {
        return StringUtils.isNotEmpty(machineCode)
                && !CollectionUtils.isEmpty(structureMinMachineRetentionEndTimeMap)
                && structureMinMachineRetentionEndTimeMap.containsKey(machineCode);
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

    /**
     * 按物料编码 + 产品状态从SKU与示方书关系中查找（支持降级匹配）。
     * <p>降级规则：正规(S)→量试(T)→试制(X)；量试(T)→试制(X)；试制(X)不降级。</p>
     * <p>用于排产结果写入前回写文字/硫化/制造示方书号，未命中时返回 null，
     * 由调用方决定是否回退到其他来源或置空。</p>
     *
     * @param materialCode 物料编码
     * @param productStatus 产品状态（S-正规、T-量试、X-试制）
     * @return SKU与示方书关系，未命中返回 null
     */
    public MdmSkuConstructionRef findSkuConstructionRef(String materialCode, String productStatus) {
        return SkuConstructionRefResolverUtil.resolveCuringRecipeRef(
                materialCode, productStatus, skuConstructionRefCompositeKeyMap);
    }

}
