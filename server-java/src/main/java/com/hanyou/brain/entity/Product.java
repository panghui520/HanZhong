package com.hanyou.brain.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 乡村农产品。
 *
 * <p>它不是一件孤立商品：必须挂在一次乡村体验（experienceId）或一个产地资源点
 * （poiId）上，数据库用 chk_product_traceable 约束保证这一点。所以前端没有、
 * 也不应该有"农产品商城"这样的独立入口——产品只出现在它所属的体验与乡村页面里。
 * 这条设计红线落在表结构上，而不是靠文档约定。
 */
@Data
@TableName("product")
public class Product {

    @TableId(type = IdType.INPUT)
    private String id;

    private String cityCode;

    /** 产地乡村点（-> poi.id） */
    private String poiId;

    /** 体验锚点（-> experience.id）。与 poiId 至少有一项非空 */
    private String experienceId;

    /** 分类编码（-> product_category.code）。数据包里写的是分类名，导入时由导入器解析 */
    private String categoryCode;

    private String name;
    private String spec;
    private BigDecimal price;
    private String originVillage;
    private Integer stock;

    /** 英文逗号分隔，对外由 ProductVO 转成数组 */
    private String tags;

    /** 溯源文案：这一款与那次体验的关系，是"离境复购"的情感落点 */
    private String story;

    private String scene;
    private String dataOrigin;
    private String sourceUrl;

    /**
     * 来源：PACK 数据包导入 / ADMIN 运营在管理端新建（M10）。
     * 与 {@link Poi#getSource()} 同一列语义，理由见 db/V11__m10_resource_admin.sql。
     */
    private String source;

    /** 1 上架 / 0 下架 */
    private Integer status;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
