package com.hanyou.brain.vo;

import lombok.Data;

/**
 * 登录 / 注册成功的返回体（M8）。
 *
 * <p>令牌与用户信息一起返回是刻意的：前端拿到就能直接渲染顶栏（昵称、角色），
 * 不必再多请求一次 /api/me。刷新页面时才需要调 /api/me 补一次用户信息。
 *
 * <p>字段经全局 snake_case 策略转成 token / expires_in_seconds / user_id / ...，
 * 与前端 AuthToken 类型一一对应。
 */
@Data
public class AuthTokenVO {

    /** JWT。前端存在 localStorage，之后放在 Authorization: Bearer <token> 里 */
    private String token;

    /** 有效期秒数，供前端做"还剩多久"的提示，不用自己解析 JWT 的 exp */
    private Long expiresInSeconds;

    private Long userId;
    private String email;
    private String nickname;

    /** GUEST / OPERATOR。前端据此决定是否显示"运营驾驶舱"入口 */
    private String role;
}
