package com.zlt.aps.lh.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zlt.aps.lh.api.domain.entity.LhMouldChangePlan;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 模具交替计划Mapper
 *
 * @author APS
 */
@Mapper
public interface LhMouldChangePlanEntityMapper extends BaseMapper<LhMouldChangePlan> {

    /**
     * 批量插入模具交替计划
     *
     * @param list 模具交替计划列表
     * @return 插入记录数
     */
    int insertBatch(@Param("list") List<LhMouldChangePlan> list);
}
