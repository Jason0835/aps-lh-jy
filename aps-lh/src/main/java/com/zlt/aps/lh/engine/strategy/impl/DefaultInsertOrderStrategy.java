package com.zlt.aps.lh.engine.strategy.impl;

import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.api.domain.dto.MachineScheduleDTO;
import com.zlt.aps.lh.api.domain.dto.SkuScheduleDTO;
import com.zlt.aps.lh.api.domain.entity.LhSpecifyMachine;
import com.zlt.aps.lh.engine.strategy.IInsertOrderStrategy;
import com.zlt.aps.lh.util.LhSpecifyMachineUtil;
import com.zlt.aps.lh.util.MachineStatusUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 默认插单处理策略
 * <p>校验插单合法性，分配可用机台，并标记交货锁定</p>
 *
 * @author APS
 */
@Slf4j
@Component
public class DefaultInsertOrderStrategy implements IInsertOrderStrategy {

    @Override
    public List<SkuScheduleDTO> processInsertOrders(LhScheduleContext context, List<SkuScheduleDTO> insertOrders) {
        if (insertOrders == null || insertOrders.isEmpty()) {
            return new ArrayList<>();
        }
        log.info("处理插单, 数量: {}", insertOrders.size());
        List<SkuScheduleDTO> validOrders = new ArrayList<>();
        for (SkuScheduleDTO order : insertOrders) {
            if (validateInsertOrder(context, order)) {
                // 交货锁定：插单默认为锁定状态
                order.setDeliveryLocked(true);
                // 若尚未指定机台，尝试匹配
                if (order.getContinuousMachineCode() == null) {
                    String machineCode = matchInsertMachine(context, order);
                    if (machineCode != null) {
                        order.setContinuousMachineCode(machineCode);
                    }
                }
                validOrders.add(order);
                log.info("插单验证通过, 物料: {}, 匹配机台: {}", order.getMaterialCode(), order.getContinuousMachineCode());
            } else {
                log.warn("插单验证未通过，跳过, 物料: {}", order.getMaterialCode());
            }
        }
        return validOrders;
    }

    @Override
    public boolean validateInsertOrder(LhScheduleContext context, SkuScheduleDTO insertOrder) {
        if (insertOrder == null || insertOrder.getMaterialCode() == null) {
            return false;
        }
        // 校验指定机台是否可用（若有指定）
        String machineCode = insertOrder.getContinuousMachineCode();
        if (machineCode != null) {
            MachineScheduleDTO machine = context.getMachineScheduleMap().get(machineCode);
            if (machine == null || !MachineStatusUtil.isEnabled(machine.getStatus())) {
                log.warn("插单指定机台不可用, 物料: {}, 机台: {}", insertOrder.getMaterialCode(), machineCode);
                return false;
            }
        }
        // 校验该规格是否有可用模具关系
        String specCode = insertOrder.getSpecCode();
        if (specCode != null && context.getSkuMouldRelMap() != null) {
            boolean hasMould = context.getSkuMouldRelMap().containsKey(insertOrder.getMaterialCode());
            if (!hasMould) {
                log.warn("插单物料无模具关系, 物料: {}", insertOrder.getMaterialCode());
                return false;
            }
        }
        return true;
    }

    @Override
    public void adjustExistingSchedule(LhScheduleContext context, SkuScheduleDTO insertOrder) {
        // 插单时若机台已有排程，将插单排到该机台现有排程的前方（不调整连续生产的已排结果）
        log.debug("调整现有排程以容纳插单, 物料: {}, 机台: {}",
                insertOrder.getMaterialCode(), insertOrder.getContinuousMachineCode());
    }

    /**
     * 为插单匹配可用机台（优先使用指定机台列表中的第一台启用机台）
     */
    private String matchInsertMachine(LhScheduleContext context, SkuScheduleDTO insertOrder) {
        if (StringUtils.isEmpty(insertOrder.getMaterialCode())) {
            return null;
        }
        List<LhSpecifyMachine> specifyList = LhSpecifyMachineUtil.listLimitSpecifyMachinesByMaterialCode(
                context, insertOrder.getMaterialCode());
        if (CollectionUtils.isEmpty(specifyList)) {
            return null;
        }
        for (LhSpecifyMachine specify : specifyList) {
            MachineScheduleDTO machine = context.getMachineScheduleMap().get(specify.getMachineCode());
            if (machine != null && MachineStatusUtil.isEnabled(machine.getStatus())) {
                return specify.getMachineCode();
            }
        }
        return null;
    }
}
