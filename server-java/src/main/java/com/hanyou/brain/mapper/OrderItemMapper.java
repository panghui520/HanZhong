package com.hanyou.brain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hanyou.brain.entity.OrderItem;

import org.apache.ibatis.annotations.Mapper;

/**
 * 订单明细（M6）。查询条件用 Wrapper 表达，无需自定义 SQL。
 *
 * <p>注意别 import 到 MyBatis-Plus 的
 * {@code com.baomidou.mybatisplus.core.metadata.OrderItem} —— 那个是排序用的
 * 元数据类，与这里的实体同名不同物。本接口用的是 entity 包下的实体。
 */
@Mapper
public interface OrderItemMapper extends BaseMapper<OrderItem> {
}
