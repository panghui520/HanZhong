package com.hanyou.brain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hanyou.brain.entity.PoiImage;

import org.apache.ibatis.annotations.Mapper;

/** 景点配图（M9）。查询条件用 Wrapper 表达，无需自定义 SQL。 */
@Mapper
public interface PoiImageMapper extends BaseMapper<PoiImage> {
}
