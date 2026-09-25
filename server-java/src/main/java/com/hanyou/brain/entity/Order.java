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
 * <p>{@code status} 只有 PENDING / SHIPPED 两个值，这是刻意的。
 * 支付状态与物流状态需要真实的支付、物流通道才成立，做了就是摆样子，
 * 还会让运营端多出一堆永远停在某个状态的数据。取消订单同理，留作扩展点。
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

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
