-- ============================================================
-- M5 续：分流公告（运营下发 → 游客端醒目提醒 → 游客自选）
--
-- 本文件补的是 M5 缺的最后一段用户可见闭环。
-- 在此之前，规则命中 → 风险事件 → 工单，全程只发生在**管理端内部**：
-- 运营知道"该分流了"，但**游客端一无所知**，唯一能看到替代方案的地方
-- 是景区详情页下面一张卡片 —— 而要去这个景区的人，恰恰是在**别处**
-- 决定行程的。
--
-- 本表把"分流建议"从一张运营内部的表单，变成一条**游客能看到的公告**：
--   规则命中 → 后端算出候选（乡村优先、景区其次）
--     → 运营审一眼、可改文案 → 发布
--     → 首页置顶提醒 + A 点详情页 banner
--     → 游客在候选里自己挑一个
--
-- ------------------------------------------------------------
-- 为什么单独一张表，而不是塞进 M9 的 site_banner
--
-- site_banner 是**运营手动维护的营销轮播**：无来源、无过期、与业务数据无关。
-- 分流公告是**由数据算出来、有来源事件、必须过期**的东西。混在一起
-- 会有两个后果：① 轮播的"启停"语义（enabled 0/1）表达不了"过期"；
--   ② 风险事件已闭环、公告却还挂在首页 —— 而首页上那条公告
--   会让游客跑一趟没有依据的路。
-- 两者共用**视觉**（前端复用轮播的卡片样式）就够了，不该共用**生命周期**。
--
-- ------------------------------------------------------------
-- ★ 为什么 expire_at 是 NOT NULL（这张表最容易翻车的一处）
--
-- 承载率是**日粒度、每天变**的数。今天的公告说"去 B 村"，明天 B 村可能
-- 自己也满了 —— 而首页那条公告不会自己消失。
--   ① 过期时间必须**写死在一行上**，不能靠"读的时候按 stat_date 推算"：
--      推算出来的过期时间会随服务重启的日期漂移，公告的寿命就不确定了。
--   ② 查询侧一律带 `status='PUBLISHED' AND expire_at > NOW()`，
--      **不依赖任何定时任务**。定时任务没跑（服务停了一夜）也不会出现
--      过期公告挂在首页的情况 —— 这是 fail-safe 与 fail-open 的区别。
--   ③ 扫描时顺手把到期的置为 EXPIRED，只是为了让**运营列表**看得准，
--      不是游客端正确性的前提。
--
-- ------------------------------------------------------------
-- 为什么 candidates_json 要存**快照**
--
-- 候选点的承载率也在变。公告一旦发布，就必须能回答"当时为什么推荐 B"。
-- 存快照 = 发布那一刻的距离 / 承载 / 相似度 / 得分，全部可回看。
-- 游客端展示时**再校验一次当前承载**（满了标灰并换下一个），
-- 所以既不会拿旧数据骗人，也说得清当时是怎么算的。
--
-- ------------------------------------------------------------
-- 不带 city_code：与 risk_event / work_order 同一条约定
--
-- 判断依据只有一条（见 V8 文件头）：**这张表的数据是不是"数据包的一部分"？**
-- 分流公告是运营动作产生的运行期事实，重启不能丢 —— 所以不带。
-- 带了就会随 CityPackImporter 的重灌一起被删掉，运营发的公告凭空消失。
--
-- 执行：mysql -uroot -p < db/V9__m5_diversion.sql
--
-- ✅ 本脚本**幂等**，可以反复执行：建表用 CREATE TABLE IF NOT EXISTS。
--
-- ⚠️ 幂等的代价（与 V3/V7/V8 同一个坑）：**表结构变更不会再自动生效**。
--   今后要给这张表加字段，必须**新开一个 V 文件写 ALTER TABLE**。
-- ============================================================

USE hanyou_brain;

-- ------------------------------------------------------------
-- 分流公告
--
-- code 形如 DN-20260927-001（日期 + 当日序号），与 work_order.code 同一理由：
-- 业务编码要写进界面与演示截图，自增 id 看起来像测试数据。
--
-- uk_event：一条风险事件最多一条公告 —— 与 work_order 的"一条事件一张单"
-- 同一口径。重复发布会让首页出现两条说同一件事的公告。
-- （risk_event_id 允许为空：以后可能有非风险来源的公告，那时多个 NULL
--   在 MySQL 唯一键下是允许的，不冲突。）
--
-- status 四态：
--   DRAFT      草稿，只有运营看得到（候选已算好、文案可改）
--   PUBLISHED  已发布，游客端可见（前提是 expire_at 还没到）
--   WITHDRAWN  运营主动撤下（例如 A 点客流回落了，不需要分流了）
--   EXPIRED    到期自动置位。保留这一态而不是删行：公告发过就要留痕
--
-- published_by 是自由文本，与 work_order.assignee 同一处理（当前无人员管理）。
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS diversion_notice (
  id             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  code           VARCHAR(64)  NOT NULL COMMENT '业务编码，形如 DN-20260927-001',
  risk_event_id  BIGINT                COMMENT '来源风险事件，-> risk_event.id。允许为空',

  from_poi_id    VARCHAR(64)  NOT NULL COMMENT '溢出的资源点（A 点），-> poi.id',
  from_poi_name  VARCHAR(128) NOT NULL COMMENT 'A 点名称快照。资源改名/下架后历史公告仍可读',
  district       VARCHAR(32)           COMMENT '区县快照，运营列表可按区县筛',

  title          VARCHAR(128) NOT NULL COMMENT '公告标题（游客端主文案）',
  message        VARCHAR(512)          COMMENT '公告正文。默认由候选自动生成，运营可改',

  candidates_json JSON        NOT NULL COMMENT '候选快照数组：发布那一刻的 poi_id/name/km/usage/similarity/score/reason',

  status         VARCHAR(16)  NOT NULL DEFAULT 'DRAFT'
                 COMMENT 'DRAFT 草稿 / PUBLISHED 已发布 / WITHDRAWN 已撤下 / EXPIRED 已过期',
  expire_at      DATETIME     NOT NULL COMMENT '失效时间。游客端查询一律带 expire_at > NOW()，不依赖定时任务',

  published_by   VARCHAR(64)           COMMENT '发布人（自由文本，同 work_order.assignee）',
  published_at   DATETIME              COMMENT '发布时间',

  created_at     DATETIME     DEFAULT CURRENT_TIMESTAMP,
  updated_at     DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

  PRIMARY KEY (id),
  UNIQUE KEY uk_code (code),
  UNIQUE KEY uk_event (risk_event_id),
  KEY idx_status_expire (status, expire_at),
  KEY idx_from_poi (from_poi_id)
) ENGINE=InnoDB COMMENT='分流公告（M5）。不带 city_code，是运行期数据';

-- ------------------------------------------------------------
-- 自检：确认表在、列数对、关键约束在。
-- 这里只查结构不查业务数据 —— 公告由运营在管理端发布，不该由迁移脚本凭空造。
-- ------------------------------------------------------------
SELECT 'diversion_notice' AS 表, COUNT(*) AS 列数
  FROM information_schema.columns
 WHERE table_schema = 'hanyou_brain' AND table_name = 'diversion_notice'
UNION ALL
SELECT 'diversion_notice 索引', COUNT(DISTINCT index_name)
  FROM information_schema.statistics
 WHERE table_schema = 'hanyou_brain' AND table_name = 'diversion_notice';

-- expire_at 必须是 NOT NULL —— 它是这张表唯一不能妥协的约束
SELECT column_name AS 列, is_nullable AS 可空, column_type AS 类型
  FROM information_schema.columns
 WHERE table_schema = 'hanyou_brain' AND table_name = 'diversion_notice'
   AND column_name IN ('expire_at', 'status', 'candidates_json', 'code');
