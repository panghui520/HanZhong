package com.hanyou.brain.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 资源关系。把一堆彼此独立的资源点变成一张可查询的网络。
 *
 * <p>NEARBY 邻近 / SUPPORT 配套 / SAME_VILLAGE 同片区乡村 / DIVERSION 可分流承接。
 * 由 CityPackImporter 按球面距离与业态规则计算生成，不是手填的——
 * 换一个城市，导入新数据包，关系网络自动重建。
 */
@Data
@TableName("poi_relation")
public class PoiRelation {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String cityCode;
    private String fromPoiId;
    private String toPoiId;
    private String relationType;

    /** 球面距离（公里） */
    private BigDecimal distanceKm;

    /** 预计通行时间（分钟，按 40km/h 估算） */
    private Integer travelMin;

    /** 关系权重，用于推荐排序 */
    private BigDecimal weight;

    private LocalDateTime createdAt;
}
