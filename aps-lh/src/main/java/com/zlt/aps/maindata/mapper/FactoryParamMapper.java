package com.zlt.aps.maindata.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zlt.aps.mp.api.domain.entity.FactoryParam;
import org.apache.ibatis.annotations.Mapper;

/**
 * 工厂月计划参数Mapper。
 *
 * <p>常规结构最低机台数按分厂和参数编码从本地参数表读取，不通过远程服务或默认值兜底。</p>
 *
 * @author APS
 */
@Mapper
public interface FactoryParamMapper extends BaseMapper<FactoryParam> {
}
