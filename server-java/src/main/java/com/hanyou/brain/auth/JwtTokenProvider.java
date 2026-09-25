package com.hanyou.brain.auth;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

import javax.crypto.SecretKey;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.hanyou.brain.config.HanYouProperties;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * JWT 签发与校验（M8）。
 *
 * <p>只负责"这张令牌是不是本服务签发的、有没有过期、里面写了什么"，
 * **不负责**"这个用户还有没有资格"。后者要看数据库里的 tokenVersion 与 status，
 * 由 {@link JwtAuthFilter} 补上——这是职责边界，别把两件事混在一个类里。
 *
 * <p>关于 tokenVersion：JWT 天生无状态，签发后在有效期内一直算数，
 * 所以"退出登录"和"改密码"光靠 JWT 本身是拦不住的。做法是把版本号写进 claims，
 * 每次校验时与库里的值比对；退出登录就把库里的版本 +1，该用户所有历史令牌
 * 立刻全部失效。比维护一张失效令牌黑名单简单，也不会随时间无限膨胀。
 */
@Component
public class JwtTokenProvider {

    private static final Logger log = LoggerFactory.getLogger(JwtTokenProvider.class);

    /** 自定义 claim 名。用短名省字节，但为了可读性这里不压缩 */
    public static final String CLAIM_ROLE = "role";
    public static final String CLAIM_VER = "ver";
    public static final String CLAIM_NICK = "nick";

    private final SecretKey key;
    private final HanYouProperties.Auth.Jwt cfg;

    public JwtTokenProvider(HanYouProperties props) {
        this.cfg = props.getAuth().getJwt();
        byte[] raw = cfg.getSecret().getBytes(StandardCharsets.UTF_8);

        // HS256 要求密钥至少 256 位（32 字节）。不提前拦的话，
        // 报错会发生在第一次签发令牌时，表现为"注册到最后一步突然 500"，
        // 很难联想到是配置文件里的密钥太短。这里启动即失败，问题一眼可见。
        if (raw.length < 32) {
            throw new IllegalStateException(
                    "hanyou.auth.jwt.secret 至少需要 32 字节（当前 " + raw.length
                            + " 字节）。HS256 使用短密钥会在签约时报 WeakKeyException。");
        }
        this.key = Keys.hmacShaKeyFor(raw);
        log.info("[M8] JWT 初始化完成，令牌有效期 {} 分钟", cfg.getExpireMinutes());
    }

    /** 令牌有效期，供接口回传给前端做倒计时 */
    public Duration getTtl() {
        return Duration.ofMinutes(cfg.getExpireMinutes());
    }

    /**
     * 签发令牌。
     *
     * @param userId       用户主键，作为 sub
     * @param email        登录邮箱
     * @param nickname     昵称，前端顶栏直接读它，省一次用户信息请求
     * @param role         GUEST / OPERATOR
     * @param tokenVersion 当前版本号，校验时比对
     */
    public String issue(Long userId, String email, String nickname, String role, int tokenVersion) {
        Instant now = Instant.now();
        Instant exp = now.plus(getTtl());
        return Jwts.builder()
                // sub 放用户 id 而不是邮箱：邮箱可以被用户申请变更，id 不会
                .subject(String.valueOf(userId))
                .issuer(cfg.getIssuer())
                .issuedAt(Date.from(now))
                .expiration(Date.from(exp))
                .claim("email", email)
                .claim(CLAIM_NICK, nickname)
                .claim(CLAIM_ROLE, role)
                .claim(CLAIM_VER, tokenVersion)
                .signWith(key)
                .compact();
    }

    /**
     * 解析并验签。
     *
     * <p>返回 null 表示令牌不可用（签名不对 / 过期 / 结构损坏 / 不是本服务签发的）。
     * 这里刻意**不区分**各种失败原因：对客户端来说处理方式完全一样（重新登录），
     * 而把"签名错误"和"已过期"分开告诉调用方，等于给攻击者提供探测信息。
     * 需要排查时看日志。
     */
    public Claims parse(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        try {
            return Jwts.parser()
                    .verifyWith(key)
                    .requireIssuer(cfg.getIssuer())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("[M8] 令牌校验失败：{}", e.getMessage());
            return null;
        }
    }
}
