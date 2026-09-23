package com.hanyou.brain.config;

import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;

/**
 * 统一 JSON 字段命名。
 *
 * <p>前端的类型定义（web/src/types/index.ts）沿用 CityPack JSON 的 snake_case 写法：
 * business_type / ticket_price / distance_km。后端若按 Java 习惯输出驼峰，
 * 前端就得为每个接口再写一层字段映射——两边各维护一套，改字段名时必漏改一处。
 * 所以这里全局指定 snake_case，让 Java 的驼峰字段自动输出成前端认得的名字。
 *
 * <p>对单字字段（code / data / name）无影响，Result 的结构不变。
 */
@Configuration
public class JacksonConfig {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer snakeCaseNamingCustomizer() {
        return builder -> builder.propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    }
}
