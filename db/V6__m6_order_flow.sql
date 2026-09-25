-- ============================================================
-- M6 订单状态流转（把「下单 → 发货」扩展成完整电商闭环）
--
-- 本文件做四件事：
--   ① 给 orders 补 12 个字段（支付、物流、收货、取消、退款）
--   ② 修正 status 列的注释与默认值（两态 -> 七态）
--   ③ 把历史订单从旧状态迁到新状态
--   ④ 新建 order_review 评价表
--
-- 执行：mysql -uroot -p --default-character-set=utf8mb4 < db/V6__m6_order_flow.sql
--
-- ✅ 本脚本**幂等**，可以反复执行，不会丢数据：
--   · 加字段走一个临时存储过程，先查 information_schema 再决定加不加；
--   · MODIFY / UPDATE 重复执行结果一致；
--   · 建表用 CREATE TABLE IF NOT EXISTS。
--
-- ⚠️ 为什么必须用存储过程，不能直接写 ALTER TABLE ... ADD COLUMN：
--   MySQL 8 **不支持** `ADD COLUMN IF NOT EXISTS`（那是 MariaDB 的语法）。
--   直接写 ALTER，第二次执行会报 `Duplicate column name` 而中断，
--   后面的语句就不会跑了 —— 在"整个文件一起执行"的场景下，
--   一个报错会让剩下的一半静默不执行，比报错本身更危险。
--   所以这里用 information_schema 判断后再动态执行。
--   存储过程用完即删，不会留在库里。
--
-- ⚠️ 这与 V1–V5 改成幂等是同一件事的两面：
--   V1–V5 现在用 CREATE TABLE IF NOT EXISTS，重跑不会再清数据，
--   但**代价是结构变更不会再自动生效** —— 已经存在的表只会被跳过。
--   所以「给已有的表加字段」从此只有一条路：新开一个 V 文件写 ALTER。
--   本文件就是这条路的第一个例子。
-- ============================================================

USE hanyou_brain;

-- ------------------------------------------------------------
-- 幂等加列的辅助过程（用完即删）
-- ------------------------------------------------------------
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
-- 支付：下单即进入待付款，30 分钟内不付款就自动取消
--
-- pay_deadline 是"截止时刻"而不是"剩余秒数"：
--   存剩余秒数的话，服务重启或定时任务晚跑一会儿，倒计时就不准了。
--   存绝对时刻，任何时刻拿 NOW() 一比就知道过期没有。
-- ------------------------------------------------------------
CALL hanyou_add_column('orders', 'pay_deadline',
  'pay_deadline DATETIME NULL COMMENT ''支付截止时刻 = 下单时刻 + 30 分钟；未付款的订单超过它即自动取消''');
CALL hanyou_add_column('orders', 'paid_at',
  'paid_at DATETIME NULL COMMENT ''付款时刻。本项目不接真实支付，"付款"是一个按钮''');

-- ------------------------------------------------------------
-- 物流：管理员发货时必须填这三项
-- ------------------------------------------------------------
CALL hanyou_add_column('orders', 'carrier',
  'carrier VARCHAR(32) NULL COMMENT ''快递公司，如 顺丰速运。发货时由管理员填写''');
CALL hanyou_add_column('orders', 'eta_days',
  'eta_days INT NULL COMMENT ''预计到达天数，发货时由管理员填写''');
CALL hanyou_add_column('orders', 'tracking_no',
  'tracking_no VARCHAR(64) NULL COMMENT ''快递单号，发货时由管理员填写''');

-- ------------------------------------------------------------
-- 收货
-- ------------------------------------------------------------
CALL hanyou_add_column('orders', 'received_at',
  'received_at DATETIME NULL COMMENT ''确认收货时刻。只有已发货订单能确认收货''');

-- ------------------------------------------------------------
-- 取消
--
-- cancel_reason 区分"超时自动取消"与"用户主动取消"：
--   两者都是 CANCELLED，但运营看报表时要能分开统计。
--   超时取消率高说明支付转化有问题，主动取消多说明商品或价格有问题。
-- ------------------------------------------------------------
CALL hanyou_add_column('orders', 'cancelled_at',
  'cancelled_at DATETIME NULL COMMENT ''取消时刻''');
CALL hanyou_add_column('orders', 'cancel_reason',
  'cancel_reason VARCHAR(32) NULL COMMENT ''TIMEOUT 超时未付自动取消 / USER 用户主动取消''');

-- ------------------------------------------------------------
-- 退款
--
-- 只用一个 status 表达"退款中"，不另设 refund_status 列 ——
--   两列表达同一件事必然有一天对不上（改了 status 忘了改 refund_status）。
--
-- status_before_refund 是**回退用**的：管理员拒绝退款时，
--   订单要回到申请之前的状态（可能是待发货，也可能是已发货），
--   不记下来就回不去了。
--
-- 退款流程：用户申请 -> status=REFUND_REQUESTED（原状态存入 prev）
--           管理员同意 -> status=REFUNDED（终态）
--           管理员拒绝 -> status=prev（并保留拒绝理由，前端可展示）
-- ------------------------------------------------------------
CALL hanyou_add_column('orders', 'refund_reason',
  'refund_reason VARCHAR(255) NULL COMMENT ''买家填写的退款原因''');
CALL hanyou_add_column('orders', 'refund_reply',
  'refund_reply VARCHAR(255) NULL COMMENT ''管理员处理退款时的回复/拒绝理由''');
CALL hanyou_add_column('orders', 'refund_at',
  'refund_at DATETIME NULL COMMENT ''买家申请退款的时刻''');
CALL hanyou_add_column('orders', 'refund_handled_at',
  'refund_handled_at DATETIME NULL COMMENT ''管理员处理退款的时刻''');
CALL hanyou_add_column('orders', 'status_before_refund',
  'status_before_refund VARCHAR(24) NULL COMMENT ''申请退款前的状态，拒绝退款时回退到这里''');

DROP PROCEDURE IF EXISTS hanyou_add_column;

-- ------------------------------------------------------------
-- 修正 status 列
--
-- 原来的定义是 VARCHAR(16) DEFAULT 'PENDING'，只有两个取值。
-- 现在最长的是 PENDING_SHIPMENT / REFUND_REQUESTED（各 16 字符），
-- 16 刚好放得下但一点余量都没有，扩到 24。
--
-- MODIFY 是幂等的：重复执行结果完全一样，不会丢数据。
-- ------------------------------------------------------------
ALTER TABLE orders MODIFY COLUMN status VARCHAR(24) NOT NULL DEFAULT 'PENDING_PAYMENT'
  COMMENT 'PENDING_PAYMENT 待付款 / PENDING_SHIPMENT 待发货 / SHIPPED 已发货 / COMPLETED 已完成 / CANCELLED 已取消 / REFUND_REQUESTED 退款中 / REFUNDED 已退款';

-- ------------------------------------------------------------
-- 迁移历史订单
--
-- 旧流程是"下单即待发货"（没有支付环节），旧值 PENDING 的语义
-- 就是"已下单、等发货"，正好等于新的 PENDING_SHIPMENT。
-- 不迁的话，这些订单在新前端里会因为没有匹配的状态而显示不出来。
--
-- 幂等：第二次执行时已经没有 status='PENDING' 的行了。
-- ------------------------------------------------------------
UPDATE orders SET status = 'PENDING_SHIPMENT' WHERE status = 'PENDING';

-- ------------------------------------------------------------
-- 待付款超时扫描的索引
--
-- 定时任务每秒/每分钟跑一次 `WHERE status='PENDING_PAYMENT' AND pay_deadline < NOW()`，
-- 已有的 idx_status_created 前缀是 status，能命中，但第二列是 created_at
-- 而不是 pay_deadline，所以要额外回表过滤。这个索引直接命中两列。
-- ------------------------------------------------------------
SET @exists = (
  SELECT COUNT(*) FROM information_schema.STATISTICS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'orders'
     AND INDEX_NAME = 'idx_status_pay_deadline'
);
SET @ddl = IF(@exists = 0,
  'ALTER TABLE orders ADD KEY idx_status_pay_deadline (status, pay_deadline)',
  'SELECT ''idx_status_pay_deadline 已存在，跳过'' AS note');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ------------------------------------------------------------
-- 订单评价
--
-- 一单一评（uk_order）：同一笔订单重复提交评价没有意义，
--   而且有了唯一键，服务端就不用先查再插（那种写法并发下会漏）。
--
-- images 存 JSON 数组字符串（相对路径），最多 3 张 ——
--   单独建 order_review_image 表当然更"规范"，但评价图片只有
--   "一次性写入、整组读出"这一种访问方式，永远不需要按图反查评价，
--   拆表只会多一次 JOIN。图片文件本身在磁盘上（复用 M9 的存储），
--   这里只存路径，与 poi_image 的做法一致。
--
-- 只有 COMPLETED 状态能评价：没收到货就评价是刷评。
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS order_review (
  id         BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
  order_id   BIGINT        NOT NULL COMMENT '-> orders.id，一单一评',
  user_id    BIGINT        NOT NULL COMMENT '-> app_user.id，评价人（冗余，避免按用户查评价时再连订单表）',
  rating     TINYINT       NOT NULL COMMENT '星级 1..5',
  content    VARCHAR(1000)          COMMENT '评价文字，可空（只打星也算评价）',
  images     VARCHAR(1000)          COMMENT '图片相对路径的 JSON 数组字符串，最多 3 张，如 ["a.jpg","b.jpg"]',
  created_at DATETIME      DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  -- 一单一评，同时让"这笔订单评过没有"变成一次主键级查找
  UNIQUE KEY uk_order (order_id),
  -- 商品详情页将来要按商品聚合评价，先按用户+时间建索引（当前只用到用户维度）
  KEY idx_user_created (user_id, created_at)
) ENGINE=InnoDB COMMENT='订单评价（运行期数据，不随城市数据包重灌）';
