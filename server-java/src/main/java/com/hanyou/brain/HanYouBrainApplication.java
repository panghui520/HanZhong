package com.hanyou.brain;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.hanyou.brain.config.HanYouProperties;

/**
 * 汉游智脑 · 业务后端
 * 职责：业务数据、权限、规则引擎、工单、订单。AI 能力在 server-ai（Python）中，通过内部 HTTP 调用。
 *
 * <p>{@code @EnableScheduling} 是为 M6 的"待付款超时自动取消"开的
 * （见 {@link com.hanyou.brain.task.OrderTimeoutTask}）。目前只有那一个定时任务，
 * 加在启动类上比单开一个配置类更容易找到。
 */
@SpringBootApplication
@MapperScan("com.hanyou.brain.mapper")
@EnableConfigurationProperties(HanYouProperties.class)
@EnableScheduling
public class HanYouBrainApplication {

    public static void main(String[] args) {
        SpringApplication.run(HanYouBrainApplication.class, args);
    }
}
