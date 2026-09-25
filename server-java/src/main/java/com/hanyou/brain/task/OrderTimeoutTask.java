package com.hanyou.brain.task;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.hanyou.brain.service.OrderService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 待付款订单的超时取消（M6）。
 *
 * <p><b>扫描频率为什么是 60 秒：</b>订单超时是"分钟级"的容忍度 ——
 * 用户不会因为第 61 秒才被取消而投诉。再密就是白耗数据库连接。
 * 也不宜更稀：太稀的话，用户在"其实已经过期"的订单上点付款会先失败一次
 * （{@code OrderService.payOrder} 里有兜底分支，会在付款时顺手把它取消），
 * 体验上就是"明明超时了、页面上还写着待付款"。
 *
 * <p><b>为什么用 {@code fixedDelay} 而不是 {@code fixedRate}：</b>
 * fixedDelay 是"上一次跑完后再等 60 秒"，fixedRate 是"每 60 秒起一次"。
 * 万一某次扫描因为数据库慢而超过了 60 秒，fixedRate 会让两次扫描重叠，
 * 而重叠没有任何好处 —— 本任务本来就是幂等的，重叠只会浪费连接。
 *
 * <p><b>单机部署的假设：</b>这里直接用 {@code @Scheduled}。多实例部署时
 * 必须换成分布式锁或带抢占的批量更新，否则两个实例会同时扫同一批订单。
 * 虽然重复执行是幂等的（第二次 UPDATE 影响 0 行），但那是巧合而不是设计，
 * 不该依赖 —— 本项目当前是单机，所以先记下这个前提。
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class OrderTimeoutTask {

    private final OrderService orderService;

    /**
     * 启动后先等 30 秒再开始扫。
     *
     * <p>不立刻扫是因为应用刚起来时 CityPackImporter 正在导入数据，
     * 这时候再去争数据库资源没有意义，而且第一轮扫到的东西和 30 秒后一样。
     */
    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void cancelExpiredOrders() {
        int n = orderService.cancelExpiredOrders();
        if (n > 0) {
            // 分钟数从 OrderService 取，不在这里写死：改了支付时限而日志还印旧数字，
            // 排查问题的人会照着错的数去算
            log.info("[订单超时] 已自动取消 {} 笔超过 {} 分钟未付款的订单",
                    n, orderService.payWindowMinutes());
        }
    }
}
