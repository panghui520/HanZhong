package com.hanyou.brain.controller;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.hanyou.brain.common.Result;
import com.hanyou.brain.entity.PoiImage;
import com.hanyou.brain.entity.SiteBanner;
import com.hanyou.brain.media.MediaService;
import com.hanyou.brain.vo.BannerVO;
import com.hanyou.brain.vo.PoiImageVO;

import lombok.RequiredArgsConstructor;

/**
 * 媒体管理接口（M9）。运营端用，需要 OPERATOR 角色。
 *
 * <p>挂在 {@code /api/admin/**} 下，因此权限由 {@code SecurityConfig} 里那条
 * {@code ADMIN_PREFIX -> hasRole("OPERATOR")} 统一兜住，本类不需要再写注解。
 * 这样"哪些接口需要什么角色"只有一处可查，不会散落在各个 Controller 上。
 *
 * <p><b>关于文件参数一律写 {@code required = false}：</b>
 * 不是忘了加校验，而是刻意让校验发生在 {@code MediaStorageService.save()} 里 ——
 * 那里对"空文件"返回 5001「请选择要上传的图片」这个有业务含义的错误码。
 * 若交给 Spring 拦，会抛 MissingServletRequestPartException，
 * 前端只能看到一个没有指向性的 9000，运营不知道该做什么。
 *
 * <p><b>关于用实体接 {@code @RequestBody}：</b>
 * 改备注、改文案这类请求只有几个字段，为它们各建一个 DTO 收益不大。
 * 安全性由 Service 保证 —— 它逐字段拷贝白名单内的属性，
 * 请求体里多塞 {@code image_path} / {@code id} 也不会被采纳。
 */
@RestController
@RequestMapping("/api/admin/media")
@RequiredArgsConstructor
public class MediaAdminController {

    private final MediaService mediaService;

    // ============================================================
    // 景点配图
    // ============================================================

    /**
     * 配图列表。不传 {@code poi_id} 返回全部（管理端总览用），
     * 传了只返回该景点的（某个景点的图片管理面板用）。
     */
    @GetMapping("/poi-images")
    public Result<List<PoiImageVO>> listPoiImages(
            @RequestParam(name = "poi_id", required = false) String poiId) {
        return Result.ok((poiId == null || poiId.isBlank())
                ? mediaService.listAllPoiImages()
                : mediaService.listPoiImages(poiId));
    }

    /** 给某个景点新增一张配图。该景点原本没图时，新图自动成为封面 */
    @PostMapping("/poi-images")
    public Result<PoiImageVO> uploadPoiImage(
            @RequestParam(name = "poi_id", required = false) String poiId,
            @RequestParam(name = "file", required = false) MultipartFile file,
            @RequestParam(name = "alt_text", required = false) String altText) {
        return Result.ok(mediaService.uploadPoiImage(poiId, file, altText));
    }

    /**
     * 替换某张配图的文件，保留它的排序位置与封面身份。
     *
     * <p>与"删掉再传一张"是两件事：运营只是想把这张照片换掉，
     * 不该顺带把它在列表里的位置和封面一起弄丢。
     */
    @PutMapping("/poi-images/{id}/image")
    public Result<PoiImageVO> replacePoiImage(
            @PathVariable Long id,
            @RequestParam(name = "file", required = false) MultipartFile file) {
        return Result.ok(mediaService.replacePoiImage(id, file));
    }

    /**
     * 改备注 / 设为封面。请求体：{@code {"alt_text": "...", "is_cover": 1}}。
     *
     * <p>两个字段都是可选的，只传要改的那个。{@code is_cover} 用 0/1
     * （与库里一致，前端不必做 boolean 转换）；设为 1 时同景点的其它图自动取消封面。
     */
    @PatchMapping("/poi-images/{id}")
    public Result<PoiImageVO> updatePoiImage(
            @PathVariable Long id,
            @RequestBody(required = false) PoiImage body) {
        PoiImage b = body == null ? new PoiImage() : body;
        return Result.ok(mediaService.updatePoiImage(id, b.getAltText(), b.getIsCover()));
    }

    /** 删除一张配图。若删的正是封面，剩下的第一张自动升为封面 */
    @DeleteMapping("/poi-images/{id}")
    public Result<Map<String, Object>> deletePoiImage(@PathVariable Long id) {
        mediaService.deletePoiImage(id);
        return Result.ok(Map.of("deleted", true));
    }

    /**
     * 重排某个景点的配图顺序。
     *
     * <p>请求体是<b>期望的完整顺序</b>：{@code [3, 1, 2]}，即 imageId 的数组，
     * 不是若干次两两交换。前端拖拽结束后把当前 DOM 顺序直接发过来即可，
     * 服务端会校验这批 id 恰好等于该景点的全部图片（不多不少），
     * 避免"只传了一部分"导致没提到的图被排到不确定的位置上。
     *
     * <p>{@code poi_id} 放在查询参数里，请求体就只是一个纯粹的数组 ——
     * 这样 Jackson 能按声明的泛型直接反序列化成 {@code List<Long>}，
     * 不用先收成 {@code List<Object>} 再逐个把 Integer 转 Long。
     */
    @PutMapping("/poi-images/order")
    public Result<List<PoiImageVO>> reorderPoiImages(
            @RequestParam(name = "poi_id", required = false) String poiId,
            @RequestBody(required = false) List<Long> imageIds) {
        return Result.ok(mediaService.reorderPoiImages(poiId, imageIds));
    }

    // ============================================================
    // 首页轮播图
    // ============================================================

    /** 轮播列表。含已停用的帧 —— 管理端要能看到并重新启用它们 */
    @GetMapping("/banners")
    public Result<List<BannerVO>> listBanners() {
        return Result.ok(mediaService.listBanners(false));
    }

    /**
     * 新建一帧。图片可选：先把文案配好、回头再传图是常见的操作顺序，
     * 没传图的帧在前端会回落到手写 SVG，不会开天窗。
     */
    @PostMapping("/banners")
    public Result<BannerVO> createBanner(
            @RequestParam(name = "file", required = false) MultipartFile file,
            @RequestParam(name = "scene", required = false) String scene,
            @RequestParam(name = "eyebrow", required = false) String eyebrow,
            @RequestParam(name = "title", required = false) String title,
            @RequestParam(name = "subtitle", required = false) String subtitle,
            @RequestParam(name = "description", required = false) String description,
            @RequestParam(name = "link_url", required = false) String linkUrl,
            @RequestParam(name = "cta", required = false) String cta,
            @RequestParam(name = "enabled", required = false) Integer enabled) {
        SiteBanner fields = new SiteBanner();
        fields.setScene(scene);
        fields.setEyebrow(eyebrow);
        fields.setTitle(title);
        fields.setSubtitle(subtitle);
        fields.setDescription(description);
        fields.setLinkUrl(linkUrl);
        fields.setCta(cta);
        fields.setEnabled(enabled);
        return Result.ok(mediaService.createBanner(file, fields));
    }

    /**
     * 改文案 / 启停。请求体：{@code {"title": "...", "enabled": 0}}，字段都可选。
     *
     * <p>刻意不动图片 —— 换图有单独的接口。混在一起的话，运营改一句标题
     * 却因为表单里恰好带着一个文件把图覆盖掉，是很难排查的事故。
     */
    @PatchMapping("/banners/{id}")
    public Result<BannerVO> updateBanner(
            @PathVariable Long id,
            @RequestBody(required = false) SiteBanner body) {
        return Result.ok(mediaService.updateBanner(id, body == null ? new SiteBanner() : body));
    }

    /** 替换某一帧的图片 */
    @PutMapping("/banners/{id}/image")
    public Result<BannerVO> replaceBannerImage(
            @PathVariable Long id,
            @RequestParam(name = "file", required = false) MultipartFile file) {
        return Result.ok(mediaService.replaceBannerImage(id, file));
    }

    /**
     * 清除某一帧的图片，回到手写 SVG 兜底画面。
     *
     * <p>与"删除这一帧"不同：帧的文案与链接都留着，只是不再用实拍图。
     * 上传的图选得不好时，运营可以先撤掉图，而不是把整帧都删了重配。
     */
    @DeleteMapping("/banners/{id}/image")
    public Result<BannerVO> clearBannerImage(@PathVariable Long id) {
        return Result.ok(mediaService.clearBannerImage(id));
    }

    /** 删除整帧 */
    @DeleteMapping("/banners/{id}")
    public Result<Map<String, Object>> deleteBanner(@PathVariable Long id) {
        mediaService.deleteBanner(id);
        return Result.ok(Map.of("deleted", true));
    }

    /**
     * 重排轮播顺序。语义与 {@link #reorderPoiImages} 一致：
     * 请求体是期望的完整顺序（bannerId 数组），必须覆盖全部帧。
     */
    @PutMapping("/banners/order")
    public Result<List<BannerVO>> reorderBanners(
            @RequestBody(required = false) List<Long> bannerIds) {
        return Result.ok(mediaService.reorderBanners(bannerIds));
    }
}
