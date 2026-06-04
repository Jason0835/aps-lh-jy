package com.zlt.aps.lh.engine.strategy.support;

import lombok.Data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 增机台模具资源分配结果。
 *
 * @author APS
 */
@Data
public class MouldResourceAllocationResult {

    /** 是否允许增加当前候选机台 */
    private boolean allowed;
    /** 当前候选机台所需模具数量 */
    private int requiredMouldQty;
    /** SKU台账启用的可用模具总数 */
    private int availableMouldQty;
    /** SKU已被新增链路占用的模具数量 */
    private int occupiedMouldQty;
    /** 当前分配前剩余可用模具数量 */
    private int remainingAvailableMouldQty;
    /** 本次分配的模具号 */
    private List<String> allocatedMouldCodeList = new ArrayList<String>(0);
    /** 不允许增加时的跳过原因 */
    private MouldResourceSkipReason skipReason;

    /**
     * 构建允许分配结果。
     *
     * @param requiredMouldQty 当前候选机台所需模具数量
     * @param availableMouldQty 可用模具总数
     * @param occupiedMouldQty 已占用模具数量
     * @param remainingAvailableMouldQty 分配后剩余可用模具数量
     * @param allocatedMouldCodeList 本次分配模具号
     * @return 分配结果
     */
    public static MouldResourceAllocationResult allowed(int requiredMouldQty,
                                                        int availableMouldQty,
                                                        int occupiedMouldQty,
                                                        int remainingAvailableMouldQty,
                                                        List<String> allocatedMouldCodeList) {
        MouldResourceAllocationResult result = new MouldResourceAllocationResult();
        result.setAllowed(true);
        result.setRequiredMouldQty(requiredMouldQty);
        result.setAvailableMouldQty(availableMouldQty);
        result.setOccupiedMouldQty(occupiedMouldQty);
        result.setRemainingAvailableMouldQty(remainingAvailableMouldQty);
        result.setAllocatedMouldCodeList(allocatedMouldCodeList == null
                ? Collections.<String>emptyList() : new ArrayList<String>(allocatedMouldCodeList));
        return result;
    }

    /**
     * 构建拒绝分配结果。
     *
     * @param requiredMouldQty 当前候选机台所需模具数量
     * @param availableMouldQty 可用模具总数
     * @param occupiedMouldQty 已占用模具数量
     * @param remainingAvailableMouldQty 剩余可用模具数量
     * @param skipReason 跳过原因
     * @return 分配结果
     */
    public static MouldResourceAllocationResult rejected(int requiredMouldQty,
                                                         int availableMouldQty,
                                                         int occupiedMouldQty,
                                                         int remainingAvailableMouldQty,
                                                         MouldResourceSkipReason skipReason) {
        MouldResourceAllocationResult result = new MouldResourceAllocationResult();
        result.setAllowed(false);
        result.setRequiredMouldQty(requiredMouldQty);
        result.setAvailableMouldQty(availableMouldQty);
        result.setOccupiedMouldQty(occupiedMouldQty);
        result.setRemainingAvailableMouldQty(remainingAvailableMouldQty);
        result.setAllocatedMouldCodeList(Collections.<String>emptyList());
        result.setSkipReason(skipReason);
        return result;
    }
}
