package com.hanyou.brain.vo;

import com.hanyou.brain.entity.SiteBanner;
import com.hanyou.brain.media.MediaStorageService;

import lombok.Data;

/**
 * 首页轮播图（M9）。
 *
 * <p>{@code url} 在 {@code imagePath} 为空时也是 null —— 前端据此判断
 * "这一帧没有上传图片，改用手写 SVG（scene）渲染"，而不是渲染一个空 src
 * （空 src 会让浏览器去请求当前页面地址，表现为一次莫名其妙的重复加载）。
 */
@Data
public class BannerVO {

    private Long id;
    private Integer sortOrder;
    private String imagePath;

    /** 完整访问地址；为 null 表示未上传图片，前端回落到 scene */
    private String url;

    /** 兜底画面（手写 SVG 变体） */
    private String scene;

    private String eyebrow;
    private String title;
    private String subtitle;
    private String description;
    private String linkUrl;
    private String cta;
    private Integer enabled;

    public static BannerVO from(SiteBanner e) {
        BannerVO v = new BannerVO();
        v.id = e.getId();
        v.sortOrder = e.getSortOrder();
        v.imagePath = e.getImagePath();
        v.url = (e.getImagePath() == null || e.getImagePath().isBlank())
                ? null
                : MediaStorageService.URL_PREFIX + e.getImagePath();
        v.scene = e.getScene();
        v.eyebrow = e.getEyebrow();
        v.title = e.getTitle();
        v.subtitle = e.getSubtitle();
        v.description = e.getDescription();
        v.linkUrl = e.getLinkUrl();
        v.cta = e.getCta();
        v.enabled = e.getEnabled();
        return v;
    }
}
