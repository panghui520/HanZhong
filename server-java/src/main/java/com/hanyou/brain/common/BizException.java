package com.hanyou.brain.common;

import lombok.Getter;

/**
 * 业务异常。
 * 抛出它表示"这是一个可预期的业务失败"（资源不存在、数据包缺文件等），
 * 由 GlobalExceptionHandler 转成统一的 Result，不把堆栈泄漏给前端。
 */
@Getter
public class BizException extends RuntimeException {

    private final ErrorCode errorCode;

    public BizException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    /** 需要补充上下文时用这个，message 会原样返回给前端（不要放敏感信息） */
    public BizException(ErrorCode errorCode, String detail) {
        super(detail);
        this.errorCode = errorCode;
    }
}
