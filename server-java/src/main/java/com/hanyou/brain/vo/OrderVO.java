package com.hanyou.brain.vo;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import lombok.Data;

/**
 * 订单对外视图（M6）。
 *
 * <p>{@code items} 直接内嵌在订单里，不另开 {@code GET /api/orders/{id}/items}：
 * 订单详情页一定要显示明细，分成两个请求只会让页面出现"主信息已到、明细还在转"
 * 的中间态。列表页若嫌重，由服务端决定要不要带明细（见 OrderService.listOrders）。
 *
 * <p>{@code buyerEmail} 只在运营端有意义（运营要联系买家），
 * 用户端看自己的订单时它是冗余的 —— 但两个场景共用这一个 VO，
 * 为省一个字段拆两个类不值得。
 */
@Data
public class OrderVO {

    private Long id;

    /** 对外单号，形如 HY20260925004217 */
    private String orderNo;

    private Long userId;
    private String buyerNickname;
    private String buyerEmail;

    /** PENDING 待发货 / SHIPPED 已发货 */
    private String status;

    /** 状态中文名。放服务端算，前端不用维护一份映射表 */
    private String statusLabel;

    private Integer itemCount;
    private BigDecimal totalAmount;

    /** 收货信息（下单时快照） */
    private String receiverName;
    private String receiverPhone;
    private String receiverAddress;

    private String remark;

    /** TRIP 到访当场带走 / REPURCHASE 离境复购 */
    private String channel;

    private LocalDateTime shippedAt;
    private LocalDateTime createdAt;

    /** 明细。列表接口可能为 null（不带明细），详情接口一定有 */
    private List<OrderItemVO> items;
}
