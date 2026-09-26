-- ============================================================
-- M4 智能行程（阶段二）：旅行 + 旅行上下文
--
-- 本模块要解决的核心问题不是"再加一个问答页"，而是让 AI **记住
-- 这次旅行**。没有这两张表时，用户问"我住的酒店附近有什么好吃的"，
-- 助手只能反问"您住哪家酒店"—— 而用户上一轮刚在卡片上点过"选择酒店"。
-- 记不住上下文的助手，每一次对话都是从零开始的搜索框。
--
-- 关键设计：这两张表都**没有 city_code**。
-- CityPackImporter 每次启动按 city_code 删除并重灌 6 张业务表
-- （见 CityPackImporter.run()：product -> experience -> product_category
--   -> poi_relation -> poi -> city_profile），
-- 行程只要不挂 city_code，就永远不会被导入器碰。
-- 目的地城市改用 destination_code 表达（语义也更准：它是"这次去哪"，
-- 不是"这条数据属于哪个城市包"）。
--
-- 执行：mysql -uroot -p < db/V7__m4_trip_context.sql
--
-- ✅ 本脚本**幂等**，可以反复执行，不会丢数据：
--   · 建表一律 CREATE TABLE IF NOT EXISTS，表已存在就跳过。
--
-- ⚠️ 幂等的代价（与 V3 同一个坑）：**表结构变更不会再自动生效**。
--   今后要给这两张表加字段，必须**新开一个 V 文件写 ALTER TABLE**，
--   不要回来改本文件 —— 在本机改了也不生效，只会让新环境与旧环境结构不一致。
--
-- ⚠️ 本阶段**只建阶段二真正会读写的列**，不预建后面才用到的字段。
--   用户原始设计里的 TripContext 还有这些字段，按阶段逐步加：
--     selected_attraction / selected_restaurant   -> 阶段三、四
--     planned_attractions / visited_attractions   -> 阶段五（行程规划）
--     food_preferences / travel_start_date / travel_end_date / current_location
--                                                -> 阶段五
--   现在建出来没人写也没人读，只会让"这一列到底有没有用"变成一个
--   每次 review 都要重新想一遍的问题。
-- ============================================================

USE hanyou_brain;

-- ------------------------------------------------------------
-- 旅行
--
-- 这是"一次旅行"的身份，回答的是"谁的哪一次出行"，
-- 内容基本不变；会随对话变的字段全部放在 trip_context。
--
-- 为什么不合并成一张表：
--   一张表里混着"身份"和"会变的上下文"，语义上说得通，但一旦
--   阶段五要做多段行程（同一次出行里换酒店、换城市），
--   就会变成"要么加一堆 current_* 列，要么把行拆开"。
--   现在拆开，阶段五只需要在 trip_context 上做时间维度的扩展。
--
-- code 形如 2026-hanzhong-001：
--   年份 + 目的地编码 + 该用户当年的序号。
--   用业务编码而不是自增 id 对外，是因为 id 会被写进前端 URL 与
--   演示截图，1 / 2 / 3 看起来像测试数据，而 2026-hanzhong-001
--   一眼就能读出"谁、去哪、第几次"。
--
-- user_id -> app_user.id。**不建外键**（与 M1/M2 一致），
-- 引用完整性由服务层保证：trip 只会由登录用户自己的请求创建。
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS trip (
  id               BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
  user_id          BIGINT      NOT NULL COMMENT '所属用户，-> app_user.id',
  code             VARCHAR(64) NOT NULL COMMENT '业务编码，形如 2026-hanzhong-001',
  destination_code VARCHAR(32) NOT NULL COMMENT '目的地城市编码，与 citypack 目录名一致（hanzhong）',
  destination      VARCHAR(64) NOT NULL COMMENT '目的地显示名（汉中）。写进提示词的用这个，不是编码',
  status           VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'
                   COMMENT 'ACTIVE 进行中 / ARCHIVED 已归档。阶段二只产生 ACTIVE',
  created_at       DATETIME    DEFAULT CURRENT_TIMESTAMP,
  updated_at       DATETIME    DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  -- 同一用户不会有两个同名编码的行程（也是"懒创建"防并发的唯一键）
  UNIQUE KEY uk_user_code (user_id, code),
  -- "取我的当前行程"按这个索引命中：user_id + status，再按 id 倒序取第一条
  KEY idx_user_status (user_id, status)
) ENGINE=InnoDB COMMENT='一次旅行（M4）。刻意不带 city_code，见文件头说明';

-- ------------------------------------------------------------
-- 旅行上下文
--
-- 一次旅行一行（uk_trip 唯一键），随对话被反复改写。
--
-- 酒店为什么存 6 列而不是一个 JSON：
--   核心需求是"读到经纬度直接去高德附近搜索"。
--   经纬度必须能被 SQL 直接读出来当查询参数用；塞进 JSON 的话，
--   每一次都要在应用层解一遍、还要自己处理"字段缺失 / 类型不对"，
--   而这两列恰恰是最不能出错的一环（错了就是搜到别的城市去）。
--
-- 经纬度为什么用 DECIMAL(10,6) 而不是 FLOAT/DOUBLE：
--   这两列是**要回读并原样交给高德的查询参数**。浮点数存进去再读出来
--   可能变成 107.01999999999999，直接拼进请求里就是非法坐标。
--   DECIMAL 存的是精确值，写进去什么读出来就是什么。
--   高德要求小数点后不超过 6 位，6 位正好是它能接受的上限，
--   所以这里不会发生"精度比接口能表达的更多"的浪费。
--
-- hotel_booking_status 是**为以后接预订能力预留的开关**，当前只写
-- not_booked。本阶段不接任何真实预订（不做库存、不做支付、不做订单），
-- 也不调美团/携程的内部接口；卡片上的"去第三方预订"是跳转链接，
-- 跳走之后的事情不在本系统内。取值：
--   not_booked 未预订 / external_pending 已跳转第三方待确认
--   / booked 已预订 / cancelled 已取消
-- 之所以现在就建这一列而不是等阶段五再加：它要参与提示词
-- （助手得知道"这家还没订"才敢说"建议先预订"），
-- 而"要不要在提示词里说这句"这件事在阶段二就已经存在了。
--
-- 其余 selected_* 字段的加列时机见文件头。
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS trip_context (
  id                     BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
  trip_id                BIGINT      NOT NULL COMMENT '所属旅行，-> trip.id',

  -- ---- 已选酒店（阶段二核心：这是"连续上下文"的载体）----
  selected_hotel_poi_id  VARCHAR(64)  COMMENT '高德 POI id。前端靠它判断"这张卡片已选中"',
  selected_hotel_name    VARCHAR(128) COMMENT '酒店名，原样存高德返回值',
  selected_hotel_address VARCHAR(255) COMMENT '地址，原样存高德返回值',
  selected_hotel_lng     DECIMAL(10,6) COMMENT '经度。高德坐标是"经度,纬度"，经度在前',
  selected_hotel_lat     DECIMAL(10,6) COMMENT '纬度',
  selected_hotel_source  VARCHAR(16)  COMMENT '数据来源，当前恒为 amap。存下来是为了以后接入自有酒店数据时能区分',

  -- ---- 预订状态（预留能力，当前只有 not_booked）----
  hotel_booking_status   VARCHAR(24) NOT NULL DEFAULT 'not_booked'
                         COMMENT 'not_booked / external_pending / booked / cancelled。本阶段不产生真实订单',

  created_at             DATETIME    DEFAULT CURRENT_TIMESTAMP,
  updated_at             DATETIME    DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  -- 一次旅行只有一份上下文。有了这个唯一键，"写上下文"就可以用
  -- 先查后写的简单写法而不会因为并发请求多出一行来。
  UNIQUE KEY uk_trip (trip_id)
) ENGINE=InnoDB COMMENT='旅行的可变上下文（M4）。一次旅行一行';

-- ------------------------------------------------------------
-- 建完打印一份自检，确认表在、列齐。
-- 这里只查结构不查数据：本脚本不插入任何业务数据 ——
-- 行程是用户点出来的，不该由迁移脚本凭空造。
-- ------------------------------------------------------------
SELECT 'trip' AS 表, COUNT(*) AS 列数
  FROM information_schema.columns
 WHERE table_schema = 'hanyou_brain' AND table_name = 'trip'
UNION ALL
SELECT 'trip_context', COUNT(*)
  FROM information_schema.columns
 WHERE table_schema = 'hanyou_brain' AND table_name = 'trip_context';
