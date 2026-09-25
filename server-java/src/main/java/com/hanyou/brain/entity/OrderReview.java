package com.hanyou.brain.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 订单评价（M6）。
 *
 * <p>一单一评，靠 {@code order_review.uk_order} 唯一键保证。有了唯一键，
 * 服务端就不必"先查有没有评过、再决定插不插"—— 那种写法在并发下会漏
 * （两个请求同时查到"没评过"），唯一键让数据库来兜底，重复提交直接失败。
 *
 * <p>{@code images} 存的是**相对路径的 JSON 数组字符串**（如
 * {@code ["a.jpg","b.jpg"]}），不是逗号分隔。用 JSON 而不是逗号，
 * 是因为文件名里出现逗号并非不可能（运营手工改名之后尤其常见），
 * 那时逗号分隔会静默地把一个文件名劈成两个。
 *
 * <p>图片文件本身在磁盘上（复用 M9 的存储与静态服务），这里只存路径 ——
 * 与 poi_image 的做法一致，理由也一样：mysqldump 不该被二进制塞满。
 *
 * <p>{@code userId} 是冗余字段（顺着 orderId 也能查到下单人）。
 * 留着它是为了让"某用户的历史评价"一次查询搞定，不必先连订单表。
 */
@Data
@TableName("order_review")
public class OrderReview {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** -> orders.id */
    private Long orderId;

    /** -> app_user.id，评价人（冗余，见类注释） */
    private Long userId;

    /** 星级 1..5 */
    private Integer rating;

    /** 评价文字，可空（只打星也算评价） */
    private String content;

    /** 图片相对路径的 JSON 数组字符串，最多 3 张 */
    private String images;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
