package com.zlt.aps.lh.api.domain.dto;

import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 清洗计划排程日期回填项。
 *
 * <p>用于硫化排程完成后，将实际清洗日期或因 SKU 收尾未安排清洗时的收尾日期，
 * 回填到对应设备停机计划（{@code T_MDM_DEVICE_PLAN_SHUT}）的排程日期字段（{@code SCHEDULE_DATE}）。
 * 回填在排程结果落库事务内统一执行，按设备停机计划主键 id 精准更新，避免重复更新与误更新。</p>
 *
 * <p>仅以下两类场景产生回填项：</p>
 * <ul>
 *   <li>清洗成功排程：{@code scheduleDate} = 实际清洗开始时间；</li>
 *   <li>因 SKU 3 天内收尾跳过清洗：{@code scheduleDate} = 候选清洗开始日 + 剩余排产天数 N。</li>
 * </ul>
 * 超窗口上限、配置非法、最晚日期超限等未安排场景不产生回填项。
 *
 * @author APS
 */
@Data
public class CleaningScheduleDateFillItem implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 来源设备停机计划主键 id（T_MDM_DEVICE_PLAN_SHUT.ID） */
    private Long planId;
    /** 回填到设备停机计划的排程日期（实际清洗开始时间 或 收尾日期） */
    private Date scheduleDate;
    /** 清洗类型：干冰/喷砂，仅用于日志定位，不参与回填匹配 */
    private String cleanType;
    /** 机台编码，仅用于日志定位，不参与回填匹配 */
    private String machineCode;
    /** 回填原因：清洗成功 / 收尾未安排清洗 */
    private String fillReason;
}
