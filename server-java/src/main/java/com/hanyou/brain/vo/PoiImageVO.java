package com.hanyou.brain.vo;

import com.hanyou.brain.entity.PoiImage;
import com.hanyou.brain.media.MediaStorageService;

import lombok.Data;

/**
 * 景点配图（M9）。
 *
 * <p>同时给出 {@code imagePath} 与 {@code url}：
 * path 是库里的相对路径（管理端做"替换/删除"时要带回来），
 * url 是可直接塞进 {@code <img src>} 的完整地址（游客端渲染用）。
 * 只给 path 的话，前端得自己知道前缀，前缀一改就两头不同步。
 */
@Data
public class PoiImageVO {

    private Long id;
    private String poiId;

    /** 相对 media-dir 的路径 */
    private String imagePath;

    /** 可直接访问的完整地址 */
    private String url;

    private String altText;
    private Integer sortOrder;
    private Integer isCover;
    private String source;

    public static PoiImageVO from(PoiImage e) {
        PoiImageVO v = new PoiImageVO();
        v.id = e.getId();
        v.poiId = e.getPoiId();
        v.imagePath = e.getImagePath();
        // image_path 在库里是 NOT NULL，正常不会为空；这里仍做一次判断，
        // 避免万一出现空值时拼出 /api/media/null 这种看起来能访问、实际 404 的地址
        v.url = (e.getImagePath() == null || e.getImagePath().isBlank())
                ? null
                : MediaStorageService.URL_PREFIX + e.getImagePath();
        v.altText = e.getAltText();
        v.sortOrder = e.getSortOrder();
        v.isCover = e.getIsCover();
        v.source = e.getSource();
        return v;
    }
}
