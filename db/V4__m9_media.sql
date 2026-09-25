-- ============================================================
-- M9 媒体与配图管理
--
-- 与 M8 同样是**横切关注点**：游客端的列表页、详情页、首页轮播都要用它，
-- 但它不属于 M1–M7 任何一个纵切业务模块，因此独立编号。
--
-- 关键设计（与 M8 同源）：这两张表都**没有 city_code**。
-- CityPackImporter 每次启动会按 city_code 全量删除重灌业务表，
-- 而用户上传的图片是**运行期数据**——运营传了 40 张实拍图，
-- 不能因为重启一次服务就全没了。不挂 city_code，导入器就永远碰不到它们。
--
-- 另一个关键点：**图片文件存在磁盘上，数据库只存相对路径**。
-- 不把二进制塞进数据库的理由很直接：
--   1) 备份与迁移时，几十 MB 的 BLOB 会让 mysqldump 变得很笨重；
--   2) 静态文件可以交给 Web 容器直接吐出去（见 MediaStorageService 与 WebConfig），
--      不需要经过 JDBC 与结果集序列化；
--   3) 换图时只改一行路径，不产生大事务。
--
-- 执行：mysql -uroot -p --default-character-set=utf8mb4 < db/V4__m9_media.sql
--
-- ⚠️⚠️ 危险：本脚本会**先 DROP 再 CREATE**，不是幂等的建表语句。
-- 重跑一次 = 运营上传的全部景点配图记录、轮播帧记录**全部清空**。
-- 注意：**磁盘上的图片文件不会跟着删**（脚本管不到文件系统），
-- 结果是磁盘留下一堆没人引用的孤儿文件，页面上所有配图一起消失。
--
-- 演示机 / 生产环境上执行前，务必先确认：
--   ① 这是一套空库，本来就没有配图要保；
--   ② 或先备份：
--        mysqldump -uroot -p hanyou_brain poi_image site_banner > media_backup.sql
--
-- （V1 / V2 重跑同样会 DROP，但那两张表的数据每次启动都会被
--   CityPackImporter 从 citypack/ 重灌，所以没有额外损失。详见 V3 顶部说明。）
-- ============================================================

USE hanyou_brain;

-- ------------------------------------------------------------
-- 景点配图
--
-- 一个景点可以有多张（列表页取封面，详情页可做成图集），所以不设唯一键。
-- sort_order 决定展示顺序，is_cover 标记封面。
--
-- 为什么要显式标 is_cover 而不是"取 sort_order 最小的那张"：
-- 运营可能想把第 3 张当封面，同时又要它排在第 3 位。两件事分开表达更清楚，
-- 也避免了"调整顺序就把封面改掉了"这种意外。
-- 服务层保证：同一 poi_id 下至多一张 is_cover=1（换封面时先把旧的清掉）。
--
-- source 记录来源：UPLOAD 为运营上传。留这个字段是为了将来能区分
-- "系统预置图"与"人工替换图"，做数据清理时有据可依。
-- ------------------------------------------------------------
DROP TABLE IF EXISTS poi_image;
CREATE TABLE poi_image (
  id         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  poi_id     VARCHAR(32)  NOT NULL COMMENT '所属资源点，对应 poi.id',
  image_path VARCHAR(255) NOT NULL
             COMMENT '相对 media-dir 的路径，如 poi/P-SCE-001/ab12cd34.jpg。不存绝对路径',
  alt_text   VARCHAR(128)          COMMENT '无障碍替代文本，兼作运营备注',
  sort_order INT          NOT NULL DEFAULT 0 COMMENT '展示顺序，小的在前',
  is_cover   TINYINT      NOT NULL DEFAULT 0 COMMENT '是否封面：0 否 / 1 是（列表页与卡片用封面）',
  source     VARCHAR(16)  NOT NULL DEFAULT 'UPLOAD' COMMENT 'UPLOAD 运营上传 / SEED 系统预置',
  created_at DATETIME     DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  -- 列表按 (poi_id, sort_order) 取，这个索引直接命中；同时加速"取封面"
  KEY idx_poi_sort (poi_id, sort_order)
) ENGINE=InnoDB COMMENT='景点配图（文件在磁盘，此处只存路径）';

-- ------------------------------------------------------------
-- 首页轮播图
--
-- 初始种入当前代码里写死的那 4 帧（见 web/src/components/HeroCarousel.vue）。
--
-- 种数据的用意：运营进到管理页时看到的是"现在线上是什么样"，
-- 然后直接对某一张点"替换图片"即可，而不是面对一张空表从零建 4 条。
-- 这 4 行的 image_path 刻意留空 —— 前端在 image_path 为空时回落到
-- SceneArt 手写 SVG（scene 字段），所以不传图也不会开天窗。
--
-- scene 是兜底画面：手写 SVG 的 7 个变体之一。传了图就用图，
-- 没传图就用它。这样"离线可演示"这条底线在任何状态下都成立。
-- ------------------------------------------------------------
DROP TABLE IF EXISTS site_banner;
CREATE TABLE site_banner (
  id         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  sort_order INT          NOT NULL DEFAULT 0 COMMENT '轮播顺序，小的在前',
  image_path VARCHAR(255)          COMMENT '相对 media-dir 的路径；为空则回落到 scene 的手写 SVG',
  scene      VARCHAR(32)  NOT NULL DEFAULT 'qinling'
             COMMENT '兜底画面：qinling/terrace/rapeseed/ancient/river/hanjiang/hantai',
  eyebrow    VARCHAR(64)           COMMENT '标题上方的小字',
  title      VARCHAR(128) NOT NULL COMMENT '主标题',
  subtitle   VARCHAR(255)          COMMENT '副标题',
  description VARCHAR(512)         COMMENT '说明文字',
  link_url   VARCHAR(255)          COMMENT '点击跳转地址（站内路径，如 /explore）',
  cta        VARCHAR(32)           COMMENT '按钮文案',
  enabled    TINYINT      NOT NULL DEFAULT 1 COMMENT '是否启用：0 隐藏 / 1 显示',
  created_at DATETIME     DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_sort (sort_order)
) ENGINE=InnoDB COMMENT='首页轮播图（文案与图片均可由运营维护）';

-- 种入当前的 4 帧。image_path 全部留空 => 前端继续用手写 SVG 渲染，
-- 运营上传图片后自动切换为实拍图。
INSERT INTO site_banner
  (sort_order, scene, eyebrow, title, subtitle, description, link_url, cta, enabled)
VALUES
  (1, 'qinling', '智慧文旅 · 乡村振兴', '汉游智脑', '发现汉中，也发现乡村的新可能',
   '当景区高位运行，让客流顺着山谷流向乡村。AI 参与的规划、分流与运营，把一次到访延展成一条持续消费链。',
   '/explore', '探索汉中', 1),
  (2, 'hanjiang', '汉江之畔 · 一城文脉', '一江汉水，两岸春秋', '从石门栈道到汉家发祥地',
   '汉中是汉文化的发祥地。我们把散落的景区、街巷、村镇连成可规划的动线，让每一次停留都落在有故事的地方。',
   '/assistant', '问问智脑', 1),
  (3, 'rapeseed', '油菜花海 · 乡村体验', '把春天种在田里', '花期之外，乡村仍然值得来',
   '油菜花、茶园、梯田不只是风景，也是可预约的乡村体验。游客走进来，收益留在村里。',
   '/explore', '乡村体验', 1),
  (4, 'hantai', '古汉台 · 东方人文', '檐下百年，一眼千载', '在古建与花树之间读懂汉中',
   '以东方人文为底色的视觉与内容体系，让文化资源可阅读、可推荐、可被 AI 准确引用。',
   '/assistant', '了解文脉', 1);
