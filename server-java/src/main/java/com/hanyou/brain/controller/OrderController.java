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
import com.hanyou.brain.common.BodyReader;
import com.hanyou.brain.common.ErrorCode;
import com.hanyou.brain.common.Result;
import com.hanyou.brain.service.OrderService;
import com.hanyou.brain.vo.OrderCountVO;
import com.hanyou.brain.vo.OrderReviewVO;
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

    /**
     * 我的订单计数（顶栏「我的订单」角标用）。
     *
     * <p>路径写在 {@code /{id}} 之前只是为了让读代码的人先看到它；
     * 真正决定优先级的是 Spring 的路径匹配规则 —— 字面量段
     * （{@code count}）优先于变量段（{@code {id}}），所以这个映射
     * 不会被 {@code /{id}} 抢走，也不会因为 id 转不成 Long 而报 400。
     *
     * <p>与 {@code /api/cart/count} 对称：顶栏只要一个数字，
     * 不该为此拉整张订单列表。
     */
    @GetMapping("/count")
    public Result<OrderCountVO> count(@AuthenticationPrincipal AuthUser me) {
        return Result.ok(orderService.countMyOrders(uid(me)));
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
                BodyReader.str(b, "receiver_name"),
                BodyReader.str(b, "receiver_phone"),
                BodyReader.str(b, "receiver_address"),
                BodyReader.str(b, "remark")));
    }

    // ==================================================================
    // 状态流转。每个动作一个接口，而不是一个"通用改状态"的接口。
    //
    // 为什么不做一个 POST /{id}/status 让前端传目标状态：那样服务端就
    // 得在一个方法里写七套校验，而且"用户能不能把订单改成已退款"这种事
    // 变成了参数校验问题，很容易漏掉一个组合。一个动作一个入口，
    // 每个入口只允许一条流转，越权与错状态都天然被挡住。
    //
    // 所有接口都不接受金额参数：金额只在下单那一刻由服务端算定。
    // ==================================================================

    /**
     * 付款。演示级：不接真实支付通道，调一次就是"已付款"。
     *
     * <p>没有请求体 —— 付多少钱由服务端按订单总额算，前端传什么都不看。
     * 真实支付里这里应该是"创建支付单 → 等回调确认"，本项目不做到那一步，
     * 验收记录里也写明了这一点。
     */
    @PostMapping("/{id}/pay")
    public Result<OrderVO> pay(@AuthenticationPrincipal AuthUser me, @PathVariable Long id) {
        return Result.ok(orderService.payOrder(uid(me), id));
    }

    /** 取消订单。只有待付款能直接取消，已付款的要走退款流程 */
    @PostMapping("/{id}/cancel")
    public Result<OrderVO> cancel(@AuthenticationPrincipal AuthUser me, @PathVariable Long id) {
        return Result.ok(orderService.cancelOrder(uid(me), id));
    }

    /** 确认收货。只有已发货能确认；确认后才能评价 */
    @PostMapping("/{id}/receipt")
    public Result<OrderVO> receipt(@AuthenticationPrincipal AuthUser me, @PathVariable Long id) {
        return Result.ok(orderService.confirmReceipt(uid(me), id));
    }

    /**
     * 申请退款。请求体只取 {@code refund_reason} 一个键。
     *
     * <p>同样用 {@code Map<String, Object>} 而不是 {@code Map<String, String>}，
     * 理由见上面 create 方法的注释（非标量字段会把 9000 抛给客户端）。
     */
    @PostMapping("/{id}/refund")
    public Result<OrderVO> refund(@AuthenticationPrincipal AuthUser me,
                                  @PathVariable Long id,
                                  @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        return Result.ok(orderService.requestRefund(uid(me), id, BodyReader.str(b, "refund_reason")));
    }

    /**
     * 评价。请求体取 {@code rating / content / images}。
     *
     * <p>{@code images} 是字符串数组，必须单独取：通用的 {@code str()}
     * 会把数组判为"没传"（那是它刻意的行为，用来挡非法字段），
     * 直接套用会静默丢掉用户上传的图片。
     */
    @PostMapping("/{id}/review")
    public Result<OrderReviewVO> review(@AuthenticationPrincipal AuthUser me,
                                        @PathVariable Long id,
                                        @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        return Result.ok(orderService.createReview(
                uid(me), id, BodyReader.intOf(b, "rating"), BodyReader.str(b, "content"), BodyReader.strList(b, "images")));
    }

    /** 同 CartController：兜底守卫，避免以后被误放行时抛出 NPE */
    private static Long uid(AuthUser me) {
        if (me == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        return me.userId();
    }
}
