package com.hanyou.brain.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 订单明细（M6 消费与离境复购）。
 *
 * <p><b>★ 这张表是"本项目不是电商"的证据所在。</b>
 *
 * <p>{@code experienceId} / {@code poiId} 两列**必须存**，哪怕已经能通过
 * {@code productId} 反查回去。两个理由：
 * <ol>
 *   <li>产品可能下架、甚至整条 product 记录被换城市的数据包清掉，
 *       历史订单仍然要说清"这件东西来自哪次体验 / 哪个产地"；</li>
 *   <li>这是项目的第一条设计红线 —— 农产品必须挂靠体验或产地。
 *       把挂靠关系落在订单行上，"消费链离境延伸"才是**可核对的数据事实**，
 *       而不是一句宣传语。</li>
 * </ol>
 *
 * <p>{@code productName} / {@code spec} / {@code unitPrice} 同理都是**下单时的快照**：
 * 产品改名、调价之后，历史订单显示的必须是当时的信息。
 *
 * <p>刻意不存图片路径：图片是可变的展示物，不该进订单快照；
 * 需要显示时按 productId 现查 M9 的配图。
 *
 * <p>类名与 MyBatis-Plus 的 {@code com.baomidou.mybatisplus.core.metadata.OrderItem}
 * 同名但不同包。本类不会去 import 那一个，也不需要在查询里用到它
 * （排序一律走 {@code Wrappers.lambdaQuery().orderByAsc(...)}）。
 */
@Data
@TableName("order_item")
public class OrderItem {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** -> orders.id */
    private Long orderId;

    /** -> product.id */
    private String productId;

    /** 体验锚点 id，-> experience.id（下单时快照） */
    private String experienceId;

    /** 体验名快照 */
    private String experienceName;

    /** 产地锚点 id，-> poi.id（下单时快照） */
    private String poiId;

    /** 产地乡村名快照 */
    private String poiName;

    /** 商品名快照 */
    private String productName;

    /** 规格快照 */
    private String spec;

    /** 单价快照。服务端按 product 表取，不信前端 */
    private BigDecimal unitPrice;

    private Integer quantity;

    /** 小计 = unitPrice × quantity，服务端算 */
    private BigDecimal subtotal;

    private LocalDateTime createdAt;
}
