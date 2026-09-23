package com.hanyou.brain.mapper;

import org.apache.ibatis.annotations.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hanyou.brain.entity.Poi;

/** 资源点。M1 只用 BaseMapper 提供的能力，复杂查询后续模块再补自定义方法 */
@Mapper
public interface PoiMapper extends BaseMapper<Poi> {
}
