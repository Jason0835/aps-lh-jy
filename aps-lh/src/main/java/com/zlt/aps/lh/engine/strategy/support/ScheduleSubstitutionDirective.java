package com.zlt.aps.lh.engine.strategy.support;

import com.zlt.aps.lh.api.domain.dto.SkuScheduleDTO;
import com.zlt.aps.lh.component.MonthPlanDateResolver;
import lombok.Data;
import org.apache.commons.lang3.StringUtils;
import org.springframework.util.CollectionUtils;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 联动置换复用新增排产主链时使用的临时排产指令。
 *
 * <p>该指令只在一次无副作用预演或一次正式提交期间存在。普通 S4.5 新增排产没有该指令，
 * 因此不会改变原候选机台、模具分配、换模、首检和班次分配语义。</p>
 *
 * <p>指令分为两类：</p>
 * <ul>
 *     <li>A 接管：指定原续作机台及原机台整套模具，不换模、不换活字块、不执行首检；</li>
 *     <li>B 迁移：预演时保留正常选机，正式提交时锁定预演命中的新机台和剩余模具，
 *     并完整执行原新增换模主链。</li>
 * </ul>
 *
 * @author APS
 */
@Data
public class ScheduleSubstitutionDirective implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 指令目标 SKU 的“物料+产品状态”复合键。 */
    private String skuKey;
    /** 正式提交指定的主机台；预演 B 迁移时为空，允许现有选机链正常选择。 */
    private String specifiedMachineCode;
    /** 本次排产允许的最早切换或直接接管时间。 */
    private Date earliestSwitchTime;
    /** A 是否直接继承原机台和模具；true 时不得占用换模、换活字块及首检资源。 */
    private boolean takeoverWithoutMouldChange;
    /** B 是否属于被置换续作尾量迁移；用于隔离历史反选和输出业务日志。 */
    private boolean continuationRelocation;
    /** B 必须在一个新物理机台精确承接的截断尾量；A 接管时为 0。 */
    private int exactScheduleQty;
    /** 不允许重新选择的机台集合，B 迁移时至少包含 A 已接管的原物理机台。 */
    private Set<String> excludedMachineCodeSet = new LinkedHashSet<String>(4);
    /**
     * 指定机台必须使用的模具号。
     *
     * <p>key 为运行态机台编码。普通机台只有一项；单控整机按 L/R 两侧分别保存，
     * 避免把同一套模具重复分配给两侧。</p>
     */
    private Map<String, List<String>> forcedMouldCodeMap =
            new LinkedHashMap<String, List<String>>(2);
    /**
     * B 首次迁移预演允许选择的剩余模具号。
     *
     * <p>该列表已由协调器排除全部占用、预占、禁用、不可用及转交给 A 的模具。
     * 新增主链只能从该列表中按候选机台模数取模，不能使用候选机台当前可释放的占用模具。</p>
     */
    private List<String> allowedRelocationMouldCodeList = new ArrayList<String>(4);

    /**
     * 判断当前 SKU 是否为本指令目标。
     *
     * @param sku 待判断 SKU
     * @return true-必须应用本指令；false-继续执行普通新增排产
     */
    public boolean matches(SkuScheduleDTO sku) {
        if (Objects.isNull(sku) || StringUtils.isEmpty(skuKey)) {
            return false;
        }
        return StringUtils.equals(skuKey, MonthPlanDateResolver.buildMaterialStatusKey(
                sku.getMaterialCode(), sku.getProductStatus()));
    }

    /**
     * 获取指定机台的强制模具号副本。
     *
     * @param machineCode 运行态机台编码
     * @return 强制模具号；未指定时返回空列表
     */
    public List<String> resolveForcedMouldCodes(String machineCode) {
        List<String> mouldCodeList = forcedMouldCodeMap.get(machineCode);
        return CollectionUtils.isEmpty(mouldCodeList)
                ? new ArrayList<String>(0) : new ArrayList<String>(mouldCodeList);
    }
}
