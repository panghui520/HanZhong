package com.hanyou.brain.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.hanyou.brain.media.MediaStorageService;

import lombok.RequiredArgsConstructor;

/**
 * Web 层配置：跨域 + 上传图片的静态资源映射。
 *
 * <p><b>跨域：</b>开发期前端走 Vite 代理（web/vite.config.ts 把 /api 转发到 8080），
 * 请求是同源的，本不需要 CORS。这里放开本机来源是为了能用 curl、浏览器直接打 8080
 * 调接口做验收，以及前端临时绕过代理时不至于卡住。生产同源部署用不到它。
 *
 * <p><b>静态资源：</b>运营上传的图片存在磁盘上，数据库里只有相对路径。
 * 这里把 {@code /api/media/**} 映射到媒体根目录，由 Web 容器直接吐文件 ——
 * 图片不经过 JDBC、不进结果集、不占业务事务，这是"图片存磁盘而不是塞 BLOB"
 * 的主要理由。
 */
@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final MediaStorageService mediaStorage;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns("http://localhost:*", "http://127.0.0.1:*")
                // PATCH 是 M9 改备注/改文案用的（部分更新）。漏掉它的话，
                // 浏览器预检会直接失败，而报错指向 CORS，很难联想到是方法没放行。
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }

    /**
     * 把上传的图片挂到 {@link MediaStorageService#URL_PREFIX} 下。
     *
     * <p>前缀常量取自 MediaStorageService，与 VO 里拼 URL 用的是同一个值 ——
     * 改一处就等于同时改了"文件放在哪个 URL 下"，不需要两处同步。
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String location = mediaStorage.root().toUri().toString();
        // 资源位置必须以 "/" 结尾，否则 Spring 会把它当成"文件前缀"去拼，
        // 解析出来的路径会少一层目录，表现为上传成功但图片 404。
        // Path.toUri() 对已存在的目录通常会给到结尾斜杠，这里再兜一道。
        if (!location.endsWith("/")) {
            location = location + "/";
        }

        registry.addResourceHandler(MediaStorageService.URL_PREFIX + "**")
                .addResourceLocations(location)
                // 文件内容是 UUID 命名、永不复用，缓存久一点没有失效风险。
                // 换图会产生新文件名，因此旧缓存不会把新图盖住。
                .setCachePeriod(3600);
    }
}
