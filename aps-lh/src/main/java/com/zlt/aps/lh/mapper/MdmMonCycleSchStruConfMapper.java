package com.zlt.aps.lh.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zlt.aps.mdm.api.domain.entity.MdmMonCycleSchStruConf;
import org.apache.ibatis.annotations.Mapper;

/**
 * 月周期排产结构配置Mapper。
 *
 * <p>结构最低机台数规则通过MyBatis-Plus单表查询复用本地数据源，不新增XML查询。</p>
 *
 * @author APS
 */
@Mapper
public interface MdmMonCycleSchStruConfMapper extends BaseMapper<MdmMonCycleSchStruConf> {
}
