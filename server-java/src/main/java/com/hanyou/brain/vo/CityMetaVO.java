package com.hanyou.brain.vo;

import lombok.Data;

/**
 * 城市档案对外视图。结构与 citypack/&lt;city&gt;/meta.json 一致，
 * 前端 web/src/types/index.ts 的 CityMeta 直接复用。
 */
@Data
public class CityMetaVO {

    private String cityCode;
    private String name;
    private String province;

    /** 与前端 CityMeta.center 的嵌套结构保持一致 */
    private Center center;

    private String tagline;
    private String summary;
    private String dataOrigin;
    private String disclaimer;
    private String version;

    @Data
    public static class Center {
        private Double lng;
        private Double lat;
    }
}
