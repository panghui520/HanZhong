package com.hanyou.brain.common;

import lombok.Getter;

/**
 * 业务错误码。区间划分：
 * <p>1xxx 参数/资源，2xxx 数据导入，3xxx 外部依赖，4xxx 认证与权限（M8），
 * 5xxx 媒体与配图（M9），6xxx 购物车与订单（M6），7xxx 智能行程（M4）。
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
    /**
     * 解读维度不在白名单内（M7）。
     *
     * <p>单开一个码而不是复用 {@code BAD_REQUEST}：这个错误的唯一成因是
     * 前端与后端的维度清单对不上，而清单是跨语言维护的（Java 校验一层、
     * Python 再校验一层）。给一个能直接指向"哪一边多了/少了"的码，
     * 比笼统的 1002 省一轮排查。
     */
    AI_FOCUS_INVALID(3003, "未知的解读维度"),
    /** 运营指标为空，解读无从谈起（M7）。成因是快照取数为空，不是 AI 故障 */
    AI_METRICS_UNAVAILABLE(3004, "运营指标暂不可用，请稍后再试"),

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

    // ---- M4 智能行程 ----
    // 7xxx 段。这几个错误都是"客户端提交的酒店信息有问题"，必须能分辨：
    // 字段缺失是前端漏传（改代码），坐标不合法是经纬度搞反了（高德最经典的坑），
    // 两者给同一句"保存失败"会让排查方向完全跑偏。
    TRIP_NOT_FOUND(7001, "行程不存在，请刷新页面重试"),
    HOTEL_INFO_INCOMPLETE(7002, "酒店信息不完整，无法保存为本次行程的住处"),
    HOTEL_COORD_INVALID(7003, "酒店坐标不合法，无法用于附近搜索"),

    // ---- M6 到访消费链（足迹）----
    // 仍用 7xxx：足迹挂在 /api/trips/** 下，与 M4 的行程同属"我的这次出行"，
    // 前端按段处理时不该被拆到两个段里。与 8xxx 的区别是**面向游客**，
    // 文案要能直接展示给用户看。
    CHECKIN_TARGET_REQUIRED(7004, "请指定要打卡的资源点或体验项目"),
    CHECKIN_TARGET_NOT_FOUND(7005, "这个资源点或体验项目不存在，请刷新页面后重试"),

    // ---- M5 承载力与乡村分流 ----
    // 8xxx 段（7xxx 已被 M4 占用）。这里的错误全是运营端动作产生的，
    // 与游客端无关，所以前端可以按段统一处理：8xxx 只可能出现在管理端页面。
    RISK_NOT_FOUND(8001, "风险事件不存在或已被清理"),
    WORK_ORDER_NOT_FOUND(8002, "工单不存在"),
    WORK_ORDER_EXISTS(8003, "该风险事件已经建过工单，请到工单列表查看"),
    WORK_ORDER_STATUS_INVALID(8004, "工单状态不合法，只支持处置中或已完结"),
    WORK_ORDER_RESULT_REQUIRED(8005, "完结工单前请填写处置反馈"),

    // ---- M5 续：分流公告 ----
    // 801x 段（8001–8005 已被 M5 的工单占用）。公告是**要发到游客端**的东西，
    // 所以这里的每一条错误都必须说清"哪里不合法" —— 运营看到一句
    // "发布失败"只能猜，而猜错的代价是首页上挂着一条没依据的公告。
    NOTICE_NOT_FOUND(8010, "分流公告不存在"),
    NOTICE_EXISTS(8011, "该风险事件已经生成过分流公告，请到公告列表查看"),
    NOTICE_STATUS_INVALID(8012, "公告状态不合法，只支持发布或撤下"),
    NOTICE_CANDIDATE_EMPTY(8013, "周边暂无可承接的资源点，无法生成分流公告"),
    NOTICE_TITLE_REQUIRED(8014, "请填写公告标题"),
    NOTICE_EXPIRED(8015, "公告已过期，请重新生成后再发布"),

    // ---- M10 资源管理（运营端）----
    // 1xxx 段续号，与 1001/1002 同属"参数与资源"：这些错误的成因都在
    // **请求本身**（资源不存在、字段不合法、编码撞了），与外部依赖无关，
    // 所以不该散到 3xxx/5xxx 去。
    //
    // RESOURCE_IN_USE 单开一个码，不复用 BAD_REQUEST：它对应一个**明确的
    // 替代动作** —— 删不掉，但可以下架。前端据此能把失败直接引导到"下架"，
    // 不需要去解析中文文案。
    RESOURCE_NOT_FOUND(1101, "资源不存在或已被删除"),
    RESOURCE_TYPE_INVALID(1102, "资源类型不合法"),
    RESOURCE_NAME_REQUIRED(1103, "请填写资源名称"),
    RESOURCE_IN_USE(1104, "该资源已被订单、行程或足迹引用，不能删除，请改用下架"),
    RESOURCE_CODE_DUPLICATE(1105, "资源编码已存在，请换一个"),
    RESOURCE_STATUS_INVALID(1106, "上架状态只支持 0 或 1"),
    RESOURCE_FIELD_INVALID(1107, "资源字段不合法，请检查后重试"),
    /**
     * 数据包导入的资源不能删除。
     *
     * <p>不是权限问题，是**删除这件事对它不成立**：CityPackImporter 每次启动
     * 都会把 citypack/ 里的资源重新灌一遍，删掉的行下一次启动就回来了。
     * 与其让运营删完发现"过两天它又在了"，不如当场说清替代动作是下架。
     * 要让一条资源真正消失，得改数据包 —— 数据包才是它的权威来源。
     */
    RESOURCE_PACK_LOCKED(1108, "该资源来自城市数据包，不能删除，请改用下架"),

    // ---- M10 续：景点评论 ----
    // 仍用 1xxx 段：成因同样在**请求本身**（评论不存在、评分越界、内容为空），
    // 与外部依赖无关。
    //
    // 与 M6 的 6010–6012（订单评价）**刻意分开**：两者是不同的业务对象 ——
    // 订单评价一单一评、挂在一次交易上；景点评论可以发多条、挂在资源上。
    // 共用一个码会让前端分不清该提示哪一边，也会让"这条 6011 是哪来的"
    // 在日志里失去指向。
    COMMENT_NOT_FOUND(1109, "评论不存在或已被删除"),
    COMMENT_STATUS_INVALID(1110, "评论状态只支持 PENDING / APPROVED / HIDDEN"),
    COMMENT_RATING_INVALID(1111, "请选择 1 到 5 星"),
    COMMENT_CONTENT_REQUIRED(1112, "请填写评论内容"),

    INTERNAL(9000, "服务内部错误");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }
}
