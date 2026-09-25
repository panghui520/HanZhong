package com.hanyou.brain.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hanyou.brain.auth.mail.VerificationCodeSender;
import com.hanyou.brain.common.BizException;
import com.hanyou.brain.common.ErrorCode;
import com.hanyou.brain.config.HanYouProperties;
import com.hanyou.brain.entity.AuthEmailCode;
import com.hanyou.brain.mapper.AuthEmailCodeMapper;

/**
 * 邮箱验证码的生成与校验（M8）。**整个注册流程的安全性都压在这个类上。**
 *
 * <p>五道闸，任何一道不满足都直接拒绝：
 * <ol>
 *   <li><b>发送冷却</b>：同一邮箱 60 秒内不能重复发。防的是被当成短信轰炸器使用。
 *   <li><b>有效期</b>：5 分钟。过期即失效，不因为"用户可能只是慢了点"而放宽。
 *   <li><b>错误次数上限</b>：错 5 次这条码就作废，必须重新发。6 位数字若不限次数，
 *       脚本几分钟就能穷举完，那验证码就形同虚设。
 *   <li><b>一次性</b>：校验通过立刻写 usedAt，同一个码不能用第二次。
 *       否则"注册"和"重置密码"两个流程可以共用一张码。
 *   <li><b>同时只有一条有效码</b>：发新码时把该邮箱此前所有未用的码作废。
 *       这样用户收到的多封邮件里，只有最新那封能用，不会出现"翻出旧邮件也能过"的混乱。
 * </ol>
 *
 * <p>哈希用固定盐 SHA-256 而不是 BCrypt，原因见 {@code HanYouProperties.Auth.Code.hashSalt}：
 * BCrypt 每盐不同，就无法用 code_hash 做等值查询，只能全表扫出来逐个比对。
 * 验证码只有 6 位数字，本来就不是抗离线爆破的强度，真正的防线是闸 2 和闸 3。
 */
@Service
public class AuthCodeService {

    private static final Logger log = LoggerFactory.getLogger(AuthCodeService.class);

    public static final String PURPOSE_REGISTER = "REGISTER";

    /** 用 SecureRandom 而不是 Random：Random 的输出可被预测，验证码不能被预测 */
    private final SecureRandom random = new SecureRandom();

    private final AuthEmailCodeMapper codeMapper;
    private final VerificationCodeSender sender;
    private final HanYouProperties.Auth.Code cfg;

    public AuthCodeService(AuthEmailCodeMapper codeMapper, VerificationCodeSender sender,
            HanYouProperties props) {
        this.codeMapper = codeMapper;
        this.sender = sender;
        this.cfg = props.getAuth().getCode();
    }

    // ------------------------------------------------------------ 发送

    /**
     * 生成并投递验证码。
     *
     * <p>{@code @Transactional} 在这里是必要的：既要作废旧码、又要插新码，
     * 中途失败的话不能留下"旧码已作废、新码没写进去"的空档——
     * 那会让用户重新发送时撞上冷却，却一封邮件也收不到。
     */
    @Transactional
    public void sendCode(String email, String purpose) {
        String mail = normalize(email);
        LocalDateTime now = LocalDateTime.now();

        // 闸 1：冷却
        AuthEmailCode latest = latestOf(mail, purpose);
        if (latest != null) {
            LocalDateTime cooldownEnd = latest.getCreatedAt().plusSeconds(cfg.getCooldownSeconds());
            if (now.isBefore(cooldownEnd)) {
                long wait = java.time.Duration.between(now, cooldownEnd).toSeconds() + 1;
                throw new BizException(ErrorCode.CODE_COOLDOWN, "请等待 " + wait + " 秒后再试");
            }
        }

        // 闸 5：把该邮箱此前未作废的码全部作废，保证同时只有一条有效码
        List<AuthEmailCode> alive = codeMapper.selectList(Wrappers.<AuthEmailCode>lambdaQuery()
                .eq(AuthEmailCode::getEmail, mail)
                .eq(AuthEmailCode::getPurpose, purpose)
                .isNull(AuthEmailCode::getUsedAt)
                .isNull(AuthEmailCode::getInvalidatedAt));
        for (AuthEmailCode old : alive) {
            AuthEmailCode patch = new AuthEmailCode();
            patch.setId(old.getId());
            patch.setInvalidatedAt(now);
            codeMapper.updateById(patch);
        }

        String code = randomCode();

        AuthEmailCode row = new AuthEmailCode();
        row.setEmail(mail);
        row.setCodeHash(hash(mail, code));
        row.setPurpose(purpose);
        row.setAttemptCount(0);
        row.setMaxAttempts(cfg.getMaxAttempts());
        row.setExpiresAt(now.plusMinutes(cfg.getExpireMinutes()));
        codeMapper.insert(row);

        // 发信放在最后：先落库再投递，若投递失败会抛异常回滚，
        // 不会留下一条"库里有码但用户没收到"的记录，用户重发也不会撞冷却。
        sender.send(mail, code, cfg.getExpireMinutes());
        log.info("[M8] 验证码已生成 email={} purpose={} channel={} 有效期={}分钟",
                mail, purpose, sender.channel(), cfg.getExpireMinutes());
    }

    // ------------------------------------------------------------ 校验

    /**
     * 校验验证码。**通过时不会消耗它**——消耗必须由调用方在业务真正成功后再做，
     * 否则"验证码对了但设置密码失败"会让用户既用掉码又没注册成功。
     *
     * <p>校验顺序是有讲究的：先按"能不能计入错误次数"分类，
     * 再比对。已失效/已过期的码不再累加错误次数——否则一条早已过期的码
     * 被连试 5 次，会把错误次数耗光，用户重新获取后心理上会以为是自己输错了。
     *
     * <p><b>这个方法必须在事务之外被调用。</b>它虽然自己不写业务表，但校验失败时
     * 会累加错误次数；若它跑在调用方的事务里，调用方随后的回滚会把计数一起抹掉，
     * 闸 3 就形同虚设。详见 {@link #recordFailure} 里关于 REQUIRES_NEW 为什么不成立的说明。
     */
    public void verifyCode(String email, String purpose, String code) {
        String mail = normalize(email);

        AuthEmailCode row = latestOf(mail, purpose);
        if (row == null) {
            throw new BizException(ErrorCode.CODE_NOT_FOUND);
        }
        if (row.getUsedAt() != null) {
            throw new BizException(ErrorCode.CODE_USED);
        }
        if (row.getInvalidatedAt() != null) {
            throw new BizException(ErrorCode.CODE_ATTEMPTS_EXCEEDED);
        }
        if (LocalDateTime.now().isAfter(row.getExpiresAt())) {
            throw new BizException(ErrorCode.CODE_EXPIRED);
        }
        if (row.getAttemptCount() >= row.getMaxAttempts()) {
            throw new BizException(ErrorCode.CODE_ATTEMPTS_EXCEEDED);
        }

        String expected = row.getCodeHash();
        String actual = hash(mail, code == null ? "" : code.trim());
        // 定长比较用 MessageDigest.isEqual：它按字节恒定耗时，不会因为
        // "前几位对没对"而泄露时间差。两个哈希长度固定，不存在长度泄漏。
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8))) {

            int used = recordFailure(row.getId(), row.getMaxAttempts());
            if (used >= row.getMaxAttempts()) {
                throw new BizException(ErrorCode.CODE_ATTEMPTS_EXCEEDED);
            }
            int left = row.getMaxAttempts() - used;
            throw new BizException(ErrorCode.CODE_MISMATCH, "验证码不正确，还可尝试 " + left + " 次");
        }
    }

    /**
     * 记录一次校验失败，返回累加后的错误次数。
     *
     * <p><b>为什么用一条原子 UPDATE 而不是"读出来 +1 再写回去"：</b>
     * 验证码校验会被并发调用（同一个码被脚本并发猜测，或用户连点两次提交）。
     * "读-改-写"三步之间必然有窗口，两个请求会读到同一个旧值、各自写出同一个新值，
     * 计数少加一次——错误次数上限就是被这样绕过去的。
     * {@code SET attempt_count = attempt_count + 1} 由数据库在行锁内完成，
     * 天然不会丢计数，也不需要悲观锁。
     *
     * <p><b>为什么这里没有 REQUIRES_NEW：</b>试过了，不成立，而且原因很隐蔽——
     * Spring 的 REQUIRES_NEW 只是"挂起"外层事务，但内层事务向连接池要连接时，
     * 拿到的<em>还是外层事务正占着的那条物理连接</em>（挂起不等于归还）。
     * MySQL 一条连接同一时刻只有一个事务作用域，于是这条 UPDATE 实际仍跑在
     * 外层事务里，外层一抛异常回滚，计数照样被抹掉——现象和没加 REQUIRES_NEW 一模一样。
     *
     * <p>所以真正的解法是：**让校验发生在注册事务开启之前**。见
     * {@code AuthService.register} 的调用顺序——先校验验证码、再进事务建用户，
     * 这样本方法就是在自动提交模式下执行的，一次 UPDATE 即一次提交。
     *
     * <p>注意：这不是"绕过事务"，而是有意把"安全计数"和"业务成败"解耦——
     * 用户注册失败这件事，不应该让"他试错了几次"这个事实被遗忘。
     */
    public int recordFailure(Long codeId, int maxAttempts) {
        // 自增无法用实体 updateById 表达（那只支持"设成某个值"），用 setSql 写常量 SQL。
        // 括号内没有任何拼接变量，不构成注入面。
        codeMapper.update(null, Wrappers.<AuthEmailCode>lambdaUpdate()
                .eq(AuthEmailCode::getId, codeId)
                .setSql("attempt_count = attempt_count + 1"));

        // 回读一次以拿到真实计数：并发下只有数据库知道最终值
        AuthEmailCode fresh = codeMapper.selectById(codeId);
        int used = fresh == null ? maxAttempts : fresh.getAttemptCount();

        // 闸 3 触发：错满就作废，用户必须重新获取
        if (used >= maxAttempts) {
            AuthEmailCode inv = new AuthEmailCode();
            inv.setId(codeId);
            inv.setInvalidatedAt(LocalDateTime.now());
            codeMapper.updateById(inv);
        }
        return used;
    }

    /**
     * 标记验证码为已使用（闸 4：一次性）。
     *
     * <p>必须由调用方在业务成功后显式调用。设计成独立方法而不是塞进
     * {@link #verifyCode}，是为了让"验证通过"和"消耗掉"之间有明确的边界，
     * 调用点一眼能看出这个码是在哪一步被真正花掉的。
     */
    @Transactional
    public void consumeCode(String email, String purpose) {
        AuthEmailCode row = latestOf(normalize(email), purpose);
        if (row == null || row.getUsedAt() != null) {
            return;
        }
        AuthEmailCode patch = new AuthEmailCode();
        patch.setId(row.getId());
        patch.setUsedAt(LocalDateTime.now());
        codeMapper.updateById(patch);
    }

    // ------------------------------------------------------------ 内部

    /** 取该邮箱该用途下最近的一条记录（不论是否已失效，失效状态要在校验里分别区分） */
    private AuthEmailCode latestOf(String email, String purpose) {
        return codeMapper.selectOne(Wrappers.<AuthEmailCode>lambdaQuery()
                .eq(AuthEmailCode::getEmail, email)
                .eq(AuthEmailCode::getPurpose, purpose)
                .orderByDesc(AuthEmailCode::getCreatedAt)
                .orderByDesc(AuthEmailCode::getId)
                .last("LIMIT 1"));
    }

    /** 生成指定位数的数字验证码。首位允许为 0，按字符串处理 */
    private String randomCode() {
        StringBuilder sb = new StringBuilder(cfg.getLength());
        for (int i = 0; i < cfg.getLength(); i++) {
            sb.append(random.nextInt(10));
        }
        return sb.toString();
    }

    /**
     * 计算 code_hash。把邮箱也拌进去：同一个验证码发给不同邮箱时哈希不同，
     * 数据库里出现重复哈希就无法反推出"这两个邮箱拿了同一个码"。
     */
    private String hash(String email, String code) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(cfg.getHashSalt().getBytes(StandardCharsets.UTF_8));
            md.update(email.getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0x1f);
            md.update(code.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 强制实现的算法，走不到这里
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /** 邮箱统一小写去空格后存储与比对，避免 Foo@x.com 与 foo@x.com 被当成两个人 */
    private String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase();
    }
}
