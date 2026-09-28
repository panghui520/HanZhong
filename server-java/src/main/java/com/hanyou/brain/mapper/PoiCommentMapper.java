package com.hanyou.brain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hanyou.brain.entity.PoiComment;

import org.apache.ibatis.annotations.Mapper;

/**
 * 景点评论（M10 续）。
 *
 * <p>查询条件用 Wrapper 表达，无需自定义 SQL —— 详情页那条
 * "按 poi_id + status 取、时间倒序"是标准的三段条件，用
 * {@code LambdaQueryWrapper} 写出来比一段 XML 更容易和实体对上。
 */
@Mapper
public interface PoiCommentMapper extends BaseMapper<PoiComment> {
}
