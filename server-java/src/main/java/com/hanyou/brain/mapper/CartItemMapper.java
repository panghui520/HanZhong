package com.hanyou.brain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hanyou.brain.entity.CartItem;

import org.apache.ibatis.annotations.Mapper;

/** 购物车行（M6）。查询条件用 Wrapper 表达，无需自定义 SQL。 */
@Mapper
public interface CartItemMapper extends BaseMapper<CartItem> {
}
