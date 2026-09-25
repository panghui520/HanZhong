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
 *
 * <p><b>{@code statusLabel} 与 {@code availableActions} 都由服务端算</b>，
 * 前端不维护"状态 → 中文名"和"状态 → 该显示哪些按钮"这两张映射表。
 * 理由是一样的：状态是后端定义的，加一个状态就要通知前端改两处，
 * 迟早漏掉某处 —— 而漏掉的后果是按钮该出现时没出现，或该消失时还在，
 * 用户点了才报错。让服务端一次算清，前端只负责渲染。
 *
 * <p>{@code availableActions} 的取值与各状态下的组合见
 * {@code OrderService.availableActions(status, admin)}。
 */
@Data
public class OrderVO {

    private Long id;

    /** 对外单号，形如 HY20260925004217 */
    private String orderNo;

    private Long userId;
    private String buyerNickname;
    private String buyerEmail;

    /** PENDING_PAYMENT 待付款 / PENDING_SHIPMENT 待发货 / SHIPPED 已发货 /
     *  COMPLETED 已完成 / CANCELLED 已取消 / REFUND_REQUESTED 退款中 / REFUNDED 已退款 */
    private String status;

    /** 状态中文名。放服务端算，前端不用维护一份映射表 */
    private String statusLabel;

    /**
     * 当前状态下**当前这个角色**能做的操作。
     *
     * <p>用户端可能拿到：PAY 付款 / CANCEL 取消 / CONFIRM_RECEIPT 确认收货 /
     * REQUEST_REFUND 申请退款 / REVIEW 评价。
     * 运营端可能拿到：SHIP 发货 / HANDLE_REFUND 处理退款。
     *
     * <p>空列表表示"当前状态没什么可做的"，前端据此把操作区整块收起来，
     * 而不是显示一堆禁用按钮。
     */
    private List<String> availableActions;

    private Integer itemCount;
    private BigDecimal totalAmount;

    /** 收货信息（下单时快照） */
    private String receiverName;
    private String receiverPhone;
    private String receiverAddress;

    private String remark;

    /** TRIP 到访当场带走 / REPURCHASE 离境复购 */
    private String channel;

    // ---------------- 支付 ----------------

    /** 支付截止时刻。前端拿它显示倒计时；为 null 表示这个订单不需要再付款 */
    private LocalDateTime payDeadline;

    private LocalDateTime paidAt;

    // ---------------- 物流 ----------------

    private LocalDateTime shippedAt;

    /** 快递公司 */
    private String carrier;

    /** 预计到达天数 */
    private Integer etaDays;

    /** 快递单号 */
    private String trackingNo;

    // ---------------- 收货 ----------------

    private LocalDateTime receivedAt;

    // ---------------- 取消 ----------------

    private LocalDateTime cancelledAt;

    /** TIMEOUT 超时未付自动取消 / USER 用户主动取消 */
    private String cancelReason;

    // ---------------- 退款 ----------------

    private String refundReason;
    private String refundReply;
    private LocalDateTime refundAt;
    private LocalDateTime refundHandledAt;

    private LocalDateTime createdAt;

    /** 明细。列表接口可能为 null（不带明细），详情接口一定有 */
    private List<OrderItemVO> items;

    /** 已评价时带上，未评价为 null。前端据此决定显示"去评价"还是评价内容 */
    private OrderReviewVO review;
}
