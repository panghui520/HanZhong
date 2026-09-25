package com.hanyou.brain.auth.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Demo 渠道：不发信，把验证码打进服务端日志（M8）。
 *
 * <p>这是**默认渠道**，为了比赛演示环境。理由很直接：现场多半没有可用的
 * 邮箱账号，而 SMTP 配置错了、或者被邮件服务商限频，都会让整个注册流程
 * 在评委面前直接卡死。demo 模式下退出码逻辑、冷却、错误次数、一次性使用
 * 全部照常生效，只有"投递方式"换成了日志——注册流程该验的都能验。
 *
 * <p>安全上说得通：日志只有服务端能看到。前端仍然拿不到验证码，
 * 仍然必须把用户输入的码送回来给后端校验。所以"前端自行判断验证码是否正确"
 * 这条禁止项没有被破坏。
 */
@Component
@ConditionalOnProperty(name = "hanyou.auth.mail.provider", havingValue = "demo", matchIfMissing = true)
public class DemoCodeSender implements VerificationCodeSender {

    private static final Logger log = LoggerFactory.getLogger(DemoCodeSender.class);

    @Override
    public void send(String email, String code, int expireMinutes) {
        // 机器可读行放在最前，且**标签只用 ASCII**。
        // 原因：Windows 控制台代码页默认 GBK，日志落到文件里就是 GBK 字节，
        // 用 grep 匹配中文标签会因编码不符而失配——而 demo 渠道下日志是取出
        // 验证码的唯一途径，契约一旦靠不住，整个注册演示就断了。
        // 让这一行完全由 ASCII 构成，编码问题就不存在了。
        // 格式契约：AUTH_CODE email=<邮箱> code=<验证码> ttl=<分钟>
        // 改动这一行务必同步更新 .workbuddy-ai/tmp/auth-acceptance.sh 的抽取正则。
        log.warn("AUTH_CODE email={} code={} ttl={}", email, code, expireMinutes);

        // 下面是给人看的中文说明，编码问题不影响它
        log.warn("""
                
                ============================================================
                [M8 MAIL][DEMO] 邮箱验证码（未真实发送，仅演示渠道）
                  收件邮箱 : {}
                  验 证 码 : {}
                  有效期   : {} 分钟
                如需真实发信：把 hanyou.auth.mail.provider 改为 smtp
                并配置 spring.mail.host / username / password。
                ============================================================
                """, email, code, expireMinutes);
    }

    @Override
    public String channel() {
        return "demo";
    }
}
