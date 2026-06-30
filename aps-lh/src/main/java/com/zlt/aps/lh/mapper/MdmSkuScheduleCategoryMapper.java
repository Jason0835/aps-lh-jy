package com.zlt.aps.lh.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zlt.aps.mdm.api.domain.entity.MdmSkuScheduleCategory;
import org.apache.ibatis.annotations.Mapper;

/**
 * SKU排产分类 Mapper（MyBatis-Plus）
 * <p>供硫化排程按主销产品判定胎胚收尾标识使用，逻辑删除由框架注解自动处理。</p>
 *
 * @author APS
 */
@Mapper
public interface MdmSkuScheduleCategoryMapper extends BaseMapper<MdmSkuScheduleCategory> {
}
