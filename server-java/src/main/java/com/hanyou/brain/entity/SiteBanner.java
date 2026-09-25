package com.hanyou.brain.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 首页轮播图（M9 媒体与配图管理）。
 *
 * <p>与 {@link PoiImage} 同理，本表没有 cityCode，不会被 CityPackImporter 重灌。
 *
 * <p>{@code imagePath} 允许为空 —— 为空时前端回落到 {@code scene} 指定的手写 SVG。
 * 这个设计让"离线可演示"这条底线在任何状态下都成立：
 * 运营把图删了、图挂了、或者还没来及传，首页依然是一幅完整的画面，而不是空白或破图。
 */
@Data
@TableName("site_banner")
public class SiteBanner {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 轮播顺序，小的在前 */
    private Integer sortOrder;

    /** 相对 media-dir 的路径；为空则回落到 scene 的手写 SVG */
    private String imagePath;

    /** 兜底画面：SceneArt 的 7 个变体之一（qinling/terrace/rapeseed/ancient/river/hanjiang/hantai） */
    private String scene;

    private String eyebrow;
    private String title;
    private String subtitle;
    private String description;

    /** 点击跳转地址（站内路径） */
    private String linkUrl;

    /** 按钮文案 */
    private String cta;

    /** 是否启用：0 隐藏 / 1 显示 */
    private Integer enabled;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
