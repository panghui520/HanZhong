package com.hanyou.brain.common;

import lombok.Data;

/**
 * 统一响应体。
 * code = 0 表示成功；非 0 为业务错误码（见 ErrorCode）。
 * 前端只判断 code，不依赖 HTTP 状态码区分业务错误。
 */
@Data
public class Result<T> {

    private int code;
    private String message;
    private T data;

    public static <T> Result<T> ok(T data) {
        Result<T> r = new Result<>();
        r.code = 0;
        r.message = "ok";
        r.data = data;
        return r;
    }

    public static <T> Result<T> fail(ErrorCode ec) {
        return fail(ec.getCode(), ec.getMessage());
    }

    public static <T> Result<T> fail(int code, String message) {
        Result<T> r = new Result<>();
        r.code = code;
        r.message = message;
        return r;
    }
}
