-- ============================================================
-- M8 认证与权限
--
-- 本模块是一个**横切关注点**，不属于 M1–M7 任何一纵切业务模块，
-- 因此独立编号为 M8，与业务模块并列。
--
-- 关键设计：这两张表都**没有 city_code**。
-- CityPackImporter 每次启动按 city_code 删除并重灌业务表
-- （见 CityPackImporter.run()：product -> experience -> product_category
--   -> poi_relation -> poi -> city_profile），
-- 用户与验证码只要不挂 city_code，就永远不会被导入器碰。
--
-- 执行：mysql -uroot -p < db/V3__m8_auth.sql
--
-- ✅ 本脚本**幂等**，可以反复执行，不会丢数据：
--   · 建表用 CREATE TABLE IF NOT EXISTS，表已存在就跳过；
--   · 演示账号用 INSERT IGNORE，邮箱已存在就整行跳过，
--     所以你后来改过的密码、昵称都不会被覆盖。
--
-- 2026-09-25 之前这里写的是 DROP TABLE IF EXISTS + CREATE TABLE，
-- 重跑一次会把账号全部抹掉（当天真实发生过一次，25 个账号没了）。已改掉。
--
-- ⚠️ 幂等的代价：**表结构变更不会再自动生效**。
--   CREATE TABLE IF NOT EXISTS 遇到已存在的表只会跳过，不会补字段。
--   今后要给这两张表加字段，必须**新开一个 V 文件写 ALTER TABLE**，
--   不要回来改本文件 —— 在本机改了也不生效，只会让新环境与旧环境结构不一致。
--
-- 同理，V1/V2 建的是 city_profile / poi / poi_relation / experience /
-- product / product_category，这些表每次启动都会被 CityPackImporter 按
-- city_code 全量重灌，本来就不需要靠脚本维护数据；而 app_user /
-- auth_email_code（本脚本）与 poi_image / site_banner（V4）刻意不带
-- city_code，是纯运行期数据，所以它们的**表结构**只能靠 ALTER 演进。
-- ============================================================

USE hanyou_brain;

-- ------------------------------------------------------------
-- 用户账号
--
-- 说明几个反直觉但必要的字段：
--
-- 1) password_hash 可空。注册分两步走：先验证邮箱（此时还没有密码），
--    再设置密码。第一步完成时用户行已经建出来了，password_hash 还是 NULL，
--    状态停留在 PENDING（未验证邮箱）。不这么设计就得把邮箱验证状态
--    放在一张临时表里、注册成功后再搬运，反而多一处不一致。
--
-- 2) token_version 是 JWT 服务端失效的开关。JWT 天生无状态，
--    签发后到期前一直有效，改密码/退出登录都拦不住它。
--    校验令牌时同时比对 version 与库里的值，一旦对不上就拒绝；
--    退出登录或强制失效时把这一列 +1，该用户所有历史 JWT 立刻作废。
--    这比维护一张"已失效令牌黑名单"简单，且不会无限增长。
--
-- 3) 不建外键（与 M1/M2 一致），引用完整性由服务层保证。
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS app_user (
  id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  email         VARCHAR(128) NOT NULL COMMENT '登录邮箱，统一转小写后存储',
  password_hash VARCHAR(72)           COMMENT 'BCrypt 哈希（60 字符），NULL = 尚未设置密码',
  nickname      VARCHAR(64)           COMMENT '昵称，未填时由服务层用邮箱前缀兜底',
  role          VARCHAR(16)  NOT NULL DEFAULT 'GUEST'
                COMMENT 'GUEST 游客 / OPERATOR 运营。注册接口写死 GUEST，不接受前端传值',
  status        VARCHAR(16)  NOT NULL DEFAULT 'PENDING'
                COMMENT 'PENDING 待邮箱验证 / ACTIVE 正常 / DISABLED 已禁用',
  email_verified TINYINT     NOT NULL DEFAULT 0 COMMENT '邮箱是否已验证：0 否 / 1 是',
  token_version INT          NOT NULL DEFAULT 0
                COMMENT 'JWT 版本号。递增一次 => 该用户所有已签发令牌立即失效',
  last_login_at DATETIME              COMMENT '最近一次登录成功时间',
  created_at    DATETIME     DEFAULT CURRENT_TIMESTAMP,
  updated_at    DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  -- 邮箱唯一：同一邮箱不能重复注册
  UNIQUE KEY uk_email (email)
) ENGINE=InnoDB COMMENT='用户账号（游客与运营共用一张表，靠 role 区分）';

-- ------------------------------------------------------------
-- 邮箱验证码
--
-- 五道闸全部在服务层实现，不依赖前端：
--   ① 有效期 5 分钟  -> expires_at
--   ② 发送冷却 60 秒 -> 查最近一条的 created_at
--   ③ 错误次数上限   -> attempt_count，超过 max_attempts 直接失效
--   ④ 一次性使用     -> used_at 非空即不可再用
--   ⑤ 同时只有一条有效码 -> 发新码时把该邮箱此前的有效码全部作废
--
-- code_hash 不存明文：万一库被读走，验证码本身不能被直接拿去用。
-- 但这里**刻意不用 BCrypt**——BCrypt 每次加盐结果都不同，没法用哈希做等值查询。
-- 验证码只有 6 位数字（100 万种可能），本来就不是能扛住离线爆破的强度，
-- 所以真正的防线是"5 分钟 + 5 次错误上限"，哈希在这里只是防明文泄漏。
-- 用固定盐的 SHA-256 即可：可查询、不可逆。
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS auth_email_code (
  id            BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
  email         VARCHAR(128) NOT NULL COMMENT '目标邮箱，统一转小写',
  code_hash     CHAR(64)    NOT NULL COMMENT 'SHA-256(邮箱 + 验证码 + 固定盐) 的十六进制，不存明文',
  purpose       VARCHAR(16) NOT NULL DEFAULT 'REGISTER'
                COMMENT 'REGISTER 注册 / RESET 重置密码（当前只实现 REGISTER）',
  attempt_count INT         NOT NULL DEFAULT 0 COMMENT '已错误的校验次数',
  max_attempts  INT         NOT NULL DEFAULT 5 COMMENT '错误次数上限，超过后本条作废',
  expires_at    DATETIME    NOT NULL COMMENT '过期时间 = 签发时间 + 5 分钟',
  used_at       DATETIME             COMMENT '使用时间，非 NULL 表示已用过（一次性）',
  invalidated_at DATETIME            COMMENT '主动作废时间（发新码时把旧码作废、错误超限时作废）',
  created_at    DATETIME    DEFAULT CURRENT_TIMESTAMP,
  updated_at    DATETIME    DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  -- 校验与冷却查询都是"按邮箱取最近一条"，这个索引直接命中
  KEY idx_email_purpose_created (email, purpose, created_at)
) ENGINE=InnoDB COMMENT='邮箱验证码（后端生成、后端校验，前端不参与判定）';

-- ------------------------------------------------------------
-- 演示账号（幂等）
--
-- 用 INSERT IGNORE 靠 uk_email 唯一键实现幂等：邮箱已存在就整行跳过，
-- 因此**不会覆盖**你后来改过的密码或昵称。重跑一百遍结果一样。
--
-- password_hash 是 BCrypt（$2a$10$ 开头，60 字符），与 AuthService 登录时
-- 的校验方式一致。下面 4 个哈希就是本机当前在用的值，
-- 所以**密码与你现在登录用的完全一样**，不需要重新记。
--
-- role='OPERATOR' 只能靠 SQL 设置：注册接口写死 GUEST，不接受前端传值
-- （见 AuthService.doRegister）。所以运营账号必须在这里种，
-- 否则新环境里没人能进管理端。
--
-- 需要统一重置密码时（例如换了演示机、忘了旧密码）：
--   把下面这段的注释去掉，把 $2a$10$... 换成新密码的 BCrypt 哈希。
--   UPDATE app_user SET password_hash = '$2a$10$在此填入新哈希'
--    WHERE email IN ('admin@hanyou.local','visitor1@hanyou.local',
--                    'visitor2@hanyou.local','visitor3@hanyou.local');
-- ------------------------------------------------------------
INSERT IGNORE INTO app_user
  (email, password_hash, nickname, role, status, email_verified)
VALUES
  ('admin@hanyou.local',
   '$2a$10$ht9v4gy8TizsW2NCxWWlhORaR7AjRVL3.4MJknhgewyIObm4bkWUy',
   '运营管理员', 'OPERATOR', 'ACTIVE', 1),
  ('visitor1@hanyou.local',
   '$2a$10$F1dsIet/zpU4AlTnlr0sHeOPbMOyh5UzV3B7HE4x6QtnPw6/mxSOG',
   '游客甲', 'GUEST', 'ACTIVE', 1),
  ('visitor2@hanyou.local',
   '$2a$10$L8IWxk02PwjwM.RllfH/2eXyhlfAenLDbdVbBNKJhIxKETiUigv.y',
   '游客乙', 'GUEST', 'ACTIVE', 1),
  ('visitor3@hanyou.local',
   '$2a$10$YRl87Gcv9iRp8f4tjoIk5e86F.SQwNz56.P0HxQiiJ4sRAZ21K99G',
   '游客丙', 'GUEST', 'ACTIVE', 1);
