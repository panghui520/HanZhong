package com.hanyou.brain.controller;

import java.util.List;
import java.util.Map;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.hanyou.brain.auth.AuthUser;
import com.hanyou.brain.common.BizException;
import com.hanyou.brain.common.ErrorCode;
import com.hanyou.brain.common.Result;
import com.hanyou.brain.service.OrderService;
import com.hanyou.brain.vo.OrderVO;

import lombok.RequiredArgsConstructor;

/**
 * 我的订单（M6 消费与离境复购，用户端）。
 *
 * <p>下单接口只接受**收货信息**，不接受商品列表也不接受金额。
 * 买什么从服务端购物车读，多少钱由服务端按库里价格算 ——
 * 前端传过来的价格一律不看。这是电商类功能最经典的漏洞入口：
 * 只要前端能传单价，抓个包改成 0.01 就成交了。
 *
 * <p>订单查询一律带 userId 过滤（见 OrderService.getMyOrder），
 * 不存在"知道单号就能看别人订单"的口子。
 */
@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    /** 我的订单列表（含明细），按时间倒序 */
    @GetMapping
    public Result<List<OrderVO>> list(@AuthenticationPrincipal AuthUser me) {
        return Result.ok(orderService.listMyOrders(uid(me)));
    }

    /** 订单详情 */
    @GetMapping("/{id}")
    public Result<OrderVO> detail(@AuthenticationPrincipal AuthUser me, @PathVariable Long id) {
        return Result.ok(orderService.getMyOrder(uid(me), id));
    }

    /**
     * 提交订单。把购物车里全部商品结算成一单。
     *
     * <p>刻意不做"只结算勾选的几件"：那要求前端把选中的行 id 传上来，
     * 服务端还得校验这些 id 确实属于本用户的购物车，多一层校验换来的
     * 只是"少买两件"的便利。要少买就先在购物车里删掉。
     *
     * <p><b>请求体用 {@code Map<String, Object>} 而不是 {@code Map<String, String>}。</b>
     * 这不是随手写的：声明成 {@code Map<String, String>} 时，只要客户端多传一个
     * 非字符串字段（比如把整个订单对象回传、或传 {@code "items": [...]}），
     * Jackson 就抛 {@code Cannot deserialize value of type String from Array value}，
     * 落到兜底分支报 9000「服务内部错误」—— 而实际问题在客户端，
     * 这个报错会把排查方向带偏。实测踩到过。
     * 用 Object 接住、只取自己认识的四个键，多传什么都不影响。
     */
    @PostMapping
    public Result<OrderVO> create(@AuthenticationPrincipal AuthUser me,
                                  @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        return Result.ok(orderService.createOrder(
                uid(me),
                str(b, "receiver_name"),
                str(b, "receiver_phone"),
                str(b, "receiver_address"),
                str(b, "remark")));
    }

    /** 只认字符串值。数字/布尔也接（前端表单值可能被 JSON 化成数字），其余一律当没传 */
    private static String str(Map<String, Object> body, String key) {
        Object v = body.get(key);
        if (v == null || v instanceof Map || v instanceof Iterable || v.getClass().isArray()) {
            return null;
        }
        return String.valueOf(v);
    }

    /** 同 CartController：兜底守卫，避免以后被误放行时抛出 NPE */
    private static Long uid(AuthUser me) {
        if (me == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        return me.userId();
    }
}
