/**
 * Copyright (c) 2008, 智立通（厦门）科技有限公司 All rights reserved。
 */
package com.zlt.aps.lh.engine.strategy.support;

import com.zlt.aps.lh.api.domain.dto.MachineScheduleDTO;
import org.apache.commons.lang3.StringUtils;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * 新增规格单个SKU候选机台缓存上下文。
 *
 * <p>本类只承载一次SKU选机过程内的短生命周期缓存，不跨SKU、不跨排程上下文复用，
 * 避免机台运行态、日计划账本或换模首检资源变化后复用旧结果。</p>
 *
 * @author APS
 */
public class NewSpecCandidateCache {

    /** 单控候选机台 */
    private final List<MachineScheduleDTO> singleControlCandidates;
    /** 普通候选机台 */
    private final List<MachineScheduleDTO> normalCandidates;
    /** 候选机台窗口可用产能缓存，key=machineCode */
    private final Map<String, Integer> candidateWindowCapacityMap;
    /** 反向匹配推荐机台编码，由单控机台反向匹配链路设置，优先于排序选择 */
    private String preferredMachineCode;

    private NewSpecCandidateCache(List<MachineScheduleDTO> singleControlCandidates,
                                  List<MachineScheduleDTO> normalCandidates,
                                  int candidateCount) {
        this.singleControlCandidates = singleControlCandidates;
        this.normalCandidates = normalCandidates;
        this.candidateWindowCapacityMap = new HashMap<String, Integer>(Math.max(1, candidateCount));
    }

    /**
     * 按单控/普通机台拆分候选列表。
     *
     * @param candidates 原候选机台
     * @param singleControlPredicate 单控机台判断
     * @return 当前SKU候选机台缓存上下文
     */
    public static NewSpecCandidateCache from(List<MachineScheduleDTO> candidates,
                                             Predicate<MachineScheduleDTO> singleControlPredicate) {
        int candidateCount = CollectionUtils.isEmpty(candidates) ? 0 : candidates.size();
        int singleControlCandidateCapacity = candidateCount <= 1 ? candidateCount : Math.max(1, candidateCount / 2);
        List<MachineScheduleDTO> singleControlCandidates =
                new ArrayList<MachineScheduleDTO>(singleControlCandidateCapacity);
        List<MachineScheduleDTO> normalCandidates =
                new ArrayList<MachineScheduleDTO>(candidateCount);
        if (!CollectionUtils.isEmpty(candidates)) {
            for (MachineScheduleDTO candidate : candidates) {
                if (candidate == null || StringUtils.isEmpty(candidate.getMachineCode())) {
                    continue;
                }
                if (singleControlPredicate != null && singleControlPredicate.test(candidate)) {
                    singleControlCandidates.add(candidate);
                    continue;
                }
                normalCandidates.add(candidate);
            }
        }
        return new NewSpecCandidateCache(singleControlCandidates, normalCandidates, candidateCount);
    }

    /**
     * 从候选分组中移除已排除机台。
     *
     * @param machineCode 已排除机台编码
     */
    public void removeMachine(String machineCode) {
        if (StringUtils.isEmpty(machineCode)) {
            return;
        }
        removeMachine(singleControlCandidates, machineCode);
        removeMachine(normalCandidates, machineCode);
    }

    /**
     * 机台运行态变化后清空产能缓存。
     */
    public void clearCapacityCache() {
        candidateWindowCapacityMap.clear();
    }

    /**
     * 获取单控候选机台。
     *
     * @return 单控候选机台
     */
    public List<MachineScheduleDTO> getSingleControlCandidates() {
        return singleControlCandidates;
    }

    /**
     * 获取普通候选机台。
     *
     * @return 普通候选机台
     */
    public List<MachineScheduleDTO> getNormalCandidates() {
        return normalCandidates;
    }

    /**
     * 获取反向匹配推荐机台编码。
     *
     * @return 推荐机台编码；未设置时返回null
     */
    public String getPreferredMachineCode() {
        return preferredMachineCode;
    }

    /**
     * 设置反向匹配推荐机台编码。
     * <p>单控机台反向匹配链路在试制/量试/小批量SKU排上一侧后,
     * 为配对侧查找匹配SKU时设置,使该SKU在选机时优先选择配对侧。</p>
     *
     * @param preferredMachineCode 推荐机台编码
     */
    public void setPreferredMachineCode(String preferredMachineCode) {
        this.preferredMachineCode = preferredMachineCode;
    }

    /**
     * 获取窗口产能缓存值。
     *
     * @param machineCode 机台编码
     * @return 产能缓存值
     */
    public Integer getCandidateWindowCapacity(String machineCode) {
        return candidateWindowCapacityMap.get(machineCode);
    }

    /**
     * 写入窗口产能缓存。
     *
     * @param machineCode 机台编码
     * @param capacityQty 窗口产能
     */
    public void putCandidateWindowCapacity(String machineCode, int capacityQty) {
        if (StringUtils.isEmpty(machineCode)) {
            return;
        }
        candidateWindowCapacityMap.put(machineCode, capacityQty);
    }

    /**
     * 从指定候选分组移除机台。
     *
     * @param candidates 候选机台分组
     * @param machineCode 已排除机台编码
     */
    private void removeMachine(List<MachineScheduleDTO> candidates, String machineCode) {
        if (CollectionUtils.isEmpty(candidates)) {
            return;
        }
        Iterator<MachineScheduleDTO> iterator = candidates.iterator();
        while (iterator.hasNext()) {
            MachineScheduleDTO candidate = iterator.next();
            if (candidate != null && StringUtils.equals(machineCode, candidate.getMachineCode())) {
                iterator.remove();
            }
        }
    }
}
