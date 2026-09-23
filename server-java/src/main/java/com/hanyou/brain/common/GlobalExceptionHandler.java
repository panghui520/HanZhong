package com.hanyou.brain.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常出口。
 *
 * <p>统一返回 HTTP 200 + Result.code，前端只判断 code——
 * 否则前端要在每个请求里同时处理"HTTP 报错"和"业务报错"两套分支。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 可预期的业务失败：记一行 warn，不打堆栈 */
    @ExceptionHandler(BizException.class)
    public Result<Void> handleBiz(BizException e) {
        log.warn("[业务异常] code={} message={}", e.getErrorCode().getCode(), e.getMessage());
        return Result.fail(e.getErrorCode().getCode(), e.getMessage());
    }

    /**
     * 路径不存在。
     * 不处理的话会走到 Spring 默认的错误页返回 HTML，前端 res.json() 会抛
     * SyntaxError，报错信息变成"意外的 token <"，很难定位。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public Result<Void> handleNoResource(NoResourceFoundException e) {
        return Result.fail(ErrorCode.NOT_FOUND.getCode(), "接口不存在：" + e.getResourcePath());
    }

    /** 兜底：未预期异常要打完整堆栈，但只把通用文案给前端 */
    @ExceptionHandler(Exception.class)
    public Result<Void> handleOther(Exception e) {
        log.error("[未预期异常]", e);
        return Result.fail(ErrorCode.INTERNAL);
    }
}
