package com.hanyou.brain.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.hanyou.brain.auth.AuthService;
import com.hanyou.brain.auth.AuthUser;
import com.hanyou.brain.common.BizException;
import com.hanyou.brain.common.ErrorCode;
import com.hanyou.brain.common.Result;
import com.hanyou.brain.entity.AppUser;
import com.hanyou.brain.vo.AuthTokenVO;
import com.hanyou.brain.vo.AuthUserVO;

import java.util.Map;

import lombok.RequiredArgsConstructor;

/**
 * 认证接口（M8）。
 *
 * <p>全部在 {@code /api/auth/**} 下，这条前缀在 SecurityConfig 里是 permitAll ——
 * 注册和登录本身当然不能要求先登录。
 *
 * <p>对外只暴露三个动作：发验证码、注册、登录。当前用户信息与退出登录放在
 * {@code /api/me/**}（需要登录），因为它们的语义是"针对我自己"，
 * 和"我还没登录时要用的入口"是两回事，混在一条前缀下会让安全规则很难写。
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /**
     * 发送注册验证码。
     *
     * <p>请求体只有一个 email。返回的 data 里**不含验证码**，也不含任何
     * 与验证码有关的提示（长度、还差几位都不给）——前端只负责显示"已发送"。
     */
    @PostMapping("/auth/code")
    public Result<Map<String, Object>> sendCode(@RequestBody(required = false) Map<String, String> body) {
        String email = body == null ? null : body.get("email");
        authService.sendRegisterCode(email);
        return Result.ok(Map.of(
                "sent", true,
                "message", "验证码已发送，请查收邮箱并在 5 分钟内完成注册"));
    }

    /** 注册。成功直接返回令牌，前端不用再登录一次 */
    @PostMapping("/auth/register")
    public Result<AuthTokenVO> register(@RequestBody(required = false) Map<String, String> body) {
        Map<String, String> b = body == null ? Map.of() : body;
        return Result.ok(authService.register(
                b.get("email"), b.get("code"), b.get("password"), b.get("nickname")));
    }

    /** 登录 */
    @PostMapping("/auth/login")
    public Result<AuthTokenVO> login(@RequestBody(required = false) Map<String, String> body) {
        Map<String, String> b = body == null ? Map.of() : body;
        return Result.ok(authService.login(b.get("email"), b.get("password")));
    }

    /**
     * 当前登录用户。
     *
     * <p>页面刷新后前端的令牌还在 localStorage 里，但用户信息得重新取一次，
     * 同时也是"这张令牌还有没有效"的探针——令牌过期或被 tokenVersion 作废时，
     * 这个接口会返回 4001/4002，前端据此清会话并跳登录页。
     */
    @GetMapping("/me")
    public Result<AuthUserVO> me(@AuthenticationPrincipal AuthUser principal) {
        AppUser u = principal == null ? null : authService.findById(principal.userId());
        if (u == null) {
            // 令牌签名有效但用户已被删除。返回 4002 而不是 4001：
            // 前端对两者的处理相同（清会话回登录页），但日志里能区分开
            throw new BizException(ErrorCode.TOKEN_INVALID);
        }
        AuthUserVO vo = new AuthUserVO();
        vo.setUserId(u.getId());
        vo.setEmail(u.getEmail());
        vo.setNickname(u.getNickname());
        vo.setRole(u.getRole());
        vo.setEmailVerified(u.getEmailVerified() != null && u.getEmailVerified() == 1);
        return Result.ok(vo);
    }

    /**
     * 退出登录。
     *
     * <p>把 tokenVersion +1，服务端立即作废该用户全部历史令牌。
     * 前端同时清掉本地的 localStorage —— 两边都做，"退出"才既干净又真实：
     * 只清前端的话，那张令牌在过期前仍然能访问所有受保护接口。
     */
    @PostMapping("/me/logout")
    public Result<Map<String, Object>> logout(@AuthenticationPrincipal AuthUser principal) {
        if (principal != null) {
            authService.logout(principal.userId());
        }
        return Result.ok(Map.of("loggedOut", true));
    }
}
