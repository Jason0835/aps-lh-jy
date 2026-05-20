package com.zlt.aps.lh.engine.strategy.support;

import lombok.Data;

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 单台机台在当前SKU窗口内的可生产段。
 *
 * @author APS
 */
@Data
public class MachineProductionSegment implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 机台编码 */
    private String machineCode;
    /** 物料编码 */
    private String materialCode;
    /** 胎胚分组Key */
    private String greenTireGroupKey;
    /** 换模班次索引 */
    private int changeoverShiftIndex;
    /** 开产班次索引 */
    private int startProductionShiftIndex;
    /** 从开产班次到窗口结束的最大可排量 */
    private int maxQtyToWindowEnd;
    /** 运行态单班产能 */
    private int shiftCapacity;
    /** 各班次最大可排产能 */
    private Map<Integer, Integer> shiftCapacityMap = new LinkedHashMap<Integer, Integer>(8);
    /** 是否需要换模 */
    private boolean needChangeover;
    /** 机台排产角色 */
    private MachineScheduleRole role;
}
