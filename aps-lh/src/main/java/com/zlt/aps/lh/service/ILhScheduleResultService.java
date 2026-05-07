package com.zlt.aps.lh.service;

import com.zlt.aps.lh.api.domain.entity.LhScheduleResult;

import java.util.Date;
import java.util.List;

/**
 * 硫化排程结果服务接口
 *
 * @author APS
 */
public interface ILhScheduleResultService {

    /**
     * 根据排程日期和工厂查询排程结果
     *
     * @param scheduleDate 排程日期
     * @param factoryCode  分厂编号
     * @return 排程结果列表
     */
    List<LhScheduleResult> selectByDateAndFactory(Date scheduleDate, String factoryCode);

    /**
     * 查询前日排程结果
     *
     * @param scheduleDate 排程日期
     * @param factoryCode  分厂编号
     * @return 前日排程结果列表
     */
    List<LhScheduleResult> selectPreviousSchedule(Date scheduleDate, String factoryCode);

    /**
     * 根据排程日期和工厂删除排程结果（仅删除 {@code isDelete = 0} 的记录）
     *
     * @param scheduleDate 排程日期
     * @param factoryCode  分厂编号
     * @return 删除记录数
     */
    int deleteByDateAndFactory(Date scheduleDate, String factoryCode);

    /**
     * 批量插入排程结果
     *
     * @param list 排程结果列表
     * @return 插入记录数
     */
    int insertBatch(List<LhScheduleResult> list);

    /**
     * 检查排程日期是否已下发 MES：仅统计 {@code isRelease = 1}（已发布）且 {@code isDelete = 0}（{@link com.zlt.aps.lh.api.enums.DeleteFlagEnum#NORMAL}）的记录数
     *
     * @param scheduleDate 排程日期
     * @param factoryCode  分厂编号
     * @return 已发布记录数，大于 0 表示该日该厂存在已下发 MES 的排程结果
     */
    int countReleasedByDate(Date scheduleDate, String factoryCode);

    /**
     * 生成下一个排程批次号（LHPC+yyyyMMdd+流水），流水按目标排程日由 Redis 原子自增全局分配
     * （见 {@link com.zlt.aps.lh.component.LhBatchNoRedisGenerator}）
     *
     * @param scheduleDate 排程日期
     * @param factoryCode  分厂编号
     * @return 新批次号
     */
    String generateNextBatchNo(Date scheduleDate, String factoryCode);
}
