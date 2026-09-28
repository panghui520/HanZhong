-- ============================================================
-- M10 资源管理：让运营新建的资源不在重启时被数据包导入冲掉
--
-- 问题：poi / experience / product 三张表都带 city_code，而
-- CityPackImporter 每次启动会按 city_code **全量删除重灌**
-- （见 CityPackImporter.run() 里连续七条 delete）。
-- 于是"运营在管理端新增一个景点"这件事在后端重启后必然消失 ——
-- 这不是 bug，是导入器的设计：数据包是权威来源，库只是它的投影。
--
-- 解法：给三张表各加一列 source，把"数据包导入的"与"运营新建的"分开：
--   PACK   数据包导入（citypack/<city>/*.json），每次启动重建
--   ADMIN  运营在管理端新建，导入器不碰
-- 导入器的删除条件随之从 `city_code = ?` 收窄为
-- `city_code = ? AND source = 'PACK'`。
--
-- ★ 对现有数据包行为**完全不变**：V1/V2 建的库里每一行都是 PACK，
--   收窄后的删除条件与收窄前删的是同一批行。
--
-- ⚠️ 另有两张表也有叫 source 的列，语义各不相同，别混：
--   poi_image.source     UPLOAD / SEED          —— 这张图是谁传的
--   trip_checkin.source  REAL / SIM / DIVERSION —— 这次到访怎么来的
--   本文件的 source      PACK / ADMIN           —— 这条资源是谁建的
--   三者都是"这条记录从哪来"，共用一个列名是项目既有习惯（见 V4 / V10）。
--
-- 执行：mysql -uroot -p --default-character-set=utf8mb4 < db/V11__m10_resource_admin.sql
--
-- ✅ 幂等：加列走 hanyou_add_column 过程（MySQL 8 的 ADD COLUMN 没有
--   IF NOT EXISTS，只能先查 information_schema 再动态执行）；
--   回填 UPDATE 带 WHERE，重跑不会改动已经是 PACK/ADMIN 的行。
--
-- ⚠️ 幂等的代价：今后这三张表再加字段，仍然要**新开 V 文件写 ALTER**，
--   不要回来改本文件 —— 在本机改了不生效，只会让新旧环境结构不一致。
-- ============================================================

USE hanyou_brain;

-- 与 V6 里同名的过程：先 DROP 再建，保证本文件能反复执行
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
-- 加列。默认 'PACK' 而不是 NULL：
-- 导入器与游客端查询都只认 'PACK'/'ADMIN' 两个值，多一个 NULL 状态
-- 就多一处"这个值到底算什么"的分支。NOT NULL + 默认值让新插入的行
-- 天然落在一侧，不需要每个写入点都记得赋值。
--
-- ⚠️ 两个写法上的坑（都实测踩过）：
--   1. definition 里**必须自带列名**。上面那个过程的 CONCAT 只拼了
--      `ADD COLUMN ` + definition，没有单独拼 p_column —— 所以传
--      'VARCHAR(16) NOT NULL ...' 会拼出 `ADD COLUMN VARCHAR(16) ...`，
--      缺列名，报 1064。p_column 那个参数只用于 information_schema 判重。
--      （V6 的调用也是这么写的：'pay_deadline DATETIME NULL COMMENT ...'。）
--   2. 用**单引号 + 内部单引号双写**，不用双引号包整个 definition。
--      双引号在本机 sql_mode 下虽然能用，但一旦哪天开了 ANSI_QUOTES
--      就会被当成标识符。V6 用的是单引号写法，这里跟着它。
-- ------------------------------------------------------------
CALL hanyou_add_column('poi', 'source',
  'source VARCHAR(16) NOT NULL DEFAULT ''PACK'' COMMENT ''PACK 数据包导入 / ADMIN 运营新建；导入器只重建 PACK 行''');

CALL hanyou_add_column('experience', 'source',
  'source VARCHAR(16) NOT NULL DEFAULT ''PACK'' COMMENT ''PACK 数据包导入 / ADMIN 运营新建；导入器只重建 PACK 行''');

CALL hanyou_add_column('product', 'source',
  'source VARCHAR(16) NOT NULL DEFAULT ''PACK'' COMMENT ''PACK 数据包导入 / ADMIN 运营新建；导入器只重建 PACK 行''');

-- ------------------------------------------------------------
-- 回填。ALTER 给已存在的行填了默认值，理论上这一步什么都不做；
-- 但"理论上"不该是唯一保障：手工插库、或从更早的备份恢复都可能留下 NULL，
-- 而一条 source 为 NULL 的资源在收窄后的删除条件里既不算 PACK 也不算 ADMIN
-- —— 它会永远留在库里且不被任何一侧认领。显式归到 PACK，与它的事实来源一致。
-- ------------------------------------------------------------
UPDATE poi        SET source = 'PACK' WHERE source IS NULL OR source = '';
UPDATE experience SET source = 'PACK' WHERE source IS NULL OR source = '';
UPDATE product    SET source = 'PACK' WHERE source IS NULL OR source = '';

DROP PROCEDURE IF EXISTS hanyou_add_column;

-- 核对（都应为 0）：
--   SELECT COUNT(*) FROM poi        WHERE source NOT IN ('PACK','ADMIN');
--   SELECT COUNT(*) FROM experience WHERE source NOT IN ('PACK','ADMIN');
--   SELECT COUNT(*) FROM product    WHERE source NOT IN ('PACK','ADMIN');
-- 运营新建的资源（重启后应仍在）：
--   SELECT id, name, source FROM poi WHERE source = 'ADMIN';
