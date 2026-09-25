package com.hanyou.brain.auth;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hanyou.brain.common.ErrorCode;
import com.hanyou.brain.common.Result;

import lombok.RequiredArgsConstructor;

/**
 * 认证与授权的 JSON 出口（M8）。
 *
 * <p>Spring Security 默认在认证失败时返回 403 和一个空 body，在授权失败时返回
 * 一个 HTML 错误页。两者都会破坏本项目"HTTP 恒为 200、业务结果看 body.code"的约定：
 * 前端 {@code request()} 会先看到 HTTP 非 200 而抛"AiError: 请求失败：HTTP 403"，
 * 或者在 HTML 上抛 JSON 解析错误——报错信息完全指不到真正的原因。
 *
 * <p>所以这两个出口都要自己实现，把失败也变成标准的 Result。
 * 注意这里**不返回 HTTP 401/403**，一律 200：前端只判断 code，
 * 4001 就跳登录、4003 就提示无权限，与其它业务错误走同一条分支。
 */
@Component
@RequiredArgsConstructor
public class AuthJsonHandlers implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    /** 未登录（或令牌已失效）访问受保护接口 */
    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException authException) throws IOException {
        write(response, ErrorCode.UNAUTHORIZED);
    }

    /** 已登录但角色不够（例如游客访问 /api/admin/**） */
    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
            AccessDeniedException accessDeniedException) throws IOException {
        write(response, ErrorCode.FORBIDDEN);
    }

    private void write(HttpServletResponse response, ErrorCode code) throws IOException {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(Result.fail(code)));
    }
}
