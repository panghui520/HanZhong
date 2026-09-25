package com.hanyou.brain.controller;

import java.util.List;
import java.util.Map;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
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
import com.hanyou.brain.vo.CartItemVO;

import lombok.RequiredArgsConstructor;

/**
 * 购物车（M6 消费与离境复购）。
 *
 * <p><b>整车都要求登录，没有匿名购物车。</b>原因不是"懒"：
 * 匿名车要靠 Cookie 或 localStorage 认领，一旦用户中途登录，
 * 就要面对"这辆匿名车归谁"的合并问题（同商品数量怎么并、并错了怎么办）。
 * 本项目下单必须登录，那不如从加购这一步就要求登录 ——
 * 少一个状态，也少一类只在边界情况下才出现的 bug。
 *
 * <p>用 {@code @AuthenticationPrincipal AuthUser} 直接拿 userId，
 * 不去解析令牌、也不按邮箱反查用户。这是 M8 专门留出来的注入点。
 */
@RestController
@RequestMapping("/api/cart")
@RequiredArgsConstructor
public class CartController {

    private final OrderService orderService;

    /** 我的购物车。空车返回空数组，不是 404 —— "没东西"是正常状态 */
    @GetMapping
    public Result<List<CartItemVO>> list(@AuthenticationPrincipal AuthUser me) {
        return Result.ok(orderService.listCart(uid(me)));
    }

    /**
     * 购物车件数（数量之和）。顶栏角标用。
     *
     * <p>单独开一个接口而不是让前端取列表再自己算：角标在每个页面都要显示，
     * 拉整张购物车只为算一个数字太浪费。
     */
    @GetMapping("/count")
    public Result<Integer> count(@AuthenticationPrincipal AuthUser me) {
        return Result.ok(orderService.cartCount(uid(me)));
    }

    /** 加购。同一商品重复加购是数量累加，不是新增一行 */
    @PostMapping
    public Result<CartItemVO> add(@AuthenticationPrincipal AuthUser me,
                                  @RequestBody(required = false) Map<String, Object> body) {
        return Result.ok(orderService.addToCart(
                uid(me), str(body, "product_id"), intOf(body, "quantity")));
    }

    /** 改数量。数量必须 >= 1；要移除请用 DELETE，不要传 0 */
    @PatchMapping("/{id}")
    public Result<CartItemVO> update(@AuthenticationPrincipal AuthUser me,
                                     @PathVariable Long id,
                                     @RequestBody(required = false) Map<String, Object> body) {
        return Result.ok(orderService.updateQuantity(uid(me), id, intOf(body, "quantity")));
    }

    @DeleteMapping("/{id}")
    public Result<Map<String, Object>> remove(@AuthenticationPrincipal AuthUser me, @PathVariable Long id) {
        orderService.removeCartItem(uid(me), id);
        return Result.ok(Map.of("deleted", true));
    }

    /** 清空购物车 */
    @DeleteMapping
    public Result<Map<String, Object>> clear(@AuthenticationPrincipal AuthUser me) {
        orderService.clearCart(uid(me));
        return Result.ok(Map.of("cleared", true));
    }

    /**
     * 取当前用户 id。
     *
     * <p>这几个接口都落在 SecurityConfig 的 {@code anyRequest().authenticated()} 里，
     * {@code me} 不该为 null。但万一以后有人图省事把它们改成 permitAll，
     * 这里给一个明确的 4001，而不是让 NullPointerException 落到兜底分支
     * 变成一句"服务内部错误"——那种报错要查半天。
     */
    private static Long uid(AuthUser me) {
        if (me == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        return me.userId();
    }

    /** JSON 里的数字可能是 Integer，也可能是字符串（前端表单值都是字符串），两种都要认 */
    private static Integer intOf(Map<String, Object> body, String key) {
        Object v = body == null ? null : body.get(key);
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.valueOf(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String str(Map<String, Object> body, String key) {
        Object v = body == null ? null : body.get(key);
        return v == null ? null : String.valueOf(v);
    }
}
