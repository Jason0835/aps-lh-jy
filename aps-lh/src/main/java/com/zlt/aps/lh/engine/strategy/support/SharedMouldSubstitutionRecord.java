package com.zlt.aps.lh.engine.strategy.support;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * SKU 共用模具联动置换成功记录。
 *
 * <p>记录只保存在本次排程上下文中，不新增数据库表。S4.6 仍根据 A、B 的实际排程结果生成
 * 原有结果和换模计划；本记录与过程日志共同提供置换前后机台、模具和时间轴的审计信息。</p>
 *
 * @author APS
 */
@Data
public class SharedMouldSubstitutionRecord implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 接管物料 A 编码。 */
    private String targetMaterialCode;
    /** 接管物料 A 产品状态。 */
    private String targetProductStatus;
    /** 被迁移续作物料 B 编码。 */
    private String continuationMaterialCode;
    /** 被迁移续作物料 B 产品状态。 */
    private String continuationProductStatus;
    /** A 日计划首次出现正计划量的来源日期。 */
    private LocalDate firstPositivePlanDate;
    /** 本次由 B 转交给 A 的整套共用模具号。 */
    private List<String> transferredMouldCodeList = new ArrayList<String>(2);
    /** B 迁移后使用的剩余模具号。 */
    private List<String> relocationMouldCodeList = new ArrayList<String>(2);
    /** B 原续作物理机台编码。 */
    private String originalPhysicalMachineCode;
    /** A 实际接管的运行态机台编码。 */
    private String takeoverMachineCode;
    /** B 重新选中的运行态机台编码。 */
    private String relocationMachineCode;
    /** B 从原续作机台下机时间。 */
    private Date continuationOfflineTime;
    /** A 实际开产及接管时间。 */
    private Date targetTakeoverTime;
    /** B 在新机台的换模开始时间。 */
    private Date relocationMouldChangeTime;
    /** B 在新机台重新开产时间。 */
    private Date relocationProductionStartTime;
    /** B 从原续作机台截断并在新机台完整承接的数量。 */
    private int relocatedQty;
}
