package com.zlt.aps.lh.api.domain.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 机台排程数据传输对象。
 *
 * <p>业务定位：</p>
 * <ul>
 *   <li>S4.2 根据机台台账、MES在机、停机、保养、清洗、胶囊等数据初始化；</li>
 *   <li>S4.4/S4.5 排产过程中持续更新当前在机物料、预计结束时间、班次可用状态和换模/清洗窗口；</li>
 *   <li>供机台匹配、换模均衡、首检均衡、产能计算、换活字块和结果校验共同读取。</li>
 * </ul>
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
    private String shellStandard;
    /** 支持19.5寸宽基 0-否，1-是 */
    private String support195WideBase;
    /** 支持22.5寸宽基 0-否，1-是 */
    private String support225WideBase;
    /** 支持芯片胎 0-否，1-是 */
    private String supportChipTire;
    /** 机台排序号 */
    private int machineOrder;

    // ========== 在产信息 ==========
    /** 当前在产物料编码，来源于 MES 在机或排程运行态更新，是续作和换活字块判断的基础 */
    private String currentMaterialCode;
    /** 当前在产物料描述 */
    private String currentMaterialDesc;
    /** 前规格物料编码（换模前机台当前在机物料编码），用于生成换模计划前规格信息 */
    private String previousMaterialCode;
    /** 前规格物料描述（换模前机台当前在机物料描述） */
    private String previousMaterialDesc;
    /** 前规格(用于换模匹配) */
    private String previousSpecCode;
    /** 前规格英寸 */
    private String previousProSize;

    // ========== 收尾信息 ==========
    /** 是否即将收尾；续作/换活字块会用该标识判断机台是否可以衔接下一规格 */
    private boolean ending;
    /** 预计收尾时间 */
    private Date estimatedEndTime;
    /** 收尾后下一个SKU */
    private String nextMaterialCode;

    // ========== 产能信息 ==========
    /** 各班次剩余产能: 按班次索引(0-8对应9个班次)，实际业务班次从1开始使用 */
    private int[] shiftRemainingCapacity = new int[9];
    /** 各班次是否可用(考虑工作日历、开停产、停机、清洗等约束) */
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
    /** 是否维修后强制换模/换活字块（05-计划性维修为下机维修，维修结束后不能直接续产，必须换模或换活字块） */
    private boolean forceChangeoverAfterRepair;

    // ========== 胶囊信息 ==========
    /** 胶囊已使用次数 */
    private int capsuleUsageCount;
    /** 胶囊已使用次数2(双模) */
    private int capsuleUsageCount2;

    // ========== 换模记录 ==========
    /** 已分配的换模任务列表 */
    private List<Object> mouldChangeTasks = new ArrayList<>();
}
