package com.zlt.aps.lh.service;


import java.util.List;
import java.util.Map;

/**
 * 硫化精度计划Service接口
 *
 * @author APS Team
 */
public interface ILhPrecisionPlanService {

    /**
     * 按精准计划主键批量回填 APS 最终安排日期。
     * <p>每项必须包含 precisionPlanId 和 scheduleDate；机台、工厂仅用于日志对账。</p>
     *
     * @param fillList 回填数据列表
     * @return 成功回填的数量
     */
    int batchFillScheduleDate(List<Map<String, Object>> fillList);

}
