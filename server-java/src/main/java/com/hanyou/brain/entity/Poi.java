package com.hanyou.brain.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 文旅资源点（多业态统一模型）。
 * 景区 / 乡村 / 餐饮 / 住宿 / 交通 / 购物共用这一张表，靠 businessType 区分——
 * 这是"多业态融合"在数据层的落点，而不是六张各管一段的表。
 */
@Data
@TableName("poi")
public class Poi {

    /** 资源编码，如 P-SCE-001。由 CityPack 指定，不用自增 */
    @TableId(type = IdType.INPUT)
    private String id;

    private String cityCode;
    private String name;

    /** SCENIC / RURAL_SPOT / FOOD / LODGING / TRANSPORT / SHOPPING */
    private String businessType;

    private String district;

    /** level 是 MySQL 的非保留关键字，加反引号确保生成的 SQL 在任何模式下都合法 */
    @TableField("`level`")
    private String level;

    private BigDecimal lng;
    private BigDecimal lat;
    private BigDecimal ticketPrice;
    private String openHours;
    private Integer durationMin;

    /** 设计承载上限（估算值） */
    private Integer capacity;

    /** 英文逗号分隔，对外由 PoiVO 转成数组 */
    private String tags;

    private String summary;

    /** 封面插画标识：qinling / terrace / rapeseed / ancient / river */
    private String scene;

    private String dataOrigin;
    private String sourceUrl;

    /** 1 上架 / 0 下架 */
    private Integer status;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
