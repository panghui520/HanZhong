-- ============================================================
-- M10 续：景点可编辑字段扩展 + 景点评论
--
-- 本文件解决两件事，它们各自独立，只是碰巧同一轮做：
--
--   A. 景点（poi）的字段不够运营改
--      现状：运营在管理端能改的只有 V1 建的那几列，且 source='PACK' 的行
--      连改都不允许。本轮做「管理员接管」——第一次编辑一条数据包景点时，
--      把它的 source 从 PACK 改成 ADMIN，从此导入器不再碰它（详见
--      CityPackImporter.run() 里 takenOverPoiIds 的注释）。
--      同时补上运营确实需要、但库里一直没有的四列。
--
--   B. 景点评论要真数据
--      现状：游客端景点详情页的「游客反馈」是前端按资源 id 派生的假数据
--      （web/src/mock/reviews.ts 的 reviewsOf）。不同景点确实显示了不同
--      内容，但那是伪随机算出来的，不是任何人写的。本轮换成一张真表。
--
-- ------------------------------------------------------------
-- ★ 四列各归哪一层：数据包态还是运营态
--
-- 判断方法（与 V11 文件头同一条）：**看它在 citypack/*.json 里有没有值。**
--   有  -> 数据包态，重灌以包为准；
--   没有 -> 运营态，重灌不该动它。
--
--   address / phone / detail   数据包态。它们描述的是"这个资源是什么"，
--                              属于数据包该提供的资料。现有 pois.json 没有
--                              这三个字段，所以新列初始为 NULL —— 这是
--                              **诚实的结果**，不是遗漏：我们不替 42 条景点
--                              编造门牌号和电话。运营接管某条资源后可以补。
--                              导入器不还原它们（以包为准，包没有就是空）。
--
--   warning_threshold          运营态。它是"这条资源的承载预警线定在多少"，
--                              是运营口径而不是资源事实。**它只会出现在
--                              ADMIN 行上** —— 因为设置它的唯一入口是
--                              「编辑」，而编辑一条 PACK 行会先把它接管成
--                              ADMIN。所以导入器天然碰不到它，
--                              不需要像 status 那样做"重灌后还原"。
--
-- ------------------------------------------------------------
-- ★ poi_comment 为什么不带 city_code
--
-- 与 app_user / orders / cart_item / trip_checkin 同一条约定（见 V8 文件头）：
-- **这张表的数据是不是"数据包的一部分"？** 评论是游客写出来的运行期事实，
-- 重启不能丢。带了 city_code 就会随导入器重灌被删掉 —— 用户写的评论凭空
-- 消失，比不做更糟。目的地城市由 poi_id 间接表达。
--
-- ★ poi_comment 为什么不建 nickname 快照列
--
-- order_review（V6）也没有这一列：署名是查 app_user 现取的
-- （见 OrderService.toOrderVO）。评论同理 —— 与项目既有做法保持一致，
-- 而不是在这一张表上另立一套。
--
-- ★ status 的取值与默认
--
-- PENDING 待审 / APPROVED 通过 / HIDDEN 隐藏。**默认 APPROVED**：
-- 这是比赛演示项目，游客提交后要立刻在页面上看到自己写的那条
-- （否则演示流程会卡在"等审核"上）。审核能力保留在管理端 ——
-- 运营可以把违规评论改成 HIDDEN，游客端随即看不到。
-- 这条取舍写在 AppComment 的注释里，也写进验收记录。
--
-- 执行：mysql -uroot -p --default-character-set=utf8mb4 < db/V12__m10_poi_edit_comment.sql
--
-- ✅ 幂等：加列走 hanyou_add_column（MySQL 8 的 ADD COLUMN 没有
--   IF NOT EXISTS，只能先查 information_schema 再动态执行）；
--   建表用 CREATE TABLE IF NOT EXISTS。
--
-- ⚠️ 幂等的代价（与 V3/V5/V7/V8/V9/V10/V11 同一个坑）：
--   **表结构变更不会再自动生效**。今后要给 poi 或 poi_comment 加字段，
--   仍然要**新开一个 V 文件写 ALTER**，不要回来改本文件。
-- ============================================================

USE hanyou_brain;

-- 与 V6/V11 里同名的过程：先 DROP 再建，保证本文件能反复执行。
-- （V11 结尾把它 DROP 掉了，所以这里必须重新定义，不能假定它还在。）
DROP PROCEDURE IF EXISTS hanyou_add_column;

DELIMITER $$
CREATE PROCEDURE hanyou_add_column(
  IN p_table VARCHAR(64),
  IN p_column VARCHAR(64),
  IN p_definition TEXT
)
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = p_table
       AND COLUMN_NAME = p_column
  ) THEN
    SET @ddl = CONCAT('ALTER TABLE `', p_table, '` ADD COLUMN ', p_definition);
    PREPARE stmt FROM @ddl;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;
  END IF;
END$$
DELIMITER ;

-- ------------------------------------------------------------
-- A. poi 补四列
--
-- ⚠️ 两个写法上的坑（V11 实测踩过，这里同样适用）：
--   1. definition 里**必须自带列名** —— 上面那个过程的 CONCAT 只拼了
--      `ADD COLUMN ` + definition，p_column 只用于 information_schema 判重。
--      传 'VARCHAR(255) ...' 会拼出 `ADD COLUMN VARCHAR(255)`，缺列名，报 1064。
--   2. 用**单引号 + 内部单引号双写**，不用双引号包整个 definition。
-- ------------------------------------------------------------
CALL hanyou_add_column('poi', 'address',
  'address VARCHAR(255) NULL COMMENT ''详细地址（街道门牌）。district 只到区县，这里补到可导航的粒度''');

CALL hanyou_add_column('poi', 'phone',
  'phone VARCHAR(32) NULL COMMENT ''对外联系电话。没有就是 NULL，不编造''');

CALL hanyou_add_column('poi', 'detail',
  'detail TEXT NULL COMMENT ''详细介绍正文。summary 是一句话简介，这里是长文''');

CALL hanyou_add_column('poi', 'warning_threshold',
  'warning_threshold DECIMAL(4,2) NULL COMMENT ''承载率预警线 0.01~1.00。NULL = 用 risk_rule 的全局阈值；只存在于 ADMIN 行''');

-- ------------------------------------------------------------
-- B. 景点评论
--
-- CHECK 约束与 product 的 chk_product_traceable、trip_checkin 的
-- chk_checkin_traceable 同一口径：把"这条数据本身说不说得通"写进数据库，
-- 而不是只靠服务层自觉。评分不在 1..5 之间、内容为空的评论没有意义。
--
-- content 给 500 字：够写一段真实感受，又不至于让详情页的一条评论
-- 占掉整屏。前端也按这个长度做输入限制。
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS poi_comment (
  id         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  poi_id     VARCHAR(32)  NOT NULL COMMENT '评论的资源点，-> poi.id',
  user_id    BIGINT       NOT NULL COMMENT '评论人，-> app_user.id。署名由 app_user.nickname 现取，不冗余',
  content    VARCHAR(500) NOT NULL COMMENT '评论内容',
  rating     TINYINT      NOT NULL COMMENT '评分 1..5',
  status     VARCHAR(16)  NOT NULL DEFAULT 'APPROVED'
             COMMENT 'PENDING 待审 / APPROVED 通过 / HIDDEN 隐藏。默认 APPROVED，见文件头',
  created_at DATETIME     DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  -- 详情页的主查询就是"这个点、可展示的、按时间倒序"：一条索引直接命中
  KEY idx_poi_status_created (poi_id, status, created_at),
  -- 管理端按状态筛（看有多少待审 / 已隐藏）
  KEY idx_status_created (status, created_at),
  -- "我发过哪些评论"与"同一用户是否刷屏"都要按用户查
  KEY idx_user_created (user_id, created_at),
  CONSTRAINT chk_comment_rating CHECK (rating BETWEEN 1 AND 5),
  CONSTRAINT chk_comment_content CHECK (CHAR_LENGTH(content) > 0)
) ENGINE=InnoDB COMMENT='景点评论（游客提交、运营审核）。刻意不带 city_code，见文件头';

DROP PROCEDURE IF EXISTS hanyou_add_column;

-- ------------------------------------------------------------
-- 自检：列齐不齐、表在不在、约束在不在。
-- 与 V7/V10 一样只查结构不查数据 —— 评论是游客写的，不该由迁移脚本凭空造。
-- ------------------------------------------------------------
SELECT 'poi 新增四列' AS 项, COUNT(*) AS 值
  FROM information_schema.columns
 WHERE table_schema = 'hanyou_brain' AND table_name = 'poi'
   AND column_name IN ('address', 'phone', 'detail', 'warning_threshold')
UNION ALL
SELECT 'poi_comment 列数', COUNT(*)
  FROM information_schema.columns
 WHERE table_schema = 'hanyou_brain' AND table_name = 'poi_comment'
UNION ALL
SELECT 'poi_comment CHECK 约束数', COUNT(*)
  FROM information_schema.table_constraints
 WHERE table_schema = 'hanyou_brain' AND table_name = 'poi_comment'
   AND constraint_type = 'CHECK';

-- 核对（期望 4 / 8 / 2）：
--   SELECT COUNT(*) FROM poi_comment;  -- 初始为 0
