package com.hanyou.brain.controller;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.hanyou.brain.common.Result;
import com.hanyou.brain.media.MediaService;
import com.hanyou.brain.vo.BannerVO;
import com.hanyou.brain.vo.PoiImageVO;

import lombok.RequiredArgsConstructor;

/**
 * 媒体只读接口（M9）。游客端用，不需要登录。
 *
 * <p>三条路径都只读，写操作全部在 {@code /api/admin/media/**}（见 MediaAdminController）。
 * 读写分开两条前缀，而不是同一条路径上靠 HTTP 方法区分，是为了让
 * {@code SecurityConfig} 的规则能一句话写清楚：
 * 公开的只有 {@code GET /api/media/**}，其余落到兜底的"需要登录"。
 *
 * <p>注意上传的图片文件本身也在这个前缀下（{@code /api/media/poi/xxx/yyy.jpg}），
 * 由 {@code WebConfig} 的静态资源映射直出，不经过本类。
 */
@RestController
@RequestMapping("/api/media")
@RequiredArgsConstructor
public class MediaController {

    private final MediaService mediaService;

    /**
     * 首页轮播。只返回启用中的帧。
     *
     * <p>每帧的 {@code url} 可能为 null —— 表示运营还没给这帧传图，
     * 前端应当用手写 SVG（{@code scene} 字段）渲染。这是"离线可演示"的兜底：
     * 没有一张真实图片时，首页依然是一幅完整的画面，而不是空白或破图。
     */
    @GetMapping("/banners")
    public Result<List<BannerVO>> banners() {
        return Result.ok(mediaService.listBanners(true));
    }

    /**
     * 景点配图列表。
     *
     * <p>不传 {@code poi_id} 返回全部（前端一次取回、自己按 poi_id 分组），
     * 传了就只返回该景点的。详情页用后者，列表页用 {@link #covers()}。
     */
    @GetMapping("/poi-images")
    public Result<List<PoiImageVO>> poiImages(
            @RequestParam(name = "poi_id", required = false) String poiId) {
        return Result.ok((poiId == null || poiId.isBlank())
                ? mediaService.listAllPoiImages()
                : mediaService.listPoiImages(poiId));
    }

    /**
     * 每个景点的封面图映射：{@code poi_id -> url}。
     *
     * <p>列表页几十张卡片，逐张发请求不现实。这里一次给全，
     * 前端拿不到的 poi_id 就回落到手写 SVG —— 也就是"还没传图的景点"
     * 与"传了图的景点"在同一个页面上能共存，不会出现半屏破图。
     */
    @GetMapping("/poi-images/covers")
    public Result<Map<String, String>> covers() {
        return Result.ok(mediaService.coverMap());
    }
}
