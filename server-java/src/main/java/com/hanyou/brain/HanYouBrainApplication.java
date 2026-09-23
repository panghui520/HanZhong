package com.hanyou.brain;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import com.hanyou.brain.config.HanYouProperties;

/**
 * 汉游智脑 · 业务后端
 * 职责：业务数据、权限、规则引擎、工单、订单。AI 能力在 server-ai（Python）中，通过内部 HTTP 调用。
 */
@SpringBootApplication
@MapperScan("com.hanyou.brain.mapper")
@EnableConfigurationProperties(HanYouProperties.class)
public class HanYouBrainApplication {

    public static void main(String[] args) {
        SpringApplication.run(HanYouBrainApplication.class, args);
    }
}
