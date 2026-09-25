package com.hanyou.brain.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
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

    /**
     * 上传文件超过容器上限（M9）。
     *
     * <p>必须单独处理：这个异常在请求体还没解析完时就抛出了，走不到
     * Controller，也就走不到 MediaStorageService 的大小校验。
     * 不处理的话会落到下面的兜底分支，前端拿到 9000「服务内部错误」，
     * 而实际情况只是"图片太大"，运营完全不知道该做什么。
     *
     * <p>application.yml 里 multipart.max-file-size(6MB) 比业务上限(5MB)略大，
     * 所以正常情况是业务校验先报错；只有明显超标的文件才会走到这里。
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public Result<Void> handleUploadTooLarge(MaxUploadSizeExceededException e) {
        log.warn("[上传超限] {}", e.getMessage());
        return Result.fail(ErrorCode.MEDIA_TOO_LARGE);
    }

    /** 兜底：未预期异常要打完整堆栈，但只把通用文案给前端 */
    @ExceptionHandler(Exception.class)
    public Result<Void> handleOther(Exception e) {
        log.error("[未预期异常]", e);
        return Result.fail(ErrorCode.INTERNAL);
    }
}
