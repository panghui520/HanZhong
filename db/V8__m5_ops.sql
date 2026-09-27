-- ============================================================
-- M5 承载力与乡村分流：客流统计 / 规则配置 / 风险事件 / 工单
--
-- 本模块要回答的是管理端最实际的一个问题：
--   **「哪个景区挤了、旁边的村子空着，我该做什么」**
-- 在此之前管理端大屏读的是前端 mock（web/src/mock/ops.ts），
-- 数字由 POI id 现算 —— 能看，但改不动、也追不了责。本模块把
-- "判定"落到数据库与 Java 规则引擎上（确定性、可解释），
-- 把"解释与建议"留给 LLM（M7），两者不越界。
--
-- ------------------------------------------------------------
-- 四张表为什么有的带 city_code、有的不带（★ 最容易搞错的一处）
--
-- CityPackImporter 每次启动按 city_code **删除并重灌**业务表
-- （见 CityPackImporter.run()：product -> experience -> product_category
--   -> poi_relation -> poi -> city_profile）。判断依据只有一条：
--   **这张表的数据是不是"数据包的一部分"？**
--
--   poi_visit_stats  带   —— 它是仿真脚本按数据包生成的，属于数据包，
--                            换城市要跟着换，重启重灌正是期望行为。
--   risk_rule        不带 —— 系统级配置。运营把「超载阈值从 80% 调成 85%」
--                            之后重启服务就变回去，是 bug 不是特性。
--   risk_event       不带 —— 运行期产生的业务事实，重启不能丢。
--   work_order       不带 —— 同上，而且工单上还挂着处置人。
--
-- 与 M8 的 app_user、M6 的 orders 是同一条约定（见 V3 / V5 文件头）。
--
-- ------------------------------------------------------------
-- 阈值为什么放在表里而不是写死在 Java 常量里
--
-- 6 条规则的阈值是**运营口径**，不是技术参数。写死在代码里，
-- 每次调阈值都要改代码、重新编译、重新部署 —— 而这恰恰是运营
-- 最常动的一个数。放进 risk_rule 后，规则引擎每次扫描前读一遍配置，
-- 调阈值只改一行数据。代价是多一次查询，可以忽略。
--
-- 四个阈值列覆盖 6 条规则（用不到的留空），对照见下面 seed 段。
--
-- 执行：mysql -uroot -p < db/V8__m5_ops.sql
--
-- ✅ 本脚本**幂等**，可以反复执行：
--   · 建表一律 CREATE TABLE IF NOT EXISTS。
--   · risk_rule 的种子用 INSERT IGNORE —— 已存在的规则**不会被覆盖**，
--     所以运营改过的阈值不会被重跑脚本冲掉。
--
-- ⚠️ 幂等的代价（与 V3/V7 同一个坑）：**表结构变更不会再自动生效**。
--   今后要给这四张表加字段，必须**新开一个 V 文件写 ALTER TABLE**，
--   不要回来改本文件 —— 在本机改了也不生效，只会让新环境与旧环境结构不一致。
-- ============================================================

USE hanyou_brain;

-- ------------------------------------------------------------
-- 客流与经营日度统计（合成数据）
--
-- 粒度是「一个资源点 × 一天」，60 天。为什么是日粒度而不是小时：
-- 6 条规则要判的都是"当日"与"近 N 日"，没有一条需要小时级；
-- 存小时级会让行数变成 24 倍（42 × 60 × 24 = 6 万行），
-- 而多出来的信息没有任何一条规则会读。
--
-- 为什么容量占用率要**存**而不是每次用 visitors / capacity 现算：
--   capacity 是"设计承载"，会随数据包更新而变化。若每次现算，
--   历史某天的占用率会随着今天的 capacity 一起变 —— 那就是
--   "上个月的数据今天看又是另一个数"。存下来才是当时的口径。
--   导入时由导入器算好写入，见 CityPackImporter。
--
-- 评价与转化三列（review_count / experience_visits / purchases /
-- repurchases）放在同一张表里，是因为它们与客流**同粒度、同来源、
-- 同生命周期**，而且规则 2/5/6 都要把它们与客流放在一起算比值。
-- 拆成四张表会让每条规则都要做三次 JOIN，收益为零。
--
-- synthetic 恒为 1。列存在的意义是**让"这是仿真数据"写在数据里**，
-- 而不是只写在文档里 —— 方案第 17 节要求演示数据必须可辨认，
-- 评审问"这数据是真的吗"时，答案在表结构上就能看到。
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS poi_visit_stats (
  id                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  city_code         VARCHAR(32)  NOT NULL COMMENT '所属城市包（hanzhong）',
  poi_id            VARCHAR(64)  NOT NULL COMMENT '资源点，-> poi.id',
  stat_date         DATE         NOT NULL COMMENT '统计日期。由数据包的天数偏移在导入时物化，见 visit_stats.json 的 offset',

  visitors          INT          NOT NULL DEFAULT 0 COMMENT '当日到访人次',
  capacity_usage    DECIMAL(8,4) NOT NULL DEFAULT 0 COMMENT '当日承载占用率（>1 表示超载）。导入时算好存下，不现算',

  review_count      INT          NOT NULL DEFAULT 0 COMMENT '当日评价数。规则 2 的"样本"',
  negative_count    INT          NOT NULL DEFAULT 0 COMMENT '当日负面评价数。规则 2 用 negative_count/review_count',

  experience_visits INT          NOT NULL DEFAULT 0 COMMENT '当日参与体验人次。只有乡村点非零。规则 5 的分母',
  purchases         INT          NOT NULL DEFAULT 0 COMMENT '当日购买笔数。只有乡村点非零。规则 5 的分子',
  repurchases       INT          NOT NULL DEFAULT 0 COMMENT '当日复购笔数（purchases 中属于复购的部分）。规则 6 用',

  synthetic         TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '恒为 1：本表全部为基于规则的仿真数据，非真实统计',
  created_at        DATETIME     DEFAULT CURRENT_TIMESTAMP,

  PRIMARY KEY (id),
  -- 一个点一天一行。也是导入幂等的基础：重灌时按 city_code 整体删除，
  -- 但万一出现"删了一半"的异常，这个唯一键能挡住重复行。
  UNIQUE KEY uk_poi_date (city_code, poi_id, stat_date),
  -- 规则 3/4/6 都要"取某天全量"或"取某点近 N 日"，两个方向各一个索引
  KEY idx_city_date (city_code, stat_date),
  KEY idx_city_poi_date (city_code, poi_id, stat_date)
) ENGINE=InnoDB COMMENT='客流与经营日度统计（M5，合成数据）。带 city_code，随数据包重灌';

-- ------------------------------------------------------------
-- 风险规则配置
--
-- 6 条规则**不在这个表里定义逻辑** —— 逻辑在 Java 的 RuleEngine 里，
-- 因为规则要做的判断（比值、窗口、分组比较）不是一句 SQL 能表达的，
-- 硬塞进 metric_expr 只会得到一列没人敢改的字符串。
-- 本表负责的是**可调的那部分：阈值与开关**。
--
-- 四个阈值列与 6 条规则的对照（用不到的留空）：
--
--   rule_id             threshold   threshold_2  window_days  min_sample
--   OVERLOAD            0.80        1.00         —            —
--     一级 / 预警两档：>= threshold 预警，>= threshold_2 一级
--   REVIEW_SURGE        0.30        —            1            5
--     近 1 日负面率 > threshold 且评价数 >= min_sample
--   HEAT_JUMP           0.80        —            7            —
--     近 window_days 日环比增幅 > threshold
--   RURAL_IDLE          0.80        0.20         —            —
--     同区县景区均载 > threshold 且乡村均载 < threshold_2  ← 核心规则
--   LOW_CONVERSION      0.10        —            7            —
--     近 window_days 日 乡村 purchases/experience_visits < threshold
--   REPURCHASE_DECAY    0.40        —            30           —
--     近 window_days 日复购量环比下降 > threshold
--
-- ⚠️ RURAL_IDLE 的 0.80 / 0.20 就是方案第 15 节写的口径，没有放宽。
--   前端旧 mock 用的是 75/40，那是**把标准迁就数据**（旧仿真区间下
--   乡村承载永远取不到 20% 以下，核心规则一次都不会触发）。
--   正确做法是让数据覆盖到标准要求的区间，见 scripts/gen_synthetic.py。
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS risk_rule (
  id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  rule_id       VARCHAR(32)  NOT NULL COMMENT '稳定编码，与 Java 规则引擎里的常量一一对应',
  name          VARCHAR(64)  NOT NULL COMMENT '规则名（界面展示）',
  type          VARCHAR(32)  NOT NULL COMMENT '事件类型：OVERLOAD/REVIEW/HEAT/RURAL_IDLE/CONVERSION/REPURCHASE',

  threshold     DECIMAL(8,4) NOT NULL COMMENT '主阈值，含义随规则变，见文件头对照表',
  threshold_2   DECIMAL(8,4)          COMMENT '次阈值。只有 OVERLOAD（一级档）与 RURAL_IDLE（乡村上限）用到',
  window_days   INT                   COMMENT '统计窗口（天）。OVERLOAD / RURAL_IDLE 用"当日"，留空',
  min_sample    INT                   COMMENT '最小样本量。只有 REVIEW_SURGE 用到',

  action_type   VARCHAR(32)  NOT NULL COMMENT '命中后建议的动作：DIVERSION/EXPOSURE/SERVICE/MONITOR',
  level         VARCHAR(16)  NOT NULL COMMENT '默认等级：HIGH/MID',
  enabled       TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '是否启用。关掉一条规则不需要改代码',
  sort          INT          NOT NULL DEFAULT 0 COMMENT '展示顺序',
  created_at    DATETIME     DEFAULT CURRENT_TIMESTAMP,
  updated_at    DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

  PRIMARY KEY (id),
  UNIQUE KEY uk_rule_id (rule_id)
) ENGINE=InnoDB COMMENT='风险规则阈值配置（M5）。不带 city_code，是系统级配置';

-- ------------------------------------------------------------
-- 风险事件
--
-- 规则引擎一次扫描命中一条，就在这里落一行。
--
-- poi_name 是**快照**，不是冗余：资源点改名或下架后，历史事件
-- 仍要说得清当时说的是谁。与 M6 把挂靠关系快照到订单行上是同一个理由。
--
-- ⚠️ UNIQUE KEY (rule_id, poi_id, stat_date) 是**幂等的关键**：
--   规则引擎可以被反复触发（启动时一次、运营手动一次、定时一次），
--   没有这个唯一键就会把同一条风险重复落库，界面上出现 5 条
--   "兴汉胜境超载"。有了它，重复扫描是安全的 —— 引擎用
--   INSERT ... ON DUPLICATE KEY UPDATE 只刷新指标值，不新增行。
--
-- status 与 work_order_id 是工单闭环的两个端点：
--   OPEN    刚命中，还没处置
--   HANDLED 已转工单（work_order_id 非空）
--   CLOSED  工单已完结（工单处置结果回写）
-- 之所以把 work_order_id 直接放在事件上（而不是每次去工单表里查
-- "有没有指向我的工单"）：列表页要显示"这条风险建单了没有"，
-- 反查要 JOIN 且一条事件可能对应多张单，而当前业务一条事件只建一张单。
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS risk_event (
  id            BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
  rule_id       VARCHAR(32)   NOT NULL COMMENT '命中的规则，-> risk_rule.rule_id',
  poi_id        VARCHAR(64)   NOT NULL COMMENT '关联资源点。规则 4 关联的是区县的代表景区',
  poi_name      VARCHAR(128)  NOT NULL COMMENT '资源点名称快照。资源改名/下架后历史事件仍可读',
  district      VARCHAR(32)            COMMENT '区县。规则 4 的事件按区县聚合，列表要能按区县筛',
  stat_date     DATE          NOT NULL COMMENT '判定所依据的统计日期',

  level         VARCHAR(16)   NOT NULL COMMENT 'HIGH/MID',
  metric_value  DECIMAL(12,4) NOT NULL COMMENT '实际指标值（如承载率 1.105）',
  threshold     DECIMAL(12,4) NOT NULL COMMENT '命中时的阈值快照。阈值后来被调过，历史事件仍说得清当时的判据',

  title         VARCHAR(128)  NOT NULL COMMENT '事件标题（界面主文案）',
  detail        VARCHAR(512)  NOT NULL COMMENT '事件详情：把指标与阈值讲清楚，不含 LLM 生成内容',
  suggestion    VARCHAR(512)  NOT NULL COMMENT '建议动作。当前为规则内置文案，M7 接 LLM 归因后由模型补充',

  status        VARCHAR(16)   NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN 待处置 / HANDLED 已建单 / CLOSED 已闭环',
  work_order_id BIGINT                 COMMENT '已生成的工单，-> work_order.id。未建单时为空',
  created_at    DATETIME      DEFAULT CURRENT_TIMESTAMP,
  updated_at    DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

  PRIMARY KEY (id),
  UNIQUE KEY uk_rule_poi_date (rule_id, poi_id, stat_date),
  KEY idx_status_level (status, level),
  KEY idx_stat_date (stat_date)
) ENGINE=InnoDB COMMENT='风险事件（M5）。不带 city_code，是运行期数据';

-- ------------------------------------------------------------
-- 工单
--
-- 风险事件 → 一键建单 → 处置 → 反馈，是方案第 14 节"工单闭环"的落点。
--
-- code 形如 WO-20260927-001（日期 + 当日序号）：
--   与 M4 的 trip.code 同一个理由 —— 业务编码要写进界面与演示截图，
--   自增 id 看起来像测试数据，而 WO-20260927-001 一眼读得出哪天建的。
--
-- suggestion 与 risk_event.suggestion 的区别：
--   事件上的那份是"为什么报这个警"的建议（规则内置），
--   工单上的这份是"这张单要怎么办"（建单时可被运营改写）。
--   两者一开始相同，但工单会被运营编辑，所以必须分开存。
--
-- assignee 是**自由文本**而不是指向 app_user 的外键：
--   当前没有"派单给某个账号"的界面，运营自己填名字即可；
--   等真要做人员管理时再改成 user_id，那时是一次明确的迁移。
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS work_order (
  id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  code          VARCHAR(64)  NOT NULL COMMENT '业务编码，形如 WO-20260927-001',
  risk_event_id BIGINT                COMMENT '来源风险事件，-> risk_event.id。允许为空：以后可能有非风险来源的工单',

  title         VARCHAR(128) NOT NULL COMMENT '工单标题',
  type          VARCHAR(32)  NOT NULL COMMENT 'DIVERSION 分流 / EXPOSURE 曝光 / SERVICE 服务 / MONITOR 关注',
  level         VARCHAR(16)  NOT NULL COMMENT '继承自事件等级',
  status        VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING 待处置 / PROCESSING 处置中 / DONE 已完结',

  assignee      VARCHAR(64)           COMMENT '处置人（自由文本，当前无人员管理）',
  suggestion    VARCHAR(512)          COMMENT '处置建议。建单时可被运营改写',
  result        VARCHAR(512)          COMMENT '处置反馈。完结时填写',
  handled_at    DATETIME              COMMENT '完结时间',

  created_at    DATETIME     DEFAULT CURRENT_TIMESTAMP,
  updated_at    DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

  PRIMARY KEY (id),
  UNIQUE KEY uk_code (code),
  KEY idx_status (status),
  KEY idx_event (risk_event_id)
) ENGINE=InnoDB COMMENT='处置工单（M5）。不带 city_code，是运行期数据';

-- ------------------------------------------------------------
-- 种子：6 条规则
--
-- 用 INSERT IGNORE 而不是 ON DUPLICATE KEY UPDATE：
-- **已存在的规则不会被覆盖**。这样重跑本脚本不会把运营调过的阈值
-- 冲回默认值 —— 那是这套配置表存在的全部意义。
-- 代价是：以后要改默认阈值，得手动 UPDATE，不能指望重跑脚本。
-- ------------------------------------------------------------
INSERT IGNORE INTO risk_rule
  (rule_id, name, type, threshold, threshold_2, window_days, min_sample, action_type, level, sort)
VALUES
  ('OVERLOAD',           '景区客流超载',       'OVERLOAD',     0.8000, 1.0000, NULL, NULL, 'DIVERSION', 'HIGH', 1),
  ('RURAL_IDLE',         '景区高位但周边乡村闲置', 'RURAL_IDLE', 0.8000, 0.2000, NULL, NULL, 'DIVERSION', 'HIGH', 2),
  ('LOW_CONVERSION',     '乡村体验到购买转化偏低', 'CONVERSION', 0.1000, NULL,   7,    NULL, 'EXPOSURE',  'MID',  3),
  ('REVIEW_SURGE',       '负面评价激增',       'REVIEW',       0.3000, NULL,   1,    5,    'SERVICE',   'MID',  4),
  ('HEAT_JUMP',          '热度突变',           'HEAT',         0.8000, NULL,   7,    NULL, 'MONITOR',   'MID',  5),
  ('REPURCHASE_DECAY',   '乡村复购衰减',       'REPURCHASE',   0.4000, NULL,   30,   NULL, 'EXPOSURE',  'MID',  6);

-- ------------------------------------------------------------
-- 自检：确认四张表在、列数对、6 条规则种下了。
-- 这里只查结构不查业务数据 —— 客流数据由导入器灌，风险事件由
-- 规则引擎跑出来，都不该由迁移脚本凭空造。
-- ------------------------------------------------------------
SELECT 'poi_visit_stats' AS 表, COUNT(*) AS 列数
  FROM information_schema.columns
 WHERE table_schema = 'hanyou_brain' AND table_name = 'poi_visit_stats'
UNION ALL
SELECT 'risk_rule', COUNT(*)
  FROM information_schema.columns
 WHERE table_schema = 'hanyou_brain' AND table_name = 'risk_rule'
UNION ALL
SELECT 'risk_event', COUNT(*)
  FROM information_schema.columns
 WHERE table_schema = 'hanyou_brain' AND table_name = 'risk_event'
UNION ALL
SELECT 'work_order', COUNT(*)
  FROM information_schema.columns
 WHERE table_schema = 'hanyou_brain' AND table_name = 'work_order';

SELECT rule_id, name, threshold, threshold_2, window_days, min_sample, action_type
  FROM risk_rule ORDER BY sort;
