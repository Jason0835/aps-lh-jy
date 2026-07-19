package com.zlt.aps.lh.engine.observer.listeners;

import com.zlt.aps.lh.api.domain.dto.MachineMaintenanceWindowDTO;
import com.zlt.aps.lh.api.domain.dto.MachineScheduleDTO;
import com.zlt.aps.lh.api.enums.EventTypeEnum;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.engine.observer.IScheduleEventListener;
import com.zlt.aps.lh.engine.observer.ScheduleEvent;
import com.zlt.aps.lh.service.ILhPrecisionPlanService;
import com.zlt.aps.lh.util.LhScheduleTimeUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 精度计划排程日期回填监听器。
 *
 * <p>监听硫化排程完成事件，直接遍历本次实际挂载的保养窗口，按计划主键回填最终保养开始时间。
 * 即使保养后没有后续 SKU，也不会因为排程结果为空而漏掉已经安排的精准计划。</p>
 *
 * @author APS
 */
@Slf4j
@Component
public class PrecisionPlanScheduleDateFillListener implements IScheduleEventListener {

    private static final String KEY_MACHINE_CODE = "machineCode";
    private static final String KEY_FACTORY_CODE = "factoryCode";
    private static final String KEY_SCHEDULE_DATE = "scheduleDate";
    private static final String KEY_PRECISION_PLAN_ID = "precisionPlanId";

    @Resource
    private ILhPrecisionPlanService lhPrecisionPlanService;

    @Override
    public void onEvent(ScheduleEvent event) {
        if (Objects.isNull(event) || Objects.isNull(event.getContext())) {
            log.warn("精度计划排程日期回填监听跳过，事件或上下文为空");
            return;
        }
        LhScheduleContext context = event.getContext();
        List<Map<String, Object>> fillList = buildFillList(context, context.getBatchNo());
        log.info("精度计划排程日期回填准备完成，批次号: {}，工厂: {}，机台数: {}，去重后待回填数: {}",
                context.getBatchNo(), context.getFactoryCode(), context.getMachineScheduleMap().size(), fillList.size());
        if (CollectionUtils.isEmpty(fillList)) {
            return;
        }

        int filledCount = lhPrecisionPlanService.batchFillScheduleDate(fillList);
        log.info("精度计划排程日期回填服务调用完成，批次号: {}，工厂: {}，请求条数: {}，返回条数: {}",
                context.getBatchNo(), context.getFactoryCode(), fillList.size(), filledCount);
    }

    @Override
    public boolean supports(EventTypeEnum eventType) {
        return eventType == EventTypeEnum.SCHEDULE_COMPLETED;
    }

    /**
     * 按精准计划主键组装去重后的回填入参。
     *
     * @param context 排程上下文
     * @param batchNo 批次号
     * @return 回填入参列表
     */
    private List<Map<String, Object>> buildFillList(LhScheduleContext context, String batchNo) {
        if (Objects.isNull(context) || CollectionUtils.isEmpty(context.getMachineScheduleMap())) {
            return new ArrayList<>();
        }
        Map<Long, Map<String, Object>> distinctMap = new LinkedHashMap<>(context.getMachineScheduleMap().size());
        for (MachineScheduleDTO machine : context.getMachineScheduleMap().values()) {
            if (Objects.isNull(machine) || CollectionUtils.isEmpty(machine.getMaintenanceWindowList())) {
                continue;
            }
            for (MachineMaintenanceWindowDTO window : machine.getMaintenanceWindowList()) {
                if (Objects.isNull(window) || Objects.isNull(window.getPrecisionPlanId())
                        || Objects.isNull(window.getMaintenanceStartTime())) {
                    log.warn("精度计划安排日期回填跳过无计划主键窗口，批次号: {}，机台: {}，保养开始: {}",
                            batchNo, machine.getMachineCode(),
                            LhScheduleTimeUtil.formatDateTime(
                                    Objects.nonNull(window) ? window.getMaintenanceStartTime() : null));
                    continue;
                }
                if (!distinctMap.containsKey(window.getPrecisionPlanId())) {
                    Map<String, Object> fillMap = new LinkedHashMap<>(4);
                    fillMap.put(KEY_PRECISION_PLAN_ID, window.getPrecisionPlanId());
                    fillMap.put(KEY_MACHINE_CODE, machine.getMachineCode());
                    fillMap.put(KEY_FACTORY_CODE, context.getFactoryCode());
                    fillMap.put(KEY_SCHEDULE_DATE, window.getMaintenanceStartTime());
                    distinctMap.put(window.getPrecisionPlanId(), fillMap);
                }
            }
        }
        return new ArrayList<>(distinctMap.values());
    }
}
