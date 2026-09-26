package com.hanyou.brain.controller;

import java.util.Map;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.hanyou.brain.auth.AuthUser;
import com.hanyou.brain.common.BizException;
import com.hanyou.brain.common.ErrorCode;
import com.hanyou.brain.common.Result;
import com.hanyou.brain.service.TripService;
import com.hanyou.brain.vo.TripContextVO;

import lombok.RequiredArgsConstructor;

/**
 * 当前行程与它的上下文（M4 智能行程）。
 *
 * <p>路径里的 {@code current} 是刻意的：阶段二一个用户只有一次"当前行程"，
 * 前端不需要知道行程 id，也就不存在"把别人的行程 id 传进来"这类越权面。
 * 阶段五要做多段行程时，再在这组接口旁边加 {@code /api/trips/{code}}，
 * 而不是现在就把 id 暴露出去、之后再补权限判断。
 *
 * <p>这一组**全部要求登录**（见 SecurityConfig）：行程是"我的数据"，
 * 与 M6 的购物车同理。未登录用户仍然可以用 AI 助手，只是助手没有记忆 ——
 * 这与"未登录不能用购物车"不同，因为助手的核心价值在问答本身，
 * 而记忆是叠加在它之上的能力，缺了它页面依然成立。
 */
@RestController
@RequestMapping("/api/trips")
@RequiredArgsConstructor
public class TripController {

    private final TripService tripService;

    /**
     * 当前行程。**没有就创建一个**（懒创建，见 TripService 类注释）。
     *
     * <p>助手页一进来就调它：既是"读当前行程"，也是"这次旅行从此开始"。
     */
    @GetMapping("/current")
    public Result<TripContextVO> current(@AuthenticationPrincipal AuthUser me) {
        return Result.ok(tripService.current(uid(me)));
    }

    /**
     * 选择/更换住处。
     *
     * <p>请求体是前端刚收到的那张高德卡片，只取五个键：
     * {@code poi_id / name / address / longitude / latitude}（外加可选的 source）。
     * 多传什么都不影响 —— 用 Map 接住而不是 DTO，理由见 {@code BodyReader} 的注释。
     *
     * <p>**这里不做真实预订。**没有库存、没有支付、没有订单，也不调
     * 美团/携程的内部接口。它只做一件事：让助手记住"用户住哪"。
     * 卡片上"去第三方预订"是跳转链接，跳走之后的事情不在本系统内。
     */
    @PostMapping("/current/hotel")
    public Result<TripContextVO> selectHotel(@AuthenticationPrincipal AuthUser me,
                                            @RequestBody(required = false) Map<String, Object> body) {
        return Result.ok(tripService.selectHotel(uid(me), body == null ? Map.of() : body));
    }

    /**
     * 取消已选住处。
     *
     * <p>用 DELETE 而不是 POST 一个 "clear=true"：它确实是"移除这个子资源"。
     * 返回整个上下文而不是 {@code {"deleted": true}}，因为前端拿到后要
     * 立刻重画上下文条与卡片高亮，返回新状态就省掉一次往返。
     */
    @DeleteMapping("/current/hotel")
    public Result<TripContextVO> clearHotel(@AuthenticationPrincipal AuthUser me) {
        return Result.ok(tripService.clearHotel(uid(me)));
    }

    /**
     * 取当前用户 id。
     *
     * <p>这组接口在 SecurityConfig 里是 {@code authenticated()}，{@code me}
     * 不该为 null。但万一以后有人把它们改成 permitAll，这里给一个明确的 4001，
     * 而不是让 NullPointerException 落到兜底分支变成"服务内部错误"。
     * 与 {@code CartController.uid} 同一写法。
     */
    private static Long uid(AuthUser me) {
        if (me == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        return me.userId();
    }
}
