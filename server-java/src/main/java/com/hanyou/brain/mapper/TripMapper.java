package com.hanyou.brain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hanyou.brain.entity.Trip;

import org.apache.ibatis.annotations.Mapper;

/** 一次旅行（M4）。查询条件用 Wrapper 表达，无需自定义 SQL。 */
@Mapper
public interface TripMapper extends BaseMapper<Trip> {
}
