package com.zlt.aps.lh.service.impl;

import com.zlt.aps.lh.api.domain.entity.LhPrecisionPlan;
import com.zlt.aps.lh.mapper.LhPrecisionPlanMapper;
import com.zlt.aps.lh.service.ILhPrecisionPlanService;
import com.zlt.aps.lh.util.LhScheduleTimeUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.CollectionUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Slf4j
@Service
public class LhPrecisionPlanServiceImpl implements ILhPrecisionPlanService {

    /** 回填参数：精准计划主键 */
    private static final String KEY_PRECISION_PLAN_ID = "precisionPlanId";
    /** 回填参数：APS 实际安排的自然日 */
    private static final String KEY_SCHEDULE_DATE = "scheduleDate";

    @Resource
    private LhPrecisionPlanMapper lhPrecisionPlanMapper;

    @Override
    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRES_NEW)
    public int batchFillScheduleDate(List<Map<String, Object>> fillList) {
        if (CollectionUtils.isEmpty(fillList)) {
            log.info("批量硫化精准计划安排日期回填跳过，待处理数据为空");
            return 0;
        }
        int filledCount = 0;
        log.info("开始批量回填硫化精准计划安排日期，共{}条", fillList.size());
        for (Map<String, Object> fillData : fillList) {
            Long precisionPlanId = (Long) fillData.get(KEY_PRECISION_PLAN_ID);
            Date scheduleDate = (Date) fillData.get(KEY_SCHEDULE_DATE);
            if (Objects.isNull(precisionPlanId) || Objects.isNull(scheduleDate)) {
                log.warn("硫化精准计划安排日期回填跳过无效数据, 计划ID: {}, 工厂: {}, 机台: {}, 安排日期: {}",
                        precisionPlanId, fillData.get("factoryCode"), fillData.get("machineCode"),
                        LhScheduleTimeUtil.formatDateTime(scheduleDate));
                continue;
            }
            // APS 只回填自然日口径的 SCHEDULE_DATE，不修改 MES/设备侧维护的 ACTUAL_DATE 和 COMPLETION_STATUS。
            LhPrecisionPlan updatePlan = new LhPrecisionPlan();
            updatePlan.setId(precisionPlanId);
            updatePlan.setScheduleDate(LhScheduleTimeUtil.clearTime(scheduleDate));
            int affectedRows = lhPrecisionPlanMapper.updateById(updatePlan);
            filledCount += affectedRows;
            log.info("硫化精准计划安排日期回填完成，计划ID: {}，工厂: {}，机台: {}，安排自然日: {}，影响行数: {}",
                    precisionPlanId,
                    fillData.get("factoryCode"),
                    fillData.get("machineCode"),
                    LhScheduleTimeUtil.formatDate(scheduleDate), affectedRows);
        }
        return filledCount;
    }

}
