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
     * 批量硫化排程回填计划排程精度日期
     * 将循环内的逐条DB查询优化为外层批量查询+内存分组匹配，逐条update优化为批量操作
     *
     * @param fillList 回填数据列表，每项包含machineCode、factoryCode、scheduleDate
     * @return 成功回填的数量
     */
    int batchFillScheduleDate(List<Map<String, Object>> fillList);

}
