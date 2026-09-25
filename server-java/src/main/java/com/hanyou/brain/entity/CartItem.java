package com.hanyou.brain.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 购物车行（M6 消费与离境复购）。
 *
 * <p>本表**刻意没有 cityCode**：CityPackImporter 每次启动按 city_code 全量删除重灌
 * 业务表，而购物车是运行期数据，不能因为重启一次服务就被清空。
 *
 * <p>一个用户 + 一个商品 = 一行（库里有 uk_user_product 唯一键）。
 * 加购是数量累加，不是插新行 —— 否则同一件东西加购三次会出现三行，
 * 页面上看起来像三个不同商品。
 *
 * <p>刻意**不存价格**：价格随时可能变，购物车该显示"现在的价格"；
 * 价格只在**下单那一刻**被快照进 {@link OrderItem}。
 */
@Data
@TableName("cart_item")
public class CartItem {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属用户，-> app_user.id。购物车必须登录才有，不做匿名车 */
    private Long userId;

    /** -> product.id */
    private String productId;

    /** 数量，最小 1 */
    private Integer quantity;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
