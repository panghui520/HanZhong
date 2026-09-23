package com.hanyou.brain.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 城市档案，对应表 city_profile。
 * 数据来自 citypack/&lt;city&gt;/meta.json，换城市时整包替换。
 */
@Data
@TableName("city_profile")
public class CityProfile {

    @TableId(type = IdType.INPUT)
    private String cityCode;

    private String name;
    private String province;
    private BigDecimal centerLng;
    private BigDecimal centerLat;
    private String tagline;
    private String summary;

    /** PUBLIC 公开资料 / SYNTHETIC 仿真 */
    private String dataOrigin;

    /** 数据来源与仿真声明，前端在页脚与详情页展示 */
    private String disclaimer;

    private String version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
