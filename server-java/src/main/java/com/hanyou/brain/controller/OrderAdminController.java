package com.hanyou.brain.controller;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.hanyou.brain.common.BizException;
import com.hanyou.brain.common.BodyReader;
import com.hanyou.brain.common.ErrorCode;
import com.hanyou.brain.common.Result;
import com.hanyou.brain.service.OrderService;
import com.hanyou.brain.vo.OrderVO;

import lombok.RequiredArgsConstructor;

/**
 * 订单处理（M6 运营端）。
 *
 * <p>整个 {@code /api/admin/**} 前缀由 {@link com.hanyou.brain.config.SecurityConfig}
 * 统一要求 OPERATOR 角色，所以这里不再逐个方法标注权限 —— 运营端接口
 * 只要挂在这个前缀下就自动继承，不用（也不该）在这里再写一遍。
 *
 * <p>运营端只做两件事：<b>发货</b>（必须填快递公司 / 预计天数 / 快递单号）
 * 与<b>处理退款</b>（同意或拒绝）。这两件都必须由人决定，所以没有
 * "自动发货"也没有"超时自动同意退款" —— 前者会发错货，
 * 后者会退错钱。能自动化的只有"超时未付款自动取消"，那个不涉及钱货。
 */
@RestController
@RequestMapping("/api/admin/orders")
@RequiredArgsConstructor
public class OrderAdminController {

    private final OrderService orderService;

    /**
     * 全部订单。可选按状态过滤。
     *
     * <p>默认不带 status 时返回全部：运营最需要的是"有没有新单"这个整体感知，
     * 一进来就只看到某个状态，反而要多点一次才放心。
     * 排序在服务端固定为「需要运营动手的排前面 + 时间倒序」，
     * 权重见 OrderService.actionPriority。
     */
    @GetMapping
    public Result<List<OrderVO>> list(@RequestParam(name = "status", required = false) String status) {
        return Result.ok(orderService.listAllOrders(status));
    }

    /**
     * 发货。必须带快递公司、预计到达天数、快递单号。
     *
     * <p>用 {@code Map<String, Object>} 接请求体而不是 {@code Map<String, String>}：
     * 后者在客户端多传一个非标量字段时会抛 HttpMessageNotReadableException，
     * 落到兜底报 9000「服务内部错误」，把客户端的问题说成服务端的（实测踩过）。
     *
     * <p>服务端会校验当前必须是「待发货」：重复点两次不会把发货时间刷成第二次的，
     * 也不会把第一次填的快递单号覆盖掉。运营端列表上按钮挨得很近，
     * 双击是常态而不是边界情况。
     */
    @PatchMapping("/{id}/ship")
    public Result<OrderVO> ship(@PathVariable Long id,
                                @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        return Result.ok(orderService.shipOrder(
                id,
                BodyReader.str(b, "carrier"),
                BodyReader.intOf(b, "eta_days"),
                BodyReader.str(b, "tracking_no")));
    }

    /**
     * 处理退款：同意或拒绝。
     *
     * <p>请求体取 {@code approve}（布尔）与 {@code reply}（处理说明）。
     *
     * <p>{@code approve} 只接受真正的布尔或 "true"/"false" 字符串，不认识的取值
     * <b>直接报参数错</b>，而不是猜一个默认值 —— 猜错的后果是
     * "本来要拒绝的退款被同意了"，那是钱。
     */
    @PostMapping("/{id}/refund")
    public Result<OrderVO> handleRefund(@PathVariable Long id,
                                        @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        Boolean approve = BodyReader.boolOf(b, "approve");
        if (approve == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "approve 必须是 true 或 false");
        }
        return Result.ok(orderService.handleRefund(id, approve, BodyReader.str(b, "reply")));
    }
}
