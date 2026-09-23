package com.hanyou.brain.common;

import lombok.Getter;

/** 业务错误码。区间划分：1xxx 参数/资源，2xxx 数据导入，3xxx 外部依赖。 */
@Getter
public enum ErrorCode {

    NOT_FOUND(1001, "资源不存在"),
    BAD_REQUEST(1002, "请求参数不合法"),
    CITYPACK_MISSING(2001, "城市数据包不存在或不可读"),
    CITYPACK_INVALID(2002, "城市数据包格式错误"),
    INTERNAL(9000, "服务内部错误");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }
}
