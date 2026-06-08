package com.zlt.aps.lh.engine.strategy.support;

import lombok.Data;

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 单台机台在当前SKU窗口内的可生产段。
 * <p>由新增排产在完成换模、首检和开产时间计算后构造，
 * 用于统一描述一台候选机台从开产班次到窗口结束的最大产能、班次产能图和多机台角色。</p>
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
    /** 从开产班次到窗口结束的最大可排量，已考虑换模、首检、停机、清洗、保养后的有效窗口 */
    private int maxQtyToWindowEnd;
    /** 运行态单班产能，单控拆分机台会按 L/R 单侧口径折算 */
    private int shiftCapacity;
    /** 各班次最大可排产能，key 为 class1～class8 对应的 shiftIndex */
    private Map<Integer, Integer> shiftCapacityMap = new LinkedHashMap<Integer, Integer>(8);
    /** 是否需要换模，新增规格通常为 true，续作/换活字块按各自策略决定 */
    private boolean needChangeover;
    /** 机台排产角色，用于区分非最后机台满排、尾机台收口等多机台拆量口径 */
    private MachineScheduleRole role;
    /** 小额欠产允许滚动时，本机台完成后不再继续追加机台 */
    private boolean stopAfterCurrentForSmallShortage;
    /** 因窗口后第一天日计划推导出的保留机台数 */
    private int futureDayDemandMachineCount;
}
