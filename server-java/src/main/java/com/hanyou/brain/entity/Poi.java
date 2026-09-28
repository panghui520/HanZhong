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

    /**
     * 详细地址（街道门牌）。district 只到区县，这一列补到可导航的粒度。
     *
     * <p>数据包态：pois.json 目前没有这个字段，所以现有 42 条为 NULL ——
     * 我们不替景点编造门牌号。运营接管某条资源后可以补上。
     * 详见 db/V12__m10_poi_edit_comment.sql 文件头。
     */
    private String address;

    /** 对外联系电话。同上，数据包态，包没有就是 NULL */
    private String phone;

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

    /**
     * 承载率预警线（0.01~1.00）。NULL = 用 {@code risk_rule} 的全局阈值。
     *
     * <p>**运营态**，且**只存在于 ADMIN 行上**：设置它的唯一入口是「编辑」，
     * 而编辑一条 PACK 行会先把它接管成 ADMIN（见 AdminResourceServiceImpl.updatePoi）。
     * 所以导入器天然碰不到它，不需要像 status 那样做"重灌后还原"。
     *
     * <p>消费方是 RuleEngine 的 OVERLOAD 规则：景点自己配了就用它，没配回退全局。
     */
    private BigDecimal warningThreshold;

    /** 英文逗号分隔，对外由 PoiVO 转成数组 */
    private String tags;

    private String summary;

    /** 详细介绍正文。summary 是一句话简介，这里是长文（db/V12 新增） */
    private String detail;

    /** 封面插画标识：qinling / terrace / rapeseed / ancient / river */
    private String scene;

    private String dataOrigin;
    private String sourceUrl;

    /**
     * 来源：PACK 数据包导入 / ADMIN 运营在管理端新建（M10）。
     *
     * <p>CityPackImporter 每次启动会按 city_code 全量重建业务表，删除条件
     * 靠这一列收窄成 {@code source = 'PACK'} —— 没有它，运营新建的景点
     * 在后端重启后会被当成"上一次导入的残留"清掉。
     * 详见 db/V11__m10_resource_admin.sql 文件头。
     */
    private String source;

    /** 1 上架 / 0 下架 */
    private Integer status;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
