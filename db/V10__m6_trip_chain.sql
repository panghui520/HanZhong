-- ============================================================
-- M6 续：到访消费链（TRIP 链）—— 足迹 `trip_checkin`
--
-- 本文件补的是 M6 缺的另一半。
-- 在此之前 M6 只有**离境复购链**：游客回家之后，在"乡村好物"页挑一个
-- 产地产品、下单、发货。而 `orders.channel` 这一列**从来只被写成
-- REPURCHASE**（OrderService 里是硬编码常量）—— 也就是说
-- "到访消费（TRIP）"这条链在数据上从未产生过一单。
--
-- 后果不是少一个字段，而是**创新点二讲不完整**：
--   方案里"以体验锚点为核⼼的文旅消费链离境延伸"要说的是
--   「到访 → 体验 → 足迹 → 复购推荐」，
--   而中间那一环（足迹）根本没有载体，于是"离境延伸"缺了它的起点。
--   一条链只有后半段，答辩时被追问"你怎么知道他去过"就答不上来。
--
-- 本表就是那个起点：**一次到访 = 一行足迹**。
--   到访打卡（REAL）／演示种子（SIM）／分流引导产生的到访（DIVERSION）
--     → 足迹
--     → 下单时按足迹判定 channel=TRIP（现场带走）还是 REPURCHASE（回家再买）
--     → 复购推荐的输入是"你体验过什么"，不是"你想买什么"
--
-- ------------------------------------------------------------
-- 为什么 source 要分三值，而不是一个 is_simulated 布尔
--
-- 因为三者要回答的问题不同，且**必须能分开统计**：
--   REAL        用户自己点的打卡。是"真实到访"的证据。
--   SIM         演示种子（合成数据）。与 M5 的 synthetic 同一个意思：
--               合成数据必须写在数据里，不能只写在说明书里。
--   DIVERSION   **由分流引导产生的到访**。这一值存在的唯一理由，
--               是让方案 §15 链路③「分流产生的到访回流，大屏显示
--               分流贡献量」从一句没法核对的话，变成一个**能查出来的数**。
--               一个布尔值表达不了"这次到访是被我们引导来的"——
--               那恰恰是创新点一唯一的效果证据。
--
-- ------------------------------------------------------------
-- 为什么 poi_name / experience_name 要冗余快照
--
-- 与 `order_item` 冗余 experience_id / poi_id 完全同一个理由（见
-- V5 文件头与 M6 验收记录第一节）：`poi` 与 `experience` **带 city_code**，
-- 每次启动都会被 CityPackImporter 按城市全量删除重灌，主键与内容都可能变。
-- 足迹是"我某年某月某日去过这里"的事实，不能因为换了一次数据包，
-- 就变成"去过一个已经不存在的点"或者"去过另一个同名的点"。
-- 名字存副本，足迹就永远说得清当时去的是哪儿。
--
-- ------------------------------------------------------------
-- 不带 city_code：与 orders / cart_item / trip 同一条约定
--
-- 判断依据见 V8 文件头：**这张表的数据是不是"数据包的一部分"？**
-- 足迹是用户行为产生的运行期事实，重启不能丢。带了 city_code 就会
-- 随导入器重灌被删掉 —— 用户的旅行记忆凭空消失，比不做更糟。
-- 目的地城市改用 trip 关联表达（trip.destination_code），语义也更准。
--
-- ------------------------------------------------------------
-- ⚠️ 刻意**不建** plan_id 这一列
--
-- 设计稿的表 10 写了 `plan_id`（足迹挂在哪次行程上）。本文件不建它，
-- 理由与 V7 文件头写的"只建真正会读写的列"是同一条：
--   `trip_plan` / `trip_plan_item` **尚未落库**（M4 的行程规划当前是
--   "读数据包现算、不落库"，见 M4 行程规划验收记录），
--   此时 plan_id 没有任何代码会写、也没有任何代码会读。
-- 现在建出来，只会让"这一列到底有没有用"变成每次 review 都要重新
-- 想一遍的问题。等行程落库那一轮，新开一个 V 文件写 ALTER TABLE 加它。
--
-- ⚠️ 同样**不建** `visitor` 表（设计稿表 7）
--
-- 设计稿的 `trip_checkin.visitor_id` 指向一张独立的 `visitor` 表
-- （nickname / phone / city_from / tags_json）。本项目已经有 `app_user`
-- 承担身份（M8 认证，email + nickname），再建一张并行的身份表，
-- 结果是两份"这个人是谁"的答案，且 visitor 表没有任何登录链路会写它。
-- 所以本表用 `user_id -> app_user.id`，与 `trip.user_id` 一致。
-- 这是**有意的落地差异**，不是遗漏；已记进验收记录。
--
-- 执行：mysql -uroot -p < db/V10__m6_trip_chain.sql
--
-- ✅ 本脚本**幂等**，可以反复执行，不会丢数据：建表用 CREATE TABLE IF NOT EXISTS。
--
-- ⚠️ 幂等的代价（与 V3/V5/V7/V8/V9 同一个坑）：**表结构变更不会再自动生效**。
--   今后要给这张表加字段，必须**新开一个 V 文件写 ALTER TABLE**，
--   不要回来改本文件 —— 在本机改了也不生效，只会让新环境与旧环境结构不一致。
-- ============================================================

USE hanyou_brain;

-- ------------------------------------------------------------
-- 足迹（到访打卡）
--
-- 一次到访一行。判"到访"的粒度是**自然日**：同一用户同一天对同一个
-- 目标重复点打卡，服务层不新增行（返回已有那条），见 TripCheckinService。
-- 没有这条规则的话，"去过几次"会随点击次数虚高，而足迹正是复购推荐的
-- 输入 —— 输入被点击次数污染，推荐就没有依据了。
--
-- CHECK 约束与 `product` 的 chk_product_traceable 同一口径：
-- 一次到访必须说得清"到访了哪个资源点或哪个体验项目"，
-- 两者都空的足迹没有任何意义（既不能算到访、也不能驱动复购）。
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS trip_checkin (
  id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  user_id         BIGINT       NOT NULL COMMENT '打卡人，-> app_user.id。设计稿写 visitor_id，实际用 user_id（见文件头）',
  trip_id         BIGINT       NULL     COMMENT '所属旅行，-> trip.id。允许为空：SIM 种子与分流回流的足迹不挂在某次用户行程上',
  poi_id          VARCHAR(64)  NULL     COMMENT '到访资源点，-> poi.poi_id。与 experience_id 至少一项非空',
  poi_name        VARCHAR(128) NULL     COMMENT '资源点名称快照。poi 表会被重灌，足迹必须自带来源',
  experience_id   VARCHAR(64)  NULL     COMMENT '到访时参与的体验项目，-> experience.experience_id',
  experience_name VARCHAR(128) NULL     COMMENT '体验项目名称快照，同上',
  checkin_at      DATETIME     NOT NULL COMMENT '到访时刻。到访消费链的判定基准',
  source          VARCHAR(16)  NOT NULL DEFAULT 'REAL'
                  COMMENT 'REAL 用户主动打卡 / SIM 演示种子（合成数据）/ DIVERSION 分流引导产生的到访',
  note            VARCHAR(255) NULL     COMMENT '游客随手记一句。当前只存不用，不参与任何判定',
  created_at      DATETIME     DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  -- "我的足迹"按时间倒序取：user_id + checkin_at
  KEY idx_user_time (user_id, checkin_at),
  -- 下单时判 channel 要问"这个人有没有到访过这个体验/这个点"：两个方向各一条索引
  KEY idx_user_exp (user_id, experience_id),
  KEY idx_user_poi (user_id, poi_id),
  -- 分流贡献量按 source 统计
  KEY idx_source_time (source, checkin_at),
  CONSTRAINT chk_checkin_traceable CHECK (poi_id IS NOT NULL OR experience_id IS NOT NULL)
) ENGINE=InnoDB COMMENT='到访足迹（M6 到访消费链）。刻意不带 city_code，见文件头说明';

-- ------------------------------------------------------------
-- 建完打印一份自检，确认表在、列齐、约束在。
-- 与 V7 一样只查结构不查数据：足迹是用户走出来的，不该由迁移脚本凭空造。
-- ------------------------------------------------------------
SELECT 'trip_checkin 列数' AS 项, COUNT(*) AS 值
  FROM information_schema.columns
 WHERE table_schema = 'hanyou_brain' AND table_name = 'trip_checkin'
UNION ALL
SELECT 'trip_checkin CHECK 约束数', COUNT(*)
  FROM information_schema.table_constraints
 WHERE table_schema = 'hanyou_brain' AND table_name = 'trip_checkin'
   AND constraint_type = 'CHECK';
