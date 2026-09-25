package com.hanyou.brain.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 订单主表（M6 消费与离境复购）。
 *
 * <p>表名是 {@code orders} 不是 {@code order} —— {@code ORDER} 是 SQL 保留字，
 * 用保留字做表名会让每一条 SQL 都得加反引号，迟早有人漏写。
 *
 * <p>本表**刻意没有 cityCode**，理由同 {@link CartItem}：订单是运行期数据。
 *
 * <p>{@code status} 现在是一条完整的订单状态机：
 * PENDING_PAYMENT 待付款 → PENDING_SHIPMENT 待发货 → SHIPPED 已发货 → COMPLETED 已完成；
 * 另有 CANCELLED 已取消、REFUND_REQUESTED 退款中、REFUNDED 已退款三个分支状态。
 * 各状态的流转条件与异常分支见 {@link com.hanyou.brain.service.OrderService} 顶部说明。
 *
 * <p>支付与物流都是**演示级**的：没有接真实支付通道（"付款"就是一个按钮），
 * 物流信息由运营手工填写、系统不去查快递接口。这一点在验收记录里写明了，
 * 答辩时不要含糊过去 —— 说"我们做了支付"会被追问到通道与对账。
 *
 * <p>金额用 {@link BigDecimal} 不用 double：double 是二进制浮点，
 * 0.1 + 0.2 != 0.3，金额算错在答辩现场被问一句就下不来台。
 * 库里对应 DECIMAL(10,2)。
 *
 * <p>receiver* 三列是**下单时的快照**，不是对地址簿的引用。若引用地址簿 id，
 * 用户后来改了收货地址，历史订单显示的地址会跟着变 —— 那是错账。
 */
@Data
@TableName("orders")
public class Order {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 对外单号，形如 HY20260925004217。主键 id 只在内部用 */
    private String orderNo;

    /** 下单人，-> app_user.id */
    private Long userId;

    /** PENDING 待发货 / SHIPPED 已发货 */
    private String status;

    /** 商品件数合计。冗余在主页上，列表页就不用再查一次子表 */
    private Integer itemCount;

    /** 订单总额。由服务端按 product 表重算，不信前端传来的价格 */
    private BigDecimal totalAmount;

    /** 收货人姓名（下单时快照） */
    private String receiverName;

    /** 联系电话（下单时快照） */
    private String receiverPhone;

    /** 收货地址（下单时快照） */
    private String receiverAddress;

    /** 买家备注 */
    private String remark;

    /** TRIP 到访当场带走 / REPURCHASE 离境复购。本轮只走 REPURCHASE，列先留着 */
    private String channel;

    /** 发货时间，未发货为 null */
    private LocalDateTime shippedAt;

    // ---------------- 支付 ----------------

    /**
     * 支付截止时刻 = 下单时刻 + 30 分钟。超过它还没付款，定时任务会把订单改成已取消。
     *
     * <p>存"截止时刻"而不是"剩余秒数"：存秒数的话，服务重启一次、或定时任务
     * 晚跑一会儿，倒计时就不准了。绝对时刻任何时候拿来和当前时间一比就知道过期没有。
     */
    private LocalDateTime payDeadline;

    /** 付款时刻。不接真实支付，"付款"是用户点的一个按钮 */
    private LocalDateTime paidAt;

    // ---------------- 物流（发货时由运营填写）----------------

    /** 快递公司，如「顺丰速运」 */
    private String carrier;

    /** 预计到达天数 */
    private Integer etaDays;

    /** 快递单号 */
    private String trackingNo;

    // ---------------- 收货 ----------------

    /** 确认收货时刻。只有已发货的订单能确认收货 */
    private LocalDateTime receivedAt;

    // ---------------- 取消 ----------------

    private LocalDateTime cancelledAt;

    /** TIMEOUT 超时未付自动取消 / USER 用户主动取消 */
    private String cancelReason;

    // ---------------- 退款 ----------------

    /** 买家填写的退款原因 */
    private String refundReason;

    /** 管理员处理退款时的回复 / 拒绝理由 */
    private String refundReply;

    /** 买家申请退款的时刻 */
    private LocalDateTime refundAt;

    /** 管理员处理退款的时刻 */
    private LocalDateTime refundHandledAt;

    /**
     * 申请退款**之前**的状态。
     *
     * <p>管理员拒绝退款时，订单要退回申请前的状态（可能是待发货，也可能是已发货）。
     * 不记下来就回不去了 —— 这是"只用一个 status 字段表达退款中"必须付的代价，
     * 但它比另开一个 refund_status 列要好：两列表达同一件事，迟早有一天对不上。
     */
    private String statusBeforeRefund;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
