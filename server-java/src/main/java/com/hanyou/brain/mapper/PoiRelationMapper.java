package com.hanyou.brain.mapper;

import org.apache.ibatis.annotations.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hanyou.brain.entity.PoiRelation;

/** 资源关系。详情页的周边/配套/分流全部从这里查 */
@Mapper
public interface PoiRelationMapper extends BaseMapper<PoiRelation> {
}
