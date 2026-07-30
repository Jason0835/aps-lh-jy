package com.zlt.aps.lh.engine.strategy.support;

import com.zlt.aps.lh.api.domain.dto.SkuScheduleDTO;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 单组 A/B 共用模具置换的可行性预演结果。
 *
 * <p>该对象只承载预演确认的事实，不直接修改排程上下文。正式提交必须重新使用指定机台、
 * 指定模具和精确尾量执行一次，并逐项与本计划校验；不一致时由通用快照整体回滚。</p>
 *
 * @author APS
 */
@Data
public class SharedMouldSubstitutionPlan implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 物料 A。 */
    private SkuScheduleDTO targetSku;
    /** 物料 B。 */
    private SkuScheduleDTO continuationSku;
    /** A 首个正日计划来源日期。 */
    private LocalDate firstPositivePlanDate;
    /** A 在本排程窗口内允许开始接管的目标时间。 */
    private Date takeoverTargetTime;
    /** B 实际下机时间。 */
    private Date continuationOfflineTime;
    /** B 原物理机台编码。 */
    private String originalPhysicalMachineCode;
    /** A 接管的主运行态机台编码。 */
    private String takeoverMachineCode;
    /** B 新机台主运行态编码。 */
    private String relocationMachineCode;
    /** B 从原续作结果截断的精确尾量。 */
    private int relocatedQty;
    /** 原机台各运行态侧转交给 A 的模具号。 */
    private Map<String, List<String>> transferredMouldCodeMap =
            new LinkedHashMap<String, List<String>>(2);
    /** B 正式迁移各运行态侧必须使用的剩余模具号。 */
    private Map<String, List<String>> relocationMouldCodeMap =
            new LinkedHashMap<String, List<String>>(2);
    /** 原物理机台的全部运行态机台编码，单控整机包含 L/R 两侧。 */
    private List<String> originalMachineCodeList = new ArrayList<String>(2);
    /** B 新物理机台的全部运行态机台编码。 */
    private List<String> relocationMachineCodeList = new ArrayList<String>(2);
    /** A 实际接管时间。 */
    private Date targetTakeoverTime;
    /** B 新机台换模开始时间。 */
    private Date relocationMouldChangeTime;
    /** B 新机台重新开产时间。 */
    private Date relocationProductionStartTime;
    /** 候选预演失败原因；成功计划为空。 */
    private String failureReason;
}
