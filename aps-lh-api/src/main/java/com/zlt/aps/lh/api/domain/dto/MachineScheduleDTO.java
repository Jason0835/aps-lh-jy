package com.zlt.aps.lh.api.domain.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 机台排程数据传输对象
 * <p>记录机台在排程过程中的状态</p>
 *
 * @author APS
 */
@Data
public class MachineScheduleDTO {

    /** 机台编号 */
    private String machineCode;
    /** 机台名称 */
    private String machineName;
    /** 模台数(最大模具数) */
    private int maxMoldNum;
    /** 机台状态 */
    private String status;
    /** 寸口范围下限 */
    private BigDecimal dimensionMinimum;
    /** 寸口范围上限 */
    private BigDecimal dimensionMaximum;
    /** 模套型号 */
    private String mouldSetCode;
    /** 机台排序号 */
    private int machineOrder;

    // ========== 在产信息 ==========
    /** 当前在产物料编码 */
    private String currentMaterialCode;
    /** 当前在产物料描述 */
    private String currentMaterialDesc;
    /** 前规格物料编码（换模前机台当前在机物料编码） */
    private String previousMaterialCode;
    /** 前规格物料描述（换模前机台当前在机物料描述） */
    private String previousMaterialDesc;
    /** 前规格(用于换模匹配) */
    private String previousSpecCode;
    /** 前规格英寸 */
    private String previousProSize;

    // ========== 收尾信息 ==========
    /** 是否即将收尾 */
    private boolean ending;
    /** 预计收尾时间 */
    private Date estimatedEndTime;
    /** 收尾后下一个SKU */
    private String nextMaterialCode;

    // ========== 产能信息 ==========
    /** 各班次剩余产能: 按班次索引(0-8对应9个班次) */
    private int[] shiftRemainingCapacity = new int[9];
    /** 各班次是否可用(考虑停机/清洗等) */
    private boolean[] shiftAvailable = new boolean[9];

    // ========== 设备停机信息 ==========
    /** 计划停机开始时间 */
    private Date planStopStartTime;
    /** 计划停机结束时间 */
    private Date planStopEndTime;
    /** 停机类型 */
    private String stopType;

    // ========== 清洗计划 ==========
    /** 是否有干冰清洗计划 */
    private boolean hasDryIceCleaning;
    /** 是否有喷砂清洗计划 */
    private boolean hasSandBlastCleaning;
    /** 清洗计划时间 */
    private Date cleaningPlanTime;
    /** 清洗时间窗口明细 */
    private List<MachineCleaningWindowDTO> cleaningWindowList = new ArrayList<>();

    // ========== 保养/维修 ==========
    /** 是否有保养计划 */
    private boolean hasMaintenancePlan;
    /** 保养计划时间 */
    private Date maintenancePlanTime;
    /** 保养时间窗口明细 */
    private List<MachineMaintenanceWindowDTO> maintenanceWindowList = new ArrayList<>();
    /** 是否有维修计划 */
    private boolean hasRepairPlan;
    /** 维修计划时间 */
    private Date repairPlanTime;

    // ========== 胶囊信息 ==========
    /** 胶囊已使用次数 */
    private int capsuleUsageCount;
    /** 胶囊已使用次数2(双模) */
    private int capsuleUsageCount2;

    // ========== 换模记录 ==========
    /** 已分配的换模任务列表 */
    private List<Object> mouldChangeTasks = new ArrayList<>();
}
