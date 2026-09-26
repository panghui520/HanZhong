package com.hanyou.brain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hanyou.brain.entity.TripContext;

import org.apache.ibatis.annotations.Mapper;

/** 旅行的可变上下文（M4）。查询条件用 Wrapper 表达，无需自定义 SQL。 */
@Mapper
public interface TripContextMapper extends BaseMapper<TripContext> {
}
