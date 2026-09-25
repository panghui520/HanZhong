-- ============================================================
-- 重置运行期数据（可选脚本，需要时手动执行）
--
-- 为什么单独有这个文件：
--   V1–V6 现在都是幂等的，重跑**不会再清数据**（这正是我们要的）。
--   但"演示前想清空测试订单"这个需求仍然存在，只是它不该和
--   建表脚本混在一起 —— 建表和清数据是两件事，混在一起的结果
--   就是"想加个字段，顺手把订单删了"。所以拆出来，需要时手动跑。
--
-- 会清空：
--   cart_item        购物车
--   order_item       订单明细
--   order_review     订单评价
--   orders           订单
--   auth_email_code  历史验证码（5 分钟就过期，留着没有意义）
--
-- 刻意**不动**：
--   app_user         你注册的账号、改过的密码（清了就得重新注册）
--   poi_image        运营上传的景点配图（重新传 40 张很痛苦）
--   site_banner      轮播帧与文案
--   city_profile / poi / poi_relation / experience / product /
--   product_category  这些由 CityPackImporter 从 citypack/ 重灌，
--                     不需要在这里动，动了下一次启动也会被覆盖回来
--
-- 执行：mysql -uroot -p --default-character-set=utf8mb4 < db/reset-runtime-data.sql
--
-- ⚠️ 订单删掉就没了。要留档先导出：
--   mysqldump -uroot -p hanyou_brain orders order_item order_review > orders_backup.sql
-- ============================================================

USE hanyou_brain;

-- 顺序：先子表后主表。
-- 本项目**不建外键**（见 V1 顶部约定），所以数据库不会拦你删错顺序，
-- 但留下孤儿明细只会让下次查数据的人困惑。
DELETE FROM cart_item;
DELETE FROM order_item;
DELETE FROM order_review;
DELETE FROM orders;
DELETE FROM auth_email_code;

-- 自增 id 复位。这不是删数据，只是把计数器归零 ——
-- 演示时第一笔订单的 id 从 1 开始，看起来比从 137 开始干净。
-- （表里还有行时这条会被 MySQL 忽略，属正常。）
ALTER TABLE cart_item    AUTO_INCREMENT = 1;
ALTER TABLE order_item   AUTO_INCREMENT = 1;
ALTER TABLE order_review AUTO_INCREMENT = 1;
ALTER TABLE orders       AUTO_INCREMENT = 1;

-- 跑完打印一份对账，确认该清的清了、该留的还在
SELECT '购物车'   AS 表, COUNT(*) AS 剩余 FROM cart_item
UNION ALL SELECT '订单',        COUNT(*) FROM orders
UNION ALL SELECT '订单明细',    COUNT(*) FROM order_item
UNION ALL SELECT '订单评价',    COUNT(*) FROM order_review
UNION ALL SELECT '用户（保留）', COUNT(*) FROM app_user
UNION ALL SELECT '配图（保留）', COUNT(*) FROM poi_image
UNION ALL SELECT '轮播（保留）', COUNT(*) FROM site_banner;
