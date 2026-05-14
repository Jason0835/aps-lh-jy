package com.zlt.aps.lh.engine.observer.listeners;

import com.zlt.aps.lh.api.domain.dto.MachineScheduleDTO;
import com.zlt.aps.lh.api.domain.entity.LhScheduleResult;
import com.zlt.aps.lh.api.enums.EventTypeEnum;
import com.zlt.aps.lh.context.LhScheduleContext;
import com.zlt.aps.lh.engine.observer.IScheduleEventListener;
import com.zlt.aps.lh.engine.observer.ScheduleEvent;
import com.zlt.aps.lh.service.ILhPrecisionPlanService;
import com.zlt.aps.lh.util.LhScheduleTimeUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 精度计划排程日期回填监听器。
 *
 * <p>监听硫化排程完成事件，提取机台、工厂和实际排程日期，为后续精度计划回填提供批量入参。</p>
 *
 * @author APS
 */
@Slf4j
@Component
public class PrecisionPlanScheduleDateFillListener implements IScheduleEventListener {

    private static final String KEY_MACHINE_CODE = "machineCode";
    private static final String KEY_FACTORY_CODE = "factoryCode";
    private static final String KEY_SCHEDULE_DATE = "scheduleDate";

    @Resource
    private ILhPrecisionPlanService lhPrecisionPlanService;

    @Override
    public void onEvent(ScheduleEvent event) {
        if (Objects.isNull(event) || Objects.isNull(event.getContext())) {
            log.warn("精度计划排程日期回填监听跳过，事件或上下文为空");
            return;
        }
        LhScheduleContext context = event.getContext();
        List<LhScheduleResult> scheduleResults = context.getScheduleResultList();
        if (CollectionUtils.isEmpty(scheduleResults)) {
            log.info("精度计划排程日期回填监听跳过，排程结果为空，批次号: {}", context.getBatchNo());
            return;
        }

        List<Map<String, Object>> fillList = buildFillList(context, scheduleResults, context.getBatchNo());
        log.info("精度计划排程日期回填准备完成，批次号: {}，工厂: {}，原始结果数: {}，去重后待回填数: {}",
                context.getBatchNo(), context.getFactoryCode(), scheduleResults.size(), fillList.size());
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
     * 按机台、工厂、实际排程日期组装去重后的回填入参。
     *
     * @param scheduleResults 排程结果
     * @param batchNo 批次号
     * @return 回填入参列表
     */
    private List<Map<String, Object>> buildFillList(LhScheduleContext context,
                                                    List<LhScheduleResult> scheduleResults,
                                                    String batchNo) {
        Map<String, Map<String, Object>> distinctMap = new LinkedHashMap<>(scheduleResults.size());
        for (LhScheduleResult scheduleResult : scheduleResults) {
            if (Objects.isNull(scheduleResult)) {
                continue;
            }
            String machineCode = scheduleResult.getLhMachineCode();
            String factoryCode = scheduleResult.getFactoryCode();
            Date realScheduleDate = scheduleResult.getRealScheduleDate();
            if (StringUtils.isEmpty(machineCode)
                    || StringUtils.isEmpty(factoryCode)
                    || Objects.isNull(realScheduleDate)) {
                log.warn("精度计划排程日期回填跳过无效结果，批次号: {}，机台: {}，工厂: {}，实际排程日期: {}，物料编码: {}",
                        batchNo, machineCode, factoryCode, LhScheduleTimeUtil.formatDate(realScheduleDate),
                        scheduleResult.getMaterialCode());
                continue;
            }
            if (!hasMaintenancePlan(context, machineCode)) {
                log.debug("精度计划排程日期回填跳过未挂保养窗口机台，批次号: {}，机台: {}，物料编码: {}",
                        batchNo, machineCode, scheduleResult.getMaterialCode());
                continue;
            }

            String distinctKey = buildDistinctKey(machineCode, factoryCode, realScheduleDate);
            if (!distinctMap.containsKey(distinctKey)) {
                Map<String, Object> fillMap = new LinkedHashMap<>(4);
                fillMap.put(KEY_MACHINE_CODE, machineCode);
                fillMap.put(KEY_FACTORY_CODE, factoryCode);
                fillMap.put(KEY_SCHEDULE_DATE, realScheduleDate);
                distinctMap.put(distinctKey, fillMap);
            }
        }
        return new ArrayList<>(distinctMap.values());
    }

    /**
     * 构建去重键。
     *
     * @param machineCode 机台编码
     * @param factoryCode 工厂编码
     * @param realScheduleDate 实际排程日期
     * @return 去重键
     */
    private String buildDistinctKey(String machineCode, String factoryCode, Date realScheduleDate) {
        return machineCode + "_" + factoryCode + "_" + LhScheduleTimeUtil.formatDate(realScheduleDate);
    }

    /**
     * 判断机台在本次排程中是否真实挂载了精度保养窗口。
     *
     * @param scheduleResult 排程结果
     * @param machineCode 机台编码
     * @return true-已挂保养窗口；false-未挂保养窗口
     */
    private boolean hasMaintenancePlan(LhScheduleContext context, String machineCode) {
        if (Objects.isNull(context) || StringUtils.isEmpty(machineCode)) {
            return false;
        }
        if (CollectionUtils.isEmpty(context.getMachineScheduleMap())) {
            return false;
        }
        MachineScheduleDTO machine = context.getMachineScheduleMap().get(machineCode);
        return Objects.nonNull(machine) && machine.isHasMaintenancePlan();
    }
}
