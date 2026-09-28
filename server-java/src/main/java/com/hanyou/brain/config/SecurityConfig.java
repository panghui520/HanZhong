package com.hanyou.brain.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import com.hanyou.brain.auth.AuthJsonHandlers;
import com.hanyou.brain.auth.JwtAuthFilter;

import java.util.List;

import lombok.RequiredArgsConstructor;

/**
 * Spring Security 配置（M8）。
 *
 * <p>三条贯穿全文件的原则：
 *
 * <p><b>1. 只放行已知路径，其余默认要登录。</b>
 * 这里把 M1/M2/M3 的全部公开接口逐条列出来 permitAll。
 * 列白名单比列黑名单长，但安全方向是对的：以后新加一个接口忘了配置，
 * 结果是"访问不了"（立刻发现），而不是"任何人都能访问"（可能一直没人发现）。
 *
 * <p><b>2. 无状态。</b>令牌走请求头，不建 Session。所以关掉 CSRF——
 * CSRF 攻击依赖浏览器自动携带 Cookie，而我们根本不发 Cookie，
 * 开着它反而会让所有 POST 都要求一个前端拿不到的 token。
 *
 * <p><b>3. 失败也返回 HTTP 200 + Result。</b>见 {@link AuthJsonHandlers} 的注释。
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;
    private final AuthJsonHandlers authJsonHandlers;

    /** 运营管理端接口前缀。前端管理端页面全部打到这里，后续模块的运营接口也复用 */
    public static final String ADMIN_PREFIX = "/api/admin/**";

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                // 无状态 + 令牌在请求头 => 浏览器不会自动附带凭证，CSRF 不成立
                .csrf(csrf -> csrf.disable())
                // 复用 WebConfig 里的跨域规则，避免两处各写一套来源白名单
                .cors(Customizer.withDefaults())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .formLogin(f -> f.disable())
                .httpBasic(b -> b.disable())
                .logout(l -> l.disable())
                .authorizeHttpRequests(auth -> auth
                        // 预检请求必须放行：浏览器不会在 OPTIONS 上带 Authorization，
                        // 拦掉它会让所有跨域 POST 在预检阶段就失败，且报错指向 CORS，很难定位
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()

                        // ---------------- 公开：认证入口本身 ----------------
                        .requestMatchers("/api/auth/**").permitAll()

                        // ---------------- 公开：M1 统一资源 ----------------
                        // 这些是游客端的浏览能力，登录与否都能看。M8 不改变它们的行为。
                        //
                        // /api/pois/** 同时也覆盖了 M10 续的景点评论列表
                        // （GET /api/pois/{id}/comments）—— 看评论与看景点是一回事，
                        // 要求登录等于把"这地方怎么样"变成只有注册用户才看得到的信息。
                        //
                        // 发表评论是 POST /api/pois/{id}/comments，**不在**这条白名单里
                        // （这里限定了 GET），会落到下面的 anyRequest().authenticated()。
                        // 发表要有作者，匿名评论在这个系统里没有归属。
                        .requestMatchers(HttpMethod.GET,
                                "/api/city",
                                "/api/pois", "/api/pois/**")
                        .permitAll()

                        // ---------------- 公开：M2 乡村体验与农产品 ----------------
                        .requestMatchers(HttpMethod.GET,
                                "/api/experiences", "/api/experiences/**",
                                "/api/products", "/api/products/**",
                                "/api/product-categories")
                        .permitAll()

                        // ---------------- 公开：M3 文旅知识问答 ----------------
                        // 问答本身是公开能力；限流与配额属于后续模块的事
                        .requestMatchers("/api/ai/**").permitAll()

                        // ---------------- 公开：M5 承载力数据（游客端） ----------------
                        // 游客端要显示"当前拥挤度 / 建议错峰"，这个数字必须未登录也能拿到 ——
                        // 错峰引导的价值恰恰在于**还没到访的人**能看到，要求登录就等于把它
                        // 从"公共信息"降级成"会员服务"。
                        //
                        // 只放行 GET：规则扫描、建单、处置全在 /api/admin/** 下，由下面那条
                        // 兜住。若这里不限定方法，等于把"手动触发规则扫描"也开给了游客。
                        //
                        // 这里给的是**聚合后的承载率**（visitors/capacity），不是原始客流。
                        // 原始 visit_stats 只在运营端暴露，见 /api/admin/ops/**。
                        .requestMatchers(HttpMethod.GET, "/api/stats/**").permitAll()

                        // ---------------- 公开：M5 分流公告（游客端） ----------------
                        // 公告的作用就是"在游客决定行程之前告诉他"，要求登录
                        // 等于把它变成只有已注册用户才看得到的内部通知 ——
                        // 而分流引导的价值恰恰在**还没到访的人**身上。
                        //
                        // 只放行 GET。发布 / 编辑 / 撤下在
                        // /api/admin/diversion-notices/** 下，由下面的 ADMIN_PREFIX 兜住。
                        // 这里若不限定方法，等于把"往首页发公告"的通道开给了游客 ——
                        // 那是这个模块里权限最不能出错的一处。
                        //
                        // 服务端还会强制过滤（只返回 PUBLISHED 且未过期），
                        // 不靠前端自觉：前端只是渲染，不该承担正确性。
                        .requestMatchers(HttpMethod.GET, "/api/diversion-notices").permitAll()

                        // ---------------- 公开：M9 媒体与配图 ----------------
                        // 这条前缀承担两件事：
                        //   1) 游客端读轮播图与景点配图（/api/media/banners 等）
                        //   2) 运营上传的图片文件本身（/api/media/poi/xxx/yyy.jpg），
                        //      由 WebConfig 的静态资源映射直出
                        // 只放行 GET：写操作全在 /api/admin/media/** 下，由下面的
                        // ADMIN_PREFIX 规则兜住。这里若不限定方法，等于把删除图片
                        // 文件的通道也开给了游客。
                        .requestMatchers(HttpMethod.GET, "/api/media/**").permitAll()

                        // ---------------- 需要登录：运营管理端 ----------------
                        // 放在最前面写，是因为它比下面的 GET 规则更具体。
                        // Spring Security 按声明顺序匹配第一条命中的规则，
                        // 若把这条挪到 GET 通配之后，GET /api/admin/xxx 会被通配先接走。
                        .requestMatchers(ADMIN_PREFIX).hasRole("OPERATOR")

                        // ---------------- 需要登录：M6 购物车与订单 ----------------
                        // 都是"我的数据"，必须登录。**没有匿名购物车** —— 匿名车要靠
                        // Cookie 或 localStorage 认领，中途登录就要处理"这辆车归谁"的
                        // 合并问题，而本项目下单本来就必须登录，从加购就要求登录更省事。
                        //
                        // 虽然兜底的 anyRequest().authenticated() 已经覆盖了它们，
                        // 这里仍然显式写一行：项目约定是每条新路径都在此留痕，
                        // review 时一眼能看出"这个接口是公开还是受保护"。
                        .requestMatchers("/api/cart/**", "/api/orders/**").authenticated()

                        // 评价图片上传（M6）。与运营上传 /api/admin/media/** 分开，
                        // 因为这里是**任何登录用户**都能用的 —— 评价要传图，
                        // 但运营上传接口不能被放开给所有人。
                        .requestMatchers("/api/reviews/**").authenticated()

                        // ---------------- 需要登录：M4 当前行程与上下文 ----------------
                        // 行程是"我的数据"，与购物车同理。注意 /api/ai/** 在上面
                        // 是 permitAll 的 —— 助手本身不需要登录，只是未登录时
                        // 服务端拿不到行程上下文，助手就没有记忆。这个降级是
                        // 刻意的：问答的核心价值不依赖登录。
                        .requestMatchers("/api/trips/**").authenticated()

                        // 认证后与个人相关的接口（当前只有 me/logout，见 AuthController）
                        .requestMatchers("/api/me/**").authenticated()

                        // ---------------- 兜底 ----------------
                        // 没被上面任何一条命中的路径一律要登录。
                        // 新增接口时这里是最后一道提醒：要么显式 permitAll，要么就是受保护的。
                        .anyRequest().authenticated())
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(authJsonHandlers)
                        .accessDeniedHandler(authJsonHandlers))
                // 放在用户名密码过滤器之前：JWT 不需要表单登录那一步
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * 密码哈希器。
     *
     * <p>BCrypt 自带随机盐并把盐写进哈希串本身，所以不需要额外的 salt 列。
     * 强度用默认的 10（约 2^10 次迭代）：注册/登录各一次，耗时几十毫秒，
     * 用户无感，而离线爆破成本已经足够高。
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * 跨域配置。与 {@link WebConfig#addCorsMappings} 保持一致。
     *
     * <p>Spring Security 的 cors() 只认这个 Bean，不会去读 WebMvcConfigurer 里的配置——
     * 不显式声明的话，被拦截的请求会在进入 MVC 之前就被拒，浏览器报的却是 CORS 错误。
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration cfg = new CorsConfiguration();
        cfg.setAllowedOriginPatterns(List.of("http://localhost:*", "http://127.0.0.1:*"));
        // 与 WebConfig#addCorsMappings 保持一致。PATCH 是 M9 改备注/改文案用的，
        // 两处都漏掉的话，跨域预检会在进入 Security 之前就失败
        cfg.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cfg.setAllowedHeaders(List.of("*"));
        cfg.setAllowCredentials(true);
        cfg.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", cfg);
        return source;
    }
}
