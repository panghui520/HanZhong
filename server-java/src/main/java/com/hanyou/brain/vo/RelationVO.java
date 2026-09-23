package com.hanyou.brain.vo;

import lombok.Data;

/**
 * 关系目标视图。详情页的「周边联动 / 业态融合 / AI 分流建议」都用它。
 *
 * <p>只带列表展示需要的字段，不把目标资源的完整信息塞进来——
 * 目标资源详情由它自己的 /api/pois/{id} 提供。
 */
@Data
public class RelationVO {

    private String id;
    private String name;
    private String businessType;
    private String district;
    private String scene;

    /** 与本资源的球面距离（公里） */
    private Double distanceKm;

    /** 预计通行时间（分钟） */
    private Integer travelMin;

    /** 关系权重，越大越该优先推荐 */
    private Double weight;
}
