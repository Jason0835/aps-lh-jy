package com.zlt.aps.lh.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zlt.aps.cx.api.domain.entity.CxStock;
import com.zlt.aps.lh.api.constant.LhScheduleConstant;
import com.zlt.aps.lh.api.constant.LhScheduleParamConstant;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.api.domain.entity.LhMachineInfo;
import com.zlt.aps.lh.api.domain.entity.LhMouldCleanPlan;
import com.zlt.aps.lh.api.domain.entity.LhMouldChangePlan;
import com.zlt.aps.lh.api.domain.entity.LhSpecifyMachine;
import com.zlt.aps.lh.api.domain.entity.LhSpecialMaterialBom;
import com.zlt.aps.lh.api.enums.DeleteFlagEnum;
import com.zlt.aps.lh.api.enums.LhSpecialMaterialCategoryEnum;
import com.zlt.aps.lh.api.enums.ScheduleStepEnum;
import com.zlt.aps.lh.handler.ScheduleAdjustHandler;
import com.zlt.aps.lh.mapper.FactoryMonthPlanProductionFinalResultMapper;
import com.zlt.aps.lh.mapper.MdmSkuScheduleCategoryMapper;
import com.zlt.aps.lh.mapper.MpFactoryProductionVersionMapper;
import com.zlt.aps.lh.mapper.LhDayFinishQtyMapper;
import com.zlt.aps.lh.mapper.LhScheFinishQtyMapper;
import com.zlt.aps.lh.mapper.LhMachineInfoMapper;
import com.zlt.aps.lh.mapper.LhMouldCleanPlanMapper;
import com.zlt.aps.lh.mapper.LhMouldChangePlanEntityMapper;
import com.zlt.aps.lh.mapper.LhScheduleResultMapper;
import com.zlt.aps.lh.mapper.LhSpecifyMachineMapper;
import com.zlt.aps.lh.mapper.LhPrecisionPlanMapper;
import com.zlt.aps.lh.mapper.MdmDevicePlanShutMapper;
import com.zlt.aps.lh.mapper.LhMachineOnlineInfoMapper;
import com.zlt.aps.lh.mapper.LhRepairCapsuleMapper;
import com.zlt.aps.lh.mapper.MdmCapsuleChuckMapper;
import com.zlt.aps.lh.mapper.MdmMaterialInfoMapper;
import com.zlt.aps.lh.mapper.MdmModelInfoMapper;
import com.zlt.aps.lh.mapper.MdmSkuConstructionRefMapper;
import com.zlt.aps.lh.mapper.MdmSkuLhCapacityMapper;
import com.zlt.aps.lh.mapper.MdmSkuMouldRelMapper;
import com.zlt.aps.lh.mapper.MdmWorkCalendarMapper;
import com.zlt.aps.lh.mapper.CxStockMapper;
import com.zlt.aps.lh.mapper.LhSpecialMaterialBomEntityMapper;
import com.zlt.aps.lh.mapper.MpMonthPlanStatisticsMapper;
import com.zlt.aps.mdm.api.domain.entity.MdmSkuScheduleCategory;
import com.zlt.aps.mp.api.domain.entity.MpMonthPlanStatistics;
import com.zlt.aps.lh.exception.ScheduleDomainExceptionHelper;
import com.zlt.aps.lh.exception.ScheduleErrorCode;
import com.zlt.aps.lh.service.ILhBaseDataService;
import com.zlt.aps.lh.util.LhScheduleTimeUtil;
import com.zlt.aps.lh.util.MachineStatusUtil;
import com.zlt.aps.lh.component.MonthPlanDateResolver;
import com.zlt.aps.lh.util.MonthPlanDayQtyUtil;
import com.zlt.aps.lh.util.MonthPlanStatisticsDayUtil;
import com.zlt.aps.lh.api.domain.entity.LhPrecisionPlan;
import com.zlt.aps.mp.api.domain.entity.MdmCapsuleChuck;
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
import com.zlt.aps.mp.api.domain.entity.MpFactoryProductionVersion;
import com.zlt.aps.lh.api.domain.entity.LhDayFinishQty;
import com.zlt.aps.lh.api.domain.entity.LhScheduleResult;
import com.zlt.aps.lh.api.domain.entity.LhScheFinishQty;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.function.IntSupplier;
import java.util.stream.Collectors;

/**
 * 硫化排程基础数据服务实现
 * <p>负责加载排程所需的所有基础数据到上下文</p>
 *
 * @author APS
 */
@Slf4j
@Service
public class LhBaseDataServiceImpl implements ILhBaseDataService {

    /** 排产版本已定稿（与 MpFactoryProductionVersion.isFinal 一致） */
    private static final String PRODUCTION_VERSION_IS_FINAL = "1";

    /** 查询最新排产版本时返回前两条，用于判断是否存在多条数据 */
    private static final String FINAL_PRODUCTION_VERSION_LIMIT_TWO = "LIMIT 2";

    /** 主销产品排产类型（与 t_mdm_sku_schedule_category.SCHEDULE_TYPE='01' 一致） */
    private static final String MAIN_PRODUCT_SCHEDULE_TYPE = "01";

    /** 非主销合并胎胚的收尾余量阈值：胎胚余量 <= 该值视为收尾 */
    private static final int NON_MAIN_PRODUCT_ENDING_THRESHOLD = 2;

    /** 主销合并胎胚的收尾余量阈值：胎胚余量 <= 该值视为收尾 */
    private static final int MAIN_PRODUCT_ENDING_THRESHOLD = 0;

    /** 胎胚收尾标识：收尾 */
    private static final int EMBRYO_ENDING_FLAG_YES = 1;

    /** 胎胚收尾标识：非收尾 */
    private static final int EMBRYO_ENDING_FLAG_NO = 0;

    @Resource
    private FactoryMonthPlanProductionFinalResultMapper monthPlanMapper;

    @Resource
    private MpFactoryProductionVersionMapper mpFactoryProductionVersionMapper;

    @Resource
    private MpMonthPlanStatisticsMapper monthPlanStatisticsMapper;

    @Resource
    private MdmWorkCalendarMapper workCalendarMapper;

    @Resource
    private MdmSkuLhCapacityMapper skuLhCapacityMapper;

    @Resource
    private MdmDevicePlanShutMapper devicePlanShutMapper;

    @Resource
    private MdmSkuMouldRelMapper skuMouldRelMapper;

    @Resource
    private MdmModelInfoMapper mdmModelInfoMapper;

    @Resource
    private LhMachineInfoMapper lhMachineInfoMapper;

    @Resource
    private LhMouldCleanPlanMapper lhMouldCleanPlanMapper;

    @Resource
    private LhDayFinishQtyMapper lhDayFinishQtyMapper;

    @Resource
    private LhScheFinishQtyMapper lhScheFinishQtyMapper;

    @Resource
    private MdmMaterialInfoMapper mdmMaterialInfoMapper;

    @Resource
    private MdmCapsuleChuckMapper mdmCapsuleChuckMapper;

    @Resource
    private LhMachineOnlineInfoMapper lhMachineOnlineInfoMapper;

    @Resource
    private LhSpecifyMachineMapper lhSpecifyMachineMapper;

    @Resource
    private LhRepairCapsuleMapper lhRepairCapsuleMapper;

    @Resource
    private LhPrecisionPlanMapper lhPrecisionPlanMapper;

    @Resource
    private LhScheduleResultMapper lhScheduleResultMapper;

    @Resource
    private LhMouldChangePlanEntityMapper lhMouldChangePlanMapper;

    @Resource
    private CxStockMapper cxStockMapper;

    @Resource
    private LhSpecialMaterialBomEntityMapper lhSpecialMaterialBomEntityMapper;

    @Resource
    private MdmSkuConstructionRefMapper skuConstructionRefMapper;

    @Resource
    private MdmSkuScheduleCategoryMapper skuScheduleCategoryMapper;

    @Resource
    private ScheduleAdjustHandler scheduleAdjustHandler;

    @Resource(name = "lhDataInitExecutor")
    private Executor lhDataInitExecutor;

    @Override
    public void loadAllBaseData(LhScheduleContext context) {
        long totalStartTime = System.currentTimeMillis();
        String factoryCode = context.getFactoryCode();
        Date scheduleDate = context.getScheduleDate();
        Date targetDate = context.getScheduleTargetDate();
        log.info("[DataInit] 开始全部初始化：factory={}, targetDate={}, scheduleDate={}, thread={}",
                factoryCode,
                LhScheduleTimeUtil.formatDate(targetDate),
                LhScheduleTimeUtil.formatDate(scheduleDate),
                Thread.currentThread().getName());

        // 加载排程时间范围：[startDate, endDate) 覆盖 T～T+(SCHEDULE_DAYS-1)，与连续排程窗口日历日一致
        Date startDate = LhScheduleTimeUtil.clearTime(scheduleDate);
        int scheduleDays = LhScheduleTimeUtil.getScheduleDays(context);
        Date endDate = LhScheduleTimeUtil.addDays(startDate, scheduleDays);
        Map<String, LocalDate> requiredMonthMap = resolveRequiredMonthMap(
                startDate, LhScheduleTimeUtil.addDays(endDate, 1));
        // 喷砂时间允许前移一天，工作日历与设备停机需覆盖 T-1；清洗计划仍按当前排程窗口加载。
        Date calendarControlStartDate = LhScheduleTimeUtil.addDays(startDate, -1);

        // 获取年月信息（按排程目标日取月计划所属年月）
        Calendar cal = Calendar.getInstance();
        cal.setTime(targetDate);
        int year = cal.get(Calendar.YEAR);
        int month = cal.get(Calendar.MONTH) + 1;

        // 1. 定稿排产版本是月计划、周程滚动调整等任务的前置条件，先单独同步完成。
        //    （若不先同步获取 productionVersion，后续月计划查询会因缺少版本号导致加载不准确。）
        waitForDataInitTasks(runDataInitTaskAsync("月生产计划版本",
                () -> loadFinalProductionVersions(context, factoryCode, requiredMonthMap, year, month),
                () -> StringUtils.isNotEmpty(context.getProductionVersion()) ? 1 : 0));
        if (context.isInterrupted()) {
            log.warn("[DataInit] 基础数据初始化中断：totalCost={}ms, reason={}",
                    System.currentTimeMillis() - totalStartTime, context.getInterruptReason());
            return;
        }

        Date previousDataDate = resolvePreviousDataDate(context, targetDate);
        int machineOnlineLookbackDays = context.getParamIntValue(
                LhScheduleParamConstant.MACHINE_ONLINE_LOOKBACK_DAYS,
                LhScheduleConstant.MACHINE_ONLINE_LOOKBACK_DAYS);

        // 2. 创建异步任务并建立任务间的依赖关系：
        //    - 月生产计划（monthPlanFuture）是特殊物料清单和胎胚库存的前置依赖，后者通过 thenCompose 串联。
        //    - 硫化机台信息（machineInfoFuture）是模具清洗计划的前置依赖，清洗计划需要根据已加载的机台列表过滤查询条件。
        //    - 机台信息与月计划无依赖关系，两者可并发加载。
        CompletableFuture<Void> monthPlanFuture = runDataInitTaskAsync("月生产计划",
                () -> loadMonthPlan(context, factoryCode, requiredMonthMap),
                () -> sizeOf(context.getMonthPlanList()));
        CompletableFuture<Void> monthPlanStatisticsFuture = runDataInitTaskAsync("月计划结构机台统计",
                () -> loadMonthPlanStatistics(context, factoryCode, requiredMonthMap, startDate,
                        LhScheduleTimeUtil.addDays(endDate, 1)),
                () -> sizeOf(context.getStructurePlanMachineCountMap()));
        CompletableFuture<Void> specialMaterialBomFuture = runAfterDataInitTask(monthPlanFuture, "特殊物料清单",
                () -> loadSpecialMaterialBom(context, factoryCode),
                () -> sizeOf(context.getSpecialMaterialBomList()));
        CompletableFuture<Void> embryoStockFuture = runAfterDataInitTask(monthPlanFuture, "胎胚实时库存",
                () -> loadEmbryoRealtimeStock(context, factoryCode, startDate),
                () -> sizeOf(context.getEmbryoRealtimeStockMap()));
        CompletableFuture<Void> monthFinishedQtyFuture = runAfterDataInitTask(monthPlanFuture, "月累计完成量",
                () -> loadMaterialMonthFinishedQty(context, factoryCode,
                        requiredMonthMap, LhScheduleTimeUtil.addDays(scheduleDate, -1), year, month),
                () -> sizeOf(context.getMaterialMonthFinishedQtyMap()));
        CompletableFuture<Void> skuMouldRelFuture = runAfterDataInitTask(monthPlanFuture, "SKU与模具关系",
                () -> loadSkuMouldRel(context, factoryCode),
                () -> sizeOf(context.getSkuMouldRelMap()));
        CompletableFuture<Void> modelInfoFuture = runAfterDataInitTask(skuMouldRelFuture, "模具台账",
                () -> loadModelInfo(context, factoryCode),
                () -> sizeOf(context.getModelInfoMap()));
        CompletableFuture<Void> machineInfoFuture = runDataInitTaskAsync("硫化机台信息",
                () -> loadMachineInfo(context, factoryCode),
                () -> sizeOf(context.getMachineInfoMap()));
        CompletableFuture<Void> cleaningPlanFuture = runAfterDataInitTask(machineInfoFuture, "模具清洗计划",
                () -> loadCleaningPlan(context, factoryCode, startDate, endDate),
                () -> sizeOf(context.getCleaningPlanList()));

        // 3. 等待所有无依赖的并行任务完成（含已通过 thenCompose 串联的依赖链）。
        //    使用 CompletableFuture.allOf().join() 实现屏障同步：
        //    - 任一任务抛出异常，join() 会透传 CompletionException，由 waitForDataInitTasks 统一解包。
        //    - 任务间的依赖通过 runAfterDataInitTask（thenCompose）保证执行顺序，
        //      因此此处只需等待顶层 Future 完成即可（底层依赖链会自动传递完成状态）。
        waitForDataInitTasks(
                monthPlanFuture,
                monthPlanStatisticsFuture,
                specialMaterialBomFuture,
                embryoStockFuture,
                runDataInitTaskAsync("工作日历",
                        () -> loadWorkCalendar(context, factoryCode, calendarControlStartDate, endDate),
                        () -> sizeOf(context.getWorkCalendarList())),
                runDataInitTaskAsync("SKU日硫化产能",
                        () -> loadSkuLhCapacity(context, factoryCode),
                        () -> sizeOf(context.getSkuLhCapacityMap())),
                runDataInitTaskAsync("设备停机计划",
                        () -> loadDevicePlanShut(context, factoryCode, calendarControlStartDate, endDate),
                        () -> sizeOf(context.getDevicePlanShutList())),
                skuMouldRelFuture,
                modelInfoFuture,
                machineInfoFuture,
                cleaningPlanFuture,
                runDataInitTaskAsync("前日物料日完成量",
                        () -> loadDayFinishQty(context, factoryCode, previousDataDate),
                        () -> sizeOf(context.getMaterialDayFinishedQtyMap())),
                monthFinishedQtyFuture,
                runDataInitTaskAsync("T日排程班次完成量",
                        () -> loadScheDayFinishQty(context, factoryCode, scheduleDate),
                        () -> sizeOf(context.getMaterialScheDayFinishQtyMap())),
                runDataInitTaskAsync("物料信息",
                        () -> loadMaterialInfo(context, factoryCode),
                        () -> sizeOf(context.getMaterialInfoMap())),
                runDataInitTaskAsync("胶囊卡盘分组",
                        () -> loadCapsuleChuck(context, factoryCode),
                        () -> sizeOf(context.getCapsuleSpecPeerMap()) + sizeOf(context.getCapsuleProSizePeerMap())),
                runDataInitTaskAsync("MES硫化在机信息",
                        () -> loadMachineOnlineInfo(context, factoryCode, startDate, machineOnlineLookbackDays),
                        () -> sizeOf(context.getMachineOnlineInfoMap())),
                runDataInitTaskAsync("硫化定点机台",
                        () -> loadSpecifyMachine(context, factoryCode),
                        () -> sizeOf(context.getSpecifyMachineMap())),
                runDataInitTaskAsync("硫化机胶囊已使用次数",
                        () -> loadCapsuleUsage(context, factoryCode),
                        () -> sizeOf(context.getCapsuleUsageMap())),
                runDataInitTaskAsync("设备保养计划",
                        () -> loadMaintenancePlan(context, factoryCode),
                        () -> sizeOf(context.getMaintenancePlanMap())),
                runDataInitTaskAsync("前日硫化排程结果",
                        () -> loadPreviousScheduleResults(context, factoryCode, targetDate),
                        () -> sizeOf(context.getPreviousScheduleResultList())),
                runDataInitTaskAsync("目标日前一日硫化排程结果",
                        () -> loadTargetPreviousScheduleResults(context, factoryCode, targetDate),
                        () -> sizeOf(context.getTargetPreviousScheduleResultList())),
                runDataInitTaskAsync("前日模具交替计划",
                        () -> loadPreviousMouldChangePlans(context, factoryCode, targetDate),
                        () -> sizeOf(context.getPreviousMouldChangePlanList())),
                runDataInitTaskAsync("SKU与示方书关系",
                        () -> loadSkuConstructionRef(context, factoryCode),
                        () -> sizeOf(context.getSkuConstructionRefMap())),
                runDataInitTaskAsync("硫化示方历史排程结果",
                        () -> loadHistoryCureFormulaResults(context, factoryCode, targetDate),
                        () -> sizeOf(context.getPreviousCureFormulaResultList()))
        );

        // 4. 胎胚收尾标识：依赖月计划、胎胚库存、月累计完成量、T日班次完成量、前日排程结果等均已就绪，
        //    故在屏障同步之后计算；以胎胚维度合并硫化余量并按主销参与情况判定收尾。
        this.loadEmbryoEndingFlagMap(context);

        if (context.isInterrupted()) {
            log.warn("基础数据加载中断, 工厂: {}, 目标日: {}, T日: {}, 原因: {}",
                    factoryCode, LhScheduleTimeUtil.formatDate(targetDate),
                    LhScheduleTimeUtil.formatDate(scheduleDate), context.getInterruptReason());
            return;
        }
        log.info("基础数据加载完成, 工厂: {}, 目标日: {}, T日: {}",
                factoryCode, LhScheduleTimeUtil.formatDate(targetDate), LhScheduleTimeUtil.formatDate(scheduleDate));
        log.info("[DataInit] 全部初始化完成：totalCost={}ms", System.currentTimeMillis() - totalStartTime);
    }

    /**
     * 异步执行基础数据初始化任务。
     * <p>使用 CompletableFuture.runAsync 将任务提交到 lhDataInitExecutor 线程池执行，
     * 实现多个数据源并行加载，缩短总初始化耗时。
     * 任务名 + 数据量统计 Supplier 用于日志监控，便于排查初始化瓶颈。</p>
     *
     * @param taskName      任务名称
     * @param task          初始化逻辑
     * @param countSupplier 数据量统计
     * @return 异步任务
     */
    private CompletableFuture<Void> runDataInitTaskAsync(String taskName, Runnable task, IntSupplier countSupplier) {
        return CompletableFuture.runAsync(() -> executeDataInitTask(taskName, task, countSupplier), lhDataInitExecutor);
    }

    /**
     * 在依赖任务完成后异步执行后续初始化任务。
     * <p>使用 thenCompose 实现异步任务的链式编排：当 dependency 正常完成后，
     * 自动提交后续 task 到同一线程池执行，保证 B 依赖 A 的执行顺序。
     * 注意：dependency 失败时后续任务不会执行，异常会沿 Future 链传播到 waitForDataInitTasks。</p>
     *
     * @param dependency    前置任务
     * @param taskName      任务名称
     * @param task          初始化逻辑
     * @param countSupplier 数据量统计
     * @return 异步任务
     */
    private CompletableFuture<Void> runAfterDataInitTask(CompletableFuture<Void> dependency, String taskName,
                                                          Runnable task, IntSupplier countSupplier) {
        return dependency.thenCompose(result -> runDataInitTaskAsync(taskName, task, countSupplier));
    }

    /**
     * 执行单个基础数据初始化任务并打印耗时。
     * <p>该方法在 lhDataInitExecutor 线程池的 worker 线程中执行（由 CompletableFuture.runAsync 调度），
     * 因此 Thread.currentThread().getName() 可用于监控线程池资源使用情况。
     * 任何运行时异常都会从 submit 的 Runnable 中透出，被 CompletableFuture 捕获并包装为 CompletionException，
     * 最终由 waitForDataInitTasks 统一处理。</p>
     *
     * @param taskName      任务名称
     * @param task          初始化逻辑
     * @param countSupplier 数据量统计
     */
    private void executeDataInitTask(String taskName, Runnable task, IntSupplier countSupplier) {
        long startTime = System.currentTimeMillis();
        String threadName = Thread.currentThread().getName();
        log.info("[DataInit] 开始初始化：task={}, thread={}", taskName, threadName);
        try {
            task.run();
            long cost = System.currentTimeMillis() - startTime;
            log.info("[DataInit] 完成初始化：task={}, count={}, cost={}ms, thread={}",
                    taskName, resolveDataInitTaskCount(taskName, countSupplier), cost, threadName);
        } catch (Throwable e) {
            long cost = System.currentTimeMillis() - startTime;
            log.error("[DataInit] 初始化失败：task={}, cost={}ms, thread={}, error={}",
                    taskName, cost, threadName, e.getMessage(), e);
            if (e instanceof RuntimeException) {
                throw (RuntimeException) e;
            }
            if (e instanceof Error) {
                throw (Error) e;
            }
            throw new IllegalStateException("基础数据初始化失败", e);
        }
    }

    /**
     * 等待基础数据初始化任务完成并透传真实异常。
     * <p>使用 CompletableFuture.allOf 聚合所有并行任务，join() 同步阻塞等待：
     * - 所有 Future 都正常完成则返回。
     * - 任一 Future 抛出异常，join() 抛出 CompletionException，通过 unwrapCompletionException 递归解包，
     * 透传业务异常（RuntimeException / Error），避免线程池包装异常被吞掉。
     * - 主线程在此阻塞，确保 loadAllBaseData 返回时所有基础数据已就绪。</p>
     *
     * @param futures 初始化任务集合
     */
    private void waitForDataInitTasks(CompletableFuture<?>... futures) {
        try {
            CompletableFuture.allOf(futures).join();
        } catch (CompletionException e) {
            Throwable cause = unwrapCompletionException(e);
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            throw new IllegalStateException("基础数据初始化失败", cause);
        }
    }

    /**
     * 获取任务数据量，避免统计日志影响初始化结果。
     *
     * @param taskName      任务名称
     * @param countSupplier 数据量统计
     * @return 数据量
     */
    private int resolveDataInitTaskCount(String taskName, IntSupplier countSupplier) {
        try {
            return countSupplier.getAsInt();
        } catch (RuntimeException e) {
            log.warn("[DataInit] 初始化数据量统计失败：task={}, error={}", taskName, e.getMessage());
            return 0;
        }
    }

    /**
     * 解包 CompletableFuture 包装异常。
     * <p>CompletableFuture.allOf().join() 抛出的 CompletionException 可能嵌套多层，
     * 需要递归解包直到找到真实的业务异常根因（如 SQLException、IllegalArgumentException 等），
     * 避免在日志中只看到 CompletionException 代理类而丢失原始错误信息。</p>
     *
     * @param throwable 异常
     * @return 根因异常
     */
    private Throwable unwrapCompletionException(Throwable throwable) {
        Throwable cause = throwable;
        while (cause instanceof CompletionException && Objects.nonNull(cause.getCause())) {
            cause = cause.getCause();
        }
        return cause;
    }

    /**
     * 统计集合大小。
     *
     * @param collection 集合
     * @return 数量
     */
    private int sizeOf(Collection<?> collection) {
        return CollectionUtils.isEmpty(collection) ? 0 : collection.size();
    }

    /**
     * 统计Map大小。
     *
     * @param map Map
     * @return 数量
     */
    private int sizeOf(Map<?, ?> map) {
        return CollectionUtils.isEmpty(map) ? 0 : map.size();
    }

    /**
     * 解析本次排程需要访问的月计划月份集合。
     * <p>范围覆盖 T～T+2 以及 T+3 后看日期，按自然月去重后用于批量加载月计划，避免策略层循环查库。</p>
     *
     * @param startDate 开始日期，包含当天
     * @param endDateExclusive 结束日期，不含当天
     * @return key=year_month，value=该月月初
     */
    private Map<String, LocalDate> resolveRequiredMonthMap(Date startDate, Date endDateExclusive) {
        Map<String, LocalDate> requiredMonthMap = new LinkedHashMap<String, LocalDate>(2);
        LocalDate startLocalDate = toLocalDate(startDate);
        LocalDate endExclusiveLocalDate = toLocalDate(endDateExclusive);
        if (Objects.isNull(startLocalDate) || Objects.isNull(endExclusiveLocalDate)
                || !startLocalDate.isBefore(endExclusiveLocalDate)) {
            return requiredMonthMap;
        }
        LocalDate cursor = startLocalDate;
        while (cursor.isBefore(endExclusiveLocalDate)) {
            LocalDate monthStartDate = cursor.withDayOfMonth(1);
            requiredMonthMap.putIfAbsent(MonthPlanDateResolver.buildYearMonthKey(
                    monthStartDate.getYear(), monthStartDate.getMonthValue()), monthStartDate);
            cursor = cursor.plusDays(1);
        }
        return requiredMonthMap;
    }

    /**
     * 加载月计划结构维度计划硫化机台数。
     * <p>提前生产规则只需要 T～T+2 窗口内 dayN.lhMachines，按 structureName 聚合 SUM 后缓存到上下文。</p>
     *
     * @param context 排程上下文
     * @param factoryCode 工厂编号
     * @param year 年份
     * @param month 月份
     * @param startDate 窗口开始日期
     * @param endDateExclusive 窗口结束日期，不含当天
     */
    private void loadMonthPlanStatistics(LhScheduleContext context,
                                         String factoryCode,
                                         Map<String, LocalDate> requiredMonthMap,
                                         Date startDate,
                                         Date endDateExclusive) {
        context.setStructurePlanMachineCountMap(new LinkedHashMap<LocalDate, Map<String, Integer>>(4));
        if (CollectionUtils.isEmpty(requiredMonthMap)) {
            return;
        }
        for (LocalDate monthStartDate : requiredMonthMap.values()) {
            loadMonthPlanStatistics(context, factoryCode, monthStartDate.getYear(), monthStartDate.getMonthValue(),
                    startDate, endDateExclusive);
        }
    }

    private void loadMonthPlanStatistics(LhScheduleContext context,
                                         String factoryCode,
                                         int year,
                                         int month,
                                         Date startDate,
                                         Date endDateExclusive) {
        String monthPlanVersion = resolveMonthPlanVersion(context, year, month);
        String productionVersion = resolveProductionVersion(context, year, month);
        if (StringUtils.isEmpty(monthPlanVersion) || StringUtils.isEmpty(productionVersion)) {
            log.warn("月计划结构机台统计跳过加载，需求版本或排产版本为空, factoryCode: {}, year: {}, month: {}, "
                            + "monthPlanVersion: {}, productionVersion: {}",
                    factoryCode, year, month, monthPlanVersion, productionVersion);
            return;
        }
        List<MpMonthPlanStatistics> statisticsList = monthPlanStatisticsMapper.selectList(
                new LambdaQueryWrapper<MpMonthPlanStatistics>()
                        .eq(MpMonthPlanStatistics::getFactoryCode, factoryCode)
                        .eq(MpMonthPlanStatistics::getYear, year)
                        .eq(MpMonthPlanStatistics::getMonth, month)
                        .eq(MpMonthPlanStatistics::getMonthPlanVersion, monthPlanVersion)
                        .eq(MpMonthPlanStatistics::getProductionVersion, productionVersion)
                        .eq(MpMonthPlanStatistics::getIsDelete, DeleteFlagEnum.NORMAL.getCode())
                        .and(wrapper -> wrapper.eq(MpMonthPlanStatistics::getTempFlag, "0")
                                .or().isNull(MpMonthPlanStatistics::getTempFlag)
                                .or().eq(MpMonthPlanStatistics::getTempFlag, "")));
        if (CollectionUtils.isEmpty(statisticsList)) {
            log.warn("月计划结构机台统计无数据，按空缓存继续排程, factoryCode: {}, year: {}, month: {}, "
                            + "monthPlanVersion: {}, productionVersion: {}",
                    factoryCode, year, month, monthPlanVersion, productionVersion);
            return;
        }
        LocalDate startLocalDate = toLocalDate(startDate);
        LocalDate endLocalDate = toLocalDate(endDateExclusive);
        for (MpMonthPlanStatistics row : statisticsList) {
            if (Objects.isNull(row) || StringUtils.isBlank(row.getStructureName())) {
                continue;
            }
            for (LocalDate productionDate = startLocalDate; productionDate.isBefore(endLocalDate);
                 productionDate = productionDate.plusDays(1)) {
                if (productionDate.getYear() != year || productionDate.getMonthValue() != month) {
                    continue;
                }
                int lhMachines;
                try {
                    lhMachines = MonthPlanStatisticsDayUtil.resolveLhMachines(row, productionDate);
                } catch (IllegalArgumentException e) {
                    // 月计划结构统计只用于提前生产机台数判断，非法JSON按0处理，不阻断排程主流程。
                    lhMachines = 0;
                    log.warn("月计划结构机台统计dayN解析失败，按0继续排程, factoryCode: {}, "
                                    + "monthPlanVersion: {}, productionVersion: {}, structureName: {}, "
                                    + "productionDate: {}, reason: {}",
                            factoryCode, monthPlanVersion, productionVersion, row.getStructureName(), productionDate,
                            e.getMessage());
                }
                context.addStructurePlanMachineCount(productionDate, row.getStructureName(), lhMachines);
            }
        }
        if (CollectionUtils.isEmpty(context.getStructurePlanMachineCountMap())) {
            log.warn("月计划结构机台统计无有效结构数据，按空缓存继续排程, factoryCode: {}, year: {}, month: {}, "
                            + "monthPlanVersion: {}, productionVersion: {}, rowCount: {}",
                    factoryCode, year, month, monthPlanVersion, productionVersion, statisticsList.size());
            return;
        }
        log.info("月计划结构机台统计加载完成, factoryCode: {}, year: {}, month: {}, monthPlanVersion: {}, "
                        + "productionVersion: {}, rowCount: {}, dateCount: {}",
                factoryCode, year, month, monthPlanVersion, productionVersion, statisticsList.size(),
                context.getStructurePlanMachineCountMap().size());
    }

    /**
     * 转换为本地日期。
     *
     * @param date 日期
     * @return 本地日期
     */
    private LocalDate toLocalDate(Date date) {
        if (Objects.isNull(date)) {
            return null;
        }
        return date.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
    }

    /**
     * 加载当前排程目标日的上一轮排程结果（用于硫化示方历史保护）。
     * 仅当 ENABLE_CURE_FORMULA_HISTORY_PROTECT = 1 时加载，结果放入
     * context.previousCureFormulaResultList，供 S4.6 保护逻辑使用。
     *
     * @param context     排程上下文
     * @param factoryCode 分厂编号
     * @param targetDate  排程目标日
     */
    private void loadHistoryCureFormulaResults(LhScheduleContext context, String factoryCode, Date targetDate) {
        if (!context.getScheduleConfig().isCureFormulaHistoryProtectEnabled()) {
            return;
        }
        List<LhScheduleResult> list = lhScheduleResultMapper.selectList(new LambdaQueryWrapper<LhScheduleResult>()
                .eq(LhScheduleResult::getFactoryCode, factoryCode)
                .eq(LhScheduleResult::getScheduleDate, targetDate)
                .eq(LhScheduleResult::getIsDelete, DeleteFlagEnum.NORMAL.getCode()));
        context.setPreviousCureFormulaResultList(list != null ? list : new ArrayList<>());
        log.info("硫化示方历史排程结果加载完成, 数量: {}, 日期: {}",
                context.getPreviousCureFormulaResultList().size(),
                LhScheduleTimeUtil.formatDate(targetDate));
    }

    /**
     * 加载前日硫化排程结果
     *
     * @param context     排程上下文
     * @param factoryCode 分厂编号
     * @param targetDate  排程目标日
     * @return
     */
    private void loadPreviousScheduleResults(LhScheduleContext context, String factoryCode, Date targetDate) {
        Date previousDate = resolvePreviousDataDate(context, targetDate);
        List<LhScheduleResult> list = lhScheduleResultMapper.selectList(new LambdaQueryWrapper<LhScheduleResult>()
                .eq(LhScheduleResult::getFactoryCode, factoryCode)
                .eq(LhScheduleResult::getScheduleDate, previousDate)
                .eq(LhScheduleResult::getIsDelete, DeleteFlagEnum.NORMAL.getCode()));
        if (list == null || list.isEmpty()) {
            log.info("未找到前日排程数据, 日期: {}", LhScheduleTimeUtil.formatDate(previousDate));
            context.setPreviousScheduleResultList(new ArrayList<>());
            return;
        }
        context.setPreviousScheduleResultList(list);
        log.info("前日排程基础数据加载完成, 数量: {}, 日期: {}", list.size(), LhScheduleTimeUtil.formatDate(previousDate));
    }

    /**
     * 加载业务目标日前一日硫化排程结果。
     * <p>强制重排下 {@code previousScheduleResultList} 服务于窗口T日前一日滚动衔接；
     * 新增历史欠产兜底判断需要按接口目标日前一日判断是否已排过，两者日期口径不能混用。</p>
     *
     * @param context 排程上下文
     * @param factoryCode 分厂编号
     * @param targetDate 排程目标日
     */
    private void loadTargetPreviousScheduleResults(LhScheduleContext context, String factoryCode, Date targetDate) {
        Date targetPreviousDate = LhScheduleTimeUtil.clearTime(LhScheduleTimeUtil.addDays(targetDate, -1));
        List<LhScheduleResult> list = lhScheduleResultMapper.selectList(new LambdaQueryWrapper<LhScheduleResult>()
                .eq(LhScheduleResult::getFactoryCode, factoryCode)
                .eq(LhScheduleResult::getScheduleDate, targetPreviousDate)
                .eq(LhScheduleResult::getIsDelete, DeleteFlagEnum.NORMAL.getCode()));
        context.setTargetPreviousScheduleResultList(list != null ? list : new ArrayList<>());
        log.info("目标日前一日排程结果加载完成, 数量: {}, 日期: {}",
                context.getTargetPreviousScheduleResultList().size(),
                LhScheduleTimeUtil.formatDate(targetPreviousDate));
    }

    /**
     * 加载前日模具交替计划。
     *
     * @param context 排程上下文
     * @param factoryCode 分厂编号
     * @param targetDate 排程目标日
     */
    private void loadPreviousMouldChangePlans(LhScheduleContext context, String factoryCode, Date targetDate) {
        Date previousDate = resolvePreviousDataDate(context, targetDate);
        List<LhMouldChangePlan> list = lhMouldChangePlanMapper.selectList(new LambdaQueryWrapper<LhMouldChangePlan>()
                .eq(LhMouldChangePlan::getFactoryCode, factoryCode)
                .eq(LhMouldChangePlan::getScheduleDate, previousDate)
                .eq(LhMouldChangePlan::getIsDelete, DeleteFlagEnum.NORMAL.getCode()));
        context.setPreviousMouldChangePlanList(list != null ? list : new ArrayList<>());
        log.info("前日模具交替计划加载完成, 数量: {}, 日期: {}",
                context.getPreviousMouldChangePlanList().size(), LhScheduleTimeUtil.formatDate(previousDate));
    }

    /**
     * 解析前日基础数据日期。
     *
     * @param context 排程上下文
     * @param targetDate 排程目标日
     * @return 前日基础数据日期
     */
    private Date resolvePreviousDataDate(LhScheduleContext context, Date targetDate) {
        // 强制重排从窗口起点T日重新计算，前日排程/换模基线需取T日前一日。
        if (context.getParamIntValue(LhScheduleParamConstant.FORCE_RESCHEDULE,
                LhScheduleConstant.FORCE_RESCHEDULE) == LhScheduleConstant.FORCE_RESCHEDULE_ENABLED) {
            return LhScheduleTimeUtil.clearTime(LhScheduleTimeUtil.addDays(context.getScheduleDate(), -1));
        }
        return LhScheduleTimeUtil.clearTime(LhScheduleTimeUtil.addDays(targetDate, -1));
    }

    /**
     * 加载定稿排产版本：工厂 + 年 + 月 + 已定稿且未删除；无数据则中断；多条时取更新时间最新一条（再按主键降序）
     *
     * @param context     排程上下文
     * @param factoryCode 分厂编号
     * @param year        年份
     * @param month       月份（1-12）
     */
    private void loadFinalProductionVersions(LhScheduleContext context,
                                             String factoryCode,
                                             Map<String, LocalDate> requiredMonthMap,
                                             int primaryYear,
                                             int primaryMonth) {
        Map<String, String> productionVersionMap = new LinkedHashMap<String, String>(4);
        Map<String, String> monthPlanVersionMap = new LinkedHashMap<String, String>(4);
        if (CollectionUtils.isEmpty(requiredMonthMap)) {
            loadFinalProductionVersion(context, factoryCode, primaryYear, primaryMonth);
            productionVersionMap.put(MonthPlanDateResolver.buildYearMonthKey(primaryYear, primaryMonth),
                    context.getProductionVersion());
            monthPlanVersionMap.put(MonthPlanDateResolver.buildYearMonthKey(primaryYear, primaryMonth),
                    context.getMonthPlanVersion());
            context.setProductionVersionByYearMonthMap(productionVersionMap);
            context.setMonthPlanVersionByYearMonthMap(monthPlanVersionMap);
            return;
        }
        for (LocalDate monthStartDate : requiredMonthMap.values()) {
            MpFactoryProductionVersion versionRow = resolveFinalProductionVersionRow(context, factoryCode,
                    monthStartDate.getYear(), monthStartDate.getMonthValue());
            if (context.isInterrupted()) {
                return;
            }
            String yearMonthKey = MonthPlanDateResolver.buildYearMonthKey(
                    monthStartDate.getYear(), monthStartDate.getMonthValue());
            productionVersionMap.put(yearMonthKey, versionRow.getProductionVersion());
            monthPlanVersionMap.put(yearMonthKey, versionRow.getMonthPlanVersion());
        }
        context.setProductionVersionByYearMonthMap(productionVersionMap);
        context.setMonthPlanVersionByYearMonthMap(monthPlanVersionMap);
        String primaryYearMonthKey = MonthPlanDateResolver.buildYearMonthKey(primaryYear, primaryMonth);
        String primaryProductionVersion = productionVersionMap.get(
                primaryYearMonthKey);
        if (StringUtils.isEmpty(primaryProductionVersion) && !productionVersionMap.isEmpty()) {
            primaryProductionVersion = productionVersionMap.values().iterator().next();
        }
        String primaryMonthPlanVersion = monthPlanVersionMap.get(primaryYearMonthKey);
        context.setProductionVersion(primaryProductionVersion);
        context.setMonthPlanVersion(primaryMonthPlanVersion);
        log.info("定稿排产版本加载完成, factoryCode: {}, requiredMonths: {}, primaryMonthPlanVersion: {}, "
                        + "primaryProductionVersion: {}",
                factoryCode, productionVersionMap.keySet(), primaryMonthPlanVersion, primaryProductionVersion);
    }

    private void loadFinalProductionVersion(LhScheduleContext context, String factoryCode, int year, int month) {
        MpFactoryProductionVersion versionRow = resolveFinalProductionVersionRow(context, factoryCode, year, month);
        if (context.isInterrupted() || Objects.isNull(versionRow)) {
            return;
        }
        context.setProductionVersion(versionRow.getProductionVersion());
        context.setMonthPlanVersion(versionRow.getMonthPlanVersion());
    }

    /**
     * 解析指定年月定稿版本记录。
     *
     * @param context 排程上下文
     * @param factoryCode 工厂编码
     * @param year 年份
     * @param month 月份
     * @return 定稿版本记录
     */
    private MpFactoryProductionVersion resolveFinalProductionVersionRow(LhScheduleContext context,
                                                                        String factoryCode,
                                                                        int year,
                                                                        int month) {
        // 仅查询前两条：第一条用于取值，第二条用于判断是否存在多条记录
        List<MpFactoryProductionVersion> list = mpFactoryProductionVersionMapper.selectList(
                wrapFinalProductionVersion(factoryCode, year, month)
                        .orderByDesc(MpFactoryProductionVersion::getUpdateTime)
                        .orderByDesc(MpFactoryProductionVersion::getId)
                        .last(FINAL_PRODUCTION_VERSION_LIMIT_TWO));
        String locationText = formatFactoryYearMonth(context.getFactoryDisplayName(), year, month);
        if (CollectionUtils.isEmpty(list)) {
            log.error("定稿排产版本无数据, 工厂: {}, 年: {}, 月: {}", factoryCode, year, month);
            interruptByDataIncomplete(context, String.format("%s 未找到定稿排产版本数据", locationText));
            return null;
        }
        if (list.size() > 1) {
            log.warn("定稿排产版本存在多条，已按更新时间最新取值, 工厂: {}, 年: {}, 月: {}",
                    factoryCode, year, month);
        }
        MpFactoryProductionVersion row = list.get(0);
        String pv = row.getProductionVersion();
        if (StringUtils.isEmpty(pv)) {
            log.error("定稿排产版本号为空, 工厂: {}, 年: {}, 月: {}, id: {}",
                    factoryCode, year, month, row.getId());
            interruptByDataIncomplete(context, String.format("%s 的定稿排产版本号为空", locationText));
            return null;
        }
        String monthPlanVersion = row.getMonthPlanVersion();
        if (StringUtils.isEmpty(monthPlanVersion)) {
            log.error("定稿需求版本号为空, 工厂: {}, 年: {}, 月: {}, id: {}",
                    factoryCode, year, month, row.getId());
            interruptByDataIncomplete(context, String.format("%s 的定稿需求版本号为空", locationText));
            return null;
        }
        log.debug("定稿版本加载完成, monthPlanVersion: {}, productionVersion: {}", monthPlanVersion, pv);
        return row;
    }

    /**
     * 获取指定年月的定稿排产版本。
     *
     * @param context 排程上下文
     * @param year 年份
     * @param month 月份
     * @return 排产版本
     */
    private String resolveProductionVersion(LhScheduleContext context, int year, int month) {
        String yearMonthKey = MonthPlanDateResolver.buildYearMonthKey(year, month);
        if (!CollectionUtils.isEmpty(context.getProductionVersionByYearMonthMap())) {
            if (context.getProductionVersionByYearMonthMap().containsKey(yearMonthKey)) {
                return context.getProductionVersionByYearMonthMap().get(yearMonthKey);
            }
            return null;
        }
        return context.getProductionVersion();
    }

    /**
     * 获取指定年月的定稿需求版本。
     *
     * @param context 排程上下文
     * @param year 年份
     * @param month 月份
     * @return 需求版本
     */
    private String resolveMonthPlanVersion(LhScheduleContext context, int year, int month) {
        String yearMonthKey = MonthPlanDateResolver.buildYearMonthKey(year, month);
        if (!CollectionUtils.isEmpty(context.getMonthPlanVersionByYearMonthMap())) {
            if (context.getMonthPlanVersionByYearMonthMap().containsKey(yearMonthKey)) {
                return context.getMonthPlanVersionByYearMonthMap().get(yearMonthKey);
            }
            return null;
        }
        return context.getMonthPlanVersion();
    }

    /**
     * 中断排程并返回基础数据不完整错误
     *
     * @param context 排程上下文
     * @param message 异常消息
     * @return
     */
    private void interruptByDataIncomplete(LhScheduleContext context, String message) {
        ScheduleDomainExceptionHelper.interrupt(
                context,
                ScheduleStepEnum.S4_2_DATA_INIT,
                ScheduleErrorCode.DATA_INCOMPLETE,
                message
        );
    }

    /**
     * 统一格式化异常提示中的工厂与年月信息
     *
     * @param factoryName 分厂名称
     * @param year        年份
     * @param month       月份（1-12）
     * @return
     */
    private String formatFactoryYearMonth(String factoryName, int year, int month) {
        String yearMonthText = String.format("%04d-%02d", year, month);
        return String.format("工厂【%s】 计划月份【%s】", factoryName, yearMonthText);
    }

    /** 定稿排产版本：工厂 + 年月 + 已定稿 + 未删除 */
    private LambdaQueryWrapper<MpFactoryProductionVersion> wrapFinalProductionVersion(
            String factoryCode, int year, int month) {
        return new LambdaQueryWrapper<MpFactoryProductionVersion>()
                .eq(MpFactoryProductionVersion::getFactoryCode, factoryCode)
                .eq(MpFactoryProductionVersion::getYear, year)
                .eq(MpFactoryProductionVersion::getMonth, month)
                .eq(MpFactoryProductionVersion::getIsFinal, PRODUCTION_VERSION_IS_FINAL)
                .eq(MpFactoryProductionVersion::getIsDelete, DeleteFlagEnum.NORMAL.getCode());
    }

    /**
     * 加载月生产计划
     *
     * @param context     排程上下文
     * @param factoryCode 分厂编号
     * @param year        年份（yyyy）
     * @param month       月份（m）
     */
    private void loadMonthPlan(LhScheduleContext context,
                               String factoryCode,
                               Map<String, LocalDate> requiredMonthMap) {
        List<FactoryMonthPlanProductionFinalResult> loadedPlanList = new ArrayList<FactoryMonthPlanProductionFinalResult>(256);
        if (CollectionUtils.isEmpty(requiredMonthMap)) {
            Calendar cal = Calendar.getInstance();
            cal.setTime(context.getScheduleTargetDate());
            loadedPlanList.addAll(queryMonthPlan(context, factoryCode,
                    cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1));
        } else {
            for (LocalDate monthStartDate : requiredMonthMap.values()) {
                loadedPlanList.addAll(queryMonthPlan(context, factoryCode,
                        monthStartDate.getYear(), monthStartDate.getMonthValue()));
            }
        }
        context.setLoadedMonthPlanList(loadedPlanList);
        context.setMonthPlanByMaterialMonthMap(MonthPlanDateResolver.buildMaterialMonthPlanMap(loadedPlanList));
        context.setMonthPlanList(selectSchedulingMonthPlanList(context, loadedPlanList));
        log.info("月生产计划加载完成, loadedCount: {}, scheduleSkuCount: {}, requiredMonths: {}",
                loadedPlanList.size(), context.getMonthPlanList().size(),
                CollectionUtils.isEmpty(requiredMonthMap) ? new ArrayList<String>(0) : requiredMonthMap.keySet());
    }

    private void loadMonthPlan(LhScheduleContext context, String factoryCode, int year, int month) {
        List<FactoryMonthPlanProductionFinalResult> monthPlanList = queryMonthPlan(context, factoryCode, year, month);
        context.setLoadedMonthPlanList(monthPlanList);
        context.setMonthPlanByMaterialMonthMap(MonthPlanDateResolver.buildMaterialMonthPlanMap(monthPlanList));
        context.setMonthPlanList(monthPlanList);
        log.debug("月生产计划加载完成, 数量: {}", context.getMonthPlanList().size());
    }

    /**
     * 查询指定月份月生产计划。
     *
     * @param context 排程上下文
     * @param factoryCode 工厂编码
     * @param year 年份
     * @param month 月份
     * @return 月计划列表
     */
    private List<FactoryMonthPlanProductionFinalResult> queryMonthPlan(LhScheduleContext context,
                                                                       String factoryCode,
                                                                       int year,
                                                                       int month) {
        String locationText = formatFactoryYearMonth(context.getFactoryDisplayName(), year, month);
        String monthPlanVersion = resolveMonthPlanVersion(context, year, month);
        if (StringUtils.isEmpty(monthPlanVersion)) {
            log.error("月生产计划加载失败，需求版本为空, factoryCode: {}, year: {}, month: {}",
                    factoryCode, year, month);
            interruptByDataIncomplete(context, String.format("%s 的定稿需求版本为空", locationText));
            return new ArrayList<FactoryMonthPlanProductionFinalResult>(0);
        }
        String productionVersion = resolveProductionVersion(context, year, month);
        if (StringUtils.isEmpty(productionVersion)) {
            log.error("月生产计划加载失败，排产版本为空, factoryCode: {}, year: {}, month: {}",
                    factoryCode, year, month);
            interruptByDataIncomplete(context, String.format("%s 的定稿排产版本为空", locationText));
            return new ArrayList<FactoryMonthPlanProductionFinalResult>(0);
        }
        LambdaQueryWrapper<FactoryMonthPlanProductionFinalResult> wrapper = new LambdaQueryWrapper<FactoryMonthPlanProductionFinalResult>()
                .eq(FactoryMonthPlanProductionFinalResult::getFactoryCode, factoryCode)
                .eq(FactoryMonthPlanProductionFinalResult::getYear, year)
                .eq(FactoryMonthPlanProductionFinalResult::getMonth, month)
                .eq(FactoryMonthPlanProductionFinalResult::getMonthPlanVersion, monthPlanVersion)
                .eq(FactoryMonthPlanProductionFinalResult::getProductionVersion, productionVersion)
                .eq(FactoryMonthPlanProductionFinalResult::getIsDelete, DeleteFlagEnum.NORMAL.getCode());
        List<FactoryMonthPlanProductionFinalResult> monthPlanList = monthPlanMapper.selectList(wrapper);
        return monthPlanList != null ? monthPlanList : new ArrayList<FactoryMonthPlanProductionFinalResult>(0);
    }

    /**
     * 为 S4.3 SKU 归集选择每个物料唯一的基础月计划。
     * <p>跨月时同一物料可能同时存在两个月计划，归集只能生成一个 DTO；dayN 读取仍由 Resolver 按业务日期取对应月份。</p>
     *
     * @param context 排程上下文
     * @param loadedPlanList 多月月计划
     * @return 去重后的归集月计划
     */
    private List<FactoryMonthPlanProductionFinalResult> selectSchedulingMonthPlanList(
            LhScheduleContext context, List<FactoryMonthPlanProductionFinalResult> loadedPlanList) {
        Map<String, FactoryMonthPlanProductionFinalResult> selectedPlanMap =
                new LinkedHashMap<String, FactoryMonthPlanProductionFinalResult>(128);
        if (CollectionUtils.isEmpty(loadedPlanList)) {
            return new ArrayList<FactoryMonthPlanProductionFinalResult>(0);
        }
        for (FactoryMonthPlanProductionFinalResult plan : loadedPlanList) {
            if (Objects.isNull(plan) || StringUtils.isEmpty(plan.getMaterialCode())) {
                continue;
            }
            FactoryMonthPlanProductionFinalResult selectedPlan = selectedPlanMap.get(plan.getMaterialCode());
            if (Objects.isNull(selectedPlan) || shouldReplaceSchedulingPlan(context, selectedPlan, plan)) {
                selectedPlanMap.put(plan.getMaterialCode(), plan);
            }
        }
        return new ArrayList<FactoryMonthPlanProductionFinalResult>(selectedPlanMap.values());
    }

    /**
     * 判断候选月计划是否更适合作为本轮 SKU 归集基础计划。
     *
     * @param context 排程上下文
     * @param selectedPlan 已选计划
     * @param candidatePlan 候选计划
     * @return true-替换
     */
    private boolean shouldReplaceSchedulingPlan(LhScheduleContext context,
                                                FactoryMonthPlanProductionFinalResult selectedPlan,
                                                FactoryMonthPlanProductionFinalResult candidatePlan) {
        boolean selectedHasWindowPlan = hasWindowPlanQty(selectedPlan, context.getScheduleDate(), context.getWindowEndDate());
        boolean candidateHasWindowPlan = hasWindowPlanQty(candidatePlan, context.getScheduleDate(), context.getWindowEndDate());
        if (selectedHasWindowPlan != candidateHasWindowPlan) {
            return candidateHasWindowPlan;
        }
        return !isPlanInScheduleMonth(selectedPlan, context.getScheduleDate())
                && isPlanInScheduleMonth(candidatePlan, context.getScheduleDate());
    }

    /**
     * 判断月计划在排程窗口内是否有日计划量。
     *
     * @param plan 月计划
     * @param startDate 窗口开始日期
     * @param endDate 窗口结束日期
     * @return true-窗口内有计划
     */
    private boolean hasWindowPlanQty(FactoryMonthPlanProductionFinalResult plan, Date startDate, Date endDate) {
        if (Objects.isNull(plan) || Objects.isNull(startDate) || Objects.isNull(endDate)
                || Objects.isNull(plan.getYear()) || Objects.isNull(plan.getMonth())) {
            return false;
        }
        LocalDate startLocalDate = toLocalDate(startDate);
        LocalDate endLocalDate = toLocalDate(endDate);
        LocalDate cursor = startLocalDate;
        while (!cursor.isAfter(endLocalDate)) {
            if (cursor.getYear() == plan.getYear() && cursor.getMonthValue() == plan.getMonth()
                    && MonthPlanDateResolver.hasPlanQty(
                    MonthPlanDayQtyUtil.resolveDayQty(plan, cursor.getDayOfMonth()))) {
                return true;
            }
            cursor = cursor.plusDays(1);
        }
        return false;
    }

    /**
     * 判断月计划是否属于排程窗口 T 日所在月份。
     *
     * @param plan 月计划
     * @param scheduleDate T 日
     * @return true-属于 T 日月份
     */
    private boolean isPlanInScheduleMonth(FactoryMonthPlanProductionFinalResult plan, Date scheduleDate) {
        if (Objects.isNull(plan) || Objects.isNull(scheduleDate)
                || Objects.isNull(plan.getYear()) || Objects.isNull(plan.getMonth())) {
            return false;
        }
        LocalDate scheduleLocalDate = toLocalDate(scheduleDate);
        return scheduleLocalDate.getYear() == plan.getYear()
                && scheduleLocalDate.getMonthValue() == plan.getMonth();
    }

    /**
     * 加载工作日历
     *
     * @param context     排程上下文
     * @param factoryCode 分厂编号
     * @param startDate   开始日期
     * @param endDate     结束日期
     */
    private void loadWorkCalendar(LhScheduleContext context, String factoryCode, Date startDate, Date endDate) {
        List<MdmWorkCalendar> workCalendarList = workCalendarMapper.selectList(
                new LambdaQueryWrapper<MdmWorkCalendar>()
                        .eq(MdmWorkCalendar::getFactoryCode, factoryCode)
                        .eq(MdmWorkCalendar::getProcCode, LhScheduleConstant.PROC_CODE_LH)
                        .ge(MdmWorkCalendar::getProductionDate, startDate)
                        .lt(MdmWorkCalendar::getProductionDate, endDate)
                        .eq(MdmWorkCalendar::getIsDelete, DeleteFlagEnum.NORMAL.getCode()));
        context.setWorkCalendarList(workCalendarList != null ? workCalendarList : context.getWorkCalendarList());
        log.debug("工作日历加载完成, 数量: {}", context.getWorkCalendarList().size());
    }

    /**
     * 加载胎胚实时库存，按胎胚编码汇总库存数量。
     *
     * @param context 排程上下文
     * @param factoryCode 分厂编号
     * @param stockDate 库存日期
     */
    private void loadEmbryoRealtimeStock(LhScheduleContext context, String factoryCode, Date stockDate) {
        List<String> embryoCodeList = context.getMonthPlanList().stream()
                .map(FactoryMonthPlanProductionFinalResult::getEmbryoCode)
                .filter(StringUtils::isNotEmpty)
                .distinct()
                .collect(java.util.stream.Collectors.toList());
        Map<String, Integer> stockMap = new HashMap<>(Math.max(16, embryoCodeList.size()));
        if (CollectionUtils.isEmpty(embryoCodeList)) {
            context.setEmbryoRealtimeStockMap(stockMap);
            log.debug("胎胚实时库存加载完成, 数量: {}", stockMap.size());
            return;
        }
        List<CxStock> stockList = cxStockMapper.selectList(new LambdaQueryWrapper<CxStock>()
                .eq(CxStock::getFactoryCode, factoryCode)
                .eq(CxStock::getStockDate, stockDate)
                .in(CxStock::getEmbryoCode, embryoCodeList));
        if (stockList != null) {
            for (CxStock stock : stockList) {
                if (StringUtils.isNotEmpty(stock.getEmbryoCode())) {
                    stockMap.merge(stock.getEmbryoCode(), resolveStockNum(stock.getStockNum()), Integer::sum);
                }
            }
        }
        context.setEmbryoRealtimeStockMap(stockMap);
        log.debug("胎胚实时库存加载完成, 数量: {}", stockMap.size());
    }


    /**
     * 加载胎胚收尾标识Map。
     * <p>以胎胚维度合并硫化余量（口径与排程阶段 {@link ScheduleAdjustHandler#calculatePlanSurplusQty} 一致），
     * 结合胎胚实时库存计算胎胚余量，按主销产品参与情况判定收尾标识：
     * <ul>
     *   <li>合并余量中含主销产品：胎胚余量 &lt;= 0 标识收尾(1)</li>
     *   <li>合并余量全为非主销：胎胚余量 &lt;= 2 标识收尾(1)</li>
     *   <li>其余标识非收尾(0)</li>
     * </ul>
     * 胎胚余量 = 合并后硫化余量 - 胎胚库存；key取月计划中所有胎胚代码全集，无库存记录默认0。</p>
     *
     * @param context 排程上下文
     */
    private void loadEmbryoEndingFlagMap(LhScheduleContext context) {
        List<FactoryMonthPlanProductionFinalResult> monthPlanList = context.getMonthPlanList();
        Map<String, Integer> embryoEndingFlagMap = new LinkedHashMap<>();
        if (CollectionUtils.isEmpty(monthPlanList)) {
            context.setEmbryoEndingFlagMap(embryoEndingFlagMap);
            log.debug("月生产计划为空，胎胚收尾标识Map为空");
            return;
        }

        // 1. 查询主销产品物料集合（SCHEDULE_TYPE='01'），逻辑删除由框架自动过滤
        Set<String> mainProductCodes = skuScheduleCategoryMapper.selectList(
                new LambdaQueryWrapper<MdmSkuScheduleCategory>()
                        .eq(MdmSkuScheduleCategory::getScheduleType, MAIN_PRODUCT_SCHEDULE_TYPE))
                .stream()
                .map(MdmSkuScheduleCategory::getMaterialCode)
                .filter(StringUtils::isNotEmpty)
                .collect(Collectors.toSet());

        // 2. 以胎胚维度合并硫化余量，并记录该胎胚下是否含主销产品
        Map<String, Integer> embryoSurplusMap = new LinkedHashMap<>();
        Map<String, Boolean> embryoHasMainMap = new LinkedHashMap<>();
        for (FactoryMonthPlanProductionFinalResult plan : monthPlanList) {
            String embryoCode = plan.getEmbryoCode();
            if (StringUtils.isEmpty(embryoCode)) {
                continue;
            }
            // 复用排程阶段余量计算口径，保证基础数据与排程判定一致
            int surplusQty = scheduleAdjustHandler.calculatePlanSurplusQty(context, plan);
            embryoSurplusMap.merge(embryoCode, surplusQty, Integer::sum);
            // 任一主销参与合并即标记为含主销，否则保持默认false
            if (mainProductCodes.contains(plan.getMaterialCode())) {
                embryoHasMainMap.put(embryoCode, Boolean.TRUE);
            } else {
                embryoHasMainMap.putIfAbsent(embryoCode, Boolean.FALSE);
            }
        }

        // 3. 按主销参与情况判定收尾标识：胎胚余量 = 合并硫化余量 - 胎胚库存
        Map<String, Integer> embryoStockMap = context.getEmbryoRealtimeStockMap();
        for (Map.Entry<String, Integer> entry : embryoSurplusMap.entrySet()) {
            String embryoCode = entry.getKey();
            int mergedSurplusQty = entry.getValue();
            int embryoStock = embryoStockMap.getOrDefault(embryoCode, 0);
            int embryoRemainQty = mergedSurplusQty - embryoStock;
            boolean hasMain = embryoHasMainMap.getOrDefault(embryoCode, Boolean.FALSE);
            int threshold = hasMain ? MAIN_PRODUCT_ENDING_THRESHOLD : NON_MAIN_PRODUCT_ENDING_THRESHOLD;
            int endingFlag = embryoRemainQty <= threshold ? EMBRYO_ENDING_FLAG_YES : EMBRYO_ENDING_FLAG_NO;
            embryoEndingFlagMap.put(embryoCode, endingFlag);
        }

        context.setEmbryoEndingFlagMap(embryoEndingFlagMap);
        int endingCount = embryoEndingFlagMap.values().stream()
                .mapToInt(Integer::intValue)
                .filter(v -> v == EMBRYO_ENDING_FLAG_YES)
                .sum();
        log.info("胎胚收尾标识加载完成, 胎胚数量: {}, 主销产品数量: {}, 收尾胎胚数量: {}",
                embryoSurplusMap.size(), mainProductCodes.size(), endingCount);
    }


    /**
     * 加载特殊物料清单，并按当前月计划范围构建分类Map。
     *
     * @param context 排程上下文
     * @param factoryCode 分厂编号
     */
    private void loadSpecialMaterialBom(LhScheduleContext context, String factoryCode) {
        Set<String> materialCodeSet = resolveMonthPlanMaterialCodeSet(context);
        Set<String> structureNameSet = resolveMonthPlanStructureNameSet(context);
        List<LhSpecialMaterialBom> specialMaterialBomList = new ArrayList<>();
        Map<String, Set<String>> categoryByMaterialCode = new HashMap<>(Math.max(16, materialCodeSet.size()));
        Map<String, Set<String>> categoryByStructureName = new HashMap<>(Math.max(16, structureNameSet.size()));
        if (CollectionUtils.isEmpty(materialCodeSet) && CollectionUtils.isEmpty(structureNameSet)) {
            attachSpecialMaterialConfig(context, specialMaterialBomList,
                    categoryByMaterialCode, categoryByStructureName);
            return;
        }

        LambdaQueryWrapper<LhSpecialMaterialBom> wrapper = new LambdaQueryWrapper<LhSpecialMaterialBom>()
                .eq(LhSpecialMaterialBom::getFactoryCode, factoryCode)
                .eq(LhSpecialMaterialBom::getIsDelete, DeleteFlagEnum.NORMAL.getCode())
                .orderByAsc(LhSpecialMaterialBom::getId);
        wrapper.and(condition -> {
            boolean hasMaterialCode = !CollectionUtils.isEmpty(materialCodeSet);
            boolean hasStructureName = !CollectionUtils.isEmpty(structureNameSet);
            if (hasMaterialCode) {
                condition.in(LhSpecialMaterialBom::getMaterialCode, materialCodeSet);
            }
            if (hasMaterialCode && hasStructureName) {
                condition.or();
            }
            if (hasStructureName) {
                condition.in(LhSpecialMaterialBom::getStructureName, structureNameSet);
            }
        });
        List<LhSpecialMaterialBom> queryList = lhSpecialMaterialBomEntityMapper.selectList(wrapper);
        if (!CollectionUtils.isEmpty(queryList)) {
            specialMaterialBomList.addAll(queryList);
            for (LhSpecialMaterialBom bom : queryList) {
                buildSpecialMaterialCategoryMap(bom, materialCodeSet, structureNameSet,
                        categoryByMaterialCode, categoryByStructureName);
            }
        }
        attachSpecialMaterialConfig(context, specialMaterialBomList,
                categoryByMaterialCode, categoryByStructureName);
    }

    /**
     * 解析月计划涉及的物料编码集合。
     *
     * @param context 排程上下文
     * @return 物料编码集合
     */
    private Set<String> resolveMonthPlanMaterialCodeSet(LhScheduleContext context) {
        return context.getMonthPlanList().stream()
                .map(FactoryMonthPlanProductionFinalResult::getMaterialCode)
                .map(this::normalizeText)
                .filter(StringUtils::isNotEmpty)
                .collect(java.util.stream.Collectors.toSet());
    }

    /**
     * 解析月计划涉及的结构名称集合。
     *
     * @param context 排程上下文
     * @return 结构名称集合
     */
    private Set<String> resolveMonthPlanStructureNameSet(LhScheduleContext context) {
        return context.getMonthPlanList().stream()
                .map(FactoryMonthPlanProductionFinalResult::getStructureName)
                .map(this::normalizeText)
                .filter(StringUtils::isNotEmpty)
                .collect(java.util.stream.Collectors.toSet());
    }

    /**
     * 构建特殊物料分类Map。
     *
     * @param bom 特殊物料清单配置
     * @param materialCodeSet 当前月计划物料编码集合
     * @param structureNameSet 当前月计划结构名称集合
     * @param categoryByMaterialCode 物料编码分类Map
     * @param categoryByStructureName 结构名称分类Map
     */
    private void buildSpecialMaterialCategoryMap(LhSpecialMaterialBom bom,
                                                 Set<String> materialCodeSet,
                                                 Set<String> structureNameSet,
                                                 Map<String, Set<String>> categoryByMaterialCode,
                                                 Map<String, Set<String>> categoryByStructureName) {
        if (Objects.isNull(bom) || !LhSpecialMaterialCategoryEnum.isValid(bom.getCategory())) {
            return;
        }
        String materialCode = normalizeText(bom.getMaterialCode());
        String structureName = normalizeText(bom.getStructureName());
        // 物料编码配置优先进入物料维度Map。
        if (StringUtils.isNotEmpty(materialCode) && materialCodeSet.contains(materialCode)) {
            categoryByMaterialCode.computeIfAbsent(materialCode, key -> new LinkedHashSet<String>(4))
                    .add(bom.getCategory());
            return;
        }
        // 结构名称只处理未维护物料编码的配置。
        if (StringUtils.isEmpty(materialCode)
                && StringUtils.isNotEmpty(structureName)
                && structureNameSet.contains(structureName)) {
            categoryByStructureName.computeIfAbsent(structureName, key -> new LinkedHashSet<String>(4))
                    .add(bom.getCategory());
        }
    }

    /**
     * 写入特殊物料配置到排程上下文。
     *
     * @param context 排程上下文
     * @param specialMaterialBomList 特殊物料配置列表
     * @param categoryByMaterialCode 物料编码分类Map
     * @param categoryByStructureName 结构名称分类Map
     */
    private void attachSpecialMaterialConfig(LhScheduleContext context,
                                             List<LhSpecialMaterialBom> specialMaterialBomList,
                                             Map<String, Set<String>> categoryByMaterialCode,
                                             Map<String, Set<String>> categoryByStructureName) {
        context.setSpecialMaterialBomList(specialMaterialBomList);
        context.setSpecialMaterialCategoryByMaterialCode(categoryByMaterialCode);
        context.setSpecialMaterialCategoryByStructureName(categoryByStructureName);
        log.info("特殊物料清单加载完成, 配置数: {}, 物料编码Map数: {}, 结构名称Map数: {}",
                specialMaterialBomList.size(), categoryByMaterialCode.size(), categoryByStructureName.size());
    }

    /**
     * 清洗配置匹配文本。
     *
     * @param value 原始值
     * @return 清洗后文本
     */
    private String normalizeText(String value) {
        if (StringUtils.isEmpty(value)) {
            return null;
        }
        String trimValue = value.trim();
        return StringUtils.isEmpty(trimValue) ? null : trimValue;
    }

    /**
     * 加载SKU日硫化产能，按物料编码建立Map
     *
     * @param context     排程上下文
     * @param factoryCode 分厂编号
     */
    private void loadSkuLhCapacity(LhScheduleContext context, String factoryCode) {
        List<MdmSkuLhCapacity> skuCapacityList = skuLhCapacityMapper.selectList(
                new LambdaQueryWrapper<MdmSkuLhCapacity>()
                        .eq(MdmSkuLhCapacity::getFactoryCode, factoryCode)
                        .eq(MdmSkuLhCapacity::getIsDelete, DeleteFlagEnum.NORMAL.getCode()));
        Map<String, MdmSkuLhCapacity> skuLhCapacityMap = new HashMap<>(64);
        if (skuCapacityList != null) {
            for (MdmSkuLhCapacity capacity : skuCapacityList) {
                if (capacity.getMaterialCode() != null) {
                    skuLhCapacityMap.put(capacity.getMaterialCode(), capacity);
                }
            }
        }
        context.setSkuLhCapacityMap(skuLhCapacityMap);
        log.debug("SKU日硫化产能加载完成, 数量: {}", skuLhCapacityMap.size());
    }

    /**
     * 加载设备停机计划
     *
     * @param context     排程上下文
     * @param factoryCode 分厂编号
     * @param startDate   开始日期
     * @param endDate     结束日期
     */
    private void loadDevicePlanShut(LhScheduleContext context, String factoryCode, Date startDate, Date endDate) {
        List<MdmDevicePlanShut> devicePlanShutList = devicePlanShutMapper.selectList(
                new LambdaQueryWrapper<MdmDevicePlanShut>()
                        .eq(MdmDevicePlanShut::getFactoryCode, factoryCode)
                        .le(MdmDevicePlanShut::getBeginDate, endDate)
                        .ge(MdmDevicePlanShut::getEndDate, startDate)
                        .eq(MdmDevicePlanShut::getIsDelete, DeleteFlagEnum.NORMAL.getCode()));
        context.setDevicePlanShutList(devicePlanShutList != null ? devicePlanShutList : context.getDevicePlanShutList());
        log.debug("设备停机计划加载完成, 数量: {}", context.getDevicePlanShutList().size());
    }

    /**
     * 加载SKU与模具关系，按物料编码建立Map
     *
     * @param context     排程上下文
     * @param factoryCode 分厂编号
     */
    private void loadSkuMouldRel(LhScheduleContext context, String factoryCode) {
        Set<String> materialCodeSet = collectMonthPlanMaterialCodes(context);
        if (CollectionUtils.isEmpty(materialCodeSet)) {
            context.setSkuMouldRelMap(new HashMap<String, List<MdmSkuMouldRel>>(0));
            log.debug("SKU与模具关系加载跳过, 本次月计划SKU为空");
            return;
        }
        List<MdmSkuMouldRel> skuMouldRelList = skuMouldRelMapper.selectList(
                new LambdaQueryWrapper<MdmSkuMouldRel>()
                        .eq(MdmSkuMouldRel::getFactoryCode, factoryCode)
                        .in(MdmSkuMouldRel::getMaterialCode, materialCodeSet)
                        .eq(MdmSkuMouldRel::getIsDelete, DeleteFlagEnum.NORMAL.getCode()));
        Map<String, List<MdmSkuMouldRel>> skuMouldRelMap = new HashMap<>(64);
        if (skuMouldRelList != null) {
            for (MdmSkuMouldRel rel : skuMouldRelList) {
                if (rel.getMaterialCode() != null && materialCodeSet.contains(rel.getMaterialCode())) {
                    skuMouldRelMap.computeIfAbsent(rel.getMaterialCode(), k -> new ArrayList<MdmSkuMouldRel>())
                            .add(rel);
                }
            }
        }
        context.setSkuMouldRelMap(skuMouldRelMap);
        log.debug("SKU与模具关系加载完成, SKU数量: {}", skuMouldRelMap.size());
    }

    /**
     * 加载模具台账，按模具号建立Map
     *
     * @param context     排程上下文
     * @param factoryCode 分厂编号
     */
    private void loadModelInfo(LhScheduleContext context, String factoryCode) {
        Set<String> mouldCodeSet = collectSkuMouldCodes(context);
        if (CollectionUtils.isEmpty(mouldCodeSet)) {
            context.setModelInfoMap(new HashMap<String, MdmModelInfo>(0));
            log.debug("模具台账加载跳过, 本次SKU关联模具号为空");
            return;
        }
        List<MdmModelInfo> modelInfoList = mdmModelInfoMapper.selectList(
                new LambdaQueryWrapper<MdmModelInfo>()
                        .eq(MdmModelInfo::getFactoryCode, factoryCode)
                        .in(MdmModelInfo::getMouldCode, mouldCodeSet)
                        .eq(MdmModelInfo::getIsDelete, DeleteFlagEnum.NORMAL.getCode()));
        Map<String, MdmModelInfo> modelInfoMap = new HashMap<>(128);
        if (!CollectionUtils.isEmpty(modelInfoList)) {
            for (MdmModelInfo modelInfo : modelInfoList) {
                if (StringUtils.isNotEmpty(modelInfo.getMouldCode())
                        && mouldCodeSet.contains(StringUtils.trim(modelInfo.getMouldCode()))) {
                    modelInfoMap.put(StringUtils.trim(modelInfo.getMouldCode()), modelInfo);
                }
            }
        }
        context.setModelInfoMap(modelInfoMap);
        log.debug("模具台账加载完成, 模具数量: {}", modelInfoMap.size());
    }

    /**
     * 收集本次月计划涉及的SKU编码。
     *
     * @param context 排程上下文
     * @return SKU编码集合
     */
    private Set<String> collectMonthPlanMaterialCodes(LhScheduleContext context) {
        Set<String> materialCodeSet = new LinkedHashSet<String>(64);
        if (Objects.isNull(context) || CollectionUtils.isEmpty(context.getMonthPlanList())) {
            return materialCodeSet;
        }
        for (FactoryMonthPlanProductionFinalResult monthPlan : context.getMonthPlanList()) {
            if (Objects.isNull(monthPlan) || StringUtils.isEmpty(monthPlan.getMaterialCode())) {
                continue;
            }
            materialCodeSet.add(StringUtils.trim(monthPlan.getMaterialCode()));
        }
        return materialCodeSet;
    }

    /**
     * 收集本次SKU关系中涉及的模具号。
     *
     * @param context 排程上下文
     * @return 模具号集合
     */
    private Set<String> collectSkuMouldCodes(LhScheduleContext context) {
        Set<String> mouldCodeSet = new LinkedHashSet<String>(128);
        if (Objects.isNull(context) || CollectionUtils.isEmpty(context.getSkuMouldRelMap())) {
            return mouldCodeSet;
        }
        for (List<MdmSkuMouldRel> relList : context.getSkuMouldRelMap().values()) {
            if (CollectionUtils.isEmpty(relList)) {
                continue;
            }
            for (MdmSkuMouldRel rel : relList) {
                if (Objects.isNull(rel) || StringUtils.isEmpty(rel.getMouldCode())) {
                    continue;
                }
                mouldCodeSet.add(StringUtils.trim(rel.getMouldCode()));
            }
        }
        return mouldCodeSet;
    }

    /**
     * 加载硫化机台信息，按机台编号建立LinkedHashMap（保持顺序）
     *
     * @param context     排程上下文
     * @param factoryCode 分厂编号
     */
    private void loadMachineInfo(LhScheduleContext context, String factoryCode) {
        List<LhMachineInfo> list = lhMachineInfoMapper.selectList(
                new LambdaQueryWrapper<LhMachineInfo>()
                        .eq(LhMachineInfo::getFactoryCode, factoryCode)
                        .eq(LhMachineInfo::getStatus, MachineStatusUtil.STATUS_ENABLED)
                        .eq(LhMachineInfo::getIsDelete, DeleteFlagEnum.NORMAL.getCode())
                        .orderByAsc(LhMachineInfo::getMachineOrder));
        Map<String, LhMachineInfo> machineInfoMap = new LinkedHashMap<>(32);
        if (list != null) {
            for (LhMachineInfo info : list) {
                if (info.getMachineCode() != null) {
                    machineInfoMap.put(info.getMachineCode(), info);
                }
            }
        }
        context.setMachineInfoMap(machineInfoMap);
        log.debug("硫化机台信息加载完成, 数量: {}", machineInfoMap.size());
    }

    /**
     * 加载模具清洗计划
     *
     * @param context     排程上下文
     * @param factoryCode 分厂编号
     * @param startDate   开始日期
     * @param endDate     结束日期
     */
    private void loadCleaningPlan(LhScheduleContext context, String factoryCode, Date startDate, Date endDate) {
        List<String> machineCodes = new ArrayList<>(context.getMachineInfoMap().keySet());
        List<LhMouldCleanPlan> cleaningPlanList;
        if (machineCodes.isEmpty()) {
            cleaningPlanList = Collections.emptyList();
        } else {
            cleaningPlanList = lhMouldCleanPlanMapper.selectList(
                    new LambdaQueryWrapper<LhMouldCleanPlan>()
                            .eq(LhMouldCleanPlan::getFactoryCode, factoryCode)
                            .in(LhMouldCleanPlan::getLhCode, machineCodes)
                            .ge(LhMouldCleanPlan::getCleanTime, startDate)
                            .lt(LhMouldCleanPlan::getCleanTime, endDate)
                            .and(w -> w.eq(LhMouldCleanPlan::getIsDelete, DeleteFlagEnum.NORMAL.getCode())
                                    .or()
                                    .isNull(LhMouldCleanPlan::getIsDelete))
                            .orderByAsc(LhMouldCleanPlan::getCleanTime));
        }
        context.setCleaningPlanList(cleaningPlanList != null ? cleaningPlanList : context.getCleaningPlanList());
        log.debug("模具清洗计划加载完成, 数量: {}", context.getCleaningPlanList().size());
    }


    /**
     * 加载指定日期的物料日完成量，按"物料+示方类型+完成日期"建立Map。
     *
     * @param context     排程上下文
     * @param factoryCode 分厂编号
     * @param finishDate  完成日期
     */
    private void loadDayFinishQty(LhScheduleContext context, String factoryCode, Date finishDate) {
        Date dayStart = LhScheduleTimeUtil.clearTime(finishDate);
        Date nextDayStart = LhScheduleTimeUtil.addDays(dayStart, 1);
        List<LhDayFinishQty> dayFinishQtyList = lhDayFinishQtyMapper.selectList(
                new LambdaQueryWrapper<LhDayFinishQty>()
                        .eq(LhDayFinishQty::getFactoryCode, factoryCode)
                        .ge(LhDayFinishQty::getFinishDate, dayStart)
                        .lt(LhDayFinishQty::getFinishDate, nextDayStart)
                        .and(wrapper -> wrapper.eq(LhDayFinishQty::getIsDelete, DeleteFlagEnum.NORMAL.getCode())
                                .or()
                                .isNull(LhDayFinishQty::getIsDelete)));
        Map<String, Integer> materialDayFinishedQtyMap = new HashMap<>(64);
        if (!CollectionUtils.isEmpty(dayFinishQtyList)) {
            for (LhDayFinishQty finishQty : dayFinishQtyList) {
                if (StringUtils.isEmpty(finishQty.getMaterialCode())) {
                    continue;
                }
                String key = buildMaterialDayKey(finishQty.getMaterialCode(), finishQty.getLhType(), dayStart);
                materialDayFinishedQtyMap.merge(key, resolveDayFinishedQty(finishQty), Integer::sum);
            }
        }
        context.setMaterialDayFinishedQtyMap(materialDayFinishedQtyMap);
        log.debug("日完成量加载完成, 完成日期: {}, 记录数: {}",
                LhScheduleTimeUtil.formatDate(dayStart), materialDayFinishedQtyMap.size());
    }

    /**
     * 加载月累计完成量（截至排产T-1日（包含）），按物料编号+示方类型建立Map。
     *
     * @param context     排程上下文
     * @param factoryCode 分厂编号
     * @param year        月计划所属年份
     * @param month       月计划所属月份
     * @param cutoffDate  截止日期（T-1日，含当天）
     */
    private void loadMaterialMonthFinishedQty(LhScheduleContext context,
                                              String factoryCode,
                                              Map<String, LocalDate> requiredMonthMap,
                                              Date cutoffDate,
                                              int primaryYear,
                                              int primaryMonth) {
        Map<String, Integer> primaryMonthFinishedQtyMap = new HashMap<>(64);
        Map<String, Integer> materialMonthFinishedQtyByMonthMap = new HashMap<>(128);
        Map<String, Integer> materialMonthDailyFinishedQtyMap = new HashMap<>(128);
        if (CollectionUtils.isEmpty(requiredMonthMap)) {
            mergeMaterialMonthFinishedQty(context, factoryCode, primaryYear, primaryMonth, cutoffDate,
                    primaryMonthFinishedQtyMap, materialMonthFinishedQtyByMonthMap, materialMonthDailyFinishedQtyMap);
        } else {
            for (LocalDate monthStartDate : requiredMonthMap.values()) {
                Map<String, Integer> monthFinishedQtyMap = new HashMap<>(64);
                mergeMaterialMonthFinishedQty(context, factoryCode, monthStartDate.getYear(),
                        monthStartDate.getMonthValue(), cutoffDate, monthFinishedQtyMap,
                        materialMonthFinishedQtyByMonthMap, materialMonthDailyFinishedQtyMap);
                if (monthStartDate.getYear() == primaryYear && monthStartDate.getMonthValue() == primaryMonth) {
                    primaryMonthFinishedQtyMap.putAll(monthFinishedQtyMap);
                }
            }
        }
        context.setMaterialMonthFinishedQtyMap(primaryMonthFinishedQtyMap);
        context.setMaterialMonthFinishedQtyByMonthMap(materialMonthFinishedQtyByMonthMap);
        context.setMaterialMonthDailyFinishedQtyMap(materialMonthDailyFinishedQtyMap);
        log.debug("月累计完成量加载完成, primaryCount: {}, monthKeyCount: {}, dailyCount: {}, requiredMonths: {}",
                primaryMonthFinishedQtyMap.size(), materialMonthFinishedQtyByMonthMap.size(),
                materialMonthDailyFinishedQtyMap.size(),
                CollectionUtils.isEmpty(requiredMonthMap) ? new ArrayList<String>(0) : requiredMonthMap.keySet());
    }

    private void loadMaterialMonthFinishedQty(LhScheduleContext context, String factoryCode,
                                              int year, int month, Date cutoffDate) {
        Map<String, Integer> monthFinishedQtyMap = new HashMap<>(64);
        Map<String, Integer> materialMonthFinishedQtyByMonthMap = new HashMap<>(128);
        Map<String, Integer> materialMonthDailyFinishedQtyMap = new HashMap<>(128);
        mergeMaterialMonthFinishedQty(context, factoryCode, year, month, cutoffDate, monthFinishedQtyMap,
                materialMonthFinishedQtyByMonthMap, materialMonthDailyFinishedQtyMap);
        context.setMaterialMonthFinishedQtyMap(monthFinishedQtyMap);
        context.setMaterialMonthFinishedQtyByMonthMap(materialMonthFinishedQtyByMonthMap);
        context.setMaterialMonthDailyFinishedQtyMap(materialMonthDailyFinishedQtyMap);
    }

    /**
     * 合并指定月份的月累计完成量。
     *
     * @param context 排程上下文
     * @param factoryCode 工厂编码
     * @param year 年份
     * @param month 月份
     * @param cutoffDate 截止日期
     * @param monthFinishedQtyMap 当前月份物料完成量
     * @param materialMonthFinishedQtyByMonthMap 跨月物料完成量
     * @param materialMonthDailyFinishedQtyMap 逐日完成量
     */
    private void mergeMaterialMonthFinishedQty(LhScheduleContext context,
                                               String factoryCode,
                                               int year,
                                               int month,
                                               Date cutoffDate,
                                               Map<String, Integer> monthFinishedQtyMap,
                                               Map<String, Integer> materialMonthFinishedQtyByMonthMap,
                                               Map<String, Integer> materialMonthDailyFinishedQtyMap) {
        Date cutoffDay = LhScheduleTimeUtil.clearTime(cutoffDate);
        Calendar calendar = Calendar.getInstance();
        calendar.clear();
        calendar.set(Calendar.YEAR, year);
        calendar.set(Calendar.MONTH, month - 1);
        calendar.set(Calendar.DAY_OF_MONTH, 1);
        Date monthStart = LhScheduleTimeUtil.clearTime(calendar.getTime());

        calendar.add(Calendar.MONTH, 1);
        Date nextMonthStart = LhScheduleTimeUtil.clearTime(calendar.getTime());

        Date queryEndDate = LhScheduleTimeUtil.addDays(cutoffDay, 1);
        if (queryEndDate.after(nextMonthStart)) {
            queryEndDate = nextMonthStart;
        }

        monthFinishedQtyMap.putAll(buildMonthPlanMaterialFinishedQtyMap(context, year, month));
        if (!queryEndDate.after(monthStart)) {
            appendMonthFinishedQtyByMonth(monthFinishedQtyMap, materialMonthFinishedQtyByMonthMap, year, month);
            log.debug("月累计完成量加载完成, 数量: {}, 月计划月份: {}-{}, 起始日: {}, 截止日: {}, 实际查询结束日: {}",
                    monthFinishedQtyMap.size(), year, month, LhScheduleTimeUtil.formatDate(monthStart),
                    LhScheduleTimeUtil.formatDate(cutoffDay), LhScheduleTimeUtil.formatDate(queryEndDate));
            return;
        }

        List<LhDayFinishQty> monthFinishList = lhDayFinishQtyMapper.selectList(
                new LambdaQueryWrapper<LhDayFinishQty>()
                        .eq(LhDayFinishQty::getFactoryCode, factoryCode)
                        .ge(LhDayFinishQty::getFinishDate, monthStart)
                        .lt(LhDayFinishQty::getFinishDate, queryEndDate)
                        .and(wrapper -> wrapper.eq(LhDayFinishQty::getIsDelete, DeleteFlagEnum.NORMAL.getCode())
                                .or()
                                .isNull(LhDayFinishQty::getIsDelete)));

        if (!CollectionUtils.isEmpty(monthFinishList)) {
            for (LhDayFinishQty finishQty : monthFinishList) {
                if (StringUtils.isEmpty(finishQty.getMaterialCode())) {
                    continue;
                }
                String materialStatusKey = buildMaterialStatusKey(finishQty.getMaterialCode(), finishQty.getLhType());
                monthFinishedQtyMap.merge(materialStatusKey, resolveDayFinishedQty(finishQty), Integer::sum);
                // 逐日Map仅保留完成量非空的日期；0是有效数据，供“最近一次完成量”判断停止回溯。
                if (Objects.nonNull(finishQty.getDayFinishQty())) {
                    materialMonthDailyFinishedQtyMap.merge(
                            buildMaterialDayKey(finishQty.getMaterialCode(), finishQty.getLhType(),
                                    LhScheduleTimeUtil.clearTime(finishQty.getFinishDate())),
                            resolveDayFinishedQty(finishQty),
                            Integer::sum);
                }
            }
        }

        appendMonthFinishedQtyByMonth(monthFinishedQtyMap, materialMonthFinishedQtyByMonthMap, year, month);
        log.debug("月累计完成量加载完成, 数量: {}, 月计划月份: {}-{}, 起始日: {}, 截止日: {}, 实际查询结束日: {}",
                monthFinishedQtyMap.size(),
                year, month, LhScheduleTimeUtil.formatDate(monthStart),
                LhScheduleTimeUtil.formatDate(cutoffDay), LhScheduleTimeUtil.formatDate(queryEndDate));
    }

    /**
     * 按月计划物料+产品状态初始化月累计完成量，确保目标月无完成记录时下游按0处理，不回退到上月完成量。
     *
     * @param context 排程上下文
     * @return 月计划物料完成量Map
     */
    private Map<String, Integer> buildMonthPlanMaterialFinishedQtyMap(LhScheduleContext context) {
        Map<String, Integer> materialMonthFinishedQtyMap = new HashMap<>(64);
        if (Objects.isNull(context) || CollectionUtils.isEmpty(context.getMonthPlanList())) {
            return materialMonthFinishedQtyMap;
        }
        for (FactoryMonthPlanProductionFinalResult plan : context.getMonthPlanList()) {
            if (Objects.isNull(plan) || StringUtils.isEmpty(plan.getMaterialCode())) {
                continue;
            }
            materialMonthFinishedQtyMap.putIfAbsent(
                    buildMaterialStatusKey(plan.getMaterialCode(), plan.getProductStatus()), 0);
        }
        return materialMonthFinishedQtyMap;
    }

    /**
     * 按指定年月的已加载月计划物料+产品状态初始化月累计完成量。
     *
     * @param context 排程上下文
     * @param year 年份
     * @param month 月份
     * @return 物料完成量Map
     */
    private Map<String, Integer> buildMonthPlanMaterialFinishedQtyMap(LhScheduleContext context, int year, int month) {
        Map<String, Integer> materialMonthFinishedQtyMap = new HashMap<>(64);
        if (Objects.isNull(context) || CollectionUtils.isEmpty(context.getLoadedMonthPlanList())) {
            return materialMonthFinishedQtyMap;
        }
        for (FactoryMonthPlanProductionFinalResult plan : context.getLoadedMonthPlanList()) {
            if (Objects.isNull(plan) || StringUtils.isEmpty(plan.getMaterialCode())
                    || !isMonthPlanBelongToMonth(plan, year, month)) {
                continue;
            }
            materialMonthFinishedQtyMap.putIfAbsent(
                    buildMaterialStatusKey(plan.getMaterialCode(), plan.getProductStatus()), 0);
        }
        return materialMonthFinishedQtyMap;
    }

    /**
     * 判断月计划是否归属指定年月。
     * <p>历史测试和部分内存构造对象可能不带年月字段，按当前查询月初始化，避免目标月无完成记录时误触发历史回退。</p>
     *
     * @param plan 月计划
     * @param year 年份
     * @param month 月份
     * @return true-属于指定年月；false-不属于
     */
    private boolean isMonthPlanBelongToMonth(FactoryMonthPlanProductionFinalResult plan, int year, int month) {
        if (Objects.isNull(plan)) {
            return false;
        }
        if (Objects.isNull(plan.getYear()) && Objects.isNull(plan.getMonth())) {
            return true;
        }
        return Objects.equals(plan.getYear(), year) && Objects.equals(plan.getMonth(), month);
    }

    /**
     * 将单月物料+产品状态完成量写入物料+产品状态+年月维度Map。
     *
     * @param monthFinishedQtyMap 单月物料+产品状态完成量
     * @param materialMonthFinishedQtyByMonthMap 物料+产品状态+年月维度完成量
     * @param year 年份
     * @param month 月份
     */
    private void appendMonthFinishedQtyByMonth(Map<String, Integer> monthFinishedQtyMap,
                                               Map<String, Integer> materialMonthFinishedQtyByMonthMap,
                                               int year,
                                               int month) {
        if (CollectionUtils.isEmpty(monthFinishedQtyMap)) {
            return;
        }
        for (Map.Entry<String, Integer> entry : monthFinishedQtyMap.entrySet()) {
            if (StringUtils.isEmpty(entry.getKey())) {
                continue;
            }
            materialMonthFinishedQtyByMonthMap.put(
                    MonthPlanDateResolver.buildMaterialMonthKey(entry.getKey(), year, month),
                    Math.max(0, Objects.isNull(entry.getValue()) ? 0 : entry.getValue()));
        }
    }

    /**
     * 加载T日排程班次完成量（来自LhScheFinishQty表），按物料编号汇总class1FinishQty。
     * <p>同一物料在同一T日可能有多条记录（不同机台），需按materialCode汇总。</p>
     *
     * @param context       排程上下文
     * @param factoryCode   分厂编号
     * @param scheduleDate  排程窗口起点T日
     */
    private void loadScheDayFinishQty(LhScheduleContext context, String factoryCode, Date scheduleDate) {
        Date tDay = LhScheduleTimeUtil.clearTime(scheduleDate);
        Date nextDay = LhScheduleTimeUtil.addDays(tDay, 1);

        List<LhScheFinishQty> scheFinishQtyList = lhScheFinishQtyMapper.selectList(
                new LambdaQueryWrapper<LhScheFinishQty>()
                        .eq(LhScheFinishQty::getFactoryCode, factoryCode)
                        .ge(LhScheFinishQty::getScheduleDate, tDay)
                        .lt(LhScheFinishQty::getScheduleDate, nextDay)
                        .and(wrapper -> wrapper.eq(LhScheFinishQty::getIsDelete, DeleteFlagEnum.NORMAL.getCode())
                                .or()
                                .isNull(LhScheFinishQty::getIsDelete)));

        Map<String, Integer> materialScheDayFinishQtyMap = new HashMap<>(64);
        if (!CollectionUtils.isEmpty(scheFinishQtyList)) {
            for (LhScheFinishQty scheFinishQty : scheFinishQtyList) {
                if (StringUtils.isEmpty(scheFinishQty.getMaterialCode())) {
                    continue;
                }
                materialScheDayFinishQtyMap.merge(
                        scheFinishQty.getMaterialCode(),
                        resolveFinishQtyValue(scheFinishQty.getClass1FinishQty()),
                        Integer::sum);
            }
        }

        context.setMaterialScheDayFinishQtyMap(materialScheDayFinishQtyMap);
        log.debug("T日排程班次完成量加载完成, 数量: {}, T日: {}",
                materialScheDayFinishQtyMap.size(),
                LhScheduleTimeUtil.formatDate(tDay));
    }

    /**
     * 构建"物料+产品状态"聚合Key。
     *
     * @param materialCode 物料编码
     * @param productStatus 产品状态或示方类型
     * @return 聚合Key
     */
    private String buildMaterialStatusKey(String materialCode, String productStatus) {
        String trimmedProductStatus = StringUtils.trimToEmpty(productStatus);
        if (StringUtils.isEmpty(trimmedProductStatus)) {
            return materialCode;
        }
        return materialCode + "_" + trimmedProductStatus;
    }

    /**
     * 生成"物料+产品状态+完成日期"聚合Key。
     *
     * @param materialCode 物料编码
     * @param productStatus 产品状态或示方类型
     * @param finishDate 完成日期
     * @return 聚合Key
     */
    private String buildMaterialDayKey(String materialCode, String productStatus, Date finishDate) {
        return buildMaterialStatusKey(materialCode, productStatus) + "_"
                + LhScheduleTimeUtil.formatDate(LhScheduleTimeUtil.clearTime(finishDate));
    }

    /**
     * 解析单条日完成记录的完成量。
     *
     * @param finishQty 日完成记录
     * @return 完成量
     */
    private int resolveDayFinishedQty(LhDayFinishQty finishQty) {
        if (Objects.isNull(finishQty)) {
            return 0;
        }
        return resolveFinishQtyValue(finishQty.getDayFinishQty());
    }

    /**
     * 将完成量安全转换为整数件数，供月累计完成量汇总使用。
     *
     * @param finishQty 完成量
     * @return 整数件数
     */
    private int resolveFinishQtyValue(BigDecimal finishQty) {
        return Objects.nonNull(finishQty) ? finishQty.intValue() : 0;
    }

    /**
     * 将胎胚库存数量安全转换为整数件数。
     *
     * @param stockNum 胎胚库存
     * @return 整数件数
     */
    private int resolveStockNum(Integer stockNum) {
        return Objects.nonNull(stockNum) ? stockNum : 0;
    }

    /**
     * 加载物料信息，按物料编码建立Map
     *
     * @param context     排程上下文
     * @param factoryCode 分厂编号
     */
    private void loadMaterialInfo(LhScheduleContext context, String factoryCode) {
        List<MdmMaterialInfo> materialInfoList = mdmMaterialInfoMapper.selectList(
                new LambdaQueryWrapper<MdmMaterialInfo>()
                        .eq(MdmMaterialInfo::getFactoryCode, factoryCode)
                        .eq(MdmMaterialInfo::getIsDelete, DeleteFlagEnum.NORMAL.getCode()));
        Map<String, MdmMaterialInfo> materialInfoMap = new HashMap<>(256);
        Map<String, Integer> embryoDescMaterialCountMap = new HashMap<>(128);
        if (materialInfoList != null) {
            for (MdmMaterialInfo materialInfo : materialInfoList) {
                if (materialInfo.getMaterialCode() != null) {
                    materialInfoMap.put(materialInfo.getMaterialCode(), materialInfo);
                }
                String embryoDesc = normalizeGroupToken(materialInfo.getEmbryoDesc());
                if (StringUtils.isNotEmpty(embryoDesc)) {
                    embryoDescMaterialCountMap.merge(embryoDesc, 1, Integer::sum);
                }
            }
        }
        context.setMaterialInfoMap(materialInfoMap);
        context.setEmbryoDescMaterialCountMap(embryoDescMaterialCountMap);
        log.debug("物料信息加载完成, 数量: {}", materialInfoMap.size());
    }

    /**
     * 加载胶囊卡盘分组，按规格和英寸分别建立快速判定Map。
     *
     * @param context 排程上下文
     * @param factoryCode 分厂编号
     */
    private void loadCapsuleChuck(LhScheduleContext context, String factoryCode) {
        List<MdmCapsuleChuck> capsuleChuckList = mdmCapsuleChuckMapper.selectList(
                new LambdaQueryWrapper<MdmCapsuleChuck>()
                        .eq(MdmCapsuleChuck::getFactoryCode, factoryCode)
                        .eq(MdmCapsuleChuck::getIsDelete, DeleteFlagEnum.NORMAL.getCode()));

        Map<String, String> capsuleSpecPeerMap = new HashMap<>(64);
        Map<String, String> capsuleProSizePeerMap = new HashMap<>(64);
        if (!CollectionUtils.isEmpty(capsuleChuckList)) {
            for (MdmCapsuleChuck capsuleChuck : capsuleChuckList) {
                registerCapsuleGroup(capsuleSpecPeerMap, capsuleChuck.getSpecifications());
                registerCapsuleGroup(capsuleProSizePeerMap, capsuleChuck.getProSize());
            }
        }
        context.setCapsuleSpecPeerMap(capsuleSpecPeerMap);
        context.setCapsuleProSizePeerMap(capsuleProSizePeerMap);
        log.debug("胶囊卡盘分组加载完成, 规格组: {}, 英寸组: {}",
                capsuleSpecPeerMap.size(), capsuleProSizePeerMap.size());
    }

    /**
     * 注册一条胶囊卡盘分组。
     *
     * @param capsuleGroupMap 分组Map
     * @param rawGroupValue 原始逗号分隔值
     */
    private void registerCapsuleGroup(Map<String, String> capsuleGroupMap, String rawGroupValue) {
        Set<String> normalizedTokens = splitGroupTokens(rawGroupValue);
        if (CollectionUtils.isEmpty(normalizedTokens)) {
            return;
        }
        String groupKey = String.join(",", normalizedTokens);
        for (String token : normalizedTokens) {
            capsuleGroupMap.putIfAbsent(token, groupKey);
        }
    }

    /**
     * 解析逗号分隔分组并做trim去重。
     *
     * @param rawGroupValue 原始分组值
     * @return 去重后的分组元素
     */
    private Set<String> splitGroupTokens(String rawGroupValue) {
        Set<String> normalizedTokens = new TreeSet<>();
        if (StringUtils.isEmpty(rawGroupValue)) {
            return normalizedTokens;
        }
        String[] tokenArray = rawGroupValue.split(",");
        for (String token : tokenArray) {
            String normalizedToken = normalizeGroupToken(token);
            if (StringUtils.isNotEmpty(normalizedToken)) {
                normalizedTokens.add(normalizedToken);
            }
        }
        return normalizedTokens;
    }

    /**
     * 统一分组字段格式，屏蔽前后空格和空串脏数据。
     *
     * @param token 原始值
     * @return 归一化结果
     */
    private String normalizeGroupToken(String token) {
        if (StringUtils.isEmpty(token)) {
            return null;
        }
        String normalizedToken = token.trim();
        return StringUtils.isEmpty(normalizedToken) ? null : normalizedToken;
    }

    /**
     * 加载MES硫化在机信息，按机台编号建立"追溯窗口内最近记录"Map
     *
     * @param context       排程上下文
     * @param factoryCode   分厂编号
     * @param scheduleTDay  排程窗口起点 T 日
     * @param lookbackDays  往前追溯天数（最小 1）
     */
    private void loadMachineOnlineInfo(LhScheduleContext context, String factoryCode, Date scheduleTDay, int lookbackDays) {
        int safeLookbackDays = Math.max(1, lookbackDays);
        Date tDay = LhScheduleTimeUtil.clearTime(scheduleTDay);
        Date lookbackStartDay = LhScheduleTimeUtil.addDays(tDay, -safeLookbackDays);

        // 在 [T-lookbackDays, T] 内加载窗口数据，并按机台保留距离T最近的一条记录。
        List<LhMachineOnlineInfo> machineOnlineInfoList = lhMachineOnlineInfoMapper.selectList(
                buildMachineOnlineBaseQuery(factoryCode)
                        .isNotNull(LhMachineOnlineInfo::getOnlineDate)
                        .ge(LhMachineOnlineInfo::getOnlineDate, lookbackStartDay)
                        .le(LhMachineOnlineInfo::getOnlineDate, tDay)
                        .orderByDesc(LhMachineOnlineInfo::getOnlineDate)
                        // ONLINE_DATE 为 date 类型；同日多条记录时按更新时间和MES版本号取最近同步版本。
                        .orderByDesc(LhMachineOnlineInfo::getUpdateTime)
                        .orderByDesc(LhMachineOnlineInfo::getDataVersion)
                        .orderByAsc(LhMachineOnlineInfo::getLhCode));
        if (CollectionUtils.isEmpty(machineOnlineInfoList)) {
            context.setMachineOnlineInfoMap(new HashMap<>(16));
            log.info("MES硫化在机信息未命中, 回溯窗口: [{} ~ {}], 回溯天数: {}, 命中机台: 0",
                    LhScheduleTimeUtil.formatDate(lookbackStartDay),
                    LhScheduleTimeUtil.formatDate(tDay),
                    safeLookbackDays);
            return;
        }

        Map<String, LhMachineOnlineInfo> machineOnlineInfoMap = new LinkedHashMap<>(32);
        for (LhMachineOnlineInfo onlineInfo : machineOnlineInfoList) {
            if (StringUtils.isEmpty(onlineInfo.getLhCode())) {
                continue;
            }
            // 查询结果已按日期倒序排列，首条即为该机台在追溯窗口内距离T最近的记录。
            machineOnlineInfoMap.putIfAbsent(onlineInfo.getLhCode(), onlineInfo);
        }
        context.setMachineOnlineInfoMap(machineOnlineInfoMap);
        log.info("MES硫化在机信息加载完成, 回溯窗口: [{} ~ {}], 回溯天数: {}, 命中机台: {}",
                LhScheduleTimeUtil.formatDate(lookbackStartDay),
                LhScheduleTimeUtil.formatDate(tDay),
                safeLookbackDays,
                machineOnlineInfoMap.size());
    }

    /**
     * 构建 MES 在机信息基础查询条件
     *
     * @param factoryCode 分厂编号
     * @return 查询条件
     */
    private LambdaQueryWrapper<LhMachineOnlineInfo> buildMachineOnlineBaseQuery(String factoryCode) {
        return new LambdaQueryWrapper<LhMachineOnlineInfo>()
                .eq(LhMachineOnlineInfo::getFactoryCode, factoryCode)
                .and(w -> w.eq(LhMachineOnlineInfo::getIsDelete, DeleteFlagEnum.NORMAL.getCode())
                        .or()
                        .isNull(LhMachineOnlineInfo::getIsDelete));
    }

    /**
     * 加载硫化定点机台，按物料编码建立Map。
     * <p>T_LH_SPECIFY_MACHINE.SPEC_CODE 实际维护物料编码。</p>
     *
     * @param context     排程上下文
     * @param factoryCode 分厂编号
     */
    private void loadSpecifyMachine(LhScheduleContext context, String factoryCode) {
        List<LhSpecifyMachine> specifyMachineList = lhSpecifyMachineMapper.selectList(
                new LambdaQueryWrapper<LhSpecifyMachine>()
                        .eq(LhSpecifyMachine::getFactoryCode, factoryCode)
                        .eq(LhSpecifyMachine::getIsDelete, DeleteFlagEnum.NORMAL.getCode()));
        Map<String, List<LhSpecifyMachine>> specifyMachineMap = new HashMap<>(32);
        if (!CollectionUtils.isEmpty(specifyMachineList)) {
            for (LhSpecifyMachine specifyMachine : specifyMachineList) {
                if (StringUtils.isNotEmpty(specifyMachine.getSpecCode())) {
                    specifyMachineMap.computeIfAbsent(specifyMachine.getSpecCode(),
                            k -> new ArrayList<>()).add(specifyMachine);
                }
            }
        }
        context.setSpecifyMachineMap(specifyMachineMap);
        log.debug("硫化定点机台加载完成, 物料数量: {}", specifyMachineMap.size());
    }

    /**
     * 加载硫化机胶囊已使用次数，按机台编号建立Map
     *
     * @param context     排程上下文
     * @param factoryCode 分厂编号
     */
    private void loadCapsuleUsage(LhScheduleContext context, String factoryCode) {
        List<LhRepairCapsule> capsuleUsageList = lhRepairCapsuleMapper.selectList(
                new LambdaQueryWrapper<LhRepairCapsule>()
                        .eq(LhRepairCapsule::getFactoryCode, factoryCode)
                        .eq(LhRepairCapsule::getIsDelete, DeleteFlagEnum.NORMAL.getCode()));
        Map<String, LhRepairCapsule> capsuleUsageMap = new HashMap<>(32);
        if (capsuleUsageList != null) {
            for (LhRepairCapsule capsule : capsuleUsageList) {
                if (capsule.getLhCode() != null) {
                    capsuleUsageMap.put(capsule.getLhCode(), capsule);
                }
            }
        }
        context.setCapsuleUsageMap(capsuleUsageMap);
        log.debug("硫化机胶囊使用次数加载完成, 数量: {}", capsuleUsageMap.size());
    }

    /**
     * 加载硫化精度保养计划，按机台编号建立Map
     *
     * @param context     排程上下文
     * @param factoryCode 分厂编号
     */
    private void loadMaintenancePlan(LhScheduleContext context, String factoryCode) {
        int scheduleYear = resolveScheduleYear(context);
        List<LhPrecisionPlan> maintenancePlanList = lhPrecisionPlanMapper.selectList(
                new LambdaQueryWrapper<LhPrecisionPlan>()
                        .eq(LhPrecisionPlan::getFactoryCode, factoryCode)
                        .eq(LhPrecisionPlan::getYear, BigDecimal.valueOf(scheduleYear))
                        .eq(LhPrecisionPlan::getCompletionStatus, "0")
                        .eq(LhPrecisionPlan::getIsDelete, DeleteFlagEnum.NORMAL.getCode()));
        Map<String, LhPrecisionPlan> maintenancePlanMap = new HashMap<>(32);
        if (maintenancePlanList != null) {
            for (LhPrecisionPlan plan : maintenancePlanList) {
                if (StringUtils.isNotEmpty(plan.getMachineCode())) {
                    maintenancePlanMap.put(plan.getMachineCode(), plan);
                }
            }
        }
        context.setMaintenancePlanMap(maintenancePlanMap);
        log.debug("硫化精度保养计划加载完成, 年度: {}, 数量: {}", scheduleYear, maintenancePlanMap.size());
    }

    /**
     * 加载SKU与示方书关系
     *
     * @param context     排程上下文
     * @param factoryCode 分厂编号
     */
    private void loadSkuConstructionRef(LhScheduleContext context, String factoryCode) {
        List<MdmSkuConstructionRef> refList = skuConstructionRefMapper.selectList(
                new LambdaQueryWrapper<MdmSkuConstructionRef>()
                        .eq(MdmSkuConstructionRef::getFactoryCode, factoryCode)
                        .eq(MdmSkuConstructionRef::getIsDelete, DeleteFlagEnum.NORMAL.getCode()));
        Map<String, MdmSkuConstructionRef> refMap = new HashMap<>(256);
        Map<String, MdmSkuConstructionRef> compositeKeyMap = new HashMap<>(256);
        if (refList != null) {
            for (MdmSkuConstructionRef ref : refList) {
                if (StringUtils.isNotEmpty(ref.getMaterialCode())) {
                    // 按物料编码（后者覆盖前者），供策略类使用
                    refMap.put(ref.getMaterialCode(), ref);
                    // 按物料编码 + 产品状态（复合key，完整保留所有记录），供校验器和策略类精确查找
                    compositeKeyMap.put(ref.getMaterialCode() + "::" + ref.getTrialStatus(), ref);
                }
            }
        }
        context.setSkuConstructionRefMap(refMap);
        context.setSkuConstructionRefCompositeKeyMap(compositeKeyMap);
        log.debug("SKU与示方书关系加载完成, 数量: {}, 复合Key数量: {}",
                refMap.size(), compositeKeyMap.size());
    }

    /**
     * 解析排程年度。
     *
     * @param context 排程上下文
     * @return 年度
     */
    private int resolveScheduleYear(LhScheduleContext context) {
        Date baseDate = context.getScheduleTargetDate() != null ? context.getScheduleTargetDate() : context.getScheduleDate();
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(baseDate != null ? baseDate : new Date());
        return calendar.get(Calendar.YEAR);
    }

}
