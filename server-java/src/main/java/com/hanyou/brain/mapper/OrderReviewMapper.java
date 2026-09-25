package com.hanyou.brain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hanyou.brain.entity.OrderReview;

import org.apache.ibatis.annotations.Mapper;

/**
 * 订单评价（M6）。查询条件用 Wrapper 表达，无需自定义 SQL。
 */
@Mapper
public interface OrderReviewMapper extends BaseMapper<OrderReview> {
}
