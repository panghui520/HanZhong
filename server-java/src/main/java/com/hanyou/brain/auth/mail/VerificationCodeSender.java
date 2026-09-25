package com.hanyou.brain.auth.mail;

/**
 * 验证码投递渠道（M8）。
 *
 * <p>抽出这个接口是为了让"演示环境没有邮箱账号"不成为阻塞：
 * {@link DemoCodeSender} 只把验证码写进日志，{@link SmtpCodeSender} 真正发信，
 * 由 {@code hanyou.auth.mail.provider} 决定用哪个。
 *
 * <p>注意：**两个实现都不把验证码返回给调用方**。
 * 换渠道只改变"验证码怎么送到用户手里"，不改变"谁生成、谁校验"——
 * 前端在任何模式下都拿不到验证码，也无法自行判断对错。
 */
public interface VerificationCodeSender {

    /**
     * 投递验证码。
     *
     * @param email  目标邮箱
     * @param code   明文验证码（仅在此处短暂可见，不会落库、不会返回给前端）
     * @param expireMinutes 有效期，用于拼邮件正文
     * @throws com.hanyou.brain.common.BizException MAIL_SEND_FAILED 发送失败时抛出
     */
    void send(String email, String code, int expireMinutes);

    /** 当前渠道标识，用于日志与健康检查展示 */
    String channel();
}
