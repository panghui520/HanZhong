package com.hanyou.brain.mapper;

import org.apache.ibatis.annotations.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hanyou.brain.entity.CityProfile;

/** 城市档案。一个城市一行 */
@Mapper
public interface CityProfileMapper extends BaseMapper<CityProfile> {
}
