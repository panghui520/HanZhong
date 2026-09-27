package com.hanyou.brain.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hanyou.brain.common.BizException;
import com.hanyou.brain.common.ErrorCode;
import com.hanyou.brain.entity.AppUser;
import com.hanyou.brain.entity.CartItem;
import com.hanyou.brain.entity.Order;
import com.hanyou.brain.entity.OrderItem;
import com.hanyou.brain.entity.OrderReview;
import com.hanyou.brain.entity.TripCheckin;
import com.hanyou.brain.mapper.AppUserMapper;
import com.hanyou.brain.mapper.CartItemMapper;
import com.hanyou.brain.mapper.OrderItemMapper;
import com.hanyou.brain.mapper.OrderMapper;
import com.hanyou.brain.mapper.OrderReviewMapper;
import com.hanyou.brain.mapper.TripCheckinMapper;
import com.hanyou.brain.media.MediaStorageService;
import com.hanyou.brain.vo.CartItemVO;
import com.hanyou.brain.vo.OrderCountVO;
import com.hanyou.brain.vo.OrderItemVO;
import com.hanyou.brain.vo.OrderReviewVO;
import com.hanyou.brain.vo.OrderVO;
import com.hanyou.brain.vo.ProductVO;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * M6 消费与离境复购：购物车 + 订单全流程。
 *
 * <p><b>订单状态机（本类的核心）：</b>
 *
 * <pre>
 *   下单
 *    │
 *    ▼
 * PENDING_PAYMENT 待付款 ──30 分钟未付──▶ CANCELLED 已取消（超时）
 *    │
 *    ├─ 用户主动取消 ────────────────────▶ CANCELLED 已取消（用户）
 *    │
 *    │ 付款
 *    ▼
 * PENDING_SHIPMENT 待发货
 *    │
 *    │ 运营发货（必须填快递公司 / 预计天数 / 快递单号）
 *    ▼
 *  SHIPPED 已发货 ────────────────────▶ REFUND_REQUESTED 退款中
 *    │ 用户确认收货                          │
 *    ▼                                       ├─ 管理员同意 ─▶ REFUNDED 已退款（终态）
 * COMPLETED 已完成                           └─ 管理员拒绝 ─▶ 退回申请前的状态
 *    │ 评价（星级 + 文字 + 图片）
 *    ▼
 *  order_review
 * </pre>
 *
 * <p><b>能申请退款的是 PENDING_SHIPMENT 与 SHIPPED 两个状态。</b>
 * 待付款还没付过钱，取消即可，谈不上"退"；已完成、已取消、已退款都是终态。
 * 已发货之后的退款必须由管理员同意 —— 货已经在路上，买家单方面退对商家不公平，
 * 这也是真实电商的通行做法。
 *
 * <p><b>支付与物流都是演示级的：</b>没有接真实支付通道（"付款"就是一个按钮），
 * 物流信息由运营手工填写、系统不去查快递接口。这一点在验收记录里写明了，
 * 答辩时不要含糊成"我们做了支付"—— 那会被追问到通道、对账和退款资金流。
 *
 * <p><b>三条贯穿本类的原则：</b>
 *
 * <p><b>1. 金额一律服务端重算。</b>前端传什么价格都不看，只认 productId，
 * 按库里的价格算。这是电商类功能最常见的漏洞入口 —— 前端传个 0.01 就成交了。
 *
 * <p><b>2. 挂靠关系必须落到订单行上。</b>每一行 order_item 都带
 * experienceId / poiId。产品以后下架了，订单还能回答"这件东西来自哪次体验"。
 * 这是项目第一条红线（农产品必须挂靠体验或产地）在数据层的落实。
 *
 * <p><b>3. 不写回 product.stock。</b>product 表带 city_code、会被 CityPackImporter
 * 重灌，扣减会在重启后失效。本轮只校验库存是否充足，不扣。详见 V5 脚本顶部说明。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class OrderService {

    // ==================================================================
    // 订单状态
    //
    // 取值必须与 db/V6__m6_order_flow.sql 里 status 列的注释保持一致。
    // 加状态时两处一起改 —— 注释和代码对不上，下一个人就会按错的注释写逻辑。
    // ==================================================================

    /** 待付款：下单后的初始状态。30 分钟内不付款就自动取消 */
    private static final String STATUS_PENDING_PAYMENT = "PENDING_PAYMENT";
    /** 待发货：已付款，等运营发货 */
    private static final String STATUS_PENDING_SHIPMENT = "PENDING_SHIPMENT";
    /** 已发货：运营已填写物流信息 */
    private static final String STATUS_SHIPPED = "SHIPPED";
    /** 已完成：用户确认收货。终态 */
    private static final String STATUS_COMPLETED = "COMPLETED";
    /** 已取消：超时未付或用户主动取消。终态 */
    private static final String STATUS_CANCELLED = "CANCELLED";
    /** 退款中：用户已申请，等管理员处理 */
    private static final String STATUS_REFUND_REQUESTED = "REFUND_REQUESTED";
    /** 已退款：管理员同意退款。终态 */
    private static final String STATUS_REFUNDED = "REFUNDED";

    /**
     * 支付时限。
     *
     * <p>30 分钟是常见电商的默认值：太短用户来不及（尤其还要去翻银行卡），
     * 太长则等于把商品无限期替人占着。做成常量而不是散在代码里的字面量，
     * 是因为它同时出现在"下单时算截止时刻"和"定时任务判断过期"两处，
     * 写两个 30 迟早会改漏一处。
     */
    private static final int PAY_WINDOW_MINUTES = 30;

    /** 取消原因：超时未付，系统自动取消 */
    private static final String CANCEL_TIMEOUT = "TIMEOUT";
    /** 取消原因：用户自己点的取消 */
    private static final String CANCEL_USER = "USER";

    /** 评价最多 3 张图。再多就不像评价、像相册了，也没有哪个运营会去看 */
    private static final int MAX_REVIEW_IMAGES = 3;

    /**
     * 订单渠道：**离境复购**。用户在没到访过（或已离开到访窗口）的情况下下单。
     */
    private static final String CHANNEL_REPURCHASE = "REPURCHASE";

    /**
     * 订单渠道：**到访消费**。下单时该用户对订单中商品的体验锚点或产地
     * 有**新鲜足迹**，说明这笔消费发生在"到访消费链"上（他刚去过，所以带走）。
     *
     * <p>这一列在 M6 到访消费链落地之前是硬编码的常量（所有订单都是
     * {@code REPURCHASE}），也就是说"到访消费"这条链在数据上从未产生过一单 ——
     * 于是创新点二讲的"从到访消费到离境复购"只有后半段。现在由足迹判定。
     */
    private static final String CHANNEL_TRIP = "TRIP";

    /**
     * "还在这次到访里"的窗口：足迹距下单不超过 3 天，算 {@code TRIP}。
     *
     * <p><b>为什么需要一个窗口，而不是"有足迹就算"：</b>
     * 没有窗口的话，一个去过汉中的用户**两年后**再买，仍然会被算成"到访消费"——
     * 那"离境复购"就永远统计不出来，而这个数字正是创新点二的效果证据。
     * 反过来，把窗口设成"必须有足迹"又太松：演示里"两周后回来复购"这一步
     * 会归错类。
     *
     * <p><b>为什么是 3 天：</b>汉中乡村体验的典型行程是 2–3 天
     * （M4 的行程规划按天排、每日预算 360 分钟，演示案例是 2 天）。
     * 3 天覆盖"一次到访期间 + 返程当天"，再往后就是离开之后的复购了。
     *
     * <p>这是**落地时定的口径**，方案里没写窗口（见验收记录的「落地差异」）。
     * 定成常量而不是散在代码里：它只出现在这一处判定里，但答辩时会被问
     * "多久算离境"，需要一个能指着说的地方。
     */
    private static final int TRIP_WINDOW_DAYS = 3;

    /**
     * 手机号：11 位、1 开头、第二位 3-9。
     *
     * <p>刻意不做"宽松兜底"（比如也接受座机）：收货地址填错是下单最常见的失败，
     * 一个明确的"请填 11 位手机号"比放过去再让物流联系不上要好。
     * 真需要座机时再放开，不要一开始就留一个谁都拦不住的口子。
     */
    private static final Pattern PHONE = Pattern.compile("^1[3-9]\\d{9}$");

    private static final DateTimeFormatter NO_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final CartItemMapper cartItemMapper;
    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final ProductService productService;

    /**
     * 运营端要看"这单是谁下的"才联系得上买家。
     *
     * <p>直接注入 Mapper 而不是复用 AuthService：AuthService 那一套是
     * 认证逻辑（校验密码、签发令牌），跟这里只是"按 id 取个昵称"不是一回事，
     * 为了一个查询去依赖整个认证服务不划算。
     */
    private final AppUserMapper appUserMapper;

    /** 订单评价。一单一评，唯一键在库里 */
    private final OrderReviewMapper orderReviewMapper;

    /**
     * 到访足迹（M6 到访消费链）。
     *
     * <p>下单时只**读**它：用来判这一单是"到访消费"还是"离境复购"。
     * 判定口径见 {@link #resolveChannel}。
     */
    private final TripCheckinMapper tripCheckinMapper;

    /**
     * 评价图片在库里存成 JSON 数组字符串，进出各序列化一次。
     *
     * <p>用 Spring 容器里那个（全局配了 snake_case），而不是 new 一个：
     * 这样库里存的格式和项目其它地方一致，也不会多出一份配置。
     */
    private final ObjectMapper objectMapper;

    // ==================================================================
    // 购物车
    // ==================================================================

    /**
     * 我的购物车。
     *
     * <p>一次批量取全部在售产品建映射，而不是逐行 getProduct：
     * 产品总数只有十几条，一次查完比 N 次单查更快，也避免了
     * "查到一半商品下架导致这一行报错"的中间态。
     */
    public List<CartItemVO> listCart(Long userId) {
        List<CartItem> rows = cartItemMapper.selectList(
                Wrappers.<CartItem>lambdaQuery()
                        .eq(CartItem::getUserId, userId)
                        .orderByAsc(CartItem::getId));
        if (rows.isEmpty()) {
            return List.of();
        }

        Map<String, ProductVO> byId = productMap();
        List<CartItemVO> out = new ArrayList<>(rows.size());
        for (CartItem r : rows) {
            out.add(toCartVO(r, byId.get(r.getProductId())));
        }
        return out;
    }

    /** 购物车件数合计（数量之和，不是行数）。顶栏角标用 */
    public int cartCount(Long userId) {
        List<CartItem> rows = cartItemMapper.selectList(
                Wrappers.<CartItem>lambdaQuery().eq(CartItem::getUserId, userId));
        return rows.stream().mapToInt(r -> r.getQuantity() == null ? 0 : r.getQuantity()).sum();
    }

    /**
     * 加购。已存在则数量累加。
     *
     * <p>为什么先查再决定 insert / update，而不是数据库层面的
     * {@code INSERT ... ON DUPLICATE KEY UPDATE}：要在同一处同时做
     * "商品是否存在"、"库存够不够"、"累加后是否超库存"三个判断，
     * 用一条 upsert 表达会把校验挤到 SQL 里，错误码就没法区分了。
     * 这里并发丢更新的风险可以接受 —— 购物车不是下单，最坏是数量少加一次。
     */
    public CartItemVO addToCart(Long userId, String productId, Integer quantity) {
        int qty = requireQuantity(quantity);
        ProductVO p = requireProduct(productId);

        CartItem existing = cartItemMapper.selectOne(
                Wrappers.<CartItem>lambdaQuery()
                        .eq(CartItem::getUserId, userId)
                        .eq(CartItem::getProductId, productId));

        int target = (existing == null ? 0 : existing.getQuantity()) + qty;
        requireStock(p, target);

        if (existing == null) {
            CartItem row = new CartItem();
            row.setUserId(userId);
            row.setProductId(productId);
            row.setQuantity(target);
            cartItemMapper.insert(row);
            return toCartVO(row, p);
        }
        existing.setQuantity(target);
        cartItemMapper.updateById(existing);
        return toCartVO(existing, p);
    }

    /**
     * 改数量。
     *
     * <p>数量下限是 1，不把"改成 0"当删除 —— 删除有独立的 DELETE 接口。
     * 一个接口两种语义，前端少写一行，但接口文档要多写三行解释，不划算。
     */
    public CartItemVO updateQuantity(Long userId, Long cartItemId, Integer quantity) {
        int qty = requireQuantity(quantity);
        CartItem row = requireCartItem(userId, cartItemId);
        ProductVO p = requireProduct(row.getProductId());
        requireStock(p, qty);

        row.setQuantity(qty);
        cartItemMapper.updateById(row);
        return toCartVO(row, p);
    }

    public void removeCartItem(Long userId, Long cartItemId) {
        CartItem row = requireCartItem(userId, cartItemId);
        cartItemMapper.deleteById(row.getId());
    }

    public void clearCart(Long userId) {
        cartItemMapper.delete(Wrappers.<CartItem>lambdaQuery().eq(CartItem::getUserId, userId));
    }

    // ==================================================================
    // 订单（用户端）
    // ==================================================================

    /**
     * 下单。整个动作在一个事务里：建订单 → 建明细 → 清空购物车。
     *
     * <p>三步必须同成同败。若明细写完、清车失败，用户会看到"购物车还在、
     * 订单也生成了"，重复下单的概率极高。
     *
     * <p><b>下单后的状态是「待付款」，不是「待发货」。</b>用户付了款
     * （见 {@link #payOrder}）才进入待发货。所以"下单成功"的提示语
     * 不该写成"我们会尽快发货"—— 那时还没付钱。
     */
    @Transactional(rollbackFor = Exception.class)
    public OrderVO createOrder(Long userId, String receiverName, String receiverPhone,
                               String receiverAddress, String remark) {
        String name = requireText(receiverName, 64);
        String phone = requireText(receiverPhone, 32);
        String address = requireText(receiverAddress, 255);
        if (!PHONE.matcher(phone).matches()) {
            throw new BizException(ErrorCode.RECEIVER_INVALID, "请填写 11 位手机号");
        }

        List<CartItem> rows = cartItemMapper.selectList(
                Wrappers.<CartItem>lambdaQuery()
                        .eq(CartItem::getUserId, userId)
                        .orderByAsc(CartItem::getId));
        if (rows.isEmpty()) {
            throw new BizException(ErrorCode.CART_EMPTY);
        }

        Map<String, ProductVO> byId = productMap();

        BigDecimal total = BigDecimal.ZERO;
        int itemCount = 0;
        List<OrderItem> items = new ArrayList<>(rows.size());

        for (CartItem r : rows) {
            ProductVO p = byId.get(r.getProductId());
            if (p == null) {
                throw new BizException(ErrorCode.PRODUCT_NOT_FOUND, "「" + r.getProductId() + "」已下架");
            }
            int qty = r.getQuantity() == null ? 0 : r.getQuantity();
            requireStock(p, qty);

            BigDecimal unit = p.getPrice() == null ? BigDecimal.ZERO : BigDecimal.valueOf(p.getPrice());
            BigDecimal sub = unit.multiply(BigDecimal.valueOf(qty));

            OrderItem it = new OrderItem();
            it.setProductId(p.getId());

            // ★ 红线：把体验与产地锚点快照到订单行上。
            // 产品以后下架、甚至被换城市的数据包清掉，这一行仍然说得清来源。
            it.setExperienceId(p.getExperienceId());
            it.setExperienceName(p.getExperienceName());
            it.setPoiId(p.getPoiId());
            it.setPoiName(p.getPoiName());

            it.setProductName(p.getName());
            it.setSpec(p.getSpec());
            it.setUnitPrice(unit);
            it.setQuantity(qty);
            it.setSubtotal(sub);
            items.add(it);

            total = total.add(sub);
            itemCount += qty;
        }

        Order order = new Order();
        order.setOrderNo(nextOrderNo());
        order.setUserId(userId);
        // ★ 下单后先是「待付款」，不是「待发货」。
        // 用户点一下付款按钮之后才进入待发货 —— 见 payOrder。
        order.setStatus(STATUS_PENDING_PAYMENT);
        // 支付截止时刻写进库，而不是靠前端倒计时：
        // 前端刷新、关页面、换设备都不影响它，定时任务也只认这一列。
        order.setPayDeadline(LocalDateTime.now().plusMinutes(PAY_WINDOW_MINUTES));
        order.setItemCount(itemCount);
        order.setTotalAmount(total);
        order.setReceiverName(name);
        order.setReceiverPhone(phone);
        order.setReceiverAddress(address);
        order.setRemark(StringUtils.hasText(remark) ? truncate(remark.trim(), 255) : null);
        // ★ 渠道由**足迹**判定，不是常量。见 resolveChannel 的口径说明。
        order.setChannel(resolveChannel(userId, items));
        orderMapper.insert(order);

        for (OrderItem it : items) {
            it.setOrderId(order.getId());
            orderItemMapper.insert(it);
        }

        // 下单成功才清车。放在最后而不是最前：万一上面任何一步抛异常，
        // 事务回滚时购物车还是原样，用户可以改一下再试。
        cartItemMapper.delete(Wrappers.<CartItem>lambdaQuery().eq(CartItem::getUserId, userId));

        // ★ 回读一次，返回「库里真实存了什么」而不是「我们以为写了什么」。
        // 时间戳由数据库默认值（CURRENT_TIMESTAMP）生成，内存对象里是 null ——
        // 不回读的话，下单响应里 created_at 会整个缺失（全局 non_null 序列化），
        // 前端拿到的订单对象比列表接口少两个字段。
        return attachItems(List.of(orderMapper.selectById(order.getId())), Map.of(), false).get(0);
    }

    /**
     * 判定这一单是「到访消费（TRIP）」还是「离境复购（REPURCHASE）」。
     *
     * <p><b>口径（唯一一处，别处不许再判）：</b>
     * 取订单里每件商品的**体验锚点**（{@code order_item.experience_id}）与
     * **产地**（{@code order_item.poi_id}）—— 这两个值在下单时已经被快照到
     * 明细行上（见 {@code createOrder} 里那段红线注释）。若该用户对其中
     * 任何一个在 {@link #TRIP_WINDOW_DAYS} 天内有足迹，就是 {@code TRIP}。
     *
     * <p><b>为什么用订单明细上的快照、而不是拿 product_id 反查商品：</b>
     * 商品会被数据包重灌、可能下架或被换掉产地。用明细行的快照，
     * "这一单当时挂的是哪个体验"这件事永远说得清 —— 与
     * {@code order_item} 冗余这两个字段的理由完全一致。
     *
     * <p><b>为什么要按体验与产地两个方向查：</b>
     * 红线允许商品只挂其中之一（{@code chk_product_traceable}：
     * {@code poi_id} 与 {@code experience_id} 至少一项非空）。只查体验的话，
     * 只挂产地的商品永远判不出 TRIP；只查产地的话，用户在体验现场打卡、
     * 买的是同体验下另一个村的产品时又会漏判。
     *
     * <p><b>查不到足迹不是错误</b>：绝大多数线上订单都没有足迹，
     * 那是 {@code REPURCHASE} 的正常路径，不是异常分支。
     */
    private String resolveChannel(Long userId, List<OrderItem> items) {
        Set<String> experienceIds = new HashSet<>();
        Set<String> poiIds = new HashSet<>();
        for (OrderItem it : items) {
            if (StringUtils.hasText(it.getExperienceId())) {
                experienceIds.add(it.getExperienceId());
            }
            if (StringUtils.hasText(it.getPoiId())) {
                poiIds.add(it.getPoiId());
            }
        }
        if (experienceIds.isEmpty() && poiIds.isEmpty()) {
            return CHANNEL_REPURCHASE;
        }

        LocalDateTime since = LocalDateTime.now().minusDays(TRIP_WINDOW_DAYS);

        // 两个方向分开查，而不是拼一个 or 的 in：
        // 集合为空时 `.in(col, emptySet)` 会生成 `IN ()`，那是非法 SQL，
        // 而"只挂体验的商品"恰恰会让其中一个集合为空。分开写还顺带
        // 让"命中的是体验还是产地"在排查时能分开打日志。
        boolean visited = false;
        if (!experienceIds.isEmpty()) {
            visited = tripCheckinMapper.selectCount(Wrappers.<TripCheckin>lambdaQuery()
                    .eq(TripCheckin::getUserId, userId)
                    .ge(TripCheckin::getCheckinAt, since)
                    .in(TripCheckin::getExperienceId, experienceIds)) > 0;
        }
        if (!visited && !poiIds.isEmpty()) {
            visited = tripCheckinMapper.selectCount(Wrappers.<TripCheckin>lambdaQuery()
                    .eq(TripCheckin::getUserId, userId)
                    .ge(TripCheckin::getCheckinAt, since)
                    .in(TripCheckin::getPoiId, poiIds)) > 0;
        }

        if (visited) {
            log.info("[M6] 用户 {} 在 {} 天内有相关足迹，本单记为到访消费 TRIP", userId, TRIP_WINDOW_DAYS);
            return CHANNEL_TRIP;
        }
        return CHANNEL_REPURCHASE;
    }

    /** 我的订单（含明细）。订单量不大，一次带全，省掉详情页的二次请求 */
    public List<OrderVO> listMyOrders(Long userId) {
        List<Order> orders = orderMapper.selectList(
                Wrappers.<Order>lambdaQuery()
                        .eq(Order::getUserId, userId)
                        .orderByDesc(Order::getCreatedAt)
                        .orderByDesc(Order::getId));
        return attachItems(orders, Map.of(), false);
    }

    /** 订单详情。只能看自己的 —— 传别人的 id 得到 6005，不是 4003（不泄漏"这个单号存在"） */
    public OrderVO getMyOrder(Long userId, Long orderId) {
        Order o = orderMapper.selectById(orderId);
        if (o == null || !o.getUserId().equals(userId)) {
            throw new BizException(ErrorCode.ORDER_NOT_FOUND);
        }
        return attachItems(List.of(o), Map.of(), false).get(0);
    }

    /**
     * 我的订单计数（顶栏角标用）。
     *
     * <p>只查 status 一列，不查整行、更不带明细：顶栏每次登录态变化都要调一次，
     * 走的是最高频的路径。用 {@code selectCount} 让它落成
     * {@code SELECT COUNT(*) ... WHERE user_id = ? AND status IN (...)}，
     * 两个计数都靠 {@code idx_user_created(user_id, created_at)} 的最左前缀
     * user_id 定位（orders 上没有 user_id + status 的联合索引，
     * 但对单个用户的几十单来说，先按 user_id 缩到几十行再过滤 status 已经够了）。
     *
     * <p>{@code pending} 的口径是"**需要用户动手**"：待付款（要付钱）、
     * 已发货（要确认收货）。刻意**不含**已完成 —— 已完成的订单用户无事可做，
     * 算进角标会让角标只增不减，变成一个永远消不掉的红点，
     * 用户很快就不再看它了。退款中也不含：那在等运营处理，不是等用户。
     */
    public OrderCountVO countMyOrders(Long userId) {
        Long total = orderMapper.selectCount(
                Wrappers.<Order>lambdaQuery().eq(Order::getUserId, userId));
        Long pending = orderMapper.selectCount(
                Wrappers.<Order>lambdaQuery()
                        .eq(Order::getUserId, userId)
                        .in(Order::getStatus, STATUS_PENDING_PAYMENT, STATUS_SHIPPED));
        OrderCountVO vo = new OrderCountVO();
        vo.setTotal(total == null ? 0 : total.intValue());
        vo.setPending(pending == null ? 0 : pending.intValue());
        return vo;
    }

    // ==================================================================
    // 订单状态流转（用户端）
    //
    // 每个方法都是同一套写法：取订单 -> 校验"当前状态允不允许这个动作"
    // -> 改状态与时间戳 -> 回读一次再返回。
    //
    // 为什么每个动作都要显式校验当前状态，而不是"改完再看结果"：
    // 状态机有 7 个状态之后，任意两个动作都可能被并发穿插
    // （用户点"确认收货"的同时点了"申请退款"）。把校验放在改之前，
    // 第二个请求会因为状态已经不匹配而被拒，而不是把状态覆盖成错的。
    // ==================================================================

    /**
     * 付款（演示级：不接真实支付通道，就是用户点一下按钮）。
     *
     * <p>为什么"下单"和"付款"要拆成两步，而不是下单即已付款：
     * 真实电商里这就是两个动作，中间那段时间正是"超时取消"存在的理由。
     * 合并成一步，待付款这个状态就没有落脚点了，30 分钟倒计时也无从谈起。
     *
     * <p><b>本方法刻意不加 {@code @Transactional}。</b>因为下面那条
     * "过期了顺手改成已取消、然后报错"的分支，需要在抛异常之后**仍然保留**
     * 已写入的状态 —— 若被事务包着，抛异常会把这次写入一起回滚，
     * 结果就是"提示已超时、刷新还是待付款"，用户会反复点付款。
     * 单步写入本来也不需要事务；让它在没有外层事务的情况下被调用，
     * 提交才是真的提交（这个坑 M8 踩过一次，见认证模块的验收记录）。
     */
    public OrderVO payOrder(Long userId, Long orderId) {
        Order o = requireOwnOrder(userId, orderId);
        if (!STATUS_PENDING_PAYMENT.equals(o.getStatus())) {
            throw new BizException(ErrorCode.ORDER_STATUS_INVALID,
                    "这笔订单不需要付款了，当前是「" + statusLabel(o.getStatus()) + "」");
        }
        // 定时任务每分钟才扫一次，这一单可能已经过期但还没被扫到。
        // 不能只报错不改状态：那样用户会一直看到"待付款"、反复点付款、每次都失败。
        if (o.getPayDeadline() != null && o.getPayDeadline().isBefore(LocalDateTime.now())) {
            o.setStatus(STATUS_CANCELLED);
            o.setCancelledAt(LocalDateTime.now());
            o.setCancelReason(CANCEL_TIMEOUT);
            orderMapper.updateById(o);
            throw new BizException(ErrorCode.ORDER_PAY_EXPIRED);
        }
        o.setStatus(STATUS_PENDING_SHIPMENT);
        o.setPaidAt(LocalDateTime.now());
        orderMapper.updateById(o);
        return reloadAsUser(orderId);
    }

    /**
     * 取消订单（用户主动）。
     *
     * <p>只有待付款能直接取消。已付款的订单必须走退款流程 —— 这是刻意的：
     * "取消"不涉及钱，而付过款的订单取消就意味着退钱，那是另一条要管理员
     * 处理的流程。把两者合成一个按钮，用户会以为点了就退钱了。
     */
    public OrderVO cancelOrder(Long userId, Long orderId) {
        Order o = requireOwnOrder(userId, orderId);
        if (!STATUS_PENDING_PAYMENT.equals(o.getStatus())) {
            throw new BizException(ErrorCode.ORDER_STATUS_INVALID,
                    "只有「待付款」的订单能直接取消，当前是「" + statusLabel(o.getStatus())
                            + "」。已付款的订单请申请退款");
        }
        o.setStatus(STATUS_CANCELLED);
        o.setCancelledAt(LocalDateTime.now());
        o.setCancelReason(CANCEL_USER);
        orderMapper.updateById(o);
        return reloadAsUser(orderId);
    }

    /**
     * 确认收货。
     *
     * <p>只有已发货能确认。确认后进入已完成 —— 而"已完成"是评价的前置条件，
     * 所以这一步不能省：没收到货就能评价等于刷评。
     */
    public OrderVO confirmReceipt(Long userId, Long orderId) {
        Order o = requireOwnOrder(userId, orderId);
        if (!STATUS_SHIPPED.equals(o.getStatus())) {
            throw new BizException(ErrorCode.ORDER_STATUS_INVALID,
                    "只有「已发货」的订单能确认收货，当前是「" + statusLabel(o.getStatus()) + "」");
        }
        o.setStatus(STATUS_COMPLETED);
        o.setReceivedAt(LocalDateTime.now());
        orderMapper.updateById(o);
        return reloadAsUser(orderId);
    }

    /**
     * 申请退款。
     *
     * <p>能申请的是「待发货」与「已发货」：前者钱付了货没发，后者货在路上。
     * 待付款还没付过钱，取消即可；已完成 / 已取消 / 已退款都是终态。
     *
     * <p>申请时把当前状态记进 {@code statusBeforeRefund}，管理员拒绝时
     * 订单要退回这里。不记下来就回不去了。
     */
    public OrderVO requestRefund(Long userId, Long orderId, String reason) {
        String text = requireText(reason, 255);
        Order o = requireOwnOrder(userId, orderId);
        if (!STATUS_PENDING_SHIPMENT.equals(o.getStatus())
                && !STATUS_SHIPPED.equals(o.getStatus())) {
            throw new BizException(ErrorCode.ORDER_STATUS_INVALID,
                    "当前状态（" + statusLabel(o.getStatus()) + "）不能申请退款");
        }
        // 用 UpdateWrapper 显式 set，而不是改实体再 updateById：
        // MyBatis-Plus 的 updateById 默认**忽略 null 字段**，于是
        // "重新申请退款时清掉上一次的拒绝理由"这个动作会静默失效，
        // 页面上就会同时出现"退款中"和上次的拒绝理由，看着自相矛盾。
        orderMapper.update(null, Wrappers.<Order>lambdaUpdate()
                .eq(Order::getId, orderId)
                .set(Order::getStatusBeforeRefund, o.getStatus())
                .set(Order::getStatus, STATUS_REFUND_REQUESTED)
                .set(Order::getRefundReason, text)
                .set(Order::getRefundAt, LocalDateTime.now())
                .set(Order::getRefundReply, null)
                .set(Order::getRefundHandledAt, null));
        return reloadAsUser(orderId);
    }

    /**
     * 评价订单。
     *
     * <p>只有「已完成」能评价 —— 没收到货就评价是刷评，最基本的一条。
     *
     * <p>一单一评靠 {@code order_review.uk_order} 唯一键兜底。这里先查一次
     * 只是为了给出友好的 6010，真正的防重是那个唯一键：两个请求同时通过
     * 这里的检查时，数据库会拒绝第二个。
     */
    public OrderReviewVO createReview(Long userId, Long orderId, Integer rating,
                                      String content, List<String> images) {
        // ★ 先校验"这笔订单能不能评价"，再校验评价内容本身。顺序不能反：
        // 对一笔还没完成的订单报"请写点评价内容"是答非所问 —— 用户会以为
        // 是自己内容没填好，反复改文案，而真正的原因是这单还不能评。
        // 实测踩到过（验收脚本第一轮把这条判成了失败）。
        Order o = requireOwnOrder(userId, orderId);
        if (!STATUS_COMPLETED.equals(o.getStatus())) {
            throw new BizException(ErrorCode.ORDER_STATUS_INVALID,
                    "只有已完成的订单能评价，当前是「" + statusLabel(o.getStatus()) + "」");
        }

        if (rating == null || rating < 1 || rating > 5) {
            throw new BizException(ErrorCode.REVIEW_RATING_INVALID);
        }
        String text = StringUtils.hasText(content) ? truncate(content.trim(), 1000) : null;
        List<String> imgs = normalizeImages(images);
        if (text == null && imgs.isEmpty()) {
            throw new BizException(ErrorCode.REVIEW_CONTENT_REQUIRED);
        }

        if (orderReviewMapper.selectCount(
                Wrappers.<OrderReview>lambdaQuery().eq(OrderReview::getOrderId, orderId)) > 0) {
            throw new BizException(ErrorCode.REVIEW_EXISTS);
        }

        OrderReview r = new OrderReview();
        r.setOrderId(orderId);
        r.setUserId(userId);
        r.setRating(rating);
        r.setContent(text);
        r.setImages(imgs.isEmpty() ? null : toImagesJson(imgs));
        orderReviewMapper.insert(r);
        // 回读：id 与 created_at 都由数据库生成，内存对象里 created_at 是 null，
        // 不回读的话响应里这个字段会整个缺失 —— 与下单那次是同一个坑。
        return toReviewVO(orderReviewMapper.selectById(r.getId()));
    }

    // ==================================================================
    // 订单（运营端）
    // ==================================================================

    /** 运营端订单列表。status 为空时返回全部，需要运营动手的排在前面 */
    public List<OrderVO> listAllOrders(String status) {
        var q = Wrappers.<Order>lambdaQuery();
        if (StringUtils.hasText(status)) {
            q.eq(Order::getStatus, status.trim().toUpperCase());
        }
        List<Order> orders = orderMapper.selectList(q);

        // ★ 排序必须在 Java 里显式表达，不能靠 orderByAsc(status) 的字母序。
        //
        // 旧实现是 `orderByAsc(Order::getStatus)`，注释写着"待发货优先" ——
        // 它当时确实成立，但纯属巧合：状态只有 PENDING / SHIPPED 两个，
        // 字母序里 P 在 S 前面，正好把待发货排到了前面。
        // 现在状态变成 7 个，字母序是 CANCELLED, COMPLETED, PENDING_PAYMENT,
        // PENDING_SHIPMENT, REFUNDED, REFUND_REQUESTED, SHIPPED ——
        // 待发货掉到第 4 位，退款中也埋在下面，"优先"静默失效。
        // 这种"靠数据取值碰巧成立"的排序，加一个枚举值就会坏掉，所以换成显式权重。
        orders.sort(Comparator
                .comparingInt((Order o) -> actionPriority(o.getStatus()))
                .thenComparing(Order::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(Order::getId, Comparator.reverseOrder()));

        // 一次把涉及到的买家全查出来，不在循环里逐个查（同样是 N+1）
        List<Long> userIds = orders.stream().map(Order::getUserId).distinct().toList();
        Map<Long, AppUser> users = userIds.isEmpty()
                ? Map.of()
                : appUserMapper.selectBatchIds(userIds).stream()
                        .collect(Collectors.toMap(AppUser::getId, Function.identity(), (a, b) -> a));
        return attachItems(orders, users, true);
    }

    /**
     * 运营端排序权重：数字越小越靠前，越小也越"需要运营动手"。
     *
     * <p>待发货排第一（运营进这个页面主要就是来发货的），退款中排第二
     * （用户等着答复），之后才是"在等别人动作"的状态，最后是终态。
     */
    private static int actionPriority(String status) {
        if (STATUS_PENDING_SHIPMENT.equals(status)) {
            return 0;
        }
        if (STATUS_REFUND_REQUESTED.equals(status)) {
            return 1;
        }
        if (STATUS_PENDING_PAYMENT.equals(status)) {
            return 2;
        }
        if (STATUS_SHIPPED.equals(status)) {
            return 3;
        }
        return 4;
    }

    /**
     * 发货。必须填写快递公司、预计到达天数、快递单号三项。
     *
     * <p>为什么强制填全：这三项是买家那边唯一能看到的物流线索。允许留空的话，
     * 运营图快会全部跳过，买家就只看到一个"已发货"，连去哪查都不知道 ——
     * 那这条状态流转等于白做。
     *
     * <p>校验"当前必须是待发货"：重复点两次不该把 shipped_at 刷成第二次的时间，
     * 也不该把第一次填的快递单号覆盖掉。运营端列表上按钮挨得很近，双击是常态。
     */
    public OrderVO shipOrder(Long orderId, String carrier, Integer etaDays, String trackingNo) {
        String carrierText = requireShipInfo(carrier, 32, "快递公司");
        String trackingText = requireShipInfo(trackingNo, 64, "快递单号");
        if (etaDays == null || etaDays < 1 || etaDays > 60) {
            throw new BizException(ErrorCode.SHIP_INFO_REQUIRED, "预计到达天数请填 1 到 60 之间");
        }

        Order o = requireOrder(orderId);
        if (!STATUS_PENDING_SHIPMENT.equals(o.getStatus())) {
            throw new BizException(ErrorCode.ORDER_STATUS_INVALID,
                    "只有「待发货」的订单能发货，当前是「" + statusLabel(o.getStatus()) + "」");
        }
        o.setStatus(STATUS_SHIPPED);
        o.setCarrier(carrierText);
        o.setEtaDays(etaDays);
        o.setTrackingNo(trackingText);
        o.setShippedAt(LocalDateTime.now());
        orderMapper.updateById(o);
        return reloadAsAdmin(orderId);
    }

    /**
     * 处理退款（运营端）：同意或拒绝。
     *
     * <p>拒绝时订单退回 {@code statusBeforeRefund} 记下的那个状态。
     * 那一列若为空（历史数据、或有人手工改过库），退回「待发货」——
     * 这是最保守的选择：钱已经收了、货还没发，运营还能再发或再协商。
     */
    public OrderVO handleRefund(Long orderId, boolean approve, String reply) {
        String text = requireText(reply, 255);
        Order o = requireOrder(orderId);
        if (!STATUS_REFUND_REQUESTED.equals(o.getStatus())) {
            throw new BizException(ErrorCode.ORDER_STATUS_INVALID,
                    "这笔订单没有待处理的退款申请，当前是「" + statusLabel(o.getStatus()) + "」");
        }
        String back = StringUtils.hasText(o.getStatusBeforeRefund())
                ? o.getStatusBeforeRefund()
                : STATUS_PENDING_SHIPMENT;
        orderMapper.update(null, Wrappers.<Order>lambdaUpdate()
                .eq(Order::getId, orderId)
                .set(Order::getStatus, approve ? STATUS_REFUNDED : back)
                .set(Order::getRefundReply, text)
                .set(Order::getRefundHandledAt, LocalDateTime.now())
                .set(Order::getStatusBeforeRefund, null));
        return reloadAsAdmin(orderId);
    }

    /**
     * 把超时未付款的订单改成已取消。由定时任务调用（见 {@code OrderTimeoutTask}）。
     *
     * <p>用一条 UPDATE 批量处理，而不是"查出来再逐个改"：待付款订单可能很多，
     * 逐个改会产生 N 条 UPDATE；而且中间任何一条失败都会留下"扫了一半"的状态，
     * 下一次扫描还得判断哪些处理过。一条 SQL 要么全成要么全不成。
     *
     * <p>条件里的 {@code isNotNull(payDeadline)} 不能省：手工插的历史订单
     * 可能没设过这一列。NULL 与时间比较结果是 NULL 而不是 true，虽然结果一样，
     * 但显式写出来能让人一眼看出"没设截止时间的不会被误杀"。
     *
     * @return 本次取消了几单
     */
    public int cancelExpiredOrders() {
        LocalDateTime now = LocalDateTime.now();
        return orderMapper.update(null, Wrappers.<Order>lambdaUpdate()
                .eq(Order::getStatus, STATUS_PENDING_PAYMENT)
                .isNotNull(Order::getPayDeadline)
                .lt(Order::getPayDeadline, now)
                .set(Order::getStatus, STATUS_CANCELLED)
                .set(Order::getCancelledAt, now)
                .set(Order::getCancelReason, CANCEL_TIMEOUT));
    }

    /**
     * 支付时限（分钟）。
     *
     * <p>暴露出来是给定时任务的日志用的：日志里写死"30 分钟"的话，
     * 哪天改了 {@link #PAY_WINDOW_MINUTES}，日志会继续宣称 30 分钟，
     * 而实际规则已经变了 —— 排查问题的人会照着错的数字去算。
     */
    public int payWindowMinutes() {
        return PAY_WINDOW_MINUTES;
    }

    // ==================================================================
    // 内部工具
    // ==================================================================

    /** 全部在售产品，按 id 建索引。一次查询供整个方法复用 */
    private Map<String, ProductVO> productMap() {
        return productService.listProducts(null, null, null, null).stream()
                .collect(Collectors.toMap(ProductVO::getId, Function.identity(), (a, b) -> a));
    }

    private ProductVO requireProduct(String productId) {
        ProductVO p = productMap().get(productId);
        if (p == null) {
            throw new BizException(ErrorCode.PRODUCT_NOT_FOUND);
        }
        return p;
    }

    private CartItem requireCartItem(Long userId, Long cartItemId) {
        CartItem row = cartItemMapper.selectById(cartItemId);
        // 别人的购物车行一律按"不存在"处理，不返回 4003 ——
        // 4003 等于告诉对方"这一行确实存在"，是可以被枚举的信息
        if (row == null || !row.getUserId().equals(userId)) {
            throw new BizException(ErrorCode.CART_ITEM_NOT_FOUND);
        }
        return row;
    }

    private int requireQuantity(Integer quantity) {
        if (quantity == null || quantity < 1) {
            throw new BizException(ErrorCode.QUANTITY_INVALID);
        }
        if (quantity > 99) {
            throw new BizException(ErrorCode.QUANTITY_INVALID, "单件商品最多 99 件");
        }
        return quantity;
    }

    private void requireStock(ProductVO p, int quantity) {
        int stock = p.getStock() == null ? 0 : p.getStock();
        if (quantity > stock) {
            throw new BizException(ErrorCode.STOCK_NOT_ENOUGH,
                    "「" + p.getName() + "」仅剩 " + stock + " 件");
        }
    }

    private String requireText(String v, int max) {
        if (!StringUtils.hasText(v)) {
            throw new BizException(ErrorCode.RECEIVER_INVALID);
        }
        String t = v.trim();
        if (t.length() > max) {
            throw new BizException(ErrorCode.RECEIVER_INVALID, "内容过长，请精简到 " + max + " 字以内");
        }
        return t;
    }

    private String truncate(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }

    /**
     * 生成对外单号：HY + yyyyMMddHHmmss + 4 位随机。
     *
     * <p>不用自增主键当单号：让用户念一串连续数字，既容易和别的系统撞号，
     * 也把"我们一共卖了多少单"暴露出去。库里 order_no 有唯一键兜底，
     * 同一秒内撞号的概率是万分之一，真撞了数据库会直接拒绝。
     */
    private String nextOrderNo() {
        return "HY" + LocalDateTime.now().format(NO_TIME)
                + String.format("%04d", ThreadLocalRandom.current().nextInt(10000));
    }

    private CartItemVO toCartVO(CartItem row, ProductVO p) {
        CartItemVO v = new CartItemVO();
        v.setId(row.getId());
        v.setProductId(row.getProductId());
        v.setQuantity(row.getQuantity());
        if (p == null) {
            // 商品已下架 / 不在当前城市的数据包里。字段留空，由前端提示移除
            v.setAvailable(false);
            return v;
        }
        v.setAvailable(true);
        v.setName(p.getName());
        v.setSpec(p.getSpec());
        v.setScene(p.getScene());
        v.setStock(p.getStock());
        v.setExperienceId(p.getExperienceId());
        v.setExperienceName(p.getExperienceName());
        v.setPoiId(p.getPoiId());
        v.setPoiName(p.getPoiName());

        BigDecimal unit = p.getPrice() == null ? BigDecimal.ZERO : BigDecimal.valueOf(p.getPrice());
        v.setUnitPrice(unit);
        int qty = row.getQuantity() == null ? 0 : row.getQuantity();
        v.setSubtotal(unit.multiply(BigDecimal.valueOf(qty)));
        return v;
    }

    /**
     * 批量补齐明细、买家信息、评价与可用操作。
     *
     * <p>先一次性把涉及到的 orderId 全部查出来再分组，而不是在循环里逐个订单查 ——
     * 后者是典型的 N+1：运营端一屏 20 个订单就是 21 次查询。评价同理，
     * 一次查完再按 orderId 分组。
     *
     * @param users userId -> 买家。传空 Map 表示不需要买家信息（用户端看自己的单）
     * @param admin true = 运营端视角。只影响 availableActions 给出哪些操作，
     *              不影响其它字段：运营端拿到 SHIP / HANDLE_REFUND，
     *              用户端拿到 PAY / CANCEL / CONFIRM_RECEIPT / REQUEST_REFUND / REVIEW
     */
    private List<OrderVO> attachItems(List<Order> orders, Map<Long, AppUser> users, boolean admin) {
        if (orders.isEmpty()) {
            return List.of();
        }
        List<Long> ids = orders.stream().map(Order::getId).toList();
        Map<Long, List<OrderItem>> grouped = orderItemMapper
                .selectList(Wrappers.<OrderItem>lambdaQuery()
                        .in(OrderItem::getOrderId, ids)
                        .orderByAsc(OrderItem::getId))
                .stream()
                .collect(Collectors.groupingBy(OrderItem::getOrderId));

        Map<Long, OrderReview> reviews = orderReviewMapper
                .selectList(Wrappers.<OrderReview>lambdaQuery().in(OrderReview::getOrderId, ids))
                .stream()
                .collect(Collectors.toMap(OrderReview::getOrderId, Function.identity(), (a, b) -> a));

        List<OrderVO> out = new ArrayList<>(orders.size());
        for (Order o : orders) {
            AppUser u = users.get(o.getUserId());
            out.add(toOrderVO(o, grouped.getOrDefault(o.getId(), List.of()),
                    u == null ? null : u.getNickname(),
                    u == null ? null : u.getEmail(),
                    reviews.get(o.getId()),
                    admin));
        }
        return out;
    }

    private OrderVO toOrderVO(Order o, List<OrderItem> items, String nickname, String email,
                              OrderReview review, boolean admin) {
        OrderVO v = new OrderVO();
        v.setId(o.getId());
        v.setOrderNo(o.getOrderNo());
        v.setUserId(o.getUserId());
        v.setBuyerNickname(nickname);
        v.setBuyerEmail(email);
        v.setStatus(o.getStatus());
        v.setStatusLabel(statusLabel(o.getStatus()));

        // 已评价就把 REVIEW 从可选操作里去掉 —— 否则前端会一直显示一个
        // 点了就报 6010 的"去评价"按钮。状态本身没错（还是已完成），
        // 但"能做什么"已经变了，这一层只有服务端知道。
        List<String> actions = availableActions(o.getStatus(), admin);
        if (review != null) {
            actions = actions.stream().filter(a -> !"REVIEW".equals(a)).toList();
        }
        v.setAvailableActions(actions);

        v.setItemCount(o.getItemCount());
        v.setTotalAmount(o.getTotalAmount());
        v.setReceiverName(o.getReceiverName());
        v.setReceiverPhone(o.getReceiverPhone());
        v.setReceiverAddress(o.getReceiverAddress());
        v.setRemark(o.getRemark());
        v.setChannel(o.getChannel());

        v.setPayDeadline(o.getPayDeadline());
        v.setPaidAt(o.getPaidAt());
        v.setShippedAt(o.getShippedAt());
        v.setCarrier(o.getCarrier());
        v.setEtaDays(o.getEtaDays());
        v.setTrackingNo(o.getTrackingNo());
        v.setReceivedAt(o.getReceivedAt());
        v.setCancelledAt(o.getCancelledAt());
        v.setCancelReason(o.getCancelReason());
        v.setRefundReason(o.getRefundReason());
        v.setRefundReply(o.getRefundReply());
        v.setRefundAt(o.getRefundAt());
        v.setRefundHandledAt(o.getRefundHandledAt());

        v.setCreatedAt(o.getCreatedAt());

        List<OrderItemVO> list = new ArrayList<>(items.size());
        for (OrderItem it : items) {
            OrderItemVO iv = new OrderItemVO();
            iv.setId(it.getId());
            iv.setProductId(it.getProductId());
            iv.setExperienceId(it.getExperienceId());
            iv.setExperienceName(it.getExperienceName());
            iv.setPoiId(it.getPoiId());
            iv.setPoiName(it.getPoiName());
            iv.setProductName(it.getProductName());
            iv.setSpec(it.getSpec());
            iv.setUnitPrice(it.getUnitPrice());
            iv.setQuantity(it.getQuantity());
            iv.setSubtotal(it.getSubtotal());
            list.add(iv);
        }
        v.setItems(list);
        v.setReview(review == null ? null : toReviewVO(review));
        return v;
    }

    /**
     * 当前状态下这个角色能做什么。
     *
     * <p>放在服务端算，前端不维护"状态 -> 按钮"的映射表 —— 与 statusLabel
     * 同一个理由：状态是后端定义的，加一个状态就要通知前端改，迟早漏一处，
     * 而漏掉的后果是按钮该出现时没出现、该消失时还在（用户点了才报错）。
     */
    private List<String> availableActions(String status, boolean admin) {
        if (admin) {
            if (STATUS_PENDING_SHIPMENT.equals(status)) {
                return List.of("SHIP");
            }
            if (STATUS_REFUND_REQUESTED.equals(status)) {
                return List.of("HANDLE_REFUND");
            }
            return List.of();
        }
        if (STATUS_PENDING_PAYMENT.equals(status)) {
            return List.of("PAY", "CANCEL");
        }
        if (STATUS_PENDING_SHIPMENT.equals(status)) {
            return List.of("REQUEST_REFUND");
        }
        if (STATUS_SHIPPED.equals(status)) {
            return List.of("CONFIRM_RECEIPT", "REQUEST_REFUND");
        }
        if (STATUS_COMPLETED.equals(status)) {
            return List.of("REVIEW");
        }
        return List.of();
    }

    /**
     * 状态中文名。
     *
     * <p>放在服务端算而不是前端维护一份映射：状态是后端定义的，
     * 加一个状态就要通知前端改一次，迟早漏掉某处显示成裸的英文枚举。
     */
    private String statusLabel(String status) {
        if (STATUS_PENDING_PAYMENT.equals(status)) {
            return "待付款";
        }
        if (STATUS_PENDING_SHIPMENT.equals(status)) {
            return "待发货";
        }
        if (STATUS_SHIPPED.equals(status)) {
            return "已发货";
        }
        if (STATUS_COMPLETED.equals(status)) {
            return "已完成";
        }
        if (STATUS_CANCELLED.equals(status)) {
            return "已取消";
        }
        if (STATUS_REFUND_REQUESTED.equals(status)) {
            return "退款中";
        }
        if (STATUS_REFUNDED.equals(status)) {
            return "已退款";
        }
        return status;
    }

    // ==================================================================
    // 订单取数与回读
    // ==================================================================

    /** 按 id 取订单，不存在则 6005。运营端用（不校验归属） */
    private Order requireOrder(Long orderId) {
        Order o = orderMapper.selectById(orderId);
        if (o == null) {
            throw new BizException(ErrorCode.ORDER_NOT_FOUND);
        }
        return o;
    }

    /**
     * 按 id 取订单，并校验它属于这个用户。
     *
     * <p>别人的订单一律按"不存在"处理（6005），不返回 4003 ——
     * 4003 等于告诉对方"这一单确实存在"，可以被拿来枚举。
     */
    private Order requireOwnOrder(Long userId, Long orderId) {
        Order o = requireOrder(orderId);
        if (!o.getUserId().equals(userId)) {
            throw new BizException(ErrorCode.ORDER_NOT_FOUND);
        }
        return o;
    }

    /**
     * 写完回读一次，给用户端用。
     *
     * <p>理由不只是"稳妥"，是一个真实踩到过的不一致：Java 的
     * {@code LocalDateTime.now()} 带纳秒，而 DATETIME 列没有小数秒，MySQL 会四舍五入。
     * 不回读的话，接口返回 {@code ...18:45:08.7085987}，而用户随后刷新看到
     * {@code ...18:45:09} —— 同一个字段两次读不一样。前端目前只显示到分钟
     * 所以看不出来，但那是运气，不是设计。
     */
    private OrderVO reloadAsUser(Long orderId) {
        return attachItems(List.of(orderMapper.selectById(orderId)), Map.of(), false).get(0);
    }

    /** 写完回读一次，给运营端用。额外带上买家信息，运营端才能整行替换而不丢掉昵称 */
    private OrderVO reloadAsAdmin(Long orderId) {
        Order saved = orderMapper.selectById(orderId);
        AppUser u = appUserMapper.selectById(saved.getUserId());
        return attachItems(List.of(saved),
                u == null ? Map.of() : Map.of(saved.getUserId(), u), true).get(0);
    }

    private String requireShipInfo(String v, int max, String field) {
        if (!StringUtils.hasText(v)) {
            throw new BizException(ErrorCode.SHIP_INFO_REQUIRED, "请填写" + field);
        }
        String t = v.trim();
        if (t.length() > max) {
            throw new BizException(ErrorCode.SHIP_INFO_REQUIRED,
                    field + "过长，请精简到 " + max + " 字以内");
        }
        return t;
    }

    // ==================================================================
    // 评价图片
    // ==================================================================

    /**
     * 清洗评价图片列表：去空、去重、挡掉可疑路径、截到 3 张。
     *
     * <p>为什么服务端还要再截一次：前端确实限了 3 张，但接口是公开的，
     * 绕过页面直接调接口就能塞 50 张。**限制必须落在服务端**，
     * 前端的限制只是体验，不是约束。
     *
     * <p>路径校验挡的是 {@code ../} 这类穿越写法：这个值最终会被前端拼成
     * 图片 URL，不校验的话可能被带出媒体目录。图片本身早就由上传接口写进
     * 磁盘了，这里存的只是它的相对路径。
     */
    private List<String> normalizeImages(List<String> images) {
        if (images == null || images.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String s : images) {
            if (!StringUtils.hasText(s)) {
                continue;
            }
            String t = s.trim();
            if (t.length() > 255 || t.contains("..") || !t.matches("[A-Za-z0-9._/-]+")) {
                log.warn("[评价图片] 丢弃可疑路径: {}", t);
                continue;
            }
            if (!out.contains(t)) {
                out.add(t);
            }
            if (out.size() >= MAX_REVIEW_IMAGES) {
                break;
            }
        }
        return out;
    }

    private String toImagesJson(List<String> images) {
        try {
            return objectMapper.writeValueAsString(images);
        } catch (Exception e) {
            // 走到这里说明是编码问题而不是数据问题，直接放弃存图比存一半好
            log.warn("[评价图片] 序列化失败，按无图处理: {}", images, e);
            return null;
        }
    }

    /**
     * 把库里的 JSON 字符串还原成数组。
     *
     * <p>解析失败按"无图"处理而不是抛异常：评价的文字和星级仍然有价值，
     * 不该因为一组图片的格式问题让整个评价打不开。
     */
    private List<String> parseImages(String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            List<String> list = objectMapper.readValue(json, new TypeReference<List<String>>() {});
            return list == null ? List.of() : list;
        } catch (Exception e) {
            log.warn("[评价图片] JSON 解析失败，按无图处理: {}", json);
            return List.of();
        }
    }

    private OrderReviewVO toReviewVO(OrderReview r) {
        OrderReviewVO v = new OrderReviewVO();
        v.setId(r.getId());
        v.setOrderId(r.getOrderId());
        v.setRating(r.getRating());
        v.setContent(r.getContent());
        // 库里存相对路径，出库拼成完整 URL（与 PoiImageVO 同一约定）。
        // 前端拿到的 images 可以直接塞进 <img src>，不必知道 /api/media/ 这个前缀。
        v.setImages(parseImages(r.getImages()).stream()
                .map(p -> MediaStorageService.URL_PREFIX + p)
                .toList());
        v.setCreatedAt(r.getCreatedAt());
        return v;
    }
}
