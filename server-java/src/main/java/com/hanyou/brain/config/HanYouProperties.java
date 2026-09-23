package com.hanyou.brain.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Data;

/** 对应 application.yml 中的 hanyou.* 配置 */
@Data
@ConfigurationProperties(prefix = "hanyou")
public class HanYouProperties {

    /** 城市数据包根目录（内含 <city>/ 子目录） */
    private String citypackDir = "../citypack";

    /** 当前城市编码，换城市只改这里 */
    private String city = "hanzhong";

    /** 启动时是否用 CityPack 覆盖导入 */
    private boolean importOnStartup = true;
}
