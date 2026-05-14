package com.zlt.aps.lh.service.impl;

import com.zlt.aps.lh.service.ILhPrecisionPlanService;
import com.zlt.aps.lh.util.LhScheduleTimeUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.CollectionUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class LhPrecisionPlanServiceImpl implements ILhPrecisionPlanService {

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int batchFillScheduleDate(List<Map<String, Object>> fillList) {
        if (CollectionUtils.isEmpty(fillList)) {
            log.info("批量硫化排程回填计划排程精度日期跳过，待处理数据为空");
            return 0;
        }
        log.info("开始批量硫化排程回填计划排程精度日期，共{}条", fillList.size());
        for (Map<String, Object> fillData : fillList) {
            log.info("精度计划排程日期回填占位日志，工厂: {}，机台: {}，实际排程日期: {}",
                    fillData.get("factoryCode"),
                    fillData.get("machineCode"),
                    LhScheduleTimeUtil.formatDate((java.util.Date) fillData.get("scheduleDate")));
        }
        return fillList.size();
    }

}
