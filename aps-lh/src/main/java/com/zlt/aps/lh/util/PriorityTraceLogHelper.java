package com.zlt.aps.lh.util;

import com.zlt.aps.lh.api.domain.entity.LhScheduleProcessLog;
import com.zlt.aps.lh.context.LhScheduleConfig;
import com.zlt.aps.lh.context.LhScheduleContext;
import org.apache.commons.lang3.StringUtils;

import java.util.Collection;
import java.util.Date;
import java.util.List;

/**
 * 优先级跟踪日志辅助工具。
 *
 * @author APS
 */
public final class PriorityTraceLogHelper {

    /** 候选机台日志默认输出前N名 */
    public static final int MACHINE_TRACE_TOP_N = 5;

    /** 日志分隔线 */
    private static final String SEPARATOR_LINE = "======================================";

    private PriorityTraceLogHelper() {
    }

    /**
     * 判断当前排程是否开启优先级跟踪日志。
     *
     * @param context 排程上下文
     * @return true-开启，false-关闭
     */
    public static boolean isEnabled(LhScheduleContext context) {
        if (context == null) {
            return false;
        }
        LhScheduleConfig scheduleConfig = context.getScheduleConfig();
        return scheduleConfig != null
                && scheduleConfig.isPriorityTraceLogEnabled()
                && !context.isPriorityTraceMuted();
    }

    /**
     * 同时写应用日志和过程日志明细时使用的安全文本。
     *
     * @param value 原始值
     * @return 展示文本
     */
    public static String safeText(Object value) {
        return value == null ? "-" : String.valueOf(value);
    }

    /**
     * 布尔值转展示文本。
     *
     * @param flag 布尔值
     * @return 是/否
     */
    public static String yesNo(boolean flag) {
        return flag ? "是" : "否";
    }

    /**
     * 格式化日期时间，空值输出"-"。
     *
     * @param date 日期
     * @return 文本
     */
    public static String formatDateTime(Date date) {
        return date == null ? "-" : LhScheduleTimeUtil.formatDateTime(date);
    }

    /**
     * 拼接文本行。
     *
     * @param builder 文本构建器
     * @param line 行内容
     */
    public static void appendLine(StringBuilder builder, String line) {
        if (builder == null || StringUtils.isEmpty(line)) {
            return;
        }
        builder.append(line).append('\n');
    }

    /**
     * 输出集合大小文本。
     *
     * @param collection 集合
     * @return 数量文本
     */
    public static int sizeOf(Collection<?> collection) {
        return collection == null ? 0 : collection.size();
    }

    /**
     * 向排程过程日志列表追加一条日志。
     *
     * @param context 排程上下文
     * @param title 标题
     * @param detail 明细
     */
    public static void appendProcessLog(LhScheduleContext context, String title, String detail) {
        if (context == null || StringUtils.isEmpty(title) || StringUtils.isEmpty(detail)) {
            return;
        }
        LhScheduleProcessLog processLog = new LhScheduleProcessLog();
        processLog.setBatchNo(context.getBatchNo());
        processLog.setTitle(title);
        processLog.setBusiCode(context.getFactoryCode());
        processLog.setLogDetail(detail);
        processLog.setIsDelete(0);
        context.getScheduleLogList().add(processLog);
    }

    // ==================== 排序汇总日志工具方法 ====================

    /**
     * 输出日志标题头，格式：========== {title} ==========
     *
     * @param builder 文本构建器
     * @param title 标题
     */
    public static void appendTitleHeader(StringBuilder builder, String title) {
        if (builder == null || StringUtils.isEmpty(title)) {
            return;
        }
        builder.append("========== ").append(title).append(" ==========\n");
    }

    /**
     * 输出日志结尾分隔线。
     *
     * @param builder 文本构建器
     */
    public static void appendTitleFooter(StringBuilder builder) {
        if (builder == null) {
            return;
        }
        builder.append(SEPARATOR_LINE).append('\n');
    }

    /**
     * 拼接键值对，格式：key=value
     *
     * @param key 键
     * @param value 值
     * @return 拼接结果
     */
    public static String kv(String key, Object value) {
        return key + "=" + safeText(value);
    }

    /**
     * 拼接 SortKey 文本，格式：[L1=xxx,L2=xxx,...]
     *
     * @param levels 排序层级键值对，按优先级从高到低排列
     * @return SortKey文本
     */
    public static String formatSortKey(List<String> levels) {
        if (levels == null || levels.isEmpty()) {
            return "[]";
        }
        return "[" + String.join(",", levels) + "]";
    }

    /**
     * 从排序层级得分列表中推导命中层级说明。
     * <p>遍历层级得分，找到第一个非默认得分（即第一个产生区分的层级）作为命中层级。</p>
     *
     * @param levelNames 层级名称列表
     * @param scores 层级得分列表
     * @param defaultScores 各层级默认得分列表（与层级一一对应，表示"未命中"时的得分）
     * @return 命中层级说明
     */
    public static String resolveHitLevel(List<String> levelNames, List<Integer> scores, List<Integer> defaultScores) {
        if (levelNames == null || scores == null || defaultScores == null
                || levelNames.size() != scores.size() || scores.size() != defaultScores.size()) {
            return "-";
        }
        for (int i = 0; i < scores.size(); i++) {
            if (!scores.get(i).equals(defaultScores.get(i))) {
                return "命中" + levelNames.get(i);
            }
        }
        return "兜底排序";
    }

    /**
     * 输出排序汇总日志（同时写应用日志和过程日志）。
     * <p>调用方需显式传入自身的 SLF4J Logger 对象（如 {@code log}），因为静态工具方法无法自动获取调用类的 Logger。</p>
     *
     * @param log SLF4J日志对象
     * @param context 排程上下文（可能为null，为null时只写应用日志）
     * @param title 日志标题
     * @param detail 日志明细
     */
    public static void logSortSummary(org.slf4j.Logger log, LhScheduleContext context, String title, String detail) {
        if (StringUtils.isEmpty(detail)) {
            return;
        }
        log.info("{}\n{}", title, detail);
        if (context != null) {
            appendProcessLog(context, title, detail);
        }
    }
}
