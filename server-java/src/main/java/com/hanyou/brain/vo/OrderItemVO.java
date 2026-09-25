package com.hanyou.brain.vo;

import java.math.BigDecimal;

import lombok.Data;

/**
 * 订单明细对外视图（M6）。
 *
 * <p>这里的 {@code unitPrice} / {@code subtotal} / {@code productName} 全部来自
 * **下单时的快照**，与 {@link CartItemVO} 的"当前价格"语义正好相反。
 * 两者都叫 unitPrice，含义不同是刻意的：购物车看的是"现在多少钱"，
 * 订单看的是"当时多少钱"。产品后来调价，历史订单金额不能跟着变。
 *
 * <p>{@code experienceName} / {@code poiName} 也是快照 —— 这是本项目的红线所在：
 * 每一行订单都要能回答"这件东西来自哪次体验、哪个产地"。
 */
@Data
public class OrderItemVO {

    private Long id;
    private String productId;

    /** 体验锚点（下单时快照） */
    private String experienceId;
    private String experienceName;

    /** 产地锚点（下单时快照） */
    private String poiId;
    private String poiName;

    /** 商品名（下单时快照） */
    private String productName;

    /** 规格（下单时快照） */
    private String spec;

    /** 单价（下单时快照） */
    private BigDecimal unitPrice;

    private Integer quantity;

    /** 小计（下单时快照，服务端算） */
    private BigDecimal subtotal;
}
