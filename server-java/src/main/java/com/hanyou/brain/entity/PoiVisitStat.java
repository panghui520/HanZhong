package com.hanyou.brain.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 客流与经营日度统计（一个资源点 × 一天）。
 *
 * <p><b>本表全部为基于规则的仿真数据</b>（{@code synthetic} 恒为 1），
 * 不是真实统计。生成方式见 {@code scripts/gen_synthetic.py}，
 * 生成结果落在 {@code citypack/hanzhong/visit_stats.json}。
 *
 * <p>为什么占用率要存下来而不是每次用 visitors / capacity 现算：
 * capacity 是"设计承载"，会随数据包更新而变化；现算的话，历史某天的
 * 占用率会跟着今天的 capacity 一起变——那就成了"上个月的数据今天看
 * 又是另一个数"。存下来才是当时的口径。
 *
 * <p>带 {@code city_code}，所以每次启动会被 CityPackImporter 按城市
 * 全量重灌——这正是期望行为：它是数据包的一部分。
 */
@Data
@TableName("poi_visit_stats")
public class PoiVisitStat {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String cityCode;
    private String poiId;

    /** 统计日期。由数据包里的天数偏移（offset）在导入时物化，见 CityPackImporter */
    private LocalDate statDate;

    private Integer visitors;

    /** 当日承载占用率，>1 表示超载 */
    private BigDecimal capacityUsage;

    /** 当日评价数。规则 2「差评激增」的样本量 */
    private Integer reviewCount;

    /** 当日负面评价数 */
    private Integer negativeCount;

    /** 当日参与体验人次。只有乡村点非零，是规则 5 的分母 */
    private Integer experienceVisits;

    /** 当日购买笔数。只有乡村点非零，是规则 5 的分子 */
    private Integer purchases;

    /** 当日复购笔数（purchases 中属于复购的部分）。规则 6 用 */
    private Integer repurchases;

    /** 恒为 true：本表是仿真数据。列存在的意义是让"这是仿真数据"写在数据里 */
    private Boolean synthetic;

    private LocalDateTime createdAt;
}
