package com.hanyou.brain.task;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.hanyou.brain.service.DiversionService;
import com.hanyou.brain.service.OpsService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 启动后跑一次规则扫描（M5）。
 *
 * <p><b>为什么必须有这个：</b>规则扫描的输入是 {@code poi_visit_stats}，
 * 而它是 {@code CityPackImporter} 在启动时灌进去的。如果没有这一步，
 * 重启服务后 {@code risk_event} 就是一张空表 —— 运营大屏的"待处置风险"
 * 显示 0 条，看起来像"今天很太平"，实际是"规则根本没跑"。
 * 这种"静默为空"是最难发现的一类问题，所以宁可启动时自动跑一次。
 *
 * <p><b>与手动接口的关系：</b>这里跑一次只是让系统"开机就有数据"。
 * 运营改了阈值想立刻看效果，走 {@code POST /api/admin/ops/scan}，
 * 不需要重启。两者调的是同一个 {@code OpsService.scan()}，
 * 且都是幂等的（同一条 规则+资源点+日期 只留一条事件）。
 * 分流公告的过期清理挂在这两处（见下），与扫描同一个节拍。
 *
 * <p><b>顺序为什么是 {@code @Order(2)}：</b>必须排在
 * {@code CityPackImporter}（{@code @Order(1)}）之后，否则扫的是空表。
 * 这一点在导入器的类注释里也记了一笔 —— 顺序错了不会报错，只会静默为空。
 *
 * <p><b>为什么把异常吞掉而不是让它冒出去：</b>扫描失败只意味着"风险列表暂时是空的"，
 * 而让异常冒出去会直接终止应用启动 —— 后者严重得多。数据已经导完了，
 * 手动调一次扫描接口就能补上。所以这里记 error 日志后继续，
 * 并在日志里明确写出补救方式。
 */
@Component
@Order(2)
@Slf4j
@RequiredArgsConstructor
public class OpsScanRunner implements ApplicationRunner {

    private final OpsService opsService;
    private final DiversionService diversionService;

    @Override
    public void run(ApplicationArguments args) {
        try {
            int hit = opsService.scan();
            log.info("[M5] 启动扫描完成，命中 {} 条风险事件", hit);
        } catch (Exception e) {
            log.error("[M5] 启动扫描失败。数据包已导入，规则未跑。"
                    + "登录运营端后调 POST /api/admin/ops/scan 可补跑，无需重启。", e);
        }
        // 公告过期清理挂在这里，与扫描同一个节拍。
        // **它不是游客端正确性的前提** —— 游客端查询本身带了
        // `expire_at > NOW()`，服务停了一夜也不会把过期公告发出去。
        // 这一步只是让运营列表看得准（"已发布"里不该混着昨天到期的）。
        try {
            int expired = diversionService.expireOverdue();
            if (expired > 0) {
                log.info("[M5] 启动清理过期分流公告 {} 条", expired);
            }
        } catch (Exception e) {
            log.error("[M5] 分流公告过期清理失败。游客端仍不会看到过期公告"
                    + "（查询带时间条件），只是运营列表里的状态可能不准。", e);
        }
    }
}
