package com.hanyou.brain.vo;

import java.util.List;

import lombok.Data;

/**
 * 资源点对外视图。
 *
 * <p>Java 侧字段是驼峰，序列化时由 JacksonConfig 统一转成 snake_case
 * （businessType → business_type），与 web/src/types/index.ts 里的 Poi 定义逐字对应。
 * 这样前端不需要写任何字段转换代码，也不会出现"后端改了字段名前端静默变 undefined"。
 */
@Data
public class PoiVO {

    private String id;
    private String name;
    private String businessType;
    private String district;

    /** 详细地址（街道门牌）。district 只到区县，游客要导航需要这一列 */
    private String address;

    private String level;

    /** 对外联系电话。没有就是 null，前端不显示这一栏 */
    private String phone;

    private Double lng;
    private Double lat;
    private Double ticketPrice;
    private String openHours;
    private Integer durationMin;
    private Integer capacity;

    /** 数据库里是逗号分隔字符串，对外统一成数组 */
    private List<String> tags;

    private String summary;

    /**
     * 详细介绍正文。summary 是一句话简介，这里是长文。
     *
     * <p>为什么不把两者合成一个字段：列表页与卡片要用短的那句
     * （长了会把卡片撑破），详情页要用长的。合成一个就得在前端截断，
     * 而截断位置按字数硬切，会在句子中间断开。
     */
    private String detail;

    private String scene;
    private String dataOrigin;
    private String sourceUrl;

    // 刻意**没有** warningThreshold：那是运营口径（"预警线定在 80% 还是 85%"），
    // 游客不需要知道，知道了也没有用。管理端的 AdminPoiVO 里才有这一列。
}
