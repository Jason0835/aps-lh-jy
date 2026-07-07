package com.zlt.aps.lh.util;

import com.zlt.aps.lh.api.constant.LhScheduleConstant;
import com.zlt.aps.lh.api.domain.dto.MachineCleaningWindowDTO;
import com.zlt.aps.lh.api.domain.dto.MachineMaintenanceWindowDTO;
import com.zlt.aps.lh.api.domain.entity.LhScheduleResult;
import com.zlt.aps.lh.api.domain.vo.LhShiftConfigVO;
import com.zlt.aps.lh.api.enums.CleaningTypeEnum;
import com.zlt.aps.mdm.api.domain.entity.MdmDevicePlanShut;
import org.springframework.util.CollectionUtils;

import java.util.Date;
import java.util.List;
import java.util.Objects;

/**
 * 排程结果停机摘要工具。
 *
 * <p>将结果实际命中的保养、停机、清洗窗口回填到结果主表，便于排查定位。</p>
 *
 * @author APS
 */
public final class ResultDowntimeSummaryUtil {

    /** 喷砂与精度保养重叠时写入班次分析的固定原因 */
    private static final String SAND_BLAST_PRECISION_ANALYSIS = "喷砂清洗+精度";
    /** 喷砂与设备停机计划重叠时写入班次分析的固定原因 */
    private static final String SAND_BLAST_SHUTDOWN_ANALYSIS = "喷砂清洗+维修(设备停机计划)";
    /** 干冰清洗与普通换模重叠时写入班次分析的固定原因 */
    private static final String DRY_ICE_MOULD_CHANGE_ANALYSIS = "干冰清洗+换模";
    /** 喷砂清洗与普通换模重叠时写入班次分析的固定原因 */
    private static final String SAND_BLAST_MOULD_CHANGE_ANALYSIS = "喷砂清洗+换模";
    /** 单独干冰清洗时写入班次分析的固定原因 */
    private static final String DRY_ICE_ANALYSIS = "干冰清洗";
    /** 单独喷砂清洗时写入班次分析的固定原因 */
    private static final String SAND_BLAST_ANALYSIS = "喷砂清洗";

    private ResultDowntimeSummaryUtil() {
    }

    /**
     * 按结果实际排产时间段回填停机摘要。
     *
     * @param result 排程结果
     * @param maintenanceWindowList 保养窗口
     * @param cleaningWindowList 清洗窗口
     * @param devicePlanShutList 设备停机窗口
     */
    public static void fillDowntimeSummary(LhScheduleResult result,
                                           List<MachineMaintenanceWindowDTO> maintenanceWindowList,
                                           List<MachineCleaningWindowDTO> cleaningWindowList,
                                           List<MdmDevicePlanShut> devicePlanShutList,
                                           List<LhShiftConfigVO> scheduleWindowShifts) {
        clearDowntimeSummary(result);
        if (Objects.isNull(result)) {
            return;
        }
        Date productionStartTime = resolveFirstPlannedShiftStartTime(result);
        Date productionEndTime = result.getSpecEndTime();
        if (Objects.isNull(productionStartTime) || Objects.isNull(productionEndTime)
                || !productionEndTime.after(productionStartTime)) {
            return;
        }
        fillMaintenanceSummary(result, maintenanceWindowList, productionStartTime, productionEndTime);
        // 清洗摘要是机台级口径：只要该机台存在有效清洗窗口就回填，不按结果生产时段截断，
        // 避免清洗恰好从完工时刻开始时被边界判定漏掉。
        fillCleaningSummary(result, cleaningWindowList);
        fillShutdownSummary(result, devicePlanShutList, productionStartTime, productionEndTime);
        // 喷砂清洗与精度/维修实际重叠时，才在对应的最后一个重叠班次追加固定原因。
        appendSandBlastDowntimeAnalysis(result, cleaningWindowList, maintenanceWindowList,
                devicePlanShutList, scheduleWindowShifts);
        // 未与换模/维修/精度重叠的单独清洗，也要在实际开始清洗的班次写入简洁原因。
        appendStandaloneCleaningAnalysis(result, cleaningWindowList, maintenanceWindowList,
                devicePlanShutList, result.getMouldChangeStartTime(), productionStartTime,
                scheduleWindowShifts);
    }

    /**
     * 清空停机摘要字段。
     *
     * @param result 排程结果
     */
    public static void clearDowntimeSummary(LhScheduleResult result) {
        if (Objects.isNull(result)) {
            return;
        }
        result.setMaintenanceStartTime(null);
        result.setMaintenanceEndTime(null);
        result.setShutdownStartTime(null);
        result.setShutdownEndTime(null);
        result.setCleaningStartTime(null);
        result.setCleaningEndTime(null);
    }

    private static void fillMaintenanceSummary(LhScheduleResult result,
                                               List<MachineMaintenanceWindowDTO> maintenanceWindowList,
                                               Date productionStartTime,
                                               Date productionEndTime) {
        if (CollectionUtils.isEmpty(maintenanceWindowList)) {
            return;
        }
        Date earliestStartTime = null;
        Date latestEndTime = null;
        for (MachineMaintenanceWindowDTO maintenanceWindow : maintenanceWindowList) {
            if (Objects.isNull(maintenanceWindow)
                    || Objects.isNull(maintenanceWindow.getMaintenanceStartTime())
                    || Objects.isNull(maintenanceWindow.getMaintenanceEndTime())
                    || !isWindowOverlap(maintenanceWindow.getMaintenanceStartTime(),
                    maintenanceWindow.getMaintenanceEndTime(), productionStartTime, productionEndTime)) {
                continue;
            }
            earliestStartTime = earlier(earliestStartTime, maintenanceWindow.getMaintenanceStartTime());
            latestEndTime = later(latestEndTime, maintenanceWindow.getMaintenanceEndTime());
        }
        result.setMaintenanceStartTime(earliestStartTime);
        result.setMaintenanceEndTime(latestEndTime);
    }

    private static void fillCleaningSummary(LhScheduleResult result,
                                            List<MachineCleaningWindowDTO> cleaningWindowList) {
        if (CollectionUtils.isEmpty(cleaningWindowList)) {
            return;
        }
        Date earliestStartTime = null;
        Date latestEndTime = null;
        for (MachineCleaningWindowDTO cleaningWindow : cleaningWindowList) {
            if (Objects.isNull(cleaningWindow)
                    || Objects.isNull(cleaningWindow.getCleanStartTime())
                    || Objects.isNull(cleaningWindow.getCleanEndTime())) {
                continue;
            }
            earliestStartTime = earlier(earliestStartTime, cleaningWindow.getCleanStartTime());
            latestEndTime = later(latestEndTime, cleaningWindow.getCleanEndTime());
        }
        result.setCleaningStartTime(earliestStartTime);
        result.setCleaningEndTime(latestEndTime);
    }

    private static void fillShutdownSummary(LhScheduleResult result,
                                            List<MdmDevicePlanShut> devicePlanShutList,
                                            Date productionStartTime,
                                            Date productionEndTime) {
        if (CollectionUtils.isEmpty(devicePlanShutList)) {
            return;
        }
        Date earliestStartTime = null;
        Date latestEndTime = null;
        for (MdmDevicePlanShut planShut : devicePlanShutList) {
            if (Objects.isNull(planShut)
                    || Objects.isNull(planShut.getBeginDate())
                    || Objects.isNull(planShut.getEndDate())
                    || !isWindowOverlap(planShut.getBeginDate(),
                    planShut.getEndDate(), productionStartTime, productionEndTime)) {
                continue;
            }
            earliestStartTime = earlier(earliestStartTime, planShut.getBeginDate());
            latestEndTime = later(latestEndTime, planShut.getEndDate());
        }
        result.setShutdownStartTime(earliestStartTime);
        result.setShutdownEndTime(latestEndTime);
    }

    /**
     * 追加喷砂清洗与精度/维修重叠原因。
     *
     * @param result 排程结果
     * @param cleaningWindowList 清洗窗口
     * @param maintenanceWindowList 精度保养窗口
     * @param devicePlanShutList 设备停机窗口
     */
    private static void appendSandBlastDowntimeAnalysis(LhScheduleResult result,
                                                        List<MachineCleaningWindowDTO> cleaningWindowList,
                                                        List<MachineMaintenanceWindowDTO> maintenanceWindowList,
                                                        List<MdmDevicePlanShut> devicePlanShutList,
                                                        List<LhShiftConfigVO> scheduleWindowShifts) {
        if (Objects.isNull(result) || CollectionUtils.isEmpty(cleaningWindowList)) {
            return;
        }
        for (MachineCleaningWindowDTO cleaningWindow : cleaningWindowList) {
            if (!isSandBlastWindow(cleaningWindow)) {
                continue;
            }
            // 喷砂与精度保养窗口实际相交时，产能扣减走并行取最大，原因写入最后一个重叠班次。
            appendSandBlastMaintenanceAnalysis(result, cleaningWindow, maintenanceWindowList, scheduleWindowShifts);
            // 喷砂与普通设备停机计划实际相交时，写入设备停机计划组合原因。
            appendSandBlastShutdownAnalysis(result, cleaningWindow, devicePlanShutList, scheduleWindowShifts);
        }
    }

    /**
     * 追加喷砂清洗与精度保养重叠原因。
     *
     * @param result 排程结果
     * @param cleaningWindow 喷砂清洗窗口
     * @param maintenanceWindowList 精度保养窗口
     */
    private static void appendSandBlastMaintenanceAnalysis(LhScheduleResult result,
                                                           MachineCleaningWindowDTO cleaningWindow,
                                                           List<MachineMaintenanceWindowDTO> maintenanceWindowList,
                                                           List<LhShiftConfigVO> scheduleWindowShifts) {
        if (CollectionUtils.isEmpty(maintenanceWindowList)) {
            return;
        }
        for (MachineMaintenanceWindowDTO maintenanceWindow : maintenanceWindowList) {
            if (Objects.isNull(maintenanceWindow)) {
                continue;
            }
            appendOverlapAnalysis(result, cleaningWindow.getCleanStartTime(), cleaningWindow.getCleanEndTime(),
                    maintenanceWindow.getMaintenanceStartTime(), maintenanceWindow.getMaintenanceEndTime(),
                    SAND_BLAST_PRECISION_ANALYSIS, scheduleWindowShifts);
        }
    }

    /**
     * 追加喷砂清洗与设备停机计划重叠原因。
     *
     * @param result 排程结果
     * @param cleaningWindow 喷砂清洗窗口
     * @param devicePlanShutList 设备停机窗口
     */
    private static void appendSandBlastShutdownAnalysis(LhScheduleResult result,
                                                        MachineCleaningWindowDTO cleaningWindow,
                                                        List<MdmDevicePlanShut> devicePlanShutList,
                                                        List<LhShiftConfigVO> scheduleWindowShifts) {
        if (CollectionUtils.isEmpty(devicePlanShutList)) {
            return;
        }
        for (MdmDevicePlanShut planShut : devicePlanShutList) {
            if (Objects.isNull(planShut)) {
                continue;
            }
            appendOverlapAnalysis(result, cleaningWindow.getCleanStartTime(), cleaningWindow.getCleanEndTime(),
                    planShut.getBeginDate(), planShut.getEndDate(), SAND_BLAST_SHUTDOWN_ANALYSIS,
                    scheduleWindowShifts);
        }
    }

    /**
     * 追加清洗与普通换模重叠原因。
     * <p>清洗 + 普通换模是特殊场景：只执行换模、耗时按换模口径计算；该方法只负责写入原因备注，
     * 不改变排程开始/结束时间，也不参与产能扣减。</p>
     *
     * @param result 排程结果
     * @param cleaningWindowList 清洗窗口
     * @param mouldChangeStartTime 换模开始时间
     * @param productionStartTime 换模完成后首个生产班次开始时间
     */
    public static void appendCleaningMouldChangeAnalysis(LhScheduleResult result,
                                                         List<MachineCleaningWindowDTO> cleaningWindowList,
                                                         Date mouldChangeStartTime,
                                                         Date mouldChangeCompleteTime,
                                                         List<LhShiftConfigVO> scheduleWindowShifts) {
        if (Objects.isNull(result) || CollectionUtils.isEmpty(cleaningWindowList)) {
            return;
        }
        int fallbackShiftIndex = resolveFirstPlannedShiftIndex(result);
        for (MachineCleaningWindowDTO cleaningWindow : cleaningWindowList) {
            String analysis = resolveCleaningMouldChangeAnalysis(cleaningWindow);
            if (Objects.isNull(analysis)) {
                continue;
            }
            // 换模重叠备注按清洗来源计划窗口或实际窗口判断；命中后只写原因，不把清洗作为额外产能扣减。
            // 换模窗口必须按真实换模总时长(8h)判断，不能用首个生产班次开始时间截断，否则首检落在换模班次时会漏判。
            appendOverlapAnalysis(result,
                    MachineCleaningOverlapUtil.resolveMouldChangeAnalysisStartTime(cleaningWindow),
                    MachineCleaningOverlapUtil.resolveMouldChangeAnalysisEndTime(cleaningWindow),
                    mouldChangeStartTime, mouldChangeCompleteTime, analysis, fallbackShiftIndex,
                    scheduleWindowShifts);
            // 排程可能把清洗提前/延后到换模时段，实际清洗窗口与来源计划窗口不一致；
            // 两者任一与换模重叠都需写“清洗+换模”，同一班次重复写入会被自动去重。
            appendOverlapAnalysis(result,
                    cleaningWindow.getCleanStartTime(), cleaningWindow.getCleanEndTime(),
                    mouldChangeStartTime, mouldChangeCompleteTime, analysis, fallbackShiftIndex,
                    scheduleWindowShifts);
        }
    }

    /**
     * 追加单独清洗原因。
     *
     * <p>只有清洗没有与换模、精度保养、普通设备停机重叠时才写“干冰清洗/喷砂清洗”，
     * 避免与“清洗+换模”等组合原因重复。</p>
     *
     * @param result 排程结果
     * @param cleaningWindowList 清洗窗口
     * @param maintenanceWindowList 精度保养窗口
     * @param devicePlanShutList 普通设备停机窗口
     * @param mouldChangeStartTime 换模开始时间
     * @param productionStartTime 换模完成后首个生产班次开始时间
     */
    private static void appendStandaloneCleaningAnalysis(LhScheduleResult result,
                                                         List<MachineCleaningWindowDTO> cleaningWindowList,
                                                         List<MachineMaintenanceWindowDTO> maintenanceWindowList,
                                                         List<MdmDevicePlanShut> devicePlanShutList,
                                                         Date mouldChangeStartTime,
                                                         Date productionStartTime,
                                                         List<LhShiftConfigVO> scheduleWindowShifts) {
        if (Objects.isNull(result) || CollectionUtils.isEmpty(cleaningWindowList)) {
            return;
        }
        for (MachineCleaningWindowDTO cleaningWindow : cleaningWindowList) {
            String analysis = resolveStandaloneCleaningAnalysis(cleaningWindow);
            if (Objects.isNull(analysis) || isCleaningOverlapOtherDowntime(cleaningWindow,
                    maintenanceWindowList, devicePlanShutList, mouldChangeStartTime, productionStartTime)) {
                continue;
            }
            appendCleaningStartShiftAnalysis(result, cleaningWindow, analysis, scheduleWindowShifts);
        }
    }

    /**
     * 判断清洗是否与其他停机事项重叠。
     *
     * @param cleaningWindow 清洗窗口
     * @param maintenanceWindowList 精度保养窗口
     * @param devicePlanShutList 普通设备停机窗口
     * @param mouldChangeStartTime 换模开始时间
     * @param productionStartTime 换模完成后首个生产班次开始时间
     * @return true-存在重叠；false-单独清洗
     */
    private static boolean isCleaningOverlapOtherDowntime(MachineCleaningWindowDTO cleaningWindow,
                                                          List<MachineMaintenanceWindowDTO> maintenanceWindowList,
                                                          List<MdmDevicePlanShut> devicePlanShutList,
                                                          Date mouldChangeStartTime,
                                                          Date productionStartTime) {
        if (MachineCleaningOverlapUtil.isSwitchOverlap(cleaningWindow, mouldChangeStartTime, productionStartTime)) {
            return true;
        }
        if (!CollectionUtils.isEmpty(maintenanceWindowList)) {
            for (MachineMaintenanceWindowDTO maintenanceWindow : maintenanceWindowList) {
                if (Objects.nonNull(maintenanceWindow) && isWindowOverlap(cleaningWindow.getCleanStartTime(),
                        cleaningWindow.getCleanEndTime(), maintenanceWindow.getMaintenanceStartTime(),
                        maintenanceWindow.getMaintenanceEndTime())) {
                    return true;
                }
            }
        }
        if (!CollectionUtils.isEmpty(devicePlanShutList)) {
            for (MdmDevicePlanShut planShut : devicePlanShutList) {
                if (Objects.nonNull(planShut) && isWindowOverlap(cleaningWindow.getCleanStartTime(),
                        cleaningWindow.getCleanEndTime(), planShut.getBeginDate(), planShut.getEndDate())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 将单独清洗原因写入实际开始清洗的班次。
     *
     * @param result 排程结果
     * @param cleaningWindow 清洗窗口
     * @param analysis 固定原因
     */
    private static void appendCleaningStartShiftAnalysis(LhScheduleResult result,
                                                         MachineCleaningWindowDTO cleaningWindow,
                                                         String analysis,
                                                         List<LhShiftConfigVO> scheduleWindowShifts) {
        // 单独清洗按"实际开始清洗时间"定位班次：使用排程窗口标准班次而非结果班次时间，
        // 避免结果在该班次无生产(班次时间为空)时备注错位到相邻班次。
        int shiftIndex = resolveShiftIndexByStartTime(scheduleWindowShifts, cleaningWindow.getCleanStartTime());
        if (shiftIndex > 0) {
            ShiftFieldUtil.appendShiftAnalysis(result, shiftIndex, analysis);
        }
    }

    /**
     * 解析单独清洗固定原因。
     *
     * @param cleaningWindow 清洗窗口
     * @return 固定原因；非干冰/喷砂清洗返回 null
     */
    private static String resolveStandaloneCleaningAnalysis(MachineCleaningWindowDTO cleaningWindow) {
        if (isDryIceWindow(cleaningWindow)) {
            return DRY_ICE_ANALYSIS;
        }
        if (isSandBlastWindow(cleaningWindow)) {
            return SAND_BLAST_ANALYSIS;
        }
        return null;
    }

    /**
     * 解析清洗与普通换模重叠的固定原因。
     *
     * @param cleaningWindow 清洗窗口
     * @return 固定原因；非干冰/喷砂清洗返回 null
     */
    private static String resolveCleaningMouldChangeAnalysis(MachineCleaningWindowDTO cleaningWindow) {
        if (isDryIceWindow(cleaningWindow)) {
            return DRY_ICE_MOULD_CHANGE_ANALYSIS;
        }
        if (isSandBlastWindow(cleaningWindow)) {
            return SAND_BLAST_MOULD_CHANGE_ANALYSIS;
        }
        return null;
    }

    /**
     * 按两个窗口的真实交集追加班次原因。
     *
     * @param result 排程结果
     * @param leftStartTime 左窗口开始时间
     * @param leftEndTime 左窗口结束时间
     * @param rightStartTime 右窗口开始时间
     * @param rightEndTime 右窗口结束时间
     * @param analysis 固定原因
     */
    private static void appendOverlapAnalysis(LhScheduleResult result,
                                              Date leftStartTime,
                                              Date leftEndTime,
                                              Date rightStartTime,
                                              Date rightEndTime,
                                              String analysis,
                                              List<LhShiftConfigVO> scheduleWindowShifts) {
        appendOverlapAnalysis(result, leftStartTime, leftEndTime, rightStartTime, rightEndTime,
                analysis, -1, scheduleWindowShifts);
    }

    /**
     * 按两个窗口的真实交集追加班次原因，必要时回退到首个生产班次。
     *
     * @param result 排程结果
     * @param leftStartTime 左窗口开始时间
     * @param leftEndTime 左窗口结束时间
     * @param rightStartTime 右窗口开始时间
     * @param rightEndTime 右窗口结束时间
     * @param analysis 固定原因
     * @param fallbackShiftIndex 未命中重叠班次时的回退班次
     */
    private static void appendOverlapAnalysis(LhScheduleResult result,
                                              Date leftStartTime,
                                              Date leftEndTime,
                                              Date rightStartTime,
                                              Date rightEndTime,
                                              String analysis,
                                              int fallbackShiftIndex,
                                              List<LhShiftConfigVO> scheduleWindowShifts) {
        if (!isWindowOverlap(leftStartTime, leftEndTime, rightStartTime, rightEndTime)) {
            return;
        }
        Date overlapStartTime = later(leftStartTime, rightStartTime);
        Date overlapEndTime = earlier(leftEndTime, rightEndTime);
        int shiftIndex = resolveLastOverlapShiftIndex(scheduleWindowShifts, overlapStartTime, overlapEndTime);
        if (shiftIndex <= 0) {
            shiftIndex = fallbackShiftIndex;
        }
        if (shiftIndex > 0) {
            ShiftFieldUtil.appendShiftAnalysis(result, shiftIndex, analysis);
        }
    }

    /**
     * 判断是否为有效喷砂清洗窗口。
     *
     * @param cleaningWindow 清洗窗口
     * @return true-喷砂清洗窗口
     */
    private static boolean isSandBlastWindow(MachineCleaningWindowDTO cleaningWindow) {
        return Objects.nonNull(cleaningWindow)
                && CleaningTypeEnum.SAND_BLAST.getCode().equals(cleaningWindow.getCleanType())
                && Objects.nonNull(cleaningWindow.getCleanStartTime())
                && Objects.nonNull(cleaningWindow.getCleanEndTime())
                && cleaningWindow.getCleanStartTime().before(cleaningWindow.getCleanEndTime());
    }

    /**
     * 判断是否为有效干冰清洗窗口。
     *
     * @param cleaningWindow 清洗窗口
     * @return true-干冰清洗窗口
     */
    private static boolean isDryIceWindow(MachineCleaningWindowDTO cleaningWindow) {
        return Objects.nonNull(cleaningWindow)
                && CleaningTypeEnum.DRY_ICE.getCode().equals(cleaningWindow.getCleanType())
                && Objects.nonNull(cleaningWindow.getCleanStartTime())
                && Objects.nonNull(cleaningWindow.getCleanEndTime())
                && cleaningWindow.getCleanStartTime().before(cleaningWindow.getCleanEndTime());
    }

    /**
     * 解析指定重叠区间对应的最后一个结果班次。
     *
     * @param result 排程结果
     * @param overlapStartTime 重叠开始时间
     * @param overlapEndTime 重叠结束时间
     * @return 最后一个重叠班次索引；未命中返回 -1
     */
    private static int resolveLastOverlapShiftIndex(List<LhShiftConfigVO> scheduleWindowShifts,
                                                    Date overlapStartTime,
                                                    Date overlapEndTime) {
        if (CollectionUtils.isEmpty(scheduleWindowShifts)) {
            return -1;
        }
        int lastShiftIndex = -1;
        for (LhShiftConfigVO shift : scheduleWindowShifts) {
            if (Objects.isNull(shift) || Objects.isNull(shift.getShiftIndex())) {
                continue;
            }
            if (isWindowOverlap(shift.getShiftStartDateTime(), shift.getShiftEndDateTime(),
                    overlapStartTime, overlapEndTime)) {
                lastShiftIndex = shift.getShiftIndex();
            }
        }
        return lastShiftIndex;
    }

    /**
     * 按时间点定位其落入的排程窗口标准班次索引。
     *
     * @param scheduleWindowShifts 排程窗口标准班次
     * @param time 待定位时间
     * @return 班次索引；未命中返回 -1
     */
    private static int resolveShiftIndexByStartTime(List<LhShiftConfigVO> scheduleWindowShifts, Date time) {
        if (CollectionUtils.isEmpty(scheduleWindowShifts) || Objects.isNull(time)) {
            return -1;
        }
        for (LhShiftConfigVO shift : scheduleWindowShifts) {
            if (Objects.isNull(shift) || Objects.isNull(shift.getShiftIndex())) {
                continue;
            }
            Date start = shift.getShiftStartDateTime();
            Date end = shift.getShiftEndDateTime();
            if (Objects.nonNull(start) && Objects.nonNull(end) && !time.before(start) && time.before(end)) {
                return shift.getShiftIndex();
            }
        }
        return -1;
    }

    private static Date resolveFirstPlannedShiftStartTime(LhScheduleResult result) {
        if (Objects.isNull(result)) {
            return null;
        }
        for (int shiftIndex = 1; shiftIndex <= LhScheduleConstant.MAX_SHIFT_SLOT_COUNT; shiftIndex++) {
            Integer shiftPlanQty = ShiftFieldUtil.getShiftPlanQty(result, shiftIndex);
            Date shiftStartTime = ShiftFieldUtil.getShiftStartTime(result, shiftIndex);
            if (Objects.nonNull(shiftPlanQty) && shiftPlanQty > 0 && Objects.nonNull(shiftStartTime)) {
                return shiftStartTime;
            }
        }
        return null;
    }

    /**
     * 解析结果中首个有计划量的班次索引。
     *
     * @param result 排程结果
     * @return 首个生产班次索引；未找到返回 -1
     */
    private static int resolveFirstPlannedShiftIndex(LhScheduleResult result) {
        if (Objects.isNull(result)) {
            return -1;
        }
        for (int shiftIndex = 1; shiftIndex <= LhScheduleConstant.MAX_SHIFT_SLOT_COUNT; shiftIndex++) {
            Integer shiftPlanQty = ShiftFieldUtil.getShiftPlanQty(result, shiftIndex);
            Date shiftStartTime = ShiftFieldUtil.getShiftStartTime(result, shiftIndex);
            if (Objects.nonNull(shiftPlanQty) && shiftPlanQty > 0 && Objects.nonNull(shiftStartTime)) {
                return shiftIndex;
            }
        }
        return -1;
    }

    private static boolean isWindowOverlap(Date windowStartTime,
                                           Date windowEndTime,
                                           Date productionStartTime,
                                           Date productionEndTime) {
        return Objects.nonNull(windowStartTime)
                && Objects.nonNull(windowEndTime)
                && Objects.nonNull(productionStartTime)
                && Objects.nonNull(productionEndTime)
                && windowEndTime.after(windowStartTime)
                && productionEndTime.after(productionStartTime)
                && windowStartTime.before(productionEndTime)
                && windowEndTime.after(productionStartTime);
    }

    private static Date earlier(Date left, Date right) {
        if (Objects.isNull(left)) {
            return right;
        }
        if (Objects.isNull(right)) {
            return left;
        }
        return left.before(right) ? left : right;
    }

    private static Date later(Date left, Date right) {
        if (Objects.isNull(left)) {
            return right;
        }
        if (Objects.isNull(right)) {
            return left;
        }
        return left.after(right) ? left : right;
    }
}
