package com.zlt.aps.lh.util;

import com.zlt.aps.lh.api.domain.dto.MachineCleaningWindowDTO;
import com.zlt.aps.lh.api.domain.vo.LhShiftConfigVO;
import com.zlt.aps.lh.api.enums.CleaningTypeEnum;
import org.springframework.util.CollectionUtils;

import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 清洗窗口与换模/换活字块窗口重叠判定工具。
 *
 * @author APS
 */
public final class MachineCleaningOverlapUtil {

    private MachineCleaningOverlapUtil() {
    }

    /**
     * 判断切换窗口是否与任一清洗窗口严格相交。
     *
     * @param cleaningWindowList 清洗窗口列表
     * @param switchStartTime 切换开始时间
     * @param switchEndTime 切换结束时间
     * @return true-存在重叠；false-不存在重叠
     */
    public static boolean hasOverlap(List<MachineCleaningWindowDTO> cleaningWindowList,
                                     Date switchStartTime,
                                     Date switchEndTime) {
        if (CollectionUtils.isEmpty(cleaningWindowList)
                || Objects.isNull(switchStartTime)
                || Objects.isNull(switchEndTime)
                || !switchStartTime.before(switchEndTime)) {
            return false;
        }
        for (MachineCleaningWindowDTO cleaningWindow : cleaningWindowList) {
            if (isOverlap(cleaningWindow, switchStartTime, switchEndTime)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断切换窗口是否命中过“清洗阻塞切换”的组合场景。
     * <p>用于原因分析：喷砂允许命中 cleanEndTime 边界，表示切换正是被喷砂卡到结束时刻后才开始。</p>
     *
     * @param cleaningWindowList 清洗窗口列表
     * @param switchStartTime 切换开始时间
     * @param switchEndTime 切换结束时间
     * @return true-命中阻塞场景；false-未命中
     */
    public static boolean hasBlockingOverlap(List<MachineCleaningWindowDTO> cleaningWindowList,
                                             Date switchStartTime,
                                             Date switchEndTime) {
        if (CollectionUtils.isEmpty(cleaningWindowList)
                || Objects.isNull(switchStartTime)
                || Objects.isNull(switchEndTime)
                || !switchStartTime.before(switchEndTime)) {
            return false;
        }
        for (MachineCleaningWindowDTO cleaningWindow : cleaningWindowList) {
            if (isBlockingOverlap(cleaningWindow, switchStartTime, switchEndTime)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断指定清洗类型是否与目标窗口存在阻塞重叠。
     *
     * @param cleaningWindowList 清洗窗口列表
     * @param cleanType 清洗类型
     * @param switchStartTime 切换开始时间
     * @param switchEndTime 切换结束时间
     * @return true-存在指定清洗类型的重叠；false-不存在
     */
    public static boolean hasCleaningTypeBlockingOverlap(List<MachineCleaningWindowDTO> cleaningWindowList,
                                                         String cleanType,
                                                         Date switchStartTime,
                                                         Date switchEndTime) {
        if (CollectionUtils.isEmpty(cleaningWindowList)) {
            return false;
        }
        for (MachineCleaningWindowDTO cleaningWindow : cleaningWindowList) {
            if (Objects.nonNull(cleaningWindow)
                    && Objects.equals(cleanType, cleaningWindow.getCleanType())
                    && isBlockingOverlap(cleaningWindow, switchStartTime, switchEndTime)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 解析重叠窗口归属的最后一个班次索引。
     *
     * @param shifts 排程窗口班次
     * @param overlapStartTime 重叠开始时间
     * @param overlapEndTime 重叠结束时间
     * @return 最后一个重叠班次索引；未命中返回 -1
     */
    public static int resolveLastOverlapShiftIndex(List<LhShiftConfigVO> shifts,
                                                   Date overlapStartTime,
                                                   Date overlapEndTime) {
        if (CollectionUtils.isEmpty(shifts)
                || Objects.isNull(overlapStartTime)
                || Objects.isNull(overlapEndTime)
                || !overlapStartTime.before(overlapEndTime)) {
            return -1;
        }
        int lastShiftIndex = -1;
        for (LhShiftConfigVO shift : shifts) {
            if (Objects.isNull(shift)
                    || Objects.isNull(shift.getShiftStartDateTime())
                    || Objects.isNull(shift.getShiftEndDateTime())) {
                continue;
            }
            if (shift.getShiftStartDateTime().before(overlapEndTime)
                    && shift.getShiftEndDateTime().after(overlapStartTime)) {
                lastShiftIndex = shift.getShiftIndex();
            }
        }
        return lastShiftIndex;
    }

    /**
     * 判断单个清洗窗口是否与切换窗口严格相交。
     *
     * @param cleaningWindow 清洗窗口
     * @param switchStartTime 切换开始时间
     * @param switchEndTime 切换结束时间
     * @return true-存在重叠；false-不存在重叠
     */
    public static boolean isOverlap(MachineCleaningWindowDTO cleaningWindow,
                                    Date switchStartTime,
                                    Date switchEndTime) {
        if (Objects.isNull(cleaningWindow)
                || Objects.isNull(cleaningWindow.getCleanStartTime())
                || Objects.isNull(switchStartTime)
                || Objects.isNull(switchEndTime)
                || !switchStartTime.before(switchEndTime)) {
            return false;
        }
        Date cleanEndTime = resolveEffectiveCleanEndTime(cleaningWindow);
        if (Objects.isNull(cleanEndTime)
                || !cleaningWindow.getCleanStartTime().before(cleanEndTime)) {
            return false;
        }
        // 严格相交才算重叠：仅端点相接不视为命中。
        return switchStartTime.before(cleanEndTime)
                && switchEndTime.after(cleaningWindow.getCleanStartTime());
    }

    /**
     * 判断单个清洗窗口是否阻塞了切换窗口。
     * <p>喷砂允许切换开始时间与 cleanEndTime 相接，表示切换正好等待喷砂完整结束。</p>
     *
     * @param cleaningWindow 清洗窗口
     * @param switchStartTime 切换开始时间
     * @param switchEndTime 切换结束时间
     * @return true-存在阻塞；false-不存在阻塞
     */
    public static boolean isBlockingOverlap(MachineCleaningWindowDTO cleaningWindow,
                                            Date switchStartTime,
                                            Date switchEndTime) {
        if (Objects.isNull(cleaningWindow)
                || Objects.isNull(cleaningWindow.getCleanStartTime())
                || Objects.isNull(switchStartTime)
                || Objects.isNull(switchEndTime)
                || !switchStartTime.before(switchEndTime)) {
            return false;
        }
        Date cleanEndTime = resolveEffectiveCleanEndTime(cleaningWindow);
        if (Objects.isNull(cleanEndTime)
                || !cleaningWindow.getCleanStartTime().before(cleanEndTime)) {
            return false;
        }
        if (isSandBlastCleaning(cleaningWindow)) {
            return !switchStartTime.after(cleanEndTime)
                    && switchEndTime.after(cleaningWindow.getCleanStartTime());
        }
        return isOverlap(cleaningWindow, switchStartTime, switchEndTime);
    }

    /**
     * 获取清洗窗口的有效结束时间。
     *
     * @param cleaningWindow 清洗窗口
     * @return 优先取 readyTime，否则取 cleanEndTime
     */
    public static Date resolveEffectiveCleanEndTime(MachineCleaningWindowDTO cleaningWindow) {
        if (Objects.isNull(cleaningWindow)) {
            return null;
        }
        if (isSandBlastCleaning(cleaningWindow)) {
            return cleaningWindow.getCleanEndTime();
        }
        return Objects.nonNull(cleaningWindow.getReadyTime())
                ? cleaningWindow.getReadyTime() : cleaningWindow.getCleanEndTime();
    }

    /**
     * 顺延与喷砂清洗重叠的切换开始时间。
     * <p>喷砂清洗命中重叠时，切换必须等待喷砂完整结束后才能开始。</p>
     *
     * @param cleaningWindowList 清洗窗口列表
     * @param switchStartTime 候选切换开始时间
     * @param switchEndTime 候选切换结束时间
     * @return 顺延后的切换开始时间
     */
    public static Date resolveDelayedSwitchStartBySandBlast(List<MachineCleaningWindowDTO> cleaningWindowList,
                                                            Date switchStartTime,
                                                            Date switchEndTime) {
        if (CollectionUtils.isEmpty(cleaningWindowList)
                || Objects.isNull(switchStartTime)
                || Objects.isNull(switchEndTime)
                || !switchStartTime.before(switchEndTime)) {
            return switchStartTime;
        }
        long switchDurationMillis = switchEndTime.getTime() - switchStartTime.getTime();
        Date adjustedStartTime = switchStartTime;
        Date adjustedEndTime = switchEndTime;
        int maxAttempts = Math.max(cleaningWindowList.size() + 1, 4);
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            Date latestOverlapEndTime = null;
            for (MachineCleaningWindowDTO cleaningWindow : cleaningWindowList) {
                if (!isSandBlastCleaning(cleaningWindow)
                        || Objects.isNull(cleaningWindow.getCleanStartTime())
                        || Objects.isNull(cleaningWindow.getCleanEndTime())
                        || !cleaningWindow.getCleanStartTime().before(cleaningWindow.getCleanEndTime())) {
                    continue;
                }
                if (adjustedStartTime.before(cleaningWindow.getCleanEndTime())
                        && adjustedEndTime.after(cleaningWindow.getCleanStartTime())) {
                    latestOverlapEndTime = later(latestOverlapEndTime, cleaningWindow.getCleanEndTime());
                }
            }
            if (latestOverlapEndTime == null || !latestOverlapEndTime.after(adjustedStartTime)) {
                return adjustedStartTime;
            }
            adjustedStartTime = latestOverlapEndTime;
            adjustedEndTime = new Date(adjustedStartTime.getTime() + switchDurationMillis);
        }
        return adjustedStartTime;
    }

    /**
     * 过滤掉与切换窗口严格相交的清洗窗口。
     *
     * @param cleaningWindowList 清洗窗口列表
     * @param switchStartTime 切换开始时间
     * @param switchEndTime 切换结束时间
     * @return 过滤后的清洗窗口列表
     */
    public static List<MachineCleaningWindowDTO> excludeOverlapWindows(List<MachineCleaningWindowDTO> cleaningWindowList,
                                                                       Date switchStartTime,
                                                                       Date switchEndTime) {
        if (CollectionUtils.isEmpty(cleaningWindowList)) {
            return java.util.Collections.emptyList();
        }
        if (Objects.isNull(switchStartTime)
                || Objects.isNull(switchEndTime)
                || !switchStartTime.before(switchEndTime)) {
            return cleaningWindowList;
        }
        return cleaningWindowList.stream()
                .filter(cleaningWindow -> !isOverlap(cleaningWindow, switchStartTime, switchEndTime))
                .collect(Collectors.toList());
    }

    public static boolean isDryIceCleaning(MachineCleaningWindowDTO cleaningWindow) {
        return Objects.nonNull(cleaningWindow)
                && CleaningTypeEnum.DRY_ICE.getCode().equals(cleaningWindow.getCleanType());
    }

    public static boolean isSandBlastCleaning(MachineCleaningWindowDTO cleaningWindow) {
        return Objects.nonNull(cleaningWindow)
                && CleaningTypeEnum.SAND_BLAST.getCode().equals(cleaningWindow.getCleanType());
    }

    private static Date later(Date current, Date candidate) {
        if (candidate == null) {
            return current;
        }
        if (current == null || candidate.after(current)) {
            return candidate;
        }
        return current;
    }
}
