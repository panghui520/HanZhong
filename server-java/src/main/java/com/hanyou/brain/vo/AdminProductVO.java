package com.hanyou.brain.vo;

import java.time.LocalDateTime;
import java.util.List;

import lombok.Data;

/**
 * 管理端农产品视图（M10）。
 *
 * <p>与游客端的 {@code ProductVO} 分开的理由同 {@link AdminPoiVO}：
 * 管理端多出 {@code status} / {@code source} / 时间戳这些"运营才关心"的字段。
 *
 * <p>{@code poiName} / {@code experienceName} 与 {@code category} 都是
 * **查出来补上的**，不是产品行上的列：产品只存 id，中文名由
 * {@code NameResolver} 批量解析。这一点与游客端一致 ——
 * 管理端列表也要显示"这袋米挂在哪个村、哪次体验下"，
 * 因为那正是本项目"不是电商"的那条红线在界面上的体现。
 */
@Data
public class AdminProductVO {

    private String id;
    private String name;

    private String categoryCode;

    /** 分类中文名（由 categoryCode 解析） */
    private String category;

    private String spec;
    private Double price;
    private String originVillage;
    private Integer stock;
    private List<String> tags;

    /** 溯源文案：这一款与那次体验的关系 */
    private String story;

    private String scene;

    /** 产地乡村点（-> poi.id） */
    private String poiId;
    private String poiName;

    /** 体验锚点（-> experience.id） */
    private String experienceId;
    private String experienceName;

    private String dataOrigin;
    private String sourceUrl;

    /** 1 上架 / 0 下架 */
    private Integer status;

    /** PACK 数据包 / ADMIN 运营新建 */
    private String source;

    /** 中文名：城市数据包 / 运营新建。**服务端算**，前端不映射 */
    private String sourceLabel;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
