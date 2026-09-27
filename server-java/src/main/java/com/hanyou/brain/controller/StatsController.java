package com.hanyou.brain.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.hanyou.brain.common.Result;
import com.hanyou.brain.service.OpsService;
import com.hanyou.brain.vo.PoiStatVO;

import lombok.RequiredArgsConstructor;

/**
 * 游客端资源点实时统计（M5）。
 *
 * <p><b>为什么单独开一个端点，而不是把承载率塞进 {@code /api/pois} 的 VO：</b>
 * 承载率是**每天变**的动态数据，而资源列表是**基本不变**的静态数据。
 * 混在一起有两个后果：① 列表接口要为 42 个资源点各查一次统计；
 * ② 缓存与刷新策略没法分开——资源列表可以长缓存，承载率必须短缓存。
 *
 * <p>为什么一次返回**全部**资源点而不是按 id 查：探索页与行程规划页
 * 都要在列表里显示承载率，逐个查就是几十个并发请求，而这份数据总共
 * 只有 42 行（约 4KB）。详情页也从这份里取，前端用全局单例缓存
 * （见 {@code web/src/composables/usePoiStats.ts}），与配图的
 * {@code useCovers} 同一套做法。
 *
 * <p>本端点**公开可访问**（SecurityConfig 白名单）：游客不登录也要能看
 * "这个景区今天挤不挤"。运营端的风险与工单在 {@code /api/admin/**} 下，
 * 需要 OPERATOR，两者不共用前缀。
 */
@RestController
@RequestMapping("/api/stats")
@RequiredArgsConstructor
public class StatsController {

    private final OpsService opsService;

    /** 全部资源点的当日承载与到访。数据来自合成客流统计（synthetic=1） */
    @GetMapping("/pois")
    public Result<List<PoiStatVO>> poiStats() {
        return Result.ok(opsService.listPoiStats());
    }
}
