package com.hanyou.brain.auth;

import java.io.IOException;
import java.util.List;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hanyou.brain.entity.AppUser;
import com.hanyou.brain.mapper.AppUserMapper;

import io.jsonwebtoken.Claims;

/**
 * JWT 认证过滤器（M8）。
 *
 * <p>只做一件事：把请求头里的 Bearer 令牌换成 Spring Security 上下文里的
 * Authentication。**不做拒绝**——令牌缺失或不合法时它就静静地什么都不设置，
 * 交给后面的授权规则去拒绝（受保护路径变 401，公开路径照常放行）。
 * 在过滤器里直接回 401 会误伤公开接口：那些接口带着一张过期令牌访问是完全正常的。
 *
 * <p>两道校验缺一不可：
 * <ol>
 *   <li>{@link JwtTokenProvider#parse}：签名对不对、过没过期
 *   <li>本类的 {@code TOKEN_INVALID} 分支：库里这个用户的 tokenVersion 有没有被 +1、
 *       账号是不是还被允许登录
 * </ol>
 * 只做第一道的话，"退出登录"就是个纯前端的假动作——把 localStorage 一清，
 * 那张令牌在过期前仍然能继续访问所有受保护接口。
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final String HEADER = "Authorization";
    private static final String PREFIX = "Bearer ";

    private final JwtTokenProvider tokens;
    private final AppUserMapper userMapper;

    public JwtAuthFilter(JwtTokenProvider tokens, AppUserMapper userMapper) {
        this.tokens = tokens;
        this.userMapper = userMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {

        String token = extract(request);
        if (token == null) {
            chain.doFilter(request, response);
            return;
        }

        Claims claims = tokens.parse(token);
        if (claims != null) {
            AuthUser user = load(claims);
            if (user != null) {
                var auth = new UsernamePasswordAuthenticationToken(
                        user, null, List.of(new SimpleGrantedAuthority("ROLE_" + user.role())));
                // setDetails 带上来源 IP，后续做审计日志（M7 运营分析）时用得到
                SecurityContextHolder.getContext().setAuthentication(auth);
            }
        }

        chain.doFilter(request, response);
    }

    /**
     * 令牌 -> 用户。返回 null 表示这张令牌虽然签名有效，但已经不该被承认了：
     * 用户被删了、tokenVersion 对不上（退出登录过）、或者账号被禁用。
     */
    private AuthUser load(Claims claims) {
        long userId;
        try {
            userId = Long.parseLong(claims.getSubject());
        } catch (NumberFormatException e) {
            return null;
        }

        AppUser u = userMapper.selectById(userId);
        if (u == null) {
            return null;
        }
        // tokenVersion 是 JWT 服务端失效的开关，比对不上说明令牌已作废
        Integer ver = claims.get(JwtTokenProvider.CLAIM_VER, Integer.class);
        if (ver == null || !ver.equals(u.getTokenVersion())) {
            return null;
        }
        // PENDING 表示邮箱还没验证完，DISABLED 表示被运营方停用，两种都不给进
        if (!"ACTIVE".equals(u.getStatus())) {
            return null;
        }

        // 角色以数据库为准，不信令牌里的那份。令牌里的 role 只是签发那一刻的快照，
        // 用户被降权后旧令牌仍会带着旧角色；以库为准就不存在这个时间窗。
        return new AuthUser(u.getId(), u.getEmail(), u.getNickname(), u.getRole());
    }

    private String extract(HttpServletRequest request) {
        String raw = request.getHeader(HEADER);
        if (raw == null || !raw.startsWith(PREFIX)) {
            return null;
        }
        String token = raw.substring(PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }
}
