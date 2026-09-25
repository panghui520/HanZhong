package com.hanyou.brain.auth;

/**
 * 已认证主体，作为 Spring Security 的 Authentication principal（M8）。
 *
 * <p>为什么不用 Spring Security 自带的 {@code org.springframework.security.core.userdetails.User}：
 * 那个类只带 UsernamePasswordAuthenticationToken 的字符串用户名和权限集合，
 * 拿不到我们真正要用的 userId。业务代码里若想按 id 查数据，就得再从邮箱反查一次用户，
 * 白白多一次查询、也多一个"邮箱被改过就对不上"的隐患。带 id 最省事也最不容易错。
 *
 * @param userId 用户主键
 * @param email  登录邮箱
 * @param nickname 昵称
 * @param role   GUEST / OPERATOR
 */
public record AuthUser(Long userId, String email, String nickname, String role) {

    public boolean isOperator() {
        return "OPERATOR".equals(role);
    }
}
