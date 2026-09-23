package com.hanyou.brain.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 跨域配置。
 *
 * <p>开发期前端走 Vite 代理（web/vite.config.ts 把 /api 转发到 8080），请求是同源的，
 * 本不需要 CORS。这里放开本机来源是为了能用 curl、浏览器直接打 8080 调接口做验收，
 * 以及前端临时绕过代理时不至于卡住。生产同源部署用不到它。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns("http://localhost:*", "http://127.0.0.1:*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }
}
