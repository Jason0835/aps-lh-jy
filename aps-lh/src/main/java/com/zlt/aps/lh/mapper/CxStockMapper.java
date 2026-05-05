package com.zlt.aps.lh.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zlt.aps.cx.api.domain.entity.CxStock;
import org.apache.ibatis.annotations.Mapper;

/**
 * 胎胚实时库存 Mapper（MyBatis-Plus）。
 */
@Mapper
public interface CxStockMapper extends BaseMapper<CxStock> {
}
