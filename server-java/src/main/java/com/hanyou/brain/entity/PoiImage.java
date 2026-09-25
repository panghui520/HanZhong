package com.hanyou.brain.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 景点配图（M9 媒体与配图管理）。
 *
 * <p>本表**刻意没有 cityCode**：CityPackImporter 每次启动按 city_code 全量删除重灌
 * 业务表，而运营上传的图片是运行期数据，不能因为重启一次服务就全没了。
 *
 * <p>{@code imagePath} 存的是**相对 media-dir 的路径**，不是绝对路径。
 * 存绝对路径的话，换机器、换部署目录、Docker 挂载点一变就全部失效；
 * 相对路径只依赖配置项 {@code hanyou.media-dir}。
 *
 * <p>为什么不设唯一键（同一 poi_id 可以多行）：列表页要封面、详情页要做图集，
 * 一个景点本来就可能有多张。取哪张当封面由 {@code isCover} 决定，不靠排序猜。
 */
@Data
@TableName("poi_image")
public class PoiImage {

    /** 主键。不能用 IdType.INPUT——那是给城市数据包的字符串编码用的，这里要自增 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属资源点，对应 poi.id。不建外键，引用完整性由服务层保证（与 M1/M2 一致） */
    private String poiId;

    /** 相对 media-dir 的路径，如 poi/P-SCE-001/ab12cd34.jpg */
    private String imagePath;

    /** 无障碍替代文本，兼作运营备注 */
    private String altText;

    /** 展示顺序，小的在前 */
    private Integer sortOrder;

    /**
     * 是否封面：0 否 / 1 是。
     *
     * <p>用 Integer 而不是 boolean：MyBatis-Plus 对 boolean 类型的 is 前缀字段
     * 生成的 getter 名容易与属性名不一致（getIsCover vs isCover），
     * 映射时踩过一次坑，用 Integer 省掉这类歧义。
     */
    private Integer isCover;

    /** UPLOAD 运营上传 / SEED 系统预置 */
    private String source;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
