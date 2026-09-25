package com.hanyou.brain.vo;

import lombok.Data;

/**
 * 当前登录用户信息（M8）。对应 GET /api/me。
 *
 * <p>设计成与 {@link AuthTokenVO} 的子集同构（除 token 外字段一致），
 * 前端可以用同一个类型去接两个接口。
 */
@Data
public class AuthUserVO {

    private Long userId;
    private String email;
    private String nickname;

    /** GUEST / OPERATOR */
    private String role;

    /** 是否已验证邮箱。当前注册流程要求先验证邮箱，所以恒为 true，保留字段供后续扩展 */
    private Boolean emailVerified;
}
