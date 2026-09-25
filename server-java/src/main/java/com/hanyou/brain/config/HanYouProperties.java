package com.hanyou.brain.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Data;

/** 对应 application.yml 中的 hanyou.* 配置 */
@Data
@ConfigurationProperties(prefix = "hanyou")
public class HanYouProperties {

    /** 城市数据包根目录（内含 <city>/ 子目录） */
    private String citypackDir = "../citypack";

    /** 当前城市编码，换城市只改这里 */
    private String city = "hanzhong";

    /** 启动时是否用 CityPack 覆盖导入 */
    private boolean importOnStartup = true;

    /**
     * 媒体文件根目录（M9）。运营上传的景点配图与轮播图都落在这里。
     *
     * <p>默认相对 server-java 的上一级，即仓库根目录下的 media/，
     * 与 citypack-dir 的相对方式保持一致。数据库里存的是相对本目录的路径。
     */
    private String mediaDir = "../media";

    /** Python AI 服务。M3 起使用，M4 行程规划复用同一个客户端 */
    private Ai ai = new Ai();

    /** 认证与权限。M8 起使用 */
    private Auth auth = new Auth();

    @Data
    public static class Ai {

        /** 是否启用 AI 能力。关掉后前端会收到"未启用"，但业务接口不受影响 */
        private boolean enabled = true;

        /** AI 服务地址。Python 只监听回环地址，不经这里访问不到 */
        private String baseUrl = "http://127.0.0.1:8000";

        /**
         * 内部令牌，必须与 Python 侧 INTERNAL_TOKEN 完全一致。
         * 两个服务共用一个值，改的时候要同时改，否则 /api/ai/** 会全部 503。
         */
        private String internalToken = "change_me_internal_token";

        /** 建连超时（秒）。给得短一点：Python 没起来时要立刻降级，不能让用户等 */
        private int connectTimeoutSeconds = 3;

        /** 单次问答的整体超时（秒）。流式生成耗时长，这里要留够 */
        private int readTimeoutSeconds = 120;
    }

    /**
     * M8 认证与权限。
     *
     * <p>分成三块：jwt（令牌签发与校验）、mail（验证码投递渠道）、code（验证码策略）。
     * 策略值全在这里配，不要在 Service 里写死——答辩现场调参数不用改代码重编译。
     */
    @Data
    public static class Auth {

        private Jwt jwt = new Jwt();
        private Mail mail = new Mail();
        private Code code = new Code();

        @Data
        public static class Jwt {

            /**
             * 签名密钥（HMAC-SHA256）。
             *
             * <p>绝不能是仓库里的默认值上生产；比赛演示环境也建议改掉，
             * 否则任何读过本仓库的人都能自行签发一张有效令牌。
             * 长度必须 >= 32 字节，否则 HS256 会抛 WeakKeyException。
             */
            private String secret = "change_me_hanyou_brain_jwt_secret_at_least_32_bytes";

            /** 令牌有效期（分钟）。演示用 12 小时，够覆盖一场答辩 */
            private int expireMinutes = 720;

            /** 签发方与受众，用于校验令牌确实由本服务签发 */
            private String issuer = "hanyou-brain";
        }

        @Data
        public static class Mail {

            /**
             * 投递渠道。demo = 只写日志不发信；smtp = 走 spring.mail.* 真实发送。
             *
             * <p>默认 demo 是刻意的：比赛现场很可能没有可用邮箱账号，
             * 而"演示当天发不出验证码"会直接让整个注册流程无法展示。
             */
            private String provider = "demo";

            /** 发件人。demo 模式下只用于日志展示 */
            private String from = "汉游智脑 <no-reply@hanyou.local>";

            /** 邮件的品牌化文案，避免各处理散落硬编码字符串 */
            private String subject = "【汉游智脑】邮箱验证码";
        }

        @Data
        public static class Code {

            /** 验证码位数。6 位是行业惯例：100 万种组合，配合错误上限足够 */
            private int length = 6;

            /** 有效期（分钟） */
            private int expireMinutes = 5;

            /** 发送冷却（秒）。同一邮箱两次发送之间至少间隔这么久 */
            private int cooldownSeconds = 60;

            /** 同一条验证码允许的错误次数，超过即作废（必须重新发送） */
            private int maxAttempts = 5;

            /**
             * 哈希固定盐。
             *
             * <p>这里用固定盐而不是 BCrypt：BCrypt 每次加盐结果都不同，
             * 就没法用 code_hash 做等值查询，只能全表扫出来逐个比对。
             * 验证码只有 6 位数字，本就不是抗离线爆破的强度，
             * 真正的防线是"5 分钟 + 5 次错误上限"，哈希只负责不存明文。
             */
            private String hashSalt = "hanyou_brain_auth_code_salt";
        }
    }
}
