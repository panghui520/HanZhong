package com.hanyou.brain.common;

import lombok.Getter;

/**
 * 业务错误码。区间划分：
 * <p>1xxx 参数/资源，2xxx 数据导入，3xxx 外部依赖，4xxx 认证与权限（M8），
 * 5xxx 媒体与配图（M9），6xxx 购物车与订单（M6）。
 *
 * <p>4xxx 段分得比较细，是因为注册流程每一道闸的失败原因对用户是不同的行动指引：
 * 冷却中要等、验证码错了要重输、过期了要重发。前端只判断数字就能给出准确文案，
 * 不用去匹配中文提示语。
 */
@Getter
public enum ErrorCode {

    NOT_FOUND(1001, "资源不存在"),
    BAD_REQUEST(1002, "请求参数不合法"),
    CITYPACK_MISSING(2001, "城市数据包不存在或不可读"),
    CITYPACK_INVALID(2002, "城市数据包格式错误"),
    AI_UNAVAILABLE(3001, "AI 服务不可用，请确认 server-ai 已启动"),
    AI_DISABLED(3002, "AI 能力未启用"),

    // ---- M8 认证与权限 ----
    UNAUTHORIZED(4001, "请先登录"),
    TOKEN_INVALID(4002, "登录状态已失效，请重新登录"),
    FORBIDDEN(4003, "没有访问该功能的权限"),
    EMAIL_INVALID(4101, "邮箱格式不正确"),
    EMAIL_ALREADY_REGISTERED(4102, "该邮箱已注册，请直接登录"),
    CODE_COOLDOWN(4103, "验证码发送过于频繁，请稍后再试"),
    CODE_MISMATCH(4104, "验证码不正确"),
    CODE_EXPIRED(4105, "验证码已过期，请重新获取"),
    CODE_USED(4106, "验证码已被使用，请重新获取"),
    CODE_ATTEMPTS_EXCEEDED(4107, "验证码错误次数过多，请重新获取"),
    CODE_NOT_FOUND(4108, "请先获取验证码"),
    PASSWORD_WEAK(4109, "密码不符合要求"),
    CREDENTIALS_INVALID(4110, "邮箱或密码不正确"),
    ACCOUNT_NOT_ACTIVE(4111, "账号状态异常，请联系运营方"),
    MAIL_SEND_FAILED(4112, "验证码发送失败，请稍后重试"),

    // ---- M9 媒体与配图 ----
    // 5xxx 段。上传失败的原因对运营是不同的行动指引：格式不对要换文件、
    // 太大要压缩、读不到要重试。前端只判断数字就能给出准确文案。
    MEDIA_EMPTY(5001, "请选择要上传的图片"),
    MEDIA_TYPE_UNSUPPORTED(5002, "只支持 JPG / PNG / WebP 格式的图片"),
    MEDIA_TOO_LARGE(5003, "图片过大，请压缩后再上传"),
    MEDIA_SAVE_FAILED(5004, "图片保存失败，请稍后重试"),
    MEDIA_NOT_FOUND(5005, "图片不存在或已被删除"),
    BANNER_NOT_FOUND(5006, "轮播图不存在或已被删除"),
    MEDIA_ORDER_INVALID(5007, "排序参数不合法"),

    // ---- M6 消费与离境复购 ----
    // 6xxx 段。"库存不足"与"商品不存在"必须分开：前者用户减少数量还能买，
    // 后者只能换一件。给同一句"下单失败"等于让用户自己猜。
    CART_EMPTY(6001, "购物车是空的，先去挑几样吧"),
    CART_ITEM_NOT_FOUND(6002, "购物车里的这件商品已不存在"),
    PRODUCT_NOT_FOUND(6003, "商品不存在或已下架"),
    STOCK_NOT_ENOUGH(6004, "库存不足，请减少数量后再试"),
    ORDER_NOT_FOUND(6005, "订单不存在"),
    ORDER_STATUS_INVALID(6006, "订单当前状态不允许该操作"),
    QUANTITY_INVALID(6007, "数量不合法"),
    RECEIVER_INVALID(6008, "收货信息不完整"),
    SHIP_INFO_REQUIRED(6009, "请填写快递公司、预计到达天数和快递单号"),
    REVIEW_EXISTS(6010, "这笔订单已经评价过了"),
    REVIEW_RATING_INVALID(6011, "请选择 1 到 5 星"),
    REVIEW_CONTENT_REQUIRED(6012, "请写点评价内容，或至少打个星级"),
    ORDER_PAY_EXPIRED(6013, "订单已超过支付时限，已自动取消"),
    REFUND_REASON_REQUIRED(6014, "请填写退款原因"),
    REFUND_NOTE_REQUIRED(6015, "请填写处理说明"),

    INTERNAL(9000, "服务内部错误");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }
}
