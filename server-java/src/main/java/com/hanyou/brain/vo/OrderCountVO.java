package com.hanyou.brain.vo;

import lombok.Data;

/**
 * 我的订单计数（M6，顶栏角标用）。
 *
 * <p>与 {@code /api/cart/count} 同一个理由：顶栏只要一个数字，
 * 不该为此拉整张订单列表 —— 列表接口每个订单都要带上明细、评价、
 * 收货信息，为渲染一个角标付出这些代价不值得。
 *
 * <p>两个字段的分工要说清楚，否则很容易被合并成一个：
 * <ul>
 *   <li>{@code total} —— 我的全部订单数。订单页的"全部"标签用。</li>
 *   <li>{@code pending} —— **需要我动手的**订单数（待付款 + 已发货）。
 *       顶栏角标显示的是这个，不是 total。已完成的订单不需要用户做任何事，
 *       把它算进角标，角标就会只增不减，变成永远消不掉的红点。</li>
 * </ul>
 */
@Data
public class OrderCountVO {

    /** 我的全部订单数（含已取消 / 已退款这些终态） */
    private int total;

    /** 需要用户处理的订单数：待付款（要付钱）+ 已发货（要确认收货） */
    private int pending;
}
