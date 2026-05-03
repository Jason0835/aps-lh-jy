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
import com.zlt.aps.lh.api.enums.DeleteFlagEnum;
import com.zlt.aps.lh.api.enums.ScheduleStepEnum;
import com.zlt.aps.lh.mapper.FactoryMonthPlanProductionFinalResultMapper;
import com.zlt.aps.lh.mapper.MpFactoryProductionVersionMapper;
import com.zlt.aps.lh.mapper.LhDayFinishQtyMapper;
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
import com.zlt.aps.lh.mapper.MdmMonthSurplusMapper;
import com.zlt.aps.lh.mapper.MdmSkuLhCapacityMapper;
import com.zlt.aps.lh.mapper.MdmSkuMouldRelMapper;
import com.zlt.aps.lh.mapper.MdmWorkCalendarMapper;
import com.zlt.aps.lh.mapper.MpAdjustResultMapper;
import com.zlt.aps.lh.mapper.CxStockMapper;
import com.zlt.aps.lh.exception.ScheduleDomainExceptionHelper;
import com.zlt.aps.lh.exception.ScheduleErrorCode;
import com.zlt.aps.lh.service.ILhBaseDataService;
import com.zlt.aps.lh.util.LhScheduleTimeUtil;
import com.zlt.aps.lh.util.MachineStatusUtil;
import com.zlt.aps.lh.api.domain.entity.LhPrecisionPlan;
import com.zlt.aps.mp.api.domain.entity.MdmCapsuleChuck;
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
import com.zlt.aps.mp.api.domain.entity.MpFactoryProductionVersion;
import com.zlt.aps.lh.api.domain.entity.LhDayFinishQty;
import com.zlt.aps.lh.api.domain.entity.LhScheduleResult;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

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
    @Resource
    private FactoryMonthPlanProductionFinalResultMapper monthPlanMapper;

    @Resource
    private MpFactoryProductionVersionMapper mpFactoryProductionVersionMapper;

    @Resource
    private MpAdjustResultMapper mpAdjustResultMapper;

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
    private MdmMonthSurplusMapper monthSurplusMapper;

    @Resource
    private LhDayFinishQtyMapper lhDayFinishQtyMapper;

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

    @Override
    public void loadAllBaseData(LhScheduleContext context) {
        String factoryCode = context.getFactoryCode();
        Date scheduleDate = context.getScheduleDate();
        Date targetDate = context.getScheduleTargetDate();

        // 加载排程时间范围：[startDate, endDate) 覆盖 T～T+(SCHEDULE_DAYS-1)，与连续排程窗口日历日一致
        Date startDate = LhScheduleTimeUtil.clearTime(scheduleDate);
        int scheduleDays = LhScheduleTimeUtil.getScheduleDays(context);
        Date endDate = LhScheduleTimeUtil.addDays(startDate, scheduleDays);

        // 获取年月信息（按排程目标日取月计划所属年月）
        Calendar cal = Calendar.getInstance();
        cal.setTime(targetDate);
        int year = cal.get(Calendar.YEAR);
        int month = cal.get(Calendar.MONTH) + 1;

        // 1. 加载定稿排产版本
        loadFinalProductionVersion(context, factoryCode, year, month);
        if (context.isInterrupted()) {
            return;
        }

        // 2. 加载月生产计划
        loadMonthPlan(context, factoryCode, year, month);

        // 3. 加载胎胚实时库存
        loadEmbryoRealtimeStock(context, factoryCode, startDate);

        // 4. 加载周程滚动调整结果
        loadAdjustResult(context, factoryCode, year, month);

        // 5. 加载工作日历
        loadWorkCalendar(context, factoryCode, startDate, endDate);

        // 6. 加载SKU日硫化产能
        loadSkuLhCapacity(context, factoryCode);

        // 7. 加载设备停机计划
        loadDevicePlanShut(context, factoryCode, startDate, endDate);

        // 8. 加载SKU与模具关系
        loadSkuMouldRel(context, factoryCode);

        // 9. 加载模具台账
        loadModelInfo(context, factoryCode);

        // 10. 加载硫化机台信息
        loadMachineInfo(context, factoryCode);

        // 11. 加载模具清洗计划
        loadCleaningPlan(context, factoryCode, startDate, endDate);

        // 12. 加载月底计划余量
        loadMonthSurplus(context, factoryCode, year, month);

        // 13. 加载前日物料日完成量（用于前日欠/超产差值修正,滚动模式取目标日前一日；强制重排取T-1）
        Date previousDataDate = resolvePreviousDataDate(context, targetDate);
        loadDayFinishQty(context, factoryCode, previousDataDate);

        // 14. 加载月累计完成量（截至排产T-1日（包含），按目标日所在月份统计）
        loadMaterialMonthFinishedQty(context, factoryCode, LhScheduleTimeUtil.addDays(scheduleDate, -1));

        // 15. 加载物料信息
        loadMaterialInfo(context, factoryCode);

        // 15.1 加载胶囊卡盘分组
        loadCapsuleChuck(context, factoryCode);

        // 16. 加载MES硫化在机信息（从 T-1 开始，按配置天数向前追溯最近有数据日期）
        int machineOnlineLookbackDays = context.getParamIntValue(
                LhScheduleParamConstant.MACHINE_ONLINE_LOOKBACK_DAYS,
                LhScheduleConstant.MACHINE_ONLINE_LOOKBACK_DAYS);
        loadMachineOnlineInfo(context, factoryCode, startDate, machineOnlineLookbackDays);

        // 17. 加载硫化定点机台
        loadSpecifyMachine(context, factoryCode);

        // 18. 加载硫化机胶囊已使用次数
        loadCapsuleUsage(context, factoryCode);

        // 19. 加载设备保养计划
        loadMaintenancePlan(context, factoryCode);

        // 20. 加载前日硫化排程结果
        loadPreviousScheduleResults(context, factoryCode, targetDate);
        // 21. 加载前日模具交替计划，供滚动衔接继承
        loadPreviousMouldChangePlans(context, factoryCode, targetDate);

        log.info("基础数据加载完成, 工厂: {}, 目标日: {}, T日: {}",
                factoryCode, LhScheduleTimeUtil.formatDate(targetDate), LhScheduleTimeUtil.formatDate(scheduleDate));
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
    private void loadFinalProductionVersion(LhScheduleContext context, String factoryCode, int year, int month) {
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
            return;
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
            return;
        }
        context.setProductionVersion(pv);
        log.debug("定稿排产版本加载完成, productionVersion: {}", pv);
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
    private void loadMonthPlan(LhScheduleContext context, String factoryCode, int year, int month) {
        LambdaQueryWrapper<FactoryMonthPlanProductionFinalResult> wrapper = new LambdaQueryWrapper<FactoryMonthPlanProductionFinalResult>()
                .eq(FactoryMonthPlanProductionFinalResult::getFactoryCode, factoryCode)
                .eq(FactoryMonthPlanProductionFinalResult::getYear, year)
                .eq(FactoryMonthPlanProductionFinalResult::getMonth, month)
                .eq(FactoryMonthPlanProductionFinalResult::getIsDelete, DeleteFlagEnum.NORMAL.getCode());
        String productionVersion = context.getProductionVersion();
        if (StringUtils.isNotEmpty(productionVersion)) {
            wrapper.eq(FactoryMonthPlanProductionFinalResult::getProductionVersion, productionVersion);
        }
        List<FactoryMonthPlanProductionFinalResult> monthPlanList = monthPlanMapper.selectList(wrapper);
        context.setMonthPlanList(monthPlanList != null ? monthPlanList : context.getMonthPlanList());
        log.debug("月生产计划加载完成, 数量: {}", context.getMonthPlanList().size());
    }

    /**
     * 加载周程滚动调整结果，按物料编码聚合。
     *
     * @param context 排程上下文
     * @param factoryCode 分厂编号
     * @param year 年份
     * @param month 月份
     */
    private void loadAdjustResult(LhScheduleContext context, String factoryCode, int year, int month) {
        String monthPlanVersion = context.getMonthPlanVersion();
        String productionVersion = context.getProductionVersion();
        if (StringUtils.isEmpty(monthPlanVersion) || StringUtils.isEmpty(productionVersion)) {
            context.setMpAdjustResultMap(new HashMap<>(16));
            log.warn("月计划版本或排产版本为空，跳过周程滚动调整结果加载, 工厂: {}, monthPlanVersion: {}, productionVersion: {}",
                    factoryCode, monthPlanVersion, productionVersion);
            return;
        }

        List<MpAdjustResult> adjustResults = mpAdjustResultMapper.selectList(new LambdaQueryWrapper<MpAdjustResult>()
                .eq(MpAdjustResult::getFactoryCode, factoryCode)
                .eq(MpAdjustResult::getYear, year)
                .eq(MpAdjustResult::getMonth, month)
                .eq(MpAdjustResult::getMonthPlanVersion, monthPlanVersion)
                .eq(MpAdjustResult::getProductionVersion, productionVersion)
                .eq(MpAdjustResult::getIsDelete, DeleteFlagEnum.NORMAL.getCode()));
        Map<String, List<MpAdjustResult>> adjustResultMap = new HashMap<>(64);
        if (!CollectionUtils.isEmpty(adjustResults)) {
            for (MpAdjustResult adjustResult : adjustResults) {
                if (StringUtils.isEmpty(adjustResult.getMaterialCode())) {
                    continue;
                }
                adjustResultMap.computeIfAbsent(adjustResult.getMaterialCode(), key -> new ArrayList<>()).add(adjustResult);
            }
        }
        context.setMpAdjustResultMap(adjustResultMap);
        log.debug("周程滚动调整结果加载完成, 记录数: {}, 物料数: {}",
                CollectionUtils.isEmpty(adjustResults) ? 0 : adjustResults.size(), adjustResultMap.size());
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
        List<MdmSkuMouldRel> skuMouldRelList = skuMouldRelMapper.selectList(
                new LambdaQueryWrapper<MdmSkuMouldRel>()
                        .eq(MdmSkuMouldRel::getFactoryCode, factoryCode)
                        .eq(MdmSkuMouldRel::getIsDelete, DeleteFlagEnum.NORMAL.getCode()));
        Map<String, List<MdmSkuMouldRel>> skuMouldRelMap = new HashMap<>(64);
        if (skuMouldRelList != null) {
            for (MdmSkuMouldRel rel : skuMouldRelList) {
                if (rel.getMaterialCode() != null) {
                    skuMouldRelMap.computeIfAbsent(rel.getMaterialCode(), k -> new ArrayList<>()).add(rel);
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
        List<MdmModelInfo> modelInfoList = mdmModelInfoMapper.selectList(
                new LambdaQueryWrapper<MdmModelInfo>()
                        .eq(MdmModelInfo::getFactoryCode, factoryCode)
                        .eq(MdmModelInfo::getIsDelete, DeleteFlagEnum.NORMAL.getCode()));
        Map<String, MdmModelInfo> modelInfoMap = new HashMap<>(128);
        if (!CollectionUtils.isEmpty(modelInfoList)) {
            for (MdmModelInfo modelInfo : modelInfoList) {
                if (StringUtils.isNotEmpty(modelInfo.getMouldCode())) {
                    modelInfoMap.put(modelInfo.getMouldCode(), modelInfo);
                }
            }
        }
        context.setModelInfoMap(modelInfoMap);
        log.debug("模具台账加载完成, 模具数量: {}", modelInfoMap.size());
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
     * 加载月底计划余量，按物料编号建立Map
     *
     * @param context     排程上下文
     * @param factoryCode 分厂编号
     * @param year        年份
     * @param month       月份
     */
    private void loadMonthSurplus(LhScheduleContext context, String factoryCode, int year, int month) {
        List<MdmMonthSurplus> monthSurplusList = monthSurplusMapper.selectList(
                new LambdaQueryWrapper<MdmMonthSurplus>()
                        .eq(MdmMonthSurplus::getFactoryCode, factoryCode)
                        .eq(MdmMonthSurplus::getYear, year)
                        .eq(MdmMonthSurplus::getMonth, month)
                        .eq(MdmMonthSurplus::getIsDelete, DeleteFlagEnum.NORMAL.getCode()));
        Map<String, MdmMonthSurplus> monthSurplusMap = new HashMap<>(64);
        if (monthSurplusList != null) {
            for (MdmMonthSurplus surplus : monthSurplusList) {
                if (StringUtils.isNotEmpty(surplus.getMaterialCode())) {
                    monthSurplusMap.put(surplus.getMaterialCode(), surplus);
                }
            }
        }
        context.setMonthSurplusMap(monthSurplusMap);
        log.debug("月底计划余量加载完成, 数量: {}", monthSurplusMap.size());
    }

    /**
     * 加载指定日期的物料日完成量，按“物料+完成日期”建立Map。
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
                String key = buildMaterialDayKey(finishQty.getMaterialCode(), dayStart);
                materialDayFinishedQtyMap.merge(key, resolveDayFinishedQty(finishQty), Integer::sum);
            }
        }
        context.setMaterialDayFinishedQtyMap(materialDayFinishedQtyMap);
        log.debug("日完成量加载完成, 完成日期: {}, 记录数: {}",
                LhScheduleTimeUtil.formatDate(dayStart), materialDayFinishedQtyMap.size());
    }

    /**
     * 加载月累计完成量（截至排产T-1日（包含）），按物料编号建立Map。
     *
     * @param context     排程上下文
     * @param factoryCode 分厂编号
     * @param cutoffDate  截止日期（T-1日，含当天）
     */
    private void loadMaterialMonthFinishedQty(LhScheduleContext context, String factoryCode, Date cutoffDate) {
        Date targetDay = LhScheduleTimeUtil.clearTime(cutoffDate);
        Date nextTargetDay = LhScheduleTimeUtil.addDays(targetDay, 1);
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(targetDay);
        calendar.set(Calendar.DAY_OF_MONTH, 1);
        Date monthStart = LhScheduleTimeUtil.clearTime(calendar.getTime());

        List<LhDayFinishQty> monthFinishList = lhDayFinishQtyMapper.selectList(
                new LambdaQueryWrapper<LhDayFinishQty>()
                        .eq(LhDayFinishQty::getFactoryCode, factoryCode)
                        .ge(LhDayFinishQty::getFinishDate, monthStart)
                        .lt(LhDayFinishQty::getFinishDate, nextTargetDay)
                        .and(wrapper -> wrapper.eq(LhDayFinishQty::getIsDelete, DeleteFlagEnum.NORMAL.getCode())
                                .or()
                                .isNull(LhDayFinishQty::getIsDelete)));

        Map<String, Integer> materialMonthFinishedQtyMap = new HashMap<>(64);
        if (!CollectionUtils.isEmpty(monthFinishList)) {
            for (LhDayFinishQty finishQty : monthFinishList) {
                if (StringUtils.isEmpty(finishQty.getMaterialCode())) {
                    continue;
                }
                materialMonthFinishedQtyMap.merge(
                        finishQty.getMaterialCode(),
                        resolveDayFinishedQty(finishQty),
                        Integer::sum);
            }
        }

        context.setMaterialMonthFinishedQtyMap(materialMonthFinishedQtyMap);
        log.debug("月累计完成量加载完成, 数量: {}, 起始日: {}, 截止: {}(含当天, 即T-1日)",
                materialMonthFinishedQtyMap.size(),
                LhScheduleTimeUtil.formatDate(monthStart),
                LhScheduleTimeUtil.formatDate(targetDay));
    }

    /**
     * 生成“物料+完成日期”聚合Key。
     *
     * @param materialCode 物料编码
     * @param finishDate 完成日期
     * @return 聚合Key
     */
    private String buildMaterialDayKey(String materialCode, Date finishDate) {
        return materialCode + "_" + LhScheduleTimeUtil.formatDate(LhScheduleTimeUtil.clearTime(finishDate));
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
     * 加载MES硫化在机信息，按机台编号建立“追溯窗口内最近记录”Map
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

        // 在 [T-lookbackDays, T) 内加载窗口数据，并按机台保留距离T最近的一条记录。
        List<LhMachineOnlineInfo> machineOnlineInfoList = lhMachineOnlineInfoMapper.selectList(
                buildMachineOnlineBaseQuery(factoryCode)
                        .isNotNull(LhMachineOnlineInfo::getOnlineDate)
                        .ge(LhMachineOnlineInfo::getOnlineDate, lookbackStartDay)
                        .lt(LhMachineOnlineInfo::getOnlineDate, tDay)
                        .orderByDesc(LhMachineOnlineInfo::getOnlineDate)
                        // ONLINE_DATE 为 date 类型；同日多条记录时按更新时间取最近同步版本。
                        .orderByDesc(LhMachineOnlineInfo::getUpdateTime)
                        .orderByAsc(LhMachineOnlineInfo::getLhCode));
        if (CollectionUtils.isEmpty(machineOnlineInfoList)) {
            context.setMachineOnlineInfoMap(new HashMap<>(16));
            log.info("MES硫化在机信息未命中, 回溯窗口: [{} ~ {}), 回溯天数: {}, 命中机台: 0",
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
        log.info("MES硫化在机信息加载完成, 回溯窗口: [{} ~ {}), 回溯天数: {}, 命中机台: {}",
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
     * 加载硫化定点机台，按规格代码建立Map
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
        if (specifyMachineList != null) {
            for (LhSpecifyMachine specifyMachine : specifyMachineList) {
                if (specifyMachine.getSpecCode() != null) {
                    specifyMachineMap.computeIfAbsent(specifyMachine.getSpecCode(),
                            k -> new ArrayList<>()).add(specifyMachine);
                }
            }
        }
        context.setSpecifyMachineMap(specifyMachineMap);
        log.debug("硫化定点机台加载完成, 规格数量: {}", specifyMachineMap.size());
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
