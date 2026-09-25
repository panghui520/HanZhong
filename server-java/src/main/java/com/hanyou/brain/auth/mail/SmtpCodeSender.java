package com.hanyou.brain.auth.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import com.hanyou.brain.common.BizException;
import com.hanyou.brain.common.ErrorCode;
import com.hanyou.brain.config.HanYouProperties;

/**
 * SMTP 渠道：真实发信（M8）。
 *
 * <p>只有把 {@code hanyou.auth.mail.provider} 设为 {@code smtp} 时这个 Bean 才存在
 * （{@code @ConditionalOnProperty}）。默认的 demo 模式下它根本不会被创建，
 * 所以也不需要配 {@code spring.mail.*} 就能启动。
 *
 * <p>用 {@link SimpleMailMessage}（纯文本）而不是 HTML 邮件：
 * 验证码邮件没必要 HTML 化——纯文本更难被垃圾邮件过滤误判，
 * 在手机邮件客户端里也更稳。验证码本身不包含任何样式信息。
 */
@Component
@ConditionalOnProperty(name = "hanyou.auth.mail.provider", havingValue = "smtp")
public class SmtpCodeSender implements VerificationCodeSender {

    private static final Logger log = LoggerFactory.getLogger(SmtpCodeSender.class);

    private final JavaMailSender mailSender;
    private final HanYouProperties.Auth.Mail cfg;

    public SmtpCodeSender(JavaMailSender mailSender, HanYouProperties props) {
        this.mailSender = mailSender;
        this.cfg = props.getAuth().getMail();
        log.info("[M8] 验证码投递渠道 = SMTP，发件人 {}", cfg.getFrom());
    }

    @Override
    public void send(String email, String code, int expireMinutes) {
        SimpleMailMessage msg = new SimpleMailMessage();
        msg.setFrom(cfg.getFrom());
        msg.setTo(email);
        msg.setSubject(cfg.getSubject());
        msg.setText("""
                您好，

                您正在注册汉游智脑账号，验证码为：

                    %s

                该验证码 %d 分钟内有效，仅可使用一次。
                如果不是您本人操作，请忽略本邮件。

                —— 汉游智脑 · 智慧文旅与乡村振兴
                """.formatted(code, expireMinutes));

        try {
            mailSender.send(msg);
            log.info("[M8] 验证码已发送至 {}", email);
        } catch (MailException e) {
            // 不把邮件服务商的原始错误抛给前端：里面常含服务器地址与账号信息。
            // 真正的原因打到服务端日志，前端只拿到一句可执行的话。
            log.error("[M8] 验证码发送失败，目标邮箱={}", email, e);
            throw new BizException(ErrorCode.MAIL_SEND_FAILED);
        }
    }

    @Override
    public String channel() {
        return "smtp";
    }
}
