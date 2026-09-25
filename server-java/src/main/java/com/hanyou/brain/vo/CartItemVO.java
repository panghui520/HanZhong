package com.hanyou.brain.vo;

import java.math.BigDecimal;

import lombok.Data;

/**
 * 购物车行对外视图（M6）。
 *
 * <p>把商品信息一起拼好，而不是只给 {@code productId} 让前端再逐个去查：
 * 购物车一次通常只有几行，服务端一次批量查询就能补齐；
 * 让前端循环发请求既慢又容易在某个商品下架时出现"一行空白"。
 *
 * <p>{@code experienceName} / {@code poiName} 是必须给前端看的 ——
 * "这一款来自哪次体验"是本项目区别于普通电商的核心信息，
 * 购物车里丢掉它，购物车就真的变成电商购物车了。
 *
 * <p>{@code unitPrice} 与 {@code subtotal} 都按**当前**价格算（不是下单快照）：
 * 购物车展示的应该是现在买要多少钱。快照只发生在下单那一刻。
 */
@Data
public class CartItemVO {

    /** 购物车行 id（不是商品 id）。改数量、删除都用它 */
    private Long id;

    private String productId;

    /**
     * 该商品当前是否可购买。
     *
     * <p>为 false 时说明商品已下架或已不属于当前城市的数据包，
     * 上面那些商品字段全为 null。**不静默删掉这一行** ——
     * 用户会以为"我明明加过"，然后反复加购反复失败。显式告诉他能删掉更好。
     */
    private Boolean available;

    private String name;
    private String spec;
    private String scene;

    /** 当前单价 */
    private BigDecimal unitPrice;

    private Integer quantity;

    /** 当前小计 = unitPrice × quantity */
    private BigDecimal subtotal;

    /** 当前库存，前端据此禁用"+"按钮，不用等到下单才报错 */
    private Integer stock;

    /** 体验锚点 */
    private String experienceId;
    private String experienceName;

    /** 产地锚点。前端把它交给 PoiImage 组件取该乡村的封面图 */
    private String poiId;
    private String poiName;
}
