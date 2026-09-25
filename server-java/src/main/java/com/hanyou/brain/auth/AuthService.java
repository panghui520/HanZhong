package com.hanyou.brain.auth;

import java.time.LocalDateTime;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hanyou.brain.common.BizException;
import com.hanyou.brain.common.ErrorCode;
import com.hanyou.brain.entity.AppUser;
import com.hanyou.brain.mapper.AppUserMapper;
import com.hanyou.brain.vo.AuthTokenVO;

/**
 * 注册与登录（M8）。
 *
 * <p>流程刻意拆成"先验证邮箱、再设密码"两步：
 * <pre>
 *   1. POST /api/auth/code          发验证码（邮箱还不必属于任何用户）
 *   2. POST /api/auth/register      提交 邮箱+验证码+密码  -> 一步完成注册
 * </pre>
 *
 * <p>关于第 2 步：接口设计上是一个请求（前端体验更顺，不用维护"半注册"状态），
 * 但服务端会依次走完"校验验证码 -> 建用户 -> 消耗验证码"。
 * 也可以拆成两个接口，但那样前端要多存一个中间态、还要处理"验证完了但没设密码"
 * 的清理问题，得不偿失。
 *
 * <p><b>角色怎么来的：</b>注册接口把 role 写死 {@code GUEST}，**不接受请求里的任何角色字段**。
 * 游客不能通过普通注册流程获得管理员权限，这是硬要求——所以它体现在代码里
 * 而不是校验逻辑里：根本没有可以让前端传 role 的入口。
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    public static final String ROLE_GUEST = "GUEST";
    public static final String ROLE_OPERATOR = "OPERATOR";
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_ACTIVE = "ACTIVE";

    /**
     * 邮箱校验用正则而不是 Apache Commons EmailValidator：少一个依赖，
     * 且这个正则足够挡住真实场景里的输入错误。邮箱的唯一权威验证是
     * "能不能收到验证码"，正则只负责拦明显不是邮箱的字符串。
     */
    private static final Pattern EMAIL = Pattern.compile(
            "^[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}$");

    /** 密码下限 8 位。上限 72 是 BCrypt 的硬限制：超过 72 字节的部分会被静默忽略 */
    private static final int PWD_MIN = 8;
    private static final int PWD_MAX = 72;

    private final AppUserMapper userMapper;
    private final AuthCodeService codeService;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider tokens;

    public AuthService(AppUserMapper userMapper, AuthCodeService codeService,
            PasswordEncoder passwordEncoder, JwtTokenProvider tokens) {
        this.userMapper = userMapper;
        this.codeService = codeService;
        this.passwordEncoder = passwordEncoder;
        this.tokens = tokens;
    }

    // ------------------------------------------------------------ 发送验证码

    /**
     * 发送注册验证码。
     *
     * <p>先挡掉"已注册的邮箱"：让用户收完邮件、输完验证码、设完密码，
     * 最后一步才告诉他"这邮箱早注册过了"，是纯粹的浪费。
     */
    public void sendRegisterCode(String email) {
        String mail = validateEmail(email);
        if (findByEmail(mail) != null) {
            throw new BizException(ErrorCode.EMAIL_ALREADY_REGISTERED);
        }
        codeService.sendCode(mail, AuthCodeService.PURPOSE_REGISTER);
    }

    // ------------------------------------------------------------ 注册

    /**
     * 注册。成功即返回令牌，前端不用再调一次登录——少一次请求，也少一次
     * "注册成功但登录失败"的中间态。
     *
     * <p><b>注意这个方法故意没有 {@code @Transactional}：</b>验证码校验必须
     * 在事务之外执行。它失败时抛出的异常如果身处事务中，会把"错误次数 +1"
     * 一起回滚掉，闸 3（错误次数上限）就失效了——这是实测踩到的坑，
     * 详见 {@code AuthCodeService.recordFailure} 的注释。
     *
     * <p>所以顺序被特意排成三段，边界就是事务边界：
     * <pre>
     *   1. 校验邮箱格式 / 密码强度 / 邮箱是否已注册   （只读，无需事务）
     *   2. 校验验证码                                （要写错误计数，必须无事务）
     *   3. doRegister：建用户 + 消耗验证码            （写业务表，必须有事务）
     * </pre>
     * 第 3 段的实际写库逻辑在 {@link #doRegister}，由 Spring 代理调用才能真正开事务
     * （自调用不走代理，标了 {@code @Transactional} 也不生效）。
     */
    public AuthTokenVO register(String email, String code, String password, String nickname) {
        String mail = validateEmail(email);
        validatePassword(password);

        if (findByEmail(mail) != null) {
            throw new BizException(ErrorCode.EMAIL_ALREADY_REGISTERED);
        }

        // 第 2 段：事务外校验。失败时错误计数已经落库，不会随后续回滚消失。
        codeService.verifyCode(mail, AuthCodeService.PURPOSE_REGISTER, code);

        // 第 3 段：真正的写库，在一个短事务里完成
        return doRegister(mail, code, password, nickname);
    }

    /**
     * 注册的写库部分：建用户 + 消耗验证码，同事务。
     *
     * <p>两者必须同生共死：用户建出来了验证码却没消耗，那个码还能再用一次
     * （可以拿去注册第二个账号）；验证码消耗了用户却没建出来，用户就只能重新收邮件。
     *
     * <p>方法必须是 {@code public} 且由外部（{@link #register}）调用，
     * Spring 的 {@code @Transactional} 才通过代理生效。改成 private 或自调用，
     * 事务会静默失效——这类 bug 不报错，只是"看起来在跑"，很难查。
     */
    @Transactional
    public AuthTokenVO doRegister(String mail, String code, String password, String nickname) {
        AppUser u = new AppUser();
        u.setEmail(mail);
        u.setPasswordHash(passwordEncoder.encode(password));
        u.setNickname(normalizeNickname(nickname, mail));
        // 角色写死：注册流程不可能产出运营账号
        u.setRole(ROLE_GUEST);
        u.setStatus(STATUS_ACTIVE);
        u.setEmailVerified(1);
        u.setTokenVersion(0);
        u.setLastLoginAt(LocalDateTime.now());
        userMapper.insert(u);

        // 业务成功了才把验证码花掉（闸 4）。放在这里而不是校验通过时，
        // 是为了让"设密码失败"时用户还能拿同一个码重试，不必重新收邮件。
        codeService.consumeCode(mail, AuthCodeService.PURPOSE_REGISTER);

        log.info("[M8] 注册成功 userId={} email={} role={}", u.getId(), mail, u.getRole());
        return tokensFor(u);
    }

    // ------------------------------------------------------------ 登录

    public AuthTokenVO login(String email, String password) {
        String mail = normalize(email);

        AppUser u = findByEmail(mail);
        if (u == null) {
            // 邮箱不存在与密码错误返回同一个错误码，不告诉攻击者"这个邮箱注册过没有"。
            // 代价是用户看不出自己是不是打错了邮箱——但那是安全上正确的取舍。
            log.info("[M8] 登录失败：邮箱未注册 email={}", mail);
            throw new BizException(ErrorCode.CREDENTIALS_INVALID);
        }
        if (u.getPasswordHash() == null
                || !passwordEncoder.matches(password == null ? "" : password, u.getPasswordHash())) {
            log.info("[M8] 登录失败：密码不匹配 userId={}", u.getId());
            throw new BizException(ErrorCode.CREDENTIALS_INVALID);
        }
        if (!STATUS_ACTIVE.equals(u.getStatus())) {
            // 走到这里说明邮箱密码都对，可以明确告知账号状态异常，不必再含糊
            throw new BizException(ErrorCode.ACCOUNT_NOT_ACTIVE);
        }

        AppUser patch = new AppUser();
        patch.setId(u.getId());
        patch.setLastLoginAt(LocalDateTime.now());
        userMapper.updateById(patch);

        log.info("[M8] 登录成功 userId={} role={}", u.getId(), u.getRole());
        return tokensFor(u);
    }

    // ------------------------------------------------------------ 退出

    /**
     * 退出登录：把 tokenVersion +1，该用户所有已签发令牌立即失效。
     *
     * <p>为什么不用"把令牌塞进黑名单"：黑名单要额外一张表、要定期清理过期项，
     * 而且每次请求都要查一次。版本号直接存在用户行上，随用户信息一起读出来，
     * 不增加任何一次额外查询。
     *
     * <p>副作用：这让"退出登录"变成了**全局**操作——用户在手机上点了退出，
     * 电脑上的登录也会一起掉。对单人使用场景这是可接受的，
     * 若将来要做"多设备独立会话"，得改成按设备签发、按设备失效。
     */
    @Transactional
    public void logout(Long userId) {
        AppUser u = userMapper.selectById(userId);
        if (u == null) {
            return;
        }
        AppUser patch = new AppUser();
        patch.setId(userId);
        patch.setTokenVersion(u.getTokenVersion() + 1);
        userMapper.updateById(patch);
        log.info("[M8] 已退出登录 userId={}，tokenVersion -> {}", userId, u.getTokenVersion() + 1);
    }

    /** 读取当前用户信息。令牌有效但用户已被删时返回 null，由 Controller 转成 4002 */
    public AppUser findById(Long userId) {
        return userMapper.selectById(userId);
    }

    // ------------------------------------------------------------ 内部

    private AppUser findByEmail(String email) {
        return userMapper.selectOne(Wrappers.<AppUser>lambdaQuery()
                .eq(AppUser::getEmail, email)
                .last("LIMIT 1"));
    }

    private AuthTokenVO tokensFor(AppUser u) {
        String token = tokens.issue(u.getId(), u.getEmail(), u.getNickname(), u.getRole(),
                u.getTokenVersion());
        AuthTokenVO vo = new AuthTokenVO();
        vo.setToken(token);
        vo.setExpiresInSeconds(tokens.getTtl().toSeconds());
        vo.setUserId(u.getId());
        vo.setEmail(u.getEmail());
        vo.setNickname(u.getNickname());
        vo.setRole(u.getRole());
        return vo;
    }

    private String validateEmail(String email) {
        String mail = normalize(email);
        if (!EMAIL.matcher(mail).matches()) {
            throw new BizException(ErrorCode.EMAIL_INVALID);
        }
        return mail;
    }

    private void validatePassword(String password) {
        if (password == null || password.length() < PWD_MIN) {
            throw new BizException(ErrorCode.PASSWORD_WEAK, "密码至少 " + PWD_MIN + " 位");
        }
        // 超长直接拒绝而不是截断：BCrypt 只处理前 72 字节，
        // 静默截断会让"密码前 72 字节相同"的两个不同密码都能登录
        if (password.length() > PWD_MAX) {
            throw new BizException(ErrorCode.PASSWORD_WEAK, "密码不得超过 " + PWD_MAX + " 位");
        }
    }

    /** 昵称没填就用邮箱 @ 前面的部分，保证顶栏永远有东西可显示 */
    private String normalizeNickname(String nickname, String email) {
        String n = nickname == null ? "" : nickname.trim();
        if (!n.isEmpty()) {
            return n.length() > 32 ? n.substring(0, 32) : n;
        }
        return email.substring(0, email.indexOf('@'));
    }

    private String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase();
    }
}
