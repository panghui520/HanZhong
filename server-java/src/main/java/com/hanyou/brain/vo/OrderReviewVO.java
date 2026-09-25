package com.hanyou.brain.vo;

import java.time.LocalDateTime;
import java.util.List;

import lombok.Data;

/**
 * 评价对外视图（M6）。
 *
 * <p>{@code images} 在库里是 JSON 字符串，**出库时已经解析成 List** ——
 * 前端拿到的是数组，而不是一个还需要自己 JSON.parse 的字符串。
 * 让前端解析会把"库里的存储格式"泄漏到前端，将来换存储方式
 * （改成逗号分隔、或拆成独立表）就得连前端一起改。
 *
 * <p><b>{@code images} 里是可直接塞进 {@code <img src>} 的完整地址，
 * 不是库里的相对路径。</b>与 {@link PoiImageVO} 同一个理由：只给相对路径的话，
 * 前端得自己知道 {@code /api/media/} 这个前缀，前缀一改就两头不同步。
 * 库里存的仍是相对路径（见 {@code OrderReview.images}），拼接发生在出库这一步。
 * 注意方向上的不对称是**刻意**的：提交评价时前端传的是相对路径
 * （即上传接口 {@code POST /api/reviews/images} 返回的 {@code path}），
 * 因为路径校验要挡 {@code ..} 穿越，不能让客户端自由拼前缀。
 *
 * <p>不带 userId：评价展示不需要暴露"是谁评的"，那还是别人的标识符。
 */
@Data
public class OrderReviewVO {

    private Long id;

    private Long orderId;

    /** 星级 1..5 */
    private Integer rating;

    /** 评价文字，可空（只打星也算评价） */
    private String content;

    /** 图片地址（完整 URL，可直接用于 img src）。出库时已由 JSON 字符串解析成数组 */
    private List<String> images;

    private LocalDateTime createdAt;
}
