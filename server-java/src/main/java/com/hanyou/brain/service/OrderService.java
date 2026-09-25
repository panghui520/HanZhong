package com.hanyou.brain.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hanyou.brain.common.BizException;
import com.hanyou.brain.common.ErrorCode;
import com.hanyou.brain.entity.AppUser;
import com.hanyou.brain.entity.CartItem;
import com.hanyou.brain.entity.Order;
import com.hanyou.brain.entity.OrderItem;
import com.hanyou.brain.mapper.AppUserMapper;
import com.hanyou.brain.mapper.CartItemMapper;
import com.hanyou.brain.mapper.OrderItemMapper;
import com.hanyou.brain.mapper.OrderMapper;
import com.hanyou.brain.vo.CartItemVO;
import com.hanyou.brain.vo.OrderItemVO;
import com.hanyou.brain.vo.OrderVO;
import com.hanyou.brain.vo.ProductVO;

import lombok.RequiredArgsConstructor;

/**
 * M6 消费与离境复购：购物车 + 订单。
 *
 * <p>本轮只做「加购 → 填收货信息 → 下单 → 运营发货」这一段，
 * 不做支付、不做物流、不做取消退款（理由见 db/V5__m6_order.sql 顶部）。
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
@RequiredArgsConstructor
public class OrderService {

    /** 待发货：用户提交后的初始状态 */
    private static final String STATUS_PENDING = "PENDING";
    /** 已发货：运营点一下「标记已发货」 */
    private static final String STATUS_SHIPPED = "SHIPPED";

    /** 离境复购。本轮所有订单都走这个渠道，TRIP 那一侧等 M6 完整版 */
    private static final String CHANNEL_REPURCHASE = "REPURCHASE";

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
        order.setStatus(STATUS_PENDING);
        order.setItemCount(itemCount);
        order.setTotalAmount(total);
        order.setReceiverName(name);
        order.setReceiverPhone(phone);
        order.setReceiverAddress(address);
        order.setRemark(StringUtils.hasText(remark) ? truncate(remark.trim(), 255) : null);
        order.setChannel(CHANNEL_REPURCHASE);
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
        return attachItems(List.of(orderMapper.selectById(order.getId())), Map.of()).get(0);
    }

    /** 我的订单（含明细）。订单量不大，一次带全，省掉详情页的二次请求 */
    public List<OrderVO> listMyOrders(Long userId) {
        List<Order> orders = orderMapper.selectList(
                Wrappers.<Order>lambdaQuery()
                        .eq(Order::getUserId, userId)
                        .orderByDesc(Order::getCreatedAt)
                        .orderByDesc(Order::getId));
        return attachItems(orders, Map.of());
    }

    /** 订单详情。只能看自己的 —— 传别人的 id 得到 6005，不是 4003（不泄漏"这个单号存在"） */
    public OrderVO getMyOrder(Long userId, Long orderId) {
        Order o = orderMapper.selectById(orderId);
        if (o == null || !o.getUserId().equals(userId)) {
            throw new BizException(ErrorCode.ORDER_NOT_FOUND);
        }
        return attachItems(List.of(o), Map.of()).get(0);
    }

    // ==================================================================
    // 订单（运营端）
    // ==================================================================

    /** 运营端订单列表。status 为空时返回全部，待发货的排在前面 */
    public List<OrderVO> listAllOrders(String status) {
        var q = Wrappers.<Order>lambdaQuery();
        if (StringUtils.hasText(status)) {
            q.eq(Order::getStatus, status.trim().toUpperCase());
        }
        // 待发货优先：运营进这个页面就是来处理待发货的，不该让他往下翻
        q.orderByAsc(Order::getStatus).orderByDesc(Order::getCreatedAt).orderByDesc(Order::getId);
        List<Order> orders = orderMapper.selectList(q);

        // 一次把涉及到的买家全查出来，不在循环里逐个查（同样是 N+1）
        List<Long> userIds = orders.stream().map(Order::getUserId).distinct().toList();
        Map<Long, AppUser> users = userIds.isEmpty()
                ? Map.of()
                : appUserMapper.selectBatchIds(userIds).stream()
                        .collect(Collectors.toMap(AppUser::getId, Function.identity(), (a, b) -> a));
        return attachItems(orders, users);
    }

    /**
     * 标记已发货。
     *
     * <p>校验"当前必须是待发货"：重复点两次不该把 shipped_at 刷新成第二次的时间。
     * 这不是理论问题 —— 运营端列表上按钮挨着，双击很常见。
     */
    @Transactional(rollbackFor = Exception.class)
    public OrderVO shipOrder(Long orderId) {
        Order o = orderMapper.selectById(orderId);
        if (o == null) {
            throw new BizException(ErrorCode.ORDER_NOT_FOUND);
        }
        if (!STATUS_PENDING.equals(o.getStatus())) {
            throw new BizException(ErrorCode.ORDER_STATUS_INVALID, "该订单已经是「已发货」状态");
        }
        o.setStatus(STATUS_SHIPPED);
        o.setShippedAt(LocalDateTime.now());
        orderMapper.updateById(o);

        // ★ 回读一次再返回。理由不只是"稳妥"，是一个真实踩到过的不一致：
        // Java 的 LocalDateTime.now() 带纳秒，而 shipped_at 列是 DATETIME（无小数秒），
        // MySQL 会四舍五入。不回读的话，发货接口返回 `...18:45:08.7085987`，
        // 而用户随后刷新「我的订单」看到的是 `...18:45:09` —— 同一个字段两次读不一样。
        // 前端目前只显示到分钟所以看不出来，但那是运气，不是设计。
        Order saved = orderMapper.selectById(orderId);

        // 回给前端的这一份也带上买家信息，运营端才能整行替换而不是丢掉昵称
        AppUser u = appUserMapper.selectById(saved.getUserId());
        return attachItems(List.of(saved), u == null ? Map.of() : Map.of(saved.getUserId(), u)).get(0);
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
     * 批量补齐明细与买家信息。
     *
     * <p>先一次性把涉及到的 orderId 全部查出来再分组，而不是在循环里逐个订单查 ——
     * 后者是典型的 N+1：运营端一屏 20 个订单就是 21 次查询。
     *
     * @param users userId -> 买家。传空 Map 表示不需要买家信息（用户端看自己的单）
     */
    private List<OrderVO> attachItems(List<Order> orders, Map<Long, AppUser> users) {
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

        List<OrderVO> out = new ArrayList<>(orders.size());
        for (Order o : orders) {
            AppUser u = users.get(o.getUserId());
            out.add(toOrderVO(o, grouped.getOrDefault(o.getId(), List.of()),
                    u == null ? null : u.getNickname(),
                    u == null ? null : u.getEmail()));
        }
        return out;
    }

    private OrderVO toOrderVO(Order o, List<OrderItem> items, String nickname, String email) {
        OrderVO v = new OrderVO();
        v.setId(o.getId());
        v.setOrderNo(o.getOrderNo());
        v.setUserId(o.getUserId());
        v.setBuyerNickname(nickname);
        v.setBuyerEmail(email);
        v.setStatus(o.getStatus());
        v.setStatusLabel(statusLabel(o.getStatus()));
        v.setItemCount(o.getItemCount());
        v.setTotalAmount(o.getTotalAmount());
        v.setReceiverName(o.getReceiverName());
        v.setReceiverPhone(o.getReceiverPhone());
        v.setReceiverAddress(o.getReceiverAddress());
        v.setRemark(o.getRemark());
        v.setChannel(o.getChannel());
        v.setShippedAt(o.getShippedAt());
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
        return v;
    }

    /**
     * 状态中文名。
     *
     * <p>放在服务端算而不是前端维护一份映射：状态是后端定义的，
     * 加一个状态就要通知前端改一次，迟早漏掉某处显示成裸的英文枚举。
     */
    private String statusLabel(String status) {
        if (STATUS_PENDING.equals(status)) {
            return "待发货";
        }
        if (STATUS_SHIPPED.equals(status)) {
            return "已发货";
        }
        return status;
    }
}
