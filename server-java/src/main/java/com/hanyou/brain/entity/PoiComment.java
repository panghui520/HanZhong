package com.hanyou.brain.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 景点评论（M10 续）。
 *
 * <p><b>它替掉的是前端的一段伪随机数据。</b>在此之前游客端景点详情页的
 * 「游客反馈」由 {@code web/src/mock/reviews.ts} 的 {@code reviewsOf} 按资源 id
 * 派生 —— 不同景点确实显示不同内容，但那是算出来的，不是任何人写的。
 * 本表是那些话第一次有了真实的作者。
 *
 * <p><b>刻意没有 cityCode</b>：评论是游客写出来的运行期事实，重启不能丢。
 * 带了 city_code 就会随 CityPackImporter 重灌被删掉，用户的评论凭空消失。
 * 与 app_user / orders / cart_item / trip_checkin 同一条约定（见 V8 文件头）。
 *
 * <p><b>刻意没有 nickname 列</b>：署名由 {@code app_user.nickname} 现取，
 * 与 M6 的 order_review 同一做法（见 OrderService.toOrderVO）。在这张表上
 * 另立一套冗余快照，只会让"用户改了昵称，哪一处会变、哪一处不变"变成一个
 * 每次 review 都要重新想一遍的问题。
 *
 * <p><b>为什么 poiId 是 String 而 userId 是 Long</b>：因为 poi 的主键是
 * 数据包给的字符串编码（P-SCE-001），app_user 的主键是自增 BIGINT。
 * 两个来源不同的主键类型，这里如实照搬，不做统一 —— 统一成字符串会让
 * "用户表的主键到底是什么"变得需要解释。
 */
@Data
@TableName("poi_comment")
public class PoiComment {

    /** 待审。当前没有入口会产生这个状态，留着是因为运营可能想先压一批再放 */
    public static final String STATUS_PENDING = "PENDING";

    /**
     * 通过。**游客提交后的默认值**。
     *
     * <p>这是刻意的产品取舍：本项目是比赛演示项目，游客提交后要立刻在页面上
     * 看到自己写的那条 —— 否则演示流程会卡在"等审核"上，而评审现场没有
     * 第二个运营账号去点通过。审核能力保留在管理端（可以改成 HIDDEN），
     * 但默认不拦。这条取舍写进验收记录，答辩时也是这么讲的。
     */
    public static final String STATUS_APPROVED = "APPROVED";

    /** 隐藏。游客端看不到，但数据还在 —— 与"删除"是两回事，可恢复 */
    public static final String STATUS_HIDDEN = "HIDDEN";

    /** 主键。不能用 IdType.INPUT —— 那是给城市数据包的字符串编码用的 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 评论的资源点，-> poi.id。不建外键，引用完整性由服务层保证（与 M1/M2 一致） */
    private String poiId;

    /** 评论人，-> app_user.id */
    private Long userId;

    /** 评论内容。长度由 db/V12 的 CHECK 与服务层双重限制 */
    private String content;

    /** 评分 1..5。TINYINT，db 侧有 CHECK */
    private Integer rating;

    /** PENDING / APPROVED / HIDDEN */
    private String status;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
