package com.hanyou.brain.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 乡村体验项目：游客在乡村点"可以做的事"。
 *
 * <p>它是整条消费链的锚点——每一款农产品都要能追溯到一次具体体验，
 * 所以 experience 不是"商品详情页的装饰"，而是 product 的上游。
 */
@Data
@TableName("experience")
public class Experience {

    @TableId(type = IdType.INPUT)
    private String id;

    private String cityCode;

    /** 所属乡村点（-> poi.id） */
    private String poiId;

    private String name;

    /** TEA / PICKING / FOLK / HOMESTAY / FOOD_MAKING / NATURE */
    private String type;

    private Integer durationMin;
    private BigDecimal price;
    private String season;
    private Integer capacity;

    /** 英文逗号分隔，对外由 VO 转成数组 */
    private String tags;

    /**
     * 体验说明。
     * 数据库列名是 description 而不是 desc——DESC 是 SQL 保留字。
     * 对外 JSON 仍然叫 desc，由 ExperienceVO 转回去，前端类型定义不用改。
     */
    private String description;

    private String dataOrigin;
    private String sourceUrl;
    private Integer status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
