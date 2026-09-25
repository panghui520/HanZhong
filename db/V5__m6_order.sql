-- ============================================================
-- M6 消费与离境复购（本轮只做「下单 → 发货」这一段）
--
-- 与 M8 / M9 同源：**三张表都不带 city_code**。
-- CityPackImporter 每次启动会按 city_code 全量删除重灌业务表，
-- 而购物车与订单是**运行期数据** —— 用户买过的东西不能因为重启服务就没了。
-- 不挂 city_code，导入器就永远碰不到它们。
--
-- ============================================================
-- ⚠️⚠️ 危险：本脚本会**先 DROP 再 CREATE**，不是幂等的建表语句。
-- 重跑一次 = 全部购物车与订单**清空且无法恢复**。
-- 演示机 / 生产环境上执行前，务必先确认是空库，或先备份：
--   mysqldump -uroot -p hanyou_brain cart_item orders order_item > order_backup.sql
-- （原因与 V3 顶部那段说明相同，可对照阅读。）
-- ============================================================
--
-- 为什么不做「库存扣减」：
--   商品的 `product` 表**带 city_code、会被导入器重灌**。若下单时把
--   `product.stock` 减掉，重启一次库存就悄悄回到数据包里的初始值 ——
--   卖了 3 件又变回原样，这种"看起来生效、实际不持久"的写入比不做更糟。
--   所以本轮的做法是：**下单时校验库存是否充足，但不写回 stock**。
--   真正的库存账需要一张不带 city_code 的流水表（记 出入库流水，
--   剩余 = 数据包库存 - 累计售出），留作 M6 完整版的扩展点。
--
-- 为什么收货地址直接存在订单行上、不建地址簿表：
--   1) 地址簿是"复购很多次"才划算的设计，本项目一个用户大概率只买一次，
--      建了就是空表；
--   2) 更关键的是**快照语义**：订单上的地址必须是下单那一刻的地址。
--      若订单引用地址簿的 id，用户后来改了收货地址，历史订单显示的地址
--      会跟着变 —— 那是错账。所以这里存的是当时的副本，不是引用。
-- ============================================================

USE hanyou_brain;

-- ------------------------------------------------------------
-- 购物车
--
-- 一个用户 + 一个商品 = 一行。加购是数量累加，不是插新行，
-- 所以 (user_id, product_id) 建唯一键。没有这个唯一键的话，
-- 同一件东西加购三次会出现三行，页面上看起来是三个不同商品。
--
-- 刻意**不存价格**：价格随时可能变，购物车显示的价格应该永远是
-- "现在的价格"。价格只在**下单那一刻**被快照进 order_item。
-- ------------------------------------------------------------
DROP TABLE IF EXISTS cart_item;
CREATE TABLE cart_item (
  id         BIGINT   NOT NULL AUTO_INCREMENT COMMENT '主键',
  user_id    BIGINT   NOT NULL COMMENT '-> app_user.id。购物车必须登录才有，不做匿名车',
  product_id VARCHAR(32) NOT NULL COMMENT '-> product.id',
  quantity   INT      NOT NULL DEFAULT 1 COMMENT '数量，最小 1',
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_user_product (user_id, product_id),
  KEY idx_user (user_id)
) ENGINE=InnoDB COMMENT='购物车（登录用户的运行期数据，不随城市数据包重灌）';

-- ------------------------------------------------------------
-- 订单主表
--
-- status 只有两个值，是刻意的：
--   PENDING 待发货  -> 用户提交后的初始状态
--   SHIPPED 已发货  -> 运营点一下「标记已发货」
-- 不做支付状态、不做物流状态、不做退款状态。理由见验收记录：
-- 那些状态需要真实的支付与物流通道才成立，做了就是摆样子。
--
-- 金额字段用 DECIMAL 不用 DOUBLE：DOUBLE 是二进制浮点，
-- 0.1 + 0.2 != 0.3，金额算错在答辩现场被问一句就下不来台。
--
-- receiver_* 三列是**下单时的快照**，不是引用地址簿。原因见文件头。
--
-- order_no 是对外可见的单号（HY + 日期 + 随机段），
-- 主键 id 只在内部用。让用户念一串自增数字很容易和别的系统撞号。
-- ------------------------------------------------------------
DROP TABLE IF EXISTS orders;
CREATE TABLE orders (
  id               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  order_no         VARCHAR(32)  NOT NULL COMMENT '对外单号，形如 HY20260925004217',
  user_id          BIGINT       NOT NULL COMMENT '-> app_user.id，下单人',
  status           VARCHAR(16)  NOT NULL DEFAULT 'PENDING'
                   COMMENT 'PENDING 待发货 / SHIPPED 已发货。只有这两个状态，见上方说明',
  item_count       INT          NOT NULL DEFAULT 0 COMMENT '商品件数合计，列表页直接显示，避免再查子表',
  total_amount     DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT '订单总额，由服务端按 product 表重算，不信前端',
  receiver_name    VARCHAR(64)  NOT NULL COMMENT '收货人姓名（下单时快照）',
  receiver_phone   VARCHAR(32)  NOT NULL COMMENT '联系电话（下单时快照）',
  receiver_address VARCHAR(255) NOT NULL COMMENT '收货地址（下单时快照）',
  remark           VARCHAR(255)          COMMENT '买家备注',
  channel          VARCHAR(16)  NOT NULL DEFAULT 'REPURCHASE'
                   COMMENT 'TRIP 到访当场带走 / REPURCHASE 离境复购。本轮只走 REPURCHASE，列先留着',
  shipped_at       DATETIME              COMMENT '发货时间，未发货为 NULL',
  created_at       DATETIME     DEFAULT CURRENT_TIMESTAMP,
  updated_at       DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_order_no (order_no),
  -- 运营端按状态筛选（待发货排前面）+ 用户端按时间倒序，这一个索引都能命中
  KEY idx_status_created (status, created_at),
  KEY idx_user_created (user_id, created_at)
) ENGINE=InnoDB COMMENT='订单主表（运行期数据，不随城市数据包重灌）';

-- ------------------------------------------------------------
-- 订单明细
--
-- ★ 这张表是本项目"不是电商"的证据所在。
--
-- experience_id / poi_id 两列**必须存**，哪怕 order_item 已经能通过
-- product_id 反查回去。理由有两条：
--   1) 产品可能下架、甚至整条 product 记录被换城市的数据包清掉，
--      历史订单仍然要说清"这件东西来自哪次体验 / 哪个产地"；
--   2) 这是本项目的第一条设计红线——农产品必须挂靠体验或产地。
--      把挂靠关系落在订单行上，"消费链离境延伸"才是可核对的数据事实，
--      而不是一句宣传语。
--
-- 同理 product_name / spec / unit_price 都是**下单时的快照**：
-- 产品改名、调价之后，历史订单显示的必须是当时的信息。
--
-- 刻意不存 image_path：图片是可变的展示物，不该进订单快照；
-- 需要显示时按 product_id 现查 M9 的配图。
-- ------------------------------------------------------------
DROP TABLE IF EXISTS order_item;
CREATE TABLE order_item (
  id              BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
  order_id        BIGINT        NOT NULL COMMENT '-> orders.id',
  product_id      VARCHAR(32)   NOT NULL COMMENT '-> product.id',

  -- 体验锚点（红线要求，可为 NULL 的只有 poi_id 那一侧，但至少有一个非空）
  experience_id   VARCHAR(32)            COMMENT '-> experience.id，下单时快照',
  experience_name VARCHAR(128)           COMMENT '体验名快照',
  poi_id          VARCHAR(32)            COMMENT '-> poi.id 产地，下单时快照',
  poi_name        VARCHAR(128)           COMMENT '产地乡村名快照',

  product_name    VARCHAR(128)  NOT NULL COMMENT '商品名快照',
  spec            VARCHAR(64)            COMMENT '规格快照',
  unit_price      DECIMAL(10,2) NOT NULL COMMENT '单价快照，服务端按 product 表取，不信前端',
  quantity        INT           NOT NULL COMMENT '数量',
  subtotal        DECIMAL(10,2) NOT NULL COMMENT '小计 = unit_price * quantity，服务端算',
  created_at      DATETIME      DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_order (order_id)
) ENGINE=InnoDB COMMENT='订单明细（含体验/产地锚点，是"不是电商"的证据）';
