package com.hanyou.brain.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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
 * <p>本轮只做一件事：把待发货改成已发货。**不做物流单号、不做批量导出、
 * 不做取消退款** —— 那些需要真实的物流与支付通道才成立，摆个空按钮反而
 * 会在答辩时被追问"这个单号填进去干什么"。留作扩展点，见验收记录。
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
     * 一进来就只看到某个状态反而要多点一次才放心。
     * 排序在服务端固定为「待发货优先 + 时间倒序」。
     */
    @GetMapping
    public Result<List<OrderVO>> list(@RequestParam(name = "status", required = false) String status) {
        return Result.ok(orderService.listAllOrders(status));
    }

    /**
     * 标记已发货。
     *
     * <p>服务端会校验当前必须是 PENDING：重复点两次不会把发货时间刷成第二次的。
     * 运营端列表上按钮挨得很近，双击是常态而不是边界情况。
     */
    @PatchMapping("/{id}/ship")
    public Result<OrderVO> ship(@PathVariable Long id) {
        return Result.ok(orderService.shipOrder(id));
    }
}
